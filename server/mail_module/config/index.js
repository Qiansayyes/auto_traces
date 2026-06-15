const path = require('path')

require('dotenv').config({ path: process.env.MAIL_MODULE_ENV_FILE || path.join(__dirname, '..', '.env') })

module.exports = {
  env: process.env.NODE_ENV || 'development',
  port: Number(process.env.MAIL_MODULE_PORT || process.env.PORT || 3100),
  host: process.env.MAIL_MODULE_HOST || process.env.HOST || process.env.BIND_HOST || '127.0.0.1',
  log: {
    level: process.env.LOG_LEVEL || 'info',
    dir: process.env.LOG_MODULE_LOG_DIR || process.env.LOG_DIR || path.join(__dirname, '..', 'logs'),
  },
}
