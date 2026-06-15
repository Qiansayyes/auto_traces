package com.autotraces.app

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FailureReportUploader {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

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
        val biz = business.trim()
        val err = normalizeErrorType(errorType)
        if (biz.isEmpty() || err.isEmpty()) return
        if (PrefsStore.isFailureReportUploaded(biz, err)) return

        val ocrFlag = ocrRelated ?: isOcrRelated(biz, err, summary)
        val zipFile = runCatching {
            buildZip(
                business = biz,
                errorType = err,
                summary = summary,
                alias = alias,
                ocrRelated = ocrFlag,
            )
        }.getOrNull() ?: return

        try {
            val resp = uploadZip(deviceId, alias, biz, err, summary, ocrFlag, zipFile)
            when (resp.code) {
                0 -> {
                    PrefsStore.markFailureReportUploaded(biz, err)
                    if (ocrFlag) OcrDebugKeeper.clearAfterUpload()
                    LogCenter.op(alias, "失败采集已上传：$biz / $err")
                }
                2 -> PrefsStore.markFailureReportUploaded(biz, err)
                3 -> LogCenter.op(alias, "失败采集：云端已满（88 条），暂未上传")
            }
        } finally {
            runCatching { zipFile.delete() }
        }
    }

    private fun buildZip(
        business: String,
        errorType: String,
        summary: String,
        alias: String,
        ocrRelated: Boolean,
    ): File {
        val ctx = PrefsStore.applicationContext()
        val out = File(ctx.cacheDir, "failure_report_${System.currentTimeMillis()}.zip")
        val meta = mapOf(
            "business" to business,
            "error_type" to errorType,
            "summary" to summary.take(2000),
            "alias" to alias,
            "device_id" to (PrefsStore.getDeviceId() ?: ""),
            "tiktok_username" to (PrefsStore.getLogContextUsername() ?: ""),
            "ocr_related" to ocrRelated,
            "created_at_ms" to System.currentTimeMillis(),
        )
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            zos.putNextEntry(ZipEntry("meta.json"))
            zos.write(gson.toJson(meta).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val logLines = LocalDeviceLogWriter.readRecentLines(400)
            zos.putNextEntry(ZipEntry("device_logs.txt"))
            zos.write(logLines.joinToString("\n").toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val recentUiLogs = LogCenter.logs.value.takeLast(120)
            if (recentUiLogs.isNotEmpty()) {
                zos.putNextEntry(ZipEntry("ui_logs.txt"))
                zos.write(recentUiLogs.joinToString("\n").toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            if (ocrRelated) {
                var ocrIndex = 0
                OcrDebugKeeper.listSnapshotFiles().forEach { png ->
                    val entryName = "ocr/${ocrIndex++}_${png.name}"
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(png).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                val cacheDir = ctx.cacheDir
                cacheDir.listFiles()
                    ?.filter { f ->
                        f.isFile &&
                            f.name.endsWith(".png", ignoreCase = true) &&
                            f.name.contains("ocr", ignoreCase = true)
                    }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(12)
                    ?.forEach { png ->
                        val entryName = "ocr/cache_${ocrIndex++}_${png.name}"
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(png).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
        }
        return out
    }

    private suspend fun uploadZip(
        deviceId: String,
        alias: String,
        business: String,
        errorType: String,
        summary: String,
        ocrRelated: Boolean,
        zipFile: File,
    ): FailureReportUploadResponse = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("alias", alias)
            .addFormDataPart("business", business)
            .addFormDataPart("error_type", errorType)
            .addFormDataPart("summary", summary.take(2000))
            .addFormDataPart("ocr_related", if (ocrRelated) "true" else "false")
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url(ApiService.getBaseUrl() + "/api/failure_reports/upload")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code}: $text")
            }
            gson.fromJson(text, FailureReportUploadResponse::class.java)
        }
    }
}
