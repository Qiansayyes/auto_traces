package com.autotraces.app

import com.autotraces.app.autoswitch.AutoSwitchBundleStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 自治期间 OP 账本；关会话时随 [toSummaryRequest] 一次性上报。
 * 云端主要消费 **ledger** 行写回 is_login / video_count 等；元字段仅满足接口契约。
 */
object AutoSwitchLedger {
    private val gson = Gson()
    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = beijingTz
    }

    private val reportDataKeys = setOf(
        "op_type",
        "username",
        "account_username",
        "auto_switch_slot_key",
        "publish_count",
        "publish_post_completed",
        "publish_post_success_count",
        "publish_post_resumable",
        "publish_post_resume_next_index",
        "login_time",
        "old_username",
        "new_username",
        "video_count",
        "row_index",
        "completion_method",
        "source",
        "mutual_op_type",
    )

    private val phaseRank = mapOf(
        "queued" to 0,
        "running" to 1,
        "success" to 2,
        "failed" to 2,
    )

    private var sessionId: String = ""
    private var bundleRevision: Int = 0
    private val entries = mutableListOf<LedgerEntry>()
    private val entryIndexByOpId = linkedMapOf<String, Int>()

    data class LedgerEntry(
        val at: String,
        val op_id: String,
        val op_type: String,
        val status: String,
        val phase: String,
        val message: String,
        val slot_key: String = "",
        val username: String = "",
        val report_data: Map<String, Any> = emptyMap(),
    )

    fun restoreFromPrefs() {
        val blob = PrefsStore.getAutoSwitchLedgerJson() ?: return
        runCatching {
            val type = object : TypeToken<PersistedLedger>() {}.type
            val saved: PersistedLedger = gson.fromJson(blob, type) ?: return
            sessionId = saved.session_id.orEmpty()
            bundleRevision = saved.bundle_revision ?: PrefsStore.getAutoSwitchBundleRevision()
            entries.clear()
            entryIndexByOpId.clear()
            saved.entries?.forEach { e ->
                val status = e.status.orEmpty()
                val phase = e.phase?.trim()?.lowercase(Locale.US)
                    ?.takeIf { it.isNotEmpty() }
                    ?: statusToPhase(status)
                val entry = LedgerEntry(
                    at = e.at.orEmpty(),
                    op_id = e.op_id.orEmpty(),
                    op_type = e.op_type.orEmpty(),
                    status = status,
                    phase = phase,
                    message = e.message.orEmpty(),
                    slot_key = e.slot_key.orEmpty(),
                    username = e.username.orEmpty(),
                    report_data = e.report_data.orEmpty(),
                )
                entries.add(entry)
                if (entry.op_id.isNotBlank()) {
                    entryIndexByOpId[entry.op_id] = entries.lastIndex
                }
            }
        }
    }

    fun startSession(sessionId: String, bundleRevision: Int = -1) {
        val sid = sessionId.trim()
        val rev = if (bundleRevision >= 0) bundleRevision else PrefsStore.getAutoSwitchBundleRevision()
        val prefSid = PrefsStore.getAutoSwitchSessionId().orEmpty()
        val sameSession = sid.isNotEmpty() && sid == this.sessionId.ifBlank { prefSid }
        if (prefSid.isNotBlank() && sid.isNotBlank() && sid != prefSid) {
            entries.clear()
            entryIndexByOpId.clear()
        }
        this.sessionId = sid
        this.bundleRevision = rev
        if (!sameSession) {
            entries.clear()
            entryIndexByOpId.clear()
        }
        persist()
    }

    fun hasPendingUpload(): Boolean =
        entries.isNotEmpty() || !PrefsStore.getAutoSwitchLedgerJson().isNullOrBlank()

    fun getEntryCount(): Int = entries.size

    fun clear() {
        sessionId = ""
        bundleRevision = 0
        entries.clear()
        entryIndexByOpId.clear()
        PrefsStore.clearAutoSwitchLedgerPersisted()
    }

    fun recordCutSwitchChainCompleted(slotKey: String, username: String, method: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val sk = slotKey.trim()
        val user = username.trim().removePrefix("@")
        if (sk.isEmpty() || user.isBlank()) return
        ensureSessionContext()
        if (!sessionContextMatchesPrefs()) return
        val methodKey = method.trim().ifBlank { "unknown" }
        record(
            opId = "cut-switch-chain-done-$sk",
            opType = "cut_switch_chain_completed",
            status = "success",
            message = "切号链完成 @$user（$methodKey）",
            slotKey = sk,
            username = user,
            reportData = mapOf(
                "op_type" to "cut_switch_chain_completed",
                "username" to user,
                "account_username" to user,
                "auto_switch_slot_key" to sk,
                "completion_method" to methodKey,
            ),
            phaseOverride = "success",
        )
    }

    fun recordPublishDispatch(
        opId: String,
        slotKey: String,
        username: String,
        publishCount: Int,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val oid = opId.trim()
        val sk = slotKey.trim()
        val user = username.trim()
        if (oid.isBlank() || sk.isBlank()) return
        record(
            opId = oid,
            opType = "publish_post_start",
            status = "running",
            message = "发作品开始 count=$publishCount",
            slotKey = sk,
            username = user,
            reportData = mapOf(
                "op_type" to "publish_post_start",
                "account_username" to user,
                "username" to user,
                "auto_switch_slot_key" to sk,
                "publish_count" to publishCount.coerceAtLeast(0),
                "source" to "auto_switch",
            ),
            phaseOverride = "running",
        )
    }

    fun recordPublishOutcome(
        opId: String,
        completed: Boolean,
        loginFailed: Boolean,
        accountChanged: Boolean,
        message: String,
        slotKey: String,
        username: String,
        successCount: Int = 0,
        resumable: Boolean = false,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val status = when {
            completed -> "success"
            loginFailed || accountChanged -> "failed"
            else -> "failed"
        }
        val reason = when {
            accountChanged -> "时段已切换"
            loginFailed -> "登录未成功"
            completed -> "发作品完成"
            resumable && successCount > 0 -> "发作品待续发（已成功 $successCount 条）"
            resumable -> "发作品待续发"
            else -> message
        }
        record(
            opId = "${opId.trim()}|outcome",
            opType = "publish_post_outcome",
            status = status,
            message = reason,
            slotKey = slotKey,
            username = username,
            reportData = mapOf(
                "op_type" to "publish_post_outcome",
                "account_username" to username.trim(),
                "username" to username.trim(),
                "auto_switch_slot_key" to slotKey.trim(),
                "publish_post_completed" to completed,
                "publish_post_success_count" to successCount.coerceAtLeast(0),
                "publish_post_resumable" to resumable,
                "publish_post_resume_next_index" to if (resumable) successCount.coerceAtLeast(0) else 0,
            ),
        )
    }

    fun recordMutualDispatch(
        opId: String,
        opType: String,
        slotKey: String,
        username: String,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val oid = opId.trim()
        if (oid.isBlank()) return
        record(
            opId = oid,
            opType = opType.trim().ifBlank { "mutual_click_start" },
            status = "running",
            message = "引流互点开始",
            slotKey = slotKey,
            username = username,
            reportData = mapOf(
                "op_type" to opType.trim().ifBlank { "mutual_click_start" },
                "mutual_op_type" to opType.trim(),
                "account_username" to username.trim(),
                "auto_switch_slot_key" to slotKey.trim(),
                "source" to "auto_switch",
            ),
            phaseOverride = "running",
        )
    }

    fun recordMutualOutcome(
        opId: String,
        completed: Boolean,
        slotKey: String,
        username: String,
        message: String = "",
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val oid = opId.trim()
        if (oid.isBlank()) return
        record(
            opId = "${oid}|outcome",
            opType = "mutual_traffic_outcome",
            status = if (completed) "success" else "failed",
            message = message.trim().ifBlank { if (completed) "引流完成" else "引流未完成" },
            slotKey = slotKey,
            username = username,
            reportData = mapOf(
                "op_type" to "mutual_traffic_outcome",
                "account_username" to username.trim(),
                "auto_switch_slot_key" to slotKey.trim(),
            ),
        )
    }

    fun recordAccountLoginSuccess(username: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val user = username.trim()
        if (user.isBlank()) return
        ensureSessionContext()
        val sid = sessionId.ifBlank { PrefsStore.getAutoSwitchSessionId().orEmpty() }
        record(
            opId = "login-${sid.ifBlank { "local" }}-${user.lowercase(Locale.US)}",
            opType = "account_mark_login",
            status = "success",
            message = "自动登录成功，待对账写 is_login",
            username = user,
            reportData = mapOf(
                "op_type" to "account_mark_login",
                "username" to user,
                "account_username" to user,
                "login_time" to nowBeijingText(),
            ),
            phaseOverride = "success",
        )
    }

    fun recordUsernameUpdate(oldUsername: String, newUsername: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val oldUser = oldUsername.trim()
        val newUser = newUsername.trim()
        if (oldUser.isBlank() || newUser.isBlank()) return
        if (oldUser.equals(newUser, ignoreCase = true)) return
        ensureSessionContext()
        record(
            opId = "uname-${oldUser.lowercase(Locale.US)}-${newUser.lowercase(Locale.US)}-${System.currentTimeMillis()}",
            opType = "account_username_update",
            status = "success",
            message = "用户名变更 $oldUser → $newUser",
            username = newUser,
            reportData = mapOf(
                "op_type" to "account_username_update",
                "old_username" to oldUser,
                "new_username" to newUser,
                "username" to newUser,
            ),
            phaseOverride = "success",
        )
    }

    fun recordVideoCountGraduate(username: String, rowIndex: Int, slotKey: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val user = username.trim()
        if (user.isBlank()) return
        record(
            opId = "vc-grad-$rowIndex-${System.currentTimeMillis()}",
            opType = "auto_switch_video_count_graduate",
            status = "success",
            message = "新号冷却 video_count -1→0",
            slotKey = slotKey.trim(),
            username = user,
            reportData = mapOf(
                "op_type" to "auto_switch_video_count_graduate",
                "username" to user,
                "row_index" to rowIndex,
                "video_count" to 0,
            ),
            phaseOverride = "success",
        )
    }

    fun buildStats(): Map<String, Any> {
        val success = entries.count { it.phase == "success" }
        val failed = entries.count { it.phase == "failed" }
        return mapOf(
            "total" to entries.size,
            "success" to success,
            "failed" to failed,
            "cut_switch_chain" to entries.count { it.op_type == "cut_switch_chain_completed" },
            "publish_dispatch" to entries.count { it.op_type == "publish_post_start" },
            "publish_outcome" to entries.count { it.op_type == "publish_post_outcome" },
            "mutual_outcome" to entries.count { it.op_type == "mutual_traffic_outcome" },
        )
    }

    fun buildOneLineSummary(alias: String): String {
        val stats = buildStats()
        return "【自动切号会话总结】$alias 合计 ${stats["total"]} 项，成功 ${stats["success"]}，失败 ${stats["failed"]}"
    }

    fun toSummaryRequest(alias: String, deviceId: String): AutoSwitchSessionSummaryRequest {
        ensureSessionContext()
        val sid = sessionId.ifBlank { PrefsStore.getAutoSwitchSessionId().orEmpty() }
        return AutoSwitchSessionSummaryRequest(
            device_id = deviceId,
            alias = alias,
            session_id = sid,
            bundle_revision = bundleRevision.coerceAtLeast(PrefsStore.getAutoSwitchBundleRevision()),
            schedule_date = PrefsStore.getAutoSwitchScheduleDate().orEmpty(),
            started_at = "",
            ended_at = "",
            ledger = entries.map {
                AutoSwitchLedgerEntryPayload(
                    at = it.at,
                    op_id = it.op_id,
                    op_type = it.op_type,
                    status = it.status,
                    phase = it.phase,
                    message = it.message,
                    slot_key = it.slot_key,
                    username = it.username,
                    report_data = it.report_data,
                )
            },
            human_log = buildHumanLog(alias),
            stats = buildStats(),
            summary_kind = "final",
            reconcile_key = "final",
            smart_template_debug = smartTemplateDebugPayload(),
        )
    }

    private fun buildHumanLog(alias: String): String {
        val stats = buildStats()
        val lines = mutableListOf<String>()
        lines.add("【自动切号】设备 ${alias.trim()} session=$sessionId")
        lines.add(
            "操作 ${stats["total"]} 项：成功 ${stats["success"]}，失败 ${stats["failed"]}",
        )
        lines.add("")
        if (entries.isEmpty()) {
            lines.add("（无 OP 记录）")
        } else {
            entries.forEachIndexed { idx, e ->
                val account = e.username.takeIf { it.isNotEmpty() }?.let { " @$it" }.orEmpty()
                val slot = e.slot_key.takeIf { it.isNotEmpty() }?.let { " [$it]" }.orEmpty()
                lines.add("${idx + 1}. [${e.at}] ${e.op_type}$account$slot — ${e.phase}：${e.message}")
            }
        }
        return lines.joinToString("\n")
    }

    private fun record(
        opId: String,
        opType: String,
        status: String,
        message: String,
        slotKey: String = "",
        username: String = "",
        reportData: Map<String, Any> = emptyMap(),
        phaseOverride: String? = null,
    ) {
        if (sessionId.isBlank() && PrefsStore.getAutoSwitchSessionId().isNullOrBlank()) return
        ensureSessionContext()
        if (!sessionContextMatchesPrefs()) return
        val oid = opId.trim()
        if (oid.isBlank()) return
        val normalizedStatus = status.trim().lowercase(Locale.US)
        val newPhase = phaseOverride?.trim()?.lowercase(Locale.US)
            ?: statusToPhase(normalizedStatus)
        val existingIdx = entryIndexByOpId[oid]
        if (existingIdx != null) {
            val existing = entries[existingIdx]
            if (!canTransitionPhase(existing.phase, newPhase)) return
            entries[existingIdx] = existing.copy(
                at = nowBeijingText(),
                op_type = opType.trim().ifBlank { existing.op_type },
                status = normalizedStatus.ifBlank { existing.status },
                phase = newPhase,
                message = message.trim().take(2000).ifBlank { existing.message },
                slot_key = slotKey.trim().ifBlank { existing.slot_key },
                username = username.trim().ifBlank { existing.username },
                report_data = if (reportData.isNotEmpty()) reportData else existing.report_data,
            )
            persist()
            return
        }
        entries.add(
            LedgerEntry(
                at = nowBeijingText(),
                op_id = oid,
                op_type = opType.trim(),
                status = normalizedStatus,
                phase = newPhase,
                message = message.trim().take(2000),
                slot_key = slotKey.trim(),
                username = username.trim(),
                report_data = reportData,
            ),
        )
        entryIndexByOpId[oid] = entries.lastIndex
        persist()
    }

    private fun smartTemplateDebugPayload(): Map<String, Any> {
        val dbg = AutoSwitchBundleStore.get()?.smart_template_debug ?: return emptyMap()
        return runCatching {
            val json = gson.toJson(dbg)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            gson.fromJson<Map<String, Any>>(json, type) ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    private fun sessionContextMatchesPrefs(): Boolean {
        val prefSid = PrefsStore.getAutoSwitchSessionId().orEmpty()
        if (prefSid.isNotBlank() && sessionId.isNotBlank() && sessionId != prefSid) return false
        return true
    }

    private fun ensureSessionContext() {
        if (sessionId.isBlank()) sessionId = PrefsStore.getAutoSwitchSessionId().orEmpty()
        if (bundleRevision <= 0) bundleRevision = PrefsStore.getAutoSwitchBundleRevision()
    }

    private fun statusToPhase(status: String): String = when (status.trim().lowercase(Locale.US)) {
        "queued" -> "queued"
        "running" -> "running"
        "success" -> "success"
        "failed" -> "failed"
        else -> "running"
    }

    private fun canTransitionPhase(fromPhase: String, toPhase: String): Boolean {
        val from = fromPhase.trim().lowercase(Locale.US)
        val to = toPhase.trim().lowercase(Locale.US)
        if (from == to) return true
        if ((from == "success" || from == "failed") && (to == "success" || to == "failed") && from != to) {
            return false
        }
        val fromRank = phaseRank[from] ?: 0
        val toRank = phaseRank[to] ?: 0
        return toRank >= fromRank
    }

    private fun persist() {
        val doc = PersistedLedger(
            session_id = sessionId,
            bundle_revision = bundleRevision,
            entries = entries.map {
                PersistedLedgerEntry(
                    at = it.at,
                    op_id = it.op_id,
                    op_type = it.op_type,
                    status = it.status,
                    phase = it.phase,
                    message = it.message,
                    slot_key = it.slot_key,
                    username = it.username,
                    report_data = it.report_data,
                )
            },
        )
        PrefsStore.setAutoSwitchLedgerJson(gson.toJson(doc))
    }

    private fun nowBeijingText(): String = tsFormat.format(Date())

    private data class PersistedLedger(
        val session_id: String? = null,
        val bundle_revision: Int? = null,
        val entries: List<PersistedLedgerEntry>? = null,
    )

    private data class PersistedLedgerEntry(
        val at: String? = null,
        val op_id: String? = null,
        val op_type: String? = null,
        val status: String? = null,
        val phase: String? = null,
        val message: String? = null,
        val slot_key: String? = null,
        val username: String? = null,
        val report_data: Map<String, Any>? = null,
    )
}
