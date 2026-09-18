package com.autotraces.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentValues
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.autotraces.app.backup.BackupDirectoryReconciler
import com.autotraces.app.backup.DeviceBackupShell
import com.autotraces.app.backup.UnifiedBackupPaths
import com.autotraces.app.backup.model.BackupRecord
import com.autotraces.app.backup.network.BackupReporter
import com.autotraces.app.videoded.CompanionApi
import com.autotraces.app.videoded.VideoDedSettingsRegistrar

object AppCoordinator {
    private fun prettyTemplateName(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return ""
        val parts = text.split("::")
        if (parts.size == 3 && parts[0] == "shared") {
            val owner = parts[1].trim()
            val tpl = parts[2].trim()
            if (owner.isNotBlank() && tpl.isNotBlank()) return "$tpl（共享自 $owner）"
        }
        return text
    }

    private fun prettyRunMode(mode: String?): String {
        return when (mode?.trim()?.lowercase()) {
            ServerRunMode.GROWTH -> "涨粉"
            ServerRunMode.LIVE -> "主播"
            ServerRunMode.DIRECT -> "直客"
            ServerRunMode.MUTUAL -> "互点引流"
            null, "" -> "未设置"
            else -> mode!!.trim()
        }
    }

    private fun queueRunMode(): String =
        _config.value.run_mode.trim().lowercase().ifBlank { ServerRunMode.GROWTH }

    private fun configSummaryLine(runMode: String, templateName: String): String {
        val tpl = templateName.trim()
        return if (tpl.isNotBlank()) {
            "${prettyRunMode(runMode)} · 模板「$tpl」"
        } else {
            prettyRunMode(runMode)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: android.content.Context
    private val executor: TraceExecutor by lazy {
        RealTraceExecutor(
            appContextProvider = { appContext },
        )
    }
    private val started = AtomicBoolean(false)
    private const val AUTO_SWITCH_CUT_SWITCH_RETRY_DELAY_MS = 3_000L
    /** force-stop 后等待进程退出、文件句柄释放（毫秒）。 */
    private const val TIKTOK_FORCE_STOP_SETTLE_MS = 1_500L
    /** 切号期间每轮强停后等待进程退出的间隔（毫秒）。 */
    private const val CUT_SWITCH_FORCE_STOP_ROUND_SETTLE_MS = 900L
    /** 切号期间最多连续强停轮数（含 am force-stop + 补充 kill -9）。 */
    private const val CUT_SWITCH_FORCE_STOP_MAX_ROUNDS = 8

    private val _config = MutableStateFlow(AppConfig())
    val config = _config.asStateFlow()
    private val _runtimeState = MutableStateFlow(
        RuntimeState(
            currentServerUrl = "",
            localStatus = PrefsStore.getLocalStatus(),
        )
    )
    val runtimeState = _runtimeState.asStateFlow()

    private var startPollJob: Job? = null
    private var modeWaitJob: Job? = null
    private var autoSwitchBundleRetryJob: Job? = null
    private val modeEventMutex = Mutex()
    @Volatile
    private var lastCloudAutoSwitchEnabled: Boolean? = null
    private val autoSwitchLocalScheduler = AutoSwitchLocalScheduler(
        scope,
        executeOp = { alias, deviceId, op -> handleDeviceOp(alias, deviceId, op) },
        onPauseGrowth = { pauseGrowthForAutoSwitchExclusiveOp() },
        onForceStopTikTokForExclusive = { phase -> forceStopTikTokForExclusiveEnqueue(phase) },
        onExclusiveQueueDrained = { releaseAutoSwitchExclusivePreemptIfIdle("独占 OP 队列已空") },
        onTryResumeGrowth = { maybeStartGrowthInAutoSwitchIdle() },
        onRefreshWeeklyBundle = { refreshAutoSwitchWeeklyBundleFromCloud() },
        isPublishExecutionActive = { isPublishPostSwitchActive() },
        isMutualExecutionActive = { isMutualSwitchActive() },
        isLiveVisitExecutionActive = { isLiveVisitSwitchActive() },
        onStalePublishInflight = { slotKey ->
            if (!tryResumeStaleAutoSwitchPublish(slotKey)) {
                val alias = PrefsStore.getAlias()?.trim().orEmpty()
                if (alias.isNotBlank()) {
                    resetStalePublishInflightForRetry(slotKey, alias)
                }
            }
        },
        onPreemptForCutSwitch = { slotKey, reason ->
            preemptAutoSwitchForCutSwitch(slotKey, reason)
        },
        onSlotTransition = { prevSlotKey, newSlotKey ->
            handleAutoSwitchSlotTransition(prevSlotKey, newSlotKey)
        },
        resolveRestoreBackupArchive = { backupName, backupPathHint, username, aliasClean, devId ->
            UnifiedBackupPaths.resolveRestoreBackupArchive(
                appContext,
                backupName,
                backupPathHint,
                username,
                aliasClean,
                devId,
            )?.let { it.fileName to it.absolutePath }
        },
        verifyInsertionLogin = { ctx, kind ->
            val sk = ctx.slotKey.trim()
            when {
                PrefsStore.isAutoSwitchTikTokLoginHoldActive(sk.ifBlank { null }) -> {
                    LogCenter.op(
                        ctx.alias,
                        "【自动切号】跳过$kind：登录保障恢复中 slot=${sk.ifBlank { "-" }}",
                    )
                    false
                }
                kind == "publish" -> verifyPublishInsertionLoginBeforeEnqueue(
                    alias = ctx.alias,
                    deviceId = ctx.deviceId,
                    targetUsername = ctx.account.username.orEmpty(),
                    slotKey = ctx.slotKey,
                )
                else -> true
            }
        },
        onAccountPlanBlocked = { a, d, ex, source ->
            handleAccountPlanBlocked(a, d, ex, source)
        },
    )
    private var pullLoopJob: Job? = null
    private var forYouSwipeJob: Job? = null
    private var manualSwitchJob: Job? = null
    /** 「活跃下个账号」整段会话（切号 + For You/涨粉）。 */
    private var activateNextJob: Job? = null
    /**
     * 多账号会话（活跃下个 / 批量发作品 / 自动活跃）内部发起的切号/发作品/预滑：
     * 勿取消父会话 Job。
     */
    @Volatile
    private var activateNextOwnsSwitch: Boolean = false
    /** 批量发作品：按账号切号→发作品→发后活跃。 */
    private var batchPublishJob: Job? = null
    /** 自动活跃：对待活跃队列整队轮流切号并活跃。 */
    private var autoActiveJob: Job? = null
    /** 日常孵化：对非待活跃账号排队养号。 */
    private var dailyIncubateJob: Job? = null
    private val _manualSwitchBusy = MutableStateFlow(false)
    /** 手动下拉切号进行中：UI 应禁用任务按钮，直至切号完成。 */
    val manualSwitchBusy = _manualSwitchBusy.asStateFlow()
    /** 手动账号就绪态变化：UI 刷新按钮（切号/自动登录/登录状态检测通过后递增）。 */
    private val _manualAccountUiEpoch = MutableStateFlow(0)
    val manualAccountUiEpoch = _manualAccountUiEpoch.asStateFlow()
    /** 最近一次 [handleDeviceOp] 关键步骤是否成功（供手动切号判断还原/清理结果）。 */
    @Volatile
    private var lastDeviceOpOk: Boolean = false
    private val autoSwitchRecoverMutex = Mutex()
    private val autoSwitchBundleFetchMutex = Mutex()
    @Volatile
    private var lastAutoSwitchRecoverAtMs: Long = 0L
    @Volatile
    private var lastBundleNetworkFetchAtMs: Long = 0L
    @Volatile
    private var lastBundleNetworkFetchRevision: Int = -1
    @Volatile
    private var lastBundleNetworkFetchSessionId: String = ""
    private var deviceOpsJob: Job? = null
    private var mutualSwitchJob: Job? = null
    private var liveVisitSwitchJob: Job? = null
    private var publishPostSwitchJob: Job? = null
    private var batchStreamJob: Job? = null
    @Volatile
    private var batchStreamStopRequested: Boolean = false
    @Volatile
    private var batchStreamGrowthPhase: Boolean = false
    @Volatile
    private var autoLoginActive: Boolean = false

    @Volatile
    private var basicProfileEditRunning: Boolean = false

    /**
     * 手动按钮分发协程（改资料等同步跑在 enqueue 内的 OP）：新按钮抢占时 cancel，尽快让出 TikTok。
     */
    private var manualDispatchJob: Job? = null

    /**
     * 手动抢占窗口内禁止「任务结束后自动涨粉」，避免旧任务 completion 顶掉新任务。
     */
    @Volatile
    private var suppressManualAutoGrowthUntilElapsed: Long = 0L

    /** 本机手动区可点任务种类（用于「同功能忽略、异功能立即抢占」）。 */
    private enum class ManualTaskKind(val label: String) {
        GROWTH("涨粉"),
        FOR_YOU_SWIPE("For You预滑"),
        MUTUAL_CLICK("引流互点"),
        PUBLISH_POST("发作品"),
        EDIT_PROFILE("修改基本资料"),
        AUTO_LOGIN("自动登录"),
        SWITCH_ACCOUNT("切号"),
        ACTIVATE_NEXT("活跃下个账号"),
        BATCH_PUBLISH("批量发作品"),
        AUTO_ACTIVE("自动活跃"),
        DAILY_INCUBATE("日常孵化"),
        BACKUP("备份"),
    }

    /**
     * 切号完成后的衔接协程尚未跑完：禁止 drain 队列时抢先恢复涨粉，
     * 避免插队导致改资料 / 主播访问饿死、调度空转。
     */
    @Volatile
    private var postCutSwitchKickPending: Boolean = false

    /** 最近一次 [ensureAccountLoggedIn] 是否实际跑完自动登录链（含 Profile→Edit 终检）。 */
    private var lastEnsureLoginPerformedFreshAutoLogin: Boolean = false

    /**
     * 云端下发/切换到涨粉模式后，在涨粉拉取循环中执行**一次**登录状态保障（非每轮拉取前）。
     * 登录状态保障 = TikTok 界面 OCR 判定是否可继续；与云端 `is_login` 无关。
     * is_login=否 时仅自动登录（持续重试）；发作品/互点前同样先完成登录。
     * 仍保留：切号完成、互点/发作品 OCR 掉登录后续登；已取消：每轮涨粉拉取前、每用户名任务开始、
     * 修改基本资料外轮、云端设备指令前。
     */
    @Volatile
    private var growthModeLoginSafeguardPending: Boolean = false

    /** 涨粉登录状态保障：整段（拉起 + 前台校验 + OCR）外层重试次数。 */
    private val loginSafeguardOuterMaxAttempts = 3

    /** 涨粉登录状态保障：外层重试间隔（毫秒）。 */
    private val loginSafeguardRetryDelayMs = 3_000L

    /**
     * 登录状态保障全局单飞已迁至 [LoginSafeguardGate]（覆盖 OCR + 续接自动登录，含互点/发作品冷启）。
     */

    /** 验证码配置缺失时，自动登录最多重试次数（超过后本地暂停）。 */
    private val autoLoginUnrecoverableCodeFetchMaxAttempts = 3
    private var autoLoginUnrecoverableCodeFetchAttempts = 0

    @Volatile
    private var dailyReconcileInFlight: Boolean = false

    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")

    /** 切号/自动登录可能含邮箱验证码，enter/exit 前须等独占 OP 跑完（默认 10 分钟）。 */
    private const val AUTO_SWITCH_EXCLUSIVE_DRAIN_MS = 600_000L

    /** exit 关闭自治：涨粉已抢占后最多再等当前步骤收尾（秒级协作退出，不必等整轮 OCR 链）。 */
    private const val AUTO_SWITCH_EXIT_DRAIN_MS = 60_000L

    /** 同一次服务恢复内 recoverAutoSwitchAutonomousWork 最短间隔，避免 bundle 重载风暴。 */
    private const val AUTO_SWITCH_RECOVER_DEBOUNCE_MS = 8_000L

    /**
     * mode_wait 长轮询秒数。须低于常见 nginx proxy_read_timeout（默认 60s），
     * 否则网关会先返回 HTML 504，设备端收不到 JSON 空轮询结果。
     */
    private const val MODE_WAIT_TIMEOUT_SEC = 45

    /** enter 后 bundle 拉取失败：首次 30s 退避重试，倍增至最多 5min（仅可恢复错误）。 */
    private const val AUTO_SWITCH_BUNDLE_RETRY_INITIAL_MS = 30_000L
    private const val AUTO_SWITCH_BUNDLE_RETRY_MAX_MS = 300_000L

    /** 登录保障失败后：暂停涨粉/发作品/引流调度刷屏，并按此间隔周期重试登录（非放弃）。 */
    private const val AUTO_SWITCH_LOGIN_RECOVERY_INTERVAL_MS = 60_000L

    private var autoSwitchLoginRecoveryJob: Job? = null

    private fun isPublishPostSwitchActive(): Boolean = publishPostSwitchJob?.isActive == true

    /** PC 遥控发作品：本轮 session 与最终结果（供 simple/status 轮询汇总）。 */
    data class ManualPublishOutcome(
        val sessionId: String,
        val success: Boolean,
        val message: String,
        val targetCount: Int,
        val successCount: Int,
        val username: String,
        val finishedAtMs: Long = System.currentTimeMillis(),
    )

    @Volatile
    private var manualPublishSessionId: String? = null

    @Volatile
    private var lastManualPublishOutcome: ManualPublishOutcome? = null

    /** 发作品协程/独占阶段真正在跑（不含磁盘残留 pending）。 */
    fun isManualPublishPostActuallyRunning(): Boolean =
        isPublishPostSwitchActive() || PublishPostGrowthGate.isBlockingGrowth()

    fun isManualPublishPostActive(): Boolean =
        isManualPublishPostActuallyRunning() ||
            PrefsStore.isPublishPostPending() ||
            PrefsStore.isPublishPostRequested()

    /**
     * PC 再发作品时：若只有残留 pending、实际没在跑，清掉标记以免把新指令挡掉。
     * @return 是否清掉了残留
     */
    fun clearStaleManualPublishIfIdle(): Boolean {
        if (isManualPublishPostActuallyRunning()) return false
        if (!PrefsStore.isPublishPostPending() && !PrefsStore.isPublishPostRequested()) return false
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        PrefsStore.clearPublishPostState()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "发作品：已清除残留 pending（任务未在跑），允许接收新指令")
        }
        return true
    }

    fun currentManualPublishSessionId(): String? = manualPublishSessionId

    fun lastManualPublishOutcome(): ManualPublishOutcome? = lastManualPublishOutcome

    /** 开启新一轮 PC/手动发作品会话；返回 sessionId。 */
    fun beginManualPublishSession(sessionId: String = "pub-${System.currentTimeMillis()}") {
        manualPublishSessionId = sessionId
        lastManualPublishOutcome = null
    }

    private fun recordManualPublishOutcome(
        success: Boolean,
        message: String,
        targetCount: Int,
        successCount: Int,
    ) {
        val session = manualPublishSessionId ?: "pub-${System.currentTimeMillis()}"
        val user = ManualDeviceControl.selectedManualUsername()
            .ifBlank { PrefsStore.getLogContextUsername().orEmpty() }
        lastManualPublishOutcome = ManualPublishOutcome(
            sessionId = session,
            success = success,
            message = message,
            targetCount = targetCount.coerceAtLeast(0),
            successCount = successCount.coerceAtLeast(0),
            username = user,
        )
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val summary =
            if (success) {
                "发作品最终结果：成功（$successCount/$targetCount）$message"
            } else {
                "发作品最终结果：失败（已成功 $successCount/$targetCount）$message"
            }
        if (alias.isNotBlank()) {
            LogCenter.op(alias, summary)
        }
    }

    private fun isAutoSwitchPublishLocallyActive(): Boolean = isPublishPostSwitchActive()

    private fun isMutualSwitchActive(): Boolean = mutualSwitchJob?.isActive == true

    private fun isLiveVisitSwitchActive(): Boolean = liveVisitSwitchJob?.isActive == true

    private fun isBatchStreamActive(): Boolean = batchStreamJob?.isActive == true

    private fun isAutoLoginActive(): Boolean = autoLoginActive

    /** 发作品独占发布、互点切换、批量带流或切号后自动登录占用 TikTok 时，不启动常规涨粉拉取循环。 */
    private fun canStartPullLoopNow(): Boolean {
        if (batchStreamGrowthPhase) {
            return !isAutoLoginActive()
        }
        if (PrefsStore.isAutoSwitchAutonomousMode() && !autoSwitchLocalScheduler.shouldAllowGrowthBaseline()) {
            return false
        }
        return !isAutoLoginActive() &&
            !basicProfileEditRunning &&
            !PublishPostGrowthGate.isBlockingGrowth() &&
            !isMutualSwitchActive() &&
            !isLiveVisitSwitchActive() &&
            !isBatchStreamActive() &&
            !(PrefsStore.isAutoSwitchAutonomousMode() &&
                (isAutoSwitchPublishLocallyActive() ||
                    AutoSwitchLocalPlanStore.isPublishContextOccupying() ||
                    AutoSwitchLocalPlanStore.isLiveVisitContextOccupying()))
    }

    /** 自治切号/发作品入队前：抢占 TikTok 通道，暂停涨粉拉取并协作中止在跑任务。 */
    private fun pauseGrowthForAutoSwitchExclusiveOp() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PublishPostGrowthGate.shouldDeferExclusivePreempt()) {
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】发作品独占发布中，跳过独占抢占")
            }
            return
        }
        PrefsStore.setGrowthContinueFromPublishHome(false)
        PrefsStore.setAutoSwitchExclusivePreempt(true)
        PrefsStore.saveLocalStatus("paused")
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】独占操作抢占 TikTok：暂停涨粉，等待当前步骤协作退出")
            updateRuntime(localStatus = "paused", lastMessage = "切号/独占操作：暂停涨粉")
        }
        pullLoopJob?.cancel()
        pullLoopJob = null
        scope.launch {
            forceStopTikTokForExclusiveEnqueue("独占抢占")
        }
    }

    /**
     * 收到 auto_switch_exit 后**立即**暂停涨粉、清空队列并抢占 TikTok，再等待当前步骤协作退出。
     * 不 cancel pullLoopJob（避免打断改资料 finally 误恢复涨粉；当前步骤靠 exiting/独占标志协作退出）。
     */
    private fun beginAutoSwitchExitShutdown(alias: String) {
        cancelAutoSwitchBundleRetry()
        PrefsStore.setAutoSwitchMode("exiting")
        autoSwitchLocalScheduler.stop()
        pendingUsernames.clear()
        PrefsStore.setGrowthContinueFromPublishHome(false)
        PrefsStore.setAutoSwitchExclusivePreempt(true)
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(localStatus = "paused", lastMessage = "自动切号关闭中：暂停涨粉")
        scope.launch {
            forceStopTikTokForExclusiveEnqueue("自动切号关闭")
        }
        LogCenter.op(alias, "【自动切号】关闭中：已暂停涨粉、清空队列并抢占 TikTok，等待当前步骤收尾")
    }

    private fun noteCloudAutoSwitchEnabled(cloudEnabled: Boolean?) {
        if (cloudEnabled != null) {
            lastCloudAutoSwitchEnabled = cloudEnabled
        }
    }

    /** enter/exit 事件类型优先于 mode_wait 携带的开关字段（服务端长轮询可能曾缓存陈旧值）。 */
    private fun resolveCloudAutoSwitchEnabledFromModeWait(
        event: DeviceModeEvent?,
        reported: Boolean?,
    ): Boolean? {
        return when (event?.type?.trim()) {
            "auto_switch_enter" -> true
            "auto_switch_exit" -> false
            else -> reported
        }
    }

    private fun isCloudAutoSwitchDisabled(): Boolean = lastCloudAutoSwitchEnabled == false

    /** mode_wait 携带的云端开关对账：本地仍在自治但云端已关时，不等 exit 事件丢失也能退出。 */
    private suspend fun reconcileCloudAutoSwitchState(
        alias: String,
        deviceId: String,
        cloudEnabled: Boolean?,
    ) {
        // simple 本机自治：开关由 APP 控制，不对账云端 auto_switch_enabled。
        if (ManualDeviceControl.ENABLED) return
        noteCloudAutoSwitchEnabled(cloudEnabled)
        if (cloudEnabled != false) return
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PrefsStore.getAutoSwitchMode() == "exiting") return
        LogCenter.op(alias, "【自动切号】云端开关已关，本机同步退出（对账）")
        performAutoSwitchExitShutdown(alias, deviceId, eventId = 0, exitSessionId = "")
    }

    /**
     * 执行 auto_switch_exit 收尾（有 eventId 时上报 ACK；对账路径 eventId=0 仅本地退出）。
     */
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
                "【自动切号】收到关闭指令，等待涨粉/独占收尾 enter_session=$enterSessionId exit_event=$exitSessionId",
            )
        } else {
            LogCenter.op(
                alias,
                "【自动切号】对账退出，等待涨粉/独占收尾 enter_session=$enterSessionId",
            )
        }
        awaitAutoSwitchExclusiveDrain(alias, maxMs = AUTO_SWITCH_EXIT_DRAIN_MS)
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
                    LogCenter.op(alias, "【自动切号】收尾已上报云端")
                }.onFailure { err ->
                    LogCenter.op(alias, "自治模式 ACK(summary_uploaded) 失败：${err.message}")
                }
            }
            AutoSwitchLedger.clear()
            AutoSwitchAccountActivityTracker.reset()
            PrefsStore.clearAutoSwitchSessionContext()
            if (eventId > 0) {
                PrefsStore.markAutoSwitchExitDone(eventId)
                PrefsStore.saveLastModeEventId(eventId)
            }
            flushCloudStatusAfterAutonomousExit()
        } else {
            PrefsStore.setAutoSwitchMode("off")
            PrefsStore.setAutoSwitchPendingSummaryUpload(true)
            flushCloudStatusAfterAutonomousExit()
            if (eventId > 0) {
                runCatching {
                    ApiService.modeAck(deviceId, alias, eventId, "exiting_started", exitSessionId)
                }.onFailure { err ->
                    LogCenter.op(alias, "自治模式 ACK(exiting_started) 失败：${err.message}")
                }
            }
            LogCenter.op(alias, "【自动切号】会话总结上报未成功，已保留本地 ledger 待重试")
        }
        PrefsStore.setAutoSwitchExclusivePreempt(false)
        pullLoopJob?.cancel()
        pullLoopJob = null
        updateRuntime(lastMessage = "自动切号已关闭，已恢复常规轮询")
        if (deviceOpsJob?.isActive != true) {
            startDeviceOpsLoop()
        }
    }

    /** 独占 OP 队列已空且账号已对齐时，解除抢占并恢复涨粉。 */
    private fun releaseAutoSwitchExclusivePreemptIfIdle(reason: String = "独占操作结束") {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (!PrefsStore.isAutoSwitchExclusivePreempt()) return
        if (PublishPostGrowthGate.isBlockingGrowth()) return
        if (autoSwitchLocalScheduler.shouldBlockGrowthPull()) return
        PrefsStore.setAutoSwitchExclusivePreempt(false)
        if (manuallyPaused || shouldHonorUserHardStop()) return
        if (PrefsStore.getLocalStatus() != "running") {
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "自动切号自治：$reason")
        }
    }

    /** 账号已对齐且本地 OP 队列空闲时，恢复自治模式下的涨粉拉取（由切号完成/调度空档/显式恢复触发，非 ensureLoopsRunning 看门狗）。 */
    private fun maybeStartGrowthInAutoSwitchIdle(reason: String = "自治空档") {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PrefsStore.getAutoSwitchMode() == "exiting") return
        if (postCutSwitchKickPending && !reason.contains("切号完成") && !reason.contains("切号后")) {
            return
        }
        if (PublishPostGrowthGate.isBlockingGrowth()) return
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            if (alias.isNotBlank()) {
                val detail = PrefsStore.autoSwitchTikTokLoginHoldReason().orEmpty()
                val remain = PrefsStore.autoSwitchTikTokLoginHoldRemainingMinutes()
                LogCenter.op(
                    alias,
                    "【自动切号】登录保障恢复中（${if (detail.isNotEmpty()) "$detail，" else ""}暂停涨粉/发作品/引流调度）",
                )
            }
            return
        }
        val wasRunning = PrefsStore.getLocalStatus() == "running"
        releaseAutoSwitchExclusivePreemptIfIdle(reason)
        if (manuallyPaused || shouldHonorUserHardStop()) return
        // 轻量对齐/冷却跳过等路径可能留下 localStatus=paused 且无独占 OP，此处自愈恢复 running。
        if (PrefsStore.getLocalStatus() != "running" &&
            !PrefsStore.isAutoSwitchExclusivePreempt() &&
            autoSwitchLocalScheduler.shouldAllowGrowthBaseline()
        ) {
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "自动切号自治：$reason")
        }
        if (PrefsStore.getLocalStatus() != "running") return
        if (!autoSwitchLocalScheduler.shouldAllowGrowthBaseline()) {
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            if (alias.isNotBlank()) {
                autoSwitchLocalScheduler.growthBaselineBlockReason()?.let { block ->
                    LogCenter.op(alias, "【自动切号】$block，暂不恢复涨粉拉取")
                }
            }
            return
        }
        if (pullLoopJob?.isActive == true) return
        if (ExecutionScope.isActive()) {
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            if (alias.isNotBlank()) {
                LogCenter.op(
                    alias,
                    "【自动切号】$reason：执行标记未释放 depth=${ExecutionScope.depth()} " +
                        "阶段=${ProcessDiagnostics.currentPhase()}，暂不恢复涨粉拉取",
                )
            }
            return
        }
        if (!canStartPullLoopNow()) {
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】$reason：尚有独占任务占用，暂不恢复涨粉拉取")
            }
            return
        }
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】$reason，恢复涨粉拉取")
        }
        if (queueRunMode() == ServerRunMode.GROWTH && !wasRunning) {
            growthModeLoginSafeguardPending = !PrefsStore.peekGrowthContinueFromPublishHome()
        }
        startPullLoop()
    }

    /** 内存中是否真有独占 OP 在跑（与 prefs 里 crash 遗留的 preempt 标记区分）。 */
    private fun isAutoSwitchExclusiveWorkActuallyActive(): Boolean =
        basicProfileEditRunning ||
            autoLoginActive ||
            ExecutionScope.isActive() ||
            autoSwitchLocalScheduler.isExclusiveWorkActive() ||
            isPublishPostSwitchActive() ||
            isMutualSwitchActive() ||
            isLiveVisitSwitchActive() ||
            isBatchStreamActive() ||
            PublishPostGrowthGate.isBlockingGrowth()

    /** crash / 强杀后 prefs 可能仍留 preempt=是，但进程内已无 OP：清掉并按 plan 续跑。 */
    private suspend fun recoverStaleAutoSwitchExclusiveState(reason: String) {
        // 手动模式同样会因抢占取消遗留 preempt；无真实独占时一并清掉
        if (!PrefsStore.isAutoSwitchAutonomousMode()) {
            releaseStaleManualExclusivePreemptIfIdle(reason)
            return
        }
        autoSwitchLocalScheduler.recoverAllStaleInflightOnStart()
        if (isAutoSwitchExclusiveWorkActuallyActive()) return
        var cleared = false
        if (PrefsStore.isAutoSwitchExclusivePreempt()) {
            PrefsStore.setAutoSwitchExclusivePreempt(false)
            cleared = true
        }
        if (PrefsStore.isTransientPausedForDestructiveOp()) {
            PrefsStore.setTransientPausedForDestructiveOp(false)
            cleared = true
        }
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (cleared && alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】$reason：已清除 crash 遗留独占/暂停标记，将按本地计划文件续跑")
        }
        restartAutoSwitchLoginRecoveryIfNeeded(reason)
    }

    /** 云端 bundle 拉取失败时，用磁盘 plan + bundle 缓存恢复调度器。 */
    private suspend fun resumeAutoSwitchFromLocalPlan(reason: String): Boolean {
        if (PrefsStore.getAutoSwitchMode() != "on") return false
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return false
        val plan = AutoSwitchLocalPlanStore.get() ?: return false
        val cachedBundle = AutoSwitchBundleHolder.getBundle() ?: AutoSwitchBundleHolder.loadCachedBundle()
            ?: return false
        val revision = PrefsStore.getAutoSwitchBundleRevision().coerceAtLeast(plan.revision)
        AutoSwitchLedger.syncSessionContextFromPrefs()
        applyAutoSwitchBundle(alias, deviceId, cachedBundle, revision)
        LogCenter.op(
            alias,
            "【自动切号】$reason：已按本地计划恢复调度 planRev=${plan.revision} slots=${plan.slots.size}",
        )
        autoSwitchLocalScheduler.requestImmediateTick()
        maybeStartGrowthInAutoSwitchIdle(reason)
        return true
    }
    private data class AutoSwitchBundleNetworkResult(
        val loaded: Boolean,
        val bundle: AutoSwitchBundle? = null,
        val msg: String = "",
        val cloudDisabled: Boolean = false,
        val skippedNetwork: Boolean = false,
    )

    /**
     * 串行化 bundle 网络拉取；同 session+revision 在 debounce 窗口内不重复打服务端。
     */
    private suspend fun fetchAutoSwitchBundleFromCloud(
        alias: String,
        deviceId: String,
        reason: String,
        forceNetwork: Boolean = false,
    ): AutoSwitchBundleNetworkResult {
        return autoSwitchBundleFetchMutex.withLock {
            val sessionId = PrefsStore.getAutoSwitchSessionId().orEmpty()
            val revision = PrefsStore.getAutoSwitchBundleRevision()
            val now = SystemClock.elapsedRealtime()
            if (
                !forceNetwork &&
                revision == lastBundleNetworkFetchRevision &&
                sessionId == lastBundleNetworkFetchSessionId &&
                now - lastBundleNetworkFetchAtMs < AUTO_SWITCH_RECOVER_DEBOUNCE_MS
            ) {
                val cached = AutoSwitchBundleHolder.getBundle()
                if (cached != null) {
                    if (alias.isNotBlank()) {
                        LogCenter.op(
                            alias,
                            "【自动切号】$reason：跳过重复 bundle 网络请求（revision=$revision，" +
                                "${now - lastBundleNetworkFetchAtMs}ms 内已拉取）",
                        )
                    }
                    return@withLock AutoSwitchBundleNetworkResult(
                        loaded = true,
                        bundle = cached,
                        skippedNetwork = true,
                    )
                }
            }
            runCatching {
                val resp = ApiService.fetchAutoSwitchBundle(deviceId, alias, sessionId, revision)
                val msg = resp.msg.orEmpty()
                when {
                    resp.code == 0 && resp.bundle != null -> {
                        lastBundleNetworkFetchAtMs = SystemClock.elapsedRealtime()
                        lastBundleNetworkFetchRevision = revision
                        lastBundleNetworkFetchSessionId = sessionId
                        AutoSwitchBundleNetworkResult(loaded = true, bundle = resp.bundle)
                    }
                    msg.contains("未开启自动切号") ->
                        AutoSwitchBundleNetworkResult(loaded = false, msg = msg, cloudDisabled = true)
                    else ->
                        AutoSwitchBundleNetworkResult(loaded = false, msg = msg.ifBlank { "bundle 为空" })
                }
            }.getOrElse { err ->
                AutoSwitchBundleNetworkResult(loaded = false, msg = err.message.orEmpty())
            }
        }
    }

    private suspend fun recoverAutoSwitchAutonomousWork(reason: String) {
        if (ManualDeviceControl.ENABLED && !PrefsStore.isManualLocalAutoSwitchEnabled()) {
            // exiting 由 performAutoSwitchExitShutdown 收尾，避免与硬停关闭路径并发清状态。
            if (PrefsStore.getAutoSwitchMode() == "exiting") return
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                clearOrphanedAutoSwitchAfterManualDisable(reason)
            }
            return
        }
        if (ManualDeviceControl.ENABLED && PrefsStore.isManualLocalAutoSwitchEnabled()) {
            autoSwitchRecoverMutex.withLock {
                val now = SystemClock.elapsedRealtime()
                if (
                    PrefsStore.getAutoSwitchMode() == "on" &&
                    now - lastAutoSwitchRecoverAtMs < AUTO_SWITCH_RECOVER_DEBOUNCE_MS
                ) {
                    val skipAlias = PrefsStore.getAlias()?.trim().orEmpty()
                    if (skipAlias.isNotBlank()) {
                        LogCenter.op(
                            skipAlias,
                            "【自动切号】$reason：恢复去重跳过完整重载（${now - lastAutoSwitchRecoverAtMs}ms 内已恢复），轻量续跑",
                        )
                    }
                    resumeAutoSwitchGrowthLightweight(reason)
                    return@withLock
                }
                lastAutoSwitchRecoverAtMs = now
                recoverManualLocalAutoSwitchWork(reason)
            }
            return
        }
        if (PrefsStore.getAutoSwitchMode() != "on") return
        autoSwitchRecoverMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAutoSwitchRecoverAtMs < AUTO_SWITCH_RECOVER_DEBOUNCE_MS) {
                val skipAlias = PrefsStore.getAlias()?.trim().orEmpty()
                if (skipAlias.isNotBlank()) {
                    LogCenter.op(
                        skipAlias,
                        "【自动切号】$reason：恢复去重跳过完整 bundle 重载（${now - lastAutoSwitchRecoverAtMs}ms 内已恢复），轻量续跑",
                    )
                }
                resumeAutoSwitchGrowthLightweight(reason)
                return@withLock
            }
            lastAutoSwitchRecoverAtMs = now
            recoverAutoSwitchAutonomousWorkInner(reason)
        }
    }

    /** 手动模式未开本机自治时：清掉历史遗留 mode=on，避免硬停无效且不停自愈。 */
    private suspend fun clearOrphanedAutoSwitchAfterManualDisable(reason: String) {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】$reason：本机未开启自动切号，清理遗留自治状态")
        }
        cancelAutoSwitchBundleRetry()
        autoSwitchLocalScheduler.stop(clearState = true)
        AutoSwitchLedger.clear()
        AutoSwitchAccountActivityTracker.reset()
        PrefsStore.clearAutoSwitchSessionContext()
        PrefsStore.setAutoSwitchExclusivePreempt(false)
        PrefsStore.setManualLocalAutoSwitchEnabled(false)
        PrefsStore.setAutoSwitchPendingSummaryUpload(false)
        updateRuntime(lastMessage = "自动切号已关闭（本机开关）")
    }

    /** simple 本机自治恢复：只用本地计划/缓存，不拉云端周计划。 */
    private suspend fun recoverManualLocalAutoSwitchWork(reason: String) {
        if (!PrefsStore.isManualLocalAutoSwitchEnabled()) return
        if (PrefsStore.getAutoSwitchMode() == "exiting") return
        if (shouldHonorUserHardStop()) {
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】$reason：硬停中，跳过本机自治恢复")
            }
            return
        }
        manuallyPaused = false
        recoverStaleAutoSwitchExclusiveState(reason)
        PrefsStore.saveLocalStatus("running")
        updateRuntime(localStatus = "running", lastMessage = "本机自动切号：$reason")
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return
        // 硬停暂停后 mode 可能仍为 on；若曾被清成 off，按计划文件恢复会话再续跑。
        ensureManualLocalAutoSwitchModeFromPlan(alias)
        if (autoSwitchLocalScheduler.isRunning()) {
            autoSwitchLocalScheduler.stop(clearState = false)
        }
        var resumed = resumeAutoSwitchFromLocalPlan(reason)
        if (!resumed) {
            resumed = rebuildAndStartManualLocalAutoSwitch(
                alias = alias,
                deviceId = deviceId,
                slotMinutes = PrefsStore.getManualAutoSwitchSlotMinutes(),
                reason = "$reason-重建计划",
            )
        }
        if (resumed && !shouldHonorUserHardStop() && PrefsStore.isManualLocalAutoSwitchEnabled()) {
            maybeStartGrowthInAutoSwitchIdle(reason)
        }
    }

    /**
     * APP 开关仍开、但硬停/异常把 mode 清掉时：用计划文件里的 session/revision 把 mode 拉回 on，
     * 以便 [resumeAutoSwitchFromLocalPlan] 能按持久化计划续跑。
     */
    private fun ensureManualLocalAutoSwitchModeFromPlan(alias: String) {
        if (!PrefsStore.isManualLocalAutoSwitchEnabled()) return
        if (PrefsStore.getAutoSwitchMode() != "off") return
        val plan = AutoSwitchLocalPlanStore.get() ?: return
        val sessionId = plan.session_id.trim().ifBlank {
            PrefsStore.getAutoSwitchSessionId().orEmpty().trim()
        }
        val revision = plan.revision.coerceAtLeast(1)
            .coerceAtLeast(PrefsStore.getAutoSwitchBundleRevision())
        if (sessionId.isBlank()) return
        if (PrefsStore.getAutoSwitchSessionId().isNullOrBlank()) {
            PrefsStore.saveAutoSwitchSessionContext(sessionId, revision)
        } else {
            PrefsStore.setAutoSwitchMode("on")
            if (PrefsStore.getAutoSwitchBundleRevision() < revision) {
                PrefsStore.saveAutoSwitchSessionContext(sessionId, revision)
            }
        }
        if (alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】已按计划文件恢复会话 mode=on session=$sessionId revision=$revision",
            )
        }
    }

    /** 服务重建/恢复去重时：不重拉 bundle，仅按本地 plan 续跑调度与涨粉。 */
    private suspend fun resumeAutoSwitchGrowthLightweight(reason: String) {
        if (PrefsStore.getAutoSwitchMode() != "on") return
        if (PrefsStore.isHardStopRequested()) return
        manuallyPaused = false
        if (
            PrefsStore.getLocalStatus() != "running" &&
            !PrefsStore.isAutoSwitchExclusivePreempt() &&
            autoSwitchLocalScheduler.shouldAllowGrowthBaseline()
        ) {
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "自动切号自治：$reason")
        }
        if (!autoSwitchLocalScheduler.isRunning()) {
            resumeAutoSwitchFromLocalPlan("$reason-轻量本地")
        } else {
            autoSwitchLocalScheduler.recoverAllStaleInflightOnStart()
            autoSwitchLocalScheduler.requestImmediateTick()
        }
        maybeStartGrowthInAutoSwitchIdle("$reason-轻量续跑")
    }

    private suspend fun recoverAutoSwitchAutonomousWorkInner(reason: String) {
        manuallyPaused = false
        recoverStaleAutoSwitchExclusiveState(reason)
        if (!PrefsStore.isHardStopRequested()) {
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "自动切号自治：$reason")
        }
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return

        // 服务 onDestroy 可能留下 tickJob 空转；恢复时强制重载 bundle 并重启调度器。
        if (autoSwitchLocalScheduler.isRunning()) {
            autoSwitchLocalScheduler.stop(clearState = false)
        }

        var resumed = false
        val fetchOutcome = fetchAutoSwitchBundleFromCloud(alias, deviceId, reason)
        when {
            fetchOutcome.cloudDisabled -> {
                noteCloudAutoSwitchEnabled(false)
                LogCenter.op(alias, "【自动切号】云端已关闭自治，停止本地调度")
                performAutoSwitchExitShutdown(alias, deviceId, eventId = 0, exitSessionId = "")
                return
            }
            fetchOutcome.loaded && fetchOutcome.bundle != null -> {
                val revision = PrefsStore.getAutoSwitchBundleRevision()
                noteCloudAutoSwitchEnabled(true)
                AutoSwitchLedger.syncSessionContextFromPrefs()
                applyAutoSwitchBundle(alias, deviceId, fetchOutcome.bundle, revision)
                scheduleAutoSwitchDailyReconcileTargetIfNeeded(alias)
                if (!fetchOutcome.skippedNetwork) {
                    LogCenter.op(alias, "【自动切号】已恢复本地调度（$reason）")
                }
                resumed = true
            }
            alias.isNotBlank() ->
                LogCenter.op(
                    alias,
                    "【自动切号】$reason：bundle 拉取失败 ${fetchOutcome.msg}，尝试本地缓存",
                )
        }
        if (!resumed) {
            if (isCloudAutoSwitchDisabled()) {
                performAutoSwitchExitShutdown(alias, deviceId, eventId = 0, exitSessionId = "")
                return
            }
            resumed = resumeAutoSwitchFromLocalPlan(reason)
        }
        if (!resumed && alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】$reason：恢复失败（无 bundle / 无本地 plan）")
            return
        }

        logAutoSwitchRecoverySnapshot(alias, reason)
        ProcessDiagnostics.emitHeartbeat("恢复:$reason")
        autoSwitchLocalScheduler.requestImmediateTick(recoveryTick = true)
        maybeStartGrowthInAutoSwitchIdle(reason)
    }

    private fun logAutoSwitchRecoverySnapshot(alias: String, reason: String) {
        val plan = AutoSwitchLocalPlanStore.get() ?: run {
            LogCenter.op(alias, "【自动切号】$reason：plan 文件为空")
            return
        }
        val scheduleDate = beijingDateString()
        val minute = beijingMinuteOfDayForBundle()
        val slot = AutoSwitchLocalPlanStore.resolveSlotForMinute(plan, scheduleDate, minute)
        val slotKey = slot?.slot_key ?: "-"
        val pendingSlot = PrefsStore.getAutoSwitchLastSlotKey()?.trim().orEmpty().ifBlank { "-" }
        val pendingPlanSlot = pendingSlot.takeIf { it != "-" }?.let { AutoSwitchLocalPlanStore.slot(it) }
        val pendingCutDone = pendingPlanSlot?.let { AutoSwitchLocalPlanStore.isCutSwitchDone(it.slot_key) }
            ?: slot?.let { AutoSwitchLocalPlanStore.isCutSwitchDone(it.slot_key) }
            ?: false
        val pendingStep = pendingPlanSlot?.cut_switch?.nextPendingStep()?.let { step ->
            when (step) {
                CutSwitchStepKind.BACKUP -> "备份"
                CutSwitchStepKind.CLEAR -> "清理"
                CutSwitchStepKind.RESTORE_OR_LOGIN -> "还原/登录"
            }
        } ?: "-"
        val progressSlotKey = pendingPlanSlot?.slot_key ?: slotKey
        val progressLine = AutoSwitchLocalPlanStore.formatCutSwitchProgressLine(progressSlotKey)
        LogCenter.op(
            alias,
            "【自动切号】$reason：续跑诊断 北京=$scheduleDate ${minute / 60}:${"%02d".format(minute % 60)} " +
                "当前槽=$slotKey 切号完成=$pendingCutDone 待执行=$pendingStep 上次槽=$pendingSlot " +
                "调度器=${if (autoSwitchLocalScheduler.isRunning()) "运行" else "停"} " +
                "bundle=${if (autoSwitchLocalScheduler.hasBundleLoaded()) "有" else "无"}" +
                (progressLine?.let { " | $it" } ?: ""),
        )
    }

    /** 本机自动切号自治入队的 OP（id 形如 local-as-backup-…），步骤细节只写 operation_logs，不打实时日志。 */
    private fun isLocalAutoSwitchOpId(opId: String): Boolean = opId.startsWith("local-as-")

    private fun isAutoSwitchCutSwitchRetryUntilSuccess(payload: DeviceOpPayload?, suppressUiLog: Boolean): Boolean =
        suppressUiLog && PrefsStore.isAutoSwitchAutonomousMode() && (
            !payload?.cut_switch_after_backup.isNullOrBlank() ||
                (payload?.skip_pm_clear == true && payload.source?.trim() == "auto_switch")
            )

    private suspend fun delayAutoSwitchCutSwitchRetry(alias: String, attempt: Int, reason: String = "") {
        val detail = reason.trim()
        if (detail.isNotEmpty()) {
            LogCenter.op(alias, "【切号】失败：${cutSwitchUserLogMessage(detail)}")
        } else {
            LogCenter.op(alias, "【切号】步骤失败（原因未记录）")
        }
        LogCenter.op(
            alias,
            "【切号】${AUTO_SWITCH_CUT_SWITCH_RETRY_DELAY_MS / 1000}s 后第 ${attempt + 1} 轮重试…",
        )
        delayUnlessHardStop(AUTO_SWITCH_CUT_SWITCH_RETRY_DELAY_MS)
    }

    private fun isAutoSwitchCutSwitchRestore(payload: DeviceOpPayload?, skipPmClear: Boolean): Boolean =
        skipPmClear && payload?.source?.trim() == "auto_switch"

    private fun isCutSwitchMissingBackupFailure(reason: String): Boolean {
        val r = reason.trim()
        return r.contains("备份文件不存在或不可读") ||
            r.contains("本机无 @") ||
            isCutSwitchCorruptBackupFailure(r)
    }

    private fun isCutSwitchCorruptBackupFailure(reason: String): Boolean {
        val r = reason.trim()
        return r.contains("备份包损坏") ||
            r.contains("备份包已损坏") ||
            r.contains("gzip/tar 完整性校验失败")
    }

    private fun cutSwitchMissingBackupUserMessage(username: String): String {
        val user = username.trim().removePrefix("@")
        return if (user.isNotBlank()) {
            "目标账号 @$user 备份包本机不可用，改走自动登录"
        } else {
            "目标账号备份包本机不可用，改走自动登录"
        }
    }

    private fun cutSwitchCorruptBackupUserMessage(username: String): String {
        val user = username.trim().removePrefix("@")
        return if (user.isNotBlank()) {
            "目标账号 @$user 备份包已损坏并删除，改走自动登录"
        } else {
            "目标账号备份包已损坏并删除，改走自动登录"
        }
    }

    /** 实时日志用：去掉完整路径、备份包名、内网地址等敏感信息。 */
    private fun cutSwitchUserLogMessage(reason: String): String {
        val r = reason.trim()
        if (r.isEmpty()) return "切号步骤失败"
        if (isCutSwitchCorruptBackupFailure(r)) {
            val user = Regex("@([\\w.]+)").find(r)?.groupValues?.getOrNull(1)
            return cutSwitchCorruptBackupUserMessage(user.orEmpty())
        }
        if (isCutSwitchMissingBackupFailure(r)) {
            val user = Regex("本机无 @([\\w.]+)").find(r)?.groupValues?.getOrNull(1)
                ?: Regex("@([\\w.]+)").find(r)?.groupValues?.getOrNull(1)
            return cutSwitchMissingBackupUserMessage(user.orEmpty())
        }
        return r
            .replace(Regex("；完整路径：[^；]+"), "")
            .replace(Regex("；备份目录：[^；]+"), "")
            .replace(Regex("（期望包名：[^）]+）"), "")
            .replace(Regex("/storage/emulated/[^；\\s]+"), "[路径已省略]")
            .replace(Regex("/data/[^；\\s]+"), "[路径已省略]")
            .replace(Regex("http[s]?://[^；\\s]+"), "[地址已省略]")
            .replace(Regex("[\\w]+_[\\w]+_dev_[a-f0-9]{32}_\\d{8}_\\d{6}\\.tar\\.gz", RegexOption.IGNORE_CASE), "[备份包已省略]")
            .replace(Regex("；{2,}"), "；")
            .trim('；', ' ')
            .ifBlank { "切号步骤失败" }
    }

    /** 备份包缺失/损坏、username 无效等错误重试无意义，应立即中止。 */
    private fun isCutSwitchFailureRetryable(reason: String): Boolean {
        if (isCutSwitchMissingBackupFailure(reason)) return false
        if (isCutSwitchCorruptBackupFailure(reason)) return false
        val r = reason.trim()
        if (r.isEmpty()) return true
        val nonRetryableMarkers = listOf(
            "缺少有效 username",
            "无法生成备份包名",
            "需在管理台重新备份或通过 PC 传输导入",
            "备份目录内无 .tar.gz",
            "数据目录",
            "数据过小",
            "61440KB",
            "跳过打包备份",
        )
        return nonRetryableMarkers.none { r.contains(it) }
    }

    /** root 下校验备份包 gzip/tar 完整性；损坏返回 false。 */
    private fun probeBackupArchiveIntegrity(
        runSu: (String) -> Boolean,
        absolutePath: String,
    ): Boolean {
        val path = absolutePath.trim()
        if (path.isEmpty()) return false
        val q = DeviceBackupShell.shellQuote(path)
        return runSu(DeviceBackupShell.backupArchiveIntegrityTestCommand(q))
    }

    /**
     * 删除损坏备份包；若同账号还有其它包则返回 true 让上层继续解析，
     * 否则走缺备份兜底（不还原，改自动登录）并返回 false。
     * @param onFailure 由 [handleDeviceOp] 内局部 `recordCutSwitchFailure` 传入，用于写入切号失败原因。
     * @return null 表示已走兜底并应 `return@handleDeviceOp`；true 继续重试解析；false 记录失败后 break。
     */
    private suspend fun discardCorruptRestoreBackupAndMaybeFallback(
        alias: String,
        deviceId: String,
        opId: String,
        payload: DeviceOpPayload?,
        skipPmClear: Boolean,
        restoreUsername: String,
        backupName: String,
        backupPath: String,
        cloudBackupName: String,
        runSu: (String) -> Boolean,
        phase: String,
        onFailure: (String) -> Unit,
    ): Boolean? {
        val q = DeviceBackupShell.shellQuote(backupPath)
        runSu("rm -f $q")
        runCatching { File(backupPath).delete() }
        val detail =
            "备份包损坏已删除（gzip/tar 完整性校验失败）：$backupName；路径：$backupPath"
        val userMsg = cutSwitchCorruptBackupUserMessage(restoreUsername)
        LogCenter.op(alias, "【切号】$userMsg（$backupName）")
        val stillHasSibling = restoreUsername.isNotBlank() &&
            UnifiedBackupPaths.listUsernameBackupArchives(
                appContext,
                restoreUsername,
                alias,
                deviceId,
            ).any { it.length() >= UnifiedBackupPaths.MIN_VALID_BACKUP_FILE_BYTES }
        if (stillHasSibling) {
            onFailure(detail)
            return true
        }
        if (isAutoSwitchCutSwitchRestore(payload, skipPmClear) && restoreUsername.isNotBlank()) {
            handleCutSwitchMissingBackupFallback(
                alias = alias,
                deviceId = deviceId,
                opId = opId,
                payload = payload,
                restoreUsername = restoreUsername,
                cloudBackupName = cloudBackupName.ifBlank { backupName },
                phase = phase,
                internalDetail = detail,
                userMessageOverride = userMsg,
            )
            return null
        }
        onFailure(detail)
        return false
    }

    /** @return false 表示不可重试，调用方应 break 重试循环 */
    private suspend fun delayAutoSwitchCutSwitchRetryIfRetryable(
        alias: String,
        attempt: Int,
        reason: String,
    ): Boolean {
        if (!isCutSwitchFailureRetryable(reason)) {
            LogCenter.op(
                alias,
                "【切号】${cutSwitchUserLogMessage(reason)}（不可重试，停止）",
            )
            return false
        }
        delayAutoSwitchCutSwitchRetry(alias, attempt, reason)
        return true
    }

    /** 本机 TikTok 备份包目录（与 [UnifiedBackupPaths] 一致）。 */
    private fun cutSwitchBackupDirPath(): String =
        UnifiedBackupPaths.backupsDir(appContext).absolutePath

    /** 还原失败时：期望路径、目录、本机同账号可用备份。 */
    private fun describeMissingRestoreBackup(
        backupName: String,
        backupPath: String,
        username: String,
        alias: String,
        deviceId: String,
    ): String {
        val dir = cutSwitchBackupDirPath()
        val existsLocally = File(backupPath).exists()
        val siblings = if (username.isNotBlank()) {
            UnifiedBackupPaths.listUsernameBackupArchives(appContext, username, alias, deviceId)
                .map { it.name }
        } else {
            UnifiedBackupPaths.ensureBackupsDir(appContext).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".tar.gz", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(8)
                ?.map { it.name }
                .orEmpty()
        }
        return buildString {
            append("备份文件不存在或不可读")
            append("（期望包名：$backupName")
            if (!existsLocally) append("，本机检测不存在")
            append("）")
            append("；完整路径：$backupPath")
            append("；备份目录：$dir")
            if (siblings.isNotEmpty()) {
                append("；本机同账号可用备份：${siblings.joinToString("、")}")
            } else if (username.isNotBlank()) {
                append("；本机无 @$username 的备份包（需在管理台重新备份或通过 PC 传输导入）")
            } else {
                append("；备份目录内无 .tar.gz 文件")
            }
        }
    }

    private fun scheduleBackupDirectoryReconcile(
        alias: String,
        deviceId: String,
        accounts: List<AutoSwitchAccount>,
        extraKeepAbsolutePath: String? = null,
        logPrefix: String = "【备份目录整理】",
        deleteUnlistedUsernameArchives: Boolean = true,
    ) {
        if (alias.isBlank() || deviceId.isBlank()) return
        scope.launch {
            runBackupDirectoryReconcile(
                alias = alias,
                deviceId = deviceId,
                accounts = accounts,
                extraKeepAbsolutePath = extraKeepAbsolutePath,
                logPrefix = logPrefix,
                deleteUnlistedUsernameArchives = deleteUnlistedUsernameArchives,
            )
        }
    }

    /**
     * 备份目录整理花名册：必须覆盖本机「还可能还原」的所有号。
     * 绝不能只塞刚备份的那一个号——否则其它号合法包会被当成无关备份删掉（S6 0719 事故）。
     */
    private fun buildAccountsForBackupDirectoryReconcile(
        reportUsername: String,
        backupName: String,
        backupPath: String,
    ): List<AutoSwitchAccount> {
        val byUser = linkedMapOf<String, AutoSwitchAccount>()
        fun put(acc: AutoSwitchAccount) {
            val u = acc.username?.trim()?.removePrefix("@").orEmpty()
            if (u.isBlank()) return
            val key = u.lowercase(java.util.Locale.US)
            val existing = byUser[key]
            if (existing == null) {
                byUser[key] = acc.copy(username = u)
                return
            }
            byUser[key] = existing.copy(
                backup_name = acc.backup_name?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
                    ?: existing.backup_name,
                backup_path = acc.backup_path?.trim()?.takeIf { it.isNotEmpty() }
                    ?: existing.backup_path,
                row_index = acc.row_index ?: existing.row_index,
                account_row_index = acc.account_row_index ?: existing.account_row_index,
            )
        }

        AutoSwitchBundleHolder.getBundle()?.accounts.orEmpty().forEach(::put)
        ManualDeviceControl.getCachedAccounts().forEach { opt ->
            put(
                AutoSwitchAccount(
                    username = opt.username,
                    backup_name = opt.backupName.trim().takeIf { it.isNotEmpty() },
                    backup_path = opt.backupPath.trim().takeIf { it.isNotEmpty() },
                    row_index = opt.rowIndex,
                    account_row_index = opt.rowIndex,
                    email = opt.email.trim().takeIf { it.isNotEmpty() },
                    password = opt.password.trim().takeIf { it.isNotEmpty() },
                    email_password = opt.emailPassword.trim().takeIf { it.isNotEmpty() },
                    api = opt.api.trim().takeIf { it.isNotEmpty() },
                    is_login = opt.cloudIsLogin,
                    basic_profile_modified = opt.basicProfileModified,
                    mail_module_code_available = opt.mailModuleCodeAvailable,
                    switch_highlight = opt.switchHighlight,
                ),
            )
        }
        // 切号中途：选中目标 / 本机当前 / 日志上下文都必须护住
        listOf(
            ManualDeviceControl.selectedManualUsername(),
            ManualDeviceControl.activeManualUsername(),
            PrefsStore.getLogContextUsername().orEmpty().trim().removePrefix("@"),
        ).forEach { u ->
            if (u.isNotBlank()) put(AutoSwitchAccount(username = u))
        }

        val report = reportUsername.trim().removePrefix("@")
        if (report.isNotBlank()) {
            put(
                AutoSwitchAccount(
                    username = report,
                    backup_name = backupName.trim().takeIf { it.isNotEmpty() },
                    backup_path = backupPath.trim().takeIf { it.isNotEmpty() },
                ),
            )
        }
        return byUser.values.toList()
    }

    private suspend fun runBackupDirectoryReconcile(
        alias: String,
        deviceId: String,
        accounts: List<AutoSwitchAccount>,
        extraKeepAbsolutePath: String? = null,
        logPrefix: String = "【备份目录整理】",
        deleteUnlistedUsernameArchives: Boolean = true,
    ) {
        val policies = BackupDirectoryReconciler.policiesFromAccounts(accounts)
        if (policies.isEmpty()) return
        // 花名册只有 1 个号时，默认禁止删「未列入」包，防止把其它本机号备份当垃圾清掉
        val safeDeleteUnlisted = deleteUnlistedUsernameArchives && policies.size >= 2
        if (deleteUnlistedUsernameArchives && !safeDeleteUnlisted) {
            LogCenter.op(
                alias,
                "$logPrefix 花名册仅 ${policies.size} 个账号，跳过删除未列入花名册的备份包（防误删）",
            )
        }
        val extraKeep = extraKeepAbsolutePath?.trim()?.takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: emptySet()
        val result = BackupDirectoryReconciler.reconcile(
            context = appContext,
            alias = alias,
            deviceId = deviceId,
            policies = policies,
            extraKeepAbsolutePaths = extraKeep,
            deleteUnlistedUsernameArchives = safeDeleteUnlisted,
            deleteFile = { path ->
                val q = DeviceBackupShell.shellQuote(path)
                runExecutorSu("rm -f $q")
            },
        )
        if (result.deleted.isNotEmpty()) {
            LogCenter.op(
                alias,
                "$logPrefix 已删除 ${result.deleted.size} 个重复/无关备份：" +
                    result.deleted.joinToString("、") { File(it).name },
            )
        }
        if (result.failed.isNotEmpty()) {
            LogCenter.op(
                alias,
                "$logPrefix ${result.failed.size} 个备份未能删除：" +
                    result.failed.joinToString("、") { File(it).name },
            )
        }
        if (result.kept.isNotEmpty() && (result.deleted.isNotEmpty() || result.failed.isNotEmpty())) {
            LogCenter.op(
                alias,
                "$logPrefix 保留 ${result.kept.size} 个：" +
                    result.kept.joinToString("、") { File(it).name },
            )
        }
        result.missingCloud.forEach { miss ->
            val fallback = miss.keptLocalFallbackName.trim()
            val detail = if (fallback.isNotEmpty()) {
                "（云端包不在本机，已保留较新本地包：$fallback；建议切号还原或重新备份以同步云端元数据）"
            } else {
                "（本机无此文件，需重新登录该账号后自动备份）"
            }
            LogCenter.op(
                alias,
                "$logPrefix 云端备份缺失 @${miss.username}：${miss.cloudBackupName} $detail",
            )
        }
        result.usernameMismatchDeleted.forEach { path ->
            LogCenter.op(
                alias,
                "$logPrefix 已删除 backup_name 与档案 username 不一致的备份：${File(path).name}",
            )
        }
    }

    private enum class CutSwitchFailureKind {
        BACKUP_OR_CLEAR,
        RESTORE_OR_LOGIN,
    }

    private fun notifyAutoSwitchCutSwitchStepFailed(
        payload: DeviceOpPayload?,
        kind: CutSwitchFailureKind = CutSwitchFailureKind.BACKUP_OR_CLEAR,
        detail: String = "",
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
        if (slotKey.isEmpty()) return
        val reason = detail.trim().ifBlank {
            when (kind) {
                CutSwitchFailureKind.BACKUP_OR_CLEAR -> "切号备份/清理失败"
                CutSwitchFailureKind.RESTORE_OR_LOGIN -> "切号还原/登录失败"
            }
        }
        when (kind) {
            CutSwitchFailureKind.BACKUP_OR_CLEAR -> {
                autoSwitchLocalScheduler.cancelPendingCutSwitchFollowUps(slotKey)
                autoSwitchLocalScheduler.clearCutSwitchInflight()
                autoSwitchLocalScheduler.invalidateSlotAlignment(slotKey, reason)
                AutoSwitchLedger.recordCutSwitchSlotAborted(slotKey, reason)
            }
            CutSwitchFailureKind.RESTORE_OR_LOGIN -> {
                autoSwitchLocalScheduler.invalidateSlotAlignmentRestoreOnly(slotKey, reason)
            }
        }
        scope.launch {
            delay(300)
            autoSwitchLocalScheduler.requestImmediateTick()
        }
    }

    /** 拉取循环被 cancel 时，executeForUsername 可能抛出带 cancelled 文案的异常。 */
    private fun isPullLoopJobCancellation(err: Throwable): Boolean {
        var t: Throwable? = err
        while (t != null) {
            if (t is CancellationException) return true
            t = t.cause
        }
        val msg = err.message.orEmpty()
        return msg.contains("cancelled", ignoreCase = true) ||
            msg.contains("CancellationException", ignoreCase = true)
    }

    @Volatile
    private var manuallyPaused: Boolean = false
    private val pendingUsernames = mutableListOf<String>()
    private val pulledTracker = linkedSetOf<String>()
    private val completedTracker = linkedSetOf<String>()
    private val failedTracker = linkedSetOf<String>()
    @Volatile
    private var forceImmediatePull: Boolean = false

    /** 服务端下发休息（pullUsername code=2）期间正在 `delay`，与「自动启动」语义冲突，此窗口内不触发自动恢复、不打误导日志。 */
    @Volatile
    private var inPullUsernameRestSleep: Boolean = false

    fun isInPullUsernameRestSleep(): Boolean = inPullUsernameRestSleep

    fun init(application: android.content.Context) {
        appContext = application.applicationContext
        PublishPostGrowthGate.installEnterHandler { alias -> enterPublishPostExclusivePhase(alias) }
        registerDailyIncubateScreenReceiver()
        ProcessDiagnostics.registerSnapshotProvider {
            buildString {
                append("worker=${WorkerServiceTracker.isRunning}")
                append(" pull=${pullLoopJob?.isActive == true}")
                append(" queue=${pendingUsernames.size}")
                append(" preempt=${PrefsStore.isAutoSwitchExclusivePreempt()}")
                append(" status=${PrefsStore.getLocalStatus()}")
                append(" hardStop=${PrefsStore.isHardStopRequested()}")
            }
        }
        ProcessDiagnostics.registerStatusLineProvider {
            buildAutoSwitchStatusLogLine()
        }
        updateRuntime(
            serviceRunning = WorkerServiceTracker.isRunning,
            localStatus = PrefsStore.getLocalStatus(),
            currentServerUrl = ApiService.getBaseUrl(),
            lastMessage = "应用已初始化",
        )
        scope.launch {
            // 冷启最先校时：墙钟错乱会导致 HTTPS 假断网，必须先于云端同步/开 TikTok
            runCatching {
                DeviceClockSync.ensureSynced(PrefsStore.getAlias() ?: "SYSTEM", "冷启校时")
            }
            if (BindingRecovery.tryRecoverFromCloud(appContext)) {
                ensureDeviceOpsLoopIfBound()
            }
            tryUploadPendingAutoSwitchSummary()
            startIfNeeded()
        }
    }

    /** Worker / 外部触发：异步校时（有短缓存，可重复调用）。 */
    fun ensureClockSyncedAsync(tag: String = "系统校时") {
        scope.launch {
            runCatching {
                DeviceClockSync.ensureSynced(PrefsStore.getAlias() ?: "SYSTEM", tag)
            }
        }
    }

    /** 周期性状态行：涨粉空窗时也能从日志看出卡在哪。 */
    private fun buildAutoSwitchStatusLogLine(): String {
        val phase = ProcessDiagnostics.currentPhase().ifBlank { "idle" }
        val slot = PrefsStore.getAutoSwitchLastSlotKey()?.trim()?.takeIf { it.isNotEmpty() } ?: "-"
        val pullOn = pullLoopJob?.isActive == true
        val execOn = ExecutionScope.isActive()
        val pull = when {
            pullOn && execOn -> "开(执行中)"
            pullOn -> "开"
            execOn -> "关(exec占用)"
            else -> "关"
        }
        val exclusive = when {
            autoSwitchLocalScheduler.isExclusiveWorkActive() -> "忙"
            PrefsStore.isAutoSwitchExclusivePreempt() -> "抢占中"
            else -> "闲"
        }
        val profilePart = if (basicProfileEditRunning) "改资料中" else "否"
        val mutualPart = when {
            isMutualSwitchActive() || AutoSwitchLocalPlanStore.isMutualContextOccupying() -> "互点中"
            isPublishPostSwitchActive() || AutoSwitchLocalPlanStore.isPublishContextOccupying() -> "发作品中"
            isLiveVisitSwitchActive() || AutoSwitchLocalPlanStore.isLiveVisitContextOccupying() -> "主播访问中"
            autoLoginActive -> "自动登录中"
            else -> "否"
        }
        return "[状态] 阶段=$phase slot=$slot 涨粉pull=$pull 独占OP=$exclusive 改资料/互点占用=$profilePart/$mutualPart"
    }

    private fun ensureDeviceOpsLoopIfBound() {
        if (!PrefsStore.isBound()) return
        if (PrefsStore.isAutoSwitchAutonomousMode()) return
        if (deviceOpsJob?.isActive != true) {
            startDeviceOpsLoop()
        }
    }

    suspend fun bootstrapAfterBinding() {
        val alias = PrefsStore.getAlias() ?: return
        try {
            syncOnline()
        } catch (error: Exception) {
            LogCenter.op(alias, "上线同步失败：${error.message}")
        }
        fetchConfig(logToUser = true)
        if (PrefsStore.isAutoSwitchAutonomousMode() && PrefsStore.getAutoSwitchMode() == "on") {
            if (!PrefsStore.isHardStopRequested() && !manuallyPaused) {
                recoverAutoSwitchAutonomousWork("进程绑定就绪")
            } else {
                LogCenter.op(alias, "自动切号自治：硬停/手动暂停中，未自动恢复任务")
                updateRuntime(
                    localStatus = PrefsStore.getLocalStatus(),
                    currentServerUrl = ApiService.getBaseUrl(),
                    lastMessage = "自动切号自治：硬停/暂停中",
                )
            }
        } else {
            LogCenter.op(alias, "设备已就绪，请在管理台点击「启动设备」开始涨粉/任务")
            updateRuntime(
                localStatus = PrefsStore.getLocalStatus(),
                currentServerUrl = ApiService.getBaseUrl(),
                lastMessage = "已就绪，等待管理台启动",
            )
        }
        enqueuePendingReports()
    }

    fun startIfNeeded() {
        // 即使硬停/暂停业务流程，也保持设备任务轮询（如素材下发）可用。
        ensureDeviceOpsLoopIfBound()
        if (!PrefsStore.isBound()) {
            return
        }
        if (manuallyPaused) {
            return
        }
        if (PrefsStore.isHardStopRequested()) {
            if (startPollJob?.isActive != true) {
                startCheckLoop()
            }
            return
        }
        if (started.compareAndSet(false, true)) {
            scope.launch {
                // 与冷启互补：服务重建时也先 await 校时，再 HTTPS/业务
                runCatching {
                    DeviceClockSync.ensureSynced(PrefsStore.getAlias() ?: "SYSTEM", "启动前校时")
                }
                bootstrapAfterBinding()
                if (!PrefsStore.isAutoSwitchAutonomousMode()) {
                    PrefsStore.saveLocalStatus("online")
                    updateRuntime(localStatus = "online", lastMessage = "在线，等待管理台启动")
                }
                startCheckLoop()
                maybeResumeDailyIncubateAfterProcessStart()
            }
            return
        }
        ensureLoopsRunning()
        maybeResumeDailyIncubateAfterProcessStart()
    }

    private fun maybeResumeDailyIncubateAfterProcessStart() {
        if (!ManualDeviceControl.ENABLED) return
        if (dailyIncubateJob?.isActive == true) return
        val sess = PrefsStore.getDailyIncubateSession() ?: return
        if (!sess.enabled) return
        if (sess.pausedByScreenOff) {
            val interactive = runCatching {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.isInteractive == true
            }.getOrDefault(true)
            if (!interactive) return
        }
        LogCenter.op(PrefsStore.getAlias(), "日常孵化：进程恢复后自动续跑")
        PrefsStore.saveDailyIncubateSession(sess.copy(pausedByScreenOff = false))
        manualStartDailyIncubate(sess.plannedMin, resumeFromPause = true)
    }

    fun forceStartRunningNow(reason: String = "设备端启动后台服务") {
        if (!PrefsStore.isBound()) {
            val alias = PrefsStore.getAlias()
            if (!alias.isNullOrBlank()) {
                LogCenter.op(alias, "启动运行被拒绝：设备未绑定")
            }
            return
        }
        if (PrefsStore.getAuthToken().isNullOrBlank()) {
            val alias = PrefsStore.getAlias()
            if (!alias.isNullOrBlank()) {
                LogCenter.op(alias, "启动运行被拒绝：未登录云端")
            }
            return
        }
        scope.launch {
            val alias = PrefsStore.getAlias()
            if (ManualDeviceControl.ENABLED) {
                PrefsStore.setTransientPausedForDestructiveOp(false)
                if (PrefsStore.isHardStopRequested()) {
                    PrefsStore.setHardStopRequested(false)
                }
                manuallyPaused = false
                if (!started.get()) {
                    started.set(true)
                }
                fetchConfig()
                enqueuePendingReports()
                if (PrefsStore.isManualLocalAutoSwitchEnabled()) {
                    PrefsStore.saveLocalStatus("running")
                    updateRuntime(
                        localStatus = "running",
                        hardStopRequested = false,
                        lastMessage = "本机自动切号：$reason",
                    )
                    if (!WorkerServiceTracker.isRunning) {
                        runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "force_start_running_now") }
                    }
                    startCheckLoop()
                    ensureDeviceOpsLoopIfBound()
                    recoverAutoSwitchAutonomousWork("显式启动")
                    if (!alias.isNullOrBlank()) {
                        LogCenter.op(alias, "本机自动切号：已按持久化计划恢复调度（$reason）")
                    }
                    return@launch
                }
                // 活跃下个 / 自动活跃 / 批量发后活跃：须保持 running，否则涨粉拉取循环空转。
                if (isBatchAccountSessionBusy()) {
                    PrefsStore.saveLocalStatus("running")
                    updateRuntime(
                        localStatus = "running",
                        hardStopRequested = false,
                        lastMessage = reason,
                    )
                    if (!WorkerServiceTracker.isRunning) {
                        runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "force_start_running_now") }
                    }
                    startCheckLoop()
                    ensureDeviceOpsLoopIfBound()
                    if (!alias.isNullOrBlank()) {
                        LogCenter.op(alias, "手动活跃会话中：保持 running（$reason）")
                    }
                    return@launch
                }
                PrefsStore.saveLocalStatus("online")
                updateRuntime(
                    localStatus = "online",
                    hardStopRequested = false,
                    lastMessage = "手动模式：$reason（等待按钮触发任务）",
                )
                if (!WorkerServiceTracker.isRunning) {
                    runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "force_start_running_now") }
                }
                startCheckLoop()
                ensureDeviceOpsLoopIfBound()
                if (!alias.isNullOrBlank()) {
                    LogCenter.op(alias, "手动模式：后台已就绪，不会自动涨粉/切号（$reason）")
                }
                return@launch
            }
            if (PublishPostGrowthGate.isBlockingGrowth()) {
                if (!alias.isNullOrBlank()) {
                    LogCenter.op(alias, "发作品独占发布中，忽略显式启动（$reason）")
                }
                return@launch
            }
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                recoverStaleAutoSwitchExclusiveState("显式启动前")
            }
            if (PrefsStore.isAutoSwitchExclusivePreempt() && isAutoSwitchExclusiveWorkActuallyActive()) {
                if (!alias.isNullOrBlank()) {
                    LogCenter.op(alias, "切号/独占操作进行中，忽略显式启动（$reason）")
                }
                return@launch
            }
            if (PrefsStore.isAutoSwitchExclusivePreempt()) {
                PrefsStore.setAutoSwitchExclusivePreempt(false)
            }
            val wasRunning = PrefsStore.getLocalStatus() == "running"
            // 显式启动（本机按钮/云端自动拉起）时，解除本地硬停与手动暂停闸门。
            PrefsStore.setTransientPausedForDestructiveOp(false)
            if (PrefsStore.isHardStopRequested()) {
                PrefsStore.setHardStopRequested(false)
            }
            manuallyPaused = false
            if (!started.get()) {
                started.set(true)
            }
            fetchConfig()
            if (
                !PrefsStore.isAutoSwitchAutonomousMode() &&
                !wasRunning &&
                queueRunMode() == ServerRunMode.GROWTH
            ) {
                growthModeLoginSafeguardPending = true
            }
            enqueuePendingReports()
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", hardStopRequested = false, lastMessage = reason)
            if (!WorkerServiceTracker.isRunning) {
                runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "force_start_running_now") }
                    .onFailure {
                        if (!alias.isNullOrBlank()) {
                            LogCenter.op(alias, "自动拉起后台服务失败：${it.message}")
                        }
                    }
            }
            startCheckLoop()
            ensureLoopsRunning()
            runCatching { syncRunningToCloud() }
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                recoverAutoSwitchAutonomousWork("显式启动")
            } else {
                startPullLoop()
            }
        }
    }

    // region 手动模式公开 API（仅 android-simple）

    /** 拉取云端最新分配账号列表，刷新本地缓存与选中账号。 */
    suspend fun manualRefreshAssignedAccounts(): Result<List<ManualDeviceControl.AccountOption>> {
        if (!ManualDeviceControl.ENABLED) return Result.failure(IllegalStateException("非手动模式"))
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) {
            return Result.failure(IllegalStateException("设备未绑定"))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val resp = ApiService.getDeviceAssignedAccountsRoster(deviceId, alias)
                if (resp.code != 0) {
                    error(resp.msg?.trim().orEmpty().ifBlank { "拉取账号列表失败" })
                }
                val options = resp.items.orEmpty().mapNotNull { ManualDeviceControl.fromAssignedItem(it) }
                if (options.isEmpty()) error("云端未返回可用账号")
                PendingActivateRecommend.ingestRoster(resp.items.orEmpty())
                val oldUsers = ManualDeviceControl.getCachedAccounts()
                    .map { it.username.trim().removePrefix("@").lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                ManualDeviceControl.updateCachedAccounts(options)
                PrefsStore.noteDailyIncubateAccountsSeen(
                    options.map { it.username },
                    grandfatherExisting = true,
                )
                val activeKey = PrefsStore.getManualActiveUsername()?.trim()?.removePrefix("@").orEmpty()
                val selectedKey = PrefsStore.getManualSelectedUsername()?.trim()?.removePrefix("@").orEmpty()
                    .ifBlank { PrefsStore.getLogContextUsername()?.trim()?.removePrefix("@").orEmpty() }
                val keep = options.firstOrNull {
                    it.username.equals(activeKey, ignoreCase = true)
                } ?: options.firstOrNull {
                    it.username.equals(selectedKey, ignoreCase = true)
                } ?: options.first()
                PrefsStore.setManualSelectedUsername(keep.username)
                PrefsStore.setLogContextUsername(keep.username)
                val activeDropped = activeKey.isNotBlank() &&
                    options.none { it.username.equals(activeKey, ignoreCase = true) }
                if (activeDropped) {
                    LogCenter.op(
                        alias,
                        "手动模式：本机当前 @$activeKey 已在云端设为不参与，已从列表移除；请切到其他账号对齐",
                    )
                }
                val newUsers = options.map { it.username.trim().removePrefix("@").lowercase() }.toSet()
                val rosterChanged = oldUsers != newUsers
                // 账号集合变化时重建本机计划，避免继续调度已排除账号
                if (rosterChanged && PrefsStore.isManualLocalAutoSwitchEnabled()) {
                    val minutes = PrefsStore.getManualAutoSwitchSlotMinutes()
                    val rebuilt = rebuildAndStartManualLocalAutoSwitch(
                        alias = alias,
                        deviceId = deviceId,
                        slotMinutes = minutes,
                        reason = "刷新账号后同步本机计划",
                    )
                    if (!rebuilt) {
                        LogCenter.op(alias, "手动模式：账号列表已更新，但本机计划重建失败，请手动点「重建计划」")
                    }
                }
                val pendingCount = PrefsStore.getPendingActivateUsernames().size
                LogCenter.op(
                    alias,
                    "手动模式：已刷新云端账号 ${options.size} 个（待活跃队列 $pendingCount），当前选中 @${keep.username}" +
                        if (activeDropped) "（本机当前仍为 @$activeKey，待切号）" else "",
                )
                options
            }
        }
    }

    /** 旧本机缓存缺 two_fa 时，从账号列表补全并写回。 */
    suspend fun manualEnrichTwoFaIfMissing(username: String): Boolean {
        if (!ManualDeviceControl.ENABLED) return false
        val user = username.trim().removePrefix("@")
        if (user.isBlank()) return false
        val cached = ManualDeviceControl.findCachedAccount(user) ?: return false
        if (cached.twoFa.isNotBlank()) return false
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return false
        return resolveManualAccountTwoFa(alias, deviceId, user).isNotBlank()
    }

    fun manualSelectAccount(username: String) {
        val u = username.trim().removePrefix("@")
        if (u.isBlank()) return
        // 仅更新下拉选中目标，不改本机 active / logContext（避免切号前把源号冲掉）
        PrefsStore.setManualSelectedUsername(u)
    }

    /** 下拉选中账号与本机当前是否已对齐（切号完成后为 true）。 */
    fun isManualAccountAligned(): Boolean {
        val selected = ManualDeviceControl.selectedManualUsername()
        val active = ManualDeviceControl.activeManualUsername()
        if (selected.isBlank()) return false
        return active.isNotBlank() && active.equals(selected, ignoreCase = true)
    }

    /** 仅看 busy 标记，避免 finally 中 job 尚未置空时 UI 误判仍在切号。 */
    fun isManualSwitchBusy(): Boolean = _manualSwitchBusy.value

    fun isActivateNextBusy(): Boolean = activateNextJob?.isActive == true

    /** 任一多账号会话占用中（活跃下个 / 批量发作品 / 自动活跃 / 日常孵化）。 */
    fun isBatchAccountSessionBusy(): Boolean =
        activateNextJob?.isActive == true ||
            batchPublishJob?.isActive == true ||
            autoActiveJob?.isActive == true ||
            dailyIncubateJob?.isActive == true

    fun isDailyIncubateBusy(): Boolean = dailyIncubateJob?.isActive == true

    private fun notifyManualAccountUi() {
        _manualAccountUiEpoch.value = _manualAccountUiEpoch.value + 1
    }

    private fun cancelActivateNextIfRunning(reason: String) {
        val job = activateNextJob ?: return
        if (!job.isActive) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "取消待活跃会话：$reason")
        }
        job.cancel()
        activateNextJob = null
    }

    private fun cancelBatchPublishIfRunning(reason: String) {
        val job = batchPublishJob ?: return
        if (!job.isActive) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "取消批量发作品：$reason")
        }
        job.cancel()
        batchPublishJob = null
        // 批量会话可能正卡在 awaitManualPublishPost；一并打断发作品子任务
        if (isPublishPostSwitchActive()) {
            publishPostSwitchJob?.cancel()
            publishPostSwitchJob = null
            PrefsStore.clearPublishPostState()
            PrefsStore.clearPublishPostTaskScope()
            PublishPostGrowthGate.exitExclusivePublishPhase()
        }
    }

    private fun cancelAutoActiveIfRunning(reason: String) {
        val job = autoActiveJob ?: return
        if (!job.isActive) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "取消自动活跃：$reason")
        }
        job.cancel()
        autoActiveJob = null
    }

    private fun cancelDailyIncubateIfRunning(reason: String) {
        val job = dailyIncubateJob ?: return
        if (!job.isActive) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "取消日常孵化：$reason")
        }
        job.cancel()
        dailyIncubateJob = null
    }

    @Volatile
    private var dailyIncubatePauseReason: String? = null

    private var dailyIncubateScreenReceiver: BroadcastReceiver? = null

    private fun registerDailyIncubateScreenReceiver() {
        if (dailyIncubateScreenReceiver != null) return
        if (!::appContext.isInitialized) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        manualPauseDailyIncubateForSleep("熄屏暂停")
                        manualPausePendingActivateForSleep("熄屏暂停")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        manualResumeDailyIncubateAfterScreenOn()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            dailyIncubateScreenReceiver = receiver
        }.onFailure {
            LogCenter.op(PrefsStore.getAlias(), "日常孵化：注册熄屏监听失败：${it.message}")
        }
    }

    /**
     * 熄屏 / PC「退出 TikTok 并熄屏」：暂停待活跃会话（活跃下个 / 自动活跃），
     * 按已跑时长记账并写入剩余秒数，队列与账号不丢；再次点「自动活跃」会同号续跑。
     */
    data class PausePendingActivateResult(
        val paused: Boolean,
        val hadSession: Boolean,
        val username: String,
        val remainingSec: Int,
        val message: String,
    )

    @Volatile
    private var pendingActivatePauseReason: String? = null

    fun manualPausePendingActivateForSleep(reason: String = "熄屏暂停"): PausePendingActivateResult {
        if (!ManualDeviceControl.ENABLED) {
            return PausePendingActivateResult(
                paused = false,
                hadSession = false,
                username = "",
                remainingSec = 0,
                message = "非手动模式",
            )
        }
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val activeUser = ManualDeviceControl.activeManualUsername().trim().removePrefix("@")
        val autoJob = autoActiveJob?.takeIf { it.isActive }
        val nextJob = activateNextJob?.takeIf { it.isActive }
        val hadSession = autoJob != null || nextJob != null
        if (!hadSession) {
            val planned = PrefsStore.getManualActivateDurationMin()
            val remain = if (activeUser.isNotBlank()) {
                PrefsStore.getActivateSessionRemainingSec(activeUser, planned) ?: 0
            } else {
                0
            }
            val msg = if (remain > 0 && activeUser.isNotBlank()) {
                "无进行中的待活跃任务（@$activeUser 已有剩余 ${formatActivateRemainingLabel(remain)}）"
            } else {
                "无进行中的待活跃任务"
            }
            if (alias.isNotBlank()) LogCenter.op(alias, "待活跃暂停：$msg")
            return PausePendingActivateResult(
                paused = false,
                hadSession = false,
                username = activeUser,
                remainingSec = remain,
                message = msg,
            )
        }

        pendingActivatePauseReason = reason
        val jobs = listOfNotNull(autoJob, nextJob)
        jobs.forEach { it.cancel(CancellationException(reason)) }
        // 等协程在 CancellationException 路径落盘剩余时长后再返回（避免嵌套 runBlocking）
        val latch = java.util.concurrent.CountDownLatch(jobs.size)
        jobs.forEach { job ->
            job.invokeOnCompletion { latch.countDown() }
        }
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        if (autoActiveJob === autoJob) autoActiveJob = null
        if (activateNextJob === nextJob) activateNextJob = null
        pullLoopJob?.cancel()
        pullLoopJob = null
        forYouSwipeJob?.cancel()
        forYouSwipeJob = null
        pendingActivatePauseReason = null

        val planned = PrefsStore.getManualActivateDurationMin()
        val remainUser = activeUser.ifBlank {
            PrefsStore.getPendingActivateUsernames().firstOrNull().orEmpty()
        }
        val remain = if (remainUser.isNotBlank()) {
            PrefsStore.getActivateSessionRemainingSec(remainUser, planned) ?: 0
        } else {
            0
        }
        val msg = buildString {
            append("待活跃已暂停")
            if (remainUser.isNotBlank()) {
                append("：@$remainUser")
                if (remain > 0) {
                    append(" 剩余 ${formatActivateRemainingLabel(remain)}")
                } else {
                    append("（本段已记入，无剩余）")
                }
            }
            append(" · $reason")
        }
        PrefsStore.saveLocalStatus("online")
        updateRuntime(localStatus = "online", lastMessage = msg)
        notifyManualAccountUi()
        if (alias.isNotBlank()) LogCenter.op(alias, msg)
        return PausePendingActivateResult(
            paused = true,
            hadSession = true,
            username = remainUser,
            remainingSec = remain,
            message = msg,
        )
    }

    private fun cancelMultiAccountSessionsIfForeign(reason: String) {
        if (activateNextOwnsSwitch) return
        cancelActivateNextIfRunning(reason)
        cancelBatchPublishIfRunning(reason)
        cancelAutoActiveIfRunning(reason)
        cancelDailyIncubateIfRunning(reason)
    }

    /** 将当前下拉账号加入待活跃队列（须用户明确点选加入）。 */
    fun manualEnqueuePendingActivate(username: String? = null): Boolean {
        if (!ManualDeviceControl.ENABLED) return false
        val user = (username ?: ManualDeviceControl.selectedManualUsername())
            .trim().removePrefix("@")
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (user.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "待活跃：请先选择账号")
            return false
        }
        val added = PrefsStore.enqueuePendingActivateUsername(user)
        if (added) {
            LogCenter.op(alias.ifBlank { null }, "待活跃：已加入 @$user（队列 ${PrefsStore.getPendingActivateUsernames().size}）")
            notifyManualAccountUi()
        } else {
            LogCenter.op(alias.ifBlank { null }, "待活跃：@$user 已在队列中")
        }
        return added
    }

    fun manualRemovePendingActivate(username: String): Boolean {
        if (!ManualDeviceControl.ENABLED) return false
        val user = username.trim().removePrefix("@")
        if (user.isBlank()) return false
        val removed = PrefsStore.removePendingActivateUsername(user)
        if (removed) {
            LogCenter.op(
                PrefsStore.getAlias()?.trim().orEmpty().ifBlank { null },
                "待活跃：已移出 @$user（队列 ${PrefsStore.getPendingActivateUsernames().size}）",
            )
            notifyManualAccountUi()
        } else {
            LogCenter.op(PrefsStore.getAlias()?.trim().orEmpty().ifBlank { null }, "待活跃：@$user 不在队列中")
        }
        return removed
    }

    fun manualClearPendingActivateQueue() {
        if (!ManualDeviceControl.ENABLED) return
        PrefsStore.clearPendingActivateUsernames()
        LogCenter.op(PrefsStore.getAlias(), "待活跃：已清空队列")
        notifyManualAccountUi()
    }

    /**
     * 将「三天总赞增长 ≤ 阈值」的推荐账号一次性加入待活跃队列。
     * @return 新加入数量。
     */
    fun manualEnqueueRecommendedPendingActivate(): Int {
        if (!ManualDeviceControl.ENABLED) return 0
        var added = 0
        for (username in PendingActivateRecommend.recommendedUsernamesNotInQueue()) {
            if (PrefsStore.enqueuePendingActivateUsername(username)) added++
        }
        if (added > 0) {
            LogCenter.op(
                PrefsStore.getAlias(),
                "待活跃：一键加入推荐 $added 个（阈值=${PrefsStore.getPendingActivateHeartGrowthMax()}）",
            )
            notifyManualAccountUi()
        }
        return added
    }

    /** 按指定顺序整体替换待活跃队列（用于按活跃时长编辑）。 */
    fun manualReplacePendingActivateQueue(usernames: List<String>) {
        if (!ManualDeviceControl.ENABLED) return
        PrefsStore.setPendingActivateUsernames(usernames)
        LogCenter.op(
            PrefsStore.getAlias(),
            "待活跃：已更新队列（${PrefsStore.getPendingActivateUsernames().size}）",
        )
        notifyManualAccountUi()
    }

    /**
     * 按活跃累计时长升序重排待活跃队列（少活跃的排前面，优先被活跃）。
     * @return 重排后的队列。
     */
    fun manualSortPendingActivateByTotalMinutesAsc(): List<String> {
        val sorted = PrefsStore.getPendingActivateUsernames()
            .sortedBy { PrefsStore.getActivateTotalMinutes(it) }
        PrefsStore.setPendingActivateUsernames(sorted)
        notifyManualAccountUi()
        return PrefsStore.getPendingActivateUsernames()
    }

    /**
     * 从待活跃队列移除累计活跃时长 ≥ [minMinutes] 的账号。
     * @return 移除数量。
     */
    fun manualTrimPendingActivateByMinTotal(minMinutes: Int): Int {
        val threshold = minMinutes.coerceAtLeast(0)
        val before = PrefsStore.getPendingActivateUsernames()
        val kept = before.filter { PrefsStore.getActivateTotalMinutes(it) < threshold }
        PrefsStore.setPendingActivateUsernames(kept)
        notifyManualAccountUi()
        return before.size - kept.size
    }

    /**
     * 等待手动切号协程结束，并返回目标是否已就绪。
     */
    private suspend fun awaitManualSwitchReady(target: String): Boolean {
        val switchJob = manualSwitchJob
        if (switchJob != null) {
            switchJob.join()
        } else {
            var wait = 0
            while (_manualSwitchBusy.value && wait < 120) {
                delay(250)
                wait++
            }
        }
        return ManualDeviceControl.activeManualUsername()
            .equals(target, ignoreCase = true) &&
            PrefsStore.isManualFirstLoginDone(target)
    }

    /**
     * 多账号会话内切到目标号（尊重用户切号方案）。已对齐且就绪则跳过。
     * @return true 表示目标已就绪。
     */
    private suspend fun switchToAccountForBatchSession(target: String): Boolean {
        val active = ManualDeviceControl.activeManualUsername()
        val alreadyReady = active.isNotBlank() &&
            active.equals(target, ignoreCase = true) &&
            PrefsStore.isManualFirstLoginDone(target)
        if (alreadyReady) {
            PrefsStore.setManualSelectedUsername(target)
            PrefsStore.setLogContextUsername(target)
            LogCenter.op(
                PrefsStore.getAlias(),
                "多账号会话：本机已是 @$target，跳过切号",
            )
            return true
        }
        activateNextOwnsSwitch = true
        try {
            PrefsStore.setManualSelectedUsername(target)
            // backupCurrentFirst 由切号方案决定：登录切号时 manualSwitchAccount 内部会跳过备份
            manualSwitchAccount(target, backupCurrentFirst = true)
            return awaitManualSwitchReady(target)
        } finally {
            // 父会话仍在跑时保持 owns；仅在切号子步骤结束后由外层统一清理
        }
    }

    private fun isAccountInPendingActivateQueue(username: String): Boolean {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return false
        return PrefsStore.getPendingActivateUsernames().any { it.equals(u, ignoreCase = true) }
    }

    /**
     * 待活跃：本机已是目标且（在队列中或有未完成时长）则跳过切号，直接活跃/续跑。
     */
    private suspend fun switchToAccountForActivateIfNeeded(target: String, logTag: String): Boolean {
        val active = ManualDeviceControl.activeManualUsername()
        val aligned = active.isNotBlank() &&
            active.equals(target, ignoreCase = true) &&
            PrefsStore.isManualFirstLoginDone(target)
        if (!aligned) {
            return switchToAccountForBatchSession(target)
        }
        PrefsStore.setManualSelectedUsername(target)
        PrefsStore.setLogContextUsername(target)
        val remaining = PrefsStore.getActivateSessionRemainingSec(
            target,
            PrefsStore.getManualActivateDurationMin(),
        ) ?: 0
        val inQueue = isAccountInPendingActivateQueue(target)
        val suffix = when {
            remaining > 0 -> "，续跑剩余 ${formatActivateRemainingLabel(remaining)}"
            inQueue -> "，队列内直接活跃"
            else -> ""
        }
        LogCenter.op(PrefsStore.getAlias(), "$logTag：本机已是 @$target，跳过切号$suffix")
        return true
    }

    /**
     * 手动模式「活跃下个 / 自动活跃 / 批量发后活跃」内启动涨粉拉取。
     * 必须同步写入 PrefsStore running；不可走 [forceStartRunningNow] 的手动 online 分支。
     */
    private suspend fun ensureManualActivateGrowthPullRunning(reason: String) {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        PrefsStore.setTransientPausedForDestructiveOp(false)
        if (PrefsStore.isHardStopRequested()) {
            PrefsStore.setHardStopRequested(false)
        }
        manuallyPaused = false
        // 发作品/手动抢占取消后可能遗留 preempt，不清除则拉号空转
        releaseStaleManualExclusivePreemptIfIdle("手动活跃涨粉·$reason")
        if (!started.get()) started.set(true)
        growthModeLoginSafeguardPending = true
        PrefsStore.saveLocalStatus("running")
        updateRuntime(
            localStatus = "running",
            hardStopRequested = false,
            lastMessage = reason,
        )
        if (!WorkerServiceTracker.isRunning) {
            runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "manual_activate_growth") }
        }
        startCheckLoop()
        ensureDeviceOpsLoopIfBound()
        startPullLoop()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "手动活跃涨粉：已启动拉取循环（$reason）")
        }
    }

    /** 本机在队列中且仍有未完成活跃时长 → 优先续跑当前号（刚下机休息的号除外，应切下一号）。 */
    private fun resolveActivateResumeTarget(queue: List<String>, plannedMin: Int): String? {
        val active = ManualDeviceControl.activeManualUsername().trim().removePrefix("@")
        if (active.isBlank()) return null
        if (PrefsStore.shouldSkipActivateResume(active)) return null
        val remaining = PrefsStore.getActivateSessionRemainingSec(active, plannedMin) ?: 0
        if (remaining <= 0) return null
        if (!queue.any { it.equals(active, ignoreCase = true) }) return null
        return active
    }

    private enum class ActivatePhaseResult {
        /** 总计划时长已跑完。 */
        PLANNED_DONE,
        /** 本段连续活跃结束并下机，剩余总时长待轮转后再补。 */
        STINT_REST,
        /** 中断（硬停等），同号可续跑。 */
        INCOMPLETE,
    }

    /**
     * 待活跃连续上机切段：
     * - 剩余 < 30 分钟：向上取整随机抽 30–58 分钟，作为最后一段跑完；
     * - 剩余 ≤ 58 分钟：本段直接跑完；
     * - 剩余 > 58 分钟：随机抽 30–58，且保证切后剩余为 0 或 ≥ 30；无法满足则改为末段 30–58 跑完。
     */
    private data class ActivateOnlineStintPlan(
        val durationSec: Int,
        /** 本段跑完即视为总计划完成（含剩余 <30 向上取整为 30–58 的末段）。 */
        val finalStint: Boolean,
    )

    private fun resolveActivateOnlineStintPlan(
        remainingTotalSec: Int,
        splitOnlineStint: Boolean,
    ): ActivateOnlineStintPlan {
        if (!splitOnlineStint) {
            return ActivateOnlineStintPlan(remainingTotalSec, finalStint = true)
        }
        val minSec = PrefsStore.ACTIVATE_ONLINE_STINT_MIN_SEC
        val maxSec = PrefsStore.ACTIVATE_ONLINE_STINT_MAX_SEC
        if (remainingTotalSec < minSec) {
            return ActivateOnlineStintPlan(
                durationSec = Random.nextInt(minSec, maxSec + 1),
                finalStint = true,
            )
        }
        if (remainingTotalSec <= maxSec) {
            return ActivateOnlineStintPlan(remainingTotalSec, finalStint = true)
        }
        // 切后剩余须 ≥ 30：本段最长 = min(58, remaining-30)
        val maxStintKeepingValidLeftover = (remainingTotalSec - minSec).coerceAtMost(maxSec)
        if (maxStintKeepingValidLeftover < minSec) {
            return ActivateOnlineStintPlan(
                durationSec = Random.nextInt(minSec, maxSec + 1),
                finalStint = true,
            )
        }
        return ActivateOnlineStintPlan(
            durationSec = Random.nextInt(minSec, maxStintKeepingValidLeftover + 1),
            finalStint = false,
        )
    }

    private fun rotateQueueAfterActivateStint(target: String, restSkip: Boolean) {
        if (target.equals(PrefsStore.peekPendingActivateUsername(), ignoreCase = true)) {
            PrefsStore.rotatePendingActivateQueueFrontToBack()
        }
        if (restSkip) {
            PrefsStore.markActivateStintRestSkip(target)
        }
        notifyManualAccountUi()
    }

    private fun formatActivateDurationLabel(minutes: Int): String {
        val hours = minutes / 60.0
        return "${PrefsStore.formatManualActivateDurationHours(hours)}小时"
    }

    private fun formatActivateRemainingLabel(remainingSec: Int): String {
        val min = (remainingSec + 59) / 60
        return formatActivateDurationLabel(min)
    }

    private fun creditActivateMinutes(target: String, elapsedSec: Int, modeLabel: String) {
        val credited = ((elapsedSec + 59) / 60).coerceAtLeast(0)
        if (credited <= 0) return
        PrefsStore.addActivateTotalMinutes(target, credited)
        if (modeLabel.contains("For You")) {
            PrefsStore.addActivateForYouMinutes(target, credited)
        } else {
            PrefsStore.addActivateGrowthMinutes(target, credited)
        }
    }

    /**
     * 随机 For You 预滑 / 涨粉一段时间；成功后累加账号活跃总时长。
     * [splitOnlineStint] 为 true 时：按 30–58 分钟切段上机；切后剩余 <30 则向上取整抽 30–58 作为末段跑完。
     * 中断时保存剩余秒数，下次同号在队列内可直接续跑而不切号。
     */
    private suspend fun runRandomActivatePhase(
        target: String,
        minutes: Int,
        logTag: String,
        splitOnlineStint: Boolean = false,
    ): ActivatePhaseResult {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val plannedSec = minutes * 60
        val remainingTotalSec = PrefsStore.getActivateSessionRemainingSec(target, minutes)?.takeIf { it > 0 }
            ?: plannedSec
        val stintPlan = resolveActivateOnlineStintPlan(remainingTotalSec, splitOnlineStint)
        val durationSec = stintPlan.durationSec
        val resuming = remainingTotalSec < plannedSec
        val roundUpFinal = splitOnlineStint &&
            stintPlan.finalStint &&
            remainingTotalSec < PrefsStore.ACTIVATE_ONLINE_STINT_MIN_SEC
        val stintRestPlanned = splitOnlineStint && !stintPlan.finalStint
        PrefsStore.setLogContextUsername(target)
        val useForYou = Random.nextBoolean()
        val modeLabel = if (useForYou) "For You 预滑" else "涨粉"
        val durationLabel = formatActivateRemainingLabel(durationSec)
        val remainTotalLabel = formatActivateRemainingLabel(remainingTotalSec)
        LogCenter.op(
            alias,
            "$logTag：@$target 随机 → $modeLabel（" +
                (if (resuming) "续跑本段 $durationLabel" else "本段 $durationLabel") +
                (when {
                    roundUpFinal ->
                        "，总剩余 $remainTotalLabel <30分钟，向上取整为末段"
                    stintRestPlanned ->
                        "，总剩余 $remainTotalLabel，满段后下机补时"
                    stintPlan.finalStint && splitOnlineStint ->
                        "，末段跑完"
                    else -> ""
                }) +
                "）",
        )
        PrefsStore.saveLocalStatus("running")
        updateRuntime(
            localStatus = "running",
            lastMessage = "活跃中：@$target · $modeLabel · $durationLabel",
        )
        fetchConfig(configQueryRunMode = ServerRunMode.GROWTH, logToUser = false)

        val startedAt = SystemClock.elapsedRealtime()
        var interrupted = false
        try {
            if (useForYou) {
                // 须在父 Job 内直接 suspend 调用；裸 launch {} 无 CoroutineScope 接收者，无法编译。
                ExecutionScope.enter()
                forYouSwipeJob = currentCoroutineContext()[Job]
                try {
                    executor.runForYouSwipe(durationSec, _config.value)
                    currentCoroutineContext().ensureActive()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    LogCenter.op(alias, "$logTag For You 异常：${t.message}")
                } finally {
                    if (forYouSwipeJob === currentCoroutineContext()[Job]) {
                        forYouSwipeJob = null
                    }
                    ExecutionScope.exit()
                }
            } else {
                ensureManualActivateGrowthPullRunning(
                    "活跃中：@$target · $modeLabel · $durationLabel",
                )
                if (pullLoopJob?.isActive != true) {
                    error("$logTag 涨粉未能启动拉取循环")
                }
                val endAt = startedAt + durationSec * 1000L
                while (SystemClock.elapsedRealtime() < endAt) {
                    if (PrefsStore.isHardStopRequested()) {
                        interrupted = true
                        break
                    }
                    currentCoroutineContext().ensureActive()
                    delay(5_000)
                }
                pullLoopJob?.cancel()
                pullLoopJob = null
            }
            currentCoroutineContext().ensureActive()
        } catch (t: Throwable) {
            if (t is CancellationException) {
                // 取消（含熄屏暂停）也必须按已跑时长落盘，否则进度丢失且计时像「空跑」。
                withContext(NonCancellable) {
                    pullLoopJob?.cancel()
                    pullLoopJob = null
                    persistActivatePhaseProgress(
                        target = target,
                        minutes = minutes,
                        remainingTotalSec = remainingTotalSec,
                        durationSec = durationSec,
                        startedAt = startedAt,
                        modeLabel = modeLabel,
                        durationLabel = durationLabel,
                        logTag = logTag,
                        alias = alias,
                        stintPlan = stintPlan,
                        roundUpFinal = roundUpFinal,
                        interrupted = true,
                        pauseReason = pendingActivatePauseReason,
                    )
                }
                throw t
            }
            throw t
        }

        return persistActivatePhaseProgress(
            target = target,
            minutes = minutes,
            remainingTotalSec = remainingTotalSec,
            durationSec = durationSec,
            startedAt = startedAt,
            modeLabel = modeLabel,
            durationLabel = durationLabel,
            logTag = logTag,
            alias = alias,
            stintPlan = stintPlan,
            roundUpFinal = roundUpFinal,
            interrupted = interrupted,
            pauseReason = null,
        )
    }

    private fun persistActivatePhaseProgress(
        target: String,
        minutes: Int,
        remainingTotalSec: Int,
        durationSec: Int,
        startedAt: Long,
        modeLabel: String,
        durationLabel: String,
        logTag: String,
        alias: String,
        stintPlan: ActivateOnlineStintPlan,
        roundUpFinal: Boolean,
        interrupted: Boolean,
        pauseReason: String?,
    ): ActivatePhaseResult {
        val elapsedSec = ((SystemClock.elapsedRealtime() - startedAt) / 1000)
            .toInt().coerceIn(0, durationSec)
        val completedStint = !interrupted && elapsedSec >= durationSec - 2
        val leftoverSec = (remainingTotalSec - elapsedSec).coerceAtLeast(0)
        // 末段（含剩余 <30 向上取整）：跑完即清零，不再留下不足 30 分钟的尾巴。
        val treatAsPlannedDone = completedStint && (
            stintPlan.finalStint ||
                leftoverSec <= 2 ||
                leftoverSec < PrefsStore.ACTIVATE_ONLINE_STINT_MIN_SEC
            )

        creditActivateMinutes(target, elapsedSec, modeLabel)
        notifyManualAccountUi()

        if (treatAsPlannedDone) {
            PrefsStore.clearActivateSession(target)
            LogCenter.op(
                alias,
                "$logTag：@$target 完成（$modeLabel · $durationLabel，累计活跃 ${PrefsStore.getActivateTotalMinutes(target)} 分钟）" +
                    (if (roundUpFinal || (leftoverSec in 3 until PrefsStore.ACTIVATE_ONLINE_STINT_MIN_SEC)) {
                        "（末段已覆盖原剩余 ${formatActivateRemainingLabel(remainingTotalSec)}）"
                    } else {
                        ""
                    }),
            )
            return ActivatePhaseResult.PLANNED_DONE
        }

        if (completedStint && leftoverSec > 2 && isAccountInPendingActivateQueue(target)) {
            PrefsStore.setActivateSessionRemaining(target, leftoverSec, minutes)
            LogCenter.op(
                alias,
                "$logTag：@$target 本段结束，关闭 TikTok 下机休息（已连续 ${formatActivateRemainingLabel(elapsedSec)}，" +
                    "剩余 ${formatActivateRemainingLabel(leftoverSec)} 待后续补时）",
            )
            return ActivatePhaseResult.STINT_REST
        }

        if (leftoverSec > 0 && isAccountInPendingActivateQueue(target)) {
            PrefsStore.setActivateSessionRemaining(target, leftoverSec, minutes)
            val statusWord = if (!pauseReason.isNullOrBlank()) {
                "已暂停（$pauseReason）"
            } else {
                "未完成"
            }
            LogCenter.op(
                alias,
                "$logTag：@$target $statusWord（剩余 ${formatActivateRemainingLabel(leftoverSec)}，" +
                    "已跑 ${formatActivateRemainingLabel(elapsedSec)}，下轮续跑不切号）",
            )
        } else {
            PrefsStore.clearActivateSession(target)
            if (interrupted && !pauseReason.isNullOrBlank()) {
                LogCenter.op(
                    alias,
                    "$logTag：@$target 已暂停（$pauseReason · 本段已记入，无剩余）",
                )
            }
        }
        return ActivatePhaseResult.INCOMPLETE
    }

    /**
     * 启动发作品并等待结束；批量会话内抑制「完成后自动涨粉」。
     * @return 是否至少成功发出目标条数（或 outcome.success）。
     */
    /**
     * @param updatePreferredCount 为 true 时同步写入界面「手动发作品条数」偏好；
     * 过渡期强制发 1 条等一次性覆盖应传 false，避免把用户偏好冲成 1。
     */
    private suspend fun awaitManualPublishPost(
        count: Int,
        updatePreferredCount: Boolean = true,
    ): Boolean {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val sessionId = "batch-pub-${System.currentTimeMillis()}"
        beginManualPublishSession(sessionId)
        // 批量会话自己会做发后活跃，禁止单号发完后自动涨粉抢占
        suppressManualAutoGrowthUntilElapsed = SystemClock.elapsedRealtime() + 24L * 60L * 60L * 1000L
        activateNextOwnsSwitch = true
        if (updatePreferredCount) {
            PrefsStore.setManualPublishCount(count)
        }
        PrefsStore.setPublishPostPackageName(com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME)
        PrefsStore.setPublishPostTargetCount(count)
        PrefsStore.setPublishPostRequested(true)
        PrefsStore.setPublishPostPending(true)

        // 直接触发发作品，避免 enqueueManualTaskOp 再开一层 takeover 取消父会话
        if (!isPublishPostSwitchActive()) {
            triggerPublishPostNow(alias)
        }
        var waitStart = 0
        while (publishPostSwitchJob == null && waitStart < 40) {
            delay(250)
            waitStart++
        }
        publishPostSwitchJob?.join()
        // 收尾闸门可能仍短暂占用
        var gateWait = 0
        while (
            (PublishPostGrowthGate.isBlockingGrowth() || isPublishPostSwitchActive()) &&
            gateWait < 120
        ) {
            delay(500)
            gateWait++
        }
        // 只清遗留独占；勿缩短 24h 抑制窗口（批量发作品依赖它挡住 completion 自动涨粉）
        PrefsStore.setAutoSwitchExclusivePreempt(false)
        PrefsStore.setTransientPausedForDestructiveOp(false)
        currentCoroutineContext().ensureActive()
        val outcome = lastManualPublishOutcome
        val ok = outcome?.sessionId == sessionId && outcome.success
        LogCenter.op(
            alias,
            buildString {
                append("批量发作品等待结束：")
                if (outcome == null) {
                    append("无结果记录")
                } else {
                    append(if (outcome.success) "成功" else "失败")
                    append("（${outcome.successCount}/${outcome.targetCount}）")
                    if (outcome.message.isNotBlank()) append(" ${outcome.message}")
                }
            },
        )
        return ok
    }

    /**
     * 活跃下个账号：队首（或本机中断续跑）→ 本段连续活跃 30–58 分钟（总计划更长则下机补时）→ 完成后队首轮转。
     * 硬停中断且时长未满时不切号，直接续跑；刚下机休息的号会切下一号。
     */
    fun manualActivateNextAccount(durationMin: Int = PrefsStore.getManualActivateDurationMin()) {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "活跃下个账号：设备未绑定")
            return
        }
        if (isBatchAccountSessionBusy()) {
            LogCenter.op(alias, "活跃下个账号：多账号会话进行中，忽略重复点击")
            return
        }
        if (isManualSwitchBusy()) {
            LogCenter.op(alias, "活跃下个账号：切号进行中，请稍候")
            return
        }
        val minutes = durationMin.coerceIn(
            PrefsStore.MIN_MANUAL_ACTIVATE_DURATION_MIN,
            PrefsStore.MAX_MANUAL_ACTIVATE_DURATION_MIN,
        )
        PrefsStore.setManualActivateDurationMin(minutes)
        val queued = PrefsStore.getPendingActivateUsernames()
        if (queued.isEmpty()) {
            LogCenter.op(alias, "活跃下个账号：待活跃队列为空，请先将账号加入队列")
            updateRuntime(lastMessage = "待活跃队列为空")
            return
        }
        val target = resolveActivateResumeTarget(queued, minutes)
            ?: PrefsStore.peekPendingActivateUsername()
        if (target.isNullOrBlank()) {
            LogCenter.op(alias, "活跃下个账号：队列为空")
            return
        }
        PrefsStore.clearActivateStintRestSkip()
        notifyManualAccountUi()
        manualDispatchJob?.cancel()
        activateNextJob = scope.launch {
            var finishedOk = false
            try {
                if (!prepareManualTaskTakeover(ManualTaskKind.ACTIVATE_NEXT, "活跃下个账号")) {
                    notifyManualAccountUi()
                    return@launch
                }
                PrefsStore.setTransientPausedForDestructiveOp(false)
                PrefsStore.setHardStopRequested(false)
                manuallyPaused = false
                if (!started.get()) started.set(true)

                LogCenter.op(
                    alias,
                    "活跃下个账号：开始 @$target（${formatActivateDurationLabel(minutes)}，队列 ${queued.size} 个，切号=${PrefsStore.getManualCutSwitchScheme()}）",
                )
                updateRuntime(
                    localStatus = "running",
                    hardStopRequested = false,
                    lastMessage = "活跃下个账号：准备 @$target",
                )
                PrefsStore.saveLocalStatus("running")

                activateNextOwnsSwitch = true
                val switchedOk = switchToAccountForActivateIfNeeded(target, "活跃下个账号")
                if (!switchedOk) {
                    notifyManualAccountUi()
                    PrefsStore.saveLocalStatus("online")
                    updateRuntime(
                        localStatus = "online",
                        lastMessage = "活跃下个账号：@$target 切号未就绪",
                    )
                    LogCenter.op(alias, "活跃下个账号：@$target 切号未就绪")
                    return@launch
                }

                val completed = runRandomActivatePhase(
                    target,
                    minutes,
                    "活跃下个账号",
                    splitOnlineStint = true,
                )
                finishedOk = completed != ActivatePhaseResult.INCOMPLETE
                if (completed == ActivatePhaseResult.PLANNED_DONE ||
                    completed == ActivatePhaseResult.STINT_REST
                ) {
                    rotateQueueAfterActivateStint(
                        target,
                        restSkip = completed == ActivatePhaseResult.STINT_REST,
                    )
                }
                PrefsStore.saveLocalStatus("online")
                updateRuntime(
                    localStatus = "online",
                    lastMessage = when (completed) {
                        ActivatePhaseResult.PLANNED_DONE ->
                            "活跃完成：@$target · ${formatActivateDurationLabel(minutes)}"
                        ActivatePhaseResult.STINT_REST ->
                            "已下机休息：@$target 本段结束，剩余时长待后续补时"
                        ActivatePhaseResult.INCOMPLETE ->
                            "活跃未完成：@$target 已保存剩余时长，下轮续跑不切号"
                    },
                )
                if (detectActiveManualTaskKind() == null ||
                    detectActiveManualTaskKind() == ManualTaskKind.ACTIVATE_NEXT
                ) {
                    forceStopTikTokForExclusiveEnqueue("活跃下个账号结束")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    val pause = pendingActivatePauseReason
                    if (!pause.isNullOrBlank()) {
                        LogCenter.op(alias, "活跃下个账号：已暂停（$pause）→ @$target")
                        updateRuntime(localStatus = "online", lastMessage = "活跃下个账号已暂停（已保存进度）")
                    } else {
                        LogCenter.op(alias, "活跃下个账号：已取消 → @$target")
                        updateRuntime(localStatus = "online", lastMessage = "活跃下个账号已取消")
                    }
                    throw t
                }
                notifyManualAccountUi()
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "活跃失败：${t.message}")
                LogCenter.op(alias, "活跃下个账号失败 → @$target：${t.message}")
            } finally {
                activateNextOwnsSwitch = false
                if (activateNextJob === currentCoroutineContext()[Job]) {
                    activateNextJob = null
                }
                notifyManualAccountUi()
            }
        }
    }

    /**
     * 自动活跃：按队列顺序轮流活跃；一轮结束后从头循环（不 pop 队列）。
     * 单号连续活跃 30–58 分钟后关 TikTok 下机并切下一号；总计划未满则轮转回来再补。
     * 硬停中断才同号续跑不切号。
     */
    fun manualStartAutoActive(
        usernames: List<String>? = null,
        durationMin: Int = PrefsStore.getManualActivateDurationMin(),
    ) {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "自动活跃：设备未绑定")
            return
        }
        if (isBatchAccountSessionBusy()) {
            LogCenter.op(alias, "自动活跃：多账号会话进行中，忽略重复点击")
            return
        }
        if (isManualSwitchBusy()) {
            LogCenter.op(alias, "自动活跃：切号进行中，请稍候")
            return
        }
        val minutes = durationMin.coerceIn(
            PrefsStore.MIN_MANUAL_ACTIVATE_DURATION_MIN,
            PrefsStore.MAX_MANUAL_ACTIVATE_DURATION_MIN,
        )
        PrefsStore.setManualActivateDurationMin(minutes)
        val seed = usernames
            ?.map { it.trim().removePrefix("@") }
            ?.filter { it.isNotEmpty() }
            ?.distinctBy { it.lowercase(java.util.Locale.US) }
            .orEmpty()
        if (seed.isNotEmpty()) {
            PrefsStore.setPendingActivateUsernames(seed)
            notifyManualAccountUi()
        }
        if (PrefsStore.getPendingActivateUsernames().isEmpty()) {
            LogCenter.op(alias, "自动活跃：待活跃队列为空，请先指定账号")
            updateRuntime(lastMessage = "自动活跃：队列为空")
            return
        }
        manualDispatchJob?.cancel()
        autoActiveJob = scope.launch {
            var processed = 0
            try {
                if (!prepareManualTaskTakeover(ManualTaskKind.AUTO_ACTIVE, "自动活跃")) {
                    return@launch
                }
                PrefsStore.setTransientPausedForDestructiveOp(false)
                PrefsStore.setHardStopRequested(false)
                manuallyPaused = false
                if (!started.get()) started.set(true)
                activateNextOwnsSwitch = true
                val scheme = PrefsStore.getManualCutSwitchScheme()
                LogCenter.op(
                    alias,
                    "自动活跃：开始（每号 ${formatActivateDurationLabel(minutes)}，" +
                        "连续上机 30–58 分钟后下机切下一号，队列 ${PrefsStore.getPendingActivateUsernames().size}，切号=$scheme）",
                )
                PrefsStore.saveLocalStatus("running")

                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (PrefsStore.isHardStopRequested()) break
                    val queue = PrefsStore.getPendingActivateUsernames()
                    if (queue.isEmpty()) break

                    val target = resolveActivateResumeTarget(queue, minutes)
                        ?: PrefsStore.peekPendingActivateUsername()
                        ?: break
                    PrefsStore.clearActivateStintRestSkip()
                    notifyManualAccountUi()
                    PrefsStore.saveLocalStatus("running")
                    updateRuntime(
                        localStatus = "running",
                        lastMessage = "自动活跃：准备 @$target",
                    )
                    val switchedOk = switchToAccountForActivateIfNeeded(target, "自动活跃")
                    if (!switchedOk) {
                        notifyManualAccountUi()
                        PrefsStore.saveLocalStatus("online")
                        LogCenter.op(alias, "自动活跃：@$target 切号未就绪，已停止")
                        updateRuntime(
                            localStatus = "online",
                            lastMessage = "自动活跃：@$target 切号失败，已停止",
                        )
                        break
                    }
                    val completed = runRandomActivatePhase(
                        target,
                        minutes,
                        "自动活跃",
                        splitOnlineStint = true,
                    )
                    processed++
                    if (completed == ActivatePhaseResult.PLANNED_DONE ||
                        completed == ActivatePhaseResult.STINT_REST
                    ) {
                        val leftover = PrefsStore.getActivateSessionRemainingSec(target, minutes) ?: 0
                        updateRuntime(
                            localStatus = "running",
                            lastMessage = if (completed == ActivatePhaseResult.STINT_REST) {
                                "自动活跃下机：@$target（本段结束，剩余 ${formatActivateRemainingLabel(leftover)} 待补）"
                            } else {
                                "自动活跃完成：@$target（第 $processed 次，队列 ${queue.size} 轮转）"
                            },
                        )
                        rotateQueueAfterActivateStint(
                            target,
                            restSkip = completed == ActivatePhaseResult.STINT_REST,
                        )
                        forceStopTikTokForExclusiveEnqueue("自动活跃单号结束")
                        continue
                    }
                    updateRuntime(
                        localStatus = "online",
                        lastMessage = "自动活跃未完成：@$target 已保存剩余时长，下轮续跑不切号",
                    )
                    PrefsStore.saveLocalStatus("online")
                    forceStopTikTokForExclusiveEnqueue("自动活跃单号结束")
                    break
                }

                PrefsStore.saveLocalStatus("online")
                updateRuntime(
                    localStatus = "online",
                    lastMessage = "自动活跃结束：共完成 $processed 次单号活跃，队列 ${PrefsStore.getPendingActivateUsernames().size} 个账号轮转",
                )
                LogCenter.op(alias, "自动活跃结束：共 $processed 次单号活跃")
                if (detectActiveManualTaskKind() == null ||
                    detectActiveManualTaskKind() == ManualTaskKind.AUTO_ACTIVE
                ) {
                    forceStopTikTokForExclusiveEnqueue("自动活跃结束")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    val pause = pendingActivatePauseReason
                    if (!pause.isNullOrBlank()) {
                        updateRuntime(localStatus = "online", lastMessage = "自动活跃已暂停（已保存进度）")
                        LogCenter.op(alias, "自动活跃：已暂停 · $pause（已处理 $processed）")
                    } else {
                        updateRuntime(localStatus = "online", lastMessage = "自动活跃已取消")
                        LogCenter.op(alias, "自动活跃：已取消（已处理 $processed）")
                    }
                    throw t
                }
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "自动活跃失败：${t.message}")
                LogCenter.op(alias, "自动活跃失败：${t.message}")
            } finally {
                activateNextOwnsSwitch = false
                if (autoActiveJob === currentCoroutineContext()[Job]) {
                    autoActiveJob = null
                }
                notifyManualAccountUi()
            }
        }
    }

    private enum class DailyIncubateAction(val key: String, val label: String) {
        GROWTH("growth", "涨粉"),
        FOR_YOU("foryou", "For You 预滑"),
        MUTUAL("mutual", "引流互点"),
        ;

        companion object {
            fun fromKey(raw: String?): DailyIncubateAction? = when (raw?.trim()?.lowercase()) {
                GROWTH.key -> GROWTH
                FOR_YOU.key, "for_you", "foryou_swipe" -> FOR_YOU
                MUTUAL.key, "mutual_click" -> MUTUAL
                else -> null
            }
        }
    }

    private enum class DailyIncubatePhaseResult {
        COMPLETED,
        INCOMPLETE,
        FAILED,
    }

    /**
     * 日常孵化：非待活跃账号排队养号。
     * 切号 → 发作品 → 加权随机动作；涨粉/预滑受分配时长；互点达云端上限即完成。
     * 新号过渡期（完整会话 <3）：第 1–2 次不发作品，第 3 次发 1 条；全程禁止互点。
     * 熄屏暂停计时与任务，亮屏自动续跑。
     */
    fun manualStartDailyIncubate(
        durationMin: Int = PrefsStore.getDailyIncubateDurationMin(),
        resumeFromPause: Boolean = false,
    ) {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "日常孵化：设备未绑定")
            return
        }
        if (dailyIncubateJob?.isActive == true) {
            LogCenter.op(alias, "日常孵化：已在进行中，忽略重复点击")
            return
        }
        if (isBatchAccountSessionBusy()) {
            LogCenter.op(alias, "日常孵化：多账号会话进行中，忽略")
            return
        }
        if (isManualSwitchBusy()) {
            LogCenter.op(alias, "日常孵化：切号进行中，请稍候")
            return
        }
        val minutes = durationMin.coerceIn(
            PrefsStore.MIN_DAILY_INCUBATE_DURATION_MIN,
            PrefsStore.MAX_DAILY_INCUBATE_DURATION_MIN,
        )
        PrefsStore.setDailyIncubateDurationMin(minutes)

        val available = listBoundDeviceNonPendingActivateUsernames(excludeBanned = true)
        PrefsStore.noteDailyIncubateAccountsSeen(available, grandfatherExisting = true)
        val queue = PrefsStore.syncDailyIncubateQueue(available)
        if (queue.isEmpty()) {
            LogCenter.op(alias, "日常孵化：队列为空（非待活跃账号无可用）")
            updateRuntime(lastMessage = "日常孵化：队列为空")
            return
        }

        val existing = PrefsStore.getDailyIncubateSession()
        PrefsStore.saveDailyIncubateSession(
            (existing ?: PrefsStore.DailyIncubateSession()).copy(
                enabled = true,
                pausedByScreenOff = false,
                plannedMin = minutes,
            ),
        )

        manualDispatchJob?.cancel()
        dailyIncubateJob = scope.launch {
            var processed = 0
            try {
                if (!prepareManualTaskTakeover(ManualTaskKind.DAILY_INCUBATE, "日常孵化")) {
                    return@launch
                }
                PrefsStore.setTransientPausedForDestructiveOp(false)
                PrefsStore.setHardStopRequested(false)
                manuallyPaused = false
                if (!started.get()) started.set(true)
                activateNextOwnsSwitch = true
                val scheme = PrefsStore.getManualCutSwitchScheme()
                LogCenter.op(
                    alias,
                    "日常孵化：${if (resumeFromPause) "亮屏续跑" else "开始"}" +
                        "（每号 ${minutes} 分钟，队列 ${queue.size}，切号=$scheme）",
                )
                PrefsStore.saveLocalStatus("running")

                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (PrefsStore.isHardStopRequested()) break

                    val liveAvail = listBoundDeviceNonPendingActivateUsernames(excludeBanned = true)
                    PrefsStore.noteDailyIncubateAccountsSeen(liveAvail, grandfatherExisting = false)
                    val liveQueue = PrefsStore.syncDailyIncubateQueue(liveAvail)
                    if (liveQueue.isEmpty()) {
                        LogCenter.op(alias, "日常孵化：队列已空，结束")
                        break
                    }

                    val session = PrefsStore.getDailyIncubateSession()
                    val resumeUser = session?.username?.takeIf { it.isNotBlank() }
                        ?.takeIf { u -> liveQueue.any { it.equals(u, ignoreCase = true) } }
                        ?.takeIf { (session.elapsedSec > 0 || session.publishDone || session.action.isNotBlank()) }
                    val target = resumeUser ?: PrefsStore.peekDailyIncubateUsername() ?: break
                    PrefsStore.noteDailyIncubateAccountsSeen(listOf(target), grandfatherExisting = false)

                    notifyManualAccountUi()
                    PrefsStore.saveLocalStatus("running")
                    updateRuntime(
                        localStatus = "running",
                        lastMessage = "日常孵化：准备 @$target",
                    )
                    val switchedOk = switchToAccountForActivateIfNeeded(target, "日常孵化")
                    if (!switchedOk) {
                        LogCenter.op(alias, "日常孵化：@$target 切号未就绪，已停止")
                        updateRuntime(
                            localStatus = "online",
                            lastMessage = "日常孵化：@$target 切号失败，已停止",
                        )
                        break
                    }

                    val result = runDailyIncubateAccountSession(target, minutes)
                    processed++
                    when (result) {
                        DailyIncubatePhaseResult.COMPLETED -> {
                            if (target.equals(PrefsStore.peekDailyIncubateUsername(), ignoreCase = true)) {
                                PrefsStore.rotateDailyIncubateQueueFrontToBack()
                            }
                            PrefsStore.clearDailyIncubateSessionProgress()
                            val enabled = PrefsStore.getDailyIncubateSession()?.enabled == true
                            PrefsStore.saveDailyIncubateSession(
                                PrefsStore.DailyIncubateSession(
                                    enabled = enabled,
                                    plannedMin = minutes,
                                ),
                            )
                            updateRuntime(
                                localStatus = "running",
                                lastMessage = "日常孵化完成：@$target（第 $processed 次，队列 ${PrefsStore.getDailyIncubateUsernames().size}）",
                            )
                            forceStopTikTokForExclusiveEnqueue("日常孵化单号结束")
                            continue
                        }
                        DailyIncubatePhaseResult.INCOMPLETE -> {
                            updateRuntime(
                                localStatus = "online",
                                lastMessage = "日常孵化已暂停/中断：@$target（已保存进度）",
                            )
                            PrefsStore.saveLocalStatus("online")
                            forceStopTikTokForExclusiveEnqueue("日常孵化中断")
                            break
                        }
                        DailyIncubatePhaseResult.FAILED -> {
                            if (target.equals(PrefsStore.peekDailyIncubateUsername(), ignoreCase = true)) {
                                PrefsStore.rotateDailyIncubateQueueFrontToBack()
                            }
                            PrefsStore.clearDailyIncubateSessionProgress()
                            PrefsStore.saveDailyIncubateSession(
                                PrefsStore.DailyIncubateSession(
                                    enabled = PrefsStore.getDailyIncubateSession()?.enabled == true,
                                    plannedMin = minutes,
                                ),
                            )
                            forceStopTikTokForExclusiveEnqueue("日常孵化单号失败")
                            continue
                        }
                    }
                }

                PrefsStore.saveLocalStatus("online")
                val sessEnd = PrefsStore.getDailyIncubateSession()
                val keepForResume = sessEnd?.enabled == true && (
                    sessEnd.pausedByScreenOff ||
                        (
                            sessEnd.username.isNotBlank() &&
                                (sessEnd.elapsedSec > 0 || sessEnd.publishDone || sessEnd.action.isNotBlank())
                            )
                    )
                if (!keepForResume) {
                    PrefsStore.saveDailyIncubateSession(
                        PrefsStore.DailyIncubateSession(
                            enabled = false,
                            plannedMin = minutes,
                        ),
                    )
                }
                updateRuntime(
                    localStatus = "online",
                    lastMessage = when {
                        sessEnd?.pausedByScreenOff == true -> "日常孵化已熄屏暂停（已保存进度）"
                        keepForResume -> "日常孵化已中断（已保存进度，可续跑）"
                        else -> "日常孵化结束：共完成 $processed 次单号"
                    },
                )
                LogCenter.op(alias, "日常孵化结束：共 $processed 次单号")
                if (detectActiveManualTaskKind() == null ||
                    detectActiveManualTaskKind() == ManualTaskKind.DAILY_INCUBATE
                ) {
                    forceStopTikTokForExclusiveEnqueue("日常孵化结束")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    val pause = dailyIncubatePauseReason
                    if (!pause.isNullOrBlank()) {
                        updateRuntime(localStatus = "online", lastMessage = "日常孵化已暂停（已保存进度）")
                        LogCenter.op(alias, "日常孵化：已暂停 · $pause（已处理 $processed）")
                    } else {
                        updateRuntime(localStatus = "online", lastMessage = "日常孵化已取消")
                        LogCenter.op(alias, "日常孵化：已取消（已处理 $processed）")
                        PrefsStore.saveDailyIncubateSession(
                            PrefsStore.getDailyIncubateSession()?.copy(enabled = false, pausedByScreenOff = false),
                        )
                    }
                    throw t
                }
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "日常孵化失败：${t.message}")
                LogCenter.op(alias, "日常孵化失败：${t.message}")
            } finally {
                activateNextOwnsSwitch = false
                if (dailyIncubateJob === currentCoroutineContext()[Job]) {
                    dailyIncubateJob = null
                }
                notifyManualAccountUi()
            }
        }
    }

    fun manualPauseDailyIncubateForSleep(reason: String = "熄屏暂停"): Boolean {
        if (!ManualDeviceControl.ENABLED) return false
        val job = dailyIncubateJob?.takeIf { it.isActive } ?: run {
            val sess = PrefsStore.getDailyIncubateSession()
            if (sess?.enabled == true) {
                PrefsStore.saveDailyIncubateSession(sess.copy(pausedByScreenOff = true))
            }
            return false
        }
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        dailyIncubatePauseReason = reason
        val sess = PrefsStore.getDailyIncubateSession()
        if (sess != null) {
            PrefsStore.saveDailyIncubateSession(
                sess.copy(enabled = true, pausedByScreenOff = true),
            )
        }
        job.cancel(CancellationException(reason))
        runCatching {
            kotlinx.coroutines.runBlocking {
                runCatching { withTimeout(8_000L) { job.join() } }
            }
        }
        if (dailyIncubateJob === job) dailyIncubateJob = null
        pullLoopJob?.cancel()
        pullLoopJob = null
        forYouSwipeJob?.cancel()
        forYouSwipeJob = null
        if (isMutualSwitchActive()) {
            clearMutualClickStateForInterrupt(reason)
        }
        dailyIncubatePauseReason = null
        // 暂停/取消日常孵化时一并清遗留独占，避免后续涨粉永久空转
        PrefsStore.setAutoSwitchExclusivePreempt(false)
        PrefsStore.setTransientPausedForDestructiveOp(false)
        PrefsStore.saveLocalStatus("online")
        val remain = PrefsStore.getDailyIncubateSession()?.remainingSec() ?: 0
        val user = PrefsStore.getDailyIncubateSession()?.username.orEmpty()
        val msg = buildString {
            append("日常孵化已暂停")
            if (user.isNotBlank()) {
                append("：@$user")
                if (remain > 0) append(" 剩余约 ${remain / 60} 分")
            }
            append(" · $reason")
        }
        updateRuntime(localStatus = "online", lastMessage = msg)
        if (alias.isNotBlank()) LogCenter.op(alias, msg)
        notifyManualAccountUi()
        return true
    }

    fun manualResumeDailyIncubateAfterScreenOn() {
        if (!ManualDeviceControl.ENABLED) return
        if (dailyIncubateJob?.isActive == true) return
        val sess = PrefsStore.getDailyIncubateSession() ?: return
        if (!sess.enabled || !sess.pausedByScreenOff) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        LogCenter.op(alias.ifBlank { null }, "日常孵化：亮屏，自动继续")
        PrefsStore.saveDailyIncubateSession(sess.copy(pausedByScreenOff = false))
        manualStartDailyIncubate(sess.plannedMin, resumeFromPause = true)
    }

    fun manualStopDailyIncubate(reason: String = "手动停止日常孵化") {
        if (!ManualDeviceControl.ENABLED) return
        PrefsStore.saveDailyIncubateSession(
            PrefsStore.getDailyIncubateSession()?.copy(enabled = false, pausedByScreenOff = false)
                ?: PrefsStore.DailyIncubateSession(enabled = false),
        )
        cancelDailyIncubateIfRunning(reason)
        PrefsStore.clearDailyIncubateSessionProgress()
        PrefsStore.saveDailyIncubateSession(PrefsStore.DailyIncubateSession(enabled = false))
        updateRuntime(localStatus = "online", lastMessage = reason)
        LogCenter.op(PrefsStore.getAlias(), reason)
        notifyManualAccountUi()
    }

    /** 正常：涨粉50 / 预滑30 / 互点20；过渡期：涨粉50:预滑30 归一化。 */
    private fun pickDailyIncubateAction(inTransition: Boolean): DailyIncubateAction {
        if (inTransition) {
            // 50:30 → 62.5% / 37.5%
            return if (Random.nextDouble() < 50.0 / 80.0) {
                DailyIncubateAction.GROWTH
            } else {
                DailyIncubateAction.FOR_YOU
            }
        }
        val r = Random.nextDouble()
        return when {
            r < 0.50 -> DailyIncubateAction.GROWTH
            r < 0.80 -> DailyIncubateAction.FOR_YOU
            else -> DailyIncubateAction.MUTUAL
        }
    }

    private suspend fun awaitManualMutualClick(): Boolean {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        suppressManualAutoGrowthUntilElapsed = SystemClock.elapsedRealtime() + 24L * 60L * 60L * 1000L
        activateNextOwnsSwitch = true
        PrefsStore.setMutualCommentOnlySession(false)
        PrefsStore.setMutualFollowOnlySession(false)
        PrefsStore.setMutualClickRequested(true)
        PrefsStore.setMutualClickPending(true)
        if (!isMutualSwitchActive()) {
            triggerMutualSwitchNow(alias)
        }
        var waitStart = 0
        while (mutualSwitchJob == null && waitStart < 40) {
            delay(250)
            waitStart++
        }
        mutualSwitchJob?.join()
        currentCoroutineContext().ensureActive()
        val stillPending = PrefsStore.isMutualClickRequested() || PrefsStore.isMutualClickPending()
        LogCenter.op(
            alias,
            "日常孵化互点等待结束：pending=$stillPending active=${isMutualSwitchActive()}",
        )
        return !stillPending && !PrefsStore.isHardStopRequested()
    }

    private suspend fun runDailyIncubateAccountSession(
        target: String,
        minutes: Int,
    ): DailyIncubatePhaseResult {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val plannedSec = minutes * 60
        var session = PrefsStore.getDailyIncubateSession()?.takeIf {
            it.username.equals(target, ignoreCase = true)
        } ?: PrefsStore.DailyIncubateSession(
            enabled = true,
            username = target,
            plannedMin = minutes,
            inTransition = PrefsStore.isDailyIncubateInTransition(target),
        )
        // 首次见到已写入；以当前过渡状态为准
        val inTransition = PrefsStore.isDailyIncubateInTransition(target)
        session = session.copy(
            enabled = true,
            username = target,
            plannedMin = minutes,
            inTransition = inTransition,
            pausedByScreenOff = false,
        )
        PrefsStore.saveDailyIncubateSession(session)
        PrefsStore.setLogContextUsername(target)

        var action = DailyIncubateAction.fromKey(session.action)
        if (action == null) {
            action = pickDailyIncubateAction(inTransition)
            session = session.copy(action = action.key)
            PrefsStore.saveDailyIncubateSession(session)
        } else if (inTransition && action == DailyIncubateAction.MUTUAL) {
            // 过渡期禁止互点：若旧进度误存互点则重抽
            action = pickDailyIncubateAction(inTransition = true)
            session = session.copy(action = action.key)
            PrefsStore.saveDailyIncubateSession(session)
        }

        val transitionOrdinal = if (inTransition) {
            PrefsStore.dailyIncubateTransitionSessionOrdinal(target)
        } else {
            null
        }
        val transitionPubCount = if (inTransition) {
            PrefsStore.dailyIncubateTransitionPublishCount(target)
        } else {
            null
        }
        LogCenter.op(
            alias,
            "日常孵化：@$target → ${action.label}" +
                (if (inTransition) {
                    val ord = transitionOrdinal ?: 0
                    val pubHint = when (transitionPubCount) {
                        null, 0 -> "跳过发作品"
                        else -> "本会话发 ${transitionPubCount} 条"
                    }
                    "（新号过渡 第${ord}/${PrefsStore.DAILY_INCUBATE_TRANSITION_NEED} 次，$pubHint、禁止互点）"
                } else {
                    ""
                }) +
                "，已计时 ${session.elapsedSec}s / ${plannedSec}s",
        )

        val wallStartedAt = SystemClock.elapsedRealtime()
        var segmentStartedAt = wallStartedAt
        var interrupted = false
        try {
            // 1) 发作品：毕业后按手动条数；过渡期仅第 3 次发 1 条；续跑已完成则跳过
            val pubCount = when {
                session.publishDone -> 0
                inTransition -> (transitionPubCount ?: 0).coerceAtLeast(0)
                else -> PrefsStore.getManualPublishCount()
            }
            if (pubCount > 0) {
                updateRuntime(
                    localStatus = "running",
                    lastMessage = "日常孵化：@$target · 发作品（${pubCount}条）",
                )
                segmentStartedAt = SystemClock.elapsedRealtime()
                // 过渡期强制条数勿回写手动偏好；毕业后按当前偏好发则可同步
                val ok = awaitManualPublishPost(
                    pubCount,
                    updatePreferredCount = !inTransition,
                )
                LogCenter.op(
                    alias,
                    "日常孵化：@$target 发作品${if (ok) "完成" else "未完全成功"}" +
                        "（目标${pubCount}条${if (inTransition) "·过渡期第${transitionOrdinal}次" else ""}），继续后续动作",
                )
                val publishElapsed = ((SystemClock.elapsedRealtime() - segmentStartedAt) / 1000).toInt()
                    .coerceAtLeast(0)
                session = session.copy(
                    publishDone = true,
                    elapsedSec = (session.elapsedSec + publishElapsed).coerceAtMost(plannedSec),
                )
                PrefsStore.saveDailyIncubateSession(session)
            } else if (inTransition && !session.publishDone) {
                // 过渡期第 1–2 次：明确记为已处理发作品阶段，避免续跑误判
                session = session.copy(publishDone = true)
                PrefsStore.saveDailyIncubateSession(session)
            }

            currentCoroutineContext().ensureActive()
            if (PrefsStore.isHardStopRequested()) {
                interrupted = true
            } else {
                segmentStartedAt = SystemClock.elapsedRealtime()
                when (action) {
                    DailyIncubateAction.MUTUAL -> {
                        updateRuntime(
                            localStatus = "running",
                            lastMessage = "日常孵化：@$target · 引流互点（达云端上限即完成）",
                        )
                        fetchConfig(configQueryRunMode = ServerRunMode.MUTUAL, logToUser = false)
                        awaitManualMutualClick()
                        val mutualElapsed = ((SystemClock.elapsedRealtime() - segmentStartedAt) / 1000)
                            .toInt().coerceAtLeast(0)
                        session = session.copy(
                            elapsedSec = (session.elapsedSec + mutualElapsed).coerceAtLeast(0),
                        )
                        PrefsStore.saveDailyIncubateSession(session)
                    }
                    DailyIncubateAction.FOR_YOU, DailyIncubateAction.GROWTH -> {
                        val remainSec = (plannedSec - session.elapsedSec).coerceAtLeast(0)
                        if (remainSec <= 2) {
                            // 发作品已耗尽分配时长，视为本号完成
                        } else {
                            val modeLabel = action.label
                            updateRuntime(
                                localStatus = "running",
                                lastMessage = "日常孵化：@$target · $modeLabel · 剩余约 ${remainSec / 60} 分",
                            )
                            fetchConfig(configQueryRunMode = ServerRunMode.GROWTH, logToUser = false)
                            if (action == DailyIncubateAction.FOR_YOU) {
                                ExecutionScope.enter()
                                forYouSwipeJob = currentCoroutineContext()[Job]
                                try {
                                    executor.runForYouSwipe(remainSec, _config.value)
                                    currentCoroutineContext().ensureActive()
                                } catch (t: Throwable) {
                                    if (t is CancellationException) throw t
                                    LogCenter.op(alias, "日常孵化 For You 异常：${t.message}")
                                } finally {
                                    if (forYouSwipeJob === currentCoroutineContext()[Job]) {
                                        forYouSwipeJob = null
                                    }
                                    ExecutionScope.exit()
                                }
                            } else {
                                ensureManualActivateGrowthPullRunning(
                                    "日常孵化：@$target · $modeLabel",
                                )
                                if (pullLoopJob?.isActive != true) {
                                    error("日常孵化涨粉未能启动拉取循环")
                                }
                                val endAt = segmentStartedAt + remainSec * 1000L
                                while (SystemClock.elapsedRealtime() < endAt) {
                                    if (PrefsStore.isHardStopRequested()) {
                                        interrupted = true
                                        break
                                    }
                                    currentCoroutineContext().ensureActive()
                                    delay(5_000)
                                }
                                pullLoopJob?.cancel()
                                pullLoopJob = null
                            }
                            val actionElapsed = ((SystemClock.elapsedRealtime() - segmentStartedAt) / 1000)
                                .toInt().coerceIn(0, remainSec)
                            session = session.copy(
                                elapsedSec = (session.elapsedSec + actionElapsed).coerceAtMost(plannedSec),
                            )
                            PrefsStore.saveDailyIncubateSession(session)
                            creditActivateMinutes(target, actionElapsed, modeLabel)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
        } catch (t: Throwable) {
            if (t is CancellationException) {
                withContext(NonCancellable) {
                    pullLoopJob?.cancel()
                    pullLoopJob = null
                    val partial = ((SystemClock.elapsedRealtime() - segmentStartedAt) / 1000)
                        .toInt().coerceAtLeast(0)
                    val base = PrefsStore.getDailyIncubateSession()?.elapsedSec
                        ?.coerceAtLeast(session.elapsedSec)
                        ?: session.elapsedSec
                    // 若当前段尚未写入 session，把 partial 补上（避免与已写入重复：仅当 base==session 起点段）
                    val alreadyCommitted = PrefsStore.getDailyIncubateSession()?.elapsedSec ?: session.elapsedSec
                    val elapsed = if (alreadyCommitted > session.elapsedSec) {
                        alreadyCommitted
                    } else {
                        (session.elapsedSec + partial).coerceAtMost(plannedSec)
                    }
                    PrefsStore.saveDailyIncubateSession(
                        session.copy(
                            enabled = true,
                            pausedByScreenOff = dailyIncubatePauseReason != null,
                            username = target,
                            action = action.key,
                            plannedMin = minutes,
                            inTransition = inTransition,
                            elapsedSec = elapsed,
                        ),
                    )
                    LogCenter.op(
                        alias,
                        "日常孵化：@$target 中断保存进度 elapsed=${elapsed}s action=${action.key}",
                    )
                }
                throw t
            }
            LogCenter.op(alias, "日常孵化：@$target 失败：${t.message}")
            return DailyIncubatePhaseResult.FAILED
        }

        if (interrupted || PrefsStore.isHardStopRequested()) {
            PrefsStore.saveDailyIncubateSession(
                session.copy(enabled = true, pausedByScreenOff = false),
            )
            return DailyIncubatePhaseResult.INCOMPLETE
        }

        // 完成判定
        val done = when (action) {
            DailyIncubateAction.MUTUAL -> true
            DailyIncubateAction.GROWTH, DailyIncubateAction.FOR_YOU ->
                session.elapsedSec >= plannedSec - 2
        }
        if (!done) {
            PrefsStore.saveDailyIncubateSession(session.copy(enabled = true))
            return DailyIncubatePhaseResult.INCOMPLETE
        }

        if (inTransition) {
            val n = PrefsStore.incrementDailyIncubateTransitionCount(target)
            LogCenter.op(
                alias,
                "日常孵化：@$target 过渡期完整会话 +1 → $n/${PrefsStore.DAILY_INCUBATE_TRANSITION_NEED}" +
                    if (n >= PrefsStore.DAILY_INCUBATE_TRANSITION_NEED) {
                        "（过渡期完成，下次按手动条数发作品并可互点）"
                    } else if (n + 1 == PrefsStore.DAILY_INCUBATE_TRANSITION_PUBLISH_SESSION) {
                        "（下次为第 ${PrefsStore.DAILY_INCUBATE_TRANSITION_PUBLISH_SESSION} 次，将发 " +
                            "${PrefsStore.DAILY_INCUBATE_TRANSITION_PUBLISH_COUNT} 条）"
                    } else {
                        ""
                    },
            )
        }
        LogCenter.op(alias, "日常孵化：@$target 本号养号任务完成（${action.label}）")
        return DailyIncubatePhaseResult.COMPLETED
    }

    /**
     * 本机绑定设备上「未在待活跃队列」的账号（默认排除云端 banned）。
     * 账号来源为已缓存的设备分配列表；顺序与列表一致。
     */
    fun listBoundDeviceNonPendingActivateUsernames(
        excludeBanned: Boolean = true,
    ): List<String> {
        val pending = PrefsStore.getPendingActivateUsernames()
            .map { it.trim().removePrefix("@").lowercase(java.util.Locale.US) }
            .filter { it.isNotEmpty() }
            .toSet()
        return ManualDeviceControl.getCachedAccounts()
            .asSequence()
            .filter { !excludeBanned || !ManualDeviceControl.accountLooksCredBlocked(it) }
            .map { it.username.trim().removePrefix("@") }
            .filter { it.isNotEmpty() }
            .filter { it.lowercase(java.util.Locale.US) !in pending }
            .distinctBy { it.lowercase(java.util.Locale.US) }
            .toList()
    }

    /**
     * 自动拉取本机绑定设备云端账号后，对全部非待活跃账号批量发作品。
     * @return 实际入队账号数；失败时带异常信息。
     */
    suspend fun manualStartBatchPublishNonPendingActivate(
        publishCount: Int = PrefsStore.getManualPublishCount(),
        afterActiveMin: Int = PrefsStore.getManualBatchPublishAfterMin(),
        excludeBanned: Boolean = true,
    ): Result<Int> {
        if (!ManualDeviceControl.ENABLED) {
            return Result.failure(IllegalStateException("非手动模式"))
        }
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isBlank() || PrefsStore.getDeviceId()?.trim().isNullOrBlank()) {
            return Result.failure(IllegalStateException("设备未绑定"))
        }
        if (isBatchAccountSessionBusy()) {
            return Result.failure(IllegalStateException("多账号会话进行中，请稍候"))
        }
        if (isManualSwitchBusy()) {
            return Result.failure(IllegalStateException("切号进行中，请稍候"))
        }
        updateRuntime(localStatus = "online", lastMessage = "非待活跃批量发：正在拉取云端账号…")
        LogCenter.op(alias, "非待活跃批量发作品：自动拉取本机设备账号列表")
        val refresh = manualRefreshAssignedAccounts()
        val roster = refresh.getOrElse { err ->
            updateRuntime(
                localStatus = "online",
                lastMessage = "非待活跃批量发：拉取账号失败：${err.message}",
            )
            return Result.failure(err)
        }
        val targets = listBoundDeviceNonPendingActivateUsernames(excludeBanned = excludeBanned)
        if (targets.isEmpty()) {
            val pending = PrefsStore.getPendingActivateUsernames().size
            val msg =
                "非待活跃批量发：无可用账号（云端 ${roster.size} 个，待活跃 $pending 个，其余为空或已封禁）"
            LogCenter.op(alias, msg)
            updateRuntime(localStatus = "online", lastMessage = msg)
            return Result.failure(IllegalStateException(msg))
        }
        LogCenter.op(
            alias,
            "非待活跃批量发作品：本机设备共 ${targets.size} 个账号（已排除待活跃队列，云端 ${roster.size}）",
        )
        manualStartBatchPublish(targets, publishCount, afterActiveMin)
        return Result.success(targets.size)
    }

    /**
     * 批量发作品：对指定账号依次切号 → 发作品 → 发完后随机 For You/涨粉一段时间。
     * 切号方案遵循用户选择的备份还原 / 登录切号。
     */
    fun manualStartBatchPublish(
        usernames: List<String>,
        publishCount: Int = PrefsStore.getManualPublishCount(),
        afterActiveMin: Int = PrefsStore.getManualBatchPublishAfterMin(),
    ) {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "批量发作品：设备未绑定")
            return
        }
        if (isBatchAccountSessionBusy()) {
            LogCenter.op(alias, "批量发作品：多账号会话进行中，忽略重复点击")
            return
        }
        if (isManualSwitchBusy()) {
            LogCenter.op(alias, "批量发作品：切号进行中，请稍候")
            return
        }
        val targets = usernames.map { it.trim().removePrefix("@") }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(java.util.Locale.US) }
        if (targets.isEmpty()) {
            LogCenter.op(alias, "批量发作品：请先指定账号")
            return
        }
        val count = publishCount.coerceIn(
            PrefsStore.MIN_MANUAL_PUBLISH_COUNT,
            PrefsStore.MAX_MANUAL_PUBLISH_COUNT,
        )
        val afterMin = afterActiveMin.coerceIn(
            PrefsStore.MIN_MANUAL_BATCH_PUBLISH_AFTER_MIN,
            PrefsStore.MAX_MANUAL_BATCH_PUBLISH_AFTER_MIN,
        )
        PrefsStore.setManualPublishCount(count)
        PrefsStore.setManualBatchPublishAfterMin(afterMin)
        manualDispatchJob?.cancel()
        batchPublishJob = scope.launch {
            var processed = 0
            try {
                if (!prepareManualTaskTakeover(ManualTaskKind.BATCH_PUBLISH, "批量发作品")) {
                    return@launch
                }
                PrefsStore.setTransientPausedForDestructiveOp(false)
                PrefsStore.setHardStopRequested(false)
                manuallyPaused = false
                if (!started.get()) started.set(true)
                activateNextOwnsSwitch = true
                val scheme = PrefsStore.getManualCutSwitchScheme()
                LogCenter.op(
                    alias,
                    "批量发作品：开始 ${targets.size} 个账号（每号 ${count} 条，发后活跃 ${afterMin} 分钟，切号=$scheme）",
                )
                PrefsStore.saveLocalStatus("running")

                for ((index, target) in targets.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (PrefsStore.isHardStopRequested()) break
                    updateRuntime(
                        localStatus = "running",
                        lastMessage = "批量发作品（${index + 1}/${targets.size}）：切号 → @$target",
                    )
                    val switchedOk = switchToAccountForBatchSession(target)
                    if (!switchedOk) {
                        LogCenter.op(alias, "批量发作品：@$target 切号未就绪，跳过")
                        updateRuntime(
                            localStatus = "running",
                            lastMessage = "批量发作品：@$target 切号失败，跳过",
                        )
                        continue
                    }
                    updateRuntime(
                        localStatus = "running",
                        lastMessage = "批量发作品（${index + 1}/${targets.size}）：@$target 发作品中",
                    )
                    val publishOk = awaitManualPublishPost(count)
                    if (!publishOk) {
                        LogCenter.op(alias, "批量发作品：@$target 发作品未完全成功，仍继续发后活跃")
                    }
                    val phase = runRandomActivatePhase(
                        target,
                        afterMin,
                        "批量发作品·发后活跃",
                        splitOnlineStint = false,
                    )
                    processed++
                    updateRuntime(
                        localStatus = "running",
                        lastMessage = "批量发作品完成：@$target · 发后活跃" +
                            (if (phase == ActivatePhaseResult.PLANNED_DONE) "完成" else "未完成") +
                            "（$processed/${targets.size}）",
                    )
                    forceStopTikTokForExclusiveEnqueue("批量发作品单号结束")
                }

                PrefsStore.saveLocalStatus("online")
                updateRuntime(
                    localStatus = "online",
                    lastMessage = "批量发作品结束：已处理 $processed/${targets.size} 个账号",
                )
                LogCenter.op(alias, "批量发作品结束：已处理 $processed/${targets.size}")
                if (detectActiveManualTaskKind() == null ||
                    detectActiveManualTaskKind() == ManualTaskKind.BATCH_PUBLISH
                ) {
                    forceStopTikTokForExclusiveEnqueue("批量发作品结束")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    updateRuntime(localStatus = "online", lastMessage = "批量发作品已取消")
                    LogCenter.op(alias, "批量发作品：已取消（已处理 $processed）")
                    throw t
                }
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "批量发作品失败：${t.message}")
                LogCenter.op(alias, "批量发作品失败：${t.message}")
            } finally {
                activateNextOwnsSwitch = false
                suppressManualAutoGrowthUntilElapsed = 0L
                if (batchPublishJob === currentCoroutineContext()[Job]) {
                    batchPublishJob = null
                }
                notifyManualAccountUi()
            }
        }
    }

    /** 手动切号成功：还原完成 / 自动登录完成 / 登录状态检测通过 → 就绪并刷新 UI。 */
    private fun markManualAccountReady(username: String, via: String) {
        val u = username.trim().removePrefix("@")
        if (u.isBlank()) return
        PrefsStore.setManualFirstLoginDone(u, true)
        PrefsStore.setManualActiveUsername(u)
        PrefsStore.setManualSelectedUsername(u)
        PrefsStore.setLogContextUsername(u)
        notifyManualAccountUi()
        LogCenter.op(PrefsStore.getAlias(), "手动账号已就绪：@$u（$via）")
    }

    /**
     * 用户已在设备上手动切到目标号：告知程序标记该账号已就绪（跳过备份还原/自动登录链），
     * 之后可直接涨粉/引流/发作品等。
     */
    fun manualConfirmSwitchDone() {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (isManualSwitchBusy()) {
            LogCenter.op(alias.ifBlank { null }, "手动切号完毕：切号进行中，请稍候")
            return
        }
        val user = ManualDeviceControl.selectedManualUsername()
            .ifBlank { PrefsStore.getManualSelectedUsername().orEmpty().trim().removePrefix("@") }
        if (user.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "手动切号完毕：请先选择账号")
            return
        }
        PrefsStore.setTransientPausedForDestructiveOp(false)
        PrefsStore.setHardStopRequested(false)
        manuallyPaused = false
        markManualAccountReady(user, "用户确认手动切号完毕")
        PrefsStore.saveLocalStatus("online")
        updateRuntime(localStatus = "online", lastMessage = "手动切号完毕：@$user 已就绪")
        LogCenter.op(alias.ifBlank { null }, "手动切号完毕：@$user 已标记就绪，可执行涨粉/引流等任务")
    }

    /**
     * 手动下拉切号入口：与 [manualSwitchAccount] 相同，默认先备份本机当前号。
     * 若目标与本机当前已相同且已就绪则直接对齐；同号未就绪则继续走还原/自动登录补齐。
     */
    fun manualSwitchAccountFromSpinner(targetUsername: String) {
        val target = targetUsername.trim().removePrefix("@")
        if (target.isBlank()) return
        val active = ManualDeviceControl.activeManualUsername()
        if (active.isNotBlank() && active.equals(target, ignoreCase = true)) {
            PrefsStore.setManualSelectedUsername(target)
            PrefsStore.setManualActiveUsername(target)
            PrefsStore.setLogContextUsername(target)
            if (PrefsStore.isManualFirstLoginDone(target)) {
                updateRuntime(lastMessage = "本机已是 @$target，无需切号")
                notifyManualAccountUi()
                return
            }
            // 同号未就绪：不再早退，走还原/自动登录把就绪门闩补上
            LogCenter.op(PrefsStore.getAlias(), "手动切号：本机已是 @$target 但未就绪，补跑还原/自动登录")
            manualSwitchAccount(target, backupCurrentFirst = false)
            return
        }
        manualSwitchAccount(target, backupCurrentFirst = true)
    }

    fun manualQuerySwitchStatus(username: String): ManualDeviceControl.SwitchAccountStatus {
        val u = username.trim().removePrefix("@")
        val alias = PrefsStore.getAlias().orEmpty()
        val deviceId = PrefsStore.getDeviceId().orEmpty()
        val cached = ManualDeviceControl.findCachedAccount(u)
        val backups = if (u.isNotBlank() && alias.isNotBlank() && deviceId.isNotBlank() && ::appContext.isInitialized) {
            val strict = UnifiedBackupPaths.listUsernameBackupArchives(appContext, u, alias, deviceId)
            if (strict.isNotEmpty()) strict
            else UnifiedBackupPaths.listLegacyBackupsForAccount(appContext, u, alias)
        } else {
            emptyList()
        }
        val firstDone = u.isNotBlank() && PrefsStore.isManualFirstLoginDone(u)
        val active = ManualDeviceControl.activeManualUsername()
        val msg = buildString {
            append(if (firstDone) "已就绪（还原/登录/状态检测完成）" else "未就绪（请先切号）")
            append(" · ")
            if (backups.isNotEmpty()) {
                append("本机备份：${backups.first().name}")
            } else {
                append("本机无备份包")
            }
            if (active.isNotBlank()) {
                append(" · 本机当前：@$active")
            }
        }
        return ManualDeviceControl.SwitchAccountStatus(
            username = u,
            firstLoginDone = firstDone,
            localBackupExists = backups.isNotEmpty(),
            localBackupName = backups.firstOrNull()?.name,
            cloudIsLogin = cached?.cloudIsLogin,
            message = msg,
        )
    }

    fun manualStartGrowth(reason: String = "手动点击涨粉") {
        if (!ManualDeviceControl.ENABLED) return
        cancelActivateNextIfRunning("手动涨粉")
        cancelBatchPublishIfRunning("手动涨粉")
        cancelAutoActiveIfRunning("手动涨粉")
        cancelDailyIncubateIfRunning("手动涨粉")
        val user = ManualDeviceControl.selectedManualUsername()
        if (user.isBlank()) {
            LogCenter.op(PrefsStore.getAlias(), "手动涨粉：请先选择账号")
            return
        }
        if (!PrefsStore.isManualFirstLoginDone(user)) {
            LogCenter.op(PrefsStore.getAlias(), "手动涨粉：请先切号完成（有备份还原 / 无备份自动登录）")
            return
        }
        // 必须先判重再 cancel：否则会掐死旧任务又因「重复」不启动新任务
        if (detectActiveManualTaskKind() == ManualTaskKind.GROWTH) {
            LogCenter.op(PrefsStore.getAlias(), "手动任务：涨粉已在进行中，忽略重复点击")
            return
        }
        manualDispatchJob?.cancel()
        manualDispatchJob = scope.launch {
            if (!prepareManualTaskTakeover(ManualTaskKind.GROWTH, reason)) return@launch
            PrefsStore.setTransientPausedForDestructiveOp(false)
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            if (!started.get()) started.set(true)
            PrefsStore.setLogContextUsername(user)
            growthModeLoginSafeguardPending = true
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", hardStopRequested = false, lastMessage = reason)
            fetchConfig(configQueryRunMode = ServerRunMode.GROWTH, logToUser = false)
            startPullLoop()
            LogCenter.op(PrefsStore.getAlias(), "手动模式：开始涨粉（@$user）")
        }
    }

    fun manualStopGrowth(reason: String = "手动停止涨粉") {
        if (!ManualDeviceControl.ENABLED) return
        cancelActivateNextIfRunning("手动停止涨粉")
        cancelBatchPublishIfRunning("手动停止涨粉")
        cancelAutoActiveIfRunning("手动停止涨粉")
        cancelDailyIncubateIfRunning("手动停止涨粉")
        scope.launch {
            // 停止涨粉：同样尽快打断当前 TikTok 步骤，但不启动新任务
            PrefsStore.setAutoSwitchExclusivePreempt(true)
            pullLoopJob?.cancel()
            pullLoopJob = null
            forYouSwipeJob?.cancel()
            forYouSwipeJob = null
            pendingUsernames.clear()
            forceStopTikTokForExclusiveEnqueue("手动停止涨粉")
            val deadline = SystemClock.elapsedRealtime() + 2_000L
            while (ExecutionScope.isActive() && SystemClock.elapsedRealtime() < deadline) {
                delay(40)
            }
            if (ExecutionScope.isActive()) {
                ExecutionScope.forceClearIfStuck("手动停止涨粉")
            }
            PrefsStore.setAutoSwitchExclusivePreempt(false)
            PrefsStore.saveLocalStatus("online")
            updateRuntime(localStatus = "online", lastMessage = reason)
            LogCenter.op(PrefsStore.getAlias(), "手动模式：$reason")
        }
    }

    /** 手动 For You 预滑：按指定秒数刷视频，点赞/收藏/转发与涨粉共用概率与额度。 */
    fun manualStartForYouSwipe(totalSec: Int) {
        if (!ManualDeviceControl.ENABLED) return
        cancelActivateNextIfRunning("手动 For You 预滑")
        cancelBatchPublishIfRunning("手动 For You 预滑")
        cancelAutoActiveIfRunning("手动 For You 预滑")
        cancelDailyIncubateIfRunning("手动 For You 预滑")
        val user = ManualDeviceControl.selectedManualUsername()
        if (user.isBlank()) {
            LogCenter.op(PrefsStore.getAlias(), "手动 For You 预滑：请先选择账号")
            return
        }
        if (!PrefsStore.isManualFirstLoginDone(user)) {
            LogCenter.op(PrefsStore.getAlias(), "手动 For You 预滑：请先切号完成（有备份还原 / 无备份自动登录）")
            return
        }
        val sec = totalSec.coerceIn(
            PrefsStore.MIN_MANUAL_FOR_YOU_SWIPE_SEC,
            PrefsStore.MAX_MANUAL_FOR_YOU_SWIPE_SEC,
        )
        PrefsStore.setManualForYouSwipeSec(sec)
        if (detectActiveManualTaskKind() == ManualTaskKind.FOR_YOU_SWIPE) {
            LogCenter.op(PrefsStore.getAlias(), "手动任务：For You预滑已在进行中，忽略重复点击")
            return
        }
        manualDispatchJob?.cancel()
        manualDispatchJob = scope.launch {
            if (!prepareManualTaskTakeover(ManualTaskKind.FOR_YOU_SWIPE, "手动 For You 预滑")) return@launch
            PrefsStore.setTransientPausedForDestructiveOp(false)
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            if (!started.get()) started.set(true)
            PrefsStore.setLogContextUsername(user)
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", hardStopRequested = false, lastMessage = "手动 For You 预滑 ${sec}s")
            fetchConfig(configQueryRunMode = ServerRunMode.GROWTH, logToUser = false)
            forYouSwipeJob = currentCoroutineContext()[Job]
            ExecutionScope.enter()
            val result = try {
                LogCenter.op(PrefsStore.getAlias(), "手动模式：开始 For You 预滑（@$user · ${sec}s）")
                executor.runForYouSwipe(sec, _config.value)
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    "For You 预滑已取消"
                } else {
                    LogCenter.op(PrefsStore.getAlias(), "手动 For You 预滑异常：${t.message}")
                    "For You 预滑失败：${t.message}"
                }
            } finally {
                ExecutionScope.exit()
                if (forYouSwipeJob === currentCoroutineContext()[Job]) {
                    forYouSwipeJob = null
                }
            }
            PrefsStore.saveLocalStatus("online")
            updateRuntime(localStatus = "online", lastMessage = result)
            LogCenter.op(PrefsStore.getAlias(), "手动模式：$result")
            // 独立预滑任务结束：若无其它手动任务接管，关闭 TikTok 释放前台
            if (detectActiveManualTaskKind() == null) {
                forceStopTikTokForExclusiveEnqueue("For You 预滑结束")
            }
        }
    }

    fun manualStartMutualClick() {
        enqueueManualTaskOp("mutual_click_start") { alias, deviceId, pkg ->
            PrefsStore.setMutualCommentOnlySession(false)
            PrefsStore.setMutualFollowOnlySession(false)
            PrefsStore.setMutualClickRequested(true)
            PrefsStore.setMutualClickPending(true)
            DeviceOp(
                op_id = ManualDeviceControl.newLocalManualOpId("mutual"),
                op_type = "mutual_click_start",
                payload = DeviceOpPayload(
                    device_id = deviceId,
                    alias = alias,
                    package_name = pkg,
                    account_username = ManualDeviceControl.selectedManualUsername(),
                    source = "manual",
                ),
            )
        }
    }

    fun manualStartPublishPost(publishCount: Int = PrefsStore.getManualPublishCount()) {
        val count = publishCount.coerceIn(
            PrefsStore.MIN_MANUAL_PUBLISH_COUNT,
            PrefsStore.MAX_MANUAL_PUBLISH_COUNT,
        )
        PrefsStore.setManualPublishCount(count)
        beginManualPublishSession()
        enqueueManualTaskOp("publish_post_start") { alias, deviceId, pkg ->
            PrefsStore.setPublishPostPackageName(pkg)
            PrefsStore.setPublishPostTargetCount(count)
            PrefsStore.setPublishPostRequested(true)
            PrefsStore.setPublishPostPending(true)
            DeviceOp(
                op_id = ManualDeviceControl.newLocalManualOpId("publish"),
                op_type = "publish_post_start",
                payload = DeviceOpPayload(
                    device_id = deviceId,
                    alias = alias,
                    package_name = pkg,
                    account_username = ManualDeviceControl.selectedManualUsername(),
                    source = "manual",
                    publish_count = count,
                ),
            )
        }
    }

    /**
     * PC 遥控发作品：启动任务并返回 sessionId；最终结果写入 [lastManualPublishOutcome]，
     * 由 PC 轮询 status 汇总成败。
     */
    fun manualStartPublishPostForRemote(publishCount: Int): String {
        val count = publishCount.coerceIn(
            PrefsStore.MIN_MANUAL_PUBLISH_COUNT,
            PrefsStore.MAX_MANUAL_PUBLISH_COUNT,
        )
        val sessionId = "pub-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}"
        PrefsStore.setManualPublishCount(count)
        beginManualPublishSession(sessionId)
        enqueueManualTaskOp("publish_post_start") { alias, deviceId, pkg ->
            PrefsStore.setPublishPostPackageName(pkg)
            PrefsStore.setPublishPostTargetCount(count)
            PrefsStore.setPublishPostRequested(true)
            PrefsStore.setPublishPostPending(true)
            DeviceOp(
                op_id = ManualDeviceControl.newLocalManualOpId("publish"),
                op_type = "publish_post_start",
                payload = DeviceOpPayload(
                    device_id = deviceId,
                    alias = alias,
                    package_name = pkg,
                    account_username = ManualDeviceControl.selectedManualUsername(),
                    source = "manual",
                    publish_count = count,
                ),
            )
        }
        return sessionId
    }

    fun manualStartEditBasicProfile() {
        enqueueManualTaskOp("tiktok_edit_basic_profile") { alias, deviceId, pkg ->
            DeviceOp(
                op_id = ManualDeviceControl.newLocalManualOpId("profile"),
                op_type = "tiktok_edit_basic_profile",
                payload = DeviceOpPayload(
                    device_id = deviceId,
                    alias = alias,
                    package_name = pkg,
                    username = ManualDeviceControl.selectedManualUsername(),
                    account_username = ManualDeviceControl.selectedManualUsername(),
                    source = "manual",
                ),
            )
        }
    }

    fun manualStartAutoLogin(reason: String = "手动点击自动登录") {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val user = ManualDeviceControl.selectedManualUsername()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "自动登录：设备未绑定")
            return
        }
        if (user.isBlank()) {
            LogCenter.op(alias, "自动登录：请先选择账号")
            return
        }
        if (ManualDeviceControl.findCachedAccount(user) == null) {
            LogCenter.op(alias, "自动登录：本地无 @$user 凭据，请先点「拉取云端最新账号信息」")
            return
        }
        if (detectActiveManualTaskKind() == ManualTaskKind.AUTO_LOGIN) {
            LogCenter.op(alias, "手动任务：自动登录已在进行中，忽略重复点击")
            return
        }
        manualDispatchJob?.cancel()
        manualDispatchJob = scope.launch {
            if (!prepareManualTaskTakeover(ManualTaskKind.AUTO_LOGIN, reason)) return@launch
            PrefsStore.setTransientPausedForDestructiveOp(false)
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            if (!started.get()) started.set(true)
            PrefsStore.setLogContextUsername(user)
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = reason)
            val outcome = ensureAccountLoggedInWithRetry(
                alias = alias,
                deviceId = deviceId,
                reason = reason,
                expectedUsername = user,
                forceRelogin = true,
            )
            if (outcome == AccountLoginRetryOutcome.LoggedIn) {
                markManualAccountReady(user, "手动自动登录")
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "切号完成（自动登录）：@$user 已就绪")
                LogCenter.op(alias, "手动自动登录成功：@$user")
                maybeManualAutoGrowthAfterTask("初次自动登录完成")
            } else {
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "自动登录未完成")
                LogCenter.op(alias, "手动自动登录未完成：@$user outcome=$outcome")
                notifyManualAccountUi()
            }
        }
    }

    /**
     * 手动「备份」：按切号同款规则打包本机当前 TikTok 数据（强停 → 目录大小检测 → tar → 校验），
     * 不 pm clear、不还原。归属账号优先本机 active，其次下拉选中。
     */
    fun manualBackupCurrentAccount(usernameHint: String = "") {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "手动备份：设备未绑定")
            return
        }
        val user = usernameHint.trim().removePrefix("@")
            .ifBlank { ManualDeviceControl.activeManualUsername() }
            .ifBlank { ManualDeviceControl.selectedManualUsername() }
        if (user.isBlank()) {
            LogCenter.op(alias, "手动备份：请先选择账号")
            return
        }
        scope.launch {
            PrefsStore.setTransientPausedForDestructiveOp(false)
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            if (!started.get()) started.set(true)
            pullLoopJob?.cancel()
            pullLoopJob = null
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "手动备份 @$user")
            PrefsStore.setManualSelectedUsername(user)
            PrefsStore.setManualActiveUsername(user)
            PrefsStore.setLogContextUsername(user)
            val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
            LogCenter.op(alias, "手动备份：按规则备份 @$user（仅打包，不清理）")
            handleDeviceOp(
                alias,
                deviceId,
                DeviceOp(
                    op_id = ManualDeviceControl.newLocalManualOpId("backup"),
                    op_type = "tiktok_backup_and_clear",
                    payload = DeviceOpPayload(
                        device_id = deviceId,
                        alias = alias,
                        package_name = pkg,
                        username = user,
                        source = "manual",
                        skip_pm_clear = true,
                    ),
                ),
            )
            PrefsStore.saveLocalStatus("online")
            val status = manualQuerySwitchStatus(user)
            updateRuntime(localStatus = "online", lastMessage = "备份完成：${status.message}")
            LogCenter.op(alias, "手动备份结束：@$user · ${status.message}")
        }
    }

    /**
     * 手动「清理」：强停后对 TikTok 执行 pm clear（不备份、不还原）。
     */
    fun manualClearTikTok(usernameHint: String = "") {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "手动清理：设备未绑定")
            return
        }
        val user = usernameHint.trim().removePrefix("@")
            .ifBlank { ManualDeviceControl.activeManualUsername() }
            .ifBlank { ManualDeviceControl.selectedManualUsername() }
        if (user.isBlank()) {
            LogCenter.op(alias, "手动清理：请先选择账号")
            return
        }
        scope.launch {
            PrefsStore.setTransientPausedForDestructiveOp(false)
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            if (!started.get()) started.set(true)
            pullLoopJob?.cancel()
            pullLoopJob = null
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "手动清理 TikTok @$user")
            PrefsStore.setManualSelectedUsername(user)
            PrefsStore.setLogContextUsername(user)
            val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
            LogCenter.op(alias, "手动清理：强停 + pm clear TikTok（@$user）")
            handleDeviceOp(
                alias,
                deviceId,
                DeviceOp(
                    op_id = ManualDeviceControl.newLocalManualOpId("clear"),
                    op_type = "tiktok_clear",
                    payload = DeviceOpPayload(
                        device_id = deviceId,
                        alias = alias,
                        package_name = pkg,
                        username = user,
                        source = "manual",
                    ),
                ),
            )
            PrefsStore.saveLocalStatus("online")
            val status = manualQuerySwitchStatus(user)
            updateRuntime(localStatus = "online", lastMessage = "清理完成：${status.message}")
            LogCenter.op(
                alias,
                "手动清理结束：@$user · ${status.message}。清理后需重新登录，可点「自动登录」。",
            )
        }
    }

    /**
     * 手动「清理并还原」：对选中账号执行 pm clear 后从本机备份包还原（不备份当前号）。
     * 仅当该账号本机存在有效备份包时有意义。
     */
    fun manualClearAndRestore(usernameHint: String = "") {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "手动清理并还原：设备未绑定")
            return
        }
        val user = usernameHint.trim().removePrefix("@")
            .ifBlank { ManualDeviceControl.selectedManualUsername() }
        if (user.isBlank()) {
            LogCenter.op(alias, "手动清理并还原：请先选择账号")
            return
        }
        scope.launch {
            PrefsStore.setTransientPausedForDestructiveOp(false)
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            if (!started.get()) started.set(true)
            pullLoopJob?.cancel()
            pullLoopJob = null
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "手动清理并还原 @$user")
            PrefsStore.setManualSelectedUsername(user)
            PrefsStore.setLogContextUsername(user)
            val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
            val localBackups = UnifiedBackupPaths.listUsernameBackupArchives(appContext, user, alias, deviceId)
            val targetBackup = localBackups.firstOrNull()
            if (targetBackup == null) {
                LogCenter.op(alias, "手动清理并还原：@$user 本机无备份包，已取消")
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "清理并还原取消：@$user 无本机备份")
                return@launch
            }
            LogCenter.op(alias, "手动清理并还原：清理后还原 @$user ← ${targetBackup.name}")
            handleDeviceOp(
                alias,
                deviceId,
                DeviceOp(
                    op_id = ManualDeviceControl.newLocalManualOpId("clear"),
                    op_type = "tiktok_clear",
                    payload = DeviceOpPayload(
                        device_id = deviceId,
                        alias = alias,
                        package_name = pkg,
                        username = user,
                        source = "manual",
                    ),
                ),
            )
            handleDeviceOp(
                alias,
                deviceId,
                DeviceOp(
                    op_id = ManualDeviceControl.newLocalManualOpId("restore"),
                    op_type = "tiktok_restore",
                    payload = DeviceOpPayload(
                        device_id = deviceId,
                        alias = alias,
                        package_name = pkg,
                        username = user,
                        backup_name = targetBackup.name,
                        backup_path = targetBackup.absolutePath,
                        source = "manual",
                        skip_pm_clear = true,
                    ),
                ),
            )
            PrefsStore.setManualSelectedUsername(user)
            PrefsStore.setManualActiveUsername(user)
            PrefsStore.setLogContextUsername(user)
            PrefsStore.saveLocalStatus("online")
            val status = manualQuerySwitchStatus(user)
            updateRuntime(localStatus = "online", lastMessage = "清理并还原完成：${status.message}")
            LogCenter.op(
                alias,
                "手动清理并还原结束：@$user · ${status.message}。还原后若未登录请点「自动登录」。",
            )
        }
    }

    /**
     * 手动切号：可选先备份本机当前账号；
     * 目标有备份 → 清理+还原；无备份（或还原失败）→ 清理+自动登录。
     * @param backupCurrentFirst 默认 true：完整切号先备份当前号。
     */
    fun manualSwitchAccount(targetUsername: String, backupCurrentFirst: Boolean = true) {
        if (!ManualDeviceControl.ENABLED) return
        if (!activateNextOwnsSwitch) {
            cancelActivateNextIfRunning("手动切号")
            cancelBatchPublishIfRunning("手动切号")
            cancelAutoActiveIfRunning("手动切号")
            cancelDailyIncubateIfRunning("手动切号")
        }
        val target = targetUsername.trim().removePrefix("@")
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "手动切号：设备未绑定")
            return
        }
        if (target.isBlank()) {
            LogCenter.op(alias, "手动切号：请先选择目标账号")
            return
        }
        if (manualSwitchJob?.isActive == true || _manualSwitchBusy.value) {
            LogCenter.op(alias, "手动切号：已有切号进行中，忽略重复请求 → @$target")
            return
        }
        val previousActive = ManualDeviceControl.activeManualUsername()
            .ifBlank { PrefsStore.getLogContextUsername().orEmpty().trim().removePrefix("@") }
        PrefsStore.setManualSelectedUsername(target)
        // 同步置 busy，避免 UI 在协程启动前仍可点「手动切号完毕」
        _manualSwitchBusy.value = true
        manualDispatchJob?.cancel()
        manualSwitchJob = scope.launch {
            var restoredFromBackup = false
            /** 已对 TikTok 执行 clear/restore 后，失败不应再回滚选中到旧号。 */
            var tikTokSwitched = false
            try {
                if (!prepareManualTaskTakeover(ManualTaskKind.SWITCH_ACCOUNT, "手动切号")) {
                    return@launch
                }
                PrefsStore.setTransientPausedForDestructiveOp(false)
                PrefsStore.setHardStopRequested(false)
                manuallyPaused = false
                if (!started.get()) started.set(true)
                PrefsStore.saveLocalStatus("running")
                updateRuntime(localStatus = "running", lastMessage = "手动切号 → @$target")
                val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
                val source = previousActive
                val sameAccount = source.isNotBlank() && source.equals(target, ignoreCase = true)
                val loginCutScheme = PrefsStore.prefersManualLoginCutSwitch()
                val schemeLabel = if (loginCutScheme) "登录切号" else "备份还原"
                LogCenter.op(alias, "手动切号：方案=$schemeLabel → @$target")
                val strictBackups = UnifiedBackupPaths.listUsernameBackupArchives(appContext, target, alias, deviceId)
                val legacyBackups = if (strictBackups.isEmpty()) {
                    UnifiedBackupPaths.listLegacyBackupsForAccount(appContext, target, alias)
                } else {
                    emptyList()
                }
                // 登录切号：忽略本机备份，一律清理后自动登录
                val targetBackup = if (loginCutScheme) {
                    null
                } else {
                    (strictBackups.ifEmpty { legacyBackups }).firstOrNull()
                }

                if (sameAccount) {
                    if (PrefsStore.isManualFirstLoginDone(target)) {
                        LogCenter.op(alias, "手动切号：本机已是 @$target，无需切号")
                        PrefsStore.setManualSelectedUsername(target)
                        PrefsStore.setManualActiveUsername(target)
                        PrefsStore.setLogContextUsername(target)
                        PrefsStore.saveLocalStatus("online")
                        updateRuntime(localStatus = "online", lastMessage = "本机已是 @$target")
                        notifyManualAccountUi()
                        return@launch
                    }
                    LogCenter.op(alias, "手动切号：本机已是 @$target 但未就绪，补齐还原/自动登录")
                }

                // 登录切号跳过源号备份；备份还原方案才在切走前备份当前号
                if (!loginCutScheme && backupCurrentFirst && source.isNotBlank() && !sameAccount) {
                    LogCenter.op(alias, "手动切号：先备份当前账号 @$source")
                    handleDeviceOp(
                        alias,
                        deviceId,
                        DeviceOp(
                            op_id = ManualDeviceControl.newLocalManualOpId("backup"),
                            op_type = "tiktok_backup_and_clear",
                            payload = DeviceOpPayload(
                                device_id = deviceId,
                                alias = alias,
                                package_name = pkg,
                                username = source,
                                source = "manual_switch_pre_backup",
                                skip_pm_clear = true,
                            ),
                        ),
                    )
                    if (!lastDeviceOpOk) {
                        error("备份当前账号 @$source 失败，已中止切号以免丢号")
                    }
                }

                PrefsStore.setManualSelectedUsername(target)
                PrefsStore.setLogContextUsername(target)

                if (targetBackup != null) {
                    // 有备份 → 清理 + 还原；还原失败则回退自动登录
                    LogCenter.op(
                        alias,
                        buildString {
                            append("手动切号：@$target 有备份 ${targetBackup.name}")
                            if (strictBackups.isEmpty()) append("（旧包名兼容）")
                            append(" → 清理后还原")
                        },
                    )
                    handleDeviceOp(
                        alias,
                        deviceId,
                        DeviceOp(
                            op_id = ManualDeviceControl.newLocalManualOpId("clear"),
                            op_type = "tiktok_clear",
                            payload = DeviceOpPayload(
                                device_id = deviceId,
                                alias = alias,
                                package_name = pkg,
                                username = source.ifBlank { target },
                                source = "manual",
                            ),
                        ),
                    )
                    if (!lastDeviceOpOk) {
                        error("清理 TikTok 失败，切号中止")
                    }
                    tikTokSwitched = true
                    PrefsStore.setManualActiveUsername(target)
                    handleDeviceOp(
                        alias,
                        deviceId,
                        DeviceOp(
                            op_id = ManualDeviceControl.newLocalManualOpId("restore"),
                            op_type = "tiktok_restore",
                            payload = DeviceOpPayload(
                                device_id = deviceId,
                                alias = alias,
                                package_name = pkg,
                                username = target,
                                backup_name = targetBackup.name,
                                backup_path = targetBackup.absolutePath,
                                source = "manual",
                                skip_pm_clear = true,
                            ),
                        ),
                    )
                    if (lastDeviceOpOk) {
                        restoredFromBackup = true
                        // 还原完成：再跑登录状态检测；通过即切号成功；需登录则保障链内自动登录
                        updateRuntime(
                            localStatus = "running",
                            lastMessage = "手动切号：@$target 还原完成，登录状态检测中",
                        )
                        LogCenter.op(alias, "手动切号：@$target 还原成功，开始登录状态检测")
                        val safeguardOk = launchTikTokWithLoginSafeguardWithRetry(
                            alias = alias,
                            deviceId = deviceId,
                            tag = "手动切号还原后",
                            forceStopBeforeLaunch = false,
                            safeguardSource = "手动切号还原后",
                            strict = true,
                        )
                        if (safeguardOk) {
                            markManualAccountReady(target, "还原+登录状态检测")
                        } else {
                            LogCenter.op(alias, "手动切号：@$target 还原后登录状态未通过，回退自动登录")
                            PrefsStore.setManualFirstLoginDone(target, false)
                            updateRuntime(
                                localStatus = "running",
                                lastMessage = "手动切号：@$target 还原后→自动登录",
                            )
                            val loginOutcome = ensureAccountLoggedInWithRetry(
                                alias = alias,
                                deviceId = deviceId,
                                reason = "手动切号还原后→自动登录",
                                expectedUsername = target,
                                resumeRunningOnSuccess = false,
                                forceRelogin = true,
                            )
                            if (loginOutcome != AccountLoginRetryOutcome.LoggedIn) {
                                error("还原后登录状态未通过且自动登录 @$target 未完成（$loginOutcome）")
                            }
                            markManualAccountReady(target, "还原后自动登录")
                        }
                    } else {
                        LogCenter.op(alias, "手动切号：@$target 还原失败，回退自动登录")
                        PrefsStore.setManualFirstLoginDone(target, false)
                        updateRuntime(localStatus = "running", lastMessage = "手动切号：@$target 还原失败→自动登录")
                        val loginOutcome = ensureAccountLoggedInWithRetry(
                            alias = alias,
                            deviceId = deviceId,
                            reason = "手动切号还原失败→自动登录",
                            expectedUsername = target,
                            resumeRunningOnSuccess = false,
                            forceRelogin = true,
                        )
                        if (loginOutcome != AccountLoginRetryOutcome.LoggedIn) {
                            error("还原失败且自动登录 @$target 未完成（$loginOutcome）")
                        }
                        markManualAccountReady(target, "还原失败后自动登录")
                    }
                } else {
                    // 无备份（或登录切号方案）→ 清理 + 自动登录
                    LogCenter.op(
                        alias,
                        if (loginCutScheme) {
                            "手动切号：@$target 登录切号 → 清理后自动登录（跳过备份/还原）"
                        } else {
                            "手动切号：@$target 无本机备份 → 清理后自动登录"
                        },
                    )
                    handleDeviceOp(
                        alias,
                        deviceId,
                        DeviceOp(
                            op_id = ManualDeviceControl.newLocalManualOpId("clear"),
                            op_type = "tiktok_clear",
                            payload = DeviceOpPayload(
                                device_id = deviceId,
                                alias = alias,
                                package_name = pkg,
                                username = target,
                                source = "manual",
                            ),
                        ),
                    )
                    if (!lastDeviceOpOk) {
                        error("清理 TikTok 失败，切号中止")
                    }
                    tikTokSwitched = true
                    PrefsStore.setManualActiveUsername(target)
                    PrefsStore.setManualFirstLoginDone(target, false)
                    updateRuntime(localStatus = "running", lastMessage = "手动切号：@$target 自动登录中")
                    val loginOutcome = ensureAccountLoggedInWithRetry(
                        alias = alias,
                        deviceId = deviceId,
                        reason = "手动切号无备份→自动登录",
                        expectedUsername = target,
                        resumeRunningOnSuccess = false,
                        forceRelogin = true,
                    )
                    if (loginOutcome != AccountLoginRetryOutcome.LoggedIn) {
                        error("自动登录 @$target 未完成（$loginOutcome）")
                    }
                    markManualAccountReady(target, "无备份自动登录")
                }

                PrefsStore.setManualSelectedUsername(target)
                PrefsStore.setManualActiveUsername(target)
                PrefsStore.setLogContextUsername(target)
                PrefsStore.saveLocalStatus("online")
                val status = manualQuerySwitchStatus(target)
                updateRuntime(
                    localStatus = "online",
                    lastMessage = if (restoredFromBackup) {
                        "切号完成（还原）：${status.message}"
                    } else {
                        "切号完成（自动登录）：${status.message}"
                    },
                )
                notifyManualAccountUi()
                LogCenter.op(
                    alias,
                    buildString {
                        append("手动切号完成：@$target")
                        append(if (restoredFromBackup) "（还原）" else "（自动登录）")
                        append(" · ${status.message}")
                    },
                )
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    if (!tikTokSwitched && previousActive.isNotBlank()) {
                        PrefsStore.setManualSelectedUsername(previousActive)
                    } else {
                        // 已清/还目标环境：保持选中目标，便于继续点「自动登录」
                        PrefsStore.setManualSelectedUsername(target)
                        PrefsStore.setManualActiveUsername(target)
                        PrefsStore.setLogContextUsername(target)
                    }
                    updateRuntime(localStatus = "online", lastMessage = "手动切号已取消")
                    LogCenter.op(alias, "手动切号已取消 → @$target")
                    throw t
                }
                if (!tikTokSwitched && previousActive.isNotBlank()) {
                    // 尚未动 TikTok（如备份失败）：回滚选中
                    PrefsStore.setManualSelectedUsername(previousActive)
                } else {
                    // 已清号：保持目标选中/active，方便用户点自动登录重试
                    PrefsStore.setManualSelectedUsername(target)
                    PrefsStore.setManualActiveUsername(target)
                    PrefsStore.setLogContextUsername(target)
                }
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "手动切号失败：${t.message}")
                LogCenter.op(alias, "手动切号失败 → @$target：${t.message}")
            } finally {
                // 先置空 job，再清 busy，避免 UI 刷新时 job.isActive 仍为 true 导致按钮一直灰
                manualSwitchJob = null
                _manualSwitchBusy.value = false
                notifyManualAccountUi()
            }
        }
    }

    private fun detectActiveManualTaskKind(): ManualTaskKind? = when {
        dailyIncubateJob?.isActive == true -> ManualTaskKind.DAILY_INCUBATE
        batchPublishJob?.isActive == true -> ManualTaskKind.BATCH_PUBLISH
        autoActiveJob?.isActive == true -> ManualTaskKind.AUTO_ACTIVE
        activateNextJob?.isActive == true -> ManualTaskKind.ACTIVATE_NEXT
        isPublishPostSwitchActive() || PublishPostGrowthGate.isBlockingGrowth() -> ManualTaskKind.PUBLISH_POST
        isMutualSwitchActive() -> ManualTaskKind.MUTUAL_CLICK
        basicProfileEditRunning -> ManualTaskKind.EDIT_PROFILE
        autoLoginActive -> ManualTaskKind.AUTO_LOGIN
        forYouSwipeJob?.isActive == true -> ManualTaskKind.FOR_YOU_SWIPE
        manualSwitchJob?.isActive == true || _manualSwitchBusy.value -> ManualTaskKind.SWITCH_ACCOUNT
        pullLoopJob?.isActive == true -> ManualTaskKind.GROWTH
        else -> null
    }

    /**
     * 手动区新任务接管：异功能则以最快速度停掉当前任务（协作退出 + cancel + force-stop TikTok）；
     * 同功能已在跑则返回 false（忽略重复点击）。
     * 注意：调用方协程若已计入 [detectActiveManualTaskKind]（如切号），须排除「自己」以免误判重复。
     */
    private suspend fun prepareManualTaskTakeover(target: ManualTaskKind, reason: String): Boolean {
        if (!ManualDeviceControl.ENABLED) return true
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val active = detectActiveManualTaskKind()
        val selfJob = currentCoroutineContext()[Job]
        val activeIsSelf = when (target) {
            ManualTaskKind.SWITCH_ACCOUNT ->
                active == ManualTaskKind.SWITCH_ACCOUNT && manualSwitchJob === selfJob
            ManualTaskKind.FOR_YOU_SWIPE ->
                active == ManualTaskKind.FOR_YOU_SWIPE && forYouSwipeJob === selfJob
            ManualTaskKind.GROWTH ->
                active == ManualTaskKind.GROWTH && pullLoopJob === selfJob
            ManualTaskKind.ACTIVATE_NEXT ->
                active == ManualTaskKind.ACTIVATE_NEXT && activateNextJob === selfJob
            ManualTaskKind.BATCH_PUBLISH ->
                active == ManualTaskKind.BATCH_PUBLISH && batchPublishJob === selfJob
            ManualTaskKind.AUTO_ACTIVE ->
                active == ManualTaskKind.AUTO_ACTIVE && autoActiveJob === selfJob
            ManualTaskKind.DAILY_INCUBATE ->
                active == ManualTaskKind.DAILY_INCUBATE && dailyIncubateJob === selfJob
            else -> false
        }
        // 多账号会话内部切号：父会话仍占 ACTIVATE_NEXT / BATCH_PUBLISH / AUTO_ACTIVE / DAILY_INCUBATE，允许子步骤继续
        val multiAccountChildStep =
            activateNextOwnsSwitch &&
                target == ManualTaskKind.SWITCH_ACCOUNT &&
                (
                    active == ManualTaskKind.ACTIVATE_NEXT ||
                        active == ManualTaskKind.BATCH_PUBLISH ||
                        active == ManualTaskKind.AUTO_ACTIVE ||
                        active == ManualTaskKind.DAILY_INCUBATE
                    )
        if (active == target && !activeIsSelf) {
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "手动任务：${target.label}已在进行中，忽略重复点击")
            }
            return false
        }
        if (multiAccountChildStep) {
            return true
        }

        val stickyForeignPublish =
            target != ManualTaskKind.PUBLISH_POST &&
                (PrefsStore.isPublishPostRequested() || PrefsStore.isPublishPostPending())
        val stickyForeignMutual =
            target != ManualTaskKind.MUTUAL_CLICK &&
                (PrefsStore.isMutualClickRequested() || PrefsStore.isMutualClickPending())

        val busy = (!activeIsSelf && active != null && active != target) ||
            (target != ManualTaskKind.GROWTH && pullLoopJob?.isActive == true) ||
            (target != ManualTaskKind.FOR_YOU_SWIPE && forYouSwipeJob?.isActive == true) ||
            (target != ManualTaskKind.PUBLISH_POST && isPublishPostSwitchActive()) ||
            (target != ManualTaskKind.MUTUAL_CLICK && isMutualSwitchActive()) ||
            isLiveVisitSwitchActive() ||
            (target != ManualTaskKind.EDIT_PROFILE && basicProfileEditRunning) ||
            (target != ManualTaskKind.AUTO_LOGIN && autoLoginActive) ||
            (target != ManualTaskKind.ACTIVATE_NEXT && activateNextJob?.isActive == true) ||
            (target != ManualTaskKind.BATCH_PUBLISH && batchPublishJob?.isActive == true) ||
            (target != ManualTaskKind.AUTO_ACTIVE && autoActiveJob?.isActive == true) ||
            (target != ManualTaskKind.DAILY_INCUBATE && dailyIncubateJob?.isActive == true) ||
            (target != ManualTaskKind.PUBLISH_POST && PublishPostGrowthGate.isBlockingGrowth()) ||
            ExecutionScope.isActive() ||
            stickyForeignPublish ||
            stickyForeignMutual

        if (!busy) {
            // 空闲接管也清掉 crash/取消遗留的独占标志，避免涨粉 pull 永久空转
            releaseStaleManualExclusivePreemptIfIdle("手动接管空闲·$reason")
            return true
        }

        if (alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "手动抢占：停止${active?.label ?: "当前任务"} → 执行${target.label}（$reason）",
            )
        }

        // 让涨粉/互点长步骤尽快协作退出（勿用硬停：硬停会挡住随后的新任务）
        // 必须 finally 清 preempt：否则协程被取消会永久卡死涨粉（9_br26 现场）
        PrefsStore.setAutoSwitchExclusivePreempt(true)
        PrefsStore.setHardStopRequested(false)
        PrefsStore.setTransientPausedForDestructiveOp(false)
        manuallyPaused = false

        try {
            var cancelledExclusiveThatAutoGrows = false
            // 注意：BATCH_PUBLISH 仅在会话中段自己 trigger 发作品，prepare 只在会话开头调用一次，
            // 此处必须清掉「外来」发作品，否则会与批量发作品并行冲突。
            if (target != ManualTaskKind.PUBLISH_POST) {
                if (isPublishPostSwitchActive()) {
                    publishPostSwitchJob?.cancel()
                    publishPostSwitchJob = null
                    cancelledExclusiveThatAutoGrows = true
                }
                PrefsStore.clearPublishPostState()
                PrefsStore.clearPublishPostTaskScope()
                PublishPostGrowthGate.exitExclusivePublishPhase()
            }
            if (target != ManualTaskKind.MUTUAL_CLICK) {
                val hadMutual =
                    PrefsStore.isMutualClickRequested() ||
                        PrefsStore.isMutualClickPending() ||
                        isMutualSwitchActive()
                if (hadMutual) cancelledExclusiveThatAutoGrows = true
                clearMutualClickStateForInterrupt("手动抢占→${target.label}")
            }
            if (target != ManualTaskKind.FOR_YOU_SWIPE) {
                forYouSwipeJob?.takeIf { it !== selfJob }?.cancel()
                if (forYouSwipeJob !== selfJob) forYouSwipeJob = null
            }
            if (target != ManualTaskKind.GROWTH) {
                pullLoopJob?.takeIf { it !== selfJob }?.cancel()
                if (pullLoopJob !== selfJob) {
                    pullLoopJob = null
                    pendingUsernames.clear()
                }
            }
            if (target != ManualTaskKind.SWITCH_ACCOUNT) {
                manualSwitchJob?.takeIf { it !== selfJob && it.isActive }?.cancel()
            }
            // 外部任务可打断多账号会话；仅「会话内部切号」跳过，避免切号 prepare 把自己掐死
            val skipCancelMultiAccount =
                activateNextOwnsSwitch && target == ManualTaskKind.SWITCH_ACCOUNT
            if (!skipCancelMultiAccount) {
                if (target != ManualTaskKind.ACTIVATE_NEXT) {
                    activateNextJob?.takeIf { it !== selfJob && it.isActive }?.cancel()
                    if (activateNextJob !== selfJob) activateNextJob = null
                }
                if (target != ManualTaskKind.BATCH_PUBLISH) {
                    batchPublishJob?.takeIf { it !== selfJob && it.isActive }?.cancel()
                    if (batchPublishJob !== selfJob) batchPublishJob = null
                }
                if (target != ManualTaskKind.AUTO_ACTIVE) {
                    autoActiveJob?.takeIf { it !== selfJob && it.isActive }?.cancel()
                    if (autoActiveJob !== selfJob) autoActiveJob = null
                }
                if (target != ManualTaskKind.DAILY_INCUBATE) {
                    dailyIncubateJob?.takeIf { it !== selfJob && it.isActive }?.cancel()
                    if (dailyIncubateJob !== selfJob) dailyIncubateJob = null
                }
            }
            if (target != ManualTaskKind.EDIT_PROFILE && basicProfileEditRunning) {
                cancelledExclusiveThatAutoGrows = true
            }
            liveVisitSwitchJob?.cancel()
            liveVisitSwitchJob = null

            // 仅当确实打断了「结束后会自动涨粉」的独占任务时，短暂抑制 completion 回调
            if (cancelledExclusiveThatAutoGrows) {
                suppressManualAutoGrowthUntilElapsed = SystemClock.elapsedRealtime() + 3_000L
            }

            // 尽快打断 OCR/点击：强停 TikTok（发作品独占标记已在上面清掉）
            forceStopTikTokForExclusiveEnqueue("手动抢占→${target.label}")

            val deadline = SystemClock.elapsedRealtime() + 2_500L
            while (
                (ExecutionScope.isActive() ||
                    (target != ManualTaskKind.PUBLISH_POST && isPublishPostSwitchActive()) ||
                    (target != ManualTaskKind.MUTUAL_CLICK && isMutualSwitchActive()) ||
                    (target != ManualTaskKind.GROWTH && pullLoopJob?.isActive == true) ||
                    (target != ManualTaskKind.FOR_YOU_SWIPE && forYouSwipeJob?.isActive == true)) &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                delay(40)
            }
            if (ExecutionScope.isActive()) {
                ExecutionScope.forceClearIfStuck("手动抢占→${target.label}")
            }
        } finally {
            PrefsStore.setAutoSwitchExclusivePreempt(false)
        }
        return true
    }

    /**
     * 手动模式下：无真实独占任务时清掉遗留 [PrefsStore.isAutoSwitchExclusivePreempt]。
     * 典型场景：手动抢占中途协程被取消，preempt=true 永久挡住涨粉拉号。
     */
    private fun releaseStaleManualExclusivePreemptIfIdle(reason: String) {
        if (!ManualDeviceControl.ENABLED) return
        if (!PrefsStore.isAutoSwitchExclusivePreempt()) return
        if (PrefsStore.isAutoSwitchAutonomousMode() && isAutoSwitchExclusiveWorkActuallyActive()) return
        if (PublishPostGrowthGate.isBlockingGrowth()) return
        if (isPublishPostSwitchActive() || isMutualSwitchActive() || isLiveVisitSwitchActive()) return
        if (basicProfileEditRunning) return
        // 抢占 takeover / 涨粉步骤仍在收尾时不要清，避免中途放行拉号
        if (ExecutionScope.isActive()) return
        PrefsStore.setAutoSwitchExclusivePreempt(false)
        PrefsStore.setTransientPausedForDestructiveOp(false)
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "已清除遗留独占抢占标志（$reason），允许涨粉继续")
        }
    }

    private fun enqueueManualTaskOp(
        label: String,
        buildOp: (alias: String, deviceId: String, pkg: String) -> DeviceOp?,
    ) {
        if (!ManualDeviceControl.ENABLED) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) {
            LogCenter.op(alias.ifBlank { null }, "手动$label：设备未绑定")
            return
        }
        val user = ManualDeviceControl.selectedManualUsername()
        if (user.isBlank()) {
            LogCenter.op(alias, "手动$label：请先选择账号")
            return
        }
        if (!PrefsStore.isManualFirstLoginDone(user)) {
            LogCenter.op(alias, "手动$label：请先切号完成（有备份还原 / 无备份自动登录）")
            return
        }
        val kind = when (label) {
            "publish_post_start" -> ManualTaskKind.PUBLISH_POST
            "mutual_click_start" -> ManualTaskKind.MUTUAL_CLICK
            "tiktok_edit_basic_profile" -> ManualTaskKind.EDIT_PROFILE
            else -> {
                LogCenter.op(alias, "手动$label：未知任务类型，已忽略")
                return
            }
        }
        if (detectActiveManualTaskKind() == kind) {
            LogCenter.op(alias, "手动任务：${kind.label}已在进行中，忽略重复点击")
            return
        }
        manualDispatchJob?.cancel()
        manualDispatchJob = scope.launch {
            if (!prepareManualTaskTakeover(kind, "手动$label")) return@launch
            PrefsStore.setTransientPausedForDestructiveOp(false)
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            if (!started.get()) started.set(true)
            PrefsStore.setLogContextUsername(user)
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "手动执行：$label")
            val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
            val op = buildOp(alias, deviceId, pkg) ?: return@launch
            LogCenter.op(alias, "手动模式：下发 local OP ${op.op_type}")
            handleDeviceOp(alias, deviceId, op)
        }
    }

    private fun maybeManualAutoGrowthAfterTask(reason: String) {
        if (!ManualDeviceControl.ENABLED) return
        if (SystemClock.elapsedRealtime() < suppressManualAutoGrowthUntilElapsed) {
            LogCenter.op(PrefsStore.getAlias(), "手动抢占窗口内：跳过「$reason」后的自动涨粉")
            return
        }
        if (!PrefsStore.isAutoGrowthAfterTasksEnabled()) {
            PrefsStore.saveLocalStatus("online")
            updateRuntime(localStatus = "online", lastMessage = "$reason（未勾选完成后自动涨粉）")
            return
        }
        val user = ManualDeviceControl.selectedManualUsername()
        if (user.isBlank() || !PrefsStore.isManualFirstLoginDone(user)) {
            PrefsStore.saveLocalStatus("online")
            updateRuntime(localStatus = "online", lastMessage = "$reason：未就绪，无法自动涨粉")
            return
        }
        LogCenter.op(PrefsStore.getAlias(), "手动模式：$reason → 自动进入涨粉")
        manualStartGrowth("$reason → 自动涨粉")
    }

    data class AutoSwitchParticipationAccount(
        val username: String,
        val rowIndex: Int?,
        val autoSwitchExcluded: Boolean,
        val looksBanned: Boolean,
        val looksMaxLogin: Boolean,
        val cloudIsLogin: Boolean?,
        val assigned: DeviceAssignedAccountItem,
    ) {
        val looksCredBlocked: Boolean get() = looksBanned || looksMaxLogin
        val participating: Boolean get() = !autoSwitchExcluded && !looksCredBlocked
    }

    /** 拉取别名桶账号（含不参与 / banned），供切换云端模式前展示。 */
    suspend fun fetchAutoSwitchParticipationRoster(): Result<List<AutoSwitchParticipationAccount>> {
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) {
            return Result.failure(IllegalStateException("请先绑定设备"))
        }
        return runCatching {
            val resp = ApiService.getDeviceAssignedAccountsRoster(deviceId, alias)
            if (resp.code != 0) {
                throw IllegalStateException(resp.msg?.trim().orEmpty().ifBlank { "拉取账号失败" })
            }
            val items = resp.items.orEmpty()
            items.mapNotNull { item ->
                val username = item.username?.trim()?.removePrefix("@").orEmpty()
                if (username.isEmpty()) return@mapNotNull null
                AutoSwitchParticipationAccount(
                    username = username,
                    rowIndex = item.row_index ?: item.account_row_index,
                    autoSwitchExcluded = item.auto_switch_excluded == true,
                    looksBanned = item.looks_banned == true ||
                        ManualDeviceControl.isBannedMarker(item.password) ||
                        ManualDeviceControl.isBannedMarker(item.email) ||
                        ManualDeviceControl.isBannedMarker(item.email_password),
                    looksMaxLogin = item.looks_max_login == true ||
                        ManualDeviceControl.isMaxLoginMarker(item.password) ||
                        ManualDeviceControl.isMaxLoginMarker(item.email) ||
                        ManualDeviceControl.isMaxLoginMarker(item.email_password),
                    cloudIsLogin = item.is_login,
                    assigned = item,
                )
            }
        }
    }

    /** 单账号立即同步云端「参与 / 不参与自动切号」。 */
    suspend fun setAccountAutoSwitchParticipating(
        username: String,
        participating: Boolean,
    ): Result<DeviceAssignedAccountSetAutoSwitchExcludedResponse> {
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val user = username.trim().removePrefix("@")
        if (deviceId.isBlank() || alias.isBlank() || user.isBlank()) {
            return Result.failure(IllegalStateException("参数无效"))
        }
        return runCatching {
            val resp = ApiService.setDeviceAssignedAccountAutoSwitchExcluded(
                deviceId = deviceId,
                alias = alias,
                username = user,
                excluded = !participating,
            )
            if (resp.code != 0) {
                throw IllegalStateException(resp.msg?.trim().orEmpty().ifBlank { "更新失败" })
            }
            LogCenter.op(
                alias,
                if (participating) {
                    "已将 @$user 设为参与自动切号"
                } else {
                    "已将 @$user 设为不参与自动切号"
                },
            )
            resp
        }
    }

    /**
     * 运行模式切换公共收尾：停本机任务、强停并 pm clear 一次 TikTok，成功后再清旧模式进度账本。
     * 因模式切换无法感知手机当前登录号，用清空数据避免串号/脏进度。
     * @return true 表示 TikTok 数据已清空且本机旧进度已清；false 表示 pm clear 失败（本机进度保留，模式不变）。
     */
    private suspend fun prepareControlModeSwitch(reason: String): Boolean {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        PrefsStore.setHardStopRequested(false)
        manuallyPaused = false
        PrefsStore.setTransientPausedForDestructiveOp(false)

        // 停本机进行中的任务协程，避免与 pm clear 打架（此时尚不清账本，失败可回退）
        cancelAutoSwitchBundleRetry()
        pullLoopJob?.cancel()
        pullLoopJob = null
        forYouSwipeJob?.cancel()
        forYouSwipeJob = null
        manualSwitchJob?.cancel()
        manualSwitchJob = null
        activateNextJob?.cancel()
        activateNextJob = null
        batchPublishJob?.cancel()
        batchPublishJob = null
        autoActiveJob?.cancel()
        autoActiveJob = null
        dailyIncubateJob?.cancel()
        dailyIncubateJob = null
        manualDispatchJob?.cancel()
        manualDispatchJob = null
        autoSwitchLoginRecoveryJob?.cancel()
        autoSwitchLoginRecoveryJob = null
        clearMutualClickStateForInterrupt(reason)
        if (isPublishPostSwitchActive()) {
            publishPostSwitchJob?.cancel()
            publishPostSwitchJob = null
        }
        if (isLiveVisitSwitchActive()) {
            liveVisitSwitchJob?.cancel()
            liveVisitSwitchJob = null
        }
        if (isBatchStreamActive()) {
            batchStreamJob?.cancel()
            batchStreamJob = null
        }
        // 停掉本机自动切号调度器，但先不清 plan 文件：pm clear 失败时仍可续跑旧计划
        PrefsStore.setManualLocalAutoSwitchEnabled(false)
        autoSwitchLocalScheduler.stop(clearState = false)

        val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "运行模式切换（$reason）：开始清空 TikTok 数据（成功后再清旧进度）")
        }
        PrefsStore.setTransientPausedForDestructiveOp(true)
        forceStopTikTokPackageAndVerify(
            packageName = pkg,
            alias = alias,
            phaseLabel = "模式切换清空",
            aggressiveForCutSwitch = true,
        )
        val clearOk = runExecutorSu("pm clear $pkg")
        delay(1_200L)
        PrefsStore.setTransientPausedForDestructiveOp(false)
        if (!clearOk) {
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "运行模式切换（$reason）：pm clear 失败，已中止切换（本机进度保留）")
            }
            updateRuntime(lastMessage = "模式切换失败：清空 TikTok 失败")
            return false
        }

        // pm clear 成功后才不可逆地清旧模式账本
        autoSwitchLocalScheduler.stop(clearState = true)
        AutoSwitchLedger.clear()
        AutoSwitchAccountActivityTracker.reset()
        AutoSwitchBundleHolder.clear()
        PrefsStore.clearControlModeTaskProgress()
        ManualDeviceControl.updateCachedAccounts(emptyList())
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "运行模式切换（$reason）：TikTok 数据已清空，旧模式任务进度已清除")
        }
        updateRuntime(lastMessage = "模式切换：已清空 TikTok 与旧进度")
        return true
    }

    /**
     * 切换到云端 client：先清空旧模式进度 + TikTok 数据，再按挑选名单同步参与状态并开启本设备云端自动切号，
     * 最后关闭本机手动模式 / 本机自治。
     */
    suspend fun switchToCloudClientMode(participateUsernames: List<String>): Result<String> {
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) {
            return Result.failure(IllegalStateException("请先绑定设备"))
        }
        if (PrefsStore.getAuthToken().isNullOrBlank()) {
            return Result.failure(IllegalStateException("请先登录云端"))
        }
        val participate = participateUsernames
            .map { it.trim().removePrefix("@") }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

        return runCatching {
            if (!prepareControlModeSwitch("切换云端模式")) {
                throw IllegalStateException("清空 TikTok 数据失败（需 root），已中止切换到云端模式")
            }
            started.set(true)

            // 必须先切出手动模式，再开启云端自动切号 / 投递 enter。
            // 否则 mode_wait 并发收到 enter 时会因 ManualDeviceControl.ENABLED 仍为 true 被忽略并推进 lastModeEventId，事件永久丢失。
            PrefsStore.setManualControlMode(false)

            val sync = ApiService.syncDeviceAssignedAccountsAutoSwitchParticipation(
                deviceId = deviceId,
                alias = alias,
                participateUsernames = participate,
                // 至少有一个参与账号时开启本设备云端自动切号；0 个则只切模式、不抢开设备开关
                enableDeviceAutoSwitch = participate.isNotEmpty(),
            )
            if (sync.code != 0) {
                // TikTok/进度已清，无法完整回滚；仅恢复手动标记，避免卡在「本地云端、云端未开」
                PrefsStore.setManualControlMode(true)
                throw IllegalStateException(
                    (sync.msg?.trim().orEmpty().ifBlank { "同步参与名单失败" }) +
                        "（TikTok 与旧进度已清空，请重试切换或手动拉账号）",
                )
            }
            val tip = when {
                participate.isEmpty() ->
                    "已切换到云端模式（已清空 TikTok/旧进度）；未挑选参与账号，设备云端自动切号未开启"
                participate.size < 2 ->
                    "已切换到云端模式（已清空 TikTok/旧进度）；参与 ${participate.size} 个账号（云端计划通常需 ≥2 个才续跑自动切号）"
                else ->
                    "已切换到云端模式（已清空 TikTok/旧进度）；参与自动切号 ${participate.size} 个账号"
            }
            LogCenter.op(
                alias,
                "$tip（更新 ${sync.updated_rows ?: 0} 行，计划桶 ${sync.bucket_size ?: 0}）",
            )
            updateRuntime(
                localStatus = PrefsStore.getLocalStatus(),
                lastMessage = "已切换到云端 client 模式",
            )
            if (!WorkerServiceTracker.isRunning) {
                runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "switch_to_cloud_client") }
            }
            ensureLoopsRunning()
            // 设备原本已开启时服务端可能不重投 enter；显式拉起云端恢复路径。
            if (participate.isNotEmpty()) {
                forceStartRunningNow("切换到云端模式后恢复")
            }
            tip
        }
    }

    /** 切回本机手动：先清空旧模式进度 + TikTok，再关本设备云端自动切号，打开手动模式。 */
    suspend fun switchToManualControlMode(): Result<String> {
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) {
            return Result.failure(IllegalStateException("请先绑定设备"))
        }
        return runCatching {
            if (!prepareControlModeSwitch("切回本机手动")) {
                throw IllegalStateException("清空 TikTok 数据失败（需 root），已中止切回本机手动")
            }
            // participate_usernames=null：只关设备开关，绝不改账号参与标记（避免拉名单失败时把全部标成不参与）
            val sync = ApiService.syncDeviceAssignedAccountsAutoSwitchParticipation(
                deviceId = deviceId,
                alias = alias,
                participateUsernames = null,
                enableDeviceAutoSwitch = false,
            )
            if (sync.code != 0) {
                throw IllegalStateException(
                    (sync.msg?.trim().orEmpty().ifBlank { "关闭云端自动切号失败" }) +
                        "（TikTok 与旧进度已清空，请重试切回手动）",
                )
            }
            PrefsStore.setManualControlMode(true)
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            LogCenter.op(alias, "已切回本机手动模式（已清空 TikTok/旧进度；云端自动切号已关；账号参与标记保留）")
            updateRuntime(lastMessage = "已切回本机手动模式")
            "已切回本机手动模式（已清空 TikTok/旧进度）"
        }
    }

    /** 开启本机自动切号自治：按当前账号列表生成计划并启动调度。 */
    fun manualEnableLocalAutoSwitch(slotMinutes: Int = PrefsStore.getManualAutoSwitchSlotMinutes()) {
        if (!ManualDeviceControl.ENABLED) return
        val minutes = ManualAutoSwitchPlanBuilder.normalizeSlotMinutes(slotMinutes)
        PrefsStore.setManualAutoSwitchSlotMinutes(minutes)
        scope.launch {
            val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            if (deviceId.isBlank() || alias.isBlank()) {
                LogCenter.op(alias.ifBlank { null }, "本机自动切号：请先绑定设备")
                PrefsStore.setManualLocalAutoSwitchEnabled(false)
                return@launch
            }
            if (shouldHonorUserHardStop()) {
                LogCenter.op(alias, "本机自动切号：当前硬停中，已取消开启")
                PrefsStore.setManualLocalAutoSwitchEnabled(false)
                return@launch
            }
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            PrefsStore.setTransientPausedForDestructiveOp(false)
            PrefsStore.setManualLocalAutoSwitchEnabled(true)
            val ok = rebuildAndStartManualLocalAutoSwitch(
                alias = alias,
                deviceId = deviceId,
                slotMinutes = minutes,
                reason = "APP 开启本机自动切号",
            )
            if (!ok || shouldHonorUserHardStop() || !PrefsStore.isManualLocalAutoSwitchEnabled()) {
                PrefsStore.setManualLocalAutoSwitchEnabled(false)
                if (ok && shouldHonorUserHardStop()) {
                    autoSwitchLocalScheduler.stop(clearState = true)
                    PrefsStore.clearAutoSwitchSessionContext()
                    LogCenter.op(alias, "本机自动切号：开启过程中遇到硬停，已回滚")
                }
                updateRuntime(lastMessage = "本机自动切号启动失败或已取消")
                return@launch
            }
            if (!WorkerServiceTracker.isRunning) {
                runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "manual_local_auto_switch") }
            }
            PrefsStore.saveLocalStatus("running")
            updateRuntime(
                localStatus = "running",
                hardStopRequested = false,
                lastMessage = "本机自动切号已开启（每号 ${minutes} 分钟）",
            )
            LogCenter.op(alias, "本机自动切号已开启：每号 ${minutes} 分钟，按账号列表轮转")
            maybeStartGrowthInAutoSwitchIdle("本机自动切号开启")
        }
    }

    /** 关闭本机自动切号自治；可同时进入硬停。 */
    fun manualDisableLocalAutoSwitch(alsoHardStop: Boolean = false) {
        if (!ManualDeviceControl.ENABLED) return
        scope.launch {
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
            val wasLocalSession =
                ManualAutoSwitchPlanBuilder.isLocalSessionId(PrefsStore.getAutoSwitchSessionId())
            PrefsStore.setManualLocalAutoSwitchEnabled(false)
            when {
                wasLocalSession &&
                    deviceId.isNotBlank() &&
                    alias.isNotBlank() &&
                    PrefsStore.isAutoSwitchAutonomousMode() ->
                    performAutoSwitchExitShutdown(alias, deviceId, eventId = 0, exitSessionId = "")
                PrefsStore.isAutoSwitchAutonomousMode() || PrefsStore.getAutoSwitchMode() == "exiting" ->
                    clearOrphanedAutoSwitchAfterManualDisable("APP 关闭遗留自治")
                else ->
                    clearOrphanedAutoSwitchAfterManualDisable("APP 关闭")
            }
            if (alsoHardStop) {
                // 退出收尾可能改写 lastMessage / localStatus，硬停标志再盖一次。
                applyUserGracefulHardStopFlags(alias.ifBlank { null })
            } else if (!shouldHonorUserHardStop()) {
                LogCenter.op(alias.ifBlank { null }, "本机自动切号已关闭")
                updateRuntime(lastMessage = "本机自动切号已关闭")
            }
        }
    }

    /** 按当前账号列表与时段分钟数重建本机计划（保持开启状态）。 */
    fun manualRebuildLocalAutoSwitchPlan(slotMinutes: Int = PrefsStore.getManualAutoSwitchSlotMinutes()) {
        if (!ManualDeviceControl.ENABLED) return
        val minutes = ManualAutoSwitchPlanBuilder.normalizeSlotMinutes(slotMinutes)
        PrefsStore.setManualAutoSwitchSlotMinutes(minutes)
        if (!PrefsStore.isManualLocalAutoSwitchEnabled()) {
            manualEnableLocalAutoSwitch(minutes)
            return
        }
        scope.launch {
            val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            if (deviceId.isBlank() || alias.isBlank()) {
                LogCenter.op(alias.ifBlank { null }, "重建本机计划：请先绑定设备")
                return@launch
            }
            if (shouldHonorUserHardStop()) {
                LogCenter.op(alias, "重建本机计划：硬停中，已跳过")
                return@launch
            }
            PrefsStore.setHardStopRequested(false)
            manuallyPaused = false
            val ok = rebuildAndStartManualLocalAutoSwitch(
                alias = alias,
                deviceId = deviceId,
                slotMinutes = minutes,
                reason = "APP 重建本机计划",
            )
            if (ok && !shouldHonorUserHardStop() && PrefsStore.isManualLocalAutoSwitchEnabled()) {
                updateRuntime(lastMessage = "本机计划已重建（每号 ${minutes} 分钟）")
                maybeStartGrowthInAutoSwitchIdle("本机计划重建")
            }
        }
    }

    private suspend fun rebuildAndStartManualLocalAutoSwitch(
        alias: String,
        deviceId: String,
        slotMinutes: Int,
        reason: String,
    ): Boolean {
        if (!PrefsStore.isManualLocalAutoSwitchEnabled()) return false
        if (shouldHonorUserHardStop()) return false
        val accounts = ManualDeviceControl.getCachedAccounts()
            .filterNot { ManualDeviceControl.accountLooksCredBlocked(it) || it.autoSwitchExcluded }
        if (accounts.isEmpty()) {
            LogCenter.op(alias, "本机自动切号失败：请先拉取云端账号列表（或列表中账号均已封禁）")
            return false
        }
        val bundle = runCatching {
            ManualAutoSwitchPlanBuilder.buildBundle(
                context = appContext,
                accounts = accounts,
                slotMinutes = slotMinutes,
                alias = alias,
                deviceId = deviceId,
                previousSessionId = PrefsStore.getAutoSwitchSessionId(),
                previousRevision = PrefsStore.getAutoSwitchBundleRevision(),
            )
        }.onFailure { err ->
            LogCenter.op(alias, "本机计划生成失败：${err.message}")
        }.getOrNull() ?: return false

        if (!PrefsStore.isManualLocalAutoSwitchEnabled() || shouldHonorUserHardStop()) return false

        val sessionId = bundle.session_id?.trim().orEmpty()
        val revision = bundle.revision ?: 1
        PrefsStore.saveAutoSwitchSessionContext(sessionId, revision)
        AutoSwitchLedger.restoreFromPrefs()
        AutoSwitchLedger.startSession(sessionId, bundleRevision = revision)
        PrefsStore.setAutoSwitchPendingSummaryUpload(false)
        if (autoSwitchLocalScheduler.isRunning()) {
            autoSwitchLocalScheduler.stop(clearState = false)
        }
        applyAutoSwitchBundle(alias, deviceId, bundle, revision)
        if (!PrefsStore.isManualLocalAutoSwitchEnabled() || shouldHonorUserHardStop()) {
            autoSwitchLocalScheduler.stop(clearState = true)
            PrefsStore.clearAutoSwitchSessionContext()
            LogCenter.op(alias, "【自动切号】$reason：启动后检测到关闭/硬停，已回滚")
            return false
        }
        LogCenter.op(
            alias,
            "【自动切号】$reason：本机计划已生效 accounts=${accounts.size} " +
                "slotMin=$slotMinutes revision=$revision slots=${AutoSwitchLocalPlanStore.get()?.slots?.size ?: 0}",
        )
        autoSwitchLocalScheduler.requestImmediateTick()
        return true
    }

    // endregion

    private fun ensureLoopsRunning() {
        if (startPollJob?.isActive != true) {
            startCheckLoop()
        }
        if (!PrefsStore.isAutoSwitchAutonomousMode() && deviceOpsJob?.isActive != true) {
            startDeviceOpsLoop()
        }
        if (ManualDeviceControl.ENABLED) {
            // 手动模式：不因 running 看门狗自动拉起涨粉。
            return
        }
        // 自治模式 bundle/调度恢复由 recoverAutoSwitchAutonomousWork 统一处理，避免与 onWorkerServiceChanged 重复拉取。
        // 自治模式涨粉由切号完成/调度空档/recoverAutoSwitchAutonomousWork 显式恢复，不在此做 pull loop 看门狗。
        if (
            !PrefsStore.isAutoSwitchAutonomousMode() &&
            PrefsStore.getLocalStatus() == "running" &&
            pullLoopJob?.isActive != true
        ) {
            startPullLoop()
        }
    }

    private fun runExecutorSu(command: String): Boolean {
        return runCatching {
            executor.run {
                val m = javaClass.getDeclaredMethod("runSuCommand", String::class.java)
                m.isAccessible = true
                (m.invoke(this, command) as? Boolean) == true
            }
        }.getOrDefault(false)
    }

    private fun runExecutorSuWithOutput(command: String): String? {
        return runCatching {
            executor.run {
                val m = javaClass.getDeclaredMethod("runSuCommandWithOutput", String::class.java)
                m.isAccessible = true
                (m.invoke(this, command) as? String)?.trim()
            }
        }.getOrNull()
    }

    private fun queryTikTokProcessIds(
        packageName: String,
        runSuWithOutput: (String) -> String? = ::runExecutorSuWithOutput,
    ): String {
        val pkg = packageName.trim().ifBlank { com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME }
        return runSuWithOutput(DeviceBackupShell.listPackageProcessPidsCommand(pkg))?.trim().orEmpty()
    }

    /**
     * force-stop 目标包并按 ps 进程名确认已退出（不用 pgrep -f，避免误判 am force-stop 的 shell）。
     * [aggressiveForCutSwitch] 为 true 时多轮 am force-stop + 补充 kill -9，尽量清掉 :push 等残留进程。
     * @return null 表示成功；非 null 为失败原因（已写实时日志）
     */
    private suspend fun forceStopTikTokPackageAndVerify(
        packageName: String,
        alias: String,
        phaseLabel: String,
        settleMs: Long = TIKTOK_FORCE_STOP_SETTLE_MS,
        aggressiveForCutSwitch: Boolean = false,
        runSu: (String) -> Boolean = ::runExecutorSu,
        runSuWithOutput: (String) -> String? = ::runExecutorSuWithOutput,
    ): String? {
        val pkg = packageName.trim().ifBlank { com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME }
        val stopCmd = DeviceBackupShell.forceStopCommand(pkg)
        val killCmd = DeviceBackupShell.supplementalKillCommand(pkg)
        val maxRounds = if (aggressiveForCutSwitch) {
            CUT_SWITCH_FORCE_STOP_MAX_ROUNDS
        } else {
            2
        }
        val roundSettleMs = if (aggressiveForCutSwitch) {
            CUT_SWITCH_FORCE_STOP_ROUND_SETTLE_MS
        } else {
            settleMs
        }

        if (aggressiveForCutSwitch && PrefsStore.isAutoSwitchAutonomousMode()) {
            PrefsStore.setAutoSwitchExclusivePreempt(true)
        }

        fun killRound(): Boolean {
            if (!runSu(stopCmd)) return false
            runSu(killCmd)
            return true
        }

        for (round in 1..maxRounds) {
            if (!killRound()) {
                val msg = "am force-stop 失败（$pkg）"
                LogCenter.op(alias, "【切号·关TikTok】$phaseLabel：$msg")
                return msg
            }
            delay(roundSettleMs)
            val pids = queryTikTokProcessIds(pkg, runSuWithOutput)
            if (pids.isEmpty()) return null
            if (round < maxRounds) {
                LogCenter.op(
                    alias,
                    "【切号·关TikTok】$phaseLabel：第${round}轮后仍有进程 pid=$pids，继续强停…",
                )
            } else {
                val msg = "force-stop ${maxRounds}轮后仍有进程 pid=$pids（$pkg）"
                LogCenter.op(alias, "【切号·关TikTok】$phaseLabel：$msg")
                return msg
            }
        }
        return null
    }

    /** 自治切号/发作品入队前：暂停涨粉并 force-stop TikTok，避免备份 tar 时 TikTok 仍写数据目录。 */
    private suspend fun forceStopTikTokForExclusiveEnqueue(phaseLabel: String) {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isBlank()) return
        if (PublishPostGrowthGate.shouldDeferExclusivePreempt()) {
            LogCenter.op(alias, "【自动切号】发作品独占发布中，跳过 force-stop TikTok（$phaseLabel）")
            return
        }
        val pkg = AutoSwitchBundleHolder.getBundle()?.run_config?.package_name?.trim()
            .orEmpty()
            .ifBlank { com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME }
        val fail = forceStopTikTokPackageAndVerify(
            pkg,
            alias,
            phaseLabel,
            aggressiveForCutSwitch = PrefsStore.isAutoSwitchAutonomousMode(),
        )
        if (fail != null) {
            LogCenter.op(
                alias,
                "【自动切号】$phaseLabel TikTok 未能确认完全退出（$pkg）：$fail，继续后续入队流程",
            )
        }
    }

    /** 打开 App 或云端登录成功后调用：检测 TikTok 图库/通知/联系人权限未开放则 root 授予。 */
    fun ensureTikTokGalleryPermissionIfNeeded() {
        if (PrefsStore.getAuthToken().isNullOrBlank()) return
        scope.launch {
            ensureTikTokGalleryPermissionGrantedIfNeeded()
        }
    }

    private fun tiktokGalleryRuntimePermissions(): List<String> {
        val mediaPerms =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                )
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        val base = mediaPerms + listOf(
            Manifest.permission.READ_CONTACTS,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base + Manifest.permission.POST_NOTIFICATIONS
        } else {
            base
        }
    }

    private fun isTikTokGalleryPermissionGranted(): Boolean {
        val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
        val pm = appContext.packageManager
        return tiktokGalleryRuntimePermissions().all { perm ->
            pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 图库授权等 root 操作前：多方案强停 TikTok 并等待进程收尾，避免后台残留触发系统授权弹窗。 */
    private suspend fun forceStopTikTokCleanly(pkg: String, alias: String, reason: String) {
        LogCenter.op(alias, "$reason：先关闭 TikTok 后台…")
        runExecutorSu(DeviceBackupShell.forceStopCommand(pkg))
        runExecutorSu(DeviceBackupShell.supplementalKillCommand(pkg))
        val waitMs = _config.value.tiktok_force_stop_wait_ms.coerceIn(100L, 8000L)
        delay(waitMs)
        LogCenter.op(alias, "$reason：TikTok 后台已关闭（等待 ${waitMs}ms）")
    }

    /**
     * 已登录云端时：检测 TikTok 图库/通知/联系人 runtime 权限，未开放则 root 授予，避免发布流程卡在系统授权弹窗。
     */
    private suspend fun ensureTikTokGalleryPermissionGrantedIfNeeded() {
        if (PrefsStore.getAuthToken().isNullOrBlank()) return
        ProcessDiagnostics.setPhase("TikTok权限检测/授予")
        val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
        val alias = PrefsStore.getAlias()?.takeIf { it.isNotBlank() } ?: "SYSTEM"
        val packageInstalled = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrElse { err ->
            if (err is PackageManager.NameNotFoundException) {
                LogCenter.op(alias, "TikTok 未安装，跳过图库/通知/联系人权限检测")
            } else {
                LogCenter.op(alias, "读取 TikTok 安装信息失败：${err.message}")
            }
            false
        }
        if (!packageInstalled) return
        if (isTikTokGalleryPermissionGranted()) return

        forceStopTikTokCleanly(pkg, alias, "TikTok 图库/通知/联系人权限未开放")
        LogCenter.op(alias, "TikTok 图库/通知/联系人权限未开放，开始 root 授予…")
        val commands = buildList {
            add("pm grant $pkg android.permission.READ_MEDIA_IMAGES")
            add("pm grant $pkg android.permission.READ_MEDIA_VIDEO")
            add("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
            add("pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE")
            add("pm grant $pkg android.permission.READ_CONTACTS")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            }
            add("appops set $pkg READ_MEDIA_IMAGES allow")
            add("appops set $pkg READ_MEDIA_VIDEO allow")
            add("appops set $pkg READ_EXTERNAL_STORAGE allow")
            add("appops set $pkg WRITE_EXTERNAL_STORAGE allow")
            add("appops set $pkg LEGACY_STORAGE allow")
            add("appops set $pkg READ_CONTACTS allow")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("appops set $pkg POST_NOTIFICATION allow")
            }
        }
        var failed = 0
        val failLogs = mutableListOf<String>()
        for (command in commands) {
            val ok = runExecutorSu(command)
            if (!ok) {
                failed++
                val output = runExecutorSuWithOutput("$command 2>&1 || true").orEmpty().take(220)
                failLogs += "$command | ${if (output.isBlank()) "no output" else output}"
            }
        }
        if (failed == 0) {
            LogCenter.op(alias, "TikTok 图库/通知/联系人权限开放完成")
        } else {
            LogCenter.op(alias, "TikTok 图库/通知/联系人权限开放存在失败：$failed/${commands.size}")
            failLogs.take(4).forEach { item ->
                LogCenter.op(alias, "TikTok 权限开放失败命令：$item")
            }
            if (failLogs.size > 4) {
                LogCenter.op(alias, "TikTok 权限开放失败明细省略 ${failLogs.size - 4} 条")
            }
        }
    }

    suspend fun syncOnline(retries: Int = 1) {
        syncStatusToCloud("online", retries)
    }

    /** 启动/恢复运行后向云端同步 running，不把本地状态降回 online。 */
    private suspend fun syncRunningToCloud(retries: Int = 3) {
        syncStatusToCloud("running", retries)
    }

    private enum class CloudStatusSyncKind {
        /** 常规轮询/独占 OP 暂停恢复等（自治期全部本地处理） */
        ROUTINE,
        /** 生命周期事件（自治期全部本地处理，退出后 flush） */
        LIFECYCLE,
        /** 空档涨粉恢复（自治期全部本地处理） */
        GROWTH,
    }

    private suspend fun syncCloudStatus(
        status: String,
        @Suppress("UNUSED_PARAMETER") kind: CloudStatusSyncKind = CloudStatusSyncKind.ROUTINE,
        retries: Int = 1,
    ) {
        syncStatusToCloud(status, retries)
    }

    private suspend fun reportAutoStartOptOutIfNeeded(deviceId: String, alias: String, optOut: Boolean) {
        if (PrefsStore.isAutoSwitchAutonomousMode()) return
        runCatching {
            ApiService.reportDeviceAutoStartOptOut(deviceId = deviceId, alias = alias, optOut = optOut)
        }
    }

    /** 自治期结束后，将本地状态一次性同步到云端。 */
    private suspend fun flushCloudStatusAfterAutonomousExit() {
        syncStatusToCloud(PrefsStore.getLocalStatus(), retries = 2, forceCloudSync = true)
    }

    private suspend fun syncStatusToCloud(
        status: String,
        retries: Int = 1,
        forceCloudSync: Boolean = false,
    ) {
        val deviceId = PrefsStore.getDeviceId() ?: return
        val alias = PrefsStore.getAlias() ?: return
        if (PrefsStore.isAutoSwitchAutonomousMode() && !forceCloudSync) {
            PrefsStore.saveLocalStatus(status)
            updateRuntime(localStatus = status)
            return
        }
        if (status == PrefsStore.getLastSyncedCloudStatus()) {
            return
        }
        val attempts = retries.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            runCatching {
                val response = ApiService.syncStatus(deviceId, alias, status)
                PrefsStore.saveLocalStatus(status)
                PrefsStore.saveLastSyncedCloudStatus(status)
                LogCenter.op(
                    alias,
                    if (status == "running") "已与云端同步：任务运行中" else "已与云端同步：设备在线",
                )
                updateRuntime(
                    localStatus = status,
                    lastMessage = response.msg,
                    currentServerUrl = ApiService.getBaseUrl(),
                )
                return
            }.onFailure { error ->
                LogCenter.op(
                    alias,
                    "同步 $status 失败 (${attempt + 1}/$attempts)：${error.message}",
                )
                if (attempt < attempts - 1) {
                    delay(800L * (attempt + 1))
                }
            }
        }
    }

    suspend fun fetchConfig(
        configQueryRunMode: String? = null,
        logToUser: Boolean = false,
        bindingRecoveryAttempted: Boolean = false,
    ): Boolean {
        if (!PrefsStore.isBound() && !bindingRecoveryAttempted) {
            BindingRecovery.tryRecoverFromCloud(appContext)
        }
        val deviceId = PrefsStore.getDeviceId() ?: return false
        val alias = PrefsStore.getAlias() ?: return false
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            if (isAutoSwitchBundledConfigSynced()) {
                return true
            }
            if (applyBundledConfigFromAutoSwitch(PrefsStore.getLogContextUsername(), logToUser)) {
                return true
            }
            if (logToUser) {
                LogCenter.op(alias, "自治期仅使用 enter bundle 配置；bundle 不可用，保留当前设置")
            }
            updateRuntime(
                configLoaded = _config.value.run_mode.isNotBlank(),
                lastMessage = "自治期 bundle 配置不可用，已保留当前设置",
            )
            return _config.value.run_mode.isNotBlank()
        }
        val modeForRequest = configQueryRunMode?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val tiktokAccount = PrefsStore.getLogContextUsername()
        return runCatching {
            ApiService.getConfig(deviceId, alias, modeForRequest, tiktokAccount)
        }.fold(
            onSuccess = { response ->
                if (
                    !bindingRecoveryAttempted &&
                    BindingRecovery.looksLikeBindingAuthError(response.code, response.msg) &&
                    BindingRecovery.tryRecoverFromCloud(appContext)
                ) {
                    return fetchConfig(configQueryRunMode, logToUser, bindingRecoveryAttempted = true)
                }
                if (response.code == 0 && response.config != null) {
                    val previousRunMode = queueRunMode()
                    val effective = response.config.copy(
                        run_mode = response.run_mode ?: response.config.run_mode,
                    )
                    val newRunMode = effective.run_mode.trim().lowercase().ifBlank { ServerRunMode.GROWTH }
                    if (newRunMode == ServerRunMode.GROWTH && newRunMode != previousRunMode) {
                        growthModeLoginSafeguardPending = true
                    } else if (newRunMode != ServerRunMode.GROWTH) {
                        growthModeLoginSafeguardPending = false
                    }
                    _config.value = effective
                    val templateLabel = prettyTemplateName(response.active_template)
                    if (logToUser) {
                        LogCenter.op(
                            alias,
                            "任务设置已更新：${configSummaryLine(effective.run_mode, templateLabel)}",
                        )
                        if (effective.no_rest) {
                            LogCenter.op(alias, "本模板已开启「不休息」：设备永不进入云端批次休息")
                        }
                    }
                    updateRuntime(
                        configLoaded = true,
                        effectiveRunMode = effective.run_mode,
                        effectiveTemplate = templateLabel,
                        lastMessage = "任务设置：${configSummaryLine(effective.run_mode, templateLabel)}",
                    )
                    true
                } else {
                    if (logToUser) {
                        LogCenter.op(alias, "任务设置同步失败，继续使用上次的 ${prettyRunMode(_config.value.run_mode)} 设置")
                    }
                    updateRuntime(configLoaded = false, lastMessage = "配置拉取失败，已保留上次配置")
                    false
                }
            },
            onFailure = { err ->
                if (logToUser) {
                    LogCenter.op(alias, "任务设置同步异常，继续使用上次设置：${err.message}")
                }
                updateRuntime(
                    configLoaded = false,
                    lastMessage = "配置拉取异常，已保留上次配置",
                )
                false
            },
        )
    }

    /** 自治期 bundle 配置是否已与当前登录账号、内存 bundle 对齐（避免默认 growth 误判已同步）。 */
    private fun isAutoSwitchBundledConfigSynced(): Boolean {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        if (AutoSwitchBundleHolder.getBundle() == null) return false
        val bundleRev = PrefsStore.getAutoSwitchBundleRevision()
        val lastFetchRev = PrefsStore.getAutoSwitchLastConfigFetchRevision()
        if (bundleRev < 0 || bundleRev != lastFetchRev) return false
        if (_config.value.run_mode.isBlank()) return false
        val lastBundled = PrefsStore.getAutoSwitchLastBundledUsername().orEmpty()
        val current = PrefsStore.getLogContextUsername().orEmpty()
        if (lastBundled.isBlank() || current.isBlank()) return false
        return lastBundled.equals(current, ignoreCase = true)
    }

    /** 自治 enter 后从 bundle.configs_by_username 载入 trace 配置，切号时按账号切换。 */
    private fun applyBundledConfigFromAutoSwitch(
        username: String? = null,
        logToUser: Boolean = false,
    ): Boolean {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        val bundle = AutoSwitchBundleHolder.getBundle() ?: return false
        val bundled = bundle.resolveBundledConfigForUsername(username) ?: return false
        val cfgBody = bundled.config ?: return false
        val accountKey = username?.trim().orEmpty()
            .ifBlank { PrefsStore.getLogContextUsername().orEmpty() }
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val mode = bundle.run_config?.run_mode?.trim()?.lowercase().orEmpty()
            .ifBlank { cfgBody.run_mode.trim().lowercase().ifBlank { ServerRunMode.GROWTH } }
        val effective = cfgBody.copy(run_mode = mode)
        val previousRunMode = queueRunMode()
        if (mode == ServerRunMode.GROWTH && mode != previousRunMode) {
            growthModeLoginSafeguardPending = true
        } else if (mode != ServerRunMode.GROWTH) {
            growthModeLoginSafeguardPending = false
        }
        _config.value = effective
        val templateLabel = prettyTemplateName(
            bundled.active_template?.trim().orEmpty().ifBlank { bundle.run_config?.active_template },
        )
        if (logToUser && alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "任务设置已更新（bundle）：${configSummaryLine(effective.run_mode, templateLabel)}",
            )
            if (effective.no_rest) {
                LogCenter.op(alias, "本模板已开启「不休息」：设备永不进入云端批次休息")
            }
        }
        updateRuntime(
            configLoaded = true,
            effectiveRunMode = effective.run_mode,
            effectiveTemplate = templateLabel,
            lastMessage = "任务设置（bundle）：${configSummaryLine(effective.run_mode, templateLabel)}",
        )
        PrefsStore.setAutoSwitchLastConfigFetchRevision(
            PrefsStore.getAutoSwitchBundleRevision().coerceAtLeast(0),
        )
        val appliedAccount = accountKey.ifBlank { PrefsStore.getLogContextUsername().orEmpty() }
        if (appliedAccount.isNotBlank()) {
            PrefsStore.setAutoSwitchLastBundledUsername(appliedAccount)
        }
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            val activityAccount = PrefsStore.getLogContextUsername()?.trim().orEmpty()
                .ifBlank { accountKey }
            AutoSwitchAccountActivityTracker.onConfigApplied(
                username = activityAccount.ifBlank { null },
                template = bundled.active_template?.trim().orEmpty().ifBlank { bundle.run_config?.active_template },
                config = effective,
            )
        }
        return true
    }

    fun startCheckLoop() {
        if (startPollJob?.isActive == true) {
            return
        }

        if (modeWaitJob?.isActive != true) {
            startModeWaitLoop()
        }
        if (!PrefsStore.isAutoSwitchAutonomousMode() && deviceOpsJob?.isActive != true) {
            startDeviceOpsLoop()
        }

        startPollJob = scope.launch {
            while (true) {
                var deviceId = PrefsStore.getDeviceId()
                var alias = PrefsStore.getAlias()
                if (deviceId.isNullOrBlank() || alias.isNullOrBlank()) {
                    if (BindingRecovery.tryRecoverFromCloud(appContext)) {
                        deviceId = PrefsStore.getDeviceId()
                        alias = PrefsStore.getAlias()
                    }
                }
                if (deviceId.isNullOrBlank() || alias.isNullOrBlank()) {
                    delay(10_000)
                    continue
                }

                if (PrefsStore.isAutoSwitchAutonomousMode()) {
                    val growthHint =
                        if (pullLoopJob?.isActive == true) "空档涨粉中" else "调度中"
                    updateRuntime(lastMessage = "自动切号自治：$growthHint")
                    // 开关对账仅由 mode_wait 携带的云端真源触发；此处勿用本地缓存硬判 false。
                    maybeRunDailyAutoSwitchReconcile(alias, deviceId)
                    delay(60_000)
                    continue
                }

                val localStatus = PrefsStore.getLocalStatus()
                // 降低 check_start 轮询频率，避免和后端锁竞争叠加。
                val intervalMs = if (localStatus == "running") 30_000L else 20_000L

                try {
                    val response = ApiService.checkStart(deviceId, alias)
                    uploadDeviceLogsIfRequested(alias, deviceId, response)
                    val pushRev = response.config_push_revision
                    if (pushRev != null && pushRev > PrefsStore.getLastAppliedConfigPushRevision()) {
                        LogCenter.op(alias, "管理台更新了任务设置，正在同步…")
                        val ok = fetchConfig(logToUser = true)
                        if (ok) {
                            PrefsStore.saveLastAppliedConfigPushRevision(pushRev)
                        }
                    }
                    if (response.code == 0) {
                        val cloudAutoStart = response.auto_start == true
                        updateRuntime(cloudAutoStart = cloudAutoStart)
                        if (ManualDeviceControl.ENABLED) {
                            // 手动模式：仅同步配置，忽略云端 running / paused 对涨粉的拉起与打断。
                            if (PrefsStore.getLocalStatus() == "paused" && !PrefsStore.isHardStopRequested()) {
                                PrefsStore.saveLocalStatus("online")
                                updateRuntime(localStatus = "online", lastMessage = "手动模式：等待按钮触发任务")
                            }
                        } else if (response.status == "paused") {
                            /* 用户硬停后服务端仍为 paused，但管理员已重新允许自动开启：视为新指令，解除硬停并恢复。 */
                            if (
                                PrefsStore.isHardStopRequested() &&
                                cloudAutoStart &&
                                !inPullUsernameRestSleep &&
                                !PrefsStore.isWithinHardStopGracePeriod()
                            ) {
                                LogCenter.op(
                                    alias,
                                    "云端已重新允许自动开启（check_start 仍为 paused），解除硬停并恢复运行",
                                )
                                forceStartRunningNow("云端已重新允许自动开启，解除硬停")
                            } else if (
                                PrefsStore.isPublishPostPending() ||
                                PrefsStore.isMutualClickPending() ||
                                ExecutionScope.isActive()
                            ) {
                                /* 发作品/互点等设备指令执行中：不因 check_start 的 paused 覆盖 running */
                                if (PrefsStore.getLocalStatus() != "running") {
                                    PrefsStore.saveLocalStatus("running")
                                    updateRuntime(localStatus = "running", lastMessage = "云端任务执行中")
                                }
                            } else if (
                                cloudAutoStart &&
                                !shouldHonorUserHardStop() &&
                                !manuallyPaused &&
                                !inPullUsernameRestSleep &&
                                PrefsStore.isTransientPausedForDestructiveOp() &&
                                PrefsStore.getLocalStatus() == "running"
                            ) {
                                /* 切号/备份已恢复 running 并 sync，云端 status 可能仍为 paused：勿打回暂停 */
                            } else if (
                                cloudAutoStart &&
                                !shouldHonorUserHardStop() &&
                                !manuallyPaused &&
                                !inPullUsernameRestSleep &&
                                PrefsStore.isTransientPausedForDestructiveOp()
                            ) {
                                LogCenter.op(
                                    alias,
                                    "切号/备份临时暂停已结束，按云端自动开启恢复涨粉（check_start 仍为 paused）",
                                )
                                forceStartRunningNow("切号/备份收尾完成，解除临时暂停")
                            } else {
                                PrefsStore.setTransientPausedForDestructiveOp(false)
                                val alreadyPaused = PrefsStore.getLocalStatus() == "paused"
                                PrefsStore.saveLocalStatus("paused")
                                syncCloudStatus("paused", CloudStatusSyncKind.LIFECYCLE)
                                if (!alreadyPaused) {
                                    LogCenter.op(alias, "收到暂停指令")
                                    updateRuntime(localStatus = "paused", lastMessage = "已暂停，等待继续")
                                }
                            }
                        } else {
                            if (shouldHonorUserHardStop() && PrefsStore.isWithinHardStopGracePeriod()) {
                                if (PrefsStore.getLocalStatus() != "paused") {
                                    PrefsStore.saveLocalStatus("paused")
                                }
                                runCatching { syncCloudStatus("paused", CloudStatusSyncKind.LIFECYCLE) }
                                LogCenter.op(alias, "本地硬停中：忽略云端「运行中」指令，保持暂停")
                            } else {
                                if (shouldHonorUserHardStop()) {
                                    PrefsStore.setHardStopRequested(false)
                                }
                                manuallyPaused = false
                                if (!started.get()) {
                                    started.set(true)
                                }
                                PrefsStore.saveLocalStatus("running")
                                syncStatusToCloud("running")
                                LogCenter.op(alias, "管理台已下发「开始运行」，涨粉/任务即将启动")
                                updateRuntime(localStatus = "running", lastMessage = "收到启动/继续指令")
                                if (!WorkerServiceTracker.isRunning) {
                                    runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "check_start_resume") }
                                        .onFailure {
                                            LogCenter.op(alias, "收到启动指令后恢复后台服务失败：${it.message}")
                                        }
                                }
                                fetchConfig(logToUser = true)
                                startPullLoop()
                            }
                        }
                    } else if (response.code == 1 && response.auto_start == true) {
                        updateRuntime(cloudAutoStart = true)
                        if (ManualDeviceControl.ENABLED) {
                            // 手动模式：忽略云端 auto_start
                        } else if (manuallyPaused) {
                            // 悬浮硬暂停：不响应云端自动拉起
                        } else if (shouldHonorUserHardStop() && PrefsStore.isWithinHardStopGracePeriod()) {
                            LogCenter.op(alias, "本地硬停中：忽略云端 auto_start（等待本机关闭自动开启生效）")
                        } else if (shouldHonorUserHardStop()) {
                            LogCenter.op(alias, "云端已重新允许自动开启，解除硬停")
                            forceStartRunningNow("云端已重新允许自动开启，解除硬停")
                        } else if (inPullUsernameRestSleep) {
                            // 休息窗口内不触发自动恢复，避免与休息意图矛盾
                        } else {
                            val ls = PrefsStore.getLocalStatus()
                            if (ls == "paused") {
                                // 云端暂停且非硬停：不自动拉起
                            } else {
                                val needWake =
                                    !WorkerServiceTracker.isRunning ||
                                        ls != "running"
                                if (needWake) {
                                    val why = when {
                                        !WorkerServiceTracker.isRunning -> "后台服务未运行"
                                        ls != "running" -> "任务未在运行中"
                                        else -> "需要恢复"
                                    }
                                    LogCenter.op(alias, "自动运行已开启，正在恢复任务（$why）")
                                    forceStartRunningNow("云端自动开启策略")
                                }
                            }
                        }
                    } else if (response.code == 1 && localStatus == "running") {
                        // code=1 且 auto_start!=true：明确当前未开启云端自动起号策略（保持当前运行不做拉起/停止）
                        updateRuntime(cloudAutoStart = false)
                    } else if (response.code == 1) {
                        // code=1 且 auto_start!=true：明确当前未开启云端自动起号策略
                        updateRuntime(cloudAutoStart = false)
                    }
                } catch (error: Exception) {
                    LogCenter.op(alias, "轮询启动指令失败：${error.message}")
                    updateRuntime(lastMessage = "检查启动指令失败")
                }

                delay(intervalMs)
            }
        }
    }


    private fun startPullLoop() {
        // 即使 pull 已在跑也要清遗留 preempt（否则会空转 abort，永远不拉号）
        releaseStaleManualExclusivePreemptIfIdle("启动涨粉拉取循环")
        if (pullLoopJob?.isActive == true) {
            return
        }
        if (!canStartPullLoopNow()) {
            val alias = PrefsStore.getAlias()
            if (alias != null) {
                val reason =
                    when {
                        basicProfileEditRunning -> "修改基本资料"
                        PrefsStore.isAutoSwitchAutonomousMode() &&
                            autoSwitchLocalScheduler.shouldBlockGrowthPull() ->
                            "自治切号/发作品占用"
                        PublishPostGrowthGate.isBlockingGrowth() -> "发作品独占发布"
                        isPublishPostSwitchActive() -> "发作品素材准备"
                        isMutualSwitchActive() -> "互点切换"
                        else -> "独占任务"
                    }
                LogCenter.op(alias, "涨粉拉取循环暂缓：$reason 仍在执行")
            }
            return
        }

        pullLoopJob = scope.launch {
            while (true) {
                if (manuallyPaused) {
                    updateRuntime(lastMessage = "已暂停（悬浮控制）")
                    delay(600)
                    continue
                }

                if (shouldHonorUserHardStop()) {
                    updateRuntime(lastMessage = "硬停中：自动任务已暂停，仍监听云端指令")
                    delay(2_000)
                    continue
                }
                if (PrefsStore.getLocalStatus() != "running") {
                    /* 与云端「暂停」或用户硬停后的状态一致：不继续拉取/执行用户名队列 */
                    delay(2_000)
                    continue
                }

                val deviceId = PrefsStore.getDeviceId() ?: break
                val alias = PrefsStore.getAlias() ?: break

                if (queueRunMode() == ServerRunMode.MUTUAL) {
                    runMutualTrafficPullLoopIteration(alias, deviceId)
                    continue
                }

                // 手动模式：未就绪时不自动登录；须先切号（有备份还原 / 无备份自动登录）或点「自动登录」重试。
                if (ManualDeviceControl.ENABLED) {
                    val currentUser = PrefsStore.getLogContextUsername()?.trim().orEmpty()
                        .ifBlank { PrefsStore.getManualSelectedUsername().orEmpty() }
                    if (currentUser.isBlank() || !PrefsStore.isManualFirstLoginDone(currentUser)) {
                        updateRuntime(lastMessage = "手动模式：请先切号完成（有备份还原 / 无备份自动登录）")
                        delay(2_000)
                        continue
                    }
                } else if (!isAssignedAccountLoggedIn(alias, deviceId)) {
                    // 未登录：仅自动登录，不拉取用户名、不执行任务、不进入休息
                    if (PublishPostGrowthGate.isBlockingGrowth()) {
                        delay(2_000)
                        continue
                    }
                    updateRuntime(lastMessage = "未登录，仅自动登录")
                    ensureAccountLoggedInWithRetry(alias, deviceId, "涨粉")
                    continue
                }

                if (!ensureAssignedAccountReadyForGrowthPull(alias, deviceId)) {
                    delay(2_000)
                    continue
                }

                if (pendingUsernames.isEmpty() && !shouldAbortPullExecution()) {
                    reportOnlineBeforePull(alias, deviceId)
                    fetchUsernamesOnce(alias, deviceId)
                }

                if (shouldAbortPullExecution()) {
                    delay(2_000)
                    continue
                }

                val queueBeforeTake = pendingUsernames.size
                val username = pendingUsernames.firstOrNull()
                if (username != null) {
                    pendingUsernames.removeAt(0)
                    LogCenter.op(alias, "执行：$username（余 ${pendingUsernames.size}）")
                    handleUsername(alias, deviceId, username)
                } else {
                    updateRuntime(lastMessage = "暂无待执行用户名")
                    LogCenter.op(alias, "队列为空，进入等待/补拉分支")
                    logFailedSummaryIfAny(alias)
                }

                if (shouldAbortPullExecution()) {
                    delay(2_000)
                    continue
                }

                if (pendingUsernames.size <= 1) {
                    reportOnlineBeforePull(alias, deviceId)
                    fetchUsernamesOnce(alias, deviceId)
                    LogCenter.op(alias, "补拉用户名后队列剩余 ${pendingUsernames.size}")
                }

                // 同一批用户名连续执行：队列里有任务时不做任何间隔
                if (pendingUsernames.isNotEmpty()) {
                    LogCenter.op(alias, "队列仍有${pendingUsernames.size}个用户名，立即进入下一轮")
                    continue
                }

                // 仅当队列为空时，等待后再拉下一批
                val interval = Random.nextLong(30_000L, 60_000L)
                if (forceImmediatePull) {
                    forceImmediatePull = false
                    LogCenter.op(alias, "强制立即拉取已触发，跳过等待")
                } else {
                    LogCenter.op(alias, "任务队列为空，${interval / 1000} 秒后再拉取用户名")
                    logFailedSummaryIfAny(alias)
                    updateRuntime(lastMessage = "等待用户名拉取")
                    if (!delayUnlessHardStop(interval)) {
                        LogCenter.op(alias, "硬停：已中断拉取等待")
                    }
                }
            }
        }
    }

    /** 互点模式独立循环：不拉涨粉用户名队列，仅周期性执行 teamwork 引流会话。 */
    private suspend fun runMutualTrafficPullLoopIteration(alias: String, deviceId: String) {
        if (!isMutualSwitchActive() && !isPublishPostSwitchActive()) {
            reportOnlineBeforePull(alias, deviceId)
            fetchConfig(configQueryRunMode = ServerRunMode.MUTUAL)
            val followOnly = _config.value.mutual_traffic_follow_only
            PrefsStore.setMutualCommentOnlySession(false)
            PrefsStore.setMutualFollowOnlySession(followOnly)
            PrefsStore.setMutualClickRequested(true)
            PrefsStore.setMutualClickPending(true)
            triggerMutualSwitchNow(alias)
        }
        if (isMutualSwitchActive() || isPublishPostSwitchActive()) {
            delay(2_000)
            return
        }
        val interval = Random.nextLong(30_000L, 60_000L)
        if (forceImmediatePull) {
            forceImmediatePull = false
            LogCenter.op(alias, "互点模式：强制立即开始下一轮引流")
        } else {
            LogCenter.op(alias, "互点模式：${interval / 1000} 秒后开始下一轮引流")
            updateRuntime(lastMessage = "互点模式等待下一轮引流")
            if (!delayUnlessHardStop(interval)) {
                LogCenter.op(alias, "硬停：已中断互点模式等待")
            }
        }
    }

    private suspend fun fetchUsernamesOnce(alias: String, deviceId: String) {
        try {
            if (!isAssignedAccountLoggedIn(alias, deviceId)) {
                LogCenter.op(alias, "未登录，跳过拉取用户名（仅自动登录）")
                updateRuntime(lastMessage = "未登录，仅自动登录")
                return
            }
            // 配置与批次拉取绑定：每次拉一批用户名前先拉一次最新配置。
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                applyBundledConfigFromAutoSwitch(
                    PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() } ?: alias,
                    logToUser = false,
                )
            } else {
                fetchConfig()
            }
            val pullN = _config.value.pull_batch_size.let { if (it < 1) 5 else it }.coerceIn(1, 100)
            val accountForPull = PrefsStore.getLogContextUsername()
            var response = pullUsernameForGrowth(deviceId, alias, pullN, queueRunMode(), accountForPull)
            if (response.code == 2 && _config.value.no_rest) {
                LogCenter.op(
                    alias,
                    "模板已开启「不休息」：忽略云端休息指令（code=2），立即重新拉取用户名",
                )
                updateRuntime(lastMessage = "不休息模式：已跳过云端休息")
                response = pullUsernameForGrowth(deviceId, alias, pullN, queueRunMode(), accountForPull)
                if (response.code == 2) {
                    LogCenter.op(
                        alias,
                        "不休息模式：服务端仍返回休息指令，本机拒绝执行休息（请确认服务端已更新）",
                    )
                    forceImmediatePull = true
                    return
                }
            }
            if (response.code == 2) {
                val minutes = (response.rest_minutes ?: 0).coerceAtLeast(1)
                val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
                LogCenter.op(
                    alias,
                    "云端让设备休息 ${minutes} 分钟：涨粉/任务暂停，已关闭 TikTok，到点后会自动继续",
                )
                updateRuntime(lastMessage = "休息中 ${minutes} 分钟，涨粉暂停")

                val runSu: (String) -> Boolean = { command ->
                    runCatching {
                        executor.run {
                            val m = javaClass.getDeclaredMethod("runSuCommand", String::class.java)
                            m.isAccessible = true
                            (m.invoke(this, command) as? Boolean) == true
                        }
                    }.getOrDefault(false)
                }
                runSu("am force-stop $pkg")
                inPullUsernameRestSleep = true
                MainActivity.navigateToLogPanel(appContext)
                try {
                    val restMs = minutes.toLong() * 60_000L
                    if (!delayUnlessHardStop(restMs)) {
                        LogCenter.op(alias, "硬停：已中断云端休息等待")
                    }
                } finally {
                    inPullUsernameRestSleep = false
                }
                LogCenter.op(alias, "休息结束，继续涨粉：即将拉取新用户")
                updateRuntime(lastMessage = "休息结束，继续涨粉")
                forceImmediatePull = true
                return
            }
            val incoming = response.usernames.orEmpty()
            if (response.code == 0 && incoming.isNotEmpty()) {
                val pendingFromBatch = PrefsStore.filterPendingFromLastBatch(incoming)
                PrefsStore.savePulledBatch(incoming)
                pendingUsernames.addAll(pendingFromBatch)
                pulledTracker.addAll(incoming)
                LogCenter.op(
                    alias,
                    "已拉到 ${pendingFromBatch.size} 个待执行用户（本批 ${incoming.size} 个，队列共 ${pendingUsernames.size} 个）",
                )
                if (pendingFromBatch.isEmpty()) {
                    LogCenter.op(alias, "本批用户都已做过或重复，没有新的可执行用户")
                }
            } else {
                val statusMsg = growthPullEmptyStatusMessage(response)
                LogCenter.op(
                    alias,
                    statusMsg.takeIf { it.isNotBlank() }
                        ?: "暂时没有新的用户可执行，稍后再试",
                )
                updateRuntime(lastMessage = statusMsg)
            }
        } catch (error: Exception) {
            if (error is CancellationException || isPullLoopJobCancellation(error)) {
                val reason =
                    when {
                        PrefsStore.getAutoSwitchMode() == "exiting" -> "自动切号关闭"
                        PrefsStore.isAutoSwitchExclusivePreempt() -> "独占操作（发作品/切号）"
                        else -> "任务切换"
                    }
                LogCenter.op(alias, "涨粉拉取被${reason}打断（预期行为）")
                throw if (error is CancellationException) {
                    error
                } else {
                    CancellationException(error.message ?: "cancelled")
                }
            }
            LogCenter.op(alias, "拉取用户名失败：${error.message}")
            updateRuntime(lastMessage = "用户名拉取失败")
        }
    }

    private suspend fun reportOnlineBeforePull(alias: String, deviceId: String) {
        if (shouldAbortPullExecution()) {
            return
        }
        try {
            // 拉取循环内本地已是 running，尽量向服务端同步为 running（自治期失败不阻断本机调度）。
            syncCloudStatus("running", CloudStatusSyncKind.GROWTH)
            if (!PrefsStore.isAutoSwitchAutonomousMode()) {
                updateRuntime(lastMessage = "拉取前已同步运行状态")
            }
        } catch (error: Exception) {
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                LogCenter.op(alias, "自治涨粉：拉取前同步 running 失败（不阻断本机拉取）：${error.message}")
            } else {
                LogCenter.op(alias, "同步运行状态失败：${error.message}")
                updateRuntime(lastMessage = "拉取前同步状态失败")
            }
        }
    }

    private fun isLegacyCloudNotStartedPullMsg(msg: String?): Boolean =
        msg?.contains("设备未启动") == true || msg?.contains("一键启动") == true

    /** 自治涨粉：本机调度为准；忽略云端陈旧「未启动」门闩并重试一次。 */
    private suspend fun pullUsernameForGrowth(
        deviceId: String,
        alias: String,
        pullN: Int,
        runMode: String,
        accountForPull: String?,
    ): PullUsernameResponse {
        var response = ApiService.pullUsername(deviceId, alias, pullN, runMode, accountForPull)
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return response
        if (response.code == 0 || !isLegacyCloudNotStartedPullMsg(response.msg)) return response
        LogCenter.op(alias, "自治涨粉：忽略云端「未启动」陈旧提示，同步 running 后重试拉取")
        runCatching { syncCloudStatus("running", CloudStatusSyncKind.GROWTH) }
        response = ApiService.pullUsername(deviceId, alias, pullN, runMode, accountForPull)
        if (isLegacyCloudNotStartedPullMsg(response.msg)) {
            LogCenter.op(alias, "自治涨粉：服务端仍返回「未启动」，本机继续自治调度（不视为致命错误）")
        }
        return response
    }

    private fun growthPullEmptyStatusMessage(response: PullUsernameResponse): String {
        val raw = response.msg?.trim().orEmpty()
        if (raw.isEmpty()) return "暂无待执行用户名"
        if (PrefsStore.isAutoSwitchAutonomousMode() && isLegacyCloudNotStartedPullMsg(raw)) {
            return "自治涨粉：云端状态滞后，本机调度继续"
        }
        return raw
    }

    private fun isPublishLoginFailure(msg: String): Boolean {
        val m = msg.trim()
        if (m.isEmpty()) return false
        return m == SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE ||
            m.contains("登录保障") ||
            m.contains("掉登录") ||
            (m.contains("自动登录") && m.contains("失败"))
    }

    private fun activateAutoSwitchTikTokLoginHold(slotKey: String, reason: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        PrefsStore.activateAutoSwitchTikTokLoginHold(slotKey, reason)
        PrefsStore.saveLocalStatus("paused")
        growthModeLoginSafeguardPending = true
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val intervalMin = AUTO_SWITCH_LOGIN_RECOVERY_INTERVAL_MS / 60_000L
        if (alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】登录保障未通过：已暂停涨粉/发作品/引流调度；持续重试登录，约每 ${intervalMin} 分钟一轮（$reason）",
            )
        }
        val sk = slotKey.trim()
        if (sk.isNotBlank()) {
            autoSwitchLocalScheduler.notifyPublishOutcome(
                slotKey = sk,
                completed = false,
                loginFailed = true,
                accountChanged = false,
            )
        }
        if (alias.isNotBlank() && deviceId.isNotBlank()) {
            scheduleAutoSwitchLoginRecovery(alias, deviceId, sk, reason)
        }
    }

    /** 防抖期间周期重试登录；成功则清除门闩并恢复涨粉，失败则续期门闩（不恢复涨粉/发作品入队刷屏）。 */
    private fun scheduleAutoSwitchLoginRecovery(
        alias: String,
        deviceId: String,
        slotKey: String,
        reason: String,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        autoSwitchLoginRecoveryJob?.cancel()
        autoSwitchLoginRecoveryJob = scope.launch {
            var firstAttempt = true
            while (isActive) {
                if (!PrefsStore.isAutoSwitchAutonomousMode()) return@launch
                if (shouldHonorUserHardStop() || manuallyPaused) return@launch
                if (!PrefsStore.isAutoSwitchLoginRecoveryActive()) return@launch
                while (PublishPostGrowthGate.isBlockingGrowth() || ExecutionScope.isActive()) {
                    if (!delayUnlessHardStop(5_000L)) return@launch
                    if (!PrefsStore.isAutoSwitchLoginRecoveryActive()) return@launch
                }
                if (!firstAttempt) {
                    if (!delayUnlessHardStop(AUTO_SWITCH_LOGIN_RECOVERY_INTERVAL_MS)) return@launch
                }
                firstAttempt = false
                if (!PrefsStore.isAutoSwitchLoginRecoveryActive()) return@launch
                LogCenter.op(alias, "【自动切号】登录保障恢复重试（来源=$reason）")
                val ok = launchTikTokWithLoginSafeguardWithRetry(
                    alias = alias,
                    deviceId = deviceId,
                    tag = "登录保障恢复",
                    forceStopBeforeLaunch = true,
                    safeguardSource = "登录保障恢复",
                    strict = true,
                )
                if (ok) {
                    PrefsStore.clearAutoSwitchTikTokLoginHold()
                    growthModeLoginSafeguardPending = false
                    autoSwitchLoginRecoveryJob = null
                    val reviveSlot = slotKey.ifBlank {
                        PrefsStore.getAutoSwitchLastSlotKey().orEmpty()
                    }
                    if (reviveSlot.isNotBlank()) {
                        autoSwitchLocalScheduler.reviveLoginAbortedInsertions(reviveSlot)
                        autoSwitchLocalScheduler.requestImmediateTick(recoveryTick = true)
                    }
                    if (!manuallyPaused && !shouldHonorUserHardStop()) {
                        PrefsStore.saveLocalStatus("running")
                        updateRuntime(localStatus = "running", lastMessage = "登录保障恢复成功")
                    }
                    LogCenter.op(alias, "【自动切号】登录保障恢复成功，可继续涨粉/发作品")
                    maybeStartGrowthInAutoSwitchIdle("登录保障恢复成功")
                    return@launch
                }
                val sk = slotKey.ifBlank { PrefsStore.getAutoSwitchLastSlotKey().orEmpty() }
                PrefsStore.activateAutoSwitchTikTokLoginHold(
                    sk,
                    reason,
                )
                growthModeLoginSafeguardPending = true
                LogCenter.op(
                    alias,
                    "【自动切号】登录保障恢复仍未通过，${AUTO_SWITCH_LOGIN_RECOVERY_INTERVAL_MS / 60_000L} 分钟后再次重试登录",
                )
            }
        }
    }

    private fun restartAutoSwitchLoginRecoveryIfNeeded(reason: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (!PrefsStore.isAutoSwitchLoginRecoveryActive()) return
        if (autoSwitchLoginRecoveryJob?.isActive == true) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (alias.isBlank() || deviceId.isBlank()) return
        val sk = PrefsStore.getAutoSwitchLastSlotKey().orEmpty()
        val holdReason = PrefsStore.autoSwitchTikTokLoginHoldReason().orEmpty().ifBlank { reason }
        growthModeLoginSafeguardPending = true
        scheduleAutoSwitchLoginRecovery(alias, deviceId, sk, holdReason)
    }

    /** PLAN 切号待执行：清空发作品/互点/涨粉等运行时占用，强制让位切号链。 */
    private fun preemptAutoSwitchForCutSwitch(slotKey: String, reason: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        val oldPublishSlot = AutoSwitchLocalPlanStore.activePublishSlotKey()?.trim().orEmpty()
        val oldMutualSlot = AutoSwitchLocalPlanStore.activeMutualSlotKey()?.trim().orEmpty()
        val oldLiveVisitSlot = AutoSwitchLocalPlanStore.activeLiveVisitSlotKey()?.trim().orEmpty()
            .ifBlank {
                AutoSwitchLocalPlanStore.get()?.slots
                    ?.firstOrNull { it.live_visit.state == PlanStepState.RUNNING }
                    ?.slot_key?.trim().orEmpty()
            }
        // 先落主播访问终态，再 cancel job，避免 RUNNING 泄漏永久堵涨粉
        if (oldLiveVisitSlot.isNotBlank()) {
            autoSwitchLocalScheduler.notifyLiveVisitOutcome(oldLiveVisitSlot, completed = false)
        }
        clearAutoSwitchPerAccountDirtyState(
            reason = reason,
            prevSlotKey = oldPublishSlot.takeIf { it.isNotBlank() && it != sk }
                ?: oldMutualSlot.takeIf { it.isNotBlank() && it != sk }
                ?: oldLiveVisitSlot.takeIf { it.isNotBlank() && it != sk },
            newSlotKey = sk,
        )
        AutoSwitchLocalPlanStore.clearActivePublish()
        AutoSwitchLocalPlanStore.clearActiveMutual()
        AutoSwitchLocalPlanStore.clearActiveLiveVisit()
        listOf(oldPublishSlot, oldMutualSlot, oldLiveVisitSlot).distinct().filter { it.isNotBlank() && it != sk }.forEach { prevSk ->
            autoSwitchLocalScheduler.clearPublishInsertionInflight(prevSk)
            AutoSwitchLocalPlanStore.resetStalePublishInflight(prevSk)
        }
        if (sk.isNotBlank()) {
            autoSwitchLocalScheduler.clearPublishInsertionInflight(sk)
            AutoSwitchLocalPlanStore.resetStalePublishInflight(sk)
        }
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】PLAN 切号抢占完成 slot=$sk：$reason")
        }
    }

    /** 残留发作品续发不得抢在 PLAN 切号之前（含跨时段旧 slot）。 */
    private fun planCutSwitchBlocksStalePublishResume(staleSlotKey: String): Boolean {
        val plan = AutoSwitchLocalPlanStore.get() ?: return false
        val scheduleDate = beijingDateString()
        val currentSlot = AutoSwitchLocalPlanStore.resolveSlotForMinute(
            plan,
            scheduleDate,
            beijingMinuteOfDayForBundle(),
        ) ?: return false
        val sk = staleSlotKey.trim()
        if (!AutoSwitchLocalPlanStore.isCutSwitchDone(currentSlot.slot_key)) return true
        return currentSlot.slot_key != sk
    }

    /**
     * 切号/跨时段：清掉上一账号遗留的登录保障、发作品、引流、涨粉衔接等运行时脏数据，避免污染下一账号。
     */
    private fun clearAutoSwitchPerAccountDirtyState(
        reason: String,
        prevSlotKey: String? = null,
        newSlotKey: String? = null,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        val hadLoginRecovery = PrefsStore.isAutoSwitchLoginRecoveryActive() ||
            PrefsStore.isAutoSwitchTikTokLoginHoldActive()
        autoSwitchLoginRecoveryJob?.cancel()
        autoSwitchLoginRecoveryJob = null
        growthModeLoginSafeguardPending = false
        if (hadLoginRecovery) {
            PrefsStore.clearAutoSwitchTikTokLoginHold()
        }
        PrefsStore.clearPublishPostState()
        PrefsStore.clearMutualClickState()
        PrefsStore.setGrowthContinueFromPublishHome(false)
        PublishPostGrowthGate.exitExclusivePublishPhase()
        executor.clearAccountStatusReloginState()
        prevSlotKey?.trim()?.takeIf { it.isNotEmpty() }?.let { sk ->
            autoSwitchLocalScheduler.clearPublishInsertionInflight(sk)
        }
        if (isPublishPostSwitchActive()) {
            publishPostSwitchJob?.cancel()
            publishPostSwitchJob = null
        }
        if (isMutualSwitchActive()) {
            mutualSwitchJob?.cancel()
            mutualSwitchJob = null
        }
        if (isLiveVisitSwitchActive()) {
            liveVisitSwitchJob?.cancel()
            liveVisitSwitchJob = null
        }
        pullLoopJob?.cancel()
        pullLoopJob = null
        if (alias.isNotBlank()) {
            val slotNote = when {
                !prevSlotKey.isNullOrBlank() && !newSlotKey.isNullOrBlank() ->
                    " $prevSlotKey→$newSlotKey"
                else -> ""
            }
            LogCenter.op(alias, "【自动切号】账号切换清理脏状态：$reason$slotNote")
        }
    }

    private fun logFailedSummaryIfAny(alias: String) {
        if (failedTracker.isEmpty()) return
        LogCenter.op(alias, "近期失败 ${failedTracker.size} 条（详情已记入队列）")
    }

    /** 运行期业务失败时异步上传失败采集包（同业务同错误类型仅上报一次）。 */
    private fun scheduleFailureReport(
        business: String,
        errorType: String,
        summary: String,
        ocrRelated: Boolean? = null,
    ) {
        if (!FailureReportUploader.UPLOAD_ENABLED) return
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isEmpty() || alias.isEmpty()) return
        scope.launch {
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                val ocrFlag = ocrRelated
                    ?: FailureReportUploader.isOcrRelated(business, errorType, summary)
                AutoSwitchLedger.recordFailureReportDeferred(
                    business = business,
                    errorType = errorType,
                    summary = summary,
                    ocrRelated = ocrFlag,
                )
                LogCenter.op(alias, "失败采集已记入 ledger，对账时上传：$business / $errorType")
                return@launch
            }
            runCatching {
                FailureReportUploader.tryUpload(
                    deviceId = deviceId,
                    alias = alias,
                    business = business.trim(),
                    errorType = errorType,
                    summary = summary,
                    ocrRelated = ocrRelated,
                )
            }.onFailure { err ->
                LogCenter.op(alias, "失败采集上传异常：${err.message}")
            }
        }
    }

    /** 长轮询正常结束、读超时或 nginx 网关 502/504 均视为软超时，不必打错误日志。 */
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
        if (modeWaitJob?.isActive == true) return
        modeWaitJob = scope.launch {
            var authErrorRounds = 0
            while (true) {
                val deviceId = PrefsStore.getDeviceId()
                val alias = PrefsStore.getAlias()
                if (deviceId.isNullOrBlank() || alias.isNullOrBlank()) {
                    delay(10_000)
                    continue
                }
                val roundStarted = SystemClock.elapsedRealtime()
                try {
                    val sinceId = PrefsStore.getLastModeEventId()
                    val resp = ApiService.modeWait(deviceId, alias, sinceId, timeoutSec = MODE_WAIT_TIMEOUT_SEC)
                    uploadDeviceLogsIfPullRequested(
                        alias,
                        deviceId,
                        resp.log_pull_requested == true,
                        resp.log_pull_max_lines,
                    )
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
                    // 服务端 mode_wait 并发自保：立刻返回 msg=busy，短退避后重试，避免 30s 空窗。
                    if (event == null && resp.msg?.equals("busy", ignoreCase = true) == true) {
                        delay(5_000L)
                        continue
                    }
                    noteCloudAutoSwitchEnabled(
                        resolveCloudAutoSwitchEnabledFromModeWait(event, resp.auto_switch_enabled),
                    )
                    if (event != null && event.event_id > 0) {
                        val ev = event
                        val dId = deviceId
                        val al = alias
                        // 乐观推进 since_event_id，避免 handler 长跑期间重复投递同一事件。
                        if (ev.event_id > PrefsStore.getLastModeEventId()) {
                            PrefsStore.saveLastModeEventId(ev.event_id)
                        }
                        // 模式事件处理（拉 bundle / drain 等）与长轮询解耦，避免阻塞 mode_wait 与自治调度。
                        scope.launch {
                            modeEventMutex.withLock {
                                handleDeviceModeEvent(al, dId, ev)
                            }
                        }
                        continue
                    }
                    scope.launch {
                        modeEventMutex.withLock {
                            reconcileCloudAutoSwitchState(alias, deviceId, resp.auto_switch_enabled)
                        }
                    }
                    // 长轮询空结果：正常应接近 timeoutSec；若过快返回则退避，避免打爆服务端。
                    val elapsed = SystemClock.elapsedRealtime() - roundStarted
                    if (elapsed < 30_000L) {
                        delay((30_000L - elapsed).coerceAtLeast(5_000L))
                    }
                } catch (err: Exception) {
                    val isPollTimeout = isModeWaitSoftTimeout(err)
                    if (!isPollTimeout) {
                        LogCenter.op(alias, "模式事件等待失败：${err.message ?: ""}")
                    }
                    // 超时/网关 502·504 视为“本轮无事件”，进入下一轮；其它异常稍作退避避免打爆服务端
                    delay(if (isPollTimeout) 500L else 5_000L)
                }
            }
        }
    }

    private data class AutoSwitchBundleFetchOutcome(
        val loaded: Boolean,
        val msg: String = "",
        val cloudDisabled: Boolean = false,
        val usernameUnchanged: Boolean = false,
    )

    private fun cancelAutoSwitchBundleRetry() {
        autoSwitchBundleRetryJob?.cancel()
        autoSwitchBundleRetryJob = null
    }

    /** 配置/鉴权类错误重试无意义，避免打爆 VPS。 */
    private fun isAutoSwitchBundlePermanentFailure(msg: String): Boolean {
        val m = msg.trim()
        if (m.isEmpty()) return false
        return m.contains("无备份档案") ||
            m.contains("缺少今日计划") ||
            m.contains("鉴权") ||
            m.contains("无权") ||
            m.contains("不存在")
    }

    private suspend fun tryLoadAutoSwitchBundleForEnter(
        alias: String,
        deviceId: String,
        sessionId: String,
        bundleRevision: Int,
    ): AutoSwitchBundleFetchOutcome {
        val outcome = fetchAutoSwitchBundleFromCloud(alias, deviceId, "enter 加载 bundle", forceNetwork = true)
        return when {
            outcome.loaded && outcome.bundle != null -> {
                val usernameUnchanged = applyAutoSwitchBundle(alias, deviceId, outcome.bundle, bundleRevision)
                val weekStart = outcome.bundle.week_start_date?.trim().orEmpty()
                val weekEnd = outcome.bundle.week_end_date?.trim().orEmpty()
                val dayCount = outcome.bundle.weekly_plan?.day_plans?.size ?: 0
                LogCenter.op(
                    alias,
                    "【自动切号】bundle 已加载 session=$sessionId revision=$bundleRevision " +
                        "week=$weekStart~$weekEnd days=$dayCount",
                )
                AutoSwitchBundleFetchOutcome(loaded = true, usernameUnchanged = usernameUnchanged)
            }
            outcome.cloudDisabled ->
                AutoSwitchBundleFetchOutcome(loaded = false, msg = outcome.msg, cloudDisabled = true)
            else ->
                AutoSwitchBundleFetchOutcome(loaded = false, msg = outcome.msg.ifBlank { "bundle 为空" })
        }
    }

    private suspend fun finalizeAutoSwitchEnterAfterBundleLoaded(
        alias: String,
        deviceId: String,
        eventId: Int,
        sessionId: String,
        requestImmediateTick: Boolean = true,
    ) {
        noteCloudAutoSwitchEnabled(true)
        cancelAutoSwitchBundleRetry()
        PrefsStore.markAutoSwitchEnterDone(eventId)
        PrefsStore.saveLastModeEventId(eventId)
        updateRuntime(lastMessage = "自动切号自治：本地调度中")
        runCatching {
            ApiService.modeAck(deviceId, alias, eventId, "bundle_loaded", sessionId)
        }.onFailure { err ->
            LogCenter.op(alias, "自治模式 ACK(bundle_loaded) 失败：${err.message}")
        }
        if (requestImmediateTick) {
            delay(1_500)
            autoSwitchLocalScheduler.requestImmediateTick()
            maybeStartGrowthInAutoSwitchIdle("自治启动")
        } else {
            LogCenter.op(alias, "【自动切号】资料重同步：当前账号未变，跳过立即调度")
            maybeStartGrowthInAutoSwitchIdle("资料重同步")
        }
    }

    private fun scheduleAutoSwitchBundleEnterRetry(
        alias: String,
        deviceId: String,
        eventId: Int,
        sessionId: String,
        bundleRevision: Int,
        firstFailureMsg: String,
        suppressTickWhenUsernameUnchanged: Boolean = false,
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
                if (autoSwitchLocalScheduler.isRunning() && autoSwitchLocalScheduler.hasBundleLoaded()) {
                    return@launch
                }
                if (isCloudAutoSwitchDisabled()) {
                    performAutoSwitchExitShutdown(alias, deviceId, eventId = 0, exitSessionId = "")
                    return@launch
                }
                attempt++
                val outcome = tryLoadAutoSwitchBundleForEnter(alias, deviceId, sessionId, bundleRevision)
                if (outcome.loaded) {
                    finalizeAutoSwitchEnterAfterBundleLoaded(
                        alias,
                        deviceId,
                        eventId,
                        sessionId,
                        requestImmediateTick = !(suppressTickWhenUsernameUnchanged && outcome.usernameUnchanged),
                    )
                    return@launch
                }
                if (outcome.cloudDisabled) {
                    LogCenter.op(
                        alias,
                        "【自动切号】bundle 重试仍被拒（云端开关滞后），继续退避",
                    )
                } else if (isAutoSwitchBundlePermanentFailure(outcome.msg)) {
                    LogCenter.op(alias, "【自动切号】bundle 重试停止（不可恢复）：${outcome.msg}")
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

    private suspend fun handleDeviceModeEvent(alias: String, deviceId: String, event: DeviceModeEvent) {
        val type = event.type.trim()
        val sessionId = event.session_id.trim()
        val eventId = event.event_id
        val bundleRevision = event.bundle_revision ?: 0
        if (
            ManualDeviceControl.ENABLED &&
            type in setOf("auto_switch_enter", "auto_switch_exit")
        ) {
            LogCenter.op(alias, "手动模式：忽略云端自动切号事件 type=$type event_id=$eventId")
            PrefsStore.saveLastModeEventId(eventId)
            return
        }
        if (
            type !in setOf("auto_switch_enter", "auto_switch_exit") &&
            PrefsStore.isModeEventProcessed(eventId)
        ) {
            LogCenter.op(alias, "模式事件已处理过(event_id=$eventId type=$type)，跳过")
            return
        }
        when (type) {
            "auto_switch_enter" -> {
                noteCloudAutoSwitchEnabled(true)
                if (PrefsStore.isAutoSwitchEnterDone(eventId)) {
                    if (isCloudAutoSwitchDisabled()) {
                        performAutoSwitchExitShutdown(alias, deviceId, eventId = 0, exitSessionId = "")
                    }
                    return
                }
                if (PrefsStore.isAutoSwitchAutonomousMode() && autoSwitchLocalScheduler.isRunning()) {
                    LogCenter.op(alias, "【自动切号】收到资料重同步 enter session=$sessionId revision=$bundleRevision")
                    handleAutoSwitchEnterResync(alias, deviceId, eventId, sessionId, bundleRevision)
                    return
                }
                // 自治模式：切号/发作品与涨粉互斥；空档时段账号对齐后恢复涨粉。
                PrefsStore.setTransientPausedForDestructiveOp(false)
                if (PrefsStore.isHardStopRequested()) {
                    PrefsStore.setHardStopRequested(false)
                }
                manuallyPaused = false
                pullLoopJob?.cancel()
                pullLoopJob = null
                batchStreamJob?.cancel()
                batchStreamJob = null
                pendingUsernames.clear()
                awaitAutoSwitchExclusiveDrain(alias)
                // 避免历史遗留的发作品 pending 卡住自治调度（自治发作品使用 auto_switch 专用任务标记）。
                PrefsStore.setPublishPostPending(false)
                growthModeLoginSafeguardPending = false
                if (!started.get()) {
                    started.set(true)
                }
                PrefsStore.saveLocalStatus("running")
                updateRuntime(
                    localStatus = "running",
                    hardStopRequested = false,
                    lastMessage = "自动切号自治启动",
                )
                if (!WorkerServiceTracker.isRunning) {
                    runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "auto_switch_enter_resume") }
                }
                runCatching { syncRunningToCloud() }
                PrefsStore.saveAutoSwitchSessionContext(sessionId, bundleRevision)
                AutoSwitchLedger.restoreFromPrefs()
                AutoSwitchLedger.startSession(sessionId, bundleRevision = bundleRevision)
                PrefsStore.setAutoSwitchPendingSummaryUpload(false)
                scheduleAutoSwitchDailyReconcileTargetIfNeeded(alias)
                cancelAutoSwitchBundleRetry()
                val bundleOutcome = tryLoadAutoSwitchBundleForEnter(
                    alias,
                    deviceId,
                    sessionId,
                    bundleRevision,
                )
                if (!bundleOutcome.loaded) {
                    val failMsg = bundleOutcome.msg.ifBlank { "未知错误" }
                    LogCenter.op(alias, "【自动切号】bundle 拉取失败：$failMsg")
                }
                updateRuntime(
                    lastMessage = if (bundleOutcome.loaded) {
                        "自动切号自治：本地调度中"
                    } else {
                        "自动切号自治：等待 bundle"
                    },
                )
                when {
                    bundleOutcome.loaded ->
                        finalizeAutoSwitchEnterAfterBundleLoaded(
                            alias,
                            deviceId,
                            eventId,
                            sessionId,
                            requestImmediateTick = !bundleOutcome.usernameUnchanged,
                        )
                    bundleOutcome.cloudDisabled -> {
                        LogCenter.op(
                            alias,
                            "【自动切号】enter 拉 bundle 被拒（云端开关滞后），将退避重试",
                        )
                        scheduleAutoSwitchBundleEnterRetry(
                            alias,
                            deviceId,
                            eventId,
                            sessionId,
                            bundleRevision,
                            bundleOutcome.msg.ifBlank { "该设备未开启自动切号" },
                            suppressTickWhenUsernameUnchanged = true,
                        )
                    }
                    else -> scheduleAutoSwitchBundleEnterRetry(
                        alias,
                        deviceId,
                        eventId,
                        sessionId,
                        bundleRevision,
                        bundleOutcome.msg,
                        suppressTickWhenUsernameUnchanged = true,
                    )
                }
            }
            "auto_switch_exit" -> {
                noteCloudAutoSwitchEnabled(false)
                if (PrefsStore.isAutoSwitchExitDone(eventId)) {
                    return
                }
                if (
                    PrefsStore.getAutoSwitchMode() == "off" &&
                    !AutoSwitchLedger.hasPendingUpload() &&
                    !PrefsStore.hasAutoSwitchPendingSummaryUpload()
                ) {
                    PrefsStore.markAutoSwitchExitDone(eventId)
                    PrefsStore.saveLastModeEventId(eventId)
                    return
                }
                performAutoSwitchExitShutdown(alias, deviceId, eventId, sessionId)
            }
            else -> LogCenter.op(alias, "收到未知模式事件：$type")
        }
    }

    private fun startDeviceOpsLoop() {
        if (deviceOpsJob?.isActive == true) return
        deviceOpsJob = scope.launch {
            var idleRounds = 0
            while (true) {
                val deviceId = PrefsStore.getDeviceId()
                val alias = PrefsStore.getAlias()
                if (deviceId.isNullOrBlank() || alias.isNullOrBlank()) {
                    delay(10_000)
                    continue
                }

                if (PrefsStore.isAutoSwitchAutonomousMode()) {
                    delay(30_000)
                    continue
                }

                // 空闲轮询降频，减轻云端 SQLite/锁竞争（与服务端 10s 限流对齐）。
                val pollDelay = when {
                    idleRounds >= 3 -> 30_000L
                    else -> 15_000L
                }

                var pollOk = false
                try {
                    val resp = ApiService.pollDeviceOps(deviceId, alias)
                    pollOk = true
                    if (resp.code == 0 && resp.op != null) {
                        idleRounds = 0
                        LogCenter.op(alias, "设备任务轮询：收到 ${resp.op.op_type}（${resp.op.op_id}）")
                        handleDeviceOp(alias, deviceId, resp.op)
                    }
                } catch (err: Exception) {
                    LogCenter.op(alias, "设备任务轮询失败：${err.message}")
                    pollOk = false
                }

                if (!pollOk) {
                    idleRounds = 0
                } else if (PrefsStore.getLocalStatus() != "running") {
                    idleRounds++
                }

                delay(pollDelay)
            }
        }
    }

    private suspend fun uploadDeviceLogsIfPullRequested(
        alias: String,
        deviceId: String,
        pullRequested: Boolean,
        maxLines: Int? = null,
    ) {
        if (!pullRequested) return
        val cap = (maxLines ?: 400).coerceIn(1, 500)
        val lines = LocalDeviceLogWriter.readTodayFileTail(cap)
        runCatching {
            ApiService.syncDeviceLogs(deviceId, alias, lines, replace = true)
        }.onSuccess {
            if (lines.isEmpty()) {
                LogCenter.op(alias, "已响应管理台日志拉取（今日暂无本地日志）")
            } else {
                LogCenter.op(alias, "已响应管理台日志拉取（今日尾部 ${lines.size} 行，上限 $cap）")
            }
        }.onFailure { err ->
            LogCenter.op(alias, "响应管理台日志拉取失败：${err.message}")
        }
    }

    private suspend fun uploadDeviceLogsIfRequested(alias: String, deviceId: String, response: CheckStartResponse) {
        uploadDeviceLogsIfPullRequested(
            alias,
            deviceId,
            response.log_pull_requested == true,
            response.log_pull_max_lines,
        )
    }

    /**
     * 管理台下发设备指令（发作品 / 互点 / 图库导入等）时：必须解除本地暂停与硬停，并同步云端为 running，
     * 避免 check_start 仍返回 paused 把任务打回「暂停后收尾」。
     */
    private suspend fun resumeExecutionForCloudDeviceOp(
        alias: String,
        deviceId: String,
        reason: String,
    ) {
        val wasHardStop = PrefsStore.isHardStopRequested()
        val wasPaused = PrefsStore.getLocalStatus() == "paused"
        PrefsStore.setTransientPausedForDestructiveOp(false)
        PrefsStore.setHardStopRequested(false)
        manuallyPaused = false
        if (!started.get()) {
            started.set(true)
        }
        PrefsStore.saveLocalStatus("running")
        updateRuntime(
            localStatus = "running",
            hardStopRequested = false,
            lastMessage = reason,
        )
        runCatching {
            syncCloudStatus("running", CloudStatusSyncKind.ROUTINE)
        }.onFailure { err ->
            LogCenter.op(alias, "云端指令：同步 running 到服务端失败：${err.message}")
        }
        if (wasHardStop || wasPaused) {
            LogCenter.op(
                alias,
                "云端指令已解除暂停/硬停并进入执行（原 paused=$wasPaused hardStop=$wasHardStop）",
            )
        }
        if (!WorkerServiceTracker.isRunning) {
            runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "pause_for_exclusive_op") }
                .onFailure { LogCenter.op(alias, "云端指令：拉起后台服务失败：${it.message}") }
        }
    }

    private val destructiveTikTokOps = setOf(
        "tiktok_clear",
        "tiktok_backup_and_clear",
        "tiktok_restore",
    )

    private val autoSwitchLedgerOpTypes = setOf(
        "tiktok_clear",
        "tiktok_backup_and_clear",
        "tiktok_restore",
    )

    /** 需独占 TikTok 界面（OCR/点击链较长）：暂停涨粉，避免与自动任务并发操作 TikTok。 */
    private val exclusiveTikTokUiOps = setOf(
        "tiktok_edit_basic_profile",
    )

    /**
     * 备份/清理/还原/切号前：停拉取循环、强停 TikTok，等待涨粉步骤收尾，避免与 pm clear 并发冲突。
     */
    private suspend fun prepareForDestructiveTikTokOp(
        alias: String,
        deviceId: String,
        opType: String,
        opId: String,
        packageName: String,
        settleMs: Long = 10_000L,
    ) {
        if (!isLocalAutoSwitchOpId(opId)) {
            LogCenter.op(alias, "[OP][$opId] 收到 $opType：暂停涨粉，${settleMs / 1000}s 后执行…")
        }
        if (
            PrefsStore.isAutoSwitchAutonomousMode() &&
            opType in setOf("tiktok_backup_and_clear", "tiktok_clear", "tiktok_restore")
        ) {
            clearAutoSwitchPerAccountDirtyState(reason = "切号开始")
            AutoSwitchAccountActivityTracker.finalizeSegment("cut_switch")
        }
        pullLoopJob?.cancel()
        pullLoopJob = null
        PrefsStore.setTransientPausedForDestructiveOp(true)
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(localStatus = "paused", lastMessage = "备份/切号前等待任务收尾")
        runCatching {
            syncCloudStatus("paused", CloudStatusSyncKind.ROUTINE)
        }.onFailure { err ->
            LogCenter.op(alias, "[OP][$opId] 同步 paused 失败：${err.message}")
        }
        val pkg = packageName.trim().ifBlank { com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME }
        val aggressiveKill = isLocalAutoSwitchOpId(opId) ||
            (PrefsStore.isAutoSwitchAutonomousMode() && opType in setOf("tiktok_backup_and_clear", "tiktok_clear", "tiktok_restore"))
        if (PrefsStore.isAutoSwitchAutonomousMode() && opType in setOf("tiktok_backup_and_clear", "tiktok_clear", "tiktok_restore")) {
            PrefsStore.setAutoSwitchExclusivePreempt(true)
        }
        if (alias.isNotBlank()) {
            val verifyFail = forceStopTikTokPackageAndVerify(
                pkg,
                alias,
                "备份/切号 OP 开始前",
                aggressiveForCutSwitch = aggressiveKill,
            )
            if (verifyFail != null) {
                LogCenter.op(
                    alias,
                    "【切号·关TikTok】备份/切号 OP 开始前 TikTok 未能确认完全退出（$pkg）：$verifyFail，仍将等待 ${settleMs / 1000}s 后执行",
                )
            }
            delay((settleMs - TIKTOK_FORCE_STOP_SETTLE_MS).coerceAtLeast(0L))
        } else {
            runExecutorSu(DeviceBackupShell.forceStopCommand(pkg))
            delay(settleMs)
        }
        if (!isLocalAutoSwitchOpId(opId)) {
            LogCenter.op(alias, "[OP][$opId] 等待结束，开始执行 $opType")
        }
    }

    /**
     * 修改基本资料等独占 TikTok UI 的远控：暂停涨粉、临时关闭云端自动拉起，强停 TikTok 并等待收尾。
     * @return 任务结束后是否应恢复涨粉（任务开始前为 running 且未硬停/悬浮暂停）。
     */
    private suspend fun prepareForExclusiveTikTokUiOp(
        alias: String,
        deviceId: String,
        opType: String,
        opId: String,
        packageName: String,
        settleMs: Long = 8_000L,
        forceStopTikTok: Boolean = true,
    ): Boolean {
        val wasRunning = PrefsStore.getLocalStatus() == "running" && !PrefsStore.isHardStopRequested() && !manuallyPaused
        val settleHint = if (forceStopTikTok) "${settleMs / 1000}s 后执行" else "立即执行（保留 TikTok 前台）"
        LogCenter.op(alias, "[OP][$opId] 收到 $opType：暂停涨粉并独占 TikTok（$settleHint）…")
        runCatching {
            withContext(Dispatchers.IO) {
                reportAutoStartOptOutIfNeeded(deviceId = deviceId, alias = alias, optOut = true)
            }
        }.onFailure { err ->
            LogCenter.op(alias, "[OP][$opId] 上报关闭自动开启失败：${err.message}")
        }
        // 登录保障/涨粉拉取循环内同步衔接改资料时，不可 cancel pullLoopJob（会自取消当前协程）。
        val pullJob = pullLoopJob
        if (pullJob != null && pullJob !== currentCoroutineContext()[Job]) {
            pullJob.cancel()
            pullLoopJob = null
        }
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(localStatus = "paused", lastMessage = "修改基本资料：暂停涨粉，独占 TikTok")
        runCatching {
            syncCloudStatus("paused", CloudStatusSyncKind.ROUTINE)
        }.onFailure { err ->
            LogCenter.op(alias, "[OP][$opId] 同步 paused 失败：${err.message}")
        }
        if (!started.get()) {
            started.set(true)
        }
        if (!WorkerServiceTracker.isRunning) {
            runCatching { WorkerServiceWatchdog.ensureWorkerService(appContext, "prepare_exclusive_ui_op") }
                .onFailure { LogCenter.op(alias, "[OP][$opId] 拉起后台服务失败：${it.message}") }
        }
        val pkg = packageName.trim().ifBlank { com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME }
        if (forceStopTikTok) {
            runCatching {
                executor.run {
                    val m = javaClass.getDeclaredMethod("runSuCommand", String::class.java)
                    m.isAccessible = true
                    m.invoke(this, "am force-stop $pkg")
                }
            }
            delay(settleMs)
        }
        LogCenter.op(alias, "[OP][$opId] 独占准备完成，开始执行 $opType")
        return wasRunning
    }

    private fun isBasicProfileModificationPending(modified: String?): Boolean =
        modified?.trim()?.equals("是", ignoreCase = true) != true

    private fun recordProfileEditRequiredIfNeeded(username: String, basicProfileModified: String?) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val user = username.trim()
        if (user.isBlank()) return
        if (!isBasicProfileModificationPending(basicProfileModified)) return
        AutoSwitchLedger.recordProfileEditRequired(
            user,
            basicProfileModified?.trim().orEmpty().ifBlank { "否" },
        )
    }

    /**
     * 发作品/互点等独占任务结束后各尝试 1 次改资料（不循环重试；若仍被其它独占挡住则等下次独占结束）。
     */
    private suspend fun maybeRetryPendingBasicProfileEditAfterExclusive(
        alias: String,
        deviceId: String,
        trigger: String,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (shouldHonorUserHardStop() || manuallyPaused) return
        maybeRunPendingBasicProfileEditAfterLoginVerified(
            alias = alias,
            deviceId = deviceId,
            parentOpId = "exclusive_retry_${System.currentTimeMillis()}",
            trigger = trigger,
            skipOuterWarmup = false,
            loginVerified = true,
        )
    }

    /** 切号标完成后串行衔接改资料；涨粉仅在改资料结束（或无需改资料）后恢复，避免与独占改资料抢 TikTok。 */
    private fun scheduleCutSwitchDeferredBasicProfileEdit(
        alias: String,
        deviceId: String,
        parentOpId: String,
    ) {
        // 必须等当前切号 OP 出队并释放 ExecutionScope，再跑改资料/插入/涨粉；
        // 旧逻辑在 OP 未结束时尝试恢复涨粉会静默失败，随后每分钟被误判「涨粉执行中」空转。
        postCutSwitchKickPending = true
        scope.launch {
            try {
                kickPostCutSwitchPipeline(alias, deviceId, parentOpId)
            } finally {
                postCutSwitchKickPending = false
            }
        }
    }

    /**
     * 切号完成后的强制衔接：等独占收尾 → 改资料（若需要）→ 即时 tick（主播访问/发作品/互点）→ 恢复涨粉。
     * 对 ExecutionScope 卡住带超时强制清除，避免空窗假死。
     */
    private suspend fun kickPostCutSwitchPipeline(
        alias: String,
        deviceId: String,
        parentOpId: String,
    ) {
        LogCenter.op(alias, "【自动切号】切号后衔接：等待独占 OP / 执行标记收尾…")
        awaitAutoSwitchExclusiveDrain(alias, maxMs = 60_000L)
        if (ExecutionScope.isActive() && !autoSwitchLocalScheduler.isExclusiveWorkActive()) {
            LogCenter.op(
                alias,
                "【自动切号】切号后衔接：执行标记仍占用 depth=${ExecutionScope.depth()} " +
                    "阶段=${ProcessDiagnostics.currentPhase()}，强制清除以免插入任务饿死",
            )
            ExecutionScope.forceClearIfStuck("切号后衔接")
        }
        recoverStaleAutoSwitchExclusiveState("切号后衔接")
        if (!manuallyPaused && !shouldHonorUserHardStop() && PrefsStore.getAutoSwitchMode() != "exiting") {
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "自动切号自治：切号后衔接")
        }
        // 独占已空：放开 drain 抢跑限制；改资料期间由 basicProfileEditRunning / preempt 护栏负责
        postCutSwitchKickPending = false

        val ran = maybeRunPendingBasicProfileEditAfterLoginVerified(
            alias = alias,
            deviceId = deviceId,
            parentOpId = "${parentOpId}_profile_after_cut",
            trigger = "cut_switch_after_login",
            skipOuterWarmup = true,
            loginVerified = true,
        )
        // 先 tick：让主播访问等插入任务有机会入队，再决定是否恢复涨粉基线
        autoSwitchLocalScheduler.requestImmediateTick()
        delay(400)
        if (!ran) {
            maybeStartGrowthInAutoSwitchIdle("切号完成（无需改资料）")
            retryMaybeStartGrowthIfStillIdle(alias, "切号完成（无需改资料）")
        }
    }

    /** 切号后常见竞态：首次恢复撞上收尾窗口，短间隔重试几次。 */
    private fun retryMaybeStartGrowthIfStillIdle(alias: String, reason: String) {
        if (pullLoopJob?.isActive == true) return
        scope.launch {
            for (attempt in 1..6) {
                delay(2_000L)
                if (pullLoopJob?.isActive == true) return@launch
                if (manuallyPaused || shouldHonorUserHardStop()) return@launch
                if (PrefsStore.getAutoSwitchMode() == "exiting") return@launch
                if (autoSwitchLocalScheduler.isExclusiveWorkActive()) continue
                if (ExecutionScope.isActive() && !autoSwitchLocalScheduler.isExclusiveWorkActive()) {
                    ExecutionScope.forceClearIfStuck("切号后恢复涨粉重试#$attempt")
                }
                if (!autoSwitchLocalScheduler.shouldAllowGrowthBaseline()) {
                    // 主播访问等插入待执行：只补 tick，不抢涨粉
                    autoSwitchLocalScheduler.requestImmediateTick()
                    continue
                }
                LogCenter.op(alias, "【自动切号】$reason：第 $attempt 次重试恢复涨粉")
                maybeStartGrowthInAutoSwitchIdle("$reason(重试$attempt)")
                if (pullLoopJob?.isActive == true) return@launch
            }
            if (pullLoopJob?.isActive != true && autoSwitchLocalScheduler.shouldAllowGrowthBaseline()) {
                LogCenter.op(
                    alias,
                    "【自动切号】$reason：多次重试后涨粉仍未启动 " +
                        "exec=${ExecutionScope.depth()} preempt=${PrefsStore.isAutoSwitchExclusivePreempt()} " +
                        "status=${PrefsStore.getLocalStatus()} 阶段=${ProcessDiagnostics.currentPhase()}",
                )
            }
        }
    }

    /**
     * 登录已就绪（自动登录成功 / 云端 is_login=是 / 登录状态保障通过）且云端「基本资料已修改」=否 时衔接改资料。
     * 手动模式（android-simple）禁止一切自动触发：仅人点「修改基本资料」经 [manualStartEditBasicProfile] 下发 local-manual OP。
     * @param loginVerified 调用方已确认登录就绪时设为 true（避免 mark is_login 上报尚未落库导致误判）。
     */
    private suspend fun maybeRunPendingBasicProfileEditAfterLoginVerified(
        alias: String,
        deviceId: String,
        parentOpId: String,
        trigger: String,
        skipOuterWarmup: Boolean = false,
        loginVerified: Boolean = false,
    ): Boolean {
        if (ManualDeviceControl.ENABLED) {
            // 登录保障 / 涨粉 / 切号后会频繁路过；静默跳过，避免刷屏
            return false
        }
        if (basicProfileEditRunning) {
            LogCenter.op(alias, "[OP][$parentOpId] 改资料已在执行中，跳过（trigger=$trigger）")
            return false
        }
        if (shouldHonorUserHardStop() || manuallyPaused || PrefsStore.getAutoSwitchMode() == "exiting") return false
        val accountSnapshot = resolveAssignedAccount(alias, deviceId)
        if (
            accountSnapshot?.code == 0 &&
            (loginVerified || accountSnapshot.is_login == true)
        ) {
            recordProfileEditRequiredIfNeeded(
                accountSnapshot.username.orEmpty(),
                accountSnapshot.basic_profile_modified,
            )
        }
        if (isPublishPostSwitchActive() || isMutualSwitchActive() || isLiveVisitSwitchActive() || isBatchStreamActive()) {
            LogCenter.op(alias, "[OP][$parentOpId] 独占任务进行中，暂缓改资料（trigger=$trigger）")
            return false
        }
        val account = accountSnapshot ?: return false
        if (account.code != 0) return false
        if (!loginVerified && account.is_login != true) return false
        if (!isBasicProfileModificationPending(account.basic_profile_modified)) return false

        basicProfileEditRunning = true
        try {
            LogCenter.op(
                alias,
                "[OP][$parentOpId] 登录已就绪且云端「基本资料已修改」=否，开始自动修改基本资料（trigger=$trigger）",
            )
            runProfileEditAfterCutSwitch(
                alias = alias,
                deviceId = deviceId,
                parentOpId = parentOpId,
                skipOuterWarmup = skipOuterWarmup,
                profileEditTrigger = trigger,
            )
            return true
        } finally {
            basicProfileEditRunning = false
        }
    }

    private fun MutableMap<String, Any>.withDeviceOpReportFlags(): MutableMap<String, Any> {
        if (PrefsStore.isHardStopRequested()) {
            this["preserve_auto_start_opt_out"] = true
        }
        return this
    }

    /** 任务结束（成功或失败）后向云端恢复「参与自动开启」；不拉起涨粉。硬停时保持关闭。 */
    private suspend fun restoreCloudAutoStartParticipation(
        alias: String,
        deviceId: String,
        opId: String,
        reason: String = "",
    ) {
        if (shouldHonorUserHardStop()) {
            if (!isLocalAutoSwitchOpId(opId)) {
                LogCenter.op(alias, "[OP][$opId] 硬停中，保持关闭云端自动开启")
            }
            return
        }
        runCatching {
            withContext(Dispatchers.IO) {
                reportAutoStartOptOutIfNeeded(deviceId = deviceId, alias = alias, optOut = false)
            }
        }.onFailure { err ->
            val prefix = if (isLocalAutoSwitchOpId(opId)) "【切号】" else "[OP][$opId] "
            LogCenter.op(alias, "${prefix}恢复自动开启上报失败：${err.message}")
        }.onSuccess {
            if (!isLocalAutoSwitchOpId(opId)) {
                val suffix = if (reason.isNotBlank()) "（$reason）" else ""
                LogCenter.op(alias, "[OP][$opId] 已恢复云端自动开启程序$suffix")
            }
        }
    }

    private suspend fun awaitExecutionScopeIdleForAutoSwitch(alias: String, maxMs: Long = 20_000L) {
        var waited = 0L
        while (ExecutionScope.isActive() && waited < maxMs) {
            delay(250)
            waited += 250
        }
        if (ExecutionScope.isActive()) {
            LogCenter.op(alias, "【自动切号】等待涨粉/独占任务收尾超时（${maxMs / 1000}s），切号调度可能被暂缓")
        }
    }

    /** exit/enter 前：等涨粉步骤与本地独占 OP（切号/还原/自动登录）均结束。 */
    private suspend fun awaitAutoSwitchExclusiveDrain(
        alias: String,
        maxMs: Long = AUTO_SWITCH_EXCLUSIVE_DRAIN_MS,
    ) {
        awaitExecutionScopeIdleForAutoSwitch(alias, maxMs)
        autoSwitchLocalScheduler.awaitExclusiveIdle(maxMs)
    }

    private suspend fun waitForBasicProfileEditIdle(
        alias: String,
        reason: String,
        maxMs: Long = 300_000L,
    ): Boolean {
        if (!basicProfileEditRunning) return true
        LogCenter.op(alias, "【自动切号】$reason：修改基本资料仍在执行，等待其结束后再继续")
        var waited = 0L
        while (basicProfileEditRunning) {
            if (shouldHonorUserHardStop() || manuallyPaused || PrefsStore.getAutoSwitchMode() == "exiting") {
                LogCenter.op(alias, "【自动切号】$reason：等待修改基本资料结束时中止")
                return false
            }
            if (waited >= maxMs) {
                LogCenter.op(alias, "【自动切号】$reason：等待修改基本资料结束超时（${maxMs / 1000}s），本轮不继续")
                return false
            }
            val delayMs = minOf(500L, maxMs - waited)
            delay(delayMs)
            waited += delayMs
        }
        LogCenter.op(alias, "【自动切号】$reason：修改基本资料已结束，继续执行")
        return true
    }

    private suspend fun finishAutoSwitchCutSwitchAfterSuccess(
        alias: String,
        opId: String,
        targetRowIndex: Int,
        slotKey: String,
        completionMethod: String,
    ) {
        val sk = slotKey.trim()
        val targetAcc = AutoSwitchBundleHolder.getBundle()?.accounts.orEmpty()
            .firstOrNull { (it.row_index ?: -1) == targetRowIndex }
        val targetUser = targetAcc?.username?.trim().orEmpty()
        if (targetRowIndex >= 0 && sk.isNotEmpty()) {
            if (!AutoSwitchLocalPlanStore.canMarkCutSwitchDone(sk)) {
                LogCenter.op(
                    alias,
                    "【自动切号】切号 OP 收尾失败：备份/清理未完成，不能标切号完成 slot=$sk",
                )
                autoSwitchLocalScheduler.invalidateSlotAlignment(sk, "切号链前序未完成")
                return
            }
            autoSwitchLocalScheduler.notifyCutSwitchAligned(
                targetRowIndex = targetRowIndex,
                slotKey = sk,
                completionMethod = completionMethod,
                targetUsername = targetUser,
            )
            if (!AutoSwitchLocalPlanStore.isCutSwitchDone(sk)) {
                LogCenter.op(alias, "【自动切号】切号完成未写入计划文件，本时段继续跑切号链")
                return
            }
            targetAcc?.let { acc ->
                recordProfileEditRequiredIfNeeded(
                    acc.username.orEmpty(),
                    acc.basic_profile_modified,
                )
                acc.username?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    PrefsStore.setLogContextUsername(it)
                    applyBundledConfigFromAutoSwitch(it, logToUser = false)
                }
            }
        } else {
            autoSwitchLocalScheduler.clearCutSwitchInflight()
            autoSwitchLocalScheduler.invalidateSlotAlignment(
                sk,
                when {
                    targetRowIndex < 0 && sk.isEmpty() -> "切号成功但缺少 targetRowIndex 与 slotKey"
                    targetRowIndex < 0 -> "切号成功但缺少 targetRowIndex"
                    else -> "切号成功但缺少 slotKey"
                },
            )
            LogCenter.op(
                alias,
                "【自动切号】切号 OP 成功但未写 plan 切号完成（row=$targetRowIndex slot=${sk.ifBlank { "-" }}）",
            )
        }
        PrefsStore.setTransientPausedForDestructiveOp(false)
        PrefsStore.setHardStopRequested(false)
        manuallyPaused = false
        PrefsStore.saveLocalStatus("running")
        updateRuntime(localStatus = "running", lastMessage = "自动切号自治：切号完成")
        LogCenter.op(
            alias,
            "【自动切号】切号完成 row=$targetRowIndex，继续自治调度（涨粉在改资料结束或无需改资料后恢复）",
        )
        // 即时 tick 改由 kickPostCutSwitchPipeline 在独占收尾后触发，避免 OP 未出栈时误判「涨粉执行中」
    }

    /**
     * 切号备份 tar 完成后：自治期记入 ledger 待上传；非自治仍即时上传。
     */
    private suspend fun reportAutoSwitchCutSwitchBackupImmediately(
        alias: String,
        deviceId: String,
        opId: String,
        username: String,
        packageName: String,
        backupName: String,
        backupPath: String,
        payload: DeviceOpPayload?,
    ) {
        val user = username.trim()
        if (user.isBlank() || backupPath.isBlank()) return
        updateLocalBundleBackupPath(user, backupName, backupPath)
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
            AutoSwitchLedger.recordCutSwitchBackupDeferred(
                sourceOpId = opId,
                username = user,
                packageName = packageName,
                backupName = backupName,
                backupPath = backupPath,
                slotKey = slotKey,
                backupRecordId = payload?.backup_record_id,
            )
            if (alias.isNotBlank()) {
                LogCenter.op(
                    alias,
                    "【自动切号】切号备份 @$user 已本地打包：$backupName（文件待 exit/每日对账时上传云端）",
                )
            }
            return
        }
        uploadCutSwitchBackupToCloudNow(
            alias = alias,
            deviceId = deviceId,
            opId = opId,
            username = user,
            packageName = packageName,
            backupName = backupName,
            backupPath = backupPath,
            payload = payload,
        )
    }

    private fun updateLocalBundleBackupPath(username: String, backupName: String, backupPath: String) {
        AutoSwitchLocalPlanStore.updateBackupPath(username, backupName, backupPath)
        AutoSwitchBundleHolder.getBundle()?.let { bundle ->
            AutoSwitchBundleHolder.setBundle(AutoSwitchLocalPlanStore.applyRuntimeToBundle(bundle))
        }
    }

    private suspend fun uploadCutSwitchBackupToCloudNow(
        alias: String,
        deviceId: String,
        opId: String,
        username: String,
        packageName: String,
        backupName: String,
        backupPath: String,
        payload: DeviceOpPayload?,
    ) {
        val user = username.trim()
        if (user.isBlank() || backupPath.isBlank()) return
        val record = BackupRecord(
            accountName = user,
            packageName = packageName,
            deviceAlias = alias,
            deviceId = deviceId,
            appAccount = PrefsStore.getAuthUsername().orEmpty(),
            backupType = "backup",
            backupScope = "cloud",
            success = true,
            path = backupPath,
            filename = backupName,
            message = "auto_switch cut_switch backup",
            uploadStatus = "pending",
        )
        val uploadOk = try {
            BackupReporter().uploadBackupAndCreateTask(deviceId, alias, record).success
        } catch (err: Exception) {
            if (alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】切号备份云端上报异常：${err.message}")
            }
            false
        }
        val reportData = mutableMapOf<String, Any>(
            "op_type" to "tiktok_backup_and_clear",
            "device_id" to deviceId,
            "backup_name" to backupName,
            "backup_path" to backupPath,
            "package_name" to packageName,
            "mobile_alias" to alias,
            "username" to user,
            "backup_report_phase" to "immediate_after_tar",
        )
        payload?.backup_record_id?.let { reportData["backup_record_id"] = it }
        payload?.auto_switch_slot_key?.trim()?.takeIf { it.isNotEmpty() }?.let {
            reportData["auto_switch_slot_key"] = it
        }
        reportData.withDeviceOpReportFlags()
        reportDeviceOpToCloud(
            deviceId = deviceId,
            alias = alias,
            opId = "${opId}_backup_immediate",
            status = "success",
            message = "切号备份完成，地址已即时上报",
            data = reportData,
        )
        if (alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】切号备份 @$user 地址已即时上报云端：$backupName（api=${if (uploadOk) "ok" else "fail"}）",
            )
        }
    }

    /** 自治 exit / 每日对账前：上传 ledger 中待传的切号备份 tar。 */
    private suspend fun flushDeferredCutSwitchBackupUploads(alias: String, deviceId: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (AutoSwitchLedger.getEntryCount() == 0) {
            AutoSwitchLedger.restoreFromPrefs()
        }
        if (!AutoSwitchLedger.hasPendingCutSwitchBackupUpload()) return
        var okCount = 0
        var failCount = 0
        AutoSwitchLedger.forEachPendingCutSwitchBackupUpload { entry ->
            val data = entry.report_data
            val backupPath = data["backup_path"]?.toString()?.trim().orEmpty()
            val backupName = data["backup_name"]?.toString()?.trim().orEmpty()
            val user = data["username"]?.toString()?.trim().orEmpty()
            val packageName = data["package_name"]?.toString()?.trim().orEmpty()
                .ifBlank { com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME }
            if (backupPath.isBlank() || user.isBlank()) {
                AutoSwitchLedger.noteCutSwitchBackupUploadFailed(entry.op_id, "缺少 backup_path 或 username")
                failCount++
                return@forEachPendingCutSwitchBackupUpload
            }
            if (!File(backupPath).isFile) {
                AutoSwitchLedger.noteCutSwitchBackupUploadFailed(entry.op_id, "本机文件不存在：$backupPath")
                failCount++
                return@forEachPendingCutSwitchBackupUpload
            }
            val name = backupName.ifBlank { File(backupPath).name }
            val record = BackupRecord(
                accountName = user,
                packageName = packageName,
                deviceAlias = alias,
                deviceId = deviceId,
                appAccount = PrefsStore.getAuthUsername().orEmpty(),
                backupType = "backup",
                backupScope = "cloud",
                success = true,
                path = backupPath,
                filename = name,
                message = "auto_switch cut_switch backup (deferred)",
                uploadStatus = "pending",
            )
            val uploadOk = try {
                BackupReporter().uploadBackupAndCreateTask(deviceId, alias, record).success
            } catch (err: Exception) {
                AutoSwitchLedger.noteCutSwitchBackupUploadFailed(entry.op_id, err.message ?: "上传异常")
                false
            }
            if (uploadOk) {
                AutoSwitchLedger.markCutSwitchBackupUploaded(entry.op_id, "切号备份文件已上传：$name")
                okCount++
            } else {
                failCount++
            }
        }
        if (alias.isNotBlank() && (okCount > 0 || failCount > 0)) {
            LogCenter.op(
                alias,
                "【自动切号】切号备份批量上传：成功 $okCount，失败 $failCount（待传条目保留至下次对账）",
            )
        }
    }

    /** 切号还原阶段备份包缺失/损坏：记入 ledger 并改走 exclusive 自动登录。 */
    private suspend fun handleCutSwitchMissingBackupFallback(
        alias: String,
        deviceId: String,
        opId: String,
        payload: DeviceOpPayload?,
        restoreUsername: String,
        cloudBackupName: String,
        phase: String,
        internalDetail: String = "",
        userMessageOverride: String = "",
    ) {
        val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
        val userMessage = userMessageOverride.trim().ifBlank {
            cutSwitchMissingBackupUserMessage(restoreUsername)
        }
        AutoSwitchLedger.recordCutSwitchMissingBackup(
            username = restoreUsername,
            slotKey = slotKey,
            cloudBackupName = cloudBackupName,
            phase = phase,
            message = userMessage,
        )
        AutoSwitchLedger.record(
            opId = opId,
            opType = "tiktok_restore",
            status = "failed",
            message = userMessage,
            slotKey = slotKey,
            username = restoreUsername,
            reportData = buildMap {
                put("op_type", "tiktok_restore")
                put("username", restoreUsername)
                put("backup_name", cloudBackupName)
                put("cut_switch_missing_backup_fallback", true)
                if (phase.contains("corrupt")) {
                    put("cut_switch_corrupt_backup_fallback", true)
                }
                put("internal_detail", internalDetail.take(500))
                payload?.auto_switch_slot_key?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("auto_switch_slot_key", it)
                }
            },
            phaseOverride = "failed",
        )
        LogCenter.op(alias, "【切号】$userMessage")
        finishExclusiveCutSwitchIfLoginOk(alias, deviceId, opId, payload, reportResp = null)
    }

    /** 切号链跑完后：OCR 确认 TikTok 已登录；失败则强制自动登录。通过后才允许标 plan 切号完成。 */
    private suspend fun verifyAutoSwitchCutSwitchSessionAfterChain(
        alias: String,
        deviceId: String,
        opId: String,
        targetUsername: String,
        phase: String,
    ): Boolean {
        val user = targetUsername.trim()
        if (user.isBlank()) {
            LogCenter.op(alias, "【自动切号】切号后登录校验失败（$phase）：缺少目标 username")
            return false
        }
        if (!waitForBasicProfileEditIdle(alias, "切号后登录校验($phase)")) {
            return false
        }
        val launched = executor.launchTikTokWithForegroundVerification(
            ctx = appContext,
            alias = alias,
            tag = "切号后校验",
            forceStopBeforeLaunch = false,
            timingConfig = _config.value,
        )
        if (!launched) {
            LogCenter.op(alias, "【自动切号】切号后登录校验失败（$phase）：TikTok 拉起失败")
            return false
        }
        if (ensureLoginSafeguardMeasure(
                alias,
                deviceId,
                "切号后校验-$phase",
                strict = true,
                deferBasicProfileEdit = true,
            )
        ) {
            return true
        }
        return ensureAccountLoggedIn(
            alias = alias,
            deviceId = deviceId,
            opId = opId,
            reason = "切号后登录校验($phase)",
            strict = true,
            forceRelogin = true,
            preferredUsername = user,
            deferBasicProfileEdit = true,
        )
    }

    /** 无备份 exclusive 切号：自治模式下须登录校验通过后才记为已对齐。 */
    private suspend fun finishExclusiveCutSwitchIfLoginOk(
        alias: String,
        deviceId: String,
        opId: String,
        payload: DeviceOpPayload?,
        reportResp: BaseResponse?,
    ) {
        val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
        val targetRi = payload?.restore_highlight_row_index
            ?: payload?.cut_switch_target_row_index
            ?: -1
        val targetUser = AutoSwitchBundleHolder.getBundle()?.accounts.orEmpty()
            .firstOrNull { (it.row_index ?: -1) == targetRi }
            ?.username?.trim().orEmpty()
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            withContext(NonCancellable) {
                try {
                    val loginOk = verifyAutoSwitchCutSwitchSessionAfterChain(
                        alias = alias,
                        deviceId = deviceId,
                        opId = opId,
                        targetUsername = targetUser,
                        phase = "exclusive_auto_login",
                    )
                    if (loginOk) {
                        finishAutoSwitchCutSwitchAfterSuccess(
                            alias,
                            opId,
                            targetRowIndex = targetRi,
                            slotKey = slotKey.trim(),
                            completionMethod = "exclusive_auto_login",
                        )
                        scheduleCutSwitchDeferredBasicProfileEdit(alias, deviceId, opId)
                    } else {
                        notifyAutoSwitchCutSwitchStepFailed(
                            payload,
                            CutSwitchFailureKind.RESTORE_OR_LOGIN,
                            "切号自动登录失败",
                        )
                    }
                } catch (blocked: AccountPlanBlockedException) {
                    // 登录保障层可能已 handle 过；再调一次云端幂等，并终止本 slot 切号（勿续跑登录）。
                    handleAccountPlanBlocked(
                        alias,
                        deviceId,
                        blocked,
                        "切号后登录校验(exclusive_auto_login)",
                    )
                    val sk = slotKey.trim()
                    if (sk.isNotEmpty()) {
                        AutoSwitchLocalPlanStore.markCutSwitchStepFailed(
                            sk,
                            CutSwitchStepKind.RESTORE_OR_LOGIN,
                        )
                        autoSwitchLocalScheduler.cancelPendingCutSwitchFollowUps(sk)
                        autoSwitchLocalScheduler.clearCutSwitchInflight()
                        AutoSwitchLedger.recordCutSwitchSlotAborted(sk, "账号阻断移出计划")
                    }
                }
            }
            return
        }
        finishCutSwitchAfterSuccess(
            alias,
            deviceId,
            opId,
            reportResp,
            targetRowIndex = targetRi,
            slotKey = slotKey,
        )
    }

    /** 还原子步骤（备份检查 / tar 解压 / 权限修复）失败时重试。 */
    private suspend fun runRestoreShellStep(
        logStep: (String) -> Unit,
        stepLabel: String,
        maxAttempts: Int = 2,
        retryDelayMs: Long = 800,
        block: () -> Boolean,
    ): Boolean {
        repeat(maxAttempts) { attempt ->
            val label =
                if (attempt == 0) stepLabel else "$stepLabel（重试 ${attempt + 1}/$maxAttempts）"
            logStep(label)
            if (block()) return true
            if (attempt < maxAttempts - 1) delay(retryDelayMs)
        }
        return false
    }

    /** 切号还原失败后：按未登录账号强制自动登录，成功则恢复涨粉，失败则暂停。 */
    private suspend fun finishCutSwitchAfterFailureFallback(
        alias: String,
        deviceId: String,
        opId: String,
        slotKey: String = "",
    ) {
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            autoSwitchLocalScheduler.clearCutSwitchInflight()
            val sk = slotKey.trim()
            if (PrefsStore.isHardStopRequested()) {
                LogCenter.op(alias, "【自动切号】切号已硬停，中止重试")
            } else if (sk.isNotEmpty()) {
                LogCenter.op(alias, "【自动切号】切号还原/登录失败，等待下一 tick 续跑（plan 已保留备份+清理进度）")
            }
            return
        }
        restoreCloudAutoStartParticipation(alias, deviceId, opId, "切号还原失败兜底")
        PrefsStore.setHardStopRequested(false)
        manuallyPaused = false

        pullLoopJob?.cancel()
        pullLoopJob = null
        fetchConfig(logToUser = false)

        if (shouldHonorUserHardStop() || manuallyPaused) {
            LogCenter.op(alias, "[OP][$opId] 切号还原失败：硬停/手动暂停，不执行登录兜底")
            return
        }

        val accountResp = resolveAssignedAccount(alias, deviceId)
        if (accountResp == null) {
            pauseGrowthAfterLoginFailure(alias, deviceId, "切号还原失败且拉取分配账号失败")
            return
        }
        if (accountResp.code != 0) {
            pauseGrowthAfterLoginFailure(
                alias,
                deviceId,
                accountResp.msg?.takeIf { it.isNotBlank() } ?: "切号还原失败且无分配账号",
            )
            return
        }

        LogCenter.op(
            alias,
            "[OP][$opId] 切号还原失败：按未登录账号尝试自动登录（username=${accountResp.username.orEmpty()}）",
        )
        lastEnsureLoginPerformedFreshAutoLogin = false
        val launched = executor.launchTikTokWithForegroundVerification(
            ctx = appContext,
            alias = alias,
            tag = "切号还原失败兜底",
            forceStopBeforeLaunch = true,
            timingConfig = _config.value,
        )
        val loginOk = if (!launched) {
            LogCenter.op(alias, "[OP][$opId] 切号还原失败：TikTok 拉起失败")
            false
        } else {
            ensureAccountLoggedIn(
                alias = alias,
                deviceId = deviceId,
                opId = opId,
                reason = "切号还原失败兜底",
                strict = false,
                forceRelogin = true,
                resumeFromLoginEntryScreen = false,
            )
        }

        if (loginOk) {
            LogCenter.op(alias, "[OP][$opId] 切号还原失败兜底：自动登录成功，恢复涨粉")
            forceStartRunningNow("切号还原失败，自动登录成功，恢复涨粉")
            return
        }

        if (!loginOk) {
            LogCenter.op(alias, "[OP][$opId] 切号还原失败兜底：自动登录未通过，交由涨粉循环持续重试")
            forceStartRunningNow("切号还原失败，自动登录重试中")
            return
        }
    }

    /** 自动/手动切号全流程成功后：恢复自动开启、按需自动登录，再拉起涨粉。 */
    private suspend fun finishCutSwitchAfterSuccess(
        alias: String,
        deviceId: String,
        opId: String,
        reportResp: BaseResponse? = null,
        targetRowIndex: Int = -1,
        slotKey: String = "",
    ) {
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            val sk = slotKey.trim()
            val targetUser = AutoSwitchBundleHolder.getBundle()?.accounts.orEmpty()
                .firstOrNull { (it.row_index ?: -1) == targetRowIndex }
                ?.username?.trim().orEmpty()
            val loginOk = try {
                verifyAutoSwitchCutSwitchSessionAfterChain(
                    alias = alias,
                    deviceId = deviceId,
                    opId = opId,
                    targetUsername = targetUser,
                    phase = "restore_chain",
                )
            } catch (blocked: AccountPlanBlockedException) {
                handleAccountPlanBlocked(
                    alias,
                    deviceId,
                    blocked,
                    "切号后登录校验(restore_chain)",
                )
                // 勿走 FailureFallback / RestoreOnly 续跑：账号已阻断。
                if (sk.isNotEmpty()) {
                    AutoSwitchLocalPlanStore.markCutSwitchStepFailed(
                        sk,
                        CutSwitchStepKind.RESTORE_OR_LOGIN,
                    )
                    autoSwitchLocalScheduler.cancelPendingCutSwitchFollowUps(sk)
                    autoSwitchLocalScheduler.clearCutSwitchInflight()
                    AutoSwitchLedger.recordCutSwitchSlotAborted(sk, "账号阻断移出计划")
                }
                return
            }
            if (!loginOk) {
                if (sk.isNotEmpty()) {
                    AutoSwitchLocalPlanStore.markCutSwitchStepFailed(sk, CutSwitchStepKind.RESTORE_OR_LOGIN)
                    autoSwitchLocalScheduler.clearCutSwitchInflight()
                }
                finishCutSwitchAfterFailureFallback(alias, deviceId, opId, sk)
                return
            }
            finishAutoSwitchCutSwitchAfterSuccess(
                alias,
                opId,
                targetRowIndex,
                sk,
                completionMethod = "restore_chain",
            )
            scheduleCutSwitchDeferredBasicProfileEdit(alias, deviceId, opId)
            return
        }
        restoreCloudAutoStartParticipation(alias, deviceId, opId, "切号成功")
        PrefsStore.setHardStopRequested(false)
        manuallyPaused = false

        pullLoopJob?.cancel()
        pullLoopJob = null
        fetchConfig(logToUser = false)
        val accountBeforeSafeguard = resolveAssignedAccount(alias, deviceId)
        val cloudLoginRequiredBeforeSwitch = accountBeforeSafeguard?.code == 0 &&
            accountBeforeSafeguard?.is_login != true
        lastEnsureLoginPerformedFreshAutoLogin = false
        val launched = executor.launchTikTokWithForegroundVerification(
            ctx = appContext,
            alias = alias,
            tag = "切号后保障",
            forceStopBeforeLaunch = true,
            timingConfig = _config.value,
        )
        val loginPathOk = if (!launched) {
            LogCenter.op(alias, "[OP][$opId] 切号后 TikTok 拉起失败")
            false
        } else if (cloudLoginRequiredBeforeSwitch) {
            LogCenter.op(
                alias,
                "[OP][$opId] 切号后云端 is_login=否，执行自动登录（username=${accountBeforeSafeguard?.username.orEmpty()}）",
            )
            try {
                ensureAccountLoggedIn(
                    alias = alias,
                    deviceId = deviceId,
                    opId = opId,
                    reason = "切号后 is_login=否",
                    strict = false,
                    forceRelogin = false,
                    resumeFromLoginEntryScreen = false,
                )
            } catch (blocked: AccountPlanBlockedException) {
                handleAccountPlanBlocked(alias, deviceId, blocked, "切号后 is_login=否")
                false
            }
        } else {
            try {
                ensureLoginSafeguardMeasure(alias, deviceId, "切号完成", strict = false)
            } catch (_: AccountPlanBlockedException) {
                // 保障层已处理移出计划
                false
            }
        }
        if (!loginPathOk) {
            LogCenter.op(alias, "[OP][$opId] 切号后自动登录/登录保障未通过，仍将按云端策略尝试恢复自动开启")
        }

        if (shouldHonorUserHardStop() || manuallyPaused) {
            LogCenter.op(alias, "[OP][$opId] 切号后保持暂停（硬停/手动暂停），不自动拉起")
            return
        }

        if (reportResp?.cut_switch_auto_start_resumed == true) {
            forceStartRunningNow("切号完成，恢复自动开启程序")
            return
        }

        /*
         * 备份/切号前 prepareForDestructiveTikTokOp 会把本机与云端 sync 为 paused；
         * 登录保障已回 Home 时，只要云端仍参与自动开启就应解除临时暂停并 sync running。
         * 不能仅因 check_start 仍返回 status=paused（或上报结果里 auto_start 尚未刷新）就停在「暂未自动拉起」。
         */
        if (loginPathOk) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resp = ApiService.checkStart(deviceId, alias)
                    val cloudAutoStart = resp.auto_start == true || reportResp?.auto_start == true
                    when {
                        resp.code == 0 && resp.status == "running" ->
                            forceStartRunningNow("切号完成，收到启动指令")
                        cloudAutoStart ->
                            forceStartRunningNow("切号完成，登录保障已通过，解除切号临时暂停并恢复任务")
                        resp.code == 1 && resp.auto_start == true ->
                            forceStartRunningNow("切号完成，云端允许自动开启")
                        else ->
                            LogCenter.op(
                                alias,
                                "[OP][$opId] 切号后暂未自动拉起（check_start code=${resp.code} status=${resp.status} auto_start=${resp.auto_start}）",
                            )
                    }
                }
            }.onFailure { err ->
                LogCenter.op(alias, "[OP][$opId] 切号后 check_start 失败：${err.message}，登录保障已通过仍尝试恢复")
                forceStartRunningNow("切号完成，登录保障已通过（check_start 失败仍恢复）")
            }
            return
        }

        runCatching {
            withContext(Dispatchers.IO) {
                val resp = ApiService.checkStart(deviceId, alias)
                val cloudAutoStart = resp.auto_start == true || reportResp?.auto_start == true
                when {
                    resp.code == 0 && resp.status == "running" ->
                        forceStartRunningNow("切号完成，收到启动指令")
                    resp.code == 1 && resp.auto_start == true ->
                        forceStartRunningNow("切号完成，云端允许自动开启")
                    reportResp?.auto_start == true ->
                        forceStartRunningNow("切号完成，恢复自动开启程序")
                    cloudAutoStart ->
                        forceStartRunningNow("切号完成，按云端自动开启恢复任务")
                    else ->
                        LogCenter.op(
                            alias,
                            "[OP][$opId] 切号后暂未自动拉起（登录保障未通过；check_start code=${resp.code} status=${resp.status} auto_start=${resp.auto_start}）",
                        )
                }
            }
        }.onFailure { err ->
            LogCenter.op(alias, "[OP][$opId] 切号后 check_start 失败：${err.message}")
        }
    }

    /**
     * 自动切号自治：优先用已下发 bundle（时段目标账号 + 凭据 + is_login），避免再拉 assigned_account。
     * 非自治或 bundle 缺数据时回退云端 API。
     */
    private suspend fun resolveAssignedAccount(
        alias: String,
        deviceId: String,
        preferredUsername: String? = null,
    ): DeviceAssignedAccountResponse? {
        if (ManualDeviceControl.ENABLED) {
            val prefer = preferredUsername?.trim()?.removePrefix("@").orEmpty()
                .ifBlank { ManualDeviceControl.selectedManualUsername() }
            if (prefer.isNotBlank()) {
                ManualDeviceControl.findCachedAccount(prefer)?.let { opt ->
                    val twoFa = opt.twoFa.ifBlank {
                        resolveManualAccountTwoFa(alias, deviceId, opt.username)
                    }
                    return DeviceAssignedAccountResponse(
                        code = 0,
                        msg = "ok",
                        row_index = opt.rowIndex,
                        username = opt.username,
                        email = opt.email,
                        password = opt.password,
                        email_password = opt.emailPassword,
                        two_fa = twoFa,
                        api = opt.api,
                        is_login = if (PrefsStore.isManualFirstLoginDone(opt.username)) {
                            true
                        } else {
                            opt.cloudIsLogin
                        },
                        basic_profile_modified = opt.basicProfileModified,
                        mail_module_code_available = opt.mailModuleCodeAvailable,
                    )
                }
            }
        }
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            AutoSwitchBundleHolder.resolveAssignedAccount(
                preferredUsername = preferredUsername,
                logContextUsername = PrefsStore.getLogContextUsername(),
            )?.let { bundleResp ->
                if (bundleResp.code == 0) return bundleResp
                if (!preferredUsername.isNullOrBlank()) return bundleResp
            }
        }
        return resolveAssignedAccountFromCloud(alias, deviceId)
    }

    private suspend fun resolveAssignedAccountFromCloud(
        alias: String,
        deviceId: String,
    ): DeviceAssignedAccountResponse? =
        runCatching {
            withContext(Dispatchers.IO) {
                ApiService.getDeviceAssignedAccount(deviceId, alias)
            }
        }.getOrNull()

    /** 手动模式旧缓存可能缺 two_fa：从账号列表补全并写回本机缓存。 */
    private suspend fun resolveManualAccountTwoFa(
        alias: String,
        deviceId: String,
        username: String,
    ): String {
        val resp = runCatching {
            withContext(Dispatchers.IO) {
                ApiService.getDeviceAssignedAccountsRoster(deviceId, alias)
            }
        }.getOrNull() ?: return ""
        if (resp.code != 0) return ""
        val twoFa = resp.items.orEmpty()
            .firstOrNull {
                it.username?.trim()?.removePrefix("@").equals(username, ignoreCase = true)
            }
            ?.two_fa
            ?.trim()
            .orEmpty()
        if (twoFa.isNotBlank()) {
            ManualDeviceControl.updateCachedAccountTwoFa(username, twoFa)
        }
        return twoFa
    }

    /**
     * 涨粉拉取循环门禁（已登录前提下）：校验分配账号、执行一次性登录状态保障。
     */
    private suspend fun ensureAssignedAccountReadyForGrowthPull(
        alias: String,
        deviceId: String,
    ): Boolean {
        val accountResp = resolveAssignedAccount(alias, deviceId)
        if (accountResp == null) {
            pauseGrowthAfterLoginFailure(alias, deviceId, "拉取分配账号失败")
            return false
        }
        if (accountResp.code != 0) {
            pauseGrowthAfterLoginFailure(
                alias,
                deviceId,
                accountResp.msg?.takeIf { it.isNotBlank() } ?: "无分配账号",
            )
            return false
        }
        accountResp.username?.trim()?.takeIf { it.isNotBlank() }?.let {
            PrefsStore.setLogContextUsername(it)
        }
        if (PrefsStore.peekGrowthContinueFromPublishHome()) {
            growthModeLoginSafeguardPending = false
            LogCenter.op(alias, "涨粉：发作品已收尾于 Home，跳过登录状态保障（直接放大镜）")
            return true
        }
        if (!growthModeLoginSafeguardPending) {
            return true
        }
        if (basicProfileEditRunning) {
            LogCenter.op(alias, "涨粉：基本资料修改仍在执行，暂停拉取")
            return false
        }
        if (lastEnsureLoginPerformedFreshAutoLogin) {
            growthModeLoginSafeguardPending = false
            lastEnsureLoginPerformedFreshAutoLogin = false
            LogCenter.op(alias, "涨粉：刚完成自动登录，跳过登录状态保障")
            maybeRunPendingBasicProfileEditAfterLoginVerified(
                alias = alias,
                deviceId = deviceId,
                parentOpId = "growth_after_auto_login_${System.currentTimeMillis()}",
                trigger = "growth_after_fresh_auto_login",
                skipOuterWarmup = true,
                loginVerified = true,
            )
            if (PrefsStore.getLocalStatus() != "running") {
                resumeRunningAfterGrowthAutoLogin(alias, deviceId)
            }
            return true
        }
        LogCenter.op(alias, "涨粉：云端涨粉模式，执行登录状态保障（最多 ${loginSafeguardOuterMaxAttempts} 次）")
        val safeguardOk = launchTikTokWithLoginSafeguardWithRetry(
            alias = alias,
            deviceId = deviceId,
            tag = "涨粉模式保障",
            forceStopBeforeLaunch = true,
            safeguardSource = "云端涨粉模式",
            strict = true,
        )
        if (!safeguardOk) {
            growthModeLoginSafeguardPending = true
            pauseGrowthAfterLoginFailure(alias, deviceId, "登录状态保障失败")
            return false
        }
        growthModeLoginSafeguardPending = false
        maybeRunPendingBasicProfileEditAfterLoginVerified(
            alias = alias,
            deviceId = deviceId,
            parentOpId = "growth_safeguard_${System.currentTimeMillis()}",
            trigger = "growth_login_safeguard",
            skipOuterWarmup = false,
            loginVerified = true,
        )
        if (PrefsStore.getLocalStatus() != "running") {
            resumeRunningAfterGrowthAutoLogin(alias, deviceId)
        }
        return true
    }

    private suspend fun resumeRunningAfterGrowthAutoLogin(alias: String, deviceId: String) {
        resumeRunningForCloudTask(alias, deviceId, "自动登录成功，继续涨粉")
    }

    private suspend fun resumeRunningForCloudTask(alias: String, deviceId: String, reason: String) {
        if (shouldHonorUserHardStop() || manuallyPaused) return
        PrefsStore.saveLocalStatus("running")
        updateRuntime(localStatus = "running", lastMessage = reason)
        runCatching {
            syncCloudStatus("running", CloudStatusSyncKind.GROWTH)
        }.onFailure { err ->
            LogCenter.op(alias, "同步 running 失败：${err.message}")
        }
    }

    private enum class AccountLoginRetryOutcome {
        LoggedIn,
        Aborted,
        /** 自动切号：分配账号已切换，本账号时段结束 */
        AccountChanged,
    }

    /**
     * 云端 is_login=否 时持续重试自动登录。
     * @param expectedUsername 自动切号场景：分配账号 username 变化时停止重试（等待云端切号）。
     */
    private suspend fun ensureAccountLoggedInWithRetry(
        alias: String,
        deviceId: String,
        reason: String,
        expectedUsername: String? = null,
        resumeRunningOnSuccess: Boolean = true,
        forceRelogin: Boolean = false,
        resumeFromLoginEntryScreen: Boolean = false,
    ): AccountLoginRetryOutcome {
        if (reason.contains("掉登录")) {
            clearMutualClickStateForInterrupt("掉登录($reason)")
        }
        if (PublishPostGrowthGate.shouldBlockExternalAutoLogin(reason)) {
            LogCenter.op(alias, "自动登录暂缓：发作品独占发布中（$reason）")
            return AccountLoginRetryOutcome.Aborted
        }
        val expected = expectedUsername?.trim()?.takeIf { it.isNotEmpty() }
        var nextForceRelogin = forceRelogin
        var nextResumeFromEntry = resumeFromLoginEntryScreen
        while (true) {
            if (shouldHonorUserHardStop() || manuallyPaused) {
                return AccountLoginRetryOutcome.Aborted
            }
            if (expected != null) {
                val current = resolveAssignedAccount(alias, deviceId, preferredUsername = expected)
                    ?.username?.trim().orEmpty()
                if (current.isNotBlank() && !current.equals(expected, ignoreCase = true)) {
                    LogCenter.op(
                        alias,
                        "自动登录重试中止：分配账号已切换（期望=$expected 当前=$current，$reason）",
                    )
                    return AccountLoginRetryOutcome.AccountChanged
                }
            }
            val accountSnapshot = if (!nextForceRelogin) {
                resolveAssignedAccount(alias, deviceId, preferredUsername = expected)
            } else {
                null
            }
            if (!nextForceRelogin && accountSnapshot != null && accountSnapshot.code == 0 && accountSnapshot.is_login == true) {
                maybeRunPendingBasicProfileEditAfterLoginVerified(
                    alias = alias,
                    deviceId = deviceId,
                    parentOpId = "login_retry_${System.currentTimeMillis()}",
                    trigger = "login_retry_cloud_is_login",
                    skipOuterWarmup = false,
                    loginVerified = true,
                )
                if (resumeRunningOnSuccess) {
                    resumeRunningForCloudTask(alias, deviceId, "自动登录成功（$reason）")
                }
                return AccountLoginRetryOutcome.LoggedIn
            }
            val fromBundle = PrefsStore.isAutoSwitchAutonomousMode()
            val retryLog = when {
                nextForceRelogin ->
                    "TikTok 已掉登录，强制重新登录（$reason；is_login 仍可能为是，重登成功后将更新 login_time）"
                accountSnapshot == null ->
                    if (fromBundle) "bundle 内无可用账号，尝试自动登录（$reason）"
                    else "拉取分配账号失败，尝试自动登录（$reason）"
                accountSnapshot.code != 0 ->
                    "无有效账号（${accountSnapshot.msg ?: "code=${accountSnapshot.code}"}），尝试自动登录（$reason）"
                accountSnapshot.is_login != true ->
                    if (fromBundle) "bundle is_login=否，尝试自动登录（$reason）"
                    else "云端 is_login=否，尝试自动登录（$reason）"
                else ->
                    "尝试自动登录（$reason）"
            }
            LogCenter.op(alias, retryLog)
            val loginOk = try {
                ensureAccountLoggedIn(
                    alias = alias,
                    deviceId = deviceId,
                    opId = "login_retry_${System.currentTimeMillis()}",
                    reason = reason,
                    strict = true,
                    forceRelogin = nextForceRelogin,
                    resumeFromLoginEntryScreen = nextResumeFromEntry,
                    preferredUsername = expected,
                )
            } catch (banned: AccountPlanBlockedException) {
                handleAccountPlanBlocked(alias, deviceId, banned, reason)
                return AccountLoginRetryOutcome.Aborted
            }
            if (loginOk) {
                autoLoginUnrecoverableCodeFetchAttempts = 0
                if (resumeRunningOnSuccess) {
                    resumeRunningForCloudTask(alias, deviceId, "自动登录成功（$reason）")
                }
                return AccountLoginRetryOutcome.LoggedIn
            }
            if (!forceRelogin) {
                nextForceRelogin = false
            }
            nextResumeFromEntry = false
            if (TikTokEmailCodeFetcher.consumeLastFetchUnrecoverableConfig()) {
                autoLoginUnrecoverableCodeFetchAttempts++
                LogCenter.op(
                    alias,
                    "自动登录：验证码配置不可恢复（$autoLoginUnrecoverableCodeFetchAttempts/" +
                        "$autoLoginUnrecoverableCodeFetchMaxAttempts）",
                )
                if (autoLoginUnrecoverableCodeFetchAttempts >= autoLoginUnrecoverableCodeFetchMaxAttempts) {
                    autoLoginUnrecoverableCodeFetchAttempts = 0
                    val pauseMsg = "自动登录暂停：邮箱 OAuth/API 未配置，无法获取验证码"
                    LogCenter.op(alias, pauseMsg)
                    PrefsStore.saveLocalStatus("paused")
                    updateRuntime(localStatus = "paused", lastMessage = pauseMsg)
                    runCatching {
                        syncCloudStatus("paused", CloudStatusSyncKind.ROUTINE)
                    }
                    return AccountLoginRetryOutcome.Aborted
                }
                val waitMs = Random.nextLong(15_000L, 30_000L)
                LogCenter.op(alias, "自动登录失败（验证码配置缺失），${waitMs / 1000} 秒后重试（$reason）")
                if (!delayUnlessHardStop(waitMs)) {
                    return AccountLoginRetryOutcome.Aborted
                }
                continue
            }
            autoLoginUnrecoverableCodeFetchAttempts = 0
            resumeRunningForCloudTask(alias, deviceId, "自动登录重试中（$reason）")
            val waitMs = Random.nextLong(15_000L, 30_000L)
            LogCenter.op(alias, "自动登录失败，${waitMs / 1000} 秒后重试（$reason）")
            if (!delayUnlessHardStop(waitMs)) {
                return AccountLoginRetryOutcome.Aborted
            }
        }
    }

    /**
     * 云端 is_login=否 时持续重试自动登录，不因单次失败暂停涨粉。
     * @return true 已可继续涨粉；false 硬停、手动暂停或自动切号账号已切换
     */
    private suspend fun ensureGrowthAccountLoggedInWithRetry(
        alias: String,
        deviceId: String,
        reason: String,
        forceRelogin: Boolean = false,
        resumeFromLoginEntryScreen: Boolean = false,
    ): Boolean {
        return when (
            ensureAccountLoggedInWithRetry(
                alias = alias,
                deviceId = deviceId,
                reason = reason,
                forceRelogin = forceRelogin,
                resumeFromLoginEntryScreen = resumeFromLoginEntryScreen,
            )
        ) {
            AccountLoginRetryOutcome.LoggedIn -> true
            AccountLoginRetryOutcome.AccountChanged -> false
            AccountLoginRetryOutcome.Aborted -> false
        }
    }

    /** 发作品/互点插入调度前：暂停涨粉、等当前步骤收尾、force-stop TikTok，独占通道。 */
    private suspend fun preemptTikTokForInsertionPrep(alias: String, phaseLabel: String): Boolean {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return true
        if (PublishPostGrowthGate.shouldDeferExclusivePreempt()) return true
        pauseGrowthForAutoSwitchExclusiveOp()
        awaitExecutionScopeIdleForAutoSwitch(alias, maxMs = 60_000L)
        if (ExecutionScope.isActive()) {
            LogCenter.op(alias, "【自动切号】$phaseLabel：涨粉未协作退出，本轮跳过")
            releaseInsertionPrepExclusiveIfIdle("$phaseLabel-涨粉占用")
            return false
        }
        forceStopTikTokForExclusiveEnqueue(phaseLabel)
        return true
    }

    private fun releaseInsertionPrepExclusiveIfIdle(reason: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (autoSwitchLocalScheduler.isExclusiveWorkActive()) return
        PrefsStore.setAutoSwitchExclusivePreempt(false)
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
            PrefsStore.saveLocalStatus("paused")
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            val detail = PrefsStore.autoSwitchTikTokLoginHoldReason().orEmpty()
            if (alias.isNotBlank()) {
                LogCenter.op(
                    alias,
                    "【自动切号】$reason：登录保障恢复中${if (detail.isNotEmpty()) "（$detail）" else ""}，不恢复涨粉",
                )
            }
            updateRuntime(localStatus = "paused", lastMessage = detail.ifBlank { "登录保障恢复中" })
            return
        }
        if (!manuallyPaused && !shouldHonorUserHardStop() && autoSwitchLocalScheduler.shouldAllowGrowthBaseline()) {
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "自动切号自治：$reason")
        }
        maybeStartGrowthInAutoSwitchIdle(reason)
    }

    /**
     * 自动切号 tick 发作品入队前：先独占 TikTok，再 bundle is_login + OCR 登录状态保障。
     * 失败时标记本时段发作品 FAILED 并进入登录保障恢复，避免每分钟 tick 重试入队前 OCR。
     */
    private suspend fun verifyPublishInsertionLoginBeforeEnqueue(
        alias: String,
        deviceId: String,
        targetUsername: String,
        slotKey: String,
    ): Boolean {
        val user = targetUsername.trim()
        val sk = slotKey.trim()
        if (user.isBlank() || deviceId.isBlank()) return false
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive(sk)) {
            LogCenter.op(alias, "【自动切号】发作品入队前跳过：登录保障恢复中 slot=$sk")
            return false
        }
        if (!preemptTikTokForInsertionPrep(alias, "发作品入队前")) return false
        PrefsStore.setLogContextUsername(user)
        applyBundledConfigFromAutoSwitch(user, logToUser = false)

        if (!isAssignedAccountLoggedIn(alias, deviceId, preferredUsername = user)) {
            when (
                ensureAccountLoggedInWithRetry(
                    alias = alias,
                    deviceId = deviceId,
                    reason = "发作品入队前",
                    expectedUsername = user,
                )
            ) {
                AccountLoginRetryOutcome.LoggedIn -> Unit
                AccountLoginRetryOutcome.AccountChanged -> {
                    LogCenter.op(alias, "【自动切号】发作品入队前账号不符 slot=$sk")
                    if (sk.isNotEmpty()) {
                        autoSwitchLocalScheduler.invalidateSlotAlignment(sk, "发作品入队前账号切换")
                    }
                    releaseInsertionPrepExclusiveIfIdle("发作品入队前账号切换")
                    return false
                }
                AccountLoginRetryOutcome.Aborted -> {
                    LogCenter.op(alias, "【自动切号】发作品入队前自动登录中止 slot=$sk")
                    activateAutoSwitchTikTokLoginHold(sk, "发作品入队前自动登录中止")
                    releaseInsertionPrepExclusiveIfIdle("发作品入队前自动登录中止")
                    return false
                }
            }
        }

        val ocrOk = launchTikTokWithLoginSafeguardWithRetry(
            alias = alias,
            deviceId = deviceId,
            tag = "发作品入队前",
            forceStopBeforeLaunch = false,
            safeguardSource = "发作品入队前",
            strict = true,
        )
        if (!ocrOk) {
            LogCenter.op(alias, "【自动切号】发作品入队前 OCR 登录保障未通过 slot=$sk account=$user")
            activateAutoSwitchTikTokLoginHold(sk, "发作品入队前 OCR 登录保障未通过")
            releaseInsertionPrepExclusiveIfIdle("发作品入队前 OCR 未通过")
        } else {
            PrefsStore.clearAutoSwitchTikTokLoginHold()
        }
        return ocrOk
    }

    /** 发作品/互点等独占任务前：未登录则先自动登录（含重试），成功后才可执行业务流程。 */
    private suspend fun ensureLoggedInBeforeExclusiveTask(
        alias: String,
        deviceId: String,
        taskLabel: String,
        clearPublishOnAccountChange: Boolean = false,
    ): Boolean {
        if (ManualDeviceControl.ENABLED) {
            val user = ManualDeviceControl.selectedManualUsername()
            if (user.isBlank() || !PrefsStore.isManualFirstLoginDone(user)) {
                LogCenter.op(alias, "$taskLabel：手动模式未就绪，请先切号（有备份还原 / 无备份自动登录）")
                return false
            }
            // 已就绪：不在入口主动跑自动登录；任务中途 OCR 掉登录再触发。
            return true
        }
        val expectedUser = AutoSwitchLocalPlanStore.activePublishAccountUsername()
        if (isAssignedAccountLoggedIn(alias, deviceId, preferredUsername = expectedUser)) {
            return true
        }
        return when (
            ensureAccountLoggedInWithRetry(
                alias = alias,
                deviceId = deviceId,
                reason = "${taskLabel}前",
                expectedUsername = expectedUser,
            )
        ) {
            AccountLoginRetryOutcome.LoggedIn -> true
            AccountLoginRetryOutcome.AccountChanged -> {
                LogCenter.op(alias, "$taskLabel：自动切号时段已切换，跳过（is_login 未就绪）")
                if (clearPublishOnAccountChange) {
                    PrefsStore.clearPublishPostState()
                }
                false
            }
            AccountLoginRetryOutcome.Aborted -> {
                LogCenter.op(alias, "$taskLabel：自动登录中止，跳过（is_login 未就绪）")
                false
            }
        }
    }

    /**
     * 素材已就绪后进入发作品独占阶段：暂停涨粉、上报 opt_out，并在需要时完成登录保障。
     * @return true 表示可继续 TikTok 发布流程
     */
    private suspend fun enterPublishPostExclusivePhase(alias: String): Boolean {
        pullLoopJob?.cancel()
        pullLoopJob = null
        delay(120)
        LogCenter.op(alias, "发作品：素材已就绪，暂停涨粉并进入发布独占阶段")
        val deviceId = PrefsStore.getDeviceId().orEmpty()
        val publishOpId = PrefsStore.getPublishPostTaskOpId().orEmpty()
        if (deviceId.isBlank()) {
            LogCenter.op(alias, "发作品：未绑定 deviceId，中止发布独占阶段")
            return false
        }
        val expectedUser = AutoSwitchLocalPlanStore.activePublishAccountUsername()
        if (ManualDeviceControl.ENABLED) {
            val user = expectedUser?.trim().orEmpty()
                .ifBlank { ManualDeviceControl.selectedManualUsername() }
            if (user.isBlank() || !PrefsStore.isManualFirstLoginDone(user)) {
                LogCenter.op(alias, "发作品：手动模式未就绪，请先切号（有备份还原 / 无备份自动登录）")
                return false
            }
            // 已就绪：不在入口主动跑自动登录；OCR 掉登录由后续保障触发。
        } else if (!isAssignedAccountLoggedIn(alias, deviceId, preferredUsername = expectedUser)) {
            when (
                ensureAccountLoggedInWithRetry(
                    alias = alias,
                    deviceId = deviceId,
                    reason = "发作品前",
                    expectedUsername = expectedUser,
                )
            ) {
                AccountLoginRetryOutcome.LoggedIn -> { /* continue */ }
                AccountLoginRetryOutcome.AccountChanged -> {
                    LogCenter.op(alias, "发作品：自动切号时段已切换，已跳过发作品入口流程")
                    if (publishOpId.isNotBlank()) {
                        reportAutoSwitchPublishOutcomeIfNeeded(
                            alias = alias,
                            deviceId = deviceId,
                            opId = publishOpId,
                            completed = false,
                            loginFailed = false,
                            accountChanged = true,
                            message = "自动切号时段已切换，停止本时段发作品",
                        )
                    }
                    PrefsStore.clearPublishPostState()
                    return false
                }
                AccountLoginRetryOutcome.Aborted -> {
                    LogCenter.op(alias, "发作品：自动登录中止，已跳过发作品入口流程（本时段可重新调度）")
                    if (publishOpId.isNotBlank()) {
                        reportAutoSwitchPublishOutcomeIfNeeded(
                            alias = alias,
                            deviceId = deviceId,
                            opId = publishOpId,
                            completed = false,
                            loginFailed = false,
                            accountChanged = false,
                            message = "发作品自动登录中止",
                        )
                    } else {
                        PrefsStore.clearPublishPostState()
                        autoSwitchLocalScheduler.notifyPublishOutcome(
                            slotKey = AutoSwitchLocalPlanStore.activePublishSlotKey().orEmpty(),
                            completed = false,
                            loginFailed = false,
                            accountChanged = false,
                        )
                    }
                    return false
                }
            }
        }
        runCatching {
            withContext(Dispatchers.IO) {
                reportAutoStartOptOutIfNeeded(deviceId = deviceId, alias = alias, optOut = true)
            }
        }.onFailure { err ->
            LogCenter.op(alias, "发作品：上报关闭自动开启失败：${err.message}")
        }
        return true
    }

    private suspend fun pauseGrowthAfterLoginFailure(
        alias: String,
        deviceId: String,
        reason: String,
    ) {
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            val sk = PrefsStore.getAutoSwitchLastSlotKey().orEmpty()
            activateAutoSwitchTikTokLoginHold(sk, reason)
            growthModeLoginSafeguardPending = true
            return
        }
        LogCenter.op(alias, "涨粉：$reason，程序已暂停（暂不拉取/执行任务）")
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(localStatus = "paused", lastMessage = reason)
        runCatching {
            syncCloudStatus("paused", CloudStatusSyncKind.ROUTINE)
        }.onFailure { err ->
            LogCenter.op(alias, "涨粉：同步 paused 失败：${err.message}")
        }
    }

    private suspend fun isAssignedAccountLoggedIn(
        alias: String,
        deviceId: String,
        preferredUsername: String? = null,
    ): Boolean {
        val accountResp = resolveAssignedAccount(alias, deviceId, preferredUsername) ?: return false
        return accountResp.code == 0 && accountResp.is_login == true
    }

    /**
     * 登录状态保障：TikTok 已在前台时 OCR 点 Profile 判定界面登录态；需登录则走自动登录链。
     * 与云端 `is_login` 字段无关，仅依据 TikTok 界面 OCR。
     * 整段（OCR + 必要自动登录）占用 [LoginSafeguardGate]，与互点/发作品冷启共用单飞。
     * @param strict true：自动登录失败等返回 false；false：切号后尽力登录，凭证不齐等仍可返回 true。
     */
    private suspend fun ensureLoginSafeguardMeasure(
        alias: String,
        deviceId: String,
        source: String,
        strict: Boolean = true,
        deferBasicProfileEdit: Boolean = false,
    ): Boolean = LoginSafeguardGate.withExclusive(source) {
        ensureLoginSafeguardMeasureUnderGate(
            alias = alias,
            deviceId = deviceId,
            source = source,
            strict = strict,
            deferBasicProfileEdit = deferBasicProfileEdit,
        )
    }

    private suspend fun ensureLoginSafeguardMeasureUnderGate(
        alias: String,
        deviceId: String,
        source: String,
        strict: Boolean,
        deferBasicProfileEdit: Boolean,
    ): Boolean {
        fetchConfig(logToUser = false)
        return try {
            when (executor.runLoginSafeguardMeasure(appContext, source)) {
                LoginSafeguardOutcome.Ready -> {
                    LogCenter.op(alias, "登录状态保障($source)：界面可继续，已回 Home")
                    if (ManualDeviceControl.ENABLED) {
                        val u = ManualDeviceControl.activeManualUsername()
                            .ifBlank { ManualDeviceControl.selectedManualUsername() }
                        if (u.isNotBlank()) {
                            markManualAccountReady(u, "登录状态检测通过($source)")
                        }
                    }
                    if (!deferBasicProfileEdit) {
                        maybeRunPendingBasicProfileEditAfterLoginVerified(
                            alias = alias,
                            deviceId = deviceId,
                            parentOpId = "safeguard_ready_${System.currentTimeMillis()}",
                            trigger = "login_safeguard_ready",
                            skipOuterWarmup = false,
                            loginVerified = true,
                        )
                    }
                    true
                }
                LoginSafeguardOutcome.RequiresAutoLogin -> {
                    LogCenter.op(alias, "登录状态保障($source)：界面需登录，启动自动登录")
                    val opId = "safeguard_${System.currentTimeMillis()}"
                    val ok = ensureAccountLoggedIn(
                        alias = alias,
                        deviceId = deviceId,
                        opId = opId,
                        reason = "登录状态保障($source)",
                        strict = strict,
                        forceRelogin = true,
                        resumeFromLoginEntryScreen = true,
                        deferBasicProfileEdit = deferBasicProfileEdit,
                    )
                    if (ok && ManualDeviceControl.ENABLED) {
                        val u = ManualDeviceControl.activeManualUsername()
                            .ifBlank { ManualDeviceControl.selectedManualUsername() }
                        if (u.isNotBlank()) {
                            markManualAccountReady(u, "登录状态保障自动登录($source)")
                        }
                    }
                    ok
                }
                LoginSafeguardOutcome.Failed -> {
                    LogCenter.op(alias, "登录状态保障($source)：执行失败")
                    false
                }
            }
        } catch (logout: LoginSafeguardRequiresAutoLoginException) {
            LogCenter.op(alias, "登录状态保障($source)：OCR 见 Add another account，从登录入口续接自动登录")
            return try {
                val ok = ensureAccountLoggedIn(
                    alias = alias,
                    deviceId = deviceId,
                    opId = "safeguard_add_account_${System.currentTimeMillis()}",
                    reason = "登录状态保障($source)-Add another account",
                    strict = strict,
                    forceRelogin = true,
                    resumeFromLoginEntryScreen = true,
                    deferBasicProfileEdit = deferBasicProfileEdit,
                )
                if (ok && ManualDeviceControl.ENABLED) {
                    val u = ManualDeviceControl.activeManualUsername()
                        .ifBlank { ManualDeviceControl.selectedManualUsername() }
                    if (u.isNotBlank()) markManualAccountReady(u, "登录状态保障自动登录($source)")
                }
                ok
            } catch (banned: AccountPlanBlockedException) {
                handleAccountPlanBlocked(alias, deviceId, banned, "登录状态保障($source)")
                throw banned
            }
        } catch (banned: AccountPlanBlockedException) {
            handleAccountPlanBlocked(alias, deviceId, banned, "登录状态保障($source)")
            throw banned
        } catch (logout: AccountStatusLoggedOutException) {
            LogCenter.op(alias, "登录状态保障($source)：Account status 掉登录，重新自动登录")
            executor.setAccountStatusReloginInProgress(true)
            return try {
                val ok = ensureAccountLoggedIn(
                    alias = alias,
                    deviceId = deviceId,
                    opId = "safeguard_acct_status_${System.currentTimeMillis()}",
                    reason = "登录状态保障($source)-Account status",
                    strict = strict,
                    forceRelogin = true,
                    deferBasicProfileEdit = deferBasicProfileEdit,
                )
                if (ok && ManualDeviceControl.ENABLED) {
                    val u = ManualDeviceControl.activeManualUsername()
                        .ifBlank { ManualDeviceControl.selectedManualUsername() }
                    if (u.isNotBlank()) markManualAccountReady(u, "登录状态保障自动登录($source)")
                }
                ok
            } catch (banned: AccountPlanBlockedException) {
                handleAccountPlanBlocked(alias, deviceId, banned, "登录状态保障($source)")
                throw banned
            }
        }
    }

    private suspend fun launchTikTokWithLoginSafeguard(
        alias: String,
        deviceId: String,
        tag: String,
        forceStopBeforeLaunch: Boolean,
        safeguardSource: String,
        strict: Boolean = true,
    ): Boolean {
        lastEnsureLoginPerformedFreshAutoLogin = false
        val launched = try {
            executor.launchTikTokWithForegroundVerification(
                ctx = appContext,
                alias = alias,
                tag = tag,
                forceStopBeforeLaunch = forceStopBeforeLaunch,
                timingConfig = _config.value,
            )
        } catch (logout: LoginSafeguardRequiresAutoLoginException) {
            // 拉起后 Don't allow OCR 若仍误触 Welcome back 上的 Add another，直接从当前登录入口续接。
            LogCenter.op(
                alias,
                "登录状态保障：拉起阶段见登录入口（$safeguardSource），从当前页续接自动登录",
            )
            return try {
                ensureAccountLoggedIn(
                    alias = alias,
                    deviceId = deviceId,
                    opId = "safeguard_launch_${System.currentTimeMillis()}",
                    reason = "登录状态保障($safeguardSource)-拉起见登录入口",
                    strict = strict,
                    forceRelogin = true,
                    resumeFromLoginEntryScreen = true,
                )
            } catch (banned: AccountPlanBlockedException) {
                handleAccountPlanBlocked(alias, deviceId, banned, "登录状态保障($safeguardSource)")
                throw banned
            }
        }
        if (!launched) {
            LogCenter.op(alias, "登录状态保障：TikTok 拉起失败（$safeguardSource）")
            return false
        }
        return try {
            ensureLoginSafeguardMeasure(alias, deviceId, safeguardSource, strict)
        } catch (blocked: AccountPlanBlockedException) {
            // 保障层已 handle；继续上抛，避免 WithRetry 把 false 当可重试失败。
            throw blocked
        }
    }

    /** 整段登录状态保障（拉起 → 前台校验 → OCR → 必要时自动登录）外层重试；仅全部失败后才由调用方 pause。 */
    private suspend fun launchTikTokWithLoginSafeguardWithRetry(
        alias: String,
        deviceId: String,
        tag: String,
        forceStopBeforeLaunch: Boolean,
        safeguardSource: String,
        strict: Boolean = true,
        maxAttempts: Int = loginSafeguardOuterMaxAttempts,
        delayBetweenMs: Long = loginSafeguardRetryDelayMs,
    ): Boolean {
        if (PublishPostGrowthGate.shouldBlockExternalLoginSafeguard()) {
            LogCenter.op(alias, "登录状态保障暂缓：发作品独占发布中（$safeguardSource）")
            return false
        }
        return LoginSafeguardGate.withExclusive(safeguardSource) {
            // 排队等待后：若其它入口已转入登录恢复（除恢复任务、切号校验），不再叠跑
            if (
                PrefsStore.isAutoSwitchLoginRecoveryActive() &&
                safeguardSource != "登录保障恢复" &&
                !LoginSafeguardGate.isCutSwitchOwner(safeguardSource)
            ) {
                LogCenter.op(alias, "登录状态保障跳过：登录保障恢复中（$safeguardSource）")
                return@withExclusive false
            }
            if (PublishPostGrowthGate.shouldBlockExternalLoginSafeguard()) {
                LogCenter.op(alias, "登录状态保障暂缓：发作品独占发布中（$safeguardSource）")
                return@withExclusive false
            }
            for (attempt in 1..maxAttempts) {
                if (shouldHonorUserHardStop() || manuallyPaused) return@withExclusive false
                if (attempt > 1) {
                    LogCenter.op(
                        alias,
                        "登录状态保障：第 $attempt/$maxAttempts 次重试（$safeguardSource）",
                    )
                    if (!delayUnlessHardStop(delayBetweenMs)) return@withExclusive false
                }
                val forceStopThisAttempt = forceStopBeforeLaunch || attempt > 1
                val ok = try {
                    launchTikTokWithLoginSafeguard(
                        alias = alias,
                        deviceId = deviceId,
                        tag = tag,
                        forceStopBeforeLaunch = forceStopThisAttempt,
                        safeguardSource = safeguardSource,
                        strict = strict,
                    )
                } catch (blocked: AccountPlanBlockedException) {
                    // 保障/登录链已 handleAccountPlanBlocked；禁止外层继续重试同一账号。
                    return@withExclusive false
                } catch (logout: LoginSafeguardRequiresAutoLoginException) {
                    // 兜底：拉起层若仍上抛 Welcome back/Add another，从入口续接，禁止冒成进程崩溃。
                    LogCenter.op(
                        alias,
                        "登录状态保障：重试层捕获登录入口信号（$safeguardSource），从当前页续接自动登录",
                    )
                    try {
                        ensureAccountLoggedIn(
                            alias = alias,
                            deviceId = deviceId,
                            opId = "safeguard_retry_entry_${System.currentTimeMillis()}",
                            reason = "登录状态保障($safeguardSource)-重试层见登录入口",
                            strict = strict,
                            forceRelogin = true,
                            resumeFromLoginEntryScreen = true,
                        )
                    } catch (banned: AccountPlanBlockedException) {
                        handleAccountPlanBlocked(alias, deviceId, banned, "登录状态保障($safeguardSource)")
                        return@withExclusive false
                    }
                }
                if (ok) {
                    return@withExclusive true
                }
            }
            LogCenter.op(alias, "登录状态保障：$maxAttempts 次尝试均失败（$safeguardSource）")
            false
        }
    }

    private suspend fun recoverFromAccountStatusLogout(alias: String, deviceId: String, source: String): Boolean {
        clearMutualClickStateForInterrupt("掉登录($source)")
        LogCenter.op(alias, "掉登录恢复：终止当前步骤，重新自动登录并接回原步骤（来源=$source）")
        executor.setAccountStatusReloginInProgress(true)
        val opId = "acct_status_${System.currentTimeMillis()}"
        return try {
            val ok = ensureAccountLoggedIn(
                alias = alias,
                deviceId = deviceId,
                opId = opId,
                reason = "掉登录恢复($source)",
                strict = true,
                forceRelogin = true,
            )
            // 无 OK 路径会 pm clear 并清掉 firstLoginDone；重登成功须重新标就绪，否则手动涨粉/独占任务被卡住
            if (ok && ManualDeviceControl.ENABLED) {
                val u = ManualDeviceControl.activeManualUsername()
                    .ifBlank { ManualDeviceControl.selectedManualUsername() }
                    .ifBlank { PrefsStore.getLogContextUsername()?.trim()?.removePrefix("@").orEmpty() }
                if (u.isNotBlank()) markManualAccountReady(u, "掉登录恢复自动登录($source)")
            }
            ok
        } catch (banned: AccountPlanBlockedException) {
            handleAccountPlanBlocked(alias, deviceId, banned, "掉登录恢复($source)")
            false
        }
    }

    /**
     * OCR 见账号封禁 / 登录次数上限：上报云端追加凭据后缀并移出自动切号计划，本机缓存剔除并重建计划，不重登。
     * kind=banned → ----banned；kind=max_login → ----Maximum login（兼容旧整字段 banned）。
     */
    private suspend fun handleAccountCredBlocked(
        alias: String,
        deviceId: String,
        blockedUsername: String,
        kind: String,
        source: String,
    ) {
        val isMaxLogin = kind.equals("max_login", ignoreCase = true)
        val suffix = if (isMaxLogin) "----Maximum login" else "----banned"
        val label = if (isMaxLogin) "登录次数上限" else "封禁"
        val opType = if (isMaxLogin) "account_max_login" else "account_banned"
        val apiName = if (isMaxLogin) "mark_max_login" else "mark_banned"
        val username = blockedUsername.trim().removePrefix("@").ifBlank {
            ManualDeviceControl.activeManualUsername()
                .ifBlank { ManualDeviceControl.selectedManualUsername() }
                .ifBlank { PrefsStore.getLogContextUsername()?.trim()?.removePrefix("@").orEmpty() }
        }
        LogCenter.op(
            alias,
            "${label}处理：OCR 命中（来源=$source username=${username.ifBlank { "-" }}），" +
                "移出计划并将 password/email/email_password 追加 $suffix，上报云端",
        )
        updateRuntime(lastMessage = "账号$label：@$username 移出计划")
        executor.clearAccountStatusReloginState()

        var cloudOk = false
        var cloudMsg = ""
        if (deviceId.isNotBlank() && username.isNotBlank()) {
            val resp = runCatching {
                if (isMaxLogin) {
                    ApiService.markDeviceAssignedAccountMaxLogin(deviceId, alias, username)
                } else {
                    ApiService.markDeviceAssignedAccountBanned(deviceId, alias, username)
                }
            }.onFailure { err ->
                cloudMsg = err.message ?: "unknown"
                LogCenter.op(alias, "${label}处理：云端 $apiName 异常：${err.message}")
            }.getOrNull()
            if (resp != null) {
                cloudOk = resp.code == 0
                cloudMsg = resp.msg.orEmpty().ifBlank { if (cloudOk) "ok" else "failed" }
                LogCenter.op(
                    alias,
                    "${label}处理：云端 $apiName code=${resp.code} msg=${resp.msg} " +
                        "excluded=${resp.device_excluded_rows} notified=${resp.devices_notified}",
                )
            }
        } else {
            cloudMsg = "缺少 deviceId/username"
            LogCenter.op(alias, "${label}处理：缺少 deviceId 或 username，跳过云端上报")
        }

        if (username.isNotBlank()) {
            ManualDeviceControl.removeCachedAccount(username)
            val selected = ManualDeviceControl.selectedManualUsername()
            val active = ManualDeviceControl.activeManualUsername()
            if (selected.equals(username, ignoreCase = true) || active.equals(username, ignoreCase = true)) {
                val next = ManualDeviceControl.getCachedAccounts()
                    .filterNot { ManualDeviceControl.accountLooksCredBlocked(it) }
                    .firstOrNull()?.username.orEmpty()
                if (next.isNotBlank()) {
                    PrefsStore.setManualSelectedUsername(next)
                    PrefsStore.setManualActiveUsername(next)
                    PrefsStore.setLogContextUsername(next)
                } else {
                    PrefsStore.setManualSelectedUsername("")
                    PrefsStore.setManualActiveUsername("")
                    PrefsStore.setLogContextUsername("")
                }
            }
        }

        val opId = "${opType}_${System.currentTimeMillis()}"
        reportDeviceOpToCloud(
            deviceId = deviceId,
            alias = alias,
            opId = opId,
            status = if (cloudOk) "success" else "failed",
            message = "账号$label @$username：移出计划，凭据追加 $suffix（$cloudMsg）",
            data = mapOf(
                "op_type" to opType,
                "username" to username,
                "kind" to kind,
                "suffix" to suffix,
                "source" to source,
                "cloud_synced" to cloudOk,
                "cloud_msg" to cloudMsg,
            ),
        )

        // 自动切号开启时：剔除后重建/刷新计划并立刻切号，切勿走「登录失败 hold」——
        // 否则会周期性重登已阻断账号，并挡住 requestImmediateTick。
        if (ManualDeviceControl.ENABLED && PrefsStore.isManualLocalAutoSwitchEnabled() && deviceId.isNotBlank()) {
            PrefsStore.clearAutoSwitchTikTokLoginHold()
            val ok = rebuildAndStartManualLocalAutoSwitch(
                alias = alias,
                deviceId = deviceId,
                slotMinutes = PrefsStore.getManualAutoSwitchSlotMinutes(),
                reason = "账号${label}移出计划",
            )
            if (ok) {
                autoSwitchLocalScheduler.requestImmediateTick()
                updateRuntime(lastMessage = "账号 @$username 已${label}并移出计划，将切到其他账号")
            } else {
                pauseGrowthAfterLoginFailure(
                    alias,
                    deviceId,
                    "账号 @$username 已$label；本机计划重建失败，请手动切号",
                )
            }
        } else if (PrefsStore.isAutoSwitchAutonomousMode() && deviceId.isNotBlank()) {
            PrefsStore.clearAutoSwitchTikTokLoginHold()
            autoSwitchLocalScheduler.clearCutSwitchInflight()
            val refreshed = refreshAutoSwitchWeeklyBundleFromCloud()
            autoSwitchLocalScheduler.requestImmediateTick()
            if (refreshed) {
                updateRuntime(lastMessage = "账号 @$username 已${label}并移出计划，将切到其他账号")
            } else {
                LogCenter.op(
                    alias,
                    "${label}处理：周计划刷新失败，已清登录 hold；等待云端推送后继续（@$username）",
                )
                updateRuntime(lastMessage = "账号 @$username 已${label}并移出计划（等待计划刷新）")
            }
        } else {
            pauseGrowthAfterLoginFailure(
                alias,
                deviceId,
                "账号 @$username 已${label}并移出计划，请切到其他账号",
            )
        }
    }

    private suspend fun handleAccountBanned(
        alias: String,
        deviceId: String,
        bannedUsername: String,
        source: String,
    ) = handleAccountCredBlocked(alias, deviceId, bannedUsername, "banned", source)

    private suspend fun handleAccountMaxLogin(
        alias: String,
        deviceId: String,
        username: String,
        source: String,
    ) = handleAccountCredBlocked(alias, deviceId, username, "max_login", source)

    private suspend fun handleAccountPlanBlocked(
        alias: String,
        deviceId: String,
        ex: AccountPlanBlockedException,
        source: String,
    ) = handleAccountCredBlocked(alias, deviceId, ex.username, ex.blockKind, source)

    /**
     * Account status：首遇尽力点 OK 后冷启；**重启后再多次 OCR 点 OK** 才继续；
     * 仍无 OK 才强停 + pm clear 后冷启。随后走完整自动登录；
     * Add another account / 登录状态保障路径则直接从当前登录入口续接。
     */
    private suspend fun recoverFromSafeguardStyleLogout(alias: String, deviceId: String, source: String): Boolean {
        LogCenter.op(
            alias,
            "掉登录恢复（登录状态保障路径）：终止当前步骤，从登录入口续接自动登录（来源=$source）",
        )
        val opId = "safeguard_logout_${System.currentTimeMillis()}"
        return try {
            ensureAccountLoggedIn(
                alias = alias,
                deviceId = deviceId,
                opId = opId,
                reason = "掉登录恢复($source)",
                strict = true,
                forceRelogin = true,
                resumeFromLoginEntryScreen = true,
            )
        } catch (banned: AccountPlanBlockedException) {
            handleAccountPlanBlocked(alias, deviceId, banned, "掉登录恢复($source)")
            false
        }
    }

    private suspend fun ensureAccountLoggedIn(
        alias: String,
        deviceId: String,
        opId: String,
        reason: String,
        strict: Boolean,
        forceRelogin: Boolean = false,
        resumeFromLoginEntryScreen: Boolean = false,
        preferredUsername: String? = null,
        deferBasicProfileEdit: Boolean = false,
    ): Boolean {
        lastEnsureLoginPerformedFreshAutoLogin = false
        val fromBundle = PrefsStore.isAutoSwitchAutonomousMode()
        val accountResp = resolveAssignedAccount(alias, deviceId, preferredUsername)
        if (accountResp == null) {
            LogCenter.op(
                alias,
                "[OP][$opId] 自动登录($reason)：${if (fromBundle) "bundle 无可用账号" else "拉取分配账号失败"}",
            )
            return !strict
        }
        if (accountResp.code != 0) {
            LogCenter.op(alias, "[OP][$opId] 自动登录($reason)：跳过（${accountResp.msg ?: "无分配账号"}）")
            return !strict
        }
        if (accountResp.is_login == true && !forceRelogin) {
            LogCenter.op(
                alias,
                "[OP][$opId] 自动登录($reason)：已登录（username=${accountResp.username.orEmpty()}）",
            )
            if (!deferBasicProfileEdit) {
                maybeRunPendingBasicProfileEditAfterLoginVerified(
                    alias = alias,
                    deviceId = deviceId,
                    parentOpId = opId,
                    trigger = "login_cloud_is_login",
                    skipOuterWarmup = false,
                    loginVerified = true,
                )
            }
            return true
        }
        val username = accountResp.username.orEmpty().trim()
        val email = accountResp.email.orEmpty().trim()
        val password = accountResp.password.orEmpty().trim()
        val twoFa = accountResp.two_fa.orEmpty().trim()
        val api = accountResp.api.orEmpty().trim()
        if (username.isBlank() || password.isBlank()) {
            LogCenter.op(alias, "[OP][$opId] 自动登录($reason)：凭证不完整（username/password）")
            return !strict
        }
        if (twoFa.isBlank() && email.isBlank()) {
            LogCenter.op(alias, "[OP][$opId] 自动登录($reason)：凭证不完整（需 email 或 2FA 密钥）")
            return !strict
        }
        if (twoFa.isBlank() && api.isBlank()) {
            LogCenter.op(
                alias,
                "[OP][$opId] 自动登录($reason)：api 为空，将尝试通过邮箱管理模块获取验证码",
            )
        }
        username.let { PrefsStore.setLogContextUsername(it) }
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            applyBundledConfigFromAutoSwitch(username, logToUser = true)
        } else {
            fetchConfig(logToUser = true)
        }
        autoLoginActive = true
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(localStatus = "paused", lastMessage = "自动登录 TikTok（$reason）")
        runCatching {
            syncCloudStatus("paused", CloudStatusSyncKind.ROUTINE)
        }
        val loginLabel = if (fromBundle) "bundle" else "云端"
        val startDetail = when {
            forceRelogin && accountResp.is_login == true ->
                "开始（$username，$loginLabel is_login=是，TikTok 已掉登录，强制重登）"
            accountResp.is_login != true ->
                "开始（$username，$loginLabel is_login=否）"
            forceRelogin ->
                "开始（$username，$loginLabel is_login=是，强制重登）"
            else ->
                "开始（$username，$loginLabel is_login=是）"
        }
        LogCenter.op(alias, "[OP][$opId] 自动登录($reason)：$startDetail")
        val flowKind = if (accountResp.is_login != true) {
            AutoLoginFlow.CutSwitchAfterRestore
        } else {
            AutoLoginFlow.Default
        }
        val loginOk = runCatching {
            executor.runAutoLoginAfterCutSwitch(
                appContext,
                TikTokAutoLoginCredentials(
                    username = username,
                    email = email,
                    password = password,
                    apiUrl = api,
                    twoFa = twoFa,
                ),
                resumeFromLoginEntryScreen = resumeFromLoginEntryScreen,
                flowKind = flowKind,
            )
        }.recoverCatching { err ->
            if (err is AccountPlanBlockedException) throw err
            // 自动登录链内 OCR 误把 Welcome back/Add another 当掉登录上抛时：从当前登录入口续接，勿直接判失败。
            if (err is LoginSafeguardRequiresAutoLoginException) {
                LogCenter.op(
                    alias,
                    "[OP][$opId] 自动登录($reason)：链内见登录入口信号，从当前页续接（resumeFromLoginEntryScreen）",
                )
                var resumed = false
                repeat(2) { attempt ->
                    if (resumed) return@repeat
                    resumed = runCatching {
                        executor.runAutoLoginAfterCutSwitch(
                            appContext,
                            TikTokAutoLoginCredentials(
                                username = username,
                                email = email,
                                password = password,
                                apiUrl = api,
                                twoFa = twoFa,
                            ),
                            resumeFromLoginEntryScreen = true,
                            flowKind = flowKind,
                        )
                    }.getOrElse { resumeErr ->
                        if (resumeErr is AccountPlanBlockedException) throw resumeErr
                        if (resumeErr is LoginSafeguardRequiresAutoLoginException) {
                            LogCenter.op(
                                alias,
                                "[OP][$opId] 自动登录($reason)：入口续接第 ${attempt + 1} 次仍见登录入口信号",
                            )
                            false
                        } else {
                            throw resumeErr
                        }
                    }
                }
                return@recoverCatching resumed
            }
            throw err
        }.getOrElse { err ->
            if (err is AccountPlanBlockedException) throw err
            LogCenter.op(alias, "[OP][$opId] 自动登录($reason)异常：${err.message}")
            false
        }
        autoLoginActive = false
        executor.clearAccountStatusReloginState()
        if (loginOk) {
            lastEnsureLoginPerformedFreshAutoLogin = true
            PrefsStore.clearAutoSwitchTikTokLoginHold()
            AutoSwitchBundleHolder.notifyAccountLoggedIn(username)
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                AutoSwitchLedger.recordAccountLoginSuccess(username)
                LogCenter.op(alias, "[OP][$opId] 自动登录($reason)成功，is_login 已记入 ledger（对账写回）")
            } else {
                runCatching {
                    withContext(Dispatchers.IO) {
                        ApiService.markDeviceAssignedAccountLogin(deviceId, alias, username)
                    }
                }.onSuccess { resp ->
                    if (resp.code == 0) {
                        LogCenter.op(alias, "[OP][$opId] 自动登录($reason)成功，已上报 is_login=是")
                    } else {
                        LogCenter.op(alias, "[OP][$opId] 自动登录($reason)成功但上报 is_login 失败：${resp.msg}")
                    }
                }.onFailure { err ->
                    LogCenter.op(alias, "[OP][$opId] 自动登录($reason)成功但上报 is_login 异常：${err.message}")
                }
            }
            val profileTrigger = when {
                reason.contains("切号") -> "cut_switch_after_login"
                reason.contains("登录状态保障") -> "login_safeguard_auto_login"
                else -> "auto_login_success"
            }
            if (deferBasicProfileEdit) {
                LogCenter.op(
                    alias,
                    "[OP][$opId] 自动登录($reason)成功，改资料延后至切号标完成后执行（trigger=$profileTrigger）",
                )
            } else {
                maybeRunPendingBasicProfileEditAfterLoginVerified(
                    alias = alias,
                    deviceId = deviceId,
                    parentOpId = opId,
                    trigger = profileTrigger,
                    skipOuterWarmup = true,
                    loginVerified = true,
                )
            }
            return true
        }
        LogCenter.op(alias, "[OP][$opId] 自动登录($reason)失败")
        return false
    }

    /** [prepareForExclusiveTikTokUiOp] 收尾：恢复云端自动拉起，并按需恢复涨粉。 */
    private suspend fun finishExclusiveTikTokUiOp(
        alias: String,
        deviceId: String,
        opId: String,
        resumePullAfter: Boolean,
    ) {
        restoreCloudAutoStartParticipation(alias, deviceId, opId, "独占 TikTok 任务结束")
        if (
            resumePullAfter &&
            !PrefsStore.isHardStopRequested() &&
            !manuallyPaused &&
            PrefsStore.getAutoSwitchMode() != "exiting"
        ) {
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = "修改基本资料结束，恢复涨粉")
            runCatching {
                syncCloudStatus("running", CloudStatusSyncKind.ROUTINE)
            }.onFailure { err ->
                LogCenter.op(alias, "[OP][$opId] 同步 running 失败：${err.message}")
            }
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                maybeStartGrowthInAutoSwitchIdle("改资料结束")
            } else if (ManualDeviceControl.ENABLED) {
                maybeManualAutoGrowthAfterTask("修改基本资料完成")
            } else {
                startPullLoop()
            }
            LogCenter.op(alias, "[OP][$opId] 已恢复涨粉拉取")
        } else {
            LogCenter.op(alias, "[OP][$opId] 修改基本资料结束，保持暂停（resume=$resumePullAfter）")
        }
    }

    /** 分段 delay，硬停或自动切号关闭时尽快返回 false。 */
    private suspend fun delayUnlessHardStop(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (shouldHonorUserHardStop()) return false
            if (PrefsStore.getAutoSwitchMode() == "exiting") return false
            val chunk = minOf(left, 200L)
            delay(chunk)
            left -= chunk
        }
        return true
    }

    /** 用户主界面「停止后台服务」触发的硬停：不得被 check_start 竞态误解除。 */
    private fun shouldHonorUserHardStop(): Boolean = PrefsStore.isHardStopRequested()

    private fun isHardStopSkippedResult(message: String): Boolean {
        val m = message.trim()
        return m.contains("已硬停") || m.contains("硬停，跳过")
    }

    private fun isExclusivePreemptSkippedResult(message: String): Boolean {
        val m = message.trim()
        return m.contains("自治独占已抢占") ||
            m.contains("独占操作抢占") ||
            m.contains("自动切号关闭中")
    }

    private fun isGrowthTaskInterruptedResult(message: String): Boolean =
        isHardStopSkippedResult(message) || isExclusivePreemptSkippedResult(message)

    /** 涨粉拉取/执行前应中止（硬停、手动暂停、独占抢占、非 running、自治独占 OP、登录保障恢复中）。 */
    private fun shouldAbortPullExecution(): Boolean {
        if (manuallyPaused) return true
        if (shouldHonorUserHardStop()) return true
        if (PrefsStore.getAutoSwitchMode() == "exiting") return true
        if (PrefsStore.isAutoSwitchExclusivePreempt()) return true
        if (PrefsStore.isAutoSwitchAutonomousMode() && PrefsStore.isAutoSwitchTikTokLoginHoldActive()) return true
        if (basicProfileEditRunning) return true

        if (PrefsStore.getLocalStatus() != "running") return true
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            if (!autoSwitchLocalScheduler.shouldAllowGrowthBaseline()) return true
            if (isPublishPostSwitchActive()) return true
            if (AutoSwitchLocalPlanStore.isPublishContextOccupying()) return true
        }
        return false
    }

    private suspend fun reportDeviceOpToCloud(
        deviceId: String,
        alias: String,
        opId: String,
        status: String,
        message: String,
        data: Map<String, Any>? = null,
    ): BaseResponse? {
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            AutoSwitchLedger.recordFromDeviceOpReport(opId, status, message, data)
            return null
        }
        return try {
            withTimeout(15_000L) {
                ApiService.reportDeviceOp(deviceId, alias, opId, status, message, data)
            }
        } catch (err: TimeoutCancellationException) {
            LogCenter.op(alias, "[OP][$opId] 设备操作结果上报超时（15s），已跳过阻塞等待")
            null
        } catch (err: SocketTimeoutException) {
            LogCenter.op(alias, "[OP][$opId] 设备操作结果上报超时：${err.message ?: "socket timeout"}")
            null
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            LogCenter.op(alias, "[OP][$opId] 设备操作结果上报失败：${err.message ?: "unknown"}")
            null
        }
    }

    private fun publishPostStartReportData(
        deviceId: String,
        opId: String,
        payload: DeviceOpPayload?,
        slotKey: String,
    ): Map<String, Any> {
        val out = linkedMapOf<String, Any>(
            "op_type" to "publish_post_start",
            "device_id" to deviceId,
        )
        if (slotKey.isNotBlank()) out["auto_switch_slot_key"] = slotKey
        payload?.account_username?.trim()?.takeIf { it.isNotEmpty() }?.let { out["account_username"] = it }
        payload?.source?.trim()?.takeIf { it.isNotEmpty() }?.let { out["source"] = it }
        out["operation_logs"] = listOf("[OP][$opId] publish_post_start")
        return out
    }

    private suspend fun refreshAutoSwitchWeeklyBundleFromCloud(): Boolean {
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return false
        if (ManualDeviceControl.ENABLED && PrefsStore.isManualLocalAutoSwitchEnabled()) {
            val ok = rebuildAndStartManualLocalAutoSwitch(
                alias = alias,
                deviceId = deviceId,
                slotMinutes = PrefsStore.getManualAutoSwitchSlotMinutes(),
                reason = "本机周计划滚动刷新",
            )
            if (ok) {
                maybeStartGrowthInAutoSwitchIdle("本机周计划刷新")
            }
            return ok
        }
        val revision = PrefsStore.getAutoSwitchBundleRevision()
        val outcome = fetchAutoSwitchBundleFromCloud(alias, deviceId, "周计划刷新")
        if (!outcome.loaded || outcome.bundle == null) {
            LogCenter.op(alias, "【自动切号】周计划 bundle 拉取失败：${outcome.msg}")
            return false
        }
        val usernameUnchanged = applyAutoSwitchBundle(
            alias,
            deviceId,
            outcome.bundle,
            revision,
            startSchedulerIfStopped = false,
        )
        if (!usernameUnchanged) {
            autoSwitchLocalScheduler.requestImmediateTick()
            maybeStartGrowthInAutoSwitchIdle("周计划刷新")
        } else {
            LogCenter.op(
                alias,
                "【自动切号】周计划幂等刷新（当前账号未变），不打断自治涨粉",
            )
        }
        return true
    }

    /** 时段边界：收尾上一时段活动统计，并清理上一账号运行时脏数据。 */
    private fun handleAutoSwitchSlotTransition(prevSlotKey: String, newSlotKey: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        clearAutoSwitchPerAccountDirtyState(
            reason = "跨时段切换",
            prevSlotKey = prevSlotKey,
            newSlotKey = newSlotKey,
        )
        AutoSwitchAccountActivityTracker.finalizeSegment("slot_transition")
        if (alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】时段切换 $prevSlotKey → $newSlotKey，已收尾上一时段活动统计",
            )
        }
    }

    private fun applyAutoSwitchBundle(
        alias: String,
        deviceId: String,
        bundle: AutoSwitchBundle,
        revision: Int,
        startSchedulerIfStopped: Boolean = true,
    ): Boolean {
        val scheduleDate = bundle.schedule_date?.trim().orEmpty()
        if (scheduleDate.isNotBlank()) {
            PrefsStore.setAutoSwitchScheduleDate(scheduleDate)
            AutoSwitchLedger.setScheduleDate(scheduleDate)
        }
        val weekStart = bundle.week_start_date?.trim().orEmpty()
            .ifBlank { bundle.weekly_plan?.week_start_date?.trim().orEmpty() }
        val weekEnd = bundle.week_end_date?.trim().orEmpty()
            .ifBlank { bundle.weekly_plan?.week_end_date?.trim().orEmpty() }
        if (weekStart.isNotBlank() && weekEnd.isNotBlank()) {
            PrefsStore.setAutoSwitchWeekRange(weekStart, weekEnd)
        }
        val wasSchedulerRunning = autoSwitchLocalScheduler.isRunning()
        val accountsEquivalent = if (wasSchedulerRunning) {
            autoSwitchLocalScheduler.refreshWeeklyBundle(bundle)
        } else {
            false
        }
        if (!wasSchedulerRunning && startSchedulerIfStopped) {
            autoSwitchLocalScheduler.start(alias, deviceId, bundle, revision = revision)
        }
        if (revision != PrefsStore.getAutoSwitchLastConfigFetchRevision()) {
            PrefsStore.setAutoSwitchLastConfigFetchRevision(-1)
        }
        val usernameUnchanged = isBundleTargetUsernameSameAsDevice(bundle)
        val skipDisruptiveConfigApply = usernameUnchanged
        if (!skipDisruptiveConfigApply) {
            val logUser = PrefsStore.getLogContextUsername()?.trim().orEmpty()
            val slotTargetUser = bundle.resolveCurrentSlotTargetAccount(
                scheduleDate.ifBlank { PrefsStore.getAutoSwitchScheduleDate().orEmpty() },
                beijingMinuteOfDayForBundle(),
            )?.username?.trim().orEmpty().ifBlank {
                bundle.resolveHighlightedAccount(logUser)?.username?.trim().orEmpty()
            }
            applyBundledConfigFromAutoSwitch(
                logUser.ifBlank { slotTargetUser }.ifBlank { null },
                logToUser = true,
            )
            if (!accountsEquivalent && alias.isNotBlank()) {
                LogCenter.op(alias, "【自动切号】云端账号变更，已按新资料重新配置")
            }
        } else if (alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】bundle 幂等刷新：当前账号未变，跳过配置重载",
            )
        }
        if (!usernameUnchanged) {
            scheduleBackupDirectoryReconcile(
                alias = alias,
                deviceId = deviceId,
                accounts = AutoSwitchBundleHolder.getBundle()?.accounts.orEmpty()
                    .ifEmpty { bundle.accounts.orEmpty() },
                logPrefix = "【备份目录整理】",
            )
        } else if (alias.isNotBlank()) {
            val current = PrefsStore.getLogContextUsername()?.trim()?.removePrefix("@").orEmpty()
            LogCenter.op(
                alias,
                "【自动切号】资料重同步：当前账号 @$current 与云端一致，跳过备份目录整理",
            )
        }
        bundle.smart_template_debug?.toAuditLines()?.takeIf { it.isNotEmpty() }?.let { lines ->
            val label = if (!wasSchedulerRunning && startSchedulerIfStopped) "enter 打包" else "bundle 更新"
            LogCenter.op(alias, "【自动切号】智能模板 $label：\n${lines.joinToString("\n")}")
        }
        maybeSyncPcServiceUrlFromBundle(alias, bundle)
        return usernameUnchanged
    }

    /** bundle 携带云端 PC 地址时：仅在与本机不一致时保存并注册，相同则完全跳过。 */
    private fun maybeSyncPcServiceUrlFromBundle(alias: String, bundle: AutoSwitchBundle) {
        val remoteUrl = bundle.pc_service_url?.trim()?.trimEnd('/').orEmpty()
        if (remoteUrl.isBlank()) return
        if (
            !remoteUrl.startsWith("http://", ignoreCase = true) &&
            !remoteUrl.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        val currentUrl = CompanionApi.windowsBaseUrl(appContext).trim().trimEnd('/')
        if (currentUrl == remoteUrl) return
        scope.launch {
            LogCenter.op(alias, "【自动切号】PC 地址与云端不一致，同步：$currentUrl → $remoteUrl")
            val result = VideoDedSettingsRegistrar.saveAndRegister(
                appContext,
                input = VideoDedSettingsRegistrar.SaveInput(windowsUrl = remoteUrl),
                logSave = true,
                logRegistration = true,
            )
            if (result.error == null) {
                LogCenter.op(
                    alias,
                    "【自动切号】PC 地址已同步（pcAdbState=${result.pcAdbState.ifBlank { "—" }}）",
                )
            } else {
                LogCenter.op(alias, "【自动切号】PC 地址同步失败：${result.error?.message}")
            }
        }
    }

    private fun beijingMinuteOfDayForBundle(): Int {
        val cal = java.util.Calendar.getInstance(beijingTz)
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    /** 云端 bundle 当前时段应对齐的 TikTok 账号（用于资料重同步 username 对账）。 */
    private fun resolveBundleCurrentTargetUsername(bundle: AutoSwitchBundle): String {
        val scheduleDate = bundle.schedule_date?.trim().orEmpty()
            .ifBlank { PrefsStore.getAutoSwitchScheduleDate().orEmpty() }
        val logUser = PrefsStore.getLogContextUsername()?.trim().orEmpty()
        return bundle.resolveCurrentSlotTargetAccount(scheduleDate, beijingMinuteOfDayForBundle())
            ?.username?.trim()?.removePrefix("@").orEmpty()
            .ifBlank {
                bundle.resolveHighlightedAccount(logUser)?.username?.trim()?.removePrefix("@").orEmpty()
            }
    }

    private fun isBundleTargetUsernameSameAsDevice(bundle: AutoSwitchBundle): Boolean {
        val current = PrefsStore.getLogContextUsername()?.trim()?.removePrefix("@").orEmpty()
        if (current.isBlank()) return false
        val target = resolveBundleCurrentTargetUsername(bundle)
        if (target.isBlank()) return false
        return current.equals(target, ignoreCase = true)
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
        val bundleOutcome = tryLoadAutoSwitchBundleForEnter(
            alias,
            deviceId,
            sessionId,
            bundleRevision,
        )
        if (!bundleOutcome.loaded) {
            val failMsg = bundleOutcome.msg.ifBlank { "未知错误" }
            LogCenter.op(alias, "【自动切号】资料重同步 bundle 拉取失败：$failMsg")
        }
        when {
            bundleOutcome.loaded ->
                finalizeAutoSwitchEnterAfterBundleLoaded(alias, deviceId, eventId, sessionId)
            bundleOutcome.cloudDisabled ->
                scheduleAutoSwitchBundleEnterRetry(
                    alias,
                    deviceId,
                    eventId,
                    sessionId,
                    bundleRevision,
                    bundleOutcome.msg.ifBlank { "该设备未开启自动切号" },
                )
            else ->
                scheduleAutoSwitchBundleEnterRetry(
                    alias,
                    deviceId,
                    eventId,
                    sessionId,
                    bundleRevision,
                    bundleOutcome.msg,
                )
        }
    }

    private suspend fun resumeAutoSwitchSchedulerIfNeeded(reason: String = "恢复调度") {
        if (PrefsStore.getAutoSwitchMode() != "on") return
        recoverStaleAutoSwitchExclusiveState(reason)
        if (autoSwitchLocalScheduler.isRunning()) {
            autoSwitchLocalScheduler.recoverAllStaleInflightOnStart()
            autoSwitchLocalScheduler.requestImmediateTick()
            maybeStartGrowthInAutoSwitchIdle("调度器已运行")
            return
        }
        recoverAutoSwitchAutonomousWork(reason)
    }

    private fun beijingDateString(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        fmt.timeZone = beijingTz
        return fmt.format(java.util.Date())
    }

    private fun beijingTimeString(ms: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        fmt.timeZone = beijingTz
        return fmt.format(java.util.Date(ms))
    }

    /** 自治周期内每日随机时刻对账（一周 bundle 不关会话也能写回 DB）。 */
    private fun scheduleAutoSwitchDailyReconcileTargetIfNeeded(alias: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val today = beijingDateString()
        if (PrefsStore.getAutoSwitchDailyReconcileDate() == today) return
        if (
            PrefsStore.getAutoSwitchDailyReconcileTargetDate() == today &&
            PrefsStore.getAutoSwitchDailyReconcileTargetMs() > 0L
        ) {
            return
        }
        val cal = Calendar.getInstance(beijingTz)
        cal.set(Calendar.HOUR_OF_DAY, Random.nextInt(2, 22))
        cal.set(Calendar.MINUTE, Random.nextInt(0, 60))
        cal.set(Calendar.SECOND, Random.nextInt(0, 60))
        cal.set(Calendar.MILLISECOND, 0)
        val now = System.currentTimeMillis()
        val targetMs = if (cal.timeInMillis <= now) {
            now + Random.nextLong(5 * 60_000L, 30 * 60_000L)
        } else {
            cal.timeInMillis
        }
        PrefsStore.setAutoSwitchDailyReconcileSchedule(today, targetMs)
        LogCenter.op(alias, "【自动切号】已安排今日对账：${beijingTimeString(targetMs)}（ledger 不清空）")
    }

    private fun deferAutoSwitchDailyReconcile(alias: String, delayMs: Long, reason: String) {
        val today = beijingDateString()
        val targetMs = System.currentTimeMillis() + delayMs.coerceIn(5 * 60_000L, 3 * 60 * 60_000L)
        PrefsStore.setAutoSwitchDailyReconcileSchedule(today, targetMs)
        LogCenter.op(
            alias,
            "【自动切号】每日对账延后至 ${beijingTimeString(targetMs)}：$reason",
        )
    }

    private fun isAutoSwitchDailyReconcileBlocked(): Boolean =
        isPublishPostSwitchActive() ||
            isMutualSwitchActive() ||
            isLiveVisitSwitchActive() ||
            isAutoLoginActive() ||
            basicProfileEditRunning ||
            (PrefsStore.isAutoSwitchAutonomousMode() && !autoSwitchLocalScheduler.shouldAllowGrowthBaseline())

    private suspend fun maybeRunDailyAutoSwitchReconcile(alias: String, deviceId: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PrefsStore.getAutoSwitchMode() != "on") return
        if (dailyReconcileInFlight) return
        val today = beijingDateString()
        if (PrefsStore.getAutoSwitchDailyReconcileDate() == today) return
        if (PrefsStore.getAutoSwitchDailyReconcileTargetDate() != today) {
            scheduleAutoSwitchDailyReconcileTargetIfNeeded(alias)
            return
        }
        if (System.currentTimeMillis() < PrefsStore.getAutoSwitchDailyReconcileTargetMs()) return
        if (shouldHonorUserHardStop() || manuallyPaused) return
        if (isAutoSwitchDailyReconcileBlocked()) {
            deferAutoSwitchDailyReconcile(alias, 30 * 60_000L, "独占任务占用")
            return
        }
        if (AutoSwitchLedger.getEntryCount() == 0) {
            AutoSwitchLedger.restoreFromPrefs()
        }
        if (AutoSwitchLedger.getEntryCount() == 0) {
            deferAutoSwitchDailyReconcile(alias, 60 * 60_000L, "ledger 尚无记录")
            return
        }
        dailyReconcileInFlight = true
        try {
            val ok = uploadAutoSwitchSessionSummary(
                alias = alias,
                deviceId = deviceId,
                summaryKind = "daily",
                reconcileKey = today,
            )
            if (ok) {
                PrefsStore.setAutoSwitchDailyReconcileDate(today)
                LogCenter.op(alias, "【自动切号】每日对账已上报（$today，会话与 ledger 继续）")
            } else {
                deferAutoSwitchDailyReconcile(alias, 15 * 60_000L, "上报失败")
            }
        } finally {
            dailyReconcileInFlight = false
        }
    }

    private suspend fun flushDeferredFailureReports(alias: String, deviceId: String) {
        if (!FailureReportUploader.UPLOAD_ENABLED) return
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (AutoSwitchLedger.getEntryCount() == 0) {
            AutoSwitchLedger.restoreFromPrefs()
        }
        AutoSwitchLedger.forEachDeferredFailureReport { entry ->
            val data = entry.report_data
            val biz = data["failure_business"]?.toString().orEmpty()
            val err = data["failure_error_type"]?.toString().orEmpty()
            val summary = data["failure_summary"]?.toString().orEmpty()
            val ocr = data["failure_ocr_related"] == true
            if (biz.isBlank() || err.isBlank()) return@forEachDeferredFailureReport
            try {
                FailureReportUploader.tryUpload(
                    deviceId = deviceId,
                    alias = alias,
                    business = biz,
                    errorType = err,
                    summary = summary,
                    ocrRelated = ocr,
                )
            } catch (e: Exception) {
                LogCenter.op(alias, "对账前失败采集上传异常：${e.message}")
            }
        }
    }

    private suspend fun uploadAutoSwitchSessionSummary(
        alias: String,
        deviceId: String,
        summaryKind: String = "final",
        reconcileKey: String = "",
    ): Boolean {
        flushDeferredFailureReports(alias, deviceId)
        flushDeferredCutSwitchBackupUploads(alias, deviceId)
        if (AutoSwitchLedger.getEntryCount() == 0) {
            AutoSwitchLedger.restoreFromPrefs()
        }
        val kind = summaryKind.trim().lowercase().ifBlank { "final" }
        val reconcile = reconcileKey.trim().ifBlank {
            if (kind == "daily") beijingDateString() else "final"
        }
        AutoSwitchAccountActivityTracker.finalizeSegment(
            if (kind == "daily") "daily_reconcile" else "session_exit",
        )
        if (kind == "daily") {
            AutoSwitchAccountActivityTracker.reopenAfterReconcileIfNeeded()
        }
        // simple 本机会话：不依赖云端会话总结，本地收尾即可。
        if (
            ManualDeviceControl.ENABLED &&
            ManualAutoSwitchPlanBuilder.isLocalSessionId(PrefsStore.getAutoSwitchSessionId())
        ) {
            LogCenter.op(alias, AutoSwitchLedger.buildOneLineSummary(alias, kind, reconcile))
            LogCenter.op(alias, "【自动切号】本机会话总结已本地收尾（不上报云端）")
            return true
        }
        val summary = AutoSwitchLedger.toSummaryRequest(
            alias = alias,
            deviceId = deviceId,
            summaryKind = kind,
            reconcileKey = reconcile,
        )
        var uploaded = false
        repeat(3) { attempt ->
            if (uploaded) return@repeat
            runCatching {
                val resp = ApiService.postAutoSwitchSessionSummary(summary)
                if (resp.code == 0) {
                    uploaded = true
                    LogCenter.op(alias, AutoSwitchLedger.buildOneLineSummary(alias, kind, reconcile))
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
        if (!uploaded) {
            LogCenter.op(
                alias,
                AutoSwitchLedger.buildOneLineSummary(alias, kind, reconcile) + "（上报未成功，详情仅保留 ledger）",
            )
        }
        return uploaded
    }

    private suspend fun tryUploadPendingAutoSwitchSummary() {
        if (!PrefsStore.hasAutoSwitchPendingSummaryUpload() && !AutoSwitchLedger.hasPendingUpload()) return
        if (PrefsStore.getAutoSwitchMode() == "on") return
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return
        flushDeferredCutSwitchBackupUploads(alias, deviceId)
        if (uploadAutoSwitchSessionSummary(alias, deviceId)) {
            AutoSwitchLedger.clear()
            AutoSwitchAccountActivityTracker.reset()
            PrefsStore.clearAutoSwitchSessionContext()
            flushCloudStatusAfterAutonomousExit()
            LogCenter.op(alias, "【自动切号】待上报会话总结已成功补传")
        }
    }

    private suspend fun handleDeviceOp(alias: String, deviceId: String, op: DeviceOp) {
        lastDeviceOpOk = false
        val opId = op.op_id
        val payloadEarly = op.payload
        if (ManualDeviceControl.shouldIgnoreCloudTaskOp(op.op_type, opId)) {
            LogCenter.op(alias, "[OP][$opId] 手动模式：忽略云端任务 ${op.op_type}")
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "success",
                message = "设备手动模式：已忽略云端任务 ${op.op_type}",
                data = mapOf(
                    "op_type" to op.op_type,
                    "device_id" to deviceId,
                    "manual_mode_ignored" to true,
                ),
            )
            lastDeviceOpOk = true
            return
        }
        if (
            PrefsStore.isAutoSwitchAutonomousMode() &&
            opId.startsWith("local-as-") &&
            AutoSwitchLedger.isOpTerminal(opId)
        ) {
            if (!isLocalAutoSwitchOpId(opId)) {
                LogCenter.op(alias, "[OP][$opId] ledger 已达终态，跳过重复执行")
            }
            return
        }
        if (
            PrefsStore.isAutoSwitchAutonomousMode() &&
            opId.startsWith("local-as-") &&
            op.op_type in autoSwitchLedgerOpTypes
        ) {
            AutoSwitchLedger.record(
                opId = opId,
                opType = op.op_type,
                status = "running",
                message = "开始执行",
                slotKey = payloadEarly?.auto_switch_slot_key.orEmpty(),
                username = payloadEarly?.account_username?.trim()
                    ?: payloadEarly?.username?.trim().orEmpty(),
                reportData = AutoSwitchLedger.buildReportDataPreview(op.op_type, payloadEarly),
                phaseOverride = "running",
            )
        }
        if (
            isBatchStreamActive() &&
            op.op_type !in setOf("batch_stream_stop", "batch_stream_start", "set_pc_service_url")
        ) {
            LogCenter.op(alias, "[OP][$opId] 批量带流进行中，已屏蔽 ${op.op_type}")
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "failed",
                message = "设备正在批量带流，暂不接受其它指令",
                data = mapOf(
                    "op_type" to op.op_type,
                    "device_id" to deviceId,
                    "blocked_by_batch_stream" to true,
                ),
            )
            return
        }
        val payload = op.payload
        if (op.op_type == "batch_stream_start") {
            val sessionId = payload?.session_id?.trim().orEmpty()
            val hours = payload?.hours_per_account ?: 0.0
            val activityRounds = (payload?.activity_rounds ?: 1).coerceIn(1, 20)
            val rows = payload?.rows.orEmpty()
            val pkg = payload?.package_name?.trim().orEmpty()
                .ifBlank { com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME }
            if (sessionId.isBlank() || hours <= 0.0 || rows.isEmpty()) {
                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = "failed",
                    message = "批量带流参数无效（session/时长/行数据）",
                    data = mapOf("op_type" to op.op_type, "device_id" to deviceId),
                )
                return
            }
            if (isBatchStreamActive()) {
                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = "failed",
                    message = "设备已在批量带流中",
                    data = mapOf("op_type" to op.op_type, "device_id" to deviceId),
                )
                return
            }
            batchStreamStopRequested = false
            pullLoopJob?.cancel()
            pullLoopJob = null
            runCatching {
                withContext(Dispatchers.IO) {
                    reportAutoStartOptOutIfNeeded(deviceId = deviceId, alias = alias, optOut = true)
                }
            }
            val startLine = if (activityRounds > 1) {
                "[OP][$opId] 批量带流已启动：${rows.size} 个账号，每轮 ${hours} 小时，共 ${activityRounds} 轮"
            } else {
                "[OP][$opId] 批量带流已启动：${rows.size} 个账号，每台 ${hours} 小时"
            }
            LogCenter.op(alias, startLine)
            batchStreamJob = scope.launch {
                runBatchStreamSession(
                    alias = alias,
                    deviceId = deviceId,
                    parentOpId = opId,
                    sessionId = sessionId,
                    packageName = pkg,
                    hoursPerAccount = hours,
                    activityRounds = activityRounds,
                    rows = rows,
                )
            }
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "success",
                message = "批量带流任务已启动",
                data = mapOf(
                    "op_type" to op.op_type,
                    "device_id" to deviceId,
                    "session_id" to sessionId,
                    "row_count" to rows.size,
                    "hours_per_account" to hours,
                    "activity_rounds" to activityRounds,
                    "operation_logs" to listOf(startLine),
                ),
            )
            return
        }
        if (op.op_type == "batch_stream_stop") {
            batchStreamStopRequested = true
            val line = "[OP][$opId] 收到停止批量带流指令"
            LogCenter.op(alias, line)
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "success",
                message = "已请求停止批量带流",
                data = mapOf(
                    "op_type" to op.op_type,
                    "device_id" to deviceId,
                    "operation_logs" to listOf(line),
                ),
            )
            return
        }
        if (op.op_type == "set_pc_service_url") {
            handleSetPcServiceUrlOp(alias, deviceId, op)
            return
        }
        val packageNameEarly = payload?.package_name?.trim().orEmpty()
        val isDestructive = op.op_type in destructiveTikTokOps
        val isExclusiveUi = op.op_type in exclusiveTikTokUiOps
        var resumePullAfterExclusive = false
        if (isDestructive) {
            prepareForDestructiveTikTokOp(alias, deviceId, op.op_type, opId, packageNameEarly)
        } else if (isExclusiveUi) {
            resumePullAfterExclusive = prepareForExclusiveTikTokUiOp(
                alias, deviceId, op.op_type, opId, packageNameEarly,
            )
        } else {
            resumeExecutionForCloudDeviceOp(alias, deviceId, "云端设备指令：${op.op_type}")
        }
        if (op.op_type == "gallery_import_media" || op.op_type == "gallery_import_images") {
            handleGalleryImportOp(alias, deviceId, op)
            return
        }
        val packageName = payload?.package_name?.trim().orEmpty()
        if (op.op_type == "live_visit_start") {
            val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
            val accountUser = payload?.account_username?.trim().orEmpty()
            val names = payload?.live_visit_usernames.orEmpty()
                .map { it.trim().removePrefix("@") }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
            if (accountUser.isNotBlank()) {
                applyBundledConfigFromAutoSwitch(accountUser, logToUser = false)
            }
            val liveLogLine =
                "[OP][$opId] 收到主播访问指令，本地消化 ${names.size} 条（不拉云端名单）"
            LogCenter.op(alias, liveLogLine)
            if (slotKey.isNotBlank()) {
                AutoSwitchLocalPlanStore.beginActiveLiveVisit(
                    slotKey = slotKey,
                    opId = opId,
                    accountUsername = accountUser,
                    remainingUsernames = names,
                )
            } else {
                AutoSwitchLocalPlanStore.clearActiveLiveVisit()
            }
            triggerLiveVisitNow(alias)
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "success",
                message = "已收到主播访问请求",
                data = mapOf(
                    "op_type" to op.op_type,
                    "device_id" to deviceId,
                    "live_visit_count" to names.size,
                    "operation_logs" to listOf(liveLogLine),
                ),
            )
            return
        }
        if (
            op.op_type == "mutual_click_start" ||
            op.op_type == "mutual_click_comment_only_start" ||
            op.op_type == "mutual_click_follow_only_start"
        ) {
            val isAutoSwitchMutual = payload?.source?.trim() == "auto_switch"
            val mutualAccount = payload?.account_username?.trim().orEmpty()
            if (isAutoSwitchMutual && mutualAccount.isNotBlank()) {
                applyBundledConfigFromAutoSwitch(mutualAccount, logToUser = false)
            }
            val followOnly = if (isAutoSwitchMutual) {
                _config.value.mutual_traffic_follow_only
            } else {
                op.op_type == "mutual_click_follow_only_start"
            }
            val commentOnly = !isAutoSwitchMutual && op.op_type == "mutual_click_comment_only_start"
            PrefsStore.setMutualCommentOnlySession(commentOnly)
            PrefsStore.setMutualFollowOnlySession(followOnly)
            val sessionLabel = when {
                followOnly -> "引流互点·仅关注"
                commentOnly -> "仅评论"
                isAutoSwitchMutual -> "引流互点"
                else -> "完整"
            }
            val mutualLogLine =
                "[OP][$opId] 收到${sessionLabel}指令，开始 teamwork Videos 引流会话"
            LogCenter.op(alias, mutualLogLine)
            if (isAutoSwitchMutual) {
                val mutualSlotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
                AutoSwitchLocalPlanStore.beginActiveMutual(
                    slotKey = mutualSlotKey,
                    opId = opId,
                    accountUsername = payload?.account_username?.trim().orEmpty(),
                    opType = op.op_type,
                )
                if (mutualSlotKey.isNotBlank()) {
                    PrefsStore.setAutoSwitchMutualSlotKey(mutualSlotKey)
                }
            } else {
                AutoSwitchLocalPlanStore.clearActiveMutual()
            }
            PrefsStore.setMutualClickRequested(true)
            PrefsStore.setMutualClickPending(true)
            triggerMutualSwitchNow(alias)
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "success",
                message = "已收到互点引流请求",
                data = mapOf(
                    "op_type" to op.op_type,
                    "device_id" to deviceId,
                    "operation_logs" to listOf(mutualLogLine),
                ),
            )
            return
        }
        if (op.op_type == "publish_post_start") {
            clearMutualClickStateForInterrupt("发作品开始")
            val pubPkg = payload?.package_name?.trim().orEmpty()
            val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
            val isAutoSwitchPublish = payload?.source == "auto_switch"
            if (isAutoSwitchPublish && slotKey.isNotBlank()) {
                if (AutoSwitchLocalPlanStore.isPublishDone(slotKey)) {
                    reportDeviceOpToCloud(
                        deviceId = deviceId,
                        alias = alias,
                        opId = opId,
                        status = "success",
                        message = "时段发作品已完成，跳过重复下发",
                        data = publishPostStartReportData(deviceId, opId, payload, slotKey),
                    )
                    return
                }
                if (AutoSwitchLocalPlanStore.isPublishTerminalFailure(slotKey)) {
                    reportDeviceOpToCloud(
                        deviceId = deviceId,
                        alias = alias,
                        opId = opId,
                        status = "failed",
                        message = "时段发作品已中止且不可重试",
                        data = publishPostStartReportData(deviceId, opId, payload, slotKey),
                    )
                    return
                }
                if (AutoSwitchLocalPlanStore.isPublishInflight(slotKey)) {
                    val activeOpId = PrefsStore.getPublishPostTaskOpId().orEmpty()
                    if (activeOpId.isNotBlank()) {
                        reportDeviceOpToCloud(
                            deviceId = deviceId,
                            alias = alias,
                            opId = opId,
                            status = "success",
                            message = if (activeOpId == opId) {
                                "时段发作品执行中（幂等跳过）"
                            } else {
                                "时段发作品执行中，跳过重复入队"
                            },
                            data = publishPostStartReportData(deviceId, opId, payload, slotKey),
                        )
                        return
                    }
                    if (tryResumeStaleAutoSwitchPublish(slotKey)) {
                        reportDeviceOpToCloud(
                            deviceId = deviceId,
                            alias = alias,
                            opId = opId,
                            status = "success",
                            message = "发作品 stale 状态已续发",
                            data = publishPostStartReportData(deviceId, opId, payload, slotKey),
                        )
                        return
                    }
                    resetStalePublishInflightForRetry(slotKey, alias)
                    LogCenter.op(
                        alias,
                        "【自动切号】plan RUNNING 无 active_publish，已重置为 PENDING，继续处理新发作品 OP",
                    )
                }
                val activePublish = AutoSwitchLocalPlanStore.getActivePublish()
                if (
                    activePublish != null &&
                    activePublish.slot_key == slotKey &&
                    activePublish.op_id == opId
                ) {
                    reportDeviceOpToCloud(
                        deviceId = deviceId,
                        alias = alias,
                        opId = opId,
                        status = "success",
                        message = "时段发作品上下文一致，幂等跳过",
                        data = publishPostStartReportData(deviceId, opId, payload, slotKey),
                    )
                    return
                }
            }
            if (pubPkg.isBlank()) {
                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = "failed",
                    message = "设备操作缺少 package_name，已拒绝执行发作品任务",
                    data = mapOf(
                        "op_type" to op.op_type,
                        "device_id" to deviceId,
                        "operation_logs" to listOf("[OP][$opId] publish_post_start 缺少 package_name"),
                    ),
                )
                return
            }
            val prevTaskOpId = PrefsStore.getPublishPostTaskOpId()
            val publishAccountKey =
                PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() } ?: alias
            if (!prevTaskOpId.isNullOrBlank() && prevTaskOpId != opId && payload?.source != "auto_switch") {
                PrefsStore.clearPublishPostResumeProgress(publishAccountKey)
                PrefsStore.setPublishPostMaterialReady(false)
                LogCenter.op(
                    alias,
                    "[OP][$opId] 新发作品指令，已清除上一指令（$prevTaskOpId）的断点续发与素材就绪标记",
                )
            }
            if (payload?.source == "auto_switch") {
                AutoSwitchLocalPlanStore.beginActivePublish(
                    slotKey = slotKey,
                    opId = opId,
                    accountUsername = payload.account_username.orEmpty(),
                    packageName = pubPkg,
                    targetCount = payload?.publish_count,
                )
            } else {
                AutoSwitchLocalPlanStore.clearActivePublish()
                PrefsStore.setPublishPostTaskOpId(opId)
                PrefsStore.setPublishPostPackageName(pubPkg)
                PrefsStore.setPublishPostTargetCount(payload?.publish_count)
                // 新手动发作品任务必须清库重拉；禁止沿用上轮 materialReady 残留。
                PrefsStore.setPublishPostMaterialReady(false)
                PrefsStore.setPublishPostRequested(true)
                PrefsStore.setPublishPostPending(true)
            }
            val publishCount = payload?.publish_count
            val countHint =
                if (publishCount != null && publishCount > 0) "，目标发布 $publishCount 条" else ""
            val autoSwitchHint =
                if (payload?.source == "auto_switch") "（自动切号调度）" else ""
            val lineStart =
                "[OP][$opId] 发作品指令$autoSwitchHint 已收到，准备切入发作品入口流程$countHint"
            PrefsStore.setPublishPostQueuedAfterMutual(false)
            LogCenter.op(alias, lineStart)
            // 不在此处上报 success：网页端需轮询最终发作品成败；启动即 success 会挡住终态回执。
            triggerPublishPostNow(alias)
            return
        }
        if (packageName.isBlank()) {
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "failed",
                message = "设备操作缺少 package_name，已拒绝执行",
                data = mapOf(
                    "op_type" to op.op_type,
                    "device_id" to deviceId,
                    "operation_logs" to listOf("[OP][$opId] 缺少 package_name，已拒绝执行"),
                ),
            )
            if (isExclusiveUi) {
                finishExclusiveTikTokUiOp(alias, deviceId, opId, resumePullAfterExclusive)
            }
            return
        }
        val operationLogs = mutableListOf<String>()
        val suppressLocalAutoSwitchUiLog = isLocalAutoSwitchOpId(opId)
        var lastCutSwitchFailureReason = ""
        ExecutionScope.enter()
        try {

        fun recordCutSwitchFailure(reason: String) {
            lastCutSwitchFailureReason = reason.trim()
            if (lastCutSwitchFailureReason.isEmpty()) return
            val line = "[OP][$opId] 失败原因：$lastCutSwitchFailureReason"
            operationLogs.add(line)
        }

        fun cutSwitchFailMessage(base: String): String =
            if (lastCutSwitchFailureReason.isNotBlank()) "$base：$lastCutSwitchFailureReason" else base

        fun opLog(text: String) {
            val line = "[OP][$opId] $text"
            operationLogs.add(line)
            if (!suppressLocalAutoSwitchUiLog) {
                LogCenter.op(alias, line)
            }
        }

        fun opStep(text: String) {
            if (!isDestructive) return
            val line = "[OP][$opId] $text"
            operationLogs.add(line)
            if (!suppressLocalAutoSwitchUiLog) {
                LogCenter.op(alias, line)
            }
        }

        fun logLocalAutoSwitchFailureDetail() {
            if (!suppressLocalAutoSwitchUiLog) return
            val tail = operationLogs.takeLast(6)
            if (tail.isEmpty()) return
            LogCenter.op(
                alias,
                "【切号】失败详情 type=${op.op_type}（末 ${tail.size}/${operationLogs.size} 步）",
            )
            tail.forEach { raw ->
                val msg = raw.removePrefix("[OP][$opId] ").ifBlank { raw }
                LogCenter.op(alias, "【切号】  ${cutSwitchUserLogMessage(msg)}")
            }
        }

        fun opDone(ok: Boolean, successText: String, failText: String) {
            val text = if (ok) successText else cutSwitchFailMessage(failText)
            val line = "[OP][$opId] $text"
            operationLogs.add(line)
            when {
                suppressLocalAutoSwitchUiLog && !ok -> {
                    LogCenter.op(alias, "【切号】${cutSwitchUserLogMessage(text)}")
                    logLocalAutoSwitchFailureDetail()
                }
                !suppressLocalAutoSwitchUiLog -> LogCenter.op(alias, line)
            }
        }

        val runSu: (String) -> Boolean = ::runExecutorSu
        val runSuWithOutput: (String) -> String? = { command ->
            runCatching {
                executor.run {
                    val m = javaClass.getDeclaredMethod("runSuCommandWithOutput", String::class.java)
                    m.isAccessible = true
                    (m.invoke(this, command) as? String)?.trim()
                }
            }.getOrNull()
        }

        if (op.op_type !in setOf("tiktok_clear", "tiktok_backup_and_clear", "tiktok_restore")) {
            opLog("收到设备操作: type=${op.op_type}")
        }

        when (op.op_type) {
            "tiktok_force_stop_1", "tiktok_force_stop" -> {
                opStep("强停 TikTok（方案1）")
                val stopOk = runSu("am force-stop $packageName")
                opDone(stopOk, "强停完成", "强停失败")

                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (stopOk) "success" else "failed",
                    message = if (stopOk) "TikTok 强停完成（方案1）" else "TikTok 强停失败（方案1）",
                    data = mapOf(
                        "op_type" to "tiktok_force_stop_1",
                        "scheme" to "force_stop_1",
                        "command" to "am force-stop $packageName",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_force_stop_2" -> {
                opStep("强停 TikTok（方案2）")
                val stopOk = runSu("pkill -f $packageName || killall $packageName")
                opDone(stopOk, "强停完成", "强停失败")

                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (stopOk) "success" else "failed",
                    message = if (stopOk) "TikTok 强停完成（方案2）" else "TikTok 强停失败（方案2）",
                    data = mapOf(
                        "op_type" to "tiktok_force_stop_2",
                        "scheme" to "force_stop_2",
                        "command" to "pkill -f $packageName || killall $packageName",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_force_stop_3" -> {
                opStep("强停 TikTok（方案3）")
                val stopOk = runSu("for p in \$(pidof $packageName); do kill -9 \$p; done")
                opDone(stopOk, "强停完成", "强停失败")

                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (stopOk) "success" else "failed",
                    message = if (stopOk) "TikTok 强停完成（方案3）" else "TikTok 强停失败（方案3）",
                    data = mapOf(
                        "op_type" to "tiktok_force_stop_3",
                        "scheme" to "force_stop_3",
                        "command" to "for p in \$(pidof $packageName); do kill -9 \$p; done",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_launch_1", "tiktok_launch" -> {
                opStep("拉起 TikTok（方案1）")
                fetchConfig()
                val launchOk = executor.launchTikTokWithForegroundVerification(
                    ctx = appContext,
                    alias = alias,
                    tag = "远控_launch_1",
                    forceStopBeforeLaunch = false,
                    timingConfig = _config.value,
                )
                opDone(launchOk, "拉起成功", "拉起失败")

                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (launchOk) "success" else "failed",
                    message = if (launchOk) "TikTok 已在前台（dumpsys 校验）" else "TikTok 拉起失败或前台校验未通过",
                    data = mapOf(
                        "op_type" to "tiktok_launch_1",
                        "scheme" to "launch_1",
                        "command" to "TraceExecutor.launchTikTokWithForegroundVerification（intent→root→dumpsys）",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_launch_2" -> {
                opStep("拉起 TikTok（方案2）")
                fetchConfig()
                val launchOk = executor.launchTikTokWithForegroundVerification(
                    ctx = appContext,
                    alias = alias,
                    tag = "远控_launch_2",
                    forceStopBeforeLaunch = false,
                    timingConfig = _config.value,
                )
                opDone(launchOk, "拉起成功", "拉起失败")

                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (launchOk) "success" else "failed",
                    message = if (launchOk) "TikTok 已在前台（dumpsys 校验）" else "TikTok 拉起失败或前台校验未通过",
                    data = mapOf(
                        "op_type" to "tiktok_launch_2",
                        "scheme" to "launch_2",
                        "command" to "TraceExecutor.launchTikTokWithForegroundVerification（intent→root→dumpsys）",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_launch_3" -> {
                opStep("拉起 TikTok（方案3）")
                fetchConfig()
                val launchOk = executor.launchTikTokWithForegroundVerification(
                    ctx = appContext,
                    alias = alias,
                    tag = "远控_launch_3",
                    forceStopBeforeLaunch = false,
                    timingConfig = _config.value,
                )
                opDone(launchOk, "拉起成功", "拉起失败")

                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (launchOk) "success" else "failed",
                    message = if (launchOk) "TikTok 已在前台（dumpsys 校验）" else "TikTok 拉起失败或前台校验未通过",
                    data = mapOf(
                        "op_type" to "tiktok_launch_3",
                        "scheme" to "launch_3",
                        "command" to "TraceExecutor.launchTikTokWithForegroundVerification（intent→root→dumpsys）",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_edit_basic_profile" -> {
                if (ManualDeviceControl.ENABLED && !ManualDeviceControl.isLocalManualOpId(opId)) {
                    opLog("手动模式：改资料仅允许本机「修改基本资料」按钮触发，已忽略")
                    reportDeviceOpToCloud(
                        deviceId = deviceId,
                        alias = alias,
                        opId = opId,
                        status = "success",
                        message = "设备手动模式：改资料仅允许本机按钮触发",
                        data = mapOf(
                            "op_type" to "tiktok_edit_basic_profile",
                            "device_id" to deviceId,
                            "manual_mode_button_only" to true,
                        ),
                    )
                    lastDeviceOpOk = true
                    return@handleDeviceOp
                }
                opStep("修改基本资料：拉取配置")
                val cfgOk = fetchConfig()
                if (!cfgOk) opLog("配置拉取失败，沿用上一版")

                val profileAccount = resolveAssignedAccount(alias, deviceId)
                val profileUsernameAtStart = profileAccount?.username?.trim().orEmpty()
                    .ifBlank { payload?.username?.trim().orEmpty() }
                    .ifBlank { payload?.account_username?.trim().orEmpty() }
                    .ifBlank { PrefsStore.getLogContextUsername()?.trim().orEmpty() }
                val profileAccountRowIndex = profileAccount?.row_index?.takeIf { it >= 0 }
                val basicProfileModifiedBefore = profileAccount?.basic_profile_modified?.trim().orEmpty().ifBlank { "否" }
                recordProfileEditRequiredIfNeeded(profileUsernameAtStart, basicProfileModifiedBefore)
                if (profileUsernameAtStart.isNotBlank() && PrefsStore.isAutoSwitchAutonomousMode()) {
                    AutoSwitchLedger.recordProfileEditStarted(
                        opId,
                        profileUsernameAtStart,
                        "remote_device_op",
                        basicProfileModifiedBefore,
                    )
                }

                var profileEditAvatarFilename = ""
                var profileEditGalleryClearedBefore = false
                var profileEditAvatarImported = false
                try {
                    opStep("修改基本资料：清理设备图库（执行前）")
                    executor.clearDevicePicturesGallery(alias)
                    profileEditGalleryClearedBefore = true
                    delayUnlessHardStop(800)

                    opStep("修改基本资料：从云端图库随机拉取头像")
                    val avatarItem = resolveProfileEditAvatarMediaItem(deviceId, alias, payload, ::opLog)
                    if (avatarItem == null) {
                        opLog("不满足修改资料条件：管理台图库为空或拉取失败（未写入设备图库）")
                        reportDeviceOpToCloud(
                            deviceId = deviceId,
                            alias = alias,
                            opId = opId,
                            status = "failed",
                            message = "管理台图库为空，请先在「图库素材下发」页上传图片",
                            data = mutableMapOf(
                                "op_type" to "tiktok_edit_basic_profile",
                                "device_id" to deviceId,
                                "package_name" to packageName,
                                "profile_edit_skipped" to true,
                                "profile_edit_skip_reason" to "cloud_gallery_empty",
                                "profile_edit_success" to false,
                                "profile_edit_gallery_cleared_before" to profileEditGalleryClearedBefore,
                                "operation_logs" to operationLogs,
                            ).enrichProfileEditReportFields(
                                profileUsernameAtStart,
                                profileAccountRowIndex,
                                basicProfileModifiedBefore,
                            ),
                        )
                        return@handleDeviceOp
                    }
                    profileEditAvatarFilename = avatarItem.filename?.trim().orEmpty()
                    profileEditAvatarImported = importGalleryMediaItemToDevice(
                        avatarItem,
                        picturesRelativePath = "${Environment.DIRECTORY_PICTURES}/",
                        log = ::opLog,
                    )
                    if (!profileEditAvatarImported) {
                        reportDeviceOpToCloud(
                            deviceId = deviceId,
                            alias = alias,
                            opId = opId,
                            status = "failed",
                            message = "随机头像写入设备图库失败",
                            data = mutableMapOf(
                                "op_type" to "tiktok_edit_basic_profile",
                                "device_id" to deviceId,
                                "package_name" to packageName,
                                "profile_edit_avatar_filename" to profileEditAvatarFilename,
                                "profile_edit_gallery_cleared_before" to profileEditGalleryClearedBefore,
                                "profile_edit_avatar_imported" to false,
                                "profile_edit_success" to false,
                                "operation_logs" to operationLogs,
                            ).enrichProfileEditReportFields(
                                profileUsernameAtStart,
                                profileAccountRowIndex,
                                basicProfileModifiedBefore,
                            ),
                        )
                        return@handleDeviceOp
                    }
                    delayUnlessHardStop(1200)

                    val galleryImageCount = executor.countPicturesGalleryImageFiles()
                    if (galleryImageCount < 1) {
                        opLog(
                            if (galleryImageCount == 0) {
                                "不满足修改资料条件：头像写入后图库仍无图片"
                            } else {
                                "不满足修改资料条件：图库检测失败"
                            },
                        )
                        reportDeviceOpToCloud(
                            deviceId = deviceId,
                            alias = alias,
                            opId = opId,
                            status = "failed",
                            message = "头像写入后设备图库仍无可用图片",
                            data = mutableMapOf(
                                "op_type" to "tiktok_edit_basic_profile",
                                "device_id" to deviceId,
                                "package_name" to packageName,
                                "gallery_image_count" to galleryImageCount.coerceAtLeast(0),
                                "gallery_image_count_raw" to galleryImageCount,
                                "profile_edit_avatar_filename" to profileEditAvatarFilename,
                                "profile_edit_gallery_cleared_before" to profileEditGalleryClearedBefore,
                                "profile_edit_avatar_imported" to profileEditAvatarImported,
                                "profile_edit_skipped" to true,
                                "profile_edit_skip_reason" to "gallery_images_empty_after_import",
                                "profile_edit_success" to false,
                                "operation_logs" to operationLogs,
                            ).enrichProfileEditReportFields(
                                profileUsernameAtStart,
                                profileAccountRowIndex,
                                basicProfileModifiedBefore,
                            ),
                        )
                        return@handleDeviceOp
                    }
                    opLog("修改基本资料：设备图库已有头像素材（count=$galleryImageCount，file=$profileEditAvatarFilename）")

                val ratioTapX = 0.97f
                val ratioTapY = 0.95f
                val maxOuter = 3
                var lastW = 0
                var lastH = 0
                var lastRatioTapPx = Pair(0, 0)
                var lastRatioTapOk = false
                var lastStopOk = false
                var lastLaunchOk = false
                var lastChain: ProfileEditChainResult? = null
                var avatarSucceededEver = false
                var successOverall = false
                var stopAfterProfileEditOk = false
                var lastEntryMode = ""
                var lastEntryFailureStep = ""

                for (outer in 1..maxOuter) {
                    if (PrefsStore.isHardStopRequested()) {
                        opLog("修改基本资料：检测到硬停，中止外轮（不再重试 OCR）")
                        lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                        runSu("am force-stop $packageName")
                        break
                    }
                    opLog("修改基本资料：第 $outer/$maxOuter 轮")
                    lastStopOk = false
                    lastLaunchOk = false
                    lastRatioTapOk = false
                    val directEntryThisRound =
                        if (outer == 1) {
                            opLog("profile edit: outer $outer/$maxOuter entry precheck before warmup")
                            /*
                            opLog("淇敼鍩烘湰璧勬枡锛氱 $outer/$maxOuter 杞厛鍋?OCR 鍒ゅ畾鏄惁鍦ㄩ椤碉紝鑻ュ湪棣栭〉鍒欑洿鎺ョ偣 Profile")
                            */
                            val prep = executor.prepareProfileEditEntryPreferHome(
                                ctx = appContext,
                                alias = alias,
                                reason = "profile_edit_outer_$outer",
                            )
                            lastEntryMode = prep.entryMode
                            lastEntryFailureStep = prep.failureStep
                            if (prep.success) {
                                val dm = appContext.resources.displayMetrics
                                lastW = dm.widthPixels
                                lastH = dm.heightPixels
                                prep.profileTapPx?.let { lastRatioTapPx = it }
                                lastLaunchOk = true
                                lastRatioTapOk = prep.profileTapPx != null
                                opLog("profile edit: outer $outer/$maxOuter entry precheck succeeded (${prep.entryMode}); continue with OCR chain")
                                /*
                                opLog("淇敼鍩烘湰璧勬枡锛氱 $outer/$maxOuter 杞洿杈惧叆鍙ｆ垚鍔燂紙${prep.entryMode}锛夛紝鐩存帴杩涘叆 OCR 閾?)
                                */
                                true
                            } else {
                                opLog("profile edit: outer $outer/$maxOuter entry precheck failed; fallback to warmup")
                                false
                            }
                        } else {
                            false
                        }
                    if (!directEntryThisRound) {
                        lastStopOk = runSu("am force-stop $packageName")
                    if (!delayUnlessHardStop(1200)) {
                        opLog("修改基本资料：硬停中止")
                        lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                        runSu("am force-stop $packageName")
                        break
                    }

                    lastLaunchOk = executor.launchTikTokWithForegroundVerification(
                        ctx = appContext,
                        alias = alias,
                        tag = "修改基本资料_外轮${outer}",
                        forceStopBeforeLaunch = false,
                        timingConfig = _config.value,
                    )

                    val dm = appContext.resources.displayMetrics
                    lastW = dm.widthPixels
                    lastH = dm.heightPixels
                    val rtx = (lastW * ratioTapX).toInt().coerceIn(1, (lastW - 1).coerceAtLeast(1))
                    val rty = (lastH * ratioTapY).toInt().coerceIn(1, (lastH - 1).coerceAtLeast(1))
                    lastRatioTapPx = rtx to rty
                    lastRatioTapOk = false
                    if (!delayUnlessHardStop(if (lastLaunchOk) 1600 else 1200)) {
                        opLog("修改基本资料：硬停中止")
                        lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                        runSu("am force-stop $packageName")
                        break
                    }
                    if (PrefsStore.isHardStopRequested()) {
                        opLog("修改基本资料：硬停中止")
                        lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                        runSu("am force-stop $packageName")
                        break
                    }
                    if (lastLaunchOk) {
                        opLog("修改基本资料：For You 等待 1.6s 后刷两下视频")
                        val forYouOk = executor.profileEditForYouTwoSwipesAfterLaunch(alias, lastW, lastH)
                        if (!forYouOk) {
                            opLog("修改基本资料：硬停中止")
                            lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                            runSu("am force-stop $packageName")
                            break
                        }
                    }
                    lastRatioTapOk = executor.navBackThenTapProfile(alias, "修改基本资料外轮资料入口", rtx, rty)

                    if (!lastStopOk || !lastLaunchOk || !lastRatioTapOk) {
                        opLog("外轮$outer：准备阶段未就绪，重试")
                        lastChain = ProfileEditChainResult(
                            success = false,
                            failureStep = "warmup_outer_$outer",
                            editOcrPreviews = listOf("stop=$lastStopOk launch=$lastLaunchOk ratioTap=$lastRatioTapOk"),
                        )
                        continue
                    }

                    }

                    if (profileUsernameAtStart.isNotBlank()) {
                        ProfileEditStepStateStore.resetAbandonedForOuterRetry(profileUsernameAtStart)
                    }
                    val chain = try {
                        executor.profileEditRunOcrChain(appContext, _config.value)
                    } catch (e: AccountPlanBlockedException) {
                        val kindStep =
                            if (e.blockKind.equals("max_login", true)) "account_max_login" else "account_banned"
                        val kindLabel =
                            if (e.blockKind.equals("max_login", true)) "登录次数上限" else "账号封禁"
                        opLog("修改基本资料：$kindLabel ${e.message}")
                        handleAccountPlanBlocked(alias, deviceId, e, "修改基本资料")
                        ProfileEditChainResult(
                            success = false,
                            failureStep = kindStep,
                            editOcrPreviews = listOf(e.message ?: kindStep),
                        )
                    } catch (e: AccountStatusLoggedOutException) {
                        opLog("修改基本资料：掉登录 ${e.message}")
                        recoverFromAccountStatusLogout(alias, deviceId, "修改基本资料")
                        ProfileEditChainResult(
                            success = false,
                            failureStep = "account_status_logout",
                            editOcrPreviews = listOf(e.message ?: "account_status_logout"),
                        )
                    } catch (e: Exception) {
                        opLog("外轮$outer：OCR链异常 ${e.message}")
                        ProfileEditChainResult(
                            success = false,
                            failureStep = "ocr_chain_exception",
                            editOcrPreviews = listOf(e.message ?: "unknown"),
                        )
                    }
                    lastChain = chain
                    if (chain.avatarStepOk) {
                        avatarSucceededEver = true
                    }
                    if (chain.failureStep == "hard_stop" || PrefsStore.isHardStopRequested()) {
                        opLog("修改基本资料：OCR 链被硬停打断 step=${chain.failureStep}")
                        runSu("am force-stop $packageName")
                        break
                    }
                    if (profileEditOuterChainFullyComplete(chain)) {
                        opLog("修改基本资料：Bio 后①② 均完成（外轮 $outer/$maxOuter），结束外轮")
                        stopAfterProfileEditOk = runSu("am force-stop $packageName")
                        delay(1200)
                        break
                    }
                    if (chain.bioAfterPart1Ok && !chain.bioAfterPart2Ok) {
                        opLog("外轮$outer：Bio ① 完成、② 未完成（${chain.failureStep}）")
                    } else {
                        opLog("外轮$outer：失败（${chain.failureStep}）")
                    }
                }

                successOverall = profileEditOverallSuccessFromOuterRounds(avatarSucceededEver)
                val chain = lastChain
                val previewsJoin = { xs: List<String> ->
                    xs.joinToString("\n---\n").take(6000)
                }
                opDone(successOverall, "修改基本资料成功", "修改基本资料失败")
                val currentAssignedUsername = resolveAssignedAccount(alias, deviceId)?.username?.trim().orEmpty()

                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (successOverall) "success" else "failed",
                    message = if (successOverall) {
                        "已完成：换头像成功，云端将「基本资料已修改」标为「是」"
                    } else if (chain?.failureStep == "hard_stop") {
                        "已硬停：修改基本资料流程被用户中止，未标「基本资料已修改」"
                    } else if (chain?.bioAfterPart1Ok == true && chain.bioAfterPart2Ok != true) {
                        "未完成：换头像未成功；Bio 后① 已完成但② 未成功（${chain.failureStep}），未标「基本资料已修改」"
                    } else {
                        "未完成：换头像未成功；${chain?.failureStep ?: "outer_or_unknown"}（已最多外轮 $maxOuter 次）"
                    },
                    data = mutableMapOf<String, Any>(
                        "op_type" to "tiktok_edit_basic_profile",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "screen_width_px" to lastW,
                        "screen_height_px" to lastH,
                        "tap_ratio_xy" to listOf(ratioTapX, ratioTapY),
                        "tap_px_xy" to listOf(lastRatioTapPx.first, lastRatioTapPx.second),
                        "stop_ok" to lastStopOk,
                        "launch_ok" to lastLaunchOk,
                        "ratio_tap_ok" to lastRatioTapOk,
                        "profile_edit_entry_mode" to lastEntryMode,
                        "profile_edit_entry_failure_step" to lastEntryFailureStep,
                        "profile_edit_outer_max" to maxOuter,
                        "profile_edit_success" to successOverall,
                        "profile_edit_avatar_step_ok" to avatarSucceededEver,
                        "profile_edit_bio_after_part1_ok" to (chain?.bioAfterPart1Ok ?: false),
                        "profile_edit_bio_after_part2_ok" to (chain?.bioAfterPart2Ok ?: false),
                        "profile_edit_failure_step" to (chain?.failureStep ?: ""),
                        "profile_edit_edit_ocr_attempts" to (chain?.editOcrAttempts ?: 0),
                        "profile_edit_change_ocr_attempts" to (chain?.changeOcrAttempts ?: 0),
                        "profile_edit_edit_ocr_previews" to previewsJoin(chain?.editOcrPreviews ?: emptyList()),
                        "profile_edit_change_ocr_previews" to previewsJoin(chain?.changeOcrPreviews ?: emptyList()),
                        "profile_edit_edit_tap_px" to (chain?.editTapPx?.let { listOf(it.first, it.second) } ?: emptyList()),
                        "profile_edit_change_tap_px" to (chain?.changeTapPx?.let { listOf(it.first, it.second) } ?: emptyList()),
                        "profile_edit_menu_change_photo_px" to (chain?.changeMenuChangePhotoPx?.let { listOf(it.first, it.second) } ?: emptyList()),
                        "profile_edit_menu_name_px" to (chain?.changeMenuNamePx?.let { listOf(it.first, it.second) } ?: emptyList()),
                        "profile_edit_menu_bio_px" to (chain?.changeMenuBioPx?.let { listOf(it.first, it.second) } ?: emptyList()),
                        "profile_edit_bio_verify_attempts" to (chain?.bioVerifyOcrAttempts ?: 0),
                        "profile_edit_post_confirm_change_photo_attempts" to (chain?.postConfirmChangePhotoOcrAttempts ?: 0),
                        "profile_edit_bio_applied" to (chain?.profileEditBioApplied ?: ""),
                        "profile_edit_avatar_verify_attempts" to (chain?.avatarVerifyOcrAttempts ?: 0),
                        "profile_edit_display_name_applied" to (chain?.displayNameRandomApplied ?: ""),
                        "profile_edit_edit_tap_ok" to (chain?.editTapOk ?: false),
                        "profile_edit_change_tap_ok" to (chain?.changeTapOk ?: false),
                        "tiktok_stopped_after_profile_edit" to stopAfterProfileEditOk,
                        "profile_edit_avatar_filename" to profileEditAvatarFilename,
                        "profile_edit_gallery_cleared_before" to profileEditGalleryClearedBefore,
                        "profile_edit_avatar_imported" to profileEditAvatarImported,
                        "operation_logs" to operationLogs,
                    ).enrichProfileEditReportFields(
                        profileUsernameAtStart,
                        profileAccountRowIndex,
                        basicProfileModifiedBefore,
                        chain,
                        currentAssignedUsername,
                    ),
                )
                if (successOverall && profileUsernameAtStart.isNotBlank()) {
                    AutoSwitchBundleHolder.notifyBasicProfileModified(profileUsernameAtStart)
                    ProfileEditStepStateStore.clear(profileUsernameAtStart)
                }
                if (!successOverall && chain?.failureStep != "hard_stop") {
                    scheduleFailureReport(
                        business = "profile_edit",
                        errorType = chain?.failureStep ?: "unknown",
                        summary = chain?.failureStep ?: "profile_edit_failed",
                        ocrRelated = true,
                    )
                }
                } finally {
                    if (profileEditGalleryClearedBefore || profileEditAvatarImported) {
                        opStep("修改基本资料：清理设备图库（流程结束）")
                        executor.clearDevicePicturesGallery(alias)
                    }
                }
            }

            "tiktok_clear" -> {
                val isCutSwitch = !payload?.cut_switch_after_backup.isNullOrBlank()
                val retryUntilSuccess = isAutoSwitchCutSwitchRetryUntilSuccess(payload, suppressLocalAutoSwitchUiLog)
                var clearOk = false
                var attempt = 0
                while (true) {
                    attempt++
                    if (isCutSwitch) {
                        if (retryUntilSuccess && attempt > 1) {
                            opStep("切号步骤重试 第${attempt}轮")
                        } else {
                            opStep("切号步骤1")
                            opStep("切号步骤2")
                        }
                    } else {
                        opStep("开始清理 TikTok 数据")
                        opStep("正在清空 TikTok 应用数据")
                    }
                    clearOk = runSu("pm clear $packageName")
                    if (clearOk || !retryUntilSuccess) break
                    if (PrefsStore.isHardStopRequested()) break
                    recordCutSwitchFailure("pm clear 失败（需 root 权限）：$packageName")
                    if (!delayAutoSwitchCutSwitchRetryIfRetryable(alias, attempt, lastCutSwitchFailureReason)) break
                }
                opDone(
                    clearOk,
                    if (isCutSwitch) "切号步骤完成" else "TikTok 数据清理完成",
                    if (isCutSwitch) "切号步骤失败" else "TikTok 数据清理失败",
                )

                val clearReport = mutableMapOf<String, Any>(
                    "op_type" to "tiktok_clear",
                    "device_id" to deviceId,
                    "package_name" to packageName,
                    "operation_logs" to operationLogs,
                )
                payload?.cut_switch_after_backup?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    clearReport["cut_switch_after_backup"] = it
                }
                payload?.cut_switch_target_row_index?.let { clearReport["cut_switch_target_row_index"] = it }
                payload?.auto_switch_slot_key?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    clearReport["auto_switch_slot_key"] = it
                }
                clearReport.withDeviceOpReportFlags()

                val clearReportResp = reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (clearOk) "success" else "failed",
                    message = if (clearOk) {
                        if (isCutSwitch) "切号步骤完成" else "TikTok 数据清理完成"
                    } else {
                        cutSwitchFailMessage(
                            if (isCutSwitch) "切号步骤失败" else "TikTok 数据清理失败",
                        )
                    },
                    data = clearReport,
                )
                lastDeviceOpOk = clearOk
                if (!clearOk && isCutSwitch && retryUntilSuccess) {
                    notifyAutoSwitchCutSwitchStepFailed(payload)
                }
                if (clearOk && isCutSwitch) {
                    payload?.auto_switch_slot_key?.trim()?.takeIf { it.isNotEmpty() }?.let { sk ->
                        AutoSwitchLocalPlanStore.markCutSwitchClearDone(sk)
                    }
                    val cutMode = payload?.cut_switch_after_backup?.trim()?.lowercase().orEmpty()
                    if (cutMode == "exclusive_activate_target") {
                        finishExclusiveCutSwitchIfLoginOk(
                            alias,
                            deviceId,
                            opId,
                            payload,
                            clearReportResp,
                        )
                    }
                }
            }

            "tiktok_backup_and_clear" -> {
                val backupRoot = UnifiedBackupPaths.ensureBackupsDir(appContext)
                val backupDir = backupRoot.absolutePath
                val qBackupDir = DeviceBackupShell.shellQuote(backupDir)
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val payloadUsername = payload?.username?.trim().orEmpty()
                val isCutSwitchEarly = !payload?.cut_switch_after_backup.isNullOrBlank()
                val reportUsername = if (isCutSwitchEarly) {
                    AutoSwitchLocalPlanStore.resolveCutSwitchBackupAttribution(
                        payloadUsername,
                        payload?.backup_record_id,
                    )?.second?.trim().orEmpty().ifBlank { payloadUsername }
                } else {
                    payloadUsername
                }
                val backupName = UnifiedBackupPaths.buildTikTokBackupArchiveName(
                    alias = alias,
                    username = reportUsername,
                    deviceId = deviceId,
                    timestamp = ts,
                )
                if (backupName == null) {
                    val missingUserReason =
                        "缺少有效 username，无法生成备份包名（包名须含 username）；备份目录：${cutSwitchBackupDirPath()}"
                    recordCutSwitchFailure(missingUserReason)
                    opStep(if (isCutSwitchEarly) "切号步骤1" else missingUserReason)
                    opDone(
                        false,
                        if (isCutSwitchEarly) "切号步骤失败" else "备份失败（缺少 username）",
                        if (isCutSwitchEarly) "切号步骤失败" else "备份失败（缺少 username）",
                    )
                    reportDeviceOpToCloud(
                        deviceId = deviceId,
                        alias = alias,
                        opId = opId,
                        status = "failed",
                        message = cutSwitchFailMessage(
                            if (isCutSwitchEarly) "切号步骤失败" else "备份失败：下发任务未带 username，无法生成与账号绑定的包名",
                        ),
                        data = mapOf(
                            "op_type" to "tiktok_backup_and_clear",
                            "device_id" to deviceId,
                            "package_name" to packageName,
                            "username" to reportUsername,
                            "operation_logs" to operationLogs,
                        ),
                    )
                    if (isCutSwitchEarly) {
                        notifyAutoSwitchCutSwitchStepFailed(payload)
                    }
                    return@handleDeviceOp
                }
                val backupPath = File(backupRoot, backupName).absolutePath
                val qBackupPath = DeviceBackupShell.shellQuote(backupPath)
                val sourceDir = "/data/data/$packageName"

                val isCutSwitch = !payload?.cut_switch_after_backup.isNullOrBlank()
                val retryUntilSuccess = isAutoSwitchCutSwitchRetryUntilSuccess(payload, suppressLocalAutoSwitchUiLog)
                var cutSwitchStep = 0
                val cutStepLabel: (String) -> String = { normal ->
                    if (isCutSwitch) {
                        cutSwitchStep += 1
                        "切号步骤$cutSwitchStep"
                    } else {
                        normal
                    }
                }
                var ok = false
                var backupSkipped = false
                var backupPathReportedImmediately = false
                var dataDirKb: Long? = null
                var attempt = 0
                while (true) {
                    attempt++
                    cutSwitchStep = 0
                    if (retryUntilSuccess && attempt > 1) {
                        opStep(cutStepLabel("切号重试 第${attempt}轮"))
                    } else {
                        opStep(cutStepLabel(if (isCutSwitch) "切号：开始备份当前账号" else if (payload?.skip_pm_clear == true) "开始备份" else "开始备份并清理"))
                        if (isCutSwitch) {
                            opStep(cutStepLabel("备份账号：@$reportUsername"))
                            if (suppressLocalAutoSwitchUiLog) {
                                LogCenter.op(alias, "【切号】备份 @$reportUsername")
                            }
                        } else if (payload?.skip_pm_clear == true) {
                            opStep(cutStepLabel("备份账号：@$reportUsername（仅打包）"))
                        }
                    }

                    opStep(cutStepLabel("第1步：关闭 TikTok"))
                    val stopFail = forceStopTikTokPackageAndVerify(
                        packageName = packageName,
                        alias = alias,
                        phaseLabel = "第1步：关闭 TikTok",
                        aggressiveForCutSwitch = isCutSwitch,
                        runSu = runSu,
                        runSuWithOutput = runSuWithOutput,
                    )
                    val stopOk = stopFail == null
                    if (stopFail != null) {
                        recordCutSwitchFailure("第1步关闭 TikTok 失败：$stopFail")
                    }

                    opStep(cutStepLabel("第2步：检测数据目录大小"))
                    dataDirKb = if (stopOk) {
                        runSuWithOutput(DeviceBackupShell.measureDataDirKbCommand(sourceDir))
                            ?.lineSequence()
                            ?.map { it.trim() }
                            ?.firstOrNull { it.isNotEmpty() }
                            ?.toLongOrNull()
                    } else {
                        null
                    }
                    backupSkipped = stopOk &&
                        (dataDirKb == null || dataDirKb < DeviceBackupShell.MIN_DATA_DIR_KB_FOR_BACKUP)

                    val backupOnly = payload?.skip_pm_clear == true
                    val backupOk = if (!stopOk) {
                        false
                    } else if (backupSkipped) {
                        if (isCutSwitch) {
                            val (existingOk, _) = UnifiedBackupPaths.verifyLocalBackupArchive(
                                context = appContext,
                                username = reportUsername,
                                alias = alias,
                                deviceId = deviceId,
                            )
                            val skipNote = if (existingOk) {
                                "（本机已有有效备份）"
                            } else {
                                "（无有效备份，仍继续清理切号）"
                            }
                            opStep(
                                cutStepLabel(
                                    "数据目录 ${dataDirKb ?: 0}KB < ${DeviceBackupShell.MIN_DATA_DIR_KB_FOR_BACKUP}KB，跳过打包备份$skipNote",
                                ),
                            )
                            if (suppressLocalAutoSwitchUiLog) {
                                LogCenter.op(
                                    alias,
                                    "【切号】@${reportUsername} TikTok 数据过小，跳过备份$skipNote",
                                )
                            }
                            true
                        } else if (backupOnly && payload?.source == "manual_switch_pre_backup") {
                            // 手动下拉切号前备份：空号/未登录数据过小，跳过打包并继续切号（无可丢数据）
                            opStep(
                                cutStepLabel(
                                    "数据目录 ${dataDirKb ?: 0}KB < ${DeviceBackupShell.MIN_DATA_DIR_KB_FOR_BACKUP}KB，跳过打包备份（切号前无可保数据）",
                                ),
                            )
                            LogCenter.op(
                                alias,
                                "手动切号：@${reportUsername.ifBlank { "?" }} TikTok 数据过小（${dataDirKb ?: 0}KB），跳过备份继续切号",
                            )
                            true
                        } else if (backupOnly) {
                            opStep(
                                cutStepLabel(
                                    "数据目录 ${dataDirKb ?: 0}KB < ${DeviceBackupShell.MIN_DATA_DIR_KB_FOR_BACKUP}KB，仅备份失败（未生成包）",
                                ),
                            )
                            false
                        } else {
                            opStep(
                                cutStepLabel(
                                    "数据目录 ${dataDirKb ?: 0}KB < ${DeviceBackupShell.MIN_DATA_DIR_KB_FOR_BACKUP}KB，跳过打包备份",
                                ),
                            )
                            true
                        }
                    } else {
                        opStep(cutStepLabel("第3步：打包前再次关闭 TikTok"))
                        val stopBeforeTarFail = forceStopTikTokPackageAndVerify(
                            packageName = packageName,
                            alias = alias,
                            phaseLabel = "第3步：打包前",
                            aggressiveForCutSwitch = isCutSwitch,
                            runSu = runSu,
                            runSuWithOutput = runSuWithOutput,
                        )
                        if (stopBeforeTarFail != null) {
                            recordCutSwitchFailure("第3步：打包前 TikTok 未完全退出：$stopBeforeTarFail")
                            false
                        } else {
                            opStep(cutStepLabel("第3步：备份账号数据"))
                            val tarOk = runSu(
                                DeviceBackupShell.tiktokStyleDataTarCommand(
                                    dataPackageName = packageName,
                                    quotedBackupDir = qBackupDir,
                                    quotedOutputTarPath = qBackupPath,
                                ),
                            )
                            if (!tarOk) {
                                val pids = queryTikTokProcessIds(packageName, runSuWithOutput)
                                val detail = if (pids.isNotEmpty()) {
                                    "第3步打包备份失败：tar 非零退出，且 TikTok 仍有进程 pid=$pids；目标：$backupPath"
                                } else {
                                    "第3步打包备份失败，目标：$backupPath（备份目录：$backupDir）"
                                }
                                recordCutSwitchFailure(detail)
                                if (suppressLocalAutoSwitchUiLog) {
                                    LogCenter.op(alias, "【切号】$detail")
                                }
                                false
                            } else {
                                val (verified, verifyMsg) = UnifiedBackupPaths.verifyLocalBackupArchive(
                                    context = appContext,
                                    username = reportUsername,
                                    alias = alias,
                                    deviceId = deviceId,
                                    expectedAbsolutePath = backupPath,
                                )
                                if (!verified) {
                                    recordCutSwitchFailure(verifyMsg)
                                    if (isCutSwitch) {
                                        LogCenter.op(alias, "【切号·备份失败】$verifyMsg")
                                    }
                                    false
                                } else {
                                    true
                                }
                            }
                        }
                    }

                    if (backupOk && !backupSkipped && !backupPathReportedImmediately) {
                        reportAutoSwitchCutSwitchBackupImmediately(
                            alias = alias,
                            deviceId = deviceId,
                            opId = opId,
                            username = reportUsername,
                            packageName = packageName,
                            backupName = backupName,
                            backupPath = backupPath,
                            payload = payload,
                        )
                        backupPathReportedImmediately = true
                    }

                    val clearOk = if (backupOk) {
                        when {
                            payload?.skip_pm_clear == true -> {
                                opStep(cutStepLabel("仅备份：跳过 pm clear"))
                                true
                            }
                            isCutSwitch && !backupSkipped -> {
                                val (verified, verifyMsg) = UnifiedBackupPaths.verifyLocalBackupArchive(
                                    context = appContext,
                                    username = reportUsername,
                                    alias = alias,
                                    deviceId = deviceId,
                                    expectedAbsolutePath = backupPath,
                                )
                                if (!verified) {
                                    opStep(cutStepLabel("清理前校验失败：$verifyMsg"))
                                    recordCutSwitchFailure("清理前校验失败：$verifyMsg")
                                    LogCenter.op(alias, "【切号·备份失败】清理前校验：$verifyMsg")
                                    false
                                } else {
                                    opStep(cutStepLabel("第4步：清理 TikTok 数据"))
                                    runSu("pm clear $packageName")
                                }
                            }
                            isCutSwitch && backupSkipped -> {
                                opStep(cutStepLabel("第4步：清理 TikTok 数据（备份已跳过）"))
                                runSu("pm clear $packageName")
                            }
                            else -> {
                                opStep(cutStepLabel(if (backupSkipped) "第3步：清理 TikTok 数据" else "第4步：清理 TikTok 数据"))
                                runSu("pm clear $packageName")
                            }
                        }
                    } else {
                        opStep(cutStepLabel("备份未成功，已跳过清理以免误删数据"))
                        false
                    }

                    ok = stopOk && backupOk && clearOk
                    if (ok) break
                    if (!retryUntilSuccess) {
                        if (attempt >= 2) break
                        opStep(cutStepLabel("本轮失败，自动重试"))
                        delay(1000)
                        continue
                    }
                    if (PrefsStore.isHardStopRequested()) break
                    val failReason = when {
                        !stopOk ->
                            lastCutSwitchFailureReason.ifBlank {
                                "第1步关闭 TikTok 失败（am force-stop $packageName）"
                            }
                        !backupOk ->
                            when {
                                backupSkipped && isCutSwitch ->
                                    lastCutSwitchFailureReason.ifBlank {
                                        "切号备份失败：TikTok 数据过小，源账号 @$reportUsername 未生成有效备份"
                                    }
                                else ->
                                    lastCutSwitchFailureReason.ifBlank {
                                        "第3步打包备份失败，目标：$backupPath（备份目录：$backupDir）"
                                    }
                            }
                        !clearOk ->
                            "第${if (backupSkipped) 3 else 4}步 pm clear 失败（需 root）：$packageName"
                        else -> "切号备份/清理未知失败"
                    }
                    if (!delayAutoSwitchCutSwitchRetryIfRetryable(alias, attempt, failReason)) break
                }

                if (!ok && isCutSwitch) {
                    val failDetail = lastCutSwitchFailureReason.ifBlank { "切号备份/清理失败" }
                    LogCenter.op(alias, "【切号·备份失败】@$reportUsername：$failDetail")
                }

                if (ok && !backupSkipped) {
                    val prevRaw = payload?.previous_backup_path?.trim().orEmpty()
                    if (prevRaw.isNotEmpty() && prevRaw != backupPath &&
                        UnifiedBackupPaths.isSafeToDeleteOldBackup(appContext, prevRaw, backupPath)
                    ) {
                        opStep(cutStepLabel("正在删除云端记录的旧备份文件"))
                        val qPrev = DeviceBackupShell.shellQuote(prevRaw)
                        runSu("rm -f $qPrev")
                    }
                    val reconcileAccounts = buildAccountsForBackupDirectoryReconcile(
                        reportUsername = reportUsername,
                        backupName = backupName,
                        backupPath = backupPath,
                    )
                    val isManualSwitchPreBackup = payload?.source == "manual_switch_pre_backup"
                    runBackupDirectoryReconcile(
                        alias = alias,
                        deviceId = deviceId,
                        accounts = reconcileAccounts,
                        extraKeepAbsolutePath = backupPath,
                        logPrefix = if (suppressLocalAutoSwitchUiLog) "【切号·备份整理】" else "【备份目录整理】",
                        // 切号预备份：只允许清同号旧包，禁止动其它号（目标号包必须活着才能还原）
                        deleteUnlistedUsernameArchives = !isManualSwitchPreBackup,
                    )
                }
                opDone(
                    ok,
                    when {
                        !ok -> if (isCutSwitch) {
                            lastCutSwitchFailureReason.ifBlank { "切号步骤失败" }
                        } else if (payload?.skip_pm_clear == true) {
                            "备份失败"
                        } else {
                            "备份或清理失败"
                        }
                        backupSkipped -> if (payload?.skip_pm_clear == true) {
                            if (payload.source == "manual_switch_pre_backup") {
                                "数据过小已跳过备份"
                            } else {
                                "备份失败（数据过小）"
                            }
                        } else {
                            "数据过小已跳过备份，清理完成"
                        }
                        isCutSwitch -> "切号步骤完成"
                        payload?.skip_pm_clear == true -> "备份完成"
                        else -> "备份并清理完成"
                    },
                    if (isCutSwitch) {
                        "切号步骤失败"
                    } else if (payload?.skip_pm_clear == true) {
                        "备份失败"
                    } else {
                        "备份或清理失败"
                    },
                )

                val reportBackupRecordId = payload?.backup_record_id
                val reportData = mutableMapOf<String, Any>(
                    "op_type" to "tiktok_backup_and_clear",
                    "device_id" to deviceId,
                    "backup_name" to backupName,
                    "backup_path" to backupPath,
                    "source_dir" to sourceDir,
                    "package_name" to packageName,
                    "mobile_alias" to alias,
                    "username" to reportUsername,
                    "operation_logs" to operationLogs,
                    "backup_skipped" to backupSkipped,
                )
                dataDirKb?.let { reportData["source_dir_kb"] = it }
                reportBackupRecordId?.let { reportData["backup_record_id"] = it }
                payload?.cut_switch_after_backup?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    reportData["cut_switch_after_backup"] = it
                }
                payload?.cut_switch_target_row_index?.let { reportData["cut_switch_target_row_index"] = it }
                payload?.auto_switch_slot_key?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    reportData["auto_switch_slot_key"] = it
                }
                reportData.withDeviceOpReportFlags()

                val backupReportResp = reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (ok) "success" else "failed",
                    message = when {
                        !ok -> cutSwitchFailMessage(
                            if (isCutSwitch) {
                                lastCutSwitchFailureReason.ifBlank { "切号步骤失败" }
                            } else {
                                "备份或清理失败"
                            },
                        )
                        backupSkipped -> "数据过小已跳过备份，清理完成"
                        isCutSwitch -> "切号步骤完成"
                        else -> "备份并清理完成"
                    },
                    data = reportData,
                )
                lastDeviceOpOk = ok
                if (ok && isCutSwitch) {
                    payload?.auto_switch_slot_key?.trim()?.takeIf { it.isNotEmpty() }?.let { sk ->
                        AutoSwitchLocalPlanStore.markCutSwitchBackupClearDone(sk, backupSkipped = backupSkipped)
                    }
                    val cutMode = payload?.cut_switch_after_backup?.trim()?.lowercase().orEmpty()
                    if (cutMode == "exclusive_activate_target") {
                        finishExclusiveCutSwitchIfLoginOk(
                            alias,
                            deviceId,
                            opId,
                            payload,
                            backupReportResp,
                        )
                    }
                } else if (!ok && isCutSwitch && retryUntilSuccess) {
                    notifyAutoSwitchCutSwitchStepFailed(
                        payload,
                        CutSwitchFailureKind.BACKUP_OR_CLEAR,
                        lastCutSwitchFailureReason.ifBlank { "切号备份/清理失败" },
                    )
                }
            }

            "tiktok_restore" -> {
                val backupNameHint = payload?.backup_name.orEmpty()
                val backupPathHint = payload?.backup_path?.trim().orEmpty()
                val backupRoot = UnifiedBackupPaths.ensureBackupsDir(appContext)
                val sourceDir = "/data/data/$packageName"
                val qPkg = DeviceBackupShell.shellQuote(packageName)
                val skipPmClear = payload?.skip_pm_clear == true
                val retryUntilSuccess = isAutoSwitchCutSwitchRetryUntilSuccess(payload, suppressLocalAutoSwitchUiLog)
                val restoreUsername = payload?.username?.trim().orEmpty()

                var restoreCutSwitchStep = 0
                val restoreStepLabel: (String) -> String = { normal ->
                    if (skipPmClear) {
                        restoreCutSwitchStep += 1
                        "切号步骤$restoreCutSwitchStep"
                    } else {
                        normal
                    }
                }
                var restoreOk = false
                var attempt = 0
                var corruptDiscardCount = 0
                var resolvedBackupName = backupNameHint
                var resolvedBackupPath = backupPathHint.ifBlank {
                    if (backupNameHint.isNotBlank()) {
                        File(backupRoot, backupNameHint).absolutePath
                    } else {
                        backupRoot.absolutePath
                    }
                }
                while (true) {
                    attempt++
                    restoreCutSwitchStep = 0
                    val resolvedBackup = UnifiedBackupPaths.resolveRestoreBackupArchive(
                        context = appContext,
                        backupName = backupNameHint,
                        backupPathHint = backupPathHint,
                        username = restoreUsername,
                        alias = alias,
                        deviceId = deviceId,
                    )
                    if (resolvedBackup == null) {
                        if (isAutoSwitchCutSwitchRestore(payload, skipPmClear) && restoreUsername.isNotBlank()) {
                            val missingPath = if (backupNameHint.isNotBlank()) {
                                File(backupRoot, backupNameHint).absolutePath
                            } else {
                                backupPathHint.ifBlank { backupRoot.absolutePath }
                            }
                            handleCutSwitchMissingBackupFallback(
                                alias = alias,
                                deviceId = deviceId,
                                opId = opId,
                                payload = payload,
                                restoreUsername = restoreUsername,
                                cloudBackupName = backupNameHint.ifBlank { File(backupPathHint).name },
                                phase = "restore",
                                internalDetail = describeMissingRestoreBackup(
                                    backupName = backupNameHint,
                                    backupPath = missingPath,
                                    username = restoreUsername,
                                    alias = alias,
                                    deviceId = deviceId,
                                ),
                            )
                            return@handleDeviceOp
                        }
                        val missingPath = if (backupNameHint.isNotBlank()) {
                            File(backupRoot, backupNameHint).absolutePath
                        } else {
                            backupPathHint.ifBlank { backupRoot.absolutePath }
                        }
                        val failReason = describeMissingRestoreBackup(
                            backupName = backupNameHint,
                            backupPath = missingPath,
                            username = restoreUsername,
                            alias = alias,
                            deviceId = deviceId,
                        )
                        recordCutSwitchFailure(failReason)
                        break
                    }
                    resolvedBackupName = resolvedBackup.fileName
                    resolvedBackupPath = resolvedBackup.absolutePath
                    val backupName = resolvedBackup.fileName
                    val backupPath = resolvedBackup.absolutePath
                    val qBackupPath = DeviceBackupShell.shellQuote(backupPath)
                    if (resolvedBackup.fileName != backupNameHint.trim() &&
                        resolvedBackup.absolutePath != backupPathHint.trim() &&
                        attempt == 1 &&
                        suppressLocalAutoSwitchUiLog
                    ) {
                        LogCenter.op(
                            alias,
                            "【切号】云端记录备份不可用，改用本机同账号备份",
                        )
                    }
                    if (retryUntilSuccess && attempt > 1) {
                        opStep(restoreStepLabel("切号重试 第${attempt}轮"))
                    } else if (!retryUntilSuccess && attempt > 1) {
                        opStep(restoreStepLabel("本轮失败，自动重试"))
                    }
                    opStep(restoreStepLabel(if (skipPmClear) "切号：开始还原目标账号" else "开始还原备份"))
                    if (skipPmClear && attempt == 1) {
                        opStep(restoreStepLabel("检查本机备份包"))
                        opStep(
                            restoreStepLabel(
                                "还原目标：@${restoreUsername.ifBlank { "?" }}",
                            ),
                        )
                        if (suppressLocalAutoSwitchUiLog) {
                            LogCenter.op(
                                alias,
                                "【切号】还原 @${restoreUsername.ifBlank { "?" }}",
                            )
                        }
                    }

                    opStep(restoreStepLabel("第1步：关闭 TikTok"))
                    val stopFail = if (skipPmClear) {
                        forceStopTikTokPackageAndVerify(
                            packageName = packageName,
                            alias = alias,
                            phaseLabel = "第1步：关闭 TikTok",
                            aggressiveForCutSwitch = true,
                            runSu = runSu,
                            runSuWithOutput = runSuWithOutput,
                        )
                    } else {
                        if (runSu("am force-stop $packageName")) null else "am force-stop 失败（$packageName）"
                    }
                    val stopOk = stopFail == null
                    if (stopFail != null && skipPmClear) {
                        recordCutSwitchFailure("第1步关闭 TikTok 失败：$stopFail")
                    }

                    val clearOk = if (skipPmClear) {
                        stopOk
                    } else {
                        opStep("第2步：清空 TikTok 旧数据")
                        stopOk && runSu("pm clear $qPkg")
                    }

                    val backupCheckLabel =
                        if (skipPmClear) restoreStepLabel("第2步：检查备份文件") else "第3步：检查备份文件"
                    val backupExists = clearOk && runRestoreShellStep(::opStep, backupCheckLabel) {
                        runSu("test -f $qBackupPath")
                    }

                    val integrityLabel =
                        if (skipPmClear) restoreStepLabel("第2.5步：校验备份包完整性") else "第3.5步：校验备份包完整性"
                    val integrityOk = backupExists && runRestoreShellStep(::opStep, integrityLabel) {
                        probeBackupArchiveIntegrity(runSu, backupPath)
                    }
                    if (backupExists && !integrityOk) {
                        if (corruptDiscardCount >= 5) {
                            recordCutSwitchFailure(
                                "备份包损坏已删除次数过多（@$restoreUsername），停止还原",
                            )
                            if (isAutoSwitchCutSwitchRestore(payload, skipPmClear) &&
                                restoreUsername.isNotBlank()
                            ) {
                                handleCutSwitchMissingBackupFallback(
                                    alias = alias,
                                    deviceId = deviceId,
                                    opId = opId,
                                    payload = payload,
                                    restoreUsername = restoreUsername,
                                    cloudBackupName = backupNameHint.ifBlank { backupName },
                                    phase = "restore_corrupt_limit",
                                    internalDetail = "corrupt discard limit: $backupPath",
                                    userMessageOverride = cutSwitchCorruptBackupUserMessage(restoreUsername),
                                )
                                return@handleDeviceOp
                            }
                            break
                        }
                        corruptDiscardCount++
                        when (
                            discardCorruptRestoreBackupAndMaybeFallback(
                                alias = alias,
                                deviceId = deviceId,
                                opId = opId,
                                payload = payload,
                                skipPmClear = skipPmClear,
                                restoreUsername = restoreUsername,
                                backupName = backupName,
                                backupPath = backupPath,
                                cloudBackupName = backupNameHint.ifBlank { backupName },
                                runSu = runSu,
                                phase = "restore_corrupt_precheck",
                                onFailure = ::recordCutSwitchFailure,
                            )
                        ) {
                            null -> return@handleDeviceOp
                            true -> continue
                            false -> break
                        }
                    }

                    val restoreDataLabel =
                        if (skipPmClear) restoreStepLabel("第3步：恢复账号数据") else "第4步：恢复账号数据"
                    val restoreDataOk = integrityOk && runRestoreShellStep(::opStep, restoreDataLabel) {
                        runSu("tar -xzf $qBackupPath -C /data/data")
                    }
                    if (integrityOk && !restoreDataOk &&
                        !probeBackupArchiveIntegrity(runSu, backupPath)
                    ) {
                        if (corruptDiscardCount >= 5) {
                            recordCutSwitchFailure(
                                "备份包损坏已删除次数过多（@$restoreUsername），停止还原",
                            )
                            if (isAutoSwitchCutSwitchRestore(payload, skipPmClear) &&
                                restoreUsername.isNotBlank()
                            ) {
                                handleCutSwitchMissingBackupFallback(
                                    alias = alias,
                                    deviceId = deviceId,
                                    opId = opId,
                                    payload = payload,
                                    restoreUsername = restoreUsername,
                                    cloudBackupName = backupNameHint.ifBlank { backupName },
                                    phase = "restore_corrupt_limit",
                                    internalDetail = "corrupt after tar: $backupPath",
                                    userMessageOverride = cutSwitchCorruptBackupUserMessage(restoreUsername),
                                )
                                return@handleDeviceOp
                            }
                            break
                        }
                        corruptDiscardCount++
                        when (
                            discardCorruptRestoreBackupAndMaybeFallback(
                                alias = alias,
                                deviceId = deviceId,
                                opId = opId,
                                payload = payload,
                                skipPmClear = skipPmClear,
                                restoreUsername = restoreUsername,
                                backupName = backupName,
                                backupPath = backupPath,
                                cloudBackupName = backupNameHint.ifBlank { backupName },
                                runSu = runSu,
                                phase = "restore_corrupt_after_tar",
                                onFailure = ::recordCutSwitchFailure,
                            )
                        ) {
                            null -> return@handleDeviceOp
                            true -> continue
                            false -> break
                        }
                    }

                    val permissionLabel =
                        if (skipPmClear) restoreStepLabel("第4步：修复文件权限") else "第5步：修复文件权限"
                    var permissionFailDetail = ""
                    val permissionOk = restoreDataOk && runRestoreShellStep(::opStep, permissionLabel) {
                        val uidGid = runSuWithOutput(
                            "stat -c '%u:%g' $sourceDir 2>/dev/null || ls -ldn $sourceDir 2>/dev/null | tr -s ' ' | cut -d ' ' -f3,4 | tr ' ' ':'",
                        )
                            ?.lineSequence()
                            ?.map { it.trim() }
                            ?.firstOrNull { it.contains(":") }
                        if (uidGid.isNullOrBlank()) {
                            permissionFailDetail = "无法读取 TikTok 数据目录 uid:gid（$sourceDir，可能 tar 解压未生成目录）"
                            return@runRestoreShellStep false
                        }
                        val chownOk = runSu("chown -R $uidGid $sourceDir")
                        if (!chownOk) {
                            permissionFailDetail = "chown -R $uidGid $sourceDir 失败（需 root）"
                            return@runRestoreShellStep false
                        }
                        val hasRestorecon = runSu("command -v restorecon >/dev/null 2>&1")
                        val restoreconOk = if (hasRestorecon) {
                            runSu("restorecon -RF $sourceDir")
                        } else {
                            true
                        }
                        if (!restoreconOk) {
                            permissionFailDetail = "restorecon -RF $sourceDir 失败"
                            return@runRestoreShellStep false
                        }
                        val chmodOk = runSu("chmod -R 700 $sourceDir")
                        if (!chmodOk) {
                            permissionFailDetail = "chmod -R 700 $sourceDir 失败"
                            return@runRestoreShellStep false
                        }
                        true
                    }

                    restoreOk = restoreDataOk && permissionOk
                    if (restoreOk) break
                    if (!retryUntilSuccess) {
                        if (attempt >= 2) break
                        delay(1000)
                        continue
                    }
                    if (PrefsStore.isHardStopRequested()) break
                    if (
                        !backupExists &&
                        isAutoSwitchCutSwitchRestore(payload, skipPmClear) &&
                        restoreUsername.isNotBlank()
                    ) {
                        val failDetail = describeMissingRestoreBackup(
                            backupName = backupName,
                            backupPath = backupPath,
                            username = restoreUsername,
                            alias = alias,
                            deviceId = deviceId,
                        )
                        handleCutSwitchMissingBackupFallback(
                            alias = alias,
                            deviceId = deviceId,
                            opId = opId,
                            payload = payload,
                            restoreUsername = restoreUsername,
                            cloudBackupName = backupName,
                            phase = "restore_check",
                            internalDetail = failDetail,
                        )
                        return@handleDeviceOp
                    }
                    val failReason = when {
                        !stopOk ->
                            lastCutSwitchFailureReason.ifBlank {
                                "第1步关闭 TikTok 失败（am force-stop $packageName）"
                            }
                        !clearOk && !skipPmClear -> "第2步 pm clear 失败（需 root）：$packageName"
                        !backupExists ->
                            describeMissingRestoreBackup(
                                backupName = backupName,
                                backupPath = backupPath,
                                username = restoreUsername,
                                alias = alias,
                                deviceId = deviceId,
                            )
                        !integrityOk ->
                            "备份包损坏（gzip/tar 完整性校验失败）：$backupName"
                        !restoreDataOk ->
                            "第3步 tar 解压失败：$backupPath → /data/data（包可能损坏或 root 权限不足）"
                        !permissionOk ->
                            permissionFailDetail.ifBlank { "第4步权限修复失败：$sourceDir" }
                        else -> "切号还原未知失败"
                    }
                    recordCutSwitchFailure(failReason)
                    if (
                        isAutoSwitchCutSwitchRestore(payload, skipPmClear) &&
                        restoreUsername.isNotBlank() &&
                        isCutSwitchMissingBackupFailure(failReason)
                    ) {
                        handleCutSwitchMissingBackupFallback(
                            alias = alias,
                            deviceId = deviceId,
                            opId = opId,
                            payload = payload,
                            restoreUsername = restoreUsername,
                            cloudBackupName = backupNameHint.ifBlank { backupName },
                            phase = "restore_retry_guard",
                            internalDetail = failReason,
                            userMessageOverride = if (isCutSwitchCorruptBackupFailure(failReason)) {
                                cutSwitchCorruptBackupUserMessage(restoreUsername)
                            } else {
                                ""
                            },
                        )
                        return@handleDeviceOp
                    }
                    if (!delayAutoSwitchCutSwitchRetryIfRetryable(alias, attempt, failReason)) break
                }
                if (restoreOk) {
                    payload?.username?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        PrefsStore.setLogContextUsername(it)
                    }
                    if (PrefsStore.isAutoSwitchAutonomousMode()) {
                        applyBundledConfigFromAutoSwitch(PrefsStore.getLogContextUsername(), logToUser = false)
                    } else {
                        fetchConfig(logToUser = false)
                    }
                }
                opDone(
                    restoreOk,
                    if (skipPmClear) "切号步骤完成" else "还原完成",
                    if (skipPmClear) "切号步骤失败" else "还原失败",
                )

                val restoreReport = mutableMapOf<String, Any>(
                    "op_type" to "tiktok_restore",
                    "device_id" to deviceId,
                    "backup_name" to resolvedBackupName,
                    "backup_path" to resolvedBackupPath,
                    "source_dir" to sourceDir,
                    "package_name" to packageName,
                    "operation_logs" to operationLogs,
                )
                if (skipPmClear) {
                    restoreReport["skip_pm_clear"] = true
                }
                payload?.backup_record_id?.let { restoreReport["backup_record_id"] = it }
                payload?.restore_highlight_row_index?.let { restoreReport["restore_highlight_row_index"] = it }
                payload?.auto_switch_slot_key?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    restoreReport["auto_switch_slot_key"] = it
                }
                restoreReport.withDeviceOpReportFlags()

                val restoreReportResp = reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (restoreOk) "success" else "failed",
                    message = if (skipPmClear) {
                        if (restoreOk) "切号步骤完成" else cutSwitchFailMessage("切号步骤失败")
                    } else {
                        if (restoreOk) "清理并还原完成" else cutSwitchFailMessage("清理或还原失败")
                    },
                    data = restoreReport,
                )
                lastDeviceOpOk = restoreOk
                if (skipPmClear) {
                    val isLocalManualRestore =
                        ManualDeviceControl.isLocalManualOpId(opId) ||
                            payload?.source?.trim().orEmpty().startsWith("manual")
                    if (isLocalManualRestore) {
                        // 手动切号还原：只负责清/还，成功与否由调用方（manualSwitchAccount）收尾。
                        // 绝不可走 finishCutSwitchAfterSuccess（会因云端 is_login=否再跑自动登录，看起来像「没还原却登录」）。
                        if (restoreOk) {
                            LogCenter.op(
                                alias,
                                "手动还原完成：@${restoreUsername.ifBlank { "?" }} ← $resolvedBackupName（不触发切号后自动登录）",
                            )
                        } else {
                            LogCenter.op(
                                alias,
                                "手动还原失败：@${restoreUsername.ifBlank { "?" }} ← $resolvedBackupName（${lastCutSwitchFailureReason.ifBlank { "见 OP 日志" }}）",
                            )
                        }
                    } else if (restoreOk) {
                        finishCutSwitchAfterSuccess(
                            alias,
                            deviceId,
                            opId,
                            restoreReportResp,
                            targetRowIndex = payload?.restore_highlight_row_index
                                ?: payload?.cut_switch_target_row_index
                                ?: -1,
                            slotKey = payload?.auto_switch_slot_key?.trim().orEmpty(),
                        )
                    } else {
                        if (retryUntilSuccess) {
                            notifyAutoSwitchCutSwitchStepFailed(
                                payload,
                                CutSwitchFailureKind.RESTORE_OR_LOGIN,
                                lastCutSwitchFailureReason.ifBlank { "切号还原失败" },
                            )
                        } else {
                            finishCutSwitchAfterFailureFallback(
                                alias,
                                deviceId,
                                opId,
                                payload?.auto_switch_slot_key?.trim().orEmpty(),
                            )
                        }
                    }
                }
            }

            else -> {
                opLog("未知操作类型: ${op.op_type}")
                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = "failed",
                    message = "未知操作类型: ${op.op_type}",
                    data = mapOf("operation_logs" to operationLogs),
                )
            }
        }
        } finally {
            ExecutionScope.exit()
            if (isExclusiveUi) {
                finishExclusiveTikTokUiOp(alias, deviceId, opId, resumePullAfterExclusive)
            } else if (isDestructive) {
                restoreCloudAutoStartParticipation(alias, deviceId, opId, "备份/切号任务结束")
            }
        }
    }

    private fun triggerLiveVisitNow(alias: String) {
        if (liveVisitSwitchJob?.isActive == true) {
            LogCenter.op(alias, "主播访问任务已在执行中，忽略重复触发")
            return
        }
        if (isPublishPostSwitchActive() || isMutualSwitchActive()) {
            LogCenter.op(alias, "主播访问暂缓：发作品/互点仍在执行，回退 PENDING 待下轮入队")
            requeueDeferredLiveVisit()
            return
        }
        liveVisitSwitchJob = scope.launch {
            ExecutionScope.enter()
            var completed = false
            // complete=成功收尾；fail=失败收尾；requeue=回退 PENDING；none=抢占已收尾
            var finishMode = "fail"
            try {
                pullLoopJob?.cancel()
                pullLoopJob = null
                delay(120)
                val deviceId = PrefsStore.getDeviceId().orEmpty()
                val active = AutoSwitchLocalPlanStore.getActiveLiveVisit()
                val accountUser = active?.account_username?.trim().orEmpty()
                if (accountUser.isNotBlank()) {
                    applyBundledConfigFromAutoSwitch(accountUser, logToUser = false)
                }
                if (deviceId.isNotBlank() &&
                    !ensureLoggedInBeforeExclusiveTask(alias, deviceId, "主播访问")
                ) {
                    LogCenter.op(alias, "主播访问：登录未就绪，回退 PENDING 待重试")
                    finishMode = "requeue"
                    return@launch
                }
                val remaining = AutoSwitchLocalPlanStore.getActiveLiveVisit()
                    ?.remaining_usernames
                    .orEmpty()
                    .toMutableList()
                if (remaining.isEmpty()) {
                    LogCenter.op(alias, "主播访问：本地名单为空，标记完成并恢复涨粉")
                    completed = true
                    finishMode = "complete"
                    return@launch
                }
                val liveConfig = _config.value.copy(run_mode = ServerRunMode.LIVE)
                var successCount = 0
                while (remaining.isNotEmpty()) {
                    if (shouldHonorUserHardStop() || PrefsStore.isHardStopRequested()) {
                        LogCenter.op(alias, "主播访问：硬停中断，剩余 ${remaining.size} 条")
                        finishMode = "fail"
                        break
                    }
                    if (PrefsStore.isAutoSwitchAutonomousMode() &&
                        AutoSwitchLocalPlanStore.getActiveLiveVisit() == null
                    ) {
                        LogCenter.op(alias, "主播访问：已被切号抢占，中止本批")
                        finishMode = "none"
                        return@launch
                    }
                    val username = remaining.removeAt(0)
                    AutoSwitchLocalPlanStore.setActiveLiveVisitRemaining(remaining)
                    LogCenter.op(alias, "主播访问：开始 @$username（剩余 ${remaining.size}）")
                    val result = runCatching {
                        executor.executeForUsername(username, liveConfig)
                    }.getOrElse { err ->
                        when (err) {
                            is LoginSafeguardRequiresAutoLoginException -> {
                                if (deviceId.isNotBlank()) {
                                    val loginReady = ensureAccountLoggedInWithRetry(
                                        alias = alias,
                                        deviceId = deviceId,
                                        reason = "主播访问保障",
                                        forceRelogin = true,
                                        resumeFromLoginEntryScreen = true,
                                    ) == AccountLoginRetryOutcome.LoggedIn
                                    if (loginReady) {
                                        runCatching {
                                            executor.executeForUsername(username, liveConfig)
                                        }.getOrElse { e2 ->
                                            "失败：登录保障后仍异常 ${e2.message ?: "unknown"}"
                                        }
                                    } else {
                                        "失败：登录保障后仍未登录"
                                    }
                                } else {
                                    "失败：需要自动登录"
                                }
                            }
                            is AccountPlanBlockedException -> {
                                if (deviceId.isNotBlank()) {
                                    handleAccountPlanBlocked(alias, deviceId, err, "主播访问")
                                }
                                val kindLabel =
                                    if (err.blockKind.equals("max_login", true)) "登录次数上限" else "账号封禁"
                                "失败：$kindLabel ${err.message ?: err.blockKind}"
                            }
                            is AccountStatusLoggedOutException -> {
                                if (deviceId.isNotBlank()) {
                                    recoverFromAccountStatusLogout(alias, deviceId, "主播访问")
                                }
                                "失败：掉登录 ${err.message ?: "account_status"}"
                            }
                            else -> "失败：${err.message ?: "unknown"}"
                        }
                    }
                    val failed = result.startsWith("失败：") ||
                        result.contains("执行失败") ||
                        result.contains("异常")
                    if (failed) {
                        LogCenter.op(alias, "主播访问失败：@$username — $result")
                    } else {
                        successCount += 1
                        LogCenter.op(alias, "主播访问完成：@$username — $result")
                    }
                }
                completed = remaining.isEmpty()
                finishMode = if (completed) "complete" else "fail"
                LogCenter.op(
                    alias,
                    "主播访问结束：成功 $successCount 条，剩余 ${remaining.size}，completed=$completed",
                )
                updateRuntime(lastMessage = "主播访问结束：成功 $successCount")
            } finally {
                when {
                    !PrefsStore.isAutoSwitchAutonomousMode() -> {
                        AutoSwitchLocalPlanStore.clearActiveLiveVisit()
                    }
                    finishMode == "none" -> {
                        AutoSwitchLocalPlanStore.clearActiveLiveVisit()
                    }
                    finishMode == "requeue" -> {
                        requeueDeferredLiveVisit()
                    }
                    else -> {
                        val sk = AutoSwitchLocalPlanStore.activeLiveVisitSlotKey()?.trim().orEmpty()
                            .ifBlank {
                                AutoSwitchLocalPlanStore.get()?.slots
                                    ?.firstOrNull { it.live_visit.state == PlanStepState.RUNNING }
                                    ?.slot_key?.trim().orEmpty()
                            }
                        if (sk.isNotBlank() &&
                            AutoSwitchLocalPlanStore.insertionState(sk, "live_visit") == PlanStepState.RUNNING
                        ) {
                            autoSwitchLocalScheduler.finalizeLiveVisitOutcome(finishMode == "complete")
                        } else {
                            AutoSwitchLocalPlanStore.clearActiveLiveVisit()
                        }
                    }
                }
                if (PrefsStore.isAutoSwitchAutonomousMode()) {
                    releaseInsertionPrepExclusiveIfIdle("主播访问结束")
                }
                ExecutionScope.exit()
                liveVisitSwitchJob = null
                if (PrefsStore.isAutoSwitchAutonomousMode() && finishMode != "requeue") {
                    maybeStartGrowthInAutoSwitchIdle("主播访问结束")
                }
            }
        }
    }

    /** 因其它独占任务暂缓主播访问：回退 PENDING，避免 RUNNING 永久堵涨粉。 */
    private fun requeueDeferredLiveVisit() {
        val sk = AutoSwitchLocalPlanStore.activeLiveVisitSlotKey()?.trim().orEmpty()
            .ifBlank {
                AutoSwitchLocalPlanStore.get()?.slots
                    ?.firstOrNull { it.live_visit.state == PlanStepState.RUNNING }
                    ?.slot_key?.trim().orEmpty()
            }
        if (sk.isBlank()) {
            AutoSwitchLocalPlanStore.clearActiveLiveVisit()
            return
        }
        autoSwitchLocalScheduler.requeueLiveVisitInsertion(sk)
    }

    private fun triggerMutualSwitchNow(alias: String) {
        if (mutualSwitchJob?.isActive == true) {
            LogCenter.op(alias, "互点切换任务已在执行中，忽略重复触发")
            return
        }
        if (isPublishPostSwitchActive()) {
            LogCenter.op(alias, "互点切换暂缓：发作品仍在执行，待发作品结束后再触发")
            return
        }
        mutualSwitchJob = scope.launch {
            if (ManualDeviceControl.ENABLED) {
                pullLoopJob?.cancel()
                pullLoopJob = null
                pendingUsernames.clear()
            }
            ExecutionScope.enter()
            var switchResult = ""
            try {
                val beforeRequested = PrefsStore.isMutualClickRequested()
                val beforePending = PrefsStore.isMutualClickPending()
                val beforeHardStop = PrefsStore.isHardStopRequested()
                val beforeStatus = PrefsStore.getLocalStatus()
                LogCenter.op(
                    alias,
                    "互点切换任务启动：requested=$beforeRequested pending=$beforePending hardStop=$beforeHardStop localStatus=$beforeStatus",
                )
                // 先打断当前用户名执行，避免与互点切换流程并发操作 TikTok
                pullLoopJob?.cancel()
                pullLoopJob = null
                delay(120)
                LogCenter.op(
                    alias,
                    "互点切换任务准备执行特殊用户名：requested=${PrefsStore.isMutualClickRequested()} pending=${PrefsStore.isMutualClickPending()} hardStop=${PrefsStore.isHardStopRequested()} localStatus=${PrefsStore.getLocalStatus()}",
                )
                val deviceId = PrefsStore.getDeviceId()
                val autoSwitchMutualSk = AutoSwitchLocalPlanStore.activeMutualSlotKey()?.trim().orEmpty()
                    .ifBlank { PrefsStore.getAutoSwitchMutualSlotKey()?.trim().orEmpty() }
                if (!deviceId.isNullOrBlank() && autoSwitchMutualSk.isBlank()) {
                    fetchConfig(configQueryRunMode = ServerRunMode.MUTUAL)
                }
                val deviceIdForMutual = deviceId.orEmpty()
                if (deviceIdForMutual.isNotBlank() && !ensureLoggedInBeforeExclusiveTask(alias, deviceIdForMutual, "互点切换")) {
                    LogCenter.op(alias, "互点切换：is_login 未就绪，已跳过互点入口流程")
                    switchResult = "互点：is_login 未就绪"
                    return@launch
                }
                var switchResultLocal = runCatching {
                    executor.executeForUsername("__mutual_switch__", _config.value)
                }.getOrElse { err ->
                    when (err) {
                        is LoginSafeguardRequiresAutoLoginException -> SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE
                        is AccountPlanBlockedException -> {
                            if (deviceIdForMutual.isNotBlank()) {
                                handleAccountPlanBlocked(alias, deviceIdForMutual, err, "互点切换")
                            }
                            "互点流程" + (if (err.blockKind.equals("max_login", true)) "登录次数上限" else "账号封禁") + "：${err.message ?: err.blockKind}"
                        }
                        is AccountStatusLoggedOutException -> {
                            if (deviceIdForMutual.isNotBlank()) {
                                recoverFromAccountStatusLogout(alias, deviceIdForMutual, "互点切换")
                            }
                            "互点流程掉登录：${err.message ?: "account_status"}"
                        }
                        else -> "互点流程切换异常：${err.message ?: "unknown"}"
                    }
                }
                if (switchResultLocal == SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE && deviceIdForMutual.isNotBlank()) {
                    // OCR 已释放单飞；自动登录 + 重试互点必须再占单飞，避免与涨粉保障抢界面
                    switchResultLocal = LoginSafeguardGate.withExclusive("互点保障") {
                        val loginReady = ensureAccountLoggedInWithRetry(
                            alias = alias,
                            deviceId = deviceIdForMutual,
                            reason = "互点保障",
                            forceRelogin = true,
                            resumeFromLoginEntryScreen = true,
                        ) == AccountLoginRetryOutcome.LoggedIn
                        if (loginReady) {
                            runCatching {
                                executor.executeForUsername("__mutual_switch__", _config.value)
                            }.getOrElse { err ->
                                when (err) {
                                    is AccountPlanBlockedException -> {
                                        handleAccountPlanBlocked(alias, deviceIdForMutual, err, "互点切换")
                                        "互点流程" + (if (err.blockKind.equals("max_login", true)) "登录次数上限" else "账号封禁") + "：${err.message ?: err.blockKind}"
                                    }
                                    is AccountStatusLoggedOutException -> {
                                        recoverFromAccountStatusLogout(alias, deviceIdForMutual, "互点切换")
                                        "互点流程掉登录：${err.message ?: "account_status"}"
                                    }
                                    else -> "互点流程切换异常：${err.message ?: "unknown"}"
                                }
                            }
                        } else {
                            "互点：登录保障后仍未登录"
                        }
                    }
                }
                switchResult = switchResultLocal
                if (!deviceId.isNullOrBlank()) {
                    fetchConfig()
                }
                LogCenter.op(alias, switchResult)
                updateRuntime(lastMessage = switchResult)
                LogCenter.op(
                    alias,
                    "互点切换任务结束：requested=${PrefsStore.isMutualClickRequested()} pending=${PrefsStore.isMutualClickPending()} hardStop=${PrefsStore.isHardStopRequested()} localStatus=${PrefsStore.getLocalStatus()}",
                )
            } finally {
                if (PrefsStore.isAutoSwitchAutonomousMode()) {
                    autoSwitchLocalScheduler.finalizeMutualTrafficOutcome(switchResult)
                }
                PrefsStore.clearMutualClickState()
                val wasQueued = PrefsStore.isPublishPostQueuedAfterMutual()
                PrefsStore.setPublishPostQueuedAfterMutual(false)
                if (wasQueued && PrefsStore.isPublishPostRequested() && PrefsStore.isPublishPostPending()) {
                    LogCenter.op(alias, "互点会话已结束，开始执行此前排队的发作品入口任务")
                    triggerPublishPostNow(alias)
                }
                val deviceIdForRetry = PrefsStore.getDeviceId().orEmpty()
                if (deviceIdForRetry.isNotBlank()) {
                    maybeRetryPendingBasicProfileEditAfterExclusive(
                        alias = alias,
                        deviceId = deviceIdForRetry,
                        trigger = "after_mutual_exclusive",
                    )
                }
                if (PrefsStore.isAutoSwitchAutonomousMode()) {
                    releaseInsertionPrepExclusiveIfIdle("互点切换结束")
                }
                ExecutionScope.exit()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (PrefsStore.isHardStopRequested() || manuallyPaused) return@invokeOnCompletion
                if (PrefsStore.isAutoSwitchAutonomousMode()) {
                    if (PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
                        LogCenter.op(alias, "【自动切号】互点切换结束：登录保障恢复中，不恢复涨粉")
                        return@invokeOnCompletion
                    }
                    // 互点启动时 pauseGrowth 会把 localStatus 置 paused；由 maybeStart 内 release 抢占再恢复
                    maybeStartGrowthInAutoSwitchIdle("互点切换结束")
                } else if (ManualDeviceControl.ENABLED) {
                    maybeManualAutoGrowthAfterTask("引流互点完成")
                } else if (PrefsStore.getLocalStatus() == "running") {
                    if (queueRunMode() == ServerRunMode.MUTUAL) {
                        LogCenter.op(alias, "互点引流会话结束，云端队列模式为互点，将继续下一轮")
                    } else {
                        LogCenter.op(alias, "互点引流会话结束")
                    }
                    startPullLoop()
                }
            }
        }
    }

    /**
     * 云端「发布作品」下发的最终回执（非自动切号）。
     * 网页端轮询 op_result，据此汇总各设备成败。
     */
    private suspend fun reportCloudPublishFinalOutcome(
        alias: String,
        deviceId: String,
        opId: String,
        completed: Boolean,
        loginFailed: Boolean,
        message: String,
        successCount: Int,
        targetCount: Int,
    ) {
        if (opId.isBlank()) return
        if (PrefsStore.isAutoSwitchAutonomousMode()) return
        val status = if (completed) "success" else "failed"
        val summary =
            if (completed) {
                "发作品最终结果：成功（$successCount/$targetCount）$message"
            } else {
                "发作品最终结果：失败（已成功 $successCount/$targetCount）$message"
            }
        runCatching {
            withContext(Dispatchers.IO) {
                reportDeviceOpToCloud(
                    deviceId = deviceId.ifBlank { PrefsStore.getDeviceId().orEmpty() },
                    alias = alias,
                    opId = opId,
                    status = status,
                    message = summary,
                    data = mapOf(
                        "op_type" to "publish_post_start",
                        "device_id" to deviceId,
                        "publish_post_outcome_report" to true,
                        "publish_post_completed" to completed,
                        "publish_success_count" to successCount.coerceAtLeast(0),
                        "publish_target_count" to targetCount.coerceAtLeast(0),
                        "publish_login_failed" to loginFailed,
                        "operation_logs" to listOf("[OP][$opId] $summary"),
                    ),
                )
            }
        }.onFailure { err ->
            LogCenter.op(alias, "发作品最终结果上报失败：${err.message}")
        }
    }

    private suspend fun reportAutoSwitchPublishOutcomeIfNeeded(
        alias: String,
        deviceId: String,
        opId: String,
        completed: Boolean,
        loginFailed: Boolean,
        accountChanged: Boolean,
        message: String,
        slotKey: String = AutoSwitchLocalPlanStore.activePublishSlotKey().orEmpty(),
    ) {
        val slotKeyResolved = slotKey.trim().ifBlank {
            AutoSwitchLocalPlanStore.activePublishSlotKey().orEmpty()
        }
        val account = AutoSwitchLocalPlanStore.activePublishAccountUsername().orEmpty()
        val accountKey = account.trim().ifBlank {
            PrefsStore.getLogContextUsername()?.trim().orEmpty()
        }.ifBlank { alias }
        val resumeIdx = PrefsStore.getPublishPostResumeNextIndex(accountKey)
        val resumable = !completed && !loginFailed && !accountChanged && resumeIdx > 0
        val successCount = when {
            completed -> {
                val actual = PrefsStore.getAndClearPublishPostLastActualSuccessCount()
                if (actual > 0) actual else resumeIdx.coerceAtLeast(0)
            }
            resumable -> resumeIdx
            else -> 0
        }
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            if (slotKeyResolved.isBlank() || opId.isBlank()) return
            AutoSwitchLedger.recordPublishOutcome(
                opId = opId,
                completed = completed,
                loginFailed = loginFailed,
                accountChanged = accountChanged,
                message = message,
                slotKey = slotKeyResolved,
                username = account,
                successCount = successCount,
                resumable = resumable,
            )
            autoSwitchLocalScheduler.notifyPublishOutcome(
                slotKey = slotKeyResolved,
                completed = completed,
                loginFailed = loginFailed,
                accountChanged = accountChanged,
            )
            releaseAutoSwitchPublishLocalState(
                alias = alias,
                completed = completed,
                loginFailed = loginFailed,
                accountChanged = accountChanged,
            )
            return
        }
        if (!PrefsStore.isAutoSwitchPublishTask()) return
        if (slotKeyResolved.isBlank() || opId.isBlank()) return
        val accountCloud = account.ifBlank { AutoSwitchLocalPlanStore.activePublishAccountUsername().orEmpty() }
        val status = if (completed) "success" else "failed"
        runCatching {
            withContext(Dispatchers.IO) {
                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = status,
                    message = message,
                    data = mapOf(
                        "op_type" to "publish_post_start",
                        "device_id" to deviceId,
                        "source" to "auto_switch",
                        "auto_switch_slot_key" to slotKeyResolved,
                        "account_username" to accountCloud,
                        "publish_post_outcome_report" to true,
                        "publish_post_completed" to completed,
                        "auto_switch_publish_login_failed" to loginFailed,
                        "auto_switch_publish_account_changed" to accountChanged,
                    ),
                )
            }
        }.onFailure { err ->
            LogCenter.op(alias, "自动切号发作品结果上报失败：${err.message}")
        }
        if (completed || loginFailed || accountChanged) {
            PrefsStore.clearPublishPostState()
        }
    }

    /**
     * 自治发作品结束/失败后释放本地 pending 与 auto_switch 上下文。
     * 断点续发（已成功至少 1 条）时保留 pending，供同 OP 续作。
     */
    private fun releaseAutoSwitchPublishLocalState(
        alias: String,
        completed: Boolean,
        loginFailed: Boolean,
        accountChanged: Boolean,
    ) {
        val accountKey = AutoSwitchLocalPlanStore.activePublishAccountUsername()
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() }
            ?: alias
        val keepForResume = !completed && !loginFailed && !accountChanged &&
            PrefsStore.getPublishPostResumeNextIndex(accountKey) > 0
        if (keepForResume) {
            AutoSwitchLocalPlanStore.setActivePublishPendingFlags(requested = true, pending = true)
        } else {
            PrefsStore.clearPublishPostState()
        }
    }

    private fun triggerPublishPostNow(alias: String) {
        if (publishPostSwitchJob?.isActive == true) {
            LogCenter.op(alias, "发作品切换任务已在执行中，忽略重复触发")
            return
        }
        clearMutualClickStateForInterrupt("发作品入口")
        publishPostSwitchJob = scope.launch {
            if (ManualDeviceControl.ENABLED) {
                // 手动发作品：素材准备阶段也必须立刻停涨粉（按钮抢占已做一遍，这里再兜底）
                pullLoopJob?.cancel()
                pullLoopJob = null
                pendingUsernames.clear()
            } else if (PrefsStore.isAutoSwitchAutonomousMode()) {
                pauseGrowthForAutoSwitchExclusiveOp()
            }
            ExecutionScope.enter()
            try {
            val beforeRequested = PrefsStore.isPublishPostRequested()
            val beforePending = PrefsStore.isPublishPostPending()
            LogCenter.op(
                alias,
                "发作品切换任务启动：requested=$beforeRequested pending=$beforePending hardStop=${PrefsStore.isHardStopRequested()} localStatus=${PrefsStore.getLocalStatus()}",
            )
            LogCenter.op(
                alias,
                if (ManualDeviceControl.ENABLED) {
                    "发作品切换任务准备执行特殊用户名 __publish_post__（手动模式：已抢占/暂停涨粉）：requested=${PrefsStore.isPublishPostRequested()} pending=${PrefsStore.isPublishPostPending()}"
                } else {
                    "发作品切换任务准备执行特殊用户名 __publish_post__（素材准备阶段不暂停涨粉）：requested=${PrefsStore.isPublishPostRequested()} pending=${PrefsStore.isPublishPostPending()}"
                },
            )
            val deviceIdForPublish = PrefsStore.getDeviceId().orEmpty()
            val publishOpId = PrefsStore.getPublishPostTaskOpId().orEmpty()
            val publishSlotKeyAtStart = AutoSwitchLocalPlanStore.activePublishSlotKey()?.trim().orEmpty()
            val autoSwitchPublishAtStart = AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()
            if (deviceIdForPublish.isBlank()) {
                LogCenter.op(alias, "发作品切换任务：未绑定 deviceId，已跳过")
                if (ManualDeviceControl.ENABLED) {
                    recordManualPublishOutcome(
                        success = false,
                        message = "未绑定 deviceId，发作品入口未执行",
                        targetCount = PrefsStore.getPublishPostTargetCount() ?: 0,
                        successCount = 0,
                    )
                }
                if (PrefsStore.isAutoSwitchPublishTask() && publishOpId.isNotBlank()) {
                    reportAutoSwitchPublishOutcomeIfNeeded(
                        alias = alias,
                        deviceId = deviceIdForPublish,
                        opId = publishOpId,
                        completed = false,
                        loginFailed = false,
                        accountChanged = false,
                        message = "未绑定 deviceId，发作品入口未执行",
                    )
                } else if (publishOpId.isNotBlank()) {
                    reportCloudPublishFinalOutcome(
                        alias = alias,
                        deviceId = PrefsStore.getDeviceId().orEmpty(),
                        opId = publishOpId,
                        completed = false,
                        loginFailed = false,
                        message = "未绑定 deviceId，发作品入口未执行",
                        successCount = 0,
                        targetCount = PrefsStore.getPublishPostTargetCount() ?: 0,
                    )
                }
                return@launch
            }
            var switchResult = runCatching {
                executor.executeForUsername("__publish_post__", _config.value)
            }.getOrElse { err ->
                when (err) {
                    is LoginSafeguardRequiresAutoLoginException -> SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE
                    is AccountPlanBlockedException -> {
                        if (deviceIdForPublish.isNotBlank()) {
                            handleAccountPlanBlocked(alias, deviceIdForPublish, err, "发作品")
                        }
                        "发作品" + (if (err.blockKind.equals("max_login", true)) "登录次数上限" else "账号封禁") + "：${err.message ?: err.blockKind}"
                    }
                    is AccountStatusLoggedOutException -> {
                        if (deviceIdForPublish.isNotBlank()) {
                            recoverFromAccountStatusLogout(alias, deviceIdForPublish, "发作品")
                        }
                        "发作品掉登录：${err.message ?: "account_status"}"
                    }
                    else -> "发作品入口流程异常：${err.message ?: "unknown"}"
                }
            }
            if (switchResult == SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE && deviceIdForPublish.isNotBlank()) {
                val expectedUser = AutoSwitchLocalPlanStore.activePublishAccountUsername()
                // OCR 已释放单飞；自动登录 + 重试发作品必须再占单飞，避免与涨粉保障抢界面
                var publishLoginReady = false
                switchResult = LoginSafeguardGate.withExclusive("发作品保障") {
                    publishLoginReady = ensureAccountLoggedInWithRetry(
                        alias = alias,
                        deviceId = deviceIdForPublish,
                        reason = "发作品保障",
                        expectedUsername = expectedUser,
                        forceRelogin = true,
                        resumeFromLoginEntryScreen = true,
                    ) == AccountLoginRetryOutcome.LoggedIn
                    if (publishLoginReady) {
                        PrefsStore.setPublishPostSkipNextColdLaunchSafeguard(true)
                        runCatching {
                            executor.executeForUsername("__publish_post__", _config.value)
                        }.getOrElse { err ->
                            when (err) {
                                is LoginSafeguardRequiresAutoLoginException -> SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE
                                is AccountPlanBlockedException -> {
                                    if (deviceIdForPublish.isNotBlank()) {
                                        handleAccountPlanBlocked(alias, deviceIdForPublish, err, "发作品")
                                    }
                                    "发作品" + (if (err.blockKind.equals("max_login", true)) "登录次数上限" else "账号封禁") + "：${err.message ?: err.blockKind}"
                                }
                                is AccountStatusLoggedOutException -> {
                                    if (deviceIdForPublish.isNotBlank()) {
                                        recoverFromAccountStatusLogout(alias, deviceIdForPublish, "发作品")
                                    }
                                    "发作品掉登录：${err.message ?: "account_status"}"
                                }
                                else -> "发作品入口流程异常：${err.message ?: "unknown"}"
                            }
                        }
                    } else {
                        "发作品：登录保障后仍未登录"
                    }
                }
                if (!publishLoginReady) {
                    activateAutoSwitchTikTokLoginHold(
                        publishSlotKeyAtStart,
                        "发作品保障自动登录失败",
                    )
                    if (publishOpId.isNotBlank()) {
                        if (PrefsStore.isAutoSwitchPublishTask()) {
                            reportAutoSwitchPublishOutcomeIfNeeded(
                                alias = alias,
                                deviceId = deviceIdForPublish,
                                opId = publishOpId,
                                completed = false,
                                loginFailed = true,
                                accountChanged = false,
                                message = switchResult,
                            )
                        } else {
                            reportCloudPublishFinalOutcome(
                                alias = alias,
                                deviceId = deviceIdForPublish,
                                opId = publishOpId,
                                completed = false,
                                loginFailed = true,
                                message = switchResult,
                                successCount = 0,
                                targetCount = PrefsStore.getPublishPostTargetCount() ?: 0,
                            )
                        }
                    }
                    if (ManualDeviceControl.ENABLED) {
                        recordManualPublishOutcome(
                            success = false,
                            message = switchResult,
                            targetCount = PrefsStore.getPublishPostTargetCount() ?: 0,
                            successCount = 0,
                        )
                    }
                    LogCenter.op(alias, switchResult)
                    updateRuntime(lastMessage = switchResult)
                    return@launch
                }
            }
            val publishLoginFailed = isPublishLoginFailure(switchResult)
            if (publishLoginFailed) {
                activateAutoSwitchTikTokLoginHold(
                    publishSlotKeyAtStart,
                    switchResult.take(120),
                )
            }
            val publishCompleted = switchResult.contains("已完成") && !publishLoginFailed
            val accountKeyForCount =
                PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: ManualDeviceControl.selectedManualUsername().takeIf { it.isNotBlank() }
                    ?: alias
            val targetCount = PrefsStore.getPublishPostTargetCount() ?: 0
            val successCount = when {
                publishCompleted -> {
                    val actual = PrefsStore.peekPublishPostLastActualSuccessCount()
                    if (actual > 0) actual else targetCount
                }
                else -> PrefsStore.getPublishPostResumeNextIndex(accountKeyForCount).coerceAtLeast(0)
            }
            if (ManualDeviceControl.ENABLED) {
                recordManualPublishOutcome(
                    success = publishCompleted,
                    message = switchResult,
                    targetCount = targetCount,
                    successCount = successCount,
                )
            }
            if (autoSwitchPublishAtStart && publishOpId.isNotBlank()) {
                reportAutoSwitchPublishOutcomeIfNeeded(
                    alias = alias,
                    deviceId = deviceIdForPublish,
                    opId = publishOpId,
                    slotKey = publishSlotKeyAtStart,
                    completed = publishCompleted,
                    loginFailed = publishLoginFailed,
                    accountChanged = false,
                    message = switchResult,
                )
            } else if (PrefsStore.isAutoSwitchPublishTask() && publishOpId.isNotBlank()) {
                reportAutoSwitchPublishOutcomeIfNeeded(
                    alias = alias,
                    deviceId = deviceIdForPublish,
                    opId = publishOpId,
                    completed = publishCompleted,
                    loginFailed = publishLoginFailed,
                    accountChanged = false,
                    message = switchResult,
                )
            } else if (publishOpId.isNotBlank() && deviceIdForPublish.isNotBlank()) {
                reportCloudPublishFinalOutcome(
                    alias = alias,
                    deviceId = deviceIdForPublish,
                    opId = publishOpId,
                    completed = publishCompleted,
                    loginFailed = publishLoginFailed,
                    message = switchResult,
                    successCount = successCount,
                    targetCount = targetCount,
                )
            }
            LogCenter.op(alias, switchResult)
            updateRuntime(lastMessage = switchResult)
            if (
                switchResult.contains("异常") ||
                switchResult.contains("失败") ||
                switchResult.contains("掉登录")
            ) {
                scheduleFailureReport(
                    business = "publish_post",
                    errorType = FailureReportUploader.normalizeErrorType(switchResult),
                    summary = switchResult,
                )
            }
            LogCenter.op(
                alias,
                "发作品切换任务结束：hardStop=${PrefsStore.isHardStopRequested()} localStatus=${PrefsStore.getLocalStatus()}",
            )
            } finally {
                ExecutionScope.exit()
                PublishPostGrowthGate.exitExclusivePublishPhase()
                // 手动发作品结束：无论成败都清独占，防止涨粉被 preempt 永久挡住
                if (ManualDeviceControl.ENABLED) {
                    PrefsStore.setAutoSwitchExclusivePreempt(false)
                    PrefsStore.setTransientPausedForDestructiveOp(false)
                }
                cleanupOrphanedAutoSwitchPublishState(alias)
                if (ManualDeviceControl.ENABLED && !PrefsStore.isAutoSwitchPublishTask()) {
                    val accountKey = PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: ManualDeviceControl.selectedManualUsername().takeIf { it.isNotBlank() }
                        ?: alias
                    val keepResume = PrefsStore.getPublishPostResumeNextIndex(accountKey) > 0
                    if (!keepResume) {
                        PrefsStore.clearPublishPostState()
                    }
                }
                val did = PrefsStore.getDeviceId()
                val devAlias = PrefsStore.getAlias()
                if (!did.isNullOrBlank() && !devAlias.isNullOrBlank()) {
                    restoreCloudAutoStartParticipation(devAlias, did, "publish", "发作品任务结束")
                    maybeRetryPendingBasicProfileEditAfterExclusive(
                        alias = devAlias,
                        deviceId = did,
                        trigger = "after_publish_exclusive",
                    )
                }
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    cleanupOrphanedAutoSwitchPublishState(alias)
                }
                if (!PrefsStore.isHardStopRequested() && !manuallyPaused) {
                    if (PrefsStore.isAutoSwitchAutonomousMode()) {
                        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
                            LogCenter.op(alias, "【自动切号】发作品切换结束：登录保障恢复中，不恢复涨粉")
                            return@invokeOnCompletion
                        }
                        val resumeAccount = AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim()
                            ?: PrefsStore.getLogContextUsername()?.trim()
                            ?: alias
                        if (
                            PrefsStore.isPublishPostPending() &&
                            PrefsStore.getPublishPostResumeNextIndex(resumeAccount) > 0 &&
                            !isPublishPostSwitchActive()
                        ) {
                            LogCenter.op(
                                alias,
                                "【自动切号】发作品断点续发：已成功 ${PrefsStore.getPublishPostResumeNextIndex(resumeAccount)} 条，继续同 OP",
                            )
                            triggerPublishPostNow(alias)
                        } else {
                            // 发作品启动时 pauseGrowth 会把 localStatus 置 paused；由 maybeStart 内 release 抢占再恢复
                            maybeStartGrowthInAutoSwitchIdle("发作品切换结束")
                        }
                    } else if (ManualDeviceControl.ENABLED) {
                        maybeManualAutoGrowthAfterTask("发作品完成")
                    } else if (PrefsStore.getLocalStatus() == "running") {
                        LogCenter.op(alias, "发作品切换结束后恢复拉取循环")
                        startPullLoop()
                    }
                }
            }
        }
    }

    /** 发作品协程异常/取消后，防止 pending 或 plan 残留阻塞自治调度。 */
    private fun cleanupOrphanedAutoSwitchPublishState(alias: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (isPublishPostSwitchActive()) return
        val slotKey = AutoSwitchLocalPlanStore.activePublishSlotKey().orEmpty()
        val sk = slotKey.ifBlank { PrefsStore.getAutoSwitchLastSlotKey().orEmpty() }
        val planInflight = sk.isNotBlank() && AutoSwitchLocalPlanStore.isPublishInflight(sk)
        val hasLocalPublishState = PrefsStore.isAutoSwitchPublishTask() || PrefsStore.isPublishPostPending()
        if (!planInflight && !hasLocalPublishState) return
        val accountKey = AutoSwitchLocalPlanStore.activePublishAccountUsername()
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() }
            ?: alias
        if (PrefsStore.getPublishPostResumeNextIndex(accountKey) > 0) return
        if (PrefsStore.isPublishPostMaterialReady() && tryResumeStaleAutoSwitchPublish(sk)) return
        if (planInflight && sk.isNotBlank()) {
            resetStalePublishInflightForRetry(sk, alias)
            return
        }
        if (hasLocalPublishState) {
            LogCenter.op(alias, "【自动切号】清理残留发作品本地状态（协程已结束）")
            PrefsStore.clearPublishPostState()
            if (sk.isNotBlank()) {
                autoSwitchLocalScheduler.notifyPublishOutcome(
                    slotKey = sk,
                    completed = false,
                    loginFailed = false,
                    accountChanged = false,
                )
            }
        }
    }

    /**
     * plan 为 RUNNING 但无有效 active_publish：重置为 PENDING 以便 tick/新 OP 重试（非终态 FAILED）。
     */
    private fun resetStalePublishInflightForRetry(slotKey: String, alias: String) {
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive(sk)) {
            LogCenter.op(alias, "【自动切号】发作品 stale 重置跳过：登录保障恢复中 slot=$sk")
            return
        }
        if (!AutoSwitchLocalPlanStore.isPublishInflight(sk) &&
            AutoSwitchLocalPlanStore.insertionState(sk, "publish") != PlanStepState.FAILED
        ) {
            return
        }
        autoSwitchLocalScheduler.clearPublishInsertionInflight(sk)
        PrefsStore.clearPublishPostState()
        AutoSwitchLocalPlanStore.resetStalePublishInflight(sk)
        LogCenter.op(alias, "【自动切号】发作品 stale in-flight 已重置为 PENDING slot=$sk")
    }

    /**
     * 进程被杀/服务重启后：若素材已就绪或 pending 未清，自动续发而非放弃本时段发作品。
     */
    private fun tryResumeStaleAutoSwitchPublish(slotKey: String): Boolean {
        val sk = slotKey.trim()
        if (sk.isBlank()) return false
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive(sk)) return false
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        if (isPublishPostSwitchActive()) return false
        if (!PrefsStore.hasRecoverableAutoSwitchPublish(sk)) return false
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isBlank()) return false
        if (planCutSwitchBlocksStalePublishResume(sk)) {
            LogCenter.op(
                alias,
                "【自动切号】PLAN 切号优先，放弃残留发作品续发 slot=$sk",
            )
            PrefsStore.clearPublishPostState()
            AutoSwitchLocalPlanStore.clearActivePublish()
            autoSwitchLocalScheduler.clearPublishInsertionInflight(sk)
            AutoSwitchLocalPlanStore.resetStalePublishInflight(sk)
            return false
        }

        autoSwitchLocalScheduler.clearSlotPublishAborted(sk)
        PrefsStore.setPublishPostPending(true)
        PrefsStore.setPublishPostRequested(true)

        LogCenter.op(
            alias,
            "【自动切号】残留发作品可恢复，触发续发：slot=$sk op=${PrefsStore.getPublishPostTaskOpId()} " +
                "materialReady=${PrefsStore.isPublishPostMaterialReady()}",
        )
        triggerPublishPostNow(alias)
        return true
    }

    /** 修改基本资料外轮：Bio 后①② 均完成时可结束外轮（整体成功仍仅看 avatarStepOk）。 */
    private fun profileEditOuterChainFullyComplete(chain: ProfileEditChainResult): Boolean =
        chain.bioAfterPart1Ok && chain.bioAfterPart2Ok

    /** 外轮全部结束后汇总：仅头像步骤成功即认定改资料成功。 */
    private fun profileEditOverallSuccessFromOuterRounds(avatarSucceededEver: Boolean): Boolean =
        avatarSucceededEver

    /**
     * 改资料设备回传：携带开始时用户名、row_index，以及 Bio 步骤可能同步后的新用户名，避免写回错位。
     */
    private fun MutableMap<String, Any>.enrichProfileEditReportFields(
        usernameAtStart: String,
        accountRowIndex: Int?,
        basicProfileModifiedBefore: String,
        chain: ProfileEditChainResult? = null,
        currentAssignedUsername: String? = null,
    ): MutableMap<String, Any> {
        val start = usernameAtStart.trim()
        val synced = chain?.profileEditUsernameSyncedTo?.trim().orEmpty()
        val current = currentAssignedUsername?.trim().orEmpty()
        // Bio 本轮同步 > 当前分配账号（应对外轮重试：上一轮已改名但本轮未再同步）> 开始时用户名
        val effective = synced.ifBlank { current.ifBlank { start } }
        if (effective.isNotBlank()) {
            put("username", effective)
            put("account_username", effective)
        }
        if (start.isNotBlank()) {
            put("profile_edit_assigned_username", start)
        }
        if (start.isNotBlank() && synced.isNotBlank() && !start.equals(synced, ignoreCase = true)) {
            put("old_username", start)
            put("new_username", synced)
        }
        accountRowIndex?.takeIf { it >= 0 }?.let { put("account_row_index", it) }
        put("basic_profile_modified_before", basicProfileModifiedBefore.trim().ifBlank { "否" })
        return this
    }

    /**
     * 切号后云端「基本资料已修改」=否 时衔接改资料（与远控一致，最多 3 轮外轮重试）。
     * @param skipOuterWarmup true：第 1 轮假定仍在个人页，跳过强停/重拉/比例点，直接 OCR 链；
     *   第 2 轮起与 false 相同，走完整外轮 warmup。false：每轮均走外轮进入 Profile。
     */
    private suspend fun runProfileEditAfterCutSwitch(
        alias: String,
        deviceId: String,
        parentOpId: String,
        skipOuterWarmup: Boolean,
        profileEditTrigger: String,
        skipAccountDbUpdate: Boolean = false,
    ) {
        // android-simple：改资料只允许人点「修改基本资料」按钮（local-manual OP），禁止一切自动衔接。
        if (ManualDeviceControl.ENABLED) {
            LogCenter.op(
                alias,
                "[OP][$parentOpId] 手动模式：跳过自动改资料（trigger=$profileEditTrigger），仅按钮可触发",
            )
            return
        }
        val profileOpId = "${parentOpId}_profile_${System.currentTimeMillis()}"
        val profileAccount = resolveAssignedAccount(alias, deviceId)
        val profileUsernameAtStart = profileAccount?.username?.trim().orEmpty()
            .ifBlank { PrefsStore.getLogContextUsername()?.trim().orEmpty() }
        val profileAccountRowIndex = profileAccount?.row_index?.takeIf { it >= 0 }
        val basicProfileModifiedBefore = profileAccount?.basic_profile_modified?.trim().orEmpty().ifBlank { "否" }
        val packageName = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
        val operationLogs = mutableListOf<String>()
        fun opLog(text: String) {
            val line = "[OP][$profileOpId] $text"
            operationLogs.add(line)
            LogCenter.op(alias, line)
        }

        opLog(
            if (skipOuterWarmup) {
                "切号后衔接修改基本资料（最多 3 轮外轮；第 1 轮跳过 warmup 直接 OCR，失败则完整外轮重试）"
            } else {
                "切号后衔接修改基本资料（最多 3 轮外轮，每轮强停/重拉/比例点进 Profile）"
            },
        )
        val wasRunning = prepareForExclusiveTikTokUiOp(
            alias = alias,
            deviceId = deviceId,
            opType = "tiktok_edit_basic_profile",
            opId = profileOpId,
            packageName = packageName,
            settleMs = 0L,
            forceStopTikTok = false,
        )
        // 登录已在上游完成校验，这里不要重新把涨粉登录保障挂回待执行，避免改资料收尾后误触发重登。
        growthModeLoginSafeguardPending = false
        if (profileUsernameAtStart.isNotBlank()) {
            AutoSwitchLedger.recordProfileEditStarted(
                profileOpId,
                profileUsernameAtStart,
                profileEditTrigger,
                basicProfileModifiedBefore,
            )
        }

        var profileEditAvatarFilename = ""
        var profileEditGalleryClearedBefore = false
        var profileEditAvatarImported = false
        var profileEditSucceeded = false
        var profileEditFailureStep = ""
        try {
            val cfgOk = fetchConfig()
            if (!cfgOk) opLog("配置拉取失败，沿用上一版")

            opLog("修改基本资料：清理设备图库（执行前）")
            executor.clearDevicePicturesGallery(alias)
            profileEditGalleryClearedBefore = true
            if (!delayUnlessHardStop(800)) {
                opLog("硬停中止，未执行改资料")
                profileEditFailureStep = "hard_stop"
                return
            }

            opLog("修改基本资料：从云端图库随机拉取头像")
            val avatarItem = resolveProfileEditAvatarMediaItem(deviceId, alias, null, ::opLog)
            if (avatarItem == null) {
                opLog("管理台图库为空或拉取失败，跳过切号后改资料")
                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = profileOpId,
                    status = "failed",
                    message = "切号后改资料：图库素材不可用",
                    data = mutableMapOf(
                        "op_type" to "tiktok_edit_basic_profile",
                        "profile_edit_trigger" to profileEditTrigger,
                        "profile_edit_skipped" to true,
                        "profile_edit_skip_reason" to "cloud_gallery_empty",
                        "profile_edit_success" to false,
                        "operation_logs" to operationLogs,
                    ).enrichProfileEditReportFields(
                        profileUsernameAtStart,
                        profileAccountRowIndex,
                        basicProfileModifiedBefore,
                    ),
                )
                return
            }
            profileEditAvatarFilename = avatarItem.filename?.trim().orEmpty()
            profileEditAvatarImported = importGalleryMediaItemToDevice(
                avatarItem,
                picturesRelativePath = "${Environment.DIRECTORY_PICTURES}/",
                log = ::opLog,
            )
            if (!profileEditAvatarImported) {
                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = profileOpId,
                    status = "failed",
                    message = "切号后改资料：头像写入设备图库失败",
                    data = mutableMapOf(
                        "op_type" to "tiktok_edit_basic_profile",
                        "profile_edit_trigger" to profileEditTrigger,
                        "profile_edit_avatar_filename" to profileEditAvatarFilename,
                        "profile_edit_success" to false,
                        "operation_logs" to operationLogs,
                    ).enrichProfileEditReportFields(
                        profileUsernameAtStart,
                        profileAccountRowIndex,
                        basicProfileModifiedBefore,
                    ),
                )
                return
            }
            if (!delayUnlessHardStop(1200)) {
                opLog("硬停中止，未执行改资料")
                profileEditFailureStep = "hard_stop"
                return
            }

            val galleryImageCount = executor.countPicturesGalleryImageFiles()
            if (galleryImageCount < 1) {
                opLog("头像写入后图库仍无可用图片，跳过切号后改资料")
                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = profileOpId,
                    status = "failed",
                    message = "切号后改资料：图库无可用图片",
                    data = mutableMapOf(
                        "op_type" to "tiktok_edit_basic_profile",
                        "profile_edit_trigger" to profileEditTrigger,
                        "profile_edit_skipped" to true,
                        "profile_edit_skip_reason" to "gallery_images_empty_after_import",
                        "profile_edit_success" to false,
                        "operation_logs" to operationLogs,
                    ).enrichProfileEditReportFields(
                        profileUsernameAtStart,
                        profileAccountRowIndex,
                        basicProfileModifiedBefore,
                    ),
                )
                return
            }
            opLog("设备图库已有头像（count=$galleryImageCount）")

            val runSu: (String) -> Boolean = ::runExecutorSu
            val dm = appContext.resources.displayMetrics
            var lastW = dm.widthPixels
            var lastH = dm.heightPixels
            var lastChain: ProfileEditChainResult? = null
            var avatarSucceededEver = false
            var successOverall = false
            var stopAfterProfileEditOk = false
            val ratioTapX = 0.97f
            val ratioTapY = 0.95f
            var lastRatioTapPx = Pair(0, 0)
            var lastStopOk = false
            var lastLaunchOk = false
            var lastRatioTapOk = false
            val maxOuter = 3
            var lastEntryMode = ""
            var lastEntryFailureStep = ""

            suspend fun runProfileEditChainOnce(): ProfileEditChainResult =
                try {
                    executor.profileEditRunOcrChain(appContext, _config.value)
                } catch (e: AccountPlanBlockedException) {
                    val kindStep =
                        if (e.blockKind.equals("max_login", true)) "account_max_login" else "account_banned"
                    val kindLabel =
                        if (e.blockKind.equals("max_login", true)) "登录次数上限" else "账号封禁"
                    opLog("$kindLabel ${e.message}")
                    handleAccountPlanBlocked(alias, deviceId, e, "切号后修改基本资料")
                    ProfileEditChainResult(
                        success = false,
                        failureStep = kindStep,
                        editOcrPreviews = listOf(e.message ?: kindStep),
                    )
                } catch (e: AccountStatusLoggedOutException) {
                    opLog("掉登录 ${e.message}")
                    recoverFromAccountStatusLogout(alias, deviceId, "切号后修改基本资料")
                    ProfileEditChainResult(
                        success = false,
                        failureStep = "account_status_logout",
                        editOcrPreviews = listOf(e.message ?: "account_status_logout"),
                    )
                } catch (e: Exception) {
                    opLog("OCR 链异常 ${e.message}")
                    ProfileEditChainResult(
                        success = false,
                        failureStep = "ocr_chain_exception",
                        editOcrPreviews = listOf(e.message ?: "unknown"),
                    )
                }

            for (outer in 1..maxOuter) {
                if (PrefsStore.isHardStopRequested()) {
                    opLog("修改基本资料：检测到硬停，中止外轮")
                    lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                    runSu("am force-stop $packageName")
                    break
                }
                lastStopOk = false
                lastLaunchOk = false
                lastRatioTapOk = false
                val directEntryThisRound =
                    if (outer == 1) {
                        opLog("profile edit after cut switch: outer $outer/$maxOuter entry precheck before warmup")
                        val prep = executor.prepareProfileEditEntryPreferHome(
                            ctx = appContext,
                            alias = alias,
                            reason = "profile_edit_after_cut_switch_outer_$outer",
                        )
                        lastEntryMode = prep.entryMode
                        lastEntryFailureStep = prep.failureStep
                        if (prep.success) {
                            val metrics = appContext.resources.displayMetrics
                            lastW = metrics.widthPixels
                            lastH = metrics.heightPixels
                            prep.profileTapPx?.let { lastRatioTapPx = it }
                            lastLaunchOk = true
                            lastRatioTapOk = prep.profileTapPx != null
                            opLog("profile edit after cut switch: outer $outer/$maxOuter entry precheck succeeded (${prep.entryMode})")
                            true
                        } else {
                            opLog("profile edit after cut switch: outer $outer/$maxOuter entry precheck failed")
                            false
                        }
                    } else {
                        false
                    }
                val skipWarmupThisRound = directEntryThisRound
                if (skipWarmupThisRound) {
                    opLog("修改基本资料：第 $outer/$maxOuter 轮（跳过外轮 warmup，直接 OCR 链）")
                } else {
                    opLog("修改基本资料：第 $outer/$maxOuter 轮")
                    lastStopOk = runSu("am force-stop $packageName")
                    if (!delayUnlessHardStop(1200)) {
                        opLog("修改基本资料：硬停中止")
                        lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                        runSu("am force-stop $packageName")
                        break
                    }

                    lastLaunchOk = executor.launchTikTokWithForegroundVerification(
                        ctx = appContext,
                        alias = alias,
                        tag = "切号后改资料_外轮${outer}",
                        forceStopBeforeLaunch = false,
                        timingConfig = _config.value,
                    )

                    val metrics = appContext.resources.displayMetrics
                    lastW = metrics.widthPixels
                    lastH = metrics.heightPixels
                    val rtx = (lastW * ratioTapX).toInt().coerceIn(1, (lastW - 1).coerceAtLeast(1))
                    val rty = (lastH * ratioTapY).toInt().coerceIn(1, (lastH - 1).coerceAtLeast(1))
                    lastRatioTapPx = rtx to rty
                    lastRatioTapOk = false
                    if (!delayUnlessHardStop(if (lastLaunchOk) 1600 else 1200)) {
                        opLog("修改基本资料：硬停中止")
                        lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                        runSu("am force-stop $packageName")
                        break
                    }
                    if (PrefsStore.isHardStopRequested()) {
                        opLog("修改基本资料：硬停中止")
                        lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                        runSu("am force-stop $packageName")
                        break
                    }
                    if (lastLaunchOk) {
                        opLog("修改基本资料：For You 等待后刷两下视频")
                        val forYouOk = executor.profileEditForYouTwoSwipesAfterLaunch(alias, lastW, lastH)
                        if (!forYouOk) {
                            opLog("修改基本资料：硬停中止")
                            lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                            runSu("am force-stop $packageName")
                            break
                        }
                    }
                    lastRatioTapOk = executor.navBackThenTapProfile(alias, "切号后改资料外轮资料入口", rtx, rty)

                    if (!lastStopOk || !lastLaunchOk || !lastRatioTapOk) {
                        opLog("外轮$outer：准备阶段未就绪，重试")
                        lastChain = ProfileEditChainResult(
                            success = false,
                            failureStep = "warmup_outer_$outer",
                            editOcrPreviews = listOf("stop=$lastStopOk launch=$lastLaunchOk ratioTap=$lastRatioTapOk"),
                        )
                        continue
                    }
                }

                if (profileUsernameAtStart.isNotBlank()) {
                    ProfileEditStepStateStore.resetAbandonedForOuterRetry(profileUsernameAtStart)
                }
                val chain = runProfileEditChainOnce()
                lastChain = chain
                if (chain.avatarStepOk) {
                    avatarSucceededEver = true
                }
                if (chain.failureStep == "hard_stop" || PrefsStore.isHardStopRequested()) {
                    opLog("修改基本资料：OCR 链被硬停打断 step=${chain.failureStep}")
                    runSu("am force-stop $packageName")
                    break
                }
                if (profileEditOuterChainFullyComplete(chain)) {
                    opLog("修改基本资料：Bio 后①② 均完成（外轮 $outer/$maxOuter），结束外轮")
                    stopAfterProfileEditOk = runSu("am force-stop $packageName").also { delay(1200) }
                    break
                }
                if (chain.bioAfterPart1Ok && !chain.bioAfterPart2Ok) {
                    opLog("外轮$outer：Bio ① 完成、② 未完成（${chain.failureStep}）")
                } else {
                    opLog("外轮$outer：失败（${chain.failureStep}）")
                }
            }

            successOverall = profileEditOverallSuccessFromOuterRounds(avatarSucceededEver)
            val chain = lastChain ?: ProfileEditChainResult(success = false, failureStep = "no_chain")
            val previewsJoin = { xs: List<String> -> xs.joinToString("\n---\n").take(6000) }
            val currentAssignedUsername = resolveAssignedAccount(alias, deviceId)?.username?.trim().orEmpty()
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = profileOpId,
                status = if (successOverall) "success" else "failed",
                message = when {
                    successOverall ->
                        "切号后衔接改资料成功（换头像成功），云端将「基本资料已修改」标为「是」"
                    chain.failureStep == "hard_stop" ->
                        "切号后改资料已硬停"
                    chain.bioAfterPart1Ok && !chain.bioAfterPart2Ok ->
                        "切号后改资料未完成：换头像未成功；Bio 后① 已完成但② 未成功（${chain.failureStep}），未标「基本资料已修改」"
                    else ->
                        "切号后改资料未完成：换头像未成功；${chain.failureStep}（已最多外轮 $maxOuter 次）"
                },
                data = mutableMapOf<String, Any>(
                    "op_type" to "tiktok_edit_basic_profile",
                    "profile_edit_trigger" to profileEditTrigger,
                    "device_id" to deviceId,
                    "package_name" to packageName,
                    "screen_width_px" to lastW,
                    "screen_height_px" to lastH,
                    "profile_edit_skip_outer_warmup" to skipOuterWarmup,
                    "profile_edit_outer_max" to maxOuter,
                    "tap_ratio_xy" to listOf(ratioTapX, ratioTapY),
                    "tap_px_xy" to listOf(lastRatioTapPx.first, lastRatioTapPx.second),
                    "stop_ok" to lastStopOk,
                    "launch_ok" to lastLaunchOk,
                    "ratio_tap_ok" to lastRatioTapOk,
                    "profile_edit_entry_mode" to lastEntryMode,
                    "profile_edit_entry_failure_step" to lastEntryFailureStep,
                    "profile_edit_success" to successOverall,
                    "profile_edit_avatar_step_ok" to avatarSucceededEver,
                    "profile_edit_bio_after_part1_ok" to chain.bioAfterPart1Ok,
                    "profile_edit_bio_after_part2_ok" to chain.bioAfterPart2Ok,
                    "profile_edit_failure_step" to chain.failureStep,
                    "profile_edit_edit_ocr_attempts" to chain.editOcrAttempts,
                    "profile_edit_change_ocr_attempts" to chain.changeOcrAttempts,
                    "profile_edit_edit_ocr_previews" to previewsJoin(chain.editOcrPreviews),
                    "profile_edit_change_ocr_previews" to previewsJoin(chain.changeOcrPreviews),
                    "profile_edit_bio_applied" to chain.profileEditBioApplied,
                    "tiktok_stopped_after_profile_edit" to stopAfterProfileEditOk,
                    "profile_edit_avatar_filename" to profileEditAvatarFilename,
                    "profile_edit_gallery_cleared_before" to profileEditGalleryClearedBefore,
                    "profile_edit_avatar_imported" to profileEditAvatarImported,
                    "operation_logs" to operationLogs,
                ).also { map ->
                    if (skipAccountDbUpdate) {
                        map["skip_account_db_update"] = true
                    }
                }.enrichProfileEditReportFields(
                    profileUsernameAtStart,
                    profileAccountRowIndex,
                    basicProfileModifiedBefore,
                    chain,
                    currentAssignedUsername,
                ),
            )
            if (successOverall && profileUsernameAtStart.isNotBlank()) {
                AutoSwitchBundleHolder.notifyBasicProfileModified(profileUsernameAtStart)
                ProfileEditStepStateStore.clear(profileUsernameAtStart)
            }
            if (!successOverall && chain.failureStep != "hard_stop") {
                scheduleFailureReport(
                    business = "profile_edit",
                    errorType = chain.failureStep.ifBlank { "unknown" },
                    summary = "cut_switch:$profileEditTrigger step=${chain.failureStep}",
                    ocrRelated = true,
                )
            }
            profileEditSucceeded = successOverall
            profileEditFailureStep = chain.failureStep
        } finally {
            if (profileEditGalleryClearedBefore || profileEditAvatarImported) {
                opLog("修改基本资料：清理设备图库（流程结束）")
                executor.clearDevicePicturesGallery(alias)
            }
            val resumePullAfter = when {
                profileEditFailureStep == "hard_stop" -> false
                !profileEditSucceeded -> true
                else -> wasRunning
            }
            if (resumePullAfter) {
                growthModeLoginSafeguardPending = false
            }
            finishExclusiveTikTokUiOp(alias, deviceId, profileOpId, resumePullAfter = resumePullAfter)
        }
    }

    private suspend fun resolveProfileEditAvatarMediaItem(
        deviceId: String,
        alias: String,
        payload: DeviceOpPayload?,
        log: (String) -> Unit,
    ): DeviceGalleryMediaPayload? {
        val fromPayload = payload?.avatar_media_item?.takeIf { !it.content_b64.isNullOrBlank() }
        if (fromPayload != null) {
            log("使用远控 payload 附带头像：${fromPayload.filename?.trim().orEmpty().ifBlank { "(无文件名)" }}")
            return fromPayload
        }
        log("payload 无头像，请求管理台图库随机一张：/api/gallery_library/random")
        val remote = runCatching {
            withContext(Dispatchers.IO) {
                ApiService.fetchRandomGalleryLibraryItem(deviceId, alias)
            }
        }.getOrElse { err ->
            log("拉取管理台图库失败：${err.message ?: "unknown"}")
            return null
        }
        if (remote.code != 0) {
            log("拉取管理台图库失败：${remote.msg.ifBlank { "code=${remote.code}" }}")
            return null
        }
        val item = remote.item?.takeIf { !it.content_b64.isNullOrBlank() }
        if (item == null) {
            log("拉取管理台图库失败：响应无有效素材（item/base64 为空）")
        } else {
            log("已从管理台图库随机选中：${item.filename?.trim().orEmpty().ifBlank { "(无文件名)" }}")
        }
        return item
    }

    private suspend fun importGalleryMediaItemToDevice(
        item: DeviceGalleryMediaPayload,
        index: Int = 0,
        picturesRelativePath: String = "${Environment.DIRECTORY_PICTURES}/AutoTraces",
        log: ((String) -> Unit)? = null,
    ): Boolean {
        val fileName = item.filename?.trim().takeUnless { it.isNullOrBlank() } ?: "image_${index + 1}.jpg"
        val mime = item.content_type?.trim().takeUnless { it.isNullOrBlank() } ?: "image/jpeg"
        val b64 = item.content_b64?.trim().orEmpty()
        if (b64.isBlank()) {
            log?.invoke("写入图库失败：$fileName（base64 为空）")
            return false
        }
        val raw = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        if (raw == null || raw.isEmpty()) {
            log?.invoke("写入图库失败：$fileName（base64 无法解码）")
            return false
        }
        return runCatching {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, picturesRelativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out ->
                out.write(raw)
                out.flush()
            } ?: throw IOException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            log?.invoke("已写入图库：$fileName → $picturesRelativePath（${raw.size} bytes）")
            true
        }.onFailure { err ->
            log?.invoke("写入图库失败：$fileName（${err.message ?: "unknown"}）")
        }.getOrDefault(false)
    }

    private suspend fun handleSetPcServiceUrlOp(alias: String, deviceId: String, op: DeviceOp) {
        val opId = op.op_id
        val operationLogs = mutableListOf<String>()
        fun opLog(text: String) {
            val line = "[OP][$opId] $text"
            operationLogs.add(line)
            LogCenter.op(alias, line)
        }

        val url = op.payload?.windows_base_url?.trim()?.trimEnd('/').orEmpty()
        if (url.isBlank()) {
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "failed",
                message = "缺少 windows_base_url",
                data = mapOf(
                    "op_type" to "set_pc_service_url",
                    "device_id" to deviceId,
                    "operation_logs" to listOf("[OP][$opId] 缺少 windows_base_url"),
                ),
            )
            return
        }

        val currentUrl = CompanionApi.windowsBaseUrl(appContext).trim().trimEnd('/')
        if (currentUrl == url) {
            opLog("PC 地址未变化：$url")
            val result = VideoDedSettingsRegistrar.saveAndRegister(
                appContext,
                input = VideoDedSettingsRegistrar.SaveInput(windowsUrl = url),
                logSave = false,
                logRegistration = true,
            )
            val ok = result.error == null
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = if (ok) "success" else "failed",
                message = if (ok) "PC 地址未变化，已重新注册" else (result.error?.message ?: "重新注册失败"),
                data = mapOf(
                    "op_type" to "set_pc_service_url",
                    "device_id" to deviceId,
                    "windows_base_url" to url,
                    "pc_adb_state" to result.pcAdbState,
                    "unchanged" to true,
                    "operation_logs" to operationLogs,
                ),
            )
            return
        }

        opLog("更新 PC 投屏地址：$currentUrl → $url")
        val result = VideoDedSettingsRegistrar.saveAndRegister(
            appContext,
            input = VideoDedSettingsRegistrar.SaveInput(windowsUrl = url),
            logSave = true,
            logRegistration = true,
        )
        val ok = result.error == null
        if (ok) {
            opLog("PC 地址已保存并完成注册（pcAdbState=${result.pcAdbState.ifBlank { "—" }}）")
        } else {
            opLog("PC 地址已保存，但注册失败：${result.error?.message}")
        }
        reportDeviceOpToCloud(
            deviceId = deviceId,
            alias = alias,
            opId = opId,
            status = if (ok) "success" else "failed",
            message = if (ok) "PC 地址已更新并重新注册" else (result.error?.message ?: "注册失败"),
            data = mapOf(
                "op_type" to "set_pc_service_url",
                "device_id" to deviceId,
                "windows_base_url" to url,
                "previous_windows_base_url" to currentUrl,
                "pc_adb_state" to result.pcAdbState,
                "operation_logs" to operationLogs,
            ),
        )
    }

    private suspend fun handleGalleryImportOp(alias: String, deviceId: String, op: DeviceOp) {
        val opId = op.op_id
        val operationLogs = mutableListOf<String>()
        fun opLog(text: String) {
            val line = "[OP][$opId] $text"
            operationLogs.add(line)
            LogCenter.op(alias, line)
        }

        val mediaList = op.payload?.media_items.orEmpty().ifEmpty { op.payload?.image_items.orEmpty() }
        if (mediaList.isEmpty()) {
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "failed",
                message = "未收到素材数据",
                data = mapOf(
                    "op_type" to "gallery_import_media",
                    "device_id" to deviceId,
                    "operation_logs" to listOf("[OP][$opId] 未收到素材数据"),
                ),
            )
            return
        }

        var successCount = 0
        var failedCount = 0
        ExecutionScope.enter()
        try {
        opLog("开始导入图库：共 ${mediaList.size} 个素材")
        mediaList.forEachIndexed { index, item ->
            val fileName = item.filename?.trim().takeUnless { it.isNullOrBlank() } ?: "image_${index + 1}.jpg"
            val ok = importGalleryMediaItemToDevice(item, index) { line ->
                opLog("第 ${index + 1} 张：$line")
            }
            if (ok) {
                successCount += 1
            } else {
                failedCount += 1
            }
        }

        val allOk = failedCount == 0
        val message = if (allOk) {
            "素材已写入图库（共 $successCount 个）"
        } else {
            "素材导入完成：成功 $successCount 个，失败 $failedCount 个"
        }
        reportDeviceOpToCloud(
            deviceId = deviceId,
            alias = alias,
            opId = opId,
            status = if (allOk) "success" else "failed",
            message = message,
            data = mapOf(
                "op_type" to "gallery_import_media",
                "device_id" to deviceId,
                "success_count" to successCount,
                "failed_count" to failedCount,
                "operation_logs" to operationLogs,
            ),
        )
        } finally {
            ExecutionScope.exit()
        }
    }

    private suspend fun handleUsername(alias: String, deviceId: String, username: String) {
        if (shouldAbortPullExecution()) {
            LogCenter.op(alias, "涨粉已暂停/独占抢占中：跳过执行 $username")
            return
        }
        if (!isAssignedAccountLoggedIn(alias, deviceId)) {
            if (shouldAbortPullExecution()) {
                LogCenter.op(alias, "涨粉已暂停/独占抢占中：未登录，跳过 $username")
                return
            }
            pendingUsernames.add(0, username)
            LogCenter.op(alias, "涨粉：未登录，$username 已放回队列（等待自动登录）")
            return
        }
        ExecutionScope.enter()
        ProcessDiagnostics.setCurrentUsername(username)
        try {
        val runMode = _config.value.run_mode.trim().lowercase()
        // 涨粉、直客：失败后不重试；主播 live：保留重试。
        val maxAttempts =
            if (runMode == ServerRunMode.GROWTH || runMode == ServerRunMode.MUTUAL || runMode == ServerRunMode.DIRECT) 1 else 3
        var lastMessage = ""
        var success = false

        for (attempt in 1..maxAttempts) {
            if (shouldAbortPullExecution()) {
                LogCenter.op(alias, "涨粉已暂停/独占抢占中：中止 $username（第 $attempt/$maxAttempts 次）")
                return
            }
            LogCenter.op(alias, "开始执行任务：$username（第 $attempt/$maxAttempts 次）")
            val result = runCatching {
                executor.executeForUsername(username, _config.value)
            }
            if (result.isSuccess) {
                lastMessage = result.getOrNull().orEmpty()
                if (isGrowthTaskInterruptedResult(lastMessage) || shouldAbortPullExecution()) {
                    LogCenter.op(alias, "涨粉任务已协作退出：$username — $lastMessage")
                    return
                }
                val isNoVideosCompleted = lastMessage.contains("目标用户无作品")
                if (isNoVideosCompleted) {
                    LogCenter.op(alias, "任务完成（无作品）：$username — $lastMessage")
                    PrefsStore.markUsernameCompleted(username)
                    completedTracker.add(username)
                    success = true
                    break
                }

                val isFailedResult = lastMessage.startsWith("失败：") || lastMessage.contains("执行失败") || lastMessage.contains("异常")
                if (isFailedResult) {
                    LogCenter.op(alias, "任务失败：$username（第 $attempt/$maxAttempts 次）— $lastMessage")
                    failedTracker.add("$username | $lastMessage")
                    scheduleFailureReport(
                        business = "${queueRunMode()}_task",
                        errorType = FailureReportUploader.normalizeErrorType(lastMessage),
                        summary = "$username | $lastMessage",
                    )
                    if (attempt == maxAttempts) {
                        break
                    }
                    continue
                }

                LogCenter.op(alias, "任务成功：$username — $lastMessage")
                PrefsStore.markUsernameCompleted(username)
                completedTracker.add(username)
                success = true
                if (runMode == ServerRunMode.LIVE || runMode == ServerRunMode.DIRECT) {
                    LogCenter.op(alias, "已回 Users，继续下一名用户")
                }
                break
            }

            val errThrowable = result.exceptionOrNull()
            if (errThrowable is LoginSafeguardRequiresAutoLoginException) {
                clearMutualClickStateForInterrupt("掉登录(涨粉)")
                LogCenter.op(
                    alias,
                    "涨粉：OCR 见 Add another account（涨粉期不把 Log in/Sign up 当掉登录），" +
                        "终止 $username 并从登录入口续接自动登录",
                )
                pendingUsernames.add(0, username)
                val recovered = ensureGrowthAccountLoggedInWithRetry(
                    alias = alias,
                    deviceId = deviceId,
                    reason = "掉登录后",
                    forceRelogin = true,
                    resumeFromLoginEntryScreen = true,
                )
                if (!recovered) {
                    pauseGrowthAfterLoginFailure(alias, deviceId, "掉登录后自动登录未恢复，已保留 $username 等待重登")
                }
                return
            }
            if (errThrowable is AccountPlanBlockedException) {
                handleAccountPlanBlocked(
                    alias,
                    deviceId,
                    errThrowable,
                    "涨粉",
                )
                return
            }
            if (errThrowable is AccountStatusLoggedOutException) {
                clearMutualClickStateForInterrupt("掉登录(Account status)")
                LogCenter.op(alias, "涨粉：掉登录，终止 $username：${errThrowable.message}")
                pendingUsernames.add(0, username)
                executor.setAccountStatusReloginInProgress(true)
                val recovered = ensureGrowthAccountLoggedInWithRetry(
                    alias = alias,
                    deviceId = deviceId,
                    reason = "掉登录后",
                    forceRelogin = true,
                )
                if (recovered) {
                    // 无 OK 清数据会清 firstLoginDone；重登成功后标就绪，避免后续手动任务被闸门挡住
                    if (ManualDeviceControl.ENABLED) {
                        val u = username.trim().removePrefix("@")
                            .ifBlank { ManualDeviceControl.activeManualUsername() }
                            .ifBlank { ManualDeviceControl.selectedManualUsername() }
                        if (u.isNotBlank()) markManualAccountReady(u, "涨粉掉登录后自动登录")
                    }
                } else {
                    pauseGrowthAfterLoginFailure(alias, deviceId, "Account status 掉登录后自动登录未恢复，已保留 $username 等待重登")
                }
                return
            }
            if (errThrowable != null && isPullLoopJobCancellation(errThrowable)) {
                val cancelReason =
                    if (PrefsStore.getAutoSwitchMode() == "exiting") "自动切号关闭打断"
                    else "发作品/互点切换打断"
                LogCenter.op(alias, "任务已取消：$username（$cancelReason）")
                if (errThrowable is CancellationException) {
                    throw errThrowable
                }
                throw CancellationException(errThrowable.message ?: "cancelled")
            }
            val err = errThrowable?.message ?: "unknown"
            if (err.contains("FATAL_DEBUG_STOP")) {
                LogCenter.op(alias, "致命错误，已停止：$username — $err")
                failedTracker.add("$username | $err")
                scheduleFailureReport(
                    business = "${queueRunMode()}_task",
                    errorType = "fatal_debug_stop",
                    summary = "$username | $err",
                )
                updateRuntime(lastMessage = "致命诊断触发，已停止任务")
                reportWithRetry("异常：用户名 $username 触发致命诊断，已停止任务，$err")
                stopAllWork()
                return
            }

            LogCenter.op(
                alias,
                "任务异常：$username（$attempt/$maxAttempts）— $err",
            )

            // 临时停用：失败达到2次后自动关闭并重启 TikTok（按你的要求）
            // if (attempt >= 2) {
            //     LogCenter.append(alias, "失败已达到2次，立即关闭 TikTok 后台并重新打开后继续任务")
            //     val restarted = runCatching { executor.restartTikTokForRecovery() }.getOrDefault(false)
            //     LogCenter.append(alias, "关闭并重启 TikTok 结果=${if (restarted) "成功" else "失败"}")
            //     if (runMode == "live") {
            //         LogCenter.append(alias, "主播访问模式：重启后从当前用户名继续执行")
            //     } else {
            //         LogCenter.append(alias, "涨粉模式：重启后从下一个用户名继续执行")
            //         lastMessage = "异常：用户名 $username 执行失败，$err"
            //         break
            //     }
            // }
            if (attempt >= 2) {
                LogCenter.op(alias, "已失败 2 次，继续重试（未自动重启 TikTok）")
            }

            if (attempt == maxAttempts) {
                lastMessage = "异常：用户名 $username 执行失败，$err"
                scheduleFailureReport(
                    business = "${queueRunMode()}_task",
                    errorType = FailureReportUploader.normalizeErrorType(err),
                    summary = "$username | $err",
                )
            }
        }

        if (success) {
            updateRuntime(lastMessage = "用户名 $username 已完成")
            // 涨粉模式：队列在拉取时已从服务端删除，无需再上报每条执行结果。
            if (runMode != ServerRunMode.GROWTH && runMode != ServerRunMode.MUTUAL) {
                reportWithRetry(lastMessage)
            }
            return
        }

        updateRuntime(lastMessage = "用户名 $username 执行异常")
        if (runMode != ServerRunMode.GROWTH && runMode != ServerRunMode.MUTUAL) {
            reportWithRetry(lastMessage)
        }

        if (runMode == ServerRunMode.GROWTH || runMode == ServerRunMode.MUTUAL) {
            val qLabel = if (runMode == ServerRunMode.MUTUAL) "互点（mobile 队列）" else "涨粉"
            LogCenter.op(alias, "$qLabel：跳过失败用户，继续下一个")
            PrefsStore.markUsernameProcessed(username)
        } else {
            LogCenter.op(alias, "主播/直客：本用户失败，将按策略重试或跳过")
        }
        } finally {
            ProcessDiagnostics.setCurrentUsername(null)
            ExecutionScope.exit()
        }
    }

    fun reportWithRetry(message: String) {
        val alias = PrefsStore.getAlias() ?: return
        val deviceId = PrefsStore.getDeviceId() ?: return
        scope.launch {
            var success = false
            for (index in 0 until 3) {
                val result = runCatching {
                    ApiService.reportResult(deviceId, alias, message, queueRunMode())
                }
                if (result.isSuccess && result.getOrNull()?.code == 0) {
                    success = true
                    updateRuntime(lastMessage = "结果上报成功")
                    break
                }
                LogCenter.op(alias, "上报结果重试 ${index + 1}/3")
                updateRuntime(lastMessage = "结果上报重试 ${index + 1}/3")
                delay(5000)
            }

            if (!success) {
                PendingReportStore.add(message)
                LogCenter.op(alias, "上报结果失败，已加入本地队列")
                updateRuntime(lastMessage = "结果上报失败，已加入重试队列")
            }
        }
    }

    private fun enqueuePendingReports() {
        scope.launch {
            val pending = PendingReportStore.drain()
            pending.forEach { message ->
                reportWithRetry(message)
            }
        }
    }

    fun onWorkerServiceChanged(isRunning: Boolean) {
        updateRuntime(serviceRunning = isRunning)
        if (isRunning && !PrefsStore.isHardStopRequested()) {
            ensureLoopsRunning()
            if (PrefsStore.isAutoSwitchAutonomousMode() && PrefsStore.getAutoSwitchMode() == "on") {
                // 涨粉协程仍在跑时勿重拉 bundle，避免打断进行中的 OCR/帧差。
                if (pullLoopJob?.isActive == true && PrefsStore.getLocalStatus() == "running") {
                    manuallyPaused = false
                    if (!autoSwitchLocalScheduler.isRunning()) {
                        scope.launch { resumeAutoSwitchGrowthLightweight("后台服务恢复-涨粉进行中") }
                    }
                    maybeStartGrowthInAutoSwitchIdle("后台服务恢复-涨粉进行中")
                } else {
                    scope.launch { recoverAutoSwitchAutonomousWork("后台服务恢复") }
                }
            }
        }
        // 停止服务时的 online 同步已在 stopAllWork() 内统一处理（含重试），此处不再重复请求。
    }

    fun stopRunningState() {
        PrefsStore.saveLocalStatus("online")
        pullLoopJob?.cancel()
        updateRuntime(localStatus = "online", lastMessage = "任务已停止")
    }

    fun setManualPaused(paused: Boolean) {
        if (paused) {
            manuallyPaused = true
            PrefsStore.saveLocalStatus("paused")
            startPollJob?.cancel()
            pullLoopJob?.cancel()
            deviceOpsJob?.cancel()
            startPollJob = null
            pullLoopJob = null
            deviceOpsJob = null
            started.set(false)
            updateRuntime(localStatus = "paused", lastMessage = "已硬暂停：执行循环已停止")
            return
        }

        manuallyPaused = false
        PrefsStore.saveLocalStatus("online")
        updateRuntime(localStatus = "online", lastMessage = "已恢复：重新进入任务轮询")
        startIfNeeded()
    }

    fun isManualPaused(): Boolean = manuallyPaused

    fun requestMutualClickSwitch() {
        PrefsStore.setMutualCommentOnlySession(false)
        PrefsStore.setMutualClickRequested(true)
        PrefsStore.setMutualClickPending(true)
        PrefsStore.setHardStopRequested(false)
        PrefsStore.saveLocalStatus("running")
        updateRuntime(
            localStatus = "running",
            hardStopRequested = false,
            lastMessage = "已收到互点切换指令，等待当前任务到达合适停止点",
        )
        LogCenter.op(
            PrefsStore.getAlias() ?: "unknown",
            "互点请求已登记：requested=${PrefsStore.isMutualClickRequested()} pending=${PrefsStore.isMutualClickPending()} hardStop=${PrefsStore.isHardStopRequested()} localStatus=${PrefsStore.getLocalStatus()}",
        )
    }

    fun shouldSwitchToMutualClick(): Boolean = PrefsStore.isMutualClickRequested() && PrefsStore.isMutualClickPending()

    /**
     * 硬停 / 发作品 / 掉登录等打断场景：清互点待执行标记并取消在途互点协程，
     * 避免残留 requested/pending 劫持后续涨粉。
     */
    private fun clearMutualClickStateForInterrupt(reason: String) {
        val hadMutual =
            PrefsStore.isMutualClickRequested() ||
                PrefsStore.isMutualClickPending() ||
                isMutualSwitchActive()
        if (!hadMutual) return
        if (isMutualSwitchActive()) {
            mutualSwitchJob?.cancel()
            mutualSwitchJob = null
        }
        PrefsStore.clearMutualClickState()
        PrefsStore.getAlias()?.trim()?.takeIf { it.isNotBlank() }?.let { alias ->
            LogCenter.op(alias, "互点状态已清除（$reason）")
        }
    }

    fun markMutualClickSwitched() {
        PrefsStore.clearMutualClickState()
        PrefsStore.setMutualCommentOnlySession(false)
    }

    /**
     * 用户从主界面请求「硬停」：不在协程安全点外杀进程；通过 [TraceExecutor] 内硬停检查在合适位置结束；
     * 暂停用户名拉取；保留 [startCheckLoop] 与设备指令轮询；并向云端同步 paused + 关闭本机「自动开启程序」。
     * simple 本机自治开启时：硬停**暂停**调度并清运行时脏数据，保留计划文件与 APP 开关；
     * 「启动后台服务」再按持久化计划续跑。
     */
    fun requestUserGracefulHardStop() {
        if (ManualDeviceControl.ENABLED) {
            val alias = PrefsStore.getAlias()
            when {
                PrefsStore.isManualLocalAutoSwitchEnabled() -> {
                    LogCenter.op(alias, "硬停：暂停本机自动切号自治并清理运行时脏数据（保留计划文件）")
                    pauseManualLocalAutoSwitchForHardStop()
                }
                PrefsStore.isAutoSwitchAutonomousMode() -> {
                    // APP 开关已关但仍遗留 mode=on/exiting：彻底清掉，避免硬停后自愈打架。
                    LogCenter.op(alias, "硬停：清理遗留自动切号自治状态")
                    scope.launch {
                        clearOrphanedAutoSwitchAfterManualDisable("硬停清理遗留")
                    }
                }
            }
            applyUserGracefulHardStopFlags(alias)
            return
        }
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            val alias = PrefsStore.getAlias()
            if (alias != null) {
                LogCenter.op(alias, "自动切号自治模式中不可硬停，请先在管理台关闭自动切号")
            }
            updateRuntime(lastMessage = "自动切号自治中：硬停不可用")
            return
        }
        applyUserGracefulHardStopFlags(PrefsStore.getAlias())
    }

    /**
     * 硬停暂停本机自动切号：停调度、取消在途插入/登录恢复、清运行时脏 prefs；
     * **不**清计划文件 / session / APP 开关，便于点击开始按本地计划续跑。
     */
    private fun pauseManualLocalAutoSwitchForHardStop() {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        cancelAutoSwitchBundleRetry()
        autoSwitchLoginRecoveryJob?.cancel()
        autoSwitchLoginRecoveryJob = null
        growthModeLoginSafeguardPending = false
        if (isPublishPostSwitchActive()) {
            publishPostSwitchJob?.cancel()
            publishPostSwitchJob = null
        }
        if (isMutualSwitchActive()) {
            mutualSwitchJob?.cancel()
            mutualSwitchJob = null
        }
        if (isLiveVisitSwitchActive()) {
            liveVisitSwitchJob?.cancel()
            liveVisitSwitchJob = null
        }
        pullLoopJob?.cancel()
        pullLoopJob = null
        autoSwitchLocalScheduler.stop(clearState = false)
        PrefsStore.clearAutoSwitchInsertionDirtyPrefs()
        AutoSwitchLocalPlanStore.clearActivePublish()
        AutoSwitchLocalPlanStore.clearActiveMutual()
        AutoSwitchLocalPlanStore.clearActiveLiveVisit()
        PrefsStore.setAutoSwitchExclusivePreempt(false)
        PublishPostGrowthGate.exitExclusivePublishPhase()
        executor.clearAccountStatusReloginState()
        ExecutionScope.forceClearIfStuck("硬停暂停本机自动切号")
        if (alias.isNotBlank()) {
            LogCenter.op(
                alias,
                "【自动切号】硬停已暂停本机自治：已清登录/发作品/互点运行时脏数据，计划文件保留；点击开始可按计划续跑",
            )
        }
        updateRuntime(lastMessage = "硬停：本机自动切号已暂停（计划保留）")
    }

    private fun applyUserGracefulHardStopFlags(alias: String?) {
        clearMutualClickStateForInterrupt("硬停")
        val deviceId = PrefsStore.getDeviceId()
        PrefsStore.setHardStopRequested(true)
        val publishAccountKey =
            PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() } ?: alias?.trim().orEmpty()
        if (publishAccountKey.isNotBlank()) {
            PrefsStore.clearPublishPostResumeProgress(publishAccountKey)
        }
        PrefsStore.clearPublishPostTaskScope()
        manuallyPaused = false
        started.set(false)
        pendingUsernames.clear()
        pullLoopJob?.cancel()
        pullLoopJob = null
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(
            localStatus = "paused",
            hardStopRequested = true,
            lastMessage = "已请求硬停：当前步骤结束后停止自动任务，仍监听云端",
            serviceRunning = WorkerServiceTracker.isRunning,
        )
        if (startPollJob?.isActive != true) {
            startCheckLoop()
        }
        ensureDeviceOpsLoopIfBound()
        scope.launch {
            if (deviceId.isNullOrBlank() || alias.isNullOrBlank()) return@launch
            runCatching {
                syncCloudStatus("paused", CloudStatusSyncKind.LIFECYCLE)
            }.onFailure { err ->
                LogCenter.op(alias, "硬停：同步 paused 到云端失败：${err.message}")
            }
            runCatching {
                reportAutoStartOptOutIfNeeded(deviceId, alias, true)
            }.onFailure { err ->
                LogCenter.op(alias, "硬停：上报云端关闭自动开启失败：${err.message}")
            }.onSuccess {
                LogCenter.op(alias, "硬停：已向云端上报关闭本设备自动开启程序（opt_out=true）")
            }
        }
    }

    /**
     * WorkerService 被系统回收后重建（非用户 stop）：不 cancel 涨粉协程、不置 manuallyPaused。
     * 协程挂在 Application 作用域，与前台 Service 生命周期解耦。
     */
    fun stopWorkForServiceDestroy() {
        updateRuntime(
            serviceRunning = false,
            lastMessage = "后台服务重建中",
        )
    }

    fun stopAllWork(requestHardStop: Boolean = true) {
        if (requestHardStop) {
            clearMutualClickStateForInterrupt("硬停")
            PrefsStore.setHardStopRequested(true)
        }
        val hardStopped = PrefsStore.isHardStopRequested()
        PrefsStore.saveLocalStatus("paused")
        startPollJob?.cancel()
        pullLoopJob?.cancel()
        deviceOpsJob?.cancel()
        startPollJob = null
        pullLoopJob = null
        deviceOpsJob = null
        manuallyPaused = true
        started.set(false)
        autoSwitchLocalScheduler.stop(clearState = false)
        updateRuntime(
            serviceRunning = false,
            localStatus = "paused",
            hardStopRequested = hardStopped,
            lastMessage = "后台服务已停止",
        )
        scope.launch {
            syncOnline(retries = 4)
        }
    }

    /**
     * 仅停止执行相关循环，保留 check_start 轮询，继续监听云端启动/暂停指令。
     */
    fun stopExecutionOnly(keepListenerReason: String = "后台服务已停止，保留云端指令监听") {
        PrefsStore.setHardStopRequested(true)
        PrefsStore.saveLocalStatus("paused")
        pullLoopJob?.cancel()
        deviceOpsJob?.cancel()
        pullLoopJob = null
        deviceOpsJob = null
        manuallyPaused = true

        updateRuntime(
            serviceRunning = false,
            localStatus = "paused",
            hardStopRequested = true,
            lastMessage = keepListenerReason,
        )

        if (startPollJob?.isActive != true && !PrefsStore.isHardStopRequested()) {
            startCheckLoop()
        }

        scope.launch {
            syncOnline(retries = 4)
        }
    }

    fun refreshRuntimeSnapshot() {
        updateRuntime(
            serviceRunning = WorkerServiceTracker.isRunning,
            localStatus = PrefsStore.getLocalStatus(),
            currentServerUrl = ApiService.getBaseUrl(),
            hardStopRequested = PrefsStore.isHardStopRequested(),
        )
        if (WorkerServiceTracker.isRunning && PrefsStore.isHardStopRequested() && !manuallyPaused && PrefsStore.isBound()) {
            if (startPollJob?.isActive != true) {
                startCheckLoop()
            }
            ensureDeviceOpsLoopIfBound()
        }
        if (WorkerServiceTracker.isRunning && !PrefsStore.isHardStopRequested() && !manuallyPaused) {
            ensureLoopsRunning()
        }
    }

    private fun batchStreamStatusText(hours: Double): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hoursText = if (hours % 1.0 == 0.0) {
            hours.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", hours).trimEnd('0').trimEnd('.')
        }
        return "${month}月${day}日已活跃${hoursText}小时"
    }

    private suspend fun batchStreamAutoLogin(
        alias: String,
        deviceId: String,
        opId: String,
        row: BatchStreamRowPayload,
    ): Boolean {
        val username = row.username?.trim().orEmpty()
        val password = row.password?.trim().orEmpty()
        val email = row.email?.trim().orEmpty()
        val api = row.api?.trim().orEmpty()
        if (username.isBlank() || password.isBlank() || email.isBlank()) {
            LogCenter.op(alias, "[OP][$opId] 批量带流行 ${row.sheet_row}：登录字段不完整，跳过")
            return false
        }
        if (api.isBlank()) {
            LogCenter.op(alias, "[OP][$opId] 批量带流行 ${row.sheet_row}：api 为空，将尝试通过邮箱管理模块获取验证码")
        }
        username.let { PrefsStore.setLogContextUsername(it) }
        fetchConfig(logToUser = true)
        autoLoginActive = true
        PrefsStore.saveLocalStatus("paused")
        updateRuntime(localStatus = "paused", lastMessage = "批量带流：自动登录 $username")
        runCatching { syncCloudStatus("paused", CloudStatusSyncKind.LIFECYCLE) }
        LogCenter.op(alias, "[OP][$opId] 批量带流：自动登录 $username（行 ${row.sheet_row}）")
        val loginOk = runCatching {
            executor.runAutoLoginAfterCutSwitch(
                appContext,
                TikTokAutoLoginCredentials(
                    username = username,
                    email = email,
                    password = password,
                    apiUrl = api,
                ),
                resumeFromLoginEntryScreen = false,
                flowKind = AutoLoginFlow.CutSwitchAfterRestore,
            )
        }.getOrElse { err ->
            LogCenter.op(alias, "[OP][$opId] 批量带流自动登录异常：${err.message}")
            false
        }
        autoLoginActive = false
        return loginOk
    }

    private suspend fun runBatchStreamSession(
        alias: String,
        deviceId: String,
        parentOpId: String,
        sessionId: String,
        packageName: String,
        hoursPerAccount: Double,
        activityRounds: Int,
        rows: List<BatchStreamRowPayload>,
    ) {
        val rounds = activityRounds.coerceIn(1, 20)
        val totalHoursPerAccount = hoursPerAccount * rounds
        try {
            LogCenter.op(
                alias,
                "[OP][$parentOpId] 批量带流会话开始 session=$sessionId rows=${rows.size} rounds=$rounds",
            )
            updateRuntime(lastMessage = "批量带流运行中（${rows.size} 个账号，${rounds} 轮）")
            for (roundIndex in 0 until rounds) {
                val roundNum = roundIndex + 1
                if (batchStreamStopRequested || PrefsStore.isHardStopRequested()) break
                if (rounds > 1) {
                    LogCenter.op(alias, "[OP][$parentOpId] 批量带流第 ${roundNum}/${rounds} 轮开始")
                    updateRuntime(lastMessage = "批量带流第 ${roundNum}/${rounds} 轮（${rows.size} 个账号）")
                }
                for ((index, row) in rows.withIndex()) {
                if (batchStreamStopRequested || PrefsStore.isHardStopRequested()) {
                    LogCenter.op(alias, "[OP][$parentOpId] 批量带流已请求停止，剩余 ${rows.size - index} 个账号未执行")
                    break
                }
                val sheetRow = row.sheet_row ?: 0
                val username = row.username?.trim().orEmpty()
                LogCenter.op(
                    alias,
                    "[OP][$parentOpId] 批量带流 ${index + 1}/${rows.size}：$username（行 $sheetRow${if (rounds > 1) "，第 ${roundNum}/${rounds} 轮" else ""}）",
                )
                val loginOk = batchStreamAutoLogin(alias, deviceId, parentOpId, row)
                if (!loginOk) {
                    LogCenter.op(alias, "[OP][$parentOpId] 批量带流：$username 自动登录失败，跳过本行")
                    continue
                }
                if (roundIndex == 0) {
                    runProfileEditAfterCutSwitch(
                        alias = alias,
                        deviceId = deviceId,
                        parentOpId = parentOpId,
                        skipOuterWarmup = true,
                        profileEditTrigger = "batch_stream",
                        skipAccountDbUpdate = true,
                    )
                }
                if (batchStreamStopRequested || PrefsStore.isHardStopRequested()) break

                batchStreamGrowthPhase = true
                growthModeLoginSafeguardPending = false
                fetchConfig(configQueryRunMode = ServerRunMode.GROWTH, logToUser = false)
                val growthLabel = if (rounds > 1) {
                    "批量带流：涨粉 ${hoursPerAccount} 小时（$username，第 ${roundNum}/${rounds} 轮）"
                } else {
                    "批量带流：涨粉 ${hoursPerAccount} 小时（$username）"
                }
                forceStartRunningNow(growthLabel)
                startPullLoop()
                val growthMs = (hoursPerAccount * 3600.0 * 1000.0).toLong().coerceAtLeast(60_000L)
                val endAt = SystemClock.elapsedRealtime() + growthMs
                while (SystemClock.elapsedRealtime() < endAt) {
                    if (batchStreamStopRequested || PrefsStore.isHardStopRequested()) break
                    delay(30_000)
                }
                pullLoopJob?.cancel()
                pullLoopJob = null
                batchStreamGrowthPhase = false
                PrefsStore.saveLocalStatus("paused")
                updateRuntime(localStatus = "paused", lastMessage = "批量带流：账号 $username 活跃结束")
                runCatching { syncCloudStatus("paused", CloudStatusSyncKind.LIFECYCLE) }

                if (roundIndex == rounds - 1 && sheetRow >= 2) {
                    val statusText = batchStreamStatusText(totalHoursPerAccount)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            ApiService.reportBatchStreamRow(
                                deviceId = deviceId,
                                alias = alias,
                                sessionId = sessionId,
                                sheetRow = sheetRow,
                                statusText = statusText,
                                hoursActive = totalHoursPerAccount,
                            )
                        }
                    }.onSuccess {
                        LogCenter.op(alias, "[OP][$parentOpId] 批量带流：行 $sheetRow 已回写 Excel（$statusText）")
                    }.onFailure { err ->
                        LogCenter.op(alias, "[OP][$parentOpId] 批量带流：回写 Excel 失败 ${err.message}")
                    }
                }

                val hasNextInRound = index < rows.lastIndex
                val hasNextRound = roundIndex < rounds - 1
                if ((hasNextInRound || hasNextRound) && !batchStreamStopRequested && !PrefsStore.isHardStopRequested()) {
                    LogCenter.op(alias, "[OP][$parentOpId] 批量带流：清理 TikTok，准备下一账号")
                    runExecutorSu("am force-stop $packageName")
                    delay(1200)
                    runExecutorSu("pm clear $packageName")
                    delay(1500)
                }
                }
                if (roundIndex < rounds - 1 && !batchStreamStopRequested && !PrefsStore.isHardStopRequested()) {
                    LogCenter.op(alias, "[OP][$parentOpId] 批量带流第 ${roundNum} 轮完成，开始下一轮")
                }
            }
        } finally {
            batchStreamGrowthPhase = false
            pullLoopJob?.cancel()
            pullLoopJob = null
            batchStreamStopRequested = false
            restoreCloudAutoStartParticipation(alias, deviceId, parentOpId, "批量带流结束")
            runCatching {
                withContext(Dispatchers.IO) {
                    ApiService.completeBatchStream(deviceId, alias)
                }
            }
            batchStreamJob = null
            PrefsStore.saveLocalStatus("paused")
            updateRuntime(localStatus = "paused", lastMessage = "批量带流已结束，设备恢复常规状态")
            runCatching { syncCloudStatus("paused", CloudStatusSyncKind.LIFECYCLE) }
            LogCenter.op(alias, "[OP][$parentOpId] 批量带流会话结束，已自动退出带流模式")
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

object PendingReportStore {
    private val queue = mutableListOf<String>()

    @Synchronized
    fun add(message: String) {
        queue.add(message)
    }

    @Synchronized
    fun drain(): List<String> {
        val copy = queue.toList()
        queue.clear()
        return copy
    }
}
