package com.autotraces.app

import android.content.Context
import com.autotraces.app.autoswitch.AutoSwitchBundleStore

/**
 * [AutoSwitchBundleStore] 的薄封装，供历史调用点（如 [TraceExecutor]）继续使用旧名字。
 *
 * 所有读写最终都落到 [AutoSwitchBundleStore]：内存 + `filesDir/auto_switch_bundle.json`。
 */
object AutoSwitchBundleHolder {

    /** 见 [AutoSwitchBundleStore.init]。 */
    fun init(context: Context) = AutoSwitchBundleStore.init(context)

    /** 见 [AutoSwitchBundleStore.get]。 */
    fun getBundle(): AutoSwitchBundle? = AutoSwitchBundleStore.get()

    /** 见 [AutoSwitchBundleStore.save]；传 null 等价于 [AutoSwitchBundleStore.clear]。 */
    fun setBundle(value: AutoSwitchBundle?) {
        if (value != null) {
            AutoSwitchBundleStore.save(value)
        } else {
            AutoSwitchBundleStore.clear()
        }
    }

    /** 见 [AutoSwitchBundleStore.loadFromFile]。 */
    fun loadCachedBundle(): AutoSwitchBundle? = AutoSwitchBundleStore.loadFromFile()

    /**
     * 从内存中的 bundle 解析「当前设备应使用的 TikTok 账号」凭证（邮箱/密码等）。
     *
     * 查找顺序：
     * 1. [preferredUsername] 精确匹配（忽略大小写）
     * 2. [logContextUsername] 精确匹配
     * 3. bundle 里第一个账号
     *
     * @return 找不到账号或 username 为空时 null；凭证不完整时 code != 0。
     */
    fun resolveAssignedAccount(
        preferredUsername: String? = null,
        logContextUsername: String? = null,
    ): DeviceAssignedAccountResponse? {
        val bundle = getBundle() ?: return null
        val accounts = bundle.accounts.orEmpty()
        if (accounts.isEmpty()) return null

        val preferred = preferredUsername?.trim()?.takeIf { it.isNotEmpty() }
        val logUser = logContextUsername?.trim()?.takeIf { it.isNotEmpty() }
        val account = when {
            preferred != null -> accounts.firstOrNull {
                it.username?.trim().equals(preferred, ignoreCase = true)
            }
            PrefsStore.isAutoSwitchAutonomousMode() -> {
                bundle.resolveCurrentSlotTargetAccount(beijingDateStr(), beijingMinuteOfDay())
                    ?: logUser?.let { bundle.findAccount(it) }
                    ?: accounts.firstOrNull()
            }
            logUser != null -> accounts.firstOrNull {
                it.username?.trim().equals(logUser, ignoreCase = true)
            }
            else -> accounts.firstOrNull()
        } ?: return null

        val username = account.username?.trim().orEmpty()
        if (username.isEmpty()) {
            return DeviceAssignedAccountResponse(code = 1, msg = "账号缺少 username")
        }
        val hasCreds = !account.email.isNullOrBlank() && !account.password.isNullOrBlank()
        return DeviceAssignedAccountResponse(
            code = if (hasCreds) 0 else 1,
            msg = if (hasCreds) "ok" else "凭证不完整",
            row_index = account.account_row_index ?: account.row_index,
            username = username,
            email = account.email?.trim().orEmpty(),
            password = account.password?.trim().orEmpty(),
            email_password = account.email_password?.trim().orEmpty(),
            api = account.api?.trim().orEmpty(),
            is_login = account.is_login == true,
            login_time = account.login_time?.trim().orEmpty(),
            basic_profile_modified = account.basic_profile_modified?.trim().orEmpty(),
            mail_module_code_available = account.mail_module_code_available == true,
        )
    }

    fun notifyAccountLoggedIn(username: String) {
        val uname = username.trim().removePrefix("@")
        if (uname.isEmpty()) return
        AutoSwitchLocalPlanStore.refreshDeviceActiveFromRuntime(uname, "账号登录成功")
    }

    /** 占位：资料修改回写 plan 由后续调度器实现。 */
    fun notifyBasicProfileModified(username: String) = Unit
}
