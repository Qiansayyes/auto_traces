package com.autotraces.app.backup.network

import com.autotraces.app.backup.model.BackupRecord

/**
 * 备份结果上报封装。
 *
 * 这一层负责把本地数据库里的结果，转换成后端需要的请求并提交。
 */
class BackupReporter {

    suspend fun uploadBackupAndCreateTask(
        deviceId: String,
        alias: String,
        record: BackupRecord,
    ): CloudUploadResult {
        val response = BackupRetrofitClient.service.reportBackupResult(
            UploadBackupResultRequest(
                device_id = deviceId,
                alias = alias,
                account_name = record.accountName,
                package_name = record.packageName,
                action = record.backupType,
                backup_scope = record.backupScope,
                success = record.success,
                path = record.path,
                filename = record.filename,
                checksum = record.checksum,
                message = record.message,
            )
        )

        val body = response.body()
        return if (response.isSuccessful && body != null && body.code == 0) {
            CloudUploadResult(
                success = true,
                taskId = body.task_id,
                message = body.msg,
            )
        } else {
            CloudUploadResult(
                success = false,
                taskId = null,
                message = body?.msg ?: "http ${response.code()}",
            )
        }
    }

    suspend fun queryCloudTaskStatus(
        deviceId: String,
        alias: String,
        taskId: String,
    ): CloudTaskStatusResult {
        val response = BackupRetrofitClient.service.queryCloudTaskStatus(
            CloudTaskStatusRequest(
                device_id = deviceId,
                alias = alias,
                task_id = taskId,
            )
        )

        val body = response.body()
        return if (response.isSuccessful && body != null && body.code == 0) {
            CloudTaskStatusResult(
                success = true,
                status = body.status ?: "unknown",
                detail = body.detail ?: body.msg,
            )
        } else {
            CloudTaskStatusResult(
                success = false,
                status = "failed",
                detail = body?.msg ?: "http ${response.code()}",
            )
        }
    }

    suspend fun reportRestoreResult(
        deviceId: String,
        alias: String,
        record: BackupRecord,
    ): Boolean = uploadBackupAndCreateTask(deviceId, alias, record).success
}

data class CloudUploadResult(
    val success: Boolean,
    val taskId: String?,
    val message: String,
)

data class CloudTaskStatusResult(
    val success: Boolean,
    val status: String,
    val detail: String,
)
