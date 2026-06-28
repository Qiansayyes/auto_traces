package com.autotraces.app

import java.io.File
import java.util.Calendar
import java.util.TimeZone

/** 自动切号 bundle 内存真源（与 [AutoSwitchLocalScheduler] 同步）。 */
object AutoSwitchBundleHolder {
    @Volatile
    private var bundle: AutoSwitchBundle? = null

    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")

    fun setBundle(value: AutoSwitchBundle?) {
        bundle = value
    }

    fun getBundle(): AutoSwitchBundle? = bundle

    fun resolveAssignedAccount(
        preferredUsername: String? = null,
        logContextUsername: String? = null,
    ): DeviceAssignedAccountResponse? {
        val current = bundle ?: return null
        val preferred = preferredUsername?.trim()?.takeIf { it.isNotEmpty() }
        val account = when {
            preferred != null -> current.findAccount(preferred)
            else -> {
                current.resolveCurrentSlotTargetAccount(beijingDateStr(), beijingMinuteOfDay())
                    ?: current.resolveHighlightedAccount(logContextUsername)
            }
        } ?: return null
        return account.toAssignedAccountResponse()
    }

    fun notifyAccountLoggedIn(username: String) {
        val uname = username.trim()
        if (uname.isEmpty()) return
        val current = bundle ?: return
        val accounts = current.accounts.orEmpty().map { acc ->
            if (acc.username?.trim().equals(uname, ignoreCase = true)) {
                acc.copy(is_login = true)
            } else {
                acc
            }
        }
        bundle = current.copy(accounts = accounts)
    }

    fun notifyBasicProfileModified(username: String) {
        val uname = username.trim()
        if (uname.isEmpty()) return
        val current = bundle ?: return
        val accounts = current.accounts.orEmpty().map { acc ->
            if (acc.username?.trim().equals(uname, ignoreCase = true)) {
                acc.copy(basic_profile_modified = "是")
            } else {
                acc
            }
        }
        bundle = current.copy(accounts = accounts)
    }

    private fun beijingMinuteOfDay(): Int =
        beijingMinuteOfDay(Calendar.getInstance(beijingTz))

    private fun beijingDateStr(): String = beijingDateStr(Calendar.getInstance(beijingTz))
}

private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")

private fun beijingDateStr(cal: Calendar): String {
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    return String.format("%04d-%02d-%02d", y, m, d)
}

private fun beijingMinuteOfDay(cal: Calendar): Int =
    cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

/** 从周计划 bundle 解析指定日期的 daily_plan（跨天自治用）。 */
fun AutoSwitchBundle.dailyPlanForDate(date: String): AutoSwitchDailyPlan? {
    val key = date.trim()
    if (key.isEmpty()) return null
    weekly_plan?.day_plans.orEmpty()
        .firstOrNull { it.schedule_date?.trim() == key }
        ?.daily_plan
        ?.let { return it }
    if (schedule_date?.trim() == key) {
        return daily_plan
    }
    return null
}

fun AutoSwitchBundle.isDateInWeekRange(date: String): Boolean {
    val d = date.trim()
    if (d.isEmpty()) return false
    val start = week_start_date?.trim().orEmpty().ifBlank { weekly_plan?.week_start_date?.trim().orEmpty() }
    val end = week_end_date?.trim().orEmpty().ifBlank { weekly_plan?.week_end_date?.trim().orEmpty() }
    if (start.isEmpty() || end.isEmpty()) {
        val hasWeeklyDays = !weekly_plan?.day_plans.isNullOrEmpty()
        if (hasWeeklyDays) return false
        return schedule_date?.trim() == d
    }
    return d in start..end
}

fun AutoSwitchBundle.hasWeeklyDayPlans(): Boolean =
    !weekly_plan?.day_plans.isNullOrEmpty()

fun AutoSwitchBundle.findAccount(username: String): AutoSwitchAccount? {
    val key = username.trim()
    if (key.isEmpty()) return null
    return accounts.orEmpty().firstOrNull {
        it.username?.trim().equals(key, ignoreCase = true)
    }
}

fun AutoSwitchBundle.resolveCurrentSlotTargetAccount(
    scheduleDate: String,
    minuteOfDay: Int,
): AutoSwitchAccount? {
    val date = scheduleDate.trim()
    if (date.isEmpty() || !isDateInWeekRange(date)) return null
    val plan = dailyPlanForDate(date) ?: return null
    val minute = minuteOfDay.coerceIn(0, 1439)
    val targetSlot = AutoSwitchSlotWindow.resolveTargetSlot(plan, minute) ?: return null
    if (!AutoSwitchSlotWindow.isWithinSlot(minute, targetSlot)) return null
    val targetRi = targetSlot.row_index ?: -1
    return accounts.orEmpty().firstOrNull { (it.row_index ?: -1) == targetRi }
}

/** 高亮仅由当前时段计划决定；时段外或计划缺失时再回退 logContext / 单账号。 */
fun AutoSwitchBundle.resolveHighlightedAccount(logContextUsername: String? = null): AutoSwitchAccount? {
    val accounts = accounts.orEmpty()
    if (accounts.isEmpty()) return null
    val cal = Calendar.getInstance(beijingTz)
    val scheduleDate = beijingDateStr(cal)
    val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    resolveCurrentSlotTargetAccount(scheduleDate, minuteOfDay)?.let { return it }
    val logUser = logContextUsername?.trim()?.lowercase()
    if (!logUser.isNullOrBlank()) {
        accounts.firstOrNull { it.username?.trim()?.lowercase() == logUser }?.let { return it }
    }
    if (accounts.size == 1) return accounts[0]
    return null
}

/** 按时段 row_index 刷新 bundle 内 switch_highlight（云端/本机展示均只认时间）。 */
fun AutoSwitchBundle.withTimeSlotHighlight(scheduleDate: String, minuteOfDay: Int): AutoSwitchBundle {
    val targetRi = resolveCurrentSlotTargetAccount(scheduleDate, minuteOfDay)?.row_index ?: return this
    val accounts = accounts.orEmpty().map { acc ->
        val rowRi = acc.row_index ?: -1
        acc.copy(switch_highlight = if (rowRi == targetRi) "是" else "否")
    }
    return copy(accounts = accounts)
}

fun AutoSwitchBundle.withCurrentTimeSlotHighlight(): AutoSwitchBundle {
    val cal = Calendar.getInstance(beijingTz)
    return withTimeSlotHighlight(
        beijingDateStr(cal),
        cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
    )
}

/** 从 bundle 内 configs_by_username 解析指定账号的 enter 冻结配置。 */
fun AutoSwitchBundle.resolveBundledConfigForUsername(username: String?): AutoSwitchBundledAccountConfig? {
    val configs = configs_by_username.orEmpty()
    if (configs.isEmpty()) return null
    val key = username?.trim()?.lowercase().orEmpty()
    if (key.isNotEmpty()) {
        configs.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.let { return it }
    }
    resolveHighlightedAccount(username)?.username?.trim()?.lowercase()?.let { highlightKey ->
        configs.entries.firstOrNull { it.key.equals(highlightKey, ignoreCase = true) }?.value?.let { return it }
    }
    if (configs.size == 1) {
        return configs.values.firstOrNull()
    }
    return null
}

fun AutoSwitchSmartTemplateDebug.toAuditLines(): List<String> {
    val lines = mutableListOf<String>()
    val sid = session_id?.trim().orEmpty()
    if (sid.isNotBlank()) lines.add("session=$sid")
    if (enter_reroll == true) lines.add("enter 重掷=是")
    accounts.orEmpty().forEach { (user, dbg) ->
        val branch = dbg.branch_label?.trim().orEmpty().ifBlank { dbg.branch.orEmpty() }
        val tpl = dbg.selected_template?.trim().orEmpty().ifBlank { dbg.picked_template.orEmpty() }
        val roll = dbg.roll
        val total = dbg.total_weight
        val rollText = if (roll != null && total != null && total > 0) " roll=$roll/$total" else ""
        lines.add("  @$user → $tpl ($branch$rollText)")
    }
    return lines
}

fun AutoSwitchAccount.toAssignedAccountResponse(): DeviceAssignedAccountResponse {
    val user = username?.trim().orEmpty()
    if (user.isBlank()) {
        return DeviceAssignedAccountResponse(code = 1, msg = "bundle 账号缺少 username")
    }
    val credsComplete = !email.isNullOrBlank() && !password.isNullOrBlank()
    return DeviceAssignedAccountResponse(
        code = if (credsComplete) 0 else 1,
        msg = if (credsComplete) "ok" else "bundle 账号凭证不完整",
        row_index = account_row_index ?: row_index,
        username = user,
        email = email?.trim().orEmpty(),
        password = password?.trim().orEmpty(),
        email_password = email_password?.trim().orEmpty(),
        api = api?.trim().orEmpty(),
        is_login = is_login == true,
        login_time = login_time?.trim().orEmpty(),
        basic_profile_modified = basic_profile_modified?.trim().orEmpty(),
        mail_module_code_available = mail_module_code_available == true,
    )
}

/**
 * 自治幂等键：账号 row + username 与周计划各日时段（slot_index / row / 时间窗）。
 * 账号数不变且重复下发时，指纹一致则不应触发重新对齐。
 */
fun AutoSwitchBundle.scheduleAndAccountsFingerprint(): String {
    val accountPart = accounts.orEmpty()
        .mapNotNull { acc ->
            val ri = acc.row_index ?: return@mapNotNull null
            val user = acc.username?.trim().orEmpty().lowercase()
            "$ri:$user"
        }
        .sorted()
        .joinToString("|")
    val planPart = weekly_plan?.day_plans.orEmpty()
        .mapNotNull { dp ->
            val date = dp.schedule_date?.trim().orEmpty()
            if (date.isEmpty()) return@mapNotNull null
            val slots = dp.daily_plan?.slots.orEmpty()
                .mapNotNull { slot ->
                    val idx = slot.slot_index ?: return@mapNotNull null
                    val ri = slot.row_index ?: -1
                    val start = slot.start_min ?: 0
                    val end = slot.end_min ?: 1440
                    val pub = slot.auto_publish_minute
                    "$idx:$ri:$start-$end:p$pub"
                }
                .sorted()
                .joinToString(",")
            "$date=[$slots]"
        }
        .sorted()
        .joinToString(";")
    val legacy = if (planPart.isEmpty() && !daily_plan?.slots.isNullOrEmpty()) {
        val date = schedule_date?.trim().orEmpty()
        val slots = daily_plan?.slots.orEmpty()
            .mapNotNull { slot ->
                val idx = slot.slot_index ?: return@mapNotNull null
                val ri = slot.row_index ?: -1
                val start = slot.start_min ?: 0
                val end = slot.end_min ?: 1440
                "$idx:$ri:$start-$end"
            }
            .sorted()
            .joinToString(",")
        if (date.isNotEmpty()) "$date=[$slots]" else ""
    } else {
        ""
    }
    return "acc[$accountPart]|plan[${planPart.ifBlank { legacy }}]"
}

fun AutoSwitchBundle.isScheduleAndAccountsEquivalentTo(other: AutoSwitchBundle?): Boolean {
    if (other == null) return false
    return scheduleAndAccountsFingerprint() == other.scheduleAndAccountsFingerprint()
}

/** 云端重下发时保留本机时段高亮与本机存在的备份路径，避免打断自治。 */
fun AutoSwitchBundle.mergePreservingLocalRuntimeState(local: AutoSwitchBundle?): AutoSwitchBundle {
    val prev = local ?: return this.withCurrentTimeSlotHighlight()
    var mergedAccounts = withCurrentTimeSlotHighlight().accounts.orEmpty()
    val localByRow = prev.accounts.orEmpty().associateBy { it.row_index ?: -1 }
    mergedAccounts = mergedAccounts.map { acc ->
        val ri = acc.row_index ?: return@map acc
        val localAcc = localByRow[ri] ?: return@map acc
        val localPath = localAcc.backup_path?.trim().orEmpty()
        val localName = localAcc.backup_name?.trim().orEmpty()
        if (localPath.isNotEmpty() && File(localPath).exists()) {
            acc.copy(
                backup_path = localPath,
                backup_name = localName.ifBlank { acc.backup_name },
            )
        } else {
            acc
        }
    }
    return copy(accounts = mergedAccounts)
}
