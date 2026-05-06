const authShell = document.getElementById("authShell");
const appShell = document.getElementById("appShell");
const authUsernameInput = document.getElementById("authUsername");
const authPasswordInput = document.getElementById("authPassword");
const authLoginBtn = document.getElementById("authLoginBtn");
const authMessageEl = document.getElementById("authMessage");
const logoutBtn = document.getElementById("logoutBtn");
const userRoleLabel = document.getElementById("userRoleLabel");
const statusEl = document.getElementById("status");
const apiBaseInput = document.getElementById("apiBase");
const refreshAllBtn = document.getElementById("refreshAll");
const refreshOverviewBtn = document.getElementById("refreshOverview");
const uploadBtn = document.getElementById("uploadUsernames");
const saveConfigBtn = document.getElementById("saveConfig");
const selectAllCheckbox = document.getElementById("selectAll");
const startSelectedBtn = document.getElementById("startSelected");
const startAllBtn = document.getElementById("startAll");
const stopSelectedBtn = document.getElementById("stopSelected");
const batchTemplateSelect = document.getElementById("batchTemplateSelect");
const applyTemplateSelectedBtn = document.getElementById("applyTemplateSelected");
const autoStartToggleBtn = document.getElementById("autoStartToggleBtn");
const growthModeBtn = document.getElementById("growthModeBtn");
const liveModeBtn = document.getElementById("liveModeBtn");
const cvModeBtn = document.getElementById("cvModeBtn");
const aliasFilterInput = document.getElementById("aliasFilter");
const startHintEl = document.getElementById("startHint");

const onlineCountEl = document.getElementById("onlineCount");
const runningCountEl = document.getElementById("runningCount");
const errorCountEl = document.getElementById("errorCount");
const pendingCountEl = document.getElementById("pendingCount");
const followerPendingEl = document.getElementById("followerPending");
const mobilePendingEl = document.getElementById("mobilePending");

const deviceTable = document.getElementById("deviceTable");

/** 云端「绑定设备自行拉起程序」开关，由 overview / logs_snapshot 同步 */
let adminAutoStartEnabled = false;
const pendingList = document.getElementById("pendingList");
const pulledList = document.getElementById("pulledList");

const configForm = document.getElementById("configForm");
const activeConfigTemplateSelect = document.getElementById("activeConfigTemplateSelect");
const copyConfigTemplateBtn = document.getElementById("copyConfigTemplateBtn");
const renameConfigTemplateBtn = document.getElementById("renameConfigTemplateBtn");
const deleteConfigTemplateBtn = document.getElementById("deleteConfigTemplateBtn");
const refreshTemplateInventoryBtn = document.getElementById("refreshTemplateInventoryBtn");
const selectAllOwnTemplatesBtn = document.getElementById("selectAllOwnTemplatesBtn");
const clearTemplateSelectionBtn = document.getElementById("clearTemplateSelectionBtn");
const deleteSelectedTemplatesBtn = document.getElementById("deleteSelectedTemplatesBtn");
const templateInventorySummaryEl = document.getElementById("templateInventorySummary");
const templateInventoryListEl = document.getElementById("templateInventoryList");
const templateInventoryAllUsersEl = document.getElementById("templateInventoryAllUsers");
const configTargetUserSelect = document.getElementById("configTargetUserSelect");
const grantConfigTemplateBtn = document.getElementById("grantConfigTemplateBtn");
const revokeConfigTemplateBtn = document.getElementById("revokeConfigTemplateBtn");
const templateGrantStatusEl = document.getElementById("templateGrantStatus");
const liveCustomerList = document.getElementById("liveCustomerList");
const importLiveCustomersBtn = document.getElementById("importLiveCustomers");
const liveCustomerFileInput = document.getElementById("liveCustomerFile");
const tiktokRootsList = document.getElementById("tiktokRootsList");
const tiktokRootsTableBody = document.getElementById("tiktokRootsTableBody");
const tiktokRootsPageInfoEl = document.getElementById("tiktokRootsPageInfo");
const tiktokRootsPrevPageBtn = document.getElementById("tiktokRootsPrevPage");
const tiktokRootsNextPageBtn = document.getElementById("tiktokRootsNextPage");
const tiktokRootsPageSizeEl = document.getElementById("tiktokRootsPageSize");
const tiktokRootsSelectPageBtn = document.getElementById("tiktokRootsSelectPage");
const tiktokRootsClearSelectionBtn = document.getElementById("tiktokRootsClearSelection");
const tiktokRootsSelectionCountEl = document.getElementById("tiktokRootsSelectionCount");
const addAllRootsToPendingBtn = document.getElementById("addAllRootsToPending");
const addTikTokRootsBtn = document.getElementById("addTikTokRoots");
const importTikTokRootsBtn = document.getElementById("importTikTokRoots");
const addMobilePendingBtn = document.getElementById("addMobilePendingUsernames");
const mobilePendingInput = document.getElementById("mobilePendingInput");
const resetTikTokRootsUsageBtn = document.getElementById("resetTikTokRootsUsage");
const tiktokRootsFileInput = document.getElementById("tiktokRootsFile");
const rootTypeInput = document.getElementById("rootTypeInput");
const triggerCollectRootsExcelBtn = document.getElementById("triggerCollectRootsExcel");
const stopCollectRootsExcelBtn = document.getElementById("stopCollectRootsExcel");
const triggerCollectRootsDbBtn = document.getElementById("triggerCollectRootsDb");
const stopCollectRootsDbBtn = document.getElementById("stopCollectRootsDb");
const triggerProcessQueueBtn = document.getElementById("triggerProcessQueue");
const stopProcessQueueInlineBtn = document.getElementById("stopProcessQueueInline");
const triggerAutoSearchBtn = document.getElementById("triggerAutoSearch");
const startSchedulersBtn = document.getElementById("startSchedulers");
const stopSchedulersBtn = document.getElementById("stopSchedulers");
const taskStatusEl = document.getElementById("taskStatus");
const taskLogsEl = document.getElementById("taskLogs");
const taskContainerIdsInput = document.getElementById("taskContainerIds");
const taskParallelInput = document.getElementById("taskParallel");
const collectRootsDbPrioritySelect = document.getElementById("collectRootsDbPriority");
const dbTableSelect = document.getElementById("dbTableSelect");
const dbPrevPageBtn = document.getElementById("dbPrevPage");
const dbNextPageBtn = document.getElementById("dbNextPage");
const dbRefreshPageBtn = document.getElementById("dbRefreshPage");
const dbPageInfoEl = document.getElementById("dbPageInfo");
const dbTableGridEl = document.getElementById("dbTableGrid");
const dbTableHeadEl = document.getElementById("dbTableHead");
const dbTableBodyEl = document.getElementById("dbTableBody");
const adminCreateUsernameInput = document.getElementById("adminCreateUsername");
const adminCreatePasswordInput = document.getElementById("adminCreatePassword");
const adminCreateRoleSelect = document.getElementById("adminCreateRole");
const adminCreateUserBtn = document.getElementById("adminCreateUserBtn");
const adminReloadUsersBtn = document.getElementById("adminReloadUsersBtn");
const adminUsersMsgEl = document.getElementById("adminUsersMsg");
const adminUsersTableBodyEl = document.getElementById("adminUsersTableBody");


const statsUsernameInput = document.getElementById("statsUsername");
const statsRecentCountInput = document.getElementById("statsRecentCount");
const fetchTiktokStatsBtn = document.getElementById("fetchTiktokStats");
const statsResultEl = document.getElementById("statsResult");
const cvAccessFilterEl = document.getElementById("cvAccessFilter");
const queryCvAccessDataBtn = document.getElementById("queryCvAccessData");
const triggerProcessQueueInlineBtn = document.getElementById("triggerProcessQueueInline");
const cvAccessTableBodyEl = document.getElementById("cvAccessTableBody");
const cvTaskContainerIdsInput = document.getElementById("cvTaskContainerIds");
const cvTaskParallelInput = document.getElementById("cvTaskParallel");
const cvTaskLogsEl = document.getElementById("cvTaskLogs");
const cvPrevPageBtn = document.getElementById("cvPrevPage");
const cvNextPageBtn = document.getElementById("cvNextPage");
const cvPageInfoEl = document.getElementById("cvPageInfo");
const cvPageSizeEl = document.getElementById("cvPageSize");
const cvFilterMinVideoCountEl = document.getElementById("cvFilterMinVideoCount");
const cvFilterMaxFollowingCountEl = document.getElementById("cvFilterMaxFollowingCount");
const saveCvQualityFilterBtn = document.getElementById("saveCvQualityFilter");
const processQueueBatchSizeInput = document.querySelector("input[name='process_queue_batch_size']");
const accountEditorListEl = document.getElementById("accountEditorList");
const deviceAliasGroupsEl = document.getElementById("deviceAliasGroups");
const deviceAliasPresetSelect = document.getElementById("deviceAliasPresetSelect");
const deviceRegionPresetSelect = document.getElementById("deviceRegionPresetSelect");
const deviceOpsSelect = document.getElementById("deviceOpsSelect");
const deviceLogDeviceSelect = document.getElementById("deviceLogDeviceSelect");
const deviceExecLogsEl = document.getElementById("deviceExecLogs");
const backupCloudInfoEl = document.getElementById("backupCloudInfo");
const addBlankRecordBtn = document.getElementById("addBlankRecordBtn");
const addDeviceBlankRecordBtn = document.getElementById("addDeviceBlankRecordBtn");
const accountBatchInputEl = document.getElementById("accountBatchInput");
const accountBatchRegionInputEl = document.getElementById("accountBatchRegionInput");
const accountBatchRejectEmptyToggleEl = document.getElementById("accountBatchRejectEmptyToggle");
const importAccountBatchBtn = document.getElementById("importAccountBatchBtn");
const copyCurrentRecordBtn = document.getElementById("copyCurrentRecordBtn");
const ACCOUNT_EDITOR_STORAGE_KEY = "auto_traces_account_editor_rows_v1";
const DEVICE_EDITOR_STORAGE_KEY = "auto_traces_device_editor_rows_v1";
const ACCOUNT_ROWS_API = "/api/admin/editor/account_rows";
const DEVICE_ROWS_API = "/api/admin/editor/device_rows";

let accountEditorRows = [];
let deviceEditorRows = [];

const COMMENT_TEXTS_LS_KEY = "auto_traces_admin_comment_texts_v1";
const API_BASE_LS_KEY = "auto_traces_api_base_v1";

let latestDevices = {};
let latestDeviceLogs = {};
let currentRunMode = "growth";
let currentConfigTemplate = "default";
let currentTemplateMeta = {};
let currentTemplatesByName = {};
let aliasTemplateByMode = { growth: {}, live: {}, direct: {} };
const pendingAutoAliasTemplateBindings = new Set();
let configTargetUsersLoaded = false;
let currentDbPage = 1;
const DB_PAGE_SIZE = 100;
let cvAccessAllItems = [];
let currentCvPage = 1;
let cvAccessUseServerPaging = false;
let cvAccessTotal = 0;
let cvAccessTotalPages = 1;
let tiktokRootsItems = [];
let currentTiktokRootsPage = 1;
let tiktokRootsTotal = 0;
let tiktokRootsTotalPages = 1;
let selectedTiktokRootIds = new Set();
let tiktokRootsUseServerPaging = false;
let overviewAutoRefreshInFlight = false;
let lastGalleryDispatchLogLine = "";

function appendGalleryDispatchLog(line, reset = false) {
  if (!galleryDispatchLogsEl) return;
  const text = String(line || "").trim();
  if (reset) {
    lastGalleryDispatchLogLine = text || "";
    galleryDispatchLogsEl.textContent = text || "暂无日志";
    return;
  }
  if (!text) return;
  if (text === lastGalleryDispatchLogLine) return;
  lastGalleryDispatchLogLine = text;
  const current = String(galleryDispatchLogsEl.textContent || "").trim();
  if (!current || current === "暂无日志") {
    galleryDispatchLogsEl.textContent = text || "暂无日志";
  } else {
    galleryDispatchLogsEl.textContent = `${current}\n${text}`;
  }
  galleryDispatchLogsEl.scrollTop = galleryDispatchLogsEl.scrollHeight;
}

const sections = document.querySelectorAll(".panel");
const navItems = document.querySelectorAll(".nav-item");
const contentShell = document.querySelector(".content");
let currentUser = null;
const TOKEN_KEY = "auto_traces_auth_token_v1";
const USER_CACHE_KEY = "auto_traces_auth_user_v1";
const RUN_MODE_KEY = "auto_traces_run_mode_v1";
const LAST_TEMPLATE_KEY = "auto_traces_last_template_v1";

function setStatus(text, type = "info") {
  statusEl.textContent = text;
  statusEl.className = `status ${type}`;
}

function showDeleteResultNotice(message, type = "success") {
  const icon = type === "success" ? "✅" : (type === "error" ? "❌" : "ℹ️");
  const text = `${icon} ${message}`;
  setStatus(message, type);
  window.alert(text);
}

function appendDebugLog() {}

function logBackupAction(action, payload = {}) {
  const act = String(action || "").trim() || "UNKNOWN";
  let detail = "";
  try {
    detail = JSON.stringify(payload || {});
  } catch (_) {
    detail = String(payload || "");
  }
  // 轮询日志仅走调试通道，不污染素材分发日志面板。
  appendDebugLog("POLL", { action: act, detail });
}

function summarizeDeviceOpResult(result) {
  if (!result) return "暂无设备执行结果";
  const lines = [];
  if (result.status) lines.push(`状态: ${result.status}`);
  if (result.message) lines.push(`消息: ${result.message}`);
  if (result.op_type) lines.push(`操作: ${result.op_type}`);
  if (result.device_id) lines.push(`设备: ${result.device_id}`);
  if (result.backup_name) lines.push(`备份档案名: ${result.backup_name}`);
  if (result.backup_path) lines.push(`备份档案路径: ${result.backup_path}`);
  const logs = result?.data?.operation_logs;
  if (Array.isArray(logs) && logs.length) {
    lines.push("执行日志:");
    lines.push(...logs);
  }
  return lines.join("\n");
}

function setTaskStatusInfo(text) {
  if (!taskStatusEl) return;
  taskStatusEl.textContent = text;
}

function updateTaskStatusFromDeviceResult(result, fallback = "任务已完成") {
  const summary = summarizeDeviceOpResult(result);
  const finalText = result?.status === "success"
    ? `${fallback}\n\n${summary}`
    : `${summary || fallback}`;
  setTaskStatusInfo(finalText);
}

function confirmTikTokAction(actionLabel, detailText) {
  return window.confirm(`确认执行【${actionLabel}】吗？\n\n${detailText}\n\n该操作会影响 TikTok 应用数据，请确保已选对设备。`);
}

function splitDeviceLogLine(line) {
  const text = String(line || "");
  const m = text.match(/^(\[[^\]]+\]\s+[^|]+\|\s*)(.*)$/);
  if (!m) return { prefix: "", body: text.trim() };
  return { prefix: m[1], body: (m[2] || "").trim() };
}

function buildDeviceLogLine(prefix, body) {
  return `${prefix || ""}${body || ""}`.trim();
}

function compactDeviceLogLines(lines = []) {
  const source = Array.isArray(lines) ? lines : [];
  const merged = [];

  for (let i = 0; i < source.length; i += 1) {
    const current = splitDeviceLogLine(source[i]);
    const next = splitDeviceLogLine(source[i + 1]);
    const next2 = splitDeviceLogLine(source[i + 2]);

    // 合并「执行X」+「X完成，耗时Nms」
    if (current.body.startsWith("执行") && /完成，耗时\s*\d+ms/.test(next.body)) {
      merged.push(buildDeviceLogLine(current.prefix, `${current.body} -> ${next.body}`));
      i += 1;
      continue;
    }

    // 合并 TikTok 重启流程的多行日志
    if (
      current.body.includes("关闭 TikTok:") &&
      next.body.includes("首次启动：重打开TikTok") &&
      next2.body.includes("首次启动结果=")
    ) {
      merged.push(buildDeviceLogLine(
        current.prefix,
        `${current.body}；${next.body}；${next2.body}`
      ));
      i += 2;
      continue;
    }

    merged.push(buildDeviceLogLine(current.prefix, current.body));
  }

  // 连续重复日志折叠为 xN
  const folded = [];
  for (const line of merged) {
    const current = splitDeviceLogLine(line);
    const prevRaw = folded[folded.length - 1];
    if (!prevRaw) {
      folded.push(buildDeviceLogLine(current.prefix, current.body));
      continue;
    }
    const prev = splitDeviceLogLine(prevRaw);
    const prevBody = prev.body.replace(/\s+\(x\d+\)$/, "");
    if (prevBody === current.body) {
      const countMatch = prev.body.match(/\(x(\d+)\)$/);
      const count = countMatch ? Number(countMatch[1]) + 1 : 2;
      folded[folded.length - 1] = buildDeviceLogLine(prev.prefix, `${prevBody} (x${count})`);
    } else {
      folded.push(buildDeviceLogLine(current.prefix, current.body));
    }
  }
  return folded;
}

function renderDeviceExecLogs(deviceLogs = {}) {
  if (!deviceExecLogsEl) return;
  const autoFollowThreshold = 40;
  const distanceToBottomBefore =
    deviceExecLogsEl.scrollHeight - (deviceExecLogsEl.scrollTop + deviceExecLogsEl.clientHeight);
  const shouldAutoFollow = distanceToBottomBefore <= autoFollowThreshold;
  const selectedFromDropdown = (deviceLogDeviceSelect?.value || "").trim();
  const selected = selectedDeviceIds();
  const allDeviceIds = Object.keys(deviceLogs || {});
  const targetIds = selectedFromDropdown
    ? [selectedFromDropdown]
    : (selected.length ? selected : allDeviceIds);
  const blocks = targetIds
    .map((deviceId) => {
      const lines = Array.isArray(deviceLogs?.[deviceId]) ? deviceLogs[deviceId] : [];
      if (!lines.length) return "";
      return compactDeviceLogLines(lines).join("\n");
    })
    .filter(Boolean);
  if (!blocks.length) {
    deviceExecLogsEl.textContent = "暂无日志";
    return;
  }
  deviceExecLogsEl.textContent = blocks.join("\n");
  if (shouldAutoFollow) {
    deviceExecLogsEl.scrollTop = deviceExecLogsEl.scrollHeight;
  }
}

function renderDeviceLogDeviceSelect(devices = {}, deviceLogs = {}) {
  if (!deviceLogDeviceSelect) return;
  const prev = deviceLogDeviceSelect.value || "";
  const ids = Array.from(new Set([
    ...Object.keys(devices || {}),
    ...Object.keys(deviceLogs || {}),
  ]));
  const options = [
    '<option value="">全部设备</option>',
    ...ids.map((deviceId) => {
      const alias = String(devices?.[deviceId]?.alias || "").trim();
      const status = String(devices?.[deviceId]?.status || "").trim();
      const label = [alias || "-", deviceId, status || "unknown"].join(" | ");
      return `<option value="${deviceId}">${label}</option>`;
    }),
  ];
  deviceLogDeviceSelect.innerHTML = options.join("");
  if (prev && ids.includes(prev)) {
    deviceLogDeviceSelect.value = prev;
  }
}

function defaultApiBase() {
  const { protocol } = window.location;
  if (protocol === "file:") {
    return "/api";
  }
  // Always use same-origin API behind Nginx reverse proxy.
  return "/api";
}

function apiBase() {
  const manual = (apiBaseInput?.value || "").trim().replace(/\/$/, "");
  if (manual) {
    localStorage.setItem(API_BASE_LS_KEY, manual);
    return manual;
  }
  const saved = (localStorage.getItem(API_BASE_LS_KEY) || "").trim().replace(/\/$/, "");
  if (saved) return saved;
  return defaultApiBase();
}

function isAuthExpiredError(error) {
  const message = String(error?.message || "");
  return /HTTP\s*(401|403)\b/.test(message) || /未登录|login required|invalid token/i.test(message);
}

function authToken() {
  return localStorage.getItem(TOKEN_KEY) || "";
}

function setAuthToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

function setCachedUser(user) {
  if (user && typeof user === "object") {
    localStorage.setItem(USER_CACHE_KEY, JSON.stringify(user));
  } else {
    localStorage.removeItem(USER_CACHE_KEY);
  }
}

function getCachedUser() {
  try {
    const raw = localStorage.getItem(USER_CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return null;
    const username = String(parsed.username || "").trim();
    const role = String(parsed.role || "customer").trim() || "customer";
    if (!username) return null;
    return { username, role };
  } catch (_) {
    return null;
  }
}

function applyRolePermissions() {
  const isAdmin = currentUser?.role === "admin";
  document.querySelectorAll(".admin-only").forEach((el) => {
    el.style.display = isAdmin ? "" : "none";
  });
  if (userRoleLabel) {
    userRoleLabel.textContent = currentUser ? `${currentUser.username} · ${isAdmin ? "管理员" : "普通用户"}` : "后台管理控制台";
  }
  if (!isAdmin && document.getElementById("adminHint")) {
    document.getElementById("adminHint").style.display = "none";
  }
  if (!isAdmin) {
    const activeVisible = Array.from(navItems).find((item) => item.style.display !== "none") || navItems[0];
    if (activeVisible) switchSection(activeVisible.dataset.section);
  }
}

async function switchSection(id) {
  const hasTargetSection = Array.from(sections).some((section) => section.id === id);
  const nextSectionId = hasTargetSection ? id : "overview";
  if (!hasTargetSection && id) {
    setStatus(`页面不存在：${id}，已自动跳回设备概览`, "warning");
  }
  sections.forEach((section) => {
    section.classList.toggle("active", section.id === nextSectionId);
  });
  navItems.forEach((item) => {
    item.classList.toggle("active", item.dataset.section === nextSectionId);
  });
  // 切换菜单后将主内容滚动到顶部，避免短面板看起来“空白”。
  if (contentShell && typeof contentShell.scrollTo === "function") {
    contentShell.scrollTo({ top: 0, behavior: "auto" });
  } else {
    window.scrollTo({ top: 0, behavior: "auto" });
  }
  // 切回设备概览时主动拉取一次完整数据，避免必须手动点“刷新数据”。
  if (nextSectionId === "overview") {
    try {
      await loadOverview();
    } catch (_) {
      // 保持静默，避免打断页面切换；状态栏会显示已有错误。
    }
  }
  // 进入 follower_queue 页面时主动刷新表格与日志，避免必须翻页后才看到数据。
  if (nextSectionId === "cv-access-data") {
    try {
      currentCvPage = 1;
      await loadCvAccessData();
    } catch (_) {
      // 保持静默，避免打断页面切换；状态栏会显示已有错误。
    }
    try {
      await refreshTaskStatus();
    } catch (_) {
      // 保持静默，避免打断页面切换；状态栏会显示已有错误。
    }
  }
}

navItems.forEach((item) => {
  item.addEventListener("click", () => {
    void switchSection(item.dataset.section);
  });
});

async function request(path, options = {}) {
  const base = String(apiBase() || "").replace(/\/$/, "");
  const rawPath = String(path || "").trim();
  const normalizedPath = rawPath.startsWith("/") ? rawPath : `/${rawPath}`;
  const dedupedPath = base.endsWith("/api") && normalizedPath.startsWith("/api/")
    ? normalizedPath.slice(4)
    : normalizedPath;
  const url = `${base}${dedupedPath}`;
  appendDebugLog("REQUEST", { url, options: { ...options, body: options.body || undefined } });
  try {
    const method = String(options.method || "GET").toUpperCase();
    const headers = {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    };
    const token = authToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    const response = await fetch(url, {
      ...options,
      headers,
      // 避免浏览器/代理层缓存导致刷新后拿到旧运行配置。
      cache: options.cache || (method === "GET" || method === "HEAD" ? "no-store" : options.cache),
    });
    const text = await response.text();
    let data;
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = { code: response.ok ? 0 : 1, msg: text };
    }
    appendDebugLog("RESPONSE", { url, status: response.status, ok: response.ok, data });
    if (!response.ok || data.code !== 0) {
      const msg = data.msg || "请求失败";
      throw new Error(`${msg} (HTTP ${response.status}) [${url}]`);
    }
    return data;
  } catch (error) {
    const raw = error?.message || String(error);
    const isNetworkFetchError =
      error instanceof TypeError &&
      /Failed to fetch|NetworkError|Load failed|fetch failed/i.test(raw);
    const friendly = isNetworkFetchError
      ? `网络请求失败：无法连接服务端，请检查 API Base 与服务是否在线 [${url}]`
      : `${raw} [${url}]`;
    appendDebugLog("ERROR", { url, message: friendly, rawMessage: raw });
    throw new Error(friendly);
  }
}

function renderDevices(devices) {
  const prevSelected = selectedDeviceIds();
  deviceTable.innerHTML = "";
  const aliasFilter = aliasFilterInput.value.trim().toLowerCase();
  const isAdmin = currentUser?.role === "admin";
  const templateNames = Object.keys(currentTemplatesByName || {}).sort((a, b) => a.localeCompare(b));
  if (!templateNames.length) templateNames.push("default");
  // 未命中 alias 映射时，按服务端 active_template 回显，避免“看起来已绑定”的假象。
  const fallbackTemplate = String(currentConfigTemplate || templateNames[0] || "default").trim() || "default";
  const rows = Object.entries(devices)
    .filter(([, info]) => {
      if (!aliasFilter) return true;
      return String(info?.alias || "").toLowerCase().includes(aliasFilter);
    })
    .map(([deviceId, info]) => {
      const alias = String(info?.alias || "").trim();
      const statusClass = info.status;
      const pending = info.pending_start ? "是" : "否";
      const pendingBadge = info.pending_start
        ? "<span class=\"badge pending\">待启动</span>"
        : "-";
      const disabled = info.status === "offline" ? "disabled" : "";
      const rowStyle = info.status === "offline" ? ' style="opacity:.55;"' : "";
      const ownerCell = isAdmin ? `<td>${String(info?.owner_username || "")}</td>` : "";
      const aliasKey = String(alias || "").trim().toLowerCase();
      const mappedTemplate = String(aliasTemplateByMode?.[currentRunMode]?.[aliasKey] || "").trim();
      const isTemplateBound = Boolean(mappedTemplate);
      const fallbackDisplayTemplate = String(fallbackTemplate || "default").trim() || "default";
      const selectedTemplate = String(mappedTemplate || fallbackDisplayTemplate).trim() || fallbackDisplayTemplate;
      const optionsHtml = templateNames.map((name) => (
        `<option value="${name}" ${name === selectedTemplate ? "selected" : ""}>${templateDisplayName(name, currentTemplateMeta)}</option>`
      )).join("");
      const templateCell = `
        <td>
          <select
            class="alias-template-select"
            data-device="${deviceId}"
            data-alias="${alias}"
            data-owner="${String(info?.owner_username || "").trim()}"
            ${isTemplateBound ? "" : `data-needs-bind="1" data-default-template="${selectedTemplate}"`}
            style="min-width:140px;padding:4px 6px;font-size:12px;"
            ${alias ? "" : "disabled"}
          >
            ${optionsHtml}
          </select>
          <div style="margin-top:6px;">
            <span class="badge ${isTemplateBound ? "online" : "pending"} js-template-bind-state">
              ${isTemplateBound ? "已绑定" : "绑定中..."}
            </span>
          </div>
        </td>
      `;
      const optOut = Boolean(info?.auto_start_opt_out);
      const effectiveEnabled = adminAutoStartEnabled && !optOut;
      const autoStartLabel = effectiveEnabled ? "开启" : "关闭";
      const autoStartCell = `
        <td>
          <button
            type="button"
            class="${effectiveEnabled ? "primary" : "ghost"}"
            style="padding:6px 10px;font-size:12px;"
            data-action="toggle-device-auto-start"
            data-device="${deviceId}"
            data-optout="${optOut ? "1" : "0"}"
            title="${adminAutoStartEnabled ? "点击切换该设备是否参与自动开启" : "当前总开关关闭，设备自动开启不会生效"}"
          >
            ${autoStartLabel}
          </button>
        </td>
      `;
      const deleteCell = `
        <td>
          <button
            type="button"
            class="ghost"
            style="padding:6px 10px;font-size:12px;"
            data-action="delete-device"
            data-device="${deviceId}"
            data-alias="${alias}"
            title="删除该设备记录（无别名也可删除）"
          >
            删除
          </button>
        </td>
      `;
      return `
      <tr${rowStyle}>
        <td><input type="checkbox" class="device-check" data-device="${deviceId}" ${disabled} /></td>
        <td>${deviceId}</td>
        <td>${info.alias}</td>
        ${ownerCell}
        ${templateCell}
        <td><span class="badge ${statusClass}">${info.status}</span></td>
        <td>${info.pending_start ? pendingBadge : pending}</td>
        ${autoStartCell}
        ${deleteCell}
      </tr>
    `;
    });
  deviceTable.innerHTML = rows.join("");
  deviceTable.querySelectorAll(".device-check").forEach((cb) => {
    if (!cb.disabled && prevSelected.includes(cb.dataset.device)) cb.checked = true;
  });
  deviceTable.querySelectorAll("button[data-action='toggle-device-auto-start']").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      const el = e.currentTarget;
      const did = el?.dataset?.device || "";
      const opt = el?.dataset?.optout === "1";
      if (!did) return;
      try {
        setStatus("正在更新单设备自动开启策略...", "info");
        const data = await request("/api/admin/device_auto_start_opt_out", {
          method: "POST",
          body: JSON.stringify({ device_id: did, opt_out: !opt }),
        });
        // 更新本地缓存并重绘
        if (latestDevices?.[did]) {
          latestDevices[did].auto_start_opt_out = Boolean(data.auto_start_opt_out);
        }
        renderDevices(latestDevices);
        bindDeviceSelection();
        setStatus("已更新单设备自动开启策略", "success");
      } catch (error) {
        setStatus(error.message || "更新失败", "error");
      }
    });
  });
  deviceTable.querySelectorAll("button[data-action='delete-device']").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      const el = e.currentTarget;
      const did = String(el?.dataset?.device || "").trim();
      const alias = String(el?.dataset?.alias || "").trim();
      if (!did) return;
      if (!window.confirm(`确认删除设备「${alias || did}」吗？`)) return;
      const oldText = String(el.textContent || "删除").trim() || "删除";
      el.disabled = true;
      el.textContent = "删除中...";
      try {
        setStatus("正在删除设备...", "info");
        await request("/api/admin/device/delete", {
          method: "POST",
          body: JSON.stringify({ device_id: did, alias }),
        });
        if (latestDevices) delete latestDevices[did];
        await loadOverview();
        bindDeviceSelection();
        showDeleteResultNotice(`设备已删除：${alias || did}`, "success");
      } catch (error) {
        showDeleteResultNotice(error.message || "删除失败", "error");
      } finally {
        el.disabled = false;
        el.textContent = oldText;
      }
    });
  });
  deviceTable.querySelectorAll("select.alias-template-select").forEach((selectEl) => {
    selectEl.addEventListener("change", async (e) => {
      const target = e.currentTarget;
      const alias = String(target?.dataset?.alias || "").trim();
      const ownerUsername = String(target?.dataset?.owner || "").trim();
      const aliasKey = alias.toLowerCase();
      const nextTemplate = String(target?.value || "").trim();
      if (!alias || !nextTemplate) return;
      try {
        const data = await request("/api/admin/alias_template/select", {
          method: "POST",
          body: JSON.stringify({
            alias,
            mode: currentRunMode,
            template_name: nextTemplate,
            owner_username: ownerUsername || undefined,
          }),
        });
        aliasTemplateByMode = (data?.alias_template_by_mode && typeof data.alias_template_by_mode === "object")
          ? data.alias_template_by_mode
          : aliasTemplateByMode;
        const stateEl = target?.closest("td")?.querySelector(".js-template-bind-state");
        if (stateEl) {
          stateEl.textContent = "已绑定";
          stateEl.classList.remove("offline");
          stateEl.classList.add("online");
        }
        setStatus(`别名 ${alias} 已切换模板：${templateDisplayName(nextTemplate, currentTemplateMeta)}`, "success");
      } catch (error) {
        setStatus(error.message || "更新别名模板失败", "error");
        const rollback = String(aliasTemplateByMode?.[currentRunMode]?.[aliasKey] || fallbackTemplate).trim() || fallbackTemplate;
        target.value = rollback;
      }
    });
  });
  deviceTable.querySelectorAll('select.alias-template-select[data-needs-bind="1"]').forEach((selectEl) => {
    const alias = String(selectEl?.dataset?.alias || "").trim();
    const ownerUsername = String(selectEl?.dataset?.owner || "").trim();
    const aliasKey = alias.toLowerCase();
    const templateName = String(selectEl?.dataset?.defaultTemplate || selectEl?.value || "").trim();
    if (!alias || !templateName) return;
    const bindKey = `${currentRunMode}::${ownerUsername}::${aliasKey}`;
    if (pendingAutoAliasTemplateBindings.has(bindKey)) return;
    pendingAutoAliasTemplateBindings.add(bindKey);
    (async () => {
      try {
        const data = await request("/api/admin/alias_template/select", {
          method: "POST",
          body: JSON.stringify({
            alias,
            mode: currentRunMode,
            template_name: templateName,
            owner_username: ownerUsername || undefined,
          }),
        });
        aliasTemplateByMode = (data?.alias_template_by_mode && typeof data.alias_template_by_mode === "object")
          ? data.alias_template_by_mode
          : aliasTemplateByMode;
        const stateEl = selectEl?.closest("td")?.querySelector(".js-template-bind-state");
        if (stateEl) {
          stateEl.textContent = "已绑定";
          stateEl.classList.remove("pending", "offline");
          stateEl.classList.add("online");
        }
        selectEl.removeAttribute("data-needs-bind");
      } catch (_) {
        const stateEl = selectEl?.closest("td")?.querySelector(".js-template-bind-state");
        if (stateEl) {
          stateEl.textContent = "未绑定";
          stateEl.classList.remove("pending", "online");
          stateEl.classList.add("offline");
        }
      } finally {
        pendingAutoAliasTemplateBindings.delete(bindKey);
      }
    })();
  });
  updateStartHint();
}

function renderTagList(target, items, options = {}) {
  target.innerHTML = "";
  if (!items.length) {
    target.innerHTML = "<li>暂无数据</li>";
    return;
  }
  const formatter = options.formatter || ((item) => `${item}`);
  const className = options.className || "";
  target.innerHTML = items
    .map((item) => `<li class="${className}">${formatter(item)}</li>`)
    .join("");
}

function fillConfigForm(config, mode = currentRunMode) {
  if (!configForm) return;
  const modeSelect = document.getElementById("configModeSelect");
  if (modeSelect) modeSelect.value = mode;
  // 先回到页面默认值，避免不同来源配置仅返回部分字段时残留旧值。
  configForm.querySelectorAll("input[name], textarea[name], select[name]").forEach((input) => {
    if (input.type === "checkbox") {
      input.checked = Boolean(input.defaultChecked);
      return;
    }
    if (input.tagName === "SELECT") {
      const fallback = input.options?.[0]?.value ?? "";
      input.value = input.defaultValue || fallback;
      return;
    }
    input.value = input.defaultValue || "";
  });
  Object.entries(config).forEach(([key, value]) => {
    const input = configForm.querySelector(`[name='${key}']`);
    if (!input) return;
    if (input.type === "checkbox") {
      input.checked = Boolean(value);
      return;
    }
    if (key === "comment_texts") {
      const lines = Array.isArray(value)
        ? value.map((s) => String(s).trim()).filter(Boolean)
        : [];
      if (lines.length) {
        input.value = lines.join("\n");
      } else {
        try {
          const backup = localStorage.getItem(COMMENT_TEXTS_LS_KEY);
          input.value = backup || "";
        } catch (_) {
          input.value = "";
        }
      }
      return;
    }
    if (Array.isArray(value)) {
      input.value = value.join("\n");
      return;
    }
    input.value = value;
  });
  syncCvQualityFilterInputsFromConfig(config);
}

function persistCommentTextsToLocal() {
  const ta = configForm?.querySelector("[name='comment_texts']");
  if (!ta) return;
  try {
    localStorage.setItem(COMMENT_TEXTS_LS_KEY, ta.value);
  } catch (_) {}
}

function selectedDeviceIdsByStatus(statuses = []) {
  const allow = new Set(statuses);
  return selectedDeviceIds().filter((id) => allow.has(latestDevices?.[id]?.status));
}

function updateStartHint() {
  const total = Object.keys(latestDevices || {}).length;
  const selected = selectedDeviceIds();
  const selectedRunnable = selectedDeviceIdsByStatus(["running", "error", "paused"]);

  if (!total) {
    startHintEl.textContent = "暂无设备。";
  } else if (!selected.length) {
    startHintEl.textContent = "请选择设备：任意状态可启动，运行中/异常可停止。";
  } else {
    startHintEl.textContent = `已选 ${selected.length} 台（可启动 ${selected.length}，可停止 ${selectedRunnable.length}）`;
  }

  const allChecks = document.querySelectorAll(".device-check");
  selectAllCheckbox.checked = allChecks.length > 0 && selected.length === allChecks.length;
}

function syncSelectAll() {
  const checks = document.querySelectorAll(".device-check");
  const shouldCheck = selectAllCheckbox.checked;
  checks.forEach((checkbox) => {
    if (!checkbox.disabled) checkbox.checked = shouldCheck;
  });
  updateStartHint();
}

function bindDeviceSelection() {
  const checkboxes = document.querySelectorAll(".device-check");
  checkboxes.forEach((checkbox) => {
    checkbox.addEventListener("change", updateStartHint);
  });
  updateStartHint();
}

function selectedDeviceIds() {
  return Array.from(document.querySelectorAll(".device-check:checked"))
    .map((item) => item.dataset.device)
    .filter(Boolean);
}

async function startDevices(deviceIds) {
  if (!deviceIds.length) {
    setStatus("请选择需要启动的设备", "warning");
    return;
  }
  try {
    setStatus("启动指令下发中...", "info");
    const data = await request("/api/start_task", {
      method: "POST",
      body: JSON.stringify({ device_ids: deviceIds }),
    });
    await loadOverview();
    setStatus(
      `启动指令已下发 ${data.count} 台设备；下方日志每几秒自动刷新。若无新日志请打开手机 App 并保持后台运行。`,
      "success",
    );
  } catch (error) {
    setStatus(error.message || "启动失败", "error");
  }
}

async function stopDevices(deviceIds) {
  if (!deviceIds.length) {
    setStatus("请选择需要停止的设备", "warning");
    return;
  }
  try {
    setStatus("正在停止设备后台服务...", "info");
    const data = await request("/api/stop_task", {
      method: "POST",
      body: JSON.stringify({ device_ids: deviceIds }),
    });
    await loadOverview();
    setStatus(`已停止 ${data.count} 台设备后台服务`, "success");
  } catch (error) {
    setStatus(error.message || "停止失败", "error");
  }
}

async function applyTemplateToSelectedDevices() {
  const templateName = String(batchTemplateSelect?.value || "").trim();
  if (!templateName) {
    setStatus("请先选择要应用的模板", "warning");
    return;
  }
  const selectedIds = selectedDeviceIds();
  if (!selectedIds.length) {
    setStatus("请先勾选设备", "warning");
    return;
  }
  const targets = selectedIds
    .map((id) => {
      const info = latestDevices?.[id] || {};
      const alias = String(info?.alias || "").trim();
      const owner = String(info?.owner_username || "").trim();
      return alias ? { alias, owner } : null;
    })
    .filter(Boolean);
  if (!targets.length) {
    setStatus("所选设备缺少别名，无法批量修改模板", "warning");
    return;
  }
  const uniqueTargets = Array.from(
    new Map(targets.map((item) => [`${item.owner}::${item.alias.toLowerCase()}`, item])).values(),
  );

  let successCount = 0;
  let failedCount = 0;
  setStatus(`正在批量修改模板（${uniqueTargets.length} 台）...`, "info");
  for (const item of uniqueTargets) {
    try {
      const data = await request("/api/admin/alias_template/select", {
        method: "POST",
        body: JSON.stringify({
          alias: item.alias,
          mode: currentRunMode,
          template_name: templateName,
          owner_username: item.owner || undefined,
        }),
      });
      aliasTemplateByMode = (data?.alias_template_by_mode && typeof data.alias_template_by_mode === "object")
        ? data.alias_template_by_mode
        : aliasTemplateByMode;
      successCount += 1;
    } catch (_) {
      failedCount += 1;
    }
  }
  renderDevices(latestDevices);
  bindDeviceSelection();
  const templateText = templateDisplayName(templateName, currentTemplateMeta) || templateName;
  if (failedCount > 0) {
    setStatus(`批量修改完成：成功 ${successCount} 台，失败 ${failedCount} 台（模板：${templateText}）`, "warning");
  } else {
    setStatus(`批量修改成功：${successCount} 台已切换为 ${templateText}`, "success");
  }
}

const MODE_DEFAULT_CONFIG_PAYLOADS = {
  growth: {
    fans_per_break: 5,
    break_min: 10,
    break_max: 20,
    visit_delay_min: 0.5,
    visit_delay_max: 2,
    video_stay_min: 3,
    video_stay_max: 10,
    like_prob: 0.4,
    comment_prob: 0.3,
    favorite_prob: 0.3,
    share_prob: 0.3,
    comment_text_prob: 0.7,
    skip_post_click_prob: 0,
    action_delay_min: 0.7,
    action_delay_max: 1.2,
    tiktok_force_stop_wait_ms: 800,
    tiktok_launch_wait_ms: 2600,
    for_you_swipe_total_sec: 0,
    for_you_like_prob: 0,
    for_you_watch_min_sec: 2,
    for_you_watch_max_sec: 5,
    like_daily_limit: 800,
    comment_daily_limit: 800,
    favorite_daily_limit: 800,
    share_daily_limit: 800,
    action_single: true,
    skip_post_click_prob: 0,
    pull_batch_size: 3,
    process_queue_batch_size: 100,
    comment_texts: ["hi", "moring", "so cute"],
    growth_post_check_after_tap_delay_ms: 1000,
    growth_post_second_check_delay_ms: 650,
  },
  live: {
    fans_per_break: 5,
    break_min: 10,
    break_max: 20,
    visit_delay_min: 0.5,
    visit_delay_max: 2,
    video_stay_min: 3,
    video_stay_max: 10,
    like_prob: 0.4,
    comment_prob: 0.3,
    favorite_prob: 0.3,
    share_prob: 0.3,
    comment_text_prob: 0.7,
    skip_post_click_prob: 0,
    action_delay_min: 0.7,
    action_delay_max: 1.2,
    tiktok_force_stop_wait_ms: 800,
    tiktok_launch_wait_ms: 2600,
    for_you_swipe_total_sec: 0,
    for_you_like_prob: 0,
    for_you_watch_min_sec: 2,
    for_you_watch_max_sec: 5,
    like_daily_limit: 800,
    comment_daily_limit: 800,
    favorite_daily_limit: 800,
    share_daily_limit: 800,
    action_single: true,
    skip_post_click_prob: 0,
    pull_batch_size: 3,
    process_queue_batch_size: 100,
    comment_texts: ["hi", "moring", "so cute"],
    direct_post_action_delay_ms: 1500,
  },
  direct: {
    fans_per_break: 5,
    break_min: 10,
    break_max: 20,
    visit_delay_min: 0.5,
    visit_delay_max: 2,
    video_stay_min: 3,
    video_stay_max: 10,
    like_prob: 0.4,
    comment_prob: 0.3,
    favorite_prob: 0.3,
    share_prob: 0.3,
    comment_text_prob: 0.7,
    skip_post_click_prob: 0,
    action_delay_min: 0.7,
    action_delay_max: 1.2,
    tiktok_force_stop_wait_ms: 800,
    tiktok_launch_wait_ms: 2600,
    for_you_swipe_total_sec: 0,
    for_you_like_prob: 0,
    for_you_watch_min_sec: 2,
    for_you_watch_max_sec: 5,
    like_daily_limit: 800,
    comment_daily_limit: 800,
    favorite_daily_limit: 800,
    share_daily_limit: 800,
    action_single: true,
    skip_post_click_prob: 0,
    pull_batch_size: 3,
    process_queue_batch_size: 100,
    comment_texts: ["hi", "moring", "so cute"],
    direct_post_action_delay_ms: 1500,
  },
};


function updateAutoStartToggleUi() {
  if (!autoStartToggleBtn) return;
  autoStartToggleBtn.textContent = adminAutoStartEnabled
    ? "自动开启程序：开"
    : "自动开启程序：关";
  autoStartToggleBtn.classList.toggle("primary", adminAutoStartEnabled);
  autoStartToggleBtn.classList.toggle("ghost", !adminAutoStartEnabled);
}

function updateModeButtons() {
  if (!growthModeBtn || !liveModeBtn || !cvModeBtn) return;

  const modeBtns = [growthModeBtn, liveModeBtn, cvModeBtn];
  modeBtns.forEach((btn) => {
    btn.classList.remove("primary");
    btn.classList.add("ghost");
  });

  if (currentRunMode === "live") {
    liveModeBtn.classList.remove("ghost");
    liveModeBtn.classList.add("primary");
  } else if (currentRunMode === "direct") {
    cvModeBtn.classList.remove("ghost");
    cvModeBtn.classList.add("primary");
  } else {
    growthModeBtn.classList.remove("ghost");
    growthModeBtn.classList.add("primary");
  }
}

function renderDeviceOpsSelect(devices = {}) {
  if (!deviceOpsSelect) return;
  const rows = Object.entries(devices || {}).filter(([, info]) => {
    const alias = String(info?.alias || "").trim();
    return Boolean(alias);
  });
  if (!rows.length) {
    deviceOpsSelect.innerHTML = '<option value="">暂无可用设备</option>';
    toggleDeviceOpsButtons();
    return;
  }

  const options = rows.map(([deviceId, info]) => {
    const alias = info?.alias || "-";
    const status = info?.status || "unknown";
    const value = `${deviceId}|${alias}`;
    return `<option value="${value}">${alias} | ${deviceId} | ${status}</option>`;
  });
  deviceOpsSelect.innerHTML = options.join("");
  toggleDeviceOpsButtons();
}

/** 更新设备卡片、表格、别名筛选下表格、运维下拉与日志区（由 overview 或 logs_snapshot 共用） */
function applyDevicesAndLogsPayload(data, options = {}) {
  const shouldSyncRunMode = options.syncRunMode !== false;
  const shouldSyncConfigForm = options.syncConfigForm === true;
  const devices = data.devices || {};
  const deviceLogs = data.device_logs || {};
  latestDevices = devices;
  latestDeviceLogs = deviceLogs;
  const nextMode = data.run_mode || currentRunMode || "growth";
  if (shouldSyncRunMode) {
    currentRunMode = nextMode;
    localStorage.setItem(RUN_MODE_KEY, currentRunMode);
  }
  if (Object.prototype.hasOwnProperty.call(data, "auto_start_enabled")) {
    adminAutoStartEnabled = Boolean(data.auto_start_enabled);
    updateAutoStartToggleUi();
  }
  const deviceValues = Object.values(devices);
  const online = deviceValues.filter((d) => d.status === "online").length;
  const running = deviceValues.filter((d) => d.status === "running").length;
  const error = deviceValues.filter((d) => d.status === "error").length;
  const pending = deviceValues.filter((d) => d.pending_start).length;

  onlineCountEl.textContent = online;
  runningCountEl.textContent = running;
  errorCountEl.textContent = error;
  pendingCountEl.textContent = pending;

  updateModeButtons();
  if (shouldSyncConfigForm && data?.config && typeof data.config === "object") {
    fillConfigForm(data.config, nextMode);
    syncCvQualityFilterInputsFromConfig(data.config);
    persistCommentTextsToLocal();
  }
  renderDevices(devices);
  renderDeviceAliasPresetOptions();
  renderDeviceOpsSelect(devices);
  renderDeviceLogDeviceSelect(devices, deviceLogs);
  renderDeviceExecLogs(deviceLogs);
  bindDeviceSelection();
}

async function loadOverview() {
  const data = await request("/api/admin/overview");
  // overview 仅用于设备与概览数据；配置表单必须由 runtime_config/template_select 驱动，
  // 否则会出现“模板名正确但表单值被 overview 旧快照覆盖”的错位。
  applyDevicesAndLogsPayload(data, { syncRunMode: true, syncConfigForm: false });
  const queueStats = data?.queue_stats || {};
  const followerPendingCount =
    queueStats?.follower?.pending ??
    queueStats?.follower_pending ??
    data?.follower_pending ??
    0;
  const mobilePendingCount =
    queueStats?.mobile_pending ??
    queueStats?.mobile?.pending ??
    data?.mobile_pending ??
    data?.mobile_pending_usernames ??
    0;
  if (followerPendingEl) {
    followerPendingEl.textContent = Number(followerPendingCount) || 0;
  }
  if (mobilePendingEl) {
    mobilePendingEl.textContent = Number(mobilePendingCount) || 0;
  }

  renderTagList(pendingList, data.pending_usernames || []);
  renderTagList(pulledList, data.pulled_usernames || []);
}

function isOverviewActive() {
  const active = document.querySelector(".panel.active");
  return active?.id === "overview";
}

async function pollOverviewSnapshot() {
  if (overviewAutoRefreshInFlight) return;
  if (!authToken()) return;
  if (document.visibilityState === "hidden") return;
  if (!isOverviewActive()) return;
  overviewAutoRefreshInFlight = true;
  try {
    await loadOverview();
  } catch (_) {
    // 静默失败，避免频繁提示；用户可手动刷新查看错误信息。
  } finally {
    overviewAutoRefreshInFlight = false;
  }
}

async function pollDeviceLogsSnapshot() {
  try {
    const data = await request("/api/admin/logs_snapshot");
    // 轮询仅更新设备/日志，不覆盖用户当前正在查看或编辑的运行配置表单。
    applyDevicesAndLogsPayload(data, { syncRunMode: false, syncConfigForm: false });
  } catch (_) {
    /* 轮询失败静默，避免刷屏；用户可手动点刷新数据 */
  }
}

async function handleRefresh() {
  try {
    setStatus("刷新中...", "info");
    await loadOverview();
    await loadUserRuntimeConfig();
    setStatus("已刷新", "success");
  } catch (error) {
    setStatus(error.message || "刷新失败", "error");
  }
}

async function uploadUsernames() {
  const input = document.getElementById("usernameInput");
  const raw = input.value.split("\n").map((item) => item.trim());
  const usernames = raw.filter(Boolean);
  if (!usernames.length) {
    setStatus("请填写用户名", "warning");
    return;
  }
  try {
    setStatus("导入中...", "info");
    const data = await request("/api/admin/add_usernames", {
      method: "POST",
      body: JSON.stringify({ usernames }),
    });
    input.value = "";
    await loadOverview();
    setStatus(`导入成功（新增 ${data.count} 条）`, "success");
  } catch (error) {
    setStatus(error.message || "导入失败", "error");
  }
}

function templateDisplayName(key, meta = {}) {
  const k = String(key || "").trim();
  if (!k) return "";
  const m = meta?.[k];
  if (!m || !m.shared) {
    // Fallback: template_meta missing 时，尝试从 shared::<owner>::<name> 中解析展示名。
    if (k.startsWith("shared::")) {
      const parts = k.split("::", 3);
      if (parts.length >= 3) {
        const owner = String(parts[1] || "").trim();
        const name = String(parts[2] || "").trim() || k;
        return owner ? `${name}（共享自 ${owner}）` : name;
      }
    }
    return k;
  }
  const owner = String(m.source_username || "").trim();
  const name = String(m.template_name || k).trim() || k;
  return owner ? `${name}（共享自 ${owner}）` : name;
}

function isOwnTemplate(key, meta = {}) {
  const m = meta?.[key];
  if (!m || !m.shared) return true;
  const owner = String(m.source_username || "").trim();
  const me = String(currentUser?.username || "").trim();
  return !owner || !me || owner === me;
}

function getConfigForCurrentTemplate() {
  const name = String(currentConfigTemplate || "").trim();
  const cfg = currentTemplatesByName?.[name];
  return cfg && typeof cfg === "object" ? cfg : {};
}

async function fetchConfigByTemplateName(templateName) {
  const next = String(templateName || "").trim();
  if (!next) return null;
  try {
    const data = await request("/api/admin/config_template/select", {
      method: "POST",
      body: JSON.stringify({ template_name: next }),
    });
    if (data?.configs_by_template && typeof data.configs_by_template === "object") {
      currentTemplatesByName = data.configs_by_template;
    }
    if (data?.template_meta && typeof data.template_meta === "object") {
      currentTemplateMeta = data.template_meta;
    }
    currentConfigTemplate = String(data?.active_template || next).trim() || next;
    renderConfigTemplateOptions(currentTemplatesByName, currentConfigTemplate, currentTemplateMeta);
    return data?.config && typeof data.config === "object" ? data.config : getConfigForCurrentTemplate();
  } catch (_) {
    return null;
  }
}

function renderConfigTemplateOptions(templates = {}, activeTemplate = "default", meta = {}) {
  if (!activeConfigTemplateSelect) return;
  const names = Object.keys(templates || {}).sort((a, b) => a.localeCompare(b));
  if (!names.length) names.push("default");
  activeConfigTemplateSelect.innerHTML = names
    .map((name) => `<option value="${name}">${templateDisplayName(name, meta)}</option>`)
    .join("");
  const defaultTemplate = names.includes("default") ? "default" : names[0];
  const next = names.includes(activeTemplate) ? activeTemplate : defaultTemplate;
  activeConfigTemplateSelect.value = next;
  currentConfigTemplate = next;
  currentTemplateMeta = meta || {};
  currentTemplatesByName = templates || {};

  const own = isOwnTemplate(next, currentTemplateMeta);
  if (renameConfigTemplateBtn) renameConfigTemplateBtn.disabled = !own;
  if (deleteConfigTemplateBtn) deleteConfigTemplateBtn.disabled = !own;
  if (grantConfigTemplateBtn) grantConfigTemplateBtn.disabled = !own;
  if (revokeConfigTemplateBtn) revokeConfigTemplateBtn.disabled = !own;
  renderBatchTemplateOptions();
  renderTemplateInventory();
}

function renderBatchTemplateOptions() {
  if (!batchTemplateSelect) return;
  const names = Object.keys(currentTemplatesByName || {}).sort((a, b) => a.localeCompare(b));
  if (!names.length) names.push("default");
  const current = String(batchTemplateSelect.value || "").trim();
  batchTemplateSelect.innerHTML = names
    .map((name) => `<option value="${name}">${templateDisplayName(name, currentTemplateMeta)}</option>`)
    .join("");
  batchTemplateSelect.value = names.includes(current) ? current : names[0];
  if (applyTemplateSelectedBtn) {
    applyTemplateSelectedBtn.disabled = names.length === 0;
  }
}

function listOwnDeletableTemplateNames() {
  return Object.keys(currentTemplatesByName || {}).filter((name) => {
    const meta = currentTemplateMeta?.[name] || {};
    const own = isOwnTemplate(name, currentTemplateMeta);
    const editable = meta.editable !== false;
    return own && editable;
  });
}

let lastTemplateInventoryData = null;

function templateInventoryRowCanDelete(templateKey) {
  const k = String(templateKey || "").trim();
  if (!k || k === "default") return false;
  return listOwnDeletableTemplateNames().includes(k);
}

function paintTemplateInventoryFromLastFetch() {
  if (!templateInventoryListEl) return;
  const d = lastTemplateInventoryData;
  if (!d) {
    templateInventoryListEl.innerHTML = '<div class="hint">暂无清单数据，请点击「刷新模板列表」</div>';
    if (templateInventorySummaryEl) templateInventorySummaryEl.textContent = "";
    return;
  }
  const me = String(currentUser?.username || "").trim();
  const parts = [];
  const showAllUsers = Boolean(templateInventoryAllUsersEl?.checked && currentUser?.role === "admin");
  const allUsersRows = Array.isArray(d.all_users_rows) ? d.all_users_rows : [];

  if (showAllUsers) {
    if (!allUsersRows.length) {
      templateInventoryListEl.innerHTML = '<div class="hint">全站暂无 configs_by_template 数据行</div>';
      if (templateInventorySummaryEl) templateInventorySummaryEl.textContent = "已请求全站原始键视图（数据库无记录）";
      return;
    }
    allUsersRows.forEach((row) => {
      const u = String(row?.username || "").trim();
      if (!u) return;
      const rawKeys = Array.isArray(row.raw_keys) ? row.raw_keys : [];
      const invalidKeys = new Set(Array.isArray(row.invalid_keys) ? row.invalid_keys : []);
      parts.push(`<div style="margin-top:10px;font-weight:600;">用户 <code>${escapeHtml(u)}</code>：原始键 ${rawKeys.length} 个（损坏值 ${invalidKeys.size} 个）</div>`);
      rawKeys.forEach((name) => {
        const inv = invalidKeys.has(name);
        const canDel = u === me && templateInventoryRowCanDelete(name) && !inv;
        const checked = canDel && name !== currentConfigTemplate ? "checked" : "";
        const disabled = canDel ? "" : "disabled";
        const label = templateDisplayName(name, currentTemplateMeta) || name;
        const tip = u !== me ? "非当前登录用户，仅展示" : (inv ? "值非对象，无法通过此处删除" : (canDel ? "可删除" : "不可删"));
        parts.push(`<label style="display:flex;align-items:center;gap:8px;padding:4px 0;border-bottom:1px dashed #f0f0f0;">
          <input type="checkbox" class="template-inventory-check" data-template-name="${escapeHtml(name)}" ${checked} ${disabled} />
          <span style="flex:1;"><span class="hint">${escapeHtml(u)} /</span> ${escapeHtml(label)} <span class="hint">(key: ${escapeHtml(name)})</span>${inv ? ' <span class="hint">[损坏]</span>' : ""}</span>
          <span class="hint">${escapeHtml(tip)}</span>
        </label>`);
      });
    });
    if (templateInventorySummaryEl) {
      templateInventorySummaryEl.textContent = `全站账号 ${allUsersRows.length} 个（数据库 configs_by_template 原始键，未过滤）`;
    }
    templateInventoryListEl.innerHTML = parts.join("");
    return;
  }

  const orphans = Array.isArray(d.orphan_alias_refs) ? d.orphan_alias_refs : [];
  if (orphans.length) {
    parts.push('<div style="margin-bottom:6px;font-weight:600;">别名引用但模板不存在</div>');
    orphans.forEach((o) => {
      const mode = escapeHtml(o?.mode || "");
      const alias = escapeHtml(o?.alias || "");
      const ref = escapeHtml(o?.ref || "");
      parts.push(`<div class="hint" style="padding:4px 0;border-bottom:1px dashed #eee;">${mode} / alias <code>${alias}</code> → <code>${ref}</code></div>`);
    });
  }

  const raw = new Set(d.raw_own_keys || []);
  const eff = new Set(d.effective_keys || []);
  const bad = new Set(d.raw_invalid_value_keys || []);
  const union = Array.from(new Set([...raw, ...eff])).sort((a, b) => a.localeCompare(b));

  parts.push('<div style="margin:10px 0 6px;font-weight:600;">当前账号：原始键 ∪ 合并可见键</div>');
  union.forEach((name) => {
    const tags = [];
    if (raw.has(name)) tags.push("原始DB");
    if (eff.has(name)) tags.push("合并可见");
    if (bad.has(name)) tags.push("值损坏");
    const inv = bad.has(name);
    const canDel = templateInventoryRowCanDelete(name) && !inv;
    const checked = canDel && name !== currentConfigTemplate ? "checked" : "";
    const disabled = canDel ? "" : "disabled";
    const label = templateDisplayName(name, currentTemplateMeta) || name;
    const tip = inv ? "值非对象" : (canDel ? "可删除" : (name === "default" ? "默认不可删" : "共享或不可删"));
    parts.push(`<label style="display:flex;align-items:center;gap:8px;padding:4px 0;border-bottom:1px dashed #f0f0f0;">
      <input type="checkbox" class="template-inventory-check" data-template-name="${escapeHtml(name)}" ${checked} ${disabled} />
      <span style="flex:1;">${escapeHtml(label)} <span class="hint">(key: ${escapeHtml(name)})</span> <span class="hint">[${escapeHtml(tags.join(" · ") || "—")}]</span></span>
      <span class="hint">${escapeHtml(tip)}</span>
    </label>`);
  });

  templateInventoryListEl.innerHTML = parts.join("") || '<div class="hint">无模板键</div>';
  if (templateInventorySummaryEl) {
    const act = d.stored_active_template ? String(d.stored_active_template) : "";
    templateInventorySummaryEl.textContent = `原始键 ${raw.size} 个，合并可见 ${eff.size} 个，去重合并 ${union.size} 个；别名缺失引用 ${orphans.length} 条${act ? `；服务端 active_template：${act}` : ""}`;
  }
}

async function refreshTemplateInventoryFromServer() {
  if (!templateInventoryListEl) return;
  const allUsers = templateInventoryAllUsersEl?.checked ? 1 : 0;
  lastTemplateInventoryData = null;
  templateInventoryListEl.innerHTML = '<div class="hint">加载模板清单…</div>';
  try {
    const data = await request(`/api/admin/template_inventory?all_users=${allUsers}&_ts=${Date.now()}`);
    lastTemplateInventoryData = data.data || null;
    paintTemplateInventoryFromLastFetch();
  } catch (error) {
    lastTemplateInventoryData = null;
    templateInventoryListEl.innerHTML = `<div class="hint">${escapeHtml(error.message || "加载模板清单失败")}</div>`;
    if (templateInventorySummaryEl) templateInventorySummaryEl.textContent = "";
  }
}

function renderTemplateInventory() {
  void refreshTemplateInventoryFromServer();
}

function getSelectedTemplateNamesForDeletion() {
  if (!templateInventoryListEl) return [];
  return Array.from(templateInventoryListEl.querySelectorAll(".template-inventory-check:checked"))
    .map((el) => String(el.getAttribute("data-template-name") || "").trim())
    .filter(Boolean);
}

function getCurrentTemplateSourceInfo() {
  const meta = currentTemplateMeta?.[currentConfigTemplate] || {};
  const own = isOwnTemplate(currentConfigTemplate, currentTemplateMeta);
  if (own) {
    return {
      own: true,
      source_username: String(currentUser?.username || "").trim(),
      template_name: String(currentConfigTemplate || "").trim(),
    };
  }
  return {
    own: false,
    source_username: String(meta.source_username || "").trim(),
    template_name: String(meta.template_name || currentConfigTemplate || "").trim(),
  };
}

function describeCurrentTemplate() {
  const key = String(currentConfigTemplate || "").trim() || "default";
  const label = templateDisplayName(key, currentTemplateMeta) || key;
  const source = getCurrentTemplateSourceInfo();
  const owner = source?.source_username ? `，来源用户：${source.source_username}` : "";
  return `当前模板：${label}（key: ${key}${owner}）`;
}

function setTemplateGrantStatus(text = "") {
  if (!templateGrantStatusEl) return;
  templateGrantStatusEl.textContent = String(text || "").trim();
}

async function loadConfigTargetUsers() {
  if (currentUser?.role !== "admin" || !configTargetUserSelect) return;
  if (configTargetUsersLoaded) return;
  const data = await request("/api/admin/users");
  const users = Array.isArray(data?.items) ? data.items : [];
  const options = users
    .filter((item) => String(item?.username || "").trim())
    .filter((item) => String(item?.username || "").trim() !== String(currentUser?.username || "").trim())
    .sort((a, b) => String(a.username || "").localeCompare(String(b.username || "")));
  configTargetUserSelect.innerHTML = [
    '<option value="">请选择用户</option>',
    ...options.map((item) => `<option value="${String(item.username)}">${String(item.username)}</option>`),
  ].join("");
  configTargetUsersLoaded = true;
}

function collectConfig() {
  const formData = new FormData(configForm);
  const payload = {};
  for (const [key, value] of formData.entries()) {
    if (key === "config_mode") continue;
    if (value === "") continue;
    if (key === "action_single") {
      payload[key] = value === "on";
      continue;
    }
    if (key === "comment_texts") {
      const list = value
        .split(/\n|,/) 
        .map((item) => item.trim())
        .filter(Boolean);
      if (list.length) payload[key] = list;
      continue;
    }
    if (key === "skip_post_click_prob") {
      payload[key] = Number(value);
      continue;
    }
    payload[key] = Number(value);
  }
  // follower_queue 批次：控件在「直客访问模式数据」页，不在 configForm 内，保存运行配置时需合并
  if (processQueueBatchSizeInput && processQueueBatchSizeInput.value.trim() !== "") {
    const v = Number(processQueueBatchSizeInput.value);
    if (Number.isFinite(v)) {
      payload.process_queue_batch_size = Math.max(1, Math.min(1000, Math.floor(v)));
    }
  }
  return payload;
}

async function saveConfig() {
  const payload = collectConfig();
  const selectedTemplate = String(activeConfigTemplateSelect?.value || currentConfigTemplate || "default").trim() || "default";
  console.log("[saveConfig] clicked", {
    currentRunMode,
    currentConfigTemplate,
    selectedTemplate,
    payload,
  });
  if (Object.keys(payload).length === 0) {
    setStatus("请填写配置项", "warning");
    console.warn("[saveConfig] empty payload");
    return;
  }
  try {
    setStatus(`保存账号独立配置（${currentRunMode}）中...`, "info");
    console.log("[saveConfig] sending update_config", {
      currentRunMode,
      currentConfigTemplate,
      payload,
    });
    const response = await request("/api/admin/update_config", {
      method: "POST",
      body: JSON.stringify({
        ...payload,
        template_name: selectedTemplate,
      }),
    });
    console.log("[saveConfig] update_config response", response);

    const savedTemplate = String(response?.active_template || selectedTemplate || currentConfigTemplate || "default").trim() || "default";
    currentConfigTemplate = savedTemplate;
    localStorage.setItem(LAST_TEMPLATE_KEY, savedTemplate);

    // 强制回显：先立即把刚保存的值写回表单，避免接口延迟或返回旧快照导致页面看起来没变。
    fillConfigForm({ ...collectConfig(), ...payload }, currentRunMode);
    syncCvQualityFilterInputsFromConfig({ ...collectConfig(), ...payload });
    persistCommentTextsToLocal();

    const echoed = response?.config && typeof response.config === "object" ? response.config : payload;
    fillConfigForm(echoed, currentRunMode);
    syncCvQualityFilterInputsFromConfig(echoed);
    persistCommentTextsToLocal();
    if (response?.configs_by_template && typeof response.configs_by_template === "object") {
      currentTemplatesByName = response.configs_by_template;
    }
    if (response?.template_meta && typeof response.template_meta === "object") {
      currentTemplateMeta = response.template_meta;
    }
    console.log("[saveConfig] applying template selection", {
      savedTemplate,
      currentTemplatesByName,
      currentTemplateMeta,
    });
    renderConfigTemplateOptions(currentTemplatesByName, savedTemplate, currentTemplateMeta);
    localStorage.setItem(LAST_TEMPLATE_KEY, savedTemplate);
    if (activeConfigTemplateSelect) {
      activeConfigTemplateSelect.value = savedTemplate;
      console.log("[saveConfig] select forced to", activeConfigTemplateSelect.value);
    }
    currentConfigTemplate = savedTemplate;
    setStatus(`已保存当前账号的 ${currentRunMode} 模式配置（模板：${savedTemplate}）`, "success");
  } catch (error) {
    setStatus(error.message || "保存配置失败", "error");
  }
}

function syncCvQualityFilterInputsFromConfig(config = {}) {
  if (processQueueBatchSizeInput) {
    const v = Number(config.process_queue_batch_size);
    processQueueBatchSizeInput.value = Number.isFinite(v) ? String(v) : "100";
  }
  if (cvFilterMinVideoCountEl) {
    const v = Number(config.following_filter_min_video_count);
    cvFilterMinVideoCountEl.value = Number.isFinite(v) ? String(v) : "10";
  }
  if (cvFilterMaxFollowingCountEl) {
    const v = Number(config.following_filter_max_following_count);
    cvFilterMaxFollowingCountEl.value = Number.isFinite(v) ? String(v) : "150";
  }
}

function randomDelayMsFromSecRange(minSec, maxSec) {
  const min = Math.max(0, Number.isFinite(minSec) ? Number(minSec) : 0);
  const max = Math.max(min, Number.isFinite(maxSec) ? Number(maxSec) : min);
  const minMs = Math.floor(min * 1000);
  const maxMs = Math.floor(max * 1000);
  if (maxMs <= minMs) return minMs;
  return Math.floor(Math.random() * (maxMs - minMs + 1)) + minMs;
}

async function saveCvQualityFilter() {
  const minVideoRaw = Number(cvFilterMinVideoCountEl?.value || 10);
  const maxFollowingRaw = Number(cvFilterMaxFollowingCountEl?.value || 150);
  const following_filter_min_video_count = Math.max(0, Number.isFinite(minVideoRaw) ? Math.floor(minVideoRaw) : 10);
  const following_filter_max_following_count = Math.max(1, Number.isFinite(maxFollowingRaw) ? Math.floor(maxFollowingRaw) : 150);

  try {
    setStatus("保存筛选阈值中...", "info");
    const data = await request("/api/admin/update_config", {
      method: "POST",
      body: JSON.stringify({
        following_filter_min_video_count,
        following_filter_max_following_count,
      }),
    });
    await loadUserRuntimeConfig();
    const echoed = getConfigForCurrentTemplate();
    fillConfigForm(echoed, currentRunMode);
    syncCvQualityFilterInputsFromConfig(echoed);
    setStatus("筛选阈值已更新", "success");
  } catch (error) {
    setStatus(error.message || "筛选阈值更新失败", "error");
  }
}

function renderCvAccessTable(items) {
  if (!cvAccessTableBodyEl) return;
  if (!items || !items.length) {
    cvAccessTableBodyEl.innerHTML = '<tr><td colspan="11" style="padding:10px;color:#666;">暂无数据</td></tr>';
    return;
  }
  cvAccessTableBodyEl.innerHTML = items
    .map((item) => `
      <tr>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.id ?? ""}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.username ?? ""}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.root_username ?? ""}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.created_at ?? ""}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.consume_status ?? "pending"}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.consumed_at ?? ""}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.consumed_by ?? ""}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.consumed_mode ?? ""}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.following_access_status ?? "unknown"}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.following_fetch_success ?? 0}</td>
        <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.mobile_push_status ?? "pending"}</td>
      </tr>
    `)
    .join("");
}

function cvPageSize() {
  const raw = Number(cvPageSizeEl?.value || 100);
  if (!Number.isFinite(raw)) return 100;
  return Math.max(1, Math.floor(raw));
}

function applyCvAccessView() {
  const status = (cvAccessFilterEl?.value || "").trim();
  let items = Array.isArray(cvAccessAllItems) ? cvAccessAllItems : [];
  if (status) {
    items = items.filter(
      (x) => String(x.following_access_status || "").trim() === status
    );
  }

  const pageSize = cvPageSize();
  const total = cvAccessUseServerPaging ? cvAccessTotal : items.length;
  const totalPages = cvAccessUseServerPaging
    ? Math.max(1, Number(cvAccessTotalPages || 1))
    : Math.max(1, Math.ceil(total / pageSize));

  if (currentCvPage > totalPages) currentCvPage = totalPages;
  if (currentCvPage < 1) currentCvPage = 1;

  const start = (currentCvPage - 1) * pageSize;
  const pageItems = cvAccessUseServerPaging ? items : items.slice(start, start + pageSize);
  renderCvAccessTable(pageItems);

  if (cvPageInfoEl) {
    cvPageInfoEl.textContent = `第 ${currentCvPage} / ${totalPages} 页 | 每页 ${pageSize} | 总数 ${total}`;
  }
  if (cvPrevPageBtn) cvPrevPageBtn.disabled = currentCvPage <= 1;
  if (cvNextPageBtn) cvNextPageBtn.disabled = currentCvPage >= totalPages;
}

function mapFollowerQueueRowToCvItem(row) {
  return {
    username: row?.username ?? "",
    root_username: row?.root_username ?? "",
    following_access_status: row?.following_access_status ?? row?.access_status ?? "unknown",
    mobile_push_status: row?.mobile_push_status ?? row?.push_status ?? "pending",
    created_at: row?.created_at ?? "",
  };
}

async function loadCvAccessDataFromTablePage() {
  const pageSize = cvPageSize();
  const table = "follower_queue";
  const data = await request(
    `/api/admin/table_page?table=${encodeURIComponent(table)}&page=${currentCvPage}&page_size=${pageSize}`
  );

  const items = Array.isArray(data.items) ? data.items : [];
  cvAccessAllItems = items.map(mapFollowerQueueRowToCvItem);
  cvAccessUseServerPaging = true;
  cvAccessTotal = Number(data.total || 0);
  cvAccessTotalPages = Math.max(1, Math.ceil(cvAccessTotal / pageSize));
  // table_page 返回的 page 可能被服务端修正
  if (Number.isFinite(Number(data.page))) currentCvPage = Number(data.page);
}

async function loadCvAccessData() {
  const pageSize = cvPageSize();
  try {
    const data = await request("/api/admin/follower_queue?limit=500");
    const items = Array.isArray(data.items) ? data.items : [];
    if (items.length) {
      cvAccessAllItems = items;
      cvAccessUseServerPaging = false;
      cvAccessTotal = items.length;
      cvAccessTotalPages = Math.max(1, Math.ceil(cvAccessTotal / pageSize));
    } else {
      await loadCvAccessDataFromTablePage();
    }
  } catch (e) {
    // follower_queue 快照明细不可用时回退到 table_page
    await loadCvAccessDataFromTablePage();
  }
  applyCvAccessView();
}

async function loadAccounts() {
  const [liveRes, rootsRes] = await Promise.allSettled([
    request("/api/admin/live_customers"),
    loadTiktokRootsData(),
  ]);

  if (liveRes.status === "fulfilled") {
    renderTagList(liveCustomerList, liveRes.value.items || [], {
      formatter: (item) => `${item.username} | usage:${item.usage_count} | ${item.created_at}`,
    });
  } else {
    renderTagList(liveCustomerList, []);
  }

  if (rootsRes.status === "fulfilled") {
    renderTikTokRootsTable(rootsRes.value.items || []);
  } else {
    renderTikTokRootsTable([]);
  }

  if (liveRes.status === "rejected" || rootsRes.status === "rejected") {
    const messages = [];
    if (liveRes.status === "rejected") messages.push(`live_customers 加载失败：${liveRes.reason?.message || "unknown"}`);
    if (rootsRes.status === "rejected") messages.push(`tiktok_roots 加载失败：${rootsRes.reason?.message || "unknown"}`);
    setStatus(messages.join(" | "), "warning");
  }

  await loadCvAccessData();
}

function syncTiktokRootsSelectionCount() {
  if (!tiktokRootsSelectionCountEl) return;
  tiktokRootsSelectionCountEl.textContent = `${selectedTiktokRootIds.size} 已选`;
}

function renderTikTokRootsTable(items) {
  if (!tiktokRootsTableBody) return;
  const rows = Array.isArray(items) ? items : [];
  if (!rows.length) {
    tiktokRootsTableBody.innerHTML = '<tr><td colspan="5" style="padding:10px;color:#666;">暂无数据</td></tr>';
    syncTiktokRootsSelectionCount();
    return;
  }
  tiktokRootsTableBody.innerHTML = rows
    .map((item, idx) => {
      const key = item.root_username ?? "";
      const checked = selectedTiktokRootIds.has(key) ? "checked" : "";
      const globalIndex = ((currentTiktokRootsPage - 1) * (Number(tiktokRootsPageSizeEl?.value || 100)) + idx + 1);
      return `
      <tr>
        <td><input type="checkbox" class="tiktok-root-check" data-root="${key}" ${checked} /></td>
        <td>${globalIndex}</td>
        <td>${item.root_username ?? ""}</td>
        <td>${item.roots_type ?? "-"}</td>
        <td>${item.usage_count ?? 0}</td>
      </tr>
    `;
    })
    .join("");
  bindTiktokRootSelection();
  syncTiktokRootsSelectionCount();
}

function bindTiktokRootSelection() {
  document.querySelectorAll(".tiktok-root-check").forEach((checkbox) => {
    checkbox.addEventListener("change", (event) => {
      const id = event.target.dataset.root;
      if (!id) return;
      if (event.target.checked) selectedTiktokRootIds.add(id);
      else selectedTiktokRootIds.delete(id);
      syncTiktokRootsSelectionCount();
    });
  });
}

function currentTiktokRootsPageItems() {
  return Array.from(tiktokRootsTableBody?.querySelectorAll(".tiktok-root-check") || []);
}

function updateTiktokRootsPageUi() {
  if (tiktokRootsPageInfoEl) {
    tiktokRootsPageInfoEl.textContent = `第 ${currentTiktokRootsPage} / ${tiktokRootsTotalPages} 页 | 每页 ${Number(tiktokRootsPageSizeEl?.value || 100)} | 总数 ${tiktokRootsTotal}`;
  }
  if (tiktokRootsPrevPageBtn) tiktokRootsPrevPageBtn.disabled = currentTiktokRootsPage <= 1;
  if (tiktokRootsNextPageBtn) tiktokRootsNextPageBtn.disabled = currentTiktokRootsPage >= tiktokRootsTotalPages;
}

function selectCurrentTiktokRootsPage(checked) {
  currentTiktokRootsPageItems().forEach((checkbox) => {
    checkbox.checked = checked;
    const id = checkbox.dataset.root;
    if (!id) return;
    if (checked) selectedTiktokRootIds.add(id);
    else selectedTiktokRootIds.delete(id);
  });
  syncTiktokRootsSelectionCount();
}

async function addSelectedRootsToPending() {
  const selected = Array.from(selectedTiktokRootIds);
  if (!selected.length) {
    setStatus("请先勾选根用户", "warning");
    return;
  }
  try {
    setStatus("正在写入 mobile_pending_usernames...", "info");
    const data = await request("/api/admin/mobile_pending_usernames/add_from_roots_selection", {
      method: "POST",
      body: JSON.stringify({ usernames: selected }),
    });
    await loadOverview();
    await loadAccounts();
    setStatus(`已加入待下发队列：${data.count ?? 0} 条`, "success");
  } catch (error) {
    setStatus(error.message || "写入失败", "error");
  }
}

async function addAllRootsToPending() {
  try {
    const pageSize = 500;
    let page = 1;
    let totalPages = 1;
    const allUsernames = [];
    while (page <= totalPages) {
      setStatus(`正在收集 tiktok_roots 全量用户名（${page}/${totalPages}）...`, "info");
      const data = await request(
        `/api/admin/table_page?table=tiktok_roots&page=${page}&page_size=${pageSize}`
      );
      const items = Array.isArray(data.items)
        ? data.items
        : (Array.isArray(data.rows) ? data.rows : (Array.isArray(data.data) ? data.data : []));
      items.forEach((item) => {
        const username = String(item?.root_username || "").trim();
        if (username) allUsernames.push(username);
      });
      const total = Number(data.total || 0);
      totalPages = Math.max(1, Math.ceil(total / pageSize));
      page += 1;
    }

    const usernames = Array.from(new Set(allUsernames));
    if (!usernames.length) {
      setStatus("tiktok_roots 为空，无可下发用户", "warning");
      return;
    }

    setStatus(`正在写入待下发队列（共 ${usernames.length} 条）...`, "info");
    const result = await request("/api/admin/mobile_pending_usernames/add_from_roots_selection", {
      method: "POST",
      body: JSON.stringify({ usernames }),
    });
    await loadOverview();
    await loadAccounts();
    setStatus(
      `已将全部根用户写入待下发队列：${result.count ?? result.inserted ?? 0} 条（去重后 ${usernames.length}）`,
      "success",
    );
  } catch (error) {
    setStatus(error.message || "全量加入待下发队列失败", "error");
  }
}

async function loadTiktokRootsData() {
  const pageSize = Number(tiktokRootsPageSizeEl?.value || 100);
  // 与“数据库分页”保持同源，避免不同后端版本的专用接口行为差异。
  const data = await request(
    `/api/admin/table_page?table=tiktok_roots&page=${currentTiktokRootsPage}&page_size=${pageSize}`
  );
  const items = Array.isArray(data.items)
    ? data.items
    : (Array.isArray(data.rows) ? data.rows : (Array.isArray(data.data) ? data.data : []));
  tiktokRootsItems = items;
  tiktokRootsUseServerPaging = true;
  tiktokRootsTotal = Number(data.total || 0);
  tiktokRootsTotalPages = Math.max(1, Math.ceil(tiktokRootsTotal / pageSize));
  if (Number.isFinite(Number(data.page))) currentTiktokRootsPage = Number(data.page);
  updateTiktokRootsPageUi();
  return data;
}

async function addTikTokRoots() {
  const input = document.getElementById("rootUserInput");
  const raw = input.value.split("\n").map((item) => item.trim());
  const usernames = raw.filter(Boolean);
  const roots_type = (rootTypeInput?.value || "").trim();
  if (!usernames.length) {
    setStatus("请填写根用户", "warning");
    return;
  }

  try {
    setStatus("添加根用户到 tiktok_roots 中...", "info");
    const data = await request("/api/admin/tiktok_roots", {
      method: "POST",
      body: JSON.stringify({ usernames, roots_type }),
    });
    input.value = "";
    currentTiktokRootsPage = 1;
    selectedTiktokRootIds.clear();
    await loadAccounts();
    await refreshTaskStatus();
    setStatus(`已添加到 tiktok_roots：${data.inserted}/${data.total_rows}`, "success");
  } catch (error) {
    setStatus(error.message || "添加根用户失败", "error");
  }
}

async function addMobilePendingUsernames() {
  const input = mobilePendingInput;
  const raw = (input?.value || "").split("\n").map((item) => item.trim());
  const usernames = raw.filter(Boolean);
  if (!usernames.length) {
    setStatus("请填写要加入待下发队列的用户名", "warning");
    return;
  }
  try {
    setStatus("写入 mobile_pending_usernames 中...", "info");
    const data = await request("/api/admin/mobile_pending_usernames/add", {
      method: "POST",
      body: JSON.stringify({ usernames }),
    });
    if (input) input.value = "";
    await loadOverview();
    await loadAccounts();
    setStatus(`已写入待下发队列：${data.inserted ?? data.count ?? 0} 条`, "success");
  } catch (error) {
    setStatus(error.message || "写入待下发队列失败", "error");
  }
}

async function resetTikTokRootsUsage() {
  try {
    setStatus("正在将 usage_count 归零...", "info");
    const data = await request("/api/admin/tiktok_roots/reset_usage", {
      method: "POST",
    });
    await loadAccounts();
    setStatus(`usage_count 已归零（影响 ${data.updated ?? 0} 条）`, "success");
  } catch (error) {
    setStatus(error.message || "usage_count 归零失败", "error");
  }
}

async function importTikTokRoots() {
  const file = tiktokRootsFileInput?.files?.[0];
  if (!file) {
    setStatus("请先选择 Excel/TXT 文件", "warning");
    return;
  }
  try {
    setStatus("正在导入根用户表格到 tiktok_roots...", "info");
    const formData = new FormData();
    formData.append("file", file);
    const response = await fetch(`${apiBase()}/api/admin/tiktok_roots/import`, {
      method: "POST",
      body: formData,
    });
    const data = await response.json();
    if (!response.ok || data.code !== 0) {
      throw new Error(data.msg || "导入失败");
    }
    tiktokRootsFileInput.value = "";
    await loadAccounts();
    await refreshTaskStatus();
    setStatus(`根用户导入完成：${data.inserted}/${data.total_rows}`, "success");
  } catch (error) {
    setStatus(error.message || "导入根用户失败", "error");
  }
}

async function triggerTask(path, successText) {
  try {
    setStatus("任务触发中...", "info");
    const container_ids = (taskContainerIdsInput?.value || "").trim();
    const parallelRaw = Number(taskParallelInput?.value || 1);
    const parallel = Math.max(1, Number.isFinite(parallelRaw) ? parallelRaw : 1);
    const joiner = path.includes("?") ? "&" : "?";
    const query = `${joiner}container_ids=${encodeURIComponent(container_ids)}&parallel=${parallel}`;
    const data = await request(`${path}${query}`, { method: "POST" });
    await refreshTaskStatus();
    if (data.workers) {
      setStatus(`${successText}（workers=${data.workers}）`, "success");
    } else {
      setStatus(`${successText}（pid=${data.pid ?? "-"}）`, "success");
    }
  } catch (error) {
    setStatus(error.message || "任务触发失败", "error");
  }
}

async function stopTaskByPrefix(prefix, successText) {
  try {
    setStatus("正在停止任务...", "info");
    const data = await request("/api/admin/tasks/stop_by_prefix", {
      method: "POST",
      body: JSON.stringify({ prefix }),
    });
    await refreshTaskStatus();
    setStatus(`${successText}（停止 ${data.stopped_count ?? 0} 个进程）`, "success");
  } catch (error) {
    setStatus(error.message || "停止任务失败", "error");
  }
}

async function triggerTaskWithOptions(path, successText, options = {}) {
  const containerIdsInput = options.containerIdsInput || taskContainerIdsInput;
  const parallelInput = options.parallelInput || taskParallelInput;
  try {
    setStatus("任务触发中...", "info");
    const container_ids = (containerIdsInput?.value || "").trim();
    const parallelRaw = Number(parallelInput?.value || 1);
    const parallel = Math.max(1, Number.isFinite(parallelRaw) ? parallelRaw : 1);
    const joiner = path.includes("?") ? "&" : "?";
    const query = `${joiner}container_ids=${encodeURIComponent(container_ids)}&parallel=${parallel}`;
    const data = await request(`${path}${query}`, { method: "POST" });
    await refreshTaskStatus();
    if (data.workers) {
      setStatus(`${successText}（workers=${data.workers}）`, "success");
    } else {
      setStatus(`${successText}（pid=${data.pid ?? "-"}）`, "success");
    }
  } catch (error) {
    setStatus(error.message || "任务触发失败", "error");
  }
}

async function refreshTaskStatus() {
  try {
    const data = await request("/api/admin/tasks/status");
    const taskLogs = data.task_logs || {};
    const noLogsText = data?.scheduler_enabled
      ? "暂无日志"
      : "暂无日志（定时任务已停止，process_queue 不会启动）";
    const rootsLogPatterns = [
      /collect_roots/i,
      /autosearch/i,
    ];
    const queueLogPatterns = [
      /process_queue/i,
      /trigger_process_queue/i,
      /follower_queue/i,
    ];
    if (taskStatusEl) {
      const taskStatusLines = [
        `scheduler_enabled: ${data.scheduler_enabled}`,
        `running_tasks: ${JSON.stringify(data.running_tasks || {})}`,
        `auto_search_schedule: ${JSON.stringify(data.auto_search_schedule || {})}`,
      ];
      if (data?.last_device_op) {
        taskStatusLines.push("", "最近设备操作:", summarizeDeviceOpResult(data.last_device_op));
      }
      taskStatusEl.textContent = taskStatusLines.join("\n");
    }
    const names = Object.keys(taskLogs);
    const renderLogsByPatterns = (patterns, emptyText) => {
      const matchedNames = names.filter((name) => patterns.some((re) => re.test(String(name))));
      if (!matchedNames.length) return emptyText;
      return matchedNames
        .map((name) => `### ${name}\n${(taskLogs[name] || []).join("\n")}`)
        .join("\n\n");
    };
    const rootsLogsText = renderLogsByPatterns(rootsLogPatterns, "暂无本页按钮相关日志（collect_roots / autoSearch）");
    const queueLogsText = renderLogsByPatterns(queueLogPatterns, noLogsText);
    if (taskLogsEl) {
      taskLogsEl.textContent = rootsLogsText;
    }
    if (cvTaskLogsEl) {
      cvTaskLogsEl.textContent = queueLogsText;
    }
  } catch (error) {
    if (taskStatusEl) {
      taskStatusEl.textContent = `任务状态读取失败: ${error.message || "unknown"}`;
    }
    if (taskLogsEl) {
      taskLogsEl.textContent = "日志读取失败";
    }
    if (cvTaskLogsEl) {
      cvTaskLogsEl.textContent = "日志读取失败";
    }
  }
}

async function startSchedulers() {
  try {
    await request("/api/admin/tasks/scheduler/start", { method: "POST" });
    await refreshTaskStatus();
    setStatus("定时任务已开启", "success");
  } catch (error) {
    setStatus(error.message || "开启失败", "error");
  }
}

async function stopSchedulers() {
  try {
    await request("/api/admin/tasks/scheduler/stop", { method: "POST" });
    await refreshTaskStatus();
    setStatus("定时任务已停止，规定时间也不会自动爬取", "success");
  } catch (error) {
    setStatus(error.message || "停止失败", "error");
  }
}

async function loadUserRuntimeConfig() {
  try {
    const data = await request(`/api/admin/runtime_config?_ts=${Date.now()}`);
    const cfg = data.data || {};
    // 刷新后优先沿用本地上次选择的模式，避免被服务端 run_mode 覆盖导致“模板看起来被切回去”。
    const savedMode = localStorage.getItem(RUN_MODE_KEY) || cfg.run_mode || currentRunMode || "growth";
    currentRunMode = savedMode;
    localStorage.setItem(RUN_MODE_KEY, savedMode);
    updateModeButtons();
    const templates = (cfg?.configs_by_template && typeof cfg.configs_by_template === "object")
      ? cfg.configs_by_template
      : {};
    const meta = (cfg?.template_meta && typeof cfg.template_meta === "object")
      ? cfg.template_meta
      : {};
    const serverActive = String(cfg?.active_template || "").trim();
    const rememberedTemplate = String(localStorage.getItem(LAST_TEMPLATE_KEY) || "").trim();
    const defaultTemplate = Object.prototype.hasOwnProperty.call(templates, "default")
      ? "default"
      : (Object.keys(templates)[0] || "default");
    const active = templates[serverActive]
      ? serverActive
      : (templates[rememberedTemplate]
        ? rememberedTemplate
        : defaultTemplate);
    currentConfigTemplate = active;
    localStorage.setItem(LAST_TEMPLATE_KEY, active);
    renderConfigTemplateOptions(templates, active, meta);
    aliasTemplateByMode = (cfg?.alias_template_by_mode && typeof cfg.alias_template_by_mode === "object")
      ? cfg.alias_template_by_mode
      : { growth: {}, live: {}, direct: {} };
    if (latestDevices && typeof latestDevices === "object") {
      renderDevices(latestDevices);
      bindDeviceSelection();
    }
    const modeConfig = await fetchConfigByTemplateName(active) || getConfigForCurrentTemplate();
    fillConfigForm(modeConfig, savedMode);
    syncCvQualityFilterInputsFromConfig(modeConfig);
    setTemplateGrantStatus(describeCurrentTemplate());
    setStatus(`已加载配置。${describeCurrentTemplate()}`, "info");
    if (currentUser?.role === "admin") {
      await loadConfigTargetUsers();
    }
    return { ...cfg, active_template: active };
  } catch (error) {
    setStatus(error.message || "加载用户配置失败", "error");
    return null;
  }
}

async function switchRunMode(mode) {
  try {
    const data = await request("/api/admin/run_mode", {
      method: "POST",
      body: JSON.stringify({ mode }),
    });
    currentRunMode = data.mode || mode;
    localStorage.setItem(RUN_MODE_KEY, currentRunMode);
    updateModeButtons();
    await loadUserRuntimeConfig();
    const echoed = getConfigForCurrentTemplate();
    fillConfigForm(echoed, currentRunMode);
    syncCvQualityFilterInputsFromConfig(echoed);
    const modeText =
      currentRunMode === "live"
        ? "主播访问模式"
        : currentRunMode === "direct"
          ? "直客访问模式"
          : "涨粉模式";
    setStatus(`已切换到${modeText}，已加载该账号独立配置`, "success");
  } catch (error) {
    setStatus(error.message || "切换模式失败", "error");
  }
}

async function selectConfigTemplate(templateName) {
  const next = String(templateName || "").trim();
  if (!next) return;
  try {
    const data = await request("/api/admin/config_template/select", {
      method: "POST",
      body: JSON.stringify({ template_name: next }),
    });
    currentConfigTemplate = String(data?.active_template || next).trim() || next;
    if (data?.configs_by_template && typeof data.configs_by_template === "object") {
      currentTemplatesByName = data.configs_by_template;
    }
    if (data?.template_meta && typeof data.template_meta === "object") {
      currentTemplateMeta = data.template_meta;
    }
    renderConfigTemplateOptions(currentTemplatesByName, currentConfigTemplate, currentTemplateMeta);
    const cfg = getConfigForCurrentTemplate();
    fillConfigForm(cfg, currentRunMode);
    syncCvQualityFilterInputsFromConfig(cfg);
    setStatus(`已切换模板：${templateDisplayName(currentConfigTemplate, currentTemplateMeta)}`, "success");
  } catch (error) {
    setStatus(error.message || "切换模板失败", "error");
    if (activeConfigTemplateSelect) {
      activeConfigTemplateSelect.value = currentConfigTemplate;
    }
  }
}

async function copyCurrentTemplate() {
  const source = String(currentConfigTemplate || "").trim();
  if (!source) {
    setStatus("当前模板无效", "warning");
    return;
  }
  const name = window.prompt("输入新模板名", `${source}_copy`);
  if (name == null) return;
  const nextName = String(name || "").trim();
  if (!nextName) {
    setStatus("模板名不能为空", "warning");
    return;
  }
  try {
    const data = await request("/api/admin/config_template/copy", {
      method: "POST",
      body: JSON.stringify({
        source_template_name: source,
        new_template_name: nextName,
      }),
    });
    currentConfigTemplate = String(data?.active_template || nextName).trim() || nextName;
    currentTemplatesByName = (data?.configs_by_template && typeof data.configs_by_template === "object")
      ? data.configs_by_template
      : currentTemplatesByName;
    currentTemplateMeta = (data?.template_meta && typeof data.template_meta === "object")
      ? data.template_meta
      : currentTemplateMeta;
    renderConfigTemplateOptions(currentTemplatesByName, currentConfigTemplate, currentTemplateMeta);
    const cfg = data?.config && typeof data.config === "object"
      ? data.config
      : currentTemplatesByName?.[currentConfigTemplate] || {};
    fillConfigForm(cfg, currentRunMode);
    syncCvQualityFilterInputsFromConfig(cfg);
    setStatus("模板复制成功", "success");
  } catch (error) {
    setStatus(error.message || "模板复制失败", "error");
  }
}

async function renameCurrentTemplate() {
  const { own } = getCurrentTemplateSourceInfo();
  if (!own) {
    setStatus("共享模板不可重命名，请先复制为新模板", "warning");
    return;
  }
  const current = String(currentConfigTemplate || "").trim();
  const name = window.prompt("输入新模板名", current);
  if (name == null) return;
  const nextName = String(name || "").trim();
  if (!nextName || nextName === current) return;
  try {
    const data = await request("/api/admin/config_template/rename", {
      method: "POST",
      body: JSON.stringify({
        old_template_name: current,
        new_template_name: nextName,
      }),
    });
    currentConfigTemplate = String(data?.active_template || nextName).trim() || nextName;
    currentTemplatesByName = (data?.configs_by_template && typeof data.configs_by_template === "object")
      ? data.configs_by_template
      : currentTemplatesByName;
    currentTemplateMeta = (data?.template_meta && typeof data.template_meta === "object")
      ? data.template_meta
      : currentTemplateMeta;
    renderConfigTemplateOptions(currentTemplatesByName, currentConfigTemplate, currentTemplateMeta);
    setStatus(data?.msg || "模板重命名成功", "success");
  } catch (error) {
    setStatus(error.message || "模板重命名失败", "error");
  }
}

async function deleteCurrentTemplate() {
  const { own } = getCurrentTemplateSourceInfo();
  if (!own) {
    setStatus("共享模板不可删除，请先切换到自己的模板", "warning");
    return;
  }
  const name = String(currentConfigTemplate || "").trim();
  if (!name) return;
  if (!window.confirm(`确认删除模板「${name}」吗？`)) return;
  try {
    const data = await request("/api/admin/config_template/delete", {
      method: "POST",
      body: JSON.stringify({ template_name: name }),
    });
    currentConfigTemplate = String(data?.active_template || "default").trim() || "default";
    currentTemplatesByName = (data?.configs_by_template && typeof data.configs_by_template === "object")
      ? data.configs_by_template
      : {};
    currentTemplateMeta = (data?.template_meta && typeof data.template_meta === "object")
      ? data.template_meta
      : {};
    renderConfigTemplateOptions(currentTemplatesByName, currentConfigTemplate, currentTemplateMeta);
    const cfg = currentTemplatesByName?.[currentConfigTemplate] || {};
    fillConfigForm(cfg, currentRunMode);
    syncCvQualityFilterInputsFromConfig(cfg);
    setStatus(data?.msg || "模板已删除", "success");
  } catch (error) {
    setStatus(error.message || "删除模板失败", "error");
  }
}

async function deleteSelectedTemplates() {
  const selected = getSelectedTemplateNamesForDeletion();
  if (!selected.length) {
    setStatus("请先在模板总览里勾选要删除的模板", "warning");
    return;
  }
  const ownDeletable = new Set(listOwnDeletableTemplateNames().filter((name) => name !== "default"));
  const targets = selected.filter((name) => ownDeletable.has(name));
  if (!targets.length) {
    setStatus("选中的模板都不可删除", "warning");
    return;
  }
  if (!window.confirm(`确认批量删除 ${targets.length} 个模板吗？\n${targets.join("\n")}`)) return;
  let success = 0;
  let failed = 0;
  for (const name of targets) {
    try {
      const data = await request("/api/admin/config_template/delete", {
        method: "POST",
        body: JSON.stringify({ template_name: name }),
      });
      currentConfigTemplate = String(data?.active_template || currentConfigTemplate || "default").trim() || "default";
      currentTemplatesByName = (data?.configs_by_template && typeof data.configs_by_template === "object")
        ? data.configs_by_template
        : currentTemplatesByName;
      currentTemplateMeta = (data?.template_meta && typeof data.template_meta === "object")
        ? data.template_meta
        : currentTemplateMeta;
      success += 1;
    } catch (_) {
      failed += 1;
    }
  }
  renderConfigTemplateOptions(currentTemplatesByName, currentConfigTemplate, currentTemplateMeta);
  const cfg = currentTemplatesByName?.[currentConfigTemplate] || {};
  fillConfigForm(cfg, currentRunMode);
  syncCvQualityFilterInputsFromConfig(cfg);
  if (failed) {
    setStatus(`批量删除完成：成功 ${success}，失败 ${failed}`, "warning");
  } else {
    setStatus(`批量删除完成：成功删除 ${success} 个模板`, "success");
  }
  await refreshTemplateInventoryFromServer();
}

async function setTemplateGrant(enabled) {
  if (currentUser?.role !== "admin") {
    setStatus("仅管理员可操作模板共享", "warning");
    return;
  }
  const target = String(configTargetUserSelect?.value || "").trim();
  if (!target) {
    setStatus("请先选择目标用户", "warning");
    return;
  }
  const sourceInfo = getCurrentTemplateSourceInfo();
  if (!sourceInfo.own) {
    setStatus("共享模板不可直接改授权，请先复制到自己的模板", "warning");
    return;
  }
  if (!sourceInfo.source_username || !sourceInfo.template_name) {
    setStatus("模板信息不完整，无法授权", "warning");
    return;
  }
  try {
    const data = await request("/api/admin/template_grants/upsert", {
      method: "POST",
      body: JSON.stringify({
        target_username: target,
        source_username: sourceInfo.source_username,
        template_name: sourceInfo.template_name,
        enabled: Boolean(enabled),
      }),
    });
    const verify = await request(`/api/admin/template_grants?target_username=${encodeURIComponent(target)}`);
    const grants = Array.isArray(verify?.items) ? verify.items : [];
    const exists = grants.some((item) => {
      return String(item?.target_username || "").trim() === target
        && String(item?.source_username || "").trim() === sourceInfo.source_username
        && String(item?.template_name || "").trim() === sourceInfo.template_name;
    });
    const expected = Boolean(enabled);
    const ok = exists === expected;
    const actionText = enabled ? "授权" : "取消授权";
    const resultText = ok ? "已确认生效" : "状态未达预期，请重试";
    const line = `${actionText}结果：${target} <- ${sourceInfo.source_username}/${sourceInfo.template_name}（${resultText}）`;
    setTemplateGrantStatus(line);
    setStatus(data?.msg || (enabled ? "授权成功" : "已取消授权"), ok ? "success" : "warning");
  } catch (error) {
    setTemplateGrantStatus("");
    setStatus(error.message || (enabled ? "授权失败" : "取消授权失败"), "error");
  }
}

async function importLiveCustomers() {
  const file = liveCustomerFileInput?.files?.[0];
  if (!file) {
    setStatus("请先选择 txt 或 xlsx 文件", "warning");
    return;
  }
  try {
    setStatus("导入主播访问数据中...", "info");
    const formData = new FormData();
    formData.append("file", file);
    const response = await fetch(`${apiBase()}/api/admin/live_customers/import`, {
      method: "POST",
      body: formData,
    });
    const data = await response.json();
    if (!response.ok || data.code !== 0) {
      throw new Error(data.msg || "导入失败");
    }
    liveCustomerFileInput.value = "";
    await loadAccounts();
    await loadOverview();
    setStatus(`导入完成：新增 ${data.inserted}/${data.total_rows}`, "success");
  } catch (error) {
    setStatus(error.message || "导入失败", "error");
  }
}

async function fetchTiktokStats() {
  const username = (statsUsernameInput?.value || "").trim();
  const recentCountRaw = Number(statsRecentCountInput?.value || 10);
  const recent_count = Math.max(1, Math.min(50, Number.isFinite(recentCountRaw) ? recentCountRaw : 10));

  if (!username) {
    setStatus("请先填写用户名", "warning");
    return;
  }

  try {
    setStatus("正在查询 TikTok 数据...", "info");
    if (statsResultEl) {
      statsResultEl.textContent = "查询中，请稍候...";
    }
    const data = await request("/api/admin/tiktok_profile_stats", {
      method: "POST",
      body: JSON.stringify({ username, recent_count, include_raw: true }),
    });

    if (statsResultEl) {
      statsResultEl.textContent = JSON.stringify(data, null, 2);
    }

    setStatus("查询成功", "success");
  } catch (error) {
    if (statsResultEl) {
      statsResultEl.textContent = `查询失败: ${error.message || "unknown error"}`;
    }
    setStatus(error.message || "查询失败", "error");
  }
}

function collectDeviceOpsIdentity() {
  const selected = (deviceOpsSelect?.value || "").trim();
  if (!selected) {
    setStatus("请先选择设备", "warning");
    return null;
  }
  const [device_id, alias] = selected.split("|");
  if (!device_id || !alias) {
    setStatus("设备选择无效，请重新选择", "warning");
    return null;
  }
  const package_name = String(backupPackageNameInput?.value || "").trim();
  if (!package_name) {
    setStatus("请先填写包名", "warning");
    return null;
  }
  return { device_id, alias, package_name };
}

function collectDeviceIdentityByAlias(alias, options = {}) {
  const normalizedAlias = String(alias || "").trim();
  if (!normalizedAlias) {
    setStatus("未提供设备别名", "warning");
    return null;
  }
  const package_name = String(options.package_name || backupPackageNameInput?.value || "").trim();
  if (!package_name && options.requirePackageName !== false) {
    setStatus("请先填写包名", "warning");
    return null;
  }
  const matched = Object.entries(latestDevices || {}).find(([, info]) => String(info?.alias || "").trim() === normalizedAlias);
  if (!matched) {
    setStatus(`未找到别名为 ${normalizedAlias} 的在线设备`, "warning");
    return null;
  }
  const [device_id] = matched;
  return { device_id, alias: normalizedAlias, package_name };
}

function collectDeviceIdentityByDeviceAndAlias(deviceId, alias, options = {}) {
  const normalizedDeviceId = String(deviceId || "").trim();
  const normalizedAlias = String(alias || "").trim();
  if (!normalizedDeviceId || !normalizedAlias) {
    setStatus("设备信息不完整（缺少 device_id 或 alias）", "warning");
    return null;
  }
  const package_name = String(options.package_name || backupPackageNameInput?.value || "").trim();
  if (!package_name && options.requirePackageName !== false) {
    setStatus("请先填写包名", "warning");
    return null;
  }
  const device = latestDevices?.[normalizedDeviceId];
  if (!device) {
    setStatus(`设备不在线或不存在：${normalizedDeviceId}`, "warning");
    return null;
  }
  const currentAlias = String(device?.alias || "").trim();
  if (currentAlias && currentAlias !== normalizedAlias) {
    setStatus(`设备别名已变化（当前: ${currentAlias}），请刷新后重试`, "warning");
    return null;
  }
  return { device_id: normalizedDeviceId, alias: normalizedAlias, package_name };
}


async function waitForDeviceOpResult(opId, timeoutMs = 120000) {
  const start = Date.now();
  logBackupAction("POLL_START", { opId, timeoutMs });
  while (Date.now() - start < timeoutMs) {
    const pollUrl = `/api/admin/device_tiktok/op_result?op_id=${encodeURIComponent(opId)}`;
    logBackupAction("POLL_REQUEST", { url: pollUrl });
    const token = authToken();
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const response = await fetch(`${apiBase()}${pollUrl}`, { method: "GET", headers });
    const text = await response.text();
    let data = {};
    try {
      data = text ? JSON.parse(text) : {};
    } catch (_) {
      data = { code: response.ok ? 0 : 1, msg: text };
    }
    logBackupAction("POLL_RESPONSE", {
      url: pollUrl,
      httpStatus: response.status,
      code: data?.code,
      status: data?.status,
      resultStatus: data?.result?.status,
    });
    if (!response.ok) {
      throw new Error((data && data.msg) || `轮询请求失败（HTTP ${response.status}）`);
    }
    if (data.status === "done" || data.code === 0) {
      const opLogs = data?.result?.data?.operation_logs;
      if (Array.isArray(opLogs) && opLogs.length && backupCloudInfoEl) {
        backupCloudInfoEl.textContent = opLogs.join("\n");
      }
      return data.result;
    }
    // 任务未完成：服务端会返回 code=1 + status=pending，继续轮询
    if (data.status !== "pending" && data.code !== 1) {
      throw new Error(data?.msg || "轮询返回异常");
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  logBackupAction("POLL_ERROR", { opId, message: "设备执行超时" });
  throw new Error("设备执行超时，请稍后刷新结果");
}

async function runTikTokDeviceOp(path, successText) {
  const identity = collectDeviceOpsIdentity();
  if (!identity) return;
  try {
    setStatus("设备操作下发中...", "info");
    const submit = await request(path, {
      method: "POST",
      body: JSON.stringify(identity),
    });
    const opId = submit.op_id;
    if (!opId) throw new Error("未拿到任务ID");
    const result = await waitForDeviceOpResult(opId);
    if (backupCloudInfoEl) {
      backupCloudInfoEl.textContent = JSON.stringify(result, null, 2);
    }
    if (result?.status === "success") {
      setStatus(successText, "success");
    } else {
      setStatus(result?.message || successText, "error");
    }
  } catch (error) {
    setStatus(error.message || "设备操作失败", "error");
  }
}

async function submitAndWaitTikTokDeviceOp(path, body, options = {}) {
  const pendingText = options.pendingText || "设备操作下发中...";
  const successText = options.successText || "设备操作完成";
  const errorText = options.errorText || "设备操作失败";
  const shouldRefresh = options.refreshAfter !== false;
  try {
    setStatus(pendingText, "info");
    const submit = await request(path, {
      method: "POST",
      body: JSON.stringify(body),
    });
    const opId = submit.op_id;
    if (!opId) {
      if (submit?.code === 0) {
        setStatus(successText, "success");
        return submit;
      }
      throw new Error(submit?.msg || "未拿到任务ID");
    }
    const result = await waitForDeviceOpResult(opId);
    if (backupCloudInfoEl) {
      backupCloudInfoEl.textContent = JSON.stringify(result, null, 2);
    }
    if (result?.status === "success") {
      setStatus(successText, "success");
    } else {
      setStatus(result?.message || errorText, "error");
    }
    if (shouldRefresh) {
      renderDeviceEditorRows();
    }
    return result;
  } catch (error) {
    setStatus(error.message || errorText, "error");
    throw error;
  }
}

async function handleBackupAndClearClick(alias, backupRecordId, username = "", deviceId = "") {
  const identity = deviceId
    ? collectDeviceIdentityByDeviceAndAlias(deviceId, alias)
    : collectDeviceIdentityByAlias(alias);
  if (!identity) return;
  const detailText = [`设备别名：${alias}`, username ? `账号：${username}` : null].filter(Boolean).join("\n");
  if (!confirmTikTokAction("备份并清理设备", detailText)) return;
  const payload = {
    ...identity,
    backup_record_id: backupRecordId ? Number(backupRecordId) : undefined,
    username: String(username || "").trim() || undefined,
  };
  await submitAndWaitTikTokDeviceOp("/api/admin/device_tiktok/backup_and_clear", payload, {
    pendingText: "正在下发备份并清理任务...",
    successText: "备份并清理任务已完成",
    errorText: "备份并清理失败",
  });
}

async function handleAliasClearClick(alias, deviceId = "") {
  const identity = deviceId
    ? collectDeviceIdentityByDeviceAndAlias(deviceId, alias)
    : collectDeviceIdentityByAlias(alias);
  if (!identity) return;
  if (!confirmTikTokAction("清理 TikTok 数据", `设备别名：${alias}`)) return;
  await submitAndWaitTikTokDeviceOp("/api/admin/device_tiktok/clear", identity, {
    pendingText: "正在下发清理任务...",
    successText: "TikTok 数据已清理",
    errorText: "清理 TikTok 数据失败",
  });
}

async function handleRestoreClick(alias, backupName, deviceId = "") {
  const normalizedBackupName = String(backupName || "").trim();
  if (!normalizedBackupName) {
    setStatus("缺少备份名称，无法执行还原", "warning");
    return;
  }
  const identity = deviceId
    ? collectDeviceIdentityByDeviceAndAlias(deviceId, alias, { requirePackageName: false })
    : collectDeviceIdentityByAlias(alias, { requirePackageName: false });
  if (!identity) return;
  const payload = {
    device_id: identity.device_id,
    alias: identity.alias,
    backup_name: normalizedBackupName,
  };
  if (!confirmTikTokAction("还原该备份", `设备别名：${alias}\n备份：${normalizedBackupName}`)) return;
  await submitAndWaitTikTokDeviceOp("/api/admin/device_tiktok/restore", payload, {
    pendingText: "正在下发还原任务...",
    successText: "备份还原任务已完成",
    errorText: "还原备份失败",
  });
}

async function uploadGalleryImagesToDevice() {
  const aliases = Array.from(galleryTargetAliasesSelect?.selectedOptions || [])
    .map((option) => String(option.value || "").trim())
    .filter(Boolean);
  if (!aliases.length) {
    setStatus("请先选择至少一个目标设备别名", "warning");
    return;
  }
  const files = Array.from(galleryImageFilesInput?.files || []);
  if (!files.length) {
    setStatus("请先选择图片文件（可多选）", "warning");
    return;
  }
  if (files.length > 20) {
    setStatus("单次最多选择 20 个素材", "warning");
    return;
  }

  try {
    const pairCount = Math.min(aliases.length, files.length);
    if (pairCount <= 0) {
      setStatus("别名或素材数量不足，无法下发", "warning");
      return;
    }
    const skippedAliases = aliases.length - pairCount;
    const skippedFiles = files.length - pairCount;
    setStatus(`正在按一对一下发：${pairCount} 台设备 <- ${pairCount} 个素材...`, "info");
    appendGalleryDispatchLog(`[${new Date().toLocaleString()}] 开始下发：别名=${aliases.length}，素材=${files.length}，配对=${pairCount}`, true);
    const headers = {};
    const token = authToken();
    if (token) headers.Authorization = `Bearer ${token}`;

    const summaries = [];
    let successCount = 0;
    for (let i = 0; i < pairCount; i += 1) {
      const alias = aliases[i];
      const file = files[i];
      appendGalleryDispatchLog(`[${i + 1}/${pairCount}] ${alias} <- ${file?.name || "unknown"}`);
      const deviceId = resolveDeviceIdByAlias(alias);
      if (!deviceId) {
        summaries.push({ alias, status: "failed", message: "未找到在线设备" });
        appendGalleryDispatchLog(`  失败：未找到在线设备`);
        continue;
      }
      const formData = new FormData();
      formData.append("device_id", deviceId);
      formData.append("alias", alias);
      formData.append("files", file);
      const response = await fetch(`${apiBase()}/api/admin/device_gallery/upload`, {
        method: "POST",
        body: formData,
        headers,
      });
      const data = await response.json();
      if (!response.ok || data.code !== 0) {
        summaries.push({ alias, status: "failed", message: data.msg || "下发图库任务失败" });
        appendGalleryDispatchLog(`  失败：${data.msg || "下发图库任务失败"}`);
        continue;
      }
      const opId = data.op_id;
      successCount += 1;
      if (!opId) {
        summaries.push({ alias, status: "queued", message: "任务已入队（无 op_id）" });
        appendGalleryDispatchLog("  已入队：任务已创建（无 op_id）");
        continue;
      }
      summaries.push({ alias, status: "queued", message: `任务已入队（op_id=${opId}）`, opId });
      appendGalleryDispatchLog(`  已入队：op_id=${opId}（设备上线后会执行）`);
    }

    if (backupCloudInfoEl) {
      const detail = summaries
        .map((item) => `${item.alias} | ${item.status} | ${item.message}`)
        .join("\n");
      const extra = [
        skippedAliases > 0 ? `未分配别名: ${skippedAliases}` : "",
        skippedFiles > 0 ? `未分配素材: ${skippedFiles}` : "",
      ].filter(Boolean).join(" | ");
      backupCloudInfoEl.textContent = extra ? `${detail}\n${extra}` : detail;
    }
    appendGalleryDispatchLog(
      `汇总：入队 ${successCount}/${pairCount}` +
      (skippedAliases > 0 ? `，未分配别名 ${skippedAliases}` : "") +
      (skippedFiles > 0 ? `，未分配素材 ${skippedFiles}` : "")
    );
    if (successCount === pairCount) {
      const suffix = (skippedAliases > 0 || skippedFiles > 0)
        ? `（未分配别名 ${Math.max(0, skippedAliases)}，未分配素材 ${Math.max(0, skippedFiles)}）`
        : "";
      setStatus(`全部入队：${successCount}/${pairCount} 台设备素材任务已创建${suffix}`, "success");
      if (galleryImageFilesInput) galleryImageFilesInput.value = "";
    } else if (successCount > 0) {
      setStatus(`部分入队：${successCount}/${pairCount} 台设备素材任务已创建`, "warning");
    } else {
      setStatus("全部入队失败，请查看结果详情", "error");
    }
  } catch (error) {
    appendGalleryDispatchLog(`异常：${error.message || "上传并下发图库失败"}`);
    setStatus(error.message || "上传并下发图库失败", "error");
  }
}





async function loadDbTablePage() {
  const table = dbTableSelect?.value || "tiktok_roots";
  try {
    const data = await request(`/api/admin/table_page?table=${encodeURIComponent(table)}&page=${currentDbPage}&page_size=${DB_PAGE_SIZE}`);
    const total = Number(data.total || 0);
    const totalPages = Math.max(1, Math.ceil(total / DB_PAGE_SIZE));
    if (currentDbPage > totalPages) {
      currentDbPage = totalPages;
    }

    if (dbPageInfoEl) {
      dbPageInfoEl.textContent = `表: ${table} | 第 ${data.page} / ${totalPages} 页 | 每页 ${data.page_size} | 总数 ${total}`;
    }

    const items = Array.isArray(data.items) ? data.items : [];
    const columns = data.columns || (items[0] ? Object.keys(items[0]) : []);

    if (dbTableHeadEl) {
      dbTableHeadEl.innerHTML = `<tr>${columns.map((c) => `<th style="position:sticky;top:0;background:#fafafa;border-bottom:1px solid #e5e7eb;padding:8px;text-align:left;">${c}</th>`).join("")}</tr>`;
    }
    if (dbTableBodyEl) {
      if (!items.length) {
        dbTableBodyEl.innerHTML = `<tr><td colspan="${Math.max(columns.length, 1)}" style="padding:10px;color:#666;">暂无数据</td></tr>`;
      } else {
        dbTableBodyEl.innerHTML = items
          .map((row) => `<tr>${columns.map((c) => `<td style="border-bottom:1px solid #f0f0f0;padding:8px;vertical-align:top;">${row?.[c] ?? ""}</td>`).join("")}</tr>`)
          .join("");
      }
    }

    if (dbPrevPageBtn) dbPrevPageBtn.disabled = data.page <= 1;
    if (dbNextPageBtn) dbNextPageBtn.disabled = data.page >= totalPages;
  } catch (error) {
    if (dbTableHeadEl) dbTableHeadEl.innerHTML = "";
    if (dbTableBodyEl) {
      dbTableBodyEl.innerHTML = `<tr><td style="padding:10px;color:#c00;">加载失败: ${error.message || "unknown"}</td></tr>`;
    }
  }
}

async function authCheck() {
  const token = authToken();
  if (!token) return false;
  try {
    const data = await request("/api/auth/me");
    currentUser = data.user;
    setCachedUser(currentUser);
    configTargetUsersLoaded = false;
    authShell?.classList.add("hidden");
    appShell?.classList.remove("hidden");
    applyRolePermissions();
    return true;
  } catch (error) {
    // 仅在登录态真实失效时清 token；网络错误/服务未就绪时保留登录态，避免刷新后反复要求重登。
    if (isAuthExpiredError(error)) {
      setAuthToken("");
      setCachedUser(null);
      currentUser = null;
      authShell?.classList.remove("hidden");
      appShell?.classList.add("hidden");
      if (authMessageEl) authMessageEl.textContent = "登录已失效，请重新登录";
    } else {
      const cachedUser = getCachedUser();
      if (cachedUser) {
        currentUser = cachedUser;
        configTargetUsersLoaded = false;
        authShell?.classList.add("hidden");
        appShell?.classList.remove("hidden");
        applyRolePermissions();
        if (authMessageEl) authMessageEl.textContent = "服务连接异常，已使用本地登录态";
        return true;
      }
      if (authMessageEl) authMessageEl.textContent = "服务连接失败，已保留登录态，请检查 API Base 后重试";
    }
    return false;
  }
}


function renderAdminUsers(items = []) {
  if (!adminUsersTableBodyEl) return;
  if (!Array.isArray(items) || !items.length) {
    adminUsersTableBodyEl.innerHTML = `<tr><td colspan="6" style="padding:10px;color:#666;">暂无数据</td></tr>`;
    return;
  }
  adminUsersTableBodyEl.innerHTML = items
    .map((item) => {
      const active = item?.is_active ? "启用" : "停用";
      return `<tr>
        <td>${item?.id ?? ""}</td>
        <td>${item?.username ?? ""}</td>
        <td>${item?.role ?? ""}</td>
        <td>${active}</td>
        <td>${item?.last_login_at || "-"}</td>
        <td>${item?.created_at || "-"}</td>
      </tr>`;
    })
    .join("");
}

async function loadAdminUsers() {
  if (currentUser?.role !== "admin") return;
  const data = await request("/api/admin/users");
  const items = Array.isArray(data.items) ? data.items : [];
  renderAdminUsers(items);
  if (configTargetUserSelect) {
    const options = items
      .filter((item) => String(item?.username || "").trim())
      .filter((item) => String(item?.username || "").trim() !== String(currentUser?.username || "").trim())
      .sort((a, b) => String(a.username || "").localeCompare(String(b.username || "")));
    configTargetUserSelect.innerHTML = [
      '<option value="">请选择用户</option>',
      ...options.map((item) => `<option value="${String(item.username)}">${String(item.username)}</option>`),
    ].join("");
    configTargetUsersLoaded = true;
  }
}

async function createAdminUser() {
  if (currentUser?.role !== "admin") return;
  const username = (adminCreateUsernameInput?.value || "").trim();
  const password = adminCreatePasswordInput?.value || "";
  const role = (adminCreateRoleSelect?.value || "customer").trim() || "customer";
  if (!username || !password) {
    if (adminUsersMsgEl) adminUsersMsgEl.textContent = "用户名和密码不能为空";
    return;
  }
  await request("/api/admin/users", {
    method: "POST",
    body: JSON.stringify({ username, password, role, is_active: true }),
  });
  if (adminUsersMsgEl) adminUsersMsgEl.textContent = `创建成功：${username}`;
  if (adminCreateUsernameInput) adminCreateUsernameInput.value = "";
  if (adminCreatePasswordInput) adminCreatePasswordInput.value = "";
  await loadAdminUsers();
}

async function doLogin() {
  const username = (authUsernameInput?.value || "").trim();
  const password = authPasswordInput?.value || "";
  try {
    const data = await request("/api/auth/login", { method: "POST", body: JSON.stringify({ username, password }) });
    setAuthToken(data.token);
    currentUser = { username: data.username, role: data.role };
    setCachedUser(currentUser);
    configTargetUsersLoaded = false;
    authShell?.classList.add("hidden");
    appShell?.classList.remove("hidden");
    if (authMessageEl) authMessageEl.textContent = `欢迎，${data.username}`;
    try { applyRolePermissions(); } catch (_) {}
    try { await loadUserRuntimeConfig(); } catch (_) {}
    try { await loadAdminUsers(); } catch (_) {}
  } catch (error) {
    if (authMessageEl) authMessageEl.textContent = error.message || "登录失败";
    throw error;
  }
}

if (authLoginBtn) authLoginBtn.addEventListener("click", () => doLogin().catch(() => {}));
if (authPasswordInput) authPasswordInput.addEventListener("keydown", (event) => { if (event.key === "Enter") authLoginBtn?.click(); });
if (authUsernameInput) authUsernameInput.addEventListener("keydown", (event) => { if (event.key === "Enter") authLoginBtn?.click(); });
if (refreshAllBtn) {
  refreshAllBtn.addEventListener("click", async () => {
    await handleRefresh();
    await loadAccounts();
  });
}
if (refreshOverviewBtn) {
  refreshOverviewBtn.addEventListener("click", async () => {
    await handleRefresh();
  });
}
{
  let commentTextsDraftTimer = null;
  const commentTextsTa = configForm?.querySelector("[name='comment_texts']");
  if (commentTextsTa) {
    commentTextsTa.addEventListener("input", () => {
      clearTimeout(commentTextsDraftTimer);
      commentTextsDraftTimer = setTimeout(persistCommentTextsToLocal, 500);
    });
  }
}
if (uploadBtn) uploadBtn.addEventListener("click", uploadUsernames);
if (saveConfigBtn) saveConfigBtn.addEventListener("click", saveConfig);
if (selectAllCheckbox) selectAllCheckbox.addEventListener("change", syncSelectAll);
if (startSelectedBtn) startSelectedBtn.addEventListener("click", () => startDevices(selectedDeviceIds()));
if (startAllBtn) startAllBtn.addEventListener("click", () => {
  const allDevices = Object.keys(latestDevices || {});
  startDevices(allDevices);
});
if (stopSelectedBtn) stopSelectedBtn.addEventListener("click", () => stopDevices(selectedDeviceIdsByStatus(["running", "error", "paused"])));
if (applyTemplateSelectedBtn) applyTemplateSelectedBtn.addEventListener("click", () => applyTemplateToSelectedDevices());
if (autoStartToggleBtn) {
  autoStartToggleBtn.addEventListener("click", async () => {
    try {
      setStatus("正在更新自动开启策略...", "info");
      const next = !adminAutoStartEnabled;
      const data = await request("/api/admin/auto_start", {
        method: "POST",
        body: JSON.stringify({ enabled: next }),
      });
      adminAutoStartEnabled = Boolean(data.auto_start_enabled);
      updateAutoStartToggleUi();
      // 顶部总开关切换后，单设备按钮显示需立即与生效状态同步。
      renderDevices(latestDevices);
      bindDeviceSelection();
      setStatus(
        next
          ? "已开启：已绑定设备在轮询到策略后将自行拉起程序（本机未暂停时）"
          : `已关闭自动开启（已暂停 ${data.paused_count ?? 0} 台，清空待启动 ${data.cleared_pending_count ?? 0} 台）`,
        "success",
      );
    } catch (error) {
      setStatus(error.message || "更新失败", "error");
    }
  });
}
aliasFilterInput.addEventListener("input", () => {
  renderDevices(latestDevices);
  bindDeviceSelection();
});
if (addTikTokRootsBtn) {
  addTikTokRootsBtn.addEventListener("click", addTikTokRoots);
}
if (importTikTokRootsBtn) {
  importTikTokRootsBtn.addEventListener("click", importTikTokRoots);
}
if (addMobilePendingBtn) {
  addMobilePendingBtn.addEventListener("click", addMobilePendingUsernames);
}
if (resetTikTokRootsUsageBtn) {
  resetTikTokRootsUsageBtn.addEventListener("click", resetTikTokRootsUsage);
}
if (tiktokRootsPrevPageBtn) {
  tiktokRootsPrevPageBtn.addEventListener("click", async () => {
    if (currentTiktokRootsPage > 1) {
      currentTiktokRootsPage -= 1;
      await loadAccounts();
    }
  });
}
if (tiktokRootsNextPageBtn) {
  tiktokRootsNextPageBtn.addEventListener("click", async () => {
    currentTiktokRootsPage += 1;
    await loadAccounts();
  });
}
if (tiktokRootsPageSizeEl) {
  tiktokRootsPageSizeEl.addEventListener("change", async () => {
    currentTiktokRootsPage = 1;
    await loadAccounts();
  });
}
if (tiktokRootsSelectPageBtn) {
  tiktokRootsSelectPageBtn.addEventListener("click", () => selectCurrentTiktokRootsPage(true));
}
if (tiktokRootsClearSelectionBtn) {
  tiktokRootsClearSelectionBtn.addEventListener("click", () => selectCurrentTiktokRootsPage(false));
}
if (addSelectedRootsToPending) {
  document.getElementById("addSelectedRootsToPending")?.addEventListener("click", addSelectedRootsToPending);
}
if (addAllRootsToPendingBtn) {
  addAllRootsToPendingBtn.addEventListener("click", addAllRootsToPending);
}
if (triggerCollectRootsExcelBtn) {
  triggerCollectRootsExcelBtn.addEventListener("click", () => triggerTask("/api/admin/tasks/trigger_collect_roots_excel", "已触发：Excel根用户抓取"));
}
if (stopCollectRootsExcelBtn) {
  stopCollectRootsExcelBtn.addEventListener("click", () => stopTaskByPrefix("collect_roots_excel", "已停止 Excel 抓取任务"));
}
if (triggerCollectRootsDbBtn) {
  triggerCollectRootsDbBtn.addEventListener("click", () => {
    const rawPriority = String(collectRootsDbPrioritySelect?.value || "high").trim().toLowerCase();
    const allowed = new Set(["high", "medium", "low"]);
    const priority = allowed.has(rawPriority) ? rawPriority : "high";
    const path = `/api/admin/tasks/trigger_collect_roots_db?full=true&priority=${encodeURIComponent(priority)}`;
    return triggerTask(path, `已触发：tiktok_roots 全量执行（优先级=${priority}）`);
  });
}
if (stopCollectRootsDbBtn) {
  stopCollectRootsDbBtn.addEventListener("click", () => stopTaskByPrefix("collect_roots_db", "已停止 tiktok_roots 抓取任务"));
}
if (triggerProcessQueueInlineBtn) {
  triggerProcessQueueInlineBtn.addEventListener("click", async () => {
    try {
      setStatus("任务触发中...", "info");
      const raw = Number(processQueueBatchSizeInput?.value || 100);
      const limit = Math.max(1, Number.isFinite(raw) ? Math.floor(raw) : 100);
      const container_ids = (cvTaskContainerIdsInput?.value || "").trim();
      const parallelRaw = Number(cvTaskParallelInput?.value || 1);
      const parallel = Math.max(1, Number.isFinite(parallelRaw) ? parallelRaw : 1);
      const query = `?limit=${limit}&container_ids=${encodeURIComponent(container_ids)}&parallel=${parallel}`;
      const data = await request(`/api/admin/tasks/trigger_process_queue${query}`, { method: "POST" });
      if (data.workers) {
        setStatus(`已触发：follower_queue处理（workers=${data.workers}）`, "success");
      } else {
        setStatus(`已触发：follower_queue处理（pid=${data.pid ?? "-"}）`, "success");
      }
    } catch (error) {
      const msg = error.message || "任务触发失败";
      setStatus(msg, "error");
      // 触发失败时右侧日志区先给出明确错误，避免用户误以为“没有任何响应”。
      if (cvTaskLogsEl) cvTaskLogsEl.textContent = msg;
    } finally {
      // 触发失败/被拦截时也刷新一次，至少让用户看到 scheduler 开关状态或已有日志。
      await refreshTaskStatus();
    }
  });
}
if (stopProcessQueueInlineBtn) {
  stopProcessQueueInlineBtn.addEventListener("click", () => stopTaskByPrefix("process_queue", "已停止 follower_queue 处理任务"));
}
if (queryCvAccessDataBtn) {
  queryCvAccessDataBtn.addEventListener("click", async () => {
    try {
      setStatus("正在查询直客模式数据...", "info");
      await loadCvAccessData();
      currentCvPage = 1;
      applyCvAccessView();
      setStatus("直客模式数据已刷新", "success");
    } catch (error) {
      setStatus(error.message || "查询直客模式数据失败", "error");
    }
  });
}
if (cvAccessFilterEl) {
  cvAccessFilterEl.addEventListener("change", () => {
    currentCvPage = 1;
    applyCvAccessView();
  });
}
if (cvPrevPageBtn) {
  cvPrevPageBtn.addEventListener("click", () => {
    currentCvPage = Math.max(1, currentCvPage - 1);
    Promise.resolve()
      .then(loadCvAccessData)
      .catch(() => applyCvAccessView());
  });
}
if (cvNextPageBtn) {
  cvNextPageBtn.addEventListener("click", () => {
    currentCvPage += 1;
    Promise.resolve()
      .then(loadCvAccessData)
      .catch(() => applyCvAccessView());
  });
}
if (cvPageSizeEl) {
  cvPageSizeEl.addEventListener("change", () => {
    currentCvPage = 1;
    Promise.resolve()
      .then(loadCvAccessData)
      .catch(() => applyCvAccessView());
  });
}
if (saveCvQualityFilterBtn) {
  saveCvQualityFilterBtn.addEventListener("click", saveCvQualityFilter);
}
if (triggerAutoSearchBtn) {
  triggerAutoSearchBtn.addEventListener("click", () => triggerTask("/api/admin/tasks/trigger_autosearch_once", "已触发：autoSearch执行一次"));
}
if (startSchedulersBtn) {
  startSchedulersBtn.addEventListener("click", startSchedulers);
}
if (stopSchedulersBtn) {
  stopSchedulersBtn.addEventListener("click", stopSchedulers);
}
if (growthModeBtn) {
  growthModeBtn.addEventListener("click", () => switchRunMode("growth"));
}
if (liveModeBtn) {
  liveModeBtn.addEventListener("click", () => switchRunMode("live"));
}
if (cvModeBtn) {
  cvModeBtn.addEventListener("click", () => switchRunMode("direct"));
}
const configModeSelect = document.getElementById("configModeSelect");
if (configModeSelect) {
  configModeSelect.addEventListener("change", (event) => {
    const mode = event.target.value || "growth";
    if (mode !== currentRunMode) {
      switchRunMode(mode);
    }
  });
}
if (activeConfigTemplateSelect) {
  activeConfigTemplateSelect.addEventListener("change", (event) => {
    const templateName = String(event.target?.value || "").trim();
    if (!templateName || templateName === currentConfigTemplate) return;
    void selectConfigTemplate(templateName);
  });
}
if (copyConfigTemplateBtn) {
  copyConfigTemplateBtn.addEventListener("click", () => {
    void copyCurrentTemplate();
  });
}
if (renameConfigTemplateBtn) {
  renameConfigTemplateBtn.addEventListener("click", () => {
    void renameCurrentTemplate();
  });
}
if (deleteConfigTemplateBtn) {
  deleteConfigTemplateBtn.addEventListener("click", () => {
    void deleteCurrentTemplate();
  });
}
if (refreshTemplateInventoryBtn) {
  refreshTemplateInventoryBtn.addEventListener("click", () => {
    void refreshTemplateInventoryFromServer().then(() => {
      setStatus("模板清单已从服务端刷新（含未过滤原始键）", "success");
    });
  });
}
if (templateInventoryAllUsersEl) {
  templateInventoryAllUsersEl.addEventListener("change", () => {
    void refreshTemplateInventoryFromServer();
  });
}
if (selectAllOwnTemplatesBtn) {
  selectAllOwnTemplatesBtn.addEventListener("click", () => {
    if (!templateInventoryListEl) return;
    templateInventoryListEl.querySelectorAll(".template-inventory-check:not([disabled])").forEach((el) => {
      el.checked = true;
    });
  });
}
if (clearTemplateSelectionBtn) {
  clearTemplateSelectionBtn.addEventListener("click", () => {
    if (!templateInventoryListEl) return;
    templateInventoryListEl.querySelectorAll(".template-inventory-check").forEach((el) => {
      el.checked = false;
    });
  });
}
if (deleteSelectedTemplatesBtn) {
  deleteSelectedTemplatesBtn.addEventListener("click", () => {
    void deleteSelectedTemplates();
  });
}
if (grantConfigTemplateBtn) {
  grantConfigTemplateBtn.addEventListener("click", () => {
    void setTemplateGrant(true);
  });
}
if (revokeConfigTemplateBtn) {
  revokeConfigTemplateBtn.addEventListener("click", () => {
    void setTemplateGrant(false);
  });
}
if (importLiveCustomersBtn) {
  importLiveCustomersBtn.addEventListener("click", importLiveCustomers);
}
if (fetchTiktokStatsBtn) {
  fetchTiktokStatsBtn.addEventListener("click", fetchTiktokStats);
}
if (dbTableSelect) {
  dbTableSelect.addEventListener("change", async () => {
    currentDbPage = 1;
    await loadDbTablePage();
  });
}
if (dbPrevPageBtn) {
  dbPrevPageBtn.addEventListener("click", async () => {
    if (currentDbPage > 1) {
      currentDbPage -= 1;
      await loadDbTablePage();
    }
  });
}
if (dbNextPageBtn) {
  dbNextPageBtn.addEventListener("click", async () => {
    currentDbPage += 1;
    await loadDbTablePage();
  });
}
if (dbRefreshPageBtn) {
  dbRefreshPageBtn.addEventListener("click", loadDbTablePage);
}
if (backupPackageNameInput) {
  backupPackageNameInput.value = (backupPackageNameInput.value || "").trim() || "com.zhiliaoapp.musically";
}

async function loadEditorState() {
  const [accountsResp, devicesResp] = await Promise.all([
    request(ACCOUNT_ROWS_API),
    request(DEVICE_ROWS_API),
  ]);
  accountEditorRows = Array.isArray(accountsResp.items)
    ? accountsResp.items.map((item) => getAccountRowTemplate(item))
    : [];
  deviceEditorRows = Array.isArray(devicesResp.items)
    ? devicesResp.items.map((item) => getDeviceRowTemplate(item))
    : [];
}

async function saveAccountRow(index, options = {}) {
  const row = accountEditorRows[index];
  if (!row) return;
  const { skipReload = false } = options || {};
  await request(`${ACCOUNT_ROWS_API}/upsert`, {
    method: "POST",
    body: JSON.stringify({ row_index: index, ...row }),
  });
  if (!skipReload) {
    await loadEditorState();
    renderAccountEditorRows();
    renderDeviceEditorRows();
  }
}

function parseAccountBatchLine(line, sharedRegion = "", rejectEmpty = false) {
  const raw = String(line || "").trim();
  if (!raw) return null;
  const cols = raw.includes("\t")
    ? raw.split("\t")
    : (raw.includes("----") ? raw.split(/-{4,}/) : [raw]);
  if (cols.length < 4) {
    throw new Error(`格式错误：至少需要4列（username、password、email、email_password）。支持 TAB 或 ---- 分隔，实际 ${cols.length} 列`);
  }
  const normalizedCols = cols.map((item) => String(item || "").trim());
  const username = String(normalizedCols[0] || "").trim();
  if (!username) {
    throw new Error("格式错误：username 不能为空");
  }
  const password = String(normalizedCols[1] || "").trim();
  const email = String(normalizedCols[2] || "").trim();
  const emailPassword = String(normalizedCols[3] || "").trim();
  // 支持两种格式：
  // 1) 7列标准：username,password,email,email_password,ignore,ignore,api
  // 2) 5列紧凑：username,password,email,email_password,api
  // 6列是缺少 api 的异常输入，不能把第5列误当作 api。
  let api = "";
  if (normalizedCols.length >= 7) {
    api = String(normalizedCols[6] || "").trim();
  } else if (normalizedCols.length === 5) {
    api = String(normalizedCols[4] || "").trim();
  } else if (normalizedCols.length === 6) {
    api = "";
  } else {
    api = String(normalizedCols[4] || "").trim();
  }
  if (rejectEmpty) {
    const missingFields = [];
    if (!username) missingFields.push("username");
    if (!password) missingFields.push("password");
    if (!email) missingFields.push("email");
    if (!emailPassword) missingFields.push("email_password");
    if (!api) missingFields.push("api");
    if (missingFields.length) {
      throw new Error(`存在空值字段：${missingFields.join("、")}`);
    }
  }
  return getAccountRowTemplate({
    username,
    password,
    email,
    email_password: emailPassword,
    api,
    region: String(sharedRegion || "").trim(),
  });
}

async function importAccountBatchFromText() {
  const text = String(accountBatchInputEl?.value || "");
  const sharedRegion = String(accountBatchRegionInputEl?.value || "").trim();
  const rejectEmpty = accountBatchRejectEmptyToggleEl?.checked !== false;
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (!lines.length) {
    throw new Error("请先粘贴批量数据（每行一条）");
  }
  const startIndex = accountEditorRows.length;
  const parsedRows = lines.map((line, idx) => {
    try {
      return parseAccountBatchLine(line, sharedRegion, rejectEmpty);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new Error(`第 ${idx + 1} 行解析失败：${message}`);
    }
  }).filter(Boolean);
  parsedRows.forEach((row) => accountEditorRows.push(row));
  try {
    for (let i = startIndex; i < accountEditorRows.length; i += 1) {
      await saveAccountRow(i, { skipReload: true });
    }
  } catch (error) {
    accountEditorRows.splice(startIndex, accountEditorRows.length - startIndex);
    await loadEditorState();
    renderAccountEditorRows();
    renderDeviceEditorRows();
    throw error;
  }
  await loadEditorState();
  renderAccountEditorRows();
  renderDeviceEditorRows();
  if (accountBatchInputEl) accountBatchInputEl.value = "";
  setStatus(`批量导入成功：新增 ${parsedRows.length} 条账号记录`, "success");
}

function isUsernameUsedElsewhere(username, currentIndex) {
  const normalized = String(username || "").trim().toLowerCase();
  if (!normalized) return false;
  return deviceEditorRows.some((row, idx) => {
    if (idx === currentIndex) return false;
    return String(row?.username || "").trim().toLowerCase() === normalized;
  });
}

async function saveDeviceRow(index, options = {}) {
  const row = deviceEditorRows[index];
  if (!row) return;
  const { skipUsernameDuplicateCheck = false, skipReload = false } = options || {};
  const username = String(row.username || "").trim();
  if (!skipUsernameDuplicateCheck && username && isUsernameUsedElsewhere(username, index)) {
    throw new Error(`username 已被其他设备别名占用：${username}`);
  }
  await request(`${DEVICE_ROWS_API}/upsert`, {
    method: "POST",
    body: JSON.stringify({ row_index: index, ...row }),
  });
  if (!skipReload) {
    await loadEditorState();
    renderAccountEditorRows();
    renderDeviceEditorRows();
  }
}

function getAccountRowTemplate(source = {}) {
  return {
    username: source.username || "",
    password: source.password || "",
    email: source.email || "",
    email_password: source.email_password || "",
    api: source.api || "",
    is_login: source.is_login ?? false,
    login_time: source.login_time || "",
    region: source.region || "",
    video_count: source.video_count || "",
    follower_count: source.follower_count || "",
    follower_growth: source.follower_growth || "",
  };
}

function getDeviceRowTemplate(source = {}) {
  return {
    device_alias: source.device_alias || "",
    device_id: source.device_id || "",
    ip: source.ip || "",
    region: source.region || "",
    username: source.username || "",
    backup_path: source.backup_path || "",
    backup_name: source.backup_name || "",
    backup_time: source.backup_time || "",
    backup_status: source.backup_status || "",
    remark: source.remark || "",
  };
}

function buildAliasDevicePairs() {
  const pairs = Object.entries(latestDevices || {})
    .map(([deviceId, info]) => ({
      device_alias: String(info?.alias || "").trim(),
      device_id: String(deviceId || "").trim(),
    }))
    .filter((item) => item.device_alias && item.device_id);
  const dedup = new Map();
  pairs.forEach((item) => {
    if (!dedup.has(item.device_alias)) {
      dedup.set(item.device_alias, item.device_id);
    }
  });
  return Array.from(dedup.entries())
    .map(([device_alias, device_id]) => ({ device_alias, device_id }))
    .sort((a, b) => a.device_alias.localeCompare(b.device_alias));
}

function resolveDeviceIdByAlias(alias) {
  const normalized = String(alias || "").trim();
  if (!normalized) return "";
  const pairs = buildAliasDevicePairs();
  const hit = pairs.find((item) => item.device_alias === normalized);
  return hit?.device_id || "";
}

function renderDeviceAliasPresetOptions() {
  const options = buildAliasDevicePairs();
  const aliasOptionsDatalist = document.getElementById("deviceAliasOptions");
  if (aliasOptionsDatalist) {
    aliasOptionsDatalist.innerHTML = options
      .map((item) => `<option value="${item.device_alias}">${item.device_alias} | ${item.device_id}</option>`)
      .join("");
  }
  if (!deviceAliasPresetSelect) return;
  const previous = String(deviceAliasPresetSelect.value || "").trim();
  const html = [
    '<option value="">手动填写（不自动带入）</option>',
    ...options.map((item) => `<option value="${item.device_alias}">${item.device_alias} | ${item.device_id}</option>`),
  ];
  deviceAliasPresetSelect.innerHTML = html.join("");
  if (previous && options.some((item) => item.device_alias === previous)) {
    deviceAliasPresetSelect.value = previous;
  }
  if (galleryTargetAliasesSelect) {
    const selectedBefore = new Set(Array.from(galleryTargetAliasesSelect.selectedOptions || []).map((it) => it.value));
    galleryTargetAliasesSelect.innerHTML = options
      .map((item) => `<option value="${item.device_alias}">${item.device_alias} | ${item.device_id}</option>`)
      .join("");
    Array.from(galleryTargetAliasesSelect.options).forEach((option) => {
      if (selectedBefore.has(option.value)) option.selected = true;
    });
  }
}

function renderDeviceRegionPresetOptions() {
  if (!deviceRegionPresetSelect) return;
  const previous = String(deviceRegionPresetSelect.value || "").trim();
  const regions = Array.from(
    new Set(
      (accountEditorRows || [])
        .map((row) => String(row?.region || "").trim())
        .filter(Boolean)
    )
  ).sort((a, b) => a.localeCompare(b));
  const html = [
    '<option value="">请选择国家地区</option>',
    ...regions.map((region) => `<option value="${region}">${region}</option>`),
  ];
  deviceRegionPresetSelect.innerHTML = html.join("");
  if (previous && regions.includes(previous)) {
    deviceRegionPresetSelect.value = previous;
  }
}

function renderAccountEditorRows() {
  if (!accountEditorListEl) return;
  if (!accountEditorRows.length) {
    accountEditorListEl.innerHTML = '<div class="hint">暂无记录，点击“新增空白记录”开始</div>';
    return;
  }
  accountEditorListEl.innerHTML = accountEditorRows.map((row, idx) => `
    <div class="panel-card" style="margin:0 0 12px 0;background:#fff;">
      <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px;">
        <strong>账号记录 #${idx + 1}</strong>
        <button class="ghost" data-editor-delete="account" data-editor-index="${idx}">删除</button>
      </div>
      <div style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;">
        <div class="field"><label>username</label><input data-editor-scope="account" data-editor-field="username" data-editor-index="${idx}" value="${row.username}" /></div>
        <div class="field"><label>password</label><input data-editor-scope="account" data-editor-field="password" data-editor-index="${idx}" value="${row.password}" /></div>
        <div class="field"><label>Email</label><input data-editor-scope="account" data-editor-field="email" data-editor-index="${idx}" value="${row.email}" /></div>
        <div class="field"><label>email_password</label><input data-editor-scope="account" data-editor-field="email_password" data-editor-index="${idx}" value="${row.email_password}" /></div>
        <div class="field"><label>API</label><input data-editor-scope="account" data-editor-field="api" data-editor-index="${idx}" value="${row.api}" /></div>
        <div class="field"><label>是否登录</label><select data-editor-scope="account" data-editor-field="is_login" data-editor-index="${idx}"><option value="false" ${row.is_login ? "" : "selected"}>否</option><option value="true" ${row.is_login ? "selected" : ""}>是</option></select></div>
        <div class="field"><label>登录时间</label><input data-editor-scope="account" data-editor-field="login_time" data-editor-index="${idx}" value="${row.login_time}" /></div>
        <div class="field"><label>国家地区</label><input data-editor-scope="account" data-editor-field="region" data-editor-index="${idx}" value="${row.region}" /></div>
        <div class="field"><label>video_count</label><input data-editor-scope="account" data-editor-field="video_count" data-editor-index="${idx}" value="${row.video_count}" /></div>
        <div class="field"><label>粉丝数量</label><input data-editor-scope="account" data-editor-field="follower_count" data-editor-index="${idx}" value="${row.follower_count}" /></div>
        <div class="field" style="grid-column:span 2;"><label>较上次粉丝增长数量</label><input data-editor-scope="account" data-editor-field="follower_growth" data-editor-index="${idx}" value="${row.follower_growth}" /></div>
      </div>
    </div>
  `).join("");
  renderDeviceRegionPresetOptions();
}

function getAccountRecordByUsername(username = "") {
  const normalized = String(username || "").trim().toLowerCase();
  if (!normalized) return null;
  return (accountEditorRows || []).find((item) => String(item?.username || "").trim().toLowerCase() === normalized) || null;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function renderClickableValue(value) {
  const text = String(value ?? "").trim();
  if (!text) return "-";
  const hasProtocol = /^https?:\/\//i.test(text);
  const looksLikeDomainLink = /^www\./i.test(text);
  if (!hasProtocol && !looksLikeDomainLink) {
    return escapeHtml(text);
  }
  const href = hasProtocol ? text : `https://${text}`;
  const safeHref = escapeHtml(href);
  const safeText = escapeHtml(text);
  return `<a href="${safeHref}" target="_blank" rel="noopener noreferrer">${safeText}</a>`;
}

function renderLinkedAccountInfo(row = {}) {
  const username = String(row?.username || "").trim();
  if (!username) {
    return '<div class="hint">当前条目未填写 username，无法关联账号记录。</div>';
  }
  const matched = getAccountRecordByUsername(username);
  if (!matched) {
    return `<div class="hint">未在账号表单中找到 username 为「${username}」的记录。</div>`;
  }
  return `
    <div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px 12px;font-size:12px;color:#334155;">
      <div><strong>username：</strong>${matched.username || "-"}</div>
      <div><strong>password：</strong>${matched.password || "-"}</div>
      <div><strong>email：</strong>${matched.email || "-"}</div>
      <div><strong>email_password：</strong>${matched.email_password || "-"}</div>
      <div><strong>api：</strong>${renderClickableValue(matched.api)}</div>
      <div><strong>is_login：</strong>${matched.is_login ? "是" : "否"}</div>
      <div><strong>login_time：</strong>${matched.login_time || "-"}</div>
      <div><strong>region：</strong>${matched.region || "-"}</div>
      <div><strong>video_count：</strong>${matched.video_count || "-"}</div>
      <div><strong>follower_count：</strong>${matched.follower_count || "-"}</div>
      <div><strong>follower_growth：</strong>${matched.follower_growth || "-"}</div>
    </div>
  `;
}

function renderDeviceEditorRows() {
  if (!deviceAliasGroupsEl) return;
  renderDeviceAliasPresetOptions();
  if (!deviceEditorRows.length) {
    deviceAliasGroupsEl.innerHTML = '<div class="hint">暂无设备记录</div>';
    return;
  }
  const groups = new Map();
  deviceEditorRows.forEach((row, idx) => {
    const aliasKey = String(row.device_alias || "").trim() || "__EMPTY_ALIAS__";
    if (!groups.has(aliasKey)) {
      groups.set(aliasKey, []);
    }
    groups.get(aliasKey).push({ row, idx });
  });

  const orderedGroups = Array.from(groups.entries()).sort((a, b) => {
    const aLabel = a[0] === "__EMPTY_ALIAS__" ? "zzz" : String(a[0]).toLowerCase();
    const bLabel = b[0] === "__EMPTY_ALIAS__" ? "zzz" : String(b[0]).toLowerCase();
    return aLabel.localeCompare(bLabel);
  });

  deviceAliasGroupsEl.innerHTML = orderedGroups.map(([aliasKey, items], groupIdx) => {
    const aliasLabel = aliasKey === "__EMPTY_ALIAS__" ? "未设置别名" : aliasKey;
    const firstDeviceId = String(items[0]?.row?.device_id || "").trim() || "-";
    const rowsHtml = items.map(({ row, idx }) => {
      const linkedAccountInfo = renderLinkedAccountInfo(row);
      return `
      <div class="panel-card" style="margin:0 0 10px 0;background:#fff;border:1px solid #eef2ff;">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px;">
          <strong>记录 #${idx + 1}</strong>
          <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;justify-content:flex-end;">
            <span class="hint">device_id：${row.device_id || "-"}</span>
            <button class="ghost" data-action="alias-clear" data-device-alias="${row.device_alias || ""}" data-device-id="${row.device_id || ""}">清理</button>
            <button class="ghost" data-account-backup="${row.device_alias || ""}" data-device-id="${row.device_id || ""}" data-account-username="${row.username || ""}" data-account-id="">备份</button>
            <button class="ghost" data-account-restore="${row.device_alias || ""}" data-device-id="${row.device_id || ""}" data-account-backup-name="${row.backup_name || ""}" data-account-id="">恢复</button>
            <button class="ghost" data-editor-delete="device" data-editor-index="${idx}">删除</button>
          </div>
        </div>
        <div style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;">
          <div class="field"><label>device_alias</label><input list="deviceAliasOptions" data-editor-scope="device" data-editor-field="device_alias" data-editor-index="${idx}" value="${row.device_alias}" /></div>
          <div class="field"><label>device_id</label><input data-editor-scope="device" data-editor-field="device_id" data-editor-index="${idx}" value="${row.device_id}" /></div>
          <div class="field"><label>username</label><input data-editor-scope="device" data-editor-field="username" data-editor-index="${idx}" value="${row.username}" /></div>
          <div class="field"><label>IP</label><input data-editor-scope="device" data-editor-field="ip" data-editor-index="${idx}" value="${row.ip}" /></div>
          <div class="field"><label>国家地区</label><input data-editor-scope="device" data-editor-field="region" data-editor-index="${idx}" value="${row.region}" /></div>
          <div class="field"><label>backup_path</label><input data-editor-scope="device" data-editor-field="backup_path" data-editor-index="${idx}" value="${row.backup_path}" /></div>
          <div class="field"><label>backup_name</label><input data-editor-scope="device" data-editor-field="backup_name" data-editor-index="${idx}" value="${row.backup_name}" /></div>
          <div class="field"><label>backup_time</label><input data-editor-scope="device" data-editor-field="backup_time" data-editor-index="${idx}" value="${row.backup_time}" /></div>
          <div class="field"><label>backup_status</label><input data-editor-scope="device" data-editor-field="backup_status" data-editor-index="${idx}" value="${row.backup_status}" /></div>
          <div class="field" style="grid-column:1/-1;"><label>remark</label><input data-editor-scope="device" data-editor-field="remark" data-editor-index="${idx}" value="${row.remark}" /></div>
        </div>
        <details style="margin-top:10px;border:1px dashed #dbe3ff;border-radius:10px;padding:8px 10px;background:#f8fbff;">
          <summary style="cursor:pointer;font-size:12px;color:#334155;font-weight:600;">查看该条目关联的 username 账号记录</summary>
          <div style="margin-top:8px;">
            ${linkedAccountInfo}
          </div>
        </details>
      </div>
    `;
    }).join("");

    return `
      <details class="panel-card" data-device-alias-group="1" style="margin:0 0 12px 0;background:#f8faff;" ${groupIdx < 2 ? "open" : ""}>
        <summary style="cursor:pointer;display:flex;justify-content:space-between;align-items:center;gap:10px;list-style:none;">
          <span><strong>${aliasLabel}</strong> <span class="hint">(${items.length} 条记录)</span></span>
          <span class="hint">device_id示例：${firstDeviceId}</span>
        </summary>
        <div style="margin-top:10px;">
          ${rowsHtml}
        </div>
      </details>
    `;
  }).join("");
}

function collapseDeviceAliasGroupsExcept(activeDetails) {
  if (!deviceAliasGroupsEl || !(activeDetails instanceof HTMLDetailsElement)) return;
  const allGroups = deviceAliasGroupsEl.querySelectorAll("details[data-device-alias-group='1']");
  allGroups.forEach((detailsEl) => {
    if (!(detailsEl instanceof HTMLDetailsElement)) return;
    detailsEl.open = detailsEl === activeDetails;
  });
}

async function syncEditorField(scope, index, field, value, checked = false) {
  if (scope === "account") {
    const row = accountEditorRows[index];
    if (!row) return;
    row[field] = field === "is_login" ? (String(value) === "true" || checked) : value;
    await saveAccountRow(index);
    if (field === "region" || field === "is_login" || field === "username") {
      renderDeviceRegionPresetOptions();
    }
  } else if (scope === "device") {
    const row = deviceEditorRows[index];
    if (!row) return;
    row[field] = value;
    await saveDeviceRow(index);
  }
}

async function addBlankEditorRow() {
  accountEditorRows.push(getAccountRowTemplate());
  const index = accountEditorRows.length - 1;
  await saveAccountRow(index);
  renderAccountEditorRows();
}

function isAccountLoggedIn(row = {}) {
  const raw = row?.is_login;
  return raw === true || raw === 1 || raw === "1" || raw === "true";
}

function pickUnloggedAccountByRegion(region) {
  const normalizedRegion = String(region || "").trim();
  if (!normalizedRegion) return null;
  return (accountEditorRows || []).find((row) => {
    const accountRegion = String(row?.region || "").trim();
    const username = String(row?.username || "").trim();
    if (!username) return false;
    if (accountRegion !== normalizedRegion || isAccountLoggedIn(row)) return false;
    return !isUsernameUsedElsewhere(username, -1);
  }) || null;
}

async function addDeviceBlankEditorRow(aliasOverride = "") {
  const selectedRegion = String(deviceRegionPresetSelect?.value || "").trim();
  if (!selectedRegion) {
    throw new Error("请先选择国家地区后再新增记录");
  }
  const matchedAccount = pickUnloggedAccountByRegion(selectedRegion);
  if (!matchedAccount) {
    throw new Error(`新增失败：账号表单中没有地区为「${selectedRegion}」、未登录且未被分配的账号`);
  }
  const selectedAlias = String(aliasOverride || deviceAliasPresetSelect?.value || "").trim();
  const inferredDeviceId = resolveDeviceIdByAlias(selectedAlias);
  deviceEditorRows.push(getDeviceRowTemplate({
    device_alias: selectedAlias,
    device_id: inferredDeviceId,
    region: selectedRegion,
    username: String(matchedAccount.username || "").trim(),
  }));
  const index = deviceEditorRows.length - 1;
  try {
    await saveDeviceRow(index);
    renderDeviceEditorRows();
  } catch (error) {
    // 保存失败时回滚本地临时新增，避免“失败但界面仍多一条记录”。
    deviceEditorRows.splice(index, 1);
    renderDeviceEditorRows();
    throw error;
  }
}

async function deleteEditorRow(scope, index) {
  if (scope === "account") {
    accountEditorRows.splice(index, 1);
    renderAccountEditorRows();
    for (let i = index; i < accountEditorRows.length; i += 1) {
      await saveAccountRow(i);
    }
  } else {
    const previousLength = deviceEditorRows.length;
    deviceEditorRows.splice(index, 1);
    renderDeviceEditorRows();
    try {
      for (let i = index; i < deviceEditorRows.length; i += 1) {
        await saveDeviceRow(i, { skipUsernameDuplicateCheck: true, skipReload: true });
      }
      await request(`${DEVICE_ROWS_API}/delete`, {
        method: "POST",
        body: JSON.stringify({ row_index: previousLength - 1 }),
      });
      await loadEditorState();
      renderAccountEditorRows();
      renderDeviceEditorRows();
    } catch (error) {
      await loadEditorState();
      renderAccountEditorRows();
      renderDeviceEditorRows();
      throw error;
    }
  }
}

function renderDeviceAliasGroups(groups = [], recordsByAlias = {}) {
  if (!deviceAliasGroupsEl) return;
  if (!groups.length) {
    deviceAliasGroupsEl.innerHTML = '<div class="hint">暂无设备别名数据</div>';
    return;
  }
  deviceAliasGroupsEl.innerHTML = groups.map((group) => {
    const alias = String(group.mobile_alias || "").trim();
    const count = Number(group.account_count || 0);
    const rows = recordsByAlias[alias] || [];
    const tableRows = rows.map((row) => `
      <tr data-account-id="${row.id ?? ""}" data-account-username="${row.username ?? ""}" data-account-backup-name="${row.backup_name ?? ""}">
        <td style="padding:8px;border-bottom:1px solid #eee;vertical-align:middle;">
          <div title="${row.username ?? ""}" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${row.username ?? ""}</div>
        </td>
        <td style="padding:8px;border-bottom:1px solid #eee;text-align:center;vertical-align:middle;">${row.video_count ?? ""}</td>
        <td style="padding:8px;border-bottom:1px solid #eee;text-align:center;vertical-align:middle;">${row.follower_count ?? ""}</td>
        <td style="padding:8px;border-bottom:1px solid #eee;text-align:center;vertical-align:middle;">${row.new_follower_count ?? ""}</td>
        <td style="padding:8px;border-bottom:1px solid #eee;vertical-align:middle;">
          <div title="${row.backup_path ?? ""}" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${row.backup_path ?? ""}</div>
        </td>
        <td style="padding:8px;border-bottom:1px solid #eee;vertical-align:middle;">
          <div title="${row.backup_name ?? ""}" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${row.backup_name ?? ""}</div>
        </td>
        <td style="padding:8px;border-bottom:1px solid #eee;white-space:nowrap;vertical-align:middle;">
          <button class="ghost" data-account-backup="${alias}" data-account-username="${row.username ?? ""}" data-account-id="${row.id ?? ""}">备份并清理设备</button>
          <button class="ghost" data-account-restore="${alias}" data-account-backup-name="${row.backup_name ?? ""}" data-account-id="${row.id ?? ""}">还原该备份</button>
        </td>
      </tr>
    `).join("");
    return `
      <div class="panel-card" style="margin-bottom:12px;">
        <div style="display:flex;justify-content:space-between;gap:10px;align-items:center;flex-wrap:wrap;">
          <div>
            <strong>${alias}</strong>
            <div class="hint">关联账号数：${count}</div>
          </div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;">
            <button class="ghost" data-alias-add-row="${alias}">新增空白记录</button>
            <button class="ghost" data-action="alias-clear" data-device-alias="${alias}">清理 TikTok 数据</button>
          </div>
        </div>
        <div style="overflow:auto;margin-top:10px;border:1px solid #eee;border-radius:8px;max-width:100%;">
          <table style="width:100%;border-collapse:collapse;font-size:12px;table-layout:fixed;">
            <thead>
              <tr>
                <th style="padding:8px;text-align:left;width:16%;">username</th>
                <th style="padding:8px;text-align:center;width:9%;">video_count</th>
                <th style="padding:8px;text-align:center;width:9%;">follower_count</th>
                <th style="padding:8px;text-align:center;width:12%;">new_follower_count</th>
                <th style="padding:8px;text-align:left;width:28%;">backup_path</th>
                <th style="padding:8px;text-align:left;width:14%;">backup_name</th>
                <th style="padding:8px;text-align:left;width:12%;">actions</th>
              </tr>
            </thead>
            <tbody>${tableRows || '<tr><td colspan="7" style="padding:10px;color:#666;">暂无账号数据</td></tr>'}</tbody>
          </table>
        </div>
      </div>
    `;
  }).join("");
}
if (deviceLogDeviceSelect) {
  deviceLogDeviceSelect.addEventListener("change", () => {
    renderDeviceExecLogs(latestDeviceLogs || {});
  });
}
if (addBlankRecordBtn) {
  addBlankRecordBtn.addEventListener("click", () => addBlankEditorRow().catch((e) => setStatus(e.message || "新增失败", "error")));
}
if (importAccountBatchBtn) {
  importAccountBatchBtn.addEventListener("click", () => {
    importAccountBatchFromText().catch((e) => setStatus(e.message || "批量导入失败", "error"));
  });
}
if (addDeviceBlankRecordBtn) {
  addDeviceBlankRecordBtn.addEventListener("click", () => addDeviceBlankEditorRow().catch((e) => setStatus(e.message || "新增失败", "error")));
}
if (accountEditorListEl) {
  accountEditorListEl.addEventListener("input", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    const scope = String(target.dataset.editorScope || "account");
    const index = Number(target.dataset.editorIndex);
    const field = String(target.dataset.editorField || "");
    if (!field || Number.isNaN(index)) return;
    syncEditorField(scope, index, field, target.value, target.checked).catch((e) => setStatus(e.message || "保存失败", "error"));
  });
  accountEditorListEl.addEventListener("change", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLSelectElement)) return;
    const scope = String(target.dataset.editorScope || "account");
    const index = Number(target.dataset.editorIndex);
    const field = String(target.dataset.editorField || "");
    if (!field || Number.isNaN(index)) return;
    syncEditorField(scope, index, field, target.value, false).catch((e) => setStatus(e.message || "保存失败", "error"));
  });
  accountEditorListEl.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    const delBtn = target.closest("[data-editor-delete]");
    if (delBtn) {
      const scope = String(delBtn.getAttribute("data-editor-delete") || "account");
      const index = Number(delBtn.getAttribute("data-editor-index"));
      if (!Number.isNaN(index)) deleteEditorRow(scope, index).catch((e) => setStatus(e.message || "删除失败", "error"));
    }
  });
}
if (deviceAliasGroupsEl) {
  deviceAliasGroupsEl.addEventListener("input", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    const activeDetails = target.closest("details[data-device-alias-group='1']");
    if (activeDetails instanceof HTMLDetailsElement) {
      activeDetails.open = true;
      collapseDeviceAliasGroupsExcept(activeDetails);
    }
    const scope = String(target.dataset.editorScope || "device");
    const index = Number(target.dataset.editorIndex);
    const field = String(target.dataset.editorField || "");
    if (!field || Number.isNaN(index)) return;
    if (scope === "device" && field === "device_alias") {
      const normalizedAlias = String(target.value || "").trim();
      const inferredDeviceId = resolveDeviceIdByAlias(normalizedAlias);
      if (inferredDeviceId) {
        const idInput = deviceAliasGroupsEl.querySelector(
          `input[data-editor-scope="device"][data-editor-field="device_id"][data-editor-index="${index}"]`
        );
        if (idInput instanceof HTMLInputElement) {
          idInput.value = inferredDeviceId;
        }
        const row = deviceEditorRows[index];
        if (row) {
          row.device_id = inferredDeviceId;
        }
      }
    }
    syncEditorField(scope, index, field, target.value, target.checked).catch((e) => setStatus(e.message || "保存失败", "error"));
  });
  deviceAliasGroupsEl.addEventListener("click", async (event) => {
    const targetEl = event.target;
    if (!(targetEl instanceof Element)) return;
    const target = targetEl.closest("button");
    if (!target) return;
    if (target.hasAttribute("data-alias-add-row")) {
      event.preventDefault();
      const alias = String(target.getAttribute("data-alias-add-row") || "").trim();
      await addDeviceBlankEditorRow(alias);
      return;
    }
    const delBtn = target.closest("[data-editor-delete]");
    if (delBtn) {
      const scope = String(delBtn.getAttribute("data-editor-delete") || "device");
      const index = Number(delBtn.getAttribute("data-editor-index"));
      if (!Number.isNaN(index)) deleteEditorRow(scope, index).catch((e) => setStatus(e.message || "删除失败", "error"));
      return;
    }
  });
}

if (authLoginBtn) authLoginBtn.addEventListener("click", () => doLogin().catch((e) => { if (authMessageEl) authMessageEl.textContent = e.message || "登录失败"; }));
if (authPasswordInput) authPasswordInput.addEventListener("keydown", (event) => { if (event.key === "Enter") authLoginBtn?.click(); });
if (authUsernameInput) authUsernameInput.addEventListener("keydown", (event) => { if (event.key === "Enter") authLoginBtn?.click(); });
if (logoutBtn) logoutBtn.addEventListener("click", () => {
  setAuthToken("");
  setCachedUser(null);
  currentUser = null;
  configTargetUsersLoaded = false;
  appShell?.classList.add("hidden");
  authShell?.classList.remove("hidden");
  if (authMessageEl) authMessageEl.textContent = "请先登录";
});
if (adminCreateUserBtn) {
  adminCreateUserBtn.addEventListener("click", () => {
    createAdminUser().catch((e) => {
      if (adminUsersMsgEl) adminUsersMsgEl.textContent = e.message || "创建失败";
    });
  });
}
if (adminReloadUsersBtn) {
  adminReloadUsersBtn.addEventListener("click", () => {
    loadAdminUsers().catch((e) => {
      if (adminUsersMsgEl) adminUsersMsgEl.textContent = e.message || "加载失败";
    });
  });
}

toggleDeviceOpsButtons();

if (apiBaseInput) {
  const stored = (localStorage.getItem(API_BASE_LS_KEY) || "").trim().replace(/\/$/, "");
  const legacyBases = new Set(["http://127.0.0.1:8000", "http://localhost:8000"]);
  const hasLegacy8000Port = /:8000(\/api)?$/i.test(stored);
  const normalizedDefault = defaultApiBase();
  const nextValue = !stored || legacyBases.has(stored) || hasLegacy8000Port ? normalizedDefault : stored;
  apiBaseInput.value = nextValue;
  localStorage.setItem(API_BASE_LS_KEY, nextValue);
  apiBaseInput.addEventListener("change", () => {
    const normalized = (apiBaseInput.value || "").trim().replace(/\/$/, "");
    if (normalized) {
      localStorage.setItem(API_BASE_LS_KEY, normalized);
    } else {
      localStorage.removeItem(API_BASE_LS_KEY);
    }
  });
}

(async () => {
  const ok = await authCheck().catch(() => false);
  if (ok) {
    // 先加载用户运行配置（含模板列表），避免首屏设备模板下拉先渲染成 default。
    await loadUserRuntimeConfig().catch(() => {});
    handleRefresh();
    loadAccounts();
    loadDbTablePage();
    loadAdminUsers();
    loadEditorState().then(() => {
      renderAccountEditorRows();
      renderDeviceEditorRows();
    }).catch(() => {});
    setInterval(refreshTaskStatus, 2000);
    refreshTaskStatus();
    setInterval(pollDeviceLogsSnapshot, 4000);
    pollDeviceLogsSnapshot();
    setInterval(pollOverviewSnapshot, 6000);
    pollOverviewSnapshot();
  } else {
    authShell?.classList.remove("hidden");
    appShell?.classList.add("hidden");
  }
})();

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") {
    void pollOverviewSnapshot();
  }
});
