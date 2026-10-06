package com.autotraces.app

/**
 * 出口 IP 门阀已关闭：不再探测 ip-api，也不再拦截拉起 TikTok。
 */
object EgressIpGate {
    suspend fun awaitUntilNotChina(alias: String, tag: String = "出口IP门阀") {
        // no-op
    }
}
