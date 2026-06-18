package com.autotraces.app

object AppInfo {
    fun versionLabel(): String = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
