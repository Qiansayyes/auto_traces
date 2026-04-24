package com.autotraces.app.backup

import android.content.Context
import com.autotraces.app.AppConstants
import com.autotraces.app.PrefsStore
import com.autotraces.app.backup.db.AppDatabase
import com.autotraces.app.backup.model.BackupRecord
import com.autotraces.app.backup.network.BackupReporter

/**
 * 备份业务仓库。
 *
 * 这一层的职责：
 * 1. 调用本地备份/恢复工具
 * 2. 把结果写入 Room
 * 3. 处理云端上传后 task_id 回填
 * 4. 提供按 scope 查询的历史数据
 */
class BackupRepository(private val context: Context) {

    private val dao = AppDatabase.getInstance(context).backupRecordDao()
    private val manager = AppDataBackupManager(context)
    private val reporter = BackupReporter()

    private fun currentMeta(): RecordMeta {
        return RecordMeta(
            deviceAlias = PrefsStore.getAlias().orEmpty(),
            deviceId = PrefsStore.getDeviceId().orEmpty(),
            appAccount = PrefsStore.getAuthUsername().orEmpty(),
        )
    }

    suspend fun backupLocal(accountName: String, packageName: String): BackupRecord {
        val result = manager.backupAppData(accountName, packageName)
        val meta = currentMeta()
        val record = BackupRecord(
            accountName = accountName,
            packageName = packageName.ifBlank { AppConstants.APP_PACKAGE_NAME },
            deviceAlias = meta.deviceAlias,
            deviceId = meta.deviceId,
            appAccount = meta.appAccount,
            backupType = "backup",
            backupScope = "local",
            success = result.success,
            path = result.path,
            filename = result.filename,
            checksum = result.checksum,
            message = result.message,
            command = result.command,
            output = result.output,
            exitCode = result.exitCode,
        )
        val id = dao.insert(record)
        return record.copy(id = id)
    }

    suspend fun backupCloud(accountName: String, packageName: String): BackupRecord {
        val result = manager.backupAppData(accountName, packageName)
        val meta = currentMeta()
        val initialRecord = BackupRecord(
            accountName = accountName,
            packageName = packageName.ifBlank { AppConstants.APP_PACKAGE_NAME },
            deviceAlias = meta.deviceAlias,
            deviceId = meta.deviceId,
            appAccount = meta.appAccount,
            backupType = "backup",
            backupScope = "cloud",
            success = result.success,
            path = result.path,
            filename = result.filename,
            checksum = result.checksum,
            message = result.message,
            command = result.command,
            output = result.output,
            exitCode = result.exitCode,
            uploadStatus = if (result.success) "pending" else "failed",
        )
        val id = dao.insert(initialRecord)
        val inserted = initialRecord.copy(id = id)

        if (!inserted.success) return inserted

        val deviceId = inserted.deviceId
        val alias = inserted.deviceAlias
        if (deviceId.isBlank() || alias.isBlank()) {
            return inserted.copy(message = "cloud upload skipped: missing device identity")
        }

        val uploadResult = reporter.uploadBackupAndCreateTask(deviceId, alias, inserted)
        if (uploadResult.success) {
            dao.updateCloudTaskInfo(inserted.id, uploadResult.taskId, "success")
            return inserted.copy(cloudTaskId = uploadResult.taskId, uploadStatus = "success")
        }

        dao.updateCloudTaskInfo(inserted.id, null, "failed")
        return inserted.copy(uploadStatus = "failed", message = uploadResult.message)
    }

    suspend fun restore(accountName: String, zipPath: String, packageName: String): BackupRecord {
        val result = manager.restoreAppData(zipPath, packageName)
        val meta = currentMeta()
        val record = BackupRecord(
            accountName = accountName,
            packageName = packageName.ifBlank { AppConstants.APP_PACKAGE_NAME },
            deviceAlias = meta.deviceAlias,
            deviceId = meta.deviceId,
            appAccount = meta.appAccount,
            backupType = "restore",
            backupScope = "local",
            success = result.success,
            path = result.path,
            filename = result.filename,
            checksum = result.checksum,
            message = result.message,
            command = result.command,
            output = result.output,
            exitCode = result.exitCode,
        )
        val id = dao.insert(record)
        return record.copy(id = id)
    }

    suspend fun history(): List<BackupRecord> = dao.getAll()
    suspend fun localHistory(): List<BackupRecord> = dao.getByScope("local")
    suspend fun cloudHistory(): List<BackupRecord> = dao.getByScope("cloud")
    suspend fun latest(): BackupRecord? = dao.getAll().firstOrNull()
    suspend fun restoreFromRecord(record: BackupRecord): BackupRecord = restore(record.accountName, record.path, record.packageName)

    suspend fun refreshCloudTaskStatus(record: BackupRecord): BackupRecord {
        val taskId = record.cloudTaskId ?: return record
        val deviceId = record.deviceId.ifBlank { currentMeta().deviceId }
        val alias = record.deviceAlias.ifBlank { currentMeta().deviceAlias }
        if (deviceId.isBlank() || alias.isBlank()) return record

        val result = reporter.queryCloudTaskStatus(deviceId, alias, taskId)
        val newStatus = if (result.success) result.status else "failed"
        val newMessage = if (result.success) result.detail else result.detail
        dao.updateCloudTaskInfo(record.id, taskId, newStatus)
        return record.copy(uploadStatus = newStatus, message = newMessage)
    }

    private data class RecordMeta(
        val deviceAlias: String,
        val deviceId: String,
        val appAccount: String,
    )
}