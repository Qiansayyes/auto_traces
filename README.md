# auto_traces

云端 + Android 设备 + 管理台的 TikTok 运营自动化平台：账号/设备调度、自动切号、素材下发、邮箱接码，以及可选的关系采集脚本。

## 架构一览

```
┌─────────────────┐     ┌──────────────────────────────┐
│  Android 客户端  │────▶│  FastAPI 主服务 (server/)      │
│  android-simple │◀────│  :8000  /api/*  SQLite         │
│  / android-client│     │         │                     │
└─────────────────┘     │         ▼                     │
                        │  mail_module (Node :3100)      │
┌─────────────────┐     │  由主服务拉起并反向代理         │
│  web-admin/     │────▶│                               │
│  主控制台静态页  │     └──────────────────────────────┘
└─────────────────┘
        │
        └── iframe → /mail-admin/ （web-mail-admin 构建产物）
```

| 目录 | 作用 |
|------|------|
| `server/` | FastAPI 主服务（设备绑定、任务队列、账号、自动切号、图库等） |
| `server/mail_module/` | Outlook 邮箱子服务（Node/Koa），主服务自动拉起 |
| `web-admin/` | 浏览器主控制台（账号 / 设备 / 图库 / 邮箱入口） |
| `web-mail-admin/` | 邮箱管理前端构建产物（生产由 FastAPI 托管） |
| `android-simple/` | **当前主力** Android 客户端（版本号更新更快） |
| `android-client/` | 并行/历史客户端工程（同包名 `com.autotraces.app`） |
| `gallery_library/` | 图库素材下发运行时数据（按账号子目录 + `index.json`；可保留 `admin/` 种子） |
| `scripts/` / `tools/` | 运维与辅助脚本 |
| `tiktok_follow_scraper.py` | Selenium 关系采集（根→粉丝→关注） |
| `tiktok_follower_growth_monitor.py` | 根账号粉丝增长监控 → 写入待拉取队列 |
| `deploy_vps.sh` + `nginx-auto_traces.conf` | VPS 一键部署与 Nginx 反代 |

更细的服务端表结构与接口见 [`server/README.md`](server/README.md)；邮箱模块见 [`server/mail_module/README.md`](server/mail_module/README.md)。

## 环境要求

- **Python** 3.9+（推荐 3.10+）
- **Node.js** 18+（邮箱模块；前端构建需 20.19+ / 22.12+）
- 生产：Nginx + systemd（见 `deploy_vps.sh`）
- Android：JDK 17 + Android SDK（编译 APK）

## 本地快速启动

### 1. 主服务（必启）

```bash
cd server
python -m venv ../.venv
# Windows: ..\.venv\Scripts\activate
# Linux/macOS: source ../.venv/bin/activate
pip install -r requirements.txt

# 首次启用邮箱模块时：
cd mail_module && npm install && cd ..

python main.py
# 或: uvicorn main:app --host 0.0.0.0 --port 8000
```

- API / Swagger：`http://127.0.0.1:8000/docs`
- 健康检查：`GET http://127.0.0.1:8000/api/health`
- APK 版本：`GET http://127.0.0.1:8000/api/apk/version`

主服务启动时若 `MAIL_MODULE_ENABLED=1`（默认），会自动拉起 `mail_module`（默认 `127.0.0.1:3100`）。

### 2. 管理台

本地开发可直接打开 `web-admin/index.html`，左下角 **API Base** 指向：

```text
http://127.0.0.1:8000/api
```

生产环境由 Nginx 托管 `web-admin/`，API 反代到后端；邮箱 SPA 走 `/mail-admin/` 反代（不可只靠 `try_files`，否则 iframe 会嵌套整页控制台）。

部署或改前端后，更新 `web-admin/index.html` 里 `app.js?v=...` 版本号，避免浏览器缓存旧脚本。

### 3. 邮箱模块（可选独立调试）

```bash
cd server/mail_module
cp .env.example .env   # 按需改 MAIL_INTERNAL_SECRET 等
npm install
npm start              # 默认 :3100
```

前端源码重建：

```bash
cd server/mail_module/web/MS_OAuth2API_Next_Web
npm install
npm run build
# 将 dist/ 同步到仓库根目录 web-mail-admin/（若生产用预构建产物）
```

控制台内入口：左侧 **邮箱管理模块**；也可访问 `http://<host>:8000/mail-admin/`。

### 4. Android 客户端

优先使用 `android-simple/`（当前 `versionName` 更高）：

```bash
cd android-simple
./gradlew.bat assembleDebug   # Windows
# ./gradlew assembleDebug     # Linux/macOS
```

安装后在设备上配置云端地址、绑定 `device_id` / `alias`。行为与模式说明见各客户端 README。

### 5. 辅助脚本（可选）

```bash
# 关系采集（需本机 Chrome；建议先手动登录复用用户目录）
python tiktok_follow_scraper.py --help

# 粉丝增长监控 → mobile_pending_usernames
python tiktok_follower_growth_monitor.py --help
```

## VPS 部署

```bash
sudo bash deploy_vps.sh
# 或自定义：
sudo APP_DIR=/opt/auto_traces/auto_traces SERVER_NAME=你的域名或IP bash deploy_vps.sh
```

脚本会：创建 venv、安装 Python/Node 依赖、写 systemd、部署 Nginx、探活 `/api/health` 与 `/mail-admin/`。

访问：`http://<SERVER_NAME>/`  
安全组需放行 TCP 80（及可选 443）。

## 常用环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `HOST` / `PORT` | `0.0.0.0` / `8000` | 主服务监听 |
| `MAIL_MODULE_ENABLED` | `1` | `0` 禁用邮箱子进程 |
| `MAIL_MODULE_PORT` | `3100` | 内部 Node 端口 |
| `MAIL_MODULE_HOST` | `127.0.0.1` | 内部绑定 |
| `MAIL_INTERNAL_SECRET` | （见 `.env.example`） | Python ↔ Node 内部密钥，两边一致 |
| `NODE_BINARY` | 自动检测 | Node 可执行文件路径 |
| `MAIL_TOKEN_REFRESH_*` | 见 mail `.env.example` | 令牌自动刷新调度 |

## 管理台能力摘要

- **设备管理**：绑定、状态、自动起号策略、任务轮询
- **账号管理**：批量导入（2FA / 邮箱 / 邮箱+2FA）、高粉导出、按文件删除、自动切号 / 单号活跃
- **图库素材下发**：上传素材库 → 按设备一对一下发
- **邮箱管理**：Outlook OAuth 拉信、令牌邮箱、项目接码、模板

### 账号记录：提取高粉

1. 「粉丝 >」填阈值（默认 1000）→ **提取高粉账号**
2. 下载 XLSX（不删原记录）
3. 无反应时 Ctrl+F5，确认 `app.js?v=` 已更新

### 账号记录：按文件删除

1. **下载删除模板** → 每行一个 username（`#` 为注释）
2. **按文件删除**（单次最多 5000）；不可恢复，大文件请先备份

## 已实现要点

- 云端核心接口 + SQLite 持久化
- 安卓绑定、配置拉取、状态同步、轮询、日志与结果上报
- 管理台账号批量导入 / 高粉导出 / 按文件删除
- **2FA 自动登录（密码后分流）**
  - 路径 A：OCR `Authenticator app` → 填 TOTP
  - 路径 B（同时有 2FA + 邮箱时）：OCR 大标题 `Email` 且同屏有 `2-step verification` / `Use another method` 等 → 点 Email 两次 → `Use another method` → 弹层 `Authenticator` → 再填 TOTP
  - 真·2FA 页以 `Authenticator app` 认定；弹层会排除最终页标题拆出的 `Authenticator` 单词
- 日志页状态区精简（横屏可用）：最多两行摘要
- 邮箱模块与主控制台登录态打通、按用户隔离数据

## 运维注意

- 改 `web-admin` 后务必 bump `app.js?v=...`
- 路径 B 依赖英文 UI；TikTok 文案变更需同步 OCR 锚点
- 改安卓 UI 后重装 APK，横屏打开「日志」确认布局
- 发版 APK 时同步 `android-*/app/build.gradle.kts` 的 `versionCode` / `versionName`（服务端会解析；无源码时用 `main.py` 内 fallback）
- 不要把 `.env`、`*.db`、密钥提交进仓库

## 文档索引

| 文档 | 内容 |
|------|------|
| [`server/README.md`](server/README.md) | 表结构、核心 API、设备任务队列 |
| [`server/mail_module/README.md`](server/mail_module/README.md) | 邮箱子服务、构建与集成 |
| [`android-simple/README.md`](android-simple/README.md) | 主力客户端模式与执行逻辑 |
| [`android-client/README.md`](android-client/README.md) | 并行客户端说明 |
| [`Updates.md`](Updates.md) | 变更摘记 |
