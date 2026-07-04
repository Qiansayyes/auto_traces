package com.autotraces.app

import android.content.Context
import com.autotraces.app.autoswitch.AutoSwitchBundleStore
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自治调度器：每分钟对账切号；对齐后读 **bundle** 本时段 `auto_publish_minute` / `auto_mutual_minute` 到点触发独占 OP。
 *
 * - 时间点：bundle JSON/内存（每 slot 字段），**不**读 plan JSON
 * - 插入执行状态：内存 [AutoSwitchLocalPlanStore.insertionState]
 * - plan JSON：仅环境归属 + 切号三步
 */
class AutoSwitchLocalScheduler(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val traceExecutor: TraceExecutor,
    private val executeExclusiveOp: suspend (alias: String, deviceId: String, op: DeviceOp) -> Unit,
    private val onSlotAligned: () -> Unit = {},
    private val onCutSwitchStarting: () -> Unit = {},
    private val onSlotTransition: (prevSlotKey: String, newSlotKey: String) -> Unit = { _, _ -> },
    private val onInsertionStarting: () -> Unit = {},
    private val onExclusiveOpDrained: () -> Unit = {},
    private val isPublishExecutionActive: () -> Boolean = { false },
    private val onStalePublishInflight: (slotKey: String) -> Unit = {},
    private val slotInsertions: List<AutoSwitchSlotInsertion> = DEFAULT_INSERTIONS,
) : AutoSwitchSlotInsertionHost {
    companion object {
        const val SWITCH_SAFE_BUFFER_MIN = 5
        private val DEFAULT_INSERTIONS = listOf(
            PublishPostSlotInsertion(),
            MutualTrafficSlotInsertion(),
        )
    }

    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")
    private val cutSwitchExecutor = AutoSwitchCutSwitchExecutor(appContext, traceExecutor)
    private val tickMutex = Mutex()
    private val opQueue = ConcurrentLinkedQueue<DeviceOp>()
    private val busy = AtomicBoolean(false)

    private var tickJob: Job? = null
    private var drainJob: Job? = null

    @Volatile
    private var cutSwitchInflight = false

    @Volatile
    private var insertionInflight: Pair<String, String>? = null

    @Volatile
    private var lastTickLogKey: String? = null

    @Volatile
    private var lastSlotKey: String? = null

    fun isRunning(): Boolean = tickJob?.isActive == true

    fun clearPublishInsertionInflight(slotKey: String) {
        val sk = slotKey.trim()
        val cur = insertionInflight ?: return
        if (cur.first == sk && cur.second == "publish") {
            insertionInflight = null
        }
    }

    fun clearSlotPublishAborted(slotKey: String) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        if (AutoSwitchLocalPlanStore.insertionState(sk, "publish") == PlanStepState.FAILED) {
            AutoSwitchLocalPlanStore.markInsertionState(sk, "publish", PlanStepState.PENDING)
        }
    }

    /**
     * 发作品结束/失败后更新插入状态；有可续发断点时不标 FAILED，保留 PENDING/RUNNING。
     */
    fun notifyPublishOutcome(
        slotKey: String,
        completed: Boolean,
        loginFailed: Boolean = false,
        accountChanged: Boolean = false,
    ) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        val alias = logAlias().orEmpty()
        val state = when {
            completed -> PlanStepState.DONE
            loginFailed || accountChanged -> PlanStepState.FAILED
            else -> {
                val accountKey = AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim().orEmpty()
                    .ifBlank { PrefsStore.getLogContextUsername()?.trim().orEmpty() }
                    .ifBlank { alias }
                val resumeIdx = PrefsStore.getPublishPostResumeNextIndex(accountKey)
                val materialReady = PrefsStore.isPublishPostMaterialReady()
                val resumable = resumeIdx > 0 || materialReady
                AutoSwitchLocalPlanStore.resolveInsertionFailureState(
                    slotKey = sk,
                    kind = "publish",
                    terminalFailure = false,
                    resumable = resumable,
                )
            }
        }
        if (state != PlanStepState.PENDING) {
            clearPublishInsertionInflight(sk)
        }
        AutoSwitchLocalPlanStore.markInsertionState(sk, "publish", state)
        if (state == PlanStepState.PENDING) {
            val attempt = AutoSwitchLocalPlanStore.insertionAttempts(sk, "publish")
            val max = AutoSwitchLocalPlanStore.maxInsertionAttempts("publish")
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】发作品未完成，本时段稍后重试 slot=$sk（$attempt/$max）")
            }
        } else if (state == PlanStepState.FAILED && alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】发作品本时段终止 slot=$sk（已达重试上限）")
        }
    }

    /** 进程重启后：恢复发作品上下文并尝试续发或重置 stale RUNNING。 */
    fun recoverStalePublishInflightOnStart() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (isPublishExecutionActive()) return
        AutoSwitchLocalPlanStore.rehydratePublishContextFromPrefsIfNeeded()
        val sk = AutoSwitchLocalPlanStore.activePublishSlotKey()?.trim().orEmpty()
            .ifBlank { lastSlotKey.orEmpty() }
            .ifBlank { PrefsStore.getAutoSwitchLastSlotKey().orEmpty() }
        if (sk.isEmpty()) return
        if (!AutoSwitchLocalPlanStore.hasRecoverableActivePublish(sk) &&
            !AutoSwitchLocalPlanStore.isPublishInflight(sk)
        ) {
            return
        }
        onStalePublishInflight(sk)
    }

    fun isExclusiveWorkActive(): Boolean = busy.get() || opQueue.isNotEmpty() ||
        insertionInflight != null ||
        PublishPostGrowthGate.isBlockingGrowth()

    fun start() {
        if (tickJob?.isActive == true) return
        AutoSwitchLocalPlanStore.recoverStaleRunningInsertionStates()
        recoverStalePublishInflightOnStart()
        if (drainJob?.isActive != true) {
            drainJob = scope.launch { drainQueueLoop() }
        }
        tickJob = scope.launch {
            runCatching { tickOnce() }
            while (isActive) {
                delay(msUntilNextBeijingMinuteBoundary())
                runCatching { tickOnce() }.onFailure { err ->
                    logAlias()?.let { LogCenter.op(it, "【自动切号】调度 tick 异常：${err.message}") }
                }
            }
        }
        logAlias()?.let { LogCenter.op(it, "【自动切号】本地调度器已启动（每分钟对账）") }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
        drainJob?.cancel()
        drainJob = null
        opQueue.clear()
        busy.set(false)
        insertionInflight = null
    }

    fun requestImmediateTick() {
        scope.launch {
            runCatching { tickOnce() }
        }
    }

    fun shouldAllowGrowthBaseline(): Boolean = growthBaselineBlockReason() == null

    fun growthBaselineBlockReason(): String? {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return null
        val slotUser = resolveCurrentSlotUsername() ?: return "当前不在任何计划时段"
        val plan = AutoSwitchLocalPlanStore.get() ?: AutoSwitchLocalPlan()
        if (!plan.isEnvironmentAlignedWith(slotUser)) {
            if (plan.isCutSwitchInProgress()) {
                return formatCutSwitchProgress(plan)
            }
            return "环境归属=${plan.environment_owner_username ?: "空"} ≠ 时段=@$slotUser，切号未完成"
        }
        return growthPullBlockReason()
    }

    private fun growthPullBlockReason(): String? {
        if (PublishPostGrowthGate.isBlockingGrowth()) return "发作品独占发布中"
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
            return PrefsStore.autoSwitchTikTokLoginHoldReason()?.let { "登录保障恢复中：$it" }
                ?: "登录保障恢复中（暂停涨粉/发作品/引流调度）"
        }
        if (AutoSwitchLocalPlanStore.isPublishContextOccupying()) return "发作品占用中"
        if (AutoSwitchLocalPlanStore.isMutualContextOccupying()) return "引流占用中"
        if (busy.get()) return "独占 OP 执行中"
        if (opQueue.isNotEmpty()) return "独占 OP 队列非空（${opQueue.size}）"
        insertionInflight?.let { (sk, kind) ->
            return "插入任务进行中 slot=$sk kind=$kind"
        }
        return null
    }

    fun shouldBlockGrowthPull(): Boolean = growthPullBlockReason() != null

    suspend fun tickOnce() {
        tickMutex.withLock { tickOnceUnlocked() }
    }

    private suspend fun tickOnceUnlocked() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) {
            stop()
            return
        }
        if (PublishPostGrowthGate.isBlockingGrowth()) {
            logAlias()?.let { logTickSkipThrottled(it, "发作品独占发布中") }
            return
        }

        val alias = logAlias() ?: return
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (deviceId.isEmpty()) return

        val bundle = AutoSwitchBundleStore.get() ?: run {
            logTickSkipThrottled(alias, "bundle 为空")
            return
        }

        val slotUser = resolveCurrentSlotUsername(bundle) ?: run {
            logTickSkipThrottled(alias, "当前不在计划时段")
            return
        }

        val plan = AutoSwitchLocalPlanStore.get() ?: AutoSwitchLocalPlan()

        if (plan.isEnvironmentAlignedWith(slotUser)) {
            PrefsStore.setLogContextUsername(slotUser)
            tryScheduleInsertionsOrGrowth(alias, deviceId, bundle, slotUser)
            return
        }

        if (cutSwitchInflight) {
            logTickSkipThrottled(alias, "切号执行中")
            return
        }

        val freshPlan = AutoSwitchLocalPlanStore.get() ?: AutoSwitchLocalPlan()
        if (!freshPlan.isCutSwitchInProgress()) {
            onCutSwitchStarting()
            val ownerEmpty = freshPlan.environment_owner_username.isNullOrBlank()
            AutoSwitchLocalPlanStore.beginCutSwitch(skipBackup = ownerEmpty)
            LogCenter.op(
                alias,
                "【自动切号】时段=@$slotUser 环境归属=${freshPlan.environment_owner_username ?: "空"}，开始切号" +
                    if (ownerEmpty) "（无归属：跳过备份，清理后登录）" else "",
            )
        }

        cutSwitchInflight = true
        try {
            repeat(4) {
                val workingPlan = AutoSwitchLocalPlanStore.get() ?: return@repeat
                if (workingPlan.isEnvironmentAlignedWith(slotUser)) return@repeat
                val progressed = cutSwitchExecutor.runNextPendingStep(
                    plan = workingPlan,
                    bundle = bundle,
                    targetUsername = slotUser,
                )
                val after = AutoSwitchLocalPlanStore.get()
                if (after?.isEnvironmentAlignedWith(slotUser) == true) {
                    PrefsStore.setLogContextUsername(slotUser)
                    bundle.resolveCurrentSlotContext()?.let { slotCtx ->
                        lastSlotKey = slotCtx.slotKey
                        AutoSwitchLedger.recordCutSwitchChainCompleted(
                            slotKey = slotCtx.slotKey,
                            username = slotUser,
                            method = "local_executor",
                        )
                    }
                    LogCenter.op(alias, "【自动切号】时段 @$slotUser 环境已对齐")
                    tryScheduleInsertionsOrGrowth(alias, deviceId, bundle, slotUser)
                    return@repeat
                }
                if (!progressed) return@repeat
            }
        } finally {
            cutSwitchInflight = false
        }
    }

    private suspend fun tryScheduleInsertionsOrGrowth(
        alias: String,
        deviceId: String,
        bundle: AutoSwitchBundle,
        slotUser: String,
    ) {
        if (isPublishExecutionActive()) {
            logTickSkipThrottled(alias, "发作品执行中，暂不调度引流")
            return
        }
        if (busy.get() || opQueue.isNotEmpty()) {
            logTickSkipThrottled(alias, "独占 OP 执行/排队中")
            return
        }
        val slotCtx = bundle.resolveCurrentSlotContext() ?: run {
            logTickSkipThrottled(alias, "已对齐 @$slotUser（时段上下文缺失）")
            onSlotAligned()
            return
        }
        val slotKey = slotCtx.slotKey
        val prevSlotKey = lastSlotKey?.trim()?.takeIf { it.isNotEmpty() && it != slotKey }
        if (prevSlotKey != null) {
            onSlotTransition(prevSlotKey, slotKey)
            LogCenter.op(alias, "【自动切号】时段切换 $prevSlotKey → $slotKey")
        }
        lastSlotKey = slotKey
        PrefsStore.setAutoSwitchLastSlotKey(slotKey)
        val ctx = AutoSwitchSlotTickContext(
            alias = alias,
            deviceId = deviceId,
            bundle = bundle,
            scheduleDate = slotCtx.scheduleDate,
            slotKey = slotCtx.slotKey,
            slot = slotCtx.slot,
            account = slotCtx.account,
            minuteOfDay = slotCtx.minuteOfDay,
        )
        var enqueued = false
        for (insertion in slotInsertions) {
            if (insertion.trySchedule(ctx, this)) {
                enqueued = true
                break
            }
        }
        if (enqueued) {
            logTickSkipThrottled(alias, "已触发插入任务，涨粉暂停")
            return
        }
        logTickSkipThrottled(alias, "已对齐 @$slotUser")
        if (shouldAllowGrowthBaseline()) {
            onSlotAligned()
        } else {
            growthBaselineBlockReason()?.let { block ->
                LogCenter.op(alias, "【自动切号】$block，暂不恢复涨粉")
            }
        }
    }

    private suspend fun drainQueueLoop() {
        while (scope.coroutineContext.isActive) {
            val alias = logAlias()
            val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
            val op = opQueue.poll()
            if (op == null || alias.isNullOrBlank() || deviceId.isEmpty()) {
                delay(500)
                continue
            }
            busy.set(true)
            runCatching {
                executeExclusiveOp(alias, deviceId, op)
            }.onFailure { err ->
                LogCenter.op(alias, "【自动切号】独占 OP 执行失败 ${op.op_type}：${err.message}")
            }
            busy.set(false)
            if (opQueue.isEmpty()) {
                insertionInflight = null
                onExclusiveOpDrained()
                if (shouldAllowGrowthBaseline()) {
                    onSlotAligned()
                }
            }
        }
    }

    override suspend fun enqueueInsertion(op: DeviceOp, slotKey: String, kind: String) {
        onInsertionStarting()
        PrefsStore.setAutoSwitchExclusivePreempt(true)
        markInsertionInflight(slotKey, kind)
        AutoSwitchLocalPlanStore.markInsertionState(slotKey, kind, PlanStepState.PENDING)
        opQueue.add(op)
    }

    override fun isInsertionInflight(kind: String, slotKey: String): Boolean {
        val cur = insertionInflight ?: return false
        return cur.first == slotKey && cur.second == kind
    }

    override fun markInsertionInflight(slotKey: String, kind: String) {
        insertionInflight = slotKey to kind
    }

    override fun graduateNewAccountVideoCount(account: AutoSwitchAccount) {
        val slotKey = lastSlotKey.orEmpty()
        val username = account.username?.trim().orEmpty()
        val rowIndex = account.row_index ?: -1
        if (username.isNotBlank() && rowIndex >= 0 && slotKey.isNotBlank()) {
            AutoSwitchLedger.recordVideoCountGraduate(username, rowIndex, slotKey)
        }
    }

    override fun isSlotReady(slotKey: String, targetRowIndex: Int): Boolean {
        val slotUser = resolveCurrentSlotUsername() ?: return false
        val plan = AutoSwitchLocalPlanStore.get() ?: return false
        return plan.isEnvironmentAlignedWith(slotUser)
    }

    override fun isWithinSwitchSafeBuffer(minuteOfDay: Int, slot: AutoSwitchPlanSlot): Boolean =
        AutoSwitchSlotWindow.isWithinSwitchSafeBuffer(minuteOfDay, slot, SWITCH_SAFE_BUFFER_MIN)

    override suspend fun verifyInsertionAccountLoggedIn(
        ctx: AutoSwitchSlotTickContext,
        kind: String,
    ): Boolean {
        val slotUser = ctx.account.username?.trim()?.removePrefix("@").orEmpty()
        if (slotUser.isEmpty()) return false
        val plan = AutoSwitchLocalPlanStore.get() ?: return false
        // 切号链完成且 plan 环境已对齐 ⇒ 备份已还原或自动登录已成功，不读 bundle.is_login 元数据。
        return plan.isEnvironmentAlignedWith(slotUser)
    }

    override fun insertionState(kind: String, slotKey: String): PlanStepState? =
        AutoSwitchLocalPlanStore.insertionState(slotKey, kind)

    private fun resolveCurrentSlotUsername(bundle: AutoSwitchBundle? = AutoSwitchBundleStore.get()): String? {
        val b = bundle ?: return null
        val cal = Calendar.getInstance(beijingTz)
        val scheduleDate = b.schedule_date?.trim().orEmpty()
            .ifBlank { PrefsStore.getAutoSwitchScheduleDate().orEmpty() }
            .ifBlank { beijingDateStr(cal) }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return b.resolveCurrentSlotTargetAccount(scheduleDate, minute)
            ?.username
            ?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun formatCutSwitchProgress(plan: AutoSwitchLocalPlan): String {
        val parts = mutableListOf<String>()
        parts.add(if (plan.backup_done) "备份✓" else "备份…")
        parts.add(if (plan.clear_done) "清理✓" else "清理…")
        parts.add(if (plan.restore_or_login_done) "登录✓" else "登录…")
        return "切号进行中：${parts.joinToString(" ")}"
    }

    private fun msUntilNextBeijingMinuteBoundary(): Long {
        val cal = Calendar.getInstance(beijingTz)
        val sec = cal.get(Calendar.SECOND)
        val ms = cal.get(Calendar.MILLISECOND)
        return (60_000L - (sec * 1000L + ms)).coerceIn(1L, 60_000L)
    }

    private fun logTickSkipThrottled(alias: String, reason: String) {
        val key = reason
        if (lastTickLogKey == key) return
        lastTickLogKey = key
        LogCenter.op(alias, "【自动切号】调度：$reason")
    }

    private fun logAlias(): String? = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() }
}
