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
import kotlinx.coroutines.launch
import java.util.Date

class TaskHistoryActivity : ComponentActivity() {
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var retryButton: Button
    private lateinit var detailButton: Button
    private var items: List<TaskSnapshot> = emptyList()
    private var selectedTask: TaskSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadHistory()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "任务历史"
            textSize = 22f
        }

        emptyView = TextView(this).apply {
            text = "暂无历史任务"
        }

        listView = ListView(this).apply {
            setOnItemClickListener { _, _, position, _ ->
                selectedTask = items.getOrNull(position)
                retryButton.isEnabled = this@TaskHistoryActivity.canRetry(selectedTask)
                detailButton.isEnabled = selectedTask != null
            }
            setOnItemLongClickListener { _, _, position, _ ->
                selectedTask = items.getOrNull(position)
                openDetail(selectedTask)
                true
            }
        }

        detailButton = Button(this).apply {
            text = "查看任务详情"
            isEnabled = false
            setOnClickListener { openDetail(selectedTask) }
        }

        retryButton = Button(this).apply {
            text = "重试失败任务"
            isEnabled = false
            setOnClickListener {
                val task = selectedTask ?: return@setOnClickListener
                lifecycleScope.launch {
                    TaskRetryManager.retryTask(this@TaskHistoryActivity, task)
                    setResult(Activity.RESULT_OK, Intent().putExtra("taskId", task.taskId))
                    loadHistory()
                }
            }
        }

        root.addView(title)
        root.addView(emptyView)
        root.addView(listView)
        root.addView(detailButton)
        root.addView(retryButton)
        setContentView(root)
    }

    private fun loadHistory() {
        items = TaskStore.loadTasks(this)
        if (items.isEmpty()) {
            emptyView.text = "暂无历史任务"
            listView.adapter = null
            retryButton.isEnabled = false
            detailButton.isEnabled = false
            return
        }

        emptyView.text = ""
        val labels = items.map {
            val total = it.files.size
            val failed = it.files.count { f -> f.status == "failed" }
            val done = it.files.count { f -> f.status == "downloaded" }
            "${it.taskId.take(8)}  ${it.status}  ${Date(it.createdAtMs)}  ${done}/$total 已完成${if (failed > 0) " · $failed 失败" else ""}"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun canRetry(task: TaskSnapshot?): Boolean {
        if (task == null) return false
        return task.files.any { it.status == "failed" || it.status == "retry_failed" }
    }

    private fun openDetail(task: TaskSnapshot?) {
        if (task == null) return
        startActivity(Intent(this, TaskDetailActivity::class.java).putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.taskId))
    }
}
