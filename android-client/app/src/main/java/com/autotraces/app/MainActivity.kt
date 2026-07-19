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
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivityMainBinding
import com.autotraces.app.videoded.VideoDedMainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

/** API 34+ 读剪贴板；部分编译环境未生成 [Manifest.permission.READ_CLIPBOARD] 符号，用字面量避免编译失败。 */
private const val PERM_READ_CLIPBOARD = "android.permission.READ_CLIPBOARD"

const val EXTRA_INITIAL_PANEL = "initial_panel"

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    // 备份/恢复 UI 已从「操作」页移除
    private var assignedAccount: DeviceAssignedAccountResponse? = null
    /** 硬停后关闭自动滚底，便于用户翻看历史日志；重新启动后台服务后恢复。 */
    private var logStickToBottom = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        logStickToBottom = !PrefsStore.isHardStopRequested()

        requestNotificationPermissionIfNeeded()
        requestReadClipboardPermissionIfNeeded()
        setupBindingSection()
        setupAssignedAccountSection()
        renderDeviceInfo()
        observeLogs()

        binding.startServiceButton.setOnClickListener {
            lifecycleScope.launch {
                if (!ensureCloudLoginAndBoundForStart()) return@launch
                val alias = PrefsStore.getAlias()
                LogCenter.op(alias, "已请求启动后台服务")
                runCatching { WorkerService.startResumingAutomation(this@MainActivity) }.onFailure { error ->
                    LogCenter.op(alias, "启动后台服务请求失败：${error.message}")
                    AppCoordinator.refreshRuntimeSnapshot()
                    return@launch
                }
                delay(1200)
                AppCoordinator.refreshRuntimeSnapshot()
                logStickToBottom = true
                if (WorkerServiceTracker.isRunning) {
                    AppCoordinator.forceStartRunningNow("设备端点击启动后台服务")
                    LogCenter.op(alias, "后台服务已启动，已按持久化计划请求恢复自动切号/自动任务")
                } else {
                    LogCenter.op(alias, "后台服务未能启动")
                }
                AppCoordinator.refreshRuntimeSnapshot()
            }
        }

        binding.stopServiceButton.setOnClickListener {
            lifecycleScope.launch {
                val alias = PrefsStore.getAlias().orEmpty()
                logStickToBottom = false
                AppCoordinator.requestUserGracefulHardStop()
                LogCenter.op(
                    alias,
                    "已请求硬停：当前步骤结束后停下自动任务；若自动切号自治已开启会暂停并清脏数据（计划文件保留）；点击「启动后台服务」可按计划续跑；后台继续轮询 check_start / 设备指令；已请求云端关闭本机自动开启",
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
        binding.openConfigParamsButton.setOnClickListener { openConfigParamsScreen() }
        binding.showSettingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.showVideoDedButton.setOnClickListener {
            startActivity(Intent(this, VideoDedMainActivity::class.java))
        }
        binding.showLogButton.setOnClickListener { showLogPanel() }
        binding.showActionButton.setOnClickListener {
            if (AppCoordinator.isInPullUsernameRestSleep()) {
                Toast.makeText(
                    this,
                    "云端休息中：自动涨粉已暂停，仍可在此绑定/复制账号等操作",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            showActionPanel()
        }
        binding.clearLogButton.setOnClickListener {
            LogCenter.clear()
            LogCenter.op(PrefsStore.getAlias(), "日志已清空")
        }

        if (requireCloudLoginIfNeeded()) return
        applyInitialPanelFromIntent()

        updateConfigSummaryLine()

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyInitialPanelFromIntent()
    }

    override fun onResume() {
        super.onResume()
        updateConfigSummaryLine()
        if (requireCloudLoginIfNeeded()) {
            renderDeviceInfo()
            return
        }
        AppCoordinator.ensureTikTokGalleryPermissionIfNeeded()
        lifecycleScope.launch {
            val recovered = BindingRecovery.tryRecoverFromCloud(this@MainActivity)
            if (recovered) {
                binding.bindInput.setText(PrefsStore.getAlias().orEmpty())
                AppCoordinator.startIfNeeded()
            }
            ensureBoundIfNeeded()
            renderDeviceInfo()
            clarifyStaleBindErrorIfNeeded()
        }
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
        val deviceId = PrefsStore.getDeviceId().orEmpty().trim()
        if (alias.isBlank() || deviceId.isBlank()) {
            PrefsStore.setLogContextUsername(null)
            assignedAccount = null
            clearAssignedAccountInputs()
            binding.assignedCopyStatusText.text = "未绑定设备，无法拉取分配账号"
            return
        }
        binding.assignedCopyStatusText.text = "正在拉取账号表单..."
        lifecycleScope.launch {
            val resp = runCatching {
                withContext(Dispatchers.IO) {
                    ApiService.getDeviceAssignedAccount(deviceId, alias)
                }
            }.getOrElse { err ->
                PrefsStore.setLogContextUsername(null)
                assignedAccount = null
                clearAssignedAccountInputs()
                binding.assignedCopyStatusText.text = "拉取失败：${err.message ?: "网络错误"}"
                return@launch
            }
            if (resp.code != 0) {
                PrefsStore.setLogContextUsername(null)
                assignedAccount = null
                clearAssignedAccountInputs()
                binding.assignedCopyStatusText.text = resp.msg?.trim().orEmpty().ifBlank {
                    "该别名暂无高亮分配账号：$alias"
                }
                return@launch
            }

            assignedAccount = resp
            applyAssignedAccount(resp)
            PrefsStore.setLogContextUsername(resp.username?.trim().orEmpty().takeIf { it.isNotEmpty() })
        }
    }

    private fun clearAssignedAccountInputs() {
        binding.assignedUsernameInput.setText("")
        binding.assignedPasswordInput.setText("")
        binding.assignedApiInput.setText("")
        binding.assignedEmailInput.setText("")
        binding.assignedEmailPasswordInput.setText("")
    }

    private fun applyAssignedAccount(account: DeviceAssignedAccountResponse?) {
        if (account == null || account.username.isNullOrBlank()) {
            clearAssignedAccountInputs()
            return
        }
        binding.assignedUsernameInput.setText(account.username.orEmpty())
        binding.assignedPasswordInput.setText(account.password.orEmpty())
        binding.assignedApiInput.setText(account.api.orEmpty())
        binding.assignedEmailInput.setText(account.email.orEmpty())
        binding.assignedEmailPasswordInput.setText(account.email_password.orEmpty())
        val apiBlank = account.api.isNullOrBlank()
        val emailPresent = !account.email.isNullOrBlank()
        val mailModuleOk = account.mail_module_code_available == true
        binding.assignedCopyStatusText.text = when {
            apiBlank && emailPresent && !mailModuleOk ->
                "已加载：${account.username.orEmpty()}（邮箱模块未配置该邮箱 OAuth，请在管理台「邮箱账户管理」导入）"
            apiBlank && emailPresent && mailModuleOk ->
                "已加载：${account.username.orEmpty()}（可通过邮箱管理模块获取验证码）"
            else -> "已加载高亮账号：${account.username.orEmpty()}"
        }
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
                val accountEmail = binding.assignedEmailInput.text?.toString().orEmpty().trim()
                val deviceId = PrefsStore.getDeviceId().orEmpty()
                val alias = PrefsStore.getAlias().orEmpty().trim()

                if (apiUrl.isBlank() && accountEmail.isBlank()) {
                    binding.assignedCopyStatusText.text = "邮箱为空且 API 为空：无法获取验证码"
                    return@launch
                }
                if (apiUrl.isBlank() && (deviceId.isBlank() || alias.isBlank())) {
                    binding.assignedCopyStatusText.text = "API 为空且设备未绑定：无法获取验证码"
                    return@launch
                }
                if (apiUrl.isNotBlank() && !apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) {
                    binding.assignedCopyStatusText.text = "API 链接无效：$apiUrl"
                    return@launch
                }

                binding.fetchTikTokCodeButton.isEnabled = false
                binding.assignedCopyStatusText.text = if (apiUrl.isBlank()) {
                    "正在通过云端邮箱管理模块获取验证码..."
                } else {
                    "正在设备端获取验证码..."
                }
                val result = withContext(Dispatchers.IO) {
                    val first = TikTokEmailCodeFetcher.fetchCode(deviceId, alias, apiUrl, accountEmail)
                    if (first.isSuccess) {
                        first
                    } else {
                        val err = first.exceptionOrNull()
                        if (deviceId.isNotBlank() && alias.isNotBlank() && apiUrl.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                binding.assignedCopyStatusText.text =
                                    "设备端失败，正在云端代取…（${err?.message ?: "未知错误"}）"
                            }
                        }
                        first
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

        binding.assignedUsernameSelect.visibility = View.GONE
    }

    private fun requireCloudLoginIfNeeded(): Boolean {
        if (!PrefsStore.getAuthToken().isNullOrBlank()) return false
        binding.logPanel.visibility = View.GONE
        binding.actionPanel.visibility = View.VISIBLE
        binding.bindStatusText.text = "请先在设置中登录云端账号"
        startActivity(Intent(this, SettingsActivity::class.java))
        return true
    }

    /** 启动后台服务前：必须已登录云端且完成设备绑定。 */
    private fun ensureCloudLoginAndBoundForStart(): Boolean {
        if (PrefsStore.getAuthToken().isNullOrBlank()) {
            Toast.makeText(this, "请先在设置中登录云端账号", Toast.LENGTH_SHORT).show()
            binding.logPanel.visibility = View.GONE
            binding.actionPanel.visibility = View.VISIBLE
            binding.bindStatusText.text = "请先在设置中登录云端账号"
            startActivity(Intent(this, SettingsActivity::class.java))
            return false
        }
        if (!PrefsStore.isBound()) {
            Toast.makeText(this, "请先填写别名并点击「绑定」完成设备绑定", Toast.LENGTH_SHORT).show()
            binding.logPanel.visibility = View.GONE
            binding.actionPanel.visibility = View.VISIBLE
            binding.bindStatusText.text = "启动前需完成绑定：填写别名后点击绑定"
            LogCenter.op(PrefsStore.getAlias(), "启动被拒绝：设备未绑定（无 deviceId/alias）")
            return false
        }
        return true
    }

    private fun applyInitialPanelFromIntent() {
        when (intent?.getStringExtra(EXTRA_INITIAL_PANEL)) {
            "action" -> showActionPanel()
            "log" -> showLogPanel()
        }
    }

    private fun showLogPanel() {
        binding.logPanel.visibility = View.VISIBLE
        binding.actionPanel.visibility = View.GONE
        if (logStickToBottom) scrollLogToLatest()
    }

    private fun showActionPanel() {
        binding.logPanel.visibility = View.GONE
        binding.actionPanel.visibility = View.VISIBLE
        updateConfigSummaryLine()
        refreshAssignedAccountFields()
    }

    private fun openConfigParamsScreen() {
        startActivity(Intent(this, ConfigParamsActivity::class.java))
    }

    private fun updateConfigSummaryLine() {
        val cfg = AppCoordinator.config.value
        val state = AppCoordinator.runtimeState.value
        val account = PrefsStore.getLogContextUsername()?.trim().orEmpty().ifBlank { "—" }
        val tpl = state.effectiveTemplate.ifBlank { "—" }
        val loaded = if (state.configLoaded) "已同步" else "未同步"
        val sourceHint = if (PrefsStore.isAutoSwitchAutonomousMode()) "bundle" else "云端"
        binding.configSummaryText.text =
            "当前账号：$account · 模板：$tpl（$sourceHint） · ${ConfigDisplay.prettyRunMode(cfg.run_mode)} · $loaded\n" +
                "互动：赞${formatProbShort(cfg.like_prob)} 评${formatProbShort(cfg.comment_prob)} " +
                "藏${formatProbShort(cfg.favorite_prob)} 转${formatProbShort(cfg.share_prob)} · " +
                "每日上限 ${cfg.like_daily_limit}/${cfg.comment_daily_limit}/${cfg.favorite_daily_limit}/${cfg.share_daily_limit}"
    }

    private fun formatProbShort(v: Double): String =
        String.format("%.2f", v.coerceIn(0.0, 1.0))

    companion object {
        fun navigateToLogPanel(context: android.content.Context) {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_INITIAL_PANEL, "log")
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            context.startActivity(intent)
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
        val au = PrefsStore.getAuthUsername()
        val cloudLabel = if (!au.isNullOrBlank()) au else "未登录"
        binding.deviceInfoText.text =
            "${PrefsStore.getAlias().orEmpty()} · ${PrefsStore.getLocalStatus()} · 云端: $cloudLabel"
    }

    private fun scrollLogToLatest() {
        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun isLogScrolledToBottom(): Boolean {
        val scroll = binding.logScroll
        val child = scroll.getChildAt(0) ?: return true
        val threshold = (16 * resources.displayMetrics.density).toInt().coerceAtLeast(8)
        val maxScroll = (child.height - scroll.height + scroll.paddingBottom).coerceAtLeast(0)
        if (maxScroll == 0) return true
        return scroll.scrollY >= maxScroll - threshold
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            LogCenter.logs.collect { logs ->
                val stickToBottom = logStickToBottom && isLogScrolledToBottom()
                binding.logText.text = logs.takeLast(120).joinToString("\n")
                if (stickToBottom) scrollLogToLatest()
            }
        }

        lifecycleScope.launch {
            AppCoordinator.config.collectLatest {
                updateConfigSummaryLine()
            }
        }

        lifecycleScope.launch {
            AppCoordinator.runtimeState.collectLatest { state ->
                if (state.hardStopRequested) logStickToBottom = false
                updateConfigSummaryLine()
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
                    val cloudAutoStartLabel = when (state.cloudAutoStart) {
                        true -> "开启"
                        false -> "关闭"
                        null -> "—"
                    }
                    appendLine("云端自动起号: $cloudAutoStartLabel")
                    val autoSwitchMode = PrefsStore.getAutoSwitchMode()
                    if (autoSwitchMode != "off") {
                        appendLine("自动切号自治（本机）: $autoSwitchMode")
                    }
                    appendLine("硬停状态: ${if (state.hardStopRequested) "已启用" else "未启用"}")
                    append("最近状态: ${state.lastMessage}")
                }
                renderDeviceInfo()
            }
        }
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

    private suspend fun ensureBoundIfNeeded() {
        if (!PrefsStore.isBound()) {
            if (!BindingRecovery.tryRecoverFromCloud(this@MainActivity)) {
                updateBindSectionState()
                return
            }
            binding.bindInput.setText(PrefsStore.getAlias().orEmpty())
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
