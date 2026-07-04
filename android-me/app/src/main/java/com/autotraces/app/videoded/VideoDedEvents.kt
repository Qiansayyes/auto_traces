package com.autotraces.app.videoded

import android.content.Context
import android.content.Intent

/**
 * 任务结束通知。
 *
 * - **未设置回调包名**（默认）：广播仅投递本包（Companion），[Intent.setPackage] 为当前应用。
 * - **同机异包**：先通过 HTTP `PUT /api/v1/settings/callback-package` 注册 `packageName` 后，广播仅投递该包名（你的 App 内需注册对应 [android.content.BroadcastReceiver]）。
 *
 * 成功且已写入系统媒体库时，附带 [EXTRA_GALLERY_CONTENT_URIS]；临时目录 `received/<taskId>/` 已删除，不再附带 [EXTRA_RECEIVED_DIR]。
 */
object VideoDedEvents {
    const val ACTION_TASK_FINISHED = "com.autotraces.app.videoded.ACTION_TASK_FINISHED"

    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_ERROR = "error"
    const val EXTRA_TARGET_PATH = "targetPath"
    /** 失败或未完成发布时：临时接收目录（若仍存在） */
    const val EXTRA_RECEIVED_DIR = "receivedDir"
    /** 成功写入系统「视频 / 相册」后的 `content://` URI（字符串列表） */
    const val EXTRA_GALLERY_CONTENT_URIS = "galleryContentUris"

    fun sendTaskFinished(
        context: Context,
        taskId: String,
        success: Boolean,
        targetPath: String?,
        error: String?,
        receivedDir: String?,
        galleryContentUriStrings: List<String>? = null,
    ) {
        val app = context.applicationContext
        val intent = Intent(ACTION_TASK_FINISHED).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_SUCCESS, success)
            if (targetPath != null) putExtra(EXTRA_TARGET_PATH, targetPath)
            if (error != null) putExtra(EXTRA_ERROR, error)
            if (receivedDir != null) putExtra(EXTRA_RECEIVED_DIR, receivedDir)
            if (!galleryContentUriStrings.isNullOrEmpty()) {
                putStringArrayListExtra(EXTRA_GALLERY_CONTENT_URIS, ArrayList(galleryContentUriStrings))
            }
        }
        val target = ApiSettings.getCallbackPackage(app)
        intent.setPackage(target ?: app.packageName)
        app.sendBroadcast(intent)
    }
}
