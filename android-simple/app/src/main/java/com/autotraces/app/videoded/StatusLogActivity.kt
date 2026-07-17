package com.autotraces.app.videoded

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.widget.NestedScrollView

class StatusLogActivity : ComponentActivity() {
    private lateinit var scroll: NestedScrollView
    private lateinit var logView: TextView

    private val logCallback: (String) -> Unit = { fullText ->
        val stickToBottom = isLogScrolledToBottom()
        logView.text = fullText
        if (stickToBottom) {
            scroll.post {
                scroll.fullScroll(View.FOCUS_DOWN)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "状态日志"
            textSize = 22f
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearBtn = Button(this).apply {
            text = "清理日志"
            setOnClickListener {
                StatusLogBuffer.clear()
                Toast.makeText(this@StatusLogActivity, "已清空", Toast.LENGTH_SHORT).show()
            }
        }
        val backBtn = Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }
        titleRow.addView(title)
        titleRow.addView(clearBtn)
        titleRow.addView(backBtn)

        scroll =
            NestedScrollView(this).apply {
                isVerticalScrollBarEnabled = true
                scrollBarFadeDuration = 0
            }
        logView =
            TextView(this).apply {
                text = StatusLogBuffer.snapshot()
                textSize = 13f
                setPadding(8, 8, 8, 8)
                setTextIsSelectable(true)
            }
        scroll.addView(
            logView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(titleRow)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        StatusLogBuffer.setListener(logCallback)
        scroll.post {
            scroll.fullScroll(View.FOCUS_DOWN)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onStop() {
        StatusLogBuffer.setListener(null)
        super.onStop()
    }

    private fun isLogScrolledToBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        val threshold = (16 * resources.displayMetrics.density).toInt().coerceAtLeast(8)
        val maxScroll =
            (child.height - scroll.height + scroll.paddingBottom).coerceAtLeast(0)
        if (maxScroll == 0) return true
        return scroll.scrollY >= maxScroll - threshold
    }
}
