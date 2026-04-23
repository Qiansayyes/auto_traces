package com.autotraces.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setupBindingSection()
        setupRunModeSpinner()
        renderDeviceInfo()
        observeLogs()

        binding.startServiceButton.setOnClickListener {
            lifecycleScope.launch {
                val alias = PrefsStore.getAlias()
                LogCenter.op(alias, "已请求启动后台服务")
                runCatching {
                    WorkerService.start(this@MainActivity)
                }.onFailure { error ->
                    LogCenter.op(alias, "启动后台服务请求失败：${error.message}")
                    AppCoordinator.refreshRuntimeSnapshot()
                    return@launch
                }

                kotlinx.coroutines.delay(1200)
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
                val deviceId = PrefsStore.getDeviceId()
                if (!deviceId.isNullOrBlank()) {
                    runCatching {
                        ApiService.setDeviceAutoStartOptOut(deviceId, true)
                    }.onSuccess { response ->
                        if (response.code == 0) {
                            LogCenter.op(alias, "已关闭云端自动开启程序")
                        } else {
                            LogCenter.op(alias, "关闭云端自动开启程序失败：${response.msg}")
                        }
                    }.onFailure { error ->
                        LogCenter.op(alias, "关闭云端自动开启程序异常：${error.message}")
                    }
                }
                WorkerService.stop(this@MainActivity)
            }
        }

        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.showSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.showLogButton.setOnClickListener {
            binding.logPanel.visibility = android.view.View.VISIBLE
            binding.actionPanel.visibility = android.view.View.GONE
        }

        binding.showActionButton.setOnClickListener {
            binding.logPanel.visibility = android.view.View.GONE
            binding.actionPanel.visibility = android.view.View.VISIBLE
        }

        binding.clearLogButton.setOnClickListener {
            LogCenter.clear()
            LogCenter.op(PrefsStore.getAlias(), "日志已清空")
        }

        if (requireCloudLoginIfNeeded()) {
            return
        }

        applyInitialPanelFromIntent()

        AppCoordinator.startIfNeeded()
        if (PrefsStore.isBound() && !WorkerServiceTracker.isRunning) {
            WorkerService.start(this)
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
    }

    private fun requireCloudLoginIfNeeded(): Boolean {
        if (!PrefsStore.getAuthToken().isNullOrBlank()) {
            return false
        }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100,
            )
        }
    }


    private fun renderDeviceInfo() {
        binding.deviceInfoText.text = buildString {
            appendLine("Alias: ${PrefsStore.getAlias()}")
            appendLine("Device ID: ${PrefsStore.getDeviceId()}")
            appendLine("Local Status: ${PrefsStore.getLocalStatus()}")
            val au = PrefsStore.getAuthUsername()
            if (!au.isNullOrBlank()) appendLine("云端账号: $au")
            else appendLine("云端账号: 未登录（绑定前请在设置中登录）")
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
                    appendLine("服务端: ${state.currentServerUrl}")
                    append("最近状态: ${state.lastMessage}")
                }
                renderDeviceInfo()
            }
        }
    }

    private fun setupRunModeSpinner() {
        val labels = listOf("涨粉模式", "主播访问模式", "直客访问模式")
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        binding.runModeSpinner.adapter = adapter

        fun indexForStored(): Int = when (PrefsStore.getDeviceRunMode()) {
            ServerRunMode.LIVE -> 1
            ServerRunMode.DIRECT -> 2
            else -> 0
        }

        var suppressCallback = true
        binding.runModeSpinner.setSelection(indexForStored())
        binding.runModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressCallback) return
                val mode = when (position) {
                    1 -> ServerRunMode.LIVE
                    2 -> ServerRunMode.DIRECT
                    else -> ServerRunMode.GROWTH
                }
                PrefsStore.saveDeviceRunMode(mode)
                lifecycleScope.launch {
                    AppCoordinator.fetchConfig()
                }
                LogCenter.op(PrefsStore.getAlias(), "已切换本机运行模式为 $mode，正在拉取配置")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.runModeSpinner.post {
            suppressCallback = false
        }
    }

    private fun setupBindingSection() {
        binding.bindButton.setOnClickListener {
            bindDevice()
        }
        binding.bindButton.isEnabled = true
        binding.bindInputLayout.error = null
        binding.bindInput.setText(PrefsStore.getAlias().orEmpty())
        updateBindSectionState()
    }

    private fun updateBindSectionState() {
        val isBound = PrefsStore.isBound()
        binding.bindButton.text = if (isBound) {
            "重新绑定"
        } else {
            getString(R.string.action_bind)
        }
    }

    /**
     * 静默校验：确认本地 device_id 仍被服务端承认（换机、清库时可能失效）。
     * 不需要每次回到前台都打接口：默认 12 小时最多校验一次；手动点「重新绑定」仍随时校验。
     */
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
                ApiService.bindDevice(
                    PrefsStore.getAlias().orEmpty(),
                    DeviceFingerprint.fromContext(this@MainActivity),
                )
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
}
