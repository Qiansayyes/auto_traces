package com.autotraces.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * android-simple 手动操控模式：任务仅由设备端按钮触发，
 * 云端涨粉 / 引流互点 / 发作品 / 改资料 / 切号 / 云端自动切号事件一律忽略。
 * 本机自动切号自治由 APP 开关 + [ManualAutoSwitchPlanBuilder] 本地计划驱动。
 */
object ManualDeviceControl {
    /** 本机手动模式（运行时可切换；false 时走云端 client 行为）。 */
    val ENABLED: Boolean
        get() = PrefsStore.isManualControlMode()

    val CLOUD_TASK_OP_TYPES: Set<String> = setOf(
        "mutual_click_start",
        "mutual_click_comment_only_start",
        "mutual_click_follow_only_start",
        "publish_post_start",
        "tiktok_edit_basic_profile",
        "live_visit_start",
        "tiktok_backup_and_clear",
        "tiktok_clear",
        "tiktok_restore",
        "tiktok_force_stop",
        "tiktok_force_stop_1",
        "tiktok_force_stop_2",
        "tiktok_force_stop_3",
        "tiktok_launch",
        "tiktok_launch_1",
        "tiktok_launch_2",
        "tiktok_launch_3",
        "batch_stream_start",
        "batch_stream_stop",
    )

    private val cachedAccounts = AtomicReference<List<AccountOption>>(emptyList())

    fun isLocalManualOpId(opId: String): Boolean =
        opId.trim().startsWith("local-manual-")

    fun shouldIgnoreCloudTaskOp(opType: String, opId: String): Boolean {
        if (!ENABLED) return false
        if (isLocalManualOpId(opId)) return false
        return opType.trim() in CLOUD_TASK_OP_TYPES
    }

    fun newLocalManualOpId(kind: String): String =
        "local-manual-$kind-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}"

    fun updateCachedAccounts(accounts: List<AccountOption>) {
        cachedAccounts.set(accounts.toList())
        PrefsStore.setManualAccountsCacheJson(encodeAccounts(accounts))
    }

    /** 从本机缓存移除账号（封禁移出计划后立即生效，无需等下次云端拉取）。 */
    fun removeCachedAccount(username: String): Boolean {
        val key = username.trim().removePrefix("@").lowercase()
        if (key.isEmpty()) return false
        val current = getCachedAccounts()
        val next = current.filterNot {
            it.username.trim().removePrefix("@").lowercase() == key
        }
        if (next.size == current.size) return false
        updateCachedAccounts(next)
        return true
    }

    /** 本机缓存同步云端 2FA 密钥（旧缓存缺字段时从列表补全）。 */
    fun updateCachedAccountTwoFa(username: String, twoFa: String): Boolean {
        val key = username.trim().removePrefix("@").lowercase()
        if (key.isEmpty()) return false
        val secret = twoFa.trim()
        if (secret.isEmpty()) return false
        val current = getCachedAccounts()
        var changed = false
        val next = current.map { opt ->
            if (opt.username.trim().removePrefix("@").lowercase() != key) {
                opt
            } else {
                changed = true
                opt.copy(twoFa = secret)
            }
        }
        if (!changed) return false
        updateCachedAccounts(next)
        return true
    }

    /** 本机缓存同步云端邮箱字段（改邮后 update_email 成功回调）。 */
    fun updateCachedAccountEmail(
        username: String,
        email: String,
        emailPassword: String,
        clearApi: Boolean = true,
        mailModuleCodeAvailable: Boolean? = null,
    ): Boolean {
        val key = username.trim().removePrefix("@").lowercase()
        if (key.isEmpty()) return false
        val current = getCachedAccounts()
        var changed = false
        val next = current.map { opt ->
            if (opt.username.trim().removePrefix("@").lowercase() != key) {
                opt
            } else {
                changed = true
                opt.copy(
                    email = email.trim(),
                    emailPassword = emailPassword,
                    api = if (clearApi) "" else opt.api,
                    mailModuleCodeAvailable = mailModuleCodeAvailable
                        ?: (email.trim().isNotEmpty()),
                )
            }
        }
        if (!changed) return false
        updateCachedAccounts(next)
        return true
    }

    fun getCachedAccounts(): List<AccountOption> {
        val mem = cachedAccounts.get()
        if (mem.isNotEmpty()) return mem
        val restored = decodeAccounts(PrefsStore.getManualAccountsCacheJson())
        if (restored.isNotEmpty()) {
            cachedAccounts.set(restored)
        }
        return restored
    }

    fun findCachedAccount(username: String): AccountOption? {
        val key = username.trim().removePrefix("@").lowercase()
        if (key.isEmpty()) return null
        return getCachedAccounts().firstOrNull {
            it.username.trim().removePrefix("@").lowercase() == key
        }
    }

    /** 下拉选中的目标账号（切号目标 / 任务操作对象）。 */
    fun selectedManualUsername(): String =
        PrefsStore.getManualSelectedUsername().orEmpty()
            .ifBlank { PrefsStore.getLogContextUsername().orEmpty() }
            .trim()
            .removePrefix("@")

    /** 本机当前已切上的账号（切号备份源）；未切过则回退 logContext。 */
    fun activeManualUsername(): String =
        PrefsStore.getManualActiveUsername().orEmpty()
            .ifBlank { PrefsStore.getLogContextUsername().orEmpty() }
            .trim()
            .removePrefix("@")

    @Deprecated("使用 selectedManualUsername / activeManualUsername", ReplaceWith("selectedManualUsername()"))
    fun currentManualUsername(): String = selectedManualUsername()

    fun isFirstLoginDoneForCurrent(): Boolean {
        val u = selectedManualUsername()
        return u.isNotBlank() && PrefsStore.isManualFirstLoginDone(u)
    }

    data class AccountOption(
        val username: String,
        val password: String = "",
        val email: String = "",
        val emailPassword: String = "",
        val twoFa: String = "",
        val api: String = "",
        val backupPath: String = "",
        val backupName: String = "",
        val rowIndex: Int? = null,
        val cloudIsLogin: Boolean? = null,
        val basicProfileModified: String = "否",
        val mailModuleCodeAvailable: Boolean = false,
        val switchHighlight: String = "否",
        /** 云端勾了「不参加自动切号」；切号下拉/待活跃仍可见，本机自动切号计划会跳过。 */
        val autoSwitchExcluded: Boolean = false,
        /** 云端粉丝量（用于下拉展示与降序排序）。 */
        val followerCount: Int = 0,
    )

    data class SwitchAccountStatus(
        val username: String,
        val firstLoginDone: Boolean,
        val localBackupExists: Boolean,
        val localBackupName: String?,
        val cloudIsLogin: Boolean?,
        val message: String,
    )

    /** password / email / email_password 任一为封禁标记（旧整字段 banned 或后缀 ----banned）。 */
    fun accountLooksBanned(opt: AccountOption): Boolean =
        isBannedMarker(opt.password) || isBannedMarker(opt.emailPassword) || isBannedMarker(opt.email)

    /** 登录次数上限标记（后缀 ----Maximum login）。 */
    fun accountLooksMaxLogin(opt: AccountOption): Boolean =
        isMaxLoginMarker(opt.password) || isMaxLoginMarker(opt.emailPassword) || isMaxLoginMarker(opt.email)

    fun accountLooksCredBlocked(opt: AccountOption): Boolean =
        accountLooksBanned(opt) || accountLooksMaxLogin(opt)

    fun isBannedMarker(value: String?): Boolean {
        val s = value?.trim().orEmpty()
        if (s.equals("banned", ignoreCase = true)) return true
        return s.contains("----banned", ignoreCase = true)
    }

    fun isMaxLoginMarker(value: String?): Boolean {
        val s = value?.trim().orEmpty()
        return s.contains("----Maximum login", ignoreCase = true)
    }

    fun parseFollowerCount(raw: String?): Int {
        val text = raw?.trim()?.replace(",", "")?.replace(" ", "").orEmpty()
        if (text.isEmpty()) return 0
        return text.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
    }

    fun fromAssignedItem(item: DeviceAssignedAccountItem): AccountOption? {
        val username = item.username?.trim()?.removePrefix("@").orEmpty()
        if (username.isEmpty()) return null
        val opt = AccountOption(
            username = username,
            password = item.password?.trim().orEmpty(),
            email = item.email?.trim().orEmpty(),
            emailPassword = item.email_password?.trim().orEmpty(),
            twoFa = item.two_fa?.trim().orEmpty(),
            api = item.api?.trim().orEmpty(),
            backupPath = item.backup_path?.trim().orEmpty(),
            backupName = item.backup_name?.trim().orEmpty(),
            rowIndex = item.row_index ?: item.account_row_index,
            cloudIsLogin = item.is_login,
            basicProfileModified = item.basic_profile_modified?.trim().orEmpty().ifBlank { "否" },
            mailModuleCodeAvailable = item.mail_module_code_available == true,
            switchHighlight = item.switch_highlight?.trim().orEmpty().ifBlank { "否" },
            autoSwitchExcluded = item.auto_switch_excluded == true,
            followerCount = parseFollowerCount(item.follower_count),
        )
        return opt
    }

    fun fromAssignedResponse(resp: DeviceAssignedAccountResponse): AccountOption? {
        val username = resp.username?.trim()?.removePrefix("@").orEmpty()
        if (username.isEmpty()) return null
        val opt = AccountOption(
            username = username,
            password = resp.password?.trim().orEmpty(),
            email = resp.email?.trim().orEmpty(),
            emailPassword = resp.email_password?.trim().orEmpty(),
            twoFa = resp.two_fa?.trim().orEmpty(),
            api = resp.api?.trim().orEmpty(),
            rowIndex = resp.row_index,
            cloudIsLogin = resp.is_login,
            basicProfileModified = resp.basic_profile_modified?.trim().orEmpty().ifBlank { "否" },
            mailModuleCodeAvailable = resp.mail_module_code_available == true,
        )
        if (accountLooksCredBlocked(opt)) return null
        return opt
    }

    private fun encodeAccounts(accounts: List<AccountOption>): String {
        val arr = JSONArray()
        for (a in accounts) {
            arr.put(
                JSONObject().apply {
                    put("username", a.username)
                    put("password", a.password)
                    put("email", a.email)
                    put("emailPassword", a.emailPassword)
                    put("twoFa", a.twoFa)
                    put("api", a.api)
                    put("backupPath", a.backupPath)
                    put("backupName", a.backupName)
                    put("rowIndex", a.rowIndex ?: JSONObject.NULL)
                    put("cloudIsLogin", a.cloudIsLogin ?: JSONObject.NULL)
                    put("basicProfileModified", a.basicProfileModified)
                    put("mailModuleCodeAvailable", a.mailModuleCodeAvailable)
                    put("switchHighlight", a.switchHighlight)
                    put("autoSwitchExcluded", a.autoSwitchExcluded)
                    put("followerCount", a.followerCount)
                },
            )
        }
        return arr.toString()
    }

    private fun decodeAccounts(json: String?): List<AccountOption> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val username = o.optString("username").trim().removePrefix("@")
                    if (username.isEmpty()) continue
                    val opt = AccountOption(
                        username = username,
                        password = o.optString("password"),
                        email = o.optString("email"),
                        emailPassword = o.optString("emailPassword"),
                        twoFa = o.optString("twoFa"),
                        api = o.optString("api"),
                        backupPath = o.optString("backupPath"),
                        backupName = o.optString("backupName"),
                        rowIndex = o.optInt("rowIndex").takeIf { o.has("rowIndex") && !o.isNull("rowIndex") },
                        cloudIsLogin = when {
                            !o.has("cloudIsLogin") || o.isNull("cloudIsLogin") -> null
                            else -> o.optBoolean("cloudIsLogin")
                        },
                        basicProfileModified = o.optString("basicProfileModified").ifBlank { "否" },
                        mailModuleCodeAvailable = o.optBoolean("mailModuleCodeAvailable"),
                        switchHighlight = o.optString("switchHighlight").ifBlank { "否" },
                        autoSwitchExcluded = o.optBoolean("autoSwitchExcluded"),
                        followerCount = o.optInt("followerCount", 0).coerceAtLeast(0),
                    )
                    if (accountLooksCredBlocked(opt)) continue
                    add(opt)
                }
            }
        }.getOrDefault(emptyList())
    }
}
