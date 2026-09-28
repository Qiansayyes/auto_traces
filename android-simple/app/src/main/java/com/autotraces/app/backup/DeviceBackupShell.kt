package com.autotraces.app.backup

/**
 * 设备端 Root 备份相关 shell 片段统一出口，避免「远控一套、本地一套」两套字符串漂移。
 *
 * **远控「备份并清理 TikTok」完整三步**（目标包 = 被备份的应用，如 TikTok）：
 * 1. `am force-stop <目标包>`
 * 2. [tiktokStyleDataTarCommand]（mkdir 统一目录 + find|tar）
 * 3. `pm clear <目标包>`
 *
 * find 使用两次 `find` + `{ …; }` 合并管道，避免 `\\( -type f -o -type l \\)` 在 Windows CMD
 * 经 `adb shell su -c "..."` 传参时反斜杠被吃掉导致设备 sh 报 `syntax error: unexpected '('`。
 */
object DeviceBackupShell {

    /**
     * `/data/data/<包名>` 目录（`du -sk`）低于此值时不打包 tar，仅 force-stop + pm clear。
     * 未登录/空数据通常 < 50MB；已登录账号一般 > 100MB。
     */
    const val MIN_DATA_DIR_KB_FOR_BACKUP: Long = 60L * 1024L

    fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    /**
     * 校验 .tar.gz 完整性（读完整流）。失败常见于截断/损坏包。
     * Android toybox：优先 `gzip -t`，再兜底 `tar -tzf`。
     */
    fun backupArchiveIntegrityTestCommand(quotedAbsolutePath: String): String =
        "gzip -t $quotedAbsolutePath 2>/dev/null || tar -tzf $quotedAbsolutePath >/dev/null 2>&1"

    fun forceStopCommand(dataPackageName: String): String =
        "am force-stop ${shellQuote(dataPackageName.trim())}"

    /** 进程仍存在时 stdout 非空；无匹配进程时通常为空。 */
    fun pidofCommand(dataPackageName: String): String =
        "pidof ${shellQuote(dataPackageName.trim())} 2>/dev/null"

    /**
     * 按 ps 进程名（NAME 列）列出目标包 pid，含 `:push` 等多进程。
     * 不用 `pgrep -f 包名`：会把 `am force-stop 包名` 的 sh/am 误判为 TikTok，导致永远杀不干净。
     */
    fun listPackageProcessPidsCommand(dataPackageName: String): String {
        val q = shellQuote(dataPackageName.trim())
        return "ps -A -o PID=,NAME= 2>/dev/null | awk -v p=$q '\$2==p || index(\$2,p\":\")==1 {printf \"%s \",\$1}' | sed 's/ *\$//'"
    }

    /** @see listPackageProcessPidsCommand */
    fun pgrepPackageCommand(dataPackageName: String): String =
        listPackageProcessPidsCommand(dataPackageName)

    /**
     * 补充强停：仅对 ps NAME 匹配包名（含 :push）的 pid 执行 kill -9。
     * 切号/备份前与 AppCoordinator 历史 force-stop 命令共用此串，避免两套杀进程逻辑漂移。
     * 注意：TikTok 图库授权路径禁止 force-stop。
     */
    fun supplementalKillCommand(dataPackageName: String): String {
        val q = shellQuote(dataPackageName.trim())
        return "for p in \$(pidof $q 2>/dev/null) \$(ps -A -o PID=,NAME= 2>/dev/null | awk -v p=$q '\$2==p || index(\$2,p\":\")==1 {print \$1}'); do " +
            "kill -9 \$p 2>/dev/null; done; true"
    }

    /** 返回 KB（与 `du -sk` 第一列一致）；目录不存在或 du 失败时返回 null。 */
    fun measureDataDirKbCommand(absoluteDataDir: String): String =
        "du -sk ${shellQuote(absoluteDataDir)} 2>/dev/null | awk '{print \$1}'"

    /**
     * 对应远控 `tiktok_backup_and_clear` 的步骤 2（不含前后的 force-stop / pm clear）。
     */
    fun tiktokStyleDataTarCommand(
        dataPackageName: String,
        quotedBackupDir: String,
        quotedOutputTarPath: String,
    ): String =
        "mkdir -p $quotedBackupDir && cd /data/data && " +
            "{ find $dataPackageName -type f ! -path '*/cache/*' ! -path '*/code_cache/*' -print0; " +
            "find $dataPackageName -type l ! -path '*/cache/*' ! -path '*/code_cache/*' -print0; } | " +
            "tar -czf $quotedOutputTarPath --null -T -"
}
