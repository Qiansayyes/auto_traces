<template>
  <div class="home-container">
    <div class="toolbar">
      <el-select
        v-model="selectedSenders"
        multiple
        collapse-tags
        collapse-tags-tooltip
        :max-collapse-tags="2"
        placeholder="选择发件人"
        clearable
        filterable
        reserve-keyword
        fit-input-width
        :loading="senderGroupsLoading"
        class="toolbar-input"
      >
        <el-option v-for="sender in senderOptions" :key="sender.value" :label="sender.label" :value="sender.value">
          <div class="sender-option">
            <span>{{ sender.label }}</span>
            <span v-if="sender.count" class="sender-option-count">{{ sender.count }}</span>
          </div>
        </el-option>
      </el-select>
      <el-input v-model="senderSearch" placeholder="搜索发件人" clearable class="toolbar-input" />
      <el-select v-model="senderSort" class="toolbar-sort" @change="handleSenderSortChange">
        <el-option label="最新写入" value="updated" />
        <el-option label="最新邮件" value="latest" />
        <el-option label="记录数量" value="count" />
      </el-select>
      <el-switch v-model="showOnlyHasUnreceived" active-text="只看存在未接收邮箱的发件人" />
      <el-button :loading="senderGroupsLoading" @click="reloadSenderGroups">刷新</el-button>
    </div>

    <el-empty v-if="!senderGroupsLoading && displayedGroupList.length === 0" description="暂无匹配的发件人分类" />

    <div v-loading="senderGroupsLoading || senderFilterLoading" v-else class="groups-container">
      <el-card v-for="group in displayedGroupList" :key="group.sender" class="group-card" shadow="hover">
        <template #header>
          <div class="group-header">
            <div class="group-header-main">
              <span class="group-title">{{ group.sender }}</span>
              <span class="group-count">发件记录 {{ getItemCount(group) }} 条</span>
              <span class="group-count">已接收收件人 {{ getReceivedRecipientCount(group) }} 个</span>
              <span class="group-count">未接收收件人 {{ getUnreceivedEmailCount(group) }} 个</span>
            </div>
            <div class="group-header-actions">
              <el-button
                size="small"
                type="success"
                :disabled="getUnreceivedEmailCount(group) === 0"
                @click="copyText(group.unreceivedEmails[0] || '', '未接收邮箱已复制')"
              >
                复制一个未接收邮箱
              </el-button>
            </div>
          </div>
        </template>

        <div class="classification-grid">
          <el-card shadow="never" class="classification-card">
            <template #header>
              <div class="classification-title">已接收收件人</div>
            </template>
            <el-empty v-if="getReceivedRecipientCount(group) === 0" description="暂无" :image-size="60" />
            <div v-else class="email-list">
              <div v-for="recipient in group.receivedRecipients" :key="`received-${group.sender}-${recipient.email}`" class="email-item">
                <div class="email-item-main">
                  <span>{{ recipient.email }}</span>
                  <span class="email-hint">发件记录 {{ recipient.count }} 条 · 最近 {{ formatDateTime(recipient.latestDate) }}</span>
                </div>
                <el-button size="small" type="primary" @click="copyText(recipient.email, '邮箱已复制')">复制</el-button>
              </div>
              <div v-if="getReceivedRecipientCount(group) > group.receivedRecipients.length" class="email-list-note">
                已显示前 {{ group.receivedRecipients.length }} 个，共 {{ getReceivedRecipientCount(group) }} 个
              </div>
            </div>
          </el-card>

          <el-card shadow="never" class="classification-card">
            <template #header>
              <div class="classification-title">未接收邮箱</div>
            </template>
            <el-empty v-if="getUnreceivedEmailCount(group) === 0" description="暂无" :image-size="60" />
            <div v-else class="email-list">
              <div v-for="email in group.unreceivedEmails" :key="`unreceived-${group.sender}-${email}`" class="email-item">
                <span>{{ email }}</span>
                <el-button size="small" type="success" @click="copyText(email, '未接收邮箱已复制')">复制</el-button>
              </div>
              <div v-if="getUnreceivedEmailCount(group) > group.unreceivedEmails.length" class="email-list-note">
                已显示前 {{ group.unreceivedEmails.length }} 个，共 {{ getUnreceivedEmailCount(group) }} 个
              </div>
            </div>
          </el-card>
        </div>

        <el-table :data="group.items" size="small" style="width: 100%">
          <el-table-column prop="senderLabel" label="发件人" min-width="180" show-overflow-tooltip />
          <el-table-column prop="email" label="收件邮箱" min-width="220" show-overflow-tooltip />
          <el-table-column prop="date" label="最近发送时间" min-width="180" show-overflow-tooltip />
        </el-table>
        <div v-if="getItemCount(group) > group.items.length" class="email-list-note table-note">
          发件记录已显示前 {{ group.items.length }} 条，共 {{ getItemCount(group) }} 条
        </div>
      </el-card>
    </div>

    <el-pagination
      v-if="senderTotal > senderPageSize && selectedSenders.length === 0"
      v-model:current-page="senderPage"
      v-model:page-size="senderPageSize"
      class="sender-pagination"
      background
      layout="total, sizes, prev, pager, next, jumper"
      :page-sizes="[10, 20, 50, 100]"
      :total="senderTotal"
      @size-change="handleSenderPageSizeChange"
      @current-change="handleSenderPageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

interface EmailRecord {
  email: string
}

interface PostRecord {
  send: string
  subject: string
  text: string
  html: string
  date: string
  verificationCode?: string
}

interface SenderGroupItem extends PostRecord {
  email: string
  senderLabel: string
  senderIdentity: string
}

interface SenderRecipientSummary {
  email: string
  count: number
  latestDate: string
}

interface BackendSenderFilterResult {
  receivedRecipients: SenderRecipientSummary[]
  receivedRecipientCount?: number
  unreceivedEmails: string[]
  unreceivedEmailCount?: number
  items: SenderGroupItem[]
  itemCount?: number
  selectedSenders: string[]
}

interface SenderGroup {
  sender: string
  senderIdentity?: string
  items: SenderGroupItem[]
  itemCount?: number
  latestVerificationCode: string
  receivedRecipients: SenderRecipientSummary[]
  receivedRecipientCount?: number
  unreceivedEmails: string[]
  unreceivedEmailCount?: number
}

interface SenderOption {
  label: string
  value: string
  count?: number
}

const selectedSenders = ref<string[]>([])
const showOnlyHasUnreceived = ref(false)
const senderFilterLoading = ref(false)
const senderGroupsLoading = ref(false)
const backendSenderFilterResult = ref<BackendSenderFilterResult | null>(null)
const backendGroupList = ref<SenderGroup[] | null>(null)
const backendSenderOptions = ref<SenderOption[]>([])
const senderSearch = ref('')
const senderSort = ref<'updated' | 'latest' | 'count'>('latest')
const senderPage = ref(1)
const senderPageSize = ref(20)
const senderTotal = ref(0)
const LIST_PREVIEW_LIMIT = 5
const LIST_ITEM_LIMIT = 5
const DETAIL_PREVIEW_LIMIT = 30
const DETAIL_ITEM_LIMIT = 30
let senderSearchTimer: ReturnType<typeof setTimeout> | undefined

const extractVerificationCode = (subject = '', text = '', html = '') => {
  const rawContent = [subject, text, html]
    .filter(Boolean)
    .join('\n')

  if (!rawContent) return ''

  const normalized = rawContent
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|tr|li|h\d)>/gi, '\n')
    .replace(/<[^>]*>/g, ' ')
    .replace(/\r/g, '')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n+/g, '\n')
    .trim()

  const keywords = ['验证码', '校验码', '验证代码', '代码', '临时验证码', '临时代码', 'verification code', 'verification codes', 'security code', 'security codes', 'one-time code', 'temporary code', 'otp', 'code']
  const candidates: Array<{ code: string, score: number }> = []

  const addCandidate = (code: string, score: number) => {
    if (!code) return
    candidates.push({ code, score })
  }

  const normalizeCandidate = (code: string) => /^\d+$/.test(code) ? code : code.toUpperCase()

  const subjectNormalized = (subject || '').trim()
  const subjectHasKeyword = keywords.some(keyword => subjectNormalized.toLowerCase().includes(keyword.toLowerCase()))

  for (const match of subjectNormalized.matchAll(/\b([A-Za-z0-9]{4,8})\b/g)) {
    const code = (match[1] || '').trim()
    if (!code) continue
    if (!(/^[0-9]{4,8}$/.test(code) || (/^[A-Za-z0-9]{4,8}$/.test(code) && /[A-Za-z]/.test(code) && /\d/.test(code)))) continue
    addCandidate(normalizeCandidate(code), 360 + (subjectHasKeyword ? 120 : 0) - code.length)
  }

  const lines = normalized.split('\n').map(line => line.trim()).filter(Boolean)
  lines.forEach((line, index) => {
    const prev = lines[index - 1] || ''
    const next = lines[index + 1] || ''
    const context = `${prev} ${line} ${next}`.toLowerCase()
    const hasKeywordNearby = keywords.some(keyword => context.includes(keyword.toLowerCase()))

    for (const match of line.matchAll(/\b([A-Za-z0-9]{4,8})\b/g)) {
      const code = (match[1] || '').trim()
      if (!code) continue
      if (!(/^[0-9]{4,8}$/.test(code) || (/^[A-Za-z0-9]{4,8}$/.test(code) && /[A-Za-z]/.test(code) && /\d/.test(code)))) continue
      addCandidate(normalizeCandidate(code), (line === code ? 260 : 180) + (hasKeywordNearby ? 180 : 0) - code.length)
    }
  })

  const keywordPatterns = [
    /(验证码|校验码|验证代码|代码|临时验证码|临时代码|verification code|verification codes|security code|security codes|one-time code|temporary code|otp|code)(?:[^A-Za-z0-9]{0,20})([A-Za-z0-9]{4,8})/ig,
    /([A-Za-z0-9]{4,8})(?:[^A-Za-z0-9]{0,20})(验证码|校验码|验证代码|代码|临时验证码|临时代码|verification code|verification codes|security code|security codes|one-time code|temporary code|otp|code)/ig,
  ]

  for (const pattern of keywordPatterns) {
    for (const match of normalized.matchAll(pattern)) {
      const code = (match[2] || match[1] || '').trim()
      if (!code) continue
      if (!(/^[0-9]{4,8}$/.test(code) || (/^[A-Za-z0-9]{4,8}$/.test(code) && /[A-Za-z]/.test(code) && /\d/.test(code)))) continue
      addCandidate(normalizeCandidate(code), /^\d+$/.test(code) ? 320 - code.length : 340 - code.length)
    }
  }

  for (const match of normalized.matchAll(/\b([A-Za-z0-9]{4,8})\b/g)) {
    const code = (match[1] || '').trim()
    if (!code) continue
    if (!(/^[0-9]{4,8}$/.test(code) || (/^[A-Za-z0-9]{4,8}$/.test(code) && /[A-Za-z]/.test(code) && /\d/.test(code)))) continue

    const index = match.index || 0
    const context = normalized.slice(Math.max(0, index - 60), Math.min(normalized.length, index + code.length + 60)).toLowerCase()
    const hasKeywordNearby = keywords.some(keyword => context.includes(keyword.toLowerCase()))
    const mixedBonus = /^[0-9]{4,8}$/.test(code) ? 120 : 100
    const contextBonus = hasKeywordNearby ? 170 : 0
    addCandidate(normalizeCandidate(code), mixedBonus + contextBonus - code.length)
  }

  candidates.sort((a, b) => b.score - a.score)
  return candidates[0]?.code || ''
}

const normalizeSenderLabel = (value: string) => {
  return (value || '').replace(/\s+/g, ' ').trim().toLowerCase()
}

const normalizeEmail = (value: string) => {
  return (value || '').trim().toLowerCase()
}

const getItemCount = (group: SenderGroup) => group.itemCount ?? group.items.length
const getReceivedRecipientCount = (group: SenderGroup) => group.receivedRecipientCount ?? group.receivedRecipients.length
const getUnreceivedEmailCount = (group: SenderGroup) => group.unreceivedEmailCount ?? group.unreceivedEmails.length

const extractSenderLabel = (send: string) => {
  const value = (send || '').trim()
  if (!value) return ''

  const quotedMatch = value.match(/"([^"]+)"/)
  if (quotedMatch?.[1]) {
    return quotedMatch[1].trim()
  }

  const displayMatch = value.match(/^([^<]+)</)
  if (displayMatch?.[1]) {
    return displayMatch[1].replace(/"/g, '').trim()
  }

  const addressMatch = value.match(/<([^>]+)>/)
  if (addressMatch?.[1]) {
    return addressMatch[1].trim()
  }

  return value
}

const extractSenderAddress = (send: string) => {
  const value = (send || '').trim()
  if (!value) return ''
  const addressMatch = value.match(/<([^>]+)>/)
  if (addressMatch?.[1]) {
    return addressMatch[1].trim()
  }
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
    return value
  }
  return ''
}

const normalizeSenderIdentity = (send: string) => {
  const label = extractSenderLabel(send)
  const address = extractSenderAddress(send)
  const normalizedLabel = normalizeSenderLabel(label)
  const normalizedAddress = normalizeEmail(address)

  const aliasRules = [
    { key: 'nomad', patterns: ['nomad', 'getnomad', 'nomad esim'] },
    { key: 'openai', patterns: ['openai', 'tm.openai.com', 'noreply@tm.openai.com'] },
    { key: 'microsoft', patterns: ['microsoft', 'accountprotection.microsoft.com', 'maccount@microsoft.com', 'outlook'] },
  ]

  for (const rule of aliasRules) {
    if (rule.patterns.some(pattern => normalizedLabel.includes(pattern) || normalizedAddress.includes(pattern))) {
      return rule.key
    }
  }

  return normalizedAddress || normalizedLabel
}

const copyText = async (text: string, successMessage = '复制成功') => {
  if (!text) {
    ElMessage.warning('没有可复制的内容')
    return
  }

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.focus()
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    ElMessage.success(successMessage)
  } catch {
    ElMessage.error('复制失败')
  }
}

const formatDateTime = (value: string) => {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

const rawMailList = computed(() => JSON.parse(localStorage.getItem('localMailList') || '[]') as EmailRecord[])
const allEmails = computed(() => rawMailList.value.map(item => item.email))

const allMailboxItems = computed<SenderGroupItem[]>(() => {
  const items: SenderGroupItem[] = []

  rawMailList.value.forEach(record => {
    const inboxPosts = JSON.parse(localStorage.getItem(record.email + 'INBOX') || '[]') as PostRecord[]
    const junkPosts = JSON.parse(localStorage.getItem(record.email + 'Junk') || '[]') as PostRecord[]
    const mergedPosts = [...inboxPosts, ...junkPosts]

    mergedPosts.forEach(post => {
      const senderLabel = extractSenderLabel(post.send)
      const senderIdentity = normalizeSenderIdentity(post.send)
      if (!senderLabel || !senderIdentity) return

      items.push({
        ...post,
        email: record.email,
        senderLabel,
        senderIdentity,
        verificationCode: post.verificationCode || extractVerificationCode(post.subject || '', post.text || '', post.html || ''),
      })
    })
  })

  return items
})

const buildSenderGroup = (sender: string, rawItems: SenderGroupItem[]) => {
  const sortedItems = [...rawItems].sort((a, b) => new Date(b.date || 0).getTime() - new Date(a.date || 0).getTime())
  const latestVerificationCode = sortedItems.find(item => item.verificationCode)?.verificationCode || ''

  const recipientMap = new Map<string, SenderRecipientSummary>()
  sortedItems.forEach(item => {
    const normalizedEmail = normalizeEmail(item.email)
    const current = recipientMap.get(normalizedEmail)
    if (!current) {
      recipientMap.set(normalizedEmail, {
        email: item.email,
        count: 1,
        latestDate: item.date || ''
      })
      return
    }

    current.count += 1
    if (new Date(item.date || 0).getTime() > new Date(current.latestDate || 0).getTime()) {
      current.latestDate = item.date || current.latestDate
      current.email = item.email
    }
  })

  const receivedRecipients = Array.from(recipientMap.values())
    .sort((a, b) => new Date(b.latestDate || 0).getTime() - new Date(a.latestDate || 0).getTime())
  const receivedEmailSet = new Set(receivedRecipients.map(item => normalizeEmail(item.email)))
  const unreceivedEmails = allEmails.value.filter(email => !receivedEmailSet.has(normalizeEmail(email)))

  return {
    sender,
    items: sortedItems.slice(0, DETAIL_ITEM_LIMIT),
    itemCount: sortedItems.length,
    latestVerificationCode,
    receivedRecipients: receivedRecipients.slice(0, DETAIL_PREVIEW_LIMIT),
    receivedRecipientCount: receivedRecipients.length,
    unreceivedEmails: unreceivedEmails.slice(0, DETAIL_PREVIEW_LIMIT),
    unreceivedEmailCount: unreceivedEmails.length,
  }
}

const groupList = computed<SenderGroup[]>(() => {
  const senderMap = new Map<string, { sender: string, items: SenderGroupItem[] }>()

  allMailboxItems.value.forEach(item => {
    if (!senderMap.has(item.senderIdentity)) {
      senderMap.set(item.senderIdentity, {
        sender: item.senderLabel,
        items: []
      })
    }
    senderMap.get(item.senderIdentity)!.items.push(item)
  })

  return Array.from(senderMap.values())
    .map(({ sender, items }) => buildSenderGroup(sender, items))
    .sort((a, b) => b.items.length - a.items.length)
})

const effectiveGroupList = computed(() => {
  return backendGroupList.value && backendGroupList.value.length > 0
    ? backendGroupList.value
    : groupList.value
})

const senderOptions = computed<SenderOption[]>(() => (
  backendSenderOptions.value.length > 0
    ? backendSenderOptions.value
    : effectiveGroupList.value.map(group => ({
        label: group.sender,
        value: group.senderIdentity || group.sender,
        count: getItemCount(group),
      }))
))

const selectedSenderLabels = computed(() => {
  const optionMap = new Map(senderOptions.value.map(option => [option.value, option.label]))
  return selectedSenders.value.map(value => optionMap.get(value) || value)
})

const combinedSelectedGroup = computed<SenderGroup | null>(() => {
  if (selectedSenders.value.length === 0) {
    return null
  }

  const normalizedSelectedSet = new Set(selectedSenders.value.map(item => normalizeSenderLabel(item)))
  const combinedItems = allMailboxItems.value.filter(item => {
    return normalizedSelectedSet.has(normalizeSenderLabel(item.senderLabel)) || normalizedSelectedSet.has(item.senderIdentity)
  })
  const title = selectedSenders.value.length === 1
    ? (selectedSenderLabels.value[0] || '')
    : `组合筛选：${selectedSenderLabels.value.join(' + ')}`

  return buildSenderGroup(title, combinedItems)
})

const fetchSenderFilter = async () => {
  if (selectedSenders.value.length === 0) {
    backendSenderFilterResult.value = null
    return
  }

  senderFilterLoading.value = true
  try {
    const response = await fetch('/api/sender_filter', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        password: localStorage.getItem('apiPassword') || '',
        senders: selectedSenders.value,
        mailboxes: [],
        previewLimit: DETAIL_PREVIEW_LIMIT,
        itemLimit: DETAIL_ITEM_LIMIT
      })
    })
    const payload = await response.json()
    if (payload.code === '200' || payload.code === 200) {
      backendSenderFilterResult.value = payload.data
    } else {
      ElMessage.error(payload.message || '发件人筛选失败')
    }
  } catch (error) {
    ElMessage.error('发件人筛选失败')
  } finally {
    senderFilterLoading.value = false
  }
}

const loadBackendSenderGroups = async () => {
  senderGroupsLoading.value = true
  try {
    const response = await fetch('/api/sender_groups', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        password: localStorage.getItem('apiPassword') || '',
        page: senderPage.value,
        pageSize: senderPageSize.value,
        search: senderSearch.value,
        sort: senderSort.value,
        previewLimit: LIST_PREVIEW_LIMIT,
        itemLimit: LIST_ITEM_LIMIT
      })
    })
    const payload = await response.json()
    if (payload.code === '200' || payload.code === 200) {
      if (Array.isArray(payload.data)) {
        backendGroupList.value = payload.data
        senderTotal.value = payload.data.length
        backendSenderOptions.value = payload.data.map((group: SenderGroup) => ({
          label: group.sender,
          value: group.senderIdentity || group.sender,
          count: getItemCount(group),
        }))
      } else {
        backendGroupList.value = Array.isArray(payload.data?.records) ? payload.data.records : null
        senderTotal.value = Number(payload.data?.total || 0)
        backendSenderOptions.value = Array.isArray(payload.data?.senderOptionRecords)
          ? payload.data.senderOptionRecords
          : (Array.isArray(payload.data?.senderOptions)
              ? payload.data.senderOptions.map((sender: string) => ({ label: sender, value: sender }))
              : [])
      }
    }
  } catch {
    backendGroupList.value = null
    senderTotal.value = 0
  } finally {
    senderGroupsLoading.value = false
  }
}

const handleSenderPageChange = (page: number) => {
  senderPage.value = page
  loadBackendSenderGroups()
}

const handleSenderPageSizeChange = (pageSize: number) => {
  senderPageSize.value = pageSize
  senderPage.value = 1
  loadBackendSenderGroups()
}

const handleSenderSortChange = () => {
  senderPage.value = 1
  loadBackendSenderGroups()
}

const reloadSenderGroups = () => {
  backendSenderFilterResult.value = null
  loadBackendSenderGroups()
  fetchSenderFilter()
}

const displayedGroupList = computed(() => {
  const groups = selectedSenders.value.length === 0
    ? effectiveGroupList.value
    : (() => {
        if (backendSenderFilterResult.value) {
          const title = selectedSenders.value.length === 1
            ? (selectedSenderLabels.value[0] || '')
            : `组合筛选：${selectedSenderLabels.value.join(' + ')}`
          const sortedItems = [...backendSenderFilterResult.value.items].sort((a, b) => new Date(b.date || 0).getTime() - new Date(a.date || 0).getTime())
          return [{
            sender: title,
            items: sortedItems,
            itemCount: backendSenderFilterResult.value.itemCount ?? sortedItems.length,
            latestVerificationCode: sortedItems.find(item => item.verificationCode)?.verificationCode || '',
            receivedRecipients: backendSenderFilterResult.value.receivedRecipients,
            receivedRecipientCount: backendSenderFilterResult.value.receivedRecipientCount,
            unreceivedEmails: backendSenderFilterResult.value.unreceivedEmails,
            unreceivedEmailCount: backendSenderFilterResult.value.unreceivedEmailCount,
          }]
        }
        return combinedSelectedGroup.value ? [combinedSelectedGroup.value] : []
      })()

  return groups.filter(group => {
    const unreceivedMatched = !showOnlyHasUnreceived.value || getUnreceivedEmailCount(group) > 0
    return unreceivedMatched
  })
})

watch(selectedSenders, () => {
  fetchSenderFilter()
}, { deep: true })

watch(senderSearch, () => {
  if (senderSearchTimer) {
    clearTimeout(senderSearchTimer)
  }
  senderSearchTimer = setTimeout(() => {
    senderPage.value = 1
    loadBackendSenderGroups()
  }, 300)
})

onMounted(() => {
  loadBackendSenderGroups()
  fetchSenderFilter()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.toolbar-input {
  width: 320px;
}

.toolbar-sort {
  width: 140px;
}

.sender-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.sender-option-count {
  color: #909399;
  font-size: 12px;
}

.groups-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 220px;
}

.group-card {
  width: 100%;
}

.group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.group-header-main,
.group-header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.group-title {
  font-weight: 600;
  color: #303133;
}

.group-count,
.latest-code {
  color: #909399;
  font-size: 13px;
}

.classification-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.classification-card {
  border: 1px solid #ebeef5;
}

.classification-title {
  font-weight: 600;
}

.email-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 320px;
  overflow-y: auto;
}

.email-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 8px 0;
  border-bottom: 1px dashed #ebeef5;
}

.email-item-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.email-hint {
  color: #909399;
  font-size: 12px;
}

.email-list-note {
  color: #909399;
  font-size: 12px;
  padding: 6px 0;
}

.table-note {
  margin-top: 8px;
  text-align: right;
}

.sender-pagination {
  justify-content: flex-end;
  margin-top: 16px;
}

.email-item:last-child {
  border-bottom: none;
}

@media (max-width: 768px) {
  .toolbar-input,
  .toolbar-sort {
    width: 100%;
  }
}
</style>
