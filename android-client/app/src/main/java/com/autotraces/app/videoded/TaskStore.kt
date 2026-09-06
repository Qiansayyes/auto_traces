package com.autotraces.app.videoded

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TaskStore {
    private const val PREFS = "video_ded"
    private const val KEY_HISTORY = "task_history_json"

    fun saveTaskSnapshot(context: Context, snapshot: TaskSnapshot) {
        val items = loadTasks(context).toMutableList()
        val existingIndex = items.indexOfFirst { it.taskId == snapshot.taskId }
        if (existingIndex >= 0) {
            items[existingIndex] = snapshot
        } else {
            items.add(snapshot)
        }
        persist(context, items)
    }

    fun loadTasks(context: Context): List<TaskSnapshot> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null)
            ?: return emptyList()

        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(TaskSnapshot.fromJson(arr.getJSONObject(i)))
                }
            }.sortedByDescending { it.createdAtMs }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun updateFileProgress(
        context: Context,
        taskId: String,
        fileName: String,
        status: String,
        message: String? = null,
        retryCount: Int? = null,
    ) {
        val tasks = loadTasks(context).toMutableList()
        val index = tasks.indexOfFirst { it.taskId == taskId }
        if (index < 0) return
        val task = tasks[index]
        val updatedFiles = task.files.toMutableList()
        val fileIndex = updatedFiles.indexOfFirst { it.fileName == fileName }
        if (fileIndex >= 0) {
            val current = updatedFiles[fileIndex]
            updatedFiles[fileIndex] = current.copy(
                status = status,
                message = message,
                retryCount = retryCount ?: current.retryCount
            )
        }
        tasks[index] = task.copy(files = updatedFiles)
        persist(context, tasks)
    }

    fun markTaskStatus(context: Context, taskId: String, status: String, error: String? = null) {
        val tasks = loadTasks(context).toMutableList()
        val index = tasks.indexOfFirst { it.taskId == taskId }
        if (index < 0) return
        tasks[index] = tasks[index].copy(status = status, error = error)
        persist(context, tasks)
    }

    private fun persist(context: Context, tasks: List<TaskSnapshot>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, JSONArray(tasks.map { it.toJson() }).toString())
            .apply()
    }
}

data class TaskSnapshot(
    val taskId: String,
    val status: String,
    val error: String?,
    val createdAtMs: Long,
    val targetPath: String?,
    val originalTaskId: String? = null,
    val retryOfTaskId: String? = null,
    val username: String? = null,
    val files: List<TaskFileSnapshot>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("taskId", taskId)
        put("status", status)
        put("error", error)
        put("createdAtMs", createdAtMs)
        put("targetPath", targetPath)
        put("originalTaskId", originalTaskId)
        put("retryOfTaskId", retryOfTaskId)
        if (!username.isNullOrBlank()) put("username", username)
        put("files", JSONArray(files.map { it.toJson() }))
    }

    companion object {
        fun fromJson(obj: JSONObject): TaskSnapshot = TaskSnapshot(
            taskId = obj.getString("taskId"),
            status = obj.getString("status"),
            error = obj.optString("error", null),
            createdAtMs = obj.getLong("createdAtMs"),
            targetPath = obj.optString("targetPath", null),
            originalTaskId = obj.optString("originalTaskId", null),
            retryOfTaskId = obj.optString("retryOfTaskId", null),
            username = obj.optString("username", null)?.takeIf { it.isNotBlank() },
            files = buildList {
                val arr = obj.optJSONArray("files") ?: JSONArray()
                for (i in 0 until arr.length()) add(TaskFileSnapshot.fromJson(arr.getJSONObject(i)))
            }
        )
    }
}

data class TaskFileSnapshot(
    val fileName: String,
    val sizeBytes: Long,
    val md5: String,
    val status: String,
    val message: String?,
    val retryCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fileName", fileName)
        put("sizeBytes", sizeBytes)
        put("md5", md5)
        put("status", status)
        put("message", message)
        put("retryCount", retryCount)
    }

    companion object {
        fun fromJson(obj: JSONObject): TaskFileSnapshot = TaskFileSnapshot(
            fileName = obj.getString("fileName"),
            sizeBytes = obj.getLong("sizeBytes"),
            md5 = obj.getString("md5"),
            status = obj.getString("status"),
            message = obj.optString("message", null),
            retryCount = obj.optInt("retryCount", 0)
        )
    }
}
