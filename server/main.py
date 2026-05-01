from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import random
import re
import sqlite3
import string
import subprocess
import sys
import locale
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from threading import Lock, Thread, Thread
from typing import Any, Dict, List, Literal

import requests

from fastapi import FastAPI, File, UploadFile, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, field_validator

if sys.platform.startswith("win"):
    # Windows 下 Proactor 事件循环在连接断开时偶发 _call_connection_lost 异常；
    # 改用 Selector 策略可避免该类噪音报错。
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

try:
    from openpyxl import load_workbook
except Exception:
    load_workbook = None


DeviceStatus = Literal["offline", "online", "running", "error"]

BASE_DEFAULT_CONFIG = {
    "fans_per_break": 5,
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
    "favorite_daily_limit": 800,
    "share_daily_limit": 800,
    # 是否互斥只执行一个动作
    "action_single": True,
    # following 质量筛选阈值（用于 follower_queue -> following 回流）
    "following_filter_min_video_count": 10,
    "following_filter_max_following_count": 1000,
    # 手机端单次拉取数量默认值（/api/pull_username 在未传 count 时采用）
    "pull_batch_size": 3,
    # 立即处理 follower_queue 的默认批次大小
    "process_queue_batch_size": 100,
    # 评论文本库
    "comment_texts": ["hi", "moring", "so cute"],
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

DEFAULT_CONFIG = dict(MODE_DEFAULT_CONFIGS["growth"])

BASE_DIR = Path(__file__).resolve().parents[1]
DB_PATH = BASE_DIR / "tiktok_fans.db"
CV_TEMPLATE_ZIP_PATH = BASE_DIR / "server" / "cv_templates.zip"
FOLLOW_SCRAPER_PATH = BASE_DIR / "tiktok_follow_scraper.py"
AUTOSEARCH_PATH = BASE_DIR / "autoSearch_5.28.py"
TIKTOK_PACKAGE_NAME = "com.zhiliaoapp.musically"


def now_text() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _hash_password(password: str) -> str:
    return hashlib.sha256(password.encode('utf-8')).hexdigest()


def _verify_password(password: str, password_hash: str) -> bool:
    return _hash_password(password) == password_hash


def _new_session_token() -> str:
    return uuid.uuid4().hex + uuid.uuid4().hex


def _get_db() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH), timeout=15.0)
    conn.row_factory = sqlite3.Row
    # 缓解并发读写下的 "database is locked"（尤其登录写 session 时）
    conn.execute("PRAGMA busy_timeout=15000")
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


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

            CREATE TABLE IF NOT EXISTS tiktok_account_backup_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                owner_username TEXT NOT NULL DEFAULT 'admin',
                tiktok_password TEXT NOT NULL DEFAULT '',
                mobile_alias TEXT NOT NULL,
                api_address TEXT NOT NULL DEFAULT '',
                email TEXT NOT NULL DEFAULT '',
                email_password TEXT NOT NULL DEFAULT '',
                login_date TEXT,
                video_count INTEGER,
                follower_count INTEGER,
                new_follower_count INTEGER,
                backup_path TEXT NOT NULL DEFAULT '',
                backup_name TEXT,
                backup_status TEXT NOT NULL DEFAULT '',
                remark TEXT NOT NULL DEFAULT '',
                ip TEXT NOT NULL DEFAULT '',
                region TEXT NOT NULL DEFAULT '',
                device_id TEXT NOT NULL DEFAULT '',
                device_alias TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE(username, mobile_alias, owner_username)
            );

            CREATE INDEX IF NOT EXISTS idx_follower_queue_root
                ON follower_queue(root_username);
            CREATE INDEX IF NOT EXISTS idx_tiktok_account_backup_username
                ON tiktok_account_backup_records(username);

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
                backup_path TEXT NOT NULL DEFAULT '',
                backup_name TEXT NOT NULL DEFAULT '',
                backup_time TEXT NOT NULL DEFAULT '',
                backup_status TEXT NOT NULL DEFAULT '',
                remark TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL,
                PRIMARY KEY (owner_username, row_index)
            );
            """
        )

        try:
            conn.execute("ALTER TABLE live_customer ADD COLUMN last_visit_at TEXT")
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
        # 兼容历史库：补齐 auto_start_opt_out 列
        try:
            conn.execute("ALTER TABLE client_devices ADD COLUMN auto_start_opt_out INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        conn.execute("CREATE INDEX IF NOT EXISTS idx_client_devices_owner ON client_devices(owner_username)")
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

        # TikTok 账号备份记录表迁移/补表
        for ddl in (
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN backup_name TEXT",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN updated_at TEXT",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN mobile_alias TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN tiktok_password TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN api_address TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN email TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN email_password TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN login_date TEXT",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN video_count INTEGER",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN follower_count INTEGER",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN new_follower_count INTEGER",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN backup_status TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN remark TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN ip TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN region TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN device_id TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tiktok_account_backup_records ADD COLUMN device_alias TEXT NOT NULL DEFAULT ''",
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


def _ensure_backup_owner_columns():
    conn = _get_db()
    try:
        try:
            conn.execute("ALTER TABLE tiktok_account_backup_records ADD COLUMN owner_username TEXT NOT NULL DEFAULT 'admin'")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("UPDATE tiktok_account_backup_records SET owner_username = 'admin' WHERE owner_username IS NULL OR TRIM(owner_username) = ''")
        except sqlite3.OperationalError:
            pass
        try:
            conn.execute("CREATE INDEX IF NOT EXISTS idx_tiktok_account_backup_owner ON tiktok_account_backup_records(owner_username)")
        except sqlite3.OperationalError:
            pass
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


def migrate_backup_owner_history():
    _ensure_backup_owner_columns()
    conn = _get_db()
    try:
        conn.execute("UPDATE tiktok_account_backup_records SET owner_username = 'admin' WHERE owner_username IS NULL OR TRIM(owner_username) = ''")
        conn.commit()
    finally:
        conn.close()


def init_databases():
    init_follow_db()
    init_autosearch_db()
    init_auth_db()
    _ensure_backup_owner_columns()
    _ensure_user_runtime_configs()
    migrate_backup_owner_history()


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
    status: DeviceStatus = "offline"
    pending_start: bool = False
    paused: bool = False
    auto_start_opt_out: bool = False


@dataclass
class MemoryStore:
    devices: Dict[str, DeviceInfo] = field(default_factory=dict)
    fingerprint_to_device: Dict[str, str] = field(default_factory=dict)
    device_logs: Dict[str, List[str]] = field(default_factory=dict)
    pending_usernames: List[str] = field(default_factory=list)
    pulled_usernames: List[str] = field(default_factory=list)
    default_config: Dict[str, object] = field(default_factory=lambda: dict(MODE_DEFAULT_CONFIGS["growth"]))
    configs_by_mode: Dict[str, Dict[str, object]] = field(
        default_factory=lambda: {mode: dict(cfg) for mode, cfg in MODE_DEFAULT_CONFIGS.items()}
    )
    config: Dict[str, object] = field(default_factory=lambda: dict(MODE_DEFAULT_CONFIGS["growth"]))
    username_cache: List[str] = field(default_factory=list)
    run_mode: Literal["growth", "live", "direct"] = "growth"
    # 旧版：全局 auto_start_enabled（管理员开关）。已改为按用户存储在 user_runtime_configs，
    # 这里保留字段仅用于兼容历史 persisted_runtime_state，不再作为实际策略来源。
    auto_start_enabled: bool = False
    scheduler_enabled: bool = True
    task_processes: Dict[str, subprocess.Popen] = field(default_factory=dict)
    task_logs: Dict[str, List[str]] = field(default_factory=dict)
    # 设备端操作任务队列与结果（用于“备份清理/恢复”）
    pending_device_ops: Dict[str, List[Dict[str, Any]]] = field(default_factory=dict)
    device_op_results: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    lock: Lock = field(default_factory=Lock)

    def verify_device(self, device_id: str, alias: str) -> DeviceInfo | None:
        device = self.devices.get(device_id)
        if not device or device.alias != alias:
            return None
        return device


store = MemoryStore()


def _load_client_devices_from_db() -> None:
    """启动时从 SQLite 恢复设备绑定（含归属客户），并重建内存索引。"""
    conn = _get_db()
    rows: list[Any] = []
    try:
        rows = conn.execute(
            "SELECT device_id, owner_username, alias, fingerprint, status, pending_start, paused, auto_start_opt_out FROM client_devices"
        ).fetchall()
    except sqlite3.OperationalError:
        rows = []
    finally:
        conn.close()
    with store.lock:
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
                status=st,  # type: ignore[arg-type]
                pending_start=bool(int(row["pending_start"] or 0)),
                paused=bool(int(row["paused"] or 0)),
                auto_start_opt_out=bool(int(row["auto_start_opt_out"] or 0)) if "auto_start_opt_out" in row.keys() else False,
            )
            store.fingerprint_to_device[str(row["fingerprint"])] = did
    if rows:
        print(f"[{now_text()}] 系统 | 已从数据库恢复 {len(rows)} 台绑定设备")


def _persist_client_device_to_db(device_id: str) -> None:
    """将单台设备的当前内存状态写入 client_devices。"""
    with store.lock:
        dev = store.devices.get(device_id)
        if not dev:
            return
        owner = dev.owner_username
        alias = dev.alias
        fingerprint = dev.fingerprint
        status = dev.status
        pending_start = 1 if dev.pending_start else 0
        paused = 1 if dev.paused else 0
        auto_start_opt_out = 1 if dev.auto_start_opt_out else 0
    now = now_text()
    conn = _get_db()
    try:
        exists = conn.execute("SELECT created_at FROM client_devices WHERE device_id = ?", (device_id,)).fetchone()
        if exists:
            conn.execute(
                """
                UPDATE client_devices SET owner_username = ?, alias = ?, fingerprint = ?, status = ?,
                    pending_start = ?, paused = ?, auto_start_opt_out = ?, updated_at = ?
                WHERE device_id = ?
                """,
                (owner, alias, fingerprint, status, pending_start, paused, auto_start_opt_out, now, device_id),
            )
        else:
            conn.execute(
                """
                INSERT INTO client_devices (device_id, owner_username, alias, fingerprint, status, pending_start, paused, auto_start_opt_out, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (device_id, owner, alias, fingerprint, status, pending_start, paused, auto_start_opt_out, now, now),
            )
        conn.commit()
    finally:
        conn.close()


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
            with store.lock:
                store.configs_by_mode = merged_cfgs
                store.config = dict(merged_cfgs.get("growth", MODE_DEFAULT_CONFIGS["growth"]))
        mode = blob.get("run_mode")
        if mode in ("growth", "live", "direct"):
            with store.lock:
                store.run_mode = mode  # type: ignore[assignment]
                store.config = dict(store.configs_by_mode.get(mode, MODE_DEFAULT_CONFIGS[mode]))
        auto_start = blob.get("auto_start_enabled")
        if isinstance(auto_start, bool):
            with store.lock:
                store.auto_start_enabled = auto_start
        dc = blob.get("default_config")
        if isinstance(dc, dict):
            dm = dict(MODE_DEFAULT_CONFIGS["growth"])
            dm.update(dc)
            with store.lock:
                store.default_config = dm
        print(f"[{now_text()}] 系统 | 已从数据库恢复运行配置")
    except sqlite3.OperationalError:
        pass
    except Exception as exc:
        print(f"[{now_text()}] 系统 | 恢复持久化配置失败：{exc}")
    finally:
        conn.close()


def _load_user_runtime_state(username: str) -> dict:
    conn = _get_db()
    try:
        rows = conn.execute(
            "SELECT config_key, config_json FROM user_runtime_configs WHERE username = ?",
            (username,),
        ).fetchall()
        result: dict = {}
        for key, raw in rows:
            try:
                result[str(key)] = json.loads(raw or "{}")
            except Exception:
                continue
        return result
    finally:
        conn.close()


def _save_user_runtime_state(username: str, payload: dict):
    conn = _get_db()
    try:
        for key, obj in payload.items():
            conn.execute(
                "INSERT OR REPLACE INTO user_runtime_configs (username, config_key, config_json, updated_at) VALUES (?, ?, ?, ?)",
                (username, key, json.dumps(obj, ensure_ascii=False), now_text()),
            )
        conn.commit()
    finally:
        conn.close()


def _default_user_config_state() -> dict:
    return {
        "run_mode": "growth",
        "configs_by_mode": {mode: dict(cfg) for mode, cfg in MODE_DEFAULT_CONFIGS.items()},
        "default_config": dict(MODE_DEFAULT_CONFIGS["growth"]),
        # 是否开启「设备端自动启动」策略（按用户）
        "auto_start_enabled": False,
    }


def _user_auto_start_enabled(username: str) -> bool:
    data = _load_user_runtime_state(username) or _default_user_config_state()
    raw = data.get("auto_start_enabled")
    return bool(raw) if isinstance(raw, bool) else False


class DeviceAutoStartOptOutRequest(BaseModel):
    device_id: str = Field(..., description="设备ID")
    opt_out: bool = Field(..., description="true=该设备禁用自动开启；false=允许自动开启")


def persist_runtime_state():
    with store.lock:
        payload = {
            "configs_by_mode": {mode: dict(cfg) for mode, cfg in store.configs_by_mode.items()},
            "run_mode": store.run_mode,
            "default_config": dict(store.default_config),
            "auto_start_enabled": store.auto_start_enabled,
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
_load_persisted_runtime_state()


def ensure_username_cache() -> List[str]:
    """从数据库表 mobile_pending_usernames 读取待下发给手机端的用户名。"""
    conn = _get_db()
    try:
        rows = conn.execute(
            "SELECT username FROM mobile_pending_usernames WHERE consume_status = 'pending' ORDER BY created_at, id"
        ).fetchall()
        return [r[0] for r in rows]
    finally:
        conn.close()


def refresh_pending_usernames() -> int:
    usernames = ensure_username_cache()
    if not usernames:
        return 0
    store.pending_usernames = list(usernames)
    return len(usernames)


def bump_usage_for_usernames(conn: sqlite3.Connection, usernames: List[str]):
    if not usernames:
        return
    for username in usernames:
        conn.execute(
            "UPDATE tiktok_roots SET usage_count = usage_count + 1 WHERE root_username = ?",
            (username,),
        )
        conn.execute(
            "UPDATE live_customer SET usage_count = usage_count + 1 WHERE username = ?",
            (username,),
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

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


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
    )

@app.middleware("http")
async def log_request_timing(request: Request, call_next):
    started = time.perf_counter()
    try:
        response = await call_next(request)
    except Exception as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000
        print(f"[{now_text()}] HTTP {request.method} {request.url.path} | 500 | {elapsed_ms:.1f}ms | EXC={exc}")
        raise
    elapsed_ms = (time.perf_counter() - started) * 1000
    if elapsed_ms >= 800 or response.status_code >= 500:
        print(f"[{now_text()}] HTTP {request.method} {request.url.path} | {response.status_code} | {elapsed_ms:.1f}ms")
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


class SyncStatusRequest(DeviceAuthModel):
    status: Literal["online", "running", "paused", "error"]


class StartTaskRequest(BaseModel):
    device_ids: List[str]


class RunModeRequest(BaseModel):
    mode: Literal["growth", "live", "direct"]


class AutoStartRequest(BaseModel):
    enabled: bool


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
    _persist_client_device_to_db(did)
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
    run_mode: Literal["growth", "live", "direct"] | None = None


class AddUsernamesRequest(BaseModel):
    usernames: List[str]
    # 为「tiktok_roots 勾选加入待下发」等场景：true 时不调用隐私判定，直接写入 mobile_pending_usernames
    skip_privacy_filter: bool = False


class AddTikTokRootsRequest(BaseModel):
    usernames: List[str]
    roots_type: str = ""


class TikTokProfileStatsRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    recent_count: int = Field(default=10, ge=1, le=50)
    include_raw: bool = False


class LiveCustomerImportResponse(BaseModel):
    inserted: int
    total_rows: int


class DeviceTikTokBackupClearRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    package_name: str = Field(..., min_length=1, max_length=255)
    backup_record_id: int | None = Field(default=None, ge=1)
    # 可选：与档案一致时写入 backup_path；设备回传时会带回，便于服务端精确更新
    username: str | None = Field(default=None, max_length=64)


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


class DeviceTikTokRestoreRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    backup_name: str = Field(..., min_length=1, max_length=255)


class TikTokBackupRecordSubmitRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    mobile_alias: str = Field(..., min_length=1, max_length=64)
    login_date: str | None = None
    video_count: int | None = Field(default=None, ge=0)
    follower_count: int | None = Field(default=None, ge=0)
    backup_path: str = Field(..., min_length=1, max_length=500)
    backup_name: str | None = Field(default=None, max_length=255)


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
    video_count: str = ""
    follower_count: str = ""
    follower_growth: str = ""


class DeviceEditorRowUpsertRequest(BaseModel):
    row_index: int = Field(..., ge=0)
    device_alias: str = ""
    device_id: str = ""
    ip: str = ""
    region: str = ""
    username: str = ""
    backup_path: str = ""
    backup_name: str = ""
    backup_time: str = ""
    backup_status: str = ""
    remark: str = ""


class DeviceOpPollRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)


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


class UpdateConfigRequest(BaseModel):
    fans_per_break: int | None = None
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
    favorite_daily_limit: int | None = None
    share_daily_limit: int | None = None
    action_single: bool | None = None
    following_filter_min_video_count: int | None = None
    following_filter_max_following_count: int | None = None
    pull_batch_size: int | None = None
    process_queue_batch_size: int | None = None
    comment_texts: List[str] | None = None

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

    @field_validator("process_queue_batch_size")
    @classmethod
    def validate_process_queue_batch_size(cls, value: int | None):
        if value is None:
            return value
        if value < 1 or value > 1000:
            raise ValueError("process_queue 批次大小必须在 1~1000 之间")
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

    @field_validator("comment_texts")
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
    token = _get_auth_token_from_header(headers)
    if not token:
        return None
    conn = _get_db()
    try:
        row = conn.execute(
            "SELECT token, username, role, expires_at FROM auth_sessions WHERE token = ?",
            (token,),
        ).fetchone()
        if not row:
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
        return {"token": row[0], "username": row[1], "role": row[2]}
    finally:
        conn.close()


def _require_login(headers: dict | None = None):
    session = _current_session(headers)
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


def auth_or_error(device_id: str, alias: str):
    device = store.verify_device(device_id, alias)
    if not device:
        return {"code": 2, "msg": "device_id与别名不匹配/设备未绑定"}, None
    return None, device


def _session_device_ids(session: dict) -> set[str]:
    """管理台当前用户可见的设备 ID 集合：管理员为全部，普通客户仅本人 owner_username。"""
    with store.lock:
        if session.get("role") == "admin":
            return set(store.devices.keys())
        owner = session["username"]
        return {did for did, d in store.devices.items() if d.owner_username == owner}


def _device_session_allowed(device: DeviceInfo, session: dict) -> bool:
    if session.get("role") == "admin":
        return True
    return device.owner_username == session["username"]


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


def _list_available_backups() -> List[Dict[str, Any]]:
    items: List[Dict[str, Any]] = []
    with store.lock:
        all_results = list(store.device_op_results.values())

    for r in all_results:
        if (r.get("status") != "success"):
            continue
        data = r.get("data") or {}
        if data.get("op_type") != "tiktok_backup_and_clear":
            continue

        backup_name = str(data.get("backup_name") or "").strip()
        backup_path = str(data.get("backup_path") or "").strip()
        if not backup_name:
            continue

        items.append(
            {
                "backup_name": backup_name,
                "local_path": backup_path,
                "size": 0,
                "mtime": r.get("reported_at") or now_text(),
                "cloud_record_status": "已记录",
            }
        )

    items.sort(key=lambda x: x.get("mtime", ""), reverse=True)
    return items


def _backup_owner_scope(session: dict | None) -> tuple[str, list[Any]]:
    if not session or session.get("role") == "admin":
        return "", []
    return " AND owner_username = ?", [session["username"]]


def _get_backup_record(username: str, mobile_alias: str | None = None):
    conn = _get_db()
    try:
        if mobile_alias:
            row = conn.execute(
                """
                SELECT id, username, tiktok_password, mobile_alias, api_address, email, email_password, login_date, video_count, follower_count, new_follower_count, backup_path, backup_name, created_at, updated_at
                FROM tiktok_account_backup_records
                WHERE username = ? AND mobile_alias = ?
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """,
                (username, mobile_alias),
            ).fetchone()
        else:
            row = conn.execute(
                """
                SELECT id, username, tiktok_password, mobile_alias, api_address, email, email_password, login_date, video_count, follower_count, new_follower_count, backup_path, backup_name, created_at, updated_at
                FROM tiktok_account_backup_records
                WHERE username = ?
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """,
                (username,),
            ).fetchone()
        return dict(row) if row else None
    finally:
        conn.close()


def _get_latest_backup_record_for_alias(mobile_alias: str) -> dict | None:
    mobile_alias = (mobile_alias or "").strip()
    if not mobile_alias:
        return None
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT id, username, tiktok_password, mobile_alias, api_address, email, email_password, login_date, video_count, follower_count, new_follower_count, backup_path, backup_name, created_at, updated_at
            FROM tiktok_account_backup_records
            WHERE mobile_alias = ?
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """,
            (mobile_alias,),
        ).fetchone()
        return dict(row) if row else None
    finally:
        conn.close()


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
        cur = conn.execute(
            """
            UPDATE tiktok_account_backup_records
            SET backup_path = ?, backup_name = ?, updated_at = ?,
                video_count = COALESCE(?, video_count),
                follower_count = COALESCE(?, follower_count),
                new_follower_count = COALESCE(?, new_follower_count)
            WHERE username = ? AND mobile_alias = ?
            """,
            (
                backup_path,
                backup_name or "",
                now,
                video_count,
                follower_count,
                new_follower_count,
                username,
                mobile_alias,
            ),
        )
        conn.commit()
        return int(cur.rowcount or 0)
    finally:
        conn.close()


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
            """
            SELECT id FROM tiktok_account_backup_records
            WHERE mobile_alias = ?
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """,
            (mobile_alias,),
        ).fetchone()
        if not row:
            return 0
        rid = row[0]
        now = now_text()
        cur = conn.execute(
            """
            UPDATE tiktok_account_backup_records
            SET backup_path = ?, backup_name = ?, updated_at = ?,
                video_count = COALESCE(?, video_count),
                follower_count = COALESCE(?, follower_count),
                new_follower_count = COALESCE(?, new_follower_count)
            WHERE id = ?
            """,
            (
                backup_path,
                backup_name or "",
                now,
                video_count,
                follower_count,
                new_follower_count,
                rid,
            ),
        )
        conn.commit()
        return int(cur.rowcount or 0)
    finally:
        conn.close()


def _get_backup_record_by_key(username: str, mobile_alias: str | None = None):
    return _get_backup_record(username, mobile_alias)


def _enqueue_device_op(device_id: str, op: Dict[str, Any]) -> None:
    with store.lock:
        q = store.pending_device_ops.setdefault(device_id, [])
        q.append(op)


def _next_device_op(device_id: str) -> Dict[str, Any] | None:
    with store.lock:
        q = store.pending_device_ops.get(device_id) or []
        if not q:
            return None
        return q.pop(0)


def _save_device_op_result(op_id: str, result: Dict[str, Any]) -> None:
    with store.lock:
        store.device_op_results[op_id] = result
        # 设备操作结果仅用于短期查询，避免内存无限增长。
        max_results = 2000
        while len(store.device_op_results) > max_results:
            oldest_key = next(iter(store.device_op_results), None)
            if not oldest_key:
                break
            store.device_op_results.pop(oldest_key, None)


def _get_device_op_result(op_id: str) -> Dict[str, Any] | None:
    with store.lock:
        return store.device_op_results.get(op_id)


def _cleanup_finished_tasks():
    with store.lock:
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
            cwd=str(Path("D:/autoGroup/auto_traces")),
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


def _is_private_username(username: str) -> tuple[bool, str]:
    """统一私密账号检测入口。"""
    result = fetch_tiktok_profile_stats(username, recent_count=3)
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


def fetch_tiktok_profile_stats(username: str, recent_count: int, include_raw: bool = False):
    username = username.strip().lstrip("@")
    if not username:
        return {
            "code": 1,
            "msg": "用户名不能为空",
        }

    url = f"https://www.tiktok.com/@{username}"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8",
    }

    try:
        response = requests.get(url, headers=headers, timeout=10)
    except Exception as exc:
        return {
            "code": 1,
            "msg": f"请求 TikTok 页面失败: {exc}",
        }

    html = response.text or ""
    response_text_raw = response.text if response.text is not None else ""

    extracted = {
        "follower_count": None,
        "following_count": None,
        "heart_count": None,
        "video_count": None,
    }

    next_data_obj = None
    sigi_obj = None
    universal_data_obj = None

    all_scripts = re.findall(r'<script([^>]*)>(.*?)</script>', html, flags=re.DOTALL | re.IGNORECASE)
    for attrs, content in all_scripts:
        attrs_text = attrs or ""
        content_text = (content or "").strip()
        sid_match = re.search(r'id=["\']([^"\']+)["\']', attrs_text, flags=re.IGNORECASE)
        stype_match = re.search(r'type=["\']([^"\']+)["\']', attrs_text, flags=re.IGNORECASE)
        sid = sid_match.group(1) if sid_match else ""
        stype = stype_match.group(1) if stype_match else ""
        if stype.lower() == "application/json" and content_text:
            try:
                parsed_obj = json.loads(content_text)
            except Exception:
                continue

            sid_upper = sid.upper()
            if sid_upper == "__NEXT_DATA__":
                next_data_obj = parsed_obj
            elif sid_upper == "SIGI_STATE":
                sigi_obj = parsed_obj
            elif sid_upper == "__UNIVERSAL_DATA_FOR_REHYDRATION__":
                universal_data_obj = parsed_obj

    source_debug = {}
    for key, field_name in (
        ("followerCount", "follower_count"),
        ("followingCount", "following_count"),
        ("heartCount", "heart_count"),
        ("videoCount", "video_count"),
    ):
        source = "none"
        val = _search_json_value(sigi_obj, key)
        if val is not None:
            source = "sigi"
        if val is None:
            val = _search_json_value(next_data_obj, key)
            if val is not None:
                source = "next_data"
        if val is None:
            val = _search_json_value(universal_data_obj, key)
            if val is not None:
                source = "universal"
        if val is None:
            m = re.search(rf'"{key}"\s*:\s*(\d+)', html)
            if m:
                val = int(m.group(1))
                source = "regex"
        extracted[field_name] = val
        source_debug[field_name] = source

    private_markers = [
        "This account is private",
        "该账号已设为私密",
        "此账号为私密账号",
        "private account",
    ]

    # 严格模式：仅当私密提示出现在 profile 主内容容器(main)中才判定为私密
    profile_main_text = _extract_profile_main_text(html)
    profile_main_lower = profile_main_text.lower()
    private_hits_main = [m for m in private_markers if m.lower() in profile_main_lower]

    # 仅做调试展示：全量 html 误命中情况（不参与判定）
    private_hits_html_anywhere = [m for m in private_markers if m.lower() in html.lower()]

    # 结构化强信号：TikTok rehydration 数据中的 privateAccount/secret
    private_flags = _extract_private_flags_from_universal(universal_data_obj, username)

    is_private_guess = False
    private_reason = ""
    if private_flags.get("privateAccount") is True or private_flags.get("secret") is True:
        is_private_guess = True
        private_reason = (
            "universal_user_flags:"
            f"privateAccount={private_flags.get('privateAccount')},"
            f"secret={private_flags.get('secret')}"
        )

    debug_payload = {
        "http_status": response.status_code,
        "final_url": str(response.url),
        "html_length": len(html),
        "json_blocks_detected": {
            "next_data": bool(next_data_obj),
            "sigi_state": bool(sigi_obj),
            "universal_data": bool(universal_data_obj),
        },
        "field_source": source_debug,
        "strict_private_mode": "universal_user_flags_only",
        "private_text_hits_main": private_hits_main,
        "private_text_hits_html_anywhere": private_hits_html_anywhere,
        "private_flags_from_universal": private_flags,
    }

    print(
        f"[{now_text()}] TikTok stats full | username={username} | data={extracted} | "
        f"private_guess={is_private_guess} | reason={private_reason or '-'} | debug={debug_payload}"
    )

    result = {
        "code": 0,
        "msg": "ok",
        "username": username,
        "data": extracted,
        "private_account_guess": is_private_guess,
        "private_account_reason": private_reason,
        "debug": debug_payload,
    }

    if include_raw:
        # 页面展示用：返回过滤后的可读 HTML（去掉大块 script/style，避免噪音）
        result["raw_response"] = {
            "status_code": response.status_code,
            "url": str(response.url),
            "encoding": response.encoding,
            "apparent_encoding": response.apparent_encoding,
            "headers": dict(response.headers),
            "text": _build_sanitized_raw_response_text(response_text_raw),
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
        result = fetch_tiktok_profile_stats(username=username, recent_count=1)
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


@app.post("/api/auth/login")
def auth_login(payload: dict):
    username = str(payload.get('username') or '').strip()
    password = str(payload.get('password') or '')
    conn = _get_db()
    try:
        row = conn.execute("SELECT username, password_hash, role, is_active FROM auth_users WHERE username = ?", (username,)).fetchone()
        if not row or int(row[3] or 0) != 1 or not _verify_password(password, row[1]):
            return {"code": 1, "msg": "用户名或密码错误"}
        token = _new_session_token()
        expires = (datetime.now().replace(microsecond=0)).isoformat(sep=' ')
        conn.execute("UPDATE auth_users SET last_login_at = ? WHERE username = ?", (now_text(), username))
        conn.execute(
            "INSERT INTO auth_sessions (token, username, role, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            (token, username, row[2], now_text(), "2999-12-31 23:59:59"),
        )
        conn.commit()
        return {"code": 0, "msg": "登录成功", "token": token, "role": row[2], "username": username}
    finally:
        conn.close()


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
            "SELECT device_id, owner_username, alias, fingerprint, status, pending_start, paused, auto_start_opt_out FROM client_devices WHERE device_id = ?",
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
            status=st,  # type: ignore[arg-type]
            pending_start=bool(int(row["pending_start"] or 0)),
            paused=bool(int(row["paused"] or 0)),
            auto_start_opt_out=bool(int(row["auto_start_opt_out"] or 0)) if "auto_start_opt_out" in row.keys() else False,
        )
        store.fingerprint_to_device[str(row["fingerprint"])] = device_id


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

    conn = _get_db()
    try:
        row = conn.execute(
            "SELECT device_id, owner_username, alias FROM client_devices WHERE fingerprint = ?",
            (fp,),
        ).fetchone()
        if row:
            existing_id = str(row[0])
            row_owner = str(row[1])
            old_alias = str(row[2])
            conflict = conn.execute(
                "SELECT 1 FROM client_devices WHERE owner_username = ? AND alias = ? AND device_id != ?",
                (owner, alias, existing_id),
            ).fetchone()
            if conflict:
                return {"code": 1, "msg": "您已使用过该设备别名，请更换别名"}
            if row_owner != owner:
                conn.execute(
                    "UPDATE client_devices SET owner_username = ?, alias = ?, updated_at = ? WHERE device_id = ?",
                    (owner, alias, now_text(), existing_id),
                )
                conn.commit()
                _hydrate_device_from_db(existing_id)
                print(f"[{now_text()}] 系统 | 设备转绑成功：{old_alias}/{row_owner} -> {alias}/{owner}（device_id={existing_id}）")
                return {"code": 0, "msg": "绑定成功", "device_id": existing_id}
            if old_alias != alias:
                conn.execute(
                    "UPDATE client_devices SET alias = ?, updated_at = ? WHERE device_id = ?",
                    (alias, now_text(), existing_id),
                )
            conn.commit()
            _hydrate_device_from_db(existing_id)
            print(f"[{now_text()}] 系统 | 设备绑定确认：{alias} -> {existing_id}（owner={owner}）")
            return {"code": 0, "msg": "绑定成功", "device_id": existing_id}

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
            INSERT INTO client_devices (device_id, owner_username, alias, fingerprint, status, pending_start, paused, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'offline', 0, 0, ?, ?)
            """,
            (device_id, owner, alias, fp, t, t),
        )
        conn.commit()
    finally:
        conn.close()

    _hydrate_device_from_db(device_id)
    print(f"[{now_text()}] 系统 | 设备绑定成功：{alias} -> {device_id}（owner={owner}）")
    return {"code": 0, "msg": "绑定成功", "device_id": device_id}


@app.post("/api/sync_status")
def sync_status(payload: SyncStatusRequest):
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    with store.lock:
        device.status = payload.status

    _persist_client_device_to_db(payload.device_id)
    print(f"[{now_text()}] 设备别名：{payload.alias} | 状态更新：{payload.status}")
    return {"code": 0, "msg": "状态同步成功"}


@app.get("/api/get_config")
def get_config(device_id: str, alias: str, run_mode: str | None = None):
    error, _device = auth_or_error(device_id, alias)
    if error:
        return error
    mode = run_mode if run_mode in ("growth", "live", "direct") else store.run_mode
    config = store.configs_by_mode.get(mode, store.config)
    return {"code": 0, "config": config, "run_mode": mode, "configs_by_mode": store.configs_by_mode}


@app.get("/api/admin/runtime_config")
def get_runtime_config(authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    data = _load_user_runtime_state(session["username"])
    if not data:
        data = _default_user_config_state()
    return {"code": 0, "data": data}


@app.post("/api/admin/run_mode")
def set_run_mode(payload: RunModeRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    data = _load_user_runtime_state(session["username"]) or _default_user_config_state()
    data["run_mode"] = payload.mode
    _save_user_runtime_state(session["username"], data)
    return {"code": 0, "mode": payload.mode, "config": data.get("configs_by_mode", {}).get(payload.mode, MODE_DEFAULT_CONFIGS[payload.mode])}


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
    data = _load_user_runtime_state(session["username"]) or _default_user_config_state()
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
                    logs = store.device_logs.setdefault(device_id, [])
                    logs.append(
                        f"[{now_text()}] 云端后台 | 自动开启已关闭，已强制暂停（alias={device.alias})",
                    )
                    if len(logs) > 400:
                        store.device_logs[device_id] = logs[-400:]
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
            logs = store.device_logs.setdefault(device_id, [])
            logs.append(
                f"[{now_text()}] 云端后台 | 已下发启动指令（alias={device.alias}）",
            )
            if len(logs) > 400:
                store.device_logs[device_id] = logs[-400:]

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
            logs = store.device_logs.setdefault(device_id, [])
            logs.append(
                f"[{now_text()}] 云端后台 | 已强制停止并清空启动指令（alias={device.alias}）",
            )
            if len(logs) > 400:
                store.device_logs[device_id] = logs[-400:]

    for did in touched:
        _persist_client_device_to_db(did)
    if stopped_count == 0:
        return {"code": 1, "msg": "无可停止设备/设备未绑定"}
    return {"code": 0, "msg": "停止指令已下发", "count": stopped_count}


@app.get("/api/check_start")
def check_start(device_id: str, alias: str):
    error, device = auth_or_error(device_id, alias)
    if error:
        return error

    mutated = False
    auto = _user_auto_start_enabled(device.owner_username) and (not device.auto_start_opt_out)
    with store.lock:
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
    if mutated:
        _persist_client_device_to_db(device_id)
    return result


@app.get("/api/pull_username")
def pull_username(device_id: str, alias: str, count: int | None = None, run_mode: str | None = None):
    error, device = auth_or_error(device_id, alias)
    if error:
        return error

    cfg_default = int(store.config.get("pull_batch_size", 3) or 3)
    req_count = cfg_default if count is None else int(count)
    safe_count = max(1, min(req_count, 100))

    promoted = False
    with store.lock:
        if device.status != "running":
            if device.pending_start:
                device.pending_start = False
                device.status = "running"
                promoted = True
            else:
                return {"code": 3, "msg": "设备未启动，请先从云端一键启动"}

    if promoted:
        _persist_client_device_to_db(device_id)

    conn = _get_db()
    try:
        with store.lock:
            mode = run_mode if run_mode in ("growth", "live", "direct") else store.run_mode

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
            rows = conn.execute(
                "SELECT id, username FROM mobile_pending_usernames WHERE consume_status = 'pending' ORDER BY created_at, id LIMIT ?",
                (safe_count,),
            ).fetchall()
            if not rows:
                with store.lock:
                    refresh_pending_usernames()
                return {"code": 1, "msg": "暂无待执行的用户名"}

            selected_ids = [r[0] for r in rows]
            selected = [r[1] for r in rows]

            bump_usage_for_usernames(conn, selected)
            conn.executemany(
                "DELETE FROM mobile_pending_usernames WHERE id = ?",
                [(rid,) for rid in selected_ids],
            )
            conn.commit()
    finally:
        conn.close()

    with store.lock:
        for username in selected:
            if username in store.pending_usernames:
                store.pending_usernames.remove(username)
            store.pulled_usernames.append(username)

    print(f"[{now_text()}] 设备别名：{alias} | 拉取用户名：{selected}")
    return {"code": 0, "usernames": selected}


@app.post("/api/report_result")
def report_result(payload: ReportResultRequest):
    error, device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    line = f"[{now_text()}] {payload.result}"
    print(f"[{now_text()}] 设备别名：{payload.alias} | 内容：{payload.result}")

    effective_rm = payload.run_mode if payload.run_mode in ("growth", "live", "direct") else None
    with store.lock:
        _rm = effective_rm if effective_rm is not None else store.run_mode
    if _rm != "growth":
        mark_mobile_username_status_by_report(payload.result)
    mark_follower_queue_direct_status_by_report(payload.result)
    mark_follower_queue_process_status_by_report(payload.result)

    status_err = False
    with store.lock:
        logs = store.device_logs.setdefault(payload.device_id, [])
        logs.append(line)
        if len(logs) > 400:
            store.device_logs[payload.device_id] = logs[-400:]
        if "异常" in payload.result:
            device.status = "error"
            status_err = True
    if status_err:
        _persist_client_device_to_db(payload.device_id)

    return {"code": 0, "msg": "结果接收成功"}


@app.post("/api/device_ops/poll")
def device_ops_poll(payload: DeviceOpPollRequest):
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    op = _next_device_op(payload.device_id)
    if not op:
        return {"code": 1, "msg": "暂无待执行设备任务"}
    return {"code": 0, "msg": "ok", "op": op}


@app.post("/api/device_ops/report")
def device_ops_report(payload: DeviceOpReportRequest):
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    data = payload.data or {}
    result = {
        "op_id": payload.op_id,
        "status": payload.status,
        "message": payload.message,
        "data": data,
        "reported_at": now_text(),
    }
    _save_device_op_result(payload.op_id, result)
    if payload.status == "success" and data.get("op_type") == "tiktok_backup_and_clear":
        backup_record_id = data.get("backup_record_id")
        username = str(data.get("username") or "").strip().lstrip("@")
        mobile_alias = str(data.get("mobile_alias") or "").strip()
        backup_path = str(data.get("backup_path") or "").strip()
        backup_name = str(data.get("backup_name") or "").strip() or None
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
                        UPDATE tiktok_account_backup_records
                        SET backup_path = ?, backup_name = ?, updated_at = ?
                        WHERE id = ?
                        """,
                        (backup_path, backup_name or "", now, rid),
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
            with store.lock:
                logs = store.device_logs.setdefault(payload.device_id, [])
                logs.extend(cleaned)
                store.device_logs[payload.device_id] = logs[-600:]

    if payload.status == "success" and data.get("op_type") == "tiktok_backup_and_clear":
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
                db_alias = str(rec.get("mobile_alias") or "").strip()
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
                if previous_path and previous_path != backup_path:
                    _delete_file_safely(previous_path)
                if old_backup_path and old_backup_path not in {backup_path, previous_path}:
                    _delete_file_safely(old_backup_path)

    return {"code": 0, "msg": "设备任务结果已接收"}


@app.post("/api/device_logs/sync")
def device_logs_sync(payload: DeviceLogSyncRequest):
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    cleaned = [str(x).strip() for x in (payload.logs or []) if str(x).strip()]
    if not cleaned:
        return {"code": 0, "msg": "无新增日志"}

    with store.lock:
        logs = store.device_logs.setdefault(payload.device_id, [])
        logs.extend(cleaned)
        # 去重（保序）+ 截断
        deduped: List[str] = []
        seen = set()
        for line in logs:
            if line in seen:
                continue
            seen.add(line)
            deduped.append(line)
        store.device_logs[payload.device_id] = deduped[-600:]

    return {"code": 0, "msg": "日志已同步", "count": len(cleaned)}


@app.post("/api/admin/tiktok_backup/submit")
def submit_tiktok_backup(payload: TikTokBackupSubmitRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    username = (payload.username or "").strip().lstrip("@")
    tiktok_password = (payload.tiktok_password or "").strip()
    mobile_alias = (payload.mobile_alias or "").strip()
    api_address = (payload.api_address or "").strip()
    email = (payload.email or "").strip()
    email_password = (payload.email_password or "").strip()
    backup_path = (payload.backup_path or "").strip()
    backup_name = (payload.backup_name or "").strip() or None
    if not username or not mobile_alias:
        return {"code": 1, "msg": "username 和 mobile_alias 不能为空"}

    try:
        conn = _get_db()
        try:
            now = now_text()
            owner = session["username"]
            existing = conn.execute(
                """
                SELECT id
                FROM tiktok_account_backup_records
                WHERE username = ? AND mobile_alias = ? AND owner_username = ?
                LIMIT 1
                """,
                (username, mobile_alias, owner),
            ).fetchone()
            if existing:
                conn.execute(
                    """
                    UPDATE tiktok_account_backup_records
                    SET
                        tiktok_password = ?,
                        api_address = ?,
                        email = ?,
                        email_password = ?,
                        login_date = COALESCE(?, login_date),
                        video_count = ?,
                        follower_count = ?,
                        new_follower_count = ?,
                        backup_path = ?,
                        backup_name = ?,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        tiktok_password,
                        api_address,
                        email,
                        email_password,
                        payload.login_date,
                        payload.video_count,
                        payload.follower_count,
                        None,
                        backup_path,
                        backup_name,
                        now,
                        int(existing["id"]),
                    ),
                )
            else:
                conn.execute(
                    """
                    INSERT INTO tiktok_account_backup_records
                        (username, owner_username, tiktok_password, mobile_alias, api_address, email, email_password, login_date, video_count, follower_count, new_follower_count, backup_path, backup_name, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        username,
                        owner,
                        tiktok_password,
                        mobile_alias,
                        api_address,
                        email,
                        email_password,
                        payload.login_date,
                        payload.video_count,
                        payload.follower_count,
                        None,
                        backup_path,
                        backup_name,
                        now,
                        now,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
    except Exception as exc:
        return {"code": 1, "msg": f"提交 TikTok 账号信息失败: {exc}"}

    return {
        "code": 0,
        "msg": "TikTok 账号信息已入库",
        "username": username,
        "tiktok_password": tiktok_password,
        "mobile_alias": mobile_alias,
        "api_address": api_address,
        "email": email,
        "email_password": email_password,
    }


def _enqueue_tiktok_device_op(device_id: str, op_type: str, payload: dict[str, Any]) -> str:
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


@app.post("/api/admin/device_tiktok/clear")
def admin_device_tiktok_clear(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_clear",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：单独清理 TikTok 数据",
        "op_id": op_id,
    }


@app.post("/api/admin/device_tiktok/backup_and_clear")
def admin_device_tiktok_backup_and_clear(payload: DeviceTikTokBackupClearRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

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

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_backup_and_clear",
        op_payload,
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：备份并一键清理 TikTok",
        "op_id": op_id,
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


@app.get("/api/admin/device_tiktok/op_result")
def admin_device_tiktok_op_result(op_id: str, authorization: str | None = Header(default=None)):
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    result = _get_device_op_result(op_id)
    if not result:
        return {"code": 1, "msg": "任务尚未完成", "status": "pending", "op_id": op_id}
    return {"code": 0, "msg": "ok", "status": "done", "op_id": op_id, "result": result}


@app.get("/api/admin/device_tiktok/backups")
def admin_device_tiktok_backups(authorization: str | None = Header(default=None)):
    err, _session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    items = _list_available_backups()
    return {"code": 0, "items": items}


@app.get("/api/admin/device_tiktok/accounts")
def admin_device_tiktok_accounts(authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    conn = _get_db()
    try:
        owner_sql, owner_params = _backup_owner_scope(session)
        rows = conn.execute(
            f"""
            SELECT
                mobile_alias,
                COUNT(1) AS account_count,
                MAX(updated_at) AS latest_updated_at,
                GROUP_CONCAT(username, '||') AS usernames
            FROM tiktok_account_backup_records
            WHERE 1=1{owner_sql}
            GROUP BY mobile_alias
            ORDER BY latest_updated_at DESC, mobile_alias ASC
            """,
            (*owner_params,),
        ).fetchall()
        items = [dict(r) for r in rows]
        return {"code": 0, "items": items}
    finally:
        conn.close()


@app.get("/api/admin/device_tiktok/accounts/{mobile_alias}")
def admin_device_tiktok_accounts_by_alias(mobile_alias: str, authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    alias = (mobile_alias or "").strip()
    if not alias:
        return {"code": 1, "msg": "mobile_alias 不能为空"}
    conn = _get_db()
    try:
        owner_sql, owner_params = _backup_owner_scope(session)
        rows = conn.execute(
            f"""
            SELECT id, username, tiktok_password, mobile_alias, api_address, email, email_password, login_date, video_count, follower_count, new_follower_count, backup_path, backup_name, created_at, updated_at
            FROM tiktok_account_backup_records
            WHERE mobile_alias = ?{owner_sql}
            ORDER BY updated_at DESC, id DESC
            """,
            (alias, *owner_params),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows]}
    finally:
        conn.close()


@app.get("/api/admin/tiktok_backup/records")
def list_tiktok_backup_records(username: str = "", mobile_alias: str = "", page: int = 1, page_size: int = 20, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    safe_page = max(1, int(page))
    safe_size = max(1, min(int(page_size), 100))
    offset = (safe_page - 1) * safe_size
    conn = _get_db()
    try:
        clauses = []
        params: List[Any] = []
        if username.strip():
            clauses.append("username LIKE ?")
            params.append(f"%{username.strip()}%")
        if mobile_alias.strip():
            clauses.append("mobile_alias = ?")
            params.append(mobile_alias.strip())
        owner_sql, owner_params = _backup_owner_scope(session)
        where_sql = f" WHERE {' AND '.join(clauses)}" if clauses else " WHERE 1=1"
        total = conn.execute(f"SELECT COUNT(1) FROM tiktok_account_backup_records{where_sql}{owner_sql}", (*params, *owner_params)).fetchone()[0]
        rows = conn.execute(
            f"""
            SELECT username, mobile_alias, video_count, follower_count, new_follower_count, backup_path, backup_name, created_at, updated_at
            FROM tiktok_account_backup_records
            {where_sql}{owner_sql}
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """,
            (*params, *owner_params, safe_size, offset),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows], "page": safe_page, "page_size": safe_size, "total": total}
    finally:
        conn.close()


@app.post("/api/admin/tiktok_backup/fill")
def fill_tiktok_backup_record(username: str, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    uname = str(username or "").strip().lstrip("@")
    if not uname:
        return {"code": 1, "msg": "username 不能为空"}

    conn = _get_db()
    try:
        old = conn.execute(
            """
            SELECT username, tiktok_password, mobile_alias, video_count, follower_count, new_follower_count, backup_path, backup_name
            FROM tiktok_account_backup_records
            WHERE username = ? AND owner_username = ?
            LIMIT 1
            """,
            (uname, session["username"]),
        ).fetchone()
        old_follower = old[4] if old else None
    finally:
        conn.close()

    try:
        profile_text = subprocess.check_output(
            [sys.executable, str(AUTOSEARCH_PATH), "--profile", uname],
            cwd=str(Path("D:/autoGroup/auto_traces")),
            text=True,
            encoding=(locale.getpreferredencoding(False) or "utf-8"),
            errors="replace",
        )
        parsed = json.loads(profile_text.strip().splitlines()[-1]) if profile_text.strip() else {}
    except Exception as exc:
        return {"code": 1, "msg": f"查询失败: {exc}"}

    video_count = parsed.get("video_count")
    follower_count = parsed.get("follower_count")
    new_follower_count = None
    if isinstance(old_follower, int) and isinstance(follower_count, int):
        new_follower_count = follower_count - old_follower

    conn = _get_db()
    try:
        now = now_text()
        conn.execute(
            """
            UPDATE tiktok_account_backup_records
            SET video_count = ?, follower_count = ?, new_follower_count = ?, updated_at = ?
            WHERE username = ? AND owner_username = ?
            """,
            (video_count, follower_count, new_follower_count, now, uname, session["username"]),
        )
        conn.commit()
    finally:
        conn.close()

    return {
        "code": 0,
        "msg": "查询并回填成功",
        "username": uname,
        "video_count": video_count,
        "follower_count": follower_count,
        "new_follower_count": new_follower_count,
    }


@app.get("/api/admin/tiktok_backup/search")
def search_tiktok_backup_records(username: str = "", mobile_alias: str = "", limit: int = 200, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    conn = _get_db()
    try:
        clauses = []
        params: List[Any] = []
        if username.strip():
            clauses.append("username LIKE ?")
            params.append(f"%{username.strip()}%")
        if mobile_alias.strip():
            clauses.append("mobile_alias = ?")
            params.append(mobile_alias.strip())
        owner_sql, owner_params = _backup_owner_scope(session)
        where_sql = " WHERE " + " AND ".join(clauses) if clauses else " WHERE 1=1"
        sql = f"""
            SELECT username, mobile_alias, video_count, follower_count, new_follower_count, backup_path, backup_name, created_at, updated_at
            FROM tiktok_account_backup_records
            {where_sql}{owner_sql}
            ORDER BY updated_at DESC, id DESC
            LIMIT ?
        """
        params.extend(owner_params)
        params.append(max(1, min(int(limit), 1000)))
        rows = conn.execute(sql, params).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows]}
    finally:
        conn.close()


@app.get("/api/admin/devices/aliases")
def list_device_aliases(authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session)
    with store.lock:
        aliases = sorted(
            {
                (device.alias or "").strip()
                for did, device in store.devices.items()
                if did in allowed and (device.alias or "").strip()
            }
        )
    return {"code": 0, "items": aliases}


@app.get("/api/admin/editor/account_rows")
def list_account_editor_rows(authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT row_index, username, password, email, email_password, api, is_login, login_time, region, video_count, follower_count, follower_growth
            FROM account_editor_rows
            WHERE owner_username = ?
            ORDER BY row_index ASC
            """,
            (session["username"],),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows]}
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
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, password, email, email_password, api, is_login, login_time, region, video_count, follower_count, follower_growth, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(owner_username, row_index) DO UPDATE SET
                username = excluded.username,
                password = excluded.password,
                email = excluded.email,
                email_password = excluded.email_password,
                api = excluded.api,
                is_login = excluded.is_login,
                login_time = excluded.login_time,
                region = excluded.region,
                video_count = excluded.video_count,
                follower_count = excluded.follower_count,
                follower_growth = excluded.follower_growth,
                updated_at = excluded.updated_at
            """,
            (
                session["username"],
                int(payload.row_index),
                payload.username.strip(),
                payload.password.strip(),
                payload.email.strip(),
                payload.email_password.strip(),
                payload.api.strip(),
                1 if payload.is_login else 0,
                payload.login_time.strip(),
                payload.region.strip(),
                payload.video_count.strip(),
                payload.follower_count.strip(),
                payload.follower_growth.strip(),
                now,
            ),
        )
        conn.commit()
        return {"code": 0, "msg": "账号记录已保存", "row_index": payload.row_index}
    finally:
        conn.close()


@app.get("/api/admin/editor/device_rows")
def list_device_editor_rows(authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT row_index, device_alias, device_id, ip, region, username, backup_path, backup_name, backup_time, backup_status, remark
            FROM device_editor_rows
            WHERE owner_username = ?
            ORDER BY row_index ASC
            """,
            (session["username"],),
        ).fetchall()
        return {"code": 0, "items": [dict(r) for r in rows]}
    finally:
        conn.close()


@app.post("/api/admin/editor/device_rows/upsert")
def upsert_device_editor_row(payload: DeviceEditorRowUpsertRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    conn = _get_db()
    try:
        now = now_text()
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, device_id, ip, region, username, backup_path, backup_name, backup_time, backup_status, remark, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(owner_username, row_index) DO UPDATE SET
                device_alias = excluded.device_alias,
                device_id = excluded.device_id,
                ip = excluded.ip,
                region = excluded.region,
                username = excluded.username,
                backup_path = excluded.backup_path,
                backup_name = excluded.backup_name,
                backup_time = excluded.backup_time,
                backup_status = excluded.backup_status,
                remark = excluded.remark,
                updated_at = excluded.updated_at
            """,
            (
                session["username"],
                int(payload.row_index),
                payload.device_alias.strip(),
                payload.device_id.strip(),
                payload.ip.strip(),
                payload.region.strip(),
                payload.username.strip(),
                payload.backup_path.strip(),
                payload.backup_name.strip(),
                payload.backup_time.strip(),
                payload.backup_status.strip(),
                payload.remark.strip(),
                now,
            ),
        )
        conn.commit()
        return {"code": 0, "msg": "设备记录已保存", "row_index": payload.row_index}
    finally:
        conn.close()


@app.post("/api/admin/device_tiktok/restore")
def admin_device_tiktok_restore(payload: DeviceTikTokRestoreRequest, authorization: str | None = Header(default=None)):
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}

    backup_name = Path(payload.backup_name).name
    conn = _get_db()
    try:
        row = conn.execute(
            """
            SELECT backup_path, username, mobile_alias, video_count, follower_count, new_follower_count
            FROM tiktok_account_backup_records
            WHERE backup_name = ? AND owner_username = ?
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """,
            (backup_name, session["username"]),
        ).fetchone()
        backup_path = str(row[0] or "").strip() if row else ""
        record_username = str(row[1] or "").strip() if row else ""
        record_mobile_alias = str(row[2] or "").strip() if row else ""
        record_video_count = row[3] if row else None
        record_follower_count = row[4] if row else None
        record_new_follower_count = row[5] if row else None
    finally:
        conn.close()

    op_id = f"op_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"
    _enqueue_device_op(
        payload.device_id,
        {
            "op_id": op_id,
            "op_type": "tiktok_restore",
            "payload": {
                "device_id": payload.device_id,
                "alias": payload.alias,
                "package_name": TIKTOK_PACKAGE_NAME,
                "backup_name": backup_name,
                "backup_path": backup_path or backup_name,
                "username": record_username,
                "mobile_alias": record_mobile_alias,
                "video_count": record_video_count,
                "follower_count": record_follower_count,
                "new_follower_count": record_new_follower_count,
            },
            "created_at": now_text(),
        },
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：恢复 TikTok 数据",
        "op_id": op_id,
        "backup_path": backup_path or backup_name,
        "record_username": record_username,
        "record_mobile_alias": record_mobile_alias,
    }


def _insert_mobile_pending_usernames(usernames: List[str], skip_privacy_filter: bool = False):
    cleaned = [u.strip().lstrip("@") for u in usernames if u and u.strip()]
    if not cleaned:
        return {"code": 1, "msg": "未提供有效用户名"}

    if skip_privacy_filter:
        allowed, skipped = cleaned, []
    else:
        allowed, skipped = _filter_non_private_usernames(cleaned)
        if not allowed:
            return {"code": 1, "msg": "全部为私密账号，未入库", "skipped_private": skipped, "count": 0}

    conn = _get_db()
    try:
        now = now_text()
        inserted = 0
        for username in allowed:
            cur = conn.execute(
                "INSERT INTO mobile_pending_usernames (username, created_at) VALUES (?, ?)",
                (username, now),
            )
            if cur.rowcount > 0:
                inserted += 1
        conn.commit()
    finally:
        conn.close()

    with store.lock:
        store.pending_usernames.extend([u for u in allowed if u])

    if skip_privacy_filter:
        print(f"[{now_text()}] 系统 | 新增待执行用户名 {len(allowed)} 个（已跳过隐私过滤）")
    else:
        print(f"[{now_text()}] 系统 | 新增待执行用户名 {len(allowed)} 个 | 跳过私密 {len(skipped)} 个")
    return {
        "code": 0,
        "msg": "用户名导入成功",
        "count": len(allowed),
        "inserted": inserted,
        "skipped_private": skipped,
    }


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


@app.post("/api/admin/tiktok_profile_stats")
def tiktok_profile_stats(payload: TikTokProfileStatsRequest):
    return fetch_tiktok_profile_stats(payload.username, payload.recent_count, include_raw=payload.include_raw)


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
def import_tiktok_roots(file: UploadFile = File(...)):
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

    raw_usernames = [u for u, _ in dedup]
    allowed_names, skipped = _filter_non_private_usernames(raw_usernames)
    allowed_set = {x.lower() for x in allowed_names}
    filtered_dedup = [(u, t) for (u, t) in dedup if u.lower() in allowed_set]

    if not filtered_dedup:
        return {"code": 1, "msg": "导入数据均为私密账号，未入库", "skipped_private": skipped, "inserted": 0, "total_rows": len(dedup)}

    now = now_text()
    conn = _get_db()
    try:
        # 按你的规则：上传表格后，本轮根用户集合仅采用该表格数据
        conn.execute("DELETE FROM tiktok_roots")

        inserted = 0
        for username, roots_type in filtered_dedup:
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
        "msg": "根用户导入完成（已覆盖旧根用户）",
        "inserted": inserted,
        "total_rows": len(dedup),
        "skipped_private": skipped,
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
    if not incoming:
        return {"code": 1, "msg": "未提供配置项"}

    data = _load_user_runtime_state(session["username"]) or _default_user_config_state()
    mode = data.get("run_mode", "growth")
    configs_by_mode = data.get("configs_by_mode", {})
    current = dict(configs_by_mode.get(mode, MODE_DEFAULT_CONFIGS[mode]))
    current.update(incoming)
    configs_by_mode[mode] = current
    data["configs_by_mode"] = configs_by_mode
    data["default_config"] = dict(configs_by_mode.get("growth", MODE_DEFAULT_CONFIGS["growth"]))
    _save_user_runtime_state(session["username"], data)
    print(f"[{now_text()}] 用户 {session['username']} | {mode} 配置已更新：{incoming}")
    return {"code": 0, "msg": "配置更新成功", "config": current, "configs_by_mode": configs_by_mode}


@app.post("/api/admin/config/default")
def set_default_config(payload: UpdateConfigRequest, authorization: str | None = Header(default=None)):
    session = _current_session({"authorization": authorization} if authorization else {})
    if not session:
        return {"code": 401, "msg": "请先登录"}
    incoming = payload.model_dump(exclude_none=True)
    if not incoming:
        return {"code": 1, "msg": "未提供配置项"}

    data = _load_user_runtime_state(session["username"]) or _default_user_config_state()
    configs_by_mode = data.get("configs_by_mode", {})
    for mode in ("growth", "live", "direct"):
        current = dict(configs_by_mode.get(mode, MODE_DEFAULT_CONFIGS[mode]))
        current.update(incoming)
        configs_by_mode[mode] = current
    data["configs_by_mode"] = configs_by_mode
    data["default_config"] = dict(configs_by_mode["growth"])
    _save_user_runtime_state(session["username"], data)
    print(f"[{now_text()}] 用户 {session['username']} | 默认配置已更新：{incoming}")
    return {"code": 0, "msg": "默认配置更新成功", "config": configs_by_mode.get(data.get("run_mode", "growth"), MODE_DEFAULT_CONFIGS["growth"]), "default_config": data["default_config"], "configs_by_mode": configs_by_mode}


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
def trigger_collect_roots_db(limit: int = 100, container_ids: str = "", parallel: int = 1):
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
                "--mode", "collect_roots_db",
                "--limit", str(worker_limit),
                "--container-id", first,
            ]
            results.append(_start_task_process(f"collect_roots_db_worker_{idx}", cmd))
        return {"code": 0, "msg": "DB抓取任务已按环境ID并行触发", "workers": len(jobs), "results": results}

    cmd = [sys.executable, str(FOLLOW_SCRAPER_PATH), "--mode", "collect_roots_db", "--limit", str(limit)]
    return _start_task_process("collect_roots_db_once", cmd)


@app.post("/api/admin/tasks/trigger_process_queue")
def trigger_process_queue(limit: int = 100, container_ids: str = "", parallel: int = 1):
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
        return {"code": 0, "msg": "队列任务已按环境ID并行触发", "workers": len(jobs), "results": results}

    cmd = [sys.executable, str(FOLLOW_SCRAPER_PATH), "--mode", "process_queue", "--limit", str(limit)]
    return _start_task_process("process_queue_once", cmd)


@app.post("/api/admin/tasks/trigger_autosearch_once")
def trigger_autosearch_once():
    cmd = [sys.executable, str(AUTOSEARCH_PATH), "--once"]
    return _start_task_process("autosearch_once", cmd)


@app.post("/api/admin/tasks/scheduler/start")
def start_scheduler_tasks():
    with store.lock:
        store.scheduler_enabled = True
    return {"code": 0, "msg": "定时任务已开启"}


@app.post("/api/admin/tasks/scheduler/stop")
def stop_scheduler_tasks():
    with store.lock:
        store.scheduler_enabled = False
    _stop_all_task_processes()
    return {"code": 0, "msg": "定时任务已停止（并终止正在运行任务）"}


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
    with store.lock:
        running = []
        for name, proc in store.task_processes.items():
            running.append({"name": name, "pid": proc.pid, "running": proc.poll() is None})
        task_logs = {k: v[-120:] for k, v in store.task_logs.items()}
        return {
            "code": 0,
            "scheduler_enabled": store.scheduler_enabled,
            "running_tasks": running,
            "task_logs": task_logs,
            "auto_search_schedule": "每日 America/New_York 06:00，并在首次后+2小时补跑一次",
        }


@app.get("/api/admin/table_page")
def admin_table_page(table: str, page: int = 1, page_size: int = 100):
    allowed = {
        "tiktok_roots": ["root_username", "roots_type", "discovered_at", "last_use_follower_count", "usage_count"],
        "follower_queue": ["username", "root_username", "created_at", "consume_status", "consumed_at", "consumed_by", "consumed_mode", "following_access_status", "following_fetch_success", "mobile_push_status"],
        "mobile_pending_usernames": ["id", "username", "created_at", "consume_status", "consumed_at"],
        "live_customer": ["username", "usage_count", "created_at", "last_visit_at"],
        "users": ["id", "username", "remark", "created_at", "last_use_at", "last_use_follower_count"],
    }
    if table not in allowed:
        return {"code": 1, "msg": f"不支持的表: {table}"}

    safe_page = max(1, int(page))
    safe_size = max(1, min(int(page_size), 1000))
    offset = (safe_page - 1) * safe_size

    order_map = {
        "tiktok_roots": "ORDER BY discovered_at DESC, root_username ASC",
        "follower_queue": "ORDER BY created_at DESC, username DESC",
        "mobile_pending_usernames": "ORDER BY id DESC",
        "live_customer": "ORDER BY created_at DESC, username ASC",
        "users": "ORDER BY id DESC",
    }

    conn = _get_db()
    try:
        total = conn.execute(f"SELECT COUNT(1) FROM {table}").fetchone()[0]
        rows = conn.execute(
            f"SELECT * FROM {table} {order_map[table]} LIMIT ? OFFSET ?",
            (safe_size, offset),
        ).fetchall()
        return {
            "code": 0,
            "table": table,
            "fields": allowed[table],
            "page": safe_page,
            "page_size": safe_size,
            "total": total,
            "items": [dict(r) for r in rows],
        }
    finally:
        conn.close()


@app.get("/api/admin/overview")
def admin_overview(authorization: str | None = Header(default=None)):
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session)
    auto_start_enabled = _user_auto_start_enabled(session["username"])
    with store.lock:
        if not store.pending_usernames:
            refresh_pending_usernames()
        devices: Dict[str, Any] = {}
        for device_id, device in store.devices.items():
            if device_id not in allowed:
                continue
            entry: Dict[str, Any] = {
                "alias": device.alias,
                "status": device.status,
                "pending_start": device.pending_start,
                "auto_start_opt_out": device.auto_start_opt_out,
            }
            if session.get("role") == "admin":
                entry["owner_username"] = device.owner_username
            devices[device_id] = entry
        device_logs = {k: v[-120:] for k, v in store.device_logs.items() if k in allowed}

    conn = _get_db()
    try:
        follow_total = conn.execute(
            "SELECT COUNT(1) FROM follower_queue"
        ).fetchone()[0]
        follow_unprocessed = conn.execute(
            "SELECT COUNT(1) FROM follower_queue WHERE COALESCE(following_fetch_success, 0) = 0"
        ).fetchone()[0]
        follow_done = conn.execute(
            "SELECT COUNT(1) FROM follower_queue WHERE COALESCE(following_fetch_success, 0) = 1"
        ).fetchone()[0]
        pending_count = conn.execute(
            "SELECT COUNT(1) FROM mobile_pending_usernames WHERE consume_status = 'pending'"
        ).fetchone()[0]
        live_count = conn.execute(
            "SELECT COUNT(1) FROM live_customer"
        ).fetchone()[0]
    finally:
        conn.close()

    return {
        "code": 0,
        "devices": devices,
        "device_logs": device_logs,
        "pending_usernames": store.pending_usernames,
        "pulled_usernames": store.pulled_usernames,
        "config": store.config,
        "run_mode": store.run_mode,
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
    }


@app.get("/api/admin/logs_snapshot")
def admin_logs_snapshot(authorization: str | None = Header(default=None)):
    """轻量轮询：仅设备状态与执行日志，不查库、不覆盖管理台未保存的其它状态。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session)
    auto_start_enabled = _user_auto_start_enabled(session["username"])
    with store.lock:
        devices = {}
        for device_id, device in store.devices.items():
            if device_id not in allowed:
                continue
            entry: Dict[str, Any] = {
                "alias": device.alias,
                "status": device.status,
                "pending_start": device.pending_start,
                "auto_start_opt_out": device.auto_start_opt_out,
            }
            if session.get("role") == "admin":
                entry["owner_username"] = device.owner_username
            devices[device_id] = entry
        device_logs = {k: v[-120:] for k, v in store.device_logs.items() if k in allowed}
        mode = store.run_mode
    return {
        "code": 0,
        "devices": devices,
        "device_logs": device_logs,
        "run_mode": mode,
        "auto_start_enabled": auto_start_enabled,
    }
