require('dotenv').config({ path: process.env.ENV_FILE || '/etc/ms-mail-tool.env' });

const fs = require('fs');
const path = require('path');
const mailService = require('../services/api');
const database = require('../services/DatabaseService');

const toInt = (value, fallback) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, Math.floor(parsed)) : fallback;
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const splitList = (value) => String(value || '').split(',').map((item) => item.trim()).filter(Boolean);

const options = {
  mailboxes: splitList(process.env.AUTO_FETCH_MAILBOXES || 'INBOX,Junk'),
  top: toInt(process.env.AUTO_FETCH_TOP, 0),
  concurrency: Math.max(1, toInt(process.env.AUTO_FETCH_CONCURRENCY, 3) || 3),
  limit: toInt(process.env.AUTO_FETCH_ACCOUNT_LIMIT, 0),
  offset: toInt(process.env.AUTO_FETCH_ACCOUNT_OFFSET, 0),
  delayMs: toInt(process.env.AUTO_FETCH_DELAY_MS, 0),
  skipSuccessWithinMinutes: toInt(process.env.AUTO_FETCH_SKIP_SUCCESS_WITHIN_MINUTES, 0),
  refreshStatsEvery: Math.max(1, toInt(process.env.AUTO_FETCH_REFRESH_STATS_EVERY, 100) || 100),
  socks5: process.env.AUTO_FETCH_SOCKS5 || '',
  http: process.env.AUTO_FETCH_HTTP || '',
  source: process.env.AUTO_FETCH_SOURCE || 'auto_fetch_timer',
  statePath: process.env.AUTO_FETCH_STATE_PATH || path.join(__dirname, '..', 'data', 'auto_fetch_last_run.json'),
  accountEmails: splitList(process.env.AUTO_FETCH_ACCOUNT_EMAILS || ''),
};

const summary = {
  startedAt: new Date().toISOString(),
  finishedAt: '',
  status: 'running',
  totalAccounts: 0,
  totalTasks: 0,
  processedAccounts: 0,
  processedTasks: 0,
  skippedTasks: 0,
  mailboxAttempts: 0,
  mailboxSuccess: 0,
  mailboxFailed: 0,
  fetchedMessages: 0,
  savedMessages: 0,
  savedSenderReceipts: 0,
  senderCompanyStatsIncrementalRefreshes: 0,
  senderCompanyStatsIncrementalIdentities: 0,
  lastSenderCompanyStatsRefreshAt: '',
  mailboxes: options.mailboxes,
  requestedTop: options.top,
  concurrency: options.concurrency,
  limit: options.limit,
  offset: options.offset,
  skipSuccessWithinMinutes: options.skipSuccessWithinMinutes,
  refreshStatsEvery: options.refreshStatsEvery,
  errors: [],
};

const pendingSenderCompanyIdentities = new Set();

const flushSenderCompanyStats = (reason = 'batch') => {
  const identities = Array.from(pendingSenderCompanyIdentities);
  if (identities.length === 0) return { refreshed: 0 };
  pendingSenderCompanyIdentities.clear();
  const refreshed = database.refreshSenderCompanyStats(identities);
  summary.senderCompanyStatsIncrementalRefreshes += 1;
  summary.senderCompanyStatsIncrementalIdentities += refreshed.refreshed || 0;
  summary.lastSenderCompanyStatsRefreshAt = new Date().toISOString();
  console.log(`[auto-fetch] refreshed sender company stats reason=${reason} identities=${identities.length}`);
  return refreshed;
};

const writeState = () => {
  try {
    fs.writeFileSync(options.statePath, `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
  } catch (error) {
    console.error(`[auto-fetch] failed to write state: ${error.message}`);
  }
};

const fetchMailbox = async (account, mailbox) => {
  summary.mailboxAttempts += 1;

  try {
    const emails = await mailService.mail_all(
      account.refresh_token,
      account.client_id,
      account.email,
      mailbox,
      options.socks5,
      options.http,
      options.top,
    );
    const items = Array.isArray(emails) ? emails : [];
    const saved = database.saveFetchedEmails({
      accountEmail: account.email,
      mailbox,
      emails: items,
      source: options.source,
      requestedTop: options.top,
      refreshStats: false,
    });
    for (const identity of saved.affected_company_identities || []) {
      pendingSenderCompanyIdentities.add(identity);
    }

    summary.mailboxSuccess += 1;
    summary.fetchedMessages += items.length;
    summary.savedMessages += saved.saved_count || 0;
    summary.savedSenderReceipts += saved.saved_count || 0;
    console.log(`[auto-fetch] ok ${account.email} ${mailbox}: fetched=${items.length} savedSenderReceipts=${saved.saved_count || 0}`);
  } catch (error) {
    const message = error?.message || String(error);
    database.recordFetchFailure({
      accountEmail: account.email,
      mailbox,
      source: options.source,
      requestedTop: options.top,
      error: message,
    });
    summary.mailboxFailed += 1;
    if (summary.errors.length < 100) {
      summary.errors.push({
        email: account.email,
        mailbox,
        error: message,
      });
    }
    console.error(`[auto-fetch] fail ${account.email} ${mailbox}: ${message}`);
  }
};

const markAccountTaskDone = (account, accountProgress) => {
  const current = accountProgress.get(account.email) || 0;
  const next = current + 1;
  accountProgress.set(account.email, next);
  if (next >= options.mailboxes.length) {
    summary.processedAccounts += 1;
  }
};

const worker = async (id, tasks, cursor, accountProgress) => {
  while (true) {
    const index = cursor.next;
    cursor.next += 1;
    if (index >= tasks.length) return;

    const task = tasks[index];
    console.log(`[auto-fetch] worker=${id} task=${index + 1}/${tasks.length} ${task.account.email} ${task.mailbox}`);
    if (task.skip) {
      summary.skippedTasks += 1;
      summary.processedTasks += 1;
      markAccountTaskDone(task.account, accountProgress);
      continue;
    }

    await fetchMailbox(task.account, task.mailbox);
    summary.processedTasks += 1;
    markAccountTaskDone(task.account, accountProgress);
    if (options.refreshStatsEvery > 0 && summary.processedTasks % options.refreshStatsEvery === 0) {
      flushSenderCompanyStats(`processed_${summary.processedTasks}`);
    }
    if (options.delayMs > 0) {
      await sleep(options.delayMs);
    }
    if (summary.processedTasks % 100 === 0) {
      writeState();
    }
  }
};

const main = async () => {
  if (options.mailboxes.length === 0) {
    throw new Error('AUTO_FETCH_MAILBOXES is empty');
  }

  const totalFetchable = database.getFetchableAccountCount({ emails: options.accountEmails });
  const accounts = database.listAccountsForFetch({
    emails: options.accountEmails,
    limit: options.limit,
    offset: options.offset,
  });
  const since = options.skipSuccessWithinMinutes > 0
    ? new Date(Date.now() - options.skipSuccessWithinMinutes * 60 * 1000).toISOString()
    : '';
  const recentSuccessKeys = since
    ? new Set(database.getRecentSuccessfulFetchKeys({
      source: options.source,
      since,
      mailboxes: options.mailboxes,
    }).map((item) => item.key))
    : new Set();
  const tasks = [];
  for (const account of accounts) {
    for (const mailbox of options.mailboxes) {
      const key = `${account.email}|${mailbox}`;
      tasks.push({
        account,
        mailbox,
        skip: recentSuccessKeys.has(key),
      });
    }
  }
  summary.totalAccounts = accounts.length;
  summary.totalTasks = tasks.length;
  summary.totalFetchableAccounts = totalFetchable;
  console.log(`[auto-fetch] start accounts=${accounts.length}/${totalFetchable} tasks=${tasks.length} mailboxes=${options.mailboxes.join(',')} top=${options.top} concurrency=${options.concurrency} skipWithinMinutes=${options.skipSuccessWithinMinutes}`);
  writeState();

  const cursor = { next: 0 };
  const accountProgress = new Map();
  const workers = Array.from(
    { length: Math.min(options.concurrency, Math.max(1, tasks.length)) },
    (_, index) => worker(index + 1, tasks, cursor, accountProgress),
  );
  await Promise.all(workers);

  flushSenderCompanyStats('final_batch');
  const refreshed = database.refreshAllSenderCompanyStats();
  summary.senderCompanyStatsRefreshed = refreshed.refreshed;

  summary.finishedAt = new Date().toISOString();
  summary.status = summary.mailboxFailed > 0 ? 'completed_with_errors' : 'completed';
  writeState();
  console.log(`[auto-fetch] done status=${summary.status} accounts=${summary.processedAccounts} tasks=${summary.processedTasks}/${summary.totalTasks} skipped=${summary.skippedTasks} success=${summary.mailboxSuccess} failed=${summary.mailboxFailed} fetched=${summary.fetchedMessages} saved=${summary.savedMessages}`);
};

main().catch((error) => {
  summary.finishedAt = new Date().toISOString();
  summary.status = 'fatal';
  summary.errors.push({ error: error?.message || String(error) });
  writeState();
  console.error(`[auto-fetch] fatal: ${error?.stack || error}`);
  process.exitCode = 1;
});
