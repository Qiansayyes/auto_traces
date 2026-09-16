const logger = require('../utils/logger');

const resolveMainApiBase = () => {
  const configured = String(process.env.MAIN_API_BASE || '').trim().replace(/\/+$/, '');
  if (configured) return configured;
  const host = String(process.env.MAIN_API_HOST || '127.0.0.1').trim() || '127.0.0.1';
  const port = String(process.env.MAIN_API_PORT || '8000').trim() || '8000';
  return `http://${host}:${port}`;
};

const resolveInternalSecret = () =>
  String(process.env.MAIL_INTERNAL_SECRET || 'auto-traces-mail-internal').trim();

/**
 * Notify main FastAPI that a mail OAuth token status changed.
 * Fire-and-forget: never throws to callers.
 */
const notifyMailTokenStatusChange = async ({
  ownerUsername,
  email,
  tokenStatus,
  previousTokenStatus,
  lastTokenError = '',
} = {}) => {
  try {
    const owner = String(ownerUsername || '').trim();
    const mail = String(email || '').trim().toLowerCase();
    const next = String(tokenStatus || '').trim().toLowerCase();
    const prev = String(previousTokenStatus || '').trim().toLowerCase();
    if (!owner || !mail || !mail.includes('@')) return { skipped: true, reason: 'bad_args' };
    if (prev === next) return { skipped: true, reason: 'unchanged' };

    const enteredInvalid = next === 'invalid' && prev !== 'invalid';
    const recoveredValid = next === 'valid' && prev === 'invalid';
    if (!enteredInvalid && !recoveredValid) {
      return { skipped: true, reason: 'not_actionable' };
    }

    const base = resolveMainApiBase();
    const secret = resolveInternalSecret();
    const url = `${base}/api/internal/mail/token_status`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Mail-Internal-Secret': secret,
        },
        body: JSON.stringify({
          owner_username: owner,
          email: mail,
          token_status: next,
          last_token_error: String(lastTokenError || '').slice(0, 1000),
        }),
        signal: controller.signal,
      });
      const text = await response.text();
      let payload = null;
      try {
        payload = text ? JSON.parse(text) : null;
      } catch {
        payload = { raw: text };
      }
      if (!response.ok || (payload && Number(payload.code) !== 0 && payload.code !== '0')) {
        logger.warn(
          `MainNotify token_status failed | status=${response.status} owner=${owner} email=${mail} next=${next} body=${text.slice(0, 300)}`,
        );
        return { ok: false, status: response.status, payload };
      }
      logger.info(
        `MainNotify token_status ok | owner=${owner} email=${mail} ${prev}->${next} updated=${payload?.updated_rows ?? '?'}`,
      );
      return { ok: true, payload };
    } finally {
      clearTimeout(timer);
    }
  } catch (error) {
    logger.warn(`MainNotify token_status error: ${error?.message || error}`);
    return { ok: false, error: String(error?.message || error) };
  }
};

const maybeNotifyAfterTokenCheck = (ownerUsername, result = {}) => {
  if (!result || !result.statusChanged) return;
  // DB 未写入成功时不要回调，避免空跑推送
  if (!Number(result.updated)) return;
  const next = String(result.tokenStatus || '').trim().toLowerCase();
  const prev = String(result.previousTokenStatus || '').trim().toLowerCase();
  if (!(next === 'invalid' && prev !== 'invalid') && !(next === 'valid' && prev === 'invalid')) {
    return;
  }
  void notifyMailTokenStatusChange({
    ownerUsername: ownerUsername || result.ownerUsername,
    email: result.email,
    tokenStatus: result.tokenStatus,
    previousTokenStatus: result.previousTokenStatus,
    lastTokenError: result.lastTokenError || '',
  });
};

module.exports = {
  notifyMailTokenStatusChange,
  maybeNotifyAfterTokenCheck,
  resolveMainApiBase,
};
