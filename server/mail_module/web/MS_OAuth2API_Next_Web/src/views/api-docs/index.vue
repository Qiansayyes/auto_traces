<template>
  <div class="api-docs-page">
    <section class="panel">
      <div class="panel-title">接口参数</div>
      <el-form :model="form" label-width="110px" class="docs-form">
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
                <span class="muted">{{ sender.value }} · {{ sender.count || 0 }}</span>
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
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="第二步获取验证码时填入第一步返回的邮箱" />
        </el-form-item>
        <el-form-item label="取件封数">
          <el-input-number v-model="form.mailTop" :min="1" :max="200" controls-position="right" :disabled="form.fullScan" />
        </el-form-item>
        <el-form-item label="扫描方式">
          <el-checkbox v-model="form.fullScan">完整扫描</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button :loading="loading.senders" @click="loadSenderOptions">刷新发件人</el-button>
          <el-button type="primary" @click="copyText(headerText)">复制请求头</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="panel">
      <div class="panel-title">请求头</div>
      <pre class="code-box">{{ headerText }}</pre>
    </section>

    <section class="endpoint-grid">
      <article v-for="endpoint in endpoints" :key="endpoint.key" class="endpoint-panel">
        <div class="endpoint-header">
          <div>
            <div class="endpoint-title">{{ endpoint.title }}</div>
            <div class="endpoint-path">{{ endpoint.method }} {{ endpoint.path }}</div>
          </div>
          <el-button size="small" @click="copyText(endpoint.url)">复制链接</el-button>
        </div>

        <el-tabs>
          <el-tab-pane label="GET">
            <el-input :model-value="endpoint.url" readonly />
          </el-tab-pane>
          <el-tab-pane label="POST JSON">
            <pre class="code-box">{{ endpoint.postJson }}</pre>
          </el-tab-pane>
          <el-tab-pane label="返回字段">
            <el-table :data="endpoint.fields" size="small" stripe>
              <el-table-column prop="field" label="字段" min-width="190" show-overflow-tooltip />
              <el-table-column prop="desc" label="说明" min-width="220" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

interface SenderOption {
  label: string
  value: string
  count?: number
}

interface FieldRow {
  field: string
  desc: string
}

const form = reactive({
  password: localStorage.getItem('apiPassword') || '',
  project: '1',
  name: 'A项目',
  senderCompanyIdentities: ['company:openai.com'],
  senderCompanyLabels: ['OpenAI'],
  mode: 'new',
  email: '',
  mailTop: 50,
  fullScan: false,
})

const loading = reactive({
  senders: false,
})

const senderOptions = ref<SenderOption[]>([])

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

const handleSenderChange = () => {
  form.senderCompanyLabels = selectedSenders.value.map(item => item.label)
}

const authParams = computed(() => {
  const params = new URLSearchParams()
  params.set('project', form.project)
  if (form.name) params.set('name', form.name)
  if (form.senderCompanyIdentities.length > 0) {
    params.set('senderCompanyIdentities', form.senderCompanyIdentities.join(','))
    params.set('senderCompanyLabels', selectedSenders.value.map(item => item.label).join(','))
  }
  return params
})

const withPasswordParams = computed(() => {
  const params = new URLSearchParams(authParams.value)
  params.set('password', form.password)
  return params
})

const acquireParams = computed(() => {
  const params = new URLSearchParams(withPasswordParams.value)
  params.set('mode', form.mode)
  return params.toString()
})
const mailParams = computed(() => {
  const params = new URLSearchParams(withPasswordParams.value)
  if (form.email) params.set('email', form.email)
  params.set('top', form.fullScan ? '0' : String(form.mailTop || 50))
  return params.toString()
})
const statusParams = computed(() => {
  const params = new URLSearchParams(withPasswordParams.value)
  if (form.email) params.set('email', form.email)
  return params.toString()
})
const mailboxPoolParams = computed(() => {
  const params = new URLSearchParams(withPasswordParams.value)
  params.set('includeRows', 'true')
  params.set('limit', '500')
  return params.toString()
})

const headerText = computed(() => [
  `x-api-password: ${form.password || '接口密码'}`,
  `Authorization: Bearer ${form.password || '接口密码'}`,
].join('\n'))

const projectPayload = computed(() => ({
  project: form.project,
  name: form.name,
  senderCompanyIdentities: form.senderCompanyIdentities,
  senderCompanyLabels: selectedSenders.value.map(item => item.label),
}))

const acquirePayload = computed(() => ({
  ...projectPayload.value,
  mode: form.mode,
}))

const jsonText = (value: Record<string, unknown>) => JSON.stringify(value, null, 2)

const endpoints = computed(() => [
  {
    key: 'mailboxes',
    title: '全项目：查询接码邮箱池',
    method: 'GET',
    path: '/api/project/mailboxes',
    url: `${origin.value}/api/project/mailboxes?${mailboxPoolParams.value}`,
    postJson: jsonText({
      ...projectPayload.value,
      includeRows: true,
      limit: 500,
    }),
    fields: [
      { field: 'data[].knownMailboxCount', desc: '项目里已出现过的邮箱总数' },
      { field: 'data[].newAvailableCount', desc: '未做过此项目、可新分配的邮箱数' },
      { field: 'data[].reusableReceivedCount', desc: '已收到过此项目发件人、可复用的邮箱数' },
      { field: 'data[].retryableCount', desc: '已分配或已检查但还可二次获取的邮箱数' },
      { field: 'data[].mailboxes', desc: '项目邮箱明细，传 includeRows=true 时返回' },
    ] as FieldRow[],
  },
  {
    key: 'acquire',
    title: '第一步：获取项目邮箱',
    method: 'GET',
    path: '/api/project/acquire',
    url: `${origin.value}/api/project/acquire?${acquireParams.value}`,
    postJson: jsonText(acquirePayload.value),
    fields: [
      { field: 'data.mode', desc: 'new / received / retry' },
      { field: 'data.reused', desc: '是否来自复用或二次重试池' },
      { field: 'data.receivedByProjectSender', desc: '该邮箱是否已收到过项目绑定发件人' },
      { field: 'data.markStatus', desc: '此邮箱在项目中的当前状态' },
      { field: 'data.available', desc: '是否还有可用邮箱' },
      { field: 'data.account.email', desc: '分配到的邮箱' },
      { field: 'data.claimed', desc: '是否已标记为项目已分配' },
      { field: 'data.project.senderCompanyIdentities', desc: '项目绑定的发件人分类标识列表' },
    ] as FieldRow[],
  },
  {
    key: 'mail',
    title: '第二步：获取邮箱里的验证码',
    method: 'GET',
    path: '/api/project/code',
    url: `${origin.value}/api/project/code?${mailParams.value}`,
    postJson: jsonText({
      ...projectPayload.value,
      email: form.email || '第一步返回的邮箱',
      top: form.fullScan ? 0 : form.mailTop,
    }),
    fields: [
      { field: 'data.latestCode', desc: '最新验证码' },
      { field: 'data.latestLink', desc: '最新验证链接；无验证码但有邮件链接时使用这个字段' },
      { field: 'data.verificationLink', desc: 'latestLink 的兼容别名' },
      { field: 'data.received', desc: '是否匹配到项目绑定发件人' },
      { field: 'data.status', desc: 'received / checked / error' },
      { field: 'data.matchedCount', desc: '命中的项目发件人邮件数量' },
      { field: 'data.messageCount', desc: '本次扫描的邮件数量；top=0 或 top=all 表示完整扫描' },
      { field: 'data.hydratedCount', desc: '对命中候选补拉完整正文的邮件数量' },
      { field: 'data.matched', desc: '匹配项目发件人的邮件摘要，包含 primaryLink / links / verificationLinks' },
      { field: 'data.mailboxResults', desc: 'INBOX 与 Junk 拉取结果' },
    ] as FieldRow[],
  },
  {
    key: 'status',
    title: '查询：邮箱项目状态',
    method: 'GET',
    path: '/api/project/status',
    url: `${origin.value}/api/project/status?${statusParams.value}`,
    postJson: jsonText({
      project: form.project,
      email: form.email || '要查询的邮箱',
    }),
    fields: [
      { field: 'data.receivedByProjectSender', desc: '此邮箱是否已接过项目发件人的邮件' },
      { field: 'data.mark.status', desc: '此邮箱在项目里的状态' },
      { field: 'data.mark.latestCode', desc: '最近一次提取到的验证码' },
      { field: 'data.mark.lastError', desc: '最近一次失败原因' },
    ] as FieldRow[],
  },
])

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

onMounted(loadSenderOptions)
</script>

<style scoped>
.api-docs-page {
  padding: 20px;
}

.panel,
.endpoint-panel {
  padding: 16px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fff;
}

.panel {
  margin-bottom: 18px;
}

.panel-title,
.endpoint-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.docs-form {
  max-width: 900px;
}

.full-control {
  width: 100%;
}

.sender-option {
  display: flex;
  justify-content: space-between;
  gap: 18px;
}

.muted,
.endpoint-path {
  color: #909399;
  font-size: 12px;
}

.endpoint-grid {
  display: grid;
  gap: 16px;
}

.endpoint-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.code-box {
  margin: 0;
  padding: 12px;
  overflow: auto;
  border-radius: 6px;
  background: #f5f7fa;
  color: #303133;
  white-space: pre-wrap;
}
</style>
