package com.autotraces.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.autotraces.app.videoded.VideoDedMainActivity
import com.google.android.material.button.MaterialButton

/** 顶部导航栏：设置 / 操作 / 待活跃 / 运行模式 / PC传输 / 日志。 */
object TopNavHelper {
    enum class Tab {
        SETTINGS,
        ACTION,
        PENDING_ACTIVATE,
        CONTROL_MODE,
        VIDEO_DED,
        LOG,
    }

    fun wire(activity: AppCompatActivity, current: Tab) {
        activity.findViewById<MaterialButton>(R.id.showSettingsButton)?.setOnClickListener {
            if (current == Tab.SETTINGS) return@setOnClickListener
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
            activity.finish()
        }
        activity.findViewById<MaterialButton>(R.id.showActionButton)?.setOnClickListener {
            if (current == Tab.ACTION) return@setOnClickListener
            activity.startActivity(
                Intent(activity, MainActivity::class.java).putExtra(EXTRA_INITIAL_PANEL, "action"),
            )
            activity.finish()
        }
        activity.findViewById<MaterialButton>(R.id.showPendingActivateButton)?.setOnClickListener {
            if (current == Tab.PENDING_ACTIVATE) return@setOnClickListener
            activity.startActivity(Intent(activity, PendingActivateActivity::class.java))
            activity.finish()
        }
        activity.findViewById<MaterialButton>(R.id.showControlModeButton)?.setOnClickListener {
            if (current == Tab.CONTROL_MODE) return@setOnClickListener
            activity.startActivity(Intent(activity, ControlModeActivity::class.java))
            activity.finish()
        }
        activity.findViewById<MaterialButton>(R.id.showVideoDedButton)?.setOnClickListener {
            if (current == Tab.VIDEO_DED) return@setOnClickListener
            activity.startActivity(Intent(activity, VideoDedMainActivity::class.java))
        }
        activity.findViewById<MaterialButton>(R.id.showLogButton)?.setOnClickListener {
            if (current == Tab.LOG) return@setOnClickListener
            activity.startActivity(
                Intent(activity, MainActivity::class.java).putExtra(EXTRA_INITIAL_PANEL, "log"),
            )
            activity.finish()
        }
    }
}
