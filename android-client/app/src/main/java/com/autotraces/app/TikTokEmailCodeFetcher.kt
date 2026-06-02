package com.autotraces.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 设备端 / 云端代取 TikTok 邮箱 6 位验证码（与 MainActivity 逻辑一致）。 */
object TikTokEmailCodeFetcher {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchCode(deviceId: String, alias: String, apiUrl: String): Result<String> {
        val url = apiUrl.trim()
        if (url.isBlank()) {
            return Result.failure(IllegalStateException("API 为空"))
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Result.failure(IllegalStateException("API 链接无效"))
        }
        val local = withContext(Dispatchers.IO) { fetchFromApiUrl(url) }
        if (local.isSuccess) return local
        val localErr = local.exceptionOrNull()!!
        if (deviceId.isBlank() || alias.isBlank()) {
            return Result.failure(
                IllegalStateException(
                    "设备端获取失败：${localErr.message ?: localErr.javaClass.simpleName}；设备未绑定，无法云端代取",
                ),
            )
        }
        return runCatching {
            val resp = ApiService.getTikTokEmailCodeFromServer(deviceId, alias, url)
            if (resp.code != 0) {
                throw IllegalStateException(resp.msg.ifBlank { "云端代取失败" })
            }
            val code = resp.email_code?.trim().orEmpty()
            if (!code.matches(Regex("""\d{6}"""))) {
                throw IllegalStateException("云端未返回有效验证码")
            }
            code
        }
    }

    private fun fetchFromApiUrl(apiUrl: String): Result<String> = runCatching {
        val req = Request.Builder().url(apiUrl).get().build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code}")
        }
        val bodyText = resp.body?.string().orEmpty()
        if (bodyText.isBlank()) {
            throw IllegalStateException("返回内容为空")
        }
        extractEmailCode(bodyText)
    }

    fun extractEmailCode(bodyText: String): String {
        val normalized = bodyText
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("[\\u00A0\\t]"), " ")

        val patterns = listOf(
            Regex("""(?im)^\s*(主题|subject)\s*[:：]\s*(\d{6})\b"""),
            Regex("""(?i)\b6[- ]?digit\s+code\b[^\d]{0,24}(\d{6})\b"""),
            Regex("""\b(\d{6})\b"""),
        )
        for (re in patterns) {
            val m = re.find(normalized) ?: continue
            val code = m.groupValues.getOrNull(2)?.ifBlank { null } ?: m.groupValues.getOrNull(1)
            val out = code?.trim().orEmpty()
            if (out.matches(Regex("""\d{6}"""))) return out
        }
        throw IllegalStateException("未识别到 6 位验证码")
    }
}
