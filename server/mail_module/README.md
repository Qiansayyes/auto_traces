# Outlook 邮箱管理模块

本目录从 `ms-mail-tool-personal` 原样迁入，作为 **独立 Node.js 子服务** 运行，由 `server/mail_integration.py` 负责启动与 API 反向代理。

## 功能

- Microsoft/Outlook 个人邮箱 OAuth2 拉信（Graph API + IMAP 降级）
- 账户批量导入、发件人统计、邮件模板匹配
- 项目化取号（acquire / code）
- 管理界面：`http://<host>:8000/mail-admin/`

## 目录结构

```
mail_module/
  main.js              # Koa 入口
  config/              # 配置
  controllers/         # HTTP 控制器
  services/            # 业务逻辑（MailService、DatabaseService 等）
  routes/              # 路由
  middlewares/         # 鉴权、日志
  data/                # SQLite 数据（运行时生成）
  templates/           # 邮件匹配模板 JSON
  web/                 # Vue 3 管理前端源码与构建产物
```

## 独立运行（调试）

```bash
cd server/mail_module
cp .env.example .env
npm install
npm start
# 默认 http://127.0.0.1:3100
```

## 与主服务集成

主服务 `main.py` 启动时会自动：

1. 启动本模块 Node 子进程（`MAIL_MODULE_PORT`，默认 3100）
2. 校验 **Auto Traces 登录态**（`Authorization: Bearer <token>`）
3. 将邮箱 API 代理到子服务，并按登录用户隔离数据
4. 托管前端静态资源 `/mail-admin/`

### 首次部署 checklist

```bash
# 1. 安装 Python 依赖（主服务目录）
cd server
pip install -r requirements.txt

# 2. 安装 Node 依赖（邮箱模块，首次必须）
cd mail_module
npm install

# 3. 构建管理前端（首次或前端有改动时）
cd web/MS_OAuth2API_Next_Web
npm install
npm run build

# 4. 同步前端产物（可选，仓库已带 web-mail-admin/ 时可跳过）
# 将 dist/ 复制到项目根目录 web-mail-admin/

# 5. 启动主服务（必须在 server/ 目录）
cd ../../..
python main.py
# 或: python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

**环境要求**：本机需安装 **Node.js 18+**（用于邮箱子服务；前端构建建议 20.19+）。

### 使用流程

1. 在 **web-admin 主控制台** 登录
2. 左侧导航进入 **邮箱管理模块**（与「账号管理模块」同级）
3. 导入/管理邮箱——每个用户只能看到自己导入的数据

> `/mail-admin/` 独立地址仍可使用；已嵌入主控制台时无需单独打开。

环境变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `MAIL_MODULE_ENABLED` | `1` | 设为 `0` 禁用模块 |
| `MAIL_MODULE_PORT` | `3100` | 内部 Node 端口 |
| `MAIL_MODULE_HOST` | `127.0.0.1` | 内部绑定地址 |
| `NODE_BINARY` | 自动检测 | Node 可执行文件路径 |

状态检查：`GET /api/mail-module/status`

## 前端构建

```bash
cd web/MS_OAuth2API_Next_Web
npm install
npm run build
# 产物输出到 dist/，base 路径为 /mail-admin/
```
