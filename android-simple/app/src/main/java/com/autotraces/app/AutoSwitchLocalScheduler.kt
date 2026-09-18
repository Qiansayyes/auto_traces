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
 * 每个 slot：切号（backup → clear → restore/自动登录）→ 主播访问（可选）→ 涨粉 → 到点发作品 / 引流互点。
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
    private val isLiveVisitExecutionActive: () -> Boolean = { false },
    private val onStalePublishInflight: (slotKey: String) -> Unit = {},
    /** PLAN 切号待执行：清空当前账号发作品/互点等运行时占用，让位切号链。 */
    private val onPreemptForCutSwitch: (slotKey: String, reason: String) -> Unit = { _, _ -> },
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
    /**
     * 本地 OP 抛出 [AccountPlanBlockedException]（封禁 / 登录次数上限）时回调；
     * 调度器本身只记 ledger，由协调器执行 mark_banned / mark_max_login 并移出计划。
     */
    private val onAccountPlanBlocked: suspend (
        alias: String,
        deviceId: String,
        ex: AccountPlanBlockedException,
        source: String,
    ) -> Unit = { _, _, _, _ -> },
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

    fun notifyLiveVisitOutcome(slotKey: String, completed: Boolean) {
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        clearInsertionInflightIfKind("live_visit", sk)
        AutoSwitchLocalPlanStore.markInsertionState(
            sk,
            "live_visit",
            if (completed) PlanStepState.DONE else PlanStepState.FAILED,
        )
        AutoSwitchLocalPlanStore.clearActiveLiveVisit()
        maybeResumePendingCutSwitch()
    }

    /** 暂缓执行时回退 RUNNING→PENDING，允许下轮 tick 重新入队，避免永久堵涨粉。 */
    fun requeueLiveVisitInsertion(slotKey: String) {
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        clearInsertionInflightIfKind("live_visit", sk)
        AutoSwitchLocalPlanStore.markInsertionState(sk, "live_visit", PlanStepState.PENDING)
        AutoSwitchLocalPlanStore.clearActiveLiveVisit()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】主播访问回退 PENDING 待重入队 slot=$sk")
        }
    }

    /** 互点收尾：从 plan / prefs / in-flight / RUNNING 槽位多路解析 slot，避免 early clear 导致状态泄漏。 */
    fun resolveMutualOutcomeSlotKey(): String {
        AutoSwitchLocalPlanStore.activeMutualSlotKey()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        PrefsStore.getAutoSwitchMutualSlotKey()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        insertionInflight
            ?.takeIf { it.second == "mutual_traffic" }
            ?.first?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return AutoSwitchLocalPlanStore.get()?.slots
            ?.firstOrNull { it.mutual_traffic.state == PlanStepState.RUNNING }
            ?.slot_key?.trim().orEmpty()
    }

    fun finalizeMutualTrafficOutcome(switchResult: String) {
        val completed = mutualSwitchResultCompleted(switchResult)
        val sk = resolveMutualOutcomeSlotKey()
        if (sk.isNotBlank()) {
            notifyMutualOutcome(sk, completed)
            return
        }
        AutoSwitchLocalPlanStore.get()?.slots.orEmpty()
            .filter { it.mutual_traffic.state == PlanStepState.RUNNING }
            .forEach { notifyMutualOutcome(it.slot_key, completed) }
        insertionInflight?.takeIf { it.second == "mutual_traffic" }?.let { insertionInflight = null }
    }

    fun finalizeLiveVisitOutcome(completed: Boolean) {
        val sk = AutoSwitchLocalPlanStore.activeLiveVisitSlotKey()?.trim().orEmpty()
            .ifBlank {
                insertionInflight
                    ?.takeIf { it.second == "live_visit" }
                    ?.first?.trim().orEmpty()
            }
            .ifBlank {
                AutoSwitchLocalPlanStore.get()?.slots
                    ?.firstOrNull { it.live_visit.state == PlanStepState.RUNNING }
                    ?.slot_key?.trim().orEmpty()
            }
        if (sk.isNotBlank()) {
            notifyLiveVisitOutcome(sk, completed)
            return
        }
        AutoSwitchLocalPlanStore.get()?.slots.orEmpty()
            .filter { it.live_visit.state == PlanStepState.RUNNING }
            .forEach { notifyLiveVisitOutcome(it.slot_key, completed) }
        insertionInflight?.takeIf { it.second == "live_visit" }?.let { insertionInflight = null }
        AutoSwitchLocalPlanStore.clearActiveLiveVisit()
    }

    private fun mutualSwitchResultCompleted(switchResult: String): Boolean {
        val msg = switchResult.trim()
        if (msg.isBlank()) return false
        if (msg.contains("跳过") || msg.contains("未就绪") || msg.contains("仍未登录") || msg.contains("异常")) {
            return false
        }
        return msg.contains("已执行 teamwork Videos 互动")
    }

    @Volatile
    private var recoveryTickNext = false

    fun hasBundleLoaded(): Boolean = bundle != null

    fun requestImmediateTick(recoveryTick: Boolean = false) {
        scope.launch {
            if (recoveryTick) recoveryTickNext = true
            try {
                runCatching { tickOnce() }.onFailure { err ->
                    LogCenter.op(alias, "【自动切号】即时调度 tick 异常：${err.message}")
                }
            } finally {
                recoveryTickNext = false
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
        reviveLoginAbortedInsertions(slotKey, reviveMutual = false)
    }

    /**
     * 登录保障恢复成功后：把因登录失败钉死的发作品/互点 FAILED 回退 PENDING，允许同 slot 重新入队。
     * 账号切换导致的终态不走此路径（会先 invalidateSlotAlignment）。
     */
    fun reviveLoginAbortedInsertions(slotKey: String, reviveMutual: Boolean = true) {
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        val slot = AutoSwitchLocalPlanStore.slot(sk) ?: return
        val revivedKinds = mutableListOf<String>()
        if (slot.publish.state == PlanStepState.FAILED) {
            AutoSwitchLocalPlanStore.markInsertionState(sk, "publish", PlanStepState.PENDING)
            revivedKinds.add("publish")
        }
        if (reviveMutual && slot.mutual_traffic.state == PlanStepState.FAILED) {
            AutoSwitchLocalPlanStore.markInsertionState(sk, "mutual_traffic", PlanStepState.PENDING)
            revivedKinds.add("mutual_traffic")
        }
        if (revivedKinds.isNotEmpty() && alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】登录恢复后重开插入任务 slot=$sk kinds=${revivedKinds.joinToString(",")}",
            )
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
        // 主播访问：上班后先消化完再涨粉（PENDING/RUNNING 均拦截）
        when (entry.live_visit.state) {
            PlanStepState.PENDING, PlanStepState.RUNNING, PlanStepState.WAITING ->
                return "本时段主播访问未完成 slot=${entry.slot_key}"
            else -> Unit
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
        if (AutoSwitchLocalPlanStore.isLiveVisitContextOccupying()) return "主播访问占用中"
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
        if (kind == "mutual_traffic") {
            PrefsStore.setAutoSwitchMutualSlotKey(slotKey.trim())
        }
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
        AutoSwitchLedger.syncSessionContextFromPrefs()
        val committed = AutoSwitchLocalPlanStore.commitCutSwitchChainCompleted(
            slotKey = sk,
            username = user,
            method = completionMethod,
        )
        if (!committed) {
            // 备份/清理已完成：ledger 写入失败时不得整段重置，否则会在本时段无限重跑切号链
            invalidateSlotAlignmentRestoreOnly(sk, "plan+ledger 切号完成写入失败")
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】切号完成同事务写入失败 slot=$sk（已保留备份/清理，仅续跑还原/登录）")
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
        AutoSwitchLedger.syncSessionContextFromPrefs()
        AutoSwitchLocalPlanStore.revalidateCutSwitchCompletionsAgainstLedger { sk ->
            AutoSwitchLedger.hasCutSwitchChainCompleted(sk)
        }
    }

    private fun refreshBundleFromPlan(cloud: AutoSwitchBundle) {
        val wasLogin = bundle?.prefersLoginCutSwitch() == true
        bundle = AutoSwitchLocalPlanStore.applyRuntimeToBundle(cloud.withCurrentTimeSlotHighlight())
        syncBundleHolder()
        // 切到登录切号后，必须丢掉已入队的还原 OP，否则会仍按备份方案还原
        if (bundle?.prefersLoginCutSwitch() == true) {
            purgePendingRestoreOpsForLoginScheme(reason = if (wasLogin) "登录切号刷新" else "切号方案改为登录切号")
        }
    }

    /** 登录切号生效时取消队列中尚未执行的 tiktok_restore。 */
    private fun purgePendingRestoreOpsForLoginScheme(reason: String) {
        val removed = mutableListOf<String>()
        val kept = opQueue.filterNot { op ->
            val isAutoRestore = op.op_type == "tiktok_restore" &&
                (op.payload?.source?.trim().orEmpty() == "auto_switch" ||
                    !op.payload?.auto_switch_slot_key.isNullOrBlank())
            if (isAutoRestore) {
                removed.add(op.op_id)
                true
            } else {
                false
            }
        }
        if (removed.isEmpty()) return
        opQueue.clear()
        opQueue.addAll(kept)
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】$reason：已取消 ${removed.size} 个待执行还原任务")
        }
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
                // 切号登录链抛出的封禁/登录上限不可只记失败：须上报云端并移出计划，否则会卡在 RESTORE_OR_LOGIN。
                if (err is AccountPlanBlockedException) {
                    runCatching {
                        onAccountPlanBlocked(
                            alias,
                            deviceId,
                            err,
                            "本地OP(${op.op_type})",
                        )
                    }.onFailure { handleErr ->
                        LogCenter.op(
                            alias,
                            "【自动切号】本地 OP 账号阻断后续处理失败 ${op.op_type}：${handleErr.message}",
                        )
                    }
                }
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
        if (recoveryTickNext) {
            AutoSwitchLocalPlanStore.abandonExpiredIncompleteCutSwitches(scheduleDate, minuteOfDay)
        }
        val currentSlotEntry = AutoSwitchLocalPlanStore.resolveSlotForMinute(plan, scheduleDate, minuteOfDay)
        val incompleteRecovery = if (recoveryTickNext) {
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

        ensurePlanSlotEnvironment(slotEntry)

        if (!AutoSwitchLocalPlanStore.isCutSwitchDone(slotKey)) {
            preemptForCutSwitchIfNeeded(slotKey, "PLAN 切号待执行")
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
            val dueLabel = dueTimedInsertionKindNeedingPreempt(
                bundle = currentBundle,
                planSlot = planSlot,
                minuteOfDay = minuteOfDay,
                slotKey = slotKey,
                targetRowIndex = slotEntry.target_row_index,
                busyReason = reason,
            )
            if (dueLabel == null) {
                logTickSkipThrottled(
                    describeBusySkipWithoutDueInsertion(
                        bundle = currentBundle,
                        planSlot = planSlot,
                        minuteOfDay = minuteOfDay,
                        slotKey = slotKey,
                        targetRowIndex = slotEntry.target_row_index,
                        busyReason = reason,
                    ),
                )
                return
            }
            // 到点发作品/互点：对齐切号，设独占抢占让涨粉协作退出，再入队（不等窗口滑过）。
            preemptGrowthForDueTimedInsertion(slotKey, dueLabel, reason)
            awaitTikTokExclusiveIdle(maxMs = 20_000L, forceEnqueueLabel = "到点$dueLabel")
            onForceStopTikTokForExclusive("到点${dueLabel}入队前（涨粉收尾后）")
            // 对齐切号：协作退出超时仍继续入队。仅登录保障 / 本地 OP 队列等硬忙因才放弃本 tick。
            val hardBusy = deviceBusyReasonForInsertionScheduling()
                ?.takeUnless { it == "独占抢占中" || it.startsWith("自动化执行中") }
            if (hardBusy != null) {
                logTickSkipThrottled("插入任务暂缓（到点${dueLabel}抢占后硬忙）：$hardBusy")
                return
            }
            if (ExecutionScope.isActive() && alias.isNotBlank()) {
                LogCenter.op(
                    alias,
                    "【自动切号】到点${dueLabel}抢占后涨粉未退尽，仍继续入队 slot=$slotKey",
                )
            }
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
        // 源=目标：仅当 plan 活跃标记/源字段已证明设备就在目标号上时才跳过。
        // 禁止用 accounts.size==1 推断同源——那会把「首次 bootstrap 清库登录」误短路掉。
        if (!cs.isDone()) {
            if (
                canShortCircuitSameAccountCutSwitch(cs) &&
                isDeviceAlreadyOnCutSwitchTarget(entry, previousSlotKey)
            ) {
                val targetUser = entry.target_username.trim().removePrefix("@")
                val active = AutoSwitchLocalPlanStore.getDeviceActiveIdentity()
                if (alias.isNotBlank()) {
                    LogCenter.op(
                        alias,
                        "【自动切号】源=目标 @$targetUser（活跃标记已对齐），跳过备份→清理→还原，直接标记切号完成 slot=$slotKey",
                    )
                }
                AutoSwitchLedger.syncSessionContextFromPrefs()
                val ok = AutoSwitchLocalPlanStore.commitCutSwitchAlreadyOnTarget(
                    slotKey = slotKey,
                    username = targetUser,
                    sourceRowIndex = active?.first,
                    sourceUsername = active?.second,
                )
                if (ok) {
                    cancelDuplicatePendingCutSwitchOps(slotKey)
                    cutSwitchInflightSlotKey = null
                    pendingCutSwitchSlotKey = null
                    if (shouldAllowGrowthBaseline()) {
                        onTryResumeGrowth()
                    }
                    return
                }
                if (alias.isNotBlank()) {
                    LogCenter.op(
                        alias,
                        "【自动切号】源=目标短路失败（可能清理已执行，将续跑还原/登录）slot=$slotKey",
                    )
                }
            }
        }
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
            if (bundle.prefersLoginCutSwitch()) {
                cancelDuplicatePendingCutSwitchOps(slotKey, restoreOnly = true)
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
                if (bundle.prefersLoginCutSwitch()) {
                    // 登录切号：跳过备份与还原，清理后 exclusive 自动登录目标号
                    cancelDuplicatePendingCutSwitchOps(slotKey, restoreOnly = true)
                    AutoSwitchLocalPlanStore.setCutSwitchMode(
                        slotKey,
                        "bootstrap",
                        sourceRow?.row_index,
                        sourceRow?.username?.trim(),
                    )
                    if (alias.isNotBlank()) {
                        LogCenter.op(
                            alias,
                            "【自动切号】切号方案=登录切号：跳过备份/还原，清理后直接登录 " +
                                "→ @${entry.target_username.trim()}",
                        )
                    }
                    enqueueBootstrapCutSwitch(bundle, targetRow, slotKey)
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
                if (bundle.prefersLoginCutSwitch()) {
                    // 方案中途改为登录切号：清理前先丢掉已排队还原
                    cancelDuplicatePendingCutSwitchOps(slotKey, restoreOnly = true)
                }
                if (planSlot.cut_switch.mode == "bootstrap" || bundle.prefersLoginCutSwitch()) {
                    enqueueBootstrapClearOnly(bundle, targetRow, slotKey)
                } else {
                    enqueueSwitchClearOnly(bundle, targetRow, slotKey)
                }
            }
            CutSwitchStepKind.RESTORE_OR_LOGIN -> {
                if (bundle.prefersLoginCutSwitch()) {
                    cancelDuplicatePendingCutSwitchOps(slotKey, restoreOnly = true)
                }
                enqueueRestoreOnly(bundle, targetRow, slotKey, sourceRow?.row_index)
            }
        }
        if (enqueued) {
            cutSwitchInflightSlotKey = slotKey
        } else if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】切号入队失败 slot=$slotKey step=$nextStep target=@${entry.target_username}")
        }
    }

    /**
     * 是否已有 plan 证据表明设备当前就在目标账号上（可安全跳过切号链）。
     * 只信 device_active / 已持久化 source / 上一完成时段；不信 accounts.size==1。
     */
    private fun isDeviceAlreadyOnCutSwitchTarget(
        entry: SlotPlanEntry,
        previousSlotKey: String?,
    ): Boolean {
        val tgtRi = entry.target_row_index
        val tgtUser = entry.target_username.trim().removePrefix("@")

        AutoSwitchLocalPlanStore.getDeviceActiveIdentity()?.let { (ri, uname) ->
            if (ri >= 0 && ri == tgtRi) return true
            if (tgtUser.isNotEmpty() && uname.equals(tgtUser, ignoreCase = true)) return true
        }

        val cs = AutoSwitchLocalPlanStore.slot(entry.slot_key)?.cut_switch ?: entry.cut_switch
        cs.source_row_index?.takeIf { it >= 0 }?.let { if (it == tgtRi) return true }
        cs.source_username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }?.let { src ->
            if (tgtUser.isNotEmpty() && src.equals(tgtUser, ignoreCase = true)) return true
        }

        val prevSk = previousSlotKey?.trim()?.takeIf { it.isNotEmpty() && it != entry.slot_key }
        if (prevSk != null && AutoSwitchLocalPlanStore.isCutSwitchDone(prevSk)) {
            AutoSwitchLocalPlanStore.slot(prevSk)?.let { prev ->
                if (prev.target_row_index == tgtRi) return true
                val prevUser = prev.target_username.trim().removePrefix("@")
                if (tgtUser.isNotEmpty() && prevUser.equals(tgtUser, ignoreCase = true)) return true
            }
        }
        return false
    }

    /**
     * 同账号短路仅在「尚未清理 TikTok」时安全。
     * 清理已 DONE/RUNNING 后数据目录可能已空，必须续跑还原/登录。
     */
    private fun canShortCircuitSameAccountCutSwitch(cs: CutSwitchPlanSteps): Boolean {
        if (cs.clear == PlanStepState.DONE || cs.clear == PlanStepState.RUNNING) return false
        if (cs.restore_or_login == PlanStepState.RUNNING) return false
        return true
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

    /** PLAN 时段目标与 device_active 不一致：假完成切号，重置并抢占。 */
    private fun ensurePlanSlotEnvironment(slotEntry: SlotPlanEntry) {
        val sk = slotEntry.slot_key.trim()
        if (sk.isEmpty() || !AutoSwitchLocalPlanStore.isCutSwitchDone(sk)) return
        val targetUser = slotEntry.target_username.trim().removePrefix("@")
        if (targetUser.isEmpty()) return
        val active = AutoSwitchLocalPlanStore.getDeviceActiveIdentity() ?: return
        val (activeRi, activeUser) = active
        val targetRi = slotEntry.target_row_index
        val aligned = (targetRi >= 0 && activeRi == targetRi) ||
            activeUser.equals(targetUser, ignoreCase = true)
        if (aligned) return
        invalidateSlotAlignment(
            sk,
            "PLAN 时段目标 @$targetUser 与活跃标记 @$activeUser 不一致",
        )
        preemptForCutSwitchIfNeeded(sk, "活跃标记与 PLAN 目标不一致")
    }

    /** PLAN 切号优先：发作品/互点等占用一律清空，不得阻塞切号调度。 */
    private fun preemptForCutSwitchIfNeeded(slotKey: String, reason: String) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        val occupied = PublishPostGrowthGate.isBlockingGrowth() ||
            hasActiveHighPriorityInsertion() ||
            AutoSwitchLocalPlanStore.isPublishContextOccupying() ||
            AutoSwitchLocalPlanStore.isMutualContextOccupying() ||
            AutoSwitchLocalPlanStore.isLiveVisitContextOccupying() ||
            isPublishExecutionActive() ||
            isMutualExecutionActive() ||
            isLiveVisitExecutionActive()
        if (!occupied) return
        onPreemptForCutSwitch(sk, reason)
        insertionInflight = null
        pendingCutSwitchSlotKey = null
        AutoSwitchLocalPlanStore.resumeCutSwitchFromWaiting(sk)
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】PLAN 切号抢占 slot=$sk：$reason")
        }
    }

    private fun isInsertionKindStillActive(kind: String): Boolean = when (kind) {
        "publish" -> isPublishInsertionStillActive()
        "mutual_traffic" -> isMutualInsertionStillActive()
        "live_visit" -> isLiveVisitInsertionStillActive()
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

    private fun isLiveVisitInsertionStillActive(): Boolean {
        if (isLiveVisitExecutionActive()) return true
        if (AutoSwitchLocalPlanStore.isLiveVisitContextOccupying()) return true
        val sk = insertionInflight?.first?.trim().orEmpty()
        if (sk.isBlank()) return false
        val state = AutoSwitchLocalPlanStore.insertionState(sk, "live_visit")
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
        logTickSkipThrottled(reason, "【自动切号】切号暂缓：")
    }

    private fun logTickSkip(reason: String) {
        logTickSkipThrottled(reason)
    }

    private fun logTickSkipThrottled(reason: String, prefix: String = "【自动切号】调度暂缓：") {
        val now = System.currentTimeMillis()
        if (reason == lastTickSkipLogKey && now - lastTickSkipLogMs < TICK_SKIP_LOG_COOLDOWN_MS) return
        lastTickSkipLogKey = reason
        lastTickSkipLogMs = now
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "$prefix$reason")
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
        if (PrefsStore.isAutoSwitchLoginRecoveryActive() || PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
            return PrefsStore.autoSwitchTikTokLoginHoldReason()?.let { "登录保障恢复中：$it" }
                ?: "登录保障恢复中（暂停涨粉/发作品/引流调度）"
        }
        // ExecutionScope 也用于切号/改资料等独占链，勿一律说成「涨粉」
        if (ExecutionScope.isActive()) {
            val phase = ProcessDiagnostics.currentPhase().ifBlank { "idle" }
            return "自动化执行中 depth=${ExecutionScope.depth()} 阶段=$phase"
        }
        if (PrefsStore.isAutoSwitchExclusivePreempt()) return "独占抢占中"
        if (busy.get()) return "本地 OP 执行中"
        if (opQueue.isNotEmpty()) return "本地 OP 队列非空（${opQueue.size}）"
        insertionInflight?.let { (sk, kind) ->
            if (isInsertionKindStillActive(kind)) return "插入 in-flight：$kind slot=$sk"
        }
        return null
    }

    /**
     * 设备忙且本 tick 没有「到点可抢占」的发作品/互点时，写清原因，避免误读成「插入被永久挡住」。
     */
    private fun describeBusySkipWithoutDueInsertion(
        bundle: AutoSwitchBundle,
        planSlot: AutoSwitchPlanSlot,
        minuteOfDay: Int,
        slotKey: String,
        targetRowIndex: Int?,
        busyReason: String,
    ): String {
        if (!busyReason.startsWith("自动化执行中")) {
            return "插入任务暂缓：$busyReason"
        }
        if (PublishPostGrowthGate.isBlockingGrowth()) {
            return "发作品闸门占用中，涨粉继续"
        }
        if (AutoSwitchSlotWindow.isWithinSwitchSafeBuffer(minuteOfDay, planSlot, SWITCH_SAFE_BUFFER_MIN)) {
            return "切号安全缓冲内，发作品/互点不插入，涨粉继续"
        }
        val pubMinute = planSlot.auto_publish_minute
        val mutualMinute = planSlot.auto_mutual_minute
        if (pubMinute == null && mutualMinute == null) {
            return "本时段无发作品/引流互点计划，涨粉继续"
        }
        val nowLabel = formatMinuteOfDay(minuteOfDay)
        val parts = mutableListOf<String>()
        if (pubMinute != null) {
            when {
                !AutoSwitchSlotWindow.isWithinInsertionTrigger(minuteOfDay, planSlot, pubMinute) ->
                    parts.add("发作品时间未到(计划${formatMinuteOfDay(pubMinute)}/现$nowLabel)")
                !isTimedInsertionSchedulable(slotKey.trim(), "publish") ->
                    parts.add("发作品已处理或进行中")
            }
        }
        if (mutualMinute != null) {
            val targetUser = bundle.accounts.orEmpty()
                .firstOrNull { (it.row_index ?: -1) == (targetRowIndex ?: -1) }
                ?.username?.trim().orEmpty()
            when {
                !AutoSwitchSlotWindow.isWithinInsertionTrigger(minuteOfDay, planSlot, mutualMinute) ->
                    parts.add("引流互点时间未到(计划${formatMinuteOfDay(mutualMinute)}/现$nowLabel)")
                targetUser.isBlank() || !bundle.isMutualTrafficConfiguredFor(targetUser) ->
                    parts.add("引流互点未配置")
                !isTimedInsertionSchedulable(slotKey.trim(), "mutual_traffic") ->
                    parts.add("引流互点已处理或进行中")
            }
        }
        return if (parts.isEmpty()) {
            "发作品/引流互点暂不可调度，涨粉继续"
        } else {
            parts.joinToString("；") + "，涨粉继续"
        }
    }

    /**
     * 发作品/引流互点已到点，但调度被「自动化执行中」（多为涨粉）挡住：
     * 返回可读标签以触发独占抢占；其它忙因（登录保障/OP 队列等）不抢占。
     */
    private fun dueTimedInsertionKindNeedingPreempt(
        bundle: AutoSwitchBundle,
        planSlot: AutoSwitchPlanSlot,
        minuteOfDay: Int,
        slotKey: String,
        targetRowIndex: Int?,
        busyReason: String,
    ): String? {
        if (!busyReason.startsWith("自动化执行中")) return null
        if (PublishPostGrowthGate.isBlockingGrowth()) return null
        if (AutoSwitchSlotWindow.isWithinSwitchSafeBuffer(minuteOfDay, planSlot, SWITCH_SAFE_BUFFER_MIN)) {
            return null
        }
        val sk = slotKey.trim()
        if (sk.isEmpty()) return null

        val pubMinute = planSlot.auto_publish_minute
        if (
            pubMinute != null &&
            AutoSwitchSlotWindow.isWithinInsertionTrigger(minuteOfDay, planSlot, pubMinute) &&
            isTimedInsertionSchedulable(sk, "publish")
        ) {
            return "发作品"
        }

        val mutualMinute = planSlot.auto_mutual_minute
        if (
            mutualMinute != null &&
            AutoSwitchSlotWindow.isWithinInsertionTrigger(minuteOfDay, planSlot, mutualMinute) &&
            isTimedInsertionSchedulable(sk, "mutual_traffic")
        ) {
            val targetUser = bundle.accounts.orEmpty()
                .firstOrNull { (it.row_index ?: -1) == (targetRowIndex ?: -1) }
                ?.username?.trim().orEmpty()
            if (targetUser.isNotBlank() && bundle.isMutualTrafficConfiguredFor(targetUser)) {
                return "引流互点"
            }
        }
        return null
    }

    private fun isTimedInsertionSchedulable(slotKey: String, kind: String): Boolean {
        val state = AutoSwitchLocalPlanStore.insertionState(slotKey, kind)
            ?: when (kind) {
                "publish" -> AutoSwitchLocalPlanStore.slot(slotKey)?.publish?.state
                "mutual_traffic" -> AutoSwitchLocalPlanStore.slot(slotKey)?.mutual_traffic?.state
                else -> null
            }
        when (state) {
            PlanStepState.DONE, PlanStepState.SKIPPED, PlanStepState.FAILED, PlanStepState.RUNNING ->
                return false
            else -> Unit
        }
        return !isInsertionInflight(kind, slotKey)
    }

    private suspend fun preemptGrowthForDueTimedInsertion(
        slotKey: String,
        kindLabel: String,
        busyReason: String,
    ) {
        if (alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】到点$kindLabel 抢占涨粉 slot=$slotKey：原暂缓=$busyReason",
            )
        }
        onPauseGrowth()
        onForceStopTikTokForExclusive("到点${kindLabel}入队前")
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
            if (bundle?.prefersLoginCutSwitch() == true) {
                LogCenter.op(alias, "【自动切号】登录切号续跑：@$targetUser 清理后自动登录（忽略备份）")
            } else {
                logCutSwitchMissingBackupUseAutoLogin(slotKey, targetUser, cloudBackupLabel)
                LogCenter.op(alias, "【自动切号】断点续跑：@$targetUser 无本地备份，入队自动登录")
            }
            enqueueExclusiveOp(
                buildClearOp(aliasClean, pkg(bundle), targetRi, targetUser, slotKey, "exclusive_activate_target"),
            )
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
        if (
            bundle?.prefersLoginCutSwitch() != true &&
            targetRow.backup_name?.trim()?.isNotEmpty() == true &&
            targetRow.backup_name != "-" &&
            resolved == null
        ) {
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
        // 登录切号：强制不还原备份，一律走清理后自动登录
        if (bundle?.prefersLoginCutSwitch() == true) return null
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
            LiveVisitSlotInsertion(),
            PublishPostSlotInsertion(),
            MutualTrafficSlotInsertion(),
        )
    }
}
