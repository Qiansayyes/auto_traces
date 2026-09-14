package com.autotraces.app

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * 拉起 TikTok 前的出口 IP 门阀：国内（CN）则循环打日志并阻塞，直至出口为非中国地区。
 */
object EgressIpGate {
    private const val POLL_INTERVAL_MS = 5_000L
    /** 非 CN 放行结果短缓存，避免 ensure→launch 连续探测。 */
    private const val OK_CACHE_MS = 30_000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastOkAtMs: Long = 0L

    @Volatile
    private var lastOkSummary: String = ""

    private data class Probe(
        val ok: Boolean,
        val ip: String = "",
        val countryCode: String = "",
        val country: String = "",
        val error: String = "",
    ) {
        val isChinaMainland: Boolean
            get() = countryCode.equals("CN", ignoreCase = true)
    }

    /**
     * 阻塞直到出口 IP 判定为非中国大陆；探测失败也继续轮询（不放行）。
     */
    suspend fun awaitUntilNotChina(alias: String, tag: String = "出口IP门阀") {
        val now = System.currentTimeMillis()
        if (lastOkAtMs > 0L && now - lastOkAtMs < OK_CACHE_MS && lastOkSummary.isNotBlank()) {
            LogCenter.op(alias, "$tag：沿用近期出口判定，允许拉起 TikTok（$lastOkSummary）")
            return
        }
        var round = 0
        while (true) {
            coroutineContext.ensureActive()
            round++
            val probe = withContext(Dispatchers.IO) { probeEgress() }
            when {
                probe.ok && !probe.isChinaMainland -> {
                    val summary = "IP=${probe.ip}，国家=${probe.country}/${probe.countryCode}"
                    lastOkSummary = summary
                    lastOkAtMs = System.currentTimeMillis()
                    LogCenter.op(alias, "$tag：出口正常，允许拉起 TikTok（$summary）")
                    return
                }
                probe.ok && probe.isChinaMainland -> {
                    lastOkAtMs = 0L
                    lastOkSummary = ""
                    LogCenter.op(
                        alias,
                        "$tag：当前为国内出口，禁止拉起 TikTok，等待代理（第${round}次，IP=${probe.ip}，国家=${probe.country}/${probe.countryCode}）",
                    )
                }
                else -> {
                    lastOkAtMs = 0L
                    lastOkSummary = ""
                    LogCenter.op(
                        alias,
                        "$tag：出口探测失败，暂不拉起 TikTok，继续等待（第${round}次，原因=${probe.error}）",
                    )
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun probeEgress(): Probe {
        // ip-api 免费层仅 HTTP；fields 精简响应
        val url = "http://ip-api.com/json/?fields=status,message,country,countryCode,query"
        return try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Probe(ok = false, error = "http_${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    return Probe(ok = false, error = "empty_body")
                }
                val json = JSONObject(body)
                val status = json.optString("status")
                if (!status.equals("success", ignoreCase = true)) {
                    return Probe(
                        ok = false,
                        error = json.optString("message").ifBlank { "status=$status" },
                    )
                }
                val code = json.optString("countryCode").trim()
                if (code.isBlank()) {
                    return Probe(ok = false, error = "missing_countryCode")
                }
                Probe(
                    ok = true,
                    ip = json.optString("query").trim(),
                    countryCode = code,
                    country = json.optString("country").trim(),
                )
            }
        } catch (t: Throwable) {
            Probe(ok = false, error = t.javaClass.simpleName + ":" + (t.message ?: ""))
        }
    }
}
