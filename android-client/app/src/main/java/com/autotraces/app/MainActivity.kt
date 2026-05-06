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
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    // 备份/恢复 UI 已从「操作」页移除
    private var assignedAccountRows: List<AccountEditorRowRecord> = emptyList()
    private var assignedUsernameAdapter: ArrayAdapter<String>? = null
    private var suppressAssignedUsernameSelectCallback: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setupBindingSection()
        setupRunModeSpinner()
        setupAssignedAccountSection()
        renderDeviceInfo()
        observeLogs()

        binding.startServiceButton.setOnClickListener {
            lifecycleScope.launch {
                val alias = PrefsStore.getAlias()
                LogCenter.op(alias, "已请求启动后台服务")
                runCatching { WorkerService.start(this@MainActivity) }.onFailure { error ->
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
                val alias = PrefsStore.getAlias()
                PrefsStore.setHardStopRequested(true)
                AppCoordinator.refreshRuntimeSnapshot()
                LogCenter.op(alias, "已请求停止后台服务（硬停）")
                WorkerService.stop(this@MainActivity)
                delay(1200)
                AppCoordinator.refreshRuntimeSnapshot()
                if (WorkerServiceTracker.isRunning) {
                    LogCenter.op(alias, "后台服务仍在运行，已再次请求停止")
                } else {
                    LogCenter.op(alias, "后台服务已停止")
                }
                AppCoordinator.refreshRuntimeSnapshot()
            }
        }

        binding.openSettingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.showSettingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.showLogButton.setOnClickListener {
            binding.logPanel.visibility = View.VISIBLE
            binding.actionPanel.visibility = View.GONE
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
            LogCenter.op(PrefsStore.getAlias(), "当前处于停止状态，打开应用不自动拉起后台服务")
        }
    }

    override fun onResume() {
        super.onResume()
        if (requireCloudLoginIfNeeded()) {
            renderDeviceInfo()
            return
        }
        if (!PrefsStore.isHardStopRequested()) {
            ensureBoundIfNeeded()
        }
        renderDeviceInfo()
        refreshAssignedAccountFields()
    }

    private fun refreshAssignedAccountFields() {
        val alias = PrefsStore.getAlias().orEmpty().trim()
        if (alias.isBlank() || PrefsStore.getAuthToken().isNullOrBlank()) {
            assignedAccountRows = emptyList()
            assignedUsernameAdapter?.clear()
            binding.assignedUsernameInput.setText("")
            binding.assignedPasswordInput.setText("")
            binding.assignedApiInput.setText("")
            binding.assignedEmailInput.setText("")
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
            val usernames = editorRows
                .asSequence()
                .filter { (it.device_alias ?: "").trim() == alias }
                .mapNotNull { it.username?.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()

            suppressAssignedUsernameSelectCallback = true
            assignedUsernameAdapter?.clear()
            assignedUsernameAdapter?.addAll(usernames)
            assignedUsernameAdapter?.notifyDataSetChanged()
            binding.assignedUsernameSelect.isEnabled = usernames.isNotEmpty()

            if (usernames.isNotEmpty()) {
                binding.assignedUsernameSelect.setSelection(0)
                applyAssignedAccountByUsername(usernames.first())
            } else {
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
            binding.assignedCopyStatusText.text = "未找到该 username 对应的账号表单数据：$username"
            return
        }
        binding.assignedUsernameInput.setText(account.username.orEmpty())
        binding.assignedPasswordInput.setText(account.password.orEmpty())
        binding.assignedApiInput.setText(account.api.orEmpty())
        binding.assignedEmailInput.setText(account.email.orEmpty())
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

        assignedUsernameAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf())
        binding.assignedUsernameSelect.adapter = assignedUsernameAdapter
        binding.assignedUsernameSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressAssignedUsernameSelectCallback) return
                val uname = assignedUsernameAdapter?.getItem(position).orEmpty()
                applyAssignedAccountByUsername(uname)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                if (suppressAssignedUsernameSelectCallback) return
                applyAssignedAccountByUsername("")
            }
        }
        refreshAssignedAccountFields()
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
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
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

    private fun observeLogs() {
        lifecycleScope.launch {
            LogCenter.logs.collect { logs ->
                binding.logText.text = logs.takeLast(120).joinToString("\n")
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
        val labels = listOf("涨粉模式", "主播访问模式", "直客访问模式")
        binding.runModeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        fun indexForStored(): Int = when (PrefsStore.getDeviceRunMode()) {
            ServerRunMode.LIVE -> 1
            ServerRunMode.DIRECT -> 2
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
            runCatching {
                ApiService.bindDevice(alias, DeviceFingerprint.fromContext(this@MainActivity))
            }.onSuccess { response ->
                if (response.code == 0 && !response.device_id.isNullOrBlank()) {
                    PrefsStore.saveBinding(response.device_id, alias)
                    PrefsStore.markBindingRefreshedNow()
                    LogCenter.op(alias, "绑定成功：${response.device_id}")
                    AppCoordinator.syncOnline()
                    binding.bindStatusText.text = "绑定成功"
                    WorkerService.start(this@MainActivity)
                    AppCoordinator.startIfNeeded()
                } else {
                    binding.bindStatusText.text = response.msg
                }
                updateBindSectionState()
                renderDeviceInfo()
            }.onFailure {
                binding.bindStatusText.text = "绑定失败：${it.message}"
            }
        }
    }

    private enum class HistoryFilter { ALL, LOCAL, CLOUD }
}
