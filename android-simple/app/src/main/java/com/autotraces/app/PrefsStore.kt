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
    /** 最近一次成功同步到云端的 status；与 [KEY_LOCAL_STATUS] 区分，避免重复 POST /api/sync_status。 */
    private const val KEY_LAST_SYNCED_CLOUD_STATUS = "last_synced_cloud_status"
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
    /** 手动模式：引流/发作品/改资料/初次自动登录完成后是否自动进入涨粉。 */
    private const val KEY_AUTO_GROWTH_AFTER_TASKS = "manual_auto_growth_after_tasks"
    /** 手动模式：切号下拉当前选中的 username（切号目标，未必已切上机）。 */
    private const val KEY_MANUAL_SELECTED_USERNAME = "manual_selected_username"
    /** 手动模式：本机当前已切上/正在使用的 username（切号备份源）。 */
    private const val KEY_MANUAL_ACTIVE_USERNAME = "manual_active_username"
    /** 手动模式：最近一次拉取的分配账号列表 JSON（进程重启后凭据不丢）。 */
    private const val KEY_MANUAL_ACCOUNTS_CACHE_JSON = "manual_accounts_cache_json"
    /** 手动模式：账号是否已就绪（切号还原或自动登录成功后置位，按 username 存）。 */
    private const val KEY_MANUAL_FIRST_LOGIN_DONE_PREFIX = "manual_first_login_done_"
    /** 手动模式：本机开启自动切号自治（不依赖管理台开关）。 */
    private const val KEY_MANUAL_LOCAL_AUTO_SWITCH = "manual_local_auto_switch_enabled"
    /** 手动模式：本机自动切号每号时段分钟数。 */
    private const val KEY_MANUAL_AUTO_SWITCH_SLOT_MINUTES = "manual_auto_switch_slot_minutes"
    /** 手动模式：For You 预滑时长（秒）。 */
    private const val KEY_MANUAL_FOR_YOU_SWIPE_SEC = "manual_for_you_swipe_sec"
    const val DEFAULT_MANUAL_FOR_YOU_SWIPE_SEC: Int = 60
    const val MIN_MANUAL_FOR_YOU_SWIPE_SEC: Int = 10
    const val MAX_MANUAL_FOR_YOU_SWIPE_SEC: Int = 3600
    /** 手动模式：待活跃队列（有序 JSON 数组）。 */
    private const val KEY_MANUAL_PENDING_ACTIVATE_QUEUE = "manual_pending_activate_queue_json"
    /** 手动模式：活跃时长（内部存分钟；界面以小时展示）。 */
    private const val KEY_MANUAL_ACTIVATE_DURATION_MIN = "manual_activate_duration_min"
    const val DEFAULT_MANUAL_ACTIVATE_DURATION_MIN: Int = 30
    /** 0.5 小时 */
    const val MIN_MANUAL_ACTIVATE_DURATION_MIN: Int = 30
    /** 24 小时 */
    const val MAX_MANUAL_ACTIVATE_DURATION_MIN: Int = 1440
    const val MIN_MANUAL_ACTIVATE_DURATION_HOURS: Double = 0.5
    const val MAX_MANUAL_ACTIVATE_DURATION_HOURS: Double = 24.0
    const val DEFAULT_MANUAL_ACTIVATE_DURATION_HOURS: Double = 0.5
    /**
     * 手动切号方案：
     * - backup（默认）：备份当前号 → 清理 → 还原/登录
     * - login：不备份不还原，清理后直接登录目标号
     */
    private const val KEY_MANUAL_CUT_SWITCH_SCHEME = "manual_cut_switch_scheme"
    const val CUT_SWITCH_SCHEME_BACKUP: String = "backup"
    const val CUT_SWITCH_SCHEME_LOGIN: String = "login"
    /** 批量发作品：每号发完后 For You/涨粉随机活跃时长（分钟）。 */
    private const val KEY_MANUAL_BATCH_PUBLISH_AFTER_MIN = "manual_batch_publish_after_min"
    const val DEFAULT_MANUAL_BATCH_PUBLISH_AFTER_MIN: Int = 10
    const val MIN_MANUAL_BATCH_PUBLISH_AFTER_MIN: Int = 1
    const val MAX_MANUAL_BATCH_PUBLISH_AFTER_MIN: Int = 180
    /** 各账号累计活跃时长（分钟），JSON 对象 username→minutes。 */
    private const val KEY_MANUAL_ACTIVATE_TOTAL_MINUTES = "manual_activate_total_minutes_json"
    /** 各账号待活跃队列累计涨粉模式时长（分钟）。 */
    private const val KEY_MANUAL_ACTIVATE_GROWTH_MINUTES = "manual_activate_growth_minutes_json"
    /** 各账号待活跃队列累计 For You 预滑时长（分钟）。 */
    private const val KEY_MANUAL_ACTIVATE_FOR_YOU_MINUTES = "manual_activate_for_you_minutes_json"
    /** 各账号最近一次加入待活跃队列时间（epoch ms）。 */
    private const val KEY_MANUAL_PENDING_ACTIVATE_ENQUEUE_AT = "manual_pending_activate_enqueue_at_json"
    /** 待活跃未完成会话：username → 剩余秒数（中断后续跑，不切号）。 */
    private const val KEY_MANUAL_ACTIVATE_SESSION_REMAINING = "manual_activate_session_remaining_json"
    /** 与剩余秒数配套的 plannedMin；改「活跃时长」后不再沿用旧续跑。 */
    private const val KEY_MANUAL_ACTIVATE_SESSION_PLANNED_MIN = "manual_activate_session_planned_min_json"
    /** 三天总赞增长 ≤ 该阈值则推荐入待活跃队列。 */
    private const val KEY_PENDING_ACTIVATE_HEART_GROWTH_MAX = "pending_activate_heart_growth_max"
    const val DEFAULT_PENDING_ACTIVATE_HEART_GROWTH_MAX: Int = 10
    const val MIN_PENDING_ACTIVATE_HEART_GROWTH_MAX: Int = 0
    const val MAX_PENDING_ACTIVATE_HEART_GROWTH_MAX: Int = 1_000_000
    /** 推荐计算结果缓存（8 小时内复用）。 */
    private const val KEY_PENDING_ACTIVATE_RECOMMEND_CACHE_JSON = "pending_activate_recommend_cache_json"
    const val PENDING_ACTIVATE_RECOMMEND_CACHE_TTL_MS: Long = 8L * 60L * 60L * 1000L
    /** 手动模式：发作品目标条数（1–5）。 */
    private const val KEY_MANUAL_PUBLISH_COUNT = "manual_publish_count"
    /**
     * 运行模式：true=本机手动（simple）；false=云端 client。
     * 默认本机手动，与 android-simple 历史行为一致。
     */
    private const val KEY_MANUAL_CONTROL_MODE = "manual_control_mode_enabled"
    /** 最近一次从「待注册TK账号」领取并转入邮箱账户管理的邮箱。 */
    private const val KEY_ISSUED_TK_PENDING_EMAIL = "issued_tk_pending_email"
    private const val KEY_ISSUED_TK_PENDING_EMAIL_PASSWORD = "issued_tk_pending_email_password"
    const val DEFAULT_MANUAL_PUBLISH_COUNT: Int = 1
    const val MIN_MANUAL_PUBLISH_COUNT: Int = 1
    const val MAX_MANUAL_PUBLISH_COUNT: Int = 5
    private const val KEY_HARD_STOP_REQUESTED = "hard_stop_requested"
    /** 最近一次请求硬停的时间；用于忽略 opt_out 同步前的 check_start 竞态。 */
    private const val KEY_HARD_STOP_AT_MS = "hard_stop_at_ms"
    /** 后台 WorkerService 是否应该被 watchdog/自恢复持续保活。 */
    private const val KEY_WORKER_SERVICE_SHOULD_RUN = "worker_service_should_run"
    /** 最近一次请求拉起 WorkerService 的时间（用于节流与幂等）。 */
    private const val KEY_WORKER_SERVICE_LAST_START_REQUEST_AT_MS = "worker_service_last_start_request_at_ms"
    private const val KEY_WORKER_SERVICE_LAST_START_REASON = "worker_service_last_start_reason"
    /**
     * 备份/清理/切号前为收尾而 sync 的 paused；登录保障通过并恢复涨粉后清除。
     * 避免 check_start 仍返回 status=paused 时轮询把本机再次打回暂停。
     */
    private const val KEY_TRANSIENT_PAUSED_FOR_DESTRUCTIVE_OP = "transient_paused_for_destructive_op"
    /** 自治切号/发作品等独占 OP 已抢占 TikTok：涨粉应协作退出且不得再拉起 TikTok。 */
    private const val KEY_AUTO_SWITCH_EXCLUSIVE_PREEMPT = "auto_switch_exclusive_preempt"
    /**
     * 登录保障未通过后的防抖门闩：阻止涨粉恢复 + 发作品入队前每分钟 OCR 刷屏。
     * [KEY_AUTO_SWITCH_LOGIN_RECOVERY_ACTIVE] 为真时登录恢复协程持续重试，hold 到期仅续期防抖、不停止登录。
     * 登录成功或账号切换时 [clearAutoSwitchTikTokLoginHold]。
     */
    private const val KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_UNTIL_MS = "auto_switch_tiktok_login_hold_until_ms"
    private const val KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_SLOT_KEY = "auto_switch_tiktok_login_hold_slot_key"
    private const val KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_REASON = "auto_switch_tiktok_login_hold_reason"
    private const val KEY_AUTO_SWITCH_LOGIN_RECOVERY_ACTIVE = "auto_switch_login_recovery_active"
    private const val AUTO_SWITCH_TIKTOK_LOGIN_HOLD_DEFAULT_MS = 5L * 60_000L
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
    /** 自动切号发作品：云端按 video_count 下发的本次目标发布条数；null/未指定=沿用运行配置；0=明确跳过发布。 */
    private const val KEY_PUBLISH_POST_TARGET_COUNT = "publish_post_target_count"
    /**
     * 发作品：首条「Post Now / Don't allow / 底部 Cancel」权限类 OCR 按设备别名按自然日只跑一轮。
     * 值为上次完成该组操作的 yyyyMMdd（[Locale.US]）。
     */
    private const val KEY_PUBLISH_POST_FIRST_DAILY_PERM_OCR_DAY_PREFIX = "publish_post_first_daily_perm_ocr_day_"
    /**
     * 发作品断点续发：按账号键记录「下一条要发的 0-based 序号」（= 已成功条数）。
     * 第 N 条失败时不递增，下次仍从第 N+1 个图库坐标继续。
     * 仅在同一次云端 [publish_post_start] 指令（[KEY_PUBLISH_POST_TASK_OP_ID]）内有效。
     */
    private const val KEY_PUBLISH_POST_RESUME_NEXT_INDEX_PREFIX = "publish_post_resume_next_index_"
    private const val KEY_PUBLISH_POST_RESUME_OP_ID_PREFIX = "publish_post_resume_op_id_"
    /** 当前进行中的发作品云端指令 op_id；新指令或硬停后清除，断点不再跨指令复用。 */
    private const val KEY_PUBLISH_POST_TASK_OP_ID = "publish_post_task_op_id"
    private const val KEY_AUTO_SWITCH_MUTUAL_SLOT_KEY = "auto_switch_mutual_slot_key"
    /** 本轮发作品实际成功条数（全部完成后写入，供 ledger 写回 video_count）。 */
    private const val KEY_PUBLISH_POST_LAST_ACTUAL_SUCCESS_COUNT = "publish_post_last_actual_success_count"
    /** 发作品素材已拉取并就绪；进程被杀后可凭此跳过清库/重拉并续发。 */
    private const val KEY_PUBLISH_POST_MATERIAL_READY = "publish_post_material_ready"
    /** 发作品保障重登成功后跳过一次冷启 force-stop 与登录保障（一次性消费）。 */
    private const val KEY_PUBLISH_POST_SKIP_NEXT_COLD_LAUNCH_SAFEGUARD = "publish_post_skip_next_cold_launch_safeguard"
    /** 自动切号自治模式：off | on | exiting */
    private const val KEY_AUTO_SWITCH_MODE = "auto_switch_mode"
    private const val KEY_AUTO_SWITCH_SESSION_ID = "auto_switch_session_id"
    private const val KEY_AUTO_SWITCH_BUNDLE_REVISION = "auto_switch_bundle_revision"
    /** 自治期 getConfig 与 bundle_revision 对齐，未变则跳过拉取。 */
    private const val KEY_AUTO_SWITCH_LAST_CONFIG_FETCH_REVISION = "auto_switch_last_config_fetch_revision"
    /** 最近一次从 bundle 成功应用的 TikTok 账号（fetchConfig 早退校验用）。 */
    private const val KEY_AUTO_SWITCH_LAST_BUNDLED_USERNAME = "auto_switch_last_bundled_username"
    private const val KEY_AUTO_SWITCH_LAST_SLOT_KEY = "auto_switch_last_slot_key"
    /** 改资料三步断点续接：按账号键存 JSON（[ProfileEditStepState]）。 */
    private const val KEY_PROFILE_EDIT_STEP_STATE_PREFIX = "profile_edit_step_state_"
    /** 最近一次进程心跳快照（进程被杀后可对照断档前卡点）。 */
    private const val KEY_LAST_PROCESS_HEARTBEAT_LINE = "last_process_heartbeat_line"
    /** 最近一次未捕获崩溃快照。 */
    private const val KEY_LAST_PROCESS_CRASH_LINE = "last_process_crash_line"
    private const val KEY_AUTO_SWITCH_SCHEDULE_DATE = "auto_switch_schedule_date"
    private const val KEY_AUTO_SWITCH_WEEK_START = "auto_switch_week_start_date"
    private const val KEY_AUTO_SWITCH_WEEK_END = "auto_switch_week_end_date"
    private const val KEY_AUTO_SWITCH_SESSION_STARTED_AT = "auto_switch_session_started_at"
    private const val KEY_AUTO_SWITCH_LEDGER_JSON = "auto_switch_ledger_json"
    private const val KEY_AUTO_SWITCH_PENDING_SUMMARY = "auto_switch_pending_summary"
    private const val KEY_AUTO_SWITCH_LAST_SCHEDULER_REVISION = "auto_switch_last_scheduler_revision"
    private const val KEY_AUTO_SWITCH_ENTER_DONE_EVENT_ID = "auto_switch_enter_done_event_id"
    private const val KEY_AUTO_SWITCH_EXIT_DONE_EVENT_ID = "auto_switch_exit_done_event_id"
    private const val KEY_AUTO_SWITCH_DAILY_RECONCILE_DATE = "auto_switch_daily_reconcile_date"
    private const val KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_DATE = "auto_switch_daily_reconcile_target_date"
    private const val KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_MS = "auto_switch_daily_reconcile_target_ms"
    private const val KEY_LAST_MODE_EVENT_ID = "last_mode_event_id"
    /**
     * 发作品资料页进图库入口布局：按 [KEY_DEVICE_ID] 持久化（A=右下固定点，B=左侧备用点）。
     * 同设备不同账号发布时优先沿用，减少 A→B 全量试探。
     */
    private const val KEY_PUBLISH_POST_ENTRY_LAYOUT_PREFIX = "publish_post_entry_layout_"
    /**
     * 发作品收尾已点 Home、TikTok 仍在 For You 主界面时置 true；
     * 下次涨粉拉取跳过登录保障/强停重拉，直接点右上角放大镜。
     */
    private const val KEY_GROWTH_CONTINUE_FROM_PUBLISH_HOME = "growth_continue_from_publish_home"
    /** 与 check_start.config_push_revision 对齐；管理台「保存并下发」后服务端递增，本机成功拉取配置后写入 */
    private const val KEY_LAST_APPLIED_CONFIG_PUSH_REVISION = "last_applied_config_push_revision"
    /**
     * 与设备编辑表「切号高亮」及本机分配账号下拉当前选中一致，用于本地 txt 日志行首 username。
     */
    private const val KEY_LOG_CONTEXT_USERNAME = "log_context_username"
    /** 失败采集：已上传的 business|error_type 去重键集合 */
    private const val KEY_FAILURE_REPORT_UPLOADED = "failure_report_uploaded_keys"
    /** TikTok 安装后 root 图库/相机/麦克风权限一键开放：记录已处理的 lastUpdateTime（0=未处理）。 */
    private const val KEY_TIKTOK_MEDIA_PERMISSION_GRANT_INSTALL_TOKEN = "tiktok_media_permission_grant_install_token"

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
            .remove(KEY_LAST_SYNCED_CLOUD_STATUS)
            .putLong(KEY_LAST_BIND_REFRESH_MS, 0L)
            .remove(KEY_LAST_APPLIED_CONFIG_PUSH_REVISION)
            .remove(KEY_LOG_CONTEXT_USERNAME)
            .putBoolean(KEY_WORKER_SERVICE_SHOULD_RUN, false)
            .remove(KEY_WORKER_SERVICE_LAST_START_REQUEST_AT_MS)
            .remove(KEY_WORKER_SERVICE_LAST_START_REASON)
            .apply()
    }

    fun saveAuthToken(token: String, username: String) {
        prefs.edit()
            .putString(KEY_AUTH_TOKEN, token)
            .putString(KEY_AUTH_USERNAME, username)
            .apply()
    }

    /** 仅写入账号密码，不登录；连上网络后由用户在设置页点「登录云端」。 */
    fun saveAuthCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_AUTH_USERNAME, username.trim())
            .putString(KEY_AUTH_PASSWORD, password)
            .remove(KEY_AUTH_TOKEN)
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

    fun setWorkerServiceShouldRun(shouldRun: Boolean) {
        prefs.edit().putBoolean(KEY_WORKER_SERVICE_SHOULD_RUN, shouldRun).apply()
    }

    fun shouldWorkerServiceRun(): Boolean =
        prefs.getBoolean(KEY_WORKER_SERVICE_SHOULD_RUN, false)

    fun noteWorkerServiceStartRequested(reason: String) {
        prefs.edit()
            .putLong(KEY_WORKER_SERVICE_LAST_START_REQUEST_AT_MS, System.currentTimeMillis())
            .putString(KEY_WORKER_SERVICE_LAST_START_REASON, reason.trim().ifBlank { "unknown" })
            .apply()
    }

    fun getWorkerServiceLastStartRequestAtMs(): Long =
        prefs.getLong(KEY_WORKER_SERVICE_LAST_START_REQUEST_AT_MS, 0L)

    fun getWorkerServiceLastStartReason(): String? =
        prefs.getString(KEY_WORKER_SERVICE_LAST_START_REASON, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun isHardStopRequested(): Boolean = prefs.getBoolean(KEY_HARD_STOP_REQUESTED, false)

    /** 硬停后短时间内忽略云端仍返回的 auto_start/running（等待 opt_out 与 paused 同步）。 */
    fun isWithinHardStopGracePeriod(graceMs: Long = 15_000L): Boolean {
        if (!isHardStopRequested()) return false
        val at = prefs.getLong(KEY_HARD_STOP_AT_MS, 0L)
        if (at <= 0L) return false
        return System.currentTimeMillis() - at < graceMs
    }

    fun setTransientPausedForDestructiveOp(active: Boolean) {
        prefs.edit().putBoolean(KEY_TRANSIENT_PAUSED_FOR_DESTRUCTIVE_OP, active).apply()
    }

    fun isTransientPausedForDestructiveOp(): Boolean =
        prefs.getBoolean(KEY_TRANSIENT_PAUSED_FOR_DESTRUCTIVE_OP, false)

    fun setAutoSwitchExclusivePreempt(active: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SWITCH_EXCLUSIVE_PREEMPT, active).apply()
    }

    fun isAutoSwitchExclusivePreempt(): Boolean =
        prefs.getBoolean(KEY_AUTO_SWITCH_EXCLUSIVE_PREEMPT, false)

    fun isAutoSwitchLoginRecoveryActive(): Boolean =
        prefs.getBoolean(KEY_AUTO_SWITCH_LOGIN_RECOVERY_ACTIVE, false)

    fun activateAutoSwitchTikTokLoginHold(
        slotKey: String,
        reason: String,
        holdMs: Long = AUTO_SWITCH_TIKTOK_LOGIN_HOLD_DEFAULT_MS,
    ) {
        val until = System.currentTimeMillis() + holdMs.coerceAtLeast(60_000L)
        prefs.edit()
            .putLong(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_UNTIL_MS, until)
            .putString(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_SLOT_KEY, slotKey.trim())
            .putString(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_REASON, reason.trim())
            .putBoolean(KEY_AUTO_SWITCH_LOGIN_RECOVERY_ACTIVE, true)
            .apply()
    }

    private fun renewAutoSwitchTikTokLoginHoldDebounce() {
        if (!isAutoSwitchLoginRecoveryActive()) return
        val slotKey = prefs.getString(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_SLOT_KEY, null)?.trim().orEmpty()
        val reason = prefs.getString(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_REASON, null)?.trim().orEmpty()
            .ifBlank { "登录保障恢复中" }
        activateAutoSwitchTikTokLoginHold(slotKey, reason)
    }

    fun isAutoSwitchTikTokLoginHoldActive(slotKey: String? = null): Boolean {
        if (isAutoSwitchLoginRecoveryActive()) {
            val until = prefs.getLong(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_UNTIL_MS, 0L)
            if (until <= System.currentTimeMillis()) {
                renewAutoSwitchTikTokLoginHoldDebounce()
            }
            val heldSlot = prefs.getString(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_SLOT_KEY, null)?.trim().orEmpty()
            val probe = slotKey?.trim().orEmpty()
            if (probe.isNotEmpty() && heldSlot.isNotEmpty() && probe != heldSlot) return false
            return true
        }
        val until = prefs.getLong(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_UNTIL_MS, 0L)
        if (until <= System.currentTimeMillis()) {
            if (until > 0L) clearAutoSwitchTikTokLoginHold()
            return false
        }
        val heldSlot = prefs.getString(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_SLOT_KEY, null)?.trim().orEmpty()
        val probe = slotKey?.trim().orEmpty()
        if (probe.isNotEmpty() && heldSlot.isNotEmpty() && probe != heldSlot) return false
        return true
    }

    fun autoSwitchTikTokLoginHoldReason(): String? =
        prefs.getString(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_REASON, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun autoSwitchTikTokLoginHoldRemainingMinutes(): Int {
        val until = prefs.getLong(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_UNTIL_MS, 0L)
        if (until <= System.currentTimeMillis()) return 0
        return ((until - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(1)
    }

    fun clearAutoSwitchTikTokLoginHold() {
        prefs.edit()
            .remove(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_UNTIL_MS)
            .remove(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_SLOT_KEY)
            .remove(KEY_AUTO_SWITCH_TIKTOK_LOGIN_HOLD_REASON)
            .putBoolean(KEY_AUTO_SWITCH_LOGIN_RECOVERY_ACTIVE, false)
            .apply()
    }

    fun setMutualClickRequested(requested: Boolean) {
        if (AutoSwitchLocalPlanStore.isAutoSwitchMutualActive()) {
            AutoSwitchLocalPlanStore.mutateActiveMutual { it.copy(requested = requested) }
            return
        }
        prefs.edit().putBoolean(KEY_MUTUAL_CLICK_REQUESTED, requested).apply()
    }

    fun isMutualClickRequested(): Boolean {
        AutoSwitchLocalPlanStore.getActiveMutual()?.let { return it.requested }
        return prefs.getBoolean(KEY_MUTUAL_CLICK_REQUESTED, false)
    }

    fun setMutualClickPending(pending: Boolean) {
        if (AutoSwitchLocalPlanStore.isAutoSwitchMutualActive()) {
            AutoSwitchLocalPlanStore.mutateActiveMutual { it.copy(pending = pending) }
            return
        }
        prefs.edit().putBoolean(KEY_MUTUAL_CLICK_PENDING, pending).apply()
    }

    fun isMutualClickPending(): Boolean {
        AutoSwitchLocalPlanStore.getActiveMutual()?.let { return it.pending }
        return prefs.getBoolean(KEY_MUTUAL_CLICK_PENDING, false)
    }

    fun setMutualCommentOnlySession(active: Boolean) {
        prefs.edit().putBoolean(KEY_MUTUAL_COMMENT_ONLY_SESSION, active).apply()
    }

    fun isMutualCommentOnlySession(): Boolean = prefs.getBoolean(KEY_MUTUAL_COMMENT_ONLY_SESSION, false)

    fun setMutualFollowOnlySession(active: Boolean) {
        prefs.edit().putBoolean(KEY_MUTUAL_FOLLOW_ONLY_SESSION, active).apply()
    }

    fun isMutualFollowOnlySession(): Boolean = prefs.getBoolean(KEY_MUTUAL_FOLLOW_ONLY_SESSION, false)

    fun setGrowthContinueFromPublishHome(value: Boolean) {
        prefs.edit().putBoolean(KEY_GROWTH_CONTINUE_FROM_PUBLISH_HOME, value).apply()
    }

    fun peekGrowthContinueFromPublishHome(): Boolean =
        prefs.getBoolean(KEY_GROWTH_CONTINUE_FROM_PUBLISH_HOME, false)

    fun consumeGrowthContinueFromPublishHome(): Boolean {
        if (!peekGrowthContinueFromPublishHome()) return false
        setGrowthContinueFromPublishHome(false)
        return true
    }

    fun setPublishPostRequested(requested: Boolean) {
        if (AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()) {
            AutoSwitchLocalPlanStore.mutateActivePublish { it.copy(requested = requested) }
            return
        }
        prefs.edit().putBoolean(KEY_PUBLISH_POST_REQUESTED, requested).apply()
    }

    fun isPublishPostRequested(): Boolean {
        AutoSwitchLocalPlanStore.getActivePublish()?.let { return it.requested }
        return prefs.getBoolean(KEY_PUBLISH_POST_REQUESTED, false)
    }

    fun setPublishPostPending(pending: Boolean) {
        if (AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()) {
            AutoSwitchLocalPlanStore.mutateActivePublish { it.copy(pending = pending) }
            return
        }
        prefs.edit().putBoolean(KEY_PUBLISH_POST_PENDING, pending).apply()
    }

    fun isPublishPostPending(): Boolean {
        AutoSwitchLocalPlanStore.getActivePublish()?.let { return it.pending }
        return prefs.getBoolean(KEY_PUBLISH_POST_PENDING, false)
    }

    fun setPublishPostQueuedAfterMutual(queued: Boolean) {
        if (AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()) {
            AutoSwitchLocalPlanStore.setActivePublishQueuedAfterMutual(queued)
            return
        }
        prefs.edit().putBoolean(KEY_PUBLISH_POST_QUEUED_AFTER_MUTUAL, queued).apply()
    }

    fun isPublishPostQueuedAfterMutual(): Boolean {
        AutoSwitchLocalPlanStore.getActivePublish()?.let { return it.queued_after_mutual }
        return prefs.getBoolean(KEY_PUBLISH_POST_QUEUED_AFTER_MUTUAL, false)
    }

    fun setPublishPostPackageName(packageName: String?) {
        if (AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()) {
            val v = packageName?.trim().orEmpty()
            AutoSwitchLocalPlanStore.mutateActivePublish { it.copy(package_name = v) }
            return
        }
        val v = packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_PUBLISH_POST_PACKAGE).apply()
        } else {
            prefs.edit().putString(KEY_PUBLISH_POST_PACKAGE, v).apply()
        }
    }

    fun getPublishPostPackageName(): String? {
        AutoSwitchLocalPlanStore.getActivePublish()?.package_name?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return prefs.getString(KEY_PUBLISH_POST_PACKAGE, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getPublishPostTargetCount(): Int? {
        AutoSwitchLocalPlanStore.getActivePublish()?.let { ap ->
            return if (ap.target_count > 0) ap.target_count.coerceIn(1, 5) else null
        }
        if (!prefs.contains(KEY_PUBLISH_POST_TARGET_COUNT)) return null
        val v = prefs.getInt(KEY_PUBLISH_POST_TARGET_COUNT, 0)
        return if (v > 0) v.coerceIn(1, 5) else null
    }

    fun setPublishPostTargetCount(count: Int?) {
        if (AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()) {
            val v = count?.coerceIn(1, 5) ?: -1
            AutoSwitchLocalPlanStore.mutateActivePublish { it.copy(target_count = v) }
            return
        }
        val v = count?.coerceIn(1, 5) ?: 0
        if (v > 0) {
            prefs.edit().putInt(KEY_PUBLISH_POST_TARGET_COUNT, v).apply()
        } else {
            prefs.edit().remove(KEY_PUBLISH_POST_TARGET_COUNT).apply()
        }
    }

    private fun publishPostPermOcrPrefsKeyForAlias(alias: String): String =
        KEY_PUBLISH_POST_FIRST_DAILY_PERM_OCR_DAY_PREFIX + alias.trim().lowercase(Locale.US)

    /** 今日是否仍需在「该账号当天首条作品发布成功后」跑 Post Now / Only this time / Don't allow / Cancel 可选弹窗 OCR。 */
    fun shouldRunPublishPostFirstDailyPermissionOcr(alias: String): Boolean {
        val a = alias.trim().lowercase(Locale.US)
        if (a.isEmpty()) return true
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val stored = prefs.getString(publishPostPermOcrPrefsKeyForAlias(a), null)?.trim().orEmpty()
        return stored != today
    }

    /** 首条作品当日可选权限弹窗 OCR 已全部跑完时调用；发布成功路径请用 [setPublishPostResumeNextIndex] 的 markFirstDailyPermOcrDone 原子写入。 */
    fun markPublishPostFirstDailyPermissionOcrDone(alias: String) {
        val a = alias.trim().lowercase(Locale.US)
        if (a.isEmpty()) return
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        prefs.edit().putString(publishPostPermOcrPrefsKeyForAlias(a), today).apply()
    }

    private fun publishPostResumeNextIndexKey(accountKey: String): String =
        KEY_PUBLISH_POST_RESUME_NEXT_INDEX_PREFIX + accountKey.trim().lowercase(Locale.US)

    private fun publishPostResumeOpIdKey(accountKey: String): String =
        KEY_PUBLISH_POST_RESUME_OP_ID_PREFIX + accountKey.trim().lowercase(Locale.US)

    fun getPublishPostTaskOpId(): String? {
        AutoSwitchLocalPlanStore.activePublishOpId()?.let { return it }
        return prefs.getString(KEY_PUBLISH_POST_TASK_OP_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setPublishPostTaskOpId(opId: String?) {
        if (AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()) {
            if (opId.isNullOrBlank()) {
                AutoSwitchLocalPlanStore.clearActivePublish()
            } else {
                AutoSwitchLocalPlanStore.mutateActivePublish { it.copy(op_id = opId.trim()) }
            }
            return
        }
        val v = opId?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_PUBLISH_POST_TASK_OP_ID).apply()
        } else {
            prefs.edit().putString(KEY_PUBLISH_POST_TASK_OP_ID, v).apply()
        }
    }

    fun clearPublishPostTaskScope() {
        prefs.edit().remove(KEY_PUBLISH_POST_TASK_OP_ID).apply()
    }

    /** 下一条待发作品的 0-based 序号（已成功 [返回值] 条）；非当前云端指令时恒为 0。 */
    fun getPublishPostResumeNextIndex(accountKey: String): Int {
        val a = accountKey.trim().lowercase(Locale.US)
        if (a.isEmpty()) return 0
        AutoSwitchLocalPlanStore.getActivePublish()?.let { ap ->
            val apAccount = ap.account_username.trim().lowercase(Locale.US).removePrefix("@")
            val key = a.removePrefix("@")
            if (apAccount == key) return ap.resume_next_index.coerceAtLeast(0)
        }
        val taskOpId = getPublishPostTaskOpId() ?: return 0
        val resumeOpId = prefs.getString(publishPostResumeOpIdKey(a), null)?.trim().orEmpty()
        if (resumeOpId != taskOpId) return 0
        return prefs.getInt(publishPostResumeNextIndexKey(a), 0).coerceAtLeast(0)
    }

    fun setPublishPostResumeNextIndex(
        accountKey: String,
        nextIndex: Int,
        markFirstDailyPermOcrDone: Boolean = false,
    ) {
        val a = accountKey.trim().lowercase(Locale.US)
        if (a.isEmpty()) return
        AutoSwitchLocalPlanStore.getActivePublish()?.let { ap ->
            val apAccount = ap.account_username.trim().lowercase(Locale.US).removePrefix("@")
            val key = a.removePrefix("@")
            if (apAccount == key) {
                AutoSwitchLocalPlanStore.setActivePublishResumeNextIndex(nextIndex)
                if (markFirstDailyPermOcrDone) {
                    val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    prefs.edit().putString(publishPostPermOcrPrefsKeyForAlias(a), today).apply()
                }
                return
            }
        }
        val taskOpId = getPublishPostTaskOpId() ?: return
        val editor = prefs.edit()
            .putInt(publishPostResumeNextIndexKey(a), nextIndex.coerceAtLeast(0))
            .putString(publishPostResumeOpIdKey(a), taskOpId)
        if (markFirstDailyPermOcrDone) {
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            editor.putString(publishPostPermOcrPrefsKeyForAlias(a), today)
        }
        editor.apply()
    }

    fun clearPublishPostResumeProgress(accountKey: String) {
        val a = accountKey.trim().lowercase(Locale.US)
        if (a.isEmpty()) return
        prefs.edit()
            .remove(publishPostResumeNextIndexKey(a))
            .remove(publishPostResumeOpIdKey(a))
            .apply()
    }

    private fun clearAllPublishPostResumeProgress() {
        val keys = prefs.all.keys.filter { key ->
            key.startsWith(KEY_PUBLISH_POST_RESUME_NEXT_INDEX_PREFIX) ||
                key.startsWith(KEY_PUBLISH_POST_RESUME_OP_ID_PREFIX)
        }
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun publishPostEntryLayoutPrefsKey(): String? {
        val id = getDeviceId()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return KEY_PUBLISH_POST_ENTRY_LAYOUT_PREFIX + id
    }

    /** 本机已确认的发作品入口布局：`A` 或 `B`；无记录时返回 null。 */
    fun getPublishPostEntryLayoutForDevice(): String? {
        val key = publishPostEntryLayoutPrefsKey() ?: return null
        return when (prefs.getString(key, null)?.trim()?.uppercase(Locale.US)) {
            "A" -> "A"
            "B" -> "B"
            else -> null
        }
    }

    fun setPublishPostEntryLayoutForDevice(layout: String) {
        val key = publishPostEntryLayoutPrefsKey() ?: return
        val v = layout.trim().uppercase(Locale.US)
        if (v != "A" && v != "B") return
        prefs.edit().putString(key, v).apply()
    }

    fun clearPublishPostEntryLayoutForDevice() {
        val key = publishPostEntryLayoutPrefsKey() ?: return
        prefs.edit().remove(key).apply()
    }

    fun setAutoSwitchMutualSlotKey(slotKey: String?) {
        AutoSwitchLocalPlanStore.getActiveMutual()?.let { am ->
            if (slotKey.isNullOrBlank()) {
                AutoSwitchLocalPlanStore.clearActiveMutual()
            } else {
                AutoSwitchLocalPlanStore.mutateActiveMutual { it.copy(slot_key = slotKey.trim()) }
            }
            return
        }
        val v = slotKey?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_AUTO_SWITCH_MUTUAL_SLOT_KEY).apply()
        } else {
            prefs.edit().putString(KEY_AUTO_SWITCH_MUTUAL_SLOT_KEY, v).apply()
        }
    }

    fun getAutoSwitchMutualSlotKey(): String? {
        AutoSwitchLocalPlanStore.activeMutualSlotKey()?.let { return it }
        return prefs.getString(KEY_AUTO_SWITCH_MUTUAL_SLOT_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setPublishPostLastActualSuccessCount(count: Int) {
        prefs.edit()
            .putInt(KEY_PUBLISH_POST_LAST_ACTUAL_SUCCESS_COUNT, count.coerceAtLeast(0))
            .apply()
    }

    fun peekPublishPostLastActualSuccessCount(): Int =
        prefs.getInt(KEY_PUBLISH_POST_LAST_ACTUAL_SUCCESS_COUNT, 0).coerceAtLeast(0)

    fun getAndClearPublishPostLastActualSuccessCount(): Int {
        val v = prefs.getInt(KEY_PUBLISH_POST_LAST_ACTUAL_SUCCESS_COUNT, 0).coerceAtLeast(0)
        if (v > 0) {
            prefs.edit().remove(KEY_PUBLISH_POST_LAST_ACTUAL_SUCCESS_COUNT).apply()
        }
        return v
    }

    fun setPublishPostMaterialReady(ready: Boolean) {
        if (AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()) {
            AutoSwitchLocalPlanStore.setActivePublishMaterialReady(ready)
            return
        }
        prefs.edit().putBoolean(KEY_PUBLISH_POST_MATERIAL_READY, ready).apply()
    }

    fun isPublishPostMaterialReady(): Boolean {
        AutoSwitchLocalPlanStore.getActivePublish()?.let { return it.material_ready }
        return prefs.getBoolean(KEY_PUBLISH_POST_MATERIAL_READY, false)
    }

    fun setPublishPostSkipNextColdLaunchSafeguard(skip: Boolean) {
        if (skip) {
            prefs.edit().putBoolean(KEY_PUBLISH_POST_SKIP_NEXT_COLD_LAUNCH_SAFEGUARD, true).apply()
        } else {
            prefs.edit().remove(KEY_PUBLISH_POST_SKIP_NEXT_COLD_LAUNCH_SAFEGUARD).apply()
        }
    }

    /** 发作品保障重登后消费：返回 true 表示本次应跳过冷启强停与登录保障。 */
    fun consumePublishPostSkipNextColdLaunchSafeguard(): Boolean {
        val v = prefs.getBoolean(KEY_PUBLISH_POST_SKIP_NEXT_COLD_LAUNCH_SAFEGUARD, false)
        if (v) {
            prefs.edit().remove(KEY_PUBLISH_POST_SKIP_NEXT_COLD_LAUNCH_SAFEGUARD).apply()
        }
        return v
    }

    fun isAutoSwitchPublishTask(): Boolean = AutoSwitchLocalPlanStore.isAutoSwitchPublishActive()

    fun hasRecoverableAutoSwitchPublish(slotKey: String): Boolean =
        AutoSwitchLocalPlanStore.hasRecoverableActivePublish(slotKey)

    fun clearPublishPostState() {
        AutoSwitchLocalPlanStore.clearActivePublish()
        prefs.edit()
            .putBoolean(KEY_PUBLISH_POST_REQUESTED, false)
            .putBoolean(KEY_PUBLISH_POST_PENDING, false)
            .putBoolean(KEY_PUBLISH_POST_QUEUED_AFTER_MUTUAL, false)
            .putBoolean(KEY_PUBLISH_POST_MATERIAL_READY, false)
            .remove(KEY_PUBLISH_POST_SKIP_NEXT_COLD_LAUNCH_SAFEGUARD)
            .remove(KEY_PUBLISH_POST_PACKAGE)
            .remove(KEY_PUBLISH_POST_TASK_OP_ID)
            .remove(KEY_PUBLISH_POST_TARGET_COUNT)
            .apply()
    }

    fun clearMutualClickState() {
        AutoSwitchLocalPlanStore.clearActiveMutual()
        prefs.edit()
            .putBoolean(KEY_MUTUAL_CLICK_REQUESTED, false)
            .putBoolean(KEY_MUTUAL_CLICK_PENDING, false)
            .putBoolean(KEY_MUTUAL_COMMENT_ONLY_SESSION, false)
            .putBoolean(KEY_MUTUAL_FOLLOW_ONLY_SESSION, false)
            .remove(KEY_AUTO_SWITCH_MUTUAL_SLOT_KEY)
            .apply()
    }

    /**
     * 清发作品/互点插入任务相关 prefs（不含 plan 文件 slot 步骤态）。
     * 用于自治退出、会话轮换、切号跨账号清理。
     */
    fun clearAutoSwitchInsertionDirtyPrefs() {
        clearAutoSwitchTikTokLoginHold()
        clearPublishPostState()
        clearAllPublishPostResumeProgress()
        clearMutualClickState()
        setGrowthContinueFromPublishHome(false)
        setAutoSwitchExclusivePreempt(false)
        prefs.edit().remove(KEY_PUBLISH_POST_LAST_ACTUAL_SUCCESS_COUNT).apply()
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

    fun getLastSyncedCloudStatus(): String? =
        prefs.getString(KEY_LAST_SYNCED_CLOUD_STATUS, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun saveLastSyncedCloudStatus(status: String) {
        prefs.edit().putString(KEY_LAST_SYNCED_CLOUD_STATUS, status).apply()
    }

    fun clearLastSyncedCloudStatus() {
        prefs.edit().remove(KEY_LAST_SYNCED_CLOUD_STATUS).apply()
    }

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

    fun getAutoSwitchLastConfigFetchRevision(): Int =
        prefs.getInt(KEY_AUTO_SWITCH_LAST_CONFIG_FETCH_REVISION, -1)

    fun setAutoSwitchLastConfigFetchRevision(revision: Int) {
        prefs.edit().putInt(KEY_AUTO_SWITCH_LAST_CONFIG_FETCH_REVISION, revision).apply()
    }

    fun getAutoSwitchLastBundledUsername(): String? =
        prefs.getString(KEY_AUTO_SWITCH_LAST_BUNDLED_USERNAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setAutoSwitchLastBundledUsername(username: String?) {
        val v = username?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_AUTO_SWITCH_LAST_BUNDLED_USERNAME).apply()
        } else {
            prefs.edit().putString(KEY_AUTO_SWITCH_LAST_BUNDLED_USERNAME, v).apply()
        }
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

    private fun failureReportDedupKey(business: String, errorType: String): String {
        val b = business.trim().lowercase(Locale.US)
        val e = errorType.trim().lowercase(Locale.US)
        return "$b|$e"
    }

    @Synchronized
    fun isFailureReportUploaded(business: String, errorType: String): Boolean {
        val key = failureReportDedupKey(business, errorType)
        if (key == "|") return false
        return prefs.getStringSet(KEY_FAILURE_REPORT_UPLOADED, emptySet())?.contains(key) == true
    }

    @Synchronized
    fun markFailureReportUploaded(business: String, errorType: String) {
        val key = failureReportDedupKey(business, errorType)
        if (key == "|") return
        val set = prefs.getStringSet(KEY_FAILURE_REPORT_UPLOADED, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(key)
        prefs.edit().putStringSet(KEY_FAILURE_REPORT_UPLOADED, set).apply()
    }

    fun getTikTokMediaPermissionGrantInstallToken(): Long =
        prefs.getLong(KEY_TIKTOK_MEDIA_PERMISSION_GRANT_INSTALL_TOKEN, 0L)

    fun setTikTokMediaPermissionGrantInstallToken(token: Long) {
        prefs.edit().putLong(KEY_TIKTOK_MEDIA_PERMISSION_GRANT_INSTALL_TOKEN, token.coerceAtLeast(0L)).apply()
    }

    fun getAutoSwitchMode(): String =
        prefs.getString(KEY_AUTO_SWITCH_MODE, "off")?.trim()?.lowercase(Locale.US) ?: "off"

    fun isAutoSwitchAutonomousMode(): Boolean {
        val mode = getAutoSwitchMode()
        return mode == "on" || mode == "exiting"
    }

    fun setAutoSwitchMode(mode: String) {
        prefs.edit().putString(KEY_AUTO_SWITCH_MODE, mode.trim().lowercase(Locale.US)).apply()
    }

    fun getAutoSwitchSessionId(): String? =
        prefs.getString(KEY_AUTO_SWITCH_SESSION_ID, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getAutoSwitchBundleRevision(): Int =
        prefs.getInt(KEY_AUTO_SWITCH_BUNDLE_REVISION, 0)

    fun getLastModeEventId(): Int =
        prefs.getInt(KEY_LAST_MODE_EVENT_ID, 0).coerceAtLeast(0)

    fun saveAutoSwitchSessionContext(sessionId: String, bundleRevision: Int) {
        prefs.edit()
            .putString(KEY_AUTO_SWITCH_MODE, "on")
            .putString(KEY_AUTO_SWITCH_SESSION_ID, sessionId)
            .putInt(KEY_AUTO_SWITCH_BUNDLE_REVISION, bundleRevision.coerceAtLeast(0))
            .putString(KEY_AUTO_SWITCH_SESSION_STARTED_AT, nowLocalText())
            .apply()
    }

    fun getAutoSwitchScheduleDate(): String? =
        prefs.getString(KEY_AUTO_SWITCH_SCHEDULE_DATE, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setAutoSwitchScheduleDate(date: String?) {
        val v = date?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_AUTO_SWITCH_SCHEDULE_DATE).apply()
        } else {
            prefs.edit().putString(KEY_AUTO_SWITCH_SCHEDULE_DATE, v).apply()
        }
    }

    fun setAutoSwitchWeekRange(weekStart: String?, weekEnd: String?) {
        val start = weekStart?.trim().orEmpty()
        val end = weekEnd?.trim().orEmpty()
        val editor = prefs.edit()
        if (start.isEmpty()) editor.remove(KEY_AUTO_SWITCH_WEEK_START) else editor.putString(KEY_AUTO_SWITCH_WEEK_START, start)
        if (end.isEmpty()) editor.remove(KEY_AUTO_SWITCH_WEEK_END) else editor.putString(KEY_AUTO_SWITCH_WEEK_END, end)
        editor.apply()
    }

    fun getAutoSwitchWeekStart(): String? =
        prefs.getString(KEY_AUTO_SWITCH_WEEK_START, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getAutoSwitchWeekEnd(): String? =
        prefs.getString(KEY_AUTO_SWITCH_WEEK_END, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getAutoSwitchSessionStartedAt(): String? =
        prefs.getString(KEY_AUTO_SWITCH_SESSION_STARTED_AT, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getAutoSwitchLedgerJson(): String? =
        prefs.getString(KEY_AUTO_SWITCH_LEDGER_JSON, null)?.takeIf { it.isNotEmpty() }

    fun setAutoSwitchLedgerJson(json: String) {
        prefs.edit().putString(KEY_AUTO_SWITCH_LEDGER_JSON, json).commit()
    }

    fun clearAutoSwitchLedgerPersisted() {
        prefs.edit().remove(KEY_AUTO_SWITCH_LEDGER_JSON).apply()
    }

    fun hasAutoSwitchPendingSummaryUpload(): Boolean =
        prefs.getBoolean(KEY_AUTO_SWITCH_PENDING_SUMMARY, false)

    fun setAutoSwitchPendingSummaryUpload(pending: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SWITCH_PENDING_SUMMARY, pending).apply()
    }

    fun saveLastModeEventId(eventId: Int) {
        prefs.edit().putInt(KEY_LAST_MODE_EVENT_ID, eventId.coerceAtLeast(0)).apply()
    }

    fun isModeEventProcessed(eventId: Int): Boolean =
        eventId > 0 && eventId <= getLastModeEventId()

    fun getAutoSwitchLastSchedulerRevision(): Int =
        prefs.getInt(KEY_AUTO_SWITCH_LAST_SCHEDULER_REVISION, 0)

    fun setAutoSwitchLastSchedulerRevision(revision: Int) {
        prefs.edit().putInt(KEY_AUTO_SWITCH_LAST_SCHEDULER_REVISION, revision.coerceAtLeast(0)).apply()
    }

    fun getAutoSwitchEnterDoneEventId(): Int =
        prefs.getInt(KEY_AUTO_SWITCH_ENTER_DONE_EVENT_ID, 0).coerceAtLeast(0)

    fun markAutoSwitchEnterDone(eventId: Int) {
        prefs.edit().putInt(KEY_AUTO_SWITCH_ENTER_DONE_EVENT_ID, eventId.coerceAtLeast(0)).apply()
    }

    fun isAutoSwitchEnterDone(eventId: Int): Boolean =
        eventId > 0 && eventId <= getAutoSwitchEnterDoneEventId()

    fun getAutoSwitchExitDoneEventId(): Int =
        prefs.getInt(KEY_AUTO_SWITCH_EXIT_DONE_EVENT_ID, 0).coerceAtLeast(0)

    fun markAutoSwitchExitDone(eventId: Int) {
        prefs.edit().putInt(KEY_AUTO_SWITCH_EXIT_DONE_EVENT_ID, eventId.coerceAtLeast(0)).apply()
    }

    fun isAutoSwitchExitDone(eventId: Int): Boolean =
        eventId > 0 && eventId <= getAutoSwitchExitDoneEventId()

    fun getAutoSwitchDailyReconcileDate(): String? =
        prefs.getString(KEY_AUTO_SWITCH_DAILY_RECONCILE_DATE, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setAutoSwitchDailyReconcileDate(date: String?) {
        val v = date?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_AUTO_SWITCH_DAILY_RECONCILE_DATE).apply()
        } else {
            prefs.edit().putString(KEY_AUTO_SWITCH_DAILY_RECONCILE_DATE, v).apply()
        }
    }

    fun getAutoSwitchDailyReconcileTargetDate(): String? =
        prefs.getString(KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_DATE, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getAutoSwitchDailyReconcileTargetMs(): Long =
        prefs.getLong(KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_MS, 0L)

    fun setAutoSwitchDailyReconcileSchedule(targetDate: String, targetMs: Long) {
        prefs.edit()
            .putString(KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_DATE, targetDate.trim())
            .putLong(KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_MS, targetMs.coerceAtLeast(0L))
            .apply()
    }

    fun clearAutoSwitchDailyReconcileSchedule() {
        prefs.edit()
            .remove(KEY_AUTO_SWITCH_DAILY_RECONCILE_DATE)
            .remove(KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_DATE)
            .remove(KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_MS)
            .apply()
    }

    private fun nowBeijingText(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        return fmt.format(java.util.Date())
    }

    private fun nowLocalText(): String = nowBeijingText()

    fun clearAutoSwitchSessionContext() {
        clearAutoSwitchInsertionDirtyPrefs()
        prefs.edit()
            .putString(KEY_AUTO_SWITCH_MODE, "off")
            .remove(KEY_AUTO_SWITCH_SESSION_ID)
            .remove(KEY_AUTO_SWITCH_BUNDLE_REVISION)
            .remove(KEY_AUTO_SWITCH_LAST_SLOT_KEY)
            .remove(KEY_AUTO_SWITCH_SCHEDULE_DATE)
            .remove(KEY_AUTO_SWITCH_WEEK_START)
            .remove(KEY_AUTO_SWITCH_WEEK_END)
            .remove(KEY_AUTO_SWITCH_SESSION_STARTED_AT)
            .remove(KEY_AUTO_SWITCH_PENDING_SUMMARY)
            .remove(KEY_AUTO_SWITCH_LAST_SCHEDULER_REVISION)
            .remove(KEY_AUTO_SWITCH_ENTER_DONE_EVENT_ID)
            .remove(KEY_AUTO_SWITCH_EXIT_DONE_EVENT_ID)
            .remove(KEY_AUTO_SWITCH_DAILY_RECONCILE_DATE)
            .remove(KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_DATE)
            .remove(KEY_AUTO_SWITCH_DAILY_RECONCILE_TARGET_MS)
            .remove(KEY_AUTO_SWITCH_LAST_CONFIG_FETCH_REVISION)
            .remove(KEY_AUTO_SWITCH_LAST_BUNDLED_USERNAME)
            .apply()
    }

    fun getAutoSwitchLastSlotKey(): String? =
        prefs.getString(KEY_AUTO_SWITCH_LAST_SLOT_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setAutoSwitchLastSlotKey(slotKey: String?) {
        val v = slotKey?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_AUTO_SWITCH_LAST_SLOT_KEY).apply()
        } else {
            prefs.edit().putString(KEY_AUTO_SWITCH_LAST_SLOT_KEY, v).apply()
        }
    }

    fun getProfileEditStepStateJson(accountKey: String): String? =
        prefs.getString(KEY_PROFILE_EDIT_STEP_STATE_PREFIX + accountKey.trim().lowercase(Locale.US), null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun setProfileEditStepStateJson(accountKey: String, json: String) {
        val key = accountKey.trim().lowercase(Locale.US)
        if (key.isEmpty()) return
        prefs.edit().putString(KEY_PROFILE_EDIT_STEP_STATE_PREFIX + key, json).apply()
    }

    fun clearProfileEditStepStateJson(accountKey: String) {
        val key = accountKey.trim().lowercase(Locale.US)
        if (key.isEmpty()) return
        prefs.edit().remove(KEY_PROFILE_EDIT_STEP_STATE_PREFIX + key).apply()
    }

    fun saveLastProcessHeartbeatLine(line: String) {
        prefs.edit().putString(KEY_LAST_PROCESS_HEARTBEAT_LINE, line.trim()).apply()
    }

    fun getLastProcessHeartbeatLine(): String? =
        prefs.getString(KEY_LAST_PROCESS_HEARTBEAT_LINE, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun saveLastProcessCrashLine(line: String) {
        prefs.edit().putString(KEY_LAST_PROCESS_CRASH_LINE, line.trim()).apply()
    }

    fun getLastProcessCrashLine(): String? =
        prefs.getString(KEY_LAST_PROCESS_CRASH_LINE, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun isAutoGrowthAfterTasksEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_GROWTH_AFTER_TASKS, true)

    fun setAutoGrowthAfterTasksEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_GROWTH_AFTER_TASKS, enabled).apply()
    }

    /** simple 本机自动切号自治开关；未开时不得按遗留 prefs 恢复云端自治。 */
    fun isManualLocalAutoSwitchEnabled(): Boolean =
        prefs.getBoolean(KEY_MANUAL_LOCAL_AUTO_SWITCH, false)

    fun setManualLocalAutoSwitchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MANUAL_LOCAL_AUTO_SWITCH, enabled).apply()
    }

    /** true=本机手动模式；false=云端 client 模式。 */
    fun isManualControlMode(): Boolean =
        prefs.getBoolean(KEY_MANUAL_CONTROL_MODE, true)

    fun setManualControlMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MANUAL_CONTROL_MODE, enabled).apply()
    }

    fun getManualAutoSwitchSlotMinutes(): Int =
        prefs.getInt(
            KEY_MANUAL_AUTO_SWITCH_SLOT_MINUTES,
            ManualAutoSwitchPlanBuilder.DEFAULT_SLOT_MINUTES,
        ).let { ManualAutoSwitchPlanBuilder.normalizeSlotMinutes(it) }

    fun setManualAutoSwitchSlotMinutes(minutes: Int) {
        prefs.edit()
            .putInt(
                KEY_MANUAL_AUTO_SWITCH_SLOT_MINUTES,
                ManualAutoSwitchPlanBuilder.normalizeSlotMinutes(minutes),
            )
            .apply()
    }

    fun getManualForYouSwipeSec(): Int =
        prefs.getInt(KEY_MANUAL_FOR_YOU_SWIPE_SEC, DEFAULT_MANUAL_FOR_YOU_SWIPE_SEC)
            .coerceIn(MIN_MANUAL_FOR_YOU_SWIPE_SEC, MAX_MANUAL_FOR_YOU_SWIPE_SEC)

    fun setManualForYouSwipeSec(sec: Int) {
        prefs.edit()
            .putInt(
                KEY_MANUAL_FOR_YOU_SWIPE_SEC,
                sec.coerceIn(MIN_MANUAL_FOR_YOU_SWIPE_SEC, MAX_MANUAL_FOR_YOU_SWIPE_SEC),
            )
            .apply()
    }

    fun getManualActivateDurationMin(): Int =
        prefs.getInt(KEY_MANUAL_ACTIVATE_DURATION_MIN, DEFAULT_MANUAL_ACTIVATE_DURATION_MIN)
            .coerceIn(MIN_MANUAL_ACTIVATE_DURATION_MIN, MAX_MANUAL_ACTIVATE_DURATION_MIN)

    fun setManualActivateDurationMin(minutes: Int) {
        prefs.edit()
            .putInt(
                KEY_MANUAL_ACTIVATE_DURATION_MIN,
                minutes.coerceIn(MIN_MANUAL_ACTIVATE_DURATION_MIN, MAX_MANUAL_ACTIVATE_DURATION_MIN),
            )
            .apply()
    }

    fun getManualActivateDurationHours(): Double =
        getManualActivateDurationMin() / 60.0

    fun setManualActivateDurationHours(hours: Double) {
        val min = kotlin.math.round(
            hours.coerceIn(MIN_MANUAL_ACTIVATE_DURATION_HOURS, MAX_MANUAL_ACTIVATE_DURATION_HOURS) * 60.0,
        ).toInt()
        setManualActivateDurationMin(min)
    }

    /**
     * 解析界面输入。正常按小时；若数字明显大于 24（例如旧习惯填 480 分钟），按分钟换算。
     */
    fun parseManualActivateDurationHours(raw: String?): Double {
        val parsed = raw?.trim()?.replace(',', '.')?.toDoubleOrNull()
            ?: return getManualActivateDurationHours()
        val hours = if (parsed > MAX_MANUAL_ACTIVATE_DURATION_HOURS) {
            parsed / 60.0
        } else {
            parsed
        }
        return hours.coerceIn(MIN_MANUAL_ACTIVATE_DURATION_HOURS, MAX_MANUAL_ACTIVATE_DURATION_HOURS)
    }

    fun formatManualActivateDurationHours(hours: Double): String {
        val normalized = hours.coerceIn(MIN_MANUAL_ACTIVATE_DURATION_HOURS, MAX_MANUAL_ACTIVATE_DURATION_HOURS)
        val rounded = kotlin.math.round(normalized * 100.0) / 100.0
        return if (kotlin.math.abs(rounded - rounded.toLong()) < 0.001) {
            rounded.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", rounded)
                .trimEnd('0')
                .trimEnd('.')
        }
    }

    fun getManualCutSwitchScheme(): String {
        val raw = prefs.getString(KEY_MANUAL_CUT_SWITCH_SCHEME, CUT_SWITCH_SCHEME_BACKUP)
            ?.trim()?.lowercase(Locale.US).orEmpty()
        return when (raw) {
            CUT_SWITCH_SCHEME_LOGIN, "login_switch", "exclusive_login" -> CUT_SWITCH_SCHEME_LOGIN
            else -> CUT_SWITCH_SCHEME_BACKUP
        }
    }

    fun setManualCutSwitchScheme(scheme: String) {
        val normalized = when (scheme.trim().lowercase(Locale.US)) {
            CUT_SWITCH_SCHEME_LOGIN, "login_switch", "exclusive_login" -> CUT_SWITCH_SCHEME_LOGIN
            else -> CUT_SWITCH_SCHEME_BACKUP
        }
        prefs.edit().putString(KEY_MANUAL_CUT_SWITCH_SCHEME, normalized).apply()
    }

    fun prefersManualLoginCutSwitch(): Boolean =
        getManualCutSwitchScheme() == CUT_SWITCH_SCHEME_LOGIN

    fun getManualBatchPublishAfterMin(): Int =
        prefs.getInt(KEY_MANUAL_BATCH_PUBLISH_AFTER_MIN, DEFAULT_MANUAL_BATCH_PUBLISH_AFTER_MIN)
            .coerceIn(MIN_MANUAL_BATCH_PUBLISH_AFTER_MIN, MAX_MANUAL_BATCH_PUBLISH_AFTER_MIN)

    fun setManualBatchPublishAfterMin(minutes: Int) {
        prefs.edit()
            .putInt(
                KEY_MANUAL_BATCH_PUBLISH_AFTER_MIN,
                minutes.coerceIn(MIN_MANUAL_BATCH_PUBLISH_AFTER_MIN, MAX_MANUAL_BATCH_PUBLISH_AFTER_MIN),
            )
            .apply()
    }

    /** 各账号累计活跃分钟（不含 @）。 */
    fun getActivateTotalMinutesMap(): Map<String, Int> {
        val raw = prefs.getString(KEY_MANUAL_ACTIVATE_TOTAL_MINUTES, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next().trim().removePrefix("@")
                    if (k.isEmpty()) continue
                    val v = obj.optInt(k, 0).coerceAtLeast(0)
                    if (v > 0) put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveActivateTotalMinutesMap(map: Map<String, Int>) {
        val normalized = map.mapKeys { it.key.trim().removePrefix("@") }
            .filterKeys { it.isNotEmpty() }
            .mapValues { it.value.coerceAtLeast(0) }
            .filterValues { it > 0 }
        if (normalized.isEmpty()) {
            prefs.edit().remove(KEY_MANUAL_ACTIVATE_TOTAL_MINUTES).apply()
            return
        }
        val obj = org.json.JSONObject()
        normalized.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_MANUAL_ACTIVATE_TOTAL_MINUTES, obj.toString()).apply()
    }

    fun getActivateTotalMinutes(username: String): Int {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return 0
        return getActivateTotalMinutesMap().entries
            .firstOrNull { it.key.equals(u, ignoreCase = true) }
            ?.value ?: 0
    }

    fun addActivateTotalMinutes(username: String, addMinutes: Int) {
        val u = username.trim().removePrefix("@")
        val add = addMinutes.coerceAtLeast(0)
        if (u.isEmpty() || add <= 0) return
        val cur = getActivateTotalMinutesMap().toMutableMap()
        val key = cur.keys.firstOrNull { it.equals(u, ignoreCase = true) } ?: u
        cur[key] = (cur[key] ?: 0) + add
        saveActivateTotalMinutesMap(cur)
    }

    fun setActivateTotalMinutes(username: String, minutes: Int) {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return
        val cur = getActivateTotalMinutesMap().toMutableMap()
        val key = cur.keys.firstOrNull { it.equals(u, ignoreCase = true) } ?: u
        if (minutes <= 0) {
            cur.remove(key)
        } else {
            cur[key] = minutes
        }
        saveActivateTotalMinutesMap(cur)
    }

    fun clearActivateTotalMinutes() {
        prefs.edit().remove(KEY_MANUAL_ACTIVATE_TOTAL_MINUTES).apply()
    }

    private fun readIntMinutesMap(key: String): Map<String, Int> {
        val raw = prefs.getString(key, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next().trim().removePrefix("@")
                    if (k.isEmpty()) continue
                    val v = obj.optInt(k, 0).coerceAtLeast(0)
                    if (v > 0) put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveIntMinutesMap(key: String, map: Map<String, Int>) {
        val normalized = map.mapKeys { it.key.trim().removePrefix("@") }
            .filterKeys { it.isNotEmpty() }
            .mapValues { it.value.coerceAtLeast(0) }
            .filterValues { it > 0 }
        if (normalized.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val obj = org.json.JSONObject()
        normalized.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun addIntMinutes(key: String, username: String, addMinutes: Int) {
        val u = username.trim().removePrefix("@")
        val add = addMinutes.coerceAtLeast(0)
        if (u.isEmpty() || add <= 0) return
        val cur = readIntMinutesMap(key).toMutableMap()
        val mapKey = cur.keys.firstOrNull { it.equals(u, ignoreCase = true) } ?: u
        cur[mapKey] = (cur[mapKey] ?: 0) + add
        saveIntMinutesMap(key, cur)
    }

    private fun getIntMinutes(key: String, username: String): Int {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return 0
        return readIntMinutesMap(key).entries
            .firstOrNull { it.key.equals(u, ignoreCase = true) }
            ?.value ?: 0
    }

    fun getActivateGrowthMinutes(username: String): Int =
        getIntMinutes(KEY_MANUAL_ACTIVATE_GROWTH_MINUTES, username)

    fun getActivateForYouMinutes(username: String): Int =
        getIntMinutes(KEY_MANUAL_ACTIVATE_FOR_YOU_MINUTES, username)

    fun addActivateGrowthMinutes(username: String, addMinutes: Int) {
        addIntMinutes(KEY_MANUAL_ACTIVATE_GROWTH_MINUTES, username, addMinutes)
    }

    fun addActivateForYouMinutes(username: String, addMinutes: Int) {
        addIntMinutes(KEY_MANUAL_ACTIVATE_FOR_YOU_MINUTES, username, addMinutes)
    }

    private fun readEnqueueAtMap(): Map<String, Long> {
        val raw = prefs.getString(KEY_MANUAL_PENDING_ACTIVATE_ENQUEUE_AT, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next().trim().removePrefix("@")
                    if (k.isEmpty()) continue
                    val v = obj.optLong(k, 0L)
                    if (v > 0L) put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveEnqueueAtMap(map: Map<String, Long>) {
        val normalized = map.mapKeys { it.key.trim().removePrefix("@") }
            .filterKeys { it.isNotEmpty() }
            .mapValues { it.value.coerceAtLeast(0L) }
            .filterValues { it > 0L }
        if (normalized.isEmpty()) {
            prefs.edit().remove(KEY_MANUAL_PENDING_ACTIVATE_ENQUEUE_AT).apply()
            return
        }
        val obj = org.json.JSONObject()
        normalized.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_MANUAL_PENDING_ACTIVATE_ENQUEUE_AT, obj.toString()).apply()
    }

    fun getPendingActivateEnqueueAt(username: String): Long? {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return null
        val v = readEnqueueAtMap().entries
            .firstOrNull { it.key.equals(u, ignoreCase = true) }
            ?.value ?: return null
        return if (v > 0L) v else null
    }

    fun setPendingActivateEnqueueAt(username: String, atMs: Long = System.currentTimeMillis()) {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return
        val cur = readEnqueueAtMap().toMutableMap()
        val mapKey = cur.keys.firstOrNull { it.equals(u, ignoreCase = true) } ?: u
        cur[mapKey] = atMs.coerceAtLeast(0L)
        saveEnqueueAtMap(cur)
    }

    /** 待活跃队列（FIFO，username 不含 @）。 */
    fun getPendingActivateUsernames(): List<String> {
        val raw = prefs.getString(KEY_MANUAL_PENDING_ACTIVATE_QUEUE, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i, "").trim().removePrefix("@")
                    if (u.isNotEmpty()) add(u)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 是否在待活跃队列中。 */
    fun isInPendingActivateQueue(username: String): Boolean =
        pendingActivateQueueIndex(username) != null

    /**
     * 待活跃队列中的位置（1-based）；不在队列则 null。
     */
    fun pendingActivateQueueIndex(username: String): Int? {
        val key = username.trim().removePrefix("@")
        if (key.isEmpty()) return null
        val idx = getPendingActivateUsernames().indexOfFirst { it.equals(key, ignoreCase = true) }
        return if (idx >= 0) idx + 1 else null
    }

    /** 1-based 队列位；不在队列则 null。 */
    fun pendingActivateQueuePosition(username: String): Int? {
        val key = username.trim().removePrefix("@")
        if (key.isEmpty()) return null
        val idx = getPendingActivateUsernames().indexOfFirst { it.equals(key, ignoreCase = true) }
        return if (idx >= 0) idx + 1 else null
    }

    fun getPendingActivateHeartGrowthMax(): Int =
        prefs.getInt(KEY_PENDING_ACTIVATE_HEART_GROWTH_MAX, DEFAULT_PENDING_ACTIVATE_HEART_GROWTH_MAX)
            .coerceIn(MIN_PENDING_ACTIVATE_HEART_GROWTH_MAX, MAX_PENDING_ACTIVATE_HEART_GROWTH_MAX)

    fun setPendingActivateHeartGrowthMax(value: Int) {
        prefs.edit()
            .putInt(
                KEY_PENDING_ACTIVATE_HEART_GROWTH_MAX,
                value.coerceIn(MIN_PENDING_ACTIVATE_HEART_GROWTH_MAX, MAX_PENDING_ACTIVATE_HEART_GROWTH_MAX),
            )
            .apply()
    }

    fun getPendingActivateRecommendCacheJson(): String? =
        prefs.getString(KEY_PENDING_ACTIVATE_RECOMMEND_CACHE_JSON, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setPendingActivateRecommendCacheJson(json: String?) {
        val text = json?.trim().orEmpty()
        prefs.edit().apply {
            if (text.isEmpty()) {
                remove(KEY_PENDING_ACTIVATE_RECOMMEND_CACHE_JSON)
            } else {
                putString(KEY_PENDING_ACTIVATE_RECOMMEND_CACHE_JSON, text)
            }
        }.apply()
    }

    private fun savePendingActivateUsernames(list: List<String>) {
        val normalized = list.map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.US) }
        if (normalized.isEmpty()) {
            prefs.edit().remove(KEY_MANUAL_PENDING_ACTIVATE_QUEUE).apply()
            return
        }
        val arr = org.json.JSONArray()
        normalized.forEach { arr.put(it) }
        prefs.edit().putString(KEY_MANUAL_PENDING_ACTIVATE_QUEUE, arr.toString()).apply()
    }

    /** @return true 表示新加入；false 表示已在队列中或 username 无效。 */
    fun enqueuePendingActivateUsername(username: String): Boolean {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return false
        val cur = getPendingActivateUsernames().toMutableList()
        if (cur.any { it.equals(u, ignoreCase = true) }) return false
        cur.add(u)
        savePendingActivateUsernames(cur)
        setPendingActivateEnqueueAt(u)
        return true
    }

    fun popPendingActivateUsername(): String? {
        val cur = getPendingActivateUsernames().toMutableList()
        if (cur.isEmpty()) return null
        val first = cur.removeAt(0)
        savePendingActivateUsernames(cur)
        return first
    }

    /** 队首账号（不移除），供活跃下个 / 自动活跃轮询。 */
    fun peekPendingActivateUsername(): String? = getPendingActivateUsernames().firstOrNull()

    /** 队首活跃完成后移到队尾，队列成员不变、顺序轮转。 */
    fun rotatePendingActivateQueueFrontToBack() {
        val cur = getPendingActivateUsernames().toMutableList()
        if (cur.size <= 1) return
        val first = cur.removeAt(0)
        cur.add(first)
        savePendingActivateUsernames(cur)
    }

    fun getActivateSessionRemainingSec(username: String, plannedMin: Int? = null): Int? {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return null
        val map = getActivateSessionRemainingMap()
        val remaining = map.entries.firstOrNull { it.key.equals(u, ignoreCase = true) }?.value?.takeIf { it > 0 }
            ?: return null
        if (plannedMin == null) return remaining
        val storedMin = getActivateSessionPlannedMinMap().entries
            .firstOrNull { it.key.equals(u, ignoreCase = true) }?.value
        if (storedMin != null && storedMin != plannedMin) {
            clearActivateSession(u)
            return null
        }
        return remaining
    }

    fun getActivateSessionPlannedMin(username: String): Int? {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return null
        return getActivateSessionPlannedMinMap().entries
            .firstOrNull { it.key.equals(u, ignoreCase = true) }?.value
    }

    fun setActivateSessionRemaining(username: String, remainingSec: Int, plannedMin: Int) {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return
        val sec = remainingSec.coerceAtLeast(0)
        val min = plannedMin.coerceIn(MIN_MANUAL_ACTIVATE_DURATION_MIN, MAX_MANUAL_ACTIVATE_DURATION_MIN)
        val curSec = getActivateSessionRemainingMap().toMutableMap()
        val curMin = getActivateSessionPlannedMinMap().toMutableMap()
        val key = curSec.keys.firstOrNull { it.equals(u, ignoreCase = true) } ?: u
        if (sec <= 0) {
            curSec.remove(key)
            curMin.remove(key)
        } else {
            curSec[key] = sec
            curMin[key] = min
        }
        saveActivateSessionRemainingMap(curSec)
        saveActivateSessionPlannedMinMap(curMin)
    }

    @Deprecated("Use setActivateSessionRemaining(username, sec, plannedMin)")
    fun setActivateSessionRemainingLegacy(username: String, remainingSec: Int) {
        setActivateSessionRemaining(username, remainingSec, getManualActivateDurationMin())
    }

    fun clearActivateSession(username: String? = null) {
        if (username.isNullOrBlank()) {
            prefs.edit()
                .remove(KEY_MANUAL_ACTIVATE_SESSION_REMAINING)
                .remove(KEY_MANUAL_ACTIVATE_SESSION_PLANNED_MIN)
                .apply()
            return
        }
        setActivateSessionRemaining(username, 0, getManualActivateDurationMin())
    }

    private fun getActivateSessionPlannedMinMap(): Map<String, Int> {
        val raw = prefs.getString(KEY_MANUAL_ACTIVATE_SESSION_PLANNED_MIN, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next().trim().removePrefix("@")
                    if (k.isEmpty()) continue
                    val v = obj.optInt(k, 0).coerceAtLeast(0)
                    if (v > 0) put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveActivateSessionPlannedMinMap(map: Map<String, Int>) {
        val normalized = map.mapKeys { it.key.trim().removePrefix("@") }
            .filterKeys { it.isNotEmpty() }
            .mapValues { it.value.coerceAtLeast(0) }
            .filterValues { it > 0 }
        if (normalized.isEmpty()) {
            prefs.edit().remove(KEY_MANUAL_ACTIVATE_SESSION_PLANNED_MIN).apply()
            return
        }
        val obj = org.json.JSONObject()
        normalized.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_MANUAL_ACTIVATE_SESSION_PLANNED_MIN, obj.toString()).apply()
    }

    private fun getActivateSessionRemainingMap(): Map<String, Int> {
        val raw = prefs.getString(KEY_MANUAL_ACTIVATE_SESSION_REMAINING, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next().trim().removePrefix("@")
                    if (k.isEmpty()) continue
                    val v = obj.optInt(k, 0).coerceAtLeast(0)
                    if (v > 0) put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveActivateSessionRemainingMap(map: Map<String, Int>) {
        val normalized = map.mapKeys { it.key.trim().removePrefix("@") }
            .filterKeys { it.isNotEmpty() }
            .mapValues { it.value.coerceAtLeast(0) }
            .filterValues { it > 0 }
        if (normalized.isEmpty()) {
            prefs.edit().remove(KEY_MANUAL_ACTIVATE_SESSION_REMAINING).apply()
            return
        }
        val obj = org.json.JSONObject()
        normalized.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_MANUAL_ACTIVATE_SESSION_REMAINING, obj.toString()).apply()
    }

    /** 切号/活跃失败时放回队首，避免丢号。 */
    fun requeuePendingActivateUsernameFront(username: String) {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return
        val cur = getPendingActivateUsernames().toMutableList()
            .filterNot { it.equals(u, ignoreCase = true) }
            .toMutableList()
        cur.add(0, u)
        savePendingActivateUsernames(cur)
    }

    fun clearPendingActivateUsernames() {
        prefs.edit().remove(KEY_MANUAL_PENDING_ACTIVATE_QUEUE).apply()
    }

    /** 从待活跃队列移除指定账号（不影响其余顺序）。 */
    fun removePendingActivateUsername(username: String): Boolean {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return false
        val cur = getPendingActivateUsernames().toMutableList()
        val idx = cur.indexOfFirst { it.equals(u, ignoreCase = true) }
        if (idx < 0) return false
        cur.removeAt(idx)
        savePendingActivateUsernames(cur)
        return true
    }

    /** 整体替换待活跃队列（用于按活跃时长编辑）。新入队账号会记录加入时间。 */
    fun setPendingActivateUsernames(usernames: List<String>) {
        val oldSet = getPendingActivateUsernames()
            .map { it.lowercase(Locale.US) }
            .toSet()
        val normalized = usernames.map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.US) }
        savePendingActivateUsernames(normalized)
        val now = System.currentTimeMillis()
        normalized.forEach { u ->
            if (!oldSet.contains(u.lowercase(Locale.US))) {
                setPendingActivateEnqueueAt(u, now)
            }
        }
    }

    fun getManualPublishCount(): Int =
        prefs.getInt(KEY_MANUAL_PUBLISH_COUNT, DEFAULT_MANUAL_PUBLISH_COUNT)
            .coerceIn(MIN_MANUAL_PUBLISH_COUNT, MAX_MANUAL_PUBLISH_COUNT)

    fun setManualPublishCount(count: Int) {
        prefs.edit()
            .putInt(
                KEY_MANUAL_PUBLISH_COUNT,
                count.coerceIn(MIN_MANUAL_PUBLISH_COUNT, MAX_MANUAL_PUBLISH_COUNT),
            )
            .apply()
    }

    fun getIssuedTkPendingEmail(): String? =
        prefs.getString(KEY_ISSUED_TK_PENDING_EMAIL, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getIssuedTkPendingEmailPassword(): String? =
        prefs.getString(KEY_ISSUED_TK_PENDING_EMAIL_PASSWORD, null)?.takeIf { it.isNotEmpty() }

    fun setIssuedTkPendingEmail(email: String?, password: String? = null) {
        val e = email?.trim()?.takeIf { it.isNotEmpty() }
        val p = password?.takeIf { it.isNotEmpty() }
        val editor = prefs.edit()
        if (e == null) {
            editor.remove(KEY_ISSUED_TK_PENDING_EMAIL).remove(KEY_ISSUED_TK_PENDING_EMAIL_PASSWORD)
        } else {
            editor.putString(KEY_ISSUED_TK_PENDING_EMAIL, e)
            if (p == null) {
                editor.remove(KEY_ISSUED_TK_PENDING_EMAIL_PASSWORD)
            } else {
                editor.putString(KEY_ISSUED_TK_PENDING_EMAIL_PASSWORD, p)
            }
        }
        editor.apply()
    }

    fun getManualSelectedUsername(): String? =
        prefs.getString(KEY_MANUAL_SELECTED_USERNAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setManualSelectedUsername(username: String?) {
        val v = username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_MANUAL_SELECTED_USERNAME).apply()
        } else {
            prefs.edit().putString(KEY_MANUAL_SELECTED_USERNAME, v).apply()
        }
    }

    /** 本机当前已切上/正在使用的账号；切号时作为备份源。 */
    fun getManualActiveUsername(): String? =
        prefs.getString(KEY_MANUAL_ACTIVE_USERNAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setManualActiveUsername(username: String?) {
        val v = username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_MANUAL_ACTIVE_USERNAME).apply()
        } else {
            prefs.edit().putString(KEY_MANUAL_ACTIVE_USERNAME, v).apply()
        }
    }

    fun getManualAccountsCacheJson(): String? =
        prefs.getString(KEY_MANUAL_ACCOUNTS_CACHE_JSON, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setManualAccountsCacheJson(json: String?) {
        val v = json?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) {
            prefs.edit().remove(KEY_MANUAL_ACCOUNTS_CACHE_JSON).apply()
        } else {
            prefs.edit().putString(KEY_MANUAL_ACCOUNTS_CACHE_JSON, v).apply()
        }
    }

    fun isManualFirstLoginDone(username: String): Boolean {
        val key = username.trim().removePrefix("@").lowercase(Locale.US)
        if (key.isEmpty()) return false
        return prefs.getBoolean(KEY_MANUAL_FIRST_LOGIN_DONE_PREFIX + key, false)
    }

    fun setManualFirstLoginDone(username: String, done: Boolean) {
        val key = username.trim().removePrefix("@").lowercase(Locale.US)
        if (key.isEmpty()) return
        prefs.edit().putBoolean(KEY_MANUAL_FIRST_LOGIN_DONE_PREFIX + key, done).apply()
    }
}
