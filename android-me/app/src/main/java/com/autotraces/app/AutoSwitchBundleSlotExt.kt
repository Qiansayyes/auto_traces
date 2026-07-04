package com.autotraces.app

import java.util.Calendar
import java.util.TimeZone

private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")

fun beijingCalendar(): Calendar = Calendar.getInstance(beijingTz)

fun beijingDateStr(cal: Calendar = beijingCalendar()): String {
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    return String.format("%04d-%02d-%02d", y, m, d)
}

fun beijingMinuteOfDay(cal: Calendar = beijingCalendar()): Int =
    cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

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
        if (!weekly_plan?.day_plans.isNullOrEmpty()) return false
        return schedule_date?.trim() == d
    }
    return d in start..end
}

/** 账号 row + username 集合指纹（资料重同步时对比用）。 */
fun AutoSwitchBundle.accountsFingerprint(): String =
    accounts.orEmpty()
        .mapNotNull { acc ->
            val ri = acc.row_index ?: return@mapNotNull null
            val user = acc.username?.trim()?.lowercase().orEmpty()
            if (user.isEmpty()) return@mapNotNull null
            "$ri:$user"
        }
        .sorted()
        .joinToString("|")

fun AutoSwitchBundle.hasSameAccountSetAs(other: AutoSwitchBundle?): Boolean {
    if (other == null) return false
    return accountsFingerprint() == other.accountsFingerprint()
}

fun AutoSwitchBundle.findAccount(username: String): AutoSwitchAccount? {
    val key = username.trim()
    if (key.isEmpty()) return null
    return accounts.orEmpty().firstOrNull {
        it.username?.trim().equals(key, ignoreCase = true)
    }
}

/** 当前时段应对齐的 TikTok 账号（只读排班，来自 bundle）。 */
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

fun AutoSwitchBundle.resolveBundledConfigForUsername(username: String?): AutoSwitchBundledAccountConfig? {
    val configs = configs_by_username.orEmpty()
    if (configs.isEmpty()) return null
    val key = username?.trim()?.lowercase().orEmpty()
    if (key.isNotEmpty()) {
        configs.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.let { return it }
    }
    resolveCurrentSlotTargetAccount(beijingDateStr(), beijingMinuteOfDay())
        ?.username?.trim()?.lowercase()?.let { slotKey ->
            configs.entries.firstOrNull { it.key.equals(slotKey, ignoreCase = true) }?.value?.let { return it }
        }
    if (configs.size == 1) {
        return configs.values.firstOrNull()
    }
    return null
}

/** bundle 内该账号是否配置了可执行的引流互点。 */
fun AutoSwitchBundle.isMutualTrafficConfiguredFor(username: String): Boolean {
    val cfg = resolveBundledConfigForUsername(username)?.config ?: return false
    if (cfg.mutual_traffic_follow_only) return true
    return cfg.mutual_traffic_comment ||
        cfg.mutual_traffic_author_follow ||
        cfg.mutual_traffic_followers_follow
}

fun AutoSwitchBundle.mutualTrafficOpTypeFor(username: String): String? {
    if (!isMutualTrafficConfiguredFor(username)) return null
    val cfg = resolveBundledConfigForUsername(username)?.config ?: return null
    return if (cfg.mutual_traffic_follow_only) {
        "mutual_click_follow_only_start"
    } else {
        "mutual_click_start"
    }
}

/** 当前分钟所在时段上下文（时间点来自 bundle slot，非 plan JSON）。 */
data class AutoSwitchCurrentSlotContext(
    val scheduleDate: String,
    val slotKey: String,
    val slot: AutoSwitchPlanSlot,
    val account: AutoSwitchAccount,
    val minuteOfDay: Int,
)

fun AutoSwitchBundle.resolveCurrentSlotContext(
    scheduleDate: String = beijingDateStr(),
    minuteOfDay: Int = beijingMinuteOfDay(),
): AutoSwitchCurrentSlotContext? {
    val date = scheduleDate.trim()
    if (date.isEmpty() || !isDateInWeekRange(date)) return null
    val plan = dailyPlanForDate(date) ?: return null
    val minute = minuteOfDay.coerceIn(0, 1439)
    val slot = AutoSwitchSlotWindow.resolveTargetSlot(plan, minute) ?: return null
    if (!AutoSwitchSlotWindow.isWithinSlot(minute, slot)) return null
    val slotIndex = slot.slot_index ?: return null
    val targetRi = slot.row_index ?: return null
    val account = accounts.orEmpty().firstOrNull { (it.row_index ?: -1) == targetRi } ?: return null
    return AutoSwitchCurrentSlotContext(
        scheduleDate = date,
        slotKey = AutoSwitchLocalPlanStore.formatSlotKey(date, slotIndex),
        slot = slot,
        account = account,
        minuteOfDay = minute,
    )
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
