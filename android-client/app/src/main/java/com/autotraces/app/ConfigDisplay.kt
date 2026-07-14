package com.autotraces.app

/**
 * 将云端下发的 [AppConfig] 格式化为设备端可读文案（与管理台「运行配置」字段对齐）。
 */
object ConfigDisplay {

    fun prettyRunMode(mode: String?): String = when (mode?.trim()?.lowercase()) {
        ServerRunMode.GROWTH -> "涨粉模式"
        ServerRunMode.LIVE -> "主播访问模式"
        ServerRunMode.DIRECT -> "直客访问模式"
        ServerRunMode.MUTUAL -> "互点引流模式"
        null, "" -> "—"
        else -> mode!!.trim()
    }

    fun formatProb(value: Double): String {
        val v = value.coerceIn(0.0, 1.0)
        return if (v == 0.0 || v == 1.0) {
            String.format("%.2f", v)
        } else {
            String.format("%.2f（约 %.0f%%）", v, v * 100)
        }
    }

    fun formatRangeSec(min: Double, max: Double): String =
        "${min} ~ ${max} 秒"

    fun formatRangeMin(min: Int, max: Int): String =
        if (min == 0 && max == 0) "不触发休息（0 ~ 0）" else "${min} ~ ${max} 分钟"

    fun formatTextListPreview(items: List<String>?, maxLines: Int = 4): String {
        val lines = (items ?: emptyList()).map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return "（空）"
        val head = lines.take(maxLines)
        val body = head.joinToString("\n") { "  · $it" }
        val more = if (lines.size > maxLines) "\n  … 共 ${lines.size} 条" else ""
        return body + more
    }

    fun buildReport(
        config: AppConfig,
        templateName: String,
        configLoaded: Boolean,
        deviceAlias: String?,
        tiktokLogUsername: String?,
    ): String = buildString {
        appendSection("概要") {
            appendLine("配置来源：${if (configLoaded) "已从云端拉取（get_config）" else "尚未成功拉取，以下为本地缓存/默认值"}")
            appendLine("生效模板：${templateName.ifBlank { "—" }}")
            appendLine("运行模式：${prettyRunMode(config.run_mode)}")
            appendLine("设备别名：${deviceAlias?.trim().orEmpty().ifBlank { "—" }}")
            val tu = tiktokLogUsername?.trim().orEmpty()
            if (tu.isNotBlank()) {
                appendLine("当前 TikTok 账号（日志上下文）：$tu")
            }
        }

        appendSection("拉取与休息") {
            appendLine("单次拉取用户名数量 pull_batch_size：${config.pull_batch_size}")
            appendLine("发作品前拉取素材 publish_post_material_fetch_count：${config.publish_post_material_fetch_count}（0=不拉取）")
            appendLine("每号每时段主播访问 live_visit_count_per_slot：${config.live_visit_count_per_slot}（0=关闭）")
            if (config.no_rest) {
                appendLine("休息策略 no_rest：不休息（模板强制关闭，永不进入批次休息）")
            } else {
                appendLine("休息策略 no_rest：关闭")
                appendLine("休息时长 break_min ~ break_max：${formatRangeMin(config.break_min, config.break_max)}")
            }
        }

        appendSection("访问与作品停留") {
            appendLine("访问延迟 visit_delay：${formatRangeSec(config.visit_delay_min, config.visit_delay_max)}")
            appendLine("作品停留 video_stay：${formatRangeSec(config.video_stay_min, config.video_stay_max)}")
        }

        appendSection("涨粉作品判定（仅涨粉）") {
            appendLine("点击后等待 growth_post_check_after_tap_delay_ms：${config.growth_post_check_after_tap_delay_ms} ms")
            appendLine("二次间隔 growth_post_second_check_delay_ms：${config.growth_post_second_check_delay_ms} ms")
        }

        appendSection("For You 预滑") {
            appendLine("预滑总时长 for_you_swipe_total_sec：${config.for_you_swipe_total_sec} 秒（0=关闭）")
            appendLine("点赞概率 for_you_like_prob：${formatProb(config.for_you_like_prob)}")
            appendLine("单条观看 for_you_watch：${formatRangeSec(config.for_you_watch_min_sec, config.for_you_watch_max_sec)}")
        }

        appendSection("互动概率（随机权重）") {
            appendLine("点赞 like_prob：${formatProb(config.like_prob)}")
            appendLine("评论 comment_prob：${formatProb(config.comment_prob)}")
            appendLine("收藏 favorite_prob：${formatProb(config.favorite_prob)}")
            appendLine("转发 share_prob：${formatProb(config.share_prob)}")
            appendLine("跳过作品点击 skip_post_click_prob：${formatProb(config.skip_post_click_prob)}")
            appendLine("评论用文字 comment_text_prob：${formatProb(config.comment_text_prob)}")
            appendLine("仅执行一个动作 action_single：${if (config.action_single) "是" else "否"}")
        }

        appendSection("每日上限（各动作单独计数）") {
            appendLine("点赞：${config.like_daily_limit} 次/天")
            appendLine("评论：${config.comment_daily_limit} 次/天")
            appendLine("引流评论：${config.mutual_traffic_comment_daily_limit} 次/天")
            appendLine("收藏：${config.favorite_daily_limit} 次/天")
            appendLine("转发：${config.share_daily_limit} 次/天")
        }

        appendSection("互动间隔与 TikTok 重启") {
            appendLine("互动间隔 action_delay：${formatRangeSec(config.action_delay_min, config.action_delay_max)}")
            appendLine("强停后等待 tiktok_force_stop_wait_ms：${config.tiktok_force_stop_wait_ms} ms")
            appendLine("拉起后等待 tiktok_launch_wait_ms：${config.tiktok_launch_wait_ms} ms")
            if (config.run_mode.trim().lowercase() in setOf(ServerRunMode.LIVE, ServerRunMode.DIRECT)) {
                appendLine("直客/主播 点击作品后 direct_post_action_delay_ms：${config.direct_post_action_delay_ms} ms")
            }
        }

        appendSection("引流 / 互点") {
            appendLine("Videos 列表轮次 mutual_videos_scroll_rounds：${config.mutual_videos_scroll_rounds}")
            appendLine("粉丝列表 Follow 上限 mutual_follow_tap_session_limit：${config.mutual_follow_tap_session_limit}")
            appendLine("引流评论 mutual_traffic_comment：${if (config.mutual_traffic_comment) "开启" else "关闭"}")
            appendLine("作者区关注 mutual_traffic_author_follow：${if (config.mutual_traffic_author_follow) "开启" else "关闭"}")
            appendLine("Followers 关注 mutual_traffic_followers_follow：${if (config.mutual_traffic_followers_follow) "开启" else "关闭"}")
            appendLine("互点仅关注 mutual_traffic_follow_only：${if (config.mutual_traffic_follow_only) "开启" else "关闭"}")
            appendLine("引流由管理台云端指令或队列 run_mode=mutual 触发（App 端无本机模式切换）")
        }

        appendSection("文案库") {
            appendLine("通用评论 comment_texts：")
            appendLine(formatTextListPreview(config.comment_texts))
            appendLine("引流评论 mutual_comment_texts：")
            appendLine(formatTextListPreview(config.mutual_comment_texts))
            appendLine("引流搜索词 mutual_traffic_target_texts：")
            appendLine(formatTextListPreview(config.mutual_traffic_target_texts))
            appendLine("改资料 Bio profile_edit_bio_texts：")
            appendLine(formatTextListPreview(config.profile_edit_bio_texts))
        }

        appendLine()
        appendLine("说明：以上为当前设备别名在云端解析后的生效参数；管理台修改模板后请点「重新拉取」或等待后台自动同步。")
    }

    private fun StringBuilder.appendSection(title: String, block: StringBuilder.() -> Unit) {
        appendLine("【$title】")
        block()
        appendLine()
    }
}
