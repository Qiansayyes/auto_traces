package com.autotraces.app.videoded

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 同一 [taskId] 只允许一个下载+ACK 流程，避免 request 与 PC follow-pc 重复入队导致竞态。
 */
object TaskDownloadCoordinator {
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun isActive(taskId: String): Boolean = active.contains(normalize(taskId))

    /** 开始执行前调用；若已有同 taskId 流程在跑则返回 false。 */
    fun tryAcquire(taskId: String): Boolean {
        val key = normalize(taskId)
        return active.add(key)
    }

    fun release(taskId: String) {
        active.remove(normalize(taskId))
    }

    private fun normalize(taskId: String): String = taskId.trim()
}
