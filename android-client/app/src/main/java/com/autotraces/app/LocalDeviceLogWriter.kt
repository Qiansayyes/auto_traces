package com.autotraces.app

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
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
    private val lock = Any()

    fun append(alias: String?, content: String, epochMillis: Long) {
        val ctx = runCatching { PrefsStore.applicationContext() }.getOrNull() ?: return
        synchronized(lock) {
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            prune(dir)
            val zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
            val day = dayFormatter.format(zoned.toLocalDate())
            val file = File(dir, "$day.txt")
            val username = PrefsStore.getLogContextUsername()?.trim().orEmpty().ifBlank { "-" }
            val aliasPart = alias?.trim().orEmpty().ifBlank { "未绑定设备" }
            val ts = tsFormatter.format(Instant.ofEpochMilli(epochMillis))
            val line = "[$ts] $username | $aliasPart | $content\n"
            OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8).use { it.write(line) }
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
