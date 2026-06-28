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

    fun backupArchiveContainsUsername(backupName: String, username: String): Boolean =
        backupArchiveMatchesUsername(backupName, username)

    /** 与 [buildTikTokBackupArchiveName] 一致的前缀：{alias}_{username}_{deviceId}_ */
    fun buildBackupFilenamePrefix(alias: String, username: String, deviceId: String): String? {
        val userToken = sanitizeUsernameForBackupFilename(username)
        if (userToken.isEmpty()) return null
        val aliasToken = sanitizePathToken(alias, "alias")
        val devToken = sanitizePathToken(deviceId, "device")
        return "${aliasToken}_${userToken}_${devToken}_"
    }

    /** 按 alias+username+deviceId 前缀精确匹配，避免短 username 误伤（如 test ⊂ my_test_account）。 */
    fun backupArchiveMatchesUsername(
        backupName: String,
        username: String,
        alias: String = "",
        deviceId: String = "",
    ): Boolean {
        val prefix = buildBackupFilenamePrefix(alias, username, deviceId) ?: return false
        val base = backupName
            .removeSuffix(".tar.gz")
            .removeSuffix(".TAR.GZ")
            .lowercase(Locale.US)
        return base.startsWith(prefix.lowercase(Locale.US))
    }

    /** 统一 backups 目录下该 username（同 alias+device）的已有 .tar.gz（不含 keepAbsolutePath）。 */
    fun listUsernameBackupArchives(
        context: Context,
        username: String,
        alias: String,
        deviceId: String,
        keepAbsolutePath: String = "",
    ): List<File> {
        if (buildBackupFilenamePrefix(alias, username, deviceId) == null) return emptyList()
        val root = ensureBackupsDir(context)
        val keepCanon = keepAbsolutePath.trim().takeIf { it.isNotEmpty() }?.let { path ->
            runCatching { File(path).canonicalFile.absolutePath }.getOrNull()
        }.orEmpty()
        return root.listFiles()?.filter { file ->
            file.isFile &&
                file.name.endsWith(".tar.gz", ignoreCase = true) &&
                backupArchiveMatchesUsername(file.name, username, alias, deviceId) &&
                runCatching { file.canonicalFile.absolutePath }.getOrNull() != keepCanon
        }?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    fun hasExistingUsernameBackup(context: Context, username: String, alias: String, deviceId: String): Boolean =
        listUsernameBackupArchives(context, username, alias, deviceId).isNotEmpty()

    data class ResolvedBackupArchive(
        val fileName: String,
        val absolutePath: String,
    )

    /**
     * 还原前解析本机可用备份：云端指定名 / payload 路径 → 同账号最新本地包 → legacy 包。
     */
    fun resolveRestoreBackupArchive(
        context: Context,
        backupName: String,
        backupPathHint: String,
        username: String,
        alias: String,
        deviceId: String,
    ): ResolvedBackupArchive? {
        val explicitPaths = buildList {
            backupPathHint.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            val name = backupName.trim()
            if (name.isNotEmpty()) {
                add(File(ensureBackupsDir(context), name).absolutePath)
                add(File(name).absolutePath)
            }
        }.distinct()
        for (path in explicitPaths) {
            val file = File(path)
            if (file.isFile && file.canRead()) {
                return ResolvedBackupArchive(file.name, file.absolutePath)
            }
        }
        val user = username.trim().removePrefix("@")
        if (user.isEmpty()) return null
        val local = listUsernameBackupArchives(context, user, alias, deviceId).firstOrNull()
            ?: listLegacyBackupsForAccount(context, user, alias).firstOrNull()
        return local?.let { ResolvedBackupArchive(it.name, it.absolutePath) }
    }

    /**
     * 同 alias+username 的旧备份（不限 deviceId 段，兼容换机后文件名里仍是旧 device_id 的包）。
     */
    fun listLegacyBackupsForAccount(
        context: Context,
        username: String,
        alias: String,
        keepAbsolutePath: String = "",
    ): List<File> {
        val userToken = sanitizeUsernameForBackupFilename(username)
        if (userToken.isEmpty()) return emptyList()
        val aliasToken = sanitizePathToken(alias, "alias").lowercase(Locale.US)
        val prefix = "${aliasToken}_${userToken}_"
        val root = ensureBackupsDir(context)
        val keepCanon = keepAbsolutePath.trim().takeIf { it.isNotEmpty() }?.let { path ->
            runCatching { File(path).canonicalFile.absolutePath }.getOrNull()
        }.orEmpty()
        return root.listFiles()?.filter { file ->
            file.isFile &&
                file.name.endsWith(".tar.gz", ignoreCase = true) &&
                file.name.lowercase(Locale.US).startsWith(prefix) &&
                runCatching { file.canonicalFile.absolutePath }.getOrNull() != keepCanon
        }?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    data class PurgeLegacyBackupsResult(
        val deleted: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
    )

    /**
     * 备份成功后：删除 backups 目录内同 alias+username 的旧包，仅保留 [keepAbsolutePath]。
     * [deleteFile] 默认 Java [File.delete]；设备端 tar 经 root 写入时建议改用 `su rm -f`。
     */
    fun purgeLegacyUsernameBackups(
        context: Context,
        username: String,
        alias: String,
        deviceId: String,
        keepAbsolutePath: String,
        deleteFile: (absolutePath: String) -> Boolean = { path -> File(path).delete() },
    ): PurgeLegacyBackupsResult {
        val files = listLegacyBackupsForAccount(context, username, alias, keepAbsolutePath)
            .ifEmpty { listUsernameBackupArchives(context, username, alias, deviceId, keepAbsolutePath) }
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        files.forEach { file ->
            val path = runCatching { file.canonicalFile.absolutePath }.getOrNull() ?: file.absolutePath
            if (deleteFile(path)) {
                deleted.add(path)
            } else {
                failed.add(path)
            }
        }
        return PurgeLegacyBackupsResult(deleted = deleted, failed = failed)
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
