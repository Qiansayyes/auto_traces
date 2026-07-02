package com.autotraces.app

/**
 * ML Kit OCR 在 [MlKitOcrGate.RECOGNIZE_TIMEOUT_MS] 内未返回。
 * 用于区分「协程已放弃等待」与后续 native 回调迟到的挂死。
 */
class OcrRecognizeTimeoutException(
    val tag: String,
    val timeoutMs: Long,
    cause: Throwable? = null,
) : Exception("OCR 超时 tag=$tag 等待>${timeoutMs}ms", cause)
