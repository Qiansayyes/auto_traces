package com.autotraces.app

/**
 * 项目级全局常量。
 *
 * 这里放所有“只要改一次，全局都生效”的值。
 */
object AppConstants {
    /**
     * 当前应用包名。
     *
     * 优先使用 BuildConfig.APPLICATION_ID，避免写死字符串后，
     * 以后改 applicationId 时需要全局手工搜替换。
     */
    const val APP_PACKAGE_NAME: String = BuildConfig.APPLICATION_ID
}
