package com.autotraces.app.videoded

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

object CompanionApi {
    private const val TAG = "CompanionApi"
    private const val PREFS = "video_ded"

    /** 与外部业务（如 adb 统计相册图片）对齐的素材落盘目录。 */
    const val DEFAULT_MATERIAL_STORAGE_PATH = "/storage/emulated/0/Pictures"

    fun resolvedTargetPath(targetPath: String?): String =
        targetPath?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MATERIAL_STORAGE_PATH
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_BACKEND_GROUP = "backend_group_name"
    private const val KEY_MATERIAL_USERNAME = "material_username"
    private const val KEY_WINDOWS_BASE_URL = "windows_base_url"
    private const val DEFAULT_WINDOWS_BASE_URL = "http://192.168.1.10:5088"
    private val gson = Gson()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun normalizeUsername(raw: String?): String =
        raw?.trim()?.trimStart('@').orEmpty()

    fun getMaterialUsername(context: Context): String =
        normalizeUsername(prefs(context).getString(KEY_MATERIAL_USERNAME, null))

    fun setMaterialUsername(context: Context, username: String?) {
        val normalized = normalizeUsername(username)
        val editor = prefs(context).edit()
        if (normalized.isEmpty()) editor.remove(KEY_MATERIAL_USERNAME) else editor.putString(KEY_MATERIAL_USERNAME, normalized)
        editor.apply()
    }

    /**
     * PC 素材文件夹按 TikTok 用户名绑定。
     * [preferred] 优先（通常为当前发作品账号）；否则用已缓存的 material_username。
     * [rejectEquals]：与之相等的值视为无效（常见为设备 alias 脏数据），命中缓存时会清空。
     */
    fun resolveMaterialUsername(
        context: Context,
        preferred: String? = null,
        rejectEquals: String? = null,
    ): String {
        val reject = normalizeUsername(rejectEquals)
        fun acceptable(value: String): Boolean =
            value.isNotEmpty() && (reject.isEmpty() || !value.equals(reject, ignoreCase = true))

        val preferredN = normalizeUsername(preferred)
        if (acceptable(preferredN)) return preferredN

        val cached = getMaterialUsername(context)
        if (acceptable(cached)) return cached
        if (cached.isNotEmpty() && reject.isNotEmpty() && cached.equals(reject, ignoreCase = true)) {
            setMaterialUsername(context, null)
        }
        return ""
    }

    private fun okhttpBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()

    private val apiClient = okhttpBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadClient = okhttpBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val json = "application/json; charset=utf-8"

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
        return "设备名称：$name · $group"
    }

    private fun parseGroupNameFromRegistration(reg: org.json.JSONObject): String? {
        if (!reg.has("groupName") || reg.isNull("groupName")) return null
        return normalizeGroupName(reg.optString("groupName", ""))
    }

    fun windowsBaseUrl(context: Context): String =
        prefs(context).getString(KEY_WINDOWS_BASE_URL, DEFAULT_WINDOWS_BASE_URL)!!

    fun setWindowsBaseUrl(context: Context, value: String) {
        val normalized = value.trim().trimEnd('/')
        prefs(context).edit().putString(KEY_WINDOWS_BASE_URL, normalized).apply()
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
        val url = "${windowsBaseUrl(context)}/api/devices/register"
        val reg = org.json.JSONObject(postJson(url, request))
        adoptCanonicalDeviceId(context, reg)
        setBackendGroupName(context, parseGroupNameFromRegistration(reg))
        applyPcAdbPublicKeyFromRegistration(reg)
        reg
    }

    private @Volatile var lastAppliedAdbKeyPrefix: String? = null

    /**
     * PC 在注册响应里下发本机 adbkey.pub：Root Companion 写入 /data/misc/adb/adb_keys，
     * 用于 USB 仍为 unauthorized、无法 adb forward 时的自动授权。
     */
    private fun applyPcAdbPublicKeyFromRegistration(reg: org.json.JSONObject) {
        val key = reg.optString("adbPublicKey", "").trim()
            .ifEmpty { reg.optString("adb_public_key", "").trim() }
        if (key.isEmpty()) return
        val prefix = key.take(48)
        if (prefix == lastAppliedAdbKeyPrefix) return
        val result = AdbWirelessSetup.ensurePublicKey(key)
        Log.i(TAG, "applyPcAdbPublicKey: ${result.detail}")
        if (result.keyInstalled || result.keyAlreadyPresent) {
            lastAppliedAdbKeyPrefix = prefix
        }
    }

    suspend fun createTask(
        context: Context,
        count: Int,
        targetPath: String? = DEFAULT_MATERIAL_STORAGE_PATH,
        username: String? = null,
    ): CreateTaskResponse = withContext(Dispatchers.IO) {
        ensureRegistered(context)
        val resolved = resolvedTargetPath(targetPath)
        val resolvedUsername = resolveMaterialUsername(context, username)
        if (resolvedUsername.isEmpty()) {
            throw IllegalArgumentException("username_required")
        }
        setMaterialUsername(context, resolvedUsername)
        val req = CreateTaskRequest(deviceId(context), count, resolved, resolvedUsername)
        val url = "${windowsBaseUrl(context)}/api/tasks/create"
        val raw = postJson(url, req)
        Log.i(TAG, "createTask ok count=$count username=$resolvedUsername")
        gson.fromJson(raw, CreateTaskResponse::class.java)
    }

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
        // 无本机目标且未显式 connectAll 时不要打 PC：服务端会误选「最近一台」别的设备
        if (!connectAll && resolvedTarget.isEmpty()) {
            Log.w(TAG, "requestPcWirelessPipeline skipped: no local Wi‑Fi target")
            return@withContext """{"ok":false,"error":"no_local_wifi_target"}"""
        }
        val url = "${windowsBaseUrl(context)}/api/mirror/start"
        val req = mutableMapOf<String, Any>(
            "autoConnectOnly" to true,
            "autoAuthorize" to true,
            "launchMirror" to launchMirror,
        )
        if (connectAll) {
            req["connectAll"] = true
        } else {
            req["target"] = resolvedTarget
        }
        Log.d(TAG, "requestPcWirelessPipeline -> $url target=$resolvedTarget launchMirror=$launchMirror")
        postJson(url, req)
    }

    suspend fun getTaskStatus(context: Context, taskId: String): TaskStatusResponse = withContext(Dispatchers.IO) {
        val url = "${windowsBaseUrl(context)}/api/tasks/$taskId"
        val raw = get(url)
        gson.fromJson(raw, TaskStatusResponse::class.java)
    }

    suspend fun fetchTaskDetail(context: Context, taskId: String): PcTaskDetailDto? = withContext(Dispatchers.IO) {
        val url = "${windowsBaseUrl(context)}/api/tasks/$taskId"
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
        val url = "${windowsBaseUrl(context)}/api/tasks/$taskId/ack"
        postJson(url, req)
        Log.i(TAG, "ackTask taskId=$taskId success=$success files=${files.size}")
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
        val url = "${windowsBaseUrl(context)}/api/tasks/$taskId/download/$fileName"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val bodyText = response.body?.string().orEmpty()
                Log.w(TAG, "downloadFileOnce failed code=${response.code} body=${bodyText.take(200)}")
                error("download_failed_${response.code}: $bodyText")
            }
            val body = response.body ?: error("empty_response_body")
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun verifyLocalFile(file: File, expected: TaskFileInfo) {
        if (!file.exists()) error("file_missing:${file.absolutePath}")
        if (file.length() != expected.sizeBytes) error("size_mismatch")
        val md5 = md5Hex(file)
        if (!md5.equals(expected.md5, ignoreCase = true)) error("md5_mismatch")
    }

    private fun verifyFile(file: File, expected: TaskFileInfo) = verifyLocalFile(file, expected)

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
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(json.toMediaType()))
            .build()

        apiClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "POST $url failed code=${response.code} body=${text.take(300)}")
                error("request_failed_${response.code}: $text")
            }
            return text
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        apiClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "GET $url failed code=${response.code} body=${text.take(300)}")
                error("request_failed_${response.code}: $text")
            }
            return text
        }
    }
}
