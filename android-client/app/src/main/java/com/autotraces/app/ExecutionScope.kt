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
}
