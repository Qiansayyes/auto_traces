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
 * 启动即校时：HTTP（明文）取权威 UTC + 出口时区 → root 强制写入系统时间。
 *
 * 目的：设备墙钟停在错误年份时，HTTPS/证书先挂（表现为断网），不能等「先联网再校时」。
 * ip-api 走 HTTP，且 App 已 usesCleartextTraffic=true，多数情况下仍可取到时间。
 *
 * 缓存用 [SystemClock.elapsedRealtime]，避免墙钟从 2024→2026（或回拨）后缓存逻辑算错。
 */
object DeviceClockSync {
    private const val DRIFT_THRESHOLD_MS = 90_000L
    private const val OK_CACHE_MS = 6L * 60L * 60L * 1000L
    private const val FORCE_RETRY_AFTER_FAIL_MS = 5L * 60L * 1000L

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
        val timezoneId: String = "",
        val utcOffsetSeconds: Int? = null,
        val ip: String = "",
        val countryCode: String = "",
        val error: String = "",
    )

    /**
     * 若漂移过大或时区不一致则 root 强制写入；成功则短缓存，避免频繁写 RTC。
     * 探测失败不阻塞后续流程（仍会打日志）。
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
            val wantTz = probe.timezoneId.trim().ifBlank { currentTz }
            val tzMismatch = wantTz.isNotBlank() && !currentTz.equals(wantTz, ignoreCase = true)

            if (absDrift <= DRIFT_THRESHOLD_MS && !tzMismatch) {
                lastSummary =
                    "漂移=${absDrift / 1000}s≤阈 时区=$currentTz IP=${probe.ip}/${probe.countryCode}"
                lastOkElapsedMs.set(SystemClock.elapsedRealtime())
                LogCenter.op(alias, "$tag：系统时间正常，无需写入（$lastSummary）")
                return@withLock true
            }

            LogCenter.op(
                alias,
                "$tag：需要强制校时 漂移=${driftMs / 1000}s 设备时区=$currentTz→$wantTz " +
                    "权威UTC=${formatUtc(probe.utcEpochMs)} IP=${probe.ip}/${probe.countryCode}",
            )
            val applied = withContext(Dispatchers.IO) {
                applyRootClock(probe.utcEpochMs, wantTz)
            }
            if (!applied) {
                LogCenter.op(alias, "$tag：root 写入系统时间失败")
                return@withLock false
            }

            // 写入后稍等 RTC/框架刷新，再读墙钟校验
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
                "已写入 原漂移=${driftMs / 1000}s 时区=$wantTz 余差=${remain / 1000}s IP=${probe.ip}"
            lastOkElapsedMs.set(SystemClock.elapsedRealtime())
            LogCenter.op(alias, "$tag：强制校时完成（$lastSummary）")
            true
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
            Probe(ok = false, error = it.javaClass.simpleName + ":" + (it.message ?: ""))
        }
        if (primaryProbe.ok && primaryProbe.utcEpochMs > 0L) return primaryProbe

        val dateFallbacks = listOf(
            "http://example.com/",
            "http://neverssl.com/",
            "http://captive.apple.com/",
        )
        for (url in dateFallbacks) {
            val utc = runCatching { fetchHttpDateOnly(url) }.getOrNull() ?: continue
            if (utc <= 0L) continue
            return Probe(
                ok = true,
                utcEpochMs = utc,
                timezoneId = primaryProbe.timezoneId,
                utcOffsetSeconds = primaryProbe.utcOffsetSeconds,
                ip = primaryProbe.ip,
                countryCode = primaryProbe.countryCode,
                error = if (primaryProbe.error.isBlank()) "" else "date_fallback:$url; primary=${primaryProbe.error}",
            )
        }
        return if (primaryProbe.error.isNotBlank()) primaryProbe
        else Probe(ok = false, error = "all_http_time_sources_failed")
    }

    private fun fetchIpApiProbe(url: String): Probe {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return Probe(ok = false, error = "http_${resp.code}")
            }
            val utcFromHeader = parseHttpDateToEpochMs(resp.header("Date")?.trim().orEmpty())
            val body = resp.body?.string().orEmpty()
            var timezone = ""
            var offsetSec: Int? = null
            var ip = ""
            var countryCode = ""
            if (body.isNotBlank()) {
                val json = JSONObject(body)
                if (json.optString("status").equals("success", ignoreCase = true)) {
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
                    timezoneId = timezone.ifBlank { offsetSec?.let { offsetToEtcGmt(it) }.orEmpty() },
                    utcOffsetSeconds = offsetSec,
                    ip = ip,
                    countryCode = countryCode,
                    error = "no_http_date",
                )
            }
            val tzId = timezone.ifBlank {
                offsetSec?.let { offsetToEtcGmt(it) }.orEmpty()
            }
            return Probe(
                ok = true,
                utcEpochMs = utcFromHeader,
                timezoneId = tzId,
                utcOffsetSeconds = offsetSec,
                ip = ip,
                countryCode = countryCode,
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

    private fun offsetToEtcGmt(offsetSeconds: Int): String {
        val hours = offsetSeconds / 3600
        return when {
            hours == 0 -> "Etc/UTC"
            hours > 0 -> "Etc/GMT-$hours"
            else -> "Etc/GMT+${-hours}"
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

    private fun applyRootClock(utcEpochMs: Long, timezoneId: String): Boolean {
        val tz = timezoneId.trim()
        runSu("settings put global auto_time 0")
        runSu("settings put global auto_time_zone 0")
        if (tz.isNotBlank()) {
            runSu("cmd alarm set-timezone $tz")
            runSu("setprop persist.sys.timezone $tz")
        }
        val utcCal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = utcEpochMs
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
        var dateOk = runSu("date -u $utcArg") || runSu("/system/bin/date -u $utcArg")
        if (!dateOk) {
            val zone = runCatching {
                if (tz.isNotBlank()) TimeZone.getTimeZone(tz) else null
            }.getOrNull()?.takeUnless { it.id == "GMT" && tz.isNotBlank() && !tz.equals("GMT", true) }
                ?: TimeZone.getDefault()
            val local = java.util.Calendar.getInstance(zone).apply { timeInMillis = utcEpochMs }
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
        runSu("am broadcast -a android.intent.action.TIMEZONE_CHANGED")
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
