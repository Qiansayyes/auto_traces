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
        if (PrefsStore.isMutualClickRequested()) {
            PrefsStore.setMutualClickPending(true)
            AppCoordinator.refreshRuntimeSnapshot()
            LogCenter.op(PrefsStore.getAlias(), "检测到互点待执行标记，等待任务进入合适的停止点")
        }
        WorkerServiceTracker.isRunning = true
        AppCoordinator.onWorkerServiceChanged(true)
        AppCoordinator.startIfNeeded()
        val notifyText =
            if (PrefsStore.isHardStopRequested()) {
                "硬停中：已暂停自动任务，仍监听云端指令"
            } else {
                "后台轮询与状态同步中"
            }
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildForegroundNotification(this, notifyText),
        )
        LogCenter.op(PrefsStore.getAlias(), "后台服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_BACKGROUND_SERVICE) {
            LogCenter.op(PrefsStore.getAlias(), "收到停止后台服务指令，立即关闭")
            stopImmediate()
            return START_NOT_STICKY
        }
        AppCoordinator.startIfNeeded()
        val notifyText =
            if (PrefsStore.isHardStopRequested()) {
                "硬停中：已暂停自动任务，仍监听云端指令"
            } else {
                "后台轮询与状态同步中"
            }
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildForegroundNotification(this, notifyText),
        )
        return START_STICKY
    }

    override fun onDestroy() {
        WorkerServiceTracker.isRunning = false
        AppCoordinator.stopAllWork(requestHardStop = false)
        AppCoordinator.onWorkerServiceChanged(false)
        LogCenter.op(PrefsStore.getAlias(), "后台服务已停止")
        super.onDestroy()
    }

    private fun stopImmediate() {
        PrefsStore.setHardStopRequested(true)
        WorkerServiceTracker.isRunning = false
        AppCoordinator.stopAllWork(requestHardStop = true)
        AppCoordinator.onWorkerServiceChanged(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** 仅拉起前台服务，不修改硬停标记。硬停期间仍需轮询时请用此方法。 */
        fun start(context: Context) {
            val intent = Intent(context, WorkerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 用户主动恢复自动化：解除硬停并启动后台服务。 */
        fun startResumingAutomation(context: Context) {
            PrefsStore.setHardStopRequested(false)
            start(context)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WorkerService::class.java).apply {
                action = ACTION_STOP_BACKGROUND_SERVICE
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
