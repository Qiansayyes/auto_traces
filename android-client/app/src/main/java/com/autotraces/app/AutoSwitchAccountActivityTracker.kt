package com.autotraces.app

import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 自治 session 内按「设备登录 TikTok 账号 × 时段 × 模板」记录涨粉/互点等行为计数，
 * 写入 [AutoSwitchLedger] 供关会话 / 每日对账上报。
 */
object AutoSwitchAccountActivityTracker {
    private val gson = Gson()
    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = beijingTz
    }

    private val trackedActions = listOf(
        "like",
        "comment",
        "favorite",
        "share",
        "mutual_traffic_comment",
        "mutual_follow",
    )

    private var openAccount: String = ""
    private var openTemplate: String = ""
    private var openSlotKey: String = ""
    private var openRunMode: String = ""
    private var openStartedAt: String = ""
    private var openConfigJson: String = ""
    private var baselineCounts: Map<String, Int> = emptyMap()
    private var lastConfig: AppConfig? = null
    private var lastTemplate: String = ""

    fun onConfigApplied(
        username: String?,
        template: String?,
        config: AppConfig,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val account = username?.trim().orEmpty()
        if (account.isEmpty()) return
        val tpl = template?.trim().orEmpty()
        if (openAccount.isNotEmpty() && !openAccount.equals(account, ignoreCase = true)) {
            finalizeSegment("account_switch")
        }
        if (openAccount.equals(account, ignoreCase = true)) {
            openTemplate = tpl.ifBlank { openTemplate }
            openConfigJson = buildTemplateConfigJson(config)
            openRunMode = config.run_mode.trim().lowercase()
            lastConfig = config
            lastTemplate = tpl.ifBlank { openTemplate }
            return
        }
        openAccount = account
        openTemplate = tpl
        openSlotKey = PrefsStore.getAutoSwitchLastSlotKey().orEmpty()
        openRunMode = config.run_mode.trim().lowercase()
        openStartedAt = nowBeijingText()
        openConfigJson = buildTemplateConfigJson(config)
        baselineCounts = snapshotActionCounts()
        lastConfig = config
        lastTemplate = tpl
    }

    /** 切号 / 对账 / 关会话前收尾当前账号活动段。 */
    fun finalizeSegment(reason: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        if (openAccount.isBlank()) return
        val endedAt = nowBeijingText()
        val endCounts = snapshotActionCounts()
        val delta = linkedMapOf<String, Int>()
        for (key in trackedActions) {
            val base = baselineCounts[key] ?: 0
            val end = endCounts[key] ?: 0
            val d = (end - base).coerceAtLeast(0)
            if (d > 0) delta[key] = d
        }
        AutoSwitchLedger.recordAccountActivitySegment(
            username = openAccount,
            slotKey = openSlotKey,
            activeTemplate = openTemplate,
            runMode = openRunMode,
            segmentStartedAt = openStartedAt,
            segmentEndedAt = endedAt,
            segmentReason = reason.trim().ifBlank { "unknown" },
            templateConfigJson = openConfigJson,
            actionCountsJson = gson.toJson(delta),
        )
        openAccount = ""
        openTemplate = ""
        openSlotKey = ""
        openRunMode = ""
        openStartedAt = ""
        openConfigJson = ""
        baselineCounts = emptyMap()
    }

    /** 每日对账上报后，同一账号继续累计下一段。 */
    fun reopenAfterReconcileIfNeeded() {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val account = PrefsStore.getLogContextUsername()?.trim().orEmpty()
        val cfg = lastConfig ?: return
        if (account.isEmpty()) return
        openAccount = account
        openTemplate = lastTemplate
        openSlotKey = PrefsStore.getAutoSwitchLastSlotKey().orEmpty()
        openRunMode = cfg.run_mode.trim().lowercase()
        openStartedAt = nowBeijingText()
        openConfigJson = buildTemplateConfigJson(cfg)
        baselineCounts = snapshotActionCounts()
    }

    fun reset() {
        openAccount = ""
        openTemplate = ""
        openSlotKey = ""
        openRunMode = ""
        openStartedAt = ""
        openConfigJson = ""
        baselineCounts = emptyMap()
        lastConfig = null
        lastTemplate = ""
    }

    private fun snapshotActionCounts(): Map<String, Int> =
        trackedActions.associateWith { PrefsStore.getDailyActionCount(it) }

    private fun buildTemplateConfigJson(config: AppConfig): String {
        val body = linkedMapOf<String, Any>(
            "run_mode" to config.run_mode.trim().lowercase(),
            "like_prob" to config.like_prob,
            "comment_prob" to config.comment_prob,
            "favorite_prob" to config.favorite_prob,
            "share_prob" to config.share_prob,
            "like_daily_limit" to config.like_daily_limit,
            "comment_daily_limit" to config.comment_daily_limit,
            "favorite_daily_limit" to config.favorite_daily_limit,
            "share_daily_limit" to config.share_daily_limit,
            "mutual_traffic_comment" to config.mutual_traffic_comment,
            "mutual_traffic_author_follow" to config.mutual_traffic_author_follow,
            "mutual_traffic_followers_follow" to config.mutual_traffic_followers_follow,
            "mutual_traffic_follow_only" to config.mutual_traffic_follow_only,
            "mutual_traffic_comment_daily_limit" to config.mutual_traffic_comment_daily_limit,
            "mutual_follow_tap_session_limit" to config.mutual_follow_tap_session_limit,
        )
        return gson.toJson(body)
    }

    private fun nowBeijingText(): String = tsFormat.format(Date())
}
