package com.autotraces.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PrefsStore {
    private const val PREFS_NAME = "auto_traces_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_ALIAS = "device_alias"
    private const val KEY_LOCAL_STATUS = "local_status"
    private const val KEY_SERVER_BASE_URL = "server_base_url"
    private const val KEY_PULL_COUNT = "pull_count"
    /** 与设置页「每次拉取数量」范围一致（1～10）；未保存时使用该默认值 */
    private const val DEFAULT_PULL_COUNT = 10
    private const val KEY_DAILY_PREFIX = "daily_action_"
    private const val KEY_BATCH_USERNAMES = "batch_usernames"
    private const val KEY_BATCH_COMPLETED = "batch_completed"
    /** 上次向服务端校验绑定的时间；用于避免每次回到前台都请求 bindDevice。 */
    private const val KEY_LAST_BIND_REFRESH_MS = "last_bind_refresh_ms"
    /** 设备端选择的任务队列/运行模式，与 get_config/pull_username 的 run_mode 参数一致 */
    private const val KEY_DEVICE_RUN_MODE = "device_run_mode"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_AUTH_USERNAME = "auth_username"
    private const val KEY_AUTH_PASSWORD = "auth_password"
    private const val KEY_HARD_STOP_REQUESTED = "hard_stop_requested"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveBinding(deviceId: String, alias: String) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_ALIAS, alias)
            .apply()
    }

    fun clearBinding() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_ALIAS)
            .putString(KEY_LOCAL_STATUS, "online")
            .putLong(KEY_LAST_BIND_REFRESH_MS, 0L)
            .apply()
    }

    fun saveAuthToken(token: String, username: String) {
        prefs.edit()
            .putString(KEY_AUTH_TOKEN, token)
            .putString(KEY_AUTH_USERNAME, username)
            .apply()
    }

    fun saveAuthPassword(password: String) {
        prefs.edit()
            .putString(KEY_AUTH_PASSWORD, password)
            .apply()
    }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_AUTH_USERNAME)
            .remove(KEY_AUTH_PASSWORD)
            .apply()
    }

    fun setHardStopRequested(requested: Boolean) {
        prefs.edit().putBoolean(KEY_HARD_STOP_REQUESTED, requested).apply()
    }

    fun isHardStopRequested(): Boolean = prefs.getBoolean(KEY_HARD_STOP_REQUESTED, false)

    fun getAuthToken(): String? = prefs.getString(KEY_AUTH_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getAuthUsername(): String? = prefs.getString(KEY_AUTH_USERNAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getAuthPassword(): String? = prefs.getString(KEY_AUTH_PASSWORD, null)?.takeIf { it.isNotEmpty() }

    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    fun getAlias(): String? = prefs.getString(KEY_DEVICE_ALIAS, null)

    fun isBound(): Boolean = !getDeviceId().isNullOrBlank() && !getAlias().isNullOrBlank()

    fun saveLocalStatus(status: String) {
        prefs.edit().putString(KEY_LOCAL_STATUS, status).apply()
    }

    fun getLocalStatus(): String = prefs.getString(KEY_LOCAL_STATUS, "online") ?: "online"

    fun saveServerBaseUrl(baseUrl: String) {
        prefs.edit().putString(KEY_SERVER_BASE_URL, baseUrl).apply()
    }

    fun getServerBaseUrl(): String? = prefs.getString(KEY_SERVER_BASE_URL, null)?.trim()

    fun savePullCount(count: Int) {
        prefs.edit().putInt(KEY_PULL_COUNT, count.coerceIn(1, 10)).apply()
    }

    fun getPullCount(): Int = prefs.getInt(KEY_PULL_COUNT, DEFAULT_PULL_COUNT).coerceIn(1, 10)

    fun getDeviceRunMode(): String {
        val raw = prefs.getString(KEY_DEVICE_RUN_MODE, ServerRunMode.GROWTH)?.trim()?.lowercase()
        return when (raw) {
            ServerRunMode.LIVE, ServerRunMode.DIRECT, ServerRunMode.GROWTH -> raw
            else -> ServerRunMode.GROWTH
        }
    }

    fun saveDeviceRunMode(mode: String) {
        val m = when (mode.trim().lowercase()) {
            ServerRunMode.LIVE, ServerRunMode.DIRECT, ServerRunMode.GROWTH -> mode.trim().lowercase()
            else -> ServerRunMode.GROWTH
        }
        prefs.edit().putString(KEY_DEVICE_RUN_MODE, m).apply()
    }

    /** @param intervalMs 距上次刷新不足该间隔则跳过静默刷新（手动绑定不受限）。 */
    fun shouldRefreshBindingSilently(intervalMs: Long): Boolean {
        val last = prefs.getLong(KEY_LAST_BIND_REFRESH_MS, 0L)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= intervalMs
    }

    fun markBindingRefreshedNow() {
        prefs.edit().putLong(KEY_LAST_BIND_REFRESH_MS, System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun savePulledBatch(usernames: List<String>) {
        val normalized = usernames.map { it.trim() }.filter { it.isNotBlank() }
        prefs.edit()
            .putStringSet(KEY_BATCH_USERNAMES, normalized.toSet())
            .putStringSet(KEY_BATCH_COMPLETED, emptySet())
            .apply()
    }

    @Synchronized
    fun markUsernameCompleted(username: String) {
        val key = username.trim()
        if (key.isBlank()) return
        val completed = prefs.getStringSet(KEY_BATCH_COMPLETED, emptySet())?.toMutableSet() ?: mutableSetOf()
        completed.add(key)
        prefs.edit().putStringSet(KEY_BATCH_COMPLETED, completed).apply()
    }

    @Synchronized
    fun isUsernameCompleted(username: String): Boolean {
        val key = username.trim()
        if (key.isBlank()) return false
        return prefs.getStringSet(KEY_BATCH_COMPLETED, emptySet())?.contains(key) == true
    }

    @Synchronized
    fun filterPendingFromLastBatch(candidates: List<String>): List<String> {
        val completed = prefs.getStringSet(KEY_BATCH_COMPLETED, emptySet()) ?: emptySet()
        return candidates.map { it.trim() }.filter { it.isNotBlank() && !completed.contains(it) }
    }

    private fun todayKey(): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        return fmt.format(Date())
    }

    private fun dateKey(action: String) = "${KEY_DAILY_PREFIX}${action}_date"
    private fun countKey(action: String) = "${KEY_DAILY_PREFIX}${action}_count"

    /**
     * 返回某动作的“今日已执行次数”。会在跨天时自动清零。
     * action 建议取值：like/comment/favorite/share
     */
    @Synchronized
    fun getDailyActionCount(action: String): Int {
        val today = todayKey()
        val dKey = dateKey(action)
        val cKey = countKey(action)
        val savedDate = prefs.getString(dKey, null)
        if (savedDate != today) {
            prefs.edit()
                .putString(dKey, today)
                .putInt(cKey, 0)
                .apply()
            return 0
        }
        return prefs.getInt(cKey, 0).coerceAtLeast(0)
    }

    /**
     * 某动作“今日次数 +1”，会在跨天时自动从 0 开始计数并返回最新值。
     */
    @Synchronized
    fun incrementDailyActionCount(action: String): Int {
        val current = getDailyActionCount(action)
        val next = current + 1
        prefs.edit().putInt(countKey(action), next).apply()
        return next
    }
}
