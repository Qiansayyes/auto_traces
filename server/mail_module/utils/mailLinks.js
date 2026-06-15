const decodeHtmlEntities = (value = '') => String(value || '')
  .replace(/&amp;/gi, '&')
  .replace(/&lt;/gi, '<')
  .replace(/&gt;/gi, '>')
  .replace(/&quot;/gi, '"')
  .replace(/&#39;/g, "'")
  .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
  .replace(/&#(\d+);/g, (_, num) => String.fromCodePoint(parseInt(num, 10)));

const stripHtml = (value = '') => String(value || '')
  .replace(/<style[\s\S]*?<\/style>/gi, ' ')
  .replace(/<script[\s\S]*?<\/script>/gi, ' ')
  .replace(/<br\s*\/?>/gi, '\n')
  .replace(/<\/(p|div|tr|td|li|h\d|a)>/gi, '\n')
  .replace(/<[^>]*>/g, ' ');

const normalizeUrl = (value = '') => {
  const decoded = decodeHtmlEntities(value)
    .trim()
    .replace(/[\])}>.,;'"，。；、]+$/g, '');
  if (!decoded) return '';

  try {
    const url = new URL(decoded);
    if (!['http:', 'https:'].includes(url.protocol)) return '';
    return url.toString();
  } catch {
    return '';
  }
};

const actionKeywords = [
  'verify', 'verification', 'activate', 'activation', 'confirm', 'confirmation',
  'magic link', 'sign in', 'signin', 'login', 'log in', 'continue', 'start',
  '验证', '校验', '确认', '激活', '登录', '登陆', '继续', '点击',
];

const ignoreKeywords = [
  'unsubscribe', 'preferences', 'manage subscription', 'privacy', 'terms',
  'facebook', 'twitter', 'linkedin', 'instagram', 'youtube', 'tiktok',
  'app store', 'google play', 'apps.apple.com', 'play.google.com',
  'help-center', 'help center', 'contact us', 'support',
];

const ignoredHosts = new Set([
  'fonts.googleapis.com',
  'fonts.gstatic.com',
  'nomad-public-read-asset-us-east-1.s3.amazonaws.com',
]);

const exactActionLabelPattern = /^(verify\s+email|verification\s+link|confirm\s+email|confirm\s+your\s+email|confirm\s+signup|activate\s+account|activate\s+email)$/i;

const cleanLinkText = (value = '') => stripHtml(decodeHtmlEntities(value))
  .replace(/\s+/g, ' ')
  .trim();

const getNearbyActionLabel = (plainText = '', index = 0) => {
  const before = String(plainText || '').slice(Math.max(0, index - 180), index);
  const after = String(plainText || '').slice(index, Math.min(String(plainText || '').length, index + 180));
  const beforeLines = before.split(/\r?\n/).map(cleanLinkText).filter(Boolean).slice(-4).reverse();
  const afterLines = after.split(/\r?\n/).map(cleanLinkText).filter(Boolean).slice(0, 4);
  const candidates = [...beforeLines, ...afterLines];
  return candidates.find((line) => exactActionLabelPattern.test(line)) || '';
};

const classifyLink = ({ url, text = '', context = '', source = 'html' }) => {
  let host = '';
  let path = '';
  try {
    const parsed = new URL(url);
    host = parsed.host.toLowerCase();
    path = `${parsed.pathname} ${parsed.search}`.toLowerCase();
  } catch {
    return { url, host, text, source, score: 0, isAction: false };
  }

  const cleanedText = cleanLinkText(text);
  const haystack = `${cleanedText} ${context} ${host} ${path}`.toLowerCase();
  const targetHaystack = `${cleanedText} ${host} ${path}`.toLowerCase();
  const isStaticAsset = /\.(?:png|jpe?g|gif|webp|svg|css|woff2?|ttf|ico)(?:$|[?\s])/i.test(path)
    || path.includes('/html-asset/')
    || host.includes('nomad-public-read-asset');
  let score = 20;
  if (source === 'text') score += 8;
  if (actionKeywords.some((keyword) => targetHaystack.includes(keyword.toLowerCase()))) score += 80;
  if (/^verify\s+email$/i.test(cleanedText)) score += 45;
  if (/^verification\s+link$/i.test(cleanedText)) score += 35;
  if (/^confirm\s+email$/i.test(cleanedText)) score += 45;
  if (exactActionLabelPattern.test(cleanedText)) score += 20;
  if (/verify|confirm|activate|login|magic|auth/.test(path)) score += 35;
  if (host.includes('e.zenrows.com') || host.includes('zenrows.com')) score += 18;
  if (ignoreKeywords.some((keyword) => haystack.includes(keyword.toLowerCase()))) score -= 80;
  if (ignoredHosts.has(host)) score -= 100;
  if (isStaticAsset) score -= 160;

  return {
    url,
    host,
    text: cleanedText,
    label: cleanedText || host || '链接',
    source,
    score,
    isAction: !isStaticAsset && score >= 60,
  };
};

const addLink = (items, seen, link) => {
  const url = normalizeUrl(link.url);
  if (!url) return;
  if (seen.has(url)) {
    const existing = items.find((item) => item.url === url);
    if (existing && !existing.text && link.text) {
      const upgraded = classifyLink({ ...link, url });
      existing.text = upgraded.text;
      existing.label = upgraded.label;
      existing.score = Math.max(existing.score, upgraded.score);
      existing.isAction = existing.isAction || upgraded.isAction;
    }
    return;
  }
  seen.add(url);
  items.push(classifyLink({ ...link, url }));
};

const extractMailLinkDetails = ({ subject = '', text = '', html = '' } = {}) => {
  const items = [];
  const seen = new Set();

  for (const match of String(html || '').matchAll(/<a\b([^>]*)href\s*=\s*(['"])(.*?)\2([^>]*)>([\s\S]*?)<\/a>/gi)) {
    addLink(items, seen, {
      url: match[3],
      text: match[5],
      context: `${subject} ${match[1] || ''} ${match[4] || ''}`,
      source: 'html',
    });
  }

  for (const match of String(html || '').matchAll(/\bhref\s*=\s*(['"])(.*?)\1/gi)) {
    addLink(items, seen, {
      url: match[2],
      text: '',
      context: subject,
      source: 'html',
    });
  }

  const plainText = `${subject}\n${text}\n${stripHtml(html)}`;
  for (const match of plainText.matchAll(/https?:\/\/[^\s<>"']+/gi)) {
    const index = match.index || 0;
    addLink(items, seen, {
      url: match[0],
      text: getNearbyActionLabel(plainText, index),
      context: plainText.slice(Math.max(0, index - 80), Math.min(plainText.length, index + match[0].length + 80)),
      source: 'text',
    });
  }

  return items;
};

const getPrimaryMailLink = (links = []) => {
  const details = Array.isArray(links) ? links : [];
  return [...details].sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score;
    if (exactActionLabelPattern.test(a.text || '')) return -1;
    if (exactActionLabelPattern.test(b.text || '')) return 1;
    return 0;
  })[0] || null;
};

const addMailLinks = (mail = {}) => {
  const linkDetails = extractMailLinkDetails(mail);
  const primaryLink = getPrimaryMailLink(linkDetails);
  return {
    ...mail,
    links: linkDetails.map((link) => link.url),
    linkDetails,
    verificationLinks: linkDetails.filter((link) => link.isAction).map((link) => link.url),
    primaryLink: primaryLink?.url || '',
    primaryLinkText: primaryLink?.text || primaryLink?.label || '',
    primaryLinkLabel: primaryLink?.label || '',
  };
};

const addMailLinksToCollection = (data) => {
  if (Array.isArray(data)) return data.map(addMailLinks);
  if (Array.isArray(data?.emails)) {
    return {
      ...data,
      emails: data.emails.map(addMailLinks),
      matched: data.matched ? addMailLinks(data.matched) : data.matched,
    };
  }
  return data;
};

module.exports = {
  extractMailLinkDetails,
  getPrimaryMailLink,
  addMailLinks,
  addMailLinksToCollection,
};
