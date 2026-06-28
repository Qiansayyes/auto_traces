package com.autotraces.app

import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.autotraces.app.backup.BackupDirectoryReconciler
import com.autotraces.app.backup.DeviceBackupShell
import com.autotraces.app.backup.UnifiedBackupPaths

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
    private val autoSwitchLocalScheduler = AutoSwitchLocalScheduler(
        scope,
        executeOp = { alias, deviceId, op -> handleDeviceOp(alias, deviceId, op) },
        onPauseGrowth = { pauseGrowthForAutoSwitchExclusiveOp() },
        onForceStopTikTokForExclusive = { phase -> forceStopTikTokForExclusiveEnqueue(phase) },
        onExclusiveQueueDrained = { releaseAutoSwitchExclusivePreemptIfIdle("独占 OP 队列已空") },
        onTryResumeGrowth = { maybeStartGrowthInAutoSwitchIdle() },
        onRefreshWeeklyBundle = { refreshAutoSwitchWeeklyBundleFromCloud() },
        isPublishExecutionActive = { isPublishPostSwitchActive() },
        onStalePublishInflight = { slotKey -> abortStaleAutoSwitchPublishInflight(slotKey) },
        onVerifyPublishInsertionLogin = { alias, deviceId, targetUsername, slotKey ->
            verifyPublishInsertionLoginBeforeEnqueue(alias, deviceId, targetUsername, slotKey)
        },
        onPublishLoginCacheStillValid = { alias, deviceId, targetUsername ->
            isAssignedAccountLoggedIn(alias, deviceId, preferredUsername = targetUsername)
        },
        onVerifySlotAlignmentLogin = { alias, deviceId, targetUsername, slotKey, targetRi ->
            verifySlotAlignmentLightweightLogin(alias, deviceId, targetUsername, slotKey, targetRi)
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
    )
    private var pullLoopJob: Job? = null
    private var deviceOpsJob: Job? = null
    private var mutualSwitchJob: Job? = null
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

    private fun isPublishPostSwitchActive(): Boolean = publishPostSwitchJob?.isActive == true

    private fun isAutoSwitchPublishLocallyActive(): Boolean = isPublishPostSwitchActive()

    private fun isMutualSwitchActive(): Boolean = mutualSwitchJob?.isActive == true

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
            !isBatchStreamActive() &&
            !(PrefsStore.isAutoSwitchAutonomousMode() && isAutoSwitchPublishLocallyActive())
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

    /** 账号已对齐且本地 OP 队列空闲时，恢复自治模式下的涨粉拉取。 */
    private fun maybeStartGrowthInAutoSwitchIdle(reason: String = "自治空档") {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PrefsStore.getAutoSwitchMode() == "exiting") return
        if (PublishPostGrowthGate.isBlockingGrowth()) return
        releaseAutoSwitchExclusivePreemptIfIdle(reason)
        if (manuallyPaused || shouldHonorUserHardStop()) return
        if (PrefsStore.getLocalStatus() != "running") return
        if (!autoSwitchLocalScheduler.shouldAllowGrowthBaseline()) return
        if (pullLoopJob?.isActive == true) return
        if (!canStartPullLoopNow()) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【自动切号】$reason，恢复涨粉拉取")
        }
        if (queueRunMode() == ServerRunMode.GROWTH) {
            growthModeLoginSafeguardPending = !PrefsStore.peekGrowthContinueFromPublishHome()
        }
        startPullLoop()
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
        return r.contains("备份文件不存在或不可读") || r.contains("本机无 @")
    }

    private fun cutSwitchMissingBackupUserMessage(username: String): String {
        val user = username.trim().removePrefix("@")
        return if (user.isNotBlank()) {
            "目标账号 @$user 备份包本机不可用，改走自动登录"
        } else {
            "目标账号备份包本机不可用，改走自动登录"
        }
    }

    /** 实时日志用：去掉完整路径、备份包名、内网地址等敏感信息。 */
    private fun cutSwitchUserLogMessage(reason: String): String {
        val r = reason.trim()
        if (r.isEmpty()) return "切号步骤失败"
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

    /** 备份包缺失、username 无效等错误重试无意义，应立即中止。 */
    private fun isCutSwitchFailureRetryable(reason: String): Boolean {
        if (isCutSwitchMissingBackupFailure(reason)) return false
        val r = reason.trim()
        if (r.isEmpty()) return true
        val nonRetryableMarkers = listOf(
            "缺少有效 username",
            "无法生成备份包名",
            "需在管理台重新备份或通过 PC 传输导入",
            "备份目录内无 .tar.gz",
        )
        return nonRetryableMarkers.none { r.contains(it) }
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
    ) {
        if (alias.isBlank() || deviceId.isBlank()) return
        scope.launch {
            runBackupDirectoryReconcile(alias, deviceId, accounts, extraKeepAbsolutePath, logPrefix)
        }
    }

    private suspend fun runBackupDirectoryReconcile(
        alias: String,
        deviceId: String,
        accounts: List<AutoSwitchAccount>,
        extraKeepAbsolutePath: String? = null,
        logPrefix: String = "【备份目录整理】",
    ) {
        val policies = BackupDirectoryReconciler.policiesFromAccounts(accounts)
        if (policies.isEmpty()) return
        val extraKeep = extraKeepAbsolutePath?.trim()?.takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: emptySet()
        val result = BackupDirectoryReconciler.reconcile(
            context = appContext,
            alias = alias,
            deviceId = deviceId,
            policies = policies,
            extraKeepAbsolutePaths = extraKeep,
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
            LogCenter.op(
                alias,
                "$logPrefix 云端备份缺失 @${miss.username}：${miss.cloudBackupName} " +
                    "（本机无此文件，需重新登录该账号后自动备份）",
            )
        }
    }

    private fun notifyAutoSwitchCutSwitchStepFailed(payload: DeviceOpPayload?) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
        if (slotKey.isEmpty()) return
        autoSwitchLocalScheduler.cancelPendingCutSwitchFollowUps(slotKey)
        autoSwitchLocalScheduler.clearCutSwitchInflight()
        autoSwitchLocalScheduler.invalidateSlotAlignment(slotKey, "切号前序步骤失败")
        AutoSwitchLedger.recordCutSwitchSlotAborted(slotKey, "切号前序步骤失败")
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
        updateRuntime(
            serviceRunning = WorkerServiceTracker.isRunning,
            localStatus = PrefsStore.getLocalStatus(),
            currentServerUrl = ApiService.getBaseUrl(),
            lastMessage = "应用已初始化",
        )
        scope.launch {
            if (BindingRecovery.tryRecoverFromCloud(appContext)) {
                ensureDeviceOpsLoopIfBound()
            }
            tryUploadPendingAutoSwitchSummary()
            startIfNeeded()
        }
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
        LogCenter.op(alias, "设备已就绪，请在管理台点击「启动设备」开始涨粉/任务")
        updateRuntime(
            localStatus = PrefsStore.getLocalStatus(),
            currentServerUrl = ApiService.getBaseUrl(),
            lastMessage = "已就绪，等待管理台启动",
        )
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
                bootstrapAfterBinding()
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "在线，等待管理台启动")
                startCheckLoop()
            }
            return
        }
        ensureLoopsRunning()
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
            if (PublishPostGrowthGate.isBlockingGrowth()) {
                if (!alias.isNullOrBlank()) {
                    LogCenter.op(alias, "发作品独占发布中，忽略显式启动（$reason）")
                }
                return@launch
            }
            if (PrefsStore.isAutoSwitchExclusivePreempt()) {
                if (!alias.isNullOrBlank()) {
                    LogCenter.op(alias, "切号/独占操作进行中，忽略显式启动（$reason）")
                }
                return@launch
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
                runCatching { WorkerService.start(appContext) }
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
                maybeStartGrowthInAutoSwitchIdle("显式启动")
            } else {
                startPullLoop()
            }
        }
    }

    private fun ensureLoopsRunning() {
        if (startPollJob?.isActive != true) {
            startCheckLoop()
        }
        if (!PrefsStore.isAutoSwitchAutonomousMode() && deviceOpsJob?.isActive != true) {
            startDeviceOpsLoop()
        }
        if (PrefsStore.getAutoSwitchMode() == "on" && !autoSwitchLocalScheduler.isRunning()) {
            scope.launch { resumeAutoSwitchSchedulerIfNeeded() }
        }
        if (PrefsStore.getLocalStatus() == "running" && pullLoopJob?.isActive != true) {
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                maybeStartGrowthInAutoSwitchIdle("循环保活")
            } else {
                startPullLoop()
            }
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

    /** 打开 App 或云端登录成功后调用：检测 TikTok 图库/相机/麦克风/通知/联系人权限未开放则 root 授予。 */
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
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
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
     * 已登录云端时：检测 TikTok 图库/相机/麦克风/通知/联系人 runtime 权限，未开放则 root 授予，避免发布流程卡在系统授权弹窗。
     */
    private suspend fun ensureTikTokGalleryPermissionGrantedIfNeeded() {
        if (PrefsStore.getAuthToken().isNullOrBlank()) return
        val pkg = com.autotraces.app.util.Config.TIKTOK_PACKAGE_NAME
        val alias = PrefsStore.getAlias()?.takeIf { it.isNotBlank() } ?: "SYSTEM"
        val packageInstalled = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrElse { err ->
            if (err is PackageManager.NameNotFoundException) {
                LogCenter.op(alias, "TikTok 未安装，跳过图库/相机/麦克风/通知/联系人权限检测")
            } else {
                LogCenter.op(alias, "读取 TikTok 安装信息失败：${err.message}")
            }
            false
        }
        if (!packageInstalled) return
        if (isTikTokGalleryPermissionGranted()) return

        forceStopTikTokCleanly(pkg, alias, "TikTok 图库/相机/麦克风/通知/联系人权限未开放")
        LogCenter.op(alias, "TikTok 图库/相机/麦克风/通知/联系人权限未开放，开始 root 授予…")
        val commands = buildList {
            add("pm grant $pkg android.permission.READ_MEDIA_IMAGES")
            add("pm grant $pkg android.permission.READ_MEDIA_VIDEO")
            add("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
            add("pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE")
            add("pm grant $pkg android.permission.CAMERA")
            add("pm grant $pkg android.permission.RECORD_AUDIO")
            add("pm grant $pkg android.permission.READ_CONTACTS")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            }
            add("appops set $pkg READ_MEDIA_IMAGES allow")
            add("appops set $pkg READ_MEDIA_VIDEO allow")
            add("appops set $pkg READ_EXTERNAL_STORAGE allow")
            add("appops set $pkg WRITE_EXTERNAL_STORAGE allow")
            add("appops set $pkg LEGACY_STORAGE allow")
            add("appops set $pkg CAMERA allow")
            add("appops set $pkg RECORD_AUDIO allow")
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
            LogCenter.op(alias, "TikTok 图库/相机/麦克风/通知/联系人权限开放完成")
        } else {
            LogCenter.op(alias, "TikTok 图库/相机/麦克风/通知/联系人权限开放存在失败：$failed/${commands.size}")
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
    private suspend fun syncRunningToCloud(retries: Int = 1) {
        syncStatusToCloud("running", retries)
    }

    private enum class CloudStatusSyncKind {
        /** 自治期跳过：独占 OP 暂停/恢复等 */
        ROUTINE,
        /** 始终同步：enter/exit/硬停/手动暂停 */
        LIFECYCLE,
        /** 始终同步：空档涨粉 pull_username 门禁 */
        GROWTH,
    }

    private suspend fun syncCloudStatus(
        status: String,
        kind: CloudStatusSyncKind = CloudStatusSyncKind.ROUTINE,
        retries: Int = 1,
    ) {
        if (PrefsStore.isAutoSwitchAutonomousMode() && kind == CloudStatusSyncKind.ROUTINE) {
            return
        }
        syncStatusToCloud(status, retries)
    }

    private suspend fun reportAutoStartOptOutIfNeeded(deviceId: String, alias: String, optOut: Boolean) {
        if (PrefsStore.isAutoSwitchAutonomousMode()) return
        runCatching {
            ApiService.reportDeviceAutoStartOptOut(deviceId = deviceId, alias = alias, optOut = optOut)
        }
    }

    private suspend fun syncStatusToCloud(status: String, retries: Int = 1) {
        val deviceId = PrefsStore.getDeviceId() ?: return
        val alias = PrefsStore.getAlias() ?: return
        val attempts = retries.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            runCatching {
                val response = ApiService.syncStatus(deviceId, alias, status)
                PrefsStore.saveLocalStatus(status)
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
                        if (response.status == "paused") {
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
                                ApiService.syncStatus(deviceId, alias, "paused")
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
                                ApiService.syncStatus(deviceId, alias, "running")
                                LogCenter.op(alias, "管理台已下发「开始运行」，涨粉/任务即将启动")
                                updateRuntime(localStatus = "running", lastMessage = "收到启动/继续指令")
                                if (!WorkerServiceTracker.isRunning) {
                                    runCatching { WorkerService.start(appContext) }
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
                        if (manuallyPaused) {
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

                // 未登录：仅自动登录，不拉取用户名、不执行任务、不进入休息
                if (!isAssignedAccountLoggedIn(alias, deviceId)) {
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
            fetchConfig()
            val pullN = _config.value.pull_batch_size.let { if (it < 1) 5 else it }.coerceIn(1, 100)
            val accountForPull = PrefsStore.getLogContextUsername()
            var response = ApiService.pullUsername(
                deviceId,
                alias,
                pullN,
                queueRunMode(),
                accountForPull,
            )
            if (response.code == 2 && _config.value.no_rest) {
                LogCenter.op(
                    alias,
                    "模板已开启「不休息」：忽略云端休息指令（code=2），立即重新拉取用户名",
                )
                updateRuntime(lastMessage = "不休息模式：已跳过云端休息")
                response = ApiService.pullUsername(
                    deviceId,
                    alias,
                    pullN,
                    queueRunMode(),
                    accountForPull,
                )
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
                LogCenter.op(
                    alias,
                    response.msg?.takeIf { it.isNotBlank() }
                        ?: "暂时没有新的用户可执行，稍后再试",
                )
                updateRuntime(lastMessage = response.msg ?: "暂无待执行用户名")
            }
        } catch (error: Exception) {
            LogCenter.op(alias, "拉取用户名失败：${error.message}")
            updateRuntime(lastMessage = "用户名拉取失败")
        }
    }

    private suspend fun reportOnlineBeforePull(alias: String, deviceId: String) {
        if (shouldAbortPullExecution()) {
            return
        }
        try {
            // 拉取循环内本地已是 running，必须向服务端同步为 running，
            // 若同步为 online 会覆盖服务端状态，导致 pull_username 返回「设备未启动」
            syncCloudStatus("running", CloudStatusSyncKind.GROWTH)
            updateRuntime(lastMessage = "拉取前已同步运行状态")
        } catch (error: Exception) {
            LogCenter.op(alias, "同步运行状态失败：${error.message}")
            updateRuntime(lastMessage = "拉取前同步状态失败")
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
                    val resp = ApiService.modeWait(deviceId, alias, sinceId, timeoutSec = 120)
                    uploadDeviceLogsIfPullRequested(alias, deviceId, resp.log_pull_requested == true)
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
                    if (event != null && event.event_id > 0) {
                        handleDeviceModeEvent(alias, deviceId, event)
                        continue
                    }
                    // 长轮询空结果：正常应接近 timeoutSec；若过快返回则退避，避免打爆服务端。
                    val elapsed = SystemClock.elapsedRealtime() - roundStarted
                    if (elapsed < 30_000L) {
                        delay((30_000L - elapsed).coerceAtLeast(5_000L))
                    }
                } catch (err: Exception) {
                    val msg = err.message ?: ""
                    val isPollTimeout = err is SocketTimeoutException || msg.contains("timeout", ignoreCase = true)
                    if (!isPollTimeout) {
                        LogCenter.op(alias, "模式事件等待失败：$msg")
                    }
                    // 超时视为“本轮无事件”，进入下一轮；异常场景下稍作退避避免打爆服务端
                    delay(if (isPollTimeout) 500L else 5_000L)
                }
            }
        }
    }

    private suspend fun handleDeviceModeEvent(alias: String, deviceId: String, event: DeviceModeEvent) {
        val type = event.type.trim()
        val sessionId = event.session_id.trim()
        val eventId = event.event_id
        val bundleRevision = event.bundle_revision ?: 0
        if (
            type !in setOf("auto_switch_enter", "auto_switch_exit") &&
            PrefsStore.isModeEventProcessed(eventId)
        ) {
            LogCenter.op(alias, "模式事件已处理过(event_id=$eventId type=$type)，跳过")
            return
        }
        when (type) {
            "auto_switch_enter" -> {
                if (PrefsStore.isAutoSwitchEnterDone(eventId)) {
                    runCatching {
                        ApiService.modeAck(deviceId, alias, eventId, "bundle_loaded", sessionId)
                    }
                    PrefsStore.saveLastModeEventId(eventId)
                    LogCenter.op(alias, "【自动切号】enter 事件已处理过(event_id=$eventId)，已重发 bundle_loaded ACK")
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
                    runCatching { WorkerService.start(appContext) }
                }
                runCatching { syncRunningToCloud() }
                PrefsStore.saveAutoSwitchSessionContext(sessionId, bundleRevision)
                AutoSwitchLedger.restoreFromPrefs()
                AutoSwitchLedger.startSession(sessionId, bundleRevision = bundleRevision)
                PrefsStore.setAutoSwitchPendingSummaryUpload(false)
                scheduleAutoSwitchDailyReconcileTargetIfNeeded(alias)
                var bundleLoaded = false
                runCatching {
                    val resp = ApiService.fetchAutoSwitchBundle(
                        deviceId,
                        alias,
                        sessionId,
                        bundleRevision,
                    )
                    if (resp.code == 0 && resp.bundle != null) {
                        applyAutoSwitchBundle(alias, deviceId, resp.bundle, bundleRevision)
                        bundleLoaded = true
                        val weekStart = resp.bundle.week_start_date?.trim().orEmpty()
                        val weekEnd = resp.bundle.week_end_date?.trim().orEmpty()
                        val dayCount = resp.bundle.weekly_plan?.day_plans?.size ?: 0
                        LogCenter.op(
                            alias,
                            "【自动切号】已进入自治 session=$sessionId revision=$bundleRevision " +
                                "week=$weekStart~$weekEnd days=$dayCount",
                        )
                    } else {
                        LogCenter.op(alias, "【自动切号】bundle 拉取失败：${resp.msg}")
                    }
                }.onFailure { err ->
                    LogCenter.op(alias, "【自动切号】bundle 拉取异常：${err.message}")
                }
                updateRuntime(lastMessage = if (bundleLoaded) "自动切号自治：本地调度中" else "自动切号自治：等待 bundle")
                if (bundleLoaded) {
                    PrefsStore.markAutoSwitchEnterDone(eventId)
                    PrefsStore.saveLastModeEventId(eventId)
                    runCatching {
                        ApiService.modeAck(deviceId, alias, eventId, "bundle_loaded", sessionId)
                    }.onFailure { err ->
                        LogCenter.op(alias, "自治模式 ACK(bundle_loaded) 失败：${err.message}")
                    }
                    scope.launch {
                        delay(1_500)
                        autoSwitchLocalScheduler.requestImmediateTick()
                        maybeStartGrowthInAutoSwitchIdle("自治启动")
                    }
                }
            }
            "auto_switch_exit" -> {
                if (PrefsStore.isAutoSwitchExitDone(eventId)) {
                    runCatching {
                        ApiService.modeAck(deviceId, alias, eventId, "summary_uploaded", sessionId)
                    }
                    PrefsStore.saveLastModeEventId(eventId)
                    LogCenter.op(alias, "【自动切号】exit 事件已处理过(event_id=$eventId)，已重发 summary_uploaded ACK")
                    return
                }
                if (
                    PrefsStore.getAutoSwitchMode() == "off" &&
                    !AutoSwitchLedger.hasPendingUpload() &&
                    !PrefsStore.hasAutoSwitchPendingSummaryUpload()
                ) {
                    PrefsStore.markAutoSwitchExitDone(eventId)
                    runCatching {
                        ApiService.modeAck(deviceId, alias, eventId, "summary_uploaded", sessionId)
                    }
                    PrefsStore.saveLastModeEventId(eventId)
                    return
                }
                val enterSessionId = PrefsStore.getAutoSwitchSessionId().orEmpty()
                beginAutoSwitchExitShutdown(alias)
                LogCenter.op(
                    alias,
                    "【自动切号】收到关闭指令，等待涨粉/独占收尾 enter_session=$enterSessionId exit_event=$sessionId",
                )
                awaitAutoSwitchExclusiveDrain(alias, maxMs = AUTO_SWITCH_EXIT_DRAIN_MS)
                val summaryUploaded = uploadAutoSwitchSessionSummary(alias, deviceId)
                if (summaryUploaded) {
                    runCatching {
                        ApiService.modeAck(deviceId, alias, eventId, "exiting_started", sessionId)
                    }.onFailure { err ->
                        LogCenter.op(alias, "自治模式 ACK(exiting_started) 失败：${err.message}")
                    }
                    runCatching {
                        ApiService.modeAck(deviceId, alias, eventId, "summary_uploaded", sessionId)
                    }.onSuccess {
                        LogCenter.op(alias, "【自动切号】收尾已上报云端")
                    }.onFailure { err ->
                        LogCenter.op(alias, "自治模式 ACK(summary_uploaded) 失败：${err.message}")
                    }
                    AutoSwitchLedger.clear()
                    AutoSwitchAccountActivityTracker.reset()
                    PrefsStore.clearAutoSwitchSessionContext()
                    PrefsStore.markAutoSwitchExitDone(eventId)
                    PrefsStore.saveLastModeEventId(eventId)
                } else {
                    PrefsStore.setAutoSwitchMode("off")
                    PrefsStore.setAutoSwitchPendingSummaryUpload(true)
                    runCatching {
                        ApiService.modeAck(deviceId, alias, eventId, "exiting_started", sessionId)
                    }.onFailure { err ->
                        LogCenter.op(alias, "自治模式 ACK(exiting_started) 失败：${err.message}")
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

    private suspend fun uploadDeviceLogsIfPullRequested(alias: String, deviceId: String, pullRequested: Boolean) {
        if (!pullRequested) return
        val lines = LocalDeviceLogWriter.readTodayFileLines()
        runCatching {
            ApiService.syncDeviceLogs(deviceId, alias, lines, replace = true)
        }.onSuccess {
            if (lines.isEmpty()) {
                LogCenter.op(alias, "已响应管理台日志拉取（今日暂无本地日志）")
            } else {
                LogCenter.op(alias, "已响应管理台日志拉取（今日 ${lines.size} 行）")
            }
        }.onFailure { err ->
            LogCenter.op(alias, "响应管理台日志拉取失败：${err.message}")
        }
    }

    private suspend fun uploadDeviceLogsIfRequested(alias: String, deviceId: String, response: CheckStartResponse) {
        uploadDeviceLogsIfPullRequested(alias, deviceId, response.log_pull_requested == true)
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
            runCatching { WorkerService.start(appContext) }
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
            runCatching { WorkerService.start(appContext) }
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

    /**
     * 登录已就绪（自动登录成功 / 云端 is_login=是 / 登录状态保障通过）且云端「基本资料已修改」=否 时衔接改资料。
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
        if (isPublishPostSwitchActive() || isMutualSwitchActive() || isBatchStreamActive()) {
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

    private suspend fun finishAutoSwitchCutSwitchAfterSuccess(
        alias: String,
        opId: String,
        targetRowIndex: Int,
        slotKey: String,
    ) {
        val sk = slotKey.trim()
        if (targetRowIndex >= 0 && sk.isNotEmpty()) {
            autoSwitchLocalScheduler.notifyCutSwitchAligned(targetRowIndex, sk)
            AutoSwitchBundleHolder.getBundle()?.accounts.orEmpty()
                .firstOrNull { (it.row_index ?: -1) == targetRowIndex }
                ?.let { acc ->
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
                "【自动切号】切号 OP 成功但未写 verified（row=$targetRowIndex slot=${sk.ifBlank { "-" }}）",
            )
        }
        PrefsStore.setTransientPausedForDestructiveOp(false)
        PrefsStore.setHardStopRequested(false)
        manuallyPaused = false
        PrefsStore.saveLocalStatus("running")
        updateRuntime(localStatus = "running", lastMessage = "自动切号自治：切号完成")
        LogCenter.op(
            alias,
            "【自动切号】切号完成 row=$targetRowIndex，继续自治调度（空档恢复涨粉）",
        )
        scope.launch {
            delay(500)
            autoSwitchLocalScheduler.requestImmediateTick()
            maybeStartGrowthInAutoSwitchIdle("切号完成")
        }
    }

    /** 切号还原阶段备份包缺失：记入 ledger 并改走 exclusive 自动登录。 */
    private suspend fun handleCutSwitchMissingBackupFallback(
        alias: String,
        deviceId: String,
        opId: String,
        payload: DeviceOpPayload?,
        restoreUsername: String,
        cloudBackupName: String,
        phase: String,
        internalDetail: String = "",
    ) {
        val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
        val userMessage = cutSwitchMissingBackupUserMessage(restoreUsername)
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
            message = "备份包本机不存在，已改走自动登录",
            slotKey = slotKey,
            username = restoreUsername,
            reportData = buildMap {
                put("op_type", "tiktok_restore")
                put("username", restoreUsername)
                put("backup_name", cloudBackupName)
                put("cut_switch_missing_backup_fallback", true)
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
                val loginOk = if (targetUser.isNotBlank()) {
                    ensureAccountLoggedIn(
                        alias = alias,
                        deviceId = deviceId,
                        opId = opId,
                        reason = "切号 exclusive 后登录校验",
                        strict = true,
                        forceRelogin = true,
                        preferredUsername = targetUser,
                    )
                } else {
                    LogCenter.op(alias, "【自动切号】exclusive 切号缺少目标 username，登录校验跳过")
                    false
                }
                if (loginOk) {
                    finishCutSwitchAfterSuccess(
                        alias,
                        deviceId,
                        opId,
                        reportResp,
                        targetRowIndex = targetRi,
                        slotKey = slotKey,
                    )
                } else {
                    notifyAutoSwitchCutSwitchStepFailed(payload)
                    finishCutSwitchAfterFailureFallback(alias, deviceId, opId, slotKey)
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
            if (sk.isNotEmpty()) {
                autoSwitchLocalScheduler.invalidateSlotAlignment(sk, "切号还原失败")
                AutoSwitchLedger.recordCutSwitchSlotAborted(sk, "切号还原失败")
            }
            if (PrefsStore.isHardStopRequested()) {
                LogCenter.op(alias, "【自动切号】切号已硬停，中止重试")
            } else {
                LogCenter.op(alias, "【自动切号】切号还原失败，等待下一 tick 重试")
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
            finishAutoSwitchCutSwitchAfterSuccess(alias, opId, targetRowIndex, slotKey)
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
            ensureAccountLoggedIn(
                alias = alias,
                deviceId = deviceId,
                opId = opId,
                reason = "切号后 is_login=否",
                strict = false,
                forceRelogin = false,
                resumeFromLoginEntryScreen = false,
            )
        } else {
            ensureLoginSafeguardMeasure(alias, deviceId, "切号完成", strict = false)
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
            val loginOk = ensureAccountLoggedIn(
                alias = alias,
                deviceId = deviceId,
                opId = "login_retry_${System.currentTimeMillis()}",
                reason = reason,
                strict = true,
                forceRelogin = nextForceRelogin,
                resumeFromLoginEntryScreen = nextResumeFromEntry,
                preferredUsername = expected,
            )
            if (loginOk) {
                autoLoginUnrecoverableCodeFetchAttempts = 0
                if (resumeRunningOnSuccess) {
                    resumeRunningForCloudTask(alias, deviceId, "自动登录成功（$reason）")
                }
                return AccountLoginRetryOutcome.LoggedIn
            }
            nextForceRelogin = false
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

    /**
     * 自动切号 tick 发作品入队前：bundle is_login + OCR 登录状态保障。
     * 失败时不标记 publish_aborted，下分钟 tick 可重试。
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
                    return false
                }
                AccountLoginRetryOutcome.Aborted -> {
                    LogCenter.op(alias, "【自动切号】发作品入队前自动登录中止 slot=$sk")
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
        }
        return ocrOk
    }

    /**
     * 高亮已与时段一致但未 verified：登录 + OCR 保障后写对齐证明（不做 clear/restore）。
     */
    private suspend fun verifySlotAlignmentLightweightLogin(
        alias: String,
        deviceId: String,
        targetUsername: String,
        slotKey: String,
        targetRowIndex: Int,
    ): Boolean {
        val user = targetUsername.trim()
        val sk = slotKey.trim()
        if (user.isBlank() || sk.isEmpty() || targetRowIndex < 0 || deviceId.isBlank()) return false
        PrefsStore.setLogContextUsername(user)
        applyBundledConfigFromAutoSwitch(user, logToUser = false)

        if (!isAssignedAccountLoggedIn(alias, deviceId, preferredUsername = user)) {
            when (
                ensureAccountLoggedInWithRetry(
                    alias = alias,
                    deviceId = deviceId,
                    reason = "时段对齐校验",
                    expectedUsername = user,
                )
            ) {
                AccountLoginRetryOutcome.LoggedIn -> Unit
                AccountLoginRetryOutcome.AccountChanged -> {
                    LogCenter.op(alias, "【自动切号】时段对齐校验账号不符 slot=$sk")
                    autoSwitchLocalScheduler.invalidateSlotAlignment(sk, "时段对齐校验账号切换")
                    return false
                }
                AccountLoginRetryOutcome.Aborted -> {
                    LogCenter.op(alias, "【自动切号】时段对齐校验自动登录中止 slot=$sk")
                    return false
                }
            }
        }

        val ocrOk = launchTikTokWithLoginSafeguardWithRetry(
            alias = alias,
            deviceId = deviceId,
            tag = "时段对齐校验",
            forceStopBeforeLaunch = false,
            safeguardSource = "时段对齐校验",
            strict = true,
        )
        if (!ocrOk) {
            LogCenter.op(alias, "【自动切号】时段对齐校验 OCR 未通过 slot=$sk account=$user")
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
        val expectedUser = PrefsStore.getAutoSwitchPublishAccountUsername()
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
                PrefsStore.clearAutoSwitchPublishContext()
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
        val expectedUser = PrefsStore.getAutoSwitchPublishAccountUsername()
        if (!isAssignedAccountLoggedIn(alias, deviceId, preferredUsername = expectedUser)) {
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
                            slotKey = PrefsStore.getAutoSwitchPublishSlotKey().orEmpty(),
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
            LogCenter.op(
                alias,
                "涨粉：$reason，自治模式不全局暂停（调度继续，待下次重试登录状态保障）",
            )
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
     * @param strict true：自动登录失败等返回 false；false：切号后尽力登录，凭证不齐等仍可返回 true。
     */
    private suspend fun ensureLoginSafeguardMeasure(
        alias: String,
        deviceId: String,
        source: String,
        strict: Boolean = true,
    ): Boolean {
        fetchConfig(logToUser = false)
        return try {
            when (executor.runLoginSafeguardMeasure(appContext, source)) {
                LoginSafeguardOutcome.Ready -> {
                    LogCenter.op(alias, "登录状态保障($source)：界面可继续，已回 Home")
                    maybeRunPendingBasicProfileEditAfterLoginVerified(
                        alias = alias,
                        deviceId = deviceId,
                        parentOpId = "safeguard_ready_${System.currentTimeMillis()}",
                        trigger = "login_safeguard_ready",
                        skipOuterWarmup = false,
                        loginVerified = true,
                    )
                    true
                }
                LoginSafeguardOutcome.RequiresAutoLogin -> {
                    LogCenter.op(alias, "登录状态保障($source)：界面需登录，启动自动登录")
                    val opId = "safeguard_${System.currentTimeMillis()}"
                    ensureAccountLoggedIn(
                        alias = alias,
                        deviceId = deviceId,
                        opId = opId,
                        reason = "登录状态保障($source)",
                        strict = strict,
                        forceRelogin = true,
                        resumeFromLoginEntryScreen = true,
                    )
                }
                LoginSafeguardOutcome.Failed -> {
                    LogCenter.op(alias, "登录状态保障($source)：执行失败")
                    false
                }
            }
        } catch (logout: LoginSafeguardRequiresAutoLoginException) {
            LogCenter.op(alias, "登录状态保障($source)：OCR 见 Add another account，从登录入口续接自动登录")
            ensureAccountLoggedIn(
                alias = alias,
                deviceId = deviceId,
                opId = "safeguard_add_account_${System.currentTimeMillis()}",
                reason = "登录状态保障($source)-Add another account",
                strict = strict,
                forceRelogin = true,
                resumeFromLoginEntryScreen = true,
            )
        } catch (logout: AccountStatusLoggedOutException) {
            LogCenter.op(alias, "登录状态保障($source)：Account status 掉登录，重新自动登录")
            ensureAccountLoggedIn(
                alias = alias,
                deviceId = deviceId,
                opId = "safeguard_acct_status_${System.currentTimeMillis()}",
                reason = "登录状态保障($source)-Account status",
                strict = strict,
                forceRelogin = true,
            )
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
        val launched = executor.launchTikTokWithForegroundVerification(
            ctx = appContext,
            alias = alias,
            tag = tag,
            forceStopBeforeLaunch = forceStopBeforeLaunch,
            timingConfig = _config.value,
        )
        if (!launched) {
            LogCenter.op(alias, "登录状态保障：TikTok 拉起失败（$safeguardSource）")
            return false
        }
        return ensureLoginSafeguardMeasure(alias, deviceId, safeguardSource, strict)
    }

    /** 整段登录状态保障（拉起 → 前台校验 → OCR）外层重试；仅全部失败后才由调用方 pause。 */
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
        for (attempt in 1..maxAttempts) {
            if (shouldHonorUserHardStop() || manuallyPaused) return false
            if (attempt > 1) {
                LogCenter.op(
                    alias,
                    "登录状态保障：第 $attempt/$maxAttempts 次重试（$safeguardSource）",
                )
                if (!delayUnlessHardStop(delayBetweenMs)) return false
            }
            if (
                launchTikTokWithLoginSafeguard(
                    alias = alias,
                    deviceId = deviceId,
                    tag = tag,
                    forceStopBeforeLaunch = forceStopBeforeLaunch,
                    safeguardSource = safeguardSource,
                    strict = strict,
                )
            ) {
                return true
            }
        }
        LogCenter.op(alias, "登录状态保障：$maxAttempts 次尝试均失败（$safeguardSource）")
        return false
    }

    private suspend fun recoverFromAccountStatusLogout(alias: String, deviceId: String, source: String): Boolean {
        LogCenter.op(alias, "掉登录恢复：终止当前步骤，重新自动登录（来源=$source）")
        val opId = "acct_status_${System.currentTimeMillis()}"
        return ensureAccountLoggedIn(
            alias = alias,
            deviceId = deviceId,
            opId = opId,
            reason = "掉登录恢复($source)",
            strict = true,
            forceRelogin = true,
        )
    }

    /**
     * Account status 弹窗路径会先点 OK 并冷启；Add another account / 登录状态保障路径则直接从当前登录入口续接。
     */
    private suspend fun recoverFromSafeguardStyleLogout(alias: String, deviceId: String, source: String): Boolean {
        LogCenter.op(
            alias,
            "掉登录恢复（登录状态保障路径）：终止当前步骤，从登录入口续接自动登录（来源=$source）",
        )
        val opId = "safeguard_logout_${System.currentTimeMillis()}"
        return ensureAccountLoggedIn(
            alias = alias,
            deviceId = deviceId,
            opId = opId,
            reason = "掉登录恢复($source)",
            strict = true,
            forceRelogin = true,
            resumeFromLoginEntryScreen = true,
        )
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
            maybeRunPendingBasicProfileEditAfterLoginVerified(
                alias = alias,
                deviceId = deviceId,
                parentOpId = opId,
                trigger = "login_cloud_is_login",
                skipOuterWarmup = false,
                loginVerified = true,
            )
            return true
        }
        val username = accountResp.username.orEmpty().trim()
        val email = accountResp.email.orEmpty().trim()
        val password = accountResp.password.orEmpty().trim()
        val api = accountResp.api.orEmpty().trim()
        if (username.isBlank() || email.isBlank() || password.isBlank()) {
            LogCenter.op(alias, "[OP][$opId] 自动登录($reason)：凭证不完整（username/email/password）")
            return !strict
        }
        if (api.isBlank()) {
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
                ),
                resumeFromLoginEntryScreen = resumeFromLoginEntryScreen,
                flowKind = flowKind,
            )
        }.getOrElse { err ->
            LogCenter.op(alias, "[OP][$opId] 自动登录($reason)异常：${err.message}")
            false
        }
        autoLoginActive = false
        if (loginOk) {
            lastEnsureLoginPerformedFreshAutoLogin = true
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
            maybeRunPendingBasicProfileEditAfterLoginVerified(
                alias = alias,
                deviceId = deviceId,
                parentOpId = opId,
                trigger = profileTrigger,
                skipOuterWarmup = true,
                loginVerified = true,
            )
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
            startPullLoop()
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

    /** 涨粉拉取/执行前应中止（硬停、手动暂停、独占抢占、非 running、自治独占 OP）。 */
    private fun shouldAbortPullExecution(): Boolean {
        if (manuallyPaused) return true
        if (shouldHonorUserHardStop()) return true
        if (PrefsStore.getAutoSwitchMode() == "exiting") return true
        if (PrefsStore.isAutoSwitchExclusivePreempt()) return true
        if (basicProfileEditRunning) return true

        if (PrefsStore.getLocalStatus() != "running") return true
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            if (!autoSwitchLocalScheduler.shouldAllowGrowthBaseline()) return true
            if (isPublishPostSwitchActive()) return true
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
        return ApiService.reportDeviceOp(deviceId, alias, opId, status, message, data)
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
        val sessionId = PrefsStore.getAutoSwitchSessionId().orEmpty()
        val revision = PrefsStore.getAutoSwitchBundleRevision()
        return runCatching {
            val resp = ApiService.fetchAutoSwitchBundle(deviceId, alias, sessionId, revision)
            if (resp.code == 0 && resp.bundle != null) {
                applyAutoSwitchBundle(alias, deviceId, resp.bundle, revision, startSchedulerIfStopped = false)
                autoSwitchLocalScheduler.requestImmediateTick()
                maybeStartGrowthInAutoSwitchIdle("周计划刷新")
                true
            } else {
                LogCenter.op(alias, "【自动切号】周计划 bundle 拉取失败：${resp.msg}")
                false
            }
        }.getOrElse { err ->
            LogCenter.op(alias, "【自动切号】周计划 bundle 拉取异常：${err.message}")
            false
        }
    }

    private fun applyAutoSwitchBundle(
        alias: String,
        deviceId: String,
        bundle: AutoSwitchBundle,
        revision: Int,
        startSchedulerIfStopped: Boolean = true,
    ) {
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
        if (wasSchedulerRunning) {
            autoSwitchLocalScheduler.refreshWeeklyBundle(bundle)
        } else if (startSchedulerIfStopped) {
            autoSwitchLocalScheduler.start(alias, deviceId, bundle, revision = revision)
        }
        if (revision != PrefsStore.getAutoSwitchLastConfigFetchRevision()) {
            PrefsStore.setAutoSwitchLastConfigFetchRevision(-1)
        }
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
        scheduleBackupDirectoryReconcile(
            alias = alias,
            deviceId = deviceId,
            accounts = bundle.accounts.orEmpty(),
            logPrefix = "【备份目录整理】",
        )
        bundle.smart_template_debug?.toAuditLines()?.takeIf { it.isNotEmpty() }?.let { lines ->
            val label = if (!wasSchedulerRunning && startSchedulerIfStopped) "enter 打包" else "bundle 更新"
            LogCenter.op(alias, "【自动切号】智能模板 $label：\n${lines.joinToString("\n")}")
        }
    }

    private fun beijingMinuteOfDayForBundle(): Int {
        val cal = java.util.Calendar.getInstance(beijingTz)
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    private suspend fun resumeAutoSwitchSchedulerIfNeeded() {
        if (PrefsStore.getAutoSwitchMode() != "on") return
        if (autoSwitchLocalScheduler.isRunning()) return
        AutoSwitchLedger.restoreFromPrefs()
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return
        val sessionId = PrefsStore.getAutoSwitchSessionId().orEmpty()
        val revision = PrefsStore.getAutoSwitchBundleRevision()
        runCatching {
            val resp = ApiService.fetchAutoSwitchBundle(deviceId, alias, sessionId, revision)
            if (resp.code == 0 && resp.bundle != null) {
                applyAutoSwitchBundle(alias, deviceId, resp.bundle, revision)
                scheduleAutoSwitchDailyReconcileTargetIfNeeded(alias)
                LogCenter.op(alias, "【自动切号】已恢复本地调度（重启后重拉 bundle）")
            } else {
                LogCenter.op(alias, "【自动切号】恢复本地调度失败：${resp.msg}")
            }
        }.onFailure { err ->
            LogCenter.op(alias, "【自动切号】恢复本地调度异常：${err.message}")
        }
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
                    LogCenter.op(alias, summary.human_log)
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
            LogCenter.op(alias, summary.human_log)
        }
        return uploaded
    }

    private suspend fun tryUploadPendingAutoSwitchSummary() {
        if (!PrefsStore.hasAutoSwitchPendingSummaryUpload() && !AutoSwitchLedger.hasPendingUpload()) return
        if (PrefsStore.getAutoSwitchMode() == "on") return
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return
        if (uploadAutoSwitchSessionSummary(alias, deviceId)) {
            AutoSwitchLedger.clear()
            AutoSwitchAccountActivityTracker.reset()
            PrefsStore.clearAutoSwitchSessionContext()
            LogCenter.op(alias, "【自动切号】待上报会话总结已成功补传")
        }
    }

    private suspend fun handleDeviceOp(alias: String, deviceId: String, op: DeviceOp) {
        val opId = op.op_id
        val payloadEarly = op.payload
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
            op.op_type !in setOf("batch_stream_stop", "batch_stream_start")
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
        if (
            op.op_type == "mutual_click_start" ||
            op.op_type == "mutual_click_comment_only_start" ||
            op.op_type == "mutual_click_follow_only_start"
        ) {
            val commentOnly = op.op_type == "mutual_click_comment_only_start"
            val followOnly = op.op_type == "mutual_click_follow_only_start"
            PrefsStore.setMutualCommentOnlySession(commentOnly)
            PrefsStore.setMutualFollowOnlySession(followOnly)
            val sessionLabel = when {
                followOnly -> "仅关注"
                commentOnly -> "仅评论"
                else -> "完整"
            }
            val mutualLogLine =
                "[OP][$opId] 收到互点引流指令（$sessionLabel），开始引流会话"
            LogCenter.op(alias, mutualLogLine)
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
            val pubPkg = payload?.package_name?.trim().orEmpty()
            val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
            val isAutoSwitchPublish = payload?.source == "auto_switch"
            if (isAutoSwitchPublish && slotKey.isNotBlank()) {
                if (AutoSwitchLedger.isPublishSlotDone(slotKey)) {
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
                if (AutoSwitchLedger.isPublishSlotAborted(slotKey)) {
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
                if (AutoSwitchLedger.isPublishSlotInflight(slotKey)) {
                    val activeOpId = PrefsStore.getPublishPostTaskOpId().orEmpty()
                    if (activeOpId.isNotBlank() && activeOpId != opId) {
                        reportDeviceOpToCloud(
                            deviceId = deviceId,
                            alias = alias,
                            opId = opId,
                            status = "success",
                            message = "时段发作品执行中，跳过重复入队",
                            data = publishPostStartReportData(deviceId, opId, payload, slotKey),
                        )
                        return
                    }
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
            if (!prevTaskOpId.isNullOrBlank() && prevTaskOpId != opId) {
                PrefsStore.clearPublishPostResumeProgress(publishAccountKey)
                LogCenter.op(
                    alias,
                    "[OP][$opId] 新发作品指令，已清除上一指令（$prevTaskOpId）的断点续发",
                )
            }
            PrefsStore.setPublishPostTaskOpId(opId)
            PrefsStore.setPublishPostPackageName(pubPkg)
            val publishCount = payload?.publish_count
            PrefsStore.setPublishPostTargetCount(publishCount)
            PrefsStore.setPublishPostRequested(true)
            PrefsStore.setPublishPostPending(true)
            if (payload?.source == "auto_switch") {
                PrefsStore.setAutoSwitchPublishAccountUsername(payload.account_username)
                PrefsStore.setAutoSwitchPublishSlotKey(payload.auto_switch_slot_key)
                PrefsStore.setPublishPostSource("auto_switch")
            } else {
                PrefsStore.clearAutoSwitchPublishContext()
            }
            val countHint =
                if (publishCount != null && publishCount > 0) "，目标发布 $publishCount 条" else ""
            val autoSwitchHint =
                if (payload?.source == "auto_switch") "（自动切号调度）" else ""
            val lineQueued =
                "[OP][$opId] 发作品指令$autoSwitchHint：互点切换任务执行中，发作品需排队在互点模式结束之后执行$countHint"
            val lineStart =
                "[OP][$opId] 发作品指令$autoSwitchHint 已收到，准备切入发作品入口流程$countHint"
            if (mutualSwitchJob?.isActive == true) {
                PrefsStore.setPublishPostQueuedAfterMutual(true)
                LogCenter.op(alias, lineQueued)
                reportDeviceOpToCloud(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = "success",
                    message = "已排队：互点会话结束后将执行发作品入口",
                    data = publishPostStartReportData(deviceId, opId, payload, slotKey).toMutableMap().apply {
                        put("publish_queued_after_mutual", true)
                        put("operation_logs", listOf(lineQueued))
                    },
                )
                return
            }
            PrefsStore.setPublishPostQueuedAfterMutual(false)
            LogCenter.op(alias, lineStart)
            if (isAutoSwitchPublish && slotKey.isNotBlank()) {
                AutoSwitchLedger.record(
                    opId = opId,
                    opType = "publish_post_start",
                    status = "running",
                    message = "发作品入口已触发",
                    slotKey = slotKey,
                    username = payload?.account_username?.trim().orEmpty(),
                    phaseOverride = "running",
                )
            }
            triggerPublishPostNow(alias)
            reportDeviceOpToCloud(
                deviceId = deviceId,
                alias = alias,
                opId = opId,
                status = "success",
                message = "已收到并发作品入口任务",
                data = publishPostStartReportData(deviceId, opId, payload, slotKey),
            )
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
                var successOverall = false
                var stopAfterProfileEditOk = false

                for (outer in 1..maxOuter) {
                    if (PrefsStore.isHardStopRequested()) {
                        opLog("修改基本资料：检测到硬停，中止外轮（不再重试 OCR）")
                        lastChain = ProfileEditChainResult(success = false, failureStep = "hard_stop")
                        runSu("am force-stop $packageName")
                        break
                    }
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
                    lastRatioTapOk = runSu("input tap $rtx $rty")

                    if (!lastStopOk || !lastLaunchOk || !lastRatioTapOk) {
                        opLog("外轮$outer：准备阶段未就绪，重试")
                        lastChain = ProfileEditChainResult(
                            success = false,
                            failureStep = "warmup_outer_$outer",
                            editOcrPreviews = listOf("stop=$lastStopOk launch=$lastLaunchOk ratioTap=$lastRatioTapOk"),
                        )
                        continue
                    }

                    val chain = try {
                        executor.profileEditRunOcrChain(appContext, _config.value)
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
                    if (chain.failureStep == "hard_stop" || PrefsStore.isHardStopRequested()) {
                        opLog("修改基本资料：OCR 链被硬停打断 step=${chain.failureStep}")
                        runSu("am force-stop $packageName")
                        break
                    }
                    val bioPartsOk = chain.bioAfterPart1Ok && chain.bioAfterPart2Ok
                    if (chain.success && bioPartsOk) {
                        successOverall = true
                        opLog("修改基本资料完成（外轮 $outer/$maxOuter）")
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
                        "已完成：Bio 后①② 均成功，已关闭 TikTok 后台；云端将「基本资料已修改」标为「是」"
                    } else if (chain?.failureStep == "hard_stop") {
                        "已硬停：修改基本资料流程被用户中止，未标「基本资料已修改」"
                    } else if (chain?.bioAfterPart1Ok == true && chain.bioAfterPart2Ok != true) {
                        "未完成：Bio 后① 已完成但② 未成功（${chain.failureStep}），未标「基本资料已修改」"
                    } else {
                        "未完成：${chain?.failureStep ?: "outer_or_unknown"}（已最多外轮 $maxOuter 次）"
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
                        "profile_edit_outer_max" to maxOuter,
                        "profile_edit_success" to successOverall,
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
                if (!clearOk && isCutSwitch && retryUntilSuccess) {
                    notifyAutoSwitchCutSwitchStepFailed(payload)
                }
                if (clearOk && isCutSwitch) {
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
                val reportUsername = payload?.username?.trim().orEmpty()
                val backupName = UnifiedBackupPaths.buildTikTokBackupArchiveName(
                    alias = alias,
                    username = reportUsername,
                    deviceId = deviceId,
                    timestamp = ts,
                )
                if (backupName == null) {
                    val isCutSwitchEarly = !payload?.cut_switch_after_backup.isNullOrBlank()
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
                var dataDirKb: Long? = null
                var attempt = 0
                while (true) {
                    attempt++
                    cutSwitchStep = 0
                    if (retryUntilSuccess && attempt > 1) {
                        opStep(cutStepLabel("切号重试 第${attempt}轮"))
                    } else {
                        opStep(cutStepLabel(if (isCutSwitch) "切号：开始备份当前账号" else "开始备份并清理"))
                        if (isCutSwitch) {
                            opStep(cutStepLabel("备份账号：@$reportUsername"))
                            if (suppressLocalAutoSwitchUiLog) {
                                LogCenter.op(alias, "【切号】备份 @$reportUsername")
                            }
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

                    val backupOk = if (!stopOk) {
                        false
                    } else if (backupSkipped) {
                        opStep(
                            cutStepLabel("数据目录 ${dataDirKb ?: 0}KB < ${DeviceBackupShell.MIN_DATA_DIR_KB_FOR_BACKUP}KB，跳过打包备份"),
                        )
                        true
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
                            }
                            tarOk
                        }
                    }

                    val clearOk = if (backupOk) {
                        opStep(cutStepLabel(if (backupSkipped) "第3步：清理 TikTok 数据" else "第4步：清理 TikTok 数据"))
                        runSu("pm clear $packageName")
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
                        !backupOk && !backupSkipped ->
                            lastCutSwitchFailureReason.ifBlank {
                                "第3步打包备份失败，目标：$backupPath（备份目录：$backupDir）"
                            }
                        !clearOk ->
                            "第${if (backupSkipped) 3 else 4}步 pm clear 失败（需 root）：$packageName"
                        else -> "切号备份/清理未知失败"
                    }
                    if (!delayAutoSwitchCutSwitchRetryIfRetryable(alias, attempt, failReason)) break
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
                    val bundleAccounts = AutoSwitchBundleHolder.getBundle()?.accounts.orEmpty()
                    val reconcileAccounts = if (bundleAccounts.isNotEmpty()) {
                        bundleAccounts.map { acc ->
                            val u = acc.username?.trim()?.removePrefix("@").orEmpty()
                            if (u.equals(reportUsername, ignoreCase = true)) {
                                acc.copy(backup_name = backupName, backup_path = backupPath)
                            } else {
                                acc
                            }
                        }
                    } else {
                        listOf(
                            AutoSwitchAccount(
                                username = reportUsername,
                                backup_name = backupName,
                                backup_path = backupPath,
                            ),
                        )
                    }
                    runBackupDirectoryReconcile(
                        alias = alias,
                        deviceId = deviceId,
                        accounts = reconcileAccounts,
                        extraKeepAbsolutePath = backupPath,
                        logPrefix = if (suppressLocalAutoSwitchUiLog) "【切号·备份整理】" else "【备份目录整理】",
                    )
                }
                opDone(
                    ok,
                    when {
                        !ok -> if (isCutSwitch) "切号步骤失败" else "备份或清理失败"
                        backupSkipped && isCutSwitch -> "切号步骤完成"
                        backupSkipped -> "数据过小已跳过备份，清理完成"
                        isCutSwitch -> "切号步骤完成"
                        else -> "备份并清理完成"
                    },
                    if (isCutSwitch) "切号步骤失败" else "备份或清理失败",
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
                            if (isCutSwitch) "切号步骤失败" else "备份或清理失败",
                        )
                        backupSkipped -> if (isCutSwitch) "切号步骤完成" else "数据过小已跳过备份，清理完成"
                        isCutSwitch -> "切号步骤完成"
                        else -> "备份并清理完成"
                    },
                    data = reportData,
                )
                if (ok && isCutSwitch) {
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
                    notifyAutoSwitchCutSwitchStepFailed(payload)
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

                    val restoreDataLabel =
                        if (skipPmClear) restoreStepLabel("第3步：恢复账号数据") else "第4步：恢复账号数据"
                    val restoreDataOk = backupExists && runRestoreShellStep(::opStep, restoreDataLabel) {
                        runSu("tar -xzf $qBackupPath -C /data/data")
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
                if (skipPmClear) {
                    if (restoreOk) {
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
                            notifyAutoSwitchCutSwitchStepFailed(payload)
                        }
                        finishCutSwitchAfterFailureFallback(
                            alias,
                            deviceId,
                            opId,
                            payload?.auto_switch_slot_key?.trim().orEmpty(),
                        )
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
            ExecutionScope.enter()
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
                if (!deviceId.isNullOrBlank()) {
                    fetchConfig(configQueryRunMode = ServerRunMode.MUTUAL)
                }
                val deviceIdForMutual = deviceId.orEmpty()
                if (deviceIdForMutual.isNotBlank() && !ensureLoggedInBeforeExclusiveTask(alias, deviceIdForMutual, "互点切换")) {
                    LogCenter.op(alias, "互点切换：is_login 未就绪，已跳过互点入口流程")
                    return@launch
                }
                var switchResult = runCatching {
                    executor.executeForUsername("__mutual_switch__", _config.value)
                }.getOrElse { err ->
                    when (err) {
                        is LoginSafeguardRequiresAutoLoginException -> SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE
                        is AccountStatusLoggedOutException -> {
                            if (deviceIdForMutual.isNotBlank()) {
                                recoverFromAccountStatusLogout(alias, deviceIdForMutual, "互点切换")
                            }
                            "互点流程掉登录：${err.message ?: "account_status"}"
                        }
                        else -> "互点流程切换异常：${err.message ?: "unknown"}"
                    }
                }
                if (switchResult == SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE && deviceIdForMutual.isNotBlank()) {
                    val loginReady = ensureAccountLoggedInWithRetry(
                        alias = alias,
                        deviceId = deviceIdForMutual,
                        reason = "互点保障",
                        forceRelogin = true,
                        resumeFromLoginEntryScreen = true,
                    ) == AccountLoginRetryOutcome.LoggedIn
                    if (loginReady) {
                        switchResult = runCatching {
                            executor.executeForUsername("__mutual_switch__", _config.value)
                        }.getOrElse { err ->
                            "互点流程切换异常：${err.message ?: "unknown"}"
                        }
                    } else {
                        switchResult = "互点：登录保障后仍未登录"
                    }
                }
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
                ExecutionScope.exit()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (!PrefsStore.isHardStopRequested() && !manuallyPaused && PrefsStore.getLocalStatus() == "running") {
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

    private suspend fun reportAutoSwitchPublishOutcomeIfNeeded(
        alias: String,
        deviceId: String,
        opId: String,
        completed: Boolean,
        loginFailed: Boolean,
        accountChanged: Boolean,
        message: String,
        slotKey: String = PrefsStore.getAutoSwitchPublishSlotKey().orEmpty(),
    ) {
        val slotKeyResolved = slotKey.trim().ifBlank { PrefsStore.getAutoSwitchPublishSlotKey().orEmpty() }
        val account = PrefsStore.getAutoSwitchPublishAccountUsername().orEmpty()
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
        val accountCloud = account.ifBlank { PrefsStore.getAutoSwitchPublishAccountUsername().orEmpty() }
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
        val accountKey = PrefsStore.getAutoSwitchPublishAccountUsername()
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() }
            ?: alias
        val keepForResume = !completed && !loginFailed && !accountChanged &&
            PrefsStore.getPublishPostResumeNextIndex(accountKey) > 0
        if (keepForResume) {
            PrefsStore.setPublishPostPending(true)
            PrefsStore.setPublishPostRequested(true)
        } else {
            PrefsStore.clearPublishPostState()
        }
    }

    private fun triggerPublishPostNow(alias: String) {
        if (publishPostSwitchJob?.isActive == true) {
            LogCenter.op(alias, "发作品切换任务已在执行中，忽略重复触发")
            return
        }
        publishPostSwitchJob = scope.launch {
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
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
                "发作品切换任务准备执行特殊用户名 __publish_post__（素材准备阶段不暂停涨粉）：requested=${PrefsStore.isPublishPostRequested()} pending=${PrefsStore.isPublishPostPending()}",
            )
            val deviceIdForPublish = PrefsStore.getDeviceId().orEmpty()
            val publishOpId = PrefsStore.getPublishPostTaskOpId().orEmpty()
            val publishSlotKeyAtStart = PrefsStore.getAutoSwitchPublishSlotKey()?.trim().orEmpty()
            val autoSwitchPublishAtStart =
                PrefsStore.getPublishPostSource() == "auto_switch" && publishSlotKeyAtStart.isNotBlank()
            if (deviceIdForPublish.isBlank()) {
                LogCenter.op(alias, "发作品切换任务：未绑定 deviceId，已跳过")
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
                }
                return@launch
            }
            var switchResult = runCatching {
                executor.executeForUsername("__publish_post__", _config.value)
            }.getOrElse { err ->
                when (err) {
                    is LoginSafeguardRequiresAutoLoginException -> SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE
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
                val expectedUser = PrefsStore.getAutoSwitchPublishAccountUsername()
                val loginReady = ensureAccountLoggedInWithRetry(
                    alias = alias,
                    deviceId = deviceIdForPublish,
                    reason = "发作品保障",
                    expectedUsername = expectedUser,
                    forceRelogin = true,
                    resumeFromLoginEntryScreen = true,
                ) == AccountLoginRetryOutcome.LoggedIn
                if (loginReady) {
                    switchResult = runCatching {
                        executor.executeForUsername("__publish_post__", _config.value)
                    }.getOrElse { err ->
                        "发作品入口流程异常：${err.message ?: "unknown"}"
                    }
                } else {
                    switchResult = "发作品：登录保障后仍未登录"
                    if (publishOpId.isNotBlank()) {
                        reportAutoSwitchPublishOutcomeIfNeeded(
                            alias = alias,
                            deviceId = deviceIdForPublish,
                            opId = publishOpId,
                            completed = false,
                            loginFailed = true,
                            accountChanged = false,
                            message = switchResult,
                        )
                    }
                    LogCenter.op(alias, switchResult)
                    updateRuntime(lastMessage = switchResult)
                    return@launch
                }
            }
            val publishCompleted = switchResult.contains("已完成")
            if (autoSwitchPublishAtStart && publishOpId.isNotBlank()) {
                reportAutoSwitchPublishOutcomeIfNeeded(
                    alias = alias,
                    deviceId = deviceIdForPublish,
                    opId = publishOpId,
                    slotKey = publishSlotKeyAtStart,
                    completed = publishCompleted,
                    loginFailed = false,
                    accountChanged = false,
                    message = switchResult,
                )
            } else if (PrefsStore.isAutoSwitchPublishTask() && publishOpId.isNotBlank()) {
                reportAutoSwitchPublishOutcomeIfNeeded(
                    alias = alias,
                    deviceId = deviceIdForPublish,
                    opId = publishOpId,
                    completed = publishCompleted,
                    loginFailed = false,
                    accountChanged = false,
                    message = switchResult,
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
                cleanupOrphanedAutoSwitchPublishState(alias)
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
                        val resumeAccount = PrefsStore.getAutoSwitchPublishAccountUsername()?.trim()
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
                    } else if (PrefsStore.getLocalStatus() == "running") {
                        LogCenter.op(alias, "发作品切换结束后恢复拉取循环")
                        startPullLoop()
                    }
                }
            }
        }
    }

    /** 发作品协程异常/取消后，防止 pending 或 ledger 残留阻塞自治调度。 */
    private fun cleanupOrphanedAutoSwitchPublishState(alias: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (isPublishPostSwitchActive()) return
        val slotKey = PrefsStore.getAutoSwitchPublishSlotKey().orEmpty()
        val sk = slotKey.ifBlank { PrefsStore.getAutoSwitchLastSlotKey().orEmpty() }
        val ledgerInflight = sk.isNotBlank() && AutoSwitchLedger.isPublishSlotInflight(sk)
        val hasLocalPublishState = PrefsStore.isAutoSwitchPublishTask() || PrefsStore.isPublishPostPending()
        if (!ledgerInflight && !hasLocalPublishState) return
        val accountKey = PrefsStore.getAutoSwitchPublishAccountUsername()
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() }
            ?: alias
        if (PrefsStore.getPublishPostResumeNextIndex(accountKey) > 0) return
        if (ledgerInflight && sk.isNotBlank()) {
            abortStaleAutoSwitchPublishInflight(sk)
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

    private fun abortStaleAutoSwitchPublishInflight(
        slotKey: String,
        message: String = "发作品本机已结束，清理残留 in-flight",
    ) {
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        AutoSwitchLedger.abortStalePublishSlot(sk, message)
        PrefsStore.clearPublishPostState()
        autoSwitchLocalScheduler.notifyPublishOutcome(
            slotKey = sk,
            completed = false,
            loginFailed = false,
            accountChanged = false,
        )
    }

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
            var successOverall = false
            var stopAfterProfileEditOk = false
            val ratioTapX = 0.97f
            val ratioTapY = 0.95f
            var lastRatioTapPx = Pair(0, 0)
            var lastStopOk = false
            var lastLaunchOk = false
            var lastRatioTapOk = false
            val maxOuter = 3

            suspend fun runProfileEditChainOnce(): ProfileEditChainResult =
                try {
                    executor.profileEditRunOcrChain(appContext, _config.value)
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
                val skipWarmupThisRound = skipOuterWarmup && outer == 1
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
                    lastRatioTapOk = runSu("input tap $rtx $rty")

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

                val chain = runProfileEditChainOnce()
                lastChain = chain
                if (chain.failureStep == "hard_stop" || PrefsStore.isHardStopRequested()) {
                    opLog("修改基本资料：OCR 链被硬停打断 step=${chain.failureStep}")
                    runSu("am force-stop $packageName")
                    break
                }
                val bioPartsOk = chain.bioAfterPart1Ok && chain.bioAfterPart2Ok
                if (chain.success && bioPartsOk) {
                    successOverall = true
                    opLog("修改基本资料完成（外轮 $outer/$maxOuter）")
                    stopAfterProfileEditOk = runSu("am force-stop $packageName").also { delay(1200) }
                    break
                }
                if (chain.bioAfterPart1Ok && !chain.bioAfterPart2Ok) {
                    opLog("外轮$outer：Bio ① 完成、② 未完成（${chain.failureStep}）")
                } else {
                    opLog("外轮$outer：失败（${chain.failureStep}）")
                }
            }

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
                        "切号后衔接改资料成功，云端将「基本资料已修改」标为「是」"
                    chain.failureStep == "hard_stop" ->
                        "切号后改资料已硬停"
                    chain.bioAfterPart1Ok && !chain.bioAfterPart2Ok ->
                        "切号后改资料未完成：Bio 后① 已完成但② 未成功（${chain.failureStep}），未标「基本资料已修改」"
                    else ->
                        "切号后改资料未完成：${chain.failureStep}（已最多外轮 $maxOuter 次）"
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
                    "profile_edit_success" to successOverall,
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
                LogCenter.op(
                    alias,
                    "涨粉：OCR 见 Add another account（与登录状态保障一致），终止 $username 并从登录入口续接自动登录",
                )
                pendingUsernames.add(0, username)
                ensureGrowthAccountLoggedInWithRetry(
                    alias = alias,
                    deviceId = deviceId,
                    reason = "掉登录后",
                    forceRelogin = true,
                    resumeFromLoginEntryScreen = true,
                )
                return
            }
            if (errThrowable is AccountStatusLoggedOutException) {
                LogCenter.op(alias, "涨粉：掉登录，终止 $username：${errThrowable.message}")
                pendingUsernames.add(0, username)
                ensureGrowthAccountLoggedInWithRetry(
                    alias = alias,
                    deviceId = deviceId,
                    reason = "掉登录后",
                    forceRelogin = true,
                )
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

    fun markMutualClickSwitched() {
        PrefsStore.clearMutualClickState()
        PrefsStore.setMutualCommentOnlySession(false)
    }

    /**
     * 用户从主界面请求「硬停」：不在协程安全点外杀进程；通过 [TraceExecutor] 内硬停检查在合适位置结束；
     * 暂停用户名拉取；保留 [startCheckLoop] 与设备指令轮询；并向云端同步 paused + 关闭本机「自动开启程序」。
     */
    fun requestUserGracefulHardStop() {
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            val alias = PrefsStore.getAlias()
            if (alias != null) {
                LogCenter.op(alias, "自动切号自治模式中不可硬停，请先在管理台关闭自动切号")
            }
            updateRuntime(lastMessage = "自动切号自治中：硬停不可用")
            return
        }
        val deviceId = PrefsStore.getDeviceId()
        val alias = PrefsStore.getAlias()
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

    fun stopAllWork(requestHardStop: Boolean = true) {
        // 注意：服务销毁(onDestroy)也会走到这里；避免在“正在重启服务”场景把硬停标记回写为 true。
        if (requestHardStop) {
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
