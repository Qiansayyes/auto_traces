require('dotenv').config({ path: process.env.ENV_FILE || '/etc/ms-mail-tool.env' });

const fs = require('fs');
const path = require('path');
const { refreshAccessToken } = require('../services/MailService');
const database = require('../services/DatabaseService');
const { classifyTokenError } = require('../utils/tokenError');

const toInt = (value, fallback) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, Math.floor(parsed)) : fallback;
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const splitList = (value) => String(value || '').split(',').map((item) => item.trim()).filter(Boolean);

const options = {
  ownerUsername: process.env.TOKEN_REFRESH_OWNER || process.env.MAIL_OWNER || '',
  concurrency: Math.max(1, toInt(process.env.TOKEN_REFRESH_CONCURRENCY, 5) || 5),
  limit: toInt(process.env.TOKEN_REFRESH_ACCOUNT_LIMIT, 0),
  offset: toInt(process.env.TOKEN_REFRESH_ACCOUNT_OFFSET, 0),
  delayMs: toInt(process.env.TOKEN_REFRESH_DELAY_MS, 200),
  skipCheckedWithinMinutes: toInt(process.env.TOKEN_REFRESH_SKIP_CHECKED_WITHIN_MINUTES, 60),
  includeInvalid: String(process.env.TOKEN_REFRESH_INCLUDE_INVALID || '').trim() === '1',
  socks5: process.env.TOKEN_REFRESH_SOCKS5 || process.env.AUTO_FETCH_SOCKS5 || '',
  http: process.env.TOKEN_REFRESH_HTTP || process.env.AUTO_FETCH_HTTP || '',
  source: process.env.TOKEN_REFRESH_SOURCE || 'auto_token_refresh',
  accountEmails: splitList(process.env.TOKEN_REFRESH_ACCOUNT_EMAILS || ''),
  statePath: process.env.TOKEN_REFRESH_STATE_PATH || path.join(__dirname, '..', 'data', 'auto_token_refresh_last_run.json'),
};

const summary = {
  startedAt: new Date().toISOString(),
  finishedAt: '',
  status: 'running',
  source: options.source,
  totalAccounts: 0,
  processedAccounts: 0,
  validCount: 0,
  invalidCount: 0,
  transientCount: 0,
  skippedCount: 0,
  refreshTokenUpdatedCount: 0,
  errors: [],
};

const writeState = () => {
  try {
    fs.writeFileSync(options.statePath, `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
  } catch (error) {
    console.error(`[token-refresh] failed to write state: ${error.message}`);
  }
};

const refreshAccountToken = async (account) => {
  try {
    const result = await refreshAccessToken(
      account.refresh_token,
      account.client_id,
      account.email,
      options.socks5,
      options.http,
      { forceRefresh: true },
    );

    if (!result.status) {
      const err = new Error('Token refresh succeeded but mail scope is missing');
      err.tokenClassification = {
        tokenStatus: 'invalid',
        lastTokenError: err.message,
        recoverable: false,
      };
      throw err;
    }

    const saved = database.recordTokenCheckResult({
      ownerUsername: account.owner_username,
      email: account.email,
      tokenStatus: 'valid',
      lastTokenError: '',
      expiresInSeconds: result.expires_in,
      refreshToken: result.refresh_token,
    });

    if (result.refresh_token) {
      summary.refreshTokenUpdatedCount += 1;
    }

    summary.validCount += 1;
    console.log(`[token-refresh] ok ${account.email} owner=${account.owner_username} cached=${result.fromCache ? 'yes' : 'no'}`);
    return { ok: true, saved };
  } catch (error) {
    const classification = classifyTokenError(error);
    database.recordTokenCheckResult({
      ownerUsername: account.owner_username,
      email: account.email,
      tokenStatus: classification.tokenStatus,
      lastTokenError: classification.lastTokenError,
    });

    if (classification.tokenStatus === 'invalid') {
      summary.invalidCount += 1;
    } else {
      summary.transientCount += 1;
    }

    const message = classification.lastTokenError || error?.message || String(error);
    if (summary.errors.length < 100) {
      summary.errors.push({
        email: account.email,
        ownerUsername: account.owner_username,
        tokenStatus: classification.tokenStatus,
        error: message,
      });
    }
    console.error(`[token-refresh] fail ${account.email} owner=${account.owner_username} status=${classification.tokenStatus}: ${message}`);
    return { ok: false, tokenStatus: classification.tokenStatus, error: message };
  }
};

const worker = async (id, accounts, cursor) => {
  while (true) {
    const index = cursor.next;
    cursor.next += 1;
    if (index >= accounts.length) return;

    const account = accounts[index];
    console.log(`[token-refresh] worker=${id} account=${index + 1}/${accounts.length} ${account.email}`);
    await refreshAccountToken(account);
    summary.processedAccounts += 1;
    if (options.delayMs > 0) {
      await sleep(options.delayMs);
    }
    if (summary.processedAccounts % 50 === 0) {
      writeState();
    }
  }
};

const main = async () => {
  const accounts = database.listAccountsForTokenRefresh({
    ownerUsername: options.ownerUsername,
    emails: options.accountEmails,
    limit: options.limit,
    offset: options.offset,
    skipCheckedWithinMinutes: options.skipCheckedWithinMinutes,
    includeInvalid: options.includeInvalid,
  });

  summary.totalAccounts = accounts.length;
  console.log(
    `[token-refresh] start accounts=${accounts.length} owner=${options.ownerUsername || '*'} `
    + `concurrency=${options.concurrency} skipWithinMinutes=${options.skipCheckedWithinMinutes}`,
  );
  writeState();

  if (accounts.length === 0) {
    summary.finishedAt = new Date().toISOString();
    summary.status = 'completed';
    writeState();
    console.log('[token-refresh] done: no accounts to refresh');
    return;
  }

  const cursor = { next: 0 };
  const workers = Array.from(
    { length: Math.min(options.concurrency, accounts.length) },
    (_, index) => worker(index + 1, accounts, cursor),
  );
  await Promise.all(workers);

  summary.finishedAt = new Date().toISOString();
  summary.status = summary.invalidCount > 0 || summary.transientCount > 0
    ? 'completed_with_errors'
    : 'completed';
  writeState();
  console.log(
    `[token-refresh] done status=${summary.status} processed=${summary.processedAccounts}/${summary.totalAccounts} `
    + `valid=${summary.validCount} invalid=${summary.invalidCount} transient=${summary.transientCount} `
    + `refreshUpdated=${summary.refreshTokenUpdatedCount}`,
  );
};

main().catch((error) => {
  summary.finishedAt = new Date().toISOString();
  summary.status = 'fatal';
  summary.errors.push({ error: error?.message || String(error) });
  writeState();
  console.error(`[token-refresh] fatal: ${error?.stack || error}`);
  process.exitCode = 1;
});
