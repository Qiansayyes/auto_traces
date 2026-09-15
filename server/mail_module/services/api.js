const logger = require('../utils/logger')
const { use_graph_api, use_get_graph_emails, use_get_graph_email_by_id, use_imap_api, generateAuthString, use_get_imap_emails, use_get_imap_email_by_id, process_mails, use_test_proxy, use_delete_graph_emails } = require('../services/MailService')
const { getTemplate, matchAndExtract } = require('../services/TemplateService')

const normalizeComparableText = (value = '') => String(value || '').trim().toLowerCase()

const getMessageTime = (value = '') => {
  const time = new Date(value || '').getTime()
  return Number.isFinite(time) ? time : 0
}

const hasTimezoneMarker = (value = '') => /(?:z|[+-]\d{2}:?\d{2})$/i.test(String(value || '').trim())

const getMessageTimeCandidates = (value = '', timezoneOffset = '') => {
  const raw = String(value || '').trim()
  if (!raw) return []

  const times = []
  const parsedTime = getMessageTime(raw)
  if (parsedTime) times.push(parsedTime)

  const naiveMatch = raw.match(/^(\d{4})[/-](\d{1,2})[/-](\d{1,2})(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?/)
  if (naiveMatch && !hasTimezoneMarker(raw)) {
    const [, year, month, day, hour = '0', minute = '0', second = '0'] = naiveMatch
    const localTime = Date.UTC(
      Number(year),
      Number(month) - 1,
      Number(day),
      Number(hour),
      Number(minute),
      Number(second),
    )
    const timezoneOffsetText = String(timezoneOffset ?? '').trim()
    const parsedOffset = Number(timezoneOffsetText)
    const offsets = timezoneOffsetText && Number.isFinite(parsedOffset) ? [parsedOffset] : [480]
    for (const offset of offsets) {
      times.push(localTime + offset * 60 * 1000)
    }
  }

  return Array.from(new Set(times.filter(Number.isFinite)))
}

const getClosestMessageTimeDiff = (mail = {}, identifiers = {}) => {
  const mailTimes = getMessageTimeCandidates(mail.date)
  const targetTimes = getMessageTimeCandidates(identifiers.date, identifiers.timezoneOffset)
  if (!mailTimes.length || !targetTimes.length) return Number.POSITIVE_INFINITY

  let closest = Number.POSITIVE_INFINITY
  for (const mailTime of mailTimes) {
    for (const targetTime of targetTimes) {
      closest = Math.min(closest, Math.abs(mailTime - targetTime))
    }
  }
  return closest
}

const matchesMessageText = (mail = {}, identifiers = {}) => {
  const subject = normalizeComparableText(identifiers.subject)
  const send = normalizeComparableText(identifiers.send)
  const sameSubject = !subject || normalizeComparableText(mail.subject) === subject
  const sameSender = !send || normalizeComparableText(mail.send) === send
  return (subject || send) && sameSubject && sameSender
}

const matchesMessageIdentity = (mail = {}, identifiers = {}) => {
  const messageId = String(identifiers.messageId || '').trim()
  const uid = String(identifiers.uid || '').trim()
  const seqno = String(identifiers.seqno || '').trim()

  if (messageId && String(mail.id || '').trim() === messageId) return true
  if (uid && String(mail.uid || '').trim() === uid) return true
  if (seqno && String(mail.seqno || '').trim() === seqno) return true

  if (!matchesMessageText(mail, identifiers)) return false

  const closestTimeDiff = getClosestMessageTimeDiff(mail, identifiers)
  return Number.isFinite(closestTimeDiff) ? closestTimeDiff <= 5 * 60 * 1000 : true
}

const findMatchingMessage = (emails = [], identifiers = {}) => {
  const exactMatch = emails.find(mail => matchesMessageIdentity(mail, identifiers))
  if (exactMatch) return exactMatch

  const textMatches = emails
    .filter(mail => matchesMessageText(mail, identifiers))
    .map(mail => ({ mail, diff: getClosestMessageTimeDiff(mail, identifiers) }))
    .sort((a, b) => a.diff - b.diff)

  return textMatches[0]?.mail || null
}

// Keep tokenClassification so controllers can persist invalid/transient token status.
const rethrowServiceError = (message, err) => {
  const wrapped = new Error(message)
  if (err?.tokenClassification) {
    wrapped.tokenClassification = err.tokenClassification
  }
  if (err?.message) {
    wrapped.cause = err
  }
  throw wrapped
}

const service = {
  async mail_all(refresh_token, client_id, email, mailbox, socks5, http, top = 0, options = {}) {
    try {

      const graph_api_result = await use_graph_api(refresh_token, client_id, mailbox, email, socks5, http)

      if (graph_api_result.status) {
        const graph_emails = await use_get_graph_emails(graph_api_result, top, email, socks5, http, options)
        return graph_emails
      }

      const imap_api_result = await use_imap_api(refresh_token, client_id, email, socks5, http)
      const authString = generateAuthString(email, imap_api_result.access_token)
      const imap_emails = await use_get_imap_emails(email, authString, mailbox, top, options)

      return imap_emails
    } catch (err) {
      logger.error('Service error when mail_all', err)
      rethrowServiceError('Failed to retrieve mail_all', err)
    }
  },

  async mail_new(refresh_token, client_id, email, mailbox, socks5, http, top = 1, template_name = null, options = {}) {
    try {

      const graph_api_result = await use_graph_api(refresh_token, client_id, mailbox, email, socks5, http)
      let emails;

      if (graph_api_result.status) {
        emails = await use_get_graph_emails(graph_api_result, top, email, socks5, http, options)
      } else {
        const imap_api_result = await use_imap_api(refresh_token, client_id, email, socks5, http)
        const authString = generateAuthString(email, imap_api_result.access_token)
        emails = await use_get_imap_emails(email, authString, mailbox, top, options)
      }

      // 如果指定了模板，进行匹配提取
      if (template_name) {
        const template = getTemplate(template_name)
        if (!template) {
          return { emails, matched: null, error: `Template '${template_name}' not found` }
        }
        const matched = matchAndExtract(emails, template)
        return { emails, matched }
      }

      return emails
    } catch (err) {
      logger.error('Service error when mail_new', err)
      rethrowServiceError('Failed to retrieve mail_new', err)
    }
  },

  async mail_one(refresh_token, client_id, email, mailbox, socks5, http, identifiers = {}, options = {}) {
    try {
      const graph_api_result = await use_graph_api(refresh_token, client_id, mailbox, email, socks5, http)
      const fallbackTop = Number.isFinite(Number(identifiers.fallbackTop)) && Number(identifiers.fallbackTop) > 0
        ? Math.min(Number(identifiers.fallbackTop), 500)
        : 100

      if (graph_api_result.status && identifiers.messageId) {
        try {
          const graphEmail = await use_get_graph_email_by_id(graph_api_result, identifiers.messageId, email, socks5, http, options)
          if (graphEmail) return [graphEmail]
        } catch (err) {
          logger.warn('Graph mail_one by id failed, falling back to mailbox scan', {
            email,
            mailbox,
            error: err.message,
          })
        }
      }

      if (graph_api_result.status) {
        const graphEmails = await use_get_graph_emails(graph_api_result, fallbackTop, email, socks5, http, options)
        const matchedGraphEmail = findMatchingMessage(graphEmails, identifiers)
        return matchedGraphEmail ? [matchedGraphEmail] : []
      }

      const imap_api_result = await use_imap_api(refresh_token, client_id, email, socks5, http)
      const authString = generateAuthString(email, imap_api_result.access_token)
      const imapEmail = await use_get_imap_email_by_id(email, authString, mailbox, identifiers, options)
      if (imapEmail) return [imapEmail]

      const imapEmails = await use_get_imap_emails(email, authString, mailbox, fallbackTop, options)
      const matchedImapEmail = findMatchingMessage(imapEmails, identifiers)
      return matchedImapEmail ? [matchedImapEmail] : []
    } catch (err) {
      logger.error('Service error when mail_one', err)
      rethrowServiceError('Failed to retrieve mail_one', err)
    }
  },

  async process_mailbox(refresh_token, client_id, email, mailbox, socks5, http) {
    try {

      const graph_api_result = await use_graph_api(refresh_token, client_id, mailbox, email, socks5, http)

      if (graph_api_result.status) {
        const graph_emails = await use_get_graph_emails(graph_api_result, undefined, email, socks5, http)

        for (const mail of graph_emails) {
          await use_delete_graph_emails(graph_api_result, mail.id, socks5, http)
        }

        return { message: '邮件正在清空中... 请稍后查看邮件' }
      }

      const imap_api_result = await use_imap_api(refresh_token, client_id, email, socks5, http)
      const authString = generateAuthString(email, imap_api_result.access_token)
      await process_mails(email, authString, mailbox)

      return { message: '邮件正在清空中... 请稍后查看邮件' }
    } catch (err) {
      logger.error('Service error when process_mailbox', err)
      rethrowServiceError('Failed to process_mailbox', err)
    }
  },

  async test_proxy(socks5, http) {
    try {
      const data = await use_test_proxy(socks5, http)
      return data
    } catch (err) {
      logger.error('Service error when test_proxy', err)
      throw new Error('Failed to test_proxy')
    }
  },
}

module.exports = service
