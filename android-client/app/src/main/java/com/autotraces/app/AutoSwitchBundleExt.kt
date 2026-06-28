package com.autotraces.app

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

    private fun beijingMinuteOfDay(): Int {
        val cal = Calendar.getInstance(beijingTz)
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun beijingDateStr(): String {
        val cal = Calendar.getInstance(beijingTz)
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", y, m, d)
    }
}

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
    val targetSlot = plan.resolveTargetSlot(minute) ?: return null
    if (!AutoSwitchSlotWindow.isWithinSlot(minute, targetSlot)) return null
    val targetRi = targetSlot.row_index ?: -1
    return accounts.orEmpty().firstOrNull { (it.row_index ?: -1) == targetRi }
}

fun AutoSwitchBundle.resolveHighlightedAccount(logContextUsername: String?): AutoSwitchAccount? {
    val accounts = accounts.orEmpty()
    if (accounts.isEmpty()) return null
    val highlighted = accounts.filter { it.switch_highlight?.trim() == "是" }
    if (highlighted.size == 1) return highlighted[0]
    val logUser = logContextUsername?.trim()?.lowercase()
    if (!logUser.isNullOrBlank()) {
        accounts.firstOrNull { it.username?.trim()?.lowercase() == logUser }?.let { return it }
    }
    if (highlighted.isNotEmpty()) return highlighted.first()
    if (accounts.size == 1) return accounts[0]
    return null
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

private fun AutoSwitchDailyPlan.resolveTargetSlot(minuteOfDay: Int): AutoSwitchPlanSlot? {
    val slots = slots.orEmpty()
    if (slots.isEmpty()) return null
    val minute = minuteOfDay.coerceIn(0, 1439)
    var idx = slots.last().slot_index ?: slots.size - 1
    for (slot in slots) {
        val start = slot.start_min ?: 0
        val end = slot.end_min ?: 0
        if (start <= minute && minute < end) {
            idx = slot.slot_index ?: idx
            break
        }
    }
    return slots.firstOrNull { (it.slot_index ?: -1) == idx }
        ?: slots.getOrNull(idx.coerceAtMost(slots.size - 1))
}
