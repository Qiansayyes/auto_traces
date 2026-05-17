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
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    @Volatile
    private var syncedCount = 0

    private fun withPausedWrapUpTag(content: String): String {
        if (!ExecutionScope.isActive()) return content
        if (PrefsStore.getLocalStatus() != "paused") return content
        if (content.startsWith(PAUSED_WRAP_UP_TAG)) return content
        return PAUSED_WRAP_UP_TAG + content
    }

    fun append(alias: String?, content: String) {
        if (!REALTIME_LOG_ENABLED) return
        val now = Date()
        val body = withPausedWrapUpTag(content)
        val line = "[${formatter.format(now)}] ${alias ?: "未绑定设备"} | $body"
        LocalDeviceLogWriter.append(alias, body, now.time)
        val merged = (_logs.value + line)
        _logs.value = if (merged.size > MAX_LOCAL_LOGS) merged.takeLast(MAX_LOCAL_LOGS) else merged
        if (syncedCount > _logs.value.size) {
            syncedCount = _logs.value.size
        }
    }

    /**
     * 设备端操作日志：一行只描述「当前在做什么」，可选附带耗时（毫秒）。
     */
    fun op(alias: String?, action: String, elapsedMs: Long? = null) {
        val tail = elapsedMs?.let { "，耗时 ${it}ms" } ?: ""
        append(alias, "$action$tail")
    }

    fun consumeUnsynced(maxLines: Int = 80): List<String> {
        val all = _logs.value
        if (syncedCount >= all.size) return emptyList()
        val unsynced = all.subList(syncedCount, all.size)
        val take = if (unsynced.size > maxLines) unsynced.takeLast(maxLines) else unsynced
        syncedCount = all.size
        return take
    }

    fun clear() {
        _logs.value = emptyList()
        syncedCount = 0
    }
}
