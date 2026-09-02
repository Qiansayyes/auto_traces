package com.autotraces.app.videoded

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.autotraces.app.PrefsStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

object CompanionApi {
    private const val TAG = "CompanionApi"
    private const val PREFS = "video_ded_embedded"
    private const val USB_LOOPBACK_BASE = "http://127.0.0.1:5088"
    private const val BASE_URL_CACHE_MS = 15_000L

    private val baseUrlCacheLock = Any()
    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var cachedAtMs: Long = 0L

    /** 与外部业务（如 adb 统计相册图片）对齐的素材落盘目录。 */
    const val DEFAULT_MATERIAL_STORAGE_PATH = "/storage/emulated/0/Pictures"

    fun resolvedTargetPath(targetPath: String?): String =
        targetPath?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MATERIAL_STORAGE_PATH
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_BACKEND_GROUP = "backend_group_name"
    private val gson = Gson()

    private fun okhttpBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })

    /** createTask / ack 等 JSON 接口。 */
    private val apiClient = okhttpBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** PC 异步 compose 素材时轮询 GET /api/tasks/{id} 的最长等待。 */
    private const val TASK_PREPARE_TIMEOUT_MS = 120_000L
    private const val TASK_PREPARE_POLL_MS = 2_000L

    private val terminalPcTaskStatuses = setOf("failed", "expired", "cancelled", "canceled")

    /** 从 PC 流式下载素材；默认读超时 10s 会导致发作品「素材拉取异常 timeout」。 */
    private val downloadClient = okhttpBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val json = "application/json; charset=utf-8"

    /**
     * PC 素材/任务接口使用的 deviceId（与 [video_ded] Companion 一致）。
     * 优先读本机已存 ID（注册后 PC 返回的 canonical UUID）；首次启动用 `vd-{ANDROID_ID}`。
     * 注册成功后由 [adoptCanonicalDeviceId] 写入 PC 合并后的 ID，**不得**每次调用强行覆盖。
     * 投屏走 ADB/Serial，与此 ID 无关。
     */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        if (stored.isNotEmpty()) {
            return stored
        }
        val stable = stableDeviceId(context)
        prefs.edit().putString(KEY_DEVICE_ID, stable).apply()
        return stable
    }

    fun hardwareKey(context: Context): String? =
        stableDeviceId(context).takeIf { it.startsWith("vd-") }

    private fun stableDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
        if (androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
            return "vd-$androidId"
        }
        return UUID.randomUUID().toString()
    }

    private fun adoptCanonicalDeviceId(context: Context, reg: org.json.JSONObject) {
        val serverId = reg.optString("deviceId", "").trim()
        if (serverId.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val local = prefs.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        if (serverId != local) {
            Log.i(TAG, "adopt canonical deviceId $local -> $serverId")
            prefs.edit().putString(KEY_DEVICE_ID, serverId).apply()
        }
    }

    fun getDeviceName(context: Context): String {
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (alias.isNotEmpty()) return alias

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DEVICE_NAME, null)?.trim().orEmpty()
        if (stored.isNotEmpty()) return stored
        val fallback = WifiUtils.getWifiIp(context)
            ?.substringAfterLast('.')
            ?.takeIf { it.isNotBlank() }
            ?.let { "设备-$it" }
            ?: "设备"
        prefs.edit().putString(KEY_DEVICE_NAME, fallback).apply()
        return fallback
    }

    fun setDeviceName(context: Context, value: String) {
        val name = value.trim().ifBlank { getDeviceName(context) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_NAME, name)
            .apply()
    }

    private fun normalizeGroupName(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    fun getBackendGroupName(context: Context): String? =
        normalizeGroupName(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKEND_GROUP, null),
        )

    private fun setBackendGroupName(context: Context, nameOrNull: String?) {
        val normalized = normalizeGroupName(nameOrNull)
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (normalized == null) {
            editor.remove(KEY_BACKEND_GROUP)
        } else {
            editor.putString(KEY_BACKEND_GROUP, normalized)
        }
        editor.apply()
    }

    fun formatDeviceNameLine(context: Context): String {
        val name = getDeviceName(context)
        val group = getBackendGroupName(context) ?: "未分组"
        val id = deviceId(context)
        val hw = hardwareKey(context)
        return if (hw != null && !hw.equals(id, ignoreCase = true)) {
            "设备名称：$name · $group · ID=$id · hw=$hw"
        } else {
            "设备名称：$name · $group · ID=$id"
        }
    }

    private fun parseGroupNameFromRegistration(reg: org.json.JSONObject): String? {
        if (!reg.has("groupName") || reg.isNull("groupName")) return null
        return normalizeGroupName(reg.optString("groupName", ""))
    }

    fun windowsBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString("windows_base_url", "http://192.168.1.10:5088")!!
        Log.d(TAG, "windowsBaseUrl=$url")
        return url
    }

    /**
     * USB 已 reverse 时优先本机 127.0.0.1:5088，否则回退 [windowsBaseUrl]。
     * 多机各自 reverse，互不串隧道；素材隔离仍靠 deviceId / taskId。
     */
    fun resolveBaseUrl(context: Context): String {
        val now = System.currentTimeMillis()
        synchronized(baseUrlCacheLock) {
            val cached = cachedBaseUrl
            if (cached != null && now - cachedAtMs < BASE_URL_CACHE_MS) {
                return cached
            }
        }
        val usb = if (probeUsbLoopbackCompanion()) USB_LOOPBACK_BASE else null
        val resolved = usb ?: windowsBaseUrl(context).trim().trimEnd('/')
        synchronized(baseUrlCacheLock) {
            cachedBaseUrl = resolved
            cachedAtMs = now
        }
        if (usb != null) {
            Log.i(TAG, "resolveBaseUrl using USB loopback $USB_LOOPBACK_BASE")
        } else {
            Log.d(TAG, "resolveBaseUrl using LAN $resolved")
        }
        return resolved
    }

    fun invalidateBaseUrlCache() {
        synchronized(baseUrlCacheLock) {
            cachedBaseUrl = null
            cachedAtMs = 0L
        }
    }

    /** USB 已 adb reverse 时，手机经 127.0.0.1:5088 可达 PC，无需 WiFi / 局域网地址。 */
    fun isUsbPcTunnelActive(): Boolean = probeUsbLoopbackCompanion()

    private fun probeUsbLoopbackCompanion(): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(800, TimeUnit.MILLISECONDS)
                .readTimeout(1200, TimeUnit.MILLISECONDS)
                .writeTimeout(800, TimeUnit.MILLISECONDS)
                .callTimeout(2, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url("$USB_LOOPBACK_BASE/health").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body?.string().orEmpty()
                body.contains("video_ded", ignoreCase = true) || body.contains("\"ok\"", ignoreCase = true)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "probeUsbLoopbackCompanion miss: ${t.message}")
            false
        }
    }

    fun setWindowsBaseUrl(context: Context, value: String) {
        val normalized = value.trim().trimEnd('/')
        Log.d(TAG, "setWindowsBaseUrl=$normalized")
        invalidateBaseUrlCache()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("windows_base_url", normalized)
            .apply()
    }

    /** PC 已 adb push 到 targetPath 时，校验本地文件是否与任务一致。 */
    fun localFileMatches(expected: TaskFileInfo, file: File): Boolean {
        return try {
            verifyFile(file, expected)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun deviceFolder(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "received")
        dir.mkdirs()
        return dir
    }

    /** 安卓端接收根目录（仅本机；与 PC 绑定目录无关）。 */
    fun receivedStorageRoot(context: Context): File = deviceFolder(context)

    fun receivedTaskDirDisplay(context: Context, taskId: String): String {
        return receivedTaskDir(context, taskId).absolutePath
    }

    /** 某任务在本机上的接收目录（与下载落盘路径一致）。 */
    fun receivedTaskDir(context: Context, taskId: String): File {
        val folder = File(deviceFolder(context), taskId)
        folder.mkdirs()
        return folder
    }

    private fun taskFolder(context: Context, taskId: String): File = receivedTaskDir(context, taskId)

    suspend fun ensureRegistered(context: Context): org.json.JSONObject = withContext(Dispatchers.IO) {
        val request = DeviceRegistrationRequest(
            deviceId = deviceId(context),
            deviceName = getDeviceName(context),
            serial = android.os.Build.SERIAL,
            folderPath = deviceFolder(context).absolutePath,
            wifiIp = MirrorStateProbe.currentWifiIp(context),
            adbAuthorized = MirrorStateProbe.isAdbAuthorized(context),
            mirrorStatus = MirrorStateProbe.currentMirrorStatus(context),
            mirrorMessage = MirrorStateProbe.currentMirrorMessage(context),
            adbTcpPort = ApiSettings.getAdbTcpipPort(context),
            companionApiPort = ApiSettings.getListenPort(context),
            companionApiToken = ApiSettings.getToken(context),
            hardwareKey = hardwareKey(context),
            groupName = getBackendGroupName(context),
        )
        val url = "${resolveBaseUrl(context)}/api/devices/register"
        Log.d(TAG, "ensureRegistered -> $url request=$request")
        val reg = org.json.JSONObject(postJson(url, request))
        adoptCanonicalDeviceId(context, reg)
        setBackendGroupName(context, parseGroupNameFromRegistration(reg))
        reg
    }

    private fun isDeviceNotFoundError(t: Throwable): Boolean =
        t.message?.contains("device_not_found", ignoreCase = true) == true

    suspend fun createTask(
        context: Context,
        count: Int,
        targetPath: String? = DEFAULT_MATERIAL_STORAGE_PATH,
    ): CreateTaskResponse = withContext(Dispatchers.IO) {
        ensureRegistered(context)
        val resolved = resolvedTargetPath(targetPath)
        val req = CreateTaskRequest(deviceId(context), count, resolved)
        val url = "${resolveBaseUrl(context)}/api/tasks/create"
        Log.d(TAG, "createTask -> $url count=$count targetPath=$resolved request=$req")
        val raw =
            try {
                postJson(url, req)
            } catch (t: Throwable) {
                if (!isDeviceNotFoundError(t)) throw t
                Log.w(TAG, "createTask device_not_found, re-register and retry once")
                ensureRegistered(context)
                postJson(url, CreateTaskRequest(deviceId(context), count, resolved))
            }
        Log.d(TAG, "createTask <- response=$raw")
        gson.fromJson(raw, CreateTaskResponse::class.java)
    }

    /**
     * 调用 PC `POST /api/tasks/create`（立即返回 taskId），再轮询 `GET /api/tasks/{taskId}` 直至文件列表就绪。
     * 兼容旧版同步 create（响应已带 files）与新版后台 compose。
     */
    suspend fun createTaskAndWaitReady(
        context: Context,
        count: Int,
        targetPath: String? = DEFAULT_MATERIAL_STORAGE_PATH,
        pollTimeoutMs: Long = TASK_PREPARE_TIMEOUT_MS,
        pollIntervalMs: Long = TASK_PREPARE_POLL_MS,
    ): CreateTaskResponse = withContext(Dispatchers.IO) {
        val created = createTask(context, count, targetPath)
        val readyFiles = validTaskFiles(created.files)
        if (readyFiles.isNotEmpty()) {
            return@withContext created.copy(files = readyFiles)
        }
        Log.d(TAG, "createTaskAndWaitReady: taskId=${created.taskId} status=${created.status} files empty, polling…")
        waitForTaskFiles(context, created.taskId, created.companionPullNotified, pollTimeoutMs, pollIntervalMs)
    }

    /** PC 已创建任务但 compose 未完成时，轮询直至 [PcTaskDetailDto.files] 可用。 */
    suspend fun pollTaskUntilReady(
        context: Context,
        taskId: String,
        pollTimeoutMs: Long = TASK_PREPARE_TIMEOUT_MS,
        pollIntervalMs: Long = TASK_PREPARE_POLL_MS,
    ): PcTaskDetailDto = withContext(Dispatchers.IO) {
        val first = fetchTaskDetail(context, taskId)
        val firstFiles = validTaskFiles(first?.files.orEmpty())
        if (first != null && firstFiles.isNotEmpty()) {
            return@withContext first.copy(files = firstFiles)
        }
        val deadline = System.currentTimeMillis() + pollTimeoutMs
        var lastStatus = first?.status ?: "pending"
        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val detail = fetchTaskDetail(context, taskId)
            if (detail == null) {
                Log.d(TAG, "pollTaskUntilReady: taskId=$taskId detail=null, retry")
                continue
            }
            lastStatus = detail.status
            val statusLower = detail.status.lowercase()
            if (statusLower in terminalPcTaskStatuses) {
                val err = detail.error?.trim().orEmpty().ifEmpty { "pc_task_$statusLower" }
                error("task_prepare_failed: $err")
            }
            val files = validTaskFiles(detail.files.orEmpty())
            if (files.isNotEmpty()) {
                Log.d(TAG, "pollTaskUntilReady: taskId=$taskId ready status=$lastStatus files=${files.size}")
                return@withContext detail.copy(files = files)
            }
            Log.d(TAG, "pollTaskUntilReady: taskId=$taskId status=$lastStatus files=0, continue")
        }
        error("task_prepare_timeout: taskId=$taskId lastStatus=$lastStatus")
    }

    private suspend fun waitForTaskFiles(
        context: Context,
        taskId: String,
        companionPullNotified: Boolean?,
        pollTimeoutMs: Long,
        pollIntervalMs: Long,
    ): CreateTaskResponse {
        val detail = pollTaskUntilReady(context, taskId, pollTimeoutMs, pollIntervalMs)
        return CreateTaskResponse(
            taskId = detail.taskId,
            status = detail.status,
            files = detail.files.orEmpty(),
            companionPullNotified = companionPullNotified,
        )
    }

    private fun validTaskFiles(files: List<TaskFileInfo>): List<TaskFileInfo> =
        files.filter { it.fileName.isNotBlank() && it.md5.isNotBlank() && it.sizeBytes >= 0L }

    suspend fun triggerAdbConnect(context: Context, target: String): String = withContext(Dispatchers.IO) {
        requestPcWirelessPipeline(context, target, launchMirror = false)
    }

    /** 请求 PC：经 Companion 写入公钥 → adb connect →（可选）启动 scrcpy。 */
    suspend fun requestPcWirelessPipeline(
        context: Context,
        target: String? = null,
        launchMirror: Boolean = false,
        connectAll: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val ip = ApiSettings.getEffectiveMirrorLanIpv4(context)
        val port = ApiSettings.getAdbTcpipPort(context)
        val resolvedTarget = target?.trim().orEmpty().ifEmpty {
            if (ip != null) "$ip:$port" else ""
        }
        val url = "${resolveBaseUrl(context)}/api/mirror/start"
        val req = mutableMapOf<String, Any>(
            "autoConnectOnly" to true,
            "autoAuthorize" to true,
            "launchMirror" to launchMirror,
        )
        if (connectAll) {
            req["connectAll"] = true
        } else if (resolvedTarget.isNotEmpty()) {
            req["target"] = resolvedTarget
        }
        Log.d(TAG, "requestPcWirelessPipeline -> $url req=$req")
        postJson(url, req)
    }

    suspend fun getTaskStatus(context: Context, taskId: String): TaskStatusResponse = withContext(Dispatchers.IO) {
        val url = "${resolveBaseUrl(context)}/api/tasks/$taskId"
        Log.d(TAG, "getTaskStatus -> $url taskId=$taskId")
        val raw = get(url)
        Log.d(TAG, "getTaskStatus <- response=$raw")
        gson.fromJson(raw, TaskStatusResponse::class.java)
    }

    suspend fun fetchTaskDetail(context: Context, taskId: String): PcTaskDetailDto? = withContext(Dispatchers.IO) {
        val url = "${resolveBaseUrl(context)}/api/tasks/$taskId"
        Log.d(TAG, "fetchTaskDetail -> $url")
        val raw = runCatching { get(url) }.getOrElse {
            Log.w(TAG, "fetchTaskDetail failed: ${it.message}")
            return@withContext null
        }
        runCatching { gson.fromJson(raw, PcTaskDetailDto::class.java) }.getOrElse {
            Log.w(TAG, "fetchTaskDetail parse failed: ${it.message} raw=${raw.take(400)}")
            null
        }
    }

    suspend fun ackTask(context: Context, taskId: String, success: Boolean, message: String? = null, files: List<TaskFileAck> = emptyList()) = withContext(Dispatchers.IO) {
        val req = TaskAckRequest(success, message, files)
        val url = "${resolveBaseUrl(context)}/api/tasks/$taskId/ack"
        Log.d(TAG, "ackTask -> $url success=$success message=$message files=${files.size}")
        postJson(url, req)
    }

    suspend fun downloadAndVerifyFile(
        context: Context,
        taskId: String,
        file: TaskFileInfo,
        maxAttempts: Int = 3,
    ): File = withContext(Dispatchers.IO) {
        val folder = taskFolder(context, taskId)
        val finalFile = File(folder, file.fileName)
        val partFile = File(folder, "${file.fileName}.part")
        var lastError: Throwable? = null

        repeat(maxAttempts) { attempt ->
            try {
                if (partFile.exists()) partFile.delete()
                downloadFileOnce(context, taskId, file.fileName, partFile)
                verifyFile(partFile, file)
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                return@withContext finalFile
            } catch (t: Throwable) {
                lastError = t
                if (partFile.exists()) partFile.delete()
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(backoffMs(attempt))
                }
            }
        }

        throw lastError ?: IllegalStateException("download_failed")
    }

    private suspend fun downloadFileOnce(context: Context, taskId: String, fileName: String, destination: File) = withContext(Dispatchers.IO) {
        val url = "${resolveBaseUrl(context)}/api/tasks/$taskId/download/$fileName"
        Log.d(TAG, "downloadFileOnce -> $url destination=${destination.absolutePath}")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val bodyText = response.body?.string().orEmpty()
                Log.d(TAG, "downloadFileOnce <- code=${response.code} body=$bodyText")
                error("download_failed_${response.code}: $bodyText")
            }
            val body = response.body ?: error("empty_response_body")
            Log.d(TAG, "downloadFileOnce <- code=${response.code} streaming to ${destination.absolutePath}")
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun verifyFile(file: File, expected: TaskFileInfo) {
        if (!file.exists()) error("file_missing")
        if (file.length() != expected.sizeBytes) error("size_mismatch")
        val md5 = md5Hex(file)
        if (!md5.equals(expected.md5, ignoreCase = true)) error("md5_mismatch")
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun backoffMs(attempt: Int): Long = when (attempt) {
        0 -> 1_000L
        1 -> 2_000L
        else -> 4_000L
    }

    private fun postJson(url: String, body: Any): String {
        val payload = gson.toJson(body)
        Log.d(TAG, "POST $url payload=$payload")
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(json.toMediaType()))
            .build()

        apiClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.d(TAG, "POST $url <- code=${response.code} success=${response.isSuccessful} body=$text")
            if (!response.isSuccessful) error("request_failed_${response.code}: $text")
            return text
        }
    }

    private fun get(url: String): String {
        Log.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        apiClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.d(TAG, "GET $url <- code=${response.code} success=${response.isSuccessful} body=$text")
            if (!response.isSuccessful) error("request_failed_${response.code}: $text")
            return text
        }
    }
}
