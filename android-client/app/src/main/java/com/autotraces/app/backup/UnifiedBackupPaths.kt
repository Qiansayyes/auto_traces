package com.autotraces.app.backup

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 本地备份包与 TikTok 远控备份包共用同一目录，便于持久化与路径一致。
 *
 * 默认：应用外置专属目录下的 `backups/`（通常位于 Android/data/<包名>/files/backups），
 * 一般不会被系统像 /data/local/tmp 那样随意清理。
 */
object UnifiedBackupPaths {

    fun backupsDir(context: Context): File {
        val base = context.getExternalFilesDir(null)
            ?: File(context.filesDir, "external_files_fallback")
        return File(base, "backups")
    }

    fun ensureBackupsDir(context: Context): File =
        backupsDir(context).also { it.mkdirs() }

    /** 备份包名片段：仅保留字母数字与 _-. ，用于文件名强关联 username。 */
    fun sanitizeUsernameForBackupFilename(username: String): String {
        val core = username.trim().removePrefix("@").lowercase(Locale.US)
        if (core.isEmpty()) return ""
        val cleaned = core.map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch == '_' || ch == '-' || ch == '.' -> ch
                else -> '_'
            }
        }.joinToString("")
        return cleaned.trim('_')
    }

    private fun sanitizePathToken(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .ifEmpty { fallback }
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    ch == '_' || ch == '-' || ch == '.' -> ch
                    else -> '_'
                }
            }
            .joinToString("")
        return cleaned.trim('_').ifEmpty { fallback }
    }

    /**
     * TikTok 备份包名：{alias}_{username}_{deviceId}_{yyyyMMdd_HHmmss}.tar.gz
     * username 为必填且必须出现在包名中。
     */
    fun buildTikTokBackupArchiveName(
        alias: String,
        username: String,
        deviceId: String,
        timestamp: String,
    ): String? {
        val userToken = sanitizeUsernameForBackupFilename(username)
        if (userToken.isEmpty()) return null
        val aliasToken = sanitizePathToken(alias, "alias")
        val devToken = sanitizePathToken(deviceId, "device")
        val tsToken = sanitizePathToken(timestamp, "ts")
        return "${aliasToken}_${userToken}_${devToken}_${tsToken}.tar.gz"
    }

    fun backupArchiveContainsUsername(backupName: String, username: String): Boolean {
        val userToken = sanitizeUsernameForBackupFilename(username)
        if (userToken.isEmpty()) return false
        return backupName.lowercase(Locale.US).contains(userToken)
    }

    /**
     * 仅允许删除统一 backups 目录下的 .tar.gz，且不得与本次新包路径相同。
     */
    fun isSafeToDeleteOldBackup(context: Context, candidatePath: String, newBackupPath: String): Boolean {
        val cand = candidatePath.trim()
        if (cand.isEmpty() || cand == newBackupPath) return false
        if (!cand.endsWith(".tar.gz", ignoreCase = true)) return false
        val root = try {
            backupsDir(context).canonicalFile.absolutePath
        } catch (_: Exception) {
            return false
        }
        val canon = try {
            File(cand).canonicalFile.absolutePath
        } catch (_: Exception) {
            return false
        }
        val sep = File.separator
        return canon.startsWith(root + sep)
    }
}
