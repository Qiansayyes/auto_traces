package com.autotraces.app.videoded

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object TaskDownloadWorkflow {
    private const val TAG = "TaskDownloadWorkflow"
    private val gson = Gson()
    private val fileListType = object : TypeToken<List<TaskFileInfo>>() {}.type

    fun parseFilesJson(json: String): List<TaskFileInfo> =
        gson.fromJson(json, fileListType)

    fun toFilesJson(files: List<TaskFileInfo>): String = gson.toJson(files)

    /**
     * 下载、校验 → 写入系统媒体库并删除 `received` 临时目录 → 向 Windows ACK → 更新 [TaskStore] → [VideoDedEvents.sendTaskFinished]。
     * [adbPushed]=true 时优先认领 PC 经 USB `adb push` 已写入 [targetPath] 的文件，失败再回退 HTTP。
     * [onProgress] 若提供，则在主线程回调（便于更新 UI）。
     */
    suspend fun downloadVerifyAndAck(
        context: Context,
        taskId: String,
        files: List<TaskFileInfo>,
        targetPath: String?,
        adbPushed: Boolean = false,
        onProgress: ((String) -> Unit)? = null,
    ) {
        val app = context.applicationContext
        suspend fun report(msg: String) {
            onProgress?.let { cb ->
                withContext(Dispatchers.Main) { cb(msg) }
            }
        }

        val receivedDirBeforePublish = CompanionApi.receivedTaskDir(app, taskId).absolutePath

        if (!TaskDownloadCoordinator.tryAcquire(taskId)) {
            Log.i(TAG, "downloadVerifyAndAck skipped (already running) taskId=$taskId")
            report("任务 $taskId 已在下载中，跳过重复执行")
            return
        }

        try {
            if (adbPushed && tryConsumeUsbPushedFiles(app, taskId, files, targetPath, ::report)) {
                return
            }

            report("开始下载任务 ${taskId.take(8)}…（${files.size} 个文件）")
            val completedFiles = mutableListOf<TaskFileAck>()
            for (file in files) {
                report("正在下载：${file.fileName}")
                CompanionApi.downloadAndVerifyFile(app, taskId, file, maxAttempts = 3)
                TaskStore.updateFileProgress(app, taskId, file.fileName, "downloaded")
                completedFiles.add(TaskFileAck(file.fileName, file.sizeBytes, file.md5))
                report("已下载并校验：${file.fileName}")
            }

            val names = files.map { it.fileName }
            val galleryUris = try {
                GalleryMediaPublisher.publishAndDeleteReceived(app, taskId, names).also {
                    report("已发布到相册并完成清理")
                }
            } catch (e: Throwable) {
                report("发布到相册失败：${e.message}")
                throw e
            }

            CompanionApi.ackTask(app, taskId, true, "download_complete", completedFiles)
            TaskStore.saveTaskSnapshot(
                app,
                TaskSnapshot(
                    taskId = taskId,
                    status = "completed",
                    error = null,
                    createdAtMs = System.currentTimeMillis(),
                    targetPath = targetPath,
                    files = files.map { TaskFileSnapshot(it.fileName, it.sizeBytes, it.md5, "downloaded", null) },
                ),
            )
            VideoDedEvents.sendTaskFinished(
                app,
                taskId = taskId,
                success = true,
                targetPath = targetPath,
                error = null,
                receivedDir = null,
                galleryContentUriStrings = galleryUris,
            )
            report("任务完成：$taskId")
        } catch (t: Throwable) {
            val failedFiles = files.map { TaskFileAck(it.fileName, it.sizeBytes, it.md5) }
            runCatching {
                CompanionApi.ackTask(app, taskId, false, t.message, failedFiles)
            }
            TaskStore.saveTaskSnapshot(
                app,
                TaskSnapshot(
                    taskId = taskId,
                    status = "failed",
                    error = t.message,
                    createdAtMs = System.currentTimeMillis(),
                    targetPath = targetPath,
                    files = files.map { TaskFileSnapshot(it.fileName, it.sizeBytes, it.md5, "failed", t.message) },
                ),
            )
            VideoDedEvents.sendTaskFinished(
                app,
                taskId = taskId,
                success = false,
                targetPath = targetPath,
                error = t.message,
                receivedDir = receivedDirBeforePublish.takeIf { CompanionApi.receivedTaskDir(app, taskId).exists() },
                galleryContentUriStrings = null,
            )
            report("任务失败：${t.message.orEmpty()}")
            throw t
        } finally {
            TaskDownloadCoordinator.release(taskId)
        }
    }

    /**
     * PC 已按本机 USB serial push 到 targetPath：校验 MD5/大小 → 媒体库扫描 → ACK。
     * 任一文件缺失或不匹配则返回 false，由调用方回退 HTTP。
     */
    private suspend fun tryConsumeUsbPushedFiles(
        app: Context,
        taskId: String,
        files: List<TaskFileInfo>,
        targetPath: String?,
        report: suspend (String) -> Unit,
    ): Boolean {
        val root = File(CompanionApi.resolvedTargetPath(targetPath))
        report("USB 已推送，校验本机文件（${files.size}）…")
        val paths = mutableListOf<String>()
        val completedFiles = mutableListOf<TaskFileAck>()
        for (file in files) {
            if (file.fileName.isBlank() || file.fileName.contains('/') || file.fileName.contains('\\')) {
                Log.w(TAG, "usb push reject unsafe name=${file.fileName}")
                return false
            }
            val local = File(root, file.fileName)
            if (!CompanionApi.localFileMatches(file, local)) {
                Log.w(TAG, "usb push local mismatch task=$taskId file=${file.fileName} path=${local.absolutePath}")
                report("USB 本地校验失败：${file.fileName}，回退 HTTP 下载")
                return false
            }
            paths.add(local.absolutePath)
            completedFiles.add(TaskFileAck(file.fileName, file.sizeBytes, file.md5))
            TaskStore.updateFileProgress(app, taskId, file.fileName, "downloaded")
        }

        val galleryUris = scanExistingPaths(app, paths, files)
        CompanionApi.ackTask(app, taskId, true, "adb_usb_push_complete", completedFiles)
        TaskStore.saveTaskSnapshot(
            app,
            TaskSnapshot(
                taskId = taskId,
                status = "completed",
                error = null,
                createdAtMs = System.currentTimeMillis(),
                targetPath = targetPath,
                files = files.map { TaskFileSnapshot(it.fileName, it.sizeBytes, it.md5, "downloaded", null) },
            ),
        )
        VideoDedEvents.sendTaskFinished(
            app,
            taskId = taskId,
            success = true,
            targetPath = targetPath,
            error = null,
            receivedDir = null,
            galleryContentUriStrings = galleryUris,
        )
        report("USB 素材已就绪并 ACK：$taskId")
        return true
    }

    private suspend fun scanExistingPaths(
        context: Context,
        paths: List<String>,
        files: List<TaskFileInfo>,
    ): List<String> = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext emptyList()
        val mimes = files.map { GalleryMediaPublisher.mimeForFileName(it.fileName) }.toTypedArray()
        suspendCancellableCoroutine { cont ->
            val uris = arrayOfNulls<String>(paths.size)
            var remaining = paths.size
            MediaScannerConnection.scanFile(
                context,
                paths.toTypedArray(),
                mimes,
            ) { path, uri ->
                val idx = paths.indexOf(path)
                if (idx >= 0) {
                    uris[idx] = uri?.toString() ?: path
                }
                remaining -= 1
                if (remaining <= 0 && cont.isActive) {
                    cont.resume(uris.mapIndexed { i, u -> u ?: paths[i] })
                }
            }
        }
    }
}
