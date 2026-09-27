package com.autotraces.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 计划任务步骤状态（持久化到本地 JSON 文件）。 */
enum class PlanStepState {
    @SerializedName("pending") PENDING,
    @SerializedName("running") RUNNING,
    @SerializedName("done") DONE,
    @SerializedName("skipped") SKIPPED,
    @SerializedName("failed") FAILED,
    @SerializedName("waiting") WAITING,
}

/** 切号链三步：备份 → 清理 → 还原或自动登录。 */
enum class CutSwitchStepKind {
    BACKUP,
    CLEAR,
    RESTORE_OR_LOGIN,
}

data class CutSwitchPlanSteps(
    val backup: PlanStepState = PlanStepState.PENDING,
    val clear: PlanStepState = PlanStepState.PENDING,
    val restore_or_login: PlanStepState = PlanStepState.PENDING,
    /** bootstrap 无源账号；restore 有本机包；exclusive 无包走自动登录。 */
    val mode: String = "switch",
    val source_row_index: Int? = null,
    val source_username: String? = null,
) {
    /** 切号完成：还原/登录步终态（与云端 is_login 无关）。 */
    fun isDone(): Boolean =
        restore_or_login == PlanStepState.DONE || restore_or_login == PlanStepState.SKIPPED

    /** 备份/清理已走完，可执行还原或自动登录。 */
    fun isReadyForRestoreOrLogin(): Boolean {
        if (mode == "bootstrap") {
            return clear == PlanStepState.DONE || clear == PlanStepState.SKIPPED
        }
        return (backup == PlanStepState.DONE || backup == PlanStepState.SKIPPED) &&
            (clear == PlanStepState.DONE || clear == PlanStepState.SKIPPED)
    }

    /** 切号链前序步骤已完成，允许把 restore_or_login 标为 DONE。 */
    fun isChainReadyForCompletion(): Boolean = isReadyForRestoreOrLogin()

    fun isFailed(): Boolean =
        backup == PlanStepState.FAILED ||
            clear == PlanStepState.FAILED ||
            restore_or_login == PlanStepState.FAILED

    fun isWaitingInsertion(): Boolean =
        backup == PlanStepState.WAITING ||
            clear == PlanStepState.WAITING ||
            restore_or_login == PlanStepState.WAITING

    fun nextPendingStep(): CutSwitchStepKind? {
        if (isDone() || isFailed()) return null
        if (mode == "bootstrap") {
            if (clear != PlanStepState.DONE && clear != PlanStepState.SKIPPED) return CutSwitchStepKind.CLEAR
            if (restore_or_login != PlanStepState.DONE && restore_or_login != PlanStepState.SKIPPED) {
                return CutSwitchStepKind.RESTORE_OR_LOGIN
            }
            return null
        }
        if (backup != PlanStepState.DONE) return CutSwitchStepKind.BACKUP
        if (clear != PlanStepState.DONE && clear != PlanStepState.SKIPPED) return CutSwitchStepKind.CLEAR
        if (restore_or_login != PlanStepState.DONE && restore_or_login != PlanStepState.SKIPPED) {
            return CutSwitchStepKind.RESTORE_OR_LOGIN
        }
        return null
    }
}

data class TimedInsertionPlan(
    val trigger_at_min: Int? = null,
    val state: PlanStepState = PlanStepState.PENDING,
)

data class SlotPlanEntry(
    val slot_key: String,
    val schedule_date: String,
    val slot_index: Int,
    val start_min: Int,
    val end_min: Int,
    /** 固定时长跨天：北京墙钟绝对分钟；0 表示走自然日 start_min/end_min。 */
    val start_epoch_min: Long = 0L,
    val end_epoch_min: Long = 0L,
    val target_row_index: Int,
    val target_username: String,
    val cut_switch: CutSwitchPlanSteps = CutSwitchPlanSteps(),
    val publish: TimedInsertionPlan = TimedInsertionPlan(),
    /** 引流互点（teamwork Videos 引流会话），触发时间见 [auto_mutual_minute]。 */
    val mutual_traffic: TimedInsertionPlan = TimedInsertionPlan(),
    /** 主播访问：上班后立刻消化 bundle 预取名单，再进入涨粉基线。 */
    val live_visit: TimedInsertionPlan = TimedInsertionPlan(),
) {
    fun hasEpochBounds(): Boolean = start_epoch_min > 0L && end_epoch_min > start_epoch_min
}

/**
 * 本机账号运行时状态（备份路径、登录态、改资料标记等），与云端 bundle 按 row_index 合并。
 */
data class LocalPlanAccount(
    val row_index: Int,
    val username: String,
    val backup_path: String? = null,
    val backup_name: String? = null,
    val is_login: Boolean = false,
    val login_time: String? = null,
    val basic_profile_modified: String = "否",
    val video_count: String? = null,
)

/**
 * 自治期进行中的发作品上下文（断点续发、素材就绪等），持久化在 plan 文件。
 */
data class ActivePublishPlan(
    val slot_key: String = "",
    val op_id: String = "",
    val account_username: String = "",
    val resume_next_index: Int = 0,
    val material_ready: Boolean = false,
    val requested: Boolean = false,
    val pending: Boolean = false,
    val package_name: String = "",
    /** -1 表示未指定条数 */
    val target_count: Int = -1,
    /** 互点会话结束后排队执行发作品 */
    val queued_after_mutual: Boolean = false,
    /** 占用时钟起点（墙钟 ms）；超时强制清占用用。 */
    val started_at_ms: Long = 0L,
)

/** 自治期进行中的引流互点上下文，持久化在 plan 文件。 */
data class ActiveMutualPlan(
    val slot_key: String = "",
    val op_id: String = "",
    val account_username: String = "",
    val op_type: String = "",
    val requested: Boolean = false,
    val pending: Boolean = false,
    /** 占用时钟起点（墙钟 ms）；超时强制清占用用。 */
    val started_at_ms: Long = 0L,
)

/** 自治期进行中的主播访问上下文（剩余用户名本地消化），持久化在 plan 文件。 */
data class ActiveLiveVisitPlan(
    val slot_key: String = "",
    val op_id: String = "",
    val account_username: String = "",
    val remaining_usernames: List<String> = emptyList(),
    val requested: Boolean = false,
    val pending: Boolean = false,
    /** 占用时钟起点（墙钟 ms）；超时强制清占用用。 */
    val started_at_ms: Long = 0L,
)

/**
 * 设备端自治执行计划（唯一调度真源）。
 * 由云端 bundle 展开；切号三步、发作品/互点状态与账号运行时字段持久化，支持断点续跑。
 */
data class AutoSwitchLocalPlan(
    val revision: Int = 0,
    val session_id: String = "",
    val updated_at_ms: Long = 0L,
    val accounts: List<LocalPlanAccount> = emptyList(),
    val active_publish: ActivePublishPlan? = null,
    val active_mutual: ActiveMutualPlan? = null,
    val active_live_visit: ActiveLiveVisitPlan? = null,
    /** 切号链成功后 TikTok 应对齐的账号（plan 级「当前活跃」标记，备份归属与切号源解析真源）。 */
    val device_active_row_index: Int? = null,
    val device_active_username: String? = null,
    /** 最近一次切号完成的 slot_key（与 device_active 同步写入）。 */
    val last_completed_slot_key: String? = null,
    val slots: List<SlotPlanEntry> = emptyList(),
)

/**
 * 读写 [AutoSwitchLocalPlan] JSON 文件（filesDir/auto_switch_local_plan.json）。
 */
object AutoSwitchLocalPlanStore {
    private const val FILE_NAME = "auto_switch_local_plan.json"
    private val gson = Gson()
    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")

    @Volatile
    private var planFile: File? = null

    @Volatile
    private var cached: AutoSwitchLocalPlan? = null

    private val writeLock = Any()

    fun init(context: Context) {
        planFile = File(context.filesDir, FILE_NAME)
        synchronized(writeLock) {
            cached = loadFromDisk()
        }
    }

    fun get(): AutoSwitchLocalPlan? = synchronized(writeLock) { getInternal() }

    fun save(plan: AutoSwitchLocalPlan) = synchronized(writeLock) {
        saveInternal(sanitizeActiveContexts(plan))
    }

    fun clear() = synchronized(writeLock) {
        cached = null
        planFile?.delete()
    }

    /** 发作品 plan 上下文仍占用 TikTok（含断点续发 pending，不含仅协程活跃）。 */
    fun isPublishContextOccupying(): Boolean = synchronized(writeLock) {
        val plan = getInternal() ?: return false
        plan.active_publish?.let { ap ->
            if (ap.pending || ap.requested || ap.material_ready || ap.resume_next_index > 0 ||
                ap.queued_after_mutual
            ) {
                return true
            }
        }
        plan.slots.any { it.publish?.state == PlanStepState.RUNNING }
    }

    fun isMutualContextOccupying(): Boolean = synchronized(writeLock) {
        val plan = getInternal() ?: return false
        plan.active_mutual?.let { am ->
            if (am.pending || am.requested) return true
        }
        plan.slots.any { it.mutual_traffic?.state == PlanStepState.RUNNING }
    }

    fun isLiveVisitContextOccupying(): Boolean = synchronized(writeLock) {
        val plan = getInternal() ?: return false
        plan.active_live_visit?.let { alv ->
            if (alv.pending || alv.requested || alv.remaining_usernames.isNotEmpty()) return true
        }
        plan.slots.any { it.live_visit?.state == PlanStepState.RUNNING }
    }

    fun isPublishOccupying(isExecutionActive: Boolean): Boolean =
        isExecutionActive || isPublishContextOccupying()

    private fun getInternal(): AutoSwitchLocalPlan? = cached ?: loadFromDisk()?.also { cached = it }

    private fun saveInternal(plan: AutoSwitchLocalPlan) {
        val file = planFile ?: return
        val stamped = sanitizePlanNullables(plan).copy(updated_at_ms = System.currentTimeMillis())
        cached = stamped
        file.writeText(gson.toJson(stamped))
    }

    /**
     * Gson 反序列化旧计划 JSON 时，新增字段（如 live_visit）可能为 null，绕过 Kotlin 默认值。
     * 启动恢复若直接 .state 会 NPE 直接把进程打崩。
     */
    private fun sanitizePlanNullables(plan: AutoSwitchLocalPlan): AutoSwitchLocalPlan {
        var dirty = false
        val slots = plan.slots.map { slot ->
            val cut = slot.cut_switch
            val pub = slot.publish
            val mutual = slot.mutual_traffic
            val live = slot.live_visit
            @Suppress("SENSELESS_COMPARISON")
            val need =
                cut == null || pub == null || mutual == null || live == null
            if (!need) return@map slot
            dirty = true
            @Suppress("SENSELESS_COMPARISON")
            slot.copy(
                cut_switch = cut ?: CutSwitchPlanSteps(),
                publish = pub ?: TimedInsertionPlan(),
                mutual_traffic = mutual ?: TimedInsertionPlan(),
                live_visit = live ?: TimedInsertionPlan(),
            )
        }
        return if (dirty) plan.copy(slots = slots) else plan
    }

    private inline fun mutatePlan(block: (AutoSwitchLocalPlan) -> AutoSwitchLocalPlan) {
        synchronized(writeLock) {
            val current = getInternal() ?: return
            saveInternal(block(current))
        }
    }

    fun slot(slotKey: String): SlotPlanEntry? {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return null
        return get()?.slots?.firstOrNull { it.slot_key == sk }
    }

    fun isCutSwitchDone(slotKey: String): Boolean {
        val cs = slot(slotKey)?.cut_switch ?: return false
        if (!cs.isDone()) return false
        // 单号活跃：云端已入队一次性切号，本地排班刻意跳过切号链，视为完成。
        if (cs.mode == "single_account") return true
        // 历史假完成：三步全 SKIPPED（未经真实 backup→clear→restore 链）
        if (cs.backup == PlanStepState.SKIPPED &&
            cs.clear == PlanStepState.SKIPPED &&
            cs.restore_or_login == PlanStepState.SKIPPED
        ) {
            return false
        }
        return true
    }

    fun isSlotReady(slotKey: String): Boolean = isCutSwitchDone(slotKey)

    /** plan 文件「当前活跃账号」标记（断电恢复、切号备份归属真源）。 */
    fun getDeviceActiveIdentity(): Pair<Int, String>? {
        val plan = get() ?: return null
        val ri = plan.device_active_row_index ?: return null
        if (ri < 0) return null
        val uname = plan.device_active_username?.trim()?.removePrefix("@").orEmpty()
        if (uname.isEmpty()) return null
        return ri to uname
    }

    /** 切号备份 tar 归属：入队 OP 显式指定的源账号优先；device_active 仅作无 OP 上下文回退。 */
    fun resolveCutSwitchBackupAttribution(
        fallbackUsername: String,
        fallbackRowIndex: Int?,
    ): Pair<Int, String>? {
        val uname = fallbackUsername.trim().removePrefix("@")
        if (uname.isNotEmpty()) {
            val ri = fallbackRowIndex?.takeIf { it >= 0 }
                ?: get()?.accounts?.firstOrNull { it.username.equals(uname, ignoreCase = true) }?.row_index
            if (ri != null && ri >= 0) return ri to uname
        }
        return getDeviceActiveIdentity()
    }

    /** 禁止用 logContext/涨粉运行时刷新活跃标记；活跃标记只在切号链成功后变更。 */
    fun refreshDeviceActiveFromRuntime(runtimeUsername: String?, action: String) {
        // no-op by design
    }

    /** 从 plan 历史推导「设备当前应对齐账号」，供切号源解析与断电恢复。 */
    fun findLastCompletedSlotEntry(plan: AutoSwitchLocalPlan? = null): SlotPlanEntry? {
        val p = plan ?: get() ?: return null
        p.last_completed_slot_key?.trim()?.takeIf { it.isNotEmpty() }?.let { sk ->
            p.slots.firstOrNull { it.slot_key == sk && isCutSwitchDone(sk) }?.let { return it }
        }
        return p.slots
            .filter { isCutSwitchDone(it.slot_key) }
            .maxByOrNull { "${it.schedule_date}|${it.slot_index.toString().padStart(5, '0')}" }
    }

    fun syncDeviceActiveFromPlanHistoryIfMissing(plan: AutoSwitchLocalPlan): AutoSwitchLocalPlan {
        val ri = plan.device_active_row_index
        val uname = plan.device_active_username?.trim()?.removePrefix("@").orEmpty()
        if (ri != null && ri >= 0 && uname.isNotEmpty()) return plan
        val last = findLastCompletedSlotEntry(plan) ?: return plan
        return plan.copy(
            device_active_row_index = last.target_row_index,
            device_active_username = last.target_username.trim().removePrefix("@"),
            last_completed_slot_key = last.slot_key,
        )
    }

    /**
     * 自治进入时：device_active 缺失则仅从已完成切号历史推导，禁止用 logContext 写入。
     */
    fun seedDeviceActiveOnAutonomousEnterIfMissing(preferredUsername: String?) {
        mutatePlan { plan ->
            val existingRi = plan.device_active_row_index
            val existingUser = plan.device_active_username?.trim()?.removePrefix("@").orEmpty()
            if (existingRi != null && existingRi >= 0 && existingUser.isNotEmpty()) return@mutatePlan plan
            syncDeviceActiveFromPlanHistoryIfMissing(plan)
        }
    }

    /**
     * 切号入队前：把源账号写入当前 slot（plan 文件真源），便于断电/断网后续跑备份→清理→还原。
     */
    fun ensureSlotCutSwitchSourcePersisted(slotKey: String, previousSlotKey: String?) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        val current = slot(sk) ?: return
        val cs = current.cut_switch
        if (cs.source_row_index != null || !cs.source_username.isNullOrBlank()) return

        val prevSk = previousSlotKey?.trim()?.takeIf { it.isNotEmpty() && it != sk }
        if (prevSk != null) {
            val prev = slot(prevSk)
            if (prev != null && isCutSwitchDone(prevSk)) {
                setCutSwitchMode(sk, "switch", prev.target_row_index, prev.target_username)
                return
            }
        }

        val plan = get() ?: return
        val activeRi = plan.device_active_row_index
        val activeUser = plan.device_active_username?.trim()?.removePrefix("@").orEmpty()
        if (activeRi != null && activeRi >= 0 && activeUser.isNotEmpty()) {
            setCutSwitchMode(sk, "switch", activeRi, activeUser)
            return
        }

        findLastCompletedSlotEntry(plan)?.let { last ->
            if (last.slot_key != sk) {
                setCutSwitchMode(sk, "switch", last.target_row_index, last.target_username)
            }
        }
    }

    /** 解析切号源 row/username（只读 plan，不推断 TikTok 前台登录态）。 */
    fun resolveCutSwitchSourceIdentity(
        slotKey: String,
        previousSlotKey: String?,
    ): Pair<Int, String>? {
        ensureSlotCutSwitchSourcePersisted(slotKey, previousSlotKey)
        val entry = slot(slotKey) ?: return null
        entry.cut_switch.source_row_index?.let { ri ->
            if (ri < 0) return@let
            val fromField = entry.cut_switch.source_username?.trim()?.removePrefix("@").orEmpty()
            if (fromField.isNotEmpty()) return ri to fromField
            get()?.accounts?.firstOrNull { it.row_index == ri }?.username?.trim()?.removePrefix("@")
                ?.takeIf { it.isNotEmpty() }
                ?.let { return ri to it }
        }
        entry.cut_switch.source_username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }?.let { uname ->
            get()?.accounts?.firstOrNull { it.username.equals(uname, ignoreCase = true) }?.row_index?.let { ri ->
                if (ri >= 0) return ri to uname
            }
        }
        get()?.let { plan ->
            val ri = plan.device_active_row_index
            val uname = plan.device_active_username?.trim()?.removePrefix("@").orEmpty()
            if (ri != null && ri >= 0 && uname.isNotEmpty()) return ri to uname
        }
        val prevSk = previousSlotKey?.trim()?.takeIf { it.isNotEmpty() && it != slotKey.trim() }
        if (prevSk != null) {
            slot(prevSk)?.takeIf { isCutSwitchDone(prevSk) }?.let { prev ->
                val uname = prev.target_username.trim().removePrefix("@")
                if (uname.isNotEmpty()) return prev.target_row_index to uname
            }
        }
        findLastCompletedSlotEntry()?.let { last ->
            val uname = last.target_username.trim().removePrefix("@")
            if (uname.isNotEmpty() && last.slot_key != slotKey.trim()) {
                return last.target_row_index to uname
            }
        }
        return null
    }

    fun describeCutSwitchSourceResolutionFailure(
        slotKey: String,
        previousSlotKey: String?,
        accountCount: Int,
    ): String {
        val sk = slotKey.trim()
        val entry = slot(sk)
        val parts = mutableListOf<String>()
        if (entry == null) {
            parts.add("slot 不在 plan")
        } else {
            if (entry.cut_switch.source_row_index == null &&
                entry.cut_switch.source_username.isNullOrBlank()
            ) {
                parts.add("本 slot 未持久化 source")
            }
        }
        val plan = get()
        if (plan?.device_active_row_index == null || plan.device_active_username.isNullOrBlank()) {
            parts.add("plan 无 device_active")
        }
        val prevSk = previousSlotKey?.trim()?.orEmpty().orEmpty()
        when {
            prevSk.isEmpty() -> parts.add("无上一时段 slot_key（prefs/plan 未记录）")
            prevSk == sk -> parts.add("上一时段 slot 与当前相同（时序 bug 已修复，不应再出现）")
            !isCutSwitchDone(prevSk) -> parts.add("上一时段 $prevSk 切号未完成")
        }
        if (findLastCompletedSlotEntry() == null) {
            parts.add("plan 内无任何已完成切号 slot")
        }
        if (accountCount != 1) {
            parts.add("多账号桶($accountCount)，不能默认唯一账号为源")
        }
        return parts.joinToString("；")
    }

    private fun updateDeviceActiveAfterCutSwitch(plan: AutoSwitchLocalPlan, slotKey: String): AutoSwitchLocalPlan {
        val slot = plan.slots.firstOrNull { it.slot_key == slotKey.trim() } ?: return plan
        val uname = slot.target_username.trim().removePrefix("@")
        if (uname.isEmpty()) return plan
        return plan.copy(
            device_active_row_index = slot.target_row_index,
            device_active_username = uname,
            last_completed_slot_key = slotKey.trim(),
        )
    }

    fun logDeviceActiveCommittedAfterCutSwitch(slotKey: String, username: String) {
        val user = username.trim().removePrefix("@")
        if (user.isEmpty()) return
        val ri = get()?.device_active_row_index
        LogCenter.op(
            PrefsStore.getAlias().orEmpty().ifBlank { "auto_switch" },
            "【自动切号·计划文件】切号成功：活跃标记已写入切过来的账号=@$user" +
                (ri?.let { " row=$it" } ?: "") +
                " slot=$slotKey",
        )
    }

    /** 计划文件 cut_switch 进度摘要（供实时日志 / 涨粉门禁诊断）。 */
    fun formatCutSwitchProgressLine(slotKey: String): String? {
        val slot = slot(slotKey) ?: return null
        val account = "@${slot.target_username.trim().removePrefix("@")}"
        val cs = slot.cut_switch
        val sourceNote = cs.source_username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }
            ?.let { "源=@$it" }
            ?: cs.source_row_index?.let { "源row=$it" }
            ?: get()?.device_active_username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }
                ?.let { "活跃标记=@$it" }
            ?: "源=未持久化"
        val completed = "备份=${planStepLabel(cs.backup)}；清理=${planStepLabel(cs.clear)}；" +
            "还原/登录=${planStepLabel(cs.restore_or_login)}"
        val pending = cs.nextPendingStep()?.let(::cutSwitchStepLabel) ?: "-"
        val gate = if (cs.isDone()) "切号完成；允许涨粉" else "切号未完成；禁止涨粉"
        return "当前时段账号=$account；$sourceNote；切号已完成：$completed；切号待完成：$pending；$gate"
    }

    fun accountByRow(rowIndex: Int): LocalPlanAccount? =
        get()?.accounts?.firstOrNull { it.row_index == rowIndex }

    fun accountByUsername(username: String): LocalPlanAccount? {
        val key = username.trim().removePrefix("@")
        if (key.isEmpty()) return null
        return get()?.accounts?.firstOrNull { it.username.equals(key, ignoreCase = true) }
    }

    /** 将 plan 内账号运行时字段覆盖到云端 bundle（内存调度用）。 */
    fun applyRuntimeToBundle(bundle: AutoSwitchBundle): AutoSwitchBundle {
        val plan = get() ?: return bundle
        val byRow = plan.accounts.associateBy { it.row_index }
        if (byRow.isEmpty()) return bundle
        val accounts = bundle.accounts.orEmpty().map { acc ->
            val ri = acc.row_index ?: return@map acc
            val local = byRow[ri] ?: return@map acc
            acc.copy(
                username = local.username.ifBlank { acc.username },
                backup_path = local.backup_path?.takeIf { it.isNotEmpty() } ?: acc.backup_path,
                backup_name = local.backup_name?.takeIf { it.isNotEmpty() } ?: acc.backup_name,
                is_login = local.is_login || acc.is_login == true,
                login_time = local.login_time?.takeIf { it.isNotEmpty() } ?: acc.login_time,
                basic_profile_modified = local.basic_profile_modified.ifBlank { acc.basic_profile_modified },
                video_count = local.video_count?.takeIf { it.isNotEmpty() } ?: acc.video_count,
            )
        }
        return bundle.copy(accounts = accounts)
    }

    fun notifyAccountLoggedIn(username: String) {
        mutateAccountByUsername(username) { acc ->
            acc.copy(is_login = true, login_time = nowBeijingText())
        }
    }

    fun notifyBasicProfileModified(username: String) {
        mutateAccountByUsername(username) { acc ->
            acc.copy(basic_profile_modified = "是")
        }
    }

    fun notifyUsernameChanged(oldUsername: String, newUsername: String) {
        val oldUser = oldUsername.trim().removePrefix("@")
        val newUser = newUsername.trim().removePrefix("@")
        if (oldUser.isEmpty() || newUser.isEmpty()) return
        if (oldUser.equals(newUser, ignoreCase = true)) return
        mutatePlan { current ->
            var changed = false
            val updatedAccounts = current.accounts.map { acc ->
                if (acc.username.equals(oldUser, ignoreCase = true)) {
                    changed = true
                    acc.copy(username = newUser)
                } else {
                    acc
                }
            }
            val updatedSlots = current.slots.map { slot ->
                var slotChanged = false
                val updatedTargetUsername =
                    if (slot.target_username.equals(oldUser, ignoreCase = true)) {
                        slotChanged = true
                        newUser
                    } else {
                        slot.target_username
                    }
                val currentSourceUsername = slot.cut_switch.source_username?.trim()?.removePrefix("@").orEmpty()
                val updatedCutSwitch =
                    if (currentSourceUsername.equals(oldUser, ignoreCase = true)) {
                        slotChanged = true
                        slot.cut_switch.copy(source_username = newUser)
                    } else {
                        slot.cut_switch
                    }
                if (slotChanged) {
                    changed = true
                    slot.copy(
                        target_username = updatedTargetUsername,
                        cut_switch = updatedCutSwitch,
                    )
                } else {
                    slot
                }
            }
            val activeUsername = current.device_active_username?.trim()?.removePrefix("@").orEmpty()
            val updatedActiveUsername =
                if (activeUsername.equals(oldUser, ignoreCase = true)) {
                    changed = true
                    newUser
                } else {
                    current.device_active_username
                }
            if (!changed) return@mutatePlan current
            current.copy(
                accounts = updatedAccounts,
                slots = updatedSlots,
                device_active_username = updatedActiveUsername,
            )
        }
    }

    fun updateBackupPath(username: String, backupName: String, backupPath: String) {
        val path = backupPath.trim()
        if (path.isEmpty()) return
        mutateAccountByUsername(username) { acc ->
            acc.copy(
                backup_path = path,
                backup_name = backupName.trim().ifBlank { acc.backup_name },
            )
        }
    }

    fun graduateVideoCount(rowIndex: Int) {
        mutateAccount(rowIndex) { acc ->
            if (acc.video_count?.trim() == "-1") acc.copy(video_count = "0") else acc
        }
    }

    fun mergeFromBundle(
        bundle: AutoSwitchBundle,
        revision: Int,
        sessionId: String,
    ): AutoSwitchLocalPlan = synchronized(writeLock) {
        val previous = getInternal()
        val expanded = expandBundle(bundle, revision, sessionId)
        if (previous == null) {
            val initial = syncDeviceActiveFromPlanHistoryIfMissing(sanitizeActiveContexts(expanded))
            saveInternal(initial)
            return@synchronized initial
        }
        if (!areAccountsEquivalent(previous.accounts, expanded.accounts)) {
            val reset = sanitizeActiveContexts(abortStalePlanOnAccountChange(expanded))
            saveInternal(reset)
            return@synchronized reset
        }
        val mergedSlots = expanded.slots.map { fresh ->
            val old = previous.slots.firstOrNull { it.slot_key == fresh.slot_key }
            if (old == null) return@map fresh
            // 固定时长重锚点/重洗后，同 slot_key 可能指向不同绝对时间窗；不得继承旧 DONE。
            if (old.start_epoch_min != fresh.start_epoch_min ||
                old.end_epoch_min != fresh.end_epoch_min
            ) {
                return@map fresh
            }
            if (old.target_row_index != fresh.target_row_index ||
                !old.target_username.equals(fresh.target_username, ignoreCase = true)
            ) {
                return@map fresh
            }
            fresh.copy(
                cut_switch = mergeCutSwitch(old.cut_switch, fresh.cut_switch),
                publish = mergeInsertion(old.publish, fresh.publish),
                mutual_traffic = mergeInsertion(old.mutual_traffic, fresh.mutual_traffic),
                live_visit = mergeInsertion(old.live_visit, fresh.live_visit),
            )
        }
        val mergedAccounts = mergeAccounts(expanded.accounts, previous.accounts)
        val merged = sanitizeActiveContexts(
            expanded.copy(
                slots = mergedSlots,
                accounts = mergedAccounts,
                active_publish = previous.active_publish,
                active_mutual = previous.active_mutual,
                active_live_visit = previous.active_live_visit,
                device_active_row_index = previous.device_active_row_index,
                device_active_username = previous.device_active_username,
                last_completed_slot_key = previous.last_completed_slot_key,
            ),
        )
        val synced = syncDeviceActiveFromPlanHistoryIfMissing(merged)
        saveInternal(synced)
        synced
    }

    private fun abortStalePlanOnAccountChange(plan: AutoSwitchLocalPlan): AutoSwitchLocalPlan =
        plan.copy(
            active_publish = null,
            active_mutual = null,
            active_live_visit = null,
            device_active_row_index = null,
            device_active_username = null,
            last_completed_slot_key = null,
            slots = plan.slots.map { slot ->
                slot.copy(
                    publish = abortRunningInsertion(slot.publish),
                    mutual_traffic = abortRunningInsertion(slot.mutual_traffic),
                    live_visit = abortRunningInsertion(slot.live_visit),
                )
            },
        )

    private fun abortRunningInsertion(plan: TimedInsertionPlan): TimedInsertionPlan =
        if (plan.state == PlanStepState.RUNNING) plan.copy(state = PlanStepState.FAILED) else plan

    private fun isActivePublishConsistent(plan: AutoSwitchLocalPlan, ap: ActivePublishPlan): Boolean {
        val slot = plan.slots.firstOrNull { it.slot_key == ap.slot_key.trim() } ?: return false
        val target = slot.target_username.trim().removePrefix("@")
        val apUser = ap.account_username.trim().removePrefix("@")
        return target.equals(apUser, ignoreCase = true)
    }

    private fun isActiveMutualConsistent(plan: AutoSwitchLocalPlan, am: ActiveMutualPlan): Boolean {
        val slot = plan.slots.firstOrNull { it.slot_key == am.slot_key.trim() } ?: return false
        val target = slot.target_username.trim().removePrefix("@")
        val amUser = am.account_username.trim().removePrefix("@")
        return target.equals(amUser, ignoreCase = true)
    }

    private fun isActiveLiveVisitConsistent(plan: AutoSwitchLocalPlan, alv: ActiveLiveVisitPlan): Boolean {
        val slot = plan.slots.firstOrNull { it.slot_key == alv.slot_key.trim() } ?: return false
        val target = slot.target_username.trim().removePrefix("@")
        val alvUser = alv.account_username.trim().removePrefix("@")
        return target.equals(alvUser, ignoreCase = true)
    }

    fun sanitizeActiveContexts(plan: AutoSwitchLocalPlan): AutoSwitchLocalPlan {
        var p = plan
        p.active_publish?.let { ap ->
            if (!isActivePublishConsistent(p, ap)) p = p.copy(active_publish = null)
        }
        p.active_mutual?.let { am ->
            if (!isActiveMutualConsistent(p, am)) p = p.copy(active_mutual = null)
        }
        p.active_live_visit?.let { alv ->
            if (!isActiveLiveVisitConsistent(p, alv)) p = p.copy(active_live_visit = null)
        }
        return p
    }

    /** 跨自然日：清理进行中的发作品/引流上下文，终止昨日 RUNNING 插入。 */
    fun onScheduleDateChanged(previousDate: String) {
        val prev = previousDate.trim()
        if (prev.isEmpty()) return
        mutatePlan { plan ->
            plan.copy(
                active_publish = null,
                active_mutual = null,
                active_live_visit = null,
                slots = plan.slots.map { slot ->
                    if (slot.schedule_date != prev) return@map slot
                    slot.copy(
                        publish = abortRunningInsertion(slot.publish),
                        mutual_traffic = abortRunningInsertion(slot.mutual_traffic),
                        live_visit = abortRunningInsertion(slot.live_visit),
                    )
                },
            )
        }
    }

    /** 跨时段：若 active 上下文不属于当前 slot，清理并终止旧 slot RUNNING 插入。 */
    fun onActiveSlotChanged(currentSlotKey: String, previousSlotKey: String) {
        val cur = currentSlotKey.trim()
        val prev = previousSlotKey.trim()
        if (cur.isEmpty() || prev.isEmpty() || cur == prev) return
        mutatePlan { plan ->
            var p = plan
            p.active_publish?.let { ap ->
                if (ap.slot_key.trim() != cur) {
                    val sk = ap.slot_key.trim()
                    p = p.copy(
                        active_publish = null,
                        slots = p.slots.map { slot ->
                            if (slot.slot_key == sk && slot.publish.state == PlanStepState.RUNNING) {
                                slot.copy(publish = slot.publish.copy(state = PlanStepState.FAILED))
                            } else {
                                slot
                            }
                        },
                    )
                }
            }
            p.active_mutual?.let { am ->
                if (am.slot_key.trim() != cur) {
                    val sk = am.slot_key.trim()
                    p = p.copy(
                        active_mutual = null,
                        slots = p.slots.map { slot ->
                            if (slot.slot_key == sk && slot.mutual_traffic.state == PlanStepState.RUNNING) {
                                slot.copy(mutual_traffic = slot.mutual_traffic.copy(state = PlanStepState.FAILED))
                            } else {
                                slot
                            }
                        },
                    )
                }
            }
            p.active_live_visit?.let { alv ->
                if (alv.slot_key.trim() != cur) {
                    val sk = alv.slot_key.trim()
                    p = p.copy(
                        active_live_visit = null,
                        slots = p.slots.map { slot ->
                            if (slot.slot_key == sk && slot.live_visit.state == PlanStepState.RUNNING) {
                                slot.copy(live_visit = slot.live_visit.copy(state = PlanStepState.FAILED))
                            } else {
                                slot
                            }
                        },
                    )
                }
            }
            p
        }
    }

    private fun areAccountsEquivalent(
        left: List<LocalPlanAccount>,
        right: List<LocalPlanAccount>,
    ): Boolean = accountsFingerprint(left) == accountsFingerprint(right)

    fun resetStalePublishInflight(slotKey: String) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        mutatePlan { plan ->
            plan.copy(
                active_publish = null,
                slots = plan.slots.map { slot ->
                    if (slot.slot_key != sk) return@map slot
                    when (slot.publish.state) {
                        PlanStepState.RUNNING, PlanStepState.FAILED ->
                            slot.copy(publish = slot.publish.copy(state = PlanStepState.PENDING))
                        else -> slot
                    }
                },
            )
        }
    }

    fun getActivePublish(): ActivePublishPlan? =
        get()?.active_publish?.takeIf { it.slot_key.isNotBlank() && it.op_id.isNotBlank() }

    fun activePublishSlotKey(): String? =
        getActivePublish()?.slot_key?.trim()?.takeIf { it.isNotEmpty() }

    fun activePublishAccountUsername(): String? =
        getActivePublish()?.account_username?.trim()?.takeIf { it.isNotEmpty() }

    fun activePublishOpId(): String? =
        getActivePublish()?.op_id?.trim()?.takeIf { it.isNotEmpty() }

    fun isAutoSwitchPublishActive(): Boolean = getActivePublish() != null

    fun beginActivePublish(
        slotKey: String,
        opId: String,
        accountUsername: String,
        packageName: String,
        targetCount: Int?,
    ) {
        val sk = slotKey.trim()
        val oid = opId.trim()
        if (sk.isEmpty() || oid.isEmpty()) return
        val now = System.currentTimeMillis()
        mutatePlan { current ->
            val prev = current.active_publish
            val sameOp = prev != null && prev.op_id == oid && prev.slot_key == sk
            val ap = ActivePublishPlan(
                slot_key = sk,
                op_id = oid,
                account_username = accountUsername.trim(),
                resume_next_index = if (sameOp) prev?.resume_next_index ?: 0 else 0,
                material_ready = if (sameOp) prev?.material_ready == true else false,
                requested = true,
                pending = true,
                package_name = packageName.trim(),
                target_count = targetCount?.takeIf { it >= 0 } ?: -1,
                queued_after_mutual = if (sameOp) prev?.queued_after_mutual == true else false,
                started_at_ms = if (sameOp && (prev?.started_at_ms ?: 0L) > 0L) {
                    prev!!.started_at_ms
                } else {
                    now
                },
            )
            current.copy(active_publish = ap)
        }
    }

    /** 发作品占用起点；缺时钟时补戳一次（升级兼容），返回 0 表示当前未占用。 */
    fun ensurePublishOccupyStartedAtMs(): Long = synchronized(writeLock) {
        val plan = getInternal() ?: return 0L
        val ap = plan.active_publish
        if (ap != null) {
            if (ap.started_at_ms > 0L) return ap.started_at_ms
            val now = System.currentTimeMillis()
            saveInternal(plan.copy(active_publish = ap.copy(started_at_ms = now)))
            return now
        }
        if (plan.slots.any { it.publish?.state == PlanStepState.RUNNING }) {
            // 无 active_publish 的 RUNNING 泄漏：立刻视为到期（勿用 updated_at_ms，会随任意 plan 写入刷新）。
            return 1L
        }
        return 0L
    }

    fun ensureMutualOccupyStartedAtMs(): Long = synchronized(writeLock) {
        val plan = getInternal() ?: return 0L
        val am = plan.active_mutual
        if (am != null) {
            if (am.started_at_ms > 0L) return am.started_at_ms
            val now = System.currentTimeMillis()
            saveInternal(plan.copy(active_mutual = am.copy(started_at_ms = now)))
            return now
        }
        if (plan.slots.any { it.mutual_traffic?.state == PlanStepState.RUNNING }) {
            return 1L
        }
        return 0L
    }

    fun ensureLiveVisitOccupyStartedAtMs(): Long = synchronized(writeLock) {
        val plan = getInternal() ?: return 0L
        val alv = plan.active_live_visit
        if (alv != null) {
            if (alv.started_at_ms > 0L) return alv.started_at_ms
            val now = System.currentTimeMillis()
            saveInternal(plan.copy(active_live_visit = alv.copy(started_at_ms = now)))
            return now
        }
        if (plan.slots.any { it.live_visit?.state == PlanStepState.RUNNING }) {
            return 1L
        }
        return 0L
    }

    fun clearActivePublish() {
        mutatePlan { it.copy(active_publish = null) }
    }

    fun mutateActivePublish(block: (ActivePublishPlan) -> ActivePublishPlan) {
        mutatePlan { current ->
            val ap = current.active_publish ?: return@mutatePlan current
            current.copy(active_publish = block(ap))
        }
    }

    fun setActivePublishResumeNextIndex(nextIndex: Int) {
        mutateActivePublish { it.copy(resume_next_index = nextIndex.coerceAtLeast(0)) }
    }

    fun setActivePublishMaterialReady(ready: Boolean) {
        mutateActivePublish { it.copy(material_ready = ready) }
    }

    fun setActivePublishPendingFlags(requested: Boolean, pending: Boolean) {
        mutateActivePublish { it.copy(requested = requested, pending = pending) }
    }

    fun setActivePublishQueuedAfterMutual(queued: Boolean) {
        mutateActivePublish { it.copy(queued_after_mutual = queued) }
    }

    fun getActiveMutual(): ActiveMutualPlan? =
        get()?.active_mutual?.takeIf { it.slot_key.isNotBlank() && it.op_id.isNotBlank() }

    fun activeMutualSlotKey(): String? =
        getActiveMutual()?.slot_key?.trim()?.takeIf { it.isNotEmpty() }

    fun isAutoSwitchMutualActive(): Boolean = getActiveMutual() != null

    fun beginActiveMutual(
        slotKey: String,
        opId: String,
        accountUsername: String,
        opType: String,
    ) {
        val sk = slotKey.trim()
        val oid = opId.trim()
        if (sk.isEmpty() || oid.isEmpty()) return
        val now = System.currentTimeMillis()
        mutatePlan { current ->
            val prev = current.active_mutual
            val sameOp = prev != null && prev.op_id == oid && prev.slot_key == sk
            current.copy(
                active_mutual = ActiveMutualPlan(
                    slot_key = sk,
                    op_id = oid,
                    account_username = accountUsername.trim(),
                    op_type = opType.trim(),
                    requested = true,
                    pending = true,
                    started_at_ms = if (sameOp && (prev?.started_at_ms ?: 0L) > 0L) {
                        prev!!.started_at_ms
                    } else {
                        now
                    },
                ),
            )
        }
    }

    fun clearActiveMutual() {
        mutatePlan { it.copy(active_mutual = null) }
    }

    fun mutateActiveMutual(block: (ActiveMutualPlan) -> ActiveMutualPlan) {
        mutatePlan { current ->
            val am = current.active_mutual ?: return@mutatePlan current
            current.copy(active_mutual = block(am))
        }
    }

    fun setActiveMutualPendingFlags(requested: Boolean, pending: Boolean) {
        mutateActiveMutual { it.copy(requested = requested, pending = pending) }
    }

    fun getActiveLiveVisit(): ActiveLiveVisitPlan? =
        get()?.active_live_visit?.takeIf { it.slot_key.isNotBlank() && it.op_id.isNotBlank() }

    fun activeLiveVisitSlotKey(): String? =
        getActiveLiveVisit()?.slot_key?.trim()?.takeIf { it.isNotEmpty() }

    fun isAutoSwitchLiveVisitActive(): Boolean = getActiveLiveVisit() != null

    fun beginActiveLiveVisit(
        slotKey: String,
        opId: String,
        accountUsername: String,
        remainingUsernames: List<String>,
    ) {
        val sk = slotKey.trim()
        val oid = opId.trim()
        if (sk.isEmpty() || oid.isEmpty()) return
        val now = System.currentTimeMillis()
        mutatePlan { current ->
            val prev = current.active_live_visit
            val sameOp = prev != null && prev.op_id == oid && prev.slot_key == sk
            current.copy(
                active_live_visit = ActiveLiveVisitPlan(
                    slot_key = sk,
                    op_id = oid,
                    account_username = accountUsername.trim(),
                    remaining_usernames = remainingUsernames
                        .map { it.trim().removePrefix("@") }
                        .filter { it.isNotEmpty() },
                    requested = true,
                    pending = true,
                    started_at_ms = if (sameOp && (prev?.started_at_ms ?: 0L) > 0L) {
                        prev!!.started_at_ms
                    } else {
                        now
                    },
                ),
            )
        }
    }

    fun clearActiveLiveVisit() {
        mutatePlan { it.copy(active_live_visit = null) }
    }

    fun mutateActiveLiveVisit(block: (ActiveLiveVisitPlan) -> ActiveLiveVisitPlan) {
        mutatePlan { current ->
            val alv = current.active_live_visit ?: return@mutatePlan current
            current.copy(active_live_visit = block(alv))
        }
    }

    fun setActiveLiveVisitRemaining(usernames: List<String>) {
        mutateActiveLiveVisit {
            it.copy(
                remaining_usernames = usernames
                    .map { u -> u.trim().removePrefix("@") }
                    .filter { u -> u.isNotEmpty() },
            )
        }
    }

    fun setActiveLiveVisitPendingFlags(requested: Boolean, pending: Boolean) {
        mutateActiveLiveVisit { it.copy(requested = requested, pending = pending) }
    }

    /**
     * 进程被杀/服务重启后，本时段发作品是否仍可恢复（有 op、slot 且素材已就绪或 pending 未清）。
     */
    fun hasRecoverableActivePublish(slotKey: String): Boolean {
        val sk = slotKey.trim()
        if (sk.isBlank()) return false
        val ap = getActivePublish() ?: return false
        if (ap.slot_key.trim() != sk) return false
        if (ap.material_ready) return true
        if (ap.pending || ap.requested || ap.queued_after_mutual) return true
        return ap.resume_next_index > 0
    }

    private fun mergeAccounts(
        fresh: List<LocalPlanAccount>,
        previous: List<LocalPlanAccount>,
    ): List<LocalPlanAccount> {
        val prevByRow = previous.associateBy { it.row_index }
        return fresh.map { cloud ->
            val old = prevByRow[cloud.row_index] ?: return@map cloud
            if (!old.username.equals(cloud.username, ignoreCase = true)) return@map cloud
            val localPath = old.backup_path?.trim().orEmpty()
            val keepLocalBackup = localPath.isNotEmpty() && File(localPath).exists()
            cloud.copy(
                backup_path = if (keepLocalBackup) localPath else cloud.backup_path,
                backup_name = if (keepLocalBackup) {
                    old.backup_name?.trim().orEmpty().ifBlank { cloud.backup_name }
                } else {
                    cloud.backup_name
                },
                is_login = old.is_login || cloud.is_login,
                login_time = old.login_time?.trim().orEmpty().ifBlank { cloud.login_time },
                basic_profile_modified = if (old.basic_profile_modified == "是") {
                    "是"
                } else {
                    cloud.basic_profile_modified
                },
                video_count = mergeVideoCount(old.video_count, cloud.video_count),
            )
        }
    }

    private fun mergeVideoCount(local: String?, cloud: String?): String? {
        val localRaw = local?.trim().orEmpty()
        val cloudRaw = cloud?.trim().orEmpty()
        if (localRaw == "0" && cloudRaw == "-1") return "0"
        return localRaw.ifBlank { cloudRaw.ifBlank { null } }
    }

    private fun mergeCutSwitch(old: CutSwitchPlanSteps, fresh: CutSwitchPlanSteps): CutSwitchPlanSteps =
        fresh.copy(
            backup = preservePlanStep(old.backup, fresh.backup),
            clear = preservePlanStep(old.clear, fresh.clear),
            restore_or_login = preservePlanStep(old.restore_or_login, fresh.restore_or_login),
            source_row_index = old.source_row_index ?: fresh.source_row_index,
            source_username = old.source_username ?: fresh.source_username,
            mode = when {
                old.source_row_index != null || !old.source_username.isNullOrBlank() -> "switch"
                old.mode != "switch" -> old.mode
                else -> fresh.mode
            },
        )

    private fun preservePlanStep(old: PlanStepState, fresh: PlanStepState): PlanStepState =
        when (old) {
            PlanStepState.PENDING, PlanStepState.WAITING -> fresh
            else -> old
        }

    private fun mergeInsertion(old: TimedInsertionPlan, fresh: TimedInsertionPlan): TimedInsertionPlan =
        when (old.state) {
            PlanStepState.DONE, PlanStepState.SKIPPED, PlanStepState.FAILED ->
                fresh.copy(state = old.state)
            else -> fresh.copy(state = old.state)
        }

    fun canMarkCutSwitchDone(slotKey: String): Boolean {
        val cs = slot(slotKey)?.cut_switch ?: return false
        return cs.isChainReadyForCompletion()
    }

    /**
     * 仅在本时段切号链前序（备份、清理）已在 plan 中完成后，将还原/登录标为 DONE。
     * 不得用 terminalDone 把未执行的备份/清理强行标完成。
     */
    fun markCutSwitchDone(slotKey: String, sourceRowIndex: Int? = null, sourceUsername: String? = null): Boolean {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return false
        var marked = false
        mutateCutSwitchSlot(sk, "标记切号完成") { slot ->
            val cs = slot.cut_switch
            if (!cs.isChainReadyForCompletion()) {
                return@mutateCutSwitchSlot slot
            }
            marked = true
            slot.copy(
                cut_switch = cs.copy(
                    restore_or_login = PlanStepState.DONE,
                    source_row_index = sourceRowIndex ?: cs.source_row_index,
                    source_username = sourceUsername ?: cs.source_username,
                ),
            )
        }
        return marked
    }

    /**
     * plan 标切号完成与 ledger 记 cut_switch_chain_completed 同一事务：
     * 先写 plan，ledger 失败则回滚 plan；任一步失败则返回 false。
     * 活跃标记仅在此处（及同路径的 [updateDeviceActiveAfterCutSwitch]）写入。
     */
    fun commitCutSwitchChainCompleted(
        slotKey: String,
        username: String,
        method: String,
        sourceRowIndex: Int? = null,
        sourceUsername: String? = null,
    ): Boolean {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        val sk = slotKey.trim()
        val user = username.trim().removePrefix("@")
        if (sk.isEmpty() || user.isBlank()) return false

        synchronized(writeLock) {
            val before = getInternal() ?: return false
            val slot = before.slots.firstOrNull { it.slot_key == sk } ?: return false
            val cs = slot.cut_switch
            if (!cs.isChainReadyForCompletion()) return false

            val after = updateDeviceActiveAfterCutSwitch(
                before.copy(
                    slots = before.slots.map { entry ->
                        if (entry.slot_key != sk) entry
                        else entry.copy(
                            cut_switch = cs.copy(
                                restore_or_login = PlanStepState.DONE,
                                source_row_index = sourceRowIndex ?: cs.source_row_index,
                                source_username = sourceUsername ?: cs.source_username,
                            ),
                        )
                    },
                ),
                sk,
            )
            saveInternal(after)
            val ledgerOk = AutoSwitchLedger.tryRecordCutSwitchChainCompleted(sk, user, method)
            if (!ledgerOk) {
                saveInternal(before)
                return false
            }
            logPlanCutSwitchProgress(sk, "标记切号完成（plan+ledger 同事务）")
            logDeviceActiveCommittedAfterCutSwitch(sk, user)
            return true
        }
    }

    /**
     * 计划文件若标「切号完成」但 ledger 无 [cut_switch_chain_completed]：
     * 补记 ledger，**禁止**整段重置重跑。
     * session 重同步会清空内存 ledger，若因 ledger 缺失而 reset，会把已对齐时段打成待执行，
     * 进而触发「源=目标」无意义自切号。
     */
    fun revalidateCutSwitchCompletionsAgainstLedger(
        hasChainCompleted: (slotKey: String) -> Boolean,
    ) {
        val plan = get() ?: return
        for (slot in plan.slots) {
            if (!isCutSwitchDone(slot.slot_key)) continue
            if (hasChainCompleted(slot.slot_key)) continue
            val user = slot.target_username.trim().removePrefix("@")
            if (user.isBlank()) {
                LogCenter.op(
                    PrefsStore.getAlias().orEmpty().ifBlank { "auto_switch" },
                    "【自动切号·计划文件】切号完成 ledger 缺失且目标 username 为空，保留 plan DONE（不重置）slot=${slot.slot_key}",
                )
                continue
            }
            val healed = AutoSwitchLedger.tryRecordCutSwitchChainCompleted(
                slot.slot_key,
                user,
                "plan_done_ledger_heal",
            )
            if (healed) {
                logPlanCutSwitchProgress(
                    slot.slot_key,
                    "ledger 补记切号完成（计划已完成，禁止因 session/重同步重置）",
                )
            } else {
                LogCenter.op(
                    PrefsStore.getAlias().orEmpty().ifBlank { "auto_switch" },
                    "【自动切号·计划文件】切号完成 ledger 缺失且补记失败，保留 plan DONE（不重置重跑）slot=${slot.slot_key}",
                )
            }
        }
    }

    /**
     * 源账号与目标账号相同：设备已在目标号上，跳过备份→清理→还原，直接标切号完成并补记 ledger。
     * 禁止同账号自切（对自己 tar 备份再清理再还原）。
     *
     * 注意：若清理步已 DONE/RUNNING，TikTok 数据目录可能已被清，不得短路，须续跑还原/登录。
     */
    fun commitCutSwitchAlreadyOnTarget(
        slotKey: String,
        username: String,
        sourceRowIndex: Int? = null,
        sourceUsername: String? = null,
    ): Boolean {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return false
        val sk = slotKey.trim()
        val user = username.trim().removePrefix("@")
        if (sk.isEmpty() || user.isBlank()) return false

        synchronized(writeLock) {
            val before = getInternal() ?: return false
            val slot = before.slots.firstOrNull { it.slot_key == sk } ?: return false
            val cs = slot.cut_switch
            if (cs.clear == PlanStepState.DONE || cs.clear == PlanStepState.RUNNING) {
                return false
            }
            if (isCutSwitchDone(sk)) {
                if (!AutoSwitchLedger.hasCutSwitchChainCompleted(sk)) {
                    AutoSwitchLedger.tryRecordCutSwitchChainCompleted(sk, user, "already_on_target_heal")
                }
                return true
            }

            val after = updateDeviceActiveAfterCutSwitch(
                before.copy(
                    slots = before.slots.map { entry ->
                        if (entry.slot_key != sk) entry
                        else entry.copy(
                            cut_switch = cs.copy(
                                backup = PlanStepState.SKIPPED,
                                clear = PlanStepState.SKIPPED,
                                restore_or_login = PlanStepState.DONE,
                                mode = "switch",
                                source_row_index = sourceRowIndex ?: cs.source_row_index ?: entry.target_row_index,
                                source_username = (sourceUsername ?: cs.source_username ?: user)
                                    .trim()
                                    .removePrefix("@"),
                            ),
                        )
                    },
                ),
                sk,
            )
            saveInternal(after)
            val ledgerOk = AutoSwitchLedger.tryRecordCutSwitchChainCompleted(
                sk,
                user,
                "already_on_target",
            )
            logPlanCutSwitchProgress(
                sk,
                if (ledgerOk) {
                    "源=目标跳过切号链（plan+ledger）"
                } else {
                    "源=目标跳过切号链（plan 已完成，ledger 待补记）"
                },
            )
            logDeviceActiveCommittedAfterCutSwitch(sk, user)
            return true
        }
    }

    fun resetCutSwitch(slotKey: String, reason: String = "") {
        val action = if (reason.isBlank()) "重置切号进度" else "重置切号进度：$reason"
        mutateCutSwitchSlot(slotKey, action) { slot ->
            slot.copy(
                cut_switch = CutSwitchPlanSteps(mode = slot.cut_switch.mode),
            )
        }
    }

    /**
     * 还原/自动登录失败后：保留已完成的备份+清理，仅重置还原/登录步以便续跑（TikTok 已被清理，不得重跑备份）。
     */
    fun resetCutSwitchRestoreOrLogin(slotKey: String, reason: String = "") {
        val action = if (reason.isBlank()) {
            "重置切号还原/登录步"
        } else {
            "重置切号还原/登录步：$reason"
        }
        mutateCutSwitchSlot(slotKey, action) { slot ->
            val cs = slot.cut_switch
            slot.copy(
                cut_switch = cs.copy(
                    restore_or_login = PlanStepState.PENDING,
                ),
            )
        }
    }

    fun markCutSwitchWaiting(slotKey: String) {
        mutateCutSwitchSlot(slotKey, "切号等待高优插入") { slot ->
            val cs = slot.cut_switch
            if (cs.isDone()) return@mutateCutSwitchSlot slot
            slot.copy(
                cut_switch = cs.copy(
                    backup = if (cs.backup == PlanStepState.PENDING) PlanStepState.WAITING else cs.backup,
                    clear = if (cs.clear == PlanStepState.PENDING) PlanStepState.WAITING else cs.clear,
                    restore_or_login = if (cs.restore_or_login == PlanStepState.PENDING) {
                        PlanStepState.WAITING
                    } else {
                        cs.restore_or_login
                    },
                ),
            )
        }
    }

    fun resumeCutSwitchFromWaiting(slotKey: String) {
        mutateCutSwitchSlot(slotKey, "切号等待结束，恢复待执行") { slot ->
            fun wake(s: PlanStepState) = if (s == PlanStepState.WAITING) PlanStepState.PENDING else s
            val cs = slot.cut_switch
            slot.copy(
                cut_switch = cs.copy(
                    backup = wake(cs.backup),
                    clear = wake(cs.clear),
                    restore_or_login = wake(cs.restore_or_login),
                ),
            )
        }
    }

    fun markCutSwitchStepRunning(slotKey: String, step: CutSwitchStepKind) {
        mutateCutSwitchSlot(slotKey, "切号步骤开始：${cutSwitchStepLabel(step)}") { slot ->
            val cs = slot.cut_switch
            slot.copy(
                cut_switch = when (step) {
                    CutSwitchStepKind.BACKUP -> cs.copy(backup = PlanStepState.RUNNING)
                    CutSwitchStepKind.CLEAR -> cs.copy(clear = PlanStepState.RUNNING)
                    CutSwitchStepKind.RESTORE_OR_LOGIN -> cs.copy(restore_or_login = PlanStepState.RUNNING)
                },
            )
        }
    }

    fun markCutSwitchBackupClearDone(slotKey: String, backupSkipped: Boolean = false) {
        val detail = if (backupSkipped) "备份已跳过" else "备份已完成"
        mutateCutSwitchSlot(slotKey, "切号步骤完成：$detail + 清理") { slot ->
            val cs = slot.cut_switch
            val backupState = when {
                backupSkipped -> PlanStepState.SKIPPED
                else -> PlanStepState.DONE
            }
            slot.copy(
                cut_switch = cs.copy(
                    backup = backupState,
                    clear = PlanStepState.DONE,
                ),
            )
        }
    }

    fun markCutSwitchClearDone(slotKey: String) {
        mutateCutSwitchSlot(slotKey, "切号步骤完成：清理") { slot ->
            slot.copy(cut_switch = slot.cut_switch.copy(clear = PlanStepState.DONE))
        }
    }

    fun markCutSwitchStepFailed(slotKey: String, step: CutSwitchStepKind) {
        mutateCutSwitchSlot(slotKey, "切号步骤失败：${cutSwitchStepLabel(step)}") { slot ->
            val cs = slot.cut_switch
            slot.copy(
                cut_switch = when (step) {
                    CutSwitchStepKind.BACKUP -> cs.copy(backup = PlanStepState.FAILED)
                    CutSwitchStepKind.CLEAR -> cs.copy(clear = PlanStepState.FAILED)
                    CutSwitchStepKind.RESTORE_OR_LOGIN -> cs.copy(restore_or_login = PlanStepState.FAILED)
                },
            )
        }
    }

    /**
     * 进程重启后：放弃已过期时段内未完成的切号（跨日或当前分钟已越过 end_min），
     * 避免 LMK 杀进程后反复续跑 2026-06-29 等旧 slot。
     * 未真实完成的 backup/clear 标 SKIPPED，不得假标 DONE。
     */
    fun abandonExpiredIncompleteCutSwitches(scheduleDate: String, minuteOfDay: Int) {
        val date = scheduleDate.trim()
        if (date.isEmpty()) return
        val minute = minuteOfDay.coerceIn(0, 1439)
        val epochNow = beijingEpochMinute()
        var abandoned = 0
        mutatePlan { plan ->
            plan.copy(
                slots = plan.slots.map { slot ->
                    if (slot.cut_switch.isDone()) return@map slot
                    val expired = if (slot.hasEpochBounds()) {
                        epochNow >= slot.end_epoch_min
                    } else {
                        slot.schedule_date < date ||
                            (slot.schedule_date == date && minute >= slot.end_min)
                    }
                    if (!expired) return@map slot
                    abandoned++
                    val cs = slot.cut_switch
                    slot.copy(
                        cut_switch = cs.copy(
                            backup = abandonExpiredStep(cs.backup),
                            clear = abandonExpiredStep(cs.clear),
                            restore_or_login = PlanStepState.SKIPPED,
                        ),
                    )
                },
            )
        }
        if (abandoned > 0) {
            LogCenter.op(
                PrefsStore.getAlias().orEmpty().ifBlank { "auto_switch" },
                "【自动切号】已放弃 $abandoned 个过期未完成切号 slot（当前 $date ${minute / 60}:${"%02d".format(minute % 60)}）",
            )
        }
    }

    /** 进程重启后：将所有 RUNNING 步骤降回 PENDING，便于按本地计划续跑。 */
    fun recoverStaleRunningStepsAfterProcessDeath() {
        mutatePlan { plan ->
            fun resetRunning(state: PlanStepState): PlanStepState =
                if (state == PlanStepState.RUNNING) PlanStepState.PENDING else state
            fun TimedInsertionPlan?.resetOrDefault(): TimedInsertionPlan {
                val base = this ?: TimedInsertionPlan()
                return base.copy(state = resetRunning(base.state))
            }
            plan.copy(
                slots = plan.slots.map { slot ->
                    @Suppress("SENSELESS_COMPARISON")
                    val cs = slot.cut_switch ?: CutSwitchPlanSteps()
                    slot.copy(
                        cut_switch = cs.copy(
                            backup = resetRunning(cs.backup),
                            clear = resetRunning(cs.clear),
                            restore_or_login = resetRunning(cs.restore_or_login),
                        ),
                        publish = slot.publish.resetOrDefault(),
                        mutual_traffic = slot.mutual_traffic.resetOrDefault(),
                        live_visit = slot.live_visit.resetOrDefault(),
                    )
                },
            )
        }
    }

    /** plan 为 RUNNING 但进程内无对应 OP：重置为 PENDING 以便 tick 重试。 */
    fun resetStaleCutSwitchInflight(slotKey: String) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        fun resetRunning(s: PlanStepState) =
            if (s == PlanStepState.RUNNING) PlanStepState.PENDING else s
        mutateCutSwitchSlot(sk, "进程重启：stale RUNNING 重置为 PENDING") { slot ->
            val cs = slot.cut_switch
            if (!cs.isDone() && !cs.isFailed()) {
                slot.copy(
                    cut_switch = cs.copy(
                        backup = resetRunning(cs.backup),
                        clear = resetRunning(cs.clear),
                        restore_or_login = resetRunning(cs.restore_or_login),
                    ),
                )
            } else {
                slot
            }
        }
    }

    fun setCutSwitchMode(slotKey: String, mode: String, sourceRowIndex: Int?, sourceUsername: String?) {
        val source = sourceUsername?.trim()?.removePrefix("@").orEmpty()
        val sourceNote = when {
            source.isNotBlank() -> "源=@$source"
            sourceRowIndex != null -> "源row=$sourceRowIndex"
            else -> "无源账号"
        }
        mutateCutSwitchSlot(slotKey, "设置切号模式=$mode $sourceNote") { slot ->
            slot.copy(
                cut_switch = slot.cut_switch.copy(
                    mode = mode,
                    source_row_index = sourceRowIndex,
                    source_username = sourceUsername,
                ),
            )
        }
    }

    fun markInsertionState(slotKey: String, kind: String, state: PlanStepState) {
        mutateSlot(slotKey) { slot ->
            when (kind) {
                "publish" -> slot.copy(publish = slot.publish.copy(state = state))
                "mutual_traffic" -> slot.copy(mutual_traffic = slot.mutual_traffic.copy(state = state))
                "live_visit" -> slot.copy(live_visit = slot.live_visit.copy(state = state))
                else -> slot
            }
        }
    }

    fun insertionState(slotKey: String, kind: String): PlanStepState? {
        val slot = slot(slotKey) ?: return null
        return when (kind.trim()) {
            "publish" -> slot.publish.state
            "mutual_traffic" -> slot.mutual_traffic.state
            "live_visit" -> slot.live_visit.state
            else -> null
        }
    }

    fun isPublishDone(slotKey: String): Boolean =
        insertionState(slotKey, "publish") == PlanStepState.DONE

    fun isPublishTerminalFailure(slotKey: String): Boolean =
        insertionState(slotKey, "publish") == PlanStepState.FAILED

    fun isPublishInflight(slotKey: String): Boolean =
        insertionState(slotKey, "publish") == PlanStepState.RUNNING

    fun isCutSwitchInflight(slotKey: String): Boolean {
        val cs = slot(slotKey)?.cut_switch ?: return false
        return cs.backup == PlanStepState.RUNNING ||
            cs.clear == PlanStepState.RUNNING ||
            cs.restore_or_login == PlanStepState.RUNNING
    }

    fun resolveSlotForMinute(plan: AutoSwitchLocalPlan, scheduleDate: String, minuteOfDay: Int): SlotPlanEntry? {
        val epochCandidates = plan.slots.filter { it.hasEpochBounds() }
        if (epochCandidates.isNotEmpty()) {
            val epoch = beijingEpochMinute()
            return epochCandidates
                .filter { epoch >= it.start_epoch_min && epoch < it.end_epoch_min }
                .minByOrNull { it.slot_index }
        }
        val date = scheduleDate.trim()
        val minute = minuteOfDay.coerceIn(0, 1439)
        return plan.slots
            .filter { it.schedule_date == date && minute >= it.start_min && minute < it.end_min }
            .minByOrNull { it.slot_index }
    }

    fun beijingEpochMinute(): Long = Calendar.getInstance(beijingTz).timeInMillis / 60_000L

    private fun terminalDone(s: PlanStepState): PlanStepState =
        when (s) {
            PlanStepState.DONE, PlanStepState.SKIPPED -> s
            else -> PlanStepState.DONE
        }

    /** 过期放弃：仅保留真实 DONE，未完成步骤标 SKIPPED（禁止假标 DONE）。 */
    private fun abandonExpiredStep(s: PlanStepState): PlanStepState =
        when (s) {
            PlanStepState.DONE -> PlanStepState.DONE
            PlanStepState.SKIPPED -> PlanStepState.SKIPPED
            else -> PlanStepState.SKIPPED
        }

    private fun planStepLabel(state: PlanStepState): String = when (state) {
        PlanStepState.PENDING -> "待执行"
        PlanStepState.RUNNING -> "进行中"
        PlanStepState.DONE -> "已完成"
        PlanStepState.SKIPPED -> "已跳过"
        PlanStepState.FAILED -> "失败"
        PlanStepState.WAITING -> "等待中"
    }

    private fun cutSwitchStepLabel(step: CutSwitchStepKind): String = when (step) {
        CutSwitchStepKind.BACKUP -> "备份"
        CutSwitchStepKind.CLEAR -> "清理"
        CutSwitchStepKind.RESTORE_OR_LOGIN -> "还原/登录"
    }

    private fun logPlanCutSwitchProgress(slotKey: String, action: String) {
        val progress = formatCutSwitchProgressLine(slotKey) ?: return
        LogCenter.op(
            PrefsStore.getAlias().orEmpty().ifBlank { "auto_switch" },
            "【自动切号·计划文件】$action slot=$slotKey $progress",
        )
    }

    private inline fun mutateCutSwitchSlot(
        slotKey: String,
        action: String,
        block: (SlotPlanEntry) -> SlotPlanEntry,
    ) {
        mutateSlot(slotKey, block)
        logPlanCutSwitchProgress(slotKey, action)
    }

    private inline fun mutateSlot(slotKey: String, block: (SlotPlanEntry) -> SlotPlanEntry) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        mutatePlan { current ->
            current.copy(
                slots = current.slots.map { if (it.slot_key == sk) block(it) else it },
            )
        }
    }

    private inline fun mutateAccount(rowIndex: Int, block: (LocalPlanAccount) -> LocalPlanAccount) {
        if (rowIndex < 0) return
        mutatePlan { current ->
            var found = false
            val updated = current.accounts.map { acc ->
                if (acc.row_index == rowIndex) {
                    found = true
                    block(acc)
                } else {
                    acc
                }
            }
            if (!found) return@mutatePlan current
            current.copy(accounts = updated)
        }
    }

    private inline fun mutateAccountByUsername(username: String, block: (LocalPlanAccount) -> LocalPlanAccount) {
        val key = username.trim().removePrefix("@")
        if (key.isEmpty()) return
        mutatePlan { current ->
            var found = false
            val updated = current.accounts.map { acc ->
                if (acc.username.equals(key, ignoreCase = true)) {
                    found = true
                    block(acc)
                } else {
                    acc
                }
            }
            if (!found) return@mutatePlan current
            current.copy(accounts = updated)
        }
    }

    private fun nowBeijingText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = beijingTz
        return fmt.format(Date())
    }

    private fun loadFromDisk(): AutoSwitchLocalPlan? {
        val file = planFile ?: return null
        if (!file.isFile) return null
        return runCatching {
            val raw = gson.fromJson(file.readText(), AutoSwitchLocalPlan::class.java) ?: return@runCatching null
            val sanitized = sanitizePlanNullables(raw)
            // 旧 JSON 缺字段时写回，避免下次启动再踩 NPE
            if (sanitized !== raw) {
                file.writeText(gson.toJson(sanitized.copy(updated_at_ms = System.currentTimeMillis())))
            }
            sanitized
        }.getOrNull()
    }

    private fun expandBundle(
        bundle: AutoSwitchBundle,
        revision: Int,
        sessionId: String,
    ): AutoSwitchLocalPlan {
        val slots = mutableListOf<SlotPlanEntry>()
        if (bundle.isFixedDurationPlan()) {
            appendFixedSlots(slots, bundle)
        } else {
            val dayPlans = bundle.weekly_plan?.day_plans.orEmpty()
            if (dayPlans.isNotEmpty()) {
                for (dp in dayPlans) {
                    val date = dp.schedule_date?.trim().orEmpty()
                    if (date.isEmpty()) continue
                    appendDaySlots(slots, bundle, date, dp.daily_plan)
                }
            } else {
                val date = bundle.schedule_date?.trim().orEmpty()
                if (date.isNotEmpty()) {
                    appendDaySlots(slots, bundle, date, bundle.daily_plan)
                }
            }
        }
        return AutoSwitchLocalPlan(
            revision = revision,
            session_id = sessionId.trim(),
            accounts = expandAccountsFromBundle(bundle),
            slots = slots,
        )
    }

    private fun expandAccountsFromBundle(bundle: AutoSwitchBundle): List<LocalPlanAccount> =
        bundle.accounts.orEmpty().mapNotNull { acc ->
            val ri = acc.row_index ?: return@mapNotNull null
            val user = acc.username?.trim().orEmpty()
            if (user.isEmpty()) return@mapNotNull null
            LocalPlanAccount(
                row_index = ri,
                username = user,
                backup_path = acc.backup_path?.trim()?.takeIf { it.isNotEmpty() },
                backup_name = acc.backup_name?.trim()?.takeIf { it.isNotEmpty() },
                is_login = acc.is_login == true,
                login_time = acc.login_time?.trim()?.takeIf { it.isNotEmpty() },
                basic_profile_modified = acc.basic_profile_modified?.trim().orEmpty().ifBlank { "否" },
                video_count = acc.video_count?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

    private fun appendFixedSlots(
        out: MutableList<SlotPlanEntry>,
        bundle: AutoSwitchBundle,
    ) {
        val planSlots = bundle.fixedPlanSlots().ifEmpty {
            // 物化列表为空时，至少用公式生成当前槽，避免无 slot 无法自治。
            listOfNotNull(bundle.resolveFixedDurationSlotAtEpoch())
        }
        val accounts = bundle.accounts.orEmpty()
        for (slot in planSlots) {
            val slotIndex = slot.slot_index ?: continue
            val startEpoch = slot.start_epoch_min ?: continue
            val endEpoch = slot.end_epoch_min ?: continue
            if (startEpoch <= 0L || endEpoch <= startEpoch) continue
            val targetRi = slot.row_index ?: continue
            val account = accounts.firstOrNull { (it.row_index ?: -1) == targetRi }
            val username = account?.username?.trim()
                ?: slot.username?.trim()
                ?: continue
            val scheduleDate = slot.schedule_date?.trim().orEmpty().ifBlank {
                val cal = Calendar.getInstance(beijingTz).apply { timeInMillis = startEpoch * 60_000L }
                String.format(
                    "%04d-%02d-%02d",
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH),
                )
            }
            val start = slot.start_min ?: 0
            val end = slot.end_min ?: (start + (bundle.slot_duration_min ?: 0))
            val slotKey = "fixed|$slotIndex"
            val skipCut = bundle.skip_scheduled_cut_switch == true || bundle.single_account_active == true
            out.add(
                SlotPlanEntry(
                    slot_key = slotKey,
                    schedule_date = scheduleDate,
                    slot_index = slotIndex,
                    start_min = start,
                    end_min = end,
                    start_epoch_min = startEpoch,
                    end_epoch_min = endEpoch,
                    target_row_index = targetRi,
                    target_username = username,
                    cut_switch = if (skipCut) {
                        CutSwitchPlanSteps(
                            backup = PlanStepState.DONE,
                            clear = PlanStepState.DONE,
                            restore_or_login = PlanStepState.DONE,
                            mode = "single_account",
                        )
                    } else {
                        CutSwitchPlanSteps(mode = "switch")
                    },
                    publish = TimedInsertionPlan(
                        trigger_at_min = slot.auto_publish_minute,
                        state = if (slot.auto_publish_minute == null && slot.auto_publish_epoch_min == null) {
                            PlanStepState.SKIPPED
                        } else {
                            PlanStepState.PENDING
                        },
                    ),
                    mutual_traffic = TimedInsertionPlan(
                        trigger_at_min = slot.auto_mutual_minute,
                        state = when {
                            slot.auto_mutual_minute == null && slot.auto_mutual_epoch_min == null -> PlanStepState.SKIPPED
                            !bundle.isMutualTrafficConfiguredFor(username) -> PlanStepState.SKIPPED
                            else -> PlanStepState.PENDING
                        },
                    ),
                    live_visit = TimedInsertionPlan(
                        trigger_at_min = start,
                        state = if (bundle.liveVisitUsernamesFor(username).isEmpty()) {
                            PlanStepState.SKIPPED
                        } else {
                            PlanStepState.PENDING
                        },
                    ),
                ),
            )
        }
    }

    private fun appendDaySlots(
        out: MutableList<SlotPlanEntry>,
        bundle: AutoSwitchBundle,
        scheduleDate: String,
        dailyPlan: AutoSwitchDailyPlan?,
    ) {
        val planSlots = dailyPlan?.slots.orEmpty()
        val accounts = bundle.accounts.orEmpty()
        for (slot in planSlots) {
            val slotIndex = slot.slot_index ?: continue
            val start = slot.start_min ?: 0
            val end = slot.end_min ?: 1440
            val targetRi = slot.row_index ?: continue
            val account = accounts.firstOrNull { (it.row_index ?: -1) == targetRi }
            val username = account?.username?.trim()
                ?: slot.username?.trim()
                ?: continue
            val slotKey = "$scheduleDate|$slotIndex"
            val skipCut = bundle.skip_scheduled_cut_switch == true || bundle.single_account_active == true
            out.add(
                SlotPlanEntry(
                    slot_key = slotKey,
                    schedule_date = scheduleDate,
                    slot_index = slotIndex,
                    start_min = start,
                    end_min = end,
                    target_row_index = targetRi,
                    target_username = username,
                    cut_switch = if (skipCut) {
                        // 单号活跃：开启时云端已入队一次切号；本地用 DONE + mode 标记跳过排班切号。
                        // 不可用三步全 SKIPPED：isCutSwitchDone 会把「全 SKIPPED」当历史假完成拒绝。
                        CutSwitchPlanSteps(
                            backup = PlanStepState.DONE,
                            clear = PlanStepState.DONE,
                            restore_or_login = PlanStepState.DONE,
                            mode = "single_account",
                        )
                    } else {
                        CutSwitchPlanSteps(mode = "switch")
                    },
                    publish = TimedInsertionPlan(
                        trigger_at_min = slot.auto_publish_minute,
                        state = if (slot.auto_publish_minute == null) PlanStepState.SKIPPED else PlanStepState.PENDING,
                    ),
                    mutual_traffic = TimedInsertionPlan(
                        trigger_at_min = slot.auto_mutual_minute,
                        state = when {
                            slot.auto_mutual_minute == null -> PlanStepState.SKIPPED
                            !bundle.isMutualTrafficConfiguredFor(username) -> PlanStepState.SKIPPED
                            else -> PlanStepState.PENDING
                        },
                    ),
                    live_visit = TimedInsertionPlan(
                        trigger_at_min = start,
                        state = if (bundle.liveVisitUsernamesFor(username).isEmpty()) {
                            PlanStepState.SKIPPED
                        } else {
                            PlanStepState.PENDING
                        },
                    ),
                ),
            )
        }
    }

    fun beijingDateStr(): String {
        val cal = Calendar.getInstance(beijingTz)
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", y, m, d)
    }

    fun beijingMinuteOfDay(): Int {
        val cal = Calendar.getInstance(beijingTz)
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }
}
