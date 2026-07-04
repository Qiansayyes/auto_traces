package com.autotraces.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** OCR 失败调试截屏（失败采集关闭时不保留）。 */
object OcrDebugKeeper {
    private const val MAX_FILES = 24
    private val lock = Any()

    fun saveOnFailure(source: File, tag: String) {
        if (!FailureReportUploader.UPLOAD_ENABLED) return
        if (!source.isFile || source.length() <= 0L) return
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return
        synchronized(lock) {
            val dir = File(ctx.filesDir, "ocr_debug").apply { mkdirs() }
            val safeTag = tag.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
            val dest = File(dir, "${System.currentTimeMillis()}_${safeTag}.png")
            runCatching {
                FileInputStream(source).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            prune(dir)
        }
    }

    fun listSnapshotFiles(): List<File> {
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return emptyList()
        val dir = File(ctx.filesDir, "ocr_debug")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun clearAfterUpload() {
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return
        synchronized(lock) {
            val dir = File(ctx.filesDir, "ocr_debug")
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.size <= MAX_FILES) return
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }
}
