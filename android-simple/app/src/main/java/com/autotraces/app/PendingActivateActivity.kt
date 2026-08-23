package com.autotraces.app

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivityPendingActivateBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 待活跃队列：与设置/操作/运行模式同级；展示云端增长数据与本地活跃追踪。 */
class PendingActivateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPendingActivateBinding
    private var accounts: List<DeviceAssignedAccountItem> = emptyList()
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPendingActivateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TopNavHelper.wire(this, TopNavHelper.Tab.PENDING_ACTIVATE)

        if (!ManualDeviceControl.ENABLED) {
            binding.pendingActivateHintText.text =
                "当前为云端模式，待活跃队列仅在本机手动模式下可用。"
            disableManualControls()
        }

        binding.pendingActivateDurationMinInput.setText(
            PrefsStore.getManualActivateDurationMin().toString(),
        )

        binding.pendingRefreshAccountsButton.setOnClickListener {
            lifecycleScope.launch { refreshAccounts(showToast = true) }
        }
        binding.pendingEnqueueButton.setOnClickListener { enqueueSelected() }
        binding.pendingClearQueueButton.setOnClickListener { clearQueue() }
        binding.pendingActivateNextButton.setOnClickListener { activateNext() }
        binding.pendingAutoActiveButton.setOnClickListener { startAutoActive() }
        binding.pendingSortByMinutesButton.setOnClickListener { sortByMinutes() }
        binding.pendingTrimByMinutesButton.setOnClickListener { trimByMinutes() }
        binding.pendingManageQueueButton.setOnClickListener {
            startActivity(Intent(this, PendingActivateQueueManageActivity::class.java))
        }

        lifecycleScope.launch {
            AppCoordinator.manualAccountUiEpoch.collectLatest {
                refreshQueueSummary()
                renderAccountStats()
            }
        }

        refreshQueueSummary()
        if (PrefsStore.isBound()) {
            lifecycleScope.launch { refreshAccounts(showToast = false) }
        } else {
            binding.pendingActivateStatusText.text = "请先绑定设备并登录云端"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshQueueSummary()
        renderAccountStats()
        updateButtonsEnabled()
    }

    private fun disableManualControls() {
        listOf(
            binding.pendingRefreshAccountsButton,
            binding.pendingEnqueueButton,
            binding.pendingClearQueueButton,
            binding.pendingActivateNextButton,
            binding.pendingAutoActiveButton,
            binding.pendingSortByMinutesButton,
            binding.pendingTrimByMinutesButton,
            binding.pendingManageQueueButton,
        ).forEach { it.isEnabled = false }
        binding.pendingAccountSpinner.isEnabled = false
        binding.pendingActivateDurationMinInput.isEnabled = false
    }

    private suspend fun refreshAccounts(showToast: Boolean) {
        binding.pendingActivateStatusText.text = "正在拉取云端账号…"
        val result = AppCoordinator.manualRefreshAssignedAccounts()
        result.onSuccess {
            accounts = fetchAccountsWithStats()
            renderAccountSpinner()
            renderAccountStats()
            binding.pendingActivateStatusText.text = "已加载 ${accounts.size} 个账号"
            if (showToast) {
                Toast.makeText(this, "已刷新 ${accounts.size} 个账号", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { err ->
            binding.pendingActivateStatusText.text = "拉取失败：${err.message}"
            if (showToast) {
                Toast.makeText(this, "拉取失败：${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
        updateButtonsEnabled()
    }

    private suspend fun fetchAccountsWithStats(): List<DeviceAssignedAccountItem> {
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return emptyList()
        return runCatching {
            val resp = ApiService.getDeviceAssignedAccounts(deviceId, alias)
            if (resp.code != 0) emptyList() else resp.items.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun renderAccountSpinner() {
        val labels = accounts.mapNotNull { item ->
            val u = item.username?.trim()?.removePrefix("@").orEmpty()
            if (u.isEmpty()) null else "@$u"
        }
        binding.pendingAccountSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        val selected = PrefsStore.getManualSelectedUsername()?.trim()?.removePrefix("@").orEmpty()
        if (selected.isNotBlank()) {
            val idx = accounts.indexOfFirst {
                it.username?.equals(selected, ignoreCase = true) == true
            }
            if (idx >= 0) binding.pendingAccountSpinner.setSelection(idx)
        }
    }

    private fun selectedUsername(): String {
        val idx = binding.pendingAccountSpinner.selectedItemPosition
        if (idx < 0 || idx >= accounts.size) return ""
        return accounts[idx].username?.trim()?.removePrefix("@").orEmpty()
    }

    private fun renderAccountStats() {
        val container = binding.pendingAccountStatsList
        container.removeAllViews()
        if (accounts.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无账号数据，请点「刷新账号」"
                setTextColor(ContextCompat.getColor(this@PendingActivateActivity, android.R.color.darker_gray))
                textSize = 12f
            }
            container.addView(empty)
            return
        }
        val queue = PrefsStore.getPendingActivateUsernames()
            .map { it.lowercase() }
            .toSet()
        val inflater = layoutInflater
        for (item in accounts) {
            val username = item.username?.trim()?.removePrefix("@").orEmpty()
            if (username.isEmpty()) continue
            val row = inflater.inflate(R.layout.item_pending_activate_stat, container, false)
            val inQueue = queue.contains(username.lowercase())
            row.findViewById<TextView>(R.id.statUsernameText).text =
                "@$username" + if (inQueue) " · 队列中" else ""
            row.findViewById<TextView>(R.id.statGrowthText).text =
                "粉丝 ${item.follower_count.orEmpty().ifBlank { "—" }}（+${item.follower_growth.orEmpty().ifBlank { "0" }}）" +
                    " · 总赞 ${item.heart_count.orEmpty().ifBlank { "—" }}（+${item.heart_growth.orEmpty().ifBlank { "0" }}）"
            val enqueueAt = PrefsStore.getPendingActivateEnqueueAt(username)
            val enqueueLabel = enqueueAt?.let { dateFmt.format(Date(it)) } ?: "—"
            val total = PrefsStore.getActivateTotalMinutes(username)
            val growth = PrefsStore.getActivateGrowthMinutes(username)
            val forYou = PrefsStore.getActivateForYouMinutes(username)
            row.findViewById<TextView>(R.id.statLocalText).text =
                "上次入队 $enqueueLabel · 累计活跃 ${total}分（涨粉${growth} / ForYou${forYou}）"
            container.addView(row)
        }
    }

    private fun refreshQueueSummary() {
        val queue = PrefsStore.getPendingActivateUsernames()
        binding.pendingQueueSummaryText.text = if (queue.isEmpty()) {
            "待活跃队列：空"
        } else {
            "待活跃队列（${queue.size}）：" + queue.joinToString(" → ") { u ->
                val total = PrefsStore.getActivateTotalMinutes(u)
                "@$u(${total}分)"
            }
        }
    }

    private fun updateButtonsEnabled() {
        val blocked = AppCoordinator.isManualSwitchBusy() || AppCoordinator.isBatchAccountSessionBusy()
        val hasUser = selectedUsername().isNotBlank()
        binding.pendingEnqueueButton.isEnabled = !blocked && hasUser && ManualDeviceControl.ENABLED
        binding.pendingClearQueueButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingActivateNextButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingAutoActiveButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingSortByMinutesButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingTrimByMinutesButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingManageQueueButton.isEnabled = ManualDeviceControl.ENABLED
    }

    private fun ensureReadyForStart(): Boolean {
        if (!ManualDeviceControl.ENABLED) {
            Toast.makeText(this, "请在本机手动模式下使用", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!PrefsStore.isBound()) {
            Toast.makeText(this, "请先绑定设备", Toast.LENGTH_SHORT).show()
            return false
        }
        if (PrefsStore.getAuthToken().isNullOrBlank()) {
            Toast.makeText(this, "请先登录云端", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun readDurationMinutes(): Int {
        val raw = binding.pendingActivateDurationMinInput.text?.toString()?.toIntOrNull()
            ?: PrefsStore.getManualActivateDurationMin()
        return raw.coerceIn(
            PrefsStore.MIN_MANUAL_ACTIVATE_DURATION_MIN,
            PrefsStore.MAX_MANUAL_ACTIVATE_DURATION_MIN,
        ).also {
            PrefsStore.setManualActivateDurationMin(it)
            binding.pendingActivateDurationMinInput.setText(it.toString())
        }
    }

    private fun enqueueSelected() {
        if (AppCoordinator.isManualSwitchBusy() || AppCoordinator.isBatchAccountSessionBusy()) {
            Toast.makeText(this, "任务进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val user = selectedUsername()
        if (user.isBlank()) {
            Toast.makeText(this, "请先选择账号", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureReadyForStart()) return
        val added = AppCoordinator.manualEnqueuePendingActivate(user)
        refreshQueueSummary()
        renderAccountStats()
        binding.pendingTaskStatusText.text = if (added) "已加入待活跃：@$user" else "@$user 已在队列中"
        Toast.makeText(this, if (added) "已加入 @$user" else "已在队列中", Toast.LENGTH_SHORT).show()
        updateButtonsEnabled()
    }

    private fun clearQueue() {
        if (AppCoordinator.isBatchAccountSessionBusy()) {
            Toast.makeText(this, "多账号会话进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        AppCoordinator.manualClearPendingActivateQueue()
        refreshQueueSummary()
        renderAccountStats()
        binding.pendingTaskStatusText.text = "已清空待活跃队列"
    }

    private fun activateNext() {
        if (AppCoordinator.isManualSwitchBusy()) {
            Toast.makeText(this, "切号进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (AppCoordinator.isBatchAccountSessionBusy()) {
            Toast.makeText(this, "多账号会话已在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        if (PrefsStore.getPendingActivateUsernames().isEmpty()) {
            Toast.makeText(this, "待活跃队列为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureReadyForStart()) return
        val minutes = readDurationMinutes()
        val next = PrefsStore.getPendingActivateUsernames().firstOrNull().orEmpty()
        binding.pendingTaskStatusText.text =
            "已请求活跃下个账号（@$next · ${minutes}分钟 · For You/涨粉随机）"
        AppCoordinator.manualActivateNextAccount(minutes)
        refreshQueueSummary()
        updateButtonsEnabled()
    }

    private fun startAutoActive() {
        if (AppCoordinator.isManualSwitchBusy()) {
            Toast.makeText(this, "切号进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (AppCoordinator.isBatchAccountSessionBusy()) {
            Toast.makeText(this, "多账号会话已在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureReadyForStart()) return
        val minutes = readDurationMinutes()
        val queued = PrefsStore.getPendingActivateUsernames()
        if (queued.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("自动活跃")
                .setMessage(
                    "待活跃队列已有 ${queued.size} 个账号。\n" +
                        "每号 ${minutes} 分钟，随机 For You/涨粉。",
                )
                .setPositiveButton("用当前队列开始") { _, _ ->
                    binding.pendingTaskStatusText.text =
                        "已请求自动活跃（${queued.size}个 · 每号${minutes}分钟）"
                    AppCoordinator.manualStartAutoActive(null, minutes)
                    refreshQueueSummary()
                    updateButtonsEnabled()
                }
                .setNeutralButton("重新勾选账号") { _, _ ->
                    showPickAccountsDialog(
                        title = "选择要自动活跃的账号",
                        preselected = queued.toSet(),
                    ) { picked ->
                        if (picked.isEmpty()) {
                            Toast.makeText(this, "请至少勾选一个账号", Toast.LENGTH_SHORT).show()
                            return@showPickAccountsDialog
                        }
                        binding.pendingTaskStatusText.text =
                            "已请求自动活跃（${picked.size}个 · 每号${minutes}分钟）"
                        AppCoordinator.manualStartAutoActive(picked, minutes)
                        refreshQueueSummary()
                        updateButtonsEnabled()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            showPickAccountsDialog(title = "选择要自动活跃的账号") { picked ->
                if (picked.isEmpty()) {
                    Toast.makeText(this, "请至少勾选一个账号", Toast.LENGTH_SHORT).show()
                    return@showPickAccountsDialog
                }
                binding.pendingTaskStatusText.text =
                    "已请求自动活跃（${picked.size}个 · 每号${minutes}分钟）"
                AppCoordinator.manualStartAutoActive(picked, minutes)
                refreshQueueSummary()
                updateButtonsEnabled()
            }
        }
    }

    private fun sortByMinutes() {
        val sorted = AppCoordinator.manualSortPendingActivateByTotalMinutesAsc()
        refreshQueueSummary()
        binding.pendingTaskStatusText.text =
            if (sorted.isEmpty()) "待活跃队列为空" else "已按累计活跃时长升序重排（少活跃优先）"
    }

    private fun trimByMinutes() {
        val input = android.widget.EditText(this).apply {
            hint = "累计活跃分钟阈值 N"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(PrefsStore.getManualActivateDurationMin().toString())
        }
        AlertDialog.Builder(this)
            .setTitle("移除已活跃满 N 分")
            .setMessage("将从待活跃队列移除累计活跃时长 ≥ N 的账号")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                val threshold = input.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val removed = AppCoordinator.manualTrimPendingActivateByMinTotal(threshold)
                refreshQueueSummary()
                renderAccountStats()
                binding.pendingTaskStatusText.text =
                    "已移除累计≥${threshold}分钟的账号 $removed 个，剩余 ${PrefsStore.getPendingActivateUsernames().size}"
            }
            .show()
    }

    private fun showPickAccountsDialog(
        title: String,
        preselected: Set<String> = emptySet(),
        onPicked: (List<String>) -> Unit,
    ) {
        if (accounts.isEmpty()) {
            Toast.makeText(this, "请先刷新账号列表", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = accounts.mapNotNull { item ->
            val u = item.username?.trim()?.removePrefix("@").orEmpty()
            if (u.isEmpty()) null else {
                val total = PrefsStore.getActivateTotalMinutes(u)
                "@$u（累计活跃 ${total} 分）"
            }
        }.toTypedArray()
        val usernames = accounts.mapNotNull { it.username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() } }
        val checked = BooleanArray(usernames.size) { i ->
            preselected.any { it.equals(usernames[i], ignoreCase = true) }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                val picked = usernames.mapIndexedNotNull { i, u -> if (checked[i]) u else null }
                onPicked(picked)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
