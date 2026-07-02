package com.autotraces.app.videoded

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 查询设备运行时内存（RAM）：总量、可用量、各进程 PSS。
 * 供 VideoDed 本地 HTTP API / PC 轮询诊断 LMK 等问题。
 */
object RuntimeMemoryProbe {

    data class SystemMemory(
        val totalKb: Long,
        val availableKb: Long,
        val freeKb: Long?,
        val usedKb: Long,
        val usedPercent: Double,
        val lowMemory: Boolean,
        val thresholdKb: Long,
        val ramStatus: String? = null,
    )

    data class ProcessMemory(
        val pid: Int,
        val name: String,
        val pssKb: Int,
        val dalvikPssKb: Int?,
        val nativePssKb: Int?,
        val importance: Int?,
    )

    data class Snapshot(
        val timestampMs: Long,
        val source: String,
        val system: SystemMemory,
        val processes: List<ProcessMemory>,
        val selfPackage: String,
        val selfPid: Int,
        val selfPssKb: Int?,
    )

    fun capture(
        context: Context,
        processLimit: Int = 40,
        minPssKb: Int = 1_024,
    ): Snapshot {
        val appCtx = context.applicationContext
        val am = amMemoryBaseline(appCtx)
        val dumpsys = if (RootWifiAdbHelper.hasSu()) readDumpsysMeminfoViaSu() else null
        val procMem = readProcMeminfoKb()

        val totalKb = dumpsys?.totalKb
            ?: procMem?.memTotalKb?.takeIf { it > 0 }
            ?: am.totalKb
        val availableKb = dumpsys?.freeKb
            ?: procMem?.memAvailableKb?.takeIf { it > 0 }
            ?: am.availableKb
        val freeKb = procMem?.memFreeKb ?: dumpsys?.freeKb
        val usedKb = (totalKb - availableKb).coerceAtLeast(0L)
        val usedPercent =
            if (totalKb > 0L) usedKb.toDouble() * 100.0 / totalKb.toDouble() else 0.0

        val system = SystemMemory(
            totalKb = totalKb,
            availableKb = availableKb,
            freeKb = freeKb,
            usedKb = usedKb,
            usedPercent = usedPercent,
            lowMemory = am.lowMemory || dumpsys?.ramStatus?.contains("low", ignoreCase = true) == true,
            thresholdKb = am.thresholdKb,
            ramStatus = dumpsys?.ramStatus,
        )

        val processes = when {
            dumpsys != null && dumpsys.processes.isNotEmpty() ->
                trimProcesses(dumpsys.processes, processLimit, minPssKb)
            else ->
                captureProcessesViaActivityManager(appCtx, processLimit, minPssKb)
        }

        val source = when {
            dumpsys != null && dumpsys.processes.isNotEmpty() -> "dumpsys_meminfo_su"
            processes.isNotEmpty() -> "activity_manager"
            else -> "proc_meminfo_only"
        }

        val selfPid = Process.myPid()
        val selfPssKb = processes.firstOrNull { it.pid == selfPid }?.pssKb ?: readSelfPssKb()

        return Snapshot(
            timestampMs = System.currentTimeMillis(),
            source = source,
            system = system,
            processes = processes,
            selfPackage = appCtx.packageName,
            selfPid = selfPid,
            selfPssKb = selfPssKb,
        )
    }

    fun toJson(snapshot: Snapshot): JSONObject = JSONObject().apply {
        put("success", true)
        put("timestampMs", snapshot.timestampMs)
        put("source", snapshot.source)
        put(
            "system",
            JSONObject().apply {
                put("totalKb", snapshot.system.totalKb)
                put("availableKb", snapshot.system.availableKb)
                snapshot.system.freeKb?.let { put("freeKb", it) }
                put("usedKb", snapshot.system.usedKb)
                put("usedPercent", snapshot.system.usedPercent)
                put("lowMemory", snapshot.system.lowMemory)
                put("thresholdKb", snapshot.system.thresholdKb)
                snapshot.system.ramStatus?.let { put("ramStatus", it) }
                put("totalMb", snapshot.system.totalKb / 1024.0)
                put("availableMb", snapshot.system.availableKb / 1024.0)
                put("usedMb", snapshot.system.usedKb / 1024.0)
            },
        )
        put(
            "self",
            JSONObject().apply {
                put("package", snapshot.selfPackage)
                put("pid", snapshot.selfPid)
                snapshot.selfPssKb?.let { put("pssKb", it) }
            },
        )
        put(
            "processes",
            JSONArray().apply {
                snapshot.processes.forEach { row ->
                    put(
                        JSONObject().apply {
                            put("pid", row.pid)
                            put("name", row.name)
                            put("pssKb", row.pssKb)
                            put("pssMb", row.pssKb / 1024.0)
                            row.dalvikPssKb?.let { put("dalvikPssKb", it) }
                            row.nativePssKb?.let { put("nativePssKb", it) }
                            row.importance?.let { put("importance", it) }
                        },
                    )
                }
            },
        )
    }

    private data class AmMemoryBaseline(
        val totalKb: Long,
        val availableKb: Long,
        val lowMemory: Boolean,
        val thresholdKb: Long,
    )

    private data class DumpsysMeminfo(
        val totalKb: Long?,
        val freeKb: Long?,
        val ramStatus: String?,
        val processes: List<ProcessMemory>,
    )

    private fun amMemoryBaseline(context: Context): AmMemoryBaseline {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return AmMemoryBaseline(
            totalKb = memInfo.totalMem / 1024L,
            availableKb = memInfo.availMem / 1024L,
            lowMemory = memInfo.lowMemory,
            thresholdKb = memInfo.threshold / 1024L,
        )
    }

    private fun captureProcessesViaActivityManager(
        context: Context,
        processLimit: Int,
        minPssKb: Int,
    ): List<ProcessMemory> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.runningAppProcesses.orEmpty()
        if (running.isEmpty()) return emptyList()

        val pids = running.map { it.pid }.toIntArray()
        val memInfos = am.getProcessMemoryInfo(pids)
        val memByPid: Map<Int, Debug.MemoryInfo> = pids.withIndex().associate { (index, pid) ->
            pid to memInfos[index]
        }

        val processes = running.mapNotNull { meta ->
            val pid = meta.pid
            val debug = memByPid[pid] ?: return@mapNotNull null
            val pss = debug.totalPss
            if (pss < minPssKb) return@mapNotNull null
            ProcessMemory(
                pid = pid,
                name = meta.processName?.trim().orEmpty().ifBlank { "pid=$pid" },
                pssKb = pss,
                dalvikPssKb = debug.dalvikPss,
                nativePssKb = debug.nativePss,
                importance = meta.importance,
            )
        }
        return trimProcesses(processes, processLimit, minPssKb)
    }

    private fun trimProcesses(
        processes: List<ProcessMemory>,
        processLimit: Int,
        minPssKb: Int,
    ): List<ProcessMemory> {
        val cap = processLimit.coerceIn(1, 200)
        val sorted = processes
            .filter { it.pssKb >= minPssKb }
            .sortedByDescending { it.pssKb }
        val top = sorted.take(cap)
        val selfPid = Process.myPid()
        if (top.any { it.pid == selfPid }) return top
        val selfRow = sorted.firstOrNull { it.pid == selfPid } ?: return top
        return (top.dropLast(1) + selfRow).sortedByDescending { it.pssKb }
    }

    private fun readDumpsysMeminfoViaSu(): DumpsysMeminfo? {
        return runCatching {
            val p = ProcessBuilder("su", "-c", "dumpsys meminfo")
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            if (waitForProcess(p, 12_000) != 0 || text.isBlank()) return@runCatching null
            parseDumpsysMeminfo(text)
        }.getOrNull()
    }

    private fun waitForProcess(p: java.lang.Process, timeoutMs: Long): Int {
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

    private fun parseDumpsysMeminfo(text: String): DumpsysMeminfo {
        val lines = text.lineSequence().toList()
        var totalKb: Long? = null
        var freeKb: Long? = null
        var ramStatus: String? = null

        val totalRamRegex = Regex("""Total RAM:\s*([\d,]+)K(?:\s*\(status\s+(\w+)\))?""", RegexOption.IGNORE_CASE)
        val freeRamRegex = Regex("""Free RAM:\s*([\d,]+)K""", RegexOption.IGNORE_CASE)
        val processRegex = Regex("""^\s*([\d,]+)K:\s+(.+?)\s+\(pid\s+(\d+)""")

        for (line in lines) {
            totalRamRegex.find(line)?.let { m ->
                totalKb = m.groupValues[1].replace(",", "").toLongOrNull()
                ramStatus = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            }
            freeRamRegex.find(line)?.let { m ->
                freeKb = m.groupValues[1].replace(",", "").toLongOrNull()
            }
        }

        val processes = mutableListOf<ProcessMemory>()
        var inPssSection = false
        for (line in lines) {
            when {
                line.startsWith("Total PSS by process:") -> {
                    inPssSection = true
                    continue
                }
                inPssSection && line.startsWith("Total PSS by ") -> break
                inPssSection -> {
                    val m = processRegex.find(line) ?: continue
                    val pssKb = m.groupValues[1].replace(",", "").toIntOrNull() ?: continue
                    val pid = m.groupValues[3].toIntOrNull() ?: continue
                    val name = m.groupValues[2].trim().ifBlank { "pid=$pid" }
                    processes.add(
                        ProcessMemory(
                            pid = pid,
                            name = name,
                            pssKb = pssKb,
                            dalvikPssKb = null,
                            nativePssKb = null,
                            importance = null,
                        ),
                    )
                }
            }
        }

        return DumpsysMeminfo(
            totalKb = totalKb,
            freeKb = freeKb,
            ramStatus = ramStatus,
            processes = processes,
        )
    }

    private data class ProcMeminfoKb(
        val memTotalKb: Long,
        val memAvailableKb: Long,
        val memFreeKb: Long,
    )

    private fun readProcMeminfoKb(): ProcMeminfoKb? {
        return runCatching {
            val lines = File("/proc/meminfo").readLines()
            fun lineKb(key: String): Long? =
                lines.firstOrNull { it.startsWith(key) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.toLongOrNull()
            val total = lineKb("MemTotal") ?: return@runCatching null
            val available = lineKb("MemAvailable") ?: lineKb("MemFree") ?: return@runCatching null
            val free = lineKb("MemFree") ?: available
            ProcMeminfoKb(memTotalKb = total, memAvailableKb = available, memFreeKb = free)
        }.getOrNull()
    }

    private fun readSelfPssKb(): Int? {
        return runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.totalPss.takeIf { it > 0 }
        }.getOrNull()
    }
}
