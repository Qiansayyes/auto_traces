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
