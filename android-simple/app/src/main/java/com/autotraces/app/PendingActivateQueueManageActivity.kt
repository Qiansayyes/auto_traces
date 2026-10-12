package com.autotraces.app

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivityPendingActivateQueueManageBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 待活跃队列管理：按增长数据与本地活跃时长编辑顺序与成员。 */
class PendingActivateQueueManageActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPendingActivateQueueManageBinding
    private var accounts: List<DeviceAssignedAccountItem> = emptyList()
    private val rowOrder = mutableListOf<String>()
    private val rowChecked = linkedMapOf<String, Boolean>()
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPendingActivateQueueManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val queue = PrefsStore.getPendingActivateUsernames()
        rowOrder.clear()
        rowChecked.clear()

        binding.queueSortByHeartGrowthButton.setOnClickListener { sortRows { parseGrowth(it.heart_growth) } }
        binding.queueSortByFollowerGrowthButton.setOnClickListener { sortRows { parseGrowth(it.follower_growth) } }
        binding.queueSortByActiveMinutesButton.setOnClickListener {
            sortRows { PrefsStore.getActivateTotalMinutes(it.username.orEmpty()).toLong() }
        }
        binding.queueRefreshStatsButton.setOnClickListener {
            lifecycleScope.launch { loadAccounts(showToast = true) }
        }
        binding.queueSaveButton.setOnClickListener { saveQueue() }
        binding.queueCancelButton.setOnClickListener { finish() }

        lifecycleScope.launch {
            loadAccounts(showToast = false)
            initFromQueue(queue)
            renderRows()
        }
    }

    private suspend fun loadAccounts(showToast: Boolean) {
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        val alias = PrefsStore.getAlias()?.trim().orEmpty()
        if (deviceId.isBlank() || alias.isBlank()) {
            if (showToast) Toast.makeText(this, "设备未绑定", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val resp = ApiService.getDeviceAssignedAccountsRoster(deviceId, alias)
            val items = resp.items
            if (resp.code != 0 && !(items != null && items.isEmpty())) {
                error(resp.msg.orEmpty().ifBlank { "拉取账号列表失败" })
            }
            accounts = items.orEmpty().filter { it.username?.trim().orEmpty().isNotEmpty() }
        }.onSuccess {
            if (showToast) Toast.makeText(this, "已刷新 ${accounts.size} 个账号", Toast.LENGTH_SHORT).show()
            ensureAllAccountsInOrder()
            renderRows()
        }.onFailure { err ->
            if (showToast) Toast.makeText(this, "刷新失败：${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initFromQueue(queue: List<String>) {
        val allUsers = accounts.mapNotNull {
            it.username?.trim()?.removePrefix("@")?.takeIf { u -> u.isNotEmpty() }
        }
        rowOrder.clear()
        rowOrder.addAll(queue.filter { q ->
            allUsers.any { it.equals(q, ignoreCase = true) }
        })
        for (u in allUsers) {
            if (rowOrder.none { it.equals(u, ignoreCase = true) }) {
                rowOrder.add(u)
            }
        }
        rowChecked.clear()
        for (u in rowOrder) {
            rowChecked[u] = queue.any { it.equals(u, ignoreCase = true) }
        }
    }

    private fun ensureAllAccountsInOrder() {
        val allUsers = accounts.mapNotNull {
            it.username?.trim()?.removePrefix("@")?.takeIf { u -> u.isNotEmpty() }
        }
        for (u in allUsers) {
            if (rowOrder.none { it.equals(u, ignoreCase = true) }) {
                rowOrder.add(u)
                rowChecked[u] = false
            }
        }
        rowOrder.removeAll { u ->
            allUsers.none { it.equals(u, ignoreCase = true) }
        }
    }

    private fun accountFor(username: String): DeviceAssignedAccountItem? =
        accounts.firstOrNull { it.username?.equals(username, ignoreCase = true) == true }

    private fun parseGrowth(raw: String?): Long =
        raw?.trim()?.replace("+", "")?.toLongOrNull() ?: 0L

    private fun sortRows(keySelector: (DeviceAssignedAccountItem) -> Long) {
        val checkedFirst = rowOrder.filter { rowChecked[it] == true }
        val unchecked = rowOrder.filter { rowChecked[it] != true }
        fun sorted(list: List<String>): List<String> =
            list.sortedBy { u ->
                val item = accountFor(u)
                if (item == null) Long.MAX_VALUE else keySelector(item)
            }
        rowOrder.clear()
        rowOrder.addAll(sorted(checkedFirst) + sorted(unchecked))
        renderRows()
    }

    private fun moveRow(username: String, delta: Int) {
        val idx = rowOrder.indexOfFirst { it.equals(username, ignoreCase = true) }
        if (idx < 0) return
        val newIdx = (idx + delta).coerceIn(0, rowOrder.lastIndex)
        if (newIdx == idx) return
        val u = rowOrder.removeAt(idx)
        rowOrder.add(newIdx, u)
        renderRows()
    }

    private fun renderRows() {
        val container = binding.queueManageList
        container.removeAllViews()
        if (rowOrder.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无账号，请先绑定并刷新云端"
                setTextColor(ContextCompat.getColor(this@PendingActivateQueueManageActivity, android.R.color.darker_gray))
                textSize = 12f
            }
            container.addView(empty)
            return
        }
        val inflater = layoutInflater
        for (username in rowOrder) {
            val item = accountFor(username)
            val row = inflater.inflate(R.layout.item_pending_activate_queue_row, container, false)
            val check = row.findViewById<MaterialCheckBox>(R.id.queueRowCheck)
            val detail = row.findViewById<TextView>(R.id.queueRowDetailText)
            val up = row.findViewById<MaterialButton>(R.id.queueRowUpButton)
            val down = row.findViewById<MaterialButton>(R.id.queueRowDownButton)

            check.text = "@$username"
            check.setOnCheckedChangeListener(null)
            check.isChecked = rowChecked[username] == true
            check.setOnCheckedChangeListener { _, checked ->
                rowChecked[username] = checked
            }

            val enqueueAt = PrefsStore.getPendingActivateEnqueueAt(username)
            val enqueueLabel = enqueueAt?.let { dateFmt.format(Date(it)) } ?: "—"
            val total = PrefsStore.getActivateTotalMinutes(username)
            val growth = PrefsStore.getActivateGrowthMinutes(username)
            val forYou = PrefsStore.getActivateForYouMinutes(username)
            val fg = item?.follower_growth.orEmpty().ifBlank { "0" }
            val hg = item?.heart_growth.orEmpty().ifBlank { "0" }
            detail.text =
                "粉丝+${fg} · 总赞+${hg} · 上次入队 $enqueueLabel · 活跃 ${total}分（涨粉${growth}/ForYou${forYou}）"

            up.setOnClickListener { moveRow(username, -1) }
            down.setOnClickListener { moveRow(username, 1) }
            container.addView(row)
        }
    }

    private fun saveQueue() {
        if (!ManualDeviceControl.ENABLED) {
            Toast.makeText(this, "请在本机手动模式下保存队列", Toast.LENGTH_SHORT).show()
            return
        }
        val saved = rowOrder.filter { rowChecked[it] == true }
        AppCoordinator.manualReplacePendingActivateQueue(saved)
        Toast.makeText(this, "已保存队列（${saved.size} 个账号）", Toast.LENGTH_SHORT).show()
        finish()
    }
}
