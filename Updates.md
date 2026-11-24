# Updates

## 2026-11-25
- 根 README 全面重写：架构、各模块启动、环境变量、VPS 部署、管理台摘要与文档索引。
- `server/requirements.txt` 锁定可复现版本（FastAPI / uvicorn / httpx 等）。
- `deploy_vps.sh`：安装 Node.js 20（若缺失）、`mail_module` 的 `npm install`、systemd 注入 `MAIL_MODULE_ENABLED`、同步 `/mail-admin/` 反代端口、修正 `/mail-admin/` 探活假失败（改用 grep）。
- APK 版本探测改为在 `android-simple` / `android-client` 中取 **更高 versionCode**；fallback 同步至 `1.0.9-mediastore-race`。
- `mail_integration.py`：关闭 Node stdout 日志句柄，避免 restart 泄漏；标注 `on_event` 待迁 lifespan。
- `nginx-auto_traces.conf`：为 `/styles.css` 加 no-cache；`web-admin` 缓存戳对齐。
- 修正 `android-simple` README 仍指向 `android-client` 的路径；`gallery_library` 运行时目录写入 `.gitignore`（保留 admin 种子）。
- 修正 `server/mail_module/README.md` 启动目录错误；`mail_module/package.json` 增加 `engines.node`。
- `.gitignore` 增加 `.env` 排除。

## 2026-08-28
- Fixed Graph mail fetch: `use_graph_api` no longer requests `https://graph.microsoft.com/.default` (causes AADSTS90023 on personal MSA tokens). It now requests `Mail.Read` / `Mail.ReadWrite` + `offline_access`, which successfully redeems the imported refresh tokens and can list inbox mail.
