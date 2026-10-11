package com.autotraces.app.videoded

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autotraces.app.WorkerServiceWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** VideoDed FGS 存活标记，供本机 Watchdog 判断是否需重拉。 */
object VideoDedDeviceServiceTracker {
    @Volatile
    var isRunning: Boolean = false
}

class VideoDedDeviceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var apiServer: ApiServer? = null
    private var adbTcpipScheduleJob: Job? = null
    private var lastAdbTcpipRunAt = 0L
    private var lastPcMirrorTriggerAt = 0L
    /** 与「已注册」合并展示，避免轮询把无线 ADB 提示刷掉 */
    private var lastAdbMirrorSummary: String? = null

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleAdbTcpipAfterWifi(network)
        }
    }

    override fun onCreate() {
        super.onCreate()
        VideoDedDeviceServiceTracker.isRunning = true
        createChannel()
        startForeground(1, buildNotification("Starting..."))
        try {
            apiServer = ApiServer(applicationContext).apply { startAndRememberPort(SOCKET_READ_TIMEOUT, false) }
        } catch (t: Throwable) {
            Log.e("VideoDedDeviceService", "local HTTP API failed to start", t)
            updateNotification("本地 API 未启动：${t.message}")
        }
        registerWifiAdbCallback()
        // 本机 Alarm 巡检：Worker 停了仍保活 18088
        runCatching { WorkerServiceWatchdog.schedule(this) }
        scope.launch {
            delay(12_000)
            tryOneShotBootAdbTcpip()
        }
        scope.launch {
            while (true) {
                try {
                    // 先节流拉云端 PC 地址（约 5 分钟），再注册，避免本机仍连旧 Windows
                    VideoDedSettingsRegistrar.maybePullPcServiceUrlFromCloud(applicationContext)
                    val reg = CompanionApi.ensureRegistered(applicationContext)
                    maybeRequestPcWirelessPipeline(reg)
                    updateNotification(registrationNotifyText(ok = true))
                } catch (_: Throwable) {
                    updateNotification(registrationNotifyText(ok = false))
                }
                delay(15_000)
            }
        }
    }

    override fun onDestroy() {
        VideoDedDeviceServiceTracker.isRunning = false
        adbTcpipScheduleJob?.cancel()
        unregisterWifiAdbCallback()
        apiServer?.let { runCatching { it.stop() } }
        apiServer = null
        // 非用户停 API：尽快再巡检拉起
        runCatching {
            WorkerServiceWatchdog.schedule(this, delayMs = 8_000L)
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRY_ROOT_ADB_TCPIP -> {
                scope.launch {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val n = cm.activeNetwork
                    if (n != null && RootWifiAdbHelper.isWifiNetwork(applicationContext, n)) {
                        runEnableAdbTcpipForNetwork(n, force = true)
                    } else {
                        updateNotification("Root 无线 ADB：当前未走 Wi‑Fi，未执行")
                    }
                }
            }
            ACTION_PULL_VIDEOS -> {
                val count = intent.getIntExtra(EXTRA_COUNT, 1)
                val targetPath = intent.getStringExtra(EXTRA_TARGET_PATH)
                val username = intent.getStringExtra(EXTRA_USERNAME)
                scope.launch { pullVideos(count, targetPath, username) }
            }
            ACTION_DOWNLOAD_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_STICKY
                val targetPath = intent.getStringExtra(EXTRA_TARGET_PATH)
                val filesJson = intent.getStringExtra(EXTRA_FILES_JSON) ?: return START_STICKY
                val files = runCatching { TaskDownloadWorkflow.parseFilesJson(filesJson) }.getOrElse { emptyList() }
                if (files.isEmpty()) return START_STICKY
                scope.launch {
                    val result = runCatching {
                        TaskDownloadWorkflow.downloadVerifyAndAck(
                            applicationContext,
                            taskId,
                            files,
                            targetPath,
                        ) { msg ->
                            updateNotification(msg)
                            StatusLogBuffer.append(msg)
                        }
                    }
                    if (result.isSuccess) {
                        updateNotification(registrationNotifyText(ok = true))
                    } else {
                        updateNotification(registrationNotifyText(ok = false))
                    }
                }
            }
            ACTION_USB_PUSHED_VERIFY -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_STICKY
                val targetPath = intent.getStringExtra(EXTRA_TARGET_PATH)
                val filesJson = intent.getStringExtra(EXTRA_FILES_JSON) ?: return START_STICKY
                val files = runCatching { TaskDownloadWorkflow.parseFilesJson(filesJson) }.getOrElse { emptyList() }
                if (files.isEmpty()) return START_STICKY
                scope.launch {
                    val result = runCatching {
                        TaskDownloadWorkflow.verifyUsbPushedAndAck(
                            applicationContext,
                            taskId,
                            files,
                            targetPath,
                        ) { msg ->
                            updateNotification(msg)
                            StatusLogBuffer.append(msg)
                        }
                    }
                    if (result.isSuccess) {
                        updateNotification(registrationNotifyText(ok = true))
                    } else {
                        updateNotification(registrationNotifyText(ok = false))
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun pullVideos(count: Int, targetPath: String?, username: String?) {
        val resolvedTarget = CompanionApi.resolvedTargetPath(targetPath)
        val task =
            try {
                CompanionApi.createTask(applicationContext, count, resolvedTarget, username)
            } catch (t: Throwable) {
                StatusLogBuffer.append("创建任务失败：${t.message}")
                updateNotification("下载失败：${t.message}")
                return
            }
        try {
            TaskStore.saveTaskSnapshot(
                applicationContext,
                TaskSnapshot(
                    taskId = task.taskId,
                    status = task.status,
                    error = null,
                    createdAtMs = System.currentTimeMillis(),
                    targetPath = resolvedTarget,
                    originalTaskId = null,
                    retryOfTaskId = null,
                    username = CompanionApi.normalizeUsername(username).ifEmpty { CompanionApi.getMaterialUsername(applicationContext) },
                    files = task.files.map { TaskFileSnapshot(it.fileName, it.sizeBytes, it.md5, "pending", null) },
                ),
            )
            if (task.companionPullNotified != true) {
                enqueueDownloadTask(applicationContext, task.taskId, task.files, resolvedTarget)
                StatusLogBuffer.append("任务 ${task.taskId} 已创建（PC 未通知，本机已入队下载）")
            } else {
                StatusLogBuffer.append("任务 ${task.taskId} 已创建，PC 已触发下载")
            }
            updateNotification("任务 ${task.taskId.take(8)}… 后台下载中")
        } catch (t: Throwable) {
            updateNotification("下载失败：${t.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, getString(com.autotraces.app.R.string.video_ded_notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.autotraces.app.R.string.video_ded_app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    private fun registerWifiAdbCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, wifiNetworkCallback)
        }
    }

    private fun unregisterWifiAdbCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(wifiNetworkCallback)
        }
    }

    private fun scheduleAdbTcpipAfterWifi(network: Network) {
        if (!ApiSettings.isRootAdbTcpipEnabled(applicationContext)) return
        adbTcpipScheduleJob?.cancel()
        adbTcpipScheduleJob = scope.launch {
            delay(4000)
            if (!RootWifiAdbHelper.isWifiNetwork(applicationContext, network)) return@launch
            runEnableAdbTcpipForNetwork(network, force = false)
        }
    }

    private fun tryOneShotBootAdbTcpip() {
        if (!ApiSettings.isRootAdbTcpipEnabled(applicationContext)) return
        scope.launch {
            delay(2000)
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return@launch
            if (!RootWifiAdbHelper.isWifiNetwork(applicationContext, n)) return@launch
            runEnableAdbTcpipForNetwork(n, force = false)
        }
    }

    private fun runEnableAdbTcpipForNetwork(network: Network, force: Boolean) {
        if (!force) {
            synchronized(this) {
                val now = System.currentTimeMillis()
                if (now - lastAdbTcpipRunAt < 12_000) return
                lastAdbTcpipRunAt = now
            }
        } else {
            synchronized(this) { lastAdbTcpipRunAt = System.currentTimeMillis() }
        }
        val port = ApiSettings.getAdbTcpipPort(applicationContext)
        val err = RootWifiAdbHelper.enableAdbTcpip(port)
        val ip = RootWifiAdbHelper.getLanIpv4ForNetwork(applicationContext, network)
            ?: ApiSettings.getEffectiveMirrorLanIpv4(applicationContext)
        if (ip != null) lastAdbMirrorSummary = "$ip:$port" else lastAdbMirrorSummary = null
        val ipDisplay = ip ?: "IP未取到"
        val line = if (err == null) {
            "视频去重伴侣：无线 ADB $ipDisplay:$port（已尝试）"
        } else {
            "视频去重伴侣：无线 ADB $err | $ipDisplay:$port"
        }
        updateNotification(line)
    }

    private fun maybeRequestPcWirelessPipeline(reg: org.json.JSONObject? = null) {
        if (reg != null && reg.optString("pcAdbState", "").trim() == "device") return
        if (MirrorStateProbe.currentMirrorStatus(applicationContext) == "offline") return
        if (ApiSettings.getEffectiveMirrorLanIpv4(applicationContext) == null) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastPcMirrorTriggerAt < 90_000) return
            lastPcMirrorTriggerAt = now
        }
        scope.launch {
            runCatching {
                // 只请求连本机，禁止 connectAll：多机同时心跳会把 PC 拖进全集群重连风暴
                CompanionApi.requestPcWirelessPipeline(applicationContext, launchMirror = false)
            }.onFailure { Log.w("VideoDedDeviceService", "PC wireless pipeline failed", it) }
        }
    }

    private fun registrationNotifyText(ok: Boolean): String {
        val base = if (ok) "Registered and online" else "Waiting for Windows service"
        val extra = if (ok && ApiSettings.isRootAdbTcpipEnabled(applicationContext) && lastAdbMirrorSummary != null) {
            " · $lastAdbMirrorSummary"
        } else {
            ""
        }
        return base + extra
    }

    companion object {
        const val CHANNEL_ID = "video_ded_channel"
        const val ACTION_TRY_ROOT_ADB_TCPIP = "com.autotraces.app.videoded.TRY_ROOT_ADB_TCPIP"
        const val ACTION_PULL_VIDEOS = "com.autotraces.app.videoded.PULL_VIDEOS"
        const val ACTION_DOWNLOAD_TASK = "com.autotraces.app.videoded.DOWNLOAD_TASK"
        const val ACTION_USB_PUSHED_VERIFY = "com.autotraces.app.videoded.USB_PUSHED_VERIFY"
        const val EXTRA_COUNT = "count"
        const val EXTRA_TARGET_PATH = "targetPath"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_FILES_JSON = "filesJson"
        private const val SOCKET_READ_TIMEOUT = 0

        /** @return 是否已入队（false 表示同 taskId 已在下载或入队被跳过） */
        fun enqueueDownloadTask(context: Context, taskId: String, files: List<TaskFileInfo>, targetPath: String?): Boolean {
            if (TaskDownloadCoordinator.isActive(taskId)) {
                return false
            }
            val intent = Intent(context, VideoDedDeviceService::class.java).apply {
                action = ACTION_DOWNLOAD_TASK
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TARGET_PATH, targetPath)
                putExtra(EXTRA_FILES_JSON, TaskDownloadWorkflow.toFilesJson(files))
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun enqueueUsbPushedVerifyTask(context: Context, taskId: String, files: List<TaskFileInfo>, targetPath: String?): Boolean {
            if (TaskDownloadCoordinator.isActive(taskId)) {
                return false
            }
            val intent = Intent(context, VideoDedDeviceService::class.java).apply {
                action = ACTION_USB_PUSHED_VERIFY
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TARGET_PATH, targetPath)
                putExtra(EXTRA_FILES_JSON, TaskDownloadWorkflow.toFilesJson(files))
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun enqueueTryRootAdbTcpip(context: Context) {
            val intent = Intent(context, VideoDedDeviceService::class.java).apply { action = ACTION_TRY_ROOT_ADB_TCPIP }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 显式拉起 FGS（开机 / Magisk / Watchdog）。 */
        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, VideoDedDeviceService::class.java),
            )
        }
    }
}
