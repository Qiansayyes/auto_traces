package com.autotraces.app.videoded

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * LAN IPv4 用系统 API 即可；开启 TCP ADB 监听需要 su（各 ROM / SELinux 表现不一，仅尽力而为）。
 */
object RootWifiAdbHelper {

    fun hasSu(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val code = waitForProcess(p, 8_000)
        code == 0
    } catch (_: Exception) {
        false
    }

    fun isWifiNetwork(context: Context, network: Network?): Boolean {
        if (network == null) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun getLanIpv4ForNetwork(context: Context, network: Network): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        for (la in lp.linkAddresses) {
            val ia = la.address ?: continue
            if (ia is Inet4Address && !ia.isLoopbackAddress && !ia.isLinkLocalAddress) {
                return ia.hostAddress
            }
        }
        return null
    }

    /** 当前默认网络上的 IPv4（若默认网络是 VPN 等虚拟网，会得到 10.x 等，与 WLAN 详情不一致）。 */
    fun getLanIPv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val n = cm.activeNetwork ?: return null
        return getLanIpv4ForNetwork(context, n)
    }

    /**
     * 仅用于「PC 投屏 / adb connect」展示与复制：**只**从「带 Wi‑Fi 且非 VPN」的链路上读 IPv4。
     * 取不到则返回 **null**（不回落到 [getLanIPv4]，以免默认网络为 VPN 时误显示 10.x 等虚拟地址）。
     */
    fun getWifiLanIpv4OrNull(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            getLanIpv4ForNetwork(context, network)?.let { return it }
        }
        return null
    }

    /**
     * @return null 表示子进程正常退出；非 null 为简短错误码（便于通知展示）
     */
    fun enableAdbTcpip(port: Int): String? {
        val script =
            "id >/dev/null 2>&1 || exit 99; " +
                "setprop service.adb.tcp.port $port; setprop persist.adb.tcp.port $port; " +
                "stop adbd; sleep 1; start adbd"
        return try {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            Thread {
                try {
                    p.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                }
            }.start()
            when (val code = waitForProcess(p, 25_000)) {
                0 -> null
                99 -> "no_su"
                -1 -> "timeout"
                else -> "exit_$code"
            }
        } catch (e: Exception) {
            e.message ?: "exec"
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
