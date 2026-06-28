package com.autotraces.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.autotraces.app.backup.DeviceBackupShell
import com.autotraces.app.util.Config
import com.autotraces.app.videoded.CompanionApi
import com.autotraces.app.videoded.TaskDownloadWorkflow
import com.autotraces.app.videoded.TaskFileSnapshot
import com.autotraces.app.videoded.TaskSnapshot
import com.autotraces.app.videoded.TaskStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.concurrent.thread
import kotlin.coroutines.resumeWithException
import kotlin.jvm.Volatile
import kotlin.random.Random

class GrowthFlowRestartRequired(message: String) : RuntimeException(message)

/** 互点/发作品等路径在登录状态保障阶段需自动登录时返回此常量，由协调器触发登录。 */
const val SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE = "__SAFEGUARD_REQUIRES_AUTO_LOGIN__"

/** TikTok「Account status / 已登出」弹窗：已在设备端点 OK、强停并冷启，需中止当前步骤并重新自动登录。 */
class AccountStatusLoggedOutException(message: String) : RuntimeException(message)

/** 登录状态保障判定需自动登录，由协调器从当前 Profile 登录入口续接（勿再冷启 TikTok）。 */
class LoginSafeguardRequiresAutoLoginException : RuntimeException(SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE)

/** 登录状态保障：点 Profile 后根据 OCR 判定 TikTok 界面是否处于可继续任务的登录态（与云端 `is_login` 无关）。 */
enum class LoginSafeguardOutcome {
    /** 界面判定可继续（如已见 Edit），并已点底栏 Home。 */
    Ready,
    /** 界面见 Log in / Sign up / 登录表单等，需走自动登录链。 */
    RequiresAutoLogin,
    Failed,
}

/** 自动登录流程变体：`Default` 强制重登等；`CutSwitchAfterRestore` 云端 is_login=否（入口标记；Email 后 Inbox 等分流对所有流程统一生效）。 */
enum class AutoLoginFlow {
    Default,
    CutSwitchAfterRestore,
}

/**
 * 管理台远控「修改账号基本资料」：比例点之后的 OCR+点击链路结果（写入设备操作回传 data）。
 */
data class ProfileEditChainResult(
    val success: Boolean,
    val failureStep: String = "",
    val editOcrAttempts: Int = 0,
    val changeOcrAttempts: Int = 0,
    val editOcrPreviews: List<String> = emptyList(),
    val changeOcrPreviews: List<String> = emptyList(),
    val editTapPx: Pair<Int, Int>? = null,
    /** 仅记录：三项菜单文案 bbox 并集中心（不点击）；兼容旧字段语义 */
    val changeMenuLinePx: Pair<Int, Int>? = null,
    /** 仅记录：独立「Change photo」钳位后中心（不点击该项） */
    val changeMenuChangePhotoPx: Pair<Int, Int>? = null,
    /** 仅记录：独立「Name」/「name」钳位后中心 */
    val changeMenuNamePx: Pair<Int, Int>? = null,
    /** 仅记录：独立「Bio」钳位后中心 */
    val changeMenuBioPx: Pair<Int, Int>? = null,
    val changeTapPx: Pair<Int, Int>? = null,
    val editTapOk: Boolean = false,
    val changeTapOk: Boolean = false,
    val changePhotoStrictOcrAttempts: Int = 0,
    val changePhotoStrictTapPx: Pair<Int, Int>? = null,
    val changePhotoStrictTapOk: Boolean = false,
    val uploadPhotoOcrAttempts: Int = 0,
    val uploadPhotoOcrPreviews: List<String> = emptyList(),
    val uploadPhotoTapPx: Pair<Int, Int>? = null,
    val uploadPhotoTapOk: Boolean = false,
    val allowAllOcrAttempts: Int = 0,
    val allowAllOcrPreviews: List<String> = emptyList(),
    val allowAllTapPx: Pair<Int, Int>? = null,
    val allowAllTapOk: Boolean = false,
    /** 发帖后回到资料页：OCR 校验出现「Change photo」的尝试次数（最多 7） */
    val avatarVerifyOcrAttempts: Int = 0,
    /** Confirm 之后：OCR 校验资料页出现「Change photo」的尝试次数（最多 7） */
    val postConfirmChangePhotoOcrAttempts: Int = 0,
    /** Bio 保存后：OCR 终验出现「Change photo」的尝试次数（最多 7） */
    val bioVerifyOcrAttempts: Int = 0,
    /** 随机填入的显示名（成功写入输入框后非空） */
    val displayNameRandomApplied: String = "",
    /** 本次写入 Bio 的模板文案（截断前已完整输入） */
    val profileEditBioApplied: String = "",
    /** Bio 输入后① Save→等 2s→终验「Change photo」→返回 已完成 */
    val bioAfterPart1Ok: Boolean = false,
    /** Bio 后② 比例点→Privacy/Viewers（或 Viewer history）已完成 */
    val bioAfterPart2Ok: Boolean = false,
    /** Bio 剪贴板比对时的分配用户名（改资料开始时） */
    val profileEditAssignedUsername: String = "",
    /** Bio 步骤检测到 TikTok 用户名变更并已同步（ledger/API）后的新用户名；未变更则为空 */
    val profileEditUsernameSyncedTo: String = "",
)

interface TraceExecutor {
    suspend fun executeForUsername(username: String, config: AppConfig): String
    suspend fun restartTikTokForRecovery(skipDontAllowOcr: Boolean = false): Boolean = false

    /**
     * 远控等路径：**拉起 TikTok 且经 dumpsys 校验包名为当前前台**（与主流程一致）。
     * 拉起命令执行后会专用整屏 OCR 拒绝权限弹窗（Don't allow / 取消 / 不允许，至多 3 次、间隔 0.5s），命中则点击，未命中跳过。
     * @param timingConfig 非 null 时写入用于本次拉起的「强停等待 / 拉起后等待」（毫秒）等快照，再走与主流程一致 intent → root → dumpsys 前台校验链路。
     */
    suspend fun launchTikTokWithForegroundVerification(
        ctx: Context,
        alias: String,
        tag: String,
        forceStopBeforeLaunch: Boolean,
        timingConfig: AppConfig? = null,
    ): Boolean

    /**
     * 假定 TikTok 已在前台且刚完成「资料入口」比例点点击：OCR 严格找「Edit」坐标→点击「Edit」→
     * OCR 在同一屏分别找「Change photo」「Name」「Bio」三词坐标并写日志（不点击）→
     * 同屏再严格找「Change photo」并首击（若无则整屏专段 OCR）→
     * 整屏「Upload photo」或「Select from Gallery」→「Allow all」（未命中但已见 Recents 则跳过 Allow）→OCR「Recents」稳定（6×0.5s，仅探测）→
     * 左上固定点→OCR「Next」×2（间隔 0.5s）→「Post this photo to Story」（至多 3 次，未命中跳过）→「Save」→
     * OCR 等待头像上传（含 uploading 则每 1s 轮询至消失；已见 Change photo 则跳过等待；逾时 60s 强停并重拉 TikTok）→
     * 最多 7 次 OCR 校验「Change photo」→Name→Bio 前 OCR 定位「TikTok」文案并点击复制（至多 3 次）→
     * Bio(模板；若已复制则先粘贴+uiautomator 读 @ 后与分配账号比对，不一致则上报云端更新 username)→
     * **Bio 后两段（各自至多 3 轮）**：① Save→等 2s→终验「Change photo」→返回（任一步失败只重试①）；
     * ② 设置点→点 Settings and privacy→【插入】点 Permissions→OCR 点 Save login info(等2.5s)→返回→【旧】校验 Permissions→点其下 Privacy→Comments 校验→下滑→Viewers→…
     * 下滑→OCR「Viewers」（找不到再下滑再 OCR）→同行 On 则成功 / Off 则点 Viewers→OCR「Viewer history」并点（7 次）→等 3s
     * （② 失败且① 已成功则只重试②：强停→拉起→资料比例点 (0.97,0.95)→再跑②，不重复 Save/终验/返回）。**此时整条链成功结束**。
     *
     * [runtimeConfig] 须为当前云端运行配置（含 `profile_edit_bio_texts`）。本链**不**依赖 [executeForUsername] 写入的 lastConfigSnapshot。
     */
    suspend fun profileEditRunOcrChain(ctx: Context, runtimeConfig: AppConfig): ProfileEditChainResult

    /**
     * 修改基本资料外轮：假定拉起后已在 For You，刷两下视频（与主流程 For You 预滑同向；无点赞、不跑完整预滑循环）。
     * 每次滑后 delay 450ms。硬停返回 false。
     */
    suspend fun profileEditForYouTwoSwipesAfterLaunch(logAlias: String, w: Int, h: Int): Boolean

    /**
     * `Pictures` 目录树下图片文件数（su + find 递归，扩展名 jpg/jpeg/png/webp/gif；
     * 与 adb `find /storage/emulated/0/Pictures … | wc -l` 一致）。失败返回 -1。
     */
    fun countPicturesGalleryImageFiles(): Int

    /**
     * 删除 `Pictures` 下全部媒体：MediaStore 索引 + 磁盘文件（含 .thumbnails）+ 媒体库扫描。
     */
    suspend fun clearDevicePicturesGallery(alias: String)

    /**
     * TikTok 自动登录（登录状态保障触发、切号后等；调用方须先暂停拉粉/执行任务）。
     * @param resumeFromLoginEntryScreen true：调用方已通过登录状态保障打开 Profile 登录入口，从 [autoLoginResolveEntryPath] 续接，不再冷启/重复预处理。
     * @return true 表示登录链完成；false 表示失败或硬停。
     */
    suspend fun runAutoLoginAfterCutSwitch(
        ctx: Context,
        credentials: TikTokAutoLoginCredentials,
        resumeFromLoginEntryScreen: Boolean = false,
        flowKind: AutoLoginFlow = AutoLoginFlow.Default,
    ): Boolean

    /**
     * 登录状态保障：TikTok 已在前台时，点 Profile → OCR 判定界面登录态；可继续则点底栏 Home。
     * 调用方：切号完成、云端下发涨粉模式、互点/发作品冷启等。
     * 不含：每用户名任务开始、修改基本资料外轮、云端设备指令前、每轮涨粉拉取前。
     */
    suspend fun runLoginSafeguardMeasure(ctx: Context, sourceTag: String): LoginSafeguardOutcome
}

class RealTraceExecutor(
    private val appContextProvider: () -> Context,
) : TraceExecutor {

    private fun isHardStopActive(): Boolean = PrefsStore.isHardStopRequested()

    private fun shouldSwitchToMutualClick(): Boolean =
        PrefsStore.isMutualClickRequested() && PrefsStore.isMutualClickPending()

    private fun hardStopOrNull(alias: String, action: String): Boolean {
        if (!isHardStopActive()) return false
        devOp(alias, "$action：检测到硬停，直接跳过")
        return true
    }

    private fun isExclusivePreemptActive(): Boolean = PrefsStore.isAutoSwitchExclusivePreempt()

    /** 切号/发作品等独占 OP 已抢占 TikTok：涨粉应协作退出且不再拉起 TikTok。 */
    private fun exclusivePreemptOrNull(alias: String, action: String): Boolean {
        if (!isExclusivePreemptActive()) return false
        devOp(alias, "$action：自治独占已抢占 TikTok，协作退出")
        return true
    }

    /** 涨粉/互点长流程协作让出（硬停、自动切号关闭或独占抢占）。 */
    private fun growthYieldOrNull(alias: String, action: String): Boolean {
        if (hardStopOrNull(alias, action)) return true
        if (PrefsStore.getAutoSwitchMode() == "exiting") {
            devOp(alias, "$action：自动切号关闭中，协作退出")
            return true
        }
        return exclusivePreemptOrNull(alias, action)
    }

    private fun growthYieldSkipMessage(username: String): String =
        when {
            isHardStopActive() -> "已硬停，跳过 $username"
            PrefsStore.getAutoSwitchMode() == "exiting" -> "自动切号关闭中，跳过 $username"
            else -> "独占操作抢占，跳过 $username"
        }

    /** 独占抢占或自动切号关闭时顺带 force-stop，避免涨粉步骤退出后 TikTok 仍留前台。 */
    private fun forceStopTikTokIfExclusivePreempt(alias: String, phase: String) {
        if (!isExclusivePreemptActive() && PrefsStore.getAutoSwitchMode() != "exiting") return
        val pkg = Config.TIKTOK_PACKAGE_NAME
        runSuCommand(DeviceBackupShell.forceStopCommand(pkg))
        runSuCommand(DeviceBackupShell.supplementalKillCommand(pkg))
        devOp(alias, "$phase：独占抢占协作 force-stop $pkg")
    }

    private fun isMlKitHardStopError(err: String): Boolean = err == "hard_stop"

    /** 发作品步骤失败收尾：硬停不重启；否则可选重启 TikTok（不发作品冷启专用 Don't allow OCR）。恒返回 false。 */
    private suspend fun publishPostFail(alias: String, phase: String, tryRestart: Boolean): Boolean {
        if (hardStopOrNull(alias, phase)) return false
        if (tryRestart) restartTikTokForRecovery(skipDontAllowOcr = true)
        return false
    }

    /** 可中断等待；硬停或自动切号关闭时返回 false。 */
    private suspend fun profileEditDelay(logAlias: String, ms: Long, phase: String): Boolean {
        var left = ms
        while (left > 0) {
            if (hardStopOrNull(logAlias, "修改基本资料-$phase")) return false
            if (PrefsStore.getAutoSwitchMode() == "exiting") return false
            coroutineContext.ensureActive()
            val chunk = minOf(left, 200L)
            delay(chunk)
            left -= chunk
        }
        return true
    }

    private suspend fun growthDelay(alias: String, ms: Long, phase: String): Boolean {
        var left = ms.coerceAtLeast(0L)
        while (left > 0) {
            if (growthYieldOrNull(alias, phase)) {
                forceStopTikTokIfExclusivePreempt(alias, phase)
                return false
            }
            coroutineContext.ensureActive()
            val chunk = minOf(left, 200L)
            delay(chunk)
            left -= chunk
        }
        return true
    }

    private fun profileEditHardStopChainResult(
        editOcrAttempts: Int = 0,
        editOcrPreviews: List<String> = emptyList(),
        changeOcrAttempts: Int = 0,
        changeOcrPreviews: List<String> = emptyList(),
        bioAfterPart1Ok: Boolean = false,
        profileEditBioApplied: String = "",
    ) = ProfileEditChainResult(
        success = false,
        failureStep = "hard_stop",
        editOcrAttempts = editOcrAttempts,
        changeOcrAttempts = changeOcrAttempts,
        editOcrPreviews = editOcrPreviews,
        changeOcrPreviews = changeOcrPreviews,
        bioAfterPart1Ok = bioAfterPart1Ok,
        bioAfterPart2Ok = false,
        profileEditBioApplied = profileEditBioApplied,
    )

    private fun mutualClickOrNull(alias: String, action: String): Boolean {
        if (!shouldSwitchToMutualClick()) return false
        devOp(alias, "$action：检测到互点切换请求，准备在合适位置收尾")
        return true
    }

    private inline fun <T> withTikTokGuard(alias: String, action: String, block: () -> T): T? {
        if (growthYieldOrNull(alias, action)) return null
        return block()
    }

    /**
     * @param useMountMaster 为 true 时使用 `su -mm`（Magisk 等：全局挂载命名空间），否则 App 内 root 可能看不到 `/data/data/<其它包>`。
     */
    private data class SuCommand(val path: String, val useShell: Boolean, val useMountMaster: Boolean = false)

    private var cachedSuCommand: SuCommand? = null
    private var lastConfigSnapshot: AppConfig = AppConfig()
    // 只要当前用户名流程中发生过重启 TikTok，下一轮就必须从头启动，禁止复用 Users。
    private var didRestartTikTokInCurrentRun: Boolean = false
    // 仅 growth：上一名 Users 恢复失败后，下一名从“点击放大镜”重新搜索（不复用 Users，不额外强关 TikTok）。
    private var restartFromMagnifierForGrowth: Boolean = false

    /** 最近一次 [runPublishPostEntryFlow] 失败原因（供 [executeForUsername] 返回与 ledger 对账）。 */
    private var lastPublishPostEntryFailureReason: String? = null

    /** 引流：作者区 + Followers 列表 Follow 累计计数（本会话，共用上限）。 */
    private var mutualSessionFollowTapCount: Int = 0

    @Volatile
    private var mutualFollowLimitReached: Boolean = false

    // 自动登录：本条整链内是否已成功点击过 Don't allow（点过则后续无需重复点击）。
    private var autoLoginDontAllowHandledInThisChain: Boolean = false
    @Volatile
    private var lastInlineDontAllowTapAtMs: Long = 0L
    private var lastViewerHistoryPopupDismissAtMs: Long = 0L
    private var lastSaveLoginForNextTimePopupDismissAtMs: Long = 0L
    @Volatile
    private var inlineIdentityVerificationInProgress: Boolean = false
    private var lastInlineIdentityResendTapAtMs: Long = 0L
    @Volatile
    private var accountStatusRecoveryInProgress: Boolean = false

    /** 涨粉 / 互点 / 发作品：该深度 >0 时所有 OCR 须观察掉登录信号；自动登录与改资料除外。 */
    @Volatile
    private var accountStatusOcrWatchDepth: Int = 0

    /** [runLoginSafeguardMeasure] 执行期间：其内所有 OCR（含 auto_login_* 预处理）也探测掉登录信号。 */
    @Volatile
    private var loginSafeguardLogoutProbeActive: Boolean = false

    private fun isLogoutProbeContextActive(): Boolean =
        isAccountStatusOcrWatchActive() || loginSafeguardLogoutProbeActive

    /** 任务执行中掉登录类异常须上浮至 [AppCoordinator]，勿在局部 OCR catch 吞掉。 */
    private fun rethrowTaskLogoutIfNeeded(ex: Throwable) {
        when (ex) {
            is AccountStatusLoggedOutException,
            is LoginSafeguardRequiresAutoLoginException,
            -> throw ex
        }
    }

    private fun isAccountStatusOcrWatchActive(): Boolean = accountStatusOcrWatchDepth > 0

    private suspend inline fun <T> withAccountStatusOcrWatch(block: suspend () -> T): T {
        accountStatusOcrWatchDepth++
        try {
            return block()
        } finally {
            accountStatusOcrWatchDepth = (accountStatusOcrWatchDepth - 1).coerceAtLeast(0)
        }
    }

    /**
     * 自动登录链与修改基本资料 OCR 不观察 Account status（[loginSafeguardLogoutProbeActive] 时除外 auto_login）。
     * 互点/发作品冷启里的 [runLoginSafeguardMeasure] 使用 `login_safeguard_*`。
     */
    private fun isAccountStatusOcrExcludedTag(fileTag: String): Boolean {
        val t = fileTag.trim()
        return t.startsWith("profile_edit") || t.startsWith("auto_login")
    }

    /**
     * 登录状态保障会主动 OCR「Add another account」做判定；任务执行中见该文案则须中止并自动登录。
     * 登录保障期间由 [loginSafeguardLogoutProbeActive] 统一走掉登录探测，不再单独排除 tag。
     */
    private fun isAddAnotherAccountLogoutOcrExcludedTag(fileTag: String): Boolean {
        val t = fileTag.trim()
        return t.startsWith("profile_edit") ||
            t.startsWith("auto_login") ||
            t.startsWith("login_safeguard")
    }

    private fun shouldObserveAccountStatusOnOcr(fileTag: String?): Boolean {
        if (!isLogoutProbeContextActive()) return false
        if (loginSafeguardLogoutProbeActive) {
            return fileTag == null || !fileTag.trim().startsWith("profile_edit")
        }
        if (fileTag != null && isAccountStatusOcrExcludedTag(fileTag)) return false
        return true
    }

    private fun shouldObserveAddAnotherAccountLogoutOnOcr(fileTag: String?): Boolean {
        if (!isLogoutProbeContextActive()) return false
        if (loginSafeguardLogoutProbeActive) {
            return fileTag == null || !fileTag.trim().startsWith("profile_edit")
        }
        if (fileTag != null && isAddAnotherAccountLogoutOcrExcludedTag(fileTag)) return false
        return true
    }

    private suspend fun observeAccountStatusOnOcrText(
        ctx: Context,
        vision: Text,
        fileTag: String?,
    ) {
        if (!isLogoutProbeContextActive()) return
        val alias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        probeAndHandleLogoutSignalsFromVision(ctx, alias, vision, fileTag)
    }

    /** 裁切 OCR 前对整屏截图顺手处理弹窗/验证，并在涨粉等场景下观察掉登录信号。 */
    private suspend fun observeAccountStatusOnScreenshot(
        ctx: Context,
        bitmap: Bitmap,
        fileTag: String,
    ) {
        if (accountStatusRecoveryInProgress) return
        val alias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            var vision = if (!inlineIdentityVerificationInProgress) {
                dismissSwipeUpPromptUntilGone(ctx, result, fileTag, "截图OCR顺手")
            } else {
                result
            }
            if (vision == null) return
            if (!inlineIdentityVerificationInProgress) {
                handleOcrVisionInlineInterruptions(ctx, vision, fileTag, "截图OCR顺手")
            }
            if (!isLogoutProbeContextActive()) return
            probeAndHandleLogoutSignalsFromVision(ctx, alias, vision, fileTag)
        } catch (ex: AccountStatusLoggedOutException) {
            throw ex
        } catch (ex: LoginSafeguardRequiresAutoLoginException) {
            throw ex
        } catch (_: Exception) {
            // OCR 失败时不因掉登录探测阻断主流程
        } finally {
            recognizer.close()
        }
    }

    /** 记录一次 Follow 点击；@return true 表示已达本会话上限。 */
    private fun recordMutualFollowTap(alias: String, config: AppConfig, source: String): Boolean {
        if (mutualFollowLimitReached) return true
        val limit = config.mutual_follow_tap_session_limit.coerceAtLeast(1)
        mutualSessionFollowTapCount++
        PrefsStore.incrementDailyActionCount("mutual_follow")
        devOp(alias, "互点：Follow 会话计数($source) $mutualSessionFollowTapCount/$limit")
        if (mutualSessionFollowTapCount >= limit) {
            mutualFollowLimitReached = true
            devOp(alias, "互点：已达 Follow 会话上限 $limit")
            return true
        }
        return false
    }

    private fun shouldStopMutualVideosAfterFollowLimit(config: AppConfig): Boolean {
        if (!mutualFollowLimitReached) return false
        // 仅关注无评论：Follow 达限即整段会话完成，不必跑满 mutual_videos_scroll_rounds
        if (PrefsStore.isMutualFollowOnlySession()) return true
        return !config.mutual_traffic_comment
    }

    private fun shouldSkipMutualAuthorLayerSwipe(config: AppConfig, mutualCommentOnly: Boolean): Boolean {
        if (PrefsStore.isMutualFollowOnlySession()) return true
        if (mutualCommentOnly) return true
        if (mutualFollowLimitReached) return true
        return !config.mutual_traffic_author_follow && !config.mutual_traffic_followers_follow
    }

    /**
     * 发作品任务内：资料页进图库入口布局。
     * 任务启动时从 [PrefsStore.getPublishPostEntryLayoutForDevice] 预填；试探成功写回本机缓存供跨账号沿用。
     */
    @Volatile
    private var publishPostEntryLayout: PublishPostEntryLayout = PublishPostEntryLayout.UNKNOWN

    private enum class PublishPostEntryLayout {
        /** 未确认：右下固定点 + Recents 试探，必要时走布局 B */
        UNKNOWN,
        /** 右下固定点可进图库 */
        A,
        /** 须点 W/12 + CREATE/POST 的 Y */
        B,
    }

    /** [recoverToHomeViaBackOrRestart] 单次 OCR 判定结果。 */
    private enum class RecoverHomeOcrOutcome {
        OnMainFeed,
        NotOnMainFeed,
        ForegroundUnavailable,
        HardStop,
    }

    private fun publishPostLoadDeviceEntryLayout(alias: String): PublishPostEntryLayout {
        return when (PrefsStore.getPublishPostEntryLayoutForDevice()) {
            "A" -> {
                devOp(alias, "发作品：本机已缓存布局 A（跨账号优先），本任务将直接走布局 A、跳过 A/B 试探")
                PublishPostEntryLayout.A
            }
            "B" -> {
                devOp(alias, "发作品：本机已缓存布局 B（跨账号优先），本任务将直接走布局 B、跳过 A/B 试探")
                PublishPostEntryLayout.B
            }
            else -> PublishPostEntryLayout.UNKNOWN
        }
    }

    private fun publishPostPersistDeviceEntryLayout(alias: String, layout: PublishPostEntryLayout) {
        when (layout) {
            PublishPostEntryLayout.A -> PrefsStore.setPublishPostEntryLayoutForDevice("A")
            PublishPostEntryLayout.B -> PrefsStore.setPublishPostEntryLayoutForDevice("B")
            PublishPostEntryLayout.UNKNOWN -> return
        }
        devOp(alias, "发作品：已写入本机布局缓存（deviceId=${PrefsStore.getDeviceId().orEmpty()}），供同设备其他账号优先沿用")
    }

    private fun publishPostClearDeviceEntryLayout(alias: String) {
        PrefsStore.clearPublishPostEntryLayoutForDevice()
        devOp(alias, "发作品：已清除本机布局缓存，后续将重新试探 A/B")
    }

    companion object {
        private const val FRAME_DIFF_PASS_THRESHOLD = 0.25f
        /** 帧差重试间隔（毫秒），临时固定 5s */
        private const val FRAME_DIFF_RETRY_DELAY_MS = 5000L
        /** Videos 区连续帧差失败时，teamwork 全量恢复的最大轮数（避免死循环） */
        private const val MUTUAL_VIDEOS_FRAME_DIFF_MAX_RECOVERY_CYCLES = 8
        /** 互点入口：force-stop 后重新搜索并进入 Videos 标签的最大尝试次数 */
        private const val MUTUAL_ENTRY_LAUNCH_MAX_ATTEMPTS = 3
        /** 互点：单次进入流程内 Videos 标签 OCR 最大尝试次数 */
        private const val MUTUAL_VIDEOS_TAB_OCR_MAX_ATTEMPTS = 3
        /** 互点：首次点击 Videos 标签后，至二次顶栏 OCR 开始的间隔（毫秒） */
        private const val MUTUAL_VIDEOS_TAB_AFTER_FIRST_TAP_DELAY_MS = 1500L
        /** 帧差失败后单次 teamwork 恢复：关开 TikTok 并重新搜索的最大连续尝试次数 */
        private const val MUTUAL_TEAMWORK_RECOVERY_REOPEN_MAX_ATTEMPTS = 3
        /** 互点-仅关注：Reply / Replying / Follow 布局 OCR 共用重试（6 次、间隔 0.5s） */
        private const val MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS = 6
        private const val MUTUAL_FOLLOW_ONLY_OCR_RETRY_DELAY_MS = 500L
        /** 涨粉搜索页：定位 Users 顶栏标签 OCR 轮数（每轮 fast + 兜底各 1 次，轮间 3s，仍失败再强关重启） */
        private const val USERS_TAB_OCR_MAX_ATTEMPTS = 2
        /** 涨粉搜索页：Users 标签每轮内 fast→兜底 OCR 间隔 */
        private const val USERS_TAB_OCR_FAST_FALLBACK_DELAY_MS = 2_000L
        /** 涨粉搜索页：Users 标签 OCR 轮与轮之间的等待（遮挡/未命中后重试） */
        private const val USERS_TAB_OCR_RETRY_DELAY_MS = 3_000L
        /** 评论区列表上滑约 40% 屏高（换批） */
        private const val MUTUAL_FOLLOW_ONLY_COMMENT_SWIPE_HEIGHT_RATIO = 0.40f
        /** 完整互点：未达 Follow 上限时 teamwork Videos 外圈最大轮数（每轮含左+右双侧），防止死循环。 */
        private const val MUTUAL_FULL_VIDEOS_SAFETY_ROUNDS = 500
        /** 单次「发布作品」任务最多连发条数（临时上限）；实际条数 = min(Pictures 图片+视频数, 本常量)。 */
        private const val PUBLISH_POST_MAX_ITEMS_PER_TASK = 5
        /** 上一条发布成功后切下一条前短等（不冷启 TikTok；界面已在底栏主界面）。 */
        private const val PUBLISH_POST_INTER_ITEM_DELAY_MS = 650L
        /** 同任务同一条作品在内层 3 次失败后，仍在流程内断点续发重试（不整段重入、不任务头冷启）。 */
        private const val PUBLISH_POST_SAME_ITEM_RESUME_MAX_PASSES = 8
        private const val PUBLISH_POST_SAME_ITEM_RESUME_DELAY_MS = 2_000L
        /** 进入创作流后 OCR「Continue」最多次数；均未命中则跳过，不判失败。 */
        private const val PUBLISH_POST_CONTINUE_OCR_MAX_PASSES = 3
        /** 右下固定点后探测「Recents」最多次数（仅识别不点击）。 */
        private const val PUBLISH_POST_RECENTS_OCR_MAX_PASSES = 3
        /** 未见 Recents 时备用入口 X = 屏宽 / 12。 */
        private const val PUBLISH_POST_LAYOUT_B_FALLBACK_X_DIVISOR = 12
        /** 布局 A/B 试探点击后 OCR「Allow all」最多次数（未命中不点击；不判失败）。 */
        private const val PUBLISH_POST_ALLOW_ALL_OCR_MAX_PASSES = 5
        /** 布局试探时 Allow all 累计命中点击次数上限（含 OCR 命中但 su tap 失败）。 */
        private const val PUBLISH_POST_ALLOW_ALL_MAX_CLICKS = 2
        /** 图库点选素材后等待界面稳定，再 OCR「Next」。 */
        private const val PUBLISH_POST_AFTER_GALLERY_DELAY_MS = 1000L
        /** 第一次定位 Next：全屏 OCR 最多次数（均未命中则本条失败）。 */
        private const val PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES = 5
        /** 第一次 Next OCR 相邻两次间隔（毫秒，含随机）。 */
        private const val PUBLISH_POST_FIRST_NEXT_OCR_RETRY_DELAY_MIN_MS = 500L
        private const val PUBLISH_POST_FIRST_NEXT_OCR_RETRY_DELAY_MAX_MS = 1000L
        /** 文案屏顶部 Post：bbox 中心 Y ≤ 屏高×本比例视为「屏幕上方」（用于与中部工具栏 Post 区分）。 */
        private const val PUBLISH_POST_CAPTION_TOP_POST_MAX_CENTER_Y_FRACTION = 0.28f
        /** For You 预滑：每累计滑动该次数后做一次底栏 OCR（Home / Profile）。 */
        private const val FOR_YOU_WARMUP_BOTTOM_NAV_CHECK_EVERY_SWIPES = 10
        /** For You 预滑底栏 OCR：单次校验内最多次数。 */
        private const val FOR_YOU_WARMUP_BOTTOM_NAV_OCR_MAX_ATTEMPTS = 3
        /** For You 预滑：总时长均分为该段数，每段开始前做一次掉登录 OCR 探测。 */
        private const val FOR_YOU_WARMUP_LOGOUT_PROBE_PHASES = 3
        /** For You 预滑：配置总时长低于本值（毫秒）时不做掉登录 OCR 探测。 */
        private const val FOR_YOU_WARMUP_LOGOUT_PROBE_MIN_TOTAL_MS = 5 * 60 * 1000L
        /** 页面错乱恢复：优先导航返回回首页的最多次数（每次返回后 OCR 验 Home+Profile）。 */
        private const val RECOVER_TO_HOME_VIA_BACK_MAX_ATTEMPTS = 10
        /** 导航返回后等待 UI 稳定再 OCR（毫秒）。 */
        private const val RECOVER_TO_HOME_VIA_BACK_DELAY_MS = 520L
        /** 返回导致 TikTok 退出后，软拉起前台再额外等待 UI 稳定（毫秒）。 */
        private const val RECOVER_TO_HOME_AFTER_RELANCE_STABILIZE_MS = 720L
        /** 发作品全部完成后的收尾：OCR 点底栏 Home（顺手处理 Don't allow）。 */
        private const val PUBLISH_POST_FINAL_HOME_TAP_MAX_PASSES = 8
        private const val PUBLISH_POST_FINAL_HOME_TAP_RETRY_DELAY_MS = 500L
        /** 第 7 步点击 Done/Post 后及后续任意 OCR：轮询「负面信号消失 / 正面信号出现」的最多次数。 */
        private const val PUBLISH_POST_ITEM_SIGNAL_POLL_MAX_PASSES = 8
        private const val PUBLISH_POST_ITEM_SIGNAL_POLL_DELAY_MS = 900L
        /** 当日首条权限弹窗链开始前等待，避免弹窗未弹出就 OCR。 */
        private const val PUBLISH_POST_PERM_DIALOG_CHAIN_PRE_DELAY_MIN_MS = 800L
        private const val PUBLISH_POST_PERM_DIALOG_CHAIN_PRE_DELAY_MAX_MS = 1500L
        /** Post 路径：点击后仍未观察到发布成功信号时，补点 Post 的上限（不含首次点击）。 */
        private const val PUBLISH_POST_POST_TAP_RETRY_MAX = 2
        /** 正面信号 OCR 兜底关键词（忽略大小写包含即命中）。 */
        private val PUBLISH_POST_POSITIVE_SIGNAL_TEXT_NEEDLES = listOf(
            "uploading",
            "processing",
            "posted",
            "your video is being uploaded",
            "video uploaded",
        )

        /**
         * 测试 Bio 后② 时 true：跳过 Edit→Bio→Bio后①，外轮比例点后须已在 TikTok 个人主页。
         * 正常全链须为 false。
         */
        private const val PROFILE_EDIT_TEST_PART2_ONLY = false

        /** 临时跳过显示名(Name) 整段（点 Name→输入→Save→Confirm→终验）；测完其他功能后改回 false。 */
        private const val PROFILE_EDIT_SKIP_NAME_STEP = false

        /** Name 编辑页可修改态占位文案（不可改时通常不出现）。 */
        private const val PROFILE_EDIT_PREFERRED_NAME_PHRASE = "add your preferred name"
        private const val PROFILE_EDIT_NAME_EDITABLE_OCR_MAX_TRIES = 3
        private const val PROFILE_EDIT_NAME_EDITABLE_OCR_RETRY_MS = 2000L

        /** Bio 后②：设置齿轮比例点（相对屏宽/高）。 */
        private const val PROFILE_EDIT_SETTINGS_GEAR_RATIO_X = 0.96f
        private const val PROFILE_EDIT_SETTINGS_GEAR_RATIO_Y = 0.07f

        /** 设置齿轮点击：相对基准点固定右下偏移（实测 1080×1920 成功点为基准+(12,9)）。 */
        private const val PROFILE_EDIT_SETTINGS_GEAR_OFFSET_X_PX = 12
        private const val PROFILE_EDIT_SETTINGS_GEAR_OFFSET_Y_PX = 9

        /** 拉起 TikTok 后统一 OCR「Don't allow」：识别轮数与相邻间隔（毫秒）。 */
        private const val TIKTOK_LAUNCH_DONT_ALLOW_OCR_MAX_PASSES = 3
        private const val TIKTOK_LAUNCH_DONT_ALLOW_OCR_RETRY_DELAY_MS = 500L

        /** 切号后 is_login=否：页面跳转门禁（按钮消失 + 后续文案出现）最长等待。 */
        private const val AUTO_LOGIN_CUT_SWITCH_PAGE_TRANSITION_MS = 80_000L
        /** 切号流：验证码过期后新码与过期码相同时的重试间隔与次数。 */
        private const val AUTO_LOGIN_CUT_SWITCH_EXPIRED_CODE_RETRY_MAX = 6
        private const val AUTO_LOGIN_CUT_SWITCH_EXPIRED_CODE_RETRY_DELAY_MS = 5_000L

        /** 切号后自动登录：通用 OCR 重试。 */
        private const val AUTO_LOGIN_OCR_MAX_PASSES = 6
        private const val AUTO_LOGIN_OCR_RETRY_DELAY_MS = 1000L
        /** 入口页 Log in / Sign up 连续未识别超过此次数后，顺带探测 Edit 判定已登录。 */
        private const val AUTO_LOGIN_ENTRY_MISS_BEFORE_EDIT_PROBE = 3
        /** 验证码输入后 OCR 轮询最长时长（毫秒）。 */
        private const val AUTO_LOGIN_CODE_AFTER_OCR_MAX_DURATION_MS = 180_000L
        /** 密码页后 Don't allow OCR。 */
        private const val AUTO_LOGIN_DONT_ALLOW_OCR_MAX_PASSES = 16
        /** 通用 OCR 顺手点击 Don't allow 的冷却，避免同帧重复触发。 */
        private const val INLINE_DONT_ALLOW_TAP_COOLDOWN_MS = 1200L
        /** Viewer history 弹窗：同屏见 Viewer history + Save 时顺手点 Save 的冷却。 */
        private const val INLINE_VIEWER_HISTORY_POPUP_DISMISS_COOLDOWN_MS = 1200L
        /** 登录后「Save login for next time?」弹窗：点 Save login 的冷却。 */
        private const val INLINE_SAVE_LOGIN_POPUP_DISMISS_COOLDOWN_MS = 1200L
        /** 通用 OCR 顺手消 Swipe up 提示：单次最多上滑次数。 */
        private const val INLINE_SWIPE_UP_DISMISS_MAX_PASSES = 8
        /** 身份验证（Before you continue）：OCR 轮询间隔。 */
        private const val INLINE_IDENTITY_VERIFY_OCR_INTERVAL_MS = 1500L
        /** 身份验证：点 Outlook+Next 后等待 Before you continue 消失的最大 OCR 次数。 */
        private const val INLINE_IDENTITY_VERIFY_WAIT_GONE_MAX_POLLS = 2
        /** 身份验证：Before you continue 未消失时重走 Outlook+Next 的最大次数。 */
        private const val INLINE_IDENTITY_VERIFY_OUTLOOK_NEXT_REDO_MAX = 2
        /** 身份验证：验证码提交后最长轮询（毫秒）。 */
        private const val INLINE_IDENTITY_VERIFY_CODE_POLL_MAX_MS = 180_000L
        /** 身份验证：Resend code 点击冷却。 */
        private const val INLINE_IDENTITY_VERIFY_RESEND_TAP_COOLDOWN_MS = 1200L
        /** 登录入口 / Resend code 未命中：关开 TikTok 整链重来上限。 */
        private const val AUTO_LOGIN_MAX_CHAIN_RESTARTS = 3
        /** Account status 掉登录弹窗：点 OK 后最长等待（毫秒），期间 OCR 复核是否已关闭。 */
        private const val ACCOUNT_STATUS_LOGOUT_OK_WAIT_MS = 3000L
        private const val ACCOUNT_STATUS_LOGOUT_OK_POLL_INTERVAL_MS = 600L
        /** 密码页 Continue/Next 后 OCR 轮询最长时长（毫秒），判定直达首页或兴趣页。 */
        private const val AUTO_LOGIN_POST_PASSWORD_ROUTE_MAX_DURATION_MS = 180_000L
        private const val AUTO_LOGIN_RESEND_WAIT_MS = 5000L
        /** 验证码页识别 expired 后：冷却等待再返回重走 Continue→Resend code（毫秒）。 */
        private const val AUTO_LOGIN_EXPIRED_RECOVERY_WAIT_MS = 58_000L
        /** Email 后 Continue：两次点击最小间隔（毫秒），避免连续狂点同一位置。 */
        private const val AUTO_LOGIN_EMAIL_CONTINUE_TAP_COOLDOWN_MS = 2_500L
        private val AUTO_LOGIN_INTEREST_TAGS = listOf(
            "comedy", "gaming", "culture", "garden", "life", "education", "skip",
        )

        /** 每次点击设置比例点后，OCR 探 Privacy（文案含 privacy）最多次数。 */
        private const val PROFILE_EDIT_SETTINGS_PRIVACY_OCR_PER_TAP = 2

        /** 未见 Privacy 时最多复点次数（固定同坐标复点）。 */
        private const val PROFILE_EDIT_SETTINGS_GEAR_RETAP_MAX = 6

        /** 修改基本资料：Upload photo / Select from Gallery 后整屏 OCR「Allow all」最多次数（未命中仅跳过）。 */
        private const val PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES = 3

        /** 修改基本资料：OCR「Post this photo to Story」最多次数（未命中仅跳过，不阻断后续 Save 等步骤）。 */
        private const val PROFILE_EDIT_POST_STORY_OCR_MAX_PASSES = 3

        /** Upload photo / Select from Gallery / Allow all 之后：OCR 确认「Recents」可见（相册界面稳定），仅探测不点击。 */
        private const val PROFILE_EDIT_RECENTS_STABLE_OCR_MAX_PASSES = 6
        private const val PROFILE_EDIT_RECENTS_STABLE_OCR_DELAY_MS = 500L

        /** Save(发帖) 后等待头像上传完成：轮询 OCR 间隔与总超时（毫秒）。 */
        private const val PROFILE_EDIT_AVATAR_UPLOAD_POLL_MS = 1000L
        private const val PROFILE_EDIT_AVATAR_UPLOAD_WAIT_MAX_MS = 60_000L

        /** 发作品图库统计根路径（与 adb `find /storage/emulated/0/Pictures` 一致）。 */
        private const val PUBLISH_POST_PICTURES_PATH = "/storage/emulated/0/Pictures"

        /** 发作品前额外清空的系统 Camera 相册目录（DCIM/Camera）。 */
        private const val PUBLISH_POST_DCIM_CAMERA_PATH = "/storage/emulated/0/DCIM/Camera"

        /** 发作品统计/选图时排除的子目录（App 调试截图、下发缓存等，不计入可发条数）。 */
        private const val PUBLISH_POST_PICTURES_EXCLUDE_SUBDIR = "AutoTraces"

        /** 发作品统计：图片扩展名（`find -iname`，递归子目录）。 */
        private val PUBLISH_POST_PICTURES_IMAGE_EXTS =
            listOf("jpg", "jpeg", "png", "webp", "gif")

        /** 发作品统计：视频扩展名（`find -iname`，递归子目录）。 */
        private val PUBLISH_POST_PICTURES_VIDEO_EXTS =
            listOf("mp4", "mkv", "3gp", "webm")
    }

    private fun buildTikTokLaunchIntent(ctx: Context): Intent? {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val pm = ctx.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            return launchIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        }

        val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
            setPackage(pkg)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return if (pm.resolveActivity(fallbackIntent, 0) != null) fallbackIntent else null
    }

    /** 仅执行 root 拉起命令（不含等待与前台校验）；强停由调用方按需处理。 */
    private fun runTikTokRootLaunchCommands(): Boolean {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val commands = listOf(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg",
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1",
        )
        for (command in commands) {
            if (runSuCommand(command)) return true
        }
        return false
    }

    private fun isHostAppInForeground(): Boolean =
        isPackageVisible(AppConstants.APP_PACKAGE_NAME)

    /**
     * 远控/后台任务时用户常在桌面或其它 App：先切回本应用，降低后台 Activity 启动（BAL）拦截概率。
     * 同时提供 root 拉起本应用，避免仅 intent 被系统静默忽略。
     */
    private fun bringHostAppToForeground(ctx: Context): Boolean {
        val hostPkg = AppConstants.APP_PACKAGE_NAME
        if (isHostAppInForeground()) return true

        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
        runCatching { ctx.startActivity(intent) }

        if (runSuCommand(
                "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $hostPkg",
            )
        ) {
            return true
        }
        return runSuCommand("monkey -p $hostPkg -c android.intent.category.LAUNCHER 1")
    }

    /**
     * 拉起 TikTok：启动命令 → 等待 [launchWaitMs] → dumpsys 前台校验 →
     * **仅在前台已确认时** OCR 处理 Don't allow（非 TikTok 前台时 OCR 无意义）。
     *
     * 不在本应用界面时：先切回 AutoTraces，并优先 root 拉起 TikTok（避免 startActivity 成功但未切前台）；
     * 前台校验失败时再 root 补拉一次。
     */
    private suspend fun launchTikTokOnceWithCheck(
        ctx: Context,
        alias: String,
        tag: String,
        launchWaitMs: Long,
        forceStopBeforeLaunch: Boolean = true,
        skipDontAllowOcr: Boolean = false,
        quietSuccessLog: Boolean = false,
    ): Boolean {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        if (!forceStopBeforeLaunch && isTikTokForeground()) {
            devOp(alias, "$tag：TikTok 已在前台（dumpsys），跳过重复拉起")
            if (!skipDontAllowOcr) {
                dismissDontAllowAfterTikTokLaunch(ctx, alias, tag)
            }
            return true
        }
        val forceStopWaitMs = lastConfigSnapshot.tiktok_force_stop_wait_ms.coerceIn(100L, 8000L)
        if (forceStopBeforeLaunch) {
            runSuCommand("am force-stop $pkg")
            delay(forceStopWaitMs)
            invalidateForegroundProbeCache()
        }

        if (!skipDontAllowOcr && isSystemPermissionGrantDialogForeground()) {
            devOp(alias, "$tag：拉起流程开始前检测到权限授权页，先 OCR 点 Don't allow 并恢复 TikTok")
            dismissSystemPermissionDialogAndRestoreTikTok(ctx, alias, "$tag·拉起前")
            invalidateForegroundProbeCache()
        }

        val hostWasForeground = isHostAppInForeground()
        val tiktokAlreadyForeground = !forceStopBeforeLaunch && isTikTokForeground()
        if (!hostWasForeground && !tiktokAlreadyForeground) {
            devOp(alias, "$tag：当前不在本应用界面，先切回 AutoTraces 再拉起 TikTok")
            bringHostAppToForeground(ctx)
            delay(400)
        }

        var didLaunchCommand = false
        if (tiktokAlreadyForeground) {
            devOp(alias, "$tag：TikTok 已在前台（严格 dumpsys），跳过重复拉起")
            didLaunchCommand = true
        } else {
            // 本应用/悬浮层在前台时 startActivity 常无法切到 TikTok，优先 root 拉起
            didLaunchCommand = runTikTokRootLaunchCommands()
            if (!didLaunchCommand) {
                val intent = buildTikTokLaunchIntent(ctx)
                if (intent != null) {
                    try {
                        ctx.startActivity(intent)
                        didLaunchCommand = true
                    } catch (_: Exception) {
                        // fall through to root launch
                    }
                }
            }
            if (!didLaunchCommand) {
                didLaunchCommand = runTikTokRootLaunchCommands()
            }
        }
        if (!didLaunchCommand) {
            devOp(alias, "$tag：拉起 TikTok 失败（启动命令均未成功）")
            return false
        }

        devOp(alias, "$tag：启动命令已执行，等待 ${launchWaitMs}ms（tiktok_launch_wait_ms）后进行严格前台校验")
        delay(launchWaitMs)

        suspend fun confirmStrictForeground(): Boolean {
            invalidateForegroundProbeCache()
            return isPackageVisible(pkg)
        }

        var confirmed = confirmStrictForeground()
        if (!confirmed) {
            devOp(alias, "$tag：配置等待后严格前台校验未通过，root 补拉 TikTok 一次")
            if (runTikTokRootLaunchCommands()) {
                delay(launchWaitMs.coerceAtMost(4000L).coerceAtLeast(1200L))
                confirmed = confirmStrictForeground()
            }
        }
        if (!confirmed) {
            devOp(alias, "$tag：补拉后仍未通过，再等 1200ms 重试严格 dumpsys")
            delay(1200)
            confirmed = confirmStrictForeground()
        }
        if (!confirmed && !skipDontAllowOcr &&
            dismissSystemPermissionDialogAndRestoreTikTok(ctx, alias, "$tag·权限恢复")
        ) {
            confirmed = confirmStrictForeground()
        }
        if (!confirmed) {
            logForegroundProbeFailure(alias, tag, pkg)
            devOp(alias, "$tag：拉起 TikTok 失败（严格前台校验未通过，跳过 Don't allow OCR）")
            return false
        }

        val fgPkg = resolveStrictForegroundPackage(collectForegroundProbeTexts())
        devOp(alias, "$tag：严格前台校验通过（焦点包=${fgPkg ?: "未知"}）")

        val dismissedDontAllow =
            if (!skipDontAllowOcr) {
                dismissDontAllowAfterTikTokLaunch(ctx, alias, tag)
            } else {
                false
            }
        delay(if (dismissedDontAllow) 600L else 350L)

        invalidateForegroundProbeCache()
        if (!confirmStrictForeground()) {
            if (skipDontAllowOcr ||
                !dismissSystemPermissionDialogAndRestoreTikTok(ctx, alias, "$tag·权限叠层")
            ) {
                logForegroundProbeFailure(alias, tag, pkg)
                devOp(alias, "$tag：Don't allow 处理后 TikTok 已不在前台，拉起失败")
                return false
            }
            invalidateForegroundProbeCache()
            if (!confirmStrictForeground()) {
                logForegroundProbeFailure(alias, tag, pkg)
                devOp(alias, "$tag：权限叠层恢复后 TikTok 仍非前台，拉起失败")
                return false
            }
        }

        if (!quietSuccessLog) {
            when {
                dismissedDontAllow ->
                    devOp(alias, "$tag：拉起 TikTok 成功（权限弹窗已处理）")
                else ->
                    devOp(alias, "$tag：拉起 TikTok 成功")
            }
        }
        return true
    }

    /** 拉起 TikTok 命令已执行后：专用整屏 OCR 点拒绝权限（Don't allow / 取消 / 不允许等，至多 3 次、间隔 0.5s）。 */
    private suspend fun dismissDontAllowAfterTikTokLaunch(
        ctx: Context,
        alias: String,
        tag: String,
    ): Boolean = tryDismissDontAllowOcr(
        ctx = ctx,
        alias = alias,
        phaseLog = "拉起后 Don't allow OCR 取消",
        logSuffix = tag.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "launch" },
        maxPasses = TIKTOK_LAUNCH_DONT_ALLOW_OCR_MAX_PASSES,
        retryDelayMs = TIKTOK_LAUNCH_DONT_ALLOW_OCR_RETRY_DELAY_MS,
        logPrefix = "",
        compactLog = true,
        useSideEffectFreeOcr = true,
        findDismissHit = { findLaunchPermissionDismissHit(it) },
    )

    override suspend fun launchTikTokWithForegroundVerification(
        ctx: Context,
        alias: String,
        tag: String,
        forceStopBeforeLaunch: Boolean,
        timingConfig: AppConfig?,
    ): Boolean {
        timingConfig?.let { lastConfigSnapshot = it }
        val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
        return launchTikTokOnceWithCheck(
            ctx = ctx,
            alias = alias,
            tag = tag,
            launchWaitMs = launchWaitMs,
            forceStopBeforeLaunch = forceStopBeforeLaunch,
        )
    }

    override suspend fun restartTikTokForRecovery(skipDontAllowOcr: Boolean): Boolean {
        val ctx: Context = appContextProvider()
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "重启 TikTok 恢复")) return false
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val forceStopWaitMs = lastConfigSnapshot.tiktok_force_stop_wait_ms.coerceIn(100L, 8000L)
        val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
        didRestartTikTokInCurrentRun = true
        val t0 = System.currentTimeMillis()
        devOp(alias, "强关重启：正在关闭 TikTok")

        suspend fun launchOnce(tag: String): Boolean =
            launchTikTokOnceWithCheck(
                ctx,
                alias,
                tag,
                launchWaitMs,
                forceStopBeforeLaunch = false,
                skipDontAllowOcr = skipDontAllowOcr,
                quietSuccessLog = true,
            )

        runSuCommand("am force-stop $pkg")
        delay(forceStopWaitMs)

        val firstOk = launchOnce("首次启动")
        if (firstOk) {
            devOp(alias, "强关重启 TikTok 成功（首次冷启）", System.currentTimeMillis() - t0)
            return true
        }

        delay(350)
        invalidateForegroundProbeCache()
        if (isPackageVisible(pkg)) {
            val fgPkg = resolveStrictForegroundPackage(collectForegroundProbeTexts())
            devOp(
                alias,
                "强关重启 TikTok 成功（短等后前台=${fgPkg ?: "未知"}）",
                System.currentTimeMillis() - t0,
            )
            return true
        }

        runSuCommand("am force-stop $pkg")
        delay(forceStopWaitMs)
        val secondOk = launchOnce("二次启动")
        devOp(
            alias,
            "强关重启 TikTok ${if (secondOk) "成功（二次冷启）" else "失败（二次冷启）"}",
            System.currentTimeMillis() - t0,
        )
        return secondOk
    }

    /**
     * 优先 [navBack] 回首页（底栏 Home+Profile 同见视为已回首页，不强关）；
     * 导航返回过程中 OCR 已见底栏 Home+Profile 时，再执行最后一次返回（搜索/内页也可能误命中底栏）；
     * 首次 OCR 已见底栏 Home+Profile 则判定可能仍在搜索/内页，未按返回键，改为强关重启；
     * 超过 [RECOVER_TO_HOME_VIA_BACK_MAX_ATTEMPTS] 次仍未回首页则 fallback 强关重启。
     *
     * [PrefsStore.peekGrowthContinueFromPublishHome] 为 true 时（发作品全部完成且已点 Home 后），
     * 短期内尽量避免强停重启，仅尝试 navBack 回首页；发作品进行中不受此限制。
     */
    private suspend fun recoverToHomeViaBackOrRestart(
        service: Context,
        reason: String,
        skipDontAllowOcr: Boolean = false,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, reason)) return false
        if (growthYieldOrNull(alias, reason)) return false
        val ctx = service.applicationContext
        val blockForceRestart = PrefsStore.peekGrowthContinueFromPublishHome()
        if (!ensureTikTokInForeground(ctx, alias, "$reason·回首页", allowForceRestart = !blockForceRestart)) {
            devOp(alias, "$reason：TikTok 非前台，未按返回键，改为强关重启")
            if (blockForceRestart) {
                devOp(alias, "$reason：发作品刚完成于 Home，短期内跳过硬关重启")
                return false
            }
            return restartTikTokForRecovery(skipDontAllowOcr)
        }

        val screenH = service.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)

        /**
         * 每次 OCR 前先验 TikTok 仍在前台；若返回已把 TikTok 退出到桌面/后台，
         * 则不强停、软拉起前台并等待稳定后再做 OCR。
         */
        suspend fun ensureTikTokForegroundStableBeforeRecoverOcr(passLabel: String): Boolean {
            invalidateForegroundProbeCache()
            if (isPackageVisible(pkg)) return true

            devOp(alias, "$reason：$passLabel 后 TikTok 非前台，疑似返回已退出 App，尝试拉回前台")
            if (dismissSystemPermissionDialogAndRestoreTikTok(ctx, alias, "$reason·$passLabel·权限")) {
                devOp(alias, "$reason：$passLabel 后经权限页恢复 TikTok 前台")
            } else if (
                launchTikTokOnceWithCheck(
                    ctx,
                    alias,
                    "$reason·$passLabel·切前台",
                    launchWaitMs,
                    forceStopBeforeLaunch = false,
                    skipDontAllowOcr = skipDontAllowOcr,
                )
            ) {
                devOp(alias, "$reason：$passLabel 后已软拉起 TikTok 至前台")
            } else if (bringTikTokToForegroundAndConfirm(ctx, alias, "$reason·$passLabel·补拉")) {
                devOp(alias, "$reason：$passLabel 后补拉 TikTok 至前台")
            } else {
                devOp(alias, "$reason：$passLabel 后拉回 TikTok 前台失败")
                return false
            }

            invalidateForegroundProbeCache()
            if (!isPackageVisible(pkg)) {
                devOp(alias, "$reason：$passLabel 后拉回前台仍未通过 dumpsys 校验")
                return false
            }
            if (!growthDelay(alias, RECOVER_TO_HOME_AFTER_RELANCE_STABILIZE_MS, "$reason·拉回稳定")) {
                return false
            }
            return true
        }

        suspend fun evaluateRecoverHomeOcr(tagSuffix: String, passLabel: String): RecoverHomeOcrOutcome {
            if (hardStopOrNull(alias, reason)) return RecoverHomeOcrOutcome.HardStop
            if (!ensureTikTokForegroundStableBeforeRecoverOcr(passLabel)) {
                return RecoverHomeOcrOutcome.ForegroundUnavailable
            }
            val (vision, err) = mlKitRecognizeFullScreen(service, "recover_home_$tagSuffix")
            if (vision == null) {
                devOp(alias, "$reason：回首页 OCR 失败：$err")
                return if (isMlKitHardStopError(err)) {
                    RecoverHomeOcrOutcome.HardStop
                } else {
                    RecoverHomeOcrOutcome.NotOnMainFeed
                }
            }
            if (autoLoginSeesMainFeedBottomNav(vision, screenH)) {
                devOp(alias, "$reason：OCR 已见主 Feed（${autoLoginDirectSuccessMatchReason(vision, screenH)}）")
                return RecoverHomeOcrOutcome.OnMainFeed
            }
            return RecoverHomeOcrOutcome.NotOnMainFeed
        }

        when (val precheck = evaluateRecoverHomeOcr("precheck", "首次 OCR 前")) {
            RecoverHomeOcrOutcome.OnMainFeed -> {
                devOp(
                    alias,
                    "$reason：OCR 已见底栏 Home+Profile（搜索/内页也可能命中），未按返回键，" +
                        if (blockForceRestart) "发作品刚完成后跳过硬关重启" else "改为强关重启",
                )
                if (blockForceRestart) return false
                return restartTikTokForRecovery(skipDontAllowOcr)
            }
            RecoverHomeOcrOutcome.HardStop -> return false
            RecoverHomeOcrOutcome.ForegroundUnavailable -> {
                devOp(alias, "$reason：首次 OCR 前 TikTok 前台不可用，未按返回键，改为强关重启")
                if (blockForceRestart) return false
                return restartTikTokForRecovery(skipDontAllowOcr)
            }
            RecoverHomeOcrOutcome.NotOnMainFeed -> {
                devOp(alias, "$reason：OCR 未见主 Feed 底栏，开始导航返回回首页")
            }
        }

        for (pass in 1..RECOVER_TO_HOME_VIA_BACK_MAX_ATTEMPTS) {
            if (hardStopOrNull(alias, reason)) return false
            invalidateForegroundProbeCache()
            if (!isPackageVisible(pkg)) {
                if (!ensureTikTokForegroundStableBeforeRecoverOcr("第 $pass 次返回前")) {
                    devOp(alias, "$reason：第 $pass 次返回前 TikTok 非前台且无法拉回，终止导航返回恢复")
                    break
                }
            }
            navBack(alias, "$reason 回首页（$pass/$RECOVER_TO_HOME_VIA_BACK_MAX_ATTEMPTS）")
            if (!growthDelay(alias, RECOVER_TO_HOME_VIA_BACK_DELAY_MS, "$reason·返回后")) return false
            when (val outcome = evaluateRecoverHomeOcr("back_$pass", "第 $pass 次返回")) {
                RecoverHomeOcrOutcome.OnMainFeed -> {
                    devOp(
                        alias,
                        "$reason：第 $pass 次导航返回后 OCR 已见回首页信息，再执行最后一次返回",
                    )
                    if (hardStopOrNull(alias, reason)) return false
                    invalidateForegroundProbeCache()
                    if (!isPackageVisible(pkg)) {
                        if (!ensureTikTokForegroundStableBeforeRecoverOcr("回首页后补返前")) {
                            devOp(alias, "$reason：回首页后最后一次返回前 TikTok 非前台且无法拉回")
                            return false
                        }
                    }
                    navBack(alias, "$reason 回首页·最后一次返回（第 $pass 次已命中）")
                    if (!growthDelay(alias, RECOVER_TO_HOME_VIA_BACK_DELAY_MS, "$reason·最后一次返回后")) {
                        return false
                    }
                    when (evaluateRecoverHomeOcr("final_back_$pass", "最后一次返回")) {
                        RecoverHomeOcrOutcome.HardStop -> return false
                        RecoverHomeOcrOutcome.ForegroundUnavailable -> {
                            if (ensureTikTokForegroundStableBeforeRecoverOcr("最后一次返回后")) {
                                devOp(alias, "$reason：最后一次返回后已拉回 TikTok 前台，视为恢复完成")
                                return true
                            }
                            devOp(alias, "$reason：最后一次返回后无法保持 TikTok 前台")
                            return false
                        }
                        RecoverHomeOcrOutcome.OnMainFeed,
                        RecoverHomeOcrOutcome.NotOnMainFeed,
                        -> {
                            devOp(alias, "$reason：最后一次返回完成，导航返回回首页恢复成功（未强关 TikTok）")
                            return true
                        }
                    }
                }
                RecoverHomeOcrOutcome.HardStop -> return false
                RecoverHomeOcrOutcome.ForegroundUnavailable -> {
                    devOp(alias, "$reason：第 $pass 次返回后无法拉回 TikTok 前台，终止导航返回恢复")
                    break
                }
                RecoverHomeOcrOutcome.NotOnMainFeed -> Unit
            }
        }

        devOp(
            alias,
            "$reason：${RECOVER_TO_HOME_VIA_BACK_MAX_ATTEMPTS} 次导航返回仍未回首页，" +
                if (blockForceRestart) "发作品刚完成后跳过硬关重启" else "改为强关重启",
        )
        if (blockForceRestart) return false
        return restartTikTokForRecovery(skipDontAllowOcr)
    }

    private data class StrictLabelHit(val point: Point, val rect: Rect)

    /** 修改基本资料：命中目标文案时不写 OCR 摘要（仅未命中时由 [logOcrReadTextOnMiss] 输出读取文案）。 */
    private fun profileEditLogStrictHit(
        alias: String,
        phase: String,
        label: String,
        hit: StrictLabelHit,
        screenW: Int = 0,
        screenH: Int = 0,
    ) {
        // 坐标/bbox 仅用于调试，不写设备日志
    }

    private fun buildProfileEditOcrPreview(result: Text, maxLines: Int = 40): String =
        result.textBlocks
            .flatMap { it.lines }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .take(maxLines)
            .joinToString(" | ")

    /** OCR 未匹配目标文案时：输出本次读到的屏幕文案（每次未命中一条，便于区分定位 vs 解析）。 */
    private fun logOcrReadTextOnMiss(
        alias: String,
        phaseLabel: String,
        attempt: Int,
        maxAttempts: Int,
        readTextPreview: String,
        maxChars: Int = 500,
    ) {
        val attemptLabel =
            if (maxAttempts in 1..999) "第 $attempt/$maxAttempts 次：" else "第 $attempt 次："
        devOp(alias, "$phaseLabel ${attemptLabel}读取到文案 ${readTextPreview.take(maxChars)}")
    }

    private fun logOcrReadTextOnMiss(
        alias: String,
        phaseLabel: String,
        attempt: Int,
        maxAttempts: Int,
        vision: Text,
        maxChars: Int = 500,
    ) {
        logOcrReadTextOnMiss(alias, phaseLabel, attempt, maxAttempts, buildProfileEditOcrPreview(vision), maxChars)
    }

    /**
     * Confirm 步骤调试用：按 element 打印 `文案@(l,t,r,b)`，便于对照真机 UI 补规则。
     */
    private fun buildProfileEditOcrElementDump(result: Text, maxElements: Int = 96): String {
        val parts = ArrayList<String>(maxElements)
        var n = 0
        outer@ for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    if (n >= maxElements) break@outer
                    val b = el.boundingBox ?: continue
                    val t = el.text.trim().replace("\n", " ").take(48)
                    if (t.isEmpty()) continue
                    parts.add("\"$t\"@(${b.left},${b.top},${b.right},${b.bottom})")
                    n++
                }
            }
        }
        return parts.joinToString(" ; ")
    }

    /** Name 保存后若 TikTok 同步修改 Username，同行会出现「Sync」或「Username」提示行。 */
    private fun findProfileEditSyncOrUsernameRowHit(vision: Text): StrictLabelHit? {
        findStrictExactLabelHit(vision, "Sync")?.let { return it }
        findStrictExactLabelHit(vision, "Username")?.let { return it }
        for (block in vision.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    if (el.text.trim().equals("username", ignoreCase = true)) {
                        val b = el.boundingBox ?: continue
                        return StrictLabelHit(Point(b.centerX(), b.centerY()), b)
                    }
                }
                val lt = line.text.trim()
                if (lt.equals("username", ignoreCase = true)) {
                    val b = line.boundingBox ?: continue
                    return StrictLabelHit(Point(b.centerX(), b.centerY()), b)
                }
            }
        }
        return null
    }

    /**
     * 资料编辑页：定位含 tiktok 的文案（如 TikTok / tiktok.com/@…），取评分最高者点击复制。
     * @return 命中坐标与 OCR 原文（写日志用）
     */
    private fun findProfileEditTikTokTextHit(vision: Text): Pair<StrictLabelHit, String>? {
        findStrictExactLabelHit(vision, "TikTok")?.let {
            return it to "TikTok"
        }
        var best: StrictLabelHit? = null
        var bestText = ""
        var bestScore = Int.MIN_VALUE
        fun scoreForTikTokText(raw: String): Int {
            val t = raw.trim()
            if (t.isEmpty()) return Int.MIN_VALUE
            val lower = t.lowercase(Locale.US)
            if (!lower.contains("tiktok")) return Int.MIN_VALUE
            var s = 10
            if (t.equals("TikTok", ignoreCase = true)) s += 100
            if (lower.contains("tiktok.com")) s += 60
            if (lower.contains("@")) s += 15
            s += t.length.coerceAtMost(48)
            return s
        }
        fun consider(b: Rect, raw: String) {
            val sc = scoreForTikTokText(raw)
            if (sc <= bestScore) return
            bestScore = sc
            bestText = raw.trim().replace("\n", " ").take(96)
            best = StrictLabelHit(Point(b.centerX(), b.centerY()), b)
        }
        for (block in vision.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    consider(b, el.text)
                }
                line.boundingBox?.let { consider(it, line.text) }
            }
        }
        val hit = best ?: return null
        return hit to bestText
    }

    /** 从粘贴读出的文案取 @ 之后的首段用户名并规范化（与互点剪贴板清洗一致）。 */
    private fun extractProfileEditUsernameAfterAt(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val atIdx = trimmed.indexOf('@')
        val token = if (atIdx >= 0) {
            trimmed.substring(atIdx + 1).lineSequence().firstOrNull()?.trim().orEmpty()
        } else {
            trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
        }
        return sanitizeMutualCopiedUsernameFromClipboard(token)
    }

    private suspend fun fetchAssignedUsernameForProfileEdit(logAlias: String): String? {
        val deviceId = PrefsStore.getDeviceId()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val alias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (PrefsStore.isAutoSwitchAutonomousMode()) {
            AutoSwitchBundleHolder.resolveAssignedAccount(
                logContextUsername = PrefsStore.getLogContextUsername(),
            )?.username?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return try {
            val resp = withContext(Dispatchers.IO) {
                ApiService.getDeviceAssignedAccount(deviceId, alias)
            }
            if (resp.code != 0) {
                devOp(logAlias, "「修改基本资料」拉取分配账号失败 code=${resp.code} msg=${resp.msg}")
                return null
            }
            resp.username?.trim()?.takeIf { it.isNotEmpty() }
        } catch (ex: Exception) {
            devOp(logAlias, "「修改基本资料」拉取分配账号异常：${ex.message}")
            null
        }
    }

    /**
     * Bio 输入框已聚焦：粘贴剪贴板并用 uiautomator 读 EditText（不依赖 ClipboardManager）。
     */
    private suspend fun profileEditTryPasteClipboardIntoBioFieldThenRead(logAlias: String): String? {
        if (hardStopOrNull(logAlias, "修改基本资料-Bio粘贴探测")) return null
        runSuCommand("input keyevent 279")
        delay(Random.nextLong(480, 750))
        val xml = runUiAutomatorDumpWindowXml(logAlias) ?: return null
        val text = parseLargestEditTextFromUiAutomatorXml(xml)
        if (text.isNullOrBlank()) {
            devOp(logAlias, "「修改基本资料」Bio：uiautomator 未解析到有效 EditText 文本")
            return null
        }
        devOp(logAlias, "「修改基本资料」Bio：粘贴后 uiautomator 读取 len=${text.length} preview=${text.take(56)}")
        return text
    }

    /** Bio 前：整屏 OCR 定位「TikTok」文案并点击（复制到系统剪贴板）；至多 3 次，未命中不点击。 */
    private suspend fun profileEditOcrTapTikTokTextCopy(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
    ): Boolean {
        repeat(3) { attemptIndex ->
            val attempt = attemptIndex + 1
            if (hardStopOrNull(logAlias, "修改基本资料-TikTok文案OCR")) return false
            if (attempt > 1 && !profileEditDelay(logAlias, 650, "TikTok文案OCR")) return false
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_tiktok_text_$attempt")
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」Bio 前 TikTok 文案 OCR 第 $attempt/3 次失败：$errTag")
                return@repeat
            }
            val hitPair = findProfileEditTikTokTextHit(vision)
            if (hitPair == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Bio 前 TikTok 文案",
                    attempt,
                    3,
                    vision,
                    420,
                )
                return@repeat
            }
            val (hit, matchedText) = hitPair
            profileEditLogStrictHit(logAlias, "TikTok文案(复制)", matchedText, hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            devOp(logAlias, "「修改基本资料」Bio 前：点击 TikTok 文案 (${tap.x},${tap.y}) 以复制到剪贴板")
            if (!runSuCommand("input tap ${tap.x} ${tap.y}")) {
                devOp(logAlias, "「修改基本资料」Bio 前 TikTok 文案 tap 失败（su），将重试 OCR")
                return@repeat
            }
            delay(Random.nextLong(700, 1100))
            return true
        }
        return false
    }

    private suspend fun profileEditSyncUsernameFromBioClipboardIfNeeded(
        logAlias: String,
        assignedUsername: String,
    ): String? {
        val fromField = profileEditTryPasteClipboardIntoBioFieldThenRead(logAlias)
        if (fromField.isNullOrBlank()) {
            devOp(logAlias, "「修改基本资料」Bio：粘贴探测无有效文案，跳过用户名比对")
            profileEditClearFocusedNameField(logAlias)
            delay(Random.nextLong(320, 520))
            return null
        }
        devOp(
            logAlias,
            "「修改基本资料」Bio：粘贴比对原文 len=${fromField.length} text=${fromField.replace("\n", " ").take(240)}",
        )
        val fromClip = extractProfileEditUsernameAfterAt(fromField)
        if (fromClip == null) {
            devOp(
                logAlias,
                "「修改基本资料」Bio：无法从粘贴文案解析 @ 后用户名，跳过上报；原文=${fromField.replace("\n", " ").take(120)}",
            )
            profileEditClearFocusedNameField(logAlias)
            delay(Random.nextLong(320, 520))
            return null
        }
        val assignedNorm =
            sanitizeMutualCopiedUsernameFromClipboard(assignedUsername)
                ?: assignedUsername.trim().lowercase(Locale.US)
        devOp(
            logAlias,
            "「修改基本资料」Bio：比对 @后解析=$fromClip | 分配账号(规范化)=$assignedNorm | 分配账号(原始)=$assignedUsername",
        )
        if (fromClip.equals(assignedNorm, ignoreCase = true)) {
            devOp(logAlias, "「修改基本资料」Bio：一致，无需更新云端")
        } else {
            val syncedUsername = fromClip.trim()
            devOp(
                logAlias,
                "「修改基本资料」Bio：不一致（剪贴板=$fromClip ≠ 分配=$assignedNorm），上报云端更新 username",
            )
            if (PrefsStore.isAutoSwitchAutonomousMode()) {
                AutoSwitchLedger.recordUsernameUpdate(assignedUsername, syncedUsername)
                devOp(logAlias, "「修改基本资料」Bio：用户名变更已记入 ledger（对账写回）")
            } else {
                val deviceId = PrefsStore.getDeviceId()?.trim()?.takeIf { it.isNotEmpty() }
                val alias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() }
                if (deviceId != null && alias != null) {
                    try {
                        val resp = withContext(Dispatchers.IO) {
                            ApiService.updateDeviceAssignedAccountUsername(deviceId, alias, syncedUsername)
                        }
                        devOp(
                            logAlias,
                            "「修改基本资料」Bio：云端更新 username code=${resp.code} msg=${resp.msg} updated=${resp.updated} username=${resp.username}",
                        )
                        if (resp.code != 0) {
                            profileEditClearFocusedNameField(logAlias)
                            delay(Random.nextLong(320, 520))
                            return null
                        }
                        val cloudUser = resp.username?.trim()?.takeIf { it.isNotEmpty() } ?: syncedUsername
                        profileEditClearFocusedNameField(logAlias)
                        delay(Random.nextLong(320, 520))
                        return cloudUser
                    } catch (ex: Exception) {
                        devOp(logAlias, "「修改基本资料」Bio：云端更新 username 异常 ${ex.message}")
                        profileEditClearFocusedNameField(logAlias)
                        delay(Random.nextLong(320, 520))
                        return null
                    }
                } else {
                    devOp(logAlias, "「修改基本资料」Bio：缺少 deviceId/alias，无法上报 username 更新")
                    profileEditClearFocusedNameField(logAlias)
                    delay(Random.nextLong(320, 520))
                    return null
                }
            }
            profileEditClearFocusedNameField(logAlias)
            delay(Random.nextLong(320, 520))
            return syncedUsername
        }
        profileEditClearFocusedNameField(logAlias)
        delay(Random.nextLong(320, 520))
        return null
    }

    /**
     * 与 [rowLabel] 同一水平带内的对勾：优先 OCR 到的符号/极短文案且在标签右侧；否则屏右侧同行几何回退。
     */
    private fun findRowAlignedCheckmarkTapForProfileEdit(
        vision: Text,
        rowLabel: StrictLabelHit,
        screenW: Int,
    ): Point? {
        val ref = rowLabel.rect
        val bandT = ref.top - 14
        val bandB = ref.bottom + 14
        val checkSyms = setOf("✓", "✔", "√", "☑", "✅")
        data class RowEl(val rect: Rect, val text: String) {
            val cx get() = rect.centerX()
            val cy get() = rect.centerY()
        }
        val rowEls = ArrayList<RowEl>(32)
        for (block in vision.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    val cy = b.centerY()
                    if (cy < bandT || cy > bandB) continue
                    if (b.width() >= ref.width() - 6 &&
                        b.height() >= ref.height() - 6 &&
                        kotlin.math.abs(b.left - ref.left) <= 6 &&
                        kotlin.math.abs(b.right - ref.right) <= 6
                    ) {
                        continue
                    }
                    val t = el.text.trim()
                    rowEls.add(RowEl(b, t))
                }
            }
        }
        rowEls.filter { it.text in checkSyms }
            .maxByOrNull { it.rect.right }
            ?.let { return Point(it.cx, it.cy) }
        val margin = (screenW * 0.12f).toInt().coerceIn(72, 140)
        rowEls.filter { e ->
            e.rect.left >= ref.right - 28 &&
                e.rect.centerX() > ref.centerX() &&
                e.text.length <= 4 &&
                e.rect.width() <= margin &&
                e.rect.height() <= ref.height() + 18
        }.maxByOrNull { it.rect.right }
            ?.let { return Point(it.cx, it.cy) }
        val fx = (screenW - 52).coerceIn(ref.right + 40, screenW - 8)
        val fy = ref.centerY().coerceIn(8, Int.MAX_VALUE)
        return Point(fx, fy)
    }

    private fun findStrictEditTapPoint(result: Text): Point? {
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    if (el.text.trim() == "Edit") {
                        el.boundingBox?.let { b -> return Point(b.centerX(), b.centerY()) }
                    }
                }
                if (line.text.trim() == "Edit") {
                    line.boundingBox?.let { b -> return Point(b.centerX(), b.centerY()) }
                }
            }
        }
        return null
    }

    /** 侧栏「Settings and privacy」行：严格全等优先，否则行/element 含该短语（如 OCR 前缀图标「O 」）。 */
    private fun findProfileEditSettingsAndPrivacyRowHit(result: Text): StrictLabelHit? =
        findStrictExactAcrossAdjacentLines(result, "Settings and privacy")
            ?: findStrictExactLabelHit(result, "Settings and privacy")
            ?: findLabelHitContainingIgnoreCase(result, "settings and privacy")

    /** 设置齿轮点开后：仅确认侧栏已出现「Settings and privacy」（此时尚无 Permissions）。 */
    private fun profileEditSettingsGearSidebarProbeVisible(vision: Text): Boolean =
        findProfileEditSettingsAndPrivacyRowHit(vision) != null

    /**
     * 进入 Settings and privacy 子页后的校验锚点：独立「Permissions」或 TikTok 常见「Security and permissions」。
     * @return 命中坐标与日志用标签
     */
    private fun findProfileEditPermissionsScreenMarkerHit(result: Text): Pair<StrictLabelHit, String>? {
        findStrictExactAcrossAdjacentLines(result, "Permissions")?.let { return it to "Permissions" }
        findStrictExactLabelHit(result, "Permissions")?.let { return it to "Permissions" }
        findStrictExactAcrossAdjacentLines(result, "Security and permissions")
            ?.let { return it to "Security and permissions" }
        findStrictExactLabelHit(result, "Security and permissions")
            ?.let { return it to "Security and permissions" }
        findLabelHitContainingIgnoreCase(result, "security and permissions")
            ?.let { return it to "Security and permissions" }
        return null
    }

    private fun findProfileEditSaveLoginInfoHit(result: Text): StrictLabelHit? =
        findStrictExactAcrossAdjacentLines(result, "Save login info")
            ?: findStrictExactLabelHit(result, "Save login info")
            ?: findLabelHitContainingIgnoreCase(result, "save login info")

    /** OCR 全文是否含 [phrase] 中任意连续词组（≥2 词，忽略大小写；折行/分 element 时合并同行再判）。 */
    private fun ocrHasConsecutiveWordsFromPhrase(result: Text, phrase: String): Boolean {
        val words = phrase.trim().lowercase(Locale.US).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return false
        val needles = buildList {
            for (start in words.indices) {
                for (end in start + 1 until words.size) {
                    add(words.subList(start, end + 1).joinToString(" "))
                }
            }
        }
        fun containsNeedle(text: String): Boolean {
            val low = text.trim().lowercase(Locale.US)
            if (low.isEmpty()) return false
            return needles.any { low.contains(it) }
        }
        val lineTexts = ArrayList<String>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                lineTexts.add(line.text)
                if (containsNeedle(line.text)) return true
                val elTexts = line.elements.map { it.text.trim() }.filter { it.isNotEmpty() }
                for (el in line.elements) {
                    if (containsNeedle(el.text)) return true
                }
                if (elTexts.size >= 2) {
                    val joined = elTexts.joinToString(" ").lowercase(Locale.US)
                    if (needles.any { joined.contains(it) }) return true
                }
            }
        }
        val full = lineTexts.joinToString(" ").lowercase(Locale.US)
        return needles.any { full.contains(it) }
    }

    /** element/line 文案（忽略大小写）包含 [substring] 即命中。 */
    private fun findLabelHitContainingIgnoreCase(result: Text, substring: String): StrictLabelHit? {
        val needle = substring.lowercase(Locale.US)
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    if (el.text.lowercase(Locale.US).contains(needle)) {
                        val b = el.boundingBox ?: continue
                        return StrictLabelHit(Point(b.centerX(), b.centerY()), b)
                    }
                }
                if (line.text.lowercase(Locale.US).contains(needle)) {
                    val b = line.boundingBox ?: continue
                    return StrictLabelHit(Point(b.centerX(), b.centerY()), b)
                }
            }
        }
        return null
    }

    /** 子串匹配：单 element/line → 同行 element 拼接 → 相邻 2～4 行拼接（OCR 折行兜底）。 */
    private fun findLabelHitContainingIgnoreCaseRelaxed(result: Text, substring: String): StrictLabelHit? {
        findLabelHitContainingIgnoreCase(result, substring)?.let { return it }
        val needle = substring.lowercase(Locale.US)
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val boxes = line.elements.mapNotNull { it.boundingBox }
                if (boxes.size >= 2) {
                    val joined = line.elements.joinToString(" ") { it.text.trim() }.lowercase(Locale.US)
                    if (joined.contains(needle)) {
                        val u = boxes.fold(boxes[0]) { acc, r ->
                            Rect(
                                minOf(acc.left, r.left),
                                minOf(acc.top, r.top),
                                maxOf(acc.right, r.right),
                                maxOf(acc.bottom, r.bottom),
                            )
                        }
                        return StrictLabelHit(Point(u.centerX(), u.centerY()), u)
                    }
                }
            }
        }
        val pairs = flattenOcrLinesWithBoxes(result)
        val n = pairs.size
        for (len in 2..4) {
            for (i in 0..n - len) {
                val slice = pairs.subList(i, i + len)
                val joined = slice.joinToString(" ") { it.first.text.trim() }.lowercase(Locale.US)
                if (!joined.contains(needle)) continue
                val u = slice.fold(slice[0].second) { acc, pair ->
                    val r = pair.second
                    Rect(
                        minOf(acc.left, r.left),
                        minOf(acc.top, r.top),
                        maxOf(acc.right, r.right),
                        maxOf(acc.bottom, r.bottom),
                    )
                }
                return StrictLabelHit(Point(u.centerX(), u.centerY()), u)
            }
        }
        return null
    }

    private fun findAutoLoginNeedHelpLoggingHit(result: Text): StrictLabelHit? =
        findLabelHitContainingIgnoreCaseRelaxed(result, "need help logging in")
            ?: findLabelHitContainingIgnoreCaseRelaxed(result, "need help logging")
            ?: findLabelHitContainingIgnoreCaseRelaxed(result, "help logging in")

    /** 严格 trim 等于 [exact] 的 element 或 line，返回点击中心与 bbox。 */
    private fun findStrictExactLabelHit(result: Text, exact: String): StrictLabelHit? {
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    if (el.text.trim() == exact) {
                        val b = el.boundingBox ?: continue
                        return StrictLabelHit(Point(b.centerX(), b.centerY()), b)
                    }
                }
                if (line.text.trim() == exact) {
                    val b = line.boundingBox ?: continue
                    return StrictLabelHit(Point(b.centerX(), b.centerY()), b)
                }
            }
        }
        return null
    }

    /** 多命中时取 bbox.top 最小（最靠上）的「Save」等，降低干扰项误点概率。 */
    private fun findTopmostStrictExactLabelHit(result: Text, exact: String): StrictLabelHit? {
        var best: StrictLabelHit? = null
        var bestTop = Int.MAX_VALUE
        fun consider(hit: StrictLabelHit) {
            val t = hit.rect.top
            if (t < bestTop) {
                bestTop = t
                best = hit
            }
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    if (el.text.trim() == exact) {
                        val b = el.boundingBox ?: continue
                        consider(StrictLabelHit(Point(b.centerX(), b.centerY()), b))
                    }
                }
                if (line.text.trim() == exact) {
                    val b = line.boundingBox ?: continue
                    consider(StrictLabelHit(Point(b.centerX(), b.centerY()), b))
                }
            }
        }
        return best
    }

    /** 多命中时取 bbox.bottom 最大（最靠下）的标签点，适合底部候选项里存在同名干扰文案的场景。 */
    private fun findBottommostStrictExactLabelHit(result: Text, exact: String): StrictLabelHit? {
        var best: StrictLabelHit? = null
        var bestBottom = Int.MIN_VALUE
        fun consider(hit: StrictLabelHit) {
            val b = hit.rect.bottom
            if (b > bestBottom) {
                bestBottom = b
                best = hit
            }
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    if (el.text.trim() == exact) {
                        val b = el.boundingBox ?: continue
                        consider(StrictLabelHit(Point(b.centerX(), b.centerY()), b))
                    }
                }
                if (line.text.trim() == exact) {
                    val b = line.boundingBox ?: continue
                    consider(StrictLabelHit(Point(b.centerX(), b.centerY()), b))
                }
            }
        }
        return best
    }

    /** 资料编辑页三项菜单：各自独立词，strict trim 全等匹配，不按「一整串」拼接。 */
    private data class ProfileEditMenuTriple(
        val changePhoto: StrictLabelHit,
        val name: StrictLabelHit,
        val bio: StrictLabelHit,
    )

    private fun unionThreeRects(r1: Rect, r2: Rect, r3: Rect): Rect =
        Rect(
            minOf(r1.left, r2.left, r3.left),
            minOf(r1.top, r2.top, r3.top),
            maxOf(r1.right, r2.right, r3.right),
            maxOf(r1.bottom, r2.bottom, r3.bottom),
        )

    /** 资料页头像入口文案：部分账号显示 Change photo，另一些为 Edit photo 或 Avatar。 */
    private val PROFILE_EDIT_CHANGE_PHOTO_LABELS = listOf("Change photo", "Edit photo", "Avatar")

    private fun findProfileEditChangePhotoMarkerHit(result: Text): Pair<StrictLabelHit, String>? {
        for (label in PROFILE_EDIT_CHANGE_PHOTO_LABELS) {
            findStrictExactLabelHit(result, label)?.let { return it to label }
        }
        return null
    }

    private fun findProfileEditChangePhotoMarkerSeen(result: Text): Boolean =
        findProfileEditChangePhotoMarkerHit(result) != null

    /** 点头像入口后选图方式：部分账号显示 Upload photo，另一些为 Select from Gallery。 */
    private val PROFILE_EDIT_UPLOAD_PHOTO_LABELS = listOf("Upload photo", "Select from Gallery")

    private fun findProfileEditUploadPhotoHit(result: Text): Pair<StrictLabelHit, String>? {
        for (label in PROFILE_EDIT_UPLOAD_PHOTO_LABELS) {
            findStrictExactLabelHit(result, label)?.let { return it to label }
        }
        return null
    }

    /** 同一屏内独立「Change photo」/「Edit photo」/「Avatar」「Name」/「name」「Bio」三词坐标（仅记录）。 */
    private fun findProfileEditChangePhotoNameBioTriple(result: Text): ProfileEditMenuTriple? {
        val (cp, _) = findProfileEditChangePhotoMarkerHit(result) ?: return null
        val nm = findStrictExactLabelHit(result, "Name")
            ?: findStrictExactLabelHit(result, "name")
            ?: return null
        val bio = findStrictExactLabelHit(result, "Bio") ?: return null
        return ProfileEditMenuTriple(cp, nm, bio)
    }

    private fun flattenOcrLinesWithBoxes(result: Text): List<Pair<Text.Line, Rect>> {
        val out = ArrayList<Pair<Text.Line, Rect>>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val b = line.boundingBox ?: continue
                if (line.text.trim().isEmpty()) continue
                out.add(line to b)
            }
        }
        return out
    }

    /** 单行 strict 优先；否则合并相邻 2～6 行（空格拼接）与 [exact] 全等（如「Post this photo to Story」折行）。 */
    private fun findStrictExactAcrossAdjacentLines(result: Text, exact: String): StrictLabelHit? {
        findStrictExactLabelHit(result, exact)?.let { return it }
        val pairs = flattenOcrLinesWithBoxes(result)
        val n = pairs.size
        if (n < 2) return null
        for (len in 2..6) {
            for (i in 0..n - len) {
                val slice = pairs.subList(i, i + len)
                val joined = slice.joinToString(" ") { it.first.text.trim() }
                if (joined != exact) continue
                val u = slice.fold(slice[0].second) { acc, pair ->
                    val r = pair.second
                    Rect(
                        minOf(acc.left, r.left),
                        minOf(acc.top, r.top),
                        maxOf(acc.right, r.right),
                        maxOf(acc.bottom, r.bottom),
                    )
                }
                return StrictLabelHit(Point(u.centerX(), u.centerY()), u)
            }
        }
        return null
    }

    private fun coerceTapToScreen(p: Point, w: Int, h: Int): Point =
        Point(
            p.x.coerceIn(1, (w - 1).coerceAtLeast(1)),
            p.y.coerceIn(1, (h - 1).coerceAtLeast(1)),
        )

    private fun visionShowsAccountStatusLogoutDialog(vision: Text): Boolean {
        val text = vision.text.lowercase(Locale.US)
        return text.contains("account status")
    }

    private fun findAccountStatusOkButtonHit(vision: Text): StrictLabelHit? =
        findBottommostStrictExactLabelHit(vision, "OK")
            ?: findBottommostStrictExactLabelHit(vision, "Ok")

    private fun tapAccountStatusOk(alias: String, vision: Text, w: Int, h: Int): Boolean {
        val okHit = findAccountStatusOkButtonHit(vision)
        if (okHit != null) {
            val tap = coerceTapToScreen(okHit.point, w, h)
            devOp(alias, "掉登录：点 OK (${tap.x},${tap.y})")
            return runSuCommand("input tap ${tap.x} ${tap.y}")
        }
        val fx = (w * 0.72f).toInt().coerceIn(1, w - 1)
        val fy = (h * 0.52f).toInt().coerceIn(1, h - 1)
        devOp(alias, "掉登录：未 OCR 到 OK，比例点 (${fx},${fy})")
        return runSuCommand("input tap $fx $fy")
    }

    /** 掉登录恢复期间专用 OCR（不触发弹窗检测与其它顺手点击）。 */
    private suspend fun accountStatusRecoveryOcrOnce(ctx: Context, tag: String): Text? {
        val (vision, err) = mlKitRecognizeFullScreenCore(ctx, tag)
        if (vision == null && err != "hard_stop") {
            devOp(
                PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown",
                "掉登录复核 OCR 失败：$err [$tag]",
            )
        }
        return vision
    }

    /**
     * 点 OK 后最长 [ACCOUNT_STATUS_LOGOUT_OK_WAIT_MS]：周期性 OCR，若仍见 Account status 则再点 OK。
     */
    private suspend fun waitAndDismissAccountStatusDialog(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
    ) {
        val deadline = SystemClock.elapsedRealtime() + ACCOUNT_STATUS_LOGOUT_OK_WAIT_MS
        var pass = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hardStopOrNull(alias, "掉登录等待OK")) {
                throw AccountStatusLoggedOutException("硬停")
            }
            coroutineContext.ensureActive()
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            delay(minOf(ACCOUNT_STATUS_LOGOUT_OK_POLL_INTERVAL_MS, remaining))
            pass++
            val probe = accountStatusRecoveryOcrOnce(ctx, "account_status_ok_verify_$pass") ?: continue
            if (!visionShowsAccountStatusLogoutDialog(probe)) {
                if (visionShowsWelcomeBackLogoutScreen(probe)) {
                    devOp(
                        alias,
                        "掉登录：OK 后见 Add another account / Welcome back（第 $pass 次复核 OCR），从登录入口续接自动登录",
                    )
                    throw LoginSafeguardRequiresAutoLoginException()
                }
                devOp(alias, "掉登录：OK 已生效，Account status 弹窗已消失（第 $pass 次复核 OCR）")
                return
            }
            devOp(alias, "掉登录：Account status 仍在，再次点 OK（第 $pass 次复核 OCR）")
            tapAccountStatusOk(alias, probe, w, h)
        }
        devOp(alias, "掉登录：${ACCOUNT_STATUS_LOGOUT_OK_WAIT_MS}ms 复核结束（弹窗可能仍在）")
    }

    /**
     * 掉登录弹窗：点 OK → 3s 内 OCR 复核并补点 → 强停 TikTok → 冷启；随后抛出 [AccountStatusLoggedOutException]。
     */
    private suspend fun performAccountStatusLogoutRecovery(
        ctx: Context,
        alias: String,
        vision: Text,
    ): Nothing {
        if (accountStatusRecoveryInProgress) {
            throw AccountStatusLoggedOutException("掉登录恢复重入")
        }
        accountStatusRecoveryInProgress = true
        try {
            if (hardStopOrNull(alias, "掉登录恢复")) {
                throw AccountStatusLoggedOutException("硬停")
            }
            devOp(alias, "掉登录：检测到 Account status 弹窗，立即终止当前步骤")
            val dm = ctx.resources.displayMetrics
            val w = dm.widthPixels.coerceAtLeast(1)
            val h = dm.heightPixels.coerceAtLeast(1)
            tapAccountStatusOk(alias, vision, w, h)
            waitAndDismissAccountStatusDialog(ctx, alias, w, h)
            val pkg = Config.TIKTOK_PACKAGE_NAME
            devOp(alias, "掉登录：强停 TikTok 后台")
            runSuCommand("am force-stop $pkg")
            val forceStopWaitMs = lastConfigSnapshot.tiktok_force_stop_wait_ms.coerceIn(100L, 8000L)
            delay(forceStopWaitMs)
            val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
            val launched = launchTikTokOnceWithCheck(
                ctx = ctx,
                alias = alias,
                tag = "掉登录恢复",
                launchWaitMs = launchWaitMs,
                forceStopBeforeLaunch = false,
            )
            devOp(alias, "掉登录：重新打开 TikTok ${if (launched) "成功" else "失败"}，待重新自动登录")
        } finally {
            accountStatusRecoveryInProgress = false
        }
        throw AccountStatusLoggedOutException("Account status 掉登录已处理，需重新自动登录")
    }

    /** 点 OK 后的 Welcome back 等：同屏见 Add another account / another account（Account status 弹窗已消失）。 */
    private fun visionShowsWelcomeBackLogoutScreen(vision: Text): Boolean =
        findAddAnotherAccountTapHit(vision) != null

    /**
     * 统一掉登录探测：Account status 弹窗 + Welcome back（Add another account / another account）。
     * 凡开启 Account status 观测的节点，同帧也检测 Add another account；后者走 [LoginSafeguardRequiresAutoLoginException]。
     */
    private suspend fun probeAndHandleLogoutSignalsFromVision(
        ctx: Context,
        alias: String,
        vision: Text,
        fileTag: String?,
    ) {
        if (accountStatusRecoveryInProgress) return
        val observeAccountStatus = shouldObserveAccountStatusOnOcr(fileTag)
        val observeAddAnother = shouldObserveAddAnotherAccountLogoutOnOcr(fileTag)
        if (!observeAccountStatus && !observeAddAnother) return

        if (observeAccountStatus && visionShowsAccountStatusLogoutDialog(vision)) {
            performAccountStatusLogoutRecovery(ctx, alias, vision)
        }
        if ((observeAccountStatus || observeAddAnother) && visionShowsWelcomeBackLogoutScreen(vision)) {
            maybeHandleAddAnotherAccountLogoutFromVision(ctx, alias, vision, fileTag)
        }
    }

    /**
     * 任务执行中 OCR 见到「Add another account」：与 [safeguardVisionNeedsAutoLogin] 判定一致，视为 TikTok 已掉登录。
     * 不强停/冷启（与 Account status 弹窗路径区分），由协调器从当前登录入口续接自动登录链。
     */
    private suspend fun maybeHandleAddAnotherAccountLogoutFromVision(
        ctx: Context,
        alias: String,
        vision: Text,
        fileTag: String?,
    ) {
        if (accountStatusRecoveryInProgress) return
        if (findAddAnotherAccountTapHit(vision) == null) return
        devOp(
            alias,
            "掉登录：OCR 见 Add another account（tag=${fileTag ?: "unknown"}），" +
                "与登录状态保障一致，中止当前步骤待自动登录",
        )
        throw LoginSafeguardRequiresAutoLoginException()
    }

    /** 整屏截屏 + ML Kit OCR（无副作用：不检测掉登录、不点 Don't allow）。 */
    private suspend fun mlKitRecognizeFullScreenCore(ctx: Context, fileTag: String): Pair<Text?, String> {
        coroutineContext.ensureActive()
        if (isHardStopActive()) return null to "hard_stop"
        val screenshotFile = File(ctx.cacheDir, "${fileTag}_${System.nanoTime()}.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) {
            return null to "screencap_failed"
        }
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return null to "bitmap_decode_failed"
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var saveOcrDebug = false
        return try {
            val result = suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            result to "ok"
        } catch (ex: Exception) {
            saveOcrDebug = true
            null to "mlkit:${ex.javaClass.simpleName}:${ex.message?.take(120)}"
        } finally {
            recognizer.close()
            bitmap.recycle()
            if (saveOcrDebug) {
                OcrDebugKeeper.saveOnFailure(screenshotFile, fileTag)
            }
            screenshotFile.delete()
        }
    }

    /**
     * 整屏截屏 + ML Kit OCR；失败时返回 null 与简短原因（不入库，仅用于 [ProfileEditChainResult]）。
     */
    private suspend fun mlKitRecognizeFullScreen(ctx: Context, fileTag: String): Pair<Text?, String> {
        return try {
            val (result, err) = mlKitRecognizeFullScreenCore(ctx, fileTag)
            if (result == null) return null to err
            var vision = if (!inlineIdentityVerificationInProgress) {
                dismissSwipeUpPromptUntilGone(ctx, result, fileTag, "OCR顺手处理")
            } else {
                result
            }
            if (vision == null) return null to "hard_stop"
            observeAccountStatusOnOcrText(ctx, vision, fileTag)
            // 通用兜底：任意 OCR 后若同屏出现 Don't allow，顺手点击并继续原流程。
            // 发作品（publish_post_*）、修改基本资料（profile_edit_*）不在此顺手点。
            if (!fileTag.contains("dont_allow", ignoreCase = true) &&
                !fileTag.startsWith("publish_post_") &&
                !fileTag.startsWith("profile_edit_")
            ) {
                runCatching {
                    tapDontAllowFromVisionIfPresent(
                        ctx = ctx,
                        alias = null,
                        vision = vision,
                        phaseLog = "OCR顺手处理",
                        useAutoLoginJitter = false,
                        markAutoLoginHandled = false,
                    )
                }
            }
            // 任意整屏 OCR：顺手关 Viewer history / Save login for next time 弹窗，并令调用方重试 OCR。
            // auto_login_* / login_safeguard_* 由 [autoLoginTryDismissInlineOverlaysFromVision] 处理。
            if (!inlineIdentityVerificationInProgress) {
                handleOcrVisionInlineInterruptions(ctx, vision, fileTag)?.let { interruptTag ->
                    return null to interruptTag
                }
            }
            vision to "ok"
        } catch (ex: AccountStatusLoggedOutException) {
            throw ex
        } catch (ex: LoginSafeguardRequiresAutoLoginException) {
            throw ex
        }
    }

    private suspend fun tapDontAllowFromVisionIfPresent(
        ctx: Context,
        alias: String?,
        vision: Text,
        phaseLog: String,
        useAutoLoginJitter: Boolean,
        markAutoLoginHandled: Boolean,
    ): Boolean {
        val hit = findDontAllowLabelHit(vision) ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastInlineDontAllowTapAtMs < INLINE_DONT_ALLOW_TAP_COOLDOWN_MS) return false
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val tap = if (useAutoLoginJitter) autoLoginTapWithJitter(hit.point, w, h) else coerceTapToScreen(hit.point, w, h)
        alias?.trim()?.takeIf { it.isNotEmpty() }?.let { safeAlias ->
            devOp(safeAlias, "$phaseLog：顺手命中 Don't allow，tap (${tap.x},${tap.y})")
        }
        val ok = runSuCommand("input tap ${tap.x} ${tap.y}")
        if (!ok) return false
        lastInlineDontAllowTapAtMs = SystemClock.elapsedRealtime()
        if (markAutoLoginHandled) autoLoginDontAllowHandledInThisChain = true
        delay(250L)
        return true
    }

    private fun visionHasViewerHistoryLabel(vision: Text): Boolean =
        findStrictExactLabelHit(vision, "Viewer history") != null
            || findStrictExactAcrossAdjacentLines(vision, "Viewer history") != null

    /** 同屏同时见 Viewer history 与 Save 时视为弹窗，点最上方 Save 关闭。 */
    private suspend fun tryDismissViewerHistoryPopupFromVision(
        ctx: Context,
        alias: String?,
        vision: Text,
        phaseLog: String,
        tapWithJitter: Boolean,
    ): Boolean {
        if (!visionHasViewerHistoryLabel(vision)) return false
        val saveHit = findTopmostStrictExactLabelHit(vision, "Save") ?: run {
            alias?.trim()?.takeIf { it.isNotEmpty() }?.let { safeAlias ->
                devOp(safeAlias, "$phaseLog：见 Viewer history 但未同屏命中 Save，跳过弹窗处理")
            }
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastViewerHistoryPopupDismissAtMs < INLINE_VIEWER_HISTORY_POPUP_DISMISS_COOLDOWN_MS) {
            return false
        }
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val tap = if (tapWithJitter) {
            autoLoginTapWithJitter(saveHit.point, w, h)
        } else {
            coerceTapToScreen(saveHit.point, w, h)
        }
        alias?.trim()?.takeIf { it.isNotEmpty() }?.let { safeAlias ->
            devOp(safeAlias, "$phaseLog：Viewer history 弹窗，点 Save (${tap.x},${tap.y})")
        }
        if (!runSuCommand("input tap ${tap.x} ${tap.y}")) return false
        lastViewerHistoryPopupDismissAtMs = SystemClock.elapsedRealtime()
        delay(350L)
        return true
    }

    private fun visionHasSaveLoginForNextTimePopup(vision: Text): Boolean {
        if (findLabelHitContainingIgnoreCase(vision, "save login for next time") != null) return true
        if (findStrictExactAcrossAdjacentLines(vision, "Save login for next time?") != null) return true
        val hasNotNow = findStrictExactLabelHit(vision, "Not now") != null
            || findLabelHitContainingIgnoreCase(vision, "not now") != null
        if (!hasNotNow) return false
        if (findSaveLoginPopupButtonHit(vision) != null) return true
        if (findLabelHitContainingIgnoreCase(vision, "without needing to enter your info") != null) return true
        if (findLabelHitContainingIgnoreCase(vision, "change this at any time in settings") != null) return true
        if (findLabelHitContainingIgnoreCase(vision, "log in to") != null) return true
        return false
    }

    /** 「Save login for next time?」弹窗右下角按钮；取最靠下命中避免误点 Settings 里的 Save login info。 */
    private fun findSaveLoginPopupButtonHit(vision: Text, screenH: Int = 0): StrictLabelHit? =
        findBottommostStrictExactLabelHit(vision, "Save login")
            ?: findStrictExactAcrossAdjacentLines(vision, "Save login")
            ?: findSaveLoginButtonRightOfNotNow(vision)
            ?: findMiddleBandBottommostSaveLoginButton(vision, screenH)

    /** Not now 右侧的 Save login 按钮（弹窗底栏分左右两钮）。 */
    private fun findSaveLoginButtonRightOfNotNow(vision: Text): StrictLabelHit? {
        val notNowHit = findStrictExactLabelHit(vision, "Not now")
            ?: findLabelHitContainingIgnoreCase(vision, "not now")
            ?: return null
        val anchorY = notNowHit.rect.centerY()
        val ySlack = (notNowHit.rect.height() * 0.85f).toInt().coerceAtLeast(14)
        var best: StrictLabelHit? = null
        var bestLeft = Int.MIN_VALUE
        fun consider(text: String, box: Rect) {
            val t = text.trim().lowercase(Locale.US)
            if (t.contains("info")) return
            val isSaveLogin = t == "save login" ||
                t == "save" ||
                t == "login" ||
                (t.startsWith("save") && t.contains("login"))
            if (!isSaveLogin) return
            if (kotlin.math.abs(box.centerY() - anchorY) > ySlack) return
            if (box.left <= notNowHit.rect.right - 6) return
            if (box.left > bestLeft) {
                bestLeft = box.left
                best = StrictLabelHit(Point(box.centerX(), box.centerY()), box)
            }
        }
        for (block in vision.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { consider(line.text, it) }
                for (el in line.elements) {
                    el.boundingBox?.let { consider(el.text, it) }
                }
            }
        }
        return best
    }

    /** 弹窗区域（屏高中部偏下）内最靠下的 Save login 文案，排除 Settings 页的 Save login info。 */
    private fun findMiddleBandBottommostSaveLoginButton(vision: Text, screenH: Int): StrictLabelHit? {
        if (screenH <= 0) return null
        val bandTop = (screenH * 0.34f).toInt()
        val bandBottom = (screenH * 0.74f).toInt()
        var best: StrictLabelHit? = null
        var bestBottom = Int.MIN_VALUE
        fun consider(text: String, box: Rect) {
            val t = text.trim().lowercase(Locale.US)
            if (t.contains("info")) return
            if (t != "save login" && !t.matches(Regex("save\\s+login[.!?]?"))) return
            if (box.centerY() < bandTop || box.centerY() > bandBottom) return
            if (box.bottom > bestBottom) {
                bestBottom = box.bottom
                best = StrictLabelHit(Point(box.centerX(), box.centerY()), box)
            }
        }
        for (block in vision.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { consider(line.text, it) }
                for (el in line.elements) {
                    el.boundingBox?.let { consider(el.text, it) }
                }
            }
        }
        return best
    }

    /**
     * 任意 OCR vision 上顺手处理弹窗/验证（Save login、Viewer history、Before you continue 身份验证等）。
     * @return 非 null 表示已处理中断，调用方应重新 OCR（勿继续用过期 vision）。
     */
    private suspend fun handleOcrVisionInlineInterruptions(
        ctx: Context,
        vision: Text,
        fileTag: String?,
        phaseLog: String = "OCR顺手",
    ): String? {
        if (fileTag != null &&
            (fileTag.startsWith("auto_login_") || fileTag.startsWith("login_safeguard_"))
        ) {
            return null
        }
        dismissFullScreenInlinePopupsIfPresent(ctx, vision, fileTag, phaseLog)?.let { return it }
        if (visionIdentityVerificationBlocking(vision)) {
            val ok = runInlineIdentityVerificationFlow(
                ctx = ctx,
                fileTag = fileTag,
                phaseLog = phaseLog,
                triggerVision = vision,
            )
            return if (ok) "identity_verification_handled" else "identity_verification_failed"
        }
        return null
    }

    /**
     * 整屏 OCR 后顺手关弹窗（Viewer history / Save login for next time 等）。
     * @return 非 null 表示已点击关弹窗，调用方应重新 OCR（勿继续用过期 vision）。
     */
    private suspend fun dismissFullScreenInlinePopupsIfPresent(
        ctx: Context,
        vision: Text,
        fileTag: String?,
        phaseLog: String = "OCR顺手",
    ): String? {
        if (fileTag != null &&
            (fileTag.startsWith("auto_login_") || fileTag.startsWith("login_safeguard_"))
        ) {
            return null
        }
        val alias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() }
        if (fileTag != null && fileTag.startsWith("profile_edit_")) {
            val viewerDismissed = runCatching {
                tryDismissViewerHistoryPopupFromVision(
                    ctx = ctx,
                    alias = alias,
                    vision = vision,
                    phaseLog = "「修改基本资料」$phaseLog",
                    tapWithJitter = false,
                )
            }.getOrDefault(false)
            if (viewerDismissed) return "viewer_history_popup_dismissed"
        }
        val saveLoginDismissed = runCatching {
            tryDismissSaveLoginForNextTimePopupFromVision(
                ctx = ctx,
                alias = alias,
                vision = vision,
                phaseLog = phaseLog,
                tapWithJitter = false,
            )
        }.getOrDefault(false)
        if (saveLoginDismissed) return "save_login_popup_dismissed"
        return null
    }

    /** 同屏见「Save login for next time?」弹窗时，点偏下方 Save login 关闭。 */
    private suspend fun tryDismissSaveLoginForNextTimePopupFromVision(
        ctx: Context,
        alias: String?,
        vision: Text,
        phaseLog: String,
        tapWithJitter: Boolean,
    ): Boolean {
        if (!visionHasSaveLoginForNextTimePopup(vision)) return false
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val saveLoginHit = findSaveLoginPopupButtonHit(vision, h) ?: run {
            val notNowHit = findStrictExactLabelHit(vision, "Not now")
                ?: findLabelHitContainingIgnoreCase(vision, "not now")
            val fx = notNowHit?.let { (it.rect.right + w * 0.14f).toInt() } ?: (w * 0.62f).toInt()
            val fy = notNowHit?.rect?.centerY() ?: (h * 0.58f).toInt()
            val px = fx.coerceIn(1, w - 1)
            val py = fy.coerceIn(1, h - 1)
            alias?.trim()?.takeIf { it.isNotEmpty() }?.let { safeAlias ->
                devOp(safeAlias, "$phaseLog：见 Save login 弹窗未 OCR 到按钮，比例点兜底 (${px},${py})")
            }
            StrictLabelHit(Point(px, py), Rect(px - 1, py - 1, px + 1, py + 1))
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSaveLoginForNextTimePopupDismissAtMs < INLINE_SAVE_LOGIN_POPUP_DISMISS_COOLDOWN_MS) {
            return false
        }
        val tap = if (tapWithJitter) {
            autoLoginTapWithJitter(saveLoginHit.point, w, h)
        } else {
            coerceTapToScreen(saveLoginHit.point, w, h)
        }
        alias?.trim()?.takeIf { it.isNotEmpty() }?.let { safeAlias ->
            devOp(safeAlias, "$phaseLog：Save login for next time 弹窗，点 Save login (${tap.x},${tap.y})")
        }
        if (!runSuCommand("input tap ${tap.x} ${tap.y}")) return false
        lastSaveLoginForNextTimePopupDismissAtMs = SystemClock.elapsedRealtime()
        delay(450L)
        return true
    }

    private fun visionHasBeforeYouContinueText(vision: Text): Boolean =
        findLabelHitContainingIgnoreCase(vision, "before you continue") != null

    private fun visionHasVerifyIdentityTitle(vision: Text): Boolean =
        findStrictExactAcrossAdjacentLines(vision, "Verify identity") != null
            || findLabelHitContainingIgnoreCase(vision, "verify identity") != null

    private fun visionHasIdentityMethodSelectionPage(vision: Text): Boolean {
        if (!visionHasBeforeYouContinueText(vision)) return false
        return visionHasVerifyIdentityTitle(vision)
            || findLabelHitContainingIgnoreCase(vision, "verify it's really you") != null
            || findLabelHitContainingIgnoreCase(vision, "verify its really you") != null
    }

    private fun visionHasIdentityCodeEntryPage(vision: Text): Boolean =
        visionHasVerifyIdentityTitle(vision) &&
            (
                findLabelHitContainingIgnoreCase(vision, "enter 6-digit code") != null
                    || findLabelHitContainingIgnoreCase(vision, "6-digit code") != null
                    || findLabelHitContainingIgnoreCase(vision, "digit code") != null
                    || findLabelHitContainingIgnoreCase(vision, "send code") != null
                    || findLabelHitContainingIgnoreCase(vision, "resend code") != null
                )

    private fun visionIdentityVerificationBlocking(vision: Text): Boolean =
        visionHasIdentityMethodSelectionPage(vision) || visionHasIdentityCodeEntryPage(vision)

    /** 验证方式页：取最靠上的 Outlook 邮箱行。 */
    private fun findTopmostOutlookEmailHit(vision: Text): StrictLabelHit? {
        var best: StrictLabelHit? = null
        var bestTop = Int.MAX_VALUE
        fun consider(text: String, box: Rect) {
            val low = text.trim().lowercase(Locale.US)
            if (!low.contains("outlook.com") && !low.contains("@outlook")) return
            if (box.top < bestTop) {
                bestTop = box.top
                best = StrictLabelHit(Point(box.centerX(), box.centerY()), box)
            }
        }
        for (block in vision.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { consider(line.text, it) }
                for (el in line.elements) {
                    el.boundingBox?.let { consider(el.text, it) }
                }
            }
        }
        return best
    }

    private fun ocrTextHasActiveSendOrResendCountdown(text: String): Boolean {
        val low = text.trim().lowercase(Locale.US)
        if (!low.contains("send code") && !low.contains("resend code")) return false
        return Regex("\\d+\\s*s").containsMatchIn(low)
    }

    private fun visionHasResendOrSendCodeCountdown(vision: Text): Boolean {
        for (block in vision.textBlocks) {
            for (line in block.lines) {
                if (ocrTextHasActiveSendOrResendCountdown(line.text)) return true
                for (el in line.elements) {
                    if (ocrTextHasActiveSendOrResendCountdown(el.text)) return true
                }
            }
        }
        return false
    }

    /** 身份验证专用 OCR：不走 inline 中断，避免递归。 */
    private suspend fun identityVerifyOcrOnce(ctx: Context, alias: String, tag: String): Text? {
        val (vision, err) = mlKitRecognizeFullScreenCore(ctx, tag)
        if (vision == null) {
            devOp(alias, "身份验证 OCR 失败：$err [$tag]")
            return null
        }
        runCatching { observeAccountStatusOnOcrText(ctx, vision, tag) }
        return vision
    }

    private suspend fun resolveInlineVerificationEmailCredentials(alias: String): Pair<String, String>? {
        val deviceId = PrefsStore.getDeviceId()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val devAlias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resp: DeviceAssignedAccountResponse? = if (PrefsStore.isAutoSwitchAutonomousMode()) {
            AutoSwitchBundleHolder.resolveAssignedAccount(
                logContextUsername = PrefsStore.getLogContextUsername(),
            )
        } else {
            try {
                withContext(Dispatchers.IO) {
                    ApiService.getDeviceAssignedAccount(deviceId, devAlias)
                }.takeIf { it.code == 0 }
            } catch (_: Exception) {
                null
            }
        }
        val email = resp?.email?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val apiUrl = resp.api?.trim().orEmpty()
        return email to apiUrl
    }

    private suspend fun identityVerifyTapOutlookAndNext(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        vision: Text,
        tagPrefix: String,
    ): Boolean {
        val outlookHit = findTopmostOutlookEmailHit(vision) ?: run {
            devOp(alias, "身份验证：未 OCR 到 Outlook 邮箱行")
            return false
        }
        var tap = coerceTapToScreen(outlookHit.point, w, h)
        devOp(alias, "身份验证：点 Outlook (${tap.x},${tap.y})")
        if (!runSuCommand("input tap ${tap.x} ${tap.y}")) return false
        if (!identityVerifyDelay(alias, 500L, "Outlook 后")) return false
        for (pass in 1..4) {
            if (pass > 1 && !identityVerifyDelay(alias, INLINE_IDENTITY_VERIFY_OCR_INTERVAL_MS, "定位 Next")) {
                return false
            }
            val nextVision = identityVerifyOcrOnce(ctx, alias, "${tagPrefix}_next_$pass") ?: continue
            val nextHit = findStrictExactLabelHit(nextVision, "Next")
                ?: findStrictExactLabelHit(nextVision, "next")
                ?: findBottommostStrictExactLabelHit(nextVision, "Next")
            if (nextHit != null) {
                tap = coerceTapToScreen(nextHit.point, w, h)
                devOp(alias, "身份验证：点 Next (${tap.x},${tap.y})")
                return runSuCommand("input tap ${tap.x} ${tap.y}")
            }
        }
        devOp(alias, "身份验证：未 OCR 到 Next 按钮")
        return false
    }

    private suspend fun identityVerifyWaitMethodPageTransition(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        tagPrefix: String,
    ): Boolean {
        for (redo in 0..INLINE_IDENTITY_VERIFY_OUTLOOK_NEXT_REDO_MAX) {
            for (poll in 1..INLINE_IDENTITY_VERIFY_WAIT_GONE_MAX_POLLS) {
                if (poll > 1 && !identityVerifyDelay(alias, INLINE_IDENTITY_VERIFY_OCR_INTERVAL_MS, "等 Before you continue 消失")) {
                    return false
                }
                val vision = identityVerifyOcrOnce(ctx, alias, "${tagPrefix}_gone_${redo}_$poll") ?: continue
                val beforeGone = !visionHasBeforeYouContinueText(vision)
                val codePage = visionHasIdentityCodeEntryPage(vision)
                if (beforeGone && codePage) {
                    devOp(alias, "身份验证：Before you continue 已消失，已进入验证码页")
                    return true
                }
            }
            if (redo >= INLINE_IDENTITY_VERIFY_OUTLOOK_NEXT_REDO_MAX) break
            devOp(alias, "身份验证：Before you continue 仍在，第 ${redo + 1} 次重试 Outlook+Next")
            val vision = identityVerifyOcrOnce(ctx, alias, "${tagPrefix}_redo_vision_$redo") ?: continue
            if (!identityVerifyTapOutlookAndNext(ctx, alias, w, h, vision, "${tagPrefix}_redo_$redo")) {
                continue
            }
            if (!identityVerifyDelay(alias, INLINE_IDENTITY_VERIFY_OCR_INTERVAL_MS, "重试 Outlook+Next 后")) {
                return false
            }
        }
        devOp(alias, "身份验证：等待 Before you continue 消失超时")
        return false
    }

    private suspend fun identityVerifyWaitAndOcrTapResendCode(
        ctx: Context,
        alias: String,
        tagPrefix: String,
        w: Int,
        h: Int,
    ): Boolean {
        val maxPolls = 80
        for (poll in 1..maxPolls) {
            if (hardStopOrNull(alias, "身份验证-等 Resend")) return false
            if (poll > 1 && !identityVerifyDelay(alias, INLINE_IDENTITY_VERIFY_OCR_INTERVAL_MS, "等 Resend 倒计时")) {
                return false
            }
            val vision = identityVerifyOcrOnce(ctx, alias, "${tagPrefix}_resend_wait_$poll") ?: continue
            if (visionHasResendOrSendCodeCountdown(vision)) continue
            val resendHit = findLabelHitContainingIgnoreCase(vision, "resend code")
                ?: findLabelHitContainingIgnoreCase(vision, "send code")
            if (resendHit == null) continue
            val now = SystemClock.elapsedRealtime()
            if (now - lastInlineIdentityResendTapAtMs < INLINE_IDENTITY_VERIFY_RESEND_TAP_COOLDOWN_MS) {
                return false
            }
            val tap = coerceTapToScreen(resendHit.point, w, h)
            devOp(alias, "身份验证：倒计时结束，点 Resend/Send code (${tap.x},${tap.y})")
            if (!runSuCommand("input tap ${tap.x} ${tap.y}")) continue
            lastInlineIdentityResendTapAtMs = SystemClock.elapsedRealtime()
            if (!identityVerifyDelay(alias, 450L, "Resend 点击后")) return false
            return true
        }
        devOp(alias, "身份验证：等待 Resend 倒计时结束超时")
        return false
    }

    private suspend fun identityVerifyDelay(alias: String, ms: Long, label: String): Boolean {
        if (hardStopOrNull(alias, "身份验证-$label")) return false
        delay(ms.coerceAtLeast(0L))
        return true
    }

    private suspend fun identityVerifyEnterCodeAndComplete(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        deviceId: String,
        apiUrl: String,
        email: String,
        tagPrefix: String,
        phaseLog: String,
    ): Boolean {
        suspend fun fetchCodeOrNull(): String? {
            val codeResult = TikTokEmailCodeFetcher.fetchCode(deviceId, alias, apiUrl, email)
            val code = codeResult.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            if (code == null) {
                devOp(alias, "$phaseLog：拉取验证码失败：${codeResult.exceptionOrNull()?.message}")
            }
            return code
        }

        suspend fun inputCode(code: String, tag: String): Boolean {
            val focusVision = identityVerifyOcrOnce(ctx, alias, "${tag}_focus")
            autoLoginTapVerificationCodeField(ctx, alias, w, h, focusVision)
            autoLoginClearFocusedInput(alias)
            if (!autoLoginInputText(alias, code)) return false
            return identityVerifyDelay(alias, 400L, "验证码输入后")
        }

        val firstCode = fetchCodeOrNull()
        if (firstCode == null) {
            if (TikTokEmailCodeFetcher.consumeLastFetchUnrecoverableConfig()) {
                devOp(alias, "$phaseLog：邮箱验证码配置不可恢复")
            }
            return false
        }
        if (!inputCode(firstCode, "${tagPrefix}_first")) return false

        val startedAt = System.currentTimeMillis()
        var ocrPass = 0
        while (System.currentTimeMillis() - startedAt < INLINE_IDENTITY_VERIFY_CODE_POLL_MAX_MS) {
            if (hardStopOrNull(alias, "身份验证-验证码后")) return false
            ocrPass++
            if (ocrPass > 1 && !identityVerifyDelay(alias, INLINE_IDENTITY_VERIFY_OCR_INTERVAL_MS, "验证码后 OCR")) {
                return false
            }
            val vision = identityVerifyOcrOnce(ctx, alias, "${tagPrefix}_after_code_$ocrPass") ?: continue
            if (!visionIdentityVerificationBlocking(vision)) {
                devOp(alias, "$phaseLog：验证页已消失，身份验证完成")
                return true
            }
            val expiredOrFailed =
                findLabelHitContainingIgnoreCase(vision, "expired") != null
                    || findLabelHitContainingIgnoreCase(vision, "failed") != null
            if (expiredOrFailed) {
                devOp(alias, "$phaseLog：识别 expired/failed，等待倒计时后 Resend 并重拉验证码")
                autoLoginClearFocusedInput(alias)
                if (!identityVerifyWaitAndOcrTapResendCode(ctx, alias, tagPrefix, w, h)) return false
                if (!identityVerifyDelay(alias, AUTO_LOGIN_RESEND_WAIT_MS, "Resend 后等码")) return false
                val newCode = fetchCodeOrNull() ?: return false
                if (!inputCode(newCode, "${tagPrefix}_resend_$ocrPass")) return false
            }
        }
        devOp(alias, "$phaseLog：验证码提交后等待验证完成超时")
        return false
    }

    private suspend fun runInlineIdentityVerificationFlow(
        ctx: Context,
        fileTag: String?,
        phaseLog: String,
        triggerVision: Text,
    ): Boolean {
        if (inlineIdentityVerificationInProgress) return false
        val alias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        if (!visionIdentityVerificationBlocking(triggerVision)) return false
        inlineIdentityVerificationInProgress = true
        try {
            return runInlineIdentityVerificationFlowCore(ctx, alias, fileTag, phaseLog, triggerVision)
        } finally {
            inlineIdentityVerificationInProgress = false
        }
    }

    private suspend fun runInlineIdentityVerificationFlowCore(
        ctx: Context,
        alias: String,
        fileTag: String?,
        phaseLog: String,
        triggerVision: Text,
    ): Boolean {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val creds = resolveInlineVerificationEmailCredentials(alias)
        if (creds == null) {
            devOp(alias, "$phaseLog：身份验证需要邮箱凭据但未获取到")
            return false
        }
        val (email, apiUrl) = creds
        val deviceId = PrefsStore.getDeviceId()?.trim().orEmpty()
        if (deviceId.isEmpty()) {
            devOp(alias, "$phaseLog：deviceId 为空，无法拉取验证码")
            return false
        }
        val safeTag = fileTag
            ?.filter { it.isLetterOrDigit() || it == '_' }
            ?.take(48)
            ?.ifEmpty { "inline" }
            ?: "inline"

        var vision = identityVerifyOcrOnce(ctx, alias, "identity_verify_${safeTag}_entry") ?: triggerVision

        if (visionHasIdentityMethodSelectionPage(vision)) {
            devOp(alias, "$phaseLog：见 Before you continue，点 Outlook + Next")
            if (!identityVerifyTapOutlookAndNext(ctx, alias, w, h, vision, "identity_${safeTag}")) {
                return false
            }
            if (!identityVerifyWaitMethodPageTransition(ctx, alias, w, h, "identity_${safeTag}")) {
                return false
            }
        }

        vision = identityVerifyOcrOnce(ctx, alias, "identity_verify_${safeTag}_code_page") ?: return false
        if (!visionHasIdentityCodeEntryPage(vision) && !visionHasVerifyIdentityTitle(vision)) {
            devOp(alias, "$phaseLog：未进入 Verify identity 验证码页")
            return false
        }
        return identityVerifyEnterCodeAndComplete(
            ctx = ctx,
            alias = alias,
            w = w,
            h = h,
            deviceId = deviceId,
            apiUrl = apiUrl,
            email = email,
            tagPrefix = "identity_${safeTag}",
            phaseLog = phaseLog,
        )
    }

    /** 发作品/其它操作后主动探测身份验证页（OCR 未在中途触发时的兜底）。 */
    private suspend fun runPostActionIdentityVerificationProbe(
        ctx: Context,
        alias: String,
        tagPrefix: String,
        phaseLog: String,
    ): Boolean {
        val vision = identityVerifyOcrOnce(ctx, alias, "${tagPrefix}_probe") ?: return true
        if (!visionIdentityVerificationBlocking(vision)) return true
        devOp(alias, "$phaseLog：探测到身份验证页，开始处理")
        return runInlineIdentityVerificationFlow(
            ctx = ctx,
            fileTag = tagPrefix,
            phaseLog = phaseLog,
            triggerVision = vision,
        )
    }

    /**
     * 整屏 OCR + 由 [findHit] 得到 bbox 后 tap；最多 [maxPasses] 轮。
     */
    private suspend fun profileEditOcrTapByFinder(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
        tagPrefix: String,
        phaseLog: String,
        strictLabelForLog: String,
        maxPasses: Int,
        findHit: (Text) -> StrictLabelHit?,
    ): Boolean {
        for (pass in 1..maxPasses) {
            if (hardStopOrNull(logAlias, "修改基本资料-$phaseLog")) return false
            if (pass > 1) {
                if (!profileEditDelay(logAlias, 650, phaseLog)) return false
            } else {
                if (!profileEditDelay(logAlias, 450, phaseLog)) return false
            }
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "${tagPrefix}_$pass")
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」$phaseLog 第 $pass/$maxPasses 次 OCR 失败：$errTag")
                if (errTag == "hard_stop") return false
                continue
            }
            val hit = findHit(vision)
            if (hit == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」$phaseLog",
                    pass,
                    maxPasses,
                    vision,
                    480,
                )
                continue
            }
            profileEditLogStrictHit(logAlias, phaseLog, strictLabelForLog, hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            devOp(logAlias, "「修改基本资料」$phaseLog：input tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) return true
            devOp(logAlias, "「修改基本资料」$phaseLog：tap 失败（su），将重试 OCR")
        }
        return false
    }

    /**
     * 进入 Name 编辑页后：OCR 探测「add your preferred name」占位文案（含其中连续词组即通过）。
     * 至多 [PROFILE_EDIT_NAME_EDITABLE_OCR_MAX_TRIES] 次，重试间隔 [PROFILE_EDIT_NAME_EDITABLE_OCR_RETRY_MS]。
     */
    private suspend fun profileEditOcrPreferredNameEditable(
        ctx: Context,
        logAlias: String,
    ): Boolean {
        val phrase = PROFILE_EDIT_PREFERRED_NAME_PHRASE
        val maxTries = PROFILE_EDIT_NAME_EDITABLE_OCR_MAX_TRIES
        for (attempt in 1..maxTries) {
            if (hardStopOrNull(logAlias, "修改基本资料-Name可编辑探测")) return false
            if (attempt > 1) {
                if (!profileEditDelay(logAlias, PROFILE_EDIT_NAME_EDITABLE_OCR_RETRY_MS, "Name可编辑探测重试")) {
                    return false
                }
            }
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_name_editable_$attempt")
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」Name可编辑探测 第 $attempt/$maxTries 次 OCR 失败：$errTag")
                if (errTag == "hard_stop") return false
                continue
            }
            if (ocrHasConsecutiveWordsFromPhrase(vision, phrase)) {
                devOp(
                    logAlias,
                    "「修改基本资料」Name可编辑：第 $attempt/$maxTries 次 OCR 已见「$phrase」连续词片段，继续修改显示名",
                )
                return true
            }
            logOcrReadTextOnMiss(
                logAlias,
                "「修改基本资料」Name可编辑探测",
                attempt,
                maxTries,
                vision,
                420,
            )
        }
        devOp(
            logAlias,
            "「修改基本资料」Name不可编辑：$maxTries 次均未见「$phrase」相关文案，跳过修改显示名",
        )
        return false
    }

    private suspend fun profileEditClearFocusedNameField(logAlias: String) {
        devOp(logAlias, "「修改基本资料」清空当前聚焦输入框（光标在框内）")
        runSuCommand("input keyevent 123")
        delay(80)
        if (runSuCommand("input keycombination 113 29")) {
            delay(40)
            runSuCommand("input keyevent 67")
            delay(80)
            return
        }
        repeat(48) {
            runSuCommand("input keyevent 67")
            delay(12)
        }
        delay(80)
    }

    private sealed class ProfileEditAvatarUploadWaitOutcome {
        data object HardStop : ProfileEditAvatarUploadWaitOutcome()
        /** 等待阶段已 strict 命中「Change photo」，可跳过其后 7 次校验。 */
        data class ChangePhotoReady(val pollAttempts: Int) : ProfileEditAvatarUploadWaitOutcome()
        /** uploading 已消失（或曾出现），进入 Change photo 7 次校验。 */
        data class NeedChangePhotoVerify(val pollAttempts: Int) : ProfileEditAvatarUploadWaitOutcome()
        /** 60s 内未完成等待；已执行强停并重拉 TikTok。 */
        data class TimedOut(val pollAttempts: Int) : ProfileEditAvatarUploadWaitOutcome()
    }

    /** Save(发帖) 后：强停 TikTok 并重新拉起（不含资料比例点）。 */
    private suspend fun profileEditForceStopAndRelaunchTikTok(
        ctx: Context,
        logAlias: String,
        phaseTag: String,
        config: AppConfig,
    ): Boolean {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        devOp(logAlias, "「修改基本资料」$phaseTag：am force-stop $pkg")
        val stopOk = runSuCommand("am force-stop $pkg")
        if (!stopOk) {
            devOp(logAlias, "「修改基本资料」$phaseTag：强停命令未成功，仍继续拉起")
        }
        if (!profileEditDelay(logAlias, 1200L, "$phaseTag·强停后")) return false
        devOp(logAlias, "「修改基本资料」$phaseTag：拉起 TikTok + dumpsys 前台校验")
        val launchOk = launchTikTokWithForegroundVerification(
            ctx = ctx,
            alias = logAlias,
            tag = phaseTag,
            forceStopBeforeLaunch = false,
            timingConfig = config,
        )
        devOp(logAlias, "「修改基本资料」$phaseTag：拉起结果=${if (launchOk) "success" else "failed"}")
        return launchOk
    }

    /**
     * Save(发帖) 之后：每 1s 整屏 OCR。
     * - strict「Change photo」→ 上传已完成；
     * - 文案含 uploading → 上传中，继续轮询；
     * - 曾见 uploading 且本轮未见 → 进入后续 Change photo 校验；
     * - 逾 [PROFILE_EDIT_AVATAR_UPLOAD_WAIT_MAX_MS] → 强停并重拉 TikTok。
     */
    private suspend fun profileEditWaitAvatarUploadSettledOrChangePhoto(
        ctx: Context,
        logAlias: String,
        runtimeConfig: AppConfig,
    ): ProfileEditAvatarUploadWaitOutcome {
        val startedAt = System.currentTimeMillis()
        var pollAttempts = 0
        var sawUploading = false

        while (System.currentTimeMillis() - startedAt < PROFILE_EDIT_AVATAR_UPLOAD_WAIT_MAX_MS) {
            if (hardStopOrNull(logAlias, "发帖后头像上传等待")) {
                return ProfileEditAvatarUploadWaitOutcome.HardStop
            }
            pollAttempts++
            val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
            val (vision, err) = mlKitRecognizeFullScreen(ctx, "profile_edit_avatar_upload_wait_$pollAttempts")
            if (vision == null) {
                devOp(
                    logAlias,
                    "「修改基本资料」发帖后上传等待 OCR $pollAttempts 次失败：$err（已等待约 ${elapsedSec}s）",
                )
                if (err == "hard_stop") return ProfileEditAvatarUploadWaitOutcome.HardStop
            } else {
                val uploadReadyHit = findProfileEditChangePhotoMarkerHit(vision)
                if (uploadReadyHit != null) {
                    devOp(
                        logAlias,
                        "「修改基本资料」发帖后上传等待：已定位「${uploadReadyHit.second}」（第 $pollAttempts 次），跳过后续 7 次校验",
                    )
                    return ProfileEditAvatarUploadWaitOutcome.ChangePhotoReady(pollAttempts)
                }
                val uploadingHit = findLabelHitContainingIgnoreCase(vision, "uploading")
                if (uploadingHit != null) {
                    sawUploading = true
                    devOp(
                        logAlias,
                        "「修改基本资料」发帖后上传等待：检测到 uploading（头像上传中），继续每 1s OCR",
                    )
                } else if (sawUploading) {
                    devOp(
                        logAlias,
                        "「修改基本资料」发帖后上传等待：uploading 已消失（第 $pollAttempts 次），进入 Change photo 校验（至多 7 次）",
                    )
                    return ProfileEditAvatarUploadWaitOutcome.NeedChangePhotoVerify(pollAttempts)
                } else {
                    logOcrReadTextOnMiss(
                        logAlias,
                        "「修改基本资料」发帖后上传等待（约 ${elapsedSec}s）",
                        pollAttempts,
                        (PROFILE_EDIT_AVATAR_UPLOAD_WAIT_MAX_MS / PROFILE_EDIT_AVATAR_UPLOAD_POLL_MS).toInt(),
                        vision,
                        480,
                    )
                }
            }

            val remainingMs = PROFILE_EDIT_AVATAR_UPLOAD_WAIT_MAX_MS - (System.currentTimeMillis() - startedAt)
            if (remainingMs <= 0L) break
            if (!profileEditDelay(
                    logAlias,
                    minOf(PROFILE_EDIT_AVATAR_UPLOAD_POLL_MS, remainingMs),
                    "发帖后头像上传等待",
                )
            ) {
                return ProfileEditAvatarUploadWaitOutcome.HardStop
            }
        }

        devOp(
            logAlias,
            "「修改基本资料」发帖后上传等待：逾时 ${PROFILE_EDIT_AVATAR_UPLOAD_WAIT_MAX_MS / 1000}s（OCR $pollAttempts 次，曾见 uploading=$sawUploading），强停并重拉 TikTok",
        )
        profileEditForceStopAndRelaunchTikTok(
            ctx,
            logAlias,
            "发帖后上传等待超时",
            runtimeConfig,
        )
        return ProfileEditAvatarUploadWaitOutcome.TimedOut(pollAttempts)
    }

    /**
     * 整屏 OCR 至多 [maxTries] 次，直到出现严格「Change photo」/「Edit photo」/「Avatar」。
     * @return first=是否命中，second=已执行 OCR 次数（含失败仍计数）
     */
    private suspend fun profileEditOcrUntilChangePhotoSeen(
        ctx: Context,
        logAlias: String,
        ocrTagPrefix: String,
        humanPhase: String,
        maxTries: Int = 7,
    ): Pair<Boolean, Int> {
        var attempts = 0
        while (attempts < maxTries) {
            if (hardStopOrNull(logAlias, "修改基本资料-$humanPhase")) return false to attempts
            attempts++
            if (attempts > 1) {
                if (!profileEditDelay(logAlias, 800, humanPhase)) return false to attempts
            }
            val (vision, err) = mlKitRecognizeFullScreen(ctx, "${ocrTagPrefix}_$attempts")
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」$humanPhase OCR $attempts/$maxTries 失败：$err")
                if (err == "hard_stop") return false to attempts
                continue
            }
            val markerHit = findProfileEditChangePhotoMarkerHit(vision)
            if (markerHit != null) {
                devOp(logAlias, "「修改基本资料」$humanPhase：已定位「${markerHit.second}」（$attempts/$maxTries）")
                return true to attempts
            }
            logOcrReadTextOnMiss(
                logAlias,
                "「修改基本资料」$humanPhase",
                attempts,
                maxTries,
                vision,
                480,
            )
        }
        return false to attempts
    }

    /** 资料隐私列表：下滑一次（手指向上滑，内容下移）。 */
    private suspend fun profileEditSwipeDownOnce(logAlias: String, w: Int, h: Int) {
        val swipeX = (w * 0.5f).toInt().coerceIn(1, (w - 1).coerceAtLeast(1))
        val startY = (h * 0.72f).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        val endY = (h * 0.35f).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        devOp(logAlias, "「修改基本资料」列表下滑一次：swipe ($swipeX,$startY)→($swipeX,$endY)")
        runSuCommand("input swipe $swipeX $startY $swipeX $endY 420")
        profileEditDelay(logAlias, 450, "列表下滑后")
    }

    /**
     * For You 推荐流翻下一条（手指上滑）：横向落点、滑动距离/时长、终点微漂移均带随机，
     * 避免固定竖直轨迹。返回建议的翻页后停留毫秒数。
     */
    private suspend fun forYouFeedSwipeNextHumanized(
        logAlias: String,
        w: Int,
        h: Int,
        logLabel: String,
    ): Long {
        val baseXFrac = 0.46f + Random.nextFloat() * 0.10f
        val startYFrac = 0.74f + Random.nextFloat() * 0.08f
        val swipeSpanFrac = 0.36f + Random.nextFloat() * 0.14f
        val endYFrac = (startYFrac - swipeSpanFrac).coerceIn(0.24f, 0.40f)
        val xDriftPx = Random.nextInt(-40, 41)
        val startX = (w * baseXFrac).toInt().coerceIn(1, (w - 1).coerceAtLeast(1))
        val endX = (startX + xDriftPx).coerceIn(1, (w - 1).coerceAtLeast(1))
        val startY = (h * startYFrac).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        val endY = (h * endYFrac).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        val quickFlick = Random.nextDouble() < 0.06
        val (y0, y1, swipeMs) = if (quickFlick) {
            val midY = ((startY + endY) / 2).coerceIn(1, (h - 1).coerceAtLeast(1))
            Triple(startY, midY, Random.nextInt(200, 301))
        } else {
            Triple(startY, endY, Random.nextInt(360, 661))
        }
        devOp(logAlias, "$logLabel：swipe ($startX,$y0)→($endX,$y1) ${swipeMs}ms")
        runSuCommand("input swipe $startX $y0 $endX $y1 $swipeMs")
        return Random.nextLong(380, 920)
    }

    /** For You 刷下一条：与 [runForYouWarmupIfNeeded] 同向（手指上滑）。 */
    private suspend fun profileEditForYouFeedSwipeOnce(logAlias: String, w: Int, h: Int): Long =
        forYouFeedSwipeNextHumanized(logAlias, w, h, "「修改基本资料」For You 翻页")

    override suspend fun profileEditForYouTwoSwipesAfterLaunch(logAlias: String, w: Int, h: Int): Boolean {
        if (hardStopOrNull(logAlias, "修改基本资料-For You预刷")) return false
        devOp(logAlias, "「修改基本资料」For You：假定已在推荐页，刷两下视频（无点赞）")
        repeat(2) { pass ->
            if (hardStopOrNull(logAlias, "修改基本资料-For You预刷")) return false
            val settleMs = profileEditForYouFeedSwipeOnce(logAlias, w, h)
            if (!profileEditDelay(logAlias, settleMs, "For You下滑${pass + 1}后")) return false
        }
        return true
    }

    private fun ocrVisionHasSwipeUpPrompt(result: Text): Boolean =
        findLabelHitContainingIgnoreCase(result, "swipe up") != null

    /**
     * 识别到 Swipe up 时按 For You 预划同向上滑，直至文案消失或达上限。
     * @return 最终 vision；处理中硬停或 OCR 失败返回 null；未识别到 Swipe up 时原样返回 [initialVision]。
     */
    private suspend fun dismissSwipeUpPromptUntilGone(
        ctx: Context,
        initialVision: Text,
        fileTag: String,
        phaseLog: String,
    ): Text? {
        if (!ocrVisionHasSwipeUpPrompt(initialVision)) return initialVision
        val alias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() }
        val logAlias = alias ?: "unknown"
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val safeTag = fileTag.replace(Regex("[^a-zA-Z0-9_]+"), "_").take(48)

        for (pass in 1..INLINE_SWIPE_UP_DISMISS_MAX_PASSES) {
            if (isHardStopActive()) return null
            val vision = if (pass == 1) {
                initialVision
            } else {
                val (v, err) = mlKitRecognizeFullScreenCore(ctx, "${safeTag}_swipe_up_$pass")
                if (v == null) {
                    if (err == "hard_stop") return null
                    continue
                }
                v
            }
            if (!ocrVisionHasSwipeUpPrompt(vision)) {
                if (pass > 1) {
                    alias?.let { devOp(it, "$phaseLog：Swipe up 已消失（上滑 ${pass - 1} 次后）") }
                }
                return vision
            }
            alias?.let {
                devOp(it, "$phaseLog：识别到 Swipe up，上滑 ${pass}/$INLINE_SWIPE_UP_DISMISS_MAX_PASSES")
            }
            forYouFeedSwipeNextHumanized(logAlias, w, h, "$phaseLog Swipe up")
            delay(450L)
        }
        val (finalVision, _) = mlKitRecognizeFullScreenCore(ctx, "${safeTag}_swipe_up_final")
        return finalVision
    }

    /**
     * 整屏 OCR + [findHit]；命中仅写日志**不点击**（用于 Comments 等跳转校验）。
     */
    private suspend fun profileEditOcrSeeByFinder(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
        tagPrefix: String,
        phaseLog: String,
        strictLabelForLog: String,
        maxPasses: Int,
        findHit: (Text) -> StrictLabelHit?,
    ): Boolean {
        for (pass in 1..maxPasses) {
            if (hardStopOrNull(logAlias, "修改基本资料-$phaseLog")) return false
            if (pass > 1) {
                if (!profileEditDelay(logAlias, 650, phaseLog)) return false
            } else {
                if (!profileEditDelay(logAlias, 450, phaseLog)) return false
            }
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "${tagPrefix}_$pass")
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」$phaseLog 第 $pass/$maxPasses 次 OCR 失败：$errTag")
                if (errTag == "hard_stop") return false
                continue
            }
            val hit = findHit(vision)
            if (hit == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」$phaseLog",
                    pass,
                    maxPasses,
                    vision,
                    480,
                )
                continue
            }
            profileEditLogStrictHit(logAlias, phaseLog, strictLabelForLog, hit, w, h)
            devOp(logAlias, "「修改基本资料」$phaseLog：已定位「$strictLabelForLog」，仅校验存在（不 input tap）")
            return true
        }
        return false
    }

    private enum class ProfileEditViewersRowToggle {
        ON,
        OFF,
    }

    private fun findProfileEditViewersHit(result: Text): StrictLabelHit? {
        findStrictExactLabelHit(result, "Viewers")?.let { return it }
        findStrictExactAcrossAdjacentLines(result, "Viewers")?.let { return it }
        return null
    }

    /** 与「Viewers」标签同一水平带、位于其右侧的 On/Off 开关文案。 */
    private fun findProfileEditViewersRowToggle(
        vision: Text,
        viewersHit: StrictLabelHit,
    ): ProfileEditViewersRowToggle? {
        val ref = viewersHit.rect
        val bandT = ref.top - 14
        val bandB = ref.bottom + 14
        for (block in vision.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    val cy = b.centerY()
                    if (cy < bandT || cy > bandB) continue
                    if (b.centerX() <= ref.centerX() - 8) continue
                    when (el.text.trim()) {
                        "On" -> return ProfileEditViewersRowToggle.ON
                        "Off" -> return ProfileEditViewersRowToggle.OFF
                    }
                }
            }
        }
        return null
    }

    /**
     * Bio 后② 内层重试：与外轮一致——强停 TikTok → 拉起前台 → Don't allow → 资料入口 (0.97×宽,0.95×高)。
     * 成功后假定已在个人主页，可再点设置比例点 (0.96,0.07)。
     */
    private suspend fun profileEditWarmupRelaunchToProfilePage(
        ctx: Context,
        logAlias: String,
        part2Round: Int,
        config: AppConfig,
    ): Boolean {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        devOp(logAlias, "「修改基本资料」Bio后② 第 $part2Round 轮重试 warmup：am force-stop $pkg")
        val stopOk = runSuCommand("am force-stop $pkg")
        if (!stopOk) {
            devOp(logAlias, "「修改基本资料」Bio后② 重试 warmup：强停命令未成功，仍继续拉起")
        }
        if (!profileEditDelay(logAlias, 1200L, "②重试强停后")) return false

        devOp(logAlias, "「修改基本资料」Bio后② 重试 warmup：拉起 TikTok + dumpsys 前台校验")
        val launchOk = launchTikTokWithForegroundVerification(
            ctx = ctx,
            alias = logAlias,
            tag = "修改基本资料_②重试_r${part2Round}",
            forceStopBeforeLaunch = false,
            timingConfig = config,
        )
        devOp(logAlias, "「修改基本资料」Bio后② 重试 warmup：拉起结果=${if (launchOk) "success" else "failed"}")
        if (!launchOk) {
            devOp(logAlias, "「修改基本资料」Bio后② 重试 warmup：拉起未通过前台校验，中止本轮回试")
            return false
        }
        if (!profileEditDelay(logAlias, 1600L, "②重试拉起后")) return false
        if (hardStopOrNull(logAlias, "修改基本资料-②重试warmup")) return false

        val dm = ctx.resources.displayMetrics
        val rw = dm.widthPixels
        val rh = dm.heightPixels
        val profileEntryX = (rw * 0.97f).toInt().coerceIn(1, (rw - 1).coerceAtLeast(1))
        val profileEntryY = (rh * 0.95f).toInt().coerceIn(1, (rh - 1).coerceAtLeast(1))
        devOp(
            logAlias,
            "「修改基本资料」Bio后② 重试 warmup：资料入口比例点 (0.97×宽,0.95×高)→($profileEntryX,$profileEntryY) 屏${rw}x${rh}",
        )
        val ratioTapOk = runSuCommand("input tap $profileEntryX $profileEntryY")
        if (!ratioTapOk) {
            devOp(logAlias, "「修改基本资料」Bio后② 重试 warmup：资料比例点 tap 失败")
            return false
        }
        if (!profileEditDelay(logAlias, 600L, "②重试资料比例点后")) return false
        devOp(logAlias, "「修改基本资料」Bio后② 重试 warmup 完成，进入 Settings and privacy→【插入】Permissions/Save login info/返回→【旧】Permissions 校验→Privacy 子页链")
        return true
    }

    /** 测试专用：仅执行 Bio 后②（比例点→Privacy→Viewers）。 */
    private suspend fun profileEditRunBioAfterPart2Only(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
    ): ProfileEditChainResult {
        devOp(
            logAlias,
            "「修改基本资料」测试阶段：跳过 Edit→Bio→Bio后①，仅执行 Bio 后②（外轮比例点后须已在 TikTok 个人主页）",
        )
        val maxBioAfterPart2 = 3
        var finalCpAttempts = 0

        fun part2Result(
            success: Boolean,
            failureStep: String,
            bioAfterPart2Ok: Boolean,
        ) = ProfileEditChainResult(
            success = success && bioAfterPart2Ok,
            failureStep = failureStep,
            bioAfterPart1Ok = true,
            bioAfterPart2Ok = bioAfterPart2Ok,
            bioVerifyOcrAttempts = finalCpAttempts,
        )

        var part2Ok = false
        var part2Round = 0
        var lastPart2Fail = "bio_after_part2_unknown"
        while (!part2Ok && part2Round < maxBioAfterPart2) {
            if (hardStopOrNull(logAlias, "修改基本资料-Bio后②")) {
                return part2Result(false, "hard_stop", false)
            }
            part2Round++
            if (part2Round > 1) {
                val warmupConfig = lastConfigSnapshot ?: AppConfig()
                if (!profileEditWarmupRelaunchToProfilePage(ctx, logAlias, part2Round, warmupConfig)) {
                    if (isHardStopActive()) {
                        return part2Result(false, "hard_stop", false)
                    }
                    lastPart2Fail = "part2_retry_warmup_failed"
                    devOp(
                        logAlias,
                        "「修改基本资料」Bio 后② 第 $part2Round 轮：关开 TikTok / 资料比例点 warmup 失败，将重试②",
                    )
                    continue
                }
            }
            val (privacyChainOk, privacyChainFail) = profileEditRunPart2PrivacyViewersChain(
                ctx,
                logAlias,
                w,
                h,
                part2Round,
            )
            if (!privacyChainOk) {
                if (privacyChainFail == "hard_stop") {
                    return part2Result(false, "hard_stop", false)
                }
                lastPart2Fail = privacyChainFail
                devOp(
                    logAlias,
                    "「修改基本资料」Bio 后② 第 $part2Round 轮：Privacy/Viewers 链失败 step=$privacyChainFail，将重试②",
                )
                continue
            }
            part2Ok = true
            devOp(logAlias, "「修改基本资料」Bio 后② 第 $part2Round 轮成功（Privacy→Comments 校验→Viewers On/Off 或 Viewer history）")
        }
        if (!part2Ok) {
            devOp(
                logAlias,
                "「修改基本资料」Bio 后② 已用尽 $maxBioAfterPart2 轮，本链失败（step=$lastPart2Fail）",
            )
            return part2Result(false, lastPart2Fail, false)
        }
        devOp(logAlias, "「修改基本资料」测试② 成功，关闭 TikTok 后台：am force-stop ${Config.TIKTOK_PACKAGE_NAME}")
        runSuCommand("am force-stop ${Config.TIKTOK_PACKAGE_NAME}")
        delay(800)
        return part2Result(true, "", true)
    }

    /**
     * 设置比例点 (0.96×宽,0.07×高) + 固定右下偏移；OCR 确认侧栏已出现「Settings and privacy」（不含 Permissions）。
     */
    private suspend fun profileEditTapSettingsGearOpenPrivacyMenu(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
        part2Round: Int,
    ): Pair<Boolean, String> {
        val baseX = (w * PROFILE_EDIT_SETTINGS_GEAR_RATIO_X).toInt().coerceIn(1, (w - 1).coerceAtLeast(1))
        val baseY = (h * PROFILE_EDIT_SETTINGS_GEAR_RATIO_Y).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        val offsetX = PROFILE_EDIT_SETTINGS_GEAR_OFFSET_X_PX
        val offsetY = PROFILE_EDIT_SETTINGS_GEAR_OFFSET_Y_PX
        val gearTap = coerceTapToScreen(Point(baseX + offsetX, baseY + offsetY), w, h)
        devOp(
            logAlias,
            "「修改基本资料」设置比例点基准 (0.96×宽,0.07×高)→($baseX,$baseY) 屏${w}x${h}；" +
                "固定偏移 右+${offsetX}px 下+${offsetY}px → 实际点击=(${gearTap.x},${gearTap.y})；" +
                "每点 OCR 确认设置菜单已开 至多 ${PROFILE_EDIT_SETTINGS_PRIVACY_OCR_PER_TAP} 次，失败同坐标复点至多 ${PROFILE_EDIT_SETTINGS_GEAR_RETAP_MAX} 次",
        )
        for (tapAttempt in 1..PROFILE_EDIT_SETTINGS_GEAR_RETAP_MAX) {
            if (hardStopOrNull(logAlias, "修改基本资料-设置比例点探针")) return false to "hard_stop"
            devOp(
                logAlias,
                "「修改基本资料」设置比例点 第 $tapAttempt/${PROFILE_EDIT_SETTINGS_GEAR_RETAP_MAX} 次点击：" +
                    "实际坐标=(${gearTap.x},${gearTap.y}) 基准=($baseX,$baseY) 固定偏移=($offsetX,$offsetY)",
            )
            if (!runSuCommand("input tap ${gearTap.x} ${gearTap.y}")) {
                devOp(logAlias, "「修改基本资料」设置比例点 第 $tapAttempt 次：su tap 失败，同坐标复点")
                continue
            }
            if (!profileEditDelay(logAlias, 600L, "设置比例点后")) return false to "hard_stop"

            for (ocrPass in 1..PROFILE_EDIT_SETTINGS_PRIVACY_OCR_PER_TAP) {
                if (hardStopOrNull(logAlias, "修改基本资料-设置菜单探针OCR")) return false to "hard_stop"
                if (ocrPass > 1) {
                    if (!profileEditDelay(logAlias, 450L, "设置菜单探针OCR间隔")) return false to "hard_stop"
                }
                val (vision, errTag) = mlKitRecognizeFullScreen(
                    ctx,
                    "profile_edit_gear_menu_p${part2Round}_t${tapAttempt}_o$ocrPass",
                )
                if (vision == null) {
                    devOp(
                        logAlias,
                        "「修改基本资料」设置菜单探针 OCR $ocrPass/${PROFILE_EDIT_SETTINGS_PRIVACY_OCR_PER_TAP}（第 $tapAttempt 次点击）失败：$errTag",
                    )
                    if (errTag == "hard_stop") return false to "hard_stop"
                    continue
                }
                if (!profileEditSettingsGearSidebarProbeVisible(vision)) {
                    logOcrReadTextOnMiss(
                        logAlias,
                        "「修改基本资料」设置菜单探针（第 $tapAttempt 次点击）",
                        ocrPass,
                        PROFILE_EDIT_SETTINGS_PRIVACY_OCR_PER_TAP,
                        vision,
                        420,
                    )
                    continue
                }
                devOp(
                    logAlias,
                    "「修改基本资料」侧栏已见 Settings and privacy（gearTap=(${gearTap.x},${gearTap.y})），下一步 OCR 点击该行",
                )
                return true to ""
            }
            devOp(
                logAlias,
                "「修改基本资料」设置比例点 第 $tapAttempt 次：${PROFILE_EDIT_SETTINGS_PRIVACY_OCR_PER_TAP} 次 OCR 均未确认菜单，同坐标复点",
            )
        }
        return false to "settings_gear_privacy_probe_failed"
    }

    /** OCR 点击「Settings and privacy」行，进入设置与隐私列表。 */
    private suspend fun profileEditTapSettingsAndPrivacyRow(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
        part2Round: Int,
    ): Pair<Boolean, String> {
        devOp(logAlias, "「修改基本资料」OCR 点击 Settings and privacy 行")
        if (
            !profileEditOcrTapByFinder(
                ctx,
                logAlias,
                w,
                h,
                "profile_edit_settings_and_privacy_r${part2Round}",
                "Settings and privacy",
                "Settings and privacy",
                6,
            ) {
                findProfileEditSettingsAndPrivacyRowHit(it)
            }
        ) {
            if (isHardStopActive()) return false to "hard_stop"
            return false to "settings_and_privacy_tap_miss"
        }
        if (!profileEditDelay(logAlias, 1500L, "点 Settings and privacy 后")) return false to "hard_stop"
        return true to ""
    }

    /**
     * Upload photo / Allow all 之后：整屏 OCR 等待出现「Recents」（仅探测不点击），确认相册界面已稳定。
     */
    private suspend fun profileEditWaitRecentsStable(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
    ): Boolean {
        devOp(
            logAlias,
            "「修改基本资料」等待相册界面稳定：OCR「Recents」（最多 ${PROFILE_EDIT_RECENTS_STABLE_OCR_MAX_PASSES} 次，" +
                "间隔 ${PROFILE_EDIT_RECENTS_STABLE_OCR_DELAY_MS}ms；命中后进入左上选图）",
        )
        for (pass in 1..PROFILE_EDIT_RECENTS_STABLE_OCR_MAX_PASSES) {
            if (hardStopOrNull(logAlias, "修改基本资料-Recents稳定")) return false
            if (pass > 1) {
                if (!profileEditDelay(logAlias, PROFILE_EDIT_RECENTS_STABLE_OCR_DELAY_MS, "Recents稳定")) {
                    return false
                }
            }
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_recents_stable_$pass")
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」Recents 稳定 第 $pass/${PROFILE_EDIT_RECENTS_STABLE_OCR_MAX_PASSES} 次 OCR 失败：$errTag")
                if (errTag == "hard_stop") return false
                continue
            }
            val hit = findStrictExactLabelHit(vision, "Recents")
            if (hit == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Recents 稳定",
                    pass,
                    PROFILE_EDIT_RECENTS_STABLE_OCR_MAX_PASSES,
                    vision,
                    480,
                )
                continue
            }
            profileEditLogStrictHit(logAlias, "Recents", "Recents", hit, w, h)
            devOp(
                logAlias,
                "「修改基本资料」相册界面已稳定（已见 Recents，${pass}/${PROFILE_EDIT_RECENTS_STABLE_OCR_MAX_PASSES}）",
            )
            return true
        }
        devOp(
            logAlias,
            "「修改基本资料」失败：${PROFILE_EDIT_RECENTS_STABLE_OCR_MAX_PASSES} 次 OCR 均未见到 Recents，相册界面未稳定",
        )
        return false
    }

    /**
     * 【插入段，不替换旧链】点 Settings and privacy 之后：OCR 定位并点击 Permissions →
     * OCR 点击 Save login info（定位不到重试，成功后等 2.5s）→ 导航返回。
     */
    private suspend fun profileEditInsertTapPermissionsSaveLoginInfoAndBack(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
        part2Round: Int,
    ): Pair<Boolean, String> {
        val maxPermissionsPasses = 4
        var permissionsHit: StrictLabelHit? = null
        var markerLabel: String? = null
        for (pass in 1..maxPermissionsPasses) {
            if (hardStopOrNull(logAlias, "修改基本资料-插入段Permissions")) return false to "hard_stop"
            if (pass > 1) {
                if (!profileEditDelay(logAlias, 450L, "插入段Permissions OCR")) return false to "hard_stop"
            } else {
                if (!profileEditDelay(logAlias, 400L, "插入段Permissions OCR")) return false to "hard_stop"
            }
            val (vision, errTag) = mlKitRecognizeFullScreen(
                ctx,
                "profile_edit_permissions_insert_p${part2Round}_$pass",
            )
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」插入段 Permissions OCR $pass/$maxPermissionsPasses 失败：$errTag")
                if (errTag == "hard_stop") return false to "hard_stop"
                continue
            }
            val marker = findProfileEditPermissionsScreenMarkerHit(vision)
            if (marker == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」插入段 Permissions",
                    pass,
                    maxPermissionsPasses,
                    vision,
                    420,
                )
                continue
            }
            val (hit, label) = marker
            permissionsHit = hit
            markerLabel = label
            profileEditLogStrictHit(logAlias, "Permissions(插入段点击前)", label, hit, w, h)
            break
        }
        if (permissionsHit == null || markerLabel == null) {
            return false to "permissions_insert_verify_miss"
        }
        val permissionsTap = coerceTapToScreen(permissionsHit.point, w, h)
        devOp(
            logAlias,
            "「修改基本资料」插入段：点击 $markerLabel tap=(${permissionsTap.x},${permissionsTap.y})",
        )
        if (!runSuCommand("input tap ${permissionsTap.x} ${permissionsTap.y}")) {
            return false to "permissions_insert_tap_failed"
        }
        if (!profileEditDelay(logAlias, 550L, "插入段点 Permissions 后")) return false to "hard_stop"
        if (
            !profileEditOcrTapByFinder(
                ctx,
                logAlias,
                w,
                h,
                "profile_edit_save_login_info_p${part2Round}",
                "Save login info(插入段)",
                "Save login info",
                6,
            ) {
                findProfileEditSaveLoginInfoHit(it)
            }
        ) {
            if (isHardStopActive()) return false to "hard_stop"
            return false to "save_login_info_ocr_miss"
        }
        devOp(logAlias, "「修改基本资料」插入段：Save login info 已点击，等待 2.5s")
        if (!profileEditDelay(logAlias, 2500L, "Save login info 点击后")) return false to "hard_stop"
        navBack(logAlias, "插入段 Save login info 后返回 Settings and privacy")
        if (!profileEditDelay(logAlias, 520L, "插入段返回后")) return false to "hard_stop"
        return true to ""
    }

    /**
     * 【旧步骤，插入段返回后原样执行】OCR 仅校验 Permissions / Security and permissions，再点屏内最下方严格「Privacy」。
     */
    private suspend fun profileEditVerifyPermissionsThenTapPrivacyBelow(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
        part2Round: Int,
    ): Pair<Boolean, String> {
        val maxPasses = 4
        for (pass in 1..maxPasses) {
            if (hardStopOrNull(logAlias, "修改基本资料-Permissions校验")) return false to "hard_stop"
            if (pass > 1) {
                if (!profileEditDelay(logAlias, 450L, "Permissions校验OCR")) return false to "hard_stop"
            } else {
                if (!profileEditDelay(logAlias, 400L, "Permissions校验OCR")) return false to "hard_stop"
            }
            val (vision, errTag) = mlKitRecognizeFullScreen(
                ctx,
                "profile_edit_permissions_verify_p${part2Round}_$pass",
            )
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」Permissions 校验 OCR $pass/$maxPasses 失败：$errTag")
                if (errTag == "hard_stop") return false to "hard_stop"
                continue
            }
            val marker = findProfileEditPermissionsScreenMarkerHit(vision)
            if (marker == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Permissions 校验",
                    pass,
                    maxPasses,
                    vision,
                    420,
                )
                continue
            }
            val (permissionsHit, markerLabel) = marker
            profileEditLogStrictHit(logAlias, "Permissions(校验)", markerLabel, permissionsHit, w, h)
            devOp(logAlias, "「修改基本资料」已见 $markerLabel：验证 Settings and privacy 点击成功（仅校验，不点击）")
            val privacyBottom = findBottommostStrictExactLabelHit(vision, "Privacy")
            if (privacyBottom == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Permissions 校验（屏内 Privacy）",
                    pass,
                    maxPasses,
                    vision,
                    420,
                )
                continue
            }
            profileEditLogStrictHit(logAlias, "Privacy(屏内最下)", "Privacy", privacyBottom, w, h)
            val privacyTap = coerceTapToScreen(privacyBottom.point, w, h)
            devOp(
                logAlias,
                "「修改基本资料」点击屏内最下方 Privacy：tap=(${privacyTap.x},${privacyTap.y}) bboxBottom=${privacyBottom.rect.bottom}",
            )
            if (!runSuCommand("input tap ${privacyTap.x} ${privacyTap.y}")) {
                return false to "privacy_below_permissions_tap_failed"
            }
            if (!profileEditDelay(logAlias, 550L, "点 Privacy 子项后")) return false to "hard_stop"
            return true to ""
        }
        return false to "permissions_verify_miss"
    }

    /**
     * Bio 后②：设置点→Settings and privacy→【插入】Permissions+Save login info+返回→【旧】Permissions 校验→Privacy→Comments→下滑→Viewers→…
     */
    private suspend fun profileEditRunPart2PrivacyViewersChain(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
        part2Round: Int,
    ): Pair<Boolean, String> {
        if (hardStopOrNull(logAlias, "修改基本资料-Privacy/Viewers 链")) return false to "hard_stop"
        val (gearPrivacyOk, gearPrivacyFail) = profileEditTapSettingsGearOpenPrivacyMenu(
            ctx,
            logAlias,
            w,
            h,
            part2Round,
        )
        if (!gearPrivacyOk) {
            if (isHardStopActive()) return false to "hard_stop"
            return false to gearPrivacyFail
        }
        val (settingsPrivacyOk, settingsPrivacyFail) = profileEditTapSettingsAndPrivacyRow(
            ctx,
            logAlias,
            w,
            h,
            part2Round,
        )
        if (!settingsPrivacyOk) {
            if (isHardStopActive()) return false to "hard_stop"
            return false to settingsPrivacyFail
        }
        val (insertOk, insertFail) = profileEditInsertTapPermissionsSaveLoginInfoAndBack(
            ctx,
            logAlias,
            w,
            h,
            part2Round,
        )
        if (!insertOk) {
            if (isHardStopActive()) return false to "hard_stop"
            return false to insertFail
        }
        val (privacyEntryOk, privacyEntryFail) = profileEditVerifyPermissionsThenTapPrivacyBelow(
            ctx,
            logAlias,
            w,
            h,
            part2Round,
        )
        if (!privacyEntryOk) {
            if (isHardStopActive()) return false to "hard_stop"
            return false to privacyEntryFail
        }
        if (!profileEditDelay(logAlias, 450, "进入 Privacy 子页后")) return false to "hard_stop"
        if (
            !profileEditOcrSeeByFinder(
                ctx,
                logAlias,
                w,
                h,
                "profile_edit_privacy_comments_r${part2Round}",
                "Comments(仅校验)",
                "Comments",
                6,
            ) {
                findStrictExactLabelHit(it, "Comments")
                    ?: findStrictExactAcrossAdjacentLines(it, "Comments")
            }
        ) {
            if (isHardStopActive()) return false to "hard_stop"
            return false to "privacy_comments_verify_miss"
        }
        if (!profileEditDelay(logAlias, 450, "Comments 校验后")) return false to "hard_stop"
        profileEditSwipeDownOnce(logAlias, w, h)
        var viewersHit: StrictLabelHit? = null
        var viewersVision: Text? = null
        for (scrollPass in 1..2) {
            if (hardStopOrNull(logAlias, "修改基本资料-Viewers OCR")) return false to "hard_stop"
            if (scrollPass == 1) {
                if (!profileEditDelay(logAlias, 400, "下滑后 Viewers")) return false to "hard_stop"
            }
            val (vision, errTag) = mlKitRecognizeFullScreen(
                ctx,
                "profile_edit_viewers_r${part2Round}_s$scrollPass",
            )
            if (vision == null) {
                devOp(
                    logAlias,
                    "「修改基本资料」Viewers OCR（下滑后第 $scrollPass 屏）失败：$errTag",
                )
            } else {
                val hit = findProfileEditViewersHit(vision)
                if (hit != null) {
                    profileEditLogStrictHit(logAlias, "Viewers", "Viewers", hit, w, h)
                    viewersHit = hit
                    viewersVision = vision
                    devOp(logAlias, "「修改基本资料」已定位「Viewers」（第 $scrollPass 屏）")
                    break
                }
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Viewers（下滑后第 $scrollPass 屏）",
                    scrollPass,
                    2,
                    vision,
                    420,
                )
            }
            if (scrollPass == 1) {
                devOp(logAlias, "「修改基本资料」未见 Viewers，再下滑一次后重试 OCR")
                profileEditSwipeDownOnce(logAlias, w, h)
            }
        }
        if (viewersHit == null || viewersVision == null) {
            return false to "viewers_ocr_miss_after_scroll"
        }
        when (findProfileEditViewersRowToggle(viewersVision, viewersHit)) {
            ProfileEditViewersRowToggle.ON -> {
                devOp(logAlias, "「修改基本资料」Viewers 同行已为 On，无需点击 Viewers / Viewer history")
                return true to ""
            }
            ProfileEditViewersRowToggle.OFF -> {
                devOp(logAlias, "「修改基本资料」Viewers 同行为 Off，点击 Viewers 进入设置")
                val vt = coerceTapToScreen(viewersHit.point, w, h)
                if (!runSuCommand("input tap ${vt.x} ${vt.y}")) {
                    return false to "viewers_tap_failed"
                }
                if (!profileEditDelay(logAlias, 550, "点 Viewers 后")) return false to "hard_stop"
                if (
                    !profileEditOcrTapByFinder(
                        ctx,
                        logAlias,
                        w,
                        h,
                        "profile_edit_viewer_history_r${part2Round}",
                        "Viewer history",
                        "Viewer history",
                        7,
                    ) {
                        findStrictExactLabelHit(it, "Viewer history")
                            ?: findStrictExactAcrossAdjacentLines(it, "Viewer history")
                    }
                ) {
                    if (isHardStopActive()) return false to "hard_stop"
                    return false to "viewer_history_ocr_miss"
                }
                devOp(logAlias, "「修改基本资料」已点 Viewer history，等待 3s 后② 完成")
                if (!profileEditDelay(logAlias, 3000L, "Viewer history 后")) return false to "hard_stop"
                return true to ""
            }
            null -> {
                devOp(logAlias, "「修改基本资料」已见 Viewers 但同行未识别 On/Off，本步失败")
                return false to "viewers_row_on_off_not_seen"
            }
        }
    }

    private suspend fun profileEditRefocusBioAndReinput(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
        bioText: String,
        fallbackTapPx: Pair<Int, Int>?,
        tag: String,
        vision: Text? = null,
    ): Boolean {
        val trimmed = bioText.trim()
        if (trimmed.isEmpty()) return false
        val bioHit = vision?.let { findStrictExactLabelHit(it, "Bio") }
        if (bioHit != null) {
            profileEditLogStrictHit(logAlias, "Bio重聚焦($tag)", "Bio", bioHit, w, h)
            val tap = coerceTapToScreen(bioHit.point, w, h)
            devOp(logAlias, "「修改基本资料」Bio重聚焦($tag)：同屏已定位 Bio，input tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                delay(450)
                profileEditClearFocusedNameField(logAlias)
                delay(350)
                return tryInputCommentText(logAlias, trimmed)
            }
        }
        for (attempt in 1..2) {
            if (hardStopOrNull(logAlias, "修改基本资料-Bio重聚焦")) return false
            if (!profileEditDelay(logAlias, if (attempt == 1) 400L else 500L, "Bio重聚焦OCR")) return false
            val (ocrVision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_bio_refocus_${tag}_$attempt")
            if (ocrVision == null) {
                devOp(logAlias, "「修改基本资料」Bio重聚焦($tag) 第 $attempt/2 次 OCR 失败：$errTag")
                if (errTag == "hard_stop") return false
                continue
            }
            val hit = findStrictExactLabelHit(ocrVision, "Bio")
            if (hit == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Bio重聚焦($tag)",
                    attempt,
                    2,
                    ocrVision,
                    420,
                )
                continue
            }
            profileEditLogStrictHit(logAlias, "Bio重聚焦($tag)", "Bio", hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            devOp(logAlias, "「修改基本资料」Bio重聚焦($tag)：已定位 Bio，input tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                delay(450)
                profileEditClearFocusedNameField(logAlias)
                delay(350)
                return tryInputCommentText(logAlias, trimmed)
            }
        }
        if (fallbackTapPx != null) {
            devOp(
                logAlias,
                "「修改基本资料」Bio重聚焦($tag)：2 次均未定位 Bio，回退菜单坐标 (${fallbackTapPx.first},${fallbackTapPx.second})",
            )
            if (runSuCommand("input tap ${fallbackTapPx.first} ${fallbackTapPx.second}")) {
                delay(450)
                profileEditClearFocusedNameField(logAlias)
                delay(350)
                return tryInputCommentText(logAlias, trimmed)
            }
        }
        return false
    }

    private suspend fun profileEditOcrTapSaveTopmost(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
        bioTextForRecovery: String? = null,
        bioFallbackTapPx: Pair<Int, Int>? = null,
    ): Pair<Int, Int>? {
        for (pass in 1..6) {
            if (hardStopOrNull(logAlias, "修改基本资料-Save(最上)")) return null
            if (!profileEditDelay(logAlias, if (pass == 1) 500L else 550L, "Save OCR")) return null
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_name_save_$pass")
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」Save 第 $pass/6 次 OCR 失败：$errTag")
                if (errTag == "hard_stop") return null
                continue
            }
            val hit = findTopmostStrictExactLabelHit(vision, "Save")
            if (hit == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Save",
                    pass,
                    6,
                    vision,
                    420,
                )
                val bioText = bioTextForRecovery?.trim().orEmpty()
                if (bioText.isNotEmpty()) {
                    profileEditRefocusBioAndReinput(
                        ctx,
                        logAlias,
                        w,
                        h,
                        bioText,
                        bioFallbackTapPx,
                        "save_miss_p$pass",
                        vision,
                    )
                    delay(350)
                }
                continue
            }
            profileEditLogStrictHit(logAlias, "Save(最上)", "Save", hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            devOp(logAlias, "「修改基本资料」Save：input tap (${tap.x},${tap.y}) bbox.top=${hit.rect.top}")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) return tap.x to tap.y
            devOp(logAlias, "「修改基本资料」Save：tap 失败（su），将重试 OCR")
        }
        return null
    }

    private suspend fun profileEditOcrTapConfirmPasses(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
    ): Boolean {
        val maxPasses = 3
        for (pass in 1..maxPasses) {
            if (hardStopOrNull(logAlias, "修改基本资料-Confirm")) return false
            if (!profileEditDelay(logAlias, if (pass == 1) 450L else 400L, "Confirm OCR")) return false
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_confirm_$pass")
            if (vision == null) {
                devOp(logAlias, "「修改基本资料」Confirm OCR 第 $pass/$maxPasses 次失败：$errTag")
                if (errTag == "hard_stop") return false
                continue
            }
            val syncRow = findProfileEditSyncOrUsernameRowHit(vision)
            if (syncRow != null) {
                profileEditLogStrictHit(
                    logAlias,
                    "Username/Sync 行(确认前)",
                    syncRow.let { sr ->
                        val raw = vision.textBlocks.flatMap { b -> b.lines.flatMap { l -> l.elements } }
                            .firstOrNull { el ->
                                val bb = el.boundingBox
                                bb != null &&
                                    kotlin.math.abs(bb.centerY() - sr.rect.centerY()) < 20 &&
                                    kotlin.math.abs(bb.centerX() - sr.rect.centerX()) < 40
                            }?.text?.trim()
                        raw?.take(24) ?: "Sync|Username"
                    },
                    syncRow,
                    w,
                    h,
                )
                val checkPt = findRowAlignedCheckmarkTapForProfileEdit(vision, syncRow, w)
                if (checkPt != null) {
                    val ck = coerceTapToScreen(checkPt, w, h)
                    devOp(
                        logAlias,
                        "「修改基本资料」Username 已同步修改：将对勾估点 tap=(${ck.x},${ck.y})（与 Sync/Username 同行；未识别符号时可能为屏右几何回退）",
                    )
                    if (runSuCommand("input tap ${ck.x} ${ck.y}")) {
                        delay(420)
                    } else {
                        devOp(logAlias, "「修改基本资料」Username 同步行对勾 tap 失败（su），仍继续尝试点 Confirm")
                    }
                }
            }
            val hit = findStrictExactLabelHit(vision, "Confirm")
            if (hit == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Confirm",
                    pass,
                    maxPasses,
                    vision,
                    480,
                )
                continue
            }
            profileEditLogStrictHit(logAlias, "Confirm", "Confirm", hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            devOp(logAlias, "「修改基本资料」Confirm：input tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                devOp(logAlias, "「修改基本资料」Confirm：第 $pass/$maxPasses 次 tap 完成（本步成功）")
                return true
            }
            devOp(logAlias, "「修改基本资料」Confirm 第 $pass/$maxPasses 次 tap 失败（su）")
        }
        return false
    }

    /**
     * 整屏 OCR 查找权限拒绝按钮并点击；未命中不点击、不判失败。
     * [useSideEffectFreeOcr] 为 true 时走 [mlKitRecognizeFullScreenCore]，不与通用 OCR 顺手点 Don't allow 混用。
     */
    private suspend fun tryDismissDontAllowOcr(
        ctx: Context,
        alias: String,
        phaseLog: String,
        logSuffix: String,
        maxPasses: Int = TIKTOK_LAUNCH_DONT_ALLOW_OCR_MAX_PASSES,
        retryDelayMs: Long = TIKTOK_LAUNCH_DONT_ALLOW_OCR_RETRY_DELAY_MS,
        logPrefix: String = "",
        compactLog: Boolean = false,
        useSideEffectFreeOcr: Boolean = false,
        findDismissHit: (Text) -> StrictLabelHit? = { findDontAllowLabelHit(it) },
    ): Boolean {
        val logAlias = alias.trim().takeIf { it.isNotEmpty() } ?: "unknown"
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val passes = maxPasses.coerceIn(1, 5)
        val safeSuffix = logSuffix.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "run" }
        val prefix = logPrefix.trim()
        fun line(msg: String) {
            devOp(logAlias, if (prefix.isEmpty()) msg else "$prefix$msg")
        }
        val retryDelay = retryDelayMs.coerceIn(100L, 5000L)
        val retrySecLabel = if (retryDelay % 1000L == 0L) {
            "${retryDelay / 1000}s"
        } else {
            "${retryDelay}ms"
        }
        if (!compactLog) {
            line(
                "$phaseLog：可选整屏 OCR（至多 $passes 次" +
                    if (passes > 1) "，相邻间隔 $retrySecLabel" else "" +
                    "；未命中则跳过）[$logSuffix]",
            )
        }
        var ocrFailCount = 0
        var missCount = 0
        for (pass in 1..passes) {
            if (hardStopOrNull(logAlias, phaseLog)) return false
            if (pass > 1) delay(retryDelay)
            val cacheTag = "dont_allow_${safeSuffix}_$pass"
            val (vision, errTag) = if (useSideEffectFreeOcr) {
                mlKitRecognizeFullScreenCore(ctx, cacheTag)
            } else {
                mlKitRecognizeFullScreen(ctx, cacheTag)
            }
            if (vision == null) {
                ocrFailCount++
                if (!compactLog) {
                    line("$phaseLog 第 $pass/$passes 次 OCR 失败：$errTag [$logSuffix]")
                }
                continue
            }
            val hit = findDismissHit(vision)
            if (hit == null) {
                missCount++
                if (!compactLog) {
                    logOcrReadTextOnMiss(
                        logAlias,
                        "$phaseLog [$logSuffix]",
                        pass,
                        passes,
                        vision,
                        480,
                    )
                }
                continue
            }
            if (prefix.isNotEmpty()) {
                profileEditLogStrictHit(logAlias, phaseLog, "Don't allow", hit, w, h)
            } else if (!compactLog) {
                val tapPreview = coerceTapToScreen(hit.point, w, h)
                devOp(logAlias, "$phaseLog：命中 Don't allow (${tapPreview.x},${tapPreview.y}) [$logSuffix]")
            }
            val tap = coerceTapToScreen(hit.point, w, h)
            if (!compactLog) {
                line("$phaseLog：input tap (${tap.x},${tap.y}) [$logSuffix]")
            }
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                delay(450)
                if (compactLog) {
                    line(
                        "$phaseLog：OCR ${passes} 轮（未命中 $missCount，OCR 失败 $ocrFailCount），" +
                            "第 $pass 轮命中并点击 (${tap.x},${tap.y}) [$logSuffix]",
                    )
                } else {
                    line("$phaseLog：已点击 [$logSuffix]")
                }
                return true
            }
            if (!compactLog) {
                line("$phaseLog：tap 失败（su）${if (passes > 1) "，间隔后将重试 OCR" else ""} [$logSuffix]")
            }
        }
        if (compactLog) {
            line(
                "$phaseLog：OCR ${passes} 轮均未完成点击（未命中 $missCount，OCR 失败 $ocrFailCount），跳过 [$logSuffix]",
            )
        } else {
            line("$phaseLog：$passes 次均未完成点击，跳过（不打断流程）[$logSuffix]")
        }
        return false
    }

    override suspend fun profileEditRunOcrChain(ctx: Context, runtimeConfig: AppConfig): ProfileEditChainResult {
        lastConfigSnapshot = runtimeConfig
        val logAlias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() } ?: "profile_edit"
        if (hardStopOrNull(logAlias, "修改基本资料 OCR 链入口")) {
            return profileEditHardStopChainResult()
        }
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        if (PROFILE_EDIT_TEST_PART2_ONLY) {
            return profileEditRunBioAfterPart2Only(ctx, logAlias, w, h)
        }

        devOp(
            logAlias,
            "「修改基本资料」OCR 链开始：屏=${w}x${h}px；步骤=OCR Edit 坐标→点击 Edit→" +
                "OCR 分别记录「Change photo」「Name」「Bio」三词坐标（不点）→" +
                "同屏严格「Change photo」首击（若无则专段整屏 OCR）→" +
                "整屏「Upload photo」或「Select from Gallery」（OCR 最多 3 次）→「Allow all」（最多 ${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次；未见 Allow 但已见 Recents 则跳过）→" +
                "OCR「Recents」稳定（${PROFILE_EDIT_RECENTS_STABLE_OCR_MAX_PASSES}×${PROFILE_EDIT_RECENTS_STABLE_OCR_DELAY_MS}ms）→" +
                "左上固定点→Next×2（间隔 0.5s）→Post this photo to Story(≤${PROFILE_EDIT_POST_STORY_OCR_MAX_PASSES}，未命中跳过)→Save→" +
                "uploading 轮询(1s,≤60s,超时强停重拉)→OCR「Change photo」校验至多 7 次→Name→" +
                "Bio 前 OCR 点 TikTok 文案(≤3)→Bio(模板+剪贴板比对)→" +
                "①Save→终验→返回(至多3轮)→②比例点→【插入】Permissions+Save login info(2.5s)+返回→【旧】Permissions校验+Privacy→Comments(6)→下滑→Viewers→On/Off→Viewer history(7)→等3s",
        )

        val editPreviews = mutableListOf<String>()
        var lastEditVision: Text? = null
        var editPoint: Point? = null
        var editAttempts = 0
        while (editAttempts < 3 && editPoint == null) {
            if (hardStopOrNull(logAlias, "修改基本资料-Edit OCR")) {
                return profileEditHardStopChainResult(
                    editOcrAttempts = editAttempts,
                    editOcrPreviews = editPreviews.toList(),
                )
            }
            editAttempts++
            if (editAttempts > 1) {
                if (!profileEditDelay(logAlias, 700, "Edit OCR")) {
                    return profileEditHardStopChainResult(
                        editOcrAttempts = editAttempts,
                        editOcrPreviews = editPreviews.toList(),
                    )
                }
            } else {
                if (!profileEditDelay(logAlias, 400, "Edit OCR")) {
                    return profileEditHardStopChainResult(
                        editOcrAttempts = editAttempts,
                        editOcrPreviews = editPreviews.toList(),
                    )
                }
            }
            devOp(logAlias, "「修改基本资料」Edit OCR 第 $editAttempts/3 次：截屏识别中…")
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_edit_$editAttempts")
            if (vision == null) {
                editPreviews.add("[$errTag]")
                devOp(logAlias, "「修改基本资料」Edit OCR 第 $editAttempts/3 次失败：$errTag")
                continue
            }
            lastEditVision = vision
            val preview = buildProfileEditOcrPreview(vision)
            editPreviews.add(preview)
            editPoint = findStrictEditTapPoint(vision)
            if (editPoint == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Edit",
                    editAttempts,
                    3,
                    preview,
                    500,
                )
            }
        }
        if (editPoint == null) {
            devOp(logAlias, "「修改基本资料」失败：Edit 经 3 次 OCR 仍未定位")
            return ProfileEditChainResult(
                success = false,
                failureStep = "edit_ocr_3x_miss",
                editOcrAttempts = editAttempts,
                changeOcrAttempts = 0,
                editOcrPreviews = editPreviews.toList(),
            )
        }
        val editVision = lastEditVision
        if (editVision == null) {
            devOp(logAlias, "「修改基本资料」失败：Edit 已定位但内部未缓存最后一次 OCR 结果")
            return ProfileEditChainResult(
                success = false,
                failureStep = "edit_internal_no_vision",
                editOcrAttempts = editAttempts,
                editOcrPreviews = editPreviews.toList(),
                editTapPx = coerceTapToScreen(editPoint, w, h).let { it.x to it.y },
            )
        }
        findStrictExactLabelHit(editVision, "Edit")?.let {
            profileEditLogStrictHit(logAlias, "Edit", "Edit", it, w, h)
        } ?: devOp(logAlias, "「修改基本资料」Edit：最后一次 OCR 中未解析到「Edit」的 bbox，仍使用推断中心点击")

        val editTap = coerceTapToScreen(editPoint, w, h)
        devOp(logAlias, "「修改基本资料」Edit：执行 input tap (${editTap.x},${editTap.y})（屏内钳位）")
        val editTapOk = runSuCommand("input tap ${editTap.x} ${editTap.y}")
        if (!editTapOk) {
            devOp(logAlias, "「修改基本资料」失败：Edit 的 input tap 未成功（su）")
            return ProfileEditChainResult(
                success = false,
                failureStep = "edit_tap_su_failed",
                editOcrAttempts = editAttempts,
                editOcrPreviews = editPreviews.toList(),
                editTapPx = editTap.x to editTap.y,
                editTapOk = false,
            )
        }
        delay(900)

        val changePreviews = mutableListOf<String>()
        var menuLineRecorded: Pair<Int, Int>? = null
        var menuChangePhotoPxOut: Pair<Int, Int>? = null
        var menuNamePxOut: Pair<Int, Int>? = null
        var menuBioPxOut: Pair<Int, Int>? = null
        /** 命中三项菜单时的那一次 OCR 结果，用于紧接着在同屏内找「Change photo」并首击。 */
        var visionWhenMenuLineHit: Text? = null
        var changeAttempts = 0
        while (changeAttempts < 3 && menuLineRecorded == null) {
            if (hardStopOrNull(logAlias, "修改基本资料-三项菜单 OCR")) {
                return profileEditHardStopChainResult(
                    editOcrAttempts = editAttempts,
                    editOcrPreviews = editPreviews.toList(),
                    changeOcrAttempts = changeAttempts,
                    changeOcrPreviews = changePreviews.toList(),
                )
            }
            changeAttempts++
            if (changeAttempts > 1) {
                if (!profileEditDelay(logAlias, 700, "三项菜单 OCR")) {
                    return profileEditHardStopChainResult(
                        editOcrAttempts = editAttempts,
                        editOcrPreviews = editPreviews.toList(),
                        changeOcrAttempts = changeAttempts,
                        changeOcrPreviews = changePreviews.toList(),
                    )
                }
            } else {
                if (!profileEditDelay(logAlias, 500, "三项菜单 OCR")) {
                    return profileEditHardStopChainResult(
                        editOcrAttempts = editAttempts,
                        editOcrPreviews = editPreviews.toList(),
                        changeOcrAttempts = changeAttempts,
                        changeOcrPreviews = changePreviews.toList(),
                    )
                }
            }
            devOp(
                logAlias,
                "「修改基本资料」三项菜单 OCR 第 $changeAttempts/3 次：分别 strict 匹配「Change photo」「Name」或「name」「Bio」（仅记录坐标，不点击三词）",
            )
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_change_menu_$changeAttempts")
            if (vision == null) {
                changePreviews.add("menu[$errTag]")
                devOp(logAlias, "「修改基本资料」三项菜单 OCR 第 $changeAttempts/3 次失败：$errTag")
                continue
            }
            val preview = buildProfileEditOcrPreview(vision)
            changePreviews.add("menu:$preview")
            val triple = findProfileEditChangePhotoNameBioTriple(vision)
            if (triple == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」三项菜单",
                    changeAttempts,
                    3,
                    preview,
                    500,
                )
            } else {
                profileEditLogStrictHit(logAlias, "菜单项(仅记录)", "Change photo", triple.changePhoto, w, h)
                profileEditLogStrictHit(logAlias, "菜单项(仅记录)", "Name", triple.name, w, h)
                profileEditLogStrictHit(logAlias, "菜单项(仅记录)", "Bio", triple.bio, w, h)
                visionWhenMenuLineHit = vision
                val cpPx = coerceTapToScreen(triple.changePhoto.point, w, h).let { it.x to it.y }
                val nmPx = coerceTapToScreen(triple.name.point, w, h).let { it.x to it.y }
                val bioPx = coerceTapToScreen(triple.bio.point, w, h).let { it.x to it.y }
                menuChangePhotoPxOut = cpPx
                menuNamePxOut = nmPx
                menuBioPxOut = bioPx
                val u = unionThreeRects(triple.changePhoto.rect, triple.name.rect, triple.bio.rect)
                val uc = coerceTapToScreen(Point(u.centerX(), u.centerY()), w, h)
                menuLineRecorded = uc.x to uc.y
                devOp(
                    logAlias,
                    "「修改基本资料」三项菜单：已记录钳位后中心 Change photo=(${cpPx.first},${cpPx.second}) Name=(${nmPx.first},${nmPx.second}) Bio=(${bioPx.first},${bioPx.second})；三词 bbox 并集参考中心 (${uc.x},${uc.y})，本步不 input tap 三词",
                )
            }
        }
        if (menuLineRecorded == null) {
            devOp(logAlias, "「修改基本资料」失败：同一屏 strict「Change photo」「Name」「Bio」三词经 3 次 OCR 仍未齐")
            return ProfileEditChainResult(
                success = false,
                failureStep = "change_menu_ocr_3x_miss",
                editOcrAttempts = editAttempts,
                changeOcrAttempts = changeAttempts,
                editOcrPreviews = editPreviews.toList(),
                changeOcrPreviews = changePreviews.toList(),
                editTapPx = editTap.x to editTap.y,
                editTapOk = true,
            )
        }

        var changePhotoTapPx: Pair<Int, Int>? = null
        var changePhotoTapOk = false
        var changePhotoStrictAttempts = 0
        val menuVision = visionWhenMenuLineHit
        if (menuVision != null) {
            delay(400)
            devOp(
                logAlias,
                "「修改基本资料」已齐三项菜单坐标；按流程在同屏内对「Change photo」执行首击（随后整屏 OCR「Upload photo」/「Select from Gallery」）",
            )
            val samePassChangePair = findProfileEditChangePhotoMarkerHit(menuVision)
            if (samePassChangePair == null) {
                devOp(
                    logAlias,
                    "「修改基本资料」同屏异常：三词已齐但单独未解析出 Change photo/Edit photo/Avatar element/line，将启动专段整屏 OCR",
                )
            } else {
                val (samePassChange, samePassLabel) = samePassChangePair
                profileEditLogStrictHit(
                    logAlias,
                    "Change photo(菜单同屏)",
                    samePassLabel,
                    samePassChange,
                    w,
                    h,
                )
                val tapMenuPass = coerceTapToScreen(samePassChange.point, w, h)
                changePhotoTapPx = tapMenuPass.x to tapMenuPass.y
                devOp(
                    logAlias,
                    "「修改基本资料」Change photo(菜单同屏)：执行 input tap (${tapMenuPass.x},${tapMenuPass.y})",
                )
                changePhotoTapOk = runSuCommand("input tap ${tapMenuPass.x} ${tapMenuPass.y}")
                changePhotoStrictAttempts = 1
                if (!changePhotoTapOk) {
                    devOp(logAlias, "「修改基本资料」失败：Change photo(菜单同屏) 的 input tap 未成功（su），将回退到专段整屏 OCR 重试「Change photo」")
                    changePhotoTapOk = false
                    changePhotoTapPx = null
                    changePhotoStrictAttempts = 0
                } else {
                    devOp(logAlias, "「修改基本资料」Change photo(菜单同屏) tap 已成功，跳过专段「Change photo」整屏 OCR")
                }
            }
        }

        while (changePhotoStrictAttempts < 3 && !changePhotoTapOk) {
            changePhotoStrictAttempts++
            delay(500)
            devOp(logAlias, "「修改基本资料」严格「Change photo」专段整屏 OCR 第 $changePhotoStrictAttempts/3 次…")
            val (vision, errTag) =
                mlKitRecognizeFullScreen(ctx, "profile_edit_change_photo_strict_$changePhotoStrictAttempts")
            if (vision == null) {
                changePreviews.add("change_photo_strict[$errTag]")
                devOp(logAlias, "「修改基本资料」严格「Change photo」第 $changePhotoStrictAttempts/3 次失败：$errTag")
                continue
            }
            val pv = buildProfileEditOcrPreview(vision)
            changePreviews.add("change_photo_strict:$pv")
            val hitPair = findProfileEditChangePhotoMarkerHit(vision)
            if (hitPair == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Change photo/Edit photo/Avatar",
                    changePhotoStrictAttempts,
                    3,
                    pv,
                    500,
                )
                continue
            }
            val (hit, hitLabel) = hitPair
            profileEditLogStrictHit(logAlias, "Change photo(专段整屏)", hitLabel, hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            changePhotoTapPx = tap.x to tap.y
            devOp(logAlias, "「修改基本资料」Change photo(专段整屏)：执行 input tap (${tap.x},${tap.y})")
            changePhotoTapOk = runSuCommand("input tap ${tap.x} ${tap.y}")
            if (!changePhotoTapOk) {
                devOp(logAlias, "「修改基本资料」失败：Change photo(专段整屏) 的 input tap 未成功（su）")
                return ProfileEditChainResult(
                    success = false,
                    failureStep = "change_photo_tap_su_failed",
                    editOcrAttempts = editAttempts,
                    changeOcrAttempts = changeAttempts,
                    editOcrPreviews = editPreviews.toList(),
                    changeOcrPreviews = changePreviews.toList(),
                    editTapPx = editTap.x to editTap.y,
                    changeMenuLinePx = menuLineRecorded,
                    changeMenuChangePhotoPx = menuChangePhotoPxOut,
                    changeMenuNamePx = menuNamePxOut,
                    changeMenuBioPx = menuBioPxOut,
                    editTapOk = true,
                    changePhotoStrictOcrAttempts = changePhotoStrictAttempts,
                    changePhotoStrictTapPx = changePhotoTapPx,
                    changePhotoStrictTapOk = false,
                    changeTapPx = changePhotoTapPx,
                    changeTapOk = false,
                )
            }
        }
        if (!changePhotoTapOk) {
            devOp(
                logAlias,
                "「修改基本资料」失败：「Change photo」未完成有效点击（已含菜单同屏首击若适用 + 专段整屏 OCR 至多 3 次）",
            )
            return ProfileEditChainResult(
                success = false,
                failureStep = "change_photo_strict_ocr_3x_miss",
                editOcrAttempts = editAttempts,
                changeOcrAttempts = changeAttempts,
                editOcrPreviews = editPreviews.toList(),
                changeOcrPreviews = changePreviews.toList(),
                editTapPx = editTap.x to editTap.y,
                changeMenuLinePx = menuLineRecorded,
                changeMenuChangePhotoPx = menuChangePhotoPxOut,
                changeMenuNamePx = menuNamePxOut,
                changeMenuBioPx = menuBioPxOut,
                editTapOk = true,
                changePhotoStrictOcrAttempts = changePhotoStrictAttempts,
                changePhotoStrictTapPx = changePhotoTapPx,
                changePhotoStrictTapOk = false,
            )
        }
        delay(600)
        devOp(
            logAlias,
            "「修改基本资料」「Change photo」tap 已完成；短延迟后重新整屏 OCR，严格匹配「Upload photo」/「Select from Gallery」（命中坐标将写入日志）",
        )

        val uploadPreviews = mutableListOf<String>()
        var uploadTapPx: Pair<Int, Int>? = null
        var uploadTapOk = false
        var uploadAttempts = 0
        while (uploadAttempts < 3 && !uploadTapOk) {
            uploadAttempts++
            delay(600)
            devOp(logAlias, "「修改基本资料」严格「Upload photo」/「Select from Gallery」OCR 第 $uploadAttempts/3 次…")
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_upload_photo_$uploadAttempts")
            if (vision == null) {
                uploadPreviews.add("[$errTag]")
                devOp(logAlias, "「修改基本资料」Upload photo/Select from Gallery 第 $uploadAttempts/3 次失败：$errTag")
                continue
            }
            val upPrev = buildProfileEditOcrPreview(vision)
            uploadPreviews.add(upPrev)
            val hitPair = findProfileEditUploadPhotoHit(vision)
            if (hitPair == null) {
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Upload photo/Select from Gallery",
                    uploadAttempts,
                    3,
                    upPrev,
                    500,
                )
                continue
            }
            val (hit, matchedLabel) = hitPair
            profileEditLogStrictHit(logAlias, "Upload photo/Select from Gallery", matchedLabel, hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            uploadTapPx = tap.x to tap.y
            devOp(logAlias, "「修改基本资料」$matchedLabel：执行 input tap (${tap.x},${tap.y})")
            uploadTapOk = runSuCommand("input tap ${tap.x} ${tap.y}")
            if (!uploadTapOk) {
                devOp(logAlias, "「修改基本资料」失败：Upload photo/Select from Gallery 的 input tap 未成功（su）")
                return ProfileEditChainResult(
                    success = false,
                    failureStep = "upload_photo_tap_su_failed",
                    editOcrAttempts = editAttempts,
                    changeOcrAttempts = changeAttempts,
                    editOcrPreviews = editPreviews.toList(),
                    changeOcrPreviews = changePreviews.toList(),
                    editTapPx = editTap.x to editTap.y,
                    changeMenuLinePx = menuLineRecorded,
                    changeMenuChangePhotoPx = menuChangePhotoPxOut,
                    changeMenuNamePx = menuNamePxOut,
                    changeMenuBioPx = menuBioPxOut,
                    editTapOk = true,
                    changePhotoStrictOcrAttempts = changePhotoStrictAttempts,
                    changePhotoStrictTapPx = changePhotoTapPx,
                    changePhotoStrictTapOk = true,
                    changeTapPx = changePhotoTapPx,
                    changeTapOk = true,
                    uploadPhotoOcrAttempts = uploadAttempts,
                    uploadPhotoOcrPreviews = uploadPreviews.toList(),
                    uploadPhotoTapPx = uploadTapPx,
                    uploadPhotoTapOk = false,
                )
            }
        }
        if (!uploadTapOk) {
            devOp(logAlias, "「修改基本资料」失败：严格「Upload photo」/「Select from Gallery」经 3 次 OCR 仍未完成有效点击")
            return ProfileEditChainResult(
                success = false,
                failureStep = "upload_photo_ocr_3x_miss",
                editOcrAttempts = editAttempts,
                changeOcrAttempts = changeAttempts,
                editOcrPreviews = editPreviews.toList(),
                changeOcrPreviews = changePreviews.toList(),
                editTapPx = editTap.x to editTap.y,
                changeMenuLinePx = menuLineRecorded,
                changeMenuChangePhotoPx = menuChangePhotoPxOut,
                changeMenuNamePx = menuNamePxOut,
                changeMenuBioPx = menuBioPxOut,
                editTapOk = true,
                changePhotoStrictOcrAttempts = changePhotoStrictAttempts,
                changePhotoStrictTapPx = changePhotoTapPx,
                changePhotoStrictTapOk = true,
                changeTapPx = changePhotoTapPx,
                changeTapOk = true,
                uploadPhotoOcrAttempts = uploadAttempts,
                uploadPhotoOcrPreviews = uploadPreviews.toList(),
                uploadPhotoTapPx = uploadTapPx,
                uploadPhotoTapOk = false,
            )
        }
        delay(600)
        devOp(
            logAlias,
            "「修改基本资料」选图入口 tap 已完成；短延迟后 OCR「Allow all」（最多 ${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次；" +
                "未见 Allow all 但已见 Recents 则跳过 Allow；之后 OCR「Recents」确认界面稳定）",
        )

        val allowPreviews = mutableListOf<String>()
        var allowTapPx: Pair<Int, Int>? = null
        var allowTapOk = false
        var allowSkippedBecauseRecentsVisible = false
        var allowAttempts = 0
        while (
            allowAttempts < PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES &&
            !allowTapOk &&
            !allowSkippedBecauseRecentsVisible
        ) {
            allowAttempts++
            delay(600)
            devOp(
                logAlias,
                "「修改基本资料」严格「Allow all」OCR 第 $allowAttempts/${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次…",
            )
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_allow_all_$allowAttempts")
            if (vision == null) {
                allowPreviews.add("[$errTag]")
                devOp(
                    logAlias,
                    "「修改基本资料」Allow all 第 $allowAttempts/${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次失败：$errTag",
                )
                continue
            }
            val ap = buildProfileEditOcrPreview(vision)
            allowPreviews.add(ap)
            val hit = findStrictExactLabelHit(vision, "Allow all")
            if (hit == null) {
                val recentsHit = findStrictExactLabelHit(vision, "Recents")
                if (recentsHit != null) {
                    profileEditLogStrictHit(logAlias, "Recents", "Recents", recentsHit, w, h)
                    devOp(
                        logAlias,
                        "「修改基本资料」Allow all 第 $allowAttempts/${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次：" +
                            "未见 Allow all，但已见 Recents（可直接选图），跳过 Allow all",
                    )
                    allowSkippedBecauseRecentsVisible = true
                    break
                }
                logOcrReadTextOnMiss(
                    logAlias,
                    "「修改基本资料」Allow all",
                    allowAttempts,
                    PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES,
                    ap,
                    500,
                )
                continue
            }
            profileEditLogStrictHit(logAlias, "Allow all", "Allow all", hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            allowTapPx = tap.x to tap.y
            devOp(logAlias, "「修改基本资料」Allow all：执行 input tap (${tap.x},${tap.y})")
            allowTapOk = runSuCommand("input tap ${tap.x} ${tap.y}")
            if (!allowTapOk) {
                devOp(logAlias, "「修改基本资料」失败：Allow all 的 input tap 未成功（su）")
                return ProfileEditChainResult(
                    success = false,
                    failureStep = "allow_all_tap_su_failed",
                    editOcrAttempts = editAttempts,
                    changeOcrAttempts = changeAttempts,
                    editOcrPreviews = editPreviews.toList(),
                    changeOcrPreviews = changePreviews.toList(),
                    editTapPx = editTap.x to editTap.y,
                    changeMenuLinePx = menuLineRecorded,
                    changeMenuChangePhotoPx = menuChangePhotoPxOut,
                    changeMenuNamePx = menuNamePxOut,
                    changeMenuBioPx = menuBioPxOut,
                    editTapOk = true,
                    changePhotoStrictOcrAttempts = changePhotoStrictAttempts,
                    changePhotoStrictTapPx = changePhotoTapPx,
                    changePhotoStrictTapOk = true,
                    changeTapPx = changePhotoTapPx,
                    changeTapOk = true,
                    uploadPhotoOcrAttempts = uploadAttempts,
                    uploadPhotoOcrPreviews = uploadPreviews.toList(),
                    uploadPhotoTapPx = uploadTapPx,
                    uploadPhotoTapOk = true,
                    allowAllOcrAttempts = allowAttempts,
                    allowAllOcrPreviews = allowPreviews.toList(),
                    allowAllTapPx = allowTapPx,
                    allowAllTapOk = false,
                )
            }
        }
        if (!allowTapOk && !allowSkippedBecauseRecentsVisible) {
            devOp(
                logAlias,
                "「修改基本资料」Allow all：${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次 OCR 均未定位 Allow all，且未见 Recents；" +
                    "跳过 Allow all 点击，继续等待 Recents 稳定",
            )
        }

        var profileEditAssignedUsername = ""
        var profileEditUsernameSyncedTo = ""

        fun chainBase(
            success: Boolean,
            failureStep: String,
            avatarTryCount: Int = 0,
            bioVerifyTryCount: Int = 0,
            appliedName: String = "",
            postConfirmChangePhotoTries: Int = 0,
            profileEditBioApplied: String = "",
            bioAfterPart1Ok: Boolean = false,
            bioAfterPart2Ok: Boolean = false,
        ) = ProfileEditChainResult(
            success = success && bioAfterPart1Ok && bioAfterPart2Ok,
            failureStep = failureStep,
            editOcrAttempts = editAttempts,
            changeOcrAttempts = changeAttempts,
            editOcrPreviews = editPreviews.toList(),
            changeOcrPreviews = changePreviews.toList(),
            editTapPx = editTap.x to editTap.y,
            changeMenuLinePx = menuLineRecorded,
            changeMenuChangePhotoPx = menuChangePhotoPxOut,
            changeMenuNamePx = menuNamePxOut,
            changeMenuBioPx = menuBioPxOut,
            changeTapPx = changePhotoTapPx,
            editTapOk = true,
            changeTapOk = true,
            changePhotoStrictOcrAttempts = changePhotoStrictAttempts,
            changePhotoStrictTapPx = changePhotoTapPx,
            changePhotoStrictTapOk = changePhotoTapOk,
            uploadPhotoOcrAttempts = uploadAttempts,
            uploadPhotoOcrPreviews = uploadPreviews.toList(),
            uploadPhotoTapPx = uploadTapPx,
            uploadPhotoTapOk = uploadTapOk,
            allowAllOcrAttempts = allowAttempts,
            allowAllOcrPreviews = allowPreviews.toList(),
            allowAllTapPx = allowTapPx,
            allowAllTapOk = allowTapOk,
            avatarVerifyOcrAttempts = avatarTryCount,
            postConfirmChangePhotoOcrAttempts = postConfirmChangePhotoTries,
            bioVerifyOcrAttempts = bioVerifyTryCount,
            displayNameRandomApplied = appliedName,
            profileEditBioApplied = profileEditBioApplied,
            bioAfterPart1Ok = bioAfterPart1Ok,
            bioAfterPart2Ok = bioAfterPart2Ok,
            profileEditAssignedUsername = profileEditAssignedUsername,
            profileEditUsernameSyncedTo = profileEditUsernameSyncedTo,
        )

        delay(550)
        when {
            allowTapOk ->
                devOp(logAlias, "「修改基本资料」Allow all 已点击，等待 Recents 稳定后进入选图发帖")
            allowSkippedBecauseRecentsVisible ->
                devOp(logAlias, "「修改基本资料」Allow all 已跳过（相册已见 Recents），等待 Recents 稳定后进入选图发帖")
            else ->
                devOp(logAlias, "「修改基本资料」Allow all 未点击，等待 Recents 稳定后进入选图发帖")
        }
        if (!profileEditWaitRecentsStable(ctx, logAlias, w, h)) {
            return chainBase(success = false, failureStep = "recents_stable_miss")
        }

        delay(400)
        val cropX = (w * 0.125f).toInt().coerceIn(1, (w - 1).coerceAtLeast(1))
        val cropY = (h * 0.20f).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        devOp(
            logAlias,
            "「修改基本资料」左上区域固定点（约 0.125×宽, 0.20×高）→ (${cropX},${cropY})，屏 ${w}x${h}",
        )
        if (!runSuCommand("input tap $cropX $cropY")) {
            return chainBase(success = false, failureStep = "post_crop_corner_tap_failed")
        }

        // 发布作品功能：第一次点 Next 后，等待 1500ms ~ 2000ms，再点第二次 Next。
        // 这里和后面的 "Post this photo to Story" / "Save" 逻辑区分开，属于前一段图片处理流程。
        if (
            !profileEditOcrTapByFinder(
                ctx, logAlias, w, h, "profile_edit_post_next_a", "Next(第1次)", "Next", 6,
            ) { findStrictExactLabelHit(it, "Next") }
        ) {
            return chainBase(success = false, failureStep = "post_next_first_miss")
        }
        delay(Random.nextLong(1500L, 2001L))
        if (
            !profileEditOcrTapByFinder(
                ctx, logAlias, w, h, "profile_edit_post_next_b", "Next(第2次)", "Next", 6,
            ) { findStrictExactLabelHit(it, "Next") }
        ) {
            return chainBase(success = false, failureStep = "post_next_second_miss")
        }

        // 这里不是发布作品链，而是“修改账户基本信息”流程中的确认/返回步骤。
        // 保留原始 OCR 文案，因为当前页面确实会出现这些确认按钮/文本。
        delay(550)
        val postStoryOk = profileEditOcrTapByFinder(
            ctx, logAlias, w, h, "profile_edit_post_story", "Post this photo to Story", "Post this photo to Story",
            PROFILE_EDIT_POST_STORY_OCR_MAX_PASSES,
        ) { findStrictExactAcrossAdjacentLines(it, "Post this photo to Story") }
        if (!postStoryOk) {
            if (PrefsStore.isHardStopRequested()) {
                return chainBase(success = false, failureStep = "hard_stop")
            }
            devOp(
                logAlias,
                "「修改基本资料」Post this photo to Story：${PROFILE_EDIT_POST_STORY_OCR_MAX_PASSES} 次 OCR 均未命中，跳过点击，继续后续步骤",
            )
        }
        delay(550)
        if (
            !profileEditOcrTapByFinder(
                ctx, logAlias, w, h, "profile_edit_post_save", "Save(发帖)", "Save", 6,
            ) { findStrictExactLabelHit(it, "Save") }
        ) {
            return chainBase(success = false, failureStep = "post_save_ocr_miss")
        }

        if (!profileEditDelay(logAlias, 400L, "Save(发帖)后")) {
            return chainBase(success = false, failureStep = "hard_stop")
        }
        val uploadWaitOutcome = profileEditWaitAvatarUploadSettledOrChangePhoto(ctx, logAlias, runtimeConfig)
        var avatarPostVerifyCount = 0
        var avatarPostSeen = false
        when (uploadWaitOutcome) {
            ProfileEditAvatarUploadWaitOutcome.HardStop ->
                return chainBase(success = false, failureStep = "hard_stop")
            is ProfileEditAvatarUploadWaitOutcome.TimedOut -> {
                avatarPostVerifyCount = uploadWaitOutcome.pollAttempts
                return chainBase(
                    success = false,
                    failureStep = "avatar_uploading_wait_timeout",
                    avatarTryCount = avatarPostVerifyCount,
                )
            }
            is ProfileEditAvatarUploadWaitOutcome.ChangePhotoReady -> {
                avatarPostSeen = true
                avatarPostVerifyCount = uploadWaitOutcome.pollAttempts
                devOp(
                    logAlias,
                    "「修改基本资料」发帖后：上传等待阶段已见「Change photo」（等待 OCR ${uploadWaitOutcome.pollAttempts} 次），继续 Name→Bio",
                )
            }
            is ProfileEditAvatarUploadWaitOutcome.NeedChangePhotoVerify -> {
                avatarPostVerifyCount = uploadWaitOutcome.pollAttempts
                devOp(
                    logAlias,
                    "「修改基本资料」发帖后：uploading 已结束，开始 Change photo 校验（等待阶段 OCR ${uploadWaitOutcome.pollAttempts} 次）",
                )
                val (seen, verifyTries) = profileEditOcrUntilChangePhotoSeen(
                    ctx = ctx,
                    logAlias = logAlias,
                    ocrTagPrefix = "profile_edit_avatar_verify",
                    humanPhase = "发帖后头像校验",
                    maxTries = 7,
                )
                avatarPostSeen = seen
                avatarPostVerifyCount = uploadWaitOutcome.pollAttempts + verifyTries
            }
        }
        if (!avatarPostSeen) {
            if (PrefsStore.isHardStopRequested()) {
                return chainBase(success = false, failureStep = "hard_stop", avatarTryCount = avatarPostVerifyCount)
            }
            devOp(
                logAlias,
                "「修改基本资料」发帖后：7 次均未出现「Change photo」，判定异常，本链失败（外轮将强停重来）",
            )
            return chainBase(
                success = false,
                failureStep = "avatar_verify_change_photo_timeout",
                avatarTryCount = avatarPostVerifyCount,
            )
        }

        val postConfirmAttempts: Int
        if (PROFILE_EDIT_SKIP_NAME_STEP) {
            devOp(logAlias, "「修改基本资料」显示名(Name)：已临时跳过（PROFILE_EDIT_SKIP_NAME_STEP=true），继续 TikTok 文案/Bio")
            postConfirmAttempts = 0
        } else {
            val nameRecall = menuNamePxOut
            if (nameRecall == null) {
                return chainBase(
                    success = false,
                    failureStep = "display_name_menu_coords_missing",
                    avatarTryCount = avatarPostVerifyCount,
                )
            }
            devOp(logAlias, "「修改基本资料」显示名：点击菜单阶段记录的 Name (${nameRecall.first},${nameRecall.second}) 聚焦输入框")
            if (!runSuCommand("input tap ${nameRecall.first} ${nameRecall.second}")) {
                return chainBase(
                    success = false,
                    failureStep = "display_name_tap_failed",
                    avatarTryCount = avatarPostVerifyCount,
                )
            }
            delay(450)
            if (!profileEditOcrPreferredNameEditable(ctx, logAlias)) {
                if (PrefsStore.isHardStopRequested()) {
                    return chainBase(
                        success = false,
                        failureStep = "hard_stop",
                        avatarTryCount = avatarPostVerifyCount,
                    )
                }
                navBack(logAlias, "Name 不可编辑：跳过修改显示名并返回资料菜单")
                if (!profileEditDelay(logAlias, 520L, "Name跳过返回后")) {
                    return chainBase(
                        success = false,
                        failureStep = "hard_stop",
                        avatarTryCount = avatarPostVerifyCount,
                    )
                }
                postConfirmAttempts = 0
            } else {
                profileEditClearFocusedNameField(logAlias)
                delay(350)
                val picked = ProfileEditFemaleDisplayNames.NAMES[Random.nextInt(ProfileEditFemaleDisplayNames.NAMES.size)]
                devOp(logAlias, "「修改基本资料」显示名：随机抽取「$picked」，输入到已清空后的框内")
                if (!tryInputCommentText(logAlias, picked)) {
                    return chainBase(
                        success = false,
                        failureStep = "display_name_input_failed",
                        avatarTryCount = avatarPostVerifyCount,
                        appliedName = picked,
                    )
                }

                val nameSaveTap = profileEditOcrTapSaveTopmost(ctx, logAlias, w, h)
                if (nameSaveTap == null) {
                    return chainBase(
                        success = false,
                        failureStep = "name_flow_save_ocr_miss",
                        avatarTryCount = avatarPostVerifyCount,
                        appliedName = picked,
                    )
                }

                if (!profileEditOcrTapConfirmPasses(ctx, logAlias, w, h)) {
                    return chainBase(
                        success = false,
                        failureStep = "name_flow_confirm_miss",
                        avatarTryCount = avatarPostVerifyCount,
                        appliedName = picked,
                    )
                }

                delay(1000L)
                val (postConfirmCpOk, postConfirmCpAttempts) = profileEditOcrUntilChangePhotoSeen(
                    ctx,
                    logAlias,
                    "profile_edit_post_confirm_cp",
                    "Confirm 后资料页（需见 Change photo）",
                )
                if (!postConfirmCpOk) {
                    devOp(
                        logAlias,
                        "「修改基本资料」Confirm 后：7 次均未出现「Change photo」，判定页面异常，本链失败（外轮将强停重来）",
                    )
                    return chainBase(
                        success = false,
                        failureStep = "post_confirm_change_photo_timeout",
                        avatarTryCount = avatarPostVerifyCount,
                        postConfirmChangePhotoTries = postConfirmCpAttempts,
                        appliedName = picked,
                    )
                }
                postConfirmAttempts = postConfirmCpAttempts
            }
        }

        val bioRecall = menuBioPxOut
        if (bioRecall == null) {
            return chainBase(
                success = false,
                failureStep = "bio_menu_coords_missing",
                avatarTryCount = avatarPostVerifyCount,
                postConfirmChangePhotoTries = postConfirmAttempts,
            )
        }

        val bioPool = (runtimeConfig.profile_edit_bio_texts ?: emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (bioPool.isEmpty()) {
            devOp(logAlias, "「修改基本资料」Bio：运行配置 profile_edit_bio_texts 为空（请确认管理台已保存并下发，且设备已拉取 get_config），本链失败")
            return chainBase(
                success = false,
                failureStep = "profile_edit_bio_texts_empty",
                avatarTryCount = avatarPostVerifyCount,
                postConfirmChangePhotoTries = postConfirmAttempts,
            )
        }
        val bioPicked = bioPool[Random.nextInt(bioPool.size)]

        val assignedUsername = fetchAssignedUsernameForProfileEdit(logAlias)
        profileEditAssignedUsername = assignedUsername.orEmpty()
        if (assignedUsername == null) {
            devOp(logAlias, "「修改基本资料」Bio 前：无分配账号 username，仍尝试 OCR 点 TikTok 文案；成功也不做云端比对")
        }
        val usernameCopiedToClipboard = profileEditOcrTapTikTokTextCopy(ctx, logAlias, w, h)
        if (!usernameCopiedToClipboard) {
            devOp(logAlias, "「修改基本资料」Bio 前：3 次 OCR 均未点到 TikTok 文案，跳过剪贴板比对")
        }

        devOp(logAlias, "「修改基本资料」Bio：点击菜单阶段记录的 Bio (${bioRecall.first},${bioRecall.second}) 聚焦输入框")
        if (!runSuCommand("input tap ${bioRecall.first} ${bioRecall.second}")) {
            return chainBase(
                success = false,
                failureStep = "bio_tap_failed",
                avatarTryCount = avatarPostVerifyCount,
                postConfirmChangePhotoTries = postConfirmAttempts,
            )
        }
        delay(450)
        if (usernameCopiedToClipboard && assignedUsername != null) {
            profileEditSyncUsernameFromBioClipboardIfNeeded(logAlias, assignedUsername)
                ?.let { profileEditUsernameSyncedTo = it }
        }
        profileEditClearFocusedNameField(logAlias)
        delay(350)
        devOp(logAlias, "「修改基本资料」Bio：从模板 profile_edit_bio_texts 随机抽取并输入（${bioPicked.length} 字）")
        if (!tryInputCommentText(logAlias, bioPicked)) {
            return chainBase(
                success = false,
                failureStep = "bio_input_failed",
                avatarTryCount = avatarPostVerifyCount,
                postConfirmChangePhotoTries = postConfirmAttempts,
                profileEditBioApplied = bioPicked,
            )
        }

        val maxBioAfterPart1 = 3
        val maxBioAfterPart2 = 3
        var finalCpAttempts = 0
        var part1Ok = false
        var part1Round = 0
        var lastPart1Fail = "bio_after_part1_unknown"

        devOp(
            logAlias,
            "「修改基本资料」Bio 输入完成：进入两段收尾（①Save→等2s→终验 Change photo→返回，失败只重试①，至多 $maxBioAfterPart1 轮；" +
                "②比例点→【插入】Permissions/Save login info/返回→【旧】Permissions校验+Privacy→…，②失败重试=强停→拉起→(0.97,0.95)资料点→再②，至多 $maxBioAfterPart2 轮）",
        )
        while (!part1Ok && part1Round < maxBioAfterPart1) {
            if (hardStopOrNull(logAlias, "修改基本资料-Bio后①")) {
                return chainBase(
                    success = false,
                    failureStep = "hard_stop",
                    avatarTryCount = avatarPostVerifyCount,
                    postConfirmChangePhotoTries = postConfirmAttempts,
                    profileEditBioApplied = bioPicked,
                )
            }
            part1Round++
            if (part1Round > 1) {
                if (!profileEditDelay(logAlias, 700, "Bio后①重试")) {
                    return chainBase(
                        success = false,
                        failureStep = "hard_stop",
                        avatarTryCount = avatarPostVerifyCount,
                        postConfirmChangePhotoTries = postConfirmAttempts,
                        profileEditBioApplied = bioPicked,
                    )
                }
                devOp(logAlias, "「修改基本资料」Bio 后① 第 $part1Round 轮：Save 未命中后先 OCR/回退重定位 Bio")
                profileEditRefocusBioAndReinput(
                    ctx,
                    logAlias,
                    w,
                    h,
                    bioPicked,
                    bioRecall,
                    "part1_r$part1Round",
                )
                if (!profileEditDelay(logAlias, 450, "Bio重聚焦后")) {
                    return chainBase(
                        success = false,
                        failureStep = "hard_stop",
                        avatarTryCount = avatarPostVerifyCount,
                        postConfirmChangePhotoTries = postConfirmAttempts,
                        profileEditBioApplied = bioPicked,
                    )
                }
            }
            devOp(logAlias, "「修改基本资料」Bio 后① 第 $part1Round/$maxBioAfterPart1 轮：Save(最上 OCR；未命中时同屏顺手重定位 Bio)")
            if (profileEditOcrTapSaveTopmost(ctx, logAlias, w, h, bioPicked, bioRecall) == null) {
                if (isHardStopActive()) {
                    return chainBase(
                        success = false,
                        failureStep = "hard_stop",
                        avatarTryCount = avatarPostVerifyCount,
                        postConfirmChangePhotoTries = postConfirmAttempts,
                        profileEditBioApplied = bioPicked,
                    )
                }
                lastPart1Fail = "bio_flow_save_ocr_miss"
                devOp(logAlias, "「修改基本资料」Bio 后① 第 $part1Round 轮：Save 未命中，将重试①（不进入②）")
                continue
            }
            if (!profileEditDelay(logAlias, 2000L, "Bio Save 后")) {
                return chainBase(
                    success = false,
                    failureStep = "hard_stop",
                    avatarTryCount = avatarPostVerifyCount,
                    postConfirmChangePhotoTries = postConfirmAttempts,
                    profileEditBioApplied = bioPicked,
                )
            }
            val (finalCpOk, cpAttempts) = profileEditOcrUntilChangePhotoSeen(
                ctx,
                logAlias,
                "profile_edit_after_bio_cp_r${part1Round}",
                "Bio 保存后终验（需见 Change photo）",
            )
            finalCpAttempts = cpAttempts
            if (!finalCpOk) {
                if (isHardStopActive()) {
                    return chainBase(
                        success = false,
                        failureStep = "hard_stop",
                        avatarTryCount = avatarPostVerifyCount,
                        postConfirmChangePhotoTries = postConfirmAttempts,
                        bioVerifyTryCount = finalCpAttempts,
                        profileEditBioApplied = bioPicked,
                    )
                }
                lastPart1Fail = "bio_save_change_photo_timeout"
                devOp(
                    logAlias,
                    "「修改基本资料」Bio 后① 第 $part1Round 轮：7 次未见「Change photo」，将重试①（不进入②）",
                )
                continue
            }
            navBack(logAlias, "Bio 终验后进入设置：系统返回一次")
            if (!profileEditDelay(logAlias, 520, "Bio后①返回后")) {
                return chainBase(
                    success = false,
                    failureStep = "hard_stop",
                    avatarTryCount = avatarPostVerifyCount,
                    postConfirmChangePhotoTries = postConfirmAttempts,
                    bioVerifyTryCount = finalCpAttempts,
                    profileEditBioApplied = bioPicked,
                    bioAfterPart1Ok = true,
                )
            }
            part1Ok = true
            devOp(logAlias, "「修改基本资料」Bio 后① 第 $part1Round 轮成功，进入②（Privacy/Viewers 链）")
        }
        if (!part1Ok) {
            devOp(
                logAlias,
                "「修改基本资料」Bio 后① 已用尽 $maxBioAfterPart1 轮，本链失败（外轮将强停重来）",
            )
            return chainBase(
                success = false,
                failureStep = lastPart1Fail,
                avatarTryCount = avatarPostVerifyCount,
                postConfirmChangePhotoTries = postConfirmAttempts,
                bioVerifyTryCount = finalCpAttempts,
                profileEditBioApplied = bioPicked,
            )
        }

        var part2Ok = false
        var part2Round = 0
        var lastPart2Fail = "bio_after_part2_unknown"
        while (!part2Ok && part2Round < maxBioAfterPart2) {
            if (hardStopOrNull(logAlias, "修改基本资料-Bio后②")) {
                return chainBase(
                    success = false,
                    failureStep = "hard_stop",
                    avatarTryCount = avatarPostVerifyCount,
                    postConfirmChangePhotoTries = postConfirmAttempts,
                    bioVerifyTryCount = finalCpAttempts,
                    profileEditBioApplied = bioPicked,
                    bioAfterPart1Ok = true,
                    bioAfterPart2Ok = false,
                )
            }
            part2Round++
            if (part2Round > 1) {
                if (!profileEditWarmupRelaunchToProfilePage(ctx, logAlias, part2Round, runtimeConfig)) {
                    if (isHardStopActive()) {
                        return chainBase(
                            success = false,
                            failureStep = "hard_stop",
                            avatarTryCount = avatarPostVerifyCount,
                            postConfirmChangePhotoTries = postConfirmAttempts,
                            bioVerifyTryCount = finalCpAttempts,
                            profileEditBioApplied = bioPicked,
                            bioAfterPart1Ok = true,
                            bioAfterPart2Ok = false,
                        )
                    }
                    lastPart2Fail = "part2_retry_warmup_failed"
                    devOp(
                        logAlias,
                        "「修改基本资料」Bio 后② 第 $part2Round 轮：关开 TikTok / 资料比例点 warmup 失败（不重复 Save/终验/返回），将重试②",
                    )
                    continue
                }
            }
            val (privacyChainOk, privacyChainFail) = profileEditRunPart2PrivacyViewersChain(
                ctx,
                logAlias,
                w,
                h,
                part2Round,
            )
            if (!privacyChainOk) {
                if (privacyChainFail == "hard_stop") {
                    return chainBase(
                        success = false,
                        failureStep = "hard_stop",
                        avatarTryCount = avatarPostVerifyCount,
                        postConfirmChangePhotoTries = postConfirmAttempts,
                        bioVerifyTryCount = finalCpAttempts,
                        profileEditBioApplied = bioPicked,
                        bioAfterPart1Ok = true,
                        bioAfterPart2Ok = false,
                    )
                }
                lastPart2Fail = privacyChainFail
                devOp(
                    logAlias,
                    "「修改基本资料」Bio 后② 第 $part2Round 轮：Privacy/Viewers 链失败 step=$privacyChainFail，将重试②",
                )
                continue
            }
            part2Ok = true
            devOp(logAlias, "「修改基本资料」Bio 后② 第 $part2Round 轮成功（Privacy→Comments 校验→Viewers On/Off 或 Viewer history）")
        }
        if (!part2Ok) {
            devOp(
                logAlias,
                "「修改基本资料」Bio 后② 已用尽 $maxBioAfterPart2 轮（① 已成功），本链失败（外轮将强停重来）",
            )
            devOp(
                logAlias,
                "「修改基本资料」Bio 后① 已完成但② 失败，不上报云端「基本资料已修改」（step=$lastPart2Fail）",
            )
            return chainBase(
                success = false,
                failureStep = lastPart2Fail,
                avatarTryCount = avatarPostVerifyCount,
                postConfirmChangePhotoTries = postConfirmAttempts,
                bioVerifyTryCount = finalCpAttempts,
                profileEditBioApplied = bioPicked,
                bioAfterPart1Ok = true,
                bioAfterPart2Ok = false,
            )
        }

        val bioShort = if (bioPicked.length <= 40) bioPicked else bioPicked.take(40) + "…"
        devOp(
            logAlias,
            "「修改基本资料」OCR 链成功（①② 均完成，可上报云端标「基本资料已修改」为是）：Edit→…→Bio「$bioShort」→" +
                "①Save→终验→返回→②比例点→【插入】Permissions/Save login info/返回→【旧】Permissions校验→Privacy→Comments→Viewers→等 3s(仅 Off 路径)",
        )
        devOp(logAlias, "「修改基本资料」①② 完成，关闭 TikTok 后台：am force-stop ${Config.TIKTOK_PACKAGE_NAME}")
        runSuCommand("am force-stop ${Config.TIKTOK_PACKAGE_NAME}")
        delay(800)
        return chainBase(
            success = true,
            failureStep = "",
            avatarTryCount = avatarPostVerifyCount,
            postConfirmChangePhotoTries = postConfirmAttempts,
            bioVerifyTryCount = finalCpAttempts,
            profileEditBioApplied = bioPicked,
            bioAfterPart1Ok = true,
            bioAfterPart2Ok = true,
        )
    }

    /**
     * 连续处理多个主用户名时，上一轮如果已回退到「搜索结果/Users 选择界面」，
     * 下一轮可以跳过再次点放大镜与重新启动 app，仅需清空搜索框并输入下一个主用户名。
     */
    private var lastEndedAtUsersSelection: Boolean = false
    private var livePostClickAtMs: Long = 0L
    /** 最近一次点击的作品位：growth 为 left/middle/right；live/direct 为 left_first */
    private var lastClickedPostSlot: String = ""
    private var currentRootUsernameForLogs: String = ""

    private data class DevOpTapCoalesce(
        val actionPrefix: String,
        var count: Int,
        val coords: MutableList<String>,
    )

    private val devOpTapCoalesceByAlias = HashMap<String, DevOpTapCoalesce>()

    private var autoLoginEmailAfterProbeAlias: String = ""
    private var autoLoginEmailAfterProbePassStart: Int = 0
    private var autoLoginEmailAfterProbePassEnd: Int = 0
    private var autoLoginEmailAfterProbeFlags: String = ""

    private val devOpTapCoalescePattern =
        Regex("""^(自动登录[^:]+)：tap \((\d+),(\d+)\)(.*)$""")

    private fun devOpFlushTapCoalesce(alias: String) {
        val pending = devOpTapCoalesceByAlias.remove(alias) ?: return
        val coordPart = when {
            pending.coords.isEmpty() -> ""
            pending.coords.size == 1 -> pending.coords[0]
            else -> pending.coords.joinToString(" → ")
        }
        val suffix = if (pending.count > 1) "（${pending.count}次）" else ""
        devOpEmit(alias, "${pending.actionPrefix} $coordPart$suffix".trim())
    }

    private fun autoLoginFlushEmailAfterProbeLog(alias: String) {
        if (autoLoginEmailAfterProbePassStart <= 0) return
        val range = if (autoLoginEmailAfterProbePassStart == autoLoginEmailAfterProbePassEnd) {
            "第${autoLoginEmailAfterProbePassStart}次"
        } else {
            "第${autoLoginEmailAfterProbePassStart}-${autoLoginEmailAfterProbePassEnd}次"
        }
        devOpEmit(alias, "自动登录 Email后OCR $range: $autoLoginEmailAfterProbeFlags")
        autoLoginEmailAfterProbeAlias = ""
        autoLoginEmailAfterProbePassStart = 0
        autoLoginEmailAfterProbePassEnd = 0
        autoLoginEmailAfterProbeFlags = ""
    }

    /** 过滤命令细节、OCR 逐次重试等噪音，只保留对用户有意义的步骤/结果。 */
    private fun shouldSkipDeviceLog(action: String): Boolean {
        val s = action
        val commandNoise = listOf(
            "am force-stop", "am start", "monkey -p", "pm clear", "pkill", "killall",
            "input tap", "input text", "input swipe", "input keyevent", "screencap",
            "uiautomator", "执行启动命令", "启动命令[", "强停命令", "行摘要=", "OCR 摘要=", "摘要=",
            "su tap", "（su）", "bbox(", "拟 tap=", "点击中心=",
            "dumpsys", "top=", "forceStopWaitMs=", "launchWaitMs=",
            "startActivity ", "distance=", "startY=", "endY=",
            "不 input tap", "input 失败", "文案 input",
        )
        if (commandNoise.any { s.contains(it, ignoreCase = true) }) return true
        if (Regex("""第\s*\d+\s*/\s*\d+\s*次[：:]""").containsMatchIn(s)) {
            val keep = listOf("失败", "成功", "完成", "已定位", "已点", "已见", "读取到文案", "上限")
            if (keep.none { s.contains(it) }) return true
        }
        val launchNoise = listOf(
            "启动前先关闭", "直接尝试拉起", "前台未确认为", "不立刻重试拉起",
            "已调用但前台", "启动意图不可用", "所有启动命令都失败",
            "准备 root 兜底", "root 兜底已执行", "拉起 TikTok 后等待",
            "强停命令返回", "执行二次兜底重启", "跳过二次强停拉起",
        )
        if (launchNoise.any { s.contains(it) }) return true
        if (s.startsWith("发作品入口：")) return true
        if (s.contains("进入 executeForUsername：")) return true
        if (Regex("""第\s*\d+\s*步[：:]""").containsMatchIn(s)) return true
        if (s.contains("发布条数=Pictures") || s.contains("断点续发：")) return true
        if (s.startsWith("发作品：") && s.contains("Allow all")) return false
        // Recents / Pictures 相册入口 OCR 调试文案必须保留，便于排查「未命中」原因
        if (s.startsWith("发作品：") && (s.contains("Recents 探测") || s.contains("Pictures 探测"))) return false
        if (s.startsWith("发作品：") && (
                s.contains("比例点 (") || s.contains("右下固定点 (") || s.contains("布局 B 备用") ||
                    s.contains("OCR 第") || s.contains("OCR 摘要=") || s.contains("跳过拉起等待") ||
                    s.contains("当日首条权限 OCR=") || s.contains("续发：")
                )
        ) return true
        return false
    }

    /** 设备端实时日志：只记录当前操作，可选附带耗时（毫秒）。 */
    private fun devOpEmit(alias: String, action: String, elapsedMs: Long? = null) {
        if (shouldSkipDeviceLog(action)) return
        val root = currentRootUsernameForLogs.trim()
        val prefix = if (root.isNotEmpty()) "[$root] " else ""
        LogCenter.op(alias, "$prefix$action", elapsedMs)
    }

    private fun devOp(alias: String, action: String, elapsedMs: Long? = null) {
        if (shouldSkipDeviceLog(action)) {
            return
        }
        val tapMatch = devOpTapCoalescePattern.matchEntire(action)
        if (tapMatch != null) {
            val actionPrefix = tapMatch.groupValues[1] + "：tap"
            val coord = "(${tapMatch.groupValues[2]},${tapMatch.groupValues[3]})"
            val pending = devOpTapCoalesceByAlias[alias]
            if (pending != null && pending.actionPrefix == actionPrefix) {
                pending.count++
                pending.coords.add(coord)
                return
            }
            devOpFlushTapCoalesce(alias)
            devOpTapCoalesceByAlias[alias] = DevOpTapCoalesce(actionPrefix, 1, mutableListOf(coord))
            return
        }
        devOpFlushTapCoalesce(alias)
        autoLoginFlushEmailAfterProbeLog(alias)
        devOpEmit(alias, action, elapsedMs)
    }

    /** 统一返回动作日志：所有 back/keyevent 4 均使用“导航返回”。 */
    private fun navBack(alias: String, target: String) {
        devOp(alias, "导航返回：$target")
        runSuCommand("input keyevent 4")
    }

    /**
     * dumpsys 判定 TikTok 非前台时尽量恢复任务，不因「当前不在 TikTok 界面」直接放弃：
     * 1) 先不切前台强停，尝试把已在后台的 TikTok 拉到前台（部分机型有效）；
     * 2) 仍非前台则 [restartTikTokForRecovery]：强停杀后台 → 完整冷启 → dumpsys 再验。
     *
     * TikTok 在后台但 dumpsys 仍显示非前台时，第 1 步可能无效，第 2 步更可靠。
     */
    /**
     * TikTok 已启动但系统权限授权页盖在最前（焦点在 permissioncontroller，TikTok 进程常在后台）。
     */
    private fun isSystemPermissionGrantDialogForeground(): Boolean {
        val probe = collectForegroundProbeTexts()
        resolveStrictForegroundPackage(probe)?.let { fg ->
            if (fg.contains("permissioncontroller", ignoreCase = true)) return true
            if (fg.contains("packageinstaller", ignoreCase = true)) return true
        }
        return filterDumpsysFocusLines(probe).any { line ->
            line.contains("GrantPermissionsActivity", ignoreCase = true) ||
                line.contains("permissioncontroller", ignoreCase = true)
        }
    }

    /** 权限页在前台时：整屏 OCR 点 Don't allow → 拉起 TikTok → dumpsys 严格校验前台。 */
    private suspend fun bringTikTokToForegroundAndConfirm(
        ctx: Context,
        alias: String,
        tag: String,
    ): Boolean {
        val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
        var launched = runTikTokRootLaunchCommands()
        if (!launched) {
            val intent = buildTikTokLaunchIntent(ctx)
            if (intent != null) {
                runCatching { ctx.startActivity(intent) }
                launched = true
            }
        }
        if (!launched) {
            launched = runTikTokRootLaunchCommands()
        }
        if (!launched) {
            devOp(alias, "$tag：恢复 TikTok 前台时拉起命令均未成功")
            return false
        }
        delay(launchWaitMs.coerceAtMost(4000L).coerceAtLeast(800L))
        invalidateForegroundProbeCache()
        if (isTikTokForeground()) return true
        delay(900L)
        invalidateForegroundProbeCache()
        return isTikTokForeground()
    }

    private suspend fun dismissSystemPermissionDialogAndRestoreTikTok(
        ctx: Context,
        alias: String,
        tag: String,
    ): Boolean {
        if (!isSystemPermissionGrantDialogForeground()) return false
        devOp(alias, "$tag：检测到系统权限授权页在前台（TikTok 可能在后台），整屏 OCR 点 Don't allow")
        val logSuffix = tag.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "perm" }
        tryDismissDontAllowOcr(
            ctx = ctx,
            alias = alias,
            phaseLog = "权限弹窗 OCR 取消",
            logSuffix = logSuffix,
            maxPasses = TIKTOK_LAUNCH_DONT_ALLOW_OCR_MAX_PASSES,
            retryDelayMs = TIKTOK_LAUNCH_DONT_ALLOW_OCR_RETRY_DELAY_MS,
            useSideEffectFreeOcr = true,
            findDismissHit = { findLaunchPermissionDismissHit(it) },
        )
        invalidateForegroundProbeCache()
        delay(400L)
        if (isTikTokForeground()) {
            devOp(alias, "$tag：点 Don't allow 后 TikTok 已回到前台")
            dismissDontAllowAfterTikTokLaunch(ctx, alias, tag)
            invalidateForegroundProbeCache()
            return isTikTokForeground()
        }
        devOp(alias, "$tag：点 Don't allow 后 TikTok 仍非前台，尝试拉起并严格校验")
        if (!bringTikTokToForegroundAndConfirm(ctx, alias, tag)) {
            logForegroundProbeFailure(alias, tag, Config.TIKTOK_PACKAGE_NAME)
            devOp(alias, "$tag：权限弹窗处理后仍无法确认 TikTok 前台")
            return false
        }
        devOp(alias, "$tag：权限弹窗处理后 TikTok 前台已确认")
        dismissDontAllowAfterTikTokLaunch(ctx, alias, tag)
        invalidateForegroundProbeCache()
        return isTikTokForeground()
    }

    private suspend fun ensureTikTokInForeground(
        ctx: Context,
        alias: String,
        tag: String,
        allowForceRestart: Boolean = true,
    ): Boolean {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        if (isPackageVisible(pkg)) {
            devOp(alias, "$tag：dumpsys 已确认 TikTok 在前台，跳过拉起")
            return true
        }

        if (dismissSystemPermissionDialogAndRestoreTikTok(ctx, alias, "$tag·权限页")) {
            devOp(alias, "$tag：权限授权页已处理，TikTok 前台已恢复，继续任务")
            return true
        }

        val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)

        devOp(alias, "$tag：dumpsys 非 TikTok 前台，先尝试切到前台（不强停，避免误杀后台进程）")
        if (launchTikTokOnceWithCheck(ctx, alias, "$tag·切前台", launchWaitMs, forceStopBeforeLaunch = false)) {
            devOp(alias, "$tag：切前台后已确认 TikTok 在前台，继续任务")
            return true
        }

        if (dismissSystemPermissionDialogAndRestoreTikTok(ctx, alias, "$tag·切前台后权限")) {
            devOp(alias, "$tag：切前台失败后经权限页恢复，TikTok 前台已确认，继续任务")
            return true
        }

        devOp(alias, "$tag：切前台后仍非 TikTok 前台（可能在后台或拉起无效），强停后完整重拉")
        if (!allowForceRestart) {
            logForegroundProbeFailure(alias, tag, pkg)
            devOp(alias, "$tag：禁止强停重启，无法确认 TikTok 前台")
            return false
        }
        if (restartTikTokForRecovery()) {
            devOp(alias, "$tag：强停重拉成功，已确认 TikTok 在前台，继续任务")
            return true
        }

        if (dismissSystemPermissionDialogAndRestoreTikTok(ctx, alias, "$tag·重拉后权限")) {
            devOp(alias, "$tag：强停重拉失败后经权限页恢复，TikTok 前台已确认，继续任务")
            return true
        }

        logForegroundProbeFailure(alias, tag, pkg)
        devOp(alias, "$tag：强停重拉后前台仍未确认，本段任务无法安全继续")
        return false
    }

    private suspend fun runForYouWarmupIfNeeded(service: Context, alias: String, config: AppConfig) {
        val totalMs = (config.for_you_swipe_total_sec * 1000.0).toLong().coerceAtLeast(0L)
        if (totalMs < 500L) {
            devOp(alias, "For You 预滑：配置 for_you_swipe_total_sec=${config.for_you_swipe_total_sec}，未启用，跳过")
            return
        }
        if (PrefsStore.isWithinHardStopGracePeriod()) {
            devOp(alias, "For You 预滑：检测到刚经历硬停恢复，跳过本次休息/预滑")
            return
        }
        if (growthYieldOrNull(alias, "For You 预滑")) {
            forceStopTikTokIfExclusivePreempt(alias, "For You 预滑")
            return
        }
        if (!ensureTikTokInForeground(service, alias, "For You 预滑")) return

        val likeProb = config.for_you_like_prob.coerceIn(0.0, 1.0)
        val rawMinMs = (config.for_you_watch_min_sec * 1000.0).toLong().coerceAtLeast(500L)
        val rawMaxMs = (config.for_you_watch_max_sec * 1000.0).toLong().coerceAtLeast(500L)
        val watchMinMs = minOf(rawMinMs, rawMaxMs)
        val watchMaxMs = maxOf(rawMinMs, rawMaxMs)
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val likeX = (width * 0.5f).toInt()
        val likeY = (height * 0.55f).toInt()
        val startedAt = System.currentTimeMillis()

        val logoutProbeEnabled = totalMs >= FOR_YOU_WARMUP_LOGOUT_PROBE_MIN_TOTAL_MS
        devOp(
            alias,
            if (logoutProbeEnabled) {
                "For You 预滑开始：总时长=${totalMs}ms"
            } else {
                "For You 预滑开始：总时长=${totalMs}ms（低于5分钟，跳过掉登录检测）"
            },
        )
        val logoutProbeAtMs = if (logoutProbeEnabled) {
            LongArray(FOR_YOU_WARMUP_LOGOUT_PROBE_PHASES) { phase ->
                (totalMs * phase / FOR_YOU_WARMUP_LOGOUT_PROBE_PHASES).coerceIn(0L, totalMs)
            }
        } else {
            null
        }
        var logoutProbesDone = 0
        var swipeCount = 0
        while (System.currentTimeMillis() - startedAt < totalMs) {
            if (growthYieldOrNull(alias, "For You 预滑")) {
                forceStopTikTokIfExclusivePreempt(alias, "For You 预滑")
                return
            }
            if (mutualClickOrNull(alias, "For You 预滑")) return
            val elapsedBeforeSegment = System.currentTimeMillis() - startedAt
            val probeSchedule = logoutProbeAtMs
            if (probeSchedule != null) {
                while (logoutProbesDone < FOR_YOU_WARMUP_LOGOUT_PROBE_PHASES &&
                    elapsedBeforeSegment >= probeSchedule[logoutProbesDone]
                ) {
                    probeForYouWarmupLogoutSignalsAtPhaseStart(service, alias, logoutProbesDone + 1)
                    logoutProbesDone++
                }
            }
            val planWatchMs =
                if (watchMaxMs > watchMinMs) Random.nextLong(watchMinMs, watchMaxMs + 1) else watchMinMs
            val elapsed = System.currentTimeMillis() - startedAt
            val remain = (totalMs - elapsed).coerceAtLeast(0L)
            val watchMs = minOf(planWatchMs, remain)
            if (watchMs <= 0L) break

            val segmentT0 = System.currentTimeMillis()
            val doLike = Random.nextDouble() < likeProb
            // 双击点赞约占用 ~250ms，留出随机落点区间 [preDelay, watchMs - budget)
            val likeTapBudgetMs = 280L
            if (doLike && watchMs >= likeTapBudgetMs + 80L) {
                val hiExclusive = watchMs - likeTapBudgetMs
                val preDelay = Random.nextLong(40L, hiExclusive + 1)
                if (!growthDelay(alias, preDelay, "For You 预滑-点赞前")) return
                val jitterX = Random.nextInt(-18, 19)
                val jitterY = Random.nextInt(-18, 19)
                val tapX = (likeX + jitterX).coerceIn(1, width - 1)
                val tapY = (likeY + jitterY).coerceIn(1, height - 1)
                runSuCommand("input tap $tapX $tapY")
                delay(90)
                runSuCommand("input tap $tapX $tapY")
                devOp(alias, "For You 点赞：观看中随机时刻双击")
            } else if (doLike && watchMs >= 120L) {
                // 本段观看较短：在中间附近点一次赞，避免总在段首
                val preDelay = Random.nextLong(0L, (watchMs / 2).coerceAtLeast(1L))
                if (!growthDelay(alias, preDelay, "For You 预滑-短段点赞前")) return
                val jitterX = Random.nextInt(-18, 19)
                val jitterY = Random.nextInt(-18, 19)
                val tapX = (likeX + jitterX).coerceIn(1, width - 1)
                val tapY = (likeY + jitterY).coerceIn(1, height - 1)
                runSuCommand("input tap $tapX $tapY")
                delay(90)
                runSuCommand("input tap $tapX $tapY")
                devOp(alias, "For You 点赞：短观看段内双击")
            }

            val spentInSegment = System.currentTimeMillis() - segmentT0
            val rest = (watchMs - spentInSegment).coerceAtLeast(0L)
            if (rest > 0L && !growthDelay(alias, rest, "For You 预滑-观看")) return
            if (System.currentTimeMillis() - startedAt >= totalMs) break

            if (Random.nextDouble() < 0.12) {
                if (!growthDelay(alias, Random.nextLong(120, 401), "For You 预滑-翻页前犹豫")) return
            }
            val settleMs = forYouFeedSwipeNextHumanized(alias, width, height, "For You 预滑翻页")
            if (!growthDelay(alias, settleMs, "For You 预滑-翻页")) return
            swipeCount++
            if (swipeCount >= FOR_YOU_WARMUP_BOTTOM_NAV_CHECK_EVERY_SWIPES) {
                swipeCount = 0
                if (!verifyForYouWarmupBottomNavOrRecover(service, alias, height)) return
            }
        }
        devOp(alias, "For You 预滑结束：已达到配置时长")
    }

    override suspend fun executeForUsername(username: String, config: AppConfig): String {
        val service: Context = appContextProvider()
        val ctx = service
        lastConfigSnapshot = config


        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "执行用户名流程 $username")) {
            return "已硬停，跳过 $username"
        }
        val trimmedUsername = username.trim()
        if (trimmedUsername == "__publish_post__") {
            currentRootUsernameForLogs = trimmedUsername
            try {
                if (hardStopOrNull(alias, "发作品入口 __publish_post__")) {
                    val accountKeyForResume =
                        PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() } ?: alias
                    PrefsStore.clearPublishPostResumeProgress(accountKeyForResume)
                    PrefsStore.clearPublishPostTaskScope()
                    PrefsStore.setPublishPostPackageName(null)
                    PrefsStore.clearPublishPostState()
                    return "已硬停，跳过发作品入口"
                }
                val pkg = PrefsStore.getPublishPostPackageName()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: Config.TIKTOK_PACKAGE_NAME
                val accountKeyForResume =
                    PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() } ?: alias
                lastPublishPostEntryFailureReason = null
                val ok = runPublishPostEntryFlow(service, pkg, config)
                if (ok) {
                    PrefsStore.clearPublishPostResumeProgress(accountKeyForResume)
                    PrefsStore.setPublishPostPending(false)
                    PrefsStore.setPublishPostRequested(false)
                    if (PrefsStore.isAutoSwitchPublishTask()) {
                        // 自治发作品：保留 slot/source/opId，由 AppCoordinator 上报 outcome 后再清理
                    } else {
                        PrefsStore.clearPublishPostTaskScope()
                        PrefsStore.setPublishPostPackageName(null)
                        PrefsStore.clearPublishPostState()
                    }
                } else if (PrefsStore.isHardStopRequested()) {
                    PrefsStore.clearPublishPostResumeProgress(accountKeyForResume)
                    PrefsStore.clearPublishPostTaskScope()
                    PrefsStore.setPublishPostPackageName(null)
                    PrefsStore.clearPublishPostState()
                } else {
                    PrefsStore.setPublishPostPending(true)
                    PrefsStore.setPublishPostRequested(true)
                }
                return if (ok) {
                    "发作品：已完成"
                } else {
                    lastPublishPostEntryFailureReason?.let { "发作品：未完成（$it）" } ?: "发作品：未完成"
                }
            } finally {
                if (!PrefsStore.isHardStopRequested()) {
                    val deviceId = PrefsStore.getDeviceId()
                    val devAlias = PrefsStore.getAlias()
                    if (!deviceId.isNullOrBlank() && !devAlias.isNullOrBlank()) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                ApiService.reportDeviceAutoStartOptOut(
                                    deviceId = deviceId,
                                    alias = devAlias,
                                    optOut = false,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (growthYieldOrNull(alias, "执行用户名流程 $username")) {
            forceStopTikTokIfExclusivePreempt(alias, "执行用户名流程 $username")
            return growthYieldSkipMessage(username)
        }
        return withAccountStatusOcrWatch {
        didRestartTikTokInCurrentRun = false
        currentRootUsernameForLogs = username
        val runMode = config.run_mode.trim().lowercase()
        val mutualRequested = PrefsStore.isMutualClickRequested()
        val mutualPending = PrefsStore.isMutualClickPending()
        devOp(alias, "进入 executeForUsername：username=$username runMode=$runMode mutualRequested=$mutualRequested mutualPending=$mutualPending hardStop=${PrefsStore.isHardStopRequested()}")
        if (shouldSwitchToMutualClick()) {
            val commentOnlyForMessage = PrefsStore.isMutualCommentOnlySession()
            val followOnlyForMessage = PrefsStore.isMutualFollowOnlySession()
            val modeName = when (runMode) {
                ServerRunMode.DIRECT -> "直客访问模式"
                ServerRunMode.LIVE -> "主播访问模式"
                ServerRunMode.MUTUAL -> "互点模式"
                else -> "涨粉模式"
            }
            devOp(alias, "$modeName：收到互点切换请求，准备重启 TikTok 并进入首页放大镜")
            lastEndedAtUsersSelection = false
            PrefsStore.setMutualClickPending(false)
            PrefsStore.setMutualClickRequested(false)
            return enterMutualClickMode(service, config, commentOnlyForMessage, followOnlyForMessage)
        }
        if ((runMode == ServerRunMode.GROWTH || runMode == ServerRunMode.MUTUAL) && restartFromMagnifierForGrowth) {
            devOp(alias, "涨粉：上一名 Users 恢复失败，本用户从放大镜重新搜索（未强关 TikTok，不复用 Users）")
            restartFromMagnifierForGrowth = false
            delay(500)
            if (!clickMagnifier(service)) {
                return "TikTok 恢复拉起失败，无法点击放大镜，无法执行 $username"
            }
        } else if (lastEndedAtUsersSelection) {
            devOp(alias, "复用 Users 选择界面，跳过重启 TikTok")
            if (!ensureTikTokInForeground(ctx, alias, "复用 Users")) {
                return "TikTok 恢复拉起失败，无法复用 Users 界面，无法执行 $username"
            }
            focusSearchInput(service)
            clearSearchInputText()
            delay(Random.nextLong(400, 700))
        } else if (PrefsStore.consumeGrowthContinueFromPublishHome()) {
            devOp(alias, "涨粉：发作品刚收尾于 Home，跳过重启与 For You 预热，直接点放大镜")
            if (!isPackageVisible(Config.TIKTOK_PACKAGE_NAME)) {
                if (!ensureTikTokInForeground(ctx, alias, "发作品转涨粉")) {
                    return "TikTok 恢复拉起失败，无法执行 $username"
                }
            }
            delay(300)
            if (!clickMagnifier(service)) {
                return "无法点击放大镜，无法执行 $username"
            }
        } else {
            if (growthYieldOrNull(alias, "打开 TikTok")) {
                forceStopTikTokIfExclusivePreempt(alias, "打开 TikTok")
                return growthYieldSkipMessage(username)
            }
            if (!ensureTikTokInForeground(ctx, alias, "打开 TikTok")) {
                return "TikTok 恢复拉起失败，无法执行 $username"
            }
            delay(500)
            runForYouWarmupIfNeeded(service, alias, config)
            if (!clickMagnifier(service)) {
                return "TikTok 恢复拉起失败，无法点击放大镜，无法执行 $username"
            }
        }

        if (!ensureTikTokInForeground(ctx, alias, "输入搜索词前")) {
            return "TikTok 恢复拉起失败，无法输入搜索词，无法执行 $username"
        }
        val safeUsername = shellEscape(username)
        val inputResult = runSuCommand("input text $safeUsername")
        if (!inputResult) {
            devOp(alias, "输入搜索词失败")
            return "输入用户名失败，未执行 Search"
        }
        delay(Random.nextLong(800, 1500))

        // 4. 通过坐标点击 Search 按钮（root input tap）
        if (!clickSearchButtonAndWait(service)) {
            return "TikTok 恢复拉起失败，无法点击 Search，无法执行 $username"
        }

        // 5. 搜索结果页：点击 Users。未命中则先返回首页或强关，再重新搜索；仍失败则中止，不进入列表匹配。
        val modeForUsersCheck = config.run_mode.trim().lowercase()
        val isDirectCustomerRunMode = modeForUsersCheck == ServerRunMode.DIRECT
        val isLiveHostRunMode = modeForUsersCheck == ServerRunMode.LIVE
        val isLiveModeForUsersCheck = isDirectCustomerRunMode || isLiveHostRunMode
        if (!ensureUsersTabWithForceRestartRecovery(service, username, isLiveModeForUsersCheck)) {
            restartFromMagnifierForGrowth = true
            lastEndedAtUsersSelection = false
            return "失败：Users 标签 OCR 未命中，恢复后仍无法进入 Users 列表，已跳过 $username"
        }

        // 6. 在 Users 列表中点击目标用户（仅 OCR：TikTok 不暴露无障碍节点）
        val ocrPoint = findUserInListByOcr(service, username, isLiveModeForUsersCheck)
        if (ocrPoint == null) {
            devOp(alias, "Users 列表 OCR 未找到目标：$username")
            // 仍在搜索/Users 页且已清空搜索框，下一名应复用界面，避免误走 For You 预滑。
            lastEndedAtUsersSelection = true
            focusSearchInput(service)
            clearSearchInputText()
            return "失败：Users 列表 OCR 未找到目标用户 $username"
        }
        var tapped = runSuCommand("input tap ${ocrPoint.x} ${ocrPoint.y}")
        delay(400)
        if (!tapped) {
            tapped = runSuCommand("input tap ${ocrPoint.x} ${ocrPoint.y}")
        }
        delay(Random.nextLong(1200, 1800))
        val noVideosOrPrivate = checkNoVideosAndGoBack(service)
        // 已禁用 waitForRootNode 调试日志
        if (noVideosOrPrivate) {
            devOp(alias, "检测到无作品或私密账号，跳过当前用户")
            lastEndedAtUsersSelection = true
            return "失败：目标用户无作品或私密，已跳过 $username"
        }
        

        when (runMode) {
            ServerRunMode.DIRECT, ServerRunMode.LIVE -> {
                val isDirectMode = runMode == ServerRunMode.DIRECT
                val modeLabel = if (isDirectMode) "直客访问模式" else "主播访问模式"
                devOp(alias, "进入 $modeLabel：打开首条作品并互动")
                val liveDone = runLiveVisitModeForCurrentProfile(service, config, isDirectMode)
                return if (liveDone) {
                    lastEndedAtUsersSelection = false
                    "${modeLabel}完成：已访问 $username 主页，执行评论+互动"
                } else {
                    lastEndedAtUsersSelection = false
                    "失败：${modeLabel}跳过：$username 首条作品不可点击或未进入作品页，已切下一个用户名"
                }
            }
            ServerRunMode.GROWTH, ServerRunMode.MUTUAL -> {
                val label = if (runMode == ServerRunMode.MUTUAL) "互点模式" else "涨粉模式"
                devOp(alias, "$label：在用户主页，准备点击 Followers")
            }
            else -> {
                devOp(alias, "运行模式无效：${config.run_mode}，跳过")
                lastEndedAtUsersSelection = false
                return "失败：无效 run_mode=${config.run_mode}，已跳过（未点 Followers）"
            }
        }

        // 7. 用户主页：点击 Followers，帧差 + OCR 校验是否进入粉丝列表（growth 模式）
        val followersEntered = clickFollowersAndVerify(service)
        if (!followersEntered) {
            devOp(alias, "点击 Followers 后仍未进入粉丝列表")
            lastEndedAtUsersSelection = false
            return "已进入用户主页，但多次点击 Followers 后仍未进入粉丝列表"
        }
        delay(Random.nextLong(1000, 2000))
        try {
            processFollowersList(service, config)
        } catch (restart: GrowthFlowRestartRequired) {
            devOp(alias, restart.message ?: "growth 流程需要重启")
            // 已发生重启：growth 下一轮从放大镜重新开始，且不能复用 Users。
            restartFromMagnifierForGrowth = true
            lastEndedAtUsersSelection = false
            return "已强关重启 TikTok 并跳过当前用户名 $username"
        } catch (ex: Exception) {
            rethrowTaskLogoutIfNeeded(ex)
            throw ex
        }
        return "已拉取用户名 $username，已完成：搜索 → 点头像 → 进粉丝列表并处理用户"
        }
    }

    /** @return 是否已恢复前台并完成放大镜点击 */
    private suspend fun clickMagnifier(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        return withTikTokGuard(alias, "点击放大镜") {
            if (!ensureTikTokInForeground(service, alias, "点击放大镜")) return@withTikTokGuard false
            tryDismissDontAllowBeforeMagnifier(service, alias)
            val metrics = service.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val ratioX = 0.92f
            val ratioY = 0.09f
            val clickX = width * ratioX
            val clickY = height * ratioY
            runSuCommand("input tap ${clickX.toInt()} ${clickY.toInt()}")
            delay(Random.nextLong(800, 1200))
            true
        } == true
    }

    /**
     * 所有模式点击放大镜前统一尝试：
     * - OCR 定位 Don't allow 并点击（命中才点）
     * - 未命中直接继续后续流程
     */
    private suspend fun tryDismissDontAllowBeforeMagnifier(service: Context, alias: String) {
        val (vision, err) = mlKitRecognizeFullScreen(service, "magnifier_before_dont_allow")
        if (vision == null) {
            devOp(alias, "点击放大镜前：Don't allow OCR 失败：$err（忽略，继续）")
            return
        }
        val handled = runCatching {
            tapDontAllowFromVisionIfPresent(
                ctx = service,
                alias = alias,
                vision = vision,
                phaseLog = "点击放大镜前",
                useAutoLoginJitter = false,
                markAutoLoginHandled = false,
            )
        }.getOrDefault(false)
        if (!handled) {
            devOp(alias, "点击放大镜前：未命中 Don't allow，继续")
        }
    }

    /** 互点 Videos 标签点击 Y：与涨粉模式中 Users 标签同一级别，仍沿用顶部标签区 OCR 定位。 */
    private suspend fun findVideosTabAnchorByOcrFast(service: Context): Point? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "互点 OCR Videos 标签")) return null
        val screenshotFile = File(service.cacheDir, "mutual_videos_tab_ocr_fast.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return null
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return null
        }
        observeAccountStatusOnScreenshot(service, bitmap, "mutual_videos_tab_ocr_fast")
        val cropHeight = (bitmap.height * 0.32f).toInt().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropHeight)
        val image = InputImage.fromBitmap(cropped, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            fun isVideosWord(t: String): Boolean {
                val w = t.trim()
                return w.equals("Videos", ignoreCase = true) ||
                    w.equals("Video", ignoreCase = true) ||
                    w.equals("搜索视频", ignoreCase = true)
            }
            val candidates = result.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    line.elements.mapNotNull { element ->
                        if (isVideosWord(element.text)) element.boundingBox?.let { box ->
                            Point(box.centerX(), box.centerY())
                        } else null
                    }.ifEmpty {
                        val lineText = line.text
                        if (lineText.contains("Videos", ignoreCase = true)) {
                            line.boundingBox?.let { box -> listOf(Point(box.centerX(), box.centerY())) }
                                ?: emptyList()
                        } else emptyList()
                    }
                }
            }
            candidates.firstOrNull()
        } catch (ex: Exception) {
            rethrowTaskLogoutIfNeeded(ex)
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
            cropped.recycle()
            screenshotFile.delete()
        }
    }

    /** 顶栏 Videos 标签 OCR 定位，最多 [MUTUAL_VIDEOS_TAB_OCR_MAX_ATTEMPTS] 次，间隔 1.5s。 */
    private suspend fun findVideosTabAnchorByOcrWithRetries(
        service: Context,
        launchCheckTag: String,
        passLabel: String,
    ): Point? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val tagSuffix = if (passLabel.isBlank()) launchCheckTag else "$launchCheckTag·$passLabel"
        var videosAnchor: Point? = null
        for (ocrAttempt in 1..MUTUAL_VIDEOS_TAB_OCR_MAX_ATTEMPTS) {
            videosAnchor = findVideosTabAnchorByOcrFast(service)
            if (videosAnchor != null) return videosAnchor
            if (ocrAttempt < MUTUAL_VIDEOS_TAB_OCR_MAX_ATTEMPTS) {
                devOp(
                    alias,
                    "互点：未找到 Videos 标签（$tagSuffix）第 $ocrAttempt/${MUTUAL_VIDEOS_TAB_OCR_MAX_ATTEMPTS} 次，${1500}ms 后重试 OCR",
                )
                delay(1500)
            }
        }
        devOp(
            alias,
            "互点：${MUTUAL_VIDEOS_TAB_OCR_MAX_ATTEMPTS} 次 OCR 均未找到 Videos 标签（$tagSuffix）",
        )
        return null
    }

    private suspend fun enterVideosVideoByQuarterTaps(service: Context, config: AppConfig): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val mutualCommentOnly = PrefsStore.isMutualCommentOnlySession()
        val mutualFollowOnly = PrefsStore.isMutualFollowOnlySession()
        val trafficComment = config.mutual_traffic_comment
        val trafficAuthor = config.mutual_traffic_author_follow
        val trafficFollowers = config.mutual_traffic_followers_follow
        val videoRoundCap =
            config.mutual_videos_scroll_rounds.coerceAtLeast(1).coerceAtMost(MUTUAL_FULL_VIDEOS_SAFETY_ROUNDS)
        val followLimit = config.mutual_follow_tap_session_limit.coerceAtLeast(1)
        val widthPx = service.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val heightPx = service.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val yTap = (heightPx * 0.725f).toInt().coerceIn(1, heightPx - 1)
        val leftTap = Point((widthPx * 0.25f).toInt().coerceIn(1, widthPx - 1), yTap)
        val rightTap = Point((widthPx * 0.75f).toInt().coerceIn(1, widthPx - 1), yTap)
        val swipeStartY = (heightPx * 0.78f).toInt().coerceIn(1, heightPx - 1)
        val swipeEndY = (heightPx * 0.28f).toInt().coerceIn(1, heightPx - 1)
        val swipeX = (widthPx * 0.5f).toInt().coerceIn(1, widthPx - 1)
        val fixedTaps = listOf(leftTap, rightTap)
        val beforeTapForVerify = File(service.cacheDir, "mutual_videos_before_tap.png")
        devOp(
            alias,
            if (mutualFollowOnly) {
                "互点：进入 Videos（仅关注·独立方案）最多 $videoRoundCap 轮；Follow 达上限 $followLimit 即提前完成（无评论）；轮间下滑换批"
            } else if (mutualCommentOnly) {
                "互点：进入 Videos（互点评论·兼容旧指令）共 $videoRoundCap 轮（左+右各进 1 次作品为 1 轮），轮间下滑约半屏换批"
            } else {
                val actions = buildList {
                    if (trafficComment) add("评论")
                    if (trafficAuthor) add("作者区关注")
                    if (trafficFollowers) add("Followers关注")
                }.joinToString("/").ifBlank { "无引流动作" }
                val limitHint =
                    if (trafficComment) {
                        "Follow 上限 $followLimit（作者区+列表合计）达限后剩余轮次仅评论"
                    } else {
                        "Follow 上限 $followLimit 达限后立即结束"
                    }
                "互点：进入 Videos（引流）共 $videoRoundCap 轮；动作=$actions；$limitHint；轮间下滑换批"
            },
        )
        try {
            var roundIndex = 0
            mainVideosLoop@ while (true) {
                if (PrefsStore.isPublishPostQueuedAfterMutual()) {
                    devOp(
                        alias,
                        "互点：发作品需排队在互点模式结束之后执行（当前 teamwork Videos 第 ${roundIndex + 1}/$videoRoundCap 组）",
                    )
                }
                if (roundIndex >= videoRoundCap) {
                    devOp(
                        alias,
                        "互点：teamwork Videos 已达配置轮次 $videoRoundCap（${
                            when {
                                mutualFollowOnly -> "互点仅关注"
                                mutualCommentOnly -> "互点评论兼容"
                                else -> "完整互点"
                            }
                        }），结束",
                    )
                    break@mainVideosLoop
                }
                val roundCapLabel = "$videoRoundCap"
                for ((tapIndex, tap) in fixedTaps.withIndex()) {
                    coroutineContext.ensureActive()
                    if (hardStopOrNull(alias, "互点 Videos 互动")) break@mainVideosLoop
                    if (shouldStopMutualVideosAfterFollowLimit(config)) {
                        devOp(
                            alias,
                            if (mutualFollowOnly) {
                                "互点仅关注：Follow 已达上限 $followLimit，会话提前完成（不继续 Videos 轮次/列表下滑）"
                            } else {
                                "互点：Follow 已达上限 $followLimit 且未开启引流评论，结束 Videos 轮次"
                            },
                        )
                        break@mainVideosLoop
                    }
                    val sideLabel = if (tapIndex == 0) "左侧" else "右侧"
                    var verified = false
                    var recoveryCycles = 0
                    while (!verified) {
                        coroutineContext.ensureActive()
                        if (hardStopOrNull(alias, "互点 Videos 互动")) break@mainVideosLoop
                        repeat(3) { attemptIndex ->
                            if (verified) return@repeat
                            if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
                            val beforeCaptured = runSuCommand("screencap -p ${beforeTapForVerify.absolutePath}")
                            devOp(
                                alias,
                                "互点：Videos 第 ${roundIndex + 1}/$roundCapLabel 轮 $sideLabel 固定点 (${tap.x},${tap.y}) 第 ${attemptIndex + 1}/3 次验证",
                            )
                            runSuCommand("input tap ${tap.x} ${tap.y}")
                            delay(Random.nextLong(800, 1200))
                            verified = detectVideoMotionByFrameDiffFromBeforeTapMutual(
                                service,
                                beforeTapReference = if (beforeCaptured) beforeTapForVerify else null,
                                afterTapDelayMs = 0L,
                                tag = "互点-videos-${roundIndex + 1}-$sideLabel-${attemptIndex + 1}",
                            )
                            if (!verified) {
                                devOp(
                                    alias,
                                    "互点：Videos 第 ${roundIndex + 1}/$roundCapLabel 轮 $sideLabel 第 ${attemptIndex + 1}/3 次帧差未通过，等待 ${FRAME_DIFF_RETRY_DELAY_MS / 1000}s 后重试",
                                )
                                delay(FRAME_DIFF_RETRY_DELAY_MS)
                            }
                        }
                        if (verified) break
                        recoveryCycles++
                        if (recoveryCycles > MUTUAL_VIDEOS_FRAME_DIFF_MAX_RECOVERY_CYCLES) {
                            devOp(
                                alias,
                                "互点：Videos 第 ${roundIndex + 1}/$roundCapLabel 轮 $sideLabel 已连续 ${MUTUAL_VIDEOS_FRAME_DIFF_MAX_RECOVERY_CYCLES} 次 teamwork 恢复仍无法进作品，清零计数并再次关开 TikTok 重新搜索",
                            )
                            recoveryCycles = 0
                        }
                        devOp(
                            alias,
                            "互点：判定当前界面未知（${sideLabel} 连续 3 次帧差未通过），关闭 TikTok 并重新搜索关键词",
                        )
                        didRestartTikTokInCurrentRun = true
                        reopenMutualTeamworkVideosTabWithRetries(service, config, "互点 teamwork 恢复")
                    }
                    val unitTag = if (mutualFollowOnly || mutualCommentOnly) {
                        "Videos ${roundIndex + 1}/$videoRoundCap $sideLabel"
                    } else if (mutualFollowLimitReached) {
                        "Videos ${roundIndex + 1}/$videoRoundCap $sideLabel·Follow已达上限仅评论"
                    } else {
                        "Videos 第${roundIndex + 1}轮 $sideLabel"
                    }
                    val skipLeftSwipeAuthor = shouldSkipMutualAuthorLayerSwipe(config, mutualCommentOnly)
                    if (skipLeftSwipeAuthor) {
                        val reason = when {
                            mutualFollowOnly -> "互点仅关注（独立方案）"
                            mutualCommentOnly -> "互点评论会话（兼容旧指令）"
                            mutualFollowLimitReached -> "Follow 已达上限，仅保留作品内互动"
                            else -> "未开启作者区/Followers 关注"
                        }
                        devOp(alias, "互点：$unitTag $reason，跳过左滑与作者主页，留在当前作品继续互动")
                    } else {
                        val stopAfterUnit =
                            mutualVideosLeftSwipeThenDoubleBackToPost(service, config, unitTag)
                        if (stopAfterUnit) {
                            devOp(alias, "互点：$unitTag 作者/Followers 链路后 Follow 已满且未开评论，结束 Videos 轮次")
                            break@mainVideosLoop
                        }
                    }
                    val postStayMs = randomDelayMsFromSecondsRange(config.video_stay_min, config.video_stay_max)
                    devOp(alias, "互点：$unitTag 回到作品后停留 ${postStayMs}ms")
                    delay(postStayMs)
                    if (mutualFollowOnly) {
                        coroutineContext.ensureActive()
                        if (!hardStopOrNull(alias, "互点 Videos 仅关注打开评论区前")) {
                            openMutualCommentPanelOnly(service)
                            val commentPanelClosed =
                                performMutualFollowOnlyCommentAreaSteps(service, config)
                            if (!commentPanelClosed) {
                                closeMutualCommentPanelAfterFollowOnly(service)
                            }
                        }
                        if (mutualFollowLimitReached) {
                            devOp(
                                alias,
                                "互点仅关注：本作品评论区 Follow 已满，结束 Videos 会话",
                            )
                            break@mainVideosLoop
                        }
                    } else {
                        val actionMode = if (config.like_prob >= config.favorite_prob) "like" else "favorite"
                        if (actionMode == "like") {
                            performLike(service)
                        } else {
                            performFavorite(service, useOcrAnchor = false)
                        }
                        val actionGapMin = config.action_delay_min.coerceAtLeast(0.0)
                        val actionGapMax = config.action_delay_max.coerceAtLeast(actionGapMin)
                        coroutineContext.ensureActive()
                        if (!hardStopOrNull(alias, "互点 Videos 发表评论前")) {
                            if (!trafficComment) {
                                devOp(alias, "互点：Videos 跳过评论（模板未开启 mutual_traffic_comment）")
                            } else {
                                val commentAvail = config.mutual_traffic_comment_daily_limit > 0 &&
                                    PrefsStore.getDailyActionCount("mutual_traffic_comment") < config.mutual_traffic_comment_daily_limit
                                if (commentAvail) {
                                    delay(randomDelayMsFromSecondsRange(actionGapMin, actionGapMax))
                                    if (performMutualCommentTextDirectFixedCoordinate(service, config)) {
                                        PrefsStore.incrementDailyActionCount("mutual_traffic_comment")
                                    }
                                } else {
                                    devOp(alias, "互点：Videos 跳过评论（已达引流评论日上限或 mutual_traffic_comment_daily_limit 为 0）")
                                }
                            }
                        }
                    }
                    delay(Random.nextLong(700, 1100))
                    navBack(alias, "互点 $unitTag 互动完成返回 teamwork Videos 列表")
                    delay(Random.nextLong(800, 1200))
                }
                roundIndex++
                if (shouldStopMutualVideosAfterFollowLimit(config)) {
                    break@mainVideosLoop
                }
                val sessionDone = roundIndex >= videoRoundCap
                if (sessionDone) break@mainVideosLoop
                // 左右两侧已在各自发评后各执行过一次 navBack 回到 teamwork Videos 列表，此处再按一次会多退一层（用户观感为「发评后连按两次返回」）。
                delay(1000)
                val swipeMs = Random.nextInt(420, 621)
                devOp(alias, "互点：Videos 第${roundIndex}组双侧完成，列表上直接下滑换批（swipe $swipeX,$swipeStartY → $swipeX,$swipeEndY, ${swipeMs}ms）")
                runSuCommand("input swipe $swipeX $swipeStartY $swipeX $swipeEndY $swipeMs")
                delay(1000)
            }
            return true
        } finally {
            if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
        }
    }

    private suspend fun enterTeamworkVideoByQuarterTaps(service: Context, config: AppConfig): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val widthPx = service.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val heightPx = service.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val yTap = (heightPx * 0.725f).toInt().coerceIn(1, heightPx - 1)
        val baseCandidates = listOf(
            Point((widthPx * 0.25f).toInt().coerceIn(1, widthPx - 1), yTap),
            Point((widthPx * 0.75f).toInt().coerceIn(1, widthPx - 1), yTap),
        )
        val beforeTapForVerify = File(service.cacheDir, "mutual_click_post_enter_before_tap.png")
        try {
            for (round in 1..3) {
                val candidates = baseCandidates.shuffled()
                for ((idx, tap) in candidates.withIndex()) {
                    coroutineContext.ensureActive()
                    if (hardStopOrNull(alias, "互点点开 teamwork 视频")) return false
                    if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
                    val beforeCaptured = runSuCommand("screencap -p ${beforeTapForVerify.absolutePath}")
                    val attemptNo = (round - 1) * baseCandidates.size + idx + 1
                    val totalAttempts = 3 * baseCandidates.size
                    devOp(alias, "互点：搜索结果区点击 (${tap.x}, ${tap.y}) 尝试 ${attemptNo}/${totalAttempts}")
                    if (!runSuCommand("input tap ${tap.x} ${tap.y}")) continue
                    delay(config.growth_post_check_after_tap_delay_ms.coerceIn(100L, 5000L))
                    val entered = detectVideoMotionByFrameDiffFromBeforeTapMutual(
                        service,
                        beforeTapReference = if (beforeCaptured) beforeTapForVerify else null,
                        afterTapDelayMs = 0L,
                        tag = "互点-teamwork-${attemptNo}",
                    )
                    if (entered) {
                        devOp(alias, "互点：已进入作品页")
                        return true
                    }
                    delay(FRAME_DIFF_RETRY_DELAY_MS)
                }
            }
            devOp(alias, "互点：多轮重试后仍未进入作品页")
            return false
        } finally {
            if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
        }
    }

    /** 与主页 [clickFollowersRoot] 同属个人页统计行一带，取偏左下作为「Followers」区域参考点。 */
    private fun mutualClickFollowersLowerLeftReferencePx(service: Context): Point {
        val m = service.resources.displayMetrics
        val w = m.widthPixels.coerceAtLeast(1)
        val h = m.heightPixels.coerceAtLeast(1)
        val refX = (w * 0.40f).toInt().coerceIn(1, w - 1)
        val refY = (h * 0.355f).toInt().coerceIn(1, h - 1)
        return Point(refX, refY)
    }

    /** 相对参考点：向左 0.166×屏宽，向下 0.05×屏高。 */
    private fun mutualClickFollowersOffsetTapFromRefPx(service: Context, ref: Point): Point {
        val m = service.resources.displayMetrics
        val w = m.widthPixels.coerceAtLeast(1)
        val h = m.heightPixels.coerceAtLeast(1)
        val tapX = (ref.x - w * 0.166f).toInt().coerceIn(1, w - 1)
        val tapY = (ref.y + h * 0.05f).toInt().coerceIn(1, h - 1)
        return Point(tapX, tapY)
    }

    private fun normalizeOcrForMutualMessageLabel(raw: String): String {
        return raw.trim().lowercase()
            .replace('0', 'o')
            .replace('1', 'l')
            .replace('|', 'l')
            .replace(Regex("\\s+"), " ")
    }

    /**
     * 归一化后判断是否为「Message」控件文案（容错：整块里含 message 单词）。
     * [centerYFullScreen] 须落在屏高的上半部分才算（用户要求上半屏）。
     */
    private fun mutualClickIsMessageLabelNormalized(norm: String, centerYFullScreen: Int, screenHeight: Int): Boolean {
        if (centerYFullScreen > screenHeight / 2) return false
        if (norm == "message") return true
        if (norm.contains("message")) {
            val tokens = norm.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            return tokens.any { it == "message" }
        }
        return false
    }

    private suspend fun mutualClickUpperHalfHasMessageLabel(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "mutual_author_message_half.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return false
        val bitmapFull = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return false
        }
        observeAccountStatusOnScreenshot(service, bitmapFull, "mutual_author_message_half")
        val fullH = bitmapFull.height.coerceAtLeast(1)
        val halfH = (fullH * 0.5f).toInt().coerceAtLeast(1)
        val topHalf = Bitmap.createBitmap(bitmapFull, 0, 0, bitmapFull.width, halfH)
        val image = InputImage.fromBitmap(topHalf, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            observeAccountStatusOnOcrText(service, result, "mutual_author_message_half")
            result.textBlocks.asSequence()
                .flatMap { it.lines.asSequence() }
                .flatMap { it.elements.asSequence() }
                .any { el ->
                    val box = el.boundingBox ?: return@any false
                    val cy = box.centerY()
                    val n = normalizeOcrForMutualMessageLabel(el.text)
                    mutualClickIsMessageLabelNormalized(n, cy, fullH)
                }
        } catch (ex: Exception) {
            rethrowTaskLogoutIfNeeded(ex)
            devOp(alias, "互点：Message OCR 异常 ${ex.message}")
            false
        } finally {
            recognizer.close()
            bitmapFull.recycle()
            topHalf.recycle()
            screenshotFile.delete()
        }
    }

    private fun normalizeOcrButtonText(raw: String): String {
        return raw.trim().lowercase(Locale.US)
            .replace('0', 'o')
            .replace('1', 'l')
            .replace('|', 'l')
            .replace(Regex("\\s+"), " ")
    }

    /** Followers/Following 统计行（含 follower 变体）；Follow 按钮不带 ers / ing 后缀。 */
    private fun isMutualFollowersOrFollowingStatsCompact(compact: String): Boolean {
        if (compact.isBlank()) return false
        if (compact.contains("followers") || compact.contains("following")) return true
        if (compact.contains("follower")) return true
        if (compact.endsWith("ers") && compact.contains("follow")) return true
        return false
    }

    /**
     * 互点作者区/评论区主页：可点的 Follow 按钮文案判定。
     * @param lineNorm 同行整行 OCR（用于排除 Followers 被拆成 follow+ers 时 element 仅命中 follow 的误认）。
     */
    private fun isMutualFollowButtonTextNormalized(norm: String, lineNorm: String = norm): Boolean {
        if (norm.isBlank()) return false
        val lineCompact = lineNorm.replace(Regex("[^a-z]+"), "")
        val normCompact = norm.replace(Regex("[^a-z]+"), "")
        if (isMutualFollowersOrFollowingStatsCompact(lineCompact)) return false
        if (isMutualFollowersOrFollowingStatsCompact(normCompact)) return false
        val tokens = norm.split(Regex("[^a-z]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (tokens.any { it == "following" || it == "followers" || it == "follower" }) return false
        if (tokens.any { it.startsWith("follow") && it != "follow" }) return false
        if (tokens.any { it == "message" }) return false
        // 单行 OCR 常把 Follow 与 Message、简介连成一块；分词恰为 follow 仍视为按钮。
        if (tokens.any { it == "follow" }) return true
        return normCompact == "follow"
    }

    private fun isMutualFollowingLabelNormalized(norm: String): Boolean {
        if (norm.isBlank()) return false
        val tokens = norm.split(Regex("[^a-z]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.any { it == "followers" }) return false
        return tokens.any { it == "following" }
    }

    private fun isMutualMessageLabelNormalized(norm: String): Boolean {
        if (norm.isBlank()) return false
        val tokens = norm.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return tokens.any { it == "message" } || norm.contains("message")
    }

    private sealed class MutualFollowOnlyProfileFollowDecision {
        data class TapFollow(val point: Point, val layoutLabel: String) : MutualFollowOnlyProfileFollowDecision()
        data class Skip(val reason: String) : MutualFollowOnlyProfileFollowDecision()
        data object NoLayer : MutualFollowOnlyProfileFollowDecision()
    }

    private sealed class MutualFollowOnlyPostTapClassify {
        /** 已识别分栏/横条主页格局（含可点 Follow 或应 Skip） */
        data class ProfileLayout(val decision: MutualFollowOnlyProfileFollowDecision) :
            MutualFollowOnlyPostTapClassify()

        /** 仍在 Replying，未进主页 */
        data object Replying : MutualFollowOnlyPostTapClassify()

        /** 既无 Replying 也未认出主页格局 */
        data object Unclear : MutualFollowOnlyPostTapClassify()
    }

    private data class MutualFollowOnlyReplyAvatarTapResult(
        val abandonCoordinate: Boolean,
        val profileDecision: MutualFollowOnlyProfileFollowDecision? = null,
    )

    private fun mutualFollowOnlyOcrElementsFromResult(result: Text): List<OcrLabelHit> =
        result.textBlocks.asSequence()
            .flatMap { it.lines.asSequence() }
            .flatMap { line ->
                val lineNorm = normalizeOcrButtonText(line.text)
                line.elements.asSequence().mapNotNull { el ->
                    val box = el.boundingBox ?: return@mapNotNull null
                    val norm = normalizeOcrButtonText(el.text)
                    if (norm.isBlank()) return@mapNotNull null
                    OcrLabelHit(norm, box.centerX(), box.centerY(), lineNorm)
                }
            }
            .toList()

    private fun mutualFollowOnlyScreenShowsReplyingFromElements(elements: List<OcrLabelHit>): Boolean =
        elements.any { isMutualFollowOnlyReplyingOcrLabel(it.norm) }

    /**
     * 从整屏 OCR 元素判断格局B（分栏/上半 Follow）或格局A（横条作者区）。
     * 格局B 含：下半 Message + 上半 Follow；或上半仅有 Follow、下半无 Message/其他按钮。
     */
    private fun mutualFollowOnlyProfileDecisionFromElements(
        elements: List<OcrLabelHit>,
        fullW: Int,
        fullH: Int,
        cropTop: Int,
        cropBottom: Int,
    ): MutualFollowOnlyProfileFollowDecision {
        val upperBound = (fullH * 0.52f).toInt()
        val lowerBound = (fullH * 0.48f).toInt()
        val upper = elements.filter { it.centerY <= upperBound }
        val lower = elements.filter { it.centerY >= lowerBound }

        val lowerMessages = lower.filter { isMutualMessageLabelNormalized(it.norm) }
        val lowerFollows = lower.filter { isMutualFollowButtonTextNormalized(it.norm, it.lineNorm) }
        val upperFollows = upper.filter { isMutualFollowButtonTextNormalized(it.norm, it.lineNorm) }
        val upperFollowing = upper.filter {
            isMutualFollowingLabelNormalized(it.norm) &&
                !isMutualFollowButtonTextNormalized(it.norm, it.lineNorm)
        }

        // 格局B（分栏式）：下半有 Message + 上半有 Follow/Following；或上半仅有 Follow、下半无 Message/其他按钮
        val splitLayoutClassic =
            lowerMessages.isNotEmpty() && (upperFollows.isNotEmpty() || upperFollowing.isNotEmpty())
        val splitLayoutUpperFollowOnly =
            upperFollows.isNotEmpty() &&
                lowerMessages.isEmpty() &&
                lowerFollows.isEmpty()
        if (splitLayoutClassic || splitLayoutUpperFollowOnly) {
            if (upperFollowing.isNotEmpty() && upperFollows.isEmpty()) {
                return MutualFollowOnlyProfileFollowDecision.Skip("格局B已为 Following")
            }
            val followHit = upperFollows.maxByOrNull { it.centerX } ?: upperFollows.firstOrNull()
            if (followHit != null) {
                return MutualFollowOnlyProfileFollowDecision.TapFollow(
                    Point(
                        followHit.centerX.coerceIn(1, fullW - 1),
                        followHit.centerY.coerceIn(1, fullH - 1),
                    ),
                    if (splitLayoutUpperFollowOnly) "格局B上半屏仅Follow" else "格局B上半屏",
                )
            }
            return MutualFollowOnlyProfileFollowDecision.Skip("格局B上半屏无 Follow")
        }

        val inAuthorCrop = elements.filter { it.centerY in cropTop..cropBottom }
        val messageInCrop = inAuthorCrop.filter { isMutualMessageLabelNormalized(it.norm) }
        val followInCrop = inAuthorCrop.filter { isMutualFollowButtonTextNormalized(it.norm, it.lineNorm) }
        val rowYTol = (fullH * 0.015f).toInt().coerceIn(14, 56)
        val halfW = (fullW * 0.5f).toInt()
        fun sameRow(y: Int, base: Int) = kotlin.math.abs(y - base) <= rowYTol

        val messageHit = messageInCrop.firstOrNull()
        val messageY = messageHit?.centerY
        val inlineFollow = if (messageY != null) {
            val my = messageY
            val mx = messageHit?.centerX
            followInCrop.firstOrNull { sameRow(it.centerY, my) && it.centerX >= halfW }
                ?: if (mx != null) {
                    followInCrop.firstOrNull { sameRow(it.centerY, my) && it.centerX > mx }
                } else {
                    null
                }
                ?: followInCrop.firstOrNull { sameRow(it.centerY, my) }
                ?: followInCrop.firstOrNull()
        } else {
            followInCrop.firstOrNull()
        }
        if (inlineFollow != null) {
            return MutualFollowOnlyProfileFollowDecision.TapFollow(
                Point(
                    inlineFollow.centerX.coerceIn(1, fullW - 1),
                    inlineFollow.centerY.coerceIn(1, fullH - 1),
                ),
                "格局A横条作者区",
            )
        }
        if (messageInCrop.isNotEmpty()) {
            return MutualFollowOnlyProfileFollowDecision.Skip("格局A已为 Message")
        }
        return MutualFollowOnlyProfileFollowDecision.NoLayer
    }

    private suspend fun mutualFollowOnlyClassifyPostTapScreen(service: Context): MutualFollowOnlyPostTapClassify? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "mutual_follow_only_post_tap_ocr.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return null
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return null
        }
        observeAccountStatusOnScreenshot(service, bitmap, "mutual_follow_only_post_tap_ocr")
        val fullW = bitmap.width.coerceAtLeast(1)
        val fullH = bitmap.height.coerceAtLeast(1)
        val followersRef = mutualClickFollowersLowerLeftReferencePx(service)
        val cropTop = (followersRef.y + fullH * 0.05f).toInt().coerceIn(0, fullH - 1)
        val cropBottom = (fullH * 0.5f).toInt().coerceIn(cropTop + 1, fullH)
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val elements = mutualFollowOnlyOcrElementsFromResult(result)
            val replying = mutualFollowOnlyScreenShowsReplyingFromElements(elements) ||
                result.textBlocks.asSequence()
                    .flatMap { it.lines.asSequence() }
                    .any { line ->
                        isMutualFollowOnlyReplyingOcrLabel(normalizeOcrButtonText(line.text))
                    }
            val profile = mutualFollowOnlyProfileDecisionFromElements(
                elements,
                fullW,
                fullH,
                cropTop,
                cropBottom,
            )
            when {
                profile !is MutualFollowOnlyProfileFollowDecision.NoLayer ->
                    MutualFollowOnlyPostTapClassify.ProfileLayout(profile)
                replying -> MutualFollowOnlyPostTapClassify.Replying
                else -> MutualFollowOnlyPostTapClassify.Unclear
            }
        } catch (ex: Exception) {
            devOp(alias, "互点仅关注：点击后 OCR 分类异常 ${ex.message}")
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
            screenshotFile.delete()
        }
    }

    /**
     * 同一次截屏：优先认主页格局，其次 Replying；最多 6 次、间隔 0.5s。
     * @return 任一轮认出 ProfileLayout 即返回；连续 Replying 返回 Replying；否则 Unclear。
     */
    private suspend fun mutualFollowOnlyClassifyPostTapScreenWithRetries(
        service: Context,
        logTag: String,
    ): MutualFollowOnlyPostTapClassify {
        val alias = PrefsStore.getAlias() ?: "unknown"
        var sawReplying = false
        for (attempt in 1..MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS) {
            if (hardStopOrNull(alias, "互点仅关注 $logTag 点击后 OCR")) {
                return MutualFollowOnlyPostTapClassify.Unclear
            }
            when (val one = mutualFollowOnlyClassifyPostTapScreen(service)) {
                null -> devOp(alias, "互点仅关注：$logTag 点击后 OCR 第 $attempt 次截屏失败")
                is MutualFollowOnlyPostTapClassify.ProfileLayout -> {
                    if (attempt > 1) {
                        devOp(
                            alias,
                            "互点仅关注：$logTag 第 $attempt 次 OCR 提前认定主页格局（${
                                when (val d = one.decision) {
                                    is MutualFollowOnlyProfileFollowDecision.TapFollow -> d.layoutLabel
                                    is MutualFollowOnlyProfileFollowDecision.Skip -> d.reason
                                    is MutualFollowOnlyProfileFollowDecision.NoLayer -> "?"
                                }
                            }）",
                        )
                    }
                    return one
                }
                is MutualFollowOnlyPostTapClassify.Replying -> {
                    sawReplying = true
                    devOp(
                        alias,
                        "互点仅关注：$logTag 点击后 OCR 第 $attempt/$MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS 次仍为 Replying",
                    )
                }
                is MutualFollowOnlyPostTapClassify.Unclear -> {
                    devOp(
                        alias,
                        "互点仅关注：$logTag 点击后 OCR 第 $attempt/$MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS 次未见格局/Replying",
                    )
                }
            }
            if (attempt < MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS) {
                delay(MUTUAL_FOLLOW_ONLY_OCR_RETRY_DELAY_MS)
            }
        }
        return if (sawReplying) {
            MutualFollowOnlyPostTapClassify.Replying
        } else {
            MutualFollowOnlyPostTapClassify.Unclear
        }
    }

    /** Follow 布局 OCR：最多 6 次、间隔 0.5s（兜底，点击后分类未认出格局时）。 */
    private suspend fun resolveMutualFollowOnlyProfileFollowWithRetries(
        service: Context,
        itemTag: String,
    ): MutualFollowOnlyProfileFollowDecision {
        val alias = PrefsStore.getAlias() ?: "unknown"
        var lastSkip: MutualFollowOnlyProfileFollowDecision.Skip? = null
        for (attempt in 1..MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS) {
            if (hardStopOrNull(alias, "互点仅关注 $itemTag Follow OCR")) {
                return lastSkip ?: MutualFollowOnlyProfileFollowDecision.NoLayer
            }
            val decision = when (val c = mutualFollowOnlyClassifyPostTapScreen(service)) {
                is MutualFollowOnlyPostTapClassify.ProfileLayout -> c.decision
                is MutualFollowOnlyPostTapClassify.Replying,
                is MutualFollowOnlyPostTapClassify.Unclear,
                null,
                -> MutualFollowOnlyProfileFollowDecision.NoLayer
            }
            when (decision) {
                is MutualFollowOnlyProfileFollowDecision.TapFollow -> {
                    if (attempt > 1) {
                        devOp(
                            alias,
                            "互点仅关注：$itemTag Follow 布局 OCR 第 $attempt 次命中可点 Follow",
                        )
                    }
                    return decision
                }
                is MutualFollowOnlyProfileFollowDecision.Skip -> {
                    lastSkip = decision
                    devOp(
                        alias,
                        "互点仅关注：$itemTag Follow 布局 OCR 第 $attempt/$MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS 次：${decision.reason}",
                    )
                }
                is MutualFollowOnlyProfileFollowDecision.NoLayer -> {
                    devOp(
                        alias,
                        "互点仅关注：$itemTag Follow 布局 OCR 第 $attempt/$MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS 次：未见 Follow/Message",
                    )
                }
            }
            if (attempt < MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS) {
                delay(MUTUAL_FOLLOW_ONLY_OCR_RETRY_DELAY_MS)
            }
        }
        if (lastSkip != null) {
            devOp(
                alias,
                "互点仅关注：$itemTag Follow 布局 OCR ${MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS} 次均为跳过：${lastSkip.reason}",
            )
            return lastSkip
        }
        devOp(
            alias,
            "互点仅关注：$itemTag Follow 布局 OCR ${MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS} 次均未识别布局",
        )
        return MutualFollowOnlyProfileFollowDecision.NoLayer
    }

    private data class OcrLabelHit(
        val norm: String,
        val centerX: Int,
        val centerY: Int,
        val lineNorm: String = norm,
    )

    private data class MutualFollowTarget(val point: Point, val messageY: Int)

    private data class MutualAuthorOcrElement(
        val norm: String,
        val lineNorm: String,
        val centerX: Int,
        val centerY: Int,
    )

    private suspend fun mutualClickUpperHalfOcrFollowTarget(service: Context): MutualFollowTarget? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        for (attempt in 1..MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS) {
            if (hardStopOrNull(alias, "互点作者区 Follow OCR")) return null
            val target = mutualClickUpperHalfOcrFollowTargetOnce(service)
            if (target != null) {
                if (attempt > 1) {
                    devOp(alias, "互点：作者区 Follow OCR 第 $attempt 次命中")
                }
                return target
            }
            if (attempt < MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS) {
                devOp(
                    alias,
                    "互点：作者区 Follow OCR 第 $attempt/$MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS 次未命中，${MUTUAL_FOLLOW_ONLY_OCR_RETRY_DELAY_MS}ms 后重试",
                )
                delay(MUTUAL_FOLLOW_ONLY_OCR_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private suspend fun mutualClickUpperHalfOcrFollowTargetOnce(service: Context): MutualFollowTarget? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "mutual_author_red_half.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return null
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return null
        }
        observeAccountStatusOnScreenshot(service, bitmap, "mutual_author_red_half")
        val fullW = bitmap.width.coerceAtLeast(1)
        val fullH = bitmap.height.coerceAtLeast(1)
        val followersRef = mutualClickFollowersLowerLeftReferencePx(service)
        val cropTop = (followersRef.y + fullH * 0.05f).toInt().coerceIn(0, fullH - 1)
        val cropBottom = (fullH * 0.5f).toInt().coerceIn(cropTop + 1, fullH)
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)
        val region = Bitmap.createBitmap(bitmap, 0, cropTop, fullW, cropHeight)
        val image = InputImage.fromBitmap(region, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }

            val allElements = result.textBlocks.asSequence()
                .flatMap { it.lines.asSequence() }
                .flatMap { line ->
                    val lineNorm = normalizeOcrButtonText(line.text)
                    line.elements.asSequence().mapNotNull { el ->
                        val box = el.boundingBox ?: return@mapNotNull null
                        val norm = normalizeOcrButtonText(el.text)
                        val centerX = box.centerX()
                        val centerY = cropTop + box.centerY()
                        MutualAuthorOcrElement(norm, lineNorm, centerX, centerY)
                    }
                }
                .toList()

            val messageHit = allElements.firstOrNull { el ->
                el.norm.contains("message") && el.centerY in cropTop..cropBottom
            }
            val messageY = messageHit?.centerY
            val followHits = allElements.filter { el ->
                el.centerY in cropTop..cropBottom &&
                    isMutualFollowButtonTextNormalized(el.norm, el.lineNorm)
            }

            val rowYTol = (fullH * 0.015f).toInt().coerceIn(14, 56)
            val halfW = (fullW * 0.5f).toInt()
            fun sameRow(y: Int, base: Int) = kotlin.math.abs(y - base) <= rowYTol

            val target = if (messageY != null) {
                val my = messageY
                val mx = messageHit?.centerX
                followHits.firstOrNull { el ->
                    sameRow(el.centerY, my) && el.centerX >= halfW
                } ?: if (mx != null) {
                    followHits.firstOrNull { el -> sameRow(el.centerY, my) && el.centerX > mx }
                } else {
                    null
                } ?: followHits.firstOrNull { el -> sameRow(el.centerY, my) }
                    ?: followHits.firstOrNull()
            } else {
                followHits.firstOrNull()
            }

            val preview = allElements.map { it.norm }.filter { it.isNotEmpty() }.take(12).joinToString(" | ")
            val messagePoint = messageHit?.let { Point(it.centerX, it.centerY) }
            val followPoint = target?.let { Point(it.centerX, it.centerY) }
            devOp(
                alias,
                "互点：OCR按钮区 crop=(${cropTop}-${cropBottom}) message=${messagePoint?.let { "(${it.x},${it.y})" } ?: "<none>"} follow=${followPoint?.let { "(${it.x},${it.y})" } ?: "<none>"} preview=$preview",
            )
            target?.let { MutualFollowTarget(Point(it.centerX, it.centerY), messageY ?: it.centerY) }
        } catch (ex: Exception) {
            devOp(alias, "互点：作者区 Follow OCR 异常 ${ex.message}")
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
            region.recycle()
            screenshotFile.delete()
        }
    }

    /** Followers 行中心与 [clickFollowersRoot] 相同基准（无 jitter）；向上 0.05×屏高为复制用户名触点。 */
    private fun mutualClickCopyUsernameTapFromFollowersRowPx(service: Context): Point {
        val m = service.resources.displayMetrics
        val w = m.widthPixels.coerceAtLeast(1)
        val h = m.heightPixels.coerceAtLeast(1)
        val baseX = (w * 0.505f).toInt().coerceIn(1, w - 1)
        val baseY = (h * 0.341f).toInt().coerceIn(1, h - 1)
        val tapY = (baseY - h * 0.05f).toInt().coerceIn(1, h - 1)
        return Point(baseX, tapY)
    }

    private fun sanitizeMutualCopiedUsernameFromClipboard(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var token = raw.lineSequence().firstOrNull()?.trim() ?: return null
        token = token.split(Regex("\\s+")).firstOrNull()?.trim()?.removePrefix("@")?.trim().orEmpty()
        val sb = StringBuilder(token.length.coerceAtMost(96))
        for (ch in token.lowercase(Locale.US)) {
            if (ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_') {
                sb.append(ch)
            }
        }
        val out = sb.toString().trim()
        if (out.length < 2) return null
        return out.take(64)
    }

    private fun isLikelyAddCommentPlaceholder(raw: String): Boolean {
        val t = raw.trim().lowercase(Locale.US)
        if (t.isEmpty()) return true
        val hints = listOf(
            "add comment",
            "add comment...",
            "add comment…",
            "say something",
            "说点什么",
            "说说你的看法",
        )
        return hints.any { t == it || t.startsWith(it) }
    }

    /** 从 uiautomator dump 的 XML 中取非空的 EditText 文案，优先排除占位提示。 */
    private fun parseLargestEditTextFromUiAutomatorXml(xml: String): String? {
        val candidates = mutableListOf<String>()
        Regex("""<node([^>]{0,8000})>""").findAll(xml).forEach { m ->
            val attrs = m.groupValues[1]
            if (!attrs.contains("EditText")) return@forEach
            val tv = Regex("""\btext="([^"]*)""").find(attrs)?.groupValues?.get(1) ?: return@forEach
            if (tv.isNotBlank()) candidates.add(tv)
        }
        val meaningful = candidates.filterNot { isLikelyAddCommentPlaceholder(it) }
        return meaningful.maxByOrNull { it.length }
            ?: candidates.maxByOrNull { it.length }?.takeIf { !isLikelyAddCommentPlaceholder(it) }
    }

    private fun runUiAutomatorDumpWindowXml(alias: String): String? {
        val path = "/data/local/tmp/autotraces_ua_window.xml"
        runSuCommandWithOutputMerged("rm -f $path 2>/dev/null")
        val log = runSuCommandWithOutputMerged("uiautomator dump $path 2>&1")
            ?: runSuCommandWithOutputMerged("/system/bin/uiautomator dump $path 2>&1")
        devOp(alias, "uiautomator dump: ${log?.take(200)}")
        val xml = runSuCommandWithOutputMerged("cat $path 2>&1")?.trim()
        if (xml.isNullOrBlank() || !xml.startsWith("<?xml")) {
            return null
        }
        return xml
    }

    /**
     * 假定评论输入框已聚焦：发送 KEYCODE_PASTE(279) 把系统剪贴板写入框内，再 uiautomator dump 读 EditText 的 text。
     * 不依赖 [ClipboardManager]，适合 Android 14 后台读剪贴板失败但 Gboard 已展示剪贴内容的场景。
     */
    private suspend fun tryPasteClipboardIntoCommentThenReadViaUiDump(alias: String): String? {
        if (hardStopOrNull(alias, "评论框粘贴")) return null
        runSuCommand("input keyevent 279")
        delay(Random.nextLong(480, 750))
        val xml = runUiAutomatorDumpWindowXml(alias) ?: return null
        val text = parseLargestEditTextFromUiAutomatorXml(xml)
        if (text.isNullOrBlank()) {
            devOp(alias, "互点：uiautomator 未解析到有效 EditText 文本")
            return null
        }
        devOp(alias, "互点：评论框经 uiautomator 读取 len=${text.length} preview=${text.take(56)}")
        return text
    }

    private suspend fun clearCommentInputViaRoot(alias: String) {
        devOp(alias, "互点：清空评论输入框（发模板前）")
        runSuCommand("input keyevent 123")
        delay(80)
        if (runSuCommand("input keycombination 113 29")) {
            delay(40)
            runSuCommand("input keyevent 67")
            delay(80)
            return
        }
        repeat(48) {
            runSuCommand("input keyevent 67")
            delay(12)
        }
        delay(80)
    }

    /**
     * 互点：进入粉丝列表后，用与涨粉相同的整屏 OCR（严格等于 Follow）定位按钮，
     * 点击 **Follow 框中心**（不涨粉式左偏），再按涨粉列表同款距离与手势下滑，最多 7 轮或到底。
     */
    private suspend fun mutualProcessFollowersListFollowTapsAndGrowthSwipe(service: Context, config: AppConfig) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val limit = config.mutual_follow_tap_session_limit.coerceAtLeast(1)
        var reachedEnd = false
        var rounds = 0
        while (!reachedEnd && rounds < 7) {
            if (mutualClickOrNull(alias, "互点粉丝列表 Follow+下滑")) return
            if (mutualFollowLimitReached) return
            val followers = try {
                findFollowersEntriesByOcr(
                    service,
                    verificationOnly = false,
                    tapKind = FollowersListOcrTapKind.FOLLOW_BUTTON_CENTER,
                )
            } catch (_: GrowthFlowRestartRequired) {
                devOp(alias, "互点：粉丝列表 OCR 触发恢复重启逻辑，结束本段 Followers 列表处理")
                return
            }
            if (followers.isEmpty()) {
                devOp(alias, "互点：粉丝列表（涨粉同款 OCR）无可用 Follow 行，结束列表滑动")
                return
            }
            devOp(alias, "互点：粉丝列表 OCR 命中 ${followers.size} 条 Follow（中心点击）")
            for (entry in followers) {
                if (mutualClickOrNull(alias, "互点粉丝列表单条 Follow")) return
                if (mutualFollowLimitReached) return
                runSuCommand("input tap ${entry.clickPoint.x} ${entry.clickPoint.y}")
                if (recordMutualFollowTap(alias, config, "Followers列表")) {
                    devOp(alias, "互点：已达 Follow 会话上限 $limit，结束列表滑动")
                    return
                }
                delay(Random.nextLong(1000, 1501))
            }
            val avgRow = followers.map { it.rowHeight }.average().toFloat().coerceAtLeast(1f)
            val scrollDistance = (avgRow * 7f).coerceAtLeast(240f)
            val metrics = service.resources.displayMetrics
            val startX = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.8f
            val endY = (startY - scrollDistance).coerceAtLeast(metrics.heightPixels * 0.2f)
            LogCenter.append(alias, "互点：粉丝列表下滑")
            runSuCommand("input swipe ${startX.toInt()} ${startY.toInt()} ${startX.toInt()} ${endY.toInt()} 450")
            delay(Random.nextLong(900, 1200))
            reachedEnd = detectFollowersListEnd(service)
            LogCenter.append(alias, "互点：粉丝列表下滑后到底判定=$reachedEnd")
            rounds++
        }
        devOp(alias, "互点：粉丝列表 Follow+下滑结束 rounds=$rounds reachedEnd=$reachedEnd")
    }

    /** @return 是否已进入并处理 Followers 列表（用于决定返回次数：进了 2 次，未进 1 次）。 */
    private suspend fun mutualClickCopyUsernameReportAndOpenFollowers(service: Context, config: AppConfig): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (mutualFollowLimitReached) {
            devOp(alias, "互点：已达 Follow 会话上限，不再执行复制用户名/进入 Followers 链路")
            return false
        }
        if (hardStopOrNull(alias, "互点复制用户名到系统剪贴板")) return false
        val copyPt = mutualClickCopyUsernameTapFromFollowersRowPx(service)
        devOp(alias, "互点：点按复制用户名（${copyPt.x},${copyPt.y}）（写入系统剪贴板；不在此读剪贴板，上报在 teamwork 发评时粘贴+uiautomator）")
        runSuCommand("input tap ${copyPt.x} ${copyPt.y}")
        delay(Random.nextLong(700, 1200))

        delay(Random.nextLong(280, 551))
        if (hardStopOrNull(alias, "互点点击 Followers 并校验列表")) return false
        val entered = mutualClickFollowersAndVerify(service)
        if (!entered) {
            devOp(alias, "互点：未进入粉丝列表（三轮 OCR 或强关恢复后），跳过列表内 Follow")
            return false
        }

        delay(Random.nextLong(800, 1400))
        if (hardStopOrNull(alias, "互点粉丝列表 Follow+下滑")) return true
        mutualProcessFollowersListFollowTapsAndGrowthSwipe(service, config)
        return true
    }

    /**
     * 左滑本身没有系统级「成功/失败」回调；这里用**截屏 + OCR 能否看到作者条典型控件**作为代理条件。
     *
     * **判定为「已进入作者层（或足够像）」当且仅当**以下任一成立：
     * 1. [mutualClickUpperHalfOcrFollowTarget] 非空（上半屏裁区内识别到可点的 Follow）；
     * 2. 否则若 [mutualClickUpperHalfHasMessageLabel] 为 true（上半屏有 Message，常见于已互关、无 Follow 行）。
     *
     * **判定为「本次尝试未达成可识别的作者层」**当且仅当上述两者皆为假——此时**不**等价于证明手势一定没发出
     * （也可能是 UI 未渲染完、裁切区不适配、OCR 漏字）；重试是在该代理条件下提高「再滑一次换参」的期望收益。
     */
    private suspend fun mutualProbeAuthorLayerAfterSwipe(service: Context): Pair<MutualFollowTarget?, Boolean> {
        val follow = mutualClickUpperHalfOcrFollowTarget(service)
        if (follow != null) return follow to true
        return null to mutualClickUpperHalfHasMessageLabel(service)
    }

    /**
     * 互点 Videos 单条作品单元（前半段）：在**已确认进入作品播放页**后执行左滑进作者层，
     * 再按是否进入 Followers 返回 1～2 次回到**同一作品**播放页，供后续点赞/收藏/评论使用。
     */
    /**
     * 左滑进作者层并按模板执行作者区 / Followers 关注，再回到作品播放页。
     * @return true 表示 Follow 已达上限且未开启引流评论，调用方应结束 Videos 轮次。
     */
    private suspend fun mutualVideosLeftSwipeThenDoubleBackToPost(
        service: Context,
        config: AppConfig,
        unitLogTag: String,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "互点 $unitLogTag 左滑")) return false
        delay(Random.nextLong(800, 1200))
        val m = service.resources.displayMetrics
        val w = m.widthPixels.coerceAtLeast(1)
        val h = m.heightPixels.coerceAtLeast(1)
        val xStart = (w * 0.92f).toInt().coerceIn(1, w - 1)
        val xEnd = (w * 0.08f).toInt().coerceIn(1, w - 1)
        val afterTapMs = config.growth_post_check_after_tap_delay_ms.coerceIn(100L, 5000L)
        val enableAuthorFollow = config.mutual_traffic_author_follow
        val enableFollowersFollow = config.mutual_traffic_followers_follow

        // 重试条件见 [mutualProbeAuthorLayerAfterSwipe]：仅当 Follow/Message OCR 均未命中时才换参再滑。
        data class MutualAuthorSwipeTry(
            val yFrac: Float,
            val swipeMsRange: IntRange,
            val settleDelayMs: LongRange,
        )
        val swipeVariants = listOf(
            MutualAuthorSwipeTry(0.55f, 520..720, 2000L..3200L),
            MutualAuthorSwipeTry(0.48f, 650..950, 1800L..2800L),
            MutualAuthorSwipeTry(0.62f, 750..1100, 1800L..2600L),
        )
        var ocrFollowTarget: MutualFollowTarget? = null
        var sawAuthorLayer = false
        for ((idx, variant) in swipeVariants.withIndex()) {
            if (hardStopOrNull(alias, "互点 $unitLogTag 左滑")) return false
            val yLine = (h * variant.yFrac).toInt().coerceIn(1, h - 1)
            val swipeMs = Random.nextInt(variant.swipeMsRange.first, variant.swipeMsRange.last + 1)
            devOp(
                alias,
                "互点：$unitLogTag 作品内左滑（尝试 ${idx + 1}/${swipeVariants.size}）swipe $xStart,$yLine → $xEnd,$yLine, ${swipeMs}ms",
            )
            runSuCommand("input swipe $xStart $yLine $xEnd $yLine $swipeMs")
            delay(afterTapMs)
            delay(Random.nextLong(variant.settleDelayMs.first, variant.settleDelayMs.last + 1))
            val probed = mutualProbeAuthorLayerAfterSwipe(service)
            ocrFollowTarget = probed.first
            sawAuthorLayer = probed.second
            if (sawAuthorLayer) break
            if (idx < swipeVariants.lastIndex) {
                devOp(alias, "互点：$unitLogTag 左滑尝试 ${idx + 1}/${swipeVariants.size} 后未见作者区 Follow/Message，换参重试")
                delay(Random.nextLong(350, 801))
            }
        }
        if (!sawAuthorLayer) {
            devOp(
                alias,
                "互点：$unitLogTag 左滑 ${swipeVariants.size} 次后仍未识别作者层（Follow/Message OCR），继续后续步骤（可能仍在纯播放页）",
            )
        }

        if (enableAuthorFollow && !mutualFollowLimitReached) {
            devOp(alias, "互点主页链路：步骤1/3 检查作者区 Follow（OCR）并点击")
            when {
                ocrFollowTarget != null -> {
                    val tap = ocrFollowTarget.point
                    devOp(
                        alias,
                        "互点主页链路：步骤1/3 作者区 Follow（OCR）通过，点击 (${tap.x},${tap.y}) messageY=${ocrFollowTarget.messageY}",
                    )
                    runSuCommand("input tap ${tap.x} ${tap.y}")
                    delay(Random.nextLong(1200, 1800))
                    recordMutualFollowTap(alias, config, "作者区")
                }
                sawAuthorLayer -> {
                    devOp(alias, "互点主页链路：步骤1/3 已识别作者层（Message），无 Follow OCR 行可点，跳过")
                }
                else -> {
                    devOp(alias, "互点主页链路：步骤1/3 作者区 Follow（OCR）未通过，跳过点击")
                }
            }
        } else {
            devOp(
                alias,
                "互点：$unitLogTag 跳过作者区 Follow（${if (!enableAuthorFollow) "未开启" else "已达上限"}）",
            )
        }

        var enteredFollowersList = false
        if (enableFollowersFollow && !mutualFollowLimitReached) {
            devOp(alias, "互点主页链路：步骤2/3 点击用户名复制并进入 Followers（上报在 teamwork 发评粘贴时）")
            enteredFollowersList = mutualClickCopyUsernameReportAndOpenFollowers(service, config)
        } else {
            devOp(
                alias,
                "互点：$unitLogTag 跳过 Followers 链路（${if (!enableFollowersFollow) "未开启" else "已达上限"}）",
            )
        }

        val backCount = if (enteredFollowersList) 2 else 1
        devOp(alias, "互点主页链路：步骤3/3 从作者层返回作品 ${backCount} 次（enteredFollowers=$enteredFollowersList）")
        repeat(backCount) { pass ->
            navBack(alias, "互点主页链路完成后返回作品 ${pass + 1}/$backCount")
            if (pass < backCount - 1) delay(Random.nextLong(700, 1100))
        }
        delay(Random.nextLong(700, 1100))
        devOp(alias, "互点主页链路：已回到作品播放页，准备点赞/评论")
        return shouldStopMutualVideosAfterFollowLimit(config)
    }

    /** 互点流程统一：进入作者主页后先完成主页链路，再回到 Videos 做本轮作品互动。 */
    private suspend fun mutualClickSwipeToAuthorThenMessageOrFollowersTap(
        service: Context,
        config: AppConfig,
    ) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "互点左滑进作者主页")) return
        delay(Random.nextLong(800, 1200))
        val m = service.resources.displayMetrics
        val w = m.widthPixels.coerceAtLeast(1)
        val h = m.heightPixels.coerceAtLeast(1)
        val yLine = (h * 0.55f).toInt().coerceIn(1, h - 1)
        val xStart = (w * 0.92f).toInt().coerceIn(1, w - 1)
        val xEnd = (w * 0.08f).toInt().coerceIn(1, w - 1)
        val swipeMs = Random.nextInt(520, 721)
        devOp(alias, "互点：左滑进入作者主页（swipe ${xStart},$yLine → ${xEnd},$yLine, ${swipeMs}ms）")
        runSuCommand("input swipe $xStart $yLine $xEnd $yLine $swipeMs")

        val afterTapMs = config.growth_post_check_after_tap_delay_ms.coerceIn(100L, 5000L)
        delay(afterTapMs)
        delay(Random.nextLong(2000L, 3201L))

        devOp(alias, "互点主页链路：步骤1/5 检查上半屏是否为 Message")
        if (hardStopOrNull(alias, "互点作者页 Message OCR")) return
        val hasMessage = mutualClickUpperHalfHasMessageLabel(service)
        if (hasMessage) {
            devOp(alias, "互点主页链路：步骤1/5 结果=Message")
        } else {
            devOp(alias, "互点主页链路：步骤1/5 结果=非 Message，继续检查作者区 Follow（OCR）")
            val ocrFollowTarget = mutualClickUpperHalfOcrFollowTarget(service)
            if (ocrFollowTarget != null) {
                val tap = ocrFollowTarget.point
                devOp(
                    alias,
                    "互点主页链路：步骤2/5 作者区 Follow（OCR）命中，点击 (${tap.x},${tap.y}) messageY=${ocrFollowTarget.messageY}",
                )
                runSuCommand("input tap ${tap.x} ${tap.y}")
                delay(Random.nextLong(1200, 1800))
            } else {
                devOp(alias, "互点主页链路：步骤2/5 作者区 Follow（OCR）未命中，跳过点击")
            }
        }
        devOp(alias, "互点主页链路：步骤3/5 点击用户名复制（上报仅在 teamwork 发评粘贴+uiautomator）")
        val enteredFollowersList = mutualClickCopyUsernameReportAndOpenFollowers(service, config)
        val backCount = if (enteredFollowersList) 2 else 1
        devOp(alias, "互点主页链路：步骤4/5 从作者层返回 ${backCount} 次（enteredFollowers=$enteredFollowersList）")
        repeat(backCount) { pass ->
            navBack(alias, "互点主页链路完成后返回 ${pass + 1}/$backCount")
            if (pass < backCount - 1) delay(Random.nextLong(700, 1100))
        }
        delay(Random.nextLong(700, 1100))
        devOp(alias, "互点主页链路：步骤5/5 已回到作品/列表，准备继续轮转")
    }

    /** 从模板 [AppConfig.mutual_traffic_target_texts] 随机选一条作为 TikTok 搜索词；为空则回退 teamwork。 */
    private fun pickMutualTikTokSearchKeyword(config: AppConfig): String {
        val pool = config.mutual_traffic_target_texts.map { it.trim() }.filter { it.isNotBlank() }
        return pool.randomOrNull() ?: "teamwork"
    }

    /**
     * 互点：force-stop → 启动 TikTok → 搜索模板配置的引流关键词 → OCR 进入 Videos（与旧版 [enterMutualClickMode] 前半段一致）。
     * 不调用 [PrefsStore.clearMutualClickState]，供帧差三连败后的界面恢复复用。
     */
    private suspend fun reopenTikTokAndOpenMutualTeamworkVideosTab(
        service: Context,
        config: AppConfig,
        launchCheckTag: String,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val mutualSearchText = pickMutualTikTokSearchKeyword(config)
        val safeSearchText = shellEscape(mutualSearchText)

        // 关开 TikTok 须走 launchTikTokWithForegroundVerification（强停→拉起→等待→dumpsys 校验→日志），
        // 不可仅用 ensureTikTokInForeground：force-stop 后 dumpsys 残留包名会静默误判为已前台而跳过拉起。
        didRestartTikTokInCurrentRun = true
        if (!launchTikTokWithForegroundVerification(
                ctx = service,
                alias = alias,
                tag = "互点·$launchCheckTag",
                forceStopBeforeLaunch = true,
                timingConfig = config,
            )
        ) {
            devOp(alias, "互点：TikTok 拉起未通过前台校验（$launchCheckTag）")
            return false
        }

        delay(800)

        runLoginSafeguardMeasureOrAbortMessage(service, "互点-$launchCheckTag")?.let { msg ->
            devOp(alias, "互点：登录状态保障未通过（$launchCheckTag）")
            if (msg == SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE) {
                throw LoginSafeguardRequiresAutoLoginException()
            }
            return false
        }

        if (!clickMagnifier(service)) {
            devOp(alias, "互点：TikTok 恢复拉起失败，无法点击放大镜（$launchCheckTag）")
            return false
        }
        delay(500)
        runSuCommand("input keyevent 67")
        runSuCommand("input keyevent 67")
        runSuCommand("input text $safeSearchText")
        delay(800)
        if (!clickSearchButtonAndWait(service)) {
            devOp(alias, "互点：TikTok 恢复拉起失败，无法点击 Search（$launchCheckTag）")
            return false
        }
        devOp(alias, "互点：已完成放大镜点击并搜索「$mutualSearchText」（$launchCheckTag）")
        delay(2400)
        val firstVideosAnchor = findVideosTabAnchorByOcrWithRetries(service, launchCheckTag, "首次")
        if (firstVideosAnchor == null) return false
        runSuCommand("input tap ${firstVideosAnchor.x} ${firstVideosAnchor.y}")
        devOp(alias, "互点：首次点击 Videos 标签（$launchCheckTag）")
        delay(MUTUAL_VIDEOS_TAB_AFTER_FIRST_TAP_DELAY_MS)
        val secondVideosAnchor = findVideosTabAnchorByOcrWithRetries(service, launchCheckTag, "二次")
        if (secondVideosAnchor == null) return false
        runSuCommand("input tap ${secondVideosAnchor.x} ${secondVideosAnchor.y}")
        devOp(alias, "互点：二次点击 Videos 标签（$launchCheckTag）")
        delay(2200)
        return true
    }

    /** 帧差判定界面错乱后：关开 TikTok 并重新搜索进入 teamwork Videos（带连续重试，不跳过左/右侧）。 */
    private suspend fun reopenMutualTeamworkVideosTabWithRetries(
        service: Context,
        config: AppConfig,
        launchCheckTag: String,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        for (attempt in 1..MUTUAL_TEAMWORK_RECOVERY_REOPEN_MAX_ATTEMPTS) {
            if (reopenTikTokAndOpenMutualTeamworkVideosTab(service, config, "$launchCheckTag·$attempt")) {
                return true
            }
            if (attempt < MUTUAL_TEAMWORK_RECOVERY_REOPEN_MAX_ATTEMPTS) {
                devOp(
                    alias,
                    "互点：teamwork 恢复失败（$launchCheckTag 第 $attempt/$MUTUAL_TEAMWORK_RECOVERY_REOPEN_MAX_ATTEMPTS 次），${2000}ms 后再次关开 TikTok 重试",
                )
                delay(Random.nextLong(2000, 3501))
            }
        }
        devOp(
            alias,
            "互点：teamwork 恢复 ${MUTUAL_TEAMWORK_RECOVERY_REOPEN_MAX_ATTEMPTS} 次均未成功（$launchCheckTag），将继续关开 TikTok 重试",
        )
        return false
    }

    /** 互点/互点评论：达到配置上限并成功跑完 Videos 单元后，短暂停留、强停 TikTok。 */
    private suspend fun finalizeMutualSessionLimitedExit(alias: String) {
        devOp(alias, "互点会话已达配置上限：短暂停留后强停 TikTok")
        delay(Random.nextLong(800, 2001))
        val pkg = Config.TIKTOK_PACKAGE_NAME
        runSuCommand("am force-stop $pkg")
        delay(Random.nextLong(400, 901))
    }

    /**
     * 发作品用：图库素材统计（`Pictures` 目录树递归，su + find；**排除** `Pictures/AutoTraces`）。
     *
     * - [picturesRootImageCount] / [picturesRootVideoCount]：非隐藏路径、按扩展名识别的图片/视频文件数。
     * - [resolvedCount]：二者之和，作为「本次可发作品条数」的上限基数（再与 [PUBLISH_POST_MAX_ITEMS_PER_TASK] 取 min）。
     */
    private data class PublishPostGalleryMaterialStats(
        val picturesRootImageCount: Int,
        val picturesRootVideoCount: Int,
    ) {
        val resolvedCount: Int get() = picturesRootImageCount + picturesRootVideoCount
    }

    private fun publishPostResolveGalleryMaterialStats(): PublishPostGalleryMaterialStats {
        val (img, vid) = publishPostCountPicturesImageVideoViaSu()
        return PublishPostGalleryMaterialStats(img, vid)
    }

    /**
     * 发作品时的音频位置固定坐标：屏幕正中 X，顶部 1/10 处 Y。
     */
    private fun fixedAudioTapPoint(service: Context): Point {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val x = (w / 2).coerceIn(1, w - 1)
        val y = (h * 0.1f).toInt().coerceIn(1, h - 1)
        return Point(x, y)
    }

    /**
     * 发作品图库选素材（单次任务最多 [PUBLISH_POST_MAX_ITEMS_PER_TASK] 条，索引 0～4）：
     * 第 1 张基准 X≈16%*W、Y≈30%*H；第 2 张屏宽水平中点、Y 同第 1；第 3 张与第 1 关于竖直中线对称；
     * 第 4 张 X 同第 1、Y≈49%*H；第 5 张屏宽水平中点、Y 同第 4。
     */
    private fun publishPostGalleryMediaTap(w: Int, h: Int, videoIndex: Int): Point {
        val y1 = (h * 0.3f).toInt().coerceIn(1, h - 1)
        val x1 = (w * 0.16f).toInt().coerceIn(1, w - 1)
        val y4 = (h * 0.49f).toInt().coerceIn(1, h - 1)
        return when (videoIndex) {
            0 -> Point(x1, y1)
            1 -> Point((w / 2).coerceIn(1, w - 1), y1)
            2 -> Point((w - x1).coerceIn(1, w - 1), y1)
            3 -> Point(x1, y4)
            4 -> Point((w / 2).coerceIn(1, w - 1), y4)
            else -> Point(x1, y1)
        }
    }

    private fun findPostNowLabelHit(result: Text): StrictLabelHit? {
        findStrictExactAcrossAdjacentLines(result, "Post Now")?.let { return it }
        findStrictExactLabelHit(result, "Post now")?.let { return it }
        findStrictExactLabelHit(result, "Post Now")?.let { return it }
        return null
    }

    private fun findDontAllowLabelHit(result: Text): StrictLabelHit? {
        findStrictExactLabelHit(result, "Don't allow")?.let { return it }
        findStrictExactLabelHit(result, "Don’t allow")?.let { return it }
        findStrictExactLabelHit(result, "Dont allow")?.let { return it }
        return null
    }

    /** 拉起后专用：拒绝系统权限弹窗（英文 Don't allow + 中文取消/不允许）。 */
    private fun findLaunchPermissionDismissHit(result: Text): StrictLabelHit? {
        findDontAllowLabelHit(result)?.let { return it }
        findStrictExactLabelHit(result, "取消")?.let { return it }
        findStrictExactLabelHit(result, "不允许")?.let { return it }
        findStrictExactLabelHit(result, "Deny")?.let { return it }
        findStrictExactLabelHit(result, "deny")?.let { return it }
        return null
    }

    /** 系统单次权限对话框「Only this time」；折行或大写变体 OCR 兜底。 */
    private fun findOnlyThisTimeLabelHit(result: Text): StrictLabelHit? {
        findStrictExactAcrossAdjacentLines(result, "Only this time")?.let { return it }
        findStrictExactLabelHit(result, "Only This Time")?.let { return it }
        return null
    }

    private fun findWritingALongCaptionHit(result: Text): StrictLabelHit? {
        fun considerTextAndBox(text: String, box: Rect): StrictLabelHit? {
            val low = text.trim().lowercase(Locale.US)
            if (low.contains("writing a long")) {
                return StrictLabelHit(Point(box.centerX(), box.centerY()), box)
            }
            return null
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { b ->
                    considerTextAndBox(line.text, b)?.let { return it }
                }
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    considerTextAndBox(el.text, b)?.let { return it }
                }
            }
        }
        return null
    }

    private enum class PublishCaptionConfirmKind { DONE, POST }

    private data class PublishCaptionConfirmHit(
        val kind: PublishCaptionConfirmKind,
        val hit: StrictLabelHit,
    )

    /** 单条作品发布成功信号评估：负面信号已消失，或命中任一正面信号。 */
    private data class PublishPostItemSignalEval(
        val succeeded: Boolean,
        val reason: String,
    )

    private sealed class PublishPostOptionalOcrOutcome {
        data class ItemSucceeded(val eval: PublishPostItemSignalEval) : PublishPostOptionalOcrOutcome()
        object DialogTapped : PublishPostOptionalOcrOutcome()
        object DialogNotFound : PublishPostOptionalOcrOutcome()
        object HardStop : PublishPostOptionalOcrOutcome()
    }

    /** 发布流程正面信号：命中任一即视为本条作品发布成功（可在第 7 步之后任意 OCR 中观察）。 */
    private fun publishPostPositiveSignalReason(result: Text, screenH: Int): String? {
        if (countBottomMainNavTabKinds(result, screenH) >= 2) return "底栏主导航≥2"
        if (findPostNowLabelHit(result) != null) return "Post Now"
        if (findOnlyThisTimeLabelHit(result) != null) return "Only this time"
        for (needle in PUBLISH_POST_POSITIVE_SIGNAL_TEXT_NEEDLES) {
            if (findLabelHitContainingIgnoreCase(result, needle) != null) return needle
        }
        return null
    }

    /** Done 布局末次点击为 Next；Post 布局末次点击为顶部 Post。点击后 OCR 仍可见则视为未生效。 */
    private fun publishPostLayoutConfirmControlStillVisible(
        result: Text,
        screenH: Int,
        kind: PublishCaptionConfirmKind,
    ): Boolean =
        when (kind) {
            PublishCaptionConfirmKind.DONE ->
                findStrictExactLabelHit(result, "Next") != null
            PublishCaptionConfirmKind.POST ->
                findTopScreenCaptionPostHit(result, screenH) != null
        }

    private fun publishPostLayoutConfirmControlLabel(kind: PublishCaptionConfirmKind): String =
        when (kind) {
            PublishCaptionConfirmKind.DONE -> "Next"
            PublishCaptionConfirmKind.POST -> "顶部Post"
        }

    /**
     * 两种文案布局在末次点击后的发布完成判定：
     * - Done 布局：点完 Next 后 OCR 不再命中 Next；
     * - Post 布局：点完顶部 Post 后 OCR 不再命中该 Post。
     * 正面信号（底栏≥2 等）仍可作为提前成功的快捷路径。
     */
    private fun evaluatePublishPostLayoutConfirmTapEval(
        result: Text,
        screenH: Int,
        kind: PublishCaptionConfirmKind,
    ): PublishPostItemSignalEval {
        publishPostPositiveSignalReason(result, screenH)?.let { hit ->
            return PublishPostItemSignalEval(true, "正面信号:$hit")
        }
        val label = publishPostLayoutConfirmControlLabel(kind)
        if (!publishPostLayoutConfirmControlStillVisible(result, screenH, kind)) {
            return PublishPostItemSignalEval(true, "${label}已消失（点击生效，发布完成）")
        }
        return PublishPostItemSignalEval(false, "仍可见$label")
    }

    /** 文案屏偏下区域 Done 等：仅考虑 bbox.top ≥ 屏高 22% 的命中，取最靠上者。 */
    private fun findTopmostCaptionLabelHit(
        result: Text,
        screenH: Int,
        labels: Set<String>,
    ): StrictLabelHit? {
        val thresholdTop = (screenH * 0.22f).toInt()
        var best: StrictLabelHit? = null
        var bestTop = Int.MAX_VALUE
        fun consider(hit: StrictLabelHit) {
            if (hit.rect.top < thresholdTop) return
            if (hit.rect.top <= bestTop) {
                bestTop = hit.rect.top
                best = hit
            }
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    if (el.text.trim() in labels) {
                        val b = el.boundingBox ?: continue
                        consider(StrictLabelHit(Point(b.centerX(), b.centerY()), b))
                    }
                }
                if (line.text.trim() in labels) {
                    val b = line.boundingBox ?: continue
                    consider(StrictLabelHit(Point(b.centerX(), b.centerY()), b))
                }
            }
        }
        return best
    }

    /** 文案屏顶部发布 Post：文案含 post（忽略大小写）、中心在屏上方；排除 Post Now / Post this…；多命中取最靠上。 */
    private fun findTopScreenCaptionPostHit(result: Text, screenH: Int): StrictLabelHit? {
        val maxCenterY = (screenH * PUBLISH_POST_CAPTION_TOP_POST_MAX_CENTER_Y_FRACTION).toInt()
        var best: StrictLabelHit? = null
        var bestTop = Int.MAX_VALUE
        fun textQualifies(raw: String): Boolean {
            val low = raw.trim().lowercase(Locale.US)
            if (!low.contains("post")) return false
            if (low.contains("post now")) return false
            if (low.contains("post this")) return false
            return true
        }
        fun consider(text: String, b: Rect) {
            if (!textQualifies(text)) return
            if (b.centerY() > maxCenterY) return
            if (b.top <= bestTop) {
                bestTop = b.top
                best = StrictLabelHit(Point(b.centerX(), b.centerY()), b)
            }
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    consider(el.text, b)
                }
                line.boundingBox?.let { consider(line.text, it) }
            }
        }
        return best
    }

    /** 文案确认：优先偏下区域 Done；无 Done 时再认屏幕上方含 post 的 Post（不含 Post Now）。 */
    private fun findPublishCaptionConfirmHit(result: Text, screenH: Int): PublishCaptionConfirmHit? {
        findTopmostCaptionLabelHit(result, screenH, setOf("Done"))
            ?.let { return PublishCaptionConfirmHit(PublishCaptionConfirmKind.DONE, it) }
        findTopScreenCaptionPostHit(result, screenH)
            ?.let { return PublishCaptionConfirmHit(PublishCaptionConfirmKind.POST, it) }
        findStrictExactLabelHit(result, "Done")
            ?.let { return PublishCaptionConfirmHit(PublishCaptionConfirmKind.DONE, it) }
        return null
    }

    private suspend fun publishPostOcrTapByFinder(
        service: Context,
        alias: String,
        fileTagPrefix: String,
        phaseLog: String,
        maxPasses: Int,
        findHit: (Text) -> StrictLabelHit?,
    ): Boolean {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        for (pass in 1..maxPasses) {
            if (hardStopOrNull(alias, "发作品 $phaseLog")) return false
            if (pass > 1) delay(700) else delay(450)
            val (vision, errTag) = mlKitRecognizeFullScreen(service, "${fileTagPrefix}_$pass")
            if (vision == null) {
                devOp(alias, "发作品 $phaseLog：第 $pass/$maxPasses 次 OCR 失败：$errTag")
                if (isMlKitHardStopError(errTag)) return false
                continue
            }
            devOp(alias, "发作品 $phaseLog：第 $pass/$maxPasses 次摘要=${buildProfileEditOcrPreview(vision).take(420)}")
            val hit = findHit(vision)
            if (hit == null) {
                devOp(alias, "发作品 $phaseLog：第 $pass/$maxPasses 次未命中")
                continue
            }
            val tap = coerceTapToScreen(hit.point, w, h)
            devOp(alias, "发作品 $phaseLog：tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) return true
            devOp(alias, "发作品 $phaseLog：tap 失败，重试")
        }
        return false
    }

    /**
     * 统计屏幕偏下区域内严格命中的底栏主导航文案种类数（Home / Friends / Inbox / Profile 各算一类）。
     * 用 bbox 纵向中心 ≥ [screenH]×[bottomFractionStart] 过滤，降低信息流正文误计。
     */
    private fun countBottomMainNavTabKinds(
        result: Text,
        screenH: Int,
        bottomFractionStart: Float = 0.67f,
    ): Int {
        val yThreshold = (screenH * bottomFractionStart).toInt().coerceIn(1, screenH - 1)
        val navLabels = setOf("Home", "Friends", "Inbox", "Profile")
        val found = mutableSetOf<String>()
        fun consider(text: String, b: Rect) {
            val t = text.trim()
            if (t !in navLabels) return
            if (b.centerY() >= yThreshold) found.add(t)
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val lt = line.text.trim()
                if (lt in navLabels) {
                    line.boundingBox?.let { consider(lt, it) }
                }
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    consider(el.text, b)
                }
            }
        }
        return found.size
    }

    /** 屏幕偏下区域是否命中底栏「Home」或「Profile」（任一即可，大小写不敏感）。 */
    private fun hasBottomHomeOrProfile(
        result: Text,
        screenH: Int,
        bottomFractionStart: Float = 0.62f,
    ): Boolean {
        val yThreshold = (screenH * bottomFractionStart).toInt().coerceIn(1, screenH - 1)
        fun matches(text: String, b: Rect): Boolean {
            val t = text.trim().lowercase(Locale.US)
            if (b.centerY() < yThreshold) return false
            return t == "home" || t == "profile" || t.contains("home") || t.contains("profile")
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val lt = line.text.trim()
                line.boundingBox?.let { if (matches(lt, it)) return true }
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    if (matches(el.text, b)) return true
                }
            }
        }
        return false
    }

    /** 屏下区域是否含指定文案（大小写不敏感，含 equals/contains）。 */
    private fun hasBottomBandContaining(
        result: Text,
        screenH: Int,
        needle: String,
        bottomFractionStart: Float = 0.62f,
    ): Boolean {
        val yThreshold = (screenH * bottomFractionStart).toInt().coerceIn(1, screenH - 1)
        val lowNeedle = needle.trim().lowercase(Locale.US)
        fun matches(text: String, b: Rect): Boolean {
            val t = text.trim().lowercase(Locale.US)
            if (b.centerY() < yThreshold) return false
            return t == lowNeedle || t.contains(lowNeedle)
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { if (matches(line.text, it)) return true }
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    if (matches(el.text, b)) return true
                }
            }
        }
        return false
    }

    /** 屏下区域按精确词命中，返回可点击的 bbox 中心点。 */
    private fun findBottomBandExactLabelHit(
        result: Text,
        screenH: Int,
        label: String,
        bottomFractionStart: Float = 0.62f,
    ): StrictLabelHit? {
        val yThreshold = (screenH * bottomFractionStart).toInt().coerceIn(1, screenH - 1)
        val target = label.trim().lowercase(Locale.US)
        for (block in result.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { b ->
                    val t = line.text.trim().lowercase(Locale.US)
                    if (b.centerY() >= yThreshold && t == target) {
                        return StrictLabelHit(Point(b.centerX(), b.centerY()), b)
                    }
                }
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    val t = el.text.trim().lowercase(Locale.US)
                    if (b.centerY() >= yThreshold && t == target) {
                        return StrictLabelHit(Point(b.centerX(), b.centerY()), b)
                    }
                }
            }
        }
        return null
    }

    /** 跳过兴趣页：底栏 Home 与 Profile 须同时出现。 */
    private fun autoLoginSeesMainFeedBottomNav(result: Text, screenH: Int): Boolean =
        hasBottomBandContaining(result, screenH, "home") &&
            hasBottomBandContaining(result, screenH, "profile")

    private enum class AutoLoginRouteScreenKind {
        Interest,
        MainFeed,
        Unknown,
    }

    /** 单次 OCR 同时判定当前界面（兴趣页优先，其次底栏 Home+Profile）。 */
    private fun autoLoginClassifyRouteScreen(result: Text, screenH: Int): AutoLoginRouteScreenKind {
        if (autoLoginSeesInterestPicker(result)) return AutoLoginRouteScreenKind.Interest
        if (autoLoginSeesMainFeedBottomNav(result, screenH)) return AutoLoginRouteScreenKind.MainFeed
        return AutoLoginRouteScreenKind.Unknown
    }

    private fun autoLoginRouteScreenKindLabel(kind: AutoLoginRouteScreenKind): String = when (kind) {
        AutoLoginRouteScreenKind.Interest -> "兴趣页"
        AutoLoginRouteScreenKind.MainFeed -> "主Feed(底栏Home+Profile)"
        AutoLoginRouteScreenKind.Unknown -> "未识别"
    }

    private fun autoLoginDirectSuccessMatchReason(result: Text, screenH: Int): String {
        if (!autoLoginSeesMainFeedBottomNav(result, screenH)) return "未知"
        val hasHome = hasBottomBandContaining(result, screenH, "home")
        val hasProfile = hasBottomBandContaining(result, screenH, "profile")
        return "底栏Home+Profile( home=$hasHome, profile=$hasProfile )"
    }

    /**
     * For You 预滑：每段开始前整屏 OCR，探测 Account status / Add another account 并走既有掉登录分支。
     * 仅当 [FOR_YOU_WARMUP_LOGOUT_PROBE_MIN_TOTAL_MS] ≤ 配置总时长时由 [runForYouWarmupIfNeeded] 调用。
     */
    private suspend fun probeForYouWarmupLogoutSignalsAtPhaseStart(
        ctx: Context,
        alias: String,
        phase: Int,
    ) {
        devOp(
            alias,
            "For You 预滑：第 $phase/$FOR_YOU_WARMUP_LOGOUT_PROBE_PHASES 时间段开始前掉登录检测（Account status / Add another account）",
        )
        val tag = "for_you_warmup_logout_probe_$phase"
        val (vision, err) = mlKitRecognizeFullScreen(ctx, tag)
        if (vision == null) {
            devOp(alias, "For You 预滑：第 $phase/$FOR_YOU_WARMUP_LOGOUT_PROBE_PHASES 段掉登录检测 OCR 失败：$err")
        }
    }

    /**
     * For You 预滑：底栏 OCR 校验 Home/Profile；缺失则强停重启 TikTok 后继续预滑。
     * @return false 表示硬停、重启失败或无法拉回前台，调用方应终止预滑。
     */
    private suspend fun verifyForYouWarmupBottomNavOrRecover(
        service: Context,
        alias: String,
        screenH: Int,
    ): Boolean {
        for (attempt in 1..FOR_YOU_WARMUP_BOTTOM_NAV_OCR_MAX_ATTEMPTS) {
            if (attempt > 1) delay(500)
            if (hardStopOrNull(alias, "For You 预滑-底栏校验")) return false
            val (vision, err) = mlKitRecognizeFullScreen(service, "for_you_warmup_bottom_nav_$attempt")
            if (vision == null) {
                devOp(alias, "For You 预滑：底栏 OCR 第 $attempt/${FOR_YOU_WARMUP_BOTTOM_NAV_OCR_MAX_ATTEMPTS} 次失败：$err")
                if (isMlKitHardStopError(err)) return false
                continue
            }
            val preview = buildProfileEditOcrPreview(vision).take(420)
            val ok = hasBottomHomeOrProfile(vision, screenH)
            devOp(
                alias,
                "For You 预滑：底栏 OCR 第 $attempt/${FOR_YOU_WARMUP_BOTTOM_NAV_OCR_MAX_ATTEMPTS} 次摘要=$preview；Home/Profile=${if (ok) "有" else "无"}",
            )
            if (ok) return true
        }
        devOp(alias, "For You 预滑：底栏未检出 Home/Profile，判定页面错乱，优先导航返回回首页")
        if (!recoverToHomeViaBackOrRestart(service, "For You 预滑-底栏恢复")) {
            devOp(alias, "For You 预滑：回首页/重启 TikTok 失败，终止预滑")
            return false
        }
        if (!ensureTikTokInForeground(service, alias, "For You 预滑-恢复后")) return false
        devOp(alias, "For You 预滑：回首页/重启成功，继续预滑")
        return true
    }

    /**
     * 轮询 OCR：Done 布局点完 Next / Post 布局点完顶部 Post 后，
     * 不再 OCR 到对应控件（或出现正面信号）则判本条发布完成。
     */
    private suspend fun verifyPublishPostLayoutConfirmTapSucceeded(
        service: Context,
        alias: String,
        videoIndex: Int,
        screenH: Int,
        layoutKind: PublishCaptionConfirmKind,
        maxPasses: Int = PUBLISH_POST_ITEM_SIGNAL_POLL_MAX_PASSES,
        phaseLog: String = "布局末次点击后发布完成",
        fileTagPrefix: String = "publish_post_layout_confirm_${videoIndex}",
    ): Boolean {
        val retries = maxPasses.coerceAtLeast(1)
        val controlLabel = publishPostLayoutConfirmControlLabel(layoutKind)
        for (attempt in 1..retries) {
            if (attempt > 1) delay(PUBLISH_POST_ITEM_SIGNAL_POLL_DELAY_MS)
            if (hardStopOrNull(alias, "发作品 $phaseLog")) return false
            val (v, err) = mlKitRecognizeFullScreen(service, "${fileTagPrefix}_$attempt")
            if (v == null) {
                devOp(alias, "发作品：$phaseLog OCR 第 $attempt/$retries 次失败：$err")
                if (isMlKitHardStopError(err)) return false
                continue
            }
            val preview = buildProfileEditOcrPreview(v).take(420)
            val eval = evaluatePublishPostLayoutConfirmTapEval(v, screenH, layoutKind)
            val detail =
                if (eval.succeeded) eval.reason
                else eval.reason.ifBlank { "仍可见$controlLabel" }
            devOp(alias, "发作品：$phaseLog OCR 第 $attempt/$retries 次摘要=$preview；$detail")
            if (eval.succeeded) return true
        }
        devOp(
            alias,
            "发作品：$phaseLog 连续 $retries 次 OCR 仍可见 $controlLabel，未判发布完成",
        )
        return false
    }

    /** 已有 OCR 结果时评估布局末次点击是否生效；命中则写日志并返回 true。 */
    private fun publishPostObserveLayoutConfirmFromVision(
        alias: String,
        vision: Text,
        screenH: Int,
        layoutKind: PublishCaptionConfirmKind,
        phaseLog: String,
        pass: Int,
        maxPasses: Int,
    ): Boolean {
        val eval = evaluatePublishPostLayoutConfirmTapEval(vision, screenH, layoutKind)
        if (!eval.succeeded) return false
        devOp(
            alias,
            "发作品：$phaseLog 第 $pass/$maxPasses 次观察到发布完成（${eval.reason}）",
        )
        return true
    }

    /**
     * 可选权限弹窗 OCR：尝试点击目标弹窗文案；未命中不判失败。
     * [watchPublishSignals] 为 true 时，每次截屏先观察 Next/顶部 Post 是否已消失，命中则返回 [PublishPostOptionalOcrOutcome.ItemSucceeded]。
     */
    private suspend fun publishPostOptionalOcrTapWatchingSignals(
        service: Context,
        alias: String,
        videoIndex: Int,
        screenH: Int,
        layoutKind: PublishCaptionConfirmKind,
        fileTagPrefix: String,
        phaseLog: String,
        maxPasses: Int,
        watchPublishSignals: Boolean = true,
        findHit: (Text) -> StrictLabelHit?,
    ): PublishPostOptionalOcrOutcome {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        for (pass in 1..maxPasses) {
            if (hardStopOrNull(alias, "发作品 $phaseLog")) return PublishPostOptionalOcrOutcome.HardStop
            if (pass > 1) delay(700) else delay(450)
            val (vision, errTag) = mlKitRecognizeFullScreen(service, "${fileTagPrefix}_$pass")
            if (vision == null) {
                devOp(alias, "发作品 $phaseLog：第 $pass/$maxPasses 次 OCR 失败：$errTag")
                if (isMlKitHardStopError(errTag)) return PublishPostOptionalOcrOutcome.HardStop
                continue
            }
            devOp(alias, "发作品 $phaseLog：第 $pass/$maxPasses 次摘要=${buildProfileEditOcrPreview(vision).take(420)}")
            if (watchPublishSignals &&
                publishPostObserveLayoutConfirmFromVision(alias, vision, screenH, layoutKind, phaseLog, pass, maxPasses)
            ) {
                return PublishPostOptionalOcrOutcome.ItemSucceeded(
                    evaluatePublishPostLayoutConfirmTapEval(vision, screenH, layoutKind),
                )
            }
            val hit = findHit(vision)
            if (hit == null) {
                devOp(alias, "发作品 $phaseLog：第 $pass/$maxPasses 次未命中")
                continue
            }
            val tap = coerceTapToScreen(hit.point, w, h)
            devOp(alias, "发作品 $phaseLog：tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                return PublishPostOptionalOcrOutcome.DialogTapped
            }
            devOp(alias, "发作品 $phaseLog：tap 失败，重试")
        }
        return PublishPostOptionalOcrOutcome.DialogNotFound
    }

    /**
     * 当日首条作品已确认发布成功后，按顺序尝试权限弹窗 OCR（未命中跳过，不判失败）。
     * @return false 仅硬停时
     */
    private suspend fun runPublishPostFirstDailyOptionalPermissionDialogs(
        service: Context,
        alias: String,
        videoIndex: Int,
        screenH: Int,
        layoutKind: PublishCaptionConfirmKind,
    ): Boolean {
        devOp(
            alias,
            "发作品：当日首条已确认发布成功，开始可选权限弹窗 OCR（Post Now / Only this time（首轮+可选二次 OCR 再点）/ Don't allow / 底部 Cancel；" +
                "各按配置次数；未命中仅跳过，不作为发布失败依据）",
        )
        val dialogChainPreDelayMs = Random.nextLong(
            PUBLISH_POST_PERM_DIALOG_CHAIN_PRE_DELAY_MIN_MS,
            PUBLISH_POST_PERM_DIALOG_CHAIN_PRE_DELAY_MAX_MS + 1,
        )
        devOp(alias, "发作品：权限弹窗链开始前等待 ${dialogChainPreDelayMs}ms，避免弹窗未弹出就 OCR")
        delay(dialogChainPreDelayMs)
        devOp(alias, "发作品：尝试 Post Now（可选）")
        when (
            publishPostOptionalOcrTapWatchingSignals(
                service,
                alias,
                videoIndex,
                screenH,
                layoutKind,
                "publish_post_post_now_${videoIndex}",
                "Post Now",
                8,
                watchPublishSignals = false,
            ) { findPostNowLabelHit(it) }
        ) {
            PublishPostOptionalOcrOutcome.DialogTapped -> {
                devOp(alias, "发作品：Post Now 已点")
                delay(500)
            }
            PublishPostOptionalOcrOutcome.DialogNotFound ->
                devOp(alias, "发作品：Post Now 未命中，跳过（可选弹窗）")
            PublishPostOptionalOcrOutcome.HardStop -> return false
            is PublishPostOptionalOcrOutcome.ItemSucceeded -> Unit
        }

        devOp(alias, "发作品：尝试 Only this time（首轮，可选）")
        when (
            publishPostOptionalOcrTapWatchingSignals(
                service,
                alias,
                videoIndex,
                screenH,
                layoutKind,
                "publish_post_only_this_time_${videoIndex}_r1",
                "Only this time(首轮)",
                3,
                watchPublishSignals = false,
            ) { findOnlyThisTimeLabelHit(it) }
        ) {
            PublishPostOptionalOcrOutcome.DialogTapped -> {
                devOp(alias, "发作品：Only this time 首轮已点，进行二次 OCR（仍查找 Only this time，命中再点，未命中则跳过）")
                delay(550)
                when (
                    publishPostOptionalOcrTapWatchingSignals(
                        service,
                        alias,
                        videoIndex,
                        screenH,
                        layoutKind,
                        "publish_post_only_this_time_${videoIndex}_r2",
                        "Only this time(二次)",
                        3,
                        watchPublishSignals = false,
                    ) { findOnlyThisTimeLabelHit(it) }
                ) {
                    PublishPostOptionalOcrOutcome.DialogTapped -> {
                        devOp(alias, "发作品：Only this time 二次已点")
                        delay(500)
                    }
                    PublishPostOptionalOcrOutcome.DialogNotFound ->
                        devOp(alias, "发作品：Only this time 二次 OCR 未再命中或未点，跳过（可能仅单层弹窗）")
                    PublishPostOptionalOcrOutcome.HardStop -> return false
                    is PublishPostOptionalOcrOutcome.ItemSucceeded -> Unit
                }
            }
            PublishPostOptionalOcrOutcome.DialogNotFound ->
                devOp(alias, "发作品：Only this time 首轮未命中或未点，跳过（无该项或未出现）")
            PublishPostOptionalOcrOutcome.HardStop -> return false
            is PublishPostOptionalOcrOutcome.ItemSucceeded -> Unit
        }

        devOp(alias, "发作品：尝试 Don't allow（可选）")
        when (
            publishPostOptionalOcrTapWatchingSignals(
                service,
                alias,
                videoIndex,
                screenH,
                layoutKind,
                "publish_post_dont_allow_${videoIndex}",
                "Don't allow",
                3,
                watchPublishSignals = false,
            ) { findDontAllowLabelHit(it) }
        ) {
            PublishPostOptionalOcrOutcome.DialogTapped -> {
                devOp(alias, "发作品：Don't allow 已点")
                delay(500)
            }
            PublishPostOptionalOcrOutcome.DialogNotFound ->
                devOp(alias, "发作品：Don't allow 未命中，跳过（可选弹窗）")
            PublishPostOptionalOcrOutcome.HardStop -> return false
            is PublishPostOptionalOcrOutcome.ItemSucceeded -> Unit
        }

        devOp(alias, "发作品：尝试底部 Cancel（可选）")
        when (
            publishPostOptionalOcrTapWatchingSignals(
                service,
                alias,
                videoIndex,
                screenH,
                layoutKind,
                "publish_post_cancel_bottom_${videoIndex}",
                "Cancel(最下)",
                3,
                watchPublishSignals = false,
            ) { findBottommostStrictExactLabelHit(it, "Cancel") }
        ) {
            PublishPostOptionalOcrOutcome.DialogTapped -> {
                devOp(alias, "发作品：底部 Cancel 已点")
                delay(500)
            }
            PublishPostOptionalOcrOutcome.DialogNotFound ->
                devOp(alias, "发作品：底部 Cancel 未命中，跳过（可选弹窗）")
            PublishPostOptionalOcrOutcome.HardStop -> return false
            is PublishPostOptionalOcrOutcome.ItemSucceeded -> Unit
        }
        return true
    }

    private suspend fun publishPostColdLaunch(service: Context, packageName: String, alias: String, config: AppConfig): Boolean {
        if (hardStopOrNull(alias, "发作品冷启")) return false
        lastConfigSnapshot = config
        val launchWaitMs = config.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
        val pkg = packageName.trim().ifEmpty { Config.TIKTOK_PACKAGE_NAME }
        if (pkg == Config.TIKTOK_PACKAGE_NAME) {
            val launched = launchTikTokOnceWithCheck(
                service,
                alias,
                "发作品冷启",
                launchWaitMs,
                forceStopBeforeLaunch = true,
                skipDontAllowOcr = true,
            )
                || run {
                    devOp(alias, "发作品：首次冷启未通过前台校验，尝试 restartTikTokForRecovery")
                    val recovered = restartTikTokForRecovery(skipDontAllowOcr = true)
                    if (!recovered) devOp(alias, "发作品：冷启 TikTok 失败")
                    recovered
                }
            if (!launched) return false
            runLoginSafeguardMeasureOrAbortMessage(service, "发作品冷启")?.let { msg ->
                if (msg == SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE) {
                    throw LoginSafeguardRequiresAutoLoginException()
                }
                return false
            }
            return true
        }
        val forceStopWaitMs = config.tiktok_force_stop_wait_ms.coerceIn(100L, 8000L)
        runSuCommand("am force-stop $pkg")
        delay(forceStopWaitMs)
        val launchOk = runCatching {
            val launchIntent = service.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                service.startActivity(
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
                true
            } else {
                false
            }
        }.getOrDefault(false) ||
            runSuCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg") ||
            runSuCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        if (!launchOk) {
            devOp(alias, "发作品：拉起失败（包名=$pkg）")
            return false
        }
        devOp(alias, "发作品：非 TikTok 包拉起，等待 ${launchWaitMs}ms 后进行前台校验（包名=$pkg）")
        delay(launchWaitMs)
        var confirmed = isPackageVisible(pkg)
        if (!confirmed) {
            devOp(alias, "发作品：配置等待后前台校验未通过，再等 1200ms 重试（包名=$pkg）")
            delay(1200)
            confirmed = isPackageVisible(pkg)
        }
        if (!confirmed) devOp(alias, "发作品：冷启失败（包名=$pkg，配置等待后前台校验未通过）")
        return confirmed
    }

    /**
     * 资料入口比例点 →（仅本任务第 1 条）可选 OCR「Continue」与「While using the app」→ **右下角**固定点：
     * 距右约 15% 屏宽、距底约 1/5 屏高（不是贴边到最右下角，而是右下区域偏内一点）。
     * → 右下固定点后（仅布局未确认时）可选 OCR「Allow all」（最多 [PUBLISH_POST_ALLOW_ALL_OCR_MAX_PASSES] 次 OCR、累计命中点击最多 [PUBLISH_POST_ALLOW_ALL_MAX_CLICKS] 次）→ OCR 探测 Recents；
     * 仍未见则备用点 (W/12, CREATE/POST 的 Y)，再 Allow all → 再 OCR 探测 Recents（同样最多 [PUBLISH_POST_RECENTS_OCR_MAX_PASSES] 次），仍无则本条失败。
     * 一旦确认布局 A/B，[runPublishPostEntryFlow] 同任务后续条目直接走对应入口，但每条仍执行 Recents→Pictures OCR 点击以保证入口稳定。
     */
    private suspend fun publishPostTapLayoutAFixedPoint(alias: String, w: Int, h: Int): Boolean {
        val fixedX = (w - (w * 0.15f).toInt()).coerceIn(1, w - 1)
        val fixedY = (h - (h / 5)).coerceIn(1, h - 1)
        devOp(
            alias,
            "发作品：右下固定点 (${fixedX},${fixedY})（屏 ${w}x${h}，距右约 15% 屏宽、距底约 1/5 屏高）",
        )
        if (!runSuCommand("input tap $fixedX $fixedY")) {
            devOp(alias, "发作品：右下固定点 tap 失败")
            return false
        }
        delay(550)
        return true
    }

    private suspend fun publishPostTapLayoutBFallback(alias: String, w: Int, createPostTap: Point): Boolean {
        val fallbackX = (w / PUBLISH_POST_LAYOUT_B_FALLBACK_X_DIVISOR).coerceIn(1, w - 1)
        val fallbackY = createPostTap.y
        devOp(
            alias,
            "发作品：布局 B 备用点击 (${fallbackX},${fallbackY})（X=屏宽/${PUBLISH_POST_LAYOUT_B_FALLBACK_X_DIVISOR}，Y=CREATE/POST）",
        )
        if (!runSuCommand("input tap $fallbackX $fallbackY")) {
            devOp(alias, "发作品：布局 B 备用点击失败")
            return false
        }
        delay(550)
        return true
    }

    /**
     * 布局 A/B 试探点击后：整屏 OCR「Allow all」，命中则 tap（tap 失败仍计入命中点击次数）。
     * 累计命中点击达 [PUBLISH_POST_ALLOW_ALL_MAX_CLICKS] 或 OCR 达 [PUBLISH_POST_ALLOW_ALL_OCR_MAX_PASSES] 即结束；不判本条失败。
     */
    private suspend fun publishPostTryAllowAllAfterLayoutTap(
        service: Context,
        alias: String,
        w: Int,
        h: Int,
        afterLayoutLabel: String,
    ): Boolean {
        if (hardStopOrNull(alias, "发作品 Allow all $afterLayoutLabel")) return false
        devOp(
            alias,
            "发作品：$afterLayoutLabel 后尝试 OCR「Allow all」（OCR 最多 ${PUBLISH_POST_ALLOW_ALL_OCR_MAX_PASSES} 次，" +
                "累计命中点击最多 ${PUBLISH_POST_ALLOW_ALL_MAX_CLICKS} 次；未命中不点击、不判失败）",
        )
        var allowHitClickCount = 0
        for (pass in 1..PUBLISH_POST_ALLOW_ALL_OCR_MAX_PASSES) {
            if (hardStopOrNull(alias, "发作品 Allow all OCR $afterLayoutLabel")) return false
            if (pass > 1) delay(600)
            devOp(
                alias,
                "发作品：$afterLayoutLabel 后 Allow all OCR 第 $pass/${PUBLISH_POST_ALLOW_ALL_OCR_MAX_PASSES} 次…",
            )
            val (vision, errTag) =
                mlKitRecognizeFullScreen(service, "publish_post_allow_all_${afterLayoutLabel}_$pass")
            if (vision == null) {
                devOp(alias, "发作品：$afterLayoutLabel 后 Allow all 第 $pass 次 OCR 失败：$errTag")
                if (isMlKitHardStopError(errTag)) return false
                continue
            }
            val preview = buildProfileEditOcrPreview(vision).take(480)
            devOp(alias, "发作品：$afterLayoutLabel 后 Allow all 第 $pass 次 OCR 摘要=$preview")
            val hit = findStrictExactLabelHit(vision, "Allow all")
            if (hit == null) {
                devOp(alias, "发作品：$afterLayoutLabel 后 Allow all 第 $pass 次未命中严格文案")
                continue
            }
            val tap = coerceTapToScreen(hit.point, w, h)
            allowHitClickCount++
            val tapped = runSuCommand("input tap ${tap.x} ${tap.y}")
            if (tapped) {
                devOp(
                    alias,
                    "发作品：$afterLayoutLabel 后 Allow all 第 $pass 次已 tap (${tap.x},${tap.y})（累计命中点击 $allowHitClickCount/${PUBLISH_POST_ALLOW_ALL_MAX_CLICKS}）",
                )
                delay(500)
            } else {
                devOp(
                    alias,
                    "发作品：$afterLayoutLabel 后 Allow all 第 $pass 次 tap 失败，仍计入累计命中点击 $allowHitClickCount/${PUBLISH_POST_ALLOW_ALL_MAX_CLICKS}",
                )
            }
            if (allowHitClickCount >= PUBLISH_POST_ALLOW_ALL_MAX_CLICKS) {
                devOp(alias, "发作品：$afterLayoutLabel 后 Allow all 已累计命中点击 ${PUBLISH_POST_ALLOW_ALL_MAX_CLICKS} 次，结束本段")
                break
            }
        }
        if (allowHitClickCount == 0) {
            devOp(
                alias,
                "发作品：$afterLayoutLabel 后 Allow all ${PUBLISH_POST_ALLOW_ALL_OCR_MAX_PASSES} 次 OCR 均未命中，跳过继续 Recents 试探",
            )
        } else if (allowHitClickCount < PUBLISH_POST_ALLOW_ALL_MAX_CLICKS) {
            devOp(
                alias,
                "发作品：$afterLayoutLabel 后 Allow all 累计命中点击 $allowHitClickCount 次（未满 ${PUBLISH_POST_ALLOW_ALL_MAX_CLICKS} 次），OCR 轮次已用尽或提前结束，继续 Recents 试探",
            )
        }
        return true
    }

    private suspend fun publishPostProbeRecentsWithRetries(
        service: Context,
        alias: String,
        tagPrefix: String,
        hardStopLabel: String,
        clickRecents: Boolean = false,
        clickPictures: Boolean = false,
    ): Boolean {
        for (pass in 1..PUBLISH_POST_RECENTS_OCR_MAX_PASSES) {
            if (hardStopOrNull(alias, hardStopLabel)) return false
            if (pass > 1) delay(700)
            val ok = publishPostProbeRecentsOnScreen(
                service = service,
                alias = alias,
                ocrTag = "${tagPrefix}_$pass",
                clickRecents = clickRecents,
                clickPictures = clickPictures,
            )
            if (ok) return true
        }
        return false
    }

    private suspend fun publishPostProbeAndCacheEntryLayout(
        service: Context,
        alias: String,
        w: Int,
        h: Int,
        createPostTap: Point,
    ): Boolean {
        if (!publishPostTapLayoutAFixedPoint(alias, w, h)) return false
        if (!publishPostTryAllowAllAfterLayoutTap(service, alias, w, h, "布局A")) return false
        if (
            publishPostProbeRecentsWithRetries(
                service,
                alias,
                "publish_post_recents",
                "发作品 Recents OCR",
                clickRecents = true,
                clickPictures = true,
            )
        ) {
            publishPostEntryLayout = PublishPostEntryLayout.A
            publishPostPersistDeviceEntryLayout(alias, PublishPostEntryLayout.A)
            devOp(alias, "发作品：已确认布局 A（右下固定点），本任务后续条目直接沿用、不再 Recents 试探")
            return true
        }
        devOp(
            alias,
            "发作品：右下固定点后 ${PUBLISH_POST_RECENTS_OCR_MAX_PASSES} 次未完成 Recents→Pictures 点击，尝试布局 B 备用点击",
        )
        if (!publishPostTapLayoutBFallback(alias, w, createPostTap)) return false
        if (!publishPostTryAllowAllAfterLayoutTap(service, alias, w, h, "布局B")) return false
        if (
            publishPostProbeRecentsWithRetries(
                service,
                alias,
                "publish_post_recents_after_fallback",
                "发作品 Recents 布局B后 OCR",
                clickRecents = true,
                clickPictures = true,
            )
        ) {
            publishPostEntryLayout = PublishPostEntryLayout.B
            publishPostPersistDeviceEntryLayout(alias, PublishPostEntryLayout.B)
            devOp(alias, "发作品：已确认布局 B（左侧备用点），本任务后续条目直接沿用、不再 Recents 试探")
            return true
        }
        devOp(
            alias,
            "发作品：布局 B 备用点击后 ${PUBLISH_POST_RECENTS_OCR_MAX_PASSES} 次仍未完成 Recents→Pictures 点击，本条失败，需整条重试",
        )
        return false
    }

    /** 发作品相册 OCR 调试：列出含 picture/recent/gallery 的 element 文案与坐标。 */
    private fun buildPublishPostGalleryOcrCandidates(result: Text): String {
        val hits = LinkedHashSet<String>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val t = el.text.trim()
                    if (t.isEmpty()) continue
                    if (
                        t.contains("picture", ignoreCase = true) ||
                        t.contains("recent", ignoreCase = true) ||
                        t.contains("gallery", ignoreCase = true) ||
                        t.contains("album", ignoreCase = true)
                    ) {
                        val b = el.boundingBox
                        hits.add(
                            if (b != null) "\"$t\"@(${b.centerX()},${b.centerY()})" else "\"$t\"",
                        )
                    }
                }
            }
        }
        return if (hits.isEmpty()) "-" else hits.joinToString(" ; ")
    }

    private suspend fun publishPostProbeRecentsOnScreen(
        service: Context,
        alias: String,
        ocrTag: String,
        clickRecents: Boolean = false,
        clickPictures: Boolean = false,
    ): Boolean {
        val (vision, errTag) = mlKitRecognizeFullScreen(service, ocrTag)
        if (vision == null) {
            devOp(alias, "发作品：Recents 探测 OCR 失败：$errTag [$ocrTag]")
            if (isMlKitHardStopError(errTag)) hardStopOrNull(alias, "发作品 Recents 探测")
            return false
        }
        val recentsPreview = buildProfileEditOcrPreview(vision)
        devOp(
            alias,
            "发作品：Recents 探测 OCR 文案=$recentsPreview [$ocrTag]",
        )
        val recentsHit = findStrictExactLabelHit(vision, "Recents")
        if (recentsHit != null) {
            if (!clickRecents) {
                devOp(alias, "发作品：已探测到 Recents（仅记录，不点击） [$ocrTag]")
                return true
            }
            val tap = coerceTapToScreen(recentsHit.point, service.resources.displayMetrics.widthPixels, service.resources.displayMetrics.heightPixels)
            devOp(alias, "发作品：已探测到 Recents，执行点击 (${tap.x},${tap.y}) [$ocrTag]")
            if (!runSuCommand("input tap ${tap.x} ${tap.y}")) {
                devOp(alias, "发作品：Recents 点击失败 [$ocrTag]")
                return false
            }
            delay(700)
            if (!clickPictures) return true
            val (vision2, errTag2) = mlKitRecognizeFullScreen(service, "${ocrTag}_pictures")
            if (vision2 == null) {
                devOp(alias, "发作品：Pictures 探测 OCR 失败：$errTag2 [$ocrTag]")
                return false
            }
            val picturesPreview = buildProfileEditOcrPreview(vision2)
            val picturesCandidates = buildPublishPostGalleryOcrCandidates(vision2)
            devOp(
                alias,
                "发作品：Pictures 探测 OCR 文案=$picturesPreview 相关候选=$picturesCandidates [$ocrTag]",
            )
            val picturesHit = findStrictExactLabelHit(vision2, "Pictures")
            if (picturesHit == null) {
                devOp(
                    alias,
                    "发作品：Pictures 探测未命中（需严格等于 Pictures）OCR文案=$picturesPreview 相关候选=$picturesCandidates [$ocrTag]",
                )
                return false
            }
            val pictureTap = coerceTapToScreen(picturesHit.point, service.resources.displayMetrics.widthPixels, service.resources.displayMetrics.heightPixels)
            devOp(alias, "发作品：已探测到 Pictures，执行点击 (${pictureTap.x},${pictureTap.y}) [$ocrTag]")
            if (!runSuCommand("input tap ${pictureTap.x} ${pictureTap.y}")) {
                devOp(alias, "发作品：Pictures 点击失败 [$ocrTag]")
                return false
            }
            delay(700)
            return true
        }
        devOp(
            alias,
            "发作品：Recents 探测未命中（需严格等于 Recents）OCR文案=$recentsPreview 相关候选=${buildPublishPostGalleryOcrCandidates(vision)} [$ocrTag]",
        )
        return false
    }

    private fun findCreatePostLabelHit(result: Text): StrictLabelHit? {
        findStrictExactLabelHit(result, "CREATE")?.let { return it }
        findStrictExactLabelHit(result, "Create")?.let { return it }
        findStrictExactLabelHit(result, "POST")?.let { return it }
        findStrictExactLabelHit(result, "Post")?.let { return it }
        return null
    }

    private suspend fun publishPostTapProfileRatioWhileUsingAndLeftBottom(
        service: Context,
        alias: String,
        launchWaitMs: Long,
        skipLaunchWait: Boolean = false,
        /** 本任务第 1 条（videoIndex==0）才跑 Continue / While using；续发 startIdx>0 时整条任务均跳过。 */
        isFirstItemInTask: Boolean = false,
    ): Boolean {
        if (!skipLaunchWait && launchWaitMs > 0L) {
            devOp(alias, "发作品：TikTok 拉起后先按配置等待 ${launchWaitMs}ms 再继续后续步骤")
            delay(launchWaitMs)
        } else if (skipLaunchWait) {
            devOp(alias, "发作品：跳过拉起等待（已在主界面或刚完成冷启）")
        }
        if (!ensureTikTokInForeground(service, alias, "发作品资料入口")) {
            devOp(alias, "发作品：TikTok 恢复拉起失败，中止资料入口点击")
            return false
        }
        val ratioTapY = 0.95f
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val tapX = (w / 2).coerceIn(1, w - 1)
        val tapY = (h * ratioTapY).toInt().coerceIn(1, h - 1)
        devOp(
            alias,
            "发作品：资料比例点 (${tapX},${tapY}) 屏${w}x${h}（Y=${ratioTapY}×高）",
        )
        if (!runSuCommand("input tap $tapX $tapY")) {
            devOp(alias, "发作品：比例位 tap 失败")
            return false
        }
        delay(650)
        if (isFirstItemInTask) {
            if (hardStopOrNull(alias, "发作品 Continue OCR")) return false
            devOp(alias, "发作品：资料比例点后尝试 OCR「Continue」（可选，仅本任务第 1 条）")
            if (
                publishPostOcrTapByFinder(
                    service,
                    alias,
                    "publish_post_continue_after_profile",
                    "Continue",
                    PUBLISH_POST_CONTINUE_OCR_MAX_PASSES,
                ) { findStrictExactLabelHit(it, "Continue") }
            ) {
                devOp(alias, "发作品：已点击「Continue」")
                delay(500)
            } else {
                devOp(alias, "发作品：${PUBLISH_POST_CONTINUE_OCR_MAX_PASSES} 次均未命中「Continue」，跳过（可选）")
            }
            val label = "While using the app"
            val maxOcrPasses = 3
            var whileUsingTapped = false
            var lastTappedPoint: Point? = null
            for (pass in 1..maxOcrPasses) {
                if (hardStopOrNull(alias, "发作品 While using OCR")) return false
                if (pass > 1) delay(700)
                val (vision, errTag) = mlKitRecognizeFullScreen(service, "publish_post_while_using_$pass")
                if (vision == null) {
                    devOp(alias, "发作品：OCR「$label」第 $pass/$maxOcrPasses 次失败：$errTag")
                    if (isMlKitHardStopError(errTag)) return false
                    continue
                }
                val pv = buildProfileEditOcrPreview(vision).take(480)
                devOp(alias, "发作品：OCR 第 $pass/$maxOcrPasses 次摘要=$pv")
                val hit = findStrictExactAcrossAdjacentLines(vision, label)
                    ?: findWhileUsingTheAppOcrRelaxed(vision)
                if (hit == null) {
                    devOp(alias, "发作品：第 $pass/$maxOcrPasses 次未命中「$label」")
                    continue
                }
                val tap = coerceTapToScreen(hit.point, w, h)
                lastTappedPoint = tap
                devOp(alias, "发作品：第 $pass/$maxOcrPasses 次命中「$label」拟 tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    devOp(alias, "发作品：已点击「$label」")
                    if (pass < maxOcrPasses) {
                        delay(500)
                        continue
                    }
                    whileUsingTapped = true
                    break
                }
            }
            if (whileUsingTapped) {
                devOp(alias, "发作品：已完成「$label」的连续点击尝试（最多 $maxOcrPasses 次）")
            } else if (lastTappedPoint != null) {
                devOp(alias, "发作品：${maxOcrPasses} 次中已点过「$label」，但未进入需要重复点击的确认状态，跳过该项")
            } else {
                devOp(alias, "发作品：${maxOcrPasses} 次仍未点击「$label」，跳过该项")
            }
        } else {
            devOp(alias, "发作品：非本任务第 1 条，跳过 OCR「Continue」与「While using the app」")
        }

        val createPostMaxPasses = 4
        var createPostSeen = false
        var createPostTap: Point? = null
        for (pass in 1..createPostMaxPasses) {
            if (hardStopOrNull(alias, "发作品 CREATE/POST OCR")) return false
            if (pass > 1) delay(700)
            val (vision, errTag) = mlKitRecognizeFullScreen(service, "publish_post_create_post_$pass")
            if (vision == null) {
                devOp(alias, "发作品：CREATE/POST OCR 第 $pass/$createPostMaxPasses 次失败：$errTag")
                if (isMlKitHardStopError(errTag)) return false
                continue
            }
            val preview = buildProfileEditOcrPreview(vision).take(480)
            devOp(alias, "发作品：CREATE/POST OCR 第 $pass/$createPostMaxPasses 次摘要=$preview")
            val hit = findCreatePostLabelHit(vision)
            if (hit == null) {
                devOp(alias, "发作品：CREATE/POST OCR 第 $pass/$createPostMaxPasses 次未命中")
                continue
            }
            val tap = coerceTapToScreen(hit.point, w, h)
            createPostTap = tap
            devOp(alias, "发作品：CREATE/POST OCR 第 $pass/$createPostMaxPasses 次命中，记录坐标 (${tap.x},${tap.y})，等待后续固定点")
            createPostSeen = true
            break
        }
        if (!createPostSeen) {
            devOp(alias, "发作品：CREATE/POST 连续 $createPostMaxPasses 次未命中，需重启 TikTok 重新开始当前作品")
            return false
        }
        val postTap = createPostTap
        if (postTap == null) {
            devOp(alias, "发作品：CREATE/POST 已命中但无坐标，本条失败")
            return false
        }

        delay(450)
        return when (publishPostEntryLayout) {
            PublishPostEntryLayout.A -> {
                devOp(alias, "发作品：本任务已确认布局 A，先右下固定点，再执行 Recents→Pictures OCR 点击")
                if (!publishPostTapLayoutAFixedPoint(alias, w, h)) return false
                publishPostProbeRecentsWithRetries(
                    service = service,
                    alias = alias,
                    tagPrefix = "publish_post_recents_layout_a_confirmed",
                    hardStopLabel = "发作品 Recents 布局A已确认 OCR",
                    clickRecents = true,
                    clickPictures = true,
                )
            }
            PublishPostEntryLayout.B -> {
                devOp(alias, "发作品：本任务已确认布局 B，先左侧备用点，再执行 Recents→Pictures OCR 点击")
                if (!publishPostTapLayoutBFallback(alias, w, postTap)) return false
                publishPostProbeRecentsWithRetries(
                    service = service,
                    alias = alias,
                    tagPrefix = "publish_post_recents_layout_b_confirmed",
                    hardStopLabel = "发作品 Recents 布局B已确认 OCR",
                    clickRecents = true,
                    clickPictures = true,
                )
            }
            PublishPostEntryLayout.UNKNOWN ->
                publishPostProbeAndCacheEntryLayout(service, alias, w, h, postTap)
        }
    }

    /**
     * 「发布作品」单条素材的核心选择流程。
     *
     * 阶段顺序：
     * 1) 底部入口（上一段：资料比例点 →（仅本任务第 1 条）Continue / While using → …；首条试探 A/B 并缓存；布局确认后每条仍执行 Recents→Pictures OCR 点击）
     * 2) 图库选素材：第 1 张 X≈16%*W、Y≈30%*H，第 2～3 相对第 1；第 4 张 X 同第 1、Y≈49%*H，第 5 屏宽中点且 Y 同第 4（最多 5 条）
     * 3) 图库点选后等待 [PUBLISH_POST_AFTER_GALLERY_DELAY_MS]，再 OCR 定位 Next（最多 [PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES] 次，间隔 500~1000ms）；同一轮命中时记录 Recents / Videos 分界 Y 等
     * 4) 通过 OCR 反复校验 Your Story 是否出现，最多 7 次，间隔 1s；若失败则整条任务失败重来
     * 5) 点击第一次 Next
     * 6) [已禁用] Your Story 命中后，说明素材加载成功，点击音频位置
     * 7) [已禁用] OCR 定位并点击 Sound
     * 8) [已禁用] 再次点击音频位置
     * 9) 沿用第一次 Next 的坐标再次点击 Next
     * 10) OCR 定位 description（忽略大小写包含即命中）并点击聚焦，最多 3 次；均未命中则认定步骤混乱并重启 TikTok
     * 11) 使用运行配置中的 [AppConfig.comment_texts] 随机文案输入
     * 12) OCR 定位 Done（偏下）或屏幕上方含 post 的 Post（优先 Done）并点击；点 Done 后再点一次第一次 Next；点顶部 Post 则等价 Done+Next+确认 Post，跳过后续 Next
     * 13)（仅 Done 路径）再次点击第一次 Next 的坐标，进入后续确认页
     * 14) 两种布局末次点击后 OCR 判定发布完成：Done 布局点完 Next 后不再 OCR 到 Next；Post 布局点完顶部 Post 后不再 OCR 到该 Post；
     *     正面信号（底栏≥2 等）仍可作为提前完成路径
     * 15) 仅当日首条且已确认发布成功：按序尝试可选权限弹窗（Post Now / Only this time×2 / Don't allow / 底部 Cancel），未命中不判失败
     */
    private suspend fun runPublishPostPickSoundCaptionAndMaybeDialogs(
        service: Context,
        config: AppConfig,
        alias: String,
        videoIndex: Int,
        needsFirstOfDayPermissionDialogs: Boolean,
    ): Boolean {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val galleryTap = publishPostGalleryMediaTap(w, h, videoIndex)
        val galleryRule =
            when (videoIndex) {
                0 -> "第1张基准（X≈16%*W，Y≈30%*H）"
                1 -> "第2张（屏X中点，Y同第1）"
                2 -> "第3张（与第1关于竖直中线对称）"
                3 -> "第4张（X同第1，Y≈49%*H）"
                4 -> "第5张（屏X中点，Y同第4）"
                else -> "第${videoIndex + 1}张回退第1张规则"
            }
        devOp(
            alias,
            "发作品 第${videoIndex + 1} 条：图库选素材 tap (${galleryTap.x},${galleryTap.y})（屏 ${w}x${h}；$galleryRule）",
        )
        if (!runSuCommand("input tap ${galleryTap.x} ${galleryTap.y}")) return false
        devOp(alias, "发作品：图库点选后等待 ${PUBLISH_POST_AFTER_GALLERY_DELAY_MS}ms 再 OCR Next")
        delay(PUBLISH_POST_AFTER_GALLERY_DELAY_MS)

        var nextHit: StrictLabelHit? = null
        for (nextPass in 1..PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES) {
            if (hardStopOrNull(alias, "发作品 Next OCR")) return false
            if (nextPass > 1) {
                val retryDelayMs = Random.nextLong(
                    PUBLISH_POST_FIRST_NEXT_OCR_RETRY_DELAY_MIN_MS,
                    PUBLISH_POST_FIRST_NEXT_OCR_RETRY_DELAY_MAX_MS + 1,
                )
                devOp(alias, "发作品：Next OCR 第 $nextPass/${PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES} 次，间隔 ${retryDelayMs}ms")
                delay(retryDelayMs)
            } else {
                devOp(alias, "发作品：Next OCR 第 1/${PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES} 次")
            }
            val (vision, err) = mlKitRecognizeFullScreen(
                service,
                "publish_post_first_next_${videoIndex}_$nextPass",
            )
            if (vision == null) {
                devOp(alias, "发作品：Next OCR 第 $nextPass/${PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES} 次识别失败 $err")
                if (isMlKitHardStopError(err)) return false
                continue
            }
            devOp(
                alias,
                "发作品：Next OCR 第 $nextPass/${PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES} 次摘要=" +
                    buildProfileEditOcrPreview(vision).take(420),
            )
            val hit = findStrictExactLabelHit(vision, "Next")
            if (hit != null) {
                nextHit = hit
                devOp(alias, "发作品：Next 第 $nextPass/${PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES} 次已命中")
                break
            }
            devOp(alias, "发作品：Next OCR 第 $nextPass/${PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES} 次未命中")
        }
        val resolvedNext = nextHit ?: run {
            devOp(
                alias,
                "发作品：Next 连续 ${PUBLISH_POST_FIRST_NEXT_OCR_MAX_PASSES} 次 OCR 均未命中，本条失败",
            )
            return false
        }
        val tapNext0 = coerceTapToScreen(resolvedNext.point, w, h)
        devOp(alias, "发作品：第一次 Next 坐标记位 (${tapNext0.x},${tapNext0.y})")
        if (!runSuCommand("input tap ${tapNext0.x} ${tapNext0.y}")) return false

        var yourStorySeen = false
        var yourStoryAttempts = 0
        while (yourStoryAttempts < 7 && !yourStorySeen) {
            if (hardStopOrNull(alias, "发作品 Your Story OCR")) return false
            yourStoryAttempts++
            if (yourStoryAttempts > 1) delay(1000)
            val (vStory, errStory) = mlKitRecognizeFullScreen(service, "publish_post_your_story_${videoIndex}_$yourStoryAttempts")
            if (vStory == null) {
                devOp(alias, "发作品：Your Story OCR 第 $yourStoryAttempts/7 次失败 $errStory")
                if (isMlKitHardStopError(errStory)) return false
                continue
            }
            devOp(alias, "发作品：Your Story OCR 第 $yourStoryAttempts/7 次摘要=${buildProfileEditOcrPreview(vStory).take(420)}")
            if (findStrictExactLabelHit(vStory, "Your Story") != null) {
                yourStorySeen = true
                devOp(alias, "发作品：已定位 Your Story，确认素材加载成功")
            } else {
                devOp(alias, "发作品：Your Story OCR 第 $yourStoryAttempts/7 次未命中")
            }
        }
        if (!yourStorySeen) {
            devOp(alias, "发作品：Your Story 7 次均未命中，判定任务失败，整条流程重来")
            return false
        }

        // 步骤 6～8 已禁用：固定音频位置 ×2 + OCR 点 Sound
        devOp(alias, "发作品：已跳过固定音频点与 Sound OCR（步骤 6～8 已注释）")
        /*
        val audioTap = fixedAudioTapPoint(service)
        devOp(alias, "发作品：第一次点击固定音频位置 (${audioTap.x},${audioTap.y})（屏幕正中 X，顶部 1/10 处 Y）")
        if (!runSuCommand("input tap ${audioTap.x} ${audioTap.y}")) return false
        delay(550)

        devOp(alias, "发作品：准备点击 Sound")
        if (!publishPostOcrTapByFinder(
                service,
                alias,
                "publish_post_sound_${videoIndex}",
                "Sound",
                6,
            ) { findBottommostStrictExactLabelHit(it, "Sound") }
        ) {
            devOp(alias, "发作品：Sound OCR 失败")
            return false
        }

        devOp(alias, "发作品：Sound 已点，准备第二次点击固定音频位置 (${audioTap.x},${audioTap.y})")
        if (!runSuCommand("input tap ${audioTap.x} ${audioTap.y}")) return false
        delay(550)
        */

        delay(1000)
        devOp(alias, "发作品：准备复用第一次 Next 坐标 (${tapNext0.x},${tapNext0.y}) 再点一次 Next")
        if (!runSuCommand("input tap ${tapNext0.x} ${tapNext0.y}")) return false
        delay(600)

        val (vCap, errCap) = mlKitRecognizeFullScreen(service, "publish_post_caption_screen_${videoIndex}")
        if (vCap == null) {
            devOp(alias, "发作品：文案屏 OCR 失败 $errCap，准备重启 TikTok 后重新发布")
            return publishPostFail(alias, "发作品文案屏 OCR", tryRestart = !isMlKitHardStopError(errCap))
        }
        devOp(alias, "发作品：文案屏 OCR 摘要=${buildProfileEditOcrPreview(vCap).take(420)}")
        val captionPool = config.comment_texts.map { it.trim() }.filter { it.isNotBlank() }
        val phrase = captionPool.randomOrNull() ?: "Nice"
        devOp(alias, "发作品：准备输入文案，来源=config.comment_texts，候选数=${captionPool.size}，选中文案 len=${phrase.length} preview=${phrase.take(40)}")
        devOp(alias, "发作品：输入文案前 OCR 定位 description 并点击（至多 3 次）")
        if (!publishPostOcrTapByFinder(
                service,
                alias,
                "publish_post_description_${videoIndex}",
                "description 聚焦",
                3,
            ) { findLabelHitContainingIgnoreCase(it, "description") }
        ) {
            devOp(
                alias,
                "发作品：description OCR 连续 3 次未命中，认定当前作品发布步骤混乱，关闭并重新打开 TikTok",
            )
            return publishPostFail(alias, "发作品 description OCR", tryRestart = true)
        }
        devOp(alias, "发作品：description 已点击，等待页面/键盘稳定")
        delay(1000)

        if (!tryInputCommentText(alias, phrase)) {
            devOp(alias, "发作品：文案 input 失败，准备重启 TikTok 后重新发布")
            return publishPostFail(alias, "发作品文案 input", tryRestart = true)
        }
        delay(350)
        val captionConfirmRetryLimit = 4
        var captionConfirmOcrSummary = ""
        var captionConfirm: PublishCaptionConfirmHit? = null
        for (pass in 1..captionConfirmRetryLimit) {
            if (hardStopOrNull(alias, "发作品 Done/Post OCR")) return false
            if (pass > 1) delay(1000)
            val (vCapConfirm, errCapConfirm) =
                mlKitRecognizeFullScreen(service, "publish_post_caption_confirm_${videoIndex}_$pass")
            if (vCapConfirm == null) {
                captionConfirmOcrSummary = "(识别失败)$errCapConfirm"
                devOp(
                    alias,
                    "发作品：Done/Post OCR 第 $pass/$captionConfirmRetryLimit 次截屏识别失败：$errCapConfirm",
                )
                if (isMlKitHardStopError(errCapConfirm)) return false
                continue
            }
            val retryText = buildProfileEditOcrPreview(vCapConfirm).take(420)
            captionConfirmOcrSummary = retryText
            devOp(alias, "发作品：Done/Post OCR 第 $pass/$captionConfirmRetryLimit 次摘要=$retryText")
            captionConfirm = findPublishCaptionConfirmHit(vCapConfirm, h)
            if (captionConfirm != null) {
                val label = if (captionConfirm.kind == PublishCaptionConfirmKind.DONE) "Done" else "Post"
                val hitExtra =
                    if (captionConfirm.kind == PublishCaptionConfirmKind.POST) {
                        val r = captionConfirm.hit.rect
                        "（屏上方含 post，bbox=(${r.left},${r.top})-(${r.right},${r.bottom})）"
                    } else {
                        ""
                    }
                devOp(
                    alias,
                    "发作品：Done/Post OCR 第 $pass/$captionConfirmRetryLimit 次命中「$label」$hitExtra",
                )
                break
            }
            devOp(alias, "发作品：Done/Post OCR 第 $pass/$captionConfirmRetryLimit 次未命中，等待 1s 后继续重试")
        }
        if (captionConfirmOcrSummary.isNotEmpty()) {
            devOp(alias, "发作品：Done/Post OCR 最后摘要=$captionConfirmOcrSummary")
        }
        val confirmed = captionConfirm
            ?: run {
                devOp(
                    alias,
                    "发作品：Done/Post OCR 连续 $captionConfirmRetryLimit 次未命中，准备重启 TikTok 后重新发布",
                )
                return publishPostFail(alias, "发作品 Done/Post OCR 未命中", tryRestart = true)
            }
        val layoutKind = confirmed.kind
        val tapConfirm = coerceTapToScreen(confirmed.hit.point, w, h)
        if (!runSuCommand("input tap ${tapConfirm.x} ${tapConfirm.y}")) return false
        when (layoutKind) {
            PublishCaptionConfirmKind.DONE -> {
                delay(500)
                val tapNextAgain = tapNext0
                devOp(
                    alias,
                    "发作品：Done 已点击，准备复用第一次 Next 坐标 (${tapNextAgain.x},${tapNextAgain.y}) 再点一次 Next",
                )
                if (!runSuCommand("input tap ${tapNextAgain.x} ${tapNextAgain.y}")) return false
                delay(800)
            }
            PublishCaptionConfirmKind.POST -> {
                devOp(
                    alias,
                    "发作品：文案屏 Post 已点击（等价 Done+Next+确认 Post），等待 OCR 确认顶部 Post 已消失",
                )
                delay(800)
            }
        }

        val layoutConfirmLabel = publishPostLayoutConfirmControlLabel(layoutKind)
        var itemPublishSignalsMet = false
        for (confirmAttempt in 0..PUBLISH_POST_POST_TAP_RETRY_MAX) {
            if (confirmAttempt > 0) {
                when (layoutKind) {
                    PublishCaptionConfirmKind.DONE -> {
                        devOp(
                            alias,
                            "发作品：Done 路径第 $confirmAttempt 次补点 Next（${tapNext0.x},${tapNext0.y}）",
                        )
                        if (!runSuCommand("input tap ${tapNext0.x} ${tapNext0.y}")) return false
                    }
                    PublishCaptionConfirmKind.POST -> {
                        devOp(
                            alias,
                            "发作品：Post 路径第 $confirmAttempt 次补点顶部 Post（${tapConfirm.x},${tapConfirm.y}）",
                        )
                        if (!runSuCommand("input tap ${tapConfirm.x} ${tapConfirm.y}")) return false
                    }
                }
                delay(800)
            }
            if (
                verifyPublishPostLayoutConfirmTapSucceeded(
                    service,
                    alias,
                    videoIndex,
                    h,
                    layoutKind,
                    maxPasses = if (layoutKind == PublishCaptionConfirmKind.DONE) 5 else 4,
                    phaseLog = "${layoutConfirmLabel}点击后发布完成校验",
                    fileTagPrefix = "publish_post_layout_confirm_${videoIndex}_a$confirmAttempt",
                )
            ) {
                itemPublishSignalsMet = true
                break
            }
        }
        if (!itemPublishSignalsMet) {
            devOp(
                alias,
                "发作品：${layoutConfirmLabel} 点击后 OCR 仍可见 $layoutConfirmLabel，进行收尾发布完成校验",
            )
        }

        if (!itemPublishSignalsMet &&
            !verifyPublishPostLayoutConfirmTapSucceeded(
                service,
                alias,
                videoIndex,
                h,
                layoutKind,
                phaseLog = "收尾发布完成校验",
                fileTagPrefix = "publish_post_layout_confirm_final_${videoIndex}",
            )
        ) {
            return false
        }

        if (needsFirstOfDayPermissionDialogs &&
            !runPublishPostFirstDailyOptionalPermissionDialogs(
                service,
                alias,
                videoIndex,
                h,
                layoutKind,
            )
        ) {
            return false
        }

        devOp(alias, "发作品 第${videoIndex + 1} 条：本段完成，检查身份验证")
        if (
            !runPostActionIdentityVerificationProbe(
                ctx = service,
                alias = alias,
                tagPrefix = "publish_post_after_$videoIndex",
                phaseLog = "发作品后身份验证",
            )
        ) {
            devOp(alias, "发作品 第${videoIndex + 1} 条：身份验证未完成")
            return false
        }
        return true
    }

    /**
     * 管理台「发布作品」：冷启 TikTok → 资料比例点 →（仅本任务第 1 条）Continue / While using → 右下固定点 →（布局未确认时）Allow all → Recents/布局 B；
     * 再按 `Pictures` 目录树内图片+视频总数循环发作品（su find 递归统计，**不含** `Pictures/AutoTraces`）；
     * 单次任务发布条数 = min(素材条数, PUBLISH_POST_MAX_ITEMS_PER_TASK)（当前上限为 5），超出部分本次不发并在日志说明；
     * 任务开始（或单条失败重试）时冷启 TikTok；条与条之间不冷启（上一条底栏校验通过后直接进下一条入口）。
     * 仅该账号当日首条在确认发布成功后按序尝试 Post Now / Only this time（点后二次 OCR 再点可选）/ Don't allow / 底部 Cancel（可选，未命中不判失败）。
     * 单条是否发布成功：Done 布局点完 Next / Post 布局点完顶部 Post 后，OCR 不再命中对应控件（或出现正面信号）。
     * 图库统计为 0 时不执行发布并返回 false。
     * 新任务（无续发断点）时：先清理图库（Pictures + Camera 相册）→ 再按运行配置拉素材 → 再统计 `Pictures` 并发作品；**全部成功后不删图库**（素材保留至下次新任务开始前清理）。
     * 素材准备阶段不暂停涨粉；图库确认有可用素材后才进入发布独占阶段（暂停涨粉并执行 TikTok 发布）。
     * 中途失败时 [PrefsStore] 记录已成功条数，**仅在同一次云端 publish_post_start 指令内**从对应图库坐标序号续发（不逐个删素材文件；续发不重复清理/拉取）。
     * 用户硬停或收到新的发作品指令时清除断点；断点不可跨指令复用。
     * 断点条数大于本次计划条数且图库仍有素材时，视为断点与图库不一致，清断点从第 1 条重发（不删图）。
     */
    private suspend fun runPublishPostEntryFlow(service: Context, packageName: String, config: AppConfig): Boolean =
        withAccountStatusOcrWatch {
        val alias = PrefsStore.getAlias() ?: "unknown"
        lastPublishPostEntryFailureReason = null
        val accountKey =
            PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() } ?: alias
        val resumeStartIdx = PrefsStore.getPublishPostResumeNextIndex(accountKey)
        val targetPublishCount = PrefsStore.getPublishPostTargetCount()
        if (resumeStartIdx <= 0) {
            devOp(alias, "发作品：清理设备图库 Pictures + DCIM/Camera（拉取新素材前）")
            deleteAllPicturesOnDevice(alias, "发作品", includeDcimCameraAlbum = true)
            val fetchCount = (targetPublishCount ?: config.publish_post_material_fetch_count).coerceIn(0, 20)
            if (fetchCount > 0) {
                if (!publishPostFetchMaterialsFromPc(service, alias, fetchCount)) {
                    lastPublishPostEntryFailureReason =
                        lastPublishPostEntryFailureReason ?: "PC 素材拉取失败"
                    return false
                }
            } else {
                devOp(alias, "发作品：运行配置 publish_post_material_fetch_count=0，跳过本机素材拉取")
            }
        } else {
            devOp(
                alias,
                "发作品：续发断点已成功 $resumeStartIdx 条（指令=${PrefsStore.getPublishPostTaskOpId()}），跳过图库清理与素材拉取",
            )
        }
        val galleryStats = publishPostResolveGalleryMaterialStats()
        val galleryMaterialCount = galleryStats.resolvedCount
        if (galleryMaterialCount <= 0) {
            lastPublishPostEntryFailureReason = "图库无可用素材"
            devOp(
                alias,
                "发作品：图库无可用素材，已中止本次任务。明细：Pictures（不含 AutoTraces）图片=${galleryStats.picturesRootImageCount}，" +
                    "视频=${galleryStats.picturesRootVideoCount}（合计=${galleryStats.resolvedCount}）。" +
                    "请确认 PC 去重服务可用、运行配置 publish_post_material_fetch_count>0，且素材可写入 ${PUBLISH_POST_PICTURES_PATH}（非 AutoTraces 子目录；扩展名 jpg/jpeg/png/webp/gif/mp4/mkv/3gp/webm）。",
            )
            return false
        }
        val plannedMax = targetPublishCount ?: PUBLISH_POST_MAX_ITEMS_PER_TASK
        val publishRounds = minOf(galleryMaterialCount, plannedMax.coerceAtLeast(1))
        val needsPerm = PrefsStore.shouldRunPublishPostFirstDailyPermissionOcr(accountKey)
        var startIdx = resumeStartIdx
        if (startIdx > publishRounds) {
            devOp(
                alias,
                "发作品：断点已成功 $startIdx 条，大于本次计划 $publishRounds 条（图库仍有 $galleryMaterialCount 个素材），" +
                    "断点与图库不一致，清断点并从第 1 条重新发",
            )
            PrefsStore.clearPublishPostResumeProgress(accountKey)
            startIdx = 0
        } else if (startIdx >= publishRounds) {
            devOp(
                alias,
                "发作品：断点记录已成功 $startIdx 条，已达本次计划 $publishRounds 条，视为本轮已完成并清理断点",
            )
            PrefsStore.clearPublishPostResumeProgress(accountKey)
            devOp(alias, "发作品：断点记录已达本次计划，视为本轮已完成（不删除图库，下次新任务开始前再清）")
            return true
        }
        val limitHint =
            if (targetPublishCount != null) {
                "云端指定 $targetPublishCount 条"
            } else {
                "上限 ${PUBLISH_POST_MAX_ITEMS_PER_TASK} 条"
            }
        if (galleryMaterialCount > publishRounds) {
            devOp(
                alias,
                "发作品：图库素材共 $galleryMaterialCount 个（图片=${galleryStats.picturesRootImageCount}，" +
                    "视频=${galleryStats.picturesRootVideoCount}），本次仅发前 $publishRounds 条（$limitHint）；" +
                    "当日首条权限 OCR=$needsPerm（账号键=$accountKey）；" +
                    if (startIdx > 0) "续发：已成功 $startIdx 条，从第 ${startIdx + 1} 条继续" else "续发：从第 1 条开始",
            )
        } else {
            devOp(
                alias,
                "发作品：图库素材共 $galleryMaterialCount 个（图片=${galleryStats.picturesRootImageCount}，" +
                    "视频=${galleryStats.picturesRootVideoCount}），本次发 $publishRounds 条（$limitHint）；" +
                    "当日首条权限 OCR=$needsPerm（账号键=$accountKey）；" +
                    if (startIdx > 0) "续发：已成功 $startIdx 条，从第 ${startIdx + 1} 条继续" else "续发：从第 1 条开始",
            )
        }
        if (!PublishPostGrowthGate.enterExclusivePublishPhase(alias)) {
            return false
        }
        val launchWaitMs = config.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
        publishPostEntryLayout = publishPostLoadDeviceEntryLayout(alias)
        if (resumeStartIdx <= 0) {
            if (!publishPostColdLaunch(service, packageName, alias, config)) return false
        } else {
            devOp(alias, "发作品：续发断点进入，跳过任务头冷启（已成功 $resumeStartIdx 条）")
        }
        for (idx in startIdx until publishRounds) {
            val needsEntryWarmup = idx > startIdx || (resumeStartIdx > 0 && idx == startIdx)
            if (needsEntryWarmup) {
                devOp(
                    alias,
                    "发作品：等待 ${PUBLISH_POST_INTER_ITEM_DELAY_MS}ms 后进入第 ${idx + 1} 条创作入口" +
                        if (idx > startIdx) "（上一条已成功，不冷启）" else "（续发断点，不冷启）",
                )
                delay(PUBLISH_POST_INTER_ITEM_DELAY_MS)
                if (!ensureTikTokInForeground(service, alias, "发作品条间续作")) {
                    devOp(alias, "发作品 第 ${idx + 1}/$publishRounds 条：条间恢复拉起未通过，尝试冷启后继续")
                    if (!publishPostColdLaunch(service, packageName, alias, config)) {
                        devOp(alias, "发作品 第 ${idx + 1}/$publishRounds 条：条间冷启失败，保存断点")
                        PrefsStore.setPublishPostResumeNextIndex(accountKey, idx)
                        lastPublishPostEntryFailureReason = "条间冷启失败"
                        return false
                    }
                }
            }
            val videoIndex = idx
            if (hardStopOrNull(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条")) return false
            var published = false
            var lastPublishAttempts = 0
            var sameItemPasses = 0
            while (!published && sameItemPasses < PUBLISH_POST_SAME_ITEM_RESUME_MAX_PASSES) {
                sameItemPasses++
                if (sameItemPasses > 1) {
                    devOp(
                        alias,
                        "发作品 第 ${videoIndex + 1}/$publishRounds 条：同任务断点续发第 $sameItemPasses/$PUBLISH_POST_SAME_ITEM_RESUME_MAX_PASSES 次，" +
                            "等待 ${PUBLISH_POST_SAME_ITEM_RESUME_DELAY_MS}ms 后重试",
                    )
                    delay(PUBLISH_POST_SAME_ITEM_RESUME_DELAY_MS)
                    if (!ensureTikTokInForeground(service, alias, "发作品同条续发")) {
                        if (!publishPostColdLaunch(service, packageName, alias, config)) {
                            devOp(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条：同条续发冷启失败")
                            if (isHardStopActive()) return false
                            continue
                        }
                    }
                }
                var publishAttempts = 0
                while (!published && publishAttempts < 3) {
                    if (hardStopOrNull(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条重试")) return false
                    publishAttempts++
                    lastPublishAttempts = publishAttempts
                    if (publishAttempts > 1) {
                        devOp(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条：第 $publishAttempts 次重试，重新拉起 TikTok 后继续同一条作品")
                        if (!publishPostColdLaunch(service, packageName, alias, config)) {
                            devOp(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条：重启 TikTok 失败，继续等待下一轮重试")
                            if (isHardStopActive()) return false
                            continue
                        }
                    }
                    if (
                        !publishPostTapProfileRatioWhileUsingAndLeftBottom(
                            service,
                            alias,
                            launchWaitMs,
                            skipLaunchWait = true,
                            isFirstItemInTask = startIdx == 0 && videoIndex == 0,
                        )
                    ) {
                        if (publishPostEntryLayout != PublishPostEntryLayout.UNKNOWN) {
                            if (videoIndex == 0 && startIdx == 0) {
                                devOp(alias, "发作品：首条入口失败，清除本机布局缓存后重新试探 A/B")
                                publishPostEntryLayout = PublishPostEntryLayout.UNKNOWN
                                publishPostClearDeviceEntryLayout(alias)
                            } else {
                                publishPostEntryLayout = publishPostLoadDeviceEntryLayout(alias)
                                devOp(
                                    alias,
                                    "发作品：第 ${videoIndex + 1} 条入口失败，保留本机布局缓存=$publishPostEntryLayout 后重试",
                                )
                            }
                        }
                        devOp(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条：资料入口阶段失败（CREATE/POST 或 Recents/布局B），准备同一条重试")
                        if (isHardStopActive()) return false
                        continue
                    }
                    val runDialogs = needsPerm && idx == 0
                    published = runPublishPostPickSoundCaptionAndMaybeDialogs(service, config, alias, videoIndex, runDialogs)
                    if (!published) {
                        if (isHardStopActive()) return false
                        devOp(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条：发布失败，已记录为第 ${publishAttempts} 次失败，稍后重试同一条作品")
                    }
                }
            }
            if (!published) {
                PrefsStore.setPublishPostResumeNextIndex(accountKey, idx)
                devOp(
                    alias,
                    "发作品：第 ${videoIndex + 1}/$publishRounds 条连续 $lastPublishAttempts 次内层重试且" +
                        "同任务续发 $sameItemPasses 次仍失败；进度已保存：已成功 $idx 条，下次从第 ${idx + 1} 条继续",
                )
                return false
            }
            PrefsStore.setPublishPostResumeNextIndex(
                accountKey,
                idx + 1,
                markFirstDailyPermOcrDone = needsPerm && idx == 0,
            )
            devOp(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条：发布成功（累计已成功 ${idx + 1}/$publishRounds 条）")
        }
        runCatching { publishPostTapBottomHomeAfterCompletion(service, alias) }
        PrefsStore.clearPublishPostResumeProgress(accountKey)
        PrefsStore.setPublishPostLastActualSuccessCount(publishRounds)
        devOp(alias, "发作品：本轮全部发布成功（不删除图库，下次新任务开始前再清）")
        return true
    }

    /**
     * 发作品全部条目成功后，收尾再做一次底栏 Home 点击：
     * - 最多 8 次 OCR，间隔 0.5s
     * - 每次都会顺手尝试点击 Don't allow（命中才点）
     * - 未命中 Home 不阻断主流程
     */
    private suspend fun publishPostTapBottomHomeAfterCompletion(service: Context, alias: String) {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        for (attempt in 1..PUBLISH_POST_FINAL_HOME_TAP_MAX_PASSES) {
            if (attempt > 1) delay(PUBLISH_POST_FINAL_HOME_TAP_RETRY_DELAY_MS)
            if (hardStopOrNull(alias, "发作品收尾点 Home")) return
            val (vision, err) = mlKitRecognizeFullScreen(service, "publish_post_final_home_$attempt")
            if (vision == null) {
                devOp(alias, "发作品收尾：Home OCR 第 $attempt/${PUBLISH_POST_FINAL_HOME_TAP_MAX_PASSES} 次失败：$err")
                if (isMlKitHardStopError(err)) return
                continue
            }
            runCatching {
                tapDontAllowFromVisionIfPresent(
                    ctx = service,
                    alias = alias,
                    vision = vision,
                    phaseLog = "发作品收尾",
                    useAutoLoginJitter = false,
                    markAutoLoginHandled = false,
                )
            }
            val homeHit = findBottomBandExactLabelHit(vision, h, "Home")
            if (homeHit == null) {
                devOp(alias, "发作品收尾：Home OCR 第 $attempt/${PUBLISH_POST_FINAL_HOME_TAP_MAX_PASSES} 次未命中")
                continue
            }
            val tap = coerceTapToScreen(homeHit.point, w, h)
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                devOp(alias, "发作品收尾：已点击 Home (${tap.x},${tap.y})")
                PrefsStore.setGrowthContinueFromPublishHome(true)
                return
            }
            devOp(alias, "发作品收尾：Home 命中但点击失败，第 $attempt 次重试")
        }
        devOp(alias, "发作品收尾：8 次 OCR 未完成 Home 点击，忽略并继续")
    }

    /** OCR 大小写/轻微差异兜底（主路径为 [findStrictExactAcrossAdjacentLines] 严格串）。 */
    private fun findWhileUsingTheAppOcrRelaxed(result: Text): StrictLabelHit? {
        val target = "while using the app"
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val n = line.text.trim().lowercase(Locale.US).replace("\n", " ")
                if (n == target) {
                    line.boundingBox?.let { b -> return StrictLabelHit(Point(b.centerX(), b.centerY()), b) }
                }
                for (el in line.elements) {
                    val en = el.text.trim().lowercase(Locale.US)
                    if (en == target) {
                        el.boundingBox?.let { b -> return StrictLabelHit(Point(b.centerX(), b.centerY()), b) }
                    }
                }
            }
        }
        val pairs = flattenOcrLinesWithBoxes(result)
        val n = pairs.size
        for (len in 2..6) {
            for (i in 0..n - len) {
                val slice = pairs.subList(i, i + len)
                val joined = slice.joinToString(" ") { it.first.text.trim().lowercase(Locale.US) }
                if (joined != target) continue
                val u = slice.fold(slice[0].second) { acc, pair ->
                    val r = pair.second
                    Rect(
                        minOf(acc.left, r.left),
                        minOf(acc.top, r.top),
                        maxOf(acc.right, r.right),
                        maxOf(acc.bottom, r.bottom),
                    )
                }
                return StrictLabelHit(Point(u.centerX(), u.centerY()), u)
            }
        }
        return null
    }

    private suspend fun enterMutualClickMode(
        service: Context,
        config: AppConfig,
        commentOnlySession: Boolean,
        followOnlySession: Boolean,
    ): String = withAccountStatusOcrWatch {
        val alias = PrefsStore.getAlias() ?: "unknown"
        var entryOk = false
        try {
            PrefsStore.clearMutualClickState()
            mutualSessionFollowTapCount = 0
            mutualFollowLimitReached = false
            devOp(alias, "互点模式：先关闭 TikTok，再重新打开并搜索引流目标关键词")
            for (attempt in 1..MUTUAL_ENTRY_LAUNCH_MAX_ATTEMPTS) {
                if (reopenTikTokAndOpenMutualTeamworkVideosTab(service, config, "互点模式启动·$attempt")) {
                    entryOk = true
                    break
                }
                if (attempt < MUTUAL_ENTRY_LAUNCH_MAX_ATTEMPTS) {
                    devOp(
                        alias,
                        "互点：入口未进入 teamwork Videos（第 $attempt/$MUTUAL_ENTRY_LAUNCH_MAX_ATTEMPTS 次），${2000}ms 后重试整段入口",
                    )
                    delay(Random.nextLong(2000, 3501))
                }
            }
            if (!entryOk) {
                devOp(
                    alias,
                    "互点：入口 ${MUTUAL_ENTRY_LAUNCH_MAX_ATTEMPTS} 次均未进入 teamwork Videos，跳过 Videos 互动轮次",
                )
            } else {
                enterVideosVideoByQuarterTaps(service, config)
            }
            finalizeMutualSessionLimitedExit(alias)
            val fullDoneHint = when {
                followOnlySession ->
                    "进作品→打开评论区（仅关注 TODO）→回列表（未执行左滑/点赞/发评）"
                commentOnlySession ->
                    "进作品→点赞/收藏/互点评论→回列表（未执行左滑与作者主页/Followers）"
                else ->
                    "进作品→左滑→回作品→点赞/收藏/互点评论→回列表"
            }
            return when {
                entryOk -> "互点会话已收尾：已执行 teamwork Videos 互动（$fullDoneHint）"
                else -> "互点会话已收尾：入口未进入 Videos，已强停 TikTok（未判为流程失败）"
            }
        } finally {
            PrefsStore.setMutualCommentOnlySession(false)
            PrefsStore.setMutualFollowOnlySession(false)
        }
    }

    /** @return 是否已恢复前台并完成 Search 点击 */
    private suspend fun clickSearchButtonAndWait(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        return withTikTokGuard(alias, "点击 Search") {
            if (!ensureTikTokInForeground(service, alias, "点击 Search")) return@withTikTokGuard false
            val metrics = service.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val searchRatioX = 0.92f
            val searchRatioY = 0.09f
            val searchX = width * searchRatioX
            val searchY = height * searchRatioY
            runSuCommand("input tap ${searchX.toInt()} ${searchY.toInt()}")
            delay(2500)
            true
        } == true
    }

    private suspend fun ensureUsersTabClicked(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "查找 Users 标签")) return false

        repeat(USERS_TAB_OCR_MAX_ATTEMPTS) { attempt ->
            if (growthYieldOrNull(alias, "查找 Users 标签")) return false
            if (attempt > 0) {
                if (!growthDelay(alias, USERS_TAB_OCR_RETRY_DELAY_MS, "Users标签OCR重试")) return false
            }
            val fastAnchor = findUsersAnchorByOcrFast(service)
            if (fastAnchor != null) {
                runSuCommand("input tap ${fastAnchor.x} ${fastAnchor.y}")
                return true
            }
            if (!growthDelay(alias, USERS_TAB_OCR_FAST_FALLBACK_DELAY_MS, "Users标签OCR兜底")) return false
            val fallbackAnchor = findUsersAnchorByOcr(service)
            if (fallbackAnchor != null) {
                devOp(alias, "OCR 定位 Users 标签（兜底），点击")
                runSuCommand("input tap ${fallbackAnchor.x} ${fallbackAnchor.y}")
                return true
            }
            val obstructed = isUsersTabLikelyObstructed(service)
            if (obstructed) {
                devOp(
                    alias,
                    "Users 标签 OCR 未命中：疑似被弹窗/Feed 遮挡（如 Follow back），" +
                        "${USERS_TAB_OCR_RETRY_DELAY_MS / 1000}s 后重试 OCR（第 ${attempt + 1}/$USERS_TAB_OCR_MAX_ATTEMPTS 轮）",
                )
            } else {
                devOp(alias, "Users 标签 OCR 未命中（第 ${attempt + 1}/$USERS_TAB_OCR_MAX_ATTEMPTS 轮）")
            }
        }
        devOp(alias, "OCR 未找到 Users 标签")
        return false
    }

    /**
     * 搜索结果页顶栏 Users 不可见，但 OCR 见到 Follow back / 多条 Follow（列表层）→ 多为临时弹窗或仍停留在 Top/Videos 层。
     */
    private suspend fun isUsersTabLikelyObstructed(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "users_tab_obstruct_check.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return false
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return false
        }
        observeAccountStatusOnScreenshot(service, bitmap, "users_tab_obstruct_check")
        val topHeight = (bitmap.height * 0.38f).toInt().coerceAtLeast(1)
        val topCrop = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, topHeight)
        val fullImage = InputImage.fromBitmap(bitmap, 0)
        val topImage = InputImage.fromBitmap(topCrop, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            fun isUsersTabWord(t: String): Boolean {
                val w = t.trim()
                return w.equals("Users", ignoreCase = true) ||
                    w.equals("User", ignoreCase = true) ||
                    w.equals("用户", ignoreCase = true)
            }
            val topResult = suspendCancellableCoroutine { cont ->
                recognizer.process(topImage)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val topTexts = topResult.textBlocks.flatMap { it.lines }
                .flatMap { line -> line.elements.map { it.text.trim() } + listOf(line.text.trim()) }
                .filter { it.isNotEmpty() }
            if (topTexts.any(::isUsersTabWord) ||
                topTexts.any { it.contains("Users", ignoreCase = true) || it.contains("用户") }
            ) {
                return false
            }
            val fullResult = suspendCancellableCoroutine { cont ->
                recognizer.process(fullImage)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val allTexts = fullResult.textBlocks.flatMap { it.lines }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
            val preview = allTexts.take(12).joinToString(" | ")
            val hasFollowBack = allTexts.any {
                it.contains("Follow back", ignoreCase = true) ||
                    it.contains("Follow Back", ignoreCase = true)
            }
            if (hasFollowBack) {
                devOp(alias, "Users 标签遮挡探测：命中 Follow back，OCR=$preview")
                return true
            }
            val followCount = fullResult.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .count { it.text.trim().equals("Follow", ignoreCase = true) }
            val tabWords = listOf("Top", "Videos", "Photos", "Shop", "Sounds")
            val tabHitCount = tabWords.count { tab -> allTexts.any { it.equals(tab, ignoreCase = true) } }
            if (followCount >= 3 && tabHitCount >= 1) {
                devOp(alias, "Users 标签遮挡探测：顶栏无 Users 但见 Follow×$followCount 与 Tab，OCR=$preview")
                return true
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            recognizer.close()
            bitmap.recycle()
            topCrop.recycle()
            screenshotFile.delete()
        }
    }

    /**
     * 搜索结果页进入 Users 标签；首次 OCR 未命中则优先导航返回回首页（或 OCR 已见底栏时强关），再重新搜索；仍失败则中止。
     */
    private suspend fun ensureUsersTabWithForceRestartRecovery(
        service: Context,
        username: String,
        isLiveModeForUsersCheck: Boolean,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "进入 Users 标签")) return false
        if (growthYieldOrNull(alias, "进入 Users 标签")) return false

        suspend fun settleAfterUsersTabClick(): Boolean {
            if (isLiveModeForUsersCheck) {
                ensureUsersTabReadyForLiveMode(service)
            } else {
                // 非主播模式：剥离无障碍后仅靠坐标+OCR，Users 列表需更充分渲染后再截屏
                delay(2800)
            }
            return true
        }

        if (ensureUsersTabClicked(service)) {
            return settleAfterUsersTabClick()
        }

        devOp(alias, "Users 标签 OCR 未命中，尝试返回首页或强关后重新进入搜索")
        if (!recoverToHomeViaBackOrRestart(service, "Users 标签恢复")) {
            devOp(alias, "Users 标签恢复：返回首页失败（导航返回与强关均未成功）")
            return false
        }
        if (!reopenSearchAndEnterUsersTab(service, username, isLiveModeForUsersCheck)) {
            devOp(alias, "Users 标签恢复：已回首页但重新搜索或进入 Users 失败")
            return false
        }
        return true
    }

    private suspend fun ensureUsersTabReadyForLiveMode(service: Context) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val firstAnchor = findUsersAnchorByOcrFast(service) ?: findUsersAnchorByOcr(service)
        if (firstAnchor == null) {
            
            delay(1500)
            return
        }

        // 第一次验收：
        // - count >= 3：成功
        // - count in 1..2：判定为“可能在加载”，额外等待 300ms 再验收一次
        // - count == 0：直接补点 Users
        delay(380)
        val firstCount = countUsersFollowButtonsByOcr(service)
        
        
        if (firstCount >= 3) {
            
            return
        }

        if (firstCount in 1..2) {
            
            delay(300)
            val secondCount = countUsersFollowButtonsByOcr(service)
            
                        if (secondCount >= 3) {
                
                return
            }
            // 二次仍不足 3，则进入补点流程
        }

        // 仅在 count==0（或 1~2 二次后仍不足）时补点
        val jitterX = (-8..8).random()
        val jitterY = (-6..6).random()
        
        runSuCommand("input tap ${firstAnchor.x + jitterX} ${firstAnchor.y + jitterY}")
        verifyUsersTabByFollowCount(service, retries = 2, minCount = 3)
    }

    private suspend fun verifyUsersTabByFollowCount(
        service: Context,
        retries: Int = 2,
        minCount: Int = 3,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        repeat(retries) { idx ->
            delay(380)
            val count = countUsersFollowButtonsByOcr(service)
            if (count >= minCount) return true
        }
        return false
    }

    private suspend fun countUsersFollowButtonsByOcr(service: Context): Int {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "users_follow_check.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return 0
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return 0
        }
        observeAccountStatusOnScreenshot(service, bitmap, "users_follow_check")
        // Users 列表的 Follow 按钮主要在中下部区域，裁剪减少顶部干扰
        val cropTop = (bitmap.height * 0.20f).toInt().coerceAtLeast(0)
        val cropHeight = (bitmap.height * 0.72f).toInt().coerceAtLeast(1)
        val safeHeight = cropHeight.coerceAtMost(bitmap.height - cropTop)
        val cropped = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, safeHeight)
        val image = InputImage.fromBitmap(cropped, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val texts = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
            val tabWords = listOf("Top", "Users", "Videos", "Photos", "Shop")
            val tabHitCount = tabWords.count { tab -> texts.any { it.contains(tab, ignoreCase = true) } }
            if (tabHitCount >= 2) {
                devOp(alias, "Followers 列表 OCR 发现标签错乱：${tabWords.filter { tab -> texts.any { it.contains(tab, ignoreCase = true) } }.joinToString(",")}")
                return -1
            }
            val followCount = result.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .count { it.text.trim().equals("Follow", ignoreCase = true) }
            
            followCount
        } catch (ex: Exception) {
            
            0
        } finally {
            recognizer.close()
            bitmap.recycle()
            cropped.recycle()
            screenshotFile.delete()
        }
    }

    /**
     * Users 标签通常在顶部 Tab 区域。只对顶部小区域做 OCR 来提速。
     * 目标：在“Search 后初期标签会移动”的阶段高频采样，稳定后立刻点击。
     */
    private suspend fun findUsersAnchorByOcrFast(service: Context): Point? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "users_ocr_fast.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return null
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return null
        }
        observeAccountStatusOnScreenshot(service, bitmap, "users_ocr_fast")
        // 仅取顶部 32%，足够覆盖 Tabs + 搜索输入框，显著减少 OCR 计算量
        val cropHeight = (bitmap.height * 0.32f).toInt().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropHeight)
        val image = InputImage.fromBitmap(cropped, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            fun isUsersWord(t: String): Boolean {
                val w = t.trim()
                return w.equals("Users", ignoreCase = true) ||
                    w.equals("User", ignoreCase = true) ||
                    w.equals("用户", ignoreCase = true)
            }
            val candidates = result.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    line.elements.mapNotNull { element ->
                        if (isUsersWord(element.text)) element.boundingBox?.let { box ->
                            Point(box.centerX(), box.centerY())
                        } else null
                    }.ifEmpty {
                        val lineText = line.text
                        if (lineText.contains("Users", ignoreCase = true) || lineText.contains("用户")) {
                            line.boundingBox?.let { box -> listOf(Point(box.centerX(), box.centerY())) } ?: emptyList()
                        } else emptyList()
                    }
                }
            }
            candidates.firstOrNull()
        } catch (ex: Exception) {
            
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
            cropped.recycle()
            screenshotFile.delete()
        }
    }

    private fun focusSearchInput(service: Context) {
        val metrics = service.resources.displayMetrics
        val x = (metrics.widthPixels * 0.38f).toInt().coerceAtLeast(1)
        val y = (metrics.heightPixels * 0.09f).toInt().coerceAtLeast(1)
        runSuCommand("input tap $x $y")
    }

    private suspend fun clearSearchInputText() {
        val alias = PrefsStore.getAlias() ?: "unknown"
        devOp(alias, "清空搜索框")
        // 方案1：优先尝试 Ctrl+A + DEL（快）
        // 部分 ROM 不支持 keycombination，失败后回退到方案3。
        runSuCommand("input keyevent 123") // KEYCODE_MOVE_END
        delay(80)
        val selectAllOk = runSuCommand("input keycombination 113 29") // CTRL + A
        delay(40)
        val delOnceOk = runSuCommand("input keyevent 67")
        if (selectAllOk && delOnceOk) {
            
            delay(80)
            return
        }

        // 方案3：兜底多次 DEL（稳）
        
        runSuCommand("input keyevent 123") // KEYCODE_MOVE_END
        delay(80)
        repeat(36) {
            runSuCommand("input keyevent 67") // KEYCODE_DEL
            delay(10)
        }
        delay(80)
    }

    private fun clickFollowersRoot(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val baseX = (metrics.widthPixels * 0.505f).toInt()
        val baseY = (metrics.heightPixels * 0.341f).toInt()
        val jitterX = Random.nextInt(-14, 15)
        val jitterY = Random.nextInt(-12, 13)
        val primaryX = (baseX + jitterX).coerceAtLeast(1)
        val primaryTapY = (baseY + jitterY).coerceAtLeast(1)
        
        devOp(alias, "点击 Followers")
        return runSuCommand("input tap $primaryX $primaryTapY")
    }

    private suspend fun clickFollowersAndVerify(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val minFollowRows = 5

        suspend fun followersListVerifiedByOcr(): Boolean {
            val rows = findFollowersEntriesByOcr(service, verificationOnly = true)
            return rows.size >= minFollowRows
        }

        /**
         * 每次 [clickFollowersRoot] 调用之后固定 2 轮 OCR（无论 su 是否成功）：
         * 每轮 OCR 前均 delay(2500) 等待列表渲染；命中 ≥ [minFollowRows] 条严格 "Follow" 即认为已在粉丝列表。
         */
        suspend fun twoRoundOcrAfterTapAttempt(): Boolean {
            delay(2500)
            if (followersListVerifiedByOcr()) {
                devOp(alias, "Followers 校验：第 1 轮 OCR 命中 ≥$minFollowRows 条 Follow，已进入粉丝列表")
                return true
            }
            delay(2500)
            if (followersListVerifiedByOcr()) {
                devOp(alias, "Followers 校验：第 2 轮 OCR 命中 ≥$minFollowRows 条 Follow，已进入粉丝列表")
                return true
            }
            return false
        }

        val clicked1 = clickFollowersRoot(service)
        if (!clicked1) {
            devOp(alias, "Followers：input tap 命令失败，先执行 2 轮 OCR（不立即重试点击）")
        }
        if (twoRoundOcrAfterTapAttempt()) {
            return true
        }

        devOp(
            alias,
            "Followers：首轮 tap 后 2 轮 OCR 未命中 ≥$minFollowRows 条 Follow，二次点击 Followers 并再执行 2 轮 OCR",
        )
        val clicked2 = clickFollowersRoot(service)
        if (!clicked2) {
            devOp(alias, "Followers：二次 tap 命令失败，仍执行 2 轮 OCR 校验")
        }
        if (twoRoundOcrAfterTapAttempt()) {
            return true
        }

        devOp(alias, "Followers：两次 tap 各附 2 轮 OCR 均未通过，判定页面混乱，优先导航返回回首页")
        val restarted = recoverToHomeViaBackOrRestart(service, "涨粉 Followers 恢复")
        devOp(alias, "涨粉 TikTok 恢复结果：${if (restarted) "success" else "failed"}")
        if (restarted) {
            delay(3000)
        }
        return false
    }

    /**
     * 互点专用：校验是否进入粉丝列表。
     * 与涨粉 [clickFollowersAndVerify] 区分：此处单次成功点击后仅 **3 轮** OCR（命中非空即可）；
     * 仍未命中则判定页面错乱并执行 [restartTikTokForRecovery]，然后返回 false。
     * 点击入口最多尝试 3 次（su 失败时重试）；涨粉为每次 tap 后固定 2 轮 OCR、需 ≥5 条 Follow，两轮 tap 周期失败后重启 TikTok。
     */
    private suspend fun mutualClickFollowersAndVerify(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val ocrPollRounds = 3

        var tapAttempts = 0
        while (tapAttempts < 3) {
            val clicked = clickFollowersRoot(service)
            if (!clicked) {
                tapAttempts++
                continue
            }

            var verified = false
            for (poll in 1..ocrPollRounds) {
                if (poll == 1) {
                    delay(2500)
                } else {
                    delay(Random.nextLong(500, 850))
                }
                val ocrRows = findFollowersEntriesByOcr(service, verificationOnly = true)
                if (ocrRows.isNotEmpty()) {
                    verified = true
                    break
                }
            }

            if (verified) {
                return true
            }

            devOp(
                alias,
                "互点 Followers：三轮 OCR 仍未命中粉丝列表，判定页面错乱，优先导航返回回首页",
            )
            val restarted = recoverToHomeViaBackOrRestart(service, "互点 Followers 恢复")
            devOp(alias, "互点 TikTok 恢复结果：${if (restarted) "success" else "failed"}")
            if (restarted) {
                delay(3000)
            }
            return false
        }

        devOp(alias, "互点 Followers：点击入口连续失败（3 次 su/tap），未进入列表校验")
        return false
    }

    private fun captureToCache(service: Context, name: String): Bitmap? {
        val screenshotFile = File(service.cacheDir, name)
        return try {
            if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return null
            BitmapFactory.decodeFile(screenshotFile.absolutePath)
        } catch (ex: Exception) {
            null
        } finally {
            screenshotFile.delete()
        }
    }


    /** 帧差日志 tag 可能含中文，仅去掉文件名非法字符。 */
    private fun sanitizeFrameDiffTagForFilename(tag: String): String {
        val t = tag.trim().ifBlank { "frame_diff" }
        return t.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(72)
    }

    /**
     * 涨粉帧差未通过时：将参与对比的两张全屏截图写入系统图库（Pictures/AutoTraces），便于肉眼对照。
     * 仅失败时调用；成功路径不写图库。
     */
    private suspend fun saveGrowthFrameDiffFailureScreensToGallery(
        ctx: Context,
        beforeFullScreen: Bitmap,
        afterFullScreen: Bitmap,
        motionTagForLog: String,
    ) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val appCtx = ctx.applicationContext
        val ts = System.currentTimeMillis()
        val safeTag = sanitizeFrameDiffTagForFilename(motionTagForLog).take(40)
        val pairId = "${ts}_${Random.nextInt(1000, 9999)}"
        val beforeName = "growth_fd_fail_${pairId}_before_$safeTag.png"
        val afterName = "growth_fd_fail_${pairId}_after_$safeTag.png"
        val (bOk, aOk) = withContext(Dispatchers.IO) {
            val b = savePngToPicturesAutoTraces(appCtx, beforeFullScreen, beforeName)
            val a = savePngToPicturesAutoTraces(appCtx, afterFullScreen, afterName)
            b to a
        }
        devOp(
            alias,
            "帧差未通过：已尝试保存前后全屏截图至图库 Pictures/AutoTraces，pair=$pairId before=${if (bOk) "ok" else "fail"} after=${if (aOk) "ok" else "fail"} tag=$motionTagForLog",
        )
    }

    private fun savePngToPicturesAutoTraces(ctx: Context, bitmap: Bitmap, displayName: String): Boolean {
        return runCatching {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AutoTraces")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            val wrote = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                true
            } ?: false
            if (!wrote) {
                runCatching { resolver.delete(uri, null, null) }
                return@runCatching false
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }

    private fun runSuCommand(command: String): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val suCommand = resolveSuCommand(alias)
        if (suCommand == null) {
            
            return false
        }
        return try {
            val execArgs = if (suCommand.useShell) {
                val inner =
                    if (suCommand.useMountMaster) "${suCommand.path} -mm -c \"$command\""
                    else "${suCommand.path} -c \"$command\""
                arrayOf("sh", "-c", inner)
            } else {
                if (suCommand.useMountMaster) {
                    arrayOf(suCommand.path, "-mm", "-c", command)
                } else {
                    arrayOf(suCommand.path, "-c", command)
                }
            }
            val process = Runtime.getRuntime().exec(execArgs)
            // 并行排空 stdout/stderr，避免 stderr 写满缓冲区导致 su/screencap 永久阻塞（表现为无后续日志）
            val tOut = thread(start = true, name = "su-out") {
                runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            }
            val tErr = thread(start = true, name = "su-err") {
                runCatching { process.errorStream.bufferedReader().use { it.readText() } }
            }
            val exitCode = process.waitFor()
            tOut.join(120_000)
            tErr.join(120_000)
            exitCode == 0
        } catch (ex: Exception) {
            
            cachedSuCommand = null
            false
        }
    }

    private fun publishPostFindExcludeAutoTracesClause(): String =
        "! -path '*/${PUBLISH_POST_PICTURES_EXCLUDE_SUBDIR}/*'"

    /**
     * 与 adb 一致：`find /storage/emulated/0/Pictures -type f … -iname '*.ext' … | wc -l`。
     * 发作品统计时排除 `Pictures/AutoTraces`；使用 `{ find …; find …; }` 合并扩展名。
     */
    private fun publishPostCountPicturesMediaFilesViaSu(exts: List<String>): Int {
        if (exts.isEmpty()) return 0
        val quotedRoot = shellEscape(PUBLISH_POST_PICTURES_PATH)
        val exclude = publishPostFindExcludeAutoTracesClause()
        val findParts = exts.joinToString(" ; ") { ext ->
            "find $quotedRoot -type f ! -path '*/.*' $exclude -iname '*.$ext'"
        }
        val cmd = "{ $findParts; } 2>/dev/null | wc -l"
        val out = runSuCommandWithOutput(cmd)?.trim().orEmpty()
        return out.lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: -1
    }

    /** 统计指定目录树下全部普通文件（用于清理前后对比）。 */
    private fun countAllFilesViaSu(absolutePath: String): Int {
        val quotedRoot = shellEscape(absolutePath)
        val out = runSuCommandWithOutput("find $quotedRoot -type f 2>/dev/null | wc -l")
            ?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull()
        return out ?: -1
    }

    private fun countAllPicturesFilesViaSu(): Int = countAllFilesViaSu(PUBLISH_POST_PICTURES_PATH)

    private fun picturesMediaStoreCollections(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            )
        } else {
            @Suppress("DEPRECATION")
            listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )
        }

    /** 与发作品素材统计/写入范围一致的 Pictures 查询条件。 */
    private fun picturesMediaStorePicturesSelection(): Pair<String, Array<String>> {
        val relativePathCol = MediaStore.MediaColumns.RELATIVE_PATH
        val dataCol = MediaStore.MediaColumns.DATA
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (
                "($relativePathCol LIKE ? OR $relativePathCol LIKE ? OR $relativePathCol = ?) " +
                    "OR $dataCol LIKE ? OR $dataCol LIKE ?"
                ) to arrayOf(
                "Pictures/%",
                "Pictures%",
                "Pictures",
                "%/Pictures/%",
                "%/emulated/0/Pictures/%",
            )
        } else {
            "($dataCol LIKE ? OR $dataCol LIKE ?)" to arrayOf("%/Pictures/%", "%/emulated/0/Pictures/%")
        }
    }

    /** 系统相册 Camera 卷（DCIM/Camera）MediaStore 范围。 */
    private fun dcimCameraMediaStoreSelection(): Pair<String, Array<String>> {
        val relativePathCol = MediaStore.MediaColumns.RELATIVE_PATH
        val dataCol = MediaStore.MediaColumns.DATA
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (
                "($relativePathCol LIKE ? OR $relativePathCol LIKE ? OR $relativePathCol = ?) " +
                    "OR $dataCol LIKE ? OR $dataCol LIKE ?"
                ) to arrayOf(
                "DCIM/Camera/%",
                "DCIM/Camera%",
                "DCIM/Camera",
                "%/DCIM/Camera/%",
                "%/emulated/0/DCIM/Camera/%",
            )
        } else {
            "($dataCol LIKE ? OR $dataCol LIKE ?)" to arrayOf(
                "%/DCIM/Camera/%",
                "%/emulated/0/DCIM/Camera/%",
            )
        }
    }

    private fun combineMediaStoreSelections(
        vararg parts: Pair<String, Array<String>>,
    ): Pair<String, Array<String>> {
        if (parts.isEmpty()) return "1=0" to emptyArray()
        if (parts.size == 1) return parts[0]
        val selection = parts.joinToString(" OR ") { "(${it.first})" }
        val args = parts.flatMap { it.second.toList() }.toTypedArray()
        return selection to args
    }

    private fun galleryCleanupMediaStoreSelection(includeDcimCameraAlbum: Boolean): Pair<String, Array<String>> =
        if (includeDcimCameraAlbum) {
            combineMediaStoreSelections(
                picturesMediaStorePicturesSelection(),
                dcimCameraMediaStoreSelection(),
            )
        } else {
            picturesMediaStorePicturesSelection()
        }

    private fun publishPostIsAutoTracesMediaPath(relativePath: String?, dataPath: String?): Boolean {
        val rel = relativePath?.trim().orEmpty()
        if (rel.equals(PUBLISH_POST_PICTURES_EXCLUDE_SUBDIR, ignoreCase = true)) return true
        if (rel.startsWith("${PUBLISH_POST_PICTURES_EXCLUDE_SUBDIR}/", ignoreCase = true)) return true
        val data = dataPath?.trim().orEmpty()
        if (data.isEmpty()) return false
        val marker = "/Pictures/${PUBLISH_POST_PICTURES_EXCLUDE_SUBDIR}"
        return data.contains("$marker/", ignoreCase = true) ||
            data.endsWith(marker, ignoreCase = true)
    }

    /**
     * 经 MediaStore 写入的素材（如 PC 去重下发）在部分设备 su 命名空间内 `find` 不可见；
     * 发作品统计/清理日志以 ContentResolver 查询为准，并与 su find 取较大值。
     */
    private fun countPicturesMediaStoreEntries(
        ctx: Context,
        exts: List<String>? = null,
        excludeAutoTraces: Boolean = false,
    ): Int {
        val extSet = exts?.map { it.lowercase(Locale.US) }?.toSet()
        val resolver = ctx.contentResolver
        val (selection, selectionArgs) = picturesMediaStorePicturesSelection()
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
        )
        var count = 0
        for (collection in picturesMediaStoreCollections()) {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val relCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val rel = cursor.getString(relCol)
                    val data = cursor.getString(dataCol)
                    if (excludeAutoTraces && publishPostIsAutoTracesMediaPath(rel, data)) continue
                    val displayName = cursor.getString(nameCol)?.trim().orEmpty()
                    if (displayName.isEmpty()) continue
                    if (extSet != null) {
                        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
                        if (ext.isEmpty() || ext !in extSet) continue
                    }
                    count++
                }
            }
        }
        return count
    }

    private fun countDcimCameraMediaStoreEntries(ctx: Context): Int =
        countMediaStoreEntriesWithSelection(ctx, dcimCameraMediaStoreSelection(), exts = null)

    private fun countMediaStoreEntriesWithSelection(
        ctx: Context,
        selectionPair: Pair<String, Array<String>>,
        exts: List<String>?,
    ): Int {
        val extSet = exts?.map { it.lowercase(Locale.US) }?.toSet()
        val resolver = ctx.contentResolver
        val (selection, selectionArgs) = selectionPair
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        var count = 0
        for (collection in picturesMediaStoreCollections()) {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameCol)?.trim().orEmpty()
                    if (displayName.isEmpty()) continue
                    if (extSet != null) {
                        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
                        if (ext.isEmpty() || ext !in extSet) continue
                    }
                    count++
                }
            }
        }
        return count
    }

    private fun countGalleryCleanupMediaStoreEntries(ctx: Context, includeDcimCameraAlbum: Boolean): Int {
        var total = countPicturesMediaStoreEntries(ctx)
        if (includeDcimCameraAlbum) total += countDcimCameraMediaStoreEntries(ctx)
        return total
    }

    private fun countGalleryCleanupFilesViaSu(includeDcimCameraAlbum: Boolean): Int {
        var pictures = countAllPicturesFilesViaSu()
        if (!includeDcimCameraAlbum) return pictures
        val camera = countAllFilesViaSu(PUBLISH_POST_DCIM_CAMERA_PATH)
        return when {
            pictures < 0 && camera < 0 -> -1
            pictures < 0 -> camera
            camera < 0 -> pictures
            else -> pictures + camera
        }
    }

    /**
     * 删除经 MediaStore 写入、仅 rm 文件仍会残留在图库里的索引（含 IS_PENDING 幽灵项）。
     */
    private fun deleteGalleryMediaStoreEntries(ctx: Context, includeDcimCameraAlbum: Boolean): Int {
        var deleted = 0
        val resolver = ctx.contentResolver
        val (selection, selectionArgs) = galleryCleanupMediaStoreSelection(includeDcimCameraAlbum)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        for (collection in picturesMediaStoreCollections()) {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val itemUri = ContentUris.withAppendedId(collection, id)
                    deleted += resolver.delete(itemUri, null, null).coerceAtLeast(0)
                }
            }
        }
        return deleted
    }

    /** su 递归清空指定目录（与 adb `find … -delete` 一致）。 */
    private fun deleteFilesystemGalleryDir(absolutePath: String) {
        val quotedRoot = shellEscape(absolutePath)
        runSuCommand("find $quotedRoot -type f -size 0 2>/dev/null -delete")
        runSuCommand("find $quotedRoot -mindepth 1 -delete 2>/dev/null")
        runSuCommand("find $quotedRoot -type f 2>/dev/null -delete")
        runSuCommand("find $quotedRoot -depth -type d -mindepth 1 2>/dev/null -delete")
    }

    private fun requestGalleryPathsMediaRescan(
        ctx: Context,
        alias: String,
        logContext: String,
        paths: List<String>,
    ) {
        MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { path, uri ->
            devOp(alias, "$logContext：媒体库刷新 scan path=$path uri=$uri")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runSuCommand("cmd media scan-volume external_primary 2>/dev/null")
        } else {
            for (path in paths) {
                runSuCommand(
                    "am broadcast -a android.intent.action.MEDIA_MOUNTED -d file://$path 2>/dev/null",
                )
            }
        }
    }

    /**
     * 清理图库：先删 MediaStore 索引，再 su 递归删磁盘，最后触发媒体库扫描。
     * [includeDcimCameraAlbum] 为 true 时额外清空 DCIM/Camera（发作品用）；修改基本资料为 false。
     */
    private suspend fun deleteAllPicturesOnDevice(
        alias: String,
        logContext: String,
        includeDcimCameraAlbum: Boolean = false,
    ) {
        withContext(Dispatchers.IO) {
            val ctx = appContextProvider().applicationContext
            val fsPaths = buildList {
                add(PUBLISH_POST_PICTURES_PATH)
                if (includeDcimCameraAlbum) add(PUBLISH_POST_DCIM_CAMERA_PATH)
            }
            val scopeLabel = if (includeDcimCameraAlbum) {
                "${PUBLISH_POST_PICTURES_PATH} + ${PUBLISH_POST_DCIM_CAMERA_PATH}"
            } else {
                PUBLISH_POST_PICTURES_PATH
            }
            val beforeMs = countGalleryCleanupMediaStoreEntries(ctx, includeDcimCameraAlbum)
            val beforeFs = countGalleryCleanupFilesViaSu(includeDcimCameraAlbum)
            val msDeletedFirst = runCatching { deleteGalleryMediaStoreEntries(ctx, includeDcimCameraAlbum) }
                .getOrElse { ex ->
                    devOp(alias, "$logContext：MediaStore 清理异常 ${ex.message}")
                    0
                }
            for (path in fsPaths) {
                deleteFilesystemGalleryDir(path)
            }
            val msDeletedSecond = runCatching {
                deleteGalleryMediaStoreEntries(ctx, includeDcimCameraAlbum)
            }.getOrElse { 0 }
            requestGalleryPathsMediaRescan(ctx, alias, logContext, fsPaths)
            delay(800)
            val afterMs = countGalleryCleanupMediaStoreEntries(ctx, includeDcimCameraAlbum)
            val afterFs = countGalleryCleanupFilesViaSu(includeDcimCameraAlbum)
            val fsRemoved = if (beforeFs >= 0 && afterFs >= 0) (beforeFs - afterFs).coerceAtLeast(0) else beforeFs
            val msRemoved = (beforeMs - afterMs).coerceAtLeast(0)
            devOp(
                alias,
                "$logContext：图库清理完成（范围 $scopeLabel） " +
                    "MediaStore 执行删除=${msDeletedFirst + msDeletedSecond} 条，清理前=$beforeMs 清理后剩余=$afterMs（已清 $msRemoved 条） " +
                    "磁盘文件 清理前=$beforeFs 清理后剩余=$afterFs（已清 $fsRemoved 个）",
            )
        }
    }

    /**
     * 等价本机 `POST /api/v1/tasks/request`：`CompanionApi.createTaskAndWaitReady` → 下载校验 → 写入 Pictures。
     * 同步等待完成，供发作品流程在清理图库后拉取新素材。
     */
    private suspend fun publishPostFetchMaterialsFromPc(
        service: Context,
        alias: String,
        count: Int,
    ): Boolean {
        if (hardStopOrNull(alias, "发作品素材拉取")) return false
        val app = service.applicationContext
        val targetPath = CompanionApi.DEFAULT_MATERIAL_STORAGE_PATH
        devOp(
            alias,
            "发作品：本机 POST /api/v1/tasks/request（count=$count，targetPath=$targetPath）→ PC tasks/create（异步轮询就绪），开始拉取…",
        )
        return try {
            val task = CompanionApi.createTaskAndWaitReady(app, count, targetPath)
            if (task.files.isEmpty()) {
                devOp(alias, "发作品：PC 返回任务无文件（taskId=${task.taskId}），素材拉取失败")
                return false
            }
            TaskStore.saveTaskSnapshot(
                app,
                TaskSnapshot(
                    taskId = task.taskId,
                    status = task.status,
                    error = null,
                    createdAtMs = System.currentTimeMillis(),
                    targetPath = targetPath,
                    originalTaskId = null,
                    retryOfTaskId = null,
                    files = task.files.map {
                        TaskFileSnapshot(it.fileName, it.sizeBytes, it.md5, "pending", null)
                    },
                ),
            )
            TaskDownloadWorkflow.downloadVerifyAndAck(app, task.taskId, task.files, targetPath)
            val stats = publishPostResolveGalleryMaterialStats()
            devOp(
                alias,
                "发作品：素材拉取完成 taskId=${task.taskId} files=${task.files.size}；" +
                    "图库统计 图片=${stats.picturesRootImageCount} 视频=${stats.picturesRootVideoCount} 合计=${stats.resolvedCount}",
            )
            stats.resolvedCount > 0
        } catch (t: Throwable) {
            val companionId = CompanionApi.deviceId(app)
            val hwKey = CompanionApi.hardwareKey(app).orEmpty()
            val hint =
                when {
                    t.message?.contains("no_video_files_available_or_all_reserved", ignoreCase = true) == true ->
                        "（PC 去重库无可用视频或素材均被其它任务占用；请补充素材或释放 PC 端 reservation）"
                    t.message?.contains("device_not_found", ignoreCase = true) == true ->
                        "（PC 未识别 deviceId=$companionId" +
                            (if (hwKey.isNotEmpty()) " hw=$hwKey" else "") +
                            "；投屏正常仅表示 ADB 已连。请打开「PC传输」保存/注册一次，" +
                            "确认 ID 与 PC 配对页一致，并更新 PC 去重服务到含 ResolveForClientDeviceId 的版本；" +
                            "PC=${CompanionApi.windowsBaseUrl(app)}）"
                    else -> ""
                }
            lastPublishPostEntryFailureReason = "素材拉取失败${t.message?.let { m -> "：$m" }.orEmpty()}"
            devOp(alias, "发作品：素材拉取异常 ${t.message ?: t.javaClass.simpleName}$hint")
            false
        }
    }

    override suspend fun clearDevicePicturesGallery(alias: String) {
        deleteAllPicturesOnDevice(alias, "修改基本资料", includeDcimCameraAlbum = false)
    }

    private fun publishPostCountPicturesImageVideoViaSu(): Pair<Int, Int> {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val ctx = appContextProvider().applicationContext
        val suImages = publishPostCountPicturesMediaFilesViaSu(PUBLISH_POST_PICTURES_IMAGE_EXTS)
        val suVideos = publishPostCountPicturesMediaFilesViaSu(PUBLISH_POST_PICTURES_VIDEO_EXTS)
        val msImages = countPicturesMediaStoreEntries(
            ctx,
            PUBLISH_POST_PICTURES_IMAGE_EXTS,
            excludeAutoTraces = true,
        )
        val msVideos = countPicturesMediaStoreEntries(
            ctx,
            PUBLISH_POST_PICTURES_VIDEO_EXTS,
            excludeAutoTraces = true,
        )
        fun resolved(su: Int, ms: Int): Int {
            if (su < 0) return ms
            return maxOf(su, ms)
        }
        val images = resolved(suImages, msImages)
        val videos = resolved(suVideos, msVideos)
        if (suImages < 0 || suVideos < 0) {
            devOp(
                alias,
                "发作品：su find 统计 Pictures 失败（images=$suImages videos=$suVideos），" +
                    "MediaStore 补统计 images=$msImages videos=$msVideos",
            )
        } else if ((suImages + suVideos) == 0 && (msImages + msVideos) > 0) {
            devOp(
                alias,
                "发作品：su find 未见 Pictures 文件，MediaStore 统计 图片=$msImages 视频=$msVideos",
            )
        }
        return images to videos
    }

    override fun countPicturesGalleryImageFiles(): Int {
        val ctx = appContextProvider().applicationContext
        val suCount = if (PUBLISH_POST_PICTURES_IMAGE_EXTS.isEmpty()) {
            0
        } else {
            publishPostCountPicturesMediaFilesViaSu(PUBLISH_POST_PICTURES_IMAGE_EXTS)
        }
        val msCount = countPicturesMediaStoreEntries(
            ctx,
            PUBLISH_POST_PICTURES_IMAGE_EXTS,
            excludeAutoTraces = false,
        )
        return when {
            suCount < 0 -> msCount
            else -> maxOf(suCount, msCount)
        }
    }

    private fun runSuCommandWithOutput(command: String): String? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val suCommand = resolveSuCommand(alias) ?: return null
        return try {
            val execArgs = if (suCommand.useShell) {
                val inner =
                    if (suCommand.useMountMaster) "${suCommand.path} -mm -c \"$command\""
                    else "${suCommand.path} -c \"$command\""
                arrayOf("sh", "-c", inner)
            } else {
                if (suCommand.useMountMaster) {
                    arrayOf(suCommand.path, "-mm", "-c", command)
                } else {
                    arrayOf(suCommand.path, "-c", command)
                }
            }
            val process = Runtime.getRuntime().exec(execArgs)
            val outHolder = arrayOf("")
            val tOut = thread(start = true, name = "su-out-cap") {
                runCatching {
                    outHolder[0] = process.inputStream.bufferedReader().use { it.readText().trim() }
                }
            }
            val tErr = thread(start = true, name = "su-err-cap") {
                runCatching { process.errorStream.bufferedReader().use { it.readText() } }
            }
            process.waitFor()
            tOut.join(120_000)
            tErr.join(120_000)
            outHolder[0].trim()
        } catch (ex: Exception) {
            
            cachedSuCommand = null
            null
        }
    }

    /** 合并 stdout/stderr（供 uiautomator dump 等命令读全量输出）。 */
    private fun runSuCommandWithOutputMerged(command: String): String? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val suCommand = resolveSuCommand(alias) ?: return null
        return try {
            val execArgs = if (suCommand.useShell) {
                val inner =
                    if (suCommand.useMountMaster) "${suCommand.path} -mm -c \"$command\""
                    else "${suCommand.path} -c \"$command\""
                arrayOf("sh", "-c", inner)
            } else {
                if (suCommand.useMountMaster) {
                    arrayOf(suCommand.path, "-mm", "-c", command)
                } else {
                    arrayOf(suCommand.path, "-c", command)
                }
            }
            val process = Runtime.getRuntime().exec(execArgs)
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n").trim().ifBlank { null }
        } catch (ex: Exception) {
            cachedSuCommand = null
            null
        }
    }

    private val dumpsysFocusLineKeywords = arrayOf(
        "mCurrentFocus",
        "mFocusedApp",
        "mFocusedWindow",
        "focusedApp",
        "mResumedActivity",
        "topResumedActivity",
        "ResumedActivity",
    )

    @Volatile
    private var foregroundProbeCache: Pair<Long, String>? = null

    private fun invalidateForegroundProbeCache() {
        foregroundProbeCache = null
    }

    /**
     * 从 dumpsys 解析**当前焦点/顶层**包名（仅 mCurrentFocus / mFocusedApp / topResumed / activity top）。
     * 不用 activity 栈里残留包名，避免 force-stop 后仍误判 TikTok 在前台。
     */
    private fun resolveStrictForegroundPackage(raw: String): String? {
        for (line in filterDumpsysFocusLines(raw)) {
            val lower = line.lowercase(Locale.US)
            val highTrust = lower.contains("mcurrentfocus") ||
                lower.contains("mfocusedapp") ||
                lower.contains("mfocusedwindow") ||
                lower.contains("topresumedactivity")
            if (!highTrust) continue
            extractPackageFromDumpsysComponentLine(line)?.let { return it }
        }
        return extractTopActivityPackage(raw)
    }

    private fun extractPackageFromDumpsysComponentLine(line: String): String? {
        Regex("""packageName=([a-zA-Z][\w.]*)""", RegexOption.IGNORE_CASE).find(line)?.let {
            return it.groupValues[1]
        }
        Regex("""([a-zA-Z][\w.]*)/[a-zA-Z][\w.$]+""").findAll(line).forEach { m ->
            val pkg = m.groupValues[1]
            if (pkg.contains('.') && !pkg.equals("android", ignoreCase = true)) return pkg
        }
        return null
    }

    /** 多源 dumpsys（不用 shell grep，避免空匹配阻断回退、部分 su 管道失效）。 */
    private fun collectForegroundProbeTexts(): String {
        val now = System.currentTimeMillis()
        foregroundProbeCache?.let { (ts, text) ->
            if (now - ts < 400L) return text
        }
        val parts = mutableListOf<String>()
        for (cmd in listOf(
            "dumpsys activity top",
            "cmd activity top",
            "dumpsys window",
            "dumpsys activity activities",
        )) {
            val chunk = runSuCommandWithOutputMerged(cmd)
            if (!chunk.isNullOrBlank()) parts += chunk
        }
        val merged = parts.joinToString("\n")
        foregroundProbeCache = now to merged
        return merged
    }

    private fun filterDumpsysFocusLines(raw: String): List<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    dumpsysFocusLineKeywords.any { kw -> line.contains(kw, ignoreCase = true) }
            }
            .toList()

    private fun extractTopActivityPackage(raw: String): String? {
        for (line in raw.lineSequence().map { it.trim() }) {
            if (!line.startsWith("ACTIVITY ", ignoreCase = true)) continue
            val m = Regex("""ACTIVITY\s+([a-zA-Z][\w.]*)/""", RegexOption.IGNORE_CASE).find(line) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    private fun extractPackagesFromDumpsys(raw: String): List<String> {
        val pkgs = LinkedHashSet<String>()
        extractTopActivityPackage(raw)?.let { pkgs += it }
        for (line in raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            Regex("""packageName=([a-zA-Z][\w.]*)""", RegexOption.IGNORE_CASE)
                .findAll(line)
                .forEach { pkgs += it.groupValues[1] }
            if (!dumpsysFocusLineKeywords.any { kw -> line.contains(kw, ignoreCase = true) } &&
                !line.startsWith("ACTIVITY ", ignoreCase = true)
            ) {
                continue
            }
            Regex("""\bu\d+\s+([a-zA-Z][\w.]*)""").findAll(line).forEach { pkgs += it.groupValues[1] }
            Regex("""([a-zA-Z][\w.]*)/[a-zA-Z][\w.$]*""").findAll(line).forEach { pkgs += it.groupValues[1] }
        }
        return pkgs.toList()
    }

    private fun isPackageForeground(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val fg = resolveStrictForegroundPackage(collectForegroundProbeTexts()) ?: return false
        return fg.equals(packageName, ignoreCase = true)
    }

    private fun isTikTokForeground(): Boolean {
        val fg = resolveStrictForegroundPackage(collectForegroundProbeTexts()) ?: return false
        return Config.TIKTOK_PACKAGE_CANDIDATES.any { it.equals(fg, ignoreCase = true) }
    }

    private fun logForegroundProbeFailure(alias: String, tag: String, expectedPkg: String) {
        val probe = collectForegroundProbeTexts()
        val strictFg = resolveStrictForegroundPackage(probe)
        val parsed = extractPackagesFromDumpsys(probe)
        val focusSnippet = filterDumpsysFocusLines(probe).take(4).joinToString(" | ")
        devOp(
            alias,
            "$tag：严格前台校验失败（期望=$expectedPkg 焦点包=${strictFg ?: "无"} 栈解析=${parsed.joinToString()} 焦点行=${focusSnippet.ifBlank { "无" }}）",
        )
    }

    private fun isPackageVisible(packageName: String): Boolean {
        if (Config.TIKTOK_PACKAGE_CANDIDATES.any { it.equals(packageName, ignoreCase = true) }) {
            return isTikTokForeground()
        }
        return isPackageForeground(packageName)
    }

    private fun resolveSuCommand(alias: String): SuCommand? {
        cachedSuCommand?.let { return it }

        val probeCommands = listOf("which su", "command -v su", "toybox which su", "busybox which su")
        for (probe in probeCommands) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", probe))
                val output = process.inputStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                if (exitCode == 0 && output.isNotBlank()) {
                    val resolved = output.lineSequence().first().trim()
                    if (resolved.isNotEmpty()) {
                        if (resolved.startsWith("/")) {
                            val resolvedCommand = validateSuCommand(SuCommand(resolved, false))
                            if (resolvedCommand != null) {
                                cachedSuCommand = resolvedCommand
                                return resolvedCommand
                            }
                        } else {
                            val resolvedCommand = validateSuCommand(SuCommand(resolved, true))
                            if (resolvedCommand != null) {
                                cachedSuCommand = resolvedCommand
                                return resolvedCommand
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        val fallbacks = listOf(
            "/data/adb/magisk/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/bin/.ext/su"
        )
        for (path in fallbacks) {
            validateSuCommand(SuCommand(path, false))?.let {
                cachedSuCommand = it
                return it
            }
        }
        for (path in fallbacks) {
            validateSuCommand(SuCommand(path, true))?.let {
                cachedSuCommand = it
                return it
            }
        }
        return null
    }

    private fun validateSuCommand(suCommand: SuCommand): SuCommand? {
        return try {
            val execArgs = if (suCommand.useShell) {
                arrayOf("sh", "-c", "${suCommand.path} -c \"id\"")
            } else {
                arrayOf(suCommand.path, "-c", "id")
            }
            val process = Runtime.getRuntime().exec(execArgs)
            val exitCode = process.waitFor()
            if (exitCode != 0) return null
            val mountMaster = probeSuMountMaster(suCommand.path, suCommand.useShell)
            suCommand.copy(useMountMaster = mountMaster)
        } catch (_: Exception) {
            null
        }
    }

    /** Magisk 等：App 进程内默认挂载空间可能看不到其它应用的 /data/data；`-mm` 使用全局命名空间。 */
    private fun probeSuMountMaster(path: String, useShell: Boolean): Boolean {
        return try {
            val execArgs = if (useShell) {
                arrayOf("sh", "-c", "$path -mm -c \"id\"")
            } else {
                arrayOf(path, "-mm", "-c", "id")
            }
            val process = Runtime.getRuntime().exec(execArgs)
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun shellEscape(value: String): String {
        if (value.isEmpty()) return "''"
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun randomDelayMsFromSecondsRange(minSec: Double, maxSec: Double): Long {
        val safeMin = minSec.coerceAtLeast(0.0)
        val safeMax = maxSec.coerceAtLeast(safeMin)
        val minMs = (safeMin * 1000.0).toLong().coerceAtLeast(0L)
        val maxMs = (safeMax * 1000.0).toLong().coerceAtLeast(minMs)
        if (maxMs <= minMs) return minMs
        return Random.nextLong(minMs, maxMs + 1)
    }

    private suspend fun findUsersAnchorByOcr(service: Context): Point? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "users_ocr.png")
        val screenshotOk = runSuCommand("screencap -p ${screenshotFile.absolutePath}")
        if (!screenshotOk) {
            
            return null
        }
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath)
        if (bitmap == null) {
            
            return null
        }
        observeAccountStatusOnScreenshot(service, bitmap, "users_ocr")

        // 扩大识别区域：搜索页 Users 标签可能在屏幕中上部，取上 70% 提高命中
        val cropHeight = (bitmap.height * 0.7f).toInt().coerceAtLeast(1)
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropHeight)

        val image = InputImage.fromBitmap(croppedBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            // 优先按「单词」匹配：OCR 常把整行识别为 "Ask Top Users Videos Shop Sounds"，
            // 需在 line.elements 里找到 "Users" 并用其 boundingBox 精确点击
            fun isUsersWord(t: String): Boolean {
                val w = t.trim()
                return w.equals("Users", ignoreCase = true) ||
                    w.equals("User", ignoreCase = true) ||
                    w.equals("用户", ignoreCase = true)
            }
            val candidates = result.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    // 1) 单词级：精确点击 "Users" 标签
                    line.elements.mapNotNull { element ->
                        if (isUsersWord(element.text)) element.boundingBox?.let { box ->
                            Point(box.centerX(), box.centerY())
                        } else null
                    }.ifEmpty {
                        // 2) 行内包含 "Users"/"用户" 时用整行中心兜底
                        val lineText = line.text
                        if (lineText.contains("Users", ignoreCase = true) || lineText.contains("用户")) {
                            line.boundingBox?.let { box -> listOf(Point(box.centerX(), box.centerY())) } ?: emptyList()
                        } else emptyList()
                    }
                }
            }
            val anchor = candidates.firstOrNull()
            if (anchor == null) {
                val allText = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
                val preview = allText.take(25).joinToString(" | ")
                
            }
            anchor
        } catch (ex: Exception) {
            
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
            screenshotFile.delete()
        }
    }

    private suspend fun isSearchLoadingByOcr(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "search_loading.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return false
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return false
        }
        observeAccountStatusOnScreenshot(service, bitmap, "search_loading")
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val texts = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
            val tabs = listOf("Ask", "Top", "Shop", "Videos", "Users", "Sounds")
            val tabHit = texts.count { t -> tabs.any { t.equals(it, ignoreCase = true) } }
            val loadingHit = texts.any { it.contains("Search", ignoreCase = true) || it.contains("Ask", ignoreCase = true) }
            val loading = tabHit >= 2 && loadingHit
            if (loading) {
                
            }
            loading
        } catch (ex: Exception) {
            
            false
        } finally {
            recognizer.close()
            bitmap.recycle()
            screenshotFile.delete()
        }
    }

    private suspend fun ensureProfileByOcr(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "profile_ocr.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return false
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return false
        }
        observeAccountStatusOnScreenshot(service, bitmap, "profile_ocr")
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val texts = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
            val keywords = listOf("Following", "Likes", "Follow", "Message")
            val hitKeywords = keywords.filter { key ->
                texts.any { it.contains(key, ignoreCase = true) }
            }
            val hit = hitKeywords.distinct().size >= 2
            if (!hit) {
                val preview = texts.take(25).joinToString(" | ")
                
            } else {
                
            }
            hit
        } catch (ex: Exception) {
            
            false
        } finally {
            recognizer.close()
            bitmap.recycle()
            screenshotFile.delete()
        }
    }

    private suspend fun checkNoVideosAndGoBack(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "检查无作品或私密账号")) return false
        val screenshotFile = File(service.cacheDir, "profile_novideos.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return false
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return false
        }
        observeAccountStatusOnScreenshot(service, bitmap, "profile_novideos")
        val cropTop = (bitmap.height * 0.46f).toInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
        val cropHeight = (bitmap.height * 0.42f).toInt()
            .coerceAtLeast(1)
            .coerceAtMost(bitmap.height - cropTop)
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, cropHeight)
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val texts = result.textBlocks
                .flatMap { it.lines }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }

            val noVideosTarget = "No videos yet"
            val privateTarget = "This account is private"

            val hitNoVideosLine = texts.firstOrNull { line ->
                line.contains(noVideosTarget, ignoreCase = true) || run {
                    val lenDiffOk = kotlin.math.abs(alphanumericOnly(line).length - alphanumericOnly(noVideosTarget).length) <= 6
                    lenDiffOk && alphanumericSimilarity(noVideosTarget, line) >= 0.52
                }
            }

            val hitPrivateLine = texts.firstOrNull { line ->
                line.contains(privateTarget, ignoreCase = true) || run {
                    val lenDiffOk = kotlin.math.abs(alphanumericOnly(line).length - alphanumericOnly(privateTarget).length) <= 8
                    lenDiffOk && alphanumericSimilarity(privateTarget, line) >= 0.5
                }
            }

            when {
                hitNoVideosLine != null -> {
                    clickProfileBack(service, "命中无作品文案")
                    delay(Random.nextLong(600, 900))
                    true
                }
                hitPrivateLine != null -> {
                    clickProfileBack(service, "命中私密账号文案")
                    delay(Random.nextLong(600, 900))
                    true
                }
                else -> false
            }
        } catch (ex: Exception) {
            
            false
        } finally {
            recognizer.close()
            bitmap.recycle()
            croppedBitmap.recycle()
            screenshotFile.delete()
        }
    }

    private suspend fun checkNoVideosAndGoBackWithRetry(service: Context): Boolean {
        // 页面可能在加载中，给两次机会（短等待）提升命中率
        if (checkNoVideosAndGoBack(service)) return true
        delay(420)
        return checkNoVideosAndGoBack(service)
    }

    private fun clickProfileBack(service: Context, reason: String) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        navBack(alias, "离开用户主页（$reason）")
    }


    private suspend fun clickTopThreePostsRandom(service: Context): Pair<Point, String> {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val centerY = (height * 0.75f).toInt()
        val left = Point((width * 0.17f).toInt(), centerY)
        val middle = Point((width * 0.5f).toInt(), centerY)
        val right = Point((width * 0.83f).toInt(), centerY)
        val candidates = listOf(
            left to "left",
            middle to "middle",
            right to "right",
        )
        val pick = candidates.random()
        lastClickedPostSlot = pick.second
        devOp(alias, "点击作品（${pick.second}）")
        runSuCommand("input tap ${pick.first.x} ${pick.first.y}")
        return pick
    }

    private fun leftPostPoint(service: Context): Point {
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val centerY = (height * 0.70f).toInt()
        return Point((width * 0.17f).toInt(), centerY)
    }

    /**
     * 点进作品后统一执行“双判定”：第一次判定 + 第二次判定（全量路径一致）。
     * 仅当第二次判定通过时才进入作品内互动动作。
     */
    private suspend fun enterPostAndRunInteractionsWithSecondCheck(
        service: Context,
        config: AppConfig,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val afterTapDelayMs = config.growth_post_check_after_tap_delay_ms.coerceIn(100L, 5000L)
        val secondCheckDelayMs = config.growth_post_second_check_delay_ms.coerceIn(0L, 5000L)

        val beforeTapForVerify = File(service.cacheDir, "post_enter_before_tap.png")
        if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
        val beforeTapCaptured = runSuCommand("screencap -p ${beforeTapForVerify.absolutePath}")

        clickTopThreePostsRandom(service)

        val firstEntered = detectEnteredPostPage(
            service,
            beforeTapReference = if (beforeTapCaptured) beforeTapForVerify else null,
            afterTapDelayMs = afterTapDelayMs,
            motionTag = "前后帧差-首次",
            allowOcrFallback = false,
            exportGrowthFrameDiffFailureToGallery = false,
        )

        if (firstEntered) {
            if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
            runPostInteractionsCancellable(service, config)
            return true
        }

        val left = leftPostPoint(service)
        lastClickedPostSlot = "left_retry"
        runSuCommand("input tap ${left.x} ${left.y}")
        delay(secondCheckDelayMs)
        val secondEntered = detectEnteredPostPage(
            service,
            beforeTapReference = if (beforeTapCaptured) beforeTapForVerify else null,
            afterTapDelayMs = afterTapDelayMs,
            motionTag = "前后帧差-二次(左侧重试)",
            allowOcrFallback = false,
            exportGrowthFrameDiffFailureToGallery = false,
        )

        if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
        if (secondEntered) {
            runPostInteractionsCancellable(service, config)
            return true
        }

        // 第二次帧差失败：先回退一次，再用 OCR 统计 Follow 数量确认是否仍在正常 Followers 列表。
        // 少于 4 直接视为页面错乱，走重启恢复；>=4 才认为状态正常，继续下一条。
        devOp(alias, "二次判定失败：尝试返回 Followers 列表并用 OCR 复核")
        if (recoverFollowersListAfterPostCheckFailure(service)) {
            devOp(alias, "Followers 列表 OCR 复核通过，按 growth 流程继续下一用户名")
            return false
        }

        // recoverFollowersListAfterPostCheckFailure 内部已经完成失败时的重启恢复，这里避免重复重启。
        devOp(alias, "Followers 列表 OCR 复核失败：已执行恢复重启，跳过当前条目")
        throw GrowthFlowRestartRequired("Followers 列表 OCR 复核失败，已执行重启并中断当前用户名流程")
    }

    private suspend fun detectEnteredPostPage(
        service: Context,
        beforeTapReference: File? = null,
        afterTapDelayMs: Long = 0L,
        motionTag: String = "帧差",
        allowOcrFallback: Boolean = true,
        exportGrowthFrameDiffFailureToGallery: Boolean = false,
    ): Boolean {
        val motion = if (beforeTapReference != null) {
            detectVideoMotionByFrameDiffFromBeforeTap(
                service,
                beforeTapReference,
                afterTapDelayMs,
                motionTag,
                exportGrowthFrameDiffFailureToGallery,
            )
        } else {
            detectVideoMotionByFrameDiff(
                service,
                debugTag = sanitizeFrameDiffTagForFilename(motionTag),
                motionTagForFailureExport = motionTag,
                exportGrowthFrameDiffFailureToGallery = exportGrowthFrameDiffFailureToGallery,
            )
        }
        if (motion) {
            return true
        }
        if (!allowOcrFallback) {
            return false
        }
        val ocr = detectCommentInputByOcr(service)
        return ocr
    }

    /**
     * 主播访问模式（live）：
     * 1) 固定点击左侧第一条作品
     * 2) 两次判定（第一次仅记录，第二次为真判定）
     * 3) 进入作品后：评论 100% + 收藏/点赞/转发 全执行（测试）
     * 4) 操作完成后返回两次，回到 Users 选择界面并清空输入框
     */
    private suspend fun runLiveVisitModeForCurrentProfile(
        service: Context,
        config: AppConfig,
        isDirectMode: Boolean,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val modeLabel = if (isDirectMode) "直客访问模式" else "主播访问模式"
        

        val entered = if (isDirectMode) {
            enterLeftFirstPostForDirectMode(service, config)
        } else {
            enterLeftFirstPostWithSecondCheck(service)
        }
        if (!entered) {
            devOp(alias, "未能进入作品页，中止本用户流程")
            backToUsersSelectionFromProfile(service)
            focusSearchInput(service)
            clearSearchInputText()
            return false
        }

        val mutualClickResult = runMutualClickFirstStep(service, config, modeLabel)
        if (!mutualClickResult) {
            devOp(alias, "$modeLabel：互点首步未通过，中止本用户互动")
            backToUsersSelectionFromProfile(service)
            focusSearchInput(service)
            clearSearchInputText()
            return false
        }

        runLivePostActions(service, config, modeLabel, isDirectMode)

        devOp(alias, "互动完成，返回 Users 搜索")
        backToUsersSelectionFromProfile(service)
        focusSearchInput(service)
        clearSearchInputText()
        return true
    }

    /**
     * 直客/主播：进作品后的「互点首步」钩子（占位可扩展）。
     * 须返回 true 才会继续 [runLivePostActions]；先前若在此处直接 return 会跳过全部作品内互动。
     */
    private suspend fun runMutualClickFirstStep(
        service: Context,
        config: AppConfig,
        modeLabel: String,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        
        devOp(alias, "$modeLabel：互点首步（暂无附加逻辑）")
        return true
    }

    private suspend fun enterLeftFirstPostForDirectMode(
        service: Context,
        config: AppConfig,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        livePostOcrSnapshot = null
        val left = leftPostPoint(service)
        val firstDelayMs = config.direct_post_action_delay_ms.coerceIn(300L, 8000L)
        val secondDelayMs = 900L
        lastClickedPostSlot = "left_first"
        livePostClickAtMs = System.currentTimeMillis()

        val beforeTapForVerify = File(service.cacheDir, "direct_post_enter_before_tap.png")
        if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
        val beforeTapCaptured = runSuCommand("screencap -p ${beforeTapForVerify.absolutePath}")
        devOp(alias, "点击首条作品（直客）")
        val tapOk = runSuCommand("input tap ${left.x} ${left.y}")
        if (!tapOk) {
            if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
            return false
        }
        delay(firstDelayMs)
        val firstEntered = detectEnteredPostPage(
            service,
            beforeTapReference = if (beforeTapCaptured) beforeTapForVerify else null,
            afterTapDelayMs = 0L,
            motionTag = "直客-前后帧差-首次",
            allowOcrFallback = false,
        )
        

        
        delay(secondDelayMs)
        val secondEntered = detectEnteredPostPage(
            service,
            beforeTapReference = if (beforeTapCaptured) beforeTapForVerify else null,
            afterTapDelayMs = 0L,
            motionTag = "直客-前后帧差-二次",
            allowOcrFallback = false,
        )
        

        if (beforeTapForVerify.exists()) beforeTapForVerify.delete()
        if (!secondEntered) {
            
            return false
        }

        
        
        return true
    }

    private suspend fun enterLeftFirstPostWithSecondCheck(
        service: Context,
        // 适当拉长二次判定间隔：给作品页动画/网络加载更多时间，降低误判率
        secondCheckDelayMs: Long = 1100L,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        livePostOcrSnapshot = null
        val left = leftPostPoint(service)
        lastClickedPostSlot = "left_first"
        livePostClickAtMs = System.currentTimeMillis()
        devOp(alias, "点击首条作品（主播）")
        val tapOk = runSuCommand("input tap ${left.x} ${left.y}")
        if (!tapOk) {
            return false
        }
        delay(1500)
        val first = detectEnteredPostPage(service)
        

        
        delay(secondCheckDelayMs)
        val second = detectEnteredPostPage(service)
        

        if (!second) {
            
        }
        
        return second
    }

    private suspend fun runLivePostActions(
        service: Context,
        config: AppConfig,
        modeLabel: String,
        isDirectMode: Boolean,
    ) {
        val alias = PrefsStore.getAlias() ?: "unknown"

        // 仅主播模式保留 OCR 锚点快照；直客模式全部使用固定坐标
        livePostOcrSnapshot = if (isDirectMode) null else buildLivePostOcrSnapshot(service)

        val postStayMs = randomDelayMsFromSecondsRange(config.video_stay_min, config.video_stay_max)
        devOp(alias, "作品页停留 ${postStayMs}ms")
        delay(postStayMs)

        val actionGapMin = config.action_delay_min.coerceAtLeast(0.0)
        val actionGapMax = config.action_delay_max.coerceAtLeast(actionGapMin)

        data class Action(
            val name: String,
            val weight: Double,
            val dailyLimit: Int,
            val run: suspend () -> Unit,
        )

        fun canRun(action: Action): Boolean {
            if (action.weight <= 0.0) return false
            if (action.dailyLimit <= 0) return false
            val used = PrefsStore.getDailyActionCount(action.name)
            return used < action.dailyLimit
        }

        fun pickOne(available: List<Action>): Action? {
            val total = available.sumOf { it.weight.coerceAtLeast(0.0) }
            if (total <= 0.0) return null
            var r = Random.nextDouble(0.0, total)
            for (a in available) {
                r -= a.weight.coerceAtLeast(0.0)
                if (r <= 0.0) return a
            }
            return available.lastOrNull()
        }

        val allActions = listOf(
            Action(
                name = "like",
                weight = config.like_prob,
                dailyLimit = config.like_daily_limit,
                run = {
                    performLike(service)
                    
                },
            ),
            Action(
                name = "comment",
                weight = config.comment_prob,
                dailyLimit = config.comment_daily_limit,
                run = {
                    if (isDirectMode) {
                        performCommentTextDirectFixed(service, config)
                    } else {
                        performComment(service, config)
                    }
                    
                },
            ),
            Action(
                name = "favorite",
                weight = config.favorite_prob,
                dailyLimit = config.favorite_daily_limit,
                run = {
                    if (isDirectMode) {
                        performFavorite(service, useOcrAnchor = false)
                    } else {
                        performFavorite(service, livePostOcrSnapshot?.searchPoint)
                    }
                    
                },
            ),
            Action(
                name = "share",
                weight = config.share_prob,
                dailyLimit = config.share_daily_limit,
                run = {
                    if (isDirectMode) {
                        performShare(service, useOcrRepost = false)
                    } else {
                        performShare(service, livePostOcrSnapshot?.searchPoint)
                    }
                    
                },
            ),
        )

        val actionsToRun: List<Action> = if (config.action_single) {
            val available = allActions.filter { canRun(it) }
            val picked = pickOne(available)
            if (picked == null) {
                
                emptyList()
            } else {
                listOf(picked)
            }
        } else {
            allActions.filter { canRun(it) }
        }

        actionsToRun.forEachIndexed { index, action ->
            devOp(alias, "执行互动：${action.name}")
            action.run()
            PrefsStore.incrementDailyActionCount(action.name)
            if (index < actionsToRun.lastIndex) {
                val gapMs = randomDelayMsFromSecondsRange(actionGapMin, actionGapMax)
                devOp(alias, "互动间隔等待", gapMs)
                delay(gapMs)
            }
        }
    }

    private suspend fun backToUsersSelectionFromProfile(service: Context) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        repeat(2) { idx ->
            navBack(alias, "回到 Users 选择界面（${idx + 1}/2）")
            delay(Random.nextLong(800, 1100))
        }
    }

    private fun normalizeFollowersOcrText(raw: String): String {
        return raw.lowercase()
            .replace('0', 'o')
            .replace('1', 'l')
            .replace('|', 'l')
            .replace(Regex("[^a-z]+"), "")
    }

    /** 粉丝列表行：和桌面一致，必须严格等于 “Follow”；仍保留归一化仅用于排除噪声文本。 */
    private fun isFollowersListFollowOcrLabel(raw: String): Boolean {
        val n = normalizeFollowersOcrText(raw)
        if (n.isEmpty()) return false
        if (n.startsWith("following")) return false
        return raw.trim().equals("Follow", ignoreCase = true)
    }

    /**
     * 归一化各 element 文案后拆词，若 top / users / videos / photos / shop 命中不少于 3 个，
     * 视为误入搜索根用户 Tab 区域（非 Followers 列表）。
     */
    private fun isSearchRootUsernameTabsLikelyFromOcrResult(result: Text): Boolean {
        val blob = result.textBlocks.asSequence()
            .flatMap { it.lines.asSequence() }
            .flatMap { it.elements.asSequence() }
            .joinToString(" ") { normalizeFollowersOcrText(it.text) }
            .trim()
        if (blob.isEmpty()) return false
        val words = blob.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()
        val tabs = listOf("top", "users", "videos", "photos", "shop")
        return tabs.count { words.contains(it) } >= 3
    }

    private suspend fun recoverFollowersListAfterPostCheckFailure(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "恢复 Followers 列表")) return false
        if (hardStopOrNull(alias, "恢复 Followers 列表")) return false
        // 这里的 OCR 只负责“数 Follow”，不参与滚动。
        // 规则：返回后 Follow 数量 < 4 => 页面错乱；>= 4 => 认为回到了正常 Followers 列表。
        navBack(alias, "二次判定失败后回到 Followers 列表进行 OCR 复核")
        delay(900)
        val count = countFollowersFollowButtonsByOcrNormalized(service)
        val ok = count >= 4
        devOp(alias, "Followers 列表 OCR 复核：Follow=${count}，阈值>=4，结果=${if (ok) "通过" else "失败"}")
        if (!ok) {
            // 页面错乱：优先导航返回回首页，等待恢复后让上层跳过当前条目，进入下一轮。
            val restarted = recoverToHomeViaBackOrRestart(service, "Followers 列表 OCR 复核")
            devOp(alias, "TikTok 恢复结果：${if (restarted) "success" else "failed"}")
            if (restarted) {
                delay(3000)
            }
            return false
        }
        return true
    }

    private suspend fun countFollowersFollowButtonsByOcrNormalized(service: Context): Int {
        val screenshotFile = File(service.cacheDir, "followers_recovery_check.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return 0
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return 0
        }
        observeAccountStatusOnScreenshot(service, bitmap, "growth_followers_recovery_check")
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            // 只统计“Follow”按钮，严格等于 Follow；不把 Following、Followed 之类算进去。
            result.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .count { element ->
                    element.text.trim().equals("Follow", ignoreCase = true)
                }
        } catch (ex: Exception) {
            rethrowTaskLogoutIfNeeded(ex)
            0
        } finally {
            recognizer.close()
            bitmap.recycle()
            screenshotFile.delete()
        }
    }

    private suspend fun detectVideoMotionByFrameDiffFromBeforeTap(
        service: Context,
        beforeTapReference: File?,
        afterTapDelayMs: Long = 1000L,
        tag: String = "前后帧差",
        exportGrowthFrameDiffFailureToGallery: Boolean = false,
    ): Boolean {
        if (beforeTapReference == null || !beforeTapReference.exists()) {
            return detectVideoMotionByFrameDiff(
                service,
                debugTag = "fb_${sanitizeFrameDiffTagForFilename(tag)}",
                motionTagForFailureExport = tag,
                exportGrowthFrameDiffFailureToGallery = exportGrowthFrameDiffFailureToGallery,
            )
        }
        val afterTap = File(service.cacheDir, "frame_diff_after_tap.png")
        try {
            val waitMs = afterTapDelayMs.coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
            if (!runSuCommand("screencap -p ${afterTap.absolutePath}")) return false

            val bmp1Full = BitmapFactory.decodeFile(beforeTapReference.absolutePath) ?: return false
            val bmp2Full = BitmapFactory.decodeFile(afterTap.absolutePath) ?: run {
                bmp1Full.recycle()
                return false
            }
            try {
                val w = bmp1Full.width.coerceAtMost(bmp2Full.width)
                val h = bmp1Full.height.coerceAtMost(bmp2Full.height)
                if (w <= 0 || h <= 0) return false
                val cropLeft = 0
                val cropTop = (h * 0.10f).toInt().coerceIn(0, h - 1)
                val cropWidth = w
                // 帧差裁剪区间统一为 10%~40%
                val cropHeight = (h * 0.30f).toInt().coerceIn(1, h - cropTop)
                val bmp1Crop = Bitmap.createBitmap(bmp1Full, cropLeft, cropTop, cropWidth, cropHeight)
                val bmp2Crop = Bitmap.createBitmap(bmp2Full, cropLeft, cropTop, cropWidth, cropHeight)
                try {
                    val targetW = 160
                    val targetH = (targetW * (bmp1Crop.height.toFloat() / bmp1Crop.width.toFloat())).toInt()
                        .coerceAtLeast(90)
                    val b1 = Bitmap.createScaledBitmap(bmp1Crop, targetW, targetH, true)
                    val b2 = Bitmap.createScaledBitmap(bmp2Crop, targetW, targetH, true)
                    try {
                        val diff = computeMeanAbsPixelDiff(b1, b2)
                        val diffText = String.format(Locale.US, "%.4f", diff)
                        val thrText = String.format(Locale.US, "%.4f", FRAME_DIFF_PASS_THRESHOLD)
                        devOp(PrefsStore.getAlias() ?: "unknown", "${sanitizeFrameDiffTagForFilename(tag)}：帧差计算完成 diff=$diffText")
                        devOp(PrefsStore.getAlias() ?: "unknown", "${sanitizeFrameDiffTagForFilename(tag)}：帧差阈值=$thrText，结果=${if (diff >= FRAME_DIFF_PASS_THRESHOLD) "通过" else "未通过"}")
                        val passed = diff >= FRAME_DIFF_PASS_THRESHOLD
                        if (!passed && exportGrowthFrameDiffFailureToGallery) {
                            saveGrowthFrameDiffFailureScreensToGallery(service, bmp1Full, bmp2Full, tag)
                        }
                        return passed
                    } finally {
                        b1.recycle()
                        b2.recycle()
                    }
                } finally {
                    bmp1Crop.recycle()
                    bmp2Crop.recycle()
                }
            } finally {
                bmp1Full.recycle()
                bmp2Full.recycle()
            }
        } catch (ex: Exception) {
            
            return false
        } finally {
            afterTap.delete()
        }
    }

    private suspend fun detectVideoMotionByFrameDiff(
        service: Context,
        debugTag: String = "no_before_ref",
        motionTagForFailureExport: String = debugTag,
        exportGrowthFrameDiffFailureToGallery: Boolean = false,
    ): Boolean {
        val screenshot1 = File(service.cacheDir, "frame_diff_1.png")
        val screenshot2 = File(service.cacheDir, "frame_diff_2.png")
        try {
            if (!runSuCommand("screencap -p ${screenshot1.absolutePath}")) return false
            // 帧间隔略拉长，提升对“慢加载/低帧率/转场”的命中率
            delay(500)
            if (!runSuCommand("screencap -p ${screenshot2.absolutePath}")) return false

            val bmp1Full = BitmapFactory.decodeFile(screenshot1.absolutePath) ?: return false
            val bmp2Full = BitmapFactory.decodeFile(screenshot2.absolutePath) ?: run {
                bmp1Full.recycle()
                return false
            }
            try {
                // 只取中间偏上的视频主体区域，避开底部评论区/顶部状态栏的抖动
                val w = bmp1Full.width.coerceAtMost(bmp2Full.width)
                val h = bmp1Full.height.coerceAtMost(bmp2Full.height)
                if (w <= 0 || h <= 0) return false
                val cropLeft = 0
                val cropTop = (h * 0.10f).toInt().coerceIn(0, h - 1)
                val cropWidth = w
                // 帧差裁剪区间统一为 10%~40%
                val cropHeight = (h * 0.30f).toInt().coerceIn(1, h - cropTop)
                val bmp1Crop = Bitmap.createBitmap(bmp1Full, cropLeft, cropTop, cropWidth, cropHeight)
                val bmp2Crop = Bitmap.createBitmap(bmp2Full, cropLeft, cropTop, cropWidth, cropHeight)
                try {
                    // 下采样到小尺寸以降低计算开销
                    val targetW = 160
                    val targetH = (targetW * (bmp1Crop.height.toFloat() / bmp1Crop.width.toFloat())).toInt()
                        .coerceAtLeast(90)
                    val b1 = Bitmap.createScaledBitmap(bmp1Crop, targetW, targetH, true)
                    val b2 = Bitmap.createScaledBitmap(bmp2Crop, targetW, targetH, true)
                    try {
                        val diff = computeMeanAbsPixelDiff(b1, b2)
                        val diffText = String.format(Locale.US, "%.4f", diff)
                        val thrText = String.format(Locale.US, "%.4f", FRAME_DIFF_PASS_THRESHOLD)
                        val alias = PrefsStore.getAlias() ?: "unknown"
                        devOp(alias, "${sanitizeFrameDiffTagForFilename(debugTag)}：帧差计算完成 diff=$diffText")
                        devOp(alias, "${sanitizeFrameDiffTagForFilename(debugTag)}：帧差阈值=$thrText，结果=${if (diff >= FRAME_DIFF_PASS_THRESHOLD) "通过" else "未通过"}")
                        val passed = diff >= FRAME_DIFF_PASS_THRESHOLD
                        if (!passed && exportGrowthFrameDiffFailureToGallery) {
                            saveGrowthFrameDiffFailureScreensToGallery(service, bmp1Full, bmp2Full, motionTagForFailureExport)
                        }
                        return passed
                    } finally {
                        b1.recycle()
                        b2.recycle()
                    }
                } finally {
                    bmp1Crop.recycle()
                    bmp2Crop.recycle()
                }
            } finally {
                bmp1Full.recycle()
                bmp2Full.recycle()
            }
        } catch (ex: Exception) {
            
            return false
        } finally {
            screenshot1.delete()
            screenshot2.delete()
        }
    }

    /**
     * 互点专用：前后帧对比使用**整屏**参与帧差（与 growth/主播等的 10%~40% 条带区分）。
     * 无「点击前」参考图时走 [detectVideoMotionByFrameDiffMutual]。
     */
    private suspend fun detectVideoMotionByFrameDiffFromBeforeTapMutual(
        service: Context,
        beforeTapReference: File?,
        afterTapDelayMs: Long = 1000L,
        tag: String = "互点-帧差",
    ): Boolean {
        if (beforeTapReference == null || !beforeTapReference.exists()) {
            return detectVideoMotionByFrameDiffMutual(
                service,
                debugTag = "fb_${sanitizeFrameDiffTagForFilename(tag)}",
            )
        }
        val afterTap = File(service.cacheDir, "frame_diff_mutual_after_tap.png")
        try {
            val waitMs = afterTapDelayMs.coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
            if (!runSuCommand("screencap -p ${afterTap.absolutePath}")) return false

            val bmp1Full = BitmapFactory.decodeFile(beforeTapReference.absolutePath) ?: return false
            val bmp2Full = BitmapFactory.decodeFile(afterTap.absolutePath) ?: run {
                bmp1Full.recycle()
                return false
            }
            try {
                val w = bmp1Full.width.coerceAtMost(bmp2Full.width)
                val h = bmp1Full.height.coerceAtMost(bmp2Full.height)
                if (w <= 0 || h <= 0) return false
                val cropLeft = 0
                val cropTop = 0
                val cropWidth = w
                val cropHeight = h
                val bmp1Crop = Bitmap.createBitmap(bmp1Full, cropLeft, cropTop, cropWidth, cropHeight)
                val bmp2Crop = Bitmap.createBitmap(bmp2Full, cropLeft, cropTop, cropWidth, cropHeight)
                try {
                    val targetW = 160
                    val targetH = (targetW * (bmp1Crop.height.toFloat() / bmp1Crop.width.toFloat())).toInt()
                        .coerceAtLeast(90)
                    val b1 = Bitmap.createScaledBitmap(bmp1Crop, targetW, targetH, true)
                    val b2 = Bitmap.createScaledBitmap(bmp2Crop, targetW, targetH, true)
                    try {
                        val diff = computeMeanAbsPixelDiff(b1, b2)
                        val diffText = String.format(Locale.US, "%.4f", diff)
                        val thrText = String.format(Locale.US, "%.4f", FRAME_DIFF_PASS_THRESHOLD)
                        val alias = PrefsStore.getAlias() ?: "unknown"
                        devOp(alias, "${sanitizeFrameDiffTagForFilename(tag)}（互点全屏）：帧差计算完成 diff=$diffText")
                        devOp(alias, "${sanitizeFrameDiffTagForFilename(tag)}（互点全屏）：帧差阈值=$thrText，结果=${if (diff >= FRAME_DIFF_PASS_THRESHOLD) "通过" else "未通过"}")
                        return diff >= FRAME_DIFF_PASS_THRESHOLD
                    } finally {
                        b1.recycle()
                        b2.recycle()
                    }
                } finally {
                    bmp1Crop.recycle()
                    bmp2Crop.recycle()
                }
            } finally {
                bmp1Full.recycle()
                bmp2Full.recycle()
            }
        } catch (_: Exception) {
            return false
        } finally {
            afterTap.delete()
        }
    }

    /** 互点专用：两帧截屏对比，**整屏**参与帧差。 */
    private suspend fun detectVideoMotionByFrameDiffMutual(
        service: Context,
        debugTag: String = "no_before_ref",
    ): Boolean {
        val screenshot1 = File(service.cacheDir, "frame_diff_mutual_1.png")
        val screenshot2 = File(service.cacheDir, "frame_diff_mutual_2.png")
        try {
            if (!runSuCommand("screencap -p ${screenshot1.absolutePath}")) return false
            delay(500)
            if (!runSuCommand("screencap -p ${screenshot2.absolutePath}")) return false

            val bmp1Full = BitmapFactory.decodeFile(screenshot1.absolutePath) ?: return false
            val bmp2Full = BitmapFactory.decodeFile(screenshot2.absolutePath) ?: run {
                bmp1Full.recycle()
                return false
            }
            try {
                val w = bmp1Full.width.coerceAtMost(bmp2Full.width)
                val h = bmp1Full.height.coerceAtMost(bmp2Full.height)
                if (w <= 0 || h <= 0) return false
                val bmp1Crop = Bitmap.createBitmap(bmp1Full, 0, 0, w, h)
                val bmp2Crop = Bitmap.createBitmap(bmp2Full, 0, 0, w, h)
                try {
                    val targetW = 160
                    val targetH = (targetW * (bmp1Crop.height.toFloat() / bmp1Crop.width.toFloat())).toInt()
                        .coerceAtLeast(90)
                    val b1 = Bitmap.createScaledBitmap(bmp1Crop, targetW, targetH, true)
                    val b2 = Bitmap.createScaledBitmap(bmp2Crop, targetW, targetH, true)
                    try {
                        val diff = computeMeanAbsPixelDiff(b1, b2)
                        val diffText = String.format(Locale.US, "%.4f", diff)
                        val thrText = String.format(Locale.US, "%.4f", FRAME_DIFF_PASS_THRESHOLD)
                        val alias = PrefsStore.getAlias() ?: "unknown"
                        devOp(alias, "${sanitizeFrameDiffTagForFilename(debugTag)}（互点全屏）：帧差计算完成 diff=$diffText")
                        devOp(alias, "${sanitizeFrameDiffTagForFilename(debugTag)}（互点全屏）：帧差阈值=$thrText，结果=${if (diff >= FRAME_DIFF_PASS_THRESHOLD) "通过" else "未通过"}")
                        return diff >= FRAME_DIFF_PASS_THRESHOLD
                    } finally {
                        b1.recycle()
                        b2.recycle()
                    }
                } finally {
                    bmp1Crop.recycle()
                    bmp2Crop.recycle()
                }
            } finally {
                bmp1Full.recycle()
                bmp2Full.recycle()
            }
        } catch (_: Exception) {
            return false
        } finally {
            screenshot1.delete()
            screenshot2.delete()
        }
    }

    private fun computeMeanAbsPixelDiff(a: Bitmap, b: Bitmap): Float {
        val w = a.width.coerceAtMost(b.width)
        val h = a.height.coerceAtMost(b.height)
        if (w <= 0 || h <= 0) return 0f
        var sum = 0L
        var count = 0L
        // 每隔 2px 采样一次，进一步降成本
        val step = 2
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p1 = a.getPixel(x, y)
                val p2 = b.getPixel(x, y)
                val r1 = (p1 shr 16) and 0xFF
                val g1 = (p1 shr 8) and 0xFF
                val b1 = p1 and 0xFF
                val r2 = (p2 shr 16) and 0xFF
                val g2 = (p2 shr 8) and 0xFF
                val b2 = p2 and 0xFF
                sum += kotlin.math.abs(r1 - r2) + kotlin.math.abs(g1 - g2) + kotlin.math.abs(b1 - b2)
                count += 3
                x += step
            }
            y += step
        }
        if (count == 0L) return 0f
        return (sum.toDouble() / (count.toDouble() * 255.0)).toFloat()
    }

    private suspend fun detectSearchInUpperHalfByOcr(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "search_upper_half_ocr.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return false
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return false
        }
        observeAccountStatusOnScreenshot(service, bitmap, "growth_search_upper_half")

        val cropHeight = (bitmap.height * 0.5f).toInt().coerceAtLeast(1)
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropHeight)
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            fun norm(s: String): String = s.lowercase()
                .replace('0', 'o')
                .replace('5', 's')
                .replace('8', 'b')
                .replace('|', 'l')
                .replace(Regex("[^a-z]+"), "")

            val linesPreview = result.textBlocks
                .flatMap { it.lines }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .take(40)
                .joinToString(" | ")
            

            val target = "search"
            val candidates = result.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .mapNotNull { e ->
                    val raw = e.text.trim()
                    val t = norm(raw)
                    val score = alphanumericSimilarity(target, t)
                    val matched = t == target ||
                        t.contains(target) ||
                        (alphanumericLengthDiffWithin(target, t, 2) && score >= 0.55)
                    if (!matched) return@mapNotNull null
                    val box = e.boundingBox ?: return@mapNotNull null
                    Triple(raw, t, box)
                }

            val debugText = if (candidates.isEmpty()) {
                "(无Search候选)"
            } else {
                candidates.mapIndexed { idx, c ->
                    "#${idx + 1} raw='${c.first}' norm='${c.second}' x=${c.third.centerX()} y=${c.third.centerY()}"
                }.joinToString(" | ")
            }
            

            candidates.isNotEmpty()
        } catch (ex: Exception) {
            rethrowTaskLogoutIfNeeded(ex)
            false
        } finally {
            recognizer.close()
            bitmap.recycle()
            croppedBitmap.recycle()
            screenshotFile.delete()
        }
    }

    private suspend fun detectCommentInputByOcr(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val p = findAddCommentPointByOcr(service)
        val hit = p != null
        if (hit) {
            
        } else {
            
        }
        return hit
    }

    private data class OcrTextHit(
        val text: String,
        val box: Rect,
        val cropTop: Int,
        val imageWidth: Int,
        val imageHeight: Int,
        val preview: String = "",
    )

    private suspend fun findFirstOcrElementByTexts(
        service: Context,
        screenshotName: String,
        cropTopRatio: Float,
        cropHeightRatio: Float,
        targets: List<String>,
        textNormalizer: (String) -> String = { s ->
            s.lowercase()
                .replace('0', 'o')
                .replace("…", "...")
                .replace(Regex("\\s+"), " ")
                .trim()
        },
    ): OcrTextHit? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, screenshotName)
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return null

        // 关闭 OCR 调试截图落盘：当前阶段仅保留识别文本日志
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return null
        }
        val ocrTag = screenshotName.removeSuffix(".png")
        observeAccountStatusOnScreenshot(service, bitmap, ocrTag)

        val width = bitmap.width
        val height = bitmap.height
        val cropTop = (height * cropTopRatio).toInt().coerceAtLeast(0)
        val cropHeight = (height * cropHeightRatio).toInt().coerceAtLeast(1)
        val safeHeight = cropHeight.coerceAtMost(height - cropTop)
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, cropTop, width, safeHeight)
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val normalizedTargets = targets.map(textNormalizer)
            val allLines = result.textBlocks.flatMap { it.lines }
            val allElements = allLines.flatMap { it.elements }
            val preview = allLines.map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .take(40)
                .joinToString(" | ")
            

            val commentCandidates = allElements.mapNotNull { element ->
                val raw = element.text.trim()
                if (raw.isEmpty()) return@mapNotNull null
                val normalized = textNormalizer(raw)
                val box = element.boundingBox ?: return@mapNotNull null
                val score = alphanumericSimilarity("comment", normalized)
                val maybeRelated = normalized.contains("add") || normalized.contains("comment") || score >= 0.45
                if (!maybeRelated) return@mapNotNull null
                "raw='$raw' norm='$normalized' score=${"%.2f".format(score)} x=${box.centerX()} y=${cropTop + box.centerY()}"
            }.take(20)
            

            fun containsTargetByTokenOrder(text: String, target: String): Boolean {
                val tokens = target
                    .split(Regex("\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (tokens.isEmpty()) return false

                var searchFrom = 0
                for (token in tokens) {
                    val matchRange = if (token == "comment") {
                        // 放宽 comment：允许 comment 后面跟任意字符（数字/符号/字母）
                        Regex("comment\\S*").find(text, startIndex = searchFrom)?.range
                    } else {
                        val idx = text.indexOf(token, startIndex = searchFrom)
                        if (idx >= 0) idx until (idx + token.length) else null
                    } ?: return false

                    searchFrom = matchRange.last + 1
                }
                return true
            }

            fun normalizeForCombinedMatch(raw: String): String {
                return textNormalizer(raw)
                    .replace("|", " ")
                    .replace(Regex("[^a-z0-9]+"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }

            // 先用“整行”匹配，兼容 OCR 把 Add/comment 拆成片段（如 Add | comment...）
            val lineHit = result.textBlocks
                .flatMap { it.lines }
                .firstOrNull { line ->
                    val normalizedLine = normalizeForCombinedMatch(line.text)
                    normalizedTargets.any { t -> containsTargetByTokenOrder(normalizedLine, t) }
                }

            val lineBox = lineHit?.boundingBox
            if (lineBox != null) {
                OcrTextHit(
                    text = lineHit.text,
                    box = lineBox,
                    cropTop = cropTop,
                    imageWidth = width,
                    imageHeight = height,
                    preview = preview,
                )
            } else {
                val hitElement = allElements.firstOrNull { element ->
                    val raw = element.text.trim()
                    if (raw.isEmpty()) return@firstOrNull false
                    val normalized = textNormalizer(raw)
                    normalizedTargets.any { t ->
                        // 强化文本过滤：要求目标词按顺序出现（如 add 后必须有 comment），
                        // 防止仅命中 add 造成误判。
                        containsTargetByTokenOrder(normalized, t)
                    }
                }

                val box = hitElement?.boundingBox
                if (box == null) {
                    OcrTextHit(
                        text = "",
                        box = Rect(0, 0, 0, 0),
                        cropTop = cropTop,
                        imageWidth = width,
                        imageHeight = height,
                        preview = preview,
                    )
                } else {
                    OcrTextHit(
                        text = hitElement.text,
                        box = box,
                        cropTop = cropTop,
                        imageWidth = width,
                        imageHeight = height,
                        preview = preview,
                    )
                }
            }
        } catch (ex: Exception) {
            rethrowTaskLogoutIfNeeded(ex)
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
            croppedBitmap.recycle()
            screenshotFile.delete()
        }
    }

    private suspend fun findAddCommentPointByOcr(service: Context): Point? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val targets = listOf(
            "add comment",
            "add comment...",
            "add comment…",
        )

        val hit = try {
            findFirstOcrElementByTexts(
                service = service,
                screenshotName = "comment_input_ocr.png",
                cropTopRatio = 0.78f,
                cropHeightRatio = 0.22f,
                targets = targets,
            )
        } catch (ex: Exception) {
            rethrowTaskLogoutIfNeeded(ex)
            null
        }

        if (hit == null || hit.text.isBlank() || hit.box.isEmpty) {
            
            return null
        }

        val x = hit.box.centerX().coerceIn(1, hit.imageWidth - 1)
        val y = (hit.cropTop + hit.box.centerY() + 6).coerceIn(1, hit.imageHeight - 1)
        
        return Point(x, y)
    }

    private suspend fun processFollowersList(service: Context, config: AppConfig) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (growthYieldOrNull(alias, "处理粉丝列表")) {
            forceStopTikTokIfExclusivePreempt(alias, "处理粉丝列表")
            return
        }
        var reachedEnd = false
        var rounds = 0
        // 主循环：固定最多 7 轮；若中途判定到底则提前结束
        while (!reachedEnd && rounds < 7) {
            if (growthYieldOrNull(alias, "处理粉丝列表循环")) {
                forceStopTikTokIfExclusivePreempt(alias, "处理粉丝列表循环")
                return
            }
            if (mutualClickOrNull(alias, "处理粉丝列表循环")) {
                return
            }
            val followers = findFollowersEntriesByOcr(service)
            if (followers.isEmpty()) {
                // 已进入 Followers 流程后，整屏 OCR 却 0 条可点行：视为页面错乱/非列表态，不应当作正常结束回 Users。
                LogCenter.append(alias, "粉丝列表 OCR 未命中任何用户，判定页面异常，优先导航返回回首页")
                val restarted = recoverToHomeViaBackOrRestart(service, "粉丝列表 OCR 异常")
                devOp(alias, "TikTok 恢复结果：${if (restarted) "success" else "failed"}")
                if (restarted) {
                    delay(3000)
                }
                throw GrowthFlowRestartRequired("粉丝列表 OCR 未命中条目，页面异常，已执行重启 TikTok")
            }
            for (entry in followers) {
                if (growthYieldOrNull(alias, "处理粉丝列表单条")) {
                    forceStopTikTokIfExclusivePreempt(alias, "处理粉丝列表单条")
                    return
                }
                runSuCommand("input tap ${entry.clickPoint.x} ${entry.clickPoint.y}")
                if (!growthDelay(alias, Random.nextLong(1200, 1600), "粉丝列表-点头像")) return
                // 新增：按配置概率直接跳过点击作品，和无作品/私密保持同样处理
                if (config.skip_post_click_prob > 0.0 && Random.nextDouble() < config.skip_post_click_prob) {
                    LogCenter.append(alias, "命中跳过作品概率，按无作品/私密同样流程处理")
                    if (checkNoVideosAndGoBackWithRetry(service)) {
                        LogCenter.append(alias, "跳过作品后已回退到粉丝列表，继续下一条")
                        delay(Random.nextLong(500, 800))
                        continue
                    }
                    // 未命中“无作品/私密”文案也要强制跳过：命中概率时不进入作品流程
                    clickProfileBack(service, "命中跳过作品概率")
                    delay(Random.nextLong(600, 900))
                    LogCenter.append(alias, "跳过作品：已执行导航返回，继续下一条")
                    delay(Random.nextLong(500, 800))
                    continue
                }
                // 优先处理「无作品」：命中则已自动回退到粉丝列表，直接跳过该用户
                if (checkNoVideosAndGoBackWithRetry(service)) {
                    LogCenter.append(alias, "该粉丝用户无作品，已执行导航返回，跳过")
                    delay(Random.nextLong(500, 800))
                    continue
                }
                // 点进作品后立刻开始执行作品内动作，同时利用二次判定间隔；二次判定失败则立刻停止动作
                val postFlowDone = enterPostAndRunInteractionsWithSecondCheck(service, config)
                // 若作品内动作被取消，依旧给一点缓冲，避免紧接着操作下一条导致 UI 抢占
                delay(Random.nextLong(450, 750))
            }
            // 测试用：只滑动 1 轮，便于观察每轮边界与回调链路
            val avgRow = followers.map { it.rowHeight }.average().toFloat().coerceAtLeast(1f)
            val scrollDistance = (avgRow * 7f).coerceAtLeast(240f)
            val metrics = service.resources.displayMetrics
            val startX = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.8f
            val endY = (startY - scrollDistance).coerceAtLeast(metrics.heightPixels * 0.2f)
            LogCenter.append(alias, "粉丝列表下滑")
            runSuCommand("input swipe ${startX.toInt()} ${startY.toInt()} ${startX.toInt()} ${endY.toInt()} 450")
            delay(Random.nextLong(900, 1200))
            reachedEnd = detectFollowersListEnd(service)
            LogCenter.append(alias, "粉丝列表下滑后到底判定结果=${reachedEnd}")
            rounds++
        }
        // 无论是到底还是中断：都回退到 Users 选择界面，便于继续下一个主用户名
        backToUsersSelectionFromFollowersList(service)
        lastEndedAtUsersSelection = true
    }

    private suspend fun backToUsersSelectionFromFollowersList(service: Context) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        navBack(alias, "粉丝列表结束后回到上一级（1/2）")
        delay(Random.nextLong(900, 1200))
        navBack(alias, "粉丝列表结束后回到 Users 选择界面（2/2）")
        delay(Random.nextLong(900, 1200))
    }

    private suspend fun detectFollowersListEnd(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        for (popupRetry in 0..1) {
            val screenshotFile = File(service.cacheDir, "followers_end_check.png")
            if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return false
            val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
                screenshotFile.delete()
                return false
            }
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val result = suspendCancellableCoroutine { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                observeAccountStatusOnOcrText(service, result, "growth_followers_end_check")
                if (
                    popupRetry == 0 &&
                    handleOcrVisionInlineInterruptions(
                        service,
                        result,
                        "growth_followers_end_check",
                        "涨粉粉丝列表到底检测",
                    ) != null
                ) {
                    delay(450L)
                    continue
                }
                val rawTexts = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
                val normalized = rawTexts.map { it.lowercase() }
                val followCount = result.textBlocks
                    .flatMap { it.lines }
                    .flatMap { it.elements }
                    .count { element ->
                        element.text.trim().equals("Follow", ignoreCase = true)
                    }
                val endMarkers = listOf(
                    "no more results",
                    "you've reached the end",
                    "youre all caught up",
                    "end of list",
                )
                val hitEndMarker = normalized.any { line -> endMarkers.any { marker -> line.contains(marker) } }
                val reachedEnd = hitEndMarker || followCount == 0
                LogCenter.append(
                    alias,
                    "粉丝列表到底判定: followCount=$followCount marker=$hitEndMarker result=$reachedEnd",
                )
                return reachedEnd
            } catch (ex: Exception) {
                rethrowTaskLogoutIfNeeded(ex)
                LogCenter.append(alias, "粉丝列表到底 OCR 判定失败：${ex.message}")
                return false
            } finally {
                recognizer.close()
                bitmap.recycle()
                screenshotFile.delete()
            }
        }
        return false
    }

    /** 粉丝列表 OCR 每行触点：涨粉为 Follow 左侧（进粉丝主页）；互点为 Follow 框中心（点关注）。 */
    private enum class FollowersListOcrTapKind {
        OPEN_PROFILE_LEFT_OF_FOLLOW,
        FOLLOW_BUTTON_CENTER,
    }

    private data class FollowerEntry(val clickPoint: Point, val rowHeight: Int)

    /**
     * @param verificationOnly 为 true 时用于「是否已进入粉丝列表」的快速判定：误入搜索根 Tab 只返回空列表，
     * 不触发 TikTok 重启（避免在 clickFollowersAndVerify 重试环内误重启）。
     * @param tapKind 涨粉用 [OPEN_PROFILE_LEFT_OF_FOLLOW]；互点粉丝列表内点关注用 [FOLLOW_BUTTON_CENTER]。
     */
    private suspend fun findFollowersEntriesByOcr(
        service: Context,
        verificationOnly: Boolean = false,
        tapKind: FollowersListOcrTapKind = FollowersListOcrTapKind.OPEN_PROFILE_LEFT_OF_FOLLOW,
    ): List<FollowerEntry> {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "识别粉丝列表")) return emptyList()
        for (popupRetry in 0..1) {
            val screenshotFile = File(service.cacheDir, "followers_ocr.png")
            if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return emptyList()
            val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
                screenshotFile.delete()
                return emptyList()
            }
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val result = suspendCancellableCoroutine { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                observeAccountStatusOnOcrText(service, result, "growth_followers_ocr")
                if (
                    popupRetry == 0 &&
                    handleOcrVisionInlineInterruptions(
                        service,
                        result,
                        "growth_followers_ocr",
                        "涨粉粉丝列表 OCR",
                    ) != null
                ) {
                    delay(450L)
                    continue
                }
                if (isSearchRootUsernameTabsLikelyFromOcrResult(result)) {
                if (verificationOnly) {
                    LogCenter.append(alias, "粉丝列表 OCR（校验）：误判为搜索根 Tab 区域，跳过")
                    return emptyList()
                }
                LogCenter.append(
                    alias,
                    "粉丝列表 OCR：检测到搜索根用户界面特征（top/users/videos/photos/shop 命中≥3），停止当前流程并重启 TikTok",
                )
                val restarted = restartTikTokForRecovery()
                devOp(alias, "TikTok 强制重启结果：${if (restarted) "success" else "failed"}")
                if (restarted) {
                    delay(3000)
                }
                throw GrowthFlowRestartRequired("误入搜索根用户界面，已执行重启 TikTok")
            }
            val followBoxes = result.textBlocks.flatMap { it.lines }.flatMap { line ->
                line.elements.mapNotNull { element ->
                    if (isFollowersListFollowOcrLabel(element.text)) {
                        element.boundingBox
                    } else null
                }
            }
            if (followBoxes.isEmpty()) {
                LogCenter.append(alias, "粉丝列表 OCR 未命中 Follow 按钮")
                return emptyList()
            }

            // ML Kit 返回的 element 顺序不保证自上而下；先按行（Follow 框中心 Y）排序，避免首条点到非第一行。
            val followBoxesTopToBottom = followBoxes.sortedBy { it.centerY() }

            // 用 Follow 按钮的纵向间距估算“列表行高”（比按钮自身 height 更稳定）。
            // 取相邻 centerY 差值的中位数，作为每次滑动 1 行的像素距离。
            val sortedCentersY = followBoxesTopToBottom.map { it.centerY() }.sorted()
            val diffs = sortedCentersY.zipWithNext { a, b -> (b - a).coerceAtLeast(1) }
                .filter { it in 12..900 }
            val estimatedRowHeight = when {
                diffs.isNotEmpty() -> {
                    val mid = diffs.size / 2
                    val s = diffs.sorted()
                    if (s.size % 2 == 1) s[mid] else ((s[mid - 1] + s[mid]) / 2)
                }
                else -> {
                    // 极端情况：只有 1 个 Follow 或 OCR 抖动；用按钮高度 * 4 做兜底
                    (followBoxesTopToBottom.first().height() * 4).coerceAtLeast(120)
                }
            }

            val entries = followBoxesTopToBottom.mapNotNull { box ->
                val centerY = box.centerY().coerceAtLeast(1)
                if (centerY < bitmap.height * 0.18f) {
                    null
                } else {
                    val clickX = when (tapKind) {
                        FollowersListOcrTapKind.OPEN_PROFILE_LEFT_OF_FOLLOW ->
                            (box.left - (box.width() * 1.2f)).toInt().coerceAtLeast(1)
                        FollowersListOcrTapKind.FOLLOW_BUTTON_CENTER ->
                            box.centerX().coerceAtLeast(1)
                    }
                    FollowerEntry(Point(clickX, centerY), estimatedRowHeight)
                }
            }
                return entries
            } catch (ex: GrowthFlowRestartRequired) {
                throw ex
            } catch (ex: Exception) {
                rethrowTaskLogoutIfNeeded(ex)
                LogCenter.append(alias, "粉丝列表 OCR 识别失败：${ex.message}")
                return emptyList()
            } finally {
                recognizer.close()
                bitmap.recycle()
                screenshotFile.delete()
            }
        }
        return emptyList()
    }



    private suspend fun runPostInteractions(service: Context, config: AppConfig) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        data class Action(
            val name: String,
            val weight: Double,
            val dailyLimit: Int,
            val run: suspend () -> Unit,
        )

        fun canRun(action: Action): Boolean {
            if (action.weight <= 0) return false
            if (action.dailyLimit <= 0) return false
            val used = PrefsStore.getDailyActionCount(action.name)
            return used < action.dailyLimit
        }

        fun pickOne(available: List<Action>): Action? {
            val total = available.sumOf { it.weight.coerceAtLeast(0.0) }
            if (total <= 0.0) return null
            var r = Random.nextDouble(0.0, total)
            for (a in available) {
                r -= a.weight.coerceAtLeast(0.0)
                if (r <= 0) return a
            }
            return available.lastOrNull()
        }

        val allActions = listOf(
            Action("like", config.like_prob, config.like_daily_limit) { performLike(service) },
            Action("comment", config.comment_prob, config.comment_daily_limit) { performComment(service, config) },
            Action("favorite", config.favorite_prob, config.favorite_daily_limit) { performFavorite(service) },
            Action("share", config.share_prob, config.share_daily_limit) { performShare(service) },
        )

        val actionsToRun: List<Action> = if (config.action_single) {
            val picked = pickOne(allActions.filter { canRun(it) })
            if (picked == null) {
                
                emptyList()
            } else listOf(picked)
        } else {
            allActions.filter { canRun(it) }
        }

        for (action in actionsToRun) {
            devOp(alias, "执行互动：${action.name}")
            action.run()
            PrefsStore.incrementDailyActionCount(action.name)
            delay(700)
        }
        navBack(alias, "从作品页回到用户主页")
        delay(Random.nextLong(800, 1200))
        navBack(alias, "从用户主页回到粉丝列表")
    }

    private suspend fun runPostInteractionsCancellable(service: Context, config: AppConfig) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val postStayMs = randomDelayMsFromSecondsRange(config.video_stay_min, config.video_stay_max)
        devOp(alias, "作品页停留 ${postStayMs}ms")
        delay(postStayMs)

        data class Action(
            val name: String,
            val weight: Double,
            val dailyLimit: Int,
            val run: suspend () -> Unit,
        )

        fun canRun(action: Action): Boolean {
            if (action.weight <= 0) return false
            if (action.dailyLimit <= 0) return false
            val used = PrefsStore.getDailyActionCount(action.name)
            return used < action.dailyLimit
        }

        fun pickOne(available: List<Action>): Action? {
            val total = available.sumOf { it.weight.coerceAtLeast(0.0) }
            if (total <= 0.0) return null
            var r = Random.nextDouble(0.0, total)
            for (a in available) {
                r -= a.weight.coerceAtLeast(0.0)
                if (r <= 0) return a
            }
            return available.lastOrNull()
        }

        val allActions = listOf(
            Action(
                name = "like",
                weight = config.like_prob,
                dailyLimit = config.like_daily_limit,
                run = { performLike(service) },
            ),
            Action(
                name = "comment",
                weight = config.comment_prob,
                dailyLimit = config.comment_daily_limit,
                run = { performComment(service, config) },
            ),
            Action(
                name = "favorite",
                weight = config.favorite_prob,
                dailyLimit = config.favorite_daily_limit,
                run = { performFavorite(service, useOcrAnchor = false) },
            ),
            Action(
                name = "share",
                weight = config.share_prob,
                dailyLimit = config.share_daily_limit,
                run = { performShare(service, useOcrRepost = false) },
            ),
        )

        val actionsToRun: List<Action> = if (config.action_single) {
            val available = allActions.filter { canRun(it) }
            val picked = pickOne(available)
            if (picked == null) {
                
                emptyList()
            } else {
                listOf(picked)
            }
        } else {
            // 兼容旧模式：按原逻辑依次执行（仍会按每日上限跳过）
            allActions.filter { canRun(it) }
        }

        for (action in actionsToRun) {
            coroutineContext.ensureActive()
            if (mutualClickOrNull(alias, "作品互动执行前")) return
            devOp(alias, "执行互动：${action.name}")
            action.run()
            PrefsStore.incrementDailyActionCount(action.name)
            coroutineContext.ensureActive()
            delay(1000)
        }
        coroutineContext.ensureActive()
        navBack(alias, "从作品页回到用户主页")
        delay(Random.nextLong(800, 1200))
        coroutineContext.ensureActive()
        navBack(alias, "从用户主页回到粉丝列表")
    }

    private suspend fun performComment(service: Context, config: AppConfig) {
        val mode = config.run_mode.trim().lowercase()

        if (mode == ServerRunMode.MUTUAL) {
            performMutualCommentTextDirectFixedCoordinate(service, config)
            return
        }
        if (mode == ServerRunMode.GROWTH || mode == ServerRunMode.DIRECT) {
            performCommentTextDirectFixed(service, config)
            return
        }
        performCommentText(service, config)
    }

    private data class LivePostOcrSnapshot(
        val searchPoint: Point?,
        val capturedAtMs: Long,
    )

    private var livePostOcrSnapshot: LivePostOcrSnapshot? = null

    private suspend fun buildLivePostOcrSnapshot(service: Context): LivePostOcrSnapshot {
        val searchPoint = findSearchPointByOcr(service)
        return LivePostOcrSnapshot(
            searchPoint = searchPoint,
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    private suspend fun findSearchPointByOcr(service: Context): Point? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "search_anchor_ocr.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return null
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return null
        }
        observeAccountStatusOnScreenshot(service, bitmap, "growth_live_search_anchor")
        val cropTop = (bitmap.height * 0.74f).toInt().coerceIn(0, bitmap.height - 1)
        val cropHeight = (bitmap.height - cropTop).coerceAtLeast(1)
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, cropHeight)
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            fun norm(s: String): String = s.lowercase()
                .replace('0', 'o')
                .replace('5', 's')
                .replace('8', 'b')
                .replace('|', 'l')
                .replace(Regex("[^a-z]+"), "")

            val elements = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
            val target = "search"

            val candidates = elements.mapNotNull { e ->
                val raw = e.text.trim()
                val t = norm(raw)
                val score = alphanumericSimilarity(target, t)
                val matched = t == target ||
                    t.contains(target) ||
                    (alphanumericLengthDiffWithin(target, t, 2) && score >= 0.55)
                if (!matched) return@mapNotNull null
                val box = e.boundingBox ?: return@mapNotNull null
                Triple(raw, t, box)
            }

            val debugText = if (candidates.isEmpty()) {
                "(无Search候选)"
            } else {
                candidates.mapIndexed { idx, c ->
                    val p = Point(c.third.centerX(), cropTop + c.third.centerY())
                    "#${idx + 1} raw='${c.first}' norm='${c.second}' x=${p.x} y=${p.y}"
                }.joinToString(" | ")
            }
            

            val box = candidates.firstOrNull()?.third
            box?.let { Point(it.centerX(), cropTop + it.centerY()) }
        } catch (ex: Exception) {
            rethrowTaskLogoutIfNeeded(ex)
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
            croppedBitmap.recycle()
            screenshotFile.delete()
        }
    }

    private suspend fun performLike(service: Context) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val t0 = System.currentTimeMillis()
        val metrics = service.resources.displayMetrics
        val tapX = (metrics.widthPixels * 0.5f).toInt().coerceIn(1, metrics.widthPixels - 1)
        val tapY = (metrics.heightPixels * 0.5f).toInt().coerceIn(1, metrics.heightPixels - 1)
        runSuCommand("input tap $tapX $tapY")
        delay(60)
        runSuCommand("input tap $tapX $tapY")
        devOp(alias, "执行点赞", System.currentTimeMillis() - t0)
    }

    private suspend fun performFavorite(
        service: Context,
        preSearchPoint: Point? = null,
        useOcrAnchor: Boolean = true,
    ) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val fixedX = (metrics.widthPixels * 0.92f).toInt().coerceIn(1, metrics.widthPixels - 1)
        val fixedY = (metrics.heightPixels * 0.75f).toInt().coerceIn(1, metrics.heightPixels - 1)
        val t0 = System.currentTimeMillis()
        devOp(alias, "执行收藏")
        if (!useOcrAnchor) {
            runSuCommand("input tap $fixedX $fixedY")
            devOp(alias, "收藏完成", System.currentTimeMillis() - t0)
            return
        }

        val searchPoint = preSearchPoint ?: findSearchPointByOcr(service)
        if (searchPoint == null) {
            runSuCommand("input tap $fixedX $fixedY")
            devOp(alias, "收藏完成（固定坐标）", System.currentTimeMillis() - t0)
            return
        }

        val offsetY = (metrics.heightPixels * 0.055f).toInt().coerceAtLeast(48)
        val tapY = (searchPoint.y - offsetY).coerceIn(1, metrics.heightPixels - 1)
        runSuCommand("input tap $fixedX $tapY")
        devOp(alias, "收藏完成", System.currentTimeMillis() - t0)
    }

    private suspend fun performShare(
        service: Context,
        preSearchPoint: Point? = null,
        useOcrRepost: Boolean = true,
    ) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val fixedX = (metrics.widthPixels * 0.92f).toInt().coerceIn(1, metrics.widthPixels - 1)
        val fixedY = (metrics.heightPixels * 0.82f).toInt().coerceIn(1, metrics.heightPixels - 1)
        val t0 = System.currentTimeMillis()
        devOp(alias, "点击转发按钮")
        runSuCommand("input tap $fixedX $fixedY")
        delay(Random.nextLong(800, 1200))

        if (!useOcrRepost) {
            val repostTapX = (metrics.widthPixels * 0.1111f).toInt().coerceIn(1, metrics.widthPixels - 1)
            val repostTapY = (metrics.heightPixels * 0.80f).toInt().coerceIn(1, metrics.heightPixels - 1)
            devOp(alias, "点击 Repost（固定坐标）")
            runSuCommand("input tap $repostTapX $repostTapY")
            delay(Random.nextLong(800, 1200))
            devOp(alias, "转发流程结束", System.currentTimeMillis() - t0)
            return
        }

        val repostPoint = findRepostByOcr(service)
        if (repostPoint != null) {
            devOp(alias, "点击 Repost（OCR）")
            runSuCommand("input tap ${repostPoint.x} ${repostPoint.y}")
        } else {
            devOp(alias, "未 OCR 到 Repost，跳过二次点击")
        }
        delay(Random.nextLong(800, 1200))
        devOp(alias, "转发流程结束", System.currentTimeMillis() - t0)
    }

    private suspend fun findRepostByOcr(service: Context): Point? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "repost_ocr.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return null
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return null
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val target = "repost"
            val matches = result.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .mapNotNull { element ->
                    val text = element.text.trim()
                    if (text.length in 5..7 && text.lowercase() == target) {
                        element.boundingBox
                    } else null
                }
            val box = matches.firstOrNull() ?: return null
            Point(box.centerX(), box.centerY())
        } catch (ex: Exception) {
            
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
            screenshotFile.delete()
        }
    }

//    private suspend fun performCommentEmoji(service: Context) {
//        val alias = PrefsStore.getAlias() ?: "unknown"
//        val metrics = service.resources.displayMetrics
//        val commentX = (metrics.widthPixels * 0.9f).toInt()
//        val commentY = (metrics.heightPixels * 0.74f).toInt()
//        
//        runSuCommand("input swipe $commentX $commentY $commentX $commentY 900")
//        delay(Random.nextLong(800, 1200))
//        val emojiRowY = (metrics.heightPixels * 0.75f).toInt()
//        val emojiXs = listOf(0.22f, 0.36f, 0.5f, 0.64f, 0.78f)
//        val pick = emojiXs.random()
//        val emojiX = (metrics.widthPixels * pick).toInt()
//        
//        runSuCommand("input tap $emojiX $emojiRowY")
//        delay(Random.nextLong(700, 1000))
//        runSuCommand("input keyevent 4")
//    }

    private suspend fun performCommentTextDirectFixed(service: Context, config: AppConfig) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics

        val commentX = (metrics.widthPixels * 0.5f).toInt().coerceIn(1, metrics.widthPixels - 1)
        val commentY = (metrics.heightPixels * 0.98f).toInt().coerceIn(1, metrics.heightPixels - 1)
        val t0 = System.currentTimeMillis()
        devOp(alias, "点击评论输入区域")
        runSuCommand("input tap $commentX $commentY")
        delay(900)

        val text = config.comment_texts.randomOrNull() ?: "Nice"
        devOp(alias, "输入评论文案：$text")
        val typed = tryInputCommentText(alias, text)
        if (!typed) {
            devOp(alias, "评论文本 root 输入失败，已跳过发送")
            return
        }
        delay(Random.nextLong(2000, 2501))

        val sendX = (metrics.widthPixels * 0.91f).toInt().coerceIn(1, metrics.widthPixels - 1)
        val sendY = (metrics.heightPixels * 0.57f).toInt().coerceIn(1, metrics.heightPixels - 1)
        devOp(alias, "点击发送评论")
        val sendTapped = runSuCommand("input tap $sendX $sendY")
        if (!sendTapped) {
            runSuCommand("input keyevent 66")
        }
        devOp(alias, "评论发送流程结束", System.currentTimeMillis() - t0)
    }

    /**
     * 互点发评：仅使用绑定模板内的 [AppConfig.mutual_comment_texts]；
     * 为空则不发评（绝不读取同模板 [AppConfig.comment_texts]）。
     */
    private fun pickMutualCommentPhrase(config: AppConfig, alias: String): String? {
        val mutualPool = config.mutual_comment_texts.map { it.trim() }.filter { it.isNotBlank() }
        if (mutualPool.isEmpty()) {
            devOp(alias, "互点：mutual_comment_texts 为空，跳过发评（不使用 comment_texts）")
            return null
        }
        return mutualPool.random()
    }

    /** 互点-仅关注：作品页右侧评论入口固定坐标（非底部评论输入条）。 */
    private suspend fun openMutualCommentPanelOnly(service: Context) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val tapX = (metrics.widthPixels * 0.924f).toInt().coerceIn(1, metrics.widthPixels - 1)
        val tapY = (metrics.heightPixels * 0.626f).toInt().coerceIn(1, metrics.heightPixels - 1)
        devOp(alias, "互点仅关注：点击评论入口固定点 ($tapX,$tapY) 屏比 0.924×0.626")
        runSuCommand("input tap $tapX $tapY")
        delay(900)
    }

    private fun isMutualFollowOnlyReplyOcrLabel(normalized: String): Boolean {
        val norm = normalized.trim().lowercase(Locale.US)
            .replace('0', 'o')
            .replace('1', 'l')
            .replace('|', 'l')
            .replace(Regex("\\s+"), " ")
        if (norm.isBlank()) return false
        val tokens = norm.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return tokens.any { it == "reply" }
    }

    private fun isMutualFollowOnlyReplyingOcrLabel(normalized: String): Boolean {
        val norm = normalized.trim().lowercase(Locale.US)
            .replace('0', 'o')
            .replace('1', 'l')
            .replace('|', 'l')
            .replace(Regex("\\s+"), " ")
        if (norm.isBlank()) return false
        val tokens = norm.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return tokens.any { it == "replying" }
    }

    /**
     * 点击 Reply 旁头像区；同轮 OCR 优先认主页格局，其次 Replying。
     * @return [MutualFollowOnlyReplyAvatarTapResult.abandonCoordinate] true 表示 Replying 已返回评论区，放弃本坐标处理下一条；
     *   [profileDecision] 非 null 时调用方无需再截屏做 Follow 布局 OCR。
     */
    private suspend fun mutualFollowOnlyTapReplyAvatarEntry(
        service: Context,
        tap: Point,
        heightPx: Int,
        itemTag: String,
    ): MutualFollowOnlyReplyAvatarTapResult {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val h = heightPx.coerceAtLeast(1)
        val y = tap.y.coerceIn(1, h - 1)
        devOp(alias, "互点仅关注：$itemTag 点击 (${tap.x},$y)")
        runSuCommand("input tap ${tap.x} $y")
        delay(Random.nextLong(1100, 1701))
        return when (
            val c = mutualFollowOnlyClassifyPostTapScreenWithRetries(service, itemTag)
        ) {
            is MutualFollowOnlyPostTapClassify.ProfileLayout -> {
                devOp(alias, "互点仅关注：$itemTag 点击后已认主页格局")
                MutualFollowOnlyReplyAvatarTapResult(false, c.decision)
            }
            is MutualFollowOnlyPostTapClassify.Replying -> {
                devOp(alias, "互点仅关注：$itemTag 仍为 Replying，返回评论区并跳过本坐标")
                navBack(alias, "互点仅关注 $itemTag Replying 返回评论区")
                delay(Random.nextLong(500, 801))
                MutualFollowOnlyReplyAvatarTapResult(abandonCoordinate = true)
            }
            is MutualFollowOnlyPostTapClassify.Unclear -> {
                devOp(
                    alias,
                    "互点仅关注：$itemTag 点击后未见 Replying/格局，进入主页 Follow 兜底 OCR",
                )
                MutualFollowOnlyReplyAvatarTapResult(false, null)
            }
        }
    }

    /** 评论区整屏 OCR：返回各 Reply 标签中心 Y（全屏坐标，自上而下排序）。 */
    private suspend fun findMutualFollowOnlyReplyCenterYs(service: Context): List<Int> {
        val alias = PrefsStore.getAlias() ?: "unknown"
        for (popupRetry in 0..1) {
            val screenshotFile = File(service.cacheDir, "mutual_follow_only_reply_ocr.png")
            if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return emptyList()
            val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
                screenshotFile.delete()
                return emptyList()
            }
            val fullH = bitmap.height.coerceAtLeast(1)
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val result = suspendCancellableCoroutine { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                observeAccountStatusOnOcrText(service, result, "mutual_follow_only_reply_ocr")
                if (
                    popupRetry == 0 &&
                    handleOcrVisionInlineInterruptions(
                        service,
                        result,
                        "mutual_follow_only_reply_ocr",
                        "互点 Reply OCR",
                    ) != null
                ) {
                    delay(450L)
                    continue
                }
                val rawYs = result.textBlocks.asSequence()
                .flatMap { it.lines.asSequence() }
                .flatMap { line ->
                    val lineNorm = line.text.trim().lowercase(Locale.US)
                    val lineHits = if (isMutualFollowOnlyReplyOcrLabel(lineNorm)) {
                        line.boundingBox?.let { listOf(it.centerY()) } ?: emptyList()
                    } else {
                        emptyList()
                    }
                    val elementHits = line.elements.mapNotNull { el ->
                        val norm = el.text.trim().lowercase(Locale.US)
                        if (!isMutualFollowOnlyReplyOcrLabel(norm)) return@mapNotNull null
                        el.boundingBox?.centerY()
                    }
                    (lineHits + elementHits).asSequence()
                }
                .map { it.coerceIn(0, fullH - 1) }
                .sorted()
                .toList()
            val rowTol = (fullH * 0.028f).toInt().coerceIn(18, 72)
            val deduped = mutableListOf<Int>()
            for (y in rawYs) {
                if (deduped.isEmpty() || kotlin.math.abs(y - deduped.last()) > rowTol) {
                    deduped.add(y)
                }
            }
                devOp(alias, "互点仅关注：Reply OCR 命中 ${deduped.size} 处 Y=${deduped.joinToString()}")
                return deduped
            } catch (ex: Exception) {
                rethrowTaskLogoutIfNeeded(ex)
                devOp(alias, "互点仅关注：Reply OCR 异常 ${ex.message}")
                return emptyList()
            } finally {
                recognizer.close()
                bitmap.recycle()
                screenshotFile.delete()
            }
        }
        return emptyList()
    }

    /**
     * Reply OCR：最多 6 次、间隔 1s；仍无则关评论区并关开 TikTok 回到 teamwork Videos。
     * @return null 表示已执行恢复，调用方应结束本段评论区；非 null 为命中列表。
     */
    private suspend fun findMutualFollowOnlyReplyCenterYsWithRetries(
        service: Context,
        config: AppConfig,
        logTag: String,
    ): List<Int>? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        for (attempt in 1..MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS) {
            if (hardStopOrNull(alias, "互点仅关注 Reply OCR")) return null
            val ys = findMutualFollowOnlyReplyCenterYs(service)
            if (ys.isNotEmpty()) {
                if (attempt > 1) {
                    devOp(alias, "互点仅关注：$logTag Reply OCR 第 $attempt 次命中 ${ys.size} 处")
                }
                return ys
            }
            devOp(
                alias,
                "互点仅关注：$logTag Reply OCR 第 $attempt/$MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS 次未命中",
            )
            if (attempt < MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS) {
                delay(MUTUAL_FOLLOW_ONLY_OCR_RETRY_DELAY_MS)
            }
        }
        devOp(
            alias,
            "互点仅关注：$logTag Reply OCR ${MUTUAL_FOLLOW_ONLY_OCR_MAX_ATTEMPTS} 次均未命中，关闭评论区并关开 TikTok 重新进入 teamwork Videos",
        )
        runSuCommand("input keyevent 4")
        delay(Random.nextLong(500, 801))
        didRestartTikTokInCurrentRun = true
        reopenMutualTeamworkVideosTabWithRetries(service, config, "互点仅关注 Reply OCR·$logTag")
        return null
    }

    private fun mutualFollowOnlyTapPointForReply(
        widthPx: Int,
        heightPx: Int,
        replyCenterY: Int,
    ): Point {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val x = (w * 0.084f).toInt().coerceIn(1, w - 1)
        val y = (replyCenterY - h * 0.053f).toInt().coerceIn(1, h - 1)
        return Point(x, y)
    }

    /**
     * @return true 表示本条应计入 skip（Following / Message / 无 Follow）
     */
    private suspend fun applyMutualFollowOnlyProfileFollowDecision(
        service: Context,
        config: AppConfig,
        itemTag: String,
        decision: MutualFollowOnlyProfileFollowDecision,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        return when (decision) {
            is MutualFollowOnlyProfileFollowDecision.TapFollow -> {
                val tap = decision.point
                devOp(
                    alias,
                    "互点仅关注：$itemTag ${decision.layoutLabel} Follow (${tap.x},${tap.y})",
                )
                runSuCommand("input tap ${tap.x} ${tap.y}")
                delay(Random.nextLong(1200, 1801))
                recordMutualFollowTap(alias, config, "评论区仅关注")
                false
            }
            is MutualFollowOnlyProfileFollowDecision.Skip -> {
                devOp(alias, "互点仅关注：$itemTag ${decision.reason}，跳过 Follow")
                true
            }
            is MutualFollowOnlyProfileFollowDecision.NoLayer -> {
                devOp(alias, "互点仅关注：$itemTag 未见 Follow/Message 主页层，跳过")
                true
            }
        }
    }

    /**
     * 分栏式 / 横条式主页上尝试 Follow；[preResolved] 来自点击后同屏 OCR 时可免二次截屏。
     */
    private suspend fun mutualFollowOnlyAuthorBarFollowOnProfile(
        service: Context,
        config: AppConfig,
        itemTag: String,
        preResolved: MutualFollowOnlyProfileFollowDecision? = null,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (mutualFollowLimitReached) return true
        if (hardStopOrNull(alias, "互点仅关注 $itemTag 主页 Follow")) return true
        if (preResolved != null) {
            return applyMutualFollowOnlyProfileFollowDecision(service, config, itemTag, preResolved)
        }
        delay(Random.nextLong(900, 1401))
        val decision = resolveMutualFollowOnlyProfileFollowWithRetries(service, itemTag)
        return applyMutualFollowOnlyProfileFollowDecision(service, config, itemTag, decision)
    }

    private suspend fun swipeUpMutualFollowOnlyCommentList(
        service: Context,
        lowestReplyCenterY: Int,
    ) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val m = service.resources.displayMetrics
        val w = m.widthPixels.coerceAtLeast(1)
        val h = m.heightPixels.coerceAtLeast(1)
        val x = (w * 0.5f).toInt().coerceIn(1, w - 1)
        val marginBelow = (h * 0.04f).toInt().coerceAtLeast(24)
        val swipeSpan =
            (h * MUTUAL_FOLLOW_ONLY_COMMENT_SWIPE_HEIGHT_RATIO).toInt().coerceAtLeast(80)
        var startY = (lowestReplyCenterY + marginBelow).coerceIn((h * 0.35f).toInt(), h - 1)
        var endY = (startY - swipeSpan).coerceIn(1, h - 1)
        if (startY <= endY) {
            startY = (h * 0.78f).toInt().coerceIn(1, h - 1)
            endY = (h * 0.53f).toInt().coerceIn(1, startY - 1)
        }
        val swipeMs = Random.nextInt(380, 561)
        devOp(
            alias,
            "互点仅关注：评论区上滑换批 swipe $x,$startY → $x,$endY ${swipeMs}ms（最低 Reply Y=$lowestReplyCenterY）",
        )
        runSuCommand("input swipe $x $startY $x $endY $swipeMs")
        delay(Random.nextLong(900, 1401))
    }

    /**
     * 互点-仅关注：评论区 OCR Reply → 点头像区进主页 → 作者条 Follow → 返回；
     * 批末上滑换批，直至 skip 过半或达 Follow 上限。
     * @return true 表示评论区已在本函数内关闭（调用方勿再 back）
     */
    private suspend fun performMutualFollowOnlyCommentAreaSteps(
        service: Context,
        config: AppConfig,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val m = service.resources.displayMetrics
        val widthPx = m.widthPixels
        val heightPx = m.heightPixels
        var commentPanelClosed = false
        var batchIndex = 0
        val maxBatches = 24

        batchLoop@ while (batchIndex < maxBatches && !commentPanelClosed) {
            coroutineContext.ensureActive()
            if (hardStopOrNull(alias, "互点仅关注评论区批处理")) break@batchLoop
            if (mutualFollowLimitReached) {
                devOp(alias, "互点仅关注：Follow 已达上限，结束评论区批处理")
                break@batchLoop
            }

            batchIndex++
            val batchTag = "第 $batchIndex 批"
            val replyCenterYs =
                findMutualFollowOnlyReplyCenterYsWithRetries(service, config, batchTag)
            if (replyCenterYs == null) {
                devOp(alias, "互点仅关注：$batchTag Reply OCR 恢复后结束评论区（需从 Videos 重新进作品）")
                commentPanelClosed = true
                break@batchLoop
            }
            val replyCount = replyCenterYs.size
            devOp(alias, "互点仅关注：$batchTag Reply=$replyCount")

            if (replyCount == 0) {
                devOp(alias, "互点仅关注：$batchTag 仍无 Reply，结束评论区")
                break@batchLoop
            }

            var skipCount = 0
            val tapPoints = replyCenterYs.map { mutualFollowOnlyTapPointForReply(widthPx, heightPx, it) }
            for ((idx, tap) in tapPoints.withIndex()) {
                coroutineContext.ensureActive()
                if (hardStopOrNull(alias, "互点仅关注评论区单条")) break@batchLoop
                if (mutualFollowLimitReached) break
                val itemTag = "批${batchIndex}条${idx + 1}/${tapPoints.size}"
                val tapResult = mutualFollowOnlyTapReplyAvatarEntry(
                    service,
                    tap,
                    heightPx,
                    itemTag,
                )
                if (tapResult.abandonCoordinate) {
                    skipCount++
                    devOp(alias, "互点仅关注：$itemTag Replying 已返回，跳过本坐标处理下一条")
                    continue
                }
                val skipped = mutualFollowOnlyAuthorBarFollowOnProfile(
                    service,
                    config,
                    itemTag,
                    tapResult.profileDecision,
                )
                if (skipped) skipCount++
                navBack(alias, "互点仅关注 $itemTag 返回评论区")
                delay(Random.nextLong(700, 1101))
            }

            if (mutualFollowLimitReached) {
                devOp(alias, "互点仅关注：Follow 上限后关闭评论区")
                navBack(alias, "互点仅关注 Follow 达限关闭评论区")
                delay(Random.nextLong(500, 801))
                commentPanelClosed = true
                break@batchLoop
            }

            val stopBySkipRatio = skipCount * 2 >= replyCount
            if (stopBySkipRatio) {
                devOp(
                    alias,
                    "互点仅关注：本批 skip=$skipCount / Reply=$replyCount（≥一半），不再上滑，关闭评论区",
                )
                navBack(alias, "互点仅关注 skip 过半关闭评论区")
                delay(Random.nextLong(500, 801))
                commentPanelClosed = true
                break@batchLoop
            }

            val lowestY = replyCenterYs.maxOrNull() ?: break@batchLoop
            swipeUpMutualFollowOnlyCommentList(service, lowestY)
        }

        if (!commentPanelClosed) {
            navBack(alias, "互点仅关注 评论区批处理结束关闭评论区")
            delay(Random.nextLong(500, 801))
            commentPanelClosed = true
        }
        return commentPanelClosed
    }

    private suspend fun closeMutualCommentPanelAfterFollowOnly(service: Context) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        devOp(alias, "互点仅关注：关闭评论区返回作品播放页")
        runSuCommand("input keyevent 4")
        delay(Random.nextLong(500, 801))
    }

    /**
     * 互点：固定坐标打开评论输入并发评；文案仅 [pickMutualCommentPhrase]（仅 mutual_comment_texts）。
     */
    private suspend fun performMutualCommentTextDirectFixedCoordinate(
        service: Context,
        config: AppConfig,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val commentX = (metrics.widthPixels * 0.5f).toInt().coerceIn(1, metrics.widthPixels - 1)
        val commentY = (metrics.heightPixels * 0.98f).toInt().coerceIn(1, metrics.heightPixels - 1)
        val t0 = System.currentTimeMillis()
        devOp(alias, "点击评论输入区域（直客模式同款）")
        runSuCommand("input tap $commentX $commentY")
        delay(900)

        // 剪贴板在 Android 14 后台常不可读：先把剪贴板粘贴进评论框，再用 uiautomator dump 读 EditText.text。
        // 已达粉丝列表 Follow 上限后的「仅互点评论」阶段未再复制主页用户名，禁止粘贴与 /api/report_mutual_copied_username 回传。
        if (!mutualFollowLimitReached) {
            val fromField = tryPasteClipboardIntoCommentThenReadViaUiDump(alias)
            if (!fromField.isNullOrBlank()) {
                val san = sanitizeMutualCopiedUsernameFromClipboard(fromField)
                if (san != null) {
                    val deviceId = PrefsStore.getDeviceId()?.trim()?.takeIf { it.isNotEmpty() }
                    if (deviceId != null) {
                        try {
                            val resp = withContext(Dispatchers.IO) {
                                ApiService.reportMutualCopiedUsername(deviceId, alias, san)
                            }
                            devOp(alias, "互点：评论框路径上报用户名 code=${resp.code} msg=${resp.msg} usage=${resp.usage_count}")
                        } catch (ex: Exception) {
                            devOp(alias, "互点：评论框路径上报异常 ${ex.message}")
                        }
                    }
                }
                clearCommentInputViaRoot(alias)
                delay(Random.nextLong(320, 520))
            }
        } else {
            devOp(alias, "互点：已达 Follow 会话上限，跳过评论框粘贴与用户名回传")
        }

        val text = pickMutualCommentPhrase(config, alias)
        if (text.isNullOrBlank()) {
            devOp(alias, "互点：无可用互点评论文案，关闭输入框并跳过发送")
            runSuCommand("input keyevent 4")
            return false
        }
        devOp(alias, "root 一次性输入评论文案（互点：模板 mutual_comment_texts）：$text")
        val typed = tryInputCommentText(alias, text)
        if (!typed) {
            devOp(alias, "评论文本 root 输入失败，已跳过发送")
            return false
        }
        delay(Random.nextLong(2000, 2501))

        val sendX = (metrics.widthPixels * 0.91f).toInt()
        val sendY = (metrics.heightPixels * 0.57f).toInt()
        devOp(alias, "点击发送评论")
        val sendTapped = runSuCommand("input tap $sendX $sendY")
        if (!sendTapped) {
            runSuCommand("input keyevent 66")
        }
        devOp(alias, "评论发送流程结束", System.currentTimeMillis() - t0)
        return true
    }

    private suspend fun performCommentTextGrowthFixedCoordinate(
        service: Context,
        config: AppConfig,
    ) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val commentX = (metrics.widthPixels * 0.5f).toInt().coerceIn(1, metrics.widthPixels - 1)
        val commentY = (metrics.heightPixels * 0.98f).toInt().coerceIn(1, metrics.heightPixels - 1)
        val t0 = System.currentTimeMillis()
        devOp(alias, "点击评论输入区域（直客模式同款）")
        runSuCommand("input tap $commentX $commentY")
        delay(900)

        val text = config.comment_texts.map { it.trim() }.filter { it.isNotBlank() }.randomOrNull() ?: "Nice"
        devOp(alias, "root 一次性输入评论文案：$text")
        val typed = tryInputCommentText(alias, text)
        if (!typed) {
            devOp(alias, "评论文本 root 输入失败，已跳过发送")
            return
        }
        delay(Random.nextLong(2000, 2501))

        val sendX = (metrics.widthPixels * 0.91f).toInt()
        val sendY = (metrics.heightPixels * 0.57f).toInt()
        devOp(alias, "点击发送评论")
        val sendTapped = runSuCommand("input tap $sendX $sendY")
        if (!sendTapped) {
            runSuCommand("input keyevent 66")
        }
        devOp(alias, "评论发送流程结束", System.currentTimeMillis() - t0)
    }

    private suspend fun performCommentText(service: Context, config: AppConfig) {
        val mode = config.run_mode.trim().lowercase()
        if (mode == ServerRunMode.MUTUAL) {
            performMutualCommentTextDirectFixedCoordinate(service, config)
            return
        }
        if (mode == ServerRunMode.GROWTH) {
            performCommentTextGrowthFixedCoordinate(service, config)
            return
        }

        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics

        val commentPoint = findAddCommentPointByOcr(service)
        if (commentPoint == null) {
            devOp(alias, "评论框 OCR 未命中，跳过评论")
            return
        }

        val tTap = System.currentTimeMillis()
        devOp(alias, "点击评论输入框")
        runSuCommand("input tap ${commentPoint.x} ${commentPoint.y}")
        delay(900)

        val text = config.comment_texts.randomOrNull() ?: "Nice"
        devOp(alias, "输入评论文案：$text")
        val typed = tryInputCommentText(alias, text)
        if (!typed) {
            devOp(alias, "评论文本 root 输入失败，已跳过发送")
            return
        }
        delay(Random.nextLong(2000, 2501))

        val sendX = (metrics.widthPixels * 0.91f).toInt()
        val sendY = (metrics.heightPixels * 0.57f).toInt()
        devOp(alias, "点击发送评论")
        val sendTapped = runSuCommand("input tap $sendX $sendY")
        if (!sendTapped) {
            runSuCommand("input keyevent 66")
        }
        devOp(alias, "评论发送流程结束", System.currentTimeMillis() - tTap)
    }

    private suspend fun performCommentEmoji(service: Context) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val commentX = (metrics.widthPixels * 0.9f).toInt()
        val commentY = (metrics.heightPixels * 0.74f).toInt()
        
        runSuCommand("input swipe $commentX $commentY $commentX $commentY 900")
        delay(Random.nextLong(800, 1200))
        val emojiRowY = (metrics.heightPixels * 0.75f).toInt()
        val emojiXs = listOf(0.22f, 0.36f, 0.5f, 0.64f, 0.78f)
        val pick = emojiXs.random()
        val emojiX = (metrics.widthPixels * pick).toInt()
        
        runSuCommand("input tap $emojiX $emojiRowY")
        delay(Random.nextLong(700, 1000))
        navBack(alias, "关闭评论面板")
    }

    /**
     * OCR 易混字符归一化：1/l/I/i/| 视为同字，0/O/o 视为同字，减少误识别导致匹配失败。
     */
    private fun normalizeForOcrMatch(s: String): String {
        return s.trim().lowercase()
            .replace(Regex("[1li|]"), "1")
            .replace(Regex("[0o]"), "0")
    }

    private fun cleanInputText(text: String): String {
        return text
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace('"', ' ')
            .replace('\'', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 统一评论输入：按字符分段输入，降低一次性 `input text` 丢字符的概率。
     * 空格：`adb/input text` 协议里需写成 `%s` 整体传给 `input text`，不能先 replace 再逐字迭代（否则会变成字面量 `%` + `s`）。
     */
    private fun tryInputCommentText(alias: String, rawText: String): Boolean {
        val text = cleanInputText(rawText)
        if (text.isBlank()) return false
        for (ch in text) {
            val segment = if (ch == ' ') "%s" else ch.toString()
            val ok = runSuCommand("input text ${shellEscape(segment)}")
            if (!ok) return false
        }
        return true
    }


    /** 只保留字母和数字（用于相似度计算）。 */
    private fun alphanumericOnly(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

    /** 双方字母数字长度差不超过 maxDiff 才允许按「相似度+上半部分」匹配。 */
    private fun alphanumericLengthDiffWithin(a: String, b: String, maxDiff: Int): Boolean =
        kotlin.math.abs(alphanumericOnly(a).length - alphanumericOnly(b).length) <= maxDiff

    /**
     * 计算目标与识别文本的相似度（仅字母数字、滑动窗口取最佳对齐），返回值 0~1。
     * 用于「识别内容与目标 50% 一样即视为匹配」。
     */
    private fun alphanumericSimilarity(target: String, recognized: String): Double {
        val t = alphanumericOnly(target)
        val r = alphanumericOnly(recognized)
        if (t.isEmpty()) return 1.0
        if (r.isEmpty()) return 0.0
        var best = 0.0
        val len = t.length
        if (r.length >= len) {
            for (start in 0..r.length - len) {
                var match = 0
                for (i in 0 until len) if (r[start + i] == t[i]) match++
                best = maxOf(best, match.toDouble() / len)
            }
        } else {
            for (start in 0..t.length - r.length) {
                var match = 0
                for (i in r.indices) if (t[start + i] == r[i]) match++
                best = maxOf(best, match.toDouble() / len)
            }
        }
        return best
    }

    /** 点击位置偏右：落在用户名文字区域，避免点到头像左侧空白或遮挡。 */
    private fun pointLeftBias(box: Rect, cropTop: Int): Point {
        val x = box.left + (box.width() * 0.6f).toInt().coerceAtLeast(16) + 24
        val y = cropTop + box.centerY()
        return Point(x, y)
    }

    private data class UserListOcrAttemptResult(
        val point: Point?,
        val hadRecognizedText: Boolean,
        val preview: String,
    )

    /**
     * 单次截屏 + Users 列表区域 OCR，尝试匹配 [username]。
     */
    private suspend fun runUserListOcrSingleAttempt(
        service: Context,
        username: String,
    ): UserListOcrAttemptResult {
        val screenshotFile = File(service.cacheDir, "user_list_ocr.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) {
            
            return UserListOcrAttemptResult(null, false, "")
        }
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            
            return UserListOcrAttemptResult(null, false, "")
        }
        observeAccountStatusOnScreenshot(service, bitmap, "growth_user_list_ocr")
        val height = bitmap.height
        val width = bitmap.width
        val cropTop = (height * 0.22f).toInt().coerceAtLeast(0)
        val cropHeight = (height * 0.68f).toInt().coerceAtLeast(1)
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, cropTop, width, cropHeight)
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val allText = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
            val preview = allText.take(30).joinToString(" | ")
            val hadText = allText.isNotEmpty()
            
            val normalized = username.trim().lowercase().removePrefix("@")
            if (normalized.isEmpty()) return UserListOcrAttemptResult(null, hadText, preview)
            val targetOcrNorm = normalizeForOcrMatch(normalized)
            val upperHalfYThreshold = cropHeight / 2
            var out: Point? = null
            blockLoop@ for (block in result.textBlocks) {
                for (line in block.lines) {
                    val lineText = line.text.trim().lowercase().replace("@", "")
                    val lineBox = line.boundingBox ?: continue
                    val inUpperHalf = lineBox.centerY() < upperHalfYThreshold
                    val lineOcrNorm = normalizeForOcrMatch(lineText)
                    val exactOrFuzzyLine = lineText.contains(normalized) || lineOcrNorm.contains(targetOcrNorm)
                    val similarEnough = alphanumericSimilarity(normalized, lineText) >= 0.5
                    val lengthOk = alphanumericLengthDiffWithin(normalized, lineText, 3)
                    if ((exactOrFuzzyLine || (inUpperHalf && similarEnough && lengthOk)) && lineBox != null) {
                        out = pointLeftBias(lineBox, cropTop)
                        break@blockLoop
                    }
                    for (element in line.elements) {
                        val el = element.text.trim().lowercase().removePrefix("@")
                        val elBox = element.boundingBox ?: continue
                        val elInUpperHalf = elBox.centerY() < upperHalfYThreshold
                        val elOcrNorm = normalizeForOcrMatch(el)
                        val exactOrContains = el == normalized || el.contains(normalized)
                        val fuzzyMatch = elOcrNorm == targetOcrNorm || elOcrNorm.contains(targetOcrNorm)
                        val elSimilarEnough = alphanumericSimilarity(normalized, el) >= 0.5
                        val elLengthOk = alphanumericLengthDiffWithin(normalized, el, 3)
                        if ((exactOrContains || fuzzyMatch || (elInUpperHalf && elSimilarEnough && elLengthOk)) && elBox != null) {
                            out = pointLeftBias(elBox, cropTop)
                            break@blockLoop
                        }
                    }
                }
            }
            UserListOcrAttemptResult(out, hadText, preview)
        } catch (ex: Exception) {
            rethrowTaskLogoutIfNeeded(ex)
            UserListOcrAttemptResult(null, false, "")
        } finally {
            recognizer.close()
            bitmap.recycle()
            croppedBitmap.recycle()
            screenshotFile.delete()
        }
    }

    /**
     * Users 标签 OCR 恢复：回首页成功后，与主流程一致地重新打开搜索并切到 Users 标签。
     */
    private suspend fun reopenSearchAndEnterUsersTab(
        service: Context,
        username: String,
        isLiveModeForUsersCheck: Boolean,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (username.trim() == "__mutual_switch__" || username.trim() == "__publish_post__") {
            devOp(alias, "检测到互点切换或发作品占位用户名，禁止走 Users 标签恢复")
            return false
        }
        delay(500)
        if (!clickMagnifier(service)) {
            devOp(alias, "Users 标签恢复：无法点击放大镜")
            return false
        }
        val safeUsername = shellEscape(username)
        devOp(alias, "Users 标签恢复：输入搜索用户名：$username")
        if (!ensureTikTokInForeground(service, alias, "Users 标签恢复输入")) {
            devOp(alias, "Users 标签恢复：TikTok 非前台，无法输入搜索词")
            return false
        }
        val inputResult = runSuCommand("input text $safeUsername")
        if (!inputResult) {
            devOp(alias, "Users 标签恢复：输入搜索词失败")
            return false
        }
        delay(Random.nextLong(800, 1500))
        if (!clickSearchButtonAndWait(service)) {
            devOp(alias, "Users 标签恢复：无法点击 Search")
            return false
        }
        if (!ensureUsersTabClicked(service)) {
            devOp(alias, "Users 标签恢复：重新搜索后仍未找到 Users 标签")
            return false
        }
        if (isLiveModeForUsersCheck) {
            ensureUsersTabReadyForLiveMode(service)
        } else {
            delay(2800)
        }
        return true
    }

    /**
     * 在 Users 列表区域用 OCR 查找包含目标用户名的行，返回该行点击点（屏幕坐标，偏左）。
     * 每一段（首次进入列表，或重启 TikTok 并重新打开搜索后）最多 **3 次** OCR。
     * 连续 3 次未匹配：先 OCR 重找 Users 标签并点击，再额外做 2 次列表匹配；仍失败则关闭并重启 TikTok、重新进入搜索与 Users 列表，再开启**新的一段**（仍最多 3 次 OCR）；整段流程最多执行 **1 次**此类重启，避免死循环。
     */
    private suspend fun findUserInListByOcr(
        service: Context,
        username: String,
        isLiveModeForUsersCheck: Boolean,
    ): Point? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val normalized = username.trim().lowercase().removePrefix("@")
        if (normalized.isEmpty()) return null
        if (normalized == "__mutual_switch__" || normalized == "__publish_post__") {
            devOp(alias, "检测到互点切换或发作品专用占位用户名，跳过 Users 列表 OCR 恢复与普通匹配")
            return null
        }

        val firstWaitMs = 3500L
        val betweenRetryMs = 3000L
        val maxAttemptsPerSegment = 3

        var lastHadText = false
        var lastPreview = ""
        var consecutiveFailures = 0
        var needLongSettleBeforeOcr = true
        var ocrInSegment = 0
        var didRestartAfterTripleMiss = false
        var didUsersTabOcrRetryBeforeRestart = false

        while (true) {
            if (consecutiveFailures >= 3) {
                if (didRestartAfterTripleMiss) {
                    devOp(alias, "Users 列表 OCR：已连续 3 次未匹配且已执行过一次重启，结束匹配")
                    break
                }
                if (!didUsersTabOcrRetryBeforeRestart) {
                    didUsersTabOcrRetryBeforeRestart = true
                    devOp(alias, "Users 列表 OCR 连续 3 次未匹配，OCR 重新定位 Users 标签并点击后再试 2 次列表匹配")
                    if (ensureUsersTabClicked(service)) {
                        delay(betweenRetryMs)
                        repeat(2) {
                            val retryResult = runUserListOcrSingleAttempt(service, username)
                            lastHadText = retryResult.hadRecognizedText
                            lastPreview = retryResult.preview
                            if (retryResult.point != null) {
                                return retryResult.point
                            }
                        }
                    } else {
                        devOp(alias, "Users 列表 OCR：重找 Users 标签失败，跳过补试，改为强关重启")
                    }
                }
                devOp(alias, "Users 列表 OCR 连续 3 次未匹配，强关重启 TikTok 后重新进入搜索与 Users 列表")
                restartTikTokForRecovery()
                if (!reopenSearchAndEnterUsersTab(service, username, isLiveModeForUsersCheck)) {
                    return null
                }
                didRestartAfterTripleMiss = true
                consecutiveFailures = 0
                ocrInSegment = 0
                didUsersTabOcrRetryBeforeRestart = false
                needLongSettleBeforeOcr = true
                continue
            }

            if (ocrInSegment >= maxAttemptsPerSegment) {
                break
            }

            if (needLongSettleBeforeOcr) {
                delay(firstWaitMs)
                needLongSettleBeforeOcr = false
            } else {
                delay(betweenRetryMs)
            }
            val result = runUserListOcrSingleAttempt(service, username)
            lastHadText = result.hadRecognizedText
            lastPreview = result.preview
            ocrInSegment++
            if (result.point != null) {
                return result.point
            }
            consecutiveFailures++
        }
        devOp(
            alias,
            if (!lastHadText) "Users 列表多次 OCR 仍无文字，匹配失败" else "Users 列表有文字但未匹配到用户名",
        )
        return null
    }

    private fun clickUsersTab(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val fallbackX = (metrics.widthPixels * 0.34f).toInt()
        val fallbackY = (metrics.heightPixels * 0.23f).toInt()
        
        return runSuCommand("input tap $fallbackX $fallbackY")
    }

    private enum class AutoLoginStepOutcome {
        Ok,
        ReachedInterest,
        /** 验证码后已见底栏 Home/Profile，跳过密码与兴趣页。 */
        ReachedHome,
        ChainRestart,
        Failed,
    }

    /**
     * ViaLogIn / ViaAddAnotherAccount：与底栏 Log in 同级，先点其一，再 Username×2 → 填邮箱 …
     * ViaSignUp：底栏仅见 Sign up（已在 Log in 之后），不点底栏，直接 Username×2 → 填邮箱 …
     * AlreadyLoggedIn：入口页未见 Log in/Sign up 但识别 Edit，账号已登录。
     */
    private enum class AutoLoginEntryPath {
        None,
        ViaLogIn,
        ViaAddAnotherAccount,
        ViaSignUp,
        AlreadyLoggedIn,
    }

    private suspend fun autoLoginTapVerificationCodeField(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        vision: Text?,
    ) {
        val hit = vision?.let { v ->
            findLabelHitContainingIgnoreCase(v, "enter code")
                ?: findLabelHitContainingIgnoreCase(v, "verification code")
                ?: findLabelHitContainingIgnoreCase(v, "code")
        }
        val tap = if (hit != null) {
            autoLoginTapWithJitter(
                Point(hit.point.x, (hit.point.y - h * 0.06f).toInt().coerceAtLeast(1)),
                w,
                h,
            )
        } else {
            Point((w * 0.5f).toInt().coerceIn(1, w - 1), (h * 0.42f).toInt().coerceIn(1, h - 1))
        }
        devOp(alias, "自动登录：点验证码输入区 (${tap.x},${tap.y})")
        runSuCommand("input tap ${tap.x} ${tap.y}")
        autoLoginDelay(alias, 350L, "点验证码输入区后")
    }

    private fun autoLoginTapWithJitter(p: Point, w: Int, h: Int): Point =
        coerceTapToScreen(
            Point(
                p.x + Random.nextInt(-14, 15),
                p.y + Random.nextInt(-12, 13),
            ),
            w,
            h,
        )

    private suspend fun autoLoginDelay(alias: String, ms: Long, label: String): Boolean {
        if (hardStopOrNull(alias, "自动登录-$label")) return false
        delay(ms.coerceAtLeast(0L))
        return true
    }

    private suspend fun autoLoginRestartTikTok(ctx: Context, alias: String): Boolean {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val forceStopWaitMs = lastConfigSnapshot.tiktok_force_stop_wait_ms.coerceIn(100L, 8000L)
        val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
        devOp(alias, "自动登录：关开 TikTok 后整链重来")
        runSuCommand("am force-stop $pkg")
        if (!autoLoginDelay(alias, forceStopWaitMs, "强停后")) return false
        return launchTikTokOnceWithCheck(
            ctx = ctx,
            alias = alias,
            tag = "自动登录重来",
            launchWaitMs = launchWaitMs,
            forceStopBeforeLaunch = false,
        )
    }

    /** 底栏最左侧 Home（登录状态保障 / 回 Feed 用）。 */
    private fun findBottomBandHomeTabHit(
        result: Text,
        screenH: Int,
        bottomFractionStart: Float = 0.62f,
    ): StrictLabelHit? {
        val bandTop = (screenH * bottomFractionStart).toInt()
        var best: StrictLabelHit? = null
        var bestX = Int.MAX_VALUE
        fun consider(text: String, box: Rect) {
            val t = text.trim().lowercase(Locale.US)
            if (box.centerY() < bandTop) return
            if (t != "home" && !t.contains("home")) return
            if (box.centerX() < bestX) {
                bestX = box.centerX()
                best = StrictLabelHit(Point(box.centerX(), box.centerY()), box)
            }
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { consider(line.text, it) }
                for (el in line.elements) {
                    el.boundingBox?.let { consider(el.text, it) }
                }
            }
        }
        return best
    }

    private fun findBottomBandLabelContaining(
        result: Text,
        screenH: Int,
        needle: String,
        bottomFractionStart: Float = 0.62f,
    ): StrictLabelHit? {
        val bandTop = (screenH * bottomFractionStart).toInt()
        val lowNeedle = needle.lowercase(Locale.US)
        var best: StrictLabelHit? = null
        var bestBottom = Int.MIN_VALUE
        fun consider(text: String, box: Rect) {
            if (!text.lowercase(Locale.US).contains(lowNeedle)) return
            if (box.centerY() < bandTop) return
            if (box.bottom > bestBottom) {
                bestBottom = box.bottom
                best = StrictLabelHit(Point(box.centerX(), box.centerY()), box)
            }
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { consider(line.text, it) }
                for (el in line.elements) {
                    el.boundingBox?.let { consider(el.text, it) }
                }
            }
        }
        return best
    }

    private fun autoLoginLogEmailAfterContinueProbe(
        alias: String,
        pass: Int,
        sawVerifyEmail: Boolean,
        sawFacebook: Boolean,
        continueStrict: StrictLabelHit?,
        continueLoose: StrictLabelHit?,
    ) {
        val flags = "verifyEmail=$sawVerifyEmail, facebook=$sawFacebook, " +
            "Continue严格=${continueStrict != null}, continue含子串=${continueLoose != null}"
        if (alias == autoLoginEmailAfterProbeAlias && flags == autoLoginEmailAfterProbeFlags) {
            autoLoginEmailAfterProbePassEnd = pass
            return
        }
        autoLoginFlushEmailAfterProbeLog(alias)
        autoLoginEmailAfterProbeAlias = alias
        autoLoginEmailAfterProbePassStart = pass
        autoLoginEmailAfterProbePassEnd = pass
        autoLoginEmailAfterProbeFlags = flags
    }

    private fun autoLoginLogVerificationCodePageProbe(
        alias: String,
        pass: Int,
        vision: Text,
        elapsedSec: Long,
    ) {
        val reason = when {
            findLabelHitContainingIgnoreCase(vision, "resend code") != null -> "OCR 命中「Resend code」"
            ocrVisionHasVerifyEmailMarker(vision) -> "OCR 命中「Verify」文案"
            findLabelHitContainingIgnoreCase(vision, "enter code") != null -> "OCR 命中「Enter code」"
            findLabelHitContainingIgnoreCase(vision, "verification code") != null ->
                "OCR 命中「Verification code」"
            else -> "未识别到明确验证码页文案"
        }
        devOp(
            alias,
            "自动登录 验证码页轮询 第${pass}次（已耗时 ${elapsedSec}s）：仍认定在验证码页，原因=$reason",
        )
    }

    private fun autoLoginLogNeedHelpLoggingProbe(
        alias: String,
        pass: Int,
        maxPasses: Int,
        vision: Text,
        hit: StrictLabelHit?,
    ) {
        val preview = buildProfileEditOcrPreview(vision, maxLines = 60)
        val hitLabel = if (hit != null) {
            "命中 tap=(${hit.point.x},${hit.point.y})"
        } else {
            "未命中"
        }
        devOp(
            alias,
            "自动登录 Need help logging in(切号) 第 $pass/$maxPasses 次 $hitLabel：读取到文案 ${preview.take(800)}",
        )
    }

    private fun autoLoginLogPasswordLayoutProbe(
        alias: String,
        pass: Int,
        maxPasses: Int,
        phase: String,
        vision: Text,
    ) {
        val pwStrict = findAllPasswordLabelHits(vision).size
        val nextHit = findStrictExactLabelHit(vision, "Next")
            ?: findStrictExactLabelHit(vision, "next")
            ?: findLabelHitContainingIgnoreCase(vision, "next")
        val pwContains = countLabelContainingIgnoreCase(vision, "password")
        val layoutAReady = findSinglePasswordAndNextHits(vision) != null
        devOp(
            alias,
            "自动登录 布局判定[$phase] 第${pass}/${maxPasses}次: " +
                "严格password=$pwStrict, 含password计数=$pwContains, next=${nextHit != null}, 布局A命中=$layoutAReady",
        )
    }

    private fun findAllPasswordLabelHits(result: Text): List<StrictLabelHit> {
        val out = ArrayList<StrictLabelHit>(4)
        fun consider(text: String, box: Rect) {
            if (!text.trim().equals("password", ignoreCase = true)) return
            out.add(StrictLabelHit(Point(box.centerX(), box.centerY()), box))
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                if (line.elements.isNotEmpty()) {
                    for (el in line.elements) {
                        el.boundingBox?.let { consider(el.text, it) }
                    }
                } else {
                    line.boundingBox?.let { consider(line.text, it) }
                }
            }
        }
        return out.sortedBy { it.rect.top }
    }

    /** 上下两个 password 标签之间的输入区域中心。 */
    private fun findMiddlePasswordFieldHit(result: Text): StrictLabelHit? {
        val labels = findAllPasswordLabelHits(result)
        if (labels.isEmpty()) return null
        if (labels.size == 1) {
            val r = labels[0].rect
            val y = (r.bottom + 48).coerceAtMost(r.bottom + 120)
            return StrictLabelHit(Point(r.centerX(), y), Rect(r.left, r.bottom, r.right, y + 40))
        }
        val top = labels.first()
        val bottom = labels.last()
        val midY = ((top.rect.bottom + bottom.rect.top) / 2).coerceAtLeast(top.rect.bottom + 8)
        val left = minOf(top.rect.left, bottom.rect.left)
        val right = maxOf(top.rect.right, bottom.rect.right)
        val cx = ((left + right) / 2).coerceAtLeast(1)
        return StrictLabelHit(Point(cx, midY), Rect(left, top.rect.bottom, right, bottom.rect.top))
    }

    /** 新版密码页格局：同屏仅 1 个 password + 1 个 next。 */
    private fun findSinglePasswordAndNextHits(result: Text): Pair<StrictLabelHit, StrictLabelHit>? {
        val pw = findAllPasswordLabelHits(result)
        if (pw.size != 1) return null
        val next =
            findStrictExactLabelHit(result, "Next")
                ?: findStrictExactLabelHit(result, "next")
                ?: findLabelHitContainingIgnoreCase(result, "next")
                ?: return null
        return pw[0] to next
    }

    /** 统计 OCR 中包含指定短语的命中数（有 element 时只计 element，避免与 line 重复）。 */
    private fun countLabelContainingIgnoreCase(result: Text, needle: String): Int {
        val lowNeedle = needle.trim().lowercase(Locale.US)
        if (lowNeedle.isEmpty()) return 0
        var count = 0
        fun hit(text: String): Boolean = text.trim().lowercase(Locale.US).contains(lowNeedle)
        for (block in result.textBlocks) {
            for (line in block.lines) {
                if (line.elements.isNotEmpty()) {
                    for (el in line.elements) {
                        if (hit(el.text)) count++
                    }
                } else if (hit(line.text)) {
                    count++
                }
            }
        }
        return count
    }

    private suspend fun autoLoginOcrOnce(
        ctx: Context,
        alias: String,
        tag: String,
    ): Text? {
        val (vision, errTag) = mlKitRecognizeFullScreen(ctx, tag)
        if (vision == null) {
            devOp(alias, "自动登录 OCR 失败：$errTag [$tag]")
        }
        if (vision != null) {
            // 顺带处理：每次 autoLogin OCR 后，尝试点击下方 agree and / Don't allow / 各类弹窗。
            val overlayDismissed = runCatching {
                autoLoginTryDismissInlineOverlaysFromVision(ctx, alias, vision)
            }.getOrDefault(false)
            if (overlayDismissed && !tag.endsWith("_overlay_redo")) {
                return autoLoginOcrOnce(ctx, alias, "${tag}_overlay_redo")
            }
        }
        return vision
    }

    /** @return true 表示已处理 Viewer history 弹窗，调用方应重试 OCR。 */
    private suspend fun autoLoginTryDismissInlineOverlaysFromVision(
        ctx: Context,
        alias: String,
        vision: Text,
    ): Boolean {
        if (hardStopOrNull(alias, "自动登录-OCR顺带处理")) return false
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)

        // 1) 下方 agree and（包含即可）
        findBottomBandLabelContaining(vision, h, "agree and")?.let { hit ->
            val tap = autoLoginTapWithJitter(hit.point, w, h)
            devOp(alias, "自动登录(顺带) agree and(下方)：tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                autoLoginDelay(alias, 450L, "agree and 后")
            }
        }

        // 2) Don't allow：本链点过则跳过
        if (!autoLoginDontAllowHandledInThisChain) {
            val tapped = tapDontAllowFromVisionIfPresent(
                ctx = ctx,
                alias = alias,
                vision = vision,
                phaseLog = "自动登录(顺带) Don't allow",
                useAutoLoginJitter = true,
                markAutoLoginHandled = true,
            )
            if (tapped) autoLoginDelay(alias, 450L, "Don't allow 后")
        }

        // 3) Viewer history 弹窗：同屏见 Viewer history + Save 时点 Save
        if (
            tryDismissViewerHistoryPopupFromVision(
                ctx = ctx,
                alias = alias,
                vision = vision,
                phaseLog = "自动登录(顺带) Viewer history 弹窗",
                tapWithJitter = true,
            )
        ) {
            autoLoginDelay(alias, 450L, "Viewer history 弹窗 Save 后")
            return true
        }

        // 4) 登录后「Save login for next time?」弹窗：点偏下方 Save login
        if (
            tryDismissSaveLoginForNextTimePopupFromVision(
                ctx = ctx,
                alias = alias,
                vision = vision,
                phaseLog = "自动登录(顺带) Save login 弹窗",
                tapWithJitter = true,
            )
        ) {
            autoLoginDelay(alias, 450L, "Save login 弹窗后")
            return true
        }
        return false
    }

    private fun findAddAnotherAccountTapHit(vision: Text): StrictLabelHit? =
        findLabelHitContainingIgnoreCase(vision, "add another account")
            ?: findLabelHitContainingIgnoreCase(vision, "another account")

    private suspend fun autoLoginResolveEntryPath(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
    ): AutoLoginEntryPath {
        var loginEntryMissStreak = 0
        for (pass in 1..AUTO_LOGIN_OCR_MAX_PASSES) {
            if (hardStopOrNull(alias, "自动登录-入口")) return AutoLoginEntryPath.None
            if (pass > 1 && !autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "登录入口")) {
                return AutoLoginEntryPath.None
            }
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_entry_$pass") ?: continue

            val addAnotherHit = findAddAnotherAccountTapHit(vision)
            if (addAnotherHit != null) {
                val tap = autoLoginTapWithJitter(addAnotherHit.point, w, h)
                devOp(
                    alias,
                    "自动登录：点 Add another account（与 Log in 同级，第 $pass/$AUTO_LOGIN_OCR_MAX_PASSES 次）tap (${tap.x},${tap.y})",
                )
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    autoLoginDelay(alias, 550L, "Add another account 点击后")
                    return AutoLoginEntryPath.ViaAddAnotherAccount
                }
                continue
            }

            val logInBottom = findBottomBandLabelContaining(vision, h, "log in")
            if (logInBottom != null) {
                loginEntryMissStreak = 0
                val tap = autoLoginTapWithJitter(logInBottom.point, w, h)
                devOp(alias, "自动登录：底栏 Log in，点 Log in（第 $pass/$AUTO_LOGIN_OCR_MAX_PASSES 次）tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    autoLoginDelay(alias, 550L, "Log in 点击后")
                    return AutoLoginEntryPath.ViaLogIn
                }
                continue
            }

            val signUpBottom = findBottomBandLabelContaining(vision, h, "sign up")
            if (signUpBottom != null) {
                loginEntryMissStreak = 0
                devOp(alias, "自动登录：底栏 Sign up（已在 Log in 之后），不点底栏，进入 Username×2")
                autoLoginDelay(alias, 550L, "Sign up 识别后")
                return AutoLoginEntryPath.ViaSignUp
            }

            loginEntryMissStreak++
            if (loginEntryMissStreak > AUTO_LOGIN_ENTRY_MISS_BEFORE_EDIT_PROBE && ocrVisionHasStrictEdit(vision)) {
                val preview = buildProfileEditOcrPreview(vision).take(400)
                devOp(
                    alias,
                    "自动登录：Log in/Sign up 连续未识别 ${loginEntryMissStreak} 次，识别到 Edit，判定已登录；OCR=$preview",
                )
                return AutoLoginEntryPath.AlreadyLoggedIn
            }

            devOp(
                alias,
                "自动登录：底栏 Log in / Sign up 均未识别（第 $pass/$AUTO_LOGIN_OCR_MAX_PASSES 次，连续 $loginEntryMissStreak 次）",
            )
        }
        return AutoLoginEntryPath.None
    }

    /** 登录入口 Username 点击（Add another account 已在 [autoLoginResolveEntryPath] 与 Log in 同级单独点击）。 */
    private fun findAutoLoginUsernameTapHit(result: Text): StrictLabelHit? =
        findStrictExactLabelHit(result, "Username")
            ?: findStrictExactLabelHit(result, "username")
            ?: findLabelHitContainingIgnoreCase(result, "username")

    private suspend fun autoLoginOcrTapByFinder(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        tagPrefix: String,
        phaseLog: String,
        maxPasses: Int = AUTO_LOGIN_OCR_MAX_PASSES,
        retryDelayMs: Long = AUTO_LOGIN_OCR_RETRY_DELAY_MS,
        findHit: (Text) -> StrictLabelHit?,
        onVisionProbe: ((pass: Int, passes: Int, vision: Text, hit: StrictLabelHit?) -> Unit)? = null,
    ): AutoLoginStepOutcome {
        val passes = maxPasses.coerceIn(1, 20)
        for (pass in 1..passes) {
            if (hardStopOrNull(alias, "自动登录-$phaseLog")) return AutoLoginStepOutcome.Failed
            if (pass > 1) {
                if (!autoLoginDelay(alias, retryDelayMs, phaseLog)) return AutoLoginStepOutcome.Failed
            } else {
                if (!autoLoginDelay(alias, 450L, phaseLog)) return AutoLoginStepOutcome.Failed
            }
            val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_$pass") ?: continue
            val hit = findHit(vision)
            onVisionProbe?.invoke(pass, passes, vision, hit)
            if (hit == null) {
                devOp(alias, "自动登录 $phaseLog 第 $pass/$passes 次：未命中")
                continue
            }
            val tap = autoLoginTapWithJitter(hit.point, w, h)
            devOp(alias, "自动登录 $phaseLog：tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                if (!autoLoginDelay(alias, 550L, "$phaseLog 点击后")) return AutoLoginStepOutcome.Failed
                return AutoLoginStepOutcome.Ok
            }
        }
        return AutoLoginStepOutcome.Failed
    }

    /** 同屏 OCR：优先 Continue，其次 Next；找到即返回。 */
    private fun findAutoLoginContinueOrNextHit(result: Text): Pair<String, StrictLabelHit>? {
        findStrictExactLabelHit(result, "Continue")?.let { return "Continue" to it }
        findStrictExactLabelHit(result, "continue")?.let { return "continue" to it }
        findStrictExactLabelHit(result, "Next")?.let { return "Next" to it }
        findStrictExactLabelHit(result, "next")?.let { return "next" to it }
        findLabelHitContainingIgnoreCase(result, "next")?.let { return "next" to it }
        return null
    }

    private suspend fun autoLoginOcrTapContinueOrNext(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        tagPrefix: String,
        phaseLog: String,
        maxPasses: Int = 3,
        retryDelayMs: Long = AUTO_LOGIN_OCR_RETRY_DELAY_MS,
    ): AutoLoginStepOutcome {
        val passes = maxPasses.coerceIn(1, 20)
        for (pass in 1..passes) {
            if (hardStopOrNull(alias, "自动登录-$phaseLog")) return AutoLoginStepOutcome.Failed
            if (pass > 1) {
                if (!autoLoginDelay(alias, retryDelayMs, phaseLog)) return AutoLoginStepOutcome.Failed
            } else {
                if (!autoLoginDelay(alias, 450L, phaseLog)) return AutoLoginStepOutcome.Failed
            }
            val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_$pass") ?: continue
            val found = findAutoLoginContinueOrNextHit(vision)
            if (found == null) {
                devOp(alias, "自动登录 $phaseLog 第 $pass/$passes 次：Continue/Next 均未命中")
                continue
            }
            val (label, hit) = found
            val tap = autoLoginTapWithJitter(hit.point, w, h)
            devOp(alias, "自动登录 $phaseLog 第 $pass/$passes 次：命中 $label，tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                if (!autoLoginDelay(alias, 550L, "$phaseLog 点击后")) return AutoLoginStepOutcome.Failed
                return AutoLoginStepOutcome.Ok
            }
        }
        return AutoLoginStepOutcome.Failed
    }

    /** 邮箱/验证码验证页：含 verify 子串即认定（不要求完整 Verify email）。 */
    private fun ocrVisionHasVerifyEmailMarker(result: Text): Boolean =
        findLabelHitContainingIgnoreCase(result, "verify") != null

    /** 验证码页门禁：Verify 文案 / Resend code 命中其一即可。 */
    private fun autoLoginDetectVerificationPageMarker(vision: Text): String? = when {
        findLabelHitContainingIgnoreCase(vision, "resend code") != null -> "Resend code"
        ocrVisionHasVerifyEmailMarker(vision) -> "Verify"
        else -> null
    }

    /**
     * 确认已进入验证码页（Verify 文案或 Resend code 任一），并持续 OCR 直至定位 Resend code。
     * 拉取验证码须在 Resend code 命中之后，故本函数返回 true 时 Resend code 已就绪。
     */
    private suspend fun autoLoginOcrConfirmVerificationPageAndWaitResendCode(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
    ): Boolean {
        val passes = AUTO_LOGIN_OCR_MAX_PASSES
        var pageConfirmed = false
        var confirmedMarker: String? = null

        for (pass in 1..passes) {
            if (hardStopOrNull(alias, "自动登录-验证码页确认")) return false
            if (pass > 1 && !autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "验证码页确认")) return false
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_verify_page_$pass") ?: continue
            val marker = autoLoginDetectVerificationPageMarker(vision)
            if (marker == null) {
                devOp(alias, "自动登录：验证码页确认第 $pass/$passes 次：Verify / Resend code 均未命中")
                continue
            }
            if (!pageConfirmed) {
                pageConfirmed = true
                confirmedMarker = marker
                devOp(alias, "自动登录：已进入验证码页（命中 $marker，第 $pass 次 OCR）")
            }
            if (marker == "Resend code") {
                devOp(alias, "自动登录：已定位 Resend code，可拉取验证码（第 $pass 次 OCR）")
                return true
            }
        }
        if (!pageConfirmed) {
            devOp(alias, "自动登录：验证码页确认失败（${passes} 次均未命中 Verify / Resend code）")
            return false
        }

        devOp(
            alias,
            "自动登录：已见 $confirmedMarker，继续等待 Resend code（最多 ${passes} 次 OCR）",
        )
        for (pass in 1..passes) {
            if (hardStopOrNull(alias, "自动登录-等待 Resend code")) return false
            if (pass > 1 && !autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "等待 Resend code")) return false
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_resend_$pass") ?: continue
            if (findLabelHitContainingIgnoreCase(vision, "resend code") != null) {
                devOp(alias, "自动登录：已定位 Resend code，可拉取验证码（等待阶段第 $pass 次 OCR）")
                return true
            }
            devOp(alias, "自动登录：等待 Resend code 第 $pass/$passes 次：仍未命中")
        }
        devOp(alias, "自动登录：已进入验证码页（$confirmedMarker）但 ${passes} 次内未定位 Resend code")
        return false
    }

    /** Email 输入框页：须可见 address 或 username 相关文案。 */
    private fun ocrVisionHasEmailFieldPrompt(result: Text): Boolean =
        findLabelHitContainingIgnoreCase(result, "address") != null ||
            findLabelHitContainingIgnoreCase(result, "username") != null ||
            findStrictExactLabelHit(result, "Username") != null ||
            findStrictExactLabelHit(result, "username") != null

    /** Email 输入前 OCR 校验；未就绪返回 false（调用方整链重来）。 */
    private suspend fun autoLoginVerifyEmailFormReadyBeforeInput(
        ctx: Context,
        alias: String,
    ): Boolean {
        val maxPasses = AUTO_LOGIN_OCR_MAX_PASSES
        for (pass in 1..maxPasses) {
            if (hardStopOrNull(alias, "自动登录-Email表单校验")) return false
            if (pass > 1 && !autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "Email表单校验")) {
                return false
            }
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_email_form_$pass") ?: continue
            if (ocrVisionHasEmailFieldPrompt(vision)) {
                devOp(alias, "自动登录：Email 输入前已识别 address/username（第 $pass/$maxPasses 次 OCR）")
                return true
            }
            val preview = buildProfileEditOcrPreview(vision).take(300)
            devOp(
                alias,
                "自动登录：Email 输入前第 $pass/$maxPasses 次 OCR 未识别 address/username；OCR=$preview",
            )
        }
        return false
    }

    private fun autoLoginInputText(alias: String, raw: String): Boolean {
        val text = cleanInputText(raw)
        if (text.isBlank()) return false
        for (ch in text) {
            val segment = if (ch == ' ') "%s" else ch.toString()
            if (!runSuCommand("input text ${shellEscape(segment)}")) {
                devOp(alias, "自动登录：输入失败")
                return false
            }
        }
        return true
    }

    private suspend fun autoLoginClearFocusedInput(alias: String, delRepeats: Int = 8) {
        repeat(delRepeats.coerceIn(4, 16)) {
            runSuCommand("input keyevent 67")
            delay(40)
        }
        if (!autoLoginDelay(alias, 200L, "清空输入")) return
    }

    private suspend fun autoLoginTryDismissDontAllow(
        ctx: Context,
        alias: String,
        maxPasses: Int = AUTO_LOGIN_DONT_ALLOW_OCR_MAX_PASSES,
    ): Boolean {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val passes = maxPasses.coerceIn(1, 20)
        for (pass in 1..passes) {
            if (hardStopOrNull(alias, "自动登录-Don't allow")) return false
            if (pass > 1 && !autoLoginDelay(alias, 1000L, "Don't allow")) return false
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_dont_allow_$pass") ?: continue
            val hit = findDontAllowLabelHit(vision)
            if (hit == null) continue
            val tap = autoLoginTapWithJitter(hit.point, w, h)
            devOp(alias, "自动登录 Don't allow：tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                autoLoginDelay(alias, 450L, "Don't allow 后")
                return true
            }
        }
        devOp(alias, "自动登录：$passes 次均未命中 Don't allow，继续后续")
        return false
    }

    /**
     * 启动后预处理：每轮 1 次整屏 OCR，固定顺序尝试下方 agree and → Don't allow。
     * 各自至多 [maxPasses] 次机会（共享轮次）；找不到跳过。返回是否点中 Don't allow。
     * 若本轮刚点 agree，本轮不再用旧 OCR 帧查 Don't allow（与原先分两段 fresh OCR 一致）。
     */
    private suspend fun autoLoginPreprocessDismissAgreeAndDontAllow(
        ctx: Context,
        alias: String,
        maxPasses: Int = 3,
        retryDelayMs: Long = 1000L,
    ): Boolean {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val passes = maxPasses.coerceIn(1, 10)
        var agreeHandled = false
        var dontAllowHandled = false

        for (pass in 1..passes) {
            if (hardStopOrNull(alias, "自动登录-预处理 agree and / Don't allow")) return dontAllowHandled
            if (agreeHandled && dontAllowHandled) return dontAllowHandled
            if (pass > 1 && !autoLoginDelay(alias, retryDelayMs, "预处理 agree and / Don't allow")) {
                return dontAllowHandled
            }

            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_preprocess_agree_dont_$pass") ?: continue

            var agreeTappedThisPass = false
            if (!agreeHandled) {
                val agreeHit = findBottomBandLabelContaining(vision, h, "agree and")
                if (agreeHit != null) {
                    val tap = autoLoginTapWithJitter(agreeHit.point, w, h)
                    devOp(alias, "自动登录 agree and(下方)：tap (${tap.x},${tap.y})")
                    if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                        if (!autoLoginDelay(alias, 450L, "agree and 后")) return dontAllowHandled
                        agreeHandled = true
                        agreeTappedThisPass = true
                    }
                }
            }

            if (!dontAllowHandled && !agreeTappedThisPass) {
                val dontAllowHit = findDontAllowLabelHit(vision) ?: continue
                val tap = autoLoginTapWithJitter(dontAllowHit.point, w, h)
                devOp(alias, "自动登录 Don't allow(预处理)：tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    if (!autoLoginDelay(alias, 450L, "Don't allow 后")) return true
                    dontAllowHandled = true
                }
            }
        }
        return dontAllowHandled
    }

    private fun ocrVisionHasSwipePrompt(result: Text): Boolean =
        ocrVisionHasSwipeUpPrompt(result) ||
            findStrictExactLabelHit(result, "swipe") != null ||
            findStrictExactLabelHit(result, "Swipe") != null ||
            findLabelHitContainingIgnoreCase(result, "swipe") != null

    private suspend fun autoLoginPerformSwipeUpTwice(
        alias: String,
        w: Int,
        h: Int,
        logTag: String = "上滑",
    ): Boolean {
        val x = (w * 0.5f).toInt().coerceIn(1, (w - 1).coerceAtLeast(1))
        val startY = (h * 0.78f).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        val endY = (h * 0.28f).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        repeat(2) { idx ->
            if (hardStopOrNull(alias, "自动登录-$logTag")) return false
            devOp(alias, "自动登录：$logTag ${idx + 1}/2（swipe $x,$startY → $x,$endY）")
            runSuCommand("input swipe $x $startY $x $endY 520")
            if (!autoLoginDelay(alias, 450L, "$logTag 后")) return false
        }
        return true
    }

    private suspend fun autoLoginSwipeUpTwice(ctx: Context, alias: String, w: Int, h: Int): Boolean {
        if (hardStopOrNull(alias, "自动登录-上滑前OCR")) return false
        val swipeVision = autoLoginOcrOnce(ctx, alias, "auto_login_swipe_gate")
        if (swipeVision == null) {
            devOp(alias, "自动登录：上滑前 OCR 失败，跳过上滑两次")
            return true
        }
        if (!ocrVisionHasSwipePrompt(swipeVision)) {
            devOp(alias, "自动登录：未识别到 swipe 文案，跳过上滑两次")
            return true
        }
        devOp(alias, "自动登录：已识别 swipe，执行上滑两次")
        return autoLoginPerformSwipeUpTwice(alias, w, h)
    }

    private suspend fun autoLoginTryDismissAgreeAndThenDontAllow(
        ctx: Context,
        alias: String,
        handleDontAllow: Boolean = true,
    ): Boolean {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        var handledAny = false
        for (pass in 1..3) {
            if (hardStopOrNull(alias, "自动登录-agree and / Don't allow")) return handledAny
            if (pass > 1 && !autoLoginDelay(alias, 1000L, "agree and / Don't allow")) return handledAny
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_agree_and_or_dont_allow_$pass") ?: continue

            val agreeHit = findBottomBandLabelContaining(vision, h, "agree and")
            if (agreeHit != null) {
                val tap = autoLoginTapWithJitter(agreeHit.point, w, h)
                devOp(alias, "自动登录 agree and(下方)：tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    autoLoginDelay(alias, 450L, "agree and 后")
                    handledAny = true
                }
            }

            if (handleDontAllow) {
                val dontAllowHit = findDontAllowLabelHit(vision)
                if (dontAllowHit != null) {
                    val tap = autoLoginTapWithJitter(dontAllowHit.point, w, h)
                    devOp(alias, "自动登录 Don't allow：tap (${tap.x},${tap.y})")
                    if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                        autoLoginDelay(alias, 450L, "Don't allow 后")
                        handledAny = true
                    }
                }
            }

            if (handledAny) return true
        }
        return false
    }

    /**
     * 预处理：从 For You 点底栏 Profile，进入可看到 Log in / Sign up 的页面。
     * 这不是密码后「处理 profile 弹窗」——弹窗逻辑已取消，仅保留此入口导航。
     */
    private suspend fun autoLoginTapProfileForLoginEntry(
        ctx: Context,
        alias: String,
    ): Boolean {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        for (pass in 1..3) {
            if (hardStopOrNull(alias, "自动登录-入口Profile")) return false
            if (pass > 1 && !autoLoginDelay(alias, 1000L, "入口Profile")) return false
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_entry_profile_$pass") ?: continue
            if (ocrVisionHasSwipePrompt(vision)) {
                devOp(alias, "自动登录：入口 Profile 定位前识别到 Swipe，上滑两次后继续")
                if (!autoLoginPerformSwipeUpTwice(alias, w, h, "入口Profile-Swipe")) return false
                if (!autoLoginDelay(alias, 600L, "入口Profile Swipe后")) return false
                continue
            }
            val hit = findBottomBandLabelContaining(vision, h, "profile")
                ?: findLabelHitContainingIgnoreCase(vision, "profile")
            if (hit == null) continue
            val tap = autoLoginTapWithJitter(hit.point, w, h)
            devOp(alias, "自动登录：预处理点 Profile 进登录入口 (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                autoLoginDelay(alias, 450L, "入口Profile 后")
                return true
            }
        }
        return false
    }

    private fun ocrVisionHasStrictEdit(result: Text): Boolean =
        findStrictEditTapPoint(result) != null || findStrictExactLabelHit(result, "Edit") != null

    /** 终检点 Profile：底栏 profile 文案。 */
    private fun findAutoLoginProfileTabHit(result: Text, screenH: Int): StrictLabelHit? =
        findBottomBandLabelContaining(result, screenH, "profile", bottomFractionStart = 0.72f)

    /**
     * 自动登录唯一成功终检：所有分支汇合后 OCR 点 Profile → OCR 确认 Edit 文案。
     */
    private suspend fun autoLoginVerifySuccessByProfileAndEdit(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
    ): Boolean {
        val maxPasses = AUTO_LOGIN_OCR_MAX_PASSES
        for (pass in 1..maxPasses) {
            if (hardStopOrNull(alias, "自动登录-成功校验 Profile")) return false
            if (pass > 1 && !autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "成功校验 Profile")) {
                return false
            }
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_success_profile_$pass") ?: continue
            if (ocrVisionHasSwipePrompt(vision)) {
                devOp(alias, "自动登录成功校验：识别到 Swipe，上滑两次后继续定位 Profile")
                if (!autoLoginPerformSwipeUpTwice(alias, w, h, "成功校验-Swipe")) return false
                if (!autoLoginDelay(alias, 600L, "成功校验 Swipe后")) return false
                continue
            }
            val profileHit = findAutoLoginProfileTabHit(vision, h)
            if (profileHit == null) {
                val preview = buildProfileEditOcrPreview(vision).take(300)
                devOp(alias, "自动登录成功校验：第 $pass/$maxPasses 次 OCR 未命中底栏 Profile；OCR=$preview")
                continue
            }
            val tap = autoLoginTapWithJitter(profileHit.point, w, h)
            devOp(alias, "自动登录成功校验：点底栏 Profile (${tap.x},${tap.y}) [pass=$pass]")
            if (!runSuCommand("input tap ${tap.x} ${tap.y}")) continue
            if (!autoLoginDelay(alias, 900L, "成功校验 Profile 点击后")) return false

            for (editPass in 1..maxPasses) {
                if (hardStopOrNull(alias, "自动登录-成功校验 Edit")) return false
                if (editPass > 1 && !autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "成功校验 Edit")) {
                    return false
                }
                val editVision = autoLoginOcrOnce(ctx, alias, "auto_login_success_edit_${pass}_$editPass") ?: continue
                if (ocrVisionHasStrictEdit(editVision)) {
                    val preview = buildProfileEditOcrPreview(editVision).take(400)
                    devOp(alias, "自动登录成功校验：已识别 Edit，判定登录成功；OCR=$preview")
                    return true
                }
                val preview = buildProfileEditOcrPreview(editVision).take(300)
                devOp(
                    alias,
                    "自动登录成功校验：Profile 点击后第 $editPass/$maxPasses 次 OCR 未命中 Edit；OCR=$preview",
                )
            }
            devOp(alias, "自动登录成功校验：点 Profile 后 $maxPasses 次均未识别 Edit，重试点 Profile")
        }
        devOp(alias, "自动登录成功校验：$maxPasses 轮均未完成 Profile→Edit")
        return false
    }

    private enum class SafeguardProfileScreenKind {
        /** Profile 页见 Edit → 已登录。 */
        LoggedIn,
        /** Log in / Sign up / 登录表单等 → 需自动登录。 */
        NeedsLogin,
        /** 底栏 Home+Profile 但未见 Edit（可能已在 Feed）。 */
        MainFeedNavOnly,
        Unknown,
    }

    private fun classifySafeguardProfileScreen(vision: Text, screenH: Int): SafeguardProfileScreenKind {
        if (ocrVisionHasStrictEdit(vision)) return SafeguardProfileScreenKind.LoggedIn
        if (autoLoginSeesInterestPicker(vision)) return SafeguardProfileScreenKind.LoggedIn
        if (safeguardVisionNeedsAutoLogin(vision, screenH)) return SafeguardProfileScreenKind.NeedsLogin
        if (autoLoginSeesMainFeedBottomNav(vision, screenH)) return SafeguardProfileScreenKind.MainFeedNavOnly
        return SafeguardProfileScreenKind.Unknown
    }

    /** 登录状态保障：除 Edit 外，识别未登录/登录中途界面；Account status / Welcome back 由 [probeAndHandleLogoutSignalsFromVision] 处理。 */
    private fun safeguardVisionNeedsAutoLogin(vision: Text, screenH: Int): Boolean {
        if (findBottomBandLabelContaining(vision, screenH, "log in") != null) return true
        if (findBottomBandLabelContaining(vision, screenH, "sign up") != null) return true
        if (findAddAnotherAccountTapHit(vision) != null) return true
        if (findAutoLoginUsernameTapHit(vision) != null) return true
        if (ocrVisionHasVerifyEmailMarker(vision)) return true
        if (findLabelHitContainingIgnoreCase(vision, "verification code") != null) return true
        if (findLabelHitContainingIgnoreCase(vision, "enter code") != null) return true
        if (findLabelHitContainingIgnoreCase(vision, "use phone") != null) return true
        if (findLabelHitContainingIgnoreCase(vision, "use email") != null) return true
        if (findLabelHitContainingIgnoreCase(vision, "add account") != null) return true
        if (findLabelHitContainingIgnoreCase(vision, "log in to") != null) return true
        if (countLabelContainingIgnoreCase(vision, "password") >= 1) return true
        return false
    }

    private suspend fun safeguardTapBottomHome(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
    ): Boolean {
        for (pass in 1..AUTO_LOGIN_OCR_MAX_PASSES) {
            if (hardStopOrNull(alias, "登录状态保障-点Home")) return false
            if (pass > 1 && !autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "登录状态保障-点Home")) return false
            val vision = autoLoginOcrOnce(ctx, alias, "login_safeguard_home_$pass") ?: continue
            val hit = findBottomBandHomeTabHit(vision, h)
                ?: findBottomBandLabelContaining(vision, h, "home")
            if (hit == null) {
                devOp(alias, "登录状态保障：第 $pass 次未命中底栏 Home")
                continue
            }
            val tap = coerceTapToScreen(hit.point, w, h)
            devOp(alias, "登录状态保障：点底栏 Home (${tap.x},${tap.y}) [pass=$pass]")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                autoLoginDelay(alias, 500L, "登录状态保障 Home 后")
                if (lastEndedAtUsersSelection) {
                    devOp(alias, "登录状态保障：已回 Home，清除 Users 复用标记")
                }
                lastEndedAtUsersSelection = false
                return true
            }
        }
        return false
    }

    override suspend fun runLoginSafeguardMeasure(ctx: Context, sourceTag: String): LoginSafeguardOutcome {
        loginSafeguardLogoutProbeActive = true
        try {
            return runLoginSafeguardMeasureCore(ctx, sourceTag)
        } finally {
            loginSafeguardLogoutProbeActive = false
        }
    }

    private suspend fun runLoginSafeguardMeasureCore(ctx: Context, sourceTag: String): LoginSafeguardOutcome {
        val alias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        if (hardStopOrNull(alias, "登录状态保障")) return LoginSafeguardOutcome.Failed

        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)

        devOp(alias, "登录状态保障：开始（来源=$sourceTag）")

        runCatching {
            autoLoginPreprocessDismissAgreeAndDontAllow(ctx, alias, maxPasses = 2, retryDelayMs = 800L)
        }

        if (!autoLoginSwipeUpTwice(ctx, alias, w, h)) {
            devOp(alias, "登录状态保障：上滑门禁失败")
            return LoginSafeguardOutcome.Failed
        }

        if (!autoLoginTapProfileForLoginEntry(ctx, alias)) {
            devOp(alias, "登录状态保障：未能点击底栏 Profile")
            return LoginSafeguardOutcome.Failed
        }
        if (!autoLoginDelay(alias, 900L, "登录状态保障 Profile 后")) return LoginSafeguardOutcome.Failed

        for (pass in 1..AUTO_LOGIN_OCR_MAX_PASSES) {
            if (hardStopOrNull(alias, "登录状态保障-判定")) return LoginSafeguardOutcome.Failed
            if (pass > 1 && !autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "登录状态保障-判定")) {
                return LoginSafeguardOutcome.Failed
            }
            val vision = autoLoginOcrOnce(ctx, alias, "login_safeguard_profile_$pass") ?: continue
            when (classifySafeguardProfileScreen(vision, h)) {
                SafeguardProfileScreenKind.LoggedIn -> {
                    if (autoLoginSeesInterestPicker(vision)) {
                        devOp(alias, "登录状态保障：识别兴趣页，先跳过")
                        when (autoLoginHandleInterestPicker(ctx, alias, w, h)) {
                            AutoLoginStepOutcome.Ok,
                            AutoLoginStepOutcome.ReachedHome,
                            AutoLoginStepOutcome.ReachedInterest,
                            -> Unit
                            AutoLoginStepOutcome.ChainRestart,
                            AutoLoginStepOutcome.Failed,
                            -> devOp(alias, "登录状态保障：兴趣页处理未完全成功，仍尝试回 Home")
                        }
                    }
                    if (safeguardTapBottomHome(ctx, alias, w, h)) {
                        devOp(alias, "登录状态保障：Profile 见 Edit/兴趣页，已点 Home，可继续")
                        return LoginSafeguardOutcome.Ready
                    }
                    devOp(alias, "登录状态保障：已登录但点 Home 失败")
                    return LoginSafeguardOutcome.Failed
                }
                SafeguardProfileScreenKind.NeedsLogin -> {
                    val preview = buildProfileEditOcrPreview(vision).take(320)
                    devOp(alias, "登录状态保障：判定未登录（Log in/Sign up/登录表单等）；OCR=$preview")
                    return LoginSafeguardOutcome.RequiresAutoLogin
                }
                SafeguardProfileScreenKind.MainFeedNavOnly -> {
                    if (safeguardTapBottomHome(ctx, alias, w, h)) {
                        devOp(alias, "登录状态保障：底栏 Home+Profile 无 Edit，已点 Home 继续")
                        return LoginSafeguardOutcome.Ready
                    }
                }
                SafeguardProfileScreenKind.Unknown -> {
                    val preview = buildProfileEditOcrPreview(vision).take(280)
                    devOp(alias, "登录状态保障：第 $pass 次 Profile 状态未明；OCR=$preview")
                }
            }
        }

        devOp(alias, "登录状态保障：多轮 OCR 无法确认已登录，需自动登录")
        return LoginSafeguardOutcome.RequiresAutoLogin
    }

    /** @return null 表示登录状态保障通过；非 null 为应中止当前流程的返回文案。 */
    private suspend fun runLoginSafeguardMeasureOrAbortMessage(
        ctx: Context,
        sourceTag: String,
    ): String? {
        return try {
            when (runLoginSafeguardMeasure(ctx, sourceTag)) {
                LoginSafeguardOutcome.Ready -> null
                LoginSafeguardOutcome.RequiresAutoLogin -> SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE
                LoginSafeguardOutcome.Failed -> "失败：登录状态保障未通过"
            }
        } catch (logout: LoginSafeguardRequiresAutoLoginException) {
            SAFEGUARD_REQUIRES_AUTO_LOGIN_MESSAGE
        } catch (logout: AccountStatusLoggedOutException) {
            throw logout
        }
    }

    private suspend fun autoLoginWaitForContinueOrUsername(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        tagPrefix: String,
        timeoutMs: Long = 180_000L,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        var pass = 0
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (hardStopOrNull(alias, "自动登录-Continue等待")) return false
            pass++
            val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_$pass")
            if (vision == null) {
                if (!autoLoginDelay(alias, 1000L, "Continue等待")) return false
                continue
            }
            val continueHit = findStrictExactLabelHit(vision, "Continue")
                ?: findStrictExactLabelHit(vision, "continue")
            if (continueHit != null) {
                val tap = autoLoginTapWithJitter(continueHit.point, w, h)
                devOp(alias, "自动登录 Continue：tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    return true
                }
            }
            val usernameVisible = findLabelHitContainingIgnoreCase(vision, "username") != null ||
                findStrictExactLabelHit(vision, "Username") != null ||
                findStrictExactLabelHit(vision, "username") != null
            if (usernameVisible) {
                devOp(alias, "自动登录：Continue 未命中但仍可见 username，继续轮询")
            }
            if (!autoLoginDelay(alias, 1000L, "Continue等待")) return false
        }
        devOp(alias, "自动登录：Continue 等待超时（${timeoutMs / 1000}s）")
        return false
    }

    /**
     * Email 输入后：OCR 找 Continue 并点击（多次允许），随后持续 OCR：
     * - 仍能看到「Facebook」= 与第一次点 Username 同屏，说明 Username 未跳走，不点 Continue
     * - 见不到 Facebook 且见到 Continue = 已离开 Username 页，点 Continue
     * - 见到含 Verify 的文案 = 跳转成功
     * 超时仍可见 Facebook → 判失败（Username 页未离开）
     */
    private suspend fun autoLoginTapContinueAndWaitForVerifyEmail(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        tagPrefix: String,
        timeoutMs: Long = 180_000L,
    ): AutoLoginEmailAfterContinueOutcome {
        val startedAt = System.currentTimeMillis()
        var pass = 0
        var lastSawFacebook = false
        var lastContinueTapAt = 0L
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (hardStopOrNull(alias, "自动登录-Email后等待跳转")) return AutoLoginEmailAfterContinueOutcome.Failed
            pass++
            val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_$pass")
            if (vision == null) {
                if (!autoLoginDelay(alias, 1000L, "Email后等待跳转")) return AutoLoginEmailAfterContinueOutcome.Failed
                continue
            }

            val sawVerifyEmail = ocrVisionHasVerifyEmailMarker(vision)
            if (sawVerifyEmail) {
                devOp(alias, "自动登录：已识别 Verify 文案，认为 Email→Continue 跳转成功（第 $pass 次 OCR）")
                return AutoLoginEmailAfterContinueOutcome.VerifyReady
            }
            if (autoLoginVisionShowsCheckYourInbox(vision)) {
                devOp(alias, "自动登录：Email后 OCR 命中 Check your inbox，转入 Inbox 密码路径（第 $pass 次）")
                return AutoLoginEmailAfterContinueOutcome.CheckYourInbox
            }

            val sawFacebook = findLabelHitContainingIgnoreCase(vision, "facebook") != null
            lastSawFacebook = sawFacebook

            // 见不到 Facebook 才点 Continue（Facebook 与第一次点 Username 同屏；仍见 Facebook = Username 未跳走）。
            val continueStrict = findStrictExactLabelHit(vision, "Continue")
                ?: findStrictExactLabelHit(vision, "continue")
            val continueLoose = findLabelHitContainingIgnoreCase(vision, "continue")
            val now = System.currentTimeMillis()
            val hit = continueStrict ?: continueLoose
            val canTapContinue = !sawFacebook &&
                hit != null &&
                now - lastContinueTapAt >= AUTO_LOGIN_EMAIL_CONTINUE_TAP_COOLDOWN_MS
            autoLoginLogEmailAfterContinueProbe(
                alias, pass, sawVerifyEmail, sawFacebook, continueStrict, continueLoose,
            )
            if (canTapContinue && hit != null) {
                val tap = autoLoginTapWithJitter(hit.point, w, h)
                devOp(alias, "自动登录 Continue(Email后)：tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    lastContinueTapAt = now
                    autoLoginDelay(alias, 600L, "Continue 点击后")
                }
            } else if (hit == null) {
                devOp(alias, "自动登录：Email后第 $pass 次 OCR 未命中 Continue，继续重试")
            }

            if (sawFacebook) {
                val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
                devOp(
                    alias,
                    "自动登录：Email后第 $pass 次仍见 Facebook（Username 页，暂不点 Continue），继续 OCR（已耗时 ${elapsedSec}s）",
                )
                if (!autoLoginDelay(alias, 1000L, "Email后等待跳转")) return AutoLoginEmailAfterContinueOutcome.Failed
                continue
            }

            if (!autoLoginDelay(alias, 1000L, "Email后等待跳转")) return AutoLoginEmailAfterContinueOutcome.Failed
        }

        val elapsed = (System.currentTimeMillis() - startedAt) / 1000
        devOp(alias, "自动登录：Email→Continue 等待跳转超时（${elapsed}s），lastSawFacebook=$lastSawFacebook")
        return AutoLoginEmailAfterContinueOutcome.Failed
    }

    private fun autoLoginSeesInterestPicker(result: Text): Boolean {
        val hasSkip = findLabelHitContainingIgnoreCase(result, "skip") != null
        val interestHits = AUTO_LOGIN_INTEREST_TAGS
            .asSequence()
            .filter { it != "skip" }
            .count { key -> findLabelHitContainingIgnoreCase(result, key) != null }
        return hasSkip && interestHits >= 2
    }

    /**
     * 密码页 Continue/Next 后持续 OCR：判定直达首页（跳过兴趣页）或进入兴趣页。
     * 与验证码后 OCR 轮询分支思路一致。
     */
    private suspend fun autoLoginPollPostPasswordRoute(
        ctx: Context,
        alias: String,
        h: Int,
        timeoutMs: Long = AUTO_LOGIN_POST_PASSWORD_ROUTE_MAX_DURATION_MS,
    ): AutoLoginStepOutcome {
        val startedAt = System.currentTimeMillis()
        var ocrPass = 0
        devOp(alias, "自动登录：密码已提交，开始 OCR 轮询路由（最长 ${timeoutMs / 1000}s）")
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (hardStopOrNull(alias, "自动登录-密码后路由")) return AutoLoginStepOutcome.Failed
            ocrPass++
            if (ocrPass > 1 && !autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "密码后路由 OCR")) {
                return AutoLoginStepOutcome.Failed
            }
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_post_password_$ocrPass") ?: continue
            val screenKind = autoLoginClassifyRouteScreen(vision, h)
            val preview = buildProfileEditOcrPreview(vision).take(300)
            when (screenKind) {
                AutoLoginRouteScreenKind.Interest -> {
                    devOp(
                        alias,
                        "自动登录：密码后界面=${autoLoginRouteScreenKindLabel(screenKind)}（第 $ocrPass 次 OCR）；OCR=$preview",
                    )
                    return AutoLoginStepOutcome.ReachedInterest
                }
                AutoLoginRouteScreenKind.MainFeed -> {
                    devOp(
                        alias,
                        "自动登录：密码后界面=${autoLoginRouteScreenKindLabel(screenKind)}，跳过兴趣页（第 $ocrPass 次 OCR）" +
                            "；${autoLoginDirectSuccessMatchReason(vision, h)}；OCR=$preview",
                    )
                    return AutoLoginStepOutcome.ReachedHome
                }
                AutoLoginRouteScreenKind.Unknown -> {
                    val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
                    if (ocrPass <= 3 || ocrPass % 10 == 0) {
                        devOp(
                            alias,
                            "自动登录：密码后第 $ocrPass 次 OCR 界面=未识别，继续轮询（已耗时 ${elapsedSec}s）；OCR=$preview",
                        )
                    }
                }
            }
        }
        devOp(
            alias,
            "自动登录：密码后 OCR 路由超时（${ocrPass} 次，${(System.currentTimeMillis() - startedAt) / 1000}s）",
        )
        return AutoLoginStepOutcome.Failed
    }

    private suspend fun autoLoginHandleInterestPicker(ctx: Context, alias: String, w: Int, h: Int): AutoLoginStepOutcome {
        if (!autoLoginDelay(alias, 600L, "兴趣页")) return AutoLoginStepOutcome.Failed
        val vision = autoLoginOcrOnce(ctx, alias, "auto_login_interest") ?: return AutoLoginStepOutcome.Failed
        data class TagHit(val key: String, val hit: StrictLabelHit)
        val hits = ArrayList<TagHit>()
        for (key in AUTO_LOGIN_INTEREST_TAGS) {
            findLabelHitContainingIgnoreCase(vision, key)?.let { hits.add(TagHit(key, it)) }
        }
        if (hits.isEmpty()) {
            devOp(alias, "自动登录：兴趣页未识别到标签，跳过")
            return AutoLoginStepOutcome.Ok
        }
        val skipOnly = hits.size == 1 && hits[0].key == "skip"
        val pool = hits.filter { it.key != "skip" }
        val picked = if (skipOnly) {
            hits
        } else if (pool.isNotEmpty()) {
            val count = Random.nextInt(1, minOf(3, pool.size + 1))
            pool.shuffled().take(count)
        } else {
            hits.filter { it.key == "skip" }
        }
        for (item in picked) {
            val tap = autoLoginTapWithJitter(item.hit.point, w, h)
            devOp(alias, "自动登录：点兴趣 ${item.key} (${tap.x},${tap.y})")
            runSuCommand("input tap ${tap.x} ${tap.y}")
            autoLoginDelay(alias, Random.nextLong(350, 700), "兴趣项")
        }
        val tappedSkip = picked.any { it.key == "skip" }
        if (!tappedSkip) {
            val nextOutcome = autoLoginOcrTapByFinder(
                ctx, alias, w, h, "auto_login_interest_next", "Next",
                findHit = { v ->
                    findStrictExactLabelHit(v, "Next")
                        ?: findStrictExactLabelHit(v, "next")
                },
            )
            if (nextOutcome == AutoLoginStepOutcome.Failed) {
                devOp(alias, "自动登录：兴趣页 Next 未命中")
                return AutoLoginStepOutcome.Failed
            }
        }
        return AutoLoginStepOutcome.Ok
    }

    private suspend fun autoLoginEnterVerificationCode(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        deviceId: String,
        apiUrl: String,
        email: String,
    ): AutoLoginStepOutcome {
        val startedAt = System.currentTimeMillis()
        var resendRetries = 0
        var ocrPass = 0

        suspend fun fetchAndInputCode(focusVision: Text? = null): AutoLoginStepOutcome? {
            val codeResult = TikTokEmailCodeFetcher.fetchCode(deviceId, alias, apiUrl, email)
            val fetched = codeResult.getOrNull()
            if (fetched.isNullOrBlank()) {
                val errMsg = codeResult.exceptionOrNull()?.message
                devOp(alias, "自动登录：获取验证码失败：$errMsg")
                if (TikTokEmailCodeFetcher.isUnrecoverableConfigError(errMsg)) {
                    devOp(alias, "自动登录：验证码配置不可恢复，停止整链重来")
                    return AutoLoginStepOutcome.Failed
                }
                return null
            }
            devOp(alias, "自动登录：已获取验证码")
            autoLoginTapVerificationCodeField(ctx, alias, w, h, focusVision)
            autoLoginClearFocusedInput(alias)
            if (!autoLoginInputText(alias, fetched)) return AutoLoginStepOutcome.Failed
            if (!autoLoginDelay(alias, 400L, "验证码输入后")) return AutoLoginStepOutcome.Failed
            return AutoLoginStepOutcome.Ok
        }

        suspend fun ocrTapResendCode(tagPrefix: String, maxPasses: Int = 3): Boolean {
            for (pass in 1..maxPasses.coerceIn(1, 10)) {
                if (hardStopOrNull(alias, "自动登录-Resend code点击")) return false
                if (pass > 1 && !autoLoginDelay(alias, 1000L, "Resend code")) return false
                val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_$pass") ?: continue
                val resendHit = findLabelHitContainingIgnoreCase(vision, "resend code") ?: continue
                val tap = autoLoginTapWithJitter(resendHit.point, w, h)
                devOp(alias, "自动登录：点击 Resend code（第 $pass/$maxPasses 次）tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    return true
                }
            }
            return false
        }

        /**
         * expired 恢复：等待 58s → 返回 → 重点 Continue → 重点 Resend code → 拉取并输入验证码。
         * 避免在验证码输入框未聚焦时直接清空导致反复 expired。
         */
        suspend fun recoverFromExpiredVerificationCode(): Boolean {
            devOp(alias, "自动登录：识别 expired，等待 ${AUTO_LOGIN_EXPIRED_RECOVERY_WAIT_MS / 1000}s 后返回并重走 Continue→Resend code")
            if (!autoLoginDelay(alias, AUTO_LOGIN_EXPIRED_RECOVERY_WAIT_MS, "expired 冷却")) return false

            navBack(alias, "验证码 expired 恢复")
            if (!autoLoginDelay(alias, 800L, "expired 返回后")) return false

            val continueOutcome = autoLoginOcrTapByFinder(
                ctx,
                alias,
                w,
                h,
                "auto_login_expired_continue",
                "Continue(expired恢复)",
                maxPasses = AUTO_LOGIN_OCR_MAX_PASSES,
                retryDelayMs = AUTO_LOGIN_OCR_RETRY_DELAY_MS,
                findHit = { v ->
                    findStrictExactLabelHit(v, "Continue")
                        ?: findStrictExactLabelHit(v, "continue")
                },
            )
            if (continueOutcome != AutoLoginStepOutcome.Ok) {
                devOp(alias, "自动登录：expired 恢复未点到 Continue")
                return false
            }
            if (!autoLoginDelay(alias, 800L, "expired Continue 后")) return false

            if (!ocrTapResendCode("auto_login_expired_resend")) {
                devOp(alias, "自动登录：expired 恢复未点到 Resend code")
                return false
            }
            if (!autoLoginDelay(alias, AUTO_LOGIN_RESEND_WAIT_MS, "expired Resend code 后")) return false
            resendRetries++
            return when (val outcome = fetchAndInputCode()) {
                AutoLoginStepOutcome.Ok -> true
                AutoLoginStepOutcome.Failed -> false
                else -> false
            }
        }

        when (val firstFetch = fetchAndInputCode()) {
            AutoLoginStepOutcome.Failed -> return AutoLoginStepOutcome.Failed
            AutoLoginStepOutcome.Ok -> Unit
            else -> return AutoLoginStepOutcome.ChainRestart
        }

        while (System.currentTimeMillis() - startedAt < AUTO_LOGIN_CODE_AFTER_OCR_MAX_DURATION_MS) {
            if (hardStopOrNull(alias, "自动登录-验证码后")) return AutoLoginStepOutcome.Failed
            ocrPass++
            if (ocrPass > 1 && !autoLoginDelay(alias, 1000L, "验证码后 OCR")) return AutoLoginStepOutcome.Failed
            val vision = autoLoginOcrOnce(ctx, alias, "auto_login_after_code_$ocrPass") ?: continue

            if (findLabelHitContainingIgnoreCase(vision, "expired") != null) {
                if (!recoverFromExpiredVerificationCode()) {
                    if (TikTokEmailCodeFetcher.consumeLastFetchUnrecoverableConfig()) {
                        return AutoLoginStepOutcome.Failed
                    }
                    return AutoLoginStepOutcome.ChainRestart
                }
                continue
            }

            when (autoLoginClassifyRouteScreen(vision, h)) {
                AutoLoginRouteScreenKind.Interest -> {
                    devOp(alias, "自动登录：验证码后界面=兴趣页（第 $ocrPass 次 OCR）")
                    return AutoLoginStepOutcome.ReachedInterest
                }
                AutoLoginRouteScreenKind.MainFeed -> {
                    devOp(
                        alias,
                        "自动登录：验证码后界面=主Feed(底栏Home+Profile)，判定直达成功（第 $ocrPass 次 OCR）" +
                            "；${autoLoginDirectSuccessMatchReason(vision, h)}",
                    )
                    return AutoLoginStepOutcome.ReachedHome
                }
                AutoLoginRouteScreenKind.Unknown -> Unit
            }

            if (findSinglePasswordAndNextHits(vision) != null) {
                devOp(alias, "自动登录：验证码页已通过（布局A命中 password+next，第 $ocrPass 次 OCR）")
                return AutoLoginStepOutcome.Ok
            }

            val pwLabels = findAllPasswordLabelHits(vision)
            if (pwLabels.size >= 2) {
                devOp(alias, "自动登录：验证码页已通过（严格 password>=2，第 $ocrPass 次 OCR）")
                return AutoLoginStepOutcome.Ok
            }

            if (findLabelHitContainingIgnoreCase(vision, "resend code") != null) {
                val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000)
                autoLoginLogVerificationCodePageProbe(alias, ocrPass, vision, elapsedSec)
            }
        }
        devOp(alias, "自动登录：验证码后 OCR 超时（已重试 $ocrPass 次，resend=$resendRetries），需要整链重来")
        return AutoLoginStepOutcome.ChainRestart
    }

    private enum class AutoLoginCutSwitchTransitionOutcome {
        ReachedInterest,
        ReachedHome,
        ReachedVerify,
        Timeout,
        Failed,
    }

    private enum class AutoLoginCutSwitchPostEmailBranch {
        CheckYourInbox,
        DirectPassword,
        VerifyPage,
        ShortPathInterest,
        ShortPathHome,
        StillOnEmail,
        Unknown,
    }

    /** 旧流 Email→Continue 等待：见到 Verify / Check your inbox / 超时失败。 */
    private enum class AutoLoginEmailAfterContinueOutcome {
        VerifyReady,
        CheckYourInbox,
        Failed,
    }

    private sealed class AutoLoginCutSwitchStepOutcome {
        data object Ok : AutoLoginCutSwitchStepOutcome()
        data object ChainRestart : AutoLoginCutSwitchStepOutcome()
        data object Failed : AutoLoginCutSwitchStepOutcome()
    }

    private fun autoLoginVisionHasContinueMarker(vision: Text): Boolean =
        findStrictExactLabelHit(vision, "Continue") != null ||
            findStrictExactLabelHit(vision, "continue") != null ||
            findLabelHitContainingIgnoreCase(vision, "continue") != null

    private fun autoLoginVisionOnVerificationPage(vision: Text): Boolean =
        autoLoginDetectVerificationPageMarker(vision) != null ||
            findLabelHitContainingIgnoreCase(vision, "resend code") != null

    private fun autoLoginVisionShowsCheckYourInbox(vision: Text): Boolean =
        findLabelHitContainingIgnoreCaseRelaxed(vision, "check your inbox") != null ||
            findAutoLoginNeedHelpLoggingHit(vision) != null

    private fun autoLoginCutSwitchClassifyPostEmailBranch(vision: Text, screenH: Int): AutoLoginCutSwitchPostEmailBranch {
        if (autoLoginVisionShowsCheckYourInbox(vision)) {
            return AutoLoginCutSwitchPostEmailBranch.CheckYourInbox
        }
        when (autoLoginClassifyRouteScreen(vision, screenH)) {
            AutoLoginRouteScreenKind.Interest -> return AutoLoginCutSwitchPostEmailBranch.ShortPathInterest
            AutoLoginRouteScreenKind.MainFeed -> return AutoLoginCutSwitchPostEmailBranch.ShortPathHome
            AutoLoginRouteScreenKind.Unknown -> Unit
        }
        if (ocrVisionHasVerifyEmailMarker(vision)) return AutoLoginCutSwitchPostEmailBranch.VerifyPage
        if (findSinglePasswordAndNextHits(vision) != null ||
            findAllPasswordLabelHits(vision).isNotEmpty()
        ) {
            return AutoLoginCutSwitchPostEmailBranch.DirectPassword
        }
        if (ocrVisionHasEmailFieldPrompt(vision) ||
            findLabelHitContainingIgnoreCase(vision, "facebook") != null
        ) {
            return AutoLoginCutSwitchPostEmailBranch.StillOnEmail
        }
        return AutoLoginCutSwitchPostEmailBranch.Unknown
    }

    /**
     * 切号流：点击 Continue 后等待旧页特征消失，再等待兴趣页/底栏 Home/verify 出现（80s）。
     * 兴趣页与 Home 可在任意时刻命中；verify 须在 [markerGone] 为 true 后命中。
     */
    private suspend fun autoLoginWaitPageTransitionCutSwitch(
        ctx: Context,
        alias: String,
        h: Int,
        phaseLog: String,
        tagPrefix: String,
        markerGone: (Text) -> Boolean,
        allowVerifySuccess: Boolean,
    ): AutoLoginCutSwitchTransitionOutcome {
        val startedAt = System.currentTimeMillis()
        var pass = 0
        var goneObserved = false
        devOp(alias, "自动登录(切号)：$phaseLog 开始页面跳转门禁（最长 ${AUTO_LOGIN_CUT_SWITCH_PAGE_TRANSITION_MS / 1000}s）")
        while (System.currentTimeMillis() - startedAt < AUTO_LOGIN_CUT_SWITCH_PAGE_TRANSITION_MS) {
            if (hardStopOrNull(alias, "自动登录(切号)-$phaseLog")) {
                return AutoLoginCutSwitchTransitionOutcome.Failed
            }
            pass++
            if (pass > 1 && !autoLoginDelay(alias, 1000L, phaseLog)) {
                return AutoLoginCutSwitchTransitionOutcome.Failed
            }
            val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_$pass") ?: continue
            when (autoLoginClassifyRouteScreen(vision, h)) {
                AutoLoginRouteScreenKind.Interest -> {
                    devOp(alias, "自动登录(切号)：$phaseLog 命中兴趣页（第 $pass 次 OCR）")
                    return AutoLoginCutSwitchTransitionOutcome.ReachedInterest
                }
                AutoLoginRouteScreenKind.MainFeed -> {
                    devOp(alias, "自动登录(切号)：$phaseLog 命中底栏 Home（第 $pass 次 OCR）")
                    return AutoLoginCutSwitchTransitionOutcome.ReachedHome
                }
                AutoLoginRouteScreenKind.Unknown -> Unit
            }
            if (!goneObserved && markerGone(vision)) {
                goneObserved = true
                devOp(alias, "自动登录(切号)：$phaseLog 旧页特征已消失（第 $pass 次 OCR）")
            }
            if (allowVerifySuccess && goneObserved && ocrVisionHasVerifyEmailMarker(vision)) {
                devOp(alias, "自动登录(切号)：$phaseLog 旧页消失且已见 verify（第 $pass 次 OCR）")
                return AutoLoginCutSwitchTransitionOutcome.ReachedVerify
            }
        }
        devOp(
            alias,
            "自动登录(切号)：$phaseLog 页面跳转门禁超时（${(System.currentTimeMillis() - startedAt) / 1000}s，gone=$goneObserved）",
        )
        return AutoLoginCutSwitchTransitionOutcome.Timeout
    }

    /** 切号流：Email 表单提交后 OCR 分流（Check your inbox 优先）。 */
    private suspend fun autoLoginSubmitEmailAndDetectBranchCutSwitch(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        tagPrefix: String,
    ): AutoLoginCutSwitchPostEmailBranch? {
        val startedAt = System.currentTimeMillis()
        var pass = 0
        var lastContinueTapAt = 0L
        while (System.currentTimeMillis() - startedAt < AUTO_LOGIN_CUT_SWITCH_PAGE_TRANSITION_MS) {
            if (hardStopOrNull(alias, "自动登录(切号)-Email分流")) return null
            pass++
            val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_$pass")
            if (vision == null) {
                if (!autoLoginDelay(alias, 1000L, "Email分流")) return null
                continue
            }
            val branch = autoLoginCutSwitchClassifyPostEmailBranch(vision, h)
            if (branch != AutoLoginCutSwitchPostEmailBranch.StillOnEmail &&
                branch != AutoLoginCutSwitchPostEmailBranch.Unknown
            ) {
                devOp(alias, "自动登录(切号)：Email 后分流=$branch（第 $pass 次 OCR）")
                return branch
            }
            val sawFacebook = findLabelHitContainingIgnoreCase(vision, "facebook") != null
            val continueStrict = findStrictExactLabelHit(vision, "Continue")
                ?: findStrictExactLabelHit(vision, "continue")
            val continueLoose = findLabelHitContainingIgnoreCase(vision, "continue")
            val now = System.currentTimeMillis()
            val hit = continueStrict ?: continueLoose
            val canTapContinue = !sawFacebook &&
                hit != null &&
                now - lastContinueTapAt >= AUTO_LOGIN_EMAIL_CONTINUE_TAP_COOLDOWN_MS
            if (canTapContinue && hit != null) {
                val tap = autoLoginTapWithJitter(hit.point, w, h)
                devOp(alias, "自动登录(切号) Continue(Email后)：tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    lastContinueTapAt = now
                    autoLoginDelay(alias, 600L, "Continue 点击后")
                }
            }
            if (!autoLoginDelay(alias, 1000L, "Email分流")) return null
        }
        devOp(alias, "自动登录(切号)：Email 分流超时（${(System.currentTimeMillis() - startedAt) / 1000}s）")
        return null
    }

    /** 切号流：布局 A→B 输密码并点 Continue/Next（不点 trust this device）。 */
    private suspend fun autoLoginCutSwitchEnterPasswordLayoutAB(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        password: String,
        tagPrefix: String,
    ): Boolean {
        var enteredPasswordViaEnterPasswordLayout = false
        for (pass in 1..AUTO_LOGIN_OCR_MAX_PASSES) {
            val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_layout_a_$pass") ?: continue
            autoLoginLogPasswordLayoutProbe(alias, pass, AUTO_LOGIN_OCR_MAX_PASSES, "切号-布局A探测", vision)
            val hits = findSinglePasswordAndNextHits(vision)
            if (hits == null) {
                if (pass < AUTO_LOGIN_OCR_MAX_PASSES) {
                    autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "切号-Password+Next")
                }
                continue
            }
            val (passwordHit, nextHit) = hits
            val pwTap = autoLoginTapWithJitter(passwordHit.point, w, h)
            devOp(alias, "自动登录(切号)：布局 A 点 password (${pwTap.x},${pwTap.y})")
            runSuCommand("input tap ${pwTap.x} ${pwTap.y}")
            if (!autoLoginDelay(alias, 400L, "切号-password后")) return false
            val nextTap = autoLoginTapWithJitter(nextHit.point, w, h)
            devOp(alias, "自动登录(切号)：布局 A 点 next (${nextTap.x},${nextTap.y})")
            runSuCommand("input tap ${nextTap.x} ${nextTap.y}")
            if (!autoLoginDelay(alias, 800L, "切号-next后")) return false
            var threePasswordsReady = false
            for (verifyPass in 1..AUTO_LOGIN_OCR_MAX_PASSES) {
                val verifyVision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_layout_a_verify_$verifyPass") ?: continue
                val passwordCount = countLabelContainingIgnoreCase(verifyVision, "password")
                if (passwordCount >= 3) {
                    threePasswordsReady = true
                    break
                }
                if (verifyPass < AUTO_LOGIN_OCR_MAX_PASSES) {
                    autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "切号-布局A校验")
                }
            }
            if (!threePasswordsReady) continue
            if (!autoLoginInputText(alias, password)) return false
            enteredPasswordViaEnterPasswordLayout = true
            break
        }
        if (enteredPasswordViaEnterPasswordLayout) {
            return autoLoginOcrTapContinueOrNext(
                ctx, alias, w, h,
                "${tagPrefix}_continue_layout_a",
                "Continue/Next(切号-布局A)",
                maxPasses = 3,
            ) == AutoLoginStepOutcome.Ok
        }
        devOp(alias, "自动登录(切号)：布局 A 未命中，转布局 B")
        var passwordFieldReady = false
        for (pass in 1..AUTO_LOGIN_OCR_MAX_PASSES) {
            val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_layout_b_$pass") ?: continue
            val field = findMiddlePasswordFieldHit(vision)
            if (field != null) {
                val tap = autoLoginTapWithJitter(field.point, w, h)
                runSuCommand("input tap ${tap.x} ${tap.y}")
                passwordFieldReady = true
                break
            }
            if (pass < AUTO_LOGIN_OCR_MAX_PASSES) {
                autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "切号-布局B")
            }
        }
        if (!passwordFieldReady) {
            devOp(alias, "自动登录(切号)：布局 B 密码输入区未定位")
            return false
        }
        if (!autoLoginInputText(alias, password)) return false
        return autoLoginOcrTapByFinder(
            ctx, alias, w, h, "${tagPrefix}_continue_layout_b", "Continue(切号-布局B)",
            findHit = { v ->
                findStrictExactLabelHit(v, "Continue")
                    ?: findStrictExactLabelHit(v, "continue")
            },
        ) == AutoLoginStepOutcome.Ok
    }

    private suspend fun autoLoginCutSwitchInboxPasswordPath(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        password: String,
    ): Boolean {
        val needHelp = autoLoginOcrTapByFinder(
            ctx, alias, w, h, "cut_switch_need_help", "Need help logging in(切号)",
            findHit = { v -> findAutoLoginNeedHelpLoggingHit(v) },
            onVisionProbe = { pass, passes, vision, hit ->
                autoLoginLogNeedHelpLoggingProbe(alias, pass, passes, vision, hit)
            },
        )
        if (needHelp != AutoLoginStepOutcome.Ok) {
            devOp(alias, "自动登录(切号)：未点到 Need help logging in")
            return false
        }
        if (!autoLoginDelay(alias, 800L, "Need help 后")) return false
        val usePwd = autoLoginOcrTapByFinder(
            ctx, alias, w, h, "cut_switch_use_password", "Use password instead(切号)",
            findHit = { v -> findLabelHitContainingIgnoreCase(v, "use password instead") },
        )
        if (usePwd != AutoLoginStepOutcome.Ok) {
            devOp(alias, "自动登录(切号)：未点到 Use password instead")
            return false
        }
        if (!autoLoginDelay(alias, 600L, "Use password instead 后")) return false
        if (!autoLoginInputText(alias, password)) return false
        return autoLoginOcrTapByFinder(
            ctx, alias, w, h, "cut_switch_inbox_continue", "Continue(切号-Inbox密码)",
            findHit = { v ->
                findStrictExactLabelHit(v, "Continue")
                    ?: findStrictExactLabelHit(v, "continue")
            },
        ) == AutoLoginStepOutcome.Ok
    }

    private suspend fun autoLoginCutSwitchApplyTransitionOutcome(
        alias: String,
        outcome: AutoLoginCutSwitchTransitionOutcome,
        phaseLog: String,
        reachedInterest: BooleanArray,
        reachedHome: BooleanArray,
        needVerify: BooleanArray,
    ): AutoLoginCutSwitchStepOutcome {
        return when (outcome) {
            AutoLoginCutSwitchTransitionOutcome.ReachedInterest -> {
                reachedInterest[0] = true
                AutoLoginCutSwitchStepOutcome.Ok
            }
            AutoLoginCutSwitchTransitionOutcome.ReachedHome -> {
                reachedHome[0] = true
                AutoLoginCutSwitchStepOutcome.Ok
            }
            AutoLoginCutSwitchTransitionOutcome.ReachedVerify -> {
                needVerify[0] = true
                AutoLoginCutSwitchStepOutcome.Ok
            }
            AutoLoginCutSwitchTransitionOutcome.Timeout,
            AutoLoginCutSwitchTransitionOutcome.Failed,
            -> {
                devOp(alias, "自动登录(切号)：$phaseLog 页面跳转门禁失败（$outcome）")
                AutoLoginCutSwitchStepOutcome.Failed
            }
        }
    }

    private data class AutoLoginCutSwitchVerifyResult(
        val step: AutoLoginCutSwitchStepOutcome,
        val reachedInterest: Boolean = false,
        val reachedHome: Boolean = false,
    )

    /** 切号流：验证码输入（自动提交；过期清码+Resend，不返回）。 */
    private suspend fun autoLoginEnterVerificationCodeCutSwitch(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        deviceId: String,
        apiUrl: String,
        email: String,
    ): AutoLoginCutSwitchVerifyResult {
        suspend fun fetchCodeOrNull(): String? {
            val codeResult = TikTokEmailCodeFetcher.fetchCode(deviceId, alias, apiUrl, email)
            val code = codeResult.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            if (code == null) {
                devOp(alias, "自动登录(切号)：获取验证码失败：${codeResult.exceptionOrNull()?.message}")
            }
            return code
        }

        suspend fun ocrTapResendCode(tagPrefix: String, maxPasses: Int = 3): Boolean {
            for (pass in 1..maxPasses.coerceIn(1, 10)) {
                if (hardStopOrNull(alias, "自动登录(切号)-Resend")) return false
                if (pass > 1 && !autoLoginDelay(alias, 1000L, "Resend code")) return false
                val vision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_$pass") ?: continue
                val resendHit = findLabelHitContainingIgnoreCase(vision, "resend code") ?: continue
                val tap = autoLoginTapWithJitter(resendHit.point, w, h)
                devOp(alias, "自动登录(切号)：点 Resend code（第 $pass 次）tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) return true
            }
            return false
        }

        suspend fun inputCodeAndWaitRoute(code: String, tagPrefix: String): AutoLoginCutSwitchVerifyResult {
            val focusVision = autoLoginOcrOnce(ctx, alias, "${tagPrefix}_focus")
            autoLoginTapVerificationCodeField(ctx, alias, w, h, focusVision)
            autoLoginClearFocusedInput(alias)
            if (!autoLoginInputText(alias, code)) {
                return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.Failed)
            }
            if (!autoLoginDelay(alias, 400L, "验证码输入后")) {
                return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.Failed)
            }
            val transition = autoLoginWaitPageTransitionCutSwitch(
                ctx = ctx,
                alias = alias,
                h = h,
                phaseLog = "验证码提交后",
                tagPrefix = "${tagPrefix}_after_code",
                markerGone = { v -> !autoLoginVisionOnVerificationPage(v) },
                allowVerifySuccess = false,
            )
            return when (transition) {
                AutoLoginCutSwitchTransitionOutcome.ReachedInterest ->
                    AutoLoginCutSwitchVerifyResult(
                        AutoLoginCutSwitchStepOutcome.Ok,
                        reachedInterest = true,
                    )
                AutoLoginCutSwitchTransitionOutcome.ReachedHome ->
                    AutoLoginCutSwitchVerifyResult(
                        AutoLoginCutSwitchStepOutcome.Ok,
                        reachedHome = true,
                    )
                else -> AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.Failed)
            }
        }

        suspend fun recoverFromExpired(expiredCode: String): AutoLoginCutSwitchVerifyResult {
            devOp(alias, "自动登录(切号)：验证码 expired，清码并点 Resend（不返回）")
            autoLoginClearFocusedInput(alias)
            if (!ocrTapResendCode("cut_switch_expired_resend")) {
                return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.ChainRestart)
            }
            if (!autoLoginDelay(alias, AUTO_LOGIN_RESEND_WAIT_MS, "expired Resend 后")) {
                return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.Failed)
            }
            var freshCode: String? = null
            for (attempt in 1..AUTO_LOGIN_CUT_SWITCH_EXPIRED_CODE_RETRY_MAX) {
                val fetched = fetchCodeOrNull()
                when {
                    fetched == null ->
                        devOp(
                            alias,
                            "自动登录(切号)：expired 后拉码失败（$attempt/${AUTO_LOGIN_CUT_SWITCH_EXPIRED_CODE_RETRY_MAX}）",
                        )
                    fetched.equals(expiredCode, ignoreCase = true) ->
                        devOp(
                            alias,
                            "自动登录(切号)：新码与过期码相同，" +
                                "${AUTO_LOGIN_CUT_SWITCH_EXPIRED_CODE_RETRY_DELAY_MS / 1000}s 后重试" +
                                "（$attempt/${AUTO_LOGIN_CUT_SWITCH_EXPIRED_CODE_RETRY_MAX}）",
                        )
                    else -> {
                        freshCode = fetched
                        break
                    }
                }
                if (attempt < AUTO_LOGIN_CUT_SWITCH_EXPIRED_CODE_RETRY_MAX) {
                    if (!autoLoginDelay(alias, AUTO_LOGIN_CUT_SWITCH_EXPIRED_CODE_RETRY_DELAY_MS, "expired 等码")) {
                        return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.Failed)
                    }
                }
            }
            if (freshCode == null) {
                if (TikTokEmailCodeFetcher.consumeLastFetchUnrecoverableConfig()) {
                    devOp(alias, "自动登录(切号)：验证码配置不可恢复，停止整链重来")
                    return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.Failed)
                }
                devOp(alias, "自动登录(切号)：expired 后未拿到新验证码")
                return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.ChainRestart)
            }
            devOp(alias, "自动登录(切号)：expired 后已拿到新验证码")
            return inputCodeAndWaitRoute(freshCode, "cut_switch_expired_reinput")
        }

        val firstCode = fetchCodeOrNull()
        if (firstCode == null) {
            if (TikTokEmailCodeFetcher.consumeLastFetchUnrecoverableConfig()) {
                devOp(alias, "自动登录(切号)：验证码配置不可恢复，停止整链重来")
                return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.Failed)
            }
            devOp(alias, "自动登录(切号)：首次拉取验证码失败")
            return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.ChainRestart)
        }
        val firstResult = inputCodeAndWaitRoute(firstCode, "cut_switch_code")
        if (firstResult.step == AutoLoginCutSwitchStepOutcome.Ok) return firstResult

        val startedAt = System.currentTimeMillis()
        var ocrPass = 0
        while (System.currentTimeMillis() - startedAt < AUTO_LOGIN_CUT_SWITCH_PAGE_TRANSITION_MS) {
            if (hardStopOrNull(alias, "自动登录(切号)-验证码轮询")) {
                return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.Failed)
            }
            ocrPass++
            if (ocrPass > 1 && !autoLoginDelay(alias, 1000L, "验证码轮询")) {
                return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.Failed)
            }
            val vision = autoLoginOcrOnce(ctx, alias, "cut_switch_code_poll_$ocrPass") ?: continue
            if (findLabelHitContainingIgnoreCase(vision, "expired") != null) {
                return recoverFromExpired(firstCode)
            }
            when (autoLoginClassifyRouteScreen(vision, h)) {
                AutoLoginRouteScreenKind.Interest ->
                    return AutoLoginCutSwitchVerifyResult(
                        AutoLoginCutSwitchStepOutcome.Ok,
                        reachedInterest = true,
                    )
                AutoLoginRouteScreenKind.MainFeed ->
                    return AutoLoginCutSwitchVerifyResult(
                        AutoLoginCutSwitchStepOutcome.Ok,
                        reachedHome = true,
                    )
                AutoLoginRouteScreenKind.Unknown -> Unit
            }
        }
        devOp(alias, "自动登录(切号)：验证码提交后未进入兴趣页/Home")
        return AutoLoginCutSwitchVerifyResult(AutoLoginCutSwitchStepOutcome.ChainRestart)
    }

    private suspend fun autoLoginCutSwitchFinishLoginTail(
        ctx: Context,
        alias: String,
        w: Int,
        h: Int,
        reachedInterest: Boolean,
        reachedHome: Boolean,
        dontAllowHandledEarly: Boolean,
    ): Boolean {
        if (reachedInterest) {
            if (!autoLoginDelay(alias, 1200L, "兴趣页前等待")) return false
            autoLoginTryDismissAgreeAndThenDontAllow(ctx, alias, handleDontAllow = !dontAllowHandledEarly)
        } else if (reachedHome) {
            if (!autoLoginDelay(alias, 600L, "直达首页后")) return false
            autoLoginTryDismissAgreeAndThenDontAllow(ctx, alias, handleDontAllow = !dontAllowHandledEarly)
        }
        if (!reachedHome) {
            when (autoLoginHandleInterestPicker(ctx, alias, w, h)) {
                AutoLoginStepOutcome.Ok -> Unit
                else -> return false
            }
        }
        return autoLoginVerifySuccessByProfileAndEdit(ctx, alias, w, h)
    }

    /** 旧流：Email 已输入后 → Continue 等 verify → 验证码 → 布局 A/B 密码 → 终检（可能性 1 当前接入此链）。 */
    private suspend fun autoLoginRunLegacyPostEmailChain(
        ctx: Context,
        alias: String,
        deviceId: String,
        w: Int,
        h: Int,
        email: String,
        password: String,
        apiUrl: String,
        dontAllowHandledEarly: Boolean,
    ): AutoLoginCutSwitchStepOutcome {
        if (!autoLoginDelay(alias, 800L, "验证码分支探测")) return AutoLoginCutSwitchStepOutcome.Failed
        when (
            autoLoginTapContinueAndWaitForVerifyEmail(
                ctx,
                alias,
                w,
                h,
                "auto_login_continue_email",
            )
        ) {
            AutoLoginEmailAfterContinueOutcome.VerifyReady -> Unit
            AutoLoginEmailAfterContinueOutcome.CheckYourInbox ->
                return autoLoginRunCheckYourInboxPostEmailTail(
                    ctx = ctx,
                    alias = alias,
                    deviceId = deviceId,
                    w = w,
                    h = h,
                    password = password,
                    apiUrl = apiUrl,
                    email = email,
                    dontAllowHandledEarly = dontAllowHandledEarly,
                )
            AutoLoginEmailAfterContinueOutcome.Failed -> {
                devOp(alias, "自动登录：Email→Continue 跳转失败（3 分钟内仍未识别 Verify 文案，或仍可见 Facebook）")
                return AutoLoginCutSwitchStepOutcome.Failed
            }
        }

        if (!autoLoginDelay(alias, 800L, "验证码分支探测")) return AutoLoginCutSwitchStepOutcome.Failed
        val verificationPageReady = autoLoginOcrConfirmVerificationPageAndWaitResendCode(ctx, alias, w, h)
        if (!verificationPageReady) {
            devOp(alias, "自动登录：验证码页未确认或未定位 Resend code，整链重来")
            return AutoLoginCutSwitchStepOutcome.ChainRestart
        }

        if (apiUrl.isBlank() && email.isBlank()) {
            devOp(alias, "自动登录：需要验证码但 API 与邮箱均为空")
            return AutoLoginCutSwitchStepOutcome.Failed
        }
        var reachedInterestAfterCode = false
        var reachedHomeAfterCode = false
        when (autoLoginEnterVerificationCode(ctx, alias, w, h, deviceId, apiUrl, email)) {
            AutoLoginStepOutcome.Ok -> Unit
            AutoLoginStepOutcome.ReachedInterest -> reachedInterestAfterCode = true
            AutoLoginStepOutcome.ReachedHome -> reachedHomeAfterCode = true
            AutoLoginStepOutcome.ChainRestart -> {
                devOp(alias, "自动登录：验证码阶段失败，整链重来")
                return AutoLoginCutSwitchStepOutcome.ChainRestart
            }
            AutoLoginStepOutcome.Failed -> return AutoLoginCutSwitchStepOutcome.Failed
        }

        if (!reachedInterestAfterCode && !reachedHomeAfterCode) {
            if (!autoLoginDelay(alias, 600L, "Password 页")) return AutoLoginCutSwitchStepOutcome.Failed
            var enteredPasswordViaEnterPasswordLayout = false
            var passwordContinueDone = false

            for (pass in 1..AUTO_LOGIN_OCR_MAX_PASSES) {
                val vision = autoLoginOcrOnce(ctx, alias, "auto_login_password_next_layout_$pass") ?: continue
                autoLoginLogPasswordLayoutProbe(alias, pass, AUTO_LOGIN_OCR_MAX_PASSES, "布局A探测", vision)
                val hits = findSinglePasswordAndNextHits(vision)
                if (hits == null) {
                    if (pass < AUTO_LOGIN_OCR_MAX_PASSES) {
                        autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "Password+Next 布局定位")
                    }
                    continue
                }
                val (passwordHit, nextHit) = hits
                val pwTap = autoLoginTapWithJitter(passwordHit.point, w, h)
                devOp(alias, "自动登录：识别 password+next 布局 A，先点 password (${pwTap.x},${pwTap.y})")
                runSuCommand("input tap ${pwTap.x} ${pwTap.y}")
                if (!autoLoginDelay(alias, 400L, "password+next-点password后")) return AutoLoginCutSwitchStepOutcome.Failed

                val nextTap = autoLoginTapWithJitter(nextHit.point, w, h)
                devOp(alias, "自动登录：识别 password+next 布局 A，再点 next (${nextTap.x},${nextTap.y})")
                runSuCommand("input tap ${nextTap.x} ${nextTap.y}")
                if (!autoLoginDelay(alias, 800L, "password+next-点next后")) return AutoLoginCutSwitchStepOutcome.Failed

                var threePasswordsReady = false
                for (verifyPass in 1..AUTO_LOGIN_OCR_MAX_PASSES) {
                    val verifyVision = autoLoginOcrOnce(ctx, alias, "auto_login_password_next_verify_$verifyPass") ?: continue
                    autoLoginLogPasswordLayoutProbe(
                        alias, verifyPass, AUTO_LOGIN_OCR_MAX_PASSES, "布局A校验(点Next后)", verifyVision,
                    )
                    val passwordCount = countLabelContainingIgnoreCase(verifyVision, "password")
                    if (passwordCount >= 3) {
                        devOp(alias, "自动登录：已识别 password 命中数=$passwordCount，判定布局 A 跳转成功")
                        threePasswordsReady = true
                        break
                    }
                    if (verifyPass < AUTO_LOGIN_OCR_MAX_PASSES) {
                        autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "password+next 校验")
                    }
                }
                if (!threePasswordsReady) continue
                if (!autoLoginInputText(alias, password)) return AutoLoginCutSwitchStepOutcome.Failed
                enteredPasswordViaEnterPasswordLayout = true
                break
            }

            if (enteredPasswordViaEnterPasswordLayout) {
                val continueOrNext = autoLoginOcrTapContinueOrNext(
                    ctx, alias, w, h,
                    "auto_login_continue_or_next_pwd_enter_layout",
                    "Continue/Next(EnterPassword页)",
                    maxPasses = 3,
                )
                if (continueOrNext != AutoLoginStepOutcome.Ok) {
                    devOp(alias, "自动登录：EnterPassword 页 Continue/Next 3 次均未命中")
                    return AutoLoginCutSwitchStepOutcome.Failed
                }
                passwordContinueDone = true
            } else {
                devOp(alias, "自动登录：布局A ${AUTO_LOGIN_OCR_MAX_PASSES} 次均未满足，转布局B")
                var passwordFieldReady = false
                for (pass in 1..AUTO_LOGIN_OCR_MAX_PASSES) {
                    val vision = autoLoginOcrOnce(ctx, alias, "auto_login_password_$pass") ?: continue
                    autoLoginLogPasswordLayoutProbe(alias, pass, AUTO_LOGIN_OCR_MAX_PASSES, "布局B定位", vision)
                    val field = findMiddlePasswordFieldHit(vision)
                    if (field != null) {
                        val tap = autoLoginTapWithJitter(field.point, w, h)
                        runSuCommand("input tap ${tap.x} ${tap.y}")
                        passwordFieldReady = true
                        break
                    }
                    if (pass < AUTO_LOGIN_OCR_MAX_PASSES) {
                        autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "Password 定位")
                    }
                }
                if (!passwordFieldReady) {
                    devOp(alias, "自动登录：Password 输入区未定位")
                    return AutoLoginCutSwitchStepOutcome.Failed
                }
                if (!autoLoginInputText(alias, password)) return AutoLoginCutSwitchStepOutcome.Failed

                var trustTapped = false
                for (trustPass in 1..2) {
                    val vision = autoLoginOcrOnce(ctx, alias, "auto_login_trust_$trustPass") ?: continue
                    findLabelHitContainingIgnoreCase(vision, "trust this device")?.let { hit ->
                        val tap = autoLoginTapWithJitter(hit.point, w, h)
                        runSuCommand("input tap ${tap.x} ${tap.y}")
                        trustTapped = true
                        devOp(alias, "自动登录：已点 trust this device")
                    }
                    if (trustTapped || trustPass == 2) break
                    autoLoginDelay(alias, AUTO_LOGIN_OCR_RETRY_DELAY_MS, "trust 补 OCR")
                }

                val continuePwd = autoLoginOcrTapByFinder(
                    ctx, alias, w, h, "auto_login_continue_pwd", "Continue(密码页)",
                    findHit = { v ->
                        findStrictExactLabelHit(v, "Continue")
                            ?: findStrictExactLabelHit(v, "continue")
                    },
                )
                if (continuePwd != AutoLoginStepOutcome.Ok) {
                    devOp(alias, "自动登录：密码页 Continue 未命中")
                    return AutoLoginCutSwitchStepOutcome.Failed
                }
                passwordContinueDone = true
            }

            if (passwordContinueDone) {
                when (autoLoginPollPostPasswordRoute(ctx, alias, h)) {
                    AutoLoginStepOutcome.ReachedHome -> reachedHomeAfterCode = true
                    AutoLoginStepOutcome.ReachedInterest -> reachedInterestAfterCode = true
                    else -> {
                        devOp(alias, "自动登录：密码后路由探测失败")
                        return AutoLoginCutSwitchStepOutcome.Failed
                    }
                }
            }
        } else if (reachedInterestAfterCode) {
            if (!autoLoginDelay(alias, 1200L, "兴趣页前等待")) return AutoLoginCutSwitchStepOutcome.Failed
            autoLoginTryDismissAgreeAndThenDontAllow(ctx, alias, handleDontAllow = !dontAllowHandledEarly)
        } else {
            devOp(alias, "自动登录：已直达 Home/Profile，跳过密码与兴趣页")
            if (!autoLoginDelay(alias, 600L, "直达首页后")) return AutoLoginCutSwitchStepOutcome.Failed
            autoLoginTryDismissAgreeAndThenDontAllow(ctx, alias, handleDontAllow = !dontAllowHandledEarly)
        }

        if (!reachedHomeAfterCode) {
            when (autoLoginHandleInterestPicker(ctx, alias, w, h)) {
                AutoLoginStepOutcome.Ok -> Unit
                else -> return AutoLoginCutSwitchStepOutcome.Failed
            }
        }

        return if (autoLoginVerifySuccessByProfileAndEdit(ctx, alias, w, h)) {
            devOp(alias, "自动登录：全流程完成（Profile→Edit 终检通过）")
            AutoLoginCutSwitchStepOutcome.Ok
        } else {
            devOp(alias, "自动登录：Profile→Edit 终检失败，整链重来")
            AutoLoginCutSwitchStepOutcome.ChainRestart
        }
    }

    private suspend fun autoLoginRunCheckYourInboxPostEmailTail(
        ctx: Context,
        alias: String,
        deviceId: String,
        w: Int,
        h: Int,
        password: String,
        apiUrl: String,
        email: String,
        dontAllowHandledEarly: Boolean,
    ): AutoLoginCutSwitchStepOutcome {
        val reachedInterest = booleanArrayOf(false)
        val reachedHome = booleanArrayOf(false)
        val needVerify = booleanArrayOf(false)

        if (!autoLoginCutSwitchInboxPasswordPath(ctx, alias, w, h, password)) {
            return AutoLoginCutSwitchStepOutcome.Failed
        }
        val transition = autoLoginWaitPageTransitionCutSwitch(
            ctx = ctx,
            alias = alias,
            h = h,
            phaseLog = "Inbox密码后",
            tagPrefix = "cut_switch_inbox_pwd_transition",
            markerGone = { v -> !autoLoginVisionHasContinueMarker(v) },
            allowVerifySuccess = true,
        )
        when (
            autoLoginCutSwitchApplyTransitionOutcome(
                alias, transition, "Inbox密码后", reachedInterest, reachedHome, needVerify,
            )
        ) {
            AutoLoginCutSwitchStepOutcome.Ok -> Unit
            else -> return AutoLoginCutSwitchStepOutcome.Failed
        }

        if (!reachedInterest[0] && !reachedHome[0] && needVerify[0]) {
            if (!autoLoginOcrConfirmVerificationPageAndWaitResendCode(ctx, alias, w, h)) {
                return AutoLoginCutSwitchStepOutcome.ChainRestart
            }
            if (apiUrl.isBlank() && email.isBlank()) {
                devOp(alias, "自动登录(Email后 Inbox)：需要验证码但 API 与邮箱均为空")
                return AutoLoginCutSwitchStepOutcome.Failed
            }
            val verifyResult = autoLoginEnterVerificationCodeCutSwitch(ctx, alias, w, h, deviceId, apiUrl, email)
            when (verifyResult.step) {
                AutoLoginCutSwitchStepOutcome.Ok -> {
                    reachedInterest[0] = reachedInterest[0] || verifyResult.reachedInterest
                    reachedHome[0] = reachedHome[0] || verifyResult.reachedHome
                }
                AutoLoginCutSwitchStepOutcome.ChainRestart -> return AutoLoginCutSwitchStepOutcome.ChainRestart
                AutoLoginCutSwitchStepOutcome.Failed -> return AutoLoginCutSwitchStepOutcome.Failed
            }
        }

        if (!reachedInterest[0] && !reachedHome[0]) {
            devOp(alias, "自动登录(Email后 Inbox)：未完成登录短路径（兴趣页/Home）")
            return AutoLoginCutSwitchStepOutcome.Failed
        }

        return if (
            autoLoginCutSwitchFinishLoginTail(
                ctx, alias, w, h, reachedInterest[0], reachedHome[0], dontAllowHandledEarly,
            )
        ) {
            AutoLoginCutSwitchStepOutcome.Ok
        } else {
            AutoLoginCutSwitchStepOutcome.Failed
        }
    }

    private suspend fun runAutoLoginCutSwitchPostEmail(
        ctx: Context,
        alias: String,
        deviceId: String,
        w: Int,
        h: Int,
        email: String,
        password: String,
        apiUrl: String,
        dontAllowHandledEarly: Boolean,
    ): AutoLoginCutSwitchStepOutcome {
        if (!autoLoginDelay(alias, 800L, "分流-Email分流前")) return AutoLoginCutSwitchStepOutcome.Failed
        val branch = autoLoginSubmitEmailAndDetectBranchCutSwitch(
            ctx, alias, w, h, "cut_switch_email_branch",
        ) ?: return AutoLoginCutSwitchStepOutcome.Failed

        val reachedInterest = booleanArrayOf(false)
        val reachedHome = booleanArrayOf(false)
        val needVerify = booleanArrayOf(false)

        when (branch) {
            AutoLoginCutSwitchPostEmailBranch.ShortPathInterest -> reachedInterest[0] = true
            AutoLoginCutSwitchPostEmailBranch.ShortPathHome -> reachedHome[0] = true
            AutoLoginCutSwitchPostEmailBranch.CheckYourInbox -> {
                return autoLoginRunCheckYourInboxPostEmailTail(
                    ctx = ctx,
                    alias = alias,
                    deviceId = deviceId,
                    w = w,
                    h = h,
                    password = password,
                    apiUrl = apiUrl,
                    email = email,
                    dontAllowHandledEarly = dontAllowHandledEarly,
                )
            }
            /*
             * 可能性 1（新）：Email 后直接布局 A/B 密码页 — 暂注释，后续启用。
            AutoLoginCutSwitchPostEmailBranch.DirectPassword -> {
                if (!autoLoginCutSwitchEnterPasswordLayoutAB(
                        ctx, alias, w, h, password, "cut_switch_direct_pwd",
                    )
                ) {
                    return AutoLoginCutSwitchStepOutcome.Failed
                }
                val transition = autoLoginWaitPageTransitionCutSwitch(
                    ctx = ctx,
                    alias = alias,
                    h = h,
                    phaseLog = "直接密码后",
                    tagPrefix = "cut_switch_direct_pwd_transition",
                    markerGone = { v ->
                        !autoLoginVisionHasContinueMarker(v) &&
                            findSinglePasswordAndNextHits(v) == null &&
                            findAllPasswordLabelHits(v).isEmpty()
                    },
                    allowVerifySuccess = true,
                )
                when (
                    autoLoginCutSwitchApplyTransitionOutcome(
                        alias, transition, "直接密码后", reachedInterest, reachedHome, needVerify,
                    )
                ) {
                    AutoLoginCutSwitchStepOutcome.Ok -> Unit
                    else -> return AutoLoginCutSwitchStepOutcome.Failed
                }
            }
             */
            AutoLoginCutSwitchPostEmailBranch.VerifyPage,
            AutoLoginCutSwitchPostEmailBranch.DirectPassword,
            AutoLoginCutSwitchPostEmailBranch.StillOnEmail,
            AutoLoginCutSwitchPostEmailBranch.Unknown,
            -> {
                devOp(
                    alias,
                    "自动登录(分流)：未见 Check your inbox（分支=$branch），可能性1 接入旧流（verify→验证码→密码）",
                )
                return autoLoginRunLegacyPostEmailChain(
                    ctx = ctx,
                    alias = alias,
                    deviceId = deviceId,
                    w = w,
                    h = h,
                    email = email,
                    password = password,
                    apiUrl = apiUrl,
                    dontAllowHandledEarly = dontAllowHandledEarly,
                )
            }
        }

        if (!reachedInterest[0] && !reachedHome[0] && needVerify[0]) {
            if (!autoLoginOcrConfirmVerificationPageAndWaitResendCode(ctx, alias, w, h)) {
                return AutoLoginCutSwitchStepOutcome.ChainRestart
            }
            if (apiUrl.isBlank() && email.isBlank()) {
                devOp(alias, "自动登录(切号)：需要验证码但 API 与邮箱均为空")
                return AutoLoginCutSwitchStepOutcome.Failed
            }
            val verifyResult = autoLoginEnterVerificationCodeCutSwitch(ctx, alias, w, h, deviceId, apiUrl, email)
            when (verifyResult.step) {
                AutoLoginCutSwitchStepOutcome.Ok -> {
                    reachedInterest[0] = reachedInterest[0] || verifyResult.reachedInterest
                    reachedHome[0] = reachedHome[0] || verifyResult.reachedHome
                }
                AutoLoginCutSwitchStepOutcome.ChainRestart -> return AutoLoginCutSwitchStepOutcome.ChainRestart
                AutoLoginCutSwitchStepOutcome.Failed -> return AutoLoginCutSwitchStepOutcome.Failed
            }
        }

        if (!reachedInterest[0] && !reachedHome[0]) {
            devOp(alias, "自动登录(切号)：未完成登录短路径（兴趣页/Home）")
            return AutoLoginCutSwitchStepOutcome.Failed
        }

        return if (
            autoLoginCutSwitchFinishLoginTail(
                ctx, alias, w, h, reachedInterest[0], reachedHome[0], dontAllowHandledEarly,
            )
        ) {
            AutoLoginCutSwitchStepOutcome.Ok
        } else {
            AutoLoginCutSwitchStepOutcome.Failed
        }
    }

    override suspend fun runAutoLoginAfterCutSwitch(
        ctx: Context,
        credentials: TikTokAutoLoginCredentials,
        resumeFromLoginEntryScreen: Boolean,
        flowKind: AutoLoginFlow,
    ): Boolean {
        val alias = PrefsStore.getAlias()?.trim()?.takeIf { it.isNotEmpty() } ?: "auto_login"
        val deviceId = PrefsStore.getDeviceId().orEmpty()
        val email = credentials.email.trim()
        val password = credentials.password.trim()
        val apiUrl = credentials.apiUrl.trim()
        if (email.isBlank() || password.isBlank()) {
            devOp(alias, "自动登录：Email 或 Password 为空，跳过")
            return false
        }

        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)

        val flowLabel = when (flowKind) {
            AutoLoginFlow.CutSwitchAfterRestore -> "未登录分流"
            AutoLoginFlow.Default -> "默认"
        }
        devOp(
            alias,
            "自动登录：开始（账号=${credentials.username}，流程=$flowLabel，整链重来最多 $AUTO_LOGIN_MAX_CHAIN_RESTARTS 轮）",
        )

        for (chain in 1..AUTO_LOGIN_MAX_CHAIN_RESTARTS) {
            if (hardStopOrNull(alias, "自动登录")) return false
            try {
            autoLoginDontAllowHandledInThisChain = false
            var dontAllowHandledEarly = false
            val skipColdBootstrap = resumeFromLoginEntryScreen && chain == 1
            if (chain > 1) {
                if (!autoLoginRestartTikTok(ctx, alias)) return false
            } else if (skipColdBootstrap) {
                devOp(alias, "自动登录：登录状态保障已打开 Profile 登录入口，跳过冷启/预处理，从入口解析接入")
                if (!ensureTikTokInForeground(ctx, alias, "自动登录-保障续接")) return false
                dismissDontAllowAfterTikTokLaunch(ctx, alias, "自动登录-保障续接")
            } else {
                if (!launchTikTokOnceWithCheck(
                        ctx,
                        alias,
                        "自动登录",
                        launchWaitMs,
                        forceStopBeforeLaunch = true,
                    )
                ) {
                    return false
                }
            }

            if (!skipColdBootstrap) {
                // 打开 TikTok 之后的预处理链：agree and → Don't allow → 上滑两次 → 点 Profile 进登录入口
                dontAllowHandledEarly = runCatching {
                    autoLoginPreprocessDismissAgreeAndDontAllow(ctx, alias, maxPasses = 3, retryDelayMs = 1000L)
                }.getOrDefault(false)
                if (dontAllowHandledEarly) {
                    autoLoginDontAllowHandledInThisChain = true
                }
                if (!autoLoginSwipeUpTwice(ctx, alias, w, h)) return false
                runCatching {
                    autoLoginTapProfileForLoginEntry(ctx, alias)
                }
            }

            val entryPath = autoLoginResolveEntryPath(ctx, alias, w, h)
            when (entryPath) {
                AutoLoginEntryPath.AlreadyLoggedIn -> {
                    devOp(alias, "自动登录：入口页已识别 Edit，跳过登录表单，判定登录成功")
                    return true
                }
                AutoLoginEntryPath.None -> {
                    if (chain < AUTO_LOGIN_MAX_CHAIN_RESTARTS) {
                        devOp(alias, "自动登录：入口未就绪（底栏 Log in 或 Sign up），整链重来")
                        continue
                    }
                    devOp(alias, "自动登录：入口未就绪且已达整链重试上限")
                    return false
                }
                AutoLoginEntryPath.ViaLogIn,
                AutoLoginEntryPath.ViaAddAnotherAccount,
                AutoLoginEntryPath.ViaSignUp,
                -> {
                    repeat(2) { round ->
                        val uOutcome = autoLoginOcrTapByFinder(
                            ctx, alias, w, h, "auto_login_username_${chain}_$round", "Username",
                            findHit = { v -> findAutoLoginUsernameTapHit(v) },
                        )
                        if (uOutcome != AutoLoginStepOutcome.Ok) {
                            devOp(alias, "自动登录：Username 第 ${round + 1}/2 次未命中")
                            return false
                        }
                    }
                }
            }

            if (!autoLoginDelay(alias, 500L, "Email 输入前")) return false
            if (!autoLoginVerifyEmailFormReadyBeforeInput(ctx, alias)) {
                if (chain < AUTO_LOGIN_MAX_CHAIN_RESTARTS) {
                    devOp(alias, "自动登录：Email 输入前未识别 address/username，页面错乱，整链重来")
                    continue
                }
                devOp(alias, "自动登录：Email 输入前未识别 address/username 且已达整链重试上限")
                return false
            }
            if (!autoLoginInputText(alias, email)) return false
            if (!autoLoginDelay(alias, 400L, "Email 输入后")) return false

            when (
                runAutoLoginCutSwitchPostEmail(
                    ctx = ctx,
                    alias = alias,
                    deviceId = deviceId,
                    w = w,
                    h = h,
                    email = email,
                    password = password,
                    apiUrl = apiUrl,
                    dontAllowHandledEarly = dontAllowHandledEarly,
                )
            ) {
                AutoLoginCutSwitchStepOutcome.Ok -> {
                    devOp(alias, "自动登录(Email后分流)：全流程完成（Profile→Edit 终检通过）")
                    return true
                }
                AutoLoginCutSwitchStepOutcome.ChainRestart -> {
                    devOp(alias, "自动登录(Email后分流)：阶段失败，整链重来")
                    continue
                }
                AutoLoginCutSwitchStepOutcome.Failed -> return false
            }
            } catch (logout: AccountStatusLoggedOutException) {
                devOp(alias, "自动登录：${logout.message ?: "掉登录"}，整链重来")
                if (chain >= AUTO_LOGIN_MAX_CHAIN_RESTARTS) {
                    devOp(alias, "自动登录：掉登录后已达整链重试上限")
                    return false
                }
                continue
            }
        }
        devOp(alias, "自动登录：整链重试耗尽")
        return false
    }

}
