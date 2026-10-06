package com.autotraces.app.videoded

import android.content.Context
import com.autotraces.app.ApiService
import com.autotraces.app.AppCoordinator
import com.autotraces.app.BindingRecovery
import com.autotraces.app.DeviceFingerprint
import com.autotraces.app.LogCenter
import com.autotraces.app.PrefsStore
import com.autotraces.app.WorkerService
import com.autotraces.app.WorkerServiceTracker
import org.json.JSONArray
import org.json.JSONObject

/**
 * PC（经本机 ApiServer）遥控 / 状态探测入口。
 * 以 android-client 安装包为准：至少保证 `alias` 可被投屏墙拉取对齐；
 * 完整手动区遥控仍以 android-simple 为准（本包无 ManualDeviceControl）。
 */
object SimpleRemoteControl {
    /** GET /api/v1/simple/status — video_ded 用返回的 alias 对齐投屏墙 device_name。 */
    fun buildStatus(): JSONObject {
        val runtime = AppCoordinator.runtimeState.value
        val alias = PrefsStore.getAlias().orEmpty()
        return JSONObject()
            .put("success", true)
            .put("manualMode", false)
            .put("bound", PrefsStore.isBound())
            .put("deviceId", PrefsStore.getDeviceId().orEmpty())
            .put("alias", alias)
            .put("deviceName", alias.ifEmpty { "设备" })
            .put("cloudUsername", PrefsStore.getAuthUsername().orEmpty())
            .put("cloudLoggedIn", !PrefsStore.getAuthToken().isNullOrBlank())
            .put(
                "serverBaseUrl",
                PrefsStore.getServerBaseUrl().orEmpty().ifBlank {
                    runCatching { ApiService.getBaseUrl() }.getOrDefault("")
                },
            )
            .put("serviceRunning", WorkerServiceTracker.isRunning)
            .put("hardStopRequested", PrefsStore.isHardStopRequested())
            .put("localStatus", runtime.localStatus)
            .put("lastMessage", runtime.lastMessage)
            .put("accounts", JSONArray())
    }

    fun buildStatus(context: Context): JSONObject {
        val base = buildStatus()
        base.put("deviceName", CompanionApi.getDeviceName(context))
        return base
    }

    fun handleAction(context: Context, body: JSONObject): JSONObject {
        val action = body.optString("action", "").trim()
        if (action.isEmpty()) {
            return fail("action_required", "缺少 action")
        }
        val app = context.applicationContext
        return when (action) {
            "status" -> buildStatus(app)

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
                // 与 android-simple 一致：云端自动切号自治中禁止 PC 硬停，避免误打断
                if (PrefsStore.isAutoSwitchAutonomousMode()) {
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

            else -> fail(
                "not_supported",
                "android-client 不支持 action=$action（完整手动遥控请用 android-simple）",
            )
        }.also { resp ->
            if (resp.optBoolean("success", false)) {
                LogCenter.op(
                    PrefsStore.getAlias(),
                    "PC 遥控：$action → ${resp.optString("message")}",
                )
            }
        }
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

        VideoDedSettingsRegistrar.syncCompanionDeviceNameFromAlias(context)
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
            .put("deviceName", CompanionApi.getDeviceName(context))
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
