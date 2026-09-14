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
        // mode_wait 是长轮询（默认 120s）。readTimeout 需要留足余量，否则会把“正常长轮询结束”
        // 误判成 SocketTimeoutException，导致日志里频繁出现 timeout。
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 绑定接口应快速返回；过长读超时会导致界面长期停在「正在绑定...」。 */
    private val bindClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .build()

    /**
     * auto_switch/bundle 会为桶内每个账号做智能模板解析，账号多时服务端常 >15s；
     * 若仍用默认 readTimeout=15s，设备端会误报 timeout（服务端其实已 200）。
     */
    private val autoSwitchBundleClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 设备日志同步可能携带数百行 JSON，写超时需长于默认 15s。 */
    private val deviceLogSyncClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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

    /** 设备端：当前别名桶下全部已分配账号（手动切号下拉）。 */
    suspend fun getDeviceAssignedAccounts(deviceId: String, alias: String): DeviceAssignedAccountsResponse = get(
        path = "/api/device/assigned_accounts?device_id=${encode(deviceId)}&alias=${encode(alias)}",
        clazz = DeviceAssignedAccountsResponse::class.java,
    )

    /**
     * 含「不参加自动切号」账号，供切换到云端模式前挑选参与名单。
     * include_banned=1 时附带 banned 账号（UI 只展示、不可选）。
     */
    suspend fun getDeviceAssignedAccountsRoster(
        deviceId: String,
        alias: String,
    ): DeviceAssignedAccountsResponse = get(
        path = "/api/device/assigned_accounts?device_id=${encode(deviceId)}&alias=${encode(alias)}" +
            "&include_excluded=1&include_banned=1",
        clazz = DeviceAssignedAccountsResponse::class.java,
    )

    suspend fun setDeviceAssignedAccountAutoSwitchExcluded(
        deviceId: String,
        alias: String,
        username: String,
        excluded: Boolean,
    ): DeviceAssignedAccountSetAutoSwitchExcludedResponse = post(
        path = "/api/device/assigned_account/set_auto_switch_excluded",
        body = DeviceAssignedAccountSetAutoSwitchExcludedRequest(
            device_id = deviceId,
            alias = alias,
            username = username,
            excluded = excluded,
        ),
        clazz = DeviceAssignedAccountSetAutoSwitchExcludedResponse::class.java,
    )

    suspend fun syncDeviceAssignedAccountsAutoSwitchParticipation(
        deviceId: String,
        alias: String,
        participateUsernames: List<String>? = null,
        enableDeviceAutoSwitch: Boolean? = null,
    ): DeviceAssignedAccountsSyncParticipationResponse = post(
        path = "/api/device/assigned_accounts/sync_auto_switch_participation",
        body = DeviceAssignedAccountsSyncParticipationRequest(
            device_id = deviceId,
            alias = alias,
            participate_usernames = participateUsernames,
            enable_device_auto_switch = enableDeviceAutoSwitch,
        ),
        clazz = DeviceAssignedAccountsSyncParticipationResponse::class.java,
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

    suspend fun markDeviceAssignedAccountBanned(
        deviceId: String,
        alias: String,
        username: String,
    ): DeviceAssignedAccountMarkBannedResponse = post(
        path = "/api/device/assigned_account/mark_banned",
        body = DeviceAssignedAccountMarkBannedRequest(deviceId, alias, username),
        clazz = DeviceAssignedAccountMarkBannedResponse::class.java,
    )

    suspend fun markDeviceAssignedAccountMaxLogin(
        deviceId: String,
        alias: String,
        username: String,
    ): DeviceAssignedAccountMarkBannedResponse = post(
        path = "/api/device/assigned_account/mark_max_login",
        body = DeviceAssignedAccountMarkMaxLoginRequest(deviceId, alias, username),
        clazz = DeviceAssignedAccountMarkBannedResponse::class.java,
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

    suspend fun updateDeviceAssignedAccountEmail(
        deviceId: String,
        alias: String,
        username: String,
        email: String,
        emailPassword: String,
        clearApi: Boolean = true,
    ): DeviceAssignedAccountUpdateEmailResponse = post(
        path = "/api/device/assigned_account/update_email",
        body = DeviceAssignedAccountUpdateEmailRequest(
            device_id = deviceId,
            alias = alias,
            username = username,
            email = email,
            email_password = emailPassword,
            clear_api = clearApi,
        ),
        clazz = DeviceAssignedAccountUpdateEmailResponse::class.java,
    )

    suspend fun acquireTkPendingEmail(
        deviceId: String,
        alias: String,
        email: String = "",
    ): DeviceTkPendingAcquireResponse = post(
        path = "/api/device/tk_pending/acquire",
        body = mapOf(
            "device_id" to deviceId,
            "alias" to alias,
            "email" to email,
        ),
        clazz = DeviceTkPendingAcquireResponse::class.java,
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
                return postWithClient(
                    longTimeoutClient,
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

    suspend fun getTikTokTwoFaCodeFromServer(
        deviceId: String,
        alias: String,
        username: String = "",
        secret: String = "",
    ): TikTokTwoFaCodeResponse {
        val body = mapOf(
            "device_id" to deviceId,
            "alias" to alias,
            "username" to username,
            "secret" to secret,
        )
        val paths = listOf(
            "/api/device/tiktok/two_fa_code",
            "/api/admin/device_tiktok/two_fa_code",
        )
        var lastError: Exception? = null
        for (path in paths) {
            try {
                return postWithClient(
                    longTimeoutClient,
                    path = path,
                    body = body,
                    clazz = TikTokTwoFaCodeResponse::class.java,
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
        throw lastError ?: IOException("云端代取 2FA 失败")
    }

    suspend fun pollDeviceOps(deviceId: String, alias: String): DeviceOpPollResponse = postWithClient(
        pollClient,
        path = "/api/device_ops/poll",
        body = DeviceOpPollRequest(deviceId, alias),
        clazz = DeviceOpPollResponse::class.java,
    )

    suspend fun modeWait(
        deviceId: String,
        alias: String,
        sinceEventId: Int = 0,
        timeoutSec: Int = 120,
    ): DeviceModeWaitResponse = getWithClient(
        longTimeoutClient,
        path = "/api/device/mode_wait?device_id=${encode(deviceId)}&alias=${encode(alias)}"
            + "&since_event_id=${sinceEventId.coerceAtLeast(0)}&timeout_sec=${timeoutSec.coerceIn(5, 180)}",
        clazz = DeviceModeWaitResponse::class.java,
    )

    suspend fun modeAck(
        deviceId: String,
        alias: String,
        eventId: Int,
        phase: String,
        sessionId: String,
    ): BaseResponse = post(
        path = "/api/device/mode_ack",
        body = DeviceModeAckRequest(deviceId, alias, eventId, phase, sessionId),
        clazz = BaseResponse::class.java,
    )

    suspend fun fetchAutoSwitchBundle(
        deviceId: String,
        alias: String,
        sessionId: String = "",
        revision: Int = 0,
    ): AutoSwitchBundleResponse = getWithClient(
        autoSwitchBundleClient,
        path = "/api/auto_switch/bundle?device_id=${encode(deviceId)}&alias=${encode(alias)}"
            + "&session_id=${encode(sessionId)}&revision=${revision.coerceAtLeast(0)}",
        clazz = AutoSwitchBundleResponse::class.java,
    )

    suspend fun postAutoSwitchSessionSummary(
        body: AutoSwitchSessionSummaryRequest,
    ): AutoSwitchSessionSummaryResponse = post(
        path = "/api/auto_switch/session_summary",
        body = body,
        clazz = AutoSwitchSessionSummaryResponse::class.java,
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

    suspend fun syncDeviceLogs(
        deviceId: String,
        alias: String,
        logs: List<String>,
        replace: Boolean = false,
    ): BaseResponse = postWithClient(
        deviceLogSyncClient,
        path = "/api/device_logs/sync",
        body = DeviceLogsSyncRequest(deviceId, alias, logs, replace),
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
        executeWithTransientRetry {
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
        executeWithTransientRetry {
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
    }

    private fun isTransientNetworkError(err: Throwable): Boolean {
        val msg = err.message.orEmpty().lowercase()
        return err is java.io.EOFException ||
            msg.contains("unexpected end of stream") ||
            msg.contains("connection reset") ||
            msg.contains("connection abort") ||
            (msg.contains("connection") && msg.contains("closed")) ||
            msg.contains("broken pipe")
    }

    private inline fun <T> executeWithTransientRetry(block: () -> T): T {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (err: Throwable) {
                lastError = err
                if (!isTransientNetworkError(err) || attempt >= 2) throw err
                Thread.sleep(1_000L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("transient network retry exhausted")
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }
}
