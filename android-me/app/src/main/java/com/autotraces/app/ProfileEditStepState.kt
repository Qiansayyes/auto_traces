package com.autotraces.app

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.Locale

/** 修改基本资料三步断点：avatar → name → bio（bio 含 ①② 子阶段）。 */
enum class ProfileEditResumeStep {
    AVATAR,
    NAME,
    BIO,
    /** Bio 后① 已成功、仅续跑②（Privacy/Viewers）。 */
    BIO_PART2_ONLY,
    DONE,
}

/**
 * 按账号持久化的改资料步骤进度（外轮/会话间续接）。
 *
 * 步骤完成判定点（断点续接用）：
 * - avatarOk：发帖后 OCR 已见「Change photo」（含等待阶段命中或 7 次校验通过）
 * - nameOk：Name→Save→Confirm 后 OCR 已见「Change photo」
 * - bioOk：bioAfterPart1Ok && bioAfterPart2Ok
 *
 * 整体「基本资料已修改」成功：仅 avatarOk（外轮全部结束后汇总判定；头像成功后仍尽量完成 name/bio）。
 *
 * 续接规则（[resolveResumeStep]）：
 * - avatar 已放弃 → 本 OCR 链内从 name 开始（外轮重试前 [resetAbandonedForOuterRetry] 会清除 abandoned）
 * - name 已放弃 → 本 OCR 链内从 bio 开始（外轮重试前同上）
 * - bio 已放弃 → 不再改资料（DONE）
 * - bioPart1Ok 且未完成 bio → 仅续跑 bio 后②
 * - 否则从第一个未完成且未放弃的步骤开始
 */
data class ProfileEditStepState(
    @SerializedName("username") val username: String = "",
    @SerializedName("avatar_ok") val avatarOk: Boolean = false,
    @SerializedName("name_ok") val nameOk: Boolean = false,
    @SerializedName("bio_ok") val bioOk: Boolean = false,
    @SerializedName("bio_part1_ok") val bioPart1Ok: Boolean = false,
    @SerializedName("avatar_abandoned") val avatarAbandoned: Boolean = false,
    @SerializedName("name_abandoned") val nameAbandoned: Boolean = false,
    @SerializedName("bio_abandoned") val bioAbandoned: Boolean = false,
) {
    fun resolveResumeStep(): ProfileEditResumeStep = when {
        bioAbandoned -> ProfileEditResumeStep.DONE
        avatarAbandoned -> when {
            nameAbandoned -> when {
                bioPart1Ok && !bioOk -> ProfileEditResumeStep.BIO_PART2_ONLY
                !bioOk -> ProfileEditResumeStep.BIO
                else -> ProfileEditResumeStep.DONE
            }
            !nameOk -> ProfileEditResumeStep.NAME
            bioPart1Ok && !bioOk -> ProfileEditResumeStep.BIO_PART2_ONLY
            !bioOk -> ProfileEditResumeStep.BIO
            else -> ProfileEditResumeStep.DONE
        }
        nameAbandoned -> when {
            bioPart1Ok && !bioOk -> ProfileEditResumeStep.BIO_PART2_ONLY
            !bioOk -> ProfileEditResumeStep.BIO
            else -> ProfileEditResumeStep.DONE
        }
        !avatarOk -> ProfileEditResumeStep.AVATAR
        !nameOk -> ProfileEditResumeStep.NAME
        bioPart1Ok && !bioOk -> ProfileEditResumeStep.BIO_PART2_ONLY
        !bioOk -> ProfileEditResumeStep.BIO
        else -> ProfileEditResumeStep.DONE
    }

    /** 外轮重试前：未 ok 的步骤清除 abandoned，允许再试；已 ok 的步骤保持。 */
    fun resetAbandonedForOuterRetry(): ProfileEditStepState = copy(
        avatarAbandoned = if (avatarOk) avatarAbandoned else false,
        nameAbandoned = if (nameOk) nameAbandoned else false,
    )
}

object ProfileEditStepStateStore {
    private val gson = Gson()

    private fun prefsKey(username: String): String =
        username.trim().lowercase(Locale.US)

    fun load(username: String): ProfileEditStepState {
        val user = username.trim()
        if (user.isEmpty()) return ProfileEditStepState()
        val json = PrefsStore.getProfileEditStepStateJson(prefsKey(user)) ?: return ProfileEditStepState(username = user)
        return runCatching { gson.fromJson(json, ProfileEditStepState::class.java) }
            .getOrNull()
            ?.copy(username = user)
            ?: ProfileEditStepState(username = user)
    }

    fun save(state: ProfileEditStepState) {
        val user = state.username.trim()
        if (user.isEmpty()) return
        PrefsStore.setProfileEditStepStateJson(prefsKey(user), gson.toJson(state))
    }

    fun clear(username: String) {
        val user = username.trim()
        if (user.isEmpty()) return
        PrefsStore.clearProfileEditStepStateJson(prefsKey(user))
    }

    /** 外轮 OCR 链重试前调用：Avatar/Name abandoned 不跨外轮保留（avatarOk/nameOk 仍持久化）。 */
    fun resetAbandonedForOuterRetry(username: String) {
        val user = username.trim()
        if (user.isEmpty()) return
        val loaded = load(user)
        val updated = loaded.resetAbandonedForOuterRetry()
        if (updated != loaded) save(updated)
    }
}
