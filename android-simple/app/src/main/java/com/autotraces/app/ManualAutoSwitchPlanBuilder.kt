package com.autotraces.app

import android.content.Context
import com.autotraces.app.backup.UnifiedBackupPaths
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * android-simple 本机自动切号：用手动账号列表生成周计划 bundle（不依赖管理台周计划）。
 */
object ManualAutoSwitchPlanBuilder {
    const val SESSION_PREFIX = "local_as_"
    const val DEFAULT_SLOT_MINUTES = 60
    const val MIN_SLOT_MINUTES = 15
    const val MAX_SLOT_MINUTES = 720
    private const val DAYS_AHEAD = 7

    private val beijingTz: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    fun isLocalSessionId(sessionId: String?): Boolean =
        sessionId?.trim()?.startsWith(SESSION_PREFIX) == true

    fun normalizeSlotMinutes(raw: Int): Int =
        raw.coerceIn(MIN_SLOT_MINUTES, MAX_SLOT_MINUTES)

    fun buildBundle(
        context: Context,
        accounts: List<ManualDeviceControl.AccountOption>,
        slotMinutes: Int,
        alias: String,
        deviceId: String,
        previousSessionId: String? = null,
        previousRevision: Int = 0,
    ): AutoSwitchBundle {
        val minutes = normalizeSlotMinutes(slotMinutes)
        require(accounts.isNotEmpty()) { "账号列表为空，请先拉取云端账号" }

        val cal = Calendar.getInstance(beijingTz)
        val today = formatDate(cal)
        val weekEndCal = cal.clone() as Calendar
        weekEndCal.add(Calendar.DAY_OF_MONTH, DAYS_AHEAD - 1)
        val weekEnd = formatDate(weekEndCal)

        val usedRows = mutableSetOf<Int>()
        val switchAccounts = accounts.mapIndexed { idx, opt ->
            val user = opt.username.trim().removePrefix("@")
            val preferred = opt.rowIndex?.takeIf { it >= 0 }
            var row = preferred ?: (idx + 1)
            if (row in usedRows) {
                row = (usedRows.maxOrNull() ?: 0) + 1
            }
            usedRows.add(row)
            val localBackup = if (alias.isNotBlank() && deviceId.isNotBlank() && user.isNotBlank()) {
                UnifiedBackupPaths.listUsernameBackupArchives(context, user, alias, deviceId).firstOrNull()
            } else {
                null
            }
            AutoSwitchAccount(
                row_index = row,
                username = user,
                backup_path = localBackup?.absolutePath
                    ?: opt.backupPath.trim().takeIf { it.isNotEmpty() },
                backup_name = localBackup?.name
                    ?: opt.backupName.trim().takeIf { it.isNotEmpty() },
                assigned_alias = alias,
                device_alias = alias,
                switch_highlight = "否",
                account_row_index = opt.rowIndex,
                email = opt.email.trim().takeIf { it.isNotEmpty() },
                password = opt.password.trim().takeIf { it.isNotEmpty() },
                email_password = opt.emailPassword.trim().takeIf { it.isNotEmpty() },
                api = opt.api.trim().takeIf { it.isNotEmpty() },
                is_login = opt.cloudIsLogin,
                basic_profile_modified = opt.basicProfileModified.ifBlank { "否" },
                mail_module_code_available = opt.mailModuleCodeAvailable,
            )
        }

        val dayPlans = mutableListOf<AutoSwitchWeeklyDayPlan>()
        val dayCal = Calendar.getInstance(beijingTz)
        for (dayOffset in 0 until DAYS_AHEAD) {
            val dateCal = dayCal.clone() as Calendar
            dateCal.add(Calendar.DAY_OF_MONTH, dayOffset)
            val date = formatDate(dateCal)
            dayPlans.add(
                AutoSwitchWeeklyDayPlan(
                    schedule_date = date,
                    daily_plan = buildDailyPlan(switchAccounts, minutes, dayOffset),
                ),
            )
        }

        val sessionId = previousSessionId?.takeIf { isLocalSessionId(it) }
            ?: newSessionId()
        val revision = (previousRevision.coerceAtLeast(0) + 1).coerceAtLeast(1)

        return AutoSwitchBundle(
            session_id = sessionId,
            revision = revision,
            schedule_date = today,
            week_start_date = today,
            week_end_date = weekEnd,
            timezone = "Asia/Shanghai",
            device_alias = alias,
            device_id = deviceId,
            run_config = AutoSwitchRunConfig(run_mode = "growth"),
            accounts = switchAccounts,
            daily_plan = dayPlans.firstOrNull()?.daily_plan,
            weekly_plan = AutoSwitchWeeklyPlan(
                week_start_date = today,
                week_end_date = weekEnd,
                account_count = switchAccounts.size,
                day_plans = dayPlans,
            ),
            retry_policy = AutoSwitchRetryPolicy(
                cut_switch_max_attempts = 3,
                login_max_attempts = 3,
                publish_max_attempts = 2,
                backoff_base_sec = 5,
                backoff_max_sec = 60,
            ),
        )
    }

    private fun buildDailyPlan(
        accounts: List<AutoSwitchAccount>,
        slotMinutes: Int,
        dayOffset: Int,
    ): AutoSwitchDailyPlan {
        val slots = mutableListOf<AutoSwitchPlanSlot>()
        var slotIndex = 0
        var start = 0
        while (start < 1440) {
            val end = (start + slotMinutes).coerceAtMost(1440)
            val account = accounts[(dayOffset * ((1440 + slotMinutes - 1) / slotMinutes) + slotIndex) % accounts.size]
            slots.add(
                AutoSwitchPlanSlot(
                    slot_index = slotIndex,
                    start_min = start,
                    end_min = end,
                    row_index = account.row_index,
                    username = account.username,
                    auto_publish_minute = null,
                    auto_mutual_minute = null,
                ),
            )
            slotIndex++
            start = end
        }
        return AutoSwitchDailyPlan(
            slot_minutes = slotMinutes,
            account_count = accounts.size,
            slots = slots,
        )
    }

    private fun newSessionId(): String {
        val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = beijingTz
        }.format(Date())
        val rand = (Math.random() * 0xFFFFFF).toInt().toString(16).padStart(6, '0')
        return "$SESSION_PREFIX${ts}_$rand"
    }

    private fun formatDate(cal: Calendar): String {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
    }
}
