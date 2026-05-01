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
)

/** 与 web-admin `switchRunMode` / 服务端 `RunModeRequest` 一致，仅使用精确字面量比较，禁止 contains 模糊匹配。 */
object ServerRunMode {
    const val GROWTH = "growth"
    const val LIVE = "live"
    const val DIRECT = "direct"
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
    val for_you_watch_min_sec: Double = 2.0,
    // For You 预滑阶段，每条视频观看时长最大值（秒）
    val for_you_watch_max_sec: Double = 5.0,
    // direct 模式：点击左侧首条作品后，等待多久开始互动（毫秒）
    val direct_post_action_delay_ms: Long = 1500L,
    // 各模式通用：进入用户主页后，按一定概率不点击作品，直接按“无作品/私密”同样的跳过方案处理
    val skip_post_click_prob: Double = 0.0,
    // growth 模式：点击作品后，前后帧差检测前等待多久（毫秒）
    val growth_post_check_after_tap_delay_ms: Long = 1000L,
    // growth 模式：第一次失败后，二次判定前等待多久（毫秒）
    val growth_post_second_check_delay_ms: Long = 650L,
    val fans_per_break: Int = 5,
    val break_min: Int = 10,
    val break_max: Int = 20,
    val visit_delay_min: Double = 0.5,
    val visit_delay_max: Double = 2.0,
    val video_stay_min: Double = 3.0,
    val video_stay_max: Double = 10.0,
    val like_prob: Double = 0.4,
    val comment_prob: Double = 0.3,
    val favorite_prob: Double = 0.3,
    val share_prob: Double = 0.3,
    // 评论动作内部细分（文字）
    val comment_text_prob: Double = 0.7,
    // 每个动作每天的上限（默认 800）
    val like_daily_limit: Int = 800,
    val comment_daily_limit: Int = 800,
    val favorite_daily_limit: Int = 800,
    val share_daily_limit: Int = 800,
    // 是否只执行一个动作（默认开启，符合互斥规则）
    val action_single: Boolean = true,
    // 互动动作之间的随机间隔（秒）
    val action_delay_min: Double = 0.7,
    val action_delay_max: Double = 1.2,
    val comment_texts: List<String> = listOf("hi", "moring", "so cute"),
)

data class ConfigResponse(
    val code: Int,
    val config: AppConfig? = null,
    val run_mode: String? = null,
    val msg: String? = null,
)

data class CheckStartResponse(
    val code: Int,
    val msg: String,
    val status: String? = null,
    /** 云端「自动开启」策略；为 true 且本机未暂停时，设备应在未运行时自行拉起 */
    val auto_start: Boolean? = null,
)

data class PullUsernameResponse(
    val code: Int,
    val usernames: List<String>? = null,
    val msg: String? = null,
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

data class DeviceOpPayload(
    val device_id: String? = null,
    val alias: String? = null,
    val package_name: String? = null,
    val backup_name: String? = null,
    val backup_path: String? = null,
    val backup_record_id: Int? = null,
    val username: String? = null,
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
)

data class DeviceAutoStartOptOutRequest(
    val device_id: String,
    val opt_out: Boolean,
)

data class DeviceAutoStartOptOutResponse(
    val code: Int,
    val msg: String,
    val device_id: String? = null,
    val alias: String? = null,
    val auto_start_opt_out: Boolean? = null,
)

data class RuntimeState(
    val serviceRunning: Boolean = false,
    val localStatus: String = "online",
    val currentServerUrl: String = "",
    val configLoaded: Boolean = false,
    val lastMessage: String = "等待初始化",
    val hardStopRequested: Boolean = false,
)
