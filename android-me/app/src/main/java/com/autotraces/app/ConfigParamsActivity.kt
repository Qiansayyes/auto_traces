package com.autotraces.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivityConfigParamsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 展示当前设备在云端解析后的运行配置参数（与管理台模板一致）。 */
class ConfigParamsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConfigParamsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigParamsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.refreshConfigButton.setOnClickListener { refreshFromCloud(manual = true) }

        lifecycleScope.launch {
            AppCoordinator.config.collectLatest { renderBody() }
        }
        lifecycleScope.launch {
            AppCoordinator.runtimeState.collectLatest { renderMeta() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFromCloud(manual = false)
    }

    private fun refreshFromCloud(manual: Boolean) {
        if (!PrefsStore.isBound()) {
            binding.metaText.text = "未绑定设备，请先在「操作」页绑定别名。"
            binding.configBodyText.text = ""
            if (manual) {
                Toast.makeText(this, "请先绑定设备", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (manual) {
            binding.metaText.text = "正在从云端拉取配置…"
        }
        lifecycleScope.launch {
            val ok = AppCoordinator.fetchConfig(logToUser = manual)
            renderMeta()
            renderBody()
            if (manual) {
                val msg = if (ok) "配置已更新" else "拉取失败，仍显示上次缓存"
                Toast.makeText(this@ConfigParamsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderMeta() {
        val state = AppCoordinator.runtimeState.value
        binding.metaText.text = buildString {
            appendLine("Alias: ${PrefsStore.getAlias() ?: "—"}")
            appendLine("Device ID: ${PrefsStore.getDeviceId() ?: "—"}")
            appendLine("配置状态: ${if (state.configLoaded) "已拉取" else "未拉取/使用缓存"}")
            if (state.effectiveTemplate.isNotBlank()) {
                appendLine("生效模板: ${state.effectiveTemplate}")
            }
            if (state.effectiveRunMode.isNotBlank()) {
                appendLine("生效模式: ${ConfigDisplay.prettyRunMode(state.effectiveRunMode)}")
            }
            append("最近: ${state.lastMessage}")
        }
    }

    private fun renderBody() {
        val cfg = AppCoordinator.config.value
        val state = AppCoordinator.runtimeState.value
        binding.configBodyText.text = ConfigDisplay.buildReport(
            config = cfg,
            templateName = state.effectiveTemplate,
            configLoaded = state.configLoaded,
            deviceAlias = PrefsStore.getAlias(),
            tiktokLogUsername = PrefsStore.getLogContextUsername(),
        )
        binding.configScroll.post {
            binding.configScroll.scrollTo(0, 0)
        }
    }
}
