package com.autotraces.app.videoded

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 将 [CompanionApi.receivedTaskDir] 下已下载的视频写入公共 Pictures 目录（与外部 adb 统计路径一致），
 * 成功后删除该任务临时目录。
 *
 * USB `adb push` 落点常为 `owner=shell`：App 用 File API 直接读会 EACCES（Permission Denied）。
 * [scanExistingFiles] 必须先把源文件中转到本 App 可读位置（cache），再经 MediaStore 正规入库。
 *
 * ## 同名 / UNIQUE 策略
 * MediaStore 对 `_data` 路径有唯一约束。反复 push 同名文件时，若旧索引删不净再 insert，
 * 会抛 `UNIQUE constraint failed: files._data`，整单 ACK 失败，PC 素材进不了 `used/`。
 * 现行策略：先按路径/显示名查找已有行 → **覆盖写流复用 URI**；insert 冲突则再查或换时间戳文件名。
 */
object GalleryMediaPublisher {
    private const val TAG = "GalleryMediaPublisher"

    fun mimeForFileName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            else -> "video/*"
        }
    }

    /**
     * 将 [taskId] 目录下 [fileNames] 对应文件写入媒体库并删除临时目录。
     * @return 写入后的 `content://` URI 字符串列表（顺序与 [fileNames] 一致）
     */
    suspend fun publishAndDeleteReceived(
        context: Context,
        taskId: String,
        fileNames: List<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val taskDir = CompanionApi.receivedTaskDir(app, taskId)
        val uris = mutableListOf<String>()
        // 先清幽灵行，避免同名 UNIQUE / 选图点到空索引
        runCatching { purgeGhostPicturesVideoRows(app) }
        for (name in fileNames) {
            val src = File(taskDir, name)
            if (!src.isFile) error("missing_file:$name")
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                insertMediaStoreQPlus(app, src, name)
            } else {
                insertLegacyPublicCopy(app, src, name)
            }
            uris.add(uri.toString())
        }
        assertPublishedVisible(app, uris)
        // 入库后再扫一遍无关幽灵；若本批 URI 被误伤成幽灵，二次 assert 会失败
        runCatching { purgeGhostPicturesVideoRows(app) }
        assertPublishedVisible(app, uris)
        taskDir.deleteRecursively()
        uris
    }

    /**
     * ACK 前硬校验：必须是 content://，且 IS_PENDING=0，且 `_data` 指向的磁盘文件真实存在且 size>0。
     * 否则 PC/日志会报「拉取成功」，TikTok 选图页仍空或点到「幽灵行」（索引在、文件没了）。
     */
    fun assertPublishedVisible(context: Context, uriStrings: List<String>) {
        if (uriStrings.isEmpty()) error("gallery_publish_empty")
        val resolver = context.applicationContext.contentResolver
        for (raw in uriStrings) {
            if (!raw.startsWith("content://")) {
                error("gallery_uri_not_content:$raw")
            }
            val uri = Uri.parse(raw)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var pending = readIsPending(resolver, uri)
                if (pending == null) {
                    error("gallery_pending_unreadable:$raw")
                }
                if (pending != 0) {
                    if (!forceClearPending(resolver, uri)) {
                        error("gallery_still_pending:$raw")
                    }
                    pending = readIsPending(resolver, uri)
                    if (pending == null || pending != 0) {
                        error("gallery_still_pending:$raw")
                    }
                }
            }
            val disk = readMediaStoreDiskFile(resolver, uri)
                ?: error("gallery_data_unreadable:$raw")
            if (!disk.exists || disk.sizeBytes <= 0L) {
                // 索引还在、文件已删：典型幽灵行，禁止 ACK 成功
                purgeVideoUri(context.applicationContext, uri)
                error("gallery_ghost_missing_file:$raw:${disk.path}:size=${disk.sizeBytes}")
            }
        }
    }

    /**
     * 清理 Pictures 下「MediaStore 有行、磁盘无文件」的幽灵视频行。
     * 发作品选图前 / 入库后调用，避免固定点「第 N 张」点到不可用格导致 Next 空转。
     *
     * 注意：不得用「_data 为空 + 有文件名」当 Pictures——会误伤 DCIM/其它卷。
     */
    fun purgeGhostPicturesVideoRows(context: Context): Int {
        val app = context.applicationContext
        val resolver = app.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DISPLAY_NAME,
        )
        val ghosts = mutableListOf<Uri>()
        runCatching {
            resolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val relCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val rel = if (relCol >= 0) cursor.getString(relCol).orEmpty() else ""
                    val data = cursor.getString(dataCol).orEmpty()
                    val inPictures =
                        rel.startsWith("Pictures", ignoreCase = true) ||
                            data.contains("/Pictures/", ignoreCase = true)
                    if (!inPictures) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    val path = data.trim()
                    val fileMissing =
                        path.isNotEmpty() && (!File(path).isFile || File(path).length() <= 0L)
                    // _data 空时不要直接当幽灵：改用 URI 可读性（Scoped Storage 下 _data 常空但仍可用）
                    val uriUnreadable =
                        path.isEmpty() && !isMediaStoreUriReadable(resolver, uri)
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L
                    val sizeLie =
                        size > 0L && path.isNotEmpty() &&
                            (!File(path).isFile || File(path).length() <= 0L)
                    if (fileMissing || uriUnreadable || sizeLie) {
                        ghosts.add(uri)
                    }
                }
            }
        }
        for (uri in ghosts) {
            Log.w(TAG, "purge ghost MediaStore row: $uri")
            purgeVideoUri(app, uri)
        }
        return ghosts.size
    }

    private fun isMediaStoreUriReadable(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): Boolean {
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize > 0L
            } == true
        }.getOrDefault(false)
    }

    private data class MediaStoreDiskFile(val path: String, val exists: Boolean, val sizeBytes: Long)

    private fun readMediaStoreDiskFile(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): MediaStoreDiskFile? {
        return runCatching {
            // 优先用 URI 打开：Scoped Storage 下 File(_data) 可能误判不存在
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val len = pfd.statSize.coerceAtLeast(0L)
                if (len > 0L) {
                    return@runCatching MediaStoreDiskFile(
                        path = uri.toString(),
                        exists = true,
                        sizeBytes = len,
                    )
                }
            }
            resolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val path = cursor.getString(0).orEmpty()
                val declaredSize = if (cursor.columnCount > 1) cursor.getLong(1) else -1L
                if (path.isEmpty()) {
                    return@use MediaStoreDiskFile(path = "", exists = false, sizeBytes = 0L)
                }
                val f = File(path)
                val len = if (f.isFile) f.length() else 0L
                MediaStoreDiskFile(
                    path = path,
                    exists = f.isFile && len > 0L,
                    sizeBytes = if (len > 0L) len else 0L,
                ).also {
                    // declaredSize 不能代替真实可读；缺文件时保持 exists=false
                    if (!it.exists && declaredSize > 0L) {
                        Log.w(TAG, "MediaStore size=$declaredSize but file missing: $path")
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * 文件已在公共目录（USB `adb push` 落点）。
     * 不能只 `MediaScannerConnection.scanFile`：shell/root push 的文件常被标成
     * `IS_PENDING`/owner=shell，系统相册可见但 TikTok 选图页看不到。
     * 改为经本 App MediaStore 正式写入（IS_PENDING 1→写流→0），再删掉 push 原文件。
     *
     * 关键：直接 `File.copyTo` / `FileInputStream` 读 shell 属主文件会 EACCES；
     * 必须先 [stageReadableCopy]（直拷 → MediaStore 读 → su cp）再入库。
     */
    suspend fun scanExistingFiles(context: Context, root: File, fileNames: List<String>) {
        if (fileNames.isEmpty()) return
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            ensureOwnMediaReadGrants()
            runCatching { purgeGhostPicturesVideoRows(app) }
            for (name in fileNames) {
                val src = File(root, name)
                if (!src.isFile) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 必须先拷到私有缓存：purge MediaStore 常会级联删掉 Pictures 下的 push 原文件；
                    // 且 shell 属主文件不能用 App File API 直接读（EACCES）。
                    val staging = stageReadableCopy(app, src, name)
                    try {
                        // shell/IS_PENDING 幽灵行禁止直接 reuse：先 purge 再 App 正规入库
                        val absHint = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            name,
                        ).absolutePath
                        val existing = findExistingVideoUri(app, name, src.absolutePath)
                            ?: findExistingVideoUri(app, name, absHint)
                        if (existing != null) {
                            purgeVideoUri(app, existing)
                        }
                        purgePicturesVideoByDisplayName(app, name)
                        insertMediaStoreQPlus(app, staging, name)
                        runCatching { if (src.exists()) src.delete() }
                        // shell 文件 App 可能删不掉，再 su 删一次，避免双份幽灵
                        if (src.exists()) deleteViaSu(src)
                    } finally {
                        runCatching { staging.delete() }
                    }
                } else {
                    val staging = stageReadableCopy(app, src, name)
                    try {
                        insertLegacyPublicCopy(app, staging, name)
                        runCatching { if (src.exists()) src.delete() }
                        if (src.exists()) deleteViaSu(src)
                    } finally {
                        runCatching { staging.delete() }
                    }
                }
            }
        }
    }

    /** 尽力删除公共目录文件（File.delete 失败时 su rm，清 shell push 残留）。 */
    fun tryDeletePublicFile(file: File) {
        runCatching { if (file.exists()) file.delete() }
        if (file.exists()) deleteViaSu(file)
    }

    /**
     * 将 USB/公共目录下可能属 shell 的文件拷到 [dest]（本 App 可读）。
     * 供 USB 校验 MD5 与 MediaStore 入库共用，避免 FileInputStream EACCES。
     */
    fun copyToAppReadable(context: Context, src: File, dest: File) {
        val staged = stageReadableCopy(context.applicationContext, src, dest.name)
        try {
            dest.parentFile?.mkdirs()
            if (staged.absolutePath != dest.absolutePath) {
                staged.copyTo(dest, overwrite = true)
            }
        } finally {
            if (staged.absolutePath != dest.absolutePath) {
                runCatching { staged.delete() }
            }
        }
        if (!dest.isFile) error("eacces_copy_dest_missing:${dest.absolutePath}")
    }

    /**
     * 把可能属 shell/不可读的公共目录文件，落到本 App cache（可读）。
     * 顺序：直拷 → MediaStore openInputStream → su cp。
     */
    private fun stageReadableCopy(context: Context, src: File, displayName: String): File {
        val staging = File(context.cacheDir, "usb_repub_${System.nanoTime()}_$displayName")
        staging.parentFile?.mkdirs()
        runCatching { if (staging.exists()) staging.delete() }

        val expectedLen = src.length().coerceAtLeast(0L)

        // 1) 直拷（HTTP 下载落私有目录、或已 chmod 的公共文件）
        val directErr = runCatching {
            src.copyTo(staging, overwrite = true)
            null
        }.exceptionOrNull()
        if (directErr == null && staging.isFile && (expectedLen == 0L || staging.length() == expectedLen)) {
            return staging
        }
        runCatching { staging.delete() }
        if (directErr != null) {
            // 对 shell 属主文件属预期回退，下一跳 MediaStore/su；仅 debug
            Log.d(TAG, "direct stage failed for ${src.absolutePath}: ${directErr.message}")
        }

        // 2) 经 MediaStore 读（需 READ_MEDIA_*；adb push 后可能尚未索引，先强制 scan）
        forceMediaScanBlocking(context, src)
        val msErr = runCatching {
            copyFromMediaStoreByAbsolutePath(context, src, staging)
            null
        }.exceptionOrNull()
        if (msErr == null && staging.isFile && staging.length() > 0L &&
            (expectedLen == 0L || staging.length() == expectedLen)
        ) {
            return staging
        }
        runCatching { staging.delete() }
        if (msErr != null) {
            Log.d(TAG, "MediaStore stage failed for ${src.absolutePath}: ${msErr.message}")
        }

        // 3) su cp（机群有 root；解决 shell 属主 + SELinux 对 App File API 的 EACCES）
        //    无 su 的机（如个别 Lineage）依赖步骤 2；步骤 3 再尝试 chown 后直读兜底
        if (!copyViaSu(src, staging)) {
            val chownOk = runSu(
                "chown $(stat -c %u:%g ${shellSingleQuote(context.cacheDir.absolutePath)}) " +
                    "${shellSingleQuote(src.absolutePath)} 2>/dev/null; " +
                    "chmod 666 ${shellSingleQuote(src.absolutePath)}",
            )
            if (chownOk) {
                val chownCopyErr = runCatching {
                    src.copyTo(staging, overwrite = true)
                    null
                }.exceptionOrNull()
                if (chownCopyErr == null && staging.isFile &&
                    (expectedLen == 0L || staging.length() == expectedLen)
                ) {
                    return staging
                }
                runCatching { staging.delete() }
            }
            val hint = directErr?.message ?: msErr?.message ?: "unreadable"
            error("eacces_stage_failed:$hint:${src.absolutePath}")
        }
        if (!staging.isFile || staging.length() == 0L) {
            error("eacces_stage_empty:${src.absolutePath}")
        }
        if (expectedLen > 0L && staging.length() != expectedLen) {
            error("eacces_stage_size_mismatch:expected=$expectedLen actual=${staging.length()}:${src.absolutePath}")
        }
        return staging
    }

    private fun copyFromMediaStoreByAbsolutePath(context: Context, src: File, dest: File) {
        val resolver = context.contentResolver
        val abs = src.absolutePath
        val name = src.name
        val collections = listOf(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
        var opened: Uri? = null
        for (collection in collections) {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
            val selection =
                "${MediaStore.MediaColumns.DATA}=? OR ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            val args = arrayOf(abs, name)
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                    if (data != null && data != abs && !data.endsWith("/$name")) continue
                    val id = cursor.getLong(idCol)
                    opened = Uri.withAppendedPath(collection, id.toString())
                    break
                }
            }
            if (opened != null) break
        }
        val uri = opened ?: error("mediastore_row_not_found:$abs")
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("mediastore_open_input_failed:$uri")
    }

    private fun forceMediaScanBlocking(context: Context, file: File) {
        if (!file.isFile) return
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeForFileName(file.name)),
            ) { _, _ -> latch.countDown() }
            latch.await(8, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "media scan failed for ${file.absolutePath}: ${t.message}")
        }
    }

    private fun shellSingleQuote(path: String): String =
        "'" + path.replace("'", "'\\''") + "'"

    private fun copyViaSu(src: File, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val cmd =
            "cp -f ${shellSingleQuote(src.absolutePath)} ${shellSingleQuote(dest.absolutePath)} && " +
                "chmod 666 ${shellSingleQuote(dest.absolutePath)}"
        return runSu(cmd) && dest.isFile && dest.length() > 0L
    }

    private fun deleteViaSu(file: File): Boolean {
        if (!file.exists()) return true
        return runSu("rm -f ${shellSingleQuote(file.absolutePath)}")
    }

    private fun runSu(command: String): Boolean = runSuWithArgs(
        listOf(
            arrayOf("su", "-c", command),
            arrayOf("/system/bin/su", "-c", command),
            arrayOf("/system/xbin/su", "-c", command),
            arrayOf("/sbin/su", "-c", command),
            arrayOf("/data/adb/magisk/su", "-c", command),
            arrayOf("/su/bin/su", "-c", command),
            arrayOf("sh", "-c", "su -c " + shellSingleQuote(command)),
        ),
    )

    /**
     * 以 shell 身份跑 content 命令：root 直接 delete MediaStore 常报
     * `UID 0 has no associated package`；`su shell -c content delete` 可删 shell 幽灵行
     * （部分机 exit 非 0 / segfault，以复查询为准）。
     */
    private fun runSuAsShell(command: String): Boolean = runSuWithArgs(
        listOf(
            arrayOf("su", "shell", "-c", command),
            arrayOf("/system/bin/su", "shell", "-c", command),
            arrayOf("sh", "-c", "su shell -c " + shellSingleQuote(command)),
        ),
        requireExitZero = false,
    )

    private fun runSuWithArgs(
        candidates: List<Array<String>>,
        requireExitZero: Boolean = true,
    ): Boolean {
        for (args in candidates) {
            val ok = try {
                val p = ProcessBuilder(*args).redirectErrorStream(true).start()
                Thread {
                    runCatching { p.inputStream.bufferedReader().use { it.readText() } }
                }.start()
                val finished = p.waitFor(120, TimeUnit.SECONDS)
                if (!finished) {
                    runCatching { p.destroyForcibly() }
                    false
                } else if (requireExitZero) {
                    p.exitValue() == 0
                } else {
                    // shell content delete 可能 segfault 但仍删成功
                    true
                }
            } catch (t: Throwable) {
                Log.d(TAG, "su candidate ${args.firstOrNull()} failed: ${t.message}")
                false
            }
            if (ok) return true
        }
        return false
    }

    /** 尽力为自身授予媒体读权限（机群 root），便于 MediaStore 读 shell 索引行。 */
    private fun ensureOwnMediaReadGrants() {
        val pkg = "com.autotraces.app"
        val cmds = listOf(
            "pm grant $pkg android.permission.READ_MEDIA_VIDEO",
            "pm grant $pkg android.permission.READ_MEDIA_IMAGES",
            "pm grant $pkg android.permission.READ_EXTERNAL_STORAGE",
            "appops set $pkg READ_MEDIA_VIDEO allow",
            "appops set $pkg READ_MEDIA_IMAGES allow",
            "appops set $pkg READ_EXTERNAL_STORAGE allow",
        )
        for (c in cmds) {
            runSu(c)
        }
    }

    /** 删除 Pictures 下同名视频的 MediaStore 行（会级联删文件时仅删索引行对应项）。 */
    private fun purgePicturesVideoByDisplayName(context: Context, displayName: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection =
            "${MediaStore.Video.Media.DISPLAY_NAME}=? AND (" +
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" +
                ")"
        val args = arrayOf(displayName, "Pictures/", "Pictures/%")
        val ids = mutableListOf<Long>()
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                selection,
                args,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) ids.add(cursor.getLong(idCol))
            }
        }
        runCatching { resolver.delete(collection, selection, args) }
        for (id in ids) {
            purgeVideoUri(context, ContentUris.withAppendedId(collection, id))
        }
        // where 兜底（部分机有效）
        runSu(
            "content delete --uri content://media/external/video/media " +
                "--where \"_display_name='${displayName.replace("'", "")}'\"",
        )
        runSuAsShell(
            "content delete --uri content://media/external/video/media " +
                "--where \"_display_name='${displayName.replace("'", "")}'\"",
        )
    }

    /** App 删不掉的 shell 幽灵行：root content 常失败，改用 su shell content delete。 */
    private fun purgeVideoUri(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
        val flat = uri.toString()
        runSu("content delete --uri $flat")
        runSuAsShell("content delete --uri $flat")
    }

    private fun readIsPending(resolver: android.content.ContentResolver, uri: Uri): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.IS_PENDING),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getInt(0)
            }
        }.getOrNull()
    }

    /**
     * 清 IS_PENDING：App update → su content update。
     * USB push 幽灵行 App 常 update 失败/返回 0，必须 su 兜底，否则 TikTok 选图页不可见。
     */
    private fun forceClearPending(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        runCatching { resolver.update(uri, done, null, null) }
        if (readIsPending(resolver, uri) == 0) return true
        val id = try {
            ContentUris.parseId(uri)
        } catch (_: Exception) {
            -1L
        }
        if (id >= 0L) {
            val updateCmd =
                "content update --uri content://media/external/video/media/$id --bind is_pending:i:0"
            runSu(updateCmd)
            runSuAsShell(updateCmd)
        }
        val after = readIsPending(resolver, uri)
        // 读不到 pending 不能当成功：否则 shell 幽灵行会 ACK 成功但 TikTok 仍不可见
        return after == 0
    }

    private fun insertMediaStoreQPlus(context: Context, source: File, displayName: String): Uri {
        if (!source.isFile || !source.canRead()) {
            error("mediastore_source_unreadable:${source.absolutePath}")
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val mime = mimeForFileName(displayName)
        val absHint = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            displayName,
        ).absolutePath

        // 同名已在库（多为 USB shell 幽灵）：先 purge，再 App 新建；purge 不掉才覆盖并强制清 pending
        findExistingVideoUri(context, displayName, absHint)?.let { existing ->
            purgeVideoUri(context, existing)
        }
        purgePicturesVideoByDisplayName(context, displayName)

        findExistingVideoUri(context, displayName, absHint)?.let { existing ->
            Log.w(TAG, "reuse after purge-failed for $displayName -> $existing")
            overwriteMediaStoreUri(resolver, existing, source, markPendingAroundWrite = true)
            if (!forceClearPending(resolver, existing)) {
                error("mediastore_pending_stuck:$displayName:$existing")
            }
            return existing
        }

        // IS_PENDING=1 是 Android Q+ 正规两步写：占位→写字节→清 0。绝不能停在 1。
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert conflict for $displayName: ${e.message}")
            null
        }

        if (uri == null) {
            findExistingVideoUri(context, displayName, absHint)?.let { existing ->
                overwriteMediaStoreUri(resolver, existing, source, markPendingAroundWrite = true)
                if (!forceClearPending(resolver, existing)) {
                    error("mediastore_pending_stuck:$displayName:$existing")
                }
                return existing
            }
            val uniqueName = uniqueDisplayName(displayName)
            val retryValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, uniqueName)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val retryUri = try {
                resolver.insert(collection, retryValues)
            } catch (e: Exception) {
                throw IOException("mediastore_insert_failed:${e.message}", e)
            } ?: error("mediastore_insert_failed")
            try {
                overwriteMediaStoreUri(resolver, retryUri, source, clearPending = true)
                if (!forceClearPending(resolver, retryUri)) {
                    error("mediastore_pending_stuck:$uniqueName:$retryUri")
                }
            } catch (t: Throwable) {
                runCatching { resolver.delete(retryUri, null, null) }
                throw wrapMediaStoreWriteError(t, source)
            }
            return retryUri
        }

        try {
            overwriteMediaStoreUri(resolver, uri, source, clearPending = true)
            if (!forceClearPending(resolver, uri)) {
                error("mediastore_pending_stuck:$displayName:$uri")
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw wrapMediaStoreWriteError(t, source)
        }
        return uri
    }

    private fun wrapMediaStoreWriteError(t: Throwable, source: File): Throwable {
        val msg = t.message.orEmpty()
        if (msg.contains("EACCES", ignoreCase = true) ||
            msg.contains("Permission denied", ignoreCase = true) ||
            t is SecurityException
        ) {
            return IOException("eacces_mediastore_write:$msg:${source.absolutePath}", t)
        }
        return t
    }

    private fun overwriteMediaStoreUri(
        resolver: android.content.ContentResolver,
        uri: Uri,
        source: File,
        clearPending: Boolean = false,
        markPendingAroundWrite: Boolean = false,
    ) {
        if (markPendingAroundWrite && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 1) },
                    null,
                    null,
                )
            }
        }
        val stream = try {
            resolver.openOutputStream(uri, "wt")
                ?: resolver.openOutputStream(uri)
        } catch (e: Exception) {
            Log.w(TAG, "openOutputStream wt failed, fallback: ${e.message}")
            resolver.openOutputStream(uri)
        } ?: error("mediastore_open_output_failed")

        stream.use { out ->
            FileInputStream(source).use { input -> input.copyTo(out) }
        }
        if (clearPending || markPendingAroundWrite) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (!forceClearPending(resolver, uri)) {
                    error("mediastore_clear_pending_failed:$uri")
                }
            }
        }
    }

    /**
     * 按显示名 + 可选绝对路径查找已有视频行。
     * USB push 后 RELATIVE_PATH 常为空/不一致，必须用 `_data` 兜底，否则会误判「不存在」再 insert → UNIQUE。
     */
    private fun findExistingVideoUri(
        context: Context,
        displayName: String,
        absolutePath: String? = null,
    ): Uri? {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Video.Media._ID)

        if (!absolutePath.isNullOrBlank()) {
            resolver.query(
                collection,
                projection,
                "${MediaStore.Video.Media.DATA}=?",
                arrayOf(absolutePath),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val selection =
                "${MediaStore.Video.Media.DISPLAY_NAME}=? AND (" +
                    "${MediaStore.Video.Media.RELATIVE_PATH}=? OR ${MediaStore.Video.Media.RELATIVE_PATH}=? OR " +
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?)"
            val args = arrayOf(
                displayName,
                "${Environment.DIRECTORY_PICTURES}/",
                Environment.DIRECTORY_PICTURES,
                "Pictures/%",
            )
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }
            resolver.query(
                collection,
                projection,
                "${MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }
        } else {
            resolver.query(
                collection,
                projection,
                "${MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }
        }
        return null
    }

    private fun uniqueDisplayName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        return "${base}_${System.currentTimeMillis()}$ext"
    }

    private suspend fun insertLegacyPublicCopy(context: Context, source: File, displayName: String): Uri {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).apply { mkdirs() }
        val dest = File(dir, displayName)
        // 同名已存在：覆盖文件内容后再 scan / 复用
        source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        val mime = mimeForFileName(displayName)
        findExistingVideoUri(context, displayName, dest.absolutePath)?.let { return it }
        val uri = suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(dest.absolutePath),
                arrayOf(mime),
            ) { _, scanned ->
                cont.resume(scanned ?: Uri.EMPTY)
            }
        }
        if (uri != Uri.EMPTY) return uri
        return findExistingVideoUri(context, displayName, dest.absolutePath)
            ?: error("mediascan_failed")
    }
}
