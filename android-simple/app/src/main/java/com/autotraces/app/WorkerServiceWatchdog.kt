package com.autotraces.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.autotraces.app.videoded.ApiSettings
import com.autotraces.app.videoded.CompanionBridge
import com.autotraces.app.videoded.VideoDedDeviceService
import com.autotraces.app.videoded.VideoDedDeviceServiceTracker
import java.net.HttpURLConnection
import java.net.URL

private const val ACTION_WORKER_SERVICE_WATCHDOG = "com.autotraces.app.action.WORKER_SERVICE_WATCHDOG"
private const val WORKER_SERVICE_START_THROTTLE_MS = 15_000L
private const val VIDEO_DED_START_THROTTLE_MS = 20_000L
private const val WORKER_SERVICE_WATCHDOG_INTERVAL_MS = 60_000L

object WorkerServiceWatchdog {
    @Volatile
    private var lastVideoDedStartRequestAtMs: Long = 0L

    fun shouldKeepWorkerServiceAlive(): Boolean {
        // 已绑定即默认保活；不再要求「以前手动开过后台」
        if (!PrefsStore.isBound()) return false
        if (!PrefsStore.shouldWorkerServiceRun()) {
            PrefsStore.setWorkerServiceShouldRun(true)
        }
        return true
    }

    /** VideoDed 本地 API / FGS：机队依赖 18088，默认持续保活。 */
    fun shouldKeepVideoDedAlive(): Boolean = true

    fun schedule(context: Context, delayMs: Long = WORKER_SERVICE_WATCHDOG_INTERVAL_MS) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(5_000L)
        val pi = pendingIntent(appCtx)
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        val appCtx = context.applicationContext
        // 涨粉 Worker 停了仍需保活 VideoDed（PC companion / 18088）
        if (shouldKeepVideoDedAlive()) {
            schedule(appCtx, delayMs = 10_000L)
            return
        }
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(appCtx)) }
    }

    /** Worker + VideoDed(18088) 一并巡检拉起。 */
    fun ensureKeepAlive(context: Context, reason: String) {
        ensureWorkerService(context, reason)
        ensureVideoDedService(context, reason)
    }

    fun ensureWorkerService(context: Context, reason: String): Boolean {
        val appCtx = context.applicationContext
        if (!shouldKeepWorkerServiceAlive()) {
            if (shouldKeepVideoDedAlive()) schedule(appCtx)
            return false
        }
        if (WorkerServiceTracker.isRunning) {
            schedule(appCtx)
            return false
        }
        val now = System.currentTimeMillis()
        val lastAt = PrefsStore.getWorkerServiceLastStartRequestAtMs()
        if (now - lastAt < WORKER_SERVICE_START_THROTTLE_MS) {
            schedule(appCtx)
            return false
        }
        PrefsStore.noteWorkerServiceStartRequested(reason)
        runCatching {
            val intent = Intent(appCtx, WorkerService::class.java)
            ContextCompat.startForegroundService(appCtx, intent)
        }.onSuccess {
            val alias = PrefsStore.getAlias()
            if (!alias.isNullOrBlank()) {
                LogCenter.op(alias, "后台服务保活拉起：$reason")
            }
        }.onFailure { err ->
            val alias = PrefsStore.getAlias()
            if (!alias.isNullOrBlank()) {
                LogCenter.op(alias, "后台服务保活拉起失败($reason)：${err.message}")
            }
        }
        schedule(appCtx)
        return true
    }

    /**
     * 巡检 VideoDed FGS + 本地 18088：进程在但端口挂了也重新拉起。
     * @return true 表示本轮发起了 startForegroundService
     */
    fun ensureVideoDedService(context: Context, reason: String): Boolean {
        val appCtx = context.applicationContext
        if (!shouldKeepVideoDedAlive()) {
            schedule(appCtx)
            return false
        }
        val apiOk = probeLocalApiHealthy(appCtx)
        if (VideoDedDeviceServiceTracker.isRunning && apiOk) {
            schedule(appCtx)
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastVideoDedStartRequestAtMs < VIDEO_DED_START_THROTTLE_MS) {
            schedule(appCtx)
            return false
        }
        lastVideoDedStartRequestAtMs = now
        val detail =
            when {
                !VideoDedDeviceServiceTracker.isRunning && !apiOk -> "fgs_down+api_down"
                !VideoDedDeviceServiceTracker.isRunning -> "fgs_down"
                else -> "api_down_port_${CompanionBridge.API_PORT}"
            }
        Log.i(TAG, "ensure VideoDed reason=$reason detail=$detail")
        runCatching {
            ContextCompat.startForegroundService(
                appCtx,
                Intent(appCtx, VideoDedDeviceService::class.java),
            )
        }.onSuccess {
            val alias = PrefsStore.getAlias()
            if (!alias.isNullOrBlank()) {
                LogCenter.op(alias, "VideoDed保活拉起：$reason ($detail)")
            }
        }.onFailure { err ->
            Log.w(TAG, "start VideoDed failed reason=$reason", err)
            val alias = PrefsStore.getAlias()
            if (!alias.isNullOrBlank()) {
                LogCenter.op(alias, "VideoDed保活失败($reason)：${err.message}")
            }
        }
        schedule(appCtx)
        return true
    }

    /**
     * 探测本机 18088 /health（带 token）。
     * 能连上并拿到 2xx/401/503 都算「端口活着」；连接失败才算挂。
     */
    fun probeLocalApiHealthy(context: Context): Boolean {
        return runCatching {
            val url = URL("http://127.0.0.1:${CompanionBridge.API_PORT}/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1_200
                readTimeout = 1_200
                requestMethod = "GET"
                setRequestProperty("x-api-token", ApiSettings.getToken(context))
                instanceFollowRedirects = false
            }
            try {
                val code = conn.responseCode
                code in 200..299 || code == 401 || code == 503
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WorkerServiceWatchdogReceiver::class.java).apply {
            action = ACTION_WORKER_SERVICE_WATCHDOG
            `package` = context.packageName
        }
        return PendingIntent.getBroadcast(
            context,
            20031,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val TAG = "WorkerServiceWatchdog"
}

class WorkerServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WORKER_SERVICE_WATCHDOG) return
        // 含本机 HTTP 探测，勿堵主线程
        val pending = goAsync()
        Thread {
            try {
                WorkerServiceWatchdog.ensureKeepAlive(context.applicationContext, "watchdog")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
