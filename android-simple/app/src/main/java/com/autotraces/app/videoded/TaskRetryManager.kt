package com.autotraces.app.videoded

import android.content.Context
import java.io.File

object TaskRetryManager {
    suspend fun retryTask(context: Context, task: TaskSnapshot) {
        val failedFiles = task.files.filter { it.status == "failed" || it.status == "retry_failed" }
        retryFailedFilesInPlace(context, task, failedFiles)
    }

    suspend fun retrySingleFile(context: Context, task: TaskSnapshot, file: TaskFileSnapshot) {
        retryFailedFilesInPlace(context, task, listOf(file))
    }

    suspend fun retryFailedFilesInPlace(context: Context, task: TaskSnapshot, failedFiles: List<TaskFileSnapshot>) {
        if (failedFiles.isEmpty()) return

        val refreshedTask = CompanionApi.createTaskAndWaitReady(context, failedFiles.size, task.targetPath)
        val mergedFiles = task.files.toMutableList()
        val newFiles = refreshedTask.files.associateBy { it.fileName }

        TaskStore.markTaskStatus(context, task.taskId, "retrying")

        var allOk = true
        failedFiles.forEach { oldFile ->
            val remote = newFiles[oldFile.fileName] ?: refreshedTask.files.first()
            val nextRetryCount = oldFile.retryCount + 1
            try {
                CompanionApi.downloadAndVerifyFile(context, refreshedTask.taskId, remote, maxAttempts = 3)
                val idx = mergedFiles.indexOfFirst { it.fileName == oldFile.fileName }
                if (idx >= 0) {
                    mergedFiles[idx] = oldFile.copy(
                        sizeBytes = remote.sizeBytes,
                        md5 = remote.md5,
                        status = "downloaded",
                        message = null,
                        retryCount = nextRetryCount,
                    )
                }
                TaskStore.updateFileProgress(context, task.taskId, oldFile.fileName, "downloaded", null, nextRetryCount)
            } catch (t: Throwable) {
                allOk = false
                val idx = mergedFiles.indexOfFirst { it.fileName == oldFile.fileName }
                if (idx >= 0) {
                    mergedFiles[idx] = oldFile.copy(
                        status = "failed",
                        message = t.message,
                        retryCount = nextRetryCount,
                    )
                }
                TaskStore.updateFileProgress(context, task.taskId, oldFile.fileName, "failed", t.message, nextRetryCount)
            }
        }

        if (allOk) {
            val ackFiles = failedFiles.mapNotNull { failed ->
                val updated = mergedFiles.firstOrNull { it.fileName == failed.fileName } ?: return@mapNotNull null
                TaskFileAck(updated.fileName, updated.sizeBytes, updated.md5)
            }
            CompanionApi.ackTask(context, refreshedTask.taskId, true, "retry_complete", ackFiles)
            TaskStore.markTaskStatus(context, task.taskId, "completed")
        } else {
            CompanionApi.ackTask(context, refreshedTask.taskId, false, "retry_failed", emptyList())
            TaskStore.markTaskStatus(context, task.taskId, "failed", "retry_failed")
        }

        TaskStore.saveTaskSnapshot(
            context,
            task.copy(files = mergedFiles)
        )
    }
}
