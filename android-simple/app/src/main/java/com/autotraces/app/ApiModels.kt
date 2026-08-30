package com.autotraces.app

data class BindDeviceRequest(
    val alias: String,
    val device_fingerprint: String,
)

data class BindDeviceResponse(
    val code: Int,
    val msg: String,
    val device_id: String? = null,
)

/** GET /api/device_binding — 按指纹恢复本机绑定 */
data class DeviceBindingResponse(
    val code: Int,
    val msg: String,
    val device_id: String? = null,
    val alias: String? = null,
)

data class AuthLoginRequest(
    val username: String,
    val password: String,
)

data class AuthLoginResponse(
    val code: Int,
    val msg: String,
    val token: String? = null,
    val username: String? = null,
    val role: String? = null,
)

data class DeviceAuthQuery(
    val deviceId: String,
    val alias: String,
)

data class SyncStatusRequest(
    val device_id: String,
    val alias: String,
    val status: String,
)

data class BaseResponse(
    val code: Int,
    val msg: String,
    val auto_start_opt_out: Boolean? = null,
    val auto_start: Boolean? = null,
    val cut_switch_auto_start_resumed: Boolean? = null,
    val cut_switch_opt_out_cleared: Boolean? = null,
)

data class TikTokEmailCodeResponse(
    val code: Int,
    val msg: String,
    val email_code: String? = null,
)

data class TikTokTwoFaCodeResponse(
    val code: Int,
    val msg: String,
    val two_fa_code: String? = null,
    val source: String? = null,
    val period: Int? = null,
    val remaining_sec: Int? = null,
    val username: String? = null,
)

/** POST /api/device/tk_pending/acquire — 从待注册 TK 池领取并转入邮箱账户管理。 */
data class DeviceTkPendingAcquireResponse(
    val code: Int,
    val msg: String? = null,
    val available: Boolean? = null,
    val promoted: Boolean? = null,
    val taken: Boolean? = null,
    val email: String? = null,
    val password: String? = null,
    val client_id: String? = null,
    val refresh_token: String? = null,
)

/** 与 web-admin `switchRunMode` / 服务端 `RunModeRequest` 一致，仅使用精确字面量比较，禁止 contains 模糊匹配。 */
object ServerRunMode {
    const val GROWTH = "growth"
    const val LIVE = "live"
    const val DIRECT = "direct"
    /** 互点：与 growth 同为 mobile 待下发队列；后台单独切模式并可在设备上由指令进入互点流程 */
    const val MUTUAL = "mutual"
}

data class AppConfig(
    // 运行模式：growth=涨粉；live=主播；direct=直客
    val run_mode: String = ServerRunMode.GROWTH,
    // 恢复重启流程：force-stop 后固定等待时长（毫秒）
    val tiktok_force_stop_wait_ms: Long = 800L,
    // 恢复重启流程：启动 TikTok 后固定等待时长（毫秒）
    val tiktok_launch_wait_ms: Long = 2600L,
    // 每次打开 TikTok 后，先执行 For You 预滑的总时长（秒），0=关闭
    val for_you_swipe_total_sec: Double = 0.0,
    // For You 预滑阶段，每条视频执行点赞（双击屏幕）的概率
    val for_you_like_prob: Double = 0.0,
    // For You 预滑阶段，每条视频观看时长最小值（秒）
    val for_you_watch_min_sec: Double = 3.0,
    // For You 预滑阶段，每条视频观看时长最大值（秒）
    val for_you_watch_max_sec: Double = 12.0,
    // direct 模式：点击左侧首条作品后，等待多久开始互动（毫秒）
    val direct_post_action_delay_ms: Long = 1500L,
    // 各模式通用：进入用户主页后，按一定概率不点击作品，直接按“无作品/私密”同样的跳过方案处理
    val skip_post_click_prob: Double = 0.0,
    // growth 模式：点击作品后，前后帧差检测前等待多久（毫秒）
    val growth_post_check_after_tap_delay_ms: Long = 1500L,
    // growth 模式：第一次失败后，二次判定前等待多久（毫秒）
    val growth_post_second_check_delay_ms: Long = 900L,
    /** 单次拉取用户名数量，与模板键 pull_batch_size 一致，由云端运行配置下发 */
    val pull_batch_size: Int = 5,
    val break_min: Int = 10,
    val break_max: Int = 20,
    /** 为 true 时本模板设备永不进入云端批次休息（优先于 break_min/break_max）。 */
    val no_rest: Boolean = false,
    val visit_delay_min: Double = 0.5,
    val visit_delay_max: Double = 2.0,
    val video_stay_min: Double = 3.0,
    val video_stay_max: Double = 10.0,
    val like_prob: Double = 0.4,
    val comment_prob: Double = 0.3,
    val favorite_prob: Double = 0.3,
    val share_prob: Double = 0.3,
    // 互点 teamwork Videos：每「左+右各进作品一次」为 1 轮；轮与轮之间列表下滑约半屏换批。完整互点与此共用本上限（另受 mutual_follow_tap_session_limit 等约束）；历史「仅互点评论」指令仍生效但管理台已合并为单一启动入口。
    val mutual_videos_scroll_rounds: Int = 5,
    /** 本会话内作者区 + Followers 列表 Follow 点击累计上限（两处点击共用计数）。 */
    val mutual_follow_tap_session_limit: Int = 20,
    /** 引流：进作品后是否发 mutual_comment_texts 评论。 */
    val mutual_traffic_comment: Boolean = true,
    /** 引流：左滑进作者层后是否在作者区 OCR 点 Follow（计入 [mutual_follow_tap_session_limit]）。 */
    val mutual_traffic_author_follow: Boolean = true,
    /** 引流：是否复制用户名并进入 Followers 列表点 Follow（计入上限；可与作者区独立开关）。 */
    val mutual_traffic_followers_follow: Boolean = true,
    /** 与上述三项引流动作互斥：互点-仅关注（进评论区关注，不发评/不点作者区与 Followers 列表）。 */
    val mutual_traffic_follow_only: Boolean = false,
    /** 已废弃：涨粉满批后自动引流。引流仅在设备 App「互点模式」下独立运行，本字段忽略。 */
    val growth_pull_batches_before_auto_mutual: Int = -1,
    // 评论动作内部细分（文字）
    val comment_text_prob: Double = 0.7,
    // 每个动作每天的上限（默认 800）
    val like_daily_limit: Int = 800,
    val comment_daily_limit: Int = 800,
    // 引流评论单独每日上限：不与通用评论共用
    val mutual_traffic_comment_daily_limit: Int = 800,
    val favorite_daily_limit: Int = 800,
    val share_daily_limit: Int = 800,
    // 是否只执行一个动作（默认开启，符合互斥规则）
    val action_single: Boolean = true,
    // 互动动作之间的随机间隔（秒）
    val action_delay_min: Double = 0.7,
    val action_delay_max: Double = 1.2,
    val comment_texts: List<String> = listOf("hi", "moring", "so cute"),
    val mutual_comment_texts: List<String> = listOf("hi", "moring", "so cute"),
    /**
     * 引流：TikTok 搜索框输入的关键词库（逗号/换行多条）；每次进入/恢复 Videos 标签流程时随机选一条。
     * 为空则设备端回退为 "teamwork"（与旧版固定搜索一致）。
     */
    val mutual_traffic_target_texts: List<String> = listOf("teamwork"),
    /** 远控「修改基本资料」：Bio 编辑时随机选用其一；逗号/换行分隔。缺省或 null 视为空列表（Bio 步会失败并提示配置）。 */
    val profile_edit_bio_texts: List<String>? = null,
    /** 发作品前经本机 `POST /api/v1/tasks/request` 向 PC 拉取素材数量；0=不拉取。默认 3。 */
    val publish_post_material_fetch_count: Int = 3,
    /**
     * 自动切号：每号每时段上班后先消化的主播访问条数（bundle 预取自 live_customer）；0=关闭。
     * 消化过程设备本地执行，不向云端 pull 主播名单。
     */
    val live_visit_count_per_slot: Int = 0,
)

data class ConfigResponse(
    val code: Int,
    val config: AppConfig? = null,
    val run_mode: String? = null,
    val active_template: String? = null,
    val msg: String? = null,
)

data class CheckStartResponse(
    val code: Int,
    val msg: String,
    val status: String? = null,
    /** 云端「自动开启」策略；为 true 且本机未暂停时，设备应在未运行时自行拉起 */
    val auto_start: Boolean? = null,
    /** 与 PrefsStore 中 last_applied_config_push_revision 对齐；管理台保存配置后服务端递增 */
    val config_push_revision: Int? = null,
    /** 管理台请求拉取本机日志时为 true */
    val log_pull_requested: Boolean? = null,
    val log_pull_max_lines: Int? = null,
)

data class PullUsernameResponse(
    val code: Int,
    val usernames: List<String>? = null,
    val msg: String? = null,
    val rest_minutes: Int? = null,
    val force_stop_tiktok: Boolean? = null,
)

data class ReportResultRequest(
    val device_id: String,
    val alias: String,
    val result: String,
    /** 与设备端选择的运行队列一致；缺省时服务端按全局 run_mode 处理上报逻辑 */
    val run_mode: String? = null,
)

data class CvTemplateResponse(
    val code: Int,
    val msg: String? = null,
    val version: String? = null,
    val sha256: String? = null,
    val zip_b64: String? = null,
)

data class DeviceOpPollRequest(
    val device_id: String,
    val alias: String,
)

/** 与服务端 `MutualClickRequest` 及互点相关 admin 路由请求体一致。 */
data class MutualClickRequest(
    val device_id: String,
    val alias: String,
    val package_name: String,
)

data class DeviceOpPayload(
    val device_id: String? = null,
    val alias: String? = null,
    val package_name: String? = null,
    val backup_name: String? = null,
    val backup_path: String? = null,
    val backup_record_id: Int? = null,
    /** 重复备份时服务端下发的上一份设备端 tar 路径，成功后由设备 rm -f。 */
    val previous_backup_path: String? = null,
    /** 为 true 时还原前跳过 pm clear（切号流程：前序已备份并清理） */
    val skip_pm_clear: Boolean? = null,
    /** 切号：备份并清理成功回传后由云端写库；设备原样回传 */
    val cut_switch_after_backup: String? = null,
    val cut_switch_target_row_index: Int? = null,
    /** 还原成功后云端要写切号高亮的 device_editor_rows.row_index */
    val restore_highlight_row_index: Int? = null,
    val username: String? = null,
    val text: String? = null,
    val key_code: Int? = null,
    /** 图库导入：素材数组（图片/视频，base64 + 文件名 + mime） */
    val media_items: List<DeviceGalleryMediaPayload>? = null,
    /** 兼容旧字段（仅图片） */
    val image_items: List<DeviceGalleryMediaPayload>? = null,
    /** 修改基本资料：云端图库随机头像（单张） */
    val avatar_media_item: DeviceGalleryMediaPayload? = null,
    /** 发作品：云端指定本次目标发布条数（自动切号按 video_count 下发）。 */
    val publish_count: Int? = null,
    /** 发作品来源标记，如 auto_switch。 */
    val source: String? = null,
    /** 自动切号发作品：当日计划日期（YYYY-MM-DD，北京时间）。 */
    val auto_switch_schedule_date: String? = null,
    /** 自动切号发作品：时段键 `{date}|{slot_index}`。 */
    val auto_switch_slot_key: String? = null,
    /** 自动切号发作品：本时段目标账号 username。 */
    val account_username: String? = null,
    /** 自动切号主播访问：本时段预取的主播用户名列表（设备本地消化）。 */
    val live_visit_usernames: List<String>? = null,
    /** 批量带流：会话 ID */
    val session_id: String? = null,
    /** 批量带流：单账号活跃时长（小时） */
    val hours_per_account: Double? = null,
    /** 批量带流：活跃轮数（全部账号完成一轮后可重复执行） */
    val activity_rounds: Int? = null,
    /** 批量带流：Excel 状态列索引（0-based） */
    val status_column: Int? = null,
    /** 批量带流：本设备待处理行 */
    val rows: List<BatchStreamRowPayload>? = null,
    /** PC 投屏/伴侣服务地址，如 http://192.168.1.6:5088 */
    val windows_base_url: String? = null,
)

data class BatchStreamRowPayload(
    val sheet_row: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val email: String? = null,
    val email_password: String? = null,
    val api: String? = null,
)

data class BatchStreamReportRowRequest(
    val device_id: String,
    val alias: String,
    val session_id: String,
    val sheet_row: Int,
    val status_text: String,
    val hours_active: Double,
)

data class BatchStreamCompleteRequest(
    val device_id: String,
    val alias: String,
)

data class GalleryLibraryRandomResponse(
    val code: Int,
    val msg: String,
    val item: DeviceGalleryMediaPayload? = null,
)

data class DeviceGalleryMediaPayload(
    val filename: String? = null,
    val content_type: String? = null,
    val content_b64: String? = null,
    val size: Int? = null,
    val media_type: String? = null,
)

data class DeviceOp(
    val op_id: String,
    val op_type: String,
    val payload: DeviceOpPayload? = null,
    val created_at: String? = null,
)

data class DeviceOpPollResponse(
    val code: Int,
    val msg: String,
    val op: DeviceOp? = null,
)

data class DeviceOpReportRequest(
    val device_id: String,
    val alias: String,
    val op_id: String,
    val status: String,
    val message: String,
    val data: Map<String, Any>? = null,
)

data class DeviceLogsSyncRequest(
    val device_id: String,
    val alias: String,
    val logs: List<String>,
    val replace: Boolean = false,
)

data class DeviceAutoStartOptOutRequest(
    val device_id: String,
    val opt_out: Boolean,
)

/** 设备鉴权：上报本设备是否参与云端「自动开启程序」（硬停、发作品等；互点不再使用）。 */
data class ReportDeviceAutoStartOptOutRequest(
    val device_id: String,
    val alias: String,
    val opt_out: Boolean,
)

data class DeviceAutoStartOptOutResponse(
    val code: Int,
    val msg: String,
    val device_id: String? = null,
    val alias: String? = null,
    val auto_start_opt_out: Boolean? = null,
)

data class DeviceTikTokAccountRecord(
    val id: Int? = null,
    val username: String? = null,
    val tiktok_password: String? = null,
    val mobile_alias: String? = null,
    val api_address: String? = null,
    val email: String? = null,
    val email_password: String? = null,
    val login_date: String? = null,
    val video_count: Int? = null,
    val follower_count: Int? = null,
    val new_follower_count: Int? = null,
    val backup_path: String? = null,
    val backup_name: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
)

data class DeviceTikTokAccountsByAliasResponse(
    val code: Int,
    val msg: String? = null,
    val items: List<DeviceTikTokAccountRecord>? = null,
)

data class DeviceEditorRowRecord(
    val row_index: Int? = null,
    val device_alias: String? = null,
    val device_id: String? = null,
    val ip: String? = null,
    val region: String? = null,
    val username: String? = null,
    val assigned_alias: String? = null,
    val backup_path: String? = null,
    val backup_name: String? = null,
    val backup_time: String? = null,
    val backup_status: String? = null,
    val remark: String? = null,
    /** 切号高亮：「是」或「否」 */
    val switch_highlight: String? = null,
)

data class DeviceEditorRowsResponse(
    val code: Int,
    val msg: String? = null,
    val items: List<DeviceEditorRowRecord>? = null,
)

data class AccountEditorRowRecord(
    val row_index: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val email: String? = null,
    val email_password: String? = null,
    val api: String? = null,
    val is_login: Int? = null,
    val login_time: String? = null,
    val region: String? = null,
    val video_count: String? = null,
    val follower_count: String? = null,
    val follower_growth: String? = null,
    /** 「是」表示已在设备/线下完成 TikTok 基本资料修改；「否」为默认。 */
    val basic_profile_modified: String? = null,
)

data class AccountEditorRowsResponse(
    val code: Int,
    val msg: String? = null,
    val items: List<AccountEditorRowRecord>? = null,
)

/** 设备端：当前别名分配账号（切号后自动登录）。 */
data class DeviceAssignedAccountResponse(
    val code: Int,
    val msg: String? = null,
    val row_index: Int? = null,
    val username: String? = null,
    val email: String? = null,
    val password: String? = null,
    val email_password: String? = null,
    val two_fa: String? = null,
    val api: String? = null,
    val is_login: Boolean? = null,
    val login_time: String? = null,
    /** 「是」= 已完成基本资料修改；「否」或未返回时视为待修改。 */
    val basic_profile_modified: String? = null,
    /** 服务端邮箱管理模块是否可用该邮箱 OAuth 拉取验证码。 */
    val mail_module_code_available: Boolean? = null,
)

/** 设备端：别名桶内单条已分配账号（列表接口）。 */
data class DeviceAssignedAccountItem(
    val row_index: Int? = null,
    val account_row_index: Int? = null,
    val username: String? = null,
    val email: String? = null,
    val password: String? = null,
    val email_password: String? = null,
    val two_fa: String? = null,
    val api: String? = null,
    val is_login: Boolean? = null,
    val login_time: String? = null,
    val basic_profile_modified: String? = null,
    val mail_module_code_available: Boolean? = null,
    val backup_path: String? = null,
    val backup_name: String? = null,
    val backup_time: String? = null,
    val backup_status: String? = null,
    val switch_highlight: String? = null,
    val env_status: String? = null,
    /** true=不参加自动切号；false/null=参加。 */
    val auto_switch_excluded: Boolean? = null,
    /** 云端 password/email/email_password 标 banned。 */
    val looks_banned: Boolean? = null,
    val video_count: String? = null,
    val follower_count: String? = null,
    /** 今日粉丝增长（优先 follower_growth_today）。 */
    val follower_growth: String? = null,
    val heart_count: String? = null,
    /** 三天总赞增长（前天+昨天+今天）。 */
    val heart_growth: String? = null,
    val heart_growth_day_before: String? = null,
    val heart_growth_yesterday: String? = null,
    val heart_growth_today: String? = null,
)

/** GET /api/device/assigned_accounts */
data class DeviceAssignedAccountsResponse(
    val code: Int,
    val msg: String? = null,
    val items: List<DeviceAssignedAccountItem>? = null,
    val count: Int? = null,
)

/** POST /api/device/assigned_account/set_auto_switch_excluded */
data class DeviceAssignedAccountSetAutoSwitchExcludedRequest(
    val device_id: String,
    val alias: String,
    val username: String,
    val excluded: Boolean,
)

data class DeviceAssignedAccountSetAutoSwitchExcludedResponse(
    val code: Int,
    val msg: String? = null,
    val username: String? = null,
    val excluded: Boolean? = null,
    val participating: Boolean? = null,
    val updated_rows: Int? = null,
    val row_index: Int? = null,
    val alias: String? = null,
    val bucket_size: Int? = null,
    val plan_regenerated: Boolean? = null,
    val devices_notified: Int? = null,
    val devices_disabled: Int? = null,
)

/** POST /api/device/assigned_accounts/sync_auto_switch_participation */
data class DeviceAssignedAccountsSyncParticipationRequest(
    val device_id: String,
    val alias: String,
    /** null=不改账号参与标记；非 null（含空列表）=按名单全量同步。 */
    val participate_usernames: List<String>? = null,
    val enable_device_auto_switch: Boolean? = null,
)

data class DeviceAssignedAccountsSyncParticipationResponse(
    val code: Int,
    val msg: String? = null,
    val alias: String? = null,
    val requested_participate: List<String>? = null,
    val updated_rows: Int? = null,
    val unchanged_rows: Int? = null,
    val set_participate: List<String>? = null,
    val set_exclude: List<String>? = null,
    val bucket_size: Int? = null,
    val plan_regenerated: Boolean? = null,
    val devices_notified: Int? = null,
    val devices_disabled: Int? = null,
    val device_auto_switch: Map<String, Any>? = null,
)

data class DeviceAssignedAccountMarkLoginRequest(
    val device_id: String,
    val alias: String,
    val username: String,
)

data class DeviceAssignedAccountMarkBannedRequest(
    val device_id: String,
    val alias: String,
    val username: String,
)

data class DeviceAssignedAccountMarkBannedResponse(
    val code: Int,
    val msg: String? = null,
    val username: String? = null,
    val password: String? = null,
    val email: String? = null,
    val email_password: String? = null,
    val account_updated: Int? = null,
    val device_excluded_rows: Int? = null,
    val plan_regenerated_aliases: Int? = null,
    val devices_notified: Int? = null,
    val cloud_synced: Boolean? = null,
)

data class DeviceAssignedAccountUpdateUsernameRequest(
    val device_id: String,
    val alias: String,
    val new_username: String,
)

data class DeviceAssignedAccountUpdateUsernameResponse(
    val code: Int,
    val msg: String? = null,
    val username: String? = null,
    val old_username: String? = null,
    val updated: Boolean? = null,
)

/** POST /api/device/assigned_account/update_email */
data class DeviceAssignedAccountUpdateEmailRequest(
    val device_id: String,
    val alias: String,
    val username: String,
    val email: String,
    val email_password: String = "",
    val clear_api: Boolean = true,
)

data class DeviceAssignedAccountUpdateEmailResponse(
    val code: Int,
    val msg: String? = null,
    val username: String? = null,
    val email: String? = null,
    val email_password: String? = null,
    val old_email: String? = null,
    val old_email_password: String? = null,
    val api_cleared: Boolean? = null,
    val mail_module_code_available: Boolean? = null,
    val updated: Boolean? = null,
)

data class TikTokAutoLoginCredentials(
    val username: String,
    val email: String,
    val password: String,
    val apiUrl: String,
)

data class RuntimeState(
    val serviceRunning: Boolean = false,
    val localStatus: String = "online",
    val currentServerUrl: String = "",
    val configLoaded: Boolean = false,
    val effectiveRunMode: String = "",
    val effectiveTemplate: String = "",
    val lastMessage: String = "等待初始化",
    val hardStopRequested: Boolean = false,
    /** 最近一次 check_start 看到的云端“自动起号/自动开启策略”开关；null=尚未拉取到 */
    val cloudAutoStart: Boolean? = null,
)

data class ReportMutualCopiedUsernameRequest(
    val device_id: String,
    val alias: String,
    val username: String,
)

data class ReportMutualCopiedUsernameResponse(
    val code: Int,
    val msg: String,
    val username: String? = null,
    val usage_count: Int? = null,
)

data class FailureReportUploadResponse(
    val code: Int,
    val msg: String,
    val report_id: String? = null,
    val dedup_key: String? = null,
)

data class DeviceModeEvent(
    val event_id: Int,
    val type: String,
    val session_id: String,
    val bundle_revision: Int? = null,
    val payload: Map<String, Any>? = null,
    val issued_at: String? = null,
)

data class DeviceModeWaitResponse(
    val code: Int,
    val msg: String? = null,
    val event: DeviceModeEvent? = null,
    val log_pull_requested: Boolean? = null,
    val log_pull_max_lines: Int? = null,
    /** 云端该设备 auto_switch_enabled 真源；自治期设备可据此对账退出。 */
    val auto_switch_enabled: Boolean? = null,
)

data class DeviceModeAckRequest(
    val device_id: String,
    val alias: String,
    val event_id: Int,
    val phase: String,
    val session_id: String = "",
)

data class AutoSwitchBundleResponse(
    val code: Int,
    val msg: String? = null,
    val bundle: AutoSwitchBundle? = null,
)

data class AutoSwitchBundle(
    val session_id: String? = null,
    val revision: Int? = null,
    val schedule_date: String? = null,
    val week_start_date: String? = null,
    val week_end_date: String? = null,
    val timezone: String? = null,
    val owner_username: String? = null,
    val device_alias: String? = null,
    val device_id: String? = null,
    /** 云端为该设备配置的 PC 伴侣服务地址；与本地不一致时由 applyAutoSwitchBundle 同步。 */
    val pc_service_url: String? = null,
    /**
     * 切号方案：
     * - backup（默认）：备份 → 清理 → 还原/登录
     * - login：不备份不还原，清理 TikTok 数据后直接登录目标号
     */
    val cut_switch_scheme: String? = null,
    val run_config: AutoSwitchRunConfig? = null,
    /** 按 TikTok 用户名（小写）打包的 enter 冻结配置；切号时本地切换，不再拉 getConfig。 */
    val configs_by_username: Map<String, AutoSwitchBundledAccountConfig>? = null,
    /** 智能模板解析审计（enter 时 session 重掷 + 每账号分支/权重命中）。 */
    val smart_template_debug: AutoSwitchSmartTemplateDebug? = null,
    val accounts: List<AutoSwitchAccount>? = null,
    val daily_plan: AutoSwitchDailyPlan? = null,
    val weekly_plan: AutoSwitchWeeklyPlan? = null,
    val retry_policy: AutoSwitchRetryPolicy? = null,
) {
    fun prefersLoginCutSwitch(): Boolean {
        val scheme = cut_switch_scheme?.trim()?.lowercase().orEmpty()
        return scheme == "login" || scheme == "login_switch" || scheme == "exclusive_login"
    }
}

data class AutoSwitchBundledAccountConfig(
    val active_template: String? = null,
    val config: AppConfig? = null,
    /** 本账号本时段预取的主播访问用户名（自治期本地消化，不再 pull）。 */
    val live_visit_usernames: List<String>? = null,
)

data class AutoSwitchSmartTemplateDebug(
    val enabled: Boolean? = null,
    val session_id: String? = null,
    val mode: String? = null,
    val alias: String? = null,
    val enter_reroll: Boolean? = null,
    val accounts: Map<String, AutoSwitchSmartTemplateAccountDebug>? = null,
)

data class AutoSwitchSmartTemplateAccountDebug(
    val branch: String? = null,
    val branch_label: String? = null,
    val follower_count: Int? = null,
    val video_count: Int? = null,
    val tiktok_username: String? = null,
    val threshold_followers: Int? = null,
    val threshold_video_count: Int? = null,
    val seed: String? = null,
    val picked_template: String? = null,
    val selected_template: String? = null,
    val roll: Int? = null,
    val total_weight: Int? = null,
    val resolution_source: String? = null,
    val session_id: String? = null,
)

data class AutoSwitchWeeklyPlan(
    val week_start_date: String? = null,
    val week_end_date: String? = null,
    val account_count: Int? = null,
    val day_plans: List<AutoSwitchWeeklyDayPlan>? = null,
)

data class AutoSwitchWeeklyDayPlan(
    val schedule_date: String? = null,
    val daily_plan: AutoSwitchDailyPlan? = null,
)

data class AutoSwitchRunConfig(
    val run_mode: String? = null,
    val active_template: String? = null,
    val publish_post_material_fetch_count: Int? = null,
    val live_visit_count_per_slot: Int? = null,
    val package_name: String? = null,
)

data class AutoSwitchAccount(
    val row_index: Int? = null,
    val username: String? = null,
    val backup_path: String? = null,
    val backup_name: String? = null,
    val assigned_alias: String? = null,
    val device_alias: String? = null,
    val switch_highlight: String? = null,
    val video_count: String? = null,
    val follower_count: String? = null,
    val follower_growth: String? = null,
    /** account_editor_rows.row_index（自动登录上报用）。 */
    val account_row_index: Int? = null,
    val email: String? = null,
    val password: String? = null,
    val email_password: String? = null,
    val api: String? = null,
    val is_login: Boolean? = null,
    val login_time: String? = null,
    val basic_profile_modified: String? = null,
    val mail_module_code_available: Boolean? = null,
)

data class AutoSwitchDailyPlan(
    val slot_minutes: Int? = null,
    val account_count: Int? = null,
    val slots: List<AutoSwitchPlanSlot>? = null,
)

data class AutoSwitchPlanSlot(
    val slot_index: Int? = null,
    val start_min: Int? = null,
    val end_min: Int? = null,
    val row_index: Int? = null,
    val username: String? = null,
    val auto_publish_minute: Int? = null,
    /** 引流互点（teamwork Videos）时间点；与 [auto_publish_minute] 同级，每 slot 一个。 */
    val auto_mutual_minute: Int? = null,
)

data class AutoSwitchRetryPolicy(
    val cut_switch_max_attempts: Int? = null,
    val login_max_attempts: Int? = null,
    val publish_max_attempts: Int? = null,
    val backoff_base_sec: Int? = null,
    val backoff_max_sec: Int? = null,
)

data class AutoSwitchLedgerEntryPayload(
    val at: String = "",
    val op_id: String = "",
    val op_type: String = "",
    val status: String = "",
    /** 账本阶段：queued / running / success / failed */
    val phase: String = "",
    val message: String = "",
    val slot_key: String = "",
    val username: String = "",
    /** 设备 OP 回传 data 子集，供云端会话结束时写回 device_editor_rows。 */
    val report_data: Map<String, Any> = emptyMap(),
)

data class AutoSwitchSessionSummaryRequest(
    val device_id: String,
    val alias: String,
    val session_id: String,
    val bundle_revision: Int = 0,
    val schedule_date: String = "",
    val started_at: String = "",
    val ended_at: String = "",
    val ledger: List<AutoSwitchLedgerEntryPayload> = emptyList(),
    val human_log: String = "",
    val stats: Map<String, Any> = emptyMap(),
    /** final=关会话；daily=周期内每日对账 */
    val summary_kind: String = "final",
    /** 幂等键：final 用 final；daily 用北京时间日期 */
    val reconcile_key: String = "",
    /** enter 时 bundle 内 smart_template_debug，供管理台会话总结审计。 */
    val smart_template_debug: Map<String, Any> = emptyMap(),
)

data class AutoSwitchSessionSummaryResponse(
    val code: Int,
    val msg: String? = null,
    val summary_id: Int? = null,
    val reconciliation: Map<String, Any>? = null,
)
