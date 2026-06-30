package com.autotraces.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 设备端 / 云端代取 TikTok 邮箱 6 位验证码（与 MainActivity 逻辑一致）。 */
object TikTokEmailCodeFetcher {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 单次 HTTP 等待期间的心跳间隔（避免拉码长时间无 UI 日志）。 */
    private const val EMAIL_CODE_FETCH_HEARTBEAT_MS = 15_000L

    private const val SERVER_FETCH_MAX_ATTEMPTS = 4
    private const val SERVER_FETCH_RETRY_DELAY_MS = 10_000L

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

    private fun finishFetch(alias: String, result: Result<String>, source: String): Result<String> {
        lastFetchUnrecoverableConfig =
            result.isFailure && isUnrecoverableConfigError(result.exceptionOrNull()?.message)
        result.onSuccess {
            LogCenter.op(alias, "拉取验证码：成功（来源=$source）")
        }.onFailure { err ->
            LogCenter.op(
                alias,
                "拉取验证码：失败（来源=$source）：${err.message ?: err.javaClass.simpleName}",
            )
        }
        return result
    }

    private fun resolveLogAlias(alias: String): String = alias.trim().ifBlank { "unknown" }

    private fun maskEmail(email: String): String {
        val e = email.trim()
        if (e.isEmpty()) return "(空)"
        val at = e.indexOf('@')
        if (at <= 0) return "***"
        val local = e.substring(0, at)
        val domain = e.substring(at)
        val maskedLocal = when {
            local.length <= 2 -> "*".repeat(local.length.coerceAtLeast(1))
            else -> local.take(2) + "***"
        }
        return maskedLocal + domain
    }

    private suspend fun <T> runWithFetchHeartbeat(
        alias: String,
        phase: String,
        block: suspend () -> T,
    ): T = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val heartbeatJob = launch {
            var tick = 0
            while (isActive) {
                delay(EMAIL_CODE_FETCH_HEARTBEAT_MS)
                tick++
                val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
                LogCenter.op(
                    alias,
                    "拉取验证码：$phase 等待响应中（已 ${elapsedSec}s，心跳 #$tick）",
                )
            }
        }
        try {
            block()
        } finally {
            heartbeatJob.cancel()
        }
    }

    suspend fun fetchCode(deviceId: String, alias: String, apiUrl: String, email: String): Result<String> {
        val logAlias = resolveLogAlias(alias)
        val url = apiUrl.trim()
        val accountEmail = email.trim()
        LogCenter.op(logAlias, "拉取验证码：开始（邮箱=${maskEmail(accountEmail)}）")

        if (url.isNotBlank()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return finishFetch(
                    logAlias,
                    Result.failure(IllegalStateException("API 链接无效")),
                    "配置校验",
                )
            }
            LogCenter.op(logAlias, "拉取验证码：尝试账号 api_url …")
            val local = runWithFetchHeartbeat(logAlias, "账号 api_url") {
                withContext(Dispatchers.IO) { fetchFromApiUrl(url) }
            }
            if (local.isSuccess) {
                return finishFetch(logAlias, local, "账号 api_url")
            }
            val localErr = local.exceptionOrNull()!!
            if (deviceId.isBlank() || alias.isBlank()) {
                return finishFetch(
                    logAlias,
                    Result.failure(
                        IllegalStateException(
                            "设备端获取失败：${localErr.message ?: localErr.javaClass.simpleName}；设备未绑定，无法云端代取",
                        ),
                    ),
                    "账号 api_url→云端",
                )
            }
            LogCenter.op(
                logAlias,
                "拉取验证码：账号 api_url 未命中，转云端代取（${localErr.message ?: localErr.javaClass.simpleName}）",
            )
        } else if (deviceId.isBlank() || alias.isBlank()) {
            return finishFetch(
                logAlias,
                Result.failure(IllegalStateException("API 为空且设备未绑定，无法云端代取")),
                "配置校验",
            )
        } else {
            LogCenter.op(logAlias, "拉取验证码：账号 api 为空，直接云端代取 …")
        }

        return finishFetch(
            logAlias,
            fetchCodeFromServerWithRetry(logAlias, deviceId, alias, url, accountEmail),
            "云端代取",
        )
    }

    private suspend fun fetchCodeFromServerWithRetry(
        logAlias: String,
        deviceId: String,
        alias: String,
        apiUrl: String,
        accountEmail: String,
        maxAttempts: Int = SERVER_FETCH_MAX_ATTEMPTS,
        retryDelayMs: Long = SERVER_FETCH_RETRY_DELAY_MS,
    ): Result<String> {
        val attempts = maxAttempts.coerceIn(1, 6)
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            val attemptNo = attempt + 1
            LogCenter.op(logAlias, "拉取验证码：云端代取 第 $attemptNo/$attempts 次 …")
            val result = runWithFetchHeartbeat(logAlias, "云端代取 第 $attemptNo/$attempts 次") {
                runCatching {
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
            }
            if (result.isSuccess) {
                return result
            }
            lastError = result.exceptionOrNull() as? Exception
            val errMsg = lastError?.message.orEmpty()
            LogCenter.op(
                logAlias,
                "拉取验证码：云端代取 第 $attemptNo/$attempts 次失败：$errMsg",
            )
            val retriable = errMsg.let { msg ->
                msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("unexpected end of stream", ignoreCase = true) ||
                    msg.contains("connection reset", ignoreCase = true) ||
                    msg.contains("超时") ||
                    msg.contains("未拉取到邮件") ||
                    msg.contains("未识别到 6 位验证码") ||
                    msg.contains("未就绪") ||
                    msg.contains("繁忙")
            }
            if (!retriable || attempt >= attempts - 1) {
                return result
            }
            LogCenter.op(
                logAlias,
                "拉取验证码：${retryDelayMs / 1000}s 后进行第 ${attemptNo + 1}/$attempts 次重试 …",
            )
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
