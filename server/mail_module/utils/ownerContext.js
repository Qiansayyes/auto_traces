const crypto = require('crypto');

const normalizeOwner = (value = '') => String(value || '').trim();

const requireOwner = (value = '') => {
  const owner = normalizeOwner(value);
  if (!owner) {
    const error = new Error('owner_username is required');
    error.status = 401;
    throw error;
  }
  return owner;
};

const buildRecordKey = (owner, rawLine) => {
  const normalizedOwner = requireOwner(owner);
  return crypto.createHash('sha256').update(`${normalizedOwner}|${String(rawLine || '')}`).digest('hex');
};

const scopeProjectKey = (owner, projectKey = '') => {
  const normalizedOwner = requireOwner(owner);
  const key = String(projectKey || '').trim();
  if (!key) return '';
  return `${normalizedOwner}::${key}`;
};

const unscopeProjectKey = (scopedKey = '') => {
  const text = String(scopedKey || '');
  const marker = '::';
  const index = text.indexOf(marker);
  if (index < 0) return text;
  return text.slice(index + marker.length);
};

const ownerAccountExistsSql = (alias = 'ar') => (
  `EXISTS (
    SELECT 1
    FROM accounts_db.account_records owner_ar
    WHERE owner_ar.email = ${alias}.account_email
      AND owner_ar.status = 'active'
      AND owner_ar.owner_username = ?
  )`
);

module.exports = {
  normalizeOwner,
  requireOwner,
  buildRecordKey,
  scopeProjectKey,
  unscopeProjectKey,
  ownerAccountExistsSql,
};
