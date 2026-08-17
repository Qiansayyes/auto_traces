package com.autotraces.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivitySettingsBinding
import com.autotraces.app.videoded.VideoDedMainActivity
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverUrlInput.setText(PrefsStore.getServerBaseUrl() ?: ApiService.getBaseUrl())
        binding.cloudUsernameInput.setText(PrefsStore.getAuthUsername().orEmpty())
        binding.cloudPasswordInput.setText(PrefsStore.getAuthPassword().orEmpty())
        renderDeviceInfo()
        binding.versionText.text = AppInfo.versionLabel()

        binding.showSettingsButton.setOnClickListener {
            // 当前页即设置页，保持不跳转
        }
        binding.showActionButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_INITIAL_PANEL, "action")
            )
            finish()
        }
        binding.showControlModeButton.setOnClickListener {
            startActivity(Intent(this, ControlModeActivity::class.java))
            finish()
        }
        binding.showVideoDedButton.setOnClickListener {
            startActivity(Intent(this, VideoDedMainActivity::class.java))
        }
        binding.showLogButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_INITIAL_PANEL, "log")
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
                            PrefsStore.saveAuthPassword(p)
                            AppCoordinator.ensureTikTokGalleryPermissionIfNeeded()
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

        binding.clearBindingButton.setOnClickListener {
            WorkerService.stop(this)
            PrefsStore.clearBinding()
            AppCoordinator.refreshRuntimeSnapshot()
            LogCenter.op(null, "已清除设备绑定")
            binding.statusText.text = "绑定信息已清除，返回首页后会重新进入绑定页"
            renderDeviceInfo()
        }

        binding.openConfigParamsButton.setOnClickListener {
            startActivity(Intent(this, ConfigParamsActivity::class.java))
        }
    }

    private fun renderDeviceInfo() {
        binding.deviceInfoText.text = buildString {
            appendLine("版本: ${AppInfo.versionLabel()}")
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
