package com.autotraces.app.backup

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 应用数据本地备份/恢复工具。
 *
 * 这个版本统一改成 Root + tar 的实现：
 * - 备份时通过 su 调用 tar 打包当前 App 私有目录
 * - 恢复时通过 su 调用 tar 解包回当前 App 私有目录
 * - 备份范围统一排除 cache
 *
 * 说明：
 * - 这里只适用于你自己拥有或明确授权的 App
 * - 需要设备已 Root，并且 su 可用
 */
class AppDataBackupManager(private val context: Context) {

    fun backupAppData(accountName: String): BackupResult {
        return try {
            val backupDir = File(context.getExternalFilesDir(null), "backups")
            if (!backupDir.exists()) backupDir.mkdirs()

            val dataDir = File(context.applicationInfo.dataDir)
            val parentDir = dataDir.parentFile ?: throw IllegalStateException("Missing parent dir for ${dataDir.absolutePath}")
            val packageName = dataDir.name
            val filename = "backup_${accountName}_${System.currentTimeMillis()}.tar.gz"
            val outFile = File(backupDir, filename)

            if (outFile.exists()) outFile.delete()

            val command = buildString {
                append("cd ")
                append(shellQuote(parentDir.absolutePath))
                append(" && find ")
                append(shellQuote(packageName))
                append(" \\( -type f -o -type l \\) ! -path '*/cache/*' ! -path '*/code_cache/*' ! -path '*/no_backup/*' -print0 | tar -czf ")
                append(shellQuote(outFile.absolutePath))
                append(" --null -T -")
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

    fun restoreAppData(zipPath: String): BackupResult {
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

            val dataDir = File(context.applicationInfo.dataDir)
            val parentDir = dataDir.parentFile ?: throw IllegalStateException("Missing parent dir for ${dataDir.absolutePath}")
            val packageName = dataDir.name

            val command = buildString {
                append("am force-stop ")
                append(shellQuote(packageName))
                append(" && tar -xzf ")
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

            val chmodCommand = "chmod -R 700 ${shellQuote(dataDir.absolutePath)}"
            val chmodResult = runAsRoot(chmodCommand)
            if (chmodResult.exitCode != 0) {
                return BackupResult(
                    success = false,
                    path = backupFile.absolutePath,
                    filename = backupFile.name,
                    exitCode = chmodResult.exitCode,
                    command = "$command && $chmodCommand",
                    output = chmodResult.output,
                    message = "restore completed but chmod failed: exit=${chmodResult.exitCode}; ${chmodResult.output.ifBlank { "no output" }}",
                )
            }

            BackupResult(
                success = true,
                path = backupFile.absolutePath,
                filename = backupFile.name,
                checksum = sha256(backupFile),
                command = command,
                output = result.output,
                message = "restore completed for $packageName",
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
        process.inputStream.use { input ->
            input.copyTo(output)
        }
        return RootCommandResult(
            exitCode = process.waitFor(),
            output = output.toString().trim(),
        )
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\\''")}'"
    }

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

data class RootCommandResult(
    val exitCode: Int,
    val output: String,
)

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
