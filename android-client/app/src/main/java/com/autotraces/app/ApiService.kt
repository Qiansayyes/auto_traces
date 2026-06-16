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

    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val longTimeoutClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 绑定接口应快速返回；过长读超时会导致界面长期停在「正在绑定...」。 */
    private val bindClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
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

    suspend fun bindDevice(alias: String, fingerprint: String): BindDeviceResponse =
        postWithClient(
            bindClient,
            "/api/bind_device",
            BindDeviceRequest(alias, fingerprint),
            BindDeviceResponse::class.java,
            includeAuth = true,
        )

    /** 本地别名/device_id 丢失或与云端不一致时，按指纹拉取服务端在册绑定。 */
    suspend fun lookupDeviceBinding(fingerprint: String): DeviceBindingResponse =
        getWithClient(
            bindClient,
            "/api/device_binding?device_fingerprint=${encode(fingerprint)}",
            DeviceBindingResponse::class.java,
            includeAuth = true,
        )

    suspend fun syncStatus(deviceId: String, alias: String, status: String): BaseResponse = post(
        path = "/api/sync_status",
        body = SyncStatusRequest(deviceId, alias, status),
        clazz = BaseResponse::class.java,
    )

    suspend fun getConfig(
        deviceId: String,
        alias: String,
        runMode: String? = null,
        tiktokUsername: String? = null,
    ): ConfigResponse {
        val modeQ = runMode?.takeIf { it.isNotBlank() }?.let { "&run_mode=${encode(it)}" }.orEmpty()
        val accountQ = tiktokUsername?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "&tiktok_username=${encode(it)}" }
            .orEmpty()
        return get(
            path = "/api/get_config?device_id=${encode(deviceId)}&alias=${encode(alias)}$modeQ$accountQ",
            clazz = ConfigResponse::class.java,
        )
    }

    suspend fun checkStart(deviceId: String, alias: String): CheckStartResponse = getWithClient(
        pollClient,
        path = "/api/check_start?device_id=${encode(deviceId)}&alias=${encode(alias)}",
        clazz = CheckStartResponse::class.java,
    )

    suspend fun pullUsername(
        deviceId: String,
        alias: String,
        count: Int,
        runMode: String? = null,
        tiktokUsername: String? = null,
    ): PullUsernameResponse {
        val modeQ = runMode?.takeIf { it.isNotBlank() }?.let { "&run_mode=${encode(it)}" }.orEmpty()
        val accountQ = tiktokUsername?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "&tiktok_username=${encode(it)}" }
            .orEmpty()
        return getWithClient(
            pollClient,
            path = "/api/pull_username?device_id=${encode(deviceId)}&alias=${encode(alias)}&count=${count}$modeQ$accountQ",
            clazz = PullUsernameResponse::class.java,
        )
    }

    suspend fun reportResult(deviceId: String, alias: String, result: String, runMode: String? = null): BaseResponse = post(
        path = "/api/report_result",
        body = ReportResultRequest(deviceId, alias, result, run_mode = runMode),
        clazz = BaseResponse::class.java,
    )

    suspend fun reportMutualCopiedUsername(deviceId: String, alias: String, username: String): ReportMutualCopiedUsernameResponse =
        post(
            path = "/api/report_mutual_copied_username",
            body = ReportMutualCopiedUsernameRequest(deviceId, alias, username),
            clazz = ReportMutualCopiedUsernameResponse::class.java,
        )

    suspend fun getCvTemplate(deviceId: String, alias: String): CvTemplateResponse = get(
        path = "/api/cv_template?device_id=${encode(deviceId)}&alias=${encode(alias)}",
        clazz = CvTemplateResponse::class.java,
    )

    suspend fun getDeviceAssignedAccount(deviceId: String, alias: String): DeviceAssignedAccountResponse = get(
        path = "/api/device/assigned_account?device_id=${encode(deviceId)}&alias=${encode(alias)}",
        clazz = DeviceAssignedAccountResponse::class.java,
    )

    suspend fun fetchRandomGalleryLibraryItem(deviceId: String, alias: String): GalleryLibraryRandomResponse =
        getWithClient(
            longTimeoutClient,
            "/api/gallery_library/random?device_id=${encode(deviceId)}&alias=${encode(alias)}",
            GalleryLibraryRandomResponse::class.java,
            includeAuth = false,
        )

    suspend fun markDeviceAssignedAccountLogin(
        deviceId: String,
        alias: String,
        username: String,
    ): BaseResponse = post(
        path = "/api/device/assigned_account/mark_login",
        body = DeviceAssignedAccountMarkLoginRequest(deviceId, alias, username),
        clazz = BaseResponse::class.java,
    )

    suspend fun updateDeviceAssignedAccountUsername(
        deviceId: String,
        alias: String,
        newUsername: String,
    ): DeviceAssignedAccountUpdateUsernameResponse = post(
        path = "/api/device/assigned_account/update_username",
        body = DeviceAssignedAccountUpdateUsernameRequest(deviceId, alias, newUsername),
        clazz = DeviceAssignedAccountUpdateUsernameResponse::class.java,
    )

    suspend fun getTikTokEmailCodeFromServer(
        deviceId: String,
        alias: String,
        apiUrl: String,
        email: String,
    ): TikTokEmailCodeResponse {
        val body = mapOf(
            "device_id" to deviceId,
            "alias" to alias,
            "email" to email,
            "api_url" to apiUrl,
        )
        val paths = listOf(
            "/api/device/tiktok/email_code",
            "/api/admin/device_tiktok/email_code",
        )
        var lastError: Exception? = null
        for (path in paths) {
            try {
                return post(
                    path = path,
                    body = body,
                    clazz = TikTokEmailCodeResponse::class.java,
                    includeAuth = path.startsWith("/api/admin/"),
                )
            } catch (e: Exception) {
                lastError = e
                val http404 = e is IOException && e.message?.contains("HTTP 404") == true
                if (!http404) {
                    throw e
                }
            }
        }
        throw lastError ?: IOException("云端代取失败")
    }

    suspend fun pollDeviceOps(deviceId: String, alias: String): DeviceOpPollResponse = postWithClient(
        pollClient,
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

    suspend fun requestMutualCommentOnlyClick(deviceId: String, alias: String, packageName: String): BaseResponse = post(
        path = "/api/admin/device_tiktok/mutual_click_comment_only_start",
        body = MutualClickRequest(device_id = deviceId, alias = alias, package_name = packageName),
        clazz = BaseResponse::class.java,
        includeAuth = true,
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

    /** 设备鉴权：上报本设备是否参与云端「自动开启程序」（硬停、发作品、修改资料等）。 */
    suspend fun reportDeviceAutoStartOptOut(deviceId: String, alias: String, optOut: Boolean): BaseResponse = post(
        path = "/api/report_device_auto_start_opt_out",
        body = ReportDeviceAutoStartOptOutRequest(device_id = deviceId, alias = alias, opt_out = optOut),
        clazz = BaseResponse::class.java,
        includeAuth = false,
    )

    suspend fun getDeviceTikTokAccountsByAlias(mobileAlias: String): DeviceTikTokAccountsByAliasResponse {
        val alias = mobileAlias.trim()
        return get(
            path = "/api/admin/device_tiktok/accounts/${encode(alias)}",
            clazz = DeviceTikTokAccountsByAliasResponse::class.java,
            includeAuth = true,
        )
    }

    suspend fun getDeviceEditorRows(): DeviceEditorRowsResponse = get(
        path = "/api/admin/editor/device_rows",
        clazz = DeviceEditorRowsResponse::class.java,
        includeAuth = true,
    )

    suspend fun getAccountEditorRows(): AccountEditorRowsResponse = get(
        path = "/api/admin/editor/account_rows",
        clazz = AccountEditorRowsResponse::class.java,
        includeAuth = true,
    )

    suspend fun reportBatchStreamRow(
        deviceId: String,
        alias: String,
        sessionId: String,
        sheetRow: Int,
        statusText: String,
        hoursActive: Double,
    ): BaseResponse = post(
        path = "/api/device/batch_stream/report_row",
        body = BatchStreamReportRowRequest(
            device_id = deviceId,
            alias = alias,
            session_id = sessionId,
            sheet_row = sheetRow,
            status_text = statusText,
            hours_active = hoursActive,
        ),
        clazz = BaseResponse::class.java,
    )

    suspend fun completeBatchStream(deviceId: String, alias: String): BaseResponse = post(
        path = "/api/device/batch_stream/complete",
        body = BatchStreamCompleteRequest(device_id = deviceId, alias = alias),
        clazz = BaseResponse::class.java,
    )

    private suspend fun <T> get(
        path: String,
        clazz: Class<T>,
        includeAuth: Boolean = false,
    ): T = getWithClient(client, path, clazz, includeAuth)

    private suspend fun <T> getWithClient(
        httpClient: OkHttpClient,
        path: String,
        clazz: Class<T>,
        includeAuth: Boolean = false,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(getBaseUrl() + path).get()
        if (includeAuth) {
            val t = PrefsStore.getAuthToken()
            if (!t.isNullOrBlank()) {
                builder.addHeader("Authorization", "Bearer $t")
            }
        }
        val request = builder.build()
        httpClient.newCall(request).execute().use { response ->
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
    ): T = postWithClient(client, path, body, clazz, includeAuth)

    private suspend fun <T> postWithClient(
        httpClient: OkHttpClient,
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
        httpClient.newCall(request).execute().use { response ->
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
