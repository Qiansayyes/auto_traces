package com.autotraces.app.backup

import android.content.Context
import android.util.Log
import com.autotraces.app.util.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 通过 Root 对**指定包**的 `/data/data/<包名>` 做备份/恢复。
 *
 * - **包名**由调用方传入（与远控 `package_name`、后端下发一致）；空则默认 [Config.TIKTOK_PACKAGE_NAME]。
 * - **备份**：`am force-stop` 目标包 → [DeviceBackupShell.tiktokStyleDataTarCommand] 写入 [UnifiedBackupPaths] →
 *   若目标包 ≠ 本应用宿主包，则 `pm clear` 目标包（与远控「备份并清理」一致；备份文件在宿主外置目录，不会被 clear 掉）。
 * - **恢复**：目标为宿主包时在本包 `dataDir` 内解压；否则与远控 `tiktok_restore` 相同（解压到 `/data/data` 后 chown / restorecon / chmod）。
 */
class AppDataBackupManager(private val context: Context) {

    fun backupAppData(accountName: String, dataPackageName: String): BackupResult {
        return try {
            val pkg = dataPackageName.trim().ifBlank { Config.TIKTOK_PACKAGE_NAME }
            val hostPkg = context.packageName

            val backupRoot = UnifiedBackupPaths.ensureBackupsDir(context)
            val qBackupDir = DeviceBackupShell.shellQuote(backupRoot.absolutePath)
            val ts = System.currentTimeMillis().toString()
            val filename = UnifiedBackupPaths.buildTikTokBackupArchiveName(
                alias = accountName,
                username = accountName,
                deviceId = "local",
                timestamp = ts,
            ) ?: "backup_${UnifiedBackupPaths.sanitizeUsernameForBackupFilename(accountName)}_${ts}.tar.gz"
            val outFile = File(backupRoot, filename)
            val qOut = DeviceBackupShell.shellQuote(outFile.absolutePath)

            if (outFile.exists()) outFile.delete()

            val archiveCmd = buildString {
                append("am force-stop ")
                append(DeviceBackupShell.shellQuote(pkg))
                append(" && ")
                append(
                    DeviceBackupShell.tiktokStyleDataTarCommand(
                        dataPackageName = pkg,
                        quotedBackupDir = qBackupDir,
                        quotedOutputTarPath = qOut,
                    ),
                )
            }

            val result = runAsRoot(archiveCmd)
            if (result.exitCode != 0 || !outFile.exists()) {
                return BackupResult(
                    success = false,
                    path = outFile.absolutePath,
                    filename = filename,
                    exitCode = result.exitCode,
                    command = archiveCmd,
                    output = result.output,
                    message = "tar backup failed: exit=${result.exitCode}; ${result.output.ifBlank { "no output" }}",
                )
            }

            var fullCommand = archiveCmd
            if (pkg != hostPkg) {
                val clearCmd = "pm clear ${DeviceBackupShell.shellQuote(pkg)}"
                val clearRes = runAsRoot(clearCmd)
                fullCommand = "$archiveCmd ; $clearCmd"
                if (clearRes.exitCode != 0) {
                    return BackupResult(
                        success = false,
                        path = outFile.absolutePath,
                        filename = filename,
                        exitCode = clearRes.exitCode,
                        command = fullCommand,
                        output = clearRes.output,
                        message = "backup tar ok but pm clear failed: exit=${clearRes.exitCode}; ${clearRes.output.ifBlank { "no output" }}",
                    )
                }
            }

            UnifiedBackupPaths.purgeLegacyUsernameBackups(
                context,
                accountName,
                accountName,
                "local",
                outFile.absolutePath,
                deleteFile = { path ->
                    val q = DeviceBackupShell.shellQuote(path)
                    runAsRoot("rm -f $q").exitCode == 0
                },
            )

            BackupResult(
                success = true,
                path = outFile.absolutePath,
                filename = filename,
                checksum = sha256(outFile),
                command = fullCommand,
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

    fun restoreAppData(zipPath: String, dataPackageName: String): BackupResult {
        return try {
            val pkg = dataPackageName.trim().ifBlank { Config.TIKTOK_PACKAGE_NAME }
            val hostPkg = context.packageName
            val backupFile = File(zipPath)
            if (!backupFile.exists()) {
                return BackupResult(
                    success = false,
                    path = zipPath,
                    filename = backupFile.name,
                    message = "backup file not found",
                )
            }

            if (pkg == hostPkg) {
                return restoreHostSelf(backupFile, pkg)
            }
            return restoreThirdPartyApp(backupFile, pkg)
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

    private fun restoreHostSelf(backupFile: File, pkg: String): BackupResult {
        val dataDir = File(context.applicationInfo.dataDir)
        val command = buildString {
            append("am force-stop ")
            append(DeviceBackupShell.shellQuote(pkg))
            append(" && cd ")
            append(DeviceBackupShell.shellQuote(dataDir.absolutePath))
            append(" && tar -xzf ")
            append(DeviceBackupShell.shellQuote(backupFile.absolutePath))
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

        val chmodCommand = "chmod -R 700 ${DeviceBackupShell.shellQuote(dataDir.absolutePath)}"
        val chmodResult = runAsRoot(chmodCommand)
        if (chmodResult.exitCode != 0) {
            return BackupResult(
                success = false,
                path = backupFile.absolutePath,
                filename = backupFile.name,
                exitCode = chmodResult.exitCode,
                command = "$command ; $chmodCommand",
                output = chmodResult.output,
                message = "restore completed but chmod failed: exit=${chmodResult.exitCode}; ${chmodResult.output.ifBlank { "no output" }}",
            )
        }

        return BackupResult(
            success = true,
            path = backupFile.absolutePath,
            filename = backupFile.name,
            checksum = sha256(backupFile),
            command = "$command ; $chmodCommand",
            output = result.output,
            message = "restore completed for $pkg",
        )
    }

    private fun restoreThirdPartyApp(backupFile: File, pkg: String): BackupResult {
        val qBak = DeviceBackupShell.shellQuote(backupFile.absolutePath)
        val sourceDir = "/data/data/$pkg"
        val qSource = DeviceBackupShell.shellQuote(sourceDir)

        val stopCmd = "am force-stop ${DeviceBackupShell.shellQuote(pkg)}"
        val stopRes = runAsRoot(stopCmd)
        if (stopRes.exitCode != 0) {
            return BackupResult(
                success = false,
                path = backupFile.absolutePath,
                filename = backupFile.name,
                exitCode = stopRes.exitCode,
                command = stopCmd,
                output = stopRes.output,
                message = "am force-stop failed: exit=${stopRes.exitCode}",
            )
        }

        val tarCmd = "test -f $qBak && tar -xzf $qBak -C /data/data"
        val tarRes = runAsRoot(tarCmd)
        if (tarRes.exitCode != 0) {
            return BackupResult(
                success = false,
                path = backupFile.absolutePath,
                filename = backupFile.name,
                exitCode = tarRes.exitCode,
                command = "$stopCmd ; $tarCmd",
                output = tarRes.output,
                message = "tar restore failed: exit=${tarRes.exitCode}; ${tarRes.output.ifBlank { "no output" }}",
            )
        }

        val statCmd =
            "stat -c '%u:%g' $qSource 2>/dev/null || ls -ldn $qSource 2>/dev/null | tr -s ' ' | cut -d ' ' -f3,4 | tr ' ' ':'"
        val statOut = runAsRootWithOutput(statCmd)
        val uidGid = statOut.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains(":") }

        if (uidGid.isNullOrBlank()) {
            return BackupResult(
                success = false,
                path = backupFile.absolutePath,
                filename = backupFile.name,
                command = "$stopCmd ; $tarCmd ; $statCmd",
                output = statOut,
                message = "restore tar ok but failed to resolve uid:gid for $sourceDir",
            )
        }

        val chownCmd = "chown -R $uidGid $qSource"
        val chownRes = runAsRoot(chownCmd)
        if (chownRes.exitCode != 0) {
            return BackupResult(
                success = false,
                path = backupFile.absolutePath,
                filename = backupFile.name,
                exitCode = chownRes.exitCode,
                command = "$stopCmd ; $tarCmd ; $chownCmd",
                output = chownRes.output,
                message = "chown failed: exit=${chownRes.exitCode}",
            )
        }

        val hasRc = runAsRoot("command -v restorecon >/dev/null 2>&1").exitCode == 0
        val restoreconCmd = "restorecon -RF $qSource"
        val restoreconRes = if (hasRc) runAsRoot(restoreconCmd) else RootCommandResult(0, "")
        if (hasRc && restoreconRes.exitCode != 0) {
            return BackupResult(
                success = false,
                path = backupFile.absolutePath,
                filename = backupFile.name,
                exitCode = restoreconRes.exitCode,
                command = "$stopCmd ; $tarCmd ; $chownCmd ; $restoreconCmd",
                output = restoreconRes.output,
                message = "restorecon failed: exit=${restoreconRes.exitCode}",
            )
        }

        val chmodCmd = "chmod -R 700 $qSource"
        val chmodRes = runAsRoot(chmodCmd)
        if (chmodRes.exitCode != 0) {
            return BackupResult(
                success = false,
                path = backupFile.absolutePath,
                filename = backupFile.name,
                exitCode = chmodRes.exitCode,
                command = "$stopCmd ; $tarCmd ; $chownCmd ; $chmodCmd",
                output = chmodRes.output,
                message = "chmod failed: exit=${chmodRes.exitCode}",
            )
        }

        val joined = buildString {
            append(stopCmd).append(" ; ").append(tarCmd).append(" ; ").append(chownCmd)
            if (hasRc) append(" ; ").append(restoreconCmd)
            append(" ; ").append(chmodCmd)
        }

        return BackupResult(
            success = true,
            path = backupFile.absolutePath,
            filename = backupFile.name,
            checksum = sha256(backupFile),
            command = joined,
            output = tarRes.output,
            message = "restore completed for $pkg",
        )
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

    private fun runAsRootWithOutput(command: String): String {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = ByteArrayOutputStream()
        process.inputStream.use { input -> input.copyTo(output) }
        process.waitFor()
        return output.toString().trim()
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
