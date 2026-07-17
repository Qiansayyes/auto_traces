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
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun backupRecordDao(): BackupRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 单例数据库实例。
         *
         * 说明：
         * - 使用 applicationContext 避免 Activity 泄漏
         * - Room 自带线程检查，所以数据库访问必须放在协程 IO 线程里
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autotraces_backup.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
