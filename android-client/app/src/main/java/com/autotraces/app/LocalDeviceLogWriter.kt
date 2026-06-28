package com.autotraces.app

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 将 [LogCenter] 产生的操作日志追加到应用私有目录 `files/logs/yyyy-MM-dd.txt`，
 * 并删除早于「昨天」的日期文件（仅保留今天与昨天两份）。
 */
object LocalDeviceLogWriter {
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val tsFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private val logFilePattern = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.txt$")
    private val repeatSuffixPattern = Regex("""\s+\(×\d+\)$""")
    private val lock = Any()

    private var lastContent: String? = null
    private var lastLineStartOffset: Long = -1L
    private var lastLogFile: File? = null

    fun resetRepeatState() {
        synchronized(lock) {
            lastContent = null
            lastLineStartOffset = -1L
            lastLogFile = null
        }
    }

    fun append(alias: String?, content: String, epochMillis: Long, repeatCount: Int = 1) {
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return
        val normalized = content.replace(repeatSuffixPattern, "")
        synchronized(lock) {
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            prune(dir)
            val zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
            val day = dayFormatter.format(zoned.toLocalDate())
            val file = File(dir, "$day.txt")
            val line = buildLine(alias, normalized, epochMillis, repeatCount)
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8)

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

    /** 读取今天本地日志文件的全部行（管理台「下载今日日志」用）。 */
    fun readTodayFileLines(): List<String> {
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return emptyList()
        val dir = File(ctx.filesDir, "logs")
        if (!dir.isDirectory) return emptyList()
        val today = LocalDate.now(ZoneId.systemDefault())
        val file = File(dir, "${dayFormatter.format(today)}.txt")
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    /** 读取今天日志尾部若干行（管理台按需拉取；避免整日日志过大触发 Nginx 413）。 */
    fun readTodayFileTail(maxLines: Int = 400): List<String> {
        val cap = maxLines.coerceIn(1, 500)
        val all = readTodayFileLines()
        if (all.size <= cap) return all
        return all.takeLast(cap)
    }

    /** 读取今天与昨天的本地设备日志行（较新的在前）。 */
    fun readRecentLines(maxLines: Int = 400): List<String> {
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return emptyList()
        val dir = File(ctx.filesDir, "logs")
        if (!dir.isDirectory) return emptyList()
        val today = LocalDate.now(ZoneId.systemDefault())
        val days = listOf(today, today.minusDays(1))
        val lines = mutableListOf<String>()
        for (day in days) {
            val file = File(dir, "${dayFormatter.format(day)}.txt")
            if (!file.isFile) continue
            runCatching {
                file.readLines(Charsets.UTF_8).forEach { line ->
                    if (line.isNotBlank()) lines.add(line)
                }
            }
        }
        if (lines.size <= maxLines) return lines
        return lines.takeLast(maxLines)
    }
}
