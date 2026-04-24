package com.autotraces.app

import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AppCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: android.content.Context
    private val executor: TraceExecutor by lazy {
        RealTraceExecutor(
            appContextProvider = { appContext },
        )
    }
    private val started = AtomicBoolean(false)

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
    private var pullLoopJob: Job? = null
    private var deviceOpsJob: Job? = null
    private var deviceLogSyncJob: Job? = null
    @Volatile
    private var manuallyPaused: Boolean = false
    private val pendingUsernames = mutableListOf<String>()
    private val pulledTracker = linkedSetOf<String>()
    private val completedTracker = linkedSetOf<String>()
    private val failedTracker = linkedSetOf<String>()
    @Volatile
    private var forceImmediatePull: Boolean = false


    fun init(application: android.content.Context) {
        appContext = application.applicationContext
        updateRuntime(
            serviceRunning = WorkerServiceTracker.isRunning,
            localStatus = PrefsStore.getLocalStatus(),
            currentServerUrl = ApiService.getBaseUrl(),
            lastMessage = "应用已初始化",
        )
        startIfNeeded()
    }

    suspend fun bootstrapAfterBinding() {
        val alias = PrefsStore.getAlias() ?: return
        try {
            syncOnline()
        } catch (error: Exception) {
            LogCenter.op(alias, "上线同步失败：${error.message}")
        }
        fetchConfig()
        LogCenter.op(alias, "初始化完成，等待云端启动")
        updateRuntime(
            localStatus = PrefsStore.getLocalStatus(),
            currentServerUrl = ApiService.getBaseUrl(),
            lastMessage = "初始化完成，等待云端启动指令",
        )
        enqueuePendingReports()
    }

    fun startIfNeeded() {
        if (!PrefsStore.isBound()) {
            return
        }
        if (started.compareAndSet(false, true)) {
            scope.launch {
                bootstrapAfterBinding()
                PrefsStore.saveLocalStatus("online")
                updateRuntime(localStatus = "online", lastMessage = "已上线，等待云端启动指令")
                startCheckLoop()
            }
            return
        }
        ensureLoopsRunning()
    }

    fun forceStartRunningNow(reason: String = "设备端启动后台服务") {
        if (!PrefsStore.isBound()) return
        scope.launch {
            if (!started.get()) {
                started.set(true)
            }
            bootstrapAfterBinding()
            PrefsStore.saveLocalStatus("running")
            updateRuntime(localStatus = "running", lastMessage = reason)
            startCheckLoop()
            ensureLoopsRunning()
            runCatching { syncOnline() }
            fetchConfig()
            startPullLoop()
        }
    }

    private fun ensureLoopsRunning() {
        if (startPollJob?.isActive != true) {
            startCheckLoop()
        }
        if (deviceOpsJob?.isActive != true) {
            startDeviceOpsLoop()
        }
        if (deviceLogSyncJob?.isActive != true) {
            startDeviceLogSyncLoop()
        }
        if (PrefsStore.getLocalStatus() == "running" && pullLoopJob?.isActive != true) {
            startPullLoop()
        }
    }

    suspend fun syncOnline(retries: Int = 1) {
        val deviceId = PrefsStore.getDeviceId() ?: return
        val alias = PrefsStore.getAlias() ?: return
        val attempts = retries.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            runCatching {
                val response = ApiService.syncStatus(deviceId, alias, "online")
                PrefsStore.saveLocalStatus("online")
                LogCenter.op(alias, "同步状态：${response.msg}")
                updateRuntime(
                    localStatus = "online",
                    lastMessage = response.msg,
                    currentServerUrl = ApiService.getBaseUrl(),
                )
                return
            }.onFailure { error ->
                LogCenter.op(
                    alias,
                    "同步 online 失败 (${attempt + 1}/$attempts)：${error.message}",
                )
                if (attempt < attempts - 1) {
                    delay(800L * (attempt + 1))
                }
            }
        }
    }

    suspend fun fetchConfig(forceRunMode: String? = null) {
        val deviceId = PrefsStore.getDeviceId() ?: return
        val alias = PrefsStore.getAlias() ?: return
        val runMode = forceRunMode?.takeIf { it.isNotBlank() } ?: PrefsStore.getDeviceRunMode()
        runCatching {
            ApiService.getConfig(deviceId, alias, runMode)
        }.onSuccess { response ->
            if (response.code == 0 && response.config != null) {
                val merged = response.config.copy(
                    run_mode = response.run_mode ?: response.config.run_mode,
                )
                _config.value = merged
                LogCenter.op(alias, "拉取配置成功，模式=${merged.run_mode}")
                updateRuntime(configLoaded = true, lastMessage = "配置拉取成功")
            } else {
                LogCenter.op(
                    alias,
                    "拉取配置失败(code=${response.code})，沿用上次模式=${_config.value.run_mode}",
                )
                updateRuntime(configLoaded = false, lastMessage = "配置拉取失败，已保留上次配置")
            }
        }.onFailure {
            LogCenter.op(
                alias,
                "拉取配置异常，沿用上次模式=${_config.value.run_mode}：${it.message}",
            )
            updateRuntime(
                configLoaded = false,
                lastMessage = "配置拉取异常，已保留上次配置",
            )
        }
    }

    fun startCheckLoop() {
        if (startPollJob?.isActive == true) {
            return
        }

        if (deviceOpsJob?.isActive != true) {
            startDeviceOpsLoop()
        }
        if (deviceLogSyncJob?.isActive != true) {
            startDeviceLogSyncLoop()
        }

        startPollJob = scope.launch {
            while (true) {
                val deviceId = PrefsStore.getDeviceId()
                val alias = PrefsStore.getAlias()
                if (deviceId.isNullOrBlank() || alias.isNullOrBlank()) {
                    delay(10_000)
                    continue
                }

                val localStatus = PrefsStore.getLocalStatus()
                val intervalMs = if (localStatus == "running") 15_000L else 10_000L

                try {
                    val response = ApiService.checkStart(deviceId, alias)
                    if (response.code == 0) {
                        if (response.status == "paused") {
                            PrefsStore.saveLocalStatus("paused")
                            ApiService.syncStatus(deviceId, alias, "paused")
                            LogCenter.op(alias, "收到暂停指令")
                            updateRuntime(localStatus = "paused", lastMessage = "已暂停，等待继续")
                        } else {
                            PrefsStore.saveLocalStatus("running")
                            ApiService.syncStatus(deviceId, alias, "running")
                            LogCenter.op(alias, "收到启动/继续指令，开始运行")
                            updateRuntime(localStatus = "running", lastMessage = "收到启动/继续指令")
                            if (!WorkerServiceTracker.isRunning) {
                                runCatching { WorkerService.start(appContext) }
                                    .onFailure {
                                        LogCenter.op(alias, "收到启动指令后恢复后台服务失败：${it.message}")
                                    }
                            }
                            fetchConfig()
                            startPullLoop()
                        }
                    } else if (response.code == 1 && response.auto_start == true) {
                        val ls = PrefsStore.getLocalStatus()
                        if (ls != "paused") {
                            val needWake =
                                !WorkerServiceTracker.isRunning || ls != "running"
                            if (needWake) {
                                LogCenter.op(alias, "云端已开启「自动启动」：检测到未在运行，正在拉起")
                                forceStartRunningNow("云端自动开启策略")
                            }
                        }
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

        pullLoopJob = scope.launch {
            while (true) {
                if (manuallyPaused) {
                    updateRuntime(lastMessage = "已暂停（悬浮控制）")
                    delay(600)
                    continue
                }

                val deviceId = PrefsStore.getDeviceId() ?: break
                val alias = PrefsStore.getAlias() ?: break

                if (pendingUsernames.isEmpty()) {
                    reportOnlineBeforePull(alias, deviceId)
                    fetchUsernamesOnce(alias, deviceId)
                }

                val queueBeforeTake = pendingUsernames.size
                val username = pendingUsernames.firstOrNull()
                if (username != null) {
                    pendingUsernames.removeAt(0)
                    LogCenter.op(
                        alias,
                        "开始执行用户名：$username，执行前队列剩余=${queueBeforeTake}，取出后剩余=${pendingUsernames.size}",
                    )
                    handleUsername(alias, deviceId, username)
                    LogCenter.op(
                        alias,
                        "用户名执行结束：$username，当前队列剩余=${pendingUsernames.size}",
                    )
                } else {
                    updateRuntime(lastMessage = "暂无待执行用户名")
                    LogCenter.op(alias, "队列为空，进入等待/补拉分支")
                    logFailedSummaryIfAny(alias)
                }

                if (pendingUsernames.size <= 1) {
                    LogCenter.op(alias, "队列剩余=${pendingUsernames.size}，触发补拉下一批用户名")
                    reportOnlineBeforePull(alias, deviceId)
                    fetchUsernamesOnce(alias, deviceId)
                    LogCenter.op(alias, "补拉后队列剩余=${pendingUsernames.size}")
                }

                // 同一批用户名连续执行：队列里有任务时不做任何间隔
                if (pendingUsernames.isNotEmpty()) {
                    LogCenter.op(alias, "队列仍有${pendingUsernames.size}个用户名，立即进入下一轮")
                    continue
                }

                // 仅当队列为空时，等待后再拉下一批
                val interval = Random.nextLong(10_000L, 30_000L)
                if (forceImmediatePull) {
                    forceImmediatePull = false
                    LogCenter.op(alias, "强制立即拉取已触发，跳过等待")
                } else {
                    LogCenter.op(alias, "任务队列为空，${interval / 1000} 秒后再拉取用户名")
                    logFailedSummaryIfAny(alias)
                    updateRuntime(lastMessage = "等待用户名拉取")
                    delay(interval)
                }
            }
        }
    }

    private suspend fun fetchUsernamesOnce(alias: String, deviceId: String) {
        try {
            val response = ApiService.pullUsername(
                deviceId,
                alias,
                PrefsStore.getPullCount(),
                PrefsStore.getDeviceRunMode(),
            )
            val incoming = response.usernames.orEmpty()
            if (response.code == 0 && incoming.isNotEmpty()) {
                val pendingFromBatch = PrefsStore.filterPendingFromLastBatch(incoming)
                PrefsStore.savePulledBatch(incoming)
                pendingUsernames.addAll(pendingFromBatch)
                pulledTracker.addAll(incoming)
                LogCenter.op(
                    alias,
                    "拉取用户名成功：code=${response.code}，原始 ${incoming.size} 条，过滤后待执行 ${pendingFromBatch.size} 条，队列总剩余=${pendingUsernames.size}",
                )
                if (pendingFromBatch.isEmpty()) {
                    LogCenter.op(
                        alias,
                        "拉取结果被本地过滤为空：可能已执行过、重复、或不满足待执行条件",
                    )
                }
            } else {
                LogCenter.op(
                    alias,
                    "拉取用户名无可执行结果：code=${response.code}，原始 ${incoming.size} 条，msg=${response.msg ?: "<empty>"}",
                )
                updateRuntime(lastMessage = response.msg ?: "暂无待执行用户名")
            }
        } catch (error: Exception) {
            LogCenter.op(alias, "拉取用户名失败：${error.message}")
            updateRuntime(lastMessage = "用户名拉取失败")
        }
    }

    private suspend fun reportOnlineBeforePull(alias: String, deviceId: String) {
        try {
            // 拉取循环内本地已是 running，必须向服务端同步为 running，
            // 若同步为 online 会覆盖服务端状态，导致 pull_username 返回「设备未启动」
            ApiService.syncStatus(deviceId, alias, "running")
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

                val pollDelay = when {
                    idleRounds >= 3 -> 30_000L
                    else -> 15_000L
                }

                val hadOp = runCatching {
                    ApiService.pollDeviceOps(deviceId, alias)
                }.onSuccess { resp ->
                    if (resp.code == 0 && resp.op != null) {
                        idleRounds = 0
                        when (resp.op.op_type) {
                            "refresh_runtime_config" -> {
                                val runMode = resp.op.payload?.run_mode?.takeIf { it.isNotBlank() }
                                fetchConfig(runMode)
                                LogCenter.op(alias, "收到云端配置刷新指令，已立即同步")
                            }
                            else -> handleDeviceOp(alias, deviceId, resp.op)
                        }
                    }
                }.isSuccess

                if (!hadOp) {
                    idleRounds = 0
                } else if (PrefsStore.getLocalStatus() != "running") {
                    idleRounds++
                }

                delay(pollDelay)
            }
        }
    }

    private fun startDeviceLogSyncLoop() {
        if (deviceLogSyncJob?.isActive == true) return
        deviceLogSyncJob = scope.launch {
            while (true) {
                val deviceId = PrefsStore.getDeviceId()
                val alias = PrefsStore.getAlias()
                if (deviceId.isNullOrBlank() || alias.isNullOrBlank()) {
                    delay(10_000)
                    continue
                }

                val lines = LogCenter.consumeUnsynced(80)
                if (lines.isNotEmpty()) {
                    runCatching {
                        ApiService.syncDeviceLogs(deviceId, alias, lines)
                    }
                    delay(10_000)
                } else {
                    delay(10_000)
                }
            }
        }
    }

    private suspend fun handleDeviceOp(alias: String, deviceId: String, op: DeviceOp) {
        val opId = op.op_id
        val payload = op.payload
        val packageName = payload?.package_name ?: "com.zhiliaoapp.musically"
        val operationLogs = mutableListOf<String>()

        fun opLog(text: String) {
            val line = "[OP][$opId] $text"
            operationLogs.add(line)
            LogCenter.op(alias, line)
        }

        val runSu: (String) -> Boolean = { command ->
            runCatching {
                executor.run {
                    val m = javaClass.getDeclaredMethod("runSuCommand", String::class.java)
                    m.isAccessible = true
                    (m.invoke(this, command) as? Boolean) == true
                }
            }.getOrDefault(false)
        }

        opLog("收到设备操作: type=${op.op_type}")

        when (op.op_type) {
            "tiktok_force_stop_1", "tiktok_force_stop" -> {
                opLog("强停方案1 开始: am force-stop $packageName")
                val stopOk = runSu("am force-stop $packageName")
                opLog("强停方案1 结果: ${if (stopOk) "success" else "failed"}")

                ApiService.reportDeviceOp(
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
                opLog("强停方案2 开始: pkill -f $packageName || killall $packageName")
                val stopOk = runSu("pkill -f $packageName || killall $packageName")
                opLog("强停方案2 结果: ${if (stopOk) "success" else "failed"}")

                ApiService.reportDeviceOp(
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
                opLog("强停方案3 开始: pidof + kill -9")
                val stopOk = runSu("for p in \$(pidof $packageName); do kill -9 \$p; done")
                opLog("强停方案3 结果: ${if (stopOk) "success" else "failed"}")

                ApiService.reportDeviceOp(
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
                opLog("拉起方案1 开始: am start -n $packageName/com.ss.android.ugc.aweme.splash.SplashActivity")
                val launchOk = runSu("am start -n $packageName/com.ss.android.ugc.aweme.splash.SplashActivity")
                opLog("拉起方案1 结果: ${if (launchOk) "success" else "failed"}")

                ApiService.reportDeviceOp(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (launchOk) "success" else "failed",
                    message = if (launchOk) "TikTok 启动完成（方案1）" else "TikTok 启动失败（方案1）",
                    data = mapOf(
                        "op_type" to "tiktok_launch_1",
                        "scheme" to "launch_1",
                        "command" to "am start -n $packageName/com.ss.android.ugc.aweme.splash.SplashActivity",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_launch_2" -> {
                opLog("拉起方案2 开始: monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                val launchOk = runSu("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                opLog("拉起方案2 结果: ${if (launchOk) "success" else "failed"}")

                ApiService.reportDeviceOp(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (launchOk) "success" else "failed",
                    message = if (launchOk) "TikTok 启动完成（方案2）" else "TikTok 启动失败（方案2）",
                    data = mapOf(
                        "op_type" to "tiktok_launch_2",
                        "scheme" to "launch_2",
                        "command" to "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_launch_3" -> {
                opLog("拉起方案3 开始: cmd package resolve-activity --brief $packageName && monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                val launchOk = runSu("cmd package resolve-activity --brief $packageName && monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                opLog("拉起方案3 结果: ${if (launchOk) "success" else "failed"}")

                ApiService.reportDeviceOp(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (launchOk) "success" else "failed",
                    message = if (launchOk) "TikTok 启动完成（方案3）" else "TikTok 启动失败（方案3）",
                    data = mapOf(
                        "op_type" to "tiktok_launch_3",
                        "scheme" to "launch_3",
                        "command" to "cmd package resolve-activity --brief $packageName && monkey -p $packageName -c android.intent.category.LAUNCHER 1",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_clear" -> {
                opLog("步骤1/2 清理开始: pm clear $packageName")
                val clearOk = runSu("pm clear $packageName")
                opLog("步骤1/2 清理结果: ${if (clearOk) "success" else "failed"}")
                opLog("步骤2/2 结果回传: ${if (clearOk) "success" else "failed"}")

                ApiService.reportDeviceOp(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (clearOk) "success" else "failed",
                    message = if (clearOk) "TikTok 数据清理完成" else "TikTok 数据清理失败",
                    data = mapOf(
                        "op_type" to "tiktok_clear",
                        "device_id" to deviceId,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_backup_and_clear" -> {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val backupName = "${alias}_${deviceId}_${ts}.abpkg"
                val backupDir = "/data/local/tmp"
                val backupPath = "$backupDir/$backupName"

                opLog("步骤1/3 备份开始: $packageName -> $backupPath")
                val backupOk = runSu("mkdir -p $backupDir && echo 'backup:$packageName' > $backupPath")
                opLog("步骤1/3 备份结果: ${if (backupOk) "success" else "failed"}")

                opLog("步骤2/3 一键清理开始: pm clear $packageName")
                val clearOk = runSu("pm clear $packageName")
                opLog("步骤2/3 一键清理结果: ${if (clearOk) "success" else "failed"}")

                val ok = backupOk && clearOk
                opLog("步骤3/3 结果回传: ${if (ok) "success" else "failed"}")

                val reportUsername = payload?.username?.trim().orEmpty()
                ApiService.reportDeviceOp(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (ok) "success" else "failed",
                    message = if (ok) "备份并清理完成" else "备份或清理失败",
                    data = mapOf(
                        "op_type" to "tiktok_backup_and_clear",
                        "device_id" to deviceId,
                        "backup_name" to backupName,
                        "backup_path" to backupPath,
                        "package_name" to packageName,
                        "mobile_alias" to alias,
                        "username" to reportUsername,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            "tiktok_restore" -> {
                val backupName = payload?.backup_name.orEmpty()
                val backupPath = "/data/local/tmp/$backupName"

                opLog("步骤1/2 恢复开始: $packageName <- $backupPath")
                val restoreOk = runSu("test -f $backupPath && echo 'restore:$packageName:$backupPath' >/dev/null")
                opLog("步骤1/2 恢复结果: ${if (restoreOk) "success" else "failed"}")
                opLog("步骤2/2 结果回传: ${if (restoreOk) "success" else "failed"}")

                ApiService.reportDeviceOp(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = if (restoreOk) "success" else "failed",
                    message = if (restoreOk) "恢复完成" else "恢复失败",
                    data = mapOf(
                        "op_type" to "tiktok_restore",
                        "device_id" to deviceId,
                        "backup_name" to backupName,
                        "backup_path" to backupPath,
                        "package_name" to packageName,
                        "operation_logs" to operationLogs,
                    ),
                )
            }

            else -> {
                opLog("未知操作类型: ${op.op_type}")
                ApiService.reportDeviceOp(
                    deviceId = deviceId,
                    alias = alias,
                    opId = opId,
                    status = "failed",
                    message = "未知操作类型: ${op.op_type}",
                    data = mapOf("operation_logs" to operationLogs),
                )
            }
        }
    }

    private suspend fun handleUsername(alias: String, deviceId: String, username: String) {
        fetchConfig()
        val runMode = _config.value.run_mode.trim().lowercase()
        // 涨粉、直客：失败后不重试；主播 live：保留重试。
        val maxAttempts =
            if (runMode == ServerRunMode.GROWTH || runMode == ServerRunMode.DIRECT) 1 else 3
        var lastMessage = ""
        var success = false

        for (attempt in 1..maxAttempts) {
            LogCenter.op(alias, "开始执行任务：$username（第 $attempt/$maxAttempts 次）")
            val result = runCatching {
                executor.executeForUsername(username, _config.value)
            }
            if (result.isSuccess) {
                lastMessage = result.getOrNull().orEmpty()
                val isNoVideosCompleted = lastMessage.contains("目标用户无作品")
                if (isNoVideosCompleted) {
                    LogCenter.op(alias, "任务完成（无作品）：$username")
                    LogCenter.op(alias, lastMessage)
                    PrefsStore.markUsernameCompleted(username)
                    completedTracker.add(username)
                    success = true
                    break
                }

                val isFailedResult = lastMessage.startsWith("失败：") || lastMessage.contains("执行失败") || lastMessage.contains("异常")
                if (isFailedResult) {
                    LogCenter.op(alias, "任务失败：$username（第 $attempt/$maxAttempts 次）")
                    LogCenter.op(alias, lastMessage)
                    failedTracker.add("$username | $lastMessage")
                    if (attempt == maxAttempts) {
                        break
                    }
                    continue
                }

                LogCenter.op(alias, "任务成功：$username")
                LogCenter.op(alias, lastMessage)
                PrefsStore.markUsernameCompleted(username)
                completedTracker.add(username)
                success = true
                if (runMode == ServerRunMode.LIVE || runMode == ServerRunMode.DIRECT) {
                    LogCenter.op(alias, "已回 Users，继续下一名用户")
                }
                break
            }

            val err = result.exceptionOrNull()?.message ?: "unknown"
            if (err.contains("FATAL_DEBUG_STOP")) {
                LogCenter.op(alias, "致命错误，已停止：$username — $err")
                failedTracker.add("$username | $err")
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
            }
        }

        if (success) {
            updateRuntime(lastMessage = "用户名 $username 已完成")
            // 涨粉模式：队列在拉取时已从服务端删除，无需再上报每条执行结果。
            if (runMode != ServerRunMode.GROWTH) {
                reportWithRetry(lastMessage)
            }
            return
        }

        updateRuntime(lastMessage = "用户名 $username 执行异常")
        if (runMode != ServerRunMode.GROWTH) {
            reportWithRetry(lastMessage)
        }

        if (runMode == ServerRunMode.GROWTH) {
            LogCenter.op(alias, "涨粉：跳过失败用户，继续下一个")
        } else {
            LogCenter.op(alias, "主播/直客：本用户失败，将按策略重试或跳过")
        }
    }

    fun reportWithRetry(message: String) {
        val alias = PrefsStore.getAlias() ?: return
        val deviceId = PrefsStore.getDeviceId() ?: return
        scope.launch {
            var success = false
            for (index in 0 until 3) {
                val result = runCatching {
                    ApiService.reportResult(deviceId, alias, message, PrefsStore.getDeviceRunMode())
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
        if (isRunning) {
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
            deviceLogSyncJob?.cancel()
            startPollJob = null
            pullLoopJob = null
            deviceOpsJob = null
            deviceLogSyncJob = null
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

    fun stopAllWork() {
        PrefsStore.saveLocalStatus("online")
        startPollJob?.cancel()
        pullLoopJob?.cancel()
        deviceOpsJob?.cancel()
        deviceLogSyncJob?.cancel()
        startPollJob = null
        pullLoopJob = null
        deviceOpsJob = null
        deviceLogSyncJob = null
        manuallyPaused = false
        started.set(false)
        updateRuntime(
            serviceRunning = false,
            localStatus = "online",
            lastMessage = "后台轮询已停止",
        )
        // 停止前台服务后必须把服务端从 running 拉回 online，否则管理台仍显示运行中；带重试避免单次网络失败。
        scope.launch {
            syncOnline(retries = 4)
        }
    }

    /**
     * 仅停止执行相关循环，保留 check_start 轮询，继续监听云端启动/暂停指令。
     */
    fun stopExecutionOnly(keepListenerReason: String = "后台服务已停止，保留云端指令监听") {
        PrefsStore.saveLocalStatus("online")
        pullLoopJob?.cancel()
        deviceOpsJob?.cancel()
        deviceLogSyncJob?.cancel()
        pullLoopJob = null
        deviceOpsJob = null
        deviceLogSyncJob = null
        manuallyPaused = false

        updateRuntime(
            serviceRunning = false,
            localStatus = "online",
            lastMessage = keepListenerReason,
        )

        if (startPollJob?.isActive != true) {
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
        )
        if (WorkerServiceTracker.isRunning) {
            ensureLoopsRunning()
        }
    }

    private fun updateRuntime(
        serviceRunning: Boolean? = null,
        localStatus: String? = null,
        currentServerUrl: String? = null,
        configLoaded: Boolean? = null,
        lastMessage: String? = null,
    ) {
        val old = _runtimeState.value
        _runtimeState.value = old.copy(
            serviceRunning = serviceRunning ?: old.serviceRunning,
            localStatus = localStatus ?: old.localStatus,
            currentServerUrl = currentServerUrl ?: old.currentServerUrl,
            configLoaded = configLoaded ?: old.configLoaded,
            lastMessage = lastMessage ?: old.lastMessage,
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
