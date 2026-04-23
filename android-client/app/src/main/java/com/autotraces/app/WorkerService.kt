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

class WorkerService : Service() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
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
        AppCoordinator.startIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        WorkerServiceTracker.isRunning = false
        AppCoordinator.stopExecutionOnly()
        AppCoordinator.onWorkerServiceChanged(false)
        LogCenter.op(PrefsStore.getAlias(), "后台服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, WorkerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WorkerService::class.java)
            context.stopService(intent)
        }
    }
}
