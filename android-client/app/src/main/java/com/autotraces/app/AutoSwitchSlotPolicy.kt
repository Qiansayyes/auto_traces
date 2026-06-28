package com.autotraces.app

import com.autotraces.app.util.Config
import java.io.File
import kotlin.random.Random

/**
 * 自动切号时段策略：账号分配窗口 [start_min, end_min) 内，默认运行涨粉基线；
 * 发作品/互点等「插入操作」在窗口内到点触发，暂停涨粉 → 独占 TikTok → 结束后恢复涨粉。
 *
 * 扩展新插入：实现 [AutoSwitchSlotInsertion]，注册到 [AutoSwitchLocalScheduler] 的 insertions 列表。
 */
object AutoSwitchSlotWindow {
    /** 当前分钟是否落在账号分配时段内（左闭右开）。 */
    fun isWithinSlot(minuteOfDay: Int, slot: AutoSwitchPlanSlot): Boolean {
        val start = slot.start_min ?: 0
        val end = slot.end_min ?: 1440
        val minute = minuteOfDay.coerceIn(0, 1439)
        return minute in start until end
    }

    /** 插入操作触发窗口：时段内且已到 triggerMinute，且未过 end_min。 */
    fun isWithinInsertionTrigger(minuteOfDay: Int, slot: AutoSwitchPlanSlot, triggerMinute: Int): Boolean {
        if (!isWithinSlot(minuteOfDay, slot)) return false
        val end = slot.end_min ?: 1440
        val minute = minuteOfDay.coerceIn(0, 1439)
        return minute >= triggerMinute && minute < end
    }

    fun formatMinute(minuteOfDay: Int): String {
        val minute = minuteOfDay.coerceIn(0, 1439)
        return String.format("%02d:%02d", minute / 60, minute % 60)
    }
}

/** 每分钟 tick 传给插入策略的上下文。 */
data class AutoSwitchSlotTickContext(
    val alias: String,
    val deviceId: String,
    val bundle: AutoSwitchBundle,
    val scheduleDate: String,
    val slotKey: String,
    val slot: AutoSwitchPlanSlot,
    val account: AutoSwitchAccount,
    val minuteOfDay: Int,
)

/**
 * 插入操作宿主：由调度器实现，负责入队、状态与 ledger。
 * 新插入类型只依赖此接口，无需改 tick 主流程。
 */
interface AutoSwitchSlotInsertionHost {
    suspend fun enqueueInsertion(op: DeviceOp, slotKey: String, kind: String)
    fun isInsertionInflight(kind: String, slotKey: String): Boolean
    fun markInsertionInflight(slotKey: String, kind: String)
    fun graduateNewAccountVideoCount(account: AutoSwitchAccount)
    /** 插入入队前：OCR/自动登录保障目标账号已登录；失败时不入队（下分钟 tick 可重试）。 */
    suspend fun verifyInsertionAccountLoggedIn(ctx: AutoSwitchSlotTickContext, kind: String): Boolean
}

/** 时段内可插入的独占操作（发作品、互点等）。 */
interface AutoSwitchSlotInsertion {
    val kind: String
    /** @return true 表示已入队，本分钟 tick 不再尝试其它插入 */
    suspend fun trySchedule(ctx: AutoSwitchSlotTickContext, host: AutoSwitchSlotInsertionHost): Boolean
}

/** 发作品：在 auto_publish_minute 起、时段结束前触发一次。 */
class PublishPostSlotInsertion : AutoSwitchSlotInsertion {
    override val kind: String = "publish"

    override suspend fun trySchedule(
        ctx: AutoSwitchSlotTickContext,
        host: AutoSwitchSlotInsertionHost,
    ): Boolean {
        val slotKey = ctx.slotKey
        val pubMinute = ctx.slot.auto_publish_minute ?: run {
            logOnce(ctx.alias, slotKey, "no_minute") {
                LogCenter.op(
                    ctx.alias,
                    "【自动切号】跳过发作品：时段 $slotKey 无 auto_publish_minute（窗口过短或未生成）",
                )
            }
            return false
        }
        if (!AutoSwitchSlotWindow.isWithinInsertionTrigger(ctx.minuteOfDay, ctx.slot, pubMinute)) {
            if (ctx.minuteOfDay < pubMinute) {
                logOnce(ctx.alias, slotKey, "wait|$pubMinute") {
                    LogCenter.op(
                        ctx.alias,
                        "【自动切号】发作品未到点：slot=$slotKey 当前=${AutoSwitchSlotWindow.formatMinute(ctx.minuteOfDay)} 计划=${AutoSwitchSlotWindow.formatMinute(pubMinute)}",
                    )
                }
            }
            return false
        }

        val doneKey = "$slotKey|publish"
        val abortKey = "$slotKey|publish_aborted"
        if (PrefsStore.getAutoSwitchPublishDoneKey() == doneKey ||
            PrefsStore.getAutoSwitchPublishAbortedKey() == abortKey
        ) {
            return false
        }
        if (host.isInsertionInflight(kind, slotKey)) return false
        val resumeAccount = ctx.account.username?.trim().orEmpty()
            .ifBlank { PrefsStore.getLogContextUsername()?.trim().orEmpty() }
        if (
            PrefsStore.isPublishPostPending() &&
            PrefsStore.getAutoSwitchPublishSlotKey()?.trim() == slotKey &&
            PrefsStore.getPublishPostResumeNextIndex(resumeAccount.ifBlank { ctx.alias }) > 0
        ) {
            logOnce(ctx.alias, slotKey, "resume_pending") {
                LogCenter.op(
                    ctx.alias,
                    "【自动切号】跳过发作品：slot=$slotKey 同 OP 断点续发 pending 中",
                )
            }
            return false
        }
        if (AutoSwitchLedger.isPublishSlotDone(slotKey)) {
            PrefsStore.setAutoSwitchPublishDoneKey(doneKey)
            return false
        }
        if (AutoSwitchLedger.isPublishSlotAborted(slotKey)) {
            PrefsStore.setAutoSwitchPublishAbortedKey(abortKey)
            logOnce(ctx.alias, slotKey, "aborted") {
                LogCenter.op(
                    ctx.alias,
                    "【自动切号】跳过发作品：时段 $slotKey 本时段已失败/中止（不再重试）",
                )
            }
            return false
        }
        if (AutoSwitchLedger.isPublishSlotInflight(slotKey)) return false

        val targetRi = ctx.account.row_index ?: -1
        if (
            !PrefsStore.isAutoSwitchVerifiedAligned(slotKey, targetRi) ||
            !AutoSwitchLedger.isCutSwitchSlotVerified(slotKey, targetRi)
        ) {
            logOnce(ctx.alias, slotKey, "not_verified") {
                LogCenter.op(
                    ctx.alias,
                    "【自动切号】跳过发作品：slot=$slotKey 未验证对齐（row=$targetRi）",
                )
            }
            return false
        }

        val targetUser = ctx.account.username?.trim().orEmpty()
        if (targetUser.isBlank()) {
            logOnce(ctx.alias, slotKey, "no_username") {
                LogCenter.op(ctx.alias, "【自动切号】跳过发作品：slot=$slotKey 目标账号 username 为空")
            }
            return false
        }

        val rawVc = parseVideoCountRaw(ctx.account.video_count)
        if (rawVc == NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN) {
            host.graduateNewAccountVideoCount(ctx.account)
            AutoSwitchLedger.recordVideoCountGraduate(
                username = ctx.account.username?.trim().orEmpty(),
                rowIndex = ctx.account.row_index ?: -1,
                slotKey = slotKey,
            )
            PrefsStore.setAutoSwitchPublishDoneKey(doneKey)
            LogCenter.op(
                ctx.alias,
                "【自动切号】跳过发作品：新号冷却 video_count=-1→0 account=${ctx.account.username}（本时段不发，下一时段起正常发）",
            )
            return false
        }

        if (!host.verifyInsertionAccountLoggedIn(ctx, kind)) {
            logOnce(ctx.alias, slotKey, "login_safeguard") {
                LogCenter.op(
                    ctx.alias,
                    "【自动切号】跳过发作品：slot=$slotKey 入队前登录保障未通过 account=$targetUser（下分钟重试）",
                )
            }
            return false
        }

        val publishCount = autoPublishCountForVideoCount(rawVc)
        val pkg = ctx.bundle.run_config?.package_name?.trim().orEmpty().ifBlank { Config.TIKTOK_PACKAGE_NAME }
        val aliasClean = ctx.bundle.device_alias?.trim().orEmpty().ifBlank { ctx.alias }
        val opId = newLocalOpId("publish")
        val op = DeviceOp(
            op_id = opId,
            op_type = "publish_post_start",
            payload = DeviceOpPayload(
                device_id = ctx.deviceId,
                alias = aliasClean,
                package_name = pkg,
                publish_count = publishCount,
                source = "auto_switch",
                auto_switch_schedule_date = ctx.scheduleDate,
                auto_switch_slot_key = slotKey,
                account_username = ctx.account.username?.trim(),
            ),
        )
        AutoSwitchLedger.record(
            opId = opId,
            opType = "publish_post_start",
            status = "queued",
            message = "已入队发作品",
            slotKey = slotKey,
            username = ctx.account.username?.trim().orEmpty(),
            reportData = mapOf(
                "op_type" to "publish_post_start",
                "account_username" to ctx.account.username?.trim().orEmpty(),
                "auto_switch_slot_key" to slotKey,
                "publish_count" to publishCount,
            ),
            phaseOverride = "queued",
        )
        host.enqueueInsertion(op, slotKey, kind)
        LogCenter.op(
            ctx.alias,
            "【自动切号】已入队发作品 slot=$slotKey count=$publishCount account=${ctx.account.username}",
        )
        return true
    }

    companion object {
        private const val NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN = -1
        private val onceLogKeys = mutableSetOf<String>()

        private fun logOnce(alias: String, slotKey: String, tag: String, block: () -> Unit) {
            val key = "$alias|$slotKey|$tag"
            if (onceLogKeys.add(key)) block()
        }

        private fun newLocalOpId(prefix: String): String =
            "local-as-$prefix-${System.currentTimeMillis()}-${Random.nextInt(1000)}"

        private fun parseVideoCountRaw(raw: String?): Int {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return 0
            return text.toIntOrNull() ?: 0
        }

        private fun autoPublishCountForVideoCount(videoCount: Int): Int {
            val vc = videoCount.coerceAtLeast(0)
            return when {
                vc < 3 -> 1
                vc <= 9 -> 2
                else -> 3
            }
        }
    }
}
