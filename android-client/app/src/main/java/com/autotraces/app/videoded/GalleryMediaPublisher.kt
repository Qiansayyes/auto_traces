package com.autotraces.app.videoded

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
        taskDir.deleteRecursively()
        uris
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
            for (name in fileNames) {
                val src = File(root, name)
                if (!src.isFile) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 必须先拷到私有缓存：purge MediaStore 常会级联删掉 Pictures 下的 push 原文件；
                    // 且 shell 属主文件不能用 App File API 直接读（EACCES）。
                    val staging = stageReadableCopy(app, src, name)
                    try {
                        purgePicturesVideoByDisplayName(app, name)
                        runCatching { if (src.exists()) src.delete() }
                        // shell 文件 App 可能删不掉，再 su 删一次，避免双份幽灵
                        if (src.exists()) deleteViaSu(src)
                        insertMediaStoreQPlus(app, staging, name)
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
            Log.w(TAG, "direct stage failed for ${src.absolutePath}: ${directErr.message}")
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
            Log.w(TAG, "MediaStore stage failed for ${src.absolutePath}: ${msErr.message}")
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

    private fun runSu(command: String): Boolean {
        val candidates = listOf(
            arrayOf("su", "-c", command),
            arrayOf("/system/bin/su", "-c", command),
            arrayOf("/system/xbin/su", "-c", command),
            arrayOf("/sbin/su", "-c", command),
            arrayOf("/data/adb/magisk/su", "-c", command),
            arrayOf("/su/bin/su", "-c", command),
            arrayOf("sh", "-c", "su -c " + shellSingleQuote(command)),
        )
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
                } else {
                    p.exitValue() == 0
                }
            } catch (t: Throwable) {
                Log.w(TAG, "su candidate ${args.firstOrNull()} failed: ${t.message}")
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
        runCatching { resolver.delete(collection, selection, args) }
        // 非本包 owner 的行可能删不掉：用 su content delete 兜底
        runSu(
            "content delete --uri content://media/external/video/media " +
                "--where \"_display_name='${displayName.replace("'", "")}'\"",
        )
    }

    private fun insertMediaStoreQPlus(context: Context, source: File, displayName: String): Uri {
        if (!source.isFile || !source.canRead()) {
            error("mediastore_source_unreadable:${source.absolutePath}")
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val mime = mimeForFileName(displayName)
        // IS_PENDING=1 是 Android Q+ 正规两步写：占位→写字节→清 0。绝不能停在 1。
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("mediastore_insert_failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out) }
            } ?: error("mediastore_open_output_failed")
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            val updated = resolver.update(uri, done, null, null)
            if (updated <= 0) error("mediastore_clear_pending_failed")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            val msg = t.message.orEmpty()
            if (msg.contains("EACCES", ignoreCase = true) ||
                msg.contains("Permission denied", ignoreCase = true) ||
                t is SecurityException
            ) {
                throw IOException("eacces_mediastore_write:$msg:${source.absolutePath}", t)
            }
            throw t
        }
        return uri
    }

    private suspend fun insertLegacyPublicCopy(context: Context, source: File, displayName: String): Uri {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).apply { mkdirs() }
        val dest = File(dir, displayName)
        source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        val mime = mimeForFileName(displayName)
        val uri = suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(dest.absolutePath),
                arrayOf(mime),
            ) { _, scanned ->
                cont.resume(scanned ?: Uri.EMPTY)
            }
        }
        if (uri == Uri.EMPTY) error("mediascan_failed")
        return uri
    }
}
