package com.autotraces.app

import android.content.Context
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ApiService {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private lateinit var defaultBaseUrl: String

    fun init(context: Context) {
        defaultBaseUrl = context.getString(R.string.server_base_url).trimEnd('/')
    }

    fun getBaseUrl(): String {
        return PrefsStore.getServerBaseUrl()
            ?.takeIf { it.isNotBlank() }
            ?.trimEnd('/')
            ?: defaultBaseUrl
    }

    suspend fun login(username: String, password: String): AuthLoginResponse {
        val request = AuthLoginRequest(username, password)
        val candidates = listOf(
            "/api/auth/login",
            "/api/login",
            "/auth/login",
            "/login",
            "/api/user/login",
            "/user/login",
        )
        var lastError: Exception? = null
        for (path in candidates) {
            try {
                return post(
                    path = path,
                    body = request,
                    clazz = AuthLoginResponse::class.java,
                    includeAuth = false,
                )
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("login failed")
    }

    suspend fun bindDevice(alias: String, fingerprint: String): BindDeviceResponse = post(
        path = "/api/bind_device",
        body = BindDeviceRequest(alias, fingerprint),
        clazz = BindDeviceResponse::class.java,
        includeAuth = true,
    )

    suspend fun syncStatus(deviceId: String, alias: String, status: String): BaseResponse = post(
        path = "/api/sync_status",
        body = SyncStatusRequest(deviceId, alias, status),
        clazz = BaseResponse::class.java,
    )

    suspend fun getConfig(deviceId: String, alias: String, runMode: String? = null): ConfigResponse {
        val modeQ = runMode?.takeIf { it.isNotBlank() }?.let { "&run_mode=${encode(it)}" }.orEmpty()
        return get(
            path = "/api/get_config?device_id=${encode(deviceId)}&alias=${encode(alias)}$modeQ",
            clazz = ConfigResponse::class.java,
        )
    }

    suspend fun checkStart(deviceId: String, alias: String): CheckStartResponse = get(
        path = "/api/check_start?device_id=${encode(deviceId)}&alias=${encode(alias)}",
        clazz = CheckStartResponse::class.java,
    )

    suspend fun pullUsername(deviceId: String, alias: String, count: Int, runMode: String? = null): PullUsernameResponse {
        val modeQ = runMode?.takeIf { it.isNotBlank() }?.let { "&run_mode=${encode(it)}" }.orEmpty()
        return get(
            path = "/api/pull_username?device_id=${encode(deviceId)}&alias=${encode(alias)}&count=${count}$modeQ",
            clazz = PullUsernameResponse::class.java,
        )
    }

    suspend fun reportResult(deviceId: String, alias: String, result: String, runMode: String? = null): BaseResponse = post(
        path = "/api/report_result",
        body = ReportResultRequest(deviceId, alias, result, run_mode = runMode),
        clazz = BaseResponse::class.java,
    )

    suspend fun getCvTemplate(deviceId: String, alias: String): CvTemplateResponse = get(
        path = "/api/cv_template?device_id=${encode(deviceId)}&alias=${encode(alias)}",
        clazz = CvTemplateResponse::class.java,
    )

    suspend fun pollDeviceOps(deviceId: String, alias: String): DeviceOpPollResponse = post(
        path = "/api/device_ops/poll",
        body = DeviceOpPollRequest(deviceId, alias),
        clazz = DeviceOpPollResponse::class.java,
    )

    suspend fun reportDeviceOp(
        deviceId: String,
        alias: String,
        opId: String,
        status: String,
        message: String,
        data: Map<String, Any>? = null,
    ): BaseResponse = post(
        path = "/api/device_ops/report",
        body = DeviceOpReportRequest(deviceId, alias, opId, status, message, data),
        clazz = BaseResponse::class.java,
    )

    suspend fun syncDeviceLogs(deviceId: String, alias: String, logs: List<String>): BaseResponse = post(
        path = "/api/device_logs/sync",
        body = DeviceLogsSyncRequest(deviceId, alias, logs),
        clazz = BaseResponse::class.java,
    )

    suspend fun setDeviceAutoStartOptOut(deviceId: String, optOut: Boolean): DeviceAutoStartOptOutResponse = post(
        path = "/api/admin/device_auto_start_opt_out",
        body = DeviceAutoStartOptOutRequest(device_id = deviceId, opt_out = optOut),
        clazz = DeviceAutoStartOptOutResponse::class.java,
        includeAuth = true,
    )

    private suspend fun <T> get(path: String, clazz: Class<T>): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(getBaseUrl() + path).get().build()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $bodyText")
            }
            gson.fromJson(bodyText, clazz)
        }
    }

    private suspend fun <T> post(
        path: String,
        body: Any,
        clazz: Class<T>,
        includeAuth: Boolean = false,
    ): T = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(body)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(getBaseUrl() + path)
            .post(requestBody)
        if (includeAuth) {
            val t = PrefsStore.getAuthToken()
            if (!t.isNullOrBlank()) {
                builder.addHeader("Authorization", "Bearer $t")
            }
        }
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $bodyText")
            }
            gson.fromJson(bodyText, clazz)
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }
}
