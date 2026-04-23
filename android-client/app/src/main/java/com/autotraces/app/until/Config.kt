package com.autotraces.app.util

/**
 * TikTok自动化配置常量类
 */
object Config {
    // TikTok 包名（常见为 com.zhiliaoapp.musically，国际版部分为 com.tiktok.android）
    const val TIKTOK_PACKAGE_NAME = "com.zhiliaoapp.musically"
    // 云端服务器基础地址
    const val SERVER_BASE_URL = "http://192.168.100.249:8000"
    
    // TikTok界面控件文本（国际版英文）
    const val TEXT_SEARCH = "Search"
    const val TEXT_FOLLOWERS = "Followers"
    
    // 网络请求超时时间
    const val NETWORK_TIMEOUT = 10L
}