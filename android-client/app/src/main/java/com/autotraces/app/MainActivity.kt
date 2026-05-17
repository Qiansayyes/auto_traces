package com.autotraces.app

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** API 34+ 读剪贴板；部分编译环境未生成 [Manifest.permission.READ_CLIPBOARD] 符号，用字面量避免编译失败。 */
private const val PERM_READ_CLIPBOARD = "android.permission.READ_CLIPBOARD"

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    // 备份/恢复 UI 已从「操作」页移除
    private var assignedAccountRows: List<AccountEditorRowRecord> = emptyList()
    private var assignedUsernameAdapter: ArrayAdapter<String>? = null
    private var suppressAssignedUsernameSelectCallback: Boolean = false

    private val codeFetchHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        requestReadClipboardPermissionIfNeeded()
        setupBindingSection()
        setupRunModeSpinner()
        setupAssignedAccountSection()
        renderDeviceInfo()
        observeLogs()

        binding.startServiceButton.setOnClickListener {
            lifecycleScope.launch {
                val alias = PrefsStore.getAlias()
                LogCenter.op(alias, "已请求启动后台服务")
                runCatching { WorkerService.startResumingAutomation(this@MainActivity) }.onFailure { error ->
                    LogCenter.op(alias, "启动后台服务请求失败：${error.message}")
                    AppCoordinator.refreshRuntimeSnapshot()
                    return@launch
                }
                delay(1200)
                AppCoordinator.refreshRuntimeSnapshot()
                if (WorkerServiceTracker.isRunning) {
                    AppCoordinator.forceStartRunningNow("设备端点击启动后台服务")
                    LogCenter.op(alias, "后台服务已启动")
                } else {
                    LogCenter.op(alias, "后台服务未能启动")
                }
                AppCoordinator.refreshRuntimeSnapshot()
            }
        }

        binding.stopServiceButton.setOnClickListener {
            lifecycleScope.launch {
                val alias = PrefsStore.getAlias().orEmpty()
                AppCoordinator.requestUserGracefulHardStop()
                LogCenter.op(
                    alias,
                    "已请求硬停：当前步骤结束后停下自动任务；后台继续轮询 check_start / 设备指令；已请求云端关闭本机自动开启",
                )
                runCatching { WorkerService.start(this@MainActivity) }
                    .onFailure { err ->
                        LogCenter.op(alias, "硬停后确保后台服务失败：${err.message}")
                    }
                AppCoordinator.startIfNeeded()
                delay(600)
                AppCoordinator.refreshRuntimeSnapshot()
            }
        }

        binding.openSettingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.showSettingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.showLogButton.setOnClickListener {
            binding.logPanel.visibility = View.VISIBLE
            binding.actionPanel.visibility = View.GONE
            scrollLogToLatest()
        }
        binding.showActionButton.setOnClickListener {
            binding.logPanel.visibility = View.GONE
            binding.actionPanel.visibility = View.VISIBLE
        }
        binding.clearLogButton.setOnClickListener {
            LogCenter.clear()
            LogCenter.op(PrefsStore.getAlias(), "日志已清空")
        }

        if (requireCloudLoginIfNeeded()) return
        applyInitialPanelFromIntent()

        if (!PrefsStore.isHardStopRequested()) {
            AppCoordinator.refreshRuntimeSnapshot()
        } else {
            AppCoordinator.refreshRuntimeSnapshot()
            lifecycleScope.launch {
                if (PrefsStore.isBound()) {
                    runCatching { WorkerService.start(this@MainActivity) }
                    AppCoordinator.startIfNeeded()
                }
                LogCenter.op(
                    PrefsStore.getAlias(),
                    "当前为硬停：用户名任务已暂停，后台仍轮询云端指令",
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (requireCloudLoginIfNeeded()) {
            renderDeviceInfo()
            return
        }
        ensureBoundIfNeeded()
        renderDeviceInfo()
        clarifyStaleBindErrorIfNeeded()
        refreshAssignedAccountFields()
    }

    /** 「重新绑定」失败后文案仍留在界面，容易误以为未绑定；已与 prefs/get_config 语义对齐时给予说明。 */
    private fun clarifyStaleBindErrorIfNeeded() {
        if (!PrefsStore.isBound() || PrefsStore.getAuthToken().isNullOrBlank()) return
        val hint = binding.bindStatusText.text?.toString().orEmpty()
        if (!hint.startsWith("绑定失败")) return
        if (hint.contains("本地仍为已绑定")) return
        binding.bindStatusText.text =
            "已绑定（Device ID 已保存）。上次失败多为「重新绑定」请求超时；服务端日志里 get_config 为 200 即仍视为在册。"
    }

    private fun refreshAssignedAccountFields() {
        val alias = PrefsStore.getAlias().orEmpty().trim()
        if (alias.isBlank() || PrefsStore.getAuthToken().isNullOrBlank()) {
            PrefsStore.setLogContextUsername(null)
            assignedAccountRows = emptyList()
            assignedUsernameAdapter?.clear()
            binding.assignedUsernameInput.setText("")
            binding.assignedPasswordInput.setText("")
            binding.assignedApiInput.setText("")
            binding.assignedEmailInput.setText("")
            binding.assignedEmailPasswordInput.setText("")
            binding.assignedCopyStatusText.text = "未登录或未绑定，无法拉取分配账号"
            return
        }
        binding.assignedCopyStatusText.text = "正在拉取账号表单..."
        lifecycleScope.launch {
            val accountRowsResp = runCatching { ApiService.getAccountEditorRows() }.getOrNull()
            val editorRowsResp = runCatching { ApiService.getDeviceEditorRows() }.getOrNull()

            val accountRows = accountRowsResp?.items.orEmpty()
            val editorRows = editorRowsResp?.items.orEmpty()

            assignedAccountRows = accountRows.filter { !it.username.isNullOrBlank() }
            val aliasNorm = alias.trim()
            fun effectiveDeviceAlias(r: DeviceEditorRowRecord): String {
                val aa = r.assigned_alias?.trim().orEmpty()
                return if (aa.isNotEmpty()) aa else r.device_alias?.trim().orEmpty()
            }
            fun isCutSwitchHighlight(r: DeviceEditorRowRecord) =
                r.switch_highlight?.trim() == "是"

            val matchedRows = editorRows
                .asSequence()
                .filter { effectiveDeviceAlias(it) == aliasNorm }
                .sortedWith(
                    compareByDescending<DeviceEditorRowRecord> { isCutSwitchHighlight(it) }
                        .thenBy { it.row_index ?: Int.MAX_VALUE },
                )
                .toList()

            val highlightedUsername = matchedRows
                .firstOrNull { isCutSwitchHighlight(it) }
                ?.username
                ?.trim()
                .orEmpty()
            val usernames = buildList {
                if (highlightedUsername.isNotBlank()) add(highlightedUsername)
                matchedRows
                    .asSequence()
                    .mapNotNull { it.username?.trim() }
                    .filter { it.isNotBlank() }
                    .filter { it != highlightedUsername }
                    .forEach { add(it) }
            }

            suppressAssignedUsernameSelectCallback = true
            assignedUsernameAdapter?.clear()
            assignedUsernameAdapter?.addAll(usernames)
            assignedUsernameAdapter?.notifyDataSetChanged()
            binding.assignedUsernameSelect.isEnabled = usernames.isNotEmpty()

            if (usernames.isNotEmpty()) {
                val selectedUsername = usernames.first()
                binding.assignedUsernameSelect.setSelection(0)
                applyAssignedAccountByUsername(selectedUsername)
                val ctxUser = when {
                    highlightedUsername.isNotBlank() -> highlightedUsername
                    else -> selectedUsername
                }
                PrefsStore.setLogContextUsername(ctxUser.trim().takeIf { it.isNotEmpty() })
            } else {
                PrefsStore.setLogContextUsername(null)
                applyAssignedAccountByUsername("")
                binding.assignedCopyStatusText.text = "该别名暂无分配账号：$alias"
            }
            suppressAssignedUsernameSelectCallback = false
        }
    }

    private fun applyAssignedAccountByUsername(username: String) {
        val u = username.trim().lowercase()
        val account = assignedAccountRows.firstOrNull {
            it.username.orEmpty().trim().lowercase() == u
        }
        if (account == null) {
            binding.assignedUsernameInput.setText("")
            binding.assignedPasswordInput.setText("")
            binding.assignedApiInput.setText("")
            binding.assignedEmailInput.setText("")
            binding.assignedEmailPasswordInput.setText("")
            binding.assignedCopyStatusText.text = "未找到该 username 对应的账号表单数据：$username"
            return
        }
        binding.assignedUsernameInput.setText(account.username.orEmpty())
        binding.assignedPasswordInput.setText(account.password.orEmpty())
        binding.assignedApiInput.setText(account.api.orEmpty())
        binding.assignedEmailInput.setText(account.email.orEmpty())
        binding.assignedEmailPasswordInput.setText(account.email_password.orEmpty())
        binding.assignedCopyStatusText.text = "已加载：${account.username.orEmpty()}"
    }

    private fun setupAssignedAccountSection() {
        fun copy(label: String, value: String) {
            val text = value.trim()
            if (text.isBlank()) {
                binding.assignedCopyStatusText.text = "$label 为空，未复制"
                return
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            binding.assignedCopyStatusText.text = "$label 已复制"
        }

        binding.copyAssignedUsernameButton.setOnClickListener {
            copy("Username", binding.assignedUsernameInput.text?.toString().orEmpty())
        }
        binding.copyAssignedPasswordButton.setOnClickListener {
            copy("Password", binding.assignedPasswordInput.text?.toString().orEmpty())
        }
        binding.copyAssignedApiButton.setOnClickListener {
            copy("API", binding.assignedApiInput.text?.toString().orEmpty())
        }
        binding.copyAssignedEmailButton.setOnClickListener {
            copy("Email", binding.assignedEmailInput.text?.toString().orEmpty())
        }
        binding.copyAssignedEmailPasswordButton.setOnClickListener {
            copy("Email Password", binding.assignedEmailPasswordInput.text?.toString().orEmpty())
        }

        binding.fetchTikTokCodeButton.setOnClickListener {
            lifecycleScope.launch {
                val apiUrl = binding.assignedApiInput.text?.toString().orEmpty().trim()
                if (apiUrl.isBlank()) {
                    binding.assignedCopyStatusText.text = "API 为空：无法获取验证码"
                    return@launch
                }
                if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) {
                    binding.assignedCopyStatusText.text = "API 链接无效：$apiUrl"
                    return@launch
                }

                val deviceId = PrefsStore.getDeviceId().orEmpty()
                val alias = PrefsStore.getAlias().orEmpty().trim()

                binding.fetchTikTokCodeButton.isEnabled = false
                binding.assignedCopyStatusText.text = "正在获取验证码..."
                val result = withContext(Dispatchers.IO) {
                    val local = fetchTikTokEmailCode(apiUrl)
                    if (local.isSuccess) {
                        local
                    } else {
                        val localErr = local.exceptionOrNull()!!
                        val serverResult = fetchTikTokEmailCodeFromServer(deviceId, alias, apiUrl)
                        if (serverResult.isSuccess) {
                            serverResult
                        } else {
                            val serverErr = serverResult.exceptionOrNull()!!
                            Result.failure(
                                IllegalStateException(
                                    "设备端获取失败：${localErr.message ?: localErr.javaClass.simpleName}；云端代取失败：${serverErr.message ?: serverErr.javaClass.simpleName}",
                                ),
                            )
                        }
                    }
                }
                binding.fetchTikTokCodeButton.isEnabled = true

                val code = result.getOrNull()
                if (code.isNullOrBlank()) {
                    val msg = result.exceptionOrNull()?.message ?: "未识别到 6 位验证码"
                    binding.assignedCopyStatusText.text = "获取失败：$msg"
                    return@launch
                }
                copy("TikTok Code", code)
                binding.assignedCopyStatusText.text = "获取成功：$code（已复制）"
            }
        }

        assignedUsernameAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf())
        binding.assignedUsernameSelect.adapter = assignedUsernameAdapter
        binding.assignedUsernameSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressAssignedUsernameSelectCallback) return
                val uname = assignedUsernameAdapter?.getItem(position).orEmpty()
                PrefsStore.setLogContextUsername(uname.trim().takeIf { it.isNotEmpty() })
                applyAssignedAccountByUsername(uname)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                if (suppressAssignedUsernameSelectCallback) return
                PrefsStore.setLogContextUsername(null)
                applyAssignedAccountByUsername("")
            }
        }
        refreshAssignedAccountFields()
    }

    private fun fetchTikTokEmailCode(apiUrl: String): Result<String> {
        return runCatching {
            val req = Request.Builder()
                .url(apiUrl)
                .get()
                .build()
            val resp = codeFetchHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val bodyText = resp.body?.string().orEmpty()
            if (bodyText.isBlank()) {
                throw IllegalStateException("返回内容为空")
            }
            extractEmailCode(bodyText)
        }
    }

    private suspend fun fetchTikTokEmailCodeFromServer(deviceId: String, alias: String, apiUrl: String): Result<String> {
        return runCatching {
            val resp = ApiService.getTikTokEmailCodeFromServer(deviceId, alias, apiUrl)
            if (resp.code != 0) {
                throw IllegalStateException(resp.msg.ifBlank { "云端代取失败" })
            }
            val code = resp.email_code?.trim().orEmpty()
            if (!code.matches(Regex("""\d{6}"""))) {
                throw IllegalStateException("云端未返回有效验证码")
            }
            code
        }
    }

    private fun extractEmailCode(bodyText: String): String {
        val normalized = bodyText
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("[\\u00A0\\t]"), " ")

        val patterns = listOf(
            // 主题: 760553 is your 6-digit code
            Regex("""(?im)^\s*(主题|subject)\s*[:：]\s*(\d{6})\b"""),
            // Your 6-digit code is: 760553
            Regex("""(?i)\b6[- ]?digit\s+code\b[^\d]{0,24}(\d{6})\b"""),
            // 兜底：任意 6 位数字（可能误命中，但只作为最后手段）
            Regex("""\b(\d{6})\b"""),
        )
        for (re in patterns) {
            val m = re.find(normalized) ?: continue
            val code = m.groupValues.getOrNull(2)?.ifBlank { null } ?: m.groupValues.getOrNull(1)
            val out = code?.trim().orEmpty()
            if (out.matches(Regex("""\d{6}"""))) return out
        }
        throw IllegalStateException("未识别到 6 位验证码")
    }

    private fun requireCloudLoginIfNeeded(): Boolean {
        if (!PrefsStore.getAuthToken().isNullOrBlank()) return false
        binding.logPanel.visibility = View.GONE
        binding.actionPanel.visibility = View.VISIBLE
        binding.bindStatusText.text = "请先在设置中登录云端账号"
        startActivity(Intent(this, SettingsActivity::class.java))
        return true
    }

    private fun applyInitialPanelFromIntent() {
        when (intent?.getStringExtra("initial_panel")) {
            "action" -> {
                binding.logPanel.visibility = View.GONE
                binding.actionPanel.visibility = View.VISIBLE
            }
            "log" -> {
                binding.logPanel.visibility = View.VISIBLE
                binding.actionPanel.visibility = View.GONE
                scrollLogToLatest()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
    }

    /** Android 14+：互点读 TikTok 复制到剪贴板的用户名需此权限，否则 [ClipboardManager.getPrimaryClip] 多为 null。 */
    private fun requestReadClipboardPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 34) return
        if (ContextCompat.checkSelfPermission(this, PERM_READ_CLIPBOARD) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(PERM_READ_CLIPBOARD), 101)
    }

    private fun renderDeviceInfo() {
        binding.deviceInfoText.text = buildString {
            appendLine("Alias: ${PrefsStore.getAlias()}")
            appendLine("Device ID: ${PrefsStore.getDeviceId()}")
            appendLine("Local Status: ${PrefsStore.getLocalStatus()}")
            val au = PrefsStore.getAuthUsername()
            if (!au.isNullOrBlank()) appendLine("云端账号: $au") else appendLine("云端账号: 未登录（绑定前请在设置中登录）")
        }
    }

    private fun scrollLogToLatest() {
        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            LogCenter.logs.collect { logs ->
                binding.logText.text = logs.takeLast(120).joinToString("\n")
                scrollLogToLatest()
            }
        }

        lifecycleScope.launch {
            AppCoordinator.runtimeState.collectLatest { state ->
                binding.runtimeStateText.text = buildString {
                    appendLine("后台服务: ${if (state.serviceRunning) "运行中" else "未启动"}")
                    appendLine("本地状态: ${state.localStatus}")
                    appendLine("配置状态: ${if (state.configLoaded) "已拉取" else "默认配置"}")
                    if (state.effectiveRunMode.isNotBlank()) {
                        appendLine("当前生效模式: ${state.effectiveRunMode}")
                    }
                    if (state.effectiveTemplate.isNotBlank()) {
                        appendLine("当前生效模板: ${state.effectiveTemplate}")
                    }
                    appendLine("服务端: ${state.currentServerUrl}")
                    appendLine("硬停状态: ${if (state.hardStopRequested) "已启用" else "未启用"}")
                    append("最近状态: ${state.lastMessage}")
                }
                renderDeviceInfo()
            }
        }
    }

    private fun setupRunModeSpinner() {
        val labels = listOf("涨粉模式", "主播访问模式", "直客访问模式", "互点模式")
        binding.runModeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        fun indexForStored(): Int = when (PrefsStore.getDeviceRunMode()) {
            ServerRunMode.LIVE -> 1
            ServerRunMode.DIRECT -> 2
            ServerRunMode.MUTUAL -> 3
            else -> 0
        }

        var suppressCallback = true
        binding.runModeSpinner.setSelection(indexForStored())
        binding.runModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCallback) return
                val mode = when (position) {
                    1 -> ServerRunMode.LIVE
                    2 -> ServerRunMode.DIRECT
                    3 -> ServerRunMode.MUTUAL
                    else -> ServerRunMode.GROWTH
                }
                PrefsStore.saveDeviceRunMode(mode)
                lifecycleScope.launch { AppCoordinator.fetchConfig() }
                LogCenter.op(PrefsStore.getAlias(), "已切换本机运行模式为 $mode，正在拉取配置")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.runModeSpinner.post { suppressCallback = false }
    }

    private fun setupBindingSection() {
        binding.bindButton.setOnClickListener { bindDevice() }
        binding.bindButton.isEnabled = true
        binding.bindInputLayout.error = null
        binding.bindInput.setText(PrefsStore.getAlias().orEmpty())
        updateBindSectionState()
    }

    // 备份/恢复相关 UI 与逻辑已从该 Activity 移除

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val targetDir = File(cacheDir, "picked_backups")
            if (!targetDir.exists()) targetDir.mkdirs()
            val displayName = queryDisplayName(uri) ?: "picked_backup_${System.currentTimeMillis()}.zip"
            val outFile = File(targetDir, displayName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            } ?: return null
            outFile
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateBindSectionState() {
        binding.bindButton.text = if (PrefsStore.isBound()) "重新绑定" else getString(R.string.action_bind)
    }

    private fun ensureBoundIfNeeded() {
        if (!PrefsStore.isBound()) {
            updateBindSectionState()
            return
        }
        if (PrefsStore.getAuthToken().isNullOrBlank()) {
            updateBindSectionState()
            return
        }
        val twelveHoursMs = 12L * 60 * 60 * 1000
        if (!PrefsStore.shouldRefreshBindingSilently(twelveHoursMs)) {
            updateBindSectionState()
            return
        }
        lifecycleScope.launch {
            runCatching {
                ApiService.bindDevice(PrefsStore.getAlias().orEmpty(), DeviceFingerprint.fromContext(this@MainActivity))
            }.onSuccess { response ->
                if (response.code == 0 && !response.device_id.isNullOrBlank()) {
                    PrefsStore.saveBinding(response.device_id, PrefsStore.getAlias().orEmpty())
                    PrefsStore.markBindingRefreshedNow()
                    LogCenter.op(PrefsStore.getAlias(), "绑定校验通过：${response.device_id}")
                } else {
                    LogCenter.op(PrefsStore.getAlias(), response.msg)
                }
                updateBindSectionState()
                renderDeviceInfo()
            }.onFailure {
                LogCenter.op(PrefsStore.getAlias(), "绑定校验失败：${it.message}")
                updateBindSectionState()
            }
        }
    }

    private fun bindDevice() {
        if (PrefsStore.getAuthToken().isNullOrBlank()) {
            binding.bindInputLayout.error = "请先在「设置」中登录云端账号"
            binding.bindStatusText.text = "绑定需登录：与网页管理台相同的用户名密码"
            return
        }
        val alias = binding.bindInput.text?.toString()?.trim().orEmpty()
        if (!Regex("^[A-Za-z0-9_]{1,20}$").matches(alias)) {
            binding.bindInputLayout.error = "别名只允许 1-20 位字母、数字、下划线"
            return
        }

        binding.bindInputLayout.error = null
        binding.bindStatusText.text = "正在绑定..."

        lifecycleScope.launch {
            binding.bindButton.isEnabled = false
            try {
                val cappedMs = 40_000L
                val result = withTimeoutOrNull(cappedMs) {
                    runCatching {
                        ApiService.bindDevice(alias, DeviceFingerprint.fromContext(this@MainActivity))
                    }
                }
                when {
                    result == null -> {
                        binding.bindStatusText.text =
                            "绑定超时（${cappedMs / 1000}秒内无完整响应）。多为网络或未打到本服务：请在服务端查是否出现 POST /api/bind_device；接口与库表无误时一般不会卡在数据库。"
                        LogCenter.op(alias, "绑定请求协程超时（${cappedMs}ms）")
                    }
                    result.isFailure -> {
                        val err = result.exceptionOrNull()!!
                        val stillBound = PrefsStore.isBound()
                        binding.bindStatusText.text = buildString {
                            append("绑定失败：${err.message}")
                            if (stillBound) {
                                append("（本地仍为已绑定；服务端 get_config 返回 200 即表示在册，可忽略本次重新绑定超时）")
                            }
                        }
                        LogCenter.op(alias, "绑定异常：${err.message}")
                    }
                    else -> {
                        val response = result.getOrThrow()
                        if (response.code == 0 && !response.device_id.isNullOrBlank()) {
                            PrefsStore.saveBinding(response.device_id, alias)
                            PrefsStore.markBindingRefreshedNow()
                            LogCenter.op(alias, "绑定成功：${response.device_id}")
                            AppCoordinator.syncOnline()
                            binding.bindStatusText.text = "绑定成功"
                            WorkerService.startResumingAutomation(this@MainActivity)
                            AppCoordinator.startIfNeeded()
                        } else {
                            binding.bindStatusText.text =
                                response.msg.ifBlank { "服务端返回 code=${response.code}，未携带 device_id（请确认账号已登录且服务端为当前仓库版本）" }
                            LogCenter.op(alias, "绑定被拒：${response.msg} code=${response.code}")
                        }
                    }
                }
                updateBindSectionState()
                renderDeviceInfo()
            } finally {
                binding.bindButton.isEnabled = true
            }
        }
    }

    private enum class HistoryFilter { ALL, LOCAL, CLOUD }
}
