package com.autotraces.app

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 自动切号自治期间本地 OP 账本；关闭会话时一次性上报云端。
 * 以 session_id + bundle_revision + slot_key 作为自治主键维度；op_id 为账本行幂等键。
 */
object AutoSwitchLedger {
    private val gson = Gson()
    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = beijingTz
    }

    /** 写入 ledger 的 report_data 白名单（与云端 _AUTO_SWITCH_LEDGER_REPORT_KEYS 对齐）。 */
    private val reportDataKeys = setOf(
        "op_type",
        "backup_skipped",
        "backup_record_id",
        "username",
        "mobile_alias",
        "assigned_alias",
        "backup_path",
        "backup_name",
        "previous_backup_path",
        "old_backup_path",
        "video_count",
        "follower_count",
        "new_follower_count",
        "cut_switch_after_backup",
        "cut_switch_target_row_index",
        "restore_highlight_row_index",
        "skip_pm_clear",
        "source",
        "auto_switch_slot_key",
        "account_username",
        "profile_edit_assigned_username",
        "account_row_index",
        "profile_edit_trigger",
        "profile_edit_success",
        "basic_profile_modified_before",
        "pending_profile_edit_usernames",
        "skip_account_db_update",
        "publish_count",
        "row_index",
        "publish_post_completed",
        "publish_post_success_count",
        "publish_post_resumable",
        "publish_post_resume_next_index",
        "login_time",
        "old_username",
        "new_username",
        "failure_business",
        "failure_error_type",
        "failure_summary",
        "failure_ocr_related",
        "active_template",
        "segment_started_at",
        "segment_ended_at",
        "segment_reason",
        "run_mode",
        "template_config_json",
        "action_counts_json",
    )

    /** 单向流转：queued -> running -> success/failed；终态不可再改为另一终态。 */
    private val phaseRank = mapOf(
        "queued" to 0,
        "running" to 1,
        "success" to 2,
        "failed" to 2,
    )

    private var sessionId: String = ""
    private var bundleRevision: Int = 0
    private var scheduleDate: String = ""
    private var startedAt: String = ""
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

    fun startSession(sessionId: String, scheduleDate: String = "", bundleRevision: Int = -1) {
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
        this.scheduleDate = scheduleDate.trim().ifBlank { PrefsStore.getAutoSwitchScheduleDate().orEmpty() }
        if (!sameSession) {
            entries.clear()
            entryIndexByOpId.clear()
            AutoSwitchAccountActivityTracker.reset()
        }
        startedAt = PrefsStore.getAutoSwitchSessionStartedAt().orEmpty().ifBlank { nowBeijingText() }
        persist()
    }

    fun hasPendingUpload(): Boolean =
        entries.isNotEmpty() || !PrefsStore.getAutoSwitchLedgerJson().isNullOrBlank()

    fun getSessionId(): String = sessionId

    fun getBundleRevision(): Int = bundleRevision

    fun getScheduleDate(): String = scheduleDate

    fun getEntryCount(): Int = entries.size

    fun isOpTerminal(opId: String): Boolean {
        val idx = entryIndexByOpId[opId.trim()] ?: return false
        val phase = entries.getOrNull(idx)?.phase.orEmpty()
        return phase == "success" || phase == "failed"
    }

    fun getPublishSlotPhase(slotKey: String): String? {
        val sk = slotKey.trim()
        if (sk.isBlank()) return null
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (e.slot_key != sk) continue
            when (e.op_type) {
                "publish_post_outcome" -> return e.phase
                "publish_post_start" -> {
                    if (e.phase == "running" || e.phase == "queued") return e.phase
                    if (e.phase == "success" || e.phase == "failed") return e.phase
                }
            }
        }
        return null
    }

    fun isPublishSlotDone(slotKey: String): Boolean {
        val sk = slotKey.trim()
        if (sk.isBlank()) return false
        return entries.any {
            it.slot_key == sk &&
                it.op_type == "publish_post_outcome" &&
                it.phase == "success"
        }
    }

    fun isPublishSlotAborted(slotKey: String): Boolean {
        val sk = slotKey.trim()
        if (sk.isBlank()) return false
        return entries.any {
            it.slot_key == sk &&
                it.op_type == "publish_post_outcome" &&
                it.phase == "failed" &&
                !isPublishOutcomeResumable(it) &&
                publishOutcomeMessageBlocksSlotRetry(it.message)
        }
    }

    private fun isPublishOutcomeResumable(entry: LedgerEntry): Boolean {
        val data = entry.report_data
        if (data.isEmpty()) return false
        if (data["publish_post_resumable"] == true) return true
        val resumeRaw = data["publish_post_resume_next_index"]
        return when (resumeRaw) {
            is Number -> resumeRaw.toInt() > 0
            is String -> resumeRaw.trim().toIntOrNull()?.let { it > 0 } == true
            else -> false
        }
    }

    /** 失败 outcome 是否表示本时段不应再调度发作品（不含 stale 清理类消息，清理后允许重试）。 */
    private fun publishOutcomeMessageBlocksSlotRetry(message: String): Boolean {
        val m = message.trim()
        if (m.isEmpty()) return false
        if (m.contains("清理残留")) return false
        if (m.contains("登录") || m.contains("时段已切换")) return true
        if (m.contains("素材") || m.contains("no_video") || m.contains("未完成")) return true
        return false
    }

    fun isPublishSlotInflight(slotKey: String): Boolean {
        val sk = slotKey.trim()
        if (sk.isBlank()) return false
        if (isPublishSlotDone(slotKey) || isPublishSlotAborted(slotKey)) return false
        val phase = getPublishSlotPhase(slotKey) ?: return false
        if (phase == "failed") return false
        if (phase == "success" && !PrefsStore.isAutoSwitchPublishTask()) return false
        return phase == "running" || phase == "queued" || phase == "success"
    }

    private val cutSwitchOpTypes = setOf("tiktok_backup_and_clear", "tiktok_clear", "tiktok_restore")
    private const val SLOT_VERIFIED_OP_TYPE = "auto_switch_slot_verified"

    fun isCutSwitchSlotInflight(slotKey: String): Boolean {
        val sk = slotKey.trim()
        if (sk.isBlank()) return false
        return entries.any {
            it.slot_key == sk &&
                it.op_type in cutSwitchOpTypes &&
                (it.phase == "queued" || it.phase == "running")
        }
    }

    fun isCutSwitchSlotVerified(slotKey: String, targetRowIndex: Int): Boolean {
        val sk = slotKey.trim()
        if (sk.isBlank() || targetRowIndex < 0) return false
        return resolveCutSwitchVerifiedRowIndex(sk) == targetRowIndex
    }

    fun recordCutSwitchSlotAborted(slotKey: String, message: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        record(
            opId = "local-as-cut-abort-${System.currentTimeMillis()}-${sk.hashCode()}",
            opType = "auto_switch_cut_switch_abort",
            status = "failed",
            message = message.trim().ifBlank { "切号 slot 中止" },
            slotKey = sk,
            reportData = mapOf(
                "op_type" to "auto_switch_cut_switch_abort",
                "auto_switch_slot_key" to sk,
            ),
            phaseOverride = "failed",
        )
    }

    private fun resolveCutSwitchVerifiedRowIndex(slotKey: String): Int? {
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (e.slot_key != slotKey) continue
            if (
                e.op_type !in cutSwitchOpTypes &&
                    e.op_type != SLOT_VERIFIED_OP_TYPE &&
                    e.op_type != "auto_switch_cut_switch_abort"
            ) continue
            if (e.op_type == "auto_switch_cut_switch_abort") return null
            when (e.phase) {
                "failed" -> return null
                "cancelled" -> continue
                "queued", "running" -> return null
                "success" -> {
                    when (e.op_type) {
                        SLOT_VERIFIED_OP_TYPE -> {
                            return parseLedgerRowIndex(e.report_data["cut_switch_target_row_index"])
                        }
                        "tiktok_restore" -> {
                            return parseLedgerRowIndex(
                                e.report_data["restore_highlight_row_index"]
                                    ?: e.report_data["cut_switch_target_row_index"],
                            )
                        }
                        "tiktok_clear", "tiktok_backup_and_clear" -> {
                            val mode = e.report_data["cut_switch_after_backup"]?.toString()?.trim()?.lowercase()
                            if (mode == "exclusive_activate_target") {
                                return parseLedgerRowIndex(e.report_data["cut_switch_target_row_index"])
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun parseLedgerRowIndex(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull()
        else -> null
    }

    /** 本机已无发作品执行上下文时，将 ledger 标为失败以便本时段可重新调度。 */
    fun abortStalePublishSlot(slotKey: String, message: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        if (isPublishSlotDone(sk) || isPublishSlotAborted(sk)) return
        if (!isPublishSlotInflight(sk)) return
        var opId = PrefsStore.getPublishPostTaskOpId().orEmpty()
        var username = PrefsStore.getAutoSwitchPublishAccountUsername().orEmpty()
        if (opId.isBlank()) {
            for (i in entries.indices.reversed()) {
                val e = entries[i]
                if (e.slot_key == sk && e.op_type == "publish_post_start") {
                    opId = e.op_id
                    if (username.isBlank()) username = e.username
                    break
                }
            }
        }
        if (opId.isBlank()) {
            opId = "stale-publish-${System.currentTimeMillis()}"
        }
        recordPublishOutcome(
            opId = opId,
            completed = false,
            loginFailed = false,
            accountChanged = false,
            message = message,
            slotKey = sk,
            username = username,
        )
    }

    fun recordOpFailure(op: DeviceOp, message: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val payload = op.payload
        record(
            opId = op.op_id,
            opType = op.op_type,
            status = "failed",
            message = message,
            slotKey = payload?.auto_switch_slot_key.orEmpty(),
            username = payload?.account_username?.trim()
                ?: payload?.username?.trim().orEmpty(),
            reportData = buildReportDataFromOp(op.op_type, payload),
        )
    }

    fun recordFromDeviceOpReport(
        opId: String,
        status: String,
        message: String,
        data: Map<String, Any>?,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val opType = data?.get("op_type")?.toString().orEmpty()
        if (opType.isBlank()) return
        record(
            opId = opId,
            opType = opType,
            status = status,
            message = message,
            slotKey = data?.get("auto_switch_slot_key")?.toString().orEmpty(),
            username = data?.get("account_username")?.toString()
                ?: data?.get("username")?.toString().orEmpty(),
            reportData = sanitizeReportData(data),
        )
    }

    /** 切到该账号且「基本资料已修改」=否 时记入 ledger（每账号每 session 一条），供关会话对账。 */
    fun recordProfileEditRequired(username: String, basicProfileModifiedBefore: String = "否") {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val user = username.trim()
        if (user.isBlank()) return
        if (basicProfileModifiedBefore.trim().equals("是", ignoreCase = true)) return
        ensureSessionContext()
        val sid = sessionId.ifBlank { PrefsStore.getAutoSwitchSessionId().orEmpty() }
        val requiredOpId = "profile-required-${sid.ifBlank { "local" }}-${user.lowercase(Locale.US)}"
        if (entryIndexByOpId.containsKey(requiredOpId)) return
        record(
            opId = requiredOpId,
            opType = "profile_edit_required",
            status = "success",
            message = "切到账号且待改基本资料：$user",
            username = user,
            reportData = mapOf(
                "op_type" to "profile_edit_required",
                "username" to user,
                "basic_profile_modified_before" to basicProfileModifiedBefore.trim().ifBlank { "否" },
            ),
            phaseOverride = "success",
        )
        appendProfileEditRequiredToBaseline(sid, user)
    }

    private fun appendProfileEditRequiredToBaseline(sessionIdKey: String, username: String) {
        val sid = sessionIdKey.trim().ifBlank { "local" }
        val user = username.trim()
        if (user.isBlank()) return
        val baselineOpId = "profile-baseline-$sid"
        val existingIdx = entryIndexByOpId[baselineOpId]
        val mergedUsers = linkedSetOf<String>()
        if (existingIdx != null) {
            val data = entries[existingIdx].report_data
            val raw = data["pending_profile_edit_usernames"]?.toString().orEmpty()
            raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { mergedUsers.add(it) }
        }
        mergedUsers.add(user)
        val pendingCsv = mergedUsers.joinToString(",")
        if (existingIdx != null) {
            val existing = entries[existingIdx]
            entries[existingIdx] = existing.copy(
                at = nowBeijingText(),
                message = "会话内待改基本资料 ${mergedUsers.size} 个账号",
                report_data = mapOf(
                    "op_type" to "auto_switch_profile_baseline",
                    "pending_profile_edit_usernames" to pendingCsv,
                ),
            )
            persist()
            return
        }
        record(
            opId = baselineOpId,
            opType = "auto_switch_profile_baseline",
            status = "success",
            message = "会话内待改基本资料 ${mergedUsers.size} 个账号",
            reportData = mapOf(
                "op_type" to "auto_switch_profile_baseline",
                "pending_profile_edit_usernames" to pendingCsv,
            ),
            phaseOverride = "success",
        )
    }

    fun recordProfileEditStarted(
        opId: String,
        username: String,
        profileEditTrigger: String,
        basicProfileModifiedBefore: String,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val user = username.trim()
        if (user.isBlank()) return
        record(
            opId = opId,
            opType = "tiktok_edit_basic_profile",
            status = "running",
            message = "改资料已开始（trigger=$profileEditTrigger）",
            username = user,
            reportData = mapOf(
                "op_type" to "tiktok_edit_basic_profile",
                "username" to user,
                "profile_edit_trigger" to profileEditTrigger.trim(),
                "basic_profile_modified_before" to basicProfileModifiedBefore.trim().ifBlank { "否" },
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

    /** 自动登录成功：自治期不写 mark_login API，对账时写 account_editor_rows.is_login。 */
    fun recordAccountLoginSuccess(username: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val user = username.trim()
        if (user.isBlank()) return
        ensureSessionContext()
        val sid = sessionId.ifBlank { PrefsStore.getAutoSwitchSessionId().orEmpty() }
        val loginTime = nowBeijingText()
        val opId = "login-${sid.ifBlank { "local" }}-${user.lowercase(Locale.US)}"
        record(
            opId = opId,
            opType = "account_mark_login",
            status = "success",
            message = "自动登录成功，待对账写 is_login",
            username = user,
            reportData = mapOf(
                "op_type" to "account_mark_login",
                "username" to user,
                "account_username" to user,
                "login_time" to loginTime,
            ),
            phaseOverride = "success",
        )
    }

    /** 改资料检测到 TikTok 用户名变更：对账时同步 account/device 表。 */
    fun recordUsernameUpdate(oldUsername: String, newUsername: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val oldUser = oldUsername.trim()
        val newUser = newUsername.trim()
        if (oldUser.isBlank() || newUser.isBlank()) return
        if (oldUser.equals(newUser, ignoreCase = true)) return
        ensureSessionContext()
        val opId = "uname-${oldUser.lowercase(Locale.US)}-${newUser.lowercase(Locale.US)}-${System.currentTimeMillis()}"
        record(
            opId = opId,
            opType = "account_username_update",
            status = "success",
            message = "用户名变更 $oldUser → $newUser（待对账写回）",
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

    /** 失败采集：自治期不上传 zip，对账/关会话前批量上传。 */
    fun recordFailureReportDeferred(
        business: String,
        errorType: String,
        summary: String,
        ocrRelated: Boolean,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val biz = business.trim()
        val err = FailureReportUploader.normalizeErrorType(errorType)
        if (biz.isBlank() || err.isBlank()) return
        if (PrefsStore.isFailureReportUploaded(biz, err)) return
        ensureSessionContext()
        val opId = "fail-${biz.lowercase(Locale.US)}-${err}-${System.currentTimeMillis()}"
        record(
            opId = opId,
            opType = "failure_report_deferred",
            status = "success",
            message = "失败采集待对账上传：$biz / $err",
            reportData = mapOf(
                "op_type" to "failure_report_deferred",
                "failure_business" to biz,
                "failure_error_type" to err,
                "failure_summary" to summary.take(2000),
                "failure_ocr_related" to ocrRelated,
            ),
            phaseOverride = "success",
        )
    }

    suspend fun forEachDeferredFailureReport(block: suspend (LedgerEntry) -> Unit) {
        for (entry in entries.filter { it.op_type == "failure_report_deferred" && it.phase == "success" }) {
            block(entry)
        }
    }

    /** 新号冷却 -1→0：写入 ledger，关会话时对账写回 DB。 */
    fun recordVideoCountGraduate(
        username: String,
        rowIndex: Int,
        slotKey: String,
        toValue: Int = 0,
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val user = username.trim()
        if (user.isBlank()) return
        record(
            opId = "vc-grad-$rowIndex-${System.currentTimeMillis()}",
            opType = "auto_switch_video_count_graduate",
            status = "success",
            message = "新号冷却 video_count -1→$toValue",
            slotKey = slotKey.trim(),
            username = user,
            reportData = mapOf(
                "op_type" to "auto_switch_video_count_graduate",
                "username" to user,
                "row_index" to rowIndex,
                "video_count" to toValue,
            ),
            phaseOverride = "success",
        )
    }

    fun record(
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
            if (!canTransitionPhase(existing.phase, newPhase)) {
                return
            }
            val merged = existing.copy(
                at = nowBeijingText(),
                op_type = opType.trim().ifBlank { existing.op_type },
                status = normalizedStatus.ifBlank { existing.status },
                phase = newPhase,
                message = message.trim().take(2000).ifBlank { existing.message },
                slot_key = slotKey.trim().ifBlank { existing.slot_key },
                username = username.trim().ifBlank { existing.username },
                report_data = if (reportData.isNotEmpty()) reportData else existing.report_data,
            )
            entries[existingIdx] = merged
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

    fun setScheduleDate(date: String) {
        scheduleDate = date.trim()
        persist()
    }

    fun buildStats(): Map<String, Any> {
        val success = entries.count { it.phase == "success" }
        val failed = entries.count { it.phase == "failed" }
        return mapOf(
            "total" to entries.size,
            "success" to success,
            "failed" to failed,
            "cut_switch_ops" to entries.count {
                it.op_type in setOf("tiktok_backup_and_clear", "tiktok_clear", "tiktok_restore")
            },
            "publish_dispatch" to entries.count { it.op_type == "publish_post_start" },
            "publish_outcome" to entries.count { it.op_type == "publish_post_outcome" },
            "profile_edit_ops" to entries.count { it.op_type == "tiktok_edit_basic_profile" },
            "cut_switch_missing_backup" to entries.count { it.op_type == "cut_switch_missing_backup" },
        )
    }

    fun buildHumanLog(alias: String): String {
        val stats = buildStats()
        val lines = mutableListOf<String>()
        lines.add("【自动切号会话总结】")
        lines.add("会话：${sessionId.ifBlank { PrefsStore.getAutoSwitchSessionId().orEmpty() }}")
        lines.add("bundle_revision：${bundleRevision.coerceAtLeast(PrefsStore.getAutoSwitchBundleRevision())}")
        lines.add("设备：${alias.trim()}")
        val date = scheduleDate.ifBlank { PrefsStore.getAutoSwitchScheduleDate().orEmpty() }
        if (date.isNotBlank()) lines.add("计划日期：$date（北京时间）")
        val started = startedAt.ifBlank { PrefsStore.getAutoSwitchSessionStartedAt().orEmpty() }
        if (started.isNotBlank()) lines.add("开始：$started")
        lines.add("结束：${nowBeijingText()}")
        val missingBackupCount = stats["cut_switch_missing_backup"] as? Int ?: 0
        lines.add(
            "操作合计 ${stats["total"]} 项：成功 ${stats["success"]}，失败 ${stats["failed"]}" +
                if (missingBackupCount > 0) "；备份包缺失改自动登录 $missingBackupCount 次" else "",
        )
        AutoSwitchBundleHolder.getBundle()?.smart_template_debug?.toAuditLines()?.let { auditLines ->
            if (auditLines.isNotEmpty()) {
                lines.add("")
                lines.add("智能模板 enter 打包：")
                auditLines.forEach { lines.add(it) }
            }
        }
        lines.add("")
        if (entries.isEmpty()) {
            lines.add("（本次自治期间无 OP 记录）")
        } else {
            entries.forEachIndexed { idx, e ->
                val account = e.username.takeIf { it.isNotEmpty() }?.let { " @$it" }.orEmpty()
                val slot = e.slot_key.takeIf { it.isNotEmpty() }?.let { " [$it]" }.orEmpty()
                lines.add(
                    "${idx + 1}. [${e.at}] ${opTypeLabel(e.op_type)}$account$slot — ${statusLabel(e.phase)}：${e.message}",
                )
            }
        }
        return lines.joinToString("\n")
    }

    fun toSummaryRequest(
        alias: String,
        deviceId: String,
        summaryKind: String = "final",
        reconcileKey: String = "",
    ): AutoSwitchSessionSummaryRequest {
        val sid = sessionId.ifBlank { PrefsStore.getAutoSwitchSessionId().orEmpty() }
        val kind = summaryKind.trim().lowercase().ifBlank { "final" }
        val reconcile = reconcileKey.trim().ifBlank {
            if (kind == "daily") beijingDateString() else "final"
        }
        val humanPrefix = if (kind == "daily") "【每日对账 $reconcile】\n" else ""
        val human = humanPrefix + buildHumanLog(alias)
        val smartDebug = smartTemplateDebugPayload()
        return AutoSwitchSessionSummaryRequest(
            device_id = deviceId,
            alias = alias,
            session_id = sid,
            bundle_revision = bundleRevision.coerceAtLeast(PrefsStore.getAutoSwitchBundleRevision()),
            schedule_date = scheduleDate.ifBlank { PrefsStore.getAutoSwitchScheduleDate().orEmpty() },
            started_at = startedAt.ifBlank { PrefsStore.getAutoSwitchSessionStartedAt().orEmpty() },
            ended_at = nowBeijingText(),
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
            human_log = human,
            stats = buildStats(),
            summary_kind = kind,
            reconcile_key = reconcile,
            smart_template_debug = smartDebug,
        )
    }

    private fun smartTemplateDebugPayload(): Map<String, Any> {
        val dbg = AutoSwitchBundleHolder.getBundle()?.smart_template_debug ?: return emptyMap()
        return runCatching {
            val json = gson.toJson(dbg)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            gson.fromJson<Map<String, Any>>(json, type) ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    private fun beijingDateString(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = beijingTz
        return fmt.format(Date())
    }

    fun buildReportDataPreview(opType: String, payload: DeviceOpPayload?): Map<String, Any> =
        buildReportDataFromOp(opType, payload)

    fun recordAccountActivitySegment(
        username: String,
        slotKey: String,
        activeTemplate: String,
        runMode: String,
        segmentStartedAt: String,
        segmentEndedAt: String,
        segmentReason: String,
        templateConfigJson: String,
        actionCountsJson: String,
    ) {
        val account = username.trim()
        if (account.isEmpty()) return
        val tpl = activeTemplate.trim().ifBlank { "—" }
        val countsPreview = actionCountsJson.trim().ifBlank { "{}" }
        record(
            opId = "local-as-activity-${System.currentTimeMillis()}-${account.hashCode()}",
            opType = "auto_switch_account_activity",
            status = "success",
            message = "@$account · $tpl · $segmentStartedAt ~ $segmentEndedAt · $countsPreview",
            slotKey = slotKey.trim(),
            username = account,
            reportData = mapOf(
                "op_type" to "auto_switch_account_activity",
                "account_username" to account,
                "active_template" to tpl,
                "auto_switch_slot_key" to slotKey.trim(),
                "segment_started_at" to segmentStartedAt,
                "segment_ended_at" to segmentEndedAt,
                "segment_reason" to segmentReason,
                "run_mode" to runMode.trim().lowercase(),
                "template_config_json" to templateConfigJson,
                "action_counts_json" to actionCountsJson,
            ),
            phaseOverride = "success",
        )
    }

    /** 高亮已对齐、登录校验通过后写入 verified（无需 clear/restore）。 */
    fun recordCutSwitchSlotVerified(slotKey: String, targetRowIndex: Int, username: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val sk = slotKey.trim()
        val user = username.trim().removePrefix("@")
        if (sk.isBlank() || targetRowIndex < 0 || user.isBlank()) return
        ensureSessionContext()
        record(
            opId = "local-as-slot-verified-${System.currentTimeMillis()}-$sk-${targetRowIndex}",
            opType = SLOT_VERIFIED_OP_TYPE,
            status = "success",
            message = "登录校验对齐 @$user",
            slotKey = sk,
            username = user,
            reportData = mapOf(
                "op_type" to SLOT_VERIFIED_OP_TYPE,
                "auto_switch_slot_key" to sk,
                "cut_switch_target_row_index" to targetRowIndex,
                "username" to user,
            ),
            phaseOverride = "success",
        )
    }

    /** 对齐已完成时取消 ledger 中同 slot 仍为 queued 的切号 OP，避免 inflight 挡住 verified。 */
    fun cancelQueuedCutSwitchOpsForSlot(slotKey: String, reason: String) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val sk = slotKey.trim()
        if (sk.isBlank()) return
        val detail = reason.trim().ifBlank { "对齐已完成，取消重复切号 OP" }
        var changed = false
        for (i in entries.indices) {
            val e = entries[i]
            if (e.slot_key != sk) continue
            if (e.op_type !in cutSwitchOpTypes) continue
            if (e.phase != "queued") continue
            entries[i] = e.copy(
                at = nowBeijingText(),
                status = "cancelled",
                phase = "cancelled",
                message = detail,
            )
            changed = true
        }
        if (changed) persist()
    }

    fun recordCutSwitchQueued(
        opId: String,
        opType: String,
        slotKey: String,
        username: String,
        payload: DeviceOpPayload?,
    ) {
        record(
            opId = opId,
            opType = opType,
            status = "queued",
            message = "已入队",
            slotKey = slotKey,
            username = username,
            reportData = buildReportDataFromOp(opType, payload),
            phaseOverride = "queued",
        )
    }

    /** 云端有备份记录但本机无包：改走自动登录；对账时统计 [cut_switch_missing_backup] 次数。 */
    fun recordCutSwitchMissingBackup(
        username: String,
        slotKey: String,
        cloudBackupName: String,
        phase: String,
        message: String = "",
    ) {
        if (!PrefsStore.isAutoSwitchAutonomousMode()) return
        val user = username.trim().removePrefix("@")
        if (user.isBlank()) return
        ensureSessionContext()
        val cloudName = cloudBackupName.trim().let { File(it).name }.ifBlank { "-" }
        val phaseKey = phase.trim().ifBlank { "unknown" }
        val detail = message.trim().ifBlank {
            "目标账号 @$user 备份包本机不可用，改走自动登录（phase=$phaseKey）"
        }
        record(
            opId = "cut-switch-missing-backup-${System.currentTimeMillis()}-${user.hashCode()}",
            opType = "cut_switch_missing_backup",
            status = "success",
            message = detail,
            slotKey = slotKey.trim(),
            username = user,
            reportData = mapOf(
                "op_type" to "cut_switch_missing_backup",
                "username" to user,
                "cloud_backup_name" to cloudName,
                "phase" to phaseKey,
                "auto_switch_slot_key" to slotKey.trim(),
            ),
            phaseOverride = "success",
        )
    }

    fun clear() {
        sessionId = ""
        bundleRevision = 0
        scheduleDate = ""
        startedAt = ""
        entries.clear()
        entryIndexByOpId.clear()
        AutoSwitchAccountActivityTracker.reset()
        PrefsStore.clearAutoSwitchLedgerPersisted()
    }

    fun restoreFromPrefs() {
        val blob = PrefsStore.getAutoSwitchLedgerJson() ?: return
        runCatching {
            val type = object : TypeToken<PersistedLedger>() {}.type
            val saved: PersistedLedger = gson.fromJson(blob, type) ?: return
            sessionId = saved.session_id.orEmpty()
            bundleRevision = saved.bundle_revision ?: PrefsStore.getAutoSwitchBundleRevision()
            scheduleDate = saved.schedule_date.orEmpty()
            startedAt = saved.started_at.orEmpty()
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

    private fun sessionContextMatchesPrefs(): Boolean {
        val prefSid = PrefsStore.getAutoSwitchSessionId().orEmpty()
        val prefRev = PrefsStore.getAutoSwitchBundleRevision()
        if (prefSid.isNotBlank() && sessionId.isNotBlank() && sessionId != prefSid) {
            return false
        }
        if (prefRev > 0 && bundleRevision > 0 && bundleRevision != prefRev) {
            return false
        }
        return true
    }

    private fun ensureSessionContext() {
        if (sessionId.isBlank()) {
            sessionId = PrefsStore.getAutoSwitchSessionId().orEmpty()
        }
        if (bundleRevision <= 0) {
            bundleRevision = PrefsStore.getAutoSwitchBundleRevision()
        }
        if (scheduleDate.isBlank()) {
            scheduleDate = PrefsStore.getAutoSwitchScheduleDate().orEmpty()
        }
        if (startedAt.isBlank()) {
            startedAt = PrefsStore.getAutoSwitchSessionStartedAt().orEmpty().ifBlank { nowBeijingText() }
        }
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
            schedule_date = scheduleDate,
            started_at = startedAt,
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

    private fun opTypeLabel(opType: String): String = when (opType) {
        "tiktok_backup_and_clear" -> "切号备份并清理"
        "tiktok_clear" -> "清理 TikTok"
        "tiktok_restore" -> "还原账号"
        "publish_post_start" -> "发作品下发"
        "publish_post_outcome" -> "发作品结果"
        "tiktok_edit_basic_profile" -> "修改基本资料"
        "auto_switch_profile_baseline" -> "改资料基线"
        "profile_edit_required" -> "待改资料(切到)"
        "auto_switch_video_count_graduate" -> "新号冷却毕业"
        "account_mark_login" -> "登录成功(待对账)"
        "account_username_update" -> "用户名变更(待对账)"
        "failure_report_deferred" -> "失败采集(待对账)"
        "auto_switch_account_activity" -> "账号任务段"
        "cut_switch_missing_backup" -> "切号备份缺失(改自动登录)"
        SLOT_VERIFIED_OP_TYPE -> "时段登录校验对齐"
        else -> opType.ifBlank { "操作" }
    }

    private fun statusLabel(phase: String): String = when (phase) {
        "queued" -> "已入队"
        "running" -> "执行中"
        "success" -> "成功"
        "failed" -> "失败"
        else -> phase.ifBlank { "未知" }
    }

    private data class PersistedLedger(
        val session_id: String? = null,
        val bundle_revision: Int? = null,
        val schedule_date: String? = null,
        val started_at: String? = null,
        val entries: List<PersistedLedgerEntry>? = null,
    )

    private fun sanitizeReportData(raw: Map<String, Any>?): Map<String, Any> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = linkedMapOf<String, Any>()
        for (key in reportDataKeys) {
            val value = raw[key] ?: continue
            when (value) {
                is String -> if (value.isNotBlank()) out[key] = value
                is Boolean, is Int, is Long, is Double, is Float -> out[key] = value
                is Number -> out[key] = value
            }
        }
        if (!out.containsKey("op_type")) {
            raw["op_type"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out["op_type"] = it }
        }
        return out
    }

    private fun buildReportDataFromOp(opType: String, payload: DeviceOpPayload?): Map<String, Any> {
        if (payload == null) {
            return if (opType.isBlank()) emptyMap() else mapOf("op_type" to opType)
        }
        val raw = linkedMapOf<String, Any>()
        raw["op_type"] = opType
        payload.backup_record_id?.let { raw["backup_record_id"] = it }
        payload.username?.trim()?.takeIf { it.isNotEmpty() }?.let { raw["username"] = it }
        payload.alias?.trim()?.takeIf { it.isNotEmpty() }?.let { raw["mobile_alias"] = it }
        payload.backup_path?.trim()?.takeIf { it.isNotEmpty() }?.let { raw["backup_path"] = it }
        payload.backup_name?.trim()?.takeIf { it.isNotEmpty() }?.let { raw["backup_name"] = it }
        payload.previous_backup_path?.trim()?.takeIf { it.isNotEmpty() }?.let { raw["previous_backup_path"] = it }
        payload.cut_switch_after_backup?.trim()?.takeIf { it.isNotEmpty() }?.let {
            raw["cut_switch_after_backup"] = it
        }
        payload.cut_switch_target_row_index?.let { raw["cut_switch_target_row_index"] = it }
        payload.restore_highlight_row_index?.let { raw["restore_highlight_row_index"] = it }
        payload.skip_pm_clear?.let { raw["skip_pm_clear"] = it }
        payload.source?.trim()?.takeIf { it.isNotEmpty() }?.let { raw["source"] = it }
        payload.auto_switch_slot_key?.trim()?.takeIf { it.isNotEmpty() }?.let {
            raw["auto_switch_slot_key"] = it
        }
        payload.account_username?.trim()?.takeIf { it.isNotEmpty() }?.let { raw["account_username"] = it }
        payload.publish_count?.let { raw["publish_count"] = it }
        return sanitizeReportData(raw)
    }

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
