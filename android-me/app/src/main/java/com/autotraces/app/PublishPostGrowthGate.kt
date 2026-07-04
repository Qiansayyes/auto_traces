package com.autotraces.app

/**
 * 发作品流程分两阶段：
 * 1. 素材准备（清图库 / PC 拉取）——不阻塞涨粉；
 * 2. 素材就绪后的 TikTok 发布独占阶段——暂停涨粉。
 */
object PublishPostGrowthGate {
    @Volatile
    var exclusiveActive: Boolean = false
        private set

    private var enterHandler: (suspend (String) -> Boolean)? = null

    fun installEnterHandler(handler: suspend (String) -> Boolean) {
        enterHandler = handler
    }

    suspend fun enterExclusivePublishPhase(alias: String): Boolean {
        exclusiveActive = true
        return try {
            val handler = enterHandler
            val ok = handler?.invoke(alias) ?: true
            if (!ok) exclusiveActive = false
            ok
        } catch (e: Throwable) {
            exclusiveActive = false
            throw e
        }
    }

    fun exitExclusivePublishPhase() {
        exclusiveActive = false
    }

    fun isBlockingGrowth(): Boolean = exclusiveActive

    fun shouldDeferExclusivePreempt(): Boolean = exclusiveActive

    fun shouldBlockExternalAutoLogin(reason: String): Boolean {
        if (!exclusiveActive) return false
        val r = reason.trim()
        if (r.contains("发作品保障") || r.contains("发作品前")) return false
        return true
    }

    fun shouldBlockExternalLoginSafeguard(): Boolean = exclusiveActive
}
