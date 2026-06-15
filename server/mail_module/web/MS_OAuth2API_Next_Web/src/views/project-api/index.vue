<template>
  <div class="project-api-page">
    <section class="panel">
      <div class="panel-title">项目接口配置</div>
      <el-form :model="form" label-width="120px" class="project-form">
        <el-form-item label="接口密码">
          <el-input v-model="form.password" type="password" show-password placeholder="服务器接口密码" />
        </el-form-item>
        <el-form-item label="项目标记">
          <el-input v-model="form.project" placeholder="例如 1" />
        </el-form-item>
        <el-form-item label="项目名称">
          <el-input v-model="form.name" placeholder="例如 A项目" />
        </el-form-item>
        <el-form-item label="发件人分类">
          <el-select
            v-model="form.senderCompanyIdentities"
            multiple
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="2"
            filterable
            clearable
            :loading="loading.senders"
            placeholder="可以选择一个或多个发件人分类"
            class="full-control"
            @change="handleSenderChange"
          >
            <el-option
              v-for="sender in senderOptions"
              :key="sender.value"
              :label="sender.label"
              :value="sender.value"
            >
              <div class="sender-option">
                <span>{{ sender.label }}</span>
                <span class="sender-count">{{ sender.count || 0 }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="取号模式">
          <el-radio-group v-model="form.mode">
            <el-radio-button
              v-for="item in acquireModeOptions"
              :key="item.value"
              :label="item.value"
            >
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="当前邮箱">
          <el-input v-model="form.email" placeholder="取号后会自动填入，也可以手动填入" />
        </el-form-item>
        <el-form-item label="取件封数">
          <el-input-number v-model="form.mailTop" :min="1" :max="200" controls-position="right" :disabled="form.fullScan" />
        </el-form-item>
        <el-form-item label="扫描方式">
          <el-checkbox v-model="form.fullScan">完整扫描</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading.save" @click="saveProject">保存项目</el-button>
          <el-button type="success" :loading="loading.acquire" @click="acquireEmail">获取项目邮箱</el-button>
          <el-button type="warning" :loading="loading.mail" :disabled="!form.email" @click="fetchMail">获取此邮箱验证码</el-button>
          <el-button :loading="loading.projects" @click="loadProjects">刷新项目</el-button>
          <el-button :loading="loading.senders" @click="loadSenderOptions">刷新发件人</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="panel">
      <div class="panel-title">调用链接</div>
      <div class="link-row">
        <span>获取项目邮箱</span>
        <el-input :model-value="acquireUrl" readonly />
        <el-button @click="copyText(acquireUrl)">复制</el-button>
      </div>
      <div class="link-row">
        <span>获取邮箱验证码</span>
        <el-input :model-value="mailUrl" readonly />
        <el-button @click="copyText(mailUrl)">复制</el-button>
      </div>
    </section>

    <section class="panel">
      <div class="panel-title">结果</div>
      <div v-if="resultData" class="result-summary">
        <div class="summary-card">
          <span>验证码</span>
          <strong>{{ latestCode || '-' }}</strong>
        </div>
        <div class="summary-card">
          <span>命中邮件</span>
          <strong>{{ resultData.matchedCount || 0 }}</strong>
        </div>
        <div class="summary-card">
          <span>扫描邮件</span>
          <strong>{{ resultData.messageCount || 0 }}</strong>
        </div>
      </div>
      <el-alert
        v-if="receivedWithoutCode"
        class="result-alert"
        type="warning"
        show-icon
        :closable="false"
        title="已收到项目邮件，但暂未提取到验证码"
      />
      <el-table v-if="matchedMessages.length" :data="matchedMessages" size="small" border max-height="260" class="matched-table">
        <el-table-column prop="send" label="发件人" min-width="230" show-overflow-tooltip />
        <el-table-column prop="subject" label="主题" min-width="220" show-overflow-tooltip />
        <el-table-column label="位置" width="90">
          <template #default="{ row }">{{ mailboxText(row.mailbox) }}</template>
        </el-table-column>
        <el-table-column label="验证码" width="120">
          <template #default="{ row }">{{ row.verificationCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="verificationSource" label="识别依据" width="110" show-overflow-tooltip />
        <el-table-column prop="date" label="日期" width="175" show-overflow-tooltip />
      </el-table>
      <pre class="result-box">{{ resultText }}</pre>
    </section>

    <section class="panel">
      <div class="panel-title">项目状态</div>
      <el-table :data="projects" size="small" stripe>
        <el-table-column prop="projectKey" label="标记" width="120" />
        <el-table-column prop="projectName" label="项目" min-width="160" />
        <el-table-column prop="senderCompanyLabel" label="绑定发件人" min-width="180" show-overflow-tooltip />
        <el-table-column prop="senderCompanyIdentity" label="发件人标识" min-width="220" show-overflow-tooltip />
        <el-table-column prop="assignedCount" label="已分配" width="100" />
        <el-table-column prop="checkedCount" label="已检查" width="100" />
        <el-table-column prop="receivedCount" label="已接收" width="100" />
        <el-table-column prop="errorCount" label="失败" width="100" />
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

interface ProjectRecord {
  projectKey: string
  projectName: string
  senderCompanyIdentity: string
  senderCompanyLabel: string
  senderCompanyIdentities?: string[]
  senderCompanyLabels?: string[]
  assignedCount: number
  checkedCount: number
  receivedCount: number
  errorCount: number
}

interface SenderOption {
  label: string
  value: string
  count?: number
}

interface ProjectMessage {
  send: string
  subject: string
  mailbox: string
  verificationCode?: string
  verificationSource?: string
  date: string
}

const form = reactive({
  password: localStorage.getItem('apiPassword') || '',
  project: '1',
  name: 'A项目',
  senderCompanyIdentities: [] as string[],
  senderCompanyLabels: [] as string[],
  mode: 'new',
  email: '',
  mailTop: 50,
  fullScan: false,
})

const loading = reactive({
  save: false,
  acquire: false,
  mail: false,
  projects: false,
  senders: false,
})

const projects = ref<ProjectRecord[]>([])
const senderOptions = ref<SenderOption[]>([])
const result = ref<unknown>(null)

const acquireModeOptions = [
  { label: '新邮箱', value: 'new' },
  { label: '复用已接收', value: 'received' },
  { label: '二次重试', value: 'retry' },
]

watch(() => form.password, value => {
  localStorage.setItem('apiPassword', value)
})

const selectedSenders = computed(() => senderOptions.value.filter(item => form.senderCompanyIdentities.includes(item.value)))
const origin = computed(() => window.location.origin)

const buildProjectPayload = () => ({
  password: form.password,
  project: form.project,
  name: form.name,
  senderCompanyIdentities: form.senderCompanyIdentities,
  senderCompanyLabels: selectedSenders.value.map(item => item.label),
})

const baseParams = computed(() => {
  const params = new URLSearchParams()
  params.set('password', form.password)
  params.set('project', form.project)
  if (form.name) params.set('name', form.name)
  if (form.senderCompanyIdentities.length > 0) {
    params.set('senderCompanyIdentities', form.senderCompanyIdentities.join(','))
    params.set('senderCompanyLabels', selectedSenders.value.map(item => item.label).join(','))
  }
  return params
})

const acquireParams = computed(() => {
  const params = new URLSearchParams(baseParams.value)
  params.set('mode', form.mode)
  return params
})

const acquireUrl = computed(() => `${origin.value}/api/project/acquire?${acquireParams.value.toString()}`)
const mailUrl = computed(() => {
  const params = new URLSearchParams(baseParams.value)
  if (form.email) params.set('email', form.email)
  params.set('top', form.fullScan ? '0' : String(form.mailTop || 50))
  return `${origin.value}/api/project/code?${params.toString()}`
})

const resultText = computed(() => result.value ? JSON.stringify(result.value, null, 2) : '')
const resultData = computed(() => result.value as Record<string, unknown> | null)
const latestCode = computed(() => String(resultData.value?.latestCode || resultData.value?.verificationCode || ''))
const matchedMessages = computed<ProjectMessage[]>(() => {
  const matched = resultData.value?.matched
  return Array.isArray(matched) ? matched as ProjectMessage[] : []
})
const receivedWithoutCode = computed(() => Boolean(resultData.value?.received || Number(resultData.value?.matchedCount || 0) > 0) && !latestCode.value)

const postJson = async (url: string, body: Record<string, unknown>) => {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...buildProjectPayload(),
      ...body,
    }),
  })
  const payload = await response.json()
  if (!(payload.code === '200' || payload.code === 200)) {
    throw new Error(payload.error || payload.message || '请求失败')
  }
  return payload.data
}

const handleSenderChange = () => {
  form.senderCompanyLabels = selectedSenders.value.map(item => item.label)
}

const saveProject = async () => {
  if (!form.project) {
    ElMessage.warning('请先填写项目标记')
    return
  }
  loading.save = true
  try {
    result.value = await postJson('/api/project/upsert', {})
    await loadProjects()
    ElMessage.success('项目已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '项目保存失败')
  } finally {
    loading.save = false
  }
}

const acquireEmail = async () => {
  if (!form.project) {
    ElMessage.warning('请先填写项目标记')
    return
  }
  loading.acquire = true
  try {
    const data = await postJson('/api/project/acquire', {
      mode: form.mode,
    })
    result.value = data
    if (data?.account?.email || data?.email) {
      form.email = data.account?.email || data.email
    }
    await loadProjects()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取邮箱失败')
  } finally {
    loading.acquire = false
  }
}

const fetchMail = async () => {
  if (!form.project || !form.email) {
    ElMessage.warning('请先填写项目标记和邮箱')
    return
  }
  loading.mail = true
  try {
    const data = await postJson('/api/project/code', {
      email: form.email,
      top: form.fullScan ? 0 : form.mailTop,
    })
    result.value = data
    await loadProjects()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取验证码失败')
  } finally {
    loading.mail = false
  }
}

const loadProjects = async () => {
  if (!form.password) return
  loading.projects = true
  try {
    const data = await postJson('/api/project/list', {})
    projects.value = Array.isArray(data) ? data : []
  } catch {
    projects.value = []
  } finally {
    loading.projects = false
  }
}

const loadSenderOptions = async () => {
  if (!form.password) return
  loading.senders = true
  try {
    const response = await fetch('/api/sender_options', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        password: form.password,
        limit: 5000,
        sort: 'latest',
      }),
    })
    const payload = await response.json()
    if (payload.code === '200' || payload.code === 200) {
      senderOptions.value = Array.isArray(payload.data)
        ? payload.data
        : []
      handleSenderChange()
    }
  } catch {
    senderOptions.value = []
  } finally {
    loading.senders = false
  }
}

const copyText = async (text: string) => {
  if (!text) return
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

const mailboxText = (mailbox = '') => ({
  INBOX: '收件箱',
  Junk: '垃圾箱',
  Deleted: '已删除',
  Archive: '归档',
  Sent: '已发送',
  Drafts: '草稿',
}[mailbox] || mailbox || '-')

onMounted(() => {
  loadSenderOptions()
  loadProjects()
})
</script>

<style scoped>
.project-api-page {
  padding: 20px;
}

.panel {
  margin-bottom: 18px;
  padding: 16px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fff;
}

.panel-title {
  margin-bottom: 14px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.project-form {
  max-width: 860px;
}

.full-control {
  width: 100%;
}

.sender-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.sender-count {
  color: #909399;
  font-size: 12px;
}

.link-row {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr) 80px;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
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

.result-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(130px, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.summary-card {
  padding: 10px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fafafa;
}

.summary-card span {
  display: block;
  font-size: 12px;
  color: #606266;
}

.summary-card strong {
  display: block;
  margin-top: 4px;
  overflow-wrap: anywhere;
  font-size: 18px;
  color: #111827;
}

.result-alert,
.matched-table {
  margin-bottom: 12px;
}

@media (max-width: 720px) {
  .link-row {
    grid-template-columns: 1fr;
  }

  .result-summary {
    grid-template-columns: 1fr;
  }
}
</style>
