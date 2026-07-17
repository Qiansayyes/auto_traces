package com.autotraces.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * 标记自动化执行中（用户名任务 / 设备指令 / 互点·发作品切换等），
 * 供 [LogCenter] 在已暂停时给日志加「暂停后收尾」前缀。
 */
object ExecutionScope {
    private val depth = AtomicInteger(0)

    fun enter() {
        depth.incrementAndGet()
    }

    fun exit() {
        depth.updateAndGet { v -> (v - 1).coerceAtLeast(0) }
    }

    fun isActive(): Boolean = depth.get() > 0

    fun depth(): Int = depth.get()

    /**
     * 切号收尾等场景：独占 OP 已空但旧涨粉/OCR 协程未协作退出，导致调度长期误判「任务执行中」。
     * 仅在确认真无在途独占 OP 时清零，避免永久卡死插入任务。
     */
    fun forceClearIfStuck(reason: String): Int {
        val before = depth.getAndSet(0)
        if (before > 0) {
            val alias = PrefsStore.getAlias()?.trim().orEmpty().ifBlank { "SYSTEM" }
            LogCenter.op(
                alias,
                "【自动切号】强制清除卡住的执行标记 depth=$before（$reason）",
            )
        }
        return before
    }
}
