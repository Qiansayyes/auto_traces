package com.autotraces.app.videoded

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

/**
 * 将 [CompanionApi.receivedTaskDir] 下已下载的视频写入公共 Pictures 目录（与外部 adb 统计路径一致），
 * 成功后删除该任务临时目录。
 */
object GalleryMediaPublisher {

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

    private fun insertMediaStoreQPlus(context: Context, source: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val mime = mimeForFileName(displayName)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("mediastore_insert_failed")
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(source).use { input -> input.copyTo(out) }
        } ?: error("mediastore_open_output_failed")
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
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
