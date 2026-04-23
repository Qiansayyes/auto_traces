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
