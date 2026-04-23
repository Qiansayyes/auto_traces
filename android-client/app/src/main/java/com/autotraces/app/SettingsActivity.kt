package com.autotraces.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverUrlInput.setText(PrefsStore.getServerBaseUrl() ?: ApiService.getBaseUrl())
        binding.pullCountInput.setText(PrefsStore.getPullCount().toString())
        binding.cloudUsernameInput.setText(PrefsStore.getAuthUsername().orEmpty())
        renderDeviceInfo()

        binding.showSettingsButton.setOnClickListener {
            // 当前页即设置页，保持不跳转
        }
        binding.showActionButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra("initial_panel", "action")
            )
            finish()
        }
        binding.showLogButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra("initial_panel", "log")
            )
            finish()
        }

        binding.cloudLoginButton.setOnClickListener {
            val u = binding.cloudUsernameInput.text?.toString()?.trim().orEmpty()
            val p = binding.cloudPasswordInput.text?.toString().orEmpty()
            if (u.isEmpty() || p.isEmpty()) {
                binding.statusText.text = "请输入用户名和密码"
                return@setOnClickListener
            }
            binding.statusText.text = "正在登录..."
            lifecycleScope.launch {
                runCatching { ApiService.login(u, p) }
                    .onSuccess { resp ->
                        if (resp.code == 0 && !resp.token.isNullOrBlank()) {
                            PrefsStore.saveAuthToken(resp.token!!, resp.username ?: u)
                            binding.statusText.text = "登录成功"
                        } else {
                            binding.statusText.text = resp.msg
                        }
                        renderDeviceInfo()
                    }
                    .onFailure {
                        binding.statusText.text = "登录失败：${it.message}"
                    }
            }
        }

        binding.cloudLogoutButton.setOnClickListener {
            PrefsStore.clearAuth()
            binding.cloudPasswordInput.text?.clear()
            binding.statusText.text = "已退出云端账号"
            renderDeviceInfo()
        }

        binding.saveServerButton.setOnClickListener {
            val url = binding.serverUrlInput.text?.toString()?.trim().orEmpty().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                binding.statusText.text = "服务器地址必须以 http:// 或 https:// 开头"
                return@setOnClickListener
            }
            PrefsStore.saveServerBaseUrl(url)
            AppCoordinator.refreshRuntimeSnapshot()
            binding.statusText.text = "服务器地址已保存"
            LogCenter.op(PrefsStore.getAlias(), "已保存服务器地址")
        }

        binding.savePullCountButton.setOnClickListener {
            val text = binding.pullCountInput.text?.toString()?.trim().orEmpty()
            val count = text.toIntOrNull()
            if (count == null || count !in 1..10) {
                binding.statusText.text = "拉取数量必须是 1-10 之间的整数"
                return@setOnClickListener
            }
            PrefsStore.savePullCount(count)
            binding.statusText.text = "拉取数量已保存"
            LogCenter.op(PrefsStore.getAlias(), "已保存单次拉取数量：$count")
        }

        binding.clearBindingButton.setOnClickListener {
            WorkerService.stop(this)
            PrefsStore.clearBinding()
            AppCoordinator.refreshRuntimeSnapshot()
            LogCenter.op(null, "已清除设备绑定")
            binding.statusText.text = "绑定信息已清除，返回首页后会重新进入绑定页"
            renderDeviceInfo()
        }
    }

    private fun renderDeviceInfo() {
        binding.deviceInfoText.text = buildString {
            appendLine("Alias: ${PrefsStore.getAlias() ?: "-"}")
            appendLine("Device ID: ${PrefsStore.getDeviceId() ?: "-"}")
            appendLine("Local Status: ${PrefsStore.getLocalStatus()}")
            val au = PrefsStore.getAuthUsername()
            if (!au.isNullOrBlank()) {
                append("云端账号: $au（已登录）")
            } else {
                append("云端账号: 未登录")
            }
        }
    }
}
