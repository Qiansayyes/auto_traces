package com.autotraces.app

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程存活与卡点诊断：周期性心跳、OCR 挂起观测、崩溃/OOM 前快照落盘。
 * 进程被杀后下次冷启会打出「上次崩溃」便于对照日志断档；心跳快照仅落盘不打日志。
 */
object ProcessDiagnostics {
    private const val HEARTBEAT_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    @Volatile
    private var processStartElapsedMs: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var currentPhase: String = "idle"

    @Volatile
    private var currentUsername: String? = null

    @Volatile
    private var snapshotProvider: (() -> String)? = null

    @Volatile
    private var heartbeatJob: Job? = null

    @Volatile
    private var defaultUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    fun registerSnapshotProvider(provider: () -> String) {
        snapshotProvider = provider
    }

    fun setPhase(phase: String) {
        currentPhase = phase.trim().ifBlank { "idle" }
    }

    fun setCurrentUsername(username: String?) {
        currentUsername = username?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun currentPhase(): String = currentPhase

    fun installCrashHandler() {
        if (defaultUncaughtHandler != null) return
        defaultUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            persistCrashSnapshot(thread, throwable)
            defaultUncaughtHandler?.uncaughtException(thread, throwable)
        }
    }

    fun noteProcessColdStart() {
        processStartElapsedMs = SystemClock.elapsedRealtime()
        val alias = PrefsStore.getAlias()?.trim().orEmpty().ifBlank { "SYSTEM" }
        val lastCrash = PrefsStore.getLastProcessCrashLine()
        LogCenter.op(
            alias,
            "[进程启动] pid=${Process.myPid()} 时间=${formatter.format(Date())}",
        )
        if (!lastCrash.isNullOrBlank()) {
            LogCenter.op(alias, "[进程恢复] 上次崩溃：$lastCrash")
        }
    }

    fun noteTrimMemory(level: Int) {
        val line = buildSnapshotLine(extra = "trimMemory=$level")
        PrefsStore.saveLastProcessHeartbeatLine(line)
    }

    fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                emitHeartbeat("周期")
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stopHeartbeat(reason: String) {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun emitHeartbeat(trigger: String = "周期") {
        val line = buildSnapshotLine(trigger = trigger)
        PrefsStore.saveLastProcessHeartbeatLine(line)
    }

    private fun buildSnapshotLine(
        trigger: String = "周期",
        extra: String? = null,
    ): String {
        val uptimeSec = ((SystemClock.elapsedRealtime() - processStartElapsedMs) / 1000L).coerceAtLeast(0L)
        val ocr = MlKitOcrGate.inFlightSummary()
        val execDepth = ExecutionScope.depth()
        val runtime = snapshotProvider?.invoke().orEmpty().trim()
        val parts = buildList {
            add("[进程心跳/$trigger]")
            add("pid=${Process.myPid()}")
            add("存活=${uptimeSec}s")
            add("阶段=${currentPhase}")
            currentUsername?.let { add("用户=$it") }
            add("exec=$execDepth")
            if (ocr.isNotBlank()) add(ocr)
            if (runtime.isNotBlank()) add(runtime)
            extra?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return parts.joinToString(" ")
    }

    private fun persistCrashSnapshot(thread: Thread, throwable: Throwable) {
        val alias = PrefsStore.getAlias()?.trim().orEmpty().ifBlank { "SYSTEM" }
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stack = sw.toString().lineSequence().take(12).joinToString(" | ")
        val line = buildString {
            append("[进程崩溃] 时间=${formatter.format(Date())}")
            append(" pid=${Process.myPid()}")
            append(" 线程=${thread.name}")
            append(" ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            append(" 阶段=$currentPhase")
            currentUsername?.let { append(" 用户=$it") }
            append(" exec=${ExecutionScope.depth()}")
            val ocr = MlKitOcrGate.inFlightSummary()
            if (ocr.isNotBlank()) append(" $ocr")
            snapshotProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" $it") }
            append(" stack=$stack")
        }
        PrefsStore.saveLastProcessCrashLine(line)
        runCatching {
            LocalDeviceLogWriter.append(alias, line, System.currentTimeMillis(), 1)
        }
    }
}
