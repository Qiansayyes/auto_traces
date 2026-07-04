#!/usr/bin/env python3
"""Split AppCoordinator.kt into coordinator/ modules (extension on CoordinatorCore)."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/autotraces/app/AppCoordinator.kt"
ORIG = ROOT.parent / "android-client/app/src/main/java/com/autotraces/app/AppCoordinator.kt"
OUT_DIR = ROOT / "app/src/main/java/com/autotraces/app/coordinator"

# function name -> target file (without .kt)
MODULE_BY_FUNC: dict[str, str] = {}

def assign(prefix: str, names: list[str]) -> None:
    for n in names:
        MODULE_BY_FUNC[n] = prefix

assign("CoordinatorText", [
    "prettyTemplateName", "prettyRunMode", "queueRunMode", "configSummaryLine",
])
assign("CoordinatorRuntime", ["updateRuntime"])
assign("ActivityProbe", [
    "isPublishPostSwitchActive", "isAutoSwitchPublishLocallyActive", "isMutualSwitchActive",
    "isBatchStreamActive", "isAutoLoginActive", "canStartPullLoopNow",
    "isPullLoopJobCancellation",
])
assign("TikTokExclusiveController", [
    "pauseGrowthForAutoSwitchExclusiveOp", "beginAutoSwitchExitShutdown",
    "releaseAutoSwitchExclusivePreemptIfIdle", "maybeStartGrowthInAutoSwitchIdle",
    "isAutoSwitchExclusiveWorkActuallyActive", "recoverStaleAutoSwitchExclusiveState",
    "runExecutorSu", "runExecutorSuWithOutput", "queryTikTokProcessIds",
    "forceStopTikTokPackageAndVerify", "forceStopTikTokForExclusiveEnqueue",
    "ensureTikTokGalleryPermissionIfNeeded", "tiktokGalleryRuntimePermissions",
    "isTikTokGalleryPermissionGranted", "forceStopTikTokCleanly",
    "ensureTikTokGalleryPermissionGrantedIfNeeded",
])
assign("AutoSwitchCoordinator", [
    "noteCloudAutoSwitchEnabled", "resolveCloudAutoSwitchEnabledFromModeWait",
    "isCloudAutoSwitchDisabled", "reconcileCloudAutoSwitchState",
    "performAutoSwitchExitShutdown", "resumeAutoSwitchFromLocalPlan",
    "fetchAutoSwitchBundleFromCloud", "recoverAutoSwitchAutonomousWork",
    "resumeAutoSwitchGrowthLightweight", "recoverAutoSwitchAutonomousWorkInner",
    "logAutoSwitchRecoverySnapshot", "cancelAutoSwitchBundleRetry",
    "isAutoSwitchBundlePermanentFailure", "tryLoadAutoSwitchBundleForEnter",
    "finalizeAutoSwitchEnterAfterBundleLoaded", "scheduleAutoSwitchBundleEnterRetry",
    "handleDeviceModeEvent", "applyAutoSwitchBundle", "refreshAutoSwitchWeeklyBundleFromCloud",
    "handleAutoSwitchEnterResync", "handleAutoSwitchSlotTransition",
    "tryResumeStaleAutoSwitchPublish", "resetStalePublishInflightForRetry",
    "maybeRunDailyAutoSwitchReconcile", "uploadAutoSwitchSessionSummary",
    "tryUploadPendingAutoSwitchSummary", "enterPublishPostExclusivePhase",
    "verifyPublishInsertionLoginBeforeEnqueue", "reportAutoSwitchPublishOutcomeIfNeeded",
])
assign("CutSwitchCoordinator", [
    "isLocalAutoSwitchOpId", "isAutoSwitchCutSwitchRetryUntilSuccess",
    "delayAutoSwitchCutSwitchRetry", "isAutoSwitchCutSwitchRestore",
    "isCutSwitchMissingBackupFailure", "cutSwitchMissingBackupUserMessage",
    "cutSwitchUserLogMessage", "isCutSwitchFailureRetryable",
    "delayAutoSwitchCutSwitchRetryIfRetryable", "cutSwitchBackupDirPath",
    "describeMissingRestoreBackup", "scheduleBackupDirectoryReconcile",
    "runBackupDirectoryReconcile", "notifyAutoSwitchCutSwitchStepFailed",
    "finishAutoSwitchCutSwitchAfterSuccess", "reportAutoSwitchCutSwitchBackupImmediately",
    "updateLocalBundleBackupPath", "uploadCutSwitchBackupToCloudNow",
    "flushDeferredCutSwitchBackupUploads", "enqueueDeferredCutSwitchBackupUpload",
])
assign("CloudStatusSync", [
    "syncOnline", "syncRunningToCloud", "syncCloudStatus", "reportAutoStartOptOutIfNeeded",
    "flushCloudStatusAfterAutonomousExit", "syncStatusToCloud", "restoreCloudAutoStartParticipation",
])
assign("CloudConfigSync", [
    "fetchConfig", "isAutoSwitchBundledConfigSynced", "applyBundledConfigFromAutoSwitch",
])
assign("CloudPollingLoops", [
    "startCheckLoop", "startModeWaitLoop", "startDeviceOpsLoop", "ensureDeviceOpsLoopIfBound",
    "uploadDeviceLogsIfPullRequested", "uploadDeviceLogsIfRequested",
])
assign("GrowthPullCoordinator", [
    "startPullLoop", "runMutualTrafficPullLoopIteration", "fetchUsernamesOnce",
    "reportOnlineBeforePull", "logFailedSummaryIfAny", "scheduleFailureReport",
    "handleUsername", "runGrowthTaskForUsername",
])
assign("DeviceOpCoordinator", [
    "handleDeviceOp", "resumeExecutionForCloudDeviceOp", "prepareForDestructiveTikTokOp",
    "prepareForExclusiveTikTokUiOp", "isBasicProfileModificationPending",
    "recordProfileEditRequiredIfNeeded", "maybeRetryPendingBasicProfileEditAfterExclusive",
    "maybeRunPendingBasicProfileEditAfterLoginVerified", "withDeviceOpReportFlags",
    "awaitExecutionScopeIdleForAutoSwitch", "awaitAutoSwitchExclusiveDrain",
    "reportDeviceOpToCloud", "handleGalleryImportOp", "ensureLoggedInBeforeExclusiveTask",
    "ensureAccountLoggedIn", "runProfileEditAfterCutSwitch", "resolveProfileEditAvatarMediaItem",
    "importGalleryMediaItemToDevice", "profileEditOuterChainFullyComplete",
    "profileEditOverallSuccessFromOuterRounds",
])
assign("ExclusiveTaskRunner", [
    "triggerMutualSwitchNow", "triggerPublishPostNow", "runBatchStreamSession",
    "runProfileEditChainOnce", "finalizeMutualSession", "runMutualClickSession",
])
assign("ReportCoordinator", [
    "reportWithRetry", "enqueuePendingReports", "scheduleFailureReport",
])
assign("CoordinatorLifecycle", [
    "init", "bootstrapAfterBinding", "startIfNeeded", "forceStartRunningNow",
    "ensureLoopsRunning", "onWorkerServiceChanged", "stopRunningState",
    "setManualPaused", "isManualPaused", "requestMutualClickSwitch",
    "shouldSwitchToMutualClick", "markMutualClickSwitched", "requestUserGracefulHardStop",
    "stopWorkForServiceDestroy", "stopAllWork", "stopExecutionOnly", "refreshRuntimeSnapshot",
    "isInPullUsernameRestSleep",
])

# default bucket for unassigned
DEFAULT_MODULE = "CoordinatorMisc"

IMPORTS = """package com.autotraces.app.coordinator

import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.autotraces.app.*
import com.autotraces.app.backup.BackupDirectoryReconciler
import com.autotraces.app.backup.DeviceBackupShell
import com.autotraces.app.backup.UnifiedBackupPaths
import com.autotraces.app.backup.model.BackupRecord
import com.autotraces.app.backup.network.BackupReporter
import com.autotraces.app.util.Config
"""

FUNC_START = re.compile(
    r"^    ((?:private |internal )?(?:suspend )?fun (?:(\w+)|(\w+)<)|"
    r"(?:private |internal )?val \w+ = ::\w+)"
)

def skip_function(body_lines: list[str], start: int) -> int:
    brace = 0
    started = False
    j = start
    n = len(body_lines)
    while j < n:
        for ch in body_lines[j]:
            if ch == "{":
                brace += 1
                started = True
            elif ch == "}":
                brace -= 1
        if started and brace == 0:
            return j + 1
        j += 1
    return n


def extract_state_members(_body_lines: list[str]) -> list[str]:
    """从 android-client 原文件按行号提取成员属性（避免与 fun 交错误判）。"""
    lines = ORIG.read_text(encoding="utf-8").splitlines(keepends=True)
    chunks = lines[75:222] + lines[976:988]  # 1-based 76-222, 977-988
    result: list[str] = []
    for cl in chunks:
        cl = cl.replace("private val ", "internal val ")
        cl = cl.replace("private var ", "internal var ")
        cl = cl.replace("private const val ", "internal const val ")
        cl = cl.replace("private lateinit var ", "internal lateinit var ")
        result.append(cl)
    return result


def find_functions(lines: list[str]) -> list[tuple[int, int, str]]:
    """Return (start_line_0idx, end_line_0idx_exclusive, func_name)."""
    result: list[tuple[int, int, str]] = []
    i = 0
    n = len(lines)
    func_header = re.compile(
        r"^    ((?:private |internal )?(?:suspend )?fun (\w+))"
    )
    while i < n:
        m = func_header.match(lines[i])
        if m:
            name = m.group(2)
            # skip nested fun inside other fun - detect indent > 4 on next meaningful line
            # AppCoordinator uses 8 spaces for nested fun - check if line starts with 8+ spaces fun
            if i > 0:
                prev = lines[i - 1].strip()
                if prev.endswith("{") and "fun " in lines[i - 1]:
                    i += 1
                    continue
            start = i
            brace = 0
            started = False
            j = i
            while j < n:
                line = lines[j]
                # stop at next top-level member (4 spaces + fun/val/var/private val)
                if j > start and re.match(r"^    (private |@|val |var |fun |suspend fun )", line):
                    if not re.match(r"^        ", line):
                        break
                for ch in line:
                    if ch == "{":
                        brace += 1
                        started = True
                    elif ch == "}":
                        brace -= 1
                if started and brace == 0:
                    j += 1
                    break
                j += 1
            result.append((start, j, name))
            i = j
            continue
        i += 1
    return result

def main() -> None:
    text = ORIG.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)

    # locate object body
    obj_start = next(i for i, l in enumerate(lines) if l.startswith("object AppCoordinator"))
    obj_open = obj_start
    for i in range(obj_start, min(obj_start + 5, len(lines))):
        if "{" in lines[i]:
            obj_open = i
            break
    obj_close = len(lines) - 1
    for i in range(len(lines) - 1, obj_start, -1):
        if lines[i].strip() == "}":
            obj_close = i
            break

    body_lines = lines[obj_open + 1 : obj_close]

    state_lines = extract_state_members(body_lines)
    # drop duplicate public config/runtimeState — facade exposes flows
    state_lines = [
        l for l in state_lines
        if not re.match(r"^    val (config|runtimeState) ", l)
    ]

    functions = find_functions(body_lines)
    print(f"Found {len(functions)} top-level functions")

  # group
    modules: dict[str, list[tuple[int, int, str, list[str]]]] = {}
    unassigned: list[str] = []
    for start, end, name in functions:
        mod = MODULE_BY_FUNC.get(name, DEFAULT_MODULE)
        if mod == DEFAULT_MODULE and name not in MODULE_BY_FUNC:
            unassigned.append(name)
        chunk = body_lines[start:end]
        # rewrite as extension on CoordinatorCore
        rewritten = []
        for idx, line in enumerate(chunk):
            if idx == 0:
                line = re.sub(
                    r"^    ((?:private |internal )?)(suspend )?fun ",
                    r"internal \2fun CoordinatorCore.",
                    line,
                )
            rewritten.append(line)
        modules.setdefault(mod, []).append((start, end, name, rewritten))

    if unassigned:
        print("Unassigned functions -> CoordinatorMisc:", ", ".join(sorted(set(unassigned))))

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # CoordinatorCore.kt
    core_state = "".join(state_lines)
    # fix scheduler reference - keep in core
    core = f"""package com.autotraces.app.coordinator

import android.content.Context
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import com.autotraces.app.*
import com.autotraces.app.TraceExecutor
import com.autotraces.app.backup.UnifiedBackupPaths

/**
 * 共享可变状态与 [AutoSwitchLocalScheduler] 实例。
 * 各 coordinator 模块以 extension 形式挂在此对象上。
 */
internal object CoordinatorCore {{
{"".join(state_lines)}
    val configFlow = _config.asStateFlow()
    val runtimeStateFlow = _runtimeState.asStateFlow()

    internal val destructiveTikTokOps = setOf(
        "tiktok_clear",
        "tiktok_backup_and_clear",
        "tiktok_restore",
    )

    internal val autoSwitchLedgerOpTypes = setOf(
        "tiktok_clear",
        "tiktok_backup_and_clear",
        "tiktok_restore",
    )

    internal val exclusiveTikTokUiOps = setOf(
        "tiktok_edit_basic_profile",
    )
}}
"""
    (OUT_DIR / "CoordinatorCore.kt").write_text(core, encoding="utf-8")

    # shared nested types used across modules
    types_kt = '''package com.autotraces.app.coordinator

internal data class AutoSwitchBundleNetworkResult(
    val loaded: Boolean,
    val bundle: AutoSwitchBundle? = null,
    val msg: String = "",
    val cloudDisabled: Boolean = false,
    val skippedNetwork: Boolean = false,
)

internal data class AutoSwitchBundleFetchOutcome(
    val loaded: Boolean,
    val msg: String = "",
)

internal enum class CloudStatusSyncKind {
    LIFECYCLE,
    PULL_LOOP,
    DEVICE_OP,
}
'''
    (OUT_DIR / "CoordinatorTypes.kt").write_text(types_kt, encoding="utf-8")

    for mod, chunks in sorted(modules.items()):
        parts = [IMPORTS, "\n", f"// {mod}: split from AppCoordinator\n"]
        for _, _, name, rewritten in chunks:
            parts.append("".join(rewritten))
            parts.append("\n")
        (OUT_DIR / f"{mod}.kt").write_text("".join(parts), encoding="utf-8")
        print(f"Wrote {mod}.kt ({len(chunks)} functions)")

    # Append PendingReportStore if present in source
    if "object PendingReportStore" in text:
        pending = text[text.index("object PendingReportStore"):].replace(
            "object PendingReportStore",
            "internal object PendingReportStore",
            1,
        )
        rp = OUT_DIR / "PendingReportStore.kt"
        rp.write_text("package com.autotraces.app.coordinator\n\n" + pending, encoding="utf-8")

    # Thin AppCoordinator facade
    public_funcs = [
        "init", "bootstrapAfterBinding", "startIfNeeded", "forceStartRunningNow",
        "ensureTikTokGalleryPermissionIfNeeded", "syncOnline", "fetchConfig",
        "startCheckLoop", "reportWithRetry", "onWorkerServiceChanged", "stopRunningState",
        "setManualPaused", "isManualPaused", "requestMutualClickSwitch",
        "shouldSwitchToMutualClick", "markMutualClickSwitched", "requestUserGracefulHardStop",
        "stopWorkForServiceDestroy", "stopAllWork", "stopExecutionOnly", "refreshRuntimeSnapshot",
        "isInPullUsernameRestSleep",
    ]
    delegates = []
    for fn in public_funcs:
        if fn == "init":
            delegates.append(
                "    fun init(application: android.content.Context) = "
                "CoordinatorCore.init(application)"
            )
        elif fn in ("bootstrapAfterBinding", "syncOnline", "fetchConfig"):
            delegates.append(
                f"    suspend fun {fn}(vararg args: Any?) = "
                f"CoordinatorCore.{fn}(*args)"
            )
        else:
            delegates.append(f"    fun {fn}() = CoordinatorCore.{fn}()")

    # fix special signatures manually in facade file
    facade = '''package com.autotraces.app

import android.content.Context
import kotlinx.coroutines.flow.asStateFlow
import com.autotraces.app.coordinator.CoordinatorCore
import com.autotraces.app.coordinator.*

/**
 * APP 总协调器门面：对外 API 不变，实现已拆至 [com.autotraces.app.coordinator] 包。
 */
object AppCoordinator {
    val config get() = CoordinatorCore.configFlow
    val runtimeState get() = CoordinatorCore.runtimeStateFlow

    fun init(application: Context) = CoordinatorCore.init(application)

    suspend fun bootstrapAfterBinding() = CoordinatorCore.bootstrapAfterBinding()

    fun startIfNeeded() = CoordinatorCore.startIfNeeded()

    fun forceStartRunningNow(reason: String = "设备端启动后台服务") =
        CoordinatorCore.forceStartRunningNow(reason)

    fun ensureTikTokGalleryPermissionIfNeeded() =
        CoordinatorCore.ensureTikTokGalleryPermissionIfNeeded()

    suspend fun syncOnline(retries: Int = 1) = CoordinatorCore.syncOnline(retries)

    suspend fun fetchConfig(
        logToUser: Boolean = false,
        manual: Boolean = false,
    ) = CoordinatorCore.fetchConfig(logToUser, manual)

    fun startCheckLoop() = CoordinatorCore.startCheckLoop()

    fun reportWithRetry(message: String) = CoordinatorCore.reportWithRetry(message)

    fun onWorkerServiceChanged(isRunning: Boolean) =
        CoordinatorCore.onWorkerServiceChanged(isRunning)

    fun stopRunningState() = CoordinatorCore.stopRunningState()

    fun setManualPaused(paused: Boolean) = CoordinatorCore.setManualPaused(paused)

    fun isManualPaused(): Boolean = CoordinatorCore.isManualPaused()

    fun requestMutualClickSwitch() = CoordinatorCore.requestMutualClickSwitch()

    fun shouldSwitchToMutualClick(): Boolean = CoordinatorCore.shouldSwitchToMutualClick()

    fun markMutualClickSwitched() = CoordinatorCore.markMutualClickSwitched()

    fun requestUserGracefulHardStop() = CoordinatorCore.requestUserGracefulHardStop()

    fun stopWorkForServiceDestroy() = CoordinatorCore.stopWorkForServiceDestroy()

    fun stopAllWork(requestHardStop: Boolean = true) =
        CoordinatorCore.stopAllWork(requestHardStop)

    fun stopExecutionOnly(keepListenerReason: String = "后台服务已停止，保留云端指令监听") =
        CoordinatorCore.stopExecutionOnly(keepListenerReason)

    fun refreshRuntimeSnapshot() = CoordinatorCore.refreshRuntimeSnapshot()

    fun isInPullUsernameRestSleep(): Boolean = CoordinatorCore.isInPullUsernameRestSleep()
}
'''
    facade_path = ROOT / "app/src/main/java/com/autotraces/app/AppCoordinator.kt"
    facade_path.write_text(facade, encoding="utf-8")
    print(f"Wrote facade AppCoordinator.kt")


if __name__ == "__main__":
    main()
