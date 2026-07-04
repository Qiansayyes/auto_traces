package com.autotraces.app.autoswitch

import android.content.Context
import com.autotraces.app.ApiService
import com.autotraces.app.AutoSwitchBundle
import com.autotraces.app.LogCenter
import com.autotraces.app.PrefsStore
import com.autotraces.app.accountsFingerprint
import com.autotraces.app.hasSameAccountSetAs
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自动切号「资料包」（AutoSwitchBundle）的本地仓库。
 *
 * ## 职责（三步，顺序固定）
 * 1. **拉云端**：调用 [ApiService.fetchAutoSwitchBundle] 向服务端要最新 bundle。
 * 2. **写文件**：把完整 JSON 落到应用私有目录，进程重启后仍可恢复。
 * 3. **读内存**：同一份对象缓存在 [inMemory]，业务侧优先走 [get] 避免反复读盘。
 *
 * ## 本地文件
 * - 路径：`context.filesDir/auto_switch_bundle.json`
 * - 格式：Gson 序列化的 [AutoSwitchBundle]（pretty print，便于 adb 排查）
 * - 仅在 [save] / [fetchFromCloud] 成功时覆盖写入；[clear] 会删除该文件。
 *
 * ## 生命周期
 * - Application 启动时 [init]：解析文件路径，并尝试 [loadFromFile] → [inMemory]。
 * - 运行时拉取：[fetchFromCloud] 成功后同时更新文件与内存。
 * - 读数据：日常用 [get]；需要与磁盘对账时用 [loadFromFile]（不改动内存）。
 *
 * ## 线程
 * - [fetchFromCloud] 在 IO 线程执行网络与写文件。
 * - [get] / [save] / [loadFromFile] 可在任意线程调用；[inMemory] 用 @Volatile 保证可见性。
 */
object AutoSwitchBundleStore {

    /** 资料包在 filesDir 下的固定文件名。 */
    private const val BUNDLE_FILE_NAME = "auto_switch_bundle.json"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * 内存中的资料包副本。
     * - 启动时由 [init] 从本地文件灌入（若文件存在）。
     * - [fetchFromCloud] / [save] 成功后指向最新对象。
     * - [clear] 后置 null。
     */
    @Volatile
    private var inMemory: AutoSwitchBundle? = null

    /** 本地 JSON 文件句柄；[init] 之后非 null。 */
    private var bundleFile: File? = null

    /**
     * 初始化仓库（在 Application.onCreate 调用一次即可）。
     *
     * @param context 任意 Context，内部会使用 applicationContext。
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        bundleFile = File(appContext.filesDir, BUNDLE_FILE_NAME)
        inMemory = loadFromFile()
    }

    /**
     * 返回当前内存中的资料包。
     *
     * @return 未拉取且本地无缓存时为 null；否则为最近一次 [save]/[fetchFromCloud]/[init] 载入的对象。
     */
    fun get(): AutoSwitchBundle? = inMemory

    /**
     * 从磁盘读取资料包（**不**写入 [inMemory]）。
     *
     * 适用场景：核对「内存 vs 文件」是否一致，或 init 之前尚未赋值 bundleFile 时。
     *
     * @return 文件不存在、为空或 JSON 解析失败时返回 null。
     */
    fun loadFromFile(): AutoSwitchBundle? {
        val file = bundleFile ?: return null
        if (!file.isFile) return null
        return runCatching {
            gson.fromJson(file.readText(), AutoSwitchBundle::class.java)
        }.getOrNull()
    }

    /**
     * 从云端拉取资料包，成功后**同时**持久化到本地文件并更新内存。
     *
     * 请求参数：
     * - [deviceId] / [alias]：设备身份，必填。
     * - [sessionId]：自治 session；传 null 时用 [PrefsStore.getAutoSwitchSessionId]。
     * - [revision]：bundle 版本；传 null 时用 [PrefsStore.getAutoSwitchBundleRevision]。
     *
     * 成功条件：HTTP 正常且响应 `code == 0` 且 `bundle != null`，且 [save] 写盘成功。
     *
     * 副作用（成功时）：
     * - 覆盖 `auto_switch_bundle.json`
     * - 更新 [inMemory]
     * - 通过 [persistSessionMeta] 把 session / revision / 排班日期等写入 PrefsStore
     *
     * @return [AutoSwitchBundleFetchResult.ok] 为 true 表示云端拉取且本地落盘均成功。
     */
    suspend fun fetchFromCloud(
        deviceId: String,
        alias: String,
        sessionId: String? = null,
        revision: Int? = null,
    ): AutoSwitchBundleFetchResult = fetchFromCloudInternal(
        deviceId = deviceId,
        alias = alias,
        sessionId = sessionId,
        revision = revision,
        skipSaveIfSameAccounts = false,
    )

    /**
     * 资料重同步：拉云端 bundle，仅当账号 row+username 集合与本地不一致时才覆盖 JSON/内存。
     */
    suspend fun fetchFromCloudForResync(
        deviceId: String,
        alias: String,
        sessionId: String? = null,
        revision: Int? = null,
    ): AutoSwitchBundleFetchResult = fetchFromCloudInternal(
        deviceId = deviceId,
        alias = alias,
        sessionId = sessionId,
        revision = revision,
        skipSaveIfSameAccounts = true,
    )

    private suspend fun fetchFromCloudInternal(
        deviceId: String,
        alias: String,
        sessionId: String?,
        revision: Int?,
        skipSaveIfSameAccounts: Boolean,
    ): AutoSwitchBundleFetchResult = withContext(Dispatchers.IO) {
        val devId = deviceId.trim()
        val aliasClean = alias.trim()
        if (devId.isEmpty() || aliasClean.isEmpty()) {
            return@withContext AutoSwitchBundleFetchResult(
                ok = false,
                message = "device_id 或 alias 为空",
            )
        }

        val sid = sessionId?.trim().orEmpty().ifBlank {
            PrefsStore.getAutoSwitchSessionId().orEmpty()
        }
        val rev = revision ?: PrefsStore.getAutoSwitchBundleRevision()

        runCatching {
            ApiService.fetchAutoSwitchBundle(devId, aliasClean, sid, rev)
        }.fold(
            onSuccess = { resp ->
                val msg = resp.msg?.trim().orEmpty()
                when {
                    resp.code == 0 && resp.bundle != null -> {
                        val remote = resp.bundle
                        val local = get() ?: loadFromFile()
                        if (skipSaveIfSameAccounts && local != null && local.hasSameAccountSetAs(remote)) {
                            LogCenter.op(
                                aliasClean,
                                "【自动切号】资料重同步：账号未变，丢弃云端 bundle（保留本地 JSON/内存）",
                            )
                            return@fold AutoSwitchBundleFetchResult(
                                ok = true,
                                message = "账号未变，未覆盖本地 bundle",
                                bundle = local,
                                skippedUnchanged = true,
                            )
                        }
                        val saved = save(remote)
                        if (!saved) {
                            LogCenter.op(aliasClean, "【自动切号】bundle 已拉取但本地写入失败")
                            return@fold AutoSwitchBundleFetchResult(
                                ok = false,
                                message = "本地持久化失败",
                                bundle = remote,
                            )
                        }
                        persistSessionMeta(remote, sid, rev)
                        val accountCount = remote.accounts?.size ?: 0
                        LogCenter.op(
                            aliasClean,
                            "【自动切号】bundle 已拉取并保存（session=${remote.session_id ?: sid} " +
                                "revision=${remote.revision ?: rev} 账号数=$accountCount）",
                        )
                        AutoSwitchBundleFetchResult(
                            ok = true,
                            message = msg.ifBlank { "ok" },
                            bundle = remote,
                        )
                    }
                    else -> AutoSwitchBundleFetchResult(
                        ok = false,
                        message = msg.ifBlank { "bundle 为空或拉取失败(code=${resp.code})" },
                    )
                }
            },
            onFailure = { err ->
                AutoSwitchBundleFetchResult(
                    ok = false,
                    message = err.message?.trim().orEmpty().ifBlank { "网络错误" },
                )
            },
        )
    }

    /**
     * 将资料包写入本地文件，并替换内存副本。
     *
     * 写入策略：整文件覆盖（非增量 patch）；失败时不修改 [inMemory]。
     *
     * @return 写盘是否成功（bundleFile 未 init 时 false）。
     */
    fun save(bundle: AutoSwitchBundle): Boolean {
        val file = bundleFile ?: return false
        return runCatching {
            val json = gson.toJson(bundle)
            file.writeText(json)
            inMemory = bundle
            true
        }.getOrDefault(false)
    }

    /**
     * 清空内存并删除本地 JSON 文件。
     *
     * 用于退出自治 / 解绑等场景；删除文件失败时仍会清空内存。
     */
    fun clear() {
        inMemory = null
        runCatching { bundleFile?.delete() }
    }

    /**
     * 把 bundle 里的会话与排班元信息同步到 [PrefsStore]（与 JSON 文件互补，便于其它模块快速读 key）。
     *
     * - session_id / revision → 下次 [fetchFromCloud] 默认请求参数
     * - schedule_date / week 范围 → UI 与调度对账
     */
    private fun persistSessionMeta(
        bundle: AutoSwitchBundle,
        requestSessionId: String,
        requestRevision: Int,
    ) {
        val sid = bundle.session_id?.trim().orEmpty().ifBlank { requestSessionId }
        val rev = bundle.revision ?: requestRevision
        if (sid.isNotEmpty()) {
            PrefsStore.saveAutoSwitchSessionContext(sid, rev)
        }
        bundle.schedule_date?.trim()?.takeIf { it.isNotEmpty() }?.let {
            PrefsStore.setAutoSwitchScheduleDate(it)
        }
        val weekStart = bundle.week_start_date?.trim().orEmpty()
        val weekEnd = bundle.week_end_date?.trim().orEmpty()
        if (weekStart.isNotEmpty() && weekEnd.isNotEmpty()) {
            PrefsStore.setAutoSwitchWeekRange(weekStart, weekEnd)
        }
    }
}

/**
 * [AutoSwitchBundleStore.fetchFromCloud] 的返回结构。
 *
 * @property ok true = 云端返回有效 bundle 且本地文件写入成功。
 * @property message 服务端 msg 或本地错误说明。
 * @property bundle 成功时为刚拉取并落盘的那份；失败时可能仍带云端对象（例如仅写盘失败）。
 */
data class AutoSwitchBundleFetchResult(
    val ok: Boolean,
    val message: String = "",
    val bundle: AutoSwitchBundle? = null,
    /** 资料重同步时账号未变，未写本地 JSON/内存。 */
    val skippedUnchanged: Boolean = false,
)
