package com.autotraces.app

import android.Manifest
import android.content.Intent
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
import com.autotraces.app.backup.BackupRepository
import com.autotraces.app.backup.model.BackupRecord
import com.autotraces.app.backup.network.BackupReporter
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

    private val backupRepository by lazy { BackupRepository(this) }
    private val backupReporter by lazy { BackupReporter() }

    private var selectedCloudTaskRecord: BackupRecord? = null
    private var currentHistoryFilter: HistoryFilter = HistoryFilter.ALL

    private val pickBackupFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch { handlePickedBackupFile(uri) }
    }

    private val backupRecordAdapter by lazy {
        object : android.widget.BaseAdapter() {
            private val items = mutableListOf<BackupRecord>()

            fun submitList(list: List<BackupRecord>) {
                items.clear()
                items.addAll(list)
                notifyDataSetChanged()
            }

            fun getItemAt(position: Int): BackupRecord = items[position]

            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = items[position].id
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val title = view.findViewById<TextView>(android.R.id.text1)
                val subtitle = view.findViewById<TextView>(android.R.id.text2)
                val item = items[position]
                title.text = "[${item.backupScope}/${item.backupType}] ${item.accountName} / ${item.filename}"
                subtitle.text = buildString {
                    append(if (item.success) "成功" else "失败")
                    append(" · ")
                    append(item.message)
                    if (!item.exitCode.isNullOrBlank()) {
                        append(" · exit=")
                        append(item.exitCode)
                    }
                    if (!item.cloudTaskId.isNullOrBlank()) {
                        append(" · task=")
                        append(item.cloudTaskId)
                        append(" · ")
                        append(item.uploadStatus ?: "-")
                    }
                }
                return view
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setupBindingSection()
        setupRunModeSpinner()
        setupBackupSection()
        setupBackupRecordList()
        renderDeviceInfo()
        observeLogs()
        refreshBackupRecords()

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
                val deviceId = PrefsStore.getDeviceId()
                if (!deviceId.isNullOrBlank()) {
                    runCatching { ApiService.setDeviceAutoStartOptOut(deviceId, true) }.onSuccess { response ->
                        if (response.code == 0) LogCenter.op(alias, "已关闭云端自动开启程序")
                        else LogCenter.op(alias, "关闭云端自动开启程序失败：${response.msg}")
                    }.onFailure { error ->
                        LogCenter.op(alias, "关闭云端自动开启程序异常：${error.message}")
                    }
                }
                WorkerService.stop(this@MainActivity)
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

        AppCoordinator.startIfNeeded()
        if (PrefsStore.isBound() && !WorkerServiceTracker.isRunning) WorkerService.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (requireCloudLoginIfNeeded()) {
            renderDeviceInfo()
            return
        }
        ensureBoundIfNeeded()
        renderDeviceInfo()
        refreshBackupRecords()
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
                    appendLine("服务端: ${state.currentServerUrl}")
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

    private fun setupBackupSection() {
        val localBackupButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.localBackupButton)
        val cloudBackupButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.cloudBackupButton)
        val pickBackupFileButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.pickBackupFileButton)
        val restoreLatestButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.restoreLatestButton)
        val reportButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.reportBackupButton)
        val pollTaskButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.pollTaskButton)
        val showAllHistoryButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.showAllHistoryButton)
        val showLocalHistoryButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.showLocalHistoryButton)
        val showCloudHistoryButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.showCloudHistoryButton)
        val backupStatusText = binding.root.findViewById<TextView>(R.id.backupStatusText)
        val cloudTaskStatusText = binding.root.findViewById<TextView>(R.id.cloudTaskStatusText)

        localBackupButton.setOnClickListener { lifecycleScope.launch { doBackup(scope = "local", statusText = backupStatusText) } }
        cloudBackupButton.setOnClickListener { lifecycleScope.launch { doBackup(scope = "cloud", statusText = backupStatusText) } }
        pickBackupFileButton.setOnClickListener { pickBackupFileLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        restoreLatestButton.setOnClickListener {
            lifecycleScope.launch {
                val latest = withContext(Dispatchers.IO) { backupRepository.latest() }
                if (latest == null) {
                    backupStatusText.text = "没有可恢复的备份记录"
                    return@launch
                }
                restoreAndReportLatest(latest, backupStatusText)
            }
        }
        reportButton.setOnClickListener {
            lifecycleScope.launch {
                val deviceId = PrefsStore.getDeviceId().orEmpty()
                if (deviceId.isBlank()) {
                    backupStatusText.text = "请先完成设备绑定"
                    return@launch
                }
                val latest = withContext(Dispatchers.IO) { backupRepository.latest() }
                if (latest == null) {
                    backupStatusText.text = "没有可上报的备份记录"
                    return@launch
                }
                backupStatusText.text = "正在上报..."
                val ok = runCatching { backupReporter.reportRestoreResult(deviceId, latest.accountName, latest) }.getOrDefault(false)
                backupStatusText.text = if (ok) "上报成功" else "上报失败，请检查服务端接口"
            }
        }
        pollTaskButton.setOnClickListener {
            lifecycleScope.launch {
                val record = selectedCloudTaskRecord ?: withContext(Dispatchers.IO) {
                    backupRepository.cloudHistory().firstOrNull { !it.cloudTaskId.isNullOrBlank() }
                }
                if (record == null || record.cloudTaskId.isNullOrBlank()) {
                    cloudTaskStatusText.text = "没有可轮询的云端任务"
                    return@launch
                }
                cloudTaskStatusText.text = "正在轮询任务 ${record.cloudTaskId} ..."
                val refreshed = withContext(Dispatchers.IO) { backupRepository.refreshCloudTaskStatus(record) }
                selectedCloudTaskRecord = refreshed
                cloudTaskStatusText.text = buildCloudTaskStatusText(refreshed)
                refreshBackupRecords()
            }
        }

        showAllHistoryButton.setOnClickListener {
            currentHistoryFilter = HistoryFilter.ALL
            refreshBackupRecords()
            refreshFilterButtonState(showAllHistoryButton, showLocalHistoryButton, showCloudHistoryButton)
        }
        showLocalHistoryButton.setOnClickListener {
            currentHistoryFilter = HistoryFilter.LOCAL
            refreshBackupRecords()
            refreshFilterButtonState(showAllHistoryButton, showLocalHistoryButton, showCloudHistoryButton)
        }
        showCloudHistoryButton.setOnClickListener {
            currentHistoryFilter = HistoryFilter.CLOUD
            refreshBackupRecords()
            refreshFilterButtonState(showAllHistoryButton, showLocalHistoryButton, showCloudHistoryButton)
        }
        refreshFilterButtonState(showAllHistoryButton, showLocalHistoryButton, showCloudHistoryButton)
    }

    private fun refreshFilterButtonState(
        allButton: com.google.android.material.button.MaterialButton,
        localButton: com.google.android.material.button.MaterialButton,
        cloudButton: com.google.android.material.button.MaterialButton,
    ) {
        allButton.isChecked = currentHistoryFilter == HistoryFilter.ALL
        localButton.isChecked = currentHistoryFilter == HistoryFilter.LOCAL
        cloudButton.isChecked = currentHistoryFilter == HistoryFilter.CLOUD
    }

    private fun setupBackupRecordList() {
        val listView = binding.root.findViewById<ListView>(R.id.backupRecordList)
        val statusText = binding.root.findViewById<TextView>(R.id.backupStatusText)
        val detailText = binding.root.findViewById<TextView>(R.id.backupDetailText)
        val cloudTaskStatusText = binding.root.findViewById<TextView>(R.id.cloudTaskStatusText)

        listView.adapter = backupRecordAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                val record = backupRecordAdapter.getItemAt(position)
                selectedCloudTaskRecord = if (!record.cloudTaskId.isNullOrBlank()) record else selectedCloudTaskRecord
                detailText.text = buildBackupDetailText(record)
                cloudTaskStatusText.text = if (!record.cloudTaskId.isNullOrBlank()) buildCloudTaskStatusText(record) else "当前记录没有云端任务 ID"
                statusText.text = "正在恢复：${record.filename}"

                val restored = withContext(Dispatchers.IO) { backupRepository.restoreFromRecord(record) }
                statusText.text = if (restored.success) "恢复成功：${restored.filename}" else "恢复失败：${restored.message}"

                LogCenter.op(record.accountName, "点击记录恢复完成：${restored.message}")
                refreshBackupRecords()

                val deviceId = PrefsStore.getDeviceId().orEmpty()
                if (deviceId.isNotBlank()) {
                    val ok = runCatching { backupReporter.reportRestoreResult(deviceId, record.accountName, restored) }.getOrDefault(false)
                    statusText.text = statusText.text.toString() + if (ok) "，上报成功" else "，上报失败"
                }
            }
        }
    }

    private fun refreshBackupRecords() {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                when (currentHistoryFilter) {
                    HistoryFilter.ALL -> backupRepository.history()
                    HistoryFilter.LOCAL -> backupRepository.localHistory()
                    HistoryFilter.CLOUD -> backupRepository.cloudHistory()
                }
            }
            backupRecordAdapter.submitList(records)
            binding.root.findViewById<TextView>(R.id.backupDetailText).text = if (records.isNotEmpty()) buildBackupDetailText(records.first()) else "当前筛选下没有备份记录"
            binding.root.findViewById<TextView>(R.id.cloudTaskStatusText).text = records.firstOrNull { !it.cloudTaskId.isNullOrBlank() }?.let { buildCloudTaskStatusText(it) } ?: "暂无云端任务"
        }
    }

    private fun buildBackupDetailText(record: BackupRecord): String = buildString {
        appendLine("ID: ${record.id}")
        appendLine("DeviceAlias: ${record.deviceAlias}")
        appendLine("DeviceId: ${record.deviceId}")
        appendLine("AppAccount: ${record.appAccount}")
        appendLine("Account: ${record.accountName}")
        appendLine("Package: ${record.packageName}")
        appendLine("Type: ${record.backupType}")
        appendLine("Scope: ${record.backupScope}")
        appendLine("Success: ${record.success}")
        appendLine("File: ${record.filename}")
        appendLine("Path: ${record.path}")
        appendLine("Checksum: ${record.checksum ?: "-"}")
        appendLine("UploadStatus: ${record.uploadStatus ?: "-"}")
        appendLine("CloudTaskId: ${record.cloudTaskId ?: "-"}")
        appendLine("ExitCode: ${record.exitCode?.toString() ?: "-"}")
        appendLine("Command: ${record.command ?: "-"}")
        appendLine("Output: ${record.output ?: "-"}")
        appendLine("Message: ${record.message}")
        append("CreatedAt: ${record.createdAt}")
    }

    private fun buildCloudTaskStatusText(record: BackupRecord): String {
        return "CloudTaskId=${record.cloudTaskId ?: "-"} | UploadStatus=${record.uploadStatus ?: "-"} | Scope=${record.backupScope}"
    }

    private suspend fun doBackup(scope: String, statusText: TextView) {
        val accountName = PrefsStore.getAlias().orEmpty().ifBlank { "default" }
        val packageName = AppConstants.APP_PACKAGE_NAME
        statusText.text = if (scope == "cloud") "正在云端备份..." else "正在本地备份..."

        val record = withContext(Dispatchers.IO) {
            if (scope == "cloud") backupRepository.backupCloud(accountName, packageName) else backupRepository.backupLocal(accountName, packageName)
        }

        statusText.text = if (record.success) {
            if (record.backupScope == "cloud") "云端备份成功：${record.filename} · task=${record.cloudTaskId ?: "-"}" else "本地备份成功：${record.filename}"
        } else {
            "备份失败：${record.message}"
        }

        LogCenter.op(accountName, "备份任务完成：${record.message}")
        refreshBackupRecords()
        Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_SHORT).show()
    }

    private suspend fun restoreAndReportLatest(record: BackupRecord, statusText: TextView) {
        val restored = withContext(Dispatchers.IO) { backupRepository.restoreFromRecord(record) }
        statusText.text = if (restored.success) "恢复成功：${restored.filename}" else "恢复失败：${restored.message}"

        val deviceId = PrefsStore.getDeviceId().orEmpty()
        if (deviceId.isNotBlank()) {
            val ok = runCatching { backupReporter.reportRestoreResult(deviceId, record.accountName, restored) }.getOrDefault(false)
            statusText.text = statusText.text.toString() + if (ok) "，上报成功" else "，上报失败"
        }

        refreshBackupRecords()
    }

    private suspend fun handlePickedBackupFile(uri: Uri) {
        val backupStatusText = binding.root.findViewById<TextView>(R.id.backupStatusText)
        backupStatusText.text = "正在读取选择的备份文件..."

        val localCopy = withContext(Dispatchers.IO) { copyUriToCache(uri) }
        if (localCopy == null) {
            backupStatusText.text = "文件复制失败，无法恢复"
            return
        }

        val record = BackupRecord(
            accountName = PrefsStore.getAlias().orEmpty().ifBlank { "default" },
            packageName = AppConstants.APP_PACKAGE_NAME,
            deviceAlias = PrefsStore.getAlias().orEmpty(),
            deviceId = PrefsStore.getDeviceId().orEmpty(),
            appAccount = PrefsStore.getAuthUsername().orEmpty(),
            backupType = "restore",
            backupScope = "local",
            success = false,
            path = localCopy.absolutePath,
            filename = localCopy.name,
            checksum = null,
            message = "selected by file picker",
        )

        backupStatusText.text = "正在恢复选择的文件..."
        val restored = withContext(Dispatchers.IO) { backupRepository.restoreFromRecord(record.copy(path = localCopy.absolutePath)) }
        backupStatusText.text = if (restored.success) "恢复成功：${restored.filename}" else "恢复失败：${restored.message}"

        val deviceId = PrefsStore.getDeviceId().orEmpty()
        if (deviceId.isNotBlank()) {
            val ok = runCatching { backupReporter.reportRestoreResult(deviceId, record.accountName, restored) }.getOrDefault(false)
            backupStatusText.text = backupStatusText.text.toString() + if (ok) "，上报成功" else "，上报失败"
        }

        refreshBackupRecords()
    }

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
