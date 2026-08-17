package com.autotraces.app

/**
 * 发作品流程分两阶段：
 * 1. 素材准备（清图库 / PC 拉取）——云端自治下可不阻塞涨粉；**本机手动模式**在按钮入队时已抢占停涨粉；
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

    /** 素材已确认可用后进入发布独占；返回 false 表示应中止本次发作品（如登录未就绪）。 */
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

    /** 发作品独占发布中：禁止切号/对齐/涨粉等外部流程 force-stop 或抢占 TikTok。 */
    fun shouldDeferExclusivePreempt(): Boolean = exclusiveActive

    /**
     * 发作品独占发布中：禁止涨粉/对齐/入队等外部自动登录。
     * 仅允许发作品流程自身的登录重试（保障、发作品前）。
     */
    fun shouldBlockExternalAutoLogin(reason: String): Boolean {
        if (!exclusiveActive) return false
        val r = reason.trim()
        if (r.contains("发作品保障") || r.contains("发作品前")) return false
        return true
    }

    /** 发作品独占发布中：禁止外部登录状态保障（发作品冷启 OCR 仍走 TraceExecutor，但与 [LoginSafeguardGate] 单飞共用）。 */
    fun shouldBlockExternalLoginSafeguard(): Boolean = exclusiveActive
}
