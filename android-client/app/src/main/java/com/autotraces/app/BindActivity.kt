package com.autotraces.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autotraces.app.databinding.ActivityBindBinding
import kotlinx.coroutines.launch

class BindActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBindBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBindBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bindButton.setOnClickListener {
            bindDevice()
        }
    }

    private fun bindDevice() {
        val alias = binding.aliasInput.text?.toString()?.trim().orEmpty()
        if (!Regex("^[A-Za-z0-9_]{1,20}$").matches(alias)) {
            binding.aliasLayout.error = "别名只允许 1-20 位字母、数字、下划线"
            return
        }

        binding.aliasLayout.error = null
        binding.statusText.text = "正在绑定..."

        lifecycleScope.launch {
            runCatching {
                ApiService.bindDevice(alias, DeviceFingerprint.fromContext(this@BindActivity))
            }.onSuccess { response ->
                if (response.code == 0 && !response.device_id.isNullOrBlank()) {
                    PrefsStore.saveBinding(response.device_id, alias)
                    PrefsStore.markBindingRefreshedNow()
                    LogCenter.op(alias, "绑定成功：${response.device_id}")
                    AppCoordinator.syncOnline()
                    WorkerService.start(this@BindActivity)
                    AppCoordinator.startIfNeeded()
                    startActivity(Intent(this@BindActivity, MainActivity::class.java))
                    finish()
                } else {
                    binding.statusText.text = response.msg
                }
            }.onFailure {
                binding.statusText.text = "绑定失败：${it.message}"
            }
        }
    }
}
