package com.autotraces.app

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 自动切号 bundle 内存缓存（账号运行时字段以 [AutoSwitchLocalPlanStore] 为准）。 */
object AutoSwitchBundleHolder {
    private const val CACHE_FILE_NAME = "auto_switch_bundle_cache.json"
    private val gson = Gson()
    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")

    @Volatile
    private var bundle: AutoSwitchBundle? = null

    @Volatile
    private var cacheFile: File? = null

    fun init(context: Context) {
        cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        bundle = loadCachedBundle()
    }

    fun setBundle(value: AutoSwitchBundle?) {
        bundle = value
        if (value != null) {
            persistCache(value)
        }
    }

    fun getBundle(): AutoSwitchBundle? = bundle

    fun loadCachedBundle(): AutoSwitchBundle? {
        val file = cacheFile ?: return null
        if (!file.isFile) return null
        return runCatching {
            gson.fromJson(file.readText(), AutoSwitchBundle::class.java)
        }.getOrNull()
    }

    private fun persistCache(value: AutoSwitchBundle) {
        val file = cacheFile ?: return
        runCatching { file.writeText(gson.toJson(value)) }
    }

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
        AutoSwitchLocalPlanStore.notifyAccountLoggedIn(uname)
        refreshFromPlan()
    }

    fun notifyBasicProfileModified(username: String) {
        val uname = username.trim()
        if (uname.isEmpty()) return
        AutoSwitchLocalPlanStore.notifyBasicProfileModified(uname)
        refreshFromPlan()
    }

    fun notifyUsernameChanged(oldUsername: String, newUsername: String) {
        val oldUser = oldUsername.trim()
        val newUser = newUsername.trim()
        if (oldUser.isEmpty() || newUser.isEmpty()) return
        if (oldUser.equals(newUser, ignoreCase = true)) return
        AutoSwitchLocalPlanStore.notifyUsernameChanged(oldUser, newUser)
        refreshFromPlan()
    }

    /** 改邮后同步内存/落盘 bundle 中对应账号的邮箱凭据，避免自治回退仍用旧邮箱。 */
    fun notifyAccountEmailUpdated(
        username: String,
        email: String,
        emailPassword: String,
        clearApi: Boolean = true,
        mailModuleCodeAvailable: Boolean? = null,
    ) {
        val uname = username.trim().removePrefix("@")
        if (uname.isEmpty()) return
        val current = bundle ?: return
        val accounts = current.accounts.orEmpty()
        if (accounts.isEmpty()) return
        var changed = false
        val nextAccounts = accounts.map { acc ->
            val u = acc.username?.trim()?.removePrefix("@").orEmpty()
            if (!u.equals(uname, ignoreCase = true)) {
                acc
            } else {
                changed = true
                acc.copy(
                    email = email.trim().takeIf { it.isNotEmpty() },
                    email_password = emailPassword,
                    api = if (clearApi) null else acc.api,
                    mail_module_code_available = mailModuleCodeAvailable
                        ?: (email.trim().isNotEmpty()),
                )
            }
        }
        if (!changed) return
        setBundle(current.copy(accounts = nextAccounts))
    }

    private fun refreshFromPlan() {
        val current = bundle ?: return
        bundle = AutoSwitchLocalPlanStore.applyRuntimeToBundle(current)
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
                    val mut = slot.auto_mutual_minute
                    "$idx:$ri:$start-$end:p$pub:m$mut"
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

/** 仅账号 row + username 的幂等键（云端重下发时：账号一致则幂等，否则走重新配置）。 */
fun AutoSwitchBundle.accountsFingerprint(): String =
    accounts.orEmpty()
        .mapNotNull { acc ->
            val ri = acc.row_index ?: return@mapNotNull null
            val user = acc.username?.trim().orEmpty().lowercase(Locale.US)
            if (user.isEmpty()) return@mapNotNull null
            "$ri:$user"
        }
        .sorted()
        .joinToString("|")

fun accountsFingerprint(accounts: List<LocalPlanAccount>): String =
    accounts
        .mapNotNull { acc ->
            val user = acc.username.trim().lowercase(Locale.US)
            if (user.isEmpty()) return@mapNotNull null
            "${acc.row_index}:$user"
        }
        .sorted()
        .joinToString("|")

fun AutoSwitchBundle.isAccountsEquivalentTo(other: AutoSwitchBundle?): Boolean {
    if (other == null) return false
    return accountsFingerprint() == other.accountsFingerprint()
}

fun AutoSwitchBundle.isAccountsEquivalentTo(planAccounts: List<LocalPlanAccount>): Boolean =
    accountsFingerprint() == accountsFingerprint(planAccounts)

fun AutoSwitchBundle.isScheduleAndAccountsEquivalentTo(other: AutoSwitchBundle?): Boolean {
    if (other == null) return false
    return scheduleAndAccountsFingerprint() == other.scheduleAndAccountsFingerprint()
}

/** bundle 内该账号是否配置了可执行的引流互点（动作开关至少一项）。 */
fun AutoSwitchBundle.isMutualTrafficConfiguredFor(username: String): Boolean {
    val cfg = resolveBundledConfigForUsername(username)?.config ?: return false
    if (cfg.mutual_traffic_follow_only) return true
    return cfg.mutual_traffic_comment ||
        cfg.mutual_traffic_author_follow ||
        cfg.mutual_traffic_followers_follow
}

/** 本账号在 bundle 中预取的主播访问用户名（已归一去 @）。 */
fun AutoSwitchBundle.liveVisitUsernamesFor(username: String): List<String> =
    resolveBundledConfigForUsername(username)?.live_visit_usernames.orEmpty()
        .map { it.trim().removePrefix("@") }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

/** 引流互点设备 OP 类型：仅关注 vs 完整引流（评论/作者区/Followers 按配置组合）。 */
fun AutoSwitchBundle.mutualTrafficOpTypeFor(username: String): String? {
    if (!isMutualTrafficConfiguredFor(username)) return null
    val cfg = resolveBundledConfigForUsername(username)?.config ?: return null
    return if (cfg.mutual_traffic_follow_only) {
        "mutual_click_follow_only_start"
    } else {
        "mutual_click_start"
    }
}
