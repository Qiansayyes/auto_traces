package com.autotraces.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivityControlModeBinding
import com.autotraces.app.videoded.VideoDedMainActivity
import kotlinx.coroutines.launch

/** 运行模式：本机手动 / 云端 client，与设置、操作、PC传输、日志同级入口。 */
class ControlModeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityControlModeBinding
    private var participationRoster: List<AppCoordinator.AutoSwitchParticipationAccount> = emptyList()
    private val participationChecked = linkedMapOf<String, Boolean>()
    private var participationBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.showSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
        binding.showActionButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(EXTRA_INITIAL_PANEL, "action"),
            )
            finish()
        }
        binding.showControlModeButton.setOnClickListener { /* current */ }
        binding.showVideoDedButton.setOnClickListener {
            startActivity(Intent(this, VideoDedMainActivity::class.java))
        }
        binding.showLogButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(EXTRA_INITIAL_PANEL, "log"),
            )
            finish()
        }

        binding.refreshParticipationButton.setOnClickListener {
            lifecycleScope.launch { refreshParticipationRoster(showToast = true) }
        }
        binding.switchToCloudModeButton.setOnClickListener {
            if (participationBusy) return@setOnClickListener
            if (!ManualDeviceControl.ENABLED) {
                Toast.makeText(this, "当前已是云端模式", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selected = participationChecked.filter { it.value }.keys.toList()
            val summary = if (selected.isEmpty()) {
                "未勾选任何账号：别名桶内全部账号将同步为「不参与自动切号」，并切换到云端模式。"
            } else {
                "将把以下 ${selected.size} 个账号同步为「参与自动切号」，其余同步为「不参与」，并切换到云端模式：\n" +
                    selected.joinToString("\n") { "@$it" }
            }
            AlertDialog.Builder(this)
                .setTitle("确认切换到云端模式")
                .setMessage(summary)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认切换") { _, _ ->
                    lifecycleScope.launch { performSwitchToCloudMode(selected) }
                }
                .show()
        }
        binding.switchToManualModeButton.setOnClickListener {
            if (participationBusy) return@setOnClickListener
            if (ManualDeviceControl.ENABLED) {
                Toast.makeText(this, "当前已是本机手动模式", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("确认切回本机手动")
                .setMessage("将关闭本设备云端自动切号，并恢复本机手动任务面板。账号参与/不参与标记会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认切回") { _, _ ->
                    lifecycleScope.launch { performSwitchToManualMode() }
                }
                .show()
        }

        applyControlModeUi()
        if (PrefsStore.isBound()) {
            lifecycleScope.launch { refreshParticipationRoster(showToast = false) }
        }
    }

    override fun onResume() {
        super.onResume()
        applyControlModeUi()
    }

    private fun applyControlModeUi() {
        val manual = ManualDeviceControl.ENABLED
        binding.controlModeStatusText.text = if (manual) {
            "当前：本机手动（忽略云端任务指令；本机按钮驱动）"
        } else {
            "当前：云端 client（接受云端自动切号 / check_start / 设备指令）"
        }
        binding.switchToCloudModeButton.visibility = if (manual) View.VISIBLE else View.GONE
        binding.switchToManualModeButton.visibility = if (manual) View.GONE else View.VISIBLE
        binding.refreshParticipationButton.isEnabled = !participationBusy
        binding.switchToCloudModeButton.isEnabled = !participationBusy
        binding.switchToManualModeButton.isEnabled = !participationBusy
        updateParticipationSummary()
    }

    private suspend fun refreshParticipationRoster(showToast: Boolean) {
        if (participationBusy) return
        participationBusy = true
        applyControlModeUi()
        binding.participationSummaryText.text = "正在拉取云端参与状态…"
        val result = AppCoordinator.fetchAutoSwitchParticipationRoster()
        participationBusy = false
        result.onSuccess { list ->
            participationRoster = list
            val previous = participationChecked.mapKeys { it.key.lowercase() }
            participationChecked.clear()
            list.forEach { acc ->
                val prev = previous[acc.username.lowercase()]
                participationChecked[acc.username] = when {
                    acc.looksBanned -> false
                    prev != null -> prev
                    else -> acc.participating
                }
            }
            renderParticipationAccountList()
            applyControlModeUi()
            if (showToast) {
                Toast.makeText(this, "已刷新 ${list.size} 个账号参与状态", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { err ->
            binding.participationSummaryText.text = "拉取失败：${err.message}"
            applyControlModeUi()
            if (showToast) {
                Toast.makeText(this, "拉取失败：${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderParticipationAccountList() {
        val container = binding.participationAccountList
        container.removeAllViews()
        if (participationRoster.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无账号。请先绑定设备并确认设备表已分配账号。"
                setTextColor(ContextCompat.getColor(this@ControlModeActivity, android.R.color.darker_gray))
                textSize = 12f
            }
            container.addView(empty)
            updateParticipationSummary()
            return
        }
        val inflater = layoutInflater
        for (acc in participationRoster) {
            val row = inflater.inflate(R.layout.item_auto_switch_participation, container, false)
            val check = row.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.participationCheck)
            val status = row.findViewById<TextView>(R.id.participationStatusText)
            val toggle = row.findViewById<com.google.android.material.button.MaterialButton>(R.id.participationToggleButton)

            val cloudLabel = when {
                acc.looksBanned -> "云端：已封禁"
                acc.autoSwitchExcluded -> "云端：不参与"
                else -> "云端：参与"
            }
            status.text = cloudLabel
            check.text = "@${acc.username}"
            check.isEnabled = !acc.looksBanned && !participationBusy
            check.setOnCheckedChangeListener(null)
            check.isChecked = participationChecked[acc.username] == true && !acc.looksBanned
            check.setOnCheckedChangeListener { _, checked ->
                if (acc.looksBanned) return@setOnCheckedChangeListener
                participationChecked[acc.username] = checked
                updateParticipationSummary()
            }

            when {
                acc.looksBanned -> {
                    toggle.isEnabled = false
                    toggle.text = "封禁不可参与"
                }
                acc.autoSwitchExcluded -> {
                    toggle.isEnabled = !participationBusy
                    toggle.text = "切换为参与"
                    toggle.setOnClickListener {
                        lifecycleScope.launch {
                            setSingleParticipation(acc.username, participating = true)
                        }
                    }
                }
                else -> {
                    toggle.isEnabled = !participationBusy
                    toggle.text = "切换为不参与"
                    toggle.setOnClickListener {
                        lifecycleScope.launch {
                            setSingleParticipation(acc.username, participating = false)
                        }
                    }
                }
            }
            container.addView(row)
        }
        updateParticipationSummary()
    }

    private fun updateParticipationSummary() {
        val total = participationRoster.count { !it.looksBanned }
        val selected = participationChecked.filter { it.value }.keys
            .count { name -> participationRoster.any { it.username.equals(name, true) && !it.looksBanned } }
        val cloudParticipate = participationRoster.count { it.participating }
        val cloudExclude = participationRoster.count { !it.looksBanned && it.autoSwitchExcluded }
        val banned = participationRoster.count { it.looksBanned }
        binding.participationSummaryText.text =
            "云端现状：参与 $cloudParticipate / 不参与 $cloudExclude / 封禁 $banned；" +
                "切换后勾选：$selected / $total"
    }

    private suspend fun setSingleParticipation(username: String, participating: Boolean) {
        if (participationBusy) return
        participationBusy = true
        applyControlModeUi()
        renderParticipationAccountList()
        val result = AppCoordinator.setAccountAutoSwitchParticipating(username, participating)
        participationBusy = false
        result.onSuccess {
            participationChecked[username] = participating
            Toast.makeText(
                this,
                if (participating) "已设为参与：@$username" else "已设为不参与：@$username",
                Toast.LENGTH_SHORT,
            ).show()
            refreshParticipationRoster(showToast = false)
        }.onFailure { err ->
            applyControlModeUi()
            renderParticipationAccountList()
            Toast.makeText(this, "修改失败：${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun performSwitchToCloudMode(selected: List<String>) {
        if (participationBusy) return
        participationBusy = true
        applyControlModeUi()
        binding.participationSummaryText.text = "正在同步参与名单并切换到云端模式…"
        val result = AppCoordinator.switchToCloudClientMode(selected)
        participationBusy = false
        result.onSuccess { tip ->
            Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
            applyControlModeUi()
            refreshParticipationRoster(showToast = false)
        }.onFailure { err ->
            binding.participationSummaryText.text = "切换失败：${err.message}"
            applyControlModeUi()
            Toast.makeText(this, "切换失败：${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun performSwitchToManualMode() {
        if (participationBusy) return
        participationBusy = true
        applyControlModeUi()
        binding.participationSummaryText.text = "正在关闭云端自动切号并切回本机手动…"
        val result = AppCoordinator.switchToManualControlMode()
        participationBusy = false
        result.onSuccess { tip ->
            Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
            applyControlModeUi()
            refreshParticipationRoster(showToast = false)
        }.onFailure { err ->
            binding.participationSummaryText.text = "切回失败：${err.message}"
            applyControlModeUi()
            Toast.makeText(this, "切回失败：${err.message}", Toast.LENGTH_LONG).show()
        }
    }
}
