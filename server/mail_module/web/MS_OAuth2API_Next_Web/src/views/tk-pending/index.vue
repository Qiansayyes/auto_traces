<template>
  <div class="tk-pending-page">
    <section class="toolbar-band">
      <el-form :inline="true" class="toolbar-form">
        <el-form-item label="分隔符">
          <el-input v-model="form.splitSymbol" style="width: 120px" />
        </el-form-item>
        <el-form-item label="接口密钥">
          <el-input v-model="form.apiPassword" type="password" show-password style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading.list" @click="loadList(true)">加载列表</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="summary-grid">
      <div class="summary-card">
        <div class="summary-label">待注册</div>
        <div class="summary-value">{{ stats.available }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">注册中</div>
        <div class="summary-value">{{ stats.claimed }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">合计</div>
        <div class="summary-value">{{ stats.total }}</div>
      </div>
    </section>

    <section class="toolbar-band">
      <div class="section-title">导入</div>
      <div class="button-row">
        <el-upload :limit="1" :show-file-list="false" :auto-upload="false" :on-change="handleFileChange" accept=".txt,.csv">
          <template #trigger>
            <el-button type="primary">{{ fileName || '选择文件' }}</el-button>
          </template>
        </el-upload>
        <el-button type="success" :loading="loading.import" @click="importFromFile">导入邮箱</el-button>
        <el-button type="success" @click="dialogPasteVisible = true">粘贴导入</el-button>
      </div>
    </section>

    <section class="toolbar-band">
      <div class="section-title">筛选与批量操作</div>
      <div class="button-row">
        <el-input v-model="form.search" clearable placeholder="搜索邮箱" style="width: 220px" @keyup.enter="loadList(true)" />
        <el-select v-model="form.poolStatus" clearable placeholder="全部状态" style="width: 140px" @change="loadList(true)">
          <el-option label="待注册" value="available" />
          <el-option label="注册中" value="claimed" />
        </el-select>
        <el-button type="primary" plain @click="loadList(true)">搜索</el-button>
        <el-button type="danger" plain :disabled="selectedEmails.length === 0" @click="deleteSelected">批量删除</el-button>
        <el-button type="danger" @click="deleteAllAvailable">删除全部待注册</el-button>
      </div>
    </section>

    <section class="panel">
      <el-table
        v-loading="loading.list"
        :data="rows"
        stripe
        size="small"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column prop="email" label="邮箱" min-width="240" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.poolStatus)">{{ statusText(row.poolStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="claimedAt" label="领取时间" min-width="170" show-overflow-tooltip />
        <el-table-column prop="importedAt" label="导入时间" min-width="170" show-overflow-tooltip />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              size="small"
              type="danger"
              @click="deleteOne(row.email)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.pageSize"
          layout="total, prev, pager, next"
          :total="pagination.total"
          @current-change="loadList()"
          @size-change="loadList(true)"
        />
      </div>
    </section>

    <el-dialog v-model="dialogPasteVisible" title="粘贴导入" width="640px">
      <el-input v-model="pasteText" type="textarea" :rows="12" placeholder="每行一个邮箱，格式与邮箱账户管理一致" />
      <template #footer>
        <el-button @click="dialogPasteVisible = false">取消</el-button>
        <el-button type="primary" :loading="loading.import" @click="importFromPaste">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

interface PendingAccount {
  email: string
  poolStatus: string
  claimedAt?: string
  importedAt?: string
}

const form = reactive({
  splitSymbol: localStorage.getItem('tkPendingSplitSymbol') || '----',
  apiPassword: localStorage.getItem('apiPassword') || '',
  search: '',
  poolStatus: '',
})

const loading = reactive({
  list: false,
  import: false,
})

const stats = reactive({
  available: 0,
  claimed: 0,
  total: 0,
})

const rows = ref<PendingAccount[]>([])
const selectedEmails = ref<string[]>([])
const fileName = ref('')
const fileLines = ref<string[]>([])
const pasteText = ref('')
const dialogPasteVisible = ref(false)

const pagination = reactive({
  page: 1,
  pageSize: 50,
  total: 0,
})

watch(() => form.splitSymbol, (value) => localStorage.setItem('tkPendingSplitSymbol', value))
watch(() => form.apiPassword, (value) => localStorage.setItem('apiPassword', value))

const postJson = async (path: string, body: Record<string, unknown>) => {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      password: form.apiPassword,
      ...body,
    }),
  })
  const payload = await response.json()
  if (!(payload.code === '200' || payload.code === 200)) {
    throw new Error(payload.error || payload.message || '请求失败')
  }
  return payload.data
}

const parseLines = (lines: string[]) => lines
  .map((item) => item.trim())
  .filter(Boolean)
  .map((line) => {
    const parts = line.split(form.splitSymbol)
    return {
      email: (parts[0] || '').trim().toLowerCase(),
      password: parts[1] || '',
      client_id: parts[2] || '',
      refresh_token: parts.slice(3).join(form.splitSymbol) || '',
    }
  })
  .filter((item) => item.email.includes('@'))

const importRecords = async (records: Array<Record<string, string>>, source: string) => {
  if (records.length === 0) {
    ElMessage.warning('没有可导入的邮箱')
    return
  }
  loading.import = true
  try {
    const data = await postJson('/api/tk-pending/accounts/import', {
      records,
      splitSymbol: form.splitSymbol,
      source,
    })
    ElMessage.success(`导入完成：成功 ${data.inserted_or_updated || 0}，跳过 ${data.skipped_records || 0}`)
    dialogPasteVisible.value = false
    pasteText.value = ''
    await loadList(true)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入失败')
  } finally {
    loading.import = false
  }
}

// Element Plus upload on-change: (uploadFile, uploadFiles)
const handleFileChange = (uploadFile: any) => {
  fileName.value = String(uploadFile?.name || '')
  const raw: File | undefined = uploadFile?.raw
  if (!raw) return
  const reader = new FileReader()
  reader.onload = () => {
    const text = String(reader.result || '')
    fileLines.value = text.split(/\r?\n/)
  }
  reader.readAsText(raw)
}

const importFromFile = async () => {
  await importRecords(parseLines(fileLines.value), 'tk_pending_file')
}

const importFromPaste = async () => {
  await importRecords(parseLines(pasteText.value.split(/\r?\n/)), 'tk_pending_paste')
}

const loadList = async (resetPage = false) => {
  if (resetPage) pagination.page = 1
  loading.list = true
  try {
    const data = await postJson('/api/tk-pending/accounts/list', {
      page: pagination.page,
      pageSize: pagination.pageSize,
      search: form.search.trim(),
      poolStatus: form.poolStatus || undefined,
    })
    rows.value = data.records || []
    pagination.total = data.total || 0
    stats.available = data.stats?.available || 0
    stats.claimed = data.stats?.claimed || 0
    stats.total = data.stats?.total || 0
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '列表加载失败')
  } finally {
    loading.list = false
  }
}

const deletePending = async (emails: string[], deleteAll = false, onlyAvailable = false) => {
  await postJson('/api/tk-pending/accounts/delete', {
    emails,
    deleteAll,
    onlyAvailable,
  })
  await loadList()
}

const deleteOne = async (email: string) => {
  try {
    await ElMessageBox.confirm(`确认删除待注册邮箱 ${email} 吗？`, '删除确认', { type: 'warning' })
    await deletePending([email], false, false)
    ElMessage.success('删除成功')
  } catch {
    // cancelled
  }
}

const deleteSelected = async () => {
  if (selectedEmails.value.length === 0) return
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selectedEmails.value.length} 个待注册邮箱吗？`, '批量删除', { type: 'warning' })
    await deletePending(selectedEmails.value, false, false)
    ElMessage.success('删除成功')
  } catch {
    // cancelled
  }
}

const deleteAllAvailable = async () => {
  try {
    await ElMessageBox.confirm('确认删除全部「待注册」邮箱吗？注册中的邮箱不会被删除。', '全部删除', { type: 'warning' })
    await deletePending([], true, true)
    ElMessage.success('删除成功')
  } catch {
    // cancelled
  }
}

const handleSelectionChange = (selection: PendingAccount[]) => {
  selectedEmails.value = selection.map((row) => row.email)
}

const statusText = (status: string) => ({
  available: '待注册',
  claimed: '注册中',
}[status] || status || '-')

const statusTag = (status: string) => ({
  available: 'success',
  claimed: 'warning',
}[status] || 'info')

onMounted(() => {
  if (form.apiPassword) loadList(true)
})
</script>

<style scoped>
.tk-pending-page {
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
  gap: 8px 12px;
}

.section-title {
  font-weight: 600;
  margin-bottom: 12px;
}

.button-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.summary-card {
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 16px;
}

.summary-label {
  color: #909399;
  font-size: 13px;
}

.summary-value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 700;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
