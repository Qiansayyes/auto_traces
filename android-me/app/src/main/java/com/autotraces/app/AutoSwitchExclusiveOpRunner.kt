package com.autotraces.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 执行自治调度器入队的独占 OP（发作品 / 引流），含发作品断点续发对账与自动重触发。
 */
class AutoSwitchExclusiveOpRunner(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val traceExecutor: TraceExecutor,
    private val getConfig: () -> AppConfig,
    private val setConfig: (AppConfig) -> Unit,
    private val onPauseGrowth: () -> Unit,
    private val onResumeGrowth: () -> Unit,
    private val onRuntimeMessage: (String) -> Unit,
    private val notifyPublishOutcome: (
        slotKey: String,
        completed: Boolean,
        loginFailed: Boolean,
        accountChanged: Boolean,
    ) -> Unit,
    private val clearPublishInsertionInflight: (slotKey: String) -> Unit,
    private val clearSlotPublishAborted: (slotKey: String) -> Unit,
    private val onLoginSafeguardFailed: (alias: String, slotKey: String, reason: String) -> Unit = { _, _, _ -> },
) {
    private var publishJob: Job? = null
    private var mutualJob: Job? = null

    fun isPublishActive(): Boolean = publishJob?.isActive == true

    fun isMutualActive(): Boolean = mutualJob?.isActive == true

    fun isAnyActive(): Boolean = isPublishActive() || isMutualActive()

    fun cancelAll() {
        publishJob?.cancel()
        publishJob = null
        mutualJob?.cancel()
        mutualJob = null
    }

    /** 进程重启 / stale RUNNING：尝试续发，否则重置为 PENDING 供 tick 重试。 */
    fun handleStalePublishInflight(slotKey: String) {
        if (!tryResumeStalePublish(slotKey)) {
            val alias = PrefsStore.getAlias()?.trim().orEmpty()
            if (alias.isNotBlank()) {
                resetStalePublishInflightForRetry(slotKey, alias)
            }
        }
    }

    fun tryResumeStalePublish(slotKey: String): Boolean {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return false
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive(sk)) return false
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        if (isPublishActive()) return false
        AutoSwitchLocalPlanStore.rehydratePublishContextFromPrefsIfNeeded()
        if (!PrefsStore.hasRecoverableAutoSwitchPublish(sk)) return false
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isBlank()) return false

        clearSlotPublishAborted(sk)
        PrefsStore.setPublishPostPending(true)
        PrefsStore.setPublishPostRequested(true)

        LogCenter.op(
            alias,
            "【自动切号】残留发作品可恢复，触发续发：slot=$sk op=${PrefsStore.getPublishPostTaskOpId()} " +
                "materialReady=${PrefsStore.isPublishPostMaterialReady()} " +
                "resume=${PrefsStore.getPublishPostResumeNextIndex(
                    AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim().orEmpty().ifBlank { alias },
                )}",
        )
        val resumeAccount = AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim().orEmpty().ifBlank { alias }
        launchPublishExecution(
            alias,
            sk,
            isResume = true,
            opId = PrefsStore.getPublishPostTaskOpId().orEmpty(),
            accountUser = resumeAccount,
        )
        return true
    }

    suspend fun runOp(alias: String, deviceId: String, op: DeviceOp) {
        when (op.op_type) {
            "publish_post_start" -> runPublishPostStart(alias, deviceId, op)
            "mutual_click_start", "mutual_click_follow_only_start" -> runMutualTrafficStart(alias, deviceId, op)
            else -> LogCenter.op(alias, "【自动切号】未知独占 OP：${op.op_type}")
        }
    }

    private suspend fun runPublishPostStart(alias: String, deviceId: String, op: DeviceOp) {
        val payload = op.payload
        val opId = op.op_id?.trim().orEmpty()
        val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
        val pkg = payload?.package_name?.trim().orEmpty()
        if (slotKey.isBlank() || opId.isBlank() || pkg.isBlank()) {
            LogCenter.op(alias, "【自动切号】发作品 OP 参数不完整，已跳过")
            notifyPublishOutcome(slotKey, completed = false, loginFailed = false, accountChanged = false)
            return
        }
        if (AutoSwitchLocalPlanStore.isPublishDone(slotKey)) return
        if (publishJob?.isActive == true) {
            LogCenter.op(alias, "【自动切号】发作品执行中，跳过重复 OP")
            return
        }
        if (AutoSwitchLocalPlanStore.isPublishInflight(slotKey)) {
            if (tryResumeStalePublish(slotKey)) return
            resetStalePublishInflightForRetry(slotKey, alias)
        }
        AutoSwitchLocalPlanStore.beginActivePublish(
            slotKey = slotKey,
            opId = opId,
            accountUsername = payload.account_username.orEmpty(),
            packageName = pkg,
            targetCount = payload.publish_count,
        )
        PrefsStore.setAutoSwitchLastSlotKey(slotKey)
        PrefsStore.setPublishPostTaskOpId(opId)
        PrefsStore.setPublishPostPackageName(pkg)
        PrefsStore.setPublishPostTargetCount(payload.publish_count)
        PrefsStore.setPublishPostRequested(true)
        PrefsStore.setPublishPostPending(true)
        val accountUser = payload.account_username.orEmpty().ifBlank { alias }
        val publishCount = payload.publish_count ?: 0
        AutoSwitchLedger.recordPublishDispatch(opId, slotKey, accountUser, publishCount)
        AutoSwitchLocalPlanStore.markInsertionState(slotKey, "publish", PlanStepState.RUNNING)
        launchPublishExecution(alias, slotKey, isResume = false, opId = opId, accountUser = accountUser)
        publishJob?.join()
    }

    private fun launchPublishExecution(
        alias: String,
        slotKey: String,
        isResume: Boolean,
        opId: String = PrefsStore.getPublishPostTaskOpId().orEmpty(),
        accountUser: String = alias,
    ) {
        if (publishJob?.isActive == true) return
        onPauseGrowth()
        publishJob = scope.launch {
            ExecutionScope.enter()
            var completed = false
            try {
                applyBundledConfigForActiveAccount(alias)
                if (isResume) {
                    LogCenter.op(
                        alias,
                        "【自动切号】发作品断点续发 slot=$slotKey 已成功=${
                            PrefsStore.getPublishPostResumeNextIndex(
                                AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim().orEmpty()
                                    .ifBlank { alias },
                            )
                        } 条",
                    )
                } else {
                    LogCenter.op(alias, "【自动切号】发作品开始 slot=$slotKey")
                }
                onRuntimeMessage("自动切号：发作品中")
                val result = runCatching {
                    traceExecutor.executeForUsername("__publish_post__", getConfig())
                }
                val msg = when (val err = result.exceptionOrNull()) {
                    is LoginSafeguardRequiresAutoLoginException -> SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE
                    else -> result.getOrNull().orEmpty()
                }
                completed = result.isSuccess &&
                    (msg.startsWith("发作品：已完成") || msg.contains("已完成"))
                val loginFailed = isPublishLoginFailure(msg)
                if (loginFailed) {
                    onLoginSafeguardFailed(alias, slotKey, msg.take(120))
                }
                val resumeAccount = AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim()
                    ?: PrefsStore.getLogContextUsername()?.trim()
                    ?: accountUser
                val resumeIdx = PrefsStore.getPublishPostResumeNextIndex(resumeAccount)
                val resumable = !completed && resumeIdx > 0
                val successCount = if (completed) {
                    PrefsStore.getAndClearPublishPostLastActualSuccessCount().takeIf { it > 0 } ?: resumeIdx
                } else {
                    resumeIdx
                }
                AutoSwitchLedger.recordPublishOutcome(
                    opId = opId,
                    completed = completed,
                    loginFailed = loginFailed,
                    accountChanged = false,
                    message = msg,
                    slotKey = slotKey,
                    username = resumeAccount,
                    successCount = successCount,
                    resumable = resumable,
                )
                notifyPublishOutcome(slotKey, completed = completed, loginFailed = loginFailed, accountChanged = false)
                releasePublishLocalState(alias, completed = completed)
                if (completed) {
                    LogCenter.op(alias, "【自动切号】发作品完成 slot=$slotKey")
                } else {
                    LogCenter.op(alias, "【自动切号】发作品未完成 slot=$slotKey：$msg")
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                AutoSwitchLedger.recordPublishOutcome(
                    opId = opId,
                    completed = false,
                    loginFailed = false,
                    accountChanged = false,
                    message = err.message.orEmpty(),
                    slotKey = slotKey,
                    username = accountUser,
                )
                notifyPublishOutcome(slotKey, completed = false, loginFailed = false, accountChanged = false)
                releasePublishLocalState(alias, completed = false)
                LogCenter.op(alias, "【自动切号】发作品异常 slot=$slotKey：${err.message}")
            } finally {
                PublishPostGrowthGate.exitExclusivePublishPhase()
                ExecutionScope.exit()
                publishJob = null
                PrefsStore.setAutoSwitchExclusivePreempt(false)
                cleanupOrphanedPublishState(alias, slotKey)
                if (PrefsStore.isAutoSwitchTikTokLoginHoldActive(slotKey)) {
                    PrefsStore.saveLocalStatus("paused")
                    LogCenter.op(alias, "【自动切号】发作品结束：登录保障恢复中，不恢复涨粉")
                } else {
                    onResumeGrowth()
                }
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    cleanupOrphanedPublishState(alias, slotKey)
                }
                if (PrefsStore.isHardStopRequested()) return@invokeOnCompletion
                if (PrefsStore.isAutoSwitchTikTokLoginHoldActive(slotKey)) return@invokeOnCompletion
                if (PrefsStore.getLocalStatus() == "paused") return@invokeOnCompletion
                if (!PrefsStore.isAutoSwitchAutonomousMode()) return@invokeOnCompletion
                val resumeAccount = AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim()
                    ?: PrefsStore.getLogContextUsername()?.trim()
                    ?: alias
                if (
                    PrefsStore.isPublishPostPending() &&
                    PrefsStore.getPublishPostResumeNextIndex(resumeAccount) > 0 &&
                    !isPublishActive()
                ) {
                    LogCenter.op(
                        alias,
                        "【自动切号】发作品断点续发：已成功 ${PrefsStore.getPublishPostResumeNextIndex(resumeAccount)} 条，继续同 OP",
                    )
                    launchPublishExecution(
                        alias,
                        slotKey,
                        isResume = true,
                        opId = PrefsStore.getPublishPostTaskOpId().orEmpty(),
                        accountUser = resumeAccount,
                    )
                }
            }
        }
    }

    /**
     * 未完成但已有续发断点时保留 pending，供同 OP 续作。
     */
    private fun releasePublishLocalState(alias: String, completed: Boolean) {
        val accountKey = AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim()?.takeIf { it.isNotEmpty() }
            ?: PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() }
            ?: alias
        val keepForResume = !completed && PrefsStore.getPublishPostResumeNextIndex(accountKey) > 0
        if (keepForResume) {
            AutoSwitchLocalPlanStore.setActivePublishPendingFlags(requested = true, pending = true)
        } else if (completed) {
            PrefsStore.clearPublishPostState()
            AutoSwitchLocalPlanStore.clearActivePublish()
        }
    }

    private fun resetStalePublishInflightForRetry(slotKey: String, alias: String) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive(sk)) {
            LogCenter.op(alias, "【自动切号】发作品 stale 重置跳过：登录保障恢复中 slot=$sk")
            return
        }
        if (!AutoSwitchLocalPlanStore.isPublishInflight(sk) &&
            AutoSwitchLocalPlanStore.insertionState(sk, "publish") != PlanStepState.FAILED
        ) {
            return
        }
        clearPublishInsertionInflight(sk)
        PrefsStore.clearPublishPostState()
        AutoSwitchLocalPlanStore.resetStalePublishInflight(sk)
        LogCenter.op(alias, "【自动切号】发作品 stale in-flight 已重置为 PENDING slot=$sk")
    }

    /** 协程异常/取消后，防止 pending 或 plan 残留阻塞自治调度。 */
    private fun cleanupOrphanedPublishState(alias: String, slotKey: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (isPublishActive()) return
        val sk = AutoSwitchLocalPlanStore.activePublishSlotKey()?.trim().orEmpty()
            .ifBlank { slotKey.trim() }
            .ifBlank { PrefsStore.getAutoSwitchLastSlotKey().orEmpty() }
        val planInflight = sk.isNotBlank() && AutoSwitchLocalPlanStore.isPublishInflight(sk)
        val hasLocalPublishState = PrefsStore.isAutoSwitchPublishTask() || PrefsStore.isPublishPostPending()
        if (!planInflight && !hasLocalPublishState) return
        val accountKey = AutoSwitchLocalPlanStore.activePublishAccountUsername()?.trim()?.takeIf { it.isNotEmpty() }
            ?: PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() }
            ?: alias
        if (PrefsStore.getPublishPostResumeNextIndex(accountKey) > 0) return
        if (PrefsStore.isPublishPostMaterialReady() && tryResumeStalePublish(sk)) return
        if (planInflight && sk.isNotBlank()) {
            resetStalePublishInflightForRetry(sk, alias)
            return
        }
        if (hasLocalPublishState) {
            LogCenter.op(alias, "【自动切号】清理残留发作品本地状态（协程已结束）")
            PrefsStore.clearPublishPostState()
            if (sk.isNotBlank()) {
                notifyPublishOutcome(sk, completed = false, loginFailed = false, accountChanged = false)
            }
        }
    }

    private suspend fun runMutualTrafficStart(alias: String, deviceId: String, op: DeviceOp) {
        val payload = op.payload
        val opId = op.op_id?.trim().orEmpty()
        val slotKey = payload?.auto_switch_slot_key?.trim().orEmpty()
        if (slotKey.isBlank() || opId.isBlank()) {
            AutoSwitchLocalPlanStore.notifyMutualOutcome(slotKey, completed = false)
            return
        }
        if (AutoSwitchLocalPlanStore.isMutualDone(slotKey)) return
        if (mutualJob?.isActive == true || isPublishActive()) {
            LogCenter.op(alias, "【自动切号】引流暂缓：发作品/引流仍在执行")
            AutoSwitchLocalPlanStore.markInsertionState(slotKey, "mutual_traffic", PlanStepState.PENDING)
            return
        }
        AutoSwitchLocalPlanStore.beginActiveMutual(
            slotKey = slotKey,
            opId = opId,
            accountUsername = payload.account_username.orEmpty(),
        )
        val followOnly = op.op_type == "mutual_click_follow_only_start"
        PrefsStore.setMutualCommentOnlySession(false)
        PrefsStore.setMutualFollowOnlySession(followOnly)
        PrefsStore.setMutualClickRequested(true)
        PrefsStore.setMutualClickPending(true)
        val accountUser = payload.account_username.orEmpty().ifBlank { alias }
        AutoSwitchLedger.recordMutualDispatch(opId, op.op_type, slotKey, accountUser)
        AutoSwitchLocalPlanStore.markInsertionState(slotKey, "mutual_traffic", PlanStepState.RUNNING)
        onPauseGrowth()
        mutualJob = scope.launch {
            ExecutionScope.enter()
            var completed = false
            try {
                applyBundledConfigForActiveAccount(alias)
                LogCenter.op(alias, "【自动切号】引流互点开始 slot=$slotKey")
                onRuntimeMessage("自动切号：引流互点中")
                val result = runCatching {
                    traceExecutor.executeForUsername("__mutual_switch__", getConfig())
                }
                val msg = result.getOrNull().orEmpty()
                completed = result.isSuccess && !msg.startsWith("失败：") && !msg.contains("异常")
                if (completed) {
                    LogCenter.op(alias, "【自动切号】引流互点完成 slot=$slotKey")
                } else {
                    LogCenter.op(alias, "【自动切号】引流互点未完成 slot=$slotKey：$msg")
                }
                AutoSwitchLedger.recordMutualOutcome(
                    opId = opId,
                    completed = completed,
                    slotKey = slotKey,
                    username = accountUser,
                    message = msg,
                )
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                AutoSwitchLedger.recordMutualOutcome(
                    opId = opId,
                    completed = false,
                    slotKey = slotKey,
                    username = accountUser,
                    message = err.message.orEmpty(),
                )
                LogCenter.op(alias, "【自动切号】引流互点异常 slot=$slotKey：${err.message}")
            } finally {
                AutoSwitchLocalPlanStore.notifyMutualOutcome(slotKey, completed = completed)
                if (!completed && AutoSwitchLocalPlanStore.insertionState(slotKey, "mutual_traffic") == PlanStepState.PENDING) {
                    val attempt = AutoSwitchLocalPlanStore.insertionAttempts(slotKey, "mutual_traffic")
                    val max = AutoSwitchLocalPlanStore.maxInsertionAttempts("mutual_traffic")
                    LogCenter.op(alias, "【自动切号】引流未完成，本时段稍后重试 slot=$slotKey（$attempt/$max）")
                }
                PrefsStore.setMutualClickRequested(false)
                PrefsStore.setMutualClickPending(false)
                ExecutionScope.exit()
                mutualJob = null
                PrefsStore.setAutoSwitchExclusivePreempt(false)
                onResumeGrowth()
            }
        }
        mutualJob?.join()
    }

    private suspend fun applyBundledConfigForActiveAccount(alias: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val bundle = com.autotraces.app.autoswitch.AutoSwitchBundleStore.get() ?: return
        val logUser = PrefsStore.getLogContextUsername()
        val bundled = bundle.resolveBundledConfigForUsername(logUser)
        bundled?.config?.let { setConfig(it) }
    }

    private fun isPublishLoginFailure(msg: String): Boolean {
        val m = msg.trim()
        if (m.isEmpty()) return false
        return m == SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE ||
            m.contains("登录保障") ||
            m.contains("掉登录") ||
            (m.contains("自动登录") && m.contains("失败"))
    }
}
