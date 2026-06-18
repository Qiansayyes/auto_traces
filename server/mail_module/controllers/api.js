const service = require('../services/api')
const { listTemplates, saveTemplate, deleteTemplate } = require('../services/TemplateService')
const database = require('../services/DatabaseService')
const logger = require('../utils/logger')
const { addMailLinksToCollection } = require('../utils/mailLinks')
const { withOwnerOptions } = require('../utils/requestOwner')

const ownerCtx = (ctx, extra = {}) => withOwnerOptions(ctx, extra)

const extractFetchedEmails = (data) => {
  if (Array.isArray(data)) return data
  if (Array.isArray(data?.emails)) return data.emails
  return []
}

const resolveTop = (top, fallback) => {
  if (top === 0 || top === '0' || String(top || '').toLowerCase() === 'all') {
    return 0
  }

  const value = Number(top)
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : fallback
}

const requestData = (ctx) => ctx.method === "GET" ? ctx.query : (ctx.request.body || {})

const normalizeMailboxName = (value = '') => {
  const text = String(value || '').trim()
  if (!text) return ''
  const lower = text.toLowerCase()
  if (lower === 'inbox' || lower.includes('收件')) return 'INBOX'
  if (lower === 'junk' || lower === 'spam' || lower.includes('垃圾')) return 'Junk'
  if (lower === 'deleted' || lower === 'deleteditems' || lower.includes('删除')) return 'Deleted'
  if (lower === 'sent' || lower === 'sentitems' || lower.includes('已发送')) return 'Sent'
  if (lower === 'drafts' || lower.includes('草稿')) return 'Drafts'
  if (lower === 'archive' || lower.includes('归档')) return 'Archive'
  return text
}

const toMailboxList = (value) => {
  if (Array.isArray(value)) {
    return value.map(normalizeMailboxName).filter(Boolean)
  }
  const text = String(value || '').trim()
  if (!text) return ['INBOX', 'Junk']
  return text.split(',').map(normalizeMailboxName).filter(Boolean)
}

const getProjectTop = (top) => {
  const value = resolveTop(top, 50)
  if (value === 0) return 0
  return Math.min(Math.max(value, 1), 200)
}

const getProjectHydrateConcurrency = (value) => {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? Math.min(Math.floor(parsed), 10) : 5
}

const getContentOptions = (params = {}, fallbackMode = 'none') => {
  const requestedMode = String(params.contentMode || '').trim().toLowerCase()
  const fullBody = params.includeBody === true
    || params.includeBody === '1'
    || params.includeBody === 'true'
    || params.fullBody === true
    || params.fullBody === '1'
    || params.fullBody === 'true'
  const contentMode = ['none', 'preview', 'full'].includes(requestedMode)
    ? requestedMode
    : fullBody
      ? 'full'
      : fallbackMode
  return {
    contentMode,
    includeContent: contentMode === 'full',
  }
}

const isLegacyAutoLatestMailRequest = (ctx, params = {}, requestedTop = 1) => {
  if (ctx.method !== 'POST') return false
  if (params.contentMode || params.includeBody || params.fullBody || params.template_name || params.manualFetch || params.allowLegacyFetch) {
    return false
  }
  const mailbox = normalizeMailboxName(params.mailbox || '')
  return requestedTop > 0
    && requestedTop <= 10
    && ['INBOX', 'Junk'].includes(mailbox)
}

const sanitizeProjectMessage = (mail, includeBody = false) => {
  const base = {
    id: mail.id || '',
    send: mail.send || '',
    subject: mail.subject || '',
    date: mail.date || '',
    mailbox: mail.mailbox || '',
    mailboxLabel: mail.mailboxLabel || '',
    senderCompanyIdentity: mail.senderCompanyIdentity || '',
    senderCompanyLabel: mail.senderCompanyLabel || '',
    verificationCode: mail.verificationCode || '',
    verificationSource: mail.verificationSource || '',
    links: Array.isArray(mail.links) ? mail.links : [],
    linkDetails: Array.isArray(mail.linkDetails) ? mail.linkDetails : [],
    verificationLinks: Array.isArray(mail.verificationLinks) ? mail.verificationLinks : [],
    primaryLink: mail.primaryLink || '',
    primaryLinkText: mail.primaryLinkText || mail.primaryLinkLabel || '',
    primaryLinkLabel: mail.primaryLinkLabel || mail.primaryLinkText || '',
    bodyMode: mail.bodyMode || (mail.html ? 'full' : (mail.text ? 'preview' : 'none')),
    hasPreview: Boolean(mail.text),
    hasBody: Boolean(mail.html),
  }

  if (includeBody) {
    return {
      ...base,
      text: mail.text || '',
      html: mail.html || '',
    }
  }

  return base
}

const buildMailOneIdentifiers = (mail, fallbackTop) => ({
  messageId: mail.id || '',
  uid: mail.uid || '',
  seqno: mail.seqno || '',
  subject: mail.subject || '',
  send: mail.send || '',
  date: mail.date || '',
  timezoneOffset: 480,
  fallbackTop,
})

const mergeHydratedProjectEmails = (emails = [], hydratedEmails = []) => {
  const makeKey = (mail) => [
    mail.id || '',
    mail.uid || '',
    mail.seqno || '',
    mail.date || '',
    mail.subject || '',
    mail.send || '',
    mail.mailbox || '',
  ].join('__')
  const merged = [...emails]

  for (const hydrated of hydratedEmails) {
    const hydratedKey = makeKey(hydrated)
    const existingIndex = merged.findIndex((mail) => makeKey(mail) === hydratedKey)
    if (existingIndex >= 0) {
      merged[existingIndex] = {
        ...merged[existingIndex],
        ...hydrated,
        mailbox: hydrated.mailbox || merged[existingIndex].mailbox,
        mailboxLabel: hydrated.mailboxLabel || merged[existingIndex].mailboxLabel,
      }
    } else {
      merged.push(hydrated)
    }
  }

  return merged
}

const mapWithConcurrency = async (items, concurrency, mapper) => {
  const list = Array.isArray(items) ? items : []
  const results = new Array(list.length)
  let cursor = 0

  const workers = Array.from({ length: Math.min(Math.max(concurrency, 1), list.length) }, async () => {
    while (cursor < list.length) {
      const index = cursor
      cursor += 1
      results[index] = await mapper(list[index], index)
    }
  })

  await Promise.all(workers)
  return results
}

const controller = {
  async mail_all(ctx) {
    const params = ctx.method === "GET" ? ctx.query : ctx.request.body
    const { refresh_token, client_id, email, mailbox, socks5, http, top } = params;
    const requestedTop = resolveTop(top, 0)
    const normalizedMailbox = normalizeMailboxName(mailbox || 'INBOX')
    try {
      const data = addMailLinksToCollection(await service.mail_all(refresh_token, client_id, email, normalizedMailbox, socks5, http, requestedTop, getContentOptions(params, 'full')))
      database.saveFetchedEmails(ownerCtx(ctx, {
        accountEmail: email,
        mailbox: normalizedMailbox,
        emails: extractFetchedEmails(data),
        source: 'mail_all',
        requestedTop,
      }))
      ctx.body = { code: "200", data }
    } catch (err) {
      database.recordFetchFailure({
        accountEmail: email,
        mailbox: normalizedMailbox,
        source: 'mail_all',
        requestedTop,
        error: err.message,
      })
      logger.error('Failed to mail_all', err)
      ctx.throw(500, 'Failed to mail_all')
    }
  },

  async mail_new(ctx) {
    const params = ctx.method === "GET" ? ctx.query : ctx.request.body
    const { refresh_token, client_id, email, mailbox, socks5, http, top, template_name } = params;
    const requestedTop = resolveTop(top, 1)
    const normalizedMailbox = normalizeMailboxName(mailbox || 'INBOX')
    try {
      if (isLegacyAutoLatestMailRequest(ctx, params, requestedTop)) {
        ctx.body = {
          code: "200",
          data: [],
          skipped: true,
          reason: 'legacy_auto_refresh_blocked',
        }
        return
      }
      const data = addMailLinksToCollection(await service.mail_new(refresh_token, client_id, email, normalizedMailbox, socks5, http, requestedTop, template_name, getContentOptions(params, 'preview')))
      database.saveFetchedEmails(ownerCtx(ctx, {
        accountEmail: email,
        mailbox: normalizedMailbox,
        emails: extractFetchedEmails(data),
        source: 'mail_new',
        requestedTop,
      }))
      ctx.body = { code: "200", data }
    } catch (err) {
      database.recordFetchFailure({
        accountEmail: email,
        mailbox: normalizedMailbox,
        source: 'mail_new',
        requestedTop,
        error: err.message,
      })
      logger.error('Failed to mail_new', err)
      ctx.throw(500, 'Failed to mail_new')
    }
  },

  async mail_one(ctx) {
    const params = ctx.method === "GET" ? ctx.query : ctx.request.body
    const { refresh_token, client_id, email, mailbox, socks5, http, id, messageId, uid, seqno, subject, send, date, timezoneOffset, fallbackTop } = params;
    const normalizedMailbox = normalizeMailboxName(mailbox || 'INBOX')
    const requestedMessageId = String(messageId || id || '').trim()
    const requestedUid = String(uid || '').trim()
    const requestedSeqno = String(seqno || '').trim()
    const requestedSubject = String(subject || '').trim()
    const requestedSend = String(send || '').trim()
    const requestedDate = String(date || '').trim()

    if (!requestedMessageId && !requestedUid && !requestedSeqno && !requestedSubject && !requestedSend && !requestedDate) {
      ctx.throw(400, 'Missing message identity')
      return
    }

    try {
      const data = addMailLinksToCollection(await service.mail_one(
        refresh_token,
        client_id,
        email,
        normalizedMailbox,
        socks5,
        http,
        {
          messageId: requestedMessageId,
          uid: requestedUid,
          seqno: requestedSeqno,
          subject: requestedSubject,
          send: requestedSend,
          date: requestedDate,
          timezoneOffset,
          fallbackTop,
        },
        getContentOptions({ ...params, contentMode: params.contentMode || 'full' }, 'full'),
      ))
      database.saveFetchedEmails(ownerCtx(ctx, {
        accountEmail: email,
        mailbox: normalizedMailbox,
        emails: extractFetchedEmails(data),
        source: 'mail_one',
        requestedTop: 1,
      }))
      ctx.body = { code: "200", data }
    } catch (err) {
      database.recordFetchFailure({
          accountEmail: email,
          mailbox: normalizedMailbox,
        source: 'mail_one',
        requestedTop: 1,
        error: err.message,
      })
      logger.error('Failed to mail_one', err)
      ctx.throw(500, 'Failed to mail_one')
    }
  },

  async process_mailbox(ctx) {
    try {
      const { refresh_token, client_id, email, mailbox, socks5, http } = ctx.method === "GET" ? ctx.query : ctx.request.body;
      const data = await service.process_mailbox(refresh_token, client_id, email, mailbox, socks5, http)
      ctx.body = { code: "200", data }
    } catch (err) {
      logger.error('Failed to process_mailbox', err)
      ctx.throw(500, 'Failed to process_mailbox')
    }
  },

  async test_proxy(ctx) {
    try {
      const { socks5, http } = ctx.method === "GET" ? ctx.query : ctx.request.body;
      const data = await service.test_proxy(socks5, http)
      ctx.body = { code: "200", data }
    } catch (err) {
      logger.error('Failed to test_proxy', err)
      ctx.throw(500, 'Failed to test_proxy')
    }
  },

  async template_list(ctx) {
    try {
      const data = listTemplates(ownerCtx(ctx).ownerUsername)
      ctx.body = { code: "200", data }
    } catch (err) {
      logger.error('Failed to list templates', err)
      ctx.throw(500, 'Failed to list templates')
    }
  },

  async template_save(ctx) {
    try {
      const template = ctx.request.body;
      const data = saveTemplate(template, ownerCtx(ctx).ownerUsername)
      ctx.body = { code: "200", data }
    } catch (err) {
      logger.error('Failed to save template', err)
      ctx.throw(400, err.message)
    }
  },

  async template_delete(ctx) {
    try {
      const { name } = ctx.method === "GET" ? ctx.query : ctx.request.body;
      if (!name) {
        ctx.throw(400, 'Template name is required')
      }
      const success = deleteTemplate(name, ownerCtx(ctx).ownerUsername)
      ctx.body = { code: "200", data: { deleted: success } }
    } catch (err) {
      logger.error('Failed to delete template', err)
      ctx.throw(500, 'Failed to delete template')
    }
  },

  async sender_filter(ctx) {
    try {
      const { senders, mailboxes, previewLimit, itemLimit } = ctx.request.body;

      if (!senders || !Array.isArray(senders)) {
        ctx.throw(400, 'senders array is required')
      }

      ctx.body = {
        code: "200",
        data: database.filterSenderGroups(senders, Array.isArray(mailboxes) ? mailboxes : [], ownerCtx(ctx, { previewLimit, itemLimit }))
      }
    } catch (err) {
      logger.error('Failed to sender_filter', err)
      ctx.throw(500, 'Failed to sender_filter')
    }
  },

  async sender_groups(ctx) {
    try {
      const { page, pageSize, search, previewLimit, itemLimit, sort } = ctx.request.body || {};
      ctx.body = {
        code: "200",
        data: database.listSenderGroups(ownerCtx(ctx, { page, pageSize, search, previewLimit, itemLimit, sort }))
      }
    } catch (err) {
      logger.error('Failed to list sender groups', err)
      ctx.throw(500, 'Failed to list sender groups')
    }
  },

  async sender_options(ctx) {
    try {
      const { limit, search, sort } = requestData(ctx)
      ctx.body = {
        code: "200",
        data: database.getSenderOptionRecords(ownerCtx(ctx, { limit, search, sort }))
      }
    } catch (err) {
      logger.error('Failed to list sender options', err)
      ctx.throw(500, 'Failed to list sender options')
    }
  },

  async accounts_import(ctx) {
    try {
      const { records, lines, splitSymbol, source } = ctx.request.body;
      const input = Array.isArray(records)
        ? records
        : String(lines || '').split(/\r?\n/).map(item => item.trim()).filter(Boolean);

      const data = database.importAccounts(input, ownerCtx(ctx, {
        splitSymbol: splitSymbol || '----',
        source: source || 'ui',
      }))

      ctx.body = { code: "200", data }
    } catch (err) {
      logger.error('Failed to import accounts', err)
      ctx.throw(500, 'Failed to import accounts')
    }
  },

  async accounts_list(ctx) {
    try {
      const {
        page,
        pageSize,
        search,
        excludeProject,
        excludeDoneProject,
        unusedProject,
        notDoneProject,
        excludeSenderCompanyIdentity,
        excludeSenderCompanyIdentities,
        excludeSenderIdentity,
        excludeSenderIdentities,
        senderCompanyIdentity,
        senderCompanyIdentities,
        projectUsage,
        projectUsageFilter,
        projectStatus,
        projectStatusFilter,
      } = ctx.method === "GET" ? ctx.query : ctx.request.body;
      ctx.body = {
        code: "200",
        data: database.listAccounts(ownerCtx(ctx, {
          page,
          pageSize,
          search,
          excludeProject,
          excludeDoneProject,
          unusedProject,
          notDoneProject,
          excludeSenderCompanyIdentity,
          excludeSenderCompanyIdentities,
          excludeSenderIdentity,
          excludeSenderIdentities,
          senderCompanyIdentity,
          senderCompanyIdentities,
          projectUsage,
          projectUsageFilter,
          projectStatus,
          projectStatusFilter,
        }))
      }
    } catch (err) {
      logger.error('Failed to list accounts', err)
      ctx.throw(500, 'Failed to list accounts')
    }
  },

  async accounts_delete(ctx) {
    try {
      const { emails, email, deleteAll } = ctx.request.body || {};
      const targets = Array.isArray(emails)
        ? emails
        : (email ? [email] : []);
      const data = database.deleteAccounts(ownerCtx(ctx, {
        emails: targets,
        deleteAll,
      }));
      ctx.body = { code: "200", data };
    } catch (err) {
      logger.error('Failed to delete accounts', err)
      ctx.throw(500, 'Failed to delete accounts')
    }
  },

  async tk_pending_import(ctx) {
    try {
      const { records, lines, splitSymbol, source } = ctx.request.body;
      const input = Array.isArray(records)
        ? records
        : String(lines || '').split(/\r?\n/).map(item => item.trim()).filter(Boolean);

      const data = database.importPendingTkAccounts(input, ownerCtx(ctx, {
        splitSymbol: splitSymbol || '----',
        source: source || 'tk_pending_ui',
      }));

      ctx.body = { code: "200", data };
    } catch (err) {
      logger.error('Failed to import pending tk accounts', err)
      ctx.throw(500, 'Failed to import pending tk accounts')
    }
  },

  async tk_pending_list(ctx) {
    try {
      const { page, pageSize, search, poolStatus, status } = ctx.method === "GET" ? ctx.query : ctx.request.body;
      ctx.body = {
        code: "200",
        data: database.listPendingTkAccounts(ownerCtx(ctx, {
          page,
          pageSize,
          search,
          poolStatus,
          status,
        })),
      };
    } catch (err) {
      logger.error('Failed to list pending tk accounts', err)
      ctx.throw(500, 'Failed to list pending tk accounts')
    }
  },

  async tk_pending_delete(ctx) {
    try {
      const { emails, email, deleteAll, onlyAvailable } = ctx.request.body || {};
      const targets = Array.isArray(emails)
        ? emails
        : (email ? [email] : []);
      const data = database.deletePendingTkAccounts(ownerCtx(ctx, {
        emails: targets,
        deleteAll,
        onlyAvailable,
      }));
      ctx.body = { code: "200", data };
    } catch (err) {
      logger.error('Failed to delete pending tk accounts', err)
      ctx.throw(500, 'Failed to delete pending tk accounts')
    }
  },

  async tk_pending_acquire(ctx) {
    try {
      const params = requestData(ctx);
      const data = database.acquirePendingTkAccount(ownerCtx(ctx, {
        email: params.email,
        includeRaw: params.includeRaw,
      }));
      ctx.body = { code: "200", data };
    } catch (err) {
      logger.error('Failed to acquire pending tk account', err)
      ctx.throw(400, err.message || 'Failed to acquire pending tk account')
    }
  },

  async tk_pending_take(ctx) {
    try {
      const params = requestData(ctx);
      const data = database.takePendingTkAccount(ownerCtx(ctx, {
        email: params.email,
        includeRaw: params.includeRaw,
        source: params.source,
      }));
      ctx.body = { code: "200", data };
    } catch (err) {
      logger.error('Failed to take pending tk account', err)
      ctx.throw(400, err.message || 'Failed to take pending tk account')
    }
  },

  async tk_pending_complete(ctx) {
    try {
      const params = requestData(ctx);
      const data = database.completePendingTkAccount(ownerCtx(ctx, {
        email: params.email,
        claimId: params.claimId || params.claim_id,
        source: params.source,
      }));
      ctx.body = { code: "200", data };
    } catch (err) {
      logger.error('Failed to complete pending tk account', err)
      ctx.throw(400, err.message || 'Failed to complete pending tk account')
    }
  },

  async mail_cache_import(ctx) {
    try {
      const { email, accountEmail, mailbox, emails } = ctx.request.body;
      const data = database.saveFetchedEmails(ownerCtx(ctx, {
        accountEmail: accountEmail || email,
        mailbox,
        emails: Array.isArray(emails) ? emails : [],
        source: 'local_cache_import',
        requestedTop: Array.isArray(emails) ? emails.length : 0,
      }))

      ctx.body = { code: "200", data }
    } catch (err) {
      logger.error('Failed to import cached mail', err)
      ctx.throw(500, 'Failed to import cached mail')
    }
  },

  async database_stats(ctx) {
    try {
      ctx.body = {
        code: "200",
        data: {
          accounts: database.getAccountStats(ownerCtx(ctx)),
          mail: database.getMailStats(ownerCtx(ctx)),
          paths: database.paths,
        }
      }
    } catch (err) {
      logger.error('Failed to get database stats', err)
      ctx.throw(500, 'Failed to get database stats')
    }
  },

  async project_upsert(ctx) {
    try {
      const data = database.upsertProject(ownerCtx(ctx, requestData(ctx)))
      ctx.body = { code: "200", data }
    } catch (err) {
      logger.error('Failed to save project', err)
      ctx.throw(400, err.message || 'Failed to save project')
    }
  },

  async project_list(ctx) {
    try {
      ctx.body = { code: "200", data: database.listProjects(ownerCtx(ctx)) }
    } catch (err) {
      logger.error('Failed to list projects', err)
      ctx.throw(500, 'Failed to list projects')
    }
  },

  async project_mailboxes(ctx) {
    try {
      const startedAt = Date.now()
      const params = requestData(ctx)
      const data = database.listProjectMailboxPools(ownerCtx(ctx, {
        ...params,
        includeRows: params.includeRows === true || params.includeRows === '1' || params.includeRows === 'true',
      }))
      ctx.body = { code: "200", data, elapsedMs: Date.now() - startedAt }
    } catch (err) {
      logger.error('Failed to list project mailboxes', err)
      ctx.throw(400, err.message || 'Failed to list project mailboxes')
    }
  },

  async project_acquire(ctx) {
    try {
      const startedAt = Date.now()
      const params = requestData(ctx)
      const data = database.acquireProjectAccount(ownerCtx(ctx, {
        ...params,
        includeRaw: params.includeRaw === true || params.includeRaw === '1' || params.includeRaw === 'true',
        allowRetryFailed: params.allowRetryFailed === true || params.allowRetryFailed === '1' || params.allowRetryFailed === 'true',
      }))
      ctx.body = { code: "200", data: { ...data, email: data.account?.email || '', elapsedMs: Date.now() - startedAt } }
    } catch (err) {
      logger.error('Failed to acquire project account', err)
      ctx.throw(400, err.message || 'Failed to acquire project account')
    }
  },

  async project_status(ctx) {
    try {
      const { project, projectKey, key, id, email } = requestData(ctx)
      const resolvedProject = projectKey || project || key || id
      if (!resolvedProject || !email) {
        ctx.throw(400, 'project and email are required')
      }
      ctx.body = { code: "200", data: database.getProjectAccountStatus(resolvedProject, email, ownerCtx(ctx)) }
    } catch (err) {
      logger.error('Failed to get project status', err)
      ctx.throw(400, err.message || 'Failed to get project status')
    }
  },

  async project_marks(ctx) {
    try {
      const params = requestData(ctx)
      const resolvedProject = params.projectKey || params.project || params.key || params.id
      if (!resolvedProject) {
        ctx.throw(400, 'project is required')
      }
      ctx.body = {
        code: "200",
        data: database.listProjectMarks(resolvedProject, ownerCtx(ctx, {
          status: params.status,
          limit: params.limit,
        }))
      }
    } catch (err) {
      logger.error('Failed to list project marks', err)
      ctx.throw(400, err.message || 'Failed to list project marks')
    }
  },

  async project_mail(ctx) {
    const startedAt = Date.now()
    const params = requestData(ctx)
    const resolvedProject = params.projectKey || params.project || params.key || params.id
    const accountEmail = params.email || params.accountEmail
    const mailboxes = toMailboxList(params.mailboxes || params.mailbox)
    const requestedTop = getProjectTop(params.top)
    const hydrateConcurrency = getProjectHydrateConcurrency(params.hydrateConcurrency)
    const projectContentOptions = getContentOptions(params, 'preview')
    const fullBody = projectContentOptions.contentMode === 'full'
    const socks5 = params.socks5
    const http = params.http

    if (!resolvedProject || !accountEmail) {
      ctx.throw(400, 'project and email are required')
    }
    if (mailboxes.length === 0) {
      ctx.throw(400, 'mailbox is invalid')
    }

    const account = database.getAccountByEmail(accountEmail, ownerCtx(ctx))
    if (!account) {
      ctx.throw(404, 'account not found')
    }
    if (!account.client_id || !account.refresh_token) {
      ctx.throw(400, 'account missing client_id or refresh_token')
    }

    const mailboxResults = await Promise.all(mailboxes.map(async (mailbox) => {
      try {
        const data = await service.mail_new(
          account.refresh_token,
          account.client_id,
          account.email,
          mailbox,
          socks5,
          http,
          requestedTop,
          null,
          projectContentOptions,
        )
        const emails = extractFetchedEmails(data).map((mail) => ({
          ...mail,
          mailbox,
          mailboxLabel: mailbox === 'Junk' ? 'Junk' : 'INBOX',
        }))
        const saved = database.saveFetchedEmails(ownerCtx(ctx, {
          accountEmail: account.email,
          mailbox,
          emails,
          source: 'project_mail',
          requestedTop,
        }))
        return { mailbox, ok: true, fetched: emails.length, savedSenderReceipts: saved.saved_count, emails }
      } catch (err) {
        database.recordFetchFailure({
          accountEmail: account.email,
          mailbox,
          source: 'project_mail',
          requestedTop,
          error: err.message,
        })
        logger.error('Failed to fetch project mailbox', err)
        return { mailbox, ok: false, fetched: 0, savedSenderReceipts: 0, error: err.message, emails: [] }
      }
    }))

    const previewEmails = mailboxResults.flatMap((item) => item.emails || [])
    const errors = mailboxResults.filter((item) => !item.ok).map((item) => `${item.mailbox}: ${item.error}`)
    const previewResult = database.summarizeProjectMailResult(ownerCtx(ctx, {
      ...params,
      projectKey: resolvedProject,
      email: account.email,
      emails: previewEmails,
    }))
    const hydratedEmails = []

    if (!fullBody && previewResult.matched.length > 0) {
      const fallbackTop = requestedTop > 0 ? Math.max(requestedTop, 50) : 200
      const hydratedResults = await mapWithConcurrency(previewResult.matched, hydrateConcurrency, async (mail) => {
        try {
          const mailbox = mail.mailbox || 'INBOX'
          const hydrated = await service.mail_one(
            account.refresh_token,
            account.client_id,
            account.email,
            mailbox,
            socks5,
            http,
            buildMailOneIdentifiers(mail, fallbackTop),
            { includeContent: true, contentMode: 'full' },
          )
          const [fullMail] = extractFetchedEmails(hydrated)
          return fullMail
            ? {
              ...fullMail,
              mailbox,
              mailboxLabel: mail.mailboxLabel || (mailbox === 'Junk' ? 'Junk' : 'INBOX'),
            }
            : null
        } catch (err) {
          logger.warn('Failed to hydrate project candidate mail', {
            project: resolvedProject,
            email: account.email,
            mailbox: mail.mailbox,
            subject: mail.subject,
            error: err.message,
          })
          return null
        }
      })
      hydratedEmails.push(...hydratedResults.filter(Boolean))
    }

    const allEmails = mergeHydratedProjectEmails(previewEmails, hydratedEmails)
    const result = database.recordProjectMailResult(ownerCtx(ctx, {
      ...params,
      projectKey: resolvedProject,
      email: account.email,
      emails: allEmails,
      error: errors.length > 0 ? errors.join('; ') : '',
    }))

    ctx.body = {
      code: "200",
      data: {
        project: result.project,
        email: result.email,
        status: result.status,
        received: result.received,
        latestCode: result.latestCode,
        verificationCode: result.latestCode,
        latestLink: result.latestLink,
        latestLinkText: result.latestLinkText,
        verificationLink: result.latestLink,
        verificationLinkText: result.latestLinkText,
        matchedCount: result.matchedCount,
        messageCount: result.messageCount,
        elapsedMs: Date.now() - startedAt,
        requestedTop,
        hydrateConcurrency,
        hydratedCount: hydratedEmails.length,
        mailboxResults: mailboxResults.map(({ emails, ...item }) => item),
        matched: result.matched.map((mail) => sanitizeProjectMessage(mail, fullBody)),
        messages: result.messages.map((mail) => sanitizeProjectMessage(mail, fullBody)),
      }
    }
  },
}

module.exports = controller
