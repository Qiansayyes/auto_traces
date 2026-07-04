# android-me — 调度器重构工作区

从 `android-client` 复制而来。**调度循环与云端拉取逻辑已全部移除**，保留执行层（`TraceExecutor`）、UI、备份等，便于在此重写调度器。

## 已删除

- 整个 `coordinator/` 包（含 `AutoSwitchCoordinator`、涨粉拉取、云端轮询、切号协调等）
- `AutoSwitchLocalScheduler.kt` — 本地 tick 调度
- 原 `AutoSwitchLocalPlan.kt` / `AutoSwitchLedger.kt` 等完整实现

## 占位桩（待新调度器替换）

| 文件 | 说明 |
|------|------|
| `AppCoordinator.kt` | 对外 API 壳，无 check_start / mode_wait / pullUsername / bundle 拉取 |
| `AutoSwitchLocalPlan.kt` | 计划存储空壳 |
| `AutoSwitchLedger.kt` | 自治 OP 账本 + 关会话 summary 上报 |
| `AutoSwitchBundleExt.kt` | bundle 缓存空壳 |
| `PublishPostGrowthGate.kt` | 发作品门控空壳 |

## 仍保留

- `TraceExecutor.kt` — 任务执行
- `backup/` — 备份还原
- `ApiService.kt` — HTTP 定义（业务层暂未调用拉取类接口）
- UI、PrefsStore、WorkerService 等

## 自动切号（已重构）

- `autoswitch/AutoSwitchBundleStore.kt` — bundle：`auto_switch_bundle.json` + 内存
- `AutoSwitchLocalPlan.kt` — plan：`auto_switch_local_plan.json`（环境归属 + 切号三步进度）
- `AutoSwitchLocalScheduler.kt` — 每分钟对账切号；对齐后读 bundle 本时段 `auto_publish_minute` / `auto_mutual_minute` 到点入队独占 OP
- `AutoSwitchCutSwitchExecutor.kt` — 备份 → 清理 → 还原/登录
- `AutoSwitchSlotPolicy.kt` — 发作品/引流插入策略（时间点来自 bundle，状态在内存）
- `AutoSwitchExclusiveOpRunner.kt` — 执行 `__publish_post__` / `__mutual_switch__`，触发前暂停涨粉
- `AppCoordinator` — `mode_wait` 长轮询 → `auto_switch_enter` / `auto_switch_exit`；拉 bundle + 启动调度器
- `GrowthPullLoop.kt` — 调度器时段对齐后涨粉；发作品/引流/切号时协作暂停

## 导入方式

Android Studio 打开本目录，Gradle 同步。
