window.__AUTO_TRACES_WEB_ADMIN_BUILD__ = "20260703-1";

{

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
const exportMobilePendingUsernamesBtn = document.getElementById("exportMobilePendingUsernames");
const exportAndClearMobilePendingUsernamesBtn = document.getElementById("exportAndClearMobilePendingUsernames");
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
const mutualStartSelectedBtn = document.getElementById("mutualStartSelectedBtn");
const mutualFollowOnlySelectedBtn = document.getElementById("mutualFollowOnlySelectedBtn");
const publishPostStartSelectedBtn = document.getElementById("publishPostStartSelectedBtn");
const aliasFilterInput = document.getElementById("aliasFilter");
const deviceGroupSelect = document.getElementById("deviceGroupSelect");
const exportBackupArchiveXlsxBtn = document.getElementById("exportBackupArchiveXlsxBtn");
const exportBackupArchiveSelectedXlsxBtn = document.getElementById("exportBackupArchiveSelectedXlsxBtn");
const backupArchiveSelectAllPageBtn = document.getElementById("backupArchiveSelectAllPageBtn");
const backupArchiveClearSelectionBtn = document.getElementById("backupArchiveClearSelectionBtn");
const backupArchiveSelectionHintEl = document.getElementById("backupArchiveSelectionHint");
const backupArchiveSelectedCountEl = document.getElementById("backupArchiveSelectedCount");
const importBackupArchiveXlsxBtn = document.getElementById("importBackupArchiveXlsxBtn");
const backupArchiveImportFileInput = document.getElementById("backupArchiveImportFileInput");
const backupArchiveSelectedRowIndices = new Set();
let backupArchiveXlsxBusy = false;
const groupMoveModalEl = document.getElementById("groupMoveModal");
const groupMoveSelectedAliasesEl = document.getElementById("groupMoveSelectedAliases");
const groupMoveSelectEl = document.getElementById("groupMoveSelect");
const groupManageMessageEl = document.getElementById("groupManageMessage");
const groupManagerSummaryEl = document.getElementById("groupManagerSummary");
const groupManagerListEl = document.getElementById("groupManagerList");
const manageDeviceGroupsBtn = document.getElementById("manageDeviceGroupsBtn");
const closeGroupMoveModalBtn = document.getElementById("closeGroupMoveModalBtn");
const confirmGroupMoveBtn = document.getElementById("confirmGroupMoveBtn");
const addGroupBtn = document.getElementById("addGroupBtn");
const deleteGroupBtn = document.getElementById("deleteGroupBtn");
const newGroupNameInput = document.getElementById("newGroupNameInput");
const overviewPrevPageBtn = document.getElementById("overviewPrevPageBtn");
const overviewNextPageBtn = document.getElementById("overviewNextPageBtn");
const overviewPageInfoEl = document.getElementById("overviewPageInfo");
const overviewPageSizeInput = document.getElementById("overviewPageSizeInput");
const pcServiceUrlInput = document.getElementById("pcServiceUrlInput");
const pcServiceApplyBtn = document.getElementById("pcServiceApplyBtn");
const pcServiceSelectPageBtn = document.getElementById("pcServiceSelectPageBtn");
const pcServiceClearSelectionBtn = document.getElementById("pcServiceClearSelectionBtn");
const pcServiceRefreshBtn = document.getElementById("pcServiceRefreshBtn");
const pcServiceSelectionHint = document.getElementById("pcServiceSelectionHint");
const pcServiceAliasFilter = document.getElementById("pcServiceAliasFilter");
const pcServiceGroupSelect = document.getElementById("pcServiceGroupSelect");
const pcServiceTableBody = document.getElementById("pcServiceTableBody");
const pcServicePrevPageBtn = document.getElementById("pcServicePrevPageBtn");
const pcServiceNextPageBtn = document.getElementById("pcServiceNextPageBtn");
const pcServicePageInfoEl = document.getElementById("pcServicePageInfo");
const pcServicePageSizeInput = document.getElementById("pcServicePageSizeInput");
const pcServiceResultLog = document.getElementById("pcServiceResultLog");
const backupArchivePageSizeInput = document.getElementById("backupArchivePageSizeInput");
const backupArchivePrevPageBtn = document.getElementById("backupArchivePrevPageBtn");
const backupArchiveNextPageBtn = document.getElementById("backupArchiveNextPageBtn");
const deviceAliasGroupsPagerEl = document.getElementById("deviceAliasGroupsPager");
const startHintEl = document.getElementById("startHint");

const onlineCountEl = document.getElementById("onlineCount");
const runningCountEl = document.getElementById("runningCount");
const errorCountEl = document.getElementById("errorCount");
const pendingCountEl = document.getElementById("pendingCount");
const followerPendingEl = document.getElementById("followerPending");
const mobilePendingEl = document.getElementById("mobilePending");
const mobilePendingLogsEl = document.getElementById("mobilePendingLogs");
let lastMobilePendingLogLine = "";
let mobilePendingExportInFlight = false;

const deviceTable = document.getElementById("deviceTable");

/** 云端「绑定设备自行拉起程序」开关，由 overview 同步 */
let adminAutoStartEnabled = false;

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
const tiktokRootsDedupOnlyInput = document.getElementById("tiktokRootsDedupOnly");
const rootTypeInput = document.getElementById("rootTypeInput");
const triggerCollectRootsExcelBtn = document.getElementById("triggerCollectRootsExcel");
const stopCollectRootsExcelBtn = document.getElementById("stopCollectRootsExcel");
const triggerCollectRootsDbBtn = document.getElementById("triggerCollectRootsDb");
const stopCollectRootsDbBtn = document.getElementById("stopCollectRootsDb");
const triggerProcessQueueBtn = document.getElementById("triggerProcessQueue");
const stopProcessQueueInlineBtn = document.getElementById("stopProcessQueueInline");
const triggerAutoSearchBtn = document.getElementById("triggerAutoSearch");
const schedulerEnabledToggle = document.getElementById("schedulerEnabledToggle");
const saveSchedulerConfigBtn = document.getElementById("saveSchedulerConfig");
const startSchedulersBtn = document.getElementById("startSchedulers");
const stopSchedulersBtn = document.getElementById("stopSchedulers");
const taskStatusEl = document.getElementById("taskStatus");
const schedulerTaskSettingsEl = document.getElementById("schedulerTaskSettings");
const schedulerTaskLogsEl = document.getElementById("schedulerTaskLogs");
const schedulerLogsFollowToggle = document.getElementById("schedulerLogsFollow");
const schedulerLogsScrollBottomBtn = document.getElementById("schedulerLogsScrollBottom");
const autoSwitchSelectAllEnableBtn = document.getElementById("autoSwitchSelectAllEnableBtn");
const autoSwitchEnableSelectedBtn = document.getElementById("autoSwitchEnableSelectedBtn");
const autoSwitchDisableSelectedBtn = document.getElementById("autoSwitchDisableSelectedBtn");
const autoSwitchCutSchemeSelect = document.getElementById("autoSwitchCutSchemeSelect");
const autoSwitchExcludedBatchBtn = document.getElementById("autoSwitchExcludedBatchBtn");
const autoSwitchExcludedBatchModalEl = document.getElementById("autoSwitchExcludedBatchModal");
const autoSwitchExcludedBatchTextarea = document.getElementById("autoSwitchExcludedBatchTextarea");
const autoSwitchExcludedBatchMessageEl = document.getElementById("autoSwitchExcludedBatchMessage");
const closeAutoSwitchExcludedBatchBtn = document.getElementById("closeAutoSwitchExcludedBatchBtn");
const cancelAutoSwitchExcludedBatchBtn = document.getElementById("cancelAutoSwitchExcludedBatchBtn");
const autoSwitchExcludedBatchExcludeBtn = document.getElementById("autoSwitchExcludedBatchExcludeBtn");
const autoSwitchExcludedBatchJoinBtn = document.getElementById("autoSwitchExcludedBatchJoinBtn");
const autoSwitchEnabledCountHint = document.getElementById("autoSwitchEnabledCountHint");
const autoSwitchSessionSummariesEl = document.getElementById("autoSwitchSessionSummariesEl");
const autoSwitchSessionSummariesHint = document.getElementById("autoSwitchSessionSummariesHint");
const autoSwitchSessionSummariesAliasSelect = document.getElementById("autoSwitchSessionSummariesAliasSelect");
const autoSwitchSessionSummariesRefreshBtn = document.getElementById("autoSwitchSessionSummariesRefreshBtn");
const backupStatsFullQueryEnabledToggle = document.getElementById("backupStatsFullQueryEnabledToggle");
const LOG_SCROLL_THRESHOLD = 48;
const taskContainerIdsInput = document.getElementById("taskContainerIds");
const taskParallelInput = document.getElementById("taskParallel");
const collectRootsDbPrioritySelect = document.getElementById("collectRootsDbPriority");
const dbTableSelect = document.getElementById("dbTableSelect");
const dbSearchFieldSelect = document.getElementById("dbSearchField");
const dbSearchKeywordInput = document.getElementById("dbSearchKeyword");
const dbSearchBtn = document.getElementById("dbSearchBtn");
const dbClearSearchBtn = document.getElementById("dbClearSearchBtn");
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
const statsIncludeRawInput = document.getElementById("statsIncludeRaw");
const fetchTiktokStatsBtn = document.getElementById("fetchTiktokStats");
const fetchTiktokStatsFullBtn = document.getElementById("fetchTiktokStatsFull");
const statsResultEl = document.getElementById("statsResult");
const statsXlsxFileInput = document.getElementById("statsXlsxFile");
const statsXlsxHintEl = document.getElementById("statsXlsxHint");
const fetchTiktokStatsXlsxBtn = document.getElementById("fetchTiktokStatsXlsx");
const downloadTiktokStatsXlsxBtn = document.getElementById("downloadTiktokStatsXlsx");
const cvAccessFilterEl = document.getElementById("cvAccessFilter");
const queryCvAccessDataBtn = document.getElementById("queryCvAccessData");
const triggerProcessQueueInlineBtn = document.getElementById("triggerProcessQueueInline");
const preflightFollowerQueueBtn = document.getElementById("preflightFollowerQueueBtn");
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
const backupPackageNameInput = document.getElementById("backupPackageNameInput");
const galleryTargetAliasesSelect = document.getElementById("galleryTargetAliasesSelect");
const galleryImageFilesInput = document.getElementById("galleryImageFilesInput");
const uploadGalleryBtn = document.getElementById("uploadGalleryBtn");
const galleryDispatchLogsEl = document.getElementById("galleryDispatchLogs");
const galleryLibraryGridEl = document.getElementById("galleryLibraryGrid");
const galleryLibraryCountEl = document.getElementById("galleryLibraryCount");
const galleryLibrarySelectedCountEl = document.getElementById("galleryLibrarySelectedCount");
const galleryLibraryUploadInput = document.getElementById("galleryLibraryUploadInput");
const galleryLibraryUploadBtn = document.getElementById("galleryLibraryUploadBtn");
const galleryLibraryRefreshBtn = document.getElementById("galleryLibraryRefreshBtn");
const galleryLibrarySelectAllBtn = document.getElementById("galleryLibrarySelectAllBtn");
const galleryLibraryClearSelectionBtn = document.getElementById("galleryLibraryClearSelectionBtn");
const failureReportsCountEl = document.getElementById("failureReportsCount");
const failureReportsOwnerFilterEl = document.getElementById("failureReportsOwnerFilter");
const failureReportsAliasFilterEl = document.getElementById("failureReportsAliasFilter");
const failureReportsErrorFilterEl = document.getElementById("failureReportsErrorFilter");
const failureReportsRefreshBtn = document.getElementById("failureReportsRefreshBtn");
const failureReportsTableBodyEl = document.getElementById("failureReportsTableBody");
const GALLERY_LIBRARY_MAX_ITEMS = 100;
// 单次 multipart 体积上限（需小于 Nginx client_max_body_size；未调大时默认约 1m）
const GALLERY_UPLOAD_BATCH_MAX_BYTES = 8 * 1024 * 1024;
let galleryLibraryItems = [];
const galleryLibrarySelectedIds = new Set();
const galleryLibraryThumbUrls = new Map();
const deviceLogDeviceSelect = document.getElementById("deviceLogDeviceSelect");
const deviceLogPullBtn = document.getElementById("deviceLogPullBtn");
const deviceExecLogsEl = document.getElementById("deviceExecLogs");
const publishPostBatchLogsEl = document.getElementById("publishPostBatchLogs");
const backupCloudInfoEl = document.getElementById("backupCloudInfo");
const addBlankRecordBtn = document.getElementById("addBlankRecordBtn");
const addDeviceBlankRecordBtn = document.getElementById("addDeviceBlankRecordBtn");
const deviceAssignCountInput = document.getElementById("deviceAssignCountInput");
const accountBatchInputEl = document.getElementById("accountBatchInput");
const accountBatchRegionInputEl = document.getElementById("accountBatchRegionInput");
const accountBatchRejectEmptyToggleEl = document.getElementById("accountBatchRejectEmptyToggle");
const importAccountBatchBtn = document.getElementById("importAccountBatchBtn");
const accountBatchPreviewModalEl = document.getElementById("accountBatchPreviewModal");
const closeAccountBatchPreviewBtn = document.getElementById("closeAccountBatchPreviewBtn");
const cancelAccountBatchPreviewBtn = document.getElementById("cancelAccountBatchPreviewBtn");
const confirmAccountBatchPreviewBtn = document.getElementById("confirmAccountBatchPreviewBtn");
const accountBatchPreviewSelectAllEl = document.getElementById("accountBatchPreviewSelectAll");
const accountBatchPreviewBulkAliasEl = document.getElementById("accountBatchPreviewBulkAlias");
const accountBatchPreviewApplyAliasBtn = document.getElementById("accountBatchPreviewApplyAliasBtn");
const accountBatchPreviewSmartAliasToggleBtn = document.getElementById("accountBatchPreviewSmartAliasToggleBtn");
const accountBatchPreviewSmartAliasPanelEl = document.getElementById("accountBatchPreviewSmartAliasPanel");
const accountBatchPreviewSmartAliasListEl = document.getElementById("accountBatchPreviewSmartAliasList");
const accountBatchPreviewSmartAliasSelectAllBtn = document.getElementById("accountBatchPreviewSmartAliasSelectAllBtn");
const accountBatchPreviewSmartAliasClearBtn = document.getElementById("accountBatchPreviewSmartAliasClearBtn");
const accountBatchPreviewSmartDistributeBtn = document.getElementById("accountBatchPreviewSmartDistributeBtn");
const accountBatchPreviewSummaryEl = document.getElementById("accountBatchPreviewSummary");
const accountBatchPreviewTableWrapEl = document.getElementById("accountBatchPreviewTableWrap");
const accountStatsQueryBtn = document.getElementById("accountStatsQueryBtn");
const accountStatsQueryScheduleToggle = document.getElementById("accountStatsQueryScheduleToggle");
const accountStatsQueryScheduleTime = document.getElementById("accountStatsQueryScheduleTime");
const accountStatsQueryLogDownloadBtn = document.getElementById("accountStatsQueryLogDownloadBtn");
const accountStatsQueryModalEl = document.getElementById("accountStatsQueryModal");
const closeAccountStatsQueryModalBtn = document.getElementById("closeAccountStatsQueryModalBtn");
const cancelAccountStatsQueryModalBtn = document.getElementById("cancelAccountStatsQueryModalBtn");
const confirmAccountStatsQueryBtn = document.getElementById("confirmAccountStatsQueryBtn");
const accountStatsQuerySelectAllEl = document.getElementById("accountStatsQuerySelectAll");
const accountStatsQuerySummaryEl = document.getElementById("accountStatsQuerySummary");
const accountStatsQueryAliasListEl = document.getElementById("accountStatsQueryAliasList");
const batchStreamAliasSelect = document.getElementById("batchStreamAliasSelect");
const batchStreamHoursInput = document.getElementById("batchStreamHoursInput");
const batchStreamRoundsInput = document.getElementById("batchStreamRoundsInput");
const batchStreamFileInput = document.getElementById("batchStreamFileInput");
const batchStreamUploadHint = document.getElementById("batchStreamUploadHint");
const batchStreamStartBtn = document.getElementById("batchStreamStartBtn");
const batchStreamStopBtn = document.getElementById("batchStreamStopBtn");
const batchStreamDownloadBtn = document.getElementById("batchStreamDownloadBtn");
const batchStreamStatusHint = document.getElementById("batchStreamStatusHint");
const batchStreamProgressCard = document.getElementById("batchStreamProgressCard");
const batchStreamProgressSummary = document.getElementById("batchStreamProgressSummary");
const batchStreamProgressBarWrap = document.getElementById("batchStreamProgressBarWrap");
const batchStreamProgressBar = document.getElementById("batchStreamProgressBar");
const batchStreamProgressPercent = document.getElementById("batchStreamProgressPercent");
const batchStreamTableBody = document.getElementById("batchStreamTableBody");
const accountUnassignedOnlyToggle = document.getElementById("accountUnassignedOnlyToggle");
const accountRecordsSearchInput = document.getElementById("accountRecordsSearchInput");
const accountRecordsSearchFieldSelect = document.getElementById("accountRecordsSearchFieldSelect");
const accountRecordsPrevPageBtn = document.getElementById("accountRecordsPrevPageBtn");
const accountRecordsNextPageBtn = document.getElementById("accountRecordsNextPageBtn");
const accountRecordsPagerEl = document.getElementById("accountRecordsPager");
const accountRecordsPageSizeInput = document.getElementById("accountRecordsPageSizeInput");
const accountRecordsBatchDeleteBtn = document.getElementById("accountRecordsBatchDeleteBtn");
const accountRecordsBatchProfileBtn = document.getElementById("accountRecordsBatchProfileBtn");
const accountRecordsBatchProfileSelect = document.getElementById("accountRecordsBatchProfileSelect");
const accountRecordsToggleCredColsBtn = document.getElementById("accountRecordsToggleCredColsBtn");
const backupArchiveSearchInput = document.getElementById("backupArchiveSearchInput");
const copyCurrentRecordBtn = document.getElementById("copyCurrentRecordBtn");
const ACCOUNT_EDITOR_STORAGE_KEY = "auto_traces_account_editor_rows_v1";
const ACCOUNT_EDITOR_CREDS_EXPANDED_LS_KEY = "auto_traces_account_editor_creds_expanded_v1";
const DEVICE_EDITOR_STORAGE_KEY = "auto_traces_device_editor_rows_v1";
const ACCOUNT_ROWS_API = "/api/admin/editor/account_rows";
const DEVICE_ROWS_API = "/api/admin/editor/device_rows";

let accountEditorRows = [];
let deviceEditorRows = [];
let backupArchiveRows = [];
/** 当前备份档案分页接口返回的原始行，用于把勾选 row_index 解析为 device_id。 */
let backupArchivePageItems = [];
let backupAliasByUsername = new Map();
let accountFilterUnassignedOnly = false;
let batchStreamSessionId = "";
let batchStreamBusy = false;
let batchStreamLastSnapshot = "";
let statsXlsxBatchToken = 0;
let statsXlsxBusy = false;
let statsXlsxSessionId = "";
let statsXlsxDownloadName = "";
let accountRecordsSearchQuery = "";
let accountRecordsSearchField = "";
let currentAccountRecordsPage = 1;
let accountRecordsFilteredTotal = 0;
/** 账号记录批量删除勾选，键为数据库 row_index */
const accountRecordsSelectedRowIndexes = new Set();
/** 是否展开 password / email / email_pwd / 2FA / api 凭据列（默认收起以留出可视空间） */
let accountRecordsCredColsExpanded = loadAccountRecordsCredColsExpanded();
const ACCOUNT_RECORDS_BATCH_DELETE_MAX = 100;
const ACCOUNT_RECORDS_BATCH_SET_BASIC_PROFILE_MAX = 100;
let backupArchiveSearchQuery = "";
let deviceGroupNames = [];
let customDeviceGroupNames = [];
let currentDeviceGroupFilter = "";
let currentOverviewPage = 1;
let overviewDevicesTotal = 0;
let pcServiceDevices = {};
let pcServiceDevicesTotal = 0;
let currentPcServicePage = 1;
let pcServiceAliasFilterText = "";
let currentBackupArchivePage = 1;
let backupArchiveAliasTotal = 0;
const DEFAULT_OVERVIEW_PAGE_SIZE = 20;
const DEFAULT_BACKUP_ARCHIVE_PAGE_SIZE = 20;
const DEFAULT_ACCOUNT_RECORDS_PAGE_SIZE = 50;
const ACCOUNT_RECORDS_CRED_COLS_STORAGE_KEY = "accountRecordsCredColsExpanded";
const MAX_OVERVIEW_PAGE_SIZE = 500;
const MAX_BACKUP_ARCHIVE_PAGE_SIZE = 200;
const MAX_ACCOUNT_RECORDS_PAGE_SIZE = 200;
const DEFAULT_DEVICE_ASSIGN_COUNT = 1;
const MAX_DEVICE_ASSIGN_COUNT = 50;

const COMMENT_TEXTS_LS_KEY = "auto_traces_admin_comment_texts_v1";
const MUTUAL_COMMENT_TEXTS_LS_KEY = "auto_traces_admin_mutual_comment_texts_v1";
const MUTUAL_TRAFFIC_TARGET_TEXTS_LS_KEY = "auto_traces_admin_mutual_traffic_target_texts_v1";
const PROFILE_EDIT_BIO_TEXTS_LS_KEY = "auto_traces_admin_profile_edit_bio_texts_v1";
const API_BASE_LS_KEY = "auto_traces_api_base_v1";

let latestDevices = {};
let latestDeviceLogs = {};
/** 全量已绑定设备别名（来自 /api/admin/devices/aliases，不受概览分页影响） */
let boundDeviceAliasPairs = [];
let boundDeviceAliasesSignature = "";
let boundDeviceAliasesSyncInFlight = false;
let runtimeApiBaseOverride = "";
let currentRunMode = "growth";
let currentConfigTemplate = "default";
let currentTemplateMeta = {};
let currentTemplatesByName = {};
let aliasTemplateByMode = { growth: {}, live: {}, direct: {}, mutual: {} };
let smartTemplateConfig = {
  old_branch_min_followers: 100,
  old_branch_min_video_count: 15,
  new_branch_templates: [{ template_name: "default", weight: 100 }],
  old_branch_templates: [{ template_name: "default", weight: 100 }],
};
let aliasSmartTemplateByMode = { growth: {}, live: {}, direct: {}, mutual: {} };
let smartTemplatePreviewCache = {};

/** 智能模板暂仅涨粉模式生效（与服务端 SMART_TEMPLATE_RUN_MODES 一致） */
const SMART_TEMPLATE_MODE = "growth";

const smartOldMinFollowersInput = document.getElementById("smartOldMinFollowers");
const smartOldMinVideosInput = document.getElementById("smartOldMinVideos");
const smartNewBranchRowsEl = document.getElementById("smartNewBranchRows");
const smartOldBranchRowsEl = document.getElementById("smartOldBranchRows");
const smartAddNewBranchRowBtn = document.getElementById("smartAddNewBranchRow");
const smartAddOldBranchRowBtn = document.getElementById("smartAddOldBranchRow");
const saveSmartTemplateConfigBtn = document.getElementById("saveSmartTemplateConfigBtn");
const reloadSmartTemplateConfigBtn = document.getElementById("reloadSmartTemplateConfigBtn");
const smartTemplateConfigStatusEl = document.getElementById("smartTemplateConfigStatus");

function smartPreviewKey(owner, mode, alias) {
  const o = String(owner || "").trim();
  const m = String(mode || SMART_TEMPLATE_MODE).trim();
  const a = String(alias || "").trim().toLowerCase();
  return `${o}::${m}::${a}`;
}

function isSmartTemplateModeActive() {
  return currentRunMode === SMART_TEMPLATE_MODE;
}

function isAliasSmartEnabledForOwner(owner, alias) {
  const ownerKey = String(owner || currentUser?.username || "").trim();
  const aliasKey = String(alias || "").trim().toLowerCase();
  const cached = smartTemplatePreviewCache[smartPreviewKey(ownerKey, SMART_TEMPLATE_MODE, aliasKey)];
  if (cached && typeof cached.enabled === "boolean") return cached.enabled;
  if (ownerKey !== String(currentUser?.username || "").trim()) return false;
  const modeMap = aliasSmartTemplateByMode?.[SMART_TEMPLATE_MODE];
  return Boolean(modeMap && modeMap[aliasKey]);
}

function normalizeTiktokUsernameKey(raw) {
  return String(raw || "").trim().replace(/^@+/, "").toLowerCase();
}

/** 别名设备上某 TikTok 账号的配置映射键（与仅 alias 的设备级键并存）。 */
function accountTemplateMapKey(alias, tiktokUsername) {
  const aliasKey = String(alias || "").trim().toLowerCase();
  const userKey = normalizeTiktokUsernameKey(tiktokUsername);
  if (!aliasKey || !userKey) return aliasKey;
  return `${aliasKey}::${userKey}`;
}

function resolveBoundTemplateRef(alias, tiktokUsername, mode = currentRunMode) {
  const aliasKey = String(alias || "").trim().toLowerCase();
  const modeMap = aliasTemplateByMode?.[mode];
  if (!modeMap || typeof modeMap !== "object") return "";
  const accountKey = accountTemplateMapKey(aliasKey, tiktokUsername);
  if (accountKey && accountKey !== aliasKey) {
    const accountRef = String(modeMap[accountKey] || "").trim();
    if (accountRef) return accountRef;
  }
  return String(modeMap[aliasKey] || "").trim();
}

function templateKeyExists(ref) {
  const t = currentTemplatesByName || {};
  return Boolean(ref && t && typeof t === "object" && Object.prototype.hasOwnProperty.call(t, ref));
}

/** 与服务端 _lookup_mode_map（含 cand in templates）一致 */
function lookupAliasMapModeProbe(modeProbe, aliasClean, tiktokUsername) {
  const modeMap = aliasTemplateByMode?.[modeProbe];
  if (!modeMap || typeof modeMap !== "object") return "";
  const accountKey = accountTemplateMapKey(aliasClean, tiktokUsername);
  if (accountKey && accountKey !== aliasClean) {
    const cand = String(modeMap[accountKey] || "").trim();
    if (templateKeyExists(cand)) return cand;
  }
  return String(modeMap[aliasClean] || "").trim();
}

/** 设备行下拉回显应与服务端 _resolve_template_for_mode_alias 一致（含跨模式复用）。 */
function resolveEffectiveDeviceTemplateUI(aliasRaw, tiktokUsername = "") {
  const aliasClean = String(aliasRaw || "").trim().toLowerCase();
  const templateModeKey = currentRunMode === "mutual" ? "growth" : currentRunMode;

  let rawRef = "";
  let sourceProbe = "";
  let fromCross = false;
  let crossFromMode = "";

  if (currentRunMode === "mutual") {
    const cand = lookupAliasMapModeProbe("mutual", aliasClean, tiktokUsername);
    if (templateKeyExists(cand)) {
      rawRef = cand;
      sourceProbe = "mutual";
    }
  }

  if (!rawRef) {
    const cand = lookupAliasMapModeProbe(templateModeKey, aliasClean, tiktokUsername);
    if (templateKeyExists(cand)) {
      rawRef = cand;
      sourceProbe = templateModeKey;
    }
  }

  if (!rawRef && aliasClean) {
    const probeModes = [templateModeKey, "mutual", "growth", "live", "direct"];
    for (const probeMode of probeModes) {
      if (probeMode === templateModeKey) continue;
      const probeRef = lookupAliasMapModeProbe(probeMode, aliasClean, tiktokUsername);
      if (!probeRef || !templateKeyExists(probeRef)) continue;
      rawRef = probeRef;
      fromCross = true;
      crossFromMode = probeMode;
      break;
    }
  }

  const storedActive = String(currentConfigTemplate || "").trim();
  const keys = Object.keys(currentTemplatesByName || {});
  let fallbackName = "";
  if (templateKeyExists(storedActive)) {
    fallbackName = storedActive;
  } else if (keys.includes("default")) {
    fallbackName = "default";
  } else {
    fallbackName = keys[0] || "default";
  }

  const selectedTemplate = templateKeyExists(rawRef) ? rawRef : fallbackName;

  /** 本条映射是否写在本模式下（与服务端语义一致）；跨模式借用不算「本模式显式」 */
  let hasExplicitInCurrentMode =
    Boolean(rawRef && !fromCross && sourceProbe &&
      ((currentRunMode === "mutual" && sourceProbe === "mutual")
        || (currentRunMode !== "mutual" && sourceProbe === templateModeKey)));

  if (!aliasClean) {
    return {
      selectedTemplate,
      badgeText: "-",
      badgeClass: "pending",
      badgeHint: "",
      hasExplicitInCurrentMode: false,
    };
  }

  const modeLabel =
    ({ growth: "涨粉", live: "主播", direct: "直客", mutual: "互点" })[crossFromMode] || crossFromMode;
  let badgeText = "";
  let badgeClass = "pending";
  let badgeHint = "";
  if (!keys.length) {
    badgeText = "暂无可选模板";
    badgeClass = "offline";
  } else if (hasExplicitInCurrentMode) {
    badgeText = "已绑定(本模式)";
    badgeClass = "online";
    badgeHint = "该别名已在当前运行模式下保存设备级模板映射。";
  } else if (fromCross) {
    badgeText = crossFromMode ? `复用「${modeLabel}」映射` : "复用其它模式映射";
    badgeClass = "online";
    badgeHint =
      "当前模式下未单独保存该别名映射；后台会复用其它模式中已绑定的别名→模板键，且与设备端解析一致。";
  } else if (resolveBoundTemplateRef(aliasClean, tiktokUsername, currentRunMode)) {
    badgeText = "（映射已失效，沿用运行配置）";
    badgeClass = "pending";
    badgeHint =
      "仍保存了别名→模板键，但该模板暂不可用（如被删除或取消共享）。设备将回退到运行配置当前模板。";
  } else {
    badgeText = "继承运行配置";
    badgeClass = "pending";
    badgeHint = "未配置别名映射时，设备使用「运行配置」里当前选中的模板（active_template）。";
  }

  return { selectedTemplate, badgeText, badgeClass, badgeHint, hasExplicitInCurrentMode };
}

function applySmartTemplateConfigToForm(cfg) {
  const c = cfg && typeof cfg === "object" ? cfg : smartTemplateConfig;
  if (smartOldMinFollowersInput) {
    smartOldMinFollowersInput.value = String(c.old_branch_min_followers ?? 100);
  }
  if (smartOldMinVideosInput) {
    smartOldMinVideosInput.value = String(c.old_branch_min_video_count ?? 15);
  }
  renderSmartTemplateWeightRows("new", c.new_branch_templates);
  renderSmartTemplateWeightRows("old", c.old_branch_templates);
}

function renderSmartTemplateWeightRows(branch, entries) {
  const container = branch === "old" ? smartOldBranchRowsEl : smartNewBranchRowsEl;
  if (!container) return;
  const list = Array.isArray(entries) && entries.length
    ? entries
    : [{ template_name: "default", weight: 100 }];
  const templateNames = Object.keys(currentTemplatesByName || {}).sort((a, b) => a.localeCompare(b));
  if (!templateNames.length) templateNames.push("default");
  container.innerHTML = list.map((row, idx) => {
    const selected = String(row?.template_name || "default").trim() || "default";
    const weight = Number(row?.weight);
    const w = Number.isFinite(weight) ? Math.max(0, Math.floor(weight)) : 100;
    const options = templateNames.map((name) => (
      `<option value="${escapeHtml(name)}" ${name === selected ? "selected" : ""}>${escapeHtml(templateDisplayName(name, currentTemplateMeta))}</option>`
    )).join("");
    return `
      <div class="smart-template-weight-row" data-branch="${branch}" data-row-index="${idx}">
        <div class="field">
          <label>模板</label>
          <select class="ui-select smart-template-pick" data-branch="${branch}" data-row-index="${idx}">${options}</select>
        </div>
        <div class="field field--weight">
          <label>概率</label>
          <input class="smart-template-weight" type="number" min="0" max="10000" step="1" value="${w}" data-branch="${branch}" data-row-index="${idx}" title="同一分支内为相对概率，数值越大占比越高" />
        </div>
        <button type="button" class="ghost smart-template-remove-row" data-branch="${branch}" data-row-index="${idx}">删除</button>
      </div>
    `;
  }).join("");
}

function collectSmartTemplateWeightRows(branch) {
  const container = branch === "old" ? smartOldBranchRowsEl : smartNewBranchRowsEl;
  if (!container) return [];
  const rows = [];
  container.querySelectorAll(".smart-template-weight-row").forEach((rowEl) => {
    const pick = rowEl.querySelector(".smart-template-pick");
    const weightInput = rowEl.querySelector(".smart-template-weight");
    const template_name = String(pick?.value || "").trim();
    const weight = Math.max(0, Math.floor(Number(weightInput?.value) || 0));
    if (template_name) rows.push({ template_name, weight });
  });
  return rows;
}

function setSmartTemplateConfigStatus(text, tone = "info") {
  if (!smartTemplateConfigStatusEl) return;
  smartTemplateConfigStatusEl.textContent = text || "";
  smartTemplateConfigStatusEl.className = `hint${tone === "success" ? " status-success" : tone === "error" ? " status-error" : ""}`;
}

async function saveSmartTemplateConfig() {
  const payload = {
    old_branch_min_followers: Math.max(0, Math.floor(Number(smartOldMinFollowersInput?.value) || 0)),
    old_branch_min_video_count: Math.max(0, Math.floor(Number(smartOldMinVideosInput?.value) || 0)),
    new_branch_templates: collectSmartTemplateWeightRows("new"),
    old_branch_templates: collectSmartTemplateWeightRows("old"),
  };
  if (!payload.new_branch_templates.length || !payload.old_branch_templates.length) {
    setSmartTemplateConfigStatus("每个分支至少保留一行模板概率", "error");
    return;
  }
  try {
    setSmartTemplateConfigStatus("正在保存…");
    const data = await request("/api/admin/smart_template/config", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    smartTemplateConfig = data?.smart_template_config || payload;
    applySmartTemplateConfigToForm(smartTemplateConfig);
    setSmartTemplateConfigStatus("智能模板规则已保存", "success");
    smartTemplatePreviewCache = {};
    if (latestDevices && typeof latestDevices === "object") {
      await refreshSmartTemplatePreviews(Object.entries(latestDevices).map(([deviceId, info]) => ({ deviceId, info })));
    }
  } catch (error) {
    setSmartTemplateConfigStatus(error.message || "保存失败", "error");
  }
}

async function refreshSmartTemplatePreviews(visibleRows = []) {
  const items = (visibleRows || [])
    .map((row) => (row.info ? row : { info: row }))
    .filter(({ info }) => String(info?.alias || "").trim())
    .map(({ info }) => ({
      alias: String(info.alias || "").trim(),
      mode: SMART_TEMPLATE_MODE,
      owner_username: String(info?.owner_username || currentUser?.username || "").trim(),
    }));
  if (!items.length) return;
  try {
    const data = await request("/api/admin/smart_template/preview", {
      method: "POST",
      body: JSON.stringify({ items }),
    });
    if (data?.results && typeof data.results === "object") {
      smartTemplatePreviewCache = { ...smartTemplatePreviewCache, ...data.results };
    }
    if (latestDevices && typeof latestDevices === "object") {
      renderDevices(latestDevices, { skipSmartPreview: true });
      bindDeviceSelection();
    }
  } catch (error) {
    console.warn("[smart_template/preview]", error);
  }
}

function collectTemplateRefsFromAliasMaps() {
  const out = new Set();
  Object.values(aliasTemplateByMode || {}).forEach((mm) => {
    if (!mm || typeof mm !== "object") return;
    Object.values(mm).forEach((v) => {
      const s = String(v || "").trim();
      if (s) out.add(s);
    });
  });
  return out;
}

let configTargetUsersLoaded = false;
let currentDbPage = 1;
let currentDbSearchField = "";
let currentDbSearchKeyword = "";
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
let deviceLogsFetchInFlight = false;
let deviceLogsPullInFlight = false;
const DEVICE_LOG_PULL_POLL_MS = 2500;
// 设备端 mode_wait 常见 timeout_sec=50；管理台等待窗口需明显大于 50s，避免“设备尚未出轮询就被判超时”。
const DEVICE_LOG_PULL_TIMEOUT_MS = 95000;
let taskStatusRefreshInFlight = false;
let backupStatsFullQueryInFlight = false;
let lastGalleryDispatchLogLine = "";
let overviewAutoRefreshCooldownUntil = 0;
let logsAutoRefreshCooldownUntil = 0;
let taskStatusRefreshCooldownUntil = 0;
let taskStatusPollBusy = false;
let taskStatusPollTimer = null;
let batchStreamPollTimer = null;
let overviewPollTimer = null;
let taskStatusPollingEnabled = false;
let overviewPollingEnabled = false;
let batchStreamPollingEnabled = false;

const TASK_STATUS_POLL_IDLE_MS = 10000;
const TASK_STATUS_POLL_BUSY_MS = 5000;
const BATCH_STREAM_POLL_MS = 2500;
const OVERVIEW_POLL_MS = 6000;

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

function appendPublishPostBatchLog(line, reset = false) {
  if (!publishPostBatchLogsEl) return;
  const text = String(line || "");
  if (reset) {
    publishPostBatchLogsEl.textContent = text.trim() || "暂无汇总";
    publishPostBatchLogsEl.scrollTop = 0;
    return;
  }
  const current = String(publishPostBatchLogsEl.textContent || "").trim();
  if (!current || current === "暂无汇总") {
    publishPostBatchLogsEl.textContent = text.trim() || "暂无汇总";
  } else {
    publishPostBatchLogsEl.textContent = `${current}\n${text}`;
  }
  publishPostBatchLogsEl.scrollTop = publishPostBatchLogsEl.scrollHeight;
}

/** 轮询发作品最终回执（须带 publish_post_outcome_report；忽略旧版「已收到」早期 success）。 */
async function waitForPublishPostFinalResult(opId, timeoutMs = 900000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const pollPath = `/api/admin/device_tiktok/op_result?op_id=${encodeURIComponent(opId)}`;
    const base = String(await resolvedApiBase()).replace(/\/$/, "");
    const dedupedPollPath = base.endsWith("/api") && pollPath.startsWith("/api/")
      ? pollPath.slice(4)
      : pollPath;
    const pollUrl = `${base}${dedupedPollPath}`;
    const token = authToken();
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const response = await fetch(pollUrl, { method: "GET", headers, cache: "no-store" });
    const text = await response.text();
    let data = {};
    try {
      data = text ? JSON.parse(text) : {};
    } catch (_) {
      data = { code: response.ok ? 0 : 1, msg: text };
    }
    if (!response.ok) {
      throw new Error((data && data.msg) || `轮询请求失败（HTTP ${response.status}）`);
    }
    if (data.status === "done" || data.code === 0) {
      const result = data?.result || null;
      const outcome = result?.data && typeof result.data === "object" ? result.data : {};
      if (outcome.publish_post_outcome_report) {
        return result;
      }
      // 旧 App 可能先报「已收到」：继续等最终结果
    } else if (data.status !== "pending" && data.code !== 1) {
      throw new Error(data?.msg || "发作品结果轮询异常");
    }
    await new Promise((res) => setTimeout(res, 3000));
  }
  throw new Error("等待发作品最终结果超时（设备可能仍在执行）");
}

function formatPublishPostFinalLine(alias, result, errMsg) {
  if (errMsg) {
    return `❌ ${alias}：超时/异常 — ${errMsg}`;
  }
  const data = result?.data && typeof result.data === "object" ? result.data : {};
  const ok = result?.status === "success" && Boolean(data.publish_post_completed);
  const successCount = Number(data.publish_success_count);
  const targetCount = Number(data.publish_target_count);
  const countHint =
    Number.isFinite(successCount) && Number.isFinite(targetCount) && (successCount > 0 || targetCount > 0)
      ? `（${successCount}/${targetCount}）`
      : "";
  const msg = String(result?.message || "").trim() || (ok ? "发作品成功" : "发作品失败");
  return `${ok ? "✅" : "❌"} ${alias}${countHint}：${msg}`;
}

const sections = document.querySelectorAll(".panel");
const navItems = document.querySelectorAll(".nav-item");
const contentShell = document.querySelector(".content");
let currentUser = null;
const TOKEN_KEY = "auto_traces_auth_token_v1";
const USER_CACHE_KEY = "auto_traces_auth_user_v1";
const RUN_MODE_KEY = "auto_traces_run_mode_v1";
const LAST_TEMPLATE_KEY = "auto_traces_last_template_v1";
const LAST_SECTION_KEY = "auto_traces_last_section_v1";
const MAIL_ROUTE_KEY = "auto_traces_mail_route_v1";
const DEVICE_GROUP_FILTER_KEY = "auto_traces_device_group_filter_v1";
const AUTO_SWITCH_SESSION_SUMMARIES_ALIAS_KEY = "auto_traces_auto_switch_session_summaries_alias_v1";
const DEVICE_GROUP_CUSTOM_KEY = "auto_traces_device_group_custom_v1";
const OVERVIEW_PAGE_KEY = "auto_traces_overview_page_v1";
const OVERVIEW_PAGE_SIZE_KEY = "auto_traces_overview_page_size_v1";
const PC_SERVICE_PAGE_KEY = "auto_traces_pc_service_page_v1";
const PC_SERVICE_PAGE_SIZE_KEY = "auto_traces_pc_service_page_size_v1";
const DEFAULT_PC_SERVICE_PAGE_SIZE = 50;
const MAX_PC_SERVICE_PAGE_SIZE = 500;
const BACKUP_ARCHIVE_PAGE_KEY = "auto_traces_backup_archive_page_v1";
const BACKUP_ARCHIVE_PAGE_SIZE_KEY = "auto_traces_backup_archive_page_size_v1";
const ACCOUNT_RECORDS_PAGE_SIZE_KEY = "auto_traces_account_records_page_size_v1";
let autoSwitchDailyPlansByAlias = {};
let autoSwitchDailyPlanDate = "";
let backupArchiveSlotHighlightTimer = null;
let autoSwitchCutScheme = "backup";
let autoSwitchCutSchemeSaveInFlight = false;
let autoSwitchDevicesById = new Map();
let autoSwitchPickDeviceIds = new Set();
let autoSwitchSaveInFlight = false;
let backupStatsFullQueryScheduleAvailable = false;
let backupStatsFullQueryManualAvailable = false;
let backupStatsFullQuerySaveInFlight = false;
let accountStatsQueryConfig = null;
let accountStatsQueryAliasGroups = [];
let accountStatsQuerySelectedAliases = new Set();
let accountStatsQueryInFlight = false;
let accountStatsQueryConfigSaveInFlight = false;
const DB_PAGE_KEY = "auto_traces_db_page_v1";
const DB_SEARCH_FIELD_KEY = "auto_traces_db_search_field_v1";
const DB_SEARCH_KEYWORD_KEY = "auto_traces_db_search_keyword_v1";
const CV_PAGE_KEY = "auto_traces_cv_page_v1";
const TIKTOK_ROOTS_PAGE_KEY = "auto_traces_tiktok_roots_page_v1";

/** 去掉 HTTP / API 编号 / URL 等技术后缀，供底部状态栏展示 */
function humanizeRequestErrorMessage(error) {
  let raw = String(error?.message || error || "操作失败").trim();
  raw = raw.replace(/\s*\(HTTP\s+\d+[^)]*\)\s*$/i, "").trim();
  raw = raw.replace(/\s*,\s*API\s*-?\d+\s*$/i, "").trim();
  raw = raw.replace(/\s*\[[^\]]*\/api[^\]]*\]\s*$/gi, "").trim();
  raw = raw.replace(/\s*\[https?:\/\/[^\]]+\]\s*$/gi, "").trim();
  if (/^请求超时：\d+ms/i.test(raw)) return "请求超时，请稍后重试";
  if (/Unexpected token '<'|is not valid JSON/i.test(raw)) {
    return "服务端返回了 HTML 页面而非 JSON，请检查 API Base 是否指向后端（本地开发一般为 http://127.0.0.1:8000/api）";
  }
  if (/网络请求失败|无法连接服务端|Failed to fetch|fetch failed/i.test(raw)) {
    return "无法连接服务器，请检查网络或联系管理员";
  }
  if (/^Internal Server Error$/i.test(raw)) {
    return "服务器内部错误，请确认后端已重启且查看服务端日志";
  }
  return raw || "操作失败";
}

/** FastAPI/Pydantic 422 等返回 `{ detail: [...] }`；业务接口多为 `{ code, msg }` */
function formatHttpApiErrorMessage(responseStatus, data) {
  const apiMsg = data?.msg;
  if (typeof apiMsg === "string" && apiMsg.trim()) {
    const trimmed = apiMsg.trim();
    if (trimmed.startsWith("<")) {
      return "服务端返回了 HTML 页面而非 JSON，请检查 API Base 是否指向后端（本地开发一般为 http://127.0.0.1:8000/api）";
    }
    return trimmed;
  }
  if (Array.isArray(data?.detail) && data.detail.length) {
    const parts = data.detail
      .map((item) => {
        if (typeof item === "string") return item.trim();
        const loc = Array.isArray(item?.loc)
          ? item.loc.filter((x) => x !== "body").join(".")
          : "";
        const piece = typeof item?.msg === "string" ? item.msg.trim() : "";
        if (!piece) return "";
        return loc ? `${loc}：${piece}` : piece;
      })
      .filter(Boolean);
    if (parts.length) return parts.join("；");
  }
  if (apiMsg != null && typeof apiMsg !== "string") return JSON.stringify(apiMsg);
  if (responseStatus === 422) return "参数校验未通过（请检查表单数值是否在允许范围内）";
  const code = data?.code;
  if (typeof code === "number" && code !== 0) return `请求失败（code=${code}）`;
  return "请求失败";
}

function setStatus(text, type = "info") {
  const message = humanizeRequestErrorMessage({ message: String(text ?? "") });
  const shouldSuppress =
    /网络请求失败：无法连接服务端/i.test(message)
    || /failed to fetch/i.test(message)
    || /networkerror/i.test(message)
    || /fetch failed/i.test(message);
  if (shouldSuppress) return;
  if (!statusEl) {
    console.warn("status element missing:", message);
    return;
  }
  const normalized = message.replace(/\s*\n+\s*/g, " ").trim();
  statusEl.textContent = normalized || "等待操作";
  statusEl.className = `status ${type}`;
}

function updateStartupSelfCheck() {}

function updateRequestTrace() {}

function showDeleteResultNotice(message, type = "success") {
  const icon = type === "success" ? "✅" : (type === "error" ? "❌" : "ℹ️");
  const text = `${icon} ${message}`;
  setStatus(message, type);
  window.alert(text);
}

function appendDebugLog() {}

window.addEventListener("error", (event) => {
  const msg = event?.error?.message || event?.message || "页面脚本异常";
  if (cvTaskLogsEl) cvTaskLogsEl.textContent = `页面脚本异常：${msg}`;
});

window.addEventListener("unhandledrejection", (event) => {
  const reason = event?.reason;
  const msg = reason?.message || String(reason || "Promise 未处理异常");
  if (cvTaskLogsEl) cvTaskLogsEl.textContent = `页面脚本异常：${msg}`;
});

function appendMobilePendingLog(line, reset = false) {
  if (!mobilePendingLogsEl) return;
  const text = String(line || "").trim();
  if (reset) {
    lastMobilePendingLogLine = text || "";
    mobilePendingLogsEl.textContent = text || "暂无日志";
    return;
  }
  if (!text || text === lastMobilePendingLogLine) return;
  lastMobilePendingLogLine = text;
  const current = String(mobilePendingLogsEl.textContent || "").trim();
  if (!current || current === "暂无日志") {
    mobilePendingLogsEl.textContent = text;
  } else {
    mobilePendingLogsEl.textContent = `${current}\n${text}`;
  }
  mobilePendingLogsEl.scrollTop = mobilePendingLogsEl.scrollHeight;
}

function setMobilePendingExportButtonsBusy(isBusy, action = "导出") {
  const label = String(action || "导出");
  if (exportMobilePendingUsernamesBtn) {
    exportMobilePendingUsernamesBtn.disabled = Boolean(isBusy);
    exportMobilePendingUsernamesBtn.textContent = isBusy ? "导出中..." : "导出为 xlsx";
  }
  if (exportAndClearMobilePendingUsernamesBtn) {
    exportAndClearMobilePendingUsernamesBtn.disabled = Boolean(isBusy);
    exportAndClearMobilePendingUsernamesBtn.textContent = isBusy ? `${label}中...` : "导出并清空队列";
  }
}

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

function humanizeOperationLogLine(line) {
  return String(line || "").trim().replace(/^\[OP\]\[[^\]]+\]\s*/, "");
}

function summarizeDeviceOpResult(result) {
  if (!result) return "暂无设备执行结果";
  const lines = [];
  if (result.status) lines.push(`状态: ${result.status}`);
  if (result.message) lines.push(`消息: ${result.message}`);
  if (result.op_type) lines.push(`操作: ${result.op_type}`);
  if (result.device_id) lines.push(`设备: ${result.device_id}`);
  if (result.backup_name) lines.push(`备份档案名: ${result.backup_name}`);
  const logs = result?.data?.operation_logs;
  if (Array.isArray(logs) && logs.length) {
    lines.push("执行步骤:");
    lines.push(...logs.map(humanizeOperationLogLine));
  }
  return lines.join("\n");
}

/** 任务轮询完成时展示「云端回执」：与概览 device_logs 同源（compactDeviceLogLines），缺失时再退回 operation_logs / 摘要 */
function formatBackupCloudReceiptFromOpPollPayload(data) {
  const dl = data?.device_logs && typeof data.device_logs === "object" ? data.device_logs : null;
  if (dl) {
    const ids = Object.keys(dl);
    if (ids.length === 1) {
      const lines = Array.isArray(dl[ids[0]]) ? dl[ids[0]] : [];
      const compacted = compactDeviceLogLines(lines).join("\n").trim();
      if (compacted) return compacted;
    }
    const parts = [];
    for (const did of ids) {
      const lines = Array.isArray(dl[did]) ? dl[did] : [];
      const text = compactDeviceLogLines(lines).join("\n").trim();
      if (text) parts.push(`--- ${did} ---\n${text}`);
    }
    if (parts.length) return parts.join("\n\n");
  }
  const opLogs = data?.result?.data?.operation_logs;
  if (Array.isArray(opLogs) && opLogs.length) {
    return opLogs.map(humanizeOperationLogLine).join("\n");
  }
  return summarizeDeviceOpResult(data?.result);
}

function setTaskStatusInfo(text) {
  if (!taskStatusEl) return;
  taskStatusEl.textContent = text;
}

function isLogFollowEnabled() {
  return schedulerLogsFollowToggle?.checked !== false;
}

function isLogContainerAtBottom(el) {
  if (!el) return true;
  return el.scrollHeight - (el.scrollTop + el.clientHeight) <= LOG_SCROLL_THRESHOLD;
}

function scrollLogContainerToBottom(el, force = false) {
  if (!el) return;
  if (force || isLogFollowEnabled()) {
    el.scrollTop = el.scrollHeight;
  }
}

function appendLogToElement(el, line) {
  if (!el) return;
  const text = String(line || "").trim();
  if (!text) return;
  const current = String(el.textContent || "").trim();
  if (!current || current === "暂无日志") {
    el.textContent = text;
  } else {
    el.textContent = `${current}\n${text}`;
  }
  el.scrollTop = el.scrollHeight;
}

function renderSchedulerTaskSettings(tasks = {}) {
  if (!schedulerTaskSettingsEl) return;
  const entries = Object.entries(tasks);
  if (!entries.length) {
    schedulerTaskSettingsEl.textContent = "暂无任务配置";
    return;
  }
  schedulerTaskSettingsEl.innerHTML = entries.map(([taskKey, task]) => {
    const enabled = task?.enabled ? "checked" : "";
    const interval = Number(task?.interval_sec || 3600);
    const label = task?.label || taskKey;
    const lastRun = task?.last_run_at || "-";
    return `
      <div style="border:1px solid rgba(0,0,0,.08);border-radius:12px;padding:12px;display:grid;gap:8px;">
        <div style="display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap;align-items:center;">
          <strong>${label}</strong>
          <span style="color:#666;font-size:12px;">任务键：${taskKey}</span>
        </div>
        <label style="display:flex;align-items:center;gap:8px;">
          <input type="checkbox" data-task-enabled="${taskKey}" ${enabled} />
          启用任务
        </label>
        <label style="display:flex;align-items:center;gap:8px;">
          间隔（秒）
          <input type="number" min="60" max="86400" value="${interval}" data-task-interval="${taskKey}" style="width:120px;" />
        </label>
        <div style="color:#666;font-size:12px;">上次执行：${lastRun}</div>
        <div style="display:flex;gap:8px;flex-wrap:wrap;">
          <button type="button" class="ghost" data-task-save="${taskKey}">保存配置</button>
          <button type="button" class="primary" data-task-run="${taskKey}">立即执行</button>
        </div>
      </div>
    `;
  }).join("");

  schedulerTaskSettingsEl.querySelectorAll("[data-task-save]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const taskKey = btn.getAttribute("data-task-save");
      const enabled = schedulerTaskSettingsEl.querySelector(`[data-task-enabled="${taskKey}"]`)?.checked ?? false;
      const intervalRaw = Number(schedulerTaskSettingsEl.querySelector(`[data-task-interval="${taskKey}"]`)?.value || 3600);
      const interval_sec = Math.max(60, Math.min(86400, Number.isFinite(intervalRaw) ? intervalRaw : 3600));
      await request("/api/admin/tasks/scheduler/task_config", {
        method: "POST",
        body: JSON.stringify({ task_key: taskKey, enabled, interval_sec }),
      });
      await refreshTaskStatus();
    });
  });

  schedulerTaskSettingsEl.querySelectorAll("[data-task-run]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const taskKey = btn.getAttribute("data-task-run");
      await request(`/api/admin/tasks/run?task_key=${encodeURIComponent(taskKey)}`, { method: "POST" });
      await refreshTaskStatus();
    });
  });
}

function renderGroupedTaskLogs(taskLogs = {}) {
  if (!schedulerTaskLogsEl) return;
  const wasAtBottom = isLogContainerAtBottom(schedulerTaskLogsEl);
  const names = Object.keys(taskLogs).sort();
  if (!names.length) {
    schedulerTaskLogsEl.textContent = "暂无日志";
    scrollLogContainerToBottom(schedulerTaskLogsEl, wasAtBottom);
    return;
  }
  const blocks = names.map((name) => {
    const lines = Array.isArray(taskLogs[name]) ? taskLogs[name] : [];
    const body = lines.length ? lines.slice(-150).join("\n") : "（无输出）";
    return `── ${name} ──\n${body}`;
  });
  schedulerTaskLogsEl.textContent = blocks.join("\n\n");
  if (wasAtBottom || isLogFollowEnabled()) {
    requestAnimationFrame(() => scrollLogContainerToBottom(schedulerTaskLogsEl, true));
  }
}

function renderCvProcessQueueLogs(taskLogs = {}, runningTasks = []) {
  if (!cvTaskLogsEl) return;
  const taskNames = Object.keys(taskLogs || {});
  const matched = taskNames.filter((name) => /^process_queue/i.test(String(name || "")));
  if (!matched.length) {
    const running = Array.isArray(runningTasks)
      ? runningTasks.filter((item) => /^process_queue/i.test(String(item?.name || "")) && !!item?.running)
      : [];
    const wasAtBottomEarly = isLogContainerAtBottom(cvTaskLogsEl);
    if (running.length) {
      const text = running.map((item) => `${item.name} (pid=${item.pid ?? "-"})`).join("\n");
      cvTaskLogsEl.textContent = `任务运行中，等待输出...\n${text}`;
    } else {
      cvTaskLogsEl.textContent = "暂无日志";
    }
    if (wasAtBottomEarly) {
      requestAnimationFrame(() => {
        cvTaskLogsEl.scrollTop = cvTaskLogsEl.scrollHeight;
      });
    }
    return;
  }
  const lines = [];
  matched
    .sort((a, b) => a.localeCompare(b))
    .forEach((name) => {
      lines.push(`[${name}]`);
      const body = Array.isArray(taskLogs[name]) ? taskLogs[name] : [];
      if (!body.length) {
        lines.push("暂无日志");
      } else {
        lines.push(...body.slice(-200));
      }
      lines.push("");
    });
  const wasAtBottom = isLogContainerAtBottom(cvTaskLogsEl);
  cvTaskLogsEl.textContent = lines.join("\n").trim() || "暂无日志";
  if (wasAtBottom) {
    requestAnimationFrame(() => {
      cvTaskLogsEl.scrollTop = cvTaskLogsEl.scrollHeight;
    });
  }
}

function toggleDeviceOpsButtons() {
  if (copyCurrentRecordBtn) {
    copyCurrentRecordBtn.disabled = !Array.isArray(accountEditorRows) || accountEditorRows.length === 0;
  }
  if (addDeviceBlankRecordBtn) {
    addDeviceBlankRecordBtn.disabled = false;
  }
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

function getDeviceLogTargetIds() {
  const selectedFromDropdown = (deviceLogDeviceSelect?.value || "").trim();
  if (selectedFromDropdown) return [selectedFromDropdown];
  const selected = selectedDeviceIds();
  if (selected.length) return selected;
  return [];
}

async function fetchCachedDeviceLogs(ids, maxLines = 120) {
  if (!ids.length) return { device_logs: {}, pull_pending: [] };
  const qs = new URLSearchParams({ device_ids: ids.join(","), max_lines: String(maxLines) });
  const data = await request(`/api/admin/device_logs?${qs}`);
  const incoming = data?.device_logs && typeof data.device_logs === "object" ? data.device_logs : {};
  latestDeviceLogs = { ...latestDeviceLogs, ...incoming };
  return {
    device_logs: incoming,
    pull_pending: Array.isArray(data?.pull_pending) ? data.pull_pending : [],
  };
}

async function requestDeviceLogPull(ids, maxLines = 400) {
  if (!ids.length) return { requested: [] };
  const data = await request("/api/admin/device_logs/request_pull", {
    method: "POST",
    body: JSON.stringify({ device_ids: ids, max_lines: maxLines }),
  });
  return {
    requested: Array.isArray(data?.requested) ? data.requested : ids,
  };
}

function sleepMs(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function downloadTextAsFile(text, filename) {
  const blob = new Blob([String(text || "")], { type: "text/plain;charset=utf-8" });
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(objectUrl);
}

async function downloadDeviceLogFile(deviceId) {
  const did = String(deviceId || "").trim();
  if (!did) return false;
  const url = await buildApiUrl(`/api/admin/device_logs/download?device_id=${encodeURIComponent(did)}`);
  const headers = {};
  const token = authToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(url, { headers, cache: "no-store" });
  if (!response.ok) {
    const text = await response.text();
    let msg = text.slice(0, 200) || `下载失败 HTTP ${response.status}`;
    try {
      const data = JSON.parse(text);
      if (data?.msg) msg = String(data.msg);
    } catch {
      /* 非 JSON 时沿用原始文本 */
    }
    const err = new Error(msg);
    err.httpStatus = response.status;
    throw err;
  }
  const blob = await response.blob();
  const cd = response.headers.get("content-disposition") || "";
  const match = /filename="?([^";]+)"?/i.exec(cd);
  const filename = match?.[1] || `device_${did}_${new Date().toISOString().slice(0, 10)}.txt`;
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(objectUrl);
  return true;
}

async function pullDeviceLogsFromDevices(ids) {
  if (!ids.length) {
    renderDeviceExecLogs(latestDeviceLogs);
    setStatus("请先选择要下载日志的设备", "error");
    return;
  }
  if (deviceLogsPullInFlight) return;
  deviceLogsPullInFlight = true;
  if (deviceLogPullBtn) {
    deviceLogPullBtn.disabled = true;
    deviceLogPullBtn.textContent = "等待设备回传…";
  }
  try {
    await requestDeviceLogPull(ids, 400);
    const deadline = Date.now() + DEVICE_LOG_PULL_TIMEOUT_MS;
    let lastPending = ids.slice();
    while (Date.now() < deadline) {
      const { pull_pending } = await fetchCachedDeviceLogs(ids, 400);
      renderDeviceExecLogs(latestDeviceLogs);
      lastPending = pull_pending;
      if (!pull_pending.length) break;
      await sleepMs(DEVICE_LOG_PULL_POLL_MS);
    }
    const pendingSet = new Set(lastPending);
    if (lastPending.length) {
      appendDebugLog("DEVICE_LOG_PULL", { pending: lastPending, note: "timeout_or_offline" });
    }
    let downloaded = 0;
    let emptyToday = 0;
    let cachedFallback = 0;
    for (const did of ids) {
      if (pendingSet.has(did)) continue;
      try {
        const ok = await downloadDeviceLogFile(did);
        if (ok) downloaded += 1;
      } catch (err) {
        if (err?.httpStatus === 404) {
          const cached = Array.isArray(latestDeviceLogs?.[did]) ? latestDeviceLogs[did] : [];
          const text = compactDeviceLogLines(cached).join("\n").trim();
          if (text) {
            const day = new Date().toISOString().slice(0, 10);
            downloadTextAsFile(`${text}\n`, `device_${did}_${day}.txt`);
            cachedFallback += 1;
            continue;
          }
          emptyToday += 1;
          continue;
        }
        appendDebugLog("DEVICE_LOG_DOWNLOAD", { device_id: did, error: String(err?.message || err) });
        setStatus(err?.message || "下载日志文件失败", "error");
        return;
      }
    }
    if (downloaded > 0) {
      const suffix = lastPending.length ? `；另有 ${lastPending.length} 台设备未在时限内回传` : "";
      setStatus(`已下载 ${downloaded} 台设备今日日志${suffix}`, lastPending.length ? "warning" : "success");
    } else if (cachedFallback > 0) {
      const suffix = lastPending.length ? `；另有 ${lastPending.length} 台设备未在时限内回传` : "";
      setStatus(`设备今日本地日志为空，已下载 ${cachedFallback} 台设备的云端缓存日志${suffix}`, "info");
    } else if (emptyToday > 0) {
      const suffix = lastPending.length ? `；${lastPending.length} 台设备未在时限内回传` : "";
      setStatus(`设备已回传，但今日本地与服务端均无可用日志${suffix}`, "info");
    } else if (lastPending.length) {
      setStatus("设备未在时限内回传日志，请确认 App 在后台运行后重试", "error");
    }
  } catch (err) {
    appendDebugLog("DEVICE_LOG_PULL", { error: String(err?.message || err) });
    setStatus(err?.message || "请求设备上传日志失败", "error");
  } finally {
    deviceLogsPullInFlight = false;
    if (deviceLogPullBtn) {
      deviceLogPullBtn.disabled = false;
      deviceLogPullBtn.textContent = "下载今日日志";
    }
  }
}

async function refreshVisibleDeviceLogs({ requestPull = false } = {}) {
  const ids = getDeviceLogTargetIds();
  if (!ids.length) {
    renderDeviceExecLogs(latestDeviceLogs);
    return;
  }
  if (requestPull) {
    await pullDeviceLogsFromDevices(ids);
    return;
  }
  if (deviceLogsFetchInFlight) return;
  deviceLogsFetchInFlight = true;
  try {
    await fetchCachedDeviceLogs(ids, 400);
    renderDeviceExecLogs(latestDeviceLogs);
  } catch (_) {
    /* 读取缓存失败静默，用户可点「下载今日日志」重试 */
  } finally {
    deviceLogsFetchInFlight = false;
  }
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

function isCurrentPageLocalDevHost() {
  const { hostname } = window.location;
  return hostname === "127.0.0.1" || hostname === "localhost";
}

function defaultApiBase() {
  const { protocol, hostname, port } = window.location;
  if (protocol === "file:") {
    return "http://127.0.0.1:8000/api";
  }
  if (isCurrentPageLocalDevHost() && String(port || "") === "8080") {
    // 本地静态服务(8080) + 后端(8000) 的固定组合，避免 /api 打到静态服务 404。
    return `${protocol}//${hostname || "127.0.0.1"}:8000/api`;
  }
  // VPS / 公网部署默认走同源 /api（由 Nginx 反代到后端）。
  return "/api";
}

function normalizeApiBaseForCurrentPage(base) {
  const raw = String(base || "").trim().replace(/\/$/, "");
  if (!raw) return "";
  return raw;
}

function isLoopbackApiBase(base) {
  const raw = String(base || "").trim().replace(/\/$/, "");
  if (!raw || raw.startsWith("/")) return false;
  try {
    const url = new URL(raw.includes("://") ? raw : `http://${raw}`);
    return url.hostname === "127.0.0.1" || url.hostname === "localhost";
  } catch {
    return /^(https?:\/\/)?(127\.0\.0\.1|localhost)(:\d+)?/i.test(raw);
  }
}

/** 在 VPS 等远程页面上，自动丢弃 localStorage 里残留的 127.0.0.1:8000/api。 */
function sanitizeApiBaseForCurrentPage(base) {
  const normalized = normalizeApiBaseForCurrentPage(base);
  if (!normalized) return "";
  if (!isCurrentPageLocalDevHost() && isLoopbackApiBase(normalized)) {
    return defaultApiBase();
  }
  // 本地 8080 静态服务时，localStorage 里残留的 /api 会打到静态端口而非 8000 后端。
  if (isCurrentPageLocalDevHost() && String(window.location.port || "") === "8080" && normalized === "/api") {
    return defaultApiBase();
  }
  return normalized;
}

function apiBase() {
  const manual = sanitizeApiBaseForCurrentPage((apiBaseInput?.value || "").trim().replace(/\/$/, ""));
  if (manual) return manual;
  const override = sanitizeApiBaseForCurrentPage(runtimeApiBaseOverride);
  if (override) return override;
  const saved = sanitizeApiBaseForCurrentPage((localStorage.getItem(API_BASE_LS_KEY) || "").trim().replace(/\/$/, ""));
  if (saved) return saved;
  return defaultApiBase();
}

async function probeAuthMeStatus(base) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 5000);
  const result = {
    ok: false,
    httpStatus: 0,
    errorKind: "网络不可达",
    authState: "未知",
  };
  try {
    const response = await fetch(`${base}/health`, {
      method: "GET",
      cache: "no-store",
      signal: controller.signal,
    });
    result.httpStatus = response.status;
    if (response.ok) {
      result.ok = true;
      result.errorKind = "无";
      result.authState = "有效";
      return result;
    }
    if (response.status === 401) {
      result.ok = true;
      result.errorKind = "401";
      result.authState = "无效（未登录/失效）";
      return result;
    }
    if (response.status === 403) {
      result.ok = true;
      result.errorKind = "403";
      result.authState = "有效但无权限";
      return result;
    }
    if (response.status === 404) {
      result.errorKind = "404";
      result.authState = "不可用";
      return result;
    }
    result.errorKind = `HTTP ${response.status}`;
    result.authState = `异常（HTTP ${response.status}）`;
    return result;
  } catch (error) {
    const raw = String(error?.message || error || "");
    if (/abort/i.test(raw)) {
      result.errorKind = "网络不可达";
      result.authState = "不可达";
    } else {
      result.errorKind = /failed to fetch|networkerror|load failed|fetch failed/i.test(raw) ? "网络不可达" : raw || "网络不可达";
      result.authState = "不可达";
    }
    return result;
  } finally {
    clearTimeout(timer);
  }
}

async function resolvedApiBase() {
  const preferred = String(apiBase() || "").trim().replace(/\/$/, "") || defaultApiBase();
  return preferred || defaultApiBase();
}

async function ensureApiBaseHealthyOnBoot() {
  const preferred = String(apiBase() || "").trim().replace(/\/$/, "") || defaultApiBase();
  updateStartupSelfCheck({ apiBase: preferred });
  if (!preferred) return;
  const probe = await probeAuthMeStatus(preferred);
  updateStartupSelfCheck({
    authMe: probe.ok ? "通" : `${probe.errorKind}（HTTP ${probe.httpStatus || "-"}）`,
    authState: probe.authState,
    errorKind: probe.errorKind,
  });
  if (probe.ok) return;
  setStatus(`API Base 连通性检查失败：${preferred}（请确认后端已启动且 Nginx 已反代 /api）`, "error");
}

function isAuthExpiredError(error) {
  const message = String(error?.message || "");
  return /HTTP\s*(401|403)\b/.test(message)
    || /API\s*(401|403)\b/.test(message)
    || /请先登录|未登录|登录已失效|无权限|login required|invalid token/i.test(message);
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
    if (activeVisible) switchSection("overview", { persist: false });
  }
}

function isAdminOnlySection(sectionId) {
  const section = document.getElementById(sectionId);
  return Boolean(section?.classList.contains("admin-only"));
}

function mailAdminOrigin() {
  const api = String(apiBase() || defaultApiBase()).trim().replace(/\/$/, "");
  if (/^https?:\/\//i.test(api)) {
    return api.replace(/\/api$/i, "");
  }
  if (isCurrentPageLocalDevHost() && String(window.location.port || "") === "8080") {
    const { protocol, hostname } = window.location;
    return `${protocol}//${hostname || "127.0.0.1"}:8000`;
  }
  return window.location.origin;
}

function mailAdminUrl(route = "email") {
  const safeRoute = String(route || "email").replace(/^\/+/, "");
  const build = String(window.__AUTO_TRACES_WEB_ADMIN_BUILD__ || "").trim();
  const bust = build || String(Date.now());
  return `${mailAdminOrigin()}/mail-admin/${safeRoute}?embed=1&v=${encodeURIComponent(bust)}`;
}

function isMailModuleFrameLoadedWrong(frame) {
  try {
    const doc = frame?.contentDocument || frame?.contentWindow?.document;
    if (!doc) return false;
    const title = String(doc.title || "");
    if (/MS_OAuth2API/i.test(title)) return false;
    if (doc.getElementById("authShell") || doc.getElementById("appShell")) return true;
    if (/Auto Traces/i.test(title)) return true;
  } catch (_) {
    // 跨域或 iframe 未就绪时不判定
  }
  return false;
}

function pushMailAuthToFrame(frame) {
  if (!frame?.contentWindow) return;
  const origin = mailAdminOrigin();
  try {
    frame.contentWindow.postMessage(
      { type: "auto_traces_auth_token", token: authToken() },
      origin,
    );
  } catch (_) {
    // 忽略跨域或 iframe 未就绪
  }
}

function navigateMailModuleFrame(route = "email") {
  const frame = document.getElementById("mailModuleFrame");
  const placeholder = document.getElementById("mailModulePlaceholder");
  if (!frame) return;
  const safeRoute = String(route || "email").replace(/^\/+/, "");
  const target = mailAdminUrl(safeRoute);
  placeholder?.classList.add("hidden");

  const onLoad = () => {
    if (isMailModuleFrameLoadedWrong(frame)) {
      placeholder?.classList.remove("hidden");
      const hint = document.getElementById("mailModuleTargetHint");
      if (hint) {
        hint.textContent = `iframe 内仍是主控制台（浏览器缓存了修复前的页面）。请按 Ctrl+Shift+R 强刷，或新标签打开：${mailAdminOrigin()}/mail-admin/${safeRoute}`;
      }
      setStatus("邮箱模块加载异常：iframe 缓存了旧页面，请强刷浏览器", "error");
      return;
    }
    pushMailAuthToFrame(frame);
    placeholder?.classList.add("hidden");
  };
  const onError = () => {
    placeholder?.classList.remove("hidden");
  };

  frame.addEventListener("load", onLoad, { once: true });
  frame.addEventListener("error", onError, { once: true });
  frame.dataset.mailRoute = safeRoute;
  frame.src = target;
  pushMailAuthToFrame(frame);
}

function syncMailModuleSection(route) {
  const safeRoute = String(route || localStorage.getItem(MAIL_ROUTE_KEY) || "email").replace(/^\/+/, "");
  localStorage.setItem(MAIL_ROUTE_KEY, safeRoute);
  contentShell?.classList.add("content--mail-module");
  const hint = document.getElementById("mailModuleTargetHint");
  if (hint) {
    hint.textContent = `加载地址：${mailAdminUrl(safeRoute)}`;
  }
  navigateMailModuleFrame(safeRoute);
}

async function switchSection(id, options = {}) {
  const { persist = true, mailRoute = "" } = options;
  const hasTargetSection = Array.from(sections).some((section) => section.id === id);
  let nextSectionId = hasTargetSection ? id : "overview";
  if (!hasTargetSection && id) {
    setStatus(`页面不存在：${id}，已自动跳回设备概览`, "warning");
  }
  if (currentUser?.role !== "admin" && isAdminOnlySection(nextSectionId)) {
    nextSectionId = "overview";
  }
  sections.forEach((section) => {
    section.classList.toggle("active", section.id === nextSectionId);
  });
  const activeMailRoute = mailRoute || localStorage.getItem(MAIL_ROUTE_KEY) || "email";
  navItems.forEach((item) => {
    const sameSection = item.dataset.section === nextSectionId;
    const mailItemRoute = item.dataset.mailRoute || "";
    const matchesMailRoute = nextSectionId !== "mail-module"
      ? true
      : (mailItemRoute ? mailItemRoute === activeMailRoute : false);
    item.classList.toggle("active", sameSection && matchesMailRoute);
  });
  if (nextSectionId !== "mail-module") {
    contentShell?.classList.remove("content--mail-module");
    document.querySelector(".app")?.classList.remove("app--mail-module");
  } else {
    document.querySelector(".app")?.classList.add("app--mail-module");
  }
  if (persist) {
    localStorage.setItem(LAST_SECTION_KEY, nextSectionId);
  }
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
  if (nextSectionId === "pc-service") {
    try {
      await loadPcServiceDevices();
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
  if (nextSectionId === "account-manager") {
    try {
      await loadBoundDeviceAliases();
      await refreshAccountEditorLight();
      renderAccountEditorRows();
    } catch (_) {
      // 保持静默，避免打断页面切换；状态栏会显示已有错误。
    }
  }
  if (nextSectionId === "batch-stream") {
    try {
      await loadBoundDeviceAliases();
      renderBatchStreamAliasOptions();
      updateBatchStreamProgressVisibility();
      await pollBatchStreamStatus();
      scheduleBatchStreamPoll(BATCH_STREAM_POLL_MS);
    } catch (_) {
      // 保持静默，避免打断页面切换；状态栏会显示已有错误。
    }
  } else {
    clearBatchStreamPollTimer();
  }
  if (nextSectionId === "backup-section") {
    try {
      await loadBoundDeviceAliases();
      await loadAccountEditorRows();
      renderDeviceRegionPresetOptions();
      renderDeviceAliasPresetOptions();
      await loadAutoSwitchConfig({ refreshArchive: false });
      await loadBackupArchiveRecords();
    } catch (_) {
      // 保持静默，避免打断页面切换；状态栏会显示已有错误。
    }
  }
  if (nextSectionId === "db-table-section") {
    try {
      await loadDbTablePage();
    } catch (_) {
      // 保持静默，避免打断页面切换；状态栏会显示已有错误。
    }
  }
  if (nextSectionId === "gallery-dispatch") {
    try {
      await loadBoundDeviceAliases();
      renderDeviceAliasPresetOptions();
      await loadGalleryLibrary();
    } catch (error) {
      setStatus(humanizeRequestErrorMessage(error) || "加载图库失败", "error");
    }
  }
  if (nextSectionId === "failure-reports") {
    try {
      await loadFailureReports();
    } catch (error) {
      setStatus(humanizeRequestErrorMessage(error) || "加载失败采集失败", "error");
    }
  }
  if (nextSectionId === "mail-module") {
    syncMailModuleSection(mailRoute || activeMailRoute);
  }
}

navItems.forEach((item) => {
  item.addEventListener("click", () => {
    const mailRoute = item.dataset.mailRoute || "";
    if (mailRoute) {
      localStorage.setItem(MAIL_ROUTE_KEY, mailRoute);
    }
    void switchSection(item.dataset.section, { mailRoute });
  });
});

function joinApiUrl(baseBefore, path) {
  const base = String(baseBefore || "").replace(/\/$/, "");
  const rawPath = String(path || "").trim();
  const normalizedPath = rawPath.startsWith("/") ? rawPath : `/${rawPath}`;
  const dedupedPath = base.endsWith("/api") && normalizedPath.startsWith("/api/")
    ? normalizedPath.slice(4)
    : normalizedPath;
  return `${base}${dedupedPath}`;
}

async function buildApiUrl(path) {
  const baseBefore = String(await resolvedApiBase()).replace(/\/$/, "");
  return joinApiUrl(baseBefore, path);
}

async function parseFetchJsonResponse(response) {
  const text = await response.text();
  if (!text) return {};
  if (response.status === 413) {
    throw new Error("上传文件过大，超过服务器限制（413）。请减少单次数量，或联系管理员调大 Nginx client_max_body_size");
  }
  try {
    return JSON.parse(text);
  } catch {
    if (/^\s*</.test(text)) {
      if (/413|Request Entity Too Large|client_max_body/i.test(text)) {
        throw new Error("上传文件过大，超过服务器限制。请减少单次数量，或联系管理员调大 Nginx client_max_body_size");
      }
      throw new Error("服务端返回了 HTML 页面而非 JSON，请检查 API Base 是否指向后端（本地开发一般为 http://127.0.0.1:8000/api）");
    }
    throw new Error(String(text).slice(0, 240) || "服务返回非 JSON 响应");
  }
}

async function fetchFormDataApi(path, formData, options = {}) {
  const url = await buildApiUrl(path);
  const headers = { ...(options.headers || {}) };
  const token = authToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(url, {
    method: options.method || "POST",
    body: formData,
    headers,
  });
  const data = await parseFetchJsonResponse(response);
  if (!response.ok || data.code !== 0) {
    throw new Error(formatHttpApiErrorMessage(response.status, data));
  }
  return data;
}

async function request(path, options = {}) {
  const baseBefore = String(await resolvedApiBase()).replace(/\/$/, "");
  let url = joinApiUrl(baseBefore, path);
  const method = String(options.method || "GET").toUpperCase();
  const startedAt = Date.now();
  const timeoutMs = Number.isFinite(Number(options.timeoutMs)) ? Math.max(1000, Number(options.timeoutMs)) : 30000;
  updateRequestTrace({ phase: "request-start", url, baseBefore, baseAfter: baseBefore, method, status: "pending", changed: "-", note: "" });
  appendDebugLog("REQUEST", { url, options: { ...options, body: options.body || undefined }, baseBefore, timeoutMs });
  try {
    const headers = {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    };
    const token = authToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const response = await fetch(url, {
      ...options,
      headers,
      signal: controller.signal,
      cache: options.cache || (method === "GET" || method === "HEAD" ? "no-store" : options.cache),
    });
    clearTimeout(timer);
    if (response.status === 401 || response.status === 403) {
      const cachedUser = getCachedUser();
      if (!cachedUser) {
        setAuthToken("");
        currentUser = null;
        authShell?.classList.remove("hidden");
        appShell?.classList.add("hidden");
        if (authMessageEl) authMessageEl.textContent = "登录已失效，请重新登录";
      }
    }
    const text = await response.text();
    let data;
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = { code: 1, msg: text || "服务返回非 JSON 响应" };
    }
    appendDebugLog("RESPONSE", { url, status: response.status, ok: response.ok, data, baseBefore });
    updateRequestTrace({ phase: "response", url, baseBefore, baseAfter: baseBefore, method, status: `http ${response.status}`, changed: "-", note: data?.code === 0 ? "ok" : String(data?.msg || "api-error") });
    const elapsedMs = Date.now() - startedAt;
    if (!response.ok || data.code !== 0) {
      const msg = formatHttpApiErrorMessage(response.status, data);
      updateRequestTrace({ phase: "response", url, baseBefore, baseAfter: baseBefore, method, status: `http ${response.status}`, changed: "-", note: `${msg} | ${elapsedMs}ms` });
      appendDebugLog("API_ERROR", { url, httpStatus: response.status, apiCode: data?.code, msg });
      throw new Error(msg);
    }
    updateRequestTrace({ phase: "response", url, baseBefore, baseAfter: baseBefore, method, status: `http ${response.status}`, changed: "-", note: `ok | ${elapsedMs}ms` });
    return data;
  } catch (error) {
    const raw = error?.message || String(error);
    const isAbortError = String(error?.name || "") === "AbortError" || /abort/i.test(raw);
    const isNetworkFetchError =
      error instanceof TypeError &&
      /Failed to fetch|NetworkError|Load failed|fetch failed/i.test(raw);
    const elapsedMs = Date.now() - startedAt;
    const friendly = isAbortError
      ? "请求超时，请稍后重试"
      : (isNetworkFetchError
        ? "无法连接服务器，请检查网络或联系管理员"
        : humanizeRequestErrorMessage(error));
    appendDebugLog("ERROR", { url, message: friendly, rawMessage: raw, baseBefore, timeoutMs, elapsedMs });
    updateRequestTrace({
      phase: isAbortError ? "timeout" : "error",
      url,
      baseBefore,
      baseAfter: String(runtimeApiBaseOverride || baseBefore || "-") || "-",
      method,
      status: isAbortError ? "timeout" : (isNetworkFetchError ? "network-error" : "error"),
      changed: runtimeApiBaseOverride && runtimeApiBaseOverride !== baseBefore ? "api-base-changed" : "-",
      note: `${raw} | ${elapsedMs}ms`,
    });
    throw new Error(friendly);
  }
}

function renderDeviceRow(deviceId, info, context = {}) {
  const isAdmin = Boolean(context.isAdmin);
  const templateNames = Array.isArray(context.templateNames) ? context.templateNames : ["default"];
  const fallbackTemplate = String(context.fallbackTemplate || "default").trim() || "default";
  const alias = String(info?.alias || "").trim();
  const statusClass = info?.status;
  const pending = info?.pending_start ? "是" : "否";
  const pendingBadge = info?.pending_start ? "<span class=\"badge pending\">待启动</span>" : "-";
  const disabled = info?.status === "offline" ? "disabled" : "";
  const rowStyle = info?.status === "offline" ? ' style="opacity:.55;"' : "";
  const groupName = String(info?.device_group || "").trim();
  const selectedGroupValue = groupName || "__ungrouped__";
  const groupOptionsHtml = getTableGroupOptions(groupName)
    .map((name) => (
      `<option value="${escapeHtml(name)}" ${name === selectedGroupValue ? "selected" : ""}>${escapeHtml(displayGroupName(name))}</option>`
    ))
    .join("");
  const groupCell = `
    <td>
      <select
        class="device-group-select ui-select ui-select--table"
        data-device="${deviceId}"
        style="min-width:100px;padding:4px 6px;font-size:12px;"
      >
        ${groupOptionsHtml}
      </select>
    </td>
  `;
  const ownerCell = isAdmin ? `<td>${String(info?.owner_username || "")}</td>` : "";
  const aliasKey = String(alias || "").trim().toLowerCase();
  const ownerUsername = String(info?.owner_username || currentUser?.username || "").trim();
  const previewKey = smartPreviewKey(ownerUsername, SMART_TEMPLATE_MODE, aliasKey);
  const smartPreview = smartTemplatePreviewCache[previewKey] || {};
  const smartModeActive = isSmartTemplateModeActive();
  const smartEnabled = alias && smartModeActive
    ? (typeof smartPreview.enabled === "boolean"
      ? smartPreview.enabled
      : isAliasSmartEnabledForOwner(ownerUsername, aliasKey))
    : false;
  const tmplUi = alias && !smartEnabled
    ? resolveEffectiveDeviceTemplateUI(aliasKey, "")
    : {
      selectedTemplate: String(context.fallbackTemplate || "default").trim() || "default",
      badgeText: "-",
      badgeClass: "pending",
      badgeHint: "",
    };
  let selectedTemplate = tmplUi.selectedTemplate;
  let badgeText = tmplUi.badgeText || "";
  let badgeClass = tmplUi.badgeClass || "pending";
  let badgeHint = tmplUi.badgeHint || "";
  if (smartEnabled) {
    selectedTemplate = String(smartPreview.resolved_template || context.fallbackTemplate || "default").trim()
      || "default";
    const res = smartPreview.resolution || {};
    const branchLabel = res.branch_label || (res.branch === "old" ? "老号分支" : "新号分支");
    badgeText = `智能 · ${branchLabel}`;
    badgeClass = "online";
    badgeHint = [
      `高亮账号：${res.tiktok_username || "（未解析）"}`,
      `粉丝 ${res.follower_count ?? 0}（>${res.threshold_followers ?? 100} 且 `,
      `作品 ${res.video_count ?? 0} >${res.threshold_video_count ?? 15} 为老号分支）`,
      `命中模板：${templateDisplayName(selectedTemplate, currentTemplateMeta) || selectedTemplate}`,
    ].join("\n");
  }
  const rowTemplateNames = (() => {
    const base = Array.isArray(context.templateNames) ? context.templateNames : ["default"];
    if (selectedTemplate && !base.includes(selectedTemplate)) {
      return [selectedTemplate, ...base];
    }
    return base;
  })();
  const optionsHtml = rowTemplateNames.map((name) => (
    `<option value="${name}" ${name === selectedTemplate ? "selected" : ""}>${templateDisplayName(name, currentTemplateMeta)}</option>`
  )).join("");
  const smartToggleDisabled = !alias || !smartModeActive ? "disabled" : "";
  const smartToggleClass = smartEnabled ? "primary" : "ghost";
  const smartToggleTitle = !smartModeActive
    ? "智能模板暂仅支持涨粉模式，请先在运行配置切换到涨粉模式"
    : "开启后按智能模板规则自动选模板，右侧下拉不可手动修改";
  const smartToggleLabel = !smartModeActive
    ? "仅涨粉可用"
    : (smartEnabled ? "已启用" : "启用智能模版");
  const smartCell = `
    <td style="white-space:nowrap;vertical-align:middle;">
      <button
        type="button"
        class="${smartToggleClass} js-smart-template-toggle"
        data-device="${deviceId}"
        data-alias="${alias}"
        data-owner="${escapeHtml(ownerUsername)}"
        style="padding:6px 10px;font-size:12px;"
        ${smartToggleDisabled}
        title="${escapeHtml(smartToggleTitle)}"
      >${smartToggleLabel}</button>
    </td>
  `;
  const templateCell = `
    <td>
      <select
        class="alias-template-select ui-select ui-select--table"
        data-device="${deviceId}"
        data-alias="${alias}"
        data-owner="${escapeHtml(ownerUsername)}"
        style="min-width:140px;padding:4px 6px;font-size:12px;"
        ${alias && !smartEnabled ? "" : "disabled"}
      >
        ${optionsHtml}
      </select>
      <div style="margin-top:6px;">
        <span class="badge ${badgeClass} js-template-bind-state" title="${escapeHtml(String(badgeHint || ""))}">
          ${escapeHtml(badgeText || "—")}
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
        title="${adminAutoStartEnabled ? "点击切换该设备是否参与自动起号策略" : "当前总开关关闭，设备自动起号策略不会生效"}"
      >
        ${autoStartLabel}
      </button>
    </td>
  `;
  const publishOneDisabled = !alias || info?.status === "offline" || isAutoSwitchEnabledForDeviceId(deviceId) ? "disabled" : "";
  const publishOneTitle = isAutoSwitchEnabledForDeviceId(deviceId)
    ? AUTO_SWITCH_MANUAL_BLOCK_HINT
    : "本行单台下发发作品入口（与上方批量「发布作品」相同接口）";
  const opsCell = `
    <td style="white-space:nowrap;">
      <button
        type="button"
        class="ghost"
        style="padding:6px 10px;font-size:12px;margin-right:6px;"
        data-action="publish-post-device"
        data-device="${deviceId}"
        data-alias="${alias}"
        ${publishOneDisabled}
        title="${publishOneTitle}"
      >
        发布作品
      </button>
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
      <td>${info?.alias || ""}</td>
      ${groupCell}
      ${ownerCell}
      ${smartCell}
      ${templateCell}
      <td><span class="badge ${statusClass}">${info?.status || "unknown"}</span></td>
      <td>${info?.pending_start ? pendingBadge : pending}</td>
      ${autoStartCell}
      ${opsCell}
    </tr>
  `;
}

function renderDevices(devices, options = {}) {
  const prevSelected = selectedDeviceIds();
  deviceTable.innerHTML = "";
  const aliasFilter = aliasFilterInput.value.trim().toLowerCase();
  const isAdmin = currentUser?.role === "admin";
  const baseTemplateKeys = Object.keys(currentTemplatesByName || {}).sort((a, b) => a.localeCompare(b));
  const aliasRefKeys = Array.from(collectTemplateRefsFromAliasMaps());
  const templateNames = [...new Set([...baseTemplateKeys, ...aliasRefKeys])].sort((a, b) => a.localeCompare(b));
  if (!templateNames.length) templateNames.push("default");
  // 未命中 alias 映射时，按服务端 active_template 回显，避免“看起来已绑定”的假象。
  const fallbackTemplate = String(currentConfigTemplate || templateNames[0] || "default").trim() || "default";
  const visibleRows = Object.entries(devices)
    .filter(([, info]) => {
      if (!aliasFilter) return true;
      return String(info?.alias || "").toLowerCase().includes(aliasFilter);
    })
    .map(([deviceId, info]) => ({ deviceId, info }))
    .sort((a, b) => {
      const aAlias = String(a.info?.alias || "").trim().toLowerCase();
      const bAlias = String(b.info?.alias || "").trim().toLowerCase();
      return aAlias.localeCompare(bAlias);
    });
  const rowHtml = visibleRows.map(({ deviceId, info }) => renderDeviceRow(deviceId, info, {
    isAdmin,
    templateNames,
    fallbackTemplate,
  }));
  if (!rowHtml.length) {
    deviceTable.innerHTML = `<tr><td colspan="${isAdmin ? 11 : 10}" style="padding:10px;color:#666;">暂无匹配设备</td></tr>`;
  } else {
    deviceTable.innerHTML = rowHtml.join("");
  }
  applyAutoSwitchManualOpsUi();
  if (!options.skipSmartPreview) {
    void refreshSmartTemplatePreviews(visibleRows);
  }
  updateOverviewPagerUi();
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
      setStatus("正在更新单设备自动起号策略...", "info");
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
        setStatus("已更新单设备自动起号策略", "success");
      } catch (error) {
        setStatus(error.message || "更新失败", "error");
      }
    });
  });
  deviceTable.querySelectorAll("button[data-action='publish-post-device']").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      const el = e.currentTarget;
      const did = String(el?.dataset?.device || "").trim();
      const alias = String(el?.dataset?.alias || "").trim();
      if (!guardAutoSwitchManualOps("发作品", did)) return;
      if (!did || !alias || el.disabled) return;
      const oldText = String(el.textContent || "发布作品").trim() || "发布作品";
      el.disabled = true;
      el.textContent = "执行中…";
      try {
        appendPublishPostBatchLog(
          `======== 发作品单台 ${new Date().toLocaleString()} · ${alias} ========\n下发中…`,
          true,
        );
        setStatus(`正在下发发作品入口：${alias}…`, "info");
        const resp = await apiPublishPostStart({
          device_id: did,
          alias,
          package_name: "com.zhiliaoapp.musically",
        });
        const opId = String(resp?.op_id || "").trim();
        if (!opId) {
          throw new Error(resp?.msg || "未返回 op_id");
        }
        appendPublishPostBatchLog(`⏳ ${alias}：已下发，等待最终结果…`);
        setStatus(`已下发 ${alias}，等待发作品最终结果…`, "info");
        const result = await waitForPublishPostFinalResult(opId, 900000);
        const line = formatPublishPostFinalLine(alias, result, null);
        appendPublishPostBatchLog(
          `======== 发作品最终结果 ${new Date().toLocaleString()} ========\n${line}`,
          true,
        );
        await loadOverview();
        bindDeviceSelection();
        const data = result?.data && typeof result.data === "object" ? result.data : {};
        const ok = result?.status === "success" && Boolean(data.publish_post_completed);
        setStatus(ok ? `发作品成功：${alias}` : `发作品失败：${alias}`, ok ? "success" : "error");
      } catch (error) {
        const msg = error.message || "发作品失败";
        appendPublishPostBatchLog(
          `======== 发作品最终结果 ${new Date().toLocaleString()} ========\n❌ ${alias}：${msg}`,
          true,
        );
        setStatus(msg, "error");
      } finally {
        el.disabled = false;
        el.textContent = oldText;
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
  deviceTable.querySelectorAll("select.device-group-select").forEach((selectEl) => {
    selectEl.addEventListener("change", async (e) => {
      const target = e.currentTarget;
      const deviceId = String(target?.dataset?.device || "").trim();
      const picked = String(target?.value || "").trim();
      const groupName = picked === "__ungrouped__" ? "" : picked;
      const prevGroup = String(latestDevices?.[deviceId]?.device_group || "").trim();
      const prevValue = prevGroup || "__ungrouped__";
      if (!deviceId) return;
      try {
        const data = await request("/api/admin/devices/set_group", {
          method: "POST",
          body: JSON.stringify({ device_group: groupName, device_ids: [deviceId] }),
        });
        if (latestDevices?.[deviceId]) latestDevices[deviceId].device_group = groupName;
        if (groupName && !customDeviceGroupNames.includes(groupName)) {
          customDeviceGroupNames.push(groupName);
          persistCustomDeviceGroupNames();
        }
        void refreshDeviceGroupsDetail();
        setStatus(data?.msg || `设备已设为「${displayGroupName(picked)}」`, "success");
      } catch (error) {
        setStatus(error.message || "更新设备分组失败", "error");
        target.value = prevValue;
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
      const modeBucket0 = aliasTemplateByMode[currentRunMode] || {};
      const prevWasExplicit = Object.prototype.hasOwnProperty.call(modeBucket0, aliasKey);
      const prevExplicitTemplate = prevWasExplicit ? modeBucket0[aliasKey] : undefined;
      if (!aliasTemplateByMode[currentRunMode]) aliasTemplateByMode[currentRunMode] = {};
      aliasTemplateByMode[currentRunMode][aliasKey] = nextTemplate;
      try {
        const data = await request("/api/admin/alias_template/select", {
          method: "POST",
          body: JSON.stringify({
            alias,
            mode: currentRunMode,
            template_name: nextTemplate,
            owner_username: ownerUsername || undefined,
            tiktok_username: "",
          }),
        });
        aliasTemplateByMode = (data?.alias_template_by_mode && typeof data.alias_template_by_mode === "object")
          ? data.alias_template_by_mode
          : aliasTemplateByMode;
        const stateEl = target?.closest("td")?.querySelector(".js-template-bind-state");
        if (stateEl) {
          stateEl.textContent = "已绑定(显式)";
          stateEl.classList.remove("offline", "pending");
          stateEl.classList.add("online");
        }
        setStatus(`别名 ${alias} 已显式绑定模板：${templateDisplayName(nextTemplate, currentTemplateMeta)}`, "success");
      } catch (error) {
        setStatus(error.message || "更新别名模板失败", "error");
        const modeBucket = aliasTemplateByMode[currentRunMode];
        if (!modeBucket) aliasTemplateByMode[currentRunMode] = {};
        if (prevWasExplicit) {
          aliasTemplateByMode[currentRunMode][aliasKey] = prevExplicitTemplate;
        } else {
          delete aliasTemplateByMode[currentRunMode][aliasKey];
        }
        const rollbackTpl = prevWasExplicit
          ? String(prevExplicitTemplate || "").trim()
          : fallbackTemplate;
        target.value = rollbackTpl || fallbackTemplate;
      }
    });
  });
  deviceTable.querySelectorAll("button.js-smart-template-toggle").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      const el = e.currentTarget;
      const alias = String(el?.dataset?.alias || "").trim();
      const ownerUsername = String(el?.dataset?.owner || "").trim();
      if (!alias || el.disabled) return;
      const aliasKey = alias.toLowerCase();
      if (!isSmartTemplateModeActive()) {
        setStatus("智能模板暂仅支持涨粉模式", "warning");
        return;
      }
      const currentlyOn = isAliasSmartEnabledForOwner(ownerUsername, aliasKey);
      const nextEnabled = !currentlyOn;
      const oldText = String(el.textContent || "").trim();
      el.disabled = true;
      el.textContent = "处理中…";
      try {
        const data = await request("/api/admin/alias_smart_template/toggle", {
          method: "POST",
          body: JSON.stringify({
            alias,
            enabled: nextEnabled,
            mode: SMART_TEMPLATE_MODE,
            owner_username: ownerUsername || undefined,
          }),
        });
        if (ownerUsername === String(currentUser?.username || "").trim()) {
          aliasSmartTemplateByMode = (data?.alias_smart_template_by_mode
            && typeof data.alias_smart_template_by_mode === "object")
            ? data.alias_smart_template_by_mode
            : aliasSmartTemplateByMode;
        }
        const key = smartPreviewKey(ownerUsername, SMART_TEMPLATE_MODE, aliasKey);
        smartTemplatePreviewCache[key] = {
          enabled: nextEnabled,
          resolved_template: data?.resolved_template || "",
          resolution: data?.resolution || {},
        };
        renderDevices(latestDevices, { skipSmartPreview: true });
        bindDeviceSelection();
        setStatus(data?.msg || (nextEnabled ? "已启用智能模板" : "已关闭智能模板"), "success");
      } catch (error) {
        setStatus(error.message || "切换智能模板失败", "error");
        el.textContent = oldText;
        el.disabled = false;
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

/** 与后端 BASE_DEFAULT_CONFIG 对齐：多行文案类控件在「配置对象缺键」时不得只靠 input 的 HTML defaultValue，否则易误回退为旧默认。 */
const TEXT_LIST_CONFIG_FORM_KEYS = [
  "comment_texts",
  "mutual_comment_texts",
  "mutual_traffic_target_texts",
  "profile_edit_bio_texts",
];

const MUTUAL_TRAFFIC_ACTION_CHECKBOX_NAMES = [
  "mutual_traffic_comment",
  "mutual_traffic_author_follow",
  "mutual_traffic_followers_follow",
];

function mutualTrafficActionCheckboxEls() {
  if (!configForm) return { followOnlyEl: null, actionEls: [] };
  const followOnlyEl = configForm.querySelector("[name='mutual_traffic_follow_only']");
  const actionEls = MUTUAL_TRAFFIC_ACTION_CHECKBOX_NAMES.map((name) =>
    configForm.querySelector(`[name='${name}']`)
  ).filter(Boolean);
  return { followOnlyEl, actionEls };
}

function syncNoRestUi() {
  const noRestEl = configForm?.querySelector("[name='no_rest']");
  if (!noRestEl) return;
  const disabled = Boolean(noRestEl.checked);
  ["break_min", "break_max"].forEach((name) => {
    const input = configForm?.querySelector(`[name='${name}']`);
    if (!input) return;
    input.disabled = disabled;
    input.closest(".range-half")?.classList.toggle("checkbox-inline--disabled", disabled);
  });
}

function setupNoRestToggleListeners() {
  if (!configForm || configForm.dataset.noRestBound === "1") return;
  configForm.dataset.noRestBound = "1";
  const noRestEl = configForm.querySelector("[name='no_rest']");
  if (!noRestEl) return;
  noRestEl.addEventListener("change", syncNoRestUi);
  syncNoRestUi();
}

/** 「互点-仅关注」与评论/作者区/Followers 三项互斥。 */
function syncMutualTrafficActionMutex(changedInput = null) {
  const { followOnlyEl, actionEls } = mutualTrafficActionCheckboxEls();
  if (!followOnlyEl || !actionEls.length) return;

  if (changedInput === followOnlyEl && followOnlyEl.checked) {
    actionEls.forEach((el) => {
      el.checked = false;
    });
  } else if (changedInput && actionEls.includes(changedInput) && changedInput.checked) {
    followOnlyEl.checked = false;
  } else if (followOnlyEl.checked && actionEls.some((el) => el.checked)) {
    actionEls.forEach((el) => {
      el.checked = false;
    });
  } else if (actionEls.some((el) => el.checked) && followOnlyEl.checked) {
    followOnlyEl.checked = false;
  }

  const followOnlyActive = followOnlyEl.checked;
  const anyActionActive = actionEls.some((el) => el.checked);
  actionEls.forEach((el) => {
    el.disabled = followOnlyActive;
    el.closest("label")?.classList.toggle("checkbox-inline--disabled", followOnlyActive);
  });
  followOnlyEl.disabled = anyActionActive;
  followOnlyEl.closest("label")?.classList.toggle("checkbox-inline--disabled", anyActionActive);
}

function setupMutualTrafficActionMutexListeners() {
  if (!configForm || configForm.dataset.mutualTrafficMutexBound === "1") return;
  configForm.dataset.mutualTrafficMutexBound = "1";
  const { followOnlyEl, actionEls } = mutualTrafficActionCheckboxEls();
  const all = [followOnlyEl, ...actionEls].filter(Boolean);
  all.forEach((el) => {
    el.addEventListener("change", () => syncMutualTrafficActionMutex(el));
  });
  syncMutualTrafficActionMutex();
}

function isMutualFollowOnlyInTemplateConfig(templateName, tiktokUsername = "") {
  const tmpl =
    String(templateName || "").trim() ||
    resolveEffectiveDeviceTemplateUI("", tiktokUsername).selectedTemplate;
  const cfg = currentTemplatesByName?.[tmpl];
  return Boolean(cfg && typeof cfg === "object" && cfg.mutual_traffic_follow_only);
}

function fillConfigForm(config, mode = currentRunMode) {
  if (!configForm) return;
  const modeSelect = document.getElementById("configModeSelect");
  if (modeSelect) modeSelect.value = mode;
  const cfg = config && typeof config === "object" ? config : {};
  // 先回到页面默认值，避免不同来源配置仅返回部分字段时残留旧值。
  configForm.querySelectorAll("input[name], textarea[name], select[name]").forEach((input) => {
    if (TEXT_LIST_CONFIG_FORM_KEYS.includes(input.name)) {
      return;
    }
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
  Object.entries(cfg).forEach(([key, value]) => {
    const input = configForm.querySelector(`[name='${key}']`);
    if (!input) return;
    if (input.type === "checkbox") {
      input.checked = Boolean(value);
      return;
    }
    if (key === "comment_texts" || key === "mutual_comment_texts" || key === "mutual_traffic_target_texts" || key === "profile_edit_bio_texts") {
      const lines = Array.isArray(value)
        ? value.map((s) => String(s).trim()).filter(Boolean)
        : [];
      if (lines.length) {
        input.value = lines.join("\n");
      } else {
        try {
          const backupKey =
            key === "mutual_comment_texts"
              ? MUTUAL_COMMENT_TEXTS_LS_KEY
              : key === "mutual_traffic_target_texts"
                ? MUTUAL_TRAFFIC_TARGET_TEXTS_LS_KEY
                : key === "profile_edit_bio_texts"
                  ? PROFILE_EDIT_BIO_TEXTS_LS_KEY
                  : COMMENT_TEXTS_LS_KEY;
          const backup = localStorage.getItem(backupKey);
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
  // 配置快照未带某多行键（旧库、共享模板直拷等）时，用当前模式默认 payload 回填，避免停在 HTML 内联默认文案上。
  const modeKey = mode === "live" || mode === "direct" ? mode : "growth";
  const payloadDefaults = MODE_DEFAULT_CONFIG_PAYLOADS[modeKey] || MODE_DEFAULT_CONFIG_PAYLOADS.growth;
  for (const key of TEXT_LIST_CONFIG_FORM_KEYS) {
    if (Object.prototype.hasOwnProperty.call(cfg, key) && cfg[key] != null) continue;
    const input = configForm.querySelector(`[name='${key}']`);
    if (!input) continue;
    const def = payloadDefaults[key];
    input.value = Array.isArray(def) && def.length ? def.join("\n") : "";
  }
  syncCvQualityFilterInputsFromConfig(cfg);
  syncMutualTrafficActionMutex();
  syncNoRestUi();
}

function persistCommentTextsToLocal() {
  const ta = configForm?.querySelector("[name='comment_texts']");
  const mutualTa = configForm?.querySelector("[name='mutual_comment_texts']");
  const mutualTrafficTa = configForm?.querySelector("[name='mutual_traffic_target_texts']");
  const profileBioTa = configForm?.querySelector("[name='profile_edit_bio_texts']");
  try {
    if (ta) localStorage.setItem(COMMENT_TEXTS_LS_KEY, ta.value);
    if (mutualTa) localStorage.setItem(MUTUAL_COMMENT_TEXTS_LS_KEY, mutualTa.value);
    if (mutualTrafficTa) localStorage.setItem(MUTUAL_TRAFFIC_TARGET_TEXTS_LS_KEY, mutualTrafficTa.value);
    if (profileBioTa) localStorage.setItem(PROFILE_EDIT_BIO_TEXTS_LS_KEY, profileBioTa.value);
  } catch (_) {}
}

function selectedDeviceIdsByStatus(statuses = []) {
  const allow = new Set(statuses);
  return selectedDeviceIds().filter((id) => allow.has(latestDevices?.[id]?.status));
}

function getActiveDeviceGroupFilter() {
  const fromSelect = String(deviceGroupSelect?.value ?? pcServiceGroupSelect?.value ?? "").trim();
  const stored = String(localStorage.getItem(DEVICE_GROUP_FILTER_KEY) || "").trim();
  const raw = (deviceGroupSelect || pcServiceGroupSelect) ? fromSelect : stored;
  return raw === "none" ? "" : raw;
}

function syncDeviceGroupFilterSelects(value = getActiveDeviceGroupFilter()) {
  currentDeviceGroupFilter = String(value || "").trim();
  [deviceGroupSelect, pcServiceGroupSelect].forEach((selectEl) => {
    if (selectEl && selectEl.value !== currentDeviceGroupFilter) {
      selectEl.value = currentDeviceGroupFilter;
    }
  });
  try {
    localStorage.setItem(DEVICE_GROUP_FILTER_KEY, currentDeviceGroupFilter);
  } catch (_) {}
}

function populateDeviceGroupFilterOptions(groupNames = []) {
  deviceGroupNames = Array.isArray(groupNames) ? groupNames.filter(Boolean) : [];
  const buildOptions = () => {
    const base = [
      '<option value="">全部分组</option>',
      '<option value="__ungrouped__">未分组</option>',
    ];
    deviceGroupNames.forEach((name) => {
      base.push(`<option value="${escapeHtml(name)}">${escapeHtml(name)}</option>`);
    });
    return base.join("");
  };
  const html = buildOptions();
  const prev = currentDeviceGroupFilter;
  if (deviceGroupSelect) deviceGroupSelect.innerHTML = html;
  if (pcServiceGroupSelect) pcServiceGroupSelect.innerHTML = html;
  syncDeviceGroupFilterSelects(prev);
}

function clampPageSize(raw, fallback, maxSize) {
  const n = Number.parseInt(String(raw ?? "").trim(), 10);
  if (!Number.isFinite(n) || n < 1) return fallback;
  return Math.min(n, maxSize);
}

function getOverviewPageSize() {
  const fromInput = overviewPageSizeInput?.value;
  const fromStorage = localStorage.getItem(OVERVIEW_PAGE_SIZE_KEY);
  return clampPageSize(fromInput ?? fromStorage, DEFAULT_OVERVIEW_PAGE_SIZE, MAX_OVERVIEW_PAGE_SIZE);
}

function getBackupArchivePageSize() {
  const fromInput = backupArchivePageSizeInput?.value;
  const fromStorage = localStorage.getItem(BACKUP_ARCHIVE_PAGE_SIZE_KEY);
  return clampPageSize(fromInput ?? fromStorage, DEFAULT_BACKUP_ARCHIVE_PAGE_SIZE, MAX_BACKUP_ARCHIVE_PAGE_SIZE);
}

function getAccountRecordsPageSize() {
  const fromInput = accountRecordsPageSizeInput?.value;
  const fromStorage = localStorage.getItem(ACCOUNT_RECORDS_PAGE_SIZE_KEY);
  return clampPageSize(fromInput ?? fromStorage, DEFAULT_ACCOUNT_RECORDS_PAGE_SIZE, MAX_ACCOUNT_RECORDS_PAGE_SIZE);
}

function persistOverviewPageSize() {
  const size = getOverviewPageSize();
  try {
    localStorage.setItem(OVERVIEW_PAGE_SIZE_KEY, String(size));
  } catch (_) {}
  if (overviewPageSizeInput && String(overviewPageSizeInput.value) !== String(size)) {
    overviewPageSizeInput.value = String(size);
  }
  return size;
}

function persistBackupArchivePageSize() {
  const size = getBackupArchivePageSize();
  try {
    localStorage.setItem(BACKUP_ARCHIVE_PAGE_SIZE_KEY, String(size));
  } catch (_) {}
  if (backupArchivePageSizeInput && String(backupArchivePageSizeInput.value) !== String(size)) {
    backupArchivePageSizeInput.value = String(size);
  }
  return size;
}

function persistAccountRecordsPageSize() {
  const size = getAccountRecordsPageSize();
  try {
    localStorage.setItem(ACCOUNT_RECORDS_PAGE_SIZE_KEY, String(size));
  } catch (_) {}
  if (accountRecordsPageSizeInput && String(accountRecordsPageSizeInput.value) !== String(size)) {
    accountRecordsPageSizeInput.value = String(size);
  }
  return size;
}

function onOverviewPageSizeChanged() {
  persistOverviewPageSize();
  currentOverviewPage = 1;
  void loadOverview();
}

function onBackupArchivePageSizeChanged() {
  persistBackupArchivePageSize();
  currentBackupArchivePage = 1;
  void loadBackupArchiveRecords();
}

function onAccountRecordsPageSizeChanged() {
  persistAccountRecordsPageSize();
  currentAccountRecordsPage = 1;
  renderAccountEditorRows();
}

function updateAccountRecordsBatchActionBtns() {
  const count = accountRecordsSelectedRowIndexes.size;
  if (accountRecordsBatchDeleteBtn) {
    accountRecordsBatchDeleteBtn.disabled = count <= 0;
    accountRecordsBatchDeleteBtn.textContent = count > 0 ? `批量删除 (${count})` : "批量删除";
  }
  if (accountRecordsBatchProfileBtn) {
    accountRecordsBatchProfileBtn.disabled = count <= 0;
    accountRecordsBatchProfileBtn.textContent = count > 0 ? `批量改资料 (${count})` : "批量改资料";
  }
}

function updateAccountRecordsBatchDeleteBtn() {
  updateAccountRecordsBatchActionBtns();
}

function setAccountRecordRowSelected(rowIndex, checked) {
  const ri = Number(rowIndex);
  if (!Number.isFinite(ri) || ri < 0) return;
  if (checked) accountRecordsSelectedRowIndexes.add(ri);
  else accountRecordsSelectedRowIndexes.delete(ri);
  updateAccountRecordsBatchDeleteBtn();
}

function clearAccountRecordsSelection() {
  accountRecordsSelectedRowIndexes.clear();
  updateAccountRecordsBatchDeleteBtn();
}

function getVisibleAccountRecordPageRows() {
  const indexedRows = (accountEditorRows || []).map((row, idx) => ({ row, idx }));
  let visibleRows = accountFilterUnassignedOnly
    ? indexedRows.filter(({ row }) => isAccountAliasUnassigned(row))
    : indexedRows;
  if (accountRecordsSearchQuery) {
    visibleRows = visibleRows.filter(({ row }) => accountRecordMatchesSearch(row));
  }
  const pageSize = getAccountRecordsPageSize();
  const start = (currentAccountRecordsPage - 1) * pageSize;
  return visibleRows.slice(start, start + pageSize);
}

function syncAccountRecordsPageSelectAllCheckbox() {
  if (!accountEditorListEl) return;
  const selectAllEl = accountEditorListEl.querySelector(".account-records-select-all");
  if (!(selectAllEl instanceof HTMLInputElement)) return;
  const pageRows = getVisibleAccountRecordPageRows();
  if (!pageRows.length) {
    selectAllEl.checked = false;
    selectAllEl.indeterminate = false;
    return;
  }
  const selectedOnPage = pageRows.filter(({ row, idx }) => {
    const ri = resolveAccountRowIndex(row, idx);
    return accountRecordsSelectedRowIndexes.has(ri);
  }).length;
  selectAllEl.checked = selectedOnPage === pageRows.length;
  selectAllEl.indeterminate = selectedOnPage > 0 && selectedOnPage < pageRows.length;
}

function buildAccountRecordDeletePayload(row, arrayIndex) {
  const rowIndex = resolveAccountRowIndex(row, arrayIndex);
  const username = String(row?.username || "").trim();
  const assignedAlias = normalizeAliasValue(row?.assigned_alias)
    || backupAliasByUsername.get(username.toLowerCase())
    || "";
  return { row_index: rowIndex, username, assigned_alias: assignedAlias };
}

async function refreshEditorTablesAfterAccountDelete() {
  try {
    await loadAccountEditorRows();
  } catch (reloadError) {
    setStatus(`账号已删除，但刷新列表失败：${humanizeRequestErrorMessage(reloadError)}`, "warning");
  }
  renderAccountEditorRows();
  renderDeviceEditorRows();
  const backupActive = document.getElementById("backup-section")?.classList.contains("active");
  if (backupActive) {
    await loadBackupArchiveRecords();
  }
}

function accountRecordStableDeleteKey(row, arrayIndex) {
  const username = String(row?.username || "").trim().toLowerCase();
  const email = String(row?.email || "").trim().toLowerCase();
  if (username) return `u:${username}|e:${email}`;
  return `ri:${resolveAccountRowIndex(row, arrayIndex)}`;
}

function findAccountEditorRowByStableKey(key) {
  for (let idx = 0; idx < (accountEditorRows || []).length; idx += 1) {
    const row = accountEditorRows[idx];
    if (accountRecordStableDeleteKey(row, idx) === key) return { row, idx };
  }
  return null;
}

function collectSelectedAccountDeleteSnapshots() {
  return (accountEditorRows || [])
    .map((row, idx) => ({ row, idx, rowIndex: resolveAccountRowIndex(row, idx) }))
    .filter(({ rowIndex }) => accountRecordsSelectedRowIndexes.has(rowIndex))
    .map(({ row, idx }) => ({
      key: accountRecordStableDeleteKey(row, idx),
      username: String(row?.username || "").trim(),
      assignedAlias: buildAccountRecordDeletePayload(row, idx).assigned_alias
        || getEffectiveAccountAssignedAlias(row),
    }));
}

function collectSelectedAccountBasicProfileSnapshots() {
  return (accountEditorRows || [])
    .map((row, idx) => ({ row, idx, rowIndex: resolveAccountRowIndex(row, idx) }))
    .filter(({ rowIndex }) => accountRecordsSelectedRowIndexes.has(rowIndex))
    .map(({ row, idx }) => ({
      key: accountRecordStableDeleteKey(row, idx),
      row_index: resolveAccountRowIndex(row, idx),
      username: String(row?.username || "").trim(),
      currentValue: normalizeBasicProfileModifiedField(row?.basic_profile_modified),
    }));
}

async function batchSetSelectedAccountBasicProfile() {
  if (!accountRecordsSelectedRowIndexes.size) {
    setStatus("请先勾选要修改的账号记录", "warning");
    return;
  }
  const targetValue = normalizeBasicProfileModifiedField(accountRecordsBatchProfileSelect?.value);
  let pendingSnapshots = collectSelectedAccountBasicProfileSnapshots();
  if (!pendingSnapshots.length) {
    clearAccountRecordsSelection();
    renderAccountEditorRows();
    setStatus("勾选的记录已不存在，请刷新后重试", "warning");
    return;
  }
  const needUpdateCount = pendingSnapshots.filter((item) => item.currentValue !== targetValue).length;
  const confirmLines = [
    `确认将 ${pendingSnapshots.length} 条账号记录的「资料」批量设为「${targetValue}」？`,
  ];
  if (needUpdateCount < pendingSnapshots.length) {
    confirmLines.push(`其中 ${pendingSnapshots.length - needUpdateCount} 条已是「${targetValue}」，将跳过`);
  }
  const previewNames = pendingSnapshots
    .map((item) => item.username)
    .filter(Boolean)
    .slice(0, 8);
  if (previewNames.length) {
    confirmLines.push(`账号：${previewNames.join("、")}${pendingSnapshots.length > previewNames.length ? " …" : ""}`);
  }
  if (!window.confirm(confirmLines.join("\n"))) return;

  if (accountRecordsBatchProfileBtn) accountRecordsBatchProfileBtn.disabled = true;
  const totalCount = pendingSnapshots.length;
  let processedCount = 0;
  setStatus(`正在批量修改 ${totalCount} 条账号记录的「资料」...`, "info");
  let updatedCount = 0;
  let unchangedCount = 0;
  let notFoundCount = 0;
  let failedBatches = 0;
  try {
    while (pendingSnapshots.length) {
      const batch = pendingSnapshots.slice(0, ACCOUNT_RECORDS_BATCH_SET_BASIC_PROFILE_MAX);
      pendingSnapshots = pendingSnapshots.slice(ACCOUNT_RECORDS_BATCH_SET_BASIC_PROFILE_MAX);
      setStatus(`正在批量修改「资料」… ${processedCount}/${totalCount}`, "info");

      try {
        const resp = await request(`${ACCOUNT_ROWS_API}/batch_set_basic_profile`, {
          method: "POST",
          body: JSON.stringify({
            basic_profile_modified: targetValue,
            items: batch.map(({ row_index, username }) => ({ row_index, username })),
          }),
        });
        updatedCount += Number(resp?.updated_count || 0);
        unchangedCount += Number(resp?.unchanged_count || 0);
        notFoundCount += Number(resp?.not_found_count || 0);
        processedCount += batch.length;
        const batchUpdated = Number(resp?.updated_count || 0);
        const batchUnchanged = Number(resp?.unchanged_count || 0);
        if (batchUpdated <= 0 && batchUnchanged <= 0) break;
      } catch (chunkError) {
        failedBatches += 1;
        if (updatedCount > 0 || unchangedCount > 0) break;
        throw chunkError;
      }
    }
    clearAccountRecordsSelection();
    await loadAccountEditorRows();
    renderAccountEditorRows();
    if (failedBatches > 0) {
      setStatus(
        `部分批次修改失败：已成功 ${updatedCount} 条，请刷新后检查剩余记录`,
        "warning",
      );
      return;
    }
    if (updatedCount <= 0) {
      if (notFoundCount > 0) {
        setStatus("所选记录已不存在", "warning");
      } else {
        setStatus(`所选记录「资料」已是「${targetValue}」`, "info");
      }
      return;
    }
    const parts = [`已批量将 ${updatedCount} 条账号记录「资料」设为「${targetValue}」`];
    if (unchangedCount > 0) parts.push(`${unchangedCount} 条无需修改`);
    if (notFoundCount > 0) parts.push(`${notFoundCount} 条已不存在`);
    setStatus(parts.join("，"), notFoundCount > 0 ? "warning" : "success");
  } catch (error) {
    await loadAccountEditorRows();
    renderAccountEditorRows();
    throw error;
  } finally {
    updateAccountRecordsBatchActionBtns();
  }
}

async function deleteSelectedAccountRecords() {
  if (!accountRecordsSelectedRowIndexes.size) {
    setStatus("请先勾选要删除的账号记录", "warning");
    return;
  }
  let pendingSnapshots = collectSelectedAccountDeleteSnapshots();
  if (!pendingSnapshots.length) {
    clearAccountRecordsSelection();
    renderAccountEditorRows();
    setStatus("勾选的记录已不存在，请刷新后重试", "warning");
    return;
  }
  const withAlias = pendingSnapshots.filter((item) => Boolean(item.assignedAlias));
  const confirmLines = [`确认批量删除 ${pendingSnapshots.length} 条账号记录？`];
  const previewNames = pendingSnapshots
    .map((item) => item.username)
    .filter(Boolean)
    .slice(0, 8);
  if (previewNames.length) {
    confirmLines.push(`账号：${previewNames.join("、")}${pendingSnapshots.length > previewNames.length ? " …" : ""}`);
  }
  if (withAlias.length) {
    confirmLines.push(`其中 ${withAlias.length} 条已分配别名，将同步删除设备备份档案中的对应数据`);
  }
  if (!window.confirm(confirmLines.join("\n"))) return;

  if (accountRecordsBatchDeleteBtn) accountRecordsBatchDeleteBtn.disabled = true;
  setStatus(`正在批量删除 ${pendingSnapshots.length} 条账号记录...`, "info");
  let deletedAccounts = 0;
  let deletedDeviceRows = 0;
  let notFoundCount = 0;
  let failedBatches = 0;
  try {
    while (pendingSnapshots.length) {
      await loadAccountEditorRows();
      pendingSnapshots = pendingSnapshots.filter((snap) => findAccountEditorRowByStableKey(snap.key));
      if (!pendingSnapshots.length) break;

      const batch = [];
      const batchKeys = [];
      for (const snap of pendingSnapshots) {
        if (batch.length >= ACCOUNT_RECORDS_BATCH_DELETE_MAX) break;
        const matched = findAccountEditorRowByStableKey(snap.key);
        if (!matched) continue;
        batch.push(buildAccountRecordDeletePayload(matched.row, matched.idx));
        batchKeys.push(snap.key);
      }
      if (!batch.length) break;

      try {
        const resp = await request(`${ACCOUNT_ROWS_API}/batch_delete`, {
          method: "POST",
          body: JSON.stringify({
            items: batch.map(({ row_index, username }) => ({ row_index, username })),
          }),
        });
        const batchDeleted = Number(resp?.deleted_accounts || 0);
        deletedAccounts += batchDeleted;
        deletedDeviceRows += Number(resp?.deleted_device_rows || 0);
        notFoundCount += Number(resp?.not_found_count || 0);
        if (batchDeleted <= 0) break;
        const deletedKeySet = new Set(batchKeys);
        pendingSnapshots = pendingSnapshots.filter((snap) => !deletedKeySet.has(snap.key));
      } catch (chunkError) {
        failedBatches += 1;
        if (deletedAccounts > 0) break;
        throw chunkError;
      }
    }
    clearAccountRecordsSelection();
    await refreshEditorTablesAfterAccountDelete();
    if (failedBatches > 0) {
      setStatus(
        `部分批次删除失败：已成功 ${deletedAccounts} 条，请刷新后检查剩余记录`,
        "warning",
      );
      return;
    }
    if (deletedAccounts <= 0) {
      setStatus(notFoundCount > 0 ? "所选记录已不存在" : "未删除任何记录", "warning");
      return;
    }
    const parts = [`已批量删除 ${deletedAccounts} 条账号记录`];
    if (deletedDeviceRows > 0) parts.push(`同步清理 ${deletedDeviceRows} 条设备备份档案`);
    if (notFoundCount > 0) parts.push(`${notFoundCount} 条已不存在`);
    setStatus(parts.join("，"), notFoundCount > 0 ? "warning" : "success");
  } catch (error) {
    await refreshEditorTablesAfterAccountDelete();
    throw error;
  } finally {
    updateAccountRecordsBatchDeleteBtn();
  }
}

function updateAccountRecordsPagerUi() {
  const pageSize = getAccountRecordsPageSize();
  const totalPages = Math.max(1, Math.ceil(accountRecordsFilteredTotal / pageSize));
  if (currentAccountRecordsPage > totalPages) currentAccountRecordsPage = totalPages;
  if (currentAccountRecordsPage < 1) currentAccountRecordsPage = 1;
  if (accountRecordsPagerEl) {
    accountRecordsPagerEl.textContent = `第 ${currentAccountRecordsPage} / ${totalPages} 页 | 每页 ${pageSize} 条 | 共 ${accountRecordsFilteredTotal} 条`;
  }
  if (accountRecordsPrevPageBtn) accountRecordsPrevPageBtn.disabled = currentAccountRecordsPage <= 1;
  if (accountRecordsNextPageBtn) accountRecordsNextPageBtn.disabled = currentAccountRecordsPage >= totalPages;
}

function overviewQueryString() {
  const pageSize = persistOverviewPageSize();
  const params = new URLSearchParams({
    page: String(currentOverviewPage),
    page_size: String(pageSize),
  });
  const group = getActiveDeviceGroupFilter();
  if (group) params.set("device_group", group);
  return params.toString();
}

function updateOverviewPagerUi() {
  const pageSize = getOverviewPageSize();
  const totalPages = Math.max(1, Math.ceil(overviewDevicesTotal / pageSize));
  if (currentOverviewPage > totalPages) currentOverviewPage = totalPages;
  if (currentOverviewPage < 1) currentOverviewPage = 1;
  if (overviewPageInfoEl) {
    overviewPageInfoEl.textContent = `第 ${currentOverviewPage} / ${totalPages} 页 | 每页 ${pageSize} 条 | 共 ${overviewDevicesTotal} 台`;
  }
  if (overviewPrevPageBtn) overviewPrevPageBtn.disabled = currentOverviewPage <= 1;
  if (overviewNextPageBtn) overviewNextPageBtn.disabled = currentOverviewPage >= totalPages;
}

function updateBackupArchivePagerUi() {
  const pageSize = getBackupArchivePageSize();
  const totalPages = Math.max(1, Math.ceil(backupArchiveAliasTotal / pageSize));
  if (currentBackupArchivePage > totalPages) currentBackupArchivePage = totalPages;
  if (currentBackupArchivePage < 1) currentBackupArchivePage = 1;
  if (deviceAliasGroupsPagerEl) {
    deviceAliasGroupsPagerEl.textContent = `第 ${currentBackupArchivePage} / ${totalPages} 页 | 每页 ${pageSize} 组 | 共 ${backupArchiveAliasTotal} 组`;
  }
  if (backupArchivePrevPageBtn) backupArchivePrevPageBtn.disabled = currentBackupArchivePage <= 1;
  if (backupArchiveNextPageBtn) backupArchiveNextPageBtn.disabled = currentBackupArchivePage >= totalPages;
}

function updateStartHint() {
  const total = overviewDevicesTotal || Object.keys(latestDevices || {}).length;
  const selected = selectedDeviceIds();
  const selectedRunnable = selectedDeviceIdsByStatus(["running", "error", "paused"]);
  const groupLabel = !currentDeviceGroupFilter
    ? "全部分组"
    : currentDeviceGroupFilter === "__ungrouped__"
      ? "未分组"
      : currentDeviceGroupFilter;

  if (!total) {
    startHintEl.textContent = "暂无设备。";
  } else if (!selected.length) {
    startHintEl.textContent = `当前筛选：${groupLabel}；请选择设备：任意状态可启动，运行中/异常可停止。`;
  } else {
    startHintEl.textContent = `当前筛选：${groupLabel}；已选 ${selected.length} 台（可启动 ${selected.length}，可停止 ${selectedRunnable.length}）`;
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
    checkbox.addEventListener("change", () => {
      updateStartHint();
      if (!(deviceLogDeviceSelect?.value || "").trim()) {
        void refreshVisibleDeviceLogs({ requestPull: false });
      }
    });
  });
  updateStartHint();
}

function selectedDeviceIds() {
  return Array.from(document.querySelectorAll(".device-check:checked"))
    .map((item) => item.dataset.device)
    .filter(Boolean);
}

function displayGroupName(value) {
  const name = String(value || "").trim();
  return !name || name === "__ungrouped__" ? "未分组" : name;
}

function loadCustomDeviceGroupNames() {
  try {
    const raw = localStorage.getItem(DEVICE_GROUP_CUSTOM_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.map((x) => String(x || "").trim()).filter(Boolean) : [];
  } catch (_) {
    return [];
  }
}

function persistCustomDeviceGroupNames() {
  try {
    localStorage.setItem(DEVICE_GROUP_CUSTOM_KEY, JSON.stringify(customDeviceGroupNames || []));
  } catch (_) {}
}

function getModalGroupOptions() {
  const names = new Set(["__ungrouped__"]);
  (deviceGroupNames || []).forEach((n) => names.add(String(n).trim()));
  (customDeviceGroupNames || []).forEach((n) => names.add(String(n).trim()));
  return Array.from(names).sort((a, b) => {
    if (a === "__ungrouped__") return -1;
    if (b === "__ungrouped__") return 1;
    return a.localeCompare(b);
  });
}

function getTableGroupOptions(currentGroupName = "") {
  const names = new Set(getModalGroupOptions());
  const current = String(currentGroupName || "").trim();
  if (current) names.add(current);
  return Array.from(names).sort((a, b) => {
    if (a === "__ungrouped__") return -1;
    if (b === "__ungrouped__") return 1;
    return a.localeCompare(b);
  });
}

function setGroupManageMessage(message, type = "info") {
  if (!groupManageMessageEl) return;
  groupManageMessageEl.textContent = String(message || "");
  groupManageMessageEl.dataset.type = type;
}

function renderGroupMoveSelect(selectedValue = "__ungrouped__") {
  if (!groupMoveSelectEl) return;
  const options = getModalGroupOptions();
  groupMoveSelectEl.innerHTML = options
    .map((name) => `<option value="${escapeHtml(name)}">${escapeHtml(displayGroupName(name))}</option>`)
    .join("");
  const normalized = options.includes(selectedValue) ? selectedValue : "__ungrouped__";
  groupMoveSelectEl.value = normalized;
}

function renderGroupManagerList(items = []) {
  if (!groupManagerListEl) return;
  const rows = Array.isArray(items) ? items : [];
  if (!rows.length) {
    groupManagerListEl.innerHTML = '<div class="hint">暂无分组数据</div>';
    if (groupManagerSummaryEl) groupManagerSummaryEl.textContent = "当前没有任何分组。";
    return;
  }
  const totalAliases = rows.reduce((sum, row) => sum + Number(row?.count || 0), 0);
  if (groupManagerSummaryEl) {
    groupManagerSummaryEl.textContent = `共 ${rows.length} 个分组，覆盖 ${totalAliases} 个别名。`;
  }
  groupManagerListEl.innerHTML = rows.map((row) => {
    const name = String(row?.group_name || "").trim() || "__ungrouped__";
    const aliases = Array.isArray(row?.aliases) ? row.aliases : [];
    return `
      <div class="panel-card" style="margin:0 0 10px 0;padding:12px;background:#f8faff;">
        <div style="display:flex;justify-content:space-between;gap:8px;align-items:center;">
          <strong>${escapeHtml(displayGroupName(name))}</strong>
          <span class="hint">${aliases.length} 个别名</span>
        </div>
        <div class="tag-list" style="margin-top:8px;">${(aliases.length ? aliases : ["无"]).map((a) => `<li>${escapeHtml(a)}</li>`).join("")}</div>
      </div>
    `;
  }).join("");
}

async function refreshDeviceGroupsDetail() {
  const data = await request("/api/admin/devices/groups_detail");
  const items = Array.isArray(data?.items) ? data.items : [];
  const names = Array.isArray(data?.group_names) ? data.group_names : [];
  populateDeviceGroupFilterOptions(names);
  renderGroupManagerList(items);
  renderGroupMoveSelect(groupMoveSelectEl?.value || "__ungrouped__");
  return items;
}

function getDeviceAliasesForSelection() {
  return Array.from(
    new Set(
      selectedDeviceIds()
        .map((id) => String(latestDevices?.[id]?.alias || "").trim())
        .filter(Boolean),
    ),
  );
}

async function openGroupMoveModal() {
  if (!groupMoveModalEl) return;
  customDeviceGroupNames = loadCustomDeviceGroupNames();
  const aliases = getDeviceAliasesForSelection();
  if (groupMoveSelectedAliasesEl) {
    groupMoveSelectedAliasesEl.textContent = aliases.length ? aliases.join("、") : "暂无选择（可在概览表格中勾选设备）";
  }
  try {
    await refreshDeviceGroupsDetail();
  } catch (error) {
    setGroupManageMessage(error.message || "加载分组失败", "error");
  }
  renderGroupMoveSelect();
  setGroupManageMessage("");
  groupMoveModalEl.classList.remove("hidden");
  groupMoveModalEl.setAttribute("aria-hidden", "false");
}

function closeGroupMoveModal() {
  if (!groupMoveModalEl) return;
  groupMoveModalEl.classList.add("hidden");
  groupMoveModalEl.setAttribute("aria-hidden", "true");
}

async function confirmGroupMove() {
  const deviceIds = selectedDeviceIds();
  if (!deviceIds.length) {
    setGroupManageMessage("请先在设备概览勾选要分组的设备", "warning");
    return;
  }
  const picked = String(groupMoveSelectEl?.value || "").trim();
  const groupName = picked === "__ungrouped__" ? "" : picked;
  try {
    setGroupManageMessage("正在写入设备分组...", "info");
    const data = await request("/api/admin/devices/set_group", {
      method: "POST",
      body: JSON.stringify({ device_group: groupName, device_ids: deviceIds }),
    });
    if (groupName && !customDeviceGroupNames.includes(groupName)) {
      customDeviceGroupNames.push(groupName);
      persistCustomDeviceGroupNames();
    }
    await refreshDeviceGroupsDetail();
    currentOverviewPage = 1;
    await loadOverview();
    if (document.querySelector(".panel.active")?.id === "backup-section") {
      await loadBackupArchiveRecords();
    }
    setGroupManageMessage(`已将 ${deviceIds.length} 台设备设为「${displayGroupName(picked)}」`, "success");
    setStatus(data?.msg || "分组已更新", "success");
  } catch (error) {
    setGroupManageMessage(error.message || "应用分组失败", "error");
  }
}

async function addDeviceAliasGroup() {
  const name = String(newGroupNameInput?.value || "").trim();
  if (!name) {
    setGroupManageMessage("请输入分组名称", "warning");
    return;
  }
  if (name === "__ungrouped__") {
    setGroupManageMessage("不能使用保留名称「未分组」", "warning");
    return;
  }
  if (!customDeviceGroupNames.includes(name)) {
    customDeviceGroupNames.push(name);
    persistCustomDeviceGroupNames();
  }
  newGroupNameInput.value = "";
  renderGroupMoveSelect(name);
  setGroupManageMessage(`已添加分组名：${name}`, "success");
}

async function deleteCurrentDeviceAliasGroup() {
  const name = String(groupMoveSelectEl?.value || "").trim();
  if (!name || name === "__ungrouped__") {
    setGroupManageMessage("未分组不能删除", "warning");
    return;
  }
  if (!window.confirm(`确定删除分组「${name}」？该组内设备将变为未分组。`)) return;
  try {
    setGroupManageMessage("正在删除分组...", "info");
    const data = await request("/api/admin/devices/delete_group", {
      method: "POST",
      body: JSON.stringify({ group_name: name }),
    });
    customDeviceGroupNames = customDeviceGroupNames.filter((g) => g !== name);
    persistCustomDeviceGroupNames();
    await refreshDeviceGroupsDetail();
    renderGroupMoveSelect("__ungrouped__");
    await loadOverview();
    setGroupManageMessage(data?.msg || "分组已删除", "success");
  } catch (error) {
    setGroupManageMessage(error.message || "删除分组失败", "error");
  }
}

async function startDevices(deviceIds) {
  for (const id of deviceIds) {
    if (!guardAutoSwitchManualOps("启动设备", id)) return;
  }
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
  for (const id of deviceIds) {
    if (!guardAutoSwitchManualOps("停止设备", id)) return;
  }
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

function normalizePcServiceUrl(raw) {
  const url = String(raw || "").trim().replace(/\/+$/, "");
  if (!/^https?:\/\//i.test(url)) {
    throw new Error("PC 服务地址须以 http:// 或 https:// 开头");
  }
  return url;
}

function getPcServicePageSize() {
  const fromInput = pcServicePageSizeInput?.value;
  const fromStorage = localStorage.getItem(PC_SERVICE_PAGE_SIZE_KEY);
  return clampPageSize(fromInput ?? fromStorage, DEFAULT_PC_SERVICE_PAGE_SIZE, MAX_PC_SERVICE_PAGE_SIZE);
}

function persistPcServicePageSize() {
  const size = getPcServicePageSize();
  try {
    localStorage.setItem(PC_SERVICE_PAGE_SIZE_KEY, String(size));
  } catch (_) {}
  if (pcServicePageSizeInput && String(pcServicePageSizeInput.value) !== String(size)) {
    pcServicePageSizeInput.value = String(size);
  }
}

function pcServiceQueryString() {
  const params = new URLSearchParams();
  params.set("page", String(currentPcServicePage));
  params.set("page_size", String(getPcServicePageSize()));
  const group = getActiveDeviceGroupFilter();
  if (group) params.set("device_group", group);
  return params.toString();
}

function updatePcServicePagerUi() {
  const pageSize = getPcServicePageSize();
  const totalPages = Math.max(1, Math.ceil(pcServiceDevicesTotal / pageSize));
  if (currentPcServicePage > totalPages) currentPcServicePage = totalPages;
  if (pcServicePageInfoEl) {
    pcServicePageInfoEl.textContent = `第 ${currentPcServicePage} / ${totalPages} 页 | 每页 ${pageSize} 条 | 共 ${pcServiceDevicesTotal} 台`;
  }
  if (pcServicePrevPageBtn) pcServicePrevPageBtn.disabled = currentPcServicePage <= 1;
  if (pcServiceNextPageBtn) pcServiceNextPageBtn.disabled = currentPcServicePage >= totalPages;
}

function getPcServiceVisibleEntries() {
  const keyword = String(pcServiceAliasFilterText || "").trim().toLowerCase();
  return Object.entries(pcServiceDevices || {}).filter(([, info]) => {
    if (!keyword) return true;
    const alias = String(info?.alias || "").trim().toLowerCase();
    return alias.includes(keyword);
  });
}

function selectedPcServiceDeviceIds() {
  return Array.from(document.querySelectorAll(".pc-service-check:checked"))
    .map((item) => item.dataset.device)
    .filter(Boolean);
}

function updatePcServiceSelectionHint() {
  if (!pcServiceSelectionHint) return;
  const count = selectedPcServiceDeviceIds().length;
  pcServiceSelectionHint.textContent = `已选 ${count} 台`;
}

function bindPcServiceSelection() {
  pcServiceTableBody?.querySelectorAll(".pc-service-check").forEach((cb) => {
    cb.addEventListener("change", updatePcServiceSelectionHint);
  });
  updatePcServiceSelectionHint();
}

function renderPcServiceTable(devices = pcServiceDevices) {
  if (!pcServiceTableBody) return;
  const entries = getPcServiceVisibleEntries();
  if (!entries.length) {
    pcServiceTableBody.innerHTML = '<tr><td colspan="6" style="padding:10px;color:#666;">暂无匹配设备</td></tr>';
    updatePcServiceSelectionHint();
    return;
  }
  pcServiceTableBody.innerHTML = entries.map(([deviceId, info]) => {
    const alias = String(info?.alias || "").trim() || "—";
    const group = displayGroupName(info?.device_group || "");
    const status = String(info?.status || "offline");
    const statusClass = escapeHtml(status);
    const currentUrl = String(info?.pc_service_url || "").trim() || "—";
    // offline 也可勾选下发：不灰置整行，避免误以为不可操作
    return `
      <tr>
        <td><input type="checkbox" class="pc-service-check" data-device="${escapeHtml(deviceId)}" /></td>
        <td>${escapeHtml(deviceId)}</td>
        <td>${escapeHtml(alias)}</td>
        <td>${escapeHtml(group)}</td>
        <td><span class="badge ${statusClass}">${escapeHtml(status)}</span></td>
        <td style="max-width:280px;word-break:break-all;">${escapeHtml(currentUrl)}</td>
      </tr>
    `;
  }).join("");
  bindPcServiceSelection();
}

async function loadPcServiceDevices() {
  persistPcServicePageSize();
  const data = await request(`/api/admin/overview?${pcServiceQueryString()}`);
  pcServiceDevices = (data?.devices && typeof data.devices === "object") ? data.devices : {};
  pcServiceDevicesTotal = Number(data?.devices_total ?? Object.keys(pcServiceDevices).length) || 0;
  if (Number.isFinite(Number(data?.page))) currentPcServicePage = Number(data.page) || 1;
  if (Array.isArray(data?.device_groups)) {
    populateDeviceGroupFilterOptions(data.device_groups);
  }
  updatePcServicePagerUi();
  renderPcServiceTable(pcServiceDevices);
}

function appendPcServiceResultLog(text) {
  if (!pcServiceResultLog) return;
  const line = String(text || "").trim();
  if (!line) return;
  if (pcServiceResultLog.textContent === "暂无记录") {
    pcServiceResultLog.textContent = line;
  } else {
    pcServiceResultLog.textContent = `${pcServiceResultLog.textContent}\n${line}`;
  }
  pcServiceResultLog.scrollTop = pcServiceResultLog.scrollHeight;
}

async function requestPcServiceBatchSet(payload) {
  const url = await buildApiUrl("/api/admin/device_pc_service/batch_set");
  const headers = { "Content-Type": "application/json" };
  const token = authToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });
  const data = await parseFetchJsonResponse(response);
  return { ok: response.ok, data };
}

async function applyPcServiceUrlToSelectedDevices() {
  let windowsBaseUrl = "";
  try {
    windowsBaseUrl = normalizePcServiceUrl(pcServiceUrlInput?.value);
  } catch (error) {
    setStatus(error.message || "PC 服务地址无效", "warning");
    return;
  }
  const selectedIds = selectedPcServiceDeviceIds();
  if (!selectedIds.length) {
    setStatus("请先勾选要下发的设备", "warning");
    return;
  }
  const items = selectedIds.map((deviceId) => {
    const info = pcServiceDevices?.[deviceId] || {};
    return {
      device_id: deviceId,
      alias: String(info?.alias || "").trim(),
      windows_base_url: windowsBaseUrl,
    };
  });
  setStatus(`正在下发 PC 地址到 ${items.length} 台设备...`, "info");
  if (pcServiceApplyBtn) pcServiceApplyBtn.disabled = true;
  try {
    const { ok, data } = await requestPcServiceBatchSet({ items });
    const results = Array.isArray(data?.results) ? data.results : [];
    const enqueued = Number(data?.enqueued || 0);
    const failed = Number(data?.failed || 0);
    const stamp = new Date().toLocaleString();
    appendPcServiceResultLog(`[${stamp}] ${data?.msg || "下发完成"} → ${windowsBaseUrl}`);
    results.forEach((row) => {
      const alias = String(row?.alias || "").trim();
      const label = alias ? `${alias} (${row.device_id})` : String(row.device_id || "");
      const status = Number(row?.code) === 0 ? "成功" : "失败";
      appendPcServiceResultLog(`  · ${label}: ${status}${row?.msg ? ` — ${row.msg}` : ""}`);
    });
    if (!ok || Number(data?.code) !== 0) {
      setStatus(data?.msg || "下发失败", failed > 0 && enqueued > 0 ? "warning" : "error");
    } else if (failed > 0) {
      setStatus(`下发完成：成功 ${enqueued} 台，失败 ${failed} 台`, "warning");
    } else {
      setStatus(`已入队 ${enqueued} 台，设备轮询后将自动更新`, "success");
    }
    await loadPcServiceDevices();
  } catch (error) {
    setStatus(humanizeRequestErrorMessage(error) || "下发失败", "error");
  } finally {
    if (pcServiceApplyBtn) pcServiceApplyBtn.disabled = false;
  }
}

function onPcServicePageSizeChanged() {
  persistPcServicePageSize();
  currentPcServicePage = 1;
  void loadPcServiceDevices().catch(() => {});
}

const MODE_DEFAULT_CONFIG_PAYLOADS = {
  growth: {
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
    skip_post_click_prob: 0,
    action_delay_min: 0.7,
    action_delay_max: 1.2,
    tiktok_force_stop_wait_ms: 800,
    tiktok_launch_wait_ms: 2600,
    for_you_swipe_total_sec: 0,
    for_you_like_prob: 0,
    for_you_watch_min_sec: 3,
    for_you_watch_max_sec: 12,
    like_daily_limit: 800,
    comment_daily_limit: 800,
    mutual_traffic_comment_daily_limit: 800,
    favorite_daily_limit: 800,
    share_daily_limit: 800,
    action_single: true,
    skip_post_click_prob: 0,
    pull_batch_size: 5,
    pull_batches_before_rest: 5,
    no_rest: false,
    publish_post_material_fetch_count: 3,
    live_visit_count_per_slot: 0,
    process_queue_batch_size: 100,
    comment_texts: ["hi", "moring", "so cute"],
    mutual_comment_texts: ["hi", "moring", "so cute"],
    mutual_traffic_target_texts: ["teamwork"],
    profile_edit_bio_texts: ["Just vibing", "Thanks for visiting"],
    mutual_videos_scroll_rounds: 5,
    mutual_follow_tap_session_limit: 20,
    mutual_traffic_comment: true,
    mutual_traffic_author_follow: true,
    mutual_traffic_followers_follow: true,
    mutual_traffic_follow_only: false,
    growth_pull_batches_before_auto_mutual: -1,
    growth_post_check_after_tap_delay_ms: 1000,
    growth_post_second_check_delay_ms: 650,
  },
  live: {
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
    skip_post_click_prob: 0,
    action_delay_min: 0.7,
    action_delay_max: 1.2,
    tiktok_force_stop_wait_ms: 800,
    tiktok_launch_wait_ms: 2600,
    for_you_swipe_total_sec: 0,
    for_you_like_prob: 0,
    for_you_watch_min_sec: 3,
    for_you_watch_max_sec: 12,
    like_daily_limit: 800,
    comment_daily_limit: 800,
    favorite_daily_limit: 800,
    share_daily_limit: 800,
    action_single: true,
    skip_post_click_prob: 0,
    pull_batch_size: 5,
    pull_batches_before_rest: 5,
    no_rest: false,
    publish_post_material_fetch_count: 3,
    live_visit_count_per_slot: 0,
    process_queue_batch_size: 100,
    comment_texts: ["hi", "moring", "so cute"],
    mutual_comment_texts: ["hi", "moring", "so cute"],
    mutual_traffic_target_texts: ["teamwork"],
    profile_edit_bio_texts: ["Just vibing", "Thanks for visiting"],
    mutual_videos_scroll_rounds: 5,
    mutual_follow_tap_session_limit: 20,
    mutual_traffic_comment: true,
    mutual_traffic_author_follow: true,
    mutual_traffic_followers_follow: true,
    mutual_traffic_follow_only: false,
    growth_pull_batches_before_auto_mutual: -1,
    direct_post_action_delay_ms: 1500,
  },
  direct: {
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
    skip_post_click_prob: 0,
    action_delay_min: 0.7,
    action_delay_max: 1.2,
    tiktok_force_stop_wait_ms: 800,
    tiktok_launch_wait_ms: 2600,
    for_you_swipe_total_sec: 0,
    for_you_like_prob: 0,
    for_you_watch_min_sec: 3,
    for_you_watch_max_sec: 12,
    like_daily_limit: 800,
    comment_daily_limit: 800,
    favorite_daily_limit: 800,
    share_daily_limit: 800,
    action_single: true,
    skip_post_click_prob: 0,
    pull_batch_size: 5,
    pull_batches_before_rest: 5,
    no_rest: false,
    publish_post_material_fetch_count: 3,
    live_visit_count_per_slot: 0,
    process_queue_batch_size: 100,
    comment_texts: ["hi", "moring", "so cute"],
    mutual_comment_texts: ["hi", "moring", "so cute"],
    mutual_traffic_target_texts: ["teamwork"],
    profile_edit_bio_texts: ["Just vibing", "Thanks for visiting"],
    mutual_videos_scroll_rounds: 5,
    mutual_follow_tap_session_limit: 20,
    mutual_traffic_comment: true,
    mutual_traffic_author_follow: true,
    mutual_traffic_followers_follow: true,
    mutual_traffic_follow_only: false,
    growth_pull_batches_before_auto_mutual: -1,
    direct_post_action_delay_ms: 1500,
  },
};


function updateAutoStartToggleUi() {
  if (!autoStartToggleBtn) return;
  autoStartToggleBtn.textContent = adminAutoStartEnabled
    ? "自动起号策略：开"
    : "自动起号策略：关";
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

/** 更新设备卡片、表格、别名筛选下表格、运维下拉与日志区（由 overview 或 logs_snapshot 共用） */
function applyDevicesAndLogsPayload(data, options = {}) {
  const shouldSyncRunMode = options.syncRunMode === true;
  const shouldSyncConfigForm = options.syncConfigForm === true;
  const hasDevicesField = data && Object.prototype.hasOwnProperty.call(data, "devices");
  const hasDeviceLogsField = data && Object.prototype.hasOwnProperty.call(data, "device_logs");
  // logs_snapshot 可能只返回部分字段；缺失字段时保留上次有效数据，避免把概览清零。
  const devices = hasDevicesField ? (data.devices || {}) : (latestDevices || {});
  const deviceLogs = hasDeviceLogsField ? (data.device_logs || {}) : (latestDeviceLogs || {});
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
  const stats = data?.device_stats && typeof data.device_stats === "object" ? data.device_stats : null;
  const deviceValues = Object.values(devices);
  const online = stats?.online ?? deviceValues.filter((d) => d.status === "online").length;
  const running = stats?.running ?? deviceValues.filter((d) => d.status === "running").length;
  const error = stats?.error ?? deviceValues.filter((d) => d.status === "error").length;
  const pending = stats?.pending_start ?? deviceValues.filter((d) => d.pending_start).length;

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
  if (Array.isArray(data?.device_groups)) {
    populateDeviceGroupFilterOptions(data.device_groups);
  }
  overviewDevicesTotal = Number(data?.devices_total ?? Object.keys(devices).length) || 0;
  if (Number.isFinite(Number(data?.page))) currentOverviewPage = Number(data.page) || 1;
  updateOverviewPagerUi();
  renderDevices(devices);
  renderDeviceAliasPresetOptions();
  renderDeviceLogDeviceSelect(devices, deviceLogs);
  renderDeviceExecLogs(deviceLogs);
  bindDeviceSelection();
  void syncAssignableAliasOptionsIfNeeded();
}

async function loadOverview() {
  const startedAt = Date.now();
  const overviewPromise = request(`/api/admin/overview?${overviewQueryString()}`);
  const data = await overviewPromise;
  // overview 仅用于设备与概览数据；配置表单必须由 runtime_config/template_select 驱动，
  // 否则会出现“模板名正确但表单值被 overview 旧快照覆盖”的错位。
  // 勿用 overview 的 run_mode 覆盖本地：其曾取自全局 store，会导致别名模板映射查错 mode 并自动回绑 default。
  applyDevicesAndLogsPayload(data, { syncRunMode: false, syncConfigForm: false });
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

  void startedAt;
  await refreshVisibleDeviceLogs({ requestPull: false });
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
  if (Date.now() < overviewAutoRefreshCooldownUntil) return;
  overviewAutoRefreshInFlight = true;
  try {
    await loadOverview();
    overviewAutoRefreshCooldownUntil = 0;
  } catch (_) {
    overviewAutoRefreshCooldownUntil = Date.now() + 30000;
    // 静默失败，避免频繁提示；用户可手动刷新查看错误信息。
  } finally {
    overviewAutoRefreshInFlight = false;
  }
}

async function handleRefresh() {
  try {
    setStatus("刷新中...", "info");
    await loadUserRuntimeConfig();
    await loadOverview();
    if (document.getElementById("pc-service")?.classList.contains("active")) {
      await loadPcServiceDevices();
    }
    setStatus("已刷新概览", "success");
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
    if (
      key === "action_single" ||
      key === "no_rest" ||
      key === "mutual_traffic_comment" ||
      key === "mutual_traffic_author_follow" ||
      key === "mutual_traffic_followers_follow" ||
      key === "mutual_traffic_follow_only"
    ) {
      payload[key] = value === "on";
      continue;
    }
    if (key === "comment_texts" || key === "mutual_comment_texts" || key === "mutual_traffic_target_texts" || key === "profile_edit_bio_texts") {
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
  for (const key of [
    "action_single",
    "no_rest",
    "mutual_traffic_comment",
    "mutual_traffic_comment_daily_limit",
    "mutual_traffic_author_follow",
    "mutual_traffic_followers_follow",
    "mutual_traffic_follow_only",
  ]) {
    const input = configForm?.querySelector(`[name='${key}']`);
    if (input?.type === "checkbox") {
      payload[key] = input.checked;
    }
  }
  return payload;
}

async function saveConfig() {
  syncMutualTrafficActionMutex();
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
    await request("/api/admin/update_config", {
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
  saveUiStateSnapshot();
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
  saveUiStateSnapshot();
}

async function loadAccounts() {
  const startedAt = Date.now();
  if (liveCustomerList) liveCustomerList.innerHTML = '<li>加载中...</li>';
  if (tiktokRootsTableBody) tiktokRootsTableBody.innerHTML = '<tr><td colspan="5" style="padding:10px;color:#666;">加载中...</td></tr>';
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
  void startedAt;
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
      timeoutMs: 300000,
    });
    await loadTiktokRootsData();
    renderTikTokRootsTable(tiktokRootsItems || []);
    setStatus(data.msg || `已加入待下发队列：新增 ${data.inserted ?? data.count ?? 0} 条`, "success");
  } catch (error) {
    setStatus(error.message || "写入失败", "error");
  }
}

async function addAllRootsToPending() {
  try {
    setStatus("正在将全部 tiktok_roots 写入待下发队列（服务端处理）...", "info");
    const result = await request("/api/admin/mobile_pending_usernames/add_all_roots", {
      method: "POST",
      timeoutMs: 600000,
    });
    await loadOverview();
    await loadAccounts();
    const selected = Number(result.selected || 0);
    const inserted = Number(result.inserted ?? result.count ?? 0);
    const skippedPending = Number(result.skipped_already_pending || 0);
    const skippedNote = skippedPending > 0 ? `，${skippedPending} 条已在队列中` : "";
    setStatus(
      result.code === 0 && inserted <= 0 && skippedPending > 0
        ? (result.msg || `无新用户写入（${skippedPending} 个已在待下发队列中）`)
        : `已将全部根用户写入待下发队列：新增 ${inserted} 条（共 ${selected} 条${skippedNote}）`,
      inserted > 0 || (result.code === 0 && skippedPending > 0) ? "success" : "warning",
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
  saveUiStateSnapshot();
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

async function exportMobilePendingUsernames(clearAfterExport = false) {
  if (mobilePendingExportInFlight) {
    setStatus("已有导出任务正在执行，请稍后重试", "warning");
    return;
  }
  mobilePendingExportInFlight = true;
  try {
    const action = clearAfterExport ? "导出并清空" : "导出";
    setMobilePendingExportButtonsBusy(true, action);
    setStatus(`已提交后台${action}任务，处理中…`, "info");
    appendMobilePendingLog(`异步${action}：已提交后台任务`, true);

    const startData = await request("/api/admin/mobile_pending_usernames/export_start", {
      method: "POST",
      body: JSON.stringify({ clear_after_export: !!clearAfterExport }),
      timeoutMs: 30000,
    });
    const jobId = String(startData?.job_id || "").trim();
    if (!jobId) {
      throw new Error("服务端未返回 job_id");
    }
    appendMobilePendingLog(`任务 id：${jobId}`);

    const pollIntervalMs = 500;
    const maxWaitMs = 20 * 60 * 1000;
    const deadline = Date.now() + maxWaitMs;
    let pollN = 0;
    let lastStatus = "";

    /** @type {Record<string, any> | undefined} */
    let data;

    while (Date.now() < deadline) {
      await new Promise((resolve) => window.setTimeout(resolve, pollIntervalMs));
      pollN += 1;
      const st = await request(
        `/api/admin/mobile_pending_usernames/export_status?job_id=${encodeURIComponent(jobId)}`,
        { method: "GET", timeoutMs: 25000 },
      );
      const status = String(st?.status || "");
      if (status && status !== lastStatus) {
        lastStatus = status;
        appendMobilePendingLog(`任务状态：${status}`);
      }
      if (status === "queued" || status === "running") {
        if (pollN % 12 === 0) {
          setStatus(`后台${action}进行中（${status}）…`, "info");
        }
        continue;
      }
      if (status === "gone") {
        throw new Error(String(st?.msg || "任务不存在或已过期"));
      }
      if (status === "failed") {
        const msg =
          typeof st?.error === "string" && st.error.trim()
            ? st.error
            : String(st?.result?.msg || "导出失败");
        throw new Error(msg);
      }
      if (status === "done") {
        data = st?.result || {};
        if (Number(data?.code ?? 0) !== 0) {
          throw new Error(String(data?.msg || "导出失败"));
        }
        break;
      }
      throw new Error(`未知任务状态：${status || "-"}`);
    }

    if (!data) {
      throw new Error(
        `${action}等待超时（${Math.round(maxWaitMs / 60000)} 分钟）；若导出量大可能仍在服务端执行，请稍后再刷新查看队列`,
      );
    }

    const filenames = Array.isArray(data?.filenames)
      ? data.filenames.map((item) => String(item || "").trim()).filter(Boolean)
      : [];
    const previewText = filenames.slice(0, 5).join(", ");
    const suffix = filenames.length > 5 ? ` ... (+${filenames.length - 5})` : "";
    appendMobilePendingLog(
      `结果：${JSON.stringify({
        count: data?.count,
        cleared: data?.cleared,
        batch_count: data?.batch_count,
        batch_size: data?.batch_size,
        filename_preview: previewText || "-",
        filename_count: filenames.length,
        filename_more: suffix || "",
      })}`
    );
    await downloadMobilePendingExportFiles(filenames);
    await loadOverview();
    const fileName = data?.filename || data?.file_path || "xlsx";
    const batchCount = Number(data?.batch_count || 1) || 1;
    const count = Number(data?.count || 0) || 0;
    const cleared = Number(data?.cleared || 0) || 0;
    const batchText = batchCount > 1 ? `，共 ${batchCount} 个文件` : "";
    if (clearAfterExport) {
      setStatus(`导出成功：${fileName}${batchText}，共 ${count} 条，已清空 ${cleared} 条`, "success");
      appendMobilePendingLog(`完成：${fileName}${batchText}，共 ${count} 条，已清空 ${cleared} 条`);
    } else {
      setStatus(`导出成功：${fileName}${batchText}，共 ${count} 条`, "success");
      appendMobilePendingLog(`完成：${fileName}${batchText}，共 ${count} 条`);
    }
  } catch (error) {
    const message = error.message || "导出失败";
    setStatus(message, "error");
    appendMobilePendingLog(`失败：${message}`);
  } finally {
    mobilePendingExportInFlight = false;
    setMobilePendingExportButtonsBusy(false);
  }
}

async function downloadMobilePendingExportFiles(fileNames) {
  const names = Array.isArray(fileNames)
    ? fileNames.map((item) => String(item || "").trim()).filter(Boolean)
    : [];
  if (!names.length) return;
  const base = String(await resolvedApiBase()).replace(/\/$/, "");
  const token = authToken();
  const headers = token ? { Authorization: `Bearer ${token}` } : {};
  for (let index = 0; index < names.length; index += 1) {
    const name = names[index];
    if (index > 0) {
      // 主动让出主线程，避免大批量文件时 UI 假死。
      await new Promise((resolve) => window.setTimeout(resolve, 0));
    }
    const url = `${base}/admin/mobile_pending_usernames/export_file?filename=${encodeURIComponent(name)}`;
    const response = await fetch(url, { method: "GET", headers, cache: "no-store" });
    if (!response.ok) {
      throw new Error(`下载导出文件失败：${name} (HTTP ${response.status})`);
    }
    const blob = await response.blob();
    const objectUrl = window.URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = name;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => window.URL.revokeObjectURL(objectUrl), 1000);
    if (index < 3 || index === names.length - 1 || (index + 1) % 20 === 0) {
      appendMobilePendingLog(`已触发下载：${index + 1}/${names.length} - ${name}`);
    }
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
  const dedupOnly = Boolean(tiktokRootsDedupOnlyInput?.checked);
  if (!file) {
    setStatus("请先选择 Excel/TXT 文件", "warning");
    return;
  }
  try {
    setStatus(dedupOnly ? "正在按仅判重模式导入到 tiktok_roots..." : "正在导入根用户表格到 tiktok_roots...", "info");
    const formData = new FormData();
    formData.append("file", file);
    formData.append("dedup_only", dedupOnly ? "1" : "0");
    const base = String(await resolvedApiBase()).replace(/\/$/, "");
    const response = await fetch(`${base}/admin/tiktok_roots/import`, {
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
    const queuedText = dedupOnly ? "" : (typeof data.queued === "number" ? `，待下发 ${data.queued}` : "");
    setStatus(`根用户导入完成：${data.inserted}/${data.total_rows}${queuedText}`, "success");
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

function isAdminPageVisible() {
  return document.visibilityState !== "hidden";
}

function taskStatusResponseBusy(data = {}) {
  const runningTasks = Array.isArray(data.running_tasks) ? data.running_tasks : [];
  if (runningTasks.some((item) => item && item.running !== false)) return true;
  if (Boolean(data.backup_stats_full_query_in_flight)) return true;
  return false;
}

function getTaskStatusPollDelayMs() {
  return taskStatusPollBusy ? TASK_STATUS_POLL_BUSY_MS : TASK_STATUS_POLL_IDLE_MS;
}

function clearTaskStatusPollTimer() {
  if (taskStatusPollTimer != null) {
    clearTimeout(taskStatusPollTimer);
    taskStatusPollTimer = null;
  }
}

function scheduleTaskStatusPoll(delayMs) {
  clearTaskStatusPollTimer();
  if (!taskStatusPollingEnabled || !isAdminPageVisible()) return;
  const wait = Number.isFinite(delayMs) ? Math.max(0, delayMs) : getTaskStatusPollDelayMs();
  taskStatusPollTimer = setTimeout(async () => {
    taskStatusPollTimer = null;
    if (!taskStatusPollingEnabled || !isAdminPageVisible()) return;
    await refreshTaskStatus();
  }, wait);
}

function clearBatchStreamPollTimer() {
  if (batchStreamPollTimer != null) {
    clearTimeout(batchStreamPollTimer);
    batchStreamPollTimer = null;
  }
}

function scheduleBatchStreamPoll(delayMs = BATCH_STREAM_POLL_MS) {
  clearBatchStreamPollTimer();
  if (!batchStreamPollingEnabled || !isAdminPageVisible()) return;
  if (!isBatchStreamSectionActive() || !batchStreamSessionId) return;
  const wait = Number.isFinite(delayMs) ? Math.max(0, delayMs) : BATCH_STREAM_POLL_MS;
  batchStreamPollTimer = setTimeout(async () => {
    batchStreamPollTimer = null;
    if (!batchStreamPollingEnabled || !isAdminPageVisible()) return;
    if (!isBatchStreamSectionActive() || !batchStreamSessionId) return;
    await pollBatchStreamStatus();
    scheduleBatchStreamPoll();
  }, wait);
}

function clearOverviewPollTimer() {
  if (overviewPollTimer != null) {
    clearTimeout(overviewPollTimer);
    overviewPollTimer = null;
  }
}

function scheduleOverviewPoll(delayMs = OVERVIEW_POLL_MS) {
  clearOverviewPollTimer();
  if (!overviewPollingEnabled || !isAdminPageVisible()) return;
  const wait = Number.isFinite(delayMs) ? Math.max(0, delayMs) : OVERVIEW_POLL_MS;
  overviewPollTimer = setTimeout(async () => {
    overviewPollTimer = null;
    if (!overviewPollingEnabled || !isAdminPageVisible()) return;
    if (isOverviewActive()) {
      await pollOverviewSnapshot();
    }
    scheduleOverviewPoll();
  }, wait);
}

function stopAdminBackgroundPollers() {
  taskStatusPollingEnabled = false;
  overviewPollingEnabled = false;
  batchStreamPollingEnabled = false;
  clearTaskStatusPollTimer();
  clearBatchStreamPollTimer();
  clearOverviewPollTimer();
}

function startAdminBackgroundPollers() {
  if (!isAdminPageVisible()) return;
  taskStatusPollingEnabled = true;
  overviewPollingEnabled = true;
  batchStreamPollingEnabled = true;
  void refreshTaskStatus();
  scheduleOverviewPoll(OVERVIEW_POLL_MS);
  if (isBatchStreamSectionActive() && batchStreamSessionId) {
    scheduleBatchStreamPoll(BATCH_STREAM_POLL_MS);
  }
}

async function refreshTaskStatus() {
  if (taskStatusRefreshInFlight) return;
  if (Date.now() < taskStatusRefreshCooldownUntil) return;
  taskStatusRefreshInFlight = true;
  try {
    const data = await request("/api/admin/tasks/status");
    taskStatusPollBusy = taskStatusResponseBusy(data);
    const schedulerTasks = data.scheduler_tasks || {};
    const taskLogs = data.task_logs || {};
    const runningTasks = Array.isArray(data.running_tasks) ? data.running_tasks : [];
    if (schedulerEnabledToggle) schedulerEnabledToggle.checked = !!data.scheduler_enabled;
    if (backupStatsFullQueryEnabledToggle) {
      backupStatsFullQueryEnabledToggle.checked = !!data.backup_stats_full_query_enabled;
    }
    applyBackupStatsFullQueryScheduleUi({
      available: data.backup_stats_full_query_schedule_available !== false,
      enabled: !!data.backup_stats_full_query_enabled,
      disabled_reason: data.backup_stats_full_query_schedule === "暂时不可用"
        ? "每日定时全量查询暂时不可用"
        : "",
    });
    if (taskStatusEl) {
      const runningLabel = runningTasks.length
        ? runningTasks.map((item) => item?.name || item).join(", ")
        : "无";
      const autoSw = data.auto_switch_enabled ? "开" : "关";
      const bj = data.auto_switch_beijing_time
        ? `${data.auto_switch_beijing_date || ""} ${data.auto_switch_beijing_time}`.trim()
        : "";
      const bsq = data.backup_stats_full_query_schedule_available === false
        ? "暂不可用"
        : (data.backup_stats_full_query_enabled ? "开" : "关");
      const bsqLast = data.backup_stats_full_query_last_run_date || "-";
      const bsqRun = data.backup_stats_full_query_in_flight ? "执行中" : "空闲";
      taskStatusEl.textContent = [
        `总开关: ${data.scheduler_enabled ? "开" : "关"}`,
        `自动切号: ${autoSw}${bj ? `（北京 ${bj}）` : ""}`,
        `每日全量: ${bsq}（上次 ${bsqLast}，${bsqRun}）`,
        `运行中: ${runningLabel}`,
        `粉丝增长监控: ${JSON.stringify(data.auto_search_schedule || {})}`,
      ].join("  |  ");
    }
    renderSchedulerTaskSettings(schedulerTasks);
    renderGroupedTaskLogs(taskLogs);
    renderCvProcessQueueLogs(taskLogs, runningTasks);
  } catch (error) {
    taskStatusRefreshCooldownUntil = Date.now() + 15000;
    if (taskStatusEl) {
      taskStatusEl.textContent = `任务状态读取失败: ${error.message || "unknown"}`;
    }
    if (schedulerTaskSettingsEl) {
      schedulerTaskSettingsEl.textContent = "任务状态读取失败";
    }
    if (schedulerTaskLogsEl) {
      schedulerTaskLogsEl.textContent = "日志读取失败";
    }
    if (cvTaskLogsEl) {
      cvTaskLogsEl.textContent = `日志读取失败: ${error.message || "unknown"}`;
    }
  } finally {
    taskStatusRefreshInFlight = false;
    if (taskStatusPollingEnabled && isAdminPageVisible()) {
      scheduleTaskStatusPoll(getTaskStatusPollDelayMs());
    }
  }
}

async function saveSchedulerConfig() {
  try {
    const enabled = !!schedulerEnabledToggle?.checked;
    await request("/api/admin/tasks/scheduler/config", {
      method: "POST",
      body: JSON.stringify({ enabled }),
    });
    await refreshTaskStatus();
    setStatus("定时任务总开关已保存", "success");
  } catch (error) {
    setStatus(error.message || "保存配置失败", "error");
  }
}

function sessionAllowedDeviceIdSet() {
  const ids = new Set();
  buildAliasDevicePairs().forEach((item) => {
    const did = String(item?.device_id || "").trim();
    if (did) ids.add(did);
  });
  return ids;
}

function filterAutoSwitchDeviceIds(deviceIds) {
  const allowed = sessionAllowedDeviceIdSet();
  const cleaned = Array.from(new Set((deviceIds || []).map((d) => String(d || "").trim()).filter(Boolean)));
  if (!allowed.size) return cleaned;
  return cleaned.filter((d) => allowed.has(d));
}

function resolveDeviceIdForArchiveAlias(alias, rowDeviceId = "") {
  const aliasKey = normalizeAliasKey(alias);
  const allowed = sessionAllowedDeviceIdSet();
  const accept = (did) => {
    const id = String(did || "").trim();
    if (!id) return "";
    if (!allowed.size || allowed.has(id)) return id;
    return "";
  };
  if (aliasKey && aliasKey !== "__empty_alias__") {
    const fromPairs = resolveDeviceIdByAlias(alias);
    const paired = accept(fromPairs);
    if (paired) return paired;
    const fromDevices = Object.entries(latestDevices || {}).find(([, info]) =>
      normalizeAliasKey(info?.alias) === aliasKey,
    );
    const fromOverview = accept(fromDevices ? fromDevices[0] : "");
    if (fromOverview) return fromOverview;
  }
  return accept(rowDeviceId);
}

function resolveDeviceIdForArchiveDetails(detailsEl) {
  if (!(detailsEl instanceof HTMLElement)) return "";
  const alias = String(detailsEl.dataset.archiveAlias || "").trim();
  const fromAttr = String(detailsEl.dataset.archiveDeviceId || "").trim();
  return resolveDeviceIdForArchiveAlias(alias, fromAttr);
}

function aliasGroupHasSelectedExportRows(detailsEl) {
  if (!(detailsEl instanceof HTMLElement)) return false;
  return Array.from(
    detailsEl.querySelectorAll(".backup-archive-export-select, .backup-archive-group-select-all"),
  ).some((el) => el instanceof HTMLInputElement && el.checked);
}

function countVisibleAutoSwitchSelectedGroups() {
  if (!deviceAliasGroupsEl) return 0;
  let count = 0;
  deviceAliasGroupsEl.querySelectorAll("details.panel-card[data-archive-alias]").forEach((details) => {
    if (!(details instanceof HTMLElement)) return;
    if (aliasGroupHasSelectedExportRows(details)) count += 1;
  });
  return count;
}

/** 收集待开启/关闭自动切号的 device_id（勾选记录 / 本组全选 / 程序化全选）。 */
function collectAutoSwitchTargetDeviceIds() {
  const ids = new Set();
  if (backupArchiveSelectedRowIndices.size && Array.isArray(backupArchivePageItems)) {
    backupArchivePageItems.forEach((row) => {
      const idx = resolveBackupArchiveExportRowIndex(row);
      if (idx == null || !backupArchiveSelectedRowIndices.has(idx)) return;
      const alias = resolveBackupArchiveDisplayAlias(row);
      const did = resolveDeviceIdForArchiveAlias(alias, row?.device_id);
      if (did) ids.add(did);
    });
  }
  if (deviceAliasGroupsEl) {
    deviceAliasGroupsEl.querySelectorAll("details.panel-card[data-archive-alias]").forEach((details) => {
      if (!(details instanceof HTMLElement)) return;
      const hasExportPick = Array.from(
        details.querySelectorAll(".backup-archive-export-select, .backup-archive-group-select-all"),
      ).some((el) => el instanceof HTMLInputElement && el.checked);
      if (!hasExportPick) return;
      const did = resolveDeviceIdForArchiveDetails(details);
      if (did) ids.add(did);
    });
  }
  return filterAutoSwitchDeviceIds(Array.from(ids));
}

function syncAutoSwitchPickForAliasGroup(detailsEl, checked = true) {
  if (!(detailsEl instanceof HTMLElement)) return "";
  const did = resolveDeviceIdForArchiveDetails(detailsEl);
  if (!did) return "";
  if (checked) {
    autoSwitchPickDeviceIds.add(did);
  } else if (!aliasGroupHasSelectedExportRows(detailsEl)) {
    autoSwitchPickDeviceIds.delete(did);
  }
  return did;
}

function isAutoSwitchEnabledForDeviceId(deviceId) {
  const did = String(deviceId || "").trim();
  if (!did) return false;
  return !!autoSwitchDevicesById.get(did);
}

function isAutoSwitchEnabledForArchiveAlias(alias, rowDeviceId = "") {
  return isAutoSwitchEnabledForDeviceId(resolveDeviceIdForArchiveAlias(alias, rowDeviceId));
}

function isAutoSwitchGloballyEnabled() {
  return autoSwitchDevicesById.size > 0 && Array.from(autoSwitchDevicesById.values()).some(Boolean);
}

function updateAutoSwitchEnabledCountHint() {
  if (!autoSwitchEnabledCountHint) return;
  const enabled = Array.from(autoSwitchDevicesById.entries()).filter(([, on]) => on).length;
  autoSwitchEnabledCountHint.textContent = enabled > 0 ? `自动切号中：${enabled} 台设备` : "自动切号：未开启";
}

function applyAutoSwitchManualOpsUi() {
  const hint = AUTO_SWITCH_MANUAL_BLOCK_HINT;
  [
    ["startSelectedBtn", "启动选中"],
    ["startAllBtn", "启动全部"],
    ["stopSelectedBtn", "停止选中"],
    ["publishPostStartSelectedBtn", "批量发作品"],
  ].forEach(([id, label]) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.disabled = false;
    el.title = "";
  });
  if (autoSwitchSelectAllEnableBtn) {
    autoSwitchSelectAllEnableBtn.disabled = autoSwitchSaveInFlight;
  }
  if (autoSwitchEnableSelectedBtn) {
    const pickCount = collectAutoSwitchTargetDeviceIds().length;
    const rawCount = countVisibleAutoSwitchSelectedGroups();
    autoSwitchEnableSelectedBtn.disabled = autoSwitchSaveInFlight || pickCount === 0;
    autoSwitchEnableSelectedBtn.title =
      pickCount > 0
        ? `为已选的 ${pickCount} 台设备开启自动切号（本机自治）`
        : rawCount > 0
          ? "所选别名尚未绑定有效 device_id，请先在设备总览完成客户端绑定"
          : "请勾选别名组左侧复选框，或点「全选本页」";
  }
  if (autoSwitchDisableSelectedBtn) {
    const pickCount = collectAutoSwitchTargetDeviceIds().length;
    autoSwitchDisableSelectedBtn.disabled = autoSwitchSaveInFlight || pickCount === 0;
  }
  updateAutoSwitchEnabledCountHint();
}

const AUTO_SWITCH_MANUAL_BLOCK_HINT = "该设备已开启自动切号，请先关闭后再操作";

function guardAutoSwitchManualOps(actionLabel = "该操作", deviceId = "", alias = "", rowDeviceId = "") {
  const did = String(deviceId || "").trim() || resolveDeviceIdForArchiveAlias(alias, rowDeviceId);
  if (!isAutoSwitchEnabledForDeviceId(did)) return true;
  setStatus(`${actionLabel}不可用：${AUTO_SWITCH_MANUAL_BLOCK_HINT}`, "warning");
  return false;
}

function setAutoSwitchExcludedBatchMessage(text, type = "") {
  if (!autoSwitchExcludedBatchMessageEl) return;
  autoSwitchExcludedBatchMessageEl.textContent = text || "";
  autoSwitchExcludedBatchMessageEl.dataset.type = type || "";
}

function parseAutoSwitchExcludedBatchUsernames(rawText) {
  const lines = String(rawText || "").split(/\r?\n/);
  const result = [];
  const seen = new Set();
  for (const line of lines) {
    let name = String(line || "").trim();
    if (!name) continue;
    // 兼容粘贴带 @ 前缀的 TikTok username
    if (name.startsWith("@")) name = name.slice(1).trim();
    if (!name) continue;
    const key = name.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(name);
  }
  return result;
}

function openAutoSwitchExcludedBatchModal() {
  if (!autoSwitchExcludedBatchModalEl) return;
  setAutoSwitchExcludedBatchMessage("");
  if (autoSwitchExcludedBatchTextarea) {
    autoSwitchExcludedBatchTextarea.value = "";
  }
  autoSwitchExcludedBatchModalEl.classList.remove("hidden");
  autoSwitchExcludedBatchModalEl.setAttribute("aria-hidden", "false");
  // 先展示弹窗再聚焦，避免 hidden 状态下 focus 无效
  autoSwitchExcludedBatchTextarea?.focus();
}

function closeAutoSwitchExcludedBatchModal() {
  if (!autoSwitchExcludedBatchModalEl) return;
  autoSwitchExcludedBatchModalEl.classList.add("hidden");
  autoSwitchExcludedBatchModalEl.setAttribute("aria-hidden", "true");
}

async function submitAutoSwitchExcludedBatch(excluded) {
  const usernames = parseAutoSwitchExcludedBatchUsernames(autoSwitchExcludedBatchTextarea?.value || "");
  if (!usernames.length) {
    setAutoSwitchExcludedBatchMessage("请至少填写一个 username（一行一个）", "warning");
    return;
  }
  const actionText = excluded ? "不参加自动切号" : "参加自动切号";
  const detail = excluded
    ? "开启后，这些账号将从自动切号计划和 bundle 中排除。"
    : "关闭后，这些账号会重新参与自动切号计划。";
  if (!window.confirm(`确认将 ${usernames.length} 个账号批量设置为“${actionText}”？\n${detail}`)) {
    return;
  }
  if (autoSwitchExcludedBatchExcludeBtn) autoSwitchExcludedBatchExcludeBtn.disabled = true;
  if (autoSwitchExcludedBatchJoinBtn) autoSwitchExcludedBatchJoinBtn.disabled = true;
  setAutoSwitchExcludedBatchMessage(`正在批量设置为“${actionText}”…`, "info");
  try {
    const resp = await request("/api/admin/editor/device_rows/auto_switch_excluded_batch", {
      method: "POST",
      body: JSON.stringify({ usernames, excluded: !!excluded }),
      timeoutMs: 120000,
    });
    await loadAutoSwitchConfig({ refreshArchive: false });
    await loadBackupArchiveRecords();
    await refreshAutoSwitchDailyPlansInBackground();
    const updatedRows = Number(resp?.updated_rows || 0);
    const unchangedRows = Number(resp?.unchanged_rows || 0);
    const notFound = Array.isArray(resp?.not_found) ? resp.not_found : [];
    const disabledDevices = Number(resp?.devices_disabled || 0);
    const notifiedDevices = Number(resp?.devices_notified || 0);
    const parts = [`已批量设置为“${actionText}”：更新 ${updatedRows} 条`];
    if (unchangedRows > 0) parts.push(`已是该状态 ${unchangedRows} 条`);
    if (notFound.length > 0) parts.push(`未找到 ${notFound.length} 个`);
    if (disabledDevices > 0) parts.push(`已自动关闭 ${disabledDevices} 台设备自动切号`);
    else if (notifiedDevices > 0) parts.push(`已重同步 ${notifiedDevices} 台设备`);
    const msg = parts.join("，");
    const level = notFound.length > 0 || disabledDevices > 0 ? "warning" : "success";
    setAutoSwitchExcludedBatchMessage(
      notFound.length > 0 ? `${msg}。未找到：${notFound.slice(0, 20).join(", ")}${notFound.length > 20 ? "…" : ""}` : msg,
      level,
    );
    setStatus(msg, level);
    if (updatedRows > 0 && notFound.length === 0) {
      closeAutoSwitchExcludedBatchModal();
    }
  } finally {
    if (autoSwitchExcludedBatchExcludeBtn) autoSwitchExcludedBatchExcludeBtn.disabled = false;
    if (autoSwitchExcludedBatchJoinBtn) autoSwitchExcludedBatchJoinBtn.disabled = false;
  }
}

async function loadAutoSwitchConfig(options = {}) {
  const refreshArchive = options.refreshArchive !== false;
  try {
    const data = await request("/api/admin/auto_switch/config");
    const cfg = data.data || {};
    autoSwitchDevicesById = new Map();
    const devices = Array.isArray(cfg.devices) ? cfg.devices : [];
    devices.forEach((item) => {
      const did = String(item?.device_id || "").trim();
      if (did) autoSwitchDevicesById.set(did, !!item?.enabled);
    });
    autoSwitchCutScheme = String(cfg.cut_switch_scheme || "backup").trim().toLowerCase() === "login"
      ? "login"
      : "backup";
    if (autoSwitchCutSchemeSelect) {
      autoSwitchCutSchemeSelect.value = autoSwitchCutScheme;
      autoSwitchCutSchemeSelect.title = autoSwitchCutScheme === "login"
        ? "当前：登录切号（清数据后直接登录目标号，不还原备份）"
        : "当前：备份切号（备份→清理→还原）";
    }
    applyAutoSwitchManualOpsUi();
    populateAutoSwitchSessionSummariesAliasOptions();
    await loadAutoSwitchSessionSummaries();
    if (refreshArchive && deviceAliasGroupsEl) {
      void loadBackupArchiveRecords();
    }
  } catch (_) {
    /* ignore */
  }
}

async function saveAutoSwitchCutScheme(scheme) {
  // 并发保存排队等待，避免下拉已切到新值但请求被静默丢弃
  while (autoSwitchCutSchemeSaveInFlight) {
    await new Promise((resolve) => setTimeout(resolve, 40));
  }
  const next = String(scheme || "backup").trim().toLowerCase() === "login" ? "login" : "backup";
  autoSwitchCutSchemeSaveInFlight = true;
  if (autoSwitchCutSchemeSelect) autoSwitchCutSchemeSelect.disabled = true;
  try {
    const data = await request("/api/admin/auto_switch/cut_switch_scheme", {
      method: "POST",
      body: JSON.stringify({ cut_switch_scheme: next }),
    });
    autoSwitchCutScheme = String(data?.data?.cut_switch_scheme || next) === "login" ? "login" : "backup";
    if (autoSwitchCutSchemeSelect) {
      autoSwitchCutSchemeSelect.value = autoSwitchCutScheme;
      autoSwitchCutSchemeSelect.title = autoSwitchCutScheme === "login"
        ? "当前：登录切号（清数据后直接登录目标号，不还原备份）"
        : "当前：备份切号（备份→清理→还原）";
    }
    const notified = Number(data?.data?.notified || 0);
    const parts = [data?.msg || (autoSwitchCutScheme === "login" ? "已切换为登录切号" : "已切换为备份切号")];
    if (notified > 0) parts.push(`已通知 ${notified} 台自动切号设备重同步`);
    setStatus(parts.join("；"), "success");
  } catch (error) {
    if (autoSwitchCutSchemeSelect) autoSwitchCutSchemeSelect.value = autoSwitchCutScheme;
    setStatus(error.message || "保存切号方案失败", "error");
  } finally {
    autoSwitchCutSchemeSaveInFlight = false;
    if (autoSwitchCutSchemeSelect) autoSwitchCutSchemeSelect.disabled = false;
  }
}

function isLoginCutSwitchScheme() {
  return autoSwitchCutScheme === "login";
}

function renderSmartTemplateDebugBlock(debug) {
  if (!debug || typeof debug !== "object") return "";
  const accounts = debug.accounts && typeof debug.accounts === "object" ? debug.accounts : {};
  const rows = Object.entries(accounts).map(([user, item]) => {
    const row = item && typeof item === "object" ? item : {};
    const branch = String(row.branch_label || row.branch || "-");
    const tpl = String(row.selected_template || row.picked_template || "-");
    const roll = row.roll;
    const total = row.total_weight;
    const rollText =
      typeof roll === "number" && typeof total === "number" && total > 0
        ? ` · roll ${roll}/${total}`
        : "";
    const src = String(row.resolution_source || "");
    const srcText = src ? ` · ${src}` : "";
    return `<li><code>${escapeHtml(user)}</code> → ${escapeHtml(tpl)}（${escapeHtml(branch)}${rollText}${srcText}）</li>`;
  });
  if (!rows.length && !debug.session_id) return "";
  const meta = [
    debug.enabled ? "智能模板" : "别名绑定",
    debug.session_id ? `session ${String(debug.session_id)}` : "",
    debug.enter_reroll ? "enter 重掷" : "",
  ].filter(Boolean).join(" · ");
  return `
    <div class="auto-switch-smart-debug" style="margin:8px 0;">
      <div class="hint"><strong>模板打包审计</strong>${meta ? ` · ${escapeHtml(meta)}` : ""}</div>
      ${rows.length ? `<ul class="auto-switch-summary-notes">${rows.join("")}</ul>` : ""}
    </div>
  `;
}

function renderAccountActivityAuditBlock(reconciliation) {
  const items = Array.isArray(reconciliation?.account_activity) ? reconciliation.account_activity : [];
  if (!items.length) return "";
  const actionLabel = {
    like: "赞",
    comment: "评",
    favorite: "藏",
    share: "转",
    mutual_traffic_comment: "引流评",
    mutual_follow: "关注",
  };
  const rows = items.map((item) => {
    const account = String(item?.account || "-");
    const tpl = String(item?.active_template || "-");
    const slot = String(item?.slot_key || "-");
    const start = String(item?.segment_started_at || "-");
    const end = String(item?.segment_ended_at || "-");
    const reason = String(item?.segment_reason || "");
    const cfg = item?.template_config && typeof item.template_config === "object" ? item.template_config : {};
    const counts = item?.action_counts && typeof item.action_counts === "object" ? item.action_counts : {};
    const cfgParts = [];
    if (cfg.mutual_traffic_comment) cfgParts.push("引流评");
    if (cfg.mutual_traffic_author_follow) cfgParts.push("作者区关注");
    if (cfg.mutual_traffic_followers_follow) cfgParts.push("Followers关注");
    if (cfg.mutual_traffic_follow_only) cfgParts.push("仅关注");
    const probParts = ["like_prob", "comment_prob", "favorite_prob", "share_prob"]
      .filter((k) => typeof cfg[k] === "number" && cfg[k] > 0)
      .map((k) => `${actionLabel[k.replace("_prob", "")] || k}${cfg[k]}`);
    const countParts = Object.entries(counts)
      .filter(([, v]) => Number(v) > 0)
      .map(([k, v]) => `${actionLabel[k] || k}×${v}`);
    const cfgText = [...probParts, ...cfgParts].join(" · ") || "—";
    const countText = countParts.length ? countParts.join(" · ") : "无计数";
    return `<li><strong>${escapeHtml(account)}</strong> · ${escapeHtml(tpl)} · ${escapeHtml(start)}~${escapeHtml(end)}` +
      `${reason ? ` · ${escapeHtml(reason)}` : ""}<br/>` +
      `<span class="hint">时段 ${escapeHtml(slot)} · 模板配置 ${escapeHtml(cfgText)} · 执行 ${escapeHtml(countText)}</span></li>`;
  });
  return `
    <div class="auto-switch-activity-audit" style="margin:8px 0;">
      <div class="hint"><strong>账号任务对账</strong> · ${items.length} 段</div>
      <ul class="auto-switch-summary-notes">${rows.join("")}</ul>
    </div>
  `;
}

function renderAutoSwitchSessionSummaryCard(item) {
  const alias = String(item?.alias || "-");
  const deviceId = String(item?.device_id || "");
  const scheduleDate = String(item?.schedule_date || "");
  const createdAt = String(item?.created_at || "");
  const sessionId = String(item?.session_id || "");
  const stats = item?.stats && typeof item.stats === "object" ? item.stats : {};
  const statsLine = [
    `操作 ${Number(stats.total ?? 0)}`,
    `成功 ${Number(stats.success ?? 0)}`,
    `失败 ${Number(stats.failed ?? 0)}`,
    Number(stats.cut_switch_missing_backup ?? 0) > 0
      ? `备份缺失改登录 ${Number(stats.cut_switch_missing_backup ?? 0)}`
      : "",
  ].filter(Boolean).join(" · ");
  const notes = Array.isArray(item?.reconciliation?.notes) ? item.reconciliation.notes : [];
  const notesHtml = notes.length
    ? `<ul class="auto-switch-summary-notes">${notes.map((n) => `<li>${escapeHtml(String(n || ""))}</li>`).join("")}</ul>`
    : "";
  const humanLog = String(item?.human_log || "").trim();
  const smartDebugHtml = renderSmartTemplateDebugBlock(item?.smart_template_debug);
  const activityHtml = renderAccountActivityAuditBlock(item?.reconciliation);
  return `
    <div class="panel-card auto-switch-summary-card">
      <div class="auto-switch-summary-head">
        <strong>${escapeHtml(alias)}</strong>
        <span class="hint">${escapeHtml(deviceId)} · ${escapeHtml(scheduleDate)} · ${escapeHtml(createdAt)}</span>
      </div>
      <div class="hint" style="margin:6px 0;">${escapeHtml(sessionId)} · ${escapeHtml(statsLine)}</div>
      ${activityHtml}
      ${smartDebugHtml}
      ${notesHtml}
      <pre class="auto-switch-human-log">${escapeHtml(humanLog || "（无 human_log）")}</pre>
    </div>
  `;
}

function getAutoSwitchSessionSummariesAliasFilter() {
  const fromSelect = String(autoSwitchSessionSummariesAliasSelect?.value ?? "").trim();
  if (fromSelect || !autoSwitchSessionSummariesAliasSelect) return fromSelect;
  try {
    return String(localStorage.getItem(AUTO_SWITCH_SESSION_SUMMARIES_ALIAS_KEY) || "").trim();
  } catch (_) {
    return "";
  }
}

function populateAutoSwitchSessionSummariesAliasOptions() {
  if (!autoSwitchSessionSummariesAliasSelect) return;
  const prev = getAutoSwitchSessionSummariesAliasFilter();
  const seen = new Set();
  const options = ['<option value="">全部别名</option>'];
  (boundDeviceAliasPairs || []).forEach(({ device_alias }) => {
    const alias = normalizeAliasValue(device_alias);
    if (!alias || seen.has(alias)) return;
    seen.add(alias);
    options.push(`<option value="${escapeHtml(alias)}">${escapeHtml(alias)}</option>`);
  });
  autoSwitchSessionSummariesAliasSelect.innerHTML = options.join("");
  const restore = prev && [...autoSwitchSessionSummariesAliasSelect.options].some((opt) => opt.value === prev) ? prev : "";
  autoSwitchSessionSummariesAliasSelect.value = restore;
  try {
    localStorage.setItem(AUTO_SWITCH_SESSION_SUMMARIES_ALIAS_KEY, restore);
  } catch (_) {}
}

async function loadAutoSwitchSessionSummaries() {
  if (!autoSwitchSessionSummariesEl) return;
  const aliasFilter = getAutoSwitchSessionSummariesAliasFilter();
  const qs = new URLSearchParams({ limit: "15" });
  if (aliasFilter) qs.set("alias", aliasFilter);
  try {
    const data = await request(`/api/admin/auto_switch/session_summaries?${qs.toString()}`);
    const items = Array.isArray(data?.data?.items) ? data.data.items : [];
    if (autoSwitchSessionSummariesHint) {
      const hintParts = [];
      if (aliasFilter) hintParts.push(aliasFilter);
      hintParts.push(items.length ? `最近 ${items.length} 条` : "无记录");
      autoSwitchSessionSummariesHint.textContent = hintParts.join(" · ");
    }
    if (!items.length) {
      autoSwitchSessionSummariesEl.innerHTML = aliasFilter
        ? `<div class="hint">别名「${escapeHtml(aliasFilter)}」暂无已关闭的自动切号会话总结</div>`
        : '<div class="hint">暂无已关闭的自动切号会话总结（关闭设备自动切号后由设备端一次性上报）</div>';
      return;
    }
    autoSwitchSessionSummariesEl.innerHTML = items.map((item) => renderAutoSwitchSessionSummaryCard(item)).join("");
  } catch (error) {
    autoSwitchSessionSummariesEl.innerHTML = `<div class="hint">加载会话总结失败：${escapeHtml(error.message || "unknown")}</div>`;
  }
}

async function saveAutoSwitchForDeviceIds(deviceIds, enabled, options = {}) {
  await loadBoundDeviceAliases().catch(() => {});
  const raw = Array.from(new Set((deviceIds || []).map((d) => String(d || "").trim()).filter(Boolean)));
  const ids = filterAutoSwitchDeviceIds(raw);
  if (!ids.length) {
    setStatus(
      raw.length
        ? "所选别名未绑定有效 device_id（档案内 device_id 可能已过期），请先在设备总览确认客户端已绑定"
        : "请先勾选要操作的设备别名组",
      "warning",
    );
    return;
  }
  const data = await request("/api/admin/auto_switch/devices", {
    method: "POST",
    body: JSON.stringify({ device_ids: ids, enabled: !!enabled }),
  });
  await loadAutoSwitchConfig({ refreshArchive: true });
  if (options.refreshStatus !== false) {
    await refreshTaskStatus();
  }
  const resp = data?.data || {};
  const notified = Number(resp.devices_notified || 0);
  const changed = Number(resp.devices_changed || 0);
  const resynced = Number(resp.devices_resynced || 0);
  setStatus(
    enabled
      ? resynced > 0 && changed === 0
        ? `已向 ${notified} 台设备推送自动切号资料（${resynced} 台资料重同步）`
        : `已向 ${notified} 台设备推送资料（${changed} 台新开启${resynced > 0 ? `，${resynced} 台重同步` : ""}）`
      : `已为 ${changed} 台设备关闭自动切号`,
    "success",
  );
}

async function saveAutoSwitchConfig(options = {}) {
  if (autoSwitchSaveInFlight) {
    setStatus("自动切号配置保存中，请稍候…", "info");
    return;
  }
  const enabled = !!options.enabled;
  await loadBoundDeviceAliases().catch(() => {});
  const deviceIds = filterAutoSwitchDeviceIds(
    (options.deviceIds && options.deviceIds.length)
      ? options.deviceIds
      : collectAutoSwitchTargetDeviceIds(),
  );
  if (!deviceIds.length) {
    setStatus(
      countVisibleAutoSwitchSelectedGroups() > 0
        ? "所选别名未绑定有效 device_id，请先在设备总览确认客户端已绑定后再开启自动切号"
        : "请先选择设备：勾选别名组左侧复选框，或点「全选本页」",
      "warning",
    );
    return;
  }
  autoSwitchPickDeviceIds = new Set(deviceIds);
  autoSwitchSaveInFlight = true;
  applyAutoSwitchManualOpsUi();
  try {
    await saveAutoSwitchForDeviceIds(deviceIds, enabled);
  } catch (error) {
    await loadAutoSwitchConfig({ refreshArchive: true });
    setStatus(error.message || "保存自动切号配置失败", "error");
  } finally {
    autoSwitchSaveInFlight = false;
    applyAutoSwitchManualOpsUi();
  }
}

async function collectAllAutoSwitchDeviceIds() {
  const ids = new Set();
  try {
    const cfgResp = await request("/api/admin/auto_switch/config");
    const devices = Array.isArray(cfgResp?.data?.devices) ? cfgResp.data.devices : [];
    devices.forEach((item) => {
      const did = String(item?.device_id || "").trim();
      if (did) ids.add(did);
    });
  } catch (_) {
    /* 下方仍会用别名绑定表兜底 */
  }
  buildAliasDevicePairs().forEach((item) => {
    const did = String(item?.device_id || "").trim();
    if (did) ids.add(did);
  });
  return Array.from(ids);
}

async function enableAutoSwitchForAllDevices() {
  if (autoSwitchSaveInFlight) return;
  autoSwitchSaveInFlight = true;
  applyAutoSwitchManualOpsUi();
  try {
    await loadBoundDeviceAliases().catch(() => {});
    const deviceIds = await collectAllAutoSwitchDeviceIds();
    if (!deviceIds.length) {
      setStatus("当前账号下没有可开启自动切号的设备（请确认客户端已绑定）", "warning");
      return;
    }
    autoSwitchPickDeviceIds = new Set(deviceIds);
    await saveAutoSwitchForDeviceIds(deviceIds, true);
  } catch (error) {
    await loadAutoSwitchConfig({ refreshArchive: true });
    setStatus(error.message || "全选并开启失败", "error");
  } finally {
    autoSwitchSaveInFlight = false;
    applyAutoSwitchManualOpsUi();
  }
}

function applyBackupStatsFullQueryScheduleUi(cfg = {}) {
  backupStatsFullQueryScheduleAvailable = cfg.available !== false;
  const available = backupStatsFullQueryScheduleAvailable;
  const label = backupStatsFullQueryEnabledToggle?.closest("label");
  if (backupStatsFullQueryEnabledToggle) {
    backupStatsFullQueryEnabledToggle.checked = available && !!cfg.enabled;
    backupStatsFullQueryEnabledToggle.disabled = !available;
  }
  if (label) {
    label.style.opacity = available ? "" : "0.55";
    label.title = available
      ? "每天北京时间 0:00 自动对全部 owner 的备份档案执行全量查询并落库（不限分组）"
      : (String(cfg.disabled_reason || "").trim() || "每日定时全量查询暂时不可用");
  }
  applyBackupStatsFullQueryManualUi(cfg);
}

function applyBackupStatsFullQueryManualUi(cfg = {}) {
  backupStatsFullQueryManualAvailable = cfg.manual_available !== false;
  const available = backupStatsFullQueryManualAvailable;
  if (fetchTiktokStatsFullBtn) {
    fetchTiktokStatsFullBtn.disabled = !available || backupStatsFullQueryInFlight;
    fetchTiktokStatsFullBtn.style.opacity = available ? "" : "0.55";
    fetchTiktokStatsFullBtn.title = available
      ? "批量查询全部备份账号的 TikTok 数据并写回档案（无需手动填用户名）"
      : (String(cfg.manual_disabled_reason || "").trim() || "暂时不可用：全量查询并落库功能已关闭");
  }
}

async function loadBackupStatsFullQueryConfig() {
  try {
    const data = await request("/api/admin/backup_stats_full_query/config");
    const cfg = data.data || {};
    applyBackupStatsFullQueryScheduleUi(cfg);
  } catch (_) {
    applyBackupStatsFullQueryScheduleUi({ available: false, enabled: false, manual_available: false });
  }
}

async function saveBackupStatsFullQueryConfig(options = {}) {
  if (backupStatsFullQuerySaveInFlight) return;
  if (!backupStatsFullQueryScheduleAvailable) {
    setStatus("每日定时全量查询暂时不可用", "warning");
    applyBackupStatsFullQueryScheduleUi({ available: false, enabled: false });
    return;
  }
  backupStatsFullQuerySaveInFlight = true;
  const enabled = !!backupStatsFullQueryEnabledToggle?.checked;
  try {
    await request("/api/admin/backup_stats_full_query/config", {
      method: "POST",
      body: JSON.stringify({ enabled }),
    });
    await loadBackupStatsFullQueryConfig();
    if (options.refreshStatus !== false) {
      await refreshTaskStatus();
    }
    setStatus(enabled ? "已开启每日 0 点自动全量查询" : "已关闭每日自动全量查询", "success");
  } catch (error) {
    await loadBackupStatsFullQueryConfig();
    setStatus(error.message || "保存失败", "error");
  } finally {
    backupStatsFullQuerySaveInFlight = false;
  }
}

async function startSchedulers() {
  try {
    await request("/api/admin/tasks/scheduler/start", { method: "POST" });
    await refreshTaskStatus();
    setStatus("定时任务总开关已开启", "success");
  } catch (error) {
    setStatus(error.message || "开启失败", "error");
  }
}

async function stopSchedulers() {
  try {
    await request("/api/admin/tasks/scheduler/stop", { method: "POST" });
    await refreshTaskStatus();
    setStatus("定时任务总开关已关闭", "success");
  } catch (error) {
    setStatus(error.message || "停止失败", "error");
  }
}

async function loadUserRuntimeConfig() {
  try {
    const data = await request(`/api/admin/runtime_config?_ts=${Date.now()}`);
    const cfg = data.data || {};
    // 刷新后优先沿用本地上次选择的模式，避免被服务端 run_mode 覆盖导致“模板看起来被切回去”。
    const rawSavedMode = localStorage.getItem(RUN_MODE_KEY) || cfg.run_mode || currentRunMode || "growth";
    const savedMode = rawSavedMode === "mutual" ? "growth" : rawSavedMode;
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
      : { growth: {}, live: {}, direct: {}, mutual: {} };
    smartTemplateConfig = (cfg?.smart_template_config && typeof cfg.smart_template_config === "object")
      ? cfg.smart_template_config
      : smartTemplateConfig;
    aliasSmartTemplateByMode = (cfg?.alias_smart_template_by_mode
      && typeof cfg.alias_smart_template_by_mode === "object")
      ? cfg.alias_smart_template_by_mode
      : { growth: {}, live: {}, direct: {}, mutual: {} };
    applySmartTemplateConfigToForm(smartTemplateConfig);
    smartTemplatePreviewCache = {};
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

function buildMutualOpTargetsForSelectedDevices() {
  const selectedIds = selectedDeviceIds();
  if (!selectedIds.length) {
    setStatus("请先勾选设备", "warning");
    return null;
  }
  const targets = selectedIds
    .map((id) => {
      const info = latestDevices?.[id] || {};
      const alias = String(info?.alias || "").trim();
      if (!alias) return null;
      return { device_id: id, alias, package_name: "com.zhiliaoapp.musically" };
    })
    .filter(Boolean);
  if (!targets.length) {
    setStatus("所选设备缺少别名，无法启动互点流程", "warning");
    return null;
  }
  return targets;
}

async function startMutualClickForSelectedDevices(options = {}) {
  const forceFollowOnly = options.forceFollowOnly === true;
  const targets = buildMutualOpTargetsForSelectedDevices();
  if (!targets) return;

  let successCount = 0;
  let failedCount = 0;
  let followOnlyCount = 0;
  let trafficCount = 0;
  const statusPrefix = forceFollowOnly
    ? "互点-仅关注"
    : "互点";
  setStatus(`正在为所选设备启动${statusPrefix}（${targets.length} 台）...`, "info");
  for (const payload of targets) {
    const info = latestDevices?.[payload.device_id] || {};
    const tiktokUsername = String(info.tiktok_username || info.tiktokUsername || "").trim();
    const templateUi = resolveEffectiveDeviceTemplateUI(payload.alias, tiktokUsername);
    const useFollowOnly =
      forceFollowOnly || isMutualFollowOnlyInTemplateConfig(templateUi.selectedTemplate, tiktokUsername);
    const apiPath = useFollowOnly
      ? "/api/admin/device_tiktok/mutual_click_follow_only_start"
      : "/api/admin/device_tiktok/mutual_click_start";
    if (useFollowOnly) followOnlyCount += 1;
    else trafficCount += 1;
    try {
      await request(apiPath, {
        method: "POST",
        body: JSON.stringify(payload),
      });
      successCount += 1;
    } catch (_) {
      failedCount += 1;
    }
  }
  await loadOverview();
  const branchHint =
    followOnlyCount && trafficCount
      ? `（仅关注 ${followOnlyCount} / 引流 ${trafficCount}）`
      : followOnlyCount
        ? "（仅关注分支）"
        : forceFollowOnly
          ? ""
          : "（引流分支）";
  if (!failedCount) {
    setStatus(
      `${statusPrefix}启动指令已下发${branchHint}（${successCount}/${targets.length}）`,
      "success",
    );
  } else {
    setStatus(
      `${statusPrefix}部分下发失败${branchHint}：成功 ${successCount}，失败 ${failedCount}`,
      "warning",
    );
  }
}

async function startMutualFollowOnlyForSelectedDevices() {
  return startMutualClickForSelectedDevices({ forceFollowOnly: true });
}

async function apiPublishPostStart(payload) {
  return request("/api/admin/device_tiktok/publish_post_start", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

async function startPublishPostForSelectedDevices() {
  const selectedIds = selectedDeviceIds();
  for (const id of selectedIds) {
    if (!guardAutoSwitchManualOps("发作品", id)) return;
  }
  if (!selectedIds.length) {
    setStatus("请先勾选设备", "warning");
    return;
  }

  const targets = selectedIds
    .map((id) => {
      const info = latestDevices?.[id] || {};
      const alias = String(info?.alias || "").trim();
      if (!alias) return null;
      return { device_id: id, alias, package_name: "com.zhiliaoapp.musically" };
    })
    .filter(Boolean);

  if (!targets.length) {
    setStatus("所选设备缺少别名，无法下发发作品任务", "warning");
    return;
  }

  if (publishPostStartSelectedBtn) publishPostStartSelectedBtn.disabled = true;
  try {
    const startedAt = new Date().toLocaleString();
    appendPublishPostBatchLog(
      `======== 发作品批量执行 ${startedAt}（${targets.length} 台）========\n下发中…`,
      true,
    );
    setStatus(`正在为所选设备下发发作品入口（${targets.length} 台）...`, "info");

    const queued = [];
    const enqueueFailed = [];
    for (const payload of targets) {
      try {
        const resp = await apiPublishPostStart(payload);
        const opId = String(resp?.op_id || "").trim();
        if (!opId) {
          enqueueFailed.push({ alias: payload.alias, error: resp?.msg || "未返回 op_id" });
          appendPublishPostBatchLog(`❌ ${payload.alias}：下发失败（无 op_id）`);
          continue;
        }
        queued.push({ ...payload, opId });
        appendPublishPostBatchLog(`⏳ ${payload.alias}：已下发，等待最终结果（op=${opId.slice(0, 8)}…）`);
      } catch (err) {
        const msg = err?.message || "下发失败";
        enqueueFailed.push({ alias: payload.alias, error: msg });
        appendPublishPostBatchLog(`❌ ${payload.alias}：下发失败 — ${msg}`);
      }
    }

    setStatus(
      `发作品已下发 ${queued.length}/${targets.length} 台，正在等待各设备最终执行结果…`,
      queued.length ? "info" : "error",
    );

    const finals = await Promise.all(
      queued.map(async (item) => {
        try {
          const result = await waitForPublishPostFinalResult(item.opId, 900000);
          return { alias: item.alias, result, error: null };
        } catch (err) {
          return { alias: item.alias, result: null, error: err?.message || "等待超时" };
        }
      }),
    );

    let okCount = 0;
    let failCount = enqueueFailed.length;
    const lines = [`======== 发作品最终结果汇总 ${new Date().toLocaleString()} ========`];
    for (const row of enqueueFailed) {
      lines.push(`❌ ${row.alias}：下发失败 — ${row.error}`);
    }
    for (const row of finals) {
      const line = formatPublishPostFinalLine(row.alias, row.result, row.error);
      lines.push(line);
      const data = row.result?.data && typeof row.result.data === "object" ? row.result.data : {};
      const ok = !row.error && row.result?.status === "success" && Boolean(data.publish_post_completed);
      if (ok) okCount += 1;
      else failCount += 1;
    }
    lines.push(`-------- 合计：成功 ${okCount} · 失败 ${failCount} · 共 ${targets.length} 台 --------`);
    appendPublishPostBatchLog(lines.join("\n"), true);

    await loadOverview();
    if (!failCount) {
      setStatus(`发作品全部成功（${okCount}/${targets.length}），详见「发作品执行汇总」`, "success");
    } else if (okCount > 0) {
      setStatus(`发作品部分完成：成功 ${okCount}，失败 ${failCount}，详见「发作品执行汇总」`, "warning");
    } else {
      setStatus(`发作品全部失败（${failCount}/${targets.length}），详见「发作品执行汇总」`, "error");
    }
  } finally {
    if (publishPostStartSelectedBtn) publishPostStartSelectedBtn.disabled = false;
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
    const base = String(await resolvedApiBase()).replace(/\/$/, "");
    const response = await fetch(`${base}/admin/live_customers/import`, {
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

async function collectBackupArchiveUsernamesForQuery(options = {}) {
  const scope = options.scope === "page" ? "page" : "group";
  const pageSize = scope === "page" ? persistBackupArchivePageSize() : MAX_BACKUP_ARCHIVE_PAGE_SIZE;
  const usernames = [];
  const seen = new Set();
  let page = scope === "page" ? currentBackupArchivePage : 1;
  let totalPages = 1;

  do {
    const params = new URLSearchParams({
      page: String(page),
      page_size: String(pageSize),
    });
    const data = await request(`/api/admin/editor/device_rows?${params.toString()}`);
    const rows = Array.isArray(data.items) ? data.items : [];
    rows.forEach((row) => {
      const username = String(row?.username || "").trim().replace(/^@+/, "");
      if (!username) return;
      const key = username.toLowerCase();
      if (seen.has(key)) return;
      seen.add(key);
      usernames.push(username);
    });
    const total = Number(data?.total ?? 0) || 0;
    const effectivePageSize = Number(data?.page_size ?? pageSize) || pageSize;
    totalPages = Math.max(1, Math.ceil(total / effectivePageSize));
    if (scope === "page") break;
    page += 1;
  } while (page <= totalPages);

  return { usernames };
}

async function fetchTiktokStats() {
  if (statsXlsxBusy) {
    setStatus("批量查询进行中，请稍候", "warning");
    return;
  }
  const username = (statsUsernameInput?.value || "").trim();

  if (!username) {
    setStatus("请先填写用户名", "warning");
    return;
  }

  try {
    setStatus("正在查询 TikTok 数据...", "info");
    if (statsResultEl) {
      statsResultEl.textContent = "查询中，请稍候...";
    }
    const include_raw = Boolean(statsIncludeRawInput?.checked);
    const data = await request("/api/admin/tiktok_profile_stats", {
      method: "POST",
      body: JSON.stringify({
        username,
        include_raw,
        write_back: false,
      }),
    });

    if (statsResultEl) {
      if (data && Number(data.code) === 0 && data.universal_extract) {
        const summary = [
          `账号私密: ${data.private_account_guess ?? "—"}`,
          `关注列表 visibility: ${data.profile_following_visibility ?? "—"} (1=公开)`,
          `关注列表对他人隐藏: ${data.following_list_hidden_guess ?? "—"}`,
        ].join("\n");
        const lines = [summary, "", JSON.stringify(data.universal_extract, null, 2)];
        if (include_raw) {
          const debug = {
            username: data.username,
            private_account_guess: data.private_account_guess,
            private_account_reason: data.private_account_reason,
            profile_following_visibility: data.profile_following_visibility,
            following_list_hidden_guess: data.following_list_hidden_guess,
            following_list_hidden_reason: data.following_list_hidden_reason,
            debug: data.debug,
            raw_response: data.raw_response,
          };
          lines.push("\n--- 调试信息（已省略完整 HTTP headers）---\n");
          lines.push(JSON.stringify(debug, null, 2));
        }
        statsResultEl.textContent = lines.join("\n");
      } else {
        statsResultEl.textContent = JSON.stringify(data, null, 2);
      }
    }

    setStatus("查询成功", "success");
  } catch (error) {
    if (statsResultEl) {
      statsResultEl.textContent = `查询失败: ${error.message || "unknown error"}`;
    }
    setStatus(error.message || "查询失败", "error");
  }
}

function formatTiktokStatsMetricsLine(metrics) {
  const keys = ["粉丝量", "关注数", "总赞", "视频数", "点赞量"];
  return keys.map((key) => `${key}=${metrics?.[key] ?? ""}`).join("  ");
}

function setTiktokStatsXlsxBusy(busy) {
  statsXlsxBusy = Boolean(busy);
  if (fetchTiktokStatsBtn) fetchTiktokStatsBtn.disabled = statsXlsxBusy;
  if (fetchTiktokStatsXlsxBtn) {
    fetchTiktokStatsXlsxBtn.disabled = statsXlsxBusy;
    fetchTiktokStatsXlsxBtn.textContent = statsXlsxBusy ? "批量查询中..." : "上传表格并查询";
  }
  if (statsXlsxFileInput) statsXlsxFileInput.disabled = statsXlsxBusy;
  if (downloadTiktokStatsXlsxBtn) {
    downloadTiktokStatsXlsxBtn.disabled = statsXlsxBusy || !statsXlsxSessionId;
  }
}

function renderTiktokStatsXlsxProgress(lines, debugPayload) {
  if (!statsResultEl) return;
  const body = lines.join("\n");
  if (debugPayload) {
    statsResultEl.textContent = `${body}\n\n--- 调试信息（已省略完整 HTTP headers）---\n${JSON.stringify(debugPayload, null, 2)}`;
    return;
  }
  statsResultEl.textContent = body;
}

async function downloadTiktokStatsXlsx() {
  if (!statsXlsxSessionId) {
    setStatus("请先完成表格查询", "warning");
    return;
  }
  const token = authToken();
  const url = await buildApiUrl(
    `/api/admin/tiktok_profile_stats/xlsx_download/${encodeURIComponent(statsXlsxSessionId)}`,
  );
  const response = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    cache: "no-store",
  });
  const contentType = String(response.headers.get("content-type") || "");
  if (!response.ok || contentType.includes("application/json")) {
    const data = await response.json().catch(() => ({}));
    throw new Error(String(data?.msg || `下载失败 (HTTP ${response.status})`));
  }
  const blob = await response.blob();
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = objectUrl;
  a.download = statsXlsxDownloadName || "tiktok_stats_queried.xlsx";
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(objectUrl);
}

async function runTiktokStatsXlsxBatch(file) {
  if (!file || statsXlsxBusy) return;
  const name = String(file.name || "").toLowerCase();
  if (!name.endsWith(".xlsx")) {
    setStatus("请选择 .xlsx 文件", "warning");
    return;
  }
  const token = ++statsXlsxBatchToken;
  setTiktokStatsXlsxBusy(true);
  const includeRaw = Boolean(statsIncludeRawInput?.checked);
  const progressLines = [];
  try {
    if (statsXlsxHintEl) statsXlsxHintEl.textContent = `正在上传 ${file.name} ...`;
    renderTiktokStatsXlsxProgress(["正在上传表格..."]);
    setStatus("正在上传表格...", "info");
    const form = new FormData();
    form.append("file", file);
    const uploadData = await fetchFormDataApi("/api/admin/tiktok_profile_stats/xlsx_upload", form);
    if (token !== statsXlsxBatchToken) return;
    statsXlsxSessionId = String(uploadData?.session_id || "").trim();
    statsXlsxDownloadName = String(uploadData?.download_name || "").trim();
    const rows = Array.isArray(uploadData?.rows) ? uploadData.rows : [];
    const total = rows.length;
    if (statsXlsxHintEl) {
      statsXlsxHintEl.textContent = `已上传 ${file.name}，共 ${total} 行，开始查询...`;
    }
    if (!statsXlsxSessionId || !total) {
      throw new Error("表格中没有可查询的 username");
    }
    let okCount = 0;
    let failCount = 0;
    for (let i = 0; i < total; i += 1) {
      if (token !== statsXlsxBatchToken) return;
      const row = rows[i] || {};
      const username = String(row.username || "").trim();
      const sheetRow = Number(row.sheet_row || 0);
      progressLines.push(`[${i + 1}/${total}] ${username || "-"} 查询中...`);
      renderTiktokStatsXlsxProgress(progressLines);
      setStatus(`正在查询 ${i + 1}/${total}：${username}`, "info");
      let line = `[${i + 1}/${total}] ${username || "-"}`;
      let debugPayload = null;
      try {
        const data = await request("/api/admin/tiktok_profile_stats/xlsx_query_row", {
          method: "POST",
          timeoutMs: 45000,
          body: JSON.stringify({
            session_id: statsXlsxSessionId,
            sheet_row: sheetRow,
            include_raw: includeRaw,
          }),
        });
        if (token !== statsXlsxBatchToken) return;
        if (data?.query_ok) {
          okCount += 1;
          line = `${line}  ${formatTiktokStatsMetricsLine(data.metrics)}`;
        } else {
          failCount += 1;
          line = `${line}  失败：${data?.msg || "查询失败"}`;
        }
        if (includeRaw) {
          debugPayload = {
            username: data?.username,
            private_account_guess: data?.private_account_guess,
            private_account_reason: data?.private_account_reason,
            profile_following_visibility: data?.profile_following_visibility,
            following_list_hidden_guess: data?.following_list_hidden_guess,
            following_list_hidden_reason: data?.following_list_hidden_reason,
            debug: data?.debug,
            raw_response: data?.raw_response,
          };
        }
      } catch (error) {
        failCount += 1;
        line = `${line}  失败：${error.message || "查询失败"}`;
      }
      progressLines[progressLines.length - 1] = line;
      renderTiktokStatsXlsxProgress(progressLines, debugPayload);
      if (i < total - 1) {
        await new Promise((resolve) => setTimeout(resolve, 300));
      }
    }
    progressLines.push("");
    progressLines.push(`完成：成功 ${okCount}，失败 ${failCount}，共 ${total} 行`);
    renderTiktokStatsXlsxProgress(progressLines);
    if (statsXlsxHintEl) {
      statsXlsxHintEl.textContent = `查询完成：成功 ${okCount}，失败 ${failCount}。可下载写回后的表格。`;
    }
    try {
      await downloadTiktokStatsXlsx();
      setStatus(`批量查询完成，已下载表格（成功 ${okCount}，失败 ${failCount}）`, failCount ? "warning" : "success");
    } catch (downloadError) {
      setStatus(
        `查询完成，但自动下载失败：${downloadError.message || "unknown error"}。请点击「下载写回表格」`,
        "warning",
      );
    }
  } catch (error) {
    if (token !== statsXlsxBatchToken) return;
    const msg = error.message || "unknown error";
    if (statsResultEl) {
      if (progressLines.length) {
        progressLines.push(`批量查询中断: ${msg}`);
        renderTiktokStatsXlsxProgress(progressLines);
      } else {
        statsResultEl.textContent = `批量查询失败: ${msg}`;
      }
    }
    setStatus(error.message || "批量查询失败", "error");
  } finally {
    if (token === statsXlsxBatchToken) {
      setTiktokStatsXlsxBusy(false);
      if (statsXlsxFileInput) statsXlsxFileInput.value = "";
    }
  }
}

function pickTiktokStatsXlsxFile() {
  if (statsXlsxBusy) return;
  if (statsXlsxFileInput) {
    statsXlsxFileInput.click();
    return;
  }
}

async function fetchTiktokStatsFullForBackupArchive() {
  if (!backupStatsFullQueryManualAvailable) {
    setStatus("全量查询并落库暂时不可用", "warning");
    return;
  }
  if (backupStatsFullQueryInFlight) {
    setStatus("全量查询正在进行中，请稍候", "warning");
    return;
  }

  backupStatsFullQueryInFlight = true;
  if (fetchTiktokStatsFullBtn) fetchTiktokStatsFullBtn.disabled = true;

  try {
    setStatus("正在收集备份账号...", "info");

    const { usernames } = await collectBackupArchiveUsernamesForQuery({ scope: "group" });
    if (!usernames.length) {
      setStatus("没有可查询的用户名", "warning");
      return;
    }

    const total = usernames.length;
    let ok = 0;
    let fail = 0;
    const failedItems = [];

    for (let i = 0; i < usernames.length; i += 1) {
      const username = usernames[i];
      const index = i + 1;
      const remain = Math.max(total - index, 0);
      const progress = `正在全量查询第 ${index} 个，还剩 ${remain} 个`;
      setStatus(`${progress}（@${username}）`, "info");
      if (deviceAliasGroupsPagerEl) {
        deviceAliasGroupsPagerEl.textContent = `${progress} | 共 ${total} 个账号`;
      }

      try {
        const data = await request("/api/admin/tiktok_profile_stats", {
          method: "POST",
          body: JSON.stringify({
            username,
            include_raw: false,
            write_back: true,
            query_index: index,
            query_total: total,
          }),
        });
        if (data && Number(data.code) === 0) {
          ok += 1;
        } else {
          fail += 1;
          failedItems.push(`${username}: ${data?.msg || "未知错误"}`);
        }
      } catch (error) {
        fail += 1;
        failedItems.push(`${username}: ${error.message || "查询失败"}`);
      }

      if (i < usernames.length - 1) {
        await new Promise((resolve) => setTimeout(resolve, 300));
      }
    }

    await loadBackupArchiveRecords();

    const summary = `全量查询完成：成功 ${ok}/${total}，失败 ${fail}`;
    if (fail > 0) {
      const detail = failedItems.slice(0, 3).join("；");
      const suffix = failedItems.length > 3 ? "…" : "";
      setStatus(`${summary}；失败：${detail}${suffix}`, fail === total ? "error" : "warning");
    } else {
      setStatus(summary, "success");
    }
  } catch (error) {
    setStatus(error.message || "全量查询失败", "error");
  } finally {
    backupStatsFullQueryInFlight = false;
    applyBackupStatsFullQueryManualUi({ manual_available: backupStatsFullQueryManualAvailable });
    updateBackupArchivePagerUi();
  }
}

function collectDeviceIdentityByAlias(alias, options = {}) {
  const normalizedAlias = String(alias || "").trim();
  if (!normalizedAlias) {
    setStatus("未提供设备别名", "warning");
    return null;
  }
  // 兜底：若旧版本前端脚本未声明 backupPackageNameInput，避免启动阶段直接 ReferenceError
  const package_name = String(
    options.package_name
      || (typeof backupPackageNameInput !== "undefined" && backupPackageNameInput ? backupPackageNameInput.value : "")
      || ""
  ).trim();
  if (!package_name && options.requirePackageName !== false) {
    setStatus("请先填写包名", "warning");
    return null;
  }
  const matches = Object.entries(latestDevices || {}).filter(([, info]) => String(info?.alias || "").trim() === normalizedAlias);
  if (!matches.length) {
    setStatus(`未找到别名为 ${normalizedAlias} 的在线设备`, "warning");
    return null;
  }
  if (matches.length > 1) {
    setStatus(`检测到多个在线设备使用别名 ${normalizedAlias}，为避免误操作已阻止执行`, "warning");
    return null;
  }
  const [device_id] = matches[0];
  return { device_id, alias: normalizedAlias, package_name };
}

/** 备份档案行的 device_id 可能过期；始终以当前在线设备的 device_id 下发远控。 */
function resolveLiveDeviceIdentityForArchive(actionAlias, rowDeviceId = "", options = {}) {
  const normalizedAlias = String(actionAlias || "").trim();
  if (!normalizedAlias) {
    setStatus("未提供设备别名", "warning");
    return null;
  }
  const live = collectDeviceIdentityByAlias(normalizedAlias, options);
  if (!live) return null;
  const rowId = String(rowDeviceId || "").trim();
  if (rowId && rowId !== live.device_id) {
    console.warn(
      `[backup-archive] 档案 device_id=${rowId} 与当前在线设备 ${live.device_id} 不一致，已使用在线设备`,
    );
  }
  return live;
}

function collectDeviceIdentityByDeviceAndAlias(deviceId, alias, options = {}) {
  const normalizedDeviceId = String(deviceId || "").trim();
  const normalizedAlias = String(alias || "").trim();
  if (!normalizedDeviceId || !normalizedAlias) {
    return resolveLiveDeviceIdentityForArchive(normalizedAlias, "", options);
  }
  // 兜底：若旧版本前端脚本未声明 backupPackageNameInput，避免启动阶段直接 ReferenceError
  const package_name = String(
    options.package_name
      || (typeof backupPackageNameInput !== "undefined" && backupPackageNameInput ? backupPackageNameInput.value : "")
      || ""
  ).trim();
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


async function waitForDeviceOpResult(opId, timeoutMs = 120000, graceAfterTimeoutMs = 0) {
  const start = Date.now();
  logBackupAction("POLL_START", { opId, timeoutMs, graceAfterTimeoutMs });

  async function pollOnce() {
    const pollPath = `/api/admin/device_tiktok/op_result?op_id=${encodeURIComponent(opId)}`;
    const base = String(await resolvedApiBase()).replace(/\/$/, "");
    const dedupedPollPath = base.endsWith("/api") && pollPath.startsWith("/api/")
      ? pollPath.slice(4)
      : pollPath;
    const pollUrl = `${base}${dedupedPollPath}`;
    logBackupAction("POLL_REQUEST", { url: pollUrl });
    const token = authToken();
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const response = await fetch(pollUrl, { method: "GET", headers });
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
      if (backupCloudInfoEl) {
        backupCloudInfoEl.textContent = formatBackupCloudReceiptFromOpPollPayload(data);
      }
      return { done: true, result: data.result };
    }
    if (data.status !== "pending" && data.code !== 1) {
      throw new Error(data?.msg || "轮询返回异常");
    }
    return { done: false };
  }

  while (Date.now() - start < timeoutMs) {
    const r = await pollOnce();
    if (r.done) return r.result;
    await new Promise((res) => setTimeout(res, 2000));
  }
  logBackupAction("POLL_PRIMARY_TIMEOUT", { opId, timeoutMs });
  if (graceAfterTimeoutMs > 0) {
    const g0 = Date.now();
    while (Date.now() - g0 < graceAfterTimeoutMs) {
      try {
        const r = await pollOnce();
        if (r.done) {
          logBackupAction("POLL_GRACE_OK", { opId });
          return r.result;
        }
      } catch (err) {
        logBackupAction("POLL_GRACE_ERR", { opId, message: err?.message || String(err) });
      }
      await new Promise((res) => setTimeout(res, 3000));
    }
  }
  logBackupAction("POLL_ERROR", { opId, message: "设备执行超时" });
  throw new Error("设备执行超时，请稍后刷新结果");
}

async function submitAndWaitTikTokDeviceOp(path, body, options = {}) {
  const pendingText = options.pendingText || "设备操作下发中...";
  const successText = options.successText || "设备操作完成";
  const errorText = options.errorText || "设备操作失败";
  const shouldRefresh = options.refreshAfter !== false;
  const refreshOnEnqueue = options.refreshOnEnqueue === true;
  const pollTimeoutRaw = Number(options.pollTimeoutMs);
  const pollTimeoutMs = Number.isFinite(pollTimeoutRaw) && pollTimeoutRaw > 5000 ? pollTimeoutRaw : 120000;
  const graceRaw = Number(options.graceAfterTimeoutMs);
  const graceAfterTimeoutMs = Number.isFinite(graceRaw) && graceRaw > 0 ? graceRaw : 0;
  try {
    setStatus(pendingText, "info");
    const submit = await request(path, {
      method: "POST",
      body: JSON.stringify(body),
    });
    const opId = submit.op_id;
    if (refreshOnEnqueue || submit?.switch_highlight_applied) {
      try {
        await loadBackupArchiveRecords();
      } catch (_) {
        /* ignore */
      }
    }
    if (!opId) {
      if (submit?.code === 0) {
        setStatus(successText, "success");
        return submit;
      }
      throw new Error(submit?.msg || "未拿到任务ID");
    }
    const result = await waitForDeviceOpResult(opId, pollTimeoutMs, graceAfterTimeoutMs);
    if (backupCloudInfoEl && result && !String(backupCloudInfoEl.textContent || "").trim()) {
      backupCloudInfoEl.textContent = summarizeDeviceOpResult(result);
    }
    if (result?.status === "success") {
      setStatus(successText, "success");
    } else {
      setStatus(result?.message || errorText, "error");
    }
    if (shouldRefresh) {
      loadBackupArchiveRecords();
    }
    return result;
  } catch (error) {
    setStatus(error.message || errorText, "error");
    throw error;
  }
}

async function handleBackupAndClearClick(alias, backupRecordId, username = "", deviceId = "", execOptions = {}) {
  const {
    skipConfirm = false,
    refreshAfter = true,
    pollTimeoutMs,
    graceAfterTimeoutMs,
    cutSwitchAfterBackup,
    cutSwitchTargetRowIndex,
    refreshOnEnqueue,
  } = execOptions;
  const identity = resolveLiveDeviceIdentityForArchive(alias, deviceId);
  if (!identity) return;
  const detailText = [`设备别名：${alias}`, username ? `账号：${username}` : null].filter(Boolean).join("\n");
  if (!skipConfirm && !confirmTikTokAction("备份并清理设备", detailText)) return;
  const payload = {
    ...identity,
    backup_record_id: backupRecordId ? Number(backupRecordId) : undefined,
    username: String(username || "").trim() || undefined,
  };
  const cs = String(cutSwitchAfterBackup || "").trim().toLowerCase();
  if (cs === "clear_source_for_restore" || cs === "exclusive_activate_target") {
    payload.cut_switch_after_backup = cs;
  }
  if (cutSwitchTargetRowIndex != null && Number.isFinite(Number(cutSwitchTargetRowIndex)) && Number(cutSwitchTargetRowIndex) >= 0) {
    payload.cut_switch_target_row_index = Number(cutSwitchTargetRowIndex);
  }
  const result = await submitAndWaitTikTokDeviceOp("/api/admin/device_tiktok/backup_and_clear", payload, {
    pendingText: "正在下发备份并清理任务...",
    successText: "备份并清理任务已完成",
    errorText: "备份并清理失败",
    refreshAfter,
    pollTimeoutMs,
    graceAfterTimeoutMs,
    refreshOnEnqueue,
  });
  if (result?.status !== "success") {
    throw new Error(result?.message || "备份并清理失败");
  }
  return result;
}

async function handleAliasClearClick(alias, deviceId = "", execOptions = {}) {
  const {
    skipConfirm = false,
    refreshAfter = true,
    pollTimeoutMs,
    graceAfterTimeoutMs,
    cutSwitchAfterBackup,
    cutSwitchTargetRowIndex,
    refreshOnEnqueue,
  } = execOptions;
  const identity = deviceId
    ? collectDeviceIdentityByDeviceAndAlias(deviceId, alias)
    : collectDeviceIdentityByAlias(alias);
  if (!identity) return;
  if (!skipConfirm && !confirmTikTokAction("清理 TikTok 数据", `设备别名：${alias}`)) return;
  const payload = { ...identity };
  const cs = String(cutSwitchAfterBackup || "").trim().toLowerCase();
  if (cs === "clear_source_for_restore" || cs === "exclusive_activate_target") {
    payload.cut_switch_after_backup = cs;
  }
  if (cutSwitchTargetRowIndex != null && Number.isFinite(Number(cutSwitchTargetRowIndex)) && Number(cutSwitchTargetRowIndex) >= 0) {
    payload.cut_switch_target_row_index = Number(cutSwitchTargetRowIndex);
  }
  const result = await submitAndWaitTikTokDeviceOp("/api/admin/device_tiktok/clear", payload, {
    pendingText: "正在下发清理任务...",
    successText: "TikTok 数据已清理",
    errorText: "清理 TikTok 数据失败",
    refreshAfter,
    pollTimeoutMs,
    graceAfterTimeoutMs,
    refreshOnEnqueue,
  });
  if (result?.status !== "success") {
    throw new Error(result?.message || "清理 TikTok 数据失败");
  }
  return result;
}

async function setBackupArchiveSwitchHighlight(rowIndex) {
  const ri = Number(rowIndex);
  if (!Number.isFinite(ri) || ri < 0) {
    throw new Error("无效的设备档案行号");
  }
  const resp = await request("/api/admin/editor/device_rows/set_switch_highlight", {
    method: "POST",
    body: JSON.stringify({ row_index: ri }),
  });
  if (resp?.code !== 0) {
    throw new Error(resp?.msg || "更新切号高亮失败");
  }
}

async function handleBasicProfileEditFromBackupCard(card) {
  if (!(card instanceof HTMLElement)) return;
  const details = card.closest("details");
  const bucketSize = Number(card.getAttribute("data-bucket-size") || "") || (details ? details.querySelectorAll(".backup-archive-record").length : 0);
  const switchHlDb = normalizeSwitchHighlightField(card.getAttribute("data-switch-hl"));
  const effectiveHighlight = bucketSize <= 1 || switchHlDb === "是";
  if (!effectiveHighlight) {
    setStatus("请先点击「切号」将本条设为当前高亮账号，再执行「修改账号基本资料」", "warning");
    return;
  }

  const actionAlias = String(card.getAttribute("data-action-alias") || "").trim();
  const deviceId = String(card.getAttribute("data-row-device-id") || "").trim();
  if (!guardAutoSwitchManualOps("修改账号基本资料", deviceId, actionAlias, deviceId)) return;
  const username = String(card.getAttribute("data-row-username") || "").trim();
  const identity = deviceId
    ? collectDeviceIdentityByDeviceAndAlias(deviceId, actionAlias)
    : collectDeviceIdentityByAlias(actionAlias);
  if (!identity) return;

  const detailLines = [
    `设备别名：${actionAlias}`,
    username ? `账号：${username}` : null,
    "执行期间设备会暂停涨粉并临时关闭「自动起号策略参与」，避免与修改资料流程争抢 TikTok。",
    "设备端总结果为 success 并回传云端后，系统会自动将该账号「基本资料已修改」标为「是」并关闭 TikTok；随后可回到 AutoTraces 查看。",
  ].filter(Boolean);
  if (!confirmTikTokAction("修改账号基本资料", detailLines.join("\n"))) return;

  await submitAndWaitTikTokDeviceOp("/api/admin/device_tiktok/edit_basic_profile", identity, {
    pendingText: "正在下发「修改账号基本资料」设备任务...",
    successText: "任务已下发；设备成功回传后云端会自动更新「基本资料已修改」并关闭 TikTok（见设备日志与账号记录）",
    errorText: "「修改账号基本资料」设备任务失败",
  });
}

async function handleRestoreClick(alias, backupName, deviceId = "", execOptions = {}) {
  if (!guardAutoSwitchManualOps("清理并还原", deviceId, alias, deviceId)) return;
  const {
    skipConfirm = false,
    skipPmClear = false,
    refreshAfter = true,
    pollTimeoutMs,
    graceAfterTimeoutMs,
    cutSwitchTargetRowIndex,
    refreshOnEnqueue,
  } = execOptions;
  const normalizedBackupName = String(backupName || "").trim();
  if (!normalizedBackupName) {
    setStatus("缺少备份名称，无法执行清理并还原", "warning");
    return;
  }
  const identity = resolveLiveDeviceIdentityForArchive(alias, deviceId, { requirePackageName: false });
  if (!identity) return;
  const payload = {
    device_id: identity.device_id,
    alias: identity.alias,
    backup_name: normalizedBackupName,
    skip_pm_clear: Boolean(skipPmClear),
  };
  if (cutSwitchTargetRowIndex != null && Number.isFinite(Number(cutSwitchTargetRowIndex)) && Number(cutSwitchTargetRowIndex) >= 0) {
    payload.cut_switch_target_row_index = Number(cutSwitchTargetRowIndex);
  }
  if (
    !skipConfirm
    && !confirmTikTokAction(
      "清理并还原该备份",
      skipPmClear
        ? `将直接还原以下备份（切号流程中上一步已清空 TikTok 数据）。\n设备别名：${alias}\n备份：${normalizedBackupName}`
        : `将先清空设备上的 TikTok 数据，再还原以下备份。\n设备别名：${alias}\n备份：${normalizedBackupName}`,
    )
  ) {
    return;
  }
  const result = await submitAndWaitTikTokDeviceOp("/api/admin/device_tiktok/restore", payload, {
    pendingText: "正在下发清理并还原任务...",
    successText: "清理并还原已完成",
    errorText: "清理并还原失败",
    refreshAfter,
    pollTimeoutMs,
    graceAfterTimeoutMs,
    refreshOnEnqueue,
  });
  if (result?.status !== "success") {
    throw new Error(result?.message || "清理并还原失败");
  }
  return result;
}

function getBeijingNowParts() {
  const fmt = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  const parts = fmt.formatToParts(new Date());
  const get = (type) => parts.find((p) => p.type === type)?.value || "";
  return {
    year: get("year"),
    month: get("month"),
    day: get("day"),
    hour: Number(get("hour") || 0),
    minute: Number(get("minute") || 0),
  };
}

function beijingDateStrNow() {
  const p = getBeijingNowParts();
  return `${p.year}-${p.month}-${p.day}`;
}

function beijingMinuteOfDayNow() {
  const p = getBeijingNowParts();
  return p.hour * 60 + p.minute;
}

function isAutoSwitchDailyPlanScheduleCurrent() {
  const planDate = String(autoSwitchDailyPlanDate || "").trim();
  return Boolean(planDate && planDate === beijingDateStrNow());
}

function resolveBackupArchiveAliasKey(alias, row = {}) {
  return normalizeAliasKey(
    row?.device_bucket_alias
    || (alias === "__EMPTY_ALIAS__"
      ? (row?.mobile_alias || row?.device_alias || row?.assigned_alias)
      : alias)
  );
}

/** 按云端 daily_plan + 北京时间解析当前时段应归属的 row（与设备端调度一致） */
function resolveCurrentSlotEntryForAliasKey(aliasKey) {
  const key = normalizeAliasKey(aliasKey);
  if (!key) return null;
  const bucket = autoSwitchDailyPlansByAlias[key];
  const slotsMap = bucket?.slots_by_row_index;
  if (!slotsMap || typeof slotsMap !== "object") return null;
  const minute = beijingMinuteOfDayNow();
  let fallback = null;
  let maxSlotIndex = -1;
  for (const [ri, slot] of Object.entries(slotsMap)) {
    const start = Number(slot?.start_min) || 0;
    const end = Number(slot?.end_min) || 0;
    const slotIndex = Number(slot?.slot_index) || 0;
    const entry = { ...slot, rowIndex: Number(ri) };
    if (start <= minute && minute < end) return entry;
    if (slotIndex >= maxSlotIndex) {
      maxSlotIndex = slotIndex;
      fallback = entry;
    }
  }
  return fallback;
}

function resolveBackupArchiveRowIndex(row) {
  const ri = Number(row?.row_index ?? row?.id);
  return Number.isFinite(ri) && ri >= 0 ? ri : null;
}

function isBackupArchiveRowCurrentSlotOwner(rowsInBucket, row, alias) {
  const n = Array.isArray(rowsInBucket) ? rowsInBucket.length : 0;
  if (n <= 1) return true;
  const actionAlias = alias === "__EMPTY_ALIAS__" ? resolveBackupArchiveDisplayAlias(row) : alias;
  const deviceId = String(row?.device_id || "").trim();
  if (!isAutoSwitchEnabledForArchiveAlias(actionAlias, deviceId)) return false;
  if (!isAutoSwitchDailyPlanScheduleCurrent()) return false;
  const current = resolveCurrentSlotEntryForAliasKey(resolveBackupArchiveAliasKey(alias, row));
  if (!current) return false;
  const rowRi = resolveBackupArchiveRowIndex(row);
  if (rowRi != null && Number.isFinite(Number(current.rowIndex))) {
    return rowRi === Number(current.rowIndex);
  }
  const user = String(row?.username || "").trim().toLowerCase();
  const targetUser = String(current.username || "").trim().toLowerCase();
  return Boolean(user && targetUser && user === targetUser);
}

/**
 * 同别名桶内高亮：
 * - 自动切号开启时按云端时段计划（北京时间）展示当前归属，不依赖设备回写 switch_highlight；
 * - 否则仍以 switch_highlight===「是」为准。
 */
function isBackupArchiveRowEffectivelyHighlighted(rowsInBucket, row, alias = "") {
  const n = Array.isArray(rowsInBucket) ? rowsInBucket.length : 0;
  if (n <= 1) return true;
  if (isBackupArchiveRowCurrentSlotOwner(rowsInBucket, row, alias)) return true;
  const actionAlias = alias === "__EMPTY_ALIAS__" ? resolveBackupArchiveDisplayAlias(row) : alias;
  const deviceId = String(row?.device_id || "").trim();
  if (isAutoSwitchEnabledForArchiveAlias(actionAlias, deviceId) && isAutoSwitchDailyPlanScheduleCurrent()) {
    return false;
  }
  return normalizeSwitchHighlightField(row?.switch_highlight) === "是";
}

/** 同别名桶内是否存在切号高亮（多条且全部为「否」时返回 false） */
function backupArchiveBucketHasHighlightFromCards(cards) {
  const list = Array.isArray(cards) ? cards : [];
  if (list.length <= 1) return true;
  return list.some((c) => c instanceof HTMLElement && normalizeSwitchHighlightField(c.getAttribute("data-switch-hl")) === "是");
}

async function runBackupArchiveAccountCutSwitch(clickedCard) {
  if (!(clickedCard instanceof HTMLElement)) return;
  const actionAlias = String(clickedCard.getAttribute("data-action-alias") || "").trim();
  const rowDeviceId = String(clickedCard.getAttribute("data-row-device-id") || "").trim();
  if (!guardAutoSwitchManualOps("手动切号", "", actionAlias, rowDeviceId)) return;
  const details = clickedCard.closest("details");
  const bucketSize = Number(clickedCard.getAttribute("data-bucket-size") || "") || (details ? details.querySelectorAll(".backup-archive-record").length : 0);
  const switchHlDb = normalizeSwitchHighlightField(clickedCard.getAttribute("data-switch-hl"));
  const effectiveHighlight = bucketSize <= 1 || switchHlDb === "是";
  if (effectiveHighlight) return;

  const targetRi = Number(clickedCard.getAttribute("data-backup-row-index"));
  const targetBackup = String(clickedCard.getAttribute("data-row-backup-name") || "").trim();
  const canRestoreToDevice = Boolean(targetBackup && targetBackup !== "-");
  if (!Number.isFinite(targetRi) || targetRi < 0 || !actionAlias) {
    setStatus("切号参数不完整", "warning");
    return;
  }

  const cards = details ? Array.from(details.querySelectorAll(".backup-archive-record")) : [];
  const bucketHasHighlight = backupArchiveBucketHasHighlightFromCards(cards);
  const clickedDeviceId = String(clickedCard.getAttribute("data-row-device-id") || "").trim();
  const targetUser = String(clickedCard.getAttribute("data-row-username") || "").trim();
  const cutSwitchWait = {
    pollTimeoutMs: 300000,
    graceAfterTimeoutMs: 120000,
    refreshOnEnqueue: true,
  };

  if (bucketSize > 1 && !bucketHasHighlight) {
    const opsIdentity = resolveLiveDeviceIdentityForArchive(actionAlias, clickedDeviceId);
    if (!opsIdentity) return;
    const useLoginScheme = isLoginCutSwitchScheme();
    const detailLines = useLoginScheme
      ? [
        `设备别名：${actionAlias}`,
        "确认后将立即切换环境归属高亮到目标账号。",
        "切号方案：登录切号 — 将【清理 TikTok 全部数据】后直接登录目标号（不还原备份）。",
        `目标 username：${targetUser || "-"}`,
      ]
      : canRestoreToDevice
      ? [
        `设备别名：${actionAlias}`,
        "确认后将立即切换环境归属高亮到目标账号。",
        "将【仅清理 TikTok】后还原目标账号备份（不备份）。",
        `目标 username：${targetUser || "-"}`,
      ]
      : [
        `设备别名：${actionAlias}`,
        "确认后将立即切换环境归属高亮到目标账号。",
        "将【仅清理 TikTok】（目标无备份元数据，不还原）。",
        `目标 username：${targetUser || "-"}`,
      ];
    if (!confirmTikTokAction("切号", detailLines.join("\n"))) return;
    try {
      await handleAliasClearClick(actionAlias, opsIdentity.device_id, {
        skipConfirm: true,
        refreshAfter: false,
        ...cutSwitchWait,
        cutSwitchAfterBackup: useLoginScheme || !canRestoreToDevice
          ? "exclusive_activate_target"
          : "clear_source_for_restore",
        cutSwitchTargetRowIndex: targetRi,
      });
      if (!useLoginScheme && canRestoreToDevice) {
        await handleRestoreClick(actionAlias, targetBackup, opsIdentity.device_id, {
          skipConfirm: true,
          skipPmClear: true,
          refreshAfter: false,
          cutSwitchTargetRowIndex: targetRi,
          ...cutSwitchWait,
          refreshOnEnqueue: false,
        });
      }
      setStatus(
        useLoginScheme
          ? "切号：环境归属已切换；设备将清理后自动登录目标号"
          : canRestoreToDevice
          ? "切号：环境归属已切换；设备任务已完成（含还原，登录状态见环境标签）"
          : "切号：环境归属已切换；设备清理任务已完成",
        "success",
      );
    } catch (e) {
      const msg = e?.message || "切号失败";
      const timedOut = /超时/.test(msg);
      setStatus(
        timedOut
          ? `${msg} 环境归属已在入队时切换；设备任务可能仍在执行，请查看设备日志后刷新。`
          : `${msg}（环境归属已在入队时切换，失败不回滚）`,
        timedOut ? "warning" : "error",
      );
    } finally {
      try {
        await refreshAccountEditorLight({ refreshArchive: true });
      } catch (_) {
        /* ignore */
      }
    }
    return;
  }

  const sourceCard = bucketSize > 1 ? cards.find((c) => normalizeSwitchHighlightField(c.getAttribute("data-switch-hl")) === "是") : null;
  if (bucketSize > 1 && (!sourceCard || sourceCard === clickedCard)) {
    setStatus("同别名高亮数据异常（服务端应保证有且仅有一条为「是」），请刷新备份档案后重试", "warning");
    return;
  }

  const sourceDeviceId = sourceCard instanceof HTMLElement
    ? String(sourceCard.getAttribute("data-row-device-id") || "").trim()
    : "";
  const opsIdentity = resolveLiveDeviceIdentityForArchive(actionAlias, sourceDeviceId || clickedDeviceId);
  if (!opsIdentity) return;

  const detailLines = isLoginCutSwitchScheme()
    ? [
      `设备别名：${actionAlias}`,
      "确认后将立即切换环境归属高亮到目标账号。",
      "切号方案：登录切号 — 将【清理 TikTok 全部数据】后直接登录目标号（不备份、不还原）。",
      `目标 username：${targetUser || "-"}`,
    ]
    : canRestoreToDevice
    ? [
      `设备别名：${actionAlias}`,
      "确认后将立即切换环境归属高亮到目标账号。",
      "将对当前「切号高亮」账号执行【备份并清理】，再对目标账号执行【还原备份】。",
      `目标 username：${targetUser || "-"}`,
    ]
    : [
      `设备别名：${actionAlias}`,
      "确认后将立即切换环境归属高亮到目标账号。",
      "将对当前「切号高亮」账号执行【备份并清理】；目标无备份元数据，不做还原。",
      `目标 username：${targetUser || "-"}`,
    ];
  if (!confirmTikTokAction("切号", detailLines.join("\n"))) return;

  try {
    const sRi = Number(sourceCard.getAttribute("data-backup-row-index"));
    const sUser = String(sourceCard.getAttribute("data-row-username") || "").trim();
    const sid = resolveLiveDeviceIdentityForArchive(actionAlias, sourceDeviceId || clickedDeviceId);
    if (!sid) return;
    const useLoginScheme = isLoginCutSwitchScheme();
    if (useLoginScheme) {
      await handleAliasClearClick(actionAlias, sid.device_id, {
        skipConfirm: true,
        refreshAfter: false,
        ...cutSwitchWait,
        cutSwitchAfterBackup: "exclusive_activate_target",
        cutSwitchTargetRowIndex: targetRi,
      });
      setStatus("切号：环境归属已切换；设备将清理后自动登录目标号", "success");
    } else {
      const backupRecordId = Number.isFinite(sRi) && sRi >= 0 ? sRi : undefined;
      await handleBackupAndClearClick(actionAlias, backupRecordId, sUser, sid.device_id, {
        skipConfirm: true,
        refreshAfter: false,
        ...cutSwitchWait,
        cutSwitchAfterBackup: canRestoreToDevice ? "clear_source_for_restore" : "exclusive_activate_target",
        cutSwitchTargetRowIndex: targetRi,
      });
      if (canRestoreToDevice) {
        await handleRestoreClick(actionAlias, targetBackup, sid.device_id, {
          skipConfirm: true,
          skipPmClear: true,
          refreshAfter: false,
          cutSwitchTargetRowIndex: targetRi,
          ...cutSwitchWait,
          refreshOnEnqueue: false,
        });
      }
      setStatus(
        canRestoreToDevice
          ? "切号：环境归属已切换；设备任务已完成（含还原，登录状态见环境标签）"
          : "切号：环境归属已切换；设备备份并清理任务已完成",
        "success",
      );
    }
  } catch (e) {
    const msg = e?.message || "切号失败";
    const timedOut = /超时/.test(msg);
    setStatus(
      timedOut
        ? `${msg} 环境归属已在入队时切换；设备任务可能仍在执行，请查看设备日志后刷新。`
        : `${msg}（环境归属已在入队时切换，失败不回滚）`,
      timedOut ? "warning" : "error",
    );
  } finally {
    try {
      await refreshAccountEditorLight({ refreshArchive: true });
    } catch (_) {
      /* ignore */
    }
  }
}

function updateGalleryLibraryCount() {
  const count = galleryLibraryItems.length;
  if (galleryLibraryCountEl) {
    galleryLibraryCountEl.textContent = `${count} / ${GALLERY_LIBRARY_MAX_ITEMS}`;
  }
  if (galleryLibrarySelectedCountEl) {
    galleryLibrarySelectedCountEl.textContent = String(galleryLibrarySelectedIds.size);
  }
}

function revokeGalleryLibraryThumbUrls() {
  galleryLibraryThumbUrls.forEach((url) => {
    try {
      URL.revokeObjectURL(url);
    } catch (_) {
      /* ignore */
    }
  });
  galleryLibraryThumbUrls.clear();
}

function toggleGalleryLibrarySelection(itemId, selected) {
  const id = String(itemId || "").trim();
  if (!id) return;
  if (selected) galleryLibrarySelectedIds.add(id);
  else galleryLibrarySelectedIds.delete(id);
  const card = galleryLibraryGridEl?.querySelector(`[data-gallery-id="${id}"]`);
  card?.classList.toggle("selected", selected);
  const check = card?.querySelector(".gallery-library-item__check");
  if (check) check.textContent = selected ? "✓" : "";
  updateGalleryLibraryCount();
}

async function loadGalleryLibraryThumb(itemId) {
  const id = String(itemId || "").trim();
  if (!id || galleryLibraryThumbUrls.has(id)) return galleryLibraryThumbUrls.get(id);
  const headers = {};
  const token = authToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(
    await buildApiUrl(`/api/admin/gallery/library/${encodeURIComponent(id)}/file`),
    { headers },
  );
  if (!response.ok) return "";
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  galleryLibraryThumbUrls.set(id, url);
  return url;
}

async function renderGalleryLibraryGrid() {
  if (!galleryLibraryGridEl) return;
  revokeGalleryLibraryThumbUrls();
  if (!galleryLibraryItems.length) {
    galleryLibraryGridEl.innerHTML = '<div class="gallery-library-empty">暂无图片，点击「上传到图库」添加素材</div>';
    updateGalleryLibraryCount();
    return;
  }
  galleryLibraryGridEl.innerHTML = galleryLibraryItems
    .map((item) => {
      const id = String(item?.id || "").trim();
      const filename = String(item?.filename || id || "image");
      const safeName = escapeHtml(filename);
      const selected = galleryLibrarySelectedIds.has(id);
      return `
        <div class="gallery-library-item${selected ? " selected" : ""}" data-gallery-id="${id}" title="${safeName}">
          <div class="gallery-library-item__check">${selected ? "✓" : ""}</div>
          <button type="button" class="gallery-library-item__delete" data-gallery-delete="${id}" title="删除">×</button>
          <img alt="${safeName}" data-gallery-thumb="${id}" />
          <div class="gallery-library-item__name">${safeName}</div>
        </div>
      `;
    })
    .join("");
  galleryLibraryGridEl.querySelectorAll(".gallery-library-item").forEach((card) => {
    const id = String(card.getAttribute("data-gallery-id") || "").trim();
    card.addEventListener("click", (event) => {
      if (event.target?.closest?.("[data-gallery-delete]")) return;
      toggleGalleryLibrarySelection(id, !galleryLibrarySelectedIds.has(id));
    });
  });
  galleryLibraryGridEl.querySelectorAll("[data-gallery-delete]").forEach((btn) => {
    btn.addEventListener("click", (event) => {
      event.stopPropagation();
      const id = String(btn.getAttribute("data-gallery-delete") || "").trim();
      if (!id) return;
      deleteGalleryLibraryItem(id).catch((error) => {
        setStatus(error?.message || "删除图库图片失败", "error");
      });
    });
  });
  await Promise.all(
    galleryLibraryItems.map(async (item) => {
      const id = String(item?.id || "").trim();
      if (!id) return;
      const url = await loadGalleryLibraryThumb(id);
      const img = galleryLibraryGridEl.querySelector(`img[data-gallery-thumb="${id}"]`);
      if (img && url) img.src = url;
    }),
  );
  updateGalleryLibraryCount();
}

async function loadGalleryLibrary() {
  const data = await request("/api/admin/gallery/library");
  galleryLibraryItems = Array.isArray(data?.items) ? data.items : [];
  const validIds = new Set(galleryLibraryItems.map((item) => String(item?.id || "").trim()).filter(Boolean));
  Array.from(galleryLibrarySelectedIds).forEach((id) => {
    if (!validIds.has(id)) galleryLibrarySelectedIds.delete(id);
  });
  await renderGalleryLibraryGrid();
}

function chunkFilesForGalleryUpload(files, maxBatchBytes) {
  const limit = Math.max(1, Number(maxBatchBytes) || GALLERY_UPLOAD_BATCH_MAX_BYTES);
  const chunks = [];
  let current = [];
  let currentBytes = 0;
  for (const file of files) {
    const size = Math.max(0, Number(file?.size) || 0);
    if (current.length > 0 && currentBytes + size > limit) {
      chunks.push(current);
      current = [];
      currentBytes = 0;
    }
    current.push(file);
    currentBytes += size;
  }
  if (current.length) chunks.push(current);
  return chunks;
}

function isUploadPayloadTooLargeError(error) {
  const msg = String(error?.message || "");
  return /413|上传文件过大|client_max_body|Request Entity Too Large|HTML 页面而非 JSON/i.test(msg);
}

async function uploadGalleryLibraryBatches(files) {
  const batches = chunkFilesForGalleryUpload(files, GALLERY_UPLOAD_BATCH_MAX_BYTES);
  let uploaded = 0;
  let lastMsg = "";
  for (let i = 0; i < batches.length; i += 1) {
    let batch = batches[i];
    while (true) {
      const formData = new FormData();
      batch.forEach((file) => formData.append("files", file));
      const batchLabel = batches.length > 1 ? `（第 ${i + 1}/${batches.length} 批，${batch.length} 张）` : "";
      setStatus(`正在上传 ${files.length} 张图片到图库...${batchLabel}`, "info");
      try {
        const data = await fetchFormDataApi("/api/admin/gallery/library", formData);
        uploaded += batch.length;
        lastMsg = String(data?.msg || "").trim();
        break;
      } catch (error) {
        if (isUploadPayloadTooLargeError(error) && batch.length > 1) {
          const mid = Math.ceil(batch.length / 2);
          batches.splice(i, 1, batch.slice(0, mid), batch.slice(mid));
          batch = batches[i];
          continue;
        }
        throw error;
      }
    }
  }
  return { uploaded, msg: lastMsg };
}

async function uploadGalleryLibraryFromInput() {
  const files = Array.from(galleryLibraryUploadInput?.files || []);
  if (!files.length) {
    setStatus("请先选择要上传到图库的图片", "warning");
    return;
  }
  const remaining = GALLERY_LIBRARY_MAX_ITEMS - galleryLibraryItems.length;
  if (remaining <= 0) {
    setStatus(`图库已满（最多 ${GALLERY_LIBRARY_MAX_ITEMS} 张）`, "warning");
    return;
  }
  if (files.length > remaining) {
    setStatus(`图库剩余容量 ${remaining} 张，请减少本次选择数量`, "warning");
    return;
  }
  const { uploaded, msg } = await uploadGalleryLibraryBatches(files);
  if (galleryLibraryUploadInput) galleryLibraryUploadInput.value = "";
  await loadGalleryLibrary();
  setStatus(msg || `已上传 ${uploaded} 张图片到图库`, "success");
}

async function deleteGalleryLibraryItem(itemId) {
  const id = String(itemId || "").trim();
  if (!id) return;
  if (!window.confirm("确定从图库删除这张图片？")) return;
  await request(`/api/admin/gallery/library/${encodeURIComponent(id)}`, { method: "DELETE" });
  galleryLibrarySelectedIds.delete(id);
  await loadGalleryLibrary();
  setStatus("已从图库删除", "success");
}

function formatFailureReportBytes(size) {
  const n = Number(size) || 0;
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

function fillFailureReportsSelect(selectEl, values, placeholder) {
  if (!selectEl) return;
  const current = String(selectEl.value || "");
  selectEl.innerHTML = "";
  const allOpt = document.createElement("option");
  allOpt.value = "";
  allOpt.textContent = placeholder;
  selectEl.appendChild(allOpt);
  (values || []).forEach((value) => {
    const v = String(value || "").trim();
    if (!v) return;
    const opt = document.createElement("option");
    opt.value = v;
    opt.textContent = v;
    selectEl.appendChild(opt);
  });
  if (current && Array.from(selectEl.options).some((o) => o.value === current)) {
    selectEl.value = current;
  }
}

function renderFailureReportsTable(items) {
  if (!failureReportsTableBodyEl) return;
  const rows = Array.isArray(items) ? items : [];
  if (!rows.length) {
    failureReportsTableBodyEl.innerHTML =
      '<tr><td colspan="9" class="failure-reports-empty">暂无失败采集数据</td></tr>';
    return;
  }
  failureReportsTableBodyEl.innerHTML = rows
    .map((item) => {
      const id = String(item?.id || "").trim();
      const owner = escapeHtml(String(item?.owner_username || "-"));
      const alias = escapeHtml(String(item?.alias || "-"));
      const business = escapeHtml(String(item?.business || "-"));
      const errorType = escapeHtml(String(item?.error_type || "-"));
      const summary = escapeHtml(String(item?.summary || "").slice(0, 240));
      const ocr = item?.ocr_related ? "是" : "否";
      const createdAt = escapeHtml(String(item?.created_at || "-"));
      const size = formatFailureReportBytes(item?.size);
      return `<tr data-report-id="${escapeHtml(id)}">
        <td>${owner}</td>
        <td>${alias}</td>
        <td>${business}</td>
        <td><code>${errorType}</code></td>
        <td class="failure-reports-summary" title="${summary}">${summary || "-"}</td>
        <td>${ocr}</td>
        <td>${createdAt}</td>
        <td>${size}</td>
        <td class="failure-reports-actions">
          <button type="button" class="ghost failure-report-download-btn" data-id="${escapeHtml(id)}">下载</button>
          <button type="button" class="danger failure-report-delete-btn" data-id="${escapeHtml(id)}">删除</button>
        </td>
      </tr>`;
    })
    .join("");
}

async function loadFailureReports() {
  if (currentUser?.role !== "admin") return;
  const owner = String(failureReportsOwnerFilterEl?.value || "").trim();
  const alias = String(failureReportsAliasFilterEl?.value || "").trim();
  const errorType = String(failureReportsErrorFilterEl?.value || "").trim();
  const qs = new URLSearchParams();
  if (owner) qs.set("owner_username", owner);
  if (alias) qs.set("alias", alias);
  if (errorType) qs.set("error_type", errorType);
  const suffix = qs.toString() ? `?${qs.toString()}` : "";
  const data = await request(`/api/admin/failure_reports${suffix}`);
  const totalCount = Number(data.total_count ?? data.count ?? 0);
  const maxItems = Number(data.max_items ?? 88);
  if (failureReportsCountEl) {
    failureReportsCountEl.textContent = `${totalCount} / ${maxItems}`;
  }
  fillFailureReportsSelect(failureReportsOwnerFilterEl, data.owners || [], "全部用户");
  fillFailureReportsSelect(failureReportsAliasFilterEl, data.aliases || [], "全部别名");
  fillFailureReportsSelect(failureReportsErrorFilterEl, data.error_types || [], "全部类型");
  if (owner) failureReportsOwnerFilterEl.value = owner;
  if (alias) failureReportsAliasFilterEl.value = alias;
  if (errorType) failureReportsErrorFilterEl.value = errorType;
  renderFailureReportsTable(data.items || []);
}

async function downloadFailureReport(reportId) {
  const id = String(reportId || "").trim();
  if (!id) return;
  const url = await buildApiUrl(`/api/admin/failure_reports/${encodeURIComponent(id)}/download`);
  const headers = {};
  const token = authToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(url, { headers });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.slice(0, 200) || `下载失败 HTTP ${response.status}`);
  }
  const blob = await response.blob();
  const cd = response.headers.get("content-disposition") || "";
  const match = /filename="?([^";]+)"?/i.exec(cd);
  const filename = match?.[1] || `failure_report_${id}.zip`;
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = objectUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(objectUrl);
  setStatus("失败采集包已开始下载", "success");
}

async function deleteFailureReport(reportId) {
  const id = String(reportId || "").trim();
  if (!id) return;
  if (!window.confirm("确定删除这条失败采集记录？压缩包将一并移除。")) return;
  await request(`/api/admin/failure_reports/${encodeURIComponent(id)}`, { method: "DELETE" });
  await loadFailureReports();
  setStatus("已删除失败采集记录", "success");
}

async function resolveGalleryDispatchFiles() {
  const selectedIds = galleryLibraryItems
    .map((item) => String(item?.id || "").trim())
    .filter((id) => id && galleryLibrarySelectedIds.has(id));
  const localFiles = Array.from(galleryImageFilesInput?.files || []);
  if (selectedIds.length) {
    const headers = {};
    const token = authToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    const files = [];
    for (const id of selectedIds) {
      const meta = galleryLibraryItems.find((item) => String(item?.id || "").trim() === id);
      const response = await fetch(
        await buildApiUrl(`/api/admin/gallery/library/${encodeURIComponent(id)}/file`),
        { headers },
      );
      if (!response.ok) {
        throw new Error(`读取图库图片失败：${meta?.filename || id}`);
      }
      const blob = await response.blob();
      const filename = String(meta?.filename || `${id}.jpg`);
      const type = String(meta?.content_type || blob.type || "image/jpeg");
      files.push(new File([blob], filename, { type }));
    }
    return files;
  }
  return localFiles;
}

async function uploadGalleryImagesToDevice() {
  const aliases = Array.from(galleryTargetAliasesSelect?.selectedOptions || [])
    .map((option) => String(option.value || "").trim())
    .filter(Boolean);
  if (!aliases.length) {
    setStatus("请先选择至少一个目标设备别名", "warning");
    return;
  }
  const files = await resolveGalleryDispatchFiles();
  if (!files.length) {
    setStatus("请先在图库勾选图片，或从本机选择图片文件", "warning");
    return;
  }
  if (files.length > GALLERY_LIBRARY_MAX_ITEMS) {
    setStatus(`单次最多选择 ${GALLERY_LIBRARY_MAX_ITEMS} 个素材`, "warning");
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
      let data;
      try {
        data = await fetchFormDataApi("/api/admin/device_gallery/upload", formData, { headers });
      } catch (error) {
        const message = error?.message || "下发图库任务失败";
        summaries.push({ alias, status: "failed", message });
        appendGalleryDispatchLog(`  失败：${message}`);
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





function syncDbSearchFieldOptions(fields = []) {
  if (!dbSearchFieldSelect) return;
  const currentValue = String(dbSearchFieldSelect.value || "").trim();
  const fieldList = Array.isArray(fields) ? fields.map((field) => String(field || "").trim()).filter(Boolean) : [];
  const options = ['<option value="">全部字段</option>']
    .concat(fieldList.map((field) => `<option value="${field}">${field}</option>`))
    .join("");
  dbSearchFieldSelect.innerHTML = options;
  const nextValue = currentValue && fieldList.includes(currentValue) ? currentValue : "";
  dbSearchFieldSelect.value = nextValue;
  if (nextValue !== currentDbSearchField) {
    currentDbSearchField = nextValue;
    saveUiStateSnapshot();
  }
}

function getActiveDbTableValue(preferred = "") {
  const forced = String(preferred || "").trim();
  if (forced) return forced;
  if (dbTableSelect && typeof dbTableSelect.value === "string" && dbTableSelect.value.trim()) {
    return dbTableSelect.value.trim();
  }
  const fallback = document.querySelector("#dbTableSelect");
  const value = String(fallback?.value || "").trim();
  return value || "tiktok_roots";
}

async function loadDbTablePage(preferredTable = "") {
  const startedAt = Date.now();
  const safePreferredTable = typeof preferredTable === "string" ? preferredTable : "";
  const table = getActiveDbTableValue(safePreferredTable);
  const searchField = currentDbSearchField || "";
  const searchKeyword = currentDbSearchKeyword || "";
  if (dbTableBodyEl) dbTableBodyEl.innerHTML = `<tr><td colspan="${Math.max((dbTableHeadEl?.querySelectorAll("th")?.length || 1), 1)}" style="padding:10px;color:#666;">加载中...</td></tr>`;
  try {
    const params = new URLSearchParams({
      table,
      page: String(currentDbPage),
      page_size: String(DB_PAGE_SIZE),
    });
    if (searchField) params.set("search_field", searchField);
    if (searchKeyword) params.set("search_keyword", searchKeyword);
    const data = await request(`/api/admin/table_page?${params.toString()}`);
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
    const allowedSearchFields = Array.isArray(data.fields) ? data.fields : columns;
    const currentSearchFieldValid = !currentDbSearchField || allowedSearchFields.includes(currentDbSearchField);
    if (!currentSearchFieldValid) {
      currentDbSearchField = "";
      if (dbSearchFieldSelect) dbSearchFieldSelect.value = "";
      saveUiStateSnapshot();
    }

    syncDbSearchFieldOptions(data.fields || columns);

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
    if (dbClearSearchBtn) dbClearSearchBtn.disabled = !currentDbSearchField && !currentDbSearchKeyword;
    saveUiStateSnapshot();
    void startedAt;
  } catch (error) {
    if (dbTableHeadEl) dbTableHeadEl.innerHTML = "";
    if (dbTableBodyEl) {
      dbTableBodyEl.innerHTML = `<tr><td style="padding:10px;color:#c00;">加载失败: ${error.message || "unknown"}</td></tr>`;
    }
    const elapsedMs = Date.now() - startedAt;
    if (elapsedMs > 3000) {
      setStatus(`数据库分页加载失败：${error.message || "unknown"} | ${elapsedMs}ms`, "error");
    }
  }
}

async function authCheck() {
  const token = authToken();
  const cachedUser = getCachedUser();
  if (!token && !cachedUser) return false;
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
    if (isAuthExpiredError(error)) {
      setAuthToken("");
      setCachedUser(null);
      currentUser = null;
      authShell?.classList.remove("hidden");
      appShell?.classList.add("hidden");
      if (authMessageEl) authMessageEl.textContent = "登录已失效，请重新登录";
      return false;
    }
    if (cachedUser) {
      currentUser = cachedUser;
      configTargetUsersLoaded = false;
      authShell?.classList.add("hidden");
      appShell?.classList.remove("hidden");
      applyRolePermissions();
      if (authMessageEl) authMessageEl.textContent = "服务连接异常，已使用本地登录态";
      return true;
    }
    if (authMessageEl) authMessageEl.textContent = "服务连接失败，请检查 API Base 后重试";
    return false;
  }
}

function restoreInputValuesAfterRefresh() {
  if (dbSearchFieldSelect) dbSearchFieldSelect.value = currentDbSearchField;
  if (dbSearchKeywordInput) dbSearchKeywordInput.value = currentDbSearchKeyword;
  if (!currentDbSearchField && dbSearchFieldSelect) dbSearchFieldSelect.value = "";
  if (cvPageSizeEl) cvPageSizeEl.value = String(cvPageSize());
  if (tiktokRootsPageSizeEl && !String(tiktokRootsPageSizeEl.value || "").trim()) {
    tiktokRootsPageSizeEl.value = "100";
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
  const startedAt = Date.now();
  if (adminUsersTableBodyEl) adminUsersTableBodyEl.innerHTML = '<tr><td colspan="6" style="padding:10px;color:#666;">加载中...</td></tr>';
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
  void startedAt;
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
    await loadBoundDeviceAliases().catch(() => {});
    await loadEditorState().catch(() => {});
    renderAccountEditorRows();
    renderDeviceAliasPresetOptions();
  });
}
if (refreshOverviewBtn) {
  refreshOverviewBtn.addEventListener("click", async () => {
    await handleRefresh();
  });
}
{
  let commentTextsDraftTimer = null;
  const commentTextAreas = [
    configForm?.querySelector("[name='comment_texts']"),
    configForm?.querySelector("[name='mutual_comment_texts']"),
    configForm?.querySelector("[name='mutual_traffic_target_texts']"),
    configForm?.querySelector("[name='profile_edit_bio_texts']"),
  ].filter(Boolean);
  commentTextAreas.forEach((ta) => {
    ta.addEventListener("input", () => {
      clearTimeout(commentTextsDraftTimer);
      commentTextsDraftTimer = setTimeout(persistCommentTextsToLocal, 500);
    });
  });
}
if (uploadBtn) uploadBtn.addEventListener("click", uploadUsernames);
if (saveConfigBtn) saveConfigBtn.addEventListener("click", saveConfig);
if (saveSmartTemplateConfigBtn) {
  saveSmartTemplateConfigBtn.addEventListener("click", () => saveSmartTemplateConfig().catch(() => {}));
}
if (reloadSmartTemplateConfigBtn) {
  reloadSmartTemplateConfigBtn.addEventListener("click", () => loadUserRuntimeConfig().catch(() => {}));
}
function appendSmartTemplateWeightRow(branch) {
  const rows = collectSmartTemplateWeightRows(branch);
  const names = Object.keys(currentTemplatesByName || {});
  const defaultName = names.includes("default") ? "default" : (names[0] || "default");
  rows.push({ template_name: defaultName, weight: 100 });
  renderSmartTemplateWeightRows(branch, rows);
}
if (smartAddNewBranchRowBtn) {
  smartAddNewBranchRowBtn.addEventListener("click", () => appendSmartTemplateWeightRow("new"));
}
if (smartAddOldBranchRowBtn) {
  smartAddOldBranchRowBtn.addEventListener("click", () => appendSmartTemplateWeightRow("old"));
}
function handleSmartTemplateWeightRowClick(event) {
  const btn = event.target?.closest?.(".smart-template-remove-row");
  if (!btn) return;
  const branch = String(btn.dataset.branch || "new");
  const idx = Number(btn.dataset.rowIndex);
  const rows = collectSmartTemplateWeightRows(branch);
  if (Number.isFinite(idx)) rows.splice(idx, 1);
  if (!rows.length) {
    const names = Object.keys(currentTemplatesByName || {});
    rows.push({ template_name: names[0] || "default", weight: 100 });
  }
  renderSmartTemplateWeightRows(branch, rows);
}
if (smartNewBranchRowsEl) smartNewBranchRowsEl.addEventListener("click", handleSmartTemplateWeightRowClick);
if (smartOldBranchRowsEl) smartOldBranchRowsEl.addEventListener("click", handleSmartTemplateWeightRowClick);
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
      setStatus("正在更新自动起号策略...", "info");
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
          : `已关闭自动起号（已暂停 ${data.paused_count ?? 0} 台，清空待启动 ${data.cleared_pending_count ?? 0} 台）`,
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
function onDeviceGroupFilterChanged(event) {
  const target = event?.target;
  let value;
  if (target === deviceGroupSelect || target === pcServiceGroupSelect) {
    value = String(target.value ?? "").trim();
  } else {
    value = getActiveDeviceGroupFilter();
  }
  syncDeviceGroupFilterSelects(value);
  currentOverviewPage = 1;
  currentPcServicePage = 1;
  void loadOverview().catch(() => {});
  if (document.getElementById("pc-service")?.classList.contains("active")) {
    void loadPcServiceDevices().catch(() => {});
  }
}
if (deviceGroupSelect) {
  const storedFilter = String(localStorage.getItem(DEVICE_GROUP_FILTER_KEY) || "").trim();
  if (storedFilter) syncDeviceGroupFilterSelects(storedFilter);
  deviceGroupSelect.addEventListener("change", onDeviceGroupFilterChanged);
}
if (pcServiceGroupSelect) {
  pcServiceGroupSelect.addEventListener("change", onDeviceGroupFilterChanged);
}
if (pcServiceApplyBtn) {
  pcServiceApplyBtn.addEventListener("click", () => {
    applyPcServiceUrlToSelectedDevices().catch((error) => {
      setStatus(humanizeRequestErrorMessage(error) || "下发失败", "error");
    });
  });
}
if (pcServiceRefreshBtn) {
  pcServiceRefreshBtn.addEventListener("click", () => {
    loadPcServiceDevices().catch((error) => {
      setStatus(humanizeRequestErrorMessage(error) || "刷新失败", "error");
    });
  });
}
if (pcServiceSelectPageBtn) {
  pcServiceSelectPageBtn.addEventListener("click", () => {
    pcServiceTableBody?.querySelectorAll(".pc-service-check").forEach((cb) => {
      cb.checked = true;
    });
    updatePcServiceSelectionHint();
  });
}
if (pcServiceClearSelectionBtn) {
  pcServiceClearSelectionBtn.addEventListener("click", () => {
    pcServiceTableBody?.querySelectorAll(".pc-service-check").forEach((cb) => {
      cb.checked = false;
    });
    updatePcServiceSelectionHint();
  });
}
if (pcServiceAliasFilter) {
  pcServiceAliasFilter.addEventListener("input", () => {
    pcServiceAliasFilterText = String(pcServiceAliasFilter.value || "");
    renderPcServiceTable(pcServiceDevices);
  });
}
if (pcServicePrevPageBtn) {
  pcServicePrevPageBtn.addEventListener("click", () => {
    if (currentPcServicePage <= 1) return;
    currentPcServicePage -= 1;
    void loadPcServiceDevices().catch(() => {});
  });
}
if (pcServiceNextPageBtn) {
  pcServiceNextPageBtn.addEventListener("click", () => {
    const totalPages = Math.max(1, Math.ceil(pcServiceDevicesTotal / getPcServicePageSize()));
    if (currentPcServicePage >= totalPages) return;
    currentPcServicePage += 1;
    void loadPcServiceDevices().catch(() => {});
  });
}
if (pcServicePageSizeInput) {
  pcServicePageSizeInput.addEventListener("change", onPcServicePageSizeChanged);
  pcServicePageSizeInput.addEventListener("blur", onPcServicePageSizeChanged);
}
if (autoSwitchSessionSummariesAliasSelect) {
  const storedAlias = String(localStorage.getItem(AUTO_SWITCH_SESSION_SUMMARIES_ALIAS_KEY) || "").trim();
  if (storedAlias) autoSwitchSessionSummariesAliasSelect.value = storedAlias;
  autoSwitchSessionSummariesAliasSelect.addEventListener("change", () => {
    try {
      localStorage.setItem(
        AUTO_SWITCH_SESSION_SUMMARIES_ALIAS_KEY,
        String(autoSwitchSessionSummariesAliasSelect.value || "").trim(),
      );
    } catch (_) {}
    void loadAutoSwitchSessionSummaries();
  });
}
if (autoSwitchSessionSummariesRefreshBtn) {
  autoSwitchSessionSummariesRefreshBtn.addEventListener("click", () => {
    void loadAutoSwitchSessionSummaries();
  });
}
if (exportBackupArchiveXlsxBtn) {
  exportBackupArchiveXlsxBtn.addEventListener("click", () => {
    exportBackupArchiveXlsx({ selectedOnly: false }).catch((e) => setStatus(e?.message || "导出失败", "error"));
  });
}
if (exportBackupArchiveSelectedXlsxBtn) {
  exportBackupArchiveSelectedXlsxBtn.addEventListener("click", () => {
    exportBackupArchiveXlsx({ selectedOnly: true }).catch((e) => setStatus(e?.message || "导出失败", "error"));
  });
}
if (backupArchiveSelectAllPageBtn) {
  backupArchiveSelectAllPageBtn.addEventListener("click", () => {
    selectAllBackupArchiveRowsOnPage();
    setStatus("已勾选当前页全部记录", "info");
  });
}
if (backupArchiveClearSelectionBtn) {
  backupArchiveClearSelectionBtn.addEventListener("click", () => {
    clearBackupArchiveSelection();
    setStatus("已取消全部勾选", "info");
  });
}
updateBackupArchiveSelectionUi();
if (importBackupArchiveXlsxBtn && backupArchiveImportFileInput) {
  importBackupArchiveXlsxBtn.addEventListener("click", () => backupArchiveImportFileInput.click());
  backupArchiveImportFileInput.addEventListener("change", () => {
    const file = backupArchiveImportFileInput.files?.[0];
    if (!file) return;
    importBackupArchiveXlsxFile(file).catch((e) => setStatus(e?.message || "导入失败", "error"));
  });
}
if (manageDeviceGroupsBtn) {
  manageDeviceGroupsBtn.addEventListener("click", () => {
    void openGroupMoveModal();
  });
}
if (closeGroupMoveModalBtn) closeGroupMoveModalBtn.addEventListener("click", closeGroupMoveModal);
if (confirmGroupMoveBtn) confirmGroupMoveBtn.addEventListener("click", () => { void confirmGroupMove(); });
if (addGroupBtn) addGroupBtn.addEventListener("click", () => { void addDeviceAliasGroup(); });
if (deleteGroupBtn) deleteGroupBtn.addEventListener("click", () => { void deleteCurrentDeviceAliasGroup(); });
if (groupMoveModalEl) {
  groupMoveModalEl.addEventListener("click", (event) => {
    if (event.target === groupMoveModalEl) closeGroupMoveModal();
  });
}
if (overviewPrevPageBtn) {
  overviewPrevPageBtn.addEventListener("click", () => {
    if (currentOverviewPage <= 1) return;
    currentOverviewPage -= 1;
    void loadOverview();
  });
}
if (overviewNextPageBtn) {
  overviewNextPageBtn.addEventListener("click", () => {
    const totalPages = Math.max(1, Math.ceil(overviewDevicesTotal / getOverviewPageSize()));
    if (currentOverviewPage >= totalPages) return;
    currentOverviewPage += 1;
    void loadOverview();
  });
}
if (overviewPageSizeInput) {
  overviewPageSizeInput.addEventListener("change", onOverviewPageSizeChanged);
  overviewPageSizeInput.addEventListener("blur", onOverviewPageSizeChanged);
}
if (backupArchivePrevPageBtn) {
  backupArchivePrevPageBtn.addEventListener("click", () => {
    if (currentBackupArchivePage <= 1) return;
    currentBackupArchivePage -= 1;
    void loadBackupArchiveRecords();
  });
}
if (backupArchiveNextPageBtn) {
  backupArchiveNextPageBtn.addEventListener("click", () => {
    const totalPages = Math.max(1, Math.ceil(backupArchiveAliasTotal / getBackupArchivePageSize()));
    if (currentBackupArchivePage >= totalPages) return;
    currentBackupArchivePage += 1;
    void loadBackupArchiveRecords();
  });
}
if (backupArchivePageSizeInput) {
  backupArchivePageSizeInput.addEventListener("change", onBackupArchivePageSizeChanged);
  backupArchivePageSizeInput.addEventListener("blur", onBackupArchivePageSizeChanged);
}
if (addTikTokRootsBtn) {
  addTikTokRootsBtn.addEventListener("click", addTikTokRoots);
}
if (importTikTokRootsBtn) {
  importTikTokRootsBtn.addEventListener("click", importTikTokRoots);
}
if (exportMobilePendingUsernamesBtn) {
  exportMobilePendingUsernamesBtn.addEventListener("click", () => exportMobilePendingUsernames(false));
}
if (exportAndClearMobilePendingUsernamesBtn) {
  exportAndClearMobilePendingUsernamesBtn.addEventListener("click", () => exportMobilePendingUsernames(true));
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
      saveUiStateSnapshot();
      await loadAccounts();
    }
  });
}
if (tiktokRootsNextPageBtn) {
  tiktokRootsNextPageBtn.addEventListener("click", async () => {
    currentTiktokRootsPage += 1;
    saveUiStateSnapshot();
    await loadAccounts();
  });
}
if (tiktokRootsPageSizeEl) {
  tiktokRootsPageSizeEl.addEventListener("change", async () => {
    currentTiktokRootsPage = 1;
    saveUiStateSnapshot();
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
function formatPreflightStats(stats) {
  if (!stats || typeof stats !== "object") return "";
  return [
    `预检扫描=${stats.scanned ?? 0}`,
    `标记关注列表隐藏=${stats.marked_following_private ?? 0}`,
    `标记私密=${stats.marked_private_account ?? 0}`,
    `标记不可用=${stats.marked_not_found ?? 0}`,
    `仍可处理=${stats.still_processable ?? 0}`,
  ].join("，");
}

if (preflightFollowerQueueBtn) {
  preflightFollowerQueueBtn.addEventListener("click", async () => {
    try {
      setStatus("正在预检关注列表可见性...", "info");
      const raw = Number(processQueueBatchSizeInput?.value || 100);
      const limit = Math.max(1, Number.isFinite(raw) ? Math.floor(raw) : 100);
      const data = await request(
        `/api/admin/follower_queue/preflight_following_visibility?limit=${limit}`,
        { method: "POST" }
      );
      const summary = formatPreflightStats(data.stats);
      setStatus(summary ? `预检完成：${summary}` : "预检完成", "success");
      await loadCvAccessData();
      currentCvPage = 1;
      applyCvAccessView();
    } catch (error) {
      setStatus(error.message || "预检失败", "error");
    }
  });
}
if (triggerProcessQueueInlineBtn) {
  triggerProcessQueueInlineBtn.addEventListener("click", async () => {
    try {
      setStatus("任务触发中（含预检）...", "info");
      const raw = Number(processQueueBatchSizeInput?.value || 100);
      const limit = Math.max(1, Number.isFinite(raw) ? Math.floor(raw) : 100);
      const container_ids = (cvTaskContainerIdsInput?.value || "").trim();
      const parallelRaw = Number(cvTaskParallelInput?.value || 1);
      const parallel = Math.max(1, Number.isFinite(parallelRaw) ? parallelRaw : 1);
      const query = `?limit=${limit}&container_ids=${encodeURIComponent(container_ids)}&parallel=${parallel}&preflight=true`;
      const data = await request(`/api/admin/tasks/trigger_process_queue${query}`, { method: "POST" });
      const pre = formatPreflightStats(data.preflight);
      if (data.workers) {
        setStatus(
          `已触发：follower_queue处理（workers=${data.workers}）${pre ? `；${pre}` : ""}`,
          "success"
        );
      } else {
        setStatus(
          `已触发：follower_queue处理（pid=${data.pid ?? "-"}）${pre ? `；${pre}` : ""}`,
          "success"
        );
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
    saveUiStateSnapshot();
    Promise.resolve()
      .then(loadCvAccessData)
      .catch(() => applyCvAccessView());
  });
}
if (cvNextPageBtn) {
  cvNextPageBtn.addEventListener("click", () => {
    currentCvPage += 1;
    saveUiStateSnapshot();
    Promise.resolve()
      .then(loadCvAccessData)
      .catch(() => applyCvAccessView());
  });
}
if (cvPageSizeEl) {
  cvPageSizeEl.addEventListener("change", () => {
    currentCvPage = 1;
    saveUiStateSnapshot();
    Promise.resolve()
      .then(loadCvAccessData)
      .catch(() => applyCvAccessView());
  });
}
if (saveCvQualityFilterBtn) {
  saveCvQualityFilterBtn.addEventListener("click", saveCvQualityFilter);
}
if (triggerAutoSearchBtn) {
  triggerAutoSearchBtn.addEventListener("click", () => triggerTask("/api/admin/tasks/trigger_autosearch_once", "已触发：粉丝增长监控执行一次"));
}
if (schedulerLogsScrollBottomBtn) {
  schedulerLogsScrollBottomBtn.addEventListener("click", () => {
    if (schedulerLogsFollowToggle) schedulerLogsFollowToggle.checked = true;
    scrollLogContainerToBottom(schedulerTaskLogsEl, true);
    scrollLogContainerToBottom(taskStatusEl, true);
  });
}
if (schedulerTaskLogsEl) {
  schedulerTaskLogsEl.addEventListener("scroll", () => {
    if (!isLogFollowEnabled()) return;
    if (!isLogContainerAtBottom(schedulerTaskLogsEl) && schedulerLogsFollowToggle) {
      schedulerLogsFollowToggle.checked = false;
    }
  });
}
if (saveSchedulerConfigBtn) {
  saveSchedulerConfigBtn.addEventListener("click", saveSchedulerConfig);
}
if (backupStatsFullQueryEnabledToggle) {
  backupStatsFullQueryEnabledToggle.addEventListener("change", () =>
    saveBackupStatsFullQueryConfig({ refreshStatus: false }),
  );
}
if (autoSwitchSelectAllEnableBtn) {
  autoSwitchSelectAllEnableBtn.addEventListener("click", () => {
    enableAutoSwitchForAllDevices().catch((e) => setStatus(e.message || "全选并开启失败", "error"));
  });
}
if (autoSwitchEnableSelectedBtn) {
  autoSwitchEnableSelectedBtn.addEventListener("click", () => {
    saveAutoSwitchConfig({ enabled: true }).catch((e) =>
      setStatus(e.message || "开启失败", "error"),
    );
  });
}
if (autoSwitchDisableSelectedBtn) {
  autoSwitchDisableSelectedBtn.addEventListener("click", () => {
    saveAutoSwitchConfig({ enabled: false }).catch((e) =>
      setStatus(e.message || "关闭失败", "error"),
    );
  });
}
if (autoSwitchCutSchemeSelect) {
  autoSwitchCutSchemeSelect.addEventListener("change", () => {
    saveAutoSwitchCutScheme(autoSwitchCutSchemeSelect.value).catch((e) => {
      setStatus(e.message || "保存切号方案失败", "error");
    });
  });
}
if (autoSwitchExcludedBatchBtn) {
  autoSwitchExcludedBatchBtn.addEventListener("click", () => openAutoSwitchExcludedBatchModal());
}
if (closeAutoSwitchExcludedBatchBtn) {
  closeAutoSwitchExcludedBatchBtn.addEventListener("click", () => closeAutoSwitchExcludedBatchModal());
}
if (cancelAutoSwitchExcludedBatchBtn) {
  cancelAutoSwitchExcludedBatchBtn.addEventListener("click", () => closeAutoSwitchExcludedBatchModal());
}
if (autoSwitchExcludedBatchExcludeBtn) {
  autoSwitchExcludedBatchExcludeBtn.addEventListener("click", () => {
    submitAutoSwitchExcludedBatch(true).catch((e) => {
      setAutoSwitchExcludedBatchMessage(e.message || "批量设置失败", "error");
      setStatus(e.message || "批量设置失败", "error");
    });
  });
}
if (autoSwitchExcludedBatchJoinBtn) {
  autoSwitchExcludedBatchJoinBtn.addEventListener("click", () => {
    submitAutoSwitchExcludedBatch(false).catch((e) => {
      setAutoSwitchExcludedBatchMessage(e.message || "批量退出失败", "error");
      setStatus(e.message || "批量退出失败", "error");
    });
  });
}
if (autoSwitchExcludedBatchModalEl) {
  autoSwitchExcludedBatchModalEl.addEventListener("click", (event) => {
    if (event.target === autoSwitchExcludedBatchModalEl) closeAutoSwitchExcludedBatchModal();
  });
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
setupMutualTrafficActionMutexListeners();
setupNoRestToggleListeners();
if (mutualStartSelectedBtn) {
  mutualStartSelectedBtn.addEventListener("click", () => {
    void startMutualClickForSelectedDevices();
  });
}
if (mutualFollowOnlySelectedBtn) {
  mutualFollowOnlySelectedBtn.addEventListener("click", () => {
    void startMutualFollowOnlyForSelectedDevices();
  });
}
if (publishPostStartSelectedBtn) {
  publishPostStartSelectedBtn.addEventListener("click", () => {
    void startPublishPostForSelectedDevices();
  });
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
try {
  const refreshTemplateBtnEl = document.getElementById("refreshTemplateInventoryBtn");
  const templateInventoryAllUsersToggleEl = document.getElementById("templateInventoryAllUsers");
  const selectAllOwnTemplatesEl = document.getElementById("selectAllOwnTemplatesBtn");
  const clearTemplateSelectionEl = document.getElementById("clearTemplateSelectionBtn");
  const deleteSelectedTemplatesEl = document.getElementById("deleteSelectedTemplatesBtn");
  const grantConfigTemplateEl = document.getElementById("grantConfigTemplateBtn");
  const revokeConfigTemplateEl = document.getElementById("revokeConfigTemplateBtn");

  if (refreshTemplateBtnEl) {
    refreshTemplateBtnEl.addEventListener("click", () => {
      void refreshTemplateInventoryFromServer().then(() => {
        setStatus("模板清单已从服务端刷新（含未过滤原始键）", "success");
      });
    });
  }
  if (templateInventoryAllUsersToggleEl) {
    templateInventoryAllUsersToggleEl.addEventListener("change", () => {
      void refreshTemplateInventoryFromServer();
    });
  }
  if (selectAllOwnTemplatesEl) {
    selectAllOwnTemplatesEl.addEventListener("click", () => {
      if (!templateInventoryListEl) return;
      templateInventoryListEl.querySelectorAll(".template-inventory-check:not([disabled])").forEach((el) => {
        el.checked = true;
      });
    });
  }
  if (clearTemplateSelectionEl) {
    clearTemplateSelectionEl.addEventListener("click", () => {
      if (!templateInventoryListEl) return;
      templateInventoryListEl.querySelectorAll(".template-inventory-check").forEach((el) => {
        el.checked = false;
      });
    });
  }
  if (deleteSelectedTemplatesEl) {
    deleteSelectedTemplatesEl.addEventListener("click", () => {
      void deleteSelectedTemplates();
    });
  }
  if (grantConfigTemplateEl) {
    grantConfigTemplateEl.addEventListener("click", () => {
      void setTemplateGrant(true);
    });
  }
  if (revokeConfigTemplateEl) {
    revokeConfigTemplateEl.addEventListener("click", () => {
      void setTemplateGrant(false);
    });
  }
} catch (error) {
  console.warn("template controls binding skipped:", error);
}
if (importLiveCustomersBtn) {
  importLiveCustomersBtn.addEventListener("click", importLiveCustomers);
}
if (fetchTiktokStatsBtn) {
  fetchTiktokStatsBtn.addEventListener("click", () => fetchTiktokStats());
}
if (fetchTiktokStatsXlsxBtn) {
  fetchTiktokStatsXlsxBtn.addEventListener("click", () => pickTiktokStatsXlsxFile());
}
if (statsXlsxFileInput) {
  statsXlsxFileInput.addEventListener("change", () => {
    const file = statsXlsxFileInput.files && statsXlsxFileInput.files[0];
    if (file) {
      runTiktokStatsXlsxBatch(file).catch((error) => {
        setStatus(error.message || "批量查询失败", "error");
      });
    }
  });
}
if (downloadTiktokStatsXlsxBtn) {
  downloadTiktokStatsXlsxBtn.addEventListener("click", () => {
    downloadTiktokStatsXlsx().catch((error) => {
      setStatus(error.message || "下载 Excel 失败", "error");
    });
  });
}
if (fetchTiktokStatsFullBtn) {
  fetchTiktokStatsFullBtn.addEventListener("click", () => {
    if (!backupStatsFullQueryManualAvailable) {
      setStatus("全量查询并落库暂时不可用", "warning");
      return;
    }
    fetchTiktokStatsFullForBackupArchive().catch((error) => {
      setStatus(error.message || "全量查询失败", "error");
    });
  });
  applyBackupStatsFullQueryManualUi({ manual_available: false });
}
async function handleDbTableSwitch(nextTable = "") {
  currentDbPage = 1;
  currentDbSearchField = "";
  currentDbSearchKeyword = "";
  if (dbSearchFieldSelect) dbSearchFieldSelect.value = "";
  if (dbSearchKeywordInput) dbSearchKeywordInput.value = "";
  saveUiStateSnapshot();
  await loadDbTablePage(nextTable);
}

if (dbTableSelect) {
  dbTableSelect.addEventListener("change", async (event) => {
    const nextTable = String(event?.target?.value || "").trim();
    await handleDbTableSwitch(nextTable);
  });
}

// 兜底：某些浏览器/脚本热更新场景下，局部节点可能被替换导致原监听丢失。
document.addEventListener("change", (event) => {
  const target = event?.target;
  if (!target || target.id !== "dbTableSelect") return;
  const nextTable = String(target.value || "").trim();
  void handleDbTableSwitch(nextTable);
});
if (dbSearchBtn) {
  dbSearchBtn.addEventListener("click", async () => {
    currentDbPage = 1;
    currentDbSearchField = dbSearchFieldSelect?.value || "";
    currentDbSearchKeyword = dbSearchKeywordInput?.value?.trim() || "";
    saveUiStateSnapshot();
    await loadDbTablePage();
  });
}
if (dbClearSearchBtn) {
  dbClearSearchBtn.addEventListener("click", async () => {
    currentDbPage = 1;
    currentDbSearchField = "";
    currentDbSearchKeyword = "";
    if (dbSearchFieldSelect) dbSearchFieldSelect.value = "";
    if (dbSearchKeywordInput) dbSearchKeywordInput.value = "";
    saveUiStateSnapshot();
    await loadDbTablePage();
  });
}
if (dbPrevPageBtn) {
  dbPrevPageBtn.addEventListener("click", async () => {
    if (currentDbPage > 1) {
      currentDbPage -= 1;
      saveUiStateSnapshot();
      await loadDbTablePage();
    }
  });
}
if (dbNextPageBtn) {
  dbNextPageBtn.addEventListener("click", async () => {
    currentDbPage += 1;
    saveUiStateSnapshot();
    await loadDbTablePage();
  });
}
if (dbRefreshPageBtn) {
  dbRefreshPageBtn.addEventListener("click", () => {
    void loadDbTablePage();
  });
}
if (dbSearchKeywordInput) {
  dbSearchKeywordInput.addEventListener("keydown", async (event) => {
    if (event.key !== "Enter") return;
    currentDbPage = 1;
    currentDbSearchField = dbSearchFieldSelect?.value || "";
    currentDbSearchKeyword = dbSearchKeywordInput?.value?.trim() || "";
    await loadDbTablePage();
  });
}
if (uploadGalleryBtn) {
  uploadGalleryBtn.addEventListener("click", () => {
    uploadGalleryImagesToDevice().catch((error) => {
      setStatus(error?.message || "上传并下发图库失败", "error");
    });
  });
}
if (galleryLibraryUploadBtn && galleryLibraryUploadInput) {
  galleryLibraryUploadBtn.addEventListener("click", () => {
    galleryLibraryUploadInput.click();
  });
  galleryLibraryUploadInput.addEventListener("change", () => {
    uploadGalleryLibraryFromInput().catch((error) => {
      setStatus(error?.message || "上传到图库失败", "error");
    });
  });
}
if (galleryLibraryRefreshBtn) {
  galleryLibraryRefreshBtn.addEventListener("click", () => {
    loadGalleryLibrary().catch((error) => {
      setStatus(error?.message || "刷新图库失败", "error");
    });
  });
}
if (failureReportsRefreshBtn) {
  failureReportsRefreshBtn.addEventListener("click", () => {
    loadFailureReports().catch((error) => {
      setStatus(error?.message || "刷新失败采集失败", "error");
    });
  });
}
[failureReportsOwnerFilterEl, failureReportsAliasFilterEl, failureReportsErrorFilterEl].forEach((el) => {
  if (!el) return;
  el.addEventListener("change", () => {
    loadFailureReports().catch((error) => {
      setStatus(error?.message || "筛选失败采集失败", "error");
    });
  });
});
if (failureReportsTableBodyEl) {
  failureReportsTableBodyEl.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    const id = String(target.dataset.id || "").trim();
    if (!id) return;
    if (target.classList.contains("failure-report-download-btn")) {
      downloadFailureReport(id).catch((error) => {
        setStatus(error?.message || "下载失败", "error");
      });
      return;
    }
    if (target.classList.contains("failure-report-delete-btn")) {
      deleteFailureReport(id).catch((error) => {
        setStatus(error?.message || "删除失败", "error");
      });
    }
  });
}
if (galleryLibrarySelectAllBtn) {
  galleryLibrarySelectAllBtn.addEventListener("click", () => {
    galleryLibraryItems.forEach((item) => {
      const id = String(item?.id || "").trim();
      if (id) galleryLibrarySelectedIds.add(id);
    });
    renderGalleryLibraryGrid().catch(() => {});
  });
}
if (galleryLibraryClearSelectionBtn) {
  galleryLibraryClearSelectionBtn.addEventListener("click", () => {
    galleryLibrarySelectedIds.clear();
    renderGalleryLibraryGrid().catch(() => {});
  });
}
if (typeof backupPackageNameInput !== "undefined" && backupPackageNameInput) {
  backupPackageNameInput.value = (backupPackageNameInput.value || "").trim() || "com.zhiliaoapp.musically";
}

async function loadBoundDeviceAliases() {
  const data = await request("/api/admin/devices/aliases");
  const pairs = [];
  const seen = new Set();
  const rawPairs = Array.isArray(data?.pairs) ? data.pairs : [];
  if (rawPairs.length) {
    rawPairs.forEach((item) => {
      const device_alias = normalizeAliasValue(item?.alias);
      const device_id = String(item?.device_id || "").trim();
      if (!device_alias || !device_id || seen.has(device_alias)) return;
      seen.add(device_alias);
      pairs.push({ device_alias, device_id });
    });
  } else {
    (Array.isArray(data?.items) ? data.items : []).forEach((alias) => {
      const device_alias = normalizeAliasValue(alias);
      if (!device_alias || seen.has(device_alias)) return;
      seen.add(device_alias);
      pairs.push({ device_alias, device_id: "" });
    });
  }
  pairs.sort((a, b) => a.device_alias.localeCompare(b.device_alias));
  boundDeviceAliasPairs = pairs;
  boundDeviceAliasesSignature = pairs.map((item) => `${item.device_alias}\0${item.device_id}`).join("\n");
  populateAutoSwitchSessionSummariesAliasOptions();
  return pairs;
}

function isAccountManagerActive() {
  return document.querySelector(".panel.active")?.id === "account-manager";
}

/** 在多个字段中做包含匹配（忽略大小写），任一字段命中即返回 true */
function matchesSearchInFields(needle, fields = []) {
  const n = String(needle || "").trim().toLowerCase();
  if (!n) return true;
  for (const field of fields) {
    if (String(field ?? "").toLowerCase().includes(n)) return true;
  }
  return false;
}

const ACCOUNT_RECORDS_SEARCH_FIELD_LABELS = {
  alias: "别名",
  username: "username",
  region: "地区分类",
};

function formatAccountRecordsSearchHint(query, field = "") {
  const q = String(query || "").trim();
  if (!q) return "";
  const fieldLabel = ACCOUNT_RECORDS_SEARCH_FIELD_LABELS[String(field || "").trim()] || "全部字段";
  return `未找到在「${fieldLabel}」中匹配「${q}」的账号记录`;
}

function accountRecordMatchesSearch(row = {}) {
  const field = String(accountRecordsSearchField || "").trim();
  if (field === "alias") {
    return matchesSearchInFields(accountRecordsSearchQuery, [getEffectiveAccountAssignedAlias(row)]);
  }
  if (field === "username") {
    return matchesSearchInFields(accountRecordsSearchQuery, [row.username]);
  }
  if (field === "region") {
    return matchesSearchInFields(accountRecordsSearchQuery, [row.region]);
  }
  return matchesSearchInFields(accountRecordsSearchQuery, [
    row.username,
    row.password,
    row.email,
    row.email_password,
    row.two_fa,
    row.api,
    row.region,
    getEffectiveAccountAssignedAlias(row),
    row.login_time,
    row.video_count,
    row.follower_count,
    row.follower_growth,
  ]);
}

function backupArchiveRecordMatchesSearch(row = {}) {
  if (!backupArchiveSearchQuery) return true;
  const username = String(row?.username || "").trim();
  const acct = username ? getAccountRecordByUsername(username) : null;
  return matchesSearchInFields(backupArchiveSearchQuery, [
    username,
    row.device_alias,
    row.assigned_alias,
    row.mobile_alias,
    row.account_assigned_alias,
    acct?.assigned_alias,
    row.region,
    acct?.region,
  ]);
}

function resolveBackupArchiveDisplayAlias(item = {}) {
  return normalizeAliasValue(
    item?.assigned_alias
    || item?.account_assigned_alias
    || item?.mobile_alias
    || item?.device_alias
  );
}

function collectAssignableAliases(currentValue = "") {
  const current = normalizeAliasValue(currentValue);
  const aliasCandidates = new Set();
  (backupArchiveRows || []).forEach((row) => {
    const alias = normalizeAliasValue(row?.assigned_alias || row?.mobile_alias || row?.device_alias);
    if (alias) aliasCandidates.add(alias);
  });
  (deviceEditorRows || []).forEach((row) => {
    const alias = normalizeAliasValue(row?.device_alias);
    if (alias) aliasCandidates.add(alias);
  });
  buildAliasDevicePairs().forEach((item) => {
    const alias = normalizeAliasValue(item?.device_alias);
    if (alias) aliasCandidates.add(alias);
  });
  const aliases = Array.from(aliasCandidates).sort((a, b) => a.localeCompare(b));
  if (current && !aliases.includes(current)) return [current, ...aliases];
  return aliases;
}

function refreshAssignableAliasDropdowns() {
  if (!accountEditorListEl) return;
  accountEditorListEl.querySelectorAll('select[data-editor-field="assigned_alias"]').forEach((selectEl) => {
    const index = Number(selectEl.dataset.editorIndex);
    const row = accountEditorRows[index];
    if (!row || isAccountAliasLocked(row)) return;
    const current = normalizeAliasValue(selectEl.value || row.assigned_alias);
    selectEl.innerHTML = getAssignableAliasOptions(current);
    if (current) selectEl.value = current;
  });
}

async function syncAssignableAliasOptionsIfNeeded() {
  if (boundDeviceAliasesSyncInFlight || !authToken()) return;
  boundDeviceAliasesSyncInFlight = true;
  try {
    const previousSignature = boundDeviceAliasesSignature;
    await loadBoundDeviceAliases();
    if (boundDeviceAliasesSignature === previousSignature) return;
    renderDeviceAliasPresetOptions();
    if (isAccountManagerActive()) {
      refreshAssignableAliasDropdowns();
    }
  } catch (_) {
    /* 静默：轮询或概览刷新失败时不打断当前编辑 */
  } finally {
    boundDeviceAliasesSyncInFlight = false;
  }
}

async function loadAccountEditorRows() {
  const [accountsResp, supportResp] = await Promise.all([
    request(ACCOUNT_ROWS_API),
    request("/api/admin/editor/account_rows/support").catch(() => ({ items: [] })),
  ]);
  accountEditorRows = Array.isArray(accountsResp.items)
    ? accountsResp.items.map((item) => getAccountRowTemplate(item))
    : [];
  const supportItems = Array.isArray(supportResp?.items) ? supportResp.items : [];
  if (supportItems.length) {
    const aliasByUser = new Map();
    const supportByUser = new Map();
    supportItems.forEach((item) => {
      const key = String(item?.username || "").trim().toLowerCase();
      if (!key) return;
      const alias = normalizeAliasValue(item?.assigned_alias || item?.device_alias);
      if (alias && !aliasByUser.has(key)) aliasByUser.set(key, alias);
      const existing = supportByUser.get(key);
      const updatedAt = String(item?.updated_at || "");
      if (!existing || updatedAt > String(existing?.updated_at || "")) {
        supportByUser.set(key, item);
      }
    });
    backupAliasByUsername = aliasByUser;
    (accountEditorRows || []).forEach((row) => {
      const key = String(row?.username || "").trim().toLowerCase();
      if (!key) return;
      if (!normalizeAliasValue(row?.assigned_alias) && aliasByUser.has(key)) {
        row.assigned_alias = aliasByUser.get(key) || "";
      }
      const support = supportByUser.get(key);
      if (!support) return;
      for (const field of ["video_count", "follower_count", "follower_growth"]) {
        if (String(row?.[field] ?? "").trim()) continue;
        const nextValue = String(support?.[field] ?? "").trim();
        if (nextValue) row[field] = nextValue;
      }
    });
  } else {
    rebuildBackupAliasByUsername();
    syncAccountAliasFromDeviceRows();
    enrichAccountStatsFromDeviceRows();
  }
}

async function refreshAccountEditorLight({ refreshArchive = false } = {}) {
  await loadAccountEditorRows();
  renderAccountEditorRows();
  renderDeviceRegionPresetOptions();
  void loadAccountStatsQueryConfig().catch(() => {});
  if (refreshArchive && document.getElementById("backup-section")?.classList.contains("active")) {
    await loadBackupArchiveRecords();
  }
}

async function loadEditorState() {
  const [accountsResp, devicesResp] = await Promise.all([
    request(ACCOUNT_ROWS_API),
    request("/api/admin/device_editor_rows/export_backup"),
    loadBoundDeviceAliases().catch(() => []),
  ]);
  accountEditorRows = Array.isArray(accountsResp.items)
    ? accountsResp.items.map((item) => getAccountRowTemplate(item))
    : [];
  deviceEditorRows = Array.isArray(devicesResp.items)
    ? devicesResp.items.map((item) => getDeviceRowTemplate(item))
    : [];
  backupArchiveRows = [];
  rebuildBackupAliasByUsername();
  syncAccountAliasFromDeviceRows();
  enrichAccountStatsFromDeviceRows();
}

function rebuildBackupAliasByUsername() {
  backupAliasByUsername = new Map();
  const ingest = (row) => {
    const username = String(row?.username || "").trim().toLowerCase();
    const alias = normalizeAliasValue(row?.assigned_alias || row?.mobile_alias || row?.device_alias);
    if (!username || !alias) return;
    if (!backupAliasByUsername.has(username)) backupAliasByUsername.set(username, alias);
  };
  (backupArchiveRows || []).forEach(ingest);
  (deviceEditorRows || []).forEach(ingest);
}

/** 账号表 assigned_alias 为空时，从设备档案反查别名用于展示与保存 */
function syncAccountAliasFromDeviceRows() {
  (accountEditorRows || []).forEach((row) => {
    if (normalizeAliasValue(row?.assigned_alias)) return;
    const username = String(row?.username || "").trim().toLowerCase();
    if (!username || !backupAliasByUsername.has(username)) return;
    row.assigned_alias = backupAliasByUsername.get(username) || "";
  });
}

/** 历史数据：备份档案已有统计而账号表为空时，加载后用于展示（不写库，等下次回写会持久化） */
function enrichAccountStatsFromDeviceRows() {
  const deviceByUser = new Map();
  (deviceEditorRows || []).forEach((dev) => {
    const key = String(dev?.username || "").trim().toLowerCase();
    if (!key) return;
    const existing = deviceByUser.get(key);
    const devUpdated = String(dev?.updated_at || "");
    if (!existing || devUpdated > String(existing?.updated_at || "")) {
      deviceByUser.set(key, dev);
    }
  });
  (accountEditorRows || []).forEach((row) => {
    const key = String(row?.username || "").trim().toLowerCase();
    if (!key) return;
    const dev = deviceByUser.get(key);
    if (!dev) return;
    for (const field of ["video_count", "follower_count", "follower_growth"]) {
      if (String(row[field] ?? "").trim()) continue;
      const fromDev = String(dev[field] ?? "").trim();
      if (fromDev) row[field] = fromDev;
    }
  });
}

function buildBackupGroups(rows = []) {
  const grouped = new Map();
  rows.forEach((row) => {
    const alias = normalizeAliasValue(row?.assigned_alias || row?.mobile_alias || row?.device_alias);
    const key = alias || "__UNASSIGNED__";
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push(row);
  });
  return Array.from(grouped.entries()).sort((a, b) => {
    const aKey = a[0] === "__UNASSIGNED__" ? "zzz" : a[0].toLowerCase();
    const bKey = b[0] === "__UNASSIGNED__" ? "zzz" : b[0].toLowerCase();
    return aKey.localeCompare(bKey);
  });
}

function renderBackupArchiveSummaryTable(rows = []) {
  if (!deviceAliasGroupsEl) return;
  if (!rows.length) {
    deviceAliasGroupsEl.innerHTML = '<div class="hint">暂无备份档案</div>';
    return;
  }
  const groups = buildBackupGroups(rows);
  deviceAliasGroupsEl.innerHTML = groups.map(([aliasKey, items], groupIdx) => {
    const aliasLabel = aliasKey === "__UNASSIGNED__" ? "未分配别名" : aliasKey;
    const count = items.length;
    const rowsHtml = items.map((row) => {
      const backupName = String(row.backup_name || "").trim() || "-";
      const updatedAt = String(row.updated_at || "").trim() || "-";
      const username = String(row.username || "").trim() || "-";
      return `<tr><td style="padding:8px;">${username}</td><td style="padding:8px;">${row.mobile_alias || "-"}</td><td style="padding:8px;">${backupName}</td><td style="padding:8px;">${updatedAt}</td></tr>`;
    }).join("");
    return `
      <details class="panel-card" style="margin-bottom:12px;background:#f8faff;" ${groupIdx < 2 ? "open" : ""}>
        <summary style="cursor:pointer;display:flex;justify-content:space-between;gap:10px;align-items:center;">
          <strong>${aliasLabel}</strong>
          <span class="hint">${count} 条</span>
        </summary>
        <div style="overflow:auto;margin-top:10px;border:1px solid #e5e7eb;border-radius:8px;background:#fff;">
          <table style="width:100%;border-collapse:collapse;font-size:12px;table-layout:fixed;">
            <thead><tr><th style="padding:8px;text-align:left;">username</th><th style="padding:8px;text-align:left;">mobile_alias</th><th style="padding:8px;text-align:left;">backup_name</th><th style="padding:8px;text-align:left;">updated_at</th></tr></thead>
            <tbody>${rowsHtml || '<tr><td colspan="4" style="padding:10px;color:#666;">暂无数据</td></tr>'}</tbody>
          </table>
        </div>
      </details>
    `;
  }).join("");
}

async function syncDeviceBackupAliasForAccount(username, assignedAlias) {
  const normalizedUsername = String(username || "").trim().toLowerCase();
  if (!normalizedUsername) return;
  const normalizedAlias = normalizeAliasValue(assignedAlias);
  const targets = deviceEditorRows
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => String(row?.username || "").trim().toLowerCase() === normalizedUsername);
  if (!targets.length) return;
  let changed = false;
  for (const { row, index } of targets) {
    if (normalizeAliasValue(row.device_alias) === normalizedAlias) continue;
    row.device_alias = normalizedAlias;
    await saveDeviceRow(index, { skipUsernameDuplicateCheck: true, skipReload: true });
    changed = true;
  }
  if (changed) {
    await loadEditorState();
  }
}

async function ensureBackupArchiveEntryForAccount(row, assignedAlias) {
  const normalizedAlias = normalizeAliasValue(assignedAlias);
  const username = String(row?.username || "").trim().toLowerCase();
  if (!normalizedAlias || !username) return;
  // upsert 已在服务端同步 device_editor_rows；仅更新本地反查缓存，避免 loadBackupArchiveRecords 全量刷新。
  backupAliasByUsername.set(username, normalizedAlias);
}

async function saveAccountRow(index, options = {}) {
  const row = accountEditorRows[index];
  if (!row) return;
  const { skipReload = false, skipBackupEnsure = false, fullReload = false } = options || {};
  const effectiveAlias = getEffectiveAccountAssignedAlias(row) || normalizeAliasValue(row?.assigned_alias);
  if (effectiveAlias) row.assigned_alias = effectiveAlias;
  const resp = await request(`${ACCOUNT_ROWS_API}/upsert`, {
    method: "POST",
    body: JSON.stringify({
      row_index: resolveAccountRowIndex(row, index),
      ...row,
      assigned_alias: effectiveAlias,
    }),
  });
  if (resp?.row_index != null && Number.isFinite(Number(resp.row_index))) {
    row.row_index = Number(resp.row_index);
  }
  const persistedAlias = effectiveAlias || normalizeAliasValue(row.assigned_alias);
  if (!skipBackupEnsure && persistedAlias) {
    await ensureBackupArchiveEntryForAccount(row, persistedAlias);
  }
  if (skipReload) return;
  if (fullReload) {
    await loadEditorState();
    renderAccountEditorRows();
    renderDeviceEditorRows();
  } else {
    renderAccountEditorRows();
  }
}

function hasMeaningfulAccountValue(value) {
  const text = String(value || "").trim();
  if (!text) return false;
  const normalized = text.toLowerCase();
  return !["ignore", "ignored", "null", "none", "n/a", "na", "-", "--", "_"].includes(normalized);
}

const ACCOUNT_IMPORT_MERGE_FIELDS = ["password", "email", "email_password", "two_fa", "api", "region"];

const ACCOUNT_BATCH_NOISE_VALUES = new Set([
  "未售出",
  "已售出",
  "已卖出",
  "卡号",
  "卡密",
  "状态",
  "status",
  "sold",
  "unsold",
]);

function normalizeAccountTwoFaSecret(value) {
  return String(value || "").trim().replace(/\s+/g, "").toUpperCase();
}

/** TOTP/2FA 密钥：Base32（A-Z2-7），常见 16/26/32/64 位；勿回调 looksLikeAccountApiValue（会循环） */
function looksLikeTwoFaSecret(value) {
  const text = normalizeAccountTwoFaSecret(value);
  if (!hasMeaningfulAccountValue(text)) return false;
  if (text.includes("@")) return false;
  // 排除 UUID / URL / 含 0/1/8/9 的普通口令
  if (/[0189_\-.:/\\]/.test(text)) return false;
  return /^[A-Z2-7]{16,64}$/.test(text);
}

function isAccountBatchNoiseValue(value) {
  const text = String(value || "").trim();
  if (!text) return true;
  if (ACCOUNT_BATCH_NOISE_VALUES.has(text)) return true;
  if (ACCOUNT_BATCH_NOISE_VALUES.has(text.toLowerCase())) return true;
  return false;
}

function isAccountBatchHeaderLine(cols) {
  if (!cols || !cols.length) return true;
  const first = String(cols[0] || "").trim().toLowerCase();
  if (first === "username" || first === "user" || first === "卡号") return true;
  const joined = cols.map((c) => String(c || "").trim()).join(" ");
  return /卡号|卡密|状态/.test(joined) && cols.length <= 4 && !looksLikeTwoFaSecret(cols[2] || "");
}

/**
 * 展开厂家「凭证----串 + TAB 状态」与标准 TAB/---- 列。
 * 例：username----password----2FA\t\t未售出 → [username, password, 2FA]
 */
function splitAccountBatchColumns(rawLine) {
  const raw = String(rawLine || "").trim();
  if (!raw) return [];
  const primaryParts = raw.includes("\t")
    ? raw.split(/\t+/)
    : (raw.includes("----") ? raw.split(/-{4,}/) : [raw]);
  const cols = [];
  primaryParts.forEach((part) => {
    const text = String(part || "").trim();
    if (!text) return;
    if (text.includes("----")) {
      text.split(/-{4,}/).forEach((piece) => {
        const item = String(piece || "").trim();
        if (item && !isAccountBatchNoiseValue(item)) cols.push(item);
      });
      return;
    }
    if (!isAccountBatchNoiseValue(text)) cols.push(text);
  });
  return cols;
}

function mergeAccountImportRow(existing, incoming) {
  const preservedAlias = getEffectiveAccountAssignedAlias(existing)
    || normalizeAliasValue(existing?.assigned_alias);
  const merged = { ...existing };
  for (const field of ACCOUNT_IMPORT_MERGE_FIELDS) {
    const incomingValue = String(incoming?.[field] || "").trim();
    if (hasMeaningfulAccountValue(incomingValue)) {
      merged[field] = incomingValue;
    }
  }
  const incomingAlias = normalizeAliasValue(incoming?.assigned_alias);
  merged.assigned_alias = preservedAlias || incomingAlias;
  return merged;
}

function looksLikeAccountApiValue(value) {
  const text = String(value || "").trim();
  if (!hasMeaningfulAccountValue(text)) return false;
  if (looksLikeTwoFaSecret(text)) return false;
  return /^https?:\/\//i.test(text) || /\/api\b/i.test(text) || /pickup/i.test(text) || /^[\w.-]+:\d+\//.test(text);
}

function resolveAccountBatchTwoFa(normalizedCols) {
  if (!normalizedCols.length) return "";
  // 仅 2FA：username, password, two_fa
  if (normalizedCols.length === 3 && looksLikeTwoFaSecret(normalizedCols[2])) {
    return normalizeAccountTwoFaSecret(normalizedCols[2]);
  }
  if (normalizedCols.length === 4) {
    // username, password, two_fa, api
    if (looksLikeTwoFaSecret(normalizedCols[2])) {
      return normalizeAccountTwoFaSecret(normalizedCols[2]);
    }
    // username, password, email, two_fa（无邮箱密码）
    if (String(normalizedCols[2] || "").includes("@") && looksLikeTwoFaSecret(normalizedCols[3])) {
      return normalizeAccountTwoFaSecret(normalizedCols[3]);
    }
  }
  // 标准邮箱格式：从 email_password 之后的尾列识别 2FA
  if (normalizedCols.length >= 5) {
    for (let i = 4; i < normalizedCols.length; i += 1) {
      if (looksLikeTwoFaSecret(normalizedCols[i])) {
        return normalizeAccountTwoFaSecret(normalizedCols[i]);
      }
    }
  }
  return "";
}

function resolveAccountBatchApi(normalizedCols) {
  if (normalizedCols.length <= 3) return "";
  // 仅 2FA + api：username, password, two_fa, api
  if (normalizedCols.length === 4 && looksLikeTwoFaSecret(normalizedCols[2])) {
    const maybeApi = String(normalizedCols[3] || "").trim();
    if (hasMeaningfulAccountValue(maybeApi) && !looksLikeTwoFaSecret(maybeApi)) return maybeApi;
    return "";
  }
  if (normalizedCols.length <= 4) return "";
  const tailCols = normalizedCols.slice(4).map((item) => String(item || "").trim())
    .filter((item) => hasMeaningfulAccountValue(item) && !looksLikeTwoFaSecret(item));
  for (let i = tailCols.length - 1; i >= 0; i -= 1) {
    if (looksLikeAccountApiValue(tailCols[i])) return tailCols[i];
  }
  if (normalizedCols.length >= 7) {
    const stdApi = String(normalizedCols[6] || "").trim();
    if (hasMeaningfulAccountValue(stdApi) && !looksLikeTwoFaSecret(stdApi)) return stdApi;
  }
  if (normalizedCols.length === 5) {
    const compactApi = String(normalizedCols[4] || "").trim();
    if (hasMeaningfulAccountValue(compactApi) && !looksLikeTwoFaSecret(compactApi)) return compactApi;
  }
  if (normalizedCols.length === 6) {
    const maybeApi = String(normalizedCols[5] || "").trim();
    if (hasMeaningfulAccountValue(maybeApi) && !looksLikeTwoFaSecret(maybeApi)) return maybeApi;
  }
  const meaningfulTail = tailCols.filter(hasMeaningfulAccountValue);
  if (meaningfulTail.length === 1) return meaningfulTail[0];
  if (meaningfulTail.length > 1) return meaningfulTail[meaningfulTail.length - 1];
  return "";
}

function hasAccountImportRowChanges(existing, incoming) {
  const merged = mergeAccountImportRow(existing, incoming);
  const fieldsChanged = ACCOUNT_IMPORT_MERGE_FIELDS.some((field) => {
    return String(existing?.[field] || "").trim() !== String(merged?.[field] || "").trim();
  });
  if (fieldsChanged) return true;
  const incomingAlias = normalizeAliasValue(incoming?.assigned_alias);
  return Boolean(incomingAlias && !getEffectiveAccountAssignedAlias(existing));
}

function getAccountImportOverwriteChanges(existing, incoming) {
  const changes = [];
  for (const field of ACCOUNT_IMPORT_MERGE_FIELDS) {
    const before = String(existing?.[field] || "").trim();
    const after = String(incoming?.[field] || "").trim();
    if (!hasMeaningfulAccountValue(after)) continue;
    if (!hasMeaningfulAccountValue(before)) continue;
    if (before !== after) {
      changes.push({ field, before, after });
    }
  }
  return changes;
}

function buildAccountImportOverwriteConfirmMessage(overwriteSummaries) {
  const lines = overwriteSummaries.map(({ username, changes }) => {
    const fieldLines = changes
      .map((item) => `  - ${item.field}: 「${item.before}」→「${item.after}」`)
      .join("\n");
    return `${username}:\n${fieldLines}`;
  });
  return `以下账号将覆盖已有非空字段，确认继续导入？\n\n${lines.join("\n\n")}`;
}

let accountBatchPreviewItems = [];
let accountBatchPreviewSmartAliases = new Set();
let accountBatchImportInFlight = false;

function buildAccountUsernameIndex(rows = accountEditorRows) {
  const index = new Map();
  (rows || []).forEach((row, rowIndex) => {
    const normalized = String(row?.username || "").trim().toLowerCase();
    if (normalized && !index.has(normalized)) index.set(normalized, rowIndex);
  });
  return index;
}

function getMaxAccountRowIndex() {
  return (accountEditorRows || []).reduce((max, row) => {
    const ri = Number(row?.row_index);
    return Number.isFinite(ri) ? Math.max(max, ri) : max;
  }, -1);
}

function allocateAccountRowIndexes(count) {
  const safeCount = Math.max(0, Math.floor(Number(count) || 0));
  const start = getMaxAccountRowIndex() + 1;
  return Array.from({ length: safeCount }, (_, i) => start + i);
}

function setAccountBatchPreviewBusy(busy) {
  const disabled = Boolean(busy);
  if (confirmAccountBatchPreviewBtn) {
    confirmAccountBatchPreviewBtn.disabled = disabled;
    confirmAccountBatchPreviewBtn.textContent = disabled ? "导入中…" : "确定导入";
  }
  if (importAccountBatchBtn) importAccountBatchBtn.disabled = disabled;
}

function isAccountBatchPreviewItemCommittable(item) {
  if (!item || !item.selected || item.kind === "duplicate") return false;
  if (item.kind !== "skip") return true;
  const incomingAlias = normalizeAliasValue(item.assigned_alias || item.row?.assigned_alias);
  if (!incomingAlias) return false;
  const existing = accountEditorRows[item.existingIndex];
  return Boolean(existing && !getEffectiveAccountAssignedAlias(existing));
}

function applyPendingAccountBatchBulkAlias() {
  const alias = normalizeAliasValue(accountBatchPreviewBulkAliasEl?.value);
  if (!alias) return 0;
  let applied = 0;
  accountBatchPreviewItems.forEach((item) => {
    if (!item.selected || item.kind === "duplicate") return;
    if (item.kind === "skip") {
      const existing = accountEditorRows[item.existingIndex];
      if (!existing || getEffectiveAccountAssignedAlias(existing)) return;
    }
    if (normalizeAliasValue(item.assigned_alias)) return;
    item.assigned_alias = alias;
    item.row.assigned_alias = alias;
    applied += 1;
  });
  return applied;
}

function buildAccountBatchPreviewAliasOptionsHtml(currentValue = "") {
  const current = normalizeAliasValue(currentValue);
  const aliases = collectAssignableAliases(current);
  return [
    '<option value="">无</option>',
    ...aliases.map((alias) => `<option value="${escapeHtml(alias)}" ${alias === current ? "selected" : ""}>${escapeHtml(alias)}</option>`),
  ].join("");
}

function refreshAccountBatchPreviewBulkAliasOptions() {
  const datalistEl = document.getElementById("accountBatchPreviewBulkAliasList");
  if (!accountBatchPreviewBulkAliasEl || !datalistEl) return;
  const previous = normalizeAliasValue(accountBatchPreviewBulkAliasEl.value);
  const aliases = collectAssignableAliases(previous);
  datalistEl.innerHTML = aliases.map((alias) => `<option value="${escapeHtml(alias)}"></option>`).join("");
  if (previous) accountBatchPreviewBulkAliasEl.value = previous;
  refreshAccountBatchPreviewSmartAliasList();
}

function updateAccountBatchPreviewSmartAliasToggleLabel() {
  if (!accountBatchPreviewSmartAliasToggleBtn) return;
  const count = accountBatchPreviewSmartAliases.size;
  accountBatchPreviewSmartAliasToggleBtn.textContent = count ? `已选 ${count} 个别名` : "选择别名";
}

function refreshAccountBatchPreviewSmartAliasList() {
  if (!accountBatchPreviewSmartAliasListEl) return;
  const aliases = collectAssignableAliases();
  const valid = new Set(aliases);
  accountBatchPreviewSmartAliases = new Set(
    Array.from(accountBatchPreviewSmartAliases).filter((alias) => valid.has(alias)),
  );
  if (!aliases.length) {
    accountBatchPreviewSmartAliasListEl.innerHTML = '<div class="hint" style="padding:4px 6px;">暂无可用别名</div>';
    updateAccountBatchPreviewSmartAliasToggleLabel();
    return;
  }
  accountBatchPreviewSmartAliasListEl.innerHTML = aliases.map((alias) => {
    const checked = accountBatchPreviewSmartAliases.has(alias);
    return `<label class="import-preview-smart-alias-item">
      <input type="checkbox" class="account-batch-preview-smart-alias-check" value="${escapeHtml(alias)}"${checked ? " checked" : ""} />
      <span>${escapeHtml(alias)}</span>
    </label>`;
  }).join("");
  updateAccountBatchPreviewSmartAliasToggleLabel();
}

function setAccountBatchPreviewSmartAliasPanelOpen(open) {
  if (!accountBatchPreviewSmartAliasPanelEl) return;
  accountBatchPreviewSmartAliasPanelEl.classList.toggle("hidden", !open);
}

function collectAccountBatchPreviewAliasTargets() {
  const targets = [];
  accountBatchPreviewItems.forEach((item) => {
    if (!item.selected || item.kind === "duplicate") return;
    if (item.kind === "skip") {
      const existing = accountEditorRows[item.existingIndex];
      if (!existing || getEffectiveAccountAssignedAlias(existing)) return;
    }
    targets.push(item);
  });
  return targets;
}

function applyAccountBatchPreviewSmartDistribution() {
  syncAccountBatchPreviewItemsFromDom();
  const aliases = Array.from(accountBatchPreviewSmartAliases).sort((a, b) => a.localeCompare(b));
  if (!aliases.length) {
    setStatus("请先选择至少一个别名", "warning");
    return;
  }
  const targets = collectAccountBatchPreviewAliasTargets();
  if (!targets.length) {
    setStatus("请先勾选需要分配别名的记录", "warning");
    return;
  }
  const base = Math.floor(targets.length / aliases.length);
  const remainder = targets.length % aliases.length;
  let targetIndex = 0;
  for (let aliasIndex = 0; aliasIndex < aliases.length; aliasIndex += 1) {
    const count = base + (aliasIndex < remainder ? 1 : 0);
    for (let i = 0; i < count; i += 1) {
      const item = targets[targetIndex];
      item.assigned_alias = aliases[aliasIndex];
      item.row.assigned_alias = aliases[aliasIndex];
      targetIndex += 1;
    }
  }
  renderAccountBatchPreviewTable();
  const perAlias = aliases.map((alias, aliasIndex) => {
    const count = base + (aliasIndex < remainder ? 1 : 0);
    return `${alias}×${count}`;
  }).join("、");
  setStatus(`已为 ${targets.length} 条选中记录智能分配别名（${perAlias}）`, "success");
}

function updateAccountBatchPreviewSummary() {
  if (!accountBatchPreviewSummaryEl) return;
  const total = accountBatchPreviewItems.length;
  const selected = accountBatchPreviewItems.filter((item) => item.selected && item.kind !== "duplicate").length;
  const duplicateCount = accountBatchPreviewItems.filter((item) => item.kind === "duplicate").length;
  const parts = [`共 ${total} 条`, `已选 ${selected} 条`];
  if (duplicateCount) parts.push(`文件内重复 ${duplicateCount} 条（不可选）`);
  accountBatchPreviewSummaryEl.textContent = parts.join("，");
}

function renderAccountBatchPreviewTable() {
  if (!accountBatchPreviewTableWrapEl) return;
  if (!accountBatchPreviewItems.length) {
    accountBatchPreviewTableWrapEl.innerHTML = '<div class="hint" style="padding:16px;">没有可预览的记录</div>';
    updateAccountBatchPreviewSummary();
    return;
  }
  const rowsHtml = accountBatchPreviewItems.map((item, index) => {
    const row = item.row || {};
    const isDuplicate = item.kind === "duplicate";
    const statusClass = item.kind === "new"
      ? "import-preview-status--new"
      : item.kind === "update"
        ? "import-preview-status--update"
        : item.kind === "duplicate"
          ? "import-preview-status--duplicate"
          : "import-preview-status--skip";
    const statusText = item.kind === "new"
      ? "新增"
      : item.kind === "update"
        ? "更新已有"
        : item.kind === "duplicate"
          ? "文件内重复"
          : "无需更新";
    const previewExisting = item.existingIndex != null ? accountEditorRows[item.existingIndex] : item.row;
    const aliasDisabled = isDuplicate || (item.kind === "skip" && getEffectiveAccountAssignedAlias(previewExisting))
      ? "disabled"
      : "";
    const aliasHtml = buildAccountBatchPreviewAliasOptionsHtml(item.assigned_alias || "");
    return `<tr data-preview-index="${index}">
      <td><input type="checkbox" class="account-batch-preview-row-check" data-preview-index="${index}" ${item.selected && !isDuplicate ? "checked" : ""} ${isDuplicate ? "disabled" : ""} /></td>
      <td class="preview-cell-truncate" title="${escapeHtml(row.username || "")}">${escapeHtml(row.username || "")}</td>
      <td class="preview-cell-truncate" title="${escapeHtml(row.password || "")}">${escapeHtml(row.password || "")}</td>
      <td class="preview-cell-truncate" title="${escapeHtml(row.email || "")}">${escapeHtml(row.email || "")}</td>
      <td class="preview-cell-truncate" title="${escapeHtml(row.email_password || "")}">${escapeHtml(row.email_password || "")}</td>
      <td class="preview-cell-truncate" title="${escapeHtml(row.two_fa || "")}">${escapeHtml(row.two_fa || "")}</td>
      <td class="preview-cell-truncate" title="${escapeHtml(row.api || "")}">${escapeHtml(row.api || "")}</td>
      <td>${escapeHtml(row.region || "")}</td>
      <td><select class="ui-select account-batch-preview-alias-select" data-preview-index="${index}" ${aliasDisabled}>${aliasHtml}</select></td>
      <td><span class="import-preview-status ${statusClass}">${statusText}</span></td>
    </tr>`;
  }).join("");
  accountBatchPreviewTableWrapEl.innerHTML = `<table class="import-preview-table">
    <thead>
      <tr>
        <th style="width:44px;">选</th>
        <th>username</th>
        <th>password</th>
        <th>email</th>
        <th>email_pwd</th>
        <th>2FA</th>
        <th>api</th>
        <th>地区</th>
        <th style="min-width:120px;">别名</th>
        <th style="width:96px;">状态</th>
      </tr>
    </thead>
    <tbody>${rowsHtml}</tbody>
  </table>`;
  syncAccountBatchPreviewSelectAllState();
  updateAccountBatchPreviewSummary();
}

function syncAccountBatchPreviewSelectAllState() {
  if (!accountBatchPreviewSelectAllEl || !accountBatchPreviewTableWrapEl) return;
  const selectable = accountBatchPreviewItems.filter((item) => item.kind !== "duplicate");
  const selectedCount = selectable.filter((item) => item.selected).length;
  accountBatchPreviewSelectAllEl.checked = selectable.length > 0 && selectedCount === selectable.length;
  accountBatchPreviewSelectAllEl.indeterminate = selectedCount > 0 && selectedCount < selectable.length;
}

function syncAccountBatchPreviewItemsFromDom() {
  if (!accountBatchPreviewTableWrapEl) return;
  accountBatchPreviewTableWrapEl.querySelectorAll(".account-batch-preview-row-check").forEach((el) => {
    const index = Number(el.dataset.previewIndex);
    const item = accountBatchPreviewItems[index];
    if (!item || item.kind === "duplicate") return;
    item.selected = Boolean(el.checked);
  });
  accountBatchPreviewTableWrapEl.querySelectorAll(".account-batch-preview-alias-select").forEach((el) => {
    const index = Number(el.dataset.previewIndex);
    const item = accountBatchPreviewItems[index];
    if (!item || item.kind === "duplicate") return;
    if (item.kind === "skip" && !isAccountBatchPreviewItemCommittable(item)) return;
    item.assigned_alias = normalizeAliasValue(el.value);
    item.row.assigned_alias = item.assigned_alias;
  });
}

function openAccountBatchPreviewModal(items) {
  if (!accountBatchPreviewModalEl) return;
  accountBatchPreviewItems = items;
  accountBatchPreviewSmartAliases = new Set();
  setAccountBatchPreviewSmartAliasPanelOpen(false);
  refreshAccountBatchPreviewBulkAliasOptions();
  renderAccountBatchPreviewTable();
  accountBatchPreviewModalEl.classList.remove("hidden");
  accountBatchPreviewModalEl.setAttribute("aria-hidden", "false");
}

function closeAccountBatchPreviewModal() {
  if (!accountBatchPreviewModalEl) return;
  accountBatchPreviewModalEl.classList.add("hidden");
  accountBatchPreviewModalEl.setAttribute("aria-hidden", "true");
  if (accountBatchPreviewBulkAliasEl) accountBatchPreviewBulkAliasEl.value = "";
  accountBatchPreviewSmartAliases = new Set();
  setAccountBatchPreviewSmartAliasPanelOpen(false);
  accountBatchPreviewItems = [];
}

let accountStatsQueryPollTimer = null;

function applyAccountStatsQueryScheduleUi(cfg = {}) {
  accountStatsQueryInFlight = !!cfg.in_flight;
  if (accountStatsQueryScheduleToggle) {
    accountStatsQueryScheduleToggle.checked = !!cfg.schedule_enabled;
  }
  if (accountStatsQueryScheduleTime) {
    accountStatsQueryScheduleTime.value = String(cfg.schedule_time || "09:00").slice(0, 5);
    accountStatsQueryScheduleTime.disabled = !accountStatsQueryScheduleToggle?.checked;
  }
  if (accountStatsQueryBtn) {
    accountStatsQueryBtn.disabled = accountStatsQueryInFlight;
    accountStatsQueryBtn.textContent = accountStatsQueryInFlight ? "查询进行中…" : "全量查询并落库";
  }
}

function updateAccountStatsQuerySummary() {
  if (!accountStatsQuerySummaryEl) return;
  const selectedCount = accountStatsQuerySelectedAliases.size;
  const totalAliases = accountStatsQueryAliasGroups.length;
  let usernameCount = 0;
  accountStatsQueryAliasGroups.forEach((group) => {
    const alias = normalizeAliasValue(group?.alias);
    if (alias && accountStatsQuerySelectedAliases.has(alias)) {
      usernameCount += Number(group?.count) || (Array.isArray(group?.usernames) ? group.usernames.length : 0);
    }
  });
  accountStatsQuerySummaryEl.textContent = `已选 ${selectedCount}/${totalAliases} 个别名，约 ${usernameCount} 个账号`;
}

function syncAccountStatsQuerySelectAllState() {
  if (!accountStatsQuerySelectAllEl) return;
  const allAliases = accountStatsQueryAliasGroups.map((g) => normalizeAliasValue(g?.alias)).filter(Boolean);
  const selectedCount = allAliases.filter((a) => accountStatsQuerySelectedAliases.has(a)).length;
  accountStatsQuerySelectAllEl.checked = allAliases.length > 0 && selectedCount === allAliases.length;
  accountStatsQuerySelectAllEl.indeterminate = selectedCount > 0 && selectedCount < allAliases.length;
}

function renderAccountStatsQueryAliasList() {
  if (!accountStatsQueryAliasListEl) return;
  if (!accountStatsQueryAliasGroups.length) {
    accountStatsQueryAliasListEl.innerHTML = '<div class="hint" style="padding:16px;">暂无已分配别名的账号，请先在账号记录或设备档案中分配别名。</div>';
    updateAccountStatsQuerySummary();
    syncAccountStatsQuerySelectAllState();
    return;
  }
  const rowsHtml = accountStatsQueryAliasGroups.map((group) => {
    const alias = normalizeAliasValue(group?.alias);
    const count = Number(group?.count) || (Array.isArray(group?.usernames) ? group.usernames.length : 0);
    const checked = alias && accountStatsQuerySelectedAliases.has(alias);
    const preview = (Array.isArray(group?.usernames) ? group.usernames.slice(0, 5) : []).join(", ");
    const more = count > 5 ? ` 等 ${count} 个` : "";
    return `<tr>
      <td style="width:40px;text-align:center;">
        <input type="checkbox" class="account-stats-query-alias-check" data-alias="${escapeHtml(alias)}"${checked ? " checked" : ""} />
      </td>
      <td><strong>${escapeHtml(alias)}</strong></td>
      <td>${count}</td>
      <td class="preview-cell-truncate" title="${escapeHtml(preview + more)}">${escapeHtml(preview)}${escapeHtml(more)}</td>
    </tr>`;
  }).join("");
  accountStatsQueryAliasListEl.innerHTML = `<table class="import-preview-table">
    <thead><tr><th></th><th>别名</th><th>账号数</th><th>username 预览</th></tr></thead>
    <tbody>${rowsHtml}</tbody>
  </table>`;
  updateAccountStatsQuerySummary();
  syncAccountStatsQuerySelectAllState();
}

async function loadAccountStatsQueryConfig() {
  try {
    const data = await request("/api/admin/account_stats_query/config");
    const cfg = data.data || {};
    accountStatsQueryConfig = cfg;
    accountStatsQueryAliasGroups = Array.isArray(cfg.alias_groups) ? cfg.alias_groups : [];
    accountStatsQuerySelectedAliases = new Set(
      (Array.isArray(cfg.selected_aliases) ? cfg.selected_aliases : [])
        .map((a) => normalizeAliasValue(a))
        .filter(Boolean),
    );
    applyAccountStatsQueryScheduleUi(cfg);
    if (accountStatsQueryModalEl && !accountStatsQueryModalEl.classList.contains("hidden")) {
      renderAccountStatsQueryAliasList();
    }
    scheduleAccountStatsQueryPoll(!!cfg.in_flight);
    return cfg;
  } catch (error) {
    applyAccountStatsQueryScheduleUi({});
    throw error;
  }
}

function scheduleAccountStatsQueryPoll(active) {
  if (accountStatsQueryPollTimer) {
    clearInterval(accountStatsQueryPollTimer);
    accountStatsQueryPollTimer = null;
  }
  if (!active) return;
  accountStatsQueryPollTimer = setInterval(async () => {
    try {
      const prevInFlight = accountStatsQueryInFlight;
      const cfg = await loadAccountStatsQueryConfig();
      if (prevInFlight && !cfg.in_flight) {
        await refreshAccountEditorLight();
        setStatus("账号统计查询已完成，表格已刷新", "success");
      }
    } catch (_) {
      // 静默轮询失败
    }
  }, 4000);
}

function restoreAccountStatsQuerySelectionFromConfig() {
  const saved = Array.isArray(accountStatsQueryConfig?.selected_aliases)
    ? accountStatsQueryConfig.selected_aliases
    : [];
  accountStatsQuerySelectedAliases = new Set(
    saved.map((a) => normalizeAliasValue(a)).filter(Boolean),
  );
}

async function saveAccountStatsQueryConfig(options = {}) {
  // 并发保存时排队等待，避免“看起来成功但其实没写库”
  while (accountStatsQueryConfigSaveInFlight) {
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  accountStatsQueryConfigSaveInFlight = true;
  try {
    const body = {};
    if (options.selected_aliases != null) {
      // 仅在明确传入时更新别名；不要用弹窗里未确认的勾选覆盖已保存记忆
      const aliases = Array.isArray(options.selected_aliases)
        ? options.selected_aliases
        : Array.from(accountStatsQuerySelectedAliases);
      body.selected_aliases = aliases.map((a) => normalizeAliasValue(a)).filter(Boolean);
    }
    if (options.schedule_enabled != null) {
      body.schedule_enabled = !!options.schedule_enabled;
    }
    if (options.schedule_time != null) {
      body.schedule_time = String(options.schedule_time || "09:00").slice(0, 5);
    }
    const data = await request("/api/admin/account_stats_query/config", {
      method: "POST",
      body: JSON.stringify(body),
    });
    accountStatsQueryConfig = data.data || accountStatsQueryConfig;
    if (body.selected_aliases) {
      accountStatsQuerySelectedAliases = new Set(body.selected_aliases);
    }
    applyAccountStatsQueryScheduleUi({ ...accountStatsQueryConfig, in_flight: accountStatsQueryInFlight });
    return data;
  } finally {
    accountStatsQueryConfigSaveInFlight = false;
  }
}

function openAccountStatsQueryModal() {
  if (!accountStatsQueryModalEl) return;
  renderAccountStatsQueryAliasList();
  accountStatsQueryModalEl.classList.remove("hidden");
  accountStatsQueryModalEl.setAttribute("aria-hidden", "false");
}

function closeAccountStatsQueryModal({ discardSelection = true } = {}) {
  if (!accountStatsQueryModalEl) return;
  accountStatsQueryModalEl.classList.add("hidden");
  accountStatsQueryModalEl.setAttribute("aria-hidden", "true");
  // 取消/关闭时丢弃未确认勾选，避免污染定时保存与下次记忆
  if (discardSelection) restoreAccountStatsQuerySelectionFromConfig();
}

async function openAccountStatsQueryFlow() {
  if (accountStatsQueryInFlight) {
    setStatus("查询任务正在执行中，请稍后再试", "warning");
    return;
  }
  try {
    await loadAccountStatsQueryConfig();
    openAccountStatsQueryModal();
  } catch (error) {
    setStatus(error.message || "加载查询配置失败", "error");
  }
}

async function confirmAccountStatsQueryRun() {
  if (accountStatsQueryInFlight) {
    setStatus("查询任务正在执行中，请稍后再试", "warning");
    return;
  }
  const selected = Array.from(accountStatsQuerySelectedAliases).filter(Boolean);
  if (!selected.length) {
    setStatus("请至少选择一个别名", "warning");
    return;
  }
  try {
    await saveAccountStatsQueryConfig({ selected_aliases: selected });
    const data = await request("/api/admin/account_stats_query/run", {
      method: "POST",
      body: JSON.stringify({ selected_aliases: selected }),
    });
    closeAccountStatsQueryModal({ discardSelection: false });
    accountStatsQueryInFlight = true;
    applyAccountStatsQueryScheduleUi({ ...accountStatsQueryConfig, in_flight: true });
    scheduleAccountStatsQueryPoll(true);
    setStatus(data.msg || "已提交查询任务", "success");
  } catch (error) {
    setStatus(error.message || "提交查询失败", "error");
  }
}

async function downloadAccountStatsQueryLogs() {
  const url = await buildApiUrl("/api/admin/account_stats_query/logs/download");
  const headers = {};
  const token = authToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(url, { headers, cache: "no-store" });
  if (!response.ok) {
    const text = await response.text();
    let msg = text.slice(0, 200) || `下载失败 HTTP ${response.status}`;
    try {
      const data = JSON.parse(text);
      if (data?.msg) msg = String(data.msg);
    } catch {
      /* 非 JSON 时沿用原始文本 */
    }
    throw new Error(msg);
  }
  const blob = await response.blob();
  const cd = response.headers.get("content-disposition") || "";
  const match = /filename="?([^";]+)"?/i.exec(cd);
  const day = new Date().toISOString().slice(0, 10);
  const filename = match?.[1] || `account_stats_query_${day}.log`;
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(objectUrl);
}

function buildAccountBatchPreviewItems(parsedRows, existingUsernameIndex) {
  const seenInBatch = new Set();
  const items = [];
  for (const row of parsedRows) {
    const normalizedUsername = String(row?.username || "").trim().toLowerCase();
    if (!normalizedUsername) continue;
    if (seenInBatch.has(normalizedUsername)) {
      items.push({
        kind: "duplicate",
        selected: false,
        assigned_alias: "",
        row: { ...row },
      });
      continue;
    }
    seenInBatch.add(normalizedUsername);
    const existingIndex = existingUsernameIndex.get(normalizedUsername);
    if (existingIndex != null) {
      const existing = accountEditorRows[existingIndex];
      if (!hasAccountImportRowChanges(existing, row)) {
        items.push({
          kind: "skip",
          selected: false,
          assigned_alias: normalizeAliasValue(existing?.assigned_alias),
          row: mergeAccountImportRow(existing, row),
          existingIndex,
        });
        continue;
      }
      items.push({
        kind: "update",
        selected: true,
        assigned_alias: normalizeAliasValue(existing?.assigned_alias),
        row: mergeAccountImportRow(existing, row),
        existingIndex,
      });
      continue;
    }
    items.push({
      kind: "new",
      selected: true,
      assigned_alias: "",
      row: { ...row },
    });
  }
  return items;
}

async function commitAccountBatchImportFromPreview() {
  if (accountBatchImportInFlight) return;
  accountBatchImportInFlight = true;
  setAccountBatchPreviewBusy(true);
  try {
    syncAccountBatchPreviewItemsFromDom();
    const bulkAliasApplied = applyPendingAccountBatchBulkAlias();
    if (bulkAliasApplied > 0) renderAccountBatchPreviewTable();
    const selectedItems = accountBatchPreviewItems.filter(isAccountBatchPreviewItemCommittable);
    if (!selectedItems.length) {
      throw new Error("请至少勾选一条可导入的记录");
    }
    syncAccountAliasFromDeviceRows();
    const existingUsernameIndex = buildAccountUsernameIndex();
    const seenUsernames = new Set();
    let parsedWithoutApi = 0;
    const rowsToImport = [];
    const rowsToUpdate = [];
    for (const item of selectedItems) {
      const normalizedUsername = String(item.row?.username || "").trim().toLowerCase();
      if (!normalizedUsername || seenUsernames.has(normalizedUsername)) continue;
      seenUsernames.add(normalizedUsername);
      const row = {
        ...item.row,
        assigned_alias: normalizeAliasValue(item.assigned_alias || item.row?.assigned_alias),
      };
      if (!hasMeaningfulAccountValue(row.api)) parsedWithoutApi += 1;
      const existingIndex = item.existingIndex != null
        ? item.existingIndex
        : existingUsernameIndex.get(normalizedUsername);
      if (existingIndex != null && accountEditorRows[existingIndex]) {
        rowsToUpdate.push({
          index: existingIndex,
          row: mergeAccountImportRow(accountEditorRows[existingIndex], row),
        });
        continue;
      }
      rowsToImport.push(row);
      existingUsernameIndex.set(normalizedUsername, accountEditorRows.length + rowsToImport.length - 1);
    }
    if (!rowsToImport.length && !rowsToUpdate.length) {
      throw new Error("没有可导入的账号：所选记录均已存在或重复");
    }
    const newRowIndexes = allocateAccountRowIndexes(rowsToImport.length);
    const overwriteSummaries = rowsToUpdate
      .map(({ index, row }) => {
        const existing = accountEditorRows[index];
        const incoming = { ...row };
        const changes = getAccountImportOverwriteChanges(existing, incoming);
        if (!changes.length) return null;
        return {
          username: String(existing?.username || "").trim(),
          changes,
        };
      })
      .filter(Boolean);
    if (overwriteSummaries.length) {
      if (!window.confirm(buildAccountImportOverwriteConfirmMessage(overwriteSummaries))) {
        setStatus("已取消批量导入：未覆盖任何已有非空字段", "info");
        return;
      }
    }
    const batchItems = [
      ...rowsToUpdate.map(({ index, row }) => ({
        row_index: resolveAccountRowIndex(accountEditorRows[index], index),
        ...row,
      })),
      ...rowsToImport.map((row, offset) => ({
        row_index: newRowIndexes[offset],
        ...row,
      })),
    ];
    const duplicateCount = accountBatchPreviewItems.filter((item) => item.kind === "duplicate").length;
    const statusParts = [];
    if (rowsToImport.length) statusParts.push(`新增 ${rowsToImport.length} 条`);
    if (rowsToUpdate.length) statusParts.push(`更新已有 ${rowsToUpdate.length} 条`);
    if (parsedWithoutApi > 0) statusParts.push(`其中 ${parsedWithoutApi} 条未识别到 api（请检查列格式）`);
    if (duplicateCount) statusParts.push(`跳过文件内重复 ${duplicateCount} 条`);
    if (bulkAliasApplied > 0) statusParts.push(`已自动分配别名 ${bulkAliasApplied} 条`);
    const hasChanges = rowsToImport.length > 0 || rowsToUpdate.length > 0;
    const statusLevel = parsedWithoutApi > 0 ? "warning" : (hasChanges ? "success" : "warning");
    await request(`${ACCOUNT_ROWS_API}/batch_upsert`, {
      method: "POST",
      body: JSON.stringify({ items: batchItems }),
    });
    closeAccountBatchPreviewModal();
    if (accountBatchInputEl) accountBatchInputEl.value = "";
    setStatus(`批量导入完成：${statusParts.join("，")}`, statusLevel);
    try {
      await loadAccountEditorRows();
      renderAccountEditorRows();
      renderDeviceRegionPresetOptions();
      if (document.getElementById("backup-section")?.classList.contains("active")) {
        await loadBackupArchiveRecords();
      }
    } catch (reloadError) {
      const reloadMsg = reloadError instanceof Error ? reloadError.message : String(reloadError || "刷新失败");
      setStatus(`批量导入已写入，但刷新列表失败：${reloadMsg}`, "warning");
    }
  } finally {
    accountBatchImportInFlight = false;
    setAccountBatchPreviewBusy(false);
  }
}

function parseAccountBatchLine(line, sharedRegion = "", rejectEmpty = false) {
  const raw = String(line || "").trim();
  if (!raw) return null;
  const normalizedCols = splitAccountBatchColumns(raw);
  if (isAccountBatchHeaderLine(normalizedCols)) return null;
  if (normalizedCols.length < 3) {
    throw new Error(`格式错误：至少需要3列（username、password、two_fa）或4列（username、password、email、email_password）。支持 TAB 或 ---- 分隔，实际 ${normalizedCols.length} 列`);
  }
  const username = String(normalizedCols[0] || "").trim();
  if (!username) {
    throw new Error("格式错误：username 不能为空");
  }
  const password = String(normalizedCols[1] || "").trim();
  let email = "";
  let emailPassword = "";
  const twoFa = resolveAccountBatchTwoFa(normalizedCols);
  // 支持格式：
  // 1) 3列仅2FA：username,password,two_fa
  // 2) 4列仅2FA+api：username,password,two_fa,api
  // 3) 4列邮箱：username,password,email,email_password
  // 4) 4列邮箱+2FA：username,password,email,two_fa
  // 5) 5列：username,password,email,email_password,api 或 …,two_fa
  // 6) 6列：…,two_fa,api 或旧版 …,(忽略),api
  // 7) 7列：…,two_fa,(忽略),api 或旧版 …,(忽略),(忽略),api
  const twoFaOnly = (
    (normalizedCols.length === 3 && Boolean(twoFa))
    || (normalizedCols.length === 4 && looksLikeTwoFaSecret(normalizedCols[2]))
  );
  if (!twoFaOnly) {
    if (normalizedCols.length < 4) {
      throw new Error("格式错误：无 2FA 时至少需要4列（username、password、email、email_password）");
    }
    email = String(normalizedCols[2] || "").trim();
    const col3 = String(normalizedCols[3] || "").trim();
    // 第4列为 2FA 时不要把它写进 email_password
    emailPassword = (looksLikeTwoFaSecret(col3) && !looksLikeTwoFaSecret(normalizedCols[2]))
      ? ""
      : col3;
  }
  const api = resolveAccountBatchApi(normalizedCols);
  if (rejectEmpty) {
    const missingFields = [];
    if (!hasMeaningfulAccountValue(username)) missingFields.push("username");
    if (!hasMeaningfulAccountValue(password)) missingFields.push("password");
    const hasEmailCreds = hasMeaningfulAccountValue(email) && hasMeaningfulAccountValue(emailPassword);
    const hasTwoFa = hasMeaningfulAccountValue(twoFa);
    if (!hasEmailCreds && !hasTwoFa) missingFields.push("email/email_password 或 two_fa");
    if (!hasMeaningfulAccountValue(api) && !hasTwoFa) missingFields.push("api");
    if (missingFields.length) {
      throw new Error(`存在空值字段：${missingFields.join("、")}`);
    }
  }
  return getAccountRowTemplate({
    username,
    password,
    email,
    email_password: emailPassword,
    two_fa: twoFa,
    api,
    region: String(sharedRegion || "").trim(),
  });
}

async function importAccountBatchFromText() {
  const text = String(accountBatchInputEl?.value || "");
  const sharedRegion = String(accountBatchRegionInputEl?.value || "").trim();
  if (!sharedRegion) {
    const msg = "批量导入前请先填写「统一国家地区」（例如 MX / US）。";
    setStatus(msg, "error");
    window.alert(msg);
    return;
  }
  const allowEmpty = accountBatchRejectEmptyToggleEl?.checked !== false;
  const rejectEmpty = !allowEmpty;
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (!lines.length) {
    throw new Error("请先粘贴批量数据（每行一条）");
  }
  syncAccountAliasFromDeviceRows();
  let parsedWithoutApi = 0;
  const parsedRows = lines.map((line, idx) => {
    try {
      const row = parseAccountBatchLine(line, sharedRegion, rejectEmpty);
      if (row && !hasMeaningfulAccountValue(row.api)) parsedWithoutApi += 1;
      return row;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new Error(`第 ${idx + 1} 行解析失败：${message}`);
    }
  }).filter(Boolean);
  const existingUsernameIndex = buildAccountUsernameIndex();
  const previewItems = buildAccountBatchPreviewItems(parsedRows, existingUsernameIndex);
  const importableCount = previewItems.filter((item) => item.kind === "new" || item.kind === "update").length;
  const duplicateCount = previewItems.filter((item) => item.kind === "duplicate").length;
  if (!importableCount) {
    if (duplicateCount) {
      throw new Error(`没有可导入的账号：检测到文件内重复 username 共 ${duplicateCount} 条`);
    }
    throw new Error("没有可导入的账号：所有记录已存在且字段无需更新");
  }
  if (parsedWithoutApi > 0) {
    setStatus(`已解析 ${previewItems.length} 条，其中 ${parsedWithoutApi} 条未识别到 api，请在预览中确认`, "warning");
  } else {
    setStatus(`已解析 ${previewItems.length} 条，请在预览中勾选并确认导入`, "info");
  }
  openAccountBatchPreviewModal(previewItems);
}

function isUsernameUsedElsewhere(username, currentIndex) {
  const normalized = String(username || "").trim().toLowerCase();
  if (!normalized) return false;
  return deviceEditorRows.some((row, idx) => {
    if (idx === currentIndex) return false;
    return String(row?.username || "").trim().toLowerCase() === normalized;
  });
}

function normalizeAliasValue(value) {
  return String(value || "").trim();
}

function normalizeAliasKey(value) {
  return normalizeAliasValue(value).toLowerCase();
}

function normalizeAutoSwitchPlansByAlias(rawPlans = {}) {
  const normalized = {};
  Object.entries(rawPlans || {}).forEach(([alias, bucket]) => {
    const key = normalizeAliasKey(alias);
    if (key) normalized[key] = bucket;
  });
  return normalized;
}

function normalizeSwitchHighlightField(value) {
  return String(value || "").trim() === "是" ? "是" : "否";
}

const ENV_STATUS_ACTIVE = "环境活跃";
const ENV_STATUS_RESTORE_FAILED = "还原失败";
const ENV_STATUS_PENDING = "待就绪";

function normalizeEnvStatusField(value) {
  const raw = String(value || "").trim();
  if (raw === ENV_STATUS_ACTIVE || raw === ENV_STATUS_RESTORE_FAILED || raw === ENV_STATUS_PENDING) {
    return raw;
  }
  return "";
}

function resolveBackupArchiveEnvRuntimeMeta(envStatus) {
  const status = normalizeEnvStatusField(envStatus);
  if (status === ENV_STATUS_ACTIVE) {
    return {
      label: ENV_STATUS_ACTIVE,
      className: "backup-archive-env-badge--env-active",
      title: "备份已还原到设备，环境处于活跃可用状态",
    };
  }
  if (status === ENV_STATUS_RESTORE_FAILED) {
    return {
      label: ENV_STATUS_RESTORE_FAILED,
      className: "backup-archive-env-badge--env-restore-failed",
      title: "环境归属未变，但最近一次还原失败",
    };
  }
  if (status === ENV_STATUS_PENDING) {
    return {
      label: ENV_STATUS_PENDING,
      className: "backup-archive-env-badge--env-pending",
      title: "环境归属已切换，设备任务尚未完成（清理/还原进行中）",
    };
  }
  return null;
}

function normalizeBasicProfileModifiedField(value) {
  return String(value || "").trim() === "是" ? "是" : "否";
}

function getAssignedAliasStats() {
  const aliasMap = new Map();
  (accountEditorRows || []).forEach((row, index) => {
    const username = String(row?.username || "").trim();
    const alias = normalizeAliasValue(row?.assigned_alias);
    if (!username) return;
    if (!alias) return;
    if (!aliasMap.has(alias)) aliasMap.set(alias, []);
    aliasMap.get(alias).push({ username, index });
  });
  return aliasMap;
}

function isAliasAssignedElsewhere(alias, currentIndex) {
  const normalized = normalizeAliasValue(alias);
  if (!normalized) return false;
  return (accountEditorRows || []).some((row, idx) => {
    if (idx === currentIndex) return false;
    return normalizeAliasValue(row?.assigned_alias) === normalized;
  });
}

async function saveDeviceRow(index, options = {}) {
  const row = deviceEditorRows[index];
  if (!row) return;
  const { skipUsernameDuplicateCheck = false, skipReload = false, rowIndex: rowIndexOverride } = options || {};
  const username = String(row.username || "").trim();
  if (!skipUsernameDuplicateCheck && username && isUsernameUsedElsewhere(username, index)) {
    throw new Error(`username 已被其他设备别名占用：${username}`);
  }
  const rowIndex = Number.isFinite(Number(rowIndexOverride))
    ? Number(rowIndexOverride)
    : (Number.isFinite(Number(row?.row_index)) ? Number(row.row_index) : index);
  await request(`${DEVICE_ROWS_API}/upsert`, {
    method: "POST",
    body: JSON.stringify({ ...row, row_index: rowIndex }),
  });
  if (!skipReload) {
    await loadEditorState();
    renderAccountEditorRows();
    renderDeviceEditorRows();
  }
}

function resolveAccountRowIndex(row, fallbackIndex) {
  const ri = Number(row?.row_index);
  return Number.isFinite(ri) && ri >= 0 ? ri : fallbackIndex;
}

/** 账号记录上展示/保存用的有效分配别名（含备份档案反查） */
function getEffectiveAccountAssignedAlias(row = {}) {
  const fromRow = normalizeAliasValue(row?.assigned_alias);
  if (fromRow) return fromRow;
  const username = String(row?.username || "").trim().toLowerCase();
  if (username && backupAliasByUsername.has(username)) {
    return backupAliasByUsername.get(username) || "";
  }
  return "";
}

function getAccountRowTemplate(source = {}) {
  const ri = source.row_index;
  const rowIndex = ri != null && ri !== "" && !Number.isNaN(Number(ri)) ? Number(ri) : undefined;
  return {
    ...(rowIndex !== undefined ? { row_index: rowIndex } : {}),
    username: source.username || "",
    password: source.password || "",
    email: source.email || "",
    email_password: source.email_password || "",
    two_fa: source.two_fa || "",
    api: source.api || "",
    is_login: source.is_login ?? false,
    login_time: source.login_time || "",
    region: source.region || "",
    video_count: source.video_count || "",
    follower_count: source.follower_count || "",
    follower_growth: source.follower_growth || "",
    heart_count: source.heart_count || "",
    follower_growth_day_before: source.follower_growth_day_before || "",
    follower_growth_yesterday: source.follower_growth_yesterday || "",
    follower_growth_today: source.follower_growth_today || "",
    heart_growth_day_before: source.heart_growth_day_before || "",
    heart_growth_yesterday: source.heart_growth_yesterday || "",
    heart_growth_today: source.heart_growth_today || "",
    basic_profile_modified: normalizeBasicProfileModifiedField(source.basic_profile_modified),
    // 分配别名仅以账号表单字段为准；备份档案别名由 backupAliasByUsername / 渲染层展示。
    assigned_alias: normalizeAliasValue(source.assigned_alias) || "",
  };
}

/** 删除分配后同步释放账号记录中的分配别名（本地缓存 + 备份索引） */
function releaseAccountAliasBindingLocally(username, assignedAlias) {
  const uname = String(username || "").trim().toLowerCase();
  const alias = normalizeAliasValue(assignedAlias);
  if (!uname) return;
  backupAliasByUsername.delete(uname);
  (accountEditorRows || []).forEach((row) => {
    const rowUser = String(row?.username || "").trim().toLowerCase();
    if (rowUser !== uname) return;
    const rowAlias = normalizeAliasValue(row?.assigned_alias);
    if (!rowAlias || !alias || rowAlias === alias) row.assigned_alias = "";
  });
  backupArchiveRows = (backupArchiveRows || []).filter((item) => {
    const itemUser = String(item?.username || "").trim().toLowerCase();
    const itemAlias = normalizeAliasValue(item?.assigned_alias || item?.mobile_alias || item?.device_alias);
    return !(itemUser === uname && itemAlias === alias);
  });
}

function getDeviceRowTemplate(source = {}) {
  const ri = source.row_index;
  const rowIndex = ri != null && ri !== "" && !Number.isNaN(Number(ri)) ? Number(ri) : undefined;
  return {
    ...(rowIndex !== undefined ? { row_index: rowIndex } : {}),
    device_alias: source.device_alias || "",
    device_id: source.device_id || "",
    ip: source.ip || "",
    region: source.region || "",
    username: source.username || "",
    assigned_alias: source.assigned_alias || "",
    backup_path: source.backup_path || "",
    backup_name: source.backup_name || "",
    backup_time: source.backup_time || "",
    backup_status: source.backup_status || "",
    remark: source.remark || "",
    switch_highlight: normalizeSwitchHighlightField(source.switch_highlight),
  };
}

function renderBatchStreamAliasOptions() {
  if (!batchStreamAliasSelect) return;
  const options = buildAliasDevicePairs();
  const previous = String(batchStreamAliasSelect.value || "").trim();
  batchStreamAliasSelect.innerHTML = [
    '<option value="">选择设备别名</option>',
    ...options.map((item) => `<option value="${escapeHtml(item.device_alias)}" data-device-id="${escapeHtml(item.device_id)}">${escapeHtml(item.device_alias)} | ${escapeHtml(item.device_id || "-")}</option>`),
  ].join("");
  if (previous && options.some((item) => item.device_alias === previous)) {
    batchStreamAliasSelect.value = previous;
  }
}

function resolveBatchStreamDeviceSelection() {
  const alias = normalizeAliasValue(batchStreamAliasSelect?.value);
  if (!alias) return null;
  const option = Array.from(batchStreamAliasSelect?.options || []).find((it) => it.value === alias);
  const deviceId = String(option?.dataset?.deviceId || "").trim();
  const pair = buildAliasDevicePairs().find((item) => item.device_alias === alias);
  return {
    alias,
    device_id: deviceId || String(pair?.device_id || "").trim(),
  };
}

function setBatchStreamUiBusy(busy) {
  batchStreamBusy = busy;
  if (batchStreamStartBtn) batchStreamStartBtn.disabled = busy;
  if (batchStreamStopBtn) batchStreamStopBtn.disabled = busy;
  if (batchStreamFileInput) batchStreamFileInput.disabled = busy;
}

function updateBatchStreamUploadHint(text) {
  if (batchStreamUploadHint) batchStreamUploadHint.textContent = text;
}

function isBatchStreamSectionActive() {
  return document.getElementById("batch-stream")?.classList.contains("active");
}

function updateBatchStreamProgressVisibility() {
  if (batchStreamProgressCard) {
    batchStreamProgressCard.style.display = batchStreamSessionId ? "" : "none";
  }
}

function batchStreamRowStatusBadge(status) {
  const text = String(status || "").trim();
  if (!text) return '<span class="badge pending">待处理</span>';
  return `<span class="badge online">${escapeHtml(text)}</span>`;
}

function resolveBatchStreamDeviceState(devices, deviceId) {
  const did = String(deviceId || "").trim();
  if (!did || !devices || typeof devices !== "object") return null;
  return devices[did] || null;
}

function buildBatchStreamProgressSnapshot(data, deviceState) {
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  return JSON.stringify({
    done: Number(data?.done_count || 0),
    pending: Number(data?.pending_count || 0),
    total: rows.length,
    device: deviceState
      ? {
          status: String(deviceState.status || ""),
          completed: Number(deviceState.completed_rows || 0),
          total: Number(deviceState.total_rows || 0),
        }
      : null,
    rows: rows.map((row) => [
      Number(row?.sheet_row || 0),
      String(row?.username || ""),
      String(row?.status || ""),
    ]),
  });
}

function renderBatchStreamProgress(data) {
  if (!batchStreamTableBody) return;
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  const target = resolveBatchStreamDeviceSelection();
  const deviceState = resolveBatchStreamDeviceState(data?.devices, target?.device_id);
  const snapshot = buildBatchStreamProgressSnapshot(data, deviceState);
  if (snapshot === batchStreamLastSnapshot) return;
  batchStreamLastSnapshot = snapshot;

  const filteredTotal = rows.length;
  const doneCount = Number(data?.done_count || 0);
  const pendingCount = Number(data?.pending_count || 0);
  const filterAlias = String(data?.filtered_alias || "").trim();
  const summaryParts = [];
  if (filterAlias) {
    summaryParts.push(`别名 ${filterAlias}：已完成 ${doneCount} / ${filteredTotal}，待处理 ${pendingCount}`);
  } else {
    summaryParts.push(`全部账号：已完成 ${doneCount} / ${filteredTotal}，待处理 ${pendingCount}`);
  }
  if (deviceState) {
    const devStatus = String(deviceState.status || "running");
    const devDone = Number(deviceState.completed_rows || 0);
    const devTotal = Number(deviceState.total_rows || 0);
    const statusLabel = devStatus === "done"
      ? "已完成"
      : devStatus === "stopping"
        ? "停止中"
        : devStatus === "running"
          ? "进行中"
          : devStatus;
    summaryParts.push(`设备 ${statusLabel}：${devDone}/${devTotal}`);
  }
  if (batchStreamProgressSummary) {
    batchStreamProgressSummary.textContent = summaryParts.join("；");
  }

  let progressPct = 0;
  if (deviceState && Number(deviceState.total_rows || 0) > 0) {
    progressPct = Math.min(100, Math.round((Number(deviceState.completed_rows || 0) / Number(deviceState.total_rows || 1)) * 100));
  } else if (filteredTotal > 0) {
    progressPct = Math.min(100, Math.round((doneCount / filteredTotal) * 100));
  }
  if (batchStreamProgressBarWrap) {
    batchStreamProgressBarWrap.style.display = filteredTotal > 0 ? "" : "none";
  }
  if (batchStreamProgressBar) {
    batchStreamProgressBar.style.width = `${progressPct}%`;
  }
  if (batchStreamProgressPercent) {
    batchStreamProgressPercent.textContent = filteredTotal > 0 ? `${progressPct}%（${doneCount}/${filteredTotal}）` : "0%";
  }

  if (!rows.length) {
    batchStreamTableBody.innerHTML =
      '<tr><td colspan="5" style="padding:10px;color:#666;">当前筛选下暂无账号行</td></tr>';
    return;
  }

  const firstPendingRow = rows.find((row) => !String(row?.status || "").trim())?.sheet_row;
  batchStreamTableBody.innerHTML = rows
    .map((row) => {
      const sheetRow = Number(row?.sheet_row || 0);
      const username = escapeHtml(String(row?.username || "-"));
      const email = escapeHtml(String(row?.email || "-"));
      const alias = escapeHtml(String(row?.assigned_alias || "-"));
      const status = String(row?.status || "").trim();
      const isDone = Boolean(status);
      const isActive = !isDone && firstPendingRow != null && sheetRow === Number(firstPendingRow);
      const rowClass = isActive ? "batch-stream-row--active" : isDone ? "batch-stream-row--done" : "";
      return `<tr class="${rowClass}">
        <td>${sheetRow || "-"}</td>
        <td>${username}</td>
        <td>${email}</td>
        <td>${alias}</td>
        <td>${batchStreamRowStatusBadge(status)}</td>
      </tr>`;
    })
    .join("");
}

async function pollBatchStreamStatus() {
  if (!batchStreamSessionId || !isBatchStreamSectionActive()) return;
  const target = resolveBatchStreamDeviceSelection();
  const qs = new URLSearchParams({ session_id: batchStreamSessionId });
  if (target?.alias) qs.set("alias", target.alias);
  try {
    const data = await request(`/api/admin/batch_stream/status?${qs.toString()}`);
    if (Number(data?.code) !== 0) return;
    renderBatchStreamProgress(data);
  } catch (_) {
    // 轮询失败时保持静默，避免打断操作。
  }
}

async function uploadBatchStreamXlsx(file) {
  if (!file) return;
  const name = String(file.name || "").toLowerCase();
  if (!name.endsWith(".xlsx")) {
    setStatus("批量带流：请选择 .xlsx 文件", "warning");
    return;
  }
  setBatchStreamUiBusy(true);
  try {
    const token = authToken();
    const form = new FormData();
    form.append("file", file);
    setStatus("正在上传批量带流 Excel...", "info");
    const response = await fetch(await buildApiUrl("/api/admin/batch_stream/upload"), {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: form,
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok || Number(data?.code) !== 0) {
      throw new Error(String(data?.msg || `上传失败 (HTTP ${response.status})`));
    }
    batchStreamSessionId = String(data?.session_id || "").trim();
    const rowCount = Number(data?.row_count || 0);
    const aliasCounts = data?.alias_counts || {};
    const aliasSummary = Object.entries(aliasCounts)
      .map(([alias, count]) => `${alias}:${count}`)
      .join("，");
    updateBatchStreamUploadHint(
      batchStreamSessionId
        ? `已上传 ${file.name}（会话 ${batchStreamSessionId}，共 ${rowCount} 行${aliasSummary ? `；${aliasSummary}` : ""}）`
        : "上传完成",
    );
    if (batchStreamDownloadBtn) batchStreamDownloadBtn.disabled = !batchStreamSessionId;
    batchStreamLastSnapshot = "";
    updateBatchStreamProgressVisibility();
    await pollBatchStreamStatus();
    if (isBatchStreamSectionActive()) {
      scheduleBatchStreamPoll(BATCH_STREAM_POLL_MS);
    }
    setStatus(`批量带流 Excel 已上传：${rowCount} 行`, "success");
  } finally {
    setBatchStreamUiBusy(false);
  }
}

async function startBatchStreamOnDevice() {
  if (batchStreamBusy) return;
  const target = resolveBatchStreamDeviceSelection();
  if (!target?.alias || !target?.device_id) {
    setStatus("请先选择已绑定的设备别名", "warning");
    return;
  }
  if (!batchStreamSessionId) {
    setStatus("请先上传批量带流 Excel", "warning");
    return;
  }
  const hours = Number(batchStreamHoursInput?.value || 0);
  if (!Number.isFinite(hours) || hours <= 0) {
    setStatus("请填写有效的单账号活跃时长（小时）", "warning");
    return;
  }
  const rounds = Math.max(1, Math.min(20, Math.floor(Number(batchStreamRoundsInput?.value || 1))));
  if (!Number.isFinite(rounds) || rounds < 1) {
    setStatus("请填写有效的活跃轮数（1~20）", "warning");
    return;
  }
  setBatchStreamUiBusy(true);
  try {
    setStatus(`正在向 ${target.alias} 下发批量带流...`, "info");
    const data = await request("/api/admin/batch_stream/start", {
      method: "POST",
      body: JSON.stringify({
        session_id: batchStreamSessionId,
        device_id: target.device_id,
        alias: target.alias,
        hours_per_account: hours,
        activity_rounds: rounds,
        package_name: (backupPackageNameInput?.value || "").trim() || "com.zhiliaoapp.musically",
      }),
    });
    if (Number(data?.code) !== 0) {
      throw new Error(String(data?.msg || "下发失败"));
    }
    const totalHours = hours * rounds;
    if (batchStreamStatusHint) {
      batchStreamStatusHint.textContent = rounds > 1
        ? `带流已启动：${target.alias}，${Number(data?.row_count || 0)} 个账号，每轮 ${hours} 小时，共 ${rounds} 轮（累计 ${totalHours} 小时/账号）。设备进入独占模式。`
        : `带流已启动：${target.alias}，${Number(data?.row_count || 0)} 个账号，每台 ${hours} 小时。设备进入独占模式。`;
    }
    batchStreamLastSnapshot = "";
    await pollBatchStreamStatus();
    scheduleBatchStreamPoll(BATCH_STREAM_POLL_MS);
    setStatus(String(data?.msg || "批量带流已启动"), "success");
  } catch (e) {
    setStatus(e.message || "批量带流启动失败", "error");
  } finally {
    setBatchStreamUiBusy(false);
  }
}

async function stopBatchStreamOnDevice() {
  if (batchStreamBusy) return;
  const target = resolveBatchStreamDeviceSelection();
  if (!target?.alias || !target?.device_id) {
    setStatus("请先选择要停止的设备别名", "warning");
    return;
  }
  setBatchStreamUiBusy(true);
  try {
    const data = await request("/api/admin/batch_stream/stop", {
      method: "POST",
      body: JSON.stringify({
        device_id: target.device_id,
        alias: target.alias,
      }),
    });
    if (Number(data?.code) !== 0) {
      throw new Error(String(data?.msg || "停止失败"));
    }
    if (batchStreamStatusHint) {
      batchStreamStatusHint.textContent = `已向 ${target.alias} 下发停止指令，设备将退出带流模式。`;
    }
    batchStreamLastSnapshot = "";
    await pollBatchStreamStatus();
    setStatus(String(data?.msg || "已请求停止批量带流"), "success");
  } catch (e) {
    setStatus(e.message || "停止批量带流失败", "error");
  } finally {
    setBatchStreamUiBusy(false);
  }
}

async function downloadBatchStreamXlsx() {
  if (!batchStreamSessionId || batchStreamBusy) return;
  setBatchStreamUiBusy(true);
  try {
    const token = authToken();
    const url = await buildApiUrl(`/api/admin/batch_stream/download/${encodeURIComponent(batchStreamSessionId)}`);
    const response = await fetch(url, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new Error(String(data?.msg || `下载失败 (HTTP ${response.status})`));
    }
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = objectUrl;
    a.download = `batch_stream_${batchStreamSessionId}.xlsx`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(objectUrl);
    setStatus("批量带流 Excel 已下载", "success");
  } catch (e) {
    setStatus(e.message || "下载 Excel 失败", "error");
  } finally {
    setBatchStreamUiBusy(false);
  }
}

function buildAliasDevicePairs() {
  if (boundDeviceAliasPairs.length) {
    return boundDeviceAliasPairs.slice();
  }
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
  const aliasKey = normalizeAliasKey(alias);
  if (!aliasKey) return "";
  const pairs = buildAliasDevicePairs();
  const hit = pairs.find((item) => normalizeAliasKey(item.device_alias) === aliasKey);
  return hit?.device_id || "";
}

function renderDeviceAliasPresetOptions() {
  const options = buildAliasDevicePairs();
  console.info("[startup-check] renderDeviceAliasPresetOptions", { count: options.length });
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
  renderBatchStreamAliasOptions();
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

const UI_STATE_KEYS = [
  LAST_SECTION_KEY,
  DB_PAGE_KEY,
  DB_SEARCH_FIELD_KEY,
  DB_SEARCH_KEYWORD_KEY,
  CV_PAGE_KEY,
  TIKTOK_ROOTS_PAGE_KEY,
];

function clearUiStateSnapshot() {
  UI_STATE_KEYS.forEach((key) => localStorage.removeItem(key));
}

function saveUiStateSnapshot() {
  if (currentDbPage) localStorage.setItem(DB_PAGE_KEY, String(currentDbPage));
  localStorage.setItem(DB_SEARCH_FIELD_KEY, String(currentDbSearchField || ""));
  localStorage.setItem(DB_SEARCH_KEYWORD_KEY, String(currentDbSearchKeyword || ""));
  if (currentCvPage) localStorage.setItem(CV_PAGE_KEY, String(currentCvPage));
  if (currentTiktokRootsPage) localStorage.setItem(TIKTOK_ROOTS_PAGE_KEY, String(currentTiktokRootsPage));
}

function restoreUiStateSnapshot() {
  clearUiStateSnapshot();
  currentDbPage = 1;
  currentDbSearchField = "";
  currentDbSearchKeyword = "";
  currentCvPage = 1;
  currentTiktokRootsPage = 1;
  currentOverviewPage = Math.max(1, Number(localStorage.getItem(OVERVIEW_PAGE_KEY) || 1) || 1);
  currentBackupArchivePage = Math.max(1, Number(localStorage.getItem(BACKUP_ARCHIVE_PAGE_KEY) || 1) || 1);
  const storedOverviewSize = localStorage.getItem(OVERVIEW_PAGE_SIZE_KEY);
  const storedBackupSize = localStorage.getItem(BACKUP_ARCHIVE_PAGE_SIZE_KEY);
  if (overviewPageSizeInput) {
    overviewPageSizeInput.value = String(clampPageSize(storedOverviewSize, DEFAULT_OVERVIEW_PAGE_SIZE, MAX_OVERVIEW_PAGE_SIZE));
  }
  if (backupArchivePageSizeInput) {
    backupArchivePageSizeInput.value = String(clampPageSize(storedBackupSize, DEFAULT_BACKUP_ARCHIVE_PAGE_SIZE, MAX_BACKUP_ARCHIVE_PAGE_SIZE));
  }
  const storedPcServiceSize = localStorage.getItem(PC_SERVICE_PAGE_SIZE_KEY);
  if (pcServicePageSizeInput) {
    pcServicePageSizeInput.value = String(clampPageSize(storedPcServiceSize, DEFAULT_PC_SERVICE_PAGE_SIZE, MAX_PC_SERVICE_PAGE_SIZE));
  }
  currentPcServicePage = Math.max(1, Number(localStorage.getItem(PC_SERVICE_PAGE_KEY) || 1) || 1);
  syncDeviceGroupFilterSelects(String(localStorage.getItem(DEVICE_GROUP_FILTER_KEY) || "").trim());
  customDeviceGroupNames = loadCustomDeviceGroupNames();
  if (dbSearchFieldSelect) dbSearchFieldSelect.value = "";
  if (dbSearchKeywordInput) dbSearchKeywordInput.value = "";
  if (cvPageSizeEl) cvPageSizeEl.value = String(cvPageSizeEl.defaultValue || cvPageSizeEl.value || 100);
  if (tiktokRootsPageSizeEl) tiktokRootsPageSizeEl.value = String(tiktokRootsPageSizeEl.defaultValue || tiktokRootsPageSizeEl.value || 100);
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

function getAssignableAliasOptions(currentValue = "") {
  const current = normalizeAliasValue(currentValue);
  const merged = collectAssignableAliases(current);
  return [
    '<option value="">无</option>',
    ...merged.map((alias) => `<option value="${escapeHtml(alias)}" ${alias === current ? "selected" : ""}>${escapeHtml(alias)}</option>`),
  ].join("");
}

function isAccountAliasLocked(row = {}) {
  return Boolean(getEffectiveAccountAssignedAlias(row));
}

function isAccountAliasUnassigned(row = {}) {
  return !isAccountAliasLocked(row);
}

function canEditAccountAlias(row = {}) {
  return isAccountAliasUnassigned(row);
}

function loadAccountRecordsCredColsExpanded() {
  try {
    const raw = localStorage.getItem(ACCOUNT_RECORDS_CRED_COLS_STORAGE_KEY);
    if (raw === "1") return true;
    if (raw === "0") return false;
  } catch (_) { /* ignore */ }
  return false;
}

function persistAccountRecordsCredColsExpanded() {
  try {
    localStorage.setItem(
      ACCOUNT_RECORDS_CRED_COLS_STORAGE_KEY,
      accountRecordsCredColsExpanded ? "1" : "0",
    );
  } catch (_) { /* ignore */ }
}

function updateAccountRecordsCredColsToggleBtn() {
  if (!accountRecordsToggleCredColsBtn) return;
  if (accountRecordsCredColsExpanded) {
    accountRecordsToggleCredColsBtn.textContent = "收起凭据列";
    accountRecordsToggleCredColsBtn.title =
      "收起 password / email / email_pwd / 2FA / api 列，为登录、统计等列腾出空间";
  } else {
    accountRecordsToggleCredColsBtn.textContent = "展开凭据列";
    accountRecordsToggleCredColsBtn.title =
      "展开 password / email / email_pwd / 2FA / api 列（默认收起）";
  }
}

function setAccountRecordsCredColsExpanded(expanded) {
  accountRecordsCredColsExpanded = Boolean(expanded);
  persistAccountRecordsCredColsExpanded();
  updateAccountRecordsCredColsToggleBtn();
  renderAccountEditorRows();
}

function toggleAccountRecordsCredCols() {
  setAccountRecordsCredColsExpanded(!accountRecordsCredColsExpanded);
}

function accountEditorCellInput(idx, field, value, extraAttrs = "", inputClass = "") {
  const className = ["account-editor-table__input", inputClass].filter(Boolean).join(" ");
  return `<input class="${className}" type="text" data-editor-scope="account" data-editor-field="${field}" data-editor-index="${idx}" value="${escapeHtml(value)}" title="${field}" ${extraAttrs} />`;
}

async function fetchAccountEditorTwoFaCode(index) {
  const row = accountEditorRows?.[index];
  const inputEl = accountEditorListEl?.querySelector(
    `input[data-editor-scope="account"][data-editor-field="two_fa"][data-editor-index="${index}"]`,
  );
  const secretFromInput = inputEl instanceof HTMLInputElement ? String(inputEl.value || "").trim() : "";
  const secret = secretFromInput || String(row?.two_fa || "").trim();
  const username = String(row?.username || "").trim();
  if (!secret) {
    throw new Error("该行尚未填写 2FA 密钥");
  }
  // 取码前把输入框当前值写回内存行，避免刚改未触发保存时用到旧密钥
  if (row && secretFromInput) row.two_fa = secretFromInput;
  const data = await request("/api/admin/two_fa/code", {
    method: "POST",
    body: JSON.stringify({ secret, username }),
  });
  const code = String(data?.two_fa_code || "").trim();
  const remaining = Number(data?.remaining_sec);
  if (!/^\d{6,8}$/.test(code)) {
    throw new Error("服务端未返回有效 2FA 验证码");
  }
  try {
    await navigator.clipboard.writeText(code);
  } catch (_) {
    /* ignore clipboard failures */
  }
  const remText = Number.isFinite(remaining) ? `，剩余 ${remaining}s` : "";
  setStatus(`${username || "账号"} 2FA：${code}${remText}（已复制）`, "success");
}

function accountEditorCellSelect(idx, field, innerHtml, cellClass = "") {
  const className = ["account-editor-table__input", cellClass].filter(Boolean).join(" ");
  return `<select class="${className}" data-editor-scope="account" data-editor-field="${field}" data-editor-index="${idx}" title="${field}">${innerHtml}</select>`;
}

function formatAccountGrowthTriple(row = {}, prefix = "follower") {
  const dayBefore = String(row[`${prefix}_growth_day_before`] ?? "").trim();
  const yesterday = String(row[`${prefix}_growth_yesterday`] ?? "").trim();
  let today = String(row[`${prefix}_growth_today`] ?? "").trim();
  if (!today && prefix === "follower") {
    today = String(row.follower_growth ?? "").trim();
  }
  const fmt = (label, value) => {
    const text = value === "" ? "-" : value;
    return `<span class="account-growth-triple__item"><em>${label}</em>${escapeHtml(text)}</span>`;
  };
  return `<div class="account-growth-triple" title="${prefix === "follower" ? "粉丝" : "总赞"}增长（前天/昨天/今天）">${fmt("前", dayBefore)}${fmt("昨", yesterday)}${fmt("今", today)}</div>`;
}

function renderAccountEditorRows() {
  if (!accountEditorListEl) return;
  const existingRowIndexes = new Set(
    (accountEditorRows || []).map((row, idx) => resolveAccountRowIndex(row, idx)),
  );
  accountRecordsSelectedRowIndexes.forEach((ri) => {
    if (!existingRowIndexes.has(ri)) accountRecordsSelectedRowIndexes.delete(ri);
  });
  updateAccountRecordsBatchDeleteBtn();
  const indexedRows = (accountEditorRows || []).map((row, idx) => ({ row, idx }));
  let visibleRows = accountFilterUnassignedOnly
    ? indexedRows.filter(({ row }) => isAccountAliasUnassigned(row))
    : indexedRows;
  if (accountRecordsSearchQuery) {
    visibleRows = visibleRows.filter(({ row }) => accountRecordMatchesSearch(row));
  }
  accountRecordsFilteredTotal = visibleRows.length;
  updateAccountRecordsPagerUi();
  const pageSize = getAccountRecordsPageSize();
  const totalPages = Math.max(1, Math.ceil(accountRecordsFilteredTotal / pageSize));
  const start = (currentAccountRecordsPage - 1) * pageSize;
  const pageRows = visibleRows.slice(start, start + pageSize);
  if (!visibleRows.length) {
    const emptyHint = accountRecordsSearchQuery
      ? formatAccountRecordsSearchHint(accountRecordsSearchQuery, accountRecordsSearchField)
      : "暂无记录，点击“新增空白记录”开始";
    accountEditorListEl.innerHTML = `<div class="hint">${escapeHtml(emptyHint)}</div>`;
    return;
  }
  try {
    const credExpanded = accountRecordsCredColsExpanded;
    const tableClass = credExpanded
      ? "account-editor-table"
      : "account-editor-table account-editor-table--creds-collapsed";
    const bodyHtml = pageRows.map(({ row, idx }, localIdx) => {
      const aliasLocked = isAccountAliasLocked(row);
      const aliasCell = aliasLocked
        ? `<span class="account-editor-table__alias-locked" title="已分配别名不可改；删除记录将同步删除备份档案">${escapeHtml(getEffectiveAccountAssignedAlias(row))}</span>`
        : accountEditorCellSelect(idx, "assigned_alias", getAssignableAliasOptions(getEffectiveAccountAssignedAlias(row)), "account-editor-table__alias-select");
      const loginSelect = accountEditorCellSelect(
        idx,
        "is_login",
        `<option value="false" ${row.is_login ? "" : "selected"}>否</option><option value="true" ${row.is_login ? "selected" : ""}>是</option>`,
        "account-editor-table__login-select",
      );
      const profileSelect = accountEditorCellSelect(
        idx,
        "basic_profile_modified",
        `<option value="否" ${normalizeBasicProfileModifiedField(row.basic_profile_modified) === "否" ? "selected" : ""}>否</option><option value="是" ${normalizeBasicProfileModifiedField(row.basic_profile_modified) === "是" ? "selected" : ""}>是</option>`,
        "account-editor-table__profile-select",
      );
      const displayNum = start + localIdx + 1;
      const dbRowIndex = resolveAccountRowIndex(row, idx);
      const rowChecked = accountRecordsSelectedRowIndexes.has(dbRowIndex);
      return `<tr>
        <td class="account-editor-table__select">
          <input type="checkbox" class="account-records-row-select" data-account-row-index="${dbRowIndex}" title="勾选以批量操作"${rowChecked ? " checked" : ""} />
        </td>
        <td class="account-editor-table__num">${displayNum}</td>
        <td class="account-editor-table__username">${accountEditorCellInput(idx, "username", row.username)}</td>
        <td class="account-editor-table__cred-col account-editor-table__cred-col--password">${accountEditorCellInput(idx, "password", row.password)}</td>
        <td class="account-editor-table__cred-col account-editor-table__cred-col--email">${accountEditorCellInput(idx, "email", row.email)}</td>
        <td class="account-editor-table__cred-col account-editor-table__cred-col--email-pwd">${accountEditorCellInput(idx, "email_password", row.email_password)}</td>
        <td class="account-editor-table__cred-col account-editor-table__cred-col--two-fa">
          <div class="account-editor-two-fa-cell">
            ${accountEditorCellInput(idx, "two_fa", row.two_fa)}
            <button type="button" class="ghost account-editor-two-fa-code-btn" data-editor-index="${idx}" title="本地生成当前 TOTP">取码</button>
          </div>
        </td>
        <td class="account-editor-table__cred-col account-editor-table__cred-col--api">${accountEditorCellInput(idx, "api", row.api)}</td>
        <td class="account-editor-table__login">${loginSelect}</td>
        <td class="account-editor-table__login-time">${accountEditorCellInput(idx, "login_time", row.login_time, "", "account-editor-table__login-time-input")}</td>
        <td class="account-editor-table__region">${accountEditorCellInput(idx, "region", row.region)}</td>
        <td class="account-editor-table__alias">${aliasCell}</td>
        <td class="account-editor-table__stat">${accountEditorCellInput(idx, "video_count", row.video_count, 'inputmode="numeric"')}</td>
        <td class="account-editor-table__stat">${accountEditorCellInput(idx, "follower_count", row.follower_count, 'inputmode="numeric"')}</td>
        <td class="account-editor-table__stat">${accountEditorCellInput(idx, "heart_count", row.heart_count, 'inputmode="numeric"')}</td>
        <td class="account-editor-table__growth">${formatAccountGrowthTriple(row, "follower")}</td>
        <td class="account-editor-table__growth">${formatAccountGrowthTriple(row, "heart")}</td>
        <td class="account-editor-table__profile">${profileSelect}</td>
        <td class="account-editor-table__actions"><button type="button" class="ghost account-editor-table__del" data-editor-delete="account" data-editor-index="${idx}">删</button></td>
      </tr>`;
    }).join("");
    accountEditorListEl.innerHTML = `
      <div class="account-editor-table-wrap${credExpanded ? "" : " account-editor-table-wrap--creds-collapsed"}">
        <table class="${tableClass}">
          <thead>
            <tr>
              <th class="account-editor-table__select">
                <input type="checkbox" class="account-records-select-all" title="全选本页" />
              </th>
              <th class="account-editor-table__num">#</th>
              <th class="account-editor-table__username">username</th>
              <th class="account-editor-table__cred-col account-editor-table__cred-col--password">password</th>
              <th class="account-editor-table__cred-col account-editor-table__cred-col--email">email</th>
              <th class="account-editor-table__cred-col account-editor-table__cred-col--email-pwd">email_pwd</th>
              <th class="account-editor-table__cred-col account-editor-table__cred-col--two-fa">2FA</th>
              <th class="account-editor-table__cred-col account-editor-table__cred-col--api">api</th>
              <th class="account-editor-table__login">登录</th>
              <th class="account-editor-table__login-time">登录时间</th>
              <th class="account-editor-table__region">地区</th>
              <th class="account-editor-table__alias">别名</th>
              <th class="account-editor-table__stat">视频</th>
              <th class="account-editor-table__stat">粉丝</th>
              <th class="account-editor-table__stat">总赞</th>
              <th class="account-editor-table__growth">粉丝增长</th>
              <th class="account-editor-table__growth">总赞增长</th>
              <th class="account-editor-table__profile">资料</th>
              <th class="account-editor-table__actions"></th>
            </tr>
          </thead>
          <tbody>${bodyHtml}</tbody>
        </table>
      </div>`;
    updateAccountRecordsCredColsToggleBtn();
    syncAccountRecordsPageSelectAllCheckbox();
  } catch (error) {
    console.error("renderAccountEditorRows failed", error);
    const msg = (error && (error.message || error.toString?.())) || "unknown";
    accountEditorListEl.innerHTML = `<div class="hint">账号记录渲染失败：${escapeHtml(msg)}</div>`;
  }
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

function resolveBackupRowStats(row = {}) {
  const username = String(row?.username || "").trim();
  const acct = username ? getAccountRecordByUsername(username) : null;
  const pick = (field) => {
    const fromRow = String(row?.[field] ?? "").trim();
    if (fromRow) return fromRow;
    const fromAccount = String(acct?.[field] ?? "").trim();
    return fromAccount || "-";
  };
  return {
    video_count: pick("video_count"),
    follower_count: pick("follower_count"),
    follower_growth: pick("follower_growth"),
  };
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
  const stats = resolveBackupRowStats({ ...matched, ...row, username });
  return `
    <div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px 12px;font-size:12px;color:#334155;">
      <div><strong>username：</strong>${matched.username || "-"}</div>
      <div><strong>password：</strong>${matched.password || "-"}</div>
      <div><strong>email：</strong>${matched.email || "-"}</div>
      <div><strong>email_password：</strong>${matched.email_password || "-"}</div>
      <div><strong>2FA：</strong>${matched.two_fa || "-"}</div>
      <div><strong>api：</strong>${renderClickableValue(matched.api)}</div>
      <div><strong>is_login：</strong>${matched.is_login ? "是" : "否"}</div>
      <div><strong>login_time：</strong>${matched.login_time || "-"}</div>
      <div><strong>region：</strong>${matched.region || "-"}</div>
      <div><strong>分配别名：</strong>${normalizeAliasValue(getEffectiveAccountAssignedAlias(matched)) || "无"}</div>
      <div><strong>video_count：</strong>${stats.video_count}</div>
      <div><strong>follower_count：</strong>${stats.follower_count}</div>
      <div><strong>follower_growth：</strong>${stats.follower_growth}</div>
      <div><strong>基本资料已修改：</strong>${normalizeBasicProfileModifiedField(matched.basic_profile_modified) === "是" ? "是" : "否"}</div>
    </div>
  `;
}

function renderDeviceEditorRows() {
  if (!deviceAliasGroupsEl) return;
  renderDeviceAliasPresetOptions();
  // 备份档案页用分页接口渲染；勿用本地全量 deviceEditorRows 覆盖。
  if (document.getElementById("backup-section")?.classList.contains("active")) {
    void loadBackupArchiveRecords();
    return;
  }
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
      const username = String(row?.username || "").trim();
      const acct = username ? getAccountRecordByUsername(username) : null;
      const switchActive = items.length <= 1 || normalizeSwitchHighlightField(row?.switch_highlight) === "是";
      const envStatusBadge = renderBackupArchiveEnvStatusBadge(switchActive, acct, row);
      const envHintText = switchActive
        ? "当前高亮：该账号是这条设备环境的归属目标"
        : "非高亮：需切号后才可操作备份/还原";
      return `
      <div class="panel-card" style="margin:0 0 10px 0;background:#fff;border:1px solid #eef2ff;">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px;">
          <strong>记录 #${idx + 1}</strong>
          <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;justify-content:flex-end;">
            <span class="hint">device_id：${row.device_id || "-"}</span>
            <button class="ghost" data-action="alias-clear" data-device-alias="${row.device_alias || ""}" data-device-id="${row.device_id || ""}">清理</button>
            <button class="ghost" data-account-backup="${row.device_alias || ""}" data-device-id="${row.device_id || ""}" data-account-username="${row.username || ""}" data-account-id="${row.row_index != null && row.row_index !== "" ? row.row_index : ""}">备份</button>
            <button class="ghost" data-account-restore="${row.device_alias || ""}" data-device-id="${row.device_id || ""}" data-account-backup-name="${row.backup_name || ""}" data-account-id="${row.row_index != null && row.row_index !== "" ? row.row_index : ""}">清理并还原该备份</button>
            <button class="ghost" data-editor-delete="device" data-editor-index="${idx}">删除</button>
          </div>
        </div>
        <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:8px 0 2px;">
          ${envStatusBadge}
          <span class="hint">${escapeHtml(envHintText)}</span>
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
    const preservedAlias = getEffectiveAccountAssignedAlias(row);
    if (field === "username") {
      row[field] = value;
    } else {
      row[field] = field === "is_login" ? (String(value) === "true" || checked) : value;
    }
    if (preservedAlias) row.assigned_alias = preservedAlias;
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
  const [rowIndex] = allocateAccountRowIndexes(1);
  accountEditorRows.push(getAccountRowTemplate({ row_index: rowIndex, video_count: "-1" }));
  const index = accountEditorRows.length - 1;
  await saveAccountRow(index);
}

function isAccountLoggedIn(row = {}) {
  const raw = row?.is_login;
  return raw === true || raw === 1 || raw === "1" || raw === "true";
}

function getDeviceAssignCount() {
  const raw = Number(deviceAssignCountInput?.value ?? DEFAULT_DEVICE_ASSIGN_COUNT);
  if (!Number.isFinite(raw) || raw < 1) return DEFAULT_DEVICE_ASSIGN_COUNT;
  return Math.min(Math.floor(raw), MAX_DEVICE_ASSIGN_COUNT);
}

function getMaxDeviceRowIndex() {
  return (deviceEditorRows || []).reduce((max, row) => {
    const ri = Number(row?.row_index ?? row?.id);
    return Number.isFinite(ri) ? Math.max(max, ri) : max;
  }, -1);
}

function allocateDeviceRowIndexes(count) {
  const safeCount = Math.max(1, Math.floor(Number(count) || 1));
  const start = getMaxDeviceRowIndex() + 1;
  return Array.from({ length: safeCount }, (_, i) => start + i);
}

function pickUnloggedAccountsByRegion(region, limit = 1) {
  const normalizedRegion = String(region || "").trim();
  const maxCount = Math.max(1, Math.floor(Number(limit) || 1));
  if (!normalizedRegion || maxCount < 1) return [];
  const picked = [];
  for (const row of accountEditorRows || []) {
    if (picked.length >= maxCount) break;
    const accountRegion = String(row?.region || "").trim();
    const username = String(row?.username || "").trim();
    if (!username) continue;
    if (accountRegion !== normalizedRegion || isAccountLoggedIn(row)) continue;
    if (!isAccountAliasUnassigned(row)) continue;
    if (isUsernameUsedElsewhere(username, -1)) continue;
    const normalizedUsername = username.toLowerCase();
    if (picked.some((item) => String(item?.username || "").trim().toLowerCase() === normalizedUsername)) continue;
    picked.push(row);
  }
  return picked;
}

async function addDeviceBlankEditorRow(aliasOverride = "") {
  await loadEditorState();
  renderDeviceRegionPresetOptions();
  renderDeviceAliasPresetOptions();
  const selectedRegion = String(deviceRegionPresetSelect?.value || "").trim();
  if (!selectedRegion) {
    throw new Error("请先选择国家地区后再新增记录");
  }
  const assignCount = getDeviceAssignCount();
  if (deviceAssignCountInput) {
    deviceAssignCountInput.value = String(assignCount);
  }
  const matchedAccounts = pickUnloggedAccountsByRegion(selectedRegion, assignCount);
  if (!matchedAccounts.length) {
    throw new Error(`新增失败：账号表单中没有地区为「${selectedRegion}」、未登录且未分配的可用账号`);
  }
  const selectedAlias = String(aliasOverride || deviceAliasPresetSelect?.value || "").trim();
  const inferredDeviceId = resolveDeviceIdByAlias(selectedAlias);
  const newRowIndexes = allocateDeviceRowIndexes(matchedAccounts.length);
  const startIndex = deviceEditorRows.length;
  const newRows = matchedAccounts.map((matchedAccount, i) => getDeviceRowTemplate({
    row_index: newRowIndexes[i],
    device_alias: selectedAlias,
    device_id: inferredDeviceId,
    region: selectedRegion,
    username: String(matchedAccount.username || "").trim(),
  }));
  deviceEditorRows.push(...newRows);
  try {
    for (let i = 0; i < newRows.length; i += 1) {
      await saveDeviceRow(startIndex + i, { skipReload: true, rowIndex: newRowIndexes[i] });
    }
    await loadEditorState();
    renderAccountEditorRows();
    renderDeviceEditorRows();
    const backupActive = document.getElementById("backup-section")?.classList.contains("active");
    if (backupActive) {
      await loadBackupArchiveRecords();
    }
    const aliasHint = selectedAlias ? `，别名 ${selectedAlias}` : "";
    if (matchedAccounts.length < assignCount) {
      setStatus(
        `已分配 ${matchedAccounts.length} 条（请求 ${assignCount} 条，可用账号不足）${aliasHint}`,
        "warning",
      );
    } else {
      setStatus(`已成功分配 ${matchedAccounts.length} 条账号到设备档案${aliasHint}`, "success");
    }
  } catch (error) {
    deviceEditorRows.splice(startIndex, newRows.length);
    renderDeviceEditorRows();
    throw error;
  }
}

async function handleAddDeviceBlankRecordClick(aliasOverride = "") {
  if (!addDeviceBlankRecordBtn) return;
  if (addDeviceBlankRecordBtn.disabled) return;
  const previousLabel = addDeviceBlankRecordBtn.textContent;
  addDeviceBlankRecordBtn.disabled = true;
  addDeviceBlankRecordBtn.textContent = "分配中...";
  setStatus("正在分配账号到设备档案...", "info");
  try {
    await addDeviceBlankEditorRow(aliasOverride);
  } catch (error) {
    const message = humanizeRequestErrorMessage(error) || "新增失败";
    setStatus(message, "error");
    window.alert(message);
  } finally {
    addDeviceBlankRecordBtn.disabled = false;
    addDeviceBlankRecordBtn.textContent = previousLabel || "新增并分配记录";
  }
}

async function deleteEditorRow(scope, index) {
  if (scope === "account") {
    const deletedRow = accountEditorRows[index];
    if (!deletedRow) return;
    const payload = buildAccountRecordDeletePayload(deletedRow, index);
    const deletedUsername = payload.username;
    const deletedAlias = payload.assigned_alias;
    const confirmLines = ["确认删除该账号记录？"];
    if (deletedUsername) confirmLines.push(`账号：${deletedUsername}`);
    if (deletedAlias) {
      confirmLines.push(`将同时删除设备备份档案中该账号的数据（别名：${deletedAlias}）`);
    }
    if (!window.confirm(confirmLines.join("\n"))) return;
    try {
      const resp = await request(`${ACCOUNT_ROWS_API}/delete`, {
        method: "POST",
        body: JSON.stringify(payload),
      });
      const cleanedBackup = Number(resp?.deleted_device_rows || 0);
      accountRecordsSelectedRowIndexes.delete(payload.row_index);
      updateAccountRecordsBatchDeleteBtn();
      await refreshEditorTablesAfterAccountDelete();
      if (deletedUsername && cleanedBackup > 0) {
        setStatus(`已删除账号 ${deletedUsername}，并同步清理 ${cleanedBackup} 条设备备份档案`, "success");
      } else if (deletedUsername) {
        setStatus(`已删除账号 ${deletedUsername}`, "success");
      } else {
        setStatus("账号记录已删除", "success");
      }
    } catch (error) {
      await refreshEditorTablesAfterAccountDelete();
      throw error;
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
      <tr data-account-id="${row.row_index ?? row.id ?? ""}" data-account-username="${row.username ?? ""}" data-account-backup-name="${row.backup_name ?? ""}">
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
          <button class="ghost" data-account-backup="${alias}" data-account-username="${row.username ?? ""}" data-account-id="${row.row_index ?? row.id ?? ""}">备份并清理</button>
          <button class="ghost" data-account-restore="${alias}" data-account-backup-name="${row.backup_name ?? ""}" data-account-id="${row.row_index ?? row.id ?? ""}">清理并还原该备份</button>
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
            <button class="ghost" data-action="alias-clear" data-device-alias="${alias}">清理TikTok数据</button>
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

function isMeaningfulBackupFieldValue(value) {
  const text = String(value ?? "").trim();
  return Boolean(text && text !== "-");
}

function buildBackupArchiveExtraSummary(stats = {}, backupName = "", backupPath = "") {
  const parts = [];
  if (isMeaningfulBackupFieldValue(stats.follower_count)) parts.push(`粉丝 ${stats.follower_count}`);
  if (isMeaningfulBackupFieldValue(stats.follower_growth)) parts.push(`增长 ${stats.follower_growth}`);
  if (isMeaningfulBackupFieldValue(stats.video_count)) parts.push(`视频 ${stats.video_count}`);
  if (isMeaningfulBackupFieldValue(backupName)) {
    const shortName = backupName.length > 28 ? `${backupName.slice(0, 28)}…` : backupName;
    parts.push(`备份 ${shortName}`);
  } else if (isMeaningfulBackupFieldValue(backupPath)) {
    parts.push("已有备份路径");
  }
  return parts.length ? parts.join(" · ") : "数据统计与备份信息";
}

function resolveBackupRowDailySlot(alias, row) {
  const info = resolveBackupRowSlotInfo(alias, row);
  return info?.label || "";
}

function resolveBackupRowSlotInfo(alias, row) {
  const aliasKey = resolveBackupArchiveAliasKey(alias, row);
  if (!aliasKey) return null;
  const bucket = autoSwitchDailyPlansByAlias[aliasKey];
  if (!bucket) return null;
  const ri = Number(row?.row_index ?? row?.id);
  let hit = null;
  if (Number.isFinite(ri) && bucket.slots_by_row_index) {
    hit = bucket.slots_by_row_index[String(ri)] ?? bucket.slots_by_row_index[ri];
  }
  if (!hit) {
    const user = String(row?.username || "").trim().toLowerCase();
    if (user && bucket.slots_by_username?.[user]) {
      hit = bucket.slots_by_username[user];
    }
  }
  if (!hit) return null;
  return {
    label: hit.label ? String(hit.label) : "",
    publishAt: hit.auto_publish_label ? String(hit.auto_publish_label) : "",
    publishStatus: String(hit.auto_publish_status || "none"),
    publishStatusLabel: String(hit.auto_publish_status_label || ""),
    publishStatusTitle: String(hit.auto_publish_status_title || ""),
  };
}

function renderAutoSwitchPublishStatusBadge(slotInfo) {
  if (!slotInfo) return "";
  const status = String(slotInfo.publishStatus || "none");
  if (status === "none") return "";
  const label = String(slotInfo.publishStatusLabel || "").trim() || status;
  const title = String(slotInfo.publishStatusTitle || "").trim()
    || (slotInfo.publishAt ? `计划发作品 ${slotInfo.publishAt}` : "自动切号发作品状态");
  const cls = `backup-archive-publish-badge backup-archive-publish-badge--${status}`;
  return `<span class="${cls}" title="${escapeHtml(title)}">发作品·${escapeHtml(label)}</span>`;
}

function renderAutoSwitchDeviceBadge(alias, rowDeviceId = "") {
  const deviceId = resolveDeviceIdForArchiveAlias(alias, rowDeviceId);
  if (!deviceId) return "";
  const on = isAutoSwitchEnabledForDeviceId(deviceId);
  if (on) {
    return '<span class="badge badge--ok backup-archive-auto-switch-badge" title="该设备已开启自动切号自治，手动切号/清理等操作已锁定">自动切号·开</span>';
  }
  return '<span class="badge backup-archive-auto-switch-badge backup-archive-auto-switch-badge--off" title="该设备未开启自动切号，可手动操作或勾选别名组后批量开启">自动切号·关</span>';
}

function renderBackupArchiveEnvStatusBadge(switchActive, acct, row = {}, options = {}) {
  if (!switchActive) {
    return `<span class="backup-archive-env-badge backup-archive-env-badge--idle" title="该账号不是此设备别名的当前环境归属；需先切号后才可操作备份/还原等">非当前环境</span>`;
  }
  const slotDriven = Boolean(options?.slotDriven);
  const envMeta = resolveBackupArchiveEnvRuntimeMeta(row?.env_status);
  const loginKnown = Boolean(acct);
  const loginOk = loginKnown && Boolean(acct.is_login);
  const loginLabel = loginKnown ? (loginOk ? "已登录" : "未登录") : "登录未知";
  const loginClass = loginOk
    ? "backup-archive-env-badge--logged-in"
    : (loginKnown ? "backup-archive-env-badge--logged-out" : "backup-archive-env-badge--unknown");
  const runtimeLabel = envMeta?.label || "";
  const runtimeClass = envMeta?.className || "";
  const runtimeTitle = envMeta?.title || "环境就绪状态未知（可能尚未执行切号/还原）";
  const textParts = [slotDriven ? "时段归属" : "环境归属"];
  if (runtimeLabel) textParts.push(runtimeLabel);
  textParts.push(loginLabel);
  const titleParts = [
    slotDriven
      ? "时段归属=按云端今日计划当前时段应在设备内的账号（与 switch_highlight 无关）"
      : "高亮=该设备别名当前环境归属此账号",
    runtimeLabel ? runtimeTitle : "尚未记录环境就绪状态",
    "登录状态来自账号 is_login（环境内结果，失败不回滚归属）",
  ];
  return `<span class="backup-archive-env-badge backup-archive-env-badge--highlighted ${runtimeClass} ${loginClass}" title="${escapeHtml(titleParts.join("；"))}">${escapeHtml(textParts.join(" · "))}</span>`;
}

/** 同别名组：默认只展示高亮记录，其余放入折叠区 */
function partitionBackupArchiveGroupRows(rows = [], alias = "") {
  if (!Array.isArray(rows) || rows.length <= 1) {
    return { primary: rows || [], secondary: [] };
  }
  const highlighted = rows.filter((row) => isBackupArchiveRowEffectivelyHighlighted(rows, row, alias));
  if (highlighted.length === 1) {
    return {
      primary: highlighted,
      secondary: rows.filter((row) => !isBackupArchiveRowEffectivelyHighlighted(rows, row, alias)),
    };
  }
  return { primary: [rows[0]], secondary: rows.slice(1) };
}

function renderSingleBackupArchiveRecordCard(row, rows, idx, alias) {
  const username = String(row?.username || "").trim();
  const displayAlias = resolveBackupArchiveDisplayAlias(row);
  const mobileAlias = String(row?.mobile_alias || row?.device_alias || "").trim();
  const accountAlias = normalizeAliasValue(row?.account_assigned_alias)
    || (username ? normalizeAliasValue(getAccountRecordByUsername(username)?.assigned_alias) : "");
  const aliasBadge = displayAlias
    ? `<span class="backup-archive-record__alias-badge" title="分配别名">${escapeHtml(displayAlias)}</span>`
    : (accountAlias ? `<span class="backup-archive-record__alias-badge backup-archive-record__alias-badge--account" title="账号记录中的分配别名">${escapeHtml(accountAlias)}</span>` : "");
  const backupName = String(row?.backup_name || "").trim();
  const backupPath = String(row?.backup_path || "").trim();
  const lastHighlightAt = String(row?.updated_at || row?.created_at || "").trim();
  const backupRecordId = Number(row?.row_index ?? row?.id ?? 0);
  const deviceRowIndex = Number(row?.row_index);
  const deviceId = String(row?.device_id || "").trim();
  const actionAlias = displayAlias || (alias === "__EMPTY_ALIAS__" ? mobileAlias : alias);
  const linkedAccountInfo = renderLinkedAccountInfo({ username, ...row });
  const stats = resolveBackupRowStats(row);
  const switchHl = normalizeSwitchHighlightField(row?.switch_highlight);
  const switchActive = isBackupArchiveRowEffectivelyHighlighted(rows, row, alias);
  const slotDrivenHighlight = isBackupArchiveRowCurrentSlotOwner(rows, row, alias);
  const acct = username ? getAccountRecordByUsername(username) : null;
  const showBasicProfileBtn = Boolean(acct) && normalizeBasicProfileModifiedField(acct.basic_profile_modified) === "否";
  const autoSwitchBlocksManual = isAutoSwitchEnabledForArchiveAlias(actionAlias, deviceId);
  const switchDisabled = autoSwitchBlocksManual ? " disabled" : "";
  const switchTitle = autoSwitchBlocksManual
    ? "已开启自动切号（全局），请按系统时段安排切号，不可手动切号"
    : "切号：确认后立即切换环境归属高亮；有高亮时先备份当前再还原，无高亮时仅清理后还原";
  const manualBlockTitle = autoSwitchBlocksManual ? AUTO_SWITCH_MANUAL_BLOCK_HINT : "";
  const otherDisabled = autoSwitchBlocksManual || !switchActive ? " disabled" : "";
  const otherTitle = autoSwitchBlocksManual
    ? manualBlockTitle
    : (switchActive ? "" : "需先切号为当前高亮账号后才可操作");
  const envStatusBadge = renderBackupArchiveEnvStatusBadge(switchActive, acct, row, { slotDriven: slotDrivenHighlight });
  const autoSwitchBadge = renderAutoSwitchDeviceBadge(actionAlias, deviceId);
  const envHintText = switchActive
    ? (slotDrivenHighlight
      ? "当前时段归属：云端计划内该账号应在设备环境内"
      : "当前高亮：该账号是这条设备环境的归属目标")
    : (autoSwitchBlocksManual
      ? "非当前时段：设备将在对应时段自动切号"
      : "非高亮：该账号不是当前设备环境归属目标，按钮仅在切号后启用");
  const exportRowIndex = resolveBackupArchiveExportRowIndex(row);
  const rowIndexNum = exportRowIndex ?? -1;
  const hasCutSwitchRow = exportRowIndex != null;
  const dailySlotInfo = resolveBackupRowSlotInfo(alias, row);
  const dailyPublishBadge = rows.length >= 2 ? renderAutoSwitchPublishStatusBadge(dailySlotInfo) : "";
  const autoSwitchExcluded = Boolean(row?.auto_switch_excluded);
  const autoSwitchExcludedBadge = autoSwitchExcluded
    ? '<span class="badge backup-archive-auto-switch-badge backup-archive-auto-switch-badge--off" title="该账号已设置为不参加自动切号">不参加自动切号</span>'
    : "";
  const extraSummary = buildBackupArchiveExtraSummary(stats, backupName, backupPath);
  const exportChecked = exportRowIndex != null && backupArchiveSelectedRowIndices.has(exportRowIndex);
  const showRecordSelect = rows.length > 1;
  const exportSelectTitle = exportRowIndex != null
    ? "勾选后可导出 / 参与自动切号批量操作"
    : "该记录缺少 row_index，暂无法参与导出";
  const recordSelectHtml = showRecordSelect
    ? `<label class="backup-archive-record__select" title="${escapeHtml(exportSelectTitle)}" onclick="event.stopPropagation()">
                <input type="checkbox" class="backup-archive-export-select" data-row-index="${exportRowIndex != null ? exportRowIndex : ""}"${exportRowIndex == null ? " disabled" : ""}${exportChecked ? " checked" : ""} />
              </label>`
    : "";
  return `
        <div class="panel-card backup-archive-record${switchActive ? " backup-archive-record--active" : ""}" data-backup-row-index="${hasCutSwitchRow ? rowIndexNum : ""}" data-switch-hl="${escapeHtml(switchHl)}" data-bucket-size="${rows.length}" data-row-username="${escapeHtml(username)}" data-row-backup-name="${escapeHtml(backupName)}" data-row-backup-path="${escapeHtml(backupPath)}" data-row-device-id="${escapeHtml(deviceId)}" data-action-alias="${escapeHtml(actionAlias)}">
          <div class="backup-archive-record__head">
            <div class="backup-archive-record__title">
              ${recordSelectHtml}
              <strong class="backup-archive-record__index">记录 #${idx + 1}</strong>
              ${aliasBadge}
              ${autoSwitchBadge}
              ${autoSwitchExcludedBadge}
              ${envStatusBadge}
              ${dailyPublishBadge}
              <span class="backup-archive-record__username">${escapeHtml(username || "-")}</span>
              <span class="hint backup-archive-record__meta">${escapeHtml(mobileAlias || "-")}${mobileAlias && displayAlias && mobileAlias.toLowerCase() !== displayAlias.toLowerCase() ? ` · 设备 ${escapeHtml(displayAlias)}` : ""} · 上次高亮 ${escapeHtml(lastHighlightAt || "-")}${switchActive ? ` · ${escapeHtml(envHintText)}` : ""}</span>
            </div>
            <div class="backup-archive-record__actions">
              <button type="button" class="ghost" title="删除该账号与别名的分配关系（无需高亮）" data-account-unassign="1" data-account-username="${escapeHtml(username)}" data-account-alias="${escapeHtml(actionAlias)}" data-device-row-index="${Number.isFinite(deviceRowIndex) ? deviceRowIndex : ""}">删除分配</button>
              <button type="button" class="ghost" title="${escapeHtml(autoSwitchExcluded ? "恢复参加自动切号" : "将该账号从自动切号计划中排除")}" data-account-auto-switch-excluded="1" data-account-id="${backupRecordId || ""}" data-account-excluded="${autoSwitchExcluded ? "1" : "0"}">${autoSwitchExcluded ? "参加自动切号" : "不参加自动切号"}</button>
              <button type="button" class="ghost"${otherDisabled} title="${escapeHtml(otherTitle || "仅清理设备内 TikTok 数据，不做备份")}" data-action="alias-clear" data-device-alias="${escapeHtml(actionAlias)}" data-device-id="${escapeHtml(deviceId)}">清理TikTok数据</button>
              <button type="button" class="ghost"${otherDisabled} title="${escapeHtml(otherTitle || "先备份，再清理设备内 TikTok 数据")}" data-account-backup="${escapeHtml(actionAlias)}" data-device-id="${escapeHtml(deviceId)}" data-account-id="${backupRecordId || ""}" data-account-username="${escapeHtml(username)}">备份并清理</button>
              <button type="button" class="ghost"${otherDisabled} title="${escapeHtml(otherTitle || "先 pm clear 清理 TikTok 数据，再从该条记录的 tar 解压还原")}" data-account-restore="${escapeHtml(actionAlias)}" data-account-backup-name="${escapeHtml(backupName)}">清理并还原该备份</button>
              ${showBasicProfileBtn ? `<button type="button" class="ghost"${otherDisabled} title="${escapeHtml(otherTitle || "高亮条目可点：下发后在设备完成修改链路")}" data-basic-profile-edit="1">修改账号基本资料</button>` : ""}
              <button type="button" class="ghost${switchActive ? " backup-archive-switch-btn--active" : ""}"${switchDisabled} title="${escapeHtml(switchTitle)}" data-account-switch="1">切号</button>
            </div>
          </div>
          <details class="backup-archive-record-extra">
            <summary class="backup-archive-record-extra__summary" title="点击展开/收起">${escapeHtml(extraSummary)}</summary>
            <div class="backup-archive-record-extra__body">
              <div class="backup-archive-record-extra__grid backup-archive-record-extra__grid--stats">
                <div><label>video_count</label><div>${escapeHtml(stats.video_count)}</div></div>
                <div><label>follower_count</label><div><strong>${escapeHtml(stats.follower_count)}</strong></div></div>
                <div><label>follower_growth</label><div><strong>${escapeHtml(stats.follower_growth)}</strong></div></div>
              </div>
              <div class="backup-archive-record-extra__grid backup-archive-record-extra__grid--backup">
                <div class="backup-archive-record-extra__field backup-archive-record-extra__field--wide">
                  <label>backup_name</label>
                  <div title="${escapeHtml(backupName || "")}">${escapeHtml(backupName || "-")}</div>
                </div>
                <div class="backup-archive-record-extra__field backup-archive-record-extra__field--wide">
                  <label>backup_path</label>
                  <div class="backup-archive-record-extra__path" title="${escapeHtml(backupPath || "")}">${escapeHtml(backupPath || "-")}</div>
                </div>
              </div>
            </div>
          </details>
          <details class="backup-archive-record-linked">
            <summary>查看该 username 关联的账号记录</summary>
            <div class="backup-archive-record-linked__body">
              ${linkedAccountInfo}
            </div>
          </details>
        </div>
      `;
}

function syncBackupArchiveSearchFromInput() {
  if (!backupArchiveSearchInput) return;
  backupArchiveSearchQuery = String(backupArchiveSearchInput.value || "").trim();
}

/** 备份档案分组「其它 N 条」折叠态；列表重渲（时段高亮/日计划刷新）时需保留，否则会看起来像自动收起。 */
function collectOpenBackupArchiveGroupFolds() {
  const open = new Set();
  if (!deviceAliasGroupsEl) return open;
  deviceAliasGroupsEl.querySelectorAll("details.panel-card[data-archive-alias]").forEach((group) => {
    if (!(group instanceof HTMLElement)) return;
    const fold = group.querySelector("details.backup-archive-group-fold");
    if (fold instanceof HTMLDetailsElement && fold.open) {
      open.add(String(group.getAttribute("data-archive-alias") || ""));
    }
  });
  return open;
}

function backupArchiveGroupFoldKey(alias) {
  return alias === "__EMPTY_ALIAS__" ? "" : String(alias || "");
}

function renderBackupGroups(items = []) {
  if (!deviceAliasGroupsEl) return;
  if (!Array.isArray(items) || !items.length) {
    const emptyHint = backupArchiveSearchQuery
      ? `未找到匹配「${escapeHtml(backupArchiveSearchQuery)}」的备份档案`
      : "暂无备份档案";
    deviceAliasGroupsEl.innerHTML = `<div class="hint">${emptyHint}</div>`;
    return;
  }
  const openGroupFolds = collectOpenBackupArchiveGroupFolds();
  const groups = new Map();
  items.forEach((item) => {
    const alias = resolveBackupArchiveDisplayAlias(item) || "__EMPTY_ALIAS__";
    if (!groups.has(alias)) groups.set(alias, []);
    groups.get(alias).push(item);
  });
  const planDateHint = autoSwitchDailyPlanDate
    ? `<span class="hint" style="font-weight:400;">（北京 ${escapeHtml(autoSwitchDailyPlanDate)}）</span>`
    : "";
  deviceAliasGroupsEl.innerHTML = Array.from(groups.entries()).map(([alias, rows]) => {
    const label = alias === "__EMPTY_ALIAS__" ? "未分配别名" : alias;
    const aliasKey = backupArchiveGroupFoldKey(alias);
    const deviceId = resolveDeviceIdForArchiveAlias(alias, rows[0]?.device_id);
    const autoSwitchOn = isAutoSwitchEnabledForDeviceId(deviceId);
    const { primary, secondary } = partitionBackupArchiveGroupRows(rows, alias);
    const primaryHtml = primary.map((row, idx) => renderSingleBackupArchiveRecordCard(row, rows, idx, alias)).join("");
    const foldOpenAttr = openGroupFolds.has(aliasKey) ? " open" : "";
    const secondaryHtml = secondary.length
      ? `<details class="backup-archive-group-fold"${foldOpenAttr}>
          <summary class="backup-archive-group-fold__summary">其它 ${secondary.length} 条记录（点击展开）</summary>
          <div class="backup-archive-group-fold__body">${secondary.map((row, idx) => renderSingleBackupArchiveRecordCard(row, rows, primary.length + idx, alias)).join("")}</div>
        </details>`
      : "";
    const body = `${primaryHtml}${secondaryHtml}`;
    const groupRowIndices = rows
      .map((row) => resolveBackupArchiveExportRowIndex(row))
      .filter((n) => n != null);
    const groupAllSelected = groupRowIndices.length > 0 && groupRowIndices.every((n) => backupArchiveSelectedRowIndices.has(n));
    return `<details class="panel-card" style="margin-bottom:12px;" open data-archive-alias="${escapeHtml(aliasKey)}" data-archive-device-id="${escapeHtml(deviceId)}">
      <summary style="cursor:pointer;font-weight:600;display:flex;justify-content:space-between;gap:10px;align-items:center;">
        <span style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
          ${groupRowIndices.length ? `<label class="backup-archive-group-select" title="勾选本组：导出 xlsx / 开启或关闭自动切号" onclick="event.stopPropagation()"><input type="checkbox" class="backup-archive-group-select-all"${groupAllSelected ? " checked" : ""} data-group-row-indices="${escapeHtml(groupRowIndices.join(","))}" /></label>` : ""}
          ${!deviceId ? `<span class="hint" title="未绑定 device_id，无法开启自动切号；请先在设备总览完成客户端绑定">⚠</span>` : ""}
          <span>${escapeHtml(label)}${planDateHint}${autoSwitchOn ? ' <span class="badge badge--ok">自动切号中</span>' : ""}</span>
        </span>
        <span class="hint">${rows.length} 条</span>
      </summary>
      <div style="margin-top:10px;">${body}</div>
    </details>`;
  }).join("");
}

function resolveBackupArchiveExportRowIndex(row) {
  const raw = row?.row_index ?? row?.id;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

function getBackupArchiveSelectedRowIndices() {
  return Array.from(backupArchiveSelectedRowIndices)
    .map((n) => Number(n))
    .filter((n) => Number.isFinite(n) && n >= 0)
    .sort((a, b) => a - b);
}

function updateBackupArchiveSelectionUi() {
  const count = backupArchiveSelectedRowIndices.size;
  if (backupArchiveSelectedCountEl) backupArchiveSelectedCountEl.textContent = String(count);
  if (backupArchiveSelectionHintEl) {
    backupArchiveSelectionHintEl.style.display = count > 0 ? "" : "none";
  }
  if (exportBackupArchiveSelectedXlsxBtn) {
    exportBackupArchiveSelectedXlsxBtn.disabled = backupArchiveXlsxBusy || count <= 0;
  }
  applyAutoSwitchManualOpsUi();
}

function setBackupArchiveRowSelected(rowIndex, selected) {
  const idx = Number(rowIndex);
  if (!Number.isFinite(idx) || idx < 0) return;
  if (selected) backupArchiveSelectedRowIndices.add(idx);
  else backupArchiveSelectedRowIndices.delete(idx);
  updateBackupArchiveSelectionUi();
}

function syncBackupArchiveExportCheckboxes() {
  if (!deviceAliasGroupsEl) return;
  deviceAliasGroupsEl.querySelectorAll(".backup-archive-export-select").forEach((el) => {
    if (!(el instanceof HTMLInputElement)) return;
    const idx = Number(el.dataset.rowIndex);
    el.checked = Number.isFinite(idx) && backupArchiveSelectedRowIndices.has(idx);
  });
}

function selectAllBackupArchiveRowsOnPage() {
  if (!deviceAliasGroupsEl) return;
  deviceAliasGroupsEl.querySelectorAll("details.panel-card[data-archive-alias]").forEach((details) => {
    if (!(details instanceof HTMLElement)) return;
    const indices = new Set();
    const groupCb = details.querySelector(".backup-archive-group-select-all");
    if (groupCb instanceof HTMLInputElement) {
      String(groupCb.dataset.groupRowIndices || "")
        .split(",")
        .map((s) => Number(s.trim()))
        .filter((n) => Number.isFinite(n) && n >= 0)
        .forEach((n) => indices.add(n));
      groupCb.checked = indices.size > 0;
    }
    details.querySelectorAll(".backup-archive-export-select").forEach((el) => {
      if (!(el instanceof HTMLInputElement)) return;
      const idx = Number(el.dataset.rowIndex);
      if (Number.isFinite(idx) && idx >= 0) indices.add(idx);
      el.checked = true;
    });
    indices.forEach((idx) => backupArchiveSelectedRowIndices.add(idx));
    syncAutoSwitchPickForAliasGroup(details, indices.size > 0);
  });
  updateBackupArchiveSelectionUi();
}

function clearBackupArchiveSelection() {
  backupArchiveSelectedRowIndices.clear();
  autoSwitchPickDeviceIds.clear();
  syncBackupArchiveExportCheckboxes();
  if (deviceAliasGroupsEl) {
    deviceAliasGroupsEl.querySelectorAll(".backup-archive-group-select-all").forEach((el) => {
      if (el instanceof HTMLInputElement) el.checked = false;
    });
  }
  updateBackupArchiveSelectionUi();
}

function setBackupArchiveXlsxButtonsBusy(busy) {
  backupArchiveXlsxBusy = Boolean(busy);
  if (exportBackupArchiveXlsxBtn) {
    exportBackupArchiveXlsxBtn.disabled = busy;
    exportBackupArchiveXlsxBtn.textContent = busy ? "导出中..." : "导出全部 xlsx";
  }
  if (exportBackupArchiveSelectedXlsxBtn) {
    exportBackupArchiveSelectedXlsxBtn.disabled = busy || backupArchiveSelectedRowIndices.size <= 0;
    exportBackupArchiveSelectedXlsxBtn.textContent = busy ? "导出中..." : "导出已选 xlsx";
  }
  if (backupArchiveSelectAllPageBtn) backupArchiveSelectAllPageBtn.disabled = busy;
  if (backupArchiveClearSelectionBtn) backupArchiveClearSelectionBtn.disabled = busy;
  if (importBackupArchiveXlsxBtn) {
    importBackupArchiveXlsxBtn.disabled = busy;
    importBackupArchiveXlsxBtn.textContent = busy ? "导入中..." : "导入 xlsx";
  }
}

async function downloadBackupArchiveExportFile(filename) {
  const safeName = String(filename || "").trim();
  if (!safeName) throw new Error("服务端未返回导出文件名");
  const token = authToken();
  const url = await buildApiUrl(
    `/api/admin/editor/backup_archive/export_file?filename=${encodeURIComponent(safeName)}`,
  );
  const response = await fetch(url, {
    method: "GET",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    cache: "no-store",
  });
  if (!response.ok) {
    let msg = `下载失败 (HTTP ${response.status})`;
    try {
      const data = await response.json();
      msg = String(data?.msg || msg);
    } catch {
      const text = await response.text();
      if (text) msg = text.slice(0, 200);
    }
    throw new Error(msg);
  }
  const blob = await response.blob();
  const objectUrl = window.URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = safeName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => window.URL.revokeObjectURL(objectUrl), 1000);
}

async function exportBackupArchiveXlsx({ selectedOnly = false } = {}) {
  if (backupArchiveXlsxBusy) return;
  const selected = getBackupArchiveSelectedRowIndices();
  if (selectedOnly && !selected.length) {
    setStatus("请先勾选要导出的备份记录", "warning");
    return;
  }
  setBackupArchiveXlsxButtonsBusy(true);
  try {
    const params = new URLSearchParams();
    if (selectedOnly && selected.length) params.set("row_indices", selected.join(","));
    const token = authToken();
    const qs = params.toString();
    const url = await buildApiUrl(
      `/api/admin/editor/backup_archive/export_xlsx${qs ? `?${qs}` : ""}`,
    );
    setStatus(selectedOnly ? "正在导出已选备份为 xlsx..." : "正在导出全部备份为 xlsx...", "info");
    const response = await fetch(url, {
      method: "GET",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      cache: "no-store",
    });
    const text = await response.text();
    let data = {};
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      throw new Error(text ? text.slice(0, 200) : `导出失败 (HTTP ${response.status})`);
    }
    if (!response.ok) {
      throw new Error(String(data?.msg || `导出失败 (HTTP ${response.status})`));
    }
    if (Number(data?.code) !== 0) {
      throw new Error(String(data?.msg || "导出失败"));
    }
    const filename = String(data?.filename || "").trim();
    const count = Number(data?.count || 0);
    await downloadBackupArchiveExportFile(filename);
    setStatus(`备份档案已导出：${filename}（${count} 条）`, "success");
  } finally {
    setBackupArchiveXlsxButtonsBusy(false);
    updateBackupArchiveSelectionUi();
  }
}

async function importBackupArchiveXlsxFile(file) {
  if (!file || backupArchiveXlsxBusy) return;
  const name = String(file.name || "").toLowerCase();
  if (!name.endsWith(".xlsx")) {
    setStatus("请选择 .xlsx 文件", "warning");
    return;
  }
  setBackupArchiveXlsxButtonsBusy(true);
  try {
    const token = authToken();
    const form = new FormData();
    form.append("file", file);
    setStatus("正在导入备份档案 xlsx...", "info");
    const response = await fetch(await buildApiUrl("/api/admin/editor/backup_archive/import_xlsx"), {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: form,
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok || Number(data?.code) !== 0) {
      throw new Error(String(data?.msg || `导入失败 (HTTP ${response.status})`));
    }
    const imported = Number(data?.imported || 0);
    const updated = Number(data?.updated || 0);
    const skippedDup = Number(data?.skipped_duplicate || 0);
    const skippedEmpty = Number(data?.skipped_empty || 0);
    await loadEditorState();
    await loadBackupArchiveRecords();
    const parts = [];
    if (imported > 0) parts.push(`新增 ${imported} 条`);
    if (updated > 0) parts.push(`更新已有 ${updated} 条`);
    if (!parts.length) parts.push("新增 0 条");
    if (skippedDup > 0) parts.push(`跳过文件内重复 username ${skippedDup} 条`);
    if (skippedEmpty > 0) parts.push(`跳过空 username ${skippedEmpty} 条`);
    setStatus(`备份档案导入完成：${parts.join("，")}`, imported > 0 || updated > 0 ? "success" : "warning");
  } finally {
    setBackupArchiveXlsxButtonsBusy(false);
    if (backupArchiveImportFileInput) backupArchiveImportFileInput.value = "";
  }
}

function scheduleBackupArchiveSlotHighlightRefresh() {
  if (backupArchiveSlotHighlightTimer) {
    clearTimeout(backupArchiveSlotHighlightTimer);
    backupArchiveSlotHighlightTimer = null;
  }
  if (!deviceAliasGroupsEl || !Array.isArray(backupArchivePageItems) || !backupArchivePageItems.length) return;
  const hasAutoSwitch = Array.from(autoSwitchDevicesById.values()).some(Boolean);
  if (!hasAutoSwitch || !isAutoSwitchDailyPlanScheduleCurrent()) return;
  const delayMs = Math.max(1000, 60_000 - (Date.now() % 60_000) + 800);
  backupArchiveSlotHighlightTimer = setTimeout(() => {
    backupArchiveSlotHighlightTimer = null;
    if (Array.isArray(backupArchivePageItems) && backupArchivePageItems.length) {
      renderBackupGroups(backupArchivePageItems);
      syncBackupArchiveExportCheckboxes();
      updateBackupArchiveSelectionUi();
      applyAutoSwitchManualOpsUi();
    }
    scheduleBackupArchiveSlotHighlightRefresh();
  }, delayMs);
}

async function refreshAutoSwitchDailyPlansInBackground() {
  try {
    const plansResp = await request("/api/admin/auto_switch/daily_plans");
    const plansPayload = plansResp?.data || {};
    autoSwitchDailyPlansByAlias = normalizeAutoSwitchPlansByAlias(plansPayload.plans_by_alias || {});
    autoSwitchDailyPlanDate = String(plansPayload.schedule_date || "").trim();
    if (!Array.isArray(backupArchivePageItems) || !backupArchivePageItems.length) return;
    renderBackupGroups(backupArchivePageItems);
    syncBackupArchiveExportCheckboxes();
    updateBackupArchiveSelectionUi();
    applyAutoSwitchManualOpsUi();
    scheduleBackupArchiveSlotHighlightRefresh();
  } catch (_) {
    /* 时段计划展示失败不影响档案列表 */
  }
}

async function loadBackupArchiveRecords() {
  if (!deviceAliasGroupsEl) return;
  try {
    syncBackupArchiveSearchFromInput();
    await loadBoundDeviceAliases().catch(() => {});
    const params = new URLSearchParams({
      page: String(currentBackupArchivePage),
      page_size: String(persistBackupArchivePageSize()),
    });
    const searchQ = String(backupArchiveSearchQuery || "").trim();
    if (searchQ) params.set("search", searchQ);
    const data = await request(`/api/admin/editor/device_rows?${params.toString()}`);
    backupArchiveAliasTotal = Number(data?.total ?? 0) || 0;
    if (Number.isFinite(Number(data?.page))) currentBackupArchivePage = Number(data.page) || 1;
    const rows = Array.isArray(data.items) ? data.items : [];
    const items = rows.map((row) => ({
      ...row,
      row_index: resolveBackupArchiveExportRowIndex(row) ?? row?.row_index ?? row?.id,
      account_assigned_alias: row?.account_assigned_alias || "",
      device_bucket_alias: normalizeAliasValue(row?.assigned_alias || row?.device_alias),
      assigned_alias: row?.assigned_alias || row?.account_assigned_alias || row?.device_alias || "",
      mobile_alias: row?.mobile_alias || row?.device_alias || "",
      created_at: row?.created_at || row?.updated_at || "",
      updated_at: row?.updated_at || row?.created_at || "",
      backup_name: row?.backup_name || "",
      backup_path: row?.backup_path || "",
      username: row?.username || "",
      auto_switch_excluded: Boolean(row?.auto_switch_excluded),
      video_count: row?.video_count || "",
      follower_count: row?.follower_count || "",
      follower_growth: row?.follower_growth || "",
      switch_highlight: normalizeSwitchHighlightField(row?.switch_highlight),
      env_status: normalizeEnvStatusField(row?.env_status),
    }));
    backupArchivePageItems = items;
    updateBackupArchivePagerUi();
    renderBackupGroups(items);
    syncBackupArchiveExportCheckboxes();
    updateBackupArchiveSelectionUi();
    applyAutoSwitchManualOpsUi();
    scheduleBackupArchiveSlotHighlightRefresh();
    void refreshAutoSwitchDailyPlansInBackground();
  } catch (error) {
    if (backupArchiveSlotHighlightTimer) {
      clearTimeout(backupArchiveSlotHighlightTimer);
      backupArchiveSlotHighlightTimer = null;
    }
    deviceAliasGroupsEl.innerHTML = `<div class="hint">加载备份档案失败：${escapeHtml(error.message || "unknown")}</div>`;
  }
}

if (deviceLogDeviceSelect) {
  deviceLogDeviceSelect.addEventListener("change", () => {
    void refreshVisibleDeviceLogs({ requestPull: false });
  });
}
if (deviceLogPullBtn) {
  deviceLogPullBtn.addEventListener("click", () => {
    void refreshVisibleDeviceLogs({ requestPull: true });
  });
}
if (addBlankRecordBtn) {
  addBlankRecordBtn.addEventListener("click", () => addBlankEditorRow().catch((e) => setStatus(e.message || "新增失败", "error")));
}
if (importAccountBatchBtn) {
  importAccountBatchBtn.addEventListener("click", () => {
    if (accountBatchImportInFlight) return;
    importAccountBatchFromText().catch((e) => setStatus(e.message || "批量导入失败", "error"));
  });
}
if (closeAccountBatchPreviewBtn) {
  closeAccountBatchPreviewBtn.addEventListener("click", closeAccountBatchPreviewModal);
}
if (cancelAccountBatchPreviewBtn) {
  cancelAccountBatchPreviewBtn.addEventListener("click", closeAccountBatchPreviewModal);
}
if (accountBatchPreviewModalEl) {
  accountBatchPreviewModalEl.addEventListener("click", (event) => {
    if (event.target === accountBatchPreviewModalEl) {
      closeAccountBatchPreviewModal();
      return;
    }
    if (accountBatchPreviewSmartAliasPanelEl && !accountBatchPreviewSmartAliasPanelEl.classList.contains("hidden")) {
      const target = event.target;
      if (
        target instanceof Node
        && !accountBatchPreviewSmartAliasPanelEl.contains(target)
        && target !== accountBatchPreviewSmartAliasToggleBtn
      ) {
        setAccountBatchPreviewSmartAliasPanelOpen(false);
      }
    }
  });
}
if (accountBatchPreviewSelectAllEl) {
  accountBatchPreviewSelectAllEl.addEventListener("change", () => {
    const checked = Boolean(accountBatchPreviewSelectAllEl.checked);
    accountBatchPreviewItems.forEach((item) => {
      if (item.kind !== "duplicate" && item.kind !== "skip") item.selected = checked;
    });
    renderAccountBatchPreviewTable();
  });
}
if (accountBatchPreviewTableWrapEl) {
  accountBatchPreviewTableWrapEl.addEventListener("change", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    if (target.classList.contains("account-batch-preview-row-check")) {
      const index = Number(target.dataset.previewIndex);
      const item = accountBatchPreviewItems[index];
      if (item && item.kind !== "duplicate") {
        item.selected = Boolean(target.checked);
        syncAccountBatchPreviewSelectAllState();
        updateAccountBatchPreviewSummary();
      }
      return;
    }
    if (target.classList.contains("account-batch-preview-alias-select")) {
      const index = Number(target.dataset.previewIndex);
      const item = accountBatchPreviewItems[index];
      if (item) {
        item.assigned_alias = normalizeAliasValue(target.value);
        item.row.assigned_alias = item.assigned_alias;
      }
    }
  });
}
if (accountBatchPreviewApplyAliasBtn) {
  accountBatchPreviewApplyAliasBtn.addEventListener("click", () => {
    syncAccountBatchPreviewItemsFromDom();
    const alias = normalizeAliasValue(accountBatchPreviewBulkAliasEl?.value);
    if (!alias) {
      setStatus("请先选择要批量分配的别名", "warning");
      return;
    }
    let applied = 0;
    accountBatchPreviewItems.forEach((item) => {
      if (!item.selected || item.kind === "duplicate") return;
      if (item.kind === "skip") {
        const existing = accountEditorRows[item.existingIndex];
        if (!existing || getEffectiveAccountAssignedAlias(existing)) return;
      }
      item.assigned_alias = alias;
      item.row.assigned_alias = alias;
      applied += 1;
    });
    if (!applied) {
      setStatus("请先勾选需要分配别名的记录", "warning");
      return;
    }
    renderAccountBatchPreviewTable();
    setStatus(`已为 ${applied} 条选中记录分配别名「${alias}」`, "success");
  });
}
if (accountBatchPreviewSmartAliasToggleBtn) {
  accountBatchPreviewSmartAliasToggleBtn.addEventListener("click", (event) => {
    event.stopPropagation();
    const willOpen = accountBatchPreviewSmartAliasPanelEl?.classList.contains("hidden");
    setAccountBatchPreviewSmartAliasPanelOpen(willOpen);
    if (willOpen) refreshAccountBatchPreviewSmartAliasList();
  });
}
if (accountBatchPreviewSmartAliasListEl) {
  accountBatchPreviewSmartAliasListEl.addEventListener("change", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement) || !target.classList.contains("account-batch-preview-smart-alias-check")) return;
    const alias = normalizeAliasValue(target.value);
    if (!alias) return;
    if (target.checked) accountBatchPreviewSmartAliases.add(alias);
    else accountBatchPreviewSmartAliases.delete(alias);
    updateAccountBatchPreviewSmartAliasToggleLabel();
  });
}
if (accountBatchPreviewSmartAliasSelectAllBtn) {
  accountBatchPreviewSmartAliasSelectAllBtn.addEventListener("click", () => {
    collectAssignableAliases().forEach((alias) => accountBatchPreviewSmartAliases.add(alias));
    refreshAccountBatchPreviewSmartAliasList();
  });
}
if (accountBatchPreviewSmartAliasClearBtn) {
  accountBatchPreviewSmartAliasClearBtn.addEventListener("click", () => {
    accountBatchPreviewSmartAliases = new Set();
    refreshAccountBatchPreviewSmartAliasList();
  });
}
if (accountBatchPreviewSmartDistributeBtn) {
  accountBatchPreviewSmartDistributeBtn.addEventListener("click", () => {
    applyAccountBatchPreviewSmartDistribution();
  });
}
if (confirmAccountBatchPreviewBtn) {
  confirmAccountBatchPreviewBtn.addEventListener("click", () => {
    commitAccountBatchImportFromPreview().catch((e) => {
      setStatus(e.message || "批量导入失败", "error");
    });
  });
}
if (accountStatsQueryBtn) {
  accountStatsQueryBtn.addEventListener("click", () => {
    openAccountStatsQueryFlow().catch((error) => {
      setStatus(error.message || "打开查询面板失败", "error");
    });
  });
}
if (accountStatsQueryLogDownloadBtn) {
  accountStatsQueryLogDownloadBtn.addEventListener("click", () => {
    downloadAccountStatsQueryLogs()
      .then(() => setStatus("查询日志已下载", "success"))
      .catch((error) => setStatus(error.message || "下载查询日志失败", "error"));
  });
}
if (closeAccountStatsQueryModalBtn) {
  closeAccountStatsQueryModalBtn.addEventListener("click", closeAccountStatsQueryModal);
}
if (cancelAccountStatsQueryModalBtn) {
  cancelAccountStatsQueryModalBtn.addEventListener("click", closeAccountStatsQueryModal);
}
if (confirmAccountStatsQueryBtn) {
  confirmAccountStatsQueryBtn.addEventListener("click", () => {
    confirmAccountStatsQueryRun().catch((error) => {
      setStatus(error.message || "提交查询失败", "error");
    });
  });
}
if (accountStatsQueryModalEl) {
  accountStatsQueryModalEl.addEventListener("click", (event) => {
    if (event.target === accountStatsQueryModalEl) closeAccountStatsQueryModal();
  });
}
if (accountStatsQuerySelectAllEl) {
  accountStatsQuerySelectAllEl.addEventListener("change", () => {
    const checked = Boolean(accountStatsQuerySelectAllEl.checked);
    accountStatsQueryAliasGroups.forEach((group) => {
      const alias = normalizeAliasValue(group?.alias);
      if (!alias) return;
      if (checked) accountStatsQuerySelectedAliases.add(alias);
      else accountStatsQuerySelectedAliases.delete(alias);
    });
    renderAccountStatsQueryAliasList();
  });
}
if (accountStatsQueryAliasListEl) {
  accountStatsQueryAliasListEl.addEventListener("change", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement) || !target.classList.contains("account-stats-query-alias-check")) return;
    const alias = normalizeAliasValue(target.dataset.alias);
    if (!alias) return;
    if (target.checked) accountStatsQuerySelectedAliases.add(alias);
    else accountStatsQuerySelectedAliases.delete(alias);
    updateAccountStatsQuerySummary();
    syncAccountStatsQuerySelectAllState();
  });
}
if (accountStatsQueryScheduleToggle) {
  accountStatsQueryScheduleToggle.addEventListener("change", () => {
    const enabled = Boolean(accountStatsQueryScheduleToggle.checked);
    if (accountStatsQueryScheduleTime) accountStatsQueryScheduleTime.disabled = !enabled;
    void (async () => {
      try {
        if (!accountStatsQueryConfig) {
          await loadAccountStatsQueryConfig();
        }
        const savedAliasCount = Array.isArray(accountStatsQueryConfig?.selected_aliases)
          ? accountStatsQueryConfig.selected_aliases.length
          : 0;
        if (enabled && !savedAliasCount) {
          accountStatsQueryScheduleToggle.checked = false;
          if (accountStatsQueryScheduleTime) accountStatsQueryScheduleTime.disabled = true;
          setStatus("请先通过「全量查询并落库」勾选并确认别名，再开启定时查询", "warning");
          return;
        }
        // 只改定时开关/时间，不覆盖已保存的别名记忆
        await saveAccountStatsQueryConfig({
          schedule_enabled: enabled,
          schedule_time: accountStatsQueryScheduleTime?.value || "09:00",
        });
        setStatus(enabled ? "已开启定时查询" : "已关闭定时查询", "success");
      } catch (error) {
        setStatus(error.message || "保存定时配置失败", "error");
        loadAccountStatsQueryConfig().catch(() => {});
      }
    })();
  });
}
if (accountStatsQueryScheduleTime) {
  accountStatsQueryScheduleTime.addEventListener("change", () => {
    if (!accountStatsQueryScheduleToggle?.checked) return;
    saveAccountStatsQueryConfig({
      schedule_enabled: true,
      schedule_time: accountStatsQueryScheduleTime.value || "09:00",
    }).catch((error) => {
      setStatus(error.message || "保存定时时间失败", "error");
    });
  });
}
if (batchStreamFileInput) {
  batchStreamFileInput.addEventListener("change", () => {
    const file = batchStreamFileInput.files?.[0];
    if (!file) return;
    uploadBatchStreamXlsx(file).catch((e) => setStatus(e.message || "上传 Excel 失败", "error"));
  });
}
if (batchStreamAliasSelect) {
  batchStreamAliasSelect.addEventListener("change", () => {
    batchStreamLastSnapshot = "";
    void pollBatchStreamStatus().then(() => {
      if (isBatchStreamSectionActive() && batchStreamSessionId) {
        scheduleBatchStreamPoll(BATCH_STREAM_POLL_MS);
      }
    });
  });
}
if (batchStreamStartBtn) {
  batchStreamStartBtn.addEventListener("click", () => {
    startBatchStreamOnDevice().catch((e) => setStatus(e.message || "启动批量带流失败", "error"));
  });
}
if (batchStreamStopBtn) {
  batchStreamStopBtn.addEventListener("click", () => {
    stopBatchStreamOnDevice().catch((e) => setStatus(e.message || "停止批量带流失败", "error"));
  });
}
if (batchStreamDownloadBtn) {
  batchStreamDownloadBtn.addEventListener("click", () => {
    downloadBatchStreamXlsx().catch((e) => setStatus(e.message || "下载 Excel 失败", "error"));
  });
}
if (addDeviceBlankRecordBtn) {
  addDeviceBlankRecordBtn.addEventListener("click", () => {
    void handleAddDeviceBlankRecordClick();
  });
}
if (accountUnassignedOnlyToggle) {
  accountUnassignedOnlyToggle.addEventListener("change", (event) => {
    accountFilterUnassignedOnly = Boolean(event?.target?.checked);
    currentAccountRecordsPage = 1;
    renderAccountEditorRows();
  });
}
if (accountRecordsSearchInput) {
  let accountSearchTimer = null;
  const onAccountRecordsSearchChanged = () => {
    accountRecordsSearchQuery = String(accountRecordsSearchInput.value || "").trim();
    accountRecordsSearchField = String(accountRecordsSearchFieldSelect?.value || "").trim();
    currentAccountRecordsPage = 1;
    clearTimeout(accountSearchTimer);
    accountSearchTimer = setTimeout(() => renderAccountEditorRows(), 200);
  };
  accountRecordsSearchInput.addEventListener("input", onAccountRecordsSearchChanged);
  if (accountRecordsSearchFieldSelect) {
    accountRecordsSearchFieldSelect.addEventListener("change", onAccountRecordsSearchChanged);
  }
}
if (accountRecordsPrevPageBtn) {
  accountRecordsPrevPageBtn.addEventListener("click", () => {
    if (currentAccountRecordsPage <= 1) return;
    currentAccountRecordsPage -= 1;
    renderAccountEditorRows();
  });
}
if (accountRecordsNextPageBtn) {
  accountRecordsNextPageBtn.addEventListener("click", () => {
    const totalPages = Math.max(1, Math.ceil(accountRecordsFilteredTotal / getAccountRecordsPageSize()));
    if (currentAccountRecordsPage >= totalPages) return;
    currentAccountRecordsPage += 1;
    renderAccountEditorRows();
  });
}
if (accountRecordsPageSizeInput) {
  persistAccountRecordsPageSize();
  accountRecordsPageSizeInput.addEventListener("change", onAccountRecordsPageSizeChanged);
  accountRecordsPageSizeInput.addEventListener("blur", onAccountRecordsPageSizeChanged);
}
if (accountRecordsToggleCredColsBtn) {
  updateAccountRecordsCredColsToggleBtn();
  accountRecordsToggleCredColsBtn.addEventListener("click", () => {
    toggleAccountRecordsCredCols();
  });
}
if (backupArchiveSearchInput) {
  let backupSearchTimer = null;
  const runBackupArchiveSearch = () => {
    syncBackupArchiveSearchFromInput();
    currentBackupArchivePage = 1;
    void loadBackupArchiveRecords();
  };
  const onBackupArchiveSearchChanged = () => {
    syncBackupArchiveSearchFromInput();
    clearTimeout(backupSearchTimer);
    backupSearchTimer = setTimeout(runBackupArchiveSearch, 300);
  };
  backupArchiveSearchInput.addEventListener("input", onBackupArchiveSearchChanged);
  backupArchiveSearchInput.addEventListener("search", onBackupArchiveSearchChanged);
  backupArchiveSearchInput.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    clearTimeout(backupSearchTimer);
    runBackupArchiveSearch();
  });
}
if (accountRecordsBatchProfileBtn) {
  accountRecordsBatchProfileBtn.addEventListener("click", () => {
    batchSetSelectedAccountBasicProfile().catch((e) => setStatus(humanizeRequestErrorMessage(e) || "批量改资料失败", "error"));
  });
}
if (accountRecordsBatchDeleteBtn) {
  accountRecordsBatchDeleteBtn.addEventListener("click", () => {
    deleteSelectedAccountRecords().catch((e) => setStatus(humanizeRequestErrorMessage(e) || "批量删除失败", "error"));
  });
}
if (accountEditorListEl) {
  accountEditorListEl.addEventListener("change", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    if (target.classList.contains("account-records-row-select")) {
      event.stopPropagation();
      setAccountRecordRowSelected(target.dataset.accountRowIndex, target.checked);
      syncAccountRecordsPageSelectAllCheckbox();
      return;
    }
    if (target.classList.contains("account-records-select-all")) {
      event.stopPropagation();
      const checked = target.checked;
      getVisibleAccountRecordPageRows().forEach(({ row, idx }) => {
        setAccountRecordRowSelected(resolveAccountRowIndex(row, idx), checked);
      });
      accountEditorListEl.querySelectorAll(".account-records-row-select").forEach((el) => {
        if (el instanceof HTMLInputElement) el.checked = checked;
      });
      target.indeterminate = false;
      return;
    }
  });
  accountEditorListEl.addEventListener("input", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    const scope = String(target.dataset.editorScope || "account");
    const index = Number(target.dataset.editorIndex);
    const field = String(target.dataset.editorField || "");
    if (!field || Number.isNaN(index)) return;
    syncEditorField(scope, index, field, target.value, target.checked).catch((e) => setStatus(humanizeRequestErrorMessage(e) || "保存失败", "error"));
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
    const twoFaBtn = target.closest(".account-editor-two-fa-code-btn");
    if (twoFaBtn) {
      event.preventDefault();
      const index = Number(twoFaBtn.getAttribute("data-editor-index"));
      if (!Number.isNaN(index)) {
        fetchAccountEditorTwoFaCode(index).catch((e) => setStatus(e.message || "2FA 取码失败", "error"));
      }
      return;
    }
    const delBtn = target.closest("[data-editor-delete]");
    if (delBtn) {
      const scope = String(delBtn.getAttribute("data-editor-delete") || "account");
      const index = Number(delBtn.getAttribute("data-editor-index"));
      if (!Number.isNaN(index)) deleteEditorRow(scope, index).catch((e) => setStatus(e.message || "删除失败", "error"));
    }
  });
}
if (deviceAliasGroupsEl) {
  deviceAliasGroupsEl.addEventListener("change", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    if (target.classList.contains("backup-archive-export-select")) {
      event.stopPropagation();
      const idx = Number(target.dataset.rowIndex);
      setBackupArchiveRowSelected(idx, target.checked);
      const details = target.closest("details.panel-card");
      if (details instanceof HTMLElement) {
        syncAutoSwitchPickForAliasGroup(details, aliasGroupHasSelectedExportRows(details));
      }
      return;
    }
    if (target.classList.contains("backup-archive-group-select-all")) {
      event.stopPropagation();
      const raw = String(target.dataset.groupRowIndices || "");
      const indices = raw.split(",").map((s) => Number(s.trim())).filter((n) => Number.isFinite(n) && n >= 0);
      indices.forEach((idx) => setBackupArchiveRowSelected(idx, target.checked));
      const details = target.closest("details");
      if (details instanceof HTMLElement) {
        details.querySelectorAll(".backup-archive-export-select").forEach((el) => {
          if (el instanceof HTMLInputElement) el.checked = target.checked;
        });
        syncAutoSwitchPickForAliasGroup(details, target.checked);
      }
      return;
    }
  });
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
    syncEditorField(scope, index, field, target.value, target.checked).catch((e) => setStatus(humanizeRequestErrorMessage(e) || "保存失败", "error"));
  });
  deviceAliasGroupsEl.addEventListener("click", async (event) => {
    const targetEl = event.target;
    if (!(targetEl instanceof Element)) return;
    const target = targetEl.closest("button");
    if (!target) return;
    const archiveCardGuard = target.closest(".backup-archive-record");
    if (archiveCardGuard instanceof HTMLElement) {
      const isActiveCard = archiveCardGuard.classList.contains("backup-archive-record--active");
      const isSwitchOnly = target.hasAttribute("data-account-switch");
      const isUnassignOnly = target.hasAttribute("data-account-unassign");
      const isAutoSwitchExcludeOnly = target.hasAttribute("data-account-auto-switch-excluded");
      if (!isActiveCard && !isSwitchOnly && !isUnassignOnly && !isAutoSwitchExcludeOnly) {
        event.preventDefault();
        return;
      }
    }
    if (target.hasAttribute("data-alias-add-row")) {
      event.preventDefault();
      const alias = String(target.getAttribute("data-alias-add-row") || "").trim();
      await handleAddDeviceBlankRecordClick(alias);
      return;
    }
    if (target.getAttribute("data-action") === "alias-clear") {
      event.preventDefault();
      const alias = String(target.getAttribute("data-device-alias") || "").trim();
      const deviceId = String(target.getAttribute("data-device-id") || "").trim();
      if (!guardAutoSwitchManualOps("清理 TikTok 数据", deviceId, alias, deviceId)) return;
      const identity = deviceId
        ? collectDeviceIdentityByDeviceAndAlias(deviceId, alias)
        : collectDeviceIdentityByAlias(alias);
      if (!identity) return;
      const detailText = [`设备别名：${alias}`].join("\n");
      if (!confirmTikTokAction("清理 TikTok 数据", detailText)) return;
      await submitAndWaitTikTokDeviceOp("/api/admin/device_tiktok/clear", identity, {
        pendingText: "清理任务下发中...",
        successText: "清理任务已完成",
        errorText: "清理任务失败",
      }).catch((e) => setStatus(e.message || "清理失败", "error"));
      return;
    }
    if (target.hasAttribute("data-account-backup")) {
      event.preventDefault();
      const alias = String(target.getAttribute("data-account-backup") || "").trim();
      const backupRecordId = Number(target.getAttribute("data-account-id") || 0) || undefined;
      const username = String(target.getAttribute("data-account-username") || "").trim();
      const deviceIdBtn = String(target.getAttribute("data-device-id") || "").trim();
      if (!guardAutoSwitchManualOps("备份并清理", deviceIdBtn, alias, deviceIdBtn)) return;
      await handleBackupAndClearClick(alias, backupRecordId, username, deviceIdBtn).catch((e) => {
        setStatus(e.message || "备份失败", "error");
      });
      return;
    }
    if (target.hasAttribute("data-account-unassign")) {
      event.preventDefault();
      const username = String(target.getAttribute("data-account-username") || "").trim();
      const assignedAlias = String(target.getAttribute("data-account-alias") || "").trim();
      const deviceRowIndexRaw = String(target.getAttribute("data-device-row-index") || "").trim();
      const deviceRowIndex = deviceRowIndexRaw ? Number(deviceRowIndexRaw) : NaN;
      if (!username || !assignedAlias) return;
      const ok = window.confirm(`确认删除分配？\n账号：${username}\n别名：${assignedAlias}`);
      if (!ok) return;
      const resp = await request("/api/admin/editor/account_rows/unassign_alias", {
        method: "POST",
        body: JSON.stringify({
          username,
          assigned_alias: assignedAlias,
          device_row_index: Number.isFinite(deviceRowIndex) ? deviceRowIndex : null,
        }),
      });
      releaseAccountAliasBindingLocally(username, assignedAlias);
      await refreshAccountEditorLight({ refreshArchive: true });
      renderAccountEditorRows();
      renderDeviceEditorRows();
      const updatedAccounts = Number(resp?.updated_accounts ?? 0);
      const deletedDevices = Number(resp?.deleted_device_rows ?? 0);
      setStatus(
        `已删除分配：${username} -> ${assignedAlias}（账号 ${updatedAccounts}，备份档案 ${deletedDevices}）`,
        "success",
      );
      return;
    }
    if (target.hasAttribute("data-account-auto-switch-excluded")) {
      event.preventDefault();
      const rowIndex = Number(target.getAttribute("data-account-id") || 0) || 0;
      if (!rowIndex) return;
      const currentExcluded = String(target.getAttribute("data-account-excluded") || "").trim() === "1";
      const nextExcluded = !currentExcluded;
      const actionText = nextExcluded ? "不参加自动切号" : "参加自动切号";
      const detail = nextExcluded
        ? "开启后，该账号将从自动切号计划和 bundle 中排除。"
        : "关闭后，该账号会重新参与自动切号计划。";
      if (!window.confirm(`确认设置为“${actionText}”？\n${detail}`)) return;
      const resp = await request("/api/admin/editor/device_rows/auto_switch_excluded", {
        method: "POST",
        body: JSON.stringify({
          row_index: rowIndex,
          excluded: nextExcluded,
        }),
      });
      await loadAutoSwitchConfig({ refreshArchive: false });
      await loadBackupArchiveRecords();
      await refreshAutoSwitchDailyPlansInBackground();
      const bucketSize = Number(resp?.bucket_size || 0);
      const disabledDevices = Number(resp?.devices_disabled || 0);
      const notifiedDevices = Number(resp?.devices_notified || 0);
      const parts = [`已设置为“${actionText}”`];
      if (bucketSize > 0) parts.push(`当前参与自动切号账号 ${bucketSize} 个`);
      if (disabledDevices > 0) parts.push(`已自动关闭 ${disabledDevices} 台设备自动切号`);
      else if (notifiedDevices > 0) parts.push(`已重同步 ${notifiedDevices} 台设备`);
      setStatus(parts.join("，"), disabledDevices > 0 ? "warning" : "success");
      return;
    }
    if (target.hasAttribute("data-account-switch")) {
      event.preventDefault();
      const card = target.closest(".backup-archive-record");
      if (!(card instanceof HTMLElement)) {
        setStatus("未找到账号条目", "warning");
        return;
      }
      runBackupArchiveAccountCutSwitch(card).catch((e) => setStatus(e.message || "切号失败", "error"));
      return;
    }
    if (target.hasAttribute("data-basic-profile-edit")) {
      event.preventDefault();
      const card = target.closest(".backup-archive-record");
      if (!(card instanceof HTMLElement)) {
        setStatus("未找到账号条目", "warning");
        return;
      }
      await handleBasicProfileEditFromBackupCard(card).catch((e) => setStatus(e.message || "操作失败", "error"));
      return;
    }
    if (target.hasAttribute("data-account-restore")) {
      event.preventDefault();
      const alias = String(target.getAttribute("data-account-restore") || "").trim();
      const backupName = String(target.getAttribute("data-account-backup-name") || "").trim();
      const restoreCard = target.closest(".backup-archive-record");
      const restoreDeviceId = restoreCard instanceof HTMLElement
        ? String(restoreCard.getAttribute("data-row-device-id") || "").trim()
        : "";
      if (!guardAutoSwitchManualOps("清理并还原", restoreDeviceId, alias, restoreDeviceId)) return;
      await handleRestoreClick(alias, backupName, restoreDeviceId).catch((e) => {
        setStatus(e.message || "清理并还原失败", "error");
      });
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
  const stored = sanitizeApiBaseForCurrentPage((localStorage.getItem(API_BASE_LS_KEY) || "").trim().replace(/\/$/, ""));
  const normalizedDefault = defaultApiBase();
  const nextValue = stored || normalizedDefault;
  apiBaseInput.value = nextValue;
  localStorage.setItem(API_BASE_LS_KEY, nextValue);
  runtimeApiBaseOverride = "";
  updateStartupSelfCheck({ apiBase: nextValue });
  const persistApiBaseInput = () => {
    const normalized = sanitizeApiBaseForCurrentPage((apiBaseInput.value || "").trim().replace(/\/$/, ""));
    runtimeApiBaseOverride = "";
    updateStartupSelfCheck({ apiBase: normalized || defaultApiBase() });
    if (normalized) {
      localStorage.setItem(API_BASE_LS_KEY, normalized);
    } else {
      localStorage.removeItem(API_BASE_LS_KEY);
    }
  };
  apiBaseInput.addEventListener("input", persistApiBaseInput);
  apiBaseInput.addEventListener("change", persistApiBaseInput);
}

(async () => {
  await ensureApiBaseHealthyOnBoot().catch(() => {});
  restoreUiStateSnapshot();
  const ok = await authCheck().catch(() => false);
  if (ok) {
    // 先加载用户运行配置（含模板列表），避免首屏设备模板下拉先渲染成 default。
    await loadUserRuntimeConfig().catch(() => {});
    await switchSection("overview", { persist: false }).catch(() => {});
    handleRefresh();
    loadAccounts();
    void loadDbTablePage().catch((e) => setStatus(e?.message || "数据表加载失败", "error"));
    void loadAdminUsers().catch((e) => setStatus(e?.message || "用户列表加载失败", "error"));
    void loadAutoSwitchConfig({ refreshArchive: true });
    refreshAccountEditorLight().then(() => {
      renderAccountEditorRows();
      void syncAssignableAliasOptionsIfNeeded();
      void loadBackupStatsFullQueryConfig();
      void loadAccountStatsQueryConfig();
    }).catch((error) => {
      // 避免静默失败：首屏卡在 “加载中...” 时直接给出错误原因
      const msg = error?.message || error?.toString?.() || "unknown";
      if (deviceAliasGroupsEl) {
        deviceAliasGroupsEl.innerHTML = `<div class="hint">初始化失败：${escapeHtml(msg)}</div>`;
      }
      setStatus(msg, "error");
    });
    startAdminBackgroundPollers();
  } else {
    authShell?.classList.remove("hidden");
    appShell?.classList.add("hidden");
  }
})();

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") {
    void syncAssignableAliasOptionsIfNeeded();
    startAdminBackgroundPollers();
  } else {
    stopAdminBackgroundPollers();
  }
});
}
