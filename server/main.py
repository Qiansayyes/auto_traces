from __future__ import annotations

import asyncio
import base64
import contextlib
import hashlib
import inspect
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
from threading import Event, Lock, Thread, Thread
from typing import Any, Dict, Iterable, List, Literal

import requests

from tiktok_universal_snapshot import (
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
    # 引流 Videos 列表轮次（与完整互点共用）；粉丝列表 Follow 单次会话点击上限
    "mutual_videos_scroll_rounds": 5,
    "mutual_follow_tap_session_limit": 20,
    # 涨粉：连续「整批拉取的用户名在设备端全部执行完毕」多少批后自动进入互点；-1 表示永不自动切换
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

DEFAULT_CONFIG = dict(MODE_DEFAULT_CONFIGS["growth"])

BASE_DIR = Path(__file__).resolve().parents[1]
DB_PATH = BASE_DIR / "tiktok_fans.db"
CV_TEMPLATE_ZIP_PATH = BASE_DIR / "server" / "cv_templates.zip"
FOLLOW_SCRAPER_PATH = BASE_DIR / "tiktok_follow_scraper.py"
AUTOSEARCH_PATH = BASE_DIR / "autoSearch_5.28_with_debug.py"
TIKTOK_PACKAGE_NAME = "com.zhiliaoapp.musically"
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


def _get_db() -> sqlite3.Connection:
    # 关键诉求：管理台接口不能因为 sqlite 写锁卡死几十秒。
    # WAL + 较短 timeout/busy_timeout 让读写更可并发；遇到锁则尽快返回错误由前端提示/重试。
    # 注意：FastAPI 同步 handler 跑在线程池里；sqlite 若长期等待锁，会把请求线程占满，最终触发 504。
    # 这里把等待时间压到很短：锁忙就快速失败，让上层返回 503（前端可重试）。
    conn = sqlite3.connect(str(DB_PATH), timeout=1.0)
    conn.row_factory = sqlite3.Row
    # 缓解并发读写下的 "database is locked"（尤其登录写 session 时）
    conn.execute("PRAGMA busy_timeout=500")
    conn.execute("PRAGMA synchronous=NORMAL")
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
                backup_path TEXT NOT NULL DEFAULT '',
                backup_name TEXT NOT NULL DEFAULT '',
                backup_time TEXT NOT NULL DEFAULT '',
                backup_status TEXT NOT NULL DEFAULT '',
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
            conn.execute("ALTER TABLE client_devices ADD COLUMN device_group TEXT NOT NULL DEFAULT ''")
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


def _list_distinct_device_groups(session: dict) -> list[str]:
    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    if not allowed:
        return []
    placeholders = ",".join("?" for _ in allowed)
    conn = _get_db()
    try:
        rows = conn.execute(
            f"""
            SELECT DISTINCT TRIM(device_group) AS grp
            FROM client_devices
            WHERE device_id IN ({placeholders}) AND COALESCE(TRIM(device_group), '') <> ''
            ORDER BY grp ASC
            """,
            list(allowed),
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
    init_follow_db()
    init_autosearch_db()
    init_auth_db()
    _ensure_backup_owner_columns()
    _ensure_user_runtime_configs()
    _ensure_template_permissions()
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
    device_group: str = ""
    status: DeviceStatus = "offline"
    pending_start: bool = False
    paused: bool = False
    auto_start_opt_out: bool = False


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
def _tracked_store_lock(label: str, threshold_ms: int = 200):
    """仅在锁等待过长或持锁过久时打印，避免淹没与「待下发入队」争用相关的信号。"""
    started_at = time.monotonic()
    caller = "unknown"
    try:
        stack = inspect.stack()
        frame = stack[2] if len(stack) > 2 else stack[1]
        caller = f"{Path(frame.filename).name}:{frame.lineno}:{frame.function}"
    except Exception:
        pass
    store.lock.acquire()
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



def _load_client_devices_from_db() -> None:
    """启动时从 SQLite 恢复设备绑定（含归属客户），并重建内存索引。"""
    conn = _get_db()
    rows: list[Any] = []
    try:
        rows = conn.execute(
            "SELECT device_id, owner_username, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out FROM client_devices"
        ).fetchall()
    except sqlite3.OperationalError:
        rows = []
    finally:
        conn.close()
    with _tracked_store_lock("_load_client_devices_from_db"):
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
            )
            store.fingerprint_to_device[str(row["fingerprint"])] = did
    if rows:
        print(f"[{now_text()}] 系统 | 已从数据库恢复 {len(rows)} 台绑定设备")


def _persist_client_device_to_db(device_id: str) -> None:
    """将单台设备的当前内存状态写入 client_devices。"""
    t0 = time.perf_counter()
    print(f"[{now_text()}] persist_client_device | start | device_id={device_id}")
    with _tracked_store_lock("_persist_client_device_to_db"):
        dev = store.devices.get(device_id)
        if not dev:
            print(f"[{now_text()}] persist_client_device | missing_device | device_id={device_id}")
            return
        owner = dev.owner_username
        alias = dev.alias
        device_group = dev.device_group
        fingerprint = dev.fingerprint
        status = dev.status
        pending_start = 1 if dev.pending_start else 0
        paused = 1 if dev.paused else 0
        auto_start_opt_out = 1 if dev.auto_start_opt_out else 0
    now = now_text()
    conn = _get_db()
    try:
        print(f"[{now_text()}] persist_client_device | db_write_start | device_id={device_id} status={status}")
        exists = conn.execute("SELECT created_at FROM client_devices WHERE device_id = ?", (device_id,)).fetchone()
        if exists:
            conn.execute(
                """
                UPDATE client_devices SET owner_username = ?, alias = ?, device_group = ?, fingerprint = ?, status = ?,
                    pending_start = ?, paused = ?, auto_start_opt_out = ?, updated_at = ?
                WHERE device_id = ?
                """,
                (owner, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out, now, device_id),
            )
        else:
            conn.execute(
                """
                INSERT INTO client_devices (device_id, owner_username, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (device_id, owner, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out, now, now),
            )
        conn.commit()
        print(f"[{now_text()}] persist_client_device | db_write_done | device_id={device_id} elapsed={(time.perf_counter() - t0) * 1000:.1f}ms")
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
            with _tracked_store_lock("_load_persisted_runtime_state:configs_by_mode"):
                store.configs_by_mode = merged_cfgs
                store.config = dict(merged_cfgs.get("growth", MODE_DEFAULT_CONFIGS["growth"]))
        mode = blob.get("run_mode")
        if mode in RUN_MODES:
            with _tracked_store_lock("_load_persisted_runtime_state:run_mode"):
                store.run_mode = mode  # type: ignore[assignment]
                store.config = dict(store.configs_by_mode.get(mode, MODE_DEFAULT_CONFIGS[mode]))
        auto_start = blob.get("auto_start_enabled")
        if isinstance(auto_start, bool):
            with _tracked_store_lock("_load_persisted_runtime_state:auto_start"):
                store.auto_start_enabled = auto_start
        dc = blob.get("default_config")
        if isinstance(dc, dict):
            dm = dict(MODE_DEFAULT_CONFIGS["growth"])
            dm.update(dc)
            with _tracked_store_lock("_load_persisted_runtime_state:default_config"):
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
        u = (username or "").strip()
        if u:
            with store.lock:
                store.user_config_push_revision[u] = int(store.user_config_push_revision.get(u, 0)) + 1
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


def _build_effective_template_bundle(username: str, data: dict) -> tuple[Dict[str, Dict[str, object]], Dict[str, Dict[str, object]]]:
    own_templates = data.get("configs_by_template", {})
    merged_templates: Dict[str, Dict[str, object]] = {}
    template_meta: Dict[str, Dict[str, object]] = {}
    if isinstance(own_templates, dict):
        for key, cfg in own_templates.items():
            name = _sanitize_template_name(key, "default")
            if not isinstance(cfg, dict):
                continue
            merged_templates[name] = dict(cfg)
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
        merged_templates[key] = dict(owner_cfg)
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
            templates[name] = merged

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
            templates["default"] = merged
    if not templates:
        templates["default"] = dict(MODE_DEFAULT_CONFIGS["growth"])

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
    return result


def _resolve_template_for_mode_alias(
    owner_username: str,
    data: dict,
    mode: str,
    alias: str,
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
    alias_map_all = data.get("alias_template_by_mode", {})
    raw_ref = ""
    # 互点模式：若别名为 mutual 队列单独绑定了模板，优先使用（含 mutual_comment_texts）；
    # 否则仍按涨粉 alias 绑定解析（与旧客户端兼容）。
    if mode_key == "mutual":
        mutual_map = alias_map_all.get("mutual") if isinstance(alias_map_all, dict) else None
        if isinstance(mutual_map, dict):
            cand = str(mutual_map.get(alias_clean) or "").strip()
            if cand in templates:
                raw_ref = cand
    template_mode_key = "growth" if mode_key == "mutual" else mode_key
    if not raw_ref:
        mode_map = alias_map_all.get(template_mode_key) if isinstance(alias_map_all, dict) else {}
        raw_ref = str(mode_map.get(alias_clean) or "").strip() if isinstance(mode_map, dict) else ""
    cross_mode_hit = ""
    # 兜底：若当前模式未配置该 alias，尝试复用其它模式同 alias 的模板，避免误回退到 active_template。
    if not raw_ref and isinstance(alias_map_all, dict) and alias_clean:
        probe_modes = [template_mode_key, "mutual", "growth", "live", "direct"]
        for probe_mode in probe_modes:
            if probe_mode == template_mode_key:
                continue
            probe_map = alias_map_all.get(probe_mode)
            if not isinstance(probe_map, dict):
                continue
            probe_ref = str(probe_map.get(alias_clean) or "").strip()
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
    print(
        f"[{now_text()}] 模板解析 | owner={owner_username} mode={mode_key} alias={alias_clean} raw_ref={raw_ref or '-'} cross_mode_hit={cross_mode_hit or '-'} selected={selected_name} fallback={fallback_name} templates={','.join(sorted(templates.keys()))}"
    )
    return selected_name, config, templates, template_meta


def _user_auto_start_enabled(username: str) -> bool:
    data = _normalize_user_runtime_state(_load_user_runtime_state(username))
    raw = data.get("auto_start_enabled")
    return bool(raw) if isinstance(raw, bool) else False


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
    return (
        isinstance(exc, OSError)
        and getattr(exc, "winerror", None) == 64
        and ("accept_coro" in raw or "finish_accept" in raw or "specified network name is no longer available" in raw or "指定的网络名不可再用" in raw)
    )

@app.on_event("startup")
async def install_asyncio_exception_filter() -> None:
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
    path = request.url.path
    print(f"[{now_text()}] HTTP {request.method} {path} | enter")
    try:
        # 管理台核心接口：避免因数据库锁/偶发阻塞导致前端长期 pending。
        if path in ("/api/admin/overview", "/api/auth/me"):
            print(f"[{now_text()}] HTTP {request.method} {path} | call_next_start | timeout=20s")
            response = await asyncio.wait_for(call_next(request), timeout=20.0)
            print(f"[{now_text()}] HTTP {request.method} {path} | call_next_done")
        else:
            print(f"[{now_text()}] HTTP {request.method} {path} | call_next_start")
            response = await call_next(request)
            print(f"[{now_text()}] HTTP {request.method} {path} | call_next_done")
    except asyncio.TimeoutError:
        elapsed_ms = (time.perf_counter() - started) * 1000
        print(f"[{now_text()}] HTTP {request.method} {request.url.path} | 504 | {elapsed_ms:.1f}ms | TIMEOUT")
        return JSONResponse(
            status_code=504,
            content={"code": 1, "msg": "服务繁忙：请求超时，请稍后重试"},
        )
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
    """设备端上报：更新该设备 auto_start_opt_out（互点/互点评论会话收尾时恢复自动开启）。"""
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
    _persist_client_device_to_db(did)
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


class TemplateGrantUpsertRequest(BaseModel):
    target_username: str = Field(..., min_length=1, max_length=64)
    source_username: str = Field(default="", max_length=64)
    template_name: str = Field(..., min_length=1, max_length=64)
    enabled: bool = True


class AutoStartRequest(BaseModel):
    enabled: bool


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


class DeviceEditorRowDeleteRequest(BaseModel):
    row_index: int = Field(..., ge=0)


class DeviceEditorSwitchHighlightRequest(BaseModel):
    row_index: int = Field(..., ge=0)


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


class MutualClickRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=64)
    alias: str = Field(..., min_length=1, max_length=64)
    package_name: str = Field(..., min_length=1, max_length=255)


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
    favorite_daily_limit: int | None = None
    share_daily_limit: int | None = None
    action_single: bool | None = None
    following_filter_min_video_count: int | None = None
    following_filter_max_following_count: int | None = None
    pull_batch_size: int | None = None
    pull_batches_before_rest: int | None = None
    process_queue_batch_size: int | None = None
    comment_texts: List[str] | None = None
    mutual_comment_texts: List[str] | None = None
    mutual_traffic_target_texts: List[str] | None = None
    profile_edit_bio_texts: List[str] | None = None
    mutual_videos_scroll_rounds: int | None = None
    mutual_follow_tap_session_limit: int | None = None
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
            raise ValueError("growth_pull_batches_before_auto_mutual 必须在 -1~10000 之间（-1=关闭自动互点）")
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
    conn = _get_db()
    try:
        print(f"[{now_text()}] auth.session | lookup_start")
        row = conn.execute(
            "SELECT token, username, role, expires_at FROM auth_sessions WHERE token = ?",
            (token,),
        ).fetchone()
        if not row:
            print(f"[{now_text()}] auth.session | token_not_found | {(time.perf_counter() - started) * 1000:.1f}ms")
            return None
        if str(row[3] or '') < now_text():
            conn.execute("DELETE FROM auth_sessions WHERE token = ?", (token,))
            conn.commit()
            print(f"[{now_text()}] auth.session | expired | {(time.perf_counter() - started) * 1000:.1f}ms")
            return None
        user = conn.execute(
            "SELECT username, role, is_active FROM auth_users WHERE username = ?",
            (row[1],),
        ).fetchone()
        if not user or int(user[2] or 0) != 1:
            print(f"[{now_text()}] auth.session | user_inactive | {(time.perf_counter() - started) * 1000:.1f}ms")
            return None
        print(f"[{now_text()}] auth.session | ok | {(time.perf_counter() - started) * 1000:.1f}ms user={row[1]}")
        return {"token": row[0], "username": row[1], "role": row[2]}
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


def auth_or_error(device_id: str, alias: str):
    device = store.verify_device(device_id, alias)
    if not device:
        return {"code": 2, "msg": "device_id与别名不匹配/设备未绑定"}, None
    return None, device


def _session_device_ids_from_fallback(session: dict) -> set[str]:
    """store.lock 抢不到时，用上一次成功概览缓存的设备表，避免 admin 得到空集合整页无设备。"""
    cached_devices = _overview_fallback_snapshot.get("devices") or {}
    if not isinstance(cached_devices, dict):
        return set()
    if session.get("role") == "admin":
        return {str(k) for k in cached_devices.keys()}
    owner = session["username"]
    out: set[str] = set()
    for did, entry in cached_devices.items():
        if isinstance(entry, dict) and str(entry.get("owner_username") or "") == owner:
            out.add(str(did))
    return out


def _session_device_ids(session: dict, *, lock_wait_sec: float = 2.0) -> set[str]:
    """管理台当前用户可见的设备 ID 集合：管理员为全部，普通客户仅本人 owner_username。"""
    if session.get("role") == "admin":
        conn = _get_db()
        try:
            rows = conn.execute("SELECT device_id FROM client_devices").fetchall()
            return {str(r[0]) for r in rows}
        except sqlite3.OperationalError:
            print(f"[{now_text()}] session_device_ids | admin_db_failed | using_overview_fallback_devices")
            return _session_device_ids_from_fallback(session)
        finally:
            conn.close()

    if not store.lock.acquire(timeout=lock_wait_sec):
        print(
            f"[{now_text()}] session_device_ids | store_lock_timeout | wait_sec={lock_wait_sec} role=customer "
            "| using_overview_fallback_devices"
        )
        return _session_device_ids_from_fallback(session)
    try:
        owner = session["username"]
        return {did for did, d in store.devices.items() if d.owner_username == owner}
    finally:
        store.lock.release()


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


def _backup_record_alias_expr() -> str:
    return "COALESCE(NULLIF(TRIM(assigned_alias), ''), device_alias)"


def _prune_orphan_device_editor_rows(conn: sqlite3.Connection, owner_username: str) -> int:
    """删除账号记录已不存在、但 device_editor_rows 仍残留的备份档案（脏数据）。"""
    owner = str(owner_username or "").strip()
    if not owner:
        return 0
    cur = conn.execute(
        """
        DELETE FROM device_editor_rows
        WHERE owner_username = ?
          AND TRIM(username) <> ''
          AND LOWER(TRIM(username)) NOT IN (
            SELECT LOWER(TRIM(username))
            FROM account_editor_rows
            WHERE owner_username = ? AND TRIM(username) <> ''
          )
        """,
        (owner, owner),
    )
    return int(cur.rowcount or 0)


def _normalize_switch_highlight(value: object | None) -> str:
    """切号高亮：仅允许「是」或「否」，其余视为否。"""
    return "是" if str(value or "").strip() == "是" else "否"


def _ensure_device_editor_switch_highlight_invariants(conn: sqlite3.Connection, owner_username: str) -> None:
    """
    切号高亮不变量（与前端约定一致）：
    - 同一 owner 下，按「COALESCE(TRIM(assigned_alias), TRIM(device_alias))」分桶（与档案别名口径一致）；
    - 每桶仅 1 条：强制 switch_highlight = 是；
    - 每桶 ≥2 条：有且仅有一条为「是」；若 0 条为是则取最小 row_index 为是，若多条为是则保留其中最小 row_index 为是。
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
    for _key, grp in buckets.items():
        if len(grp) == 1:
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
            continue
        indices = [int(r["row_index"]) for r in grp]
        yes_rows = [r for r in grp if _normalize_switch_highlight(r.get("switch_highlight")) == "是"]
        if len(yes_rows) == 1:
            winner = int(yes_rows[0]["row_index"])
        elif len(yes_rows) == 0:
            # 多条且均为「否」：允许（切号流程中「备份并清理」已清掉高亮、还原尚未回传前不应自动点亮任一条）
            continue
        else:
            winner = min(int(r["row_index"]) for r in yes_rows)
        for r in grp:
            rid = int(r["row_index"])
            val = "是" if rid == winner else "否"
            if _normalize_switch_highlight(r.get("switch_highlight")) != val:
                conn.execute(
                    """
                    UPDATE device_editor_rows
                    SET switch_highlight = ?, updated_at = ?
                    WHERE owner_username = ? AND row_index = ?
                    """,
                    (val, now, owner_username, rid),
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


def _device_ops_apply_cut_switch_after_backup(owner_username: str, data: dict) -> None:
    """设备回传「备份并清理」成功时，按切号意图更新 switch_highlight（不依赖管理台轮询）。"""
    mode = str(data.get("cut_switch_after_backup") or "").strip().lower()
    if mode not in ("clear_source_for_restore", "exclusive_activate_target"):
        return
    conn = _get_db()
    try:
        now = now_text()
        if mode == "clear_source_for_restore":
            br = data.get("backup_record_id")
            try:
                rid = int(br) if br is not None else 0
            except (TypeError, ValueError):
                rid = 0
            if rid > 0:
                conn.execute(
                    """
                    UPDATE device_editor_rows
                    SET switch_highlight = '否', updated_at = ?
                    WHERE owner_username = ? AND row_index = ?
                    """,
                    (now, owner_username, rid),
                )
        elif mode == "exclusive_activate_target":
            tr = data.get("cut_switch_target_row_index")
            try:
                tid = int(tr) if tr is not None else -1
            except (TypeError, ValueError):
                tid = -1
            if tid >= 0:
                _db_apply_switch_highlight_exclusive(conn, owner_username, tid, now)
        _ensure_device_editor_switch_highlight_invariants(conn, owner_username)
        conn.commit()
    finally:
        conn.close()


def _device_ops_apply_cut_switch_after_restore(owner_username: str, data: dict) -> None:
    """设备回传「还原」成功时，将对应档案行设为切号高亮。"""
    try:
        tid = int(data.get("restore_highlight_row_index")) if data.get("restore_highlight_row_index") is not None else -1
    except (TypeError, ValueError):
        tid = -1
    if tid < 0:
        bn = str(data.get("backup_name") or "").strip()
        if not bn:
            return
        conn = _get_db()
        try:
            row = conn.execute(
                """
                SELECT row_index FROM device_editor_rows
                WHERE owner_username = ? AND backup_name = ?
                ORDER BY updated_at DESC, row_index DESC
                LIMIT 1
                """,
                (owner_username, Path(bn).name if bn else ""),
            ).fetchone()
            tid = int(row[0]) if row else -1
        finally:
            conn.close()
    if tid < 0:
        return
    conn = _get_db()
    try:
        now = now_text()
        _db_apply_switch_highlight_exclusive(conn, owner_username, tid, now)
        _ensure_device_editor_switch_highlight_invariants(conn, owner_username)
        conn.commit()
    finally:
        conn.close()


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
            f"""
            UPDATE device_editor_rows
            SET backup_path = ?, backup_name = ?, backup_time = ?, backup_status = ?, updated_at = ?
            WHERE username = ? AND {_backup_record_alias_expr()} = ?
            """,
            (
                backup_path,
                backup_name or "",
                now,
                "已备份",
                now,
                username,
                mobile_alias,
            ),
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


def _link_backup_record_alias_for_username(username: str, assigned_alias: str) -> int:
    uname = _normalized_alias(username).lstrip("@").lower()
    alias = _normalized_alias(assigned_alias)
    if not uname or not alias:
        return 0
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT row_index, assigned_alias
            FROM device_editor_rows
            WHERE LOWER(username) = ?
            ORDER BY updated_at DESC, row_index DESC
            """,
            (uname,),
        ).fetchall()
        now = now_text()
        touched = 0
        for row in rows:
            current = _normalized_alias(row["assigned_alias"])
            if current and current != alias:
                continue
            cur = conn.execute(
                """
                UPDATE device_editor_rows
                SET assigned_alias = ?, updated_at = ?
                WHERE row_index = ? AND COALESCE(NULLIF(TRIM(assigned_alias), ''), device_alias) IN ('', ?)
                """,
                (alias, now, int(row["row_index"]), alias),
            )
            touched += int(cur.rowcount or 0)
        conn.commit()
        return touched
    finally:
        conn.close()


def _upsert_backup_record_for_account(
    owner_username: str,
    username: str,
    assigned_alias: str,
    region: str = "",
) -> int:
    owner = _normalized_alias(owner_username)
    uname = _normalized_alias(username).lstrip("@")
    alias = _normalized_alias(assigned_alias)
    region_text = _normalized_alias(region)
    if not owner or not uname or not alias:
        return 0
    conn = _get_db()
    try:
        now = now_text()
        existing = conn.execute(
            f"""
            SELECT row_index
            FROM device_editor_rows
            WHERE owner_username = ? AND LOWER(username) = LOWER(?) AND {_backup_record_alias_expr()} = ?
            ORDER BY updated_at DESC, row_index DESC
            LIMIT 1
            """,
            (owner, uname, alias),
        ).fetchone()
        if existing:
            rid = int(existing["row_index"])
            cur = conn.execute(
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
            conn.commit()
            return int(cur.rowcount or 0)

        max_row = conn.execute(
            "SELECT COALESCE(MAX(row_index), -1) FROM device_editor_rows WHERE owner_username = ?",
            (owner,),
        ).fetchone()[0]
        row_index = int(max_row) + 1
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, switch_highlight, updated_at)
            VALUES (?, ?, ?, '', '', ?, ?, ?, '', '', '', '', '', '否', ?)
            """,
            (owner, row_index, alias, region_text, uname, alias, now),
        )
        conn.commit()
        return 1
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
        now = now_text()
        cur = conn.execute(
            """
            UPDATE device_editor_rows
            SET backup_path = ?, backup_name = ?, backup_time = ?, backup_status = ?, updated_at = ?
            WHERE row_index = ?
            """,
            (
                backup_path,
                backup_name or "",
                now,
                "已备份",
                now,
                rid,
            ),
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
               '' AS video_count, '' AS follower_count, '' AS new_follower_count,
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
    with _tracked_store_lock("_next_device_op"):
        q = store.pending_device_ops.get(device_id) or []
        if not q:
            print(f"[{now_text()}] device_ops.poll | next_op_empty | device_id={device_id}")
            return None
        op = q.pop(0)
        print(f"[{now_text()}] device_ops.poll | next_op_pop | device_id={device_id} op_type={op.get('type')}")
        return op


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
        "label": "自动搜索",
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


def fetch_tiktok_profile_stats(username: str, include_raw: bool = False):
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

    print(
        f"[{now_text()}] TikTok stats | username={username} | universal_extract={extract_dict} | "
        f"private_guess={is_private_guess} | reason={private_reason or '-'}"
    )

    result = {
        "code": 0,
        "msg": "ok",
        "username": username,
        "universal_extract": extract_dict,
        "private_account_guess": is_private_guess,
        "private_account_reason": private_reason,
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
    conn = _get_db()
    try:
        row = conn.execute("SELECT username, password_hash, role, is_active FROM auth_users WHERE username = ?", (username,)).fetchone()
        if not row or int(row[3] or 0) != 1 or not _verify_password(password, row[1]):
            return {"code": 1, "msg": "用户名或密码错误"}
        token = _new_session_token()
        expires = (datetime.now().replace(microsecond=0)).isoformat(sep=' ')
        # last_login_at 仅用于审计，失败不应拖慢或阻断登录主流程。
        try:
            conn.execute("UPDATE auth_users SET last_login_at = ? WHERE username = ?", (now_text(), username))
        except Exception as exc:
            print(f"[{now_text()}] 登录审计写入失败（last_login_at）：{exc}")
        conn.execute(
            "INSERT INTO auth_sessions (token, username, role, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            (token, username, row[2], now_text(), "2999-12-31 23:59:59"),
        )
        conn.commit()
        return {"code": 0, "msg": "登录成功", "token": token, "role": row[2], "username": username}
    finally:
        conn.close()


@app.post("/api/login")
def auth_login_legacy(payload: dict):
    """兼容旧客户端：等价于 /api/auth/login。"""
    return auth_login(payload)


@app.get("/api/login")
def auth_login_legacy_get():
    """兼容误发起的 GET /api/login，避免前端控制台出现 404。"""
    return {"code": 1, "msg": "请使用 POST /api/auth/login"}


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
            "SELECT device_id, owner_username, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out FROM client_devices WHERE device_id = ?",
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
                    "UPDATE client_devices SET owner_username = ?, alias = ?, device_group = ?, auto_start_opt_out = 1, updated_at = ? WHERE device_id = ?",
                    (owner, alias, row_group, now_text(), existing_id),
                )
                conn.commit()
                _hydrate_device_from_db(existing_id)
                print(f"[{now_text()}] 系统 | 设备转绑成功：{old_alias}/{row_owner} -> {alias}/{owner}（device_id={existing_id}）")
                return {"code": 0, "msg": "绑定成功", "device_id": existing_id}
            if old_alias != alias:
                conn.execute(
                    "UPDATE client_devices SET alias = ?, device_group = ?, auto_start_opt_out = 1, updated_at = ? WHERE device_id = ?",
                    (alias, row_group, now_text(), existing_id),
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
            INSERT INTO client_devices (device_id, owner_username, alias, device_group, fingerprint, status, pending_start, paused, auto_start_opt_out, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'offline', 0, 0, 1, ?, ?)
            """,
            (device_id, owner, alias, row_group, fp, t, t),
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
    error, device = auth_or_error(device_id, alias)
    if error:
        return error
    data = _normalize_user_runtime_state(_load_user_runtime_state(device.owner_username))
    mode = run_mode if run_mode in RUN_MODES else data.get("run_mode", "growth")
    mode = mode if mode in RUN_MODES else "growth"
    active_template, config, templates, template_meta = _resolve_template_for_mode_alias(
        device.owner_username,
        data,
        mode,
        alias,
    )
    alias_clean = _normalize_alias_key(alias)
    alias_map_all = data.get("alias_template_by_mode", {})
    mode_map = alias_map_all.get(mode) if isinstance(alias_map_all, dict) else {}
    mode_raw_ref = str(mode_map.get(alias_clean) or "").strip() if isinstance(mode_map, dict) else ""
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
        active_template = "default" if "default" in templates else sorted(templates.keys())[0]
        owner, name, is_shared = _parse_template_ref(active_template)
        if not is_shared or owner == session["username"]:
            data["active_template"] = name if is_shared else active_template
            _save_user_runtime_state(session["username"], data)
    resp = dict(data)
    resp["active_template"] = active_template
    resp["configs_by_template"] = templates
    resp["template_meta"] = template_meta
    resp["alias_template_by_mode"] = data.get(
        "alias_template_by_mode",
        {"growth": {}, "live": {}, "direct": {}, "mutual": {}},
    )
    return {"code": 0, "data": resp}


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
    mode_map[alias] = requested
    alias_map_all[mode] = mode_map
    data["alias_template_by_mode"] = alias_map_all
    _save_user_runtime_state(target_username, data)
    print(
        f"[{now_text()}] 模板映射 | operator={session['username']} user={target_username} mode={mode} alias={alias} raw_alias={alias_raw} template={requested}"
    )
    return {
        "code": 0,
        "msg": "别名模板已更新",
        "owner_username": target_username,
        "mode": mode,
        "alias": alias,
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
    with _tracked_store_lock("check_start"):
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
    with store.lock:
        push_rev = int(store.user_config_push_revision.get(device.owner_username, 0))
    result["config_push_revision"] = push_rev
    if mutated:
        _persist_client_device_to_db(device_id)
    print(f"[{now_text()}] check_start | done | device_id={device_id} result={result.get('msg')}")
    return result


@app.get("/api/pull_username")
def pull_username(device_id: str, alias: str, count: int | None = None, run_mode: str | None = None):
    error, device = auth_or_error(device_id, alias)
    if error:
        print(f"[{now_text()}] pull_username | auth_failed | device_id={device_id} alias={alias}")
        return error

    promoted = False
    with _tracked_store_lock("pull_username"):
        if device.status != "running":
            if device.pending_start:
                device.pending_start = False
                device.status = "running"
                promoted = True
            else:
                print(f"[{now_text()}] pull_username | not_running | device_id={device_id}")
                return {"code": 3, "msg": "设备未启动，请先从云端一键启动"}

    if promoted:
        _persist_client_device_to_db(device_id)

    # 设备 owner 的运行配置（含模板合并/共享模板）应与 get_config 一致；
    # pull_username 用它来决定 pull_batch_size、休息前批次数与休息策略。
    data = _normalize_user_runtime_state(_load_user_runtime_state(device.owner_username))
    mode = run_mode if run_mode in RUN_MODES else data.get("run_mode", "growth")
    active_template, eff_config, templates, _template_meta = _resolve_template_for_mode_alias(
        device.owner_username,
        data,
        mode,
        alias,
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

    # ——按“拉取批次”触发休息（完成 N 批后，第 N+1 次拉取返回休息指令）——
    with store.lock:
        rounds = int(store.device_pull_rounds.get(device_id, 0) or 0) + 1
        store.device_pull_rounds[device_id] = rounds
        should_rest = rounds > batches_before_rest
        if should_rest:
            store.device_pull_rounds[device_id] = 0

    if should_rest:
        try:
            bmin = int(eff_config.get("break_min", 10) or 10)
        except Exception:
            bmin = 10
        try:
            bmax = int(eff_config.get("break_max", 20) or 20)
        except Exception:
            bmax = 20
        bmin = max(1, min(bmin, 24 * 60))
        bmax = max(1, min(bmax, 24 * 60))
        lo = min(bmin, bmax)
        hi = max(bmin, bmax)
        rest_minutes = random.randint(lo, hi)
        return {
            "code": 2,
            "msg": f"触发休息：{rest_minutes} 分钟（强制关闭 TikTok 后台）",
            "rest_minutes": rest_minutes,
            "force_stop_tiktok": True,
        }

    consumed_mobile_pending = False
    selected: List[str] = []
    conn = _get_db()
    try:
        with store.lock:
            # 兼容旧逻辑：mode 已在上方由用户配置/请求参数决定，这里仅防并发读写。
            mode = mode if mode in RUN_MODES else store.run_mode

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
            consumed_mobile_pending = True
            rows = conn.execute(
                "SELECT id, username FROM mobile_pending_usernames WHERE consume_status = 'pending' ORDER BY created_at, id LIMIT ?",
                (safe_count,),
            ).fetchall()
            if not rows:
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

    print(f"[{now_text()}] 设备别名：{alias} | 拉取用户名：{selected}")
    return {"code": 0, "usernames": selected}


@app.post("/api/report_mutual_copied_username")
def report_mutual_copied_username(payload: ReportMutualCopiedUsernameRequest):
    """互点复制作者用户名入库。表中唯一字段仅 username（主键）；created_at 首次写入，usage_count 按次累加。"""
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    normalized = _normalize_mutual_clip_username(payload.username or "")
    if not normalized:
        return {"code": 1, "msg": "用户名为空或格式无效"}

    conn = _get_db()
    try:
        ts = now_text()
        conn.execute(
            """
            INSERT INTO mutual_click_copied_usernames (username, created_at, usage_count)
            VALUES (?, ?, 1)
            ON CONFLICT(username) DO UPDATE SET
                usage_count = mutual_click_copied_usernames.usage_count + 1
            """,
            (normalized, ts),
        )
        conn.commit()
        row = conn.execute(
            "SELECT username, created_at, usage_count FROM mutual_click_copied_usernames WHERE username = ?",
            (normalized,),
        ).fetchone()
        uc = int(row["usage_count"]) if row else 1
        return {"code": 0, "msg": "已记录", "username": normalized, "usage_count": uc}
    finally:
        conn.close()


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
        _persist_client_device_to_db(payload.device_id)

    return {"code": 0, "msg": "结果接收成功"}


@app.post("/api/device_ops/poll")
def device_ops_poll(payload: DeviceOpPollRequest):
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


@app.post("/api/admin/device_tiktok/mutual_click_start")
def admin_device_tiktok_mutual_click_start(payload: MutualClickRequest, authorization: str | None = Header(default=None)):
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
        _append_device_log(payload.device_id, "互点指令：云端已自动关闭该设备的自动开启程序")
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
        "msg": "已下发设备端任务：进入互点模式（已自动关闭该设备自动开启程序）" if auto_start_disabled else "已下发设备端任务：进入互点模式",
        "op_id": op_id,
        "auto_start_opt_out": True if auto_start_disabled else bool(getattr(_device, "auto_start_opt_out", False)),
    }


@app.post("/api/admin/device_tiktok/mutual_click_comment_only_start")
def admin_device_tiktok_mutual_click_comment_only_start(
    payload: MutualClickRequest, authorization: str | None = Header(default=None)
):
    """互点「仅评论」：与完整互点相同的 teamwork 进作品与点赞/收藏/评论，不下发左滑与作者主页链路。"""
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
        _append_device_log(payload.device_id, "互点仅评论指令：云端已自动关闭该设备的自动开启程序")
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
        "msg": "已下发设备端任务：进入互点仅评论模式（已自动关闭该设备自动开启程序）"
        if auto_start_disabled
        else "已下发设备端任务：进入互点仅评论模式",
        "op_id": op_id,
        "auto_start_opt_out": True if auto_start_disabled else bool(getattr(_device, "auto_start_opt_out", False)),
    }


@app.post("/api/admin/device_tiktok/publish_post_start")
def admin_device_tiktok_publish_post_start(payload: MutualClickRequest, authorization: str | None = Header(default=None)):
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
        _append_device_log(payload.device_id, "发作品指令：云端已自动关闭该设备的自动开启程序")
    _resume_device_for_cloud_op(
        payload.device_id,
        f"[{now_text()}] 云端后台 | 已下发发作品指令，设备由暂停切换为 running（alias={payload.alias}）",
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
        "msg": "已下发设备端任务：发作品入口（已自动关闭该设备自动开启程序）" if auto_start_disabled else "已下发设备端任务：发作品入口",
        "op_id": op_id,
        "auto_start_opt_out": True if auto_start_disabled else bool(getattr(_device, "auto_start_opt_out", False)),
    }


@app.post("/api/device_ops/report")
def device_ops_report(payload: DeviceOpReportRequest):
    error, device = auth_or_error(payload.device_id, payload.alias)
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
        _pe_op = str(data.get("op_type") or "").strip() == "tiktok_edit_basic_profile"
        _pe_ok = bool(data.get("profile_edit_success"))
        _pe_p1 = data.get("profile_edit_bio_after_part1_ok")
        _pe_p2 = data.get("profile_edit_bio_after_part2_ok")
        if _pe_p1 is not None and _pe_p2 is not None:
            _pe_parts_ok = bool(_pe_p1) and bool(_pe_p2)
        else:
            _pe_parts_ok = _pe_ok
        if _pe_op and _pe_ok and _pe_parts_ok:
            owner_u = str(device.owner_username or "").strip()
            alias_key = _normalize_alias_key(payload.alias)
            if owner_u and alias_key:
                conn = _get_db()
                try:
                    cur = conn.execute(
                        """
                        UPDATE account_editor_rows
                        SET basic_profile_modified = '是', updated_at = ?
                        WHERE owner_username = ?
                          AND LOWER(TRIM(COALESCE(assigned_alias, ''))) = ?
                        """,
                        (now_text(), owner_u, alias_key),
                    )
                    conn.commit()
                    print(
                        f"[{now_text()}] account_editor_rows | basic_profile_modified=是 "
                        f"| owner={owner_u} alias={alias_key} rowcount={cur.rowcount}"
                    )
                finally:
                    conn.close()

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

    if payload.status == "success" and isinstance(data, dict):
        owner_u = str(device.owner_username or "").strip()
        op_type = str(data.get("op_type") or "").strip()
        if owner_u:
            if op_type == "tiktok_backup_and_clear":
                _device_ops_apply_cut_switch_after_backup(owner_u, data)
            elif op_type == "tiktok_restore":
                _device_ops_apply_cut_switch_after_restore(owner_u, data)

    return {"code": 0, "msg": "设备任务结果已接收"}


@app.post("/api/device_logs/sync")
def device_logs_sync(payload: DeviceLogSyncRequest):
    error, _device = auth_or_error(payload.device_id, payload.alias)
    if error:
        return error

    cleaned = [str(x).strip() for x in (payload.logs or []) if str(x).strip()]
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
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, switch_highlight, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, '否', ?)
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

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_backup_and_clear",
        op_payload,
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：备份并一键清理 TikTok（已自动关闭该设备自动开启程序）" if auto_start_disabled else "已下发设备端任务：备份并一键清理 TikTok",
        "op_id": op_id,
        "auto_start_opt_out": True if auto_start_disabled else bool(getattr(_device, "auto_start_opt_out", False)),
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
    err, _device = _require_login_and_owning_device(payload.device_id, payload.alias, authorization)
    if err:
        return err

    op_id = _enqueue_tiktok_device_op(
        payload.device_id,
        "tiktok_edit_basic_profile",
        {
            "device_id": payload.device_id,
            "alias": payload.alias,
            "package_name": payload.package_name.strip(),
        },
    )

    return {
        "code": 0,
        "msg": "已下发设备端任务：修改基本资料（关→开→比例点→OCR Edit→OCR Change photo,name,Bio，失败自动整段重试）",
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
        rows = conn.execute(
            """
            SELECT row_index, device_alias, device_id, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, switch_highlight, updated_at
            FROM device_editor_rows
            WHERE owner_username = ?
            ORDER BY row_index ASC
            """,
            (session["username"],),
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
            _prune_orphan_device_editor_rows(conn, owner)
            conn.commit()
        clauses = []
        params: List[Any] = []
        if username.strip():
            clauses.append("username LIKE ?")
            params.append(f"%{username.strip()}%")
        if mobile_alias.strip():
            clauses.append(f"{_backup_record_alias_expr()} = ?")
            params.append(mobile_alias.strip())
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
        rows = conn.execute(
            """
            SELECT row_index, username, password, email, email_password, api, is_login, login_time, region, assigned_alias, video_count, follower_count, follower_growth, basic_profile_modified
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

        def _blank_if_none(text: object | None) -> str:
            if text is None:
                return ""
            return str(text).strip()

        row_index = int(payload.row_index)
        incoming_alias = _blank_if_none(payload.assigned_alias)
        existing = conn.execute(
            """
            SELECT username, assigned_alias
            FROM account_editor_rows
            WHERE owner_username = ? AND row_index = ?
            LIMIT 1
            """,
            (session["username"], row_index),
        ).fetchone()
        existing_username = str(existing["username"] or "").strip() if existing else ""
        existing_assigned_alias = _blank_if_none(existing["assigned_alias"]) if existing else ""
        alias_locked = bool(existing_assigned_alias)
        if existing and alias_locked and incoming_alias and incoming_alias != existing_assigned_alias:
            return {"code": 1, "msg": "该账号已分配别名，不能修改别名；删除账号记录将同步清理设备备份档案"}
        persisted_alias = existing_assigned_alias or incoming_alias
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
                api = excluded.api,
                is_login = excluded.is_login,
                login_time = excluded.login_time,
                region = excluded.region,
                assigned_alias = CASE
                    WHEN TRIM(COALESCE(account_editor_rows.assigned_alias, '')) <> '' THEN account_editor_rows.assigned_alias
                    ELSE excluded.assigned_alias
                END,
                video_count = COALESCE(excluded.video_count, ''),
                follower_count = COALESCE(excluded.follower_count, ''),
                follower_growth = COALESCE(excluded.follower_growth, ''),
                basic_profile_modified = COALESCE(excluded.basic_profile_modified, '否'),
                updated_at = excluded.updated_at
            """,
            (
                session["username"],
                row_index,
                payload.username.strip(),
                payload.password.strip(),
                payload.email.strip(),
                payload.email_password.strip(),
                payload.api.strip(),
                1 if payload.is_login else 0,
                payload.login_time.strip(),
                payload.region.strip(),
                persisted_alias,
                _blank_if_none(payload.video_count),
                _blank_if_none(payload.follower_count),
                _blank_if_none(payload.follower_growth),
                _normalize_switch_highlight(payload.basic_profile_modified),
                now,
            ),
        )
        conn.commit()
        if persisted_alias and existing_username:
            _link_backup_record_alias_for_username(existing_username, persisted_alias)
        if persisted_alias:
            _upsert_backup_record_for_account(
                session["username"],
                payload.username,
                persisted_alias,
                payload.region,
            )
        return {"code": 0, "msg": "账号记录已保存", "row_index": payload.row_index, "assigned_alias_locked": alias_locked or bool(persisted_alias)}
    finally:
        conn.close()


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
            return {"code": 1, "msg": "账号记录不存在或已删除"}
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
        if int(account_cur.rowcount or 0) > 0:
            conn.execute(
                """
                UPDATE account_editor_rows
                SET row_index = row_index - 1, updated_at = ?
                WHERE owner_username = ? AND row_index > ?
                """,
                (now_text(), owner, row_index),
            )
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
        return {
            "code": 0,
            "msg": "账号记录已删除",
            "row_index": row_index,
            "username": username,
            "deleted_accounts": int(account_cur.rowcount or 0),
            "deleted_device_rows": deleted_device_rows,
        }
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
    conn = _get_db()
    try:
        owner = str(session["username"] or "").strip()
        _ensure_device_editor_switch_highlight_invariants(conn, owner)
        pruned = _prune_orphan_device_editor_rows(conn, owner)
        if pruned:
            _ensure_device_editor_switch_highlight_invariants(conn, owner)
        conn.commit()
        count_row = conn.execute(
            f"""
            SELECT COUNT(DISTINCT {alias_expr})
            FROM device_editor_rows
            WHERE owner_username = ? AND TRIM({alias_expr}) <> ''{group_sql}
            """,
            (owner, *group_params),
        ).fetchone()
        total_aliases = int(count_row[0] if count_row else 0)
        alias_rows = conn.execute(
            f"""
            SELECT DISTINCT {alias_expr} AS eff_alias
            FROM device_editor_rows
            WHERE owner_username = ? AND TRIM({alias_expr}) <> ''{group_sql}
            ORDER BY eff_alias ASC
            LIMIT ? OFFSET ?
            """,
            (owner, *group_params, safe_size, offset),
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
            }
        placeholders = ",".join("?" for _ in page_aliases)
        rows = conn.execute(
            f"""
            SELECT row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, switch_highlight, updated_at
            FROM device_editor_rows
            WHERE owner_username = ? AND {alias_expr} IN ({placeholders})
            ORDER BY {alias_expr} ASC, row_index ASC
            """,
            (owner, *page_aliases),
        ).fetchall()
        return {
            "code": 0,
            "items": [dict(r) for r in rows],
            "page": safe_page,
            "page_size": safe_size,
            "total": total_aliases,
            "alias_page_aliases": page_aliases,
            "device_groups": _list_distinct_device_groups(session),
        }
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
        assigned_alias = _blank_if_none(payload.assigned_alias)
        if assigned_alias:
            alias_row = conn.execute(
                """
                SELECT username
                FROM account_editor_rows
                WHERE owner_username = ? AND TRIM(assigned_alias) = TRIM(?)
                LIMIT 1
                """,
                (session["username"], assigned_alias),
            ).fetchone()
            if not alias_row:
                return {"code": 1, "msg": f"别名「{assigned_alias}」尚未在账号表单中分配，不能写入设备档案"}

        sh = _normalize_switch_highlight(payload.switch_highlight)
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, device_id, ip, region, username, assigned_alias, backup_path, backup_name, backup_time, backup_status, remark, switch_highlight, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                assigned_alias,
                payload.backup_path.strip(),
                payload.backup_name.strip(),
                payload.backup_time.strip(),
                payload.backup_status.strip(),
                payload.remark.strip(),
                sh,
                now,
            ),
        )
        _ensure_device_editor_switch_highlight_invariants(conn, str(session["username"] or "").strip())
        conn.commit()
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
            SELECT row_index, assigned_alias, device_alias
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
        return {"code": 0, "msg": "切号高亮已更新", "row_index": row_index}
    finally:
        conn.close()


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
        backup_path = str(row[1] or "").strip() if row else ""
        record_username = str(row[2] or "").strip() if row else ""
        record_mobile_alias = str(row[3] or "").strip() if row else ""
        record_assigned_alias = str(row[4] or "").strip() if row else ""
        record_video_count = None
        record_follower_count = None
        record_new_follower_count = None
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
    _enqueue_device_op(
        payload.device_id,
        {
            "op_id": op_id,
            "op_type": "tiktok_restore",
            "payload": restore_payload,
            "created_at": now_text(),
        },
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
    }


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
    if len(files) > 20:
        return {"code": 1, "msg": "单次最多上传 20 张图片"}

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
    return fetch_tiktok_profile_stats(payload.username, include_raw=payload.include_raw)


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
    return {
        "code": 0,
        "scheduler_enabled": snapshot["scheduler_enabled"],
        "scheduler_tasks": snapshot["tasks"],
        "running_tasks": running,
        "task_logs": task_logs,
        "auto_search_schedule": "由各任务独立间隔控制",
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
        if safe_search_field:
            if safe_search_field not in allowed[table]:
                return {"code": 1, "msg": f"不支持的搜索字段: {safe_search_field}"}
            where_sql = f"WHERE CAST({safe_search_field} AS TEXT) LIKE ?"
            params.append(f"%{safe_search_keyword}%")
        else:
            like_clauses = [f"CAST({field} AS TEXT) LIKE ?" for field in allowed[table]]
            where_sql = "WHERE " + " OR ".join(like_clauses)
            params.extend([f"%{safe_search_keyword}%"] * len(allowed[table]))

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


def _load_visible_devices_from_db(session: dict, *, device_group: str = "") -> Dict[str, Any]:
    """按权限从 client_devices 读取可见设备列表，可按 device_group 过滤。"""
    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    group_filter = str(device_group or "").strip()
    conn = _get_db()
    try:
        rows = conn.execute(
            "SELECT device_id, owner_username, alias, device_group, status, pending_start, auto_start_opt_out FROM client_devices"
        ).fetchall()
        devices: Dict[str, Any] = {}
        for row in rows:
            device_id = str(row["device_id"])
            if device_id not in allowed:
                continue
            alias = str(row["alias"] or "").strip()
            row_group = str(row["device_group"] or "").strip()
            if group_filter == "__ungrouped__":
                if row_group:
                    continue
            elif group_filter and row_group != group_filter:
                continue
            entry: Dict[str, Any] = {
                "alias": alias,
                "device_group": row_group,
                "status": str(row["status"] or "offline"),
                "pending_start": bool(int(row["pending_start"] or 0)),
                "auto_start_opt_out": bool(int(row["auto_start_opt_out"] or 0)),
            }
            if session.get("role") == "admin":
                entry["owner_username"] = str(row["owner_username"])
            devices[device_id] = entry
        return devices
    finally:
        conn.close()


def _overview_queue_payload_from_db(*, preview_limit: int = 200) -> dict[str, Any]:
    """不持有 store.lock：概览降级时仍返回真实队列计数与待下发预览（避免误显示为 0）。"""
    conn = _get_db()
    try:
        follow_unprocessed = conn.execute(
            "SELECT COUNT(1) FROM follower_queue WHERE COALESCE(following_fetch_success, 0) = 0"
        ).fetchone()[0]
        follow_done = conn.execute(
            "SELECT COUNT(1) FROM follower_queue WHERE COALESCE(following_fetch_success, 0) = 1"
        ).fetchone()[0]
        pending_count = conn.execute(
            "SELECT COUNT(1) FROM mobile_pending_usernames WHERE consume_status = 'pending'"
        ).fetchone()[0]
        pending_preview = [
            str(r[0])
            for r in conn.execute(
                "SELECT username FROM mobile_pending_usernames WHERE consume_status = 'pending' ORDER BY created_at, id LIMIT ?",
                (preview_limit,),
            ).fetchall()
        ]
        live_count = conn.execute("SELECT COUNT(1) FROM live_customer").fetchone()[0]
        return {
            "mobile_pending_total": pending_count,
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
        }
    finally:
        conn.close()


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

    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    t_allowed = _mark(f"session_devices(count={len(allowed)})", t_auth)

    try:
        auto_start_enabled = _user_auto_start_enabled(session["username"])
    except Exception as exc:
        auto_start_enabled = False
        print(f"[{now_text()}] overview.step | auto_start_load_failed | {exc}")
    t_auto_start = _mark("auto_start", t_allowed)

    preview_limit = 200
    safe_page = max(1, int(page or 1))
    safe_size = max(1, min(int(page_size or 50), 500))
    group_filter = str(device_group or "").strip()
    snapshot_started = time.perf_counter()
    conn = _get_db()
    try:
        all_devices = _load_visible_devices_from_db(session, device_group=group_filter)
        all_values = list(all_devices.values())
        device_stats = {
            "online": sum(1 for d in all_values if d.get("status") == "online"),
            "running": sum(1 for d in all_values if d.get("status") == "running"),
            "error": sum(1 for d in all_values if d.get("status") == "error"),
            "pending_start": sum(1 for d in all_values if d.get("pending_start")),
        }
        devices, devices_total = _paginate_dict_items(all_devices, safe_page, safe_size)
        device_logs = _device_logs_dict_for_ids(list(devices.keys()), 120)
        config_snapshot = dict(store.config)
        run_mode_snapshot = store.run_mode
        follow_total = conn.execute("SELECT COUNT(1) FROM follower_queue").fetchone()[0]
        follow_unprocessed = conn.execute("SELECT COUNT(1) FROM follower_queue WHERE COALESCE(following_fetch_success, 0) = 0").fetchone()[0]
        follow_done = conn.execute("SELECT COUNT(1) FROM follower_queue WHERE COALESCE(following_fetch_success, 0) = 1").fetchone()[0]
        pending_count = conn.execute("SELECT COUNT(1) FROM mobile_pending_usernames WHERE consume_status = 'pending'").fetchone()[0]
        pending_preview = [
            str(r[0])
            for r in conn.execute(
                "SELECT username FROM mobile_pending_usernames WHERE consume_status = 'pending' ORDER BY created_at, id LIMIT ?",
                (preview_limit,),
            ).fetchall()
        ]
        live_count = conn.execute("SELECT COUNT(1) FROM live_customer").fetchone()[0]
    finally:
        conn.close()
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
        "device_logs": device_logs,
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
            "device_logs": device_logs,
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
    """轻量轮询：仅设备状态与执行日志，不查库、不覆盖管理台未保存的其它状态。"""
    err, session = _require_login({"authorization": authorization} if authorization else {})
    if err:
        return err
    allowed = _session_device_ids(session, lock_wait_sec=4.0)
    auto_start_enabled = _user_auto_start_enabled(session["username"])
    group_filter = str(device_group or "").strip()
    safe_page = max(1, int(page or 1))
    safe_size = max(1, min(int(page_size or 50), 500))

    all_devices = _load_visible_devices_from_db(session, device_group=group_filter)
    devices, devices_total = _paginate_dict_items(all_devices, safe_page, safe_size)
    snapshot_log_device_ids = list(devices.keys())
    with store.lock:
        _trim_task_log_keys_unlocked()
        task_logs = {k: v[-120:] for k, v in store.task_logs.items()}
        mode = store.run_mode
    device_logs = _device_logs_dict_for_ids(snapshot_log_device_ids, 120)
    return {
        "code": 0,
        "devices": devices,
        "devices_total": devices_total,
        "page": safe_page,
        "page_size": safe_size,
        "device_group": group_filter,
        "device_logs": device_logs,
        "task_logs": task_logs,
        "run_mode": mode,
        "auto_start_enabled": auto_start_enabled,
    }
