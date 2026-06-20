const crypto = require('crypto');

const STANDARD_DELIMITER = '----';

const normalizeEmail = (value = '') => String(value || '').trim().toLowerCase();

const EMAIL_RE = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-z]{2,}/i;
const MS_EMAIL_RE = /[a-zA-Z0-9._%+-]+@(?:outlook|hotmail|live|msn)\.[a-z]{2,}(?:\.[a-z]{2,})?/i;
const CLIENT_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CLIENT_ID_FIND_RE = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

const isRefreshToken = (value = '') => {
  const text = String(value || '').trim();
  if (text.length < 50) return false;
  if (/^M\.C[\w\-.!$%&'()*+,/:;=?@~]+$/i.test(text)) return true;
  if (/^0\.[A-Za-z0-9][\w\-.!$%&'()*+,/:;=?@~]+$/i.test(text)) return true;
  if (text.length >= 80 && /^[\w\-.!$%&'()*+,/:;=?@~]+$/.test(text)) return true;
  return false;
};

const findRefreshToken = (text = '') => {
  const raw = String(text || '');
  const msToken = raw.match(/M\.C[\w\-.!$%&'()*+,/:;=?@~]+/i);
  if (msToken && isRefreshToken(msToken[0])) return msToken[0];
  const zeroToken = raw.match(/0\.[A-Za-z0-9][\w\-.!$%&'()*+,/:;=?@~]+/i);
  if (zeroToken && isRefreshToken(zeroToken[0])) return zeroToken[0];
  const longToken = raw.match(/[\w\-.!$%&'()*+,/:;=?@~]{80,}/);
  if (longToken && isRefreshToken(longToken[0])) return longToken[0];
  return '';
};

const isLikelyPassword = (value = '') => {
  const text = String(value || '').trim();
  if (!text || text.length < 4 || text.length > 128) return false;
  if (EMAIL_RE.test(text) || CLIENT_ID_RE.test(text) || isRefreshToken(text)) return false;
  return true;
};

const classifyPart = (value = '') => {
  const text = String(value || '').trim();
  if (!text) return 'empty';
  if (MS_EMAIL_RE.test(text) || EMAIL_RE.test(text)) return 'email';
  if (CLIENT_ID_RE.test(text)) return 'client_id';
  if (isRefreshToken(text)) return 'refresh_token';
  if (isLikelyPassword(text)) return 'password';
  return 'unknown';
};

const extractEmail = (value = '') => {
  const text = String(value || '').trim();
  const match = text.match(MS_EMAIL_RE) || text.match(EMAIL_RE);
  return match ? normalizeEmail(match[0]) : '';
};

const normalizeSplitSymbol = (splitSymbol = '') => {
  const sym = String(splitSymbol || '').trim();
  return sym === STANDARD_DELIMITER ? sym : STANDARD_DELIMITER;
};

const isUnsafeCustomDelimiter = (splitSymbol = '') => (
  normalizeSplitSymbol(splitSymbol) !== STANDARD_DELIMITER
);

const validateAccountFields = (record = {}) => {
  const email = extractEmail(record.email);
  const password = String(record.password || '').trim();
  const client_id = String(record.client_id || '').trim();
  const refresh_token = String(record.refresh_token || '').trim();

  if (!email) {
    return { ok: false, reason: '邮箱无效或缺失' };
  }
  if (!password) {
    return { ok: false, reason: '缺少邮箱密码' };
  }
  if (!isLikelyPassword(password)) {
    return { ok: false, reason: '邮箱密码识别错误' };
  }
  if (!CLIENT_ID_RE.test(client_id)) {
    return { ok: false, reason: 'client_id 无效（需为 UUID 格式）' };
  }
  if (!isRefreshToken(refresh_token)) {
    return { ok: false, reason: 'refresh_token 无效或过短' };
  }
  if (password === refresh_token || client_id === refresh_token || email === password) {
    return { ok: false, reason: '字段识别冲突，请检查分隔格式' };
  }

  return {
    ok: true,
    record: {
      email,
      password,
      client_id,
      refresh_token,
    },
  };
};

const splitByStandardDelimiter = (rawLine) => {
  if (!rawLine.includes(STANDARD_DELIMITER)) return null;
  const parts = rawLine.split(STANDARD_DELIMITER).map((item) => item.trim()).filter(Boolean);
  return parts.length >= 4 ? parts : null;
};

const splitBySpaces = (rawLine) => {
  const normalized = String(rawLine || '').replace(/\s+/g, ' ').trim();
  const parts = normalized.split(' ').map((item) => item.trim()).filter(Boolean);
  return parts.length >= 4 ? parts : null;
};

const splitLineParts = (line) => {
  const rawLine = String(line || '').trim();
  if (!rawLine) return null;

  const dashParts = splitByStandardDelimiter(rawLine);
  if (dashParts) {
    return { parts: dashParts, joiner: STANDARD_DELIMITER };
  }

  const spaceParts = splitBySpaces(rawLine);
  if (spaceParts) {
    return { parts: spaceParts, joiner: ' ' };
  }

  return null;
};

const parseByOrderedParts = (parts, joiner = STANDARD_DELIMITER) => {
  if (!Array.isArray(parts) || parts.length < 4) return null;

  const candidate = {
    email: parts[0],
    password: parts[1],
    client_id: parts[2],
    refresh_token: parts.slice(3).join(joiner === ' ' ? ' ' : STANDARD_DELIMITER),
  };
  const validated = validateAccountFields(candidate);
  return validated.ok ? validated.record : null;
};

const parseByClassifiedParts = (parts) => {
  const buckets = {
    email: [],
    password: [],
    client_id: [],
    refresh_token: [],
    empty: [],
    unknown: [],
  };

  for (const part of parts) {
    buckets[classifyPart(part)].push(String(part).trim());
  }

  if (buckets.unknown.length > 0) {
    return null;
  }

  const email = buckets.email.find((item) => MS_EMAIL_RE.test(item)) || buckets.email[0];
  if (
    buckets.email.length !== 1
    || buckets.client_id.length !== 1
    || buckets.refresh_token.length !== 1
    || buckets.password.length !== 1
    || !email
  ) {
    return null;
  }

  const validated = validateAccountFields({
    email,
    password: buckets.password[0],
    client_id: buckets.client_id[0],
    refresh_token: buckets.refresh_token[0],
  });
  return validated.ok ? validated.record : null;
};

const stripKnownField = (text, fieldValue) => {
  if (!fieldValue) return text;
  const index = text.indexOf(fieldValue);
  if (index < 0) return text;
  return `${text.slice(0, index)} ${text.slice(index + fieldValue.length)}`;
};

const parseByPatternExtraction = (line) => {
  const rawLine = String(line || '').trim();
  const emailMatch = rawLine.match(MS_EMAIL_RE) || rawLine.match(EMAIL_RE);
  const clientIdMatch = rawLine.match(CLIENT_ID_FIND_RE);
  if (!emailMatch || !clientIdMatch) return null;

  const email = normalizeEmail(emailMatch[0]);
  const client_id = clientIdMatch[0];
  const emailEnd = emailMatch.index + emailMatch[0].length;
  const clientStart = clientIdMatch.index;
  const clientEnd = clientStart + client_id.length;

  let refresh_token = findRefreshToken(rawLine.slice(clientEnd));
  if (!refresh_token) {
    refresh_token = findRefreshToken(rawLine);
  }
  if (!refresh_token) return null;

  const tokenStart = rawLine.indexOf(refresh_token);
  const betweenEmailAndClient = rawLine
    .slice(emailEnd, clientStart)
    .replace(/^[\s-]+/, '')
    .replace(/[\s-]+$/, '');
  const beforeToken = tokenStart > clientEnd
    ? rawLine.slice(clientEnd, tokenStart).replace(/^[\s-]+/, '').replace(/[\s-]+$/, '')
    : '';

  let password = betweenEmailAndClient || beforeToken;
  if (!isLikelyPassword(password)) {
    let remainder = rawLine;
    remainder = stripKnownField(remainder, emailMatch[0]);
    remainder = stripKnownField(remainder, client_id);
    remainder = stripKnownField(remainder, refresh_token);
    password = remainder.replace(/^[\s-]+/, '').replace(/[\s-]+$/, '').trim();
  }

  const validated = validateAccountFields({
    email,
    password,
    client_id,
    refresh_token,
  });
  return validated.ok ? validated.record : null;
};

const buildRawLine = (record, splitSymbol = STANDARD_DELIMITER) => (
  [record.email, record.password, record.client_id, record.refresh_token].join(normalizeSplitSymbol(splitSymbol))
);

const parseAccountLine = (line, splitSymbol = STANDARD_DELIMITER) => {
  const rawLine = String(line || '').trim();
  if (!rawLine) {
    return { error: '空行', rawLine };
  }

  const splitResult = splitLineParts(rawLine);
  if (splitResult) {
    const ordered = parseByOrderedParts(splitResult.parts, splitResult.joiner);
    if (ordered) {
      return { ...ordered, raw_line: rawLine };
    }

    const classified = parseByClassifiedParts(splitResult.parts);
    if (classified) {
      return { ...classified, raw_line: rawLine };
    }
  }

  const extracted = parseByPatternExtraction(rawLine);
  if (extracted) {
    return { ...extracted, raw_line: rawLine };
  }

  return {
    error: '无法识别完整账号信息（需包含邮箱、密码、client_id、refresh_token，仅支持 ---- 或空格分隔）',
    rawLine,
  };
};

const toAccountRecord = (record, splitSymbol = STANDARD_DELIMITER) => {
  if (typeof record === 'string') {
    return parseAccountLine(record, splitSymbol);
  }

  if (!record || typeof record !== 'object') {
    return { error: '无效记录', rawLine: '' };
  }

  const validated = validateAccountFields(record);
  if (!validated.ok) {
    return { error: validated.reason, rawLine: String(record.email || '') };
  }

  return {
    ...validated.record,
    raw_line: buildRawLine(validated.record, splitSymbol),
  };
};

const buildAccountEmailRecordKey = (owner, email) => {
  const normalizedOwner = String(owner || '').trim();
  const normalizedEmail = normalizeEmail(email);
  if (!normalizedOwner || !normalizedEmail) {
    throw new Error('owner and email are required for account record key');
  }
  return crypto.createHash('sha256').update(`${normalizedOwner}|email|${normalizedEmail}`).digest('hex');
};

module.exports = {
  normalizeEmail,
  extractEmail,
  normalizeSplitSymbol,
  isUnsafeCustomDelimiter,
  STANDARD_DELIMITER,
  EMAIL_RE,
  CLIENT_ID_RE,
  isRefreshToken,
  isLikelyPassword,
  validateAccountFields,
  parseAccountLine,
  toAccountRecord,
  buildRawLine,
  buildAccountEmailRecordKey,
};
