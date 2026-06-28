package com.autotraces.app

import com.autotraces.app.util.Config
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自动切号自治调度器。
 *
 * 时段模型：
 * 1. **对齐阶段**：账号 row 与时段目标不一致 → 入队切号（涨粉暂停）；
 * 2. **基线阶段**：对齐且登录 OK → 涨粉占满时段空档；
 * 3. **插入阶段**：在 [start_min, end_min) 内到点的发作品/互点等 → 暂停涨粉 → 独占 TikTok → 结束后恢复涨粉。
 */
class AutoSwitchLocalScheduler(
    private val scope: CoroutineScope,
    private val executeOp: suspend (alias: String, deviceId: String, op: DeviceOp) -> Unit,
    private val onPauseGrowth: () -> Unit = {},
    /** 切号/发作品入队前：force-stop TikTok 并 pidof 校验（由 [AppCoordinator] 注入）。 */
    private val onForceStopTikTokForExclusive: suspend (phaseLabel: String) -> Unit = {},
    private val onExclusiveQueueDrained: () -> Unit = {},
    private val onTryResumeGrowth: () -> Unit = {},
    private val onRefreshWeeklyBundle: suspend () -> Boolean = { false },
    private val isPublishExecutionActive: () -> Boolean = { false },
    private val onStalePublishInflight: (slotKey: String) -> Unit = {},
    private val onVerifyPublishInsertionLogin: suspend (
        alias: String,
        deviceId: String,
        targetUsername: String,
        slotKey: String,
    ) -> Boolean = { _, _, _, _ -> true },
    /** OCR 缓存命中时：bundle is_login 仍为真才跳过完整登录保障。 */
    private val onPublishLoginCacheStillValid: suspend (
        alias: String,
        deviceId: String,
        targetUsername: String,
    ) -> Boolean = { _, _, _ -> true },
    /** 高亮已与时段一致但未 verified：登录校验对齐（跳过 clear/restore）。 */
    private val onVerifySlotAlignmentLogin: suspend (
        alias: String,
        deviceId: String,
        targetUsername: String,
        slotKey: String,
        targetRowIndex: Int,
    ) -> Boolean = { _, _, _, _, _ -> false },
    /** 还原前解析本机可用备份（由 [AppCoordinator] 注入）。 */
    private val resolveRestoreBackupArchive: (
        backupName: String,
        backupPathHint: String,
        username: String,
        alias: String,
        deviceId: String,
    ) -> Pair<String, String>? = { _, _, _, _, _ -> null },
    private val slotInsertions: List<AutoSwitchSlotInsertion> = DEFAULT_INSERTIONS,
) : AutoSwitchSlotInsertionHost {
    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")
    private val opQueue = ConcurrentLinkedQueue<DeviceOp>()
    private val busy = AtomicBoolean(false)
    private val tickMutex = Mutex()

    private val cutSwitchQueueOpTypes = setOf("tiktok_backup_and_clear", "tiktok_clear", "tiktok_restore")

    private var tickJob: Job? = null
    private var drainJob: Job? = null

    @Volatile
    private var bundle: AutoSwitchBundle? = null
    private var alias: String = ""
    private var deviceId: String = ""

    private var lastSlotKey: String? = null
    private var publishDoneKey: String? = null
    private var publishAbortedKey: String? = null
    /** 当前插入操作 in-flight：slotKey → kind（如 publish / mutual） */
    @Volatile
    private var insertionInflight: Pair<String, String>? = null
    /** 本 slot 发作品入队前登录保障已通过（避免 publish 窗口内每分钟重复 OCR）。 */
    private var publishLoginVerifiedSlotKey: String? = null
    private var alignOnNextTick = false
    private var cutSwitchInflightSlotKey: String? = null
    /** 轻量登录校验对齐进行中（避免与 tick 并发重复入队）。 */
    private var lightweightVerifyInflightSlotKey: String? = null
    private var lastProcessedScheduleDate: String? = null
    private var lastWeeklyRefreshAttemptMs: Long = 0L

    /** 本分钟 tick 判定：高亮账号是否与当前时段目标一致 */
    @Volatile
    private var accountAlignedWithSlot: Boolean = false

    private data class SlotAlignment(
        val needsCutSwitch: Boolean,
        val verifiedAligned: Boolean,
        val metadataAligned: Boolean,
    )

    fun notifyPublishOutcome(
        slotKey: String,
        completed: Boolean,
        loginFailed: Boolean,
        accountChanged: Boolean,
    ) {
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        clearInsertionInflightIfKind("publish", sk)
        when {
            completed -> {
                publishDoneKey = "$sk|publish"
                PrefsStore.setAutoSwitchPublishDoneKey(publishDoneKey)
            }
            loginFailed || accountChanged -> {
                publishAbortedKey = "$sk|publish_aborted"
                PrefsStore.setAutoSwitchPublishAbortedKey(publishAbortedKey)
            }
            else -> {
                // 素材拉取失败等不可续发场景：本时段不再每分钟重入队
                val accountKey = PrefsStore.getAutoSwitchPublishAccountUsername()
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: alias
                if (PrefsStore.getPublishPostResumeNextIndex(accountKey) <= 0) {
                    publishAbortedKey = "$sk|publish_aborted"
                    PrefsStore.setAutoSwitchPublishAbortedKey(publishAbortedKey)
                    LogCenter.op(
                        alias,
                        "【自动切号】发作品本时段终止（无断点续发），slot=$sk 不再重试",
                    )
                }
            }
        }
    }

    fun requestImmediateTick() {
        scope.launch {
            runCatching { tickOnce() }.onFailure { err ->
                LogCenter.op(alias, "【自动切号】即时调度 tick 异常：${err.message}")
            }
        }
    }

    fun clearCutSwitchInflight() {
        cutSwitchInflightSlotKey = null
    }

    /** 切号失败或前序步骤失败：清除本机已验证对齐证明。 */
    fun invalidateSlotAlignment(slotKey: String, reason: String) {
        val sk = slotKey.trim()
        PrefsStore.clearAutoSwitchVerifiedAlignment()
        if (sk.isEmpty() || publishLoginVerifiedSlotKey == sk) {
            publishLoginVerifiedSlotKey = null
        }
        accountAlignedWithSlot = false
        clearHighlightInBundle()
        if (alias.isNotBlank()) {
            val slotLabel = sk.ifBlank { "-" }
            LogCenter.op(alias, "【自动切号】对齐证明失效 slot=$slotLabel：$reason")
        }
    }

    private fun clearHighlightInBundle() {
        val current = bundle ?: return
        val accounts = current.accounts.orEmpty().map { acc ->
            acc.copy(switch_highlight = "否")
        }
        bundle = current.copy(accounts = accounts)
        syncBundleHolder()
    }

    /** 切号链前序步骤失败（如硬停）时，丢弃同 slot 尚未执行的还原 OP，避免在错误状态下还原。 */
    fun cancelPendingCutSwitchFollowUps(slotKey: String) {
        cancelDuplicatePendingCutSwitchOps(slotKey.trim(), restoreOnly = true)
    }

    /** 对齐已完成或需去重时：移除同 slot 队列中待执行的切号 OP，并清理 ledger queued。 */
    private fun cancelDuplicatePendingCutSwitchOps(slotKey: String, restoreOnly: Boolean = false) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        val removed = mutableListOf<String>()
        val kept = opQueue.filterNot { op ->
            val opSk = op.payload?.auto_switch_slot_key?.trim().orEmpty()
            if (opSk != sk) return@filterNot false
            val match = when {
                restoreOnly -> op.op_type == "tiktok_restore"
                else -> op.op_type in cutSwitchQueueOpTypes
            }
            if (match) removed.add(op.op_id)
            match
        }
        if (kept.size == opQueue.size) return
        opQueue.clear()
        opQueue.addAll(kept)
        if (!restoreOnly) {
            AutoSwitchLedger.cancelQueuedCutSwitchOpsForSlot(sk, "对齐已完成，取消重复切号 OP")
        }
        if (alias.isNotBlank()) {
            val label = if (restoreOnly) "还原" else "切号"
            LogCenter.op(alias, "【自动切号】已取消同 slot 待执行$label OP ${removed.size} 个")
        }
    }

    fun isRunning(): Boolean = tickJob?.isActive == true

    /** 当前是否允许涨粉基线（对齐 + 无独占 OP/插入）。 */
    fun shouldAllowGrowthBaseline(): Boolean {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        if (!accountAlignedWithSlot) return false
        return !shouldBlockGrowthPull()
    }

    /** 切号/插入 OP 队列或插入 in-flight 时涨粉应让路。 */
    fun shouldBlockGrowthPull(): Boolean {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        if (PrefsStore.getAutoSwitchMode() == "exiting") return true
        if (busy.get()) return true
        if (opQueue.isNotEmpty()) return true
        val inflight = insertionInflight ?: return false
        return isInsertionKindStillActive(inflight.second)
    }

    // --- AutoSwitchSlotInsertionHost ---

    override suspend fun enqueueInsertion(op: DeviceOp, slotKey: String, kind: String) {
        beginExclusiveEnqueue("插入操作")
        markInsertionInflight(slotKey, kind)
        opQueue.add(op)
    }

    override fun isInsertionInflight(kind: String, slotKey: String): Boolean {
        val cur = insertionInflight ?: return false
        return cur.first == slotKey && cur.second == kind && isInsertionKindStillActive(kind)
    }

    override fun markInsertionInflight(slotKey: String, kind: String) {
        insertionInflight = slotKey to kind
    }

    override fun graduateNewAccountVideoCount(account: AutoSwitchAccount) {
        graduateNewAccountVideoCountInBundle(account)
    }

    override suspend fun verifyInsertionAccountLoggedIn(
        ctx: AutoSwitchSlotTickContext,
        kind: String,
    ): Boolean {
        if (kind != "publish") return true
        val slotKey = ctx.slotKey.trim()
        val targetUser = ctx.account.username?.trim().orEmpty()
        if (slotKey.isNotEmpty() && publishLoginVerifiedSlotKey == slotKey) {
            if (targetUser.isNotBlank() && onPublishLoginCacheStillValid(alias, deviceId, targetUser)) {
                return true
            }
            publishLoginVerifiedSlotKey = null
            if (alias.isNotBlank()) {
                LogCenter.op(
                    alias,
                    "【自动切号】发作品登录 OCR 缓存失效（bundle is_login 未就绪），重新校验 slot=$slotKey",
                )
            }
        }
        if (targetUser.isBlank() || alias.isBlank() || deviceId.isBlank()) return false
        beginExclusiveEnqueue("发作品入队前登录校验")
        val ok = runCatching {
            onVerifyPublishInsertionLogin(alias, deviceId, targetUser, slotKey)
        }.getOrElse { err ->
            LogCenter.op(alias, "【自动切号】发作品入队前登录校验异常：${err.message}")
            false
        }
        if (ok) {
            publishLoginVerifiedSlotKey = slotKey
        } else {
            releaseExclusivePreemptAfterFailedInsertionVerify()
        }
        return ok
    }

    private fun releaseExclusivePreemptAfterFailedInsertionVerify() {
        onExclusiveQueueDrained()
        onTryResumeGrowth()
    }

    // --- bundle / lifecycle ---

    fun refreshBundle(bundle: AutoSwitchBundle) {
        this.bundle = bundle
        syncBundleHolder()
    }

    /** 跨天仍在本周内：仅刷新内存 bundle；跨周由 [onRefreshWeeklyBundle] 拉新包。 */
    fun refreshWeeklyBundle(bundle: AutoSwitchBundle) {
        val verifiedRi = PrefsStore.getAutoSwitchVerifiedRowIndex()
        val merged = if (verifiedRi >= 0) {
            mergeVerifiedHighlight(bundle, verifiedRi)
        } else {
            bundle
        }
        this.bundle = merged
        syncBundleHolder()
        lastWeeklyRefreshAttemptMs = 0L
        val weekStart = merged.week_start_date?.trim().orEmpty()
        val weekEnd = merged.week_end_date?.trim().orEmpty()
        if (weekStart.isNotBlank() && weekEnd.isNotBlank()) {
            PrefsStore.setAutoSwitchWeekRange(weekStart, weekEnd)
        }
        LogCenter.op(
            alias,
            "【自动切号】已刷新周计划 bundle week=$weekStart~$weekEnd days=${merged.weekly_plan?.day_plans?.size ?: 0}",
        )
    }

    private fun mergeVerifiedHighlight(bundle: AutoSwitchBundle, verifiedRi: Int): AutoSwitchBundle {
        val accounts = bundle.accounts.orEmpty().map { acc ->
            val rowRi = acc.row_index ?: -1
            acc.copy(switch_highlight = if (rowRi == verifiedRi) "是" else "否")
        }
        return bundle.copy(accounts = accounts)
    }

    fun notifyCutSwitchAligned(targetRowIndex: Int, slotKey: String) {
        val ri = targetRowIndex
        val sk = slotKey.trim()
        if (ri < 0 || sk.isEmpty()) {
            accountAlignedWithSlot = false
            return
        }
        cancelDuplicatePendingCutSwitchOps(sk)
        val current = bundle ?: return
        val accounts = current.accounts.orEmpty().map { acc ->
            val rowRi = acc.row_index ?: -1
            acc.copy(switch_highlight = if (rowRi == ri) "是" else "否")
        }
        bundle = current.copy(accounts = accounts)
        cutSwitchInflightSlotKey = null
        PrefsStore.setAutoSwitchVerifiedAlignment(sk, ri)
        accountAlignedWithSlot = true
        syncBundleHolder()
    }

    private fun syncBundleHolder() {
        AutoSwitchBundleHolder.setBundle(bundle)
    }

    private fun graduateNewAccountVideoCountInBundle(targetRow: AutoSwitchAccount) {
        val targetRi = targetRow.row_index ?: return
        val current = bundle ?: return
        val accounts = current.accounts.orEmpty().map { acc ->
            if ((acc.row_index ?: -1) == targetRi) acc.copy(video_count = "0") else acc
        }
        bundle = current.copy(accounts = accounts)
        syncBundleHolder()
    }

    fun start(alias: String, deviceId: String, bundle: AutoSwitchBundle, revision: Int = -1) {
        val rev = if (revision >= 0) revision else PrefsStore.getAutoSwitchBundleRevision()
        val sameRevision = rev > 0 && rev == PrefsStore.getAutoSwitchLastSchedulerRevision() && isRunning()
        if (sameRevision) {
            this.alias = alias.trim()
            this.deviceId = deviceId.trim()
            refreshWeeklyBundle(bundle)
            LogCenter.op(this.alias, "【自动切号】bundle revision=$rev 未变，已刷新周计划（不重启调度器）")
            return
        }
        stop(clearState = false)
        this.alias = alias.trim()
        this.deviceId = deviceId.trim()
        this.bundle = bundle
        syncBundleHolder()
        this.alignOnNextTick = true
        lastSlotKey = PrefsStore.getAutoSwitchLastSlotKey()
        publishDoneKey = PrefsStore.getAutoSwitchPublishDoneKey()
        publishAbortedKey = PrefsStore.getAutoSwitchPublishAbortedKey()
        insertionInflight = null
        recoverStalePublishInflightOnStart()
        accountAlignedWithSlot = false
        lastProcessedScheduleDate = null
        PrefsStore.setAutoSwitchLastSchedulerRevision(rev)
        drainJob = scope.launch { drainQueueLoop() }
        tickJob = scope.launch { tickLoop() }
        LogCenter.op(
            this.alias,
            "【自动切号】本地调度器已启动 schedule=${bundle.schedule_date} accounts=${bundle.accounts?.size ?: 0} revision=$rev",
        )
    }

    fun isExclusiveWorkActive(): Boolean = busy.get() || opQueue.isNotEmpty()

    /**
     * 等待独占 OP（切号/还原/插入）收尾。exit 前必须调用，否则 [stop] 会 cancel drainJob 打断进行中的自动登录。
     */
    suspend fun awaitExclusiveIdle(maxWaitMs: Long = 600_000L): Boolean {
        if (alias.isNotBlank() && isExclusiveWorkActive()) {
            LogCenter.op(
                alias,
                "【自动切号】等待独占 OP 收尾（队列=${opQueue.size} busy=${busy.get()}）…",
            )
        }
        var waited = 0L
        val interval = 250L
        while (isExclusiveWorkActive() && waited < maxWaitMs) {
            delay(interval)
            waited += interval
        }
        val idle = !isExclusiveWorkActive()
        if (!idle && alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】独占 OP 等待超时（${maxWaitMs / 1000}s），仍将停止调度器",
            )
        }
        return idle
    }

    /** 先等独占 OP 结束再 [stop]，避免会话关闭打断切号链。 */
    suspend fun stopAfterIdle(clearState: Boolean = true, maxWaitMs: Long = 600_000L) {
        awaitExclusiveIdle(maxWaitMs)
        stop(clearState)
    }

    fun stop(clearState: Boolean = true) {
        tickJob?.cancel()
        tickJob = null
        drainJob?.cancel()
        drainJob = null
        opQueue.clear()
        busy.set(false)
        if (clearState) {
            bundle = null
            syncBundleHolder()
            alignOnNextTick = false
            lastSlotKey = null
            publishDoneKey = null
            publishAbortedKey = null
            insertionInflight = null
            cutSwitchInflightSlotKey = null
            lightweightVerifyInflightSlotKey = null
            publishLoginVerifiedSlotKey = null
            accountAlignedWithSlot = false
            PrefsStore.clearAutoSwitchVerifiedAlignment()
            PrefsStore.setAutoSwitchPublishDoneKey(null)
            PrefsStore.setAutoSwitchPublishAbortedKey(null)
        }
    }

    private suspend fun tickLoop() {
        if (alignOnNextTick) {
            alignOnNextTick = false
            runCatching { tickOnce() }.onFailure { err ->
                LogCenter.op(alias, "【自动切号】启动即时 tick 异常：${err.message}")
            }
        }
        alignToNextBeijingMinuteBoundary()
        while (scope.coroutineContext.isActive) {
            runCatching { tickOnce() }.onFailure { err ->
                LogCenter.op(alias, "【自动切号】本地调度 tick 异常：${err.message}")
            }
            delay(msUntilNextBeijingMinuteBoundary())
        }
    }

    private suspend fun alignToNextBeijingMinuteBoundary() {
        val waitMs = msUntilNextBeijingMinuteBoundary()
        if (waitMs in 1..59_999) {
            delay(waitMs)
        }
        runCatching { tickOnce() }
    }

    private fun msUntilNextBeijingMinuteBoundary(): Long {
        val cal = Calendar.getInstance(beijingTz)
        val sec = cal.get(Calendar.SECOND)
        val ms = cal.get(Calendar.MILLISECOND)
        return (60_000L - (sec * 1000L + ms)).coerceIn(1L, 60_000L)
    }

    private suspend fun drainQueueLoop() {
        while (scope.coroutineContext.isActive) {
            val op = opQueue.poll()
            if (op == null) {
                delay(500)
                continue
            }
            busy.set(true)
            runCatching {
                executeOp(alias, deviceId, op)
            }.onFailure { err ->
                LogCenter.op(alias, "【自动切号】本地 OP 执行失败 ${op.op_type}：${err.message}")
                AutoSwitchLedger.recordOpFailure(op, err.message ?: "执行异常")
            }
            busy.set(false)
            if (opQueue.isEmpty()) {
                onExclusiveQueueDrained()
            }
            if (opQueue.isEmpty() && shouldAllowGrowthBaseline()) {
                onTryResumeGrowth()
            }
        }
    }

    private suspend fun tryRefreshWeeklyBundle(reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastWeeklyRefreshAttemptMs < WEEKLY_REFRESH_COOLDOWN_MS) {
            return false
        }
        lastWeeklyRefreshAttemptMs = now
        LogCenter.op(alias, "【自动切号】$reason，向云端拉取周计划 bundle…")
        return onRefreshWeeklyBundle()
    }

    private suspend fun tickOnce() {
        tickMutex.withLock {
            tickOnceUnlocked()
        }
    }

    private suspend fun tickOnceUnlocked() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PublishPostGrowthGate.isBlockingGrowth()) {
            LogCenter.op(alias, "【自动切号】调度暂缓：发作品独占发布中")
            return
        }
        val currentBundle = bundle ?: run {
            accountAlignedWithSlot = false
            LogCenter.op(alias, "【自动切号】调度跳过：bundle 为空")
            return
        }

        val scheduleDate = beijingDateStr()
        if (lastProcessedScheduleDate != null && lastProcessedScheduleDate != scheduleDate) {
            onPauseGrowth()
            cutSwitchInflightSlotKey = null
            lightweightVerifyInflightSlotKey = null
            publishDoneKey = null
            publishAbortedKey = null
            insertionInflight = null
            publishLoginVerifiedSlotKey = null
            PrefsStore.clearAutoSwitchVerifiedAlignment()
            PrefsStore.setAutoSwitchPublishDoneKey(null)
            PrefsStore.setAutoSwitchPublishAbortedKey(null)
            LogCenter.op(
                alias,
                "【自动切号】日期切换 $lastProcessedScheduleDate → $scheduleDate，已暂停涨粉并清理昨日发作品状态",
            )
        }
        lastProcessedScheduleDate = scheduleDate

        if (!currentBundle.isDateInWeekRange(scheduleDate)) {
            accountAlignedWithSlot = false
            if (tryRefreshWeeklyBundle("今日 $scheduleDate 不在当前周计划内")) {
                return
            }
            LogCenter.op(alias, "【自动切号】周计划刷新冷却中或失败，本分钟 tick 暂缓")
            return
        }

        val plan = currentBundle.dailyPlanForDate(scheduleDate)
        if (plan == null) {
            accountAlignedWithSlot = false
            if (tryRefreshWeeklyBundle("周计划缺少今日 $scheduleDate 的 daily_plan")) {
                return
            }
            LogCenter.op(alias, "【自动切号】调度跳过：周计划缺少今日 $scheduleDate 的 daily_plan")
            return
        }
        val slots = plan.slots.orEmpty()
        if (slots.isEmpty()) {
            accountAlignedWithSlot = false
            LogCenter.op(alias, "【自动切号】调度跳过：时段 slots 为空")
            return
        }

        val minuteOfDay = beijingMinuteOfDay()
        val targetSlot = resolveTargetSlot(plan, minuteOfDay) ?: run {
            accountAlignedWithSlot = false
            LogCenter.op(alias, "【自动切号】调度跳过：当前分钟 $minuteOfDay 无匹配时段")
            return
        }

        if (!AutoSwitchSlotWindow.isWithinSlot(minuteOfDay, targetSlot)) {
            accountAlignedWithSlot = false
            return
        }

        val slotIndex = resolveSlotIndex(plan, minuteOfDay)
        val slotKey = "$scheduleDate|$slotIndex"

        refreshInsertionSlotState(slotKey, minuteOfDay, targetSlot)
        maybeSweepOrphanedPublishState(slotKey)

        val accounts = currentBundle.accounts.orEmpty()
        val targetRi = targetSlot.row_index ?: -1
        val targetRow = accounts.firstOrNull { (it.row_index ?: -1) == targetRi } ?: run {
            accountAlignedWithSlot = false
            LogCenter.op(alias, "【自动切号】调度跳过：时段目标 row_index=$targetRi 不在 bundle.accounts")
            return
        }
        val alignment = evaluateSlotAlignment(slotKey, targetRi, accounts)
        val needsCutSwitch = alignment.needsCutSwitch
        accountAlignedWithSlot = alignment.verifiedAligned

        if (needsCutSwitch && alignment.metadataAligned && !alignment.verifiedAligned) {
            LogCenter.op(
                alias,
                "【自动切号】高亮与时段一致但未验证对齐，仍入队切号 slot=$slotKey targetRi=$targetRi",
            )
        }

        val busyReason = if (needsCutSwitch) {
            deviceBusyReasonForCutSwitch()
        } else {
            deviceBusyReasonForInsertionScheduling()
        }
        if (busyReason != null) {
            LogCenter.op(alias, "【自动切号】调度暂缓：$busyReason")
            return
        }

        if (!needsCutSwitch) {
            cutSwitchInflightSlotKey = null
            if (lastSlotKey != null && !lastSlotKey!!.startsWith("$scheduleDate|")) {
                publishDoneKey = null
                publishAbortedKey = null
                PrefsStore.setAutoSwitchPublishDoneKey(null)
                PrefsStore.setAutoSwitchPublishAbortedKey(null)
            }
            lastSlotKey = slotKey
            PrefsStore.setAutoSwitchLastSlotKey(slotKey)
            val enqueuedInsertion = scheduleSlotInsertions(
                currentBundle, targetSlot, targetRow, scheduleDate, slotKey, minuteOfDay,
            )
            tryResumeGrowthBaseline(enqueuedInsertion = enqueuedInsertion)
            return
        }

        val targetUser = targetRow.username?.trim().orEmpty()
        if (hasCutSwitchWorkInProgress(slotKey, targetRi)) {
            LogCenter.op(alias, "【自动切号】时段 $slotKey 切号/对齐验证进行中，等待完成")
            return
        }

        val highlightedForSource = resolveHighlightedRowStrict(accounts) ?: resolveHighlightedRow(accounts)
        val cutEnqueued = when {
            highlightedForSource == null -> {
                if (accounts.size <= 1) {
                    val soleRi = accounts.firstOrNull()?.row_index ?: -1
                    if (soleRi != targetRi) {
                        LogCenter.op(alias, "【自动切号】调度跳过：无高亮且仅 1 个账号，无法切号")
                        return
                    }
                    if (tryLightweightSlotAlignmentVerify(targetRow, slotKey)) {
                        tryResumeGrowthBaseline(enqueuedInsertion = false)
                        return
                    }
                    enqueueBootstrapCutSwitch(currentBundle, targetRow, slotKey)
                } else {
                    enqueueBootstrapCutSwitch(currentBundle, targetRow, slotKey)
                }
            }
            (highlightedForSource.row_index ?: -1) == targetRi -> {
                if (tryLightweightSlotAlignmentVerify(targetRow, slotKey)) {
                    tryResumeGrowthBaseline(enqueuedInsertion = false)
                    return
                }
                false
            }
            else -> {
                val sourceUser = highlightedForSource.username?.trim().orEmpty()
                LogCenter.op(
                    alias,
                    "【自动切号】时段 $slotKey 应对齐 $targetUser（当前 $sourceUser），入队切号",
                )
                enqueueCutSwitch(currentBundle, highlightedForSource, targetRow, slotKey)
            }
        }
        if (cutEnqueued) {
            cutSwitchInflightSlotKey = slotKey
        }
    }

    private suspend fun scheduleSlotInsertions(
        bundle: AutoSwitchBundle,
        targetSlot: AutoSwitchPlanSlot,
        targetRow: AutoSwitchAccount,
        scheduleDate: String,
        slotKey: String,
        minuteOfDay: Int,
    ): Boolean {
        val ctx = AutoSwitchSlotTickContext(
            alias = alias,
            deviceId = deviceId,
            bundle = bundle,
            scheduleDate = scheduleDate,
            slotKey = slotKey,
            slot = targetSlot,
            account = targetRow,
            minuteOfDay = minuteOfDay,
        )
        for (insertion in slotInsertions) {
            if (insertion.trySchedule(ctx, this)) {
                return true
            }
        }
        return false
    }

    private fun tryResumeGrowthBaseline(enqueuedInsertion: Boolean) {
        if (enqueuedInsertion || !accountAlignedWithSlot) return
        if (!shouldAllowGrowthBaseline()) return
        onTryResumeGrowth()
    }

    private fun enqueueExclusiveOp(op: DeviceOp) {
        onPauseGrowth()
        opQueue.add(op)
    }

    private fun hasCutSwitchWorkInProgress(slotKey: String, targetRi: Int): Boolean {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return false
        if (lightweightVerifyInflightSlotKey == sk) return true
        if (opQueue.any { isCutSwitchOpForSlot(it, sk) }) return true
        if (AutoSwitchLedger.isCutSwitchSlotInflight(sk)) return true
        if (busy.get() && cutSwitchInflightSlotKey == sk) return true
        return false
    }

    private fun isCutSwitchOpForSlot(op: DeviceOp, slotKey: String): Boolean {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return false
        return op.payload?.auto_switch_slot_key?.trim() == sk &&
            op.op_type in cutSwitchQueueOpTypes
    }

    /**
     * 高亮已与时段目标一致：仅做登录校验并写 verified，不做 pm clear / 还原。
     * 成功返回 true；失败返回 false，下分钟 tick 重试。
     */
    private suspend fun tryLightweightSlotAlignmentVerify(
        targetRow: AutoSwitchAccount,
        slotKey: String,
    ): Boolean {
        val targetRi = targetRow.row_index ?: return false
        val targetUser = targetRow.username?.trim().orEmpty()
        if (targetUser.isBlank()) return false
        val sk = slotKey.trim()
        if (sk.isEmpty()) return false
        if (hasCutSwitchWorkInProgress(sk, targetRi)) return false

        lightweightVerifyInflightSlotKey = sk
        cutSwitchInflightSlotKey = sk
        try {
            onPauseGrowth()
            LogCenter.op(
                alias,
                "【自动切号】时段 $sk 高亮已是 $targetUser 但未 verified，执行登录校验对齐（跳过 clear/restore）",
            )
            val loginOk = onVerifySlotAlignmentLogin(alias, deviceId, targetUser, sk, targetRi)
            if (!loginOk) {
                LogCenter.op(alias, "【自动切号】时段 $sk 登录校验未通过，下分钟重试")
                return false
            }
            AutoSwitchLedger.recordCutSwitchSlotVerified(sk, targetRi, targetUser)
            notifyCutSwitchAligned(targetRi, sk)
            LogCenter.op(alias, "【自动切号】时段 $sk 登录校验对齐完成 @$targetUser")
            return true
        } finally {
            if (lightweightVerifyInflightSlotKey == sk) {
                lightweightVerifyInflightSlotKey = null
            }
            if (cutSwitchInflightSlotKey == sk && !hasCutSwitchWorkInProgress(sk, targetRi)) {
                cutSwitchInflightSlotKey = null
            }
        }
    }

    /** 切号/发作品入队前：暂停涨粉拉取，等待当前 TikTok 步骤收尾（超时后仍入队）。 */
    private suspend fun beginExclusiveEnqueue(forceEnqueueLabel: String) {
        onPauseGrowth()
        onForceStopTikTokForExclusive("切号入队前")
        awaitTikTokExclusiveIdle(forceEnqueueLabel = forceEnqueueLabel)
        // 等待涨粉收尾期间 TikTok 可能被再次拉起，收尾后再关一次
        onForceStopTikTokForExclusive("切号入队前（涨粉收尾后）")
    }

    private suspend fun awaitTikTokExclusiveIdle(
        maxMs: Long = 20_000L,
        forceEnqueueLabel: String = "操作",
    ) {
        var waited = 0L
        while (ExecutionScope.isActive() && waited < maxMs) {
            delay(250)
            waited += 250
            if (waited > 0L && waited % 2_000L == 0L) {
                onForceStopTikTokForExclusive("切号等待涨粉收尾")
            }
        }
        if (ExecutionScope.isActive()) {
            LogCenter.op(
                alias,
                "【自动切号】等待 TikTok 独占任务收尾超时（${maxMs / 1000}s），仍将${forceEnqueueLabel}入队",
            )
        }
    }

    private fun isInsertionKindStillActive(kind: String): Boolean = when (kind) {
        "publish" -> isPublishInsertionStillActive()
        else -> insertionInflight?.second == kind
    }

    private fun recoverStalePublishInflightOnStart() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (isPublishExecutionActive()) return
        val sk = PrefsStore.getAutoSwitchPublishSlotKey()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: lastSlotKey?.trim().orEmpty()
        if (sk.isBlank()) return
        if (!AutoSwitchLedger.isPublishSlotInflight(sk)) return
        onStalePublishInflight(sk)
        LogCenter.op(alias, "【自动切号】启动时发现残留发作品 in-flight，已清理：slot=$sk")
    }

    private fun isAutoSwitchPublishLocallyActive(): Boolean {
        if (isPublishExecutionActive()) return true
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        if (PrefsStore.isAutoSwitchPublishTask()) return true
        return PrefsStore.getPublishPostSource() == "auto_switch" && PrefsStore.isPublishPostPending()
    }

    /**
     * 自治发作品：以发作品协程是否在跑为准；prefs/ledger 仅作辅助。
     * 协程已结束但 prefs 仍 pending 时视为非 active，由 [refreshInsertionSlotState] 触发残留清理。
     */
    private fun isPublishInsertionStillActive(): Boolean {
        if (isPublishExecutionActive()) return true
        val sk = insertionInflight?.first?.trim().orEmpty()
        if (sk.isNotBlank()) {
            if (AutoSwitchLedger.isPublishSlotDone(sk) || AutoSwitchLedger.isPublishSlotAborted(sk)) {
                return false
            }
        }
        if (!PrefsStore.isAutoSwitchAutonomousMode()) {
            return PrefsStore.isPublishPostPending()
        }
        return false
    }

    private fun maybeSweepOrphanedPublishState(slotKey: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (isPublishExecutionActive()) return
        if (!isAutoSwitchPublishLocallyActive()) return
        val sk = PrefsStore.getAutoSwitchPublishSlotKey()?.trim().orEmpty()
            .ifBlank { insertionInflight?.first?.trim().orEmpty() }
            .ifBlank { slotKey.trim() }
        if (sk.isBlank()) return
        onStalePublishInflight(sk)
        LogCenter.op(alias, "【自动切号】发作品协程已结束，清理残留 prefs/ledger：slot=$sk")
    }

    private fun clearInsertionInflightIfKind(kind: String, slotKey: String) {
        val cur = insertionInflight ?: return
        if (cur.first == slotKey && cur.second == kind) {
            insertionInflight = null
        }
    }

    private fun evaluateSlotAlignment(
        slotKey: String,
        targetRi: Int,
        accounts: List<AutoSwitchAccount>,
    ): SlotAlignment {
        val highlightedStrict = resolveHighlightedRowStrict(accounts)
        val metadataAligned = (highlightedStrict?.row_index ?: -1) == targetRi

        // 先只按高亮是否对齐判断；下面这些 verified / ledger 条件暂时注释掉，后续再恢复。
        // val verifiedAligned =
        //     PrefsStore.isAutoSwitchVerifiedAligned(slotKey, targetRi) &&
        //         AutoSwitchLedger.isCutSwitchSlotVerified(slotKey, targetRi) &&
        //         !AutoSwitchLedger.isCutSwitchSlotInflight(slotKey)
        // if (verifiedAligned) {
        //     return SlotAlignment(
        //         needsCutSwitch = false,
        //         verifiedAligned = true,
        //         metadataAligned = true,
        //     )
        // }
        return SlotAlignment(
            needsCutSwitch = !metadataAligned,
            verifiedAligned = metadataAligned,
            metadataAligned = metadataAligned,
        )
    }

    /** 对齐判定专用：只信 switch_highlight，不用 logContext 兜底。 */
    private fun resolveHighlightedRowStrict(accounts: List<AutoSwitchAccount>): AutoSwitchAccount? {
        val highlighted = accounts.filter { normalizeSwitchHighlight(it.switch_highlight) }
        if (highlighted.size == 1) return highlighted[0]
        if (accounts.size == 1) return accounts[0]
        return null
    }

    private fun resolveHighlightedRow(accounts: List<AutoSwitchAccount>): AutoSwitchAccount? {
        val highlighted = accounts.filter { normalizeSwitchHighlight(it.switch_highlight) }
        if (highlighted.size == 1) return highlighted[0]
        if (highlighted.size > 1 && alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】bundle 内多个 switch_highlight=是（${highlighted.size}），改用当前登录账号推断")
        }
        if (accounts.size == 1) return accounts[0]
        val logUser = PrefsStore.getLogContextUsername()?.trim()?.lowercase()
        if (!logUser.isNullOrBlank()) {
            accounts.firstOrNull { it.username?.trim()?.lowercase() == logUser }?.let { return it }
        }
        if (highlighted.isNotEmpty()) {
            return highlighted.first()
        }
        return null
    }

    /**
     * 切号调度：仅阻塞本地 OP 队列/in-flight。
     * 涨粉基线运行中 [ExecutionScope] 为 active 是正常态，不在此拦截——到时段边界时由
     * [enqueueCutSwitch] / [enqueueBootstrapCutSwitch] 主动 pause 涨粉并等待后再入队。
     */
    private fun deviceBusyReasonForCutSwitch(): String? {
        if (busy.get()) return "本地 OP 执行中（切号/还原/插入，本分钟 tick 暂缓）"
        if (opQueue.isNotEmpty()) return "本地 OP 队列非空（${opQueue.size}）"
        return null
    }

    /**
     * 插入调度：仅阻塞本地 OP 队列/in-flight。
     * 涨粉基线运行中 [ExecutionScope] 为 active 是正常态，不在此拦截——到点发作品时由
     * [enqueueInsertion] 主动 pause 涨粉并 [awaitTikTokExclusiveIdle] 后再入队。
     */
    private fun deviceBusyReasonForInsertionScheduling(): String? {
        if (busy.get()) return "本地 OP 执行中（切号/还原/插入，本分钟 tick 暂缓）"
        if (opQueue.isNotEmpty()) return "本地 OP 队列非空（${opQueue.size}）"
        insertionInflight?.let { (sk, kind) ->
            if (isInsertionKindStillActive(kind)) {
                return "插入操作 in-flight：$kind slot=$sk"
            }
        }
        return null
    }

    private fun refreshInsertionSlotState(slotKey: String, minuteOfDay: Int, targetSlot: AutoSwitchPlanSlot) {
        val endMin = targetSlot.end_min ?: 1440
        insertionInflight?.let { (sk, kind) ->
            if (sk != slotKey) {
                insertionInflight = null
            } else if (!isInsertionKindStillActive(kind)) {
                if (kind == "publish" && PrefsStore.isAutoSwitchAutonomousMode()) {
                    val doneKey = "$sk|publish"
                    val slotAlreadyDone =
                        PrefsStore.getAutoSwitchPublishDoneKey() == doneKey ||
                            AutoSwitchLedger.isPublishSlotDone(sk)
                    if (!slotAlreadyDone) {
                        val hasOrphanedPublishState =
                            AutoSwitchLedger.isPublishSlotInflight(sk) || isAutoSwitchPublishLocallyActive()
                        if (hasOrphanedPublishState) {
                            onStalePublishInflight(sk)
                        }
                    }
                    if (!isPublishExecutionActive() && !isAutoSwitchPublishLocallyActive()) {
                        LogCenter.op(alias, "【自动切号】清理残留发作品 in-flight：slot=$sk")
                    }
                }
                insertionInflight = null
            }
        }
        if (minuteOfDay >= endMin) {
            insertionInflight = null
        }
        if (publishDoneKey != null && !publishDoneKey!!.startsWith("$slotKey|")) {
            publishDoneKey = null
        }
        if (publishAbortedKey != null && !publishAbortedKey!!.startsWith("$slotKey|")) {
            publishAbortedKey = null
        }
    }

    private suspend fun enqueueBootstrapCutSwitch(
        bundle: AutoSwitchBundle,
        targetRow: AutoSwitchAccount,
        slotKey: String,
    ): Boolean {
        val targetRi = targetRow.row_index ?: return false
        val targetUser = targetRow.username?.trim().orEmpty()
        if (targetUser.isBlank()) return false
        if (hasCutSwitchWorkInProgress(slotKey, targetRi)) {
            LogCenter.op(alias, "【自动切号】时段 $slotKey 已有切号 OP 进行中，跳过重复入队")
            return false
        }
        beginExclusiveEnqueue("切号")
        val pkg = bundle.run_config?.package_name?.trim().orEmpty().ifBlank { Config.TIKTOK_PACKAGE_NAME }
        val aliasClean = bundle.device_alias?.trim().orEmpty().ifBlank { alias }
        val targetBackup = targetRow.backup_name?.trim().orEmpty()
        val resolved = resolveTargetRestoreBackup(targetRow, targetUser, aliasClean)
        if (targetBackup.isNotEmpty() && targetBackup != "-" && resolved == null) {
            logCutSwitchMissingBackupUseAutoLogin(slotKey, targetUser, File(targetBackup).name)
        }
        val canRestore = resolved != null
        val cutMode = if (canRestore) "clear_source_for_restore" else "exclusive_activate_target"

        val clearOpId = newLocalOpId("clear")
        val clearPayload = DeviceOpPayload(
            device_id = deviceId,
            alias = aliasClean,
            package_name = pkg,
            cut_switch_after_backup = cutMode,
            cut_switch_target_row_index = targetRi,
            source = "auto_switch",
            auto_switch_slot_key = slotKey,
        )
        val clearOp = DeviceOp(
            op_id = clearOpId,
            op_type = "tiktok_clear",
            payload = clearPayload,
        )
        AutoSwitchLedger.recordCutSwitchQueued(
            clearOpId, "tiktok_clear", slotKey, targetUser, clearPayload,
        )
        enqueueExclusiveOp(clearOp)
        LogCenter.op(alias, "【自动切号】已入队初始对齐清理 → $targetUser")

        if (canRestore) {
            val (backupName, backupPath) = resolved!!
            val restoreOpId = newLocalOpId("restore")
            val restorePayload = DeviceOpPayload(
                device_id = deviceId,
                alias = aliasClean,
                package_name = pkg,
                backup_name = backupName,
                backup_path = backupPath,
                username = targetUser,
                skip_pm_clear = true,
                restore_highlight_row_index = targetRi,
                source = "auto_switch",
                auto_switch_slot_key = slotKey,
            )
            AutoSwitchLedger.recordCutSwitchQueued(
                restoreOpId, "tiktok_restore", slotKey, targetUser, restorePayload,
            )
            enqueueExclusiveOp(
                DeviceOp(
                    op_id = restoreOpId,
                    op_type = "tiktok_restore",
                    payload = restorePayload,
                ),
            )
            LogCenter.op(alias, "【自动切号】已入队初始对齐还原 → $targetUser")
        }
        return true
    }

    private suspend fun enqueueCutSwitch(
        bundle: AutoSwitchBundle,
        sourceRow: AutoSwitchAccount,
        targetRow: AutoSwitchAccount,
        slotKey: String,
    ): Boolean {
        val sourceRi = sourceRow.row_index ?: return false
        val targetRi = targetRow.row_index ?: return false
        if (sourceRi == targetRi) return false
        val sourceUser = sourceRow.username?.trim().orEmpty()
        val targetUser = targetRow.username?.trim().orEmpty()
        if (sourceUser.isBlank() || targetUser.isBlank()) return false
        if (hasCutSwitchWorkInProgress(slotKey, targetRi)) {
            LogCenter.op(alias, "【自动切号】时段 $slotKey 已有切号 OP 进行中，跳过重复入队")
            return false
        }
        beginExclusiveEnqueue("切号")

        val pkg = bundle.run_config?.package_name?.trim().orEmpty().ifBlank { Config.TIKTOK_PACKAGE_NAME }
        val aliasClean = bundle.device_alias?.trim().orEmpty().ifBlank { alias }
        val targetBackup = targetRow.backup_name?.trim().orEmpty()
        val resolved = resolveTargetRestoreBackup(targetRow, targetUser, aliasClean)
        if (targetBackup.isNotEmpty() && targetBackup != "-" && resolved == null) {
            logCutSwitchMissingBackupUseAutoLogin(slotKey, targetUser, File(targetBackup).name)
        }
        val canRestore = resolved != null

        val backupOpId = newLocalOpId("backup")
        val backupPayload = DeviceOpPayload(
            device_id = deviceId,
            alias = aliasClean,
            package_name = pkg,
            backup_record_id = sourceRi,
            username = sourceUser,
            previous_backup_path = sourceRow.backup_path?.trim()?.takeIf { it.isNotEmpty() },
            source = "auto_switch",
            cut_switch_after_backup = if (canRestore) "clear_source_for_restore" else "exclusive_activate_target",
            cut_switch_target_row_index = targetRi,
            auto_switch_slot_key = slotKey,
        )
        AutoSwitchLedger.recordCutSwitchQueued(
            backupOpId, "tiktok_backup_and_clear", slotKey, sourceUser, backupPayload,
        )
        enqueueExclusiveOp(
            DeviceOp(
                op_id = backupOpId,
                op_type = "tiktok_backup_and_clear",
                payload = backupPayload,
            ),
        )
        LogCenter.op(alias, "【自动切号】已入队切号备份 $sourceUser → $targetUser")

        if (canRestore) {
            val (backupName, backupPath) = resolved!!
            val restoreOpId = newLocalOpId("restore")
            val restorePayload = DeviceOpPayload(
                device_id = deviceId,
                alias = aliasClean,
                package_name = pkg,
                backup_name = backupName,
                backup_path = backupPath,
                username = targetUser,
                skip_pm_clear = true,
                restore_highlight_row_index = targetRi,
                backup_record_id = sourceRi,
                source = "auto_switch",
                auto_switch_slot_key = slotKey,
            )
            AutoSwitchLedger.recordCutSwitchQueued(
                restoreOpId, "tiktok_restore", slotKey, targetUser, restorePayload,
            )
            enqueueExclusiveOp(
                DeviceOp(
                    op_id = restoreOpId,
                    op_type = "tiktok_restore",
                    payload = restorePayload,
                ),
            )
            LogCenter.op(alias, "【自动切号】已入队切号还原 → $targetUser")
        }
        return true
    }

    private fun normalizeSwitchHighlight(raw: String?): Boolean =
        raw?.trim() == "是"

    private fun resolveTargetRestoreBackup(
        targetRow: AutoSwitchAccount,
        targetUser: String,
        aliasClean: String,
    ): Pair<String, String>? {
        val cloudRaw = targetRow.backup_name?.trim().orEmpty()
        if (cloudRaw.isEmpty() || cloudRaw == "-") return null
        val backupName = File(cloudRaw).name
        val pathHint = targetRow.backup_path?.trim().orEmpty().ifBlank { cloudRaw }
        return resolveRestoreBackupArchive(backupName, pathHint, targetUser, aliasClean, deviceId)
    }

    private fun logCutSwitchMissingBackupUseAutoLogin(
        slotKey: String,
        targetUser: String,
        cloudBackupName: String,
    ) {
        AutoSwitchLedger.recordCutSwitchMissingBackup(
            username = targetUser,
            slotKey = slotKey,
            cloudBackupName = cloudBackupName,
            phase = "enqueue",
        )
        LogCenter.op(
            alias,
            "【自动切号】目标 @$targetUser 备份包本机不可用，跳过还原，备份清理后自动登录",
        )
    }

    private fun resolveSlotIndex(plan: AutoSwitchDailyPlan, minuteOfDay: Int): Int {
        val slots = plan.slots.orEmpty()
        if (slots.isEmpty()) return 0
        val minute = minuteOfDay.coerceIn(0, 1439)
        for (slot in slots) {
            val start = slot.start_min ?: 0
            val end = slot.end_min ?: 0
            if (start <= minute && minute < end) {
                return slot.slot_index ?: 0
            }
        }
        return slots.last().slot_index ?: slots.size - 1
    }

    private fun resolveTargetSlot(plan: AutoSwitchDailyPlan, minuteOfDay: Int): AutoSwitchPlanSlot? {
        val idx = resolveSlotIndex(plan, minuteOfDay)
        return plan.slots.orEmpty().firstOrNull { (it.slot_index ?: -1) == idx }
            ?: plan.slots.orEmpty().getOrNull(idx.coerceAtMost(plan.slots.orEmpty().size - 1))
    }

    private fun beijingMinuteOfDay(): Int {
        val cal = Calendar.getInstance(beijingTz)
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun beijingDateStr(): String {
        val cal = Calendar.getInstance(beijingTz)
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", y, m, d)
    }

    private fun newLocalOpId(prefix: String): String =
        "local-as-$prefix-${System.currentTimeMillis()}-${Random.nextInt(1000)}"

    companion object {
        private const val WEEKLY_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
        private val DEFAULT_INSERTIONS: List<AutoSwitchSlotInsertion> = listOf(
            PublishPostSlotInsertion(),
            // 未来互点：MutualClickSlotInsertion()，读取 slot.auto_mutual_minute
        )
    }
}
