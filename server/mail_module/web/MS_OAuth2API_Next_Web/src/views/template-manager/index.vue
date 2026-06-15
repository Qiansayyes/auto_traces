<template>
  <div class="home-container">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="从邮件创建模板" name="create">
        <el-alert v-if="statusMsg" :title="statusMsg" :type="statusType" show-icon :closable="true"
          @close="statusMsg = ''" style="margin-bottom: 16px;" />

        <!-- Step 1: Fetch -->
        <el-card shadow="never" style="margin-bottom: 16px;">
          <template #header>
            <div class="step-header">
              <el-tag :type="step >= 1 ? 'primary' : 'info'" round>1</el-tag>
              <span class="step-title">获取邮件</span>
            </div>
          </template>
          <el-form :inline="false" label-width="100px">
            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="Refresh Token">
                  <el-input v-model="fetchForm.refresh_token" placeholder="refresh_token" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="Client ID">
                  <el-input v-model="fetchForm.client_id" placeholder="client_id" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="Email">
                  <el-input v-model="fetchForm.email" placeholder="your@outlook.com" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="邮箱文件夹">
                  <el-select v-model="fetchForm.mailbox" style="width:100%">
                    <el-option label="收件箱 (INBOX)" value="INBOX" />
                    <el-option label="垃圾邮件 (Junk)" value="Junk" />
                    <el-option label="已发送 (Sent)" value="Sent" />
                    <el-option label="草稿 (Drafts)" value="Drafts" />
                    <el-option label="存档 (Archive)" value="Archive" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="获取数量">
                  <el-input-number v-model="fetchForm.top" :min="1" :max="100" style="width:100%" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="代理 (可选)">
                  <el-input v-model="fetchForm.proxy" placeholder="socks5://ip:port 或 http://ip:port" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item>
              <el-button type="primary" :loading="fetchLoading" @click="fetchEmails">获取邮件</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- Step 2: Select email -->
        <el-card shadow="never" style="margin-bottom: 16px;">
          <template #header>
            <div class="step-header">
              <el-tag :type="step >= 2 ? 'primary' : 'info'" round>2</el-tag>
              <span class="step-title">选择一封邮件作为样本</span>
            </div>
          </template>
          <el-empty v-if="emails.length === 0" description="请先获取邮件" :image-size="60" />
          <el-table v-else :data="emails" highlight-current-row @current-change="selectEmail" max-height="360"
            size="small" style="width:100%">
            <el-table-column prop="send" label="发件人" min-width="200" show-overflow-tooltip />
            <el-table-column prop="subject" label="主题" min-width="240" show-overflow-tooltip />
            <el-table-column label="日期" width="160">
              <template #default="{ row }">{{ formatDate(row.date) }}</template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- Step 3: Define template -->
        <el-card v-if="selectedEmail" shadow="never">
          <template #header>
            <div class="step-header">
              <el-tag type="primary" round>3</el-tag>
              <span class="step-title">定义提取规则</span>
            </div>
          </template>

          <el-descriptions :column="3" border size="small" style="margin-bottom: 16px;">
            <el-descriptions-item label="发件人">{{ selectedEmail.send }}</el-descriptions-item>
            <el-descriptions-item label="主题">{{ selectedEmail.subject }}</el-descriptions-item>
            <el-descriptions-item label="时间">{{ formatDate(selectedEmail.date) }}</el-descriptions-item>
          </el-descriptions>

          <div style="margin-bottom: 8px;">
            <el-text type="info" size="small">邮件正文 — 用鼠标选中验证码文本，系统自动生成正则</el-text>
          </div>
          <div class="email-body-area" @mouseup="onTextSelect">{{ selectedEmail.text || selectedEmail.html || '(无内容)'
            }}</div>
          <el-text v-if="selectHint" type="success" size="small" style="display:block;margin:6px 0 12px;">{{ selectHint
            }}</el-text>

          <el-form label-width="120px" style="margin-top: 16px;">
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="模板名称">
                  <el-input v-model="tplForm.name" placeholder="例如: github_verify" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="描述">
                  <el-input v-model="tplForm.description" placeholder="描述此模板的用途" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="发件人匹配">
                  <el-input v-model="tplForm.sender" placeholder="例如: noreply@github.com" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="主题匹配">
                  <el-input v-model="tplForm.subject" placeholder="例如: verification（可留空）" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="提取正则">
                  <el-input v-model="tplForm.pattern" placeholder="例如: (\d{6})" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="提取来源">
                  <el-select v-model="tplForm.field" style="width:100%">
                    <el-option label="纯文本 (text)" value="text" />
                    <el-option label="HTML" value="html" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item>
              <el-button @click="testPattern">测试正则</el-button>
              <el-button type="success" @click="saveTemplate">保存模板</el-button>
            </el-form-item>
          </el-form>

          <el-alert v-if="testResult" :title="testResult.msg" :type="testResult.ok ? 'success' : 'error'" show-icon
            :closable="true" @close="testResult = null" style="margin-top: 8px;" />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="管理模板" name="manage">
        <el-empty v-if="templates.length === 0" description="暂无模板，请先创建" />
        <el-table v-else :data="templates" style="width:100%" size="small">
          <el-table-column prop="name" label="模板名称" width="160" />
          <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
          <el-table-column label="发件人匹配" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.match?.sender || '-' }}</template>
          </el-table-column>
          <el-table-column label="主题匹配" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.match?.subject || '-' }}</template>
          </el-table-column>
          <el-table-column label="提取正则" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.extract?.pattern || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button size="small" type="danger" @click="deleteTemplate(row.name)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top: 12px;">
          <el-button @click="loadTemplates">刷新</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

interface EmailItem {
  id?: string
  send: string
  subject: string
  text: string
  html: string
  date: string
}

interface Template {
  name: string
  description: string
  match: { sender: string | null; subject: string | null }
  extract: { type: string; pattern: string; field: string }
  createdAt?: string
}

const activeTab = ref('create')
const step = ref(1)
const statusMsg = ref('')
const statusType = ref<'success' | 'error' | 'info' | 'warning'>('info')
const fetchLoading = ref(false)
const emails = ref<EmailItem[]>([])
const selectedEmail = ref<EmailItem | null>(null)
const selectHint = ref('')
const templates = ref<Template[]>([])
const testResult = ref<{ ok: boolean; msg: string } | null>(null)

const fetchForm = ref({
  refresh_token: '',
  client_id: '',
  email: '',
  mailbox: 'INBOX',
  top: 20,
  proxy: '',
})

const tplForm = ref({
  name: '',
  description: '',
  sender: '',
  subject: '',
  pattern: '',
  field: 'text',
})

const extractAddr = (s: string) => {
  if (!s) return ''
  const m = s.match(/<([^>]+)>/)
  return m?.[1] ?? s.trim()
}

const formatDate = (d: string) => {
  if (!d) return '-'
  try {
    return new Date(d).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  } catch { return d }
}

const fetchEmails = async () => {
  const { refresh_token, client_id, email, mailbox, top, proxy } = fetchForm.value
  if (!refresh_token || !client_id || !email) {
    ElMessage.warning('请填写 Refresh Token、Client ID 和 Email')
    return
  }
  fetchLoading.value = true
  statusMsg.value = ''
  try {
    const params = new URLSearchParams({ refresh_token, client_id, email, mailbox, top: String(top), contentMode: 'full', manualFetch: 'true' })
    if (proxy.startsWith('socks5')) params.set('socks5', proxy)
    else if (proxy) params.set('http', proxy)
    const res = await fetch('/api/mail_new?' + params.toString())
    const json = await res.json()
    if (json.code === '200' || json.code === 200) {
      const data = Array.isArray(json.data) ? json.data : (json.data?.emails || [])
      emails.value = data
      step.value = 2
      statusMsg.value = `成功获取 ${data.length} 封邮件`
      statusType.value = 'success'
    } else {
      statusMsg.value = json.error || json.message || '获取失败'
      statusType.value = 'error'
    }
  } catch (e: any) {
    statusMsg.value = '请求失败: ' + e.message
    statusType.value = 'error'
  } finally {
    fetchLoading.value = false
  }
}

const selectEmail = (row: EmailItem | null) => {
  if (!row) return
  selectedEmail.value = row
  step.value = 3
  tplForm.value.sender = extractAddr(row.send)
  tplForm.value.subject = ''
  tplForm.value.pattern = ''
  tplForm.value.name = ''
  tplForm.value.description = ''
  selectHint.value = ''
  testResult.value = null
}

const onTextSelect = () => {
  const sel = window.getSelection()
  const text = sel?.toString().trim()
  if (!text) return

  let pattern = ''
  if (/^\d+$/.test(text)) {
    pattern = `(\\d{${text.length}})`
    selectHint.value = `检测到 ${text.length} 位数字验证码: "${text}" → 正则: ${pattern}`
  } else if (/^[a-zA-Z0-9]+$/.test(text)) {
    pattern = `([a-zA-Z0-9]{${text.length}})`
    selectHint.value = `检测到 ${text.length} 位字母数字码: "${text}" → 正则: ${pattern}`
  } else {
    const escaped = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    pattern = `(${escaped})`
    selectHint.value = `选中文本: "${text}" → 已生成字面匹配正则`
  }
  tplForm.value.pattern = pattern
}

const testPattern = () => {
  const { pattern, field } = tplForm.value
  if (!pattern) { ElMessage.warning('请先输入正则表达式'); return }
  if (!selectedEmail.value) { ElMessage.warning('请先选择邮件'); return }
  const content = field === 'html' ? selectedEmail.value.html : selectedEmail.value.text
  if (!content) { testResult.value = { ok: false, msg: '邮件没有对应内容' }; return }
  try {
    const regex = new RegExp(pattern)
    const result = regex.exec(content)
    if (result && result[1]) {
      testResult.value = { ok: true, msg: `匹配成功！提取结果: ${result[1]}` }
    } else if (result) {
      testResult.value = { ok: false, msg: '正则匹配了但没有捕获组，请用 () 包裹要提取的部分' }
    } else {
      testResult.value = { ok: false, msg: '未匹配到内容，请调整正则表达式' }
    }
  } catch (e: any) {
    testResult.value = { ok: false, msg: '正则语法错误: ' + e.message }
  }
}

const saveTemplate = async () => {
  const { name, description, sender, subject, pattern, field } = tplForm.value
  if (!name) { ElMessage.warning('请输入模板名称'); return }
  if (!pattern) { ElMessage.warning('请输入提取正则'); return }
  try {
    const res = await fetch('/api/template/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name, description,
        match: { sender: sender || null, subject: subject || null },
        extract: { type: 'regex', pattern, field }
      })
    })
    const json = await res.json()
    if (json.code === '200' || json.code === 200) {
      ElMessage.success(`模板 "${name}" 保存成功`)
      loadTemplates()
    } else {
      ElMessage.error(json.message || '保存失败')
    }
  } catch (e: any) {
    ElMessage.error('保存失败: ' + e.message)
  }
}

const loadTemplates = async () => {
  try {
    const res = await fetch('/api/template/list')
    const json = await res.json()
    templates.value = json.data || []
  } catch { templates.value = [] }
}

const deleteTemplate = async (name: string) => {
  try {
    await ElMessageBox.confirm(`确定删除模板 "${name}" 吗？`, '删除确认', { type: 'warning' })
    await fetch('/api/template/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name })
    })
    ElMessage.success('删除成功')
    loadTemplates()
  } catch { /* cancelled */ }
}

onMounted(() => {
  loadTemplates()
})
</script>

<style scoped>
.step-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.step-title {
  font-weight: 600;
  font-size: 15px;
}

.email-body-area {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 14px;
  font-size: 13px;
  line-height: 1.7;
  max-height: 240px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  user-select: text;
  cursor: text;
}
</style>
