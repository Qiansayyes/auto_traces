package com.autotraces.app.videoded

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID

class ApiServer(private val context: Context) : NanoHTTPD(CompanionBridge.API_PORT) {
    fun startAndRememberPort(timeout: Int, daemon: Boolean): Int {
        start(timeout, daemon)
        val port = listeningPort
        if (port > 0) {
            ApiSettings.rememberListenPort(context, port)
        }
        return port
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!ApiSettings.isApiEnabled(context)) {
                return jsonResponse(503, errorJson("api_disabled"))
            }

            if (!ApiAuth.validate(context, session)) {
                return jsonResponse(401, errorJson("unauthorized"))
            }

            when {
                session.method == Method.GET && session.uri == "/health" -> jsonResponse(200, JSONObject().put("ok", true).put("service", "video_ded_autotraces"))
                session.method == Method.PUT && session.uri == "/api/v1/settings/callback-package" -> handleCallbackPackage(session)
                session.method == Method.GET && session.uri == "/api/v1/settings/callback-package" -> jsonResponse(
                    200,
                    JSONObject().apply {
                        put("packageName", ApiSettings.getCallbackPackage(context) ?: JSONObject.NULL)
                    },
                )
                session.method == Method.POST && session.uri == "/api/v1/tasks/request" -> handleRequestVideos(session)
                session.method == Method.POST && session.uri == "/api/v1/tasks/follow-pc" -> handleFollowPcTask(session)
                session.method == Method.GET && session.uri == "/api/v1/tasks" -> handleListTasks()
                session.method == Method.GET && session.uri.startsWith("/api/v1/tasks/") -> handleGetTask(session)
                session.method == Method.POST && session.uri.startsWith("/api/v1/tasks/") && session.uri.endsWith("/retry") -> handleRetry(session)
                session.method == Method.POST && session.uri == "/api/v1/adb/prepare" -> handleAdbPrepare(session)
                session.method == Method.GET && session.uri == "/api/v1/system/memory" -> handleSystemMemory(session)
                session.method == Method.GET && session.uri == "/api/v1/simple/status" ->
                    jsonResponse(200, SimpleRemoteControl.buildStatus())
                session.method == Method.POST && session.uri == "/api/v1/simple/action" -> handleSimpleAction(session)
                session.method == Method.POST && session.uri == "/api/v1/worker/start" -> handleWorkerStart(session)
                else -> jsonResponse(404, errorJson("not_found"))
            }
        } catch (t: Throwable) {
            Log.e("ApiServer", "serve failed", t)
            jsonResponse(500, errorJson("server_error", t.message))
        }
    }

    private fun handleCallbackPackage(session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        val raw = body.optString("packageName", "").trim()
        val normalized = if (raw.isEmpty()) null else raw
        if (normalized != null && !normalized.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*\$"))) {
            return jsonResponse(400, errorJson("invalid_package_name"))
        }
        ApiSettings.setCallbackPackage(context, normalized)
        return jsonResponse(
            200,
            JSONObject().apply {
                put("success", true)
                if (normalized == null) put("packageName", JSONObject.NULL) else put("packageName", normalized)
            },
        )
    }

    private fun handleFollowPcTask(session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        val taskId = body.optString("taskId", "").trim().ifEmpty { body.optString("task_id", "").trim() }
        if (taskId.isEmpty()) {
            return jsonResponse(400, errorJson("task_id_required"))
        }
        return runBlockingApi {
            val mine = CompanionApi.deviceId(context.applicationContext)
            val detail =
                runCatching { CompanionApi.pollTaskUntilReady(context.applicationContext, taskId) }
                    .getOrElse { t ->
                        val msg = t.message.orEmpty()
                        if (msg.contains("task_prepare_failed")) {
                            return@runBlockingApi jsonResponse(
                                409,
                                JSONObject().apply {
                                    put("success", false)
                                    put("error", "task_terminal")
                                    put("detail", msg)
                                },
                            )
                        }
                        if (msg.contains("task_prepare_timeout")) {
                            return@runBlockingApi jsonResponse(
                                504,
                                JSONObject().apply {
                                    put("success", false)
                                    put("error", "task_prepare_timeout")
                                    put("detail", msg)
                                },
                            )
                        }
                        return@runBlockingApi jsonResponse(
                            502,
                            JSONObject().apply {
                                put("success", false)
                                put("error", "pc_task_fetch_failed")
                                put(
                                    "detail",
                                    msg.ifEmpty {
                                        "无法在 Windows ${CompanionApi.windowsBaseUrl(context.applicationContext)} 读取任务详情，请核对手机「Windows 地址」与网络."
                                    },
                                )
                            },
                        )
                    }

            val owner = detail.deviceId?.trim()?.takeIf { it.isNotEmpty() }
            if (owner != null && !owner.equals(mine, ignoreCase = true)) {
                return@runBlockingApi jsonResponse(
                    403,
                    JSONObject().apply {
                        put("success", false)
                        put("error", "task_wrong_device")
                        put("detail", "任务的 deviceId 与当前手机不一致.")
                    },
                )
            }

            when (detail.status.lowercase()) {
                "completed", "failed", "expired" ->
                    return@runBlockingApi jsonResponse(
                        409,
                        JSONObject().apply {
                            put("success", false)
                            put("error", "task_terminal")
                            put("status", detail.status)
                        },
                    )
            }

            val files = detail.files.orEmpty()

            val targetPath = CompanionApi.resolvedTargetPath(detail.targetPath)
            TaskStore.saveTaskSnapshot(
                context.applicationContext,
                TaskSnapshot(
                    taskId = detail.taskId,
                    status = detail.status,
                    error = detail.error,
                    createdAtMs = System.currentTimeMillis(),
                    targetPath = targetPath,
                    originalTaskId = null,
                    retryOfTaskId = null,
                    files =
                        files.map {
                            TaskFileSnapshot(it.fileName, it.sizeBytes, it.md5, "pending", null)
                        },
                ),
            )
            VideoDedDeviceService.enqueueDownloadTask(context.applicationContext, detail.taskId, files, targetPath)
            jsonResponse(
                200,
                JSONObject()
                    .put("success", true)
                    .put("taskId", detail.taskId)
                    .put("status", detail.status)
                    .put("message", "download_enqueued"),
            )
        }
    }

    private fun handleRequestVideos(session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        val count = body.optInt("count", 1)
        val targetPath = CompanionApi.resolvedTargetPath(
            body.optString("targetPath", null).takeIf { body.has("targetPath") && !body.isNull("targetPath") },
        )
        val task = runBlockingApi { CompanionApi.createTaskAndWaitReady(context, count, targetPath) }
        TaskStore.saveTaskSnapshot(
            context,
            TaskSnapshot(
                taskId = task.taskId,
                status = task.status,
                error = null,
                createdAtMs = System.currentTimeMillis(),
                targetPath = targetPath,
                originalTaskId = null,
                retryOfTaskId = null,
                files = task.files.map { TaskFileSnapshot(it.fileName, it.sizeBytes, it.md5, "pending", null) }
            )
        )
        // PC 创建成功时会 follow-pc；仅在其未通知时再本机入队，避免双跑。
        val enqueuedLocally =
            task.companionPullNotified != true &&
                VideoDedDeviceService.enqueueDownloadTask(context.applicationContext, task.taskId, task.files, targetPath)
        return jsonResponse(
            200,
            JSONObject()
                .put("success", true)
                .put("taskId", task.taskId)
                .put("status", task.status)
                .put(
                    "message",
                    if (enqueuedLocally) "download_enqueued_local_fallback" else "download_enqueued_by_pc",
                ),
        )
    }

    private fun handleListTasks(): Response {
        val tasks = TaskStore.loadTasks(context)
        val arr = JSONArray()
        tasks.forEach { task ->
            arr.put(task.toJson())
        }
        return jsonResponse(200, JSONObject().put("tasks", arr))
    }

    private fun handleGetTask(session: IHTTPSession): Response {
        val taskId = session.uri.substringAfterLast("/")
        val task = TaskStore.loadTasks(context).firstOrNull { it.taskId == taskId }
            ?: return jsonResponse(404, errorJson("task_not_found"))
        return jsonResponse(200, task.toJson())
    }

    private fun handleSystemMemory(session: IHTTPSession): Response {
        val params = session.parms
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 40
        val minPssKb = params["minPssKb"]?.toIntOrNull()?.coerceAtLeast(0) ?: 1_024
        val snapshot = RuntimeMemoryProbe.capture(context.applicationContext, limit, minPssKb)
        return jsonResponse(200, RuntimeMemoryProbe.toJson(snapshot))
    }

    private fun handleAdbPrepare(session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        val publicKey = body.optString("publicKey", "").trim().ifEmpty { null }
        val port = body.optInt("tcpPort", ApiSettings.getAdbTcpipPort(context))
        val result = AdbWirelessSetup.prepare(context, publicKey, port)
        return jsonResponse(
            200,
            JSONObject()
                .put("ok", result.ok)
                .put("su", result.su)
                .put("tcpPort", result.tcpPort)
                .put("tcpOpen", result.tcpOpen)
                .put("keyInstalled", result.keyInstalled)
                .put("keyAlreadyPresent", result.keyAlreadyPresent)
                .put("tcpEnabled", result.tcpEnabled)
                .put("detail", result.detail),
        )
    }

    private fun handleSimpleAction(session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        // 业务成败看 JSON success；HTTP 恒为 200，便于 PC 经 USB forward 解析 message。
        return jsonResponse(200, SimpleRemoteControl.handleAction(context.applicationContext, body))
    }

    /** 兼容 video_ded 批量「启动后台服务」：转发到 simple 遥控 start_service。 */
    private fun handleWorkerStart(session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        val reason = body.optString("reason", "").trim().ifEmpty { "PC 批量启动后台服务" }
        val result = SimpleRemoteControl.handleAction(
            context.applicationContext,
            JSONObject().put("action", "start_service").put("reason", reason),
        )
        return jsonResponse(200, result)
    }

    private fun handleRetry(session: IHTTPSession): Response {
        val taskId = session.uri.substringAfterLast("/").substringBefore("/retry")
        val task = TaskStore.loadTasks(context).firstOrNull { it.taskId == taskId }
            ?: return jsonResponse(404, errorJson("task_not_found"))
        val failedFiles = task.files.filter { it.status == "failed" || it.status == "retry_failed" }
        if (failedFiles.isEmpty()) {
            return jsonResponse(400, errorJson("no_failed_files"))
        }
        runBlockingApi { TaskRetryManager.retryFailedFilesInPlace(context, task, failedFiles) }
        val refreshed = TaskStore.loadTasks(context).firstOrNull { it.taskId == task.taskId } ?: task
        return jsonResponse(200, refreshed.toJson())
    }

    private fun readBody(session: IHTTPSession): String {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        return files["postData"] ?: "{}"
    }

    private fun jsonResponse(code: Int, obj: Any): Response {
        val text = when (obj) {
            is JSONObject -> obj.toString()
            else -> JSONObject.wrap(obj).toString()
        }
        return newFixedLengthResponse(Response.Status.lookup(code) ?: Response.Status.OK, "application/json", text)
    }

    private fun errorJson(error: String, detail: String? = null): JSONObject = JSONObject().apply {
        put("success", false)
        put("error", error)
        if (detail != null) put("detail", detail)
    }

    private fun <T> runBlockingApi(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}

object ApiSettings {
    private const val PREFS = "video_ded_embedded"
    private const val KEY_API_ENABLED = "api_enabled"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_API_LISTEN_PORT = "api_listen_port"
    private const val KEY_CALLBACK_PACKAGE = "callback_target_package"
    private const val KEY_ADB_TCPIP_PORT = "adb_tcpip_port"
    private const val KEY_MIRROR_IP_OVERRIDE = "mirror_ip_override"

    fun rememberListenPort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_API_LISTEN_PORT, port).apply()
    }

    /** 本地 HTTP API 常开，供 PC / Companion 轮询内存等诊断接口。 */
    @Suppress("UNUSED_PARAMETER")
    fun isApiEnabled(context: Context): Boolean = true

    /** 未写入前默认为 [CompanionBridge.API_PORT]（与 HTTP 监听一致）。 */
    fun getListenPort(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_API_LISTEN_PORT, 0)
        return if (p > 0) p else CompanionBridge.API_PORT
    }

    fun localBaseUrl(context: Context): String = "http://127.0.0.1:${getListenPort(context)}"

    /** 任务完成广播投递的包名；null 或空表示仅本包（Companion）。 */
    fun getCallbackPackage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CALLBACK_PACKAGE, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setCallbackPackage(context: Context, packageName: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (packageName.isNullOrBlank()) {
            editor.remove(KEY_CALLBACK_PACKAGE)
        } else {
            editor.putString(KEY_CALLBACK_PACKAGE, packageName.trim())
        }
        editor.apply()
    }

    @Suppress("UNUSED_PARAMETER")
    fun setApiEnabled(context: Context, enabled: Boolean) {
        // 常开：忽略历史偏好与外部写入
    }

    fun getToken(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_API_TOKEN, null)
        ?: UUID.randomUUID().toString().also {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API_TOKEN, it).apply()
        }

    fun setToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API_TOKEN, token).apply()
    }

    /** Root + su：在已连 Wi‑Fi 时由前台服务尝试 `setprop` + 重启 adbd，固定 TCP 端口供 `adb connect`（常开，不可关闭）。 */
    @Suppress("UNUSED_PARAMETER")
    fun isRootAdbTcpipEnabled(context: Context): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun setRootAdbTcpipEnabled(context: Context, enabled: Boolean) {
        // 常开：忽略历史偏好与外部写入
    }

    fun getAdbTcpipPort(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ADB_TCPIP_PORT, 5555)
        return if (p in 1024..65535) p else 5555
    }

    fun setAdbTcpipPort(context: Context, port: Int) {
        val clamped = port.coerceIn(1024, 65535)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ADB_TCPIP_PORT, clamped).apply()
    }

    /** 已缓存的局域网 IPv4（可能过期）；展示/连接时应优先 [probeLiveLanIpv4] / [getEffectiveMirrorLanIpv4]。 */
    fun getMirrorIpOverride(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MIRROR_IP_OVERRIDE, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun setMirrorIpOverride(context: Context, ipv4OrNull: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (ipv4OrNull.isNullOrBlank()) {
            editor.remove(KEY_MIRROR_IP_OVERRIDE)
        } else {
            editor.putString(KEY_MIRROR_IP_OVERRIDE, ipv4OrNull.trim())
        }
        editor.apply()
    }

    /** 实时探测本机局域网 IPv4（仅 Wi‑Fi 链路，避免误取 VPN/其它网卡）；失败返回 null。 */
    fun probeLiveLanIpv4(context: Context): String? {
        val raw = RootWifiAdbHelper.getWifiLanIpv4OrNull(context) ?: return null
        if (!isPlausibleLanIpv4(raw)) return null
        return runCatching { (InetAddress.getByName(raw.trim()) as Inet4Address).hostAddress }.getOrNull()
            ?: raw.trim()
    }

    /**
     * 有效投屏/ADB 局域网 IPv4：能探测到则用实时 Wi‑Fi 地址；
     * 否则回退已存覆盖值，再回退 [WifiUtils.getWifiIp]（仅作兜底展示，不写缓存）。
     */
    fun getEffectiveMirrorLanIpv4(context: Context): String? {
        probeLiveLanIpv4(context)?.let { return it }
        val o = getMirrorIpOverride(context)
        if (o != null && isPlausibleLanIpv4(o)) {
            return runCatching { (InetAddress.getByName(o) as Inet4Address).hostAddress }.getOrNull() ?: o
        }
        val fallback = WifiUtils.getWifiIp(context)?.trim().orEmpty()
        return fallback.takeIf { it.isNotEmpty() && isPlausibleLanIpv4(it) }
    }

    /** 非空且可解析为 IPv4（含校验用例）则视为有效；保存前应在 UI 再提示一次。 */
    fun isPlausibleLanIpv4(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        return try {
            val a = InetAddress.getByName(s)
            a is Inet4Address && !a.isLoopbackAddress
        } catch (_: Exception) {
            false
        }
    }

}

object ApiAuth {
    fun validate(context: Context, session: NanoHTTPD.IHTTPSession): Boolean {
        val token = ApiSettings.getToken(context)
        val headerToken = session.headers["x-api-token"] ?: session.headers["authorization"]?.removePrefix("Bearer ")
        return headerToken == token
    }
}
