package com.autotraces.app

import android.content.Context
import com.autotraces.app.backup.AppDataBackupManager
import com.autotraces.app.backup.BackupRepository
import com.autotraces.app.backup.UnifiedBackupPaths
import com.autotraces.app.util.Config

/**
 * 自治切号三步：备份（可选）→ 清理 → 还原或自动登录。
 * 进度由 [AutoSwitchLocalPlanStore] 持久化。
 */
class AutoSwitchCutSwitchExecutor(
    private val appContext: Context,
    private val traceExecutor: TraceExecutor,
) {
    private val backupManager = AppDataBackupManager(appContext)
    private val backupRepository = BackupRepository(appContext)

    suspend fun runNextPendingStep(
        plan: AutoSwitchLocalPlan,
        bundle: AutoSwitchBundle,
        targetUsername: String,
    ): Boolean {
        val targetUser = targetUsername.trim().removePrefix("@")
        if (targetUser.isEmpty()) return false

        val pkg = bundle.run_config?.package_name?.trim().orEmpty().ifBlank { Config.TIKTOK_PACKAGE_NAME }
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isEmpty()) return false

        PrefsStore.setAutoSwitchExclusivePreempt(true)
        try {
            if (!plan.backup_done) {
                return runBackupStep(plan, alias, pkg)
            }
            if (!plan.clear_done) {
                return runClearStep(alias, pkg)
            }
            if (!plan.restore_or_login_done) {
                return runRestoreOrLoginStep(plan, targetUser, bundle, alias, pkg)
            }
            AutoSwitchLocalPlanStore.finishCutSwitchSuccess(targetUser)
            PrefsStore.setLogContextUsername(targetUser)
            return true
        } finally {
            PrefsStore.setAutoSwitchExclusivePreempt(false)
        }
    }

    private suspend fun runBackupStep(plan: AutoSwitchLocalPlan, alias: String, pkg: String): Boolean {
        val owner = plan.environment_owner_username?.trim()?.removePrefix("@").orEmpty()
        if (owner.isEmpty()) {
            AutoSwitchLocalPlanStore.markBackupDone()
            LogCenter.op(alias, "【自动切号】环境归属为空，跳过备份")
            return true
        }
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val filename = UnifiedBackupPaths.buildTikTokBackupArchiveName(
            alias = alias,
            username = owner,
            deviceId = deviceId.ifBlank { "local" },
            timestamp = System.currentTimeMillis().toString(),
        )
        val result = backupManager.backupAppDataTarOnly(
            accountName = owner,
            dataPackageName = pkg,
        )
        if (!result.success) {
            LogCenter.op(alias, "【自动切号】备份失败：${result.message}")
            return false
        }
        AutoSwitchLocalPlanStore.markBackupDone()
        LogCenter.op(alias, "【自动切号】备份完成：@$owner file=${result.filename ?: filename.orEmpty()}")
        return true
    }

    private suspend fun runClearStep(alias: String, pkg: String): Boolean {
        val result = backupManager.clearAppData(pkg)
        if (!result.success) {
            LogCenter.op(alias, "【自动切号】清理失败：${result.message}")
            return false
        }
        AutoSwitchLocalPlanStore.markClearDone()
        LogCenter.op(alias, "【自动切号】TikTok 已清理")
        return true
    }

    private suspend fun runRestoreOrLoginStep(
        plan: AutoSwitchLocalPlan,
        targetUser: String,
        bundle: AutoSwitchBundle,
        alias: String,
        pkg: String,
    ): Boolean {
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val account = bundle.findAccount(targetUser)
        val ownerEmpty = plan.environment_owner_username.isNullOrBlank()

        if (!ownerEmpty) {
            val backupFile = UnifiedBackupPaths.listUsernameBackupArchives(
                context = appContext,
                username = targetUser,
                alias = alias,
                deviceId = deviceId.ifBlank { "local" },
            ).firstOrNull()

            if (backupFile != null && backupFile.length() >= UnifiedBackupPaths.MIN_VALID_BACKUP_FILE_BYTES) {
                val restore = backupRepository.restore(targetUser, backupFile.absolutePath, pkg)
                if (!restore.success) {
                    LogCenter.op(alias, "【自动切号】还原失败：${restore.message}")
                    return false
                }
                LogCenter.op(alias, "【自动切号】已还原 @${targetUser}：${backupFile.name}")
                AutoSwitchLocalPlanStore.markRestoreOrLoginDone()
                AutoSwitchBundleHolder.notifyAccountLoggedIn(targetUser)
                return true
            }
        } else {
            LogCenter.op(alias, "【自动切号】环境归属为空，跳过 tar 还原，直接登录 @$targetUser")
        }

        val creds = account?.toAssignedAccountResponse()
            ?: AutoSwitchBundleHolder.resolveAssignedAccount(preferredUsername = targetUser)
        if (creds == null || creds.code != 0) {
            LogCenter.op(alias, "【自动切号】无本地包且凭证不完整，无法登录 @$targetUser")
            return false
        }

        val loginOk = traceExecutor.runAutoLoginAfterCutSwitch(
            ctx = appContext,
            credentials = TikTokAutoLoginCredentials(
                username = creds.username.orEmpty(),
                email = creds.email,
                password = creds.password,
                apiUrl = creds.api,
            ),
            resumeFromLoginEntryScreen = false,
            flowKind = AutoLoginFlow.CutSwitchAfterRestore,
        )
        if (!loginOk) {
            LogCenter.op(alias, "【自动切号】自动登录失败：@$targetUser")
            return false
        }
        AutoSwitchLocalPlanStore.markRestoreOrLoginDone()
        AutoSwitchBundleHolder.notifyAccountLoggedIn(targetUser)
        LogCenter.op(alias, "【自动切号】自动登录成功：@$targetUser")
        return true
    }
}
