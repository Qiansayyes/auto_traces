package com.autotraces.app.videoded

import android.util.Log

/**
 * Root 下自动开启 TCP adbd 并将 PC 的 RSA 公钥写入 [adb_keys]，实现免点「允许调试」的无线授权。
 */
object AdbWirelessSetup {
    private const val TAG = "AdbWirelessSetup"
    private const val ADB_KEYS = "/data/misc/adb/adb_keys"

    data class PrepareResult(
        val ok: Boolean,
        val su: Boolean,
        val tcpPort: Int,
        val tcpOpen: Boolean,
        val keyInstalled: Boolean,
        val keyAlreadyPresent: Boolean,
        val tcpEnabled: Boolean,
        val detail: String,
    )

    fun prepare(context: android.content.Context, publicKeyLine: String?, forceTcpPort: Int?): PrepareResult {
        val port = (forceTcpPort ?: ApiSettings.getAdbTcpipPort(context)).coerceIn(1024, 65535)
        val su = RootWifiAdbHelper.hasSu()
        var tcpEnabled = false
        var keyInstalled = false
        var keyAlready = false

        if (su) {
            // 端口已在监听时禁止 stop/start adbd，否则会打断 PC 已建立的无线连接/投屏。
            if (AdbRuntimeProbe.isAdbTcpPortOpen(context, port)) {
                Log.d(TAG, "adb tcp $port already open, skip stop/start adbd")
            } else {
                val err = RootWifiAdbHelper.enableAdbTcpip(port)
                tcpEnabled = err == null
                if (err != null) {
                    Log.w(TAG, "enableAdbTcpip failed: $err")
                }
            }
        }

        val key = publicKeyLine?.trim().orEmpty()
        if (su && key.isNotEmpty()) {
            when (val r = installPublicKey(key)) {
                is KeyInstallResult.AlreadyPresent -> {
                    keyAlready = true
                    keyInstalled = true
                }
                is KeyInstallResult.Installed -> keyInstalled = true
                is KeyInstallResult.Failed -> Log.w(TAG, "installPublicKey: ${r.reason}")
            }
        }

        val tcpOpen = AdbRuntimeProbe.isAdbTcpPortOpen(context, port)
        val ok = tcpOpen && (key.isEmpty() || keyInstalled || keyAlready || AdbRuntimeProbe.hasNonEmptyAdbKeysViaRoot())
        val detail = buildString {
            append(if (su) "su 可用" else "无 su")
            append(" · 端口 $port ")
            append(if (tcpOpen) "已监听" else "未监听")
            if (tcpEnabled) append(" · 已尝试开启 TCP adbd")
            when {
                key.isEmpty() -> append(" · 未提供 PC 公钥")
                keyAlready -> append(" · PC 公钥已存在")
                keyInstalled -> append(" · 已写入 PC 公钥")
                else -> append(" · PC 公钥未写入")
            }
        }

        return PrepareResult(ok, su, port, tcpOpen, keyInstalled, keyAlready, tcpEnabled, detail)
    }

    private sealed class KeyInstallResult {
        data object AlreadyPresent : KeyInstallResult()
        data object Installed : KeyInstallResult()
        data class Failed(val reason: String) : KeyInstallResult()
    }

    private fun installPublicKey(line: String): KeyInstallResult {
        val escaped = line.replace("'", "'\\''")
        val script =
            "id >/dev/null 2>&1 || exit 99; " +
                "mkdir -p /data/misc/adb 2>/dev/null; " +
                "touch $ADB_KEYS 2>/dev/null; " +
                "grep -qF '${escaped.take(48)}' $ADB_KEYS 2>/dev/null && exit 2; " +
                "echo '$escaped' >> $ADB_KEYS; " +
                "chmod 640 $ADB_KEYS 2>/dev/null; " +
                "chown system:shell $ADB_KEYS 2>/dev/null; " +
                "setprop ctl.restart adbd"
        return try {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            when (waitForProcess(p, 20_000)) {
                0 -> KeyInstallResult.Installed
                2 -> KeyInstallResult.AlreadyPresent
                99 -> KeyInstallResult.Failed("no_su")
                else -> KeyInstallResult.Failed(out.ifEmpty { "exit_${p.exitValue()}" })
            }
        } catch (e: Exception) {
            KeyInstallResult.Failed(e.message ?: "exec")
        }
    }

    private fun waitForProcess(p: Process, timeoutMs: Long): Int {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                return p.exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(40)
            }
        }
        runCatching { p.destroyForcibly() }
        return -1
    }
}
