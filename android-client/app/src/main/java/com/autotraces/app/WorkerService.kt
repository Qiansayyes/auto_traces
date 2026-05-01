package com.autotraces.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat

object WorkerServiceTracker {
    @Volatile
    var isRunning: Boolean = false
}

private const val ACTION_STOP_BACKGROUND_SERVICE = "com.autotraces.app.action.STOP_BACKGROUND_SERVICE"

class WorkerService : Service() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        if (PrefsStore.isHardStopRequested()) {
            AppCoordinator.onWorkerServiceChanged(false)
            AppCoordinator.refreshRuntimeSnapshot()
            LogCenter.op(PrefsStore.getAlias(), "检测到硬停止标记，拒绝启动后台服务")
            runCatching { stopSelf() }
            return
        }
        WorkerServiceTracker.isRunning = true
        AppCoordinator.onWorkerServiceChanged(true)
        AppCoordinator.startIfNeeded()
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildForegroundNotification(this, "后台轮询与状态同步中"),
        )
        LogCenter.op(PrefsStore.getAlias(), "后台服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_BACKGROUND_SERVICE) {
            LogCenter.op(PrefsStore.getAlias(), "收到停止后台服务指令，立即关闭")
            stopImmediate()
            return START_NOT_STICKY
        }
        if (PrefsStore.isHardStopRequested()) {
            LogCenter.op(PrefsStore.getAlias(), "硬停止态中，忽略启动意图")
            stopImmediate()
            return START_NOT_STICKY
        }
        AppCoordinator.startIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        WorkerServiceTracker.isRunning = false
        AppCoordinator.stopAllWork()
        AppCoordinator.onWorkerServiceChanged(false)
        LogCenter.op(PrefsStore.getAlias(), "后台服务已停止")
        super.onDestroy()
    }

    private fun stopImmediate() {
        PrefsStore.setHardStopRequested(true)
        WorkerServiceTracker.isRunning = false
        AppCoordinator.stopAllWork()
        AppCoordinator.onWorkerServiceChanged(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            PrefsStore.setHardStopRequested(false)
            val intent = Intent(context, WorkerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WorkerService::class.java).apply {
                action = ACTION_STOP_BACKGROUND_SERVICE
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
