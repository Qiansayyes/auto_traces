package com.autotraces.app.backup.network

/**
 * 本地/云端备份档案上报请求。
 *
 * 说明：
 * - local 备份仅生成记录，不必调用上传接口
 * - cloud 备份会触发上传，并由服务端返回 task_id
 */
data class UploadBackupResultRequest(
    val device_id: String,
    val alias: String,
    val account_name: String,
    val package_name: String,
    val action: String, // backup / restore
    val backup_scope: String, // local / cloud
    val success: Boolean,
    val path: String,
    val filename: String,
    val checksum: String? = null,
    val message: String,
)

/**
 * 备份上报结果。
 *
 * 云端备份成功时，服务器会返回 task_id，用于和本地记录一一对应。
 */
data class UploadBackupResultResponse(
    val code: Int,
    val msg: String,
    val task_id: String? = null,
)

/**
 * 云端任务状态查询请求。
 */
data class CloudTaskStatusRequest(
    val device_id: String,
    val alias: String,
    val task_id: String,
)

/**
 * 云端任务状态查询响应。
 */
data class CloudTaskStatusResponse(
    val code: Int,
    val msg: String,
    val task_id: String? = null,
    val status: String? = null, // pending / running / success / failed
    val detail: String? = null,
)
