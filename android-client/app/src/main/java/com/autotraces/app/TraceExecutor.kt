package com.autotraces.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.autotraces.app.util.Config
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
)

interface TraceExecutor {
    suspend fun executeForUsername(username: String, config: AppConfig): String
    suspend fun restartTikTokForRecovery(): Boolean = false

    /**
     * 远控等路径：**拉起 TikTok 且经 dumpsys 校验包名为当前前台**（与主流程一致）。
     * 拉起命令执行后会统一整屏 OCR「Don't allow」（至多 3 次、间隔 0.5s），命中则点击，未命中跳过。
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
     * 整屏「Upload photo」→「Allow all」（Allow 未命中仅跳过**该按钮**，其余选图发帖步骤照常）→
     * 左上固定点→OCR「Next」×2（间隔 0.5s）→「Post this photo to Story」→「Save」→
     * 等 2s 后最多 7 次 OCR 校验「Change photo」→Name→Bio(模板)→
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

    private fun isMlKitHardStopError(err: String): Boolean = err == "hard_stop"

    /** 发作品步骤失败收尾：硬停不重启；否则可选重启 TikTok。恒返回 false。 */
    private suspend fun publishPostFail(alias: String, phase: String, tryRestart: Boolean): Boolean {
        if (hardStopOrNull(alias, phase)) return false
        if (tryRestart) restartTikTokForRecovery()
        return false
    }

    /** 可中断等待；硬停时返回 false（便于 OCR 循环尽快退出）。 */
    private suspend fun profileEditDelay(logAlias: String, ms: Long, phase: String): Boolean {
        var left = ms
        while (left > 0) {
            if (hardStopOrNull(logAlias, "修改基本资料-$phase")) return false
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
        if (hardStopOrNull(alias, action)) return null
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
    // 仅 growth：发生恢复重启后，下一轮从“点击放大镜”重新开始（不复用 Users，不再二次拉起 App）。
    private var restartFromMagnifierForGrowth: Boolean = false

    /** 完整互点：粉丝列表 Follow 累计计数（本会话）。 */
    private var mutualSessionFollowTapCount: Int = 0

    @Volatile
    private var mutualFollowLimitReached: Boolean = false

    /** 单次发作品任务内：资料页进图库入口布局（首条试探成功后后续条目不重复 Recents 试探）。 */
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

    companion object {
        private const val FRAME_DIFF_PASS_THRESHOLD = 0.25f
        /** 帧差重试间隔（毫秒），临时固定 5s */
        private const val FRAME_DIFF_RETRY_DELAY_MS = 5000L
        /** Videos 区连续帧差失败时，teamwork 全量恢复的最大轮数（避免死循环） */
        private const val MUTUAL_VIDEOS_FRAME_DIFF_MAX_RECOVERY_CYCLES = 8
        /** 完整互点：未达 Follow 上限时 teamwork Videos 外圈最大轮数（每轮含左+右双侧），防止死循环。 */
        private const val MUTUAL_FULL_VIDEOS_SAFETY_ROUNDS = 500
        /** 单次「发布作品」任务最多连发条数（临时上限）；实际条数 = min(Pictures 图片+视频数, 本常量)。 */
        private const val PUBLISH_POST_MAX_ITEMS_PER_TASK = 5
        /** 上一条发布成功后切下一条前短等（不冷启 TikTok；界面已在底栏主界面）。 */
        private const val PUBLISH_POST_INTER_ITEM_DELAY_MS = 650L
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

        /**
         * 测试 Bio 后② 时 true：跳过 Edit→Bio→Bio后①，外轮比例点后须已在 TikTok 个人主页。
         * 正常全链须为 false。
         */
        private const val PROFILE_EDIT_TEST_PART2_ONLY = false

        /** Bio 后②：设置齿轮比例点（相对屏宽/高）。 */
        private const val PROFILE_EDIT_SETTINGS_GEAR_RATIO_X = 0.96f
        private const val PROFILE_EDIT_SETTINGS_GEAR_RATIO_Y = 0.07f

        /** 设置齿轮点击：相对基准点固定右下偏移（实测 1080×1920 成功点为基准+(12,9)）。 */
        private const val PROFILE_EDIT_SETTINGS_GEAR_OFFSET_X_PX = 12
        private const val PROFILE_EDIT_SETTINGS_GEAR_OFFSET_Y_PX = 9

        /** 拉起 TikTok 后统一 OCR「Don't allow」：识别轮数与相邻间隔（毫秒）。 */
        private const val TIKTOK_LAUNCH_DONT_ALLOW_OCR_MAX_PASSES = 3
        private const val TIKTOK_LAUNCH_DONT_ALLOW_OCR_RETRY_DELAY_MS = 500L

        /** 每次点击设置比例点后，OCR 探 Privacy（文案含 privacy）最多次数。 */
        private const val PROFILE_EDIT_SETTINGS_PRIVACY_OCR_PER_TAP = 2

        /** 未见 Privacy 时最多复点次数（固定同坐标复点）。 */
        private const val PROFILE_EDIT_SETTINGS_GEAR_RETAP_MAX = 6

        /** 修改基本资料：Upload photo 后整屏 OCR「Allow all」最多次数（未命中仅跳过）。 */
        private const val PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES = 3

        /** 发作品图库统计根路径（与 adb `find /storage/emulated/0/Pictures` 一致）。 */
        private const val PUBLISH_POST_PICTURES_PATH = "/storage/emulated/0/Pictures"

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

    /**
     * 拉起 TikTok：启动命令 → 等待 [launchWaitMs] → dumpsys 前台校验 →
     * **仅在前台已确认时** OCR 处理 Don't allow（非 TikTok 前台时 OCR 无意义）。
     */
    private suspend fun launchTikTokOnceWithCheck(
        ctx: Context,
        alias: String,
        tag: String,
        launchWaitMs: Long,
        forceStopBeforeLaunch: Boolean = true,
    ): Boolean {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val forceStopWaitMs = lastConfigSnapshot.tiktok_force_stop_wait_ms.coerceIn(100L, 8000L)
        if (forceStopBeforeLaunch) {
            runSuCommand("am force-stop $pkg")
            delay(forceStopWaitMs)
        }

        var didLaunchCommand = false
        val intent = buildTikTokLaunchIntent(ctx)
        if (intent != null) {
            try {
                ctx.startActivity(intent)
                didLaunchCommand = true
            } catch (_: Exception) {
                // fall through to root launch
            }
        }
        if (!didLaunchCommand) {
            didLaunchCommand = runTikTokRootLaunchCommands()
        }
        if (!didLaunchCommand) {
            devOp(alias, "$tag：拉起 TikTok 失败（启动命令均未成功）")
            return false
        }

        devOp(alias, "$tag：启动命令已执行，等待 ${launchWaitMs}ms（tiktok_launch_wait_ms）后进行前台校验")
        delay(launchWaitMs)

        var confirmed = isPackageVisible(pkg)
        if (!confirmed) {
            devOp(alias, "$tag：配置等待后前台校验未通过，再等 1200ms 重试 dumpsys")
            delay(1200)
            confirmed = isPackageVisible(pkg)
        }
        if (!confirmed) {
            devOp(alias, "$tag：拉起 TikTok 失败（配置等待后前台校验未通过，跳过 Don't allow OCR）")
            return false
        }

        val dismissedDontAllow = dismissDontAllowAfterTikTokLaunch(ctx, alias, tag)
        delay(if (dismissedDontAllow) 600L else 350L)

        when {
            dismissedDontAllow ->
                devOp(alias, "$tag：拉起 TikTok 成功（权限弹窗已处理）")
            else ->
                devOp(alias, "$tag：拉起 TikTok 成功")
        }
        return true
    }

    /** 拉起 TikTok 命令已执行后：统一 OCR「Don't allow」并点击（至多 3 次、间隔 0.5s）。 */
    private suspend fun dismissDontAllowAfterTikTokLaunch(
        ctx: Context,
        alias: String,
        tag: String,
    ): Boolean = tryDismissDontAllowOcr(
        ctx = ctx,
        alias = alias,
        phaseLog = "拉起后 Don't allow",
        logSuffix = tag.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "launch" },
        maxPasses = TIKTOK_LAUNCH_DONT_ALLOW_OCR_MAX_PASSES,
        retryDelayMs = TIKTOK_LAUNCH_DONT_ALLOW_OCR_RETRY_DELAY_MS,
        logPrefix = "",
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

    override suspend fun restartTikTokForRecovery(): Boolean {
        val ctx: Context = appContextProvider()
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "重启 TikTok 恢复")) return false
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val forceStopWaitMs = lastConfigSnapshot.tiktok_force_stop_wait_ms.coerceIn(100L, 8000L)
        val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
        didRestartTikTokInCurrentRun = true
        val t0 = System.currentTimeMillis()

        suspend fun launchOnce(tag: String): Boolean {
            val ok = launchTikTokOnceWithCheck(ctx, alias, tag, launchWaitMs, forceStopBeforeLaunch = false)
            if (ok) {
                devOp(alias, "$tag：重新打开 TikTok", System.currentTimeMillis() - t0)
            }
            return ok
        }

        runSuCommand("am force-stop $pkg")
        delay(forceStopWaitMs)

        val firstOk = launchOnce("首次启动")
        if (firstOk) {
            devOp(alias, "重启 TikTok 成功", System.currentTimeMillis() - t0)
            return true
        }

        delay(350)
        if (isPackageVisible(pkg)) {
            devOp(alias, "重启 TikTok 成功（短等后 dumpsys 已为前台）", System.currentTimeMillis() - t0)
            return true
        }

        runSuCommand("am force-stop $pkg")
        delay(forceStopWaitMs)
        val secondOk = launchOnce("二次启动")
        devOp(alias, "重启 TikTok ${if (secondOk) "成功" else "失败"}", System.currentTimeMillis() - t0)
        return secondOk
    }

    private data class StrictLabelHit(val point: Point, val rect: Rect)

    /** 修改基本资料专用：把 OCR 命中的文案与 bbox、点击中心写入设备日志（经 LogCenter 同步云端）。 */
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

    /** 同一屏内独立「Change photo」「Name」/「name」「Bio」三词坐标（仅记录）。 */
    private fun findProfileEditChangePhotoNameBioTriple(result: Text): ProfileEditMenuTriple? {
        val cp = findStrictExactLabelHit(result, "Change photo") ?: return null
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

    /**
     * 整屏截屏 + ML Kit OCR；失败时返回 null 与简短原因（不入库，仅用于 [ProfileEditChainResult]）。
     */
    private suspend fun mlKitRecognizeFullScreen(ctx: Context, fileTag: String): Pair<Text?, String> {
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
        return try {
            val result = suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            result to "ok"
        } catch (ex: Exception) {
            null to "mlkit:${ex.javaClass.simpleName}:${ex.message?.take(120)}"
        } finally {
            recognizer.close()
            bitmap.recycle()
            screenshotFile.delete()
        }
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
            val pv = buildProfileEditOcrPreview(vision).take(480)
            devOp(logAlias, "「修改基本资料」$phaseLog 第 $pass/$maxPasses 次：行摘要=$pv")
            val hit = findHit(vision)
            if (hit == null) {
                devOp(logAlias, "「修改基本资料」$phaseLog 第 $pass/$maxPasses 次：未命中")
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

    /**
     * 整屏 OCR 至多 [maxTries] 次，直到出现严格「Change photo」。
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
            val pv = buildProfileEditOcrPreview(vision).take(420)
            devOp(logAlias, "「修改基本资料」$humanPhase 第 $attempts/$maxTries 次：摘要=$pv")
            if (findStrictExactLabelHit(vision, "Change photo") != null) {
                devOp(logAlias, "「修改基本资料」$humanPhase：已定位「Change photo」（$attempts/$maxTries）")
                return true to attempts
            }
            devOp(logAlias, "「修改基本资料」$humanPhase 第 $attempts/$maxTries 次：未见「Change photo」")
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

    /** For You 刷下一条：与 [runForYouWarmupIfNeeded] 同向（手指上滑）。 */
    private suspend fun profileEditForYouFeedSwipeOnce(logAlias: String, w: Int, h: Int) {
        val swipeX = (w * 0.5f).toInt().coerceIn(1, (w - 1).coerceAtLeast(1))
        val startY = (h * 0.78f).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        val endY = (h * 0.32f).toInt().coerceIn(1, (h - 1).coerceAtLeast(1))
        val swipeDuration = Random.nextInt(260, 421)
        devOp(logAlias, "「修改基本资料」For You 下滑：swipe ($swipeX,$startY)→($swipeX,$endY) ${swipeDuration}ms")
        runSuCommand("input swipe $swipeX $startY $swipeX $endY $swipeDuration")
    }

    override suspend fun profileEditForYouTwoSwipesAfterLaunch(logAlias: String, w: Int, h: Int): Boolean {
        if (hardStopOrNull(logAlias, "修改基本资料-For You预刷")) return false
        devOp(logAlias, "「修改基本资料」For You：假定已在推荐页，刷两下视频（无点赞）")
        repeat(2) { pass ->
            if (hardStopOrNull(logAlias, "修改基本资料-For You预刷")) return false
            profileEditForYouFeedSwipeOnce(logAlias, w, h)
            if (!profileEditDelay(logAlias, 450, "For You下滑${pass + 1}后")) return false
        }
        return true
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
            val pv = buildProfileEditOcrPreview(vision).take(480)
            devOp(logAlias, "「修改基本资料」$phaseLog 第 $pass/$maxPasses 次：行摘要=$pv")
            val hit = findHit(vision)
            if (hit == null) {
                devOp(logAlias, "「修改基本资料」$phaseLog 第 $pass/$maxPasses 次：未命中")
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
                val pv = buildProfileEditOcrPreview(vision).take(420)
                devOp(
                    logAlias,
                    "「修改基本资料」设置菜单探针 OCR $ocrPass/${PROFILE_EDIT_SETTINGS_PRIVACY_OCR_PER_TAP}（第 $tapAttempt 次点击）摘要=$pv",
                )
                if (!profileEditSettingsGearSidebarProbeVisible(vision)) {
                    devOp(
                        logAlias,
                        "「修改基本资料」设置菜单探针 OCR $ocrPass/${PROFILE_EDIT_SETTINGS_PRIVACY_OCR_PER_TAP}（第 $tapAttempt 次点击）：未见 Settings and privacy",
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
        if (!profileEditDelay(logAlias, 550L, "点 Settings and privacy 后")) return false to "hard_stop"
        return true to ""
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
            val pv = buildProfileEditOcrPreview(vision).take(420)
            devOp(logAlias, "「修改基本资料」插入段 Permissions OCR $pass/$maxPermissionsPasses：摘要=$pv")
            val marker = findProfileEditPermissionsScreenMarkerHit(vision)
            if (marker == null) {
                devOp(
                    logAlias,
                    "「修改基本资料」插入段 Permissions $pass/$maxPermissionsPasses：未见 Permissions / Security and permissions",
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
            val pv = buildProfileEditOcrPreview(vision).take(420)
            devOp(logAlias, "「修改基本资料」Permissions 校验 OCR $pass/$maxPasses：摘要=$pv")
            val marker = findProfileEditPermissionsScreenMarkerHit(vision)
            if (marker == null) {
                devOp(
                    logAlias,
                    "「修改基本资料」Permissions 校验 $pass/$maxPasses：未见 Permissions / Security and permissions（Settings and privacy 可能未点中）",
                )
                continue
            }
            val (permissionsHit, markerLabel) = marker
            profileEditLogStrictHit(logAlias, "Permissions(校验)", markerLabel, permissionsHit, w, h)
            devOp(logAlias, "「修改基本资料」已见 $markerLabel：验证 Settings and privacy 点击成功（仅校验，不点击）")
            val privacyBottom = findBottommostStrictExactLabelHit(vision, "Privacy")
            if (privacyBottom == null) {
                devOp(logAlias, "「修改基本资料」Permissions 校验 $pass/$maxPasses：屏内未见 Privacy")
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
                val pv = buildProfileEditOcrPreview(vision).take(420)
                devOp(
                    logAlias,
                    "「修改基本资料」Viewers OCR（下滑后第 $scrollPass 屏）行摘要=$pv",
                )
                val hit = findProfileEditViewersHit(vision)
                if (hit != null) {
                    profileEditLogStrictHit(logAlias, "Viewers", "Viewers", hit, w, h)
                    viewersHit = hit
                    viewersVision = vision
                    devOp(logAlias, "「修改基本资料」已定位「Viewers」（第 $scrollPass 屏）")
                    break
                }
                devOp(logAlias, "「修改基本资料」Viewers OCR（下滑后第 $scrollPass 屏）：未命中")
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

    private suspend fun profileEditOcrTapSaveTopmost(
        ctx: Context,
        logAlias: String,
        w: Int,
        h: Int,
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
            val pv = buildProfileEditOcrPreview(vision).take(420)
            devOp(logAlias, "「修改基本资料」Save 第 $pass/6 次：行摘要=$pv")
            val hit = findTopmostStrictExactLabelHit(vision, "Save")
            if (hit == null) {
                devOp(logAlias, "「修改基本资料」Save 第 $pass/6 次：未命中（多命中时取 bbox.top 最小）")
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
            val pv = buildProfileEditOcrPreview(vision).take(420)
            devOp(logAlias, "「修改基本资料」Confirm OCR 第 $pass/$maxPasses 次：行摘要=$pv")
            val elDump = buildProfileEditOcrElementDump(vision, 96)
            devOp(
                logAlias,
                "「修改基本资料」Confirm OCR 第 $pass/$maxPasses 次：element 采样（供对勾/文案校准，至多 96 段）=$elDump",
            )
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
                devOp(logAlias, "「修改基本资料」Confirm 第 $pass/$maxPasses 次：未命中严格文案")
                continue
            }
            profileEditLogStrictHit(logAlias, "Confirm", "Confirm", hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            devOp(logAlias, "「修改基本资料」Confirm：input tap (${tap.x},${tap.y})")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                devOp(logAlias, "「修改基本资料」Confirm：第 $pass/$maxPasses 次命中并完成 tap（本步成功）")
                return true
            }
            devOp(logAlias, "「修改基本资料」Confirm 第 $pass/$maxPasses 次 tap 失败（su）")
        }
        return false
    }

    /**
     * 整屏 OCR 查找「Don't allow」并点击；未命中不点击、不判失败。
     */
    private suspend fun tryDismissDontAllowOcr(
        ctx: Context,
        alias: String,
        phaseLog: String,
        logSuffix: String,
        maxPasses: Int = TIKTOK_LAUNCH_DONT_ALLOW_OCR_MAX_PASSES,
        retryDelayMs: Long = TIKTOK_LAUNCH_DONT_ALLOW_OCR_RETRY_DELAY_MS,
        logPrefix: String = "",
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
        line(
            "$phaseLog：可选整屏 OCR（至多 $passes 次" +
                if (passes > 1) "，相邻间隔 $retrySecLabel" else "" +
                "；未命中则跳过）[$logSuffix]",
        )
        for (pass in 1..passes) {
            if (hardStopOrNull(logAlias, phaseLog)) return false
            if (pass > 1) delay(retryDelay)
            val cacheTag = "dont_allow_${safeSuffix}_$pass"
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, cacheTag)
            if (vision == null) {
                line("$phaseLog 第 $pass/$passes 次 OCR 失败：$errTag [$logSuffix]")
                continue
            }
            val pv = buildProfileEditOcrPreview(vision).take(480)
            line("$phaseLog 第 $pass/$passes 次：行摘要=$pv [$logSuffix]")
            val hit = findDontAllowLabelHit(vision)
            if (hit == null) {
                line("$phaseLog 第 $pass/$passes 次：未命中 [$logSuffix]")
                continue
            }
            if (prefix.isNotEmpty()) {
                profileEditLogStrictHit(logAlias, phaseLog, "Don't allow", hit, w, h)
            } else {
                val tapPreview = coerceTapToScreen(hit.point, w, h)
                devOp(logAlias, "$phaseLog：命中 Don't allow (${tapPreview.x},${tapPreview.y}) [$logSuffix]")
            }
            val tap = coerceTapToScreen(hit.point, w, h)
            line("$phaseLog：input tap (${tap.x},${tap.y}) [$logSuffix]")
            if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                delay(450)
                line("$phaseLog：已点击 [$logSuffix]")
                return true
            }
            line("$phaseLog：tap 失败（su）${if (passes > 1) "，间隔后将重试 OCR" else ""} [$logSuffix]")
        }
        line("$phaseLog：$passes 次均未完成点击，跳过（不打断流程）[$logSuffix]")
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
                "整屏「Upload photo」（OCR 最多 3 次）→「Allow all」（OCR 最多 ${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次，未命中仅跳过该按钮）→" +
                "左上固定点→Next×2（间隔 0.5s）→Post this photo to Story→Save→" +
                "等 2s 后 OCR「Change photo」校验至多 7 次→Name→Bio(模板)→" +
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
            devOp(logAlias, "「修改基本资料」Edit OCR 第 $editAttempts/3 次：行摘要=${preview.take(500)}")
            editPoint = findStrictEditTapPoint(vision)
            if (editPoint == null) {
                devOp(logAlias, "「修改基本资料」Edit OCR 第 $editAttempts/3 次：未命中严格文案「Edit」")
            }
        }
        if (editPoint == null) {
            devOp(logAlias, "「修改基本资料」失败：Edit 经 3 次 OCR 仍未命中")
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
            devOp(logAlias, "「修改基本资料」失败：Edit 命中但内部未缓存最后一次 OCR 结果")
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
            devOp(logAlias, "「修改基本资料」三项菜单 OCR 第 $changeAttempts/3 次：行摘要=${preview.take(500)}")
            val triple = findProfileEditChangePhotoNameBioTriple(vision)
            if (triple == null) {
                val okCp = findStrictExactLabelHit(vision, "Change photo") != null
                val okNm =
                    findStrictExactLabelHit(vision, "Name") != null || findStrictExactLabelHit(vision, "name") != null
                val okBio = findStrictExactLabelHit(vision, "Bio") != null
                devOp(
                    logAlias,
                    "「修改基本资料」三项菜单 OCR 第 $changeAttempts/3 次：未三词齐全（Change photo=$okCp，Name/name=$okNm，Bio=$okBio）。行摘要仅供浏览，命中以逐项 element/line trim 全等为准",
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
                "「修改基本资料」已齐三项菜单坐标；按流程在同屏内对「Change photo」执行首击（随后整屏 OCR「Upload photo」）",
            )
            val samePassChange = findStrictExactLabelHit(menuVision, "Change photo")
            if (samePassChange == null) {
                devOp(
                    logAlias,
                    "「修改基本资料」同屏异常：三词已齐但单独未解析出「Change photo」element/line，将启动专段整屏 OCR 查找「Change photo」",
                )
            } else {
                profileEditLogStrictHit(
                    logAlias,
                    "Change photo(菜单同屏)",
                    "Change photo",
                    samePassChange,
                    w,
                    h,
                )
                val tapMenuPass = coerceTapToScreen(samePassChange.point, w, h)
                changePhotoTapPx = tapMenuPass.x to tapMenuPass.y
                devOp(
                    logAlias,
                    "「修改基本资料」Change photo(菜单同屏)：严格文案坐标已打印于上条；执行 input tap (${tapMenuPass.x},${tapMenuPass.y})",
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
            devOp(logAlias, "「修改基本资料」严格「Change photo」第 $changePhotoStrictAttempts/3 次：行摘要=${pv.take(500)}")
            val hit = findStrictExactLabelHit(vision, "Change photo")
            if (hit == null) {
                devOp(logAlias, "「修改基本资料」严格「Change photo」第 $changePhotoStrictAttempts/3 次：未命中")
                continue
            }
            profileEditLogStrictHit(logAlias, "Change photo(专段整屏)", "Change photo", hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            changePhotoTapPx = tap.x to tap.y
            devOp(logAlias, "「修改基本资料」Change photo(专段整屏)：严格文案坐标已打印于上条；执行 input tap (${tap.x},${tap.y})")
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
            "「修改基本资料」「Change photo」tap 已完成；短延迟后重新整屏 OCR，严格匹配「Upload photo」（命中坐标将写入日志）",
        )

        val uploadPreviews = mutableListOf<String>()
        var uploadTapPx: Pair<Int, Int>? = null
        var uploadTapOk = false
        var uploadAttempts = 0
        while (uploadAttempts < 3 && !uploadTapOk) {
            uploadAttempts++
            delay(600)
            devOp(logAlias, "「修改基本资料」严格「Upload photo」OCR 第 $uploadAttempts/3 次…")
            val (vision, errTag) = mlKitRecognizeFullScreen(ctx, "profile_edit_upload_photo_$uploadAttempts")
            if (vision == null) {
                uploadPreviews.add("[$errTag]")
                devOp(logAlias, "「修改基本资料」Upload photo 第 $uploadAttempts/3 次失败：$errTag")
                continue
            }
            val upPrev = buildProfileEditOcrPreview(vision)
            uploadPreviews.add(upPrev)
            devOp(logAlias, "「修改基本资料」Upload photo 第 $uploadAttempts/3 次：行摘要=${upPrev.take(500)}")
            val hit = findStrictExactLabelHit(vision, "Upload photo")
            if (hit == null) {
                devOp(logAlias, "「修改基本资料」Upload photo 第 $uploadAttempts/3 次：未命中严格文案")
                continue
            }
            profileEditLogStrictHit(logAlias, "Upload photo", "Upload photo", hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            uploadTapPx = tap.x to tap.y
            devOp(logAlias, "「修改基本资料」Upload photo：严格文案坐标已打印于上条；执行 input tap (${tap.x},${tap.y})")
            uploadTapOk = runSuCommand("input tap ${tap.x} ${tap.y}")
            if (!uploadTapOk) {
                devOp(logAlias, "「修改基本资料」失败：Upload photo 的 input tap 未成功（su）")
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
            devOp(logAlias, "「修改基本资料」失败：严格「Upload photo」经 3 次 OCR 仍未完成有效点击")
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
            "「修改基本资料」「Upload photo」tap 已完成；短延迟后尝试整屏 OCR「Allow all」（最多 ${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次；均未命中则跳过，不中断链路）",
        )

        val allowPreviews = mutableListOf<String>()
        var allowTapPx: Pair<Int, Int>? = null
        var allowTapOk = false
        var allowAttempts = 0
        while (allowAttempts < PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES && !allowTapOk) {
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
            devOp(
                logAlias,
                "「修改基本资料」Allow all 第 $allowAttempts/${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次：行摘要=${ap.take(500)}",
            )
            val hit = findStrictExactLabelHit(vision, "Allow all")
            if (hit == null) {
                devOp(
                    logAlias,
                    "「修改基本资料」Allow all 第 $allowAttempts/${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次：未命中严格文案",
                )
                continue
            }
            profileEditLogStrictHit(logAlias, "Allow all", "Allow all", hit, w, h)
            val tap = coerceTapToScreen(hit.point, w, h)
            allowTapPx = tap.x to tap.y
            devOp(logAlias, "「修改基本资料」Allow all：严格文案坐标已打印于上条；执行 input tap (${tap.x},${tap.y})")
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
        if (!allowTapOk) {
            devOp(
                logAlias,
                "「修改基本资料」Allow all：${PROFILE_EDIT_ALLOW_ALL_OCR_MAX_PASSES} 次 OCR 均未命中严格文案（可能无系统权限弹窗或当前为相册/最近界面），不再查找点击位置，跳过本步继续后续流程",
            )
        }

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
        )

        delay(550)
        if (allowTapOk) {
            devOp(logAlias, "「修改基本资料」Allow all 已点击，进入选图发帖（左上点→Next×2→Story→Save→头像校验）")
        } else {
            devOp(logAlias, "「修改基本资料」Allow all 未点击（仅跳过该权限按钮），其余选图发帖步骤照常执行")
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
        if (
            !profileEditOcrTapByFinder(
                ctx, logAlias, w, h, "profile_edit_post_story", "Post this photo to Story", "Post this photo to Story", 6,
            ) { findStrictExactAcrossAdjacentLines(it, "Post this photo to Story") }
        ) {
            return chainBase(success = false, failureStep = "post_story_ocr_miss")
        }
        delay(550)
        if (
            !profileEditOcrTapByFinder(
                ctx, logAlias, w, h, "profile_edit_post_save", "Save(发帖)", "Save", 6,
            ) { findStrictExactLabelHit(it, "Save") }
        ) {
            return chainBase(success = false, failureStep = "post_save_ocr_miss")
        }

        delay(2000L)
        var avatarPostVerifyCount = 0
        var avatarPostSeen = false
        while (avatarPostVerifyCount < 7) {
            avatarPostVerifyCount++
            if (avatarPostVerifyCount > 1) delay(800)
            val (vAv, eAv) = mlKitRecognizeFullScreen(ctx, "profile_edit_avatar_verify_$avatarPostVerifyCount")
            if (vAv == null) {
                devOp(logAlias, "「修改基本资料」发帖后头像校验 OCR $avatarPostVerifyCount/7 失败：$eAv")
                continue
            }
            devOp(
                logAlias,
                "「修改基本资料」发帖后头像校验第 $avatarPostVerifyCount/7 次：摘要=${buildProfileEditOcrPreview(vAv).take(420)}",
            )
            if (findStrictExactLabelHit(vAv, "Change photo") != null) {
                avatarPostSeen = true
                devOp(logAlias, "「修改基本资料」发帖后：已定位「Change photo」（$avatarPostVerifyCount/7），继续 Name→Bio 流程")
                break
            }
            devOp(logAlias, "「修改基本资料」发帖后头像校验第 $avatarPostVerifyCount/7 次：未见「Change photo」")
        }
        if (!avatarPostSeen) {
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
        val postConfirmAttempts = postConfirmCpAttempts

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
            }
            devOp(logAlias, "「修改基本资料」Bio 后① 第 $part1Round/$maxBioAfterPart1 轮：Save(最上 OCR)")
            if (profileEditOcrTapSaveTopmost(ctx, logAlias, w, h) == null) {
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

    /** 过滤命令细节、OCR 逐次重试等噪音，只保留对用户有意义的步骤/结果。 */
    private fun shouldSkipDeviceLog(action: String): Boolean {
        val s = action
        val commandNoise = listOf(
            "am force-stop", "am start", "monkey -p", "pm clear", "pkill", "killall",
            "input tap", "input text", "input swipe", "input keyevent", "screencap",
            "uiautomator", "执行启动命令", "启动命令[", "强停命令", "行摘要=", "OCR 摘要=",
            "su tap", "（su）", "bbox(", "拟 tap=", "点击中心=",
            "dumpsys", "top=", "forceStopWaitMs=", "launchWaitMs=",
            "startActivity ", "distance=", "startY=", "endY=",
            "不 input tap", "input 失败", "文案 input",
        )
        if (commandNoise.any { s.contains(it, ignoreCase = true) }) return true
        if (Regex("""第\s*\d+\s*/\s*\d+\s*次[：:]""").containsMatchIn(s)) {
            val keep = listOf("失败", "成功", "完成", "已定位", "已点", "已见", "命中", "上限")
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
        if (s.startsWith("发作品：") && (
                s.contains("比例点 (") || s.contains("右下固定点 (") || s.contains("布局 B 备用") ||
                    s.contains("OCR 第") || s.contains("OCR 摘要=") || s.contains("跳过拉起等待") ||
                    s.contains("当日首条权限 OCR=") || s.contains("续发：")
                )
        ) return true
        return false
    }

    /** 设备端实时日志：只记录当前操作，可选附带耗时（毫秒）。 */
    private fun devOp(alias: String, action: String, elapsedMs: Long? = null) {
        if (shouldSkipDeviceLog(action)) return
        val root = currentRootUsernameForLogs.trim()
        val prefix = if (root.isNotEmpty()) "[$root] " else ""
        LogCenter.op(alias, "$prefix$action", elapsedMs)
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
    private suspend fun ensureTikTokInForeground(
        ctx: Context,
        alias: String,
        tag: String,
    ): Boolean {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        if (isPackageVisible(pkg)) return true

        val launchWaitMs = lastConfigSnapshot.tiktok_launch_wait_ms.coerceIn(800L, 10000L)

        devOp(alias, "$tag：dumpsys 非 TikTok 前台，先尝试切到前台（不强停，避免误杀后台进程）")
        if (launchTikTokOnceWithCheck(ctx, alias, "$tag·切前台", launchWaitMs, forceStopBeforeLaunch = false)) {
            devOp(alias, "$tag：切前台后已确认 TikTok 在前台，继续任务")
            return true
        }

        devOp(alias, "$tag：切前台后仍非 TikTok 前台（可能在后台或拉起无效），强停后完整重拉")
        if (restartTikTokForRecovery()) {
            devOp(alias, "$tag：强停重拉成功，已确认 TikTok 在前台，继续任务")
            return true
        }

        devOp(alias, "$tag：强停重拉后前台仍未确认，本段任务无法安全继续")
        return false
    }

    private suspend fun runForYouWarmupIfNeeded(service: Context, alias: String, config: AppConfig) {
        val totalMs = (config.for_you_swipe_total_sec * 1000.0).toLong().coerceAtLeast(0L)
        if (totalMs < 500L) return
        if (!ensureTikTokInForeground(service, alias, "For You 预滑")) return

        val likeProb = config.for_you_like_prob.coerceIn(0.0, 1.0)
        val rawMinMs = (config.for_you_watch_min_sec * 1000.0).toLong().coerceAtLeast(500L)
        val rawMaxMs = (config.for_you_watch_max_sec * 1000.0).toLong().coerceAtLeast(500L)
        val watchMinMs = minOf(rawMinMs, rawMaxMs)
        val watchMaxMs = maxOf(rawMinMs, rawMaxMs)
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val baseX = (width * 0.5f).toInt()
        val startY = (height * 0.78f).toInt()
        val endY = (height * 0.32f).toInt()
        val likeX = (width * 0.5f).toInt()
        val likeY = (height * 0.55f).toInt()
        val startedAt = System.currentTimeMillis()

        devOp(alias, "For You 预滑开始：总时长=${totalMs}ms")
        while (System.currentTimeMillis() - startedAt < totalMs) {
            if (mutualClickOrNull(alias, "For You 预滑")) return
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
                delay(preDelay)
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
                delay(preDelay)
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
            if (rest > 0L) {
                delay(rest)
            }
            if (System.currentTimeMillis() - startedAt >= totalMs) break

            val swipeX = (baseX + Random.nextInt(-24, 25)).coerceIn(1, width - 1)
            val swipeDuration = Random.nextInt(260, 421)
            runSuCommand("input swipe $swipeX $startY $swipeX $endY $swipeDuration")
            delay(450)
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
            if (hardStopOrNull(alias, "发作品入口 __publish_post__")) {
                PrefsStore.setPublishPostPackageName(null)
                PrefsStore.clearPublishPostState()
                return "已硬停，跳过发作品入口"
            }
            val pkg = PrefsStore.getPublishPostPackageName()?.trim()?.takeIf { it.isNotEmpty() }
                ?: Config.TIKTOK_PACKAGE_NAME
            val accountKeyForResume =
                PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() } ?: alias
            val ok = runPublishPostEntryFlow(service, pkg, config)
            if (ok) {
                PrefsStore.clearPublishPostResumeProgress(accountKeyForResume)
                PrefsStore.setPublishPostPending(false)
                PrefsStore.setPublishPostRequested(false)
                PrefsStore.setPublishPostPackageName(null)
                PrefsStore.clearPublishPostState()
            } else {
                PrefsStore.setPublishPostPending(true)
                PrefsStore.setPublishPostRequested(true)
            }
            if (!PrefsStore.getDeviceId().isNullOrBlank() && !PrefsStore.getAlias().isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        ApiService.reportDeviceAutoStartOptOut(
                            deviceId = PrefsStore.getDeviceId()!!,
                            alias = PrefsStore.getAlias()!!,
                            optOut = false,
                        )
                    }
                }
            }
            return if (ok) "发作品：已完成" else "发作品：未完成"
        }
        didRestartTikTokInCurrentRun = false
        currentRootUsernameForLogs = username
        val runMode = config.run_mode.trim().lowercase()
        val mutualRequested = PrefsStore.isMutualClickRequested()
        val mutualPending = PrefsStore.isMutualClickPending()
        devOp(alias, "进入 executeForUsername：username=$username runMode=$runMode mutualRequested=$mutualRequested mutualPending=$mutualPending hardStop=${PrefsStore.isHardStopRequested()}")
        if (shouldSwitchToMutualClick()) {
            val commentOnlyForMessage = PrefsStore.isMutualCommentOnlySession()
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
            val ok = enterMutualClickMode(service, config)
            return if (ok) {
                if (commentOnlyForMessage) {
                    "已切换到互点流程（仅评论）：已完成 teamwork Videos 单元（进作品→点赞/收藏/互点评论→回列表；未执行左滑与作者主页/Followers）"
                } else {
                    "已切换到互点流程：已完成 teamwork Videos 循环单元（进作品→左滑→回作品→点赞/收藏/互点评论→回列表）"
                }
            } else {
                "互点流程切换失败：已尝试重启 TikTok 并走完 teamwork 进作品链路"
            }
        }
        if ((runMode == ServerRunMode.GROWTH || runMode == ServerRunMode.MUTUAL) && restartFromMagnifierForGrowth) {
            devOp(alias, "涨粉模式：重启后从点击放大镜重新开始")
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
        } else {
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

        // 5. 搜索结果页：点击 Users。主播模式增加“>=3个Follow”验收，不通过则补点重试。
        val modeForUsersCheck = config.run_mode.trim().lowercase()
        val isDirectCustomerRunMode = modeForUsersCheck == ServerRunMode.DIRECT
        val isLiveHostRunMode = modeForUsersCheck == ServerRunMode.LIVE
        val isLiveModeForUsersCheck = isDirectCustomerRunMode || isLiveHostRunMode
        ensureUsersTabClicked(service)
        if (isLiveModeForUsersCheck) {
            ensureUsersTabReadyForLiveMode(service)
        } else {
            // 非主播模式：剥离无障碍后仅靠坐标+OCR，Users 列表需更充分渲染后再截屏（与 findUserInListByOcr 内首帧等待叠加）
            delay(2800)
        }

        // 6. 在 Users 列表中点击目标用户（仅 OCR：TikTok 不暴露无障碍节点）
        val ocrPoint = findUserInListByOcr(service, username, isLiveModeForUsersCheck)
        if (ocrPoint == null) {
            devOp(alias, "Users 列表 OCR 未找到目标：$username")
            lastEndedAtUsersSelection = !didRestartTikTokInCurrentRun
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
            return "已重启 TikTok 并跳过当前用户名 $username"
        }
        return "已拉取用户名 $username，已完成：搜索 → 点头像 → 进粉丝列表并处理用户"
    }

    /** @return 是否已恢复前台并完成放大镜点击 */
    private suspend fun clickMagnifier(service: Context): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        return withTikTokGuard(alias, "点击放大镜") {
            if (!ensureTikTokInForeground(service, alias, "点击放大镜")) return@withTikTokGuard false
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
            
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
            cropped.recycle()
            screenshotFile.delete()
        }
    }

    private suspend fun enterVideosVideoByQuarterTaps(service: Context, config: AppConfig): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val mutualCommentOnly = PrefsStore.isMutualCommentOnlySession()
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
            if (mutualCommentOnly) {
                "互点：进入 Videos（互点评论·兼容旧指令）共 $videoRoundCap 轮（左+右各进 1 次作品为 1 轮），轮间下滑约半屏换批"
            } else {
                "互点：进入 Videos（完整互点）共 $videoRoundCap 轮；粉丝列表 Follow 上限 $followLimit（达到后本会话剩余轮次仅互点评论，不再左滑/进粉丝列表）；轮间下滑换批"
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
                        "互点：teamwork Videos 已达配置轮次 $videoRoundCap（${if (mutualCommentOnly) "互点评论兼容" else "完整互点"}），结束",
                    )
                    break@mainVideosLoop
                }
                val roundCapLabel = "$videoRoundCap"
                fixedTaps.forEachIndexed { tapIndex, tap ->
                    coroutineContext.ensureActive()
                    if (hardStopOrNull(alias, "互点 Videos 互动")) return false
                    val sideLabel = if (tapIndex == 0) "左侧" else "右侧"
                    var verified = false
                    var recoveryCycles = 0
                    while (!verified) {
                        coroutineContext.ensureActive()
                        if (hardStopOrNull(alias, "互点 Videos 互动")) return false
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
                                "互点：Videos 第 ${roundIndex + 1}/$roundCapLabel 轮 $sideLabel teamwork 恢复已超过上限（${MUTUAL_VIDEOS_FRAME_DIFF_MAX_RECOVERY_CYCLES} 次），中止",
                            )
                            return false
                        }
                        devOp(
                            alias,
                            "互点：判定当前界面未知（${sideLabel} 连续 3 次帧差未通过），关闭 TikTok 并重新搜索关键词",
                        )
                        didRestartTikTokInCurrentRun = true
                        if (!reopenTikTokAndOpenMutualTeamworkVideosTab(service, config, "互点 teamwork 恢复")) {
                            devOp(alias, "互点：teamwork 界面恢复失败，中止 Videos 互动")
                            return false
                        }
                    }
                    val unitTag = if (mutualCommentOnly) {
                        "Videos ${roundIndex + 1}/$videoRoundCap $sideLabel"
                    } else if (mutualFollowLimitReached) {
                        "Videos ${roundIndex + 1}/$videoRoundCap $sideLabel·Follow已达上限仅评论"
                    } else {
                        "Videos 第${roundIndex + 1}轮 $sideLabel"
                    }
                    val skipSwipeAndFollowers = mutualCommentOnly || mutualFollowLimitReached
                    if (skipSwipeAndFollowers) {
                        val reason =
                            if (mutualCommentOnly) "互点评论会话" else "已达粉丝列表 Follow 上限，后续仅互点评论"
                        devOp(alias, "互点：$unitTag $reason，跳过左滑与作者主页/Followers，留在当前作品继续互动")
                    } else {
                        mutualVideosLeftSwipeThenDoubleBackToPost(service, config, unitTag)
                    }
                    val postStayMs = randomDelayMsFromSecondsRange(config.video_stay_min, config.video_stay_max)
                    devOp(alias, "互点：$unitTag 回到作品后停留 ${postStayMs}ms")
                    delay(postStayMs)
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
                        val commentAvail = config.comment_daily_limit > 0 &&
                            PrefsStore.getDailyActionCount("comment") < config.comment_daily_limit
                        if (commentAvail) {
                            delay(randomDelayMsFromSecondsRange(actionGapMin, actionGapMax))
                            if (performMutualCommentTextDirectFixedCoordinate(service, config)) {
                                PrefsStore.incrementDailyActionCount("comment")
                            }
                        } else {
                            devOp(alias, "互点：Videos 跳过评论（已达上限或 comment_daily_limit 为 0）")
                        }
                    }
                    delay(Random.nextLong(700, 1100))
                    navBack(alias, "互点 $unitTag 互动完成返回 teamwork Videos 列表")
                    delay(Random.nextLong(800, 1200))
                }
                roundIndex++
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

    private fun isMutualFollowButtonTextNormalized(norm: String): Boolean {
        if (norm.isBlank()) return false
        val tokens = norm.split(Regex("[^a-z]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (tokens.any { it == "following" || it == "followers" }) return false
        // 单行 OCR 常把 Follow 与 Message、简介连成一块；只要分词里有 follow 仍视为关注按钮候选。
        if (tokens.any { it == "follow" }) return true
        if (tokens.any { it == "message" }) return false
        return norm.contains("follow") && !norm.contains("following") && !norm.contains("followers")
    }

    private data class MutualFollowTarget(val point: Point, val messageY: Int)

    private suspend fun mutualClickUpperHalfOcrFollowTarget(service: Context): MutualFollowTarget? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val screenshotFile = File(service.cacheDir, "mutual_author_red_half.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return null
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return null
        }
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
                .flatMap { it.elements.asSequence() }
                .mapNotNull { el ->
                    val box = el.boundingBox ?: return@mapNotNull null
                    val norm = normalizeOcrButtonText(el.text)
                    val centerX = box.centerX()
                    val centerY = cropTop + box.centerY()
                    Triple(norm, centerX, centerY)
                }
                .toList()

            val messageHit = allElements.firstOrNull { (norm, _, centerY) ->
                norm.contains("message") && centerY in cropTop..cropBottom
            }
            val messageY = messageHit?.third
            val followHits = allElements.filter { (norm, _, centerY) ->
                centerY in cropTop..cropBottom && isMutualFollowButtonTextNormalized(norm)
            }

            val rowYTol = (fullH * 0.015f).toInt().coerceIn(14, 56)
            val halfW = (fullW * 0.5f).toInt()
            fun sameRow(y: Int, base: Int) = kotlin.math.abs(y - base) <= rowYTol

            val target = if (messageY != null) {
                val my = messageY
                val mx = messageHit?.second
                followHits.firstOrNull { (_, x, y) ->
                    sameRow(y, my) && x >= halfW
                } ?: if (mx != null) {
                    followHits.firstOrNull { (_, x, y) -> sameRow(y, my) && x > mx }
                } else {
                    null
                } ?: followHits.firstOrNull { (_, _, y) -> sameRow(y, my) }
                    ?: followHits.firstOrNull()
            } else {
                followHits.firstOrNull()
            }

            val preview = allElements.map { it.first }.filter { it.isNotEmpty() }.take(12).joinToString(" | ")
            val messagePoint = messageHit?.let { Point(it.second, it.third) }
            val followPoint = target?.let { Point(it.second, it.third) }
            devOp(
                alias,
                "互点：OCR按钮区 crop=(${cropTop}-${cropBottom}) message=${messagePoint?.let { "(${it.x},${it.y})" } ?: "<none>"} follow=${followPoint?.let { "(${it.x},${it.y})" } ?: "<none>"} preview=$preview",
            )
            target?.let { MutualFollowTarget(Point(it.second, it.third), messageY ?: it.third) }
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
                mutualSessionFollowTapCount++
                devOp(alias, "互点：粉丝列表 Follow 会话计数 $mutualSessionFollowTapCount/$limit")
                if (mutualSessionFollowTapCount >= limit) {
                    mutualFollowLimitReached = true
                    devOp(alias, "互点：已达粉丝列表 Follow 会话上限 $limit，结束列表滑动")
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

    private suspend fun mutualClickCopyUsernameReportAndOpenFollowers(service: Context, config: AppConfig) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (mutualFollowLimitReached) {
            devOp(alias, "互点：已达粉丝列表 Follow 会话上限，不再执行复制用户名/进入 Followers 链路")
            return
        }
        if (hardStopOrNull(alias, "互点复制用户名到系统剪贴板")) return
        val copyPt = mutualClickCopyUsernameTapFromFollowersRowPx(service)
        devOp(alias, "互点：点按复制用户名（${copyPt.x},${copyPt.y}）（写入系统剪贴板；不在此读剪贴板，上报在 teamwork 发评时粘贴+uiautomator）")
        runSuCommand("input tap ${copyPt.x} ${copyPt.y}")
        delay(Random.nextLong(700, 1200))

        delay(Random.nextLong(280, 551))
        if (hardStopOrNull(alias, "互点点击 Followers 并校验列表")) return
        val entered = mutualClickFollowersAndVerify(service)
        if (!entered) {
            devOp(alias, "互点：未进入粉丝列表（三轮 OCR 或重启后），跳过列表内 Follow")
            return
        }

        delay(Random.nextLong(800, 1400))
        if (hardStopOrNull(alias, "互点粉丝列表 Follow+下滑")) return
        mutualProcessFollowersListFollowTapsAndGrowthSwipe(service, config)
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
     * 再连续两次返回回到**同一作品**播放页，供后续点赞/收藏/评论使用。
     * 与 [mutualClickSwipeToAuthorThenMessageOrFollowersTap]（完整主页+Followers 链路）不同，此处不跑 OCR/上报。
     */
    private suspend fun mutualVideosLeftSwipeThenDoubleBackToPost(
        service: Context,
        config: AppConfig,
        unitLogTag: String,
    ) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "互点 $unitLogTag 左滑")) return
        delay(Random.nextLong(800, 1200))
        val m = service.resources.displayMetrics
        val w = m.widthPixels.coerceAtLeast(1)
        val h = m.heightPixels.coerceAtLeast(1)
        val xStart = (w * 0.92f).toInt().coerceIn(1, w - 1)
        val xEnd = (w * 0.08f).toInt().coerceIn(1, w - 1)
        val afterTapMs = config.growth_post_check_after_tap_delay_ms.coerceIn(100L, 5000L)

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
            if (hardStopOrNull(alias, "互点 $unitLogTag 左滑")) return
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
            }
            sawAuthorLayer -> {
                devOp(alias, "互点主页链路：步骤1/3 已识别作者层（Message），无 Follow OCR 行可点，跳过")
            }
            else -> {
                devOp(alias, "互点主页链路：步骤1/3 作者区 Follow（OCR）未通过，跳过点击")
            }
        }

        devOp(alias, "互点主页链路：步骤2/3 点击用户名复制（上报仅在 teamwork 发评粘贴+uiautomator）")
        mutualClickCopyUsernameReportAndOpenFollowers(service, config)

        devOp(alias, "互点主页链路：步骤3/3 Followers 后返回 2 次")
        navBack(alias, "互点主页链路完成后返回 Videos 第 1 次")
        delay(Random.nextLong(700, 1100))
        navBack(alias, "互点主页链路完成后返回 Videos 第 2 次")
        delay(Random.nextLong(700, 1100))
        devOp(alias, "互点主页链路：已回到 Videos，准备继续轮转")
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
        mutualClickCopyUsernameReportAndOpenFollowers(service, config)
        devOp(alias, "互点主页链路：步骤4/5 Followers 后返回 2 次")
        navBack(alias, "互点主页链路完成后返回 Videos 第 1 次")
        delay(Random.nextLong(700, 1100))
        navBack(alias, "互点主页链路完成后返回 Videos 第 2 次")
        delay(Random.nextLong(700, 1100))
        devOp(alias, "互点主页链路：步骤5/5 已回到 Videos，准备继续轮转")
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
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val forceStopWaitMs = config.tiktok_force_stop_wait_ms.coerceIn(100L, 8000L)
        val mutualSearchText = pickMutualTikTokSearchKeyword(config)
        val safeSearchText = shellEscape(mutualSearchText)

        runSuCommand("am force-stop $pkg")
        delay(forceStopWaitMs)

        if (!ensureTikTokInForeground(service, alias, "互点·$launchCheckTag")) {
            devOp(alias, "互点：TikTok 恢复拉起失败（$launchCheckTag）")
            return false
        }

        delay(800)

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
        val videosAnchor = findVideosTabAnchorByOcrFast(service)
        if (videosAnchor == null) {
            devOp(alias, "互点：未找到 Videos 标签（$launchCheckTag）")
            return false
        }
        runSuCommand("input tap ${videosAnchor.x} ${videosAnchor.y}")
        delay(2200)
        return true
    }

    /** 互点/互点评论：达到配置上限并成功跑完 Videos 单元后，短暂停留、强停 TikTok、云端恢复自动开启。 */
    private suspend fun finalizeMutualSessionLimitedExit(alias: String) {
        devOp(alias, "互点会话已达配置上限：短暂停留后强停 TikTok，并请求云端恢复该设备自动开启程序（opt_out=false）")
        delay(Random.nextLong(800, 2001))
        val pkg = Config.TIKTOK_PACKAGE_NAME
        runSuCommand("am force-stop $pkg")
        delay(Random.nextLong(400, 901))
        val did = PrefsStore.getDeviceId()
        val aid = PrefsStore.getAlias()
        if (!did.isNullOrBlank() && !aid.isNullOrBlank()) {
            val r = withContext(Dispatchers.IO) {
                runCatching { ApiService.reportDeviceAutoStartOptOut(deviceId = did, alias = aid, optOut = false) }
                    .getOrNull()
            }
            devOp(alias, "互点会话收尾：report_device_auto_start_opt_out 已发 code=${r?.code}")
        } else {
            devOp(alias, "互点会话收尾：缺少 device_id 或 alias，跳过云端自动开启恢复")
        }
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
     * 发作品图库选素材：第 1 张为左上区域基准点；第 2 张为屏宽水平中点、与第 1 同 Y；
     * 第 3 张为第 1 张关于屏幕竖直中线（过屏心的竖线）的左右对称点；
     * 第 4 张固定像素 (162, 1080)（按屏宽高钳位）；第 5 张为屏宽水平中点、Y 与第 4 张相同。
     */
    private fun publishPostGalleryMediaTap(w: Int, h: Int, videoIndex: Int): Point {
        val y1 = ((h * 0.2f) + (h * 0.028f)).toInt().coerceIn(1, h - 1)
        val x1 = ((w * 0.15f).toInt()).coerceIn(1, w - 1)
        val galleryFourthY = 1080
        val y4 = galleryFourthY.coerceIn(1, h - 1)
        return when (videoIndex) {
            0 -> Point(x1, y1)
            1 -> Point((w / 2).coerceIn(1, w - 1), y1)
            2 -> Point((w - x1).coerceIn(1, w - 1), y1)
            3 -> Point(162.coerceIn(1, w - 1), y4)
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

    /** 文案屏偏下区域标签：取最靠上的一个命中，降低误点顶部按钮的概率。 */
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

    /** 文案确认：优先 Done；无 Done 时再认 Post/post（全字匹配，不含 Post Now）。 */
    private fun findPublishCaptionConfirmHit(result: Text, screenH: Int): PublishCaptionConfirmHit? {
        findTopmostCaptionLabelHit(result, screenH, setOf("Done"))
            ?.let { return PublishCaptionConfirmHit(PublishCaptionConfirmKind.DONE, it) }
        findTopmostCaptionLabelHit(result, screenH, setOf("Post", "post"))
            ?.let { return PublishCaptionConfirmHit(PublishCaptionConfirmKind.POST, it) }
        findStrictExactLabelHit(result, "Done")
            ?.let { return PublishCaptionConfirmHit(PublishCaptionConfirmKind.DONE, it) }
        findStrictExactLabelHit(result, "Post")
            ?.let { return PublishCaptionConfirmHit(PublishCaptionConfirmKind.POST, it) }
        findStrictExactLabelHit(result, "post")
            ?.let { return PublishCaptionConfirmHit(PublishCaptionConfirmKind.POST, it) }
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

    /** 三次点 Next 及可选首条弹窗处理之后：底栏至少命中两类主导航则视为发布成功。 */
    private suspend fun verifyPublishPostSucceededByBottomMainNav(
        service: Context,
        alias: String,
        videoIndex: Int,
        screenH: Int,
    ): Boolean {
        val retries = 5
        for (attempt in 1..retries) {
            if (attempt > 1) delay(800)
            if (hardStopOrNull(alias, "发作品 底栏成功校验")) return false
            val (v, err) = mlKitRecognizeFullScreen(service, "publish_post_bottom_nav_ok_${videoIndex}_$attempt")
            if (v == null) {
                devOp(alias, "发作品：发布后底栏校验 OCR 第 $attempt/$retries 次失败：$err")
                if (isMlKitHardStopError(err)) return false
                continue
            }
            val preview = buildProfileEditOcrPreview(v).take(420)
            val n = countBottomMainNavTabKinds(v, screenH)
            devOp(alias, "发作品：发布后底栏 OCR 第 $attempt/$retries 次摘要=$preview；底部主导航种类数=$n（需≥2）")
            if (n >= 2) return true
        }
        devOp(alias, "发作品：发布后底栏连续 $retries 次未同时命中 Home/Friends/Inbox/Profile 中至少 2 项，判定发布失败")
        return false
    }

    private suspend fun publishPostColdLaunch(service: Context, packageName: String, alias: String, config: AppConfig): Boolean {
        if (hardStopOrNull(alias, "发作品冷启")) return false
        lastConfigSnapshot = config
        val launchWaitMs = config.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
        val pkg = packageName.trim().ifEmpty { Config.TIKTOK_PACKAGE_NAME }
        if (pkg == Config.TIKTOK_PACKAGE_NAME) {
            if (launchTikTokOnceWithCheck(service, alias, "发作品冷启", launchWaitMs, forceStopBeforeLaunch = true)) {
                return true
            }
            devOp(alias, "发作品：首次冷启未通过前台校验，尝试 restartTikTokForRecovery")
            val recovered = restartTikTokForRecovery()
            if (!recovered) devOp(alias, "发作品：冷启 TikTok 失败")
            return recovered
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
     * 一旦确认布局 A/B，[runPublishPostEntryFlow] 同任务后续条目直接走对应入口，不再试探。
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
    ): Boolean {
        for (pass in 1..PUBLISH_POST_RECENTS_OCR_MAX_PASSES) {
            if (hardStopOrNull(alias, hardStopLabel)) return false
            if (pass > 1) delay(700)
            if (publishPostProbeRecentsOnScreen(service, alias, "${tagPrefix}_$pass")) return true
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
            )
        ) {
            publishPostEntryLayout = PublishPostEntryLayout.A
            devOp(alias, "发作品：已确认布局 A（右下固定点），本任务后续条目直接沿用、不再 Recents 试探")
            return true
        }
        devOp(
            alias,
            "发作品：右下固定点后 ${PUBLISH_POST_RECENTS_OCR_MAX_PASSES} 次未见 Recents，尝试布局 B 备用点击",
        )
        if (!publishPostTapLayoutBFallback(alias, w, createPostTap)) return false
        if (!publishPostTryAllowAllAfterLayoutTap(service, alias, w, h, "布局B")) return false
        if (
            publishPostProbeRecentsWithRetries(
                service,
                alias,
                "publish_post_recents_after_fallback",
                "发作品 Recents 布局B后 OCR",
            )
        ) {
            publishPostEntryLayout = PublishPostEntryLayout.B
            devOp(alias, "发作品：已确认布局 B（左侧备用点），本任务后续条目直接沿用、不再 Recents 试探")
            return true
        }
        devOp(
            alias,
            "发作品：布局 B 备用点击后 ${PUBLISH_POST_RECENTS_OCR_MAX_PASSES} 次仍未见 Recents，本条失败，需整条重试",
        )
        return false
    }

    private suspend fun publishPostProbeRecentsOnScreen(
        service: Context,
        alias: String,
        ocrTag: String,
    ): Boolean {
        val (vision, errTag) = mlKitRecognizeFullScreen(service, ocrTag)
        if (vision == null) {
            devOp(alias, "发作品：Recents 探测 OCR 失败：$errTag [$ocrTag]")
            if (isMlKitHardStopError(errTag)) hardStopOrNull(alias, "发作品 Recents 探测")
            return false
        }
        devOp(
            alias,
            "发作品：Recents 探测 OCR 摘要=${buildProfileEditOcrPreview(vision).take(420)} [$ocrTag]",
        )
        if (findStrictExactLabelHit(vision, "Recents") != null) {
            devOp(alias, "发作品：已探测到 Recents（仅记录，不点击） [$ocrTag]")
            return true
        }
        devOp(alias, "发作品：Recents 探测未命中 [$ocrTag]")
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
                devOp(alias, "发作品：第 $pass/$maxOcrPasses 次命中「$label」拟 tap (${tap.x},${tap.y})")
                if (runSuCommand("input tap ${tap.x} ${tap.y}")) {
                    devOp(alias, "发作品：已点击「$label」")
                    whileUsingTapped = true
                    break
                }
            }
            if (!whileUsingTapped) {
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
                devOp(alias, "发作品：本任务已确认布局 A，直接右下固定点（跳过 Recents 试探）")
                publishPostTapLayoutAFixedPoint(alias, w, h)
            }
            PublishPostEntryLayout.B -> {
                devOp(alias, "发作品：本任务已确认布局 B，直接左侧备用点（跳过右下固定点与 Recents 试探）")
                publishPostTapLayoutBFallback(alias, w, postTap)
            }
            PublishPostEntryLayout.UNKNOWN ->
                publishPostProbeAndCacheEntryLayout(service, alias, w, h, postTap)
        }
    }

    /**
     * 「发布作品」单条素材的核心选择流程。
     *
     * 阶段顺序：
     * 1) 底部入口（上一段：资料比例点 →（仅本任务第 1 条）Continue / While using → …；首条试探 A/B 并缓存，后续条目不重复 Recents 试探）
     * 2) 图库选素材：第 1～3 张按比例/对称；第 4 张固定 (162,1080)；第 5 张屏宽中点且 Y 同第 4
     * 3) 第一次 OCR 定位 Next；同一轮 OCR 记录 Recents / Videos 之间的分界 Y，并尽量偏 Recents 一侧取值作为“音频位置”
     * 4) 通过 OCR 反复校验 Your Story 是否出现，最多 7 次，间隔 1s；若失败则整条任务失败重来
     * 5) 点击第一次 Next
     * 6) Your Story 命中后，说明素材加载成功，点击音频位置
     * 7) OCR 定位并点击 Sound
     * 8) 再次点击音频位置
     * 9) 沿用第一次 Next 的坐标再次点击 Next
     * 10) 输入文案后 OCR 定位 Done 或 Post/post（优先 Done）；点 Done 后再点一次第一次 Next；点 Post 则等价 Done+Next+确认 Post，跳过后续 Next
     * 11)（仅 Done 路径）再次点击第一次 Next 的坐标，进入后续确认页
     * 12) 使用运行配置中的 [AppConfig.comment_texts] 随机文案输入
     * 13) 仅当日首条：可选权限弹窗（Post Now / Only this time×2（首轮点后二次 OCR，再命中则再点）/ Don't allow / 底部 Cancel），各最多 3 次 OCR，未命中不判失败
     * 14) OCR 校验底部主导航：Home / Friends / Inbox / Profile 在屏幕偏下区域至少命中 2 类则判发布成功
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
                0 -> "第1张基准（X≈15%*W，Y≈22.8%*H）"
                1 -> "第2张（屏X中点，Y同第1）"
                2 -> "第3张（与第1关于竖直中线对称）"
                3 -> "第4张固定 (162,1080) 按屏钳位"
                4 -> "第5张（屏X中点，Y与第4同）"
                else -> "第${videoIndex + 1}张回退第1张规则"
            }
        devOp(
            alias,
            "发作品 第${videoIndex + 1} 条：图库选素材 tap (${galleryTap.x},${galleryTap.y})（屏 ${w}x${h}；$galleryRule）",
        )
        if (!runSuCommand("input tap ${galleryTap.x} ${galleryTap.y}")) return false
        delay(550)

        val (vision0, err0) = mlKitRecognizeFullScreen(service, "publish_post_first_next_${videoIndex}")
        if (vision0 == null) {
            devOp(alias, "发作品：首轮 OCR 失败 $err0")
            if (isMlKitHardStopError(err0)) hardStopOrNull(alias, "发作品首轮 OCR")
            return false
        }
        devOp(alias, "发作品：首轮 OCR 摘要=${buildProfileEditOcrPreview(vision0).take(420)}")
        val next0 = findStrictExactLabelHit(vision0, "Next") ?: run {
            devOp(alias, "发作品：未命中 Next")
            return false
        }
        val audioPoint = fixedAudioTapPoint(service)
        devOp(alias, "发作品：点击固定音频位置 (${audioPoint.x},${audioPoint.y})（屏幕正中 X，顶部 1/10 处 Y）")
        val tapNext0 = coerceTapToScreen(next0.point, w, h)
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

        val audioTap = audioPoint
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
        val writingTapX = (w / 2).coerceIn(1, w - 1)
        val writingTapY = (h / 2).coerceIn(1, h - 1)
        val captionPool = config.comment_texts.map { it.trim() }.filter { it.isNotBlank() }
        val phrase = captionPool.randomOrNull() ?: "Nice"
        devOp(alias, "发作品：准备输入文案，来源=config.comment_texts，候选数=${captionPool.size}，选中文案 len=${phrase.length} preview=${phrase.take(40)}")
        devOp(alias, "发作品：在输入文案前双击屏幕中间固定坐标 (${writingTapX},${writingTapY})，等待页面变化")
        if (!runSuCommand("input tap ${writingTapX} ${writingTapY}")) return false
        delay(120)
        if (!runSuCommand("input tap ${writingTapX} ${writingTapY}")) return false
        devOp(alias, "发作品：已在输入文案前双击屏幕中间固定坐标 (${writingTapX},${writingTapY})，等待页面变化")
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
                devOp(alias, "发作品：Done/Post OCR 第 $pass/$captionConfirmRetryLimit 次命中「$label」")
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
        val tapConfirm = coerceTapToScreen(confirmed.hit.point, w, h)
        if (!runSuCommand("input tap ${tapConfirm.x} ${tapConfirm.y}")) return false
        when (confirmed.kind) {
            PublishCaptionConfirmKind.DONE -> {
                delay(500)
                val tapNextAgain = tapNext0
                devOp(
                    alias,
                    "发作品：Done 已点击，准备复用第一次 Next 坐标 (${tapNextAgain.x},${tapNextAgain.y}) 再点一次 Next",
                )
                if (!runSuCommand("input tap ${tapNextAgain.x} ${tapNextAgain.y}")) return false
                delay(600)

                val postNextRetryLimit = 3
                var postNextRetryAttempts = 0
                while (postNextRetryAttempts < postNextRetryLimit) {
                    if (hardStopOrNull(alias, "发作品第二次 Next 后校验")) return false
                    val (retryVision, retryErr) =
                        mlKitRecognizeFullScreen(
                            service,
                            "publish_post_post_next_retry_${videoIndex}_${postNextRetryAttempts + 1}",
                        )
                    if (retryVision == null) {
                        postNextRetryAttempts++
                        devOp(
                            alias,
                            "发作品：第二次 Next 后校验 OCR 第 ${postNextRetryAttempts}/$postNextRetryLimit 次失败：$retryErr",
                        )
                        if (isMlKitHardStopError(retryErr)) return false
                        continue
                    }
                    val retrySummary = buildProfileEditOcrPreview(retryVision).take(420)
                    devOp(
                        alias,
                        "发作品：第二次 Next 后校验 OCR 第 ${postNextRetryAttempts + 1}/$postNextRetryLimit 次摘要=$retrySummary",
                    )
                    val nextStillVisible = findStrictExactLabelHit(retryVision, "Next") != null
                    val storyVisible = findStrictExactLabelHit(retryVision, "Your Story") != null
                    if (!nextStillVisible || storyVisible) {
                        break
                    }
                    postNextRetryAttempts++
                    delay(900)
                }
                if (postNextRetryAttempts >= postNextRetryLimit) {
                    devOp(alias, "发作品：最后一次点击 Next 前 OCR 连续重试失败，关闭 TikTok 并重新开始")
                    if (hardStopOrNull(alias, "发作品第二次 Next 后校验耗尽")) return false
                    if (!restartTikTokForRecovery()) return false
                    delay(1500)
                    return false
                }
            }
            PublishCaptionConfirmKind.POST -> {
                devOp(
                    alias,
                    "发作品：文案屏 Post 已点击（等价 Done+Next+确认 Post），跳过 Next 与二次 Next 后校验；" +
                        "后续与 Done 路径相同（含当日首条 Post Now 等可选弹窗）",
                )
                delay(600)
            }
        }

        if (needsFirstOfDayPermissionDialogs) {
            devOp(
                alias,
                "发作品：当日首条可选权限弹窗 OCR（Post Now / Only this time（首轮+可选二次 OCR 再点）/ Don't allow / 底部 Cancel；各最多 3 次；" +
                    "未命中仅跳过，不作为发布失败依据）",
            )
            devOp(alias, "发作品：尝试 Post Now（可选）")
            if (publishPostOcrTapByFinder(
                    service,
                    alias,
                    "publish_post_post_now_${videoIndex}",
                    "Post Now",
                    3,
                ) { findPostNowLabelHit(it) }
            ) {
                devOp(alias, "发作品：Post Now 已点")
                delay(500)
            } else {
                devOp(alias, "发作品：Post Now 未命中，跳过（可选弹窗）")
            }
            devOp(alias, "发作品：尝试 Only this time（首轮，可选）")
            val onlyThisTimeRound1Ok = publishPostOcrTapByFinder(
                service,
                alias,
                "publish_post_only_this_time_${videoIndex}_r1",
                "Only this time(首轮)",
                3,
            ) { findOnlyThisTimeLabelHit(it) }
            if (!onlyThisTimeRound1Ok) {
                devOp(alias, "发作品：Only this time 首轮未命中或未点，跳过（无该项或未出现）")
            } else {
                devOp(alias, "发作品：Only this time 首轮已点，进行二次 OCR（仍查找 Only this time，命中再点，未命中则跳过）")
                delay(550)
                if (publishPostOcrTapByFinder(
                        service,
                        alias,
                        "publish_post_only_this_time_${videoIndex}_r2",
                        "Only this time(二次)",
                        3,
                    ) { findOnlyThisTimeLabelHit(it) }
                ) {
                    devOp(alias, "发作品：Only this time 二次已点")
                    delay(500)
                } else {
                    devOp(alias, "发作品：Only this time 二次 OCR 未再命中或未点，跳过（可能仅单层弹窗）")
                }
            }
            devOp(alias, "发作品：尝试 Don't allow（可选）")
            if (publishPostOcrTapByFinder(
                    service,
                    alias,
                    "publish_post_dont_allow_${videoIndex}",
                    "Don't allow",
                    3,
                ) { findDontAllowLabelHit(it) }
            ) {
                devOp(alias, "发作品：Don't allow 已点")
                delay(500)
            } else {
                devOp(alias, "发作品：Don't allow 未命中，跳过（可选弹窗）")
            }
            devOp(alias, "发作品：尝试底部 Cancel（可选）")
            if (publishPostOcrTapByFinder(
                    service,
                    alias,
                    "publish_post_cancel_bottom_${videoIndex}",
                    "Cancel(最下)",
                    3,
                ) { findBottommostStrictExactLabelHit(it, "Cancel") }
            ) {
                devOp(alias, "发作品：底部 Cancel 已点")
                delay(500)
            } else {
                devOp(alias, "发作品：底部 Cancel 未命中，跳过（可选弹窗）")
            }
        }

        if (!verifyPublishPostSucceededByBottomMainNav(service, alias, videoIndex, h)) {
            return false
        }

        devOp(alias, "发作品 第${videoIndex + 1} 条：本段完成")
        return true
    }

    /**
     * 管理台「发布作品」：冷启 TikTok → 资料比例点 →（仅本任务第 1 条）Continue / While using → 右下固定点 →（布局未确认时）Allow all → Recents/布局 B；
     * 再按 `Pictures` 目录树内图片+视频总数循环发作品（su find 递归统计，**不含** `Pictures/AutoTraces`）；
     * 单次任务发布条数 = min(素材条数, PUBLISH_POST_MAX_ITEMS_PER_TASK)（当前上限为 5），超出部分本次不发并在日志说明；
     * 任务开始（或单条失败重试）时冷启 TikTok；条与条之间不冷启（上一条底栏校验通过后直接进下一条入口）。
     * 仅该账号当日首条尝试 Post Now / Only this time（点后二次 OCR 再点可选）/ Don't allow / 底部 Cancel（可选）。
     * 单条是否发布成功以底栏主导航 OCR（Home/Friends/Inbox/Profile 至少 2 类）为准。
     * 图库统计为 0 时不执行发布并返回 false。
     * 全部条数发布成功后，su 删除 `Pictures` 下**全部文件**（含 AutoTraces、Screenshots、.thumbnails 等）。
     * 中途失败时 [PrefsStore] 记录已成功条数，下次从对应图库坐标序号续发（不逐个删素材文件）。
     */
    private suspend fun runPublishPostEntryFlow(service: Context, packageName: String, config: AppConfig): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val galleryStats = publishPostResolveGalleryMaterialStats()
        val galleryMaterialCount = galleryStats.resolvedCount
        if (galleryMaterialCount <= 0) {
            devOp(
                alias,
                "发作品：图库无可用素材，已中止本次任务。明细：Pictures（不含 AutoTraces）图片=${galleryStats.picturesRootImageCount}，" +
                    "视频=${galleryStats.picturesRootVideoCount}（合计=${galleryStats.resolvedCount}）。" +
                    "请确认已 root、素材在 ${PUBLISH_POST_PICTURES_PATH} 下（非 AutoTraces 子目录）且扩展名为 jpg/jpeg/png/webp/gif/mp4/mkv/3gp/webm。",
            )
            return false
        }
        val publishRounds = minOf(galleryMaterialCount, PUBLISH_POST_MAX_ITEMS_PER_TASK)
        val accountKey =
            PrefsStore.getLogContextUsername()?.trim()?.takeIf { it.isNotEmpty() } ?: alias
        val needsPerm = PrefsStore.shouldRunPublishPostFirstDailyPermissionOcr(accountKey)
        var startIdx = PrefsStore.getPublishPostResumeNextIndex(accountKey)
        if (startIdx >= publishRounds) {
            devOp(
                alias,
                "发作品：断点记录已成功 $startIdx 条，不少于本次计划 $publishRounds 条，视为本轮已完成并清理断点",
            )
            PrefsStore.clearPublishPostResumeProgress(accountKey)
            publishPostDeleteAllPicturesAfterSuccess(alias)
            return true
        }
        if (galleryMaterialCount > PUBLISH_POST_MAX_ITEMS_PER_TASK) {
            devOp(
                alias,
                "发作品：图库素材共 $galleryMaterialCount 个（图片=${galleryStats.picturesRootImageCount}，" +
                    "视频=${galleryStats.picturesRootVideoCount}），本次仅发前 $publishRounds 条（上限 ${PUBLISH_POST_MAX_ITEMS_PER_TASK}）；" +
                    "当日首条权限 OCR=$needsPerm（账号键=$accountKey）；" +
                    if (startIdx > 0) "续发：已成功 $startIdx 条，从第 ${startIdx + 1} 条继续" else "续发：从第 1 条开始",
            )
        } else {
            devOp(
                alias,
                "发作品：图库素材共 $galleryMaterialCount 个（图片=${galleryStats.picturesRootImageCount}，" +
                    "视频=${galleryStats.picturesRootVideoCount}），本次发 $publishRounds 条；" +
                    "当日首条权限 OCR=$needsPerm（账号键=$accountKey）；" +
                    if (startIdx > 0) "续发：已成功 $startIdx 条，从第 ${startIdx + 1} 条继续" else "续发：从第 1 条开始",
            )
        }
        val launchWaitMs = config.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
        publishPostEntryLayout = PublishPostEntryLayout.UNKNOWN
        if (!publishPostColdLaunch(service, packageName, alias, config)) return false
        for (idx in startIdx until publishRounds) {
            if (idx > startIdx) {
                devOp(
                    alias,
                    "发作品：上一条已成功，不冷启 TikTok，等待 ${PUBLISH_POST_INTER_ITEM_DELAY_MS}ms 后进入第 ${idx + 1} 条创作入口",
                )
                delay(PUBLISH_POST_INTER_ITEM_DELAY_MS)
                if (!ensureTikTokInForeground(service, alias, "发作品条间续作")) {
                    devOp(alias, "发作品 第 ${idx + 1}/$publishRounds 条：条间恢复拉起未通过，尝试冷启后继续")
                    if (!publishPostColdLaunch(service, packageName, alias, config)) {
                        devOp(alias, "发作品 第 ${idx + 1}/$publishRounds 条：条间冷启失败，跳过本条")
                        if (isHardStopActive()) return false
                        continue
                    }
                }
            }
            val videoIndex = idx
            if (hardStopOrNull(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条")) return false
            var published = false
            var publishAttempts = 0
            while (!published && publishAttempts < 3) {
                if (hardStopOrNull(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条重试")) return false
                publishAttempts++
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
                        isFirstItemInTask = videoIndex == 0,
                    )
                ) {
                    if (publishPostEntryLayout != PublishPostEntryLayout.UNKNOWN) {
                        devOp(alias, "发作品：已缓存布局失效，清除后本条重试将重新试探 A/B")
                        publishPostEntryLayout = PublishPostEntryLayout.UNKNOWN
                    }
                    devOp(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条：资料入口阶段失败（CREATE/POST 或 Recents/布局B），准备同一条重试")
                    if (isHardStopActive()) return false
                    continue
                }
                val runDialogs = needsPerm && idx == 0
                published = runPublishPostPickSoundCaptionAndMaybeDialogs(service, config, alias, videoIndex, runDialogs)
                if (published && runDialogs) {
                    PrefsStore.markPublishPostFirstDailyPermissionOcrDone(accountKey)
                }
                if (!published) {
                    if (isHardStopActive()) return false
                    devOp(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条：发布失败，已记录为第 ${publishAttempts} 次失败，稍后重试同一条作品")
                }
            }
            if (!published) {
                PrefsStore.setPublishPostResumeNextIndex(accountKey, idx)
                devOp(
                    alias,
                    "发作品：第 ${videoIndex + 1}/$publishRounds 条连续 $publishAttempts 次失败；" +
                        "进度已保存：已成功 $idx 条，下次从第 ${idx + 1} 条（图库坐标序号 $idx）继续",
                )
                return false
            }
            PrefsStore.setPublishPostResumeNextIndex(accountKey, idx + 1)
            devOp(alias, "发作品 第 ${videoIndex + 1}/$publishRounds 条：发布成功（累计已成功 ${idx + 1}/$publishRounds 条）")
        }
        PrefsStore.clearPublishPostResumeProgress(accountKey)
        publishPostDeleteAllPicturesAfterSuccess(alias)
        return true
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

    private suspend fun enterMutualClickMode(service: Context, config: AppConfig): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        try {
            PrefsStore.clearMutualClickState()
            mutualSessionFollowTapCount = 0
            mutualFollowLimitReached = false
            devOp(alias, "互点模式：先关闭 TikTok，再重新打开并搜索引流目标关键词")
            if (!reopenTikTokAndOpenMutualTeamworkVideosTab(service, config, "互点模式启动")) {
                return false
            }
            val enteredVideo = enterVideosVideoByQuarterTaps(service, config)
            if (!enteredVideo) {
                devOp(alias, "互点模式：Videos 结果区互动未完成")
                return false
            }
            finalizeMutualSessionLimitedExit(alias)
            return true
        } finally {
            PrefsStore.setMutualCommentOnlySession(false)
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

    private suspend fun ensureUsersTabClicked(service: Context): Point? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (hardStopOrNull(alias, "查找 Users 标签")) return null
        // 简化版本：只做一次（最多两次）OCR，不做稳定性多轮复核
        // 1) 先尝试快速顶部区域 OCR
        val fastAnchor = findUsersAnchorByOcrFast(service)
        if (fastAnchor != null) {
            runSuCommand("input tap ${fastAnchor.x} ${fastAnchor.y}")
            return fastAnchor
        }
        val fallbackAnchor = findUsersAnchorByOcr(service)
        if (fallbackAnchor != null) {
            devOp(alias, "OCR 定位 Users 标签（兜底），点击")
            runSuCommand("input tap ${fallbackAnchor.x} ${fallbackAnchor.y}")
            return fallbackAnchor
        }
        devOp(alias, "OCR 未找到 Users 标签")
        return null
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

        devOp(alias, "Followers：两次 tap 各附 2 轮 OCR 均未通过，判定页面混乱，执行关闭并重新打开 TikTok")
        val restarted = restartTikTokForRecovery()
        devOp(alias, "涨粉 TikTok 恢复重启结果：${if (restarted) "success" else "failed"}")
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
                "互点 Followers：三轮 OCR 仍未命中粉丝列表，判定页面错乱，执行关闭并重新打开 TikTok",
            )
            val restarted = restartTikTokForRecovery()
            devOp(alias, "互点 TikTok 恢复重启结果：${if (restarted) "success" else "failed"}")
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

    /**
     * 发作品全部成功后：删除 [PUBLISH_POST_PICTURES_PATH] 下所有文件（含子目录、隐藏文件如 .thumbnails/.nomedia）。
     * 主路径 while read + rm（兼容中文/空格）；再 find -delete 与删空目录兜底。
     */
    private suspend fun publishPostDeleteAllPicturesAfterSuccess(alias: String) {
        withContext(Dispatchers.IO) {
            val quotedRoot = shellEscape(PUBLISH_POST_PICTURES_PATH)
            val before = runSuCommandWithOutput("find $quotedRoot -type f 2>/dev/null | wc -l")
                ?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull() ?: -1
            if (before <= 0) {
                devOp(alias, "发作品：任务完成，Pictures 下无文件需删除（before=$before）")
                return@withContext
            }
            runSuCommand(
                "find $quotedRoot -type f 2>/dev/null | while IFS= read -r f; do rm -f \"\$f\"; done",
            )
            runSuCommand("find $quotedRoot -type f 2>/dev/null -delete")
            runSuCommand("find $quotedRoot -type d -empty 2>/dev/null -delete")
            val after = runSuCommandWithOutput("find $quotedRoot -type f 2>/dev/null | wc -l")
                ?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull() ?: -1
            val removed = if (before >= 0 && after >= 0) (before - after).coerceAtLeast(0) else before
            devOp(
                alias,
                "发作品：任务完成，已删除 Pictures 下约 $removed 个文件（删除前=$before 删除后=$after）",
            )
        }
    }

    private fun publishPostCountPicturesImageVideoViaSu(): Pair<Int, Int> {
        val images = publishPostCountPicturesMediaFilesViaSu(PUBLISH_POST_PICTURES_IMAGE_EXTS)
        val videos = publishPostCountPicturesMediaFilesViaSu(PUBLISH_POST_PICTURES_VIDEO_EXTS)
        if (images < 0 || videos < 0) {
            val alias = PrefsStore.getAlias() ?: "unknown"
            devOp(
                alias,
                "发作品：su find 统计 Pictures 失败（images=$images videos=$videos），按 0 处理",
            )
            return 0 to 0
        }
        return images to videos
    }

    override fun countPicturesGalleryImageFiles(): Int {
        if (PUBLISH_POST_PICTURES_IMAGE_EXTS.isEmpty()) return 0
        val quotedRoot = shellEscape(PUBLISH_POST_PICTURES_PATH)
        val findParts = PUBLISH_POST_PICTURES_IMAGE_EXTS.joinToString(" ; ") { ext ->
            "find $quotedRoot -type f ! -path '*/.*' -iname '*.$ext'"
        }
        val cmd = "{ $findParts; } 2>/dev/null | wc -l"
        val out = runSuCommandWithOutput(cmd)?.trim().orEmpty()
        return out.lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: -1
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

    private fun getTopPackageNameBySu(): String {
        val out = runSuCommandWithOutput("dumpsys window | grep mCurrentFocus")
            ?: runSuCommandWithOutput("dumpsys activity activities | grep mResumedActivity")
            ?: ""
        return out
    }

    private fun isPackageVisible(packageName: String): Boolean {
        val top = getTopPackageNameBySu()
        return top.contains(packageName, ignoreCase = true)
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
            // 页面错乱：直接重启 TikTok，等待恢复后让上层跳过当前条目，进入下一轮。
            val restarted = restartTikTokForRecovery()
            devOp(alias, "TikTok 强制重启结果：${if (restarted) "success" else "failed"}")
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        if (hardStopOrNull(alias, "处理粉丝列表")) return
        var reachedEnd = false
        var rounds = 0
        // 主循环：固定最多 7 轮；若中途判定到底则提前结束
        while (!reachedEnd && rounds < 7) {
            if (mutualClickOrNull(alias, "处理粉丝列表循环")) {
                return
            }
            val followers = findFollowersEntriesByOcr(service)
            if (followers.isEmpty()) {
                // 已进入 Followers 流程后，整屏 OCR 却 0 条可点行：视为页面错乱/非列表态，不应当作正常结束回 Users。
                LogCenter.append(alias, "粉丝列表 OCR 未命中任何用户，判定页面异常，执行重启 TikTok")
                val restarted = restartTikTokForRecovery()
                devOp(alias, "TikTok 强制重启结果：${if (restarted) "success" else "failed"}")
                if (restarted) {
                    delay(3000)
                }
                throw GrowthFlowRestartRequired("粉丝列表 OCR 未命中条目，页面异常，已执行重启 TikTok")
            }
            for (entry in followers) {
                runSuCommand("input tap ${entry.clickPoint.x} ${entry.clickPoint.y}")
                delay(Random.nextLong(1200, 1600))
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
        val screenshotFile = File(service.cacheDir, "followers_end_check.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return false
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return false
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
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
            reachedEnd
        } catch (ex: Exception) {
            LogCenter.append(alias, "粉丝列表到底 OCR 判定失败：${ex.message}")
            false
        } finally {
            recognizer.close()
            bitmap.recycle()
            screenshotFile.delete()
        }
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
        if (hardStopOrNull(alias, "识别粉丝列表")) return emptyList()
        val screenshotFile = File(service.cacheDir, "followers_ocr.png")
        if (!runSuCommand("screencap -p ${screenshotFile.absolutePath}")) return emptyList()
        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath) ?: run {
            screenshotFile.delete()
            return emptyList()
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
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
            entries
        } catch (ex: GrowthFlowRestartRequired) {
            throw ex
        } catch (ex: Exception) {
            LogCenter.append(alias, "粉丝列表 OCR 识别失败：${ex.message}")
            emptyList()
        } finally {
            recognizer.close()
            bitmap.recycle()
            screenshotFile.delete()
        }
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
        } catch (_: Exception) {
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
            devOp(alias, "互点：已达粉丝列表 Follow 上限，跳过评论框粘贴与用户名回传")
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
        } catch (e: Exception) {
            
            UserListOcrAttemptResult(null, false, "")
        } finally {
            recognizer.close()
            bitmap.recycle()
            croppedBitmap.recycle()
            screenshotFile.delete()
        }
    }

    /**
     * Users 列表 OCR 失败恢复：force-stop 重启 TikTok 后，与主流程一致地重新打开搜索并切到 Users 标签。
     */
    private suspend fun reopenSearchAndEnterUsersTab(
        service: Context,
        username: String,
        isLiveModeForUsersCheck: Boolean,
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (username.trim() == "__mutual_switch__" || username.trim() == "__publish_post__") {
            devOp(alias, "检测到互点切换或发作品占位用户名，禁止走 Users OCR 恢复")
            return false
        }
        delay(500)
        if (!clickMagnifier(service)) {
            devOp(alias, "Users OCR 恢复：TikTok 恢复拉起失败，无法点击放大镜")
            return false
        }
        val safeUsername = shellEscape(username)
        devOp(alias, "Users OCR 恢复：输入搜索用户名：$username")
        if (!ensureTikTokInForeground(service, alias, "Users OCR 恢复输入")) {
            devOp(alias, "Users OCR 恢复：TikTok 恢复拉起失败，无法输入搜索词")
            return false
        }
        val inputResult = runSuCommand("input text $safeUsername")
        if (!inputResult) {
            devOp(alias, "Users OCR 恢复：输入搜索词失败")
            return false
        }
        delay(Random.nextLong(800, 1500))
        if (!clickSearchButtonAndWait(service)) {
            devOp(alias, "Users OCR 恢复：TikTok 恢复拉起失败，无法点击 Search")
            return false
        }
        ensureUsersTabClicked(service)
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
     * 连续 3 次未匹配：关闭并重启 TikTok、重新进入搜索与 Users 列表，再开启**新的一段**（仍最多 3 次 OCR）；整段流程最多执行 **1 次**此类重启，避免死循环。
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

        while (true) {
            if (consecutiveFailures >= 3) {
                if (didRestartAfterTripleMiss) {
                    devOp(alias, "Users 列表 OCR：已连续 3 次未匹配且已执行过一次重启，结束匹配")
                    break
                }
                devOp(alias, "Users 列表 OCR 连续 3 次未匹配，关闭并重启 TikTok 后重新进入搜索与 Users 列表")
                restartTikTokForRecovery()
                if (!reopenSearchAndEnterUsersTab(service, username, isLiveModeForUsersCheck)) {
                    return null
                }
                didRestartAfterTripleMiss = true
                consecutiveFailures = 0
                ocrInSegment = 0
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

}
