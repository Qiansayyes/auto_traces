package com.autotraces.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivityPendingActivateBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 待活跃队列：展示全部账号，按行加入/退出队列并查看活跃状态。 */
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
            PrefsStore.formatManualActivateDurationHours(PrefsStore.getManualActivateDurationHours()),
        )
        binding.pendingRecommendHeartGrowthMaxInput.setText(
            PrefsStore.getPendingActivateHeartGrowthMax().toString(),
        )
        setupActivateDurationPersistence()

        binding.pendingRefreshAccountsButton.setOnClickListener {
            lifecycleScope.launch { refreshAccounts(showToast = true) }
        }
        binding.pendingEnqueueRecommendedButton.setOnClickListener { enqueueRecommended() }
        binding.pendingRecommendHeartGrowthMaxInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                persistRecommendThresholdFromInput()
                renderAccountList()
            }
        }
        binding.pendingRemoveAllButton.setOnClickListener { clearQueue() }
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
                renderAccountList()
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
        renderAccountList()
        updateButtonsEnabled()
    }

    override fun onPause() {
        persistActivateDurationFromInput()
        persistRecommendThresholdFromInput()
        super.onPause()
    }

    /** 失焦 / 离开页面时写入 prefs，避免只改数字未点「活跃下个账号」就丢失。 */
    private fun setupActivateDurationPersistence() {
        binding.pendingActivateDurationMinInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistActivateDurationFromInput()
        }
    }

    private fun persistActivateDurationFromInput(): Int {
        val raw = binding.pendingActivateDurationMinInput.text?.toString()?.trim().orEmpty()
        val typed = raw.replace(',', '.').toDoubleOrNull()
        val hours = PrefsStore.parseManualActivateDurationHours(raw.ifBlank { null })
        PrefsStore.setManualActivateDurationHours(hours)
        val text = PrefsStore.formatManualActivateDurationHours(hours)
        if (binding.pendingActivateDurationMinInput.text?.toString() != text) {
            binding.pendingActivateDurationMinInput.setText(text)
            if (typed != null && typed > PrefsStore.MAX_MANUAL_ACTIVATE_DURATION_HOURS) {
                Toast.makeText(
                    this,
                    "已按分钟换算为 ${text} 小时（请填小时，例如 8）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        return PrefsStore.getManualActivateDurationMin()
    }

    private fun persistRecommendThresholdFromInput(): Int {
        val raw = binding.pendingRecommendHeartGrowthMaxInput.text?.toString()?.trim().orEmpty()
        val value = raw.toIntOrNull()?.coerceIn(
            PrefsStore.MIN_PENDING_ACTIVATE_HEART_GROWTH_MAX,
            PrefsStore.MAX_PENDING_ACTIVATE_HEART_GROWTH_MAX,
        ) ?: PrefsStore.getPendingActivateHeartGrowthMax()
        PrefsStore.setPendingActivateHeartGrowthMax(value)
        val text = value.toString()
        if (binding.pendingRecommendHeartGrowthMaxInput.text?.toString() != text) {
            binding.pendingRecommendHeartGrowthMaxInput.setText(text)
        }
        return value
    }

    private fun enqueueRecommended() {
        if (AppCoordinator.isManualSwitchBusy() || AppCoordinator.isBatchAccountSessionBusy()) {
            Toast.makeText(this, "任务进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureReadyForStart()) return
        persistRecommendThresholdFromInput()
        val added = AppCoordinator.manualEnqueueRecommendedPendingActivate()
        refreshQueueSummary()
        renderAccountList()
        val recTotal = PendingActivateRecommend.recommendedCount()
        binding.pendingTaskStatusText.text = when {
            added > 0 -> "已将 $added 个推荐账号加入待活跃队列"
            recTotal == 0 ->
                "当前没有推荐账号（三天总赞增长均大于 ${PrefsStore.getPendingActivateHeartGrowthMax()}）"
            else -> "推荐账号都已在队列中（共 $recTotal 个）"
        }
        Toast.makeText(this, binding.pendingTaskStatusText.text, Toast.LENGTH_SHORT).show()
        updateButtonsEnabled()
    }

    private fun formatSessionDurationLabel(minutes: Int): String {
        val hours = minutes / 60.0
        return "${PrefsStore.formatManualActivateDurationHours(hours)}小时"
    }

    private fun disableManualControls() {
        listOf(
            binding.pendingRefreshAccountsButton,
            binding.pendingRemoveAllButton,
            binding.pendingActivateNextButton,
            binding.pendingAutoActiveButton,
            binding.pendingSortByMinutesButton,
            binding.pendingTrimByMinutesButton,
            binding.pendingManageQueueButton,
            binding.pendingEnqueueRecommendedButton,
        ).forEach { it.isEnabled = false }
        binding.pendingActivateDurationMinInput.isEnabled = false
        binding.pendingRecommendHeartGrowthMaxInput.isEnabled = false
    }

    private suspend fun refreshAccounts(showToast: Boolean) {
        binding.pendingActivateStatusText.text = "正在拉取云端账号…"
        val result = withContext(Dispatchers.IO) {
            runCatching { fetchAccountsWithStats() }
        }
        result.onSuccess { list ->
            accounts = list
            PendingActivateRecommend.ingestRoster(list)
            renderAccountList()
            val inQueue = PrefsStore.getPendingActivateUsernames().size
            val rec = PendingActivateRecommend.recommendedCount()
            binding.pendingActivateStatusText.text =
                "已加载 ${accounts.size} 个账号（队列中 $inQueue 个，推荐 $rec 个，${PendingActivateRecommend.cacheAgeLabel()}）"
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

    /** 拉取本别名桶全部账号（含不参与自动切号；封禁账号仅展示不可入队）。 */
    private suspend fun fetchAccountsWithStats(): List<DeviceAssignedAccountItem> {
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) return emptyList()
        val resp = ApiService.getDeviceAssignedAccountsRoster(deviceId, alias)
        if (resp.code != 0) {
            error(resp.msg?.trim().orEmpty().ifBlank { "拉取账号列表失败" })
        }
        return resp.items.orEmpty()
            .filter { it.username?.trim().orEmpty().isNotEmpty() }
    }

    private fun itemLooksBanned(item: DeviceAssignedAccountItem): Boolean {
        if (item.looks_banned == true) return true
        return ManualDeviceControl.isBannedMarker(item.password) ||
            ManualDeviceControl.isBannedMarker(item.email) ||
            ManualDeviceControl.isBannedMarker(item.email_password)
    }

    private fun sortedAccountsForDisplay(): List<DeviceAssignedAccountItem> {
        val queue = PrefsStore.getPendingActivateUsernames()
        val queueIndex = queue.mapIndexed { index, u ->
            u.trim().removePrefix("@").lowercase(Locale.US) to index
        }.toMap()
        return accounts.sortedWith(
            compareBy<DeviceAssignedAccountItem>(
                { item ->
                    val key = item.username?.trim()?.removePrefix("@").orEmpty()
                        .lowercase(Locale.US)
                    queueIndex[key] ?: Int.MAX_VALUE
                },
                { item -> item.username?.trim()?.lowercase(Locale.US).orEmpty() },
            ),
        )
    }

    private fun queuePosition(username: String): Int? {
        val key = username.trim().removePrefix("@").lowercase(Locale.US)
        val queue = PrefsStore.getPendingActivateUsernames()
        val idx = queue.indexOfFirst { it.equals(key, ignoreCase = true) }
        return if (idx >= 0) idx + 1 else null
    }

    private fun renderAccountList() {
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
        val blocked = AppCoordinator.isManualSwitchBusy() || AppCoordinator.isBatchAccountSessionBusy()
        val inflater = layoutInflater
        for (item in sortedAccountsForDisplay()) {
            val username = item.username?.trim()?.removePrefix("@").orEmpty()
            if (username.isEmpty()) continue
            val row = inflater.inflate(R.layout.item_pending_activate_account_row, container, false)
            val usernameText = row.findViewById<TextView>(R.id.accountRowUsernameText)
            val statusText = row.findViewById<TextView>(R.id.accountRowQueueStatusText)
            val toggleBtn = row.findViewById<MaterialButton>(R.id.accountRowQueueToggleButton)
            val growthText = row.findViewById<TextView>(R.id.accountRowGrowthText)
            val localText = row.findViewById<TextView>(R.id.accountRowLocalText)

            val banned = itemLooksBanned(item)
            val excluded = item.auto_switch_excluded == true
            usernameText.text = buildString {
                append("@$username")
                if (excluded) append(" · 不切号")
                if (banned) append(" · 已封禁")
            }

            val pos = queuePosition(username)
            val recommended = !banned && PendingActivateRecommend.isRecommended(username)
            if (pos != null) {
                statusText.text = "队列 #$pos"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.pending_activate_in_queue))
                toggleBtn.text = "退出"
            } else if (recommended) {
                statusText.text = "推荐入队"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.pending_activate_recommend))
                toggleBtn.text = "加入"
            } else {
                statusText.text = "未入队"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                toggleBtn.text = "加入"
            }

            val snap = PendingActivateRecommend.snapshot(username)
            val growth3d = snap?.heartGrowth3d ?: PendingActivateRecommend.heartGrowth3d(item)
            growthText.text =
                "粉丝 ${item.follower_count.orEmpty().ifBlank { "—" }}（+${item.follower_growth.orEmpty().ifBlank { "0" }}）" +
                    " · 总赞 ${item.heart_count.orEmpty().ifBlank { "—" }}" +
                    "（三天+$growth3d 前${snap?.dayBefore ?: PendingActivateRecommend.parseGrowth(item.heart_growth_day_before)}" +
                    "/昨${snap?.yesterday ?: PendingActivateRecommend.parseGrowth(item.heart_growth_yesterday)}" +
                    "/今${snap?.today ?: PendingActivateRecommend.parseGrowth(item.heart_growth_today)}）"

            val enqueueAt = PrefsStore.getPendingActivateEnqueueAt(username)
            val enqueueLabel = enqueueAt?.let { dateFmt.format(Date(it)) } ?: "—"
            val total = PrefsStore.getActivateTotalMinutes(username)
            val growth = PrefsStore.getActivateGrowthMinutes(username)
            val forYou = PrefsStore.getActivateForYouMinutes(username)
            localText.text =
                "上次入队 $enqueueLabel · 累计活跃 ${total}分（涨粉${growth} / ForYou${forYou}）"

            val canToggle = ManualDeviceControl.ENABLED && !blocked && !banned
            toggleBtn.isEnabled = canToggle
            toggleBtn.setOnClickListener {
                if (pos != null) {
                    removeFromQueue(username)
                } else {
                    addToQueue(username)
                }
            }
            container.addView(row)
        }
    }

    private fun addToQueue(username: String) {
        if (AppCoordinator.isManualSwitchBusy() || AppCoordinator.isBatchAccountSessionBusy()) {
            Toast.makeText(this, "任务进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureReadyForStart()) return
        val added = AppCoordinator.manualEnqueuePendingActivate(username)
        refreshQueueSummary()
        renderAccountList()
        binding.pendingTaskStatusText.text =
            if (added) "已加入待活跃：@$username" else "@$username 已在队列中"
        Toast.makeText(this, if (added) "已加入 @$username" else "已在队列中", Toast.LENGTH_SHORT).show()
        updateButtonsEnabled()
    }

    private fun removeFromQueue(username: String) {
        if (AppCoordinator.isBatchAccountSessionBusy()) {
            Toast.makeText(this, "多账号会话进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val removed = AppCoordinator.manualRemovePendingActivate(username)
        refreshQueueSummary()
        renderAccountList()
        binding.pendingTaskStatusText.text =
            if (removed) "已移出待活跃：@$username" else "@$username 不在队列中"
        Toast.makeText(
            this,
            if (removed) "已移出 @$username" else "不在队列中",
            Toast.LENGTH_SHORT,
        ).show()
        updateButtonsEnabled()
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
        binding.pendingRemoveAllButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingActivateNextButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingAutoActiveButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingSortByMinutesButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingTrimByMinutesButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingManageQueueButton.isEnabled = ManualDeviceControl.ENABLED
        binding.pendingEnqueueRecommendedButton.isEnabled = !blocked && ManualDeviceControl.ENABLED
        binding.pendingActivateDurationMinInput.isEnabled = ManualDeviceControl.ENABLED
        binding.pendingRecommendHeartGrowthMaxInput.isEnabled = ManualDeviceControl.ENABLED
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

    private fun readDurationMinutes(): Int = persistActivateDurationFromInput()

    private fun clearQueue() {
        if (AppCoordinator.isBatchAccountSessionBusy()) {
            Toast.makeText(this, "多账号会话进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val count = PrefsStore.getPendingActivateUsernames().size
        if (count <= 0) {
            Toast.makeText(this, "待活跃队列已空", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("全部移出")
            .setMessage("将把队列中的 $count 个账号全部移出待活跃队列，是否继续？")
            .setPositiveButton("移出全部") { _, _ ->
                AppCoordinator.manualClearPendingActivateQueue()
                refreshQueueSummary()
                renderAccountList()
                binding.pendingTaskStatusText.text = "已将全部账号移出待活跃队列"
                updateButtonsEnabled()
            }
            .setNegativeButton("取消", null)
            .show()
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
            "已请求活跃下个账号（@$next · ${formatSessionDurationLabel(minutes)} · For You/涨粉随机）"
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
        val queued = PrefsStore.getPendingActivateUsernames()
        if (queued.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("自动活跃")
                .setMessage(
                    "待活跃队列已有 ${queued.size} 个账号。\n" +
                        "每号 ${formatSessionDurationLabel(readDurationMinutes())}，随机 For You/涨粉。\n" +
                        "连续活跃 30–58 分钟后关闭 TikTok 下机，切下一号；切后剩余不足 30 分钟则向上取整抽 30–58 分钟作为末段跑完。",
                )
                .setPositiveButton("用当前队列开始") { _, _ ->
                    val minutes = readDurationMinutes()
                    binding.pendingTaskStatusText.text =
                        "已请求自动活跃（${queued.size}个 · 每号${formatSessionDurationLabel(minutes)}）"
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
                        val minutes = readDurationMinutes()
                        binding.pendingTaskStatusText.text =
                            "已请求自动活跃（${picked.size}个 · 每号${formatSessionDurationLabel(minutes)}）"
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
                val minutes = readDurationMinutes()
                binding.pendingTaskStatusText.text =
                    "已请求自动活跃（${picked.size}个 · 每号${formatSessionDurationLabel(minutes)}）"
                AppCoordinator.manualStartAutoActive(picked, minutes)
                refreshQueueSummary()
                updateButtonsEnabled()
            }
        }
    }

    private fun sortByMinutes() {
        val sorted = AppCoordinator.manualSortPendingActivateByTotalMinutesAsc()
        refreshQueueSummary()
        renderAccountList()
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
                renderAccountList()
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
        val eligible = accounts.filterNot { itemLooksBanned(it) }
        val labels = eligible.mapNotNull { item ->
            val u = item.username?.trim()?.removePrefix("@").orEmpty()
            if (u.isEmpty()) null else {
                val total = PrefsStore.getActivateTotalMinutes(u)
                val inQueue = queuePosition(u) != null
                buildString {
                    append("@$u（累计活跃 ${total} 分")
                    if (inQueue) append(" · 已在队列")
                    append("）")
                }
            }
        }.toTypedArray()
        val usernames = eligible.mapNotNull {
            it.username?.trim()?.removePrefix("@")?.takeIf { u -> u.isNotEmpty() }
        }
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
