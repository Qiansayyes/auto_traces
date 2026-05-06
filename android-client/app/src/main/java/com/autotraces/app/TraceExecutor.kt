package com.autotraces.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import com.autotraces.app.util.Config
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.concurrent.thread
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

class GrowthFlowRestartRequired(message: String) : RuntimeException(message)

interface TraceExecutor {
    suspend fun executeForUsername(username: String, config: AppConfig): String
    suspend fun restartTikTokForRecovery(): Boolean = false
}

class RealTraceExecutor(
    private val appContextProvider: () -> Context,
) : TraceExecutor {

    private fun isHardStopActive(): Boolean = PrefsStore.isHardStopRequested()

    private fun hardStopOrNull(alias: String, action: String): Boolean {
        if (!isHardStopActive()) return false
        devOp(alias, "$action：检测到硬停，直接跳过")
        return true
    }

    private inline fun <T> withTikTokGuard(alias: String, action: String, block: () -> T): T? {
        if (hardStopOrNull(alias, action)) return null
        return block()
    }

    private data class SuCommand(val path: String, val useShell: Boolean)

    private var cachedSuCommand: SuCommand? = null
    private var lastConfigSnapshot: AppConfig = AppConfig()
    // 只要当前用户名流程中发生过重启 TikTok，下一轮就必须从头启动，禁止复用 Users。
    private var didRestartTikTokInCurrentRun: Boolean = false
    // 仅 growth：发生恢复重启后，下一轮从“点击放大镜”重新开始（不复用 Users，不再二次拉起 App）。
    private var restartFromMagnifierForGrowth: Boolean = false

    companion object {
        private const val FRAME_DIFF_PASS_THRESHOLD = 0.3f
    }

    private fun buildTikTokLaunchIntent(ctx: Context): Intent? {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val pm = ctx.packageManager
        var intent = pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            intent = Intent(Intent.ACTION_MAIN).apply {
                setPackage(pkg)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            if (pm.resolveActivity(intent, 0) == null) return null
        }
        return intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private suspend fun launchTikTokOnceWithCheck(
        ctx: Context,
        alias: String,
        tag: String,
        launchWaitMs: Long,
    ): Boolean {
        val pkg = Config.TIKTOK_PACKAGE_NAME
        val intent = buildTikTokLaunchIntent(ctx)
        if (intent != null) {
            try {
                ctx.startActivity(intent)
                delay(launchWaitMs)
                if (isPackageVisible(pkg)) {
                    return true
                }
                devOp(alias, "$tag：startActivity 已调用但前台包名未命中")
            } catch (ex: Exception) {
                devOp(alias, "$tag：startActivity 启动 TikTok 异常：${ex.message}")
            }
        } else {
            devOp(alias, "$tag：启动失败：应用不可用")
        }

        val monkeyOk = runSuCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        if (!monkeyOk) {
            devOp(alias, "$tag：monkey 启动 TikTok 失败")
            return false
        }
        delay(launchWaitMs)
        val visible = isPackageVisible(pkg)
        devOp(alias, "$tag：monkey 启动后前台包名${if (visible) "命中" else "未命中"}")
        return visible
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
            val ok = launchTikTokOnceWithCheck(ctx, alias, tag, launchWaitMs)
            if (ok) {
                devOp(alias, "$tag：重新打开 TikTok", System.currentTimeMillis() - t0)
            }
            return ok
        }

        devOp(
            alias,
            "关闭 TikTok 后台（forceStopWaitMs=${forceStopWaitMs}ms, launchWaitMs=${launchWaitMs}ms）",
        )
        runSuCommand("am force-stop $pkg")
        delay(forceStopWaitMs)

        val firstOk = launchOnce("首次启动") && isPackageVisible(pkg)
        devOp(alias, "重启 TikTok：首次启动结果=${if (firstOk) "成功" else "失败"}")
        if (firstOk) {
            return true
        }

        devOp(alias, "首次启动未成功，执行二次兜底重启")
        runSuCommand("am force-stop $pkg")
        delay(forceStopWaitMs)
        val secondOk = launchOnce("二次启动") && isPackageVisible(pkg)
        devOp(alias, "重启 TikTok：二次启动结果=${if (secondOk) "成功" else "失败"}")
        return secondOk
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

    /** 设备端实时日志：只记录当前操作，可选附带耗时（毫秒）。 */
    private fun devOp(alias: String, action: String, elapsedMs: Long? = null) {
        val root = currentRootUsernameForLogs.trim()
        val prefix = if (root.isNotEmpty()) "[$root] " else ""
        LogCenter.op(alias, "$prefix$action", elapsedMs)
    }

    /** 统一返回动作日志：所有 back/keyevent 4 均使用“导航返回”。 */
    private fun navBack(alias: String, target: String) {
        devOp(alias, "导航返回：$target")
        runSuCommand("input keyevent 4")
    }

    private suspend fun runForYouWarmupIfNeeded(service: Context, alias: String, config: AppConfig) {
        val totalMs = (config.for_you_swipe_total_sec * 1000.0).toLong().coerceAtLeast(0L)
        if (totalMs < 500L) return

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
        didRestartTikTokInCurrentRun = false
        currentRootUsernameForLogs = username
        val runMode = config.run_mode.trim().lowercase()
        if (runMode == ServerRunMode.GROWTH && restartFromMagnifierForGrowth) {
            devOp(alias, "涨粉模式：重启后从点击放大镜重新开始")
            restartFromMagnifierForGrowth = false
            delay(500)
            clickMagnifier(service)
        } else if (lastEndedAtUsersSelection) {
            devOp(alias, "复用 Users 选择界面，跳过重启 TikTok")
            focusSearchInput(service)
            clearSearchInputText()
            delay(Random.nextLong(400, 700))
        } else {
            var launched = false
            for (idx in 0 until 2) {
                val tag = "打开 TikTok 第 ${idx + 1} 次"
                val launchWaitMs = config.tiktok_launch_wait_ms.coerceIn(800L, 10000L)
                if (launchTikTokOnceWithCheck(ctx, alias, tag, launchWaitMs)) {
                    launched = true
                    break
                }
                devOp(alias, "$tag：结果=失败")
                if (idx == 0) {
                    devOp(alias, "打开 TikTok：准备重试第 2 次")
                    delay(900)
                }
            }
            if (!launched) {
                devOp(alias, "打开 TikTok 失败：多次判活未通过")
                return "TikTok 启动失败（多次重试仍未进入前台），无法执行 $username"
            }
            delay(500)
            runForYouWarmupIfNeeded(service, alias, config)
            clickMagnifier(service)
        }

        val safeUsername = shellEscape(username)
        val inputResult = runSuCommand("input text $safeUsername")
        if (!inputResult) {
            devOp(alias, "输入搜索词失败")
            return "输入用户名失败，未执行 Search"
        }
        delay(Random.nextLong(800, 1500))

        // 4. 通过坐标点击 Search 按钮（root input tap）
        clickSearchButtonAndWait(service)

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
            ServerRunMode.GROWTH -> {
                devOp(alias, "涨粉模式：在用户主页，准备点击 Followers")
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

    private suspend fun clickMagnifier(service: Context) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        withTikTokGuard(alias, "点击放大镜") {
            val metrics = service.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val ratioX = 0.92f
            val ratioY = 0.09f
            val clickX = width * ratioX
            val clickY = height * ratioY
            runSuCommand("input tap ${clickX.toInt()} ${clickY.toInt()}")
            delay(Random.nextLong(800, 1200))
        }
    }

    private suspend fun clickSearchButtonAndWait(service: Context) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        withTikTokGuard(alias, "点击 Search") {
            val metrics = service.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val searchRatioX = 0.92f
            val searchRatioY = 0.09f
            val searchX = width * searchRatioX
            val searchY = height * searchRatioY
            runSuCommand("input tap ${searchX.toInt()} ${searchY.toInt()}")
            delay(2500)
        }
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


    private fun distancePx(a: Point, b: Point): Int {
        val dx = (a.x - b.x)
        val dy = (a.y - b.y)
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
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

        var attempts = 0
        while (attempts < 3) {
            val clicked = clickFollowersRoot(service)
            if (!clicked) {
                attempts++
                continue
            }

            // 网络慢时 Followers 列表可能延迟渲染：单次点击后轮询 OCR 多次再判失败。
            var verified = false
            val ocrPollRounds = 7
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

            devOp(alias, "OCR 未通过：本次点击轮询 ${ocrPollRounds} 次未命中，重试 Followers（第 ${attempts + 1} 次）")
            attempts++
        }

        devOp(alias, "Followers：OCR 连续失败，已重试 3 次仍未进入粉丝列表")
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

    private fun runSuCommand(command: String): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val suCommand = resolveSuCommand(alias)
        if (suCommand == null) {
            
            return false
        }
        return try {
            val execArgs = if (suCommand.useShell) {
                arrayOf("sh", "-c", "${suCommand.path} -c \"$command\"")
            } else {
                arrayOf(suCommand.path, "-c", command)
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

    private fun runSuCommandWithOutput(command: String): String? {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val suCommand = resolveSuCommand(alias) ?: return null
        return try {
            val execArgs = if (suCommand.useShell) {
                arrayOf("sh", "-c", "${suCommand.path} -c \"$command\"")
            } else {
                arrayOf(suCommand.path, "-c", command)
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
            if (exitCode == 0) suCommand else null
        } catch (_: Exception) {
            null
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
    ): Boolean {
        val motion = if (beforeTapReference != null) {
            detectVideoMotionByFrameDiffFromBeforeTap(service, beforeTapReference, afterTapDelayMs, motionTag)
        } else {
            detectVideoMotionByFrameDiff(
                service,
                debugTag = sanitizeFrameDiffTagForFilename(motionTag),
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

        
        runLivePostActions(service, config, modeLabel, isDirectMode)
        

        devOp(alias, "互动完成，返回 Users 搜索")
        backToUsersSelectionFromProfile(service)
        focusSearchInput(service)
        clearSearchInputText()
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
        beforeTapReference: File,
        afterTapDelayMs: Long = 1000L,
        tag: String = "前后帧差",
    ): Boolean {
        val alias = PrefsStore.getAlias() ?: "unknown"
        if (!beforeTapReference.exists()) {
            return detectVideoMotionByFrameDiff(
                service,
                debugTag = "fb_${sanitizeFrameDiffTagForFilename(tag)}",
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
        } catch (ex: Exception) {
            
            return false
        } finally {
            afterTap.delete()
        }
    }

    private suspend fun detectVideoMotionByFrameDiff(
        service: Context,
        debugTag: String = "no_before_ref",
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
        } catch (ex: Exception) {
            
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
            LogCenter.append(alias, "粉丝列表准备下滑：distance=${scrollDistance.toInt()} startY=${startY.toInt()} endY=${endY.toInt()}")
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

    private data class FollowerEntry(val clickPoint: Point, val rowHeight: Int)

    /**
     * @param verificationOnly 为 true 时用于「是否已进入粉丝列表」的快速判定：误入搜索根 Tab 只返回空列表，
     * 不触发 TikTok 重启（避免在 clickFollowersAndVerify 重试环内误重启）。
     */
    private suspend fun findFollowersEntriesByOcr(
        service: Context,
        verificationOnly: Boolean = false,
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
                val clickX = (box.left - (box.width() * 1.2f)).toInt().coerceAtLeast(1)
                val clickY = box.centerY().coerceAtLeast(1)
                if (clickY < bitmap.height * 0.18f) {
                    null
                } else {
                    FollowerEntry(Point(clickX, clickY), estimatedRowHeight)
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
        val alias = PrefsStore.getAlias() ?: "unknown"
        val mode = config.run_mode.trim().lowercase()
        
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
        val safeText = shellEscape(cleanInputText(text))
        val typed = runSuCommand("input text $safeText")
        if (!typed) {
            devOp(alias, "评论文本 root 输入失败，已跳过发送")
            return
        }
        delay(500)

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
     * 涨粉模式（growth）：原先用 OCR 找「Add comment...」评论框再点击的方案已停用，
     * 改为固定比例坐标 (0.92×屏宽, 0.68×屏高) 打开评论输入。
     * live/direct 仍走 [performCommentText] 中的 OCR/诊断路径（direct 实际使用 [performCommentTextDirectFixed]）。
     */
    private suspend fun performCommentTextGrowthFixedCoordinate(service: Context, config: AppConfig) {
        val alias = PrefsStore.getAlias() ?: "unknown"
        val metrics = service.resources.displayMetrics
        val commentX = (metrics.widthPixels * 0.92f).toInt().coerceIn(1, metrics.widthPixels - 1)
        val commentY = (metrics.heightPixels * 0.68f).toInt().coerceIn(1, metrics.heightPixels - 1)
        val t0 = System.currentTimeMillis()
        devOp(alias, "点击评论按钮（涨粉固定坐标）")
        runSuCommand("input tap $commentX $commentY")
        delay(900)

        val text = config.comment_texts.randomOrNull() ?: "Nice"
        val safeText = shellEscape(text)
        devOp(alias, "root 一次性输入评论文案：$text")
        val typed = runSuCommand("input text $safeText")
        if (!typed) {
            devOp(alias, "评论文本 root 输入失败，已跳过发送")
            return
        }
        delay(500)

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
        val safeText = shellEscape(text)
        val typed = runSuCommand("input text $safeText")
        if (!typed) {
            devOp(alias, "评论文本 root 输入失败，已跳过发送")
            return
        }
        delay(500)

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
        return text.replace("'", "")
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
        delay(500)
        clickMagnifier(service)
        val safeUsername = shellEscape(username)
        devOp(alias, "Users OCR 恢复：输入搜索用户名：$username")
        val inputResult = runSuCommand("input text $safeUsername")
        if (!inputResult) {
            devOp(alias, "Users OCR 恢复：输入搜索词失败")
            return false
        }
        delay(Random.nextLong(800, 1500))
        clickSearchButtonAndWait(service)
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
