package com.autotraces.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 时段内插入任务（发作品/引流）执行状态；持久化到 [INSERTION_STATES_FILE_NAME]。 */
enum class PlanStepState {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED,
}

/**
 * 自治切号本地计划（唯一运行时真源）：`filesDir/auto_switch_local_plan.json`。
 *
 * 仅维护：
 * - [environment_owner_username] 当前 TikTok 环境归属账号
 * - 切号进度：备份 / 清理 / 还原或登录 是否完成
 *
 * 切号目标 username 由调度器每分钟从 bundle 时段排班解析，不写入本文件。
 */
data class AutoSwitchLocalPlan(
    val environment_owner_username: String? = null,
    val backup_done: Boolean = false,
    val clear_done: Boolean = false,
    val restore_or_login_done: Boolean = false,
    val updated_at_ms: Long = 0L,
) {
    /** 切号链进行中（任一步已开始且尚未整链完成）。 */
    fun isCutSwitchInProgress(): Boolean {
        if (!backup_done && !clear_done && !restore_or_login_done) return false
        return !(backup_done && clear_done && restore_or_login_done)
    }

    /** 环境归属与当前时段 username 一致，且不在切号中。 */
    fun isEnvironmentAlignedWith(username: String): Boolean {
        val owner = environment_owner_username?.trim()?.removePrefix("@").orEmpty()
        val slotUser = username.trim().removePrefix("@")
        if (slotUser.isEmpty()) return false
        if (owner.isEmpty()) return false
        return owner.equals(slotUser, ignoreCase = true) && !isCutSwitchInProgress()
    }
}

/** 发作品/互点占位（PrefsStore 仍引用，与 plan 文件无关）。 */
data class ActivePublishPlanState(
    val slot_key: String = "",
    val requested: Boolean = false,
    val pending: Boolean = false,
    val queued_after_mutual: Boolean = false,
    val package_name: String = "",
    val target_count: Int = -1,
    val op_id: String = "",
    val material_ready: Boolean = false,
    val account_username: String = "",
    val resume_next_index: Int = 0,
)

data class ActiveMutualPlanState(
    val slot_key: String = "",
    val op_id: String = "",
    val requested: Boolean = false,
    val pending: Boolean = false,
)

data class InsertionStateEntry(
    val state: PlanStepState = PlanStepState.PENDING,
    val attempts: Int = 0,
)

/** 插入任务状态持久化（与 plan 文件分开，避免切号 JSON 膨胀）。 */
private data class PersistedInsertionStates(
    val session_id: String = "",
    val entries: List<PersistedInsertionEntry> = emptyList(),
)

private data class PersistedInsertionEntry(
    val slot_key: String = "",
    val kind: String = "",
    val state: String = "",
    val attempts: Int = 0,
)

object AutoSwitchLocalPlanStore {
    private const val FILE_NAME = "auto_switch_local_plan.json"
    private const val INSERTION_STATES_FILE_NAME = "auto_switch_insertion_states.json"
    private const val DEFAULT_INSERTION_MAX_ATTEMPTS = 3

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var planFile: File? = null

    @Volatile
    private var insertionStatesFile: File? = null

    @Volatile
    private var cached: AutoSwitchLocalPlan? = null

    private val writeLock = Any()

    private var activePublish: ActivePublishPlanState? = null
    private var activeMutual: ActiveMutualPlanState? = null

    /** slotKey -> kind -> 状态 + 本时段已尝试次数 */
    private val insertionStates = ConcurrentHashMap<String, ConcurrentHashMap<String, InsertionStateEntry>>()

    fun init(context: Context) {
        val filesDir = context.applicationContext.filesDir
        planFile = File(filesDir, FILE_NAME)
        insertionStatesFile = File(filesDir, INSERTION_STATES_FILE_NAME)
        synchronized(writeLock) {
            cached = loadFromDisk()
            loadInsertionStatesFromDisk()
            recoverStaleRunningInsertionStates()
        }
    }

    fun get(): AutoSwitchLocalPlan? = synchronized(writeLock) { cached ?: loadFromDisk()?.also { cached = it } }

    fun save(plan: AutoSwitchLocalPlan) = synchronized(writeLock) {
        val stamped = plan.copy(updated_at_ms = System.currentTimeMillis())
        cached = stamped
        val file = planFile ?: return
        runCatching { file.writeText(gson.toJson(stamped)) }
    }

    fun clear() = synchronized(writeLock) {
        cached = null
        planFile?.delete()
        insertionStatesFile?.delete()
        clearInsertionRuntime()
    }

    fun formatSlotKey(scheduleDate: String, slotIndex: Int): String =
        "${scheduleDate.trim()}|$slotIndex"

    fun markInsertionState(slotKey: String, kind: String, state: PlanStepState) {
        val sk = slotKey.trim()
        val k = kind.trim()
        if (sk.isEmpty() || k.isEmpty()) return
        synchronized(writeLock) {
            val prev = insertionStates.computeIfAbsent(sk) { ConcurrentHashMap() }[k]
            val attempts = when {
                state == PlanStepState.RUNNING -> (prev?.attempts ?: 0) + 1
                else -> prev?.attempts ?: 0
            }
            insertionStates[sk]?.set(k, InsertionStateEntry(state, attempts))
            persistInsertionStates()
        }
    }

    fun insertionState(slotKey: String, kind: String): PlanStepState? {
        val sk = slotKey.trim()
        val k = kind.trim()
        if (sk.isEmpty() || k.isEmpty()) return null
        return insertionStates[sk]?.get(k)?.state
    }

    fun insertionAttempts(slotKey: String, kind: String): Int {
        val sk = slotKey.trim()
        val k = kind.trim()
        if (sk.isEmpty() || k.isEmpty()) return 0
        return insertionStates[sk]?.get(k)?.attempts ?: 0
    }

    fun maxInsertionAttempts(kind: String): Int {
        val policy = com.autotraces.app.autoswitch.AutoSwitchBundleStore.get()?.retry_policy
        val fromBundle = if (kind == "publish") policy?.publish_max_attempts else null
        return (fromBundle ?: DEFAULT_INSERTION_MAX_ATTEMPTS).coerceIn(1, 10)
    }

    /**
     * 失败后的下一状态：有断点续发 → PENDING；未超重试上限 → PENDING；否则 FAILED。
     * [terminalFailure] 为 true（登录失败/换号等）时直接 FAILED。
     */
    fun resolveInsertionFailureState(
        slotKey: String,
        kind: String,
        terminalFailure: Boolean = false,
        resumable: Boolean = false,
    ): PlanStepState {
        if (terminalFailure) return PlanStepState.FAILED
        if (resumable) return PlanStepState.PENDING
        val attempts = insertionAttempts(slotKey, kind)
        return if (attempts < maxInsertionAttempts(kind)) {
            PlanStepState.PENDING
        } else {
            PlanStepState.FAILED
        }
    }

    /** 进程重启后：RUNNING 无法继续，改 PENDING 供下一 tick 重试。 */
    fun recoverStaleRunningInsertionStates() {
        var changed = false
        for ((sk, kinds) in insertionStates) {
            for ((kind, entry) in kinds) {
                if (entry.state == PlanStepState.RUNNING) {
                    kinds[kind] = entry.copy(state = PlanStepState.PENDING)
                    changed = true
                }
            }
        }
        if (changed) persistInsertionStates()
    }

    fun isPublishDone(slotKey: String): Boolean =
        insertionState(slotKey, "publish") == PlanStepState.DONE

    fun isPublishTerminalFailure(slotKey: String): Boolean =
        insertionState(slotKey, "publish") == PlanStepState.FAILED

    fun isPublishInflight(slotKey: String): Boolean =
        insertionState(slotKey, "publish") == PlanStepState.RUNNING

    fun isMutualDone(slotKey: String): Boolean =
        insertionState(slotKey, "mutual_traffic") == PlanStepState.DONE

    fun isPublishContextOccupying(): Boolean {
        activePublish?.let { ap ->
            if (ap.pending || ap.requested || ap.material_ready || ap.resume_next_index > 0 ||
                ap.queued_after_mutual
            ) {
                return true
            }
        }
        return insertionStates.values.any { it["publish"]?.state == PlanStepState.RUNNING }
    }

    fun isMutualContextOccupying(): Boolean =
        activeMutual != null || insertionStates.values.any { it["mutual_traffic"]?.state == PlanStepState.RUNNING }

    fun activePublishSlotKey(): String? = activePublish?.slot_key?.trim()?.takeIf { it.isNotEmpty() }

    fun activePublishAccountUsername(): String? =
        activePublish?.account_username?.trim()?.takeIf { it.isNotEmpty() }

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
        synchronized(writeLock) {
            val prev = activePublish
            val sameOp = prev != null && prev.op_id == oid && prev.slot_key == sk
            activePublish = ActivePublishPlanState(
                slot_key = sk,
                op_id = oid,
                account_username = accountUsername.trim(),
                package_name = packageName.trim(),
                target_count = targetCount?.takeIf { it >= 0 } ?: -1,
                resume_next_index = if (sameOp) prev?.resume_next_index ?: 0 else 0,
                material_ready = if (sameOp) prev?.material_ready == true else false,
                requested = true,
                pending = true,
            )
        }
        markInsertionState(sk, "publish", PlanStepState.RUNNING)
    }

    fun beginActiveMutual(
        slotKey: String,
        opId: String,
        accountUsername: String,
    ) {
        val sk = slotKey.trim()
        val oid = opId.trim()
        if (sk.isEmpty() || oid.isEmpty()) return
        synchronized(writeLock) {
            activeMutual = ActiveMutualPlanState(
                slot_key = sk,
                op_id = oid,
                requested = true,
                pending = true,
            )
        }
        markInsertionState(sk, "mutual_traffic", PlanStepState.RUNNING)
    }

    fun notifyPublishOutcome(slotKey: String, completed: Boolean) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        markInsertionState(
            sk,
            "publish",
            if (completed) PlanStepState.DONE else PlanStepState.FAILED,
        )
        if (completed) {
            clearActivePublish()
        }
    }

    fun notifyMutualOutcome(slotKey: String, completed: Boolean, terminalFailure: Boolean = false) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        val state = if (completed) {
            PlanStepState.DONE
        } else {
            resolveInsertionFailureState(sk, "mutual_traffic", terminalFailure = terminalFailure)
        }
        markInsertionState(sk, "mutual_traffic", state)
        clearActiveMutual()
    }

    private fun clearInsertionRuntime() {
        insertionStates.clear()
        activePublish = null
        activeMutual = null
    }

    private fun loadInsertionStatesFromDisk() {
        val file = insertionStatesFile ?: return
        if (!file.isFile) return
        val sid = PrefsStore.getAutoSwitchSessionId().orEmpty()
        runCatching {
            val doc = gson.fromJson(file.readText(), PersistedInsertionStates::class.java) ?: return
            if (sid.isNotEmpty() && doc.session_id.isNotEmpty() && doc.session_id != sid) {
                return
            }
            insertionStates.clear()
            doc.entries.forEach { e ->
                val sk = e.slot_key.trim()
                val kind = e.kind.trim()
                val state = runCatching { PlanStepState.valueOf(e.state.trim()) }.getOrNull() ?: return@forEach
                if (sk.isEmpty() || kind.isEmpty()) return@forEach
                insertionStates.computeIfAbsent(sk) { ConcurrentHashMap() }[kind] =
                    InsertionStateEntry(state, e.attempts.coerceAtLeast(0))
            }
        }
    }

    private fun persistInsertionStates() {
        val file = insertionStatesFile ?: return
        val sid = PrefsStore.getAutoSwitchSessionId().orEmpty()
        val entries = insertionStates.flatMap { (sk, kinds) ->
            kinds.map { (kind, entry) ->
                PersistedInsertionEntry(
                    slot_key = sk,
                    kind = kind,
                    state = entry.state.name,
                    attempts = entry.attempts,
                )
            }
        }
        runCatching {
            file.writeText(gson.toJson(PersistedInsertionStates(session_id = sid, entries = entries)))
        }
    }

    fun loadFromDisk(): AutoSwitchLocalPlan? {
        val file = planFile ?: return null
        if (!file.isFile) return null
        return runCatching {
            gson.fromJson(file.readText(), AutoSwitchLocalPlan::class.java)
        }.getOrNull()
    }

    fun getEnvironmentOwnerUsername(): String? =
        get()?.environment_owner_username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }

    fun refreshDeviceActiveFromRuntime(username: String?, reason: String) {
        val uname = username?.trim()?.removePrefix("@").orEmpty()
        if (uname.isEmpty()) return
        synchronized(writeLock) {
            val current = get() ?: AutoSwitchLocalPlan()
            if (current.environment_owner_username?.trim()?.removePrefix("@")
                    .orEmpty()
                    .equals(uname, ignoreCase = true)
            ) {
                return
            }
            save(
                current.copy(
                    environment_owner_username = uname,
                ),
            )
        }
        LogCenter.op(
            PrefsStore.getAlias().orEmpty().ifBlank { "auto_switch" },
            "【自动切号·计划】$reason：环境归属=@$uname",
        )
    }

    /**
     * 开始切号进度。
     * [skipBackup] 为 true 表示环境归属为空：跳过备份，清理后直接登录（不做 tar 还原）。
     */
    fun beginCutSwitch(skipBackup: Boolean) {
        val current = get() ?: AutoSwitchLocalPlan()
        save(
            current.copy(
                backup_done = skipBackup,
                clear_done = false,
                restore_or_login_done = false,
            ),
        )
    }

    fun markBackupDone() = mutateProgress { it.copy(backup_done = true) }

    fun markClearDone() = mutateProgress { it.copy(clear_done = true) }

    fun markRestoreOrLoginDone() = mutateProgress { it.copy(restore_or_login_done = true) }

    /** 切号链完成：环境归属切到 [targetUsername]，进度清零。 */
    fun finishCutSwitchSuccess(targetUsername: String) {
        synchronized(writeLock) {
            val target = targetUsername.trim().removePrefix("@")
            if (target.isEmpty()) return
            val plan = get() ?: AutoSwitchLocalPlan()
            save(
                plan.copy(
                    environment_owner_username = target,
                    backup_done = false,
                    clear_done = false,
                    restore_or_login_done = false,
                ),
            )
            LogCenter.op(
                PrefsStore.getAlias().orEmpty().ifBlank { "auto_switch" },
                "【自动切号·计划】切号完成：环境归属=@$target",
            )
        }
    }

    fun resetCutSwitchProgress(reason: String = "") {
        synchronized(writeLock) {
            val plan = get() ?: return
            save(
                plan.copy(
                    backup_done = false,
                    clear_done = false,
                    restore_or_login_done = false,
                ),
            )
            if (reason.isNotBlank()) {
                LogCenter.op(
                    PrefsStore.getAlias().orEmpty().ifBlank { "auto_switch" },
                    "【自动切号·计划】切号进度重置：$reason",
                )
            }
        }
    }

    private inline fun mutateProgress(block: (AutoSwitchLocalPlan) -> AutoSwitchLocalPlan) {
        synchronized(writeLock) {
            val plan = get() ?: return
            save(block(plan))
        }
    }

    // --- 发作品/互点占位（PrefsStore 兼容） ---

    fun isAutoSwitchMutualActive(): Boolean = activeMutual != null

    fun getActiveMutual(): ActiveMutualPlanState? = activeMutual

    fun mutateActiveMutual(block: (ActiveMutualPlanState) -> ActiveMutualPlanState) {
        activeMutual = activeMutual?.let(block)
    }

    fun clearActiveMutual() {
        activeMutual = null
    }

    fun activeMutualSlotKey(): String? = activeMutual?.slot_key?.trim()?.takeIf { it.isNotEmpty() }

    fun isAutoSwitchPublishActive(): Boolean = activePublish != null

    fun getActivePublish(): ActivePublishPlanState? = activePublish

    fun mutateActivePublish(block: (ActivePublishPlanState) -> ActivePublishPlanState) {
        activePublish = activePublish?.let(block)
    }

    fun clearActivePublish() {
        activePublish = null
    }

    fun setActivePublishQueuedAfterMutual(queued: Boolean) {
        mutateActivePublish { it.copy(queued_after_mutual = queued) }
    }

    fun activePublishOpId(): String? = activePublish?.op_id?.trim()?.takeIf { it.isNotEmpty() }

    fun setActivePublishResumeNextIndex(nextIndex: Int) {
        mutateActivePublish { it.copy(resume_next_index = nextIndex.coerceAtLeast(0)) }
    }

    fun setActivePublishMaterialReady(ready: Boolean) {
        mutateActivePublish { it.copy(material_ready = ready) }
    }

    fun setActivePublishPendingFlags(requested: Boolean, pending: Boolean) {
        mutateActivePublish { it.copy(requested = requested, pending = pending) }
    }

    /**
     * 进程重启后：从 [PrefsStore] 恢复内存中的 active_publish（plan JSON 不持久化发作品上下文）。
     */
    fun rehydratePublishContextFromPrefsIfNeeded() {
        if (activePublish != null) return
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val opId = PrefsStore.getPublishPostTaskOpId()?.trim().orEmpty()
        if (opId.isEmpty()) return
        val slotKey = PrefsStore.getAutoSwitchLastSlotKey()?.trim().orEmpty()
        if (slotKey.isEmpty()) return
        if (!PrefsStore.isPublishPostPending() && !PrefsStore.isAutoSwitchPublishTask()) return
        val account = PrefsStore.getLogContextUsername()?.trim().orEmpty()
            .ifBlank { PrefsStore.getAlias()?.trim().orEmpty() }
        val resumeIdx = if (account.isNotEmpty()) {
            PrefsStore.getPublishPostResumeNextIndex(account)
        } else {
            0
        }
        synchronized(writeLock) {
            activePublish = ActivePublishPlanState(
                slot_key = slotKey,
                op_id = opId,
                account_username = account,
                package_name = PrefsStore.getPublishPostPackageName().orEmpty(),
                target_count = PrefsStore.getPublishPostTargetCount() ?: -1,
                resume_next_index = resumeIdx,
                material_ready = PrefsStore.isPublishPostMaterialReady(),
                requested = PrefsStore.isPublishPostRequested(),
                pending = PrefsStore.isPublishPostPending(),
            )
        }
        if (insertionState(slotKey, "publish") != PlanStepState.DONE) {
            markInsertionState(slotKey, "publish", PlanStepState.RUNNING)
        }
    }

    /**
     * 本时段发作品是否仍可恢复（素材已就绪、pending 未清或已有续发断点）。
     */
    fun hasRecoverableActivePublish(slotKey: String): Boolean {
        rehydratePublishContextFromPrefsIfNeeded()
        val sk = slotKey.trim()
        if (sk.isEmpty()) return false
        val ap = activePublish ?: return false
        if (ap.slot_key.trim() != sk) return false
        if (ap.op_id.trim().isEmpty()) return false
        if (ap.material_ready) return true
        if (ap.pending || ap.requested || ap.queued_after_mutual) return true
        return ap.resume_next_index > 0
    }

    /** plan 为 RUNNING 但上下文无效：重置插入状态以便 tick 重试。 */
    fun resetStalePublishInflight(slotKey: String) {
        val sk = slotKey.trim()
        if (sk.isEmpty()) return
        clearActivePublish()
        when (insertionState(sk, "publish")) {
            PlanStepState.RUNNING, PlanStepState.FAILED ->
                markInsertionState(sk, "publish", PlanStepState.PENDING)
            else -> Unit
        }
    }
}
