package com.autotraces.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

object DeviceFingerprint {
    fun fromContext(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val raw = listOf(
            androidId,
            Build.BRAND,
            Build.MODEL,
            Build.MANUFACTURER,
            Build.DEVICE,
            Build.PRODUCT,
            Build.ID,
            Build.FINGERPRINT,
            Build.VERSION.SDK_INT.toString(),
            context.packageName,
        ).joinToString("|")
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
