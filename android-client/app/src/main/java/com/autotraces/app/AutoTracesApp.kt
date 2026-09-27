package com.autotraces.app

import android.app.Application

class AutoTracesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrefsStore.init(this)
        ProcessDiagnostics.installCrashHandler()
        AutoSwitchLocalPlanStore.init(this)
        AutoSwitchBundleHolder.init(this)
        ApiService.init(this)
        NotificationHelper.ensureChannel(this)
        AppCoordinator.init(this)
        ProcessDiagnostics.noteProcessColdStart()
        // Module C：进程冷启动后尽快巡检 Worker + VideoDed(18088)（后台线程，避免卡启动）
        Thread {
            runCatching { WorkerServiceWatchdog.ensureKeepAlive(this@AutoTracesApp, "app_cold_start") }
        }.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            ProcessDiagnostics.noteTrimMemory(level)
        }
    }
}
