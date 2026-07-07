package com.autotraces.app

import android.content.Context
import com.autotraces.app.autoswitch.AutoSwitchBundleFetchResult
import com.autotraces.app.autoswitch.AutoSwitchBundleStore
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * APP 协调器：云端 [mode_wait] 驱动 enter/exit；本地 [AutoSwitchLocalScheduler] 每分钟对账切号。
 */
object AppCoordinator {
    /** 单次 mode_wait 挂起上限（秒）；空结果后再等 [MODE_WAIT_IDLE_DELAY_MS]。 */
    private const val MODE_WAIT_TIMEOUT_SEC = 12
    private const val MODE_WAIT_IDLE_DELAY_MS = 48_000L
    private const val AUTO_SWITCH_BUNDLE_RETRY_INITIAL_MS = 15_000L
    private const val AUTO_SWITCH_BUNDLE_RETRY_MAX_MS = 120_000L
    private const val AUTO_SWITCH_EXIT_DRAIN_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private lateinit var traceExecutor: TraceExecutor
    private lateinit var autoSwitchScheduler: AutoSwitchLocalScheduler
    private lateinit var growthPullLoop: GrowthPullLoop
    private lateinit var exclusiveOpRunner: AutoSwitchExclusiveOpRunner
    private lateinit var loginRecovery: AutoSwitchLoginRecovery

    private fun initAutoSwitchRuntime() {
        loginRecovery = AutoSwitchLoginRecovery(
            scope = scope,
            appContext = appContext,
            traceExecutor = traceExecutor,
            onRecoverySuccess = {
                growthPullLoop.maybeStartInAutoSwitchIdle("登录保障恢复成功")
            },
            isBusyWithExclusiveWork = {
                exclusiveOpRunner.isPublishActive() ||
                    PublishPostGrowthGate.isBlockingGrowth() ||
                    ExecutionScope.isActive()
            },
        )
        exclusiveOpRunner = AutoSwitchExclusiveOpRunner(
            scope = scope,
            appContext = appContext,
            traceExecutor = traceExecutor,
            getConfig = { _config.value },
            setConfig = { cfg -> _config.value = cfg },
            onPauseGrowth = {
                growthPullLoop.pauseForExclusiveOp("独占 OP")
                PrefsStore.setAutoSwitchExclusivePreempt(true)
            },
            onResumeGrowth = {
                growthPullLoop.maybeStartInAutoSwitchIdle("独占 OP 结束")
            },
            onRuntimeMessage = { msg -> updateRuntime(lastMessage = msg) },
            notifyPublishOutcome = { slotKey, completed, loginFailed, accountChanged ->
                autoSwitchScheduler.notifyPublishOutcome(slotKey, completed, loginFailed, accountChanged)
            },
            clearPublishInsertionInflight = { slotKey ->
                autoSwitchScheduler.clearPublishInsertionInflight(slotKey)
            },
            clearSlotPublishAborted = { slotKey ->
                autoSwitchScheduler.clearSlotPublishAborted(slotKey)
            },
            onLoginSafeguardFailed = { alias, slotKey, reason ->
                loginRecovery.activate(alias, slotKey, reason)
            },
        )
        autoSwitchScheduler = AutoSwitchLocalScheduler(
            scope = scope,
            appContext = appContext,
            traceExecutor = traceExecutor,
            executeExclusiveOp = { alias, deviceId, op ->
                exclusiveOpRunner.runOp(alias, deviceId, op)
            },
            onSlotAligned = {
                growthPullLoop.maybeStartInAutoSwitchIdle("时段已对齐")
            },
            onCutSwitchStarting = {
                growthPullLoop.pauseForExclusiveOp("切号开始")
                loginRecovery.clearForAccountTransition("切号开始")
            },
            onSlotTransition = { prevSlotKey, newSlotKey ->
                loginRecovery.clearForAccountTransition(
                    reason = "跨时段切换",
                    prevSlotKey = prevSlotKey,
                    newSlotKey = newSlotKey,
                )
            },
            onInsertionStarting = {
                growthPullLoop.pauseForExclusiveOp("发作品/引流到点")
            },
            onExclusiveOpDrained = {
                PrefsStore.setAutoSwitchExclusivePreempt(false)
            },
            isPublishExecutionActive = { exclusiveOpRunner.isPublishActive() },
            onStalePublishInflight = { slotKey ->
                exclusiveOpRunner.handleStalePublishInflight(slotKey)
            },
        )
        growthPullLoop = GrowthPullLoop(
            scope = scope,
            appContext = appContext,
            traceExecutor = traceExecutor,
            scheduler = autoSwitchScheduler,
            getConfig = { _config.value },
            setConfig = { cfg -> _config.value = cfg },
            onRuntimeMessage = { msg -> updateRuntime(lastMessage = msg) },
        )
        PublishPostGrowthGate.installEnterHandler { alias ->
            growthPullLoop.pauseForExclusiveOp("发作品独占发布")
            true
        }
    }

    private var modeWaitJob: Job? = null
    private var autoSwitchBundleRetryJob: Job? = null
    private val modeEventMutex = Mutex()

    /** mode_wait 携带的云端 auto_switch_enabled 快照；enter/exit 事件优先。 */
    @Volatile
    private var cloudAutoSwitchEnabled: Boolean? = null

    private val _config = MutableStateFlow(AppConfig())
    val config = _config.asStateFlow()

    private val _runtimeState = MutableStateFlow(
        RuntimeState(
            currentServerUrl = "",
            localStatus = PrefsStore.getLocalStatus(),
        ),
    )
    val runtimeState = _runtimeState.asStateFlow()

    fun init(application: Context) {
        appContext = application.applicationContext
        traceExecutor = RealTraceExecutor { appContext }
        initAutoSwitchRuntime()
        AutoSwitchLedger.restoreFromPrefs()
        loginRecovery.ensureRecoveryLoopRunningIfNeeded("进程恢复")
        refreshRuntimeSnapshot()
        ensureAutoSwitchSchedulerIfNeeded()
        startModeWaitLoop()
        scope.launch {
            if (BindingRecovery.tryRecoverFromCloud(appContext)) {
                bootstrapAfterBinding()
            }
        }
    }

    suspend fun bootstrapAfterBinding() {
        val alias = PrefsStore.getAlias() ?: return
        LogCenter.op(alias, "设备已就绪")
        updateRuntime(
            localStatus = PrefsStore.getLocalStatus(),
            currentServerUrl = ApiService.getBaseUrl(),
            lastMessage = autoSwitchStatusLine(),
        )
        ensureAutoSwitchSchedulerIfNeeded()
        startModeWaitLoop()
    }

    fun startIfNeeded() {
        if (!PrefsStore.isBound()) return
        refreshRuntimeSnapshot()
        ensureAutoSwitchSchedulerIfNeeded()
        startModeWaitLoop()
    }

    fun forceStartRunningNow(reason: String = "设备端启动后台服务") {
        if (!PrefsStore.isBound()) return
        PrefsStore.saveLocalStatus("running")
        updateRuntime(localStatus = "running", lastMessage = "已请求启动：$reason")
        val alias = PrefsStore.getAlias()
        if (!alias.isNullOrBlank()) {
            LogCenter.op(alias, "启动运行：$reason")
        }
        startModeWaitLoop()
    }

    fun ensureTikTokGalleryPermissionIfNeeded() = Unit

    suspend fun syncOnline(retries: Int = 1) {
        val deviceId = PrefsStore.getDeviceId() ?: return
        val alias = PrefsStore.getAlias() ?: return
        runCatching {
            ApiService.syncStatus(deviceId, alias, PrefsStore.getLocalStatus())
        }.onFailure { err ->
            LogCenter.op(alias, "上线同步失败：${err.message}")
        }
    }

    suspend fun fetchConfig(
        logToUser: Boolean = false,
        manual: Boolean = false,
    ): Boolean {
        if (logToUser || manual) {
            PrefsStore.getAlias()?.let { LogCenter.op(it, "配置拉取已禁用（自治期走 bundle）") }
        }
        return _config.value.run_mode.isNotBlank()
    }

    fun startCheckLoop() {
        startModeWaitLoop()
    }

    fun reportWithRetry(message: String) {
        val deviceId = PrefsStore.getDeviceId() ?: return
        val alias = PrefsStore.getAlias() ?: return
        scope.launch {
            runCatching {
                ApiService.reportResult(deviceId, alias, message, _config.value.run_mode)
            }.onFailure { err ->
                LogCenter.op(alias, "结果上报失败：${err.message}")
            }
        }
    }

    fun onWorkerServiceChanged(isRunning: Boolean) {
        updateRuntime(serviceRunning = isRunning)
    }

    fun stopRunningState() {
        PrefsStore.saveLocalStatus("online")
        updateRuntime(localStatus = "online", lastMessage = "任务已停止")
    }

    fun setManualPaused(paused: Boolean) {
        if (paused) {
            PrefsStore.saveLocalStatus("paused")
            updateRuntime(localStatus = "paused", lastMessage = "已暂停")
        } else {
            PrefsStore.saveLocalStatus("online")
            updateRuntime(localStatus = "online", lastMessage = "已恢复")
            startIfNeeded()
        }
    }

    fun isManualPaused(): Boolean = PrefsStore.getLocalStatus() == "paused"

    fun requestMutualClickSwitch() {
        PrefsStore.setMutualClickRequested(true)
        PrefsStore.setMutualClickPending(true)
        PrefsStore.saveLocalStatus("running")
        updateRuntime(localStatus = "running", lastMessage = "互点请求已登记")
    }

    fun shouldSwitchToMutualClick(): Boolean =
        PrefsStore.isMutualClickRequested() && PrefsStore.isMutualClickPending()

    fun markMutualClickSwitched() {
        PrefsStore.clearMutualClickState()
    }

    fun requestUserGracefulHardStop() {
        PrefsStore.setHardStopRequested(true)
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(
            localStatus = "paused",
            hardStopRequested = true,
            lastMessage = "已请求硬停",
        )
    }

    fun stopWorkForServiceDestroy() {
        updateRuntime(serviceRunning = false, lastMessage = "后台服务重建中")
    }

    fun stopAllWork(requestHardStop: Boolean = true) {
        if (requestHardStop) {
            PrefsStore.setHardStopRequested(true)
        }
        if (::growthPullLoop.isInitialized) growthPullLoop.stop()
        if (::exclusiveOpRunner.isInitialized) exclusiveOpRunner.cancelAll()
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(
            localStatus = "paused",
            hardStopRequested = requestHardStop,
            lastMessage = "已停止全部任务",
            serviceRunning = WorkerServiceTracker.isRunning,
        )
    }

    fun stopExecutionOnly(keepListenerReason: String = "后台服务已停止") {
        updateRuntime(lastMessage = keepListenerReason)
    }

    fun refreshRuntimeSnapshot() {
        updateRuntime(
            serviceRunning = WorkerServiceTracker.isRunning,
            localStatus = PrefsStore.getLocalStatus(),
            currentServerUrl = ApiService.getBaseUrl(),
            configLoaded = _config.value.run_mode.isNotBlank(),
            effectiveRunMode = _config.value.run_mode,
            hardStopRequested = PrefsStore.isHardStopRequested(),
            lastMessage = autoSwitchStatusLine(),
        )
    }

    fun isInPullUsernameRestSleep(): Boolean =
        if (::growthPullLoop.isInitialized) growthPullLoop.isInRestSleep() else false

    // --- mode_wait / enter / exit ---

    private fun isModeWaitSoftTimeout(err: Throwable): Boolean {
        val msg = err.message.orEmpty()
        return err is SocketTimeoutException ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("HTTP 502") ||
            msg.contains("HTTP 504") ||
            msg.contains("Gateway Time-out", ignoreCase = true) ||
            msg.contains("Bad Gateway", ignoreCase = true)
    }

    private fun startModeWaitLoop() {
        if (!PrefsStore.isBound()) return
        if (modeWaitJob?.isActive == true) return
        modeWaitJob = scope.launch {
            var authErrorRounds = 0
            while (isActive) {
                val deviceId = PrefsStore.getDeviceId()
                val alias = PrefsStore.getAlias()
                if (deviceId.isNullOrBlank() || alias.isNullOrBlank()) {
                    delay(10_000)
                    continue
                }
                try {
                    val sinceId = PrefsStore.getLastModeEventId()
                    val resp = ApiService.modeWait(deviceId, alias, sinceId, timeoutSec = MODE_WAIT_TIMEOUT_SEC)
                    if (resp.code != 0) {
                        authErrorRounds++
                        if (
                            authErrorRounds >= 3 &&
                            BindingRecovery.looksLikeBindingAuthError(resp.code, resp.msg)
                        ) {
                            BindingRecovery.tryRecoverFromCloud(appContext)
                            authErrorRounds = 0
                        }
                        LogCenter.op(alias, "模式事件鉴权失败(${resp.code})：${resp.msg}")
                        delay((5_000L * authErrorRounds.coerceAtMost(6)))
                        continue
                    }
                    authErrorRounds = 0
                    val event = resp.event
                    noteCloudAutoSwitchEnabled(
                        resolveCloudAutoSwitchEnabledFromModeWait(event, resp.auto_switch_enabled),
                    )
                    if (event != null && event.event_id > 0) {
                        val ev = event
                        if (ev.event_id > PrefsStore.getLastModeEventId()) {
                            PrefsStore.saveLastModeEventId(ev.event_id)
                        }
                        scope.launch {
                            modeEventMutex.withLock {
                                handleDeviceModeEvent(alias, deviceId, ev)
                            }
                        }
                        continue
                    }
                    scope.launch {
                        modeEventMutex.withLock {
                            reconcileCloudAutoSwitchState(alias, deviceId, resp.auto_switch_enabled)
                        }
                    }
                    delay(MODE_WAIT_IDLE_DELAY_MS)
                } catch (err: Exception) {
                    val isPollTimeout = isModeWaitSoftTimeout(err)
                    if (!isPollTimeout) {
                        LogCenter.op(alias, "模式事件等待失败：${err.message ?: ""}")
                    }
                    delay(if (isPollTimeout) MODE_WAIT_IDLE_DELAY_MS else 5_000L)
                }
            }
        }
    }

    private suspend fun handleDeviceModeEvent(alias: String, deviceId: String, event: DeviceModeEvent) {
        val type = event.type.trim()
        val sessionId = event.session_id.trim()
        val eventId = event.event_id
        val bundleRevision = event.bundle_revision ?: 0
        when (type) {
            "auto_switch_enter" -> handleAutoSwitchEnter(alias, deviceId, eventId, sessionId, bundleRevision)
            "auto_switch_exit" -> handleAutoSwitchExit(alias, deviceId, eventId, sessionId)
            else -> LogCenter.op(alias, "收到未知模式事件：$type")
        }
    }

    private suspend fun handleAutoSwitchEnter(
        alias: String,
        deviceId: String,
        eventId: Int,
        sessionId: String,
        bundleRevision: Int,
    ) {
        noteCloudAutoSwitchEnabled(true)
        if (PrefsStore.isAutoSwitchEnterDone(eventId)) {
            if (isCloudAutoSwitchDisabled()) {
                performAutoSwitchExitShutdown(alias, deviceId, eventId = 0, exitSessionId = "")
            }
            return
        }
        if (PrefsStore.isAutoSwitchAutonomousMode() && autoSwitchScheduler.isRunning()) {
            LogCenter.op(alias, "【自动切号】资料重同步 enter session=$sessionId revision=$bundleRevision")
            handleAutoSwitchEnterResync(alias, deviceId, eventId, sessionId, bundleRevision)
            return
        }
        if (PrefsStore.isHardStopRequested()) {
            PrefsStore.setHardStopRequested(false)
        }
        PrefsStore.saveLocalStatus("running")
        updateRuntime(
            localStatus = "running",
            hardStopRequested = false,
            lastMessage = "自动切号自治启动",
        )
        if (!WorkerServiceTracker.isRunning) {
            runCatching { WorkerService.start(appContext) }
        }
        runCatching { syncOnline() }
        PrefsStore.saveAutoSwitchSessionContext(sessionId, bundleRevision)
        cancelAutoSwitchBundleRetry()
        val outcome = tryLoadAutoSwitchBundleForEnter(alias, deviceId, sessionId, bundleRevision)
        if (!outcome.loaded) {
            LogCenter.op(alias, "【自动切号】bundle 拉取失败：${outcome.msg}")
        }
        updateRuntime(
            lastMessage = if (outcome.loaded) {
                "自动切号自治：本地调度中"
            } else {
                "自动切号自治：等待 bundle"
            },
        )
        when {
            outcome.loaded ->
                finalizeAutoSwitchEnterAfterBundleLoaded(alias, deviceId, eventId, sessionId)
            outcome.cloudDisabled ->
                scheduleAutoSwitchBundleEnterRetry(
                    alias, deviceId, eventId, sessionId, bundleRevision,
                    outcome.msg.ifBlank { "该设备未开启自动切号" },
                )
            else ->
                scheduleAutoSwitchBundleEnterRetry(alias, deviceId, eventId, sessionId, bundleRevision, outcome.msg)
        }
    }

    private suspend fun handleAutoSwitchEnterResync(
        alias: String,
        deviceId: String,
        eventId: Int,
        sessionId: String,
        bundleRevision: Int,
    ) {
        noteCloudAutoSwitchEnabled(true)
        PrefsStore.saveAutoSwitchSessionContext(sessionId, bundleRevision)
        cancelAutoSwitchBundleRetry()
        val outcome = tryLoadAutoSwitchBundleForResync(alias, deviceId, sessionId, bundleRevision)
        when {
            outcome.loaded ->
                finalizeAutoSwitchEnterAfterBundleLoaded(alias, deviceId, eventId, sessionId, outcome.skippedUnchanged)
            outcome.cloudDisabled ->
                scheduleAutoSwitchBundleEnterRetry(
                    alias, deviceId, eventId, sessionId, bundleRevision,
                    outcome.msg.ifBlank { "该设备未开启自动切号" },
                    resync = true,
                )
            else ->
                scheduleAutoSwitchBundleEnterRetry(
                    alias, deviceId, eventId, sessionId, bundleRevision, outcome.msg, resync = true,
                )
        }
    }

    private suspend fun handleAutoSwitchExit(
        alias: String,
        deviceId: String,
        eventId: Int,
        exitSessionId: String,
    ) {
        noteCloudAutoSwitchEnabled(false)
        if (PrefsStore.isAutoSwitchExitDone(eventId)) {
            return
        }
        if (PrefsStore.getAutoSwitchMode() == "off") {
            PrefsStore.markAutoSwitchExitDone(eventId)
            PrefsStore.saveLastModeEventId(eventId)
            return
        }
        performAutoSwitchExitShutdown(alias, deviceId, eventId, exitSessionId)
    }

    private suspend fun performAutoSwitchExitShutdown(
        alias: String,
        deviceId: String,
        eventId: Int,
        exitSessionId: String,
    ) {
        if (
            PrefsStore.getAutoSwitchMode() == "off" &&
            !AutoSwitchLedger.hasPendingUpload() &&
            !PrefsStore.hasAutoSwitchPendingSummaryUpload()
        ) {
            if (eventId > 0) {
                PrefsStore.markAutoSwitchExitDone(eventId)
                runCatching {
                    ApiService.modeAck(deviceId, alias, eventId, "summary_uploaded", exitSessionId)
                }
                PrefsStore.saveLastModeEventId(eventId)
            }
            return
        }
        val enterSessionId = PrefsStore.getAutoSwitchSessionId().orEmpty()
        beginAutoSwitchExitShutdown(alias)
        if (eventId > 0) {
            LogCenter.op(
                alias,
                "【自动切号】收到关闭指令 enter_session=$enterSessionId exit_session=$exitSessionId",
            )
        } else {
            LogCenter.op(alias, "【自动切号】对账退出 enter_session=$enterSessionId")
        }
        awaitAutoSwitchExclusiveDrain(alias)
        val summaryUploaded = uploadAutoSwitchSessionSummary(alias, deviceId)
        if (summaryUploaded) {
            if (eventId > 0) {
                runCatching {
                    ApiService.modeAck(deviceId, alias, eventId, "exiting_started", exitSessionId)
                }.onFailure { err ->
                    LogCenter.op(alias, "自治模式 ACK(exiting_started) 失败：${err.message}")
                }
                runCatching {
                    ApiService.modeAck(deviceId, alias, eventId, "summary_uploaded", exitSessionId)
                }.onSuccess {
                    LogCenter.op(alias, "【自动切号】会话总结已上报")
                }.onFailure { err ->
                    LogCenter.op(alias, "自治模式 ACK(summary_uploaded) 失败：${err.message}")
                }
            }
            AutoSwitchLedger.clear()
            AutoSwitchLocalPlanStore.clear()
            AutoSwitchBundleStore.clear()
            cancelAutoSwitchBundleRetry()
            PrefsStore.clearAutoSwitchSessionContext()
            if (eventId > 0) {
                PrefsStore.markAutoSwitchExitDone(eventId)
                PrefsStore.saveLastModeEventId(eventId)
            }
            updateRuntime(lastMessage = "自动切号已关闭")
        } else {
            PrefsStore.setAutoSwitchMode("off")
            PrefsStore.setAutoSwitchPendingSummaryUpload(true)
            if (eventId > 0) {
                runCatching {
                    ApiService.modeAck(deviceId, alias, eventId, "exiting_started", exitSessionId)
                }.onFailure { err ->
                    LogCenter.op(alias, "自治模式 ACK(exiting_started) 失败：${err.message}")
                }
            }
            updateRuntime(lastMessage = "自动切号已关闭（会话总结待补传）")
        }
    }

    private fun beginAutoSwitchExitShutdown(alias: String) {
        cancelAutoSwitchBundleRetry()
        PrefsStore.setAutoSwitchMode("exiting")
        if (::growthPullLoop.isInitialized) growthPullLoop.stop()
        if (::exclusiveOpRunner.isInitialized) exclusiveOpRunner.cancelAll()
        if (::autoSwitchScheduler.isInitialized) autoSwitchScheduler.stop()
        PrefsStore.setAutoSwitchExclusivePreempt(true)
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(localStatus = "paused", lastMessage = "自动切号关闭中")
        LogCenter.op(alias, "【自动切号】关闭中：已暂停调度与涨粉，等待独占任务收尾")
    }

    private suspend fun awaitAutoSwitchExclusiveDrain(alias: String, maxMs: Long = AUTO_SWITCH_EXIT_DRAIN_MS) {
        val step = 500L
        var waited = 0L
        while (waited < maxMs) {
            val busy = ExecutionScope.isActive() ||
                (::autoSwitchScheduler.isInitialized && autoSwitchScheduler.isExclusiveWorkActive()) ||
                (::exclusiveOpRunner.isInitialized && exclusiveOpRunner.isAnyActive())
            if (!busy) return
            delay(step)
            waited += step
        }
        LogCenter.op(alias, "【自动切号】等待独占任务收尾超时（${maxMs / 1000}s）")
    }

    private suspend fun uploadAutoSwitchSessionSummary(alias: String, deviceId: String): Boolean {
        if (AutoSwitchLedger.getEntryCount() == 0) {
            AutoSwitchLedger.restoreFromPrefs()
        }
        val summary = AutoSwitchLedger.toSummaryRequest(alias, deviceId)
        var uploaded = false
        repeat(3) { attempt ->
            if (uploaded) return@repeat
            runCatching {
                val resp = ApiService.postAutoSwitchSessionSummary(summary)
                if (resp.code == 0) {
                    uploaded = true
                    LogCenter.op(alias, AutoSwitchLedger.buildOneLineSummary(alias))
                    val notes = resp.reconciliation?.get("notes")
                    if (notes is List<*>) {
                        notes.filterIsInstance<String>().forEach { note ->
                            if (note.isNotBlank()) LogCenter.op(alias, "【对账】$note")
                        }
                    }
                } else {
                    LogCenter.op(alias, "会话总结上报失败(${attempt + 1}/3)：${resp.msg}")
                }
            }.onFailure { err ->
                LogCenter.op(alias, "会话总结上报异常(${attempt + 1}/3)：${err.message}")
            }
            if (!uploaded && attempt < 2) delay(2_000)
        }
        return uploaded
    }

    private suspend fun finalizeAutoSwitchEnterAfterBundleLoaded(
        alias: String,
        deviceId: String,
        eventId: Int,
        sessionId: String,
        bundleUnchanged: Boolean = false,
    ) {
        noteCloudAutoSwitchEnabled(true)
        cancelAutoSwitchBundleRetry()
        AutoSwitchLedger.restoreFromPrefs()
        AutoSwitchLedger.startSession(sessionId, bundleRevision = PrefsStore.getAutoSwitchBundleRevision())
        PrefsStore.markAutoSwitchEnterDone(eventId)
        PrefsStore.saveLastModeEventId(eventId)
        ensureAutoSwitchSchedulerIfNeeded()
        updateRuntime(
            lastMessage = if (bundleUnchanged) {
                "自动切号自治：资料重同步（账号未变）"
            } else {
                "自动切号自治：本地调度中"
            },
        )
        if (bundleUnchanged) {
            LogCenter.op(alias, "【自动切号】资料重同步完成：账号一致，未改本地 bundle")
        }
        runCatching {
            ApiService.modeAck(deviceId, alias, eventId, "bundle_loaded", sessionId)
        }.onFailure { err ->
            LogCenter.op(alias, "自治模式 ACK(bundle_loaded) 失败：${err.message}")
        }
        delay(500)
        autoSwitchScheduler.requestImmediateTick()
    }

    private data class AutoSwitchBundleEnterOutcome(
        val loaded: Boolean,
        val msg: String = "",
        val cloudDisabled: Boolean = false,
        val skippedUnchanged: Boolean = false,
    )

    private suspend fun tryLoadAutoSwitchBundleForEnter(
        alias: String,
        deviceId: String,
        sessionId: String,
        bundleRevision: Int,
    ): AutoSwitchBundleEnterOutcome {
        val deviceIdClean = deviceId.trim()
        val aliasClean = alias.trim()
        val result = AutoSwitchBundleStore.fetchFromCloud(deviceIdClean, aliasClean, sessionId, bundleRevision)
        return mapBundleFetchToEnterOutcome(result)
    }

    private suspend fun tryLoadAutoSwitchBundleForResync(
        alias: String,
        deviceId: String,
        sessionId: String,
        bundleRevision: Int,
    ): AutoSwitchBundleEnterOutcome {
        val deviceIdClean = deviceId.trim()
        val aliasClean = alias.trim()
        val result = AutoSwitchBundleStore.fetchFromCloudForResync(
            deviceIdClean,
            aliasClean,
            sessionId,
            bundleRevision,
        )
        return mapBundleFetchToEnterOutcome(result)
    }

    private fun mapBundleFetchToEnterOutcome(result: AutoSwitchBundleFetchResult): AutoSwitchBundleEnterOutcome =
        when {
            result.ok && result.bundle != null ->
                AutoSwitchBundleEnterOutcome(loaded = true, skippedUnchanged = result.skippedUnchanged)
            isBundleFetchCloudDisabled(result.message) ->
                AutoSwitchBundleEnterOutcome(loaded = false, msg = result.message, cloudDisabled = true)
            else -> AutoSwitchBundleEnterOutcome(loaded = false, msg = result.message)
        }

    private fun scheduleAutoSwitchBundleEnterRetry(
        alias: String,
        deviceId: String,
        eventId: Int,
        sessionId: String,
        bundleRevision: Int,
        firstFailureMsg: String,
        resync: Boolean = false,
    ) {
        if (isAutoSwitchBundlePermanentFailure(firstFailureMsg)) {
            LogCenter.op(alias, "【自动切号】bundle 失败不可自动重试：$firstFailureMsg")
            return
        }
        cancelAutoSwitchBundleRetry()
        autoSwitchBundleRetryJob = scope.launch {
            var delayMs = AUTO_SWITCH_BUNDLE_RETRY_INITIAL_MS
            var attempt = 0
            while (isActive) {
                delay(delayMs)
                if (PrefsStore.getAutoSwitchMode() != "on") return@launch
                if (PrefsStore.isAutoSwitchEnterDone(eventId)) return@launch
                if (PrefsStore.getAutoSwitchSessionId().orEmpty() != sessionId) return@launch
                if (autoSwitchScheduler.isRunning() && AutoSwitchBundleStore.get() != null) {
                    return@launch
                }
                if (isCloudAutoSwitchDisabled()) {
                    performAutoSwitchExitShutdown(alias, deviceId, eventId = 0, exitSessionId = "")
                    return@launch
                }
                attempt++
                val outcome = if (resync) {
                    tryLoadAutoSwitchBundleForResync(alias, deviceId, sessionId, bundleRevision)
                } else {
                    tryLoadAutoSwitchBundleForEnter(alias, deviceId, sessionId, bundleRevision)
                }
                if (outcome.loaded) {
                    finalizeAutoSwitchEnterAfterBundleLoaded(
                        alias,
                        deviceId,
                        eventId,
                        sessionId,
                        outcome.skippedUnchanged,
                    )
                    return@launch
                }
                if (isAutoSwitchBundlePermanentFailure(outcome.msg)) {
                    LogCenter.op(alias, "【自动切号】bundle 重试停止：${outcome.msg}")
                    return@launch
                }
                LogCenter.op(
                    alias,
                    "【自动切号】bundle 重试第 ${attempt} 次失败：${outcome.msg}，${delayMs / 1000}s 后再试",
                )
                delayMs = (delayMs * 2).coerceAtMost(AUTO_SWITCH_BUNDLE_RETRY_MAX_MS)
            }
        }
    }

    private suspend fun reconcileCloudAutoSwitchState(
        alias: String,
        deviceId: String,
        autoSwitchEnabled: Boolean?,
    ) {
        if (autoSwitchEnabled != false) return
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PrefsStore.getAutoSwitchMode() == "exiting") return
        LogCenter.op(alias, "【自动切号】云端开关已关，本机同步退出（对账）")
        performAutoSwitchExitShutdown(alias, deviceId, eventId = 0, exitSessionId = "")
    }

    private fun cancelAutoSwitchBundleRetry() {
        autoSwitchBundleRetryJob?.cancel()
        autoSwitchBundleRetryJob = null
    }

    private fun isAutoSwitchBundlePermanentFailure(msg: String): Boolean {
        val m = msg.trim()
        if (m.isEmpty()) return false
        return m.contains("无备份档案") ||
            m.contains("缺少今日计划") ||
            m.contains("鉴权") ||
            m.contains("无权") ||
            m.contains("不存在")
    }

    private fun isBundleFetchCloudDisabled(msg: String): Boolean {
        val m = msg.trim()
        return m.contains("未开启") ||
            m.contains("未启用") ||
            m.contains("未打开") ||
            m.contains("not enabled", ignoreCase = true)
    }

    private fun isCloudAutoSwitchDisabled(): Boolean = cloudAutoSwitchEnabled == false

    private fun noteCloudAutoSwitchEnabled(enabled: Boolean?) {
        if (enabled != null) {
            cloudAutoSwitchEnabled = enabled
        }
    }

    private fun resolveCloudAutoSwitchEnabledFromModeWait(
        event: DeviceModeEvent?,
        autoSwitchEnabled: Boolean?,
    ): Boolean? = when (event?.type?.trim()) {
        "auto_switch_enter" -> true
        "auto_switch_exit" -> false
        else -> autoSwitchEnabled
    }

    private fun ensureAutoSwitchSchedulerIfNeeded() {
        if (!PrefsStore.isBound()) return
        if (!PrefsStore.isAutoSwitchAutonomousMode()) {
            autoSwitchScheduler.stop()
            return
        }
        if (AutoSwitchBundleStore.get() == null) return
        if (!autoSwitchScheduler.isRunning()) {
            autoSwitchScheduler.start()
        }
    }

    private fun autoSwitchStatusLine(): String {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return "非自治模式"
        val owner = AutoSwitchLocalPlanStore.getEnvironmentOwnerUsername() ?: "空"
        val block = autoSwitchScheduler.growthBaselineBlockReason()
        return if (block.isNullOrBlank()) {
            "自治：环境归属=@$owner，已对齐"
        } else {
            "自治：环境归属=@$owner；$block"
        }
    }

    private fun updateRuntime(
        serviceRunning: Boolean? = null,
        localStatus: String? = null,
        currentServerUrl: String? = null,
        configLoaded: Boolean? = null,
        effectiveRunMode: String? = null,
        effectiveTemplate: String? = null,
        lastMessage: String? = null,
        hardStopRequested: Boolean? = null,
        cloudAutoStart: Boolean? = null,
    ) {
        val old = _runtimeState.value
        _runtimeState.value = old.copy(
            serviceRunning = serviceRunning ?: old.serviceRunning,
            localStatus = localStatus ?: old.localStatus,
            currentServerUrl = currentServerUrl ?: old.currentServerUrl,
            configLoaded = configLoaded ?: old.configLoaded,
            effectiveRunMode = effectiveRunMode ?: old.effectiveRunMode,
            effectiveTemplate = effectiveTemplate ?: old.effectiveTemplate,
            lastMessage = lastMessage ?: old.lastMessage,
            hardStopRequested = hardStopRequested ?: old.hardStopRequested,
            cloudAutoStart = cloudAutoStart ?: old.cloudAutoStart,
        )
    }
}
