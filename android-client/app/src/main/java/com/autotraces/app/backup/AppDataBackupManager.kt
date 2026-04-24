package com.autotraces.app.backup

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 通用的 Root + tar 备份/恢复工具
 *
 * 支持任意包名（需目标应用已安装且有数据目录）
 * 备份范围：/data/data/<packageName>，排除 cache/code_cache/no_backup 等目录
 */
class AppDataBackupManager(private val context: Context) {

    /**
     * 备份指定包名的应用数据
     * @param accountName 业务账号名（用于文件名）
     * @param targetPackageName 要备份的应用包名（如 com.zhiliao.tiktok）
     */
    fun backupAppData(accountName: String, targetPackageName: String): BackupResult {
        return try {
            val backupDir = File(context.getExternalFilesDir(null), "backups")
            if (!backupDir.exists()) backupDir.mkdirs()

            val dataDir = File("/data/data/$targetPackageName")
            if (!dataDir.exists()) {
                return BackupResult(
                    success = false,
                    path = "",
                    filename = "",
                    message = "Target package data directory not found: $dataDir"
                )
            }

            val parentDir = dataDir.parentFile ?: return BackupResult(
                success = false, path = "", filename = "",
                message = "Cannot get parent dir of $dataDir"
            )
            val packageName = dataDir.name

            val filename = "app_${accountName}_${System.currentTimeMillis()}.tar.gz"
            val outFile = File(backupDir, filename)
            if (outFile.exists()) outFile.delete()

            val command = buildString {
                append("tar -czf ")
                append(shellQuote(outFile.absolutePath))
                append(" -C ")
                append(shellQuote(parentDir.absolutePath))
                append(" --exclude=")
                append(shellQuote("$packageName/cache"))
                append(" --exclude=")
                append(shellQuote("$packageName/code_cache"))
                append(" --exclude=")
                append(shellQuote("$packageName/no_backup"))
                append(" ")
                append(shellQuote(packageName))
            }

            val result = runAsRoot(command)
            if (result.exitCode != 0 || !outFile.exists()) {
                return BackupResult(
                    success = false,
                    path = outFile.absolutePath,
                    filename = filename,
                    exitCode = result.exitCode,
                    command = command,
                    output = result.output,
                    message = "tar backup failed: exit=${result.exitCode}; ${result.output.ifBlank { "no output" }}",
                )
            }

            BackupResult(
                success = true,
                path = outFile.absolutePath,
                filename = filename,
                checksum = sha256(outFile),
                command = command,
                output = result.output,
                exitCode = result.exitCode,
                message = "backup completed",
            )
        } catch (e: Exception) {
            Log.e("AppDataBackupManager", "backup failed", e)
            BackupResult(
                success = false,
                path = "",
                filename = "",
                message = e.message ?: "unknown error",
            )
        }
    }

    /**
     * 恢复指定包名的应用数据
     * @param zipPath 备份文件路径
     * @param targetPackageName 目标应用包名（用于校验和 chown）
     */
    fun restoreAppData(zipPath: String, targetPackageName: String): BackupResult {
        return try {
            val backupFile = File(zipPath)
            if (!backupFile.exists()) {
                return BackupResult(
                    success = false,
                    path = zipPath,
                    filename = backupFile.name,
                    message = "backup file not found",
                )
            }

            val dataDir = File("/data/data/$targetPackageName")
            if (!dataDir.exists()) {
                return BackupResult(
                    success = false,
                    path = zipPath,
                    filename = backupFile.name,
                    message = "Target package data directory not found: $dataDir"
                )
            }

            val parentDir = dataDir.parentFile ?: return BackupResult(
                success = false, path = zipPath, filename = backupFile.name,
                message = "Cannot get parent dir of $dataDir"
            )
            val packageName = dataDir.name

            // 1. 强制停止目标应用
            runAsRoot("am force-stop $targetPackageName")

            // 2. 删除现有数据目录（可选，使用 tar 的 --overwrite 也可，但建议清空）
            runAsRoot("rm -rf ${shellQuote(dataDir.absolutePath)}")

            // 3. 解压备份包
            val command = buildString {
                append("tar -xzf ")
                append(shellQuote(backupFile.absolutePath))
                append(" -C ")
                append(shellQuote(parentDir.absolutePath))
            }
            val result = runAsRoot(command)
            if (result.exitCode != 0) {
                return BackupResult(
                    success = false,
                    path = backupFile.absolutePath,
                    filename = backupFile.name,
                    exitCode = result.exitCode,
                    command = command,
                    output = result.output,
                    message = "tar restore failed: exit=${result.exitCode}; ${result.output.ifBlank { "no output" }}",
                )
            }

            // 4. 获取目标应用的 UID 并修复权限
            val uidResult = runAsRoot("grep '$targetPackageName' /data/system/packages.list | cut -d' ' -f2")
            val uid = uidResult.output.trim()
            if (uid.isNotEmpty()) {
                runAsRoot("chown -R $uid:$uid ${shellQuote(dataDir.absolutePath)}")
            }
            runAsRoot("chmod -R 755 ${shellQuote(dataDir.absolutePath)}")
            runAsRoot("restorecon -R ${shellQuote(dataDir.absolutePath)}")

            BackupResult(
                success = true,
                path = backupFile.absolutePath,
                filename = backupFile.name,
                checksum = sha256(backupFile),
                command = command,
                output = result.output,
                exitCode = result.exitCode,
                message = "restore completed for $targetPackageName",
            )
        } catch (e: Exception) {
            Log.e("AppDataBackupManager", "restore failed", e)
            BackupResult(
                success = false,
                path = zipPath,
                filename = File(zipPath).name,
                message = e.message ?: "unknown error",
            )
        }
    }

    private fun runAsRoot(command: String): RootCommandResult {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = ByteArrayOutputStream()
        process.inputStream.use { input -> input.copyTo(output) }
        return RootCommandResult(
            exitCode = process.waitFor(),
            output = output.toString().trim(),
        )
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var read = input.read(buffer)
            while (read >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class RootCommandResult(val exitCode: Int, val output: String)
data class BackupResult(
    val success: Boolean,
    val path: String,
    val filename: String,
    val checksum: String? = null,
    val command: String? = null,
    val output: String? = null,
    val exitCode: Int? = null,
    val message: String,
)