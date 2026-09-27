package com.autotraces.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启（Module C）：拉起 VideoDed FGS（18088）；已绑定则默认拉起涨粉 WorkerService。
 * 与 PC 侧 DeviceHealthWatchdog 互补：无 ADB 时也能本地恢复。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != ACTION_HTC_QUICKBOOT_POWERON
        ) {
            return
        }
        Log.i(TAG, "boot keep-alive action=$action")
        val appCtx = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                WorkerServiceWatchdog.ensureKeepAlive(appCtx, "boot:$action")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
