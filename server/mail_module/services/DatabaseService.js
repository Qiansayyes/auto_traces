const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { DatabaseSync } = require('node:sqlite');
const logger = require('../utils/logger');
const { addMailLinks, getPrimaryMailLink } = require('../utils/mailLinks');
const {
  normalizeOwner,
  requireOwner,
  buildRecordKey,
  scopeProjectKey,
  unscopeProjectKey,
} = require('../utils/ownerContext');

const DATA_DIR = path.join(__dirname, '..', 'data');
const MAIL_DB_PATH = path.join(DATA_DIR, 'mail.sqlite');
const ACCOUNTS_DB_PATH = path.join(DATA_DIR, 'accounts.sqlite');

if (!fs.existsSync(DATA_DIR)) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

const mailDb = new DatabaseSync(MAIL_DB_PATH);
const accountsDb = new DatabaseSync(ACCOUNTS_DB_PATH);

const nowIso = () => new Date().toISOString();
const normalizeEmail = (value = '') => String(value || '').trim().toLowerCase();
const dbText = (value = '') => (value === undefined || value === null ? '' : String(value));
const safeJson = (value) => JSON.stringify(value ?? null);
const sha256 = (value) => crypto.createHash('sha256').update(String(value || '')).digest('hex');
const DEFAULT_PREVIEW_LIMIT = 100;
const DEFAULT_ITEM_LIMIT = 200;
const DEFAULT_SENDER_PAGE_SIZE = 20;
const MAX_SENDER_PAGE_SIZE = 100;
const DEFAULT_SENDER_OPTION_LIMIT = 5000;

const normalizeDateText = (value = '') => {
  if (!value) return '';
  if (value instanceof Date) {
    const time = value.getTime();
    return Number.isNaN(time) ? '' : value.toISOString();
  }
  const raw = dbText(value).trim();
  if (!raw) return '';
  const time = Date.parse(raw);
  return Number.isNaN(time) ? raw : new Date(time).toISOString();
};

const normalizeSenderSort = (value = 'count') => {
  const sort = String(value || '').trim().toLowerCase();
  return ['count', 'latest', 'updated'].includes(sort) ? sort : 'count';
};

const senderStatsOrderSql = (sort = 'count') => {
  const normalizedSort = normalizeSenderSort(sort);
  if (normalizedSort === 'updated') {
    return 'ORDER BY updated_at DESC, latest_date DESC, item_count DESC, sender ASC';
  }
  if (normalizedSort === 'latest') {
    return 'ORDER BY latest_date DESC, updated_at DESC, item_count DESC, sender ASC';
  }
  return 'ORDER BY item_count DESC, latest_date DESC, updated_at DESC, sender ASC';
};

const toPositiveInt = (value, fallback, max = Number.MAX_SAFE_INTEGER) => {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) return fallback;
  return Math.min(max, Math.floor(parsed));
};

const parseBoolean = (value) => (
  value === true
  || value === 1
  || value === '1'
  || String(value || '').toLowerCase() === 'true'
  || String(value || '').toLowerCase() === 'yes'
);

const configureDb = (db) => {
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;
    PRAGMA busy_timeout = 5000;
  `);
};

configureDb(mailDb);
configureDb(accountsDb);

try {
  const attachedPath = ACCOUNTS_DB_PATH.replace(/'/g, "''");
  mailDb.exec(`ATTACH DATABASE '${attachedPath}' AS accounts_db`);
} catch (error) {
  if (!String(error?.message || '').includes('already in use')) {
    logger.error('Failed to attach accounts database to mail database', error);
  }
}

const runTransaction = (db, work) => {
  db.exec('BEGIN');
  try {
    const result = work();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    try {
      db.exec('ROLLBACK');
    } catch (rollbackError) {
      logger.error('Failed to rollback database transaction', rollbackError);
    }
    throw error;
  }
};

const runImmediateTransaction = (db, work) => {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = work();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    try {
      db.exec('ROLLBACK');
    } catch (rollbackError) {
      logger.error('Failed to rollback database transaction', rollbackError);
    }
    throw error;
  }
};

const accountTableColumns = accountsDb
  .prepare(`PRAGMA table_info(account_records)`)
  .all()
  .map((column) => column.name);

if (accountTableColumns.length > 0 && !accountTableColumns.includes('record_key')) {
  accountsDb.exec(`ALTER TABLE account_records RENAME TO account_records_legacy_email_primary`);
}

accountsDb.exec(`
  CREATE TABLE IF NOT EXISTS account_records (
    record_key TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    password TEXT NOT NULL DEFAULT '',
    client_id TEXT NOT NULL DEFAULT '',
    refresh_token TEXT NOT NULL DEFAULT '',
    raw_line TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'active',
    imported_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS account_import_batches (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL DEFAULT '',
    total_lines INTEGER NOT NULL DEFAULT 0,
    valid_records INTEGER NOT NULL DEFAULT 0,
    inserted_or_updated INTEGER NOT NULL DEFAULT 0,
    skipped_records INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
  );

  CREATE INDEX IF NOT EXISTS idx_account_records_email ON account_records(email);
  CREATE INDEX IF NOT EXISTS idx_account_records_status ON account_records(status);
  CREATE INDEX IF NOT EXISTS idx_account_records_status_email_updated ON account_records(status, email, updated_at);
  CREATE INDEX IF NOT EXISTS idx_account_records_updated_at ON account_records(updated_at);
`);

mailDb.exec(`
  CREATE TABLE IF NOT EXISTS mail_fetch_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_email TEXT NOT NULL,
    mailbox TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT '',
    requested_top INTEGER NOT NULL DEFAULT 0,
    fetched_count INTEGER NOT NULL DEFAULT 0,
    saved_count INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'success',
    error TEXT NOT NULL DEFAULT '',
    started_at TEXT NOT NULL,
    finished_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS sender_receipts (
    account_email TEXT NOT NULL,
    sender_identity TEXT NOT NULL,
    sender_raw TEXT NOT NULL DEFAULT '',
    sender_label TEXT NOT NULL DEFAULT '',
    sender_address TEXT NOT NULL DEFAULT '',
    sender_company_identity TEXT NOT NULL DEFAULT '',
    sender_company_label TEXT NOT NULL DEFAULT '',
    first_received_at TEXT NOT NULL DEFAULT '',
    latest_received_at TEXT NOT NULL DEFAULT '',
    first_mailbox TEXT NOT NULL DEFAULT '',
    latest_mailbox TEXT NOT NULL DEFAULT '',
    message_count INTEGER NOT NULL DEFAULT 0,
    first_fetched_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY(account_email, sender_identity)
  );

  CREATE TABLE IF NOT EXISTS sender_company_stats (
    sender_company_identity TEXT PRIMARY KEY,
    sender_company_label TEXT NOT NULL DEFAULT '',
    item_count INTEGER NOT NULL DEFAULT 0,
    received_recipient_count INTEGER NOT NULL DEFAULT 0,
    latest_date TEXT NOT NULL DEFAULT '',
    latest_verification_code TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS sender_company_recipients (
    sender_company_identity TEXT NOT NULL,
    account_email TEXT NOT NULL,
    count INTEGER NOT NULL DEFAULT 0,
    latest_date TEXT NOT NULL DEFAULT '',
    PRIMARY KEY(sender_company_identity, account_email)
  );

  CREATE TABLE IF NOT EXISTS project_records (
    project_key TEXT PRIMARY KEY,
    project_name TEXT NOT NULL DEFAULT '',
    sender_company_identity TEXT NOT NULL DEFAULT '',
    sender_company_label TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS project_sender_identities (
    project_key TEXT NOT NULL,
    sender_company_identity TEXT NOT NULL,
    sender_company_label TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY(project_key, sender_company_identity)
  );

  CREATE TABLE IF NOT EXISTS project_account_marks (
    project_key TEXT NOT NULL,
    account_email TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'assigned',
    assigned_count INTEGER NOT NULL DEFAULT 0,
    latest_code TEXT NOT NULL DEFAULT '',
    latest_sender TEXT NOT NULL DEFAULT '',
    latest_mailbox TEXT NOT NULL DEFAULT '',
    latest_received_at TEXT NOT NULL DEFAULT '',
    last_error TEXT NOT NULL DEFAULT '',
    first_assigned_at TEXT NOT NULL DEFAULT '',
    last_assigned_at TEXT NOT NULL DEFAULT '',
    last_checked_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL,
    PRIMARY KEY(project_key, account_email)
  );

  CREATE INDEX IF NOT EXISTS idx_sender_receipts_company ON sender_receipts(sender_company_identity);
  CREATE INDEX IF NOT EXISTS idx_sender_receipts_company_account ON sender_receipts(sender_company_identity, account_email);
  CREATE INDEX IF NOT EXISTS idx_sender_receipts_company_latest ON sender_receipts(sender_company_identity, latest_received_at);
  CREATE INDEX IF NOT EXISTS idx_sender_receipts_account ON sender_receipts(account_email);
  CREATE INDEX IF NOT EXISTS idx_sender_company_stats_count ON sender_company_stats(item_count);
  CREATE INDEX IF NOT EXISTS idx_sender_company_recipients_identity_latest ON sender_company_recipients(sender_company_identity, latest_date);
  CREATE INDEX IF NOT EXISTS idx_project_account_marks_status ON project_account_marks(project_key, status);
  CREATE INDEX IF NOT EXISTS idx_project_account_marks_status_assigned ON project_account_marks(project_key, status, last_assigned_at);
  CREATE INDEX IF NOT EXISTS idx_project_account_marks_email ON project_account_marks(account_email);
  CREATE INDEX IF NOT EXISTS idx_project_sender_identities_identity ON project_sender_identities(sender_company_identity);
`);

const ensureColumn = (db, table, column, definition) => {
  const columns = db.prepare(`PRAGMA table_info(${table})`).all().map((row) => row.name);
  if (!columns.includes(column)) {
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
  }
};

ensureColumn(accountsDb, 'account_records', 'owner_username', "TEXT NOT NULL DEFAULT ''");
ensureColumn(accountsDb, 'account_import_batches', 'owner_username', "TEXT NOT NULL DEFAULT ''");
ensureColumn(mailDb, 'mail_fetch_runs', 'owner_username', "TEXT NOT NULL DEFAULT ''");
ensureColumn(mailDb, 'project_records', 'owner_username', "TEXT NOT NULL DEFAULT ''");

const decorateProjectRecord = (row) => {
  if (!row) return null;
  const senderRows = getProjectSenderRows(row.project_key);
  return {
    projectKey: unscopeProjectKey(row.project_key),
    storageProjectKey: row.project_key,
    projectName: row.project_name,
    senderCompanyIdentity: row.sender_company_identity,
    senderCompanyLabel: row.sender_company_label,
    senderCompanyIdentities: senderRows.length > 0
      ? senderRows.map((item) => item.sender_company_identity)
      : toList(row.sender_company_identity),
    senderCompanyLabels: senderRows.length > 0
      ? senderRows.map((item) => item.sender_company_label)
      : toList(row.sender_company_label),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
};

const resolveOwnerOptions = (options = {}) => {
  const ownerUsername = requireOwner(options.ownerUsername);
  return { ...options, ownerUsername };
};

const resolveScopedProjectOptions = (options = {}) => {
  const resolved = resolveOwnerOptions(options);
  const userProjectKey = normalizeProjectKey(
    resolved.projectKey || resolved.project || resolved.key || resolved.id,
  );
  return {
    ...resolved,
    userProjectKey,
    projectKey: scopeProjectKey(resolved.ownerUsername, userProjectKey),
  };
};

const ensureMailColumn = (name, definition) => {
  const table = mailDb.prepare(`
    SELECT name
    FROM sqlite_master
    WHERE type = 'table' AND name = 'received_messages'
  `).get();
  if (!table) return;
  const columns = mailDb.prepare(`PRAGMA table_info(received_messages)`).all().map((column) => column.name);
  if (!columns.includes(name)) {
    mailDb.exec(`ALTER TABLE received_messages ADD COLUMN ${name} ${definition}`);
  }
};

ensureMailColumn('sender_company_identity', `TEXT NOT NULL DEFAULT ''`);
ensureMailColumn('sender_company_label', `TEXT NOT NULL DEFAULT ''`);

if (mailDb.prepare(`SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'received_messages'`).get()) {
  mailDb.exec(`
    CREATE INDEX IF NOT EXISTS idx_received_messages_sender_company_identity ON received_messages(sender_company_identity);
    CREATE INDEX IF NOT EXISTS idx_received_messages_sender_company_account ON received_messages(sender_company_identity, account_email);
    CREATE INDEX IF NOT EXISTS idx_received_messages_sender_company_received ON received_messages(sender_company_identity, received_at);
  `);
}

const parseAccountLine = (line, splitSymbol = '----') => {
  const rawLine = String(line || '').trim();
  if (!rawLine) return null;

  const parts = rawLine.split(splitSymbol);
  const email = normalizeEmail(parts[0]);
  if (!email || !email.includes('@')) return null;

  return {
    email,
    password: parts[1] || '',
    client_id: parts[2] || '',
    refresh_token: parts.slice(3).join(splitSymbol) || '',
    raw_line: rawLine,
  };
};

const toAccountRecord = (record, splitSymbol = '----') => {
  if (typeof record === 'string') {
    return parseAccountLine(record, splitSymbol);
  }

  const email = normalizeEmail(record?.email);
  if (!email || !email.includes('@')) return null;

  const password = String(record?.password || '');
  const client_id = String(record?.client_id || '');
  const refresh_token = String(record?.refresh_token || '');

  return {
    email,
    password,
    client_id,
    refresh_token,
    raw_line: [email, password, client_id, refresh_token].join(splitSymbol),
  };
};

const importAccounts = (records, options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  const splitSymbol = options.splitSymbol || '----';
  const source = options.source || 'manual';
  const rows = Array.isArray(records) ? records : [];
  const parsed = [];
  let skipped = 0;

  for (const row of rows) {
    const record = toAccountRecord(row, splitSymbol);
    if (record) parsed.push(record);
    else skipped += 1;
  }

  const timestamp = nowIso();
  const upsert = accountsDb.prepare(`
    INSERT INTO account_records (
      record_key, email, password, client_id, refresh_token, raw_line, source, status, imported_at, updated_at, owner_username
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?)
    ON CONFLICT(record_key) DO UPDATE SET
      email = excluded.email,
      password = excluded.password,
      client_id = excluded.client_id,
      refresh_token = excluded.refresh_token,
      raw_line = excluded.raw_line,
      source = excluded.source,
      status = 'active',
      updated_at = excluded.updated_at,
      owner_username = excluded.owner_username
  `);

  runTransaction(accountsDb, () => {
    for (const item of parsed) {
      upsert.run(
        buildRecordKey(ownerUsername, item.raw_line),
        item.email,
        item.password,
        item.client_id,
        item.refresh_token,
        item.raw_line,
        source,
        timestamp,
        timestamp,
        ownerUsername,
      );
    }
  });

  const insertBatch = accountsDb.prepare(`
    INSERT INTO account_import_batches (
      source, total_lines, valid_records, inserted_or_updated, skipped_records, created_at, owner_username
    )
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `);
  insertBatch.run(source, rows.length, parsed.length, parsed.length, skipped, timestamp, ownerUsername);

  return {
    source,
    total_lines: rows.length,
    valid_records: parsed.length,
    inserted_or_updated: parsed.length,
    skipped_records: skipped,
    database: ACCOUNTS_DB_PATH,
  };
};

const listAccountEmails = (options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  return accountsDb
    .prepare(`SELECT DISTINCT email FROM account_records WHERE status = 'active' AND owner_username = ? ORDER BY email`)
    .all(ownerUsername)
    .map((row) => row.email);
};

const getProjectKeysForSenderIdentities = (senderIdentities = []) => {
  const identities = [...new Set((Array.isArray(senderIdentities) ? senderIdentities : [])
    .map((item) => String(item || '').trim())
    .filter(Boolean))];
  if (identities.length === 0) return [];

  return mailDb.prepare(`
    SELECT DISTINCT project_key
    FROM (
      SELECT project_key
      FROM project_sender_identities
      WHERE sender_company_identity IN (${identities.map(() => '?').join(', ')})
      UNION
      SELECT project_key
      FROM project_records
      WHERE sender_company_identity IN (${identities.map(() => '?').join(', ')})
    )
    WHERE project_key <> ''
  `).all(...identities, ...identities).map((row) => row.project_key).filter(Boolean);
};

const normalizeAccountProjectUsage = (value = '', fallback = 'all') => {
  const raw = String(value || '').trim().toLowerCase();
  if (['done', 'used', 'received', 'completed', 'has_project', 'project_done', '做过', '已做'].includes(raw)) {
    return 'done';
  }
  if (['not_done', 'not-done', 'unused', 'new', 'available', 'no_project', 'project_unused', '未做', '没做', '未做过'].includes(raw)) {
    return 'not_done';
  }
  if (['all', 'any', '全部'].includes(raw)) {
    return 'all';
  }
  return fallback;
};

const listAccounts = (options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  const page = Math.max(1, Number(options.page) || 1);
  const pageSize = Math.min(500, Math.max(1, Number(options.pageSize) || 50));
  const offset = (page - 1) * pageSize;
  const search = String(options.search || '').trim().toLowerCase();
  const rawProjectUsage = String(
    options.projectUsage
    || options.projectUsageFilter
    || options.projectStatus
    || options.projectStatusFilter
    || options.doneFilter
    || '',
  ).trim();
  const directSenderIdentities = [
    ...toList(options.excludeSenderCompanyIdentities || options.excludeSenderIdentities || options.senderCompanyIdentities || options.sender_company_identities),
    ...toList(options.excludeSenderCompanyIdentity || options.excludeSenderIdentity || options.senderCompanyIdentity || options.sender_company_identity),
  ];
  const excludeSenderIdentities = [...new Set(directSenderIdentities.map((item) => String(item || '').trim()).filter(Boolean))];
  const excludeProjectKey = normalizeProjectKey(
    options.excludeProject
    || options.excludeDoneProject
    || options.unusedProject
    || options.notDoneProject
    || '',
  );
  const project = excludeSenderIdentities.length === 0 && excludeProjectKey
    ? getProjectOrKnownSenderProject(excludeProjectKey, { ownerUsername })
    : null;
  const projectSenderIdentities = project ? getProjectSenderIdentities(project) : [];
  const senderIdentities = excludeSenderIdentities.length > 0 ? excludeSenderIdentities : projectSenderIdentities;
  const projectMarkKeys = project
    ? [project.projectKey]
    : getProjectKeysForSenderIdentities(senderIdentities);
  const scopedProjectFilter = senderIdentities.length > 0 || Boolean(project);
  const legacyUnusedFilter = !rawProjectUsage && (excludeSenderIdentities.length > 0 || Boolean(excludeProjectKey));
  const projectUsage = normalizeAccountProjectUsage(rawProjectUsage, legacyUnusedFilter ? 'not_done' : 'all');

  if (excludeProjectKey && excludeSenderIdentities.length === 0 && !project) {
    return {
      records: [],
      page,
      pageSize,
      total: 0,
      database: ACCOUNTS_DB_PATH,
      projectFilter: {
        mode: projectUsage,
        projectKey: excludeProjectKey,
        applied: false,
        reason: 'project_not_found',
      },
    };
  }

  const whereParts = [`ar.status = 'active'`, `ar.owner_username = ?`];
  const params = [ownerUsername];

  if (search) {
    whereParts.push(`lower(ar.email) LIKE ?`);
    params.push(`%${search}%`);
  }

  const scopedProjectMarkExistsSql = projectMarkKeys.length > 0
    ? `EXISTS (
        SELECT 1
        FROM project_account_marks pam
        WHERE pam.account_email = ar.email
          AND pam.project_key IN (${projectMarkKeys.map(() => '?').join(', ')})
        LIMIT 1
      )`
    : '';
  const globalProjectMarkExistsSql = `EXISTS (
        SELECT 1
        FROM project_account_marks pam
        WHERE pam.account_email = ar.email
        LIMIT 1
      )`;
  const projectMarkExistsSql = scopedProjectFilter ? scopedProjectMarkExistsSql : globalProjectMarkExistsSql;
  const projectMarkParams = scopedProjectFilter ? projectMarkKeys : [];
  const senderReceivedExistsSql = senderIdentities.length > 0
    ? `EXISTS (
        SELECT 1
        FROM sender_company_recipients scr
        WHERE scr.sender_company_identity IN (${senderIdentities.map(() => '?').join(', ')})
          AND scr.account_email = ar.email
        LIMIT 1
      )`
    : '';

  if (projectUsage === 'not_done') {
    if (projectMarkExistsSql) {
      whereParts.push(`NOT ${projectMarkExistsSql}`);
      params.push(...projectMarkParams);
    }

    if (senderReceivedExistsSql) {
      whereParts.push(`NOT ${senderReceivedExistsSql}`);
      params.push(...senderIdentities);
    }
  }

  if (projectUsage === 'done') {
    const doneParts = [];
    const doneParams = [];
    if (projectMarkExistsSql) {
      doneParts.push(projectMarkExistsSql);
      doneParams.push(...projectMarkParams);
    }
    if (senderReceivedExistsSql) {
      doneParts.push(senderReceivedExistsSql);
      doneParams.push(...senderIdentities);
    }
    if (doneParts.length > 0) {
      whereParts.push(`(${doneParts.join(' OR ')})`);
      params.push(...doneParams);
    } else {
      whereParts.push(globalProjectMarkExistsSql);
    }
  }

  const where = `WHERE ${whereParts.join(' AND ')}`;
  const total = mailDb.prepare(`
    SELECT COUNT(*) AS total
    FROM accounts_db.account_records ar
    ${where}
  `).get(...params)?.total || 0;
  const records = mailDb.prepare(`
    SELECT ar.record_key, ar.email, ar.password, ar.client_id, ar.refresh_token, ar.source, ar.updated_at
    FROM accounts_db.account_records ar
    ${where}
    ORDER BY ar.email ASC, ar.updated_at DESC
    LIMIT ? OFFSET ?
  `).all(...params, pageSize, offset);

  return {
    records,
    page,
    pageSize,
    total,
    database: ACCOUNTS_DB_PATH,
    projectFilter: senderIdentities.length > 0 || project || projectUsage !== 'all' ? {
      mode: projectUsage,
      projectKey: project?.projectKey || '',
      projectName: project?.projectName || '',
      senderCompanyIdentities: senderIdentities,
      projectMarkKeys,
      applied: true,
    } : null,
  };
};

const listAccountsForFetch = (options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  const limit = Math.max(0, Number(options.limit) || 0);
  const offset = Math.max(0, Number(options.offset) || 0);
  const emailFilters = Array.isArray(options.emails)
    ? options.emails.map(normalizeEmail).filter(Boolean)
    : [];
  const whereParts = [`status = 'active'`, `owner_username = ?`, `email <> ''`, `client_id <> ''`, `refresh_token <> ''`];
  const params = [ownerUsername];

  if (emailFilters.length > 0) {
    whereParts.push(`email IN (${emailFilters.map(() => '?').join(', ')})`);
    params.push(...emailFilters);
  }

  const where = `WHERE ${whereParts.join(' AND ')}`;
  const sql = `
    SELECT record_key, email, password, client_id, refresh_token, source, updated_at
    FROM account_records
    ${where}
    ORDER BY email ASC, updated_at DESC
    ${limit > 0 ? 'LIMIT ? OFFSET ?' : ''}
  `;
  const finalParams = limit > 0 ? [...params, limit, offset] : params;

  return accountsDb.prepare(sql).all(...finalParams);
};

const getFetchableAccountCount = (options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  const emailFilters = Array.isArray(options.emails)
    ? options.emails.map(normalizeEmail).filter(Boolean)
    : [];
  const whereParts = [`status = 'active'`, `owner_username = ?`, `email <> ''`, `client_id <> ''`, `refresh_token <> ''`];
  const params = [ownerUsername];

  if (emailFilters.length > 0) {
    whereParts.push(`email IN (${emailFilters.map(() => '?').join(', ')})`);
    params.push(...emailFilters);
  }

  const row = accountsDb
    .prepare(`SELECT COUNT(*) AS total FROM account_records WHERE ${whereParts.join(' AND ')}`)
    .get(...params);

  return row?.total || 0;
};

const getRecentSuccessfulFetchKeys = (options = {}) => {
  const source = String(options.source || '').trim();
  const since = String(options.since || '').trim();
  const mailboxes = Array.isArray(options.mailboxes)
    ? options.mailboxes.map((mailbox) => String(mailbox || '').trim()).filter(Boolean)
    : [];
  const whereParts = [`status = 'success'`];
  const params = [];

  if (source) {
    whereParts.push(`source = ?`);
    params.push(source);
  }

  if (since) {
    whereParts.push(`finished_at >= ?`);
    params.push(since);
  }

  if (mailboxes.length > 0) {
    whereParts.push(`mailbox IN (${mailboxes.map(() => '?').join(', ')})`);
    params.push(...mailboxes);
  }

  return mailDb.prepare(`
    SELECT account_email, mailbox, MAX(finished_at) AS finished_at
    FROM mail_fetch_runs
    WHERE ${whereParts.join(' AND ')}
    GROUP BY account_email, mailbox
  `).all(...params).map((row) => ({
    account_email: normalizeEmail(row.account_email),
    mailbox: row.mailbox,
    key: `${normalizeEmail(row.account_email)}|${row.mailbox}`,
    finished_at: row.finished_at,
  }));
};

const getAccountStats = (options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  const row = accountsDb.prepare(`
    SELECT COUNT(*) AS total, MAX(updated_at) AS last_updated_at
    FROM account_records
    WHERE status = 'active' AND owner_username = ?
  `).get(ownerUsername);

  return {
    total: row?.total || 0,
    last_updated_at: row?.last_updated_at || '',
    database: ACCOUNTS_DB_PATH,
  };
};

const getActiveAccountCount = (options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  return accountsDb.prepare(`
    SELECT COUNT(DISTINCT email) AS total
    FROM account_records
    WHERE status = 'active' AND owner_username = ?
  `).get(ownerUsername)?.total || 0;
};

const extractSenderLabel = (send = '') => {
  const value = String(send || '').trim();
  if (!value) return '';

  const quotedMatch = value.match(/"([^"]+)"/);
  if (quotedMatch?.[1]) return quotedMatch[1].trim();

  const displayMatch = value.match(/^([^<]+)</);
  if (displayMatch?.[1]) return displayMatch[1].replace(/"/g, '').trim();

  const addressMatch = value.match(/<([^>]+)>/);
  if (addressMatch?.[1]) return addressMatch[1].trim();

  return value;
};

const extractSenderAddress = (send = '') => {
  const value = String(send || '').trim();
  if (!value) return '';

  const addressMatch = value.match(/<([^>]+)>/);
  if (addressMatch?.[1]) return normalizeEmail(addressMatch[1]);

  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return normalizeEmail(value);

  return '';
};

const normalizeSenderLabel = (value = '') => String(value || '').replace(/\s+/g, ' ').trim().toLowerCase();

const titleCaseCompany = (value = '') => {
  const normalized = String(value || '')
    .replace(/[-_]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();
  if (!normalized) return '';
  return normalized.split(' ').map((word) => (word ? `${word[0].toUpperCase()}${word.slice(1)}` : '')).join(' ');
};

const getRootDomain = (address = '') => {
  const host = String(address || '').toLowerCase().split('@').pop().replace(/[>\s]+/g, '').trim();
  if (!host || !host.includes('.')) return '';

  const parts = host.split('.').filter(Boolean);
  if (parts.length < 2) return host;

  const twoLevelTlds = new Set([
    'co.uk', 'org.uk', 'ac.uk',
    'com.au', 'net.au', 'org.au',
    'com.br', 'com.cn', 'net.cn', 'org.cn',
    'co.jp', 'co.kr', 'com.hk', 'com.sg', 'com.tw',
    'com.mx', 'com.tr', 'com.sa', 'co.in',
  ]);
  const lastTwo = parts.slice(-2).join('.');
  const lastThree = parts.slice(-3).join('.');
  if (parts.length >= 3 && twoLevelTlds.has(lastTwo)) return lastThree;
  return lastTwo;
};

const companyAliases = [
  { identity: 'company:openai.com', label: 'OpenAI', patterns: ['openai', 'openai.com', 'tm.openai.com'] },
  { identity: 'company:microsoft.com', label: 'Microsoft', patterns: ['microsoft', 'microsoft.com', 'accountprotection.microsoft.com', 'account.microsoft.com', 'outlook.com'] },
  { identity: 'company:nomad.com', label: 'Nomad', patterns: ['nomad', 'getnomad', 'nomad esim', 'nomad.com', 'getnomad.app'] },
  { identity: 'company:google.com', label: 'Google', patterns: ['google', 'google.com', 'gmail.com'] },
  { identity: 'company:github.com', label: 'GitHub', patterns: ['github', 'github.com'] },
  { identity: 'company:apple.com', label: 'Apple', patterns: ['apple', 'apple.com'] },
  { identity: 'company:amazon.com', label: 'Amazon', patterns: ['amazon', 'amazon.com'] },
  { identity: 'company:paypal.com', label: 'PayPal', patterns: ['paypal', 'paypal.com'] },
  { identity: 'company:facebook.com', label: 'Meta', patterns: ['facebook', 'meta', 'facebookmail.com', 'facebook.com', 'instagram.com'] },
  { identity: 'company:discord.com', label: 'Discord', patterns: ['discord', 'discord.com'] },
  { identity: 'company:telegram.org', label: 'Telegram', patterns: ['telegram', 'telegram.org'] },
  { identity: 'company:x.com', label: 'X', patterns: ['twitter', 'x.com', 'twitter.com'] },
  { identity: 'company:tiktok.com', label: 'TikTok', patterns: ['tiktok', 'tiktok.com'] },
  { identity: 'company:linkedin.com', label: 'LinkedIn', patterns: ['linkedin', 'linkedin.com'] },
  { identity: 'company:cloudflare.com', label: 'Cloudflare', patterns: ['cloudflare', 'cloudflare.com'] },
  { identity: 'company:binance.com', label: 'Binance', patterns: ['binance', 'binance.com'] },
  { identity: 'company:coinbase.com', label: 'Coinbase', patterns: ['coinbase', 'coinbase.com'] },
  { identity: 'company:base44.com', label: 'Base44', patterns: ['base44', 'base44.com'] },
];

const getSenderCompany = (send = '') => {
  const label = extractSenderLabel(send);
  const address = extractSenderAddress(send);
  const normalizedLabel = normalizeSenderLabel(label);
  const normalizedAddress = normalizeEmail(address);
  const domain = getRootDomain(normalizedAddress);
  const haystack = [normalizedLabel, normalizedAddress, domain].filter(Boolean).join(' ');

  for (const rule of companyAliases) {
    if (rule.patterns.some((pattern) => haystack.includes(pattern))) {
      return { identity: rule.identity, label: rule.label };
    }
  }

  if (domain) {
    const companyName = domain.split('.')[0] || domain;
    return {
      identity: `company:${domain}`,
      label: titleCaseCompany(companyName),
    };
  }

  const senderIdentity = normalizeSenderIdentity(send);
  return {
    identity: senderIdentity ? `sender:${senderIdentity}` : '',
    label: label || senderIdentity,
  };
};

const tableExists = (name) => Boolean(mailDb.prepare(`
  SELECT name
  FROM sqlite_master
  WHERE type = 'table' AND name = ?
`).get(name));

const backfillSenderCompanies = (limit = 0) => {
  if (!tableExists('received_messages')) {
    return { updated: 0, remaining: 0 };
  }

  const rowLimit = Math.max(0, Number(limit) || 0);
  const rows = mailDb.prepare(`
    SELECT message_key, sender_raw
    FROM received_messages
    WHERE sender_company_identity = '' OR sender_company_label = ''
    ORDER BY fetched_at DESC
    ${rowLimit > 0 ? 'LIMIT ?' : ''}
  `).all(...(rowLimit > 0 ? [rowLimit] : []));

  if (rows.length === 0) {
    return { updated: 0, remaining: 0 };
  }

  const update = mailDb.prepare(`
    UPDATE received_messages
    SET sender_company_identity = ?, sender_company_label = ?
    WHERE message_key = ?
  `);

  runTransaction(mailDb, () => {
    for (const row of rows) {
      const company = getSenderCompany(row.sender_raw || '');
      update.run(company.identity, company.label, row.message_key);
    }
  });

  const remaining = mailDb.prepare(`
    SELECT COUNT(*) AS total
    FROM received_messages
    WHERE sender_company_identity = '' OR sender_company_label = ''
  `).get()?.total || 0;

  return { updated: rows.length, remaining };
};

const upsertSenderReceipt = mailDb.prepare(`
  INSERT INTO sender_receipts (
    account_email, sender_identity, sender_raw, sender_label, sender_address,
    sender_company_identity, sender_company_label,
    first_received_at, latest_received_at, first_mailbox, latest_mailbox,
    message_count, first_fetched_at, updated_at
  )
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(account_email, sender_identity) DO UPDATE SET
    sender_raw = CASE WHEN excluded.latest_received_at >= sender_receipts.latest_received_at THEN excluded.sender_raw ELSE sender_receipts.sender_raw END,
    sender_label = CASE WHEN excluded.latest_received_at >= sender_receipts.latest_received_at THEN excluded.sender_label ELSE sender_receipts.sender_label END,
    sender_address = CASE WHEN excluded.latest_received_at >= sender_receipts.latest_received_at THEN excluded.sender_address ELSE sender_receipts.sender_address END,
    sender_company_identity = excluded.sender_company_identity,
    sender_company_label = excluded.sender_company_label,
    first_received_at = CASE
      WHEN sender_receipts.first_received_at = '' OR (excluded.first_received_at <> '' AND excluded.first_received_at < sender_receipts.first_received_at)
      THEN excluded.first_received_at
      ELSE sender_receipts.first_received_at
    END,
    latest_received_at = CASE
      WHEN excluded.latest_received_at > sender_receipts.latest_received_at THEN excluded.latest_received_at
      ELSE sender_receipts.latest_received_at
    END,
    first_mailbox = CASE
      WHEN sender_receipts.first_received_at = '' OR (excluded.first_received_at <> '' AND excluded.first_received_at < sender_receipts.first_received_at)
      THEN excluded.first_mailbox
      ELSE sender_receipts.first_mailbox
    END,
    latest_mailbox = CASE
      WHEN excluded.latest_received_at >= sender_receipts.latest_received_at THEN excluded.latest_mailbox
      ELSE sender_receipts.latest_mailbox
    END,
    message_count = MAX(sender_receipts.message_count, excluded.message_count),
    updated_at = excluded.updated_at
`);

const saveSenderReceipt = ({ accountEmail, senderRaw, mailbox = '', receivedAt = '', fetchedAt = '', messageCount = 1 }) => {
  const account = normalizeEmail(accountEmail);
  const raw = String(senderRaw || '').trim();
  const senderIdentity = normalizeSenderIdentity(raw);
  if (!account || !senderIdentity) return null;

  const company = getSenderCompany(raw);
  const timestamp = fetchedAt || nowIso();
  const normalizedReceivedAt = normalizeDateText(receivedAt) || timestamp;
  const mailboxValue = String(mailbox || '');
  const senderLabel = extractSenderLabel(raw);
  const senderAddress = extractSenderAddress(raw);

  upsertSenderReceipt.run(
    account,
    senderIdentity,
    raw,
    senderLabel,
    senderAddress,
    company.identity,
    company.label,
    normalizedReceivedAt,
    normalizedReceivedAt,
    mailboxValue,
    mailboxValue,
    Math.max(1, Number(messageCount) || 1),
    timestamp,
    timestamp,
  );

  return {
    senderIdentity,
    senderCompanyIdentity: company.identity,
  };
};

const migrateReceivedMessagesToSenderReceipts = (options = {}) => {
  const dropDetails = Boolean(options.dropDetails);
  const reset = options.reset !== false;
  const vacuum = Boolean(options.vacuum);

  if (!tableExists('received_messages')) {
    const refreshed = refreshAllSenderCompanyStats();
    return { migrated: 0, dropped_details: false, refreshed: refreshed.refreshed };
  }

  backfillSenderCompanies(0);

  const rows = mailDb.prepare(`
    WITH normalized AS (
      SELECT
        account_email,
        mailbox,
        sender_raw,
        sender_label,
        sender_address,
        COALESCE(NULLIF(sender_identity, ''), NULLIF(sender_address, ''), NULLIF(sender_label, ''), NULLIF(sender_raw, '')) AS normalized_sender_identity,
        sender_company_identity,
        sender_company_label,
        received_at,
        fetched_at
      FROM received_messages
      WHERE account_email <> ''
        AND (
          sender_identity <> ''
          OR sender_address <> ''
          OR sender_label <> ''
          OR sender_raw <> ''
        )
    )
    SELECT
      account_email,
      normalized_sender_identity AS sender_identity,
      COALESCE(MAX(NULLIF(sender_raw, '')), normalized_sender_identity) AS sender_raw,
      COALESCE(MAX(NULLIF(sender_label, '')), normalized_sender_identity) AS sender_label,
      COALESCE(MAX(NULLIF(sender_address, '')), '') AS sender_address,
      COALESCE(MAX(NULLIF(sender_company_identity, '')), '') AS sender_company_identity,
      COALESCE(MAX(NULLIF(sender_company_label, '')), '') AS sender_company_label,
      MIN(received_at) AS first_received_at,
      MAX(received_at) AS latest_received_at,
      MIN(mailbox) AS first_mailbox,
      MAX(mailbox) AS latest_mailbox,
      COUNT(*) AS message_count,
      MIN(fetched_at) AS first_fetched_at,
      MAX(fetched_at) AS updated_at
    FROM normalized
    WHERE normalized_sender_identity <> ''
    GROUP BY account_email, normalized_sender_identity
  `).all();

  if (reset) {
    mailDb.exec(`DELETE FROM sender_company_stats`);
    mailDb.exec(`DELETE FROM sender_company_recipients`);
    mailDb.exec(`DELETE FROM sender_receipts`);
  }

  const timestamp = nowIso();
  runTransaction(mailDb, () => {
    for (const row of rows) {
      const senderRaw = row.sender_raw || row.sender_identity;
      const company = row.sender_company_identity
        ? { identity: row.sender_company_identity, label: row.sender_company_label || row.sender_company_identity }
        : getSenderCompany(senderRaw);
      upsertSenderReceipt.run(
        normalizeEmail(row.account_email),
        normalizeSenderIdentity(senderRaw),
        senderRaw,
        row.sender_label || extractSenderLabel(senderRaw),
        row.sender_address || extractSenderAddress(senderRaw),
        company.identity,
        company.label,
        normalizeDateText(row.first_received_at),
        normalizeDateText(row.latest_received_at),
        row.first_mailbox || '',
        row.latest_mailbox || '',
        Number(row.message_count) || 1,
        row.first_fetched_at || timestamp,
        row.updated_at || timestamp,
      );
    }
  });

  const refreshed = refreshAllSenderCompanyStats();

  if (dropDetails) {
    mailDb.exec(`DROP TABLE IF EXISTS received_messages`);
    if (vacuum) {
      mailDb.exec(`VACUUM`);
    }
  }

  return {
    migrated: rows.length,
    dropped_details: dropDetails,
    refreshed: refreshed.refreshed,
  };
};

const refreshSenderCompanyStats = (identities = []) => {
  const cleanIdentities = Array.from(new Set(
    identities.map((item) => String(item || '').trim()).filter(Boolean)
  ));
  if (cleanIdentities.length === 0) return { refreshed: 0 };

  const deleteRecipients = mailDb.prepare(`DELETE FROM sender_company_recipients WHERE sender_company_identity = ?`);
  const insertRecipients = mailDb.prepare(`
    INSERT INTO sender_company_recipients (sender_company_identity, account_email, count, latest_date)
    SELECT sender_company_identity, account_email, COUNT(*) AS count, MAX(latest_received_at) AS latest_date
    FROM sender_receipts
    WHERE sender_company_identity = ?
    GROUP BY sender_company_identity, account_email
  `);
  const getStats = mailDb.prepare(`
    SELECT
      sender_company_identity,
      COALESCE(MAX(NULLIF(sender_company_label, '')), sender_company_identity) AS sender_company_label,
      COUNT(*) AS item_count,
      COUNT(DISTINCT account_email) AS received_recipient_count,
      MAX(latest_received_at) AS latest_date
    FROM sender_receipts
    WHERE sender_company_identity = ?
    GROUP BY sender_company_identity
  `);
  const upsertStats = mailDb.prepare(`
    INSERT INTO sender_company_stats (
      sender_company_identity, sender_company_label, item_count,
      received_recipient_count, latest_date, latest_verification_code, updated_at
    )
    VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(sender_company_identity) DO UPDATE SET
      sender_company_label = excluded.sender_company_label,
      item_count = excluded.item_count,
      received_recipient_count = excluded.received_recipient_count,
      latest_date = excluded.latest_date,
      latest_verification_code = excluded.latest_verification_code,
      updated_at = excluded.updated_at
  `);
  const deleteStats = mailDb.prepare(`DELETE FROM sender_company_stats WHERE sender_company_identity = ?`);

  runTransaction(mailDb, () => {
    for (const identity of cleanIdentities) {
      deleteRecipients.run(identity);
      insertRecipients.run(identity);
      const stats = getStats.get(identity);
      if (!stats) {
        deleteStats.run(identity);
        continue;
      }
      upsertStats.run(
        identity,
        stats.sender_company_label || identity,
        Number(stats.item_count) || 0,
        Number(stats.received_recipient_count) || 0,
        stats.latest_date || '',
        '',
        nowIso(),
      );
    }
  });

  return { refreshed: cleanIdentities.length };
};

const refreshAllSenderCompanyStats = () => {
  const identities = mailDb.prepare(`
    SELECT DISTINCT sender_company_identity
    FROM sender_receipts
    WHERE sender_company_identity <> ''
  `).all().map((row) => row.sender_company_identity);
  mailDb.exec(`DELETE FROM sender_company_stats`);
  mailDb.exec(`DELETE FROM sender_company_recipients`);
  return refreshSenderCompanyStats(identities);
};

const normalizeStoredSenderReceiptDates = (limit = 0) => {
  const rowLimit = Math.max(0, Number(limit) || 0);
  const rows = mailDb.prepare(`
    SELECT account_email, sender_identity, first_received_at, latest_received_at
    FROM sender_receipts
    WHERE first_received_at <> '' OR latest_received_at <> ''
    ${rowLimit > 0 ? `LIMIT ${rowLimit}` : ''}
  `).all();

  if (rows.length === 0) {
    return { checked: 0, updated: 0, refreshed: 0 };
  }

  const update = mailDb.prepare(`
    UPDATE sender_receipts
    SET first_received_at = ?, latest_received_at = ?
    WHERE account_email = ? AND sender_identity = ?
  `);
  let updated = 0;

  runTransaction(mailDb, () => {
    for (const row of rows) {
      const firstReceivedAt = normalizeDateText(row.first_received_at);
      const latestReceivedAt = normalizeDateText(row.latest_received_at);
      if (firstReceivedAt === row.first_received_at && latestReceivedAt === row.latest_received_at) continue;
      update.run(firstReceivedAt, latestReceivedAt, row.account_email, row.sender_identity);
      updated += 1;
    }
  });

  const refreshed = updated > 0 ? refreshAllSenderCompanyStats().refreshed : 0;
  return { checked: rows.length, updated, refreshed };
};

const normalizeSenderIdentity = (send = '') => {
  const label = extractSenderLabel(send);
  const address = extractSenderAddress(send);
  const normalizedLabel = normalizeSenderLabel(label);
  const normalizedAddress = normalizeEmail(address);

  const aliasRules = [
    { key: 'nomad', patterns: ['nomad', 'getnomad', 'nomad esim'] },
    { key: 'openai', patterns: ['openai', 'tm.openai.com', 'noreply@tm.openai.com'] },
    { key: 'microsoft', patterns: ['microsoft', 'accountprotection.microsoft.com', 'maccount@microsoft.com'] },
  ];

  for (const rule of aliasRules) {
    if (rule.patterns.some((pattern) => normalizedLabel.includes(pattern) || normalizedAddress.includes(pattern))) {
      return rule.key;
    }
  }

  return normalizedAddress || normalizedLabel;
};

const buildIgnoredVerificationCodes = (terms = []) => {
  const ignored = new Set();
  const add = (value = '') => {
    const compact = String(value || '').replace(/[^A-Za-z0-9]/g, '');
    if (/^[A-Za-z0-9]{4,8}$/.test(compact) && /[A-Za-z]/.test(compact)) {
      ignored.add(compact.toUpperCase());
    }
  };

  for (const term of Array.isArray(terms) ? terms : [terms]) {
    const text = String(term || '').trim();
    if (!text) continue;
    add(text);
    text.split(/[^A-Za-z0-9]+/).forEach(add);
    if (text.startsWith('company:')) {
      const domain = text.slice('company:'.length);
      add(domain);
      add(domain.split('.')[0] || domain);
    }
  }

  return ignored;
};

const commonIgnoredVerificationWords = new Set([
  'CODE', 'CODES', 'EMAIL', 'VERIFY', 'VERIFIED', 'VERIFICATION', 'ACCOUNT',
  'LOGIN', 'SIGNIN', 'SIGNUP', 'SECURITY', 'TEMPORARY', 'ENTER', 'THIS',
  'YOUR', 'NOMAD', 'ESIM', 'GETNOMAD', 'SUPPORT', 'TEAM', 'TRIAL',
  'PLAN', 'PLANS', 'DATA', 'ROAMING', 'APP', 'STORE', 'DOWNLOAD',
  'EXPIRE', 'EXPIRES', 'EXPIRED', 'MINUTE', 'MINUTES', 'AFTER', 'BEFORE',
  'IGNORE', 'MESSAGE', 'REQUEST', 'CONTACT', 'QUESTIONS', 'CONCERNS',
  'AUTOMATIC', 'AUTOMATICALLY', 'GENERATED', 'REPLIES', 'MONITORED',
  'CURSOR',
]);

const decodeHtmlEntities = (value = '') => String(value || '')
  .replace(/&nbsp;/gi, ' ')
  .replace(/&amp;/gi, '&')
  .replace(/&lt;/gi, '<')
  .replace(/&gt;/gi, '>')
  .replace(/&quot;/gi, '"')
  .replace(/&#39;/g, "'")
  .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
  .replace(/&#(\d+);/g, (_, num) => String.fromCodePoint(parseInt(num, 10)));

const extractHtmlCodeHints = (html = '') => {
  const hints = [];
  const source = String(html || '');
  for (const match of source.matchAll(/\b(?:alt|title|aria-label|data-code|data-otp)\s*=\s*(['"])(.*?)\1/gi)) {
    const value = decodeHtmlEntities(match[2]).replace(/\s+/g, ' ').trim();
    if (value) hints.push(value);
  }
  return hints.join('\n');
};

const hasNomadContext = (options = {}) => {
  const project = options.project || {};
  const company = options.company || {};
  const terms = [
    options.projectKey,
    options.projectName,
    options.senderCompanyIdentity,
    options.senderCompanyLabel,
    project.projectKey,
    project.projectName,
    project.senderCompanyIdentity,
    project.senderCompanyLabel,
    company.identity,
    company.label,
    ...(Array.isArray(options.ignoredTerms) ? options.ignoredTerms : []),
  ].filter(Boolean).join(' ').toLowerCase();
  return terms.includes('nomad') || terms.includes('getnomad');
};

const hasNomadVerificationIntent = (content = '') => {
  const normalized = String(content || '').toLowerCase();
  return [
    'verification code',
    'verify your account',
    'verify your email',
    'enter this code',
    'this code in nomad',
    'nomad verification code',
    'verification codes expire',
    'one-time code',
    'one time code',
    'security code',
    'login code',
    'otp',
  ].some((phrase) => normalized.includes(phrase));
};

const cursorCompanyIdentities = new Set(['company:cursor.com', 'company:cursor.sh']);

const isCursorCompanyIdentity = (identity = '') => cursorCompanyIdentities.has(String(identity || '').trim().toLowerCase());

const hasCursorContext = (options = {}) => {
  const project = options.project || {};
  const company = options.company || {};
  const terms = [
    options.projectKey,
    options.projectName,
    options.senderCompanyIdentity,
    options.senderCompanyLabel,
    project.projectKey,
    project.projectName,
    project.senderCompanyIdentity,
    project.senderCompanyLabel,
    company.identity,
    company.label,
    ...(Array.isArray(project.senderCompanyIdentities) ? project.senderCompanyIdentities : []),
    ...(Array.isArray(project.senderCompanyLabels) ? project.senderCompanyLabels : []),
    ...(Array.isArray(options.ignoredTerms) ? options.ignoredTerms : []),
  ].filter(Boolean).join(' ').toLowerCase();
  return terms.includes('cursor') || terms.includes('cursor.com') || terms.includes('cursor.sh');
};

const hasCursorVerificationIntent = (content = '') => {
  const normalized = String(content || '')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .toLowerCase();
  return [
    /verify(?:ing)?\s+(?:your\s+)?(?:email|account)(?:\s+address)?/i,
    /(?:email|account)\s+verification/i,
    /verification\s+code/i,
    /security\s+code/i,
    /one[-\s]?time\s+code/i,
    /login\s+code/i,
    /sign[-\s]?in\s+code/i,
    /otp\b/i,
    /confirm(?:\s+your)?\s+(?:email|account)(?:\s+address)?/i,
    /\u9a8c\u8bc1(?:\u60a8\u7684|\u4f60\u7684)?(?:\u90ae\u7bb1|\u8d26\u53f7|\u5e10\u53f7)(?:\u5730\u5740)?/i,
    /\u90ae\u7bb1\u9a8c\u8bc1/i,
    /\u8d26\u53f7\u9a8c\u8bc1/i,
    /\u9a8c\u8bc1\u7801/i,
    /\u9a57\u8b49\u78bc/i,
    /\u767b\u5f55\u7801/i,
    /\u767b\u5165\u78bc/i,
    /\u4e00\u6b21\u6027(?:\u4ee3\u7801|\u9a8c\u8bc1\u7801)/i,
    /\u4e00\u6b21\u6027\u9a57\u8b49\u78bc/i,
  ].some((pattern) => pattern.test(normalized));
};

const isCursorSenderMail = (mail = {}) => {
  const sender = `${mail.send || ''} ${mail.senderAddress || ''}`.toLowerCase();
  return isCursorCompanyIdentity(mail.senderCompanyIdentity)
    || sender.includes('@cursor.sh')
    || sender.includes('@cursor.com')
    || sender.includes('@mail.cursor.com');
};

const isCursorVerificationMail = (mail = {}) => {
  const sender = `${mail.send || ''} ${mail.senderAddress || ''}`.toLowerCase();
  const content = [mail.subject, mail.text, mail.html].filter(Boolean).join('\n');
  const fromVerificationSender = sender.includes('no-reply@cursor.sh') || sender.includes('noreply@cursor.sh');
  return isCursorSenderMail(mail) && (fromVerificationSender || hasCursorVerificationIntent(content));
};

const extractVerificationCode = (subject = '', text = '', html = '', options = {}) => {
  const htmlHints = extractHtmlCodeHints(html);
  const rawContent = [subject, text, htmlHints, html].filter(Boolean).join('\n');
  if (!rawContent) return { code: '', source: '' };

  const normalized = rawContent
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/https?:\/\/\S+/gi, ' ')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|tr|li|h\d)>/gi, '\n')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/g, "'")
    .replace(/\r/g, '')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n+/g, '\n')
    .trim();

  const isNomadContext = hasNomadContext(options);
  const nomadVerificationIntent = isNomadContext && hasNomadVerificationIntent(`${subject}\n${text}\n${normalized}`);
  if (isNomadContext && !nomadVerificationIntent) {
    return { code: '', source: '' };
  }
  const isCursorContext = hasCursorContext(options);
  const cursorVerificationIntent = isCursorContext && hasCursorVerificationIntent(`${subject}\n${text}\n${normalized}`);
  if (isCursorContext && !cursorVerificationIntent) {
    return { code: '', source: '' };
  }

  const keywords = [
    '验证码', '校验码', '验证代码', '动态码', '一次性代码', '一次性验证码', '临时验证码', '临时代码',
    '驗證碼', '校驗碼', '驗證代碼', '動態碼', '一次性代碼', '一次性驗證碼', '臨時驗證碼', '臨時代碼',
    'verification code', 'verification codes', 'security code', 'security codes',
    'one-time code', 'one time code', 'temporary code', 'login code', 'otp', 'code',
  ];
  const keywordRegex = keywords
    .map((keyword) => keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .sort((a, b) => b.length - a.length)
    .join('|');
  const candidates = [];
  const ignoredCodes = buildIgnoredVerificationCodes(options.ignoredTerms || []);
  const normalizeCandidate = (code) => {
    const compact = String(code || '').replace(/[^A-Za-z0-9]/g, '');
    return /^\d+$/.test(compact) ? compact : compact.toUpperCase();
  };
  const isLikelyCode = (code, context = {}) => {
    const normalizedCode = normalizeCandidate(code);
    if (!/^[A-Za-z0-9]{4,8}$/.test(normalizedCode)) return false;
    if (commonIgnoredVerificationWords.has(normalizedCode)) return false;
    if (/^(\d)\1{3,7}$/.test(normalizedCode)) return false;
    if (/^\d{1,2}(?:AM|PM)$/.test(normalizedCode)) return false;
    if (/^(?:DAY|WEEK|MONTH|YEAR)\d{1,2}$/.test(normalizedCode)) return false;
    if (/^\d{1,3}(?:K|OFF)$/.test(normalizedCode)) return false;
    if (/^(?:19|20)\d{2}$/.test(normalizedCode) && !context.hasKeyword && !context.explicitKeyword) return false;
    if (/^[0-9]{4,8}$/.test(normalizedCode)) return true;
    if (/^[A-Z]{4,8}$/.test(normalizedCode)) {
      return Boolean(context.allowPureAlpha && (context.explicitKeyword || context.hasKeyword || context.lineIsCode));
    }
    if (!/[A-Z]/.test(normalizedCode) || !/\d/.test(normalizedCode)) return false;
    if (context.hasKeyword || context.explicitKeyword) return true;
    const digitCount = (normalizedCode.match(/\d/g) || []).length;
    return context.lineIsCode && (digitCount >= 2 || normalizedCode.length >= 5);
  };
  const addCandidate = (code, score, source, context = {}) => {
    const normalizedCode = normalizeCandidate(code);
    if (!normalizedCode || ignoredCodes.has(normalizedCode) || !isLikelyCode(normalizedCode, context)) return;
    const numericBonus = /^\d+$/.test(normalizedCode) ? 90 : 0;
    const lengthBonus = normalizedCode.length === 6 && !/^[A-Z]+$/.test(normalizedCode) ? 20 : 0;
    candidates.push({ code: normalizedCode, score: score + numericBonus + lengthBonus, source });
  };

  const subjectNormalized = String(subject || '').trim();
  const subjectHasKeyword = keywords.some((keyword) => subjectNormalized.toLowerCase().includes(keyword.toLowerCase()));
  for (const match of subjectNormalized.matchAll(/\b([A-Za-z0-9]{4,8})\b/g)) {
    const code = (match[1] || '').trim();
    addCandidate(code, 360 + (subjectHasKeyword ? 120 : 0) - code.length, subjectHasKeyword ? 'subject_keyword' : 'subject', {
      hasKeyword: subjectHasKeyword,
    });
  }

  const separatorChunk = String.raw`(?:[\s\u00a0\u2000-\u200d\ufeff]+|(?:is|are|为|為|是|:|：|,|，|。|\.|;|；|-|–|—|=)[\s\u00a0\u2000-\u200d\ufeff]*)`;
  const codeTokenPattern = String.raw`([A-Za-z0-9]{4,8}|[A-Za-z0-9](?:[\s\u00a0\u2000-\u200d._-]+[A-Za-z0-9]){3,7})`;
  const keywordBeforeCode = new RegExp(`(?:${keywordRegex})(?:${separatorChunk}){0,8}${codeTokenPattern}`, 'ig');
  for (const match of normalized.matchAll(keywordBeforeCode)) {
    addCandidate(match[1] || '', 720, 'explicit_keyword_after', { explicitKeyword: true, hasKeyword: true });
  }

  const lines = normalized.split('\n').map((line) => line.trim()).filter(Boolean);
  lines.forEach((line, index) => {
    const contextText = `${lines[index - 1] || ''} ${line} ${lines[index + 1] || ''}`.toLowerCase();
    const hasKeywordNearby = keywords.some((keyword) => contextText.includes(keyword.toLowerCase()));
    for (const match of line.matchAll(/\b([A-Za-z0-9]{4,8})\b/g)) {
      const code = (match[1] || '').trim();
      const normalizedCode = normalizeCandidate(code);
      const lineIsCode = line.replace(/[^A-Za-z0-9]/g, '').toUpperCase() === normalizedCode;
      addCandidate(code, (lineIsCode ? 330 : 180) + (hasKeywordNearby ? 180 : 0) - normalizedCode.length, hasKeywordNearby ? 'near_keyword' : 'line', {
        hasKeyword: hasKeywordNearby,
        lineIsCode,
      });
    }
  });

  if (nomadVerificationIntent) {
    const addVisualWindowCandidates = (windowText = '', baseScore = 0) => {
      const cleaned = String(windowText || '')
        .replace(/https?:\/\/\S+/gi, ' ')
        .replace(/[：:]/g, ':')
        .replace(/\s+/g, ' ')
        .trim();
      if (!cleaned) return;

      let order = 0;
      const scanPatterns = [
        /(?:^|[^A-Za-z0-9])([A-Za-z0-9]{4,8})(?=$|[^A-Za-z0-9])/g,
        /(?:^|[^A-Za-z0-9])([A-Za-z0-9](?:[\s\u00a0._-]+[A-Za-z0-9]){3,7})(?=$|[^A-Za-z0-9])/g,
      ];
      for (const pattern of scanPatterns) {
        for (const match of cleaned.matchAll(pattern)) {
          const rawCode = match[1] || '';
          const candidateStart = (match.index || 0) + match[0].lastIndexOf(rawCode);
          const before = cleaned.slice(Math.max(0, candidateStart - 100), candidateStart).toLowerCase();
          const after = cleaned.slice(candidateStart + rawCode.length, candidateStart + rawCode.length + 100).toLowerCase();
          const hasStrongPrefix = /(?:enter\s+this\s+code|verification\s+code|code\s+in\s+nomad|nomad\s+verification\s+code)/i.test(before);
          const hasStrongSuffix = /(?:verification\s+codes?\s+expire|expire|minutes?|if\s+you)/i.test(after);
          const lineIsCode = cleaned.replace(/[^A-Za-z0-9]/g, '').toUpperCase() === normalizeCandidate(rawCode);
          addCandidate(rawCode, baseScore + (hasStrongPrefix ? 160 : 0) + (hasStrongSuffix ? 80 : 0) - order, 'nomad_visual_code', {
            explicitKeyword: hasStrongPrefix,
            hasKeyword: hasStrongPrefix || hasStrongSuffix,
            lineIsCode,
            allowPureAlpha: true,
          });
          order += 1;
        }
      }
    };

    const nomadMarkerRegex = /(?:verification\s+code|enter\s+this\s+code|code\s+in\s+nomad|nomad\s+verification\s+code)/ig;
    for (const match of normalized.matchAll(nomadMarkerRegex)) {
      const index = match.index || 0;
      addVisualWindowCandidates(normalized.slice(Math.max(0, index - 80), index + 320), 900);
    }

    lines.forEach((line, index) => {
      const contextText = `${lines[index - 1] || ''} ${line} ${lines[index + 1] || ''}`;
      if (hasNomadVerificationIntent(contextText)) {
        addVisualWindowCandidates(contextText, 820);
      }
    });
  }

  candidates.sort((a, b) => b.score - a.score);
  return {
    code: candidates[0]?.code || '',
    source: candidates[0]?.source || '',
  };
};

const isStrongVerificationSource = (source = '') => {
  const normalized = String(source || '').trim().toLowerCase();
  return normalized.startsWith('explicit_keyword')
    || normalized === 'subject_keyword'
    || normalized === 'nomad_visual_code';
};

const getMailboxLabel = (mailbox = '') => {
  if (mailbox === 'Junk') return '垃圾箱';
  if (mailbox === 'Archive') return '归档';
  if (mailbox === 'Deleted') return '已删除';
  if (mailbox === 'Sent') return '已发送';
  if (mailbox === 'Drafts') return '草稿';
  return '收件箱';
};

const buildMessageKey = (accountEmail, mailbox, mail) => {
  const providerId = String(mail?.id || mail?.provider_message_id || '').trim();
  const base = providerId
    ? `${accountEmail}|${mailbox}|id|${providerId}`
    : `${accountEmail}|${mailbox}|fallback|${mail?.date || ''}|${mail?.subject || ''}|${mail?.send || ''}`;
  return sha256(base);
};

const saveFetchedEmails = ({ accountEmail, mailbox, emails, source = 'mail_fetch', requestedTop = 0, status = 'success', error = '', refreshStats = true }) => {
  const account = normalizeEmail(accountEmail);
  const items = Array.isArray(emails) ? emails : [];
  const timestamp = nowIso();
  const mailboxValue = String(mailbox || '');
  const startedAt = timestamp;
  const affectedCompanyIdentities = new Set();
  const savedKeys = new Set();

  runTransaction(mailDb, () => {
    for (const mail of items) {
      const senderRaw = String(mail?.send || '');
      const receipt = saveSenderReceipt({
        accountEmail: account,
        senderRaw,
        mailbox: mailboxValue,
        receivedAt: mail?.date || '',
        fetchedAt: timestamp,
      });
      if (!receipt) continue;
      savedKeys.add(`${account}|${receipt.senderIdentity}`);
      if (receipt.senderCompanyIdentity) affectedCompanyIdentities.add(receipt.senderCompanyIdentity);
    }
  });

  const savedCount = savedKeys.size;
  mailDb.prepare(`
    INSERT INTO mail_fetch_runs (
      account_email, mailbox, source, requested_top, fetched_count, saved_count, status, error, started_at, finished_at
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(account, mailboxValue, source, Number(requestedTop) || 0, items.length, savedCount, status, error, startedAt, nowIso());

  const affectedCompanyIdentityList = Array.from(affectedCompanyIdentities);
  if (parseBoolean(refreshStats) && affectedCompanyIdentityList.length > 0) {
    refreshSenderCompanyStats(affectedCompanyIdentityList);
  }

  return {
    database: MAIL_DB_PATH,
    account_email: account,
    mailbox: mailboxValue,
    fetched_count: items.length,
    saved_count: savedCount,
    affected_company_identities: affectedCompanyIdentityList,
  };
};

const recordFetchFailure = ({ accountEmail, mailbox, source = 'mail_fetch', requestedTop = 0, error = '' }) => {
  const timestamp = nowIso();
  mailDb.prepare(`
    INSERT INTO mail_fetch_runs (
      account_email, mailbox, source, requested_top, fetched_count, saved_count, status, error, started_at, finished_at
    )
    VALUES (?, ?, ?, ?, 0, 0, 'error', ?, ?, ?)
  `).run(normalizeEmail(accountEmail), String(mailbox || ''), source, Number(requestedTop) || 0, String(error || ''), timestamp, timestamp);
};

const rowsToSenderItems = (rows) => rows.map((row) => ({
  email: row.account_email,
  send: row.sender_raw,
  subject: '',
  text: '',
  html: '',
  date: row.received_at || row.latest_received_at,
  mailbox: row.mailbox,
  mailboxLabel: row.mailbox_label || getMailboxLabel(row.mailbox),
  verificationCode: '',
  verificationSource: '',
  senderLabel: row.sender_label || extractSenderLabel(row.sender_raw),
  senderIdentity: row.sender_identity || normalizeSenderIdentity(row.sender_raw),
  senderCompanyLabel: row.sender_company_label || '',
  senderCompanyIdentity: row.sender_company_identity || '',
}));

const senderCompanyExpr = `sender_company_identity`;
const senderCompanyLabelExpr = `COALESCE(NULLIF(sender_company_label, ''), NULLIF(sender_label, ''), sender_identity)`;

const getSenderItemRows = (whereSql, params = [], limit = 0) => {
  const limitSql = limit > 0 ? 'LIMIT ?' : '';
  const finalParams = limit > 0 ? [...params, limit] : params;
  return mailDb.prepare(`
    SELECT account_email, latest_mailbox AS mailbox, latest_mailbox AS mailbox_label, sender_raw, sender_label, sender_identity,
           sender_company_identity, sender_company_label,
           latest_received_at AS received_at
    FROM sender_receipts
    ${whereSql}
    ORDER BY latest_received_at DESC
    ${limitSql}
  `).all(...finalParams);
};

const companyIdentityWhere = (identities = []) => {
  const cleanIdentities = identities.map((item) => String(item || '').trim()).filter(Boolean);
  if (cleanIdentities.length === 0) return { sql: '1 = 0', params: [] };
  return {
    sql: `sender_company_identity IN (${cleanIdentities.map(() => '?').join(', ')})`,
    params: cleanIdentities,
  };
};

const buildSenderGroup = (sender, rawItems, allEmails, options = {}) => {
  const previewLimit = Number.isInteger(options.previewLimit) ? options.previewLimit : DEFAULT_PREVIEW_LIMIT;
  const itemLimit = Number.isInteger(options.itemLimit) ? options.itemLimit : DEFAULT_ITEM_LIMIT;
  const sortedItems = [...rawItems].sort((a, b) => new Date(b.date || 0).getTime() - new Date(a.date || 0).getTime());
  const recipientMap = new Map();

  for (const item of sortedItems) {
    const normalized = normalizeEmail(item.email);
    const current = recipientMap.get(normalized);
    if (!current) {
      recipientMap.set(normalized, {
        email: item.email,
        count: 1,
        latestDate: item.date || '',
      });
      continue;
    }

    current.count += 1;
    if (new Date(item.date || 0).getTime() > new Date(current.latestDate || 0).getTime()) {
      current.latestDate = item.date || current.latestDate;
      current.email = item.email;
    }
  }

  const receivedRecipients = Array.from(recipientMap.values())
    .sort((a, b) => new Date(b.latestDate || 0).getTime() - new Date(a.latestDate || 0).getTime());
  const receivedSet = new Set(receivedRecipients.map((item) => normalizeEmail(item.email)));
  const unreceivedEmails = allEmails.filter((email) => !receivedSet.has(normalizeEmail(email)));

  return {
    sender,
    items: sortedItems.slice(0, itemLimit),
    itemCount: sortedItems.length,
    latestVerificationCode: sortedItems.find((item) => item.verificationCode)?.verificationCode || '',
    receivedRecipients: receivedRecipients.slice(0, previewLimit),
    receivedRecipientCount: receivedRecipients.length,
    unreceivedEmails: unreceivedEmails.slice(0, previewLimit),
    unreceivedEmailCount: unreceivedEmails.length,
  };
};

const getSenderOptionRecords = (options = DEFAULT_SENDER_OPTION_LIMIT) => {
  const normalizedOptions = (options && typeof options === 'object') ? options : { limit: options };
  const { ownerUsername } = resolveOwnerOptions(normalizedOptions);
  const limit = toPositiveInt(normalizedOptions.limit, DEFAULT_SENDER_OPTION_LIMIT, DEFAULT_SENDER_OPTION_LIMIT);
  const search = String(normalizedOptions.search || '').trim();
  const sort = normalizeSenderSort(normalizedOptions.sort);
  const whereParts = [
    `sender_company_identity <> ''`,
    `EXISTS (
      SELECT 1
      FROM sender_company_recipients scr
      INNER JOIN accounts_db.account_records ar ON ar.email = scr.account_email
      WHERE scr.sender_company_identity = sender_company_stats.sender_company_identity
        AND ar.owner_username = ?
        AND ar.status = 'active'
    )`,
  ];
  const params = [ownerUsername];

  if (search) {
    const like = `%${search}%`;
    whereParts.push(`(sender_company_label LIKE ? OR sender_company_identity LIKE ?)`);
    params.push(like, like);
  }

  const whereSql = `WHERE ${whereParts.join(' AND ')}`;
  return mailDb.prepare(`
    SELECT
      sender_company_identity AS sender_identity,
      sender_company_label AS sender,
      item_count,
      latest_date,
      updated_at
    FROM sender_company_stats
    ${whereSql}
    ${senderStatsOrderSql(sort)}
    LIMIT ?
  `).all(...params, limit).map((row) => ({
    label: row.sender || row.sender_identity,
    value: row.sender_identity,
    count: Number(row.item_count) || 0,
    latestDate: row.latest_date || '',
    updatedAt: row.updated_at || '',
  }));
};

const getSenderOptions = (limit = DEFAULT_SENDER_OPTION_LIMIT) => {
  return getSenderOptionRecords(limit).map((row) => row.label);
};

const getLatestVerificationCodeForSenderIdentities = (identities) => {
  if (identities.length === 1) {
    const row = mailDb.prepare(`
      SELECT latest_verification_code
      FROM sender_company_stats
      WHERE sender_company_identity = ?
    `).get(identities[0]);
    if (row?.latest_verification_code) return row.latest_verification_code;
  }
  return '';
};

const getReceivedRecipientsForSenderIdentities = (identities, previewLimit) => {
  const where = companyIdentityWhere(identities);
  return mailDb.prepare(`
    SELECT account_email AS email, SUM(count) AS count, MAX(latest_date) AS latestDate
    FROM sender_company_recipients
    WHERE ${where.sql}
    GROUP BY account_email
    ORDER BY latestDate DESC
    LIMIT ?
  `).all(...where.params, previewLimit);
};

const getUnreceivedEmailsForSenderIdentities = (identities, previewLimit) => {
  if (previewLimit <= 0) return [];
  const where = companyIdentityWhere(identities);
  return mailDb.prepare(`
    SELECT ar.email
    FROM accounts_db.account_records ar
    WHERE ar.status = 'active'
      AND NOT EXISTS (
        SELECT 1
        FROM sender_company_recipients rm
        WHERE ${where.sql.replaceAll('sender_company_identity', 'rm.sender_company_identity')}
          AND rm.account_email = ar.email
        LIMIT 1
      )
    ORDER BY ar.email ASC
    LIMIT ?
  `).all(...where.params, previewLimit).map((row) => row.email);
};

const buildSenderGroupFromSummary = (summary, totalAccountCount, options = {}) => {
  const previewLimit = Number.isInteger(options.previewLimit) ? options.previewLimit : DEFAULT_PREVIEW_LIMIT;
  const itemLimit = Number.isInteger(options.itemLimit) ? options.itemLimit : DEFAULT_ITEM_LIMIT;
  const senderIdentity = summary.sender_identity;
  const identities = Array.isArray(summary.sender_identities) ? summary.sender_identities : [senderIdentity];
  const where = companyIdentityWhere(identities);
  const items = rowsToSenderItems(getSenderItemRows(`WHERE ${where.sql}`, where.params, itemLimit));
  const receivedRecipients = getReceivedRecipientsForSenderIdentities(identities, previewLimit);
  const receivedRecipientCount = Number(summary.received_recipient_count) || 0;
  const unreceivedEmailCount = Math.max(0, totalAccountCount - receivedRecipientCount);
  const unreceivedEmails = getUnreceivedEmailsForSenderIdentities(identities, Math.min(previewLimit, unreceivedEmailCount));

  return {
    sender: summary.sender || senderIdentity,
    senderIdentity,
    items,
    itemCount: Number(summary.item_count) || items.length,
    latestVerificationCode: summary.latest_verification_code || getLatestVerificationCodeForSenderIdentities(identities),
    receivedRecipients,
    receivedRecipientCount,
    unreceivedEmails,
    unreceivedEmailCount,
  };
};

const listSenderGroups = (options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  const page = toPositiveInt(options.page, 1);
  const pageSize = toPositiveInt(options.pageSize, DEFAULT_SENDER_PAGE_SIZE, MAX_SENDER_PAGE_SIZE);
  const previewLimit = toPositiveInt(options.previewLimit, DEFAULT_PREVIEW_LIMIT, DEFAULT_PREVIEW_LIMIT);
  const itemLimit = toPositiveInt(options.itemLimit, DEFAULT_ITEM_LIMIT, DEFAULT_ITEM_LIMIT);
  const search = String(options.search || '').trim();
  const sort = normalizeSenderSort(options.sort);
  const whereParts = [
    `sender_company_identity <> ''`,
    `EXISTS (
      SELECT 1
      FROM sender_company_recipients scr
      INNER JOIN accounts_db.account_records ar ON ar.email = scr.account_email
      WHERE scr.sender_company_identity = sender_company_stats.sender_company_identity
        AND ar.owner_username = ?
        AND ar.status = 'active'
    )`,
  ];
  const params = [ownerUsername];

  if (search) {
    const like = `%${search}%`;
    whereParts.push(`(sender_company_label LIKE ? OR sender_company_identity LIKE ?)`);
    params.push(like, like);
  }

  const whereSql = `WHERE ${whereParts.join(' AND ')}`;
  const total = mailDb.prepare(`
    SELECT COUNT(*) AS total
    FROM sender_company_stats
    ${whereSql}
  `).get(...params)?.total || 0;
  const offset = (page - 1) * pageSize;
  const totalAccountCount = getActiveAccountCount({ ownerUsername });
  const senderOptionRecords = getSenderOptionRecords({ sort, ownerUsername });
  const summaries = mailDb.prepare(`
    SELECT
      sender_company_identity AS sender_identity,
      sender_company_label AS sender,
      item_count,
      received_recipient_count,
      latest_date,
      latest_verification_code
    FROM sender_company_stats
    ${whereSql}
    ${senderStatsOrderSql(sort)}
    LIMIT ? OFFSET ?
  `).all(...params, pageSize, offset);

  return {
    records: summaries.map((summary) => buildSenderGroupFromSummary(summary, totalAccountCount, { previewLimit, itemLimit })),
    page,
    pageSize,
    total,
    senderOptions: senderOptionRecords.map((row) => row.label),
    senderOptionRecords,
    database: MAIL_DB_PATH,
  };
};

const listSenderGroupsLegacy = () => {
  return listSenderGroups({ page: 1, pageSize: MAX_SENDER_PAGE_SIZE }).records;
};

const filterSenderGroups = (selectedSenders = [], mailboxes = [], options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  const previewLimit = toPositiveInt(options.previewLimit, DEFAULT_PREVIEW_LIMIT, DEFAULT_PREVIEW_LIMIT);
  const itemLimit = toPositiveInt(options.itemLimit, DEFAULT_ITEM_LIMIT, DEFAULT_ITEM_LIMIT);
  const allEmails = mailboxes.length > 0
    ? mailboxes.map(normalizeEmail).filter(Boolean)
    : listAccountEmails({ ownerUsername });
  const selectedValues = selectedSenders.map((item) => normalizeSenderLabel(item)).filter(Boolean);
  const selected = new Set(selectedValues);

  if (selected.size === 0) {
    return {
      receivedRecipients: [],
      unreceivedEmails: allEmails,
      items: [],
      selectedSenders,
    };
  }

  const selectedCompanyIdentities = selectedValues.filter((value) => value.startsWith('company:') || value.startsWith('sender:'));
  if (selectedCompanyIdentities.length === selectedValues.length) {
    const where = companyIdentityWhere(selectedCompanyIdentities);
    const summary = mailDb.prepare(`
      SELECT
        SUM(item_count) AS item_count,
        MAX(latest_date) AS latest_date,
        MAX(latest_verification_code) AS latest_verification_code
      FROM sender_company_stats
      WHERE ${where.sql}
    `).get(...where.params);
    const recipientSummary = mailDb.prepare(`
      SELECT COUNT(DISTINCT account_email) AS received_recipient_count
      FROM sender_company_recipients
      WHERE ${where.sql}
    `).get(...where.params);
    const group = buildSenderGroupFromSummary({
      sender: selectedSenders.join(' + '),
      sender_identity: selectedCompanyIdentities[0],
      sender_identities: selectedCompanyIdentities,
      item_count: summary?.item_count || 0,
      received_recipient_count: recipientSummary?.received_recipient_count || 0,
      latest_verification_code: summary?.latest_verification_code || '',
    }, allEmails.length || getActiveAccountCount(), { previewLimit, itemLimit });

    return {
      receivedRecipients: group.receivedRecipients,
      receivedRecipientCount: group.receivedRecipientCount,
      unreceivedEmails: group.unreceivedEmails,
      unreceivedEmailCount: group.unreceivedEmailCount,
      items: group.items,
      itemCount: group.itemCount,
      selectedSenders,
    };
  }

  const whereParts = [];
  const params = [];
  for (const value of selectedValues) {
    whereParts.push(`(
      sender_company_identity = ?
      OR lower(trim(sender_company_label)) = ?
      OR lower(trim(sender_company_identity)) = ?
      OR lower(trim(sender_company_label)) LIKE ?
      OR lower(trim(sender_company_identity)) LIKE ?
      OR
      sender_identity = ?
      OR lower(trim(sender_label)) = ?
      OR lower(trim(sender_raw)) = ?
      OR lower(sender_raw) LIKE ?
    )`);
    params.push(value, value, value, `%${value}%`, `%${value}%`, value, value, value, `%${value}%`);
  }

  const rows = getSenderItemRows(`WHERE ${whereParts.join(' OR ')}`, params);
  const items = rowsToSenderItems(rows).filter((item) => (
    selected.has(normalizeSenderLabel(item.senderLabel))
    || selected.has(item.senderIdentity)
    || selected.has(item.senderCompanyIdentity)
    || selectedValues.some((value) => normalizeSenderLabel(item.send).includes(value))
  ));
  const group = buildSenderGroup(selectedSenders.join(' + '), items, allEmails, { previewLimit, itemLimit });

  return {
    receivedRecipients: group.receivedRecipients,
    receivedRecipientCount: group.receivedRecipientCount,
    unreceivedEmails: group.unreceivedEmails,
    unreceivedEmailCount: group.unreceivedEmailCount,
    items: group.items,
    itemCount: group.itemCount,
    selectedSenders,
  };
};

const getMailStats = (options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  const row = mailDb.prepare(`
    SELECT COUNT(*) AS total, COUNT(DISTINCT sr.account_email) AS accounts, MAX(sr.updated_at) AS last_fetched_at
    FROM sender_receipts sr
    INNER JOIN accounts_db.account_records ar ON ar.email = sr.account_email
    WHERE ar.owner_username = ? AND ar.status = 'active'
  `).get(ownerUsername);

  return {
    total: row?.total || 0,
    accounts: row?.accounts || 0,
    last_fetched_at: row?.last_fetched_at || '',
    storage: 'sender_receipts',
    database: MAIL_DB_PATH,
  };
};

const normalizeProjectKey = (value = '') => String(value || '').trim();

const inferCompanyLabelFromIdentity = (identity = '') => {
  const clean = String(identity || '').trim();
  if (!clean) return '';
  const domain = clean.startsWith('company:') ? clean.slice('company:'.length) : clean;
  return titleCaseCompany(domain.split('.')[0] || domain);
};

const findSenderCompanyByText = (value = '') => {
  const text = String(value || '').trim();
  if (!text) return null;
  return mailDb.prepare(`
    SELECT sender_company_identity, sender_company_label
    FROM sender_company_stats
    WHERE lower(sender_company_identity) = lower(?)
       OR lower(sender_company_label) = lower(?)
       OR lower(replace(sender_company_label, ' ', '')) = lower(replace(?, ' ', ''))
    ORDER BY item_count DESC
    LIMIT 1
  `).get(text, text, text) || null;
};

const getProjectSenderLookupText = (options = {}) => String(
  options.projectName
  || options.name
  || options.project_name
  || options.project
  || options.projectKey
  || options.key
  || options.id
  || '',
).trim();

const resolveProjectSender = (options = {}) => {
  const directIdentity = String(
    options.senderCompanyIdentity
    || options.senderIdentity
    || options.sender_company_identity
    || '',
  ).trim();
  const directLabel = String(
    options.senderCompanyLabel
    || options.senderLabel
    || options.sender_company_label
    || '',
  ).trim();

  if (directIdentity) {
    const existing = mailDb.prepare(`
      SELECT sender_company_identity, sender_company_label
      FROM sender_company_stats
      WHERE sender_company_identity = ?
    `).get(directIdentity);
    return {
      identity: directIdentity,
      label: directLabel || existing?.sender_company_label || inferCompanyLabelFromIdentity(directIdentity),
    };
  }

  const senderText = String(options.sender || options.senderName || '').trim();
  const projectText = getProjectSenderLookupText(options);
  if (!senderText && !projectText) {
    return { identity: '', label: directLabel };
  }

  const exact = findSenderCompanyByText(senderText || projectText);
  if (exact?.sender_company_identity) {
    return {
      identity: exact.sender_company_identity,
      label: directLabel || exact.sender_company_label || senderText || projectText,
    };
  }

  if (!senderText) {
    return { identity: '', label: directLabel };
  }

  const inferred = getSenderCompany(senderText);
  return {
    identity: inferred.identity || '',
    label: directLabel || inferred.label || senderText,
  };
};

const toList = (value) => {
  if (Array.isArray(value)) return value.map((item) => String(item || '').trim()).filter(Boolean);
  const text = String(value || '').trim();
  if (!text) return [];
  return text.split(',').map((item) => item.trim()).filter(Boolean);
};

const resolveProjectSenders = (options = {}) => {
  const identities = toList(options.senderCompanyIdentities || options.senderIdentities || options.sender_company_identities);
  const labels = toList(options.senderCompanyLabels || options.senderLabels || options.sender_company_labels);

  if (identities.length > 0) {
    const seen = new Set();
    return identities.map((identity, index) => {
      const sender = resolveProjectSender({
        senderCompanyIdentity: identity,
        senderCompanyLabel: labels[index] || '',
      });
      return sender;
    }).filter((sender) => {
      if (!sender.identity || seen.has(sender.identity)) return false;
      seen.add(sender.identity);
      return true;
    });
  }

  const single = resolveProjectSender(options);
  return single.identity ? [single] : [];
};

const normalizeProjectSenders = (senders = []) => senders
  .map((sender) => ({
    identity: String(sender?.identity || '').trim(),
    label: String(sender?.label || '').trim(),
  }))
  .filter((sender) => sender.identity)
  .sort((left, right) => left.identity.localeCompare(right.identity));

const areProjectSendersEqual = (left = [], right = []) => {
  const normalizedLeft = normalizeProjectSenders(left);
  const normalizedRight = normalizeProjectSenders(right);
  if (normalizedLeft.length !== normalizedRight.length) return false;
  return normalizedLeft.every((sender, index) => (
    sender.identity === normalizedRight[index].identity
    && sender.label === normalizedRight[index].label
  ));
};

const getProjectSendersFromRecord = (project = {}) => {
  const identities = project.senderCompanyIdentities?.length
    ? project.senderCompanyIdentities
    : toList(project.senderCompanyIdentity);
  const labels = project.senderCompanyLabels?.length
    ? project.senderCompanyLabels
    : toList(project.senderCompanyLabel);
  return identities.map((identity, index) => ({
    identity,
    label: labels[index] || inferCompanyLabelFromIdentity(identity),
  }));
};

const hasProjectSenderConfig = (options = {}) => [
  'senderCompanyIdentities',
  'senderIdentities',
  'sender_company_identities',
  'senderCompanyLabels',
  'senderLabels',
  'sender_company_labels',
  'senderCompanyIdentity',
  'senderIdentity',
  'sender_company_identity',
  'senderCompanyLabel',
  'senderLabel',
  'sender_company_label',
  'sender',
  'senderName',
].some((field) => Object.prototype.hasOwnProperty.call(options, field));

const getProjectSenderRows = (projectKey) => mailDb.prepare(`
  SELECT sender_company_identity, sender_company_label
  FROM project_sender_identities
  WHERE project_key = ?
  ORDER BY sender_company_label ASC, sender_company_identity ASC
`).all(projectKey);

const getProjectSenderIdentities = (project) => {
  if (!project?.projectKey) return [];
  const storageKey = project.storageProjectKey || project.projectKey;
  const rows = getProjectSenderRows(storageKey);
  if (rows.length > 0) return rows.map((row) => row.sender_company_identity).filter(Boolean);
  return project.senderCompanyIdentity ? [project.senderCompanyIdentity] : [];
};

const syncProjectSenderIdentities = (projectKey, senders, timestamp = nowIso()) => {
  const existing = getProjectSenderRows(projectKey).map((row) => ({
    identity: row.sender_company_identity,
    label: row.sender_company_label,
  }));
  if (areProjectSendersEqual(existing, senders)) return;

  mailDb.prepare(`DELETE FROM project_sender_identities WHERE project_key = ?`).run(projectKey);
  const insert = mailDb.prepare(`
    INSERT INTO project_sender_identities (
      project_key, sender_company_identity, sender_company_label, created_at, updated_at
    )
    VALUES (?, ?, ?, ?, ?)
  `);
  for (const sender of senders) {
    if (!sender.identity) continue;
    insert.run(projectKey, sender.identity, sender.label || inferCompanyLabelFromIdentity(sender.identity), timestamp, timestamp);
  }
};

const upsertProject = (options = {}) => {
  const scoped = resolveScopedProjectOptions(options);
  const projectKey = scoped.projectKey;
  if (!projectKey) {
    throw new Error('project is required');
  }

  const senders = resolveProjectSenders(scoped);
  const sender = senders[0] || { identity: '', label: '' };
  const projectName = String(scoped.projectName || scoped.name || scoped.project_name || scoped.userProjectKey || unscopeProjectKey(projectKey)).trim();
  const timestamp = nowIso();

  runTransaction(mailDb, () => {
    mailDb.prepare(`
      INSERT INTO project_records (
        project_key, project_name, sender_company_identity, sender_company_label, created_at, updated_at, owner_username
      )
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(project_key) DO UPDATE SET
        project_name = excluded.project_name,
        sender_company_identity = excluded.sender_company_identity,
        sender_company_label = excluded.sender_company_label,
        updated_at = excluded.updated_at,
        owner_username = excluded.owner_username
    `).run(projectKey, projectName, sender.identity, sender.label, timestamp, timestamp, scoped.ownerUsername);

    if (hasProjectSenderConfig(scoped)) {
      syncProjectSenderIdentities(projectKey, senders, timestamp);
    }
  });

  return getProject(scoped.userProjectKey || unscopeProjectKey(projectKey), { ownerUsername: scoped.ownerUsername });
};

const getProject = (projectKey, options = {}) => {
  const scoped = resolveScopedProjectOptions({
    ...options,
    projectKey: projectKey || options.projectKey || options.project || options.key || options.id,
  });
  const key = scoped.projectKey;
  if (!key) return null;
  const row = mailDb.prepare(`
    SELECT project_key, project_name, sender_company_identity, sender_company_label, created_at, updated_at, owner_username
    FROM project_records
    WHERE project_key = ? AND owner_username = ?
  `).get(key, scoped.ownerUsername);

  if (!row) return null;
  return decorateProjectRecord(row);
};

const ensureProject = (options = {}) => {
  const scoped = resolveScopedProjectOptions(options);
  const key = scoped.userProjectKey;
  const existing = getProject(key, { ownerUsername: scoped.ownerUsername });
  const hasNameUpdate = ['projectName', 'name', 'project_name'].some((field) => Object.prototype.hasOwnProperty.call(scoped, field));
  const hasSenderUpdate = hasProjectSenderConfig(scoped);
  const existingSenders = existing ? getProjectSendersFromRecord(existing) : [];
  const inferredSender = existing && !hasSenderUpdate && existingSenders.length === 0
    ? resolveProjectSender(scoped)
    : null;
  const shouldApplyInferredSender = Boolean(inferredSender?.identity);
  if (existing && (hasNameUpdate || hasSenderUpdate || shouldApplyInferredSender)) {
    const nextName = hasNameUpdate
      ? String(scoped.projectName || scoped.name || scoped.project_name || key).trim()
      : existing.projectName;
    const nextSenders = hasSenderUpdate
      ? resolveProjectSenders(scoped)
      : (shouldApplyInferredSender ? [inferredSender] : existingSenders);
    const sameName = nextName === existing.projectName;
    const sameSenders = areProjectSendersEqual(existingSenders, nextSenders);
    return sameName && sameSenders
      ? existing
      : upsertProject(shouldApplyInferredSender && !hasSenderUpdate
        ? {
          ...scoped,
          senderCompanyIdentity: inferredSender.identity,
          senderCompanyLabel: inferredSender.label,
        }
        : scoped);
  }
  if (existing) return existing;
  return upsertProject(scoped);
};

const getProjectOrKnownSenderProject = (projectKey, options = {}) => {
  const scoped = resolveScopedProjectOptions({ ...options, projectKey });
  const key = scoped.userProjectKey;
  if (!key) return null;

  const existing = getProject(key, { ownerUsername: scoped.ownerUsername });
  if (existing) {
    return ensureProject({ ...scoped, project: key });
  }

  const sender = resolveProjectSender({ project: key });
  if (!sender.identity) return null;
  return upsertProject({
    ...scoped,
    project: key,
    projectName: key,
    senderCompanyIdentity: sender.identity,
    senderCompanyLabel: sender.label,
  });
};

const listProjects = (options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  return mailDb.prepare(`
    SELECT
      pr.project_key,
      pr.project_name,
      pr.sender_company_identity,
      pr.sender_company_label,
      pr.created_at,
      pr.updated_at,
      (
        SELECT COUNT(DISTINCT account_email)
        FROM project_account_marks
        WHERE project_key = pr.project_key
      ) AS marked_count,
      (
        SELECT COUNT(DISTINCT account_email)
        FROM project_account_marks
        WHERE project_key = pr.project_key AND status = 'assigned'
      ) AS assigned_count,
      (
        SELECT COUNT(DISTINCT account_email)
        FROM project_account_marks
        WHERE project_key = pr.project_key AND status = 'checked'
      ) AS checked_count,
      (
        SELECT COUNT(DISTINCT account_email)
        FROM project_account_marks
        WHERE project_key = pr.project_key AND status = 'received'
      ) AS received_count,
      (
        SELECT COUNT(DISTINCT account_email)
        FROM project_account_marks
        WHERE project_key = pr.project_key AND status = 'error'
      ) AS error_count
    FROM project_records pr
    WHERE pr.owner_username = ?
    ORDER BY pr.updated_at DESC, pr.project_key ASC
  `).all(ownerUsername).map((row) => {
    const senderRows = getProjectSenderRows(row.project_key);
    return {
      projectKey: unscopeProjectKey(row.project_key),
      projectName: row.project_name,
      senderCompanyIdentity: row.sender_company_identity,
      senderCompanyLabel: row.sender_company_label,
      senderCompanyIdentities: senderRows.length > 0
        ? senderRows.map((item) => item.sender_company_identity)
        : toList(row.sender_company_identity),
      senderCompanyLabels: senderRows.length > 0
        ? senderRows.map((item) => item.sender_company_label)
        : toList(row.sender_company_label),
      markedCount: Number(row.marked_count) || 0,
      assignedCount: Number(row.assigned_count) || 0,
      checkedCount: Number(row.checked_count) || 0,
      receivedCount: Number(row.received_count) || 0,
      errorCount: Number(row.error_count) || 0,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
    };
  });
};

const getAccountByEmail = (email, options = {}) => {
  const { ownerUsername } = resolveOwnerOptions(options);
  const accountEmail = normalizeEmail(email);
  if (!accountEmail) return null;
  return accountsDb.prepare(`
    SELECT record_key, email, password, client_id, refresh_token, raw_line, source, status, imported_at, updated_at, owner_username
    FROM account_records
    WHERE lower(email) = lower(?) AND status = 'active' AND owner_username = ?
    ORDER BY updated_at DESC
    LIMIT 1
  `).get(accountEmail, ownerUsername) || null;
};

const hasAccountReceivedProjectSender = (project, email) => {
  const senderIdentities = getProjectSenderIdentities(project);
  if (senderIdentities.length === 0) return false;
  const row = mailDb.prepare(`
    SELECT 1 AS found
    FROM sender_company_recipients
    WHERE sender_company_identity IN (${senderIdentities.map(() => '?').join(', ')})
      AND account_email = ?
    LIMIT 1
  `).get(...senderIdentities, normalizeEmail(email));
  return Boolean(row?.found);
};

const normalizeAcquireMode = (options = {}) => {
  if (parseBoolean(options.reuseReceived) || parseBoolean(options.receivedOnly)) return 'received';
  if (parseBoolean(options.retryAssigned) || parseBoolean(options.retry) || parseBoolean(options.second)) return 'retry';

  const raw = String(
    options.mode
    || options.acquireMode
    || options.pool
    || options.type
    || '',
  ).trim().toLowerCase();

  if (['received', 'reuse', 'used', 'done', 'completed', 'old'].includes(raw)) return 'received';
  if (['retry', 'second', 'again', 'assigned', 'checked', 'unfinished'].includes(raw)) return 'retry';
  return 'new';
};

const buildProjectAccountPayload = (account, project, { mode, claim, includeRaw }) => {
  const accountEmail = normalizeEmail(account.email);
  const receivedByProjectSender = hasAccountReceivedProjectSender(project, accountEmail);
  const markStatus = account.project_status
    || (claim ? (mode === 'received' || receivedByProjectSender ? 'received' : 'assigned') : '');
  return {
    project,
    mode,
    available: true,
    claimed: claim,
    reused: mode !== 'new',
    receivedByProjectSender,
    markStatus,
    latestCode: account.latest_code || '',
    latestSender: account.latest_sender || '',
    latestMailbox: account.latest_mailbox || '',
    latestReceivedAt: account.latest_received_at || '',
    lastCheckedAt: account.last_checked_at || '',
    account: {
      email: accountEmail,
      source: account.source,
      updatedAt: account.updated_at,
      ...(includeRaw ? { rawLine: account.raw_line } : {}),
    },
  };
};

const buildProjectAccountUnavailable = (project, mode, reason) => ({
  project,
  mode,
  account: null,
  available: false,
  claimed: false,
  reused: mode !== 'new',
  reason,
});

const recordProjectAccountAssignment = ({ project, account, mode, timestamp }) => {
  const accountEmail = normalizeEmail(account.email);
  const shouldKeepReceived = mode === 'received'
    || account.project_status === 'received'
    || hasAccountReceivedProjectSender(project, accountEmail);
  const nextStatus = shouldKeepReceived ? 'received' : 'assigned';

  mailDb.prepare(`
    INSERT INTO project_account_marks (
      project_key, account_email, status, assigned_count, first_assigned_at, last_assigned_at, updated_at
    )
    VALUES (?, ?, ?, 1, ?, ?, ?)
    ON CONFLICT(project_key, account_email) DO UPDATE SET
      status = CASE
        WHEN excluded.status = 'received' OR project_account_marks.status = 'received' THEN 'received'
        ELSE excluded.status
      END,
      assigned_count = project_account_marks.assigned_count + 1,
      last_assigned_at = excluded.last_assigned_at,
      updated_at = excluded.updated_at
  `).run(project.storageProjectKey || project.projectKey, accountEmail, nextStatus, timestamp, timestamp, timestamp);
};

const acquireProjectAccount = (options = {}) => {
  const scoped = resolveScopedProjectOptions(options);
  const project = ensureProject(scoped);
  const mode = normalizeAcquireMode(scoped);
  const includeRaw = parseBoolean(scoped.includeRaw);
  const claim = !(scoped.claim === false || scoped.claim === 'false' || scoped.claim === '0');
  const timestamp = nowIso();
  const senderIdentities = getProjectSenderIdentities(project);
  const activeAccountWhere = [
    `ar.status = 'active'`,
    `ar.owner_username = ?`,
    `ar.email <> ''`,
    `ar.client_id <> ''`,
    `ar.refresh_token <> ''`,
  ];
  const ownerParam = [scoped.ownerUsername];
  const selectFields = `
    ar.record_key,
    ar.email,
    ar.password,
    ar.client_id,
    ar.refresh_token,
    ar.raw_line,
    ar.source,
    ar.updated_at,
    pam.status AS project_status,
    pam.latest_code,
    pam.latest_sender,
    pam.latest_mailbox,
    pam.latest_received_at,
    pam.last_checked_at
  `;

  const projectStorageKey = project.storageProjectKey || project.projectKey;

  const buildNewSelector = () => {
    const params = [...ownerParam];
    const whereParts = [...activeAccountWhere];

    if (senderIdentities.length > 0) {
      whereParts.push(`NOT EXISTS (
        SELECT 1
        FROM sender_company_recipients scr
        WHERE scr.sender_company_identity IN (${senderIdentities.map(() => '?').join(', ')})
          AND scr.account_email = ar.email
        LIMIT 1
      )`);
      params.push(...senderIdentities);
    }

    whereParts.push(`NOT EXISTS (
      SELECT 1
      FROM project_account_marks existing_mark
      WHERE existing_mark.project_key = ?
        AND existing_mark.account_email = ar.email
      LIMIT 1
    )`);
    params.push(projectStorageKey);

    return {
      sql: `
        SELECT ${selectFields}
        FROM accounts_db.account_records ar
        LEFT JOIN project_account_marks pam
          ON pam.project_key = ?
         AND pam.account_email = ar.email
        WHERE ${whereParts.join(' AND ')}
        ORDER BY random()
        LIMIT 1
      `,
      params: [projectStorageKey, ...params],
      unavailableReason: 'no_available_account',
    };
  };

  const buildReceivedSelector = () => {
    const params = [...ownerParam];
    const whereParts = [...activeAccountWhere];
    if (senderIdentities.length > 0) {
      whereParts.push(`EXISTS (
        SELECT 1
        FROM sender_company_recipients scr
        WHERE scr.sender_company_identity IN (${senderIdentities.map(() => '?').join(', ')})
          AND scr.account_email = ar.email
        LIMIT 1
      )`);
      params.push(...senderIdentities);
    } else {
      whereParts.push(`0`);
    }

    // Project mailbox acquisition is one-time per project/email. Any existing
    // mark means this mailbox has already been exposed for this project.
    whereParts.push(`NOT EXISTS (
      SELECT 1
      FROM project_account_marks existing_mark
      WHERE existing_mark.project_key = ?
        AND existing_mark.account_email = ar.email
      LIMIT 1
    )`);
    params.push(projectStorageKey);

    return {
      sql: `
        SELECT ${selectFields}
        FROM accounts_db.account_records ar
        LEFT JOIN project_account_marks pam
          ON pam.project_key = ?
         AND pam.account_email = ar.email
        WHERE ${whereParts.join(' AND ')}
        ORDER BY random()
        LIMIT 1
      `,
      params: [projectStorageKey, ...params],
      unavailableReason: 'no_received_account',
    };
  };

  const buildRetrySelector = () => ({
    sql: `
      SELECT ${selectFields}
      FROM accounts_db.account_records ar
      LEFT JOIN project_account_marks pam
        ON pam.project_key = ?
       AND pam.account_email = ar.email
      WHERE 0
      LIMIT 1
    `,
    params: [projectStorageKey],
    unavailableReason: 'no_retry_account',
  });

  const selector = mode === 'received'
    ? buildReceivedSelector()
    : mode === 'retry'
      ? buildRetrySelector()
      : buildNewSelector();
  const selectCandidate = () => mailDb.prepare(selector.sql).get(...selector.params);

  if (!claim) {
    const account = selectCandidate();
    return account
      ? buildProjectAccountPayload(account, project, { mode, claim, includeRaw })
      : buildProjectAccountUnavailable(project, mode, selector.unavailableReason);
  }

  return runImmediateTransaction(mailDb, () => {
    const account = selectCandidate();
    if (!account) return buildProjectAccountUnavailable(project, mode, selector.unavailableReason);

    recordProjectAccountAssignment({ project, account, mode, timestamp });
    return buildProjectAccountPayload(account, project, { mode, claim, includeRaw });
  });
};

const listProjectMarks = (projectKey, options = {}) => {
  const scoped = resolveScopedProjectOptions({ ...options, projectKey });
  const key = scoped.projectKey;
  const status = String(options.status || '').trim();
  const limit = toPositiveInt(options.limit, 100, 1000);
  const whereParts = [`project_key = ?`];
  const params = [key];
  if (status) {
    whereParts.push(`status = ?`);
    params.push(status);
  }
  return mailDb.prepare(`
    SELECT project_key, account_email, status, assigned_count, latest_code, latest_sender,
           latest_mailbox, latest_received_at, last_error, first_assigned_at, last_assigned_at,
           last_checked_at, updated_at
    FROM project_account_marks
    WHERE ${whereParts.join(' AND ')}
    ORDER BY updated_at DESC
    LIMIT ?
  `).all(...params, limit).map((row) => ({
    projectKey: unscopeProjectKey(row.project_key),
    email: row.account_email,
    status: row.status,
    assignedCount: Number(row.assigned_count) || 0,
    latestCode: row.latest_code,
    latestSender: row.latest_sender,
    latestMailbox: row.latest_mailbox,
    latestReceivedAt: row.latest_received_at,
    lastError: row.last_error,
    firstAssignedAt: row.first_assigned_at,
    lastAssignedAt: row.last_assigned_at,
    lastCheckedAt: row.last_checked_at,
    updatedAt: row.updated_at,
  }));
};

const getProjectAccountStatus = (projectKey, email, options = {}) => {
  const scoped = resolveScopedProjectOptions({ ...options, projectKey });
  const key = scoped.projectKey;
  const accountEmail = normalizeEmail(email);
  if (!key || !accountEmail) return null;
  const project = getProjectOrKnownSenderProject(scoped.userProjectKey, { ownerUsername: scoped.ownerUsername });
  const mark = mailDb.prepare(`
    SELECT project_key, account_email, status, assigned_count, latest_code, latest_sender,
           latest_mailbox, latest_received_at, last_error, first_assigned_at, last_assigned_at,
           last_checked_at, updated_at
    FROM project_account_marks
    WHERE project_key = ?
      AND lower(account_email) = lower(?)
  `).get(key, accountEmail);

  return {
    project,
    email: accountEmail,
    receivedByProjectSender: hasAccountReceivedProjectSender(project, accountEmail),
    mark: mark ? {
      projectKey: unscopeProjectKey(mark.project_key),
      email: mark.account_email,
      status: mark.status,
      assignedCount: Number(mark.assigned_count) || 0,
      latestCode: mark.latest_code,
      latestSender: mark.latest_sender,
      latestMailbox: mark.latest_mailbox,
      latestReceivedAt: mark.latest_received_at,
      lastError: mark.last_error,
      firstAssignedAt: mark.first_assigned_at,
      lastAssignedAt: mark.last_assigned_at,
      lastCheckedAt: mark.last_checked_at,
      updatedAt: mark.updated_at,
    } : null,
  };
};

const getProjectMarkCounts = (projectKey) => {
  const key = normalizeProjectKey(projectKey);
  const row = mailDb.prepare(`
    SELECT
      COUNT(DISTINCT account_email) AS marked_count,
      COUNT(DISTINCT CASE WHEN status = 'assigned' THEN account_email END) AS assigned_count,
      COUNT(DISTINCT CASE WHEN status = 'checked' THEN account_email END) AS checked_count,
      COUNT(DISTINCT CASE WHEN status = 'received' THEN account_email END) AS received_count,
      COUNT(DISTINCT CASE WHEN status = 'error' THEN account_email END) AS error_count,
      MAX(updated_at) AS latest_updated_at
    FROM project_account_marks
    WHERE project_key = ?
  `).get(key);

  return {
    markedCount: Number(row?.marked_count) || 0,
    assignedCount: Number(row?.assigned_count) || 0,
    checkedCount: Number(row?.checked_count) || 0,
    receivedCount: Number(row?.received_count) || 0,
    errorCount: Number(row?.error_count) || 0,
    latestUpdatedAt: row?.latest_updated_at || '',
  };
};

const projectActiveAccountWhere = [
  `ar.status = 'active'`,
  `ar.email <> ''`,
  `ar.client_id <> ''`,
  `ar.refresh_token <> ''`,
];

const countProjectKnownMailboxes = (project) => {
  const senderIdentities = getProjectSenderIdentities(project);
  const params = [project.projectKey];
  const parts = [`SELECT account_email FROM project_account_marks WHERE project_key = ?`];
  if (senderIdentities.length > 0) {
    parts.push(`
      SELECT account_email
      FROM sender_company_recipients
      WHERE sender_company_identity IN (${senderIdentities.map(() => '?').join(', ')})
    `);
    params.push(...senderIdentities);
  }

  const row = mailDb.prepare(`
    WITH project_accounts AS (
      ${parts.join('\nUNION\n')}
    )
    SELECT COUNT(DISTINCT account_email) AS total
    FROM project_accounts
    WHERE account_email <> ''
  `).get(...params);
  return Number(row?.total) || 0;
};

const countProjectNewAvailableAccounts = (project) => {
  const senderIdentities = getProjectSenderIdentities(project);
  const params = [];
  const whereParts = [...projectActiveAccountWhere];

  if (senderIdentities.length > 0) {
    whereParts.push(`
      NOT EXISTS (
        SELECT 1
        FROM sender_company_recipients scr
        WHERE scr.sender_company_identity IN (${senderIdentities.map(() => '?').join(', ')})
          AND scr.account_email = ar.email
        LIMIT 1
      )
    `);
    params.push(...senderIdentities);
  }

  whereParts.push(`
    NOT EXISTS (
      SELECT 1
      FROM project_account_marks pam
      WHERE pam.project_key = ?
        AND pam.account_email = ar.email
      LIMIT 1
    )
  `);
  params.push(project.projectKey);

  const row = mailDb.prepare(`
    SELECT COUNT(DISTINCT ar.email) AS total
    FROM accounts_db.account_records ar
    WHERE ${whereParts.join(' AND ')}
  `).get(...params);
  return Number(row?.total) || 0;
};

const countProjectReusableReceivedAccounts = (project) => {
  const senderIdentities = getProjectSenderIdentities(project);
  if (senderIdentities.length === 0) return 0;
  const params = [project.projectKey, ...senderIdentities, project.projectKey];

  const row = mailDb.prepare(`
    SELECT COUNT(DISTINCT ar.email) AS total
    FROM accounts_db.account_records ar
    LEFT JOIN project_account_marks pam
      ON pam.project_key = ?
     AND pam.account_email = ar.email
    WHERE ${projectActiveAccountWhere.join(' AND ')}
      AND EXISTS (
        SELECT 1
        FROM sender_company_recipients scr
        WHERE scr.sender_company_identity IN (${senderIdentities.map(() => '?').join(', ')})
          AND scr.account_email = ar.email
        LIMIT 1
      )
      AND NOT EXISTS (
        SELECT 1
        FROM project_account_marks existing_mark
        WHERE existing_mark.project_key = ?
          AND existing_mark.account_email = ar.email
        LIMIT 1
      )
  `).get(...params);
  return Number(row?.total) || 0;
};

const countProjectRetryableAccounts = (project) => {
  return 0;
};

const listProjectMailboxRows = (project, options = {}) => {
  const senderIdentities = getProjectSenderIdentities(project);
  const limit = toPositiveInt(options.limit, 100, 1000);
  const status = String(options.status || '').trim();
  const params = [project.projectKey];
  const projectAccountParts = [`SELECT account_email FROM project_account_marks WHERE project_key = ?`];

  if (senderIdentities.length > 0) {
    projectAccountParts.push(`
      SELECT account_email
      FROM sender_company_recipients
      WHERE sender_company_identity IN (${senderIdentities.map(() => '?').join(', ')})
    `);
    params.push(...senderIdentities);
  }

  const senderJoin = senderIdentities.length > 0
    ? `scr.account_email = pa.account_email AND scr.sender_company_identity IN (${senderIdentities.map(() => '?').join(', ')})`
    : `0`;
  if (senderIdentities.length > 0) {
    params.push(...senderIdentities);
  }

  const whereSql = status ? `WHERE status = ?` : '';
  if (status) params.push(status);
  params.push(limit);

  return mailDb.prepare(`
    WITH project_accounts AS (
      ${projectAccountParts.join('\nUNION\n')}
    ),
    active_accounts AS (
      SELECT
        email,
        MAX(source) AS source,
        MAX(updated_at) AS updated_at,
        MAX(CASE
          WHEN status = 'active' AND email <> '' AND client_id <> '' AND refresh_token <> ''
          THEN 1 ELSE 0
        END) AS active
      FROM accounts_db.account_records
      GROUP BY email
    ),
    enriched AS (
      SELECT
        pa.account_email,
        COALESCE(ar.active, 0) AS active,
        COALESCE(ar.source, '') AS account_source,
        COALESCE(ar.updated_at, '') AS account_updated_at,
        COALESCE(pam.status, '') AS mark_status,
        CASE
          WHEN COALESCE(pam.status, '') <> '' THEN pam.status
          WHEN COUNT(scr.sender_company_identity) > 0 THEN 'received'
          ELSE 'unmarked'
        END AS status,
        COALESCE(pam.assigned_count, 0) AS assigned_count,
        COALESCE(pam.latest_code, '') AS latest_code,
        COALESCE(pam.latest_sender, '') AS latest_sender,
        COALESCE(pam.latest_mailbox, '') AS latest_mailbox,
        COALESCE(NULLIF(pam.latest_received_at, ''), MAX(COALESCE(scr.latest_date, '')), '') AS latest_received_at,
        COALESCE(pam.last_error, '') AS last_error,
        COALESCE(pam.first_assigned_at, '') AS first_assigned_at,
        COALESCE(pam.last_assigned_at, '') AS last_assigned_at,
        COALESCE(pam.last_checked_at, '') AS last_checked_at,
        COALESCE(pam.updated_at, '') AS updated_at,
        CASE WHEN COUNT(scr.sender_company_identity) > 0 THEN 1 ELSE 0 END AS received_by_project_sender,
        COALESCE(SUM(scr.count), 0) AS project_sender_message_count,
        COALESCE(MAX(scr.latest_date), '') AS project_sender_latest_date
      FROM project_accounts pa
      LEFT JOIN active_accounts ar
        ON ar.email = pa.account_email
      LEFT JOIN project_account_marks pam
        ON pam.project_key = ?
       AND pam.account_email = pa.account_email
      LEFT JOIN sender_company_recipients scr
        ON ${senderJoin}
      WHERE pa.account_email <> ''
      GROUP BY pa.account_email
    )
    SELECT *
    FROM enriched
    ${whereSql}
    ORDER BY
      active DESC,
      COALESCE(NULLIF(updated_at, ''), project_sender_latest_date, account_updated_at, '') DESC,
      account_email ASC
    LIMIT ?
  `).all(...params.slice(0, projectAccountParts.length > 1 ? 1 + senderIdentities.length : 1), project.projectKey, ...params.slice(projectAccountParts.length > 1 ? 1 + senderIdentities.length : 1)).map((row) => ({
    projectKey: project.projectKey,
    email: row.account_email,
    active: Boolean(row.active),
    accountSource: row.account_source,
    accountUpdatedAt: row.account_updated_at,
    status: row.status,
    markStatus: row.mark_status,
    assignedCount: Number(row.assigned_count) || 0,
    latestCode: row.latest_code,
    latestSender: row.latest_sender,
    latestMailbox: row.latest_mailbox,
    latestReceivedAt: row.latest_received_at,
    lastError: row.last_error,
    firstAssignedAt: row.first_assigned_at,
    lastAssignedAt: row.last_assigned_at,
    lastCheckedAt: row.last_checked_at,
    updatedAt: row.updated_at,
    receivedByProjectSender: Boolean(row.received_by_project_sender),
    projectSenderMessageCount: Number(row.project_sender_message_count) || 0,
    projectSenderLatestDate: row.project_sender_latest_date,
  }));
};

const buildProjectMailboxPool = (project, options = {}) => {
  const markCounts = getProjectMarkCounts(project.projectKey);
  const includeRows = options.includeRows === true || parseBoolean(options.includeRows) || Boolean(options.project || options.projectKey || options.key || options.id);
  return {
    project,
    projectKey: project.projectKey,
    projectName: project.projectName,
    senderCompanyIdentity: project.senderCompanyIdentity,
    senderCompanyLabel: project.senderCompanyLabel,
    senderCompanyIdentities: project.senderCompanyIdentities,
    senderCompanyLabels: project.senderCompanyLabels,
    knownMailboxCount: countProjectKnownMailboxes(project),
    markedCount: markCounts.markedCount,
    assignedCount: markCounts.assignedCount,
    checkedCount: markCounts.checkedCount,
    receivedCount: markCounts.receivedCount,
    errorCount: markCounts.errorCount,
    reusableReceivedCount: countProjectReusableReceivedAccounts(project),
    retryableCount: countProjectRetryableAccounts(project),
    newAvailableCount: countProjectNewAvailableAccounts(project),
    latestUpdatedAt: markCounts.latestUpdatedAt || project.updatedAt || '',
    mailboxes: includeRows ? listProjectMailboxRows(project, options) : [],
  };
};

const listProjectMailboxPools = (options = {}) => {
  const scoped = resolveScopedProjectOptions(options);
  const projectKey = scoped.userProjectKey;
  if (projectKey) {
    const project = hasProjectSenderConfig(scoped) || scoped.name || scoped.projectName || scoped.project_name
      ? ensureProject(scoped)
      : getProjectOrKnownSenderProject(projectKey, { ownerUsername: scoped.ownerUsername });
    if (!project) return [];
    return [buildProjectMailboxPool(project, { ...scoped, project: project.projectKey, includeRows: true })];
  }

  return listProjects({ ownerUsername: scoped.ownerUsername }).map((project) => buildProjectMailboxPool(project, scoped));
};

const buildProjectVerificationIgnoreTerms = (project = {}, company = {}) => {
  const terms = [
    project.projectKey,
    project.projectName,
    project.senderCompanyIdentity,
    project.senderCompanyLabel,
    company.identity,
    company.label,
  ];
  if (Array.isArray(project.senderCompanyIdentities)) terms.push(...project.senderCompanyIdentities);
  if (Array.isArray(project.senderCompanyLabels)) terms.push(...project.senderCompanyLabels);
  return terms.filter(Boolean);
};

const summarizeProjectFetchedEmails = (project, emails = []) => {
  const projectSenderIdentities = getProjectSenderIdentities(project);
  const senderIdentities = new Set(projectSenderIdentities);
  const isCursorProject = hasCursorContext({
    project,
    ignoredTerms: projectSenderIdentities,
  });
  const annotated = (Array.isArray(emails) ? emails : [])
    .map((mail) => {
      const company = getSenderCompany(mail?.send || '');
      const verification = extractVerificationCode(mail?.subject || '', mail?.text || '', mail?.html || '', {
        project,
        company,
        ignoredTerms: buildProjectVerificationIgnoreTerms(project, company),
      });
      return addMailLinks({
        ...mail,
        senderCompanyIdentity: company.identity,
        senderCompanyLabel: company.label,
        verificationCode: verification.code,
        verificationSource: verification.source,
      });
    })
    .sort((a, b) => new Date(b.date || 0).getTime() - new Date(a.date || 0).getTime());

  const senderMatched = senderIdentities.size > 0
    ? annotated.filter((mail) => senderIdentities.has(mail.senderCompanyIdentity) || (isCursorProject && isCursorCompanyIdentity(mail.senderCompanyIdentity)))
    : (isCursorProject ? annotated.filter(isCursorSenderMail) : annotated);
  const matched = isCursorProject
    ? senderMatched.filter(isCursorVerificationMail)
    : senderMatched;
  const hasStrongMatchedCode = matched.some((mail) => mail.verificationCode && isStrongVerificationSource(mail.verificationSource));
  const normalizedMatched = hasStrongMatchedCode
    ? matched.map((mail) => (mail.verificationCode && !isStrongVerificationSource(mail.verificationSource)
      ? { ...mail, verificationCode: '', verificationSource: '' }
      : mail))
    : matched;
  const latestMatched = matched[0] || null;
  const latestWithCode = normalizedMatched.find((mail) => mail.verificationCode) || latestMatched;
  const exactActionLinkPattern = /^(verify\s+email|verification\s+link|confirm\s+email|confirm\s+your\s+email|confirm\s+signup|activate\s+account|activate\s+email)$/i;
  const exactActionLinkMail = normalizedMatched.find((mail) => {
    const detail = getPrimaryMailLink(mail.linkDetails);
    const label = `${mail.primaryLinkText || mail.primaryLinkLabel || detail?.text || detail?.label || ''}`.trim();
    return exactActionLinkPattern.test(label);
  });
  const actionLinkMail = normalizedMatched.find((mail) => {
    const detail = getPrimaryMailLink(mail.linkDetails);
    return detail?.isAction || (Array.isArray(mail.verificationLinks) && mail.verificationLinks.length > 0);
  });
  const latestWithLink = exactActionLinkMail
    || actionLinkMail
    || null;
  const latestLink = latestWithLink?.primaryLink || getPrimaryMailLink(latestWithLink?.linkDetails)?.url || '';
  const latestLinkDetail = getPrimaryMailLink(latestWithLink?.linkDetails) || null;
  const latestLinkText = latestWithLink?.primaryLinkText || latestWithLink?.primaryLinkLabel || latestLinkDetail?.text || latestLinkDetail?.label || '';

  return {
    annotated,
    matched: normalizedMatched,
    received: normalizedMatched.length > 0,
    latestCode: dbText(latestWithCode?.verificationCode),
    latestLink: dbText(latestLink),
    latestLinkText: dbText(latestLinkText),
    latestSender: dbText(latestMatched?.send),
    latestMailbox: dbText(latestMatched?.mailbox),
    latestReceivedAt: dbText(latestMatched?.date),
  };
};

const recordProjectMailResult = ({ projectKey, email, emails = [], error = '', ...projectOptions }) => {
  const project = ensureProject({ ...projectOptions, projectKey });
  const accountEmail = normalizeEmail(email);
  const timestamp = nowIso();
  const summary = summarizeProjectFetchedEmails(project, emails);
  const status = error
    ? (summary.received ? 'received' : 'error')
    : (summary.received ? 'received' : 'checked');

  mailDb.prepare(`
    INSERT INTO project_account_marks (
      project_key, account_email, status, assigned_count, latest_code, latest_sender,
      latest_mailbox, latest_received_at, last_error, last_checked_at, updated_at
    )
    VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(project_key, account_email) DO UPDATE SET
      status = excluded.status,
      latest_code = excluded.latest_code,
      latest_sender = excluded.latest_sender,
      latest_mailbox = excluded.latest_mailbox,
      latest_received_at = excluded.latest_received_at,
      last_error = excluded.last_error,
      last_checked_at = excluded.last_checked_at,
      updated_at = excluded.updated_at
  `).run(
    dbText(project.projectKey),
    dbText(accountEmail),
    dbText(status),
    dbText(summary.latestCode),
    dbText(summary.latestSender),
    dbText(summary.latestMailbox),
    dbText(summary.latestReceivedAt),
    String(error || ''),
    timestamp,
    timestamp,
  );

  return {
    project,
    email: accountEmail,
    status,
    received: summary.received,
    latestCode: summary.latestCode,
    latestLink: summary.latestLink,
    latestLinkText: summary.latestLinkText,
    matchedCount: summary.matched.length,
    messageCount: summary.annotated.length,
    matched: summary.matched,
    messages: summary.annotated,
  };
};

const summarizeProjectMailResult = ({ projectKey, emails = [], ...projectOptions }) => {
  const project = ensureProject({ ...projectOptions, projectKey });
  const summary = summarizeProjectFetchedEmails(project, emails);
  return {
    project,
    received: summary.received,
    latestCode: summary.latestCode,
    latestLink: summary.latestLink,
    latestLinkText: summary.latestLinkText,
    matchedCount: summary.matched.length,
    messageCount: summary.annotated.length,
    matched: summary.matched,
    messages: summary.annotated,
  };
};

module.exports = {
  paths: {
    dataDir: DATA_DIR,
    mailDb: MAIL_DB_PATH,
    accountsDb: ACCOUNTS_DB_PATH,
  },
  importAccounts,
  listAccounts,
  listAccountsForFetch,
  getFetchableAccountCount,
  getRecentSuccessfulFetchKeys,
  listAccountEmails,
  getAccountStats,
  backfillSenderCompanies,
  migrateReceivedMessagesToSenderReceipts,
  refreshSenderCompanyStats,
  refreshAllSenderCompanyStats,
  normalizeStoredSenderReceiptDates,
  saveFetchedEmails,
  recordFetchFailure,
  listSenderGroups,
  listSenderGroupsLegacy,
  getSenderOptionRecords,
  filterSenderGroups,
  getMailStats,
  extractVerificationCode,
  getSenderCompany,
  upsertProject,
  listProjects,
  getProject,
  acquireProjectAccount,
  listProjectMailboxPools,
  listProjectMarks,
  getAccountByEmail,
  getProjectAccountStatus,
  summarizeProjectMailResult,
  recordProjectMailResult,
};
