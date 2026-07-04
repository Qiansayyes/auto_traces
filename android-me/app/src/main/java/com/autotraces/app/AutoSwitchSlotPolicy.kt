package com.autotraces.app

import com.autotraces.app.util.Config
import kotlin.random.Random

/**
 * 时段策略：涨粉基线运行在 [start_min, end_min)；
 * 发作品/引流在 bundle slot 的 [auto_publish_minute] / [auto_mutual_minute] 到点触发；
 * 距时段结束 [AutoSwitchLocalScheduler.SWITCH_SAFE_BUFFER_MIN] 分钟内不再启动新插入。
 */
object AutoSwitchSlotWindow {
    fun slotEndMin(slot: AutoSwitchPlanSlot): Int {
        val end = slot.end_min
        return if (end != null && end > 0) end else 1440
    }

    fun resolveSlotIndex(plan: AutoSwitchDailyPlan, minuteOfDay: Int): Int {
        val slots = plan.slots.orEmpty()
        if (slots.isEmpty()) return 0
        val minute = minuteOfDay.coerceIn(0, 1439)
        for (slot in slots) {
            val start = slot.start_min ?: 0
            val end = slotEndMin(slot)
            if (start <= minute && minute < end) {
                return slot.slot_index ?: 0
            }
        }
        return slots.last().slot_index ?: slots.size - 1
    }

    fun resolveTargetSlot(plan: AutoSwitchDailyPlan, minuteOfDay: Int): AutoSwitchPlanSlot? {
        val idx = resolveSlotIndex(plan, minuteOfDay)
        val slots = plan.slots.orEmpty()
        return slots.firstOrNull { (it.slot_index ?: -1) == idx }
            ?: slots.getOrNull(idx.coerceAtMost(slots.size - 1))
    }

    fun isWithinSlot(minuteOfDay: Int, slot: AutoSwitchPlanSlot): Boolean {
        val start = slot.start_min ?: 0
        val end = slot.end_min ?: 1440
        val minute = minuteOfDay.coerceIn(0, 1439)
        return minute in start until end
    }

    fun isWithinInsertionTrigger(minuteOfDay: Int, slot: AutoSwitchPlanSlot, triggerMinute: Int): Boolean {
        if (!isWithinSlot(minuteOfDay, slot)) return false
        val end = slot.end_min ?: 1440
        val minute = minuteOfDay.coerceIn(0, 1439)
        return minute >= triggerMinute && minute < end
    }

    fun isWithinSwitchSafeBuffer(minuteOfDay: Int, slot: AutoSwitchPlanSlot, bufferMin: Int): Boolean {
        if (bufferMin <= 0) return false
        val start = slot.start_min ?: 0
        val end = slotEndMin(slot)
        val minute = minuteOfDay.coerceIn(0, 1439)
        val bufferStart = (end - bufferMin).coerceAtLeast(start)
        return minute >= bufferStart && minute < end
    }

    fun formatMinute(minuteOfDay: Int): String {
        val minute = minuteOfDay.coerceIn(0, 1439)
        return String.format("%02d:%02d", minute / 60, minute % 60)
    }
}

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

interface AutoSwitchSlotInsertionHost {
    suspend fun enqueueInsertion(op: DeviceOp, slotKey: String, kind: String)
    fun isInsertionInflight(kind: String, slotKey: String): Boolean
    fun markInsertionInflight(slotKey: String, kind: String)
    fun graduateNewAccountVideoCount(account: AutoSwitchAccount)
    fun isSlotReady(slotKey: String, targetRowIndex: Int): Boolean
    fun isWithinSwitchSafeBuffer(minuteOfDay: Int, slot: AutoSwitchPlanSlot): Boolean
    suspend fun verifyInsertionAccountLoggedIn(ctx: AutoSwitchSlotTickContext, kind: String): Boolean
    fun insertionState(kind: String, slotKey: String): PlanStepState?
}

internal object AutoSwitchPublishVideoCount {
    const val NEW_ACCOUNT_COOLDOWN = -1

    fun parseRaw(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0
        return text.toIntOrNull() ?: 0
    }

    fun autoPublishCountFor(videoCount: Int): Int {
        val vc = videoCount.coerceAtLeast(0)
        return when {
            vc < 3 -> 1
            vc <= 9 -> 2
            else -> 3
        }
    }
}

interface AutoSwitchSlotInsertion {
    val kind: String
    suspend fun trySchedule(ctx: AutoSwitchSlotTickContext, host: AutoSwitchSlotInsertionHost): Boolean
}

/** 发作品：bundle slot [auto_publish_minute] 到点触发。 */
class PublishPostSlotInsertion : AutoSwitchSlotInsertion {
    override val kind: String = "publish"

    override suspend fun trySchedule(
        ctx: AutoSwitchSlotTickContext,
        host: AutoSwitchSlotInsertionHost,
    ): Boolean {
        val slotKey = ctx.slotKey
        if (PrefsStore.isAutoSwitchTikTokLoginHoldActive(slotKey)) {
            LogCenter.op(
                ctx.alias,
                "【自动切号】跳过发作品：slot=$slotKey 登录保障恢复中（暂停调度，登录持续重试）",
            )
            return false
        }
        val resumeSlotKey = AutoSwitchLocalPlanStore.activePublishSlotKey()?.trim().orEmpty()
            .ifBlank { PrefsStore.getAutoSwitchLastSlotKey().orEmpty() }
        val resumeAccount = ctx.account.username?.trim().orEmpty()
            .ifBlank { PrefsStore.getLogContextUsername()?.trim().orEmpty() }
        if (
            PrefsStore.isPublishPostPending() &&
            resumeSlotKey == slotKey &&
            (
                PrefsStore.getPublishPostResumeNextIndex(resumeAccount.ifBlank { ctx.alias }) > 0 ||
                    PrefsStore.isPublishPostMaterialReady()
                )
        ) {
            LogCenter.op(
                ctx.alias,
                "【自动切号】跳过发作品：slot=$slotKey 同 OP 断点续发 pending 中",
            )
            return false
        }
        val pubMinute = ctx.slot.auto_publish_minute ?: return false
        if (host.isWithinSwitchSafeBuffer(ctx.minuteOfDay, ctx.slot)) return false
        if (!AutoSwitchSlotWindow.isWithinInsertionTrigger(ctx.minuteOfDay, ctx.slot, pubMinute)) {
            return false
        }
        when (host.insertionState(kind, slotKey) ?: AutoSwitchLocalPlanStore.insertionState(slotKey, kind)) {
            PlanStepState.DONE, PlanStepState.SKIPPED, PlanStepState.RUNNING, PlanStepState.FAILED -> return false
            else -> Unit
        }
        if (host.isInsertionInflight(kind, slotKey)) return false
        val targetRi = ctx.account.row_index ?: -1
        if (!host.isSlotReady(slotKey, targetRi)) return false
        val targetUser = ctx.account.username?.trim().orEmpty()
        if (targetUser.isBlank()) return false
        val rawVc = AutoSwitchPublishVideoCount.parseRaw(ctx.account.video_count)
        if (rawVc == AutoSwitchPublishVideoCount.NEW_ACCOUNT_COOLDOWN) {
            host.graduateNewAccountVideoCount(ctx.account)
            AutoSwitchLocalPlanStore.markInsertionState(slotKey, kind, PlanStepState.SKIPPED)
            LogCenter.op(
                ctx.alias,
                "【自动切号】跳过发作品：新号冷却 video_count=-1 account=${ctx.account.username}",
            )
            return false
        }
        if (!host.verifyInsertionAccountLoggedIn(ctx, kind)) return false
        val publishCount = AutoSwitchPublishVideoCount.autoPublishCountFor(rawVc)
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
        host.enqueueInsertion(op, slotKey, kind)
        LogCenter.op(
            ctx.alias,
            "【自动切号】已入队发作品 slot=$slotKey count=$publishCount account=${ctx.account.username}",
        )
        return true
    }

    companion object {
        private fun newLocalOpId(prefix: String): String =
            "local-as-$prefix-${System.currentTimeMillis()}-${Random.nextInt(1000)}"
    }
}

/** 引流互点：bundle slot [auto_mutual_minute] 到点触发。 */
class MutualTrafficSlotInsertion : AutoSwitchSlotInsertion {
    override val kind: String = "mutual_traffic"

    override suspend fun trySchedule(
        ctx: AutoSwitchSlotTickContext,
        host: AutoSwitchSlotInsertionHost,
    ): Boolean {
        val slotKey = ctx.slotKey
        val mutualMinute = ctx.slot.auto_mutual_minute ?: return false
        val targetUser = ctx.account.username?.trim().orEmpty()
        if (targetUser.isBlank()) return false
        if (!ctx.bundle.isMutualTrafficConfiguredFor(targetUser)) {
            AutoSwitchLocalPlanStore.markInsertionState(slotKey, kind, PlanStepState.SKIPPED)
            return false
        }
        val opType = ctx.bundle.mutualTrafficOpTypeFor(targetUser) ?: return false
        if (host.isWithinSwitchSafeBuffer(ctx.minuteOfDay, ctx.slot)) return false
        if (!AutoSwitchSlotWindow.isWithinInsertionTrigger(ctx.minuteOfDay, ctx.slot, mutualMinute)) {
            return false
        }
        when (host.insertionState(kind, slotKey) ?: AutoSwitchLocalPlanStore.insertionState(slotKey, kind)) {
            PlanStepState.DONE, PlanStepState.SKIPPED, PlanStepState.FAILED, PlanStepState.RUNNING -> return false
            else -> Unit
        }
        if (host.isInsertionInflight(kind, slotKey)) return false
        val targetRi = ctx.account.row_index ?: -1
        if (!host.isSlotReady(slotKey, targetRi)) return false
        if (!host.verifyInsertionAccountLoggedIn(ctx, kind)) return false
        val pkg = ctx.bundle.run_config?.package_name?.trim().orEmpty().ifBlank { Config.TIKTOK_PACKAGE_NAME }
        val aliasClean = ctx.bundle.device_alias?.trim().orEmpty().ifBlank { ctx.alias }
        val opId = newLocalOpId("mutual-traffic")
        val op = DeviceOp(
            op_id = opId,
            op_type = opType,
            payload = DeviceOpPayload(
                device_id = ctx.deviceId,
                alias = aliasClean,
                package_name = pkg,
                source = "auto_switch",
                auto_switch_slot_key = slotKey,
                account_username = targetUser,
            ),
        )
        host.enqueueInsertion(op, slotKey, kind)
        val modeLabel = if (opType == "mutual_click_follow_only_start") "仅关注" else "引流"
        LogCenter.op(
            ctx.alias,
            "【自动切号】已入队引流（$modeLabel）slot=$slotKey 计划=${AutoSwitchSlotWindow.formatMinute(mutualMinute)} account=$targetUser",
        )
        return true
    }

    companion object {
        private fun newLocalOpId(prefix: String): String =
            "local-as-$prefix-${System.currentTimeMillis()}-${Random.nextInt(1000)}"
    }
}
