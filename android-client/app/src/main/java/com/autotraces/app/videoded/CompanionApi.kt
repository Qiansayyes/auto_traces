package com.autotraces.app.videoded

import android.content.Context
import android.util.Log
import com.autotraces.app.PrefsStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
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

object CompanionApi {
    private const val TAG = "CompanionApi"
    private const val PREFS = "video_ded_embedded"

    /** 与外部业务（如 adb 统计相册图片）对齐的素材落盘目录。 */
    const val DEFAULT_MATERIAL_STORAGE_PATH = "/storage/emulated/0/Pictures"

    fun resolvedTargetPath(targetPath: String?): String =
        targetPath?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MATERIAL_STORAGE_PATH
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_BACKEND_GROUP = "backend_group_name"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private const val json = "application/json; charset=utf-8"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DEVICE_ID, null)
        if (stored != null) return stored
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
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
        return "设备名称：$name · $group"
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

    fun setWindowsBaseUrl(context: Context, value: String) {
        val normalized = value.trim().trimEnd('/')
        Log.d(TAG, "setWindowsBaseUrl=$normalized")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("windows_base_url", normalized)
            .apply()
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
        )
        val url = "${windowsBaseUrl(context)}/api/devices/register"
        Log.d(TAG, "ensureRegistered -> $url request=$request")
        val reg = org.json.JSONObject(postJson(url, request))
        setBackendGroupName(context, parseGroupNameFromRegistration(reg))
        reg
    }

    suspend fun createTask(
        context: Context,
        count: Int,
        targetPath: String? = DEFAULT_MATERIAL_STORAGE_PATH,
    ): CreateTaskResponse = withContext(Dispatchers.IO) {
        val resolved = resolvedTargetPath(targetPath)
        val req = CreateTaskRequest(deviceId(context), count, resolved)
        val url = "${windowsBaseUrl(context)}/api/tasks/create"
        Log.d(TAG, "createTask -> $url count=$count targetPath=$resolved request=$req")
        val raw = postJson(url, req)
        Log.d(TAG, "createTask <- response=$raw")
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
        val url = "${windowsBaseUrl(context)}/api/mirror/start"
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
        val url = "${windowsBaseUrl(context)}/api/tasks/$taskId"
        Log.d(TAG, "getTaskStatus -> $url taskId=$taskId")
        val raw = get(url)
        Log.d(TAG, "getTaskStatus <- response=$raw")
        gson.fromJson(raw, TaskStatusResponse::class.java)
    }

    suspend fun fetchTaskDetail(context: Context, taskId: String): PcTaskDetailDto? = withContext(Dispatchers.IO) {
        val url = "${windowsBaseUrl(context)}/api/tasks/$taskId"
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
        val url = "${windowsBaseUrl(context)}/api/tasks/$taskId/ack"
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
        val url = "${windowsBaseUrl(context)}/api/tasks/$taskId/download/$fileName"
        Log.d(TAG, "downloadFileOnce -> $url destination=${destination.absolutePath}")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
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

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.d(TAG, "POST $url <- code=${response.code} success=${response.isSuccessful} body=$text")
            if (!response.isSuccessful) error("request_failed_${response.code}: $text")
            return text
        }
    }

    private fun get(url: String): String {
        Log.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.d(TAG, "GET $url <- code=${response.code} success=${response.isSuccessful} body=$text")
            if (!response.isSuccessful) error("request_failed_${response.code}: $text")
            return text
        }
    }
}
