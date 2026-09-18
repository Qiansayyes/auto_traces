# auto_traces

## 目录结构

- `server/`：完整可运行的 FastAPI 服务（含 SQLite 持久化）
- `android-client/` / `android-simple/`：Android 客户端
- `web-admin/`：浏览器管理后台（账号、设备、模板、素材等）

## 先启动云端

```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Swagger：

- `http://127.0.0.1:8000/docs`

管理后台：

- 打开 `web-admin/index.html`（或由服务端静态托管），左下角 API Base 指向 `http://127.0.0.1:8000/api`

## 账号管理 · 账号记录（常用）

### 提取高粉账号

1. 在「粉丝 >」填阈值（默认 1000）
2. 点 **提取高粉账号**
3. 会下载一份 XLSX（粉丝数大于阈值的整行），**不会删除**原记录

若点击无反应：强制刷新页面（Ctrl+F5），确保加载最新 `app.js`。

### 按文件删除账号（整行）

用于批量删掉指定 username 对应的账号记录，并同步清理设备备份档案。

1. 点 **下载删除模板**，得到 `delete_usernames_template.txt`
2. 按模板填写要删除的 username（每行一个；`#` 开头为注释）
3. 选择填好的 `.txt` / `.csv` 文件
4. 点 **按文件删除**，确认后执行

模板格式示例：

```text
# 按用户名删除账号记录模板
# 说明：每行填写一个 username；以 # 开头的行为注释，会被忽略
# example_user_1
# example_user_2
your_real_username
```

单次最多 5000 个 username。模板里的示例账号已加 `#` 注释，直接上传空模板不会误删。

## 说明

已实现：

- 云端核心接口与数据持久化
- 安卓端绑定、配置拉取、状态同步、轮询、日志与结果上报
- 管理台账号批量导入、高粉导出、按文件删除等
- **2FA 自动登录（密码后分流）**
  - 路径 A：OCR 到 `Authenticator app` → 直接填 TOTP
  - 路径 B（账号同时有 2FA + 邮箱时可能出现）：OCR 到大标题 `Email`，且同屏有 `2-step verification` / `Use another method` / 次数上限文案 → **点 Email 两次** → `Use another method` → 弹层点 `Authenticator` → 再 OCR 到 `Authenticator app` 后填 TOTP
  - 各 OCR 判定均带重试；真·2FA 页必须以 `Authenticator app` 认定（不能单靠 `2-step verification`）；弹层选项会排除最终页标题里拆出来的 `Authenticator` 单词，降低误点
- **日志页状态区已精简（横屏可用）**：原先一长串状态会把横屏日志挤没；现改为最多两行摘要（服务 · 模式/模板 · 切号/硬停等 + 最近消息）；服务端地址、配置是否拉取等去设置/操作页查看

改进建议：

- 部署或改前端后务必更新 `web-admin/index.html` 里 `app.js?v=...` 版本号，避免浏览器继续用旧脚本导致按钮无反应
- 大文件删除时注意先备份；删除不可恢复
- 路径 B 依赖英文 UI 文案（Email / Use another method / Authenticator）；若 TikTok 语言或文案变更需同步改 OCR 锚点
- 改安卓 UI 后重新安装 apk，横屏打开「日志」确认日志区占满剩余高度
