package com.autotraces.app

import android.app.Application

class AutoTracesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrefsStore.init(this)
        ApiService.init(this)
        NotificationHelper.ensureChannel(this)
        AppCoordinator.init(this)
    }
}
