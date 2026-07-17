package com.autotraces.app

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 登录状态保障 / 续接自动登录的全局单飞门闩。
 *
 * 覆盖：
 * - AppCoordinator 涨粉 / 发作品入队前 / 登录恢复 / 切号后校验
 * - TraceExecutor 互点/发作品冷启 OCR
 *
 * 可重入：同一协程外层已持锁时，内层 OCR（runLoginSafeguardMeasure）直接进入，避免死锁。
 *
 * 切号不会被长期搁置：PLAN 切号抢占会先 cancel 涨粉 / 登录恢复 / 互点协程，
 * 持锁方在 CancellationException 路径释放 Mutex，切号登录再获取单飞。
 */
object LoginSafeguardGate {
    private val mutex = Mutex()

    @Volatile
    private var holder: String = ""

    private object HeldKey : CoroutineContext.Key<HeldMarker>
    private class HeldMarker(val owner: String) : AbstractCoroutineContextElement(HeldKey)

    fun isHeld(): Boolean = mutex.isLocked

    fun currentHolder(): String = holder

    suspend fun isHeldInCurrentCoroutine(): Boolean =
        coroutineContext[HeldKey] != null

    /** 切号还原/登录校验来源：不被「登录保障恢复中」门闩直接跳过。 */
    fun isCutSwitchOwner(owner: String): Boolean {
        val s = owner.trim()
        if (s.isEmpty()) return false
        return s == "切号完成" ||
            s.startsWith("切号后") ||
            s.contains("restore_chain") ||
            s.contains("切号后校验") ||
            s.contains("切号后登录")
    }

    suspend fun <T> withExclusive(owner: String, block: suspend () -> T): T {
        if (coroutineContext[HeldKey] != null) {
            return block()
        }
        val alias = PrefsStore.getAlias()?.trim().orEmpty().ifBlank { "device" }
        if (mutex.isLocked) {
            val hold = holder.ifBlank { "?" }
            val note = if (isCutSwitchOwner(owner)) {
                "切号优先等待单飞释放（抢占应已取消持有方）"
            } else {
                "等待进行中的保障"
            }
            LogCenter.op(alias, "登录状态保障单飞：$note（持有者=$hold，申请=$owner）")
        }
        return mutex.withLock {
            holder = owner
            try {
                withContext(HeldMarker(owner)) {
                    block()
                }
            } finally {
                if (holder == owner) holder = ""
            }
        }
    }
}
