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
    private const val KEY_DAILY_PREFIX = "daily_action_"
    private const val KEY_BATCH_USERNAMES = "batch_usernames"
    private const val KEY_BATCH_COMPLETED = "batch_completed"
    /** 本批已跑过（成功、跳过或失败跳过），与 [KEY_BATCH_COMPLETED]（仅成功收尾）区分 */
    private const val KEY_BATCH_PROCESSED = "batch_processed"
    /** 每次 [savePulledBatch] 后递增，标记「当前拉取清单」代数 */
    private const val KEY_PULL_BATCH_SEQ = "pull_batch_seq"
    /** 上次向服务端校验绑定的时间；用于避免每次回到前台都请求 bindDevice。 */
    private const val KEY_LAST_BIND_REFRESH_MS = "last_bind_refresh_ms"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_AUTH_USERNAME = "auth_username"
    private const val KEY_AUTH_PASSWORD = "auth_password"
    private const val KEY_HARD_STOP_REQUESTED = "hard_stop_requested"
    /** 最近一次请求硬停的时间；用于忽略 opt_out 同步前的 check_start 竞态。 */
    private const val KEY_HARD_STOP_AT_MS = "hard_stop_at_ms"
    private const val KEY_MUTUAL_CLICK_REQUESTED = "mutual_click_requested"
    private const val KEY_MUTUAL_CLICK_PENDING = "mutual_click_pending"
    /** 下一趟 teamwork Videos 单元：为 true 时跳过左滑与作者主页，仅点赞/收藏/互点评论（历史指令 `mutual_click_comment_only_start`；管理台已合并入口，服务端仍可下发兼容）。 */
    private const val KEY_MUTUAL_COMMENT_ONLY_SESSION = "mutual_comment_only_session"
    /** 互点-仅关注：跳过左滑/作者区，仅打开评论区（区内步骤 TODO），不受模板引流动作勾选影响。 */
    private const val KEY_MUTUAL_FOLLOW_ONLY_SESSION = "mutual_follow_only_session"
    /** 管理台「发布作品」设备任务：与互点类似 requested+pending；互点会话进行中时置 queued_after_mutual */
    private const val KEY_PUBLISH_POST_REQUESTED = "publish_post_requested"
    private const val KEY_PUBLISH_POST_PENDING = "publish_post_pending"
    private const val KEY_PUBLISH_POST_QUEUED_AFTER_MUTUAL = "publish_post_queued_after_mutual"
    /** 下发 publish_post_start 时写入，供 TraceExecutor 内 `__publish_post__` 使用（包名来自 payload） */
    private const val KEY_PUBLISH_POST_PACKAGE = "publish_post_package_name"
    /**
     * 发作品：首条「Post Now / Don't allow / 底部 Cancel」权限类 OCR 按设备别名按自然日只跑一轮。
     * 值为上次完成该组操作的 yyyyMMdd（[Locale.US]）。
     */
    private const val KEY_PUBLISH_POST_FIRST_DAILY_PERM_OCR_DAY_PREFIX = "publish_post_first_daily_perm_ocr_day_"
    /**
     * 发作品断点续发：按账号键记录「下一条要发的 0-based 序号」（= 已成功条数）。
     * 第 N 条失败时不递增，下次仍从第 N+1 个图库坐标继续。
     */
    private const val KEY_PUBLISH_POST_RESUME_NEXT_INDEX_PREFIX = "publish_post_resume_next_index_"
    /** 与 check_start.config_push_revision 对齐；管理台「保存并下发」后服务端递增，本机成功拉取配置后写入 */
    private const val KEY_LAST_APPLIED_CONFIG_PUSH_REVISION = "last_applied_config_push_revision"
    /**
     * 与设备编辑表「切号高亮」及本机分配账号下拉当前选中一致，用于本地 txt 日志行首 username。
     */
    private const val KEY_LOG_CONTEXT_USERNAME = "log_context_username"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun applicationContext(): Context = appContext

    /** 当前写入本地设备日志时使用的 TikTok username（可为空，文件里会写为 `-`）。 */
    fun getLogContextUsername(): String? =
        prefs.getString(KEY_LOG_CONTEXT_USERNAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setLogContextUsername(username: String?) {
        val v = username?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_LOG_CONTEXT_USERNAME).apply()
        } else {
            prefs.edit().putString(KEY_LOG_CONTEXT_USERNAME, v).apply()
        }
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
            .remove(KEY_LAST_APPLIED_CONFIG_PUSH_REVISION)
            .remove(KEY_LOG_CONTEXT_USERNAME)
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
            .remove(KEY_LOG_CONTEXT_USERNAME)
            .apply()
    }

    fun setHardStopRequested(requested: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_HARD_STOP_REQUESTED, requested)
        if (requested) {
            editor.putLong(KEY_HARD_STOP_AT_MS, System.currentTimeMillis())
        } else {
            editor.remove(KEY_HARD_STOP_AT_MS)
        }
        editor.apply()
    }

    fun isHardStopRequested(): Boolean = prefs.getBoolean(KEY_HARD_STOP_REQUESTED, false)

    /** 硬停后短时间内忽略云端仍返回的 auto_start/running（等待 opt_out 与 paused 同步）。 */
    fun isWithinHardStopGracePeriod(graceMs: Long = 15_000L): Boolean {
        if (!isHardStopRequested()) return false
        val at = prefs.getLong(KEY_HARD_STOP_AT_MS, 0L)
        if (at <= 0L) return false
        return System.currentTimeMillis() - at < graceMs
    }

    fun setMutualClickRequested(requested: Boolean) {
        prefs.edit().putBoolean(KEY_MUTUAL_CLICK_REQUESTED, requested).apply()
    }

    fun isMutualClickRequested(): Boolean = prefs.getBoolean(KEY_MUTUAL_CLICK_REQUESTED, false)

    fun setMutualClickPending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_MUTUAL_CLICK_PENDING, pending).apply()
    }

    fun isMutualClickPending(): Boolean = prefs.getBoolean(KEY_MUTUAL_CLICK_PENDING, false)

    fun setMutualCommentOnlySession(active: Boolean) {
        prefs.edit().putBoolean(KEY_MUTUAL_COMMENT_ONLY_SESSION, active).apply()
    }

    fun isMutualCommentOnlySession(): Boolean = prefs.getBoolean(KEY_MUTUAL_COMMENT_ONLY_SESSION, false)

    fun setMutualFollowOnlySession(active: Boolean) {
        prefs.edit().putBoolean(KEY_MUTUAL_FOLLOW_ONLY_SESSION, active).apply()
    }

    fun isMutualFollowOnlySession(): Boolean = prefs.getBoolean(KEY_MUTUAL_FOLLOW_ONLY_SESSION, false)

    fun setPublishPostRequested(requested: Boolean) {
        prefs.edit().putBoolean(KEY_PUBLISH_POST_REQUESTED, requested).apply()
    }

    fun isPublishPostRequested(): Boolean = prefs.getBoolean(KEY_PUBLISH_POST_REQUESTED, false)

    fun setPublishPostPending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_PUBLISH_POST_PENDING, pending).apply()
    }

    fun isPublishPostPending(): Boolean = prefs.getBoolean(KEY_PUBLISH_POST_PENDING, false)

    fun setPublishPostQueuedAfterMutual(queued: Boolean) {
        prefs.edit().putBoolean(KEY_PUBLISH_POST_QUEUED_AFTER_MUTUAL, queued).apply()
    }

    fun isPublishPostQueuedAfterMutual(): Boolean = prefs.getBoolean(KEY_PUBLISH_POST_QUEUED_AFTER_MUTUAL, false)

    fun setPublishPostPackageName(packageName: String?) {
        val v = packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_PUBLISH_POST_PACKAGE).apply()
        } else {
            prefs.edit().putString(KEY_PUBLISH_POST_PACKAGE, v).apply()
        }
    }

    fun getPublishPostPackageName(): String? =
        prefs.getString(KEY_PUBLISH_POST_PACKAGE, null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun publishPostPermOcrPrefsKeyForAlias(alias: String): String =
        KEY_PUBLISH_POST_FIRST_DAILY_PERM_OCR_DAY_PREFIX + alias.trim().lowercase(Locale.US)

    /** 今日是否仍需在「该账号当天首条作品」上跑 Post Now / Don't allow / Cancel 三段 OCR。 */
    fun shouldRunPublishPostFirstDailyPermissionOcr(alias: String): Boolean {
        val a = alias.trim().lowercase(Locale.US)
        if (a.isEmpty()) return true
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val stored = prefs.getString(publishPostPermOcrPrefsKeyForAlias(a), null)?.trim().orEmpty()
        return stored != today
    }

    /** 首条作品当日权限 OCR 已成功跑完时调用（在整条发布成功之后）。 */
    fun markPublishPostFirstDailyPermissionOcrDone(alias: String) {
        val a = alias.trim().lowercase(Locale.US)
        if (a.isEmpty()) return
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        prefs.edit().putString(publishPostPermOcrPrefsKeyForAlias(a), today).apply()
    }

    private fun publishPostResumeNextIndexKey(accountKey: String): String =
        KEY_PUBLISH_POST_RESUME_NEXT_INDEX_PREFIX + accountKey.trim().lowercase(Locale.US)

    /** 下一条待发作品的 0-based 序号（已成功 [返回值] 条）。 */
    fun getPublishPostResumeNextIndex(accountKey: String): Int {
        val a = accountKey.trim().lowercase(Locale.US)
        if (a.isEmpty()) return 0
        return prefs.getInt(publishPostResumeNextIndexKey(a), 0).coerceAtLeast(0)
    }

    fun setPublishPostResumeNextIndex(accountKey: String, nextIndex: Int) {
        val a = accountKey.trim().lowercase(Locale.US)
        if (a.isEmpty()) return
        prefs.edit().putInt(publishPostResumeNextIndexKey(a), nextIndex.coerceAtLeast(0)).apply()
    }

    fun clearPublishPostResumeProgress(accountKey: String) {
        val a = accountKey.trim().lowercase(Locale.US)
        if (a.isEmpty()) return
        prefs.edit().remove(publishPostResumeNextIndexKey(a)).apply()
    }

    fun clearPublishPostState() {
        prefs.edit()
            .putBoolean(KEY_PUBLISH_POST_REQUESTED, false)
            .putBoolean(KEY_PUBLISH_POST_PENDING, false)
            .putBoolean(KEY_PUBLISH_POST_QUEUED_AFTER_MUTUAL, false)
            .remove(KEY_PUBLISH_POST_PACKAGE)
            .apply()
    }

    fun clearMutualClickState() {
        prefs.edit()
            .putBoolean(KEY_MUTUAL_CLICK_REQUESTED, false)
            .putBoolean(KEY_MUTUAL_CLICK_PENDING, false)
            .apply()
    }

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

    /** @param intervalMs 距上次刷新不足该间隔则跳过静默刷新（手动绑定不受限）。 */
    fun shouldRefreshBindingSilently(intervalMs: Long): Boolean {
        val last = prefs.getLong(KEY_LAST_BIND_REFRESH_MS, 0L)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= intervalMs
    }

    fun markBindingRefreshedNow() {
        prefs.edit().putLong(KEY_LAST_BIND_REFRESH_MS, System.currentTimeMillis()).apply()
    }

    fun getLastAppliedConfigPushRevision(): Int =
        prefs.getInt(KEY_LAST_APPLIED_CONFIG_PUSH_REVISION, 0).coerceAtLeast(0)

    fun saveLastAppliedConfigPushRevision(revision: Int) {
        prefs.edit().putInt(KEY_LAST_APPLIED_CONFIG_PUSH_REVISION, revision.coerceAtLeast(0)).apply()
    }

    @Synchronized
    fun savePulledBatch(usernames: List<String>) {
        val normalized = usernames.map { it.trim() }.filter { it.isNotBlank() }
        val nextSeq = prefs.getInt(KEY_PULL_BATCH_SEQ, 0) + 1
        prefs.edit()
            .putStringSet(KEY_BATCH_USERNAMES, normalized.toSet())
            .putStringSet(KEY_BATCH_COMPLETED, emptySet())
            .putStringSet(KEY_BATCH_PROCESSED, emptySet())
            .putInt(KEY_PULL_BATCH_SEQ, nextSeq)
            .apply()
    }

    @Synchronized
    fun markUsernameProcessed(username: String) {
        val key = username.trim()
        if (key.isBlank()) return
        val processed = prefs.getStringSet(KEY_BATCH_PROCESSED, emptySet())?.toMutableSet() ?: mutableSetOf()
        processed.add(key)
        prefs.edit().putStringSet(KEY_BATCH_PROCESSED, processed).apply()
    }

    @Synchronized
    fun markUsernameCompleted(username: String) {
        val key = username.trim()
        if (key.isBlank()) return
        markUsernameProcessed(key)
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

    /** 切号高亮 TikTok 账号；有值时每日行为计数按账号隔离，否则仍按整机。 */
    private fun dailyActionScopeSuffix(): String {
        val user = getLogContextUsername()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return ""
        return "_acct_${user.replace(Regex("[^a-z0-9._-]"), "_")}"
    }

    private fun dateKey(action: String) = "${KEY_DAILY_PREFIX}${action}_date${dailyActionScopeSuffix()}"
    private fun countKey(action: String) = "${KEY_DAILY_PREFIX}${action}_count${dailyActionScopeSuffix()}"

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
