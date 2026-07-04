package com.autotraces.app.backup.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 备份/恢复历史记录访问接口。
 *
 * 所有方法都是挂起函数，便于在协程中直接调用，避免阻塞主线程。
 */
@Dao
interface BackupRecordDao {

    /**
     * 插入一条记录。
     *
     * 使用 REPLACE 策略是为了在你未来需要按业务主键去覆盖记录时更方便。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BackupRecord): Long

    /**
     * 直接更新一条记录。
     *
     * 在云端备份场景中，通常先插入一条本地记录，再回填 cloudTaskId / uploadStatus。
     */
    @Query("UPDATE backup_records SET cloudTaskId = :taskId, uploadStatus = :uploadStatus WHERE id = :id")
    suspend fun updateCloudTaskInfo(id: Long, taskId: String?, uploadStatus: String?): Int

    /**
     * 查询所有记录，按时间倒序排列。
     */
    @Query("SELECT * FROM backup_records ORDER BY createdAt DESC")
    suspend fun getAll(): List<BackupRecord>

    /**
     * 按账号名查询记录。
     */
    @Query("SELECT * FROM backup_records WHERE accountName = :accountName ORDER BY createdAt DESC")
    suspend fun getByAccount(accountName: String): List<BackupRecord>

    /**
     * 按备份范围查询记录：local / cloud。
     */
    @Query("SELECT * FROM backup_records WHERE backupScope = :backupScope ORDER BY createdAt DESC")
    suspend fun getByScope(backupScope: String): List<BackupRecord>

    /**
     * 按主键查单条记录。
     */
    @Query("SELECT * FROM backup_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BackupRecord?

    /**
     * 删除单条记录。
     */
    @Query("DELETE FROM backup_records WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /**
     * 清空整张表。
     */
    @Query("DELETE FROM backup_records")
    suspend fun clearAll(): Int
}
