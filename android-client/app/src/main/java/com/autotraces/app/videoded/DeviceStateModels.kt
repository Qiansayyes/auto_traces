package com.autotraces.app.videoded

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object WifiUtils {
    fun getWifiIp(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null

            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.hostAddress != null }
                ?.hostAddress
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * 尽力探测本机 ADB / 无线调试状态（无法 100% 得知 PC 侧 `adb devices` 是否已 authorized，
 * 但可区分「开发者选项未开 / adbd 未监听 / 本机尚未写入 adb_keys」等常见情况）。
 */
object AdbRuntimeProbe {
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

    fun getProp(name: String): String? = try {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText().trim() }
        when (waitForProcess(p, 2_000)) {
            0 -> text.ifEmpty { null }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    /** 系统属性里 adbd 声明的 TCP 端口（Root setprop 或 adb tcpip 后常见）。 */
    fun readConfiguredTcpPort(): Int? {
        val candidates = listOf(
            "service.adb.tcp.port",
            "persist.adb.tcp.port",
            "service.adb.tls.port",
        )
        for (name in candidates) {
            val v = getProp(name)?.toIntOrNull()
            if (v != null && v in 1024..65535) return v
        }
        return null
    }

    /**
     * adbd 是否在 TCP 端口监听。部分 ROM 只绑 wlan0、不绑 127.0.0.1，故同时探测环回与本机 LAN IP。
     */
    fun isAdbTcpPortOpen(context: Context, port: Int, connectTimeoutMs: Int = 1_200): Boolean {
        val configured = readConfiguredTcpPort()
        val ports = linkedSetOf(port)
        if (configured != null) ports.add(configured)
        val hosts = linkedSetOf("127.0.0.1")
        WifiUtils.getWifiIp(context)?.let { hosts.add(it) }
        ApiSettings.getEffectiveMirrorLanIpv4(context)?.let { hosts.add(it) }
        for (p in ports) {
            for (host in hosts) {
                if (tryTcpConnect(host, p, connectTimeoutMs)) return true
            }
        }
        return false
    }

    private fun tryTcpConnect(host: String, port: Int, connectTimeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            true
        }
    } catch (_: Throwable) {
        false
    }

    @Deprecated("Use isAdbTcpPortOpen", ReplaceWith("isAdbTcpPortOpen(context, port)"))
    fun isLocalTcpPortOpen(port: Int, connectTimeoutMs: Int = 1_200): Boolean =
        tryTcpConnect("127.0.0.1", port, connectTimeoutMs)

    /** 至少已有一把 PC RSA 公钥写入本机（需 su）。 */
    fun hasNonEmptyAdbKeysViaRoot(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "test -s /data/misc/adb/adb_keys && echo OK")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText().trim() }
        waitForProcess(p, 8_000) == 0 && out == "OK"
    } catch (_: Throwable) {
        false
    }
}

object MirrorStateProbe {
    fun currentWifiIp(context: Context): String? = WifiUtils.getWifiIp(context)

    private fun isUsbDebuggingEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    } catch (_: Throwable) {
        false
    }

    private fun isWirelessDebuggingToggleOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    private fun developerPathLooksReady(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return isWirelessDebuggingToggleOn(context) || isUsbDebuggingEnabled(context)
        }
        return isUsbDebuggingEnabled(context)
    }

    fun currentMirrorStatus(context: Context): String {
        if (currentWifiIp(context) == null) return "offline"
        val port = ApiSettings.getAdbTcpipPort(context)
        val tcpOpen = AdbRuntimeProbe.isAdbTcpPortOpen(context, port)

        if (ApiSettings.isRootAdbTcpipEnabled(context)) {
            if (!RootWifiAdbHelper.hasSu()) return "not_authorized"
            if (!tcpOpen) return "first_time_pending"
            if (AdbRuntimeProbe.hasNonEmptyAdbKeysViaRoot()) return "ready"
            return "not_authorized"
        }

        if (!tcpOpen) return "first_time_pending"
        if (developerPathLooksReady(context)) return "ready"
        return "first_time_pending"
    }

    fun currentMirrorMessage(context: Context): String? {
        val wifiIp = currentWifiIp(context)
        if (wifiIp == null) return "未检测到 Wi‑Fi IP，设备离线或未连入局域网"

        val port = ApiSettings.getAdbTcpipPort(context)
        val tcpOpen = AdbRuntimeProbe.isAdbTcpPortOpen(context, port)
        val rootMode = ApiSettings.isRootAdbTcpipEnabled(context)

        return when (currentMirrorStatus(context)) {
            "ready" -> null
            "offline" -> "未检测到 Wi‑Fi IP，设备离线或未连入局域网"
            "first_time_pending" -> when {
                rootMode -> "无线 ADB 尚未在本机 $port 端口监听；请保持前台服务运行或从通知重试"
                !tcpOpen -> "本机 $port 端口无 ADB 监听；请在开发者选项中开启无线调试（或 USB 调试后执行无线配对），并与应用内端口一致"
                else -> "请在开发者选项中开启 USB 调试或无线调试，并与应用内 ADB 端口一致"
            }
            else -> when {
                rootMode && !RootWifiAdbHelper.hasSu() -> "已启用 Root 无线 ADB，但未取得 su，无法自动 setprop 重启 adbd"
                rootMode && tcpOpen && !AdbRuntimeProbe.hasNonEmptyAdbKeysViaRoot() ->
                    "设备已连网且 adbd 在监听，但尚未写入 ADB 授权密钥；请从 PC 执行 adb connect 后在手机上确认 RSA 指纹"
                else -> "设备已连网，但尚未完成 ADB 授权"
            }
        }
    }

    fun isAdbAuthorized(context: Context): Boolean = currentMirrorStatus(context) == "ready"
}
