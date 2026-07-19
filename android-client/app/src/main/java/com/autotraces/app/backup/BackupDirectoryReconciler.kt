package com.autotraces.app.backup

import android.content.Context
import com.autotraces.app.AutoSwitchAccount
import java.io.File
import java.util.Locale

/**
 * 按云端 bundle 账号档案 + 备份包命名规则整理 [UnifiedBackupPaths] 目录：
 * - 每个已分配 username 仅保留 1 个包（本机最新；包名时间戳优先）
 * - 云端 [backup_name] 仅用于缺失告警，不得覆盖保留更新的本地包
 * - 删除同 username 重复包、别名下未分配 username 的包、不符合命名规则的 .tar.gz
 * - 云端 [backup_name] 解析出的 username 与档案 username 不一致时删除该备份包
 * - 云端有 [backup_name] 但本机不存在时记入 [ReconcileResult.missingCloud]（需重新登录备份）
 */
object BackupDirectoryReconciler {

    private val TIMESTAMP_SUFFIX =
        Regex("_\\d{8}_\\d{6}\\.tar\\.gz$", RegexOption.IGNORE_CASE)

    data class AccountBackupPolicy(
        val username: String,
        val cloudBackupName: String = "",
    )

    data class MissingCloudBackup(
        val username: String,
        val cloudBackupName: String,
        /** 云端指定包不在本机时，若保留了较新的本地包则为其文件名 */
        val keptLocalFallbackName: String = "",
    )

    data class ReconcileResult(
        val kept: List<String> = emptyList(),
        val deleted: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val missingCloud: List<MissingCloudBackup> = emptyList(),
        /** backup_name 解析 username 与档案 username 不一致而删除的包 */
        val usernameMismatchDeleted: List<String> = emptyList(),
    )

    fun policiesFromAccounts(accounts: List<AutoSwitchAccount>): List<AccountBackupPolicy> =
        accounts.mapNotNull { acc ->
            val user = acc.username?.trim()?.removePrefix("@").orEmpty()
            if (user.isBlank()) return@mapNotNull null
            val raw = acc.backup_name?.trim().orEmpty()
            val cloudName = when {
                raw.isEmpty() || raw == "-" -> ""
                else -> File(raw).name
            }
            AccountBackupPolicy(username = user, cloudBackupName = cloudName)
        }

    /**
     * @param extraKeepAbsolutePaths 本轮刚生成、尚未写入云端的备份路径，整理时强制保留
     */
    fun reconcile(
        context: Context,
        alias: String,
        deviceId: String,
        policies: List<AccountBackupPolicy>,
        extraKeepAbsolutePaths: Set<String> = emptySet(),
        deleteFile: (absolutePath: String) -> Boolean,
    ): ReconcileResult {
        val root = UnifiedBackupPaths.ensureBackupsDir(context)
        val aliasToken = sanitizeAliasToken(alias)
        if (aliasToken.isEmpty()) {
            return ReconcileResult()
        }
        val policyByUser = policies.associateBy { normalizeUsername(it.username) }
        val assignedTokens = policyByUser.keys.mapNotNull { user ->
            UnifiedBackupPaths.sanitizeUsernameForBackupFilename(user).takeIf { it.isNotEmpty() }
        }.distinct().sortedByDescending { it.length }

        val extraKeepCanon = extraKeepAbsolutePaths.mapNotNull { path ->
            runCatching { File(path.trim()).canonicalFile.absolutePath }.getOrNull()
        }.toSet()

        val allArchives = root.listFiles()?.filter { file ->
            file.isFile && file.name.endsWith(".tar.gz", ignoreCase = true)
        }.orEmpty()

        val kept = mutableSetOf<String>()
        val toDelete = mutableSetOf<String>()
        val missingCloud = mutableListOf<MissingCloudBackup>()
        val usernameMismatchDeleted = mutableListOf<String>()
        val allCandidateUsers = policyByUser.keys.toList()

        // 1) 已分配账号：每个 username 只保留“最新可用”的 1 个包。
        for ((userKey, policy) in policyByUser) {
            val userToken = UnifiedBackupPaths.sanitizeUsernameForBackupFilename(userKey)
            if (userToken.isEmpty()) continue
            val prefix = "${aliasToken}_${userToken}_"
            val cloudName = policy.cloudBackupName.trim().let { if (it == "-") "" else it }

            // backup_name 与档案 username 不一致 → 删除该备份包
            if (cloudName.isNotEmpty()) {
                val parsedFromCloud = UnifiedBackupPaths.parseUsernameFromBackupArchiveName(
                    cloudName,
                    alias,
                    allCandidateUsers,
                )
                if (parsedFromCloud != null &&
                    normalizeUsername(parsedFromCloud) != normalizeUsername(userKey)
                ) {
                    allArchives.filter { file ->
                        file.name.equals(cloudName, ignoreCase = true) ||
                            file.name.equals(File(cloudName).name, ignoreCase = true)
                    }.forEach { file ->
                        val path = file.canonicalOrAbsolute()
                        toDelete.add(path)
                        usernameMismatchDeleted.add(path)
                    }
                    // 仅删 backup_name 指向的错误包，继续按档案 username 处理本机其它合法包
                }
            }

            val userFiles = allArchives.filter { it.name.lowercase(Locale.US).startsWith(prefix) }
            if (userFiles.isEmpty()) {
                if (cloudName.isNotEmpty()) {
                    missingCloud.add(MissingCloudBackup(userKey, cloudName))
                }
                continue
            }

            val validFiles = userFiles.filter { file ->
                matchesNamingRule(file.name, alias, userKey)
            }
            val invalidFiles = userFiles.filter { file -> file !in validFiles.toSet() }
            invalidFiles.forEach { toDelete.add(it.canonicalOrAbsolute()) }

            // 必须保留最新：包名时间戳优先，避免 mtime 相同或 extraKeep 护住旧包
            val keepFile = validFiles.maxWithOrNull(
                compareByDescending<File> { UnifiedBackupPaths.archiveRecencyKey(it) }
                    .thenByDescending { it.name },
            )
            val cloudNameForMatch = cloudName
            if (cloudNameForMatch.isNotEmpty()) {
                val cloudMatchesLocal = validFiles.any { file ->
                    file.name.equals(cloudNameForMatch, ignoreCase = true) ||
                        file.name.equals(File(cloudNameForMatch).name, ignoreCase = true)
                }
                if (!cloudMatchesLocal) {
                    missingCloud.add(
                        MissingCloudBackup(
                            userKey,
                            cloudNameForMatch,
                            keptLocalFallbackName = keepFile?.name.orEmpty(),
                        ),
                    )
                }
            }

            val keepPath = keepFile?.canonicalOrAbsolute()
            if (keepPath != null) {
                kept.add(keepPath)
            }
            validFiles.forEach { file ->
                val path = file.canonicalOrAbsolute()
                // 非最新一律删；extraKeep 不得保留更旧的同账号包
                if (path != keepPath) {
                    toDelete.add(path)
                }
            }
        }

        // 2) 本轮新备份：仅当它不是「已判定删除的旧包」时才强制保留
        extraKeepCanon.forEach { path ->
            if (path !in toDelete) {
                kept.add(path)
            }
        }

        // 3) 未分配 username / 非本别名 / 无法识别：删除
        for (file in allArchives) {
            val path = file.canonicalOrAbsolute()
            if (path in kept || path in toDelete) continue
            val lowerName = file.name.lowercase(Locale.US)
            if (!lowerName.startsWith("${aliasToken}_")) {
                toDelete.add(path)
                continue
            }
            val matchedUser = matchAssignedUsername(lowerName, aliasToken, assignedTokens, policyByUser.keys)
            if (matchedUser == null) {
                toDelete.add(path)
                continue
            }
            if (!matchesNamingRule(file.name, alias, matchedUser)) {
                toDelete.add(path)
            }
            // 已分配但不在步骤 1 出现（policy 重复/username 规范化遗漏）— 仅保留最新
            if (path !in kept) {
                toDelete.add(path)
            }
        }

        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        toDelete.filter { it !in kept }.distinct().forEach { path ->
            if (!isUnderBackupRoot(context, path)) return@forEach
            if (deleteFile(path)) {
                deleted.add(path)
            } else {
                failed.add(path)
            }
        }

        return ReconcileResult(
            kept = kept.sorted(),
            deleted = deleted.sorted(),
            failed = failed.sorted(),
            missingCloud = missingCloud.distinctBy { it.username.lowercase() },
            usernameMismatchDeleted = usernameMismatchDeleted.distinct().sorted(),
        )
    }

    private fun matchAssignedUsername(
        lowerFileName: String,
        aliasToken: String,
        assignedTokens: List<String>,
        assignedUsers: Set<String>,
    ): String? {
        for (token in assignedTokens) {
            if (lowerFileName.startsWith("${aliasToken}_${token}_")) {
                return assignedUsers.firstOrNull { user ->
                    UnifiedBackupPaths.sanitizeUsernameForBackupFilename(user) == token
                }
            }
        }
        return null
    }

    fun matchesNamingRule(fileName: String, alias: String, username: String): Boolean {
        val userToken = UnifiedBackupPaths.sanitizeUsernameForBackupFilename(username)
        val aliasToken = sanitizeAliasToken(alias)
        if (userToken.isEmpty() || aliasToken.isEmpty()) return false
        val lower = fileName.lowercase(Locale.US)
        if (!lower.startsWith("${aliasToken}_${userToken}_")) return false
        if (!TIMESTAMP_SUFFIX.containsMatchIn(lower)) return false
        // alias_user_*_至少一段_*_yyyyMMdd_HHmmss.tar.gz
        val withoutExt = lower.removeSuffix(".tar.gz")
        val afterUser = withoutExt.removePrefix("${aliasToken}_${userToken}_")
        if (afterUser.isEmpty() || !afterUser.contains('_')) return false
        return true
    }

    private fun sanitizeAliasToken(alias: String): String =
        alias.trim()
            .ifEmpty { return "" }
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    ch == '_' || ch == '-' || ch == '.' -> ch
                    else -> '_'
                }
            }
            .joinToString("")
            .trim('_')
            .lowercase(Locale.US)

    private fun normalizeUsername(raw: String): String =
        raw.trim().removePrefix("@").lowercase(Locale.US)

    private fun File.canonicalOrAbsolute(): String =
        runCatching { canonicalFile.absolutePath }.getOrNull() ?: absolutePath

    private fun isUnderBackupRoot(context: Context, absolutePath: String): Boolean {
        val root = runCatching {
            UnifiedBackupPaths.backupsDir(context).canonicalFile.absolutePath
        }.getOrNull() ?: return false
        val canon = runCatching { File(absolutePath).canonicalFile.absolutePath }.getOrNull() ?: return false
        return canon.startsWith(root + File.separator) || canon == root
    }
}
