package com.autotraces.app.videoded

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TaskDetailActivity : ComponentActivity() {
    private lateinit var headerView: TextView
    private lateinit var summaryView: TextView
    private lateinit var filesListView: ListView
    private lateinit var retryFileButton: Button
    private lateinit var retryAllButton: Button
    private var task: TaskSnapshot? = null
    private var selectedFile: TaskFileSnapshot? = null
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId != null) loadTask(taskId)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        headerView = TextView(this).apply {
            textSize = 22f
            text = "任务详情"
        }

        summaryView = TextView(this).apply {
            text = ""
            setPadding(0, 12, 0, 12)
        }

        filesListView = ListView(this).apply {
            setOnItemClickListener { _, _, position, _ ->
                selectedFile = task?.files?.getOrNull(position)
                retryFileButton.isEnabled = selectedFile?.status != "downloaded"
            }
        }

        retryFileButton = Button(this).apply {
            text = "重试选中文件"
            isEnabled = false
            setOnClickListener {
                val currentTask = task ?: return@setOnClickListener
                val file = selectedFile ?: return@setOnClickListener
                lifecycleScope.launch {
                    TaskRetryManager.retrySingleFile(this@TaskDetailActivity, currentTask, file)
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TASK_ID, currentTask.taskId))
                    loadTask(currentTask.taskId)
                }
            }
        }

        retryAllButton = Button(this).apply {
            text = "重试失败文件"
            setOnClickListener {
                val currentTask = task ?: return@setOnClickListener
                lifecycleScope.launch {
                    TaskRetryManager.retryTask(this@TaskDetailActivity, currentTask)
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TASK_ID, currentTask.taskId))
                    loadTask(currentTask.taskId)
                }
            }
        }

        root.addView(headerView)
        root.addView(summaryView)
        root.addView(filesListView)
        root.addView(retryFileButton)
        root.addView(retryAllButton)
        setContentView(root)
    }

    private fun loadTask(taskId: String) {
        val tasks = TaskStore.loadTasks(this)
        task = tasks.firstOrNull { it.taskId == taskId }
        val current = task
        if (current == null) {
            headerView.text = "任务详情"
            summaryView.text = "任务不存在"
            filesListView.adapter = null
            retryFileButton.isEnabled = false
            return
        }

        headerView.text = "任务详情 ${current.taskId.take(8)}"
        summaryView.text = buildString {
            appendLine("状态：${current.status}")
            appendLine("错误：${current.error ?: "无"}")
            appendLine("原始任务：${current.originalTaskId ?: "无"}")
            appendLine("重试来源：${current.retryOfTaskId ?: "无"}")
            appendLine("文件数量：${current.files.size}")
            append("(下载过程中每约 2.5 秒自动刷新本条与下方列表.)")
        }

        val labels = current.files.map { file ->
            val message = file.message?.takeIf { it.isNotBlank() } ?: "无说明"
            "${file.fileName}  [${file.status}]  重试:${file.retryCount}  $message"
        }
        filesListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        retryAllButton.isEnabled = current.files.any { it.status == "failed" || it.status == "retry_failed" }
    }

    private fun startPollingCurrentTask() {
        pollJob?.cancel()
        val id = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        pollJob = lifecycleScope.launch {
            delay(350)
            while (isActive) {
                loadTask(id)
                val snap = TaskStore.loadTasks(this@TaskDetailActivity).firstOrNull { it.taskId == id }
                val done =
                    snap != null &&
                        (
                            snap.status == "completed" ||
                                snap.status == "failed"
                            )
                val allDownloaded =
                    snap != null && snap.files.isNotEmpty() && snap.files.all { it.status == "downloaded" }
                if (done || allDownloaded) break
                delay(2500)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        intent.getStringExtra(EXTRA_TASK_ID)?.let { loadTask(it) }
        startPollingCurrentTask()
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        super.onPause()
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"
    }
}
