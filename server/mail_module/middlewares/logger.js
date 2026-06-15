const logger = require('../utils/logger')

module.exports = async (ctx, next) => {
  const start = Date.now()
  await next()
  const ms = Date.now() - start
  const logMsg = `${ctx.method} ${ctx.url} - ${ms}ms - ${ctx.status}`
  
  if (ctx.status >= 500) {
    logger.error(logMsg)
  } else if (ctx.status >= 400) {
    logger.warn(logMsg)
  } else {
    logger.info(logMsg)
  }
}
