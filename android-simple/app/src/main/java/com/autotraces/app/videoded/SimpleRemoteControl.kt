package com.autotraces.app.videoded

import android.content.Context
import com.autotraces.app.ApiService
import com.autotraces.app.AppCoordinator
import com.autotraces.app.BindingRecovery
import com.autotraces.app.DeviceFingerprint
import com.autotraces.app.LogCenter
import com.autotraces.app.ManualDeviceControl
import com.autotraces.app.PendingActivateRecommend
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
        val accountsArr = buildAccountsJson(ManualDeviceControl.getCachedAccounts())
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
            .put("cloudUsername", PrefsStore.getAuthUsername().orEmpty())
            .put("cloudLoggedIn", !PrefsStore.getAuthToken().isNullOrBlank())
            .put(
                "serverBaseUrl",
                PrefsStore.getServerBaseUrl().orEmpty().ifBlank {
                    runCatching { ApiService.getBaseUrl() }.getOrDefault("")
                },
            )
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
            .put("activateDurationMin", PrefsStore.getManualActivateDurationMin())
            .put("activateDurationHours", PrefsStore.getManualActivateDurationHours())
            .put("heartGrowthRecommendMax", PrefsStore.getPendingActivateHeartGrowthMax())
            .put("recommendedCount", PendingActivateRecommend.recommendedCount())
            .put("recommendCacheLabel", PendingActivateRecommend.cacheAgeLabel())
            .put("batchSessionBusy", AppCoordinator.isBatchAccountSessionBusy())
            .put("activateNextBusy", AppCoordinator.isActivateNextBusy())
            .put("dailyIncubateBusy", AppCoordinator.isDailyIncubateBusy())
            .put("dailyIncubateDurationMin", PrefsStore.getDailyIncubateDurationMin())
            .put("dailyIncubateDurationHours", PrefsStore.getDailyIncubateDurationHours())
            .put("pendingActivateQueue", buildPendingActivateQueueJson())
            .put(
                "nonPendingActivateCount",
                AppCoordinator.listBoundDeviceNonPendingActivateUsernames().size,
            )
            .put("batchPublishAfterMin", PrefsStore.getManualBatchPublishAfterMin())
    }

    fun handleAction(context: Context, body: JSONObject): JSONObject {
        val action = body.optString("action", "").trim()
        if (action.isEmpty()) {
            return fail("action_required", "缺少 action")
        }
        val app = context.applicationContext
        return when (action) {
            "status" -> buildStatus()

            "ensure_usb_ready" -> kotlinx.coroutines.runBlocking {
                ensureUsbReady(app, body)
            }

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

            "set_cloud_auth" -> {
                // 云端登录名：只 trim，不去掉 @（与素材/TikTok 用户名分流）
                val username = body.optString("cloud_username", "").trim()
                    .ifBlank { body.optString("username", "").trim() }
                val password = body.optString("cloud_password", "").trim()
                    .ifBlank { body.optString("password", "") }
                if (username.isBlank() || password.isBlank()) {
                    return fail("credentials_required", "请提供 cloud_username 与 cloud_password")
                }
                val alias = PrefsStore.getAlias().orEmpty()
                PrefsStore.saveAuthCredentials(username, password)
                AppCoordinator.refreshRuntimeSnapshot()
                LogCenter.op(alias.ifBlank { null }, "PC USB：已写入云端账号 @$username（待联网后登录）")
                return JSONObject()
                    .put("success", true)
                    .put("message", "账号密码已写入手机，联网后请在设置中点「登录云端」")
                    .put("cloudUsername", username)
                    .put("cloudLoggedIn", false)
                    .put("credentialsSaved", true)
            }

            "refresh_accounts" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                val result = kotlinx.coroutines.runBlocking {
                    AppCoordinator.manualRefreshAssignedAccounts()
                }
                result.fold(
                    onSuccess = { list ->
                        val accountsArr = buildAccountsJson(list)
                        val pendingCount = PrefsStore.getPendingActivateUsernames().size
                        val rec = PendingActivateRecommend.recommendedCount()
                        ok(
                            "已拉取 ${list.size} 个账号（待活跃 $pendingCount，推荐 $rec，" +
                                "${PendingActivateRecommend.cacheAgeLabel()}）",
                        )
                            .put("accountsCount", list.size)
                            .put("accounts", accountsArr)
                            .put("pendingActivateQueue", buildPendingActivateQueueJson())
                            .put("recommendedCount", rec)
                            .put("heartGrowthRecommendMax", PrefsStore.getPendingActivateHeartGrowthMax())
                            .put("recommendCacheLabel", PendingActivateRecommend.cacheAgeLabel())
                            .put("selectedUsername", ManualDeviceControl.selectedManualUsername())
                            .put("activeUsername", ManualDeviceControl.activeManualUsername())
                    },
                    onFailure = { err ->
                        fail("refresh_failed", err.message ?: "拉取失败")
                    },
                )
            }

            "select_account", "switch_account" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                val username = tiktokUsernameFromBody(body)
                if (username.isEmpty()) return fail("username_required", "请提供 username")
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
                val username = tiktokUsernameFromBody(body, ManualDeviceControl.selectedManualUsername())
                if (username.isEmpty()) return fail("username_required", "请先选择账号")
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
                if (ManualDeviceControl.ENABLED) {
                    return ok("当前已是本机手动模式").put("manualMode", true).put("alreadyManual", true)
                }
                val result = kotlinx.coroutines.runBlocking {
                    AppCoordinator.switchToManualControlMode()
                }
                result.fold(
                    onSuccess = { tip -> ok(tip).put("manualMode", true) },
                    onFailure = { err -> fail("switch_mode_failed", err.message ?: "切回手动失败") },
                )
            }

            "switch_to_cloud_mode" -> {
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                val names = body.optJSONArray("participateUsernames")
                    ?.let { parseUsernameArray(it) }
                    ?: ManualDeviceControl.getCachedAccounts().map { it.username }
                val result = kotlinx.coroutines.runBlocking {
                    AppCoordinator.switchToCloudClientMode(names)
                }
                result.fold(
                    onSuccess = { tip -> ok(tip).put("participateCount", names.size) },
                    onFailure = { err -> fail("switch_mode_failed", err.message ?: "切换云端失败") },
                )
            }

            "enqueue_pending_activate" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                val username = tiktokUsernameFromBody(body, ManualDeviceControl.selectedManualUsername())
                if (username.isEmpty()) return fail("username_required", "请选择账号")
                val added = AppCoordinator.manualEnqueuePendingActivate(username)
                ok(
                    if (added) "已加入待活跃队列：@$username" else "@$username 已在队列中",
                )
                    .put("added", added)
                    .put("pendingActivateQueue", buildPendingActivateQueueJson())
            }

            "set_heart_growth_recommend_max" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                val raw = body.optInt(
                    "max",
                    body.optInt("heartGrowthRecommendMax", PrefsStore.getPendingActivateHeartGrowthMax()),
                )
                val value = raw.coerceIn(
                    PrefsStore.MIN_PENDING_ACTIVATE_HEART_GROWTH_MAX,
                    PrefsStore.MAX_PENDING_ACTIVATE_HEART_GROWTH_MAX,
                )
                PrefsStore.setPendingActivateHeartGrowthMax(value)
                ok("已设置三天总赞增长推荐阈值 ≤$value")
                    .put("heartGrowthRecommendMax", value)
                    .put("recommendedCount", PendingActivateRecommend.recommendedCount())
                    .put("accounts", buildAccountsJson(ManualDeviceControl.getCachedAccounts()))
            }

            "enqueue_recommended_pending_activate" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                if (body.has("max") || body.has("heartGrowthRecommendMax")) {
                    val raw = body.optInt(
                        "max",
                        body.optInt("heartGrowthRecommendMax", PrefsStore.getPendingActivateHeartGrowthMax()),
                    )
                    PrefsStore.setPendingActivateHeartGrowthMax(
                        raw.coerceIn(
                            PrefsStore.MIN_PENDING_ACTIVATE_HEART_GROWTH_MAX,
                            PrefsStore.MAX_PENDING_ACTIVATE_HEART_GROWTH_MAX,
                        ),
                    )
                }
                val added = AppCoordinator.manualEnqueueRecommendedPendingActivate()
                val recTotal = PendingActivateRecommend.recommendedCount()
                val msg = when {
                    added > 0 -> "已将 $added 个推荐账号加入待活跃队列"
                    recTotal == 0 ->
                        "当前没有推荐账号（三天总赞增长均大于 ${PrefsStore.getPendingActivateHeartGrowthMax()}）"
                    else -> "推荐账号都已在队列中（共 $recTotal 个）"
                }
                ok(msg)
                    .put("added", added)
                    .put("recommendedCount", recTotal)
                    .put("heartGrowthRecommendMax", PrefsStore.getPendingActivateHeartGrowthMax())
                    .put("pendingActivateQueue", buildPendingActivateQueueJson())
                    .put("accounts", buildAccountsJson(ManualDeviceControl.getCachedAccounts()))
            }

            "remove_pending_activate" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                val username = tiktokUsernameFromBody(body, ManualDeviceControl.selectedManualUsername())
                if (username.isEmpty()) return fail("username_required", "请选择账号")
                val removed = AppCoordinator.manualRemovePendingActivate(username)
                ok(
                    if (removed) "已移出待活跃队列：@$username" else "@$username 不在队列中",
                )
                    .put("removed", removed)
                    .put("pendingActivateQueue", buildPendingActivateQueueJson())
            }

            "clear_pending_activate_queue" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                AppCoordinator.manualClearPendingActivateQueue()
                ok("已清空待活跃队列")
                    .put("pendingActivateQueue", buildPendingActivateQueueJson())
            }

            "set_pending_activate_queue" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                val names = parseUsernamesFromBody(body)
                if (names.isEmpty()) return fail("usernames_required", "请提供 usernames 列表")
                AppCoordinator.manualReplacePendingActivateQueue(names)
                ok("已更新待活跃队列（${names.size} 个）")
                    .put("pendingActivateQueue", buildPendingActivateQueueJson())
            }

            "sort_pending_activate_asc" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                AppCoordinator.manualSortPendingActivateByTotalMinutesAsc()
                ok("已按累计活跃时长升序重排")
                    .put("pendingActivateQueue", buildPendingActivateQueueJson())
            }

            "trim_pending_activate" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                val min = body.optInt(
                    "minutes",
                    PrefsStore.getManualActivateDurationMin(),
                ).coerceAtLeast(0)
                val removed = AppCoordinator.manualTrimPendingActivateByMinTotal(min)
                ok("已移除累计≥${min}分钟的账号 $removed 个")
                    .put("removed", removed)
                    .put("pendingActivateQueue", buildPendingActivateQueueJson())
            }

            "activate_next_account" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                if (AppCoordinator.isManualSwitchBusy()) {
                    return fail("switch_busy", "切号进行中，请稍候")
                }
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                if (PrefsStore.getPendingActivateUsernames().isEmpty()) {
                    return fail("queue_empty", "待活跃队列为空，请先将账号加入队列")
                }
                ensureWorkerForManualTask(app)
                val minutes = resolveActivateMinutes(body)
                PrefsStore.setManualActivateDurationMin(minutes)
                AppCoordinator.manualActivateNextAccount(minutes)
                ok("已请求活跃下个账号（${minutes} 分钟）")
                    .put("minutes", minutes)
            }

            "auto_active" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                if (AppCoordinator.isManualSwitchBusy()) {
                    return fail("switch_busy", "切号进行中，请稍候")
                }
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                val names = parseUsernamesFromBody(body)
                if (names.isEmpty() && PrefsStore.getPendingActivateUsernames().isEmpty()) {
                    return fail("queue_empty", "待活跃队列为空，请先加入账号或指定 usernames")
                }
                ensureWorkerForManualTask(app)
                val minutes = resolveActivateMinutes(body)
                PrefsStore.setManualActivateDurationMin(minutes)
                AppCoordinator.manualStartAutoActive(
                    if (names.isEmpty()) null else names,
                    minutes,
                )
                val queueSize = PrefsStore.getPendingActivateUsernames().size
                ok("已请求自动活跃（每号 ${minutes} 分钟，队列 $queueSize 个）")
                    .put("minutes", minutes)
                    .put("queueSize", queueSize)
            }

            "pause_pending_activate" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                val reason = body.optString("reason", "").trim().ifBlank { "熄屏暂停" }
                val result = AppCoordinator.manualPausePendingActivateForSleep(reason)
                ok(result.message)
                    .put("paused", result.paused)
                    .put("hadSession", result.hadSession)
                    .put("username", result.username)
                    .put("remainingSec", result.remainingSec)
                    .put("pendingActivateQueue", buildPendingActivateQueueJson())
            }

            "batch_publish_non_pending" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                if (AppCoordinator.isManualSwitchBusy()) {
                    return fail("switch_busy", "切号进行中，请稍候")
                }
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                val excludeBanned = body.optBoolean("excludeBanned", true)
                val countRaw = if (body.has("count")) {
                    body.optInt("count")
                } else {
                    PrefsStore.getManualPublishCount()
                }
                val count = countRaw.coerceIn(
                    PrefsStore.MIN_MANUAL_PUBLISH_COUNT,
                    PrefsStore.MAX_MANUAL_PUBLISH_COUNT,
                )
                val afterRaw = if (body.has("afterActiveMin")) {
                    body.optInt("afterActiveMin")
                } else {
                    PrefsStore.getManualBatchPublishAfterMin()
                }
                val afterMin = afterRaw.coerceIn(
                    PrefsStore.MIN_MANUAL_BATCH_PUBLISH_AFTER_MIN,
                    PrefsStore.MAX_MANUAL_BATCH_PUBLISH_AFTER_MIN,
                )
                PrefsStore.setManualPublishCount(count)
                PrefsStore.setManualBatchPublishAfterMin(afterMin)
                ensureWorkerForManualTask(app)
                val startedResult = kotlinx.coroutines.runBlocking {
                    AppCoordinator.manualStartBatchPublishNonPendingActivate(
                        publishCount = count,
                        afterActiveMin = afterMin,
                        excludeBanned = excludeBanned,
                    )
                }
                startedResult.fold(
                    onSuccess = { started ->
                        val targets = AppCoordinator.listBoundDeviceNonPendingActivateUsernames(
                            excludeBanned = excludeBanned,
                        )
                        ok(
                            "已请求非待活跃批量发（${started}个 · 每号${count}条 · 发后${afterMin}分钟）",
                        )
                            .put("targetCount", started)
                            .put("count", count)
                            .put("afterActiveMin", afterMin)
                            .put("usernames", JSONArray(targets))
                            .put(
                                "pendingActivateCount",
                                PrefsStore.getPendingActivateUsernames().size,
                            )
                    },
                    onFailure = { err ->
                        fail(
                            "start_failed",
                            err.message ?: "未能启动非待活跃批量发作品",
                        )
                            .put("pendingActivateCount", PrefsStore.getPendingActivateUsernames().size)
                            .put("accountCount", ManualDeviceControl.getCachedAccounts().size)
                    },
                )
            }

            "daily_incubate", "start_daily_incubate", "daily_incubate_current" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                if (!PrefsStore.isBound()) return fail("not_bound", "请先绑定设备")
                if (AppCoordinator.isManualSwitchBusy()) {
                    return fail("switch_busy", "切号进行中，请稍候")
                }
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }
                val minutes = when {
                    body.has("hours") -> {
                        PrefsStore.setDailyIncubateDurationHours(body.optDouble("hours"))
                        PrefsStore.getDailyIncubateDurationMin()
                    }
                    body.has("minutes") -> {
                        body.optInt("minutes").coerceIn(
                            PrefsStore.MIN_DAILY_INCUBATE_DURATION_MIN,
                            PrefsStore.MAX_DAILY_INCUBATE_DURATION_MIN,
                        ).also { PrefsStore.setDailyIncubateDurationMin(it) }
                    }
                    else -> PrefsStore.getDailyIncubateDurationMin()
                }
                val hoursText = PrefsStore.formatDailyIncubateDurationHours(
                    PrefsStore.getDailyIncubateDurationHours(),
                )
                val currentOnly = action.equals("daily_incubate_current", ignoreCase = true)
                    || body.optBoolean("currentOnly", false)
                    || body.optBoolean("onlyCurrent", false)
                // 仅认 onlyUsername。PC mirror 常把全局下拉 username 附带上，
                // 若这里再读 username，批量「当前账号养号」会错误地让所有设备养同一个号。
                val onlyUsernameExplicit = CompanionApi.normalizeUsername(
                    body.optString("onlyUsername", ""),
                )
                ensureWorkerForManualTask(app)
                val refresh = kotlinx.coroutines.runBlocking {
                    AppCoordinator.manualRefreshAssignedAccounts()
                }
                if (refresh.isFailure) {
                    return fail(
                        "refresh_failed",
                        refresh.exceptionOrNull()?.message ?: "拉取账号失败",
                    )
                }
                // 再次确认：刷新期间可能被其它多账号会话抢占
                if (AppCoordinator.isManualSwitchBusy()) {
                    return fail("switch_busy", "切号进行中，请稍候")
                }
                if (AppCoordinator.isBatchAccountSessionBusy()) {
                    return fail("session_busy", "多账号会话进行中，请稍候")
                }

                if (currentOnly || onlyUsernameExplicit.isNotBlank()) {
                    val target = onlyUsernameExplicit.ifBlank {
                        ManualDeviceControl.selectedManualUsername()
                            .ifBlank { ManualDeviceControl.activeManualUsername() }
                    }
                    if (target.isBlank()) {
                        return fail("username_required", "请先选择账号")
                            .put("dailyIncubateDurationMin", minutes)
                            .put("dailyIncubateDurationHours", PrefsStore.getDailyIncubateDurationHours())
                    }
                    val canIncubate = AppCoordinator.listBoundDeviceNonPendingActivateUsernames()
                        .any { it.equals(target, ignoreCase = true) }
                    if (!canIncubate) {
                        return fail(
                            "not_incubatable",
                            "@$target 不可养号（待活跃或已封禁）",
                        )
                            .put("username", target)
                            .put("queueSize", 0)
                            .put("dailyIncubateDurationMin", minutes)
                            .put("dailyIncubateDurationHours", PrefsStore.getDailyIncubateDurationHours())
                    }
                    AppCoordinator.manualStartDailyIncubate(minutes, onlyUsername = target)
                    if (!AppCoordinator.isDailyIncubateBusy()) {
                        val tip = AppCoordinator.runtimeState.value.lastMessage.trim()
                        return fail(
                            "start_failed",
                            if (tip.contains("日常孵化")) tip else "当前账号养号未能启动（冲突或已被抢占）",
                        )
                            .put("username", target)
                            .put("queueSize", 1)
                            .put("dailyIncubateDurationMin", minutes)
                            .put("dailyIncubateDurationHours", PrefsStore.getDailyIncubateDurationHours())
                    }
                    ok("已请求当前账号养号：@$target（约 ${hoursText} 小时）")
                        .put("minutes", minutes)
                        .put("hours", PrefsStore.getDailyIncubateDurationHours())
                        .put("username", target)
                        .put("queueSize", 1)
                        .put("singleAccountOnly", true)
                        .put("dailyIncubateDurationMin", minutes)
                        .put("dailyIncubateDurationHours", PrefsStore.getDailyIncubateDurationHours())
                } else {
                    val queueSize = AppCoordinator.listBoundDeviceNonPendingActivateUsernames().size
                    if (queueSize <= 0) {
                        return fail("queue_empty", "无非待活跃账号可养号")
                            .put("queueSize", 0)
                            .put("dailyIncubateDurationMin", minutes)
                            .put("dailyIncubateDurationHours", PrefsStore.getDailyIncubateDurationHours())
                    }
                    AppCoordinator.manualStartDailyIncubate(minutes)
                    if (!AppCoordinator.isDailyIncubateBusy()) {
                        val tip = AppCoordinator.runtimeState.value.lastMessage.trim()
                        return fail(
                            "start_failed",
                            if (tip.contains("日常孵化")) tip else "日常孵化未能启动（队列冲突或已被抢占）",
                        )
                            .put("queueSize", queueSize)
                            .put("dailyIncubateDurationMin", minutes)
                            .put("dailyIncubateDurationHours", PrefsStore.getDailyIncubateDurationHours())
                    }
                    ok("已请求日常孵化（约 $queueSize 个 · 每号 ${hoursText} 小时）")
                        .put("minutes", minutes)
                        .put("hours", PrefsStore.getDailyIncubateDurationHours())
                        .put("queueSize", queueSize)
                        .put("dailyIncubateDurationMin", minutes)
                        .put("dailyIncubateDurationHours", PrefsStore.getDailyIncubateDurationHours())
                }
            }

            "stop_daily_incubate" -> {
                if (!ManualDeviceControl.ENABLED) return fail("not_manual_mode", "请先切到本机手动模式")
                AppCoordinator.manualStopDailyIncubate("PC 遥控停止日常孵化")
                ok("已停止日常孵化")
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

    /** 全量账号列表；已在待活跃队列的会带 inPendingActivateQueue / pendingQueueIndex。 */
    private fun buildAccountsJson(accounts: List<ManualDeviceControl.AccountOption>): JSONArray {
        val arr = JSONArray()
        val active = ManualDeviceControl.activeManualUsername()
        accounts.forEach { opt ->
            val queueIndex = PrefsStore.pendingActivateQueueIndex(opt.username)
            arr.put(
                JSONObject()
                    .put("username", opt.username)
                    .put("hasBackup", opt.backupPath.isNotBlank() || opt.backupName.isNotBlank())
                    .put("firstLoginDone", PrefsStore.isManualFirstLoginDone(opt.username))
                    .put("autoSwitchExcluded", opt.autoSwitchExcluded)
                    .put("inPendingActivateQueue", queueIndex != null)
                    .put(
                        "pendingQueueIndex",
                        if (queueIndex != null) queueIndex else JSONObject.NULL,
                    )
                    .put(
                        "isActive",
                        active.isNotBlank() && opt.username.equals(active, ignoreCase = true),
                    )
                    .put("activateTotalMinutes", PrefsStore.getActivateTotalMinutes(opt.username))
                    .put("recommended", PendingActivateRecommend.isRecommended(opt.username))
                    .put(
                        "heartGrowth3d",
                        PendingActivateRecommend.snapshot(opt.username)?.heartGrowth3d
                            ?: JSONObject.NULL,
                    ),
            )
        }
        return arr
    }

    private fun buildPendingActivateQueueJson(): JSONArray {
        val arr = JSONArray()
        PrefsStore.getPendingActivateUsernames().forEachIndexed { index, username ->
            val plannedMin = PrefsStore.getManualActivateDurationMin()
            val remaining = PrefsStore.getActivateSessionRemainingSec(username, plannedMin)
            arr.put(
                JSONObject()
                    .put("username", username)
                    .put("queueIndex", index + 1)
                    .put("totalMinutes", PrefsStore.getActivateTotalMinutes(username))
                    .put(
                        "remainingSec",
                        if (remaining != null && remaining > 0) remaining else JSONObject.NULL,
                    ),
            )
        }
        return arr
    }

    /** TikTok / 素材账号：与 [CompanionApi.normalizeUsername] 对齐（trim + 去前导 @）。 */
    private fun tiktokUsernameFromBody(body: JSONObject, fallback: String = ""): String =
        CompanionApi.normalizeUsername(body.optString("username", ""))
            .ifEmpty { CompanionApi.normalizeUsername(fallback) }

    private fun parseUsernameArray(arr: JSONArray): List<String> {
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val u = CompanionApi.normalizeUsername(arr.optString(i, ""))
            if (u.isNotEmpty() && names.none { it.equals(u, ignoreCase = true) }) {
                names.add(u)
            }
        }
        return names
    }

    private fun parseUsernamesFromBody(body: JSONObject): List<String> {
        val arr = body.optJSONArray("usernames")
            ?: body.optJSONArray("pendingUsernames")
            ?: return emptyList()
        return parseUsernameArray(arr)
    }

    private fun resolveActivateMinutes(body: JSONObject): Int {
        if (body.has("hours")) {
            val hours = body.optDouble("hours", PrefsStore.getManualActivateDurationHours())
                .coerceIn(
                    PrefsStore.MIN_MANUAL_ACTIVATE_DURATION_HOURS,
                    PrefsStore.MAX_MANUAL_ACTIVATE_DURATION_HOURS,
                )
            return kotlin.math.round(hours * 60.0)
                .toInt()
                .coerceIn(
                    PrefsStore.MIN_MANUAL_ACTIVATE_DURATION_MIN,
                    PrefsStore.MAX_MANUAL_ACTIVATE_DURATION_MIN,
                )
        }
        val raw = if (body.has("minutes")) {
            body.optInt("minutes")
        } else {
            PrefsStore.getManualActivateDurationMin()
        }
        return raw.coerceIn(
            PrefsStore.MIN_MANUAL_ACTIVATE_DURATION_MIN,
            PrefsStore.MAX_MANUAL_ACTIVATE_DURATION_MIN,
        )
    }

    private fun ensureWorkerForManualTask(app: Context) {
        if (!WorkerServiceTracker.isRunning) {
            WorkerService.startResumingAutomation(app)
            LogCenter.op(PrefsStore.getAlias(), "PC 遥控活跃任务：后台服务未启动，已自动拉起")
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

    private suspend fun ensureUsbReady(context: Context, body: JSONObject): JSONObject {
        val aliasHint = body.optString("alias", "").trim()
        BindingRecovery.tryRecoverFromCloud(context)
        if (!PrefsStore.isBound()) {
            if (PrefsStore.getAuthToken().isNullOrBlank()) {
                return fail(
                    "cloud_auth_required",
                    "请先在网页「批量写入账号」，或在本机设置中登录云端",
                )
            }
            val alias = resolveBindAlias(context, aliasHint)
            val bindResp = runCatching {
                ApiService.bindDevice(alias, DeviceFingerprint.fromContext(context))
            }.getOrElse { err ->
                return fail("bind_error", err.message ?: "绑定请求异常")
            }
            if (bindResp.code != 0 || bindResp.device_id.isNullOrBlank()) {
                return fail(
                    "bind_failed",
                    bindResp.msg.ifBlank { "绑定失败 code=${bindResp.code}" },
                )
            }
            PrefsStore.saveBinding(bindResp.device_id!!, alias)
            PrefsStore.markBindingRefreshedNow()
            LogCenter.op(alias, "PC USB：云端绑定成功 ${bindResp.device_id}")
        }

        val regResult = VideoDedSettingsRegistrar.saveAndRegister(
            context,
            VideoDedSettingsRegistrar.SaveInput(),
            triggerWirelessIfNeeded = false,
            retryRootAdbIfNeeded = false,
            logSave = false,
            logRegistration = false,
        )
        val regJson = regResult.registration
        val regErr = regResult.error?.message?.trim().orEmpty()
        return JSONObject()
            .put("success", true)
            .put("message", if (regErr.isEmpty()) "USB 就绪（已绑定并向 PC 注册）" else "已绑定；PC 注册：$regErr")
            .put("bound", PrefsStore.isBound())
            .put("deviceId", PrefsStore.getDeviceId().orEmpty())
            .put("alias", PrefsStore.getAlias().orEmpty())
            .put("pcRegistered", regJson != null)
            .put("registration", regJson ?: JSONObject.NULL)
    }

    private fun resolveBindAlias(context: Context, hint: String): String {
        val trimmed = hint.trim()
        if (trimmed.isNotEmpty() && ALIAS_PATTERN.matches(trimmed)) return trimmed
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull().orEmpty()
        val raw = (trimmed.ifEmpty { androidId })
            .replace(Regex("[^A-Za-z0-9_]"), "_")
            .take(20)
            .ifBlank { "phone" }
        return if (raw.first().isDigit()) "d_$raw".take(20) else raw
    }

    private val ALIAS_PATTERN = Regex("^[A-Za-z0-9_]{1,20}$")

    private fun ok(message: String): JSONObject =
        JSONObject().put("success", true).put("message", message)

    private fun fail(error: String, detail: String): JSONObject =
        JSONObject()
            .put("success", false)
            .put("error", error)
            .put("detail", detail)
            .put("message", detail)
}
