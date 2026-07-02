package com.autotraces.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 设备端 / 云端代取 TikTok 邮箱 6 位验证码（与 MainActivity 逻辑一致）。 */
object TikTokEmailCodeFetcher {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastFetchUnrecoverableConfig = false

    /** 最近一次 [fetchCode] 是否因邮箱/OAuth/API 配置缺失而不可恢复。 */
    fun consumeLastFetchUnrecoverableConfig(): Boolean {
        val value = lastFetchUnrecoverableConfig
        lastFetchUnrecoverableConfig = false
        return value
    }

    fun isUnrecoverableConfigError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val markers = listOf(
            "未配置该邮箱或 OAuth 令牌缺失",
            "且 API 为空",
            "API 为空且设备未绑定",
            "设备未关联管理账号",
            "邮箱管理模块未集成",
            "无法云端代取",
            "API 链接无效",
        )
        return markers.any { message.contains(it, ignoreCase = true) }
    }

    private fun finishFetch(result: Result<String>): Result<String> {
        lastFetchUnrecoverableConfig =
            result.isFailure && isUnrecoverableConfigError(result.exceptionOrNull()?.message)
        return result
    }

    suspend fun fetchCode(deviceId: String, alias: String, apiUrl: String, email: String): Result<String> {
        val url = apiUrl.trim()
        val accountEmail = email.trim()
        if (url.isNotBlank()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return finishFetch(Result.failure(IllegalStateException("API 链接无效")))
            }
            val local = withContext(Dispatchers.IO) { fetchFromApiUrl(url) }
            if (local.isSuccess) return finishFetch(local)
            val localErr = local.exceptionOrNull()!!
            if (deviceId.isBlank() || alias.isBlank()) {
                return finishFetch(
                    Result.failure(
                        IllegalStateException(
                            "设备端获取失败：${localErr.message ?: localErr.javaClass.simpleName}；设备未绑定，无法云端代取",
                        ),
                    ),
                )
            }
        } else if (deviceId.isBlank() || alias.isBlank()) {
            return finishFetch(Result.failure(IllegalStateException("API 为空且设备未绑定，无法云端代取")))
        }

        return finishFetch(fetchCodeFromServerWithRetry(deviceId, alias, url, accountEmail))
    }

    private suspend fun fetchCodeFromServerWithRetry(
        deviceId: String,
        alias: String,
        apiUrl: String,
        accountEmail: String,
        maxAttempts: Int = 4,
        retryDelayMs: Long = 10_000L,
    ): Result<String> {
        var lastError: Exception? = null
        repeat(maxAttempts.coerceIn(1, 6)) { attempt ->
            val result = runCatching {
                val resp = ApiService.getTikTokEmailCodeFromServer(deviceId, alias, apiUrl, accountEmail)
                if (resp.code != 0) {
                    throw IllegalStateException(resp.msg.ifBlank { "云端代取失败" })
                }
                val code = resp.email_code?.trim().orEmpty()
                if (!code.matches(Regex("""\d{6}"""))) {
                    throw IllegalStateException("云端未返回有效验证码")
                }
                code
            }
            if (result.isSuccess) {
                return result
            }
            lastError = result.exceptionOrNull() as? Exception
            val retriable = lastError?.message?.let { msg ->
                msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("unexpected end of stream", ignoreCase = true) ||
                    msg.contains("connection reset", ignoreCase = true) ||
                    msg.contains("超时") ||
                    msg.contains("未拉取到邮件") ||
                    msg.contains("未识别到 6 位验证码") ||
                    msg.contains("未就绪") ||
                    msg.contains("繁忙")
            } ?: false
            if (!retriable || attempt >= maxAttempts - 1) {
                return result
            }
            delay(retryDelayMs)
        }
        return Result.failure(lastError ?: IllegalStateException("云端代取失败"))
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
