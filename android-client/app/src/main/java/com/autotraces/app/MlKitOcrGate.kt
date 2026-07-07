package com.autotraces.app

import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Serializes all ML Kit OCR calls behind one shared [TextRecognizer].
 * Avoids native reentrancy / concurrent [NativePipelineImpl_close] crashes.
 */
object MlKitOcrGate {
    private val mutex = Mutex()
    private val recognizerLock = Any()

    @Volatile
    private var recognizer: TextRecognizer? = null

    private val warnScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val MIN_INTERVAL_MS = 250L
    /** 单次 OCR 最长等待；超时后 close 并重建 [TextRecognizer]，再抛 [OcrRecognizeTimeoutException]。 */
    const val RECOGNIZE_TIMEOUT_MS = 45_000L
    private const val SLOW_OCR_WARN_MS = 10_000L

    @Volatile
    private var lastCallFinishedAtMs = 0L

    @Volatile
    private var inFlightTag: String? = null

    @Volatile
    private var inFlightSinceMs: Long = 0L

    fun inFlightSummary(): String {
        val tag = inFlightTag?.trim().orEmpty()
        if (tag.isEmpty() || inFlightSinceMs <= 0L) return ""
        val sec = ((SystemClock.elapsedRealtime() - inFlightSinceMs) / 1000L).coerceAtLeast(0L)
        return "OCR进行中($tag,${sec}s)"
    }

    private fun client(): TextRecognizer {
        recognizer?.let { return it }
        synchronized(recognizerLock) {
            recognizer?.let { return it }
            return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
        }
    }

    private fun rebuildRecognizerAfterTimeout(tag: String) {
        synchronized(recognizerLock) {
            runCatching { recognizer?.close() }
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        val alias = PrefsStore.getAlias()?.trim().orEmpty().ifBlank { "SYSTEM" }
        LogCenter.op(alias, "[OCR重建] tag=$tag 超时后已 close 并重建 TextRecognizer")
    }

    suspend fun recognize(image: InputImage, tag: String? = null): Text {
        val label = tag?.trim()?.takeIf { it.isNotEmpty() }
            ?: ProcessDiagnostics.currentPhase().takeIf { it.isNotEmpty() && it != "idle" }
            ?: "ocr"
        return mutex.withLock {
            val elapsedSinceLast = SystemClock.elapsedRealtime() - lastCallFinishedAtMs
            val waitMs = MIN_INTERVAL_MS - elapsedSinceLast
            if (waitMs > 0) delay(waitMs)
            inFlightTag = label
            inFlightSinceMs = SystemClock.elapsedRealtime()
            var warnJob: Job? = null
            try {
                warnJob = warnScope.launch {
                    delay(SLOW_OCR_WARN_MS)
                    val alias = PrefsStore.getAlias()?.trim().orEmpty().ifBlank { "SYSTEM" }
                    val sec = ((SystemClock.elapsedRealtime() - inFlightSinceMs) / 1000L).coerceAtLeast(0L)
                    LogCenter.op(alias, "[OCR偏慢] tag=$label 已等待>${sec}s（阈值${SLOW_OCR_WARN_MS / 1000}s）")
                }
                withTimeout(RECOGNIZE_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        client().process(image)
                            .addOnSuccessListener { cont.resume(it) }
                            .addOnFailureListener { cont.resumeWithException(it) }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                val alias = PrefsStore.getAlias()?.trim().orEmpty().ifBlank { "SYSTEM" }
                val waitedSec =
                    ((SystemClock.elapsedRealtime() - inFlightSinceMs) / 1000L).coerceAtLeast(0L)
                LogCenter.op(
                    alias,
                    "[OCR超时] tag=$label 已等待>${waitedSec}s（上限${RECOGNIZE_TIMEOUT_MS / 1000}s），" +
                        "协程放弃等待并重建 TextRecognizer",
                )
                rebuildRecognizerAfterTimeout(label)
                ProcessDiagnostics.emitHeartbeat("OCR超时")
                throw OcrRecognizeTimeoutException(label, RECOGNIZE_TIMEOUT_MS, timeout)
            } finally {
                warnJob?.cancel()
                inFlightTag = null
                inFlightSinceMs = 0L
                lastCallFinishedAtMs = SystemClock.elapsedRealtime()
            }
        }
    }
}
