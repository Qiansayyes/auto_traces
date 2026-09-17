<template>
  <div class="token-mailboxes-page">
    <section class="toolbar-band">
      <div class="page-header">
        <div>
          <h2 class="page-title">令牌邮箱管理</h2>
          <p class="page-desc">
            按手机设备别名分组展示 OAuth 令牌。主服务约每天巡检一次，跳过近 20 天已检测的令牌；可单条刷新、页面全量刷新，或点「立即后台巡检」。
          </p>
          <p class="auto-refresh-status">
            自动刷新：
            <template v-if="autoRefresh.enabled">
              已启用（约每 {{ autoRefresh.skipDays }} 天跳过已检测；每日巡检）
              <span v-if="autoRefresh.inFlight"> · 正在运行</span>
              <span v-else-if="autoRefresh.lastStatus">
                · 上次 {{ autoRefresh.lastStatus }}
                <template v-if="autoRefresh.lastFinishedAt">（{{ autoRefresh.lastFinishedAt }}）</template>
              </span>
              <span v-else> · 尚未跑过</span>
            </template>
            <template v-else>已关闭</template>
            <el-button size="small" link type="primary" :loading="loading.autoStatus" @click="loadAutoRefreshStatus">
              刷新状态
            </el-button>
          </p>
        </div>
      </div>
      <el-form :inline="true" class="toolbar-form" @submit.prevent>
        <el-form-item label="接口密钥">
          <el-input v-model="form.apiPassword" type="password" show-password style="width: 200px" />
        </el-form-item>
        <el-form-item label="搜索邮箱">
          <el-input
            v-model="form.search"
            clearable
            placeholder="输入邮箱"
            style="width: 220px"
            @keyup.enter="loadList(true)"
            @clear="loadList(true)"
          />
        </el-form-item>
        <el-form-item label="令牌状态">
          <el-select v-model="form.tokenStatus" style="width: 140px" @change="loadList(true)">
            <el-option label="全部" value="all" />
            <el-option label="正常" value="valid" />
            <el-option label="失效" value="invalid" />
            <el-option label="未检测" value="unknown" />
            <el-option label="临时失败" value="transient" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.onlyInAccountRecords" @change="loadList(true)">
            仅账号表单中的邮箱
          </el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.collapseUnlinked" @change="persistCollapseUnlinked">
            折叠未关联邮箱（不在账号表单/记录中）
          </el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading.list" @click="loadList(true)">刷新列表</el-button>
          <el-button
            type="success"
            :loading="loading.export"
            :disabled="loading.list || loading.export"
            @click="exportLinkedInvalid"
          >
            导出已关联失效
          </el-button>
          <el-button
            type="warning"
            :loading="loading.check"
            :disabled="loading.list || loading.check || Boolean(refreshingEmail)"
            @click="runBatchRefresh"
          >
            全量刷新令牌
          </el-button>
          <el-button
            :loading="loading.autoRun"
            :disabled="loading.autoRun || autoRefresh.inFlight"
            @click="runServerAutoRefresh(false)"
          >
            立即后台巡检
          </el-button>
        </el-form-item>
      </el-form>
      <div v-if="checkProgress.active" class="check-progress">
        刷新进度：{{ checkProgress.done }} / {{ checkProgress.total }}
        （正常 {{ checkProgress.valid }} / 失效 {{ checkProgress.invalid }} / 临时失败 {{ checkProgress.transient }}）
      </div>
    </section>

    <section class="summary-grid">
      <div class="summary-card">
        <div class="summary-label">令牌邮箱</div>
        <div class="summary-value">{{ stats.total }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">账号表单已关联</div>
        <div class="summary-value">{{ stats.priorityMatched }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">账号表单邮箱数</div>
        <div class="summary-value">{{ stats.priorityEmailCount }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">设备别名分组</div>
        <div class="summary-value">{{ aliasGroups.length }}</div>
      </div>
    </section>

    <section class="panel">
      <div v-loading="loading.list" class="alias-groups">
        <el-empty v-if="!loading.list && !aliasGroups.length" description="暂无令牌邮箱" />
        <details
          v-for="group in aliasGroups"
          :key="group.key"
          class="alias-group"
          :class="{ 'alias-group-unlinked': group.key === UNLINKED_KEY }"
          :open="isGroupOpen(group.key)"
          @toggle="onGroupToggle(group.key, $event)"
        >
          <summary class="alias-group-summary">
            <span class="alias-group-title">{{ group.title }}</span>
            <el-tag size="small" type="info">{{ group.rows.length }}</el-tag>
            <el-tag
              v-if="group.invalidCount > 0"
              size="small"
              type="danger"
            >
              失效 {{ group.invalidCount }}
            </el-tag>
            <el-tag
              v-if="group.unknownCount > 0"
              size="small"
            >
              未检测 {{ group.unknownCount }}
            </el-tag>
          </summary>
          <el-table
            :data="group.rows"
            stripe
            size="small"
            :row-class-name="getRowClassName"
          >
            <el-table-column label="邮箱" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="email-cell">
                  <span>{{ row.email }}</span>
                  <el-button size="small" type="primary" @click="copyText(row.email, '邮箱已复制')">复制</el-button>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="设备别名" width="140" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.assigned_alias || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="账号表单" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="row.in_account_records ? 'success' : 'info'">
                  {{ row.in_account_records ? '已关联' : '未关联' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="令牌状态" width="140">
              <template #default="{ row }">
                <div class="token-status-cell">
                  <el-tag size="small" :type="getTokenStatusTagType(row.token_status)">
                    {{ formatTokenStatus(row.token_status) }}
                  </el-tag>
                  <span v-if="row.last_token_checked_at" class="token-status-hint">
                    {{ formatTokenCheckedAt(row.last_token_checked_at) }}
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="最近错误" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <span :class="{ 'token-error': row.token_status === 'invalid' }">
                  {{ row.last_token_error || '-' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">
                {{ formatTokenCheckedAt(row.updated_at) || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button
                  size="small"
                  type="primary"
                  link
                  :loading="refreshingEmail === normalizeEmail(row.email)"
                  :disabled="loading.check || (Boolean(refreshingEmail) && refreshingEmail !== normalizeEmail(row.email))"
                  @click="runSingleRefresh(row)"
                >
                  刷新令牌
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </details>
      </div>
    </section>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as XLSX from 'xlsx'

interface TokenMailboxRow {
  email: string
  token_status?: string
  last_token_error?: string
  last_token_checked_at?: string
  updated_at?: string
  in_account_records?: boolean
  assigned_alias?: string
}

interface AliasGroup {
  key: string
  title: string
  rows: TokenMailboxRow[]
  invalidCount: number
  unknownCount: number
}

const EMPTY_ALIAS_KEY = '__EMPTY_ALIAS__'
const UNLINKED_KEY = '__UNLINKED__'
const CHECK_BATCH_SIZE = 30
const LIST_PAGE_SIZE = 2000

const form = reactive({
  apiPassword: localStorage.getItem('apiPassword') || '',
  search: '',
  tokenStatus: localStorage.getItem('tokenMailboxStatus') || 'all',
  onlyInAccountRecords: localStorage.getItem('tokenMailboxOnlyLinked') === '1',
  collapseUnlinked: localStorage.getItem('tokenMailboxCollapseUnlinked') !== '0',
})

const loading = reactive({
  list: false,
  check: false,
  export: false,
  autoStatus: false,
  autoRun: false,
})

const refreshingEmail = ref('')

const stats = reactive({
  total: 0,
  priorityMatched: 0,
  priorityEmailCount: 0,
})

const autoRefresh = reactive({
  enabled: true,
  skipDays: 20,
  inFlight: false,
  lastStatus: '',
  lastFinishedAt: '',
})

const checkProgress = reactive({
  active: false,
  done: 0,
  total: 0,
  valid: 0,
  invalid: 0,
  transient: 0,
})

const rows = ref<TokenMailboxRow[]>([])
const priorityEmails = ref<string[]>([])
const aliasByEmail = ref<Map<string, string>>(new Map())
const openGroupKeys = ref<Set<string>>(new Set())
const openGroupsInitialized = ref(false)

watch(() => form.apiPassword, (value) => localStorage.setItem('apiPassword', value))
watch(() => form.tokenStatus, (value) => localStorage.setItem('tokenMailboxStatus', value || 'all'))
watch(
  () => form.onlyInAccountRecords,
  (value) => localStorage.setItem('tokenMailboxOnlyLinked', value ? '1' : '0'),
)

const persistCollapseUnlinked = () => {
  localStorage.setItem('tokenMailboxCollapseUnlinked', form.collapseUnlinked ? '1' : '0')
}

const loadAutoRefreshStatus = async () => {
  loading.autoStatus = true
  try {
    const response = await fetch('/api/mail-module/token_refresh/status')
    const payload = await response.json()
    const data = payload?.data || {}
    const last = data.last || {}
    autoRefresh.enabled = data.enabled !== false
    autoRefresh.skipDays = Number(data.skip_days) || 20
    autoRefresh.inFlight = Boolean(data.in_flight)
    autoRefresh.lastStatus = String(last.status || '').trim()
    autoRefresh.lastFinishedAt = String(last.finished_at || '').trim()
  } catch {
    // 状态接口失败时不打断主流程
  } finally {
    loading.autoStatus = false
  }
}

/** 触发主服务后台巡检：默认跳过近 N 天已检测；forceAll 则全量。 */
const runServerAutoRefresh = async (forceAll = false) => {
  if (loading.autoRun || autoRefresh.inFlight) {
    ElMessage.warning('后台刷新任务正在运行')
    return
  }
  try {
    await ElMessageBox.confirm(
      forceAll
        ? '将在后台全量刷新全部令牌（忽略「近 20 天已检测」跳过）。确认开始？'
        : `将在后台巡检令牌：跳过近 ${autoRefresh.skipDays} 天内已检测过的邮箱。确认开始？`,
      forceAll ? '后台全量刷新' : '立即后台巡检',
      { type: 'warning', confirmButtonText: '开始', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  loading.autoRun = true
  try {
    const response = await fetch('/api/mail-module/token_refresh/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ force_all: forceAll }),
    })
    const payload = await response.json()
    if (Number(payload?.code) !== 0) {
      throw new Error(payload?.msg || payload?.message || '启动失败')
    }
    ElMessage.success('已在后台启动，可点「刷新状态」查看进度')
    autoRefresh.inFlight = true
    await loadAutoRefreshStatus()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '启动失败')
  } finally {
    loading.autoRun = false
  }
}

const normalizeAlias = (value?: string) => String(value || '').trim()

const normalizeEmail = (value?: string) => String(value || '').trim().toLowerCase()

const compareAliasNatural = (a: string, b: string) =>
  a.localeCompare(b, 'zh-CN', { numeric: true, sensitivity: 'base' })

const formatTokenStatus = (value?: string) => {
  const status = String(value || 'unknown').trim().toLowerCase()
  if (status === 'valid') return '正常'
  if (status === 'invalid') return '失效'
  if (status === 'transient') return '临时失败'
  return '未检测'
}

const getTokenStatusTagType = (value?: string) => {
  const status = String(value || 'unknown').trim().toLowerCase()
  if (status === 'valid') return 'success'
  if (status === 'invalid') return 'danger'
  if (status === 'transient') return 'warning'
  return 'info'
}

const formatTokenCheckedAt = (value?: string) => {
  const raw = String(value || '').trim()
  if (!raw) return ''
  const time = Date.parse(raw)
  if (Number.isNaN(time)) return raw
  return new Date(time).toLocaleString()
}

const getRowClassName = ({ row }: { row: TokenMailboxRow }) => {
  const classes: string[] = []
  if (row.in_account_records) classes.push('linked-account-row')
  if (String(row.token_status || '').toLowerCase() === 'invalid') classes.push('token-invalid-row')
  return classes.join(' ')
}

const copyWithFallback = (text: string) => {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', 'readonly')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '0'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  textarea.setSelectionRange(0, textarea.value.length)
  const ok = document.execCommand('copy')
  document.body.removeChild(textarea)
  if (!ok) throw new Error('execCommand copy failed')
}

const copyText = async (text: string, successMessage: string) => {
  const value = String(text || '')
  if (!value) {
    ElMessage.warning('没有可复制的内容')
    return
  }

  try {
    if (navigator.clipboard?.writeText && window.isSecureContext) {
      await navigator.clipboard.writeText(value)
    } else {
      copyWithFallback(value)
    }
    ElMessage.success(successMessage)
  } catch {
    try {
      copyWithFallback(value)
      ElMessage.success(successMessage)
    } catch {
      ElMessage.error('复制失败')
    }
  }
}

const countStatus = (list: TokenMailboxRow[], status: string) =>
  list.filter((row) => String(row.token_status || 'unknown').toLowerCase() === status).length

const aliasGroups = computed<AliasGroup[]>(() => {
  const linkedByAlias = new Map<string, TokenMailboxRow[]>()
  const unlinked: TokenMailboxRow[] = []

  for (const row of rows.value) {
    if (!row.in_account_records) {
      unlinked.push(row)
      continue
    }
    const alias = normalizeAlias(row.assigned_alias)
    const key = alias || EMPTY_ALIAS_KEY
    if (!linkedByAlias.has(key)) linkedByAlias.set(key, [])
    linkedByAlias.get(key)!.push(row)
  }

  const groups: AliasGroup[] = Array.from(linkedByAlias.entries())
    .sort((a, b) => {
      if (a[0] === EMPTY_ALIAS_KEY) return 1
      if (b[0] === EMPTY_ALIAS_KEY) return -1
      return compareAliasNatural(a[0], b[0])
    })
    .map(([key, groupRows]) => ({
      key,
      title: key === EMPTY_ALIAS_KEY ? '未分配别名' : key,
      rows: groupRows,
      invalidCount: countStatus(groupRows, 'invalid'),
      unknownCount: countStatus(groupRows, 'unknown'),
    }))

  if (!form.onlyInAccountRecords && unlinked.length > 0) {
    groups.push({
      key: UNLINKED_KEY,
      title: '未关联账号表单（可折叠）',
      rows: unlinked,
      invalidCount: countStatus(unlinked, 'invalid'),
      unknownCount: countStatus(unlinked, 'unknown'),
    })
  }

  return groups
})

const isGroupOpen = (key: string) => {
  if (!openGroupsInitialized.value) {
    if (key === UNLINKED_KEY) return !form.collapseUnlinked
    return true
  }
  return openGroupKeys.value.has(key)
}

const onGroupToggle = (key: string, event: Event) => {
  const el = event.currentTarget as HTMLDetailsElement | null
  if (!el || el.tagName !== 'DETAILS') return
  const next = new Set(openGroupKeys.value)
  if (el.open) next.add(key)
  else next.delete(key)
  openGroupKeys.value = next
  openGroupsInitialized.value = true
}

watch(
  () => form.collapseUnlinked,
  (collapse) => {
    persistCollapseUnlinked()
    if (!openGroupsInitialized.value) return
    const next = new Set(openGroupKeys.value)
    if (collapse) next.delete(UNLINKED_KEY)
    else if (aliasGroups.value.some((group) => group.key === UNLINKED_KEY)) next.add(UNLINKED_KEY)
    openGroupKeys.value = next
  },
)

const syncOpenGroupsAfterLoad = () => {
  if (openGroupsInitialized.value) {
    const validKeys = new Set(aliasGroups.value.map((group) => group.key))
    openGroupKeys.value = new Set([...openGroupKeys.value].filter((key) => validKeys.has(key)))
    return
  }
  const next = new Set<string>()
  for (const group of aliasGroups.value) {
    if (group.key === UNLINKED_KEY && form.collapseUnlinked) continue
    next.add(group.key)
  }
  openGroupKeys.value = next
  openGroupsInitialized.value = true
}

type PriorityLoadResult =
  | { ok: true; emails: string[]; aliasMap: Map<string, string> }
  | { ok: false; emails: []; aliasMap: Map<string, string>; error: string }

const loadPriorityEmails = async (): Promise<PriorityLoadResult> => {
  try {
    const [accountResp, supportResp] = await Promise.all([
      fetch('/api/admin/editor/account_rows'),
      fetch('/api/admin/editor/account_rows/support'),
    ])

    let accountPayload: {
      code?: number | string
      msg?: string
      message?: string
      items?: Array<{ email?: string; username?: string; assigned_alias?: string }>
    } | null = null
    try {
      accountPayload = await accountResp.json()
    } catch {
      return {
        ok: false,
        emails: [],
        aliasMap: new Map(),
        error: `账号表单接口返回异常（HTTP ${accountResp.status}）`,
      }
    }

    const code = Number(accountPayload?.code)
    if (code === 401 || accountResp.status === 401) {
      return {
        ok: false,
        emails: [],
        aliasMap: new Map(),
        error: accountPayload?.msg || accountPayload?.message || '请先登录后再加载账号表单邮箱',
      }
    }
    if (!accountResp.ok || code !== 0) {
      return {
        ok: false,
        emails: [],
        aliasMap: new Map(),
        error: accountPayload?.msg || accountPayload?.message || `账号表单加载失败（HTTP ${accountResp.status}）`,
      }
    }

    const aliasByUsername = new Map<string, string>()
    try {
      if (supportResp.ok) {
        const supportPayload = await supportResp.json()
        if (Number(supportPayload?.code) === 0 && Array.isArray(supportPayload?.items)) {
          for (const item of supportPayload.items) {
            const username = String(item?.username || '').trim().toLowerCase()
            const alias = normalizeAlias(item?.assigned_alias || item?.device_alias)
            if (username && alias && !aliasByUsername.has(username)) {
              aliasByUsername.set(username, alias)
            }
          }
        }
      }
    } catch {
      // support endpoint is optional enrichment
    }

    const seen = new Set<string>()
    const emails: string[] = []
    const aliasMap = new Map<string, string>()
    for (const item of accountPayload?.items || []) {
      const email = normalizeEmail(item?.email)
      if (!email || !email.includes('@') || seen.has(email)) continue
      seen.add(email)
      emails.push(email)
      const alias = normalizeAlias(item?.assigned_alias)
        || aliasByUsername.get(String(item?.username || '').trim().toLowerCase())
        || ''
      if (alias) aliasMap.set(email, alias)
    }
    return { ok: true, emails, aliasMap }
  } catch (error) {
    return {
      ok: false,
      emails: [],
      aliasMap: new Map(),
      error: error instanceof Error ? error.message : '账号表单邮箱加载失败',
    }
  }
}

const enrichRowsWithAlias = (records: TokenMailboxRow[]) =>
  records.map((row) => {
    const email = normalizeEmail(row.email)
    return {
      ...row,
      assigned_alias: aliasByEmail.value.get(email) || '',
    }
  })

const loadList = async (resetPage = false) => {
  if (resetPage) {
    openGroupsInitialized.value = false
  }
  loading.list = true
  try {
    const priorityResult = await loadPriorityEmails()
    if (!priorityResult.ok) {
      priorityEmails.value = []
      aliasByEmail.value = new Map()
      if (form.onlyInAccountRecords) {
        ElMessage.error(`${priorityResult.error}；已停止本次「仅账号表单中的邮箱」筛选，避免误清空列表`)
        return
      }
      ElMessage.warning(`${priorityResult.error}；已关联标记与别名分组可能不准`)
    } else {
      priorityEmails.value = priorityResult.emails
      aliasByEmail.value = priorityResult.aliasMap
    }

    const collected: TokenMailboxRow[] = []
    let page = 1
    let total = 0
    let priorityMatched = 0
    let priorityEmailCount = priorityEmails.value.length

    while (true) {
      const response = await fetch('/api/accounts/token-mailboxes/list', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          password: form.apiPassword,
          page,
          pageSize: LIST_PAGE_SIZE,
          search: form.search,
          tokenStatus: form.tokenStatus,
          onlyInAccountRecords: form.onlyInAccountRecords,
          priorityEmails: priorityEmails.value,
        }),
      })
      const payload = await response.json()
      if (!(payload.code === '200' || payload.code === 200)) {
        throw new Error(payload.error || payload.message || '加载失败')
      }
      const data = payload.data || {}
      const batch = Array.isArray(data.records) ? data.records : []
      collected.push(...batch)
      total = Number(data.total) || collected.length
      priorityMatched = Number(data.priorityMatched) || 0
      priorityEmailCount = Number(data.priorityEmailCount) || priorityEmails.value.length
      if (collected.length >= total || batch.length === 0) break
      page += 1
      if (page > 20) break
    }

    rows.value = enrichRowsWithAlias(collected)
    stats.total = total
    stats.priorityMatched = priorityMatched
    stats.priorityEmailCount = priorityEmailCount
    syncOpenGroupsAfterLoad()
  } catch (error) {
    rows.value = []
    stats.total = 0
    ElMessage.error(error instanceof Error ? error.message : '加载失败')
  } finally {
    loading.list = false
  }
}

const collectCheckTargets = (includeUnlinked: boolean) => {
  const targets: string[] = []
  for (const group of aliasGroups.value) {
    if (group.key === UNLINKED_KEY && !includeUnlinked) continue
    for (const row of group.rows) {
      const email = normalizeEmail(row.email)
      if (email) targets.push(email)
    }
  }
  return [...new Set(targets)]
}

const postTokenRefresh = async (emails: string[]) => {
  const response = await fetch('/api/accounts/token-mailboxes/check', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      password: form.apiPassword,
      emails,
      concurrency: Math.min(5, Math.max(1, emails.length)),
      includeInvalid: true,
      skipCheckedWithinMinutes: 0,
    }),
  })
  const payload = await response.json()
  if (!(payload.code === '200' || payload.code === 200)) {
    throw new Error(payload.error || payload.message || '刷新失败')
  }
  return payload.data || {}
}

type LinkedInvalidExportRow = {
  email: string
  emailPassword: string
  alias: string
}

const compareLinkedInvalidExportRows = (a: LinkedInvalidExportRow, b: LinkedInvalidExportRow) => {
  const aliasA = a.alias
  const aliasB = b.alias
  if (!aliasA && aliasB) return 1
  if (aliasA && !aliasB) return -1
  const aliasCmp = aliasA.localeCompare(aliasB, 'zh-CN')
  if (aliasCmp !== 0) return aliasCmp
  return a.email.localeCompare(b.email, 'zh-CN')
}

/**
 * 导出数据源：
 * - 邮箱 / 邮箱密码：账号表单与账号记录（account_editor_rows.email / email_password）
 * - 别名：账号表 assigned_alias，缺失时从设备档案 support 反查
 * - 令牌失效：与本页「已关联 + 失效」一致（mail_token_status=invalid）
 */
const fetchLinkedInvalidExportRows = async (): Promise<LinkedInvalidExportRow[]> => {
  const [accountResp, supportResp] = await Promise.all([
    fetch('/api/admin/editor/account_rows'),
    fetch('/api/admin/editor/account_rows/support'),
  ])
  let accountPayload: {
    code?: number | string
    msg?: string
    message?: string
    items?: Array<{
      email?: string
      username?: string
      email_password?: string
      assigned_alias?: string
      mail_token_status?: string
    }>
  } | null = null
  try {
    accountPayload = await accountResp.json()
  } catch {
    throw new Error(`账号表单接口返回异常（HTTP ${accountResp.status}）`)
  }

  const code = Number(accountPayload?.code)
  if (code === 401 || accountResp.status === 401) {
    throw new Error(accountPayload?.msg || accountPayload?.message || '请先登录后再导出')
  }
  if (!accountResp.ok || code !== 0) {
    throw new Error(
      accountPayload?.msg || accountPayload?.message || `账号表单加载失败（HTTP ${accountResp.status}）`,
    )
  }

  const aliasByUsername = new Map<string, string>()
  try {
    if (supportResp.ok) {
      const supportPayload = await supportResp.json()
      if (Number(supportPayload?.code) === 0 && Array.isArray(supportPayload?.items)) {
        for (const item of supportPayload.items) {
          const username = String(item?.username || '').trim().toLowerCase()
          const alias = normalizeAlias(item?.assigned_alias || item?.device_alias)
          if (username && alias && !aliasByUsername.has(username)) {
            aliasByUsername.set(username, alias)
          }
        }
      }
    }
  } catch {
    // support endpoint is optional enrichment
  }

  const seen = new Set<string>()
  const targets: LinkedInvalidExportRow[] = []
  for (const item of accountPayload?.items || []) {
    const email = String(item?.email || '').trim()
    const emailKey = normalizeEmail(email)
    if (!emailKey || !emailKey.includes('@') || seen.has(emailKey)) continue
    if (String(item?.mail_token_status || '').trim().toLowerCase() !== 'invalid') continue
    seen.add(emailKey)
    const alias = normalizeAlias(item?.assigned_alias)
      || aliasByUsername.get(String(item?.username || '').trim().toLowerCase())
      || ''
    targets.push({
      email,
      emailPassword: String(item?.email_password || '').trim(),
      alias,
    })
  }
  return targets
}

const exportLinkedInvalid = async () => {
  if (loading.export) return
  loading.export = true
  try {
    const targets = await fetchLinkedInvalidExportRows()
    if (!targets.length) {
      ElMessage.warning('没有「账号表单已关联且令牌失效」的邮箱可导出')
      return
    }

    const missingPassword = targets.filter((row) => !row.emailPassword).length
    try {
      await ElMessageBox.confirm(
        `将导出 ${targets.length} 条「已关联 + 令牌失效」账号为表格：A 列邮箱----密码，B 列别名（同别名相邻）。${
          missingPassword > 0 ? `\n其中 ${missingPassword} 条缺少邮箱密码，仍会导出为空密码。` : ''
        }`,
        '导出确认',
        { type: 'warning', confirmButtonText: '导出', cancelButtonText: '取消' },
      )
    } catch {
      return
    }

    const sorted = [...targets].sort(compareLinkedInvalidExportRows)
    const sheetData = [
      ['邮箱----密码', '别名'],
      ...sorted.map((row) => [`${row.email}----${row.emailPassword}`, row.alias]),
    ]
    const worksheet = XLSX.utils.aoa_to_sheet(sheetData)
    worksheet['!cols'] = [{ wch: 48 }, { wch: 20 }]
    const workbook = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(workbook, worksheet, '已关联失效')
    const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')
    XLSX.writeFile(workbook, `linked_invalid_mails_${stamp}.xlsx`)
    ElMessage.success(`已导出 ${targets.length} 条`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败')
  } finally {
    loading.export = false
  }
}

const runSingleRefresh = async (row: TokenMailboxRow) => {
  const email = normalizeEmail(row.email)
  if (!email) {
    ElMessage.warning('邮箱无效')
    return
  }
  if (loading.check || refreshingEmail.value) return

  refreshingEmail.value = email
  try {
    const data = await postTokenRefresh([email])
    const status = String(data.results?.[0]?.tokenStatus || '').toLowerCase()
    if (status === 'valid') {
      ElMessage.success(`${email} 令牌刷新成功`)
    } else if (status === 'invalid') {
      ElMessage.error(`${email} 令牌已失效`)
    } else if (status === 'transient') {
      ElMessage.warning(`${email} 临时失败，可稍后重试`)
    } else if (Number(data.matched) === 0) {
      ElMessage.warning(`${email} 未找到可刷新的令牌账号`)
    } else {
      ElMessage.success(`${email} 刷新完成`)
    }
    await loadList()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '刷新失败')
  } finally {
    refreshingEmail.value = ''
  }
}

const runBatchRefresh = async () => {
  const includeUnlinked = !form.collapseUnlinked
  const targets = collectCheckTargets(includeUnlinked)
  const linkedCount = collectCheckTargets(false).length
  const unlinkedSkipped = Math.max(0, collectCheckTargets(true).length - linkedCount)

  if (!targets.length) {
    ElMessage.warning('当前没有可刷新的邮箱（可关闭「仅账号表单中的邮箱」）')
    return
  }

  try {
    await ElMessageBox.confirm(
      includeUnlinked
        ? `将全量刷新全部 ${targets.length} 个邮箱的 OAuth 令牌（含未关联）。过程可能较久，确认开始？`
        : `将全量刷新 ${linkedCount} 个「账号表单已关联」邮箱的 OAuth 令牌。${
            unlinkedSkipped > 0 ? `\n另有 ${unlinkedSkipped} 个未关联邮箱已跳过（取消勾选「折叠未关联邮箱」可一并刷新）。` : ''
          }\n过程可能较久，确认开始？`,
      '全量刷新令牌',
      { type: 'warning', confirmButtonText: '开始刷新', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  loading.check = true
  checkProgress.active = true
  checkProgress.done = 0
  checkProgress.total = targets.length
  checkProgress.valid = 0
  checkProgress.invalid = 0
  checkProgress.transient = 0

  try {
    for (let i = 0; i < targets.length; i += CHECK_BATCH_SIZE) {
      const batch = targets.slice(i, i + CHECK_BATCH_SIZE)
      const data = await postTokenRefresh(batch)
      checkProgress.valid += Number(data.validCount) || 0
      checkProgress.invalid += Number(data.invalidCount) || 0
      checkProgress.transient += Number(data.transientCount) || 0
      // Count by requested batch size so unmatched emails do not stall the progress bar.
      checkProgress.done = Math.min(targets.length, i + batch.length)
    }

    ElMessage.success(
      `刷新完成：正常 ${checkProgress.valid}，失效 ${checkProgress.invalid}，临时失败 ${checkProgress.transient}`,
    )
    await loadList()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '刷新失败')
  } finally {
    loading.check = false
    checkProgress.active = false
  }
}

onMounted(() => {
  void loadList(true)
  void loadAutoRefreshStatus()
})
</script>

<style scoped>
.token-mailboxes-page {
  padding: 20px;
  background: #f6f7f9;
  min-height: calc(100vh - 61px);
}

.toolbar-band,
.panel {
  margin-bottom: 16px;
  padding: 16px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fff;
}

.page-header {
  margin-bottom: 12px;
}

.auto-refresh-status {
  margin: 6px 0 0;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}

.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.page-desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: #909399;
}

.toolbar-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
}

.check-progress {
  margin-top: 8px;
  font-size: 13px;
  color: #e6a23c;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.summary-card {
  padding: 14px 16px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fff;
}

.summary-label {
  font-size: 13px;
  color: #909399;
}

.summary-value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.alias-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 120px;
}

.alias-group {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background: #fff;
  overflow: hidden;
}

.alias-group-unlinked {
  border-style: dashed;
  background: #fafafa;
}

.alias-group-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  user-select: none;
  list-style: none;
  background: #f5f7fa;
}

.alias-group-summary::-webkit-details-marker {
  display: none;
}

.alias-group-title {
  font-weight: 600;
  color: #303133;
}

.email-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.token-status-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.token-status-hint {
  font-size: 12px;
  color: #909399;
}

.token-error {
  color: #f56c6c;
}

:deep(.linked-account-row) {
  background: #f0f9eb;
}

:deep(.token-invalid-row) {
  background: #fef0f0;
}

@media (max-width: 1100px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
