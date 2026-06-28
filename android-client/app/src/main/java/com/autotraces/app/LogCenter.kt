package com.autotraces.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogCenter {
    private const val REALTIME_LOG_ENABLED = true
    private const val MAX_LOCAL_LOGS = 500
    /** 已暂停但当前仍有执行中的自动化（收尾阶段） */
    private const val PAUSED_WRAP_UP_TAG = "[暂停后收尾] "
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val repeatSuffixPattern = Regex("""\s+\(×\d+\)$""")
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private var lastBody: String? = null
    private var lastRepeatCount = 0

    private fun withPausedWrapUpTag(content: String): String {
        if (!ExecutionScope.isActive()) return content
        if (PrefsStore.getLocalStatus() != "paused") return content
        if (content.startsWith(PAUSED_WRAP_UP_TAG)) return content
        return PAUSED_WRAP_UP_TAG + content
    }

    private fun stripRepeatSuffix(body: String): String = body.replace(repeatSuffixPattern, "")

    private fun formatDisplayBody(body: String, repeatCount: Int): String {
        if (repeatCount <= 1) return body
        return "$body (×$repeatCount)"
    }

    private fun formatUiLine(alias: String?, body: String, now: Date): String {
        val displayAlias = alias ?: "未绑定设备"
        return "[${formatter.format(now)}] $displayAlias | $body"
    }

    fun append(alias: String?, content: String) {
        if (!REALTIME_LOG_ENABLED) return
        val now = Date()
        val body = stripRepeatSuffix(withPausedWrapUpTag(content))
        val isRepeat = body == lastBody
        if (isRepeat) {
            lastRepeatCount++
        } else {
            lastBody = body
            lastRepeatCount = 1
        }
        val displayBody = formatDisplayBody(body, lastRepeatCount)
        val line = formatUiLine(alias, displayBody, now)
        val current = _logs.value
        _logs.value = if (isRepeat && current.isNotEmpty()) {
            val merged = current.dropLast(1) + line
            if (merged.size > MAX_LOCAL_LOGS) merged.takeLast(MAX_LOCAL_LOGS) else merged
        } else {
            val merged = current + line
            if (merged.size > MAX_LOCAL_LOGS) merged.takeLast(MAX_LOCAL_LOGS) else merged
        }
        LocalDeviceLogWriter.append(alias, body, now.time, lastRepeatCount)
    }

    /**
     * 设备端操作日志：一行只描述「当前在做什么」，可选附带耗时（毫秒）。
     */
    fun op(alias: String?, action: String, elapsedMs: Long? = null) {
        val tail = elapsedMs?.let { "，耗时 ${it}ms" } ?: ""
        append(alias, "$action$tail")
    }

    fun clear() {
        lastBody = null
        lastRepeatCount = 0
        LocalDeviceLogWriter.resetRepeatState()
        _logs.value = emptyList()
    }
}
