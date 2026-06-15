const authPasswordMiddleware = async (ctx, next) => {
  const internalSecret = String(process.env.MAIL_INTERNAL_SECRET || 'auto-traces-mail-internal').trim();
  const providedSecret = String(ctx.get('x-mail-internal-secret') || '').trim();
  if (providedSecret && providedSecret === internalSecret && String(ctx.get('x-mail-owner') || '').trim()) {
    await next();
    return;
  }

  const payload = ctx.method === "GET" ? ctx.query : (ctx.request.body || {});
  const headerPassword = ctx.get('x-api-password') || '';
  const bearer = (ctx.get('authorization') || '').replace(/^Bearer\s+/i, '').trim();
  const password = payload.password || payload.token || headerPassword || bearer;
  const expectedPassword = process.env.PASSWORD;

  if (expectedPassword && password !== expectedPassword) {
    ctx.status = 401;
    ctx.body = {
      code: "401",
      error:
        "Authentication failed. Please provide valid credentials or contact administrator for access. Refer to API documentation for deployment details.",
    };
    return;
  }

  await next();
};

const authParamsMiddleware = async (ctx, next) => {
  let { refresh_token, client_id, email, mailbox } = ctx.method === "GET" ? ctx.query : ctx.request.body;

  if (!refresh_token || !client_id || !email || !mailbox) {
    ctx.status = 400;
    ctx.body = {
      code: "400",
      error: "Missing required parameters: refresh_token, client_id, email, or mailbox",
    };
    return;
  }

  await next();
};

module.exports = {
  authPasswordMiddleware,
  authParamsMiddleware,
};
