package com.autotraces.app.videoded

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * 同一 [taskId] 只允许一个下载+ACK 流程，避免 request 与 PC follow-pc 重复入队导致竞态。
 */
object TaskDownloadCoordinator {
    private const val TAG = "TaskDownloadCoordinator"
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

    /**
     * 等待同 taskId 的并行流程结束。
     * @return true 已空闲；false 超时仍占用
     */
    suspend fun awaitIdle(taskId: String, timeoutMs: Long = 600_000L): Boolean {
        val key = normalize(taskId)
        if (!active.contains(key)) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1_000L)
        Log.i(TAG, "awaitIdle start taskId=$key timeoutMs=$timeoutMs")
        while (active.contains(key)) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "awaitIdle timeout taskId=$key")
                return false
            }
            delay(400L)
        }
        Log.i(TAG, "awaitIdle done taskId=$key")
        return true
    }

    private fun normalize(taskId: String): String = taskId.trim()
}
