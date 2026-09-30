package com.autotraces.app.videoded

import android.content.Context
import android.util.Log
import com.autotraces.app.ApiService
import com.autotraces.app.PrefsStore
import org.json.JSONObject

/**
 * 统一「保存 PC/投屏设置 + 向 Windows 注册」逻辑。
 * 无论 PC 地址为默认还是手填、IPv4 为自动还是手填，后台与界面均应主动执行，无需用户点按钮。
 */
object VideoDedSettingsRegistrar {
    private const val TAG = "VideoDedSettingsRegistrar"
    /** 无线流水线最短间隔，避免 15s 轮询反复 adb connect / prepare 打断投屏。 */
    private const val WIRELESS_PIPELINE_MIN_INTERVAL_MS = 120_000L
    @Volatile
    private var lastWirelessPipelineAtMs: Long = 0L
    /** 最近一次成功写入的投屏 IP；用于识别 IP 变更并强制重连。 */
    @Volatile
    private var lastPersistedMirrorIp: String = ""

    data class SaveInput(
        val windowsUrl: String? = null,
        val mirrorIp: String? = null,
        val adbPort: Int? = null,
    )

    data class SaveAndRegisterResult(
        val settingsSaved: Boolean,
        val registration: JSONObject?,
        val error: Throwable?,
        val mirrorIpChanged: Boolean = false,
    ) {
        val pcAdbState: String
            get() = registration?.optString("pcAdbState", "")?.trim().orEmpty()
        val mirrorReadyOnPc: Boolean
            get() = pcAdbState == "device"
    }

    /** 投屏显示名强制等于云端别名，不再保留任何自定义名。 */
    fun syncCompanionDeviceNameFromAlias(context: Context) {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isEmpty()) return
        // 直接写入；勿先 getDeviceName（会先自愈成 alias，导致后续日志永远不打印）
        CompanionApi.setDeviceName(context, alias)
    }

    data class PullPcServiceUrlResult(
        val message: String,
        val changed: Boolean,
        val remoteUrl: String = "",
        val registration: SaveAndRegisterResult? = null,
    )

    /**
     * 用户主动从云端拉取本机 PC 投屏地址并落盘/注册（不走轮询热路径）。
     * 使用 get_config（只读），避免 check_start 消耗 pending_start / 改状态。
     */
    suspend fun pullPcServiceUrlFromCloud(context: Context): PullPcServiceUrlResult {
        val app = context.applicationContext
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isEmpty() || alias.isEmpty()) {
            return PullPcServiceUrlResult("未绑定别名，无法从云端拉取", changed = false)
        }
        val remoteUrl = try {
            ApiService.getConfig(deviceId, alias).pc_service_url
                ?.trim()?.trimEnd('/').orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "pullPcServiceUrlFromCloud get_config failed", t)
            return PullPcServiceUrlResult("拉取失败：${t.message ?: "network"}", changed = false)
        }
        if (remoteUrl.isBlank()) {
            return PullPcServiceUrlResult("云端未配置该设备的 PC 地址", changed = false)
        }
        if (
            !remoteUrl.startsWith("http://", ignoreCase = true) &&
            !remoteUrl.startsWith("https://", ignoreCase = true)
        ) {
            return PullPcServiceUrlResult("云端 PC 地址格式无效：$remoteUrl", changed = false)
        }
        val currentUrl = CompanionApi.windowsBaseUrl(app).trim().trimEnd('/')
        if (currentUrl == remoteUrl) {
            return PullPcServiceUrlResult("已与云端一致：$remoteUrl", changed = false, remoteUrl = remoteUrl)
        }
        val result = saveAndRegister(
            app,
            input = SaveInput(windowsUrl = remoteUrl),
            logSave = true,
            logRegistration = true,
            bypassWirelessThrottle = true,
        )
        val msg = if (result.error == null) {
            "已同步：$currentUrl → $remoteUrl"
        } else {
            "地址已保存为 $remoteUrl，注册失败：${result.error?.message}"
        }
        return PullPcServiceUrlResult(msg, changed = true, remoteUrl = remoteUrl, registration = result)
    }

    /** 解析并持久化设置；未传字段沿用已存值或自动探测。能探测到实时 IPv4 时始终覆盖过期缓存。 */
    fun persistSettings(context: Context, input: SaveInput = SaveInput()): Boolean {
        return persistSettingsDetailed(context, input).saved
    }

    private data class PersistOutcome(val saved: Boolean, val mirrorIpChanged: Boolean, val rawIp: String)

    private fun persistSettingsDetailed(context: Context, input: SaveInput = SaveInput()): PersistOutcome {
        val app = context.applicationContext
        val windowsUrl = input.windowsUrl?.trim()?.trimEnd('/')
            ?: CompanionApi.windowsBaseUrl(app)
        if (windowsUrl.isBlank()) return PersistOutcome(false, false, "")

        val port = (input.adbPort ?: ApiSettings.getAdbTcpipPort(app)).coerceIn(1024, 65535)
        val previousIp = ApiSettings.getMirrorIpOverride(app).orEmpty()
            .ifEmpty { lastPersistedMirrorIp }
        val rawIp = resolveMirrorIp(app, input.mirrorIp)
        if (rawIp.isNotEmpty() && !ApiSettings.isPlausibleLanIpv4(rawIp)) {
            Log.w(TAG, "invalid mirror ip: $rawIp")
            return PersistOutcome(false, false, rawIp)
        }

        CompanionApi.setWindowsBaseUrl(app, windowsUrl)
        ApiSettings.setAdbTcpipPort(app, port)
        ApiSettings.setMirrorIpOverride(app, rawIp.ifEmpty { null })
        val changed = rawIp.isNotEmpty() && previousIp.isNotEmpty() && previousIp != rawIp
        if (changed) {
            Log.i(TAG, "mirror IP refreshed $previousIp -> $rawIp")
        }
        if (rawIp.isNotEmpty()) lastPersistedMirrorIp = rawIp
        Log.d(TAG, "persistSettings PC=$windowsUrl IP=${rawIp.ifEmpty { "auto" }} port=$port changed=$changed")
        return PersistOutcome(true, changed, rawIp)
    }

    /**
     * 能探测到实时 Wi‑Fi 局域网 IP 时始终覆盖旧缓存（含过期手填值）；
     * 仅在探测失败时才沿用入参或已存值。
     */
    private fun resolveMirrorIp(context: Context, override: String?): String {
        ApiSettings.probeLiveLanIpv4(context)?.let { return it }
        val fromInput = override?.trim().orEmpty()
        if (fromInput.isNotEmpty()) return fromInput
        return ApiSettings.getMirrorIpOverride(context)?.trim().orEmpty()
    }

    /**
     * 是否请求 PC 无线流水线。
     * @param forceReconnect IP 刚变更时即使 PC 仍报 device 也要重连（旧串口/旧 IP 已失效）。
     * @param bypassThrottle 用户手动保存/重试时绕过 120s 节流，但仍尊重「已是 device」短路。
     */
    fun shouldTriggerWirelessPipeline(
        context: Context,
        reg: JSONObject?,
        forceReconnect: Boolean = false,
        bypassThrottle: Boolean = false,
    ): Boolean {
        if (!forceReconnect && reg != null && reg.optString("pcAdbState", "").trim() == "device") return false
        if (MirrorStateProbe.currentMirrorStatus(context) == "offline") return false
        if (forceReconnect) return true
        if (!bypassThrottle) {
            val now = System.currentTimeMillis()
            if (now - lastWirelessPipelineAtMs < WIRELESS_PIPELINE_MIN_INTERVAL_MS) return false
        }
        return true
    }

    /** adbd 已在目标端口监听时，禁止 stop/start adbd，否则会断开 PC 投屏。 */
    fun shouldEnableAdbTcpip(context: Context, port: Int? = null): Boolean {
        val p = port ?: ApiSettings.getAdbTcpipPort(context)
        return !AdbRuntimeProbe.isAdbTcpPortOpen(context, p)
    }

    suspend fun saveAndRegister(
        context: Context,
        input: SaveInput = SaveInput(),
        triggerWirelessIfNeeded: Boolean = true,
        retryRootAdbIfNeeded: Boolean = true,
        logSave: Boolean = true,
        logRegistration: Boolean = true,
        /** 用户手动保存/重试：绕过 120s 节流，但不在已是 device 时强行重连。 */
        bypassWirelessThrottle: Boolean = false,
    ): SaveAndRegisterResult {
        val app = context.applicationContext
        val persist = persistSettingsDetailed(app, input)
        if (!persist.saved) {
            if (logSave) StatusLogBuffer.append("保存中止：IPv4 格式不正确")
            return SaveAndRegisterResult(false, null, IllegalArgumentException("invalid_settings"))
        }
        if (logSave) {
            val windowsUrl = CompanionApi.windowsBaseUrl(app)
            val rawIp = persist.rawIp.ifEmpty { ApiSettings.getMirrorIpOverride(app).orEmpty() }
            val port = ApiSettings.getAdbTcpipPort(app)
            val ipNote = if (persist.mirrorIpChanged) "（已更新）" else ""
            StatusLogBuffer.append("保存设置：PC=$windowsUrl · IP=${rawIp.ifEmpty { "自动" }}$ipNote · ADB端口=$port")
        }
        return try {
            syncCompanionDeviceNameFromAlias(app)
            if (logRegistration) StatusLogBuffer.append("正在向 PC 重新注册…")
            val reg = CompanionApi.ensureRegistered(app)
            afterRegistration(
                app,
                reg,
                retryRootAdbIfNeeded = retryRootAdbIfNeeded || persist.mirrorIpChanged,
                triggerWirelessIfNeeded = triggerWirelessIfNeeded || persist.mirrorIpChanged,
                forceWirelessReconnect = persist.mirrorIpChanged,
                bypassWirelessThrottle = bypassWirelessThrottle,
                logRegistration = logRegistration,
            )
            SaveAndRegisterResult(true, reg, null, mirrorIpChanged = persist.mirrorIpChanged)
        } catch (t: Throwable) {
            Log.e(TAG, "saveAndRegister failed", t)
            if (logRegistration) StatusLogBuffer.append("注册失败：${t.message}")
            SaveAndRegisterResult(true, null, t, mirrorIpChanged = persist.mirrorIpChanged)
        }
    }

    private suspend fun afterRegistration(
        context: Context,
        reg: JSONObject,
        retryRootAdbIfNeeded: Boolean,
        triggerWirelessIfNeeded: Boolean,
        forceWirelessReconnect: Boolean,
        bypassWirelessThrottle: Boolean,
        logRegistration: Boolean,
    ) {
        val port = ApiSettings.getAdbTcpipPort(context)
        val mirrorStatus = MirrorStateProbe.currentMirrorStatus(context)
        val mirrorHint = MirrorStateProbe.currentMirrorMessage(context)
        val tcpOpen = AdbRuntimeProbe.isAdbTcpPortOpen(context, port)
        val pcAdbState = reg.optString("pcAdbState", "").trim()
        val pcSerial = reg.optString("pcAdbSerial", "").trim()
        if (logRegistration) {
            StatusLogBuffer.append(
                "设备注册成功（本机：$mirrorStatus · 本机${port}=${if (tcpOpen) "已监听" else "未监听"} · PC端=${pcAdbState.ifEmpty { "—" }}）",
            )
        }
        if (pcAdbState == "device" && !forceWirelessReconnect) {
            if (logRegistration) {
                StatusLogBuffer.append(
                    "PC 已无线连接${if (pcSerial.isNotEmpty()) "（$pcSerial）" else ""}，可投屏；本机若仍写未监听，以 PC 为准",
                )
            }
            return
        }
        if (forceWirelessReconnect && logRegistration) {
            StatusLogBuffer.append("本机 IP 已变更，请求 PC 按新地址重新无线连接…")
        }
        if (logRegistration) {
            mirrorHint?.let { StatusLogBuffer.append("投屏说明：${mirrorHintForStatusLog(it)}") }
        }

        if (retryRootAdbIfNeeded && !tcpOpen &&
            ApiSettings.isRootAdbTcpipEnabled(context) && RootWifiAdbHelper.hasSu()
        ) {
            if (logRegistration) StatusLogBuffer.append("正在重试开启无线 ADB…")
            VideoDedDeviceService.enqueueTryRootAdbTcpip(context)
        }
        if (triggerWirelessIfNeeded &&
            shouldTriggerWirelessPipeline(
                context,
                reg,
                forceReconnect = forceWirelessReconnect,
                bypassThrottle = bypassWirelessThrottle,
            )
        ) {
            if (ApiSettings.getEffectiveMirrorLanIpv4(context) == null) {
                if (logRegistration) StatusLogBuffer.append("本机暂无 Wi‑Fi IPv4，跳过 PC 无线流水线（避免误连其它机）")
            } else {
                if (logRegistration) StatusLogBuffer.append("正在请求 PC 无线授权流水线…")
                lastWirelessPipelineAtMs = System.currentTimeMillis()
                runCatching {
                    CompanionApi.requestPcWirelessPipeline(context, launchMirror = false)
                }.onSuccess {
                    if (logRegistration) StatusLogBuffer.append("PC 已执行无线准备/连接")
                }.onFailure {
                    if (logRegistration) StatusLogBuffer.append("PC 流水线失败：${it.message}")
                }
            }
        }
    }

    private fun mirrorHintForStatusLog(hint: String): String {
        val lower = hint.lowercase()
        if ("su" in lower || "root" in lower) {
            return "无线 ADB 或授权未完成，请检查开发者选项与本机 ADB 端口"
        }
        return hint
    }
}
