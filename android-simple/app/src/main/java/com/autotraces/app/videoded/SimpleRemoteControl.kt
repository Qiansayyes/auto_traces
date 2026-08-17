package com.autotraces.app.videoded

import android.content.Context
import com.autotraces.app.AppCoordinator
import com.autotraces.app.LogCenter
import com.autotraces.app.ManualDeviceControl
import com.autotraces.app.PrefsStore
import com.autotraces.app.WorkerService
import com.autotraces.app.WorkerServiceTracker
import org.json.JSONArray
import org.json.JSONObject

/**
 * PC / USB（经本机 ApiServer）遥控 android-simple 手动区按钮能力。
 * 仅转发至现有 [AppCoordinator] / [WorkerService] 入口，不改业务逻辑。
 */
object SimpleRemoteControl {
    fun buildStatus(): JSONObject {
        val selected = ManualDeviceControl.selectedManualUsername()
        val active = ManualDeviceControl.activeManualUsername()
        val accounts = ManualDeviceControl.getCachedAccounts()
        val accountsArr = JSONArray()
        accounts.forEach { opt ->
            accountsArr.put(
                JSONObject()
                    .put("username", opt.username)
                    .put("hasBackup", opt.backupPath.isNotBlank() || opt.backupName.isNotBlank())
                    .put("firstLoginDone", PrefsStore.isManualFirstLoginDone(opt.username)),
            )
        }
        val runtime = AppCoordinator.runtimeState.value
        val publishOutcome = AppCoordinator.lastManualPublishOutcome()
        val outcomeJson = if (publishOutcome == null) {
            JSONObject.NULL
        } else {
            JSONObject()
                .put("sessionId", publishOutcome.sessionId)
                .put("success", publishOutcome.success)
                .put("message", publishOutcome.message)
                .put("targetCount", publishOutcome.targetCount)
                .put("successCount", publishOutcome.successCount)
                .put("username", publishOutcome.username)
                .put("finishedAtMs", publishOutcome.finishedAtMs)
        }
        return JSONObject()
            .put("success", true)
            .put("manualMode", ManualDeviceControl.ENABLED)
            .put("bound", PrefsStore.isBound())
            .put("deviceId", PrefsStore.getDeviceId().orEmpty())
            .put("alias", PrefsStore.getAlias().orEmpty())
            .put("selectedUsername", selected)
            .put("activeUsername", active)
            .put("accountAligned", AppCoordinator.isManualAccountAligned())
            .put("switchBusy", AppCoordinator.isManualSwitchBusy())
            .put("serviceRunning", WorkerServiceTracker.isRunning)
            .put("hardStopRequested", PrefsStore.isHardStopRequested())
            .put("autoGrowthAfterTasks", PrefsStore.isAutoGrowthAfterTasksEnabled())
            .put("localAutoSwitchEnabled", PrefsStore.isManualLocalAutoSwitchEnabled())
            .put("autoSwitchSlotMinutes", PrefsStore.getManualAutoSwitchSlotMinutes())
            .put("forYouSwipeSec", PrefsStore.getManualForYouSwipeSec())
            .put("publishCount", PrefsStore.getManualPublishCount())
            .put("publishActive", AppCoordinator.isManualPublishPostActive())
            .put("publishSessionId", AppCoordinator.currentManualPublishSessionId().orEmpty())
            .put("publishOutcome", outcomeJson)
            .put(
                "firstLoginDone",
                selected.isNotBlank() && PrefsStore.isManualFirstLoginDone(selected),
            )
            .put("localStatus", runtime.localStatus)
            .put("lastMessage", runtime.lastMessage)
            .put("accounts", accountsArr)
    }

    fun handleAction(context: Context, body: JSONObject): JSONObject {
        val action = body.optString("action", "").trim()
        if (action.isEmpty()) {
            return fail("action_required", "缺少 action")
        }
        val app = context.applicationContext
        return when (action) {
            "status" -> buildStatus()

            "start_service" -> {
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                WorkerService.startResumingAutomation(app)
                AppCoordinator.forceStartRunningNow("PC 遥控启动后台服务")
                AppCoordinator.refreshRuntimeSnapshot()
                ok("已请求启动后台服务")
            }

            "stop_service" -> {
                if (!ManualDeviceControl.ENABLED && PrefsStore.isAutoSwitchAutonomousMode()) {
                    return fail(
                        "cloud_autonomous",
                        "云端自动切号自治中：请先切回本机手动模式，再硬停",
                    )
                }
                AppCoordinator.requestUserGracefulHardStop()
                runCatching { WorkerService.start(app) }
                AppCoordinator.startIfNeeded()
                AppCoordinator.refreshRuntimeSnapshot()
                ok("已请求硬停（当前步骤结束后停下）")
            }

            "refresh_accounts" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                val result = kotlinx.coroutines.runBlocking {
                    AppCoordinator.manualRefreshAssignedAccounts()
                }
                result.fold(
                    onSuccess = { list ->
                        ok("已拉取 ${list.size} 个账号").put("accountsCount", list.size)
                    },
                    onFailure = { err ->
                        fail("refresh_failed", err.message ?: "拉取失败")
                    },
                )
            }

            "select_account", "switch_account" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                val username = body.optString("username", "").trim().removePrefix("@")
                if (username.isBlank()) return fail("username_required", "请提供 username")
                if (AppCoordinator.isManualSwitchBusy()) {
                    return fail("switch_busy", "切号进行中，请稍候")
                }
                val cached = ManualDeviceControl.findCachedAccount(username)
                if (cached == null && ManualDeviceControl.getCachedAccounts().isNotEmpty()) {
                    return fail("account_not_found", "账号不在本机列表中，请先拉取账号")
                }
                AppCoordinator.manualSelectAccount(username)
                val active = ManualDeviceControl.activeManualUsername()
                if (active.isNotBlank() && active.equals(username, ignoreCase = true)) {
                    return ok("已选中当前账号 @$username（无需切号）")
                }
                AppCoordinator.manualSwitchAccountFromSpinner(username)
                ok("已请求切号 → @$username")
            }

            "confirm_switch_done" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (AppCoordinator.isManualSwitchBusy()) {
                    return fail("switch_busy", "切号进行中，请稍候")
                }
                val username = body.optString("username", "").trim().removePrefix("@")
                    .ifBlank { ManualDeviceControl.selectedManualUsername() }
                if (username.isBlank()) return fail("username_required", "请先选择账号")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                AppCoordinator.manualSelectAccount(username)
                AppCoordinator.manualConfirmSwitchDone()
                ok("已确认手动切号完毕：@$username")
            }

            "auto_login" -> {
                val gate = guardManualTask(requireAligned = true)
                if (gate != null) return gate
                val user = ManualDeviceControl.selectedManualUsername()
                if (PrefsStore.isManualFirstLoginDone(user)) {
                    return fail("already_ready", "账号已就绪，无需自动登录")
                }
                AppCoordinator.manualStartAutoLogin("PC 遥控自动登录")
                ok("已请求自动登录 @$user")
            }

            "start_growth" -> {
                val gate = guardManualTask(requireAligned = true, requireFirstLoginDone = true)
                if (gate != null) return gate
                AppCoordinator.manualStartGrowth("PC 遥控开始涨粉")
                ok("已请求开始涨粉")
            }

            "stop_growth" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                AppCoordinator.manualStopGrowth("PC 遥控停止涨粉")
                ok("已请求停止涨粉")
            }

            "for_you_swipe" -> {
                val gate = guardManualTask(requireAligned = true, requireFirstLoginDone = true)
                if (gate != null) return gate
                val raw = if (body.has("sec")) body.optInt("sec") else PrefsStore.getManualForYouSwipeSec()
                val sec = raw.coerceIn(
                    PrefsStore.MIN_MANUAL_FOR_YOU_SWIPE_SEC,
                    PrefsStore.MAX_MANUAL_FOR_YOU_SWIPE_SEC,
                )
                PrefsStore.setManualForYouSwipeSec(sec)
                AppCoordinator.manualStartForYouSwipe(sec)
                ok("已请求 For You 预滑（${sec}s）").put("sec", sec)
            }

            "mutual_click" -> {
                val gate = guardManualTask(requireAligned = true, requireFirstLoginDone = true)
                if (gate != null) return gate
                AppCoordinator.manualStartMutualClick()
                ok("已请求引流互点")
            }

            "publish_post" -> {
                val gate = guardManualTask(requireAligned = true, requireFirstLoginDone = true)
                if (gate != null) return gate
                if (AppCoordinator.isManualPublishPostActuallyRunning()) {
                    return fail("publish_busy", "发作品已在进行中，请等待结束后再试")
                }
                AppCoordinator.clearStaleManualPublishIfIdle()
                if (!WorkerServiceTracker.isRunning) {
                    WorkerService.startResumingAutomation(app)
                    AppCoordinator.forceStartRunningNow("PC 遥控发作品")
                    LogCenter.op(PrefsStore.getAlias(), "PC 遥控发作品：后台服务未启动，已自动拉起")
                }
                val raw = if (body.has("count")) body.optInt("count") else PrefsStore.getManualPublishCount()
                val count = raw.coerceIn(
                    PrefsStore.MIN_MANUAL_PUBLISH_COUNT,
                    PrefsStore.MAX_MANUAL_PUBLISH_COUNT,
                )
                PrefsStore.setManualPublishCount(count)
                val sessionId = AppCoordinator.manualStartPublishPostForRemote(count)
                ok("已请求发作品（${count}条），等待最终结果…")
                    .put("count", count)
                    .put("publishSessionId", sessionId)
                    .put("waitForResult", true)
            }

            "edit_profile" -> {
                val gate = guardManualTask(requireAligned = true, requireFirstLoginDone = true)
                if (gate != null) return gate
                AppCoordinator.manualStartEditBasicProfile()
                ok("已请求修改基本资料")
            }

            "set_auto_growth_after_tasks" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                val enabled = body.optBoolean("enabled", true)
                PrefsStore.setAutoGrowthAfterTasksEnabled(enabled)
                ok(if (enabled) "已开启任务后自动涨粉" else "已关闭任务后自动涨粉")
                    .put("enabled", enabled)
            }

            "enable_local_auto_switch" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                if (ManualDeviceControl.getCachedAccounts().isEmpty()) {
                    return fail("no_accounts", "请先拉取云端账号列表")
                }
                val minutes = if (body.has("minutes")) {
                    body.optInt("minutes")
                } else {
                    PrefsStore.getManualAutoSwitchSlotMinutes()
                }
                AppCoordinator.manualEnableLocalAutoSwitch(minutes)
                ok("已请求开启本机自动切号").put("minutes", minutes)
            }

            "disable_local_auto_switch" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                AppCoordinator.manualDisableLocalAutoSwitch()
                ok("已请求关闭本机自动切号")
            }

            "rebuild_local_auto_switch" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                if (ManualDeviceControl.getCachedAccounts().isEmpty()) {
                    return fail("no_accounts", "请先拉取云端账号列表")
                }
                val minutes = if (body.has("minutes")) {
                    body.optInt("minutes")
                } else {
                    PrefsStore.getManualAutoSwitchSlotMinutes()
                }
                AppCoordinator.manualRebuildLocalAutoSwitchPlan(minutes)
                ok("已请求重建本机切号计划").put("minutes", minutes)
            }

            "switch_to_manual_mode" -> {
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                val result = kotlinx.coroutines.runBlocking {
                    AppCoordinator.switchToManualControlMode()
                }
                result.fold(
                    onSuccess = { tip -> ok(tip) },
                    onFailure = { err -> fail("switch_mode_failed", err.message ?: "切回手动失败") },
                )
            }

            "switch_to_cloud_mode" -> {
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                val names = mutableListOf<String>()
                val arr = body.optJSONArray("participateUsernames")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val u = arr.optString(i, "").trim().removePrefix("@")
                        if (u.isNotEmpty()) names.add(u)
                    }
                } else {
                    ManualDeviceControl.getCachedAccounts().forEach { names.add(it.username) }
                }
                val result = kotlinx.coroutines.runBlocking {
                    AppCoordinator.switchToCloudClientMode(names)
                }
                result.fold(
                    onSuccess = { tip -> ok(tip).put("participateCount", names.size) },
                    onFailure = { err -> fail("switch_mode_failed", err.message ?: "切换云端失败") },
                )
            }

            else -> fail("unknown_action", "未知 action：$action")
        }.also { resp ->
            if (resp.optBoolean("success", false)) {
                LogCenter.op(
                    PrefsStore.getAlias(),
                    "PC 遥控：$action → ${resp.optString("message")}",
                )
            }
        }
    }

    private fun guardManualTask(
        requireAligned: Boolean,
        requireFirstLoginDone: Boolean = false,
    ): JSONObject? {
        if (!ManualDeviceControl.ENABLED) {
            return fail("not_manual_mode", "请先切到本机手动模式")
        }
        if (!PrefsStore.isBound()) {
            return fail("not_bound", "请先绑定设备")
        }
        if (AppCoordinator.isManualSwitchBusy()) {
            return fail("switch_busy", "切号进行中，请稍候")
        }
        if (requireAligned && !AppCoordinator.isManualAccountAligned()) {
            return fail("not_aligned", "请先切号完成（本机当前需与选中账号一致）")
        }
        val user = ManualDeviceControl.selectedManualUsername()
        if (user.isBlank()) {
            return fail("username_required", "请先选择账号")
        }
        if (requireFirstLoginDone && !PrefsStore.isManualFirstLoginDone(user)) {
            return fail(
                "first_login_required",
                "请先切号完成（有备份还原 / 无备份自动登录）后再执行任务",
            )
        }
        return null
    }

    private fun ok(message: String): JSONObject =
        JSONObject().put("success", true).put("message", message)

    private fun fail(error: String, detail: String): JSONObject =
        JSONObject()
            .put("success", false)
            .put("error", error)
            .put("detail", detail)
            .put("message", detail)
}
