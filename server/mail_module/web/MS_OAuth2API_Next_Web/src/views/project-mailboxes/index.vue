<template>
  <div class="project-mailboxes-page">
    <section class="toolbar-band">
      <el-form :model="form" inline class="toolbar-form">
        <el-form-item label="接口密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="服务器接口密码"
            class="password-input"
          />
        </el-form-item>
        <el-form-item label="项目筛选">
          <el-input v-model="form.projectFilter" clearable placeholder="项目标记或名称" class="filter-input" />
        </el-form-item>
        <el-form-item label="明细状态">
          <el-select v-model="form.status" clearable placeholder="全部状态" class="status-select">
            <el-option label="已分配" value="assigned" />
            <el-option label="已检查" value="checked" />
            <el-option label="已接收" value="received" />
            <el-option label="失败" value="error" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading.pools" @click="loadPools">刷新项目池</el-button>
          <el-button :disabled="!selectedProject" :loading="loading.mailboxes" @click="reloadSelected">刷新明细</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="summary-grid">
      <div class="summary-item">
        <div class="summary-value">{{ filteredPools.length }}</div>
        <div class="summary-label">项目数</div>
      </div>
      <div class="summary-item">
        <div class="summary-value">{{ totalKnown }}</div>
        <div class="summary-label">项目邮箱</div>
      </div>
      <div class="summary-item">
        <div class="summary-value">{{ totalReusable }}</div>
        <div class="summary-label">可复用</div>
      </div>
      <div class="summary-item">
        <div class="summary-value">{{ totalNew }}</div>
        <div class="summary-label">可新取</div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-title">全项目接码邮箱池</div>
      <el-table
        :data="filteredPools"
        size="small"
        stripe
        highlight-current-row
        :row-class-name="projectRowClassName"
        @row-click="selectProject"
      >
        <el-table-column prop="projectKey" label="项目标记" width="110" />
        <el-table-column prop="projectName" label="项目名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="发件人分类" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            {{ senderLabelText(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="knownMailboxCount" label="项目邮箱" width="95" />
        <el-table-column prop="newAvailableCount" label="可新取" width="85" />
        <el-table-column prop="reusableReceivedCount" label="可复用" width="85" />
        <el-table-column prop="retryableCount" label="可二次" width="85" />
        <el-table-column prop="assignedCount" label="已分配" width="85" />
        <el-table-column prop="checkedCount" label="已检查" width="85" />
        <el-table-column prop="receivedCount" label="已接收" width="85" />
        <el-table-column prop="errorCount" label="失败" width="75" />
        <el-table-column label="操作" width="300">
          <template #default="{ row }">
            <div class="action-row">
              <el-button size="small" type="primary" :loading="actionKey === row.projectKey + ':new'" @click.stop="acquireProject(row, 'new')">取新</el-button>
              <el-button size="small" type="success" :loading="actionKey === row.projectKey + ':received'" @click.stop="acquireProject(row, 'received')">复用</el-button>
              <el-button size="small" type="warning" :loading="actionKey === row.projectKey + ':retry'" @click.stop="acquireProject(row, 'retry')">二次</el-button>
              <el-button size="small" :loading="loading.mailboxes && selectedProjectKey === row.projectKey" @click.stop="loadProjectMailboxes(row)">明细</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel">
      <div class="panel-title">当前项目取码</div>
      <el-form :model="codeForm" inline class="code-form">
        <el-form-item label="项目">
          <el-input :model-value="selectedProjectTitle" readonly class="project-input" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="codeForm.email" clearable placeholder="先取邮箱或从明细选择" class="email-input" />
        </el-form-item>
        <el-form-item label="拉取封数">
          <el-input-number v-model="codeForm.top" :min="1" :max="200" controls-position="right" :disabled="codeForm.fullScan" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="codeForm.fullScan">完整扫描</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="warning" :disabled="!selectedProject || !codeForm.email" :loading="loading.code" @click="fetchCode()">获取验证码</el-button>
          <el-button :disabled="!resultText" @click="copyText(resultText)">复制结果</el-button>
        </el-form-item>
      </el-form>
      <div class="code-result">
        <div class="code-card">
          <span class="code-label">验证码</span>
          <strong>{{ latestCode || '-' }}</strong>
        </div>
        <div class="code-card">
          <span class="code-label">状态</span>
          <strong>{{ latestStatus || '-' }}</strong>
        </div>
        <div class="code-card wide">
          <span class="code-label">邮箱</span>
          <strong>{{ latestEmail || '-' }}</strong>
        </div>
        <div class="code-card wide link-card">
          <span class="code-label">{{ latestLinkText || '链接' }}</span>
          <strong>{{ latestLink || '-' }}</strong>
          <el-button v-if="latestLink" size="small" type="success" @click="copyText(latestLink)">复制</el-button>
        </div>
      </div>
      <div v-if="resultData" class="fetch-meta">
        <el-tag size="small" type="info">扫描 {{ resultData.messageCount || 0 }} 封</el-tag>
        <el-tag size="small" type="success">命中 {{ resultData.matchedCount || 0 }} 封</el-tag>
        <el-tag size="small" type="warning">补拉 {{ resultData.hydratedCount || 0 }} 封正文</el-tag>
        <el-tag size="small" type="info">耗时 {{ resultData.elapsedMs || 0 }} ms</el-tag>
      </div>
      <el-alert
        v-if="receivedWithoutCodeOrLink"
        class="code-alert"
        type="warning"
        show-icon
        :closable="false"
        title="已收到项目邮件，但暂未提取到验证码或链接"
      />
      <div v-if="matchedMessages.length" class="matched-block">
        <div class="sub-title">匹配邮件</div>
        <el-table :data="matchedMessages" size="small" border max-height="260">
          <el-table-column prop="send" label="发件人" min-width="230" show-overflow-tooltip />
          <el-table-column prop="subject" label="主题" min-width="220" show-overflow-tooltip />
          <el-table-column label="位置" width="90">
            <template #default="{ row }">{{ mailboxText(row.mailbox) }}</template>
          </el-table-column>
          <el-table-column label="验证码" width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.verificationCode || '-' }}</template>
          </el-table-column>
          <el-table-column label="链接" width="90">
            <template #default="{ row }">
              <el-button v-if="messageLink(row)" size="small" type="success" @click="copyText(messageLink(row))">{{ messageLinkText(row) }}</el-button>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="verificationSource" label="识别依据" width="110" show-overflow-tooltip />
          <el-table-column prop="date" label="日期" width="175" show-overflow-tooltip />
        </el-table>
      </div>
    </section>

    <section class="panel">
      <div class="panel-title">项目邮箱明细</div>
      <el-table :data="mailboxes" size="small" stripe v-loading="loading.mailboxes">
        <el-table-column prop="email" label="邮箱" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="assignedCount" label="分配次数" width="90" />
        <el-table-column label="项目发件人" width="105">
          <template #default="{ row }">
            <el-tag size="small" :type="row.receivedByProjectSender ? 'success' : 'info'">
              {{ row.receivedByProjectSender ? '收到过' : '未确认' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latestCode" label="最近验证码" width="120" show-overflow-tooltip />
        <el-table-column prop="latestSender" label="最近发件人" min-width="180" show-overflow-tooltip />
        <el-table-column prop="latestReceivedAt" label="最近接收" min-width="170" show-overflow-tooltip />
        <el-table-column prop="lastCheckedAt" label="最近检查" min-width="170" show-overflow-tooltip />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" @click="useMailbox(row)">选择</el-button>
            <el-button size="small" type="warning" :loading="codeKey === row.email" @click="fetchCode(row)">取码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel">
      <div class="panel-title">接口返回</div>
      <pre class="result-box">{{ resultText }}</pre>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

type AcquireMode = 'new' | 'received' | 'retry'

interface ProjectMailbox {
  projectKey: string
  email: string
  active: boolean
  status: string
  markStatus: string
  assignedCount: number
  latestCode: string
  latestSender: string
  latestMailbox: string
  latestReceivedAt: string
  lastError: string
  lastCheckedAt: string
  receivedByProjectSender: boolean
  projectSenderMessageCount: number
  projectSenderLatestDate: string
}

interface ProjectMessage {
  id?: string
  send: string
  subject: string
  mailbox: string
  mailboxLabel?: string
  verificationCode?: string
  verificationSource?: string
  linkDetails?: Array<{ url: string, text?: string, label?: string }>
  links?: string[]
  verificationLinks?: string[]
  primaryLink?: string
  primaryLinkText?: string
  primaryLinkLabel?: string
  date: string
}

interface ProjectPool {
  projectKey: string
  projectName: string
  senderCompanyIdentity: string
  senderCompanyLabel: string
  senderCompanyIdentities?: string[]
  senderCompanyLabels?: string[]
  knownMailboxCount: number
  markedCount: number
  assignedCount: number
  checkedCount: number
  receivedCount: number
  errorCount: number
  reusableReceivedCount: number
  retryableCount: number
  newAvailableCount: number
  latestUpdatedAt: string
  mailboxes?: ProjectMailbox[]
}

const form = reactive({
  password: localStorage.getItem('apiPassword') || '',
  projectFilter: '',
  status: '',
})

const codeForm = reactive({
  email: '',
  top: 50,
  fullScan: false,
})

const loading = reactive({
  pools: false,
  mailboxes: false,
  code: false,
})

const pools = ref<ProjectPool[]>([])
const mailboxes = ref<ProjectMailbox[]>([])
const selectedProjectKey = ref('')
const result = ref<unknown>(null)
const actionKey = ref('')
const codeKey = ref('')

watch(() => form.password, value => {
  localStorage.setItem('apiPassword', value)
})

const filteredPools = computed(() => {
  const keyword = form.projectFilter.trim().toLowerCase()
  if (!keyword) return pools.value
  return pools.value.filter(item => (
    item.projectKey.toLowerCase().includes(keyword)
    || item.projectName.toLowerCase().includes(keyword)
    || senderLabelText(item).toLowerCase().includes(keyword)
  ))
})

const selectedProject = computed(() => pools.value.find(item => item.projectKey === selectedProjectKey.value) || null)
const selectedProjectTitle = computed(() => selectedProject.value ? `${selectedProject.value.projectKey} / ${selectedProject.value.projectName}` : '')
const totalKnown = computed(() => filteredPools.value.reduce((sum, item) => sum + (item.knownMailboxCount || 0), 0))
const totalReusable = computed(() => filteredPools.value.reduce((sum, item) => sum + (item.reusableReceivedCount || 0), 0))
const totalNew = computed(() => filteredPools.value.reduce((sum, item) => sum + (item.newAvailableCount || 0), 0))

const resultData = computed(() => result.value as Record<string, unknown> | null)
const latestCode = computed(() => String(resultData.value?.latestCode || resultData.value?.verificationCode || ''))
const latestLink = computed(() => String(resultData.value?.latestLink || resultData.value?.verificationLink || ''))
const latestLinkText = computed(() => String(resultData.value?.latestLinkText || resultData.value?.verificationLinkText || ''))
const latestStatus = computed(() => String(resultData.value?.status || resultData.value?.markStatus || ''))
const latestEmail = computed(() => String(resultData.value?.email || codeForm.email || ''))
const resultText = computed(() => result.value ? JSON.stringify(result.value, null, 2) : '')
const matchedMessages = computed<ProjectMessage[]>(() => {
  const matched = resultData.value?.matched
  return Array.isArray(matched) ? matched as ProjectMessage[] : []
})
const receivedWithoutCodeOrLink = computed(() => Boolean(resultData.value?.received || Number(resultData.value?.matchedCount || 0) > 0) && !latestCode.value && !latestLink.value)

const messageLink = (message: ProjectMessage) => {
  return message.primaryLink
    || message.linkDetails?.[0]?.url
    || message.verificationLinks?.[0]
    || message.links?.[0]
    || ''
}

const messageLinkText = (message: ProjectMessage) => {
  return message.primaryLinkText
    || message.primaryLinkLabel
    || message.linkDetails?.find(link => link.url === messageLink(message))?.text
    || message.linkDetails?.find(link => link.url === messageLink(message))?.label
    || '复制'
}

const senderLabelText = (project: ProjectPool) => {
  const labels = Array.isArray(project.senderCompanyLabels) && project.senderCompanyLabels.length > 0
    ? project.senderCompanyLabels
    : [project.senderCompanyLabel].filter(Boolean)
  const identities = Array.isArray(project.senderCompanyIdentities) && project.senderCompanyIdentities.length > 0
    ? project.senderCompanyIdentities
    : [project.senderCompanyIdentity].filter(Boolean)
  return labels.length > 0 ? labels.join(' / ') : identities.join(' / ')
}

const projectPayload = (project: ProjectPool) => ({
  project: project.projectKey,
  name: project.projectName,
  senderCompanyIdentities: project.senderCompanyIdentities || [],
  senderCompanyLabels: project.senderCompanyLabels || [],
})

const postJson = async <T,>(url: string, body: Record<string, unknown>): Promise<T> => {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      password: form.password,
      ...body,
    }),
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok || !(payload.code === '200' || payload.code === 200)) {
    throw new Error(payload.error || payload.message || `请求失败 ${response.status}`)
  }
  return payload.data as T
}

const loadPools = async () => {
  if (!form.password) {
    ElMessage.warning('请先填写接口密码')
    return
  }
  loading.pools = true
  try {
    const data = await postJson<ProjectPool[]>('/api/project/mailboxes', {
      includeRows: false,
      limit: 100,
    })
    pools.value = Array.isArray(data) ? data : []
    if (!selectedProjectKey.value && pools.value.length > 0) {
      selectedProjectKey.value = pools.value[0]?.projectKey || ''
    }
    if (selectedProject.value) {
      await loadProjectMailboxes(selectedProject.value)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '项目邮箱池加载失败')
  } finally {
    loading.pools = false
  }
}

const loadProjectMailboxes = async (project: ProjectPool) => {
  if (!form.password) {
    ElMessage.warning('请先填写接口密码')
    return
  }
  selectedProjectKey.value = project.projectKey
  loading.mailboxes = true
  try {
    const data = await postJson<ProjectPool[]>('/api/project/mailboxes', {
      ...projectPayload(project),
      status: form.status,
      includeRows: true,
      limit: 500,
    })
    const pool = Array.isArray(data) ? data[0] : null
    mailboxes.value = pool?.mailboxes || []
    const index = pools.value.findIndex(item => item.projectKey === project.projectKey)
    if (pool && index >= 0) pools.value[index] = { ...pools.value[index], ...pool, mailboxes: [] }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '项目邮箱明细加载失败')
  } finally {
    loading.mailboxes = false
  }
}

const reloadSelected = async () => {
  if (!selectedProject.value) return
  await loadProjectMailboxes(selectedProject.value)
}

const selectProject = async (project: ProjectPool) => {
  selectedProjectKey.value = project.projectKey
  await loadProjectMailboxes(project)
}

const acquireProject = async (project: ProjectPool, mode: AcquireMode) => {
  if (!form.password) {
    ElMessage.warning('请先填写接口密码')
    return
  }
  selectedProjectKey.value = project.projectKey
  actionKey.value = `${project.projectKey}:${mode}`
  try {
    const data = await postJson<Record<string, unknown>>('/api/project/acquire', {
      ...projectPayload(project),
      mode,
    })
    result.value = data
    const email = String((data.account as Record<string, unknown> | undefined)?.email || data.email || '')
    if (email) codeForm.email = email
    await loadProjectMailboxes(project)
    ElMessage.success(email ? `已获取 ${email}` : '没有可用邮箱')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取项目邮箱失败')
  } finally {
    actionKey.value = ''
  }
}

const fetchCode = async (mailbox?: ProjectMailbox) => {
  const project = selectedProject.value
  const email = mailbox?.email || codeForm.email
  if (!project || !email) {
    ElMessage.warning('请先选择项目和邮箱')
    return
  }
  loading.code = true
  codeKey.value = mailbox?.email || ''
  codeForm.email = email
  try {
    const data = await postJson<Record<string, unknown>>('/api/project/code', {
      ...projectPayload(project),
      email,
      top: codeForm.fullScan ? 0 : codeForm.top,
    })
    result.value = data
    await loadProjectMailboxes(project)
    if (data.latestCode || data.verificationCode) {
      ElMessage.success(`验证码 ${data.latestCode || data.verificationCode}`)
    } else if (data.latestLink || data.verificationLink) {
      ElMessage.success('已获取验证链接')
    } else if (data.received || Number(data.matchedCount || 0) > 0) {
      ElMessage.warning('已收到项目邮件，但暂未提取到验证码或链接')
    } else {
      ElMessage.warning('暂未匹配到验证码或链接')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取验证码失败')
  } finally {
    loading.code = false
    codeKey.value = ''
  }
}

const useMailbox = (mailbox: ProjectMailbox) => {
  codeForm.email = mailbox.email
}

const copyText = async (text: string) => {
  if (!text) return
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

const statusText = (status: string) => ({
  assigned: '已分配',
  checked: '已检查',
  received: '已接收',
  error: '失败',
  unmarked: '未标记',
}[status] || status || '-')

const statusTag = (status: string) => ({
  assigned: 'primary',
  checked: 'info',
  received: 'success',
  error: 'danger',
}[status] || 'info')

const mailboxText = (mailbox = '') => ({
  INBOX: '收件箱',
  Junk: '垃圾箱',
  Deleted: '已删除',
  Archive: '归档',
  Sent: '已发送',
  Drafts: '草稿',
}[mailbox] || mailbox || '-')

const projectRowClassName = ({ row }: { row: ProjectPool }) => (
  row.projectKey === selectedProjectKey.value ? 'selected-project-row' : ''
)

onMounted(() => {
  if (form.password) loadPools()
})
</script>

<style scoped>
.project-mailboxes-page {
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

.toolbar-form {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
}

.password-input {
  width: 240px;
}

.filter-input {
  width: 220px;
}

.status-select {
  width: 150px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.summary-item {
  padding: 14px 16px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fff;
}

.summary-value {
  font-size: 24px;
  line-height: 1.2;
  font-weight: 700;
  color: #1f2937;
}

.summary-label {
  margin-top: 4px;
  font-size: 13px;
  color: #606266;
}

.panel-title {
  margin-bottom: 14px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.action-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.action-row :deep(.el-button + .el-button) {
  margin-left: 0;
}

.code-form {
  display: flex;
  flex-wrap: wrap;
}

.project-input {
  width: 260px;
}

.email-input {
  width: 320px;
}

.code-result {
  display: grid;
  grid-template-columns: 160px 160px minmax(220px, 1fr);
  gap: 12px;
  margin-top: 4px;
}

.fetch-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.code-alert {
  margin-top: 10px;
}

.matched-block {
  margin-top: 12px;
}

.sub-title {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.code-card {
  min-height: 64px;
  padding: 10px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fafafa;
}

.code-card strong {
  display: block;
  margin-top: 5px;
  overflow-wrap: anywhere;
  font-size: 18px;
  color: #111827;
}

.link-card {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: 10px;
}

.link-card strong {
  flex: 1;
  min-width: 0;
  font-size: 13px;
}

.code-label {
  font-size: 12px;
  color: #606266;
}

.result-box {
  min-height: 160px;
  max-height: 360px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  background: #f5f7fa;
  color: #303133;
  white-space: pre-wrap;
}

:deep(.selected-project-row > td) {
  background: #edf5ff !important;
}

@media (max-width: 900px) {
  .summary-grid,
  .code-result {
    grid-template-columns: 1fr 1fr;
  }

  .project-input,
  .email-input,
  .password-input,
  .filter-input {
    width: 100%;
  }
}

@media (max-width: 560px) {
  .project-mailboxes-page {
    padding: 12px;
  }

  .summary-grid,
  .code-result {
    grid-template-columns: 1fr;
  }
}
</style>
