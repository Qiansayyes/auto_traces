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
        if (backup != PlanStepState.DONE && backup != PlanStepState.SKIPPED) return CutSwitchStepKind.BACKUP
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
    val target_row_index: Int,
    val target_username: String,
    val cut_switch: CutSwitchPlanSteps = CutSwitchPlanSteps(),
    val publish: TimedInsertionPlan = TimedInsertionPlan(),
    /** 引流互点（teamwork Videos 引流会话），触发时间见 [auto_mutual_minute]。 */
    val mutual_traffic: TimedInsertionPlan = TimedInsertionPlan(),
)

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
)

/** 自治期进行中的引流互点上下文，持久化在 plan 文件。 */
data class ActiveMutualPlan(
    val slot_key: String = "",
    val op_id: String = "",
    val account_username: String = "",
    val op_type: String = "",
    val requested: Boolean = false,
    val pending: Boolean = false,
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
        plan.slots.any { it.publish.state == PlanStepState.RUNNING }
    }

    fun isMutualContextOccupying(): Boolean = synchronized(writeLock) {
        val plan = getInternal() ?: return false
        plan.active_mutual?.let { am ->
            if (am.pending || am.requested) return true
        }
        plan.slots.any { it.mutual_traffic.state == PlanStepState.RUNNING }
    }

    fun isPublishOccupying(isExecutionActive: Boolean): Boolean =
        isExecutionActive || isPublishContextOccupying()

    private fun getInternal(): AutoSwitchLocalPlan? = cached ?: loadFromDisk()?.also { cached = it }

    private fun saveInternal(plan: AutoSwitchLocalPlan) {
        val file = planFile ?: return
        val stamped = plan.copy(updated_at_ms = System.currentTimeMillis())
        cached = stamped
        file.writeText(gson.toJson(stamped))
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

    fun isCutSwitchDone(slotKey: String): Boolean = slot(slotKey)?.cut_switch?.isDone() == true

    fun isSlotReady(slotKey: String): Boolean = isCutSwitchDone(slotKey)

    /** 计划文件 cut_switch 进度摘要（供实时日志 / 涨粉门禁诊断）。 */
    fun formatCutSwitchProgressLine(slotKey: String): String? {
        val slot = slot(slotKey) ?: return null
        val account = "@${slot.target_username.trim().removePrefix("@")}"
        val cs = slot.cut_switch
        val completed = "备份=${planStepLabel(cs.backup)}；清理=${planStepLabel(cs.clear)}；" +
            "还原/登录=${planStepLabel(cs.restore_or_login)}"
        val pending = cs.nextPendingStep()?.let(::cutSwitchStepLabel) ?: "-"
        val gate = if (cs.isDone()) "切号完成；允许涨粉" else "切号未完成；禁止涨粉"
        return "当前时段账号=$account；切号已完成：$completed；切号待完成：$pending；$gate"
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
            val initial = sanitizeActiveContexts(expanded)
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
            if (old.target_row_index != fresh.target_row_index ||
                !old.target_username.equals(fresh.target_username, ignoreCase = true)
            ) {
                return@map fresh
            }
            fresh.copy(
                cut_switch = mergeCutSwitch(old.cut_switch, fresh.cut_switch),
                publish = mergeInsertion(old.publish, fresh.publish),
                mutual_traffic = mergeInsertion(old.mutual_traffic, fresh.mutual_traffic),
            )
        }
        val mergedAccounts = mergeAccounts(expanded.accounts, previous.accounts)
        val merged = sanitizeActiveContexts(
            expanded.copy(
                slots = mergedSlots,
                accounts = mergedAccounts,
                active_publish = previous.active_publish,
                active_mutual = previous.active_mutual,
            ),
        )
        saveInternal(merged)
        merged
    }

    private fun abortStalePlanOnAccountChange(plan: AutoSwitchLocalPlan): AutoSwitchLocalPlan =
        plan.copy(
            active_publish = null,
            active_mutual = null,
            slots = plan.slots.map { slot ->
                slot.copy(
                    publish = abortRunningInsertion(slot.publish),
                    mutual_traffic = abortRunningInsertion(slot.mutual_traffic),
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

    fun sanitizeActiveContexts(plan: AutoSwitchLocalPlan): AutoSwitchLocalPlan {
        var p = plan
        p.active_publish?.let { ap ->
            if (!isActivePublishConsistent(p, ap)) p = p.copy(active_publish = null)
        }
        p.active_mutual?.let { am ->
            if (!isActiveMutualConsistent(p, am)) p = p.copy(active_mutual = null)
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
                slots = plan.slots.map { slot ->
                    if (slot.schedule_date != prev) return@map slot
                    slot.copy(
                        publish = abortRunningInsertion(slot.publish),
                        mutual_traffic = abortRunningInsertion(slot.mutual_traffic),
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
            )
            current.copy(active_publish = ap)
        }
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
        mutatePlan { current ->
            current.copy(
                active_mutual = ActiveMutualPlan(
                    slot_key = sk,
                    op_id = oid,
                    account_username = accountUsername.trim(),
                    op_type = opType.trim(),
                    requested = true,
                    pending = true,
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
            mode = if (old.mode != "switch") old.mode else fresh.mode,
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

            val after = before.copy(
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
            )
            saveInternal(after)
            val ledgerOk = AutoSwitchLedger.tryRecordCutSwitchChainCompleted(sk, user, method)
            if (!ledgerOk) {
                saveInternal(before)
                return false
            }
            logPlanCutSwitchProgress(sk, "标记切号完成（plan+ledger 同事务）")
            return true
        }
    }

    /**
     * 计划文件若标「切号完成」但 ledger 无 [cut_switch_chain_completed]，视为无效状态，整段切号重置重跑。
     */
    fun revalidateCutSwitchCompletionsAgainstLedger(
        hasChainCompleted: (slotKey: String) -> Boolean,
    ) {
        val plan = get() ?: return
        for (slot in plan.slots) {
            if (!slot.cut_switch.isDone()) continue
            if (hasChainCompleted(slot.slot_key)) continue
            resetCutSwitch(
                slot.slot_key,
                "计划文件标切号完成但 ledger 无本时段切号链完成记录，将重跑切号",
            )
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
            slot.copy(
                cut_switch = cs.copy(
                    backup = if (backupSkipped) PlanStepState.SKIPPED else PlanStepState.DONE,
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
     */
    fun abandonExpiredIncompleteCutSwitches(scheduleDate: String, minuteOfDay: Int) {
        val date = scheduleDate.trim()
        if (date.isEmpty()) return
        val minute = minuteOfDay.coerceIn(0, 1439)
        var abandoned = 0
        val abandonedSlotKeys = mutableListOf<String>()
        mutatePlan { plan ->
            plan.copy(
                slots = plan.slots.map { slot ->
                    if (slot.cut_switch.isDone()) return@map slot
                    val expired = slot.schedule_date < date ||
                        (slot.schedule_date == date && minute >= slot.end_min)
                    if (!expired) return@map slot
                    abandoned++
                    abandonedSlotKeys.add(slot.slot_key)
                    val cs = slot.cut_switch
                    slot.copy(
                        cut_switch = cs.copy(
                            backup = terminalDone(cs.backup),
                            clear = terminalDone(cs.clear),
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
        abandonedSlotKeys.forEach { sk ->
            logPlanCutSwitchProgress(sk, "放弃过期未完成切号")
        }
    }

    /** 进程重启后：将所有 RUNNING 步骤降回 PENDING，便于按本地计划续跑。 */
    fun recoverStaleRunningStepsAfterProcessDeath() {
        mutatePlan { plan ->
            fun resetRunning(state: PlanStepState): PlanStepState =
                if (state == PlanStepState.RUNNING) PlanStepState.PENDING else state
            plan.copy(
                slots = plan.slots.map { slot ->
                    val cs = slot.cut_switch
                    slot.copy(
                        cut_switch = cs.copy(
                            backup = resetRunning(cs.backup),
                            clear = resetRunning(cs.clear),
                            restore_or_login = resetRunning(cs.restore_or_login),
                        ),
                        publish = slot.publish.copy(state = resetRunning(slot.publish.state)),
                        mutual_traffic = slot.mutual_traffic.copy(state = resetRunning(slot.mutual_traffic.state)),
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
                else -> slot
            }
        }
    }

    fun insertionState(slotKey: String, kind: String): PlanStepState? {
        val slot = slot(slotKey) ?: return null
        return when (kind.trim()) {
            "publish" -> slot.publish.state
            "mutual_traffic" -> slot.mutual_traffic.state
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
        val date = scheduleDate.trim()
        val minute = minuteOfDay.coerceIn(0, 1439)
        return plan.slots
            .filter { it.schedule_date == date && minute >= it.start_min && minute < it.end_min }
            .minByOrNull { it.slot_index }
    }

    private fun terminalDone(s: PlanStepState): PlanStepState =
        when (s) {
            PlanStepState.DONE, PlanStepState.SKIPPED -> s
            else -> PlanStepState.DONE
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
            gson.fromJson(file.readText(), AutoSwitchLocalPlan::class.java)
        }.getOrNull()
    }

    private fun expandBundle(
        bundle: AutoSwitchBundle,
        revision: Int,
        sessionId: String,
    ): AutoSwitchLocalPlan {
        val slots = mutableListOf<SlotPlanEntry>()
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
            out.add(
                SlotPlanEntry(
                    slot_key = slotKey,
                    schedule_date = scheduleDate,
                    slot_index = slotIndex,
                    start_min = start,
                    end_min = end,
                    target_row_index = targetRi,
                    target_username = username,
                    cut_switch = CutSwitchPlanSteps(mode = "switch"),
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
