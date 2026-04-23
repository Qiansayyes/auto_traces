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
const uploadBtn = document.getElementById("uploadUsernames");
const saveConfigBtn = document.getElementById("saveConfig");
const selectAllCheckbox = document.getElementById("selectAll");
const startSelectedBtn = document.getElementById("startSelected");
const startAllBtn = document.getElementById("startAll");
const stopSelectedBtn = document.getElementById("stopSelected");
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
const backupUsernameInput = document.getElementById("backupUsernameInput");
const backupTiktokPasswordInput = document.getElementById("backupTiktokPasswordInput");
const backupMobileAliasInput = document.getElementById("backupMobileAliasInput");
const backupApiAddressInput = document.getElementById("backupApiAddressInput");
const backupEmailInput = document.getElementById("backupEmailInput");
const backupEmailPasswordInput = document.getElementById("backupEmailPasswordInput");
const backupVideoCountInput = document.getElementById("backupVideoCountInput");
const backupFollowerCountInput = document.getElementById("backupFollowerCountInput");
const backupNewFollowerCountInput = document.getElementById("backupNewFollowerCountInput");
const submitBackupRecordBtn = document.getElementById("submitBackupRecordBtn");
const queryAndFillBackupBtn = document.getElementById("queryAndFillBackupBtn");
const backupRecordHintEl = document.getElementById("backupRecordHint");
const backupRecordTableBodyEl = document.getElementById("backupRecordTableBody");
const deviceOpsSelect = document.getElementById("deviceOpsSelect");
const deviceAliasSelect = document.getElementById("backupDeviceAliasSelect");
const deviceLogDeviceSelect = document.getElementById("deviceLogDeviceSelect");
const backupSearchUsernameInput = document.getElementById("backupSearchUsernameInput");
const queryBackupRecordsBtn = document.getElementById("queryBackupRecordsBtn");
const resetBackupQueryBtn = document.getElementById("resetBackupQueryBtn");
const backupQueryHintEl = document.getElementById("backupQueryHint");
const deviceExecLogsEl = document.getElementById("deviceExecLogs");
const clearTikTokBtn = document.getElementById("clearTikTokBtn");
const backupClearTikTokBtn = document.getElementById("backupClearTikTokBtn");
const refreshBackupsBtn = document.getElementById("refreshBackupsBtn");
const restoreTikTokBtn = document.getElementById("restoreTikTokBtn");
const restoreTikTokExecBtn = document.getElementById("restoreTikTokExecBtn");
const restoreBackupSelect = document.getElementById("restoreBackupSelect");
const backupCloudInfoEl = document.getElementById("backupCloudInfo");
const forceStopTikTokBtn1 = document.getElementById("forceStopTikTokBtn1");
const forceStopTikTokBtn2 = document.getElementById("forceStopTikTokBtn2");
const forceStopTikTokBtn3 = document.getElementById("forceStopTikTokBtn3");
const launchTikTokBtn1 = document.getElementById("launchTikTokBtn1");
const launchTikTokBtn2 = document.getElementById("launchTikTokBtn2");
const launchTikTokBtn3 = document.getElementById("launchTikTokBtn3");

const COMMENT_TEXTS_LS_KEY = "auto_traces_admin_comment_texts_v1";

let latestDevices = {};
let latestDeviceLogs = {};
let currentRunMode = "growth";
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

const sections = document.querySelectorAll(".panel");
const navItems = document.querySelectorAll(".nav-item");
let currentUser = null;
const TOKEN_KEY = "auto_traces_auth_token_v1";

function setStatus(text, type = "info") {
  statusEl.textContent = text;
  statusEl.className = `status ${type}`;
}

function appendDebugLog() {}

function summarizeDeviceOpResult(result) {
  if (!result) return "暂无设备执行结果";
  const lines = [];
  if (result.status) lines.push(`状态: ${result.status}`);
  if (result.message) lines.push(`消息: ${result.message}`);
  if (result.op_type) lines.push(`操作: ${result.op_type}`);
  if (result.device_id) lines.push(`设备: ${result.device_id}`);
  if (result.backup_name) lines.push(`备份名: ${result.backup_name}`);
  if (result.backup_path) lines.push(`备份路径: ${result.backup_path}`);
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

function apiBase() {
  const manual = (apiBaseInput?.value || "").trim().replace(/\/$/, "");
  if (manual) return manual;
  return window.location.origin;
}

function authToken() {
  return localStorage.getItem(TOKEN_KEY) || "";
}

function setAuthToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
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

function switchSection(id) {
  sections.forEach((section) => {
    section.classList.toggle("active", section.id === id);
  });
  navItems.forEach((item) => {
    item.classList.toggle("active", item.dataset.section === id);
  });
}

navItems.forEach((item) => {
  item.addEventListener("click", () => switchSection(item.dataset.section));
});

async function request(path, options = {}) {
  const url = `${apiBase()}${path}`;
  appendDebugLog("REQUEST", { url, options: { ...options, body: options.body || undefined } });
  try {
    const headers = {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    };
    const token = authToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    const response = await fetch(url, {
      ...options,
      headers,
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
  const rows = Object.entries(devices)
    .filter(([, info]) => {
      if (!aliasFilter) return true;
      return String(info?.alias || "").toLowerCase().includes(aliasFilter);
    })
    .map(([deviceId, info]) => {
      const statusClass = info.status;
      const pending = info.pending_start ? "是" : "否";
      const pendingBadge = info.pending_start
        ? "<span class=\"badge pending\">待启动</span>"
        : "-";
      const disabled = info.status === "offline" ? "disabled" : "";
      const rowStyle = info.status === "offline" ? ' style="opacity:.55;"' : "";
      const ownerCell = isAdmin ? `<td>${String(info?.owner_username || "")}</td>` : "";
      const optOut = Boolean(info?.auto_start_opt_out);
      const autoStartCell = `
        <td>
          <button
            type="button"
            class="${optOut ? "ghost" : "primary"}"
            style="padding:6px 10px;font-size:12px;"
            data-action="toggle-device-auto-start"
            data-device="${deviceId}"
            data-optout="${optOut ? "1" : "0"}"
          >
            ${optOut ? "已关闭" : "允许"}
          </button>
        </td>
      `;
      return `
      <tr${rowStyle}>
        <td><input type="checkbox" class="device-check" data-device="${deviceId}" ${disabled} /></td>
        <td>${deviceId}</td>
        <td>${info.alias}</td>
        ${ownerCell}
        <td><span class="badge ${statusClass}">${info.status}</span></td>
        <td>${info.pending_start ? pendingBadge : pending}</td>
        ${autoStartCell}
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
  const modeSelect = document.getElementById("configModeSelect");
  if (modeSelect) modeSelect.value = mode;
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
    setStatus("停止指令下发中...", "info");
    const data = await request("/api/stop_task", {
      method: "POST",
      body: JSON.stringify({ device_ids: deviceIds }),
    });
    await loadOverview();
    setStatus(`停止指令已下发 ${data.count} 台设备`, "success");
  } catch (error) {
    setStatus(error.message || "停止失败", "error");
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
function applyDevicesAndLogsPayload(data) {
  const devices = data.devices || {};
  const deviceLogs = data.device_logs || {};
  latestDevices = devices;
  latestDeviceLogs = deviceLogs;
  currentRunMode = data.run_mode || currentRunMode || "growth";
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
  renderDevices(devices);
  renderDeviceOpsSelect(devices);
  renderDeviceLogDeviceSelect(devices, deviceLogs);
  renderDeviceExecLogs(deviceLogs);
  bindDeviceSelection();
}

async function loadOverview() {
  const data = await request("/api/admin/overview");
  applyDevicesAndLogsPayload(data);
  if (followerPendingEl) {
    followerPendingEl.textContent = data.queue_stats?.follower?.pending ?? 0;
  }
  if (mobilePendingEl) {
    mobilePendingEl.textContent = data.queue_stats?.mobile_pending ?? 0;
  }

  renderTagList(pendingList, data.pending_usernames || []);
  renderTagList(pulledList, data.pulled_usernames || []);
}

async function pollDeviceLogsSnapshot() {
  try {
    const data = await request("/api/admin/logs_snapshot");
    applyDevicesAndLogsPayload(data);
  } catch (_) {
    /* 轮询失败静默，避免刷屏；用户可手动点刷新数据 */
  }
}

async function handleRefresh() {
  try {
    setStatus("刷新中...", "info");
    await loadOverview();
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
  if (Object.keys(payload).length === 0) {
    setStatus("请填写配置项", "warning");
    return;
  }
  try {
    setStatus(`保存账号独立配置（${currentRunMode}）中...`, "info");
    const data = await request("/api/admin/update_config", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    const cfg = await loadUserRuntimeConfig();
    fillConfigForm(cfg?.configs_by_mode?.[currentRunMode] || data.config || {}, currentRunMode);
    syncCvQualityFilterInputsFromConfig(cfg?.configs_by_mode?.[currentRunMode] || data.config || {});
    persistCommentTextsToLocal();
    setStatus(`已保存当前账号的 ${currentRunMode} 模式配置`, "success");
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
    const cfg = await loadUserRuntimeConfig();
    fillConfigForm(cfg?.configs_by_mode?.[currentRunMode] || data.config || {}, currentRunMode);
    syncCvQualityFilterInputsFromConfig(cfg?.configs_by_mode?.[currentRunMode] || data.config || {});
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
  const [liveData, rootsData] = await Promise.all([
    request("/api/admin/live_customers"),
    loadTiktokRootsData(),
  ]);
  renderTagList(liveCustomerList, liveData.items || [], {
    formatter: (item) => `${item.username} | usage:${item.usage_count} | ${item.created_at}`,
  });
  renderTikTokRootsTable(rootsData.items || []);

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

async function loadTiktokRootsData() {
  const pageSize = Number(tiktokRootsPageSizeEl?.value || 100);
  const data = await request(`/api/admin/tiktok_roots?page=${currentTiktokRootsPage}&page_size=${pageSize}`);
  const items = Array.isArray(data.items) ? data.items : [];
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
    const query = `?container_ids=${encodeURIComponent(container_ids)}&parallel=${parallel}`;
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
    const query = `?container_ids=${encodeURIComponent(container_ids)}&parallel=${parallel}`;
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
    if (taskLogsEl) {
      const logs = data.task_logs || {};
      const names = Object.keys(logs);
      if (!names.length) {
        taskLogsEl.textContent = "暂无日志";
      } else {
        const text = names
          .map((name) => `### ${name}\n${(logs[name] || []).join("\n")}`)
          .join("\n\n");
        taskLogsEl.textContent = text;
      }
    }
    if (cvTaskLogsEl) {
      const logs = data.task_logs || {};
      const list = logs?.process_queue || logs?.trigger_process_queue || logs?.follower_queue || null;
      if (Array.isArray(list) && list.length) {
        cvTaskLogsEl.textContent = list.join("\n");
      } else {
        const names = Object.keys(logs || {});
        if (!names.length) {
          cvTaskLogsEl.textContent = "暂无日志";
        } else {
          const text = names
            .map((name) => `### ${name}\n${(logs[name] || []).join("\n")}`)
            .join("\n\n");
          cvTaskLogsEl.textContent = text;
        }
      }
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
    const data = await request("/api/admin/runtime_config");
    const cfg = data.data || {};
    currentRunMode = cfg.run_mode || currentRunMode || "growth";
    updateModeButtons();
    fillConfigForm(cfg.configs_by_mode?.[currentRunMode] || {}, currentRunMode);
    syncCvQualityFilterInputsFromConfig(cfg.configs_by_mode?.[currentRunMode] || {});
    return cfg;
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
    updateModeButtons();
    const cfg = await loadUserRuntimeConfig();
    if (cfg?.configs_by_mode?.[currentRunMode]) {
      fillConfigForm(cfg.configs_by_mode[currentRunMode] || {}, currentRunMode);
      syncCvQualityFilterInputsFromConfig(cfg.configs_by_mode[currentRunMode] || {});
    }
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
  return { device_id, alias };
}

function renderBackupSelect(items = []) {
  if (!restoreBackupSelect) return;
  if (!items.length) {
    restoreBackupSelect.innerHTML = '<option value="">暂无可恢复备份</option>';
    return;
  }
  restoreBackupSelect.innerHTML = items
    .map((x) => `<option value="${x.backup_name}">${x.backup_name} | ${x.mtime} | ${x.local_path}</option>`)
    .join("");
}

function renderBackupAliasOptions(items = []) {
  if (!backupMobileAliasInput) return;
  const recordAliases = Array.from(new Set((items || []).map((item) => String(item.mobile_alias || "").trim()).filter(Boolean)));
  const onlineAliases = Array.from(new Set(Object.values(latestDevices || {}).map((device) => String(device?.alias || "").trim()).filter(Boolean)));
  const aliases = Array.from(new Set([...onlineAliases, ...recordAliases]));
  if (!aliases.length) {
    backupMobileAliasInput.innerHTML = '<option value="">暂无可选手机别名</option>';
    return;
  }
  backupMobileAliasInput.innerHTML = ['<option value="">请选择手机别名</option>', ...aliases.map((alias) => `<option value="${alias}">${alias}</option>`)].join("");
}

function renderBackupRecords(items = []) {
  if (!backupRecordTableBodyEl) return;
  const isAdmin = currentUser?.role === "admin";
  const emptyText = isAdmin ? "暂无数据" : "暂无你名下的备份数据";
  const colSpan = isAdmin ? 8 : 7;
  if (!items.length) {
    backupRecordTableBodyEl.innerHTML = `<tr><td colspan="${colSpan}" style="padding:10px;color:#666;">${emptyText}</td></tr>`;
    renderBackupAliasOptions([]);
    return;
  }
  renderBackupAliasOptions(items);
  backupRecordTableBodyEl.innerHTML = items.map((item) => `
    <tr data-backup-username="${item.username ?? ""}" data-backup-tiktok-password="${item.tiktok_password ?? ""}" data-backup-mobile-alias="${item.mobile_alias ?? ""}" data-backup-path="${item.backup_path ?? ""}" data-backup-name="${item.backup_name ?? ""}">
      ${isAdmin ? `<td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.owner_username ?? ""}</td>` : ""}
      <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.username ?? ""}</td>
      <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.mobile_alias ?? ""}</td>
      <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.video_count ?? ""}</td>
      <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.follower_count ?? ""}</td>
      <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.new_follower_count ?? ""}</td>
      <td style="border-bottom:1px solid #f0f0f0;padding:8px;word-break:break-all;">${item.backup_path ?? ""}</td>
      <td style="border-bottom:1px solid #f0f0f0;padding:8px;word-break:break-all;">${item.backup_name ?? ""}</td>
      <td style="border-bottom:1px solid #f0f0f0;padding:8px;">${item.updated_at ?? ""}</td>
    </tr>
  `).join("");
}

function refreshBackupQueryHint(count = 0) {
  if (!backupQueryHintEl) return;
  backupQueryHintEl.textContent = `当前共有 ${count} 条展示记录。`;
}

function logBackupAction(action, detail) {
  appendDebugLog(action, detail);
}

async function refreshTikTokBackups() {
  try {
    setStatus("正在加载可恢复备份...", "info");
    appendDebugLog("CLICK", "刷新可恢复备份");
    const data = await request("/api/admin/device_tiktok/backups");
    renderBackupSelect(data.items || []);
    if (backupQueryHintEl) {
      backupQueryHintEl.textContent = currentUser?.role === "admin" ? "可恢复备份仅显示当前设备导出的记录。" : "普通用户可查看自己名下的备份记录。";
    }
    setStatus("可恢复备份已刷新", "success");
  } catch (error) {
    setStatus(error.message || "加载备份失败", "error");
  }
}

async function refreshBackupRecords() {
  try {
    const query = new URLSearchParams();
    query.set("page", "1");
    query.set("page_size", "500");
    const isAdmin = currentUser?.role === "admin";
    const username = (backupSearchUsernameInput?.value || "").trim();
    const mobileAlias = (deviceAliasSelect?.value || "").trim();
    if (isAdmin && username) query.set("username", username);
    if (mobileAlias) query.set("mobile_alias", mobileAlias);
    const data = await request(`/api/admin/tiktok_backup/records?${query.toString()}`);
    renderBackupRecords(data.items || []);
    refreshBackupQueryHint(Array.isArray(data.items) ? data.items.length : 0);
    renderBackupAliasOptions(data.items || []);
    if (backupRecordHintEl) {
      backupRecordHintEl.textContent = isAdmin ? "管理员可查看全部备份档案，并可按用户名/手机别名筛选。" : "普通用户仅可查看自己名下的备份档案。";
    }
    setStatus("备份档案已刷新", "success");
  } catch (error) {
    setStatus(error.message || "加载备份档案失败", "error");
  }
}

function getBackupSelectionRow() {
  const selected = backupRecordTableBodyEl?.querySelector("tr.selected");
  if (!selected) return null;
  return {
    username: selected.dataset.backupUsername || "",
    tiktok_password: selected.dataset.backupTiktokPassword || "",
    mobile_alias: selected.dataset.backupMobileAlias || "",
    backup_path: selected.dataset.backupPath || "",
    backup_name: selected.dataset.backupName || "",
  };
}

function syncBackupFormFromSelection(row) {
  if (!row) return;
  if (backupUsernameInput && row.username) backupUsernameInput.value = row.username;
  if (backupTiktokPasswordInput && row.tiktok_password) backupTiktokPasswordInput.value = row.tiktok_password;
  if (backupMobileAliasInput && row.mobile_alias) backupMobileAliasInput.value = row.mobile_alias;
}

async function waitForDeviceOpResult(opId, timeoutMs = 120000) {
  const start = Date.now();
  logBackupAction("POLL_START", { opId, timeoutMs });
  while (Date.now() - start < timeoutMs) {
    const pollUrl = `/api/admin/device_tiktok/op_result?op_id=${encodeURIComponent(opId)}`;
    logBackupAction("POLL_REQUEST", { url: pollUrl });
    const data = await request(pollUrl);
    logBackupAction("POLL_RESPONSE", { url: pollUrl, status: data.status, resultStatus: data?.result?.status });
    if (data.status === "done") {
      const opLogs = data?.result?.data?.operation_logs;
      if (Array.isArray(opLogs) && opLogs.length && backupCloudInfoEl) {
        backupCloudInfoEl.textContent = opLogs.join("\n");
      }
      return data.result;
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  logBackupAction("POLL_ERROR", { opId, message: "设备执行超时" });
  throw new Error("设备执行超时，请稍后刷新结果");
}

async function runTikTokDeviceOp(path, successText) {
  const device = deviceOpsSelect?.value || "";
  if (!device) {
    setStatus("请先选择在线设备", "warning");
    return;
  }
  const [deviceId, alias] = device.split("|");
  if (!deviceId || !alias) {
    setStatus("设备信息不完整", "warning");
    return;
  }
  try {
    setStatus("设备操作下发中...", "info");
    const submit = await request(path, {
      method: "POST",
      body: JSON.stringify({ device_id: deviceId, alias }),
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

async function submitBackupRecord() {
  appendDebugLog("CLICK", "提交备份档案");
  const identity = collectDeviceOpsIdentity();
  if (!identity) return;
  const username = (backupUsernameInput?.value || "").trim();
  const tiktok_password = (backupTiktokPasswordInput?.value || "").trim();
  const mobile_alias = (backupMobileAliasInput?.value || "").trim();
  const api_address = (backupApiAddressInput?.value || "").trim();
  const email = (backupEmailInput?.value || "").trim();
  const email_password = (backupEmailPasswordInput?.value || "").trim();
  if (!username || !mobile_alias) {
    setStatus("请至少填写用户名和手机别名", "warning");
    appendDebugLog("VALIDATION_ERROR", { action: "submitBackupRecord", message: "username or mobile_alias missing" });
    return;
  }
  const payload = {
    username,
    tiktok_password,
    mobile_alias,
    api_address,
    email,
    email_password,
  };
  try {
    setStatus("正在提交 TikTok 账号信息（仅入库）...", "info");
    appendDebugLog("REQUEST_PAYLOAD", { url: "/api/admin/tiktok_backup/submit", payload });
    const submit = await request("/api/admin/tiktok_backup/submit", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    appendDebugLog("SUBMIT_RESPONSE", submit);
    if (backupCloudInfoEl) {
      backupCloudInfoEl.textContent = JSON.stringify(submit, null, 2);
    }
    await refreshBackupRecords();
    await refreshTikTokBackups();
    setStatus("备份信息已入库", "success");
  } catch (error) {
    appendDebugLog("SUBMIT_ERROR", { message: error.message || String(error) });
    setStatus(error.message || "提交备份失败", "error");
  }
}

async function clearTikTokData() {
  appendDebugLog("CLICK", "单独清理 TikTok 数据");
  const identity = collectDeviceOpsIdentity();
  if (!identity) {
    appendDebugLog("VALIDATION_ERROR", { action: "clearTikTokData", message: "missing device" });
    return;
  }
  const confirmed = confirmTikTokAction("单独清理 TikTok 数据", "仅执行设备端 TikTok 应用数据清理，不会创建新的备份。\n适用场景：清缓存/清应用数据后重新登录。");
  if (!confirmed) {
    setStatus("已取消清理", "warning");
    return;
  }
  try {
    setStatus("正在下发任务：单独清理 TikTok 数据...", "info");
    appendDebugLog("REQUEST_PAYLOAD", { url: "/api/admin/device_tiktok/clear", payload: identity });
    const submit = await request("/api/admin/device_tiktok/clear", {
      method: "POST",
      body: JSON.stringify(identity),
    });
    appendDebugLog("CLEAR_RESPONSE", submit);
    const opId = submit.op_id;
    if (!opId) throw new Error("未拿到任务ID");

    setStatus("设备执行中，请稍候...", "info");
    const result = await waitForDeviceOpResult(opId);
    if (backupCloudInfoEl) {
      backupCloudInfoEl.textContent = JSON.stringify(result, null, 2);
    }
    updateTaskStatusFromDeviceResult(result, "单独清理 TikTok 数据已完成");
    if (result?.status === "success") {
      setStatus("TikTok 数据已清理", "success");
    } else {
      setStatus(result?.message || "清理失败", "error");
    }
  } catch (error) {
    appendDebugLog("CLEAR_ERROR", { message: error.message || String(error) });
    setStatus(error.message || "清理失败", "error");
  }
}

async function backupAndClearTikTok() {
  appendDebugLog("CLICK", "备份并清理 TikTok");
  const identity = collectDeviceOpsIdentity();
  if (!identity) {
    appendDebugLog("VALIDATION_ERROR", { action: "backupAndClearTikTok", message: "missing device" });
    return;
  }
  const confirmed = confirmTikTokAction("备份并清理 TikTok", "先执行设备端备份，再执行 TikTok 应用数据清理。\n系统会保留云端备份记录，方便后续恢复。");
  if (!confirmed) {
    setStatus("已取消备份并清理", "warning");
    return;
  }
  try {
    setStatus("正在下发任务：备份→一键清理→云端记录...", "info");
    const usernameHint = (backupUsernameInput?.value || "").trim().replace(/^@/, "")
      || (getBackupSelectionRow()?.username || "").trim().replace(/^@/, "");
    const payload = { ...identity };
    if (usernameHint) payload.username = usernameHint;
    appendDebugLog("REQUEST_PAYLOAD", { url: "/api/admin/device_tiktok/backup_and_clear", payload });
    const submit = await request("/api/admin/device_tiktok/backup_and_clear", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    appendDebugLog("BACKUP_CLEAR_RESPONSE", submit);
    const opId = submit.op_id;
    if (!opId) throw new Error("未拿到任务ID");

    setStatus("设备执行中，请稍候...", "info");
    const result = await waitForDeviceOpResult(opId);
    if (backupCloudInfoEl) {
      backupCloudInfoEl.textContent = JSON.stringify(result, null, 2);
    }
    updateTaskStatusFromDeviceResult(result, "备份并一键清理已完成");
    await refreshTikTokBackups();
    if (result?.status === "success") {
      setStatus("备份并一键清理完成（已记录云端包路径与名称）", "success");
    } else {
      setStatus(result?.message || "备份并清理失败", "error");
    }
  } catch (error) {
    appendDebugLog("BACKUP_CLEAR_ERROR", { message: error.message || String(error) });
    setStatus(error.message || "备份并清理失败", "error");
  }
}

async function restoreTikTokFromBackup() {
  appendDebugLog("CLICK", "执行恢复");
  const identity = collectDeviceOpsIdentity();
  if (!identity) {
    appendDebugLog("VALIDATION_ERROR", { action: "restoreTikTokFromBackup", message: "missing device" });
    return;
  }
  const selectedRow = getBackupSelectionRow();
  const backup_name = (restoreBackupSelect?.value || selectedRow?.backup_name || "").trim();
  const backup_path = (selectedRow?.backup_path || "").trim();
  if (!backup_name) {
    setStatus("请先选择一个可恢复备份", "warning");
    appendDebugLog("VALIDATION_ERROR", { action: "restoreTikTokFromBackup", message: "missing backup_name" });
    return;
  }
  const confirmed = confirmTikTokAction("恢复 TikTok", `将恢复备份：${backup_name}\n${backup_path ? `路径：${backup_path}\n` : ""}恢复会覆盖设备当前 TikTok 数据状态。`);
  if (!confirmed) return;

  try {
    setStatus("正在下发 TikTok 数据恢复任务...", "info");
    appendDebugLog("REQUEST_PAYLOAD", { url: "/api/admin/device_tiktok/restore", payload: { ...identity, backup_name } });
    const submit = await request("/api/admin/device_tiktok/restore", {
      method: "POST",
      body: JSON.stringify({ ...identity, backup_name }),
    });
    appendDebugLog("RESTORE_RESPONSE", submit);
    const opId = submit.op_id;
    if (!opId) throw new Error("未拿到任务ID");
    if (submit.backup_path) {
      setStatus(`恢复路径已锁定：${submit.backup_path}`, "info");
    }

    setStatus("设备恢复执行中，请稍候...", "info");
    const result = await waitForDeviceOpResult(opId);

    if (backupCloudInfoEl) {
      backupCloudInfoEl.textContent = JSON.stringify(result, null, 2);
    }
    updateTaskStatusFromDeviceResult(result, "TikTok 数据恢复已完成");
    if (result?.status === "success") {
      setStatus("TikTok 数据恢复成功", "success");
    } else {
      setStatus(result?.message || "恢复失败", "error");
    }
  } catch (error) {
    appendDebugLog("RESTORE_ERROR", { message: error.message || String(error) });
    setStatus(error.message || "恢复失败", "error");
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
  const data = await request("/api/auth/me");
  currentUser = data.user;
  authShell?.classList.add("hidden");
  appShell?.classList.remove("hidden");
  applyRolePermissions();
  await loadUserRuntimeConfig();
  return true;
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
  renderAdminUsers(Array.isArray(data.items) ? data.items : []);
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
  const data = await request("/api/auth/login", { method: "POST", body: JSON.stringify({ username, password }) });
  setAuthToken(data.token);
  currentUser = { username: data.username, role: data.role };
  authShell?.classList.add("hidden");
  appShell?.classList.remove("hidden");
  applyRolePermissions();
  await loadUserRuntimeConfig();
  if (authMessageEl) authMessageEl.textContent = `欢迎，${data.username}`;
  await refreshBackupRecords();
  await refreshTikTokBackups();
  await loadAdminUsers();
}

refreshAllBtn.addEventListener("click", async () => {
  await handleRefresh();
  await loadAccounts();
});
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
uploadBtn.addEventListener("click", uploadUsernames);
saveConfigBtn.addEventListener("click", saveConfig);
selectAllCheckbox.addEventListener("change", syncSelectAll);
startSelectedBtn.addEventListener("click", () => startDevices(selectedDeviceIds()));
startAllBtn.addEventListener("click", () => {
  const allDevices = Object.keys(latestDevices || {});
  startDevices(allDevices);
});
stopSelectedBtn.addEventListener("click", () => stopDevices(selectedDeviceIdsByStatus(["running", "error", "paused"])));
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
if (triggerCollectRootsExcelBtn) {
  triggerCollectRootsExcelBtn.addEventListener("click", () => triggerTask("/api/admin/tasks/trigger_collect_roots_excel", "已触发：Excel根用户抓取"));
}
if (stopCollectRootsExcelBtn) {
  stopCollectRootsExcelBtn.addEventListener("click", () => stopTaskByPrefix("collect_roots_excel", "已停止 Excel 抓取任务"));
}
if (triggerCollectRootsDbBtn) {
  triggerCollectRootsDbBtn.addEventListener("click", () => triggerTask("/api/admin/tasks/trigger_collect_roots_db", "已触发：随机抽取一批（来自 tiktok_roots）"));
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
      await refreshTaskStatus();
      if (data.workers) {
        setStatus(`已触发：follower_queue处理（workers=${data.workers}）`, "success");
      } else {
        setStatus(`已触发：follower_queue处理（pid=${data.pid ?? "-"}）`, "success");
      }
    } catch (error) {
      setStatus(error.message || "任务触发失败", "error");
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
if (submitBackupRecordBtn) {
  submitBackupRecordBtn.addEventListener("click", submitBackupRecord);
}
if (queryBackupRecordsBtn) {
  queryBackupRecordsBtn.addEventListener("click", refreshBackupRecords);
}
if (queryAndFillBackupBtn) {
  queryAndFillBackupBtn.addEventListener("click", async () => {
    const username = (backupUsernameInput?.value || "").trim();
    if (!username) {
      setStatus("请先输入用户名", "warning");
      return;
    }
    try {
      setStatus("正在查询并回填 TikTok 统计数据...", "info");
      const data = await request(`/api/admin/tiktok_backup/fill?username=${encodeURIComponent(username)}`, { method: "POST" });
      if (backupVideoCountInput && data.video_count !== undefined) backupVideoCountInput.value = data.video_count ?? "";
      if (backupFollowerCountInput && data.follower_count !== undefined) backupFollowerCountInput.value = data.follower_count ?? "";
      if (backupNewFollowerCountInput && data.new_follower_count !== undefined) backupNewFollowerCountInput.value = data.new_follower_count ?? "";
      await refreshBackupRecords();
      if (backupRecordHintEl) backupRecordHintEl.textContent = `查询完成：${data.msg || "已回填"}`;
      setStatus("查询并回填完成", "success");
    } catch (error) {
      setStatus(error.message || "查询并回填失败", "error");
    }
  });
}
if (resetBackupQueryBtn) {
  resetBackupQueryBtn.addEventListener("click", async () => {
    if (backupSearchUsernameInput) backupSearchUsernameInput.value = "";
    if (deviceAliasSelect) deviceAliasSelect.value = "";
    await refreshBackupRecords();
    setStatus("备份档案筛选已重置", "success");
  });
}
if (backupSearchUsernameInput) {
  backupSearchUsernameInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      refreshBackupRecords();
    }
  });
}
if (deviceAliasSelect) {
  deviceAliasSelect.addEventListener("change", () => refreshBackupRecords());
}
if (deviceLogDeviceSelect) {
  deviceLogDeviceSelect.addEventListener("change", () => {
    renderDeviceExecLogs(latestDeviceLogs || {});
  });
}
if (backupRecordTableBodyEl) {
  backupRecordTableBodyEl.addEventListener("click", (event) => {
    const row = event.target.closest("tr[data-backup-username]");
    if (!row) return;
    backupRecordTableBodyEl.querySelectorAll("tr.selected").forEach((el) => el.classList.remove("selected"));
    row.classList.add("selected");
    syncBackupFormFromSelection({
      username: row.dataset.backupUsername || "",
      tiktok_password: row.dataset.backupTiktokPassword || "",
      mobile_alias: row.dataset.backupMobileAlias || "",
    });
    if (restoreBackupSelect) restoreBackupSelect.value = row.dataset.backupName || "";
    setStatus(`已选中档案：${row.dataset.backupUsername || ""} / ${row.dataset.backupMobileAlias || ""}`, "info");
  });
}
if (clearTikTokBtn) {
  clearTikTokBtn.addEventListener("click", clearTikTokData);
}
if (backupClearTikTokBtn) {
  backupClearTikTokBtn.addEventListener("click", backupAndClearTikTok);
}
if (refreshBackupsBtn) {
  refreshBackupsBtn.addEventListener("click", async () => {
    appendDebugLog("CLICK", "刷新可恢复备份");
    await refreshTikTokBackups();
    await refreshBackupRecords();
  });
}
if (restoreTikTokBtn) {
  restoreTikTokBtn.addEventListener("click", restoreTikTokFromBackup);
}
if (restoreTikTokExecBtn) {
  restoreTikTokExecBtn.addEventListener("click", restoreTikTokFromBackup);
}
if (forceStopTikTokBtn1) {
  forceStopTikTokBtn1.addEventListener("click", (event) => {
    event.preventDefault();
    runTikTokDeviceOp("/api/admin/device_tiktok/force_stop_1", "TikTok 已强制关闭（方案1）");
  });
}
if (forceStopTikTokBtn2) {
  forceStopTikTokBtn2.addEventListener("click", (event) => {
    event.preventDefault();
    runTikTokDeviceOp("/api/admin/device_tiktok/force_stop_2", "TikTok 已强制关闭（方案2）");
  });
}
if (forceStopTikTokBtn3) {
  forceStopTikTokBtn3.addEventListener("click", (event) => {
    event.preventDefault();
    runTikTokDeviceOp("/api/admin/device_tiktok/force_stop_3", "TikTok 已强制关闭（方案3）");
  });
}
if (launchTikTokBtn1) {
  launchTikTokBtn1.addEventListener("click", (event) => {
    event.preventDefault();
    runTikTokDeviceOp("/api/admin/device_tiktok/launch_1", "TikTok 已拉起（方案1）");
  });
}
if (launchTikTokBtn2) {
  launchTikTokBtn2.addEventListener("click", (event) => {
    event.preventDefault();
    runTikTokDeviceOp("/api/admin/device_tiktok/launch_2", "TikTok 已拉起（方案2）");
  });
}
if (launchTikTokBtn3) {
  launchTikTokBtn3.addEventListener("click", (event) => {
    event.preventDefault();
    runTikTokDeviceOp("/api/admin/device_tiktok/launch_3", "TikTok 已拉起（方案3）");
  });
}

function toggleDeviceOpsButtons() {
  const hasDevice = Boolean((deviceOpsSelect?.value || "").trim());
  [
    backupClearTikTokBtn,
    restoreTikTokBtn,
    restoreTikTokExecBtn,
    forceStopTikTokBtn1,
    forceStopTikTokBtn2,
    forceStopTikTokBtn3,
    launchTikTokBtn1,
    launchTikTokBtn2,
    launchTikTokBtn3,
  ].forEach((btn) => {
    if (btn) btn.disabled = !hasDevice;
  });
  if (clearTikTokBtn) clearTikTokBtn.disabled = false;
}

if (deviceOpsSelect) {
  deviceOpsSelect.addEventListener("change", toggleDeviceOpsButtons);
}

if (authLoginBtn) authLoginBtn.addEventListener("click", () => doLogin().catch((e) => { if (authMessageEl) authMessageEl.textContent = e.message || "登录失败"; }));
if (logoutBtn) logoutBtn.addEventListener("click", () => { setAuthToken(""); currentUser = null; appShell?.classList.add("hidden"); authShell?.classList.remove("hidden"); if (authMessageEl) authMessageEl.textContent = "请先登录"; });
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

(async () => {
  const ok = await authCheck().catch(() => false);
  if (ok) {
    handleRefresh();
    loadAccounts();
    loadDbTablePage();
    loadAdminUsers();
    refreshBackupRecords();
    refreshTikTokBackups();
    setInterval(refreshTaskStatus, 2000);
    refreshTaskStatus();
    setInterval(pollDeviceLogsSnapshot, 4000);
    pollDeviceLogsSnapshot();
  } else {
    authShell?.classList.remove("hidden");
    appShell?.classList.add("hidden");
  }
})();
