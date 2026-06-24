import * as XLSX from 'xlsx'

export interface MailAccountRecord {
  email: string
  password: string
  client_id: string
  refresh_token: string
}

export interface MailAccountFailedRow {
  rowNumber: number
  email: string
  password: string
  client_id: string
  refresh_token: string
  reason: string
}

export interface MailAccountParseResult {
  records: MailAccountRecord[]
  failedRows: MailAccountFailedRow[]
}

export interface MailImportPreviewItem {
  key: string
  selected: boolean
  kind: 'valid' | 'duplicate' | 'invalid'
  reason?: string
  record: MailAccountRecord
}

const CLIENT_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const EMAIL_RE = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-z]{2,}/i

const cellText = (value: unknown) => String(value ?? '').trim()

const normalizeEmail = (value: string) => value.trim().toLowerCase()

const isRefreshToken = (value = '') => {
  const text = String(value || '').trim()
  if (text.length < 50) return false
  if (/^M\.C[\w\-.!$%&'()*+,/:;=?@~]+$/i.test(text)) return true
  if (/^0\.[A-Za-z0-9][\w\-.!$%&'()*+,/:;=?@~]+$/i.test(text)) return true
  if (text.length >= 80 && /^[\w\-.!$%&'()*+,/:;=?@~]+$/.test(text)) return true
  return false
}

const isLikelyPassword = (value = '') => {
  const text = String(value || '').trim()
  if (!text || text.length < 4 || text.length > 128) return false
  if (EMAIL_RE.test(text) || CLIENT_ID_RE.test(text) || isRefreshToken(text)) return false
  return true
}

export const validateMailAccountRecord = (record: MailAccountRecord): string | null => {
  const email = normalizeEmail(record.email)
  const password = String(record.password || '').trim()
  const clientId = String(record.client_id || '').trim()
  const refreshToken = String(record.refresh_token || '').trim()

  if (!email || !EMAIL_RE.test(email)) {
    return '邮箱无效或缺失'
  }
  if (!password) {
    return '缺少邮箱密码'
  }
  if (!isLikelyPassword(password)) {
    return '邮箱密码识别错误'
  }
  if (!CLIENT_ID_RE.test(clientId)) {
    return 'client_id 无效（需为 UUID 格式）'
  }
  if (!isRefreshToken(refreshToken)) {
    return 'refresh_token 无效或过短'
  }
  if (password === refreshToken || clientId === refreshToken || email === password) {
    return '字段识别冲突，请检查列顺序'
  }
  return null
}

export const recordToImportLine = (record: MailAccountRecord, splitSymbol = '----') => {
  return [
    record.email,
    record.password,
    record.client_id,
    record.refresh_token,
  ].join(splitSymbol)
}

const recordFromParts = (parts: string[]): MailAccountRecord => ({
  email: normalizeEmail(parts[0] || ''),
  password: parts[1] || '',
  client_id: parts[2] || '',
  refresh_token: parts.slice(3).join('----') || '',
})

const isHeaderRow = (parts: string[]) => {
  const firstCell = parts[0]?.toLowerCase() || ''
  return firstCell.includes('邮箱') || firstCell === 'email'
}

export const parseMailAccountTextLines = (lines: string[], splitSymbol = '----'): MailAccountRecord[] => {
  return lines
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const parts = line.includes('\t')
        ? line.split(/\t+/)
        : line.includes('----')
          ? line.split(/-{4,}/)
          : line.split(/\s+/)
      return recordFromParts(parts)
    })
    .filter((item) => item.email.includes('@'))
}

export const parseMailAccountXlsxFile = async (file: File): Promise<MailAccountParseResult> => {
  const buffer = await file.arrayBuffer()
  const workbook = XLSX.read(buffer, { type: 'array' })
  const sheetName = workbook.SheetNames[0]
  if (!sheetName) return { records: [], failedRows: [] }
  const sheet = workbook.Sheets[sheetName]
  if (!sheet) return { records: [], failedRows: [] }
  const rows = XLSX.utils.sheet_to_json<(string | number | null | undefined)[]>(sheet, {
    header: 1,
    raw: false,
    defval: '',
  })

  const records: MailAccountRecord[] = []
  const failedRows: MailAccountFailedRow[] = []
  let dataRowNumber = 0

  rows.forEach((row, index) => {
    if (!Array.isArray(row)) return
    const parts = row.slice(0, 4).map(cellText)
    const hasAnyValue = parts.some(Boolean)
    if (!hasAnyValue) return

    if (index === 0 && isHeaderRow(parts)) return

    dataRowNumber += 1
    const record = recordFromParts(parts)
    const reason = validateMailAccountRecord(record)
    if (reason) {
      failedRows.push({
        rowNumber: dataRowNumber,
        email: parts[0] || '',
        password: parts[1] || '',
        client_id: parts[2] || '',
        refresh_token: parts[3] || '',
        reason,
      })
      return
    }
    records.push({
      email: normalizeEmail(record.email),
      password: record.password,
      client_id: record.client_id,
      refresh_token: record.refresh_token,
    })
  })

  return { records, failedRows }
}

export const buildMailImportPreviewItems = (records: MailAccountRecord[]): MailImportPreviewItem[] => {
  const seen = new Set<string>()
  return records.map((record, index) => {
    const email = normalizeEmail(record.email)
    const validationReason = validateMailAccountRecord({ ...record, email })
    if (validationReason) {
      return {
        key: `invalid-${index}`,
        selected: false,
        kind: 'invalid',
        reason: validationReason,
        record: { ...record, email },
      }
    }
    if (seen.has(email)) {
      return {
        key: `${email}-${index}`,
        selected: false,
        kind: 'duplicate',
        reason: '文件内重复',
        record: { ...record, email },
      }
    }
    seen.add(email)
    return {
      key: email,
      selected: true,
      kind: 'valid',
      record: { ...record, email },
    }
  })
}

export const selectedPreviewRecords = (items: MailImportPreviewItem[]) => {
  return items
    .filter((item) => item.kind === 'valid')
    .map((item) => item.record)
}

export const failedRowsFromPreviewItems = (items: MailImportPreviewItem[]): MailAccountFailedRow[] => {
  return items
    .filter((item) => item.kind !== 'valid')
    .map((item, index) => ({
      rowNumber: index + 1,
      email: item.record.email,
      password: item.record.password,
      client_id: item.record.client_id,
      refresh_token: item.record.refresh_token,
      reason: item.reason || (item.kind === 'duplicate' ? '文件内重复' : '无效'),
    }))
}

export const failedRowsFromRejectedRecords = (
  rejected: Array<{
    email?: string
    password?: string
    client_id?: string
    refresh_token?: string
    line?: string
    reason?: string
  }>,
): MailAccountFailedRow[] => {
  return rejected.map((item, index) => {
    if (item.email || item.password || item.client_id || item.refresh_token) {
      return {
        rowNumber: index + 1,
        email: item.email || '',
        password: item.password || '',
        client_id: item.client_id || '',
        refresh_token: item.refresh_token || '',
        reason: item.reason || '导入失败',
      }
    }

    const line = String(item.line || '')
    const parts = line.includes('----')
      ? line.split(/-{4,}/).map((part) => part.trim())
      : line.includes('\t')
        ? line.split(/\t+/).map((part) => part.trim())
        : line.split(/\s+/).map((part) => part.trim())

    return {
      rowNumber: index + 1,
      email: parts[0] || line.slice(0, 200),
      password: parts[1] || '',
      client_id: parts[2] || '',
      refresh_token: parts.slice(3).join('----') || '',
      reason: item.reason || '导入失败',
    }
  })
}

export const mergeFailedRows = (...groups: MailAccountFailedRow[][]) => {
  const merged: MailAccountFailedRow[] = []
  const seen = new Set<string>()
  for (const group of groups) {
    for (const row of group) {
      const key = [
        row.email,
        row.password,
        row.client_id,
        row.refresh_token,
        row.reason,
      ].join('|')
      if (seen.has(key)) continue
      seen.add(key)
      merged.push({
        ...row,
        rowNumber: merged.length + 1,
      })
    }
  }
  return merged
}

export const downloadMailImportFailedXlsx = (
  rows: MailAccountFailedRow[],
  filename = '邮箱导入失败记录.xlsx',
) => {
  if (!rows.length) return
  const sheetData = [
    ['邮箱', '邮箱密码', 'client_id', 'token', '失败原因'],
    ...rows.map((row) => [
      row.email,
      row.password,
      row.client_id,
      row.refresh_token,
      row.reason,
    ]),
  ]
  const worksheet = XLSX.utils.aoa_to_sheet(sheetData)
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, worksheet, '失败记录')
  XLSX.writeFile(workbook, filename)
}
