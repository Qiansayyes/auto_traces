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
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            ProcessDiagnostics.noteTrimMemory(level)
        }
    }
}
