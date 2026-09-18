package com.autotraces.app.videoded

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TaskDownloadWorkflow {
    private const val TAG = "TaskDownloadWorkflow"
    private val gson = Gson()
    private val fileListType = object : TypeToken<List<TaskFileInfo>>() {}.type

    fun parseFilesJson(json: String): List<TaskFileInfo> =
        gson.fromJson(json, fileListType)

    fun toFilesJson(files: List<TaskFileInfo>): String = gson.toJson(files)

    /**
     * PC 经 USB adb push 已将文件写到 [targetPath]；本机只校验 MD5 并 ACK，不再经 Wi‑Fi/HTTP 重复下载。
     */
    suspend fun verifyUsbPushedAndAck(
        context: Context,
        taskId: String,
        files: List<TaskFileInfo>,
        targetPath: String?,
        onProgress: ((String) -> Unit)? = null,
    ) {
        val app = context.applicationContext
        suspend fun report(msg: String) {
            onProgress?.let { cb ->
                withContext(Dispatchers.Main) { cb(msg) }
            }
        }

        if (!TaskDownloadCoordinator.tryAcquire(taskId)) {
            Log.i(TAG, "verifyUsbPushedAndAck skipped (already running) taskId=$taskId")
            report("任务 $taskId 已在处理中，跳过重复执行")
            return
        }

        try {
            val root = java.io.File(CompanionApi.resolvedTargetPath(targetPath))
            val receivedDir = CompanionApi.receivedTaskDir(app, taskId)
            report("USB 已推送，正在校验 ${files.size} 个文件…")
            val completedFiles = mutableListOf<TaskFileAck>()
            for (file in files) {
                val pushed = java.io.File(root, file.fileName)
                // shell/root push 的 Pictures 文件对本 App 常 EACCES；先中转到私有 received 再验 MD5
                val local = java.io.File(receivedDir, file.fileName)
                report("中转并校验：${file.fileName}")
                GalleryMediaPublisher.copyToAppReadable(app, pushed, local)
                CompanionApi.verifyLocalFile(local, file)
                completedFiles.add(TaskFileAck(file.fileName, file.sizeBytes, file.md5))
                TaskStore.updateFileProgress(app, taskId, file.fileName, "downloaded")
                // 私有副本已就绪：删掉公共目录 push 原件，避免 shell 幽灵索引
                GalleryMediaPublisher.tryDeletePublicFile(pushed)
            }

            // 必须经 MediaStore 正规入库（IS_PENDING=0）才 ACK。
            // 禁止 UNIQUE 时用 file:// 假成功：那会让 PC 以为完成、TikTok 选图页仍空。
            val names = files.map { it.fileName }
            val galleryUris = try {
                GalleryMediaPublisher.publishAndDeleteReceived(app, taskId, names)
            } catch (t: Throwable) {
                val msg = t.message.orEmpty()
                if (msg.contains("UNIQUE", ignoreCase = true) ||
                    msg.contains("SQLITE_CONSTRAINT", ignoreCase = true) ||
                    msg.contains("mediastore_pending_stuck", ignoreCase = true)
                ) {
                    Log.w(TAG, "gallery publish retry after $msg")
                    report("相册入库冲突，清理索引后重试…")
                    GalleryMediaPublisher.publishAndDeleteReceived(app, taskId, names)
                } else {
                    throw t
                }
            }
            GalleryMediaPublisher.assertPublishedVisible(app, galleryUris)
            report("校验通过，已写入相册，向 PC 确认完成")

            CompanionApi.ackTask(app, taskId, true, "usb_push_complete", completedFiles)
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
            report("USB 传输任务完成：$taskId")
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
                receivedDir = null,
                galleryContentUriStrings = null,
            )
            report("USB 传输任务失败：${t.message.orEmpty()}")
            throw t
        } finally {
            TaskDownloadCoordinator.release(taskId)
        }
    }

    /**
     * 下载、校验 → 写入系统媒体库并删除 `received` 临时目录 → 向 Windows ACK → 更新 [TaskStore] → [VideoDedEvents.sendTaskFinished]。
     * [onProgress] 若提供，则在主线程回调（便于更新 UI）。
     */
    suspend fun downloadVerifyAndAck(
        context: Context,
        taskId: String,
        files: List<TaskFileInfo>,
        targetPath: String?,
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
                val msg = e.message.orEmpty()
                if (msg.contains("UNIQUE", ignoreCase = true) ||
                    msg.contains("SQLITE_CONSTRAINT", ignoreCase = true) ||
                    msg.contains("mediastore_pending_stuck", ignoreCase = true)
                ) {
                    Log.w(TAG, "gallery publish retry after HTTP path: $msg")
                    report("相册入库冲突，清理索引后重试…")
                    GalleryMediaPublisher.publishAndDeleteReceived(app, taskId, names)
                } else {
                    report("发布到相册失败：${e.message}")
                    throw e
                }
            }
            GalleryMediaPublisher.assertPublishedVisible(app, galleryUris)

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
}
