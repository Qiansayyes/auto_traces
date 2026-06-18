<template>
    <div class="home-container">
        <el-form :model="toolForm" :rules="toolRules" ref="formRef" class="mail-toolbar">
            <div class="toolbar-grid">
                <section class="toolbar-section toolbar-section-config">
                    <div class="toolbar-section-title">基础设置</div>
                    <div class="toolbar-fields">
                        <el-form-item class="toolbar-form-item" label="分隔符" label-width="58px">
                            <el-input v-model="toolForm.splitSymbol" class="split-input" />
                        </el-form-item>
                        <el-form-item class="toolbar-form-item password-item" label="接口密码" label-width="72px">
                            <el-input
                                v-model="toolForm.apiPassword"
                                type="password"
                                show-password
                                placeholder="服务器接口密码"
                            />
                        </el-form-item>
                        <el-button class="toolbar-load-button" type="primary" :loading="accountsLoading" @click="loadAccountsPage(true)">
                            加载邮箱
                        </el-button>
                    </div>
                </section>

                <section class="toolbar-section toolbar-section-filter">
                    <div class="toolbar-section-title">筛选</div>
                    <div class="toolbar-fields">
                        <el-form-item class="toolbar-form-item email-search-item" label="搜索邮箱" label-width="72px">
                            <el-input
                                v-model="toolForm.emailSearch"
                                clearable
                                placeholder="输入邮箱"
                                @keyup.enter="handleAccountFilterChange"
                                @clear="handleAccountFilterChange"
                            />
                        </el-form-item>
                        <el-form-item class="toolbar-form-item project-usage-item" label="项目状态" label-width="72px">
                            <el-select
                                v-model="toolForm.projectUsage"
                                placeholder="项目状态"
                                @change="handleAccountFilterChange"
                            >
                                <el-option label="全部" value="all" />
                                <el-option label="做过项目" value="done" />
                                <el-option label="未做项目" value="not_done" />
                            </el-select>
                        </el-form-item>
                        <el-form-item class="toolbar-form-item sender-filter-item" label="项目分类" label-width="72px">
                            <el-select
                                v-model="toolForm.excludeDoneSenderIdentity"
                                clearable
                                filterable
                                :loading="senderFilterLoading"
                                placeholder="选择发件人分类"
                                @change="handleSenderUnusedFilterChange"
                            >
                                <el-option
                                    v-for="sender in senderFilterOptions"
                                    :key="sender.value"
                                    :label="formatSenderOption(sender)"
                                    :value="sender.value"
                                />
                            </el-select>
                        </el-form-item>
                        <el-button class="toolbar-secondary-button" type="primary" plain @click="handleAccountFilterChange">
                            <el-icon>
                                <SearchIcon />
                            </el-icon>
                            搜索
                        </el-button>
                        <el-button class="toolbar-secondary-button" :loading="senderFilterLoading" @click="loadSenderFilterOptions">
                            刷新分类
                        </el-button>
                    </div>
                </section>

                <section class="toolbar-section toolbar-section-actions toolbar-section-import">
                    <div class="toolbar-section-title">导入</div>
                    <div class="toolbar-button-row">
                        <el-upload ref="upload" :limit="1" :show-file-list="false" :on-exceed="handleExceed"
                            :auto-upload="false" :on-change="handleFileChange" accept=".txt,.csv" class="custom-upload">
                            <template #trigger>
                                <el-button class="toolbar-action-button" type="primary">
                                    <el-icon>
                                        <FilesIcon />
                                    </el-icon>
                                    {{ fileName || '选择文件' }}
                                </el-button>
                            </template>
                        </el-upload>
                        <el-button class="toolbar-action-button" type="success" @click="handleAdd">
                            <el-icon>
                                <UploadIcon />
                            </el-icon>
                            导入邮箱
                        </el-button>
                        <el-button class="toolbar-action-button" type="success" @click="dialogCopyVisible = true">
                            <el-icon>
                                <CopyDocumentIcon />
                            </el-icon>
                            粘贴导入
                        </el-button>
                    </div>
                </section>

                <section class="toolbar-section toolbar-section-actions toolbar-section-batch">
                    <div class="toolbar-section-title">批量操作</div>
                    <div class="toolbar-button-row">
                        <el-button class="toolbar-action-button" type="warning" @click="handleBatchExport">
                            <el-icon>
                                <DownloadIcon />
                            </el-icon>
                            批量导出
                        </el-button>
                        <el-button class="toolbar-action-button" type="warning" @click="handleExportAll">
                            <el-icon>
                                <DownloadIcon />
                            </el-icon>
                            全部导出
                        </el-button>
                        <el-button class="toolbar-action-button" type="danger" plain @click="handleBatchDelete">
                            <el-icon>
                                <DeleteIcon />
                            </el-icon>
                            批量删除
                        </el-button>
                        <el-button class="toolbar-action-button" type="danger" @click="handleDeleteAll">
                            <el-icon>
                                <DeleteFilledIcon />
                            </el-icon>
                            全部删除
                        </el-button>
                    </div>
                </section>
            </div>
        </el-form>

        <div class="table">
            <el-table
                ref="multipleTableRef"
                v-loading="accountsLoading"
                :height="calcTableHeight"
                stripe
                :data="tableMailList"
                :row-key="getEmailRowKey"
                :row-class-name="getMailboxRowClassName"
                class="mail-table"
                style="width: 100%"
                size="small"
                center
                @row-click="handleMobileRowClick"
                @selection-change="handleSelectionChange"
            >
                <el-table-column type="selection" width="44" class-name="selection-column" label-class-name="selection-column" />
                <el-table-column label="邮箱" min-width="240" show-overflow-tooltip>
                    <template #default="scope">
                        <div class="email-cell">
                            <span>{{ scope.row.email }}</span>
                            <el-button size="small" type="primary" @click.stop="copyText(scope.row.email, '邮箱已复制')">
                                复制
                            </el-button>
                        </div>
                    </template>
                </el-table-column>

                <el-table-column label="最新邮件" min-width="180">
                    <template #default="scope">
                        <div class="latest-mail-cell">
                            <span>{{ formatLatestMail(scope.row.email).label }}</span>
                            <span class="latest-mail-hint">{{ formatLatestMail(scope.row.email).time }}</span>
                        </div>
                    </template>
                </el-table-column>

                <el-table-column label="最新验证码" width="130">
                    <template #default="scope">
                        <div class="verification-code-cell">
                            <span>{{ getLatestVerificationCode(scope.row.email) || '-' }}</span>
                        </div>
                    </template>
                </el-table-column>

                <el-table-column label="操作" width="330" class-name="desktop-actions-column" label-class-name="desktop-actions-column">
                    <template #default="scope">
                        <div class="desktop-row-actions">
                            <el-button
                                size="small"
                                type="info"
                                :loading="rowRefreshLoadingMap[scope.row.email]"
                                @click.stop="handleFetchLatest(scope.row)"
                            >
                                获取
                            </el-button>
                            <el-button
                                size="small"
                                type="warning"
                                @click.stop="copyLatestVerificationCode(scope.row)"
                            >
                                复制
                            </el-button>
                            <el-button size="small" type="primary" @click.stop="handleEdit(scope.row, scope.$index)">
                                编辑
                            </el-button>
                            <el-button
                                size="small"
                                type="success"
                                :loading="rowCollectLoadingMap[scope.row.email]"
                                :disabled="rowRefreshLoadingMap[scope.row.email]"
                                @click.stop="handleCollect(scope.row)"
                            >
                                收件
                            </el-button>
                            <el-button size="small" type="danger" @click.stop="handleDelete(scope.row, scope.$index)">
                                删除
                            </el-button>
                        </div>
                    </template>
                </el-table-column>
            </el-table>
        </div>
        <div class="pagination" ref="tablePaginationRef">
            <el-pagination background v-model:current-page="tablePagination.currentPage"
                v-model:page-size="tablePagination.pageSize" :page-sizes="[10, 20, 50, 100]"
                :size="tablePagination.defaultSize" layout="total, sizes, prev, pager, next"
                :total="tablePagination.total" @size-change="handleTableSizeChange"
                @current-change="handleTableCurrentChange" />
        </div>

        <div v-if="isMobileLayout && activeMobileEmail" class="mobile-action-spacer" />
        <div v-if="isMobileLayout && activeMobileEmail" class="mobile-email-actions">
            <div class="mobile-action-summary">
                <span class="mobile-action-email">{{ activeMobileEmail.email }}</span>
                <span class="mobile-action-status">{{ formatLatestMail(activeMobileEmail.email).time }}</span>
            </div>
            <div class="mobile-action-buttons">
                <el-button
                    type="primary"
                    size="large"
                    :loading="rowRefreshLoadingMap[activeMobileEmail.email]"
                    @click="handleFetchLatest(activeMobileEmail)"
                >
                    <el-icon><RefreshIcon /></el-icon>
                    取件
                </el-button>
                <el-button type="warning" size="large" @click="copyLatestVerificationCode(activeMobileEmail)">
                    <el-icon><DocumentCopyIcon /></el-icon>
                    验证码
                </el-button>
                <el-button
                    type="success"
                    size="large"
                    :loading="rowCollectLoadingMap[activeMobileEmail.email]"
                    :disabled="rowRefreshLoadingMap[activeMobileEmail.email]"
                    @click="handleCollect(activeMobileEmail)"
                >
                    <el-icon><MessageIcon /></el-icon>
                    邮件
                </el-button>
                <el-dropdown trigger="click" placement="top-end" @command="handleMobileMoreCommand">
                    <el-button size="large" class="mobile-more-button">
                        <el-icon><MoreFilledIcon /></el-icon>
                    </el-button>
                    <template #dropdown>
                        <el-dropdown-menu>
                            <el-dropdown-item command="copyEmail">复制邮箱</el-dropdown-item>
                            <el-dropdown-item command="edit">编辑</el-dropdown-item>
                            <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                        </el-dropdown-menu>
                    </template>
                </el-dropdown>
            </div>
        </div>

        <el-dialog v-model="dialogCopyVisible" title="粘贴导入" width="800">
            <el-text class="mx-1" type="warning">请粘贴邮箱地址（每个邮箱地址占一行）</el-text>
            <div class="copy-textarea-container">
                <el-input v-model="copyTextarea" style="width: 100%" :rows="20" type="textarea" placeholder="请粘贴邮箱地址" />
            </div>
            <template #footer>
                <div class="dialog-footer">
                    <el-button @click="dialogCopyVisible = false">取消</el-button>
                    <el-button type="primary" @click="handlePasteAdd">
                        导入
                    </el-button>
                </div>
            </template>
        </el-dialog>

        <el-dialog v-model="dialogEditVisible" title="编辑" width="800">

            <el-form :model="editForm" ref="editFormRef" label-width="120px">
                <el-form-item label="邮箱" prop="email">
                    <el-input v-model="editForm.email" placeholder="请输入邮箱" />
                </el-form-item>
                <el-form-item label="密码" prop="password">
                    <el-input v-model="editForm.password" type="password" placeholder="请输入密码" />
                </el-form-item>
            </el-form>

            <template #footer>
                <div class="dialog-footer">
                    <el-button @click="dialogEditVisible = false">取消</el-button>
                    <el-button type="primary" @click="handleSave">
                        保存
                    </el-button>
                </div>
            </template>
        </el-dialog>

        <el-dialog v-model="dialogEmailVisible" :title="postTitle" width="96vw" class="mail-list-dialog">
            <div class="mail-dialog-actions">
                <el-button type="primary" @click="handleReceive()" :loading="postLoading">
                    <el-icon><RefreshIcon /></el-icon>
                    自动收件
                </el-button>
                <el-button type="danger" @click="handleClear">
                    <el-icon><DeleteIcon /></el-icon>
                    清空
                </el-button>
            </div>

            <div class="table post-dialog-table">
                <el-table
                    v-model="postList"
                    class="post-table"
                    style="width: 100%"
                    size="small"
                    :data="postList"
                    :fit="false"
                    empty-text="暂无邮件"
                    height="calc(100vh - 260px)"
                >
                    <el-table-column label="发件人" min-width="360" show-overflow-tooltip>
                        <template #default="scope">
                            <span class="post-wrap-cell">{{ scope.row.send || '-' }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="主题" min-width="300" show-overflow-tooltip>
                        <template #default="scope">
                            <span class="post-wrap-cell">{{ scope.row.subject || '-' }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column prop="mailboxLabel" label="位置" width="90" show-overflow-tooltip />
                    <el-table-column label="验证码" width="150">
                        <template #default="scope">
                            <div class="verification-code-cell">
                                <span>{{ getPostVerification(scope.row).code || '-' }}</span>
                                <el-button
                                    v-if="getPostVerification(scope.row).code"
                                    size="small"
                                    type="primary"
                                    @click.stop="copyText(getPostVerification(scope.row).code, '验证码已复制')"
                                >
                                    复制
                                </el-button>
                            </div>
                        </template>
                    </el-table-column>
                    <el-table-column label="识别依据" width="130" show-overflow-tooltip>
                        <template #default="scope">
                            {{ getPostVerification(scope.row).source || '-' }}
                        </template>
                    </el-table-column>
                    <el-table-column label="链接" width="110">
                        <template #default="scope">
                            <el-button
                                v-if="getPostPrimaryLink(scope.row)"
                                size="small"
                                type="success"
                                @click.stop="copyText(getPostPrimaryLink(scope.row), '链接已复制')"
                            >
                                复制
                            </el-button>
                            <span v-else>-</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="文本" min-width="300" show-overflow-tooltip>
                        <template #default="scope">
                            <span class="post-preview-cell">{{ getPostPreview(scope.row) }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="日期" width="190" show-overflow-tooltip>
                        <template #default="scope">
                            <span class="post-date-cell">{{ formatDateTime(scope.row.date) }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="操作" width="90" fixed="right">
                        <template #default="scope">
                            <el-button size="small" type="primary" @click="viewPost(scope.row)">
                                查看
                            </el-button>
                        </template>
                    </el-table-column>
                </el-table>
            </div>
        </el-dialog>

        <el-dialog v-model="dialogPostVisible" title="邮件内容" width="92vw" class="mail-content-dialog">
            <div v-if="dialogPostLinks.length" class="mail-copy-links">
                <div v-for="link in dialogPostLinks" :key="link.url" class="mail-copy-link-row">
                    <span class="mail-copy-link-label">{{ link.label }}</span>
                    <span class="mail-copy-link-url">{{ link.url }}</span>
                    <el-button size="small" type="success" @click="copyText(link.url, `${link.label} 链接已复制`)">
                        复制
                    </el-button>
                </div>
            </div>
            <div v-loading="dialogPostLoading" class="mail-content-frame-wrap">
                <iframe
                    :srcdoc="dialogEmailContent"
                    sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
                    class="mail-content-frame"
                ></iframe>
            </div>
        </el-dialog>

    </div>
</template>

<script lang="ts" setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { genFileId, ElMessage, ElMessageBox } from 'element-plus'
import {
    Upload as UploadIcon,
    Plus as FilesIcon,
    Download as DownloadIcon,
    Delete as DeleteIcon,
    DeleteFilled as DeleteFilledIcon,
    CopyDocument as CopyDocumentIcon,
    DocumentCopy as DocumentCopyIcon,
    Message as MessageIcon,
    MoreFilled as MoreFilledIcon,
    Refresh as RefreshIcon,
    Search as SearchIcon,
} from '@element-plus/icons-vue'

import type { UploadInstance, UploadProps, UploadRawFile } from 'element-plus'

const upload = ref<UploadInstance>()
const emailList = ref<string[]>([])
const fileName = ref<string>('')

const handleExceed: UploadProps['onExceed'] = (files) => {
    upload.value!.clearFiles()
    const file = files[0] as UploadRawFile
    file.uid = genFileId()
    upload.value!.handleStart(file)
}

const handleFileChange: UploadProps['onChange'] = (file, fileList = []) => {
    if (fileList.length === 0) {
        fileName.value = ''
        return
    }

    const rawFile = fileList[0]?.raw as UploadRawFile | undefined

    if (!rawFile) {
        fileName.value = ''
        return
    }

    if (!rawFile.type.match(/text\/(plain|csv)/) &&
        !rawFile.name.endsWith('.txt') &&
        !rawFile.name.endsWith('.csv')) {
        ElMessage.error('请上传 .txt 或 .csv 格式的文件')
        upload.value!.clearFiles()
        fileName.value = ''
        return
    }

    fileName.value = rawFile.name.length > 10 ? rawFile.name.slice(0, 10) + '...' : rawFile.name

    parseFileContent(rawFile)
}

const parseFileContent = (file: UploadRawFile) => {
    const reader = new FileReader()

    reader.onload = (e) => {
        try {
            const content = e.target?.result as string
            if (!content) {
                ElMessage.error('文件内容为空')
                return
            }

            const matches = content.split('\n');

            if (matches) {
                emailList.value = matches
            } else {
                ElMessage.warning('未在文件中找到邮箱地址')
                emailList.value = []
            }
        } catch (error) {
            console.error('解析文件失败:', error)
            ElMessage.error('解析文件失败，请检查文件格式')
        }
    }

    reader.onerror = () => {
        ElMessage.error('读取文件失败')
    }

    // 读取文件内容
    reader.readAsText(file)
}

const toolForm = ref({
    splitSymbol: '----',
    apiPassword: localStorage.getItem('apiPassword') || '',
    excludeDoneSenderIdentity: localStorage.getItem('emailExcludeDoneSenderIdentity') || localStorage.getItem('emailExcludeDoneProject') || '',
    emailSearch: localStorage.getItem('emailSearch') || '',
    projectUsage: localStorage.getItem('emailProjectUsage') || 'all'
})

watch(
    () => toolForm.value.apiPassword,
    (value) => {
        localStorage.setItem('apiPassword', value)
    }
)

const toolRules = ref({
    splitSymbol: [{ required: true, message: '请输入分隔符', trigger: 'blur' }]
})

import type { TableInstance } from 'element-plus'
interface Email {
    record_key?: string
    email: string
    password: string
    client_id: string
    refresh_token: string
}

interface SenderOption {
    label: string
    value: string
    count?: number
}

const senderFilterOptions = ref<SenderOption[]>([])
const senderFilterLoading = ref(false)

const formatSenderOption = (sender: SenderOption) => {
    const countText = sender.count ? ` / 已收 ${sender.count}` : ''
    return `${sender.label || sender.value}${countText}`
}

const multipleTableRef = ref<TableInstance>()
const multipleSelection = ref<Email[]>([])

const toggleSelection = (rows?: Email[], ignoreSelectable?: boolean) => {
    if (rows) {
        rows.forEach((row) => {
            multipleTableRef.value!.toggleRowSelection(
                row,
                undefined,
                ignoreSelectable
            )
        })
    } else {
        multipleTableRef.value!.clearSelection()
    }
}

const handleSelectionChange = (val: Email[]) => {
    multipleSelection.value = val
}

const mailList = ref<Email[]>([])
const ALL_MAIL_TOP = 0
const LATEST_MAIL_TOP = 10
const LATEST_RETRY_TOP = 50
const MAILBOX_REFRESH_TOP = 50
const VISIBLE_REFRESH_CONCURRENCY = 3
const MAILBOX_CACHE_LIMIT = 50
const MAILBOX_CACHE_FOLDERS = ['INBOX', 'Junk']
const accountsLoading = ref(false)
const localStorageMailList = () => JSON.parse(localStorage.getItem('localMailList') || '[]') as Email[]
const getEmailRowKey = (row: Email) => row.record_key || row.email
const activeMobileEmail = ref<Email | null>(null)

const isSameEmailRow = (left?: Email | null, right?: Email | null) => {
    if (!left || !right) return false
    return getEmailRowKey(left) === getEmailRowKey(right)
}

const syncActiveMobileEmail = () => {
    if (!activeMobileEmail.value) return
    const current = mailList.value.find(item => isSameEmailRow(item, activeMobileEmail.value))
    activeMobileEmail.value = current || null
}

const handleMobileRowClick = (row: Email) => {
    if (!isMobileLayout.value) return
    activeMobileEmail.value = row
}

const getMailboxRowClassName = ({ row }: { row: Email }) => {
    return isSameEmailRow(row, activeMobileEmail.value) ? 'active-mobile-mail-row' : ''
}

const getCurrentPageRowIndex = (row: Email) => {
    return mailList.value.findIndex(item => isSameEmailRow(item, row))
}

const handleMobileMoreCommand = (command: string) => {
    const row = activeMobileEmail.value
    if (!row) return

    if (command === 'copyEmail') {
        void copyText(row.email, '邮箱已复制')
        return
    }

    const index = getCurrentPageRowIndex(row)
    if (command === 'edit') {
        handleEdit(row, Math.max(0, index))
        return
    }

    if (command === 'delete' && index >= 0) {
        handleDelete(row, index)
    }
}

const parseAccountLines = (lines: string[]) => {
    return lines
        .map(item => item.trim())
        .filter(item => item !== '')
        .map(item => {
            const tempArr = item.split(toolForm.value.splitSymbol)
            return {
                email: (tempArr[0] || '').trim().toLowerCase(),
                password: tempArr[1] || '',
                client_id: tempArr[2] || '',
                refresh_token: tempArr.slice(3).join(toolForm.value.splitSymbol) || ''
            }
        })
        .filter(item => item.email.includes('@'))
}

const importAccountsToDatabase = async (records: Email[], source = 'ui') => {
    if (records.length === 0) return null

    const response = await fetch('/api/accounts/import', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            password: toolForm.value.apiPassword,
            records,
            splitSymbol: toolForm.value.splitSymbol,
            source
        })
    })
    const payload = await response.json()
    if (!(payload.code === '200' || payload.code === 200)) {
        throw new Error(payload.message || payload.error || '账号入库失败')
    }
    return payload.data
}

const deleteAccountsFromDatabase = async (emails: string[], deleteAll = false) => {
    const response = await fetch('/api/accounts/delete', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            password: toolForm.value.apiPassword,
            emails,
            deleteAll,
        })
    })
    const payload = await response.json()
    if (!(payload.code === '200' || payload.code === 200)) {
        throw new Error(payload.message || payload.error || '账号删除失败')
    }
    const deleted = Number(payload.data?.deleted || 0)
    if (deleted < 1) {
        throw new Error(deleteAll ? '未删除任何账号' : '未找到可删除的账号，请刷新列表后重试')
    }
    return payload.data
}

const removeEmailsFromLocalCache = (emails: string[]) => {
    const targets = new Set(emails.map((item) => item.trim().toLowerCase()).filter(Boolean))
    if (targets.size === 0) return

    const cached = localStorageMailList().filter((item) => !targets.has(item.email.trim().toLowerCase()))
    localStorage.setItem('localMailList', JSON.stringify(cached))

    for (const email of targets) {
        localStorage.removeItem(`${email}INBOX`)
        localStorage.removeItem(`${email}Junk`)
    }
}

const syncAccountsToDatabase = async (records: Email[], source = 'localStorage_sync') => {
    try {
        await importAccountsToDatabase(records, source)
    } catch (error) {
        console.warn('同步账号数据库失败:', error)
    }
}

const syncCachedMailboxToDatabase = async (accountEmail: string, mailbox: string) => {
    const cachedPosts = JSON.parse(localStorage.getItem(accountEmail + mailbox) || '[]') as Post[]
    if (cachedPosts.length === 0) return

    await fetch('/api/mail/cache/import', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            password: toolForm.value.apiPassword,
            email: accountEmail,
            mailbox,
            emails: cachedPosts
        })
    })
}

const syncCachedMailToDatabase = async (records: Email[]) => {
    try {
        for (const record of records) {
            await syncCachedMailboxToDatabase(record.email, 'INBOX')
            await syncCachedMailboxToDatabase(record.email, 'Junk')
        }
    } catch (error) {
        console.warn('同步邮件缓存数据库失败:', error)
    }
}

const tablePagination = ref({
    currentPage: 1,
    pageSize: 10,
    defaultSize: 'default',
    total: 0
})

const tableMailList = computed(() => {
    return mailList.value
})

const loadAccountsPage = async (showMessage = false) => {
    accountsLoading.value = true
    try {
        const response = await fetch('/api/accounts/list', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                password: toolForm.value.apiPassword,
                page: tablePagination.value.currentPage,
                pageSize: tablePagination.value.pageSize,
                search: toolForm.value.emailSearch.trim(),
                projectUsage: toolForm.value.projectUsage || 'all',
                excludeSenderCompanyIdentity: toolForm.value.excludeDoneSenderIdentity || ''
            })
        })
        if (response.status === 401) {
            throw new Error('请先填写服务器接口密码，然后重新加载邮箱列表')
        }
        const payload = await response.json()
        if (payload.code === '200' || payload.code === 200) {
            mailList.value = payload.data?.records || []
            tablePagination.value.total = payload.data?.total || 0
            localStorage.setItem('localMailList', JSON.stringify(mailList.value))
            syncActiveMobileEmail()
            if (showMessage) {
                ElMessage.success(`已加载 ${mailList.value.length} / ${tablePagination.value.total} 个邮箱`)
                void refreshVisibleLatestRows([...mailList.value])
            }
        } else {
            throw new Error(payload.message || payload.error || '账号列表加载失败')
        }
    } catch (error) {
        console.warn('账号列表加载失败，回退到浏览器本地列表:', error)
        if (error instanceof Error && error.message.includes('接口密码')) {
            mailList.value = []
            tablePagination.value.total = 0
            ElMessage.warning(error.message)
            return
        }
        if (toolForm.value.excludeDoneSenderIdentity || toolForm.value.projectUsage !== 'all') {
            mailList.value = []
            tablePagination.value.total = 0
            ElMessage.warning('项目筛选需要服务器接口可用，已停止本地回退列表')
            return
        }
        const search = toolForm.value.emailSearch.trim().toLowerCase()
        const fallback = localStorageMailList().filter(row => !search || row.email.toLowerCase().includes(search))
        const start = (tablePagination.value.currentPage - 1) * tablePagination.value.pageSize
        const end = start + tablePagination.value.pageSize
        mailList.value = fallback.slice(start, end)
        tablePagination.value.total = fallback.length
        syncActiveMobileEmail()
        if (showMessage) {
            ElMessage.warning('服务器账号列表加载失败，已显示浏览器本地缓存')
        }
    } finally {
        accountsLoading.value = false
    }
}

const loadSenderFilterOptions = async () => {
    if (!toolForm.value.apiPassword) {
        ElMessage.warning('请先填写接口密码')
        return
    }

    senderFilterLoading.value = true
    try {
        const response = await fetch('/api/sender_options', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                password: toolForm.value.apiPassword,
                limit: 5000,
                sort: 'latest'
            })
        })
        const payload = await response.json()
        if (payload.code === '200' || payload.code === 200) {
            senderFilterOptions.value = Array.isArray(payload.data) ? payload.data : []
        } else {
            throw new Error(payload.message || payload.error || '发件人分类加载失败')
        }
    } catch (error) {
        senderFilterOptions.value = []
        ElMessage.error(error instanceof Error ? error.message : '发件人分类加载失败')
    } finally {
        senderFilterLoading.value = false
    }
}

const handleSenderUnusedFilterChange = async () => {
    await handleAccountFilterChange()
}

const handleAccountFilterChange = async () => {
    localStorage.setItem('emailSearch', toolForm.value.emailSearch.trim())
    localStorage.setItem('emailProjectUsage', toolForm.value.projectUsage || 'all')
    localStorage.setItem('emailExcludeDoneSenderIdentity', toolForm.value.excludeDoneSenderIdentity || '')
    localStorage.removeItem('emailExcludeDoneProject')
    tablePagination.value.currentPage = 1
    await loadAccountsPage()
}

const handleTableSizeChange = async (val: number) => {
    tablePagination.value.pageSize = val
    tablePagination.value.currentPage = 1
    await loadAccountsPage()
}

const handleTableCurrentChange = async (val: number) => {
    tablePagination.value.currentPage = val
    await loadAccountsPage()
}

import type { FormInstance } from 'element-plus'
const formRef = ref<FormInstance>()
const formHeight = ref(0)
const tablePaginationRef = ref<HTMLDivElement>()
const tablePaginationHeight = ref(0)
const viewportWidth = ref(window.innerWidth)
const isMobileLayout = computed(() => viewportWidth.value <= 768)
const calcTableHeight = computed(() => {
    if (isMobileLayout.value) {
        return '64vh'
    }
    return `calc(100vh - ${formHeight.value}px - ${tablePaginationHeight.value}px - 72px)`
})

let formResizeObserver: ResizeObserver | null = null
let paginationResizeObserver: ResizeObserver | null = null
let windowResizeHandler: (() => void) | null = null

const updateAllHeights = () => {
    viewportWidth.value = window.innerWidth

    if (formRef.value?.$el) {
        formHeight.value = formRef.value.$el.offsetHeight || 0
    }

    if (tablePaginationRef.value) {
        tablePaginationHeight.value = tablePaginationRef.value.offsetHeight || 0
    }
}

onMounted(() => {
    if (toolForm.value.apiPassword) {
        void loadSenderFilterOptions()
    }
    void loadAccountsPage()

    nextTick(() => {
        updateAllHeights()
        if (formRef.value?.$el) {
            formResizeObserver = new ResizeObserver(entries => {
                for (const entry of entries) {
                    formHeight.value = entry.contentRect.height
                }
            })
            formResizeObserver.observe(formRef.value.$el)
        }

        if (tablePaginationRef.value) {
            paginationResizeObserver = new ResizeObserver(entries => {
                for (const entry of entries) {
                    tablePaginationHeight.value = entry.contentRect.height
                }
            })
            paginationResizeObserver.observe(tablePaginationRef.value)
        }

        windowResizeHandler = () => {
            updateAllHeights()
        }
        window.addEventListener('resize', windowResizeHandler)
    })
})

// 在组件卸载时清理所有监听器
onBeforeUnmount(() => {
    if (formResizeObserver) {
        formResizeObserver.disconnect()
        formResizeObserver = null
    }

    if (paginationResizeObserver) {
        paginationResizeObserver.disconnect()
        paginationResizeObserver = null
    }

    if (windowResizeHandler) {
        window.removeEventListener('resize', windowResizeHandler)
        windowResizeHandler = null
    }

})

// 批量删除选中的邮箱
const handleBatchDelete = () => {
    if (multipleSelection.value.length === 0) {
        ElMessage.warning('请选择要删除的邮箱')
        return
    }

    ElMessageBox.confirm('确认删除选中的邮箱吗？', '删除确认', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
    }).then(async () => {
        const emails = multipleSelection.value.map((item) => item.email)
        try {
            const result = await deleteAccountsFromDatabase(emails)
            removeEmailsFromLocalCache(emails)
            toggleSelection()
            syncActiveMobileEmail()
            await loadAccountsPage()
            ElMessage.success(`删除成功，已删除 ${result.deleted} 条`)
        } catch (error: any) {
            ElMessage.error(error?.message || '删除失败')
        }
    }).catch(() => {
        ElMessage.info('已取消删除')
    })
}

// 删除所有邮箱
const handleDeleteAll = () => {
    ElMessageBox.confirm('确认删除所有邮箱吗？', '删除确认', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
    }).then(async () => {
        try {
            const result = await deleteAccountsFromDatabase([], true)
            localStorage.setItem('localMailList', JSON.stringify([]))
            toggleSelection()
            activeMobileEmail.value = null
            await loadAccountsPage()
            ElMessage.success(`删除成功，已删除 ${result.deleted} 条`)
        } catch (error: any) {
            ElMessage.error(error?.message || '删除失败')
        }
    }).catch(() => {
        ElMessage.info('已取消删除')
    })
}

// 添加邮箱
const handleAdd = async () => {
    if (emailList.value.length === 0) {
        ElMessage.warning('请先选择文件解析')
        return
    }

    const parsedAccounts = parseAccountLines(emailList.value)
    if (parsedAccounts.length === 0) {
        ElMessage.warning('未解析到有效邮箱记录')
        return
    }

    try {
        const result = await importAccountsToDatabase(parsedAccounts, 'ui_import')
        tablePagination.value.currentPage = 1
        await loadAccountsPage()
        ElMessage.success(`邮箱地址添加成功，共 ${parsedAccounts.length} 条，已入库 ${result?.inserted_or_updated || 0} 条`)
    } catch (error: any) {
        ElMessage.error(`账号入库失败：${error.message}`)
        return
    }

    emailList.value = []
    copyTextarea.value = ''
    dialogCopyVisible.value = false
    fileName.value = ''
    upload.value!.clearFiles()
}

// 粘贴添加邮箱
const dialogCopyVisible = ref(false)
const copyTextarea = ref('')
const handlePasteAdd = async () => {
    emailList.value = copyTextarea.value.split('\n').map(item => item.trim()).filter(item => item !== '')
    await handleAdd()
}

const buildAccountExportContent = (records: Email[]) => {
    return records
        .map(item => [
            item.email,
            item.password,
            item.client_id,
            item.refresh_token,
        ].join(toolForm.value.splitSymbol))
        .join('\n')
}

const downloadTextFile = (filename: string, content: string) => {
    const blob = new Blob([content], { type: 'text/plain' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = filename
    link.click()
    URL.revokeObjectURL(link.href)
}

// 批量导出选中的邮箱
const handleBatchExport = () => {
    if (multipleSelection.value.length === 0) {
        ElMessage.warning('请选择要导出的邮箱')
        return
    }

    ElMessageBox.confirm('确认导出选中的邮箱吗？', '导出确认', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
    }).then(() => {
        downloadTextFile('selected_mails.txt', buildAccountExportContent(multipleSelection.value))
        ElMessage.success('导出成功')
    }).catch(() => {
        ElMessage.info('已取消导出')
    })
}

// 导出所有邮箱
const handleExportAll = () => {

    if (mailList.value.length === 0) {
        ElMessage.warning('请先添加邮箱')
        return
    }

    ElMessageBox.confirm('确认导出所有邮箱吗？', '导出确认', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
    }).then(() => {
        downloadTextFile('all_mails.txt', buildAccountExportContent(mailList.value))
        ElMessage.success('导出成功')
    }).catch(() => {
        ElMessage.info('已取消导出')
    })
}

// 编辑邮箱
const dialogEditVisible = ref(false)
const editForm = ref<Email>({
    email: '',
    password: '',
    client_id: '',
    refresh_token: ''
})
const editIndex = ref(-1)
const handleEdit = (row: Email, index: number) => {
    editIndex.value = (tablePagination.value.currentPage - 1) * tablePagination.value.pageSize + index
    editForm.value = JSON.parse(JSON.stringify(row))
    dialogEditVisible.value = true
}

const handleSave = async () => {
    if (editIndex.value === -1) {
        ElMessage.error('编辑索引错误')
        return
    }

    mailList.value[editIndex.value] = editForm.value
    localStorage.setItem('localMailList', JSON.stringify(mailList.value))
    await syncAccountsToDatabase([editForm.value], 'ui_edit')
    tablePagination.value.total = mailList.value.length
    syncActiveMobileEmail()
    dialogEditVisible.value = false
    ElMessage.success('保存成功')
}

// 删除邮箱
const handleDelete = (row: Email, index: number) => {
    ElMessageBox.confirm('确认删除邮箱 ' + row.email + ' 吗？', '删除确认', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
    }).then(async () => {
        try {
            const result = await deleteAccountsFromDatabase([row.email])
            removeEmailsFromLocalCache([row.email])
            await loadAccountsPage()
            syncActiveMobileEmail()
            ElMessage.success(`删除成功，已删除 ${result.deleted} 条`)
        } catch (error: any) {
            ElMessage.error(error?.message || '删除失败')
        }
    }).catch(() => {
        ElMessage.info('已取消删除')
    })
}

interface Post {
    id?: string
    uid?: number | string | null
    seqno?: number
    send: string
    subject: string
    text: string
    html: string
    date: string
    verificationCode?: string
    verificationSource?: string
    links?: string[]
    linkDetails?: PostLinkDetail[]
    verificationLinks?: string[]
    primaryLink?: string
    primaryLinkText?: string
    primaryLinkLabel?: string
    mailbox?: string
    mailboxLabel?: string
    bodyMode?: 'none' | 'preview' | 'full'
}

type PostBodyMode = 'none' | 'preview' | 'full'
interface PostLinkDetail {
    url: string
    text?: string
    label?: string
    host?: string
    source?: string
    score?: number
    isAction?: boolean
}

interface FetchPostsResult {
    ok: boolean
    posts: Post[]
    freshPosts: Post[]
    errorMessage?: string
}

const dialogEmailVisible = ref(false)
const postList = ref<Post[]>([])
const postTitle = ref('')
const nowPost = ref<Email>({
    email: '',
    password: '',
    client_id: '',
    refresh_token: ''
})

const postLoading = ref(false)
const latestMailMap = ref<Record<string, Post | null>>({})
const rowRefreshLoadingMap = ref<Record<string, boolean>>({})
const rowCollectLoadingMap = ref<Record<string, boolean>>({})
const dialogPostLinks = ref<PostLinkDetail[]>([])

const setRowRefreshLoading = (email: string, value: boolean) => {
    rowRefreshLoadingMap.value = {
        ...rowRefreshLoadingMap.value,
        [email]: value
    }
}

const setRowCollectLoading = (email: string, value: boolean) => {
    rowCollectLoadingMap.value = {
        ...rowCollectLoadingMap.value,
        [email]: value
    }
}

const sortPostsByDate = (posts: Post[]) => {
    return [...posts].sort((a, b) => new Date(b.date || 0).getTime() - new Date(a.date || 0).getTime())
}

const normalizeMailboxName = (value = '') => {
    const text = String(value || '').trim()
    if (!text) return ''
    const lower = text.toLowerCase()
    if (lower === 'inbox' || lower.includes('收件')) return 'INBOX'
    if (lower === 'junk' || lower === 'spam' || lower.includes('\u5783')) return 'Junk'
    if (lower === 'deleted' || lower === 'deleteditems' || lower.includes('删除')) return 'Deleted'
    if (lower === 'sent' || lower === 'sentitems' || lower.includes('已发送')) return 'Sent'
    if (lower === 'drafts' || lower.includes('草稿')) return 'Drafts'
    if (lower === 'archive' || lower.includes('归档')) return 'Archive'
    return text
}

const resolvePostMailbox = (post: Post) => {
    return normalizeMailboxName(post.mailbox || post.mailboxLabel || 'INBOX') || 'INBOX'
}

const buildIgnoredVerificationCodes = (terms: string[] = []) => {
    const ignored = new Set<string>()
    const add = (value = '') => {
        const compact = String(value || '').replace(/[^A-Za-z0-9]/g, '')
        if (/^[A-Za-z0-9]{4,8}$/.test(compact) && /[A-Za-z]/.test(compact)) {
            ignored.add(compact.toUpperCase())
        }
    }

    for (const term of terms) {
        const value = String(term || '').trim()
        if (!value) continue
        add(value)
        value.split(/[^A-Za-z0-9]+/).forEach(add)
    }
    return ignored
}

const extractSenderLabel = (send = '') => {
    const text = String(send || '').trim()
    const angleIndex = text.indexOf('<')
    return (angleIndex >= 0 ? text.slice(0, angleIndex) : text)
        .replace(/^"+|"+$/g, '')
        .trim()
}

const extractSenderAddress = (send = '') => {
    const text = String(send || '').trim()
    const angleMatch = text.match(/<([^>]+)>/)
    const emailMatch = text.match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i)
    return (angleMatch?.[1] || emailMatch?.[0] || '').trim().toLowerCase()
}

const buildPostIgnoredVerificationTerms = (post: Partial<Post>) => {
    const send = post.send || ''
    const label = extractSenderLabel(send)
    const address = extractSenderAddress(send)
    const domain = address.split('@')[1] || ''
    const domainParts = domain.split('.').filter(Boolean)
    const rootDomain = domainParts.length >= 2 ? domainParts.slice(-2).join('.') : domain
    return [label, address, domain, rootDomain, rootDomain.split('.')[0] || ''].filter(Boolean)
}

const decodeHtmlEntities = (value = '') => {
    const textarea = document.createElement('textarea')
    textarea.innerHTML = value
    return textarea.value
}

const normalizeLink = (value = '') => {
    const decoded = decodeHtmlEntities(String(value || ''))
        .trim()
        .replace(/[\])}>.,;'"，。；、]+$/g, '')
    if (!decoded) return ''
    try {
        const url = new URL(decoded)
        if (!['http:', 'https:'].includes(url.protocol)) return ''
        return url.toString()
    } catch {
        return ''
    }
}

const getLinkHost = (value = '') => {
    try {
        return new URL(value).host
    } catch {
        return ''
    }
}

const cleanLinkLabel = (value = '') => {
    return stripHtml(decodeHtmlEntities(value))
        .replace(/\s+/g, ' ')
        .trim()
}

const getPostLinkDetails = (post: Partial<Post>) => {
    const links: PostLinkDetail[] = []
    const seen = new Set<string>()
    const add = (value = '', label = '', source = 'text', score = 0) => {
        const normalized = normalizeLink(value)
        if (!normalized || seen.has(normalized)) return
        const host = getLinkHost(normalized)
        if (['fonts.googleapis.com', 'fonts.gstatic.com'].includes(host)) return
        seen.add(normalized)
        const cleanedLabel = cleanLinkLabel(label)
        links.push({
            url: normalized,
            label: cleanedLabel || host || '链接',
            text: cleanedLabel,
            host,
            source,
            score
        })
    }

    ;(post.linkDetails || []).forEach(link => add(link.url, link.text || link.label || '', link.source || 'api', link.score || 0))
    if (post.primaryLink) add(post.primaryLink, post.primaryLinkText || post.primaryLinkLabel || 'Verify email', 'api-primary', 1000)
    ;(post.verificationLinks || []).forEach(link => add(link, 'Verify email', 'api-action', 900))
    ;(post.links || []).forEach(link => add(link, '', 'api', 0))

    const html = String(post.html || '')
    for (const match of html.matchAll(/<a\b[^>]*href\s*=\s*(['"])(.*?)\1[^>]*>([\s\S]*?)<\/a>/gi)) {
        add(match[2], match[3], 'html', /verify\s+email/i.test(cleanLinkLabel(match[3])) ? 100 : 20)
    }
    for (const match of html.matchAll(/\bhref\s*=\s*(['"])(.*?)\1/gi)) {
        add(match[2], '', 'html', 0)
    }

    const rawText = `${post.subject || ''}\n${post.text || ''}\n${stripHtml(html)}`
    for (const match of rawText.matchAll(/https?:\/\/[^\s<>"']+/gi)) {
        add(match[0], '', 'text', 0)
    }

    return links.sort((a, b) => {
        if (/^verify\s+email$/i.test(a.label || '')) return -1
        if (/^verify\s+email$/i.test(b.label || '')) return 1
        return (b.score || 0) - (a.score || 0)
    })
}

const getPostLinks = (post: Partial<Post>) => {
    return getPostLinkDetails(post).map(link => link.url)
}

const getPostPrimaryLink = (post: Partial<Post>) => {
    return getPostLinkDetails(post)[0]?.url || ''
}

const extractVerificationCode = (subject = '', text = '', html = '', ignoredTerms: string[] = []) => {
    const rawContent = [subject, text, html]
        .filter(Boolean)
        .join('\n')

    if (!rawContent) {
        return { code: '', source: '' }
    }

    const normalized = rawContent
        .replace(/<br\s*\/?>/gi, '\n')
        .replace(/<\/(p|div|tr|li|h\d)>/gi, '\n')
        .replace(/<[^>]*>/g, ' ')
        .replace(/https?:\/\/\S+/gi, ' ')
        .replace(/\r/g, '')
        .replace(/[ \t]+/g, ' ')
        .replace(/\n+/g, '\n')
        .trim()

    const keywords = ['验证码', '校验码', '验证代码', '代码', '临时验证码', '临时代码', 'verification code', 'verification codes', 'security code', 'security codes', 'one-time code', 'temporary code', 'otp', 'code']
    const candidates: Array<{ code: string, score: number, source: string }> = []
    const ignoredCodes = buildIgnoredVerificationCodes(ignoredTerms)

    const normalizeCandidate = (code: string) => /^\d+$/.test(code) ? code : code.toUpperCase()
    const isLikelyCode = (code: string, context: { hasKeyword?: boolean, lineIsCode?: boolean } = {}) => {
        const normalizedCode = normalizeCandidate(code)
        if (/^(\d)\1{3,7}$/.test(normalizedCode)) return false
        if (/^\d{1,2}(?:AM|PM)$/.test(normalizedCode)) return false
        if (/^(?:DAY|WEEK|MONTH|YEAR)\d{1,2}$/.test(normalizedCode)) return false
        if (/^\d{1,3}(?:K|OFF)$/.test(normalizedCode)) return false
        if (/^(?:19|20)\d{2}$/.test(normalizedCode) && !context.hasKeyword) return false
        if (/^[0-9]{4,8}$/.test(normalizedCode)) return true
        if (!/^[A-Za-z0-9]{4,8}$/.test(normalizedCode) || !/[A-Za-z]/.test(normalizedCode) || !/\d/.test(normalizedCode)) return false
        if (context.hasKeyword) return true
        const digitCount = (normalizedCode.match(/\d/g) || []).length
        return Boolean(context.lineIsCode && (digitCount >= 2 || normalizedCode.length >= 5))
    }

    const addCandidate = (code: string, score: number, source: string, context: { hasKeyword?: boolean, lineIsCode?: boolean } = {}) => {
        if (!code) return
        const normalizedCode = normalizeCandidate(code)
        if (ignoredCodes.has(normalizedCode) || !isLikelyCode(code, context)) return
        candidates.push({ code: normalizedCode, score, source })
    }

    const subjectNormalized = (subject || '').trim()
    const subjectHasKeyword = keywords.some(keyword => subjectNormalized.toLowerCase().includes(keyword.toLowerCase()))

    for (const match of subjectNormalized.matchAll(/\b([A-Za-z0-9]{4,8})\b/g)) {
        const code = (match[1] || '').trim()
        if (!code) continue
        addCandidate(code, 360 + (subjectHasKeyword ? 120 : 0) - code.length, '主题命中', { hasKeyword: subjectHasKeyword })
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
            const source = line === code
                ? (/^\d+$/.test(code) ? '正文独立数字行命中' : '正文独立字母数字行命中')
                : (/^\d+$/.test(code) ? '正文数字命中' : '正文混合码命中')
            addCandidate(code, (line === code ? 260 : 180) + (hasKeywordNearby ? 180 : 0) - code.length, source, {
                hasKeyword: hasKeywordNearby,
                lineIsCode: line === code,
            })
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
            addCandidate(code, /^\d+$/.test(code) ? 320 - code.length : 340 - code.length, '关键词近邻命中', { hasKeyword: true })
        }
    }

    for (const match of normalized.matchAll(/\b([A-Za-z0-9]{4,8})\b/g)) {
        const code = (match[1] || '').trim()
        if (!code) continue
        const index = match.index || 0
        const context = normalized.slice(Math.max(0, index - 60), Math.min(normalized.length, index + code.length + 60)).toLowerCase()
        const hasKeywordNearby = keywords.some(keyword => context.includes(keyword.toLowerCase()))
        const mixedBonus = /^[0-9]{4,8}$/.test(code) ? 120 : 100
        const contextBonus = hasKeywordNearby ? 170 : 0
        addCandidate(code, mixedBonus + contextBonus - code.length, hasKeywordNearby ? '上下文命中' : '兜底命中', { hasKeyword: hasKeywordNearby })
    }

    candidates.sort((a, b) => b.score - a.score)
    return {
        code: candidates[0]?.code || '',
        source: candidates[0]?.source || ''
    }
}

const resolvePostBodyMode = (post: Post): PostBodyMode => {
    if (post.bodyMode === 'full' || post.bodyMode === 'preview' || post.bodyMode === 'none') {
        return post.bodyMode
    }
    if ((post.html || '').trim()) return 'full'
    if ((post.text || '').trim()) return 'preview'
    return 'none'
}

const mergePostBodyMode = (existingPost: Post, incomingPost: Post): PostBodyMode => {
    const modes = [resolvePostBodyMode(existingPost), resolvePostBodyMode(incomingPost)]
    if (modes.includes('full')) return 'full'
    if (modes.includes('preview')) return 'preview'
    return 'none'
}

const normalizePosts = (posts: Post[], mailbox = 'INBOX', bodyMode: PostBodyMode = 'preview') => {
    return sortPostsByDate(posts.map(post => {
        const verification = extractVerificationCode(post.subject || '', post.text || '', post.html || '', buildPostIgnoredVerificationTerms(post))
        return {
            ...post,
            mailbox,
            mailboxLabel: mailbox === 'Junk' ? '垃圾箱' : '收件箱',
            bodyMode: post.bodyMode || bodyMode,
            verificationCode: verification.code,
            verificationSource: verification.source,
            links: getPostLinks(post),
            linkDetails: getPostLinkDetails(post),
            primaryLink: getPostPrimaryLink(post)
        }
    }))
}

const getPostVerification = (post: Post) => {
    return extractVerificationCode(post.subject || '', post.text || '', post.html || '', buildPostIgnoredVerificationTerms(post))
}

const mergePosts = (...lists: Post[][]) => {
    return sortPostsByDate(lists.flat())
}

const mergePostRecord = (existingPost: Post | undefined, incomingPost: Post) => {
    if (!existingPost) {
        return incomingPost
    }

    return {
        ...existingPost,
        ...incomingPost,
        id: incomingPost.id || existingPost.id,
        uid: incomingPost.uid || existingPost.uid,
        seqno: incomingPost.seqno || existingPost.seqno,
        text: incomingPost.text || existingPost.text || '',
        html: incomingPost.html || existingPost.html || '',
        bodyMode: mergePostBodyMode(existingPost, incomingPost),
        verificationCode: incomingPost.verificationCode || '',
        verificationSource: incomingPost.verificationSource || '',
        links: getPostLinks(incomingPost).length ? getPostLinks(incomingPost) : getPostLinks(existingPost),
        linkDetails: getPostLinkDetails(incomingPost).length ? getPostLinkDetails(incomingPost) : getPostLinkDetails(existingPost),
        primaryLink: getPostPrimaryLink(incomingPost) || getPostPrimaryLink(existingPost),
        primaryLinkText: incomingPost.primaryLinkText || existingPost.primaryLinkText,
        primaryLinkLabel: incomingPost.primaryLinkLabel || existingPost.primaryLinkLabel,
        mailbox: incomingPost.mailbox || existingPost.mailbox,
        mailboxLabel: incomingPost.mailboxLabel || existingPost.mailboxLabel
    }
}

const mergeUniquePosts = (existingPosts: Post[], incomingPosts: Post[]) => {
    const mergedPosts: Post[] = []
    for (const post of [...existingPosts, ...incomingPosts]) {
        const currentIndex = mergedPosts.findIndex(currentPost => isLikelySamePost(currentPost, post) || postIdentityKey(currentPost) === postIdentityKey(post))
        if (currentIndex >= 0) {
            mergedPosts[currentIndex] = mergePostRecord(mergedPosts[currentIndex], post)
        } else {
            mergedPosts.push(post)
        }
    }
    return sortPostsByDate(mergedPosts)
}

const getMailboxCacheKey = (email: string, mailbox: string) => `${email}${mailbox}`

const isMailboxCacheKey = (key: string) => {
    return key.includes('@') && MAILBOX_CACHE_FOLDERS.some(mailbox => key.endsWith(mailbox))
}

const pruneMailboxCaches = (keepKey = '') => {
    for (let index = localStorage.length - 1; index >= 0; index -= 1) {
        const key = localStorage.key(index)
        if (key && key !== keepKey && isMailboxCacheKey(key)) {
            localStorage.removeItem(key)
        }
    }
}

const slimPostForCache = (post: Post): Post => ({
    id: post.id,
    uid: post.uid,
    seqno: post.seqno,
    send: post.send,
    subject: post.subject,
    text: '',
    html: '',
    date: post.date,
    verificationCode: post.verificationCode,
    verificationSource: post.verificationSource,
    links: (post.links || []).slice(0, 10),
    linkDetails: (post.linkDetails || []).slice(0, 10),
    verificationLinks: (post.verificationLinks || []).slice(0, 10),
    primaryLink: post.primaryLink,
    primaryLinkText: post.primaryLinkText,
    primaryLinkLabel: post.primaryLinkLabel,
    mailbox: post.mailbox,
    mailboxLabel: post.mailboxLabel,
    bodyMode: resolvePostBodyMode(post) === 'none' ? 'none' : 'preview'
})

const prepareMailboxCachePosts = (posts: Post[]) => {
    return sortPostsByDate(posts).slice(0, MAILBOX_CACHE_LIMIT).map(slimPostForCache)
}

const safeSetMailboxPosts = (email: string, mailbox: string, posts: Post[]) => {
    const cacheKey = getMailboxCacheKey(email, mailbox)
    const cachePosts = prepareMailboxCachePosts(posts)
    const payload = JSON.stringify(cachePosts)

    try {
        localStorage.setItem(cacheKey, payload)
        return
    } catch (error) {
        localStorage.removeItem(cacheKey)
    }

    try {
        localStorage.setItem(cacheKey, payload)
        return
    } catch (error) {
        pruneMailboxCaches(cacheKey)
    }

    try {
        localStorage.setItem(cacheKey, payload)
    } catch (error) {
        console.warn('邮箱缓存写入失败，已跳过本地缓存', error)
    }
}

const getCachedMailboxPosts = (email: string, mailbox: string) => {
    const cacheKey = getMailboxCacheKey(email, mailbox)

    try {
        return mergeUniquePosts([], normalizePosts(JSON.parse(localStorage.getItem(cacheKey) || '[]'), mailbox, 'preview'))
    } catch (error) {
        localStorage.removeItem(cacheKey)
        return []
    }
}

const getLatestPost = (email: string) => {
    return latestMailMap.value[email] || mergePosts(getCachedMailboxPosts(email, 'INBOX'), getCachedMailboxPosts(email, 'Junk'))[0] || null
}

const setLatestPost = (email: string, post: Post | null) => {
    latestMailMap.value = {
        ...latestMailMap.value,
        [email]: post
    }
}

const getLatestPostWithCode = (email: string) => {
    const latestMemoryPost = latestMailMap.value[email]
    if (latestMemoryPost && getPostVerification(latestMemoryPost).code) {
        return latestMemoryPost
    }
    const posts = mergePosts(getCachedMailboxPosts(email, 'INBOX'), getCachedMailboxPosts(email, 'Junk'))
    return posts.find(post => getPostVerification(post).code) || null
}

const loadCombinedPosts = (email: string) => {
    postList.value = mergePosts(getCachedMailboxPosts(email, 'INBOX'), getCachedMailboxPosts(email, 'Junk'))
}

const handleCollect = async (row: Email) => {
    if (rowCollectLoadingMap.value[row.email] || postLoading.value) {
        return
    }

    postTitle.value = row.email + "的收件"
    setRowCollectLoading(row.email, true)
    try {
        await handlePost(row)
    } finally {
        setRowCollectLoading(row.email, false)
    }
}

const handlePost = async (row: Email) => {
    nowPost.value = row
    loadCombinedPosts(row.email)
    dialogEmailVisible.value = true
    await handleReceive(row)
}

const handleReceive = async (target: Email = nowPost.value) => {
    if (postLoading.value) {
        return
    }

    const { email, password, client_id, refresh_token } = target
    if (!email) {
        return
    }

    await refreshMailboxPair(email, password, client_id, refresh_token, true)
    loadCombinedPosts(email)
    mailList.value = mailList.value.map(item => item.email === email ? { ...item } : item)
}

const getRequestPassword = () => {
    const apiPassword = toolForm.value.apiPassword.trim()
    if (apiPassword) {
        return apiPassword
    }

    return ''
}

const getPosts = async (email: string, password: string, client_id: string, refresh_token: string, mailbox: string, silent = false, latestOnly = false, top = ALL_MAIL_TOP, contentMode: 'none' | 'preview' | 'full' = 'preview'): Promise<FetchPostsResult> => {
    const requestPassword = getRequestPassword()
    if (!requestPassword) {
        if (!silent) {
            ElMessage.warning('请先填写顶部的接口密码')
        }
        return {
            ok: false,
            posts: getCachedMailboxPosts(email, mailbox),
            freshPosts: [],
            errorMessage: 'missing_api_password'
        }
    }

    let errorMessage = ''

    try {
        const response = await fetch(latestOnly ? '/api/mail_new' : '/api/mail_all', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                email,
                password: requestPassword,
                client_id,
                refresh_token,
                mailbox,
                top,
                contentMode,
                manualFetch: true
            })
        })
        const data = await response.json()

        if (data.code == 200) {
            const normalizedPosts = normalizePosts(data.data || [], mailbox, contentMode)
            const existingPosts = getCachedMailboxPosts(email, mailbox)
            const finalPosts = mergeUniquePosts(existingPosts, normalizedPosts)
            safeSetMailboxPosts(email, mailbox, finalPosts)
            return {
                ok: true,
                posts: finalPosts,
                freshPosts: normalizedPosts
            }
        }

        errorMessage = data.message || data.error || (response.status === 401 ? '接口密码错误' : '收件失败')
        if (!silent) {
            ElMessage.error(errorMessage)
        }
    } catch (error) {
        errorMessage = error instanceof Error ? error.message : '收取失败'
        if (!silent) {
            ElMessage.error('收取失败')
        }
    }

    return {
        ok: false,
        posts: getCachedMailboxPosts(email, mailbox),
        freshPosts: [],
        errorMessage
    }
}

const getPostFullBody = async (account: Email, post: Post, silent = false) => {
    const mailbox = resolvePostMailbox(post)
    const requestPassword = getRequestPassword()
    if (!requestPassword) {
        if (!silent) {
            ElMessage.warning('请先填写顶部的接口密码')
        }
        return null
    }

    try {
        const response = await fetch('/api/mail_one', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                email: account.email,
                password: requestPassword,
                client_id: account.client_id,
                refresh_token: account.refresh_token,
                mailbox,
                id: post.id || '',
                messageId: post.id || '',
                uid: post.uid || '',
                seqno: post.seqno || '',
                subject: post.subject || '',
                send: post.send || '',
                date: post.date || '',
                timezoneOffset: '',
                fallbackTop: 200,
                contentMode: 'full',
                manualFetch: true
            })
        })
        const data = await response.json()

        if (data.code == 200) {
            const normalizedPosts = normalizePosts(data.data || [], mailbox, 'full')
            const fetchedPost = normalizedPosts[0] || null
            if (!fetchedPost) {
                return null
            }

            const cachedMailboxPosts = getCachedMailboxPosts(account.email, mailbox)
            const mergedMailboxPosts = mergeUniquePosts(cachedMailboxPosts, [fetchedPost])
            safeSetMailboxPosts(account.email, mailbox, mergedMailboxPosts)
            return fetchedPost
        }

        if (!silent) {
            ElMessage.error(data.message || data.error || '邮件正文获取失败')
        }
    } catch (error) {
        if (!silent) {
            ElMessage.error('邮件正文获取失败')
        }
    }

    return null
}

const refreshMailboxPair = async (email: string, password: string, client_id: string, refresh_token: string, showMessage = false) => {
    if (showMessage) {
        postLoading.value = true
    }

    try {
        const [inboxResult, junkResult] = await Promise.all([
            getPosts(email, password, client_id, refresh_token, 'INBOX', true, false, MAILBOX_REFRESH_TOP, 'preview'),
            getPosts(email, password, client_id, refresh_token, 'Junk', true, false, MAILBOX_REFRESH_TOP, 'preview')
        ])

        const combinedPosts = mergePosts(inboxResult.posts, junkResult.posts)
        latestMailMap.value[email] = combinedPosts[0] || null

        if (nowPost.value.email === email) {
            postList.value = combinedPosts
        }

        if (showMessage) {
            if (inboxResult.ok || junkResult.ok) {
                ElMessage.success('收件成功')
            } else {
                const errorText = getFetchErrorText(inboxResult, junkResult)
                ElMessage.error(errorText ? `收件失败：${errorText}` : '收件失败')
            }
        }

        return combinedPosts
    } finally {
        if (showMessage) {
            postLoading.value = false
        }
    }
}

const dialogPostVisible = ref(false)
const dialogEmailContent = ref('')
const dialogPostLoading = ref(false)

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
    } catch (error) {
        ElMessage.error('复制失败')
    }
}

const formatDateTime = (value: string) => {
    if (!value) {
        return '-'
    }
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

const formatLatestMail = (email: string) => {
    const latestPost = getLatestPost(email)
    return {
        label: latestPost?.mailboxLabel || '-',
        time: latestPost?.date ? formatDateTime(latestPost.date) : '-'
    }
}

const getLatestVerificationCode = (email: string) => {
    const latestPost = getLatestPost(email)
    if (latestPost && getPostVerification(latestPost).code) {
        return getPostVerification(latestPost).code
    }
    const postWithCode = getLatestPostWithCode(email)
    return postWithCode ? getPostVerification(postWithCode).code : ''
}

const getFetchErrorText = (...results: FetchPostsResult[]) => {
    return results
        .filter(result => !result.ok && result.errorMessage)
        .map(result => result.errorMessage)
        .filter(Boolean)
        .join('；')
}

const handleFetchLatest = async (row: Email, silentMessage = false) => {
    if (rowRefreshLoadingMap.value[row.email]) {
        return [] as Post[]
    }

    setRowRefreshLoading(row.email, true)

    try {
        let usedRetry = false
        let [inboxResult, junkResult] = await Promise.all([
            getPosts(row.email, row.password, row.client_id, row.refresh_token, 'INBOX', true, true, LATEST_MAIL_TOP, 'preview'),
            getPosts(row.email, row.password, row.client_id, row.refresh_token, 'Junk', true, true, LATEST_MAIL_TOP, 'preview')
        ])

        let freshCandidates = mergePosts(inboxResult.freshPosts, junkResult.freshPosts)

        if (freshCandidates.length === 0 || !inboxResult.ok || !junkResult.ok) {
            usedRetry = true
            const [retryInboxResult, retryJunkResult] = await Promise.all([
                getPosts(row.email, row.password, row.client_id, row.refresh_token, 'INBOX', true, false, LATEST_RETRY_TOP, 'preview'),
                getPosts(row.email, row.password, row.client_id, row.refresh_token, 'Junk', true, false, LATEST_RETRY_TOP, 'preview')
            ])

            const retryFreshCandidates = mergePosts(retryInboxResult.freshPosts, retryJunkResult.freshPosts)
            if (retryFreshCandidates.length > 0) {
                freshCandidates = retryFreshCandidates
            }

            inboxResult = retryInboxResult.ok || retryInboxResult.freshPosts.length > 0 ? retryInboxResult : inboxResult
            junkResult = retryJunkResult.ok || retryJunkResult.freshPosts.length > 0 ? retryJunkResult : junkResult
        }

        const cachedCandidates = mergePosts(inboxResult.posts, junkResult.posts)
        const latestCandidates = freshCandidates.length > 0 ? freshCandidates : cachedCandidates
        const latestPost = latestCandidates[0] || null

        if (!latestPost) {
            if (!silentMessage) {
                const errorText = getFetchErrorText(inboxResult, junkResult)
                ElMessage.warning(errorText ? `没有获取到最新邮件：${errorText}` : '没有获取到该邮箱的最新邮件')
            }
            return [] as Post[]
        }

        setLatestPost(row.email, latestPost)

        if (latestPost.mailbox) {
            const cachedMailboxPosts = getCachedMailboxPosts(row.email, latestPost.mailbox)
            const mergedMailboxPosts = mergeUniquePosts(cachedMailboxPosts, [latestPost])
            safeSetMailboxPosts(row.email, latestPost.mailbox, mergedMailboxPosts)
        }

        if (!silentMessage) {
            nowPost.value = row
            postTitle.value = `${row.email} 的邮件`
            loadCombinedPosts(row.email)
            dialogEmailVisible.value = true
        } else if (nowPost.value.email === row.email) {
            loadCombinedPosts(row.email)
        }

        if (!silentMessage) {
            if (freshCandidates.length > 0) {
                ElMessage.success(usedRetry ? '获取成功，已自动深度重试' : '获取成功')
            } else {
                ElMessage.warning('本次没有拉到新数据，已显示本地缓存')
            }
        }

        return [latestPost]
    } finally {
        setRowRefreshLoading(row.email, false)
    }
}

const refreshVisibleLatestRows = async (rows: Email[]) => {
    const visibleRows = rows.filter(row => row.email)
    if (visibleRows.length === 0) return

    ElMessage.info(`正在刷新本页 ${visibleRows.length} 个邮箱的最新邮件`)
    let refreshed = 0
    let failed = 0
    let cursor = 0

    const worker = async () => {
        while (cursor < visibleRows.length) {
            const row = visibleRows[cursor]
            cursor += 1
            if (!row) continue
            const posts = await handleFetchLatest(row, true)
            if (posts.length > 0) {
                refreshed += 1
            } else {
                failed += 1
            }
        }
    }

    await Promise.all(Array.from(
        { length: Math.min(VISIBLE_REFRESH_CONCURRENCY, visibleRows.length) },
        () => worker()
    ))

    if (failed > 0) {
        ElMessage.warning(`本页最新邮件刷新完成：成功 ${refreshed} 个，失败 ${failed} 个`)
    } else {
        ElMessage.success(`本页最新邮件已刷新：${refreshed} 个`)
    }
}

const copyLatestVerificationCode = async (row: Email) => {
    const latestCode = getLatestVerificationCode(row.email)

    if (!latestCode) {
        ElMessage.warning('请先点击“获取”更新最新邮件与验证码')
        return
    }

    await copyText(latestCode, '最新验证码已复制')
}

const escapeHtml = (value = '') => value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')

const postIdentityKey = (post: Post) => [
    post.id || '',
    post.date || '',
    post.subject || '',
    post.send || '',
    resolvePostMailbox(post)
].join('__')

const normalizeComparableText = (value = '') => String(value || '').trim().toLowerCase()

const getPostDateLabels = (post: Post) => {
    const rawDate = String(post.date || '').trim()
    const labels = new Set<string>()
    if (rawDate) {
        labels.add(rawDate)
        const date = new Date(rawDate)
        if (!Number.isNaN(date.getTime())) {
            labels.add(date.toLocaleString('zh-CN', { hour12: false }))
        }
    }
    return labels
}

const getPostTime = (post: Post) => {
    const time = new Date(post.date || '').getTime()
    return Number.isNaN(time) ? 0 : time
}

const hasSameDateLabel = (post: Post, target: Post) => {
    const labels = getPostDateLabels(post)
    for (const label of getPostDateLabels(target)) {
        if (labels.has(label)) {
            return true
        }
    }
    return false
}

const isLikelySamePost = (post: Post, target: Post) => {
    const sameSubject = normalizeComparableText(post.subject) === normalizeComparableText(target.subject)
    const sameSender = normalizeComparableText(post.send) === normalizeComparableText(target.send)
    const sameMailbox = resolvePostMailbox(post) === resolvePostMailbox(target)
    const postTime = getPostTime(post)
    const targetTime = getPostTime(target)
    const nearTime = postTime && targetTime ? Math.abs(postTime - targetTime) <= 5 * 60 * 1000 : true
    const sameDateLabel = hasSameDateLabel(post, target)
    if (post.id && target.id && post.id === target.id && sameMailbox) return true
    if (post.uid && target.uid && String(post.uid) === String(target.uid) && sameMailbox) return true
    if (post.seqno && target.seqno && String(post.seqno) === String(target.seqno) && sameMailbox) return true
    return sameSubject && sameSender && sameMailbox && (nearTime || sameDateLabel)
}

const stripHtml = (html = '') => html
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|tr|li|h\d)>/gi, '\n')
    .replace(/<[^>]*>/g, ' ')

const getPostPreview = (post: Post) => {
    const raw = (post.text || stripHtml(post.html || ''))
        .replace(/\r/g, '')
        .replace(/[ \t]+/g, ' ')
        .replace(/\n+/g, ' ')
        .trim()
    return raw ? raw.slice(0, 180) : '-'
}

const findPostInList = (posts: Post[], target: Post) => {
    if (target.id) {
        const byId = posts.find(post => post.id === target.id)
        if (byId) return byId
    }
    if (target.uid) {
        const byUid = posts.find(post => String(post.uid || '') === String(target.uid || '') && resolvePostMailbox(post) === resolvePostMailbox(target))
        if (byUid) return byUid
    }
    if (target.seqno) {
        const bySeqno = posts.find(post => String(post.seqno || '') === String(target.seqno || '') && resolvePostMailbox(post) === resolvePostMailbox(target))
        if (bySeqno) return bySeqno
    }
    const targetKey = postIdentityKey(target)
    return posts.find(post => postIdentityKey(post) === targetKey)
        || posts.find(post => isLikelySamePost(post, target))
        || null
}

const hasPostBody = (post: Post) => Boolean((post.html || '').trim() || (post.text || '').trim())
const hasFullPostBody = (post: Post) => resolvePostBodyMode(post) === 'full' && hasPostBody(post)

const renderPostContent = (post: Post, bodyState: 'none' | 'loading' | 'missing' = 'none') => {
    const verification = getPostVerification(post)
    const links = getPostLinkDetails(post)
    const linksHtml = links.length
        ? `<div class="links-block">
            <div class="links-title">邮件链接</div>
            ${links.map((link, index) => `
              <div class="link-row">
                <span class="link-index">${index + 1}</span>
                <span class="link-label">${escapeHtml(link.label || `链接 ${index + 1}`)}</span>
                <a href="${escapeHtml(link.url)}" target="_blank" rel="noreferrer noopener">${escapeHtml(link.url)}</a>
              </div>
            `).join('')}
          </div>`
        : ''
    const body = post.html
        ? `<div class="mail-body">${post.html}</div>`
        : post.text
            ? `<pre class="mail-text">${escapeHtml(post.text)}</pre>`
            : bodyState === 'loading'
                ? `<div class="empty-body">正在补拉完整正文，请稍等...</div>`
                : bodyState === 'missing'
                    ? `<div class="empty-body">接口暂未返回这封邮件的正文，已展示可用的邮件信息。请点击收件窗口里的“自动收件”后再查看，或稍后重试。</div>`
                    : ''

    return `<!doctype html>
<html>
<head>
<meta charset="utf-8" />
<style>
body{margin:0;padding:18px 20px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",Arial,sans-serif;color:#202124;background:#fff;line-height:1.55}
.meta{border-bottom:1px solid #e6eaf0;margin-bottom:16px;padding-bottom:12px}
.subject{font-size:18px;font-weight:700;margin-bottom:10px}
.row{display:flex;gap:8px;margin:4px 0;font-size:13px;color:#4b5563}
.label{flex:0 0 64px;color:#8a9099}
.value{min-width:0;word-break:break-word}
.code{font-weight:700;color:#1f6feb}
.links-block{border:1px solid #d9e4f5;border-radius:8px;background:#f8fbff;margin:0 0 16px;padding:12px}
.links-title{font-weight:700;margin-bottom:8px;color:#374151}
.link-row{display:flex;gap:8px;align-items:flex-start;margin:6px 0;font-size:13px}
.link-index{flex:0 0 22px;height:22px;line-height:22px;border-radius:11px;background:#e8f1ff;color:#1f6feb;text-align:center;font-weight:700}
.link-label{flex:0 0 130px;color:#111827;font-weight:700;word-break:break-word}
.link-row a{color:#1f6feb;word-break:break-all}
.mail-body{max-width:100%;overflow:auto}
.mail-body a{word-break:break-all}
.mail-body img{max-width:100%;height:auto}
.mail-text{white-space:pre-wrap;font:inherit;margin:0}
.empty-body{border:1px dashed #cfd7e6;border-radius:8px;padding:14px;color:#6b7280;background:#f8fafc}
</style>
</head>
<body>
<div class="meta">
  <div class="subject">${escapeHtml(post.subject || '无主题')}</div>
  <div class="row"><span class="label">发件人</span><span class="value">${escapeHtml(post.send || '-')}</span></div>
  <div class="row"><span class="label">位置</span><span class="value">${escapeHtml(post.mailboxLabel || post.mailbox || '-')}</span></div>
  <div class="row"><span class="label">日期</span><span class="value">${escapeHtml(formatDateTime(post.date || ''))}</span></div>
  <div class="row"><span class="label">验证码</span><span class="value code">${escapeHtml(verification.code || '-')}</span></div>
</div>
${linksHtml}
${body}
</body>
</html>`
}

const setDialogPostContent = (post: Post, bodyState: 'none' | 'loading' | 'missing' = 'none') => {
    dialogPostLinks.value = getPostLinkDetails(post)
    dialogEmailContent.value = renderPostContent(post, bodyState)
}

const viewPost = async (post: Post) => {
    dialogPostVisible.value = true

    if (!nowPost.value.email) {
        setDialogPostContent(post, hasPostBody(post) ? 'none' : 'missing')
        return
    }

    if (hasFullPostBody(post)) {
        setDialogPostContent(post)
        return
    }

    dialogPostLoading.value = true
    setDialogPostContent(post, 'loading')
    try {
        const mailbox = resolvePostMailbox(post)
        const onePost = await getPostFullBody(nowPost.value, post, true)
        if (onePost && hasPostBody(onePost)) {
            loadCombinedPosts(nowPost.value.email)
            postList.value = mergeUniquePosts(postList.value, [onePost])
            setDialogPostContent(onePost)
            return
        }

        const result = await getPosts(
            nowPost.value.email,
            nowPost.value.password,
            nowPost.value.client_id,
            nowPost.value.refresh_token,
            mailbox,
            true,
            false,
            200,
            'full'
        )
        loadCombinedPosts(nowPost.value.email)
        const hydratedPost = findPostInList(result.posts, post) || findPostInList(postList.value, post)
        if (hydratedPost) {
            postList.value = mergeUniquePosts(postList.value, [hydratedPost])
        }
        setDialogPostContent(hydratedPost || post, hasPostBody(hydratedPost || post) ? 'none' : 'missing')
    } catch (error) {
        setDialogPostContent(post, 'missing')
    } finally {
        dialogPostLoading.value = false
    }
}

// 清空邮箱
const handleClear = () => {
    ElMessageBox.confirm('确认清空邮箱 ' + nowPost.value.email + ' 的所有邮件吗？', '清空确认', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
    }).then(() => {
        postList.value = []
        // 清空两个邮箱文件夹的本地缓存
        localStorage.setItem(nowPost.value.email + 'INBOX', JSON.stringify([]))
        localStorage.setItem(nowPost.value.email + 'Junk', JSON.stringify([]))
        // 分别清空服务器上的两个文件夹
        clearPosts(nowPost.value.email, nowPost.value.password, nowPost.value.client_id, nowPost.value.refresh_token, 'INBOX')
        clearPosts(nowPost.value.email, nowPost.value.password, nowPost.value.client_id, nowPost.value.refresh_token, 'Junk')
        ElMessage.success('邮箱正在清空中... 请稍后查看')
    }).catch(() => {
        ElMessage.info('已取消清空')
    })
}

const clearPosts = (email: string, password: string, client_id: string, refresh_token: string, mailbox: string) => {
    const requestPassword = getRequestPassword()
    if (!requestPassword) {
        ElMessage.warning('请先填写顶部的接口密码')
        return
    }

    fetch('/api/process-mailbox', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            email,
            password: requestPassword,
            client_id,
            refresh_token,
            mailbox
        })
    })
}
</script>

<style scoped>
.mail-toolbar {
    padding: 8px;
    border: 1px solid #dfe6f2;
    border-radius: 8px;
    background: #fff;
    box-shadow: 0 3px 12px rgba(20, 35, 66, 0.04);
}

.toolbar-grid {
    display: flex;
    flex-wrap: wrap;
    align-items: stretch;
    gap: 6px;
}

.toolbar-section {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
    min-height: 38px;
    padding: 5px 8px;
    border: 1px solid #e8edf5;
    border-radius: 6px;
    background: #fbfdff;
}

.toolbar-section-config {
    flex: 1 1 620px;
}

.toolbar-section-filter {
    flex: 2 1 760px;
    min-width: 560px;
}

.toolbar-section-import {
    flex: 1 1 300px;
}

.toolbar-section-batch {
    flex: 1 1 390px;
}

.toolbar-section-title {
    flex: 0 0 auto;
    color: #606266;
    font-size: 13px;
    font-weight: 700;
    white-space: nowrap;
}

.toolbar-fields,
.toolbar-button-row {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
}

.toolbar-button-row {
    flex-wrap: wrap;
}

.toolbar-form-item {
    min-width: 0;
    margin-bottom: 0;
}

.toolbar-form-item :deep(.el-form-item__label) {
    color: #606266;
    font-weight: 600;
}

.split-input {
    width: 96px;
}

.password-item {
    flex: 1 1 220px;
}

.email-search-item {
    flex: 0 1 240px;
    min-width: 210px;
}

.project-usage-item {
    flex: 0 0 180px;
}

.sender-filter-item {
    flex: 1 1 280px;
    min-width: 260px;
}

.email-search-item :deep(.el-input),
.project-usage-item :deep(.el-select),
.sender-filter-item :deep(.el-select) {
    width: 100%;
}

.email-search-item :deep(.el-form-item__content),
.project-usage-item :deep(.el-form-item__content),
.sender-filter-item :deep(.el-form-item__content) {
    min-width: 0;
}

.toolbar-section-filter .toolbar-fields {
    flex: 1 1 auto;
    flex-wrap: wrap;
}

.toolbar-section-filter .toolbar-secondary-button {
    flex: 0 0 auto;
}

.toolbar-load-button,
.toolbar-secondary-button,
.toolbar-action-button {
    height: 30px;
    min-width: 78px;
    padding-right: 12px;
    padding-left: 12px;
    margin-left: 0;
    font-weight: 600;
}

.toolbar-action-button {
    flex: 0 0 auto;
}

.toolbar-fields :deep(.el-button + .el-button),
.toolbar-button-row :deep(.el-button + .el-button) {
    margin-left: 0;
}

/* 隐藏默认上传列表 */
.custom-upload :deep(.el-upload__tip),
.custom-upload :deep(.el-upload__list) {
    display: none;
}

.custom-upload :deep(.el-upload) {
    width: 100%;
}

/* 自定义上传按钮样式 */
.custom-upload :deep(.el-button) {
    max-width: 170px;
}

.custom-upload :deep(.el-button span) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.upload-button {
    display: flex;
    align-items: center;
    gap: 8px;
    max-width: 250px;
    overflow: hidden;
}

/* 确保文件名显示在按钮内 */
.upload-button span {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

:deep(.el-form--inline .el-form-item) {
    margin-right: 10px;
}

.table {
    margin-top: 10px;
}

.mail-table :deep(.el-table__header th.el-table__cell) {
    padding: 8px 0;
}

.mail-table :deep(.el-table__body td.el-table__cell) {
    padding: 8px 0;
}

.mail-table :deep(.cell) {
    line-height: 18px;
}

.pagination {
    margin-top: 10px;
    display: flex;
    justify-content: center;
}

.copy-textarea-container {
    display: flex;
    justify-content: center;
    margin-top: 20px;
}

.verification-code-cell {
    display: flex;
    align-items: center;
    gap: 6px;
}

.email-cell {
    display: flex;
    align-items: center;
    gap: 6px;
}

.latest-mail-cell {
    display: flex;
    flex-direction: column;
    gap: 2px;
}

.latest-mail-hint {
    color: #909399;
    font-size: 11px;
}

.desktop-row-actions,
.mail-dialog-actions {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
}

.desktop-row-actions :deep(.el-button) {
    min-width: 52px;
    margin-left: 0;
    padding: 5px 10px;
}

.mail-dialog-actions {
    margin-bottom: 12px;
}

:deep(.mail-list-dialog) {
    max-width: calc(100vw - 24px);
}

:deep(.mail-list-dialog .el-dialog__body) {
    padding-top: 8px;
}

:deep(.mail-content-dialog) {
    max-width: calc(100vw - 24px);
}

:deep(.mail-content-dialog .el-dialog__body) {
    padding-top: 8px;
}

.mail-content-frame-wrap {
    min-height: calc(100vh - 180px);
}

.mail-copy-links {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-bottom: 10px;
    padding: 10px;
    border: 1px solid #d9e4f5;
    border-radius: 6px;
    background: #f8fbff;
}

.mail-copy-link-row {
    display: grid;
    grid-template-columns: minmax(110px, 170px) minmax(0, 1fr) auto;
    gap: 10px;
    align-items: center;
}

.mail-copy-link-label {
    font-weight: 700;
    color: #1f2937;
    word-break: break-word;
}

.mail-copy-link-url {
    min-width: 0;
    overflow: hidden;
    color: #4b5563;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.mail-content-frame {
    width: 100%;
    height: calc(100vh - 180px);
    border: none;
    border-radius: 6px;
    background: #fff;
}

.post-dialog-table {
    margin-top: 8px;
}

.post-table :deep(.el-table__header th.el-table__cell),
.post-table :deep(.el-table__body td.el-table__cell) {
    padding: 7px 0;
}

.post-table :deep(.cell) {
    line-height: 18px;
}

.post-wrap-cell,
.post-preview-cell,
.post-date-cell {
    display: block;
    max-width: 100%;
}

.post-wrap-cell {
    white-space: normal;
    word-break: break-word;
}

.post-preview-cell {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.post-date-cell {
    white-space: nowrap;
}

@media (max-width: 900px) {
    .mail-toolbar {
        padding: 8px;
    }

    .toolbar-grid {
        gap: 8px;
    }

    .toolbar-section {
        flex: 1 1 100%;
        flex-direction: column;
        align-items: stretch;
        gap: 6px;
        padding: 8px;
    }

    .toolbar-fields {
        flex-wrap: wrap;
    }

    .toolbar-form-item,
    .password-item,
    .email-search-item,
    .project-usage-item,
    .sender-filter-item {
        flex: 1 1 100%;
        min-width: 0;
        width: 100%;
    }

    .email-search-item :deep(.el-form-item__content),
    .project-usage-item :deep(.el-form-item__content),
    .sender-filter-item :deep(.el-form-item__content) {
        min-width: 0;
    }

    .split-input,
    .toolbar-form-item :deep(.el-input),
    .toolbar-form-item :deep(.el-select),
    .toolbar-load-button,
    .toolbar-secondary-button {
        width: 100%;
    }

    .toolbar-button-row {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 8px;
    }

    .toolbar-button-row .custom-upload,
    .toolbar-action-button {
        width: 100%;
        min-width: 0;
    }

    :deep(.el-form--inline .el-form-item) {
        margin-right: 0;
    }

    .table {
        overflow-x: auto;
        margin-top: 8px;
    }

    .email-cell,
    .verification-code-cell {
        align-items: flex-start;
        flex-direction: column;
    }

    .email-cell .el-button,
    .verification-code-cell .el-button {
        width: 100%;
    }

    .pagination {
        justify-content: flex-start;
    }

    :deep(.desktop-actions-column) {
        display: none;
    }

    :deep(.active-mobile-mail-row > td) {
        background: #eef5ff !important;
    }

    .mail-table :deep(.el-table__row) {
        cursor: pointer;
    }

    .mobile-action-spacer {
        height: 112px;
    }

    .mobile-email-actions {
        position: fixed;
        right: 10px;
        bottom: max(10px, env(safe-area-inset-bottom));
        left: 10px;
        z-index: 35;
        padding: 10px;
        border: 1px solid #dfe6f2;
        border-radius: 14px;
        background: rgba(255, 255, 255, 0.98);
        box-shadow: 0 -8px 30px rgba(20, 35, 66, 0.16);
    }

    .mobile-action-summary {
        display: grid;
        gap: 2px;
        min-width: 0;
        margin-bottom: 8px;
    }

    .mobile-action-email,
    .mobile-action-status {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }

    .mobile-action-email {
        font-weight: 700;
        color: #1f2d3d;
    }

    .mobile-action-status {
        color: #606266;
        font-size: 12px;
    }

    .mobile-action-buttons {
        display: grid;
        grid-template-columns: 1fr 1fr 1fr 48px;
        gap: 6px;
    }

    .mobile-action-buttons :deep(.el-button) {
        width: 100%;
        min-width: 0;
        margin-left: 0;
        padding-right: 8px;
        padding-left: 8px;
    }

    .mobile-more-button {
        padding-right: 0;
        padding-left: 0;
    }

    .mail-dialog-actions {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 10px;
    }

    .mail-dialog-actions .el-button {
        width: 100%;
        margin-left: 0;
    }
}

@media (max-width: 520px) {
    .toolbar-button-row {
        grid-template-columns: 1fr;
    }
}
</style>
