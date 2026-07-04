package com.autotraces.app.videoded

import android.net.Uri

/**
 * 供 **与 Companion 同签名** 的异包应用通过 [ContentProvider] 读取本机 API 绑定信息。
 * 不同签名时无法在运行时自动取 Token，请在 UI 中手动配置 `http://127.0.0.1:[CompanionBridge.API_PORT]` + Token。
 */
object CompanionBridge {
    const val AUTHORITY = "com.autotraces.app.videoded.bridge"
    const val PATH_BINDING = "binding"
    val BINDING_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_BINDING")

    /** 与 [AndroidManifest] 中 `permission` 的 name 一致；protectionLevel=signature。 */
    const val PERMISSION_BRIDGE_READ = "com.autotraces.app.videoded.permission.BRIDGE_READ"

    /** 与 [ApiServer] 监听端口一致，便于异包写死 Base URL。 */
    const val API_PORT = 18088

    const val COLUMN_TOKEN = "token"
    const val COLUMN_BASE_URL = "base_url"
    const val COLUMN_LISTEN_PORT = "listen_port"
}
