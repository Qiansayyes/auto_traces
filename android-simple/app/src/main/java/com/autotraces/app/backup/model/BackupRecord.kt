package com.autotraces.app.backup.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 备份/恢复任务的本地历史记录。
 *
 * 字段说明：
 * - accountName：业务侧标识，用来区分不同账号或配置档案
 * - packageName：应用包名，方便多应用场景下做区分
 * - deviceAlias：设备别名，来自设备管理/概览页面
 * - deviceId：设备 ID，来自设备管理/概览页面
 * - appAccount：App 内部账号标识，备份前写入
 * - backupType：backup / restore
 * - backupScope：local / cloud，用于区分本地备份和云端备份
 * - cloudTaskId：云端创建任务后返回的任务 ID（仅云端备份时有值）
 * - uploadStatus：pending / success / failed，用于记录上传状态
 * - success：本次任务是否成功
 * - path / filename：生成的备份文件路径和文件名
 * - checksum：备份包校验值，便于上传后校验完整性
 * - message：详细结果描述，方便排查失败原因
 * - command / output / exitCode：Root tar 命令、命令输出、退出码，用于完整追踪失败原因
 */
@Entity(tableName = "backup_records")
data class BackupRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountName: String,
    val packageName: String,
    val deviceAlias: String,
    val deviceId: String,
    val appAccount: String,
    val backupType: String,
    val backupScope: String = "local",
    val cloudTaskId: String? = null,
    val uploadStatus: String? = null,
    val success: Boolean,
    val path: String,
    val filename: String,
    val checksum: String? = null,
    val message: String,
    val command: String? = null,
    val output: String? = null,
    val exitCode: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
