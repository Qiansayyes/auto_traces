package com.autotraces.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

private const val ACTION_WORKER_SERVICE_WATCHDOG = "com.autotraces.app.action.WORKER_SERVICE_WATCHDOG"
private const val WORKER_SERVICE_START_THROTTLE_MS = 15_000L
private const val WORKER_SERVICE_WATCHDOG_INTERVAL_MS = 60_000L

object WorkerServiceWatchdog {
    fun shouldKeepWorkerServiceAlive(): Boolean {
        if (!PrefsStore.shouldWorkerServiceRun()) return false
        if (!PrefsStore.isBound()) return false
        return true
    }

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
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(appCtx)) }
    }

    fun ensureWorkerService(context: Context, reason: String): Boolean {
        val appCtx = context.applicationContext
        if (!shouldKeepWorkerServiceAlive()) return false
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
}

class WorkerServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WORKER_SERVICE_WATCHDOG) return
        WorkerServiceWatchdog.ensureWorkerService(context, "watchdog")
    }
}
