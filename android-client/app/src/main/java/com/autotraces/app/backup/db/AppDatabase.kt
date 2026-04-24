package com.autotraces.app.backup.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.autotraces.app.backup.model.BackupRecord
import com.autotraces.app.backup.model.BackupRecordDao

/**
 * 应用本地数据库。
 *
 * 当前只放“备份/恢复任务历史”这一张表，后续如果你要扩展：
 * - 设备状态
 * - 远程任务队列
 * - 上传失败重试队列
 * 都可以继续往这里加 Entity 和 Dao。
 */
@Database(
    entities = [BackupRecord::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun backupRecordDao(): BackupRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autotraces_backup.db",
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
