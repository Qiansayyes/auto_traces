package com.autotraces.app.videoded

import android.os.Handler
import android.os.Looper

/**
 * 主界面与 [StatusLogActivity] 共享的状态日志环形缓冲。
 * 追加可在任意线程调用；前台 [listener] 始终在主线程回调。
 */
object StatusLogBuffer {
    private const val MAX_LINES = 3000

    private val lock = Any()
    private val lines = ArrayList<String>(512)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: ((String) -> Unit)? = null

    fun setListener(l: ((String) -> Unit)?) {
        listener = l
        if (l != null) {
            val snap = snapshot()
            main.post { l(snap) }
        }
    }

    fun append(line: String) {
        val full: String
        synchronized(lock) {
            lines.add(line)
            while (lines.size > MAX_LINES) {
                lines.removeAt(0)
            }
            full = joinLinesLocked()
        }
        main.post { listener?.invoke(full) }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
        main.post { listener?.invoke("") }
    }

    fun snapshot(): String =
        synchronized(lock) {
            joinLinesLocked()
        }

    private fun joinLinesLocked(): String =
        if (lines.isEmpty()) {
            ""
        } else {
            buildString(lines.size * 32) {
                for (i in lines.indices) {
                    append(lines[i])
                    append('\n')
                }
            }
        }
}
