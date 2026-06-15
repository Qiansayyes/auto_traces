const Koa = require('koa')
const { koaBody } = require('koa-body')
const path = require('path')
const static = require('koa-static')
const fs = require('fs')

const app = new Koa()

// 配置
const config = require('./config')

// 记录日志
const logger = require('./utils/logger')
app.use(require('./middlewares/logger'))

// 错误处理
const errorHandler = require('./middlewares/error')
app.use(errorHandler)


// 请求体解析
app.use(koaBody({
  multipart: true,
  formidable: {
    maxFileSize: 200 * 1024 * 1024 // 设置上传文件大小最大限制，默认2M
  }
}))

// 前端资源文件
const staticPath = path.join(__dirname, './web/MS_OAuth2API_Next_Web/dist')
app.use(static(staticPath, {
  maxage: 0,
  gzip: true,
  index: 'index.html',
  setHeaders: (res, filePath) => {
    if (filePath.endsWith('.html') || filePath.endsWith('.js') || filePath.endsWith('.css')) {
      res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate')
      res.setHeader('Pragma', 'no-cache')
      res.setHeader('Expires', '0')
    }
  }
}))

// 路由
const router = require('./routes')
app.use(router.routes()).use(router.allowedMethods())



app.use(async (ctx, next) => {
  // 如果已经有响应体，直接返回
  if (ctx.body || ctx.status !== 404) {
    return
  }

  // 如果是 API 请求或静态资源，不处理
  if (ctx.path === '/api' || ctx.path.startsWith('/api/') || ctx.path.includes('.')) {
    return
  }

  try {
    const indexPath = path.join(staticPath, 'index.html')
    if (fs.existsSync(indexPath)) {
      ctx.type = 'html'
      ctx.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate')
      ctx.set('Pragma', 'no-cache')
      ctx.set('Expires', '0')
      ctx.body = fs.createReadStream(indexPath)
    } else {
      ctx.status = 404
      ctx.body = 'Vue index.html not found'
    }
  } catch (error) {
    logger.error('Error serving index.html:', error)
    ctx.status = 500
    ctx.body = 'Internal Server Error'
  }
})

// 启动服务（被 mail_integration.py 子进程调用时执行；直接 node main.js 亦可独立运行）
const PORT = config.port || 3100
const HOST = config.host || '127.0.0.1'

if (require.main === module) {
  app.listen(PORT, HOST, () => {
    logger.info(`Mail module is running on http://${HOST}:${PORT}`)
  })
}

module.exports = app
