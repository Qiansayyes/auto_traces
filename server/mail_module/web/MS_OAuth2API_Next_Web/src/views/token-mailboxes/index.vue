<template>
  <div class="token-mailboxes-page">
    <section class="toolbar-band">
      <div class="page-header">
        <div>
          <h2 class="page-title">令牌邮箱管理</h2>
          <p class="page-desc">
            专门管理已配置 OAuth 令牌的邮箱；账号表单与账号记录中已存在的邮箱会优先排在前面。
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
            仅账号记录中的邮箱
          </el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading.list" @click="loadList(true)">刷新列表</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="summary-grid">
      <div class="summary-card">
        <div class="summary-label">令牌邮箱</div>
        <div class="summary-value">{{ stats.total }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">账号记录优先命中</div>
        <div class="summary-value">{{ stats.priorityMatched }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">账号记录邮箱数</div>
        <div class="summary-value">{{ stats.priorityEmailCount }}</div>
      </div>
    </section>

    <section class="panel">
      <el-table
        v-loading="loading.list"
        :data="rows"
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
        <el-table-column label="账号记录" width="120">
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
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.pageSize"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[20, 50, 100, 200]"
          :total="pagination.total"
          @current-change="loadList()"
          @size-change="loadList(true)"
        />
      </div>
    </section>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

interface TokenMailboxRow {
  email: string
  token_status?: string
  last_token_error?: string
  last_token_checked_at?: string
  updated_at?: string
  in_account_records?: boolean
}

const form = reactive({
  apiPassword: localStorage.getItem('apiPassword') || '',
  search: '',
  tokenStatus: localStorage.getItem('tokenMailboxStatus') || 'all',
  onlyInAccountRecords: localStorage.getItem('tokenMailboxOnlyLinked') === '1',
})

const loading = reactive({
  list: false,
})

const stats = reactive({
  total: 0,
  priorityMatched: 0,
  priorityEmailCount: 0,
})

const rows = ref<TokenMailboxRow[]>([])
const priorityEmails = ref<string[]>([])

const pagination = reactive({
  page: 1,
  pageSize: 50,
  total: 0,
})

watch(() => form.apiPassword, (value) => localStorage.setItem('apiPassword', value))
watch(() => form.tokenStatus, (value) => localStorage.setItem('tokenMailboxStatus', value || 'all'))
watch(
  () => form.onlyInAccountRecords,
  (value) => localStorage.setItem('tokenMailboxOnlyLinked', value ? '1' : '0'),
)

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

const copyText = async (text: string, successMessage: string) => {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(successMessage)
  } catch {
    ElMessage.error('复制失败')
  }
}

type PriorityLoadResult =
  | { ok: true; emails: string[] }
  | { ok: false; emails: []; error: string }

const loadPriorityEmails = async (): Promise<PriorityLoadResult> => {
  try {
    const response = await fetch('/api/admin/editor/account_rows')
    let payload: { code?: number | string; msg?: string; message?: string; items?: Array<{ email?: string }> } | null = null
    try {
      payload = await response.json()
    } catch {
      return {
        ok: false,
        emails: [],
        error: `账号记录接口返回异常（HTTP ${response.status}）`,
      }
    }

    const code = Number(payload?.code)
    if (code === 401 || response.status === 401) {
      return {
        ok: false,
        emails: [],
        error: payload?.msg || payload?.message || '请先登录后再加载账号记录优先邮箱',
      }
    }
    if (!response.ok || code !== 0) {
      return {
        ok: false,
        emails: [],
        error: payload?.msg || payload?.message || `账号记录加载失败（HTTP ${response.status}）`,
      }
    }

    const seen = new Set<string>()
    const emails: string[] = []
    for (const item of payload?.items || []) {
      const email = String(item?.email || '').trim().toLowerCase()
      if (!email || !email.includes('@') || seen.has(email)) continue
      seen.add(email)
      emails.push(email)
    }
    return { ok: true, emails }
  } catch (error) {
    return {
      ok: false,
      emails: [],
      error: error instanceof Error ? error.message : '账号记录优先邮箱加载失败',
    }
  }
}

const loadList = async (resetPage = false) => {
  if (resetPage) pagination.page = 1
  loading.list = true
  try {
    const priorityResult = await loadPriorityEmails()
    if (!priorityResult.ok) {
      priorityEmails.value = []
      // Avoid treating auth/network failure as "no linked emails" (empty filter / all 未关联).
      if (form.onlyInAccountRecords) {
        ElMessage.error(`${priorityResult.error}；已停止本次「仅账号记录中的邮箱」筛选，避免误清空列表`)
        return
      }
      ElMessage.warning(`${priorityResult.error}；已关联标记与优先排序可能不准`)
    } else {
      priorityEmails.value = priorityResult.emails
    }

    const response = await fetch('/api/accounts/token-mailboxes/list', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        password: form.apiPassword,
        page: pagination.page,
        pageSize: pagination.pageSize,
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
    rows.value = Array.isArray(data.records) ? data.records : []
    pagination.total = Number(data.total) || 0
    stats.total = Number(data.total) || 0
    stats.priorityMatched = Number(data.priorityMatched) || 0
    stats.priorityEmailCount = Number(data.priorityEmailCount) || priorityEmails.value.length
  } catch (error) {
    rows.value = []
    pagination.total = 0
    ElMessage.error(error instanceof Error ? error.message : '加载失败')
  } finally {
    loading.list = false
  }
}

onMounted(() => {
  void loadList(true)
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

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
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

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

:deep(.linked-account-row) {
  background: #f0f9eb;
}

:deep(.token-invalid-row) {
  background: #fef0f0;
}

@media (max-width: 900px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
