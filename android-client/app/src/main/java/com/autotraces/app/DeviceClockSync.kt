package com.autotraces.app

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 启动即校时：HTTP（明文）取权威 UTC +（可选）出口 IP 时区 → 按需 root 写入。
 *
 * 两条路径必须区分：
 * 1) **时间错乱导致假断网**：墙钟漂移过大 → 可用 Date 头回退只改 UTC，不碰时区。
 * 2) **网络已通、时钟正常**：不得无脑改钟；仅在 **ip-api 成功拿到非空 timezone** 后才调整时区。
 *
 * 缓存用 [SystemClock.elapsedRealtime]，避免墙钟回拨后缓存逻辑算错。
 * 状态仅存进程内存，升级/杀进程后冷启会重新探测，无 SharedPreferences 跳过开关。
 */
object DeviceClockSync {
    private const val DRIFT_THRESHOLD_MS = 90_000L
    private const val OK_CACHE_MS = 6L * 60L * 60L * 1000L
    private const val FORCE_RETRY_AFTER_FAIL_MS = 5L * 60L * 1000L
    /** Date 头回退源之间允许的互差；超过则视为不可信。 */
    private const val DATE_FALLBACK_AGREE_MS = 120_000L
    /**
     * 仅 Date 回退时：若设备墙钟年份看起来正常，禁止一次性大幅改钟
     * （避免 captive portal / 代理缓存的陈旧 Date 把正确时钟改坏，如 br8→2026-05-20）。
     */
    private const val DATE_FALLBACK_MAX_CORRECT_MS = 36L * 60L * 60L * 1000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val mutex = Mutex()
    /** 上次成功校时的 elapsedRealtime（单调时钟）。 */
    private val lastOkElapsedMs = AtomicLong(0L)
    /** 上次尝试（含失败）的 elapsedRealtime。 */
    private val lastAttemptElapsedMs = AtomicLong(0L)

    @Volatile
    private var lastSummary: String = ""

    private data class Probe(
        val ok: Boolean,
        val utcEpochMs: Long = 0L,
        /** 仅 ip-api status=success 且 timezone 非空时才有值；Date 回退不得伪造。 */
        val timezoneId: String = "",
        val hasAuthoritativeTimezone: Boolean = false,
        val utcOffsetSeconds: Int? = null,
        val ip: String = "",
        val countryCode: String = "",
        val error: String = "",
        val source: String = "",
    )

    /**
     * 按需校时：成功短缓存；探测失败不阻塞后续流程。
     */
    suspend fun ensureSynced(alias: String, tag: String = "系统校时"): Boolean {
        val elapsed = SystemClock.elapsedRealtime()
        val lastOk = lastOkElapsedMs.get()
        if (lastOk > 0L && elapsed - lastOk < OK_CACHE_MS && lastSummary.isNotBlank()) {
            LogCenter.op(alias, "$tag：沿用近期校时（$lastSummary）")
            return true
        }
        val lastAttempt = lastAttemptElapsedMs.get()
        if (lastOk <= 0L && lastAttempt > 0L && elapsed - lastAttempt < FORCE_RETRY_AFTER_FAIL_MS) {
            // 刚失败过：冷启/Worker 短窗跳过；出口门阀/开 TikTok 路径仍可再试
            if (tag.contains("冷启") || tag.contains("Worker") || tag.contains("启动前")) {
                LogCenter.op(alias, "$tag：跳过（近期校时失败，稍后再试）")
                return false
            }
        }

        return mutex.withLock {
            val elapsedNow = SystemClock.elapsedRealtime()
            val okCached = lastOkElapsedMs.get()
            if (okCached > 0L && elapsedNow - okCached < OK_CACHE_MS && lastSummary.isNotBlank()) {
                LogCenter.op(alias, "$tag：沿用近期校时（$lastSummary）")
                return@withLock true
            }
            lastAttemptElapsedMs.set(elapsedNow)
            val probe = withContext(Dispatchers.IO) { probeHttpClock() }
            if (!probe.ok || probe.utcEpochMs <= 0L) {
                LogCenter.op(alias, "$tag：HTTP 取时失败，暂不改系统时间（${probe.error}）")
                return@withLock false
            }

            val deviceNow = System.currentTimeMillis()
            val driftMs = probe.utcEpochMs - deviceNow
            val absDrift = kotlin.math.abs(driftMs)
            val currentTz = readPersistTimezone().ifBlank { TimeZone.getDefault().id }
            val dateOnly = probe.source.startsWith("date:")
            val deviceYear = java.util.Calendar.getInstance().apply {
                timeInMillis = deviceNow
            }.get(java.util.Calendar.YEAR)
            val deviceYearPlausible = deviceYear in 2025..2035

            // Date 头回退不可靠（代理/captive 常返回陈旧 Date）。设备年份已正常时禁止大幅回拨/快进。
            if (dateOnly && absDrift > DATE_FALLBACK_MAX_CORRECT_MS && deviceYearPlausible) {
                LogCenter.op(
                    alias,
                    "$tag：拒绝 Date 回退大幅改钟（漂移=${absDrift / 1000}s src=${probe.source} " +
                        "权威UTC=${formatUtc(probe.utcEpochMs)} 设备年=$deviceYear），避免写坏正确时钟",
                )
                return@withLock false
            }

            // 路径1：墙钟错乱 → 必须改时间（可无 IP 时区）
            val needFixTime = absDrift > DRIFT_THRESHOLD_MS
            // 路径2：仅在拿到权威 IP 时区且与设备不一致时才改时区（网络正常也不无脑改）
            val wantTz = if (probe.hasAuthoritativeTimezone) probe.timezoneId.trim() else ""
            val needFixTz = wantTz.isNotBlank() &&
                !currentTz.equals(wantTz, ignoreCase = true)

            if (!needFixTime && !needFixTz) {
                lastSummary =
                    "漂移=${absDrift / 1000}s≤阈 时区=$currentTz IP=${probe.ip}/${probe.countryCode} src=${probe.source}"
                lastOkElapsedMs.set(SystemClock.elapsedRealtime())
                LogCenter.op(alias, "$tag：系统时间/时区正常，无需写入（$lastSummary）")
                return@withLock true
            }

            val action = buildString {
                if (needFixTime) append("改时间")
                if (needFixTz) {
                    if (isNotEmpty()) append('+')
                    append("改时区")
                }
            }
            LogCenter.op(
                alias,
                "$tag：需要强制校时($action) 漂移=${driftMs / 1000}s 设备时区=$currentTz" +
                    (if (needFixTz) "→$wantTz" else "（保持）") +
                    " 权威UTC=${formatUtc(probe.utcEpochMs)} IP=${probe.ip}/${probe.countryCode} src=${probe.source}",
            )
            val applied = withContext(Dispatchers.IO) {
                applyRootClock(
                    utcEpochMs = if (needFixTime) probe.utcEpochMs else null,
                    timezoneId = if (needFixTz) wantTz else null,
                )
            }
            if (!applied) {
                LogCenter.op(alias, "$tag：root 写入系统时间失败")
                return@withLock false
            }

            // 写入后稍等 RTC/框架刷新，再读墙钟校验（仅改过时间时）
            if (needFixTime) {
                runCatching { Thread.sleep(200L) }
                val after = System.currentTimeMillis()
                val remain = kotlin.math.abs(probe.utcEpochMs - after)
                if (remain > DRIFT_THRESHOLD_MS * 2) {
                    LogCenter.op(
                        alias,
                        "$tag：写入后校验仍偏差 ${remain / 1000}s（权威UTC=${formatUtc(probe.utcEpochMs)} 现=${formatUtc(after)}）",
                    )
                    return@withLock false
                }
                lastSummary =
                    "已写入($action) 原漂移=${driftMs / 1000}s 时区=${if (needFixTz) wantTz else currentTz} 余差=${remain / 1000}s IP=${probe.ip}"
            } else {
                lastSummary =
                    "已写入($action) 时区=$currentTz→$wantTz 漂移=${absDrift / 1000}s≤阈 IP=${probe.ip}"
            }
            lastOkElapsedMs.set(SystemClock.elapsedRealtime())
            LogCenter.op(alias, "$tag：强制校时完成（$lastSummary）")
            true
        }
    }

    /** 业务侧发现疑似证书/日期类假断网时可主动作废成功缓存，促使下次入口重探。 */
    fun invalidateOkCache(reason: String = "") {
        lastOkElapsedMs.set(0L)
        lastSummary = ""
        if (reason.isNotBlank()) {
            LogCenter.op("SYSTEM", "校时缓存已作废：$reason")
        }
    }

    private fun formatUtc(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(epochMs)
    }

    private fun probeHttpClock(): Probe {
        val primary = "http://ip-api.com/json/?fields=status,message,country,countryCode,query,timezone,offset"
        val primaryProbe = runCatching { fetchIpApiProbe(primary) }.getOrElse {
            Probe(ok = false, error = it.javaClass.simpleName + ":" + (it.message ?: ""), source = "ip-api")
        }
        if (primaryProbe.ok && primaryProbe.utcEpochMs > 0L) return primaryProbe

        // Date 回退：只解决「时间错乱假断网」，绝不带权威时区（避免无脑改时区）。
        // 必须 ≥2 个源互差 ≤ DATE_FALLBACK_AGREE_MS，拒绝单一 captive/代理陈旧 Date。
        val dateFallbacks = listOf(
            "http://example.com/",
            "http://neverssl.com/",
            "http://captive.apple.com/",
        )
        val samples = mutableListOf<Pair<String, Long>>()
        for (url in dateFallbacks) {
            val utc = runCatching { fetchHttpDateOnly(url) }.getOrNull() ?: continue
            if (utc <= 0L) continue
            samples += url to utc
        }
        if (samples.size >= 2) {
            // 取与其它样本互证最多的那个时间
            var bestUrl = samples[0].first
            var bestUtc = samples[0].second
            var bestPeers = 0
            for ((url, utc) in samples) {
                val peers = samples.count { (_, other) ->
                    kotlin.math.abs(other - utc) <= DATE_FALLBACK_AGREE_MS
                }
                if (peers > bestPeers) {
                    bestPeers = peers
                    bestUrl = url
                    bestUtc = utc
                }
            }
            if (bestPeers >= 2) {
                return Probe(
                    ok = true,
                    utcEpochMs = bestUtc,
                    timezoneId = "",
                    hasAuthoritativeTimezone = false,
                    utcOffsetSeconds = null,
                    ip = primaryProbe.ip,
                    countryCode = primaryProbe.countryCode,
                    error = if (primaryProbe.error.isBlank()) {
                        "date_fallback_consensus:$bestPeers"
                    } else {
                        "date_fallback_consensus:$bestPeers; primary=${primaryProbe.error}"
                    },
                    source = "date:$bestUrl",
                )
            }
        }
        val detail = buildString {
            if (primaryProbe.error.isNotBlank()) append("primary=${primaryProbe.error}; ")
            append("date_samples=${samples.size}")
            if (samples.isNotEmpty()) {
                append("(")
                append(samples.joinToString(",") { (u, t) -> "${u.substringAfter("://").substringBefore("/")}=${formatUtc(t)}" })
                append(")")
            }
        }
        return Probe(
            ok = false,
            error = if (samples.isEmpty()) {
                if (primaryProbe.error.isNotBlank()) primaryProbe.error else "all_http_time_sources_failed"
            } else {
                "date_fallback_no_consensus:$detail"
            },
            source = "none",
        )
    }

    private fun fetchIpApiProbe(url: String): Probe {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return Probe(ok = false, error = "http_${resp.code}", source = "ip-api")
            }
            val utcFromHeader = parseHttpDateToEpochMs(resp.header("Date")?.trim().orEmpty())
            val body = resp.body?.string().orEmpty()
            var timezone = ""
            var offsetSec: Int? = null
            var ip = ""
            var countryCode = ""
            var apiOk = false
            if (body.isNotBlank()) {
                val json = JSONObject(body)
                if (json.optString("status").equals("success", ignoreCase = true)) {
                    apiOk = true
                    timezone = json.optString("timezone").trim()
                    if (json.has("offset") && !json.isNull("offset")) {
                        offsetSec = json.optInt("offset")
                    }
                    ip = json.optString("query").trim()
                    countryCode = json.optString("countryCode").trim()
                }
            }
            if (utcFromHeader == null || utcFromHeader <= 0L) {
                return Probe(
                    ok = false,
                    timezoneId = timezone,
                    hasAuthoritativeTimezone = apiOk && timezone.isNotBlank(),
                    utcOffsetSeconds = offsetSec,
                    ip = ip,
                    countryCode = countryCode,
                    error = "no_http_date",
                    source = "ip-api",
                )
            }
            // 仅 JSON timezone 字段算权威；offset→Etc/GMT 只作弱参考，不当作「正确 IP 时区」去强制写入
            val hasAuthTz = apiOk && timezone.isNotBlank()
            return Probe(
                ok = true,
                utcEpochMs = utcFromHeader,
                timezoneId = if (hasAuthTz) timezone else "",
                hasAuthoritativeTimezone = hasAuthTz,
                utcOffsetSeconds = offsetSec,
                ip = ip,
                countryCode = countryCode,
                source = "ip-api",
            )
        }
    }

    private fun fetchHttpDateOnly(url: String): Long? {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            val utc = parseHttpDateToEpochMs(resp.header("Date")?.trim().orEmpty())
            runCatching { resp.body?.close() }
            return utc
        }
    }

    private fun parseHttpDateToEpochMs(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.parse(raw)?.time
        }.getOrNull()
    }

    private fun readPersistTimezone(): String {
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "persist.sys.timezone"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        }.getOrDefault("")
    }

    /**
     * @param utcEpochMs 非空才写墙钟；为 null 表示只改时区。
     * @param timezoneId 非空才写时区；为 null/blank 表示保持设备时区。
     */
    private fun applyRootClock(utcEpochMs: Long?, timezoneId: String?): Boolean {
        val tz = timezoneId?.trim().orEmpty()
        val writeTz = tz.isNotBlank()
        val writeTime = utcEpochMs != null && utcEpochMs > 0L
        if (!writeTz && !writeTime) return false

        if (writeTime) {
            runSu("settings put global auto_time 0")
        }
        if (writeTz) {
            runSu("settings put global auto_time_zone 0")
            runSu("cmd alarm set-timezone $tz")
            runSu("setprop persist.sys.timezone $tz")
        }
        var dateOk = !writeTime
        if (writeTime) {
            val epoch = utcEpochMs!!
            val utcCal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = epoch
            }
            val utcArg = String.format(
                Locale.US,
                "%02d%02d%02d%02d%04d.%02d",
                utcCal.get(java.util.Calendar.MONTH) + 1,
                utcCal.get(java.util.Calendar.DAY_OF_MONTH),
                utcCal.get(java.util.Calendar.HOUR_OF_DAY),
                utcCal.get(java.util.Calendar.MINUTE),
                utcCal.get(java.util.Calendar.YEAR),
                utcCal.get(java.util.Calendar.SECOND),
            )
            dateOk = runSu("date -u $utcArg") || runSu("/system/bin/date -u $utcArg")
            if (!dateOk) {
                val zone = runCatching {
                    if (writeTz) TimeZone.getTimeZone(tz) else null
                }.getOrNull()?.takeUnless { it.id == "GMT" && writeTz && !tz.equals("GMT", true) }
                    ?: TimeZone.getDefault()
                val local = java.util.Calendar.getInstance(zone).apply { timeInMillis = epoch }
                val localArg = String.format(
                    Locale.US,
                    "%02d%02d%02d%02d%04d.%02d",
                    local.get(java.util.Calendar.MONTH) + 1,
                    local.get(java.util.Calendar.DAY_OF_MONTH),
                    local.get(java.util.Calendar.HOUR_OF_DAY),
                    local.get(java.util.Calendar.MINUTE),
                    local.get(java.util.Calendar.YEAR),
                    local.get(java.util.Calendar.SECOND),
                )
                dateOk = runSu("date $localArg") || runSu("/system/bin/date $localArg")
            }
            runSu("am broadcast -a android.intent.action.TIME_SET")
        }
        if (writeTz) {
            runSu("am broadcast -a android.intent.action.TIMEZONE_CHANGED")
        }
        return dateOk
    }

    private fun runSu(command: String): Boolean {
        val candidates = listOf(
            "/data/adb/magisk/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "su",
        )
        for (su in candidates) {
            try {
                val process = if (su.startsWith("/")) {
                    Runtime.getRuntime().exec(arrayOf(su, "-c", command))
                } else {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "$su -c \"$command\""))
                }
                val tOut = Thread {
                    runCatching { BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() } }
                }.also { it.start() }
                val tErr = Thread {
                    runCatching { BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() } }
                }.also { it.start() }
                val code = process.waitFor()
                tOut.join(15_000)
                tErr.join(15_000)
                if (code == 0) return true
            } catch (_: Throwable) {
                // try next
            }
        }
        return false
    }
}
