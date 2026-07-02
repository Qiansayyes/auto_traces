package com.autotraces.app

import com.autotraces.app.util.Config
import java.io.File
import kotlin.random.Random

/**
 * 自动切号时段策略：账号分配窗口 [start_min, end_min) 内，默认运行涨粉基线；
 * 发作品/引流互点等「插入操作」在窗口内到点触发，暂停涨粉 → 独占 TikTok → 结束后恢复涨粉；
 * 距时段结束前 [AutoSwitchLocalScheduler.SWITCH_SAFE_BUFFER_MIN] 分钟进入切号安全缓冲，不再启动新高优插入。
 *
 * 扩展新插入：实现 [AutoSwitchSlotInsertion]，注册到 [AutoSwitchLocalScheduler] 的 insertions 列表。
 */
object AutoSwitchSlotWindow {
    /** 时段右边界（左闭右开）；缺省 1440。 */
    fun slotEndMin(slot: AutoSwitchPlanSlot): Int {
        val end = slot.end_min
        return if (end != null && end > 0) end else 1440
    }

    /** 当前分钟所在 slot_index；与 [AutoSwitchSlotResolution.resolveTargetSlot] 配套。 */
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

    /** 切号安全缓冲：距时段结束 [bufferMin] 分钟内不再启动新高优插入。 */
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
 * 插入操作宿主：由调度器实现，负责入队与 plan 状态。
 * 新插入类型只依赖此接口，无需改 tick 主流程。
 */
interface AutoSwitchSlotInsertionHost {
    suspend fun enqueueInsertion(op: DeviceOp, slotKey: String, kind: String)
    fun isInsertionInflight(kind: String, slotKey: String): Boolean
    fun markInsertionInflight(slotKey: String, kind: String)
    fun graduateNewAccountVideoCount(account: AutoSwitchAccount)
    /** 本时段切号链是否已完成（可触发插入）。 */
    fun isSlotReady(slotKey: String, targetRowIndex: Int): Boolean
    /** 是否处于切号前安全缓冲（不再启动新高优插入）。 */
    fun isWithinSwitchSafeBuffer(minuteOfDay: Int, slot: AutoSwitchPlanSlot): Boolean
    /** 插入入队前登录保障（简化调度下默认跳过）。 */
    suspend fun verifyInsertionAccountLoggedIn(ctx: AutoSwitchSlotTickContext, kind: String): Boolean
    /** 本地计划文件中该插入任务的状态。 */
    fun insertionState(kind: String, slotKey: String): PlanStepState? = null
}

/** 自动切号发作品：video_count 解析与条数策略（客户端与 [PublishPostSlotInsertion] 共用）。 */
internal object AutoSwitchPublishVideoCount {
    const val NEW_ACCOUNT_COOLDOWN = -1

    fun parseRaw(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0
        return text.toIntOrNull() ?: 0
    }

    fun isNewAccountCooldown(raw: String?): Boolean = parseRaw(raw) == NEW_ACCOUNT_COOLDOWN

    fun autoPublishCountFor(videoCount: Int): Int {
        val vc = videoCount.coerceAtLeast(0)
        return when {
            vc < 3 -> 1
            vc <= 9 -> 2
            else -> 3
        }
    }
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
        if (host.isWithinSwitchSafeBuffer(ctx.minuteOfDay, ctx.slot)) {
            logOnce(ctx.alias, slotKey, "switch_buffer") {
                LogCenter.op(
                    ctx.alias,
                    "【自动切号】跳过发作品：slot=$slotKey 处于切号安全缓冲（距时段结束不足 ${AutoSwitchLocalScheduler.SWITCH_SAFE_BUFFER_MIN} 分钟）",
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

        val planState = host.insertionState(kind, slotKey)
            ?: AutoSwitchLocalPlanStore.slot(slotKey)?.publish?.state
        when (planState) {
            PlanStepState.DONE, PlanStepState.SKIPPED, PlanStepState.RUNNING -> return false
            PlanStepState.FAILED -> {
                logOnce(ctx.alias, slotKey, "aborted") {
                    LogCenter.op(
                        ctx.alias,
                        "【自动切号】跳过发作品：时段 $slotKey 本时段已失败/中止（不再重试）",
                    )
                }
                return false
            }
            else -> Unit
        }
        if (host.isInsertionInflight(kind, slotKey)) return false
        val resumeAccount = ctx.account.username?.trim().orEmpty()
            .ifBlank { PrefsStore.getLogContextUsername()?.trim().orEmpty() }
        if (
            PrefsStore.isPublishPostPending() &&
            AutoSwitchLocalPlanStore.activePublishSlotKey()?.trim() == slotKey &&
            (
                PrefsStore.getPublishPostResumeNextIndex(resumeAccount.ifBlank { ctx.alias }) > 0 ||
                    PrefsStore.isPublishPostMaterialReady()
                )
        ) {
            logOnce(ctx.alias, slotKey, "resume_pending") {
                LogCenter.op(
                    ctx.alias,
                    "【自动切号】跳过发作品：slot=$slotKey 同 OP 断点续发 pending 中",
                )
            }
            return false
        }

        val targetRi = ctx.account.row_index ?: -1
        if (!host.isSlotReady(slotKey, targetRi)) {
            logOnce(ctx.alias, slotKey, "slot_not_ready") {
                LogCenter.op(
                    ctx.alias,
                    "【自动切号】跳过发作品：slot=$slotKey 本时段切号未完成（row=$targetRi）",
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

        val rawVc = AutoSwitchPublishVideoCount.parseRaw(ctx.account.video_count)
        if (rawVc == AutoSwitchPublishVideoCount.NEW_ACCOUNT_COOLDOWN) {
            host.graduateNewAccountVideoCount(ctx.account)
            AutoSwitchLedger.recordVideoCountGraduate(
                username = ctx.account.username?.trim().orEmpty(),
                rowIndex = ctx.account.row_index ?: -1,
                slotKey = slotKey,
            )
            AutoSwitchLocalPlanStore.markInsertionState(slotKey, "publish", PlanStepState.SKIPPED)
            LogCenter.op(
                ctx.alias,
                "【自动切号】跳过发作品：新号冷却 video_count=-1→0 account=${ctx.account.username}（本时段不发作品，继续涨粉基线）",
            )
            return false
        }

        val publishCount = AutoSwitchPublishVideoCount.autoPublishCountFor(rawVc)
        val targetUserForLogin = ctx.account.username?.trim().orEmpty()
        if (!host.verifyInsertionAccountLoggedIn(ctx, kind)) {
            logOnce(ctx.alias, slotKey, "login_not_ready") {
                LogCenter.op(
                    ctx.alias,
                    "【自动切号】跳过发作品：slot=$slotKey 登录保障未通过 account=$targetUserForLogin",
                )
            }
            return false
        }
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
        private val onceLogKeys = mutableSetOf<String>()

        private fun logOnce(alias: String, slotKey: String, tag: String, block: () -> Unit) {
            val key = "$alias|$slotKey|$tag"
            if (onceLogKeys.add(key)) block()
        }

        private fun newLocalOpId(prefix: String): String =
            "local-as-$prefix-${System.currentTimeMillis()}-${Random.nextInt(1000)}"
    }
}

/** 引流互点：在 auto_mutual_minute 到点触发 teamwork Videos 引流会话（读 bundle 内 mutual_traffic_* 配置）。 */
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
        when (
            host.insertionState(kind, slotKey)
                ?: AutoSwitchLocalPlanStore.slot(slotKey)?.mutual_traffic?.state
        ) {
            PlanStepState.DONE, PlanStepState.SKIPPED, PlanStepState.FAILED, PlanStepState.RUNNING -> return false
            else -> Unit
        }
        if (host.isInsertionInflight(kind, slotKey)) return false
        val targetRi = ctx.account.row_index ?: -1
        if (!host.isSlotReady(slotKey, targetRi)) return false
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
            "【自动切号】已入队引流互点（$modeLabel）slot=$slotKey 计划=${AutoSwitchSlotWindow.formatMinute(mutualMinute)} account=$targetUser",
        )
        return true
    }

    companion object {
        private fun newLocalOpId(prefix: String): String =
            "local-as-$prefix-${System.currentTimeMillis()}-${Random.nextInt(1000)}"
    }
}
