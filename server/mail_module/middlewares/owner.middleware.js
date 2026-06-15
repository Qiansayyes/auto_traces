const { normalizeOwner } = require('../utils/ownerContext');

const INTERNAL_HEADER = 'x-mail-internal-secret';
const OWNER_HEADER = 'x-mail-owner';

const resolveInternalSecret = () => String(process.env.MAIL_INTERNAL_SECRET || 'auto-traces-mail-internal').trim();

const ownerMiddleware = async (ctx, next) => {
  const expectedSecret = resolveInternalSecret();
  const providedSecret = String(ctx.get(INTERNAL_HEADER) || '').trim();
  const owner = normalizeOwner(ctx.get(OWNER_HEADER) || '');

  if (!providedSecret || providedSecret !== expectedSecret || !owner) {
    ctx.status = 401;
    ctx.body = {
      code: '401',
      error: '请先登录主系统后再使用邮箱管理模块。',
    };
    return;
  }

  ctx.state.ownerUsername = owner;
  await next();
};

module.exports = {
  ownerMiddleware,
  INTERNAL_HEADER,
  OWNER_HEADER,
};
