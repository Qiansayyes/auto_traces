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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VideoDedDeviceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var apiServer: ApiServer? = null
    private var adbTcpipScheduleJob: Job? = null
    private var lastAdbTcpipRunAt = 0L
    /** 与「已注册」合并展示，避免轮询把无线 ADB 提示刷掉 */
    private var lastAdbMirrorSummary: String? = null
    /** 最近一次注册时 PC 端 ADB 状态；device 表示已可投屏，不再触发无线流水线 */
    @Volatile
    private var lastPcAdbState: String = ""

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleAdbTcpipAfterWifi(network)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        try {
            apiServer = ApiServer(applicationContext).apply { startAndRememberPort(SOCKET_READ_TIMEOUT, false) }
        } catch (t: Throwable) {
            Log.e("DeviceService", "local HTTP API failed to start", t)
            updateNotification("本地 API 未启动：${t.message}")
        }
        registerWifiAdbCallback()
        scope.launch {
            delay(12_000)
            tryOneShotBootAdbTcpip()
        }
        scope.launch {
            val ok = runSaveSettingsAndRegister(logSave = true, logRegistration = true)
            updateNotification(registrationNotifyText(ok = ok))
        }
        scope.launch {
            while (true) {
                val ok = runSaveSettingsAndRegister(logSave = false, logRegistration = false)
                updateNotification(registrationNotifyText(ok = ok))
                delay(15_000)
            }
        }
    }

    override fun onDestroy() {
        adbTcpipScheduleJob?.cancel()
        unregisterWifiAdbCallback()
        apiServer?.let { runCatching { it.stop() } }
        apiServer = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRY_ROOT_ADB_TCPIP -> {
                scope.launch {
                    val port = ApiSettings.getAdbTcpipPort(applicationContext)
                    if (!VideoDedSettingsRegistrar.shouldEnableAdbTcpip(applicationContext, port)) {
                        updateNotification("无线 ADB 已在 $port 监听，跳过重启 adbd")
                        return@launch
                    }
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
                scope.launch { pullVideos(count, targetPath) }
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
        }
        return START_STICKY
    }

    private suspend fun pullVideos(count: Int, targetPath: String?) {
        val resolvedTarget = CompanionApi.resolvedTargetPath(targetPath)
        val task =
            try {
                CompanionApi.createTaskAndWaitReady(applicationContext, count, resolvedTarget)
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
        nm.notify(NOTIFICATION_ID, buildNotification(text))
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
            runSaveSettingsAndRegister(logSave = false, logRegistration = false)
            runEnableAdbTcpipForNetwork(network, force = false)
        }
    }

    private fun tryOneShotBootAdbTcpip() {
        if (!ApiSettings.isRootAdbTcpipEnabled(applicationContext)) return
        scope.launch {
            delay(2000)
            val port = ApiSettings.getAdbTcpipPort(applicationContext)
            if (!VideoDedSettingsRegistrar.shouldEnableAdbTcpip(applicationContext, port)) return@launch
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return@launch
            if (!RootWifiAdbHelper.isWifiNetwork(applicationContext, n)) return@launch
            runEnableAdbTcpipForNetwork(n, force = false)
        }
    }

    private suspend fun runSaveSettingsAndRegister(logSave: Boolean, logRegistration: Boolean): Boolean {
        val result = VideoDedSettingsRegistrar.saveAndRegister(
            applicationContext,
            triggerWirelessIfNeeded = lastPcAdbState != "device",
            retryRootAdbIfNeeded = lastPcAdbState != "device",
            logSave = logSave,
            logRegistration = logRegistration,
        )
        lastPcAdbState = result.pcAdbState
        if (result.error != null) {
            Log.w("DeviceService", "PC registration failed: ${result.error.message}")
        }
        return result.error == null
    }

    private fun runEnableAdbTcpipForNetwork(network: Network, force: Boolean) {
        val port = ApiSettings.getAdbTcpipPort(applicationContext)
        if (!force && !VideoDedSettingsRegistrar.shouldEnableAdbTcpip(applicationContext, port)) {
            val ip = RootWifiAdbHelper.getLanIpv4ForNetwork(applicationContext, network)
                ?: ApiSettings.getEffectiveMirrorLanIpv4(applicationContext)
            if (ip != null) lastAdbMirrorSummary = "$ip:$port"
            return
        }
        if (!force) {
            synchronized(this) {
                val now = System.currentTimeMillis()
                if (now - lastAdbTcpipRunAt < 12_000) return
                lastAdbTcpipRunAt = now
            }
        } else {
            synchronized(this) { lastAdbTcpipRunAt = System.currentTimeMillis() }
        }
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
        private const val NOTIFICATION_ID = 88088
        const val CHANNEL_ID = "video_ded_channel"
        const val ACTION_TRY_ROOT_ADB_TCPIP = "com.autotraces.app.videoded.TRY_ROOT_ADB_TCPIP"
        const val ACTION_PULL_VIDEOS = "com.autotraces.app.videoded.PULL_VIDEOS"
        const val ACTION_DOWNLOAD_TASK = "com.autotraces.app.videoded.DOWNLOAD_TASK"
        const val EXTRA_COUNT = "count"
        const val EXTRA_TARGET_PATH = "targetPath"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_FILES_JSON = "filesJson"
        private const val SOCKET_READ_TIMEOUT = 0

        /** @return 是否已入队（false 表示同 taskId 已在下载或入队被跳过） */
        fun enqueueDownloadTask(context: Context, taskId: String, files: List<TaskFileInfo>, targetPath: String?): Boolean {
            if (TaskDownloadCoordinator.isActive(taskId)) {
                Log.d("DeviceService", "enqueueDownloadTask skipped duplicate taskId=$taskId")
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

        fun enqueueTryRootAdbTcpip(context: Context) {
            val intent = Intent(context, VideoDedDeviceService::class.java).apply { action = ACTION_TRY_ROOT_ADB_TCPIP }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
