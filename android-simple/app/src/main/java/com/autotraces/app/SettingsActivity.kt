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
        binding.cloudUsernameInput.setText(PrefsStore.getAuthUsername().orEmpty())
        binding.cloudPasswordInput.setText(PrefsStore.getAuthPassword().orEmpty())
        renderDeviceInfo()
        binding.versionText.text = AppInfo.versionLabel()

        TopNavHelper.wire(this, TopNavHelper.Tab.SETTINGS)
        binding.showSettingsButton.setOnClickListener { /* current */ }

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

        renderPageDisorderRecoveryScheme()
        binding.pageDisorderRecoveryGroup.setOnCheckedChangeListener { _, checkedId ->
            val scheme = when (checkedId) {
                binding.pageDisorderBackHomeRadio.id -> PrefsStore.PAGE_DISORDER_RECOVERY_BACK_HOME
                else -> PrefsStore.PAGE_DISORDER_RECOVERY_FORCE_RESTART
            }
            if (scheme == PrefsStore.getPageDisorderRecoveryScheme()) return@setOnCheckedChangeListener
            PrefsStore.setPageDisorderRecoveryScheme(scheme)
            val label = if (scheme == PrefsStore.PAGE_DISORDER_RECOVERY_BACK_HOME) {
                "优先导航返回回首页"
            } else {
                "直接强关重启"
            }
            binding.statusText.text = "页面错乱恢复方案已切换：$label"
            LogCenter.op(PrefsStore.getAlias(), "页面错乱恢复方案：$label")
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

    override fun onResume() {
        super.onResume()
        binding.cloudUsernameInput.setText(PrefsStore.getAuthUsername().orEmpty())
        binding.cloudPasswordInput.setText(PrefsStore.getAuthPassword().orEmpty())
        renderDeviceInfo()
        renderPageDisorderRecoveryScheme()
    }

    private fun renderDeviceInfo() {
        binding.deviceInfoText.text = buildString {
            appendLine("版本: ${AppInfo.versionLabel()}")
            appendLine("Alias: ${PrefsStore.getAlias() ?: "-"}")
            appendLine("Device ID: ${PrefsStore.getDeviceId() ?: "-"}")
            appendLine("Local Status: ${PrefsStore.getLocalStatus()}")
            val au = PrefsStore.getAuthUsername()
            val loggedIn = !PrefsStore.getAuthToken().isNullOrBlank()
            when {
                loggedIn && !au.isNullOrBlank() -> append("云端账号: $au（已登录）")
                !au.isNullOrBlank() -> append("云端账号: $au（已填写，请点登录云端）")
                else -> append("云端账号: 未登录")
            }
        }
    }

    private fun renderPageDisorderRecoveryScheme() {
        val force = PrefsStore.isPageDisorderRecoverForceRestart()
        binding.pageDisorderRecoveryGroup.check(
            if (force) binding.pageDisorderForceRestartRadio.id
            else binding.pageDisorderBackHomeRadio.id,
        )
    }
}
