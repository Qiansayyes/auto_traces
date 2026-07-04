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
 * 自动切号自治调度器：读本地 [AutoSwitchLocalPlan] 计划文件，按时间执行下一 pending 任务。
 *
 * 每个 slot：切号（backup → clear → restore/自动登录）→ 涨粉 → 到点发作品 / 引流互点。
 * 切号三步与插入任务状态持久化在计划文件中，支持断点续跑。
 */
class AutoSwitchLocalScheduler(
    private val scope: CoroutineScope,
    private val executeOp: suspend (alias: String, deviceId: String, op: DeviceOp) -> Unit,
    private val onPauseGrowth: () -> Unit = {},
    private val onForceStopTikTokForExclusive: suspend (phaseLabel: String) -> Unit = {},
    private val onExclusiveQueueDrained: () -> Unit = {},
    private val onTryResumeGrowth: () -> Unit = {},
    private val onRefreshWeeklyBundle: suspend () -> Boolean = { false },
    private val isPublishExecutionActive: () -> Boolean = { false },
    private val isMutualExecutionActive: () -> Boolean = { false },
    private val onStalePublishInflight: (slotKey: String) -> Unit = {},
    private val onSlotTransition: (prevSlotKey: String, newSlotKey: String) -> Unit = { _, _ -> },
    private val resolveRestoreBackupArchive: (
        backupName: String,
        backupPathHint: String,
        username: String,
        alias: String,
        deviceId: String,
    ) -> Pair<String, String>? = { _, _, _, _, _ -> null },
    private val verifyInsertionLogin: suspend (AutoSwitchSlotTickContext, String) -> Boolean =
        { _, _ -> true },
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
    private var sessionId: String = ""

    private var lastSlotKey: String? = null
    @Volatile
    private var insertionInflight: Pair<String, String>? = null
    private var alignOnNextTick = false
    private var cutSwitchInflightSlotKey: String? = null
    private var lastProcessedScheduleDate: String? = null
    private var lastWeeklyRefreshAttemptMs: Long = 0L
    private var pendingCutSwitchSlotKey: String? = null
    private var lastTickSkipLogKey: String? = null
    private var lastTickSkipLogMs: Long = 0L

    fun notifyPublishOutcome(
        slotKey: String,
        completed: Boolean,
        loginFailed: Boolean,
        accountChanged: Boolean,
    ) {
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        val state = when {
            completed -> PlanStepState.DONE
            loginFailed || accountChanged -> PlanStepState.FAILED
            else -> {
                val accountKey = AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim().orEmpty()
                    .ifBlank { PrefsStore.getLogContextUsername()?.trim().orEmpty() }
                    .ifBlank { alias }
                val resumeIdx = PrefsStore.getPublishPostResumeNextIndex(accountKey)
                val materialReady = PrefsStore.isPublishPostMaterialReady()
                if (resumeIdx <= 0 && !materialReady) {
                    PlanStepState.FAILED
                } else {
                    PlanStepState.PENDING
                }
            }
        }
        if (state != PlanStepState.PENDING) {
            clearInsertionInflightIfKind("publish", sk)
        }
        AutoSwitchLocalPlanStore.markInsertionState(sk, "publish", state)
        if (state == PlanStepState.FAILED && alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】发作品本时段终止 slot=$sk")
        }
        maybeResumePendingCutSwitch()
    }

    fun notifyMutualOutcome(slotKey: String, completed: Boolean) {
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        clearInsertionInflightIfKind("mutual_traffic", sk)
        AutoSwitchLocalPlanStore.markInsertionState(
            sk,
            "mutual_traffic",
            if (completed) PlanStepState.DONE else PlanStepState.FAILED,
        )
        maybeResumePendingCutSwitch()
    }

    @Volatile
    private var forceLogNextTick = false

    fun hasBundleLoaded(): Boolean = bundle != null

    fun requestImmediateTick(forceLog: Boolean = false) {
        scope.launch {
            if (forceLog) forceLogNextTick = true
            try {
                runCatching { tickOnce() }.onFailure { err ->
                    LogCenter.op(alias, "【自动切号】即时调度 tick 异常：${err.message}")
                }
            } finally {
                forceLogNextTick = false
            }
        }
    }

    fun clearCutSwitchInflight() {
        cutSwitchInflightSlotKey = null
    }

    fun clearPublishInsertionInflight(slotKey: String) {
        clearInsertionInflightIfKind("publish", slotKey.trim())
    }

    fun clearSlotPublishAborted(slotKey: String) {
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        val slot = AutoSwitchLocalPlanStore.slot(sk) ?: return
        if (slot.publish.state == PlanStepState.FAILED) {
            AutoSwitchLocalPlanStore.markInsertionState(sk, "publish", PlanStepState.PENDING)
        }
    }

    fun invalidateSlotAlignment(slotKey: String, reason: String) {
        val sk = slotKey.trim()
        AutoSwitchLocalPlanStore.resetCutSwitch(sk)
        pendingCutSwitchSlotKey = sk.ifBlank { pendingCutSwitchSlotKey }
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】切号计划重置 slot=${sk.ifBlank { "-"}}：$reason")
        }
    }

    /**
     * 还原/自动登录失败：保留备份+清理进度，仅重置还原/登录步（TikTok 已清理，禁止重跑备份+清理）。
     */
    fun invalidateSlotAlignmentRestoreOnly(slotKey: String, reason: String) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        AutoSwitchLocalPlanStore.resetCutSwitchRestoreOrLogin(sk, reason)
        cancelPendingCutSwitchFollowUps(sk)
        cutSwitchInflightSlotKey = null
        pendingCutSwitchSlotKey = sk
        AutoSwitchLedger.recordCutSwitchSlotAborted(sk, reason)
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】切号续跑还原/登录 slot=$sk：$reason")
        }
    }

    fun cancelPendingCutSwitchFollowUps(slotKey: String) {
        cancelDuplicatePendingCutSwitchOps(slotKey.trim(), restoreOnly = true)
    }

    fun isRunning(): Boolean = tickJob?.isActive == true

    fun shouldAllowGrowthBaseline(): Boolean = growthBaselineBlockReason() == null

    /** null 表示允许涨粉；非 null 为拦截原因（含 plan 切号进度摘要）。 */
    fun growthBaselineBlockReason(): String? {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return "非自治模式"
        val plan = AutoSwitchLocalPlanStore.get() ?: return "plan 文件不可用"
        val entry = currentSlotEntry(plan) ?: return "当前不在任何计划时段"
        if (!AutoSwitchLocalPlanStore.isCutSwitchDone(entry.slot_key)) {
            return AutoSwitchLocalPlanStore.formatCutSwitchProgressLine(entry.slot_key)
                ?: "切号未完成 slot=${entry.slot_key}"
        }
        return growthPullBlockReason()
    }

    private fun growthPullBlockReason(): String? {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return null
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
            return PrefsStore.autoSwitchTikTokLoginHoldReason()?.let { "登录保障恢复中：$it" }
                ?: "登录保障恢复中（暂停涨粉/发作品/引流调度）"
        }
        if (PrefsStore.getAutoSwitchMode() == "exiting") return "自治退出中"
        if (AutoSwitchLocalPlanStore.isPublishContextOccupying()) return "发作品占用中"
        if (AutoSwitchLocalPlanStore.isMutualContextOccupying()) return "互点占用中"
        if (busy.get()) return "独占 OP 执行中"
        if (opQueue.isNotEmpty()) return "独占 OP 队列非空（${opQueue.size}）"
        if (pendingCutSwitchSlotKey != null) return "切号等待高优插入 slot=$pendingCutSwitchSlotKey"
        val inflight = insertionInflight ?: return null
        return if (isInsertionKindStillActive(inflight.second)) {
            "插入任务进行中 slot=${inflight.first} kind=${inflight.second}"
        } else {
            null
        }
    }

    fun shouldBlockGrowthPull(): Boolean = growthPullBlockReason() != null

    override fun isSlotReady(slotKey: String, targetRowIndex: Int): Boolean =
        AutoSwitchLocalPlanStore.isSlotReady(slotKey)

    override fun isWithinSwitchSafeBuffer(minuteOfDay: Int, slot: AutoSwitchPlanSlot): Boolean =
        AutoSwitchSlotWindow.isWithinSwitchSafeBuffer(minuteOfDay, slot, SWITCH_SAFE_BUFFER_MIN)

    override suspend fun enqueueInsertion(op: DeviceOp, slotKey: String, kind: String) {
        beginExclusiveEnqueue("插入操作")
        markInsertionInflight(slotKey, kind)
        AutoSwitchLocalPlanStore.markInsertionState(slotKey, kind, PlanStepState.RUNNING)
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
    ): Boolean = verifyInsertionLogin(ctx, kind)

    override fun insertionState(kind: String, slotKey: String): PlanStepState? =
        AutoSwitchLocalPlanStore.insertionState(slotKey, kind)

    fun refreshBundle(bundle: AutoSwitchBundle) {
        syncPlanFromBundle(bundle)
    }

    fun refreshWeeklyBundle(bundle: AutoSwitchBundle): Boolean {
        val local = this.bundle
        val planAccounts = AutoSwitchLocalPlanStore.get()?.accounts.orEmpty()
        val accountsEquivalent = local != null &&
            (local.isAccountsEquivalentTo(bundle) ||
                (planAccounts.isNotEmpty() && bundle.isAccountsEquivalentTo(planAccounts)))
        syncPlanFromBundle(bundle)
        lastWeeklyRefreshAttemptMs = 0L
        val merged = this.bundle ?: return accountsEquivalent
        val weekStart = merged.week_start_date?.trim().orEmpty()
        val weekEnd = merged.week_end_date?.trim().orEmpty()
        if (weekStart.isNotBlank() && weekEnd.isNotBlank()) {
            PrefsStore.setAutoSwitchWeekRange(weekStart, weekEnd)
        }
        val fpNote = if (accountsEquivalent) "（账号未变，幂等刷新）" else "（账号变更，将重新配置）"
        LogCenter.op(
            alias,
            "【自动切号】已刷新周计划 bundle week=$weekStart~$weekEnd days=${merged.weekly_plan?.day_plans?.size ?: 0}$fpNote",
        )
        return accountsEquivalent
    }

    fun isCurrentSlotEnvironmentCommitted(): Boolean {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        val plan = AutoSwitchLocalPlanStore.get() ?: return false
        val entry = currentSlotEntry(plan) ?: return false
        return AutoSwitchLocalPlanStore.isCutSwitchDone(entry.slot_key)
    }

    fun notifyCutSwitchAligned(
        targetRowIndex: Int,
        slotKey: String,
        completionMethod: String,
        targetUsername: String = "",
    ) {
        val sk = slotKey.trim()
        if (targetRowIndex < 0 || sk.isEmpty()) return
        if (!AutoSwitchLocalPlanStore.canMarkCutSwitchDone(sk)) {
            val progress = AutoSwitchLocalPlanStore.formatCutSwitchProgressLine(sk).orEmpty()
            invalidateSlotAlignment(sk, "切号链前序未完成，拒绝标切号完成")
            if (alias.isNotBlank()) {
                LogCenter.op(
                    alias,
                    "【自动切号】拒绝标记切号完成：前序步骤未完成 slot=$sk" +
                        (progress.takeIf { it.isNotBlank() }?.let { "；$it" } ?: ""),
                )
            }
            return
        }
        val user = targetUsername.trim().removePrefix("@").ifBlank {
            AutoSwitchLocalPlanStore.slot(sk)?.target_username?.trim()?.removePrefix("@").orEmpty()
        }
        if (user.isBlank()) {
            invalidateSlotAlignment(sk, "切号完成缺少目标 username")
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】拒绝标记切号完成：缺少目标 username slot=$sk")
            }
            return
        }
        val committed = AutoSwitchLocalPlanStore.commitCutSwitchChainCompleted(
            slotKey = sk,
            username = user,
            method = completionMethod,
        )
        if (!committed) {
            invalidateSlotAlignment(sk, "plan+ledger 切号完成写入失败")
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】切号完成同事务写入失败 slot=$sk")
            }
            return
        }
        cancelDuplicatePendingCutSwitchOps(sk)
        cutSwitchInflightSlotKey = null
        pendingCutSwitchSlotKey = null
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
        this.sessionId = PrefsStore.getAutoSwitchSessionId().orEmpty()
        syncPlanFromBundle(bundle)
        AutoSwitchLocalPlanStore.refreshDeviceActiveFromRuntime(
            PrefsStore.getLogContextUsername(),
            "自治启动刷新活跃标记",
        )
        this.alignOnNextTick = true
        lastSlotKey = PrefsStore.getAutoSwitchLastSlotKey()
        insertionInflight = null
        pendingCutSwitchSlotKey = null
        recoverAllStaleInflightOnStart()
        lastProcessedScheduleDate = null
        PrefsStore.setAutoSwitchLastSchedulerRevision(rev)
        drainJob = scope.launch { drainQueueLoop() }
        tickJob = scope.launch { tickLoop() }
        LogCenter.op(
            this.alias,
            "【自动切号】本地调度器已启动（计划文件驱动） schedule=${bundle.schedule_date} " +
                "accounts=${AutoSwitchLocalPlanStore.get()?.accounts?.size ?: 0} revision=$rev",
        )
    }

    fun isExclusiveWorkActive(): Boolean = busy.get() || opQueue.isNotEmpty()

    suspend fun awaitExclusiveIdle(maxWaitMs: Long = 600_000L): Boolean {
        if (alias.isNotBlank() && isExclusiveWorkActive()) {
            LogCenter.op(alias, "【自动切号】等待独占 OP 收尾（队列=${opQueue.size} busy=${busy.get()}）…")
        }
        var waited = 0L
        val interval = 250L
        while (isExclusiveWorkActive() && waited < maxWaitMs) {
            delay(interval)
            waited += interval
        }
        return !isExclusiveWorkActive()
    }

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
            insertionInflight = null
            cutSwitchInflightSlotKey = null
            pendingCutSwitchSlotKey = null
            AutoSwitchLocalPlanStore.clear()
        }
    }

    private fun syncPlanFromBundle(cloud: AutoSwitchBundle) {
        val rev = PrefsStore.getAutoSwitchBundleRevision()
        AutoSwitchLocalPlanStore.mergeFromBundle(
            cloud,
            rev,
            sessionId.ifBlank { PrefsStore.getAutoSwitchSessionId().orEmpty() },
        )
        revalidateCutSwitchCompletionsAgainstLedger()
        refreshBundleFromPlan(cloud)
    }

    private fun revalidateCutSwitchCompletionsAgainstLedger() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        AutoSwitchLedger.restoreFromPrefs()
        AutoSwitchLocalPlanStore.revalidateCutSwitchCompletionsAgainstLedger { sk ->
            AutoSwitchLedger.hasCutSwitchChainCompleted(sk)
        }
    }

    private fun refreshBundleFromPlan(cloud: AutoSwitchBundle) {
        bundle = AutoSwitchLocalPlanStore.applyRuntimeToBundle(cloud.withCurrentTimeSlotHighlight())
        syncBundleHolder()
    }

    private fun currentSlotEntry(plan: AutoSwitchLocalPlan): SlotPlanEntry? {
        val date = beijingDateStr()
        val minute = beijingMinuteOfDay()
        return AutoSwitchLocalPlanStore.resolveSlotForMinute(plan, date, minute)
    }

    private suspend fun tickLoop() {
        if (alignOnNextTick) {
            alignOnNextTick = false
            runCatching { tickOnce() }
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
        if (waitMs in 1..59_999) delay(waitMs)
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
            maybeResumePendingCutSwitch()
        }
    }

    private fun maybeResumePendingCutSwitch() {
        if (pendingCutSwitchSlotKey != null && !hasActiveHighPriorityInsertion()) {
            AutoSwitchLocalPlanStore.resumeCutSwitchFromWaiting(pendingCutSwitchSlotKey!!)
            pendingCutSwitchSlotKey = null
            requestImmediateTick()
        }
    }

    private suspend fun tickOnce() {
        tickMutex.withLock { tickOnceUnlocked() }
    }

    private suspend fun tickOnceUnlocked() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PublishPostGrowthGate.isBlockingGrowth()) {
            logTickSkipThrottled("发作品独占发布中")
            return
        }

        val currentBundle = bundle ?: run {
            LogCenter.op(alias, "【自动切号】调度跳过：bundle 为空")
            return
        }
        val plan = AutoSwitchLocalPlanStore.get() ?: run {
            syncPlanFromBundle(currentBundle)
            AutoSwitchLocalPlanStore.get() ?: run {
                logTickSkip("本地 plan 文件不可用")
                return
            }
        }

        val scheduleDate = beijingDateStr()
        if (lastProcessedScheduleDate != null && lastProcessedScheduleDate != scheduleDate) {
            AutoSwitchLocalPlanStore.onScheduleDateChanged(lastProcessedScheduleDate!!)
            onPauseGrowth()
            opQueue.clear()
            busy.set(false)
            insertionInflight = null
            pendingCutSwitchSlotKey = null
            AutoSwitchLedger.cancelQueuedCutSwitchOpsForScheduleDate(lastProcessedScheduleDate!!)
            LogCenter.op(alias, "【自动切号】日期切换 $lastProcessedScheduleDate → $scheduleDate")
        }
        lastProcessedScheduleDate = scheduleDate

        if (!currentBundle.isDateInWeekRange(scheduleDate)) {
            if (tryRefreshWeeklyBundle("今日 $scheduleDate 不在当前周计划内")) return
            logTickSkipThrottled("今日 $scheduleDate 不在当前周计划内")
            return
        }

        val minuteOfDay = beijingMinuteOfDay()
        if (forceLogNextTick) {
            AutoSwitchLocalPlanStore.abandonExpiredIncompleteCutSwitches(scheduleDate, minuteOfDay)
        }
        val currentSlotEntry = AutoSwitchLocalPlanStore.resolveSlotForMinute(plan, scheduleDate, minuteOfDay)
        val incompleteRecovery = if (forceLogNextTick) {
            findRecoverableIncompleteCutSwitch(currentSlotEntry)
        } else {
            null
        }
        val slotEntry = incompleteRecovery
            ?: currentSlotEntry
            ?: run {
                logTickSkip("当前 $scheduleDate ${formatMinuteOfDay(minuteOfDay)} 不在任何计划时段内")
                return
            }
        if (incompleteRecovery != null && alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】续跑：恢复 crash 未完成切号 slot=${slotEntry.slot_key}（仅当前时段）",
            )
        }

        val slotScheduleDate = slotEntry.schedule_date
        val planSlot = currentBundle.dailyPlanForDate(slotScheduleDate)?.slots.orEmpty()
            .firstOrNull { (it.slot_index ?: -1) == slotEntry.slot_index }
            ?: run {
                logTickSkip("时段 slot=${slotEntry.slot_key} 在 bundle 中缺失")
                return
            }

        if (!AutoSwitchSlotWindow.isWithinSlot(minuteOfDay, planSlot)) {
            logTickSkip("当前分钟不在时段 ${slotEntry.slot_key} 有效窗口内")
            return
        }

        bundle = AutoSwitchLocalPlanStore.applyRuntimeToBundle(
            currentBundle.withTimeSlotHighlight(slotScheduleDate, minuteOfDay),
        )
        syncBundleHolder()

        val slotKey = slotEntry.slot_key
        val previousSlotKey = lastSlotKey?.trim()?.takeIf { it.isNotEmpty() && it != slotKey }
        val slotChanged = previousSlotKey != null
        if (slotChanged && previousSlotKey != null) {
            AutoSwitchLocalPlanStore.onActiveSlotChanged(slotKey, previousSlotKey)
            onSlotTransition(previousSlotKey, slotKey)
            LogCenter.op(alias, "【自动切号】时段切换 $previousSlotKey → $slotKey")
        }

        maybeSweepOrphanedPublishState(slotKey)

        if (!AutoSwitchLocalPlanStore.isCutSwitchDone(slotKey)) {
            if (hasActiveHighPriorityInsertion()) {
                pendingCutSwitchSlotKey = slotKey
                AutoSwitchLocalPlanStore.markCutSwitchWaiting(slotKey)
                LogCenter.op(alias, "【自动切号】切号等待高优插入完成 slot=$slotKey")
                lastSlotKey = slotKey
                PrefsStore.setAutoSwitchLastSlotKey(slotKey)
                return
            }
            deviceBusyReasonForCutSwitch()?.let { reason ->
                logTickSkip("切号等待：$reason")
                lastSlotKey = slotKey
                PrefsStore.setAutoSwitchLastSlotKey(slotKey)
                return
            }
            pendingCutSwitchSlotKey = null
            handleCutSwitchFromPlan(currentBundle, slotEntry, previousSlotKey)
            lastSlotKey = slotKey
            PrefsStore.setAutoSwitchLastSlotKey(slotKey)
            return
        }

        lastSlotKey = slotKey
        PrefsStore.setAutoSwitchLastSlotKey(slotKey)

        cutSwitchInflightSlotKey = null
        pendingCutSwitchSlotKey = null

        deviceBusyReasonForInsertionScheduling()?.let { reason ->
            logTickSkipThrottled("插入任务暂缓：$reason")
            return
        }

        val targetRow = currentBundle.accounts.orEmpty()
            .firstOrNull { (it.row_index ?: -1) == slotEntry.target_row_index }
            ?: return

        val ctx = AutoSwitchSlotTickContext(
            alias = alias,
            deviceId = deviceId,
            bundle = currentBundle,
            scheduleDate = scheduleDate,
            slotKey = slotKey,
            slot = planSlot,
            account = targetRow,
            minuteOfDay = minuteOfDay,
        )
        var enqueued = false
        for (insertion in slotInsertions) {
            if (insertion.trySchedule(ctx, this)) {
                enqueued = true
                break
            }
        }
        if (!enqueued && shouldAllowGrowthBaseline()) {
            if (alias.isNotBlank()) {
                val progress = AutoSwitchLocalPlanStore.formatCutSwitchProgressLine(slotKey)
                LogCenter.op(
                    alias,
                    "【自动切号】空档无插入任务，允许涨粉" +
                        (progress?.let { "：$it" } ?: ""),
                )
            }
            onTryResumeGrowth()
        } else if (!enqueued && alias.isNotBlank()) {
            growthBaselineBlockReason()?.let { reason ->
                LogCenter.op(alias, "【自动切号】空档暂不允许涨粉：$reason")
            }
        }
    }

    private suspend fun handleCutSwitchFromPlan(
        bundle: AutoSwitchBundle,
        entry: SlotPlanEntry,
        previousSlotKey: String?,
    ) {
        val slotKey = entry.slot_key
        AutoSwitchLocalPlanStore.ensureSlotCutSwitchSourcePersisted(slotKey, previousSlotKey)
        val targetRi = entry.target_row_index
        val accounts = bundle.accounts.orEmpty()
        val targetRow = accounts.firstOrNull { (it.row_index ?: -1) == targetRi } ?: run {
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】切号跳过：slot=$slotKey 目标 row=$targetRi 在 bundle 中不存在")
            }
            return
        }

        val planSlot = AutoSwitchLocalPlanStore.slot(slotKey) ?: entry
        val cs = planSlot.cut_switch
        // 切号是否完成只看 plan 文件 cut_switch 进度；未 DONE 则 strictly 按 nextPendingStep 续跑，
        // 不因 device_active / logContext 等推断「已对齐」（避免涨粉误刷活跃标记后假完成切号）。
        val needsLoginFinish = !cs.isDone() &&
            cs.restore_or_login != PlanStepState.DONE &&
            cs.restore_or_login != PlanStepState.SKIPPED &&
            cs.isReadyForRestoreOrLogin()
        if (needsLoginFinish) {
            if (hasCutSwitchWorkInProgress(slotKey, targetRi)) {
                logCutSwitchSkip("切号进行中 slot=$slotKey step=RESTORE_OR_LOGIN（plan 续跑还原/自动登录）")
                return
            }
            val sourceRow = resolveSourceAccount(accounts, entry, previousSlotKey)
            val enqueued = enqueueRestoreOnly(bundle, targetRow, slotKey, sourceRow?.row_index)
            if (enqueued) {
                cutSwitchInflightSlotKey = slotKey
            } else if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】切号入队失败 slot=$slotKey step=RESTORE_OR_LOGIN（plan 续跑）")
            }
            return
        }

        val sourceRow = resolveSourceAccount(accounts, entry, previousSlotKey)
        val nextStep = planSlot.cut_switch.nextPendingStep() ?: run {
            if (!AutoSwitchLocalPlanStore.isCutSwitchDone(slotKey)) {
                logCutSwitchSkip("切号计划无待执行步骤 slot=$slotKey state=${planSlot.cut_switch}")
            }
            return
        }

        if (hasCutSwitchWorkInProgress(slotKey, targetRi)) {
            logCutSwitchSkip("切号进行中 slot=$slotKey step=$nextStep")
            return
        }

        val enqueued = when (nextStep) {
            CutSwitchStepKind.BACKUP -> {
                val targetUser = entry.target_username.trim().removePrefix("@")
                if (sourceRow != null && isCutSwitchAlreadyOnTarget(sourceRow, targetRow, targetUser)) {
                    if (alias.isNotBlank()) {
                        LogCenter.op(alias, "【自动切号】当前时段已是 @$targetUser，跳过切号直接进入业务")
                    }
                    if (AutoSwitchLocalPlanStore.commitCutSwitchAlreadyActive(slotKey, targetUser)) {
                        cutSwitchInflightSlotKey = null
                        pendingCutSwitchSlotKey = null
                        cancelDuplicatePendingCutSwitchOps(slotKey)
                        onTryResumeGrowth()
                        requestImmediateTick(forceLog = true)
                    }
                    false
                } else if (sourceRow == null) {
                    val reason = AutoSwitchLocalPlanStore.describeCutSwitchSourceResolutionFailure(
                        slotKey,
                        previousSlotKey,
                        accounts.size,
                    )
                    if (alias.isNotBlank()) {
                        LogCenter.op(alias, "【自动切号】计划文件无法解析切号源，bootstrap（不备份）：$reason")
                    }
                    AutoSwitchLocalPlanStore.setCutSwitchMode(slotKey, "bootstrap", null, null)
                    enqueueBootstrapCutSwitch(bundle, targetRow, slotKey)
                } else {
                    AutoSwitchLocalPlanStore.setCutSwitchMode(
                        slotKey,
                        "switch",
                        sourceRow.row_index,
                        sourceRow.username?.trim(),
                    )
                    if (alias.isNotBlank()) {
                        LogCenter.op(
                            alias,
                            "【自动切号】计划文件切号源=@${sourceRow.username?.trim()} " +
                                "→ 目标=@${entry.target_username.trim()}（备份→清理→还原）",
                        )
                    }
                    enqueueCutSwitch(bundle, sourceRow, targetRow, slotKey)
                }
            }
            CutSwitchStepKind.CLEAR -> {
                if (planSlot.cut_switch.mode == "bootstrap") {
                    enqueueBootstrapClearOnly(bundle, targetRow, slotKey)
                } else {
                    enqueueSwitchClearOnly(bundle, targetRow, slotKey)
                }
            }
            CutSwitchStepKind.RESTORE_OR_LOGIN -> {
                enqueueRestoreOnly(bundle, targetRow, slotKey, sourceRow?.row_index)
            }
        }
        if (enqueued) {
            cutSwitchInflightSlotKey = slotKey
        } else if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】切号入队失败 slot=$slotKey step=$nextStep target=@${entry.target_username}")
        }
    }

    /** 当前时段切号源与目标一致，无需备份/清理/还原（不得仅凭 device_active 推断）。 */
    private fun isCutSwitchAlreadyOnTarget(
        sourceRow: AutoSwitchAccount,
        targetRow: AutoSwitchAccount,
        targetUser: String,
    ): Boolean {
        val sourceRi = sourceRow.row_index ?: -1
        val targetRi = targetRow.row_index ?: -1
        if (sourceRi >= 0 && targetRi >= 0 && sourceRi == targetRi) return true
        val sourceUser = sourceRow.username?.trim()?.removePrefix("@").orEmpty()
        return sourceUser.isNotEmpty() && sourceUser.equals(targetUser, ignoreCase = true)
    }

    /** 切号源账号：只读 plan 文件（source、device_active、上一已完成时段），不用 TikTok 前台推断。 */
    private fun resolveSourceAccount(
        accounts: List<AutoSwitchAccount>,
        entry: SlotPlanEntry,
        previousSlotKey: String?,
    ): AutoSwitchAccount? {
        AutoSwitchLocalPlanStore.resolveCutSwitchSourceIdentity(entry.slot_key, previousSlotKey)?.let { (ri, _) ->
            accounts.firstOrNull { (it.row_index ?: -1) == ri }?.let { return it }
        }
        if (accounts.size == 1) return accounts[0]
        return null
    }

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
            AutoSwitchLedger.cancelQueuedCutSwitchOpsForSlot(sk, "切号已完成，取消重复切号 OP")
        }
    }

    private suspend fun tryRefreshWeeklyBundle(reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastWeeklyRefreshAttemptMs < WEEKLY_REFRESH_COOLDOWN_MS) return false
        lastWeeklyRefreshAttemptMs = now
        LogCenter.op(alias, "【自动切号】$reason，向云端拉取周计划 bundle…")
        return onRefreshWeeklyBundle()
    }

    private fun hasActiveHighPriorityInsertion(): Boolean {
        val inflight = insertionInflight ?: return false
        return isInsertionKindStillActive(inflight.second)
    }

    private fun isInsertionKindStillActive(kind: String): Boolean = when (kind) {
        "publish" -> isPublishInsertionStillActive()
        "mutual_traffic" -> isMutualInsertionStillActive()
        else -> insertionInflight?.second == kind
    }

    private fun isPublishInsertionStillActive(): Boolean {
        if (isPublishExecutionActive()) return true
        if (AutoSwitchLocalPlanStore.isPublishContextOccupying()) return true
        val sk = insertionInflight?.first?.trim().orEmpty()
        if (sk.isBlank()) return false
        return !AutoSwitchLocalPlanStore.isPublishDone(sk) &&
            !AutoSwitchLocalPlanStore.isPublishTerminalFailure(sk)
    }

    private fun isMutualInsertionStillActive(): Boolean {
        if (isMutualExecutionActive()) return true
        if (AutoSwitchLocalPlanStore.isMutualContextOccupying()) return true
        val sk = insertionInflight?.first?.trim().orEmpty()
        if (sk.isBlank()) return false
        val state = AutoSwitchLocalPlanStore.insertionState(sk, "mutual_traffic")
        return state == PlanStepState.RUNNING
    }

    private fun recoverStalePublishInflightOnStart() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (isPublishExecutionActive()) return
        val sk = AutoSwitchLocalPlanStore.activePublishSlotKey()?.trim().orEmpty()
            .ifBlank { lastSlotKey.orEmpty() }
        if (sk.isBlank()) return
        if (!AutoSwitchLocalPlanStore.hasRecoverableActivePublish(sk) &&
            !AutoSwitchLocalPlanStore.isPublishInflight(sk)
        ) return
        onStalePublishInflight(sk)
    }

    private fun maybeSweepOrphanedPublishState(slotKey: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (isPublishExecutionActive()) return
        if (!AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()) return
        val sk = AutoSwitchLocalPlanStore.activePublishSlotKey()?.trim().orEmpty()
            .ifBlank { insertionInflight?.first.orEmpty() }
            .ifBlank { slotKey }
        if (sk.isBlank()) return
        onStalePublishInflight(sk)
        maybeResumePendingCutSwitch()
    }

    private fun clearInsertionInflightIfKind(kind: String, slotKey: String) {
        val cur = insertionInflight ?: return
        if (cur.first == slotKey && cur.second == kind) {
            insertionInflight = null
        }
    }

    private fun hasCutSwitchWorkInProgress(slotKey: String, targetRi: Int): Boolean {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return false
        if (opQueue.any { isCutSwitchOpForSlot(it, sk) }) return true
        if (busy.get() && cutSwitchInflightSlotKey == sk) return true
        if (AutoSwitchLocalPlanStore.isCutSwitchInflight(sk)) {
            if (tryRecoverStaleCutSwitchInflight(sk)) return false
            return true
        }
        return false
    }

    private fun tryRecoverStaleCutSwitchInflight(slotKey: String): Boolean {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return false
        if (busy.get() || opQueue.any { isCutSwitchOpForSlot(it, sk) } || cutSwitchInflightSlotKey == sk) {
            return false
        }
        AutoSwitchLocalPlanStore.resetStaleCutSwitchInflight(sk)
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】切号 stale in-flight 已重置为 PENDING slot=$sk")
        }
        return true
    }

    /** 进程重启 / 人为启动后：按本地 plan 文件清理 stale RUNNING，便于 tick 续跑。 */
    fun recoverAllStaleInflightOnStart() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        revalidateCutSwitchCompletionsAgainstLedger()
        AutoSwitchLocalPlanStore.recoverStaleRunningStepsAfterProcessDeath()
        recoverStalePublishInflightOnStart()
        insertionInflight = null
        cutSwitchInflightSlotKey = null
        pendingCutSwitchSlotKey = null
        val plan = AutoSwitchLocalPlanStore.get() ?: return
        for (entry in plan.slots) {
            if (AutoSwitchLocalPlanStore.isCutSwitchInflight(entry.slot_key)) {
                tryRecoverStaleCutSwitchInflight(entry.slot_key)
            }
        }
        lastSlotKey?.trim()?.takeIf { it.isNotEmpty() }?.let { tryRecoverStaleCutSwitchInflight(it) }
    }

    /** 仅当「当前分钟所属时段」存在未完成切号时才续跑；过期/其它日期的 slot 由 [abandonExpiredIncompleteCutSwitches] 清理。 */
    private fun findRecoverableIncompleteCutSwitch(currentSlot: SlotPlanEntry?): SlotPlanEntry? {
        val entry = currentSlot ?: return null
        if (AutoSwitchLocalPlanStore.isCutSwitchDone(entry.slot_key)) return null
        val cs = AutoSwitchLocalPlanStore.slot(entry.slot_key)?.cut_switch ?: entry.cut_switch
        return if (cs.nextPendingStep() != null) entry else null
    }

    private fun logCutSwitchSkip(reason: String) {
        if (forceLogNextTick) {
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】切号暂缓：$reason")
            }
            return
        }
        logTickSkipThrottled(reason)
    }

    private fun logTickSkip(reason: String) {
        if (forceLogNextTick) {
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】调度暂缓：$reason")
            }
            return
        }
        logTickSkipThrottled(reason)
    }

    private fun logTickSkipThrottled(reason: String) {
        val now = System.currentTimeMillis()
        if (reason == lastTickSkipLogKey && now - lastTickSkipLogMs < TICK_SKIP_LOG_COOLDOWN_MS) return
        lastTickSkipLogKey = reason
        lastTickSkipLogMs = now
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】调度暂缓：$reason")
        }
    }

    private fun formatMinuteOfDay(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        return "%02d:%02d".format(h, m)
    }

    private fun isCutSwitchOpForSlot(op: DeviceOp, slotKey: String): Boolean =
        op.payload?.auto_switch_slot_key?.trim() == slotKey.trim() &&
            op.op_type in cutSwitchQueueOpTypes

    private suspend fun beginExclusiveEnqueue(forceEnqueueLabel: String) {
        onPauseGrowth()
        onForceStopTikTokForExclusive("切号入队前")
        awaitTikTokExclusiveIdle(forceEnqueueLabel = forceEnqueueLabel)
        onForceStopTikTokForExclusive("切号入队前（涨粉收尾后）")
    }

    private suspend fun awaitTikTokExclusiveIdle(maxMs: Long = 20_000L, forceEnqueueLabel: String = "操作") {
        var waited = 0L
        while (ExecutionScope.isActive() && waited < maxMs) {
            delay(250)
            waited += 250
        }
        if (ExecutionScope.isActive()) {
            LogCenter.op(alias, "【自动切号】等待 TikTok 独占收尾超时（${maxMs / 1000}s），仍将${forceEnqueueLabel}入队")
        }
    }

    private fun deviceBusyReasonForCutSwitch(): String? {
        if (busy.get()) return "本地 OP 执行中"
        if (opQueue.isNotEmpty()) return "本地 OP 队列非空（${opQueue.size}）"
        return null
    }

    private fun deviceBusyReasonForInsertionScheduling(): String? {
        if (ExecutionScope.isActive()) return "涨粉任务执行中"
        if (PrefsStore.isAutoSwitchExclusivePreempt()) return "独占抢占中"
        if (busy.get()) return "本地 OP 执行中"
        if (opQueue.isNotEmpty()) return "本地 OP 队列非空（${opQueue.size}）"
        insertionInflight?.let { (sk, kind) ->
            if (isInsertionKindStillActive(kind)) return "插入 in-flight：$kind slot=$sk"
        }
        return null
    }

    private fun enqueueExclusiveOp(op: DeviceOp) {
        onPauseGrowth()
        opQueue.add(op)
    }

    private suspend fun enqueueBootstrapCutSwitch(
        bundle: AutoSwitchBundle,
        targetRow: AutoSwitchAccount,
        slotKey: String,
    ): Boolean = enqueueClearRestoreChain(bundle, targetRow, slotKey, bootstrap = true)

    private suspend fun enqueueBootstrapClearOnly(
        bundle: AutoSwitchBundle,
        targetRow: AutoSwitchAccount,
        slotKey: String,
    ): Boolean {
        val targetRi = targetRow.row_index ?: return false
        val targetUser = targetRow.username?.trim().orEmpty()
        if (targetUser.isBlank()) return false
        beginExclusiveEnqueue("切号清理")
        val pkg = pkg(bundle)
        val aliasClean = aliasClean(bundle)
        val resolved = resolveTargetRestoreBackup(targetRow, targetUser, aliasClean)
        val cutMode = if (resolved != null) "clear_source_for_restore" else "exclusive_activate_target"
        AutoSwitchLocalPlanStore.markCutSwitchStepRunning(slotKey, CutSwitchStepKind.CLEAR)
        enqueueExclusiveOp(buildClearOp(aliasClean, pkg, targetRi, targetUser, slotKey, cutMode))
        return true
    }

    /** switch 模式断点续跑：备份已完成，仅清理（保留 plan 内 source，不得改 bootstrap）。 */
    private suspend fun enqueueSwitchClearOnly(
        bundle: AutoSwitchBundle,
        targetRow: AutoSwitchAccount,
        slotKey: String,
    ): Boolean {
        val targetRi = targetRow.row_index ?: return false
        val targetUser = targetRow.username?.trim().orEmpty()
        if (targetUser.isBlank()) return false
        beginExclusiveEnqueue("切号清理")
        val pkgName = pkg(bundle)
        val aliasClean = aliasClean(bundle)
        val resolved = resolveTargetRestoreBackup(targetRow, targetUser, aliasClean)
        val cutMode = if (resolved != null) "clear_source_for_restore" else "exclusive_activate_target"
        AutoSwitchLocalPlanStore.markCutSwitchStepRunning(slotKey, CutSwitchStepKind.CLEAR)
        enqueueExclusiveOp(buildClearOp(aliasClean, pkgName, targetRi, targetUser, slotKey, cutMode))
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】计划文件续跑：清理（源已备份）→ @$targetUser")
        }
        return true
    }

    private suspend fun enqueueRestoreOnly(
        bundle: AutoSwitchBundle,
        targetRow: AutoSwitchAccount,
        slotKey: String,
        sourceRi: Int?,
    ): Boolean {
        val targetRi = targetRow.row_index ?: return false
        val targetUser = targetRow.username?.trim().orEmpty()
        if (targetUser.isBlank()) return false
        val aliasClean = aliasClean(bundle)
        val cloudRaw = targetRow.backup_name?.trim().orEmpty()
        val resolved = resolveTargetRestoreBackup(targetRow, targetUser, aliasClean)
        beginExclusiveEnqueue(if (resolved != null) "切号还原" else "切号自动登录")
        AutoSwitchLocalPlanStore.markCutSwitchStepRunning(slotKey, CutSwitchStepKind.RESTORE_OR_LOGIN)
        if (resolved == null) {
            val cloudBackupLabel = when {
                cloudRaw.isEmpty() || cloudRaw == "-" -> "-"
                else -> File(cloudRaw).name
            }
            logCutSwitchMissingBackupUseAutoLogin(slotKey, targetUser, cloudBackupLabel)
            enqueueExclusiveOp(
                buildClearOp(aliasClean, pkg(bundle), targetRi, targetUser, slotKey, "exclusive_activate_target"),
            )
            LogCenter.op(alias, "【自动切号】断点续跑：@$targetUser 无本地备份，入队自动登录")
            return true
        }
        val (backupName, backupPath) = resolved
        val restoreOpId = newLocalOpId("restore")
        val restorePayload = DeviceOpPayload(
            device_id = deviceId,
            alias = aliasClean,
            package_name = pkg(bundle),
            backup_name = backupName,
            backup_path = backupPath,
            username = targetUser,
            skip_pm_clear = true,
            restore_highlight_row_index = targetRi,
            backup_record_id = sourceRi,
            source = "auto_switch",
            auto_switch_slot_key = slotKey,
        )
        AutoSwitchLedger.recordCutSwitchQueued(restoreOpId, "tiktok_restore", slotKey, targetUser, restorePayload)
        enqueueExclusiveOp(DeviceOp(op_id = restoreOpId, op_type = "tiktok_restore", payload = restorePayload))
        LogCenter.op(alias, "【自动切号】断点续跑：已入队还原 → $targetUser")
        return true
    }

    private suspend fun enqueueClearRestoreChain(
        bundle: AutoSwitchBundle,
        targetRow: AutoSwitchAccount,
        slotKey: String,
        bootstrap: Boolean,
    ): Boolean {
        val targetRi = targetRow.row_index ?: return false
        val targetUser = targetRow.username?.trim().orEmpty()
        if (targetUser.isBlank()) return false
        beginExclusiveEnqueue("切号")
        val aliasClean = aliasClean(bundle)
        val pkgName = pkg(bundle)
        val resolved = resolveTargetRestoreBackup(targetRow, targetUser, aliasClean)
        if (targetRow.backup_name?.trim()?.isNotEmpty() == true && targetRow.backup_name != "-" && resolved == null) {
            logCutSwitchMissingBackupUseAutoLogin(slotKey, targetUser, File(targetRow.backup_name!!).name)
        }
        val canRestore = resolved != null
        val cutMode = if (canRestore) "clear_source_for_restore" else "exclusive_activate_target"
        AutoSwitchLocalPlanStore.markCutSwitchStepRunning(slotKey, CutSwitchStepKind.CLEAR)
        enqueueExclusiveOp(buildClearOp(aliasClean, pkgName, targetRi, targetUser, slotKey, cutMode))
        if (canRestore && resolved != null) {
            val (backupName, backupPath) = resolved
            val restoreOpId = newLocalOpId("restore")
            val restorePayload = DeviceOpPayload(
                device_id = deviceId,
                alias = aliasClean,
                package_name = pkgName,
                backup_name = backupName,
                backup_path = backupPath,
                username = targetUser,
                skip_pm_clear = true,
                restore_highlight_row_index = targetRi,
                source = "auto_switch",
                auto_switch_slot_key = slotKey,
            )
            AutoSwitchLedger.recordCutSwitchQueued(restoreOpId, "tiktok_restore", slotKey, targetUser, restorePayload)
            enqueueExclusiveOp(DeviceOp(op_id = restoreOpId, op_type = "tiktok_restore", payload = restorePayload))
        }
        LogCenter.op(alias, "【自动切号】已入队初始切号 → $targetUser")
        return true
    }

    private fun buildClearOp(
        aliasClean: String,
        pkgName: String,
        targetRi: Int,
        targetUser: String,
        slotKey: String,
        cutMode: String,
    ): DeviceOp {
        val clearOpId = newLocalOpId("clear")
        val clearPayload = DeviceOpPayload(
            device_id = deviceId,
            alias = aliasClean,
            package_name = pkgName,
            cut_switch_after_backup = cutMode,
            cut_switch_target_row_index = targetRi,
            source = "auto_switch",
            auto_switch_slot_key = slotKey,
        )
        AutoSwitchLedger.recordCutSwitchQueued(clearOpId, "tiktok_clear", slotKey, targetUser, clearPayload)
        return DeviceOp(op_id = clearOpId, op_type = "tiktok_clear", payload = clearPayload)
    }

    private suspend fun enqueueCutSwitch(
        switchBundle: AutoSwitchBundle,
        sourceRow: AutoSwitchAccount,
        targetRow: AutoSwitchAccount,
        slotKey: String,
    ): Boolean {
        val sourceRi = sourceRow.row_index ?: return false
        val targetRi = targetRow.row_index ?: return false
        val sourceUser = sourceRow.username?.trim().orEmpty()
        val targetUser = targetRow.username?.trim().orEmpty()
        if (sourceUser.isBlank() || targetUser.isBlank()) return false
        AutoSwitchLocalPlanStore.setDeviceActive(sourceRi, sourceUser, "切号入队备份前确认活跃标记")
        val backupAttr = AutoSwitchLocalPlanStore.resolveCutSwitchBackupAttribution(sourceUser, sourceRi)
            ?: return false
        val (backupRi, backupUser) = backupAttr
        val accounts = switchBundle.accounts.orEmpty()
        val backupAccount = accounts.firstOrNull { (it.row_index ?: -1) == backupRi }
            ?: sourceRow.copy(row_index = backupRi, username = backupUser)
        beginExclusiveEnqueue("切号")
        val aliasClean = aliasClean(switchBundle)
        val pkgName = pkg(switchBundle)
        val resolved = resolveTargetRestoreBackup(targetRow, targetUser, aliasClean)
        val canRestore = resolved != null
        AutoSwitchLocalPlanStore.markCutSwitchStepRunning(slotKey, CutSwitchStepKind.BACKUP)
        val backupOpId = newLocalOpId("backup")
        val backupPayload = DeviceOpPayload(
            device_id = deviceId,
            alias = aliasClean,
            package_name = pkgName,
            backup_record_id = backupRi,
            username = backupUser,
            previous_backup_path = backupAccount.backup_path?.trim()?.takeIf { it.isNotEmpty() },
            source = "auto_switch",
            cut_switch_after_backup = if (canRestore) "clear_source_for_restore" else "exclusive_activate_target",
            cut_switch_target_row_index = targetRi,
            auto_switch_slot_key = slotKey,
        )
        AutoSwitchLedger.recordCutSwitchQueued(backupOpId, "tiktok_backup_and_clear", slotKey, backupUser, backupPayload)
        enqueueExclusiveOp(DeviceOp(op_id = backupOpId, op_type = "tiktok_backup_and_clear", payload = backupPayload))
        LogCenter.op(alias, "【自动切号】已入队切号备份 $backupUser → $targetUser（活跃标记归属 @$backupUser）")
        if (canRestore && resolved != null) {
            val (backupName, backupPath) = resolved
            val restoreOpId = newLocalOpId("restore")
            val restorePayload = DeviceOpPayload(
                device_id = deviceId,
                alias = aliasClean,
                package_name = pkgName,
                backup_name = backupName,
                backup_path = backupPath,
                username = targetUser,
                skip_pm_clear = true,
                restore_highlight_row_index = targetRi,
                backup_record_id = backupRi,
                source = "auto_switch",
                auto_switch_slot_key = slotKey,
            )
            AutoSwitchLedger.recordCutSwitchQueued(restoreOpId, "tiktok_restore", slotKey, targetUser, restorePayload)
            enqueueExclusiveOp(DeviceOp(op_id = restoreOpId, op_type = "tiktok_restore", payload = restorePayload))
        }
        return true
    }

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

    private fun logCutSwitchMissingBackupUseAutoLogin(slotKey: String, targetUser: String, cloudBackupName: String) {
        AutoSwitchLedger.recordCutSwitchMissingBackup(
            username = targetUser,
            slotKey = slotKey,
            cloudBackupName = cloudBackupName,
            phase = "enqueue",
        )
        LogCenter.op(alias, "【自动切号】目标 @$targetUser 备份包本机不可用，清理后自动登录")
    }

    private fun syncBundleHolder() {
        AutoSwitchBundleHolder.setBundle(bundle)
    }

    private fun graduateNewAccountVideoCountInBundle(targetRow: AutoSwitchAccount) {
        val targetRi = targetRow.row_index ?: return
        AutoSwitchLocalPlanStore.graduateVideoCount(targetRi)
        val current = bundle ?: return
        bundle = AutoSwitchLocalPlanStore.applyRuntimeToBundle(current)
        syncBundleHolder()
    }

    private fun pkg(bundle: AutoSwitchBundle) =
        bundle.run_config?.package_name?.trim().orEmpty().ifBlank { Config.TIKTOK_PACKAGE_NAME }

    private fun aliasClean(bundle: AutoSwitchBundle) =
        bundle.device_alias?.trim().orEmpty().ifBlank { alias }

    private fun beijingMinuteOfDay(): Int {
        val cal = Calendar.getInstance(beijingTz)
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun beijingDateStr(): String {
        val cal = Calendar.getInstance(beijingTz)
        return String.format(
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun newLocalOpId(prefix: String): String =
        "local-as-$prefix-${System.currentTimeMillis()}-${Random.nextInt(1000)}"

    companion object {
        private const val WEEKLY_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
        private const val TICK_SKIP_LOG_COOLDOWN_MS = 5 * 60 * 1000L
        const val SWITCH_SAFE_BUFFER_MIN = 5
        private val DEFAULT_INSERTIONS: List<AutoSwitchSlotInsertion> = listOf(
            PublishPostSlotInsertion(),
            MutualTrafficSlotInsertion(),
        )
    }
}
