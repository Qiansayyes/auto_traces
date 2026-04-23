package com.autotraces.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences工具类，存储设备ID/别名
 */
object SPUtil {
    private const val SP_NAME = "tiktok_auto_trace"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_ALIAS = "device_alias"

    /**
     * 获取SP实例
     */
    private fun getSP(context: Context): SharedPreferences {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 保存设备ID
     */
    fun saveDeviceId(context: Context, deviceId: String) {
        getSP(context).edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    /**
     * 获取设备ID
     */
    fun getDeviceId(context: Context): String {
        return getSP(context).getString(KEY_DEVICE_ID, "") ?: ""
    }

    /**
     * 保存设备别名
     */
    fun saveDeviceAlias(context: Context, alias: String) {
        getSP(context).edit().putString(KEY_DEVICE_ALIAS, alias).apply()
    }

    /**
     * 获取设备别名
     */
    fun getDeviceAlias(context: Context): String {
        return getSP(context).getString(KEY_DEVICE_ALIAS, "") ?: ""
    }
}