package com.autotraces.app

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * 将 [LogCenter] 产生的操作日志追加到应用私有目录 `files/logs/yyyy-MM-dd.txt`，
 * 单文件超过 [MAX_FILE_BYTES] 时轮转为 `yyyy-MM-dd.1.txt` 等；写盘在后台单线程队列执行。
 */
object LocalDeviceLogWriter {
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024 // 2MB

    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val tsFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private val logFilePattern = Regex("""^(\d{4}-\d{2}-\d{2})(?:\.(\d+))?\.txt$""")
    private val repeatSuffixPattern = Regex("""\s+\(×\d+\)$""")
    private val lock = Any()

    private var lastContent: String? = null
    private var lastLineStartOffset: Long = -1L
    private var lastLogFile: File? = null

    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LocalDeviceLogWriter").apply { isDaemon = true }
    }

    fun resetRepeatState() {
        writeExecutor.execute { resetRepeatStateLocked() }
    }

    fun append(alias: String?, content: String, epochMillis: Long, repeatCount: Int = 1) {
        if (runCatching { PrefsStore.applicationContext() }.getOrNull() == null) return
        writeExecutor.execute {
            appendLocked(alias, content, epochMillis, repeatCount)
        }
    }

    private fun resetRepeatStateLocked() {
        synchronized(lock) {
            lastContent = null
            lastLineStartOffset = -1L
            lastLogFile = null
        }
    }

    private fun appendLocked(alias: String?, content: String, epochMillis: Long, repeatCount: Int) {
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return
        val normalized = content.replace(repeatSuffixPattern, "")
        synchronized(lock) {
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            prune(dir)
            val zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
            val day = dayFormatter.format(zoned.toLocalDate())
            val line = buildLine(alias, normalized, epochMillis, repeatCount)
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8)

            val file = resolveWritableFile(dir, day, lineBytes.size.toLong())
            if (lastLogFile != null && lastLogFile != file) {
                lastLineStartOffset = -1L
            }

            if (
                normalized == lastContent &&
                repeatCount > 1 &&
                lastLogFile == file &&
                lastLineStartOffset >= 0L
            ) {
                rewriteTail(file, lastLineStartOffset, lineBytes)
                return
            }

            lastContent = normalized
            lastLogFile = file
            lastLineStartOffset = file.length()
            FileOutputStream(file, true).use { it.write(lineBytes) }
        }
    }

    private fun resolveWritableFile(dir: File, day: String, incomingBytes: Long): File {
        var seq = 0
        while (true) {
            val file = segmentFile(dir, day, seq)
            if (!file.exists() || file.length() + incomingBytes <= MAX_FILE_BYTES) {
                return file
            }
            seq++
        }
    }

    private fun segmentFile(dir: File, day: String, seq: Int): File {
        val name = if (seq == 0) "$day.txt" else "$day.$seq.txt"
        return File(dir, name)
    }

    private fun buildLine(alias: String?, content: String, epochMillis: Long, repeatCount: Int): String {
        val username = PrefsStore.getLogContextUsername()?.trim().orEmpty().ifBlank { "-" }
        val aliasPart = alias?.trim().orEmpty().ifBlank { "未绑定设备" }
        val ts = tsFormatter.format(Instant.ofEpochMilli(epochMillis))
        val body = if (repeatCount > 1) "$content (×$repeatCount)" else content
        return "[$ts] $username | $aliasPart | $body\n"
    }

    private fun rewriteTail(file: File, startOffset: Long, lineBytes: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(startOffset)
            raf.write(lineBytes)
            raf.setLength(startOffset + lineBytes.size)
        }
    }

    private fun prune(logDir: File) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val cutoff = today.minusDays(1)
        logDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val match = logFilePattern.matchEntire(f.name) ?: return@forEach
            val fileDate = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return@forEach
            if (fileDate.isBefore(cutoff)) {
                runCatching { f.delete() }
            }
        }
    }

    private fun listSegmentFiles(dir: File, day: String): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { f ->
                val match = logFilePattern.matchEntire(f.name) ?: return@filter false
                match.groupValues[1] == day
            }
            ?.sortedWith(compareBy<File> { f ->
                logFilePattern.matchEntire(f.name)?.groupValues?.get(2)?.toIntOrNull() ?: 0
            })
            ?: emptyList()
    }

    /** 读取今天本地日志文件的全部行（管理台「下载今日日志」用）。 */
    fun readTodayFileLines(): List<String> {
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return emptyList()
        val dir = File(ctx.filesDir, "logs")
        val today = LocalDate.now(ZoneId.systemDefault())
        val day = dayFormatter.format(today)
        val lines = mutableListOf<String>()
        for (file in listSegmentFiles(dir, day)) {
            runCatching {
                file.readLines(Charsets.UTF_8).forEach { line ->
                    if (line.isNotBlank()) lines.add(line)
                }
            }
        }
        return lines
    }

    /** 读取今天日志尾部若干行（管理台按需拉取；tail 读文件，避免整日日志进内存）。 */
    fun readTodayFileTail(maxLines: Int = 400): List<String> {
        val cap = maxLines.coerceIn(1, 500)
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return emptyList()
        val dir = File(ctx.filesDir, "logs")
        val today = LocalDate.now(ZoneId.systemDefault())
        val files = listSegmentFiles(dir, dayFormatter.format(today))
        return collectTailFromFiles(files, cap)
    }

    /** 读取今天与昨天的本地设备日志行（较新的在前）。 */
    fun readRecentLines(maxLines: Int = 400): List<String> {
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return emptyList()
        val dir = File(ctx.filesDir, "logs")
        if (!dir.isDirectory) return emptyList()
        val today = LocalDate.now(ZoneId.systemDefault())
        val files = listSegmentFiles(dir, dayFormatter.format(today)) +
            listSegmentFiles(dir, dayFormatter.format(today.minusDays(1)))
        return collectTailFromFiles(files, maxLines.coerceAtLeast(1))
    }

    private fun collectTailFromFiles(files: List<File>, maxLines: Int): List<String> {
        if (files.isEmpty() || maxLines <= 0) return emptyList()
        val collected = ArrayDeque<String>()
        for (file in files.asReversed()) {
            if (collected.size >= maxLines) break
            val need = maxLines - collected.size
            for (line in readFileTailLines(file, need).asReversed()) {
                collected.addFirst(line)
                if (collected.size >= maxLines) break
            }
        }
        return collected.toList()
    }

    private fun readFileTailLines(file: File, maxLines: Int): List<String> {
        if (!file.isFile || file.length() == 0L || maxLines <= 0) return emptyList()
        var window = (maxLines * 256L).coerceAtLeast(8192)
        while (true) {
            val lines = readFileTailBytes(file, window).filter { it.isNotBlank() }
            if (lines.size >= maxLines || window >= file.length()) {
                return lines.takeLast(maxLines)
            }
            window = (window * 2).coerceAtMost(file.length())
        }
    }

    private fun readFileTailBytes(file: File, windowBytes: Long): List<String> {
        val length = file.length()
        if (length == 0L) return emptyList()
        val start = (length - windowBytes).coerceAtLeast(0)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            var text = String(bytes, StandardCharsets.UTF_8)
            if (start > 0) {
                val idx = text.indexOf('\n')
                text = if (idx >= 0) text.substring(idx + 1) else ""
            }
            return text.split('\n').map { it.trimEnd('\r') }
        }
    }
}
