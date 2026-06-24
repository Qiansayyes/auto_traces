<template>
  <el-dialog v-model="visible" :title="title" width="920px" destroy-on-close @closed="handleClosed">
    <el-text type="warning">{{ hint }}</el-text>
    <div class="preview-toolbar">
      <span class="preview-summary">{{ summaryText }}</span>
    </div>
    <el-table
      :data="items"
      row-key="key"
      size="small"
      max-height="420"
      stripe
    >
      <el-table-column prop="record.email" label="邮箱" min-width="220" show-overflow-tooltip />
      <el-table-column prop="record.password" label="密码" min-width="120" show-overflow-tooltip />
      <el-table-column prop="record.client_id" label="client_id" min-width="180" show-overflow-tooltip />
      <el-table-column prop="record.refresh_token" label="token" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <el-tag v-if="row.kind === 'valid'" size="small" type="success">可导入</el-tag>
          <el-tag v-else-if="row.kind === 'duplicate'" size="small" type="warning">{{ row.reason || '重复' }}</el-tag>
          <el-tag v-else size="small" type="danger">{{ row.reason || '无效' }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button v-if="failedCount > 0" type="warning" @click="handleDownloadFailed">
        下载失败记录 ({{ failedCount }})
      </el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="confirmLoading" :disabled="validCount === 0" @click="handleConfirm">
        确定导入{{ validCount > 0 ? ` (${validCount})` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { MailImportPreviewItem } from '@/utils/mailAccountImport'
import { downloadMailImportFailedXlsx, failedRowsFromPreviewItems } from '@/utils/mailAccountImport'

const props = withDefaults(defineProps<{
  modelValue: boolean
  items: MailImportPreviewItem[]
  title?: string
  hint?: string
  confirmLoading?: boolean
}>(), {
  title: '导入预览',
  hint: '确认后将自动导入全部可导入记录；失败记录可下载查看原因。',
  confirmLoading: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [items: MailImportPreviewItem[]]
  closed: []
  'download-failed': []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const validCount = computed(() => props.items.filter((item) => item.kind === 'valid').length)
const failedCount = computed(() => props.items.filter((item) => item.kind !== 'valid').length)

const summaryText = computed(() => {
  const total = props.items.length
  const duplicateCount = props.items.filter((item) => item.kind === 'duplicate').length
  const invalidCount = props.items.filter((item) => item.kind === 'invalid').length
  const parts = [`共 ${total} 条`, `可导入 ${validCount.value} 条`]
  if (duplicateCount) parts.push(`重复 ${duplicateCount} 条`)
  if (invalidCount) parts.push(`无效 ${invalidCount} 条`)
  return parts.join('，')
})

const handleConfirm = () => {
  emit('confirm', props.items)
}

const handleDownloadFailed = () => {
  const rows = failedRowsFromPreviewItems(props.items)
  if (!rows.length) return
  downloadMailImportFailedXlsx(rows)
  emit('download-failed')
}

const handleClosed = () => {
  emit('closed')
}
</script>

<style scoped>
.preview-toolbar {
  display: flex;
  align-items: center;
  margin: 14px 0 10px;
}

.preview-summary {
  color: #909399;
  font-size: 13px;
}
</style>
