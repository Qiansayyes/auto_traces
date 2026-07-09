from __future__ import annotations

import asyncio
import base64
import contextlib
import copy
import hashlib
import inspect
import json
import random
import re
import sqlite3
import string
import signal
import subprocess
import sys
import locale
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo
from threading import Event, Lock, RLock, Thread
from typing import Any, Dict, Iterable, List, Literal

import requests

from tiktok_universal_snapshot import (
    apply_following_visibility_html_fallback,
    _get_universal_data,
    _webapp_user_detail_meta,
    extract_tiktok_universal_profile_snapshot,
    prepare_tiktok_profile_request_headers_and_cookies,
    tiktok_universal_extract_to_json_dict,
)

from fastapi import FastAPI, File, Form, UploadFile, Header, HTTPException, Query, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field, field_validator

if sys.platform.startswith("win"):
    # Windows 下 Proactor 事件循环在连接断开时偶发 _call_connection_lost 异常；
    # 改用 Selector 策略可避免该类噪音报错。
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

try:
    from openpyxl import Workbook, load_workbook
except Exception:
    Workbook = None
    load_workbook = None


DeviceStatus = Literal["offline", "online", "running", "error"]

BASE_DEFAULT_CONFIG = {
    "break_min": 10,
    "break_max": 20,
    "visit_delay_min": 0.5,
    "visit_delay_max": 2,
    "video_stay_min": 3,
    "video_stay_max": 10,
    # 互动动作权重（一次只做一个动作时的权重随机）
    "like_prob": 0.4,
    "comment_prob": 0.3,
    "favorite_prob": 0.3,
    "share_prob": 0.3,
    # 评论动作内部：文字/表情
    "comment_text_prob": 0.7,
    "comment_emoji_prob": 0.3,
    # 每个动作每日上限（默认 800，可后台修改）
    "like_daily_limit": 800,
    "comment_daily_limit": 800,
    # 引流评论每日上限：独立于通用评论
    "mutual_traffic_comment_daily_limit": 800,
    "favorite_daily_limit": 800,
    "share_daily_limit": 800,
    # 是否互斥只执行一个动作
    "action_single": True,
    # following 质量筛选阈值（用于 follower_queue -> following 回流）
    "following_filter_min_video_count": 10,
    "following_filter_max_following_count": 1000,
    # 手机端单次拉取数量默认值（/api/pull_username 在未传 count 时采用）
    "pull_batch_size": 5,
    # 设备连续完成多少批拉取后，下一次拉取触发休息（与 pull_batch_size 解耦）
    "pull_batches_before_rest": 5,
    # 为 true 时本模板设备永不进入批次休息（优先于 break_min/break_max）
    "no_rest": False,
    # 立即处理 follower_queue 的默认批次大小
    "process_queue_batch_size": 100,
    # 评论文本库
    "comment_texts": ["hi", "moring", "so cute"],
    # 互点流程专用评论文本库（设备端为空则不发互点评，不回退 comment_texts）
    "mutual_comment_texts": ["hi", "moring", "so cute"],
    # TikTok 搜索关键词库（逗号/换行多条）；设备每次进入/恢复 Videos 流程时随机选一条输入搜索框；为空则回退 teamwork
    "mutual_traffic_target_texts": ["teamwork"],
    # 远控「修改基本资料」：Bio 随机文案库（设备端为空则 Bio 步失败）
    "profile_edit_bio_texts": ["Just vibing", "Thanks for visiting"],
    # 发作品前本机 tasks/request 拉取素材条数（清理图库之后）；0=不拉取
    "publish_post_material_fetch_count": 3,
    # 引流 Videos 列表轮次（与完整互点共用）；粉丝列表 Follow 单次会话点击上限
    "mutual_videos_scroll_rounds": 5,
    "mutual_follow_tap_session_limit": 20,
    # 引流 Videos 单条作品内可独立开关：评论 / 作者区 Follow / Followers 列表 Follow（作者区与列表点击共用上限计数）
    "mutual_traffic_comment": True,
    "mutual_traffic_author_follow": True,
    "mutual_traffic_followers_follow": True,
    # 与上述三项引流动作互斥：开启后走「互点-仅关注」方案（评论区关注，不发评/不点作者区与 Followers）
    "mutual_traffic_follow_only": False,
    # 已废弃：涨粉满批后自动引流（设备端仅在互点模式独立运行引流）
    "growth_pull_batches_before_auto_mutual": -1,
    # 互动动作之间的随机间隔（秒）
    "action_delay_min": 0.7,
    "action_delay_max": 1.2,
    # TikTok 恢复重启：强停后等待、拉起后等待（毫秒）
    "tiktok_force_stop_wait_ms": 800,
    "tiktok_launch_wait_ms": 2600,
    # 打开 TikTok 后，For You 预滑总时长（秒），0 表示关闭
    "for_you_swipe_total_sec": 0.0,
    # For You 预滑阶段点赞概率（双击屏幕）
    "for_you_like_prob": 0.0,
    # For You 预滑阶段每条视频观看时长区间（秒）
    "for_you_watch_min_sec": 2.0,
    "for_you_watch_max_sec": 5.0,
}

MODE_DEFAULT_CONFIGS = {
    "growth": {
        **BASE_DEFAULT_CONFIG,
        # 各模式通用：进入用户主页后，按一定概率不点击作品，直接跳过该用户
        "skip_post_click_prob": 0.0,
        # growth 模式中/右列作品点击后：前后帧差检测的点击后等待毫秒
        "growth_post_check_after_tap_delay_ms": 1000,
        # growth 模式中/右列作品点击后：首次失败到二次判定的等待毫秒
        "growth_post_second_check_delay_ms": 650,
    },
    "live": {
        **BASE_DEFAULT_CONFIG,
        "direct_post_action_delay_ms": 1500,
        "skip_post_click_prob": 0.0,
    },
    "direct": {
        **BASE_DEFAULT_CONFIG,
        "direct_post_action_delay_ms": 1500,
        "skip_post_click_prob": 0.0,
    },
}
MODE_DEFAULT_CONFIGS["mutual"] = dict(MODE_DEFAULT_CONFIGS["growth"])
RUN_MODES = ("growth", "live", "direct", "mutual")
# 智能模板暂仅对涨粉模式生效（设备 get_config 与别名开关均按 growth 桶）
SMART_TEMPLATE_RUN_MODES = frozenset({"growth"})

DEFAULT_SMART_TEMPLATE_CONFIG: Dict[str, object] = {
    "old_branch_min_followers": 100,
    "old_branch_min_video_count": 15,
    "new_branch_templates": [{"template_name": "default", "weight": 100}],
    "old_branch_templates": [{"template_name": "default", "weight": 100}],
}

DEFAULT_CONFIG = dict(MODE_DEFAULT_CONFIGS["growth"])

BASE_DIR = Path(__file__).resolve().parents[1]
GALLERY_LIBRARY_DIR = BASE_DIR / "gallery_library"
GALLERY_LIBRARY_MAX_ITEMS = 100
GALLERY_LIBRARY_INDEX_FILENAME = "index.json"
with contextlib.suppress(Exception):
    GALLERY_LIBRARY_DIR.mkdir(parents=True, exist_ok=True)

FAILURE_REPORTS_DIR = BASE_DIR / "failure_reports"
FAILURE_REPORTS_MAX_ITEMS = 88
FAILURE_REPORTS_INDEX_FILENAME = "index.json"
with contextlib.suppress(Exception):
    FAILURE_REPORTS_DIR.mkdir(parents=True, exist_ok=True)
_failure_reports_lock = Lock()

BATCH_STREAM_DIR = BASE_DIR / "batch_stream_sessions"
with contextlib.suppress(Exception):
    BATCH_STREAM_DIR.mkdir(parents=True, exist_ok=True)
_batch_stream_session_lock = Lock()
_batch_stream_sessions: Dict[str, Dict[str, Any]] = {}
_batch_stream_device_active: Dict[str, str] = {}

BATCH_STREAM_HEADER_ALIASES: Dict[str, tuple[str, ...]] = {
    "username": ("username", "用户名", "user"),
    "password": ("password", "密码", "tiktok_password"),
    "email": ("email", "邮箱"),
    "email_password": ("email_password", "邮箱密码", "email password", "email_pwd"),
    "api": ("api", "api_address", "api地址", "api_url", "api url"),
    "assigned_alias": ("分配别名", "assigned_alias", "别名", "alias", "device_alias"),
}
DB_PATH = BASE_DIR / "tiktok_fans.db"
CV_TEMPLATE_ZIP_PATH = BASE_DIR / "server" / "cv_templates.zip"
FOLLOW_SCRAPER_PATH = BASE_DIR / "tiktok_follow_scraper.py"
AUTOSEARCH_PATH = BASE_DIR / "autoSearch_5.28_with_debug.py"
TIKTOK_PACKAGE_NAME = "com.zhiliaoapp.musically"
BEIJING_TZ = ZoneInfo("Asia/Shanghai")
AUTO_SWITCH_TASK_LOG_KEY = "auto_account_switch"
AUTO_SWITCH_TICK_INTERVAL_SEC = 60
# 单次调度最多下发切号/初始对齐条数，避免开启自动切号时瞬时打满设备队列与数据库写锁。
_AUTO_SWITCH_MAX_SWITCH_OPS_PER_TICK = 8
BACKUP_STATS_FULL_QUERY_LOG_KEY = "backup_stats_full_query"
# 暂时关闭「每日 0 点自动全量查询」；恢复时改 True 并重启服务。
BACKUP_STATS_FULL_QUERY_SCHEDULE_AVAILABLE = False
# 暂时关闭「全量查询并落库」手动按钮；恢复时改 True。
BACKUP_STATS_FULL_QUERY_MANUAL_AVAILABLE = False
BACKUP_STATS_FULL_QUERY_TICK_INTERVAL_SEC = 60
BACKUP_STATS_FULL_QUERY_REQUEST_GAP_SEC = 0.3
MOBILE_PENDING_AUTO_REFILL_TICK_INTERVAL_SEC = 60
MOBILE_PENDING_AUTO_REFILL_DEFAULT_THRESHOLD = 5000
# 临时方案（涨粉）：pull_username 从 tiktok_roots 随机只读下发；原 mobile_pending 队列消费见 pull_username 内注释块。

# 应用关闭信号：后台调度线程与 shutdown 钩子共享，避免 uvicorn 长时间卡在 Shutting down。
_APP_SHUTDOWN_EVENT = Event()
_app_shutting_down = False
_EARLY_SHUTDOWN_PREV_HANDLERS: Dict[int, Any] = {}
MOBILE_PENDING_AUTO_REFILL_DEFAULT_COOLDOWN_SEC = 600
DEFAULT_PROCESS_QUEUE_BATCH_SIZE = int(BASE_DEFAULT_CONFIG.get("process_queue_batch_size", 100))
DEVICE_LOG_DEDUP_WINDOW_SEC = 5.0
_device_log_dedup_cache_lock = Lock()
_device_log_dedup_cache: Dict[str, tuple[str, float]] = {}


def now_text() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _hash_password(password: str) -> str:
    return hashlib.sha256(password.encode('utf-8')).hexdigest()


def _verify_password(password: str, password_hash: str) -> bool:
    return _hash_password(password) == password_hash


def _new_session_token() -> str:
    return uuid.uuid4().hex + uuid.uuid4().hex



_sqlite_wal_ready = False
_DB_BUSY_TIMEOUT_MS = 8_000
_DB_RETRY_ATTEMPTS = 3
_DB_RETRY_BASE_SEC = 0.03
_AUTH_SESSION_CACHE: dict[str, tuple[dict, float]] = {}
_AUTH_SESSION_CACHE_TTL_SEC = 20.0
_DEVICE_EDITOR_PRUNE_INTERVAL_SEC = 120.0
_device_editor_prune_last_at: dict[str, float] = {}
_DEVICE_EDITOR_RECONCILE_INTERVAL_SEC = 300.0
_device_editor_reconcile_last_at: dict[str, float] = {}
# 备份档案等读接口：prune/reconcile/materialize 合并限频，避免翻页/刷新与设备 pull_username 抢写锁。
_DEVICE_EDITOR_READ_MAINTAIN_INTERVAL_SEC = 600.0
_device_editor_read_maintain_last_at: dict[str, float] = {}
_USER_RUNTIME_STATE_CACHE_TTL_SEC = 45.0
_user_runtime_state_cache: dict[str, tuple[dict, float]] = {}
_user_runtime_state_cache_lock = Lock()
DEVICE_EDITOR_UNASSIGNED_ALIAS_BUCKET = "__UNASSIGNED__"
_DEVICE_OPS_POLL_MIN_INTERVAL_SEC = 10.0
_device_ops_poll_last_at: dict[str, float] = {}


def _device_ops_poll_try_acquire(device_id: str) -> bool:
    """同一 device_id 冷却期内跳过 poll，避免设备密集轮询拖垮管理台接口。"""
    did = str(device_id or "").strip()
    if not did:
        return True
    now = time.monotonic()
    last = _device_ops_poll_last_at.get(did, 0.0)
    if now - last < _DEVICE_OPS_POLL_MIN_INTERVAL_SEC:
        return False
    _device_ops_poll_last_at[did] = now
    return True


def _is_db_lock_error(exc: BaseException) -> bool:
    if not isinstance(exc, sqlite3.OperationalError):
        return False
    msg = str(exc).lower()
    return "database is locked" in msg or "database is busy" in msg or "locked" in msg


def _db_retry_call(fn):
    last: BaseException | None = None
    for attempt in range(_DB_RETRY_ATTEMPTS):
        try:
            return fn()
        except sqlite3.OperationalError as exc:
            last = exc
            if not _is_db_lock_error(exc) or attempt >= _DB_RETRY_ATTEMPTS - 1:
                raise
            time.sleep(_DB_RETRY_BASE_SEC * (2**attempt))
    if last:
        raise last
    raise RuntimeError("db_retry_call: unreachable")


def _apply_sqlite_pragmas(conn: sqlite3.Connection) -> None:
    conn.execute(f"PRAGMA busy_timeout={_DB_BUSY_TIMEOUT_MS}")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA wal_autocheckpoint=1000")


def _ensure_sqlite_wal_mode() -> None:
    """一次性开启 WAL，提升读写并发（须在 init_databases 最早调用）。"""
    global _sqlite_wal_ready
    if _sqlite_wal_ready:
        return
    conn = sqlite3.connect(str(DB_PATH), timeout=30.0)
    try:
        _apply_sqlite_pragmas(conn)
    finally:
        conn.close()
    _sqlite_wal_ready = True


def _invalidate_auth_session_cache(token: str | None = None) -> None:
    if not token:
        _AUTH_SESSION_CACHE.clear()
        return
    _AUTH_SESSION_CACHE.pop(token, None)


_AUTH_DB_LOCK_TIMEOUT_SEC = 3.0


@contextlib.contextmanager
def _auth_db_conn():
    """登录专用短超时连接：写锁拥堵时快速返回 503，避免前端 30s 超时无响应。"""
    conn = sqlite3.connect(str(DB_PATH), timeout=_AUTH_DB_LOCK_TIMEOUT_SEC, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=2000")
    conn.execute("PRAGMA synchronous=NORMAL")
    try:
        yield conn
    finally:
        conn.close()


def _touch_auth_last_login_async(username: str) -> None:
    owner = str(username or "").strip()
    if not owner:
        return

    def _run() -> None:
        conn = _get_db()
        try:
            conn.execute("UPDATE auth_users SET last_login_at = ? WHERE username = ?", (now_text(), owner))
            conn.commit()
        except Exception as exc:
            print(f"[{now_text()}] 登录审计写入失败（last_login_at）：{exc}")
        finally:
            conn.close()

    Thread(target=_run, name="auth_last_login", daemon=True).start()


class _DbConn:
    """包装 sqlite3 连接：WAL + busy_timeout + 单语句重试；不再进程级串行化，避免后台任务拖死 HTTP。"""

    __slots__ = ("_conn",)

    def __init__(self) -> None:
        self._conn = sqlite3.connect(str(DB_PATH), timeout=8.0, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        _apply_sqlite_pragmas(self._conn)

    def execute(self, *args, **kwargs):
        started = time.perf_counter()
        try:
            return _db_retry_call(lambda: self._conn.execute(*args, **kwargs))
        finally:
            elapsed_ms = (time.perf_counter() - started) * 1000
            if elapsed_ms >= 800:
                print(f"[{now_text()}] db.slow_execute | {elapsed_ms:.0f}ms | sql={str(args[0] if args else '')[:120]}")

    def executemany(self, *args, **kwargs):
        return _db_retry_call(lambda: self._conn.executemany(*args, **kwargs))

    def commit(self) -> None:
        _db_retry_call(self._conn.commit)

    def __getattr__(self, name: str):
        return getattr(self._conn, name)

    def close(self) -> None:
        self._conn.close()


def _get_db() -> sqlite3.Connection:
    return _DbConn()  # type: ignore[return-value]


def _maybe_prune_orphan_device_editor_rows(conn: sqlite3.Connection, owner_username: str) -> int:
    """列表接口用：限频清理孤儿档案，避免每次打开 device_rows 都做重 DELETE。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return 0
    now = time.monotonic()
    last = _device_editor_prune_last_at.get(owner, 0.0)
    if now - last < _DEVICE_EDITOR_PRUNE_INTERVAL_SEC:
        return 0
    try:
        pruned = _prune_orphan_device_editor_rows(conn, owner)
        _device_editor_prune_last_at[owner] = now
        return pruned
    except sqlite3.OperationalError as exc:
        if _is_db_lock_error(exc):
            print(f"[{now_text()}] device_editor.prune | skipped_db_busy | owner={owner}")
            return 0
        raise


def _maybe_reconcile_editor_alias_bindings(conn: sqlite3.Connection, owner_username: str) -> tuple[int, int]:
    """读接口用：限频双向对齐，避免每次打开管理台都全表扫描+逐条 upsert 拖垮 SQLite。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return 0, 0
    now = time.monotonic()
    last = _device_editor_reconcile_last_at.get(owner, 0.0)
    if now - last < _DEVICE_EDITOR_RECONCILE_INTERVAL_SEC:
        return 0, 0
    try:
        result = _reconcile_editor_alias_bindings(conn, owner)
        _device_editor_reconcile_last_at[owner] = now
        return result
    except sqlite3.OperationalError as exc:
        if _is_db_lock_error(exc):
            print(f"[{now_text()}] device_editor.reconcile | skipped_db_busy | owner={owner}")
            return 0, 0
        raise


def _reset_device_editor_read_maintain_schedule(owner_username: str) -> None:
    """写操作后允许下次读接口尽快做一次对齐（管理台刚改完档案时）。"""
    owner = str(owner_username or "").strip()
    if owner:
        _device_editor_read_maintain_last_at.pop(owner, None)


def _maybe_maintain_device_editor_on_read(conn: sqlite3.Connection, owner_username: str) -> None:
    """读接口用：限频 prune + reconcile + materialize，默认 10 分钟最多写库一次。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return
    now = time.monotonic()
    last = _device_editor_read_maintain_last_at.get(owner, 0.0)
    if now - last < _DEVICE_EDITOR_READ_MAINTAIN_INTERVAL_SEC:
        return
    try:
        pruned = _maybe_prune_orphan_device_editor_rows(conn, owner)
        to_accounts, to_devices = _maybe_reconcile_editor_alias_bindings(conn, owner)
        forced_accounts, forced_devices = _ensure_device_editor_rows_materialized(conn, owner)
        dirty = bool(pruned or to_accounts or to_devices or forced_accounts or forced_devices)
        if dirty:
            _ensure_device_editor_switch_highlight_invariants(conn, owner)
            conn.commit()
        _device_editor_read_maintain_last_at[owner] = now
    except sqlite3.OperationalError as exc:
        if _is_db_lock_error(exc):
            print(f"[{now_text()}] device_editor.read_maintain | skipped_db_busy | owner={owner}")
            return
        raise


def _invalidate_user_runtime_state_cache(username: str | None = None) -> None:
    with _user_runtime_state_cache_lock:
        if not username:
            _user_runtime_state_cache.clear()
            return
        _user_runtime_state_cache.pop(str(username or "").strip(), None)


def _table_column_names(conn: sqlite3.Connection, table: str) -> set[str]:
    rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
    return {str(r[1]).strip() for r in rows}


def _migrate_shared_follow_tables(conn: sqlite3.Connection) -> None:
    """tiktok_roots / follower_queue 为全平台共享数据，不按用户拆分；迁移掉历史 owner_username 列。"""
    roots_cols = _table_column_names(conn, "tiktok_roots")
    fq_cols = _table_column_names(conn, "follower_queue")
    if "owner_username" not in roots_cols and "owner_username" not in fq_cols:
        return

    conn.execute("PRAGMA foreign_keys=OFF")
    conn.execute("ALTER TABLE tiktok_roots RENAME TO tiktok_roots_old")
    conn.execute(
        """
        CREATE TABLE tiktok_roots (
            root_username           TEXT NOT NULL PRIMARY KEY,
            roots_type              TEXT NOT NULL DEFAULT '',
            discovered_at           TEXT NOT NULL,
            last_use_follower_count INTEGER,
            usage_count             INTEGER NOT NULL DEFAULT 0
        )
        """
    )
    conn.execute(
        """
        INSERT OR IGNORE INTO tiktok_roots (root_username, roots_type, discovered_at, last_use_follower_count, usage_count)
        SELECT o.root_username, o.roots_type, o.discovered_at, o.last_use_follower_count, o.usage_count
        FROM tiktok_roots_old o
        JOIN (
            SELECT root_username, MAX(rowid) AS mr
            FROM tiktok_roots_old
            GROUP BY root_username
        ) t ON o.root_username = t.root_username AND o.rowid = t.mr
        """
    )

    conn.execute("ALTER TABLE follower_queue RENAME TO follower_queue_old")
    conn.execute(
        """
        CREATE TABLE follower_queue (
            username                TEXT UNIQUE NOT NULL,
            root_username           TEXT NOT NULL,
            created_at              TEXT NOT NULL,
            consume_status          TEXT NOT NULL DEFAULT 'pending',
            consumed_at             TEXT,
            consumed_by             TEXT,
            consumed_mode           TEXT,
            following_access_status TEXT NOT NULL DEFAULT 'unknown',
            following_fetch_success INTEGER NOT NULL DEFAULT 0,
            mobile_push_status      TEXT NOT NULL DEFAULT 'pending',
            FOREIGN KEY (root_username) REFERENCES tiktok_roots(root_username)
        )
        """
    )
    conn.execute(
        """
        INSERT OR IGNORE INTO follower_queue (
            username, root_username, created_at, consume_status, consumed_at,
            consumed_by, consumed_mode, following_access_status, following_fetch_success, mobile_push_status
        )
        SELECT o.username, o.root_username, o.created_at,
               COALESCE(o.consume_status, 'pending'), o.consumed_at, o.consumed_by, o.consumed_mode,
               COALESCE(o.following_access_status, 'unknown'),
               COALESCE(o.following_fetch_success, 0), COALESCE(o.mobile_push_status, 'pending')
        FROM follower_queue_old o
        WHERE EXISTS (SELECT 1 FROM tiktok_roots tr WHERE tr.root_username = o.root_username)
        """
    )
    conn.execute("DROP TABLE follower_queue_old")
    conn.execute("DROP TABLE tiktok_roots_old")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_follower_queue_root ON follower_queue(root_username)")
    conn.execute("PRAGMA foreign_keys=ON")


def _migrate_live_customer_global(conn: sqlite3.Connection) -> None:
    """live_customer 与手机拉取池一致，全平台共享，去掉 owner_username。"""
    try:
        cols = _table_column_names(conn, "live_customer")
    except sqlite3.OperationalError:
        return
    if "owner_username" not in cols:
        return

    conn.execute("PRAGMA foreign_keys=OFF")
    conn.execute("ALTER TABLE live_customer RENAME TO live_customer_old")
    conn.execute(
        """
        CREATE TABLE live_customer (
            username    TEXT PRIMARY KEY,
            usage_count INTEGER NOT NULL DEFAULT 0,
            created_at  TEXT NOT NULL,
            last_visit_at TEXT
        )
        """
    )
    conn.execute(
        """
        INSERT OR IGNORE INTO live_customer (username, usage_count, created_at, last_visit_at)
        SELECT o.username, o.usage_count, o.created_at, o.last_visit_at
        FROM live_customer_old o
        JOIN (
            SELECT username, MAX(rowid) AS mr FROM live_customer_old GROUP BY username
        ) t ON o.username = t.username AND o.rowid = t.mr
        """
    )
    conn.execute("DROP TABLE live_customer_old")
    conn.execute("PRAGMA foreign_keys=ON")


def init_follow_db():
    conn = _get_db()
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS tiktok_roots (
                root_username           TEXT NOT NULL PRIMARY KEY,
                roots_type              TEXT NOT NULL DEFAULT '',
                discovered_at           TEXT NOT NULL,
                last_use_follower_count INTEGER,
                usage_count             INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS follower_queue (
                username                TEXT UNIQUE NOT NULL,
                root_username           TEXT NOT NULL,
                created_at              TEXT NOT NULL,
                consume_status          TEXT NOT NULL DEFAULT 'pending',
                consumed_at             TEXT,
                consumed_by             TEXT,
                consumed_mode           TEXT,
                following_access_status TEXT NOT NULL DEFAULT 'unknown',
                following_fetch_success INTEGER NOT NULL DEFAULT 0,
                mobile_push_status      TEXT NOT NULL DEFAULT 'pending',
                FOREIGN KEY (root_username) REFERENCES tiktok_roots(root_username)
            );

            CREATE TABLE IF NOT EXISTS live_customer (
                username    TEXT PRIMARY KEY,
                usage_count INTEGER NOT NULL DEFAULT 0,
                created_at  TEXT NOT NULL,
                last_visit_at TEXT
            );

            CREATE INDEX IF NOT EXISTS idx_follower_queue_root
                ON follower_queue(root_username);

            CREATE TABLE IF NOT EXISTS account_editor_rows (
                owner_username TEXT NOT NULL,
                row_index INTEGER NOT NULL,
                username TEXT NOT NULL DEFAULT '',
                password TEXT NOT NULL DEFAULT '',
                email TEXT NOT NULL DEFAULT '',
                email_password TEXT NOT NULL DEFAULT '',
                api TEXT NOT NULL DEFAULT '',
                is_login INTEGER NOT NULL DEFAULT 0,
                login_time TEXT NOT NULL DEFAULT '',
                region TEXT NOT NULL DEFAULT '',
                assigned_alias TEXT NOT NULL DEFAULT '',
                video_count TEXT NOT NULL DEFAULT '',
                follower_count TEXT NOT NULL DEFAULT '',
                follower_growth TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL,
                PRIMARY KEY (owner_username, row_index)
            );

            CREATE TABLE IF NOT EXISTS device_editor_rows (
                owner_username TEXT NOT NULL,
                row_index INTEGER NOT NULL,
                device_alias TEXT NOT NULL DEFAULT '',
                device_id TEXT NOT NULL DEFAULT '',
                ip TEXT NOT NULL DEFAULT '',
                region TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL DEFAULT '',
                video_count TEXT NOT NULL DEFAULT '',
                follower_count TEXT NOT NULL DEFAULT '',
                follower_growth TEXT NOT NULL DEFAULT '',
                backup_path TEXT NOT NULL DEFAULT '',
                backup_name TEXT NOT NULL DEFAULT '',
                backup_time TEXT NOT NULL DEFAULT '',
                backup_status TEXT NOT NULL DEFAULT '',
                auto_switch_excluded INTEGER NOT NULL DEFAULT 0,
                remark TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL,
                PRIMARY KEY (owner_username, row_index)
            );

            CREATE TABLE IF NOT EXISTS mutual_click_copied_usernames (
                username TEXT NOT NULL PRIMARY KEY,
                created_at TEXT NOT NULL,
                usage_count INTEGER NOT NULL DEFAULT 1
            );
            """
        )

        try:
            conn.execute("ALTER TABLE live_customer ADD COLUMN last_visit_at TEXT")
        except sqlite3.OperationalError:
            pass

        # account_editor_rows 兼容历史库：补齐编辑器字段，避免后续查询报 "no such column"
        try:
            conn.execute("ALTER TABLE account_editor_rows ADD COLUMN assigned_alias TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE account_editor_rows ADD COLUMN video_count TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE account_editor_rows ADD COLUMN follower_count TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE account_editor_rows ADD COLUMN follower_growth TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE device_editor_rows ADD COLUMN video_count TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE device_editor_rows ADD COLUMN follower_count TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE device_editor_rows ADD COLUMN follower_growth TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE device_editor_rows ADD COLUMN auto_switch_excluded INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute(
                "ALTER TABLE account_editor_rows ADD COLUMN basic_profile_modified TEXT NOT NULL DEFAULT '否'"
            )
        except sqlite3.OperationalError:
            pass

        # follower_queue 新字段迁移
        try:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN consume_status TEXT NOT NULL DEFAULT 'pending'")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN consumed_at TEXT")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN consumed_by TEXT")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN consumed_mode TEXT")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN following_access_status TEXT NOT NULL DEFAULT 'unknown'")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN following_fetch_success INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN mobile_push_status TEXT NOT NULL DEFAULT 'pending'")
        except sqlite3.OperationalError:
            pass

        _migrate_shared_follow_tables(conn)
        _migrate_live_customer_global(conn)

        conn.commit()
    finally:
        conn.close()


def _delete_file_safely(path: str) -> bool:
    p = Path(path or "")
    try:
        if not path or not p.exists() or not p.is_file():
            return False
        p.unlink()
        return True
    except Exception:
        return False


def init_auth_db():
    conn = _get_db()
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS auth_users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'customer',
                created_at TEXT NOT NULL,
                last_login_at TEXT,
                is_active INTEGER NOT NULL DEFAULT 1
            );
            CREATE TABLE IF NOT EXISTS auth_sessions (
                token TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                role TEXT NOT NULL,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                FOREIGN KEY (username) REFERENCES auth_users(username)
            );
            """
        )
        try:
            conn.execute("ALTER TABLE auth_users ADD COLUMN role TEXT NOT NULL DEFAULT 'customer'")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE auth_users ADD COLUMN last_login_at TEXT")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE auth_users ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1")
        except sqlite3.OperationalError:
            pass
        conn.execute(
            "INSERT OR IGNORE INTO auth_users (username, password_hash, role, created_at, is_active) VALUES (?, ?, 'admin', ?, 1)",
            ('admin', _hash_password('gggqqq666$'), now_text()),
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS client_devices (
                device_id TEXT PRIMARY KEY,
                owner_username TEXT NOT NULL,
                alias TEXT NOT NULL,
                device_group TEXT NOT NULL DEFAULT '',
                fingerprint TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL DEFAULT 'offline',
                pending_start INTEGER NOT NULL DEFAULT 0,
                paused INTEGER NOT NULL DEFAULT 0,
                auto_start_opt_out INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE(owner_username, alias)
            )
            """
        )
        # 兼容历史库：补齐 auto_start_opt_out / device_group 列
        try:
            conn.execute("ALTER TABLE client_devices ADD COLUMN auto_start_opt_out INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE client_devices ADD COLUMN auto_switch_enabled INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE client_devices ADD COLUMN device_group TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE client_devices ADD COLUMN pc_service_url TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        conn.execute("CREATE INDEX IF NOT EXISTS idx_client_devices_owner ON client_devices(owner_username)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_client_devices_group ON client_devices(device_group)")
        conn.commit()
    finally:
        conn.close()


def init_autosearch_db():
    conn = _get_db()
    try:
        # mobile_pending_usernames 结构迁移：由 username 主键改为自增 id
        old_mobile = conn.execute(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='mobile_pending_usernames'"
        ).fetchone()
        if old_mobile and "PRIMARY KEY" in (old_mobile[0] or "") and "username" in (old_mobile[0] or ""):
            conn.executescript(
                """
                ALTER TABLE mobile_pending_usernames RENAME TO mobile_pending_usernames_old;
                CREATE TABLE mobile_pending_usernames (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    consume_status TEXT NOT NULL DEFAULT 'pending',
                    consumed_at TEXT
                );
                INSERT INTO mobile_pending_usernames (username, created_at, consume_status, consumed_at)
                SELECT username, created_at, 'pending', NULL FROM mobile_pending_usernames_old;
                DROP TABLE mobile_pending_usernames_old;
                """
            )
        else:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS mobile_pending_usernames (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    consume_status TEXT NOT NULL DEFAULT 'pending',
                    consumed_at TEXT
                )
                """
            )

        try:
            conn.execute("ALTER TABLE mobile_pending_usernames ADD COLUMN consume_status TEXT NOT NULL DEFAULT 'pending'")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("ALTER TABLE mobile_pending_usernames ADD COLUMN consumed_at TEXT")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_mobile_pending_username_pending "
                "ON mobile_pending_usernames(username) WHERE consume_status = 'pending'"
            )
        except sqlite3.OperationalError:
            pass

        # device_editor_rows 兼容历史库：补齐备份相关字段，替代 tiktok_account_backup_records
        for ddl in (
            "ALTER TABLE device_editor_rows ADD COLUMN assigned_alias TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE device_editor_rows ADD COLUMN backup_path TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE device_editor_rows ADD COLUMN backup_name TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE device_editor_rows ADD COLUMN backup_time TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE device_editor_rows ADD COLUMN backup_status TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE device_editor_rows ADD COLUMN switch_highlight TEXT NOT NULL DEFAULT '否'",
            "ALTER TABLE device_editor_rows ADD COLUMN env_status TEXT NOT NULL DEFAULT ''",
        ):
            try:
                conn.execute(ddl)
            except sqlite3.OperationalError:
                pass

        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS persisted_runtime_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                json TEXT NOT NULL DEFAULT '{}'
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS auto_switch_daily_plans (
                owner_username TEXT NOT NULL,
                schedule_date TEXT NOT NULL,
                alias TEXT NOT NULL,
                plan_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (owner_username, schedule_date, alias)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS auto_switch_weekly_plans (
                owner_username TEXT NOT NULL,
                week_start_date TEXT NOT NULL,
                alias TEXT NOT NULL,
                plan_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (owner_username, week_start_date, alias)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS device_mode_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                owner_username TEXT NOT NULL,
                event_type TEXT NOT NULL,
                session_id TEXT NOT NULL,
                bundle_revision INTEGER NOT NULL DEFAULT 0,
                payload_json TEXT NOT NULL DEFAULT '{}',
                created_at TEXT NOT NULL,
                acked_at TEXT,
                ack_phase TEXT
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_device_mode_events_device_pending "
            "ON device_mode_events(device_id, id) WHERE acked_at IS NULL"
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS auto_switch_sessions (
                session_id TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL,
                bundle_revision INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                closed_at TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS auto_switch_session_summaries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                owner_username TEXT NOT NULL,
                alias TEXT NOT NULL,
                bundle_revision INTEGER NOT NULL DEFAULT 0,
                schedule_date TEXT NOT NULL DEFAULT '',
                summary_json TEXT NOT NULL,
                human_log TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_auto_switch_session_summaries_owner "
            "ON auto_switch_session_summaries(owner_username, id DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_auto_switch_session_summaries_device "
            "ON auto_switch_session_summaries(device_id, id DESC)"
        )
        try:
            conn.execute(
                "ALTER TABLE auto_switch_session_summaries "
                "ADD COLUMN summary_kind TEXT NOT NULL DEFAULT 'final'"
            )
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute(
                "ALTER TABLE auto_switch_session_summaries "
                "ADD COLUMN reconcile_key TEXT NOT NULL DEFAULT 'final'"
            )
        except sqlite3.OperationalError:
            pass
        # 升级前已入库的总结行 reconcile_key 默认为 final，会挡住新关会话写回；标为 legacy。
        try:
            conn.execute(
                """
                UPDATE auto_switch_session_summaries
                SET reconcile_key = 'legacy-' || CAST(id AS TEXT)
                WHERE reconcile_key = 'final'
                  AND summary_json NOT LIKE '%"summary_kind"%'
                """
            )
        except sqlite3.OperationalError:
            pass
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS auto_switch_publish_video_writeback (
                owner_username TEXT NOT NULL,
                session_id TEXT NOT NULL,
                dedupe_key TEXT NOT NULL,
                slot_key TEXT NOT NULL DEFAULT '',
                outcome_op_id TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL DEFAULT '',
                delta INTEGER NOT NULL DEFAULT 1,
                applied_at TEXT NOT NULL,
                PRIMARY KEY (owner_username, session_id, dedupe_key)
            )
            """
        )
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_auto_switch_publish_video_writeback_session
            ON auto_switch_publish_video_writeback(owner_username, session_id)
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS user_runtime_configs (
                username TEXT NOT NULL,
                config_key TEXT NOT NULL,
                config_json TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (username, config_key)
            )
            """
        )

        conn.commit()
    finally:
        conn.close()


def _ensure_user_runtime_configs():
    conn = _get_db()
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS user_runtime_configs (
                username TEXT NOT NULL,
                config_key TEXT NOT NULL,
                config_json TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (username, config_key)
            )
            """
        )
        conn.commit()
    finally:
        conn.close()


def _ensure_template_permissions():
    conn = _get_db()
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS template_permissions (
                source_username TEXT NOT NULL,
                template_name TEXT NOT NULL,
                target_username TEXT NOT NULL,
                granted_by TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (source_username, template_name, target_username)
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_template_permissions_target ON template_permissions(target_username)"
        )
        conn.commit()
    finally:
        conn.close()


def _ensure_backup_owner_columns():
    """
    兼容历史库：确保 device_editor_rows 存在 owner_username，便于按当前登录用户做数据隔离。
    """
    conn = _get_db()
    try:
        cols = {str(r[1] or "").strip() for r in conn.execute("PRAGMA table_info(device_editor_rows)").fetchall()}
        if "owner_username" not in cols:
            conn.execute("ALTER TABLE device_editor_rows ADD COLUMN owner_username TEXT NOT NULL DEFAULT ''")
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_device_editor_rows_owner_updated ON device_editor_rows(owner_username, updated_at)"
        )
        conn.commit()
    finally:
        conn.close()


def _backup_owner_scope(session: dict) -> tuple[str, list[Any]]:
    """
    备份档案查询权限：管理员看全部，普通用户仅看本人 owner_username。
    """
    if (session or {}).get("role") == "admin":
        return "", []
    return " AND owner_username = ?", [str((session or {}).get("username") or "").strip()]


def _device_group_filter_sql(device_group: str, alias_expr: str) -> tuple[str, list[Any]]:
    """按 client_devices.device_group 过滤别名表达式（alias_expr 为 SQL 片段）。"""
    group = str(device_group or "").strip()
    if not group:
        return "", []
    if group == "__ungrouped__":
        return (
            f" AND {alias_expr} <> '' AND ("
            f"NOT EXISTS (SELECT 1 FROM client_devices cd WHERE TRIM(cd.alias) = {alias_expr}) "
            f"OR EXISTS (SELECT 1 FROM client_devices cd WHERE TRIM(cd.alias) = {alias_expr} "
            f"AND COALESCE(TRIM(cd.device_group), '') = ''))"
        ), []
    return (
        f" AND EXISTS (SELECT 1 FROM client_devices cd WHERE TRIM(cd.alias) = {alias_expr} "
        f"AND COALESCE(TRIM(cd.device_group), '') = ?)",
        [group],
    )


def _client_devices_visible_owner(session: dict) -> str:
    return str(session.get("username") or "").strip()


def _client_devices_group_where_clause(device_group: str) -> tuple[str, list[Any]]:
    """client_devices 表上的 device_group 过滤片段（不含 owner）。"""
    group = str(device_group or "").strip()
    if not group:
        return "", []
    if group == "__ungrouped__":
        return " AND COALESCE(TRIM(device_group), '') = ''", []
    return " AND COALESCE(TRIM(device_group), '') = ?", [group]


def _device_row_to_overview_entry(row: Any, session: dict) -> dict[str, Any]:
    entry: Dict[str, Any] = {
        "alias": str(row["alias"] or "").strip(),
        "device_group": str(row["device_group"] or "").strip(),
        "status": str(row["status"] or "offline"),
        "pending_start": bool(int(row["pending_start"] or 0)),
        "auto_start_opt_out": bool(int(row["auto_start_opt_out"] or 0)),
        "pc_service_url": str(row["pc_service_url"] or "").strip() if "pc_service_url" in row.keys() else "",
    }
    if session.get("role") == "admin":
        entry["owner_username"] = str(row["owner_username"])
    return entry


def _device_stats_from_db(session: dict, *, device_group: str = "") -> dict[str, int]:
    """全量设备状态统计（SQL 聚合，不拉取设备列表）。"""
    owner = _client_devices_visible_owner(session)
    stats = {"online": 0, "running": 0, "error": 0, "pending_start": 0}
    if not owner:
        return stats
    group_sql, group_params = _client_devices_group_where_clause(device_group)
    conn = _get_db()
    try:
        status_rows = conn.execute(
            f"""
            SELECT status, COUNT(1) AS cnt
            FROM client_devices
            WHERE owner_username = ?{group_sql}
            GROUP BY status
            """,
            [owner, *group_params],
        ).fetchall()
        for row in status_rows:
            st = str(row["status"] or "offline")
            if st in stats:
                stats[st] = int(row["cnt"] or 0)
        pending_row = conn.execute(
            f"""
            SELECT COUNT(1) FROM client_devices
            WHERE owner_username = ?{group_sql} AND pending_start = 1
            """,
            [owner, *group_params],
        ).fetchone()
        stats["pending_start"] = int(pending_row[0] if pending_row else 0)
        return stats
    finally:
        conn.close()


def _load_visible_devices_page_from_db(
    session: dict,
    *,
    device_group: str = "",
    page: int = 1,
    page_size: int = 20,
) -> tuple[dict[str, Any], int]:
    """按权限分页读取可见设备（SQL LIMIT/OFFSET，按别名排序）。"""
    owner = _client_devices_visible_owner(session)
    if not owner:
        return {}, 0
    safe_page = max(1, int(page or 1))
    safe_size = max(1, min(int(page_size or 50), 500))
    offset = (safe_page - 1) * safe_size
    group_sql, group_params = _client_devices_group_where_clause(device_group)
    conn = _get_db()
    try:
        total = int(
            conn.execute(
                f"SELECT COUNT(1) FROM client_devices WHERE owner_username = ?{group_sql}",
                [owner, *group_params],
            ).fetchone()[0]
        )
        rows = conn.execute(
            f"""
            SELECT device_id, owner_username, alias, device_group, status, pending_start, auto_start_opt_out, pc_service_url
            FROM client_devices
            WHERE owner_username = ?{group_sql}
            ORDER BY LOWER(TRIM(alias)), device_id
            LIMIT ? OFFSET ?
            """,
            [owner, *group_params, safe_size, offset],
        ).fetchall()
        devices = {
            str(row["device_id"]): _device_row_to_overview_entry(row, session)
            for row in rows
        }
        return devices, total
    finally:
        conn.close()


_OVERVIEW_QUEUE_STATS_GLOBAL_KEY = "__global__"
_overview_snapshot_lock = Lock()
_overview_dirty_owners: set[str] = set()
_overview_queue_stats_dirty = False
_overview_snapshot_wake = Event()
_OVERVIEW_SNAPSHOT_WORKER_INTERVAL_SEC = 0.4
_OVERVIEW_QUEUE_STATS_CACHE_TTL_SEC = 1.0
_overview_queue_stats_cache_lock = Lock()
_overview_queue_stats_cache: dict[str, Any] = {
    "payload": None,
    "expires_at": 0.0,
}


def _ensure_overview_snapshot_tables() -> None:
    """概览读模型 snapshot 表（读路径只查小表，重查询由后台单线程重建）。"""
    conn = _get_db()
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS overview_devices_snapshot (
                device_id TEXT NOT NULL PRIMARY KEY,
                owner_username TEXT NOT NULL,
                alias TEXT NOT NULL DEFAULT '',
                device_group TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'offline',
                pending_start INTEGER NOT NULL DEFAULT 0,
                auto_start_opt_out INTEGER NOT NULL DEFAULT 0,
                pc_service_url TEXT NOT NULL DEFAULT '',
                sort_key TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL DEFAULT ''
            );
            CREATE INDEX IF NOT EXISTS idx_overview_devices_owner_sort
                ON overview_devices_snapshot(owner_username, sort_key, device_id);
            CREATE INDEX IF NOT EXISTS idx_overview_devices_owner_group
                ON overview_devices_snapshot(owner_username, device_group);
            CREATE TABLE IF NOT EXISTS overview_queue_stats_snapshot (
                owner_key TEXT NOT NULL PRIMARY KEY,
                payload_json TEXT NOT NULL,
                updated_at TEXT NOT NULL DEFAULT ''
            );
            """
        )
        conn.commit()
    finally:
        conn.close()


def _mark_overview_dirty(owner: str | None) -> None:
    owner_key = str(owner or "").strip()
    if not owner_key:
        return
    with _overview_snapshot_lock:
        _overview_dirty_owners.add(owner_key)
    _overview_snapshot_wake.set()


def _mark_queue_stats_dirty(_owner: str | None = None) -> None:
    global _overview_queue_stats_dirty
    with _overview_snapshot_lock:
        _overview_queue_stats_dirty = True
    _overview_snapshot_wake.set()


def _invalidate_overview_queue_stats_cache() -> None:
    """兼容旧调用点：队列统计改为 snapshot 标脏。"""
    _mark_queue_stats_dirty()


def _rebuild_overview_devices_snapshot(conn: sqlite3.Connection, owner: str) -> None:
    owner_key = str(owner or "").strip()
    if not owner_key:
        return
    conn.execute("DELETE FROM overview_devices_snapshot WHERE owner_username = ?", (owner_key,))
    rows = conn.execute(
        """
        SELECT device_id, owner_username, alias, device_group, status, pending_start, auto_start_opt_out, pc_service_url
        FROM client_devices
        WHERE owner_username = ?
        """,
        (owner_key,),
    ).fetchall()
    now = now_text()
    for row in rows:
        alias = str(row["alias"] or "").strip()
        conn.execute(
            """
            INSERT INTO overview_devices_snapshot (
                device_id, owner_username, alias, device_group, status, pending_start,
                auto_start_opt_out, pc_service_url, sort_key, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                str(row["device_id"]),
                owner_key,
                alias,
                str(row["device_group"] or "").strip(),
                str(row["status"] or "offline"),
                int(row["pending_start"] or 0),
                int(row["auto_start_opt_out"] or 0),
                str(row["pc_service_url"] or "").strip() if "pc_service_url" in row.keys() else "",
                alias.lower(),
                now,
            ),
        )


def _rebuild_overview_queue_stats_snapshot(conn: sqlite3.Connection, *, preview_limit: int = 200) -> None:
    payload = _load_overview_queue_stats_from_db(preview_limit=preview_limit)
    conn.execute(
        """
        INSERT OR REPLACE INTO overview_queue_stats_snapshot (owner_key, payload_json, updated_at)
        VALUES (?, ?, ?)
        """,
        (_OVERVIEW_QUEUE_STATS_GLOBAL_KEY, json.dumps(payload, ensure_ascii=False), now_text()),
    )


def _warm_overview_snapshots_blocking() -> None:
    conn = _get_db()
    try:
        owners = [
            str(r[0] or "").strip()
            for r in conn.execute(
                "SELECT DISTINCT owner_username FROM client_devices WHERE TRIM(COALESCE(owner_username, '')) <> ''"
            ).fetchall()
            if str(r[0] or "").strip()
        ]
        for owner in owners:
            _rebuild_overview_devices_snapshot(conn, owner)
        _rebuild_overview_queue_stats_snapshot(conn)
        conn.commit()
        print(f"[{now_text()}] overview_snapshot | warm_up | owners={len(owners)}")
    except Exception as exc:
        print(f"[{now_text()}] overview_snapshot | warm_up_failed | {exc}")
    finally:
        conn.close()


def _overview_snapshot_worker_loop() -> None:
    while not _APP_SHUTDOWN_EVENT.is_set():
        _overview_snapshot_wake.wait(timeout=_OVERVIEW_SNAPSHOT_WORKER_INTERVAL_SEC)
        _overview_snapshot_wake.clear()
        if _APP_SHUTDOWN_EVENT.is_set():
            break
        with _overview_snapshot_lock:
            owners = list(_overview_dirty_owners)
            _overview_dirty_owners.clear()
            queue_dirty = _overview_queue_stats_dirty
            _overview_queue_stats_dirty = False
        if not owners and not queue_dirty:
            continue
        conn = _get_db()
        try:
            for owner in owners:
                _rebuild_overview_devices_snapshot(conn, owner)
            if queue_dirty:
                _rebuild_overview_queue_stats_snapshot(conn)
            conn.commit()
        except Exception as exc:
            print(f"[{now_text()}] overview_snapshot | rebuild_failed | {exc}")
        finally:
            conn.close()


def _start_overview_snapshot_worker() -> None:
    Thread(target=_overview_snapshot_worker_loop, name="overview_snapshot", daemon=True).start()


def _load_visible_devices_page_from_snapshot(
    session: dict,
    *,
    device_group: str = "",
    page: int = 1,
    page_size: int = 20,
) -> tuple[dict[str, Any], int]:
    """概览读路径：只查 overview_devices_snapshot。"""
    owner = _client_devices_visible_owner(session)
    if not owner:
        return {}, 0
    safe_page = max(1, int(page or 1))
    safe_size = max(1, min(int(page_size or 50), 500))
    offset = (safe_page - 1) * safe_size
    group_sql, group_params = _client_devices_group_where_clause(device_group)
    conn = _get_db()
    try:
        total = int(
            conn.execute(
                f"SELECT COUNT(1) FROM overview_devices_snapshot WHERE owner_username = ?{group_sql}",
                [owner, *group_params],
            ).fetchone()[0]
        )
        rows = conn.execute(
            f"""
            SELECT device_id, owner_username, alias, device_group, status, pending_start, auto_start_opt_out, pc_service_url
            FROM overview_devices_snapshot
            WHERE owner_username = ?{group_sql}
            ORDER BY sort_key, device_id
            LIMIT ? OFFSET ?
            """,
            [owner, *group_params, safe_size, offset],
        ).fetchall()
        devices = {
            str(row["device_id"]): _device_row_to_overview_entry(row, session)
            for row in rows
        }
        return devices, total
    finally:
        conn.close()


def _device_stats_from_snapshot(session: dict, *, device_group: str = "") -> dict[str, int]:
    owner = _client_devices_visible_owner(session)
    stats = {"online": 0, "running": 0, "error": 0, "pending_start": 0}
    if not owner:
        return stats
    group_sql, group_params = _client_devices_group_where_clause(device_group)
    conn = _get_db()
    try:
        status_rows = conn.execute(
            f"""
            SELECT status, COUNT(1) AS cnt
            FROM overview_devices_snapshot
            WHERE owner_username = ?{group_sql}
            GROUP BY status
            """,
            [owner, *group_params],
        ).fetchall()
        for row in status_rows:
            st = str(row["status"] or "offline")
            if st in stats:
                stats[st] = int(row["cnt"] or 0)
        pending_row = conn.execute(
            f"""
            SELECT COUNT(1) FROM overview_devices_snapshot
            WHERE owner_username = ?{group_sql} AND pending_start = 1
            """,
            [owner, *group_params],
        ).fetchone()
        stats["pending_start"] = int(pending_row[0] if pending_row else 0)
        return stats
    finally:
        conn.close()


def _list_distinct_device_groups(session: dict) -> list[str]:
    owner = _client_devices_visible_owner(session)
    if not owner:
        return []
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT DISTINCT TRIM(device_group) AS grp
            FROM overview_devices_snapshot
            WHERE owner_username = ? AND COALESCE(TRIM(device_group), '') <> ''
            ORDER BY grp ASC
            """,
            (owner,),
        ).fetchall()
        return [str(r[0]) for r in rows if str(r[0] or "").strip()]
    finally:
        conn.close()


def _paginate_dict_items(items: dict[str, Any], page: int, page_size: int) -> tuple[dict[str, Any], int]:
    safe_page = max(1, int(page or 1))
    safe_size = max(1, min(int(page_size or 50), 500))
    ordered_ids = sorted(
        items.keys(),
        key=lambda did: str((items.get(did) or {}).get("alias") or did).strip().lower(),
    )
    total = len(ordered_ids)
    start = (safe_page - 1) * safe_size
    page_ids = ordered_ids[start : start + safe_size]
    return {did: items[did] for did in page_ids if did in items}, total


def migrate_backup_owner_history():
    return


def init_databases():
    _ensure_sqlite_wal_mode()
    init_follow_db()
    init_autosearch_db()
    init_auth_db()
    _ensure_backup_owner_columns()
    _ensure_user_runtime_configs()
    _ensure_template_permissions()
    migrate_backup_owner_history()
    _ensure_overview_snapshot_tables()


def prune_obsolete_tables():
    """
    彻底砍掉与 mobile_pending_usernames 目标无关的历史模块表。
    注意：会永久删除表及数据。
    """
    conn = _get_db()
    try:
        conn.executescript(
            """
            DROP TABLE IF EXISTS snapshots;
            DROP TABLE IF EXISTS run_results;
            DROP TABLE IF EXISTS run_stats;
            DROP TABLE IF EXISTS task_queue;
            DROP TABLE IF EXISTS follow_edges;
            DROP TABLE IF EXISTS candidate_users;
            DROP TABLE IF EXISTS users;
            DROP TABLE IF EXISTS follower_roots;
            """
        )
        conn.commit()
    finally:
        conn.close()


init_databases()
prune_obsolete_tables()


def make_device_id() -> str:
    # 使用 UUID4，理论上全局唯一；保留 dev_ 前缀便于日志识别
    return "dev_" + uuid.uuid4().hex


@dataclass
class DeviceInfo:
    alias: str
    fingerprint: str
    owner_username: str
    device_group: str = ""
    status: DeviceStatus = "offline"
    pending_start: bool = False
    paused: bool = False
    auto_start_opt_out: bool = False
    auto_switch_enabled: bool = False


@dataclass
class MemoryStore:
    devices: Dict[str, DeviceInfo] = field(default_factory=dict)
    fingerprint_to_device: Dict[str, str] = field(default_factory=dict)
    # 每台设备已完成的“拉取批次”计数（用于按批次触发休息指令）。仅内存态，服务重启后会清零。
    device_pull_rounds: Dict[str, int] = field(default_factory=dict)
    # 每用户配置下发版本：保存 user_runtime_configs 后递增；设备 check_start 比对以触发拉取 get_config。
    user_config_push_revision: Dict[str, int] = field(default_factory=dict)
    default_config: Dict[str, object] = field(default_factory=lambda: dict(MODE_DEFAULT_CONFIGS["growth"]))
    configs_by_mode: Dict[str, Dict[str, object]] = field(
        default_factory=lambda: {mode: dict(cfg) for mode, cfg in MODE_DEFAULT_CONFIGS.items()}
    )
    config: Dict[str, object] = field(default_factory=lambda: dict(MODE_DEFAULT_CONFIGS["growth"]))
    run_mode: Literal["growth", "live", "direct", "mutual"] = "growth"
    # 旧版：全局 auto_start_enabled（管理员开关）。已改为按用户存储在 user_runtime_configs，
    # 这里保留字段仅用于兼容历史 persisted_runtime_state，不再作为实际策略来源。
    auto_start_enabled: bool = False
    scheduler_enabled: bool = True
    scheduler_stop_event: Event = field(default_factory=Event)
    scheduler_task_enabled: Dict[str, bool] = field(default_factory=dict)
    scheduler_task_interval_sec: Dict[str, int] = field(default_factory=dict)
    scheduler_task_last_run_at: Dict[str, str] = field(default_factory=dict)
    auto_switch_enabled: bool = False
    # 自治包版本：每次开启自动切号时递增，写入 device_mode_events.bundle_revision。
    auto_switch_bundle_revision: int = 0
    # 仅用户显式开启自动切号时置 True；重启默认为 False，避免每次启动全量对齐拖垮 SQLite/HTTP。
    auto_switch_align_on_next_tick: bool = False
    auto_switch_last_slot_keys: Dict[str, str] = field(default_factory=dict)
    # bucket_key -> "{schedule_date}|{slot_index}|publish"：当日该时段自动发作品已成功
    auto_switch_publish_done_keys: Dict[str, str] = field(default_factory=dict)
    # bucket_key -> "{schedule_date}|{slot_index}|publish_aborted"：本时段因未登录/时段切换等不再重试
    auto_switch_publish_aborted_keys: Dict[str, str] = field(default_factory=dict)
    # bucket_key -> "{schedule_date}|{slot_index}|{op_id}"：已下发、待设备回传结果
    auto_switch_publish_inflight_keys: Dict[str, str] = field(default_factory=dict)
    backup_stats_full_query_enabled: bool = False
    backup_stats_full_query_last_run_date: str = ""
    backup_stats_full_query_in_flight: bool = False
    mobile_pending_auto_refill_last_run_ts: float = 0.0
    mobile_pending_auto_refill_in_flight: bool = False
    task_processes: Dict[str, subprocess.Popen] = field(default_factory=dict)
    task_logs: Dict[str, List[str]] = field(default_factory=dict)
    # 设备端操作任务队列与结果（用于“备份清理/恢复”）
    pending_device_ops: Dict[str, List[Dict[str, Any]]] = field(default_factory=dict)
    device_op_results: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    # device_id -> {"max_lines": int, "requested_at": float}
    pending_device_log_pulls: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    lock: RLock = field(default_factory=RLock)

    def verify_device(self, device_id: str, alias: str) -> DeviceInfo | None:
        device = self.devices.get((device_id or "").strip())
        if not device:
            return None
        if _normalize_alias_key(device.alias) != _normalize_alias_key(alias):
            return None
        return device


store = MemoryStore()

# 设备热路径持锁上限：超时快速失败，避免 check_start / device_ops 拖死全站。
STORE_LOCK_WAIT_SEC = 2.5
STORE_LOCK_DEVICE_WAIT_SEC = 1.5
# client_devices 异步落库：状态未变且距上次写入不足该间隔则跳过；否则合并批量 commit。
_DEVICE_PERSIST_MIN_INTERVAL_SEC = 30.0
_AUTO_SWITCH_DEVICE_PERSIST_MIN_INTERVAL_SEC = 300.0
_DEVICE_PERSIST_BATCH_INTERVAL_SEC = 0.3
_device_persist_lock = Lock()
_device_persist_pending: set[str] = set()
_device_persist_last_written: Dict[str, tuple] = {}
_device_persist_last_at: Dict[str, float] = {}
_device_persist_wake = Event()
# 管理台 overview 队列统计已迁至 SQLite snapshot + 后台 worker 重建（见 _overview_snapshot_worker_loop）。
# 已有设备开启自动切号自治后，云端 minute tick 仅保活探测，大幅降频。
AUTO_SWITCH_AUTONOMOUS_IDLE_SEC = 300


class StoreLockBusy(RuntimeError):
    def __init__(self, label: str) -> None:
        super().__init__(label)
        self.label = label


_overview_fallback_snapshot: Dict[str, Any] = {
    "devices": {},
    "device_logs": {},
    "list_preview_limit": 200,
    "config": {},
    "run_mode": "growth",
}

# mobile_pending_usernames：管理台大批量导出占用 HTTP 过久，使用后台线程 + job 轮询避免阻塞前端。
_MOBILE_PENDING_EXPORT_JOB_MAX_AGE_SEC = int(2 * 60 * 60)
_mobile_pending_export_jobs: Dict[str, Dict[str, Any]] = {}
_mobile_pending_export_job_lock = Lock()


def _purge_old_mobile_pending_export_jobs_unlocked() -> None:
    now_ts = time.time()
    stale: List[str] = []
    for jid, rec in list(_mobile_pending_export_jobs.items()):
        if rec.get("status") not in ("done", "failed"):
            continue
        fin = float(rec.get("finished_ts") or rec.get("created_ts") or now_ts)
        if now_ts - fin > _MOBILE_PENDING_EXPORT_JOB_MAX_AGE_SEC:
            stale.append(jid)
    for jid in stale:
        _mobile_pending_export_jobs.pop(jid, None)


def _trim_task_log_keys_unlocked() -> None:
    """任务日志当前不再做额外裁剪。"""
    return


@contextlib.contextmanager
def _tracked_store_lock(
    label: str,
    threshold_ms: int = 200,
    *,
    blocking: bool = False,
    wait_sec: float | None = None,
):
    """仅在锁等待过长或持锁过久时打印，避免淹没与「待下发入队」争用相关的信号。

    默认带超时（STORE_LOCK_WAIT_SEC），设备热路径抢不到锁时抛 StoreLockBusy 快速失败。
    启动恢复、shutdown 等路径请传 blocking=True。
    """
    started_at = time.monotonic()
    caller = "unknown"
    try:
        stack = inspect.stack()
        frame = stack[2] if len(stack) > 2 else stack[1]
        caller = f"{Path(frame.filename).name}:{frame.lineno}:{frame.function}"
    except Exception:
        pass
    timeout = None if blocking else (STORE_LOCK_WAIT_SEC if wait_sec is None else wait_sec)
    if blocking:
        acquired = store.lock.acquire()
    else:
        acquired = store.lock.acquire(timeout=timeout)
    if not acquired:
        print(f"[{now_text()}] 锁超时 | {label} | wait>={timeout}s | caller={caller}")
        raise StoreLockBusy(label)
    waited_ms = int((time.monotonic() - started_at) * 1000)
    try:
        yield
    finally:
        held_ms = int((time.monotonic() - started_at) * 1000)
        if waited_ms >= 100:
            print(f"[{now_text()}] 锁等待 | {label} | wait={waited_ms}ms | caller={caller}")
        if held_ms >= threshold_ms:
            print(f"[{now_text()}] 锁耗时 | {label} | hold={held_ms}ms | caller={caller}")
        store.lock.release()


@contextlib.contextmanager
def _store_lock(
    label: str,
    *,
    blocking: bool = False,
    wait_sec: float | None = None,
):
    """替换散落的 `with store.lock`，与 _tracked_store_lock 共用超时语义。"""
    with _tracked_store_lock(label, blocking=blocking, wait_sec=wait_sec):
        yield


def _store_lock_busy_response() -> dict:
    return {"code": 503, "msg": "服务繁忙，请稍后重试"}



def _load_client_devices_from_db() -> None:
    """启动时从 SQLite 恢复设备绑定（含归属客户），并重建内存索引。"""
    conn = _get_db()
    rows: list[Any] = []
    try:
        rows = conn.execute(
            "SELECT device_id, owner_username, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out, auto_switch_enabled FROM client_devices"
        ).fetchall()
    except sqlite3.OperationalError:
        rows = []
    finally:
        conn.close()
    with _tracked_store_lock("_load_client_devices_from_db", blocking=True):
        store.devices.clear()
        store.fingerprint_to_device.clear()
        for row in rows:
            did = str(row["device_id"])
            st = str(row["status"] or "offline")
            if st not in ("offline", "online", "running", "error"):
                st = "offline"
            store.devices[did] = DeviceInfo(
                alias=str(row["alias"]),
                fingerprint=str(row["fingerprint"]),
                owner_username=str(row["owner_username"]),
                device_group=str(row["device_group"] or ""),
                status=st,  # type: ignore[arg-type]
                pending_start=bool(int(row["pending_start"] or 0)),
                paused=bool(int(row["paused"] or 0)),
                auto_start_opt_out=bool(int(row["auto_start_opt_out"] or 0)) if "auto_start_opt_out" in row.keys() else False,
                auto_switch_enabled=bool(int(row["auto_switch_enabled"] or 0)) if "auto_switch_enabled" in row.keys() else False,
            )
            store.fingerprint_to_device[str(row["fingerprint"])] = did
    if rows:
        print(f"[{now_text()}] 系统 | 已从数据库恢复 {len(rows)} 台绑定设备")


def _device_persist_row_snapshot(dev: DeviceInfo) -> tuple:
    return (
        str(dev.owner_username or ""),
        str(dev.alias or ""),
        str(dev.device_group or ""),
        str(dev.fingerprint or ""),
        str(dev.status or "offline"),
        bool(dev.pending_start),
        bool(dev.paused),
        bool(dev.auto_start_opt_out),
        bool(dev.auto_switch_enabled),
    )


def _write_client_devices_batch(device_ids: Iterable[str]) -> int:
    """批量 upsert client_devices（单次 commit），仅由落库 worker / shutdown flush 调用。"""
    unique_ids = list(dict.fromkeys(str(d or "").strip() for d in device_ids if str(d or "").strip()))
    if not unique_ids:
        return 0
    rows: List[tuple[str, tuple]] = []
    with store.lock:
        for did in unique_ids:
            dev = store.devices.get(did)
            if dev:
                rows.append((did, _device_persist_row_snapshot(dev)))
    if not rows:
        return 0

    t0 = time.perf_counter()
    now = now_text()
    conn = _get_db()
    written = 0
    try:
        for did, snap in rows:
            (
                owner,
                alias,
                device_group,
                fingerprint,
                status,
                pending_start,
                paused,
                auto_start_opt_out,
                auto_switch_enabled,
            ) = snap
            pending_i = 1 if pending_start else 0
            paused_i = 1 if paused else 0
            auto_start_i = 1 if auto_start_opt_out else 0
            auto_switch_i = 1 if auto_switch_enabled else 0
            exists = conn.execute("SELECT created_at FROM client_devices WHERE device_id = ?", (did,)).fetchone()
            if exists:
                conn.execute(
                    """
                    UPDATE client_devices SET owner_username = ?, alias = ?, device_group = ?, fingerprint = ?, status = ?,
                        pending_start = ?, paused = ?, auto_start_opt_out = ?, auto_switch_enabled = ?, updated_at = ?
                    WHERE device_id = ?
                    """,
                    (
                        owner,
                        alias,
                        device_group,
                        fingerprint,
                        status,
                        pending_i,
                        paused_i,
                        auto_start_i,
                        auto_switch_i,
                        now,
                        did,
                    ),
                )
            else:
                conn.execute(
                    """
                    INSERT INTO client_devices (device_id, owner_username, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out, auto_switch_enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        did,
                        owner,
                        alias,
                        device_group,
                        fingerprint,
                        status,
                        pending_i,
                        paused_i,
                        auto_start_i,
                        auto_switch_i,
                        now,
                        now,
                    ),
                )
            _device_persist_last_written[did] = snap
            _device_persist_last_at[did] = time.monotonic()
            written += 1
        conn.commit()
    finally:
        conn.close()
    elapsed_ms = (time.perf_counter() - t0) * 1000
    if elapsed_ms >= 200 or written >= 8:
        print(f"[{now_text()}] persist_client_device | batch | n={written} elapsed={elapsed_ms:.1f}ms")
    return written


def _device_persist_min_interval_sec(device_id: str) -> float:
    if _device_auto_switch_enabled(device_id):
        return _AUTO_SWITCH_DEVICE_PERSIST_MIN_INTERVAL_SEC
    return _DEVICE_PERSIST_MIN_INTERVAL_SEC


def _persist_client_device_to_db(device_id: str, *, force: bool = False) -> None:
    """将单台设备的当前内存状态写入 client_devices（默认异步合并；force 时立即落库）。"""
    did = str(device_id or "").strip()
    if not did:
        return
    with store.lock:
        dev = store.devices.get(did)
        if not dev:
            return
        snapshot = _device_persist_row_snapshot(dev)
    now_mono = time.monotonic()
    min_interval = _device_persist_min_interval_sec(did)
    if not force:
        last_at = _device_persist_last_at.get(did, 0.0)
        last_snap = _device_persist_last_written.get(did)
        # 快照未变：完全跳过（内存与 DB 已一致）
        if last_snap == snapshot and (now_mono - last_at) < min_interval:
            return
        # 快照已变但距上次落库不足间隔：仅标记 dirty，由 worker 在间隔到期后合并写入
        if (now_mono - last_at) < min_interval:
            with _device_persist_lock:
                _device_persist_pending.add(did)
            return
    if force:
        _write_client_devices_batch([did])
        return
    with _device_persist_lock:
        _device_persist_pending.add(did)
    _device_persist_wake.set()


def _flush_device_persist_queue_blocking() -> None:
    """shutdown 时排空待落库队列。"""
    for _ in range(4):
        with _device_persist_lock:
            batch = list(_device_persist_pending)
            _device_persist_pending.clear()
        if batch:
            _write_client_devices_batch(batch)
        else:
            break


def _device_persist_worker_loop() -> None:
    while not _APP_SHUTDOWN_EVENT.is_set():
        _device_persist_wake.wait(timeout=_DEVICE_PERSIST_BATCH_INTERVAL_SEC)
        _device_persist_wake.clear()
        if _APP_SHUTDOWN_EVENT.is_set():
            break
        with _device_persist_lock:
            if not _device_persist_pending:
                continue
            pending = list(_device_persist_pending)
        now_mono = time.monotonic()
        eligible = [
            did
            for did in pending
            if (now_mono - _device_persist_last_at.get(did, 0.0)) >= _device_persist_min_interval_sec(did)
        ]
        if not eligible:
            continue
        with _device_persist_lock:
            for did in eligible:
                _device_persist_pending.discard(did)
        _write_client_devices_batch(eligible)


def _start_device_persist_worker() -> None:
    Thread(target=_device_persist_worker_loop, name="client_device_persist", daemon=True).start()


def _seed_device_persist_throttle_from_memory() -> None:
    """重启后从内存设备态初始化节流快照，避免每台设备首轮 sync 都写库。"""
    now_mono = time.monotonic()
    with store.lock:
        items = [(did, _device_persist_row_snapshot(dev)) for did, dev in store.devices.items()]
    for did, snap in items:
        _device_persist_last_written[did] = snap
        _device_persist_last_at[did] = now_mono


def _load_persisted_runtime_state():
    """启动时恢复全局运行状态与默认模板配置。"""
    conn = _get_db()
    try:
        row = conn.execute("SELECT json FROM persisted_runtime_state WHERE id = 1").fetchone()
        if not row or not row[0]:
            return
        blob = json.loads(row[0])
        if not isinstance(blob, dict):
            return
        cfgs = blob.get("configs_by_mode")
        if isinstance(cfgs, dict):
            merged_cfgs: Dict[str, Dict[str, object]] = {}
            for mode, base in MODE_DEFAULT_CONFIGS.items():
                mode_cfg = dict(base)
                incoming = cfgs.get(mode)
                if isinstance(incoming, dict):
                    mode_cfg.update(incoming)
                merged_cfgs[mode] = mode_cfg
            with _tracked_store_lock("_load_persisted_runtime_state:configs_by_mode", blocking=True):
                store.configs_by_mode = merged_cfgs
                store.config = dict(merged_cfgs.get("growth", MODE_DEFAULT_CONFIGS["growth"]))
        mode = blob.get("run_mode")
        if mode in RUN_MODES:
            with _tracked_store_lock("_load_persisted_runtime_state:run_mode", blocking=True):
                store.run_mode = mode  # type: ignore[assignment]
                store.config = dict(store.configs_by_mode.get(mode, MODE_DEFAULT_CONFIGS[mode]))
        auto_start = blob.get("auto_start_enabled")
        if isinstance(auto_start, bool):
            with _tracked_store_lock("_load_persisted_runtime_state:auto_start", blocking=True):
                store.auto_start_enabled = auto_start
        auto_sw = blob.get("auto_switch_enabled")
        if isinstance(auto_sw, bool):
            with _tracked_store_lock("_load_persisted_runtime_state:auto_switch", blocking=True):
                store.auto_switch_enabled = auto_sw
        auto_sw_rev = blob.get("auto_switch_bundle_revision")
        if auto_sw_rev is not None:
            try:
                with _tracked_store_lock("_load_persisted_runtime_state:auto_switch_bundle_revision", blocking=True):
                    store.auto_switch_bundle_revision = max(0, int(auto_sw_rev))
            except (TypeError, ValueError):
                pass
        last_slots = blob.get("auto_switch_last_slot_keys")
        if isinstance(last_slots, dict):
            with _tracked_store_lock("_load_persisted_runtime_state:auto_switch_slots", blocking=True):
                store.auto_switch_last_slot_keys = {
                    str(k): str(v) for k, v in last_slots.items() if str(k).strip() and str(v).strip()
                }
        publish_done = blob.get("auto_switch_publish_done_keys")
        if isinstance(publish_done, dict):
            with _tracked_store_lock("_load_persisted_runtime_state:auto_switch_publish_done", blocking=True):
                store.auto_switch_publish_done_keys = {
                    str(k): str(v) for k, v in publish_done.items() if str(k).strip() and str(v).strip()
                }
        publish_aborted = blob.get("auto_switch_publish_aborted_keys")
        if isinstance(publish_aborted, dict):
            with _tracked_store_lock("_load_persisted_runtime_state:auto_switch_publish_aborted", blocking=True):
                store.auto_switch_publish_aborted_keys = {
                    str(k): str(v) for k, v in publish_aborted.items() if str(k).strip() and str(v).strip()
                }
        publish_inflight = blob.get("auto_switch_publish_inflight_keys")
        if isinstance(publish_inflight, dict):
            with _tracked_store_lock("_load_persisted_runtime_state:auto_switch_publish_inflight", blocking=True):
                store.auto_switch_publish_inflight_keys = {
                    str(k): str(v) for k, v in publish_inflight.items() if str(k).strip() and str(v).strip()
                }
        bs_enabled = blob.get("backup_stats_full_query_enabled")
        if isinstance(bs_enabled, bool):
            with _tracked_store_lock("_load_persisted_runtime_state:backup_stats_full_query", blocking=True):
                store.backup_stats_full_query_enabled = bs_enabled
        bs_last = blob.get("backup_stats_full_query_last_run_date")
        if isinstance(bs_last, str):
            with _tracked_store_lock("_load_persisted_runtime_state:backup_stats_last", blocking=True):
                store.backup_stats_full_query_last_run_date = bs_last.strip()
        dc = blob.get("default_config")
        if isinstance(dc, dict):
            dm = dict(MODE_DEFAULT_CONFIGS["growth"])
            dm.update(dc)
            with _tracked_store_lock("_load_persisted_runtime_state:default_config", blocking=True):
                store.default_config = dm
        print(f"[{now_text()}] 系统 | 已从数据库恢复运行配置")
    except sqlite3.OperationalError:
        pass
    except Exception as exc:
        print(f"[{now_text()}] 系统 | 恢复持久化配置失败：{exc}")
    finally:
        conn.close()


def _load_user_runtime_state(username: str, *, use_cache: bool = True) -> dict:
    owner = str(username or "").strip()
    if not owner:
        return {}
    if use_cache:
        now = time.monotonic()
        with _user_runtime_state_cache_lock:
            cached = _user_runtime_state_cache.get(owner)
            if cached:
                payload, expires_at = cached
                if now < expires_at:
                    return copy.deepcopy(payload)
    conn = _get_db()
    try:
        rows = conn.execute(
            "SELECT config_key, config_json FROM user_runtime_configs WHERE username = ?",
            (owner,),
        ).fetchall()
        result: dict = {}
        for key, raw in rows:
            try:
                result[str(key)] = json.loads(raw or "{}")
            except Exception:
                continue
    finally:
        conn.close()
    if use_cache:
        with _user_runtime_state_cache_lock:
            _user_runtime_state_cache[owner] = (
                copy.deepcopy(result),
                time.monotonic() + _USER_RUNTIME_STATE_CACHE_TTL_SEC,
            )
    return result


def _save_user_runtime_state(username: str, payload: dict):
    conn = _get_db()
    try:
        for key, obj in payload.items():
            conn.execute(
                "INSERT OR REPLACE INTO user_runtime_configs (username, config_key, config_json, updated_at) VALUES (?, ?, ?, ?)",
                (username, key, json.dumps(obj, ensure_ascii=False), now_text()),
            )
        conn.commit()
        u = (username or "").strip()
        if u:
            _invalidate_user_runtime_state_cache(u)
            try:
                with _store_lock("save_user_runtime_state:push_rev", wait_sec=STORE_LOCK_DEVICE_WAIT_SEC):
                    store.user_config_push_revision[u] = int(store.user_config_push_revision.get(u, 0)) + 1
            except StoreLockBusy:
                print(f"[{now_text()}] save_user_runtime_state | store_lock_busy | user={u}")
    finally:
        conn.close()


def _load_raw_configs_by_template_blob(username: str) -> object | None:
    """直接从 DB 读取 configs_by_template 原始 JSON，不经 normalize 过滤。"""
    conn = _get_db()
    try:
        row = conn.execute(
            "SELECT config_json FROM user_runtime_configs WHERE username = ? AND config_key = ?",
            (username, "configs_by_template"),
        ).fetchone()
        if not row:
            return None
        try:
            return json.loads(row[0] or "{}")
        except Exception:
            return None
    finally:
        conn.close()


def _parse_raw_template_keys(blob: object) -> tuple[list[str], list[str]]:
    """返回 (所有键名, 值非 dict 的键名)。根不是对象时键名为 ['<invalid_root>']。"""
    if blob is None:
        return [], []
    if not isinstance(blob, dict):
        return ["<invalid_root>"], ["<invalid_root>"]
    keys: list[str] = []
    bad: list[str] = []
    for k, v in blob.items():
        name = str(k or "").strip()
        if not name:
            continue
        keys.append(name)
        if not isinstance(v, dict):
            bad.append(name)
    keys.sort(key=lambda x: x.lower())
    bad.sort(key=lambda x: x.lower())
    return keys, bad


def _collect_orphan_alias_template_refs(data: dict, effective_keys: set[str]) -> list[dict[str, str]]:
    out: list[dict[str, str]] = []
    alias_map_all = data.get("alias_template_by_mode")
    if not isinstance(alias_map_all, dict):
        return out
    for mode_key in RUN_MODES:
        mode_map = alias_map_all.get(mode_key)
        if not isinstance(mode_map, dict):
            continue
        for raw_alias, raw_template in mode_map.items():
            alias = _normalize_alias_key(raw_alias)
            ref = str(raw_template or "").strip()
            if not alias or not ref:
                continue
            if ref not in effective_keys:
                out.append({"mode": mode_key, "alias": alias, "ref": ref})
    return out


def _default_user_config_state() -> dict:
    default_template = "default"
    return {
        "run_mode": "growth",
        "active_template": default_template,
        "configs_by_template": {default_template: dict(MODE_DEFAULT_CONFIGS["growth"])},
        "configs_by_mode": {mode: dict(cfg) for mode, cfg in MODE_DEFAULT_CONFIGS.items()},
        "default_config": dict(MODE_DEFAULT_CONFIGS["growth"]),
        # 是否开启「设备端自动启动」策略（按用户）
        "auto_start_enabled": False,
    }


def _sanitize_template_name(raw: object, fallback: str = "default") -> str:
    text = str(raw or "").strip()
    if not text:
        return fallback
    return text[:64]


def _shared_template_key(source_username: str, template_name: str) -> str:
    return f"shared::{source_username}::{template_name}"


def _normalize_alias_key(raw: object) -> str:
    text = str(raw or "").strip()
    return text.lower()


def _normalize_tiktok_username_key(raw: object) -> str:
    text = str(raw or "").strip().lstrip("@")
    return text.lower()


def _sanitize_username_backup_token(username: str) -> str:
    """与 Android UnifiedBackupPaths.sanitizeUsernameForBackupFilename 规则一致。"""
    text = _normalize_tiktok_username_key(username)
    if not text:
        return ""
    cleaned = []
    for ch in text:
        if ch.isalnum() or ch in "._-":
            cleaned.append(ch)
        else:
            cleaned.append("_")
    return "".join(cleaned).strip("_")


def _backup_name_contains_username(backup_name: str, username: str) -> bool:
    token = _sanitize_username_backup_token(username)
    if not token:
        return False
    return token in str(backup_name or "").lower()


def _alias_account_template_key(alias: object, tiktok_username: object) -> str:
    """别名设备上某 TikTok 账号的配置映射键（与仅 alias 的设备级键并存，解析时账号键优先）。"""
    alias_clean = _normalize_alias_key(alias)
    user_clean = _normalize_tiktok_username_key(tiktok_username)
    if not alias_clean or not user_clean:
        return alias_clean
    return f"{alias_clean}::{user_clean}"


def _device_editor_effective_alias(row: dict) -> str:
    assigned = str(row.get("assigned_alias") or "").strip()
    if assigned:
        return _normalize_alias_key(assigned)
    return _normalize_alias_key(row.get("device_alias"))


def _auto_switch_slot_end_min(slot: dict[str, Any], slots: list[dict[str, Any]]) -> int:
    """时段右边界（左闭右开）；缺省为当日结束 1440。"""
    try:
        end = int(slot.get("end_min")) if slot.get("end_min") is not None else 0
    except (TypeError, ValueError):
        end = 0
    return end if end > 0 else 1440


def _resolve_slot_highlight_row_index(
    owner_username: str,
    alias: str,
    schedule_date: str | None = None,
    minute_of_day: int | None = None,
    rows: list[dict[str, Any]] | None = None,
) -> int:
    """自动切号桶：按当前北京时段计划解析应高亮的 row_index。"""
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner or not alias_key:
        return -1
    bucket_rows = rows
    if bucket_rows is None:
        buckets = _list_device_editor_buckets(owner)
        bucket_rows = buckets.get(alias_key, [])
    if not bucket_rows:
        return -1
    if len(bucket_rows) == 1:
        return int(bucket_rows[0].get("row_index") or 0)
    date_key = str(schedule_date or "").strip() or _beijing_date_str()
    minute = max(0, min(int(minute_of_day if minute_of_day is not None else _beijing_minute_of_day()), 1439))
    try:
        weekly_plan = _ensure_auto_switch_weekly_plan(owner, alias_key, bucket_rows, date_key)
        plan = _daily_plan_for_schedule_date(weekly_plan, date_key)
        if not plan:
            return -1
        target_slot = _auto_switch_target_row(plan, minute)
        if not target_slot:
            return -1
        try:
            ri = int(target_slot.get("row_index"))
        except (TypeError, ValueError):
            return -1
        return ri if ri >= 0 else -1
    except Exception:
        return -1


def _sync_switch_highlight_from_slot(
    conn: sqlite3.Connection,
    owner_username: str,
    alias: str,
    schedule_date: str | None = None,
    minute_of_day: int | None = None,
) -> int:
    """将别名桶内 switch_highlight 与当前时段计划对齐（多账号桶）；单账号桶恒为是。"""
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner or not alias_key:
        return -1
    buckets = _list_device_editor_buckets(owner)
    rows = buckets.get(alias_key, [])
    if not rows:
        return -1
    if len(rows) == 1:
        rid = int(rows[0].get("row_index") or 0)
        if rid >= 0:
            _db_apply_switch_highlight_exclusive(conn, owner, rid)
        return rid
    ri = _resolve_slot_highlight_row_index(
        owner, alias_key, schedule_date, minute_of_day, rows,
    )
    if ri >= 0:
        _db_apply_switch_highlight_exclusive(conn, owner, ri)
    return ri


def _pick_device_editor_bucket_row(
    matched: list[dict[str, Any]],
    owner_username: str = "",
    alias: str = "",
    schedule_date: str | None = None,
    minute_of_day: int | None = None,
) -> dict[str, Any] | None:
    """同别名桶内解析当前账号行：多账号按时段计划，单条桶回退该行。"""
    if not matched:
        return None
    if len(matched) == 1:
        return matched[0]
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if owner and alias_key:
        sched = schedule_date if schedule_date is not None else _beijing_date_str()
        minute = minute_of_day if minute_of_day is not None else _beijing_minute_of_day()
        ri = _resolve_slot_highlight_row_index(
            owner, alias_key, sched, minute, matched,
        )
        if ri >= 0:
            for row in matched:
                if int(row.get("row_index") or -1) == ri:
                    return row
    yes_rows = [r for r in matched if _normalize_switch_highlight(r.get("switch_highlight")) == "是"]
    if len(yes_rows) == 1:
        return yes_rows[0]
    if len(yes_rows) > 1:
        return min(yes_rows, key=lambda r: int(r.get("row_index") or 0))
    return None


def _account_assigned_alias_key(
    conn: sqlite3.Connection, owner_username: str, username: str
) -> str:
    """账号表 assigned_alias（为空时从设备档案反查）。"""
    owner = str(owner_username or "").strip()
    uname = _normalized_alias(username).lstrip("@")
    if not owner or not uname:
        return ""
    row = conn.execute(
        """
        SELECT assigned_alias
        FROM account_editor_rows
        WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))
        LIMIT 1
        """,
        (owner, uname),
    ).fetchone()
    if not row:
        return ""
    alias = str(row["assigned_alias"] or "").strip()
    if alias:
        return _normalize_alias_key(alias)
    recovered = _recover_assigned_alias_for_account_username(conn, owner, uname)
    return _normalize_alias_key(recovered) if recovered else ""


def _validate_username_device_effective_alias(
    conn: sqlite3.Connection,
    owner_username: str,
    username: str,
    effective_alias: str,
) -> str | None:
    """校验账号表分配别名与设备档案有效别名一致；通过返回 None，否则返回错误说明。"""
    owner = str(owner_username or "").strip()
    uname = _normalized_alias(username).lstrip("@")
    eff = _normalize_alias_key(effective_alias)
    if not owner or not uname or not eff:
        return None
    acct = conn.execute(
        """
        SELECT 1 FROM account_editor_rows
        WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))
        LIMIT 1
        """,
        (owner, uname),
    ).fetchone()
    if not acct:
        return f"账号「{uname}」不存在于账号表单，请先添加账号记录"
    acct_alias = _account_assigned_alias_key(conn, owner, uname)
    if acct_alias and acct_alias != eff:
        display_alias = _normalized_alias(effective_alias)
        return (
            f"账号「{uname}」在账号表单中的分配别名为「{acct_alias}」，"
            f"与设备档案有效别名「{display_alias}」不一致，请先在账号表单修正分配关系"
        )
    return None


def _resolve_switch_highlight_tiktok_username(owner_username: str, alias: str) -> str:
    """按 device_editor_rows 切号高亮解析该别名桶内当前 TikTok 账号。"""
    owner = str(owner_username or "").strip()
    alias_clean = _normalize_alias_key(alias)
    if not owner or not alias_clean:
        return ""
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT row_index, username, switch_highlight, device_alias, assigned_alias
            FROM device_editor_rows
            WHERE owner_username = ?
            """,
            (owner,),
        ).fetchall()
    finally:
        conn.close()
    matched: list[dict] = []
    for row in rows or []:
        item = {
            "row_index": int(row[0]) if row[0] is not None else 0,
            "username": str(row[1] or "").strip(),
            "switch_highlight": _normalize_switch_highlight(row[2]),
            "device_alias": str(row[3] or "").strip(),
            "assigned_alias": str(row[4] or "").strip(),
        }
        if _device_editor_effective_alias(item) != alias_clean:
            continue
        matched.append(item)
    pick = _pick_device_editor_bucket_row(matched, owner, alias_clean)
    if not pick:
        return ""
    return _normalize_tiktok_username_key(pick.get("username"))


NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN = -1


def _parse_video_count_raw(value: object) -> int | None:
    """解析 video_count 原始值（可含负数）；空值返回 None。"""
    if value is None:
        return None
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    text = str(value or "").strip().replace(",", "")
    if not text:
        return None
    try:
        return int(float(text))
    except (TypeError, ValueError):
        return None


def _default_video_count_for_new_row(conn, owner_username: str, username: str = "") -> str:
    """新行默认作品数：优先沿用账号表已有值，否则为 -1（新号 24h 内不发作品标记）。"""
    owner = str(owner_username or "").strip()
    uname = str(username or "").strip()
    if owner and uname:
        row = conn.execute(
            """
            SELECT video_count FROM account_editor_rows
            WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))
            LIMIT 1
            """,
            (owner, uname),
        ).fetchone()
        if row:
            vc = str(row[0] or "").strip()
            if vc:
                return vc
    return str(NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN)


def _parse_stat_int(value: object) -> int:
    if value is None:
        return 0
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return max(0, value)
    text = str(value or "").strip().replace(",", "")
    if not text:
        return 0
    try:
        return max(0, int(float(text)))
    except (TypeError, ValueError):
        return 0


def _get_highlight_account_stats(owner_username: str, alias: str) -> dict[str, object]:
    """按别名桶内切号高亮行读取粉丝数、作品数（用于智能模板分支）。"""
    owner = str(owner_username or "").strip()
    alias_clean = _normalize_alias_key(alias)
    empty = {
        "tiktok_username": "",
        "follower_count": 0,
        "video_count": 0,
        "switch_highlight": "否",
    }
    if not owner or not alias_clean:
        return empty
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT row_index, username, switch_highlight, device_alias, assigned_alias,
                   video_count, follower_count
            FROM device_editor_rows
            WHERE owner_username = ?
            """,
            (owner,),
        ).fetchall()
    finally:
        conn.close()
    matched: list[dict] = []
    for row in rows or []:
        item = {
            "row_index": int(row[0]) if row[0] is not None else 0,
            "username": str(row[1] or "").strip(),
            "switch_highlight": _normalize_switch_highlight(row[2]),
            "device_alias": str(row[3] or "").strip(),
            "assigned_alias": str(row[4] or "").strip(),
            "video_count": row[5] if len(row) > 5 else "",
            "follower_count": row[6] if len(row) > 6 else "",
        }
        if _device_editor_effective_alias(item) != alias_clean:
            continue
        matched.append(item)
    if not matched:
        return empty
    pick = _pick_device_editor_bucket_row(matched, owner, alias_clean)
    if not pick:
        return empty
    return {
        "tiktok_username": _normalize_tiktok_username_key(pick.get("username")),
        "follower_count": _parse_stat_int(pick.get("follower_count")),
        "video_count": _parse_stat_int(pick.get("video_count")),
        "switch_highlight": "是",
    }


def _normalize_smart_template_entries(raw: object) -> list[dict[str, object]]:
    if not isinstance(raw, list):
        return []
    out: list[dict[str, object]] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        name = str(item.get("template_name") or "").strip()
        if not name:
            continue
        try:
            weight = int(item.get("weight") or 0)
        except (TypeError, ValueError):
            weight = 0
        out.append({"template_name": name[:128], "weight": max(0, min(10000, weight))})
    return out


def _normalize_smart_template_config(raw: object | None) -> dict[str, object]:
    base = dict(DEFAULT_SMART_TEMPLATE_CONFIG)
    if not isinstance(raw, dict):
        return base
    try:
        min_followers = int(raw.get("old_branch_min_followers", base["old_branch_min_followers"]))
    except (TypeError, ValueError):
        min_followers = int(base["old_branch_min_followers"])
    try:
        min_video = int(raw.get("old_branch_min_video_count", base["old_branch_min_video_count"]))
    except (TypeError, ValueError):
        min_video = int(base["old_branch_min_video_count"])
    new_entries = _normalize_smart_template_entries(raw.get("new_branch_templates"))
    old_entries = _normalize_smart_template_entries(raw.get("old_branch_templates"))
    return {
        "old_branch_min_followers": max(0, min_followers),
        "old_branch_min_video_count": max(0, min_video),
        "new_branch_templates": new_entries or list(base["new_branch_templates"]),
        "old_branch_templates": old_entries or list(base["old_branch_templates"]),
    }


def _normalize_alias_smart_template_by_mode(raw: object | None) -> Dict[str, Dict[str, bool]]:
    empty = {mode: {} for mode in RUN_MODES}
    if not isinstance(raw, dict):
        return empty
    for mode_key in RUN_MODES:
        mode_map = raw.get(mode_key)
        if not isinstance(mode_map, dict):
            continue
        for raw_alias, raw_enabled in mode_map.items():
            alias = _normalize_alias_key(raw_alias)
            if not alias:
                continue
            empty[mode_key][alias] = bool(raw_enabled)
    return empty


def _alias_smart_template_enabled(data: dict, mode: str, alias: str) -> bool:
    alias_clean = _normalize_alias_key(alias)
    if not alias_clean:
        return False
    mode_key = mode if mode in RUN_MODES else str(data.get("run_mode") or "growth")
    if mode_key not in SMART_TEMPLATE_RUN_MODES:
        return False
    by_mode = data.get("alias_smart_template_by_mode")
    if not isinstance(by_mode, dict):
        return False
    # 开关统一记在 growth 桶，避免与其它运行模式混用
    mode_map = by_mode.get("growth")
    if not isinstance(mode_map, dict):
        return False
    return bool(mode_map.get(alias_clean))


def _stable_weighted_template_pick(seed: str, entries: list[dict[str, object]], valid_names: set[str]) -> str:
    weighted: list[tuple[str, int]] = []
    for item in entries:
        name = str(item.get("template_name") or "").strip()
        if not name or name not in valid_names:
            continue
        try:
            weight = int(item.get("weight") or 0)
        except (TypeError, ValueError):
            weight = 0
        if weight > 0:
            weighted.append((name, weight))
    if not weighted:
        return ""
    total = sum(w for _, w in weighted)
    if total <= 0:
        return weighted[0][0]
    digest = hashlib.sha256(str(seed or "").encode("utf-8")).hexdigest()
    roll = int(digest[:8], 16) % total
    acc = 0
    for name, weight in weighted:
        acc += weight
        if roll < acc:
            return name
    return weighted[-1][0]


def _get_account_stats_for_smart_template(
    owner_username: str,
    alias: str,
    tiktok_username: str | None = None,
    account_stats: dict[str, object] | None = None,
) -> dict[str, object]:
    """读取指定账号（或高亮账号）的粉丝/作品数，供智能模板分支判定。"""
    if isinstance(account_stats, dict) and account_stats:
        return {
            "tiktok_username": _normalize_tiktok_username_key(account_stats.get("tiktok_username")),
            "follower_count": _parse_stat_int(account_stats.get("follower_count")),
            "video_count": _parse_stat_int(account_stats.get("video_count")),
        }
    owner = str(owner_username or "").strip()
    alias_clean = _normalize_alias_key(alias)
    account = _normalize_tiktok_username_key(tiktok_username or "")
    if owner and alias_clean and account:
        conn = _get_db()
        try:
            rows = conn.execute(
                """
                SELECT username, video_count, follower_count, device_alias, assigned_alias
                FROM device_editor_rows
                WHERE owner_username = ?
                """,
                (owner,),
            ).fetchall()
        finally:
            conn.close()
        for row in rows or []:
            item = {
                "username": str(row[0] or "").strip(),
                "video_count": row[1] if len(row) > 1 else "",
                "follower_count": row[2] if len(row) > 2 else "",
                "device_alias": str(row[3] or "").strip() if len(row) > 3 else "",
                "assigned_alias": str(row[4] or "").strip() if len(row) > 4 else "",
            }
            if _device_editor_effective_alias(item) != alias_clean:
                continue
            if not _normalize_tiktok_username_key(item.get("username")) == account:
                continue
            return {
                "tiktok_username": account,
                "follower_count": _parse_stat_int(item.get("follower_count")),
                "video_count": _parse_stat_int(item.get("video_count")),
            }
    return _get_highlight_account_stats(owner_username, alias_clean)


def _smart_template_weighted_roll(seed: str, entries: list[dict[str, object]], valid_names: set[str]) -> tuple[int, int]:
    weighted: list[tuple[str, int]] = []
    for item in entries:
        name = str(item.get("template_name") or "").strip()
        if not name or name not in valid_names:
            continue
        try:
            weight = int(item.get("weight") or 0)
        except (TypeError, ValueError):
            weight = 0
        if weight > 0:
            weighted.append((name, weight))
    total = sum(w for _, w in weighted)
    if total <= 0:
        return 0, 0
    digest = hashlib.sha256(str(seed or "").encode("utf-8")).hexdigest()
    roll = int(digest[:8], 16) % total
    return roll, total


def _resolve_smart_template_for_alias(
    owner_username: str,
    data: dict,
    mode: str,
    alias: str,
    templates: Dict[str, Dict[str, object]],
    *,
    tiktok_username: str | None = None,
    account_stats: dict[str, object] | None = None,
    session_id: str = "",
) -> tuple[str, dict[str, object]]:
    """智能模板：按账号粉丝/作品数选分支，再按权重命中具体模板。

    session_id 非空时写入 seed，使同一 session 每次 enter 重新掷概率；
    管理台预览/普通 getConfig 不传 session_id，保持按账号稳定命中。
    """
    mode_key = mode if mode in RUN_MODES else str(data.get("run_mode") or "growth")
    alias_clean = _normalize_alias_key(alias)
    cfg = _normalize_smart_template_config(data.get("smart_template_config"))
    stats = _get_account_stats_for_smart_template(
        owner_username,
        alias_clean,
        tiktok_username=tiktok_username,
        account_stats=account_stats,
    )
    followers = int(stats.get("follower_count") or 0)
    videos = int(stats.get("video_count") or 0)
    min_followers = int(cfg.get("old_branch_min_followers") or 0)
    min_videos = int(cfg.get("old_branch_min_video_count") or 0)
    is_old_branch = followers > min_followers and videos > min_videos
    branch = "old" if is_old_branch else "new"
    branch_entries = (
        cfg.get("old_branch_templates")
        if is_old_branch
        else cfg.get("new_branch_templates")
    )
    if not isinstance(branch_entries, list):
        branch_entries = []
    account = str(stats.get("tiktok_username") or "").strip()
    session_part = str(session_id or "").strip()
    seed_base = f"{owner_username}|{mode_key}|{alias_clean}|{account}|{branch}"
    seed = f"{session_part}|{seed_base}" if session_part else seed_base
    valid_names = set(templates.keys())
    roll, total_weight = _smart_template_weighted_roll(seed, branch_entries, valid_names)
    picked = _stable_weighted_template_pick(seed, branch_entries, valid_names)
    stored_active = str(data.get("active_template") or "").strip()
    fallback = stored_active if stored_active in valid_names else (sorted(valid_names)[0] if valid_names else "default")
    selected = picked if picked in valid_names else fallback
    debug = {
        "branch": branch,
        "branch_label": "老号分支" if is_old_branch else "新号分支",
        "follower_count": followers,
        "video_count": videos,
        "tiktok_username": account,
        "threshold_followers": min_followers,
        "threshold_video_count": min_videos,
        "seed": seed,
        "picked_template": picked,
        "selected_template": selected,
        "roll": roll,
        "total_weight": total_weight,
        "session_id": session_part,
    }
    return selected, debug


def _parse_template_ref(template_ref: object) -> tuple[str, str, bool]:
    text = str(template_ref or "").strip()
    if text.startswith("shared::"):
        parts = text.split("::", 2)
        if len(parts) == 3:
            owner = _sanitize_template_name(parts[1], "admin")
            name = _sanitize_template_name(parts[2], "default")
            return owner, name, True
    return "", _sanitize_template_name(text, "default"), False


def _load_template_grants_for_user(target_username: str) -> List[Dict[str, str]]:
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT source_username, template_name
            FROM template_permissions
            WHERE target_username = ?
            ORDER BY source_username, template_name
            """,
            (target_username,),
        ).fetchall()
        return [
            {
                "source_username": str(r[0] or "").strip(),
                "template_name": str(r[1] or "").strip(),
            }
            for r in rows
            if str(r[0] or "").strip() and str(r[1] or "").strip()
        ]
    finally:
        conn.close()


def _normalize_mutual_traffic_action_mutex(cfg: dict) -> dict:
    """「互点-仅关注」与评论/作者区/Followers 三项引流动作互斥。"""
    out = dict(cfg)
    follow_only = bool(out.get("mutual_traffic_follow_only"))
    has_traffic_action = any(
        bool(out.get(k))
        for k in (
            "mutual_traffic_comment",
            "mutual_traffic_author_follow",
            "mutual_traffic_followers_follow",
        )
    )
    if follow_only:
        out["mutual_traffic_comment"] = False
        out["mutual_traffic_author_follow"] = False
        out["mutual_traffic_followers_follow"] = False
    elif has_traffic_action:
        out["mutual_traffic_follow_only"] = False
    else:
        out.setdefault("mutual_traffic_follow_only", False)
    return out


def _migrate_mutual_traffic_config(cfg: dict) -> dict:
    """将已废弃的 mutual_left_swipe_follow 迁移为三项引流开关；缺省与历史「完整互点」一致。"""
    out = dict(cfg)
    has_new = any(
        k in out
        for k in (
            "mutual_traffic_comment",
            "mutual_traffic_author_follow",
            "mutual_traffic_followers_follow",
            "mutual_traffic_follow_only",
        )
    )
    out.pop("mutual_left_swipe_follow", None)
    if not has_new:
        out.setdefault("mutual_traffic_comment", True)
        out.setdefault("mutual_traffic_author_follow", True)
        out.setdefault("mutual_traffic_followers_follow", True)
    else:
        out.setdefault("mutual_traffic_comment", True)
        out.setdefault("mutual_traffic_author_follow", True)
        out.setdefault("mutual_traffic_followers_follow", True)
    out.setdefault("mutual_traffic_follow_only", False)
    return _normalize_mutual_traffic_action_mutex(out)


def _build_effective_template_bundle(username: str, data: dict) -> tuple[Dict[str, Dict[str, object]], Dict[str, Dict[str, object]]]:
    own_templates = data.get("configs_by_template", {})
    merged_templates: Dict[str, Dict[str, object]] = {}
    template_meta: Dict[str, Dict[str, object]] = {}
    if isinstance(own_templates, dict):
        for key, cfg in own_templates.items():
            name = _sanitize_template_name(key, "default")
            if not isinstance(cfg, dict):
                continue
            merged_templates[name] = _migrate_mutual_traffic_config(dict(cfg))
            template_meta[name] = {
                "source_username": username,
                "template_name": name,
                "editable": True,
                "shared": False,
            }

    grants = _load_template_grants_for_user(username)
    for grant in grants:
        source_username = grant["source_username"]
        template_name = grant["template_name"]
        owner_state = _normalize_user_runtime_state(_load_user_runtime_state(source_username))
        owner_templates = owner_state.get("configs_by_template", {})
        owner_cfg = owner_templates.get(template_name) if isinstance(owner_templates, dict) else None
        if not isinstance(owner_cfg, dict):
            continue
        key = _shared_template_key(source_username, template_name)
        merged_templates[key] = _migrate_mutual_traffic_config(dict(owner_cfg))
        template_meta[key] = {
            "source_username": source_username,
            "template_name": template_name,
            "editable": False,
            "shared": True,
        }
    return merged_templates, template_meta


def _normalize_user_runtime_state(raw_state: dict | None) -> dict:
    data = dict(raw_state or {})
    result = _default_user_config_state()
    run_mode = data.get("run_mode")
    if run_mode in RUN_MODES:
        result["run_mode"] = run_mode
    auto_start_enabled = data.get("auto_start_enabled")
    if isinstance(auto_start_enabled, bool):
        result["auto_start_enabled"] = auto_start_enabled

    alias_template_by_mode: Dict[str, Dict[str, str]] = {
        "growth": {},
        "live": {},
        "direct": {},
        "mutual": {},
    }
    incoming_alias_template_by_mode = data.get("alias_template_by_mode")
    if isinstance(incoming_alias_template_by_mode, dict):
        for mode_key in RUN_MODES:
            mode_map = incoming_alias_template_by_mode.get(mode_key)
            if not isinstance(mode_map, dict):
                continue
            for raw_alias, raw_template in mode_map.items():
                alias = _normalize_alias_key(raw_alias)
                template_ref = str(raw_template or "").strip()
                if not alias or not template_ref:
                    continue
                alias_template_by_mode[mode_key][alias] = template_ref

    templates: Dict[str, Dict[str, object]] = {}
    incoming_templates = data.get("configs_by_template")
    # 旧库或前端曾写入 null：勿用 null 覆盖 BASE 里的列表默认（否则下发缺键、管理台误回退为 HTML 默认）。
    _list_keys_no_null = frozenset(
        {"comment_texts", "mutual_comment_texts", "mutual_traffic_target_texts", "profile_edit_bio_texts"}
    )
    if isinstance(incoming_templates, dict):
        for key, value in incoming_templates.items():
            if not isinstance(value, dict):
                continue
            name = _sanitize_template_name(key, "default")
            merged = dict(MODE_DEFAULT_CONFIGS["growth"])
            filtered = {
                k: v for k, v in value.items() if not (k in _list_keys_no_null and v is None)
            }
            merged.update(filtered)
            templates[name] = _migrate_mutual_traffic_config(merged)

    # 兼容旧结构：仅存在 configs_by_mode 时，迁移到 default 模板。
    if not templates:
        legacy = data.get("configs_by_mode")
        if isinstance(legacy, dict):
            mode = result["run_mode"]
            seed = legacy.get(mode) if isinstance(legacy.get(mode), dict) else None
            if seed is None and isinstance(legacy.get("growth"), dict):
                seed = legacy.get("growth")
            merged = dict(MODE_DEFAULT_CONFIGS["growth"])
            if isinstance(seed, dict):
                merged.update(
                    {
                        k: v
                        for k, v in seed.items()
                        if not (k in _list_keys_no_null and v is None)
                    },
                )
            templates["default"] = _migrate_mutual_traffic_config(merged)
    if not templates:
        templates["default"] = _migrate_mutual_traffic_config(dict(MODE_DEFAULT_CONFIGS["growth"]))

    active_template = _sanitize_template_name(data.get("active_template"), "default")
    if active_template not in templates:
        active_template = "default" if "default" in templates else sorted(templates.keys())[0]

    active_config = dict(templates.get(active_template, MODE_DEFAULT_CONFIGS["growth"]))
    result["active_template"] = active_template
    result["configs_by_template"] = templates
    # 老字段继续回填，兼容现有旧逻辑/旧客户端。
    result["configs_by_mode"] = {mode: dict(active_config) for mode in RUN_MODES}
    result["default_config"] = dict(active_config)
    result["alias_template_by_mode"] = alias_template_by_mode
    result["smart_template_config"] = _normalize_smart_template_config(data.get("smart_template_config"))
    result["alias_smart_template_by_mode"] = _normalize_alias_smart_template_by_mode(
        data.get("alias_smart_template_by_mode"),
    )
    return result


def _resolve_template_for_mode_alias(
    owner_username: str,
    data: dict,
    mode: str,
    alias: str,
    tiktok_username: str | None = None,
    *,
    emit_log: bool = True,
) -> tuple[str, Dict[str, object], Dict[str, Dict[str, object]], Dict[str, Dict[str, object]]]:
    templates, template_meta = _build_effective_template_bundle(owner_username, data)
    if not templates:
        fallback_name = "default"
        return (
            fallback_name,
            dict(MODE_DEFAULT_CONFIGS["growth"]),
            {fallback_name: dict(MODE_DEFAULT_CONFIGS["growth"])},
            {},
        )
    mode_key = mode if mode in RUN_MODES else str(data.get("run_mode") or "growth")
    alias_clean = _normalize_alias_key(alias)
    account_clean = _normalize_tiktok_username_key(tiktok_username or "")
    if not account_clean and alias_clean:
        account_clean = _resolve_switch_highlight_tiktok_username(owner_username, alias_clean)
    account_key = _alias_account_template_key(alias_clean, account_clean) if account_clean else ""

    if alias_clean and _alias_smart_template_enabled(data, mode_key, alias_clean):
        selected_name, smart_debug = _resolve_smart_template_for_alias(
            owner_username,
            data,
            mode_key,
            alias_clean,
            templates,
            tiktok_username=account_clean or None,
        )
        config = dict(templates.get(selected_name, MODE_DEFAULT_CONFIGS["growth"]))
        if emit_log:
            print(
                f"[{now_text()}] 智能模板 | owner={owner_username} mode={mode_key} alias={alias_clean} "
                f"account={account_clean or '-'} branch={smart_debug.get('branch')} "
                f"followers={smart_debug.get('follower_count')} videos={smart_debug.get('video_count')} "
                f"selected={selected_name}"
            )
        return selected_name, config, templates, template_meta

    alias_map_all = data.get("alias_template_by_mode", {})
    raw_ref = ""
    template_source_hint = ""

    def _lookup_mode_map(mode_probe: str) -> str:
        mode_map = alias_map_all.get(mode_probe) if isinstance(alias_map_all, dict) else None
        if not isinstance(mode_map, dict):
            return ""
        if account_key:
            cand = str(mode_map.get(account_key) or "").strip()
            if cand in templates:
                return cand
        return str(mode_map.get(alias_clean) or "").strip()

    # 互点模式：若别名为 mutual 队列单独绑定了模板，优先使用（含 mutual_comment_texts）；
    # 否则仍按涨粉 alias 绑定解析（与旧客户端兼容）。
    if mode_key == "mutual":
        cand = _lookup_mode_map("mutual")
        if cand in templates:
            raw_ref = cand
            template_source_hint = "mutual"
    template_mode_key = "growth" if mode_key == "mutual" else mode_key
    if not raw_ref:
        cand = _lookup_mode_map(template_mode_key)
        if cand in templates:
            raw_ref = cand
            template_source_hint = template_mode_key
    cross_mode_hit = ""
    # 兜底：若当前模式未配置该 alias/账号，尝试复用其它模式同键的模板，避免误回退到 active_template。
    if not raw_ref and isinstance(alias_map_all, dict) and alias_clean:
        probe_modes = [template_mode_key, "mutual", "growth", "live", "direct"]
        for probe_mode in probe_modes:
            if probe_mode == template_mode_key:
                continue
            probe_ref = _lookup_mode_map(probe_mode)
            if probe_ref in templates:
                raw_ref = probe_ref
                cross_mode_hit = probe_mode
                break

    stored_active = str(data.get("active_template") or "").strip()
    fallback_name = stored_active if stored_active in templates else ""
    if not fallback_name:
        fallback_name = sorted(templates.keys())[0]

    selected_name = raw_ref if raw_ref in templates else fallback_name
    config = dict(templates.get(selected_name, MODE_DEFAULT_CONFIGS["growth"]))
    if emit_log:
        print(
            f"[{now_text()}] 模板解析 | owner={owner_username} mode={mode_key} alias={alias_clean} account={account_clean or '-'} account_key={account_key or '-'} source={template_source_hint or '-'} raw_ref={raw_ref or '-'} cross_mode_hit={cross_mode_hit or '-'} selected={selected_name} fallback={fallback_name} templates={','.join(sorted(templates.keys()))}"
        )
    return selected_name, config, templates, template_meta


def _user_auto_start_enabled(username: str) -> bool:
    data = _normalize_user_runtime_state(_load_user_runtime_state(username))
    raw = data.get("auto_start_enabled")
    return bool(raw) if isinstance(raw, bool) else False


def _user_run_mode(username: str) -> str:
    """当前登录用户在库中的运行模式（勿用全局 store.run_mode，否则多账号会互相覆盖）。"""
    data = _normalize_user_runtime_state(_load_user_runtime_state(username))
    mode = data.get("run_mode")
    return mode if mode in RUN_MODES else "growth"


class DeviceAutoStartOptOutRequest(BaseModel):
    device_id: str = Field(..., description="设备ID")
    opt_out: bool = Field(..., description="true=该设备禁用自动开启；false=允许自动开启")


def persist_runtime_state():
    with _tracked_store_lock("persist_runtime_state"):
        payload = {
            "configs_by_mode": {mode: dict(cfg) for mode, cfg in store.configs_by_mode.items()},
            "run_mode": store.run_mode,
            "default_config": dict(store.default_config),
            "auto_start_enabled": store.auto_start_enabled,
            "auto_switch_enabled": bool(store.auto_switch_enabled),
            "auto_switch_bundle_revision": int(store.auto_switch_bundle_revision or 0),
            "auto_switch_last_slot_keys": dict(store.auto_switch_last_slot_keys),
            "auto_switch_publish_done_keys": dict(store.auto_switch_publish_done_keys),
            "auto_switch_publish_aborted_keys": dict(store.auto_switch_publish_aborted_keys),
            "auto_switch_publish_inflight_keys": dict(store.auto_switch_publish_inflight_keys),
            "backup_stats_full_query_enabled": bool(store.backup_stats_full_query_enabled),
            "backup_stats_full_query_last_run_date": str(store.backup_stats_full_query_last_run_date or ""),
        }
    conn = _get_db()
    try:
        conn.execute(
            "INSERT OR REPLACE INTO persisted_runtime_state (id, json) VALUES (1, ?)",
            (json.dumps(payload, ensure_ascii=False),),
        )
        conn.commit()
    except Exception as exc:
        print(f"[{now_text()}] 系统 | 持久化配置失败：{exc}")
    finally:
        conn.close()


_load_client_devices_from_db()
_seed_device_persist_throttle_from_memory()
_load_persisted_runtime_state()


def _backup_stats_full_query_schedule_available() -> bool:
    return bool(BACKUP_STATS_FULL_QUERY_SCHEDULE_AVAILABLE)


def _backup_stats_full_query_manual_available() -> bool:
    return bool(BACKUP_STATS_FULL_QUERY_MANUAL_AVAILABLE)


def _ensure_backup_stats_full_query_schedule_off() -> None:
    """定时全量查询暂不可用时：强制关闭并停止 in_flight 标记（不中断已跑的手动任务线程）。"""
    if _backup_stats_full_query_schedule_available():
        return
    dirty = False
    with store.lock:
        if store.backup_stats_full_query_enabled:
            store.backup_stats_full_query_enabled = False
            dirty = True
    if dirty:
        persist_runtime_state()
        _backup_stats_full_query_log(f"[{now_text()}] 每日定时全量查询已强制关闭（功能暂时不可用）")


def bump_usage_for_usernames(conn: sqlite3.Connection, usernames: List[str]):
    if not usernames:
        return
    uniq = list(dict.fromkeys(usernames))
    # 逐条 UPDATE 会在一次 pull 内长时间占用 SQLite 写锁，拖住「根用户入队」等并发写。
    step = 200
    for i in range(0, len(uniq), step):
        part = uniq[i : i + step]
        ph = ",".join(["?"] * len(part))
        conn.execute(
            f"UPDATE tiktok_roots SET usage_count = usage_count + 1 WHERE root_username IN ({ph})",
            part,
        )
        conn.execute(
            f"UPDATE live_customer SET usage_count = usage_count + 1 WHERE username IN ({ph})",
            part,
        )


def _report_status_from_text(text: str) -> str:
    lower = text.lower()
    if ("失败" in text) or ("异常" in text) or ("failed" in lower) or ("error" in lower):
        return "failed"
    return "success"


def _extract_report_username(text: str) -> str | None:
    m = re.search(r"用户名\s*([A-Za-z0-9._\-]+)", text)
    if not m:
        return None
    username = m.group(1).strip().lstrip("@")
    return username or None


def mark_mobile_username_status_by_report(message: str):
    text = (message or "").strip()
    if not text:
        return

    username = _extract_report_username(text)
    if not username:
        return

    status = _report_status_from_text(text)
    conn = _get_db()
    try:
        updated_at = now_text()
        conn.execute(
            """
            UPDATE mobile_pending_usernames
            SET consume_status = ?, consumed_at = ?
            WHERE id = (
                SELECT id FROM mobile_pending_usernames
                WHERE username = ? AND consume_status = 'sent'
                ORDER BY id DESC
                LIMIT 1
            )
            """,
            (status, updated_at, username),
        )
        conn.commit()
    finally:
        conn.close()


def mark_follower_queue_direct_status_by_report(message: str):
    text = (message or "").strip()
    if not text:
        return

    username = _extract_report_username(text)
    if not username:
        return

    status = _report_status_from_text(text)
    conn = _get_db()
    try:
        updated_at = now_text()
        conn.execute(
            """
            UPDATE follower_queue
            SET consume_status = ?, consumed_at = ?
            WHERE id = (
                SELECT id FROM follower_queue
                WHERE username = ? AND consume_status = 'sent'
                ORDER BY id DESC
                LIMIT 1
            )
            """,
            (status, updated_at, username),
        )
        conn.commit()
    finally:
        conn.close()


def mark_follower_queue_process_status_by_report(message: str):
    text = (message or "").strip()
    if not text:
        return

    username = _extract_report_username(text)
    if not username:
        return

    status = _report_status_from_text(text)
    conn = _get_db()
    try:
        updated_at = now_text()
        conn.execute(
            """
            UPDATE follower_queue
            SET following_access_status = ?, consumed_at = COALESCE(consumed_at, ?)
            WHERE username = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            (status, updated_at, username),
        )
        conn.commit()
    finally:
        conn.close()

app = FastAPI(
    title="TikTok Auto Traces Memory Server",
    description="设备绑定、配置下发、任务队列与用户名管理服务。",
    version="1.1.0",
)

@app.exception_handler(sqlite3.OperationalError)
async def sqlite_operational_error_handler(_request: Request, exc: sqlite3.OperationalError):
    if _is_db_lock_error(exc):
        return JSONResponse(
            status_code=503,
            content={"code": 1, "msg": "数据库繁忙，请稍后重试"},
        )
    return JSONResponse(status_code=500, content={"code": 1, "msg": str(exc)})


app.add_middleware(
    CORSMiddleware,
    # 管理台可能通过 file://、局域网 IP、不同端口静态服务访问；
    # 这里放开跨域以避免浏览器只发 OPTIONS 不发实际请求。
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

def _should_suppress_windows_accept_noise(exc: BaseException | None, context: dict[str, Any] | None = None) -> bool:
    if not sys.platform.startswith("win"):
        return False
    message = ""
    task_repr = ""
    if context and isinstance(context, dict):
        message = str(context.get("message") or "")
        task_repr = str(context.get("task") or "")
    raw = f"{message}\n{task_repr}\n{exc or ''}".lower()
    winerror = getattr(exc, "winerror", None)
    if not isinstance(exc, OSError):
        return False
    if winerror == 64 and (
        "accept_coro" in raw
        or "finish_accept" in raw
        or "specified network name is no longer available" in raw
        or "指定的网络名不可再用" in raw
    ):
        return True
    # Windows 关闭连接时 Proactor 偶发 WinError 10022，不影响业务。
    if winerror == 10022 and ("connection_lost" in raw or "_call_connection_lost" in raw):
        return True
    return False


def _interruptible_worker_sleep(seconds: float) -> bool:
    """等待指定秒数；若收到关闭信号则提前返回 True。"""
    return _APP_SHUTDOWN_EVENT.wait(max(0.05, float(seconds)))


def _begin_app_shutdown_early(*, log: bool = True) -> None:
    """收到关闭信号时立即执行，不必等 uvicorn 排空所有 HTTP 连接。"""
    global _app_shutting_down
    if _app_shutting_down:
        return
    _app_shutting_down = True
    _APP_SHUTDOWN_EVENT.set()
    with contextlib.suppress(Exception):
        store.scheduler_stop_event.set()
    with contextlib.suppress(Exception):
        _stop_all_task_processes()
    with contextlib.suppress(Exception):
        _flush_device_persist_queue_blocking()
    if log:
        print(f"[{now_text()}] app shutdown | background workers signaled (early)")


def _install_early_shutdown_signal_handlers() -> None:
    """在 uvicorn 优雅关闭等待连接期间，先停后台线程并拒绝新请求。"""

    def _wrap(signum, frame):
        _begin_app_shutdown_early()
        prev = _EARLY_SHUTDOWN_PREV_HANDLERS.get(signum)
        if prev is not None and callable(prev) and prev is not _wrap:
            prev(signum, frame)
            return
        if signum == signal.SIGINT:
            raise KeyboardInterrupt

    for sig in (signal.SIGINT, signal.SIGTERM):
        with contextlib.suppress(Exception):
            _EARLY_SHUTDOWN_PREV_HANDLERS[sig] = signal.getsignal(sig)
            signal.signal(sig, _wrap)


@app.on_event("shutdown")
async def _on_app_shutdown() -> None:
    _begin_app_shutdown_early(log=False)
    with contextlib.suppress(Exception):
        from mail_integration import stop_mail_module_process

        await asyncio.to_thread(stop_mail_module_process)
    print(f"[{now_text()}] app shutdown | complete")


@app.on_event("startup")
async def install_asyncio_exception_filter() -> None:
    print(f"[{now_text()}] 系统 | HTTP 服务就绪（main 模块已加载，开始接受请求）")
    loop = asyncio.get_running_loop()
    previous_handler = loop.get_exception_handler()

    def _handler(current_loop: asyncio.AbstractEventLoop, context: dict[str, Any]) -> None:
        exc = context.get("exception")
        if isinstance(exc, BaseException) and _should_suppress_windows_accept_noise(exc, context):
            # Windows 下客户端断开触发的 accept 噪声，不影响业务请求，直接忽略。
            return
        if previous_handler:
            previous_handler(current_loop, context)
            return
        current_loop.default_exception_handler(context)

    loop.set_exception_handler(_handler)
    _install_early_shutdown_signal_handlers()
    _reconcile_auto_switch_mode_event_mailbox()


if __name__ == "__main__":
    import os
    import uvicorn

    host = os.getenv("HOST", "0.0.0.0")
    port = int(os.getenv("PORT", "8000"))

    uvicorn.run(
        "main:app",
        host=host,
        port=port,
        reload=False,
        log_level="info",
        timeout_graceful_shutdown=5,
        timeout_keep_alive=5,
    )

@app.middleware("http")
async def log_request_timing(request: Request, call_next):
    if _app_shutting_down:
        return JSONResponse(status_code=503, content={"code": 1, "msg": "服务正在关闭"})
    started = time.perf_counter()
    path = request.url.path
    quiet_poll = path in ("/api/device/mode_wait", "/api/device/mode_ack", "/api/check_start", "/api/device_ops/poll")
    if not quiet_poll:
        print(f"[{now_text()}] HTTP {request.method} {path} | enter")
    try:
        # 管理台核心接口：避免因数据库锁/偶发阻塞导致前端长期 pending。
        if path in ("/api/admin/overview", "/api/auth/me", "/api/health"):
            print(f"[{now_text()}] HTTP {request.method} {path} | call_next_start | timeout=20s")
            response = await asyncio.wait_for(call_next(request), timeout=20.0)
            print(f"[{now_text()}] HTTP {request.method} {path} | call_next_done")
        else:
            if not quiet_poll:
                print(f"[{now_text()}] HTTP {request.method} {path} | call_next_start")
            response = await call_next(request)
            if not quiet_poll:
                print(f"[{now_text()}] HTTP {request.method} {path} | call_next_done")
    except asyncio.TimeoutError:
        elapsed_ms = (time.perf_counter() - started) * 1000
        print(f"[{now_text()}] HTTP {request.method} {request.url.path} | 504 | {elapsed_ms:.1f}ms | TIMEOUT")
        return JSONResponse(
            status_code=504,
            content={"code": 1, "msg": "服务繁忙：请求超时，请稍后重试"},
        )
    except sqlite3.OperationalError as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000
        if _is_db_lock_error(exc):
            print(f"[{now_text()}] HTTP {request.method} {request.url.path} | 503 | {elapsed_ms:.1f}ms | DB_BUSY")
            return JSONResponse(
                status_code=503,
                content={"code": 1, "msg": "数据库繁忙，请稍后重试"},
            )
        print(f"[{now_text()}] HTTP {request.method} {request.url.path} | 500 | {elapsed_ms:.1f}ms | EXC={exc}")
        raise
    except RuntimeError as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000
        if str(exc).startswith("DB_BUSY:"):
            print(f"[{now_text()}] HTTP {request.method} {request.url.path} | 503 | {elapsed_ms:.1f}ms | DB_BUSY")
            return JSONResponse(
                status_code=503,
                content={"code": 1, "msg": "数据库繁忙，请稍后重试"},
            )
        print(f"[{now_text()}] HTTP {request.method} {request.url.path} | 500 | {elapsed_ms:.1f}ms | EXC={exc}")
        raise
    except Exception as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000
        print(f"[{now_text()}] HTTP {request.method} {request.url.path} | 500 | {elapsed_ms:.1f}ms | EXC={exc}")
        raise
    elapsed_ms = (time.perf_counter() - started) * 1000
    if elapsed_ms >= 800 or response.status_code >= 500:
        print(f"[{now_text()}] HTTP {request.method} {request.url.path} | {response.status_code} | {elapsed_ms:.1f}ms")

    # Chrome Private Network Access (PNA) 预检：如果浏览器带了该请求头，
    # 需要显式回传 allow-private-network 才会继续发送实际请求。
    if request.headers.get("access-control-request-private-network"):
        response.headers["access-control-allow-private-network"] = "true"
    return response


class BindDeviceRequest(BaseModel):
    alias: str = Field(..., min_length=1, max_length=20)
    device_fingerprint: str = Field(..., min_length=1, max_length=64)

    @field_validator("alias")
    @classmethod
    def validate_alias(cls, value: str) -> str:
        allowed = string.ascii_letters + string.digits + "_"
        if any(ch not in allowed for ch in value):
            raise ValueError("alias 仅支持字母、数字、下划线")
        return value


class DeviceAuthModel(BaseModel):
    device_id: str
    alias: str


class ReportDeviceAutoStartOptOutRequest(DeviceAuthModel):
    opt_out: bool


@app.post("/api/report_device_auto_start_opt_out")
def report_device_auto_start_opt_out(payload: ReportDeviceAutoStartOptOutRequest):
    """设备端上报：更新该设备 auto_start_opt_out（硬停、发作品、修改资料等；互点流程不再使用）。"""
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    did = (payload.device_id or "").strip()
    if not did:
        return {"code": 1, "msg": "device_id不能为空"}
    with store.lock:
        dev = store.devices.get(did)
        if not dev:
            return {"code": 1, "msg": "设备不存在"}
        dev.auto_start_opt_out = bool(payload.opt_out)
    _persist_client_device_to_db(did, force=True)
    return {"code": 0, "msg": "已更新", "device_id": did, "auto_start_opt_out": bool(payload.opt_out)}


class SyncStatusRequest(DeviceAuthModel):
    status: Literal["online", "running", "paused", "error"]


class StartTaskRequest(BaseModel):
    device_ids: List[str]


class RunModeRequest(BaseModel):
    mode: Literal["growth", "live", "direct", "mutual"]


class ConfigTemplateSelectRequest(BaseModel):
    template_name: str = Field(..., min_length=1, max_length=64)


class ConfigTemplateRenameRequest(BaseModel):
    old_template_name: str = Field(..., min_length=1, max_length=64)
    new_template_name: str = Field(..., min_length=1, max_length=64)


class ConfigTemplateDeleteRequest(BaseModel):
    template_name: str = Field(..., min_length=1, max_length=64)


class ConfigTemplateCopyRequest(BaseModel):
    source_template_name: str = Field(..., min_length=1, max_length=128)
    new_template_name: str = Field(..., min_length=1, max_length=64)


class AliasTemplateSelectRequest(BaseModel):
    alias: str = Field(..., min_length=1, max_length=64)
    template_name: str = Field(..., min_length=1, max_length=128)
    mode: Literal["growth", "live", "direct", "mutual"] | None = None
    owner_username: str | None = Field(default=None, max_length=64)
    tiktok_username: str | None = Field(
        default=None,
        max_length=128,
        description="非空时绑定到「别名+该 TikTok 账号」；为空则仍绑定整个别名设备",
    )


class SmartTemplateBranchEntry(BaseModel):
    template_name: str = Field(..., min_length=1, max_length=128)
    weight: int = Field(default=100, ge=0, le=10000)


class SmartTemplateConfigRequest(BaseModel):
    old_branch_min_followers: int = Field(default=100, ge=0, le=10_000_000)
    old_branch_min_video_count: int = Field(default=15, ge=0, le=10_000_000)
    new_branch_templates: List[SmartTemplateBranchEntry] = Field(default_factory=list)
    old_branch_templates: List[SmartTemplateBranchEntry] = Field(default_factory=list)


class AliasSmartTemplateToggleRequest(BaseModel):
    alias: str = Field(..., min_length=1, max_length=64)
    enabled: bool
    mode: Literal["growth", "live", "direct", "mutual"] | None = None
    owner_username: str | None = Field(default=None, max_length=64)


class SmartTemplatePreviewItem(BaseModel):
    alias: str = Field(..., min_length=1, max_length=64)
    mode: Literal["growth", "live", "direct", "mutual"] | None = None
    owner_username: str | None = Field(default=None, max_length=64)


class SmartTemplatePreviewRequest(BaseModel):
    items: List[SmartTemplatePreviewItem] = Field(default_factory=list)


class TemplateGrantUpsertRequest(BaseModel):
    target_username: str = Field(..., min_length=1, max_length=64)
    source_username: str = Field(default="", max_length=64)
    template_name: str = Field(..., min_length=1, max_length=64)
    enabled: bool = True


class AutoStartRequest(BaseModel):
    enabled: bool


class AutoSwitchConfigRequest(BaseModel):
    enabled: bool | None = None


class AutoSwitchDevicesRequest(BaseModel):
    device_ids: List[str] = Field(default_factory=list)
    enabled: bool


class BackupStatsFullQueryConfigRequest(BaseModel):
    enabled: bool | None = None


class SchedulerConfigRequest(BaseModel):
    enabled: bool


class SchedulerTaskConfigRequest(BaseModel):
    task_key: str = Field(..., min_length=1, max_length=64)
    enabled: bool | None = None
    interval_sec: int | None = Field(default=None, ge=60, le=86400)


@app.post("/api/admin/device_auto_start_opt_out")
def set_device_auto_start_opt_out(payload: DeviceAutoStartOptOutRequest, authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    did = (payload.device_id or "").strip()
    if not did:
        return {"code": 1, "msg": "device_id不能为空"}
    allowed = _session_device_ids(session)
    if did not in allowed:
        return {"code": 403, "msg": "无权操作该设备"}
    with store.lock:
        dev = store.devices.get(did)
        if not dev:
            return {"code": 1, "msg": "设备不存在"}
        dev.auto_start_opt_out = bool(payload.opt_out)
        alias = dev.alias
    _persist_client_device_to_db(did, force=True)
    return {"code": 0, "msg": "已更新", "device_id": did, "alias": alias, "auto_start_opt_out": bool(payload.opt_out)}


class StopTaskByPrefixRequest(BaseModel):
    prefix: str = Field(..., min_length=1, max_length=120)


class AdminCreateUserRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    password: str = Field(..., min_length=6, max_length=128)
    role: Literal["customer", "admin"] = "customer"
    is_active: bool = True


class ReportResultRequest(DeviceAuthModel):
    result: str = Field(..., min_length=1)
    run_mode: Literal["growth", "live", "direct", "mutual"] | None = None


class ReportMutualCopiedUsernameRequest(DeviceAuthModel):
    username: str = Field(..., min_length=1, max_length=64)


def _normalize_mutual_clip_username(raw: str) -> str:
    """互点粘贴板用户名：单行首 token，去 @；仅字母数字下标点；主键为小写折叠。"""
    if not raw or not isinstance(raw, str):
        return ""
    s = raw.strip().splitlines()[0].strip().split()[0].strip().lstrip("@").strip()
    allowed = frozenset("abcdefghijklmnopqrstuvwxyz0123456789._")
    lowered = []
    for ch in s.lower():
        if ch in allowed:
            lowered.append(ch)
    s2 = "".join(lowered)
    if len(s2) < 2:
        return ""
    return s2[:64]


class AddUsernamesRequest(BaseModel):
    usernames: List[str]
    # 为「tiktok_roots 勾选加入待下发」等场景：true 时不调用隐私判定，直接写入 mobile_pending_usernames
    skip_privacy_filter: bool = False


class AddTikTokRootsRequest(BaseModel):
    usernames: List[str]
    roots_type: str = ""


class TikTokProfileStatsRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    include_raw: bool = False
    write_back: bool = False
    query_index: int | None = None
    query_total: int | None = None
    query_index: int | None = Field(default=None, ge=1)
    query_total: int | None = Field(default=None, ge=1)


class LiveCustomerImportResponse(BaseModel):
    inserted: int
    total_rows: int


class AddLatestRootsToPendingRequest(BaseModel):
    count: int = Field(default=100, ge=1, le=5000)


class MobilePendingExportStartRequest(BaseModel):
    clear_after_export: bool = False


class DeviceTikTokBackupClearRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    package_name: str = Field(..., min_length=1, max_length=255)
    backup_record_id: int | None = Field(default=None, ge=1)
    # 可选：与档案一致时写入 backup_path；设备回传时会带回，便于服务端精确更新
    username: str | None = Field(default=None, max_length=64)
    # 切号：设备「备份并清理」成功回传后由服务端更新 switch_highlight，不依赖管理台轮询
    cut_switch_after_backup: str = Field(default="", max_length=64)
    cut_switch_target_row_index: int | None = Field(default=None, ge=0)


class BatchStreamStartRequest(BaseModel):
    session_id: str = Field(..., min_length=8, max_length=80)
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    hours_per_account: float = Field(..., gt=0, le=72)
    activity_rounds: int = Field(default=1, ge=1, le=20)
    package_name: str = Field(default=TIKTOK_PACKAGE_NAME, min_length=1, max_length=255)


class BatchStreamStopRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)


class BatchStreamReportRowRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    session_id: str = Field(..., min_length=8, max_length=80)
    sheet_row: int = Field(..., ge=2, le=100000)
    status_text: str = Field(..., min_length=1, max_length=255)
    hours_active: float = Field(default=0, ge=0, le=1440)


class TikTokBackupSubmitRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    tiktok_password: str = Field(default="", max_length=255)
    mobile_alias: str = Field(..., min_length=1, max_length=64)
    api_address: str = Field(default="", max_length=255)
    email: str = Field(default="", max_length=255)
    email_password: str = Field(default="", max_length=255)
    login_date: str | None = None
    video_count: int | None = Field(default=None, ge=0)
    follower_count: int | None = Field(default=None, ge=0)
    backup_path: str = Field(default="", max_length=500)
    backup_name: str | None = Field(default=None, max_length=255)
    backup_time: str = Field(default="", max_length=64)
    backup_status: str = Field(default="", max_length=64)


class DeviceTikTokRestoreRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    backup_name: str = Field(..., min_length=1, max_length=255)
    skip_pm_clear: bool = Field(
        default=False,
        description="为 true 时设备端还原前跳过 pm clear（切号：前序已执行备份并清理）",
    )
    cut_switch_target_row_index: int | None = Field(
        default=None,
        ge=0,
        description="切号入队时立即切换环境归属高亮到该行（失败不回滚）",
    )


class TikTokBackupRecordSubmitRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    mobile_alias: str = Field(..., min_length=1, max_length=64)
    login_date: str | None = None
    video_count: int | None = Field(default=None, ge=0)
    follower_count: int | None = Field(default=None, ge=0)
    backup_path: str = Field(..., min_length=1, max_length=500)
    backup_name: str | None = Field(default=None, max_length=255)
    backup_time: str = Field(default="", max_length=64)
    backup_status: str = Field(default="", max_length=64)


class AccountEditorRowUpsertRequest(BaseModel):
    row_index: int = Field(..., ge=0)
    username: str = ""
    password: str = ""
    email: str = ""
    email_password: str = ""
    api: str = ""
    is_login: bool = False
    login_time: str = ""
    region: str = ""
    assigned_alias: str = ""
    video_count: str = ""
    follower_count: str = ""
    follower_growth: str = ""
    basic_profile_modified: str = "否"

    @field_validator("basic_profile_modified", mode="before")
    @classmethod
    def _normalize_basic_profile_modified(cls, v: object) -> str:
        return _normalize_switch_highlight(v)


class AccountEditorRowBatchUpsertRequest(BaseModel):
    items: list[AccountEditorRowUpsertRequest] = Field(..., min_length=1)


ACCOUNT_EDITOR_ROW_BATCH_UPSERT_MAX = 300


class DeviceEditorRowUpsertRequest(BaseModel):
    row_index: int = Field(..., ge=0)
    device_alias: str = ""
    device_id: str = ""
    ip: str = ""
    region: str = ""
    username: str = ""
    assigned_alias: str = ""
    backup_path: str = ""
    backup_name: str = ""
    backup_time: str = ""
    backup_status: str = ""
    remark: str = ""
    switch_highlight: str = "否"

    @field_validator("switch_highlight", mode="before")
    @classmethod
    def _normalize_switch_highlight_field(cls, v: object) -> str:
        return _normalize_switch_highlight(v)


class AccountEditorRowDeleteRequest(BaseModel):
    row_index: int = Field(..., ge=0)
    username: str = Field(default="", max_length=256)
    assigned_alias: str = Field(default="", max_length=64)


class AccountEditorRowBatchDeleteItem(BaseModel):
    row_index: int = Field(..., ge=0)
    username: str = Field(default="", max_length=256)


class AccountEditorRowBatchDeleteRequest(BaseModel):
    items: list[AccountEditorRowBatchDeleteItem] = Field(..., min_length=1)


ACCOUNT_EDITOR_ROW_BATCH_DELETE_MAX = 100


class AccountEditorRowBatchSetBasicProfileItem(BaseModel):
    row_index: int = Field(..., ge=0)
    username: str = Field(default="", max_length=256)


class AccountEditorRowBatchSetBasicProfileRequest(BaseModel):
    basic_profile_modified: str = "否"
    items: list[AccountEditorRowBatchSetBasicProfileItem] = Field(..., min_length=1)

    @field_validator("basic_profile_modified", mode="before")
    @classmethod
    def _normalize_basic_profile_modified(cls, v: object) -> str:
        return _normalize_switch_highlight(v)


ACCOUNT_EDITOR_ROW_BATCH_SET_BASIC_PROFILE_MAX = 100


class DeviceEditorRowDeleteRequest(BaseModel):
    row_index: int = Field(..., ge=0)


class DeviceEditorSwitchHighlightRequest(BaseModel):
    row_index: int = Field(..., ge=0)


class DeviceEditorAutoSwitchExcludedRequest(BaseModel):
    row_index: int = Field(..., ge=0)
    excluded: bool = False


class SetDeviceGroupRequest(BaseModel):
    device_group: str = Field(default="", max_length=64)
    aliases: list[str] = Field(default_factory=list)
    device_ids: list[str] = Field(default_factory=list)


class DeleteDeviceGroupRequest(BaseModel):
    group_name: str = Field(..., min_length=1, max_length=64)


class AccountAliasUnassignRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=255)
    assigned_alias: str = Field(..., min_length=1, max_length=64)
    device_row_index: int | None = Field(default=None, ge=0)


class DeviceIdAliasRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(default="", max_length=64)


class DeviceOpPollRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)


class DeviceModeAckRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    event_id: int = Field(..., ge=1)
    phase: str = Field(..., min_length=1, max_length=64)
    session_id: str = Field(default="", max_length=128)


class AutoSwitchLedgerEntryPayload(BaseModel):
    at: str = Field(default="", max_length=64)
    op_id: str = Field(default="", max_length=128)
    op_type: str = Field(default="", max_length=64)
    status: str = Field(default="", max_length=32)
    phase: str = Field(default="", max_length=32)
    message: str = Field(default="", max_length=2000)
    slot_key: str = Field(default="", max_length=64)
    username: str = Field(default="", max_length=128)
    report_data: dict[str, Any] = Field(default_factory=dict)


class AutoSwitchSessionSummaryRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    session_id: str = Field(..., min_length=1, max_length=128)
    bundle_revision: int = Field(default=0, ge=0)
    schedule_date: str = Field(default="", max_length=32)
    started_at: str = Field(default="", max_length=64)
    ended_at: str = Field(default="", max_length=64)
    ledger: List[AutoSwitchLedgerEntryPayload] = Field(default_factory=list)
    human_log: str = Field(default="", max_length=50000)
    stats: dict[str, Any] = Field(default_factory=dict)
    """final=关会话总结；daily=自治周期内每日对账（同一 session 可多次）。"""
    summary_kind: str = Field(default="final", max_length=16)
    """幂等键：final 用 final；daily 用北京时间日期 YYYY-MM-DD。"""
    reconcile_key: str = Field(default="", max_length=64)
    smart_template_debug: dict[str, Any] = Field(default_factory=dict)


class MutualClickRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    package_name: str = Field(..., min_length=1, max_length=255)


class DevicePcServiceUrlSetItem(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(default="", max_length=64)
    windows_base_url: str = Field(..., min_length=8, max_length=512)


class DevicePcServiceUrlBatchSetRequest(BaseModel):
    items: List[DevicePcServiceUrlSetItem] = Field(..., min_length=1, max_length=500)


class DeviceTikTokEmailCodeRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    email: str = Field(default="", max_length=255)
    api_url: str = Field(default="", max_length=2048)


class DeviceTkPendingAcquireRequest(DeviceAuthModel):
    email: str = Field(default="", max_length=255)


class DeviceTkPendingCompleteRequest(DeviceAuthModel):
    email: str = Field(default="", max_length=255)
    claim_id: str = Field(default="", max_length=80)


class DeviceAssignedAccountMarkLoginRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    username: str = Field(..., min_length=1, max_length=128)


class DeviceAssignedAccountUpdateUsernameRequest(DeviceAuthModel):
    new_username: str = Field(..., min_length=1, max_length=128)


class DeviceOpReportRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    op_id: str = Field(..., min_length=1, max_length=80)
    status: Literal["success", "failed"]
    message: str = ""
    data: Dict[str, Any] | None = None


class DeviceLogSyncRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    logs: List[str] = Field(default_factory=list)
    replace: bool = False


class DeviceLogPullRequest(BaseModel):
    device_ids: List[str] = Field(default_factory=list)
    max_lines: int = 400


class UpdateConfigRequest(BaseModel):
    template_name: str | None = None
    break_min: int | None = None
    break_max: int | None = None
    visit_delay_min: float | None = None
    visit_delay_max: float | None = None
    video_stay_min: float | None = None
    video_stay_max: float | None = None
    direct_post_action_delay_ms: int | None = None
    skip_post_click_prob: float | None = None
    growth_post_check_after_tap_delay_ms: int | None = None
    growth_post_second_check_delay_ms: int | None = None
    like_prob: float | None = None
    comment_prob: float | None = None
    favorite_prob: float | None = None
    share_prob: float | None = None
    comment_text_prob: float | None = None
    comment_emoji_prob: float | None = None
    action_delay_min: float | None = None
    action_delay_max: float | None = None
    tiktok_force_stop_wait_ms: int | None = None
    tiktok_launch_wait_ms: int | None = None
    for_you_swipe_total_sec: float | None = None
    for_you_like_prob: float | None = None
    for_you_watch_min_sec: float | None = None
    for_you_watch_max_sec: float | None = None
    like_daily_limit: int | None = None
    comment_daily_limit: int | None = None
    mutual_traffic_comment_daily_limit: int | None = None
    favorite_daily_limit: int | None = None
    share_daily_limit: int | None = None
    action_single: bool | None = None
    following_filter_min_video_count: int | None = None
    following_filter_max_following_count: int | None = None
    pull_batch_size: int | None = None
    pull_batches_before_rest: int | None = None
    no_rest: bool | None = None
    process_queue_batch_size: int | None = None
    comment_texts: List[str] | None = None
    mutual_comment_texts: List[str] | None = None
    mutual_traffic_target_texts: List[str] | None = None
    profile_edit_bio_texts: List[str] | None = None
    mutual_videos_scroll_rounds: int | None = None
    mutual_follow_tap_session_limit: int | None = None
    mutual_traffic_comment: bool | None = None
    mutual_traffic_author_follow: bool | None = None
    mutual_traffic_followers_follow: bool | None = None
    mutual_traffic_follow_only: bool | None = None
    mutual_left_swipe_follow: bool | None = None  # 已废弃，保存时忽略；读模板时迁移为三项开关
    growth_pull_batches_before_auto_mutual: int | None = None

    @field_validator(
        "like_prob",
        "comment_prob",
        "favorite_prob",
        "share_prob",
        "comment_text_prob",
        "comment_emoji_prob",
        "for_you_like_prob",
    )
    @classmethod
    def validate_prob(cls, value: float | None):
        if value is None:
            return value
        if value < 0 or value > 1:
            raise ValueError("概率必须在 0~1 之间")
        return value

    @field_validator(
        "like_daily_limit",
        "comment_daily_limit",
        "mutual_traffic_comment_daily_limit",
        "favorite_daily_limit",
        "share_daily_limit",
    )
    @classmethod
    def validate_daily_limit(cls, value: int | None):
        if value is None:
            return value
        if value < 0 or value > 100_000:
            raise ValueError("每日上限必须在 0~100000 之间")
        return value

    @field_validator("following_filter_min_video_count")
    @classmethod
    def validate_following_filter_min_video_count(cls, value: int | None):
        if value is None:
            return value
        if value < 0 or value > 100_000:
            raise ValueError("作品数阈值必须在 0~100000 之间")
        return value

    @field_validator("following_filter_max_following_count")
    @classmethod
    def validate_following_filter_max_following_count(cls, value: int | None):
        if value is None:
            return value
        if value < 1 or value > 1_000_000:
            raise ValueError("关注数上限必须在 1~1000000 之间")
        return value

    @field_validator("pull_batch_size")
    @classmethod
    def validate_pull_batch_size(cls, value: int | None):
        if value is None:
            return value
        if value < 1 or value > 100:
            raise ValueError("单次拉取数量必须在 1~100 之间")
        return value

    @field_validator("pull_batches_before_rest")
    @classmethod
    def validate_pull_batches_before_rest(cls, value: int | None):
        if value is None:
            return value
        if value < 1 or value > 100:
            raise ValueError("休息前拉取批次数必须在 1~100 之间")
        return value

    @field_validator("process_queue_batch_size")
    @classmethod
    def validate_process_queue_batch_size(cls, value: int | None):
        if value is None:
            return value
        if value < 1 or value > 1000:
            raise ValueError("process_queue 批次大小必须在 1~1000 之间")
        return value

    @field_validator("mutual_videos_scroll_rounds")
    @classmethod
    def validate_mutual_videos_scroll_rounds(cls, value: int | None):
        if value is None:
            return value
        if value < 1 or value > 500:
            raise ValueError("mutual_videos_scroll_rounds 必须在 1~500 之间")
        return value

    @field_validator("mutual_follow_tap_session_limit")
    @classmethod
    def validate_mutual_follow_tap_session_limit(cls, value: int | None):
        if value is None:
            return value
        if value < 1 or value > 100_000:
            raise ValueError("mutual_follow_tap_session_limit 必须在 1~100000 之间")
        return value

    @field_validator("growth_pull_batches_before_auto_mutual")
    @classmethod
    def validate_growth_pull_batches_before_auto_mutual(cls, value: int | None):
        if value is None:
            return value
        if value < -1 or value > 10_000:
            raise ValueError("growth_pull_batches_before_auto_mutual 必须在 -1~10000 之间（已废弃，设备端忽略）")
        return value

    @field_validator("direct_post_action_delay_ms")
    @classmethod
    def validate_direct_post_action_delay_ms(cls, value: int | None):
        if value is None:
            return value
        if value < 300 or value > 8000:
            raise ValueError("direct_post_action_delay_ms 必须在 300~8000 之间")
        return value

    @field_validator("growth_post_check_after_tap_delay_ms")
    @classmethod
    def validate_growth_post_check_after_tap_delay_ms(cls, value: int | None):
        if value is None:
            return value
        if value < 100 or value > 5000:
            raise ValueError("growth_post_check_after_tap_delay_ms 必须在 100~5000 之间")
        return value

    @field_validator("growth_post_second_check_delay_ms")
    @classmethod
    def validate_growth_post_second_check_delay_ms(cls, value: int | None):
        if value is None:
            return value
        if value < 0 or value > 5000:
            raise ValueError("growth_post_second_check_delay_ms 必须在 0~5000 之间")
        return value

    @field_validator("tiktok_force_stop_wait_ms")
    @classmethod
    def validate_tiktok_force_stop_wait_ms(cls, value: int | None):
        if value is None:
            return value
        if value < 100 or value > 8000:
            raise ValueError("tiktok_force_stop_wait_ms 必须在 100~8000 之间")
        return value

    @field_validator("tiktok_launch_wait_ms")
    @classmethod
    def validate_tiktok_launch_wait_ms(cls, value: int | None):
        if value is None:
            return value
        if value < 800 or value > 10000:
            raise ValueError("tiktok_launch_wait_ms 必须在 800~10000 之间")
        return value

    @field_validator("for_you_swipe_total_sec")
    @classmethod
    def validate_for_you_swipe_total_sec(cls, value: float | None):
        if value is None:
            return value
        if value < 0 or value > 600:
            raise ValueError("for_you_swipe_total_sec 必须在 0~600 之间")
        return value

    @field_validator("for_you_watch_min_sec", "for_you_watch_max_sec")
    @classmethod
    def validate_for_you_watch_sec(cls, value: float | None):
        if value is None:
            return value
        if value < 0.5 or value > 120:
            raise ValueError("for_you_watch_min_sec / for_you_watch_max_sec 必须在 0.5~120 之间")
        return value

    @field_validator("comment_texts", "mutual_comment_texts", "mutual_traffic_target_texts", "profile_edit_bio_texts")
    @classmethod
    def validate_comment_texts(cls, value: List[str] | None):
        if value is None:
            return value
        cleaned = [str(x).strip() for x in value]
        cleaned = [x for x in cleaned if x]
        return cleaned[:200]


def _hash_password(password: str) -> str:
    return hashlib.sha256(password.encode('utf-8')).hexdigest()


def _verify_password(password: str, password_hash: str) -> bool:
    return _hash_password(password) == password_hash


def _new_session_token() -> str:
    return uuid.uuid4().hex + uuid.uuid4().hex


def _get_auth_token_from_header(headers: dict | None = None) -> str:
    headers = headers or {}
    auth = str(headers.get('authorization') or headers.get('Authorization') or '').strip()
    if auth.lower().startswith('bearer '):
        return auth[7:].strip()
    return ''


def _current_session(headers: dict | None = None) -> dict | None:
    started = time.perf_counter()
    token = _get_auth_token_from_header(headers)
    if not token:
        print(f"[{now_text()}] auth.session | missing_token")
        return None
    cached = _AUTH_SESSION_CACHE.get(token)
    if cached:
        session, expires_at = cached
        if time.monotonic() < expires_at:
            return dict(session)
        _AUTH_SESSION_CACHE.pop(token, None)
    conn = _get_db()
    try:
        row = conn.execute(
            "SELECT token, username, role, expires_at FROM auth_sessions WHERE token = ?",
            (token,),
        ).fetchone()
        if not row:
            if (time.perf_counter() - started) * 1000 >= 200:
                print(f"[{now_text()}] auth.session | token_not_found | {(time.perf_counter() - started) * 1000:.1f}ms")
            return None
        if str(row[3] or '') < now_text():
            conn.execute("DELETE FROM auth_sessions WHERE token = ?", (token,))
            conn.commit()
            return None
        user = conn.execute(
            "SELECT username, role, is_active FROM auth_users WHERE username = ?",
            (row[1],),
        ).fetchone()
        if not user or int(user[2] or 0) != 1:
            return None
        elapsed_ms = (time.perf_counter() - started) * 1000
        if elapsed_ms >= 200:
            print(f"[{now_text()}] auth.session | slow | {elapsed_ms:.1f}ms user={row[1]}")
        session = {"token": row[0], "username": row[1], "role": row[2]}
        _AUTH_SESSION_CACHE[token] = (session, time.monotonic() + _AUTH_SESSION_CACHE_TTL_SEC)
        return dict(session)
    except sqlite3.OperationalError as exc:
        print(f"[{now_text()}] auth.session | db_busy | {(time.perf_counter() - started) * 1000:.1f}ms err={exc}")
        # e.g. database is locked / busy_timeout hit
        raise RuntimeError(f"DB_BUSY:{exc}") from exc
    finally:
        conn.close()


def _require_login(headers: dict | None = None):
    try:
        session = _current_session(headers)
    except RuntimeError as exc:
        if str(exc).startswith("DB_BUSY:"):
            return {"code": 503, "msg": "数据库繁忙，请稍后重试"}, None
        raise
    if not session:
        return {"code": 401, "msg": "请先登录"}, None
    return None, session


def _require_admin(headers: dict | None = None):
    error, session = _require_login(headers)
    if error:
        return error, None
    if session["role"] != "admin":
        return {"code": 403, "msg": "无权限"}, None
    return None, session


def _verify_device_from_db(device_id: str, alias: str) -> DeviceInfo | None:
    """内存 store 未命中时从 SQLite 恢复（多 worker / 重启后避免误报未绑定）。"""
    did = str(device_id or "").strip()
    alias_norm = _normalize_alias_key(alias)
    if not did or not alias_norm:
        return None
    conn = _get_db()
    row = None
    try:
        row = conn.execute(
            "SELECT device_id, owner_username, alias FROM client_devices WHERE device_id = ?",
            (did,),
        ).fetchone()
    finally:
        conn.close()
    if not row:
        return None
    if _normalize_alias_key(row["alias"]) != alias_norm:
        return None
    _hydrate_device_from_db(did)
    return store.verify_device(did, alias)


def auth_or_error(device_id: str, alias: str):
    device = store.verify_device(device_id, alias)
    if not device:
        device = _verify_device_from_db(device_id, alias)
    if not device:
        return {"code": 2, "msg": "device_id与别名不匹配/设备未绑定"}, None
    return None, device


def _session_device_ids_from_fallback(session: dict) -> set[str]:
    """store.lock 抢不到时，用上一次成功概览缓存的设备表，避免得到空集合整页无设备。"""
    cached_devices = _overview_fallback_snapshot.get("devices") or {}
    if not isinstance(cached_devices, dict):
        return set()
    owner = str(session.get("username") or "").strip()
    out: set[str] = set()
    for did, entry in cached_devices.items():
        if isinstance(entry, dict) and str(entry.get("owner_username") or "") == owner:
            out.add(str(did))
    return out


def _session_device_ids(session: dict, *, lock_wait_sec: float = 2.0) -> set[str]:
    """管理台当前用户可见的设备 ID 集合：仅 owner_username 与登录用户名一致的设备。"""
    owner = str(session.get("username") or "").strip()
    if not owner:
        return set()

    _ = lock_wait_sec  # 保留参数以兼容既有调用方
    conn = _get_db()
    try:
        rows = conn.execute(
            "SELECT device_id FROM client_devices WHERE owner_username = ?",
            (owner,),
        ).fetchall()
        return {str(r[0]) for r in rows}
    except sqlite3.OperationalError:
        print(f"[{now_text()}] session_device_ids | db_failed | using_overview_fallback_devices")
        return _session_device_ids_from_fallback(session)
    finally:
        conn.close()


def _device_session_allowed(device: DeviceInfo, session: dict) -> bool:
    return str(device.owner_username or "").strip() == str(session.get("username") or "").strip()


def _require_login_and_owning_device(
    device_id: str,
    alias: str,
    authorization: str | None,
) -> tuple[dict | None, DeviceInfo | None]:
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err, None
    error, device = auth_or_error(device_id, alias)
    if error:
        return error, None
    if not _device_session_allowed(device, session):
        return {"code": 403, "msg": "无权操作该设备"}, None
    return None, device


def _looks_like_android_device_path(path: str) -> bool:
    """设备上备份路径形如 /storage/.../Android/data/.../files/backups/xxx.tar.gz，不应在服务端本机 unlink。"""
    p = (path or "").strip().replace("\\", "/")
    if not p:
        return False
    return (
        p.startswith("/storage/")
        or p.startswith("/data/")
        or p.startswith("/sdcard/")
        or "/android/data/" in p.lower()
    )


def _fetch_previous_backup_path_for_backup_op(
    owner_username: str,
    backup_record_id: int | None,
    device_id: str,
    username_hint: str,
) -> str:
    """同一档案重复备份前，读取当前库里的 backup_path，供设备端成功后 rm 旧 tar。"""
    owner = (owner_username or "").strip()
    if not owner:
        return ""
    conn = _get_db()
    try:
        if backup_record_id and int(backup_record_id) > 0:
            row = conn.execute(
                """
                SELECT backup_path FROM device_editor_rows
                WHERE owner_username = ? AND row_index = ?
                """,
                (owner, int(backup_record_id)),
            ).fetchone()
            if row:
                return str(row["backup_path"] or "").strip()
        uh = (username_hint or "").strip().lstrip("@")
        did = (device_id or "").strip()
        if uh and did:
            row = conn.execute(
                """
                SELECT backup_path FROM device_editor_rows
                WHERE owner_username = ? AND device_id = ? AND TRIM(username) = ?
                  AND TRIM(COALESCE(backup_path, '')) <> ''
                ORDER BY updated_at DESC, row_index DESC
                LIMIT 1
                """,
                (owner, did, uh),
            ).fetchone()
            if row:
                return str(row["backup_path"] or "").strip()
    finally:
        conn.close()
    return ""


def _list_available_backups() -> List[Dict[str, Any]]:
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT row_index, device_alias, device_id, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, updated_at
            FROM device_editor_rows
            WHERE TRIM(backup_name) <> '' OR TRIM(backup_path) <> '' OR TRIM(backup_time) <> '' OR TRIM(backup_status) <> ''
            ORDER BY updated_at DESC, row_index DESC
            """
        ).fetchall()
        return [dict(r) for r in rows]
    finally:
        conn.close()


def _blank_if_none(text: object | None) -> str:
    if text is None:
        return ""
    return str(text).strip()


def _normalized_alias(value: object | None) -> str:
    return str(value or "").strip()


def _backup_record_alias_expr(table_alias: str = "") -> str:
    prefix = f"{table_alias}." if table_alias else ""
    return f"COALESCE(NULLIF(TRIM({prefix}assigned_alias), ''), {prefix}device_alias)"


def _editor_username_key_sql(column: str) -> str:
    """账号 username 比对键：忽略大小写与 leading @。"""
    return f"LOWER(LTRIM(TRIM({column}), '@'))"


def _device_editor_paged_alias_expr(alias_expr: str | None = None) -> str:
    """分页按别名分组；空别名归入固定桶，避免列表误显示为 0 组。"""
    expr = alias_expr or _backup_record_alias_expr()
    return (
        f"CASE WHEN TRIM({expr}) = '' THEN '{DEVICE_EDITOR_UNASSIGNED_ALIAS_BUCKET}' "
        f"ELSE {expr} END"
    )


def _ensure_device_editor_rows_materialized(conn: sqlite3.Connection, owner: str) -> tuple[int, int]:
    """存在设备档案但有效别名为空时，强制对齐一次（不受 reconcile 限频）。"""
    owner_key = str(owner or "").strip()
    if not owner_key:
        return 0, 0
    alias_expr = _backup_record_alias_expr()
    raw = int(
        conn.execute(
            "SELECT COUNT(*) FROM device_editor_rows WHERE owner_username = ?",
            (owner_key,),
        ).fetchone()[0]
        or 0
    )
    if raw <= 0:
        return 0, 0
    with_alias = int(
        conn.execute(
            f"""
            SELECT COUNT(*) FROM device_editor_rows
            WHERE owner_username = ? AND TRIM({alias_expr}) <> ''
            """,
            (owner_key,),
        ).fetchone()[0]
        or 0
    )
    if with_alias > 0:
        return 0, 0
    return _reconcile_editor_alias_bindings(conn, owner_key)


def _normalized_search_keyword(keyword: str) -> str:
    return str(keyword or "").strip().lower()


def _case_insensitive_like_pattern(keyword: str) -> str:
    q = _normalized_search_keyword(keyword)
    return f"%{q}%" if q else ""


def _device_editor_search_sql(
    search: str,
    *,
    search_field: str = "",
    table_alias: str = "device_editor_rows",
) -> tuple[str, list[Any]]:
    """备份档案列表模糊筛选：username / 别名 / 地区 / device_id / ip（忽略大小写）。"""
    q = _normalized_search_keyword(search)
    if not q:
        return "", []
    pattern = _case_insensitive_like_pattern(search)
    ta = table_alias
    field = str(search_field or "").strip().lower()
    if field == "username":
        return f" AND LOWER(LTRIM(TRIM({ta}.username), '@')) LIKE ?", [pattern]
    if field == "region":
        sql = f"""
        AND (
            LOWER(TRIM({ta}.region)) LIKE ?
            OR EXISTS (
                SELECT 1 FROM account_editor_rows a
                WHERE a.owner_username = {ta}.owner_username
                  AND {_editor_username_key_sql('a.username')} = {_editor_username_key_sql(f'{ta}.username')}
                  AND LOWER(TRIM(a.region)) LIKE ?
            )
        )
        """
        return sql, [pattern, pattern]
    if field == "alias":
        sql = f"""
        AND (
            LOWER(TRIM({ta}.device_alias)) LIKE ?
            OR LOWER(TRIM({ta}.assigned_alias)) LIKE ?
            OR EXISTS (
                SELECT 1 FROM account_editor_rows a
                WHERE a.owner_username = {ta}.owner_username
                  AND {_editor_username_key_sql('a.username')} = {_editor_username_key_sql(f'{ta}.username')}
                  AND LOWER(TRIM(a.assigned_alias)) LIKE ?
            )
        )
        """
        return sql, [pattern, pattern, pattern]
    sql = f"""
        AND (
            LOWER(LTRIM(TRIM({ta}.username), '@')) LIKE ?
            OR LOWER(TRIM({ta}.device_alias)) LIKE ?
            OR LOWER(TRIM({ta}.assigned_alias)) LIKE ?
            OR LOWER(TRIM({ta}.region)) LIKE ?
            OR LOWER(TRIM({ta}.device_id)) LIKE ?
            OR LOWER(TRIM({ta}.ip)) LIKE ?
            OR EXISTS (
                SELECT 1 FROM account_editor_rows a
                WHERE a.owner_username = {ta}.owner_username
                  AND {_editor_username_key_sql('a.username')} = {_editor_username_key_sql(f'{ta}.username')}
                  AND (
                    LOWER(TRIM(a.assigned_alias)) LIKE ?
                    OR LOWER(TRIM(a.region)) LIKE ?
                  )
            )
        )
    """
    return sql, [pattern] * 8


def _sync_account_assigned_alias_for_username(
    conn: sqlite3.Connection,
    owner_username: str,
    username: str,
    assigned_alias: str,
    *,
    only_if_empty: bool = True,
) -> int:
    """将设备档案中的有效别名写回账号记录（仅当账号侧尚未分配时）。"""
    owner = str(owner_username or "").strip()
    uname = _normalized_alias(username).lstrip("@")
    alias = _normalized_alias(assigned_alias)
    if not owner or not uname or not alias:
        return 0
    sql = """
        UPDATE account_editor_rows
        SET assigned_alias = ?, updated_at = ?
        WHERE owner_username = ?
          AND LOWER(TRIM(username)) = LOWER(TRIM(?))
    """
    params: list[Any] = [alias, now_text(), owner, uname]
    if only_if_empty:
        sql += " AND TRIM(COALESCE(assigned_alias, '')) = ''"
    cur = conn.execute(sql, params)
    return int(cur.rowcount or 0)


def _sync_device_aliases_to_account_rows(conn: sqlite3.Connection, owner_username: str) -> int:
    """将设备备份档案中的有效别名同步回同 username 的账号记录（修复仅设备表有别名的情况）。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return 0
    alias_expr = _backup_record_alias_expr()
    rows = conn.execute(
        f"""
        SELECT username, {alias_expr} AS eff_alias
        FROM device_editor_rows
        WHERE owner_username = ?
          AND TRIM(username) <> ''
          AND TRIM({alias_expr}) <> ''
        ORDER BY row_index ASC
        """,
        (owner,),
    ).fetchall()
    touched = 0
    for row in rows or []:
        uname = str(row["username"] or "").strip()
        alias = _normalized_alias(row["eff_alias"])
        if not uname or not alias:
            continue
        touched += _sync_account_assigned_alias_for_username(
            conn, owner, uname, alias, only_if_empty=True
        )
    return touched


def _reconcile_editor_alias_bindings(conn: sqlite3.Connection, owner_username: str) -> tuple[int, int]:
    """双向对齐账号表与设备档案的分配别名（先设备→账号，再账号→设备）。"""
    to_accounts = _sync_device_aliases_to_account_rows(conn, owner_username)
    to_devices = _sync_account_aliases_to_device_rows(conn, owner_username)
    return to_accounts, to_devices


def _sync_account_aliases_to_device_rows(conn: sqlite3.Connection, owner_username: str) -> int:
    """将账号记录中的 assigned_alias 同步到同 username 的设备备份行（修复仅账号表有别名的情况）。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return 0
    rows = conn.execute(
        """
        SELECT username, assigned_alias, region
        FROM account_editor_rows
        WHERE owner_username = ? AND TRIM(COALESCE(assigned_alias, '')) <> ''
        ORDER BY row_index ASC
        """,
        (owner,),
    ).fetchall()
    touched = 0
    for row in rows or []:
        uname = str(row["username"] or "").strip()
        alias = _normalized_alias(row["assigned_alias"])
        region = _normalized_alias(row["region"])
        if not uname or not alias:
            continue
        touched += _upsert_backup_record_for_account(
            owner, uname, alias, region, conn=conn, commit=False
        )
    return touched


def _prune_orphan_device_editor_rows(conn: sqlite3.Connection, owner_username: str) -> int:
    """删除账号记录已不存在、但 device_editor_rows 仍残留的备份档案（脏数据）。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return 0
    user_key = _editor_username_key_sql("username")
    cur = conn.execute(
        f"""
        DELETE FROM device_editor_rows
        WHERE owner_username = ?
          AND TRIM(username) <> ''
          AND {user_key} NOT IN (
            SELECT {_editor_username_key_sql('username')}
            FROM account_editor_rows
            WHERE owner_username = ? AND TRIM(username) <> ''
          )
        """,
        (owner, owner),
    )
    return int(cur.rowcount or 0)


def _shift_account_editor_row_indices_after_delete(
    conn: sqlite3.Connection, owner_username: str, deleted_row_index: int, now: str | None = None
) -> None:
    """
    删除一行后，将更大 row_index 顺次减 1。
    必须按 row_index 从小到大逐条更新，避免 UPDATE 时主键 (owner_username, row_index) 冲突。
    """
    owner = str(owner_username or "").strip()
    if not owner:
        return
    ts = now or now_text()
    deleted_row_index = int(deleted_row_index)
    rows = conn.execute(
        """
        SELECT row_index FROM account_editor_rows
        WHERE owner_username = ? AND row_index > ?
        ORDER BY row_index ASC
        """,
        (owner, deleted_row_index),
    ).fetchall()
    for row in rows or []:
        old_idx = int(row["row_index"])
        conn.execute(
            """
            UPDATE account_editor_rows
            SET row_index = ?, updated_at = ?
            WHERE owner_username = ? AND row_index = ?
            """,
            (old_idx - 1, ts, owner, old_idx),
        )


def _normalize_switch_highlight(value: object | None) -> str:
    """切号高亮：仅允许「是」或「否」，其余视为否。"""
    return "是" if str(value or "").strip() == "是" else "否"


ENV_STATUS_ACTIVE = "环境活跃"
ENV_STATUS_RESTORE_FAILED = "还原失败"
ENV_STATUS_PENDING = "待就绪"


def _resolve_device_editor_row_index_for_restore(owner_username: str, data: dict) -> int:
    """从设备还原/切号回传 data 解析目标 device_editor_rows.row_index。"""
    owner = str(owner_username or "").strip()
    if not owner or not isinstance(data, dict):
        return -1
    for key in ("restore_highlight_row_index", "cut_switch_target_row_index"):
        try:
            tid = int(data.get(key)) if data.get(key) is not None else -1
        except (TypeError, ValueError):
            tid = -1
        if tid >= 0:
            return tid
    bn = str(data.get("backup_name") or "").strip()
    if not bn:
        return -1
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT row_index FROM device_editor_rows
            WHERE owner_username = ? AND backup_name = ?
            ORDER BY updated_at DESC, row_index DESC
            LIMIT 1
            """,
            (owner, Path(bn).name if bn else ""),
        ).fetchone()
        return int(row[0]) if row else -1
    finally:
        conn.close()


def _db_set_env_status(
    conn: sqlite3.Connection,
    owner_username: str,
    row_index: int,
    env_status: str,
    now: str | None = None,
) -> None:
    owner = str(owner_username or "").strip()
    try:
        rid = int(row_index)
    except (TypeError, ValueError):
        return
    if not owner or rid < 0:
        return
    ts = now or now_text()
    conn.execute(
        """
        UPDATE device_editor_rows
        SET env_status = ?, updated_at = ?
        WHERE owner_username = ? AND row_index = ?
        """,
        (str(env_status or "").strip(), ts, owner, rid),
    )


def _ensure_device_editor_switch_highlight_invariants(conn: sqlite3.Connection, owner_username: str) -> None:
    """
    切号高亮不变量（仅单账号桶）：
    - 单账号桶：强制 switch_highlight = 是；
    - 多账号桶：高亮由 bundle 下发时段计划派生，不在此写库（自治期也不认设备 OP 回传改高亮）。
    """
    owner_username = str(owner_username or "").strip()
    if not owner_username:
        return
    cur = conn.execute(
        """
        SELECT row_index, assigned_alias, device_alias, switch_highlight
        FROM device_editor_rows
        WHERE owner_username = ?
        ORDER BY row_index ASC
        """,
        (owner_username,),
    )
    rows = [dict(r) for r in cur.fetchall()]
    if not rows:
        return
    buckets: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        aa = str(r.get("assigned_alias") or "").strip()
        da = str(r.get("device_alias") or "").strip()
        key = aa if aa else da
        buckets.setdefault(key, []).append(r)

    now = now_text()
    for _bucket_key, grp in buckets.items():
        if len(grp) != 1:
            continue
        rid = int(grp[0]["row_index"])
        if _normalize_switch_highlight(grp[0].get("switch_highlight")) != "是":
            conn.execute(
                """
                UPDATE device_editor_rows
                SET switch_highlight = '是', updated_at = ?
                WHERE owner_username = ? AND row_index = ?
                """,
                (now, owner_username, rid),
            )


def _db_apply_switch_highlight_exclusive(conn: sqlite3.Connection, owner_username: str, row_index: int, now: str | None = None) -> None:
    """同别名桶内仅 row_index 为「是」，其余「否」（与 set_switch_highlight 接口一致）。"""
    owner_username = str(owner_username or "").strip()
    if not owner_username or int(row_index) < 0:
        return
    ts = now or now_text()
    row = conn.execute(
        """
        SELECT assigned_alias, device_alias
        FROM device_editor_rows
        WHERE owner_username = ? AND row_index = ?
        LIMIT 1
        """,
        (owner_username, int(row_index)),
    ).fetchone()
    if not row:
        return
    eff_assigned = str(row["assigned_alias"] or "").strip()
    eff_device = str(row["device_alias"] or "").strip()
    bucket = eff_assigned if eff_assigned else eff_device
    conn.execute(
        f"""
        UPDATE device_editor_rows
        SET switch_highlight = '否', updated_at = ?
        WHERE owner_username = ? AND LOWER(TRIM({_backup_record_alias_expr()})) = LOWER(TRIM(?))
        """,
        (ts, owner_username, bucket),
    )
    conn.execute(
        """
        UPDATE device_editor_rows
        SET switch_highlight = '是', updated_at = ?
        WHERE owner_username = ? AND row_index = ?
        """,
        (ts, owner_username, int(row_index)),
    )


def _apply_cut_switch_highlight_intent(owner_username: str, target_row_index: int) -> bool:
    """切号任务入队：仅更新 env_status；高亮由时段计划派生，不在此写入。"""
    owner = str(owner_username or "").strip()
    try:
        tid = int(target_row_index)
    except (TypeError, ValueError):
        return False
    if not owner or tid < 0:
        return False
    conn = _get_db()
    try:
        now = now_text()
        row = conn.execute(
            """
            SELECT assigned_alias, device_alias
            FROM device_editor_rows
            WHERE owner_username = ? AND row_index = ?
            LIMIT 1
            """,
            (owner, tid),
        ).fetchone()
        if row:
            bucket = str(row["assigned_alias"] or "").strip() or str(row["device_alias"] or "").strip()
            conn.execute(
                f"""
                UPDATE device_editor_rows
                SET env_status = '', updated_at = ?
                WHERE owner_username = ? AND LOWER(TRIM({_backup_record_alias_expr()})) = LOWER(TRIM(?))
                  AND row_index <> ?
                """,
                (now, owner, bucket, tid),
            )
            _db_set_env_status(conn, owner, tid, ENV_STATUS_PENDING, now)
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
        return True
    finally:
        conn.close()


def _maybe_apply_cut_switch_highlight_on_enqueue(
    owner_username: str,
    cut_switch_after_backup: str,
    cut_switch_target_row_index: int | None,
) -> bool:
    cs = str(cut_switch_after_backup or "").strip().lower()
    if cs not in ("clear_source_for_restore", "exclusive_activate_target"):
        return False
    if cut_switch_target_row_index is None:
        return False
    try:
        tid = int(cut_switch_target_row_index)
    except (TypeError, ValueError):
        return False
    if tid < 0:
        return False
    return _apply_cut_switch_highlight_intent(owner_username, tid)


def _device_ops_apply_cut_switch_after_backup(owner_username: str, data: dict) -> None:
    """设备回传「备份并清理/清理」成功：仅确认 env_status（高亮由时段计划派生）。"""
    mode = str(data.get("cut_switch_after_backup") or "").strip().lower()
    if mode not in ("clear_source_for_restore", "exclusive_activate_target"):
        return
    conn = _get_db()
    try:
        now = now_text()
        if mode == "clear_source_for_restore":
            tr = data.get("cut_switch_target_row_index")
            try:
                tid = int(tr) if tr is not None else -1
            except (TypeError, ValueError):
                tid = -1
            if tid >= 0:
                _db_set_env_status(conn, owner_username, tid, ENV_STATUS_PENDING, now)
        elif mode == "exclusive_activate_target":
            tr = data.get("cut_switch_target_row_index")
            try:
                tid = int(tr) if tr is not None else -1
            except (TypeError, ValueError):
                tid = -1
            if tid >= 0:
                _db_set_env_status(conn, owner_username, tid, ENV_STATUS_ACTIVE, now)
        _ensure_device_editor_switch_highlight_invariants(conn, owner_username)
        conn.commit()
    finally:
        conn.close()


def _device_ops_apply_cut_switch_after_restore(owner_username: str, data: dict) -> None:
    """设备回传「还原」成功：标 env 活跃；高亮由时段计划派生。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return
    tid = _resolve_device_editor_row_index_for_restore(owner, data)
    if tid < 0:
        return
    conn = _get_db()
    try:
        now = now_text()
        _db_set_env_status(conn, owner, tid, ENV_STATUS_ACTIVE, now)
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
    finally:
        conn.close()


def _device_ops_apply_restore_failure(owner_username: str, data: dict) -> None:
    """设备回传「还原」失败时，环境归属不回滚，仅标记还原失败。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return
    tid = _resolve_device_editor_row_index_for_restore(owner, data)
    if tid < 0:
        return
    conn = _get_db()
    try:
        now = now_text()
        _db_set_env_status(conn, owner, tid, ENV_STATUS_RESTORE_FAILED, now)
        conn.commit()
    finally:
        conn.close()


def _resolve_cut_switch_source_row_index(data: dict) -> int:
    if not isinstance(data, dict):
        return -1
    try:
        rid = int(data.get("backup_record_id")) if data.get("backup_record_id") is not None else -1
    except (TypeError, ValueError):
        rid = -1
    return rid if rid >= 0 else -1


def _device_ops_rollback_cut_switch_highlight(owner_username: str, data: dict) -> None:
    """切号失败：高亮仍按时段计划；源账号 env 标活跃。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return
    source_row = _resolve_cut_switch_source_row_index(data)
    if source_row < 0:
        return
    conn = _get_db()
    try:
        now = now_text()
        _db_set_env_status(conn, owner, source_row, ENV_STATUS_ACTIVE, now)
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
    finally:
        conn.close()


def _ensure_device_auto_start_after_cut_switch(
    device_id: str,
    alias: str = "",
    owner_username: str = "",
) -> dict[str, bool]:
    """切号全流程成功后：清除 opt_out，并在用户开启自动开启策略时下发 pending_start。"""
    did = (device_id or "").strip()
    if not did:
        return {"opt_out_cleared": False, "auto_start_resumed": False}
    dev_alias = ""
    opt_out_cleared = False
    auto_start_resumed = False
    with store.lock:
        dev = store.devices.get(did)
        if not dev:
            return {"opt_out_cleared": False, "auto_start_resumed": False}
        dev_alias = str(dev.alias or alias or "").strip()
        owner = str(owner_username or dev.owner_username or "").strip()
        if dev.auto_start_opt_out:
            dev.auto_start_opt_out = False
            opt_out_cleared = True
        if _user_auto_start_enabled(owner):
            dev.pending_start = True
            dev.paused = False
            dev.status = "running"
            auto_start_resumed = True
    if opt_out_cleared or auto_start_resumed:
        _persist_client_device_to_db(did)
        parts = []
        if opt_out_cleared:
            parts.append("已清除自动开启排除")
        if auto_start_resumed:
            parts.append("已下发启动指令（参与自动开启程序）")
        _append_device_log(
            did,
            f"[{now_text()}] 切号完成 | {'，'.join(parts) or '已恢复自动开启'}"
            + (f"（alias={dev_alias}）" if dev_alias else ""),
        )
    return {"opt_out_cleared": opt_out_cleared, "auto_start_resumed": auto_start_resumed}


def _restore_device_auto_start_opt_out_after_cut_switch(device_id: str, alias: str = "") -> bool:
    """兼容旧调用：切号成功后恢复自动开启参与。"""
    result = _ensure_device_auto_start_after_cut_switch(device_id, alias=alias)
    return bool(result.get("opt_out_cleared") or result.get("auto_start_resumed"))


# 发作品/切号/改资料等临时关闭 opt_out 的任务；上报结束（成功或失败）后应恢复参与自动开启。
_TRANSIENT_AUTO_START_RESTORE_OP_TYPES = frozenset(
    {
        "tiktok_clear",
        "tiktok_backup_and_clear",
        "tiktok_restore",
        "tiktok_edit_basic_profile",
    }
)


def _restore_device_auto_start_participation(
    device_id: str,
    alias: str = "",
    reason: str = "",
) -> bool:
    """任务结束后恢复设备参与云端自动开启（与成功与否无关；不含管理台手动 opt_out 的语义变更）。"""
    did = (device_id or "").strip()
    if not did:
        return False
    dev_alias = ""
    cleared = False
    with store.lock:
        dev = store.devices.get(did)
        if not dev:
            return False
        dev_alias = str(dev.alias or alias or "").strip()
        if dev.auto_start_opt_out:
            dev.auto_start_opt_out = False
            cleared = True
    if cleared:
        _persist_client_device_to_db(did)
        extra = f" | {reason}" if reason else ""
        alias_note = f"（alias={dev_alias}）" if dev_alias else ""
        _append_device_log(
            did,
            f"[{now_text()}] 云端后台 | 任务结束，已恢复该设备自动开启程序{extra}{alias_note}",
        )
    return cleared


def _beijing_now() -> datetime:
    return datetime.now(BEIJING_TZ)


def _beijing_date_str(dt: datetime | None = None) -> str:
    return (_beijing_now() if dt is None else dt.astimezone(BEIJING_TZ)).strftime("%Y-%m-%d")


def _beijing_minute_of_day(dt: datetime | None = None) -> int:
    cur = _beijing_now() if dt is None else dt.astimezone(BEIJING_TZ)
    return cur.hour * 60 + cur.minute


def _parse_beijing_date(date_str: str) -> datetime:
    text = str(date_str or "").strip()
    return datetime.strptime(text, "%Y-%m-%d").replace(tzinfo=BEIJING_TZ)


def _beijing_week_bounds(date_str: str | None = None) -> tuple[str, str]:
    """北京时间自然周：周一 00:00 至周日 24:00（含首尾日期）。"""
    date_key = str(date_str or "").strip() or _beijing_date_str()
    dt = _parse_beijing_date(date_key)
    monday = dt - timedelta(days=dt.weekday())
    sunday = monday + timedelta(days=6)
    return monday.strftime("%Y-%m-%d"), sunday.strftime("%Y-%m-%d")


def _iter_dates_inclusive(start_date: str, end_date: str) -> List[str]:
    start = _parse_beijing_date(start_date)
    end = _parse_beijing_date(end_date)
    if end < start:
        return []
    out: List[str] = []
    cur = start
    while cur <= end:
        out.append(cur.strftime("%Y-%m-%d"))
        cur += timedelta(days=1)
    return out


def _date_in_week_range(date_str: str, week_start: str, week_end: str) -> bool:
    try:
        d = _parse_beijing_date(date_str)
        s = _parse_beijing_date(week_start)
        e = _parse_beijing_date(week_end)
    except ValueError:
        return False
    return s <= d <= e


def _auto_switch_bucket_key(owner_username: str, alias: str) -> str:
    return f"{str(owner_username or '').strip()}::{_normalize_alias_key(alias)}"


def _auto_switch_log(line: str) -> None:
    text = str(line or "").rstrip("\r\n")
    if not text:
        return
    with store.lock:
        logs = store.task_logs.setdefault(AUTO_SWITCH_TASK_LOG_KEY, [])
        logs.append(text)
        if len(logs) > 500:
            del logs[: len(logs) - 500]
    print(f"[{now_text()}] auto_switch | {text}")


def _auto_switch_row_dict(row: sqlite3.Row | tuple | dict) -> dict[str, Any]:
    if isinstance(row, dict):
        return dict(row)
    if hasattr(row, "keys"):
        return dict(row)
    return {
        "row_index": row[0] if len(row) > 0 else None,
        "device_alias": row[1] if len(row) > 1 else "",
        "device_id": row[2] if len(row) > 2 else "",
        "username": row[3] if len(row) > 3 else "",
        "assigned_alias": row[4] if len(row) > 4 else "",
        "backup_path": row[5] if len(row) > 5 else "",
        "backup_name": row[6] if len(row) > 6 else "",
        "switch_highlight": row[7] if len(row) > 7 else "否",
        "video_count": row[8] if len(row) > 8 else "",
        "auto_switch_excluded": bool(int(row[9] or 0)) if len(row) > 9 else False,
    }


def _list_device_editor_buckets(owner_username: str) -> Dict[str, List[dict[str, Any]]]:
    owner = str(owner_username or "").strip()
    if not owner:
        return {}
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT row_index, device_alias, device_id, username, assigned_alias,
                   backup_path, backup_name, switch_highlight, video_count, auto_switch_excluded
            FROM device_editor_rows
            WHERE owner_username = ? AND TRIM(username) <> '' AND COALESCE(auto_switch_excluded, 0) = 0
            ORDER BY row_index ASC
            """,
            (owner,),
        ).fetchall()
    finally:
        conn.close()
    buckets: Dict[str, List[dict[str, Any]]] = {}
    for row in rows or []:
        item = _auto_switch_row_dict(row)
        alias_key = _device_editor_effective_alias(item)
        if not alias_key:
            continue
        buckets.setdefault(alias_key, []).append(item)
    return {k: v for k, v in buckets.items() if len(v) >= 2}


def _delete_auto_switch_plans_for_alias(owner_username: str, alias: str) -> None:
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner or not alias_key:
        return
    conn = _get_db()
    try:
        conn.execute(
            "DELETE FROM auto_switch_daily_plans WHERE owner_username = ? AND alias = ?",
            (owner, alias_key),
        )
        conn.execute(
            "DELETE FROM auto_switch_weekly_plans WHERE owner_username = ? AND alias = ?",
            (owner, alias_key),
        )
        conn.commit()
    finally:
        conn.close()


def _refresh_auto_switch_plans_for_alias(owner_username: str, alias: str) -> dict[str, Any]:
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner or not alias_key:
        return {"bucket_size": 0, "plan_regenerated": False}
    rows = _list_device_editor_buckets(owner).get(alias_key, [])
    _delete_auto_switch_plans_for_alias(owner, alias_key)
    if len(rows) < 2:
        return {"bucket_size": len(rows), "plan_regenerated": False}
    schedule_date = _beijing_date_str()
    _ensure_auto_switch_weekly_plan(owner, alias_key, rows, schedule_date)
    _ensure_auto_switch_daily_plan(owner, alias_key, rows, schedule_date)
    return {"bucket_size": len(rows), "plan_regenerated": True}


def _enabled_auto_switch_device_ids_for_owner_alias(owner_username: str, alias: str) -> list[str]:
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner or not alias_key:
        return []
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT device_id
            FROM client_devices
            WHERE owner_username = ?
              AND LOWER(TRIM(alias)) = LOWER(TRIM(?))
              AND COALESCE(auto_switch_enabled, 0) = 1
            ORDER BY updated_at DESC, device_id ASC
            """,
            (owner, alias_key),
        ).fetchall()
        return [str(r[0] or "").strip() for r in rows if str(r[0] or "").strip()]
    finally:
        conn.close()


def _resync_auto_switch_devices_for_alias(owner_username: str, alias: str, bucket_size: int) -> dict[str, Any]:
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner or not alias_key:
        return {"notified": 0, "disabled": 0, "session_id": "", "bundle_revision": 0}
    device_ids = _enabled_auto_switch_device_ids_for_owner_alias(owner, alias_key)
    if not device_ids:
        return {"notified": 0, "disabled": 0, "session_id": "", "bundle_revision": 0}

    if bucket_size >= 2:
        session_id = _new_auto_switch_session_id(entering=True)
        with store.lock:
            store.auto_switch_bundle_revision = int(store.auto_switch_bundle_revision or 0) + 1
            bundle_revision = int(store.auto_switch_bundle_revision)
            store.auto_switch_align_on_next_tick = True
        persist_runtime_state()
        _persist_auto_switch_session_row(session_id, enabled=True, bundle_revision=bundle_revision)
        notified = 0
        for did in device_ids:
            if not store.devices.get(did):
                _hydrate_device_from_db(did)
            closed_sessions = _close_active_auto_switch_enter_sessions_for_device(
                did, except_session_id=session_id
            )
            if closed_sessions > 0:
                _auto_switch_log(
                    f"账号排除切换后关闭 {closed_sessions} 个旧 enter session device={did} session={session_id}"
                )
            superseded = _supersede_pending_device_mode_events(did, ("auto_switch_exit",))
            if superseded > 0:
                _auto_switch_log(
                    f"账号排除切换后作废 {superseded} 条 exit 事件 device={did} session={session_id}"
                )
            if _enqueue_device_mode_event(did, owner, "auto_switch_enter", session_id, bundle_revision=bundle_revision):
                notified += 1
                _append_device_log(
                    did,
                    f"[{now_text()}] 自动切号 | 账号参与范围变更，已下发资料重同步 session={session_id} revision={bundle_revision}",
                )
        return {
            "notified": notified,
            "disabled": 0,
            "session_id": session_id,
            "bundle_revision": bundle_revision,
        }

    session_id = _new_auto_switch_session_id(entering=False)
    _persist_auto_switch_session_row(session_id, enabled=False, bundle_revision=0)
    notified = 0
    disabled = 0
    cancelled_ops = 0
    for did in device_ids:
        if not store.devices.get(did):
            _hydrate_device_from_db(did)
        with store.lock:
            dev = store.devices.get(did)
            if dev and dev.auto_switch_enabled:
                dev.auto_switch_enabled = False
                disabled += 1
        _persist_client_device_to_db(did, force=True)
        closed_sessions = _close_active_auto_switch_enter_sessions_for_device(did)
        if closed_sessions > 0:
            _auto_switch_log(
                f"账号排除导致桶内不足 2 个账号，关闭 {closed_sessions} 个旧 enter session device={did} session={session_id}"
            )
        superseded = _supersede_pending_device_mode_events(did, ("auto_switch_enter",))
        if superseded > 0:
            _auto_switch_log(
                f"账号排除导致桶内不足 2 个账号，作废 {superseded} 条 enter 事件 device={did} session={session_id}"
            )
        cancelled_ops += _cancel_pending_auto_switch_device_ops_for_device(did)
        if _enqueue_device_mode_event(did, owner, "auto_switch_exit", session_id, bundle_revision=0):
            notified += 1
            _append_device_log(
                did,
                f"[{now_text()}] 自动切号 | 参与账号不足 2 个，已自动关闭自治 session={session_id}",
            )
    if cancelled_ops > 0:
        _auto_switch_log(
            f"账号排除导致关闭自动切号 alias={alias_key} cancelled_ops={cancelled_ops} session={session_id}"
        )
    return {"notified": notified, "disabled": disabled, "session_id": session_id, "bundle_revision": 0}


def _auto_publish_count_for_video_count(video_count: int) -> int:
    """自动切号发作品：按账号 video_count 决定本次下发条数（不含 -1 新号冷却标记）。"""
    vc = max(0, int(video_count))
    if vc < 3:
        return 1
    if vc <= 9:
        return 2
    return 3


def _auto_switch_graduate_new_account_video_count(
    owner_username: str,
    target_row: dict[str, Any],
) -> bool:
    """自动切号发作品：新号 video_count=-1 首次触发时改为 0，表示已过 24h 冷却。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return False
    try:
        raw_ri = target_row.get("row_index")
        row_index = int(raw_ri) if raw_ri is not None else -1
    except (TypeError, ValueError):
        row_index = -1
    username = str(target_row.get("username") or "").strip()
    now = now_text()
    changed = 0
    conn = _get_db()
    try:
        if row_index >= 0:
            cur = conn.execute(
                """
                UPDATE device_editor_rows
                SET video_count = '0', updated_at = ?
                WHERE owner_username = ? AND row_index = ? AND TRIM(video_count) = ?
                """,
                (now, owner, row_index, str(NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN)),
            )
            changed += int(cur.rowcount or 0)
        if username:
            cur = conn.execute(
                """
                UPDATE account_editor_rows
                SET video_count = '0', updated_at = ?
                WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))
                  AND TRIM(video_count) = ?
                """,
                (now, owner, username, str(NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN)),
            )
            changed += int(cur.rowcount or 0)
        conn.commit()
    finally:
        conn.close()
    if changed > 0:
        target_row["video_count"] = "0"
    return changed > 0


def _auto_switch_skip_publish_new_account_cooldown(
    owner_username: str,
    alias: str,
    target_row: dict[str, Any],
    slot_key: str,
    bucket_key: str,
    publish_minute: int,
) -> None:
    """新号首次自动发作品窗口：-1→0，本时段不发作品并标记完成。"""
    account = str(target_row.get("username") or "").strip()
    _auto_switch_graduate_new_account_video_count(owner_username, target_row)
    done_key = _auto_switch_publish_done_key(slot_key)
    with store.lock:
        store.auto_switch_publish_inflight_keys.pop(bucket_key, None)
        store.auto_switch_publish_done_keys[bucket_key] = done_key
    persist_runtime_state()
    _auto_switch_log(
        f"新号 24h 冷却：本时段跳过自动发作品（video_count -1→0）| owner={owner_username} alias={alias} "
        f"slot={slot_key} time={_format_beijing_minute(publish_minute)} account={account or '-'}"
    )


def _random_auto_publish_minute(start_min: int, end_min: int) -> int | None:
    """在时段开始 10 分钟后至切号前 10 分钟内随机取一分钟触发自动发作品。"""
    window_start = int(start_min) + 10
    window_end = int(end_min) - 10
    if window_end <= window_start:
        return None
    return random.randint(window_start, window_end)


def _format_beijing_minute(minute_of_day: int) -> str:
    minute = max(0, min(int(minute_of_day), 1439))
    h, mi = divmod(minute, 60)
    return f"{h:02d}:{mi:02d}"


def _ensure_auto_publish_minutes_in_plan(plan: dict[str, Any]) -> bool:
    """为缺少 auto_publish_minute 的时段补齐随机发作品时间点；有变更则返回 True。"""
    slots = plan.get("slots") if isinstance(plan.get("slots"), list) else []
    modified = False
    for slot in slots:
        if "auto_publish_minute" in slot:
            continue
        try:
            start = int(slot.get("start_min") or 0)
            end = int(slot.get("end_min") or 0)
        except (TypeError, ValueError):
            start, end = 0, 0
        slot["auto_publish_minute"] = _random_auto_publish_minute(start, end)
        modified = True
    return modified


def _random_auto_mutual_minute(start_min: int, end_min: int, publish_min: int | None) -> int | None:
    """在发作品点之后（或时段开始+10 分钟）至切号前 10 分钟内随机取一分钟触发引流互点。"""
    base = int(start_min) + 10
    window_start = (int(publish_min) + 5) if publish_min is not None else base
    window_end = int(end_min) - 10
    if window_end <= window_start:
        return None
    return random.randint(window_start, window_end)


def _ensure_auto_mutual_minutes_in_plan(plan: dict[str, Any]) -> bool:
    """为缺少 auto_mutual_minute 的时段补齐随机引流互点时间点；有变更则返回 True。"""
    slots = plan.get("slots") if isinstance(plan.get("slots"), list) else []
    modified = False
    for slot in slots:
        if "auto_mutual_minute" in slot:
            continue
        try:
            start = int(slot.get("start_min") or 0)
            end = int(slot.get("end_min") or 0)
        except (TypeError, ValueError):
            start, end = 0, 0
        pub = slot.get("auto_publish_minute")
        try:
            pub_min = int(pub) if pub is not None else None
        except (TypeError, ValueError):
            pub_min = None
        slot["auto_mutual_minute"] = _random_auto_mutual_minute(start, end, pub_min)
        modified = True
    return modified


def _ensure_slot_timed_insertions_in_plan(plan: dict[str, Any]) -> bool:
    modified = False
    if _ensure_auto_publish_minutes_in_plan(plan):
        modified = True
    if _ensure_auto_mutual_minutes_in_plan(plan):
        modified = True
    return modified


def _generate_auto_switch_daily_plan(rows: List[dict[str, Any]]) -> dict[str, Any]:
    ordered = sorted(rows, key=lambda r: int(r.get("row_index") or 0))
    shuffled = list(ordered)
    random.shuffle(shuffled)
    n = len(shuffled)
    base = 1440 // n
    extra = 1440 % n
    slots: List[dict[str, Any]] = []
    start = 0
    for idx, row in enumerate(shuffled):
        span = base + (1 if idx < extra else 0)
        end = start + span
        if idx == n - 1:
            end = 1440
        pub_min = _random_auto_publish_minute(start, end)
        slots.append(
            {
                "slot_index": idx,
                "start_min": start,
                "end_min": end,
                "row_index": int(row.get("row_index") or 0),
                "username": str(row.get("username") or "").strip(),
                "auto_publish_minute": pub_min,
                "auto_mutual_minute": _random_auto_mutual_minute(start, end, pub_min),
            }
        )
        start = end
    return {"slot_minutes": base, "account_count": n, "slots": slots}


def _slot_username_order(plan: dict[str, Any]) -> tuple[str, ...]:
    slots = plan.get("slots") if isinstance(plan.get("slots"), list) else []
    return tuple(str(s.get("username") or "").strip() for s in slots)


def _weekly_plan_rows_fingerprint(rows: List[dict[str, Any]]) -> str:
    parts: List[str] = []
    for row in sorted(rows, key=lambda r: int(r.get("row_index") or 0)):
        ri = int(row.get("row_index") or -1)
        user = str(row.get("username") or "").strip().lower()
        parts.append(f"{ri}:{user}")
    return "|".join(parts)


def _generate_auto_switch_weekly_plan(
    rows: List[dict[str, Any]],
    week_start_date: str,
    week_end_date: str,
) -> dict[str, Any]:
    """为自然周内每一天独立洗牌生成时段（每天账号顺序不同）。"""
    days: Dict[str, Any] = {}
    for date_key in _iter_dates_inclusive(week_start_date, week_end_date):
        days[date_key] = _generate_auto_switch_daily_plan(rows)
    # return 必须在 for 循环外（与 for 同级缩进），否则会只生成 1 天
    return {
        "week_start_date": week_start_date,
        "week_end_date": week_end_date,
        "account_count": len(rows),
        "rows_fingerprint": _weekly_plan_rows_fingerprint(rows),
        "days": days,
    }


def _load_auto_switch_weekly_plan(
    owner_username: str,
    alias: str,
    week_start_date: str,
) -> dict[str, Any] | None:
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    week_key = str(week_start_date or "").strip()
    if not owner or not alias_key or not week_key:
        return None
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT plan_json FROM auto_switch_weekly_plans
            WHERE owner_username = ? AND week_start_date = ? AND alias = ?
            """,
            (owner, week_key, alias_key),
        ).fetchone()
    finally:
        conn.close()
    if not row or not row[0]:
        return None
    try:
        plan = json.loads(row[0])
        return plan if isinstance(plan, dict) else None
    except Exception:
        return None


def _save_auto_switch_weekly_plan(
    owner_username: str,
    alias: str,
    week_start_date: str,
    plan: dict[str, Any],
) -> None:
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    week_key = str(week_start_date or "").strip()
    if not owner or not alias_key or not week_key:
        return
    conn = _get_db()
    try:
        conn.execute(
            """
            INSERT OR REPLACE INTO auto_switch_weekly_plans
            (owner_username, week_start_date, alias, plan_json, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (owner, week_key, alias_key, json.dumps(plan, ensure_ascii=False), now_text()),
        )
        conn.commit()
    finally:
        conn.close()


def _weekly_plan_is_complete(
    plan: dict[str, Any] | None,
    week_start_date: str,
    week_end_date: str,
    rows: List[dict[str, Any]],
) -> bool:
    account_count = len(rows)
    if not plan or not isinstance(plan.get("days"), dict):
        return False
    if int(plan.get("account_count") or 0) != int(account_count):
        return False
    expected_fp = _weekly_plan_rows_fingerprint(rows)
    stored_fp = str(plan.get("rows_fingerprint") or "").strip()
    if stored_fp and stored_fp != expected_fp:
        return False
    if not stored_fp and account_count > 0:
        return False
    days = plan.get("days") or {}
    expected = _iter_dates_inclusive(week_start_date, week_end_date)
    if len(expected) != 7:
        return False
    for date_key in expected:
        day_plan = days.get(date_key)
        if not isinstance(day_plan, dict):
            return False
        slots = day_plan.get("slots")
        if not isinstance(slots, list) or len(slots) != account_count:
            return False
    return True


def _ensure_auto_publish_minutes_in_weekly_plan(plan: dict[str, Any]) -> bool:
    modified = False
    days = plan.get("days")
    if not isinstance(days, dict):
        return False
    for day_plan in days.values():
        if isinstance(day_plan, dict) and _ensure_slot_timed_insertions_in_plan(day_plan):
            modified = True
    return modified


def _ensure_auto_switch_weekly_plan(
    owner_username: str,
    alias: str,
    rows: List[dict[str, Any]],
    schedule_date: str | None = None,
) -> dict[str, Any]:
    """确保当前自然周（周一至周日）的 7 天排班已生成；每天独立洗牌。"""
    date_key = str(schedule_date or "").strip() or _beijing_date_str()
    week_start, week_end = _beijing_week_bounds(date_key)
    existing = _load_auto_switch_weekly_plan(owner_username, alias, week_start)
    if _weekly_plan_is_complete(existing, week_start, week_end, rows):
        if _ensure_auto_publish_minutes_in_weekly_plan(existing):
            _save_auto_switch_weekly_plan(owner_username, alias, week_start, existing)
            _auto_switch_log(
                f"已补齐本周发作品/互点随机时间点 | owner={owner_username} alias={alias} week={week_start}~{week_end}"
            )
        return existing
    plan = _generate_auto_switch_weekly_plan(rows, week_start, week_end)
    _save_auto_switch_weekly_plan(owner_username, alias, week_start, plan)
    for day_key, day_plan in (plan.get("days") or {}).items():
        if isinstance(day_plan, dict):
            _save_auto_switch_daily_plan(owner_username, alias, str(day_key), day_plan)
    orders = [
        _slot_username_order(day_plan)
        for day_plan in (plan.get("days") or {}).values()
        if isinstance(day_plan, dict)
    ]
    distinct_days = len(set(orders))
    _auto_switch_log(
        f"已生成本周随机时段（7天，{distinct_days}种排班） | owner={owner_username} alias={alias} "
        f"week={week_start}~{week_end}"
    )
    return plan


def _daily_plan_for_schedule_date(
    weekly_plan: dict[str, Any],
    schedule_date: str,
) -> dict[str, Any] | None:
    days = weekly_plan.get("days") if isinstance(weekly_plan.get("days"), dict) else {}
    day_plan = days.get(str(schedule_date or "").strip())
    return day_plan if isinstance(day_plan, dict) else None


def _weekly_plan_for_bundle(weekly_plan: dict[str, Any]) -> dict[str, Any]:
    """设备端 Gson 友好：附带 day_plans 列表（含 7 天每日计划）。"""
    out = dict(weekly_plan)
    days = out.get("days") if isinstance(out.get("days"), dict) else {}
    out["day_plans"] = [
        {"schedule_date": date_key, "daily_plan": day_plan}
        for date_key, day_plan in sorted(days.items())
        if isinstance(day_plan, dict)
    ]
    return out


def _load_auto_switch_daily_plan(owner_username: str, alias: str, schedule_date: str) -> dict[str, Any] | None:
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    date_key = str(schedule_date or "").strip()
    if not owner or not alias_key or not date_key:
        return None
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT plan_json FROM auto_switch_daily_plans
            WHERE owner_username = ? AND schedule_date = ? AND alias = ?
            """,
            (owner, date_key, alias_key),
        ).fetchone()
    finally:
        conn.close()
    if not row or not row[0]:
        return None
    try:
        plan = json.loads(row[0])
        return plan if isinstance(plan, dict) else None
    except Exception:
        return None


def _save_auto_switch_daily_plan(owner_username: str, alias: str, schedule_date: str, plan: dict[str, Any]) -> None:
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    date_key = str(schedule_date or "").strip()
    if not owner or not alias_key or not date_key:
        return
    conn = _get_db()
    try:
        conn.execute(
            """
            INSERT OR REPLACE INTO auto_switch_daily_plans
            (owner_username, schedule_date, alias, plan_json, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (owner, date_key, alias_key, json.dumps(plan, ensure_ascii=False), now_text()),
        )
        conn.commit()
    finally:
        conn.close()


def _ensure_auto_switch_daily_plan(owner_username: str, alias: str, rows: List[dict[str, Any]], schedule_date: str) -> dict[str, Any]:
    """单日计划：优先从本周周计划取当天，否则按日生成（兼容旧逻辑）。"""
    date_key = str(schedule_date or "").strip() or _beijing_date_str()
    weekly = _ensure_auto_switch_weekly_plan(owner_username, alias, rows, date_key)
    day_plan = _daily_plan_for_schedule_date(weekly, date_key)
    if day_plan:
        return day_plan
    existing = _load_auto_switch_daily_plan(owner_username, alias, date_key)
    if existing and isinstance(existing.get("slots"), list) and len(existing["slots"]) == len(rows):
        if _ensure_slot_timed_insertions_in_plan(existing):
            _save_auto_switch_daily_plan(owner_username, alias, date_key, existing)
            _auto_switch_log(
                f"已补齐今日发作品/互点随机时间点 | owner={owner_username} alias={alias} date={date_key}"
            )
        return existing
    plan = _generate_auto_switch_daily_plan(rows)
    _save_auto_switch_daily_plan(owner_username, alias, date_key, plan)
    slot_desc = ", ".join(
        _auto_switch_slot_plan_log_line(s) for s in plan.get("slots") or []
    )
    _auto_switch_log(
        f"已生成今日随机时段 | owner={owner_username} alias={alias} date={date_key} | {slot_desc}"
    )
    return plan


def _auto_switch_slot_plan_log_line(slot: dict[str, Any]) -> str:
    user = str(slot.get("username") or "-")
    start = int(slot.get("start_min") or 0)
    end = int(slot.get("end_min") or 0)
    pub = slot.get("auto_publish_minute")
    range_text = f"{start // 60:02d}:{start % 60:02d}-{end // 60:02d}:{end % 60:02d}"
    if pub is None:
        pub_text = "发作品=跳过"
    else:
        try:
            pub_text = f"发作品@{_format_beijing_minute(int(pub))}"
        except (TypeError, ValueError):
            pub_text = "发作品=跳过"
    return f"{user}[{range_text},{pub_text}]"


def _format_beijing_slot_range(start_min: int, end_min: int) -> str:
    def fmt(m: int) -> str:
        clamped = max(0, min(int(m), 1440))
        h, mi = divmod(clamped, 60)
        return f"{h:02d}:{mi:02d}"

    start = max(0, int(start_min))
    end = max(start, int(end_min))
    if end >= 1440:
        return f"{fmt(start)}-24:00"
    return f"{fmt(start)}-{fmt(end)}"


def _auto_switch_plans_view_for_owner(
    owner_username: str,
    schedule_date: str | None = None,
    *,
    ensure: bool = True,
) -> dict[str, Any]:
    owner = str(owner_username or "").strip()
    date_key = str(schedule_date or "").strip() or _beijing_date_str()
    week_start, week_end = _beijing_week_bounds(date_key)
    if not owner:
        return {
            "schedule_date": date_key,
            "week_start_date": week_start,
            "week_end_date": week_end,
            "plans_by_alias": {},
        }
    buckets = _list_device_editor_buckets(owner)
    plans_by_alias: Dict[str, Any] = {}
    for alias_key, rows in buckets.items():
        if len(rows) < 2:
            continue
        if ensure:
            weekly = _ensure_auto_switch_weekly_plan(owner, alias_key, rows, date_key)
            plan = _daily_plan_for_schedule_date(weekly, date_key) or _ensure_auto_switch_daily_plan(
                owner, alias_key, rows, date_key
            )
        else:
            weekly = _load_auto_switch_weekly_plan(owner, alias_key, week_start)
            plan = _daily_plan_for_schedule_date(weekly or {}, date_key) if weekly else None
            if not plan:
                plan = _load_auto_switch_daily_plan(owner, alias_key, date_key)
            if not plan or not isinstance(plan.get("slots"), list):
                continue
        slots_by_row_index: Dict[str, dict[str, Any]] = {}
        slots_by_username: Dict[str, dict[str, Any]] = {}
        for slot in plan.get("slots") if isinstance(plan.get("slots"), list) else []:
            try:
                row_index = int(slot.get("row_index") or -1)
            except (TypeError, ValueError):
                row_index = -1
            try:
                start = int(slot.get("start_min") or 0)
                end = int(slot.get("end_min") or 0)
            except (TypeError, ValueError):
                start, end = 0, 0
            pub_min = slot.get("auto_publish_minute")
            pub_label = ""
            if pub_min is not None:
                try:
                    pub_label = _format_beijing_minute(int(pub_min))
                except (TypeError, ValueError):
                    pub_label = ""
            slot_index = int(slot.get("slot_index") or 0)
            entry = {
                "slot_index": slot_index,
                "start_min": start,
                "end_min": end,
                "label": _format_beijing_slot_range(start, end),
                "username": str(slot.get("username") or "").strip(),
                "auto_publish_minute": pub_min,
                "auto_publish_label": pub_label,
            }
            entry.update(
                _auto_switch_slot_publish_status_view(
                    owner,
                    alias_key,
                    date_key,
                    slot_index,
                    pub_min,
                    start,
                    end,
                )
            )
            if row_index >= 0:
                slots_by_row_index[str(row_index)] = entry
            user_key = str(slot.get("username") or "").strip().lower()
            if user_key:
                slots_by_username[user_key] = entry
        plans_by_alias[alias_key] = {
            "account_count": len(rows),
            "slot_minutes": plan.get("slot_minutes"),
            "slots_by_row_index": slots_by_row_index,
            "slots_by_username": slots_by_username,
        }
    return {
        "schedule_date": date_key,
        "week_start_date": week_start,
        "week_end_date": week_end,
        "plans_by_alias": plans_by_alias,
    }


def _auto_switch_slot_index_at_minute(plan: dict[str, Any], minute_of_day: int) -> int:
    slots = plan.get("slots") if isinstance(plan.get("slots"), list) else []
    if not slots:
        return 0
    minute = max(0, min(int(minute_of_day), 1439))
    for slot in slots:
        try:
            start = int(slot.get("start_min") or 0)
        except (TypeError, ValueError):
            start = 0
        end = _auto_switch_slot_end_min(slot, slots)
        if start <= minute < end:
            return int(slot.get("slot_index") or 0)
    return int(slots[-1].get("slot_index") or len(slots) - 1)


def _auto_switch_target_row(plan: dict[str, Any], minute_of_day: int) -> dict[str, Any] | None:
    slots = plan.get("slots") if isinstance(plan.get("slots"), list) else []
    if not slots:
        return None
    idx = _auto_switch_slot_index_at_minute(plan, minute_of_day)
    for slot in slots:
        if int(slot.get("slot_index") or -1) == idx:
            return slot
    return slots[min(idx, len(slots) - 1)]


def _auto_switch_highlighted_row(
    rows: List[dict[str, Any]],
    owner_username: str = "",
    alias: str = "",
    schedule_date: str | None = None,
    minute_of_day: int | None = None,
) -> dict[str, Any] | None:
    return _pick_device_editor_bucket_row(
        rows, owner_username, alias, schedule_date, minute_of_day,
    )


def _resolve_device_id_for_owner_alias(owner_username: str, alias: str, hint_device_id: str = "") -> str | None:
    alias_key = _normalize_alias_key(alias)
    owner = str(owner_username or "").strip()
    if not owner or not alias_key:
        return None
    hint = str(hint_device_id or "").strip()
    with store.lock:
        if hint:
            dev = store.devices.get(hint)
            if dev and dev.owner_username == owner and _normalize_alias_key(dev.alias) == alias_key:
                return hint
        for did, dev in store.devices.items():
            if dev.owner_username == owner and _normalize_alias_key(dev.alias) == alias_key:
                return did
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT device_id FROM client_devices
            WHERE owner_username = ? AND LOWER(TRIM(alias)) = LOWER(TRIM(?))
            ORDER BY updated_at DESC
            LIMIT 1
            """,
            (owner, alias_key),
        ).fetchone()
    finally:
        conn.close()
    if row and str(row[0] or "").strip():
        return str(row[0]).strip()
    return None


def _device_has_blocking_tiktok_ops(device_id: str) -> bool:
    did = str(device_id or "").strip()
    if not did:
        return False
    blocking = {
        "tiktok_backup_and_clear",
        "tiktok_restore",
        "tiktok_clear",
    }
    with store.lock:
        for op in store.pending_device_ops.get(did) or []:
            if str(op.get("op_type") or "") in blocking:
                return True
    return False


def _device_has_pending_publish_post(device_id: str) -> bool:
    did = str(device_id or "").strip()
    if not did:
        return False
    with store.lock:
        for op in store.pending_device_ops.get(did) or []:
            if str(op.get("op_type") or "") == "publish_post_start":
                return True
    return False


def _auto_switch_publish_done_key(slot_key: str) -> str:
    return f"{slot_key}|publish"


def _auto_switch_publish_aborted_key(slot_key: str) -> str:
    return f"{slot_key}|publish_aborted"


def _auto_switch_publish_inflight_value(slot_key: str, op_id: str) -> str:
    return f"{slot_key}|{op_id}"


def _parse_auto_switch_publish_inflight(value: str) -> tuple[str, str]:
    text = str(value or "").strip()
    if "|" not in text:
        return "", ""
    slot_key, op_id = text.rsplit("|", 1)
    return slot_key.strip(), op_id.strip()


def _auto_switch_slot_end_minute(target_slot: dict[str, Any]) -> int:
    try:
        return int(target_slot.get("end_min") or 0)
    except (TypeError, ValueError):
        return 0


def _auto_switch_slot_publish_status_view(
    owner_username: str,
    alias_key: str,
    schedule_date: str,
    slot_index: int,
    pub_min: object,
    start_min: int,
    end_min: int,
) -> dict[str, Any]:
    """管理台展示：本时段自动发作品状态（未到时间/执行中/重试中/已成功/已放弃/无计划/时段已过）。"""
    slot_key = f"{schedule_date}|{int(slot_index)}"
    bucket_key = _auto_switch_bucket_key(owner_username, alias_key)
    done_key = _auto_switch_publish_done_key(slot_key)
    abort_key = _auto_switch_publish_aborted_key(slot_key)
    minute = _beijing_minute_of_day()

    if pub_min is None:
        return {
            "auto_publish_status": "none",
            "auto_publish_status_label": "无发作品计划",
            "auto_publish_status_title": "该时段可用窗口不足（<21 分钟），系统未安排自动发作品时间点",
        }

    try:
        pub_minute = int(pub_min)
        pub_label = _format_beijing_minute(pub_minute)
    except (TypeError, ValueError):
        return {
            "auto_publish_status": "none",
            "auto_publish_status_label": "无发作品计划",
            "auto_publish_status_title": "发作品时间点无效",
        }

    with store.lock:
        done = str(store.auto_switch_publish_done_keys.get(bucket_key) or "") == done_key
        aborted = str(store.auto_switch_publish_aborted_keys.get(bucket_key) or "") == abort_key
        inflight_raw = str(store.auto_switch_publish_inflight_keys.get(bucket_key) or "")
    inflight_slot, inflight_op = _parse_auto_switch_publish_inflight(inflight_raw)
    inflight = inflight_slot == slot_key

    if done:
        return {
            "auto_publish_status": "done",
            "auto_publish_status_label": "已成功",
            "auto_publish_status_title": f"本时段已在 {pub_label} 起自动发作品并成功完成",
        }
    if aborted:
        return {
            "auto_publish_status": "aborted",
            "auto_publish_status_label": "已放弃",
            "auto_publish_status_title": (
                f"本时段在 {pub_label} 起曾尝试自动发作品，但因账号未登录成功或时段已切换，已停止重试"
            ),
        }
    if inflight:
        op_hint = f"（op_id={inflight_op}）" if inflight_op else ""
        return {
            "auto_publish_status": "inflight",
            "auto_publish_status_label": "执行中",
            "auto_publish_status_title": f"设备正在执行自动发作品任务{op_hint}",
        }
    if minute < pub_minute:
        return {
            "auto_publish_status": "pending",
            "auto_publish_status_label": "未到时间",
            "auto_publish_status_title": f"计划于北京时间 {pub_label} 开始自动发作品（{_format_beijing_slot_range(start_min, end_min)}）",
        }
    if minute >= int(end_min):
        return {
            "auto_publish_status": "missed",
            "auto_publish_status_label": "时段已过",
            "auto_publish_status_title": (
                f"本时段（{_format_beijing_slot_range(start_min, end_min)}）已结束，自动发作品未成功完成"
            ),
        }
    return {
        "auto_publish_status": "retrying",
        "auto_publish_status_label": "重试中",
        "auto_publish_status_title": (
            f"已于 {pub_label} 触发，本时段内将持续重试直至成功或时段结束（当前在 {_format_beijing_slot_range(start_min, end_min)} 内）"
        ),
    }


def _auto_switch_refresh_publish_slot_state(
    bucket_key: str,
    slot_key: str,
    minute_of_day: int,
    end_min: int,
) -> None:
    """时段切换或时段结束后清理过期的 inflight / aborted 标记，避免阻塞下一时段。"""
    with store.lock:
        inflight = store.auto_switch_publish_inflight_keys.get(bucket_key)
        if inflight:
            inflight_slot, _ = _parse_auto_switch_publish_inflight(str(inflight))
            if inflight_slot != slot_key or int(minute_of_day) >= int(end_min):
                store.auto_switch_publish_inflight_keys.pop(bucket_key, None)
        aborted = str(store.auto_switch_publish_aborted_keys.get(bucket_key) or "")
        if aborted:
            aborted_slot = aborted.replace("|publish_aborted", "")
            if aborted_slot != slot_key:
                store.auto_switch_publish_aborted_keys.pop(bucket_key, None)


def _auto_switch_apply_publish_outcome_report(
    owner_username: str,
    alias: str,
    status: str,
    data: dict[str, Any],
) -> None:
    """设备回传发作品最终结果：成功才记 publish_done；未登录/时段切换记 aborted 并停止本时段重试。"""
    if str(data.get("op_type") or "") != "publish_post_start":
        return
    if not bool(data.get("publish_post_outcome_report")):
        return
    if str(data.get("source") or "").strip() != "auto_switch":
        return
    slot_key = str(data.get("auto_switch_slot_key") or "").strip()
    if not slot_key:
        return
    bucket_key = _auto_switch_bucket_key(owner_username, alias)
    done_key = _auto_switch_publish_done_key(slot_key)
    abort_key = _auto_switch_publish_aborted_key(slot_key)
    account = str(data.get("account_username") or "").strip()
    login_failed = bool(data.get("auto_switch_publish_login_failed"))
    account_changed = bool(data.get("auto_switch_publish_account_changed"))
    completed = bool(data.get("publish_post_completed")) and str(status or "").strip().lower() == "success"

    with store.lock:
        store.auto_switch_publish_inflight_keys.pop(bucket_key, None)
        if login_failed or account_changed:
            store.auto_switch_publish_aborted_keys[bucket_key] = abort_key
        elif completed:
            store.auto_switch_publish_done_keys[bucket_key] = done_key

    if login_failed or account_changed:
        reason = "时段已切换" if account_changed else "账号未登录成功"
        persist_runtime_state()
        _auto_switch_log(
            f"本时段自动发作品已停止重试（{reason}）| owner={owner_username} alias={alias} "
            f"slot={slot_key} account={account or '-'}"
        )
        return
    if completed:
        persist_runtime_state()
        _auto_switch_log(
            f"自动发作品已成功，本时段标记完成 | owner={owner_username} alias={alias} "
            f"slot={slot_key} account={account or '-'}"
        )
        return
    _auto_switch_log(
        f"自动发作品未完成，将在本时段内继续重试 | owner={owner_username} alias={alias} "
        f"slot={slot_key} account={account or '-'}"
    )


def _auto_switch_enqueue_publish(
    owner_username: str,
    alias: str,
    device_id: str,
    account_row: dict[str, Any],
    publish_count: int,
    schedule_date: str,
    slot_key: str,
    publish_minute: int,
) -> str | None:
    alias_clean = _normalize_alias_key(alias)
    did = str(device_id or "").strip()
    if not did or not alias_clean:
        return None
    count = max(1, min(int(publish_count), 5))

    _resume_device_for_cloud_op(
        did,
        f"[{now_text()}] 自动切号发作品 | 已下发（alias={alias_clean} publish_count={count} "
        f"北京时间 {_format_beijing_minute(publish_minute)}；素材就绪后设备端再暂停涨粉）",
    )
    op_id = _enqueue_tiktok_device_op(
        did,
        "publish_post_start",
        {
            "device_id": did,
            "alias": alias_clean,
            "package_name": TIKTOK_PACKAGE_NAME,
            "publish_count": count,
            "source": "auto_switch",
            "auto_switch_schedule_date": schedule_date,
            "auto_switch_slot_key": slot_key,
            "account_username": str(account_row.get("username") or "").strip(),
        },
    )
    _append_device_log(
        did,
        f"[{now_text()}] 自动切号发作品 | op_id={op_id} publish_count={count} "
        f"account={str(account_row.get('username') or '').strip() or '-'}",
    )
    return op_id


def _auto_switch_maybe_enqueue_publish(
    owner_username: str,
    alias: str,
    device_id: str,
    target_slot: dict[str, Any],
    target_row: dict[str, Any],
    schedule_date: str,
    slot_key: str,
    bucket_key: str,
    minute_of_day: int,
) -> None:
    if not _auto_switch_enabled_now():
        return
    pub_min = target_slot.get("auto_publish_minute")
    if pub_min is None:
        return
    try:
        pub_minute = int(pub_min)
    except (TypeError, ValueError):
        return

    minute = int(minute_of_day)
    end_min = _auto_switch_slot_end_minute(target_slot)
    if minute < pub_minute:
        return
    if minute >= end_min:
        return

    _auto_switch_refresh_publish_slot_state(bucket_key, slot_key, minute, end_min)

    done_key = _auto_switch_publish_done_key(slot_key)
    abort_key = _auto_switch_publish_aborted_key(slot_key)
    with store.lock:
        if str(store.auto_switch_publish_done_keys.get(bucket_key) or "") == done_key:
            return
        if str(store.auto_switch_publish_aborted_keys.get(bucket_key) or "") == abort_key:
            return
        if bucket_key in store.auto_switch_publish_inflight_keys:
            return

    if _device_has_pending_publish_post(device_id):
        return

    target_user = str(target_row.get("username") or "").strip()
    if target_user:
        conn = _get_db()
        try:
            alias_err = _validate_username_device_effective_alias(
                conn, owner_username, target_user, alias
            )
        finally:
            conn.close()
        if alias_err:
            _auto_switch_log(
                f"跳过自动发作品：账号分配别名不一致 | owner={owner_username} alias={alias} "
                f"account={target_user} | {alias_err}"
            )
            return

    if _device_has_blocking_tiktok_ops(device_id):
        _auto_switch_log(
            f"自动发作品暂缓：切号任务排队中，下分钟重试 | device={device_id} alias={alias} slot={slot_key}"
        )
        return

    raw_video_count = _parse_video_count_raw(target_row.get("video_count"))
    if raw_video_count == NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN:
        _auto_switch_skip_publish_new_account_cooldown(
            owner_username,
            alias,
            target_row,
            slot_key,
            bucket_key,
            pub_minute,
        )
        return

    video_count = max(0, int(raw_video_count or 0))
    publish_count = _auto_publish_count_for_video_count(video_count)
    op_id = _auto_switch_enqueue_publish(
        owner_username,
        alias,
        device_id,
        target_row,
        publish_count,
        schedule_date,
        slot_key,
        pub_minute,
    )
    if op_id:
        with store.lock:
            store.auto_switch_publish_inflight_keys[bucket_key] = _auto_switch_publish_inflight_value(
                slot_key, op_id
            )
        persist_runtime_state()
        _auto_switch_log(
            f"已下发自动发作品 | owner={owner_username} alias={alias} slot={slot_key} "
            f"time={_format_beijing_minute(pub_minute)} video_count={video_count} "
            f"publish_count={publish_count} account={str(target_row.get('username') or '')} op_id={op_id}"
        )


def _device_op_payload(op: dict[str, Any]) -> dict[str, Any]:
    raw = op.get("payload")
    return raw if isinstance(raw, dict) else {}


def _is_pending_auto_switch_device_op(op: dict[str, Any]) -> bool:
    return str(_device_op_payload(op).get("source") or "").strip() == "auto_switch"


def _cancel_pending_auto_switch_device_ops() -> int:
    """关闭自动切号时撤销队列中尚未被设备拉取的自动切号/自动发作品任务。"""
    rollback_items: list[tuple[str, dict[str, Any]]] = []
    removed_op_ids: set[str] = set()
    removed = 0
    with store.lock:
        for device_id, queue in list(store.pending_device_ops.items()):
            if not queue:
                continue
            owner = ""
            dev = store.devices.get(device_id)
            if dev:
                owner = str(dev.owner_username or "").strip()
            kept: list[dict[str, Any]] = []
            for op in queue:
                if not _is_pending_auto_switch_device_op(op):
                    kept.append(op)
                    continue
                removed += 1
                op_id = str(op.get("op_id") or "").strip()
                if op_id:
                    removed_op_ids.add(op_id)
                payload = _device_op_payload(op)
                op_type = str(op.get("op_type") or "")
                if owner and op_type in (
                    "tiktok_backup_and_clear",
                    "tiktok_restore",
                    "tiktok_clear",
                ):
                    if _resolve_cut_switch_source_row_index(payload) >= 0:
                        rollback_items.append((owner, dict(payload)))
            if kept:
                store.pending_device_ops[device_id] = kept
            else:
                store.pending_device_ops.pop(device_id, None)

        if removed_op_ids:
            for bucket_key, inflight in list(store.auto_switch_publish_inflight_keys.items()):
                _slot, op_id = _parse_auto_switch_publish_inflight(str(inflight))
                if op_id in removed_op_ids:
                    store.auto_switch_publish_inflight_keys.pop(bucket_key, None)

    seen_rollback: set[tuple[str, int]] = set()
    for owner, data in rollback_items:
        src = _resolve_cut_switch_source_row_index(data)
        key = (owner, src)
        if src < 0 or key in seen_rollback:
            continue
        seen_rollback.add(key)
        _device_ops_rollback_cut_switch_highlight(owner, data)

    if removed:
        persist_runtime_state()
    return removed


def _auto_switch_enqueue_cut_switch(
    owner_username: str,
    alias: str,
    device_id: str,
    source_row: dict[str, Any],
    target_row: dict[str, Any],
) -> bool:
    if not _device_auto_switch_enabled(device_id):
        return False
    alias_clean = _normalize_alias_key(alias)
    did = str(device_id or "").strip()
    if not did or not alias_clean:
        return False
    try:
        source_ri = int(source_row.get("row_index") or -1)
        target_ri = int(target_row.get("row_index") or -1)
    except (TypeError, ValueError):
        return False
    if source_ri < 0 or target_ri < 0 or source_ri == target_ri:
        return False

    conn = _get_db()
    try:
        for label, row in (("源", source_row), ("目标", target_row)):
            user = str(row.get("username") or "").strip()
            if not user:
                _auto_switch_log(
                    f"跳过切号：{label}账号用户名为空 | owner={owner_username} alias={alias_clean}"
                )
                return False
            alias_err = _validate_username_device_effective_alias(conn, owner_username, user, alias_clean)
            if alias_err:
                _auto_switch_log(
                    f"跳过切号：{label}账号分配别名不一致 | owner={owner_username} alias={alias_clean} | {alias_err}"
                )
                return False
    finally:
        conn.close()

    _apply_cut_switch_highlight_intent(owner_username, target_ri)

    target_backup = str(target_row.get("backup_name") or "").strip()
    can_restore = bool(target_backup and target_backup != "-")
    source_user = str(source_row.get("username") or "").strip()
    target_user = str(target_row.get("username") or "").strip()

    auto_start_disabled = False
    with store.lock:
        dev = store.devices.get(did)
        if dev and not dev.auto_start_opt_out:
            dev.auto_start_opt_out = True
            auto_start_disabled = True
    if auto_start_disabled:
        _persist_client_device_to_db(did)
        _append_device_log(did, "自动切号：云端已自动关闭该设备的自动开启程序")

    prev_path = _fetch_previous_backup_path_for_backup_op(
        owner_username,
        source_ri if source_ri > 0 else None,
        did,
        source_user,
    )
    backup_payload: dict[str, Any] = {
        "device_id": did,
        "alias": alias_clean,
        "package_name": TIKTOK_PACKAGE_NAME,
        "backup_record_id": source_ri,
        "username": source_user,
        "source": "auto_switch",
    }
    if prev_path:
        backup_payload["previous_backup_path"] = prev_path
    if can_restore:
        backup_payload["cut_switch_after_backup"] = "clear_source_for_restore"
        backup_payload["cut_switch_target_row_index"] = target_ri
    else:
        backup_payload["cut_switch_after_backup"] = "exclusive_activate_target"
        backup_payload["cut_switch_target_row_index"] = target_ri

    _resume_device_for_cloud_op(
        did,
        f"[{now_text()}] 自动切号 | 已入队备份并清理（alias={alias_clean} → {target_user or target_ri}）",
    )
    backup_op_id = _enqueue_tiktok_device_op(did, "tiktok_backup_and_clear", backup_payload)
    _append_device_log(
        did,
        f"[{now_text()}] 自动切号 | op_id={backup_op_id} backup source={source_user or source_ri}",
    )

    if can_restore:
        backup_name = Path(target_backup).name
        restore_payload: dict[str, Any] = {
            "device_id": did,
            "alias": alias_clean,
            "package_name": TIKTOK_PACKAGE_NAME,
            "backup_name": backup_name,
            "backup_path": str(target_row.get("backup_path") or "").strip() or backup_name,
            "username": target_user,
            "mobile_alias": alias_clean,
            "assigned_alias": str(target_row.get("assigned_alias") or "").strip() or alias_clean,
            "skip_pm_clear": True,
            "restore_highlight_row_index": target_ri,
            "backup_record_id": source_ri,
            "source": "auto_switch",
        }
        _enqueue_tiktok_device_op(did, "tiktok_restore", restore_payload)
        _append_device_log(
            did,
            f"[{now_text()}] 自动切号 | 已入队还原 target={target_user or target_ri} backup={backup_name}",
        )
    return True


def _auto_switch_enqueue_bootstrap_cut_switch(
    owner_username: str,
    alias: str,
    device_id: str,
    target_row: dict[str, Any],
) -> bool:
    """无切号高亮的多账号桶：仅清理 TikTok 后还原目标（不备份源），对齐到当前时段账号。"""
    if not _device_auto_switch_enabled(device_id):
        return False
    alias_clean = _normalize_alias_key(alias)
    did = str(device_id or "").strip()
    if not did or not alias_clean:
        return False
    try:
        target_ri = int(target_row.get("row_index") or -1)
    except (TypeError, ValueError):
        return False
    if target_ri < 0:
        return False

    conn = _get_db()
    try:
        target_user = str(target_row.get("username") or "").strip()
        if not target_user:
            _auto_switch_log(
                f"跳过初始对齐：目标账号用户名为空 | owner={owner_username} alias={alias_clean}"
            )
            return False
        alias_err = _validate_username_device_effective_alias(
            conn, owner_username, target_user, alias_clean
        )
        if alias_err:
            _auto_switch_log(
                f"跳过初始对齐：目标账号分配别名不一致 | owner={owner_username} alias={alias_clean} | {alias_err}"
            )
            return False
    finally:
        conn.close()

    _apply_cut_switch_highlight_intent(owner_username, target_ri)

    target_backup = str(target_row.get("backup_name") or "").strip()
    can_restore = bool(target_backup and target_backup != "-")
    cut_mode = "clear_source_for_restore" if can_restore else "exclusive_activate_target"

    auto_start_disabled = False
    with store.lock:
        dev = store.devices.get(did)
        if dev and not dev.auto_start_opt_out:
            dev.auto_start_opt_out = True
            auto_start_disabled = True
    if auto_start_disabled:
        _persist_client_device_to_db(did)
        _append_device_log(did, "自动切号：云端已自动关闭该设备的自动开启程序")

    clear_payload: dict[str, Any] = {
        "device_id": did,
        "alias": alias_clean,
        "package_name": TIKTOK_PACKAGE_NAME,
        "cut_switch_after_backup": cut_mode,
        "cut_switch_target_row_index": target_ri,
        "source": "auto_switch",
    }
    _resume_device_for_cloud_op(
        did,
        f"[{now_text()}] 自动切号 | 无高亮桶初始对齐：已入队清理（alias={alias_clean} → {target_user or target_ri}）",
    )
    clear_op_id = _enqueue_tiktok_device_op(did, "tiktok_clear", clear_payload)
    _append_device_log(
        did,
        f"[{now_text()}] 自动切号 | op_id={clear_op_id} 初始对齐 clear target={target_user or target_ri}",
    )

    if can_restore:
        backup_name = Path(target_backup).name
        restore_payload: dict[str, Any] = {
            "device_id": did,
            "alias": alias_clean,
            "package_name": TIKTOK_PACKAGE_NAME,
            "backup_name": backup_name,
            "backup_path": str(target_row.get("backup_path") or "").strip() or backup_name,
            "username": target_user,
            "mobile_alias": alias_clean,
            "assigned_alias": str(target_row.get("assigned_alias") or "").strip() or alias_clean,
            "skip_pm_clear": True,
            "restore_highlight_row_index": target_ri,
            "source": "auto_switch",
        }
        _enqueue_tiktok_device_op(did, "tiktok_restore", restore_payload)
        _append_device_log(
            did,
            f"[{now_text()}] 自动切号 | 已入队还原 target={target_user or target_ri} backup={backup_name}",
        )
    return True


def _auto_switch_list_owners_with_buckets() -> List[str]:
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT DISTINCT owner_username
            FROM device_editor_rows
            WHERE TRIM(owner_username) <> '' AND TRIM(username) <> ''
            """
        ).fetchall()
    finally:
        conn.close()
    return [str(r[0] or "").strip() for r in rows or [] if str(r[0] or "").strip()]


def _auto_switch_tick() -> None:
    # 已开启自动切号的设备由本机 bundle + 本地 scheduler 自治；云端 minute tick 不再入队。
    if _any_device_auto_switch_enabled():
        return
    tick_started = time.perf_counter()
    with store.lock:
        align_now = bool(store.auto_switch_align_on_next_tick)
        if align_now:
            store.auto_switch_align_on_next_tick = False

    schedule_date = _beijing_date_str()
    minute_of_day = _beijing_minute_of_day()
    owners = _auto_switch_list_owners_with_buckets()
    state_dirty = False
    switch_ops_this_tick = 0
    align_deferred = False

    for owner in owners:
        if not _auto_switch_enabled_now():
            return
        buckets = _list_device_editor_buckets(owner)
        for alias_key, rows in buckets.items():
            if not _auto_switch_enabled_now():
                return
            plan = _ensure_auto_switch_daily_plan(owner, alias_key, rows, schedule_date)
            target_slot = _auto_switch_target_row(plan, minute_of_day)
            if not target_slot:
                continue
            try:
                target_ri = int(target_slot.get("row_index") or -1)
            except (TypeError, ValueError):
                continue
            if target_ri < 0:
                continue

            target_row = next((r for r in rows if int(r.get("row_index") or -1) == target_ri), None)
            if not target_row:
                continue

            slot_index = _auto_switch_slot_index_at_minute(plan, minute_of_day)
            slot_key = f"{schedule_date}|{slot_index}"
            bucket_key = _auto_switch_bucket_key(owner, alias_key)

            highlighted = _auto_switch_highlighted_row(
                rows, owner, alias_key, schedule_date, minute_of_day,
            )
            device_active = next(
                (
                    r for r in rows
                    if str(r.get("env_status") or "").strip() == ENV_STATUS_ACTIVE
                ),
                None,
            )
            if device_active is None and len(rows) == 1:
                device_active = rows[0]
            try:
                current_ri = int(device_active.get("row_index") or -1) if device_active else -1
            except (TypeError, ValueError):
                current_ri = -1

            if current_ri == target_ri:
                with store.lock:
                    store.auto_switch_last_slot_keys[bucket_key] = slot_key
                device_id_active = _resolve_device_id_for_owner_alias(
                    owner,
                    alias_key,
                    str((highlighted or target_row).get("device_id") or ""),
                )
                if device_id_active:
                    _auto_switch_maybe_enqueue_publish(
                        owner_username=owner,
                        alias=alias_key,
                        device_id=device_id_active,
                        target_slot=target_slot,
                        target_row=target_row,
                        schedule_date=schedule_date,
                        slot_key=slot_key,
                        bucket_key=bucket_key,
                        minute_of_day=minute_of_day,
                    )
                continue

            with store.lock:
                last_key = str(store.auto_switch_last_slot_keys.get(bucket_key) or "")
            if last_key == slot_key and not align_now:
                continue

            device_id = _resolve_device_id_for_owner_alias(
                owner,
                alias_key,
                str((highlighted or target_row).get("device_id") or ""),
            )
            if not device_id:
                _auto_switch_log(f"跳过：未找到在线绑定设备 owner={owner} alias={alias_key}")
                continue
            if _device_has_blocking_tiktok_ops(device_id):
                _auto_switch_log(f"跳过：设备任务队列未空闲 device={device_id} alias={alias_key}")
                continue

            if not device_active:
                if len(rows) <= 1:
                    _auto_switch_log(f"跳过：别名桶内无法解析设备当前账号 owner={owner} alias={alias_key}")
                    continue
                if not align_now:
                    continue
                if switch_ops_this_tick >= _AUTO_SWITCH_MAX_SWITCH_OPS_PER_TICK:
                    align_deferred = True
                    continue
                if not _auto_switch_enabled_now():
                    return
                ok = _auto_switch_enqueue_bootstrap_cut_switch(
                    owner, alias_key, device_id, target_row
                )
                if ok:
                    switch_ops_this_tick += 1
                    with store.lock:
                        store.auto_switch_last_slot_keys[bucket_key] = slot_key
                    state_dirty = True
                    _auto_switch_log(
                        f"无高亮桶初始对齐 | owner={owner} alias={alias_key} slot={slot_key} "
                        f"→ {str(target_row.get('username') or '')}"
                    )
                continue

            if switch_ops_this_tick >= _AUTO_SWITCH_MAX_SWITCH_OPS_PER_TICK:
                if align_now:
                    align_deferred = True
                continue

            if not _auto_switch_enabled_now():
                return

            ok = _auto_switch_enqueue_cut_switch(owner, alias_key, device_id, device_active, target_row)
            if ok:
                switch_ops_this_tick += 1
                with store.lock:
                    store.auto_switch_last_slot_keys[bucket_key] = slot_key
                state_dirty = True
                _auto_switch_log(
                    f"已下发切号 | owner={owner} alias={alias_key} slot={slot_key} "
                    f"{str(device_active.get('username') or '')} → {str(target_row.get('username') or '')}"
                )

    if align_deferred:
        with store.lock:
            store.auto_switch_align_on_next_tick = True
        state_dirty = True
        _auto_switch_log(
            f"自动切号对齐未完成（本 tick 已下发 {switch_ops_this_tick} 条，"
            f"上限 {_AUTO_SWITCH_MAX_SWITCH_OPS_PER_TICK}），下一轮继续"
        )

    if state_dirty:
        persist_runtime_state()

    elapsed_ms = (time.perf_counter() - tick_started) * 1000
    if elapsed_ms >= 1500:
        _auto_switch_log(f"本轮调度耗时 {elapsed_ms:.0f}ms（owners={len(owners)} switch_ops={switch_ops_this_tick}）")


def _auto_switch_worker_loop() -> None:
    _auto_switch_log("自动切号调度线程已启动（北京时间，每分钟检查）")
    # 重启后稍候再跑首轮调度，避免与首屏 HTTP / SQLite 恢复争抢写锁。
    if _interruptible_worker_sleep(8):
        return
    while not _APP_SHUTDOWN_EVENT.is_set():
        try:
            if _any_device_auto_switch_enabled():
                # 设备自治：bundle 已下发，由本机 scheduler 执行；云端 tick 仅低频保活。
                if _interruptible_worker_sleep(AUTO_SWITCH_AUTONOMOUS_IDLE_SEC):
                    break
                continue
            _auto_switch_tick()
        except Exception as exc:
            _auto_switch_log(f"调度异常: {exc}")
        if _interruptible_worker_sleep(AUTO_SWITCH_TICK_INTERVAL_SEC):
            break


def _start_auto_switch_worker() -> None:
    Thread(target=_auto_switch_worker_loop, name="auto_account_switch", daemon=True).start()


def _backup_stats_full_query_log(line: str) -> None:
    _append_task_log(BACKUP_STATS_FULL_QUERY_LOG_KEY, line)


def _list_distinct_backup_stats_owners() -> List[str]:
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT DISTINCT TRIM(owner_username) AS owner
            FROM device_editor_rows
            WHERE TRIM(owner_username) <> '' AND TRIM(username) <> ''
            ORDER BY owner ASC
            """
        ).fetchall()
        return [str(r[0] or "").strip() for r in rows if str(r[0] or "").strip()]
    finally:
        conn.close()


def _list_backup_archive_usernames_for_owner(owner_username: str) -> List[str]:
    owner = str(owner_username or "").strip()
    if not owner:
        return []
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT DISTINCT LOWER(TRIM(username)) AS uname
            FROM device_editor_rows
            WHERE owner_username = ? AND TRIM(username) <> ''
            ORDER BY uname ASC
            """,
            (owner,),
        ).fetchall()
        seen: set[str] = set()
        out: List[str] = []
        for row in rows:
            username = str(row[0] or "").strip().lstrip("@")
            if not username:
                continue
            key = username.lower()
            if key in seen:
                continue
            seen.add(key)
            out.append(username)
        return out
    finally:
        conn.close()


def _run_backup_stats_full_query_job() -> dict[str, int]:
    owners = _list_distinct_backup_stats_owners()
    if not owners:
        _backup_stats_full_query_log(f"[{now_text()}] 跳过：无备份档案账号")
        return {"owners": 0, "total": 0, "ok": 0, "fail": 0}

    total = 0
    ok = 0
    fail = 0
    _backup_stats_full_query_log(
        f"[{now_text()}] 开始全量查询并落库 | owners={len(owners)} | 北京时间={_beijing_now().strftime('%Y-%m-%d %H:%M:%S')}"
    )

    for owner in owners:
        usernames = _list_backup_archive_usernames_for_owner(owner)
        if not usernames:
            continue
        _backup_stats_full_query_log(f"[{now_text()}] owner={owner} | 待查询 {len(usernames)} 个账号")
        for idx, username in enumerate(usernames, start=1):
            total += 1
            try:
                result = fetch_tiktok_profile_stats(
                    username,
                    write_back=True,
                    query_index=idx,
                    query_total=len(usernames),
                )
                if isinstance(result, dict) and int(result.get("code", 1)) == 0:
                    ok += 1
                else:
                    fail += 1
                    msg = (result or {}).get("msg") if isinstance(result, dict) else "unknown"
                    _backup_stats_full_query_log(f"[{now_text()}] 失败 @{username} | {msg}")
            except Exception as exc:
                fail += 1
                _backup_stats_full_query_log(f"[{now_text()}] 异常 @{username} | {exc}")
            if idx < len(usernames):
                time.sleep(BACKUP_STATS_FULL_QUERY_REQUEST_GAP_SEC)

    _backup_stats_full_query_log(
        f"[{now_text()}] 全量查询完成 | 成功 {ok}/{total}，失败 {fail}"
    )
    return {"owners": len(owners), "total": total, "ok": ok, "fail": fail}


def _backup_stats_full_query_worker() -> None:
    today = _beijing_date_str()
    try:
        stats = _run_backup_stats_full_query_job()
        print(
            f"[{now_text()}] backup_stats_full_query | done | date={today} "
            f"owners={stats.get('owners', 0)} ok={stats.get('ok', 0)} "
            f"total={stats.get('total', 0)} fail={stats.get('fail', 0)}"
        )
    except Exception as exc:
        _backup_stats_full_query_log(f"[{now_text()}] 任务异常: {exc}")
        print(f"[{now_text()}] backup_stats_full_query | failed | {exc}")
    finally:
        with store.lock:
            store.backup_stats_full_query_in_flight = False
        persist_runtime_state()


def _backup_stats_full_query_tick() -> None:
    if not _backup_stats_full_query_schedule_available():
        return
    with store.lock:
        if not store.backup_stats_full_query_enabled:
            return
        if store.backup_stats_full_query_in_flight:
            return

    now = _beijing_now()
    if now.hour != 0 or now.minute >= 2:
        return

    today = _beijing_date_str(now)
    with store.lock:
        if store.backup_stats_full_query_last_run_date == today:
            return
        store.backup_stats_full_query_last_run_date = today
        store.backup_stats_full_query_in_flight = True
    persist_runtime_state()

    _backup_stats_full_query_log(
        f"[{now_text()}] 触发每日定时全量查询（北京时间 0:00）| date={today}"
    )
    Thread(
        target=_backup_stats_full_query_worker,
        name="backup_stats_full_query",
        daemon=True,
    ).start()


def _backup_stats_full_query_worker_loop() -> None:
    _backup_stats_full_query_log("每日全量查询调度线程已启动（北京时间 0:00 自动执行）")
    while not _APP_SHUTDOWN_EVENT.is_set():
        try:
            _backup_stats_full_query_tick()
        except Exception as exc:
            _backup_stats_full_query_log(f"调度异常: {exc}")
        if _interruptible_worker_sleep(BACKUP_STATS_FULL_QUERY_TICK_INTERVAL_SEC):
            break


def _start_backup_stats_full_query_worker() -> None:
    Thread(
        target=_backup_stats_full_query_worker_loop,
        name="backup_stats_full_query_scheduler",
        daemon=True,
    ).start()


def _mobile_pending_auto_refill_log(line: str) -> None:
    text = str(line or "").strip()
    if not text:
        return
    print(f"[{now_text()}] mobile_pending_auto_refill | {text}")


def _count_mobile_pending_pending() -> int:
    conn = _get_db()
    try:
        return int(
            conn.execute(
                "SELECT COUNT(1) FROM mobile_pending_usernames WHERE consume_status = 'pending'"
            ).fetchone()[0]
            or 0
        )
    finally:
        conn.close()


def _count_tiktok_roots() -> int:
    conn = _get_db()
    try:
        return int(conn.execute("SELECT COUNT(1) FROM tiktok_roots").fetchone()[0] or 0)
    finally:
        conn.close()


def _pull_growth_usernames_random_from_roots(conn: sqlite3.Connection, safe_count: int) -> List[str]:
    """涨粉临时方案：从 tiktok_roots 随机取 N 个根用户名，只读不写库。"""
    n = max(1, min(int(safe_count or 1), 100))
    rows = conn.execute(
        "SELECT root_username FROM tiktok_roots ORDER BY RANDOM() LIMIT ?",
        (n,),
    ).fetchall()
    return [str(r[0]).strip() for r in rows if str(r[0] or "").strip()]


def _refill_mobile_pending_from_all_roots() -> dict:
    """将全部 tiktok_roots 写入待下发队列（与后台「将所有根用户加入待下发队列」一致）。"""
    conn = _get_db()
    try:
        rows = conn.execute("SELECT root_username FROM tiktok_roots ORDER BY root_username ASC").fetchall()
        usernames = [str(r["root_username"] or "").strip() for r in rows]
    finally:
        conn.close()
    usernames = [u for u in usernames if u]
    if not usernames:
        return {"code": 1, "msg": "tiktok_roots 为空，无可下发用户"}
    result = _insert_mobile_pending_usernames(usernames, skip_privacy_filter=True)
    result["selected"] = len(usernames)
    return result


def _mobile_pending_auto_refill_worker(pending_before: int, threshold: int) -> None:
    try:
        _mobile_pending_auto_refill_log(
            f"[{now_text()}] 待下发 {pending_before} < 阈值 {threshold}，开始将全部根用户写入待下发队列..."
        )
        result = _refill_mobile_pending_from_all_roots()
        inserted = int(result.get("inserted") or result.get("count") or 0)
        selected = int(result.get("selected") or 0)
        pending_after = _count_mobile_pending_pending()
        msg = str(result.get("msg") or "")
        if int(result.get("code") or 0) != 0:
            _mobile_pending_auto_refill_log(f"[{now_text()}] 自动补货失败：{msg}")
        else:
            _mobile_pending_auto_refill_log(
                f"[{now_text()}] 自动补货完成：新增 {inserted} 条，根用户 {selected} 条，"
                f"待下发 {pending_before} → {pending_after}"
            )
    except Exception as exc:
        _mobile_pending_auto_refill_log(f"[{now_text()}] 自动补货异常：{exc}")
        print(f"[{now_text()}] mobile_pending_auto_refill | failed | {exc}")
    finally:
        with store.lock:
            store.mobile_pending_auto_refill_in_flight = False
            store.mobile_pending_auto_refill_last_run_ts = time.time()


def _mobile_pending_auto_refill_tick() -> None:
    # 临时方案：涨粉随机下发不写 mobile_pending，自动补队列无意义，整段逻辑先跳过。
    return
    threshold = MOBILE_PENDING_AUTO_REFILL_DEFAULT_THRESHOLD
    cooldown_sec = MOBILE_PENDING_AUTO_REFILL_DEFAULT_COOLDOWN_SEC
    with store.lock:
        if store.mobile_pending_auto_refill_in_flight:
            return
        last_ts = float(store.mobile_pending_auto_refill_last_run_ts or 0.0)
    if last_ts > 0 and (time.time() - last_ts) < cooldown_sec:
        return

    pending_count = _count_mobile_pending_pending()
    if pending_count >= threshold:
        return

    with store.lock:
        if store.mobile_pending_auto_refill_in_flight:
            return
        store.mobile_pending_auto_refill_in_flight = True

    Thread(
        target=_mobile_pending_auto_refill_worker,
        args=(pending_count, threshold),
        name="mobile_pending_auto_refill",
        daemon=True,
    ).start()


def _mobile_pending_auto_refill_worker_loop() -> None:
    _mobile_pending_auto_refill_log(
        f"调度已启动：待下发 < {MOBILE_PENDING_AUTO_REFILL_DEFAULT_THRESHOLD} 时自动将全部根用户加入待下发队列"
    )
    while not _APP_SHUTDOWN_EVENT.is_set():
        try:
            _mobile_pending_auto_refill_tick()
        except Exception as exc:
            _mobile_pending_auto_refill_log(f"调度异常: {exc}")
            print(f"[{now_text()}] mobile_pending_auto_refill | tick_exc | {exc}")
        if _interruptible_worker_sleep(MOBILE_PENDING_AUTO_REFILL_TICK_INTERVAL_SEC):
            break


def _start_mobile_pending_auto_refill_worker() -> None:
    Thread(
        target=_mobile_pending_auto_refill_worker_loop,
        name="mobile_pending_auto_refill_scheduler",
        daemon=True,
    ).start()


def _get_device_editor_record(username: str, mobile_alias: str | None = None):
    conn = _get_db()
    try:
        if mobile_alias:
            if username:
                row = conn.execute(
                    f"""
                    SELECT row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, updated_at
                    FROM device_editor_rows
                    WHERE username = ? AND {_backup_record_alias_expr()} = ?
                    ORDER BY updated_at DESC, row_index DESC
                    LIMIT 1
                    """,
                    (username, mobile_alias),
                ).fetchone()
            else:
                row = conn.execute(
                    f"""
                    SELECT row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, updated_at
                    FROM device_editor_rows
                    WHERE {_backup_record_alias_expr()} = ?
                    ORDER BY updated_at DESC, row_index DESC
                    LIMIT 1
                    """,
                    (mobile_alias,),
                ).fetchone()
        else:
            row = conn.execute(
                """
                SELECT row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, updated_at
                FROM device_editor_rows
                WHERE username = ?
                ORDER BY updated_at DESC, row_index DESC
                LIMIT 1
                """,
                (username,),
            ).fetchone()
        return dict(row) if row else None
    finally:
        conn.close()


def _get_backup_record_by_id(record_id: int) -> dict | None:
    if int(record_id or 0) <= 0:
        return None
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, updated_at
            FROM device_editor_rows
            WHERE row_index = ?
            """,
            (int(record_id),),
        ).fetchone()
        return dict(row) if row else None
    finally:
        conn.close()


def _get_latest_backup_record_for_alias(mobile_alias: str) -> dict | None:
    mobile_alias = _normalized_alias(mobile_alias)
    if not mobile_alias:
        return None
    return _get_device_editor_record("", mobile_alias)


def _compute_follower_growth_text(
    follower_count: int | None,
    new_follower_count: int | None = None,
    *,
    prev_follower_count: int | None = None,
) -> str:
    if isinstance(follower_count, int) and isinstance(new_follower_count, int):
        return str(max(follower_count - new_follower_count, 0))
    if isinstance(follower_count, int) and isinstance(prev_follower_count, int):
        return str(max(follower_count - prev_follower_count, 0))
    if isinstance(follower_count, int):
        return "0" if new_follower_count is not None else str(follower_count)
    return ""


def _sync_account_editor_stats(
    conn,
    username: str,
    *,
    owner_username: str = "",
    video_count: str | None = None,
    follower_count: str | None = None,
    follower_growth: str | None = None,
    now: str | None = None,
) -> int:
    """设备/统计回写后，将粉丝与视频数同步持久化到 account_editor_rows。"""
    uname = str(username or "").strip()
    if not uname:
        return 0
    sets: list[str] = []
    params: list[object] = []
    if video_count is not None:
        sets.append("video_count = ?")
        params.append(str(video_count))
    if follower_count is not None:
        sets.append("follower_count = ?")
        params.append(str(follower_count))
    if follower_growth is not None:
        sets.append("follower_growth = ?")
        params.append(str(follower_growth))
    if not sets:
        return 0
    now_val = now or now_text()
    sets.append("updated_at = ?")
    params.append(now_val)
    owner = str(owner_username or "").strip()
    if owner:
        where = "owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))"
        params.extend([owner, uname])
    else:
        where = "LOWER(TRIM(username)) = LOWER(TRIM(?))"
        params.append(uname)
    cur = conn.execute(
        f"UPDATE account_editor_rows SET {', '.join(sets)} WHERE {where}",
        tuple(params),
    )
    return int(cur.rowcount or 0)


def _patch_backup_record_device_result(
    username: str,
    mobile_alias: str,
    *,
    backup_path: str,
    backup_name: str | None,
    video_count: int | None,
    follower_count: int | None,
    new_follower_count: int | None,
) -> int:
    conn = _get_db()
    try:
        now = now_text()
        follower_growth = _compute_follower_growth_text(follower_count, new_follower_count)
        vc_text = None if video_count is None else str(video_count)
        fc_text = None if follower_count is None else str(follower_count)
        owner_row = conn.execute(
            f"""
            SELECT owner_username FROM device_editor_rows
            WHERE username = ? AND {_backup_record_alias_expr()} = ?
            LIMIT 1
            """,
            (username, mobile_alias),
        ).fetchone()
        set_parts = [
            "backup_path = ?",
            "backup_name = ?",
            "backup_time = ?",
            "backup_status = ?",
            "updated_at = ?",
        ]
        params: list[object] = [
            backup_path,
            backup_name or "",
            now,
            "已备份",
            now,
        ]
        if vc_text is not None:
            set_parts.append("video_count = ?")
            params.append(vc_text)
        if fc_text is not None:
            set_parts.append("follower_count = ?")
            params.append(fc_text)
        if follower_growth or fc_text is not None:
            set_parts.append("follower_growth = ?")
            params.append(follower_growth)
        params.extend([username, mobile_alias])
        cur = conn.execute(
            f"""
            UPDATE device_editor_rows
            SET {', '.join(set_parts)}
            WHERE username = ? AND {_backup_record_alias_expr()} = ?
            """,
            tuple(params),
        )
        owner = str(owner_row["owner_username"] or "").strip() if owner_row else ""
        fg_sync = follower_growth if follower_growth else None
        if follower_count is not None or new_follower_count is not None:
            fg_sync = follower_growth
        _sync_account_editor_stats(
            conn,
            username,
            owner_username=owner,
            video_count=vc_text,
            follower_count=fc_text,
            follower_growth=fg_sync,
            now=now,
        )
        conn.commit()
        return int(cur.rowcount or 0)
    finally:
        conn.close()


def _update_backup_record_assigned_alias(record_id: int, assigned_alias: str) -> int:
    rid = int(record_id or 0)
    alias = _normalized_alias(assigned_alias)
    if rid <= 0 or not alias:
        return 0
    conn = _get_db()
    try:
        now = now_text()
        cur = conn.execute(
            """
            UPDATE device_editor_rows
            SET assigned_alias = ?, updated_at = ?
            WHERE row_index = ? AND COALESCE(NULLIF(TRIM(assigned_alias), ''), device_alias) IN ('', ?)
            """,
            (alias, now, rid, alias),
        )
        conn.commit()
        return int(cur.rowcount or 0)
    finally:
        conn.close()


def _recover_assigned_alias_for_account_username(
    conn: sqlite3.Connection, owner_username: str, username: str
) -> str:
    """从设备备份档案反查账号当前用户名对应的有效别名（账号表 assigned_alias 为空时兜底）。"""
    owner = str(owner_username or "").strip()
    uname = _normalized_alias(username).lstrip("@")
    if not owner or not uname:
        return ""
    alias_expr = _backup_record_alias_expr()
    row = conn.execute(
        f"""
        SELECT {alias_expr} AS eff_alias
        FROM device_editor_rows
        WHERE owner_username = ?
          AND LOWER(TRIM(username)) = LOWER(TRIM(?))
          AND TRIM({alias_expr}) <> ''
        ORDER BY updated_at DESC, row_index DESC
        LIMIT 1
        """,
        (owner, uname),
    ).fetchone()
    return _blank_if_none(row["eff_alias"]) if row else ""


def _sync_device_editor_username_for_account(
    conn: sqlite3.Connection,
    owner_username: str,
    old_username: str,
    new_username: str,
    assigned_alias: str = "",
) -> int:
    """账号记录改名时，同步更新 device_editor_rows 中同账号的 username（保留其余字段）。"""
    owner = str(owner_username or "").strip()
    old_u = _normalized_alias(old_username).lstrip("@")
    new_u = _normalized_alias(new_username).lstrip("@")
    if not owner or not old_u or not new_u or old_u.lower() == new_u.lower():
        return 0
    alias_expr = _backup_record_alias_expr()
    alias = _normalized_alias(assigned_alias)
    now = now_text()

    def _run_update(scope_by_alias: bool) -> int:
        sql = f"""
            UPDATE device_editor_rows
            SET username = ?, updated_at = ?
            WHERE owner_username = ?
              AND LOWER(TRIM(username)) = LOWER(TRIM(?))
        """
        params: list[Any] = [new_u, now, owner, old_u]
        if scope_by_alias and alias:
            sql += f" AND LOWER(TRIM({alias_expr})) = LOWER(TRIM(?))"
            params.append(alias)
        cur = conn.execute(sql, params)
        return int(cur.rowcount or 0)

    if alias:
        touched = _run_update(scope_by_alias=True)
        if touched < 1:
            touched = _run_update(scope_by_alias=False)
        return touched
    return _run_update(scope_by_alias=False)


def _link_backup_record_alias_for_username(
    username: str,
    assigned_alias: str,
    *,
    owner_username: str = "",
    conn: sqlite3.Connection | None = None,
    commit: bool = True,
) -> int:
    uname = _normalized_alias(username).lstrip("@").lower()
    alias = _normalized_alias(assigned_alias)
    owner = str(owner_username or "").strip()
    if not uname or not alias:
        return 0
    own_conn = conn is None
    db = conn or _get_db()
    try:
        now = now_text()
        sql = """
            UPDATE device_editor_rows
            SET assigned_alias = ?, updated_at = ?
            WHERE LOWER(TRIM(username)) = ?
              AND (
                TRIM(COALESCE(assigned_alias, '')) = ''
                OR LOWER(TRIM(assigned_alias)) = LOWER(?)
              )
        """
        params: list[Any] = [alias, now, uname, alias]
        if owner:
            sql += " AND owner_username = ?"
            params.append(owner)
        cur = db.execute(sql, params)
        touched = int(cur.rowcount or 0)
        if commit and own_conn:
            db.commit()
        return touched
    finally:
        if own_conn:
            db.close()


def _upsert_backup_record_for_account(
    owner_username: str,
    username: str,
    assigned_alias: str,
    region: str = "",
    *,
    conn: sqlite3.Connection | None = None,
    commit: bool = True,
) -> int:
    owner = _normalized_alias(owner_username)
    uname = _normalized_alias(username).lstrip("@")
    alias = _normalized_alias(assigned_alias)
    region_text = _normalized_alias(region)
    if not owner or not uname or not alias:
        return 0
    own_conn = conn is None
    db = conn or _get_db()
    try:
        now = now_text()
        existing = db.execute(
            """
            SELECT row_index
            FROM device_editor_rows
            WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))
            ORDER BY updated_at DESC, row_index DESC
            LIMIT 1
            """,
            (owner, uname),
        ).fetchone()
        if existing:
            rid = int(existing["row_index"])
            cur = db.execute(
                """
                UPDATE device_editor_rows
                SET username = ?,
                    assigned_alias = ?,
                    device_alias = CASE WHEN TRIM(device_alias) = '' THEN ? ELSE device_alias END,
                    region = CASE WHEN TRIM(?) <> '' THEN ? ELSE region END,
                    updated_at = ?
                WHERE owner_username = ? AND row_index = ?
                """,
                (uname, alias, alias, region_text, region_text, now, owner, rid),
            )
            touched = int(cur.rowcount or 0)
            if commit and own_conn:
                db.commit()
            return touched

        max_row = db.execute(
            "SELECT COALESCE(MAX(row_index), -1) FROM device_editor_rows WHERE owner_username = ?",
            (owner,),
        ).fetchone()[0]
        row_index = int(max_row) + 1
        default_vc = _default_video_count_for_new_row(db, owner, uname)
        db.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, switch_highlight, video_count, updated_at)
            VALUES (?, ?, ?, '', '', ?, ?, ?, '', '', '', '', '', '否', ?, ?)
            """,
            (owner, row_index, alias, region_text, uname, alias, default_vc, now),
        )
        if commit and own_conn:
            db.commit()
        return 1
    finally:
        if own_conn:
            db.close()


def _patch_latest_backup_row_by_alias(
    mobile_alias: str,
    *,
    backup_path: str,
    backup_name: str | None,
    video_count: int | None,
    follower_count: int | None,
    new_follower_count: int | None,
) -> int:
    mobile_alias = (mobile_alias or "").strip()
    if not mobile_alias:
        return 0
    conn = _get_db()
    try:
        row = conn.execute(
            f"""
            SELECT row_index FROM device_editor_rows
            WHERE {_backup_record_alias_expr()} = ?
            ORDER BY updated_at DESC, row_index DESC
            LIMIT 1
            """,
            (mobile_alias,),
        ).fetchone()
        if not row:
            return 0
        rid = row[0]
        detail = conn.execute(
            """
            SELECT owner_username, username FROM device_editor_rows WHERE row_index = ? LIMIT 1
            """,
            (rid,),
        ).fetchone()
        now = now_text()
        follower_growth = _compute_follower_growth_text(follower_count, new_follower_count)
        vc_text = None if video_count is None else str(video_count)
        fc_text = None if follower_count is None else str(follower_count)
        cur = conn.execute(
            """
            UPDATE device_editor_rows
            SET video_count = COALESCE(?, ''),
                follower_count = COALESCE(?, ''),
                follower_growth = COALESCE(?, ''),
                backup_path = ?, backup_name = ?, backup_time = ?, backup_status = ?, updated_at = ?
            WHERE row_index = ?
            """,
            (
                vc_text,
                fc_text,
                follower_growth,
                backup_path,
                backup_name or "",
                now,
                "已备份",
                now,
                rid,
            ),
        )
        if detail:
            owner = str(detail["owner_username"] or "").strip()
            uname = str(detail["username"] or "").strip()
            fg_sync = follower_growth if follower_growth else None
            if follower_count is not None or new_follower_count is not None:
                fg_sync = follower_growth
            _sync_account_editor_stats(
                conn,
                uname,
                owner_username=owner,
                video_count=vc_text,
                follower_count=fc_text,
                follower_growth=fg_sync,
                now=now,
            )
        conn.commit()
        return int(cur.rowcount or 0)
    finally:
        conn.close()


def _get_backup_record_by_key(username: str, mobile_alias: str | None = None):
    return _get_device_editor_record(username, mobile_alias)


def _backup_record_query_sql(base_where: str, owner_sql: str) -> str:
    return f"""
        SELECT username,
               {_backup_record_alias_expr()} AS mobile_alias,
               {_backup_record_alias_expr()} AS assigned_alias,
               video_count,
               follower_count,
               follower_growth AS new_follower_count,
               backup_path, backup_name, backup_time AS created_at, updated_at
        FROM device_editor_rows
        {base_where}{owner_sql}
    """


def _enqueue_device_op(device_id: str, op: Dict[str, Any]) -> None:
    print(f"[{now_text()}] device_ops.enqueue | enter | device_id={device_id} op_type={op.get('type')}")
    with _tracked_store_lock("_enqueue_device_op"):
        q = store.pending_device_ops.setdefault(device_id, [])
        q.append(op)
        print(f"[{now_text()}] device_ops.enqueue | queued | device_id={device_id} size={len(q)}")


def _next_device_op(device_id: str) -> Dict[str, Any] | None:
    print(f"[{now_text()}] device_ops.poll | next_op_check_start | device_id={device_id}")
    try:
        with _tracked_store_lock("_next_device_op", wait_sec=STORE_LOCK_DEVICE_WAIT_SEC):
            q = store.pending_device_ops.get(device_id) or []
            if not q:
                print(f"[{now_text()}] device_ops.poll | next_op_empty | device_id={device_id}")
                return None
            op = q.pop(0)
            print(f"[{now_text()}] device_ops.poll | next_op_pop | device_id={device_id} op_type={op.get('type')}")
            return op
    except StoreLockBusy:
        print(f"[{now_text()}] device_ops.poll | store_lock_busy | device_id={device_id}")
        return None


def _save_device_op_result(op_id: str, result: Dict[str, Any]) -> None:
    print(f"[{now_text()}] device_ops.report | save_result_start | op_id={op_id}")
    with _tracked_store_lock("_save_device_op_result"):
        store.device_op_results[op_id] = result
        # 设备操作结果仅用于短期查询，避免内存无限增长。
        max_results = 2000
        while len(store.device_op_results) > max_results:
            oldest_key = next(iter(store.device_op_results), None)
            if not oldest_key:
                break
            store.device_op_results.pop(oldest_key, None)


def _device_op_report_is_duplicate(op_id: str, status: str) -> bool:
    """非自治模式下 op_id 幂等：已成功/失败回报不再触发写库副作用。"""
    oid = str(op_id or "").strip()
    if not oid:
        return False
    existing = _get_device_op_result(oid)
    if not existing:
        return False
    prev = str(existing.get("status") or "").strip().lower()
    new = str(status or "").strip().lower()
    return prev in ("success", "failed") and new in ("success", "failed")


def _get_device_op_result(op_id: str) -> Dict[str, Any] | None:
    with _tracked_store_lock("_get_device_op_result"):
        return store.device_op_results.get(op_id)


def _cleanup_finished_tasks():
    with _tracked_store_lock("_cleanup_finished_tasks"):
        to_remove = []
        for key, proc in store.task_processes.items():
            if proc.poll() is not None:
                to_remove.append(key)
        for key in to_remove:
            store.task_processes.pop(key, None)


def _start_task_process(task_name: str, command: List[str]):
    _cleanup_finished_tasks()
    with store.lock:
        if not store.scheduler_enabled:
            return {"code": 1, "msg": "定时任务已停止，请先点击开始定时任务"}
        old = store.task_processes.get(task_name)
        if old and old.poll() is None:
            return {"code": 1, "msg": f"任务已在运行：{task_name}"}

        proc = subprocess.Popen(
            command,
            cwd=str(BASE_DIR),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding=(locale.getpreferredencoding(False) or "utf-8"),
            errors="replace",
            bufsize=1,
        )
        store.task_processes[task_name] = proc
        store.task_logs[task_name] = [f"[{now_text()}] 任务启动: {' '.join(command)}"]

    def _reader():
        try:
            if proc.stdout is None:
                return
            for line in proc.stdout:
                text = (line or "").rstrip("\r\n")
                if not text:
                    continue
                with store.lock:
                    logs = store.task_logs.setdefault(task_name, [])
                    logs.append(text)
                    if len(logs) > 500:
                        del logs[: len(logs) - 500]
        finally:
            code = proc.poll()
            with store.lock:
                logs = store.task_logs.setdefault(task_name, [])
                logs.append(f"[{now_text()}] 任务结束: exit_code={code}")
                if len(logs) > 500:
                    del logs[: len(logs) - 500]

    Thread(target=_reader, daemon=True).start()
    return {"code": 0, "msg": f"任务已触发：{task_name}", "pid": proc.pid}


def _stop_all_task_processes():
    with store.lock:
        for _, proc in list(store.task_processes.items()):
            if proc.poll() is None:
                proc.terminate()
        store.task_processes.clear()


def _stop_task_processes_by_prefix(prefix: str) -> int:
    stopped = 0
    with store.lock:
        for name, proc in list(store.task_processes.items()):
            if not str(name).startswith(prefix):
                continue
            if proc.poll() is None:
                proc.terminate()
                stopped += 1
            store.task_processes.pop(name, None)
            logs = store.task_logs.setdefault(name, [])
            logs.append(f"[{now_text()}] 手动停止任务: prefix={prefix}")
            if len(logs) > 500:
                del logs[: len(logs) - 500]
    return stopped


def _task_log_file_path(task_name: str) -> Path:
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(task_name or "task").strip()) or "task"
    log_dir = (BASE_DIR / "task_logs").resolve()
    log_dir.mkdir(parents=True, exist_ok=True)
    return log_dir / f"{safe}.log"


def _device_log_file_path(device_id: str) -> Path:
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(device_id or "device").strip()) or "device"
    log_dir = (BASE_DIR / "device_logs").resolve()
    log_dir.mkdir(parents=True, exist_ok=True)
    return log_dir / f"{safe}.log"


def _read_device_log_tail(device_id: str, max_lines: int = 120) -> List[str]:
    """从设备日志文件读取末尾若干行（主存不缓存设备日志，概览轮询从此处取数）。"""
    path = _device_log_file_path(device_id)
    if not path.is_file():
        return []
    n = max(1, int(max_lines))
    try:
        with path.open("rb") as f:
            f.seek(0, 2)
            size = f.tell()
            if size == 0:
                return []
            truncated = False
            if size <= 256 * 1024:
                f.seek(0)
                raw = f.read()
            else:
                approx = max(n * 160, 8192)
                chunk = min(size, approx)
                f.seek(size - chunk)
                raw = f.read()
                truncated = chunk < size
        text = raw.decode("utf-8", errors="replace")
        lines = text.splitlines()
        if truncated and lines:
            lines = lines[1:]
        if len(lines) > n:
            lines = lines[-n:]
        return lines
    except Exception as exc:
        print(f"[{now_text()}] device_log_file_read_failed | device={device_id} err={exc}")
        return []


def _device_logs_dict_for_ids(device_ids: Iterable[str], max_lines: int = 120) -> Dict[str, List[str]]:
    out: Dict[str, List[str]] = {}
    for did in device_ids:
        lines = _read_device_log_tail(did, max_lines)
        if lines:
            out[did] = lines
    return out


DEVICE_LOG_PULL_TTL_SEC = 300


def _replace_device_log(device_id: str, lines: List[str]) -> None:
    did = str(device_id or "").strip()
    if not did:
        return
    cleaned = [str(x).rstrip("\r\n") for x in (lines or []) if str(x).strip()]
    if not cleaned:
        # 设备今日本地日志为空：保留服务端已有累积日志，避免 pull 回传把历史日志清空。
        return
    path = _device_log_file_path(did)
    try:
        path.write_text("\n".join(cleaned) + "\n", encoding="utf-8")
        with _device_log_dedup_cache_lock:
            _device_log_dedup_cache.pop(did, None)
    except Exception as exc:
        print(f"[{now_text()}] device_log_file_replace_failed | device={did} err={exc}")


def _peek_device_log_pull_request(device_id: str) -> Dict[str, Any] | None:
    did = str(device_id or "").strip()
    if not did:
        return None
    with store.lock:
        req = store.pending_device_log_pulls.get(did)
        if not req:
            return None
        requested_at = float(req.get("requested_at") or 0.0)
        if requested_at and (time.time() - requested_at) > DEVICE_LOG_PULL_TTL_SEC:
            store.pending_device_log_pulls.pop(did, None)
            return None
        max_lines = max(1, min(int(req.get("max_lines") or 400), 500))
        return {"max_lines": max_lines, "requested_at": requested_at}


def _complete_device_log_pull(device_id: str) -> None:
    did = str(device_id or "").strip()
    if not did:
        return
    with store.lock:
        store.pending_device_log_pulls.pop(did, None)


def _request_device_log_pulls(device_ids: Iterable[str], max_lines: int = 400) -> List[str]:
    safe_lines = max(1, min(int(max_lines or 400), 500))
    now = time.time()
    queued: List[str] = []
    with store.lock:
        for raw in device_ids:
            did = str(raw or "").strip()
            if not did:
                continue
            store.pending_device_log_pulls[did] = {
                "max_lines": safe_lines,
                "requested_at": now,
            }
            queued.append(did)
    return queued


def _append_device_log(device_id: str, line: str) -> None:
    text = str(line or "").rstrip("\r\n")
    if not text:
        return
    did = str(device_id or "").strip()
    now = time.time()
    should_skip = False
    with _device_log_dedup_cache_lock:
        prev = _device_log_dedup_cache.get(did)
        if prev and prev[0] == text and (now - prev[1]) <= DEVICE_LOG_DEDUP_WINDOW_SEC:
            should_skip = True
        else:
            _device_log_dedup_cache[did] = (text, now)
    if should_skip:
        return
    try:
        with _device_log_file_path(did).open("a", encoding="utf-8") as f:
            f.write(text + "\n")
    except Exception as exc:
        print(f"[{now_text()}] device_log_file_write_failed | device={did} err={exc}")


def _cleanup_task_artifacts(task_name: str) -> None:
    try:
        with _tracked_store_lock("_cleanup_task_artifacts"):
            store.pending_device_ops.pop(task_name, None)
            store.device_op_results.pop(task_name, None)
            store.task_logs[task_name] = store.task_logs.get(task_name, [])[-20:]
    except Exception as exc:
        print(f"[{now_text()}] task_cleanup_failed | task={task_name} err={exc}")




SCHEDULER_TASK_DEFS = {
    "collect_roots_db": {
        "label": "全量根用户抓取",
        "default_enabled": True,
        "default_interval_sec": 6 * 60 * 60,
        "command": lambda: [sys.executable, str(FOLLOW_SCRAPER_PATH), "--mode", "collect_roots_db", "--limit", "1000", "--priority", "high"],
    },
    "process_queue": {
        "label": "队列处理",
        "default_enabled": True,
        "default_interval_sec": 60 * 60,
        "command": lambda: [
            sys.executable,
            str(FOLLOW_SCRAPER_PATH),
            "--mode",
            "process_queue",
            "--limit",
            str(_get_process_queue_batch_size()),
        ],
    },
    "autosearch": {
        "label": "粉丝增长监控",
        "default_enabled": True,
        "default_interval_sec": 2 * 60 * 60,
        "command": lambda: [sys.executable, str(AUTOSEARCH_PATH), "--once"],
    },
}


def _scheduler_task_keys() -> List[str]:
    return list(SCHEDULER_TASK_DEFS.keys())


def _get_scheduler_snapshot() -> dict:
    with store.lock:
        task_enabled = {k: bool(store.scheduler_task_enabled.get(k, SCHEDULER_TASK_DEFS[k]["default_enabled"])) for k in _scheduler_task_keys()}
        task_interval_sec = {
            k: int(store.scheduler_task_interval_sec.get(k) or SCHEDULER_TASK_DEFS[k]["default_interval_sec"])
            for k in _scheduler_task_keys()
        }
        task_last_run_at = {k: str(store.scheduler_task_last_run_at.get(k, "") or "") for k in _scheduler_task_keys()}
        return {
            "scheduler_enabled": bool(store.scheduler_enabled),
            "tasks": {
                k: {
                    "enabled": task_enabled[k],
                    "interval_sec": task_interval_sec[k],
                    "last_run_at": task_last_run_at[k],
                    "label": SCHEDULER_TASK_DEFS[k]["label"],
                }
                for k in _scheduler_task_keys()
            },
        }


def _get_process_queue_batch_size() -> int:
    try:
        with store.lock:
            raw = store.config.get("process_queue_batch_size", DEFAULT_PROCESS_QUEUE_BATCH_SIZE)
    except Exception:
        raw = DEFAULT_PROCESS_QUEUE_BATCH_SIZE
    try:
        value = int(raw)
    except Exception:
        value = DEFAULT_PROCESS_QUEUE_BATCH_SIZE
    return max(1, min(1000, value))




def _search_json_value(obj: Any, key: str):
    if isinstance(obj, dict):
        if key in obj and obj[key] is not None:
            return obj[key]
        for v in obj.values():
            found = _search_json_value(v, key)
            if found is not None:
                return found
    elif isinstance(obj, list):
        for item in obj:
            found = _search_json_value(item, key)
            if found is not None:
                return found
    return None


def _collect_video_items(obj: Any, output: List[Dict[str, Any]]):
    if isinstance(obj, dict):
        has_desc = "desc" in obj
        has_play = "playCount" in obj or ("stats" in obj and isinstance(obj.get("stats"), dict) and "playCount" in obj.get("stats", {}))
        has_id = "id" in obj or "aweme_id" in obj
        if has_desc or has_play or has_id:
            output.append(obj)
        for v in obj.values():
            _collect_video_items(v, output)
    elif isinstance(obj, list):
        for item in obj:
            _collect_video_items(item, output)


def _parse_count_text_to_int(raw: str | None):
    if raw is None:
        return None
    text = str(raw).strip().replace(",", "")
    if not text:
        return None
    m = re.match(r"^([0-9]+(?:\.[0-9]+)?)([KkMmBb]?)$", text)
    if m:
        num = float(m.group(1))
        suffix = m.group(2).upper()
        if suffix == "K":
            num *= 1_000
        elif suffix == "M":
            num *= 1_000_000
        elif suffix == "B":
            num *= 1_000_000_000
        return int(num)
    if text.isdigit():
        return int(text)
    return None


def _extract_video_views_from_html_cards(html: str, limit: int):
    items = []
    # 兼容你提供的结构：data-e2e="user-post-item" + /video/{id} + data-e2e="video-views"
    pattern = re.compile(
        r'data-e2e="user-post-item"[\s\S]*?href="https?://www\.tiktok\.com/@[^"/]+/video/(\d+)"[\s\S]*?data-e2e="video-views"[^>]*>([^<]+)</strong>',
        re.IGNORECASE,
    )
    for m in pattern.finditer(html):
        video_id = m.group(1)
        raw_views = (m.group(2) or "").strip()
        play_count = _parse_count_text_to_int(raw_views)
        if play_count is None:
            continue
        items.append(
            {
                "video_id": video_id,
                "desc": "",
                "play_count": play_count,
                "source": "html_video_views",
                "raw_views": raw_views,
            }
        )
        if len(items) >= limit:
            break
    return items


def _extract_profile_main_text(html: str) -> str:
    """仅提取 profile 主内容容器文本，避免脚本/注释中的误命中。"""
    try:
        m = re.search(r"<main\b[^>]*>([\s\S]*?)</main>", html, flags=re.IGNORECASE)
        if not m:
            return ""
        main_html = m.group(1)
        # 去除 script/style/noscript
        main_html = re.sub(r"<script\b[^>]*>[\s\S]*?</script>", " ", main_html, flags=re.IGNORECASE)
        main_html = re.sub(r"<style\b[^>]*>[\s\S]*?</style>", " ", main_html, flags=re.IGNORECASE)
        main_html = re.sub(r"<noscript\b[^>]*>[\s\S]*?</noscript>", " ", main_html, flags=re.IGNORECASE)
        # 去标签
        text = re.sub(r"<[^>]+>", " ", main_html)
        text = re.sub(r"\s+", " ", text).strip()
        return text
    except Exception:
        return ""


def _extract_private_flags_from_universal(universal_obj: object, username: str) -> dict:
    """从 __UNIVERSAL_DATA_FOR_REHYDRATION__ 中提取私密强信号。"""
    target = (username or "").lower().lstrip("@")
    found_private = None
    found_secret = None
    matched_unique = None

    def walk(node):
        nonlocal found_private, found_secret, matched_unique
        if isinstance(node, dict):
            uid = node.get("uniqueId")
            if isinstance(uid, str) and uid.lower() == target:
                matched_unique = uid
                if isinstance(node.get("privateAccount"), bool):
                    found_private = node.get("privateAccount")
                if isinstance(node.get("secret"), bool):
                    found_secret = node.get("secret")
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for x in node:
                walk(x)

    try:
        walk(universal_obj)
    except Exception:
        pass

    return {
        "matched_unique": matched_unique,
        "privateAccount": found_private,
        "secret": found_secret,
    }


def _build_sanitized_raw_response_text(html: str) -> str:
    """用于页面展示：去掉大量 script/style，保留主要可读HTML。"""
    try:
        text = html or ""
        text = re.sub(r"<script\b[^>]*>[\s\S]*?</script>", "", text, flags=re.IGNORECASE)
        text = re.sub(r"<style\b[^>]*>[\s\S]*?</style>", "", text, flags=re.IGNORECASE)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text[:120_000]
    except Exception:
        return (html or "")[:120_000]


def _append_task_log(task_name: str, line: str, max_lines: int = 500) -> None:
    """向内存任务日志追加一行（供 web-admin 轮询 /api/admin/tasks/status 展示）。"""
    text = (line or "").strip()
    if not text:
        return
    with store.lock:
        logs = store.task_logs.setdefault(str(task_name), [])
        logs.append(f"[{now_text()}] {text}")
        if len(logs) > max_lines:
            del logs[: len(logs) - max_lines]


def _mark_follower_queue_preflight_status(
    username: str,
    following_access_status: str,
    mobile_push_status: str = "not_eligible",
    following_fetch_success: int = 1,
) -> None:
    conn = _get_db()
    try:
        conn.execute(
            """
            UPDATE follower_queue
            SET following_access_status = ?,
                mobile_push_status = ?,
                following_fetch_success = ?
            WHERE username = ?
            """,
            (
                following_access_status,
                mobile_push_status,
                int(following_fetch_success),
                username.strip().lstrip("@"),
            ),
        )
        conn.commit()
    finally:
        conn.close()


def preflight_follower_queue_following_visibility(limit: int = 100) -> dict:
    """
    批量预检 follower_queue：用 Universal/followingVisibility 标记关注列表不可见等状态，
    避免 process_queue 开浏览器后再发现无法采集。
    """
    limit = max(1, min(1000, int(limit or 100)))
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT username
            FROM follower_queue
            WHERE COALESCE(following_fetch_success, 0) = 0
              AND COALESCE(following_access_status, 'unknown') NOT IN (
                  'following_private', 'private_account', 'not_found'
              )
            ORDER BY created_at
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    finally:
        conn.close()

    stats = {
        "scanned": len(rows),
        "marked_following_private": 0,
        "marked_private_account": 0,
        "marked_not_found": 0,
        "still_processable": 0,
        "check_failed": 0,
    }
    log_key = "process_queue_preflight"
    with store.lock:
        store.task_logs[log_key] = [
            f"[{now_text()}] follower_queue 预检开始 | 本批待检 {len(rows)} 条 | limit={limit}（拉取 TikTok 页 / Universal 判断关注列表可见性）",
        ]
    total = len(rows)
    for idx, row in enumerate(rows, start=1):
        username = str(row[0] or "").strip().lstrip("@")
        if not username:
            continue
        _append_task_log(log_key, f"预检 [{idx}/{total}] @{username} …")
        try:
            result = fetch_tiktok_profile_stats(username)
        except Exception as exc:
            stats["check_failed"] += 1
            _append_task_log(log_key, f"预检 [{idx}/{total}] @{username} | 异常跳过 | {exc}")
            print(
                f"[{now_text()}] follower_queue预检失败 | username={username} | error={exc}",
            )
            continue

        if not isinstance(result, dict) or int(result.get("code", 1)) != 0:
            stats["check_failed"] += 1
            msg = result.get("msg", "unknown") if isinstance(result, dict) else "non_dict"
            _append_task_log(log_key, f"预检 [{idx}/{total}] @{username} | 接口失败 | {msg}")
            continue

        if bool(result.get("private_account_guess")):
            _mark_follower_queue_preflight_status(username, "private_account")
            stats["marked_private_account"] += 1
            _append_task_log(log_key, f"预检 [{idx}/{total}] @{username} | 判定私密账号 → 标记 private_account")
            print(
                f"[{now_text()}] follower_queue预检 | username={username} | private_account",
            )
            continue

        if result.get("following_list_hidden_guess") is True:
            vis = result.get("profile_following_visibility")
            _mark_follower_queue_preflight_status(username, "following_private")
            stats["marked_following_private"] += 1
            _append_task_log(
                log_key,
                f"预检 [{idx}/{total}] @{username} | 关注列表对他人不可见 → following_private | visibility={vis}",
            )
            print(
                f"[{now_text()}] follower_queue预检 | username={username} | "
                f"following_private | followingVisibility={vis}",
            )
            continue

        follower_count = (result.get("universal_extract") or {}).get("follower_count")
        following_count = (result.get("universal_extract") or {}).get("following_count")
        video_count = (result.get("universal_extract") or {}).get("video_count")
        if (
            follower_count is None
            and following_count is None
            and video_count is None
        ):
            _mark_follower_queue_preflight_status(username, "not_found")
            stats["marked_not_found"] += 1
            _append_task_log(log_key, f"预检 [{idx}/{total}] @{username} | 画像关键字段为空 → not_found")
            continue

        stats["still_processable"] += 1
        _append_task_log(
            log_key,
            f"预检 [{idx}/{total}] @{username} | 通过（关注列表可见，可进入浏览器采集）",
        )

    _append_task_log(
        log_key,
        "预检结束 | "
        f"scanned={stats['scanned']} still_processable={stats['still_processable']} "
        f"following_private={stats['marked_following_private']} private={stats['marked_private_account']} "
        f"not_found={stats['marked_not_found']} check_failed={stats['check_failed']}",
    )
    return stats


def _is_private_username(username: str) -> tuple[bool, str]:
    """统一私密账号检测入口。"""
    result = fetch_tiktok_profile_stats(username)
    if not isinstance(result, dict):
        return False, "stats_unavailable"
    if int(result.get("code", 1)) != 0:
        return False, f"stats_error:{result.get('msg', 'unknown')}"

    if bool(result.get("private_account_guess")):
        reason = str(result.get("private_account_reason") or "private_account_guess")
        return True, reason

    return False, "not_private"


def _filter_out_private_usernames(usernames: List[str], scene: str = "") -> tuple[List[str], List[dict]]:
    cleaned: List[str] = []
    skipped: List[dict] = []
    seen = set()

    for raw in usernames:
        u = (raw or "").strip().lstrip("@")
        if not u:
            continue
        key = u.lower()
        if key in seen:
            continue
        seen.add(key)

        is_private, reason = _is_private_username(u)
        if is_private:
            skipped.append({"username": u, "reason": reason})
            print(f"[{now_text()}] 私密过滤 | scene={scene} | username={u} | reason={reason}")
            continue
        cleaned.append(u)

    return cleaned, skipped


def fetch_tiktok_profile_stats(
    username: str,
    include_raw: bool = False,
    *,
    write_back: bool = False,
    query_index: int | None = None,
    query_total: int | None = None,
):
    """抓取 TikTok 主页并解析 __UNIVERSAL_DATA_FOR_REHYDRATION__，返回结构与 autoSearch extract 一致。"""
    username = username.strip().lstrip("@")
    if not username:
        return {
            "code": 1,
            "msg": "用户名不能为空",
        }

    url = f"https://www.tiktok.com/@{username}"
    headers, cookies_dict, cookie_debug_lines, cookie_path = prepare_tiktok_profile_request_headers_and_cookies()

    try:
        response = requests.get(url, headers=headers, cookies=cookies_dict, timeout=10)
    except Exception as exc:
        return {
            "code": 1,
            "msg": f"请求 TikTok 页面失败: {exc}",
        }

    html = response.text or ""
    response_text_raw = response.text if response.text is not None else ""

    universal = _get_universal_data(html)
    snap = extract_tiktok_universal_profile_snapshot(universal, username)
    apply_following_visibility_html_fallback(snap, html)
    extract_dict = tiktok_universal_extract_to_json_dict(snap)
    user_detail_meta = _webapp_user_detail_meta(universal)

    is_private_guess = snap.profile_private_account is True or snap.profile_secret is True
    private_reason = ""
    if is_private_guess:
        private_reason = (
            "universal_matched_user_flags:"
            f"privateAccount={snap.profile_private_account},"
            f"secret={snap.profile_secret}"
        )

    following_list_hidden_guess = snap.following_list_hidden_guess
    following_list_hidden_reason = ""
    if following_list_hidden_guess is True:
        following_list_hidden_reason = (
            f"universal_matched_user_flags:followingVisibility={snap.profile_following_visibility}"
        )

    debug_payload = {
        "http_status": response.status_code,
        "final_url": str(response.url),
        "html_length": len(html),
        "cookie_file": cookie_path,
        "cookie_debug_lines": cookie_debug_lines,
        "universal_data_present": universal is not None,
        "user_detail_meta": (
            {"status_code": user_detail_meta[0], "status_msg": user_detail_meta[1]}
            if user_detail_meta
            else None
        ),
        "__universal_script_marker_in_html": "__UNIVERSAL_DATA_FOR_REHYDRATION__" in html,
    }

    progress_prefix = ""
    if query_index is not None:
        total = int(query_total or 0)
        remain = max(total - int(query_index), 0) if total > 0 else 0
        progress_prefix = f"正在全量查询第 {int(query_index)} 个，还剩 {remain} 个 | "

    print(
        f"[{now_text()}] {progress_prefix}TikTok stats | username={username} | universal_extract={extract_dict} | "
        f"private_guess={is_private_guess} | reason={private_reason or '-'} | "
        f"following_hidden={following_list_hidden_guess} | "
        f"followingVisibility={snap.profile_following_visibility}"
    )

    if write_back:
        video_count = extract_dict.get("video_count")
        follower_count = extract_dict.get("follower_count")
        new_fc = follower_count if isinstance(follower_count, int) else None
        conn = _get_db()
        try:
            row = conn.execute(
                """
                SELECT row_index, owner_username, username, follower_count, {_alias} AS mobile_alias
                FROM device_editor_rows
                WHERE LOWER(TRIM(username)) = LOWER(TRIM(?))
                ORDER BY updated_at DESC, row_index DESC
                LIMIT 1
                """.format(_alias=_backup_record_alias_expr()),
                (username,),
            ).fetchone()
            if row:
                prev_fc = _parse_count_text_to_int(row["follower_count"])
                follower_growth = ""
                if isinstance(new_fc, int) and isinstance(prev_fc, int):
                    follower_growth = str(max(new_fc - prev_fc, 0))
                elif isinstance(new_fc, int):
                    follower_growth = "0"
                now = now_text()
                vc_text = "" if video_count is None else str(video_count)
                fc_text = "" if follower_count is None else str(follower_count)
                conn.execute(
                    """
                    UPDATE device_editor_rows
                    SET video_count = ?, follower_count = ?, follower_growth = ?, updated_at = ?
                    WHERE row_index = ?
                    """,
                    (
                        vc_text,
                        fc_text,
                        follower_growth,
                        now,
                        int(row["row_index"]),
                    ),
                )
                owner = str(row["owner_username"] or "").strip()
                _sync_account_editor_stats(
                    conn,
                    username,
                    owner_username=owner,
                    video_count=vc_text,
                    follower_count=fc_text,
                    follower_growth=follower_growth,
                    now=now,
                )
                conn.commit()
            elif isinstance(new_fc, int) or video_count is not None:
                now = now_text()
                vc_text = "" if video_count is None else str(video_count)
                fc_text = "" if follower_count is None else str(follower_count)
                fg_text = "0" if isinstance(new_fc, int) else ""
                _sync_account_editor_stats(
                    conn,
                    username,
                    video_count=vc_text if video_count is not None else None,
                    follower_count=fc_text if follower_count is not None else None,
                    follower_growth=fg_text if fg_text else None,
                    now=now,
                )
                conn.commit()
        finally:
            conn.close()

    result = {
        "code": 0,
        "msg": "ok",
        "username": username,
        "universal_extract": extract_dict,
        "private_account_guess": is_private_guess,
        "private_account_reason": private_reason,
        "profile_following_visibility": snap.profile_following_visibility,
        "following_list_hidden_guess": following_list_hidden_guess,
        "following_list_hidden_reason": following_list_hidden_reason,
        "debug": debug_payload,
    }

    if include_raw:
        # 不返回完整 headers（CSP 等动辄数万字符）；仅保留排查用摘要
        hdr = response.headers
        result["raw_response"] = {
            "status_code": response.status_code,
            "url": str(response.url),
            "encoding": response.encoding,
            "apparent_encoding": response.apparent_encoding,
            "headers_summary": {
                "content-type": hdr.get("content-type"),
                "content-length": hdr.get("content-length"),
                "x-tt-trace-id": hdr.get("x-tt-trace-id"),
                "set-cookie_count": len(response.cookies),
                "header_count": len(hdr),
            },
            "text_preview": _build_sanitized_raw_response_text(response_text_raw)[:8000],
            "text_truncated": len(response_text_raw or "") > 8000,
        }

    return result


def _is_private_tiktok_username(username: str) -> tuple[bool, str]:
    """返回是否私密账号及原因。

    这里采用“落库前强校验”策略：
    - 判断为私密 => 拦截
    - 判断失败/超时/返回异常 => 也拦截（fail-closed）
    这样可以保证所有入库数据都经过同一隐私判定函数。
    """
    try:
        result = fetch_tiktok_profile_stats(username=username)
    except Exception as exc:
        return True, f"privacy_check_error:{exc}"

    if not isinstance(result, dict):
        return True, "privacy_check_invalid_result"

    if int(result.get("code", 1)) != 0:
        return True, f"privacy_check_failed:{result.get('msg', 'unknown')}"

    is_private = bool(result.get("private_account_guess"))
    reason = str(result.get("private_account_reason") or "")
    if is_private:
        return True, reason or "private_account_guess=true"

    return False, "not_private"


def _filter_non_private_usernames(usernames: List[str]) -> tuple[List[str], List[Dict[str, str]]]:
    allowed: List[str] = []
    skipped: List[Dict[str, str]] = []
    for u in usernames:
        name = (u or "").strip().lstrip("@")
        if not name:
            continue

        private_flag, reason = _is_private_tiktok_username(name)
        if private_flag:
            skipped.append({"username": name, "reason": reason or "blocked_by_privacy_guard"})
            print(f"[{now_text()}] 入库前隐私拦截 | username={name} | reason={reason}")
            continue

        allowed.append(name)
    return allowed, skipped


@app.get("/")
def root():
    return {"code": 0, "msg": "service ok"}


@app.post("/api/auth/register")
def auth_register(payload: dict):
    return {"code": 403, "msg": "已关闭自助注册，请联系管理员创建账号"}


@app.get("/api/admin/users")
def admin_list_users(authorization: str | None = Header(default=None)):
    err, _session = _require_admin({"authorization": authorization} if authorization else {})
    if err:
        return err
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT id, username, role, created_at, last_login_at, is_active
            FROM auth_users
            ORDER BY id DESC
            """
        ).fetchall()
        items = [
            {
                "id": int(r[0]),
                "username": str(r[1] or ""),
                "role": str(r[2] or "customer"),
                "created_at": str(r[3] or ""),
                "last_login_at": str(r[4] or ""),
                "is_active": bool(int(r[5] or 0)),
            }
            for r in rows
        ]
        return {"code": 0, "items": items}
    finally:
        conn.close()


@app.post("/api/admin/users")
def admin_create_user(payload: AdminCreateUserRequest, authorization: str | None = Header(default=None)):
    err, _session = _require_admin({"authorization": authorization} if authorization else {})
    if err:
        return err
    username = str(payload.username or "").strip()
    if not username or len(username) > 64 or not re.match(r"^[A-Za-z0-9_.-]+$", username):
        return {"code": 1, "msg": "用户名不合法，仅支持字母数字和 ._-"}
    conn = _get_db()
    try:
        exists = conn.execute("SELECT 1 FROM auth_users WHERE username = ?", (username,)).fetchone()
        if exists:
            return {"code": 1, "msg": "用户名已存在"}
        conn.execute(
            """
            INSERT INTO auth_users (username, password_hash, role, created_at, is_active)
            VALUES (?, ?, ?, ?, ?)
            """,
            (username, _hash_password(payload.password), payload.role, now_text(), 1 if payload.is_active else 0),
        )
        conn.commit()
        return {"code": 0, "msg": "创建成功"}
    finally:
        conn.close()


@app.get("/api/admin/template_grants")
def admin_list_template_grants(
    target_username: str = "",
    source_username: str = "",
    authorization: str | None = Header(default=None),
):
    err, _session = _require_admin({"authorization": authorization} if authorization else {})
    if err:
        return err
    target = str(target_username or "").strip()
    source = str(source_username or "").strip()
    conn = _get_db()
    try:
        sql = """
            SELECT source_username, template_name, target_username, granted_by, updated_at
            FROM template_permissions
            WHERE 1=1
        """
        args: list[object] = []
        if target:
            sql += " AND target_username = ?"
            args.append(target)
        if source:
            sql += " AND source_username = ?"
            args.append(source)
        sql += " ORDER BY target_username, source_username, template_name"
        rows = conn.execute(sql, tuple(args)).fetchall()
        items = [
            {
                "source_username": str(r[0] or ""),
                "template_name": str(r[1] or ""),
                "target_username": str(r[2] or ""),
                "granted_by": str(r[3] or ""),
                "updated_at": str(r[4] or ""),
            }
            for r in rows
        ]
        return {"code": 0, "items": items}
    finally:
        conn.close()


@app.get("/api/admin/user_templates")
def admin_user_templates(username: str, authorization: str | None = Header(default=None)):
    err, _session = _require_admin({"authorization": authorization} if authorization else {})
    if err:
        return err
    target = str(username or "").strip()
    if not target:
        return {"code": 1, "msg": "username 不能为空"}
    state = _normalize_user_runtime_state(_load_user_runtime_state(target))
    templates = state.get("configs_by_template", {})
    names = sorted([str(k) for k in templates.keys()]) if isinstance(templates, dict) else []
    return {"code": 0, "username": target, "templates": names}


@app.post("/api/admin/template_grants/upsert")
def admin_upsert_template_grant(payload: TemplateGrantUpsertRequest, authorization: str | None = Header(default=None)):
    err, session = _require_admin({"authorization": authorization} if authorization else {})
    if err:
        return err
    target = str(payload.target_username or "").strip()
    source = str(payload.source_username or "").strip() or session["username"]
    template_name = _sanitize_template_name(payload.template_name, "default")
    if target == source:
        return {"code": 1, "msg": "无需给自己授权模板"}
    conn = _get_db()
    try:
        target_row = conn.execute("SELECT 1 FROM auth_users WHERE username = ?", (target,)).fetchone()
        source_row = conn.execute("SELECT 1 FROM auth_users WHERE username = ?", (source,)).fetchone()
        if not target_row:
            return {"code": 1, "msg": "目标用户不存在"}
        if not source_row:
            return {"code": 1, "msg": "来源用户不存在"}
    finally:
        conn.close()
    source_state = _normalize_user_runtime_state(_load_user_runtime_state(source))
    source_templates = source_state.get("configs_by_template", {})
    if not isinstance(source_templates, dict) or template_name not in source_templates:
        return {"code": 1, "msg": "来源模板不存在"}
    conn = _get_db()
    try:
        if payload.enabled:
            conn.execute(
                """
                INSERT OR REPLACE INTO template_permissions
                (source_username, template_name, target_username, granted_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, COALESCE((SELECT created_at FROM template_permissions
                    WHERE source_username = ? AND template_name = ? AND target_username = ?), ?), ?)
                """,
                (
                    source,
                    template_name,
                    target,
                    session["username"],
                    source,
                    template_name,
                    target,
                    now_text(),
                    now_text(),
                ),
            )
            msg = "授权成功"
        else:
            conn.execute(
                "DELETE FROM template_permissions WHERE source_username = ? AND template_name = ? AND target_username = ?",
                (source, template_name, target),
            )
            msg = "已取消授权"
        conn.commit()
    finally:
        conn.close()
    return {"code": 0, "msg": msg}


@app.post("/api/auth/login")
def auth_login(payload: dict):
    username = str(payload.get('username') or '').strip()
    password = str(payload.get('password') or '')
    token = ""
    role = ""
    try:
        with _auth_db_conn() as conn:
            row = conn.execute(
                "SELECT username, password_hash, role, is_active FROM auth_users WHERE username = ?",
                (username,),
            ).fetchone()
            if not row or int(row[3] or 0) != 1 or not _verify_password(password, row[1]):
                return {"code": 1, "msg": "用户名或密码错误"}
            token = _new_session_token()
            role = str(row[2] or "")
            conn.execute(
                "INSERT INTO auth_sessions (token, username, role, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                (token, username, role, now_text(), "2999-12-31 23:59:59"),
            )
            conn.commit()
    except sqlite3.OperationalError as exc:
        if _is_db_lock_error(exc):
            print(f"[{now_text()}] auth.login | db_busy | user={username} err={exc}")
            return {"code": 1, "msg": "服务繁忙，请稍后重试"}
        raise
    _touch_auth_last_login_async(username)
    _invalidate_auth_session_cache()
    return {"code": 0, "msg": "登录成功", "token": token, "role": role, "username": username}


@app.post("/api/login")
def auth_login_legacy(payload: dict):
    """兼容旧客户端：等价于 /api/auth/login。"""
    return auth_login(payload)


@app.get("/api/login")
def auth_login_legacy_get():
    """兼容误发起的 GET /api/login，避免前端控制台出现 404。"""
    return {"code": 1, "msg": "请使用 POST /api/auth/login"}


@app.get("/api/health")
async def api_health():
    """轻量探活：不访问 SQLite；async 避免 sync 路由占满线程池时 health 也排队超时。"""
    return {"code": 0, "msg": "ok", "ts": now_text()}


@app.get("/api/auth/me")
def auth_me(authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "未登录"}
    return {"code": 0, "user": session}


def _hydrate_device_from_db(device_id: str) -> None:
    """按 device_id 从 client_devices 读一行并写回内存 store。"""
    conn = _get_db()
    try:
        row = conn.execute(
            "SELECT device_id, owner_username, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out, auto_switch_enabled FROM client_devices WHERE device_id = ?",
            (device_id,),
        ).fetchone()
    finally:
        conn.close()
    if not row:
        return
    st = str(row["status"] or "offline")
    if st not in ("offline", "online", "running", "error"):
        st = "offline"
    with store.lock:
        store.devices[device_id] = DeviceInfo(
            alias=str(row["alias"]),
            fingerprint=str(row["fingerprint"]),
            owner_username=str(row["owner_username"]),
            device_group=str(row["device_group"] or "") if "device_group" in row.keys() else "",
            status=st,  # type: ignore[arg-type]
            pending_start=bool(int(row["pending_start"] or 0)),
            paused=bool(int(row["paused"] or 0)),
            auto_start_opt_out=bool(int(row["auto_start_opt_out"] or 0)) if "auto_start_opt_out" in row.keys() else False,
            auto_switch_enabled=bool(int(row["auto_switch_enabled"] or 0)) if "auto_switch_enabled" in row.keys() else False,
        )
        store.fingerprint_to_device[str(row["fingerprint"])] = device_id


@app.get("/api/device_binding")
def lookup_device_binding(
    device_fingerprint: str = Query(..., min_length=1, max_length=128),
    authorization: str | None = Header(default=None),
):
    """按设备指纹查询当前登录用户下的绑定（用于 APP 本地别名丢失后的恢复）。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    owner = str(session["username"] or "").strip()
    fp = str(device_fingerprint or "").strip()
    if not fp:
        return {"code": 1, "msg": "参数无效"}
    conn = _get_db()
    try:
        # 优先当前用户；若无则按指纹全局查一条（设备曾转绑/多 worker 内存未同步时仍可恢复 device_id+alias）
        row = conn.execute(
            """
            SELECT device_id, alias, owner_username
            FROM client_devices
            WHERE fingerprint = ? AND owner_username = ?
            LIMIT 1
            """,
            (fp, owner),
        ).fetchone()
        if not row:
            row = conn.execute(
                """
                SELECT device_id, alias, owner_username
                FROM client_devices
                WHERE fingerprint = ?
                LIMIT 1
                """,
                (fp,),
            ).fetchone()
            if row and str(row["owner_username"] or "").strip() != owner:
                return {
                    "code": 1,
                    "msg": "该设备已绑定到其他云端账号，请使用原账号登录或联系管理员转绑",
                }
    finally:
        conn.close()
    if not row:
        return {"code": 1, "msg": "未找到该设备的绑定记录，请重新绑定"}
    did = str(row["device_id"] or "").strip()
    alias = str(row["alias"] or "").strip()
    if not did or not alias:
        return {"code": 1, "msg": "绑定记录不完整，请重新绑定"}
    _hydrate_device_from_db(did)
    return {"code": 0, "msg": "ok", "device_id": did, "alias": alias}


@app.post("/api/bind_device")
def bind_device(payload: BindDeviceRequest, authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    owner = session["username"]
    fp = (payload.device_fingerprint or "").strip()
    alias = (payload.alias or "").strip()
    if not fp or not alias:
        return {"code": 1, "msg": "参数无效"}

    row_group = ""
    conn = _get_db()
    try:
        row = conn.execute(
            "SELECT device_id, owner_username, alias, device_group FROM client_devices WHERE fingerprint = ?",
            (fp,),
        ).fetchone()
        if row:
            existing_id = str(row[0])
            row_owner = str(row[1])
            old_alias = str(row[2])
            row_group = str(row[3] or "")
            conflict = conn.execute(
                "SELECT 1 FROM client_devices WHERE owner_username = ? AND alias = ? AND device_id != ?",
                (owner, alias, existing_id),
            ).fetchone()
            if conflict:
                return {"code": 1, "msg": "您已使用过该设备别名，请更换别名"}
            if row_owner != owner:
                conn.execute(
                    "UPDATE client_devices SET owner_username = ?, alias = ?, device_group = ?, updated_at = ? WHERE device_id = ?",
                    (owner, alias, row_group, now_text(), existing_id),
                )
                conn.commit()
                _hydrate_device_from_db(existing_id)
                print(f"[{now_text()}] 系统 | 设备转绑成功：{old_alias}/{row_owner} -> {alias}/{owner}（device_id={existing_id}）")
                return {"code": 0, "msg": "绑定成功", "device_id": existing_id, "alias": alias}
            if old_alias != alias:
                conn.execute(
                    "UPDATE client_devices SET alias = ?, device_group = ?, updated_at = ? WHERE device_id = ?",
                    (alias, row_group, now_text(), existing_id),
                )
            conn.commit()
            _hydrate_device_from_db(existing_id)
            print(f"[{now_text()}] 系统 | 设备绑定确认：{alias} -> {existing_id}（owner={owner}）")
            return {"code": 0, "msg": "绑定成功", "device_id": existing_id, "alias": alias}

        conflict = conn.execute(
            "SELECT 1 FROM client_devices WHERE owner_username = ? AND alias = ?",
            (owner, alias),
        ).fetchone()
        if conflict:
            return {"code": 1, "msg": "您已使用过该设备别名，请更换别名"}

        device_id = make_device_id()
        while conn.execute("SELECT 1 FROM client_devices WHERE device_id = ?", (device_id,)).fetchone():
            device_id = make_device_id()
        t = now_text()
        conn.execute(
            """
            INSERT INTO client_devices (device_id, owner_username, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'offline', 0, 0, 0, ?, ?)
            """,
            (device_id, owner, alias, row_group, fp, t, t),
        )
        conn.commit()
    finally:
        conn.close()

    _hydrate_device_from_db(device_id)
    print(f"[{now_text()}] 系统 | 设备绑定成功：{alias} -> {device_id}（owner={owner}）")
    return {"code": 0, "msg": "绑定成功", "device_id": device_id, "alias": alias}


@app.post("/api/sync_status")
def sync_status(payload: SyncStatusRequest):
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    new_status = str(payload.status or "").strip()
    if str(device.status or "") == new_status:
        return {"code": 0, "msg": "状态未变化"}

    autonomous = _device_auto_switch_enabled(payload.device_id)
    try:
        with _store_lock("sync_status", wait_sec=STORE_LOCK_DEVICE_WAIT_SEC):
            if str(device.status or "") == new_status:
                return {"code": 0, "msg": "状态未变化"}
            device.status = new_status  # type: ignore[assignment]
    except StoreLockBusy:
        print(f"[{now_text()}] sync_status | store_lock_busy | device_id={payload.device_id} alias={payload.alias}")
        return _store_lock_busy_response()

    if autonomous:
        return {"code": 0, "msg": "自治期已更新内存"}

    _persist_client_device_to_db(payload.device_id)
    print(f"[{now_text()}] 设备别名：{payload.alias} | 状态更新：{new_status}")
    return {"code": 0, "msg": "状态同步成功"}


@app.get("/api/get_config")
def get_config(
    device_id: str,
    alias: str,
    run_mode: str | None = None,
    tiktok_username: str | None = None,
):
    error, device = auth_or_error(device_id, alias)
    if error:
        return error
    data = _normalize_user_runtime_state(_load_user_runtime_state(device.owner_username))
    mode = run_mode if run_mode in RUN_MODES else data.get("run_mode", "growth")
    mode = mode if mode in RUN_MODES else "growth"
    alias_clean = _normalize_alias_key(alias)
    account_clean = _normalize_tiktok_username_key(tiktok_username or "")
    if not account_clean:
        account_clean = _resolve_switch_highlight_tiktok_username(device.owner_username, alias_clean)
    active_template, config, templates, template_meta = _resolve_template_for_mode_alias(
        device.owner_username,
        data,
        mode,
        alias,
        account_clean or None,
    )
    alias_map_all = data.get("alias_template_by_mode", {})
    mode_map = alias_map_all.get(mode) if isinstance(alias_map_all, dict) else {}
    account_key = _alias_account_template_key(alias_clean, account_clean) if account_clean else ""
    mode_raw_ref = ""
    if isinstance(mode_map, dict):
        if account_key:
            mode_raw_ref = str(mode_map.get(account_key) or "").strip()
        if not mode_raw_ref:
            mode_raw_ref = str(mode_map.get(alias_clean) or "").strip()
    cross_mode_hit = ""
    cross_mode_ref = ""
    if not mode_raw_ref and isinstance(alias_map_all, dict):
        for probe_mode in RUN_MODES:
            if probe_mode == mode:
                continue
            probe_map = alias_map_all.get(probe_mode)
            if not isinstance(probe_map, dict):
                continue
            probe_ref = str(probe_map.get(alias_clean) or "").strip()
            if probe_ref in templates:
                cross_mode_hit = probe_mode
                cross_mode_ref = probe_ref
                break
    stored_active_template = str(data.get("active_template") or "").strip()
    if mode_raw_ref and mode_raw_ref in templates:
        template_source = "mode_alias"
    elif cross_mode_ref:
        template_source = f"cross_mode_alias:{cross_mode_hit}"
    else:
        template_source = "active_template_fallback"
    return {
        "code": 0,
        "config": config,
        "run_mode": mode,
        "active_template": active_template,
        "configs_by_template": templates,
        "template_meta": template_meta,
        "debug_template_resolution": {
            "db_path": str(DB_PATH),
            "owner_username": device.owner_username,
            "device_id": device_id,
            "alias_raw": alias,
            "alias_normalized": alias_clean,
            "tiktok_username": account_clean or "",
            "account_template_key": account_key or "",
            "run_mode_requested": run_mode,
            "run_mode_effective": mode,
            "run_mode_stored": str(data.get("run_mode") or "").strip(),
            "stored_active_template": stored_active_template,
            "mode_alias_ref": mode_raw_ref,
            "cross_mode_hit": cross_mode_hit,
            "cross_mode_ref": cross_mode_ref,
            "selected_template": active_template,
            "selected_source": template_source,
        },
    }


@app.get("/api/admin/runtime_config")
def get_runtime_config(response: Response, authorization: str | None = Header(default=None)):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    response.headers["Pragma"] = "no-cache"
    response.headers["Expires"] = "0"
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    templates, template_meta = _build_effective_template_bundle(session["username"], data)
    active_template = str(data.get("active_template") or "default")
    if active_template not in templates:
        # GET 只修正响应中的模板名，不写库、不 bump config_push_revision，避免登录/刷新误触发设备同步。
        active_template = "default" if "default" in templates else sorted(templates.keys())[0]
    resp = dict(data)
    resp["active_template"] = active_template
    resp["configs_by_template"] = templates
    resp["template_meta"] = template_meta
    resp["alias_template_by_mode"] = data.get(
        "alias_template_by_mode",
        {"growth": {}, "live": {}, "direct": {}, "mutual": {}},
    )
    resp["smart_template_config"] = data.get("smart_template_config") or dict(DEFAULT_SMART_TEMPLATE_CONFIG)
    resp["alias_smart_template_by_mode"] = data.get("alias_smart_template_by_mode") or {
        mode: {} for mode in RUN_MODES
    }
    return {"code": 0, "data": resp}


@app.post("/api/admin/smart_template/config")
def save_smart_template_config(
    payload: SmartTemplateConfigRequest,
    authorization: str | None = Header(default=None),
):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    data["smart_template_config"] = _normalize_smart_template_config(
        {
            "old_branch_min_followers": payload.old_branch_min_followers,
            "old_branch_min_video_count": payload.old_branch_min_video_count,
            "new_branch_templates": [e.model_dump() for e in payload.new_branch_templates],
            "old_branch_templates": [e.model_dump() for e in payload.old_branch_templates],
        },
    )
    _save_user_runtime_state(session["username"], data)
    return {
        "code": 0,
        "msg": "智能模板规则已保存",
        "smart_template_config": data["smart_template_config"],
    }


@app.post("/api/admin/alias_smart_template/toggle")
def toggle_alias_smart_template(
    payload: AliasSmartTemplateToggleRequest,
    authorization: str | None = Header(default=None),
):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    target_username = session["username"]
    requested_owner = str(payload.owner_username or "").strip()
    if requested_owner:
        if session.get("role") != "admin" and requested_owner != session["username"]:
            return {"code": 1, "msg": "无权限修改其他用户智能模板开关"}
        target_username = requested_owner
    alias = _normalize_alias_key(payload.alias)
    if not alias:
        return {"code": 1, "msg": "alias不能为空"}
    data = _normalize_user_runtime_state(_load_user_runtime_state(target_username))
    mode = "growth"
    by_mode = data.get("alias_smart_template_by_mode")
    if not isinstance(by_mode, dict):
        by_mode = {m: {} for m in RUN_MODES}
    mode_map = by_mode.get(mode)
    if not isinstance(mode_map, dict):
        mode_map = {}
    if payload.enabled:
        mode_map[alias] = True
    else:
        mode_map.pop(alias, None)
    by_mode[mode] = mode_map
    data["alias_smart_template_by_mode"] = _normalize_alias_smart_template_by_mode(by_mode)
    _save_user_runtime_state(target_username, data)
    templates, _meta = _build_effective_template_bundle(target_username, data)
    resolved_name = ""
    resolved_debug: dict[str, object] = {}
    if payload.enabled and templates:
        resolved_name, resolved_debug = _resolve_smart_template_for_alias(
            target_username,
            data,
            mode,
            alias,
            templates,
        )
    return {
        "code": 0,
        "msg": "已启用智能模板" if payload.enabled else "已关闭智能模板",
        "owner_username": target_username,
        "mode": mode,
        "alias": alias,
        "enabled": bool(payload.enabled),
        "alias_smart_template_by_mode": data["alias_smart_template_by_mode"],
        "resolved_template": resolved_name,
        "resolution": resolved_debug,
    }


@app.post("/api/admin/smart_template/preview")
def preview_smart_templates(
    payload: SmartTemplatePreviewRequest,
    authorization: str | None = Header(default=None),
):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    is_admin = session.get("role") == "admin"
    results: dict[str, dict[str, object]] = {}
    for item in payload.items or []:
        alias = _normalize_alias_key(item.alias)
        if not alias:
            continue
        owner = str(item.owner_username or session["username"]).strip()
        if not owner:
            continue
        if not is_admin and owner != session["username"]:
            continue
        mode = item.mode if item.mode in RUN_MODES else "growth"
        data = _normalize_user_runtime_state(_load_user_runtime_state(owner))
        templates, _meta = _build_effective_template_bundle(owner, data)
        enabled = _alias_smart_template_enabled(data, mode, alias)
        key = f"{owner}::{mode}::{alias}"
        if not enabled:
            manual_name, _, _, _ = _resolve_template_for_mode_alias(owner, data, mode, alias)
            results[key] = {
                "enabled": False,
                "resolved_template": manual_name,
            }
            continue
        if not templates:
            results[key] = {"enabled": True, "resolved_template": "", "resolution": {}}
            continue
        resolved_name, debug = _resolve_smart_template_for_alias(owner, data, mode, alias, templates)
        results[key] = {
            "enabled": True,
            "resolved_template": resolved_name,
            "resolution": debug,
        }
    return {"code": 0, "results": results}


@app.get("/api/admin/template_inventory")
def get_template_inventory(
    response: Response,
    all_users: int = Query(0, ge=0, le=1),
    authorization: str | None = Header(default=None),
):
    """列出模板相关键：数据库原始 configs_by_template 键 + 合并后可见键 + 别名引用但缺失的 ref（不过滤为「可编辑」子集）。"""
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    response.headers["Pragma"] = "no-cache"
    response.headers["Expires"] = "0"
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    username = session["username"]
    data = _normalize_user_runtime_state(_load_user_runtime_state(username))
    eff_templates, _eff_meta = _build_effective_template_bundle(username, data)
    effective_keys = sorted(eff_templates.keys(), key=lambda x: x.lower())
    raw_blob = _load_raw_configs_by_template_blob(username)
    raw_keys, raw_invalid_keys = _parse_raw_template_keys(raw_blob)
    orphans = _collect_orphan_alias_template_refs(data, set(effective_keys))

    payload: dict[str, object] = {
        "username": username,
        "raw_own_keys": raw_keys,
        "raw_invalid_value_keys": raw_invalid_keys,
        "effective_keys": effective_keys,
        "orphan_alias_refs": orphans,
        "stored_active_template": str(data.get("active_template") or "").strip(),
        "all_users_rows": None,
    }

    if all_users:
        if session.get("role") != "admin":
            return {"code": 1, "msg": "仅管理员可查看全站模板清单"}
        conn = _get_db()
        try:
            rows = conn.execute(
                "SELECT username, config_json FROM user_runtime_configs WHERE config_key = ? ORDER BY username",
                ("configs_by_template",),
            ).fetchall()
            all_rows: list[dict[str, object]] = []
            for row_username, raw_json in rows:
                u = str(row_username or "").strip()
                if not u:
                    continue
                try:
                    blob = json.loads(raw_json or "{}")
                except Exception:
                    blob = None
                rk, inv = _parse_raw_template_keys(blob)
                all_rows.append({"username": u, "raw_keys": rk, "invalid_keys": inv})
            payload["all_users_rows"] = all_rows
        finally:
            conn.close()

    return {"code": 0, "data": payload}


@app.post("/api/admin/alias_template/select")
def set_alias_template(payload: AliasTemplateSelectRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    target_username = session["username"]
    requested_owner = str(payload.owner_username or "").strip()
    if requested_owner:
        if session.get("role") != "admin" and requested_owner != session["username"]:
            return {"code": 1, "msg": "无权限修改其他用户模板映射"}
        target_username = requested_owner
    alias_raw = str(payload.alias or "").strip()
    alias = _normalize_alias_key(alias_raw)
    if not alias:
        return {"code": 1, "msg": "alias不能为空"}
    data = _normalize_user_runtime_state(_load_user_runtime_state(target_username))
    mode = payload.mode if payload.mode in RUN_MODES else str(data.get("run_mode") or "growth")
    templates, _template_meta = _build_effective_template_bundle(target_username, data)
    if not templates:
        return {"code": 1, "msg": "暂无可用模板"}
    requested = str(payload.template_name or "").strip()
    if requested not in templates:
        return {"code": 1, "msg": "模板不存在或当前用户无权限"}
    alias_map_all = data.get("alias_template_by_mode", {})
    if not isinstance(alias_map_all, dict):
        alias_map_all = {}
    mode_map = alias_map_all.get(mode, {})
    if not isinstance(mode_map, dict):
        mode_map = {}
    map_key = alias
    tiktok_user = _normalize_tiktok_username_key(payload.tiktok_username or "")
    if tiktok_user:
        map_key = _alias_account_template_key(alias, tiktok_user)
    mode_map[map_key] = requested
    alias_map_all[mode] = mode_map
    data["alias_template_by_mode"] = alias_map_all
    _save_user_runtime_state(target_username, data)
    print(
        f"[{now_text()}] 模板映射 | operator={session['username']} user={target_username} mode={mode} alias={alias} account={tiktok_user or '-'} map_key={map_key} raw_alias={alias_raw} template={requested}"
    )
    return {
        "code": 0,
        "msg": "账号模板已更新" if tiktok_user else "别名模板已更新",
        "owner_username": target_username,
        "mode": mode,
        "alias": alias,
        "tiktok_username": tiktok_user,
        "template_name": requested,
        "alias_template_by_mode": alias_map_all,
    }


@app.post("/api/admin/run_mode")
def set_run_mode(payload: RunModeRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    data["run_mode"] = payload.mode
    _save_user_runtime_state(session["username"], data)
    templates, _meta = _build_effective_template_bundle(session["username"], data)
    active_template = str(data.get("active_template") or "default")
    if active_template not in templates:
        active_template = "default" if "default" in templates else sorted(templates.keys())[0]
    config = dict(templates.get(active_template, MODE_DEFAULT_CONFIGS["growth"]))
    return {"code": 0, "mode": payload.mode, "config": config, "active_template": active_template}


@app.post("/api/admin/config_template/select")
def set_config_template(payload: ConfigTemplateSelectRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    own_templates = data.get("configs_by_template", {})
    all_templates, meta = _build_effective_template_bundle(session["username"], data)
    active = str(data.get("active_template") or "default")
    next_template = str(payload.template_name or "").strip()
    if not next_template:
        return {"code": 1, "msg": "模板名称不能为空"}
    if next_template not in all_templates:
        own_next = _sanitize_template_name(next_template, "default")
        own_templates[own_next] = dict(all_templates.get(active, MODE_DEFAULT_CONFIGS["growth"]))
        data["configs_by_template"] = own_templates
        next_template = own_next
        all_templates, meta = _build_effective_template_bundle(session["username"], data)
    data["active_template"] = next_template
    data["default_config"] = dict(all_templates[next_template])
    _save_user_runtime_state(session["username"], data)
    return {
        "code": 0,
        "active_template": next_template,
        "config": all_templates[next_template],
        "configs_by_template": all_templates,
        "template_meta": meta,
    }


@app.post("/api/admin/config_template/rename")
def rename_config_template(payload: ConfigTemplateRenameRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    old_name = _sanitize_template_name(payload.old_template_name, "default")
    new_name = _sanitize_template_name(payload.new_template_name, "default")
    if old_name == new_name:
        return {"code": 1, "msg": "新旧模板名相同"}
    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    templates = data.get("configs_by_template", {})
    if old_name not in templates:
        return {"code": 1, "msg": "原模板不存在或无编辑权限"}
    if new_name in templates:
        return {"code": 1, "msg": "新模板名已存在"}
    templates[new_name] = dict(templates.pop(old_name))
    if str(data.get("active_template") or "") == old_name:
        data["active_template"] = new_name
    alias_map_all = data.get("alias_template_by_mode", {})
    if isinstance(alias_map_all, dict):
        for mode_key in RUN_MODES:
            mode_map = alias_map_all.get(mode_key)
            if not isinstance(mode_map, dict):
                continue
            for alias_key, mapped in list(mode_map.items()):
                if str(mapped or "").strip() == old_name:
                    mode_map[alias_key] = new_name
            alias_map_all[mode_key] = mode_map
        data["alias_template_by_mode"] = alias_map_all
    data["configs_by_template"] = templates
    data["default_config"] = dict(templates.get(str(data.get("active_template")), MODE_DEFAULT_CONFIGS["growth"]))
    _save_user_runtime_state(session["username"], data)
    conn = _get_db()
    try:
        conn.execute(
            """
            UPDATE template_permissions
            SET template_name = ?, updated_at = ?
            WHERE source_username = ? AND template_name = ?
            """,
            (new_name, now_text(), session["username"], old_name),
        )
        conn.commit()
    finally:
        conn.close()
    eff, meta = _build_effective_template_bundle(session["username"], data)
    return {"code": 0, "msg": "模板重命名成功", "active_template": data.get("active_template"), "configs_by_template": eff, "template_meta": meta}


@app.post("/api/admin/config_template/delete")
def delete_config_template(payload: ConfigTemplateDeleteRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    name = _sanitize_template_name(payload.template_name, "default")
    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    templates = data.get("configs_by_template", {})
    if name not in templates:
        return {"code": 1, "msg": "模板不存在或无编辑权限"}
    if len(templates) <= 1:
        return {"code": 1, "msg": "至少保留一个模板，不能全部删除"}
    del templates[name]
    active = str(data.get("active_template") or "default")
    if active == name:
        data["active_template"] = "default" if "default" in templates else sorted(templates.keys())[0]
    alias_map_all = data.get("alias_template_by_mode", {})
    if isinstance(alias_map_all, dict):
        for mode_key in RUN_MODES:
            mode_map = alias_map_all.get(mode_key)
            if not isinstance(mode_map, dict):
                continue
            stale_aliases = [k for k, v in mode_map.items() if str(v or "").strip() == name]
            for alias_key in stale_aliases:
                mode_map.pop(alias_key, None)
            alias_map_all[mode_key] = mode_map
        data["alias_template_by_mode"] = alias_map_all
    data["configs_by_template"] = templates
    data["default_config"] = dict(templates.get(str(data.get("active_template")), MODE_DEFAULT_CONFIGS["growth"]))
    _save_user_runtime_state(session["username"], data)
    conn = _get_db()
    try:
        conn.execute(
            "DELETE FROM template_permissions WHERE source_username = ? AND template_name = ?",
            (session["username"], name),
        )
        conn.commit()
    finally:
        conn.close()
    eff, meta = _build_effective_template_bundle(session["username"], data)
    return {"code": 0, "msg": "模板已删除", "active_template": data.get("active_template"), "configs_by_template": eff, "template_meta": meta}


@app.post("/api/admin/config_template/copy")
def copy_config_template(payload: ConfigTemplateCopyRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    templates = data.get("configs_by_template", {})
    new_name = _sanitize_template_name(payload.new_template_name, "default")
    if new_name in templates:
        return {"code": 1, "msg": "目标模板名已存在"}
    source_owner, source_name, is_shared_ref = _parse_template_ref(payload.source_template_name)
    source_cfg: Dict[str, object] | None = None
    if is_shared_ref and source_owner != session["username"]:
        owner_state = _normalize_user_runtime_state(_load_user_runtime_state(source_owner))
        owner_templates = owner_state.get("configs_by_template", {})
        got = owner_templates.get(source_name) if isinstance(owner_templates, dict) else None
        if isinstance(got, dict):
            source_cfg = dict(got)
        if source_cfg is None:
            return {"code": 1, "msg": "来源共享模板不存在"}
        grants = _load_template_grants_for_user(session["username"])
        if not any(g["source_username"] == source_owner and g["template_name"] == source_name for g in grants):
            return {"code": 403, "msg": "你没有该共享模板使用权限"}
    else:
        src_key = _sanitize_template_name(source_name if is_shared_ref else payload.source_template_name, "default")
        got = templates.get(src_key)
        if isinstance(got, dict):
            source_cfg = dict(got)
        if source_cfg is None:
            return {"code": 1, "msg": "来源模板不存在"}
    templates[new_name] = source_cfg
    data["configs_by_template"] = templates
    data["active_template"] = new_name
    data["default_config"] = dict(source_cfg)
    _save_user_runtime_state(session["username"], data)
    eff, meta = _build_effective_template_bundle(session["username"], data)
    return {"code": 0, "msg": "模板复制成功", "active_template": new_name, "config": source_cfg, "configs_by_template": eff, "template_meta": meta}


@app.post("/api/admin/auto_start")
def admin_set_auto_start(payload: AutoStartRequest, authorization: str | None = Header(default=None)):
    """管理台（登录用户）：开启/关闭「本用户绑定设备在未暂停时自行拉起程序」的策略。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    paused_count = 0
    cleared_pending_count = 0
    persist_ids: list[str] = []
    allowed = _session_device_ids(session)
    next_enabled = bool(payload.enabled)
    # 写入用户配置（按用户名持久化）
    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    data["auto_start_enabled"] = next_enabled
    _save_user_runtime_state(session["username"], data)
    with store.lock:
        enabled = next_enabled
        if not enabled:
            for device_id, device in store.devices.items():
                if device_id not in allowed:
                    continue
                touched = False
                if device.pending_start:
                    device.pending_start = False
                    cleared_pending_count += 1
                    touched = True
                if device.status in {"running", "error"} or not device.paused:
                    device.status = "paused"
                    device.paused = True
                    paused_count += 1
                    touched = True
                    _append_device_log(
                        device_id,
                        f"[{now_text()}] 云端后台 | 自动开启已关闭，已强制暂停（alias={device.alias}）",
                    )
                if touched:
                    persist_ids.append(device_id)
    for did in persist_ids:
        _persist_client_device_to_db(did)
    return {
        "code": 0,
        "msg": "已更新",
        "auto_start_enabled": enabled,
        "paused_count": paused_count,
        "cleared_pending_count": cleared_pending_count,
    }


@app.post("/api/start_task")
def start_task(payload: StartTaskRequest, authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session)
    for device_id in payload.device_ids:
        if device_id not in allowed:
            return {"code": 403, "msg": "包含无权操作的设备"}
        blocked = _reject_if_auto_switch_manual_ops(device_id=device_id)
        if blocked:
            return blocked
    started_count = 0
    touched: list[str] = []
    with store.lock:
        for device_id in payload.device_ids:
            device = store.devices.get(device_id)
            if not device:
                continue
            # 无论设备当前状态如何，都统一下发一次启动指令
            device.pending_start = True
            device.paused = False
            started_count += 1
            touched.append(device_id)
            # 写入后台可见日志：设备端未必立刻轮询到 check_start，避免用户误以为「第二次点击无效」
            _append_device_log(
                device_id,
                f"[{now_text()}] 云端后台 | 已下发启动指令（alias={device.alias}）",
            )

    for did in touched:
        _persist_client_device_to_db(did)
    if started_count == 0:
        return {"code": 1, "msg": "无可启动设备/设备未绑定"}
    return {
        "code": 0,
        "msg": "启动指令已下发",
        "count": started_count,
        "started": started_count,
        "resumed": 0,
    }


@app.post("/api/stop_task")
def stop_task(payload: StartTaskRequest, authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session)
    for device_id in payload.device_ids:
        if device_id not in allowed:
            return {"code": 403, "msg": "包含无权操作的设备"}
        blocked_one = _reject_if_auto_switch_manual_ops(device_id=device_id)
        if blocked_one:
            return blocked_one

    stopped_count = 0
    touched: list[str] = []
    with store.lock:
        for device_id in payload.device_ids:
            device = store.devices.get(device_id)
            if not device:
                continue

            # 停止指令的语义：立刻清空待启动状态，并将设备标记为暂停，禁止继续自动拉起。
            # 这样无论指令从哪里发出，都不会再被 pending_start / auto_start 继续顶起来。
            device.status = "paused"
            device.paused = True
            device.pending_start = False
            stopped_count += 1
            touched.append(device_id)
            _append_device_log(
                device_id,
                f"[{now_text()}] 云端后台 | 已强制停止并清空启动指令（alias={device.alias}）",
            )

    for did in touched:
        _persist_client_device_to_db(did)
    if stopped_count == 0:
        return {"code": 1, "msg": "无可停止设备/设备未绑定"}
    return {"code": 0, "msg": "停止指令已下发", "count": stopped_count}


@app.get("/api/check_start")
def check_start(device_id: str, alias: str):
    error, device = auth_or_error(device_id, alias)
    if error:
        print(f"[{now_text()}] check_start | auth_failed | device_id={device_id} alias={alias}")
        return error

    mutated = False
    auto = _user_auto_start_enabled(device.owner_username) and (not device.auto_start_opt_out)
    try:
        with _tracked_store_lock("check_start", wait_sec=STORE_LOCK_DEVICE_WAIT_SEC):
            if device.pending_start:
                device.pending_start = False
                device.status = "running"
                device.paused = False
                mutated = True
                result: dict = {"code": 0, "msg": "启动", "status": "running", "auto_start": auto}
            elif device.status == "paused":
                result = {"code": 0, "msg": "暂停", "status": "paused", "auto_start": auto}
            else:
                result = {"code": 1, "msg": "暂无指令", "auto_start": auto}
            push_rev = int(store.user_config_push_revision.get(device.owner_username, 0))
            result["config_push_revision"] = push_rev
    except StoreLockBusy:
        print(f"[{now_text()}] check_start | store_lock_busy | device_id={device_id} alias={alias}")
        return _store_lock_busy_response()
    pull_req = _peek_device_log_pull_request(device_id)
    if pull_req:
        result["log_pull_requested"] = True
        result["log_pull_max_lines"] = int(pull_req.get("max_lines") or 400)
    if mutated and not _device_auto_switch_enabled(device_id):
        _persist_client_device_to_db(device_id)
    print(f"[{now_text()}] check_start | done | device_id={device_id} result={result.get('msg')}")
    return result


@app.get("/api/pull_username")
def pull_username(
    device_id: str,
    alias: str,
    count: int | None = None,
    run_mode: str | None = None,
    tiktok_username: str | None = None,
):
    error, device = auth_or_error(device_id, alias)
    if error:
        print(f"[{now_text()}] pull_username | auth_failed | device_id={device_id} alias={alias}")
        return error

    # 设备主动拉取即下发：不要求云端先「一键启动」；顺手把内存态标为 running 便于看板展示。
    promoted = False
    try:
        with _tracked_store_lock("pull_username", wait_sec=STORE_LOCK_DEVICE_WAIT_SEC):
            if device.status != "running" or device.pending_start or device.paused:
                if device.pending_start:
                    device.pending_start = False
                if device.paused:
                    device.paused = False
                if device.status != "running":
                    device.status = "running"
                    promoted = True
    except StoreLockBusy:
        print(f"[{now_text()}] pull_username | store_lock_busy | device_id={device_id} alias={alias}")
        return _store_lock_busy_response()

    if promoted and not _device_auto_switch_enabled(device_id):
        _persist_client_device_to_db(device_id)
        try:
            with _store_lock("pull_username:promoted_rounds", wait_sec=STORE_LOCK_DEVICE_WAIT_SEC):
                store.device_pull_rounds[device_id] = 0
        except StoreLockBusy:
            print(f"[{now_text()}] pull_username | store_lock_busy_rounds_reset | device_id={device_id}")

    # 设备 owner 的运行配置（含模板合并/共享模板）应与 get_config 一致；
    # pull_username 用它来决定 pull_batch_size、休息前批次数与休息策略。
    data = _normalize_user_runtime_state(_load_user_runtime_state(device.owner_username))
    mode = run_mode if run_mode in RUN_MODES else data.get("run_mode", "growth")
    account_clean = _normalize_tiktok_username_key(tiktok_username or "")
    if not account_clean:
        account_clean = _resolve_switch_highlight_tiktok_username(device.owner_username, alias)
    active_template, eff_config, templates, _template_meta = _resolve_template_for_mode_alias(
        device.owner_username,
        data,
        mode,
        alias,
        account_clean or None,
    )

    cfg_default = int(eff_config.get("pull_batch_size", 5) or 5)
    req_count = cfg_default if count is None else int(count)
    safe_count = max(1, min(req_count, 100))

    # 休息前需完成的拉取批次数（默认 5）；旧模板未配置时回退 pull_batch_size 以兼容历史行为
    if "pull_batches_before_rest" in eff_config:
        batches_before_rest = int(eff_config.get("pull_batches_before_rest", 5) or 5)
    else:
        batches_before_rest = int(eff_config.get("pull_batch_size", 5) or 5)
    batches_before_rest = max(1, min(batches_before_rest, 100))

    # break_min=0 为合法值，不可用 `or 10`（会把 0 当成未配置而回退默认 10）
    def _cfg_int(key: str, default: int) -> int:
        if key not in eff_config:
            return default
        raw = eff_config[key]
        if raw is None:
            return default
        return int(raw)

    try:
        break_min_cfg = _cfg_int("break_min", 10)
    except Exception:
        break_min_cfg = 10
    try:
        break_max_cfg = _cfg_int("break_max", 20)
    except Exception:
        break_max_cfg = 20
    no_rest_cfg = bool(eff_config.get("no_rest"))
    # 模板「不休息」或最短/最长均为 0：关闭休息（仍按批次数清零轮次，但不返回 code=2）
    rest_enabled = not no_rest_cfg and not (break_min_cfg == 0 and break_max_cfg == 0)

    # 涨粉（growth）：设备端已在拉取前完成登录门禁；自治期凭据来自 bundle，此处不再查 assigned_account。
    if mode != "growth":
        account_logged_in = False
        conn_login = _get_db()
        try:
            acct_row = _fetch_device_assigned_account_row(conn_login, device.owner_username, alias)
            account_logged_in = bool(int((acct_row or {}).get("is_login") or 0))
        finally:
            conn_login.close()
        if not account_logged_in:
            return {"code": 1, "msg": "账号未登录，请先完成自动登录"}

    # ——按“拉取批次”触发休息：已完成 N 次有效拉取后，下一次拉取直接返回休息（不查库）——
    try:
        with _store_lock("pull_username:rest_rounds", wait_sec=STORE_LOCK_DEVICE_WAIT_SEC):
            rounds = int(store.device_pull_rounds.get(device_id, 0) or 0)
            batch_threshold_met = rounds >= batches_before_rest
            if batch_threshold_met:
                store.device_pull_rounds[device_id] = 0
    except StoreLockBusy:
        print(f"[{now_text()}] pull_username | store_lock_busy_rest | device_id={device_id}")
        return _store_lock_busy_response()

    if batch_threshold_met and rest_enabled:
        bmin = max(0, min(break_min_cfg, 24 * 60))
        bmax = max(0, min(break_max_cfg, 24 * 60))
        bmin = max(1, bmin)
        bmax = max(1, bmax)
        lo = min(bmin, bmax)
        hi = max(bmin, bmax)
        rest_minutes = random.randint(lo, hi)
        return {
            "code": 2,
            "msg": f"触发休息：{rest_minutes} 分钟（强制关闭 TikTok 后台）",
            "rest_minutes": rest_minutes,
            "force_stop_tiktok": True,
        }

    def _finish_pull_username_response(payload: dict, *, count_round: bool = False) -> dict:
        """仅在本次拉取返回有效用户名后累计休息批次数。"""
        if count_round:
            try:
                with _store_lock("pull_username:count_round", wait_sec=STORE_LOCK_DEVICE_WAIT_SEC):
                    store.device_pull_rounds[device_id] = int(store.device_pull_rounds.get(device_id, 0) or 0) + 1
            except StoreLockBusy:
                print(f"[{now_text()}] pull_username | store_lock_busy_count_round | device_id={device_id}")
        return payload

    selected: List[str] = []
    conn = _get_db()
    try:
        if mode == "live":
            rows = conn.execute(
                "SELECT username FROM live_customer ORDER BY usage_count ASC, created_at ASC LIMIT ?",
                (safe_count,),
            ).fetchall()
            selected = [r[0] for r in rows]
            if not selected:
                return {"code": 1, "msg": "暂无主播访问数据"}
            current_time = now_text()
            for username in selected:
                conn.execute(
                    "UPDATE live_customer SET usage_count = usage_count + 1, last_visit_at = ? WHERE username = ?",
                    (current_time, username),
                )
            conn.commit()
        elif mode == "direct":
            rows = conn.execute(
                "SELECT rowid, username FROM follower_queue WHERE COALESCE(consume_status, 'pending') = 'pending' ORDER BY created_at ASC, rowid ASC LIMIT ?",
                (safe_count,),
            ).fetchall()
            selected_rowids = [r[0] for r in rows]
            selected = [r[1] for r in rows]
            if not selected:
                return {"code": 1, "msg": "暂无直客访问数据（follower_queue为空）"}
            consumed_at = now_text()
            conn.executemany(
                "UPDATE follower_queue SET consume_status = 'sent', consumed_at = ?, consumed_mode = 'direct', consumed_by = ? WHERE rowid = ?",
                [(consumed_at, alias, rid) for rid in selected_rowids],
            )
            conn.commit()
        else:
            # 涨粉（growth）。mutual（互点引流）设备端不调用 pull_username，搜索词来自模板 mutual_traffic_target_texts。
            # 临时：从 tiktok_roots 随机只读，不写库。
            selected = _pull_growth_usernames_random_from_roots(conn, safe_count)
            if not selected:
                return {"code": 1, "msg": "暂无根用户（tiktok_roots 为空）"}
            # --- 原涨粉队列消费（恢复时取消下方注释，并注释掉上面随机下发）---
            # rows = conn.execute(
            #     "SELECT id, username FROM mobile_pending_usernames WHERE consume_status = 'pending' ORDER BY created_at, id LIMIT ?",
            #     (safe_count,),
            # ).fetchall()
            # if not rows:
            #     return {"code": 1, "msg": "暂无待执行的用户名"}
            # selected_ids = [r[0] for r in rows]
            # selected = [r[1] for r in rows]
            # bump_usage_for_usernames(conn, selected)
            # conn.executemany(
            #     "DELETE FROM mobile_pending_usernames WHERE id = ?",
            #     [(rid,) for rid in selected_ids],
            # )
            # conn.commit()
    finally:
        conn.close()

    print(f"[{now_text()}] 设备别名：{alias} | 拉取用户名({mode})：{selected}")
    return _finish_pull_username_response({"code": 0, "usernames": selected}, count_round=True)


@app.post("/api/report_mutual_copied_username")
def report_mutual_copied_username(payload: ReportMutualCopiedUsernameRequest):
    """互点复制作者用户名入库。表中唯一字段仅 username（主键）；created_at 首次写入，usage_count 按次累加。"""
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    normalized = _normalize_mutual_clip_username(payload.username or "")
    if not normalized:
        return {"code": 1, "msg": "用户名为空或格式无效"}

    # 临时：互点复制用户名不写库（恢复时取消下方注释，并删掉本 return）。
    return {"code": 0, "msg": "临时跳过入库", "username": normalized, "usage_count": 0}
    # conn = _get_db()
    # try:
    #     ts = now_text()
    #     conn.execute(
    #         """
    #         INSERT INTO mutual_click_copied_usernames (username, created_at, usage_count)
    #         VALUES (?, ?, 1)
    #         ON CONFLICT(username) DO UPDATE SET
    #             usage_count = mutual_click_copied_usernames.usage_count + 1
    #         """,
    #         (normalized, ts),
    #     )
    #     conn.commit()
    #     row = conn.execute(
    #         "SELECT username, created_at, usage_count FROM mutual_click_copied_usernames WHERE username = ?",
    #         (normalized,),
    #     ).fetchone()
    #     uc = int(row["usage_count"]) if row else 1
    #     return {"code": 0, "msg": "已记录", "username": normalized, "usage_count": uc}
    # finally:
    #     conn.close()


@app.post("/api/report_result")
def report_result(payload: ReportResultRequest):
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    line = f"[{now_text()}] {payload.result}"
    print(f"[{now_text()}] 设备别名：{payload.alias} | 内容：{payload.result}")

    effective_rm = payload.run_mode if payload.run_mode in RUN_MODES else None
    with store.lock:
        _rm = effective_rm if effective_rm is not None else store.run_mode
    if _rm != "growth":
        mark_mobile_username_status_by_report(payload.result)
    mark_follower_queue_direct_status_by_report(payload.result)
    mark_follower_queue_process_status_by_report(payload.result)

    status_err = False
    _append_device_log(payload.device_id, line)
    if "异常" in payload.result:
        with store.lock:
            device.status = "error"
        status_err = True
    if status_err:
        _persist_client_device_to_db(payload.device_id, force=True)

    return {"code": 0, "msg": "结果接收成功"}


# mode_wait 挂起上限：须低于常见 nginx proxy_read_timeout（默认 60s），否则网关先返回 HTML 504。
_MODE_WAIT_SERVER_MAX_SEC = 55


@app.get("/api/device/mode_wait")
async def device_mode_wait(
    device_id: str,
    alias: str,
    since_event_id: int = 0,
    timeout_sec: int = 45,
):
    """长轮询：等待自动切号 ENTER/EXIT 等模式事件（自治 P0 控制面）。"""
    error, _device = auth_or_error(device_id, alias)
    if error:
        return error
    safe_timeout = max(5, min(int(timeout_sec or 45), _MODE_WAIT_SERVER_MAX_SEC))
    deadline = time.monotonic() + float(safe_timeout)
    while time.monotonic() < deadline:
        pull_req = await asyncio.to_thread(_peek_device_log_pull_request, device_id)
        if pull_req:
            enabled = await asyncio.to_thread(_device_auto_switch_enabled_resolved, device_id)
            return {
                "code": 0,
                "event": None,
                "msg": "log_pull",
                "log_pull_requested": True,
                "log_pull_max_lines": int(pull_req.get("max_lines") or 400),
                # 返回时现读开关，避免长轮询期间管理台已开启但携带轮询开始时的陈旧 false。
                "auto_switch_enabled": enabled,
            }
        event = await asyncio.to_thread(_fetch_pending_device_mode_event, device_id, since_event_id)
        if event:
            print(
                f"[{now_text()}] device_mode.wait | hit | device_id={device_id} "
                f"event_id={event.get('event_id')} type={event.get('type')}"
            )
            enabled = await asyncio.to_thread(_device_auto_switch_enabled_resolved, device_id)
            return {
                "code": 0,
                "event": event,
                "auto_switch_enabled": enabled,
            }
        if _APP_SHUTDOWN_EVENT.is_set():
            break
        await asyncio.sleep(1.0)
    enabled = await asyncio.to_thread(_device_auto_switch_enabled_resolved, device_id)
    return {
        "code": 0,
        "event": None,
        "msg": "none",
        "auto_switch_enabled": enabled,
    }


@app.post("/api/device/mode_ack")
def device_mode_ack(payload: DeviceModeAckRequest):
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    _ack_device_mode_event(
        payload.device_id,
        payload.event_id,
        payload.phase,
        session_id=payload.session_id,
    )
    # 尽力投递模型：ACK 失败不影响控制面，设备不依赖回执重试。
    return {"code": 0, "msg": "已记录", "event_id": payload.event_id, "phase": payload.phase}


@app.post("/api/device_ops/poll")
def device_ops_poll(payload: DeviceOpPollRequest):
    if not _device_ops_poll_try_acquire(payload.device_id):
        return {"code": 1, "msg": "暂无待执行设备任务"}
    print(f"[{now_text()}] device_ops.poll | enter | device_id={payload.device_id} alias={payload.alias}")
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        print(f"[{now_text()}] device_ops.poll | auth_failed | {error}")
        return error

    op = _next_device_op(payload.device_id)
    if not op:
        print(f"[{now_text()}] device_ops.poll | no_task")
        return {"code": 1, "msg": "暂无待执行设备任务"}
    print(f"[{now_text()}] device_ops.poll | return_task | type={op.get('type')}")
    return {"code": 0, "msg": "ok", "op": op}


_TIKTOK_EMAIL_CODE_PATTERNS = (
    re.compile(r"(?im)^\s*(主题|subject)\s*[:：]\s*(\d{6})\b"),
    re.compile(r"(?i)\b6[- ]?digit\s+code\b[^\d]{0,24}(\d{6})\b"),
    re.compile(r"\b(\d{6})\b"),
)


def _extract_tiktok_email_code(body_text: str) -> str:
    """与 Android MainActivity.extractEmailCode 保持一致的 6 位验证码解析。"""
    normalized = (
        (body_text or "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
    )
    normalized = re.sub(r"[\u00A0\t]", " ", normalized)
    for pat in _TIKTOK_EMAIL_CODE_PATTERNS:
        m = pat.search(normalized)
        if not m:
            continue
        code = (m.group(2) if m.lastindex and m.lastindex >= 2 else "").strip()
        if not code:
            code = (m.group(1) or "").strip()
        if re.fullmatch(r"\d{6}", code):
            return code
    raise ValueError("未识别到 6 位验证码")


def _fetch_tiktok_email_code_from_api(api_url: str) -> tuple[int, str, str | None]:
    url = (api_url or "").strip()
    if not url.startswith(("http://", "https://")):
        return 1, "API 链接无效", None
    try:
        response = requests.get(url, timeout=15)
    except Exception as exc:
        return 1, f"请求邮箱 API 失败: {exc}", None
    if response.status_code != 200:
        return 1, f"HTTP {response.status_code}", None
    body = (response.text or "").strip()
    if not body:
        return 1, "返回内容为空", None
    try:
        code = _extract_tiktok_email_code(body)
    except ValueError as exc:
        return 1, str(exc), None
    return 0, "ok", code


def _try_fetch_tiktok_email_code_via_mail_module(owner_username: str, email: str) -> tuple[int, str, str | None]:
    try:
        from mail_integration import fetch_tiktok_email_code_via_mail_module
    except ImportError:
        return 1, "邮箱管理模块未集成", None
    return fetch_tiktok_email_code_via_mail_module(
        owner_username,
        email,
        _extract_tiktok_email_code,
    )


def _tiktok_email_code_response(
    device_id: str,
    alias: str,
    api_url: str,
    *,
    owner_username: str | None = None,
    account_email: str | None = None,
) -> dict:
    owner = str(owner_username or "").strip()
    email = str(account_email or "").strip()
    mail_msg = ""

    if owner and email:
        mail_code, mail_msg, mail_email_code = _try_fetch_tiktok_email_code_via_mail_module(owner, email)
        if mail_code == 0 and mail_email_code:
            print(
                f"[{now_text()}] device_tiktok.email_code | ok_mail_module | device={device_id} "
                f"alias={alias} email={email} | code={mail_email_code}"
            )
            return {"code": 0, "msg": "ok", "email_code": mail_email_code, "source": "mail_module"}
        print(
            f"[{now_text()}] device_tiktok.email_code | mail_module_fallback | device={device_id} "
            f"alias={alias} email={email} | {mail_msg}"
        )

    legacy_url = (api_url or "").strip()
    if not legacy_url:
        if not owner:
            fail_msg = "设备未关联管理账号，无法通过邮箱管理模块获取验证码"
        elif not email:
            fail_msg = "邮箱为空且 API 为空，无法获取验证码"
        elif mail_msg:
            fail_msg = f"邮箱管理模块：{mail_msg}（且 API 为空）"
        else:
            fail_msg = "邮箱管理模块不可用且 API 为空，无法获取验证码"
        return {
            "code": 1,
            "msg": fail_msg,
            "email_code": None,
        }

    code, msg, email_code = _fetch_tiktok_email_code_from_api(legacy_url)
    if code != 0 or not email_code:
        print(
            f"[{now_text()}] device_tiktok.email_code | failed | device={device_id} "
            f"alias={alias} | {msg}"
        )
        return {"code": code, "msg": msg, "email_code": None}
    print(
        f"[{now_text()}] device_tiktok.email_code | ok_api | device={device_id} "
        f"alias={alias} | code={email_code}"
    )
    return {"code": 0, "msg": "ok", "email_code": email_code, "source": "api"}


def _effective_device_editor_alias(row: dict[str, Any]) -> str:
    aa = str(row.get("assigned_alias") or "").strip()
    da = str(row.get("device_alias") or "").strip()
    return aa if aa else da


def _fetch_device_assigned_account_row(conn: sqlite3.Connection, owner_username: str, alias: str) -> dict[str, Any] | None:
    """按设备别名桶解析当前分配账号（须有唯一切号高亮，或桶内仅一条记录）。"""
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner or not alias_key:
        return None
    dev_rows = conn.execute(
        """
        SELECT row_index, username, assigned_alias, device_alias, switch_highlight
        FROM device_editor_rows
        WHERE owner_username = ? AND TRIM(username) <> ''
        ORDER BY row_index ASC
        """,
        (owner,),
    ).fetchall()
    matched = [
        dict(r)
        for r in dev_rows
        if _normalize_alias_key(_effective_device_editor_alias(dict(r))) == alias_key
    ]
    pick = _pick_device_editor_bucket_row(matched, owner, alias_key)
    if not pick:
        return None
    username = str(pick.get("username") or "").strip()
    if not username:
        return None
    alias_err = _validate_username_device_effective_alias(conn, owner, username, alias_key)
    if alias_err:
        return None
    acct = conn.execute(
        """
        SELECT row_index, username, password, email, email_password, api, is_login, login_time,
               basic_profile_modified
        FROM account_editor_rows
        WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(?)
        LIMIT 1
        """,
        (owner, username),
    ).fetchone()
    return dict(acct) if acct else None


def _account_bundle_fields_for_username(
    conn: sqlite3.Connection, owner_username: str, username: str
) -> dict[str, Any]:
    """自动切号 bundle 内账号：合并 account_editor_rows 凭据与登录态。"""
    owner = str(owner_username or "").strip()
    uname = str(username or "").strip()
    empty: dict[str, Any] = {
        "account_row_index": 0,
        "email": "",
        "password": "",
        "email_password": "",
        "api": "",
        "is_login": False,
        "login_time": "",
        "basic_profile_modified": "否",
        "mail_module_code_available": False,
    }
    if not owner or not uname:
        return empty
    acct = conn.execute(
        """
        SELECT row_index, username, password, email, email_password, api, is_login, login_time,
               basic_profile_modified
        FROM account_editor_rows
        WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(?)
        LIMIT 1
        """,
        (owner, uname),
    ).fetchone()
    if not acct:
        return empty
    row = dict(acct)
    account_email = str(row.get("email") or "").strip()
    mail_module_code_available = False
    try:
        from mail_integration import mail_module_can_fetch_code

        mail_module_code_available = mail_module_can_fetch_code(owner, account_email)
    except ImportError:
        mail_module_code_available = False
    return {
        "account_row_index": int(row.get("row_index") or 0),
        "email": account_email,
        "password": str(row.get("password") or "").strip(),
        "email_password": str(row.get("email_password") or "").strip(),
        "api": str(row.get("api") or "").strip(),
        "is_login": bool(int(row.get("is_login") or 0)),
        "login_time": str(row.get("login_time") or "").strip(),
        "basic_profile_modified": _normalize_switch_highlight(row.get("basic_profile_modified")),
        "mail_module_code_available": mail_module_code_available,
    }


def _fetch_device_alias_account_form_items(
    conn: sqlite3.Connection, owner_username: str, alias: str
) -> list[dict[str, Any]]:
    """设备端账号表单：仅返回当前别名桶内切号高亮（或单条桶回退）的账号凭据。"""
    row = _fetch_device_assigned_account_row(conn, owner_username, alias)
    if not row:
        return []
    username = str(row.get("username") or "").strip()
    if not username:
        return []
    return [
        {
            "device_row_index": None,
            "account_row_index": int(row.get("row_index") or 0),
            "username": username,
            "password": str(row.get("password") or "").strip(),
            "email": str(row.get("email") or "").strip(),
            "email_password": str(row.get("email_password") or "").strip(),
            "api": str(row.get("api") or "").strip(),
            "is_login": bool(int(row.get("is_login") or 0)),
            "login_time": str(row.get("login_time") or "").strip(),
            "region": "",
            "switch_highlight": "是",
            "basic_profile_modified": _normalize_switch_highlight(row.get("basic_profile_modified")),
        }
    ]


@app.get("/api/device/alias_account_form")
def device_alias_account_form(device_id: str, alias: str):
    """设备端：按当前绑定别名拉取切号高亮账号表单（供主界面复制凭据）。"""
    error, device = auth_or_error(device_id, alias)
    if error:
        return error
    owner = str(getattr(device, "owner_username", "") or "").strip()
    if not owner:
        return {"code": 1, "msg": "设备未关联管理账号"}
    conn = _get_db()
    try:
        items = _fetch_device_alias_account_form_items(conn, owner, alias)
        if not items:
            return {
                "code": 1,
                "msg": "未找到该别名下已高亮的分配账号（同桶多条时需有且仅有一条切号高亮，且账号分配别名须一致）",
            }
        return {"code": 0, "msg": "ok", "items": items}
    finally:
        conn.close()


@app.get("/api/gallery_library/random")
def device_gallery_library_random(device_id: str, alias: str):
    """设备端：从所属管理账号的图库随机取一张图片（修改基本资料头像用）。"""
    error, device = auth_or_error(device_id, alias)
    if error:
        return error
    owner = str(getattr(device, "owner_username", "") or "").strip()
    if not owner:
        return {"code": 1, "msg": "设备未关联管理账号"}
    item = _gallery_library_random_media_payload(owner)
    if not item:
        return {"code": 1, "msg": "管理台图库为空，请先在「图库素材下发」页上传图片"}
    return {"code": 0, "msg": "ok", "item": item}


@app.get("/api/device/assigned_account")
def device_assigned_account(device_id: str, alias: str):
    """设备端：拉取当前别名桶下分配账号及登录状态（切号后自动登录用）。"""
    error, device = auth_or_error(device_id, alias)
    if error:
        return error
    owner = str(getattr(device, "owner_username", "") or "").strip()
    if not owner:
        return {"code": 1, "msg": "设备未关联管理账号"}
    conn = _get_db()
    try:
        row = _fetch_device_assigned_account_row(conn, owner, alias)
        if not row:
            return {
                "code": 1,
                "msg": "未找到该别名下已高亮的分配账号（同桶多条时需有且仅有一条切号高亮，且账号分配别名须一致）",
            }
        account_email = str(row.get("email") or "").strip()
        mail_module_code_available = False
        try:
            from mail_integration import mail_module_can_fetch_code

            mail_module_code_available = mail_module_can_fetch_code(owner, account_email)
        except ImportError:
            mail_module_code_available = False
        return {
            "code": 0,
            "msg": "ok",
            "row_index": int(row.get("row_index") or 0),
            "username": str(row.get("username") or "").strip(),
            "email": account_email,
            "password": str(row.get("password") or "").strip(),
            "email_password": str(row.get("email_password") or "").strip(),
            "api": str(row.get("api") or "").strip(),
            "is_login": bool(int(row.get("is_login") or 0)),
            "login_time": str(row.get("login_time") or "").strip(),
            "basic_profile_modified": _normalize_switch_highlight(row.get("basic_profile_modified")),
            "mail_module_code_available": mail_module_code_available,
        }
    finally:
        conn.close()


@app.post("/api/device/assigned_account/mark_login")
def device_assigned_account_mark_login(payload: DeviceAssignedAccountMarkLoginRequest):
    """设备端：自动登录成功后标记账号已登录。"""
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    owner = str(getattr(device, "owner_username", "") or "").strip()
    username = str(payload.username or "").strip()
    if not owner or not username:
        return {"code": 1, "msg": "参数无效"}
    conn = _get_db()
    try:
        now = now_text()
        cur = conn.execute(
            """
            UPDATE account_editor_rows
            SET is_login = 1, login_time = ?, updated_at = ?
            WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(?)
            """,
            (now, now, owner, username),
        )
        conn.commit()
        if int(cur.rowcount or 0) < 1:
            return {"code": 1, "msg": "未找到对应账号记录"}
        _append_device_log(
            payload.device_id,
            f"[{now}] 自动登录 | is_login=是 username={username}",
        )
        return {"code": 0, "msg": "已标记登录", "login_time": now}
    finally:
        conn.close()


@app.post("/api/device/assigned_account/update_username")
def device_assigned_account_update_username(payload: DeviceAssignedAccountUpdateUsernameRequest):
    """设备端：修改基本资料时检测到 TikTok 用户名与分配账号不一致，同步更新账号表与设备表。"""
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    owner = str(getattr(device, "owner_username", "") or "").strip()
    if not owner:
        return {"code": 1, "msg": "设备未关联管理账号"}
    new_username = _normalize_mutual_clip_username(payload.new_username or "")
    if not new_username:
        return {"code": 1, "msg": "新用户名为空或格式无效"}
    conn = _get_db()
    try:
        row = _fetch_device_assigned_account_row(conn, owner, payload.alias)
        if not row:
            return {"code": 1, "msg": "未找到该别名分配的 TikTok 账号"}
        old_username = str(row.get("username") or "").strip()
        row_index = int(row.get("row_index") or 0)
        if not old_username:
            return {"code": 1, "msg": "当前分配账号用户名为空"}
        if new_username.lower() == old_username.lower():
            return {
                "code": 0,
                "msg": "无需更新",
                "username": old_username,
                "updated": False,
            }
        conflict = conn.execute(
            """
            SELECT 1 FROM account_editor_rows
            WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(?)
              AND row_index <> ?
            LIMIT 1
            """,
            (owner, new_username, row_index),
        ).fetchone()
        if conflict:
            return {"code": 1, "msg": "新用户名已被其他账号占用"}
        now = now_text()
        device_alias = _normalized_alias(payload.alias)
        acct_cur = conn.execute(
            """
            UPDATE account_editor_rows
            SET username = ?,
                assigned_alias = CASE
                    WHEN TRIM(COALESCE(assigned_alias, '')) <> '' THEN assigned_alias
                    ELSE ?
                END,
                updated_at = ?
            WHERE owner_username = ? AND row_index = ?
            """,
            (new_username, device_alias, now, owner, row_index),
        )
        if int(acct_cur.rowcount or 0) < 1:
            return {"code": 1, "msg": "账号表更新失败"}
        alias_key = _normalize_alias_key(payload.alias)
        dev_rows = conn.execute(
            """
            SELECT row_index, username, assigned_alias, device_alias
            FROM device_editor_rows
            WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(?)
            """,
            (owner, old_username),
        ).fetchall()
        dev_updated = 0
        for dr in dev_rows:
            d = dict(dr)
            if _normalize_alias_key(_effective_device_editor_alias(d)) != alias_key:
                continue
            cur = conn.execute(
                """
                UPDATE device_editor_rows
                SET username = ?, updated_at = ?
                WHERE owner_username = ? AND row_index = ?
                """,
                (new_username, now, owner, int(d["row_index"])),
            )
            dev_updated += int(cur.rowcount or 0)
        conn.commit()
        _append_device_log(
            payload.device_id,
            f"[{now}] 修改基本资料 | 用户名同步 {old_username} → {new_username}（设备表 {dev_updated} 行）",
        )
        return {
            "code": 0,
            "msg": "已更新用户名",
            "username": new_username,
            "old_username": old_username,
            "updated": True,
            "device_rows_updated": dev_updated,
        }
    finally:
        conn.close()


def _resolve_device_tiktok_account_email(
    owner_username: str,
    alias: str,
    payload_email: str,
) -> str:
    email = str(payload_email or "").strip()
    if email:
        return email
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner or not alias_key:
        return ""
    conn = _get_db()
    try:
        row = _fetch_device_assigned_account_row(conn, owner, alias)
    finally:
        conn.close()
    if not row:
        return ""
    return str(row.get("email") or "").strip()


@app.post("/api/device/tiktok/email_code")
def device_tiktok_email_code(payload: DeviceTikTokEmailCodeRequest):
    """设备端拉取 TikTok 邮箱验证码：优先邮箱管理模块，失败则回退旧 API 链接。"""
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    owner = str(getattr(device, "owner_username", "") or "").strip()
    account_email = _resolve_device_tiktok_account_email(owner, payload.alias, payload.email)
    return _tiktok_email_code_response(
        payload.device_id,
        payload.alias,
        payload.api_url,
        owner_username=owner,
        account_email=account_email,
    )


@app.post("/api/admin/device_tiktok/email_code")
def admin_device_tiktok_email_code(
    payload: DeviceTikTokEmailCodeRequest,
    authorization: str | None = Header(default=None),
):
    """管理台/已登录用户代设备拉取 TikTok 邮箱验证码。"""
    err, device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    owner = str(getattr(device, "owner_username", "") or "").strip()
    return _tiktok_email_code_response(
        payload.device_id,
        payload.alias,
        payload.api_url,
        owner_username=owner,
        account_email=payload.email,
    )


@app.post("/api/device/tk_pending/acquire")
def device_tk_pending_acquire(payload: DeviceTkPendingAcquireRequest):
    """设备端：从待注册 TK 邮箱池领取一个邮箱，并立即转入邮箱账户管理。"""
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    owner = str(getattr(device, "owner_username", "") or "").strip()
    if not owner:
        return {"code": 1, "msg": "设备未关联管理账号"}
    try:
        from mail_integration import take_pending_tk_account_via_mail_module
    except ImportError:
        return {"code": 1, "msg": "邮箱管理模块未集成"}
    code, msg, data = take_pending_tk_account_via_mail_module(owner, payload.email)
    if code != 0 or not data:
        return {"code": 1, "msg": msg, "available": False, "promoted": False}
    account = data.get("account") if isinstance(data.get("account"), dict) else {}
    return {
        "code": 0,
        "msg": "已领取并转入邮箱账户管理",
        "available": True,
        "promoted": True,
        "taken": True,
        "email": str(account.get("email") or data.get("email") or "").strip(),
        "password": str(account.get("password") or "").strip(),
        "client_id": str(account.get("client_id") or "").strip(),
        "refresh_token": str(account.get("refresh_token") or "").strip(),
    }


@app.post("/api/device/tk_pending/complete")
def device_tk_pending_complete(payload: DeviceTkPendingCompleteRequest):
    """设备端：TK 注册成功后，将待注册邮箱转入邮箱账户管理。"""
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    owner = str(getattr(device, "owner_username", "") or "").strip()
    if not owner:
        return {"code": 1, "msg": "设备未关联管理账号"}
    claim_id = str(payload.claim_id or "").strip()
    email = str(payload.email or "").strip()
    if not claim_id and not email:
        return {"code": 1, "msg": "claim_id 或 email 不能为空"}
    try:
        from mail_integration import complete_pending_tk_account_via_mail_module
    except ImportError:
        return {"code": 1, "msg": "邮箱管理模块未集成"}
    code, msg, data = complete_pending_tk_account_via_mail_module(
        owner,
        email=email,
        claim_id=claim_id,
    )
    if code != 0 or not data:
        return {"code": 1, "msg": msg, "promoted": False}
    return {
        "code": 0,
        "msg": "已转入邮箱账户管理",
        "promoted": True,
        "email": str(data.get("email") or email).strip(),
        "claim_id": str(data.get("claimId") or data.get("claim_id") or claim_id).strip(),
    }


@app.post("/api/admin/device_tiktok/mutual_click_start")
def admin_device_tiktok_mutual_click_start(payload: MutualClickRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    _resume_device_for_cloud_op(
        payload.device_id,
        f"[{now_text()}] 云端后台 | 已下发互点指令，设备由暂停切换为 running（alias={payload.alias}）",
    )
    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "mutual_click_start",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )
    return {
        "code": 0,
        "msg": "已下发设备端任务：进入互点模式",
        "op_id": op_id,
        "auto_start_opt_out": bool(getattr(_device, "auto_start_opt_out", False)),
    }


@app.post("/api/admin/device_tiktok/mutual_click_comment_only_start")
def admin_device_tiktok_mutual_click_comment_only_start(
    payload: MutualClickRequest, authorization: str | None = Header(default=None)
):
    """互点「仅评论」：与完整互点相同的 teamwork 进作品与点赞/收藏/评论，不下发左滑与作者主页链路。"""
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    _resume_device_for_cloud_op(
        payload.device_id,
        f"[{now_text()}] 云端后台 | 已下发互点仅评论指令，设备由暂停切换为 running（alias={payload.alias}）",
    )
    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "mutual_click_comment_only_start",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )
    return {
        "code": 0,
        "msg": "已下发设备端任务：进入互点仅评论模式",
        "op_id": op_id,
        "auto_start_opt_out": bool(getattr(_device, "auto_start_opt_out", False)),
    }


@app.post("/api/admin/device_tiktok/mutual_click_follow_only_start")
def admin_device_tiktok_mutual_click_follow_only_start(
    payload: MutualClickRequest, authorization: str | None = Header(default=None)
):
    """互点「仅关注」：独立方案，进 teamwork Videos 后仅打开评论区（区内 TODO），不受模板引流动作勾选影响。"""
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    _resume_device_for_cloud_op(
        payload.device_id,
        f"[{now_text()}] 云端后台 | 已下发互点仅关注指令，设备由暂停切换为 running（alias={payload.alias}）",
    )
    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "mutual_click_follow_only_start",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )
    return {
        "code": 0,
        "msg": "已下发设备端任务：进入互点仅关注模式",
        "op_id": op_id,
        "auto_start_opt_out": bool(getattr(_device, "auto_start_opt_out", False)),
    }


@app.post("/api/admin/device_tiktok/publish_post_start")
def admin_device_tiktok_publish_post_start(payload: MutualClickRequest, authorization: str | None = Header(default=None)):
    blocked = _reject_if_auto_switch_manual_ops(device_id=payload.device_id, alias=payload.alias)
    if blocked:
        return blocked
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    _resume_device_for_cloud_op(
        payload.device_id,
        f"[{now_text()}] 云端后台 | 已下发发作品指令，设备由暂停切换为 running（alias={payload.alias}；素材就绪后再暂停涨粉）",
    )
    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "publish_post_start",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )
    return {
        "code": 0,
        "msg": "已下发设备端任务：发作品入口（素材就绪后设备端再暂停涨粉）",
        "op_id": op_id,
        "auto_start_opt_out": bool(getattr(_device, "auto_start_opt_out", False)),
    }


@app.post("/api/device_ops/report")
def device_ops_report(payload: DeviceOpReportRequest):
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    if _device_op_report_is_duplicate(payload.op_id, payload.status):
        print(
            f"[{now_text()}] device_ops.report | duplicate_ignored | op_id={payload.op_id} "
            f"status={payload.status}"
        )
        return {"code": 0, "msg": "重复回报已忽略", "duplicate": True}

    data = payload.data or {}
    result = {
        "op_id": payload.op_id,
        "status": payload.status,
        "message": payload.message,
        "data": data,
        "reported_at": now_text(),
    }
    _save_device_op_result(payload.op_id, result)
    backup_skipped = bool(data.get("backup_skipped")) if isinstance(data, dict) else False
    if (
        payload.status == "success"
        and data.get("op_type") == "tiktok_backup_and_clear"
        and not backup_skipped
    ):
        backup_record_id = data.get("backup_record_id")
        username = str(data.get("username") or "").strip().lstrip("@")
        mobile_alias = str(data.get("mobile_alias") or "").strip()
        backup_path = str(data.get("backup_path") or "").strip()
        backup_name = str(data.get("backup_name") or "").strip() or None
        if username and backup_name and not _backup_name_contains_username(backup_name, username):
            print(
                f"[{now_text()}] backup_name 未包含 username | user={username} name={backup_name} device={payload.device_id}"
            )
        if backup_record_id:
            try:
                rid = int(backup_record_id)
            except (TypeError, ValueError):
                rid = 0
            if rid > 0:
                conn = _get_db()
                try:
                    now = now_text()
                    conn.execute(
                        """
                        UPDATE device_editor_rows
                        SET backup_path = ?, backup_name = ?, backup_time = ?, backup_status = ?, updated_at = ?
                        WHERE row_index = ?
                        """,
                        (backup_path, backup_name or "", now, "已备份", now, rid),
                    )
                    conn.commit()
                finally:
                    conn.close()
        elif username and mobile_alias and backup_path:
            _patch_backup_record_device_result(
                username,
                mobile_alias,
                backup_path=backup_path,
                backup_name=backup_name,
                video_count=None,
                follower_count=None,
                new_follower_count=None,
            )

    op_logs = data.get("operation_logs") if isinstance(data, dict) else None
    if isinstance(op_logs, list) and op_logs:
        cleaned = [str(x).strip() for x in op_logs if str(x).strip()]
        if cleaned:
            for item in cleaned:
                _append_device_log(payload.device_id, item)

    if payload.status == "success" and isinstance(data, dict):
        pe_writeback = _apply_profile_edit_device_op_report(
            str(device.owner_username or "").strip(),
            payload.alias,
            data,
        )
        if pe_writeback.get("updated"):
            alias_key = _normalize_alias_key(payload.alias)
            print(
                f"[{now_text()}] account_editor_rows | basic_profile_modified=是 "
                f"| owner={device.owner_username} alias={alias_key} "
                f"username={pe_writeback.get('username')} rowcount={pe_writeback.get('updated')}"
            )

    if (
        payload.status == "success"
        and data.get("op_type") == "tiktok_backup_and_clear"
        and not backup_skipped
    ):
        username = str(data.get("username") or "").strip().lstrip("@")
        mobile_alias = str(data.get("mobile_alias") or payload.alias or "").strip()
        video_count = data.get("video_count")
        follower_count = data.get("follower_count")
        new_follower_count = data.get("new_follower_count")
        backup_path = str(data.get("backup_path") or "").strip()
        backup_name = str(data.get("backup_name") or "").strip() or None
        old_backup_path = str(data.get("old_backup_path") or "").strip()

        vc = int(video_count) if isinstance(video_count, int) else None
        fc = int(follower_count) if isinstance(follower_count, int) else None
        nfc = int(new_follower_count) if isinstance(new_follower_count, int) else None

        if backup_path and mobile_alias:
            if not username:
                latest = _get_latest_backup_record_for_alias(mobile_alias)
                if latest:
                    username = str(latest.get("username") or "").strip().lstrip("@")

            rec = None
            if username and mobile_alias:
                rec = _get_backup_record_by_key(username, mobile_alias)
            if not rec and mobile_alias:
                rec = _get_latest_backup_record_for_alias(mobile_alias)
                if rec:
                    username = str(rec.get("username") or "").strip().lstrip("@")

            if username and rec:
                db_alias = str(rec.get("assigned_alias") or rec.get("device_alias") or "").strip()
                key_alias = db_alias if db_alias else mobile_alias
                previous_path = str(rec.get("backup_path") or "").strip()
                patched = _patch_backup_record_device_result(
                    username,
                    key_alias,
                    backup_path=backup_path,
                    backup_name=backup_name,
                    video_count=vc,
                    follower_count=fc,
                    new_follower_count=nfc,
                )
                if patched == 0 and mobile_alias:
                    _patch_latest_backup_row_by_alias(
                        mobile_alias,
                        backup_path=backup_path,
                        backup_name=backup_name,
                        video_count=vc,
                        follower_count=fc,
                        new_follower_count=nfc,
                    )
                if previous_path and previous_path != backup_path and not _looks_like_android_device_path(previous_path):
                    _delete_file_safely(previous_path)
                if (
                    old_backup_path
                    and old_backup_path not in {backup_path, previous_path}
                    and not _looks_like_android_device_path(old_backup_path)
                ):
                    _delete_file_safely(old_backup_path)

    cut_switch_auto_start: dict[str, bool] = {
        "opt_out_cleared": False,
        "auto_start_resumed": False,
    }
    op_type_reported = str(data.get("op_type") or "").strip() if isinstance(data, dict) else ""
    preserve_opt_out = bool(data.get("preserve_auto_start_opt_out")) if isinstance(data, dict) else False
    if (
        isinstance(data, dict)
        and not preserve_opt_out
        and op_type_reported in _TRANSIENT_AUTO_START_RESTORE_OP_TYPES
    ):
        cut_switch_auto_start["opt_out_cleared"] = _restore_device_auto_start_participation(
            payload.device_id,
            payload.alias or "",
            reason=f"op={op_type_reported} status={payload.status}",
        )
    owner_u = str(device.owner_username or "").strip()
    if isinstance(data, dict) and owner_u:
        _auto_switch_apply_publish_outcome_report(
            owner_u,
            payload.alias or "",
            payload.status or "",
            data,
        )
        op_type = op_type_reported
        if payload.status == "success":
            if op_type in ("tiktok_backup_and_clear", "tiktok_clear"):
                _device_ops_apply_cut_switch_after_backup(owner_u, data)
                # 切号后自动登录与恢复涨粉由设备端 finishCutSwitchAfterSuccess 统一处理，
                # 避免云端过早 pending_start 导致涨粉与自动登录并发。
            elif op_type == "tiktok_restore":
                _device_ops_apply_cut_switch_after_restore(owner_u, data)
        else:
            if op_type == "tiktok_restore":
                _device_ops_apply_restore_failure(owner_u, data)
            if op_type in ("tiktok_backup_and_clear", "tiktok_restore", "tiktok_clear"):
                _device_ops_rollback_cut_switch_highlight(owner_u, data)

    with store.lock:
        dev = store.devices.get(payload.device_id)
        auto_start_flag = bool(
            dev
            and _user_auto_start_enabled(str(dev.owner_username or ""))
            and not bool(getattr(dev, "auto_start_opt_out", False))
        )
        opt_out_now = bool(getattr(dev, "auto_start_opt_out", False)) if dev else False
    return {
        "code": 0,
        "msg": "设备任务结果已接收",
        "auto_start_opt_out": opt_out_now,
        "auto_start": auto_start_flag,
        "cut_switch_auto_start_resumed": bool(cut_switch_auto_start.get("auto_start_resumed")),
        "cut_switch_opt_out_cleared": bool(cut_switch_auto_start.get("opt_out_cleared")),
    }


@app.post("/api/device_logs/sync")
def device_logs_sync(payload: DeviceLogSyncRequest):
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    cleaned = [str(x).strip() for x in (payload.logs or []) if str(x).strip()]
    if payload.replace:
        _replace_device_log(payload.device_id, cleaned)
        _complete_device_log_pull(payload.device_id)
        return {"code": 0, "msg": "日志已覆盖同步", "count": len(cleaned), "replace": True}

    if not cleaned:
        return {"code": 0, "msg": "无新增日志"}

    for item in cleaned:
        _append_device_log(payload.device_id, item)

    return {"code": 0, "msg": "日志已同步", "count": len(cleaned)}


@app.post("/api/admin/tiktok_backup/submit")
def submit_tiktok_backup(payload: TikTokBackupSubmitRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    username = (payload.username or "").strip().lstrip("@")
    mobile_alias = (payload.mobile_alias or "").strip()
    if not username or not mobile_alias:
        return {"code": 1, "msg": "username 和 mobile_alias 不能为空"}

    conn = _get_db()
    try:
        now = now_text()
        owner = session["username"]
        row = conn.execute(
            """
            SELECT row_index
            FROM device_editor_rows
            WHERE owner_username = ? AND username = ? AND TRIM(assigned_alias) = TRIM(?)
            LIMIT 1
            """,
            (owner, username, mobile_alias),
        ).fetchone()
        if row:
            row_index = int(row["row_index"])
        else:
            max_row = conn.execute(
                "SELECT COALESCE(MAX(row_index), -1) FROM device_editor_rows WHERE owner_username = ?",
                (owner,),
            ).fetchone()[0]
            row_index = int(max_row) + 1
        default_vc = _default_video_count_for_new_row(conn, owner, username)
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, switch_highlight, video_count, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, '否', ?, ?)
            ON CONFLICT(owner_username, row_index) DO UPDATE SET
                username = excluded.username,
                assigned_alias = excluded.assigned_alias,
                backup_path = excluded.backup_path,
                backup_name = excluded.backup_name,
                backup_time = excluded.backup_time,
                backup_status = excluded.backup_status,
                updated_at = excluded.updated_at
            """,
            (
                owner,
                row_index,
                username,
                mobile_alias,
                (payload.backup_path or "").strip(),
                (payload.backup_name or "").strip(),
                (payload.backup_time or now).strip(),
                (payload.backup_status or "").strip(),
                default_vc,
                now,
            ),
        )
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
    finally:
        conn.close()

    return {
        "code": 0,
        "msg": "设备档案已入库",
        "username": username,
        "mobile_alias": mobile_alias,
        "backup_path": (payload.backup_path or "").strip(),
        "backup_name": (payload.backup_name or "").strip(),
        "backup_time": (payload.backup_time or now_text()).strip(),
        "backup_status": (payload.backup_status or "").strip(),
    }


def _resume_device_for_cloud_op(device_id: str, log_line: str) -> None:
    """管理台下发需立即执行的设备任务时，解除云端 paused，避免设备 check_start 持续覆盖为暂停。"""
    with store.lock:
        dev = store.devices.get(device_id)
        if not dev:
            return
        dev.status = "running"
        dev.paused = False
        dev.pending_start = False
    _persist_client_device_to_db(device_id)
    _append_device_log(device_id, log_line)


def _enqueue_tiktok_device_op(device_id: str, op_type: str, payload: dict[str, Any]) -> str:
    allowed_during_batch_stream = {"batch_stream_stop", "batch_stream_start", "set_pc_service_url"}
    if _batch_stream_device_in_active(device_id) and op_type not in allowed_during_batch_stream:
        raise HTTPException(status_code=409, detail=f"设备正在批量带流中，暂不可下发 {op_type}")
    op_id = f"op_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"
    _enqueue_device_op(
        device_id,
        {
            "op_id": op_id,
            "op_type": op_type,
            "payload": payload,
            "created_at": now_text(),
        },
    )
    return op_id


def _normalize_windows_base_url(raw: str) -> str:
    url = str(raw or "").strip().rstrip("/")
    if not url.lower().startswith(("http://", "https://")):
        raise ValueError("PC 服务地址须以 http:// 或 https:// 开头")
    return url


def _resolve_device_alias_for_admin(device_id: str, alias: str = "") -> str:
    resolved = str(alias or "").strip()
    if resolved:
        return resolved
    did = str(device_id or "").strip()
    if not did:
        return ""
    with store.lock:
        dev = store.devices.get(did)
        if dev and str(dev.alias or "").strip():
            return str(dev.alias).strip()
    conn = _get_db()
    try:
        row = conn.execute("SELECT alias FROM client_devices WHERE device_id = ?", (did,)).fetchone()
        return str(row["alias"] if row else "").strip()
    finally:
        conn.close()


def _load_device_pc_service_url(device_id: str) -> str:
    did = str(device_id or "").strip()
    if not did:
        return ""
    conn = _get_db()
    try:
        row = conn.execute(
            "SELECT pc_service_url FROM client_devices WHERE device_id = ?",
            (did,),
        ).fetchone()
        return str(row["pc_service_url"] if row else "").strip()
    finally:
        conn.close()


def _persist_device_pc_service_url(device_id: str, url: str) -> None:
    did = str(device_id or "").strip()
    if not did:
        return
    conn = _get_db()
    try:
        conn.execute(
            "UPDATE client_devices SET pc_service_url = ?, updated_at = ? WHERE device_id = ?",
            (url, now_text(), did),
        )
        conn.commit()
    finally:
        conn.close()


def _enqueue_pc_service_url_op(device_id: str, alias: str, windows_base_url: str) -> str:
    return _enqueue_tiktok_device_op(
        device_id,
        "set_pc_service_url",
        {
            "device_id": device_id,
            "alias": alias,
            "windows_base_url": windows_base_url,
        },
    )


@app.post("/api/admin/device_pc_service/batch_set")
def admin_device_pc_service_batch_set(
    payload: DevicePcServiceUrlBatchSetRequest,
    authorization: str | None = Header(default=None),
):
    """批量下发 PC 投屏/伴侣服务地址；设备轮询 device_ops 后自动保存并重新注册。"""
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err

    results: list[dict[str, Any]] = []
    enqueued = 0
    failed = 0
    for item in payload.items:
        did = item.device_id.strip()
        try:
            url = _normalize_windows_base_url(item.windows_base_url)
        except ValueError as exc:
            results.append({"device_id": did, "code": 1, "msg": str(exc)})
            failed += 1
            continue

        eff_alias = _resolve_device_alias_for_admin(did, item.alias)
        if not eff_alias:
            results.append({"device_id": did, "code": 1, "msg": "设备未绑定或缺少 alias"})
            failed += 1
            continue

        err2, _device = _require_login_and_owning_device(did, eff_alias, authorization)
        if err2:
            results.append(
                {
                    "device_id": did,
                    "alias": eff_alias,
                    "code": int(err2.get("code") or 1),
                    "msg": str(err2.get("msg") or "无权操作该设备"),
                }
            )
            failed += 1
            continue

        try:
            _persist_device_pc_service_url(did, url)
            _append_device_log(
                did,
                f"[{now_text()}] 云端后台 | 已入队更新 PC 投屏地址 → {url}（alias={eff_alias}）",
            )
            op_id = _enqueue_pc_service_url_op(did, eff_alias, url)
            _append_device_log(did, f"[{now_text()}] 设备任务已入队 op_id={op_id} type=set_pc_service_url")
            results.append(
                {
                    "device_id": did,
                    "alias": eff_alias,
                    "code": 0,
                    "msg": "已下发",
                    "op_id": op_id,
                    "windows_base_url": url,
                }
            )
            enqueued += 1
        except HTTPException as exc:
            results.append({"device_id": did, "alias": eff_alias, "code": exc.status_code, "msg": str(exc.detail)})
            failed += 1

    return {
        "code": 0 if enqueued else 1,
        "msg": f"已入队 {enqueued} 台，失败 {failed} 台",
        "enqueued": enqueued,
        "failed": failed,
        "results": results,
    }


@app.post("/api/admin/device_tiktok/clear")
def admin_device_tiktok_clear(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    cs = (payload.cut_switch_after_backup or "").strip().lower()
    if cs in ("clear_source_for_restore", "exclusive_activate_target"):
        auto_start_disabled = False
        with store.lock:
            dev = store.devices.get(payload.device_id)
            if dev and not dev.auto_start_opt_out:
                dev.auto_start_opt_out = True
                auto_start_disabled = True
        if auto_start_disabled:
            _persist_client_device_to_db(payload.device_id)
            _append_device_log(payload.device_id, "切号清理指令：云端已自动关闭该设备的自动开启程序")

    op_payload: dict[str, Any] = {
        "device_id": payload.device_id,
        "alias": payload.alias,
        "package_name": payload.package_name.strip(),
    }
    if cs in ("clear_source_for_restore", "exclusive_activate_target"):
        op_payload["cut_switch_after_backup"] = cs
    if payload.cut_switch_target_row_index is not None and int(payload.cut_switch_target_row_index) >= 0:
        op_payload["cut_switch_target_row_index"] = int(payload.cut_switch_target_row_index)

    session = _current_session({"authorization": authorization} if authorization else {})
    owner_username = str((session or {}).get("username") or "").strip()
    highlight_applied = False
    if owner_username:
        highlight_applied = _maybe_apply_cut_switch_highlight_on_enqueue(
            owner_username,
            cs,
            payload.cut_switch_target_row_index,
        )

    _resume_device_for_cloud_op(
        payload.device_id,
        f"[{now_text()}] 云端后台 | 已入队清理 TikTok 任务，等待设备拉取（alias={payload.alias}）",
    )
    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_clear",
        op_payload,
    )
    _append_device_log(payload.device_id, f"[{now_text()}] 设备任务已入队 op_id={op_id} type=tiktok_clear")

    return {
        "code": 0,
        "msg": "已下发设备端任务：单独清理 TikTok 数据",
        "op_id": op_id,
        "switch_highlight_applied": highlight_applied,
    }


@app.post("/api/admin/device_tiktok/backup_and_clear")
def admin_device_tiktok_backup_and_clear(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    blocked = _reject_if_auto_switch_manual_ops(device_id=payload.device_id, alias=payload.alias)
    if blocked:
        return blocked
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    auto_start_disabled = False
    with store.lock:
        dev = store.devices.get(payload.device_id)
        if dev and not dev.auto_start_opt_out:
            dev.auto_start_opt_out = True
            auto_start_disabled = True
    if auto_start_disabled:
        _persist_client_device_to_db(payload.device_id)
        _append_device_log(payload.device_id, "备份并清理指令：云端已自动关闭该设备的自动开启程序")

    hint = (payload.username or "").strip().lstrip("@")
    op_payload: dict[str, Any] = {
        "device_id": payload.device_id,
        "alias": payload.alias,
        "package_name": payload.package_name.strip(),
    }
    if payload.backup_record_id:
        op_payload["backup_record_id"] = int(payload.backup_record_id)
    if hint:
        op_payload["username"] = hint

    session = _current_session({"authorization": authorization} if authorization else {})
    owner_username = str((session or {}).get("username") or "").strip()
    if owner_username:
        prev_path = _fetch_previous_backup_path_for_backup_op(
            owner_username,
            payload.backup_record_id,
            payload.device_id,
            hint,
        )
        if prev_path:
            op_payload["previous_backup_path"] = prev_path

    cs = (payload.cut_switch_after_backup or "").strip().lower()
    if cs in ("clear_source_for_restore", "exclusive_activate_target"):
        op_payload["cut_switch_after_backup"] = cs
    if payload.cut_switch_target_row_index is not None and int(payload.cut_switch_target_row_index) >= 0:
        op_payload["cut_switch_target_row_index"] = int(payload.cut_switch_target_row_index)

    highlight_applied = False
    if owner_username:
        highlight_applied = _maybe_apply_cut_switch_highlight_on_enqueue(
            owner_username,
            cs,
            payload.cut_switch_target_row_index,
        )

    _resume_device_for_cloud_op(
        payload.device_id,
        f"[{now_text()}] 云端后台 | 已入队备份并清理/切号任务，等待设备拉取（alias={payload.alias}）",
    )
    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_backup_and_clear",
        op_payload,
    )
    _append_device_log(
        payload.device_id,
        f"[{now_text()}] 设备任务已入队 op_id={op_id} type=tiktok_backup_and_clear alias={payload.alias}",
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：备份并一键清理 TikTok（已自动关闭该设备自动开启程序）" if auto_start_disabled else "已下发设备端任务：备份并一键清理 TikTok",
        "op_id": op_id,
        "auto_start_opt_out": True if auto_start_disabled else bool(getattr(_device, "auto_start_opt_out", False)),
        "switch_highlight_applied": highlight_applied,
    }


@app.post("/api/admin/device_tiktok/force_stop_1")
def admin_device_tiktok_force_stop_1(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_force_stop_1",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：强制关闭 TikTok（方案1）",
        "op_id": op_id,
    }


@app.post("/api/admin/device_tiktok/force_stop_2")
def admin_device_tiktok_force_stop_2(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_force_stop_2",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：强制关闭 TikTok（方案2）",
        "op_id": op_id,
    }


@app.post("/api/admin/device_tiktok/force_stop_3")
def admin_device_tiktok_force_stop_3(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_force_stop_3",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：强制关闭 TikTok（方案3）",
        "op_id": op_id,
    }


@app.post("/api/admin/device_tiktok/launch_1")
def admin_device_tiktok_launch_1(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_launch_1",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：打开 / 拉起 TikTok（方案1）",
        "op_id": op_id,
    }


@app.post("/api/admin/device_tiktok/launch_2")
def admin_device_tiktok_launch_2(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_launch_2",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：打开 / 拉起 TikTok（方案2）",
        "op_id": op_id,
    }


@app.post("/api/admin/device_tiktok/launch_3")
def admin_device_tiktok_launch_3(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_launch_3",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：打开 / 拉起 TikTok（方案3）",
        "op_id": op_id,
    }


@app.post("/api/admin/device_tiktok/edit_basic_profile")
def admin_device_tiktok_edit_basic_profile(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    blocked = _reject_if_auto_switch_manual_ops(device_id=payload.device_id, alias=payload.alias)
    if blocked:
        return blocked
    err, device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    op_payload: dict[str, Any] = {
        "device_id": payload.device_id,
        "alias": payload.alias,
        "package_name": payload.package_name.strip(),
    }
    owner = str(getattr(device, "owner_username", "") or "").strip()
    avatar_item = _gallery_library_random_media_payload(owner)
    if avatar_item:
        op_payload["avatar_media_item"] = avatar_item

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_edit_basic_profile",
        op_payload,
    )

    avatar_hint = "，已附带随机头像素材" if avatar_item else "（图库为空，设备端将尝试拉取或失败）"
    return {
        "code": 0,
        "msg": f"已下发设备端任务：修改基本资料（清图库→随机头像→OCR 链路，失败自动重试）{avatar_hint}",
        "op_id": op_id,
        "avatar_attached": bool(avatar_item),
    }


@app.get("/api/admin/device_tiktok/op_result")
def admin_device_tiktok_op_result(op_id: str, authorization: str | None = Header(default=None)):
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    result = _get_device_op_result(op_id)
    if not result:
        return {"code": 1, "msg": "任务尚未完成", "status": "pending", "op_id": op_id}
    device_id = ""
    data = result.get("data") if isinstance(result, dict) else None
    if isinstance(data, dict):
        device_id = str(data.get("device_id") or "").strip()
    device_logs: Dict[str, List[str]] = {}
    if device_id:
        device_logs[device_id] = _read_device_log_tail(device_id, 120)
    return {"code": 0, "msg": "ok", "status": "done", "op_id": op_id, "result": result, "device_logs": device_logs}


@app.get("/api/admin/device_tiktok/backups")
def admin_device_tiktok_backups(
    page: int = 1,
    page_size: int = 50,
    device_group: str = "",
    authorization: str | None = Header(default=None),
):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    safe_page = max(1, int(page or 1))
    safe_size = max(1, min(int(page_size or 50), 500))
    offset = (safe_page - 1) * safe_size
    group = str(device_group or "").strip()
    owner_sql, owner_params = _backup_owner_scope(session)
    alias_expr = _backup_record_alias_expr()
    group_sql, group_params = _device_group_filter_sql(group, alias_expr)
    params: list[Any] = list(owner_params) + list(group_params)
    conn = _get_db()
    try:
        total = conn.execute(
            f"SELECT COUNT(1) FROM device_editor_rows WHERE 1=1{owner_sql}{group_sql}",
            params,
        ).fetchone()[0]
        rows = conn.execute(
            f"""
            SELECT row_index, device_alias, device_id, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, switch_highlight, updated_at
            FROM device_editor_rows
            WHERE 1=1{owner_sql}{group_sql}
            ORDER BY updated_at DESC, row_index DESC
            LIMIT ? OFFSET ?
            """,
            (*params, safe_size, offset),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows], "page": safe_page, "page_size": safe_size, "total": int(total)}
    finally:
        conn.close()


@app.get("/api/admin/device_editor_rows/export_backup")
def export_device_editor_backup_rows(authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    conn = _get_db()
    try:
        owner = str(session["username"] or "").strip()
        _maybe_maintain_device_editor_on_read(conn, owner)
        rows = conn.execute(
            """
            SELECT row_index, device_alias, device_id, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, switch_highlight, updated_at
            FROM device_editor_rows
            WHERE owner_username = ?
            ORDER BY row_index ASC
            """,
            (owner,),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows]}
    finally:
        conn.close()


@app.get("/api/admin/tiktok_backup/search")
def search_tiktok_backup_records(username: str = "", mobile_alias: str = "", limit: int = 200, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    conn = _get_db()
    try:
        owner = str(session.get("username") or "").strip()
        if owner:
            _maybe_maintain_device_editor_on_read(conn, owner)
        clauses = []
        params: List[Any] = []
        if username.strip():
            clauses.append("LOWER(LTRIM(TRIM(username), '@')) LIKE ?")
            params.append(_case_insensitive_like_pattern(username))
        if mobile_alias.strip():
            clauses.append(f"LOWER(TRIM({_backup_record_alias_expr()})) LIKE ?")
            params.append(_case_insensitive_like_pattern(mobile_alias))
        owner_sql, owner_params = _backup_owner_scope(session)
        where_sql = " WHERE " + " AND ".join(clauses) if clauses else " WHERE 1=1"
        sql = f"""
            SELECT username,
                   {_backup_record_alias_expr()} AS mobile_alias,
                   '' AS video_count,
                   '' AS follower_count,
                   '' AS new_follower_count,
                   backup_path,
                   backup_name,
                   backup_time AS created_at,
                   updated_at
            FROM device_editor_rows
            {where_sql}{owner_sql}
            ORDER BY updated_at DESC, row_index DESC
            LIMIT ?
        """
        params.extend(owner_params)
        params.append(max(1, min(int(limit), 1000)))
        rows = conn.execute(sql, params).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows]}
    finally:
        conn.close()


@app.get("/api/admin/tiktok_backup/records")
def list_tiktok_backup_records(
    page: int = 1,
    page_size: int = 100,
    username: str = "",
    mobile_alias: str = "",
    authorization: str | None = Header(default=None),
):
    # 兼容旧前端：records 分页接口转发到 search 逻辑。
    safe_page = max(1, int(page or 1))
    safe_page_size = max(1, min(int(page_size or 100), 1000))
    result = search_tiktok_backup_records(
        username=username,
        mobile_alias=mobile_alias,
        limit=max(safe_page * safe_page_size, safe_page_size),
        authorization=authorization,
    )
    if int(result.get("code", 1)) != 0:
        return result
    items = list(result.get("items") or [])
    start = (safe_page - 1) * safe_page_size
    end = start + safe_page_size
    return {
        "code": 0,
        "items": items[start:end],
        "page": safe_page,
        "page_size": safe_page_size,
        "total": len(items),
    }


@app.get("/api/admin/devices/groups")
def list_device_groups(authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    return {"code": 0, "items": _list_distinct_device_groups(session)}


def _device_groups_detail(session: dict) -> list[dict[str, Any]]:
    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    if not allowed:
        return []
    placeholders = ",".join("?" for _ in allowed)
    conn = _get_db()
    try:
        rows = conn.execute(
            f"""
            SELECT TRIM(alias) AS alias, COALESCE(TRIM(device_group), '') AS device_group
            FROM client_devices
            WHERE device_id IN ({placeholders}) AND TRIM(alias) <> ''
            """,
            list(allowed),
        ).fetchall()
    finally:
        conn.close()
    grouped: dict[str, set[str]] = {}
    for row in rows:
        alias = str(row["alias"] or "").strip()
        group = str(row["device_group"] or "").strip() or "__ungrouped__"
        grouped.setdefault(group, set()).add(alias)
    items = []
    for group_name in sorted(grouped.keys(), key=lambda g: (g == "__ungrouped__", g.lower())):
        aliases = sorted(grouped[group_name], key=str.lower)
        items.append({"group_name": group_name, "aliases": aliases, "count": len(aliases)})
    return items


@app.get("/api/admin/devices/groups_detail")
def list_device_groups_detail(authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    items = _device_groups_detail(session)
    names = [str(i["group_name"]) for i in items if str(i["group_name"]) not in ("", "__ungrouped__")]
    return {"code": 0, "items": items, "group_names": names}


@app.post("/api/admin/devices/delete_group")
def delete_device_group(payload: DeleteDeviceGroupRequest, authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    group_name = str(payload.group_name or "").strip()
    if not group_name or group_name == "__ungrouped__":
        return {"code": 1, "msg": "未分组不能删除"}
    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    if not allowed:
        return {"code": 0, "msg": "已删除分组", "updated": 0}
    placeholders = ",".join("?" for _ in allowed)
    now = now_text()
    conn = _get_db()
    try:
        cur = conn.execute(
            f"""
            UPDATE client_devices
            SET device_group = '', updated_at = ?
            WHERE device_id IN ({placeholders}) AND COALESCE(TRIM(device_group), '') = ?
            """,
            (now, *allowed, group_name),
        )
        conn.commit()
        updated = int(cur.rowcount or 0)
    finally:
        conn.close()
    with _tracked_store_lock("delete_device_group"):
        for did, dev in store.devices.items():
            if did in allowed and str(dev.device_group or "").strip() == group_name:
                dev.device_group = ""
    return {"code": 0, "msg": "分组已删除，相关设备已置为未分组", "updated": updated}


@app.post("/api/admin/devices/set_group")
def set_device_group(payload: SetDeviceGroupRequest, authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    group_name = str(payload.device_group or "").strip()
    alias_targets = [str(a or "").strip() for a in (payload.aliases or []) if str(a or "").strip()]
    id_targets = [str(d or "").strip() for d in (payload.device_ids or []) if str(d or "").strip()]
    if not alias_targets and not id_targets:
        return {"code": 1, "msg": "请提供 device_ids 或 aliases"}
    now = now_text()
    updated = 0
    conn = _get_db()
    try:
        if id_targets:
            for device_id in id_targets:
                if device_id not in allowed:
                    continue
                conn.execute(
                    "UPDATE client_devices SET device_group = ?, updated_at = ? WHERE device_id = ?",
                    (group_name, now, device_id),
                )
                updated += int(conn.execute("SELECT changes()").fetchone()[0] or 0)
        for alias in alias_targets:
            rows = conn.execute(
                "SELECT device_id FROM client_devices WHERE TRIM(alias) = ?",
                (alias,),
            ).fetchall()
            for row in rows:
                device_id = str(row[0])
                if device_id not in allowed:
                    continue
                conn.execute(
                    "UPDATE client_devices SET device_group = ?, updated_at = ? WHERE device_id = ?",
                    (group_name, now, device_id),
                )
                updated += int(conn.execute("SELECT changes()").fetchone()[0] or 0)
        conn.commit()
    finally:
        conn.close()
    with _tracked_store_lock("set_device_group"):
        for device_id in id_targets:
            if device_id in store.devices and device_id in allowed:
                store.devices[device_id].device_group = group_name
        if alias_targets:
            alias_set = {a.lower() for a in alias_targets}
            for did, dev in store.devices.items():
                if did not in allowed:
                    continue
                if str(dev.alias or "").strip().lower() in alias_set:
                    dev.device_group = group_name
    return {"code": 0, "msg": "分组已更新", "updated": updated, "device_group": group_name}


@app.get("/api/admin/devices/aliases")
def list_device_aliases(authorization: str | None = Header(default=None)):
    """返回当前用户可见的全部已绑定设备别名（不受概览分页影响）。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session)
    conn = _get_db()
    try:
        rows = conn.execute(
            "SELECT device_id, alias FROM client_devices WHERE TRIM(COALESCE(alias, '')) <> ''"
        ).fetchall()
        pairs: list[dict[str, str]] = []
        seen_aliases: set[str] = set()
        for row in rows:
            device_id = str(row["device_id"])
            if device_id not in allowed:
                continue
            alias = str(row["alias"] or "").strip()
            if not alias or alias in seen_aliases:
                continue
            seen_aliases.add(alias)
            pairs.append({"alias": alias, "device_id": device_id})
    finally:
        conn.close()
    pairs.sort(key=lambda item: item["alias"].lower())
    return {"code": 0, "items": [item["alias"] for item in pairs], "pairs": pairs}


@app.get("/api/admin/editor/account_rows")
def list_account_editor_rows(authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    conn = _get_db()
    try:
        owner = str(session["username"] or "").strip()
        _maybe_maintain_device_editor_on_read(conn, owner)
        rows = conn.execute(
            """
            SELECT row_index, username, password, email, email_password, api, is_login, login_time, region, assigned_alias, video_count, follower_count, follower_growth, basic_profile_modified
            FROM account_editor_rows
            WHERE owner_username = ?
            ORDER BY row_index ASC
            """,
            (owner,),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows]}
    finally:
        conn.close()


@app.get("/api/admin/editor/account_rows/support")
def list_account_editor_row_support(authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "璇峰厛鐧诲綍"}
    conn = _get_db()
    try:
        owner = str(session["username"] or "").strip()
        rows = conn.execute(
            """
            SELECT username, assigned_alias, device_alias, video_count, follower_count, follower_growth, updated_at, row_index
            FROM device_editor_rows
            WHERE owner_username = ? AND TRIM(username) <> ''
            ORDER BY updated_at DESC, row_index DESC
            """,
            (owner,),
        ).fetchall()
        seen: set[str] = set()
        items: list[dict[str, str]] = []
        for row in rows:
            username = str(row["username"] or "").strip()
            key = username.lower()
            if not key or key in seen:
                continue
            seen.add(key)
            assigned_alias = str(row["assigned_alias"] or "").strip() or str(row["device_alias"] or "").strip()
            items.append(
                {
                    "username": username,
                    "assigned_alias": assigned_alias,
                    "device_alias": str(row["device_alias"] or "").strip(),
                    "video_count": str(row["video_count"] or "").strip(),
                    "follower_count": str(row["follower_count"] or "").strip(),
                    "follower_growth": str(row["follower_growth"] or "").strip(),
                    "updated_at": str(row["updated_at"] or "").strip(),
                }
            )
        return {"code": 0, "items": items}
    finally:
        conn.close()


@app.post("/api/admin/editor/account_rows/upsert")
def upsert_account_editor_row(payload: AccountEditorRowUpsertRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    conn = _get_db()
    try:
        now = now_text()

        def _blank_if_none(text: object | None) -> str:
            if text is None:
                return ""
            return str(text).strip()

        row_index = int(payload.row_index)
        incoming_alias = _blank_if_none(payload.assigned_alias)
        owner = str(session["username"] or "").strip()
        existing = conn.execute(
            """
            SELECT username, assigned_alias
            FROM account_editor_rows
            WHERE owner_username = ? AND row_index = ?
            LIMIT 1
            """,
            (owner, row_index),
        ).fetchone()
        existing_username = str(existing["username"] or "").strip() if existing else ""
        existing_assigned_alias = _blank_if_none(existing["assigned_alias"]) if existing else ""
        if not existing_assigned_alias and existing_username:
            existing_assigned_alias = _recover_assigned_alias_for_account_username(
                conn, owner, existing_username
            )
        alias_locked = bool(existing_assigned_alias)
        if existing and alias_locked and incoming_alias and incoming_alias != existing_assigned_alias:
            return {"code": 1, "msg": "该账号已分配别名，不能修改别名；删除账号记录将同步清理设备备份档案"}
        persisted_alias = existing_assigned_alias or incoming_alias
        new_username = payload.username.strip()
        if not persisted_alias:
            recover_username = new_username or existing_username
            if recover_username:
                persisted_alias = _recover_assigned_alias_for_account_username(
                    conn, owner, recover_username
                )
        vc_input = _blank_if_none(payload.video_count)
        if not vc_input and not existing:
            vc_input = str(NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN)
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, password, email, email_password, api, is_login, login_time, region, assigned_alias, video_count, follower_count, follower_growth, basic_profile_modified, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, ''), COALESCE(?, ''), COALESCE(?, ''), ?, ?)
            ON CONFLICT(owner_username, row_index) DO UPDATE SET
                username = excluded.username,
                password = excluded.password,
                email = excluded.email,
                email_password = excluded.email_password,
                api = CASE
                    WHEN TRIM(excluded.api) <> '' THEN excluded.api
                    ELSE account_editor_rows.api
                END,
                is_login = excluded.is_login,
                login_time = excluded.login_time,
                region = excluded.region,
                assigned_alias = CASE
                    WHEN TRIM(COALESCE(account_editor_rows.assigned_alias, '')) <> '' THEN account_editor_rows.assigned_alias
                    WHEN TRIM(excluded.assigned_alias) <> '' THEN excluded.assigned_alias
                    ELSE account_editor_rows.assigned_alias
                END,
                video_count = COALESCE(excluded.video_count, ''),
                follower_count = COALESCE(excluded.follower_count, ''),
                follower_growth = COALESCE(excluded.follower_growth, ''),
                basic_profile_modified = COALESCE(excluded.basic_profile_modified, '否'),
                updated_at = excluded.updated_at
            """,
            (
                owner,
                row_index,
                new_username,
                payload.password.strip(),
                payload.email.strip(),
                payload.email_password.strip(),
                payload.api.strip(),
                1 if payload.is_login else 0,
                payload.login_time.strip(),
                payload.region.strip(),
                persisted_alias,
                vc_input,
                _blank_if_none(payload.follower_count),
                _blank_if_none(payload.follower_growth),
                _normalize_switch_highlight(payload.basic_profile_modified),
                now,
            ),
        )
        if (
            existing_username
            and new_username
            and existing_username.lower() != new_username.lower()
        ):
            _sync_device_editor_username_for_account(
                conn, owner, existing_username, new_username, persisted_alias
            )
        link_username = new_username or existing_username
        if persisted_alias and link_username:
            _link_backup_record_alias_for_username(
                link_username, persisted_alias, owner_username=owner, conn=conn, commit=False
            )
            _upsert_backup_record_for_account(
                owner,
                link_username,
                persisted_alias,
                payload.region,
                conn=conn,
                commit=False,
            )
        conn.commit()
        _reset_device_editor_read_maintain_schedule(owner)
        return {"code": 0, "msg": "账号记录已保存", "row_index": payload.row_index, "assigned_alias_locked": alias_locked or bool(persisted_alias)}
    finally:
        conn.close()

def _account_editor_blank_if_none(text: object | None) -> str:
    if text is None:
        return ""
    return str(text).strip()


def _account_editor_batch_upsert_one_in_conn(
    conn: sqlite3.Connection,
    owner: str,
    payload: AccountEditorRowUpsertRequest,
) -> dict[str, Any]:
    now = now_text()
    row_index = int(payload.row_index)
    incoming_alias = _account_editor_blank_if_none(payload.assigned_alias)
    existing = conn.execute(
        """
        SELECT username, assigned_alias
        FROM account_editor_rows
        WHERE owner_username = ? AND row_index = ?
        LIMIT 1
        """,
        (owner, row_index),
    ).fetchone()
    existing_username = str(existing["username"] or "").strip() if existing else ""
    existing_assigned_alias = _account_editor_blank_if_none(existing["assigned_alias"]) if existing else ""
    if not existing_assigned_alias and existing_username:
        existing_assigned_alias = _recover_assigned_alias_for_account_username(
            conn, owner, existing_username
        )
    alias_locked = bool(existing_assigned_alias)
    if existing and alias_locked and incoming_alias and incoming_alias != existing_assigned_alias:
        raise ValueError("该账号已分配别名，不能修改别名；删除账号记录将同步清理设备备份档案")

    persisted_alias = existing_assigned_alias or incoming_alias
    new_username = payload.username.strip()
    if not persisted_alias:
        recover_username = new_username or existing_username
        if recover_username:
            persisted_alias = _recover_assigned_alias_for_account_username(
                conn, owner, recover_username
            )

    vc_input = _account_editor_blank_if_none(payload.video_count)
    if not vc_input and not existing:
        vc_input = str(NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN)

    conn.execute(
        """
        INSERT INTO account_editor_rows
            (owner_username, row_index, username, password, email, email_password, api, is_login, login_time, region, assigned_alias, video_count, follower_count, follower_growth, basic_profile_modified, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, ''), COALESCE(?, ''), COALESCE(?, ''), ?, ?)
        ON CONFLICT(owner_username, row_index) DO UPDATE SET
            username = excluded.username,
            password = excluded.password,
            email = excluded.email,
            email_password = excluded.email_password,
            api = CASE
                WHEN TRIM(excluded.api) <> '' THEN excluded.api
                ELSE account_editor_rows.api
            END,
            is_login = excluded.is_login,
            login_time = excluded.login_time,
            region = excluded.region,
            assigned_alias = CASE
                WHEN TRIM(COALESCE(account_editor_rows.assigned_alias, '')) <> '' THEN account_editor_rows.assigned_alias
                WHEN TRIM(excluded.assigned_alias) <> '' THEN excluded.assigned_alias
                ELSE account_editor_rows.assigned_alias
            END,
            video_count = COALESCE(excluded.video_count, ''),
            follower_count = COALESCE(excluded.follower_count, ''),
            follower_growth = COALESCE(excluded.follower_growth, ''),
            basic_profile_modified = COALESCE(excluded.basic_profile_modified, '否'),
            updated_at = excluded.updated_at
        """,
        (
            owner,
            row_index,
            new_username,
            payload.password.strip(),
            payload.email.strip(),
            payload.email_password.strip(),
            payload.api.strip(),
            1 if payload.is_login else 0,
            payload.login_time.strip(),
            payload.region.strip(),
            persisted_alias,
            vc_input,
            _account_editor_blank_if_none(payload.follower_count),
            _account_editor_blank_if_none(payload.follower_growth),
            _normalize_switch_highlight(payload.basic_profile_modified),
            now,
        ),
    )

    if (
        existing_username
        and new_username
        and existing_username.lower() != new_username.lower()
    ):
        _sync_device_editor_username_for_account(
            conn, owner, existing_username, new_username, persisted_alias
        )

    link_username = new_username or existing_username
    if persisted_alias and link_username:
        _link_backup_record_alias_for_username(
            link_username, persisted_alias, owner_username=owner, conn=conn, commit=False
        )
        _upsert_backup_record_for_account(
            owner,
            link_username,
            persisted_alias,
            payload.region,
            conn=conn,
            commit=False,
        )

    return {
        "row_index": row_index,
        "assigned_alias_locked": alias_locked or bool(persisted_alias),
        "persisted_alias": persisted_alias,
        "username": link_username,
    }


@app.post("/api/admin/editor/account_rows/batch_upsert")
def batch_upsert_account_editor_rows(
    payload: AccountEditorRowBatchUpsertRequest, authorization: str | None = Header(default=None)
):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "璇峰厛鐧诲綍"}

    items = list(payload.items or [])
    if len(items) > ACCOUNT_EDITOR_ROW_BATCH_UPSERT_MAX:
        return {
            "code": 1,
            "msg": f"单次最多导入 {ACCOUNT_EDITOR_ROW_BATCH_UPSERT_MAX} 条，请分批操作",
        }

    owner = str(session["username"] or "").strip()
    conn = _get_db()
    try:
        results: list[dict[str, Any]] = []
        for item in items:
            results.append(_account_editor_batch_upsert_one_in_conn(conn, owner, item))
        conn.commit()
        _reset_device_editor_read_maintain_schedule(owner)
        return {
            "code": 0,
            "msg": f"已批量保存 {len(results)} 条账号记录",
            "count": len(results),
            "items": results,
        }
    except ValueError as exc:
        conn.rollback()
        return {"code": 1, "msg": str(exc)}
    finally:
        conn.close()


def _delete_one_account_editor_row_in_conn(
    conn: sqlite3.Connection,
    owner: str,
    row_index: int,
    username: str = "",
) -> dict:
    """删除单条账号记录并级联清理 device_editor_rows；调用方负责 commit。"""
    row_index = int(row_index)
    username = _normalized_alias(username).lstrip("@")
    existing = conn.execute(
        """
        SELECT username, assigned_alias
        FROM account_editor_rows
        WHERE owner_username = ? AND row_index = ?
        LIMIT 1
        """,
        (owner, row_index),
    ).fetchone()
    if not existing:
        return {
            "found": False,
            "row_index": row_index,
            "username": username,
            "deleted_accounts": 0,
            "deleted_device_rows": 0,
        }
    if not username:
        username = _normalized_alias(existing["username"]).lstrip("@")

    deleted_device_rows = 0
    if username:
        device_cur = conn.execute(
            """
            DELETE FROM device_editor_rows
            WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))
            """,
            (owner, username),
        )
        deleted_device_rows = int(device_cur.rowcount or 0)

    account_cur = conn.execute(
        """
        DELETE FROM account_editor_rows
        WHERE owner_username = ? AND row_index = ?
        """,
        (owner, row_index),
    )
    deleted_accounts = int(account_cur.rowcount or 0)
    if deleted_accounts > 0:
        _shift_account_editor_row_indices_after_delete(conn, owner, row_index)
    return {
        "found": True,
        "row_index": row_index,
        "username": username,
        "deleted_accounts": deleted_accounts,
        "deleted_device_rows": deleted_device_rows,
    }


@app.post("/api/admin/editor/account_rows/delete")
def delete_account_editor_row(payload: AccountEditorRowDeleteRequest, authorization: str | None = Header(default=None)):
    """删除指定账号记录；已分配别名也可删，并级联删除 device_editor_rows 中该 username 的备份档案。"""
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    owner = str(session["username"] or "").strip()
    row_index = int(payload.row_index)
    username = _normalized_alias(payload.username).lstrip("@")
    conn = _get_db()
    try:
        result = _delete_one_account_editor_row_in_conn(conn, owner, row_index, username)
        if not result["found"]:
            return {"code": 1, "msg": "账号记录不存在或已删除"}
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
        _reset_device_editor_read_maintain_schedule(owner)
        return {
            "code": 0,
            "msg": "账号记录已删除",
            "row_index": row_index,
            "username": result["username"],
            "deleted_accounts": int(result["deleted_accounts"] or 0),
            "deleted_device_rows": int(result["deleted_device_rows"] or 0),
        }
    except sqlite3.OperationalError as exc:
        if _is_db_lock_error(exc):
            return {"code": 1, "msg": "数据库繁忙，请稍后重试"}
        return {"code": 1, "msg": f"删除失败：{exc}"}
    except Exception as exc:
        print(f"[{now_text()}] account_rows.delete | owner={owner} row_index={row_index} EXC={exc}")
        return {"code": 1, "msg": f"删除失败：{exc}"}
    finally:
        conn.close()


@app.post("/api/admin/editor/account_rows/batch_delete")
def batch_delete_account_editor_rows(
    payload: AccountEditorRowBatchDeleteRequest, authorization: str | None = Header(default=None)
):
    """批量删除账号记录；按 row_index 从大到小逐条删除，单事务提交，避免索引重排冲突。"""
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    owner = str(session["username"] or "").strip()
    raw_items = payload.items or []
    if len(raw_items) > ACCOUNT_EDITOR_ROW_BATCH_DELETE_MAX:
        return {
            "code": 1,
            "msg": f"单次最多删除 {ACCOUNT_EDITOR_ROW_BATCH_DELETE_MAX} 条，请分批操作",
        }
    deduped: dict[int, str] = {}
    for item in raw_items:
        row_index = int(item.row_index)
        if row_index in deduped:
            continue
        deduped[row_index] = _normalized_alias(item.username).lstrip("@")
    row_indices = sorted(deduped.keys(), reverse=True)
    conn = _get_db()
    try:
        deleted_accounts = 0
        deleted_device_rows = 0
        not_found_count = 0
        deleted_usernames: list[str] = []
        for row_index in row_indices:
            result = _delete_one_account_editor_row_in_conn(
                conn, owner, row_index, deduped.get(row_index, "")
            )
            if not result["found"]:
                not_found_count += 1
                continue
            deleted_accounts += int(result["deleted_accounts"] or 0)
            deleted_device_rows += int(result["deleted_device_rows"] or 0)
            username = str(result.get("username") or "").strip()
            if username:
                deleted_usernames.append(username)
        if deleted_accounts <= 0:
            return {"code": 1, "msg": "未删除任何账号记录（可能已不存在）", "not_found_count": not_found_count}
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
        _reset_device_editor_read_maintain_schedule(owner)
        return {
            "code": 0,
            "msg": f"已批量删除 {deleted_accounts} 条账号记录",
            "deleted_accounts": deleted_accounts,
            "deleted_device_rows": deleted_device_rows,
            "not_found_count": not_found_count,
            "deleted_usernames": deleted_usernames,
        }
    except sqlite3.OperationalError as exc:
        if _is_db_lock_error(exc):
            return {"code": 1, "msg": "数据库繁忙，请稍后重试"}
        return {"code": 1, "msg": f"批量删除失败：{exc}"}
    except Exception as exc:
        print(f"[{now_text()}] account_rows.batch_delete | owner={owner} count={len(row_indices)} EXC={exc}")
        return {"code": 1, "msg": f"批量删除失败：{exc}"}
    finally:
        conn.close()


@app.post("/api/admin/editor/account_rows/batch_set_basic_profile")
def batch_set_account_editor_rows_basic_profile(
    payload: AccountEditorRowBatchSetBasicProfileRequest, authorization: str | None = Header(default=None)
):
    """批量设置账号记录「资料」(basic_profile_modified) 为「是」或「否」。"""
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    owner = str(session["username"] or "").strip()
    raw_items = payload.items or []
    if len(raw_items) > ACCOUNT_EDITOR_ROW_BATCH_SET_BASIC_PROFILE_MAX:
        return {
            "code": 1,
            "msg": f"单次最多修改 {ACCOUNT_EDITOR_ROW_BATCH_SET_BASIC_PROFILE_MAX} 条，请分批操作",
        }
    target_value = _normalize_switch_highlight(payload.basic_profile_modified)
    deduped: dict[int, str] = {}
    for item in raw_items:
        row_index = int(item.row_index)
        if row_index in deduped:
            continue
        deduped[row_index] = _normalized_alias(item.username).lstrip("@")
    conn = _get_db()
    try:
        updated_count = 0
        not_found_count = 0
        unchanged_count = 0
        now = now_text()
        for row_index, username in deduped.items():
            existing = conn.execute(
                """
                SELECT username, basic_profile_modified
                FROM account_editor_rows
                WHERE owner_username = ? AND row_index = ?
                LIMIT 1
                """,
                (owner, row_index),
            ).fetchone()
            if not existing:
                not_found_count += 1
                continue
            existing_username = str(existing["username"] or "").strip()
            if username and existing_username and existing_username.lower() != username.lower():
                not_found_count += 1
                continue
            current_value = _normalize_switch_highlight(existing["basic_profile_modified"])
            if current_value == target_value:
                unchanged_count += 1
                continue
            cur = conn.execute(
                """
                UPDATE account_editor_rows
                SET basic_profile_modified = ?, updated_at = ?
                WHERE owner_username = ? AND row_index = ?
                """,
                (target_value, now, owner, row_index),
            )
            if int(cur.rowcount or 0) > 0:
                updated_count += 1
        if updated_count <= 0:
            if unchanged_count <= 0 and not_found_count > 0:
                return {
                    "code": 1,
                    "msg": "未修改任何账号记录（可能已不存在）",
                    "updated_count": 0,
                    "not_found_count": not_found_count,
                    "unchanged_count": unchanged_count,
                }
            msg_parts: list[str] = []
            if unchanged_count > 0:
                msg_parts.append(f"{unchanged_count} 条已是「{target_value}」")
            if not_found_count > 0:
                msg_parts.append(f"{not_found_count} 条已不存在")
            msg = "未修改任何记录"
            if msg_parts:
                msg += f"（{'，'.join(msg_parts)}）"
            return {
                "code": 0,
                "msg": msg,
                "updated_count": 0,
                "not_found_count": not_found_count,
                "unchanged_count": unchanged_count,
                "basic_profile_modified": target_value,
            }
        conn.commit()
        return {
            "code": 0,
            "msg": f"已批量将 {updated_count} 条账号记录「资料」设为「{target_value}」",
            "updated_count": updated_count,
            "not_found_count": not_found_count,
            "unchanged_count": unchanged_count,
            "basic_profile_modified": target_value,
        }
    except sqlite3.OperationalError as exc:
        if _is_db_lock_error(exc):
            return {"code": 1, "msg": "数据库繁忙，请稍后重试"}
        return {"code": 1, "msg": f"批量修改失败：{exc}"}
    except Exception as exc:
        print(
            f"[{now_text()}] account_rows.batch_set_basic_profile | owner={owner} "
            f"count={len(deduped)} EXC={exc}"
        )
        return {"code": 1, "msg": f"批量修改失败：{exc}"}
    finally:
        conn.close()


@app.post("/api/admin/editor/account_rows/unassign_alias")
def unassign_account_alias(payload: AccountAliasUnassignRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    username = _normalized_alias(payload.username).lstrip("@")
    assigned_alias = _normalized_alias(payload.assigned_alias)
    if not username or not assigned_alias:
        return {"code": 1, "msg": "username 和 assigned_alias 不能为空"}
    owner = str(session["username"] or "").strip()
    conn = _get_db()
    try:
        if payload.device_row_index is not None:
            device_cur = conn.execute(
                f"""
                DELETE FROM device_editor_rows
                WHERE owner_username = ?
                  AND row_index = ?
                  AND LOWER(username) = LOWER(?)
                  AND LOWER(TRIM({_backup_record_alias_expr()})) = LOWER(TRIM(?))
                """,
                (owner, int(payload.device_row_index), username, assigned_alias),
            )
        else:
            device_cur = conn.execute(
                f"""
                DELETE FROM device_editor_rows
                WHERE owner_username = ?
                  AND LOWER(username) = LOWER(?)
                  AND LOWER(TRIM({_backup_record_alias_expr()})) = LOWER(TRIM(?))
                """,
                (owner, username, assigned_alias),
            )
        account_cur = conn.execute(
            """
            UPDATE account_editor_rows
            SET assigned_alias = '', updated_at = ?
            WHERE owner_username = ?
              AND LOWER(TRIM(username)) = LOWER(TRIM(?))
              AND (
                LOWER(TRIM(COALESCE(assigned_alias, ''))) = LOWER(TRIM(?))
                OR NOT EXISTS (
                  SELECT 1 FROM device_editor_rows d
                  WHERE d.owner_username = account_editor_rows.owner_username
                    AND LOWER(TRIM(d.username)) = LOWER(TRIM(account_editor_rows.username))
                )
              )
            """,
            (now_text(), owner, username, assigned_alias),
        )
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
        return {
            "code": 0,
            "msg": "分配关系已删除",
            "updated_accounts": int(account_cur.rowcount or 0),
            "deleted_device_rows": int(device_cur.rowcount or 0),
        }
    finally:
        conn.close()


@app.get("/api/admin/editor/device_rows")
def list_device_editor_rows(
    page: int = 1,
    page_size: int = 20,
    device_group: str = "",
    search: str = "",
    search_field: str = "",
    authorization: str | None = Header(default=None),
):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    safe_page = max(1, int(page or 1))
    safe_size = max(1, min(int(page_size or 20), 200))
    offset = (safe_page - 1) * safe_size
    alias_expr = _backup_record_alias_expr()
    group_sql, group_params = _device_group_filter_sql(str(device_group or "").strip(), alias_expr)
    safe_search_field = str(search_field or "").strip().lower()
    if safe_search_field not in {"", "alias", "username", "region"}:
        return {"code": 1, "msg": f"不支持的搜索字段: {search_field}"}
    search_sql, search_params = _device_editor_search_sql(search, search_field=safe_search_field)
    conn = _get_db()
    try:
        owner = str(session["username"] or "").strip()
        _maybe_maintain_device_editor_on_read(conn, owner)
        paged_alias_expr = _device_editor_paged_alias_expr(alias_expr)
        count_row = conn.execute(
            f"""
            SELECT COUNT(DISTINCT {paged_alias_expr})
            FROM device_editor_rows
            WHERE owner_username = ?{group_sql}{search_sql}
            """,
            (owner, *group_params, *search_params),
        ).fetchone()
        total_aliases = int(count_row[0] if count_row else 0)
        alias_rows = conn.execute(
            f"""
            SELECT DISTINCT {paged_alias_expr} AS eff_alias
            FROM device_editor_rows
            WHERE owner_username = ?{group_sql}{search_sql}
            ORDER BY eff_alias ASC
            LIMIT ? OFFSET ?
            """,
            (owner, *group_params, *search_params, safe_size, offset),
        ).fetchall()
        page_aliases = [str(r[0]).strip() for r in alias_rows if str(r[0] or "").strip()]
        if not page_aliases:
            return {
                "code": 0,
                "items": [],
                "page": safe_page,
                "page_size": safe_size,
                "total": total_aliases,
                "alias_page_aliases": [],
                "search": str(search or "").strip(),
                "search_field": safe_search_field,
            }
        placeholders = ",".join("?" for _ in page_aliases)
        rows = conn.execute(
            f"""
            SELECT row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, auto_switch_excluded, remark, switch_highlight, env_status, video_count, follower_count, follower_growth, updated_at
            FROM device_editor_rows
            WHERE owner_username = ? AND {paged_alias_expr} IN ({placeholders}){search_sql}
            ORDER BY {paged_alias_expr} ASC, row_index ASC
            """,
            (owner, *page_aliases, *search_params),
        ).fetchall()
        account_alias_by_user: dict[str, str] = {}
        acct_rows = conn.execute(
            """
            SELECT username, assigned_alias
            FROM account_editor_rows
            WHERE owner_username = ? AND TRIM(COALESCE(assigned_alias, '')) <> ''
            """,
            (owner,),
        ).fetchall()
        for ar in acct_rows or []:
            u = str(ar["username"] or "").strip().lower()
            if u and u not in account_alias_by_user:
                account_alias_by_user[u] = str(ar["assigned_alias"] or "").strip()
        items: list[dict[str, Any]] = []
        for r in rows:
            item = dict(r)
            item["auto_switch_excluded"] = bool(int(item.get("auto_switch_excluded") or 0))
            ukey = str(item.get("username") or "").strip().lower()
            if ukey and ukey in account_alias_by_user:
                item["account_assigned_alias"] = account_alias_by_user[ukey]
            items.append(item)
        alias_to_device: dict[str, str] = {}
        for br in conn.execute(
            """
            SELECT device_id, alias FROM client_devices
            WHERE owner_username = ? AND TRIM(COALESCE(alias, '')) <> ''
            """,
            (owner,),
        ).fetchall():
            key = str(br["alias"] or "").strip().lower()
            if key and key not in alias_to_device:
                alias_to_device[key] = str(br["device_id"])
        for item in items:
            eff = str(item.get("assigned_alias") or item.get("device_alias") or "").strip().lower()
            bound = alias_to_device.get(eff, "")
            if bound:
                item["device_id"] = bound
        return {
            "code": 0,
            "items": items,
            "page": safe_page,
            "page_size": safe_size,
            "total": total_aliases,
            "alias_page_aliases": page_aliases,
            "device_groups": _list_distinct_device_groups(session),
            "search": str(search or "").strip(),
            "search_field": safe_search_field,
        }
    finally:
        conn.close()


BACKUP_ARCHIVE_XLSX_HEADERS: List[str] = [
    "row_index",
    "assigned_alias",
    "device_alias",
    "device_id",
    "username",
    "ip",
    "region",
    "backup_path",
    "backup_name",
    "backup_time",
    "backup_status",
    "remark",
    "switch_highlight",
    "video_count",
    "follower_count",
    "follower_growth",
    "updated_at",
    "account_row_index",
    "account_password",
    "account_email",
    "account_email_password",
    "account_api",
    "account_is_login",
    "account_login_time",
    "account_region",
    "account_assigned_alias",
    "account_video_count",
    "account_follower_count",
    "account_follower_growth",
    "account_basic_profile_modified",
]


def _xlsx_cell_str(value: object | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "是" if value else "否"
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value).strip()


def _parse_editor_login_flag(value: object | None) -> bool:
    text = _xlsx_cell_str(value).lower()
    return text in {"1", "true", "yes", "y", "是", "已登录", "login"}


def _list_device_editor_rows_for_backup_export(
    conn: sqlite3.Connection,
    owner: str,
    device_group: str = "",
) -> List[dict[str, Any]]:
    alias_expr = _backup_record_alias_expr()
    group_sql, group_params = _device_group_filter_sql(str(device_group or "").strip(), alias_expr)
    rows = conn.execute(
        f"""
        SELECT row_index, device_alias, device_id, ip, region, username, assigned_alias,
               backup_path, backup_name, backup_time, backup_status, remark, switch_highlight,
               video_count, follower_count, follower_growth, updated_at
        FROM device_editor_rows
        WHERE owner_username = ? AND TRIM({alias_expr}) <> ''{group_sql}
        ORDER BY {alias_expr} ASC, row_index ASC
        """,
        (owner, *group_params),
    ).fetchall()
    return [dict(r) for r in rows]


def _account_editor_rows_by_username(conn: sqlite3.Connection, owner: str) -> Dict[str, dict[str, Any]]:
    rows = conn.execute(
        """
        SELECT row_index, username, password, email, email_password, api, is_login, login_time, region,
               assigned_alias, video_count, follower_count, follower_growth, basic_profile_modified
        FROM account_editor_rows
        WHERE owner_username = ?
        """,
        (owner,),
    ).fetchall()
    out: Dict[str, dict[str, Any]] = {}
    for row in rows:
        uname = _normalized_alias(row["username"]).lstrip("@").lower()
        if uname and uname not in out:
            out[uname] = dict(row)
    return out


def _backup_archive_export_row(device_row: dict[str, Any], account_row: dict[str, Any] | None) -> List[str]:
    acct = account_row or {}
    alias_expr_val = _blank_if_none(device_row.get("assigned_alias")) or _blank_if_none(device_row.get("device_alias"))
    return [
        _xlsx_cell_str(device_row.get("row_index")),
        alias_expr_val,
        _xlsx_cell_str(device_row.get("device_alias")),
        _xlsx_cell_str(device_row.get("device_id")),
        _xlsx_cell_str(device_row.get("username")),
        _xlsx_cell_str(device_row.get("ip")),
        _xlsx_cell_str(device_row.get("region")),
        _xlsx_cell_str(device_row.get("backup_path")),
        _xlsx_cell_str(device_row.get("backup_name")),
        _xlsx_cell_str(device_row.get("backup_time")),
        _xlsx_cell_str(device_row.get("backup_status")),
        _xlsx_cell_str(device_row.get("remark")),
        _normalize_switch_highlight(device_row.get("switch_highlight")),
        _xlsx_cell_str(device_row.get("video_count")),
        _xlsx_cell_str(device_row.get("follower_count")),
        _xlsx_cell_str(device_row.get("follower_growth")),
        _xlsx_cell_str(device_row.get("updated_at")),
        _xlsx_cell_str(acct.get("row_index")),
        _xlsx_cell_str(acct.get("password")),
        _xlsx_cell_str(acct.get("email")),
        _xlsx_cell_str(acct.get("email_password")),
        _xlsx_cell_str(acct.get("api")),
        "是" if int(acct.get("is_login") or 0) else "否",
        _xlsx_cell_str(acct.get("login_time")),
        _xlsx_cell_str(acct.get("region")),
        _xlsx_cell_str(acct.get("assigned_alias")),
        _xlsx_cell_str(acct.get("video_count")),
        _xlsx_cell_str(acct.get("follower_count")),
        _xlsx_cell_str(acct.get("follower_growth")),
        _normalize_switch_highlight(acct.get("basic_profile_modified")),
    ]


def _parse_backup_archive_row_indices_param(raw: str) -> List[int]:
    out: List[int] = []
    seen: set[int] = set()
    for part in str(raw or "").split(","):
        text = str(part or "").strip()
        if not text:
            continue
        try:
            idx = int(text)
        except (TypeError, ValueError):
            continue
        if idx < 0 or idx in seen:
            continue
        seen.add(idx)
        out.append(idx)
    return out


def _export_backup_archive_xlsx(
    owner: str,
    device_group: str = "",
    row_indices: List[int] | None = None,
) -> dict[str, Any]:
    if Workbook is None:
        return {"code": 1, "msg": "缺少 openpyxl 依赖，无法导出 xlsx"}
    conn = _get_db()
    try:
        device_rows = _list_device_editor_rows_for_backup_export(conn, owner, device_group)
        accounts_by_user = _account_editor_rows_by_username(conn, owner)
    finally:
        conn.close()
    if row_indices:
        wanted = set(int(x) for x in row_indices if int(x) >= 0)
        device_rows = [r for r in device_rows if int(r.get("row_index") or -1) in wanted]
        if not device_rows:
            return {"code": 1, "msg": "所选记录不存在或不在当前分组筛选下"}
    if not device_rows:
        return {"code": 1, "msg": "当前筛选下没有可导出的备份档案"}

    export_dir = (BASE_DIR / "exports").resolve()
    export_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    group_tag = re.sub(r"[^\w\-]+", "_", str(device_group or "all").strip())[:32] or "all"
    select_tag = "selected" if row_indices else "all"
    filename = f"backup_archive_{group_tag}_{select_tag}_{timestamp}.xlsx"
    file_path = export_dir / filename

    try:
        wb = Workbook(write_only=True)
        ws = wb.create_sheet(title="backup_archive")
        ws.append(BACKUP_ARCHIVE_XLSX_HEADERS)
        for device_row in device_rows:
            uname = _normalized_alias(device_row.get("username")).lstrip("@").lower()
            account_row = accounts_by_user.get(uname) if uname else None
            ws.append(_backup_archive_export_row(device_row, account_row))
        wb.save(file_path)
    except Exception as exc:
        return {"code": 1, "msg": f"生成 xlsx 失败：{exc}"}
    if not file_path.is_file() or int(file_path.stat().st_size or 0) <= 0:
        return {"code": 1, "msg": "导出文件生成失败"}
    return {
        "code": 0,
        "msg": "导出成功",
        "filename": filename,
        "file_path": str(file_path),
        "count": len(device_rows),
    }


def _parse_backup_archive_xlsx_sheet(sheet) -> tuple[List[str], List[dict[str, str]]]:
    rows_iter = list(sheet.iter_rows(values_only=True))
    if not rows_iter:
        return [], []
    header_cells = [_xlsx_cell_str(cell) for cell in rows_iter[0]]
    if not any(header_cells):
        return [], []
    header_map: Dict[str, int] = {}
    for idx, name in enumerate(header_cells):
        key = name.strip()
        if key and key not in header_map:
            header_map[key] = idx
    parsed: List[dict[str, str]] = []
    for raw in rows_iter[1:]:
        if not raw or not any(cell is not None and str(cell).strip() for cell in raw):
            continue
        item: dict[str, str] = {}
        for key, idx in header_map.items():
            val = raw[idx] if idx < len(raw) else None
            item[key] = _xlsx_cell_str(val)
        parsed.append(item)
    headers = list(header_map.keys())
    return headers, parsed


def _backup_archive_import_user_key(username: str) -> str:
    return _normalized_alias(username).lstrip("@").lower()


def _device_backup_payload_empty(row: dict[str, Any]) -> bool:
    fields = (
        "backup_path",
        "backup_name",
        "backup_time",
        "backup_status",
        "video_count",
        "follower_count",
        "follower_growth",
    )
    return not any(str(row.get(f) or "").strip() for f in fields)


def _pick_existing_device_row_for_import(
    candidates: List[dict[str, Any]],
    row: dict[str, str],
) -> dict[str, Any] | None:
    if not candidates:
        return None
    device_ri_raw = row.get("row_index", "")
    device_ri: int | None = None
    if device_ri_raw != "":
        try:
            device_ri = int(float(device_ri_raw))
        except (TypeError, ValueError):
            device_ri = None
    if device_ri is not None and device_ri >= 0:
        for item in candidates:
            if int(item["row_index"]) == device_ri:
                return item
    empty_rows = [item for item in candidates if _device_backup_payload_empty(item)]
    if len(empty_rows) == 1:
        return empty_rows[0]
    pool = empty_rows or candidates
    return min(pool, key=lambda item: int(item["row_index"]))


def _import_backup_archive_xlsx_rows(owner: str, parsed_rows: List[dict[str, str]]) -> dict[str, Any]:
    if not parsed_rows:
        return {"code": 1, "msg": "xlsx 中没有可导入的数据行"}
    conn = _get_db()
    try:
        now = now_text()
        accounts_by_user = _account_editor_rows_by_username(conn, owner)
        existing_device_by_user: Dict[str, List[dict[str, Any]]] = {}
        for r in conn.execute(
            """
            SELECT row_index, username, backup_path, backup_name, backup_time, backup_status,
                   video_count, follower_count, follower_growth
            FROM device_editor_rows
            WHERE owner_username = ?
            """,
            (owner,),
        ).fetchall():
            user_key = _backup_archive_import_user_key(r["username"])
            if not user_key:
                continue
            existing_device_by_user.setdefault(user_key, []).append(
                {
                    "row_index": int(r["row_index"]),
                    "username": str(r["username"] or ""),
                    "backup_path": str(r["backup_path"] or ""),
                    "backup_name": str(r["backup_name"] or ""),
                    "backup_time": str(r["backup_time"] or ""),
                    "backup_status": str(r["backup_status"] or ""),
                    "video_count": str(r["video_count"] or ""),
                    "follower_count": str(r["follower_count"] or ""),
                    "follower_growth": str(r["follower_growth"] or ""),
                }
            )
        max_device_ri = int(
            conn.execute(
                "SELECT COALESCE(MAX(row_index), -1) FROM device_editor_rows WHERE owner_username = ?",
                (owner,),
            ).fetchone()[0]
        )
        max_account_ri = int(
            conn.execute(
                "SELECT COALESCE(MAX(row_index), -1) FROM account_editor_rows WHERE owner_username = ?",
                (owner,),
            ).fetchone()[0]
        )

        imported = 0
        updated = 0
        skipped_duplicate = 0
        skipped_empty = 0
        seen_in_file: set[str] = set()

        for row in parsed_rows:
            username = _normalized_alias(row.get("username")).lstrip("@")
            if not username:
                skipped_empty += 1
                continue
            user_key = username.lower()
            if user_key in seen_in_file:
                skipped_duplicate += 1
                continue
            seen_in_file.add(user_key)
            existing_device_candidates = existing_device_by_user.get(user_key, [])
            merge_existing_device = bool(existing_device_candidates)

            assigned_alias = _blank_if_none(row.get("assigned_alias")) or _blank_if_none(row.get("device_alias"))
            account_assigned = _blank_if_none(row.get("account_assigned_alias")) or assigned_alias

            account_ri_raw = row.get("account_row_index", "")
            account_ri: int | None = None
            if account_ri_raw != "":
                try:
                    account_ri = int(float(account_ri_raw))
                except (TypeError, ValueError):
                    account_ri = None
            existing_acct = accounts_by_user.get(user_key)
            if account_ri is None or account_ri < 0:
                account_ri = int(existing_acct["row_index"]) if existing_acct else max_account_ri + 1
            if existing_acct is None and account_ri > max_account_ri:
                max_account_ri = account_ri

            acct_password = _blank_if_none(row.get("account_password"))
            acct_email = _blank_if_none(row.get("account_email"))
            acct_email_password = _blank_if_none(row.get("account_email_password"))
            acct_api = _blank_if_none(row.get("account_api"))
            acct_region = _blank_if_none(row.get("account_region")) or _blank_if_none(row.get("region"))
            acct_login_time = _blank_if_none(row.get("account_login_time"))
            acct_is_login = 1 if _parse_editor_login_flag(row.get("account_is_login")) else 0
            acct_video = _blank_if_none(row.get("account_video_count")) or _blank_if_none(row.get("video_count"))
            if not acct_video:
                acct_video = str(NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN)
            acct_follower = _blank_if_none(row.get("account_follower_count")) or _blank_if_none(
                row.get("follower_count")
            )
            acct_growth = _blank_if_none(row.get("account_follower_growth")) or _blank_if_none(
                row.get("follower_growth")
            )
            acct_basic = _normalize_switch_highlight(row.get("account_basic_profile_modified"))

            conn.execute(
                """
                INSERT INTO account_editor_rows
                    (owner_username, row_index, username, password, email, email_password, api, is_login, login_time,
                     region, assigned_alias, video_count, follower_count, follower_growth, basic_profile_modified, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner_username, row_index) DO UPDATE SET
                    username = excluded.username,
                    password = CASE WHEN TRIM(excluded.password) <> '' THEN excluded.password ELSE account_editor_rows.password END,
                    email = CASE WHEN TRIM(excluded.email) <> '' THEN excluded.email ELSE account_editor_rows.email END,
                    email_password = CASE WHEN TRIM(excluded.email_password) <> '' THEN excluded.email_password ELSE account_editor_rows.email_password END,
                    api = CASE WHEN TRIM(excluded.api) <> '' THEN excluded.api ELSE account_editor_rows.api END,
                    is_login = excluded.is_login,
                    login_time = CASE WHEN TRIM(excluded.login_time) <> '' THEN excluded.login_time ELSE account_editor_rows.login_time END,
                    region = CASE WHEN TRIM(excluded.region) <> '' THEN excluded.region ELSE account_editor_rows.region END,
                    assigned_alias = CASE
                        WHEN TRIM(COALESCE(account_editor_rows.assigned_alias, '')) <> '' THEN account_editor_rows.assigned_alias
                        WHEN TRIM(excluded.assigned_alias) <> '' THEN excluded.assigned_alias
                        ELSE account_editor_rows.assigned_alias
                    END,
                    video_count = CASE WHEN TRIM(excluded.video_count) <> '' THEN excluded.video_count ELSE account_editor_rows.video_count END,
                    follower_count = CASE WHEN TRIM(excluded.follower_count) <> '' THEN excluded.follower_count ELSE account_editor_rows.follower_count END,
                    follower_growth = CASE WHEN TRIM(excluded.follower_growth) <> '' THEN excluded.follower_growth ELSE account_editor_rows.follower_growth END,
                    basic_profile_modified = COALESCE(NULLIF(TRIM(excluded.basic_profile_modified), ''), account_editor_rows.basic_profile_modified),
                    updated_at = excluded.updated_at
                """,
                (
                    owner,
                    account_ri,
                    username,
                    acct_password,
                    acct_email,
                    acct_email_password,
                    acct_api,
                    acct_is_login,
                    acct_login_time,
                    acct_region,
                    account_assigned,
                    acct_video,
                    acct_follower,
                    acct_growth,
                    acct_basic,
                    now,
                ),
            )
            accounts_by_user[user_key] = {
                "row_index": account_ri,
                "username": username,
                "assigned_alias": account_assigned,
            }

            device_ri_raw = row.get("row_index", "")
            device_ri: int | None = None
            if merge_existing_device:
                picked = _pick_existing_device_row_for_import(existing_device_candidates, row)
                device_ri = int(picked["row_index"]) if picked else None
            if device_ri_raw != "" and not merge_existing_device:
                try:
                    device_ri = int(float(device_ri_raw))
                except (TypeError, ValueError):
                    device_ri = None
            if device_ri is None or device_ri < 0:
                if merge_existing_device:
                    skipped_duplicate += 1
                    continue
                max_device_ri += 1
                device_ri = max_device_ri
            elif not merge_existing_device:
                max_device_ri = max(max_device_ri, device_ri)

            device_alias = _blank_if_none(row.get("device_alias")) or assigned_alias
            sh = _normalize_switch_highlight(row.get("switch_highlight"))
            device_conflict_sql = (
                """
                ON CONFLICT(owner_username, row_index) DO UPDATE SET
                    device_alias = CASE WHEN TRIM(excluded.device_alias) <> '' THEN excluded.device_alias ELSE device_editor_rows.device_alias END,
                    device_id = CASE WHEN TRIM(excluded.device_id) <> '' AND TRIM(COALESCE(device_editor_rows.device_id, '')) = '' THEN excluded.device_id ELSE device_editor_rows.device_id END,
                    ip = CASE WHEN TRIM(excluded.ip) <> '' AND TRIM(COALESCE(device_editor_rows.ip, '')) = '' THEN excluded.ip ELSE device_editor_rows.ip END,
                    region = CASE WHEN TRIM(excluded.region) <> '' AND TRIM(COALESCE(device_editor_rows.region, '')) = '' THEN excluded.region ELSE device_editor_rows.region END,
                    username = excluded.username,
                    assigned_alias = CASE
                        WHEN TRIM(COALESCE(device_editor_rows.assigned_alias, '')) <> '' THEN device_editor_rows.assigned_alias
                        WHEN TRIM(excluded.assigned_alias) <> '' THEN excluded.assigned_alias
                        ELSE device_editor_rows.assigned_alias
                    END,
                    backup_path = CASE WHEN TRIM(excluded.backup_path) <> '' THEN excluded.backup_path ELSE device_editor_rows.backup_path END,
                    backup_name = CASE WHEN TRIM(excluded.backup_name) <> '' THEN excluded.backup_name ELSE device_editor_rows.backup_name END,
                    backup_time = CASE WHEN TRIM(excluded.backup_time) <> '' THEN excluded.backup_time ELSE device_editor_rows.backup_time END,
                    backup_status = CASE WHEN TRIM(excluded.backup_status) <> '' THEN excluded.backup_status ELSE device_editor_rows.backup_status END,
                    remark = CASE WHEN TRIM(excluded.remark) <> '' AND TRIM(COALESCE(device_editor_rows.remark, '')) = '' THEN excluded.remark ELSE device_editor_rows.remark END,
                    switch_highlight = CASE
                        WHEN TRIM(excluded.switch_highlight) = '是' THEN '是'
                        ELSE device_editor_rows.switch_highlight
                    END,
                    video_count = CASE WHEN TRIM(excluded.video_count) <> '' AND TRIM(COALESCE(device_editor_rows.video_count, '')) = '' THEN excluded.video_count ELSE device_editor_rows.video_count END,
                    follower_count = CASE WHEN TRIM(excluded.follower_count) <> '' AND TRIM(COALESCE(device_editor_rows.follower_count, '')) = '' THEN excluded.follower_count ELSE device_editor_rows.follower_count END,
                    follower_growth = CASE WHEN TRIM(excluded.follower_growth) <> '' AND TRIM(COALESCE(device_editor_rows.follower_growth, '')) = '' THEN excluded.follower_growth ELSE device_editor_rows.follower_growth END,
                    updated_at = excluded.updated_at
                """
                if merge_existing_device
                else """
                ON CONFLICT(owner_username, row_index) DO UPDATE SET
                    device_alias = excluded.device_alias,
                    device_id = excluded.device_id,
                    ip = excluded.ip,
                    region = excluded.region,
                    username = excluded.username,
                    assigned_alias = excluded.assigned_alias,
                    backup_path = excluded.backup_path,
                    backup_name = excluded.backup_name,
                    backup_time = excluded.backup_time,
                    backup_status = excluded.backup_status,
                    remark = excluded.remark,
                    switch_highlight = excluded.switch_highlight,
                    video_count = excluded.video_count,
                    follower_count = excluded.follower_count,
                    follower_growth = excluded.follower_growth,
                    updated_at = excluded.updated_at
                """
            )
            device_video = _blank_if_none(row.get("video_count")) or acct_video
            conn.execute(
                f"""
                INSERT INTO device_editor_rows
                    (owner_username, row_index, device_alias, device_id, ip, region, username, assigned_alias,
                     backup_path, backup_name, backup_time, backup_status, remark, switch_highlight,
                     video_count, follower_count, follower_growth, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                {device_conflict_sql}
                """,
                (
                    owner,
                    device_ri,
                    device_alias,
                    _blank_if_none(row.get("device_id")),
                    _blank_if_none(row.get("ip")),
                    _blank_if_none(row.get("region")),
                    username,
                    assigned_alias,
                    _blank_if_none(row.get("backup_path")),
                    _blank_if_none(row.get("backup_name")),
                    _blank_if_none(row.get("backup_time")),
                    _blank_if_none(row.get("backup_status")),
                    _blank_if_none(row.get("remark")),
                    sh,
                    device_video,
                    _blank_if_none(row.get("follower_count")),
                    _blank_if_none(row.get("follower_growth")),
                    now,
                ),
            )
            existing_device_by_user[user_key] = [
                {
                    "row_index": device_ri,
                    "username": username,
                    "backup_path": _blank_if_none(row.get("backup_path")),
                    "backup_name": _blank_if_none(row.get("backup_name")),
                    "backup_time": _blank_if_none(row.get("backup_time")),
                    "backup_status": _blank_if_none(row.get("backup_status")),
                    "video_count": _blank_if_none(row.get("video_count")),
                    "follower_count": _blank_if_none(row.get("follower_count")),
                    "follower_growth": _blank_if_none(row.get("follower_growth")),
                }
            ]
            if merge_existing_device:
                updated += 1
            else:
                imported += 1

        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
    finally:
        conn.close()

    return {
        "code": 0,
        "msg": "导入完成",
        "imported": imported,
        "updated": updated,
        "skipped_duplicate": skipped_duplicate,
        "skipped_empty": skipped_empty,
    }


@app.get("/api/admin/editor/backup_archive/export_xlsx")
def export_backup_archive_xlsx(
    device_group: str = "",
    row_indices: str = "",
    authorization: str | None = Header(default=None),
):
    """生成 xlsx 并返回 JSON（含 filename）；前端再通过 export_file 下载。"""
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    owner = str(session["username"] or "").strip()
    indices = _parse_backup_archive_row_indices_param(row_indices)
    result = _export_backup_archive_xlsx(
        owner,
        device_group,
        indices if indices else None,
    )
    return result


@app.get("/api/admin/editor/backup_archive/export_file")
def download_backup_archive_export_file(
    filename: str = "",
    authorization: str | None = Header(default=None),
):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    safe_name = Path(str(filename or "").strip()).name
    if not safe_name.endswith(".xlsx") or not safe_name.startswith("backup_archive_"):
        return {"code": 1, "msg": "非法文件名"}
    file_path = (BASE_DIR / "exports" / safe_name).resolve()
    export_root = (BASE_DIR / "exports").resolve()
    if not str(file_path).startswith(str(export_root)) or not file_path.is_file():
        return {"code": 1, "msg": "文件不存在"}
    return FileResponse(
        path=str(file_path),
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        filename=safe_name,
    )


@app.post("/api/admin/editor/backup_archive/import_xlsx")
def import_backup_archive_xlsx(
    file: UploadFile = File(...),
    authorization: str | None = Header(default=None),
):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    filename = (file.filename or "").lower()
    if not filename.endswith(".xlsx"):
        return {"code": 1, "msg": "仅支持 xlsx 文件"}
    if load_workbook is None:
        return {"code": 1, "msg": "缺少 openpyxl 依赖，无法导入 xlsx"}
    owner = str(session["username"] or "").strip()
    workbook = load_workbook(file.file, read_only=True, data_only=True)
    sheet = workbook[workbook.sheetnames[0]]
    _headers, parsed_rows = _parse_backup_archive_xlsx_sheet(sheet)
    if not parsed_rows:
        return {"code": 1, "msg": "xlsx 中没有可导入的数据行（需含表头与至少一行数据）"}
    return _import_backup_archive_xlsx_rows(owner, parsed_rows)


@app.post("/api/admin/editor/device_rows/upsert")
def upsert_device_editor_row(payload: DeviceEditorRowUpsertRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    conn = _get_db()
    try:
        now = now_text()
        owner = str(session["username"] or "").strip()
        row_index = int(payload.row_index)
        device_alias = payload.device_alias.strip()
        username = payload.username.strip()
        assigned_alias = _blank_if_none(payload.assigned_alias)
        if not assigned_alias and device_alias:
            assigned_alias = device_alias
        effective_alias = assigned_alias or device_alias
        if username and effective_alias:
            alias_err = _validate_username_device_effective_alias(
                conn, owner, username, effective_alias
            )
            if alias_err:
                return {"code": 1, "msg": alias_err}
        elif effective_alias:
            alias_row = conn.execute(
                """
                SELECT username
                FROM account_editor_rows
                WHERE owner_username = ? AND TRIM(assigned_alias) = TRIM(?)
                LIMIT 1
                """,
                (owner, effective_alias),
            ).fetchone()
            if not alias_row:
                return {"code": 1, "msg": f"别名「{effective_alias}」尚未在账号表单中分配，不能写入设备档案"}

        sh = _normalize_switch_highlight(payload.switch_highlight)
        default_vc = _default_video_count_for_new_row(conn, owner, username)
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, switch_highlight, video_count, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(owner_username, row_index) DO UPDATE SET
                device_alias = excluded.device_alias,
                device_id = excluded.device_id,
                ip = excluded.ip,
                region = excluded.region,
                username = excluded.username,
                assigned_alias = CASE
                    WHEN TRIM(excluded.assigned_alias) <> '' THEN excluded.assigned_alias
                    WHEN TRIM(device_editor_rows.assigned_alias) <> '' THEN device_editor_rows.assigned_alias
                    WHEN TRIM(excluded.device_alias) <> '' THEN excluded.device_alias
                    ELSE device_editor_rows.assigned_alias
                END,
                backup_path = excluded.backup_path,
                backup_name = excluded.backup_name,
                backup_time = excluded.backup_time,
                backup_status = excluded.backup_status,
                remark = excluded.remark,
                switch_highlight = excluded.switch_highlight,
                updated_at = excluded.updated_at
            """,
            (
                owner,
                row_index,
                device_alias,
                payload.device_id.strip(),
                payload.ip.strip(),
                payload.region.strip(),
                username,
                assigned_alias,
                payload.backup_path.strip(),
                payload.backup_name.strip(),
                payload.backup_time.strip(),
                payload.backup_status.strip(),
                payload.remark.strip(),
                sh,
                default_vc,
                now,
            ),
        )
        if username and effective_alias:
            _sync_account_assigned_alias_for_username(
                conn, owner, username, effective_alias, only_if_empty=True
            )
            conn.execute(
                """
                UPDATE device_editor_rows
                SET assigned_alias = ?, updated_at = ?
                WHERE owner_username = ? AND row_index = ?
                  AND TRIM(COALESCE(assigned_alias, '')) = ''
                  AND TRIM(?) <> ''
                """,
                (effective_alias, now, owner, row_index, effective_alias),
            )
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
        _reset_device_editor_read_maintain_schedule(owner)
        return {"code": 0, "msg": "设备记录已保存", "row_index": payload.row_index}
    finally:
        conn.close()


@app.post("/api/admin/editor/device_rows/set_switch_highlight")
def set_device_editor_switch_highlight(payload: DeviceEditorSwitchHighlightRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    owner = str(session.get("username") or "").strip()
    if not owner:
        return {"code": 401, "msg": "请先登录"}
    row_index = int(payload.row_index)
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT row_index, assigned_alias, device_alias, device_id
            FROM device_editor_rows
            WHERE owner_username = ? AND row_index = ?
            LIMIT 1
            """,
            (owner, row_index),
        ).fetchone()
        if not row:
            return {"code": 1, "msg": "设备记录不存在"}
        eff_assigned = str(row["assigned_alias"] or "").strip()
        eff_device = str(row["device_alias"] or "").strip()
        bucket = eff_assigned if eff_assigned else eff_device
        blocked = _reject_if_auto_switch_manual_ops(
            device_id=str(row["device_id"] or "").strip(),
            owner_username=owner,
            alias=bucket,
        )
        if blocked:
            return blocked
        now = now_text()
        conn.execute(
            f"""
            UPDATE device_editor_rows
            SET switch_highlight = '否', updated_at = ?
            WHERE owner_username = ? AND LOWER(TRIM({_backup_record_alias_expr()})) = LOWER(TRIM(?))
            """,
            (now, owner, bucket),
        )
        conn.execute(
            """
            UPDATE device_editor_rows
            SET switch_highlight = '是', updated_at = ?
            WHERE owner_username = ? AND row_index = ?
            """,
            (now, owner, row_index),
        )
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
        _reset_device_editor_read_maintain_schedule(owner)
        return {"code": 0, "msg": "切号高亮已更新", "row_index": row_index}
    finally:
        conn.close()


@app.post("/api/admin/editor/device_rows/auto_switch_excluded")
def set_device_editor_auto_switch_excluded(
    payload: DeviceEditorAutoSwitchExcludedRequest, authorization: str | None = Header(default=None)
):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "璇峰厛鐧诲綍"}
    owner = str(session.get("username") or "").strip()
    if not owner:
        return {"code": 401, "msg": "璇峰厛鐧诲綍"}
    row_index = int(payload.row_index)
    excluded_i = 1 if bool(payload.excluded) else 0
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT row_index, username, assigned_alias, device_alias
            FROM device_editor_rows
            WHERE owner_username = ? AND row_index = ?
            LIMIT 1
            """,
            (owner, row_index),
        ).fetchone()
        if not row:
            return {"code": 1, "msg": "设备记录不存在"}
        item = {
            "row_index": int(row["row_index"] or 0),
            "username": str(row["username"] or "").strip(),
            "assigned_alias": str(row["assigned_alias"] or "").strip(),
            "device_alias": str(row["device_alias"] or "").strip(),
        }
        alias_key = _device_editor_effective_alias(item)
        now = now_text()
        cur = conn.execute(
            """
            UPDATE device_editor_rows
            SET auto_switch_excluded = ?, updated_at = ?
            WHERE owner_username = ? AND row_index = ?
            """,
            (excluded_i, now, owner, row_index),
        )
        if int(cur.rowcount or 0) <= 0:
            return {"code": 1, "msg": "未更新任何记录"}
        conn.commit()
    finally:
        conn.close()

    plan_info = _refresh_auto_switch_plans_for_alias(owner, alias_key)
    sync_info = _resync_auto_switch_devices_for_alias(
        owner, alias_key, int(plan_info.get("bucket_size") or 0)
    )
    _reset_device_editor_read_maintain_schedule(owner)
    return {
        "code": 0,
        "msg": "自动切号参与状态已更新",
        "row_index": row_index,
        "excluded": bool(excluded_i),
        "alias": alias_key,
        "username": item["username"],
        "bucket_size": int(plan_info.get("bucket_size") or 0),
        "plan_regenerated": bool(plan_info.get("plan_regenerated")),
        "devices_notified": int(sync_info.get("notified") or 0),
        "devices_disabled": int(sync_info.get("disabled") or 0),
        "session_id": str(sync_info.get("session_id") or ""),
        "bundle_revision": int(sync_info.get("bundle_revision") or 0),
    }


@app.post("/api/admin/editor/device_rows/delete")
def delete_device_editor_row(payload: DeviceEditorRowDeleteRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    conn = _get_db()
    try:
        conn.execute(
            """
            DELETE FROM device_editor_rows
            WHERE owner_username = ? AND row_index = ?
            """,
            (session["username"], int(payload.row_index)),
        )
        _ensure_device_editor_switch_highlight_invariants(conn, str(session["username"] or "").strip())
        conn.commit()
        _reset_device_editor_read_maintain_schedule(str(session["username"] or "").strip())
        return {"code": 0, "msg": "设备记录已删除", "row_index": payload.row_index}
    finally:
        conn.close()


@app.post("/api/admin/device/delete")
def admin_device_delete(payload: DeviceIdAliasRequest, authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err

    device_id = (payload.device_id or "").strip()
    alias = (payload.alias or "").strip()
    if not device_id:
        return {"code": 1, "msg": "device_id不能为空"}

    conn = _get_db()
    row = None
    try:
        row = conn.execute(
            "SELECT device_id, owner_username, alias, fingerprint FROM client_devices WHERE device_id = ?",
            (device_id,),
        ).fetchone()
        if not row:
            return {"code": 1, "msg": "设备不存在"}
        device = DeviceInfo(
            alias=str(row[2] or ""),
            fingerprint=str(row[3] or ""),
            owner_username=str(row[1] or ""),
        )
        if not _device_session_allowed(device, session):
            return {"code": 403, "msg": "无权删除该设备"}
        if alias and str(row[2] or "").strip() and str(row[2]).strip() != alias:
            return {"code": 1, "msg": "device_id与别名不匹配"}

        conn.execute("DELETE FROM client_devices WHERE device_id = ?", (device_id,))
        conn.commit()
    except Exception as exc:
        try:
            conn.rollback()
        except Exception:
            pass
        print(f"[{now_text()}] 系统 | 删除设备失败：{device_id} / {alias} / {exc}")
        return {"code": 1, "msg": f"删除设备失败：{exc}"}
    finally:
        conn.close()

    try:
        with store.lock:
            store.devices.pop(device_id, None)
            fp = str(row[3] or "").strip() if row else ""
            if fp:
                store.fingerprint_to_device.pop(fp, None)
            store.pending_device_ops.pop(device_id, None)
    except Exception as exc:
        print(f"[{now_text()}] 系统 | 删除设备后清理内存状态失败：{device_id} / {alias} / {exc}")

    print(f"[{now_text()}] 系统 | 设备已删除：{alias or str(row[2] or '')}/{device_id}")
    return {"code": 0, "msg": "设备已删除", "device_id": device_id}


@app.post("/api/admin/device_tiktok/restore")
def admin_device_tiktok_restore(payload: DeviceTikTokRestoreRequest, authorization: str | None = Header(default=None)):
    blocked = _reject_if_auto_switch_manual_ops(device_id=payload.device_id, alias=payload.alias)
    if blocked:
        return blocked
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}

    backup_name = Path(payload.backup_name).name
    owner = str(session["username"] or "").strip()
    cut_target_ri = (
        int(payload.cut_switch_target_row_index)
        if payload.cut_switch_target_row_index is not None and int(payload.cut_switch_target_row_index) >= 0
        else -1
    )
    highlight_applied = False
    if owner and cut_target_ri >= 0:
        highlight_applied = _apply_cut_switch_highlight_intent(owner, cut_target_ri)

    conn = _get_db()
    try:
        row = conn.execute(
            f"""
            SELECT row_index, backup_path, username, {_backup_record_alias_expr()} AS mobile_alias, assigned_alias
            FROM device_editor_rows
            WHERE backup_name = ? AND owner_username = ?
            ORDER BY updated_at DESC, row_index DESC
            LIMIT 1
            """,
            (backup_name, session["username"]),
        ).fetchone()
        record_row_index = int(row[0]) if row else -1
        if record_row_index < 0 and cut_target_ri >= 0:
            record_row_index = cut_target_ri
        backup_path = str(row[1] or "").strip() if row else ""
        record_username = str(row[2] or "").strip() if row else ""
        record_mobile_alias = str(row[3] or "").strip() if row else ""
        record_assigned_alias = str(row[4] or "").strip() if row else ""
        record_video_count = None
        record_follower_count = None
        record_new_follower_count = None
    finally:
        conn.close()

    if owner and record_row_index >= 0 and cut_target_ri < 0:
        conn = _get_db()
        try:
            _db_set_env_status(conn, owner, record_row_index, ENV_STATUS_PENDING, now_text())
            conn.commit()
        finally:
            conn.close()

    op_id = f"op_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"
    restore_payload: dict[str, Any] = {
        "device_id": payload.device_id,
        "alias": payload.alias,
        "package_name": TIKTOK_PACKAGE_NAME,
        "backup_name": backup_name,
        "backup_path": backup_path or backup_name,
        "username": record_username,
        "mobile_alias": record_mobile_alias,
        "assigned_alias": record_assigned_alias or record_mobile_alias,
        "video_count": record_video_count,
        "follower_count": record_follower_count,
        "new_follower_count": record_new_follower_count,
        "skip_pm_clear": bool(payload.skip_pm_clear),
    }
    if record_row_index >= 0:
        restore_payload["restore_highlight_row_index"] = record_row_index
    _resume_device_for_cloud_op(
        payload.device_id,
        f"[{now_text()}] 云端后台 | 已入队还原备份/切号任务，等待设备拉取（alias={payload.alias}）",
    )
    _enqueue_device_op(
        payload.device_id,
        {
            "op_id": op_id,
            "op_type": "tiktok_restore",
            "payload": restore_payload,
            "created_at": now_text(),
        },
    )
    _append_device_log(
        payload.device_id,
        f"[{now_text()}] 设备任务已入队 op_id={op_id} type=tiktok_restore alias={payload.alias}",
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：清理并还原该备份（先 pm clear，再解压）"
        if not payload.skip_pm_clear
        else "已下发设备端任务：还原该备份（跳过 pm clear，依赖前序已清空数据）",
        "op_id": op_id,
        "backup_path": backup_path or backup_name,
        "record_username": record_username,
        "record_mobile_alias": record_mobile_alias,
        "switch_highlight_applied": highlight_applied,
    }


def _batch_stream_normalize_header_cell(value: object | None) -> str:
    text = _xlsx_cell_str(value).lower().replace(" ", "").replace("_", "")
    return text


def _batch_stream_match_header_key(header_cell: str) -> str | None:
    norm = _batch_stream_normalize_header_cell(header_cell)
    if not norm:
        return None
    for key, aliases in BATCH_STREAM_HEADER_ALIASES.items():
        for alias in aliases:
            alias_norm = alias.lower().replace(" ", "").replace("_", "")
            if norm == alias_norm:
                return key
    return None


def _batch_stream_parse_workbook(file_path: Path) -> tuple[Dict[str, int], int, List[Dict[str, Any]]]:
    if load_workbook is None:
        raise RuntimeError("缺少 openpyxl 依赖，无法解析 xlsx")
    wb = load_workbook(file_path, read_only=True, data_only=True)
    try:
        ws = wb.worksheets[0]
        header_row = next(ws.iter_rows(min_row=1, max_row=1, values_only=True), None)
        if not header_row:
            raise ValueError("Excel 首行表头为空")
        header_map: Dict[str, int] = {}
        status_col = -1
        for idx, cell in enumerate(header_row):
            key = _batch_stream_match_header_key(_xlsx_cell_str(cell))
            if key and key not in header_map:
                header_map[key] = idx
            elif not _xlsx_cell_str(cell) and status_col < 0:
                status_col = idx
        required = ("username", "password", "email", "email_password", "api", "assigned_alias")
        missing = [k for k in required if k not in header_map]
        if missing:
            raise ValueError(f"Excel 缺少必要列：{', '.join(missing)}")
        if status_col < 0:
            status_col = max(header_map.values()) + 1
        rows: List[Dict[str, Any]] = []
        for row_idx, row in enumerate(ws.iter_rows(min_row=2, values_only=True), start=2):
            if not row:
                continue
            alias_idx = header_map["assigned_alias"]
            alias_val = _xlsx_cell_str(row[alias_idx] if alias_idx < len(row) else "")
            username_val = _xlsx_cell_str(row[header_map["username"]] if header_map["username"] < len(row) else "")
            if not alias_val and not username_val:
                continue
            item = {
                "sheet_row": row_idx,
                "username": username_val,
                "password": _xlsx_cell_str(row[header_map["password"]] if header_map["password"] < len(row) else ""),
                "email": _xlsx_cell_str(row[header_map["email"]] if header_map["email"] < len(row) else ""),
                "email_password": _xlsx_cell_str(
                    row[header_map["email_password"]] if header_map["email_password"] < len(row) else ""
                ),
                "api": _xlsx_cell_str(row[header_map["api"]] if header_map["api"] < len(row) else ""),
                "assigned_alias": alias_val,
                "status": _xlsx_cell_str(row[status_col] if status_col < len(row) else ""),
            }
            rows.append(item)
        return header_map, status_col, rows
    finally:
        wb.close()


def _batch_stream_rows_for_alias(session: Dict[str, Any], alias: str) -> List[Dict[str, Any]]:
    alias_key = _normalize_alias_key(alias)
    out: List[Dict[str, Any]] = []
    for row in session.get("rows") or []:
        row_alias = _normalize_alias_key(str(row.get("assigned_alias") or ""))
        if row_alias != alias_key:
            continue
        if str(row.get("status") or "").strip():
            continue
        out.append(row)
    return out


def _batch_stream_write_status(file_path: Path, sheet_row: int, status_col: int, status_text: str) -> None:
    if load_workbook is None:
        raise RuntimeError("缺少 openpyxl 依赖，无法写入 xlsx")
    wb = load_workbook(file_path)
    try:
        ws = wb.worksheets[0]
        ws.cell(row=sheet_row, column=status_col + 1, value=status_text)
        wb.save(file_path)
    finally:
        wb.close()


def _batch_stream_get_session(session_id: str, owner: str = "") -> Dict[str, Any] | None:
    sid = str(session_id or "").strip()
    if not sid:
        return None
    with _batch_stream_session_lock:
        session = _batch_stream_sessions.get(sid)
        if not session:
            return None
        if owner and str(session.get("owner") or "").strip() != owner:
            return None
        return session


def _batch_stream_device_in_active(device_id: str) -> bool:
    did = str(device_id or "").strip()
    if not did:
        return False
    with _batch_stream_session_lock:
        return did in _batch_stream_device_active


def _batch_stream_format_status_text(hours_active: float) -> str:
    now_bj = datetime.now(BEIJING_TZ)
    month = now_bj.month
    day = now_bj.day
    hours_text = str(int(hours_active)) if float(hours_active).is_integer() else f"{hours_active:.1f}".rstrip("0").rstrip(".")
    return f"{month}月{day}日已活跃{hours_text}小时"


@app.post("/api/admin/batch_stream/upload")
async def admin_batch_stream_upload(
    file: UploadFile = File(...),
    authorization: str | None = Header(default=None),
):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    if load_workbook is None:
        return {"code": 1, "msg": "缺少 openpyxl 依赖，无法处理 xlsx"}
    owner = str(session.get("username") or "").strip()
    filename = str(file.filename or "").strip()
    if not filename.lower().endswith(".xlsx"):
        return {"code": 1, "msg": "请上传 .xlsx 文件"}
    session_id = f"bs_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"
    owner_dir = (BATCH_STREAM_DIR / re.sub(r"[^\w\-.@]+", "_", owner or "unknown")).resolve()
    owner_dir.mkdir(parents=True, exist_ok=True)
    dest = owner_dir / f"{session_id}.xlsx"
    try:
        content = await file.read()
        dest.write_bytes(content)
        header_map, status_col, rows = _batch_stream_parse_workbook(dest)
    except Exception as exc:
        with contextlib.suppress(Exception):
            dest.unlink(missing_ok=True)
        return {"code": 1, "msg": f"解析 Excel 失败：{exc}"}
    with _batch_stream_session_lock:
        _batch_stream_sessions[session_id] = {
            "session_id": session_id,
            "owner": owner,
            "file_path": str(dest),
            "status_col": status_col,
            "header_map": header_map,
            "rows": rows,
            "created_at": now_text(),
            "devices": {},
        }
    alias_counts: Dict[str, int] = {}
    for row in rows:
        ak = _normalize_alias_key(str(row.get("assigned_alias") or ""))
        if ak:
            alias_counts[ak] = alias_counts.get(ak, 0) + 1
    return {
        "code": 0,
        "msg": "Excel 已上传",
        "session_id": session_id,
        "row_count": len(rows),
        "alias_counts": alias_counts,
        "status_column_index": status_col,
    }


@app.get("/api/admin/batch_stream/status")
def admin_batch_stream_status(
    session_id: str,
    alias: str = "",
    authorization: str | None = Header(default=None),
):
    err, session_login = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    owner = str(session_login.get("username") or "").strip()
    session = _batch_stream_get_session(session_id, owner)
    if not session:
        return {"code": 1, "msg": "会话不存在或无权访问"}
    devices = session.get("devices") or {}
    pending_by_alias: Dict[str, int] = {}
    done_by_alias: Dict[str, int] = {}
    alias_filter = _normalize_alias_key(alias)
    rows_out: List[Dict[str, Any]] = []
    for row in session.get("rows") or []:
        ak = _normalize_alias_key(str(row.get("assigned_alias") or ""))
        if ak:
            if str(row.get("status") or "").strip():
                done_by_alias[ak] = done_by_alias.get(ak, 0) + 1
            else:
                pending_by_alias[ak] = pending_by_alias.get(ak, 0) + 1
        if alias_filter and ak != alias_filter:
            continue
        rows_out.append(
            {
                "sheet_row": int(row.get("sheet_row") or 0),
                "username": str(row.get("username") or "").strip(),
                "email": str(row.get("email") or "").strip(),
                "assigned_alias": str(row.get("assigned_alias") or "").strip(),
                "status": str(row.get("status") or "").strip(),
            }
        )
    done_count = sum(1 for item in rows_out if item.get("status"))
    pending_count = len(rows_out) - done_count
    return {
        "code": 0,
        "msg": "ok",
        "session_id": session_id,
        "created_at": session.get("created_at"),
        "row_count": len(session.get("rows") or []),
        "filtered_row_count": len(rows_out),
        "done_count": done_count,
        "pending_count": pending_count,
        "filtered_alias": str(alias or "").strip(),
        "rows": rows_out,
        "devices": devices,
        "pending_by_alias": pending_by_alias,
        "done_by_alias": done_by_alias,
    }


@app.get("/api/admin/batch_stream/download/{session_id}")
def admin_batch_stream_download(session_id: str, authorization: str | None = Header(default=None)):
    err, session_login = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    owner = str(session_login.get("username") or "").strip()
    session = _batch_stream_get_session(session_id, owner)
    if not session:
        return {"code": 1, "msg": "会话不存在或无权访问"}
    file_path = Path(str(session.get("file_path") or ""))
    if not file_path.is_file():
        return {"code": 1, "msg": "会话文件不存在"}
    return FileResponse(
        path=file_path,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        filename=f"batch_stream_{session_id}.xlsx",
    )


@app.post("/api/admin/batch_stream/start")
def admin_batch_stream_start(payload: BatchStreamStartRequest, authorization: str | None = Header(default=None)):
    err, device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    owner = str(getattr(device, "owner_username", "") or "").strip()
    session = _batch_stream_get_session(payload.session_id, owner)
    if not session:
        return {"code": 1, "msg": "批量带流会话不存在或无权访问"}
    if _batch_stream_device_in_active(payload.device_id):
        return {"code": 1, "msg": "该设备已在批量带流中，请先停止"}
    rows = _batch_stream_rows_for_alias(session, payload.alias)
    if not rows:
        return {"code": 1, "msg": f"Excel 中未找到别名「{payload.alias}」的待处理行（或均已写入活跃记录）"}
    hours = float(payload.hours_per_account)
    activity_rounds = int(payload.activity_rounds or 1)
    op_payload: dict[str, Any] = {
        "device_id": payload.device_id,
        "alias": payload.alias,
        "package_name": payload.package_name.strip() or TIKTOK_PACKAGE_NAME,
        "session_id": payload.session_id,
        "hours_per_account": hours,
        "activity_rounds": activity_rounds,
        "status_column": int(session.get("status_col") or 0),
        "rows": [
            {
                "sheet_row": int(r.get("sheet_row") or 0),
                "username": str(r.get("username") or "").strip(),
                "password": str(r.get("password") or "").strip(),
                "email": str(r.get("email") or "").strip(),
                "email_password": str(r.get("email_password") or "").strip(),
                "api": str(r.get("api") or "").strip(),
            }
            for r in rows
        ],
    }
    with _batch_stream_session_lock:
        _batch_stream_device_active[payload.device_id] = payload.session_id
        dev_state = session.setdefault("devices", {})
        dev_state[payload.device_id] = {
            "alias": payload.alias,
            "status": "running",
            "hours_per_account": hours,
            "activity_rounds": activity_rounds,
            "started_at": now_text(),
            "total_rows": len(rows),
            "completed_rows": 0,
            "stop_requested": False,
        }
    auto_start_disabled = False
    with store.lock:
        dev = store.devices.get(payload.device_id)
        if dev and not dev.auto_start_opt_out:
            dev.auto_start_opt_out = True
            auto_start_disabled = True
    if auto_start_disabled:
        _persist_client_device_to_db(payload.device_id)
        _append_device_log(payload.device_id, "批量带流：云端已自动关闭该设备的自动开启程序")
    _resume_device_for_cloud_op(
        payload.device_id,
        f"[{now_text()}] 云端后台 | 已入队批量带流任务（alias={payload.alias} rows={len(rows)} hours={hours} rounds={activity_rounds}）",
    )
    op_id = _enqueue_tiktok_device_op(payload.device_id, "batch_stream_start", op_payload)
    _append_device_log(
        payload.device_id,
        f"[{now_text()}] 批量带流已入队 op_id={op_id} session={payload.session_id} rows={len(rows)} rounds={activity_rounds}",
    )
    rounds_msg = f"，共 {activity_rounds} 轮" if activity_rounds > 1 else ""
    return {
        "code": 0,
        "msg": f"已下发批量带流：{len(rows)} 个账号，每轮活跃 {hours} 小时{rounds_msg}",
        "op_id": op_id,
        "session_id": payload.session_id,
        "row_count": len(rows),
    }


@app.post("/api/admin/batch_stream/stop")
def admin_batch_stream_stop(payload: BatchStreamStopRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    session_id = ""
    with _batch_stream_session_lock:
        session_id = str(_batch_stream_device_active.get(payload.device_id) or "")
        if session_id:
            session = _batch_stream_sessions.get(session_id)
            if session:
                dev_state = (session.get("devices") or {}).get(payload.device_id) or {}
                dev_state["stop_requested"] = True
                dev_state["status"] = "stopping"
                session.setdefault("devices", {})[payload.device_id] = dev_state
    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "batch_stream_stop",
        {"device_id": payload.device_id, "alias": payload.alias, "session_id": session_id},
    )
    _append_device_log(payload.device_id, f"[{now_text()}] 批量带流停止指令已入队 op_id={op_id}")
    return {"code": 0, "msg": "已下发停止批量带流指令", "op_id": op_id, "session_id": session_id}


@app.post("/api/device/batch_stream/report_row")
def device_batch_stream_report_row(payload: BatchStreamReportRowRequest):
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    owner = str(getattr(device, "owner_username", "") or "").strip()
    session = _batch_stream_get_session(payload.session_id, owner)
    if not session:
        return {"code": 1, "msg": "批量带流会话不存在"}
    status_text = str(payload.status_text or "").strip() or _batch_stream_format_status_text(payload.hours_active)
    file_path = Path(str(session.get("file_path") or ""))
    status_col = int(session.get("status_col") or 0)
    try:
        _batch_stream_write_status(file_path, int(payload.sheet_row), status_col, status_text)
    except Exception as exc:
        return {"code": 1, "msg": f"写入 Excel 失败：{exc}"}
    with _batch_stream_session_lock:
        for row in session.get("rows") or []:
            if int(row.get("sheet_row") or 0) == int(payload.sheet_row):
                row["status"] = status_text
                break
        dev_state = (session.get("devices") or {}).get(payload.device_id) or {}
        dev_state["completed_rows"] = int(dev_state.get("completed_rows") or 0) + 1
        session.setdefault("devices", {})[payload.device_id] = dev_state
    _append_device_log(
        payload.device_id,
        f"[{now_text()}] 批量带流 | 行 {payload.sheet_row} 已写入 Excel：{status_text}",
    )
    return {"code": 0, "msg": "已写入 Excel", "status_text": status_text}


@app.post("/api/device/batch_stream/complete")
def device_batch_stream_complete(payload: BatchStreamStopRequest):
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    with _batch_stream_session_lock:
        session_id = str(_batch_stream_device_active.pop(payload.device_id, "") or "")
        if session_id:
            session = _batch_stream_sessions.get(session_id)
            if session:
                dev_state = (session.get("devices") or {}).get(payload.device_id) or {}
                dev_state["status"] = "done"
                dev_state["finished_at"] = now_text()
                session.setdefault("devices", {})[payload.device_id] = dev_state
    _append_device_log(payload.device_id, f"[{now_text()}] 批量带流已全部完成，设备已退出带流模式")
    return {"code": 0, "msg": "批量带流已结束", "session_id": session_id}


def _gallery_library_owner_dir(owner: str) -> Path:
    safe = re.sub(r"[^\w\-.@]+", "_", str(owner or "").strip()) or "unknown"
    owner_dir = (GALLERY_LIBRARY_DIR / safe).resolve()
    root = GALLERY_LIBRARY_DIR.resolve()
    if not str(owner_dir).startswith(str(root)):
        raise ValueError("invalid gallery owner path")
    owner_dir.mkdir(parents=True, exist_ok=True)
    return owner_dir


def _gallery_library_read_index(owner_dir: Path) -> list[dict[str, Any]]:
    index_path = owner_dir / GALLERY_LIBRARY_INDEX_FILENAME
    if not index_path.is_file():
        return []
    try:
        raw = json.loads(index_path.read_text(encoding="utf-8"))
    except Exception:
        return []
    return raw if isinstance(raw, list) else []


def _gallery_library_write_index(owner_dir: Path, items: list[dict[str, Any]]) -> None:
    index_path = owner_dir / GALLERY_LIBRARY_INDEX_FILENAME
    index_path.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")


def _gallery_library_item_path(owner_dir: Path, item_id: str) -> Path | None:
    safe_id = re.sub(r"[^a-zA-Z0-9_-]+", "", str(item_id or "").strip())
    if not safe_id:
        return None
    for path in owner_dir.glob(f"{safe_id}.*"):
        if path.is_file() and path.name != GALLERY_LIBRARY_INDEX_FILENAME:
            resolved = path.resolve()
            if str(resolved).startswith(str(owner_dir.resolve())):
                return resolved
    return None


def _gallery_library_random_media_payload(owner: str) -> dict[str, Any] | None:
    """从管理台图库随机取一张图片，编码为设备端 gallery_import 兼容结构。"""
    owner_key = str(owner or "").strip()
    if not owner_key:
        return None
    owner_dir = _gallery_library_owner_dir(owner_key)
    items = _gallery_library_read_index(owner_dir)
    valid = [it for it in items if isinstance(it, dict) and str(it.get("id") or "").strip()]
    if not valid:
        return None
    pick = random.choice(valid)
    item_id = str(pick.get("id") or "").strip()
    file_path = _gallery_library_item_path(owner_dir, item_id)
    if not file_path or not file_path.is_file():
        return None
    raw = file_path.read_bytes()
    if not raw:
        return None
    return {
        "id": item_id,
        "filename": str(pick.get("filename") or file_path.name),
        "content_type": str(pick.get("content_type") or "image/jpeg"),
        "content_b64": base64.b64encode(raw).decode("ascii"),
        "size": len(raw),
        "media_type": "image",
    }


def _gallery_library_guess_ext(filename: str, content_type: str) -> str:
    name = (filename or "").lower().strip()
    for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif"):
        if name.endswith(ext):
            return ext
    ctype = (content_type or "").lower().strip()
    if "png" in ctype:
        return ".png"
    if "webp" in ctype:
        return ".webp"
    if "gif" in ctype:
        return ".gif"
    if "bmp" in ctype:
        return ".bmp"
    if "heic" in ctype or "heif" in ctype:
        return ".heic"
    return ".jpg"


@app.get("/api/admin/gallery/library")
def admin_gallery_library_list(authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    try:
        owner = str(session["username"] or "").strip()
        owner_dir = _gallery_library_owner_dir(owner)
        items = _gallery_library_read_index(owner_dir)
        normalized: list[dict[str, Any]] = []
        changed = False
        for item in items:
            if not isinstance(item, dict):
                changed = True
                continue
            item_id = str(item.get("id") or "").strip()
            filename = str(item.get("filename") or "").strip() or f"{item_id}.jpg"
            file_path = _gallery_library_item_path(owner_dir, item_id)
            if not item_id or not file_path:
                changed = True
                continue
            try:
                size = int(file_path.stat().st_size)
            except OSError:
                changed = True
                continue
            normalized.append(
                {
                    "id": item_id,
                    "filename": filename,
                    "content_type": str(item.get("content_type") or "image/jpeg"),
                    "size": size,
                    "created_at": str(item.get("created_at") or ""),
                }
            )
        if changed:
            _gallery_library_write_index(owner_dir, normalized)
        return {
            "code": 0,
            "items": normalized,
            "count": len(normalized),
            "max_items": GALLERY_LIBRARY_MAX_ITEMS,
        }
    except Exception as exc:
        return {"code": 1, "msg": f"读取图库失败：{exc}"}


@app.post("/api/admin/gallery/library")
def admin_gallery_library_upload(
    files: List[UploadFile] = File(...),
    authorization: str | None = Header(default=None),
):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    if not files:
        return {"code": 1, "msg": "请至少选择 1 张图片"}
    owner = str(session["username"] or "").strip()
    owner_dir = _gallery_library_owner_dir(owner)
    items = _gallery_library_read_index(owner_dir)
    max_single_image_size = 15 * 1024 * 1024
    added: list[dict[str, Any]] = []
    for idx, f in enumerate(files):
        if len(items) >= GALLERY_LIBRARY_MAX_ITEMS:
            return {
                "code": 1,
                "msg": f"图库已满（最多 {GALLERY_LIBRARY_MAX_ITEMS} 张），本次成功上传 {len(added)} 张",
                "added": added,
                "count": len(items),
            }
        content_type = (f.content_type or "").lower().strip()
        if content_type and not content_type.startswith("image/"):
            return {"code": 1, "msg": f"第 {idx + 1} 个文件不是图片：{f.filename or '-'}"}
        raw = f.file.read()
        if not raw:
            return {"code": 1, "msg": f"第 {idx + 1} 个文件为空：{f.filename or '-'}"}
        if len(raw) > max_single_image_size:
            return {"code": 1, "msg": f"第 {idx + 1} 个文件超过 15MB：{f.filename or '-'}"}
        item_id = uuid.uuid4().hex
        ext = _gallery_library_guess_ext(f.filename or "", content_type)
        safe_name = (f.filename or f"image_{len(items) + 1}{ext}").replace("\x00", "").strip() or f"image_{len(items) + 1}{ext}"
        file_path = owner_dir / f"{item_id}{ext}"
        file_path.write_bytes(raw)
        entry = {
            "id": item_id,
            "filename": safe_name,
            "content_type": content_type or "image/jpeg",
            "created_at": now_text(),
        }
        items.append(entry)
        added.append({**entry, "size": len(raw)})
    _gallery_library_write_index(owner_dir, items)
    return {
        "code": 0,
        "msg": f"已上传 {len(added)} 张图片到图库",
        "added": added,
        "count": len(items),
        "max_items": GALLERY_LIBRARY_MAX_ITEMS,
    }


@app.delete("/api/admin/gallery/library/{item_id}")
def admin_gallery_library_delete(
    item_id: str,
    authorization: str | None = Header(default=None),
):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    owner = str(session["username"] or "").strip()
    owner_dir = _gallery_library_owner_dir(owner)
    file_path = _gallery_library_item_path(owner_dir, item_id)
    items = _gallery_library_read_index(owner_dir)
    next_items = [it for it in items if isinstance(it, dict) and str(it.get("id") or "").strip() != str(item_id or "").strip()]
    if len(next_items) == len(items) and not file_path:
        return {"code": 1, "msg": "图库中不存在该图片"}
    if file_path and file_path.is_file():
        file_path.unlink(missing_ok=True)
    _gallery_library_write_index(owner_dir, next_items)
    return {"code": 0, "msg": "已删除", "count": len(next_items)}


@app.get("/api/admin/gallery/library/{item_id}/file")
def admin_gallery_library_file(
    item_id: str,
    authorization: str | None = Header(default=None),
):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    owner = str(session["username"] or "").strip()
    owner_dir = _gallery_library_owner_dir(owner)
    file_path = _gallery_library_item_path(owner_dir, item_id)
    if not file_path or not file_path.is_file():
        return {"code": 1, "msg": "图片不存在"}
    items = _gallery_library_read_index(owner_dir)
    meta = next((it for it in items if isinstance(it, dict) and str(it.get("id") or "").strip() == str(item_id or "").strip()), {})
    media_type = str(meta.get("content_type") or "image/jpeg")
    filename = str(meta.get("filename") or file_path.name)
    return FileResponse(path=str(file_path), media_type=media_type, filename=filename)


def _failure_reports_index_path() -> Path:
    return FAILURE_REPORTS_DIR / FAILURE_REPORTS_INDEX_FILENAME


def _failure_reports_read_index() -> list[dict[str, Any]]:
    path = _failure_reports_index_path()
    if not path.is_file():
        return []
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return []
    if not isinstance(raw, list):
        return []
    return [it for it in raw if isinstance(it, dict)]


def _failure_reports_write_index(items: list[dict[str, Any]]) -> None:
    path = _failure_reports_index_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")


def _failure_reports_dedup_key(owner: str, alias: str, business: str, error_type: str) -> str:
    parts = [
        str(owner or "").strip().lower(),
        _normalize_alias_key(alias),
        str(business or "").strip().lower(),
        str(error_type or "").strip().lower(),
    ]
    return "|".join(parts)


def _failure_reports_zip_path(report_id: str) -> Path | None:
    rid = str(report_id or "").strip()
    if not rid or not re.fullmatch(r"[0-9a-f]{32}", rid):
        return None
    return FAILURE_REPORTS_DIR / f"{rid}.zip"


def _failure_reports_find_duplicate(
    items: list[dict[str, Any]],
    dedup_key: str,
) -> dict[str, Any] | None:
    key = str(dedup_key or "").strip()
    if not key:
        return None
    for item in items:
        if str(item.get("dedup_key") or "").strip() == key:
            return item
    return None


def _failure_reports_delete_zip(report_id: str) -> None:
    zip_path = _failure_reports_zip_path(str(report_id or "").strip())
    if zip_path and zip_path.is_file():
        zip_path.unlink(missing_ok=True)


def _failure_reports_evict_oldest(items: list[dict[str, Any]], slots: int = 1) -> list[dict[str, Any]]:
    """队列满时丢弃最旧记录，为新上传腾出空间（管理台仍可见最新 FAILURE_REPORTS_MAX_ITEMS 条）。"""
    need = max(1, int(slots or 1))
    out = list(items)
    while len(out) + need > FAILURE_REPORTS_MAX_ITEMS and out:
        out.sort(key=lambda x: str(x.get("created_at") or ""))
        evicted = out.pop(0)
        _failure_reports_delete_zip(str(evicted.get("id") or ""))
    return out


def _failure_reports_visible_items(_session: dict) -> list[dict[str, Any]]:
    return _failure_reports_read_index()


@app.post("/api/failure_reports/upload")
async def device_failure_reports_upload(
    device_id: str = Form(...),
    alias: str = Form(...),
    business: str = Form(...),
    error_type: str = Form(...),
    summary: str = Form(default=""),
    ocr_related: str = Form(default="false"),
    file: UploadFile = File(...),
):
    """设备端上报失败采集压缩包（日志 + 可选 OCR 截图）。"""
    did = str(device_id or "").strip()
    al = str(alias or "").strip()
    biz = str(business or "").strip()
    err_type = str(error_type or "").strip()
    if not did or not al or not biz or not err_type:
        return {"code": 1, "msg": "device_id、alias、business、error_type 不能为空"}
    auth_err, device = auth_or_error(did, al)
    if auth_err:
        return auth_err
    owner = str(device.owner_username or "").strip()
    if not owner:
        return {"code": 1, "msg": "设备未关联用户，无法上报失败采集"}

    raw = await file.read()
    if not raw:
        return {"code": 1, "msg": "压缩包为空"}
    max_zip_size = 25 * 1024 * 1024
    if len(raw) > max_zip_size:
        return {"code": 1, "msg": "压缩包超过 25MB 上限"}

    dedup_key = _failure_reports_dedup_key(owner, al, biz, err_type)
    report_id = uuid.uuid4().hex
    zip_path = _failure_reports_zip_path(report_id)
    if not zip_path:
        return {"code": 1, "msg": "生成报告 ID 失败"}

    with _failure_reports_lock:
        items = _failure_reports_read_index()
        if _failure_reports_find_duplicate(items, dedup_key):
            return {"code": 2, "msg": "相同业务相同失败类型已上报，跳过", "dedup_key": dedup_key}
        if len(items) >= FAILURE_REPORTS_MAX_ITEMS:
            items = _failure_reports_evict_oldest(items, slots=1)
        zip_path.write_bytes(raw)
        entry = {
            "id": report_id,
            "owner_username": owner,
            "device_id": did,
            "alias": al,
            "business": biz,
            "error_type": err_type,
            "summary": str(summary or "").strip()[:2000],
            "ocr_related": str(ocr_related or "").strip().lower() in ("1", "true", "yes", "on"),
            "dedup_key": dedup_key,
            "filename": (file.filename or f"{report_id}.zip").replace("\x00", "").strip() or f"{report_id}.zip",
            "size": len(raw),
            "created_at": now_text(),
        }
        items.append(entry)
        _failure_reports_write_index(items)

    _append_device_log(did, f"[失败采集] 已上传 business={biz} error_type={err_type}")
    return {
        "code": 0,
        "msg": "失败采集已上传",
        "report_id": report_id,
        "count": len(items),
        "max_items": FAILURE_REPORTS_MAX_ITEMS,
    }


@app.get("/api/admin/failure_reports")
def admin_failure_reports_list(
    owner_username: str = "",
    alias: str = "",
    error_type: str = "",
    authorization: str | None = Header(default=None),
):
    err, session = _require_admin({"authorization": authorization} if authorization else {})
    if err:
        return err
    items = _failure_reports_visible_items(session)
    owner_filter = str(owner_username or "").strip()
    alias_filter = _normalize_alias_key(alias)
    err_filter = str(error_type or "").strip().lower()

    owners = sorted({str(it.get("owner_username") or "").strip() for it in items if str(it.get("owner_username") or "").strip()})
    aliases = sorted(
        {str(it.get("alias") or "").strip() for it in items if str(it.get("alias") or "").strip()},
        key=lambda x: _normalize_alias_key(x),
    )
    error_types = sorted(
        {str(it.get("error_type") or "").strip() for it in items if str(it.get("error_type") or "").strip()},
    )

    if owner_filter:
        items = [it for it in items if str(it.get("owner_username") or "").strip() == owner_filter]
    if alias_filter:
        items = [
            it for it in items if _normalize_alias_key(str(it.get("alias") or "")) == alias_filter
        ]
    if err_filter:
        items = [it for it in items if str(it.get("error_type") or "").strip().lower() == err_filter]
    items.sort(key=lambda x: str(x.get("created_at") or ""), reverse=True)

    grouped: dict[str, dict[str, dict[str, list[dict[str, Any]]]]] = {}
    for it in items:
        o = str(it.get("owner_username") or "").strip() or "-"
        a = str(it.get("alias") or "").strip() or "-"
        e = str(it.get("error_type") or "").strip() or "-"
        grouped.setdefault(o, {}).setdefault(a, {}).setdefault(e, []).append(it)

    all_items = _failure_reports_read_index()
    return {
        "code": 0,
        "items": items,
        "grouped": grouped,
        "count": len(items),
        "total_count": len(all_items),
        "max_items": FAILURE_REPORTS_MAX_ITEMS,
        "owners": owners,
        "aliases": aliases,
        "error_types": error_types,
    }


@app.delete("/api/admin/failure_reports/{report_id}")
def admin_failure_reports_delete(
    report_id: str,
    authorization: str | None = Header(default=None),
):
    err, _session = _require_admin({"authorization": authorization} if authorization else {})
    if err:
        return err
    rid = str(report_id or "").strip()
    zip_path = _failure_reports_zip_path(rid)
    with _failure_reports_lock:
        items = _failure_reports_read_index()
        target = next((it for it in items if str(it.get("id") or "").strip() == rid), None)
        if not target:
            return {"code": 1, "msg": "失败采集记录不存在"}
        next_items = [it for it in items if str(it.get("id") or "").strip() != rid]
        _failure_reports_write_index(next_items)
    if zip_path and zip_path.is_file():
        zip_path.unlink(missing_ok=True)
    return {"code": 0, "msg": "已删除", "count": len(next_items)}


@app.get("/api/admin/failure_reports/{report_id}/download")
def admin_failure_reports_download(
    report_id: str,
    authorization: str | None = Header(default=None),
):
    err, _session = _require_admin({"authorization": authorization} if authorization else {})
    if err:
        return err
    rid = str(report_id or "").strip()
    items = _failure_reports_read_index()
    target = next((it for it in items if str(it.get("id") or "").strip() == rid), None)
    if not target:
        return {"code": 1, "msg": "失败采集记录不存在"}
    zip_path = _failure_reports_zip_path(rid)
    if not zip_path or not zip_path.is_file():
        return {"code": 1, "msg": "压缩包文件不存在"}
    filename = str(target.get("filename") or f"{rid}.zip")
    return FileResponse(path=str(zip_path), media_type="application/zip", filename=filename)


@app.post("/api/admin/device_gallery/upload")
def admin_device_gallery_upload(
    device_id: str = Form(...),
    alias: str = Form(...),
    files: List[UploadFile] = File(...),
    authorization: str | None = Header(default=None),
):
    """上传素材并下发给设备端写入系统图库（支持图片/视频）。"""
    did = (device_id or "").strip()
    al = (alias or "").strip()
    if not did or not al:
        return {"code": 1, "msg": "device_id 与 alias 不能为空"}
    err, _device = _require_login_and_owning_device(did, al, authorization)
    if err:
        return err
    if not files:
        return {"code": 1, "msg": "请至少上传 1 个素材文件"}
    if len(files) > GALLERY_LIBRARY_MAX_ITEMS:
        return {"code": 1, "msg": f"单次最多上传 {GALLERY_LIBRARY_MAX_ITEMS} 张图片"}

    media_items: list[dict[str, Any]] = []
    max_single_image_size = 15 * 1024 * 1024
    max_single_video_size = 80 * 1024 * 1024
    total_size = 0
    total_limit = 200 * 1024 * 1024
    for idx, f in enumerate(files):
        content_type = (f.content_type or "").lower().strip()
        is_image = content_type.startswith("image/")
        is_video = content_type.startswith("video/")
        if content_type and not (is_image or is_video):
            return {"code": 1, "msg": f"第 {idx + 1} 个文件不是图片/视频：{f.filename or '-'}"}
        raw = f.file.read()
        if not raw:
            return {"code": 1, "msg": f"第 {idx + 1} 个文件为空：{f.filename or '-'}"}
        max_single_size = max_single_video_size if is_video else max_single_image_size
        if len(raw) > max_single_size:
            limit_mb = max_single_size // (1024 * 1024)
            return {"code": 1, "msg": f"第 {idx + 1} 个文件超过 {limit_mb}MB：{f.filename or '-'}"}
        total_size += len(raw)
        if total_size > total_limit:
            return {"code": 1, "msg": "本次上传总大小超过 200MB"}
        safe_name = (f.filename or f"image_{idx + 1}.jpg").replace("\x00", "").strip() or f"image_{idx + 1}.jpg"
        media_items.append(
            {
                "filename": safe_name,
                "content_type": content_type or ("video/mp4" if is_video else "image/jpeg"),
                "content_b64": base64.b64encode(raw).decode("ascii"),
                "size": len(raw),
                "media_type": "video" if is_video else "image",
            }
        )

    op_id = _enqueue_tiktok_device_op(
        did,
        "gallery_import_media",
        {
            "device_id": did,
            "alias": al,
            "media_items": media_items,
        },
    )
    return {
        "code": 0,
        "msg": f"已下发图库导入任务（{len(media_items)} 个素材）",
        "op_id": op_id,
        "count": len(media_items),
    }


def _insert_mobile_pending_usernames(usernames: List[str], skip_privacy_filter: bool = False):
    cleaned = list(
        dict.fromkeys([u.strip().lstrip("@") for u in usernames if u and u.strip()])
    )
    if not cleaned:
        return {"code": 1, "msg": "未提供有效用户名"}

    if skip_privacy_filter:
        allowed, skipped = cleaned, []
    else:
        allowed, skipped = _filter_non_private_usernames(cleaned)
        if not allowed:
            return {"code": 1, "msg": "全部为私密账号，未入库", "skipped_private": skipped, "count": 0}

    print(
        f"[{now_text()}] mobile_pending.insert | enter | n_allowed={len(allowed)} "
        f"skip_privacy={skip_privacy_filter}"
    )
    skipped_already_pending = 0
    db_started = time.perf_counter()
    conn = _get_db()
    try:
        # 分批短事务提交，避免一次大事务长时间占用写锁。
        # 表上历史上无 username 唯一约束：第二次勾选同一批根用户会重复插入，队列爆炸、写库与 NOT IN 预查都会越来越慢。
        # 每批先查已是 pending 的用户名，只插入新行；并依赖 idx_mobile_pending_username_pending 加速。
        now = now_text()
        chunk_size = 300
        inserted = 0
        failed = 0
        failed_batches = 0
        first_error = ""
        for start in range(0, len(allowed), chunk_size):
            chunk = allowed[start:start + chunk_size]
            if not chunk:
                continue
            try:
                uniq_chunk = list(dict.fromkeys(chunk))
                ph = ",".join(["?"] * len(uniq_chunk))
                already = conn.execute(
                    f"SELECT username FROM mobile_pending_usernames WHERE consume_status = 'pending' AND username IN ({ph})",
                    uniq_chunk,
                ).fetchall()
                have = {str(r[0]) for r in already}
                to_add = [u for u in uniq_chunk if u not in have]
                skipped_already_pending += len(uniq_chunk) - len(to_add)
                if not to_add:
                    continue
                conn.executemany(
                    "INSERT INTO mobile_pending_usernames (username, created_at) VALUES (?, ?)",
                    [(username, now) for username in to_add],
                )
                conn.commit()
                inserted += len(to_add)
            except Exception as exc:
                conn.rollback()
                failed += len(chunk)
                failed_batches += 1
                if not first_error:
                    first_error = str(exc)
    finally:
        conn.close()

    db_ms = (time.perf_counter() - db_started) * 1000
    priv_note = "" if skip_privacy_filter else f" skipped_private={len(skipped)}"
    print(
        f"[{now_text()}] mobile_pending.insert | done | inserted={inserted} "
        f"skip_pending={skipped_already_pending} db_ms={db_ms:.1f}{priv_note}"
    )
    if inserted <= 0:
        if not first_error and skipped_already_pending > 0:
            return {
                "code": 0,
                "msg": f"无新用户写入（{skipped_already_pending} 个已在待下发队列中）",
                "count": len(allowed),
                "inserted": 0,
                "failed": 0,
                "failed_batches": 0,
                "skipped_private": skipped,
                "skipped_already_pending": skipped_already_pending,
            }
        msg = "用户名导入失败"
        if first_error:
            msg = f"{msg}（{first_error}）"
        return {
            "code": 1,
            "msg": msg,
            "count": len(allowed),
            "inserted": 0,
            "failed": failed or len(allowed),
            "failed_batches": failed_batches or 1,
            "skipped_private": skipped,
            "skipped_already_pending": skipped_already_pending,
        }
    msg = "用户名导入成功"
    if skipped_already_pending > 0:
        msg = f"{msg}（{skipped_already_pending} 条已在队列中已跳过）"
    if failed > 0:
        msg = f"用户名部分导入成功（成功 {inserted}，失败 {failed}）"
        if first_error:
            msg = f"{msg}，首个错误：{first_error}"
    _invalidate_overview_queue_stats_cache()
    return {
        "code": 0,
        "msg": msg,
        "count": len(allowed),
        "inserted": inserted,
        "failed": failed,
        "failed_batches": failed_batches,
        "skipped_private": skipped,
        "skipped_already_pending": skipped_already_pending,
    }


def _export_mobile_pending_usernames_xlsx(clear_after_export: bool = False):
    t0 = time.perf_counter()
    print(f"[{now_text()}] mobile_pending.export | enter | clear_after_export={clear_after_export}")
    if Workbook is None:
        print(f"[{now_text()}] mobile_pending.export | missing_openpyxl")
        return {"code": 1, "msg": "缺少 openpyxl 依赖，无法导出 xlsx"}

    # 与下载接口保持同一目录，避免因启动 cwd 不同导致导出后 404。
    export_dir = (BASE_DIR / "exports").resolve()
    export_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    # 大批次减少「读库轮次 + 文件个数」；write_only 降低内存与写盘耗时。非首次全量导出时避免 fetchall 整表。
    batch_size = 8000
    exported_files: List[str] = []
    total_exported = 0
    batch_index = 0
    last_id = 0

    read_conn = _get_db()
    try:
        t_cnt0 = time.perf_counter()
        cnt = read_conn.execute("SELECT COUNT(1) FROM mobile_pending_usernames").fetchone()
        approx_total = int(cnt[0] if cnt else 0)
        print(
            f"[{now_text()}] mobile_pending.export | count_done | "
            f"{(time.perf_counter() - t_cnt0) * 1000:.1f}ms approx_rows={approx_total}"
        )
        if approx_total <= 0:
            print(f"[{now_text()}] mobile_pending.export | empty")
            return {"code": 1, "msg": "mobile_pending_usernames 为空，无需导出"}

        t_stream0 = time.perf_counter()
        print(f"[{now_text()}] mobile_pending.export | stream_start | batch_size={batch_size}")
        while True:
            rows = read_conn.execute(
                """
                SELECT id, username, created_at, consume_status, consumed_at
                FROM mobile_pending_usernames
                WHERE id > ?
                ORDER BY id ASC
                LIMIT ?
                """,
                (last_id, batch_size),
            ).fetchall()
            if not rows:
                break
            batch_index += 1
            n = len(rows)
            total_exported += n
            # 多文件临时名；若最终仅一批则改为单文件名，与旧行为一致。
            filename = f"mobile_pending_usernames_{timestamp}_part{batch_index:04d}.xlsx"
            file_path = export_dir / filename

            t_write0 = time.perf_counter()
            print(
                f"[{now_text()}] mobile_pending.export | xlsx_write_start | "
                f"part={batch_index} rows={n} file={filename}"
            )
            wb = Workbook(write_only=True)
            ws = wb.create_sheet(title="mobile_pending_usernames")
            ws.append(["id", "username", "created_at", "consume_status", "consumed_at"])
            for row in rows:
                ws.append(
                    [
                        row["id"],
                        row["username"],
                        row["created_at"],
                        row["consume_status"],
                        row["consumed_at"],
                    ]
                )
            wb.save(file_path)
            exported_files.append(str(file_path))
            last_id = int(rows[-1]["id"])
            print(
                f"[{now_text()}] mobile_pending.export | xlsx_write_done | part={batch_index} "
                f"{(time.perf_counter() - t_write0) * 1000:.1f}ms cumulative={total_exported}"
            )
        print(
            f"[{now_text()}] mobile_pending.export | stream_done | "
            f"{(time.perf_counter() - t_stream0) * 1000:.1f}ms parts={batch_index} rows={total_exported}"
        )
        if batch_index == 0:
            print(f"[{now_text()}] mobile_pending.export | empty_after_count_race")
            return {"code": 1, "msg": "mobile_pending_usernames 为空（可能在统计后已被清空），无需导出"}
    except Exception as e:
        print(f"[{now_text()}] mobile_pending.export | stream_or_write_failed | err={e}")
        return {
            "code": 1,
            "msg": f"导出失败（流式导出中断），未清空队列（{e}）",
            "count": total_exported,
            "batch_size": batch_size,
            "batch_count": len(exported_files),
            "exported_count": len(exported_files),
            "file_paths": exported_files,
        }
    finally:
        read_conn.close()

    if batch_index == 1 and exported_files:
        sole = Path(exported_files[0])
        short_name = f"mobile_pending_usernames_{timestamp}.xlsx"
        short_path = sole.parent / short_name
        try:
            if short_path.resolve() != sole.resolve():
                if short_path.is_file():
                    short_path.unlink()
                sole.rename(short_path)
            exported_files = [str(short_path)]
        except Exception as exc:
            print(f"[{now_text()}] mobile_pending.export | single_file_rename_failed | err={exc} keep_part_name")

    batch_count = len(exported_files)
    total_count = total_exported

    # 二次校验：只有当所有导出文件都真实落盘且大小>0，才允许清空数据库。
    files_verified = True
    verify_errors: List[str] = []
    t_verify0 = time.perf_counter()
    print(f"[{now_text()}] mobile_pending.export | verify_start | files={len(exported_files)}")
    for path_str in exported_files:
        try:
            p = Path(path_str).resolve()
            if not p.exists() or not p.is_file():
                files_verified = False
                verify_errors.append(f"文件不存在: {p.name}")
                continue
            if int(p.stat().st_size or 0) <= 0:
                files_verified = False
                verify_errors.append(f"文件大小为0: {p.name}")
        except Exception as e:
            files_verified = False
            verify_errors.append(f"校验失败: {path_str} ({e})")
    print(f"[{now_text()}] mobile_pending.export | verify_done | {(time.perf_counter() - t_verify0) * 1000:.1f}ms ok={files_verified} errors={len(verify_errors)}")

    cleared = 0
    if clear_after_export and exported_files and files_verified:
        # 分批删除并逐批提交，避免一次性清空导致长写锁。
        delete_conn = _get_db()
        try:
            delete_batch_size = 4000
            t_delete0 = time.perf_counter()
            print(f"[{now_text()}] mobile_pending.export | delete_start")
            while True:
                ids = delete_conn.execute(
                    "SELECT id FROM mobile_pending_usernames ORDER BY id ASC LIMIT ?",
                    (delete_batch_size,),
                ).fetchall()
                if not ids:
                    break
                delete_conn.executemany(
                    "DELETE FROM mobile_pending_usernames WHERE id = ?",
                    [(int(r["id"]),) for r in ids],
                )
                delete_conn.commit()
                cleared += len(ids)
                print(f"[{now_text()}] mobile_pending.export | delete_batch_committed | batch_size={len(ids)} cleared={cleared}")
            print(f"[{now_text()}] mobile_pending.export | delete_done | {(time.perf_counter() - t_delete0) * 1000:.1f}ms cleared={cleared}")
        finally:
            delete_conn.close()
        # 表已分批删空，不再维护内存缓存镜像。
    elif clear_after_export and not files_verified:
        print(f"[{now_text()}] mobile_pending.export | skip_clear_due_verify_failed")
        return {
            "code": 1,
            "msg": "导出文件校验未通过，已保留数据库原始数据（未清空）",
            "count": total_count,
            "cleared": 0,
            "batch_size": batch_size,
            "batch_count": batch_count,
            "file_paths": exported_files,
            "filenames": [Path(path).name for path in exported_files],
            "verify_errors": verify_errors[:10],
        }
    elapsed_ms = (time.perf_counter() - t0) * 1000
    print(f"[{now_text()}] mobile_pending.export | done | {elapsed_ms:.1f}ms cleared={cleared} verified={files_verified}")
    return {
        "code": 0,
        "msg": "导出成功",
        "count": total_count,
        "cleared": cleared,
        "batch_size": batch_size,
        "batch_count": batch_count,
        "file_paths": exported_files,
        "filenames": [Path(path).name for path in exported_files],
        "file_path": exported_files[0],
        "filename": Path(exported_files[0]).name,
        "files_verified": files_verified,
    }


def _mobile_pending_export_worker(job_id: str, clear_after_export: bool) -> None:
    print(f"[{now_text()}] mobile_pending.export_async | worker_start job_id={job_id} clear={clear_after_export}")
    try:
        with _mobile_pending_export_job_lock:
            rec = _mobile_pending_export_jobs.get(job_id)
            if rec:
                rec["status"] = "running"
        result = _export_mobile_pending_usernames_xlsx(clear_after_export=clear_after_export)
        with _mobile_pending_export_job_lock:
            rec = _mobile_pending_export_jobs.get(job_id)
            if rec:
                rec["finished_ts"] = time.time()
                ok = int(result.get("code") or 0) == 0
                rec["status"] = "done" if ok else "failed"
                rec["result"] = result
                rec["error"] = "" if ok else str(result.get("msg") or "导出失败")
    except Exception as exc:
        with _mobile_pending_export_job_lock:
            rec = _mobile_pending_export_jobs.get(job_id)
            if rec:
                rec["finished_ts"] = time.time()
                rec["status"] = "failed"
                rec["error"] = str(exc)
                rec["result"] = {"code": 1, "msg": str(exc)}
        print(f"[{now_text()}] mobile_pending.export_async | worker_exc job_id={job_id} err={exc}")
    finally:
        print(f"[{now_text()}] mobile_pending.export_async | worker_finish job_id={job_id}")


@app.post("/api/admin/add_usernames")
def add_usernames(payload: AddUsernamesRequest):
    """
    直接把用户名丢进 mobile_pending_usernames（手机端待拉取队列）。
    私密账号会被过滤，不入库。
    """
    return _insert_mobile_pending_usernames(payload.usernames)


@app.post("/api/admin/mobile_pending_usernames/add")
def add_mobile_pending_usernames(payload: AddUsernamesRequest):
    """将指定用户名直接写入 mobile_pending_usernames。skip_privacy_filter=true 时不做私密判定（用于 tiktok_roots 勾选下发）。"""
    return _insert_mobile_pending_usernames(
        payload.usernames,
        skip_privacy_filter=bool(payload.skip_privacy_filter),
    )


@app.post("/api/admin/mobile_pending_usernames/add_from_roots_selection")
def add_mobile_pending_from_roots_selection(payload: AddUsernamesRequest):
    """后台「将选中根用户加入待下发队列」专用入口：不调用 fetch_tiktok_profile_stats / 私密判定，直接写入 mobile_pending_usernames。"""
    return _insert_mobile_pending_usernames(payload.usernames, skip_privacy_filter=True)


@app.post("/api/admin/mobile_pending_usernames/add_latest_roots")
def add_mobile_pending_from_latest_roots(payload: AddLatestRootsToPendingRequest):
    """后台「将最新 N 条 tiktok_roots 加入待下发队列」：按 discovered_at 倒序取最新 N 条，不做私密判定，直接写入 mobile_pending_usernames。"""
    safe_count = max(1, min(int(payload.count or 0), 5000))
    conn = _get_db()
    try:
        rows = conn.execute(
            "SELECT root_username FROM tiktok_roots ORDER BY discovered_at DESC, root_username ASC LIMIT ?",
            (safe_count,),
        ).fetchall()
        usernames = [str(r["root_username"] or "").strip() for r in rows]
    finally:
        conn.close()
    usernames = [u for u in usernames if u]
    result = _insert_mobile_pending_usernames(usernames, skip_privacy_filter=True)
    result["requested"] = safe_count
    result["selected"] = len(usernames)
    return result


@app.post("/api/admin/mobile_pending_usernames/add_all_roots")
def add_mobile_pending_from_all_roots():
    """后台「将全部 tiktok_roots 加入待下发队列」：服务端直接读库分批写入，不做私密判定。"""
    return _refill_mobile_pending_from_all_roots()


@app.get("/api/admin/mobile_pending_usernames/export")
def export_mobile_pending_usernames(clear_after_export: bool = Query(default=False)):
    """导出 mobile_pending_usernames 到 xlsx；可选导出后清空。"""
    return _export_mobile_pending_usernames_xlsx(clear_after_export=clear_after_export)


@app.get("/api/admin/mobile_pending_usernames/export_file")
def download_mobile_pending_export_file(filename: str = Query(..., min_length=1)):
    """下载已导出的 mobile_pending_usernames xlsx 文件。"""
    safe_name = Path(str(filename)).name
    if safe_name != filename:
        raise HTTPException(status_code=400, detail="非法文件名")
    export_dir = (BASE_DIR / "exports").resolve()
    file_path = (export_dir / safe_name).resolve()
    if export_dir not in file_path.parents:
        raise HTTPException(status_code=400, detail="非法文件路径")
    if not file_path.exists() or not file_path.is_file():
        raise HTTPException(status_code=404, detail="导出文件不存在")
    return FileResponse(
        path=str(file_path),
        filename=safe_name,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )


@app.post("/api/admin/mobile_pending_usernames/export_start")
def export_mobile_pending_start(
    payload: MobilePendingExportStartRequest,
    authorization: str | None = Header(default=None),
):
    """提交后台导出（可选导出后清空）；立即返回 job_id，前端轮询 export_status。"""
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    job_id = str(uuid.uuid4())
    clear_flag = bool(payload.clear_after_export)
    with _mobile_pending_export_job_lock:
        _purge_old_mobile_pending_export_jobs_unlocked()
        for rec in _mobile_pending_export_jobs.values():
            if rec.get("status") in ("queued", "running"):
                return JSONResponse(
                    status_code=409,
                    content={"code": 409, "msg": "已有导出任务进行中，请稍后再试"},
                )
        _mobile_pending_export_jobs[job_id] = {
            "status": "queued",
            "created_ts": time.time(),
            "clear_after_export": clear_flag,
        }
    thread = Thread(
        target=_mobile_pending_export_worker,
        args=(job_id, clear_flag),
        daemon=True,
        name=f"mobexport-{job_id[:8]}",
    )
    thread.start()
    print(f"[{now_text()}] mobile_pending.export_async | job_queued job_id={job_id} clear={clear_flag}")
    return {"code": 0, "msg": "已提交后台导出任务", "job_id": job_id}


@app.get("/api/admin/mobile_pending_usernames/export_status")
def export_mobile_pending_status(
    job_id: str = Query(..., min_length=8, max_length=64),
    authorization: str | None = Header(default=None),
):
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    jid = str(job_id or "").strip()
    with _mobile_pending_export_job_lock:
        rec = _mobile_pending_export_jobs.get(jid)
    if not rec:
        return {"code": 1, "msg": "任务不存在或已过期", "status": "gone"}
    status = str(rec.get("status") or "")
    payload: Dict[str, Any] = {
        "code": 0,
        "job_id": jid,
        "status": status,
        "clear_after_export": bool(rec.get("clear_after_export")),
    }
    if status in ("done", "failed"):
        payload["result"] = rec.get("result")
        payload["error"] = str(rec.get("error") or "")
    return payload


@app.post("/api/admin/tiktok_profile_stats")
def tiktok_profile_stats(payload: TikTokProfileStatsRequest):
    return fetch_tiktok_profile_stats(
        payload.username,
        include_raw=payload.include_raw,
        write_back=payload.write_back,
        query_index=payload.query_index,
        query_total=payload.query_total,
    )


## 已彻底砍掉：任务中心、队列监控、候选池、导出接口


@app.get("/api/admin/tiktok_roots")
def list_tiktok_roots(page: int = 1, page_size: int = 100):
    safe_page = max(1, int(page))
    safe_size = max(1, min(int(page_size), 500))
    offset = (safe_page - 1) * safe_size
    conn = _get_db()
    try:
        total = conn.execute("SELECT COUNT(1) FROM tiktok_roots").fetchone()[0]
        rows = conn.execute(
            "SELECT root_username, roots_type, discovered_at, last_use_follower_count, usage_count FROM tiktok_roots ORDER BY discovered_at DESC, root_username ASC LIMIT ? OFFSET ?",
            (safe_size, offset),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows], "page": safe_page, "page_size": safe_size, "total": total}
    finally:
        conn.close()


@app.post("/api/admin/tiktok_roots/reset_usage")
def reset_tiktok_roots_usage():
    conn = _get_db()
    try:
        cur = conn.execute("UPDATE tiktok_roots SET usage_count = 0")
        conn.commit()
        return {"code": 0, "msg": "usage_count 已重置", "updated": int(cur.rowcount or 0)}
    finally:
        conn.close()


@app.post("/api/admin/tiktok_roots")
def add_tiktok_roots(payload: AddTikTokRootsRequest):
    cleaned = [u.strip().lstrip("@") for u in payload.usernames if u and u.strip()]
    if not cleaned:
        return {"code": 1, "msg": "未提供有效根用户"}

    allowed, skipped = _filter_non_private_usernames(cleaned)
    if not allowed:
        return {"code": 1, "msg": "全部为私密账号，未入库", "skipped_private": skipped, "inserted": 0, "total_rows": len(cleaned)}

    roots_type = (payload.roots_type or "").strip()
    now = now_text()

    conn = _get_db()
    try:
        inserted = 0
        for username in allowed:
            cur = conn.execute(
                "INSERT OR IGNORE INTO tiktok_roots (root_username, roots_type, discovered_at, last_use_follower_count, usage_count) VALUES (?, ?, ?, NULL, 0)",
                (username, roots_type, now),
            )
            if cur.rowcount > 0:
                inserted += 1
        conn.commit()
    finally:
        conn.close()

    return {
        "code": 0,
        "msg": "根用户已添加到 tiktok_roots",
        "inserted": inserted,
        "total_rows": len(cleaned),
        "skipped_private": skipped,
    }


@app.post("/api/admin/tiktok_roots/import")
def import_tiktok_roots(file: UploadFile = File(...), dedup_only: int = Form(0)):
    filename = (file.filename or "").lower()
    items: List[tuple[str, str]] = []

    if filename.endswith(".txt"):
        content = file.file.read().decode("utf-8", errors="ignore")
        for line in content.splitlines():
            val = (line or "").strip()
            if not val:
                continue
            parts = [p.strip() for p in val.split(",", 1)]
            username = parts[0].lstrip("@")
            roots_type = parts[1] if len(parts) > 1 else ""
            if username:
                items.append((username, roots_type))
    elif filename.endswith(".xlsx"):
        if load_workbook is None:
            return {"code": 1, "msg": "缺少 openpyxl 依赖，无法导入 xlsx"}
        workbook = load_workbook(file.file)
        sheet = workbook[workbook.sheetnames[0]]
        for row in sheet.iter_rows(values_only=True):
            if not row:
                continue
            raw_username = "" if row[0] is None else str(row[0]).strip()
            raw_type = "" if len(row) < 2 or row[1] is None else str(row[1]).strip()
            if not raw_username:
                continue
            if raw_username.lower() in {"username", "root_username", "根用户"}:
                continue
            items.append((raw_username.lstrip("@"), raw_type))
    else:
        return {"code": 1, "msg": "仅支持 txt 或 xlsx 文件"}

    dedup: List[tuple[str, str]] = []
    seen = set()
    for u, t in items:
        key = u.lower().strip()
        if not key or key in seen:
            continue
        seen.add(key)
        dedup.append((u.strip(), t.strip()))

    if not dedup:
        return {"code": 1, "msg": "文件中没有有效根用户"}
    only_dedup = int(dedup_only or 0) == 1
    skipped: List[Dict[str, str]] = []
    filtered_dedup = dedup
    if not only_dedup:
        raw_usernames = [u for u, _ in dedup]
        allowed_names, skipped = _filter_non_private_usernames(raw_usernames)
        allowed_set = {x.lower() for x in allowed_names}
        filtered_dedup = [(u, t) for (u, t) in dedup if u.lower() in allowed_set]

        if not filtered_dedup:
            return {
                "code": 1,
                "msg": "导入数据均为私密账号，未入库",
                "skipped_private": skipped,
                "inserted": 0,
                "total_rows": len(dedup),
                "dedup_only": only_dedup,
            }

    now = now_text()
    conn = _get_db()
    try:
        inserted = 0
        queued = 0
        for username, roots_type in filtered_dedup:
            cur = conn.execute(
                "INSERT OR IGNORE INTO tiktok_roots (root_username, roots_type, discovered_at, last_use_follower_count, usage_count) VALUES (?, ?, ?, NULL, 0)",
                (username, roots_type, now),
            )
            if cur.rowcount > 0:
                inserted += 1

            if not only_dedup:
                queue_cur = conn.execute(
                    "INSERT OR IGNORE INTO mobile_pending_usernames (username, status, created_at, pulled_at, pulled_by, failed_at, fail_reason) VALUES (?, 'pending', ?, NULL, NULL, NULL, NULL)",
                    (username, now),
                )
                if queue_cur.rowcount > 0:
                    queued += 1
        conn.commit()
    finally:
        conn.close()

    return {
        "code": 0,
        "msg": "根用户导入完成（仅判重入库）" if only_dedup else "根用户导入完成（已同步加入待下发队列）",
        "inserted": inserted,
        "queued": queued,
        "total_rows": len(dedup),
        "skipped_private": skipped,
        "dedup_only": only_dedup,
    }


@app.get("/api/admin/live_customers")
def list_live_customers(limit: int = 500):
    conn = _get_db()
    try:
        rows = conn.execute(
            "SELECT username, usage_count, created_at, last_visit_at FROM live_customer ORDER BY created_at DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows]}
    finally:
        conn.close()


@app.get("/api/admin/follower_queue")
def list_follower_queue(limit: int = 500):
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT username, root_username, created_at, consume_status, consumed_at, consumed_by, consumed_mode, following_access_status, following_fetch_success, mobile_push_status
            FROM follower_queue
            ORDER BY created_at DESC, username ASC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows]}
    finally:
        conn.close()


@app.post("/api/admin/live_customers/import")
def import_live_customers(file: UploadFile = File(...)):
    filename = (file.filename or "").lower()
    usernames: List[str] = []

    if filename.endswith(".txt"):
        content = file.file.read().decode("utf-8", errors="ignore")
        usernames = [line.strip().lstrip("@") for line in content.splitlines() if line.strip()]
    elif filename.endswith(".xlsx"):
        if load_workbook is None:
            return {"code": 1, "msg": "缺少 openpyxl 依赖，无法导入 xlsx"}
        workbook = load_workbook(file.file)
        sheet = workbook[workbook.sheetnames[0]]
        for row in sheet.iter_rows(values_only=True):
            for cell in row:
                if cell is None:
                    continue
                val = str(cell).strip().lstrip("@")
                if val and val.lower() not in {"username", "用户名"}:
                    usernames.append(val)
                break
    else:
        return {"code": 1, "msg": "仅支持 txt 或 xlsx 文件"}

    cleaned = []
    seen = set()
    for u in usernames:
        key = u.lower().strip()
        if key and key not in seen:
            seen.add(key)
            cleaned.append(u)

    if not cleaned:
        return {"code": 1, "msg": "文件中没有有效用户名"}

    allowed, skipped = _filter_non_private_usernames(cleaned)
    if not allowed:
        return {"code": 1, "msg": "导入数据均为私密账号，未入库", "skipped_private": skipped, "inserted": 0, "total_rows": len(cleaned)}

    now = now_text()
    conn = _get_db()
    try:
        inserted = 0
        for username in allowed:
            cur = conn.execute(
                "INSERT OR IGNORE INTO live_customer (username, usage_count, created_at) VALUES (?, 0, ?)",
                (username, now),
            )
            if cur.rowcount > 0:
                inserted += 1
        conn.commit()
    finally:
        conn.close()

    return {
        "code": 0,
        "inserted": inserted,
        "total_rows": len(cleaned),
        "skipped_private": skipped,
    }


## 已彻底砍掉：导出接口


@app.post("/api/admin/update_config")
def update_config(payload: UpdateConfigRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    incoming = payload.model_dump(exclude_none=True)
    template_name = incoming.pop("template_name", None)
    if not incoming:
        return {"code": 1, "msg": "未提供配置项"}

    print(
        f"[{now_text()}] 配置保存入口 | user={session['username']} template_name={template_name or '-'} fields={sorted(incoming.keys())}"
    )

    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    raw_ref = str(template_name or data.get("active_template") or "default").strip()
    source_owner, parsed_name, is_shared_ref = _parse_template_ref(raw_ref)
    if is_shared_ref and source_owner != session["username"]:
        return {"code": 1, "msg": "共享模板不可直接修改，请先复制为新模板"}
    active_template = parsed_name if is_shared_ref else _sanitize_template_name(raw_ref, "default")
    print(
        f"[{now_text()}] 配置更新 | user={session['username']} template_name={template_name or '-'} raw_ref={raw_ref} active_template={active_template} shared={is_shared_ref}"
    )
    templates = data.get("configs_by_template", {})
    if active_template not in templates:
        seed = dict(templates.get(data.get("active_template", "default"), MODE_DEFAULT_CONFIGS["growth"]))
        templates[active_template] = seed
    current = dict(templates.get(active_template, MODE_DEFAULT_CONFIGS["growth"]))
    current.update(incoming)
    current = _normalize_mutual_traffic_action_mutex(current)
    templates[active_template] = current
    data["active_template"] = active_template
    data["configs_by_template"] = templates
    data["configs_by_mode"] = {mode: dict(current) for mode in RUN_MODES}
    data["default_config"] = dict(current)
    _save_user_runtime_state(session["username"], data)
    print(f"[{now_text()}] 用户 {session['username']} | 模板 {active_template} 配置已更新：{incoming}")
    eff, meta = _build_effective_template_bundle(session["username"], data)
    return {
        "code": 0,
        "msg": "配置更新成功",
        "config": current,
        "active_template": active_template,
        "configs_by_template": eff,
        "template_meta": meta,
    }


@app.post("/api/admin/config/default")
def set_default_config(payload: UpdateConfigRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    incoming = payload.model_dump(exclude_none=True)
    incoming.pop("template_name", None)
    if not incoming:
        return {"code": 1, "msg": "未提供配置项"}

    data = _normalize_user_runtime_state(_load_user_runtime_state(session["username"]))
    templates = data.get("configs_by_template", {})
    for name, cfg in list(templates.items()):
        current = dict(cfg)
        current.update(incoming)
        templates[name] = current
    active_template = _sanitize_template_name(data.get("active_template"), "default")
    active_config = dict(templates.get(active_template, MODE_DEFAULT_CONFIGS["growth"]))
    data["configs_by_template"] = templates
    data["configs_by_mode"] = {mode: dict(active_config) for mode in RUN_MODES}
    data["default_config"] = dict(active_config)
    _save_user_runtime_state(session["username"], data)
    print(f"[{now_text()}] 用户 {session['username']} | 默认配置已更新：{incoming}")
    return {
        "code": 0,
        "msg": "默认配置更新成功",
        "config": active_config,
        "default_config": data["default_config"],
        "active_template": active_template,
        "configs_by_template": templates,
    }


@app.get("/api/cv_template")
def get_cv_template(device_id: str, alias: str):
    error, _device = auth_or_error(device_id, alias)
    if error:
        return error

    if not CV_TEMPLATE_ZIP_PATH.exists():
        return {"code": 1, "msg": "cv_templates.zip 不存在"}

    try:
        data = CV_TEMPLATE_ZIP_PATH.read_bytes()
        sha256 = hashlib.sha256(data).hexdigest()
        b64 = base64.b64encode(data).decode("utf-8")
        return {
            "code": 0,
            "msg": "ok",
            "version": sha256[:16],
            "sha256": sha256,
            "zip_b64": b64,
        }
    except Exception as exc:
        return {"code": 1, "msg": f"读取模板失败: {exc}"}


@app.post("/api/admin/tasks/trigger_collect_roots_excel")
def trigger_collect_roots_excel(container_ids: str = "", parallel: int = 1):
    env_ids = [x.strip() for x in (container_ids or "").splitlines() if x.strip()]
    if env_ids:
        jobs = []
        p = max(1, min(parallel, len(env_ids)))
        for i in range(p):
            group = [eid for idx, eid in enumerate(env_ids) if idx % p == i]
            if group:
                jobs.append(group)

        results = []
        for idx, group in enumerate(jobs, start=1):
            first = group[0]
            cmd = [sys.executable, str(FOLLOW_SCRAPER_PATH), "--mode", "collect_roots", "--container-id", first]
            results.append(_start_task_process(f"collect_roots_excel_worker_{idx}", cmd))
        return {"code": 0, "msg": "Excel抓取任务已按环境ID并行触发", "workers": len(jobs), "results": results}

    cmd = [sys.executable, str(FOLLOW_SCRAPER_PATH), "--mode", "collect_roots"]
    return _start_task_process("collect_roots_excel_once", cmd)


@app.post("/api/admin/tasks/trigger_collect_roots_db")
def trigger_collect_roots_db(
    limit: int = 100,
    container_ids: str = "",
    parallel: int = 1,
    full: bool = True,
    priority: str = "high",
):
    env_ids = [x.strip() for x in (container_ids or "").splitlines() if x.strip()]
    normalized_priority = (priority or "high").strip().lower()
    if normalized_priority not in {"high", "medium", "low"}:
        normalized_priority = "high"
    safe_limit = max(1, min(int(limit), 100000))
    effective_limit = safe_limit
    if full:
        conn = _get_db()
        try:
            total_roots = int(conn.execute("SELECT COUNT(1) FROM tiktok_roots").fetchone()[0] or 0)
        finally:
            conn.close()
        effective_limit = max(1, total_roots)
    else:
        total_roots = None

    if env_ids:
        jobs = []
        p = max(1, min(parallel, len(env_ids)))
        for i in range(p):
            group = [eid for idx, eid in enumerate(env_ids) if idx % p == i]
            if group:
                jobs.append(group)

        results = []
        for idx, group in enumerate(jobs, start=1):
            first = group[0]
            worker_limit = max(1, (effective_limit + len(jobs) - 1) // len(jobs))
            cmd = [
                sys.executable, str(FOLLOW_SCRAPER_PATH),
                "--mode", "collect_roots_db",
                "--limit", str(worker_limit),
                "--priority", normalized_priority,
                "--container-id", first,
            ]
            task_name = f"collect_roots_db_worker_{idx}"
            results.append(_start_task_process(task_name, cmd))
        return {
            "code": 0,
            "msg": "DB抓取任务已按环境ID并行触发",
            "workers": len(jobs),
            "full": bool(full),
            "priority": normalized_priority,
            "limit": effective_limit,
            "total_roots": total_roots,
            "results": results,
        }

    cmd = [
        sys.executable,
        str(FOLLOW_SCRAPER_PATH),
        "--mode",
        "collect_roots_db",
        "--limit",
        str(effective_limit),
        "--priority",
        normalized_priority,
    ]
    result = _start_task_process("collect_roots_db_once", cmd)
    if isinstance(result, dict):
        result["full"] = bool(full)
        result["priority"] = normalized_priority
        result["limit"] = effective_limit
        if total_roots is not None:
            result["total_roots"] = total_roots
    return result


@app.post("/api/admin/follower_queue/preflight_following_visibility")
def follower_queue_preflight_following_visibility(limit: int = 100):
    """批量预检关注列表可见性并回写 follower_queue 状态（不启动浏览器）。"""
    stats = preflight_follower_queue_following_visibility(limit=limit)
    return {
        "code": 0,
        "msg": "预检完成",
        "limit": max(1, min(1000, int(limit or 100))),
        "stats": stats,
    }


@app.post("/api/admin/tasks/trigger_process_queue")
def trigger_process_queue(
    limit: int = 100,
    container_ids: str = "",
    parallel: int = 1,
    preflight: bool = True,
):
    preflight_stats = None
    if preflight:
        preflight_stats = preflight_follower_queue_following_visibility(limit=limit)

    env_ids = [x.strip() for x in (container_ids or "").splitlines() if x.strip()]
    if env_ids:
        jobs = []
        p = max(1, min(parallel, len(env_ids)))
        for i in range(p):
            group = [eid for idx, eid in enumerate(env_ids) if idx % p == i]
            if group:
                jobs.append(group)

        results = []
        for idx, group in enumerate(jobs, start=1):
            first = group[0]
            worker_limit = max(1, limit // len(jobs))
            cmd = [
                sys.executable, str(FOLLOW_SCRAPER_PATH),
                "--mode", "process_queue",
                "--limit", str(worker_limit),
                "--container-id", first,
            ]
            results.append(_start_task_process(f"process_queue_worker_{idx}", cmd))
        return {
            "code": 0,
            "msg": "队列任务已按环境ID并行触发",
            "workers": len(jobs),
            "results": results,
            "preflight": preflight_stats,
        }

    cmd = [sys.executable, str(FOLLOW_SCRAPER_PATH), "--mode", "process_queue", "--limit", str(limit)]
    started = _start_task_process("process_queue_once", cmd)
    if isinstance(started, dict):
        started["preflight"] = preflight_stats
    return started


@app.post("/api/admin/tasks/trigger_autosearch_once")
def trigger_autosearch_once():
    cmd = [sys.executable, str(AUTOSEARCH_PATH), "--once"]
    return _start_task_process("autosearch_once", cmd)


@app.get("/api/admin/backup_stats_full_query/config")
def get_backup_stats_full_query_config(authorization: str | None = Header(default=None)):
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    with store.lock:
        enabled = bool(store.backup_stats_full_query_enabled) if _backup_stats_full_query_schedule_available() else False
        last_run_date = str(store.backup_stats_full_query_last_run_date or "")
        in_flight = bool(store.backup_stats_full_query_in_flight)
    return {
        "code": 0,
        "data": {
            "available": _backup_stats_full_query_schedule_available(),
            "enabled": enabled,
            "last_run_date": last_run_date,
            "in_flight": in_flight,
            "schedule": "每天北京时间 00:00（0:00–0:01 窗口触发）",
            "scope": "全部 owner 下所有备份档案 username（不限分组）",
            "beijing_date": _beijing_date_str(),
            "beijing_time": _beijing_now().strftime("%H:%M:%S"),
            "manual_available": _backup_stats_full_query_manual_available(),
            "disabled_reason": (
                ""
                if _backup_stats_full_query_schedule_available()
                else "每日定时全量查询暂时不可用"
            ),
            "manual_disabled_reason": (
                "" if _backup_stats_full_query_manual_available()
                else "全量查询并落库暂时不可用"
            ),
        },
    }


@app.post("/api/admin/backup_stats_full_query/config")
def config_backup_stats_full_query(
    payload: BackupStatsFullQueryConfigRequest,
    authorization: str | None = Header(default=None),
):
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    if not _backup_stats_full_query_schedule_available():
        _ensure_backup_stats_full_query_schedule_off()
        if payload.enabled:
            return {"code": 1, "msg": "每日定时全量查询暂时不可用"}
    with store.lock:
        if payload.enabled is not None:
            if payload.enabled and not _backup_stats_full_query_schedule_available():
                return {"code": 1, "msg": "每日定时全量查询暂时不可用"}
            store.backup_stats_full_query_enabled = bool(payload.enabled)
        enabled = bool(store.backup_stats_full_query_enabled)
    persist_runtime_state()
    if payload.enabled is not None:
        _backup_stats_full_query_log(
            f"[{now_text()}] 每日全量查询已{'开启' if enabled else '关闭'}"
        )
    return {
        "code": 0,
        "msg": "每日全量查询配置已更新",
        "data": {"enabled": enabled},
    }


@app.post("/api/admin/backup_stats_full_query/trigger_once")
def trigger_backup_stats_full_query_once(authorization: str | None = Header(default=None)):
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    if not _backup_stats_full_query_manual_available():
        return {"code": 1, "msg": "全量查询并落库暂时不可用"}
    with store.lock:
        if store.backup_stats_full_query_in_flight:
            return {"code": 1, "msg": "全量查询任务正在执行中，请稍后再试"}
        store.backup_stats_full_query_in_flight = True
    _backup_stats_full_query_log(f"[{now_text()}] 手动触发全量查询并落库")
    Thread(
        target=_backup_stats_full_query_worker,
        name="backup_stats_full_query_manual",
        daemon=True,
    ).start()
    return {"code": 0, "msg": "已提交后台全量查询任务，请在任务日志中查看进度"}


AUTO_SWITCH_MANUAL_BLOCK_MSG = "该设备已开启自动切号，请先关闭后再执行手动操作"


def _device_auto_switch_enabled(device_id: str) -> bool:
    did = str(device_id or "").strip()
    if not did:
        return False
    with store.lock:
        dev = store.devices.get(did)
        return bool(dev and dev.auto_switch_enabled)


def _auto_switch_enter_session_active(session_id: str) -> bool:
    """enter session 行仍标记为 enabled 且未 closed（管理台开启时同步写入 DB）。"""
    sid = str(session_id or "").strip()
    if not sid or not sid.startswith("as_on_"):
        return False
    conn = _get_db()
    try:
        row = conn.execute(
            "SELECT enabled, closed_at FROM auto_switch_sessions WHERE session_id = ?",
            (sid,),
        ).fetchone()
    finally:
        conn.close()
    if not row:
        return False
    enabled = row[0] if not hasattr(row, "keys") else row["enabled"]
    closed_at = row[1] if not hasattr(row, "keys") else row["closed_at"]
    return bool(int(enabled or 0)) and not str(closed_at or "").strip()


def _latest_auto_switch_enter_session_id(device_id: str) -> str:
    did = str(device_id or "").strip()
    if not did:
        return ""
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT session_id FROM device_mode_events
            WHERE device_id = ? AND event_type = 'auto_switch_enter'
            ORDER BY id DESC
            LIMIT 1
            """,
            (did,),
        ).fetchone()
    finally:
        conn.close()
    if not row:
        return ""
    return str((row[0] if not hasattr(row, "keys") else row["session_id"]) or "").strip()


def _sync_device_auto_switch_enabled(device_id: str, enabled: bool, *, force_persist: bool = False) -> None:
    did = str(device_id or "").strip()
    if not did:
        return
    with store.lock:
        dev = store.devices.get(did)
        if not dev:
            _hydrate_device_from_db(did)
            dev = store.devices.get(did)
        if dev:
            dev.auto_switch_enabled = bool(enabled)
    if force_persist or enabled:
        _persist_client_device_to_db(did, force=True)


def _device_auto_switch_enabled_resolved(device_id: str, session_id: str = "") -> bool:
    """内存开关 + enter session 真源（修复多 worker / 异步落库滞后导致 bundle 被拒）。"""
    if _device_auto_switch_enabled(device_id):
        return True
    # 管理台已关且有待投递 exit 时，不得凭陈旧 enter session 把开关复活。
    if _has_pending_auto_switch_exit_event(device_id):
        return False
    sid = str(session_id or "").strip() or _latest_auto_switch_enter_session_id(device_id)
    if sid and _auto_switch_enter_session_active(sid):
        _sync_device_auto_switch_enabled(device_id, True, force_persist=True)
        return True
    return False


def _any_device_auto_switch_enabled() -> bool:
    with store.lock:
        return any(bool(d.auto_switch_enabled) for d in store.devices.values())


def _resolve_device_id_for_owner_alias(owner_username: str, alias: str, fallback_device_id: str = "") -> str:
    alias_key = _normalize_alias_key(alias)
    owner = str(owner_username or "").strip()
    fallback = str(fallback_device_id or "").strip()
    if fallback:
        with store.lock:
            dev = store.devices.get(fallback)
            if dev and str(dev.owner_username or "").strip() == owner:
                if not alias_key or _normalize_alias_key(dev.alias) == alias_key:
                    return fallback
    if not owner or not alias_key:
        return fallback
    with store.lock:
        for did, dev in store.devices.items():
            if str(dev.owner_username or "").strip() != owner:
                continue
            if _normalize_alias_key(dev.alias) == alias_key:
                return str(did)
    return fallback


def _reject_if_auto_switch_manual_ops(
    device_id: str = "",
    owner_username: str = "",
    alias: str = "",
) -> dict | None:
    did = str(device_id or "").strip()
    if not did:
        did = _resolve_device_id_for_owner_alias(owner_username, alias)
    if did and _device_auto_switch_enabled(did):
        return {"code": 1, "msg": AUTO_SWITCH_MANUAL_BLOCK_MSG}
    return None


def _auto_switch_enabled_now() -> bool:
    """兼容旧调用：任一设备开启自动切号即视为功能活跃。"""
    return _any_device_auto_switch_enabled()


def _new_auto_switch_session_id(*, entering: bool) -> str:
    prefix = "as_on" if entering else "as_off"
    return f"{prefix}_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"


def _persist_auto_switch_session_row(session_id: str, *, enabled: bool, bundle_revision: int) -> None:
    conn = _get_db()
    try:
        conn.execute(
            """
            INSERT OR REPLACE INTO auto_switch_sessions
            (session_id, enabled, bundle_revision, created_at, closed_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (
                session_id,
                1 if enabled else 0,
                int(bundle_revision or 0),
                now_text(),
                None if enabled else now_text(),
            ),
        )
        conn.commit()
    finally:
        conn.close()


def _close_active_auto_switch_enter_sessions_for_device(
    device_id: str,
    *,
    except_session_id: str = "",
) -> int:
    """关闭设备仍标记为开启的 enter session，避免 resolved() 用过期 session 复活开关。"""
    did = str(device_id or "").strip()
    if not did:
        return 0
    except_sid = str(except_session_id or "").strip()
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT DISTINCT e.session_id
            FROM device_mode_events e
            INNER JOIN auto_switch_sessions s ON s.session_id = e.session_id
            WHERE e.device_id = ?
              AND e.event_type = 'auto_switch_enter'
              AND e.session_id LIKE 'as_on_%'
              AND s.closed_at IS NULL
              AND s.enabled = 1
            """,
            (did,),
        ).fetchall()
        closed = 0
        ts = now_text()
        for row in rows:
            sid = str((row[0] if not hasattr(row, "keys") else row["session_id"]) or "").strip()
            if not sid or sid == except_sid:
                continue
            cur = conn.execute(
                """
                UPDATE auto_switch_sessions
                SET enabled = 0, closed_at = COALESCE(closed_at, ?)
                WHERE session_id = ? AND closed_at IS NULL
                """,
                (ts, sid),
            )
            closed += int(cur.rowcount or 0)
        conn.commit()
        return closed
    finally:
        conn.close()


def _has_pending_auto_switch_exit_event(device_id: str) -> bool:
    did = str(device_id or "").strip()
    if not did:
        return False
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT 1 FROM device_mode_events
            WHERE device_id = ? AND event_type = 'auto_switch_exit' AND acked_at IS NULL
            LIMIT 1
            """,
            (did,),
        ).fetchone()
        return row is not None
    finally:
        conn.close()


def _supersede_pending_device_mode_events(
    device_id: str,
    event_types: tuple[str, ...] | list[str],
) -> int:
    """作废设备信箱中尚未 ACK 的指定类型模式事件，避免过期 enter/exit 覆盖管理台最新意图。"""
    did = str(device_id or "").strip()
    types = [str(t or "").strip() for t in (event_types or []) if str(t or "").strip()]
    if not did or not types:
        return 0
    placeholders = ",".join("?" for _ in types)
    conn = _get_db()
    try:
        cur = conn.execute(
            f"""
            UPDATE device_mode_events
            SET acked_at = ?, ack_phase = 'superseded'
            WHERE device_id = ? AND acked_at IS NULL AND event_type IN ({placeholders})
            """,
            (now_text(), did, *types),
        )
        conn.commit()
        return int(cur.rowcount or 0)
    finally:
        conn.close()


def _reconcile_auto_switch_mode_event_mailbox() -> None:
    """启动后与 client_devices 开关对齐：作废与当前状态矛盾的待投递模式事件。"""
    with store.lock:
        devices = list(store.devices.items())
    exit_superseded = 0
    enter_superseded = 0
    for did, dev in devices:
        if bool(dev.auto_switch_enabled):
            exit_superseded += _supersede_pending_device_mode_events(did, ("auto_switch_exit",))
        else:
            enter_superseded += _supersede_pending_device_mode_events(did, ("auto_switch_enter",))
    total = exit_superseded + enter_superseded
    if total > 0:
        _auto_switch_log(
            f"启动对齐模式信箱：作废 exit={exit_superseded} enter={enter_superseded}"
        )


def _enqueue_device_mode_event(
    device_id: str,
    owner_username: str,
    event_type: str,
    session_id: str,
    bundle_revision: int = 0,
    payload: dict | None = None,
) -> int:
    """写入设备模式事件信箱，返回 event_id。"""
    did = str(device_id or "").strip()
    owner = str(owner_username or "").strip()
    et = str(event_type or "").strip()
    sid = str(session_id or "").strip()
    if not did or not owner or not et or not sid:
        return 0
    payload_json = json.dumps(payload or {}, ensure_ascii=False)
    conn = _get_db()
    try:
        cur = conn.execute(
            """
            INSERT INTO device_mode_events
            (device_id, owner_username, event_type, session_id, bundle_revision, payload_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (did, owner, et, sid, int(bundle_revision or 0), payload_json, now_text()),
        )
        conn.commit()
        return int(cur.lastrowid or 0)
    finally:
        conn.close()


def _enqueue_auto_switch_mode_events_for_all_devices(
    event_type: str,
    session_id: str,
    bundle_revision: int = 0,
) -> int:
    """为全部绑定设备写入 ENTER/EXIT 事件，返回写入条数。"""
    et = str(event_type or "").strip()
    sid = str(session_id or "").strip()
    if not et or not sid:
        return 0
    with store.lock:
        devices = list(store.devices.items())
    count = 0
    for did, dev in devices:
        eid = _enqueue_device_mode_event(
            did,
            dev.owner_username,
            et,
            sid,
            bundle_revision=bundle_revision,
        )
        if eid > 0:
            count += 1
            _append_device_log(
                did,
                f"[{now_text()}] 自动切号 | 已投递模式事件 {et} session={sid} event_id={eid}",
            )
    return count


def _enqueue_auto_switch_mode_events_for_device_ids(
    device_ids: list[str],
    event_type: str,
    session_id: str,
    bundle_revision: int = 0,
) -> int:
    et = str(event_type or "").strip()
    sid = str(session_id or "").strip()
    if not et or not sid:
        return 0
    count = 0
    for did in device_ids:
        did_clean = str(did or "").strip()
        if not did_clean:
            continue
        with store.lock:
            dev = store.devices.get(did_clean)
            if not dev:
                continue
            owner = dev.owner_username
        eid = _enqueue_device_mode_event(
            did_clean,
            owner,
            et,
            sid,
            bundle_revision=bundle_revision,
        )
        if eid > 0:
            count += 1
            _append_device_log(
                did_clean,
                f"[{now_text()}] 自动切号 | 已投递模式事件 {et} session={sid} event_id={eid}",
            )
    return count


def _cancel_pending_auto_switch_device_ops_for_device(device_id: str) -> int:
    did = str(device_id or "").strip()
    if not did:
        return 0
    rollback_items: list[tuple[str, dict[str, Any]]] = []
    removed_op_ids: set[str] = set()
    removed = 0
    with store.lock:
        queue = list(store.pending_device_ops.get(did) or [])
        if not queue:
            return 0
        owner = ""
        dev = store.devices.get(did)
        if dev:
            owner = str(dev.owner_username or "").strip()
        kept: list[dict[str, Any]] = []
        for op in queue:
            if not _is_pending_auto_switch_device_op(op):
                kept.append(op)
                continue
            removed += 1
            op_id = str(op.get("op_id") or "").strip()
            if op_id:
                removed_op_ids.add(op_id)
            payload = _device_op_payload(op)
            op_type = str(op.get("op_type") or "")
            if owner and op_type in ("tiktok_backup_and_clear", "tiktok_restore", "tiktok_clear"):
                if _resolve_cut_switch_source_row_index(payload) >= 0:
                    rollback_items.append((owner, dict(payload)))
        if kept:
            store.pending_device_ops[did] = kept
        else:
            store.pending_device_ops.pop(did, None)
        if removed_op_ids:
            for bucket_key, inflight in list(store.auto_switch_publish_inflight_keys.items()):
                if not bucket_key.endswith(f"|{_normalize_alias_key(dev.alias if dev else '')}"):
                    continue
                _slot, op_id = _parse_auto_switch_publish_inflight(str(inflight))
                if op_id in removed_op_ids:
                    store.auto_switch_publish_inflight_keys.pop(bucket_key, None)
    seen_rollback: set[tuple[str, int]] = set()
    for owner, data in rollback_items:
        src = _resolve_cut_switch_source_row_index(data)
        key = (owner, src)
        if src < 0 or key in seen_rollback:
            continue
        seen_rollback.add(key)
        _device_ops_rollback_cut_switch_highlight(owner, data)
    return removed


def _build_auto_switch_account_configs(
    owner: str,
    data: dict,
    mode: str,
    alias_key: str,
    account_rows: list[dict[str, Any]],
    session_id: str = "",
) -> tuple[dict[str, dict[str, Any]], dict[str, Any], str, dict[str, Any]]:
    """为桶内每个账号解析模板并打包完整 trace 配置（含智能模板 per-account + session 重掷）。"""
    templates, _template_meta = _build_effective_template_bundle(owner, data)
    mode_key = mode if mode in RUN_MODES else str(data.get("run_mode") or "growth")
    smart_enabled = _alias_smart_template_enabled(data, mode_key, alias_key)
    configs_by_username: dict[str, dict[str, Any]] = {}
    accounts_debug: dict[str, dict[str, Any]] = {}

    for row in account_rows:
        username = str(row.get("username") or "").strip()
        if not username:
            continue
        account_key = _normalize_tiktok_username_key(username)
        if not account_key:
            continue
        if smart_enabled and mode_key in SMART_TEMPLATE_RUN_MODES and templates:
            row_stats = {
                "tiktok_username": account_key,
                "follower_count": _parse_stat_int(row.get("follower_count")),
                "video_count": _parse_stat_int(row.get("video_count")),
            }
            selected_name, smart_debug = _resolve_smart_template_for_alias(
                owner,
                data,
                mode_key,
                alias_key,
                templates,
                account_stats=row_stats,
                session_id=session_id,
            )
            config = dict(templates.get(selected_name, MODE_DEFAULT_CONFIGS["growth"]))
            accounts_debug[account_key] = {
                **smart_debug,
                "resolution_source": "smart_template",
            }
        else:
            selected_name, config, _, _ = _resolve_template_for_mode_alias(
                owner,
                data,
                mode_key,
                alias_key,
                username,
                emit_log=False,
            )
            accounts_debug[account_key] = {
                "tiktok_username": account_key,
                "selected_template": selected_name,
                "follower_count": _parse_stat_int(row.get("follower_count")),
                "video_count": _parse_stat_int(row.get("video_count")),
                "resolution_source": "alias_binding",
            }
        configs_by_username[account_key] = {
            "active_template": selected_name,
            "config": config,
        }

    if configs_by_username:
        from collections import Counter

        template_counts = Counter(
            str(entry.get("active_template") or "").strip()
            for entry in configs_by_username.values()
            if str(entry.get("active_template") or "").strip()
        )
        summary = ",".join(f"{name}×{cnt}" for name, cnt in sorted(template_counts.items()))
        path = "smart_template" if smart_enabled else "alias_binding"
        print(
            f"[{now_text()}] 模板解析 | owner={owner} mode={mode_key} alias={alias_key} "
            f"accounts={len(configs_by_username)} selected={summary or '-'} path={path}"
        )

    highlight_user = _resolve_switch_highlight_tiktok_username(owner, alias_key)
    highlight_key = _normalize_tiktok_username_key(highlight_user)
    highlight_entry = configs_by_username.get(highlight_key)
    if not highlight_entry and configs_by_username:
        highlight_entry = next(iter(configs_by_username.values()))
    active_template = str((highlight_entry or {}).get("active_template") or "")
    eff_config = dict((highlight_entry or {}).get("config") or MODE_DEFAULT_CONFIGS["growth"])

    smart_template_debug: dict[str, Any] = {
        "enabled": smart_enabled,
        "session_id": str(session_id or "").strip(),
        "mode": mode_key,
        "alias": alias_key,
        "enter_reroll": bool(str(session_id or "").strip()),
        "accounts": accounts_debug,
    }
    return configs_by_username, smart_template_debug, active_template, eff_config


def _build_auto_switch_bundle_for_device(
    device_id: str,
    alias: str,
    session_id: str = "",
    revision: int = 0,
) -> dict[str, Any]:
    error, device = auth_or_error(device_id, alias)
    if error:
        raise ValueError(str(error.get("msg") or "设备鉴权失败"))
    owner = str(device.owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    schedule_date = _beijing_date_str()
    buckets = _list_device_editor_buckets(owner)
    rows = buckets.get(alias_key, [])
    if not rows:
        raise ValueError(f"别名 {alias_key} 无备份档案")
    weekly_plan = _ensure_auto_switch_weekly_plan(owner, alias_key, rows, schedule_date)
    week_start = str(weekly_plan.get("week_start_date") or "").strip()
    week_end = str(weekly_plan.get("week_end_date") or "").strip()
    plan = _daily_plan_for_schedule_date(weekly_plan, schedule_date)
    if not plan:
        raise ValueError(f"本周排班缺少今日计划 date={schedule_date}")
    minute_of_day = _beijing_minute_of_day()
    slot_highlight_ri = _resolve_slot_highlight_row_index(
        owner, alias_key, schedule_date, minute_of_day, rows,
    )
    runtime = _normalize_user_runtime_state(_load_user_runtime_state(owner))
    mode = str(runtime.get("run_mode") or "growth")
    if mode not in RUN_MODES:
        mode = "growth"
    accounts: list[dict[str, Any]] = []
    conn = _get_db()
    try:
        # 切号高亮落库在 device_ops 回传 / ledger 写回时同步；bundle 只读排班派生高亮，避免每次拉包写库。
        for row in rows:
            username = str(row.get("username") or "").strip()
            ri = int(row.get("row_index") or 0)
            acct_fields = _account_bundle_fields_for_username(conn, owner, username)
            accounts.append(
                {
                    "row_index": ri,
                    "username": username,
                    "backup_path": str(row.get("backup_path") or "").strip(),
                    "backup_name": str(row.get("backup_name") or "").strip(),
                    "assigned_alias": str(row.get("assigned_alias") or "").strip(),
                    "device_alias": str(row.get("device_alias") or "").strip(),
                    "switch_highlight": "是" if ri == slot_highlight_ri else "否",
                    "video_count": row.get("video_count"),
                    "follower_count": row.get("follower_count"),
                    "follower_growth": row.get("follower_growth"),
                    **acct_fields,
                }
            )
    finally:
        conn.close()
    configs_by_username, smart_template_debug, active_template, eff_config = _build_auto_switch_account_configs(
        owner,
        runtime,
        mode,
        alias_key,
        accounts,
        session_id=str(session_id or "").strip(),
    )
    mat_fetch = eff_config.get("publish_post_material_fetch_count", 3)
    try:
        mat_fetch_n = int(mat_fetch)
    except (TypeError, ValueError):
        mat_fetch_n = 3
    pc_service_url = _load_device_pc_service_url(str(device_id or "").strip())
    return {
        "session_id": str(session_id or "").strip(),
        "revision": int(revision or 0),
        "schedule_date": schedule_date,
        "week_start_date": week_start,
        "week_end_date": week_end,
        "weekly_plan": _weekly_plan_for_bundle(weekly_plan),
        "timezone": "Asia/Shanghai",
        "owner_username": owner,
        "device_alias": alias_key,
        "device_id": str(device_id or "").strip(),
        "pc_service_url": pc_service_url,
        "run_config": {
            "run_mode": mode,
            "active_template": str(active_template or ""),
            "publish_post_material_fetch_count": mat_fetch_n,
            "package_name": TIKTOK_PACKAGE_NAME,
        },
        "configs_by_username": configs_by_username,
        "smart_template_debug": smart_template_debug,
        "accounts": accounts,
        "daily_plan": plan,
        "retry_policy": {
            "cut_switch_max_attempts": 3,
            "login_max_attempts": 5,
            "publish_max_attempts": 3,
            "backoff_base_sec": 30,
            "backoff_max_sec": 600,
        },
    }


def _fetch_pending_device_mode_event(device_id: str, since_event_id: int = 0) -> dict[str, Any] | None:
    """尽力投递：每条模式事件仅按 since_event_id 投递一次，不依赖 ACK 决定是否重发。

    管理台 auto_switch_enabled 为开关真源；ACK 仅作可观测回执，丢指令由设备端对账兜底。
    """
    did = str(device_id or "").strip()
    if not did:
        return None
    since = max(0, int(since_event_id or 0))
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT id, event_type, session_id, bundle_revision, payload_json, created_at
            FROM device_mode_events
            WHERE device_id = ? AND id > ?
            ORDER BY id ASC
            LIMIT 1
            """,
            (did, since),
        ).fetchone()
    finally:
        conn.close()
    if not row:
        return None
    payload: dict[str, Any] = {}
    try:
        raw = json.loads(row[4] or "{}")
        if isinstance(raw, dict):
            payload = raw
    except Exception:
        payload = {}
    return {
        "event_id": int(row[0]),
        "type": str(row[1] or ""),
        "session_id": str(row[2] or ""),
        "bundle_revision": int(row[3] or 0),
        "payload": payload,
        "issued_at": str(row[5] or ""),
    }


_AUTO_SWITCH_MODE_ACK_TERMINAL_PHASES = frozenset({"bundle_loaded", "summary_uploaded"})


def _ack_device_mode_event(
    device_id: str,
    event_id: int,
    phase: str,
    session_id: str = "",
) -> bool:
    did = str(device_id or "").strip()
    eid = int(event_id or 0)
    ph = str(phase or "").strip()
    if not did or eid <= 0 or not ph:
        return False
    conn = _get_db()
    try:
        sid = str(session_id or "").strip()
        event_row = conn.execute(
            "SELECT event_type, acked_at FROM device_mode_events WHERE device_id = ? AND id = ?",
            (did, eid),
        ).fetchone()
        if not event_row:
            return False
        event_type = str(event_row[0] or "").strip()
        if event_row[1]:
            return True
        is_terminal = ph in _AUTO_SWITCH_MODE_ACK_TERMINAL_PHASES

        def _run_ack(require_session: bool) -> int:
            if is_terminal:
                params: list[Any] = [now_text(), ph, did, eid]
                sql = """
                    UPDATE device_mode_events
                    SET acked_at = ?, ack_phase = ?
                    WHERE device_id = ? AND id = ? AND acked_at IS NULL
                """
            else:
                params = [ph, did, eid]
                sql = """
                    UPDATE device_mode_events
                    SET ack_phase = ?
                    WHERE device_id = ? AND id = ? AND acked_at IS NULL
                """
            if require_session and sid:
                sql += " AND session_id = ?"
                params.append(sid)
            cur = conn.execute(sql, tuple(params))
            return int(cur.rowcount or 0)

        ok = _run_ack(require_session=bool(sid)) > 0
        if not ok and sid and is_terminal:
            ok = _run_ack(require_session=False) > 0
        conn.commit()
    finally:
        conn.close()
    if ok and is_terminal:
        # 管理台 set_auto_switch_devices 已是开关真源；终端 ACK 仅确认，不得用过期事件反向覆盖。
        if event_type == "auto_switch_enter" and ph == "bundle_loaded":
            with store.lock:
                dev = store.devices.get(did)
                if dev and not dev.auto_switch_enabled:
                    _auto_switch_log(
                        f"忽略过期 enter ACK event_id={eid} device={did}（管理台当前为关）"
                    )
                elif dev:
                    _persist_client_device_to_db(did)
        elif event_type == "auto_switch_exit" and ph == "summary_uploaded":
            with store.lock:
                dev = store.devices.get(did)
                if dev and dev.auto_switch_enabled:
                    _auto_switch_log(
                        f"忽略过期 exit ACK event_id={eid} device={did}（管理台已重新开启）"
                    )
                elif dev:
                    _persist_client_device_to_db(did)
    return ok


@app.get("/api/admin/auto_switch/config")
def get_auto_switch_config(authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session)
    devices_out: list[dict[str, Any]] = []
    try:
        with _store_lock("auto_switch_config", wait_sec=STORE_LOCK_WAIT_SEC):
            for did in sorted(allowed):
                dev = store.devices.get(did)
                if not dev:
                    continue
                devices_out.append(
                    {
                        "device_id": did,
                        "alias": str(dev.alias or ""),
                        "enabled": bool(dev.auto_switch_enabled),
                    }
                )
    except StoreLockBusy:
        return {"code": 503, "msg": "服务繁忙，请稍后重试"}
    enabled_count = sum(1 for d in devices_out if d.get("enabled"))
    autonomous_mode = enabled_count > 0
    return {
        "code": 0,
        "data": {
            "devices": devices_out,
            "enabled_count": enabled_count,
            "any_enabled": enabled_count > 0,
            "autonomous_mode": autonomous_mode,
            "cloud_tick_active": not autonomous_mode,
            "autonomous_idle_sec": AUTO_SWITCH_AUTONOMOUS_IDLE_SEC,
            "beijing_date": _beijing_date_str(),
            "beijing_time": _beijing_now().strftime("%H:%M:%S"),
            "tick_interval_sec": AUTO_SWITCH_TICK_INTERVAL_SEC,
        },
    }


@app.post("/api/admin/auto_switch/devices")
def set_auto_switch_devices(payload: AutoSwitchDevicesRequest, authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session)
    device_ids = [str(d or "").strip() for d in (payload.device_ids or []) if str(d or "").strip()]
    device_ids = [d for d in device_ids if d in allowed]
    if not device_ids:
        return {"code": 1, "msg": "未选择有效设备"}
    target_enabled = bool(payload.enabled)
    session_id = _new_auto_switch_session_id(entering=target_enabled)
    bundle_revision = 0
    if target_enabled:
        with store.lock:
            store.auto_switch_bundle_revision = int(store.auto_switch_bundle_revision or 0) + 1
            bundle_revision = int(store.auto_switch_bundle_revision)
        persist_runtime_state()
        _persist_auto_switch_session_row(session_id, enabled=True, bundle_revision=bundle_revision)
    else:
        _persist_auto_switch_session_row(session_id, enabled=False, bundle_revision=0)

    changed = 0
    notified = 0
    resynced = 0
    cancelled_ops = 0
    for did in device_ids:
        turning_on = False
        turning_off = False
        resync_bundle = False
        owner = ""
        if not store.devices.get(did):
            _hydrate_device_from_db(did)
        with store.lock:
            dev = store.devices.get(did)
            if not dev:
                continue
            owner = str(dev.owner_username or "").strip()
            was_enabled = bool(dev.auto_switch_enabled)
            dev.auto_switch_enabled = bool(target_enabled)
            if target_enabled and not was_enabled:
                turning_on = True
            elif target_enabled and was_enabled:
                resync_bundle = True
            elif (not target_enabled) and was_enabled:
                turning_off = True
        _persist_client_device_to_db(did, force=True)
        if turning_on or turning_off:
            changed += 1
        if turning_on:
            closed_sessions = _close_active_auto_switch_enter_sessions_for_device(
                did, except_session_id=session_id
            )
            if closed_sessions > 0:
                _auto_switch_log(
                    f"开启前关闭 {closed_sessions} 个陈旧 enter session device={did} session={session_id}"
                )
            superseded = _supersede_pending_device_mode_events(did, ("auto_switch_exit",))
            if superseded > 0:
                _auto_switch_log(
                    f"开启前作废 {superseded} 条待投递 exit 事件 device={did} session={session_id}"
                )
            if _enqueue_device_mode_event(did, owner, "auto_switch_enter", session_id, bundle_revision=bundle_revision):
                notified += 1
                _append_device_log(did, f"[{now_text()}] 自动切号 | 已开启自治 session={session_id}")
                with store.lock:
                    store.auto_switch_align_on_next_tick = True
        elif resync_bundle:
            # 管理台再次点「开启」：设备可能已在自治中，仍须推送 bundle 供终端对账 username / 整理本地文件。
            closed_sessions = _close_active_auto_switch_enter_sessions_for_device(
                did, except_session_id=session_id
            )
            if closed_sessions > 0:
                _auto_switch_log(
                    f"资料重同步前关闭 {closed_sessions} 个陈旧 enter session device={did} session={session_id}"
                )
            superseded = _supersede_pending_device_mode_events(did, ("auto_switch_exit",))
            if superseded > 0:
                _auto_switch_log(
                    f"资料重同步前作废 {superseded} 条待投递 exit 事件 device={did} session={session_id}"
                )
            if _enqueue_device_mode_event(did, owner, "auto_switch_enter", session_id, bundle_revision=bundle_revision):
                notified += 1
                resynced += 1
                _append_device_log(
                    did,
                    f"[{now_text()}] 自动切号 | 资料重同步 session={session_id} revision={bundle_revision}",
                )
                with store.lock:
                    store.auto_switch_align_on_next_tick = True
        elif turning_off:
            closed_sessions = _close_active_auto_switch_enter_sessions_for_device(did)
            if closed_sessions > 0:
                _auto_switch_log(
                    f"关闭时结束 {closed_sessions} 个活跃 enter session device={did} session={session_id}"
                )
            superseded = _supersede_pending_device_mode_events(did, ("auto_switch_enter",))
            if superseded > 0:
                _auto_switch_log(
                    f"关闭前作废 {superseded} 条待投递 enter 事件 device={did} session={session_id}"
                )
            cancelled_ops += _cancel_pending_auto_switch_device_ops_for_device(did)
            if _enqueue_device_mode_event(did, owner, "auto_switch_exit", session_id, bundle_revision=0):
                notified += 1
                _append_device_log(did, f"[{now_text()}] 自动切号 | 已关闭自治 session={session_id}")

    if target_enabled:
        _auto_switch_log(
            f"已为 {changed} 台设备开启自动切号、{resynced} 台资料重同步 "
            f"session={session_id} revision={bundle_revision} notified={notified}"
        )
    else:
        _auto_switch_log(
            f"已为 {changed} 台设备关闭自动切号 session={session_id} cancelled_ops={cancelled_ops} notified={notified}"
        )
    return {
        "code": 0,
        "msg": "自动切号设备配置已更新",
        "data": {
            "enabled": target_enabled,
            "session_id": session_id,
            "bundle_revision": bundle_revision if target_enabled else 0,
            "devices_changed": changed,
            "devices_notified": notified,
            "devices_resynced": resynced,
            "cancelled_ops": cancelled_ops,
        },
    }


@app.post("/api/admin/auto_switch/config")
def config_auto_switch(payload: AutoSwitchConfigRequest, authorization: str | None = Header(default=None)):
    """兼容旧全局开关：转为对当前用户全部设备批量开/关。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    if payload.enabled is None:
        return get_auto_switch_config(authorization=authorization)
    allowed = sorted(_session_device_ids(session))
    req = AutoSwitchDevicesRequest(device_ids=allowed, enabled=bool(payload.enabled))
    return set_auto_switch_devices(req, authorization=authorization)


@app.get("/api/auto_switch/bundle")
def get_auto_switch_bundle(
    device_id: str,
    alias: str,
    session_id: str = "",
    revision: int = 0,
):
    error, _device = auth_or_error(device_id, alias)
    if error:
        return error
    if not _device_auto_switch_enabled_resolved(device_id, session_id):
        return {"code": 1, "msg": "该设备未开启自动切号"}
    try:
        bundle = _build_auto_switch_bundle_for_device(device_id, alias, session_id, revision)
    except ValueError as exc:
        return {"code": 1, "msg": str(exc)}
    except Exception as exc:
        return {"code": 1, "msg": f"构建自治包失败：{exc}"}
    return {"code": 0, "bundle": bundle}


@app.get("/api/admin/auto_switch/bundle_preview")
def admin_auto_switch_bundle_preview(
    device_id: str,
    alias: str,
    session_id: str = "",
    authorization: str | None = Header(default=None),
):
    """管理台预览自治 bundle（含 configs_by_username 与 smart_template_debug）。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = set(_session_device_ids(session))
    did = str(device_id or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not did or not alias_key:
        return {"code": 1, "msg": "device_id 与 alias 不能为空"}
    if did not in allowed:
        return {"code": 1, "msg": "无权预览该设备"}
    preview_session = str(session_id or "").strip() or f"preview-{uuid.uuid4().hex[:12]}"
    try:
        bundle = _build_auto_switch_bundle_for_device(did, alias_key, preview_session, revision=0)
    except ValueError as exc:
        return {"code": 1, "msg": str(exc)}
    except Exception as exc:
        return {"code": 1, "msg": f"构建预览 bundle 失败：{exc}"}
    return {"code": 0, "bundle": bundle, "preview_session_id": preview_session}


_AUTO_SWITCH_LEDGER_WRITEBACK_OP_TYPES = frozenset(
    {"tiktok_backup_and_clear", "tiktok_clear", "tiktok_restore"}
)


def _auto_switch_ledger_entry_writeback_eligible(entry: dict[str, Any]) -> tuple[bool, str]:
    """仅备份成功、还原成功、切号高亮相关条目可写回 device_editor_rows。"""
    op_type = str(entry.get("op_type") or "").strip()
    phase = str(entry.get("phase") or entry.get("status") or "").strip().lower()
    status = str(entry.get("status") or "").strip().lower()
    terminal = phase in ("success", "failed") or status in ("success", "failed")
    if op_type not in _AUTO_SWITCH_LEDGER_WRITEBACK_OP_TYPES:
        return False, f"{op_type or '未知类型'} 不参与档案写回，仅记入对账"
    if not terminal:
        return False, f"{op_type} 未达终态（phase={phase or status or '-'}），跳过写回"
    data = _sanitize_auto_switch_ledger_report_data(entry.get("report_data"))
    if not data:
        return False, f"{op_type} 缺少 report_data，无法写回"
    if op_type == "tiktok_backup_and_clear":
        if status != "success" and phase != "success":
            return True, ""  # 失败走 rollback
        if bool(data.get("backup_skipped")):
            return False, "备份已跳过（backup_skipped），不写回备份字段"
    return True, ""


def _normalize_auto_switch_summary_kind(kind: object) -> str:
    k = str(kind or "").strip().lower()
    return k if k in ("final", "daily") else "final"


def _resolve_auto_switch_reconcile_meta(payload: AutoSwitchSessionSummaryRequest) -> tuple[str, str]:
    kind = _normalize_auto_switch_summary_kind(payload.summary_kind)
    key = str(payload.reconcile_key or "").strip()
    if not key:
        key = "final" if kind == "final" else _beijing_date_str()
    return kind, key


def _find_auto_switch_session_summary_id(
    owner_username: str,
    device_id: str,
    alias: str,
    session_id: str,
    reconcile_key: str = "final",
) -> int:
    owner = str(owner_username or "").strip()
    did = str(device_id or "").strip()
    alias_key = _normalize_alias_key(alias)
    sid = str(session_id or "").strip()
    rkey = str(reconcile_key or "final").strip() or "final"
    if not owner or not did or not alias_key or not sid:
        return 0
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT id FROM auto_switch_session_summaries
            WHERE owner_username = ? AND device_id = ? AND alias = ? AND session_id = ?
              AND reconcile_key = ?
            ORDER BY id DESC LIMIT 1
            """,
            (owner, did, alias_key, sid, rkey),
        ).fetchone()
        return int(row[0]) if row else 0
    finally:
        conn.close()


def _save_auto_switch_session_summary(
    owner_username: str,
    device_id: str,
    alias: str,
    payload: AutoSwitchSessionSummaryRequest,
    reconciliation: dict[str, Any],
) -> int:
    owner = str(owner_username or "").strip()
    did = str(device_id or "").strip()
    alias_key = _normalize_alias_key(alias)
    sid = str(payload.session_id or "").strip()
    if not owner or not did or not alias_key or not sid:
        return 0
    summary_kind, reconcile_key = _resolve_auto_switch_reconcile_meta(payload)
    summary_doc = {
        "session_id": sid,
        "device_id": did,
        "alias": alias_key,
        "summary_kind": summary_kind,
        "reconcile_key": reconcile_key,
        "bundle_revision": int(payload.bundle_revision or 0),
        "schedule_date": str(payload.schedule_date or "").strip(),
        "started_at": str(payload.started_at or "").strip(),
        "ended_at": str(payload.ended_at or "").strip(),
        "ledger": [e.model_dump() for e in payload.ledger],
        "human_log": str(payload.human_log or "").strip(),
        "stats": payload.stats if isinstance(payload.stats, dict) else {},
        "reconciliation": reconciliation,
    }
    smart_dbg = payload.smart_template_debug if isinstance(payload.smart_template_debug, dict) else {}
    if smart_dbg:
        summary_doc["smart_template_debug"] = smart_dbg
    existing_id = _find_auto_switch_session_summary_id(owner, did, alias_key, sid, reconcile_key)
    conn = _get_db()
    try:
        if existing_id > 0:
            conn.execute(
                """
                UPDATE auto_switch_session_summaries
                SET bundle_revision = ?, schedule_date = ?, summary_json = ?, human_log = ?,
                    summary_kind = ?, reconcile_key = ?
                WHERE id = ?
                """,
                (
                    int(payload.bundle_revision or 0),
                    str(payload.schedule_date or "").strip(),
                    json.dumps(summary_doc, ensure_ascii=False),
                    str(payload.human_log or "").strip(),
                    summary_kind,
                    reconcile_key,
                    existing_id,
                ),
            )
            conn.commit()
            return existing_id
        cur = conn.execute(
            """
            INSERT INTO auto_switch_session_summaries
            (session_id, device_id, owner_username, alias, bundle_revision, schedule_date,
             summary_json, human_log, summary_kind, reconcile_key, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                sid,
                did,
                owner,
                alias_key,
                int(payload.bundle_revision or 0),
                str(payload.schedule_date or "").strip(),
                json.dumps(summary_doc, ensure_ascii=False),
                str(payload.human_log or "").strip(),
                summary_kind,
                reconcile_key,
                now_text(),
            ),
        )
        conn.commit()
        return int(cur.lastrowid or 0)
    finally:
        conn.close()


def _list_auto_switch_session_summaries_for_owner(
    owner_username: str,
    *,
    device_id: str = "",
    alias: str = "",
    limit: int = 20,
) -> list[dict[str, Any]]:
    owner = str(owner_username or "").strip()
    if not owner:
        return []
    did = str(device_id or "").strip()
    alias_key = _normalize_alias_key(alias)
    lim = max(1, min(int(limit or 20), 100))
    where = ["owner_username = ?"]
    params: list[Any] = [owner]
    if did:
        where.append("device_id = ?")
        params.append(did)
    if alias_key:
        where.append("alias = ?")
        params.append(alias_key)
    params.append(lim)
    conn = _get_db()
    try:
        rows = conn.execute(
            f"""
            SELECT id, session_id, device_id, alias, bundle_revision, schedule_date,
                   summary_json, human_log, created_at
            FROM auto_switch_session_summaries
            WHERE {' AND '.join(where)}
            ORDER BY id DESC
            LIMIT ?
            """,
            tuple(params),
        ).fetchall()
    finally:
        conn.close()
    out: list[dict[str, Any]] = []
    for row in rows or []:
        summary: dict[str, Any] = {}
        try:
            parsed = json.loads(row[6] or "{}")
            if isinstance(parsed, dict):
                summary = parsed
        except Exception:
            summary = {}
        out.append(
            {
                "id": int(row[0]),
                "session_id": str(row[1] or ""),
                "device_id": str(row[2] or ""),
                "alias": str(row[3] or ""),
                "bundle_revision": int(row[4] or 0),
                "schedule_date": str(row[5] or ""),
                "human_log": str(row[7] or ""),
                "created_at": str(row[8] or ""),
                "stats": summary.get("stats") if isinstance(summary.get("stats"), dict) else {},
                "reconciliation": summary.get("reconciliation")
                if isinstance(summary.get("reconciliation"), dict)
                else {},
                "smart_template_debug": summary.get("smart_template_debug")
                if isinstance(summary.get("smart_template_debug"), dict)
                else {},
            }
        )
    return out


def _pending_profile_edit_usernames_from_ledger(entries: list[dict[str, Any]]) -> set[str]:
    pending: set[str] = set()
    for raw in entries or []:
        if not isinstance(raw, dict):
            continue
        op_type = str(raw.get("op_type") or "")
        if op_type == "auto_switch_profile_baseline":
            data = raw.get("report_data") if isinstance(raw.get("report_data"), dict) else {}
            raw_list = str(data.get("pending_profile_edit_usernames") or "").strip()
            if not raw_list:
                continue
            for part in raw_list.split(","):
                u = part.strip().lower()
                if u:
                    pending.add(u)
            continue
        if op_type == "profile_edit_required":
            data = raw.get("report_data") if isinstance(raw.get("report_data"), dict) else {}
            u = str(
                raw.get("username")
                or data.get("username")
                or data.get("account_username")
                or ""
            ).strip().lower()
            if u:
                pending.add(u)
    return pending


def _profile_edit_report_username(
    data: dict[str, Any],
    owner_username: str = "",
    alias: str = "",
) -> str:
    """从改资料设备回传 data 解析目标 TikTok 用户名；缺省时回退切号高亮账号。"""
    u = str(
        data.get("username")
        or data.get("account_username")
        or data.get("new_username")
        or ""
    ).strip().lstrip("@")
    if u:
        return u
    assigned = str(data.get("profile_edit_assigned_username") or data.get("old_username") or "").strip().lstrip("@")
    if assigned:
        return assigned
    owner = str(owner_username or "").strip()
    alias_key = str(alias or "").strip()
    if owner and alias_key:
        return _resolve_switch_highlight_tiktok_username(owner, alias_key)
    return ""


def _profile_edit_writeback_username_candidates(data: dict[str, Any]) -> list[str]:
    """改资料写回：按优先级尝试多个用户名（Bio 步骤可能已将账号 old→new）。"""
    candidates: list[str] = []
    seen: set[str] = set()

    def _add(raw: object) -> None:
        text = str(raw or "").strip().lstrip("@")
        key = text.lower()
        if text and key not in seen:
            seen.add(key)
            candidates.append(text)

    for key in (
        "username",
        "account_username",
        "new_username",
        "profile_edit_assigned_username",
        "old_username",
    ):
        _add(data.get(key))
    return candidates


def _profile_edit_identity_keys(data: dict[str, Any]) -> set[str]:
    """改资料对账：同一账号 old/new 用户名均视为同一身份键。"""
    keys: set[str] = set()
    for raw in _profile_edit_writeback_username_candidates(data):
        keys.add(raw.lower())
    return keys


def _mark_account_basic_profile_modified_by_row_index(
    owner_username: str,
    row_index: int,
    *,
    only_if_pending: bool = False,
) -> int:
    owner = str(owner_username or "").strip()
    ri = int(row_index)
    if not owner or ri < 0:
        return 0
    conn = _get_db()
    try:
        sql = """
            UPDATE account_editor_rows
            SET basic_profile_modified = '是', updated_at = ?
            WHERE owner_username = ? AND row_index = ?
        """
        if only_if_pending:
            sql += " AND TRIM(COALESCE(basic_profile_modified, '否')) <> '是'"
        cur = conn.execute(sql, (now_text(), owner, ri))
        conn.commit()
        return int(cur.rowcount or 0)
    finally:
        conn.close()


def _mark_account_basic_profile_modified_for_username(
    owner_username: str,
    username: str,
    *,
    only_if_pending: bool = False,
) -> int:
    """将指定账号 account_editor_rows.basic_profile_modified 标为「是」。"""
    owner = str(owner_username or "").strip()
    uname = str(username or "").strip().lstrip("@")
    if not owner or not uname:
        return 0
    conn = _get_db()
    try:
        sql = """
            UPDATE account_editor_rows
            SET basic_profile_modified = '是', updated_at = ?
            WHERE owner_username = ?
              AND LOWER(TRIM(COALESCE(username, ''))) = LOWER(?)
        """
        if only_if_pending:
            sql += " AND TRIM(COALESCE(basic_profile_modified, '否')) <> '是'"
        cur = conn.execute(sql, (now_text(), owner, uname))
        conn.commit()
        return int(cur.rowcount or 0)
    finally:
        conn.close()


def _mark_account_basic_profile_modified_for_profile_edit(
    owner_username: str,
    alias: str,
    data: dict[str, Any],
    *,
    only_if_pending: bool = False,
) -> dict[str, Any]:
    """
    改资料成功写回：优先 account_row_index（Bio 改名后仍稳定），再按 old/new 用户名候选匹配。
    """
    owner = str(owner_username or "").strip()
    if not owner or not isinstance(data, dict):
        return {"updated": 0, "username": ""}
    row_index_raw = data.get("account_row_index")
    if row_index_raw is not None:
        try:
            ri = int(row_index_raw)
            if ri >= 0:
                rowcount = _mark_account_basic_profile_modified_by_row_index(
                    owner, ri, only_if_pending=only_if_pending
                )
                if rowcount:
                    resolved = _profile_edit_report_username(data, owner, alias)
                    return {"updated": rowcount, "username": resolved or f"row_index:{ri}"}
        except (TypeError, ValueError):
            pass
    for uname in _profile_edit_writeback_username_candidates(data):
        rowcount = _mark_account_basic_profile_modified_for_username(
            owner, uname, only_if_pending=only_if_pending
        )
        if rowcount:
            return {"updated": rowcount, "username": uname}
    fb = _profile_edit_report_username({}, owner, alias)
    if fb:
        rowcount = _mark_account_basic_profile_modified_for_username(owner, fb, only_if_pending=only_if_pending)
        if rowcount:
            return {"updated": rowcount, "username": fb}
    return {"updated": 0, "username": ""}


def _apply_profile_edit_device_op_report(
    owner_username: str,
    alias: str,
    data: dict[str, Any] | None,
) -> dict[str, Any]:
    """设备 tiktok_edit_basic_profile 成功回传：按 username 写 basic_profile_modified（非 assigned_alias）。"""
    if not isinstance(data, dict):
        return {"updated": 0, "username": ""}
    if str(data.get("op_type") or "").strip() != "tiktok_edit_basic_profile":
        return {"updated": 0, "username": ""}
    if not bool(data.get("profile_edit_success")):
        return {"updated": 0, "username": ""}
    p1 = data.get("profile_edit_bio_after_part1_ok")
    p2 = data.get("profile_edit_bio_after_part2_ok")
    if p1 is not None and p2 is not None and not (bool(p1) and bool(p2)):
        return {"updated": 0, "username": ""}
    if bool(data.get("skip_account_db_update")):
        return {"updated": 0, "username": ""}
    owner_u = str(owner_username or "").strip()
    if not owner_u:
        return {"updated": 0, "username": ""}
    return _mark_account_basic_profile_modified_for_profile_edit(
        owner_u,
        alias,
        data,
        only_if_pending=False,
    )


def _profile_edit_success_entries_from_ledger(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for raw in entries or []:
        if not isinstance(raw, dict):
            continue
        if str(raw.get("op_type") or "") != "tiktok_edit_basic_profile":
            continue
        phase = str(raw.get("phase") or raw.get("status") or "").strip().lower()
        if phase != "success":
            continue
        data = raw.get("report_data") if isinstance(raw.get("report_data"), dict) else {}
        if bool(data.get("skip_account_db_update")):
            continue
        if data.get("profile_edit_success") is False:
            continue
        p1 = data.get("profile_edit_bio_after_part1_ok")
        p2 = data.get("profile_edit_bio_after_part2_ok")
        if p1 is not None and p2 is not None and not (bool(p1) and bool(p2)):
            continue
        merged: dict[str, Any] = dict(data)
        for key in ("username", "account_username", "profile_edit_assigned_username", "old_username", "new_username", "account_row_index", "row_index"):
            if key not in merged or not str(merged.get(key) or "").strip():
                val = raw.get(key)
                if val is not None and str(val).strip():
                    merged[key] = val
        out.append(merged)
    return out


def _profile_edit_success_usernames_from_ledger(entries: list[dict[str, Any]]) -> set[str]:
    done: set[str] = set()
    for data in _profile_edit_success_entries_from_ledger(entries):
        done.update(_profile_edit_identity_keys(data))
    return done


def _profile_edit_failed_usernames_from_ledger(entries: list[dict[str, Any]]) -> list[str]:
    failed: list[str] = []
    for raw in entries or []:
        if not isinstance(raw, dict):
            continue
        if str(raw.get("op_type") or "") != "tiktok_edit_basic_profile":
            continue
        phase = str(raw.get("phase") or raw.get("status") or "").strip().lower()
        if phase != "failed":
            continue
        data = raw.get("report_data") if isinstance(raw.get("report_data"), dict) else {}
        u = str(raw.get("username") or data.get("username") or data.get("account_username") or "").strip()
        if u:
            failed.append(u)
    return failed


def _apply_auto_switch_ledger_profile_edit_success_to_account_rows(
    owner_username: str,
    ledger: list[dict[str, Any]],
) -> dict[str, Any]:
    """ledger 显示改资料成功但 account_editor_rows 仍为否时补写 basic_profile_modified。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return {"fixed": 0, "notes": []}
    success_entries = _profile_edit_success_entries_from_ledger(ledger)
    if not success_entries:
        return {"fixed": 0, "notes": []}
    fixed = 0
    notes: list[str] = []
    for data in success_entries:
        result = _mark_account_basic_profile_modified_for_profile_edit(
            owner,
            "",
            data,
            only_if_pending=True,
        )
        if result.get("updated"):
            fixed += int(result["updated"])
            notes.append(f"补写 basic_profile_modified=是：{result.get('username') or 'unknown'}")
    return {"fixed": fixed, "notes": notes}


def _apply_auto_switch_ledger_account_login_writeback(
    owner_username: str,
    ledger: list[dict[str, Any]],
) -> dict[str, Any]:
    """ledger 记录 account_mark_login 成功时写回 account_editor_rows.is_login。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return {"applied": 0, "notes": []}
    applied = 0
    notes: list[str] = []
    seen_users: set[str] = set()
    for entry in ledger or []:
        if not isinstance(entry, dict):
            continue
        if str(entry.get("op_type") or "").strip() != "account_mark_login":
            continue
        phase = str(entry.get("phase") or entry.get("status") or "").strip().lower()
        if phase != "success":
            continue
        data = _sanitize_auto_switch_ledger_report_data(entry.get("report_data"))
        username = str(
            data.get("username") or data.get("account_username") or entry.get("username") or ""
        ).strip().lstrip("@")
        if not username:
            continue
        user_key = username.lower()
        if user_key in seen_users:
            continue
        seen_users.add(user_key)
        login_time = str(data.get("login_time") or entry.get("at") or "").strip() or now_text()
        conn = _get_db()
        try:
            cur = conn.execute(
                """
                UPDATE account_editor_rows
                SET is_login = 1, login_time = ?, updated_at = ?
                WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(?)
                """,
                (login_time, now_text(), owner, username),
            )
            conn.commit()
            if int(cur.rowcount or 0) > 0:
                applied += 1
                notes.append(f"对账写 is_login=是：{username}")
        finally:
            conn.close()
    if applied:
        notes.insert(0, f"登录状态写回 {applied} 条")
    return {"applied": applied, "notes": notes}


def _apply_auto_switch_ledger_username_update_writeback(
    owner_username: str,
    alias: str,
    ledger: list[dict[str, Any]],
) -> dict[str, Any]:
    """ledger 记录 account_username_update 时同步账号表与设备表 username。"""
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner:
        return {"applied": 0, "notes": []}
    applied = 0
    notes: list[str] = []
    for entry in ledger or []:
        if not isinstance(entry, dict):
            continue
        if str(entry.get("op_type") or "").strip() != "account_username_update":
            continue
        phase = str(entry.get("phase") or entry.get("status") or "").strip().lower()
        if phase != "success":
            continue
        data = _sanitize_auto_switch_ledger_report_data(entry.get("report_data"))
        old_username = str(data.get("old_username") or "").strip().lstrip("@")
        new_username = _normalize_mutual_clip_username(str(data.get("new_username") or "").strip())
        if not old_username or not new_username:
            continue
        if old_username.lower() == new_username.lower():
            continue
        conn = _get_db()
        try:
            row = conn.execute(
                """
                SELECT row_index, username FROM account_editor_rows
                WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(?)
                ORDER BY updated_at DESC, row_index DESC
                LIMIT 1
                """,
                (owner, old_username),
            ).fetchone()
            if not row:
                notes.append(f"用户名变更跳过（未找到旧账号）：{old_username} → {new_username}")
                continue
            row_index = int(row["row_index"] or 0)
            conflict = conn.execute(
                """
                SELECT 1 FROM account_editor_rows
                WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(?)
                  AND row_index <> ?
                LIMIT 1
                """,
                (owner, new_username, row_index),
            ).fetchone()
            if conflict:
                notes.append(f"用户名变更冲突（新名已占用）：{old_username} → {new_username}")
                continue
            device_alias = alias_key or _normalized_alias(alias)
            ts = now_text()
            conn.execute(
                """
                UPDATE account_editor_rows
                SET username = ?,
                    assigned_alias = CASE
                        WHEN TRIM(COALESCE(assigned_alias, '')) <> '' THEN assigned_alias
                        ELSE ?
                    END,
                    updated_at = ?
                WHERE owner_username = ? AND row_index = ?
                """,
                (new_username, device_alias, ts, owner, row_index),
            )
            dev_updated = _sync_device_editor_username_for_account(
                conn,
                owner,
                old_username,
                new_username,
                assigned_alias=device_alias,
            )
            conn.commit()
            applied += 1
            notes.append(
                f"用户名写回：{old_username} → {new_username}（设备表 {dev_updated} 行）"
            )
        finally:
            conn.close()
    if applied:
        notes.insert(0, f"用户名变更写回 {applied} 条")
    return {"applied": applied, "notes": notes}


def _collect_usernames_from_auto_switch_ledger(ledger: list[dict[str, Any]]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for entry in ledger or []:
        if not isinstance(entry, dict):
            continue
        candidates: list[str] = []
        entry_user = str(entry.get("username") or "").strip().lstrip("@")
        if entry_user:
            candidates.append(entry_user)
        data = entry.get("report_data")
        if isinstance(data, dict):
            for key in ("username", "account_username"):
                u = str(data.get(key) or "").strip().lstrip("@")
                if u:
                    candidates.append(u)
        for user in candidates:
            key = user.lower()
            if key not in seen:
                seen.add(key)
                ordered.append(user)
    return ordered


def _needs_profile_stats_video_count_fill(owner_username: str, username: str) -> bool:
    owner = str(owner_username or "").strip()
    uname = str(username or "").strip().lstrip("@")
    if not owner or not uname:
        return False
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT video_count FROM device_editor_rows
            WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))
            ORDER BY updated_at DESC, row_index DESC
            LIMIT 1
            """,
            (owner, uname),
        ).fetchone()
    finally:
        conn.close()
    if not row:
        return False
    raw_vc = _parse_video_count_raw(row["video_count"])
    return raw_vc is None or raw_vc == NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN


def _write_owner_device_profile_stats(
    owner_username: str,
    username: str,
    video_count: int | None,
    follower_count: int | None,
) -> bool:
    owner = str(owner_username or "").strip()
    uname = str(username or "").strip().lstrip("@")
    if not owner or not uname:
        return False
    if video_count is None and follower_count is None:
        return False
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT row_index, follower_count FROM device_editor_rows
            WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))
            ORDER BY updated_at DESC, row_index DESC
            LIMIT 1
            """,
            (owner, uname),
        ).fetchone()
        if not row:
            return False
        prev_fc = _parse_count_text_to_int(row["follower_count"])
        new_fc = follower_count if isinstance(follower_count, int) else None
        follower_growth = ""
        if isinstance(new_fc, int) and isinstance(prev_fc, int):
            follower_growth = str(max(new_fc - prev_fc, 0))
        elif isinstance(new_fc, int):
            follower_growth = "0"
        now = now_text()
        vc_text = None if video_count is None else str(video_count)
        fc_text = None if follower_count is None else str(follower_count)
        set_parts: list[str] = []
        params: list[object] = []
        if vc_text is not None:
            set_parts.append("video_count = ?")
            params.append(vc_text)
        if fc_text is not None:
            set_parts.append("follower_count = ?")
            params.append(fc_text)
            set_parts.append("follower_growth = ?")
            params.append(follower_growth)
        set_parts.append("updated_at = ?")
        params.append(now)
        params.extend([owner, int(row["row_index"])])
        conn.execute(
            f"""
            UPDATE device_editor_rows
            SET {', '.join(set_parts)}
            WHERE owner_username = ? AND row_index = ?
            """,
            tuple(params),
        )
        _sync_account_editor_stats(
            conn,
            uname,
            owner_username=owner,
            video_count=vc_text,
            follower_count=fc_text,
            follower_growth=follower_growth if fc_text is not None else None,
            now=now,
        )
        conn.commit()
        return True
    finally:
        conn.close()


def _apply_auto_switch_session_profile_stats_writeback(
    owner_username: str,
    ledger: list[dict[str, Any]],
) -> dict[str, Any]:
    """关会话：ledger 涉及账号若 video_count 仍为 -1/空，抓取 TikTok 主页补写统计。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return {"applied": 0, "notes": ["缺少 owner，跳过画像 video_count 补写"]}
    users = _collect_usernames_from_auto_switch_ledger(ledger)
    if not users:
        return {"applied": 0, "notes": ["ledger 无 username，跳过画像 video_count 补写"]}
    pending = [u for u in users if _needs_profile_stats_video_count_fill(owner, u)]
    if not pending:
        return {"applied": 0, "notes": ["ledger 账号 video_count 均已填，跳过画像补写"]}
    applied = 0
    notes: list[str] = []
    for username in pending:
        result = fetch_tiktok_profile_stats(username, write_back=False)
        if int(result.get("code", 1)) != 0:
            notes.append(f"画像补写失败 @{username}：{result.get('msg', 'unknown')}")
            continue
        extract = result.get("universal_extract") or {}
        vc = extract.get("video_count")
        fc = extract.get("follower_count")
        vc_int = int(vc) if isinstance(vc, int) else None
        fc_int = int(fc) if isinstance(fc, int) else None
        if vc_int is None and fc_int is None:
            notes.append(f"画像补写 @{username}：关键字段为空")
            continue
        if _write_owner_device_profile_stats(owner, username, vc_int, fc_int):
            applied += 1
            detail = f"video_count={vc_int}" if vc_int is not None else ""
            if fc_int is not None:
                detail = (detail + f" follower_count={fc_int}").strip()
            notes.append(f"画像补写 @{username} {detail}".strip())
        else:
            notes.append(f"画像补写 @{username}：DB 行未更新")
    if applied:
        notes.insert(0, f"画像 video_count 补写 {applied} 个账号")
    return {"applied": applied, "notes": notes}


def _increment_auto_switch_account_video_count(
    owner_username: str,
    username: str,
    *,
    delta: int = 1,
    row_index: int = -1,
) -> bool:
    """自治 ledger 回放：发作品成功后累加 video_count（-1 视为 0 后再加）。"""
    owner = str(owner_username or "").strip()
    uname = str(username or "").strip().lstrip("@")
    inc = max(1, int(delta or 1))
    if not owner or not uname:
        return False
    conn = _get_db()
    try:
        row = None
        if int(row_index) >= 0:
            row = conn.execute(
                """
                SELECT row_index, video_count FROM device_editor_rows
                WHERE owner_username = ? AND row_index = ?
                LIMIT 1
                """,
                (owner, int(row_index)),
            ).fetchone()
        if not row:
            row = conn.execute(
                """
                SELECT row_index, video_count FROM device_editor_rows
                WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(TRIM(?))
                ORDER BY updated_at DESC, row_index DESC
                LIMIT 1
                """,
                (owner, uname),
            ).fetchone()
        if not row:
            return False
        raw_vc = _parse_video_count_raw(row["video_count"])
        base = 0 if raw_vc == NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN else max(0, int(raw_vc or 0))
        new_vc = str(base + inc)
        ri = int(row["row_index"])
        now = now_text()
        conn.execute(
            """
            UPDATE device_editor_rows
            SET video_count = ?, updated_at = ?
            WHERE owner_username = ? AND row_index = ?
            """,
            (new_vc, now, owner, ri),
        )
        _sync_account_editor_stats(
            conn,
            uname,
            owner_username=owner,
            video_count=new_vc,
            now=now,
        )
        conn.commit()
        return True
    finally:
        conn.close()


def _ensure_auto_switch_publish_video_writeback_table(conn) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS auto_switch_publish_video_writeback (
            owner_username TEXT NOT NULL,
            session_id TEXT NOT NULL,
            dedupe_key TEXT NOT NULL,
            slot_key TEXT NOT NULL DEFAULT '',
            outcome_op_id TEXT NOT NULL DEFAULT '',
            username TEXT NOT NULL DEFAULT '',
            delta INTEGER NOT NULL DEFAULT 1,
            applied_at TEXT NOT NULL,
            PRIMARY KEY (owner_username, session_id, dedupe_key)
        )
        """
    )


def _publish_video_writeback_dedupe_key(slot_key: str, outcome_op_id: str) -> str:
    sk = str(slot_key or "").strip()
    if sk:
        return f"slot:{sk}"
    oid = str(outcome_op_id or "").strip()
    if oid:
        return f"op:{oid}"
    return ""


def _is_publish_video_writeback_applied(
    conn,
    owner_username: str,
    session_id: str,
    dedupe_key: str,
) -> bool:
    owner = str(owner_username or "").strip()
    sid = str(session_id or "").strip()
    key = str(dedupe_key or "").strip()
    if not owner or not sid or not key:
        return False
    row = conn.execute(
        """
        SELECT 1 FROM auto_switch_publish_video_writeback
        WHERE owner_username = ? AND session_id = ? AND dedupe_key = ?
        LIMIT 1
        """,
        (owner, sid, key),
    ).fetchone()
    return row is not None


def _mark_publish_video_writeback_applied(
    conn,
    owner_username: str,
    session_id: str,
    dedupe_key: str,
    *,
    slot_key: str = "",
    outcome_op_id: str = "",
    username: str = "",
    delta: int = 1,
) -> None:
    conn.execute(
        """
        INSERT OR IGNORE INTO auto_switch_publish_video_writeback
        (owner_username, session_id, dedupe_key, slot_key, outcome_op_id, username, delta, applied_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            str(owner_username or "").strip(),
            str(session_id or "").strip(),
            str(dedupe_key or "").strip(),
            str(slot_key or "").strip(),
            str(outcome_op_id or "").strip(),
            str(username or "").strip().lstrip("@"),
            max(1, int(delta or 1)),
            now_text(),
        ),
    )


def _apply_auto_switch_ledger_stats_writeback(
    owner_username: str,
    ledger: list[dict[str, Any]],
    *,
    session_id: str = "",
) -> dict[str, Any]:
    """自治 ledger 回放：新号 -1→0 毕业；发作品成功按 session+slot/op 幂等累加 video_count。"""
    owner = str(owner_username or "").strip()
    sid = str(session_id or "").strip()
    if not owner:
        return {"applied": 0, "notes": ["缺少 owner，跳过 video_count 写回"]}

    publish_count_by_slot: dict[str, int] = {}
    for entry in ledger or []:
        if not isinstance(entry, dict):
            continue
        if str(entry.get("op_type") or "") != "publish_post_start":
            continue
        data = _sanitize_auto_switch_ledger_report_data(entry.get("report_data"))
        slot_key = str(entry.get("slot_key") or data.get("auto_switch_slot_key") or "").strip()
        pc_raw = data.get("publish_count")
        try:
            pc = int(pc_raw) if pc_raw is not None else 0
        except (TypeError, ValueError):
            pc = 0
        if slot_key and pc > 0:
            publish_count_by_slot[slot_key] = pc

    applied = 0
    notes: list[str] = []
    skipped_publish = 0
    conn = _get_db()
    try:
        if sid:
            _ensure_auto_switch_publish_video_writeback_table(conn)
        for entry in ledger or []:
            if not isinstance(entry, dict):
                continue
            op_type = str(entry.get("op_type") or "").strip()
            phase = str(entry.get("phase") or entry.get("status") or "").strip().lower()
            if op_type == "auto_switch_video_count_graduate" and phase == "success":
                data = _sanitize_auto_switch_ledger_report_data(entry.get("report_data"))
                username = str(data.get("username") or entry.get("username") or "").strip().lstrip("@")
                try:
                    ri = int(data.get("row_index") if data.get("row_index") is not None else -1)
                except (TypeError, ValueError):
                    ri = -1
                if username or ri >= 0:
                    if _auto_switch_graduate_new_account_video_count(
                        owner,
                        {"username": username, "row_index": ri},
                    ):
                        applied += 1
                        notes.append(f"video_count -1→0：{username or f'row#{ri}'}")
                continue
            if op_type != "publish_post_outcome" or phase != "success":
                continue
            data = _sanitize_auto_switch_ledger_report_data(entry.get("report_data"))
            if data.get("publish_post_completed") is False:
                continue
            slot_key = str(entry.get("slot_key") or data.get("auto_switch_slot_key") or "").strip()
            outcome_op_id = str(entry.get("op_id") or "").strip()
            username = str(
                data.get("account_username")
                or data.get("username")
                or entry.get("username")
                or ""
            ).strip().lstrip("@")
            if not username:
                continue
            dedupe_key = _publish_video_writeback_dedupe_key(slot_key, outcome_op_id)
            if sid and dedupe_key and _is_publish_video_writeback_applied(conn, owner, sid, dedupe_key):
                skipped_publish += 1
                continue
            if sid and not dedupe_key:
                notes.append(f"发作品 outcome 缺少 slot/op_id，跳过 video_count 累加：{username}")
                continue
            try:
                actual_raw = data.get("publish_post_success_count")
                actual = int(actual_raw) if actual_raw is not None else 0
            except (TypeError, ValueError):
                actual = 0
            planned = publish_count_by_slot.get(slot_key, 0)
            delta = actual if actual > 0 else (planned if planned > 0 else 1)
            if planned > 0 and actual > planned:
                delta = planned
            if _increment_auto_switch_account_video_count(owner, username, delta=delta):
                if sid and dedupe_key:
                    _mark_publish_video_writeback_applied(
                        conn,
                        owner,
                        sid,
                        dedupe_key,
                        slot_key=slot_key,
                        outcome_op_id=outcome_op_id,
                        username=username,
                        delta=delta,
                    )
                    conn.commit()
                applied += 1
                notes.append(f"发作品成功 video_count +{delta}：{username}（{dedupe_key or '无幂等键'}）")
        if sid and skipped_publish:
            notes.append(f"发作品 video_count 已写回过 {skipped_publish} 条，跳过重复累加")
    finally:
        conn.close()

    if applied:
        notes.insert(0, f"video_count 写回 {applied} 条")
    return {"applied": applied, "notes": notes}


def _reconcile_auto_switch_session_summary(
    owner_username: str,
    alias: str,
    schedule_date: str,
    ledger: list[dict[str, Any]],
) -> dict[str, Any]:
    """根据 ledger 与当日计划生成简要对账说明。"""
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    date_key = str(schedule_date or "").strip() or _beijing_date_str()
    plan = _load_auto_switch_daily_plan(owner, alias_key, date_key) or {}
    slots = plan.get("slots") if isinstance(plan.get("slots"), list) else []

    def _ledger_items() -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        for raw in ledger or []:
            if isinstance(raw, dict):
                items.append(raw)
            elif hasattr(raw, "model_dump"):
                items.append(raw.model_dump())
        return items

    entries = _ledger_items()
    success = sum(1 for e in entries if str(e.get("status") or "").strip().lower() == "success")
    failed = sum(1 for e in entries if str(e.get("status") or "").strip().lower() == "failed")
    cut_ops = [
        e
        for e in entries
        if str(e.get("op_type") or "") in {"tiktok_backup_and_clear", "tiktok_clear", "tiktok_restore"}
    ]
    publish_ops = [e for e in entries if str(e.get("op_type") or "") == "publish_post_start"]
    publish_outcomes = [e for e in entries if str(e.get("op_type") or "") == "publish_post_outcome"]
    profile_edit_ops = [e for e in entries if str(e.get("op_type") or "") == "tiktok_edit_basic_profile"]
    profile_baseline_ops = [e for e in entries if str(e.get("op_type") or "") == "auto_switch_profile_baseline"]
    profile_required_ops = [e for e in entries if str(e.get("op_type") or "") == "profile_edit_required"]
    login_ops = [e for e in entries if str(e.get("op_type") or "") == "account_mark_login"]
    username_ops = [e for e in entries if str(e.get("op_type") or "") == "account_username_update"]
    failure_deferred_ops = [e for e in entries if str(e.get("op_type") or "") == "failure_report_deferred"]
    account_activity_ops = [
        e for e in entries if str(e.get("op_type") or "") == "auto_switch_account_activity"
    ]
    missing_backup_ops = [
        e for e in entries if str(e.get("op_type") or "") == "cut_switch_missing_backup"
    ]

    pending_profile_edits = _pending_profile_edit_usernames_from_ledger(entries)
    profile_edit_success_users = _profile_edit_success_usernames_from_ledger(entries)
    profile_edit_failed_users = _profile_edit_failed_usernames_from_ledger(entries)
    missing_profile_edit_usernames = sorted(pending_profile_edits - profile_edit_success_users)

    publish_done_slots: set[str] = set()
    for e in publish_outcomes:
        if str(e.get("status") or "").strip().lower() == "success":
            sk = str(e.get("slot_key") or "").strip()
            if sk:
                publish_done_slots.add(sk)

    missing_publish_slots: list[str] = []
    minute_now = _beijing_minute_of_day()
    for slot in slots:
        try:
            idx = int(slot.get("slot_index") or -1)
        except (TypeError, ValueError):
            continue
        if idx < 0:
            continue
        slot_key = f"{date_key}|{idx}"
        if slot_key in publish_done_slots:
            continue
        pub_min = slot.get("auto_publish_minute")
        if pub_min is None:
            continue
        try:
            pub_minute = int(pub_min)
        except (TypeError, ValueError):
            continue
        if minute_now < pub_minute:
            continue
        user = str(slot.get("username") or "").strip()
        missing_publish_slots.append(f"{slot_key} ({user or '-'})")

    notes: list[str] = []
    notes.append(f"ledger 共 {len(entries)} 条：成功 {success}，失败 {failed}")
    notes.append(f"切号相关 {len(cut_ops)} 条，发作品下发 {len(publish_ops)} 条，发作品结果 {len(publish_outcomes)} 条")
    notes.append(
        f"改资料待办 {len(profile_required_ops)} 条（汇总基线 {len(profile_baseline_ops)} 条），"
        f"改资料 OP {len(profile_edit_ops)} 条"
        f"（成功 {len(profile_edit_success_users)}，失败 {len(profile_edit_failed_users)}）"
    )
    notes.append(
        f"登录写回 ledger {len(login_ops)} 条，用户名变更 ledger {len(username_ops)} 条，"
        f"失败采集待传 {len(failure_deferred_ops)} 条"
    )
    if missing_backup_ops:
        notes.append(
            f"切号备份包本机缺失改自动登录 {len(missing_backup_ops)} 次"
        )
    if pending_profile_edits:
        notes.append(
            f"会话内轮到且待改资料 {len(pending_profile_edits)} 个："
            f"{', '.join(sorted(pending_profile_edits)[:8])}"
            + (f"…等 {len(pending_profile_edits)} 个" if len(pending_profile_edits) > 8 else "")
        )
    if missing_profile_edit_usernames:
        notes.append(
            f"待改资料但 ledger 未见成功记录：{', '.join(missing_profile_edit_usernames[:8])}"
            + (f"…另有 {len(missing_profile_edit_usernames) - 8} 个" if len(missing_profile_edit_usernames) > 8 else "")
        )
    elif pending_profile_edits:
        notes.append("改资料对账：bundle 待改账号均已有成功 ledger 记录")
    if account_activity_ops:
        notes.append(f"账号任务段审计 {len(account_activity_ops)} 条（模板与行为计数见 account_activity）")
    if profile_edit_failed_users:
        notes.append(f"改资料失败账号：{', '.join(profile_edit_failed_users[:8])}")
    if missing_publish_slots:
        notes.append(f"计划内未见成功发作品记录的时段：{', '.join(missing_publish_slots[:8])}")
        if len(missing_publish_slots) > 8:
            notes.append(f"…另有 {len(missing_publish_slots) - 8} 个时段")
    else:
        notes.append("计划时段发作品记录与 ledger 对账：未发现明显缺口（或当日无发作品计划）")

    return {
        "schedule_date": date_key,
        "plan_slot_count": len(slots),
        "ledger_total": len(entries),
        "ledger_success": success,
        "ledger_failed": failed,
        "cut_op_count": len(cut_ops),
        "publish_dispatch_count": len(publish_ops),
        "publish_outcome_count": len(publish_outcomes),
        "profile_baseline_count": len(profile_baseline_ops),
        "profile_required_count": len(profile_required_ops),
        "profile_edit_dispatch_count": len(profile_edit_ops),
        "profile_edit_success_count": len(profile_edit_success_users),
        "profile_edit_failed_count": len(profile_edit_failed_users),
        "pending_profile_edit_usernames": sorted(pending_profile_edits),
        "missing_profile_edit_usernames": missing_profile_edit_usernames,
        "missing_publish_slots": missing_publish_slots,
        "cut_switch_missing_backup_count": len(missing_backup_ops),
        "account_activity": _account_activity_audit_from_ledger(account_activity_ops),
        "notes": notes,
    }


def _account_activity_audit_from_ledger(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """从 ledger 提取账号×时段×模板×行为计数，供管理台对账展示。"""
    out: list[dict[str, Any]] = []
    for raw in entries or []:
        if not isinstance(raw, dict):
            continue
        data = _sanitize_auto_switch_ledger_report_data(raw.get("report_data"))
        if not data:
            data = _sanitize_auto_switch_ledger_report_data(raw)
        account = str(data.get("account_username") or raw.get("username") or "").strip()
        if not account:
            continue
        tpl = str(data.get("active_template") or "").strip()
        slot_key = str(data.get("auto_switch_slot_key") or raw.get("slot_key") or "").strip()
        started = str(data.get("segment_started_at") or "").strip()
        ended = str(data.get("segment_ended_at") or raw.get("at") or "").strip()
        reason = str(data.get("segment_reason") or "").strip()
        run_mode = str(data.get("run_mode") or "").strip()
        config_json = str(data.get("template_config_json") or "").strip()
        counts_json = str(data.get("action_counts_json") or "").strip()
        template_config: dict[str, Any] = {}
        action_counts: dict[str, Any] = {}
        if config_json:
            try:
                parsed_cfg = json.loads(config_json)
                if isinstance(parsed_cfg, dict):
                    template_config = parsed_cfg
            except Exception:
                template_config = {}
        if counts_json:
            try:
                parsed_cnt = json.loads(counts_json)
                if isinstance(parsed_cnt, dict):
                    action_counts = parsed_cnt
            except Exception:
                action_counts = {}
        out.append(
            {
                "account": account,
                "slot_key": slot_key,
                "active_template": tpl,
                "run_mode": run_mode,
                "segment_started_at": started,
                "segment_ended_at": ended,
                "segment_reason": reason,
                "template_config": template_config,
                "action_counts": action_counts,
            }
        )
    out.sort(key=lambda x: (str(x.get("segment_started_at") or ""), str(x.get("account") or "")))
    return out


_AUTO_SWITCH_LEDGER_REPORT_KEYS = frozenset(
    {
        "op_type",
        "backup_skipped",
        "backup_record_id",
        "username",
        "mobile_alias",
        "assigned_alias",
        "backup_path",
        "backup_name",
        "previous_backup_path",
        "old_backup_path",
        "video_count",
        "follower_count",
        "new_follower_count",
        "cut_switch_after_backup",
        "cut_switch_target_row_index",
        "restore_highlight_row_index",
        "skip_pm_clear",
        "source",
        "auto_switch_slot_key",
        "account_username",
        "profile_edit_assigned_username",
        "account_row_index",
        "profile_edit_trigger",
        "profile_edit_success",
        "basic_profile_modified_before",
        "pending_profile_edit_usernames",
        "profile_edit_bio_after_part1_ok",
        "profile_edit_bio_after_part2_ok",
        "skip_account_db_update",
        "publish_count",
        "row_index",
        "publish_post_completed",
        "publish_post_success_count",
        "publish_post_resumable",
        "publish_post_resume_next_index",
        "login_time",
        "old_username",
        "new_username",
        "failure_business",
        "failure_error_type",
        "failure_summary",
        "failure_ocr_related",
        "active_template",
        "segment_started_at",
        "segment_ended_at",
        "segment_reason",
        "run_mode",
        "template_config_json",
        "action_counts_json",
    }
)


def _sanitize_auto_switch_ledger_report_data(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        return {}
    out: dict[str, Any] = {}
    for key in _AUTO_SWITCH_LEDGER_REPORT_KEYS:
        if key not in raw:
            continue
        val = raw[key]
        if val is None:
            continue
        if isinstance(val, str) and not val.strip():
            continue
        out[key] = val
    return out


def _apply_auto_switch_ledger_backup_success_to_editor_rows(
    owner_username: str,
    fallback_alias: str,
    data: dict[str, Any],
) -> None:
    """自治 ledger 回放：备份成功时写回 device_editor_rows（不复删设备端已处理的旧备份文件）。"""
    backup_record_id = data.get("backup_record_id")
    username = str(data.get("username") or "").strip().lstrip("@")
    mobile_alias = str(data.get("mobile_alias") or fallback_alias or "").strip()
    backup_path = str(data.get("backup_path") or "").strip()
    backup_name = str(data.get("backup_name") or "").strip() or None
    if username and backup_name and not _backup_name_contains_username(backup_name, username):
        print(
            f"[{now_text()}] auto_switch.ledger_writeback | backup_name 未包含 username "
            f"| user={username} name={backup_name}"
        )
    if backup_record_id:
        try:
            rid = int(backup_record_id)
        except (TypeError, ValueError):
            rid = 0
        if rid > 0:
            conn = _get_db()
            try:
                ts = now_text()
                conn.execute(
                    """
                    UPDATE device_editor_rows
                    SET backup_path = ?, backup_name = ?, backup_time = ?, backup_status = ?, updated_at = ?
                    WHERE owner_username = ? AND row_index = ?
                    """,
                    (backup_path, backup_name or "", ts, "已备份", ts, owner_username, rid),
                )
                conn.commit()
            finally:
                conn.close()
    elif username and mobile_alias and backup_path:
        _patch_backup_record_device_result(
            username,
            mobile_alias,
            backup_path=backup_path,
            backup_name=backup_name,
            video_count=None,
            follower_count=None,
            new_follower_count=None,
        )

    video_count = data.get("video_count")
    follower_count = data.get("follower_count")
    new_follower_count = data.get("new_follower_count")
    vc = int(video_count) if isinstance(video_count, int) else None
    fc = int(follower_count) if isinstance(follower_count, int) else None
    nfc = int(new_follower_count) if isinstance(new_follower_count, int) else None
    if not backup_path or not mobile_alias:
        return
    if not username:
        latest = _get_latest_backup_record_for_alias(mobile_alias)
        if latest:
            username = str(latest.get("username") or "").strip().lstrip("@")
    rec = None
    if username and mobile_alias:
        rec = _get_backup_record_by_key(username, mobile_alias)
    if not rec and mobile_alias:
        rec = _get_latest_backup_record_for_alias(mobile_alias)
        if rec:
            username = str(rec.get("username") or "").strip().lstrip("@")
    if not username or not rec:
        return
    db_alias = str(rec.get("assigned_alias") or rec.get("device_alias") or "").strip()
    key_alias = db_alias if db_alias else mobile_alias
    patched = _patch_backup_record_device_result(
        username,
        key_alias,
        backup_path=backup_path,
        backup_name=backup_name,
        video_count=vc,
        follower_count=fc,
        new_follower_count=nfc,
    )
    if patched == 0 and mobile_alias:
        _patch_latest_backup_row_by_alias(
            mobile_alias,
            backup_path=backup_path,
            backup_name=backup_name,
            video_count=vc,
            follower_count=fc,
            new_follower_count=nfc,
        )


def _replay_auto_switch_ledger_to_device_editor_rows(
    owner_username: str,
    alias: str,
    ledger: list[dict[str, Any]],
) -> dict[str, Any]:
    """
    按 ledger 时间顺序回放自治期间 OP 结果，写回 device_editor_rows。
    备份/env 状态与 device_ops/report 一致；切号高亮由时段计划派生，不在此写库。
    """
    owner = str(owner_username or "").strip()
    alias_key = _normalize_alias_key(alias)
    if not owner or not alias_key:
        return {
            "applied_effects": 0,
            "skipped_without_data": 0,
            "writeback_notes": ["缺少 owner 或 alias，已跳过档案写回"],
        }

    applied = 0
    skipped = 0
    notes: list[str] = []

    for entry in ledger or []:
        if not isinstance(entry, dict):
            continue
        eligible, skip_note = _auto_switch_ledger_entry_writeback_eligible(entry)
        if not eligible:
            if skip_note:
                notes.append(skip_note)
            continue
        status = str(entry.get("status") or "").strip().lower()
        phase = str(entry.get("phase") or "").strip().lower()
        if phase in ("success", "failed"):
            status = phase
        data = _sanitize_auto_switch_ledger_report_data(entry.get("report_data"))
        op_type = str(data.get("op_type") or entry.get("op_type") or "").strip()
        if not data:
            if op_type in _AUTO_SWITCH_LEDGER_WRITEBACK_OP_TYPES:
                skipped += 1
                op_id = str(entry.get("op_id") or "").strip()
                notes.append(
                    f"跳过无 report_data 的 {op_type} 条目"
                    + (f"（op_id={op_id}）" if op_id else "")
                )
            continue

        backup_skipped = bool(data.get("backup_skipped"))
        if status == "success":
            if op_type == "tiktok_backup_and_clear" and not backup_skipped:
                _apply_auto_switch_ledger_backup_success_to_editor_rows(owner, alias_key, data)
                applied += 1
            if op_type in ("tiktok_backup_and_clear", "tiktok_clear"):
                _device_ops_apply_cut_switch_after_backup(owner, data)
                applied += 1
            elif op_type == "tiktok_restore":
                _device_ops_apply_cut_switch_after_restore(owner, data)
                applied += 1
        else:
            if op_type == "tiktok_restore":
                _device_ops_apply_restore_failure(owner, data)
                applied += 1
            if op_type in ("tiktok_backup_and_clear", "tiktok_restore", "tiktok_clear"):
                _device_ops_rollback_cut_switch_highlight(owner, data)
                applied += 1

    conn = _get_db()
    try:
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
    finally:
        conn.close()

    if applied:
        notes.append(f"已根据 ledger 回放写回 device_editor_rows（生效 {applied} 次）")
    elif not skipped:
        notes.append("ledger 中无可写回的切号/备份档案条目")
    if skipped:
        notes.append(f"有 {skipped} 条切号相关 ledger 缺少 report_data，未写回（需新版设备端）")

    return {
        "applied_effects": applied,
        "skipped_without_data": skipped,
        "writeback_notes": notes,
    }


def _apply_auto_switch_ledger_writeback_bundle(
    owner_username: str,
    alias: str,
    ledger: list[dict[str, Any]],
    summary_kind: str,
    session_id: str = "",
) -> dict[str, Any]:
    """会话/每日对账：回放 ledger 写回 device_editor_rows 与统计字段。"""
    writeback = _replay_auto_switch_ledger_to_device_editor_rows(owner_username, alias, ledger)
    writeback["duplicate"] = False
    login_writeback = _apply_auto_switch_ledger_account_login_writeback(owner_username, ledger)
    writeback["login_account_writeback"] = login_writeback
    username_writeback = _apply_auto_switch_ledger_username_update_writeback(owner_username, alias, ledger)
    writeback["username_account_writeback"] = username_writeback
    stats_writeback = _apply_auto_switch_ledger_stats_writeback(
        owner_username,
        ledger,
        session_id=session_id,
    )
    writeback["stats_writeback"] = stats_writeback
    profile_writeback = _apply_auto_switch_ledger_profile_edit_success_to_account_rows(owner_username, ledger)
    writeback["profile_account_writeback"] = profile_writeback
    profile_stats_writeback = _apply_auto_switch_session_profile_stats_writeback(owner_username, ledger)
    writeback["profile_stats_writeback"] = profile_stats_writeback
    for wb in (login_writeback, username_writeback, stats_writeback, profile_writeback, profile_stats_writeback):
        for note in wb.get("notes") or []:
            if isinstance(note, str) and note.strip():
                writeback.setdefault("writeback_notes", []).append(note.strip())
    return writeback


@app.post("/api/auto_switch/session_summary")
def post_auto_switch_session_summary(payload: AutoSwitchSessionSummaryRequest):
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error
    owner = str(device.owner_username or "").strip()
    alias_key = _normalize_alias_key(payload.alias or device.alias)
    ledger_dicts = [e.model_dump() for e in payload.ledger]
    summary_kind, reconcile_key = _resolve_auto_switch_reconcile_meta(payload)
    reconciliation = _reconcile_auto_switch_session_summary(
        owner,
        alias_key,
        str(payload.schedule_date or "").strip(),
        ledger_dicts,
    )
    existing_summary_id = _find_auto_switch_session_summary_id(
        owner,
        payload.device_id,
        alias_key,
        str(payload.session_id or "").strip(),
        reconcile_key,
    )
    is_duplicate = existing_summary_id > 0
    if is_duplicate:
        dup_note = (
            f"同 session 每日对账已处理（{reconcile_key}），跳过写回（幂等）"
            if summary_kind == "daily"
            else "同 session 总结重传，跳过 device_editor_rows 写回（幂等）"
        )
        writeback = {
            "applied_effects": 0,
            "skipped_without_data": 0,
            "writeback_notes": [dup_note],
            "duplicate": True,
            "summary_kind": summary_kind,
            "reconcile_key": reconcile_key,
        }
    else:
        writeback = _apply_auto_switch_ledger_writeback_bundle(
            owner,
            alias_key,
            ledger_dicts,
            summary_kind,
            str(payload.session_id or "").strip(),
        )
        for note in writeback.get("writeback_notes") or []:
            if isinstance(note, str) and note.strip():
                reconciliation.setdefault("notes", []).append(note.strip())
    reconciliation["editor_writeback"] = writeback
    reconciliation["duplicate_summary"] = is_duplicate
    reconciliation["summary_kind"] = summary_kind
    reconciliation["reconcile_key"] = reconcile_key
    for note in writeback.get("writeback_notes") or []:
        if isinstance(note, str) and note.strip():
            reconciliation.setdefault("notes", []).append(note.strip())
    row_id = _save_auto_switch_session_summary(
        owner,
        payload.device_id,
        alias_key,
        payload,
        reconciliation,
    )
    if not row_id:
        return {"code": 1, "msg": "保存会话总结失败"}
    enter_sid = str(payload.session_id or "").strip()
    if enter_sid and not is_duplicate and summary_kind == "final":
        conn = _get_db()
        try:
            conn.execute(
                """
                UPDATE auto_switch_sessions SET closed_at = ?
                WHERE session_id = ? AND closed_at IS NULL
                """,
                (now_text(), enter_sid),
            )
            conn.commit()
        finally:
            conn.close()
    _append_device_log(
        payload.device_id,
        f"[{now_text()}] 自动切号 | {'每日对账' if summary_kind == 'daily' else '会话总结'}已上报 "
        f"session={payload.session_id} reconcile={reconcile_key} ledger={len(payload.ledger)}"
        + ("（重传更新）" if is_duplicate else ""),
    )
    _auto_switch_log(
        f"{'每日对账' if summary_kind == 'daily' else '会话总结'}已入库 | owner={owner} "
        f"device={payload.device_id} alias={alias_key} session={payload.session_id} "
        f"reconcile={reconcile_key} ledger={len(payload.ledger)} "
        f"writeback={writeback.get('applied_effects', 0)} duplicate={is_duplicate}"
    )
    return {
        "code": 0,
        "msg": "会话总结已保存" if not is_duplicate else "会话总结已更新（重传）",
        "summary_id": row_id,
        "duplicate": is_duplicate,
        "reconciliation": reconciliation,
    }


@app.get("/api/admin/auto_switch/session_summaries")
def get_auto_switch_session_summaries(
    device_id: str = "",
    alias: str = "",
    limit: int = 20,
    authorization: str | None = Header(default=None),
):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    owner = str(session.get("username") or "").strip()
    allowed = set(_session_device_ids(session))
    did = str(device_id or "").strip()
    alias_key = _normalize_alias_key(alias)
    if did and did not in allowed:
        return {"code": 1, "msg": "无权查看该设备"}
    items = _list_auto_switch_session_summaries_for_owner(
        owner,
        device_id=did,
        alias=alias_key,
        limit=limit,
    )
    return {"code": 0, "data": {"items": items, "total": len(items), "alias": alias_key}}


@app.get("/api/admin/auto_switch/daily_plans")
def get_auto_switch_daily_plans(
    schedule_date: str = "",
    authorization: str | None = Header(default=None),
):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    owner = str(session.get("username") or "").strip()
    date_key = str(schedule_date or "").strip() or _beijing_date_str()
    # 管理台只读展示已有计划；生成/补齐周计划由设备拉取或切号流程触发，避免每次翻页/搜索写库抢锁。
    view = _auto_switch_plans_view_for_owner(owner, date_key, ensure=False)
    return {"code": 0, "data": view}


@app.get("/api/admin/tasks/scheduler/config")
def get_scheduler_config(authorization: str | None = Header(default=None)):
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    return {"code": 0, "data": _get_scheduler_snapshot()}


@app.post("/api/admin/tasks/scheduler/config")
def config_scheduler_tasks(payload: SchedulerConfigRequest):
    with store.lock:
        store.scheduler_enabled = bool(payload.enabled)
    persist_runtime_state()
    return {"code": 0, "msg": "定时任务总开关已更新", "scheduler_enabled": store.scheduler_enabled}


@app.post("/api/admin/tasks/scheduler/task_config")
def config_scheduler_task(payload: SchedulerTaskConfigRequest):
    key = str(payload.task_key or "").strip()
    if key not in SCHEDULER_TASK_DEFS:
        return {"code": 1, "msg": f"未知任务: {key}"}
    with store.lock:
        if payload.enabled is not None:
            store.scheduler_task_enabled[key] = bool(payload.enabled)
        if payload.interval_sec is not None:
            store.scheduler_task_interval_sec[key] = int(payload.interval_sec)
    persist_runtime_state()
    return {"code": 0, "msg": "任务配置已更新", "task_key": key, "task": _get_scheduler_snapshot()["tasks"][key]}


@app.post("/api/admin/tasks/scheduler/start")
def start_scheduler_tasks():
    with store.lock:
        store.scheduler_enabled = True
        store.scheduler_stop_event.clear()
    persist_runtime_state()
    return {"code": 0, "msg": "定时任务已开启", **_get_scheduler_snapshot()}


@app.post("/api/admin/tasks/scheduler/stop")
def stop_scheduler_tasks():
    with store.lock:
        store.scheduler_enabled = False
        store.scheduler_stop_event.set()
    _stop_all_task_processes()
    persist_runtime_state()
    return {"code": 0, "msg": "定时任务已停止（并终止正在运行任务）", **_get_scheduler_snapshot()}


@app.post("/api/admin/tasks/stop_by_prefix")
def stop_tasks_by_prefix(payload: StopTaskByPrefixRequest):
    prefix = (payload.prefix or "").strip()
    if not prefix:
        return {"code": 1, "msg": "prefix不能为空"}
    stopped = _stop_task_processes_by_prefix(prefix)
    return {"code": 0, "msg": f"已停止前缀任务: {prefix}", "stopped_count": stopped}


@app.get("/api/admin/tasks/status")
def task_status():
    _cleanup_finished_tasks()
    snapshot = _get_scheduler_snapshot()
    with store.lock:
        running = []
        for name, proc in store.task_processes.items():
            running.append({"name": name, "pid": proc.pid, "running": proc.poll() is None})
        _trim_task_log_keys_unlocked()
        task_logs = {k: v[-120:] for k, v in store.task_logs.items()}
    with store.lock:
        auto_switch_enabled = _any_device_auto_switch_enabled()
        auto_switch_device_count = sum(1 for d in store.devices.values() if d.auto_switch_enabled)
        backup_stats_full_query_enabled = bool(store.backup_stats_full_query_enabled)
        backup_stats_full_query_last_run_date = str(store.backup_stats_full_query_last_run_date or "")
        backup_stats_full_query_in_flight = bool(store.backup_stats_full_query_in_flight)
    return {
        "code": 0,
        "scheduler_enabled": snapshot["scheduler_enabled"],
        "scheduler_tasks": snapshot["tasks"],
        "running_tasks": running,
        "task_logs": task_logs,
        "auto_search_schedule": "由各任务独立间隔控制",
        "auto_switch_enabled": auto_switch_enabled,
        "auto_switch_beijing_date": _beijing_date_str(),
        "auto_switch_beijing_time": _beijing_now().strftime("%H:%M:%S"),
        "backup_stats_full_query_enabled": (
            backup_stats_full_query_enabled
            if _backup_stats_full_query_schedule_available()
            else False
        ),
        "backup_stats_full_query_schedule_available": _backup_stats_full_query_schedule_available(),
        "backup_stats_full_query_last_run_date": backup_stats_full_query_last_run_date,
        "backup_stats_full_query_in_flight": backup_stats_full_query_in_flight,
        "backup_stats_full_query_schedule": (
            "每天北京时间 00:00"
            if _backup_stats_full_query_schedule_available()
            else "暂时不可用"
        ),
    }


@app.get("/api/admin/table_page")
def admin_table_page(table: str, page: int = 1, page_size: int = 100, search_field: str = "", search_keyword: str = ""):
    allowed = {
        "tiktok_roots": ["root_username", "roots_type", "discovered_at", "last_use_follower_count", "usage_count"],
        "follower_queue": ["username", "root_username", "created_at", "consume_status", "consumed_at", "consumed_by", "consumed_mode", "following_access_status", "following_fetch_success", "mobile_push_status"],
        "mobile_pending_usernames": ["id", "username", "created_at", "consume_status", "consumed_at"],
        "mutual_click_copied_usernames": ["username", "created_at", "usage_count"],
        "live_customer": ["username", "usage_count", "created_at", "last_visit_at"],
        "users": ["id", "username", "remark", "created_at", "last_use_at", "last_use_follower_count"],
    }
    if table not in allowed:
        return {"code": 1, "msg": f"不支持的表: {table}"}

    safe_page = max(1, int(page))
    safe_size = max(1, min(int(page_size), 1000))
    offset = (safe_page - 1) * safe_size
    safe_search_field = (search_field or "").strip()
    safe_search_keyword = (search_keyword or "").strip()

    order_map = {
        "tiktok_roots": "ORDER BY discovered_at DESC, root_username ASC",
        "follower_queue": "ORDER BY created_at DESC, username DESC",
        "mobile_pending_usernames": "ORDER BY id DESC",
        "mutual_click_copied_usernames": "ORDER BY datetime(created_at) DESC, username ASC",
        "live_customer": "ORDER BY created_at DESC, username ASC",
        "users": "ORDER BY id DESC",
    }

    where_sql = ""
    params: list[Any] = []
    if safe_search_keyword:
        pattern = _case_insensitive_like_pattern(safe_search_keyword)
        if safe_search_field:
            if safe_search_field not in allowed[table]:
                return {"code": 1, "msg": f"不支持的搜索字段: {safe_search_field}"}
            where_sql = f"WHERE LOWER(CAST({safe_search_field} AS TEXT)) LIKE ?"
            params.append(pattern)
        else:
            like_clauses = [f"LOWER(CAST({field} AS TEXT)) LIKE ?" for field in allowed[table]]
            where_sql = "WHERE " + " OR ".join(like_clauses)
            params.extend([pattern] * len(allowed[table]))

    conn = _get_db()
    try:
        total = conn.execute(f"SELECT COUNT(1) FROM {table} {where_sql}", params).fetchone()[0]
        rows = conn.execute(
            f"SELECT * FROM {table} {where_sql} {order_map[table]} LIMIT ? OFFSET ?",
            (*params, safe_size, offset),
        ).fetchall()
        return {
            "code": 0,
            "table": table,
            "fields": allowed[table],
            "search_field": safe_search_field,
            "search_keyword": safe_search_keyword,
            "page": safe_page,
            "page_size": safe_size,
            "total": total,
            "items": [dict(r) for r in rows],
        }
    finally:
        conn.close()


def _load_overview_queue_stats_from_db(*, preview_limit: int = 200) -> dict[str, Any]:
    """读取 overview 队列 COUNT 与待下发预览（不持有 store.lock）。"""
    conn = _get_db()
    try:
        follow_total = conn.execute("SELECT COUNT(1) FROM follower_queue").fetchone()[0]
        follow_unprocessed = conn.execute(
            "SELECT COUNT(1) FROM follower_queue WHERE COALESCE(following_fetch_success, 0) = 0"
        ).fetchone()[0]
        follow_done = conn.execute(
            "SELECT COUNT(1) FROM follower_queue WHERE COALESCE(following_fetch_success, 0) = 1"
        ).fetchone()[0]
        # 临时：待下发数字 = tiktok_roots 全量；恢复队列方案时改回 mobile_pending 查询（见下方注释）。
        pending_count = conn.execute("SELECT COUNT(1) FROM tiktok_roots").fetchone()[0]
        pending_preview = [
            str(r[0])
            for r in conn.execute(
                "SELECT root_username FROM tiktok_roots ORDER BY root_username ASC LIMIT ?",
                (preview_limit,),
            ).fetchall()
        ]
        # pending_count = conn.execute(
        #     "SELECT COUNT(1) FROM mobile_pending_usernames WHERE consume_status = 'pending'"
        # ).fetchone()[0]
        # pending_preview = [
        #     str(r[0])
        #     for r in conn.execute(
        #         "SELECT username FROM mobile_pending_usernames WHERE consume_status = 'pending' ORDER BY created_at, id LIMIT ?",
        #         (preview_limit,),
        #     ).fetchall()
        # ]
        live_count = conn.execute("SELECT COUNT(1) FROM live_customer").fetchone()[0]
        return {
            "follow_total": int(follow_total or 0),
            "follow_unprocessed": int(follow_unprocessed or 0),
            "follow_done": int(follow_done or 0),
            "pending_count": int(pending_count or 0),
            "pending_preview": pending_preview,
            "live_count": int(live_count or 0),
            "mobile_pending_total": int(pending_count or 0),
            "queue_stats": {
                "follower": {
                    "pending": int(follow_unprocessed or 0),
                    "processing": 0,
                    "done": int(follow_done or 0),
                    "failed": 0,
                },
                "mobile_pending": int(pending_count or 0),
                "live_customer": int(live_count or 0),
            },
            "mobile_pending_preview": pending_preview,
        }
    finally:
        conn.close()


def _invalidate_overview_queue_stats_cache() -> None:
    with _overview_queue_stats_cache_lock:
        _overview_queue_stats_cache["payload"] = None
        _overview_queue_stats_cache["expires_at"] = 0.0
    _mark_queue_stats_dirty()


def _overview_queue_stats_cached(*, preview_limit: int = 200) -> dict[str, Any]:
    now = time.monotonic()
    with _overview_queue_stats_cache_lock:
        expires = float(_overview_queue_stats_cache.get("expires_at") or 0)
        payload = _overview_queue_stats_cache.get("payload")
        if isinstance(payload, dict) and now < expires:
            return dict(payload)
    fresh = _load_overview_queue_stats_from_db(preview_limit=preview_limit)
    with _overview_queue_stats_cache_lock:
        _overview_queue_stats_cache["payload"] = fresh
        _overview_queue_stats_cache["expires_at"] = now + _OVERVIEW_QUEUE_STATS_CACHE_TTL_SEC
    return dict(fresh)


def _overview_queue_payload_from_db(*, preview_limit: int = 200) -> dict[str, Any]:
    """不持有 store.lock：概览降级时仍返回真实队列计数与待下发预览（避免误显示为 0）。"""
    cached = _overview_queue_stats_cached(preview_limit=preview_limit)
    return {
        "mobile_pending_total": cached["mobile_pending_total"],
        "queue_stats": dict(cached["queue_stats"]),
        "mobile_pending_preview": list(cached["mobile_pending_preview"]),
    }


@app.get("/api/admin/overview")
def admin_overview(
    page: int = 1,
    page_size: int = 20,
    device_group: str = "",
    authorization: str | None = Header(default=None),
):
    started = time.perf_counter()
    print(f"[{now_text()}] overview.enter")

    def _mark(step: str, t0: float) -> float:
        t1 = time.perf_counter()
        print(f"[{now_text()}] overview.step | {step} | {(t1 - t0) * 1000:.1f}ms")
        return t1

    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        print(f"[{now_text()}] overview.step | auth_failed | {err}")
        return err
    t_auth = _mark("auth", started)

    try:
        auto_start_enabled = _user_auto_start_enabled(session["username"])
    except Exception as exc:
        auto_start_enabled = False
        print(f"[{now_text()}] overview.step | auto_start_load_failed | {exc}")
    t_auto_start = _mark("auto_start", t_auth)

    preview_limit = 200
    safe_page = max(1, int(page or 1))
    safe_size = max(1, min(int(page_size or 50), 500))
    group_filter = str(device_group or "").strip()
    snapshot_started = time.perf_counter()
    devices, devices_total = _load_visible_devices_page_from_db(
        session,
        device_group=group_filter,
        page=safe_page,
        page_size=safe_size,
    )
    device_stats = _device_stats_from_db(session, device_group=group_filter)
    config_snapshot = dict(store.config)
    run_mode_snapshot = _user_run_mode(session["username"])
    queue_snapshot = _overview_queue_stats_cached(preview_limit=preview_limit)
    follow_total = queue_snapshot["follow_total"]
    follow_unprocessed = queue_snapshot["follow_unprocessed"]
    follow_done = queue_snapshot["follow_done"]
    pending_count = queue_snapshot["pending_count"]
    pending_preview = queue_snapshot["pending_preview"]
    live_count = queue_snapshot["live_count"]
    t_db = time.perf_counter()
    print(
        f"[{now_text()}] overview.step | db_snapshot | {(t_db - snapshot_started) * 1000:.1f}ms "
        f"devices={len(devices)} follower_total={follow_total} mobile_pending={pending_count} live_customer={live_count}"
    )

    payload = {
        "code": 0,
        "devices": devices,
        "devices_total": devices_total,
        "device_stats": device_stats,
        "page": safe_page,
        "page_size": safe_size,
        "device_group": group_filter,
        "device_groups": _list_distinct_device_groups(session),
        "list_preview_limit": preview_limit,
        "config": config_snapshot,
        "run_mode": run_mode_snapshot,
        "auto_start_enabled": auto_start_enabled,
        "queue_stats": {
            "follower": {
                "pending": follow_unprocessed,
                "processing": 0,
                "done": follow_done,
                "failed": 0,
            },
            "mobile_pending": pending_count,
            "live_customer": live_count,
        },
        "mobile_pending_preview": pending_preview,
        "mobile_pending_total": pending_count,
    }
    _overview_fallback_snapshot.update(
        {
            "devices": devices,
            "list_preview_limit": preview_limit,
            "config": config_snapshot,
            "run_mode": run_mode_snapshot,
            "mobile_pending_preview": pending_preview,
            "mobile_pending_total": pending_count,
        }
    )
    return payload


@app.get("/api/admin/logs_snapshot")
def admin_logs_snapshot(
    page: int = 1,
    page_size: int = 20,
    device_group: str = "",
    authorization: str | None = Header(default=None),
):
    """轻量轮询：仅当前页设备状态与任务日志；设备执行日志请用 /api/admin/device_logs 按需拉取。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    auto_start_enabled = _user_auto_start_enabled(session["username"])
    group_filter = str(device_group or "").strip()
    safe_page = max(1, int(page or 1))
    safe_size = max(1, min(int(page_size or 50), 500))

    devices, devices_total = _load_visible_devices_page_from_db(
        session,
        device_group=group_filter,
        page=safe_page,
        page_size=safe_size,
    )
    with store.lock:
        _trim_task_log_keys_unlocked()
        task_logs = {k: v[-120:] for k, v in store.task_logs.items()}
    mode = _user_run_mode(session["username"])
    return {
        "code": 0,
        "devices": devices,
        "devices_total": devices_total,
        "page": safe_page,
        "page_size": safe_size,
        "device_group": group_filter,
        "task_logs": task_logs,
        "run_mode": mode,
        "auto_start_enabled": auto_start_enabled,
    }


@app.post("/api/admin/device_logs/request_pull")
def admin_device_logs_request_pull(
    payload: DeviceLogPullRequest,
    authorization: str | None = Header(default=None),
):
    """管理台请求设备上传今日本地日志文件（设备在下次 check_start 时响应）。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err

    raw_ids = [str(x or "").strip() for x in (payload.device_ids or []) if str(x or "").strip()]
    unique_ids = list(dict.fromkeys(raw_ids))
    if not unique_ids:
        return {"code": 1, "msg": "请指定 device_ids"}

    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    safe_ids = [did for did in unique_ids if did in allowed]
    if not safe_ids:
        return {"code": 403, "msg": "无权拉取这些设备日志"}

    queued = _request_device_log_pulls(safe_ids, payload.max_lines)
    return {
        "code": 0,
        "msg": "已请求设备上传今日日志",
        "requested": queued,
        "max_lines": max(1, min(int(payload.max_lines or 400), 500)),
    }


@app.get("/api/admin/device_logs/download")
def admin_device_logs_download(
    device_id: str = "",
    authorization: str | None = Header(default=None),
):
    """下载已同步到云端的设备日志文件（需先请求设备上传今日本地日志）。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return JSONResponse(err, status_code=int(err.get("code") or 401))

    did = str(device_id or "").strip()
    if not did:
        return JSONResponse({"code": 1, "msg": "请指定 device_id"}, status_code=400)

    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    if did not in allowed:
        return JSONResponse({"code": 403, "msg": "无权下载该设备日志"}, status_code=403)

    path = _device_log_file_path(did)
    if not path.is_file() or path.stat().st_size <= 0:
        return JSONResponse(
            {"code": 1, "msg": "暂无该设备日志，请先点击「下载今日日志」等待设备回传"},
            status_code=404,
        )

    alias = did
    conn = _get_db()
    try:
        row = conn.execute(
            "SELECT alias FROM client_devices WHERE device_id = ? LIMIT 1",
            (did,),
        ).fetchone()
        if row and str(row["alias"] or "").strip():
            alias = str(row["alias"]).strip()
    except Exception:
        pass

    safe_alias = re.sub(r"[^A-Za-z0-9_.-]+", "_", alias) or "device"
    filename = f"{safe_alias}_{time.strftime('%Y-%m-%d')}.txt"
    return FileResponse(path=str(path), media_type="text/plain; charset=utf-8", filename=filename)


@app.get("/api/admin/device_logs")
def admin_device_logs(
    device_id: str = "",
    device_ids: str = "",
    max_lines: int = 120,
    authorization: str | None = Header(default=None),
):
    """按需读取设备执行日志（仅允许当前用户可见设备）。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err

    raw_ids: list[str] = []
    single = str(device_id or "").strip()
    if single:
        raw_ids.append(single)
    for part in str(device_ids or "").split(","):
        piece = part.strip()
        if piece:
            raw_ids.append(piece)
    unique_ids = list(dict.fromkeys(raw_ids))
    if not unique_ids:
        return {"code": 1, "msg": "请指定 device_id 或 device_ids"}

    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    safe_ids = [did for did in unique_ids if did in allowed]
    if not safe_ids:
        return {"code": 403, "msg": "无权查看这些设备日志"}

    safe_lines = max(1, min(int(max_lines or 120), 500))
    pull_pending: list[str] = []
    now = time.time()
    with store.lock:
        for did in safe_ids:
            req = store.pending_device_log_pulls.get(did)
            if not req:
                continue
            requested_at = float(req.get("requested_at") or 0.0)
            if requested_at and (now - requested_at) > DEVICE_LOG_PULL_TTL_SEC:
                store.pending_device_log_pulls.pop(did, None)
                continue
            pull_pending.append(did)
    return {
        "code": 0,
        "device_logs": _device_logs_dict_for_ids(safe_ids, safe_lines),
        "pull_pending": pull_pending,
    }


_start_auto_switch_worker()
_start_device_persist_worker()
_ensure_backup_stats_full_query_schedule_off()
if _backup_stats_full_query_schedule_available():
    _start_backup_stats_full_query_worker()
else:
    _backup_stats_full_query_log("每日定时全量查询调度未启动（功能暂时不可用）")
_start_mobile_pending_auto_refill_worker()

try:
    from mail_integration import attach_mail_module

    attach_mail_module(app)
except Exception as _mail_module_exc:
    import logging as _mail_logging

    _mail_logging.getLogger("mail_integration").warning("Mail module not attached: %s", _mail_module_exc)


def _attach_web_admin_static(fastapi_app: FastAPI) -> None:
    """本地 uvicorn 单进程托管控制台静态页（VPS 生产环境仍由 Nginx 提供 /）。"""
    admin_dir = Path(__file__).resolve().parent.parent / "web-admin"
    if not admin_dir.is_dir():
        return
    from fastapi.staticfiles import StaticFiles

    fastapi_app.mount(
        "/",
        StaticFiles(directory=str(admin_dir), html=True),
        name="web-admin-static",
    )


_attach_web_admin_static(app)
