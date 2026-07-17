package com.autotraces.app

import java.util.Locale

object FailureReportUploader {
    /** 失败采集已关闭：不上传、不打包 zip。 */
    const val UPLOAD_ENABLED = false

    fun isOcrRelated(business: String, errorType: String, summary: String): Boolean {
        if (business.contains("ocr", ignoreCase = true)) return true
        if (errorType.contains("ocr", ignoreCase = true)) return true
        val s = summary.lowercase(Locale.US)
        return s.contains("ocr") || s.contains("识别") || s.contains("mlkit") || s.contains("screencap")
    }

    fun normalizeErrorType(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "unknown"
        val lower = trimmed.lowercase(Locale.US)
        when {
            lower.contains("hard_stop") || trimmed.contains("硬停") -> return "hard_stop"
            lower.contains("ocr") && lower.contains("miss") -> return "ocr_miss"
            lower.contains("ocr") && lower.contains("fail") -> return "ocr_fail"
            lower.contains("ocr") -> return "ocr_error"
            lower.contains("logout") || trimmed.contains("掉登录") -> return "account_logout"
            lower.contains("login") || trimmed.contains("登录") -> return "login_error"
            lower.contains("timeout") || trimmed.contains("超时") -> return "timeout"
        }
        val slug = trimmed
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "_")
            .trim('_')
        return slug.take(64).ifBlank { "unknown" }
    }

    suspend fun tryUpload(
        deviceId: String,
        alias: String,
        business: String,
        errorType: String,
        summary: String,
        ocrRelated: Boolean? = null,
    ) {
        if (!UPLOAD_ENABLED) return
    }
}
