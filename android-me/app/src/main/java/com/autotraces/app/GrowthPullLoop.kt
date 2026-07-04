package com.autotraces.app

import android.content.Context
import com.autotraces.app.autoswitch.AutoSwitchBundleStore
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 自治对齐后的涨粉拉取循环：仅由 [AutoSwitchLocalScheduler] 对齐回调触发。
 */
class GrowthPullLoop(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val traceExecutor: TraceExecutor,
    private val scheduler: AutoSwitchLocalScheduler,
    private val getConfig: () -> AppConfig,
    private val setConfig: (AppConfig) -> Unit,
    private val onRuntimeMessage: (String) -> Unit,
) {
    private val pendingUsernames = mutableListOf<String>()
    private var pullLoopJob: Job? = null

    @Volatile
    private var inPullUsernameRestSleep: Boolean = false

    @Volatile
    private var forceImmediatePull: Boolean = false

    fun isPullLoopActive(): Boolean = pullLoopJob?.isActive == true

    fun isInRestSleep(): Boolean = inPullUsernameRestSleep

    fun stop() {
        pullLoopJob?.cancel()
        pullLoopJob = null
        pendingUsernames.clear()
    }

    /** 切号/独占操作前暂停涨粉拉取。 */
    fun pauseForExclusiveOp(reason: String = "独占操作") {
        pullLoopJob?.cancel()
        pullLoopJob = null
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotBlank()) {
            LogCenter.op(alias, "【涨粉】$reason：已暂停拉取循环")
        }
    }

    /**
     * 自治期：环境已对齐且非独占时恢复涨粉拉取。
     * 由调度器 [onSlotAligned]、切号完成等自治路径触发。
     */
    fun maybeStartInAutoSwitchIdle(reason: String = "自治空档") {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PrefsStore.getAutoSwitchMode() == "exiting") return
        if (PublishPostGrowthGate.isBlockingGrowth()) return
        if (PrefsStore.isHardStopRequested()) return
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
            PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() }?.let { alias ->
                val detail = PrefsStore.autoSwitchTikTokLoginHoldReason().orEmpty()
                val remain = PrefsStore.autoSwitchTikTokLoginHoldRemainingMinutes()
                LogCenter.op(
                    alias,
                    "【自动切号】登录保障恢复中（${if (detail.isNotEmpty()) "$detail，" else ""}暂停涨粉/发作品/引流调度）",
                )
            }
            return
        }
        releaseExclusivePreemptIfIdle(reason)

        if (PrefsStore.getLocalStatus() != "running" &&
            !PrefsStore.isAutoSwitchExclusivePreempt() &&
            scheduler.shouldAllowGrowthBaseline()
        ) {
            PrefsStore.saveLocalStatus("running")
            onRuntimeMessage("自动切号自治：$reason")
        }
        if (PrefsStore.getLocalStatus() != "running") return
        if (!scheduler.shouldAllowGrowthBaseline()) {
            scheduler.growthBaselineBlockReason()?.let { block ->
                PrefsStore.getAlias()?.let { LogCenter.op(it, "【自动切号】$block，暂不恢复涨粉") }
            }
            return
        }
        if (pullLoopJob?.isActive == true) return
        if (ExecutionScope.isActive()) return
        if (!canStartPullLoopNow()) return

        PrefsStore.getAlias()?.let { LogCenter.op(it, "【自动切号】$reason，恢复涨粉拉取") }
        startPullLoop()
    }

    private fun releaseExclusivePreemptIfIdle(reason: String) {
        if (!PrefsStore.isAutoSwitchExclusivePreempt()) return
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
            PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() }?.let { alias ->
                val detail = PrefsStore.autoSwitchTikTokLoginHoldReason().orEmpty()
                LogCenter.op(
                    alias,
                    "【自动切号】$reason：登录保障恢复中${if (detail.isNotEmpty()) "（$detail）" else ""}，不恢复涨粉",
                )
            }
            return
        }
        if (!scheduler.shouldAllowGrowthBaseline()) return
        PrefsStore.setAutoSwitchExclusivePreempt(false)
        PrefsStore.getAlias()?.let {
            LogCenter.op(it, "【自动切号】$reason：独占标记已清除，可恢复涨粉")
        }
    }

    private fun canStartPullLoopNow(): Boolean {
        if (PrefsStore.isAutoSwitchAutonomousMode() && PrefsStore.isAutoSwitchTikTokLoginHoldActive()) {
            return false
        }
        if (PrefsStore.isAutoSwitchAutonomousMode() && !scheduler.shouldAllowGrowthBaseline()) {
            return false
        }
        if (PrefsStore.isAutoSwitchExclusivePreempt()) return false
        if (PublishPostGrowthGate.isBlockingGrowth()) return false
        if (ExecutionScope.isActive()) return false
        return true
    }

    private fun shouldAbortPullExecution(): Boolean {
        if (PrefsStore.isHardStopRequested()) return true
        if (PrefsStore.getAutoSwitchMode() == "exiting") return true
        if (PrefsStore.isAutoSwitchExclusivePreempt()) return true
        if (PrefsStore.isAutoSwitchAutonomousMode() && PrefsStore.isAutoSwitchTikTokLoginHoldActive()) return true
        if (PrefsStore.getLocalStatus() != "running") return true
        if (PrefsStore.isAutoSwitchAutonomousMode() && !scheduler.shouldAllowGrowthBaseline()) return true
        return false
    }

    private fun isLegacyCloudNotStartedPullMsg(msg: String?): Boolean =
        msg?.contains("设备未启动") == true || msg?.contains("一键启动") == true

    /** 自治涨粉：本机调度为准；忽略云端陈旧「未启动」门闩并重试一次。 */
    private suspend fun pullUsernameForGrowth(
        deviceId: String,
        alias: String,
        pullN: Int,
        runMode: String,
        accountForPull: String?,
    ): PullUsernameResponse {
        var response = ApiService.pullUsername(deviceId, alias, pullN, runMode, accountForPull)
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return response
        if (response.code == 0 || !isLegacyCloudNotStartedPullMsg(response.msg)) return response
        LogCenter.op(alias, "自治涨粉：忽略云端「未启动」陈旧提示，同步 running 后重试拉取")
        runCatching { ApiService.syncStatus(deviceId, alias, "running") }
        response = ApiService.pullUsername(deviceId, alias, pullN, runMode, accountForPull)
        if (isLegacyCloudNotStartedPullMsg(response.msg)) {
            LogCenter.op(alias, "自治涨粉：服务端仍返回「未启动」，本机继续自治调度（不视为致命错误）")
        }
        return response
    }

    private fun growthPullStatusMessage(response: PullUsernameResponse): String {
        val raw = response.msg?.trim().orEmpty()
        if (raw.isEmpty()) return "暂无待执行用户名"
        if (PrefsStore.isAutoSwitchAutonomousMode() && isLegacyCloudNotStartedPullMsg(raw)) {
            return "自治涨粉：云端状态滞后，本机调度继续"
        }
        return raw
    }

    private fun startPullLoop() {
        if (pullLoopJob?.isActive == true) return
        if (!canStartPullLoopNow()) {
            val alias = PrefsStore.getAlias()
            if (alias != null) {
                val reason = scheduler.growthBaselineBlockReason() ?: "独占任务"
                LogCenter.op(alias, "涨粉拉取循环暂缓：$reason")
            }
            return
        }

        pullLoopJob = scope.launch {
            while (isActive) {
                if (shouldAbortPullExecution()) {
                    delay(2_000)
                    continue
                }

                val deviceId = PrefsStore.getDeviceId() ?: break
                val alias = PrefsStore.getAlias() ?: break

                if (pendingUsernames.isEmpty()) {
                    reportOnlineBeforePull(alias, deviceId)
                    fetchUsernamesOnce(alias, deviceId)
                }

                if (shouldAbortPullExecution()) {
                    delay(2_000)
                    continue
                }

                val username = pendingUsernames.firstOrNull()
                if (username != null) {
                    pendingUsernames.removeAt(0)
                    LogCenter.op(alias, "执行：$username（余 ${pendingUsernames.size}）")
                    handleUsername(alias, deviceId, username)
                } else {
                    onRuntimeMessage("暂无待执行用户名")
                }

                if (shouldAbortPullExecution()) {
                    delay(2_000)
                    continue
                }

                if (pendingUsernames.size <= 1) {
                    reportOnlineBeforePull(alias, deviceId)
                    fetchUsernamesOnce(alias, deviceId)
                }

                if (pendingUsernames.isNotEmpty()) {
                    continue
                }

                val interval = Random.nextLong(30_000L, 60_000L)
                if (forceImmediatePull) {
                    forceImmediatePull = false
                } else {
                    onRuntimeMessage("等待用户名拉取")
                    if (!delayUnlessHardStop(interval)) {
                        LogCenter.op(alias, "硬停：已中断拉取等待")
                    }
                }
            }
        }
    }

    private suspend fun reportOnlineBeforePull(alias: String, deviceId: String) {
        if (shouldAbortPullExecution()) return
        runCatching {
            ApiService.syncStatus(deviceId, alias, "running")
        }.onFailure { err ->
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                LogCenter.op(alias, "自治涨粉：拉取前同步 running 失败（不阻断本机拉取）：${err.message}")
            } else {
                LogCenter.op(alias, "拉取前同步 running 失败：${err.message}")
            }
        }
    }

    private suspend fun fetchUsernamesOnce(alias: String, deviceId: String) {
        try {
            if (!ensureGrowthConfig(alias)) return

            val pullN = getConfig().pull_batch_size.let { if (it < 1) 5 else it }.coerceIn(1, 100)
            val accountForPull = PrefsStore.getLogContextUsername()
            val runMode = getConfig().run_mode.ifBlank { ServerRunMode.GROWTH }
            var response = pullUsernameForGrowth(deviceId, alias, pullN, runMode, accountForPull)

            if (response.code == 2 && getConfig().no_rest) {
                LogCenter.op(alias, "不休息模式：忽略云端休息，重新拉取")
                response = pullUsernameForGrowth(deviceId, alias, pullN, runMode, accountForPull)
                if (response.code == 2) {
                    forceImmediatePull = true
                    return
                }
            }

            if (response.code == 2) {
                val minutes = (response.rest_minutes ?: 0).coerceAtLeast(1)
                LogCenter.op(alias, "云端休息 ${minutes} 分钟，涨粉暂停")
                onRuntimeMessage("休息中 ${minutes} 分钟")
                inPullUsernameRestSleep = true
                try {
                    val restMs = minutes.toLong() * 60_000L
                    if (!delayUnlessHardStop(restMs)) {
                        LogCenter.op(alias, "硬停：已中断云端休息")
                    }
                } finally {
                    inPullUsernameRestSleep = false
                }
                LogCenter.op(alias, "休息结束，继续涨粉")
                forceImmediatePull = true
                return
            }

            val incoming = response.usernames.orEmpty()
            if (response.code == 0 && incoming.isNotEmpty()) {
                val pendingFromBatch = PrefsStore.filterPendingFromLastBatch(incoming)
                PrefsStore.savePulledBatch(incoming)
                pendingUsernames.addAll(pendingFromBatch)
                LogCenter.op(
                    alias,
                    "已拉到 ${pendingFromBatch.size} 个待执行用户（本批 ${incoming.size} 个，队列共 ${pendingUsernames.size} 个）",
                )
            } else {
                val statusMsg = growthPullStatusMessage(response)
                LogCenter.op(
                    alias,
                    statusMsg.takeIf { it.isNotBlank() } ?: "暂时没有新的用户可执行",
                )
                onRuntimeMessage(statusMsg)
            }
        } catch (err: Exception) {
            if (err is CancellationException) throw err
            LogCenter.op(alias, "拉取用户名失败：${err.message}")
            onRuntimeMessage("用户名拉取失败")
        }
    }

    private suspend fun ensureGrowthConfig(alias: String): Boolean {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        val bundle = AutoSwitchBundleStore.get() ?: run {
            LogCenter.op(alias, "自治涨粉：bundle 为空")
            return false
        }
        val logUser = PrefsStore.getLogContextUsername()
        val bundled = bundle.resolveBundledConfigForUsername(logUser)
        if (bundled?.config != null) {
            setConfig(bundled.config)
            bundled.active_template?.trim()?.takeIf { it.isNotEmpty() }?.let {
                onRuntimeMessage("自治涨粉：模板=$it")
            }
            return true
        }
        LogCenter.op(alias, "自治涨粉：bundle 未找到 ${logUser?.let { "@$it" } ?: "当前账号"} 配置")
        return false
    }

    private suspend fun handleUsername(alias: String, deviceId: String, username: String) {
        if (shouldAbortPullExecution()) {
            LogCenter.op(alias, "涨粉已暂停：跳过 $username")
            return
        }
        if (!ensureGrowthConfig(alias)) return

        ExecutionScope.enter()
        try {
            LogCenter.op(alias, "开始执行任务：$username")
            val result = runCatching {
                traceExecutor.executeForUsername(username, getConfig())
            }
            if (result.isSuccess) {
                val msg = result.getOrNull().orEmpty()
                if (shouldAbortPullExecution()) {
                    LogCenter.op(alias, "涨粉协作退出：$username — $msg")
                    return
                }
                val failed = msg.startsWith("失败：") || msg.contains("执行失败") || msg.contains("异常")
                if (failed) {
                    LogCenter.op(alias, "任务失败：$username — $msg")
                } else {
                    LogCenter.op(alias, "任务完成：$username — $msg")
                    PrefsStore.markUsernameCompleted(username)
                }
                reportWithRetry(alias, deviceId, msg.ifBlank { "完成：$username" })
            } else {
                val err = result.exceptionOrNull()
                if (err is CancellationException) throw err
                LogCenter.op(alias, "任务异常：$username — ${err?.message}")
            }
        } finally {
            ExecutionScope.exit()
        }
    }

    private suspend fun reportWithRetry(alias: String, deviceId: String, message: String) {
        runCatching {
            ApiService.reportResult(deviceId, alias, message, getConfig().run_mode)
        }.onFailure { err ->
            LogCenter.op(alias, "结果上报失败：${err.message}")
        }
    }

    private suspend fun delayUnlessHardStop(ms: Long): Boolean {
        val step = 500L
        var left = ms
        while (left > 0) {
            if (PrefsStore.isHardStopRequested()) return false
            val chunk = minOf(step, left)
            delay(chunk)
            left -= chunk
        }
        return true
    }
}
