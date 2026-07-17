package com.autotraces.app

import android.content.Context

/**
 * 按设备指纹从云端恢复 [PrefsStore] 中的 device_id / 别名。
 * 服务端接口：GET /api/device_binding（见 server/main.py lookup_device_binding）。
 */
object BindingRecovery {
    /** @return true 表示本地绑定信息已写入或已与服务端一致 */
    suspend fun tryRecoverFromCloud(context: Context): Boolean {
        if (PrefsStore.getAuthToken().isNullOrBlank()) return false
        val fp = DeviceFingerprint.fromContext(context)
        val response = runCatching { ApiService.lookupDeviceBinding(fp) }.getOrNull() ?: return false
        if (response.code != 0) return false
        val deviceId = response.device_id?.trim().orEmpty()
        val alias = response.alias?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return false

        val currentId = PrefsStore.getDeviceId().orEmpty().trim()
        val currentAlias = PrefsStore.getAlias().orEmpty().trim()
        if (currentId == deviceId && currentAlias == alias && PrefsStore.isBound()) {
            return false
        }

        PrefsStore.saveBinding(deviceId, alias)
        PrefsStore.markBindingRefreshedNow()
        LogCenter.op(alias, "已从云端恢复设备绑定（$deviceId）")
        return true
    }

    fun looksLikeBindingAuthError(code: Int, msg: String?): Boolean {
        if (code != 2) return false
        val text = msg.orEmpty()
        return text.contains("绑定") || text.contains("不匹配")
    }
}
