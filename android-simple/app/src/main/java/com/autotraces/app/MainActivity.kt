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
import android.widget.Spinner
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
    private var manualAccounts: List<ManualDeviceControl.AccountOption> = emptyList()
    private var suppressingManualSpinnerCallback = false
    private var suppressingManualAutoSwitchCheck = false
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
        setupIssuedNewEmailSection()
        setupManualTaskSection()
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
                    LogCenter.op(alias, "后台服务已启动，已按持久化计划请求恢复本机自动切号/自动任务")
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
                    "已请求硬停：当前步骤结束后停下自动任务；若已开本机自动切号会暂停并清脏数据（计划文件保留）；点击「启动后台服务」可按计划续跑；后台继续轮询 check_start / 设备指令",
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

    private fun setupIssuedNewEmailSection() {
        fun copy(label: String, value: String) {
            val text = value.trim()
            if (text.isBlank()) {
                binding.issuedNewEmailStatusText.text = "$label 为空，未复制"
                return
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            binding.issuedNewEmailStatusText.text = "$label 已复制"
        }

        fun applyIssuedEmail(email: String?, password: String?) {
            binding.issuedNewEmailInput.setText(email.orEmpty())
            binding.issuedNewEmailPasswordInput.setText(password.orEmpty())
        }

        applyIssuedEmail(PrefsStore.getIssuedTkPendingEmail(), PrefsStore.getIssuedTkPendingEmailPassword())
        if (!PrefsStore.getIssuedTkPendingEmail().isNullOrBlank()) {
            binding.issuedNewEmailStatusText.text = "已恢复上次下发邮箱：${PrefsStore.getIssuedTkPendingEmail()}"
        }

        binding.copyIssuedNewEmailButton.setOnClickListener {
            copy("新邮箱", binding.issuedNewEmailInput.text?.toString().orEmpty())
        }
        binding.copyIssuedNewEmailPasswordButton.setOnClickListener {
            copy("邮箱密码", binding.issuedNewEmailPasswordInput.text?.toString().orEmpty())
        }

        binding.acquireNewEmailButton.setOnClickListener {
            if (!binding.acquireNewEmailButton.isEnabled) return@setOnClickListener
            if (requireCloudLoginIfNeeded()) return@setOnClickListener
            val deviceId = PrefsStore.getDeviceId().orEmpty()
            val alias = PrefsStore.getAlias().orEmpty().trim()
            if (deviceId.isBlank() || alias.isBlank()) {
                binding.issuedNewEmailStatusText.text = "设备未绑定，无法领取新邮箱"
                return@setOnClickListener
            }

            // 同步禁用，避免连点并发领取多封邮箱
            binding.acquireNewEmailButton.isEnabled = false
            binding.issuedNewEmailStatusText.text = "正在从「待注册TK账号」领取新邮箱…"
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ApiService.acquireTkPendingEmail(deviceId, alias) }
                    }

                    val resp = result.getOrNull()
                    if (resp == null) {
                        binding.issuedNewEmailStatusText.text =
                            "领取失败：${result.exceptionOrNull()?.message ?: "网络错误"}"
                        return@launch
                    }
                    if (resp.code != 0 || resp.email.isNullOrBlank()) {
                        binding.issuedNewEmailStatusText.text =
                            resp.msg?.trim().orEmpty().ifBlank { "待注册池无可用邮箱" }
                        return@launch
                    }

                    val email = resp.email.trim()
                    val password = resp.password.orEmpty()
                    PrefsStore.setIssuedTkPendingEmail(email, password)
                    applyIssuedEmail(email, password)
                    copy("新邮箱", email)
                    binding.issuedNewEmailStatusText.text =
                        "已下发并转入邮箱账户管理：$email（已复制）"
                } finally {
                    binding.acquireNewEmailButton.isEnabled = true
                }
            }
        }

        binding.fetchNewEmailCodeButton.setOnClickListener {
            if (!binding.fetchNewEmailCodeButton.isEnabled) return@setOnClickListener
            val accountEmail = binding.issuedNewEmailInput.text?.toString().orEmpty().trim()
                .ifBlank { PrefsStore.getIssuedTkPendingEmail().orEmpty() }
            val deviceId = PrefsStore.getDeviceId().orEmpty()
            val alias = PrefsStore.getAlias().orEmpty().trim()

            if (accountEmail.isBlank()) {
                binding.issuedNewEmailStatusText.text = "请先点击「获取新邮箱」"
                return@setOnClickListener
            }
            if (deviceId.isBlank() || alias.isBlank()) {
                binding.issuedNewEmailStatusText.text = "设备未绑定：无法从云端取验证码"
                return@setOnClickListener
            }

            binding.fetchNewEmailCodeButton.isEnabled = false
            binding.issuedNewEmailStatusText.text = "正在从云端邮箱管理模块获取验证码…"
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        TikTokEmailCodeFetcher.fetchCode(deviceId, alias, apiUrl = "", email = accountEmail)
                    }

                    val code = result.getOrNull()
                    if (code.isNullOrBlank()) {
                        val msg = result.exceptionOrNull()?.message ?: "未识别到 6 位验证码"
                        binding.issuedNewEmailStatusText.text = "获取验证码失败：$msg"
                        return@launch
                    }
                    copy("TikTok Code", code)
                    binding.issuedNewEmailStatusText.text = "验证码 $code（已自动复制）"
                } finally {
                    binding.fetchNewEmailCodeButton.isEnabled = true
                }
            }
        }
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
        val cached = ManualDeviceControl.getCachedAccounts()
        if (cached.isNotEmpty()) {
            if (manualAccounts.isEmpty()) {
                bindManualAccountSpinner(cached)
            } else {
                val user = selectedManualUsername()
                ManualDeviceControl.findCachedAccount(user)?.let { applyManualAccountFields(it) }
                refreshManualAccountStatus()
            }
        } else {
            refreshAssignedAccountFields()
            refreshManualAccountStatus()
        }
        refreshManualAutoSwitchStatus()
    }

    private fun setupManualTaskSection() {
        binding.manualAutoGrowthAfterTasksCheck.isChecked = PrefsStore.isAutoGrowthAfterTasksEnabled()
        binding.manualAutoGrowthAfterTasksCheck.setOnCheckedChangeListener { _, checked ->
            PrefsStore.setAutoGrowthAfterTasksEnabled(checked)
            binding.manualTaskStatusText.text =
                if (checked) "已开启：任务完成后自动涨粉" else "已关闭：任务完成后不自动涨粉"
        }

        binding.manualAutoSwitchSlotMinutesInput.setText(
            PrefsStore.getManualAutoSwitchSlotMinutes().toString(),
        )
        suppressingManualAutoSwitchCheck = true
        binding.manualAutoSwitchEnableCheck.isChecked = PrefsStore.isManualLocalAutoSwitchEnabled()
        suppressingManualAutoSwitchCheck = false
        binding.manualAutoSwitchEnableCheck.setOnCheckedChangeListener { _, checked ->
            if (suppressingManualAutoSwitchCheck) return@setOnCheckedChangeListener
            if (!ensureCloudLoginAndBoundForStart()) {
                suppressingManualAutoSwitchCheck = true
                binding.manualAutoSwitchEnableCheck.isChecked = PrefsStore.isManualLocalAutoSwitchEnabled()
                suppressingManualAutoSwitchCheck = false
                return@setOnCheckedChangeListener
            }
            val minutes = binding.manualAutoSwitchSlotMinutesInput.text?.toString()?.toIntOrNull()
                ?: PrefsStore.getManualAutoSwitchSlotMinutes()
            if (checked) {
                if (ManualDeviceControl.getCachedAccounts().isEmpty()) {
                    Toast.makeText(this, "请先拉取云端账号列表", Toast.LENGTH_SHORT).show()
                    suppressingManualAutoSwitchCheck = true
                    binding.manualAutoSwitchEnableCheck.isChecked = false
                    suppressingManualAutoSwitchCheck = false
                    return@setOnCheckedChangeListener
                }
                binding.manualTaskStatusText.text = "正在开启本机自动切号…"
                AppCoordinator.manualEnableLocalAutoSwitch(minutes)
            } else {
                binding.manualTaskStatusText.text = "正在关闭本机自动切号…"
                AppCoordinator.manualDisableLocalAutoSwitch()
            }
            lifecycleScope.launch {
                delay(800)
                refreshManualAutoSwitchStatus()
                // 开启失败时回滚勾选状态
                if (checked && !PrefsStore.isManualLocalAutoSwitchEnabled()) {
                    binding.manualTaskStatusText.text = "本机自动切号开启失败或已取消"
                }
            }
        }
        binding.manualAutoSwitchRebuildButton.setOnClickListener {
            if (!ensureCloudLoginAndBoundForStart()) return@setOnClickListener
            if (ManualDeviceControl.getCachedAccounts().isEmpty()) {
                Toast.makeText(this, "请先拉取云端账号列表", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val minutes = binding.manualAutoSwitchSlotMinutesInput.text?.toString()?.toIntOrNull()
                ?: PrefsStore.getManualAutoSwitchSlotMinutes()
            binding.manualTaskStatusText.text = "正在重建本机切号计划…"
            AppCoordinator.manualRebuildLocalAutoSwitchPlan(minutes)
            suppressingManualAutoSwitchCheck = true
            binding.manualAutoSwitchEnableCheck.isChecked = true
            suppressingManualAutoSwitchCheck = false
            lifecycleScope.launch {
                delay(600)
                refreshManualAutoSwitchStatus()
            }
        }
        refreshManualAutoSwitchStatus()

        binding.manualAccountSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressingManualSpinnerCallback) return
                val opt = manualAccounts.getOrNull(position) ?: return
                AppCoordinator.manualSelectAccount(opt.username)
                applyManualAccountFields(opt)
                val active = ManualDeviceControl.activeManualUsername()
                if (active.isNotBlank() && active.equals(opt.username, ignoreCase = true)) {
                    refreshManualAccountStatus()
                    return
                }
                if (!ensureCloudLoginAndBoundForStart()) {
                    // 未绑定：回滚到本机当前账号，避免假选中
                    revertManualSpinnerToActive()
                    refreshManualAccountStatus()
                    return
                }
                if (AppCoordinator.isManualSwitchBusy()) {
                    Toast.makeText(this@MainActivity, "切号进行中，请稍候", Toast.LENGTH_SHORT).show()
                    revertManualSpinnerToActive()
                    return
                }
                binding.manualTaskStatusText.text = "正在切号 → @${opt.username} …"
                // 先同步禁用（含「手动切号完毕」），再启动切号；busy 也会在 coordinator 内同步置位
                binding.manualConfirmSwitchDoneButton.isEnabled = false
                setManualTaskButtonsEnabled(false)
                AppCoordinator.manualSwitchAccountFromSpinner(opt.username)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.manualRefreshAccountsButton.setOnClickListener {
            lifecycleScope.launch {
                binding.manualRefreshAccountsButton.isEnabled = false
                binding.manualTaskStatusText.text = "正在拉取云端账号…"
                val result = AppCoordinator.manualRefreshAssignedAccounts()
                binding.manualRefreshAccountsButton.isEnabled = true
                result.onSuccess { list ->
                    bindManualAccountSpinner(list)
                    val active = ManualDeviceControl.activeManualUsername()
                    val activeStillListed = active.isNotBlank() &&
                        list.any { it.username.equals(active, ignoreCase = true) }
                    binding.manualTaskStatusText.text = when {
                        active.isNotBlank() && !activeStillListed ->
                            "已拉取 ${list.size} 个账号；本机当前 @$active 已不参与，请切到其他账号"
                        else -> "已拉取 ${list.size} 个账号"
                    }
                    refreshManualAutoSwitchStatus()
                    // 不要再调 refreshAssignedAccountFields：它会用云端「高亮」单账号覆盖下拉选中凭据
                }.onFailure { err ->
                    binding.manualTaskStatusText.text = "拉取失败：${err.message}"
                }
            }
        }

        // 备份/清理已移除：切号由账号下拉触发完整备份→清理→还原链

        binding.manualConfirmSwitchDoneButton.setOnClickListener {
            if (AppCoordinator.isManualSwitchBusy()) {
                Toast.makeText(this, "切号进行中，请稍候", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val user = selectedManualUsername()
            if (user.isBlank()) {
                Toast.makeText(this, "请先选择账号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ensureCloudLoginAndBoundForStart()) return@setOnClickListener
            AppCoordinator.manualSelectAccount(user)
            AppCoordinator.manualConfirmSwitchDone()
            binding.manualTaskStatusText.text = "已确认手动切号完毕：@$user"
            refreshManualAccountStatus()
            refreshManualAccountSpinnerLabels()
            Toast.makeText(this, "已标记 @$user 切号完毕", Toast.LENGTH_SHORT).show()
        }

        binding.manualAutoLoginButton.setOnClickListener {
            if (AppCoordinator.isManualSwitchBusy()) {
                Toast.makeText(this, "切号进行中，请稍候", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val user = selectedManualUsername()
            if (user.isBlank()) {
                Toast.makeText(this, "请先选择账号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!AppCoordinator.isManualAccountAligned()) {
                Toast.makeText(this, "请先等待切号完成（本机当前需与下拉一致）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ensureCloudLoginAndBoundForStart()) return@setOnClickListener
            binding.manualTaskStatusText.text = "正在自动登录 @$user …"
            AppCoordinator.manualStartAutoLogin("手动点击自动登录")
        }

        binding.manualStartGrowthButton.setOnClickListener {
            if (!guardManualTaskReady("涨粉")) return@setOnClickListener
            binding.manualTaskStatusText.text = "已请求开始涨粉"
            AppCoordinator.manualStartGrowth()
        }

        binding.manualStopGrowthButton.setOnClickListener {
            binding.manualTaskStatusText.text = "已请求停止涨粉"
            AppCoordinator.manualStopGrowth()
        }

        binding.manualForYouSwipeSecInput.setText(
            PrefsStore.getManualForYouSwipeSec().toString(),
        )
        binding.manualForYouSwipeButton.setOnClickListener {
            if (!guardManualTaskReady("For You 预滑")) return@setOnClickListener
            val raw = binding.manualForYouSwipeSecInput.text?.toString()?.toIntOrNull()
                ?: PrefsStore.getManualForYouSwipeSec()
            val sec = raw.coerceIn(
                PrefsStore.MIN_MANUAL_FOR_YOU_SWIPE_SEC,
                PrefsStore.MAX_MANUAL_FOR_YOU_SWIPE_SEC,
            )
            PrefsStore.setManualForYouSwipeSec(sec)
            binding.manualForYouSwipeSecInput.setText(sec.toString())
            binding.manualTaskStatusText.text = "已请求 For You 预滑（${sec}s）"
            AppCoordinator.manualStartForYouSwipe(sec)
        }

        binding.manualMutualButton.setOnClickListener {
            if (!guardManualTaskReady("引流互点")) return@setOnClickListener
            binding.manualTaskStatusText.text = "已请求引流互点"
            AppCoordinator.manualStartMutualClick()
        }

        binding.manualPublishCountInput.setText(
            PrefsStore.getManualPublishCount().toString(),
        )
        binding.manualPublishButton.setOnClickListener {
            if (!guardManualTaskReady("发作品")) return@setOnClickListener
            val raw = binding.manualPublishCountInput.text?.toString()?.toIntOrNull()
                ?: PrefsStore.getManualPublishCount()
            val count = raw.coerceIn(
                PrefsStore.MIN_MANUAL_PUBLISH_COUNT,
                PrefsStore.MAX_MANUAL_PUBLISH_COUNT,
            )
            PrefsStore.setManualPublishCount(count)
            binding.manualPublishCountInput.setText(count.toString())
            binding.manualTaskStatusText.text = "已请求发作品（${count}条）"
            AppCoordinator.manualStartPublishPost(count)
        }

        binding.manualProfileEditButton.setOnClickListener {
            if (!guardManualTaskReady("修改基本资料")) return@setOnClickListener
            binding.manualTaskStatusText.text = "已请求修改基本资料"
            AppCoordinator.manualStartEditBasicProfile()
        }

        lifecycleScope.launch {
            var seenBusy = false
            AppCoordinator.manualSwitchBusy.collectLatest { busy ->
                refreshManualAccountStatus()
                if (busy) {
                    seenBusy = true
                    binding.manualTaskStatusText.text = "正在切号…"
                    revertManualSpinnerToSelected()
                } else if (seenBusy) {
                    val active = ManualDeviceControl.activeManualUsername()
                    val msg = AppCoordinator.runtimeState.value.lastMessage
                    if (msg.startsWith("切号完成") || msg.startsWith("手动切号完成")) {
                        binding.manualTaskStatusText.text =
                            if (active.isNotBlank()) "切号完成，本机当前：@$active" else msg
                        revertManualSpinnerToActive()
                    } else if (msg.startsWith("手动切号失败") || msg.startsWith("手动切号已取消")) {
                        binding.manualTaskStatusText.text = msg
                        revertManualSpinnerToActive()
                    } else {
                        revertManualSpinnerToActive()
                    }
                    refreshManualAccountSpinnerLabels()
                    refreshManualAccountStatus()
                }
            }
        }
        lifecycleScope.launch {
            AppCoordinator.manualAccountUiEpoch.collectLatest {
                refreshManualAccountSpinnerLabels()
                refreshManualAccountStatus()
            }
        }

        val cached = ManualDeviceControl.getCachedAccounts()
        if (cached.isNotEmpty()) {
            bindManualAccountSpinner(cached)
        }
        refreshManualAccountStatus()
    }

    /** 任务按钮：切号完成且本机当前与下拉一致后才可点。 */
    private fun guardManualTaskReady(label: String): Boolean {
        if (AppCoordinator.isManualSwitchBusy()) {
            Toast.makeText(this, "切号进行中，请稍候", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!AppCoordinator.isManualAccountAligned()) {
            Toast.makeText(this, "请先切号完成（本机当前需与下拉账号一致）后再$label", Toast.LENGTH_SHORT).show()
            return false
        }
        return ensureCloudLoginAndBoundForStart()
    }

    private fun revertManualSpinnerToActive() {
        val active = ManualDeviceControl.activeManualUsername()
            .ifBlank { PrefsStore.getManualSelectedUsername().orEmpty() }
        if (active.isBlank() || manualAccounts.isEmpty()) return
        val idx = manualAccounts.indexOfFirst { it.username.equals(active, ignoreCase = true) }
        if (idx < 0) return
        suppressingManualSpinnerCallback = true
        binding.manualAccountSelect.setSelection(idx)
        applyManualAccountFields(manualAccounts[idx])
        AppCoordinator.manualSelectAccount(manualAccounts[idx].username)
        suppressingManualSpinnerCallback = false
    }

    /** 切号进行中：下拉保持显示目标选中账号（prefs selected）。 */
    private fun revertManualSpinnerToSelected() {
        val selected = PrefsStore.getManualSelectedUsername().orEmpty()
        if (selected.isBlank() || manualAccounts.isEmpty()) return
        val idx = manualAccounts.indexOfFirst { it.username.equals(selected, ignoreCase = true) }
        if (idx < 0) return
        if (binding.manualAccountSelect.selectedItemPosition == idx) return
        suppressingManualSpinnerCallback = true
        binding.manualAccountSelect.setSelection(idx)
        applyManualAccountFields(manualAccounts[idx])
        suppressingManualSpinnerCallback = false
    }

    private fun setManualTaskButtonsEnabled(enabled: Boolean) {
        val busy = AppCoordinator.isManualSwitchBusy()
        val aligned = enabled && AppCoordinator.isManualAccountAligned() && !busy
        binding.manualAccountSelect.isEnabled = !busy
        binding.manualRefreshAccountsButton.isEnabled = !busy
        // 手动切号完毕：未在自动切号中即可点（用户已在设备上切好号后告知程序）
        binding.manualConfirmSwitchDoneButton.isEnabled = !busy && selectedManualUsername().isNotBlank()
        binding.manualAutoGrowthAfterTasksCheck.isEnabled = aligned
        binding.manualStartGrowthButton.isEnabled = aligned
        binding.manualStopGrowthButton.isEnabled = true
        binding.manualForYouSwipeButton.isEnabled = aligned
        binding.manualForYouSwipeSecInput.isEnabled = aligned
        binding.manualMutualButton.isEnabled = aligned
        binding.manualPublishButton.isEnabled = aligned
        binding.manualProfileEditButton.isEnabled = aligned
        // 自动登录：切号对齐后、尚未就绪时可点（重试无备份自动登录）
        val statusUser = selectedManualUsername()
        val firstDone = statusUser.isNotBlank() && PrefsStore.isManualFirstLoginDone(statusUser)
        binding.manualAutoLoginButton.isEnabled = aligned && !firstDone
    }

    private fun selectedManualUsername(): String {
        val pos = binding.manualAccountSelect.selectedItemPosition
        return manualAccounts.getOrNull(pos)?.username
            ?: PrefsStore.getManualSelectedUsername().orEmpty()
    }

    private fun bindManualAccountSpinner(list: List<ManualDeviceControl.AccountOption>) {
        manualAccounts = list
        val active = ManualDeviceControl.activeManualUsername()
        val labels = list.map { opt -> formatManualAccountSpinnerLabel(opt.username, active) }
        suppressingManualSpinnerCallback = true
        binding.manualAccountSelect.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        // 优先展示本机当前账号，否则用上次选中
        val preferred = ManualDeviceControl.activeManualUsername()
            .ifBlank { PrefsStore.getManualSelectedUsername().orEmpty() }
        val idx = list.indexOfFirst { it.username.equals(preferred, ignoreCase = true) }
            .takeIf { it >= 0 } ?: 0
        if (list.isNotEmpty()) {
            binding.manualAccountSelect.setSelection(idx)
            applyManualAccountFields(list[idx])
            AppCoordinator.manualSelectAccount(list[idx].username)
        }
        suppressingManualSpinnerCallback = false
        refreshManualAccountStatus()
    }

    /** 下拉项文案：只标注本机切号后的当前活跃账号，不展示云端高亮/is_login（易误导）。 */
    private fun formatManualAccountSpinnerLabel(username: String, activeUsername: String = ManualDeviceControl.activeManualUsername()): String {
        return buildString {
            append("@$username")
            if (activeUsername.isNotBlank() && username.equals(activeUsername, ignoreCase = true)) {
                append(" ·本机当前")
            }
        }
    }

    /** 切号完成后刷新「本机当前」标记，保持下拉选中位置不变。 */
    private fun refreshManualAccountSpinnerLabels() {
        if (manualAccounts.isEmpty()) return
        val active = ManualDeviceControl.activeManualUsername()
        val labels = manualAccounts.map { formatManualAccountSpinnerLabel(it.username, active) }
        val rawPos = binding.manualAccountSelect.selectedItemPosition
        val selectedPos = if (rawPos in manualAccounts.indices) rawPos else 0
        suppressingManualSpinnerCallback = true
        binding.manualAccountSelect.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.manualAccountSelect.setSelection(selectedPos)
        suppressingManualSpinnerCallback = false
    }

    private fun applyManualAccountFields(opt: ManualDeviceControl.AccountOption) {
        assignedAccount = DeviceAssignedAccountResponse(
            code = 0,
            msg = "ok",
            row_index = opt.rowIndex,
            username = opt.username,
            password = opt.password,
            api = opt.api,
            email = opt.email,
            email_password = opt.emailPassword,
            is_login = opt.cloudIsLogin,
            basic_profile_modified = opt.basicProfileModified,
            mail_module_code_available = opt.mailModuleCodeAvailable,
        )
        applyAssignedAccount(assignedAccount)
    }

    private fun refreshManualAccountStatus() {
        val user = selectedManualUsername()
        if (user.isBlank()) {
            binding.manualAccountStatusText.text = "账号状态：未选择"
            binding.manualClearAndRestoreButton.visibility = View.GONE
            setManualTaskButtonsEnabled(false)
            return
        }
        val status = AppCoordinator.manualQuerySwitchStatus(user)
        val busy = AppCoordinator.isManualSwitchBusy()
        val aligned = AppCoordinator.isManualAccountAligned()
        binding.manualAccountStatusText.text = buildString {
            append("账号状态：${status.message}")
            when {
                busy -> append(" · 切号中…")
                !aligned -> append(" · 待切号对齐")
            }
        }
        binding.manualClearAndRestoreButton.visibility = View.GONE
        setManualTaskButtonsEnabled(!busy && aligned)
    }

    private fun refreshManualAutoSwitchStatus() {
        val enabled = PrefsStore.isManualLocalAutoSwitchEnabled()
        val mode = PrefsStore.getAutoSwitchMode()
        val minutes = PrefsStore.getManualAutoSwitchSlotMinutes()
        val accounts = ManualDeviceControl.getCachedAccounts().size
        val planSlots = AutoSwitchLocalPlanStore.get()?.slots?.size ?: 0
        val weekStart = PrefsStore.getAutoSwitchWeekStart().orEmpty()
        val weekEnd = PrefsStore.getAutoSwitchWeekEnd().orEmpty()
        val week = when {
            weekStart.isNotBlank() && weekEnd.isNotBlank() -> "$weekStart~$weekEnd"
            else -> PrefsStore.getAutoSwitchScheduleDate().orEmpty().ifBlank { "—" }
        }
        suppressingManualAutoSwitchCheck = true
        binding.manualAutoSwitchEnableCheck.isChecked = enabled
        suppressingManualAutoSwitchCheck = false
        if (binding.manualAutoSwitchSlotMinutesInput.text?.toString().isNullOrBlank()) {
            binding.manualAutoSwitchSlotMinutesInput.setText(minutes.toString())
        }
        binding.manualAutoSwitchStatusText.text = buildString {
            append("自治状态：")
            append(if (enabled) "开" else "关")
            append(" · mode=$mode")
            append(" · 每号 ${minutes} 分钟")
            append(" · 账号 $accounts")
            append(" · 计划槽 $planSlots")
            if (week.isNotBlank() && week != "—") append(" · $week")
        }
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
                    if (autoSwitchMode != "off" || PrefsStore.isManualLocalAutoSwitchEnabled()) {
                        appendLine(
                            "自动切号自治（本机）: $autoSwitchMode" +
                                if (PrefsStore.isManualLocalAutoSwitchEnabled()) " · APP开关=开" else "",
                        )
                    }
                    refreshManualAutoSwitchStatus()
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
