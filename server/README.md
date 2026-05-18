# FastAPI 云端服务

## 功能概览

- 设备绑定、鉴权、状态同步
- 配置拉取与运行模式切换（growth / live / direct）
- 设备轮询拉取用户名（`/api/pull_username`）
- 结果上报（`/api/report_result`）
- 管理端导入用户名、查看概览、触发任务
- SQLite 持久化（非纯内存）

## 启动

```bash
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

## 关键数据库表（系统功能文档）

> 下列字段为当前服务端实现中的实际字段，供前后端与运维统一对齐。

### 1) `mobile_pending_usernames`（growth 下发池，设备端优先消费）

| 字段 | 类型 | 含义 |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | 自增主键 |
| `username` | TEXT NOT NULL | 待执行用户名 |
| `created_at` | TEXT NOT NULL | 入池时间 |
| `consume_status` | TEXT NOT NULL DEFAULT `pending` | 消费状态（见下） |
| `consumed_at` | TEXT NULL | 最近一次状态变更时间 |

`consume_status` 语义：

- `pending`：未发送给设备（待下发）
- `sent`：已通过 `/api/pull_username` 下发给设备，等待设备结果回报
- `success`：设备处理成功（根据上报结果解析）
- `failed`：已发送但设备处理失败/异常（根据上报结果解析）

说明：
- growth 模式下，`/api/pull_username` **仅**拉取 `consume_status='pending'` 的记录。
- 拉取后将该行更新为 `sent`（表示已占用该任务）；设备在 `/api/report_result` 上报**成功**时，对该用户名匹配到的最新一条队列记录执行 **DELETE** 物理删除，避免成功任务无限堆积；上报**失败**时仍保留为 `failed` 便于排查。
- 数据库升级时若发生 `mobile_pending_usernames` 表结构迁移（例如主键策略调整），迁移脚本会重建表；日常运行**不会**批量清空该表。`prune_obsolete_tables()` 仅删除历史无关表（如 `snapshots` 等），**不**动 `mobile_pending_usernames`。

### 2) `follower_queue`

| 字段 | 类型 | 含义 |
|---|---|---|
| `username` | TEXT UNIQUE NOT NULL | 粉丝用户名 |
| `root_username` | TEXT NOT NULL | 来源根用户 |
| `created_at` | TEXT NOT NULL | 入队时间 |
| `following_access_status` | TEXT NOT NULL DEFAULT `unknown` | 访问状态 |
| `mobile_push_status` | TEXT NOT NULL DEFAULT `pending` | 下发状态 |

### 3) `tiktok_roots`

| 字段 | 类型 | 含义 |
|---|---|---|
| `root_username` | TEXT PK | 根用户名 |
| `roots_type` | TEXT NOT NULL DEFAULT `''` | 根类型 |
| `discovered_at` | TEXT NOT NULL | 发现时间 |
| `last_use_follower_count` | INTEGER NULL | 最近抓取粉丝数 |
| `usage_count` | INTEGER NOT NULL DEFAULT `0` | 使用计数 |

### 4) `live_customer`

| 字段 | 类型 | 含义 |
|---|---|---|
| `username` | TEXT PK | 主播用户名 |
| `usage_count` | INTEGER NOT NULL DEFAULT `0` | 使用计数 |
| `created_at` | TEXT NOT NULL | 入库时间 |
| `last_visit_at` | TEXT NULL | 最近访问时间 |

## 主要接口

- `POST /api/bind_device`
- `POST /api/sync_status`
- `GET /api/get_config`
- `POST /api/start_task`
- `GET /api/check_start`
- `GET /api/pull_username`
- `POST /api/report_result`
- `POST /api/admin/add_usernames`
- `POST /api/admin/update_config`
- `GET /api/admin/overview`
- `POST /api/admin/device_tiktok/publish_post_start`

## 设备任务队列（TikTok 相关）

服务端通过统一的设备任务队列下发 TikTok 操作。管理端点击“发布作品”时，后端并不直接操控页面，而是创建一个 `publish_post_start` 任务，设备端需要先轮询 `/api/device_ops/poll` 再执行后续动作。

### `publish_post_start` 的服务端前置步骤

1. **鉴权与设备归属校验**
   - 先调用 `_require_login_and_owning_device(...)`。
   - 校验登录状态、设备归属和 `alias` 是否匹配。
   - 校验失败时，任务不会入队。

2. **必要时关闭设备自动开启程序**
   - 进入 `store.lock` 后检查设备的 `auto_start_opt_out`。
   - 如果设备存在且尚未关闭，会将其置为 `True`。
   - 随后调用 `_persist_client_device_to_db(...)` 持久化。
   - 并写入设备日志：`发作品指令：云端已自动关闭该设备的自动开启程序`。

3. **把设备状态恢复为 running**
   - 调用 `_resume_device_for_cloud_op(...)`。
   - 这一步只是把设备从暂停态切回可运行态，方便设备端继续拉取任务。

4. **下发任务到队列**
   - 调用 `_enqueue_tiktok_device_op(device_id, "publish_post_start", ...)`。
   - 生成 `op_id`，并写入设备的待处理任务队列。
   - 任务载荷包含：`device_id`、`alias`、`package_name`。

5. **接口返回结果**
   - 返回 `code=0`、`op_id`、`auto_start_opt_out`。
   - 如果第 2 步确实关闭了自动开启程序，提示语会带上“已自动关闭该设备自动开启程序”。

### 设备端后续行为（基于当前仓库可确认的部分）

- 设备端通过 `/api/device_ops/poll` 轮询任务。
- 轮询到任务后，服务端返回结构为 `{"code": 0, "msg": "ok", "op": ...}`。
- 对于 `publish_post_start`，仓库中当前能确认的是：服务端仅负责下发该任务；设备端“如何打开页面、点击按钮、填写发布内容”等 UI 级操作逻辑不在服务端实现中。
- 设备端在 `While using the app` 阶段支持可选重复点击：如果 OCR 再次识别到该行，会继续点击，最多 3 轮；识别不到则跳过，不视为失败。

