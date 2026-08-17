package com.autotraces.app.videoded

data class DeviceRegistrationRequest(
    val deviceId: String,
    val deviceName: String,
    val serial: String? = null,
    val folderPath: String,
    val wifiIp: String? = null,
    val adbAuthorized: Boolean? = null,
    val mirrorStatus: String? = null,
    val mirrorMessage: String? = null,
    /** 本机无线 ADB 监听端口，供 PC 端 adb connect 使用。 */
    val adbTcpPort: Int? = null,
    /** Companion 本地 HTTP API 端口，供 PC 调用 /api/v1/adb/prepare 等。 */
    val companionApiPort: Int? = null,
    /** Companion 本地 API Token（X-Api-Token），注册时上报给 PC。 */
    val companionApiToken: String? = null,
    /** 稳定硬件标识（vd-{ANDROID_ID}），用于 PC 端合并同一部手机的多条记录。 */
    val hardwareKey: String? = null,
    /** 上次在 PC 端设置的分组，重启后随注册带回，避免合并时丢失。 */
    val groupName: String? = null,
)

data class CreateTaskRequest(
    val deviceId: String,
    val count: Int,
    val targetPath: String? = null,
    /** 与 PC 配对页「手动下发」一致：指定 deviceId + count + targetPath。 */
    val manualAssign: Boolean = true,
)

data class TaskAckRequest(
    val success: Boolean,
    val message: String? = null,
    val files: List<TaskFileAck> = emptyList(),
)

data class TaskFileAck(
    val fileName: String,
    val sizeBytes: Long,
    val md5: String,
)

data class CreateTaskResponse(
    val taskId: String,
    val status: String,
    val files: List<TaskFileInfo> = emptyList(),
    /** PC 是否已成功调用本机 follow-pc；为 false 时需本机 fallback 入队。 */
    val companionPullNotified: Boolean? = null,
    /** 素材传输通道：`adb_usb` / `adb_usb_http` / `http`。 */
    val deliveryChannel: String? = null,
    /** PC 是否已通过 USB `adb push` 把本任务文件推到 targetPath。 */
    val adbPushed: Boolean? = null,
    /** 本次绑定的 USB ADB 序列号（多机隔离用）。 */
    val usbSerial: String? = null,
)

data class TaskFileInfo(
    val fileName: String,
    val sizeBytes: Long,
    val md5: String,
)

data class TaskStatusResponse(
    val taskId: String,
    val status: String,
    val error: String? = null,
)

/** Windows GET /api/tasks/{taskId} 完整 JSON，用于跟随 PC 已创建的下发任务拉取素材。 */
data class PcTaskDetailDto(
    val taskId: String,
    val deviceId: String? = null,
    val targetPath: String? = null,
    val status: String,
    val error: String? = null,
    val files: List<TaskFileInfo>? = null,
)
