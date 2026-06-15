const fs = require('fs');
const path = require('path');
const logger = require('../utils/logger');
const { normalizeOwner, requireOwner } = require('../utils/ownerContext');

const TEMPLATES_DIR = path.join(__dirname, '..', 'templates');

if (!fs.existsSync(TEMPLATES_DIR)) {
  fs.mkdirSync(TEMPLATES_DIR, { recursive: true });
}

const getOwnerTemplateDir = (ownerUsername = '') => {
  const owner = requireOwner(ownerUsername);
  const safeOwner = owner.replace(/[^a-zA-Z0-9_\-@.]/g, '_');
  const dir = path.join(TEMPLATES_DIR, safeOwner);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  return dir;
};

const getTemplatePath = (name, ownerUsername = '') => {
  const safeName = String(name || '').replace(/[^a-zA-Z0-9_\-]/g, '_');
  return path.join(getOwnerTemplateDir(ownerUsername), `${safeName}.json`);
};

const saveTemplate = (template, ownerUsername = '') => {
  if (!template.name) {
    throw new Error('Template name is required');
  }

  if (!template.match || !template.extract) {
    throw new Error('Template must have match and extract fields');
  }

  if (!template.extract.pattern) {
    throw new Error('Template extract.pattern is required');
  }

  try {
    new RegExp(template.extract.pattern);
  } catch (e) {
    throw new Error(`Invalid regex pattern: ${e.message}`);
  }

  const templateData = {
    name: template.name,
    description: template.description || '',
    match: {
      sender: template.match.sender || null,
      subject: template.match.subject || null,
    },
    extract: {
      type: template.extract.type || 'regex',
      pattern: template.extract.pattern,
      field: template.extract.field || 'text',
    },
    createdAt: new Date().toISOString(),
  };

  const filePath = getTemplatePath(template.name, ownerUsername);
  fs.writeFileSync(filePath, JSON.stringify(templateData, null, 2), 'utf-8');
  logger.info(`Template saved: ${template.name} owner=${normalizeOwner(ownerUsername)}`);

  return templateData;
};

const getTemplate = (name, ownerUsername = '') => {
  const filePath = getTemplatePath(name, ownerUsername);
  if (!fs.existsSync(filePath)) {
    return null;
  }
  const content = fs.readFileSync(filePath, 'utf-8');
  return JSON.parse(content);
};

const listTemplates = (ownerUsername = '') => {
  const dir = getOwnerTemplateDir(ownerUsername);
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.json'));
  return files.map((f) => {
    const content = fs.readFileSync(path.join(dir, f), 'utf-8');
    try {
      return JSON.parse(content);
    } catch {
      return null;
    }
  }).filter(Boolean);
};

const deleteTemplate = (name, ownerUsername = '') => {
  const filePath = getTemplatePath(name, ownerUsername);
  if (!fs.existsSync(filePath)) {
    return false;
  }
  fs.unlinkSync(filePath);
  logger.info(`Template deleted: ${name} owner=${normalizeOwner(ownerUsername)}`);
  return true;
};

const extractEmailAddress = (senderStr) => {
  if (!senderStr) return '';
  const angleMatch = senderStr.match(/<([^>]+)>/);
  if (angleMatch) return angleMatch[1].toLowerCase().trim();
  return senderStr.toLowerCase().trim();
};

const matchAndExtract = (emails, template) => {
  if (!emails || !emails.length || !template) {
    return null;
  }

  const { match, extract } = template;

  for (const email of emails) {
    if (match.sender) {
      const senderRaw = (email.send || '').toLowerCase();
      const senderAddr = extractEmailAddress(email.send);
      const matchSender = match.sender.toLowerCase().trim();

      const matched = senderAddr === matchSender
        || senderAddr.includes(matchSender)
        || senderRaw.includes(matchSender);

      if (!matched) {
        continue;
      }
    }

    if (match.subject) {
      const subject = (email.subject || '').toLowerCase();
      if (!subject.includes(match.subject.toLowerCase())) {
        continue;
      }
    }

    const field = extract.field === 'html' ? email.html : email.text;
    if (!field) continue;

    try {
      const regex = new RegExp(extract.pattern);
      const result = regex.exec(field);
      if (result && result[1]) {
        return {
          code: result[1],
          email: {
            send: email.send,
            subject: email.subject,
            date: email.date,
          },
        };
      }
    } catch (err) {
      logger.error(`Template regex error: ${err.message}`);
    }
  }

  return null;
};

module.exports = {
  saveTemplate,
  getTemplate,
  listTemplates,
  deleteTemplate,
  matchAndExtract,
};
