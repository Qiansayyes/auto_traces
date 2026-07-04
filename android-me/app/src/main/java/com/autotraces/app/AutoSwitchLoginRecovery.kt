package com.autotraces.app

import android.content.Context
import com.autotraces.app.autoswitch.AutoSwitchBundleStore
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 登录保障失败后：抑制涨粉/发作品/引流调度，但持续周期重试登录保障与自动登录。
 */
class AutoSwitchLoginRecovery(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val traceExecutor: TraceExecutor,
    private val onRecoverySuccess: () -> Unit,
    private val isBusyWithExclusiveWork: () -> Boolean,
) {
    companion object {
        private const val RECOVERY_INTERVAL_MS = 60_000L
        private const val BUSY_POLL_MS = 5_000L
        private const val AUTO_LOGIN_ATTEMPTS = 3
    }

    private var recoveryJob: Job? = null

    fun activate(alias: String, slotKey: String, reason: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        PrefsStore.activateAutoSwitchTikTokLoginHold(slotKey, reason)
        PrefsStore.saveLocalStatus("paused")
        val intervalMin = RECOVERY_INTERVAL_MS / 60_000L
        LogCenter.op(
            alias,
            "【自动切号】登录保障未通过：已暂停涨粉/发作品/引流调度；持续重试登录，约每 ${intervalMin} 分钟一轮（$reason）",
        )
        schedule(alias, slotKey.trim(), reason)
    }

    fun clearForAccountTransition(reason: String, prevSlotKey: String? = null, newSlotKey: String? = null) {
        recoveryJob?.cancel()
        recoveryJob = null
        if (PrefsStore.isAutoSwitchLoginRecoveryActive() || PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
            PrefsStore.clearAutoSwitchTikTokLoginHold()
        }
        PrefsStore.clearPublishPostState()
        PrefsStore.clearMutualClickState()
        PrefsStore.setGrowthContinueFromPublishHome(false)
        traceExecutor.clearAccountStatusReloginState()
        PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() }?.let { alias ->
            val slotNote = when {
                !prevSlotKey.isNullOrBlank() && !newSlotKey.isNullOrBlank() ->
                    " $prevSlotKey→$newSlotKey"
                else -> ""
            }
            LogCenter.op(alias, "【自动切号】账号切换清理脏状态：$reason$slotNote")
        }
    }

    fun ensureRecoveryLoopRunningIfNeeded(reason: String) {
        if (!PrefsStore.isAutoSwitchLoginRecoveryActive()) return
        if (recoveryJob?.isActive == true) return
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isBlank()) return
        val sk = PrefsStore.getAutoSwitchLastSlotKey().orEmpty()
        val holdReason = PrefsStore.autoSwitchTikTokLoginHoldReason().orEmpty().ifBlank { reason }
        schedule(alias, sk, holdReason)
    }

    private fun schedule(alias: String, slotKey: String, reason: String) {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            var firstAttempt = true
            while (isActive) {
                if (!PrefsStore.isAutoSwitchAutonomousMode()) return@launch
                if (PrefsStore.isHardStopRequested()) return@launch
                if (!PrefsStore.isAutoSwitchLoginRecoveryActive()) return@launch
                while (isBusyWithExclusiveWork()) {
                    delay(BUSY_POLL_MS)
                    if (!PrefsStore.isAutoSwitchLoginRecoveryActive()) return@launch
                }
                if (!firstAttempt) {
                    delay(RECOVERY_INTERVAL_MS)
                }
                firstAttempt = false
                if (!PrefsStore.isAutoSwitchLoginRecoveryActive()) return@launch
                LogCenter.op(alias, "【自动切号】登录保障恢复重试（来源=$reason）")
                if (runLoginRecoveryOnce(alias)) {
                    PrefsStore.clearAutoSwitchTikTokLoginHold()
                    recoveryJob = null
                    PrefsStore.saveLocalStatus("running")
                    LogCenter.op(alias, "【自动切号】登录保障恢复成功，可继续涨粉/发作品")
                    onRecoverySuccess()
                    return@launch
                }
                PrefsStore.activateAutoSwitchTikTokLoginHold(slotKey, reason)
                LogCenter.op(
                    alias,
                    "【自动切号】登录保障恢复仍未通过，${RECOVERY_INTERVAL_MS / 60_000L} 分钟后再次重试登录",
                )
            }
        }
    }

    private suspend fun runLoginRecoveryOnce(alias: String): Boolean {
        when (traceExecutor.runLoginSafeguardMeasure(appContext, "登录保障恢复")) {
            LoginSafeguardOutcome.Ready -> return true
            LoginSafeguardOutcome.Failed -> return false
            LoginSafeguardOutcome.RequiresAutoLogin -> Unit
        }
        val targetUser = PrefsStore.getLogContextUsername()?.trim()?.removePrefix("@").orEmpty()
        if (targetUser.isEmpty()) return false
        val bundle = AutoSwitchBundleStore.get()
        val creds = bundle?.findAccount(targetUser)?.toAssignedAccountResponse()
            ?: AutoSwitchBundleHolder.resolveAssignedAccount(preferredUsername = targetUser)
        if (creds == null || creds.code != 0) return false
        repeat(AUTO_LOGIN_ATTEMPTS) { attempt ->
            if (PrefsStore.isHardStopRequested()) return false
            if (attempt > 0) {
                val waitMs = Random.nextLong(15_000L, 30_000L)
                delay(waitMs)
            }
            val loginOk = traceExecutor.runAutoLoginAfterCutSwitch(
                ctx = appContext,
                credentials = TikTokAutoLoginCredentials(
                    username = creds.username.orEmpty(),
                    email = creds.email,
                    password = creds.password,
                    apiUrl = creds.api,
                ),
                resumeFromLoginEntryScreen = true,
                flowKind = AutoLoginFlow.Default,
            )
            if (loginOk) return true
        }
        return false
    }
}
