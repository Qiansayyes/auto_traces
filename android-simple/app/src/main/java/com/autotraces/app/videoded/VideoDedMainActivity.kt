package com.autotraces.app.videoded

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VideoDedMainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "VideoDedMainActivity"
        const val EXTRA_AUTO_REQUEST_COUNT = "autoRequestCount"
    }

    private lateinit var statusView: TextView
    private lateinit var nameHint: TextView
    private lateinit var windowsUrlInput: EditText
    private lateinit var countInput: EditText
    private lateinit var currentTaskView: TextView
    private lateinit var lastResultView: TextView
    private lateinit var historySummaryView: TextView
    private lateinit var apiTokenView: TextView
    private lateinit var apiLocalUrlView: TextView
    private lateinit var apiToggleView: TextView
    private lateinit var historyButton: Button
    private lateinit var copyTokenButton: Button
    private lateinit var mirrorAddrView: TextView
    private lateinit var adbTcpPortInput: EditText
    private lateinit var rootAdbStatusView: TextView
    private lateinit var copyMirrorAddrButton: Button
    private lateinit var mirrorIpOverrideInput: EditText
    private var pollJob: Job? = null
    private var currentTaskId: String? = null
    /** 轮询 PC / 本地任务状态去重，避免每 10 秒刷同样一行 */
    private var lastTaskPollFingerprint: String? = null

    private val historyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            refreshHistorySummary()
            val taskId = result.data?.getStringExtra("taskId")
            if (taskId != null) {
                currentTaskId = taskId
                lifecycleScope.launch { refreshCurrentTaskStatus(taskId) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate start")
        startForegroundService(Intent(this, VideoDedDeviceService::class.java))
        buildUi()
        lifecycleScope.launch { autoSaveSettingsAndRegister(verbose = false) }
        refreshHistorySummary()
        refreshApiSection()
        startPollingCurrentTask()
        val autoRequestCount = intent.getIntExtra(EXTRA_AUTO_REQUEST_COUNT, 0)
        if (autoRequestCount > 0) {
            val normalizedCount = autoRequestCount.coerceIn(1, 20)
            countInput.setText(normalizedCount.toString())
            appendLog("临时入口触发：自动请求视频 $normalizedCount 条")
            lifecycleScope.launch { requestVideos(normalizedCount) }
        }
        Log.d(TAG, "onCreate done")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        if (::nameHint.isInitialized) refreshDeviceNameLabels()
        refreshApiSection()
        if (::mirrorIpOverrideInput.isInitialized && mirrorIpOverrideInput.text.isNullOrBlank()) {
            val wifiIp = WifiUtils.getWifiIp(this).orEmpty()
            Log.d(TAG, "auto fill mirrorIpOverrideInput=$wifiIp")
            mirrorIpOverrideInput.setText(wifiIp)
            lifecycleScope.launch { autoSaveSettingsAndRegister(verbose = false) }
        } else if (::statusView.isInitialized && statusView.text?.contains("初始化") == true) {
            // 从通知/后台回到本页时，若仍停在初始化文案则补一次注册刷新
            lifecycleScope.launch { autoSaveSettingsAndRegister(verbose = false) }
        }
        if (::mirrorAddrView.isInitialized) {
            refreshMirrorAddrLine()
        }
    }

    private fun buildUi() {
        val scroll =
            NestedScrollView(this).apply {
                isFillViewport = false
            }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = getString(com.autotraces.app.R.string.video_ded_app_name)
            textSize = 24f
        }
        nameHint = TextView(this).apply {
            text = CompanionApi.formatDeviceNameLine(this@VideoDedMainActivity)
            setPadding(0, 6, 0, 12)
        }

        statusView = TextView(this).apply {
            text = "状态：初始化中"
            setPadding(0, 16, 0, 16)
        }

        container.addView(title)
        container.addView(nameHint)
        container.addView(statusView)
        container.addView(buildSettingsSection())
        container.addView(buildApiSection())
        container.addView(buildRequestSection())
        container.addView(buildStatusSection())
        container.addView(buildHistorySection())
        scroll.addView(container)
        setContentView(scroll)
    }

    private fun buildSettingsSection(): View {
        val section = sectionContainer("PC 服务 / 投屏地址")
        windowsUrlInput = editText("PC 服务地址", CompanionApi.windowsBaseUrl(this))
        section.addView(windowsUrlInput)
        val saveSettingsButton = Button(this).apply {
            text = "保存设置并重新注册"
            setOnClickListener {
                lifecycleScope.launch { autoSaveSettingsAndRegister(verbose = true) }
            }
        }
        section.addView(saveSettingsButton)
        val mirrorIpTitle = TextView(this).apply {
            text = "设备 IPv4"
            setPadding(0, 8, 0, 4)
        }
        mirrorIpOverrideInput = editText(
            "本机 IPv4（自动获取后可直接保存）",
            ApiSettings.getMirrorIpOverride(this).orEmpty().ifBlank { WifiUtils.getWifiIp(this).orEmpty() },
            inputType = InputType.TYPE_CLASS_TEXT,
        )
        mirrorAddrView = TextView(this).apply {
            setPadding(0, 12, 0, 4)
        }
        adbTcpPortInput = editText("ADB 无线端口", ApiSettings.getAdbTcpipPort(this).toString(), inputType = InputType.TYPE_CLASS_NUMBER)
        rootAdbStatusView = TextView(this).apply {
            text = "Root：Wi‑Fi 后自动尝试无线 ADB（常开）"
            setPadding(0, 8, 0, 8)
        }
        copyMirrorAddrButton = Button(this).apply {
            text = "复制 PC 无线 ADB 地址"
            setOnClickListener { copyMirrorAddressToClipboard() }
        }
        section.addView(mirrorIpTitle)
        section.addView(mirrorIpOverrideInput)
        section.addView(mirrorAddrView)
        section.addView(adbTcpPortInput)
        section.addView(rootAdbStatusView)
        section.addView(copyMirrorAddrButton)
        refreshMirrorAddrLine()
        return section
    }

    private fun refreshDeviceNameLabels() {
        val line = CompanionApi.formatDeviceNameLine(this)
        if (::nameHint.isInitialized) nameHint.text = line
    }

    private fun buildApiSection(): View {
        val section = sectionContainer("外部调用接口")
        apiToggleView = TextView(this).apply {
            text = "API："
            setPadding(0, 8, 0, 8)
        }
        apiTokenView = TextView(this).apply {
            text = "Token："
            setPadding(0, 8, 0, 8)
        }
        apiLocalUrlView = TextView(this).apply {
            text = "本地 Base URL："
            setPadding(0, 0, 0, 8)
        }
        copyTokenButton = Button(this).apply {
            text = "刷新并显示 Token"
            setOnClickListener {
                refreshApiSection()
            }
        }
        section.addView(apiToggleView)
        section.addView(apiTokenView)
        section.addView(apiLocalUrlView)
        section.addView(copyTokenButton)
        return section
    }

    private fun buildRequestSection(): View {
        val section = sectionContainer("请求页")
        countInput = editText("请求视频数量", "1", inputType = InputType.TYPE_CLASS_NUMBER)
        val requestButton = Button(this).apply {
            text = "请求并下载视频"
            setOnClickListener {
                val count = countInput.text?.toString()?.toIntOrNull() ?: 1
                Log.d(TAG, "request button clicked count=$count targetPath=${CompanionApi.DEFAULT_MATERIAL_STORAGE_PATH}")
                lifecycleScope.launch {
                    requestVideos(count)
                }
            }
        }
        lastResultView = TextView(this).apply {
            text = "最近结果：无"
            setPadding(0, 8, 0, 8)
        }
        section.addView(countInput)
        section.addView(requestButton)
        section.addView(lastResultView)
        return section
    }

    private fun buildStatusSection(): View {
        val section = sectionContainer("任务状态页")
        currentTaskView = TextView(this).apply {
            text = "当前任务：无"
            setPadding(0, 8, 0, 8)
        }
        val refreshButton = Button(this).apply {
            text = "手动刷新任务状态"
            setOnClickListener { lifecycleScope.launch { refreshCurrentTaskStatus() } }
        }
        val logButton = Button(this).apply {
            text = "查看状态日志"
            setOnClickListener {
                startActivity(Intent(this@VideoDedMainActivity, StatusLogActivity::class.java))
            }
        }
        section.addView(currentTaskView)
        section.addView(refreshButton)
        section.addView(logButton)
        return section
    }

    private fun buildHistorySection(): View {
        val section = sectionContainer("任务历史")
        historySummaryView = TextView(this).apply {
            text = "暂无历史任务"
            setPadding(0, 8, 0, 8)
        }
        historyButton = Button(this).apply {
            text = "打开任务历史与重试"
            setOnClickListener {
                historyLauncher.launch(Intent(this@VideoDedMainActivity, TaskHistoryActivity::class.java))
            }
        }
        section.addView(historySummaryView)
        section.addView(historyButton)
        return section
    }

    private fun refreshApiSection() {
        apiToggleView.text = "API：已启用（常开）"
        apiTokenView.text = "Token：${ApiSettings.getToken(this)}"
        val p = ApiSettings.getListenPort(this)
        apiLocalUrlView.text =
            "本地 Base URL：${ApiSettings.localBaseUrl(this)}（固定端口 $p；异包同机见 docs/INTEGRATION.md §3.1；Header：X-Api-Token）"
    }

    private fun sectionContainer(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
            addView(TextView(this@VideoDedMainActivity).apply {
                text = title
                textSize = 20f
            })
        }
    }

    private fun editText(hint: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            this.inputType = inputType
            setPadding(0, 8, 0, 8)
        }
    }

    private suspend fun autoSaveSettingsAndRegister(verbose: Boolean) {
        val port = adbTcpPortInput.text?.toString()?.toIntOrNull() ?: 5555
        val rawIp = mirrorIpOverrideInput.text?.toString().orEmpty().trim()
        val windowsUrl = windowsUrlInput.text?.toString().orEmpty().trim()
        Log.d(TAG, "autoSaveSettingsAndRegister port=$port rawIp=$rawIp windowsUrl=$windowsUrl verbose=$verbose")
        if (rawIp.isNotEmpty() && !ApiSettings.isPlausibleLanIpv4(rawIp)) {
            Log.w(TAG, "invalid mirror ip: $rawIp")
            if (verbose) {
                Toast.makeText(this@VideoDedMainActivity, "本机 IPv4 格式不正确，请检查或留空", Toast.LENGTH_LONG).show()
            }
            appendLog("保存中止：IPv4 格式不正确")
            return
        }
        val result = VideoDedSettingsRegistrar.saveAndRegister(
            applicationContext,
            input = VideoDedSettingsRegistrar.SaveInput(
                windowsUrl = windowsUrl.ifEmpty { null },
                mirrorIp = rawIp.ifEmpty { null },
                adbPort = port,
            ),
            triggerWirelessIfNeeded = true,
            retryRootAdbIfNeeded = true,
            logSave = true,
            logRegistration = true,
        )
        windowsUrlInput.hint = "PC 服务地址"
        refreshMirrorAddrLine()
        refreshDeviceNameLabels()
        applyRegistrationUi(result, verbose)
    }

    private suspend fun applyRegistrationUi(result: VideoDedSettingsRegistrar.SaveAndRegisterResult, verbose: Boolean) {
        if (CompanionApi.isUsbPcTunnelActive()) {
            statusView.text = "状态：USB 已直连 PC（走 adb 隧道，无需 WiFi / 不用改上方地址）"
            if (verbose) {
                Toast.makeText(this@VideoDedMainActivity, "USB 已连接 PC，批量操作请直接在电脑网页执行", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (result.error != null) {
            val reason = explainWindowsServiceFailure(result.error)
            Log.e(TAG, "registration failed reason=$reason", result.error)
            statusView.text = "状态：未连上 PC（$reason）。USB 已插时请先在电脑打开 VideoDed 网页操作，会自动建隧道"
            if (verbose) {
                Toast.makeText(this@VideoDedMainActivity, "注册失败：$reason", Toast.LENGTH_LONG).show()
            }
            return
        }
        statusView.text = "状态：已连接 Windows 服务"
        if (!verbose) return
        val reg = result.registration ?: return
        val mirrorStatus = MirrorStateProbe.currentMirrorStatus(this)
        val mirrorHint = MirrorStateProbe.currentMirrorMessage(this)
        val port = ApiSettings.getAdbTcpipPort(this)
        val tcpOpen = AdbRuntimeProbe.isAdbTcpPortOpen(this, port)
        val pcAdbState = reg.optString("pcAdbState", "").trim()
        val pcSerial = reg.optString("pcAdbSerial", "").trim()
        val toast = when {
            mirrorStatus == "ready" || pcAdbState == "device" ->
                "注册成功，PC 已可投屏${if (pcSerial.isNotEmpty()) "（$pcSerial）" else ""}"
            else -> buildString {
                append("注册成功，但投屏未就绪（$mirrorStatus）")
                if (!RootWifiAdbHelper.hasSu() && ApiSettings.isRootAdbTcpipEnabled(this@VideoDedMainActivity)) {
                    append("\n请检查无线 ADB 辅助权限是否可用")
                } else if (!tcpOpen) {
                    append("\n本机 $port 仍未监听，请看通知栏错误码")
                }
                mirrorHint?.let { append("\n${mirrorHintForStatusLog(it)}") }
            }
        }
        Toast.makeText(this@VideoDedMainActivity, toast, Toast.LENGTH_LONG).show()
    }

    private fun explainWindowsServiceFailure(t: Throwable): String {
        val msg = t.message.orEmpty().trim()
        val lower = msg.lowercase()
        return when {
            lower.contains("request_failed_401") || lower.contains("unauthorized") || lower.contains("forbidden") ->
                "鉴权失败，请检查 Windows 端 Token"
            lower.contains("request_failed_404") ->
                "接口地址不存在，请检查 Windows 服务地址或版本"
            lower.contains("request_failed_503") ->
                "Windows 服务不可用，可能未启动或已禁用"
            lower.contains("request_failed_5") ->
                "Windows 服务内部错误（$msg）"
            lower.contains("connect") && (lower.contains("failed") || lower.contains("refused") || lower.contains("timeout") || lower.contains("timed out")) ->
                "网络连接失败，请检查 IP、端口、Wi‑Fi 和防火墙"
            lower.contains("timeout") ->
                "连接超时，请检查 Windows 服务是否启动、地址是否正确"
            lower.contains("unknown host") ->
                "主机名无法解析，请检查服务地址"
            lower.contains("ssl") ->
                "HTTPS/证书问题，请确认使用的是 http:// 地址"
            msg.isNotEmpty() ->
                msg
            else ->
                t.javaClass.simpleName
        }
    }

    private suspend fun requestVideos(count: Int) {
        val targetPath = CompanionApi.DEFAULT_MATERIAL_STORAGE_PATH
        try {
            Log.d(TAG, "requestVideos start count=$count targetPath=$targetPath")
            val task = CompanionApi.createTaskAndWaitReady(applicationContext, count, targetPath)
            Log.d(TAG, "requestVideos task=${task.taskId} status=${task.status} files=${task.files.size}")
            currentTaskId = task.taskId
            currentTaskView.text = "当前任务：${task.taskId} (${task.status})"
            TaskStore.saveTaskSnapshot(
                this,
                TaskSnapshot(
                    taskId = task.taskId,
                    status = task.status,
                    error = null,
                    createdAtMs = System.currentTimeMillis(),
                    targetPath = targetPath,
                    files = task.files.map {
                        TaskFileSnapshot(it.fileName, it.sizeBytes, it.md5, "pending", null)
                    }
                )
            )
            refreshHistorySummary()
            appendLog("创建任务：${task.taskId} channel=${task.deliveryChannel ?: "http"} pushed=${task.adbPushed == true}")
            if (task.adbPushed == true || task.companionPullNotified != true) {
                downloadTask(task.taskId, task.files, targetPath, adbPushed = task.adbPushed == true)
            } else {
                appendLog("PC 已触发下载（follow-pc），本界面不重复执行")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "requestVideos failed", t)
            lastResultView.text = "最近结果：请求失败 ${t.message}"
            appendLog("请求失败：${t.message}")
        }
    }

    private suspend fun downloadTask(
        taskId: String,
        files: List<TaskFileInfo>,
        targetPath: String?,
        adbPushed: Boolean = false,
    ) {
        try {
            Log.d(TAG, "downloadTask start taskId=$taskId files=${files.size} targetPath=$targetPath adbPushed=$adbPushed")
            TaskDownloadWorkflow.downloadVerifyAndAck(
                applicationContext,
                taskId,
                files,
                targetPath,
                adbPushed = adbPushed,
            ) { msg ->
                Log.d(TAG, "downloadTask progress taskId=$taskId msg=$msg")
                appendLog(msg)
            }
            lastResultView.text = "最近结果：任务 $taskId 下载完成"
            currentTaskView.text = "当前任务：$taskId (completed)"
            refreshHistorySummary()
            Log.d(TAG, "downloadTask success taskId=$taskId")
        } catch (t: Throwable) {
            Log.e(TAG, "downloadTask failed taskId=$taskId", t)
            refreshHistorySummary()
            lastResultView.text = "最近结果：任务 $taskId 失败 ${t.message}"
        }
    }

    private fun startPollingCurrentTask() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                val snapshot =
                    TaskStore.loadTasks(this@VideoDedMainActivity)
                        .firstOrNull { t ->
                            t.status != "completed" && t.status != "failed"
                        }
                val idToPoll = currentTaskId ?: snapshot?.taskId
                if (idToPoll != null) {
                    refreshCurrentTaskStatus(idToPoll)
                }
                delay(10_000)
            }
        }
    }

    private suspend fun refreshCurrentTaskStatus(taskId: String? = null) {
        val id = taskId ?: currentTaskId ?: return
        try {
            Log.d(TAG, "refreshCurrentTaskStatus id=$id")
            val local = TaskStore.loadTasks(this).firstOrNull { it.taskId == id }
            val status = CompanionApi.getTaskStatus(applicationContext, id)
            val localSt = local?.status ?: "—"
            currentTaskView.text = "当前任务：${status.taskId}（PC ${status.status} · 本机 $localSt）"
            val err = status.error?.trim().orEmpty()
            val fp = "${status.taskId}|${status.status}|$localSt|$err"
            if (fp != lastTaskPollFingerprint) {
                lastTaskPollFingerprint = fp
                val line = if (err.isNotEmpty()) {
                    "任务 ${status.taskId.take(8)} → PC:${status.status} · 本机:$localSt · $err"
                } else {
                    "任务 ${status.taskId.take(8)} → PC:${status.status} · 本机:$localSt"
                }
                appendLog(line)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "refreshCurrentTaskStatus failed id=$id", t)
            val failFp = "${id}|err|${t.message}"
            if (failFp != lastTaskPollFingerprint) {
                lastTaskPollFingerprint = failFp
                appendLog("刷新状态失败：${t.message}")
            }
        }
    }

    private fun refreshHistorySummary() {
        val history = TaskStore.loadTasks(this)
        if (history.isEmpty()) {
            historySummaryView.text = "暂无历史任务"
            return
        }
        val latest = history.first()
        historySummaryView.text = "最近任务：${latest.taskId.take(8)} / ${latest.status} / ${latest.files.size} 个文件"
    }

    private fun appendLog(message: String) {
        Log.d(TAG, "appendLog: $message")
        StatusLogBuffer.append(message)
    }

    /** 状态日志中不展示 su / Root 等字样，改为通用提示 */
    private fun mirrorHintForStatusLog(hint: String): String {
        val lower = hint.lowercase()
        if ("su" in lower || "root" in lower) {
            return "无线 ADB 或授权未完成，请检查开发者选项与本机 ADB 端口"
        }
        return hint
    }

    private fun refreshMirrorAddrLine() {
        val port = ApiSettings.getAdbTcpipPort(this)
        val ip = ApiSettings.getEffectiveMirrorLanIpv4(this)
        Log.d(TAG, "refreshMirrorAddrLine ip=$ip port=$port")
        mirrorAddrView.text = if (ip.isNullOrBlank()) {
            "无线 ADB 地址：adb connect <本机 IPv4>:$port"
        } else {
            "无线 ADB 地址：adb connect $ip:$port"
        }
    }

    private fun copyMirrorAddressToClipboard() {
        val port = ApiSettings.getAdbTcpipPort(this)
        val ip = ApiSettings.getEffectiveMirrorLanIpv4(this)
        Log.d(TAG, "copyMirrorAddressToClipboard ip=$ip port=$port")
        if (ip.isNullOrBlank()) {
            Toast.makeText(this, "未取得局域网 IPv4，请先在上方自动获取或手填本机 IP", Toast.LENGTH_LONG).show()
            return
        }
        val text = "$ip:$port"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("无线 ADB 地址", text))
        Toast.makeText(this, "已复制无线 ADB 地址 $text", Toast.LENGTH_SHORT).show()
    }
}
