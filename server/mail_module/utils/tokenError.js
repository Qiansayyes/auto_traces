const INVALID_GRANT_PATTERNS = [
  /invalid_grant/i,
  /AADSTS70000/i,
  /AADSTS70008/i,
  /AADSTS50173/i,
  /AADSTS65001/i,
  /AADSTS50057/i,
  /AADSTS50034/i,
  /refresh token.*expired/i,
  /token.*expired/i,
  /credentials.*revoked/i,
];

const TRANSIENT_PATTERNS = [
  /timeout/i,
  /ECONNRESET/i,
  /ENOTFOUND/i,
  /ETIMEDOUT/i,
  /ECONNREFUSED/i,
  /rate limit/i,
  /too many requests/i,
  /\b429\b/,
  /\b502\b/,
  /\b503\b/,
  /\b504\b/,
  /temporarily unavailable/i,
  /Failed to parse JSON/i,
];

const parseOAuthErrorBody = (body = '') => {
  const raw = String(body || '').trim();
  if (!raw) {
    return { error: '', errorDescription: '', errorCodes: [] };
  }
  try {
    const data = JSON.parse(raw);
    return {
      error: String(data.error || '').trim(),
      errorDescription: String(data.error_description || '').trim(),
      errorCodes: Array.isArray(data.error_codes) ? data.error_codes : [],
    };
  } catch {
    return { error: '', errorDescription: raw, errorCodes: [] };
  }
};

const buildErrorSummary = (parsed, body = '') => {
  const parts = [parsed.error, parsed.errorDescription, String(body || '')]
    .map((item) => String(item || '').trim())
    .filter(Boolean);
  return parts.join(' | ').slice(0, 500);
};

const classifyOAuthResponse = (body = '', httpStatus = 0) => {
  const parsed = parseOAuthErrorBody(body);
  const summary = buildErrorSummary(parsed, body);
  const combined = summary.toLowerCase();

  if (
    parsed.error === 'invalid_grant'
    || INVALID_GRANT_PATTERNS.some((pattern) => pattern.test(summary))
  ) {
    return {
      tokenStatus: 'invalid',
      lastTokenError: summary,
      recoverable: false,
    };
  }

  if (
    httpStatus === 429
    || httpStatus >= 500
    || TRANSIENT_PATTERNS.some((pattern) => pattern.test(summary))
  ) {
    return {
      tokenStatus: 'transient',
      lastTokenError: summary,
      recoverable: true,
    };
  }

  if (httpStatus >= 400 || parsed.error) {
    return {
      tokenStatus: 'invalid',
      lastTokenError: summary || `OAuth error (${httpStatus || 'unknown'})`,
      recoverable: false,
    };
  }

  return {
    tokenStatus: 'transient',
    lastTokenError: summary || 'Unknown token error',
    recoverable: true,
  };
};

const classifyTokenError = (error) => {
  if (error?.tokenClassification) {
    return error.tokenClassification;
  }

  const message = String(error?.message || error || '');
  const statusMatch = message.match(/status:\s*(\d+)/i);
  const httpStatus = statusMatch ? Number(statusMatch[1]) : 0;
  const responseMatch = message.match(/response:\s*([\s\S]+)$/i);
  const body = responseMatch ? responseMatch[1].trim() : message;

  if (TRANSIENT_PATTERNS.some((pattern) => pattern.test(message)) && !INVALID_GRANT_PATTERNS.some((pattern) => pattern.test(message))) {
    return {
      tokenStatus: 'transient',
      lastTokenError: message.slice(0, 500),
      recoverable: true,
    };
  }

  return classifyOAuthResponse(body, httpStatus);
};

module.exports = {
  parseOAuthErrorBody,
  classifyOAuthResponse,
  classifyTokenError,
};
