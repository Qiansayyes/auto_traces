"""
根据Excel中的TikTok用户名，自动：
1. 逐个搜索该用户；
2. 进入其个人主页，打开粉丝列表，采集前 N 个粉丝用户名(ID)；
3. 再根据这些粉丝用户名逐个进入主页，打开其关注列表，采集前 M 个关注用户名；
4. 将 (原始用户名, 粉丝用户名, 关注用户名) 三元组写入新的 Excel。

说明：
- 本脚本使用 Selenium + Chrome，请先在本机安装 Chrome 浏览器。
- 默认会复用一个固定的 Chrome 用户数据目录，方便你先手动登录 TikTok 后再由脚本继续操作。
- TikTok 页面结构经常更新，如果采集不到数据，大概率是定位器(XPath/CSS)需要根据最新页面结构微调。
"""

from dataclasses import dataclass
import argparse
import os
import time
import re
import json
import subprocess
import sys
import requests
from typing import List, Set, Tuple
import urllib.parse
from datetime import datetime, timezone
import glob
import sqlite3
from pathlib import Path

from openpyxl import Workbook, load_workbook
from selenium import webdriver
from selenium.common.exceptions import NoSuchElementException, TimeoutException
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait


# ================== 基本配置 ==================

DEFAULT_CONFIG = {
    "excel_input_path": "input.xlsx",
    "excel_output_path": "output_follow_graph.xlsx",
    "input_sheet_name": None,
    "username_column_letter": "A",
    "status_column_letter": "B",
    "date_column_letter": "C",
    "type_column_letter": "D",
    "max_followers_per_user": 70,
    "max_following_per_user": 70,
    "db_path": str(Path(__file__).resolve().parent / "tiktok_fans.db"),
    "autosearch_path": str(Path(__file__).resolve().parent / "autoSearch_5.28_with_debug.py"),
    "chrome_user_data_dir": str(Path(__file__).resolve().parent / "chrome_profile_tiktok"),
    "hubstudio_local_api": "http://127.0.0.1:6873",
    "hubstudio_container_id": "",
    "hubstudio_container_start_path": "/api/v1/browser/start",
    "hubstudio_container_list_path": "/api/v1/browser/list",
    "internal_overview_api": "http://127.0.0.1:8000/api/admin/overview",
    "default_following_filter_min_video_count": 10,
    "default_following_filter_max_following_count": 1000,
    # 降噪：关闭 Hubstudio 调试/环境细节日志（只保留关键进度与汇总）
    "verbose_driver_logs": False,
}

EXCEL_INPUT_PATH = DEFAULT_CONFIG["excel_input_path"]
EXCEL_OUTPUT_PATH = DEFAULT_CONFIG["excel_output_path"]
INPUT_SHEET_NAME = DEFAULT_CONFIG["input_sheet_name"]
USERNAME_COLUMN_LETTER = DEFAULT_CONFIG["username_column_letter"]
STATUS_COLUMN_LETTER = DEFAULT_CONFIG["status_column_letter"]
DATE_COLUMN_LETTER = DEFAULT_CONFIG["date_column_letter"]
TYPE_COLUMN_LETTER = DEFAULT_CONFIG["type_column_letter"]
MAX_FOLLOWERS_PER_USER = DEFAULT_CONFIG["max_followers_per_user"]
MAX_FOLLOWING_PER_USER = DEFAULT_CONFIG["max_following_per_user"]
DB_PATH = DEFAULT_CONFIG["db_path"]
AUTOSEARCH_PATH = DEFAULT_CONFIG["autosearch_path"]
CHROME_USER_DATA_DIR = DEFAULT_CONFIG["chrome_user_data_dir"]
HUBSTUDIO_LOCAL_API = DEFAULT_CONFIG["hubstudio_local_api"]
HUBSTUDIO_CONTAINER_ID = DEFAULT_CONFIG["hubstudio_container_id"]
START_CONTAINER_PATH = DEFAULT_CONFIG["hubstudio_container_start_path"]
LIST_CONTAINER_PATH = DEFAULT_CONFIG["hubstudio_container_list_path"]
INTERNAL_OVERVIEW_API = DEFAULT_CONFIG["internal_overview_api"]
DEFAULT_FOLLOWING_FILTER_MIN_VIDEO_COUNT = DEFAULT_CONFIG["default_following_filter_min_video_count"]
DEFAULT_FOLLOWING_FILTER_MAX_FOLLOWING_COUNT = DEFAULT_CONFIG["default_following_filter_max_following_count"]

# 运行期缓存：避免每次 save_edges_to_db 都打印阈值
_QUALITY_FILTER_CACHE: tuple[int, int] | None = None
_QUALITY_FILTER_PRINTED = False


def _coerce_bool(value, default=False):
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


def _coerce_int(value, default):
    if value is None or value == "":
        return default
    try:
        return int(value)
    except Exception:
        return default


def _load_runtime_config(args=None):
    cfg = dict(DEFAULT_CONFIG)
    env = os.environ
    cfg["excel_input_path"] = env.get("EXCEL_INPUT_PATH", cfg["excel_input_path"])
    cfg["excel_output_path"] = env.get("EXCEL_OUTPUT_PATH", cfg["excel_output_path"])
    cfg["input_sheet_name"] = env.get("INPUT_SHEET_NAME", cfg["input_sheet_name"]) or None
    cfg["username_column_letter"] = env.get("USERNAME_COLUMN_LETTER", cfg["username_column_letter"])
    cfg["status_column_letter"] = env.get("STATUS_COLUMN_LETTER", cfg["status_column_letter"])
    cfg["date_column_letter"] = env.get("DATE_COLUMN_LETTER", cfg["date_column_letter"])
    cfg["type_column_letter"] = env.get("TYPE_COLUMN_LETTER", cfg["type_column_letter"])
    cfg["max_followers_per_user"] = _coerce_int(env.get("MAX_FOLLOWERS_PER_USER"), cfg["max_followers_per_user"])
    cfg["max_following_per_user"] = _coerce_int(env.get("MAX_FOLLOWING_PER_USER"), cfg["max_following_per_user"])
    cfg["db_path"] = env.get("DB_PATH", cfg["db_path"])
    cfg["autosearch_path"] = env.get("AUTOSEARCH_PATH", cfg["autosearch_path"])
    cfg["chrome_user_data_dir"] = env.get("CHROME_USER_DATA_DIR", cfg["chrome_user_data_dir"])
    cfg["hubstudio_local_api"] = env.get("HUBSTUDIO_LOCAL_API", cfg["hubstudio_local_api"])
    cfg["hubstudio_container_id"] = env.get("HUBSTUDIO_CONTAINER_ID", cfg["hubstudio_container_id"])
    cfg["hubstudio_container_start_path"] = env.get("HUBSTUDIO_START_PATH", cfg["hubstudio_container_start_path"])
    cfg["hubstudio_container_list_path"] = env.get("HUBSTUDIO_LIST_PATH", cfg["hubstudio_container_list_path"])
    cfg["internal_overview_api"] = env.get("INTERNAL_OVERVIEW_API", cfg["internal_overview_api"])
    cfg["default_following_filter_min_video_count"] = _coerce_int(env.get("FOLLOWING_FILTER_MIN_VIDEO_COUNT"), cfg["default_following_filter_min_video_count"])
    cfg["default_following_filter_max_following_count"] = _coerce_int(env.get("FOLLOWING_FILTER_MAX_FOLLOWING_COUNT"), cfg["default_following_filter_max_following_count"])

    if args is not None:
        for key in (
            "excel_input_path",
            "excel_output_path",
            "input_sheet_name",
            "username_column_letter",
            "status_column_letter",
            "date_column_letter",
            "type_column_letter",
            "max_followers_per_user",
            "max_following_per_user",
            "db_path",
            "autosearch_path",
            "chrome_user_data_dir",
            "hubstudio_local_api",
            "hubstudio_container_id",
            "hubstudio_container_start_path",
            "hubstudio_container_list_path",
            "internal_overview_api",
            "default_following_filter_min_video_count",
            "default_following_filter_max_following_count",
            "verbose_driver_logs",
        ):
            value = getattr(args, key, None)
            if value in (None, ""):
                continue
            # argparse 的数值参数默认可能是 0；0 表示“未显式传参”，不应覆盖 DEFAULT_CONFIG
            if key in {
                "max_followers_per_user",
                "max_following_per_user",
                "default_following_filter_min_video_count",
                "default_following_filter_max_following_count",
            }:
                try:
                    if int(value) <= 0:
                        continue
                except Exception:
                    continue
            cfg[key] = value
    return cfg


def _get_db(db_path: str | None = None):
    target = db_path or DB_PATH
    conn = sqlite3.connect(str(target))
    return conn


def _follow_graph_table_columns(conn: sqlite3.Connection, table: str) -> set[str]:
    rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
    return {str(r[1]).strip() for r in rows}


def _migrate_follow_graph_remove_owner(conn: sqlite3.Connection) -> None:
    """去掉历史 owner_username：根用户池与 follower 队列全平台共享（与 server/main.py 一致）。"""
    try:
        roots_cols = _follow_graph_table_columns(conn, "tiktok_roots")
        fq_cols = _follow_graph_table_columns(conn, "follower_queue")
    except sqlite3.OperationalError:
        return
    if "owner_username" not in roots_cols and "owner_username" not in fq_cols:
        return

    conn.execute("PRAGMA foreign_keys=OFF")
    conn.execute("ALTER TABLE tiktok_roots RENAME TO tiktok_roots_old")
    conn.execute(
        """
        CREATE TABLE tiktok_roots (
            root_username TEXT NOT NULL PRIMARY KEY,
            roots_type TEXT NOT NULL DEFAULT '',
            discovered_at TEXT NOT NULL,
            last_use_follower_count INTEGER,
            usage_count INTEGER NOT NULL DEFAULT 0
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
            username TEXT UNIQUE NOT NULL,
            root_username TEXT NOT NULL,
            created_at TEXT NOT NULL,
            consume_status TEXT NOT NULL DEFAULT 'pending',
            consumed_at TEXT,
            consumed_by TEXT,
            consumed_mode TEXT,
            following_access_status TEXT NOT NULL DEFAULT 'unknown',
            following_fetch_success INTEGER NOT NULL DEFAULT 0,
            mobile_push_status TEXT NOT NULL DEFAULT 'pending',
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


def init_db_for_follow_graph(db_path: str):
    """创建/校验 tiktok_roots、follower_queue 与 mobile_pending_usernames 表。"""
    conn = _get_db(db_path)
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS tiktok_roots (
                root_username TEXT NOT NULL PRIMARY KEY,
                roots_type TEXT NOT NULL DEFAULT '',
                discovered_at TEXT NOT NULL,
                last_use_follower_count INTEGER,
                usage_count INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS follower_queue (
                username TEXT UNIQUE NOT NULL,
                root_username TEXT NOT NULL,
                created_at TEXT NOT NULL,
                consume_status TEXT NOT NULL DEFAULT 'pending',
                consumed_at TEXT,
                consumed_by TEXT,
                consumed_mode TEXT,
                following_access_status TEXT NOT NULL DEFAULT 'unknown',
                following_fetch_success INTEGER NOT NULL DEFAULT 0,
                mobile_push_status TEXT NOT NULL DEFAULT 'pending',
                FOREIGN KEY (root_username) REFERENCES tiktok_roots(root_username)
            );

            CREATE TABLE IF NOT EXISTS mobile_pending_usernames (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                created_at TEXT NOT NULL,
                consume_status TEXT NOT NULL DEFAULT 'pending',
                consumed_at TEXT
            );

            CREATE INDEX IF NOT EXISTS idx_follower_queue_root
                ON follower_queue(root_username);
            """
        )

        cols = [str(r[1]).strip() for r in conn.execute("PRAGMA table_info(follower_queue)").fetchall()]
        if "consume_status" not in cols:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN consume_status TEXT NOT NULL DEFAULT 'pending'")
        if "consumed_at" not in cols:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN consumed_at TEXT")
        if "consumed_by" not in cols:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN consumed_by TEXT")
        if "consumed_mode" not in cols:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN consumed_mode TEXT")
        if "following_access_status" not in cols:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN following_access_status TEXT NOT NULL DEFAULT 'unknown'")
        if "following_fetch_success" not in cols:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN following_fetch_success INTEGER NOT NULL DEFAULT 0")
        if "mobile_push_status" not in cols:
            conn.execute("ALTER TABLE follower_queue ADD COLUMN mobile_push_status TEXT NOT NULL DEFAULT 'pending'")

        root_cols = [str(r[1]).strip() for r in conn.execute("PRAGMA table_info(tiktok_roots)").fetchall()]
        if "usage_count" not in root_cols:
            conn.execute("ALTER TABLE tiktok_roots ADD COLUMN usage_count INTEGER NOT NULL DEFAULT 0")

        mobile_cols = [str(r[1]).strip() for r in conn.execute("PRAGMA table_info(mobile_pending_usernames)").fetchall()]
        if "consume_status" not in mobile_cols:
            conn.execute("ALTER TABLE mobile_pending_usernames ADD COLUMN consume_status TEXT NOT NULL DEFAULT 'pending'")
        if "consumed_at" not in mobile_cols:
            conn.execute("ALTER TABLE mobile_pending_usernames ADD COLUMN consumed_at TEXT")

        _migrate_follow_graph_remove_owner(conn)

        conn.commit()
    finally:
        conn.close()


def load_quality_filter_config(overview_api: str, default_min_video: int, default_max_following: int) -> tuple[int, int]:
    """从服务端配置读取 following 质量筛选阈值。"""
    min_video = default_min_video
    max_following = default_max_following

    try:
        import urllib.request
        with urllib.request.urlopen(overview_api, timeout=3) as resp:
            raw = resp.read().decode("utf-8", errors="ignore")
            data = json.loads(raw or "{}")
            cfg = data.get("config") if isinstance(data, dict) else None
            if isinstance(cfg, dict):
                mv = cfg.get("following_filter_min_video_count")
                mf = cfg.get("following_filter_max_following_count")
                if isinstance(mv, (int, float)):
                    min_video = max(0, int(mv))
                if isinstance(mf, (int, float)):
                    max_following = max(1, int(mf))
    except Exception:
        pass

    return min_video, max_following


def fetch_profile_metrics_via_autosearch(username: str, autosearch_path: str = "") -> dict | None:
    """通过 autoSearch_5.28_with_debug 查询单个用户质量信息（进程内加载，无子进程）。"""
    _ = autosearch_path  # 保留参数兼容旧配置；实现固定走 with_debug
    project_root = Path(__file__).resolve().parent
    server_dir = project_root / "server"
    if not (project_root / "autoSearch_5.28_with_debug.py").is_file():
        print(
            f"[质量筛选] 未找到脚本: {project_root / 'autoSearch_5.28_with_debug.py'}",
            flush=True,
        )
        return None
    try:
        server = str(server_dir)
        if server not in sys.path:
            sys.path.insert(0, server)
        from tiktok_universal_snapshot import fetch_tiktok_profile_metrics_dict

        return fetch_tiktok_profile_metrics_dict(username)
    except Exception as exc:
        print(f"[质量筛选] 查询失败 @{username}: {exc}", flush=True)
        return None


def evaluate_following_by_autosearch(
    username: str,
    min_video_count: int,
    max_following_count: int,
    autosearch_path: str,
) -> tuple[bool, str, dict | None]:
    """一次 autoSearch 查询同时完成：私密过滤 + 质量阈值判断。"""
    profile = fetch_profile_metrics_via_autosearch(username, autosearch_path)
    if not profile:
        return False, "profile_unavailable", None

    if bool(profile.get("private_account_guess")):
        return False, f"private_account:{profile.get('private_account_reason') or 'private_guess=true'}", profile

    video_count = profile.get("video_count")
    following_count = profile.get("following_count")
    if video_count is None or following_count is None:
        return False, "profile_incomplete", profile

    if int(video_count) <= int(min_video_count):
        return False, f"video_count={video_count}<=min_video({min_video_count})", profile
    if int(following_count) >= int(max_following_count):
        return False, f"following_count={following_count}>=max_following({max_following_count})", profile

    return True, f"video_count={video_count}, following_count={following_count}", profile


def save_edges_to_db(records: List["EdgeRecord"]) -> dict:
    """
    将 following 结果落库（带质量筛选）：
    1) 通过 autoSearch 查询 following 画像（video_count / following_count）；
    2) 仅当满足阈值（video_count > min_video_count 且 following_count < max_following_count）时，才进入候选；
    3) 对通过筛选且原本不在 tiktok_roots 的用户名，同时写入 tiktok_roots 与 mobile_pending_usernames。

    roots_type 规则：
    - 取该 following 来源 root_username 在 tiktok_roots 的 roots_type
    - 若同一 following 来自多个 root，优先使用第一个非空 roots_type
    """
    if not records:
        return {
            "candidates": 0,
            "passed_quality": 0,
            "inserted_roots": 0,
            "inserted_pending": 0,
            "skipped_existing_root": 0,
            "skipped_quality": 0,
            "skipped_private": 0,
            "skipped_profile_unavailable": 0,
        }

    conn = _get_db()
    try:
        now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

        global _QUALITY_FILTER_CACHE, _QUALITY_FILTER_PRINTED
        if _QUALITY_FILTER_CACHE is None:
            _QUALITY_FILTER_CACHE = load_quality_filter_config(
                INTERNAL_OVERVIEW_API,
                DEFAULT_FOLLOWING_FILTER_MIN_VIDEO_COUNT,
                DEFAULT_FOLLOWING_FILTER_MAX_FOLLOWING_COUNT,
            )
        min_video_count, max_following_count = _QUALITY_FILTER_CACHE
        if not _QUALITY_FILTER_PRINTED:
            _QUALITY_FILTER_PRINTED = True
            print(
                f"[质量筛选] 当前阈值: video_count>{min_video_count}, following_count<{max_following_count}",
                flush=True,
            )

        # 1) 先查来源 root 的 roots_type
        source_roots = sorted({(r.root_username or "").strip() for r in records if (r.root_username or "").strip()})
        root_type_map: dict[str, str] = {}
        if source_roots:
            placeholders = ",".join(["?"] * len(source_roots))
            rows = conn.execute(
                f"SELECT root_username, roots_type FROM tiktok_roots WHERE root_username IN ({placeholders})",
                source_roots,
            ).fetchall()
            root_type_map = {str(row[0]).strip(): (str(row[1]).strip() if row[1] else "") for row in rows}

        # 2) following -> roots_type（优先第一个非空）
        following_type_map: dict[str, str] = {}
        for r in records:
            following = (r.following_username or "").strip().lstrip("@")
            if not following:
                continue
            src_root = (r.root_username or "").strip()
            src_type = (root_type_map.get(src_root) or "").strip()

            if following not in following_type_map:
                following_type_map[following] = src_type
            elif not following_type_map[following] and src_type:
                following_type_map[following] = src_type

        inserted_roots = 0
        inserted_pending = 0
        skipped_existing_root = 0
        skipped_quality = 0
        skipped_private = 0
        skipped_profile_unavailable = 0
        passed_quality = 0
        skipped_video_count = 0
        skipped_following_count = 0
        skipped_profile_incomplete = 0

        # 3) 一次查询完成：私密过滤 + 质量筛选 + 写库
        for following, roots_type in following_type_map.items():
            ok, reason, profile = evaluate_following_by_autosearch(
                following,
                min_video_count,
                max_following_count,
                AUTOSEARCH_PATH,
            )
            if not ok:
                skipped_quality += 1
                if reason.startswith("private_account:"):
                    skipped_private += 1
                    print(f"[私密拦截] @{following} -> {reason}", flush=True)
                elif reason == "profile_unavailable":
                    skipped_profile_unavailable += 1
                elif reason == "profile_incomplete":
                    skipped_profile_incomplete += 1
                elif reason.startswith("video_count=") and "<=min_video" in reason:
                    skipped_video_count += 1
                elif reason.startswith("following_count=") and ">=max_following" in reason:
                    skipped_following_count += 1
                else:
                    # 其它原因统一归到 skipped_quality（不逐条刷屏）
                    pass
                continue
            passed_quality += 1

            existed_before = conn.execute(
                "SELECT 1 FROM tiktok_roots WHERE root_username = ? LIMIT 1",
                (following,),
            ).fetchone() is not None
            if existed_before:
                skipped_existing_root += 1
                continue

            cur = conn.execute(
                """
                INSERT OR IGNORE INTO tiktok_roots (root_username, roots_type, discovered_at, last_use_follower_count, usage_count)
                VALUES (?, ?, ?, NULL, 0)
                """,
                (following, roots_type, now),
            )
            if cur.rowcount > 0:
                inserted_roots += 1

            conn.execute(
                "INSERT INTO mobile_pending_usernames (username, created_at) VALUES (?, ?)",
                (following, now),
            )
            inserted_pending += 1

        conn.commit()
        print(
            f"[DB] following回流完成：新增tiktok_roots={inserted_roots}，"
            f"新增mobile_pending_usernames={inserted_pending}，"
            f"跳过(质量/不完整)={skipped_quality}，"
            f"跳过(已在tiktok_roots)={skipped_existing_root}",
            flush=True,
        )
        return {
            "candidates": len(following_type_map),
            "passed_quality": passed_quality,
            "inserted_roots": inserted_roots,
            "inserted_pending": inserted_pending,
            "skipped_existing_root": skipped_existing_root,
            "skipped_quality": skipped_quality,
            "skipped_private": skipped_private,
            "skipped_profile_unavailable": skipped_profile_unavailable,
            "skipped_video_count": skipped_video_count,
            "skipped_following_count": skipped_following_count,
            "skipped_profile_incomplete": skipped_profile_incomplete,
        }
    finally:
        conn.close()

    return {
        "candidates": len(following_type_map),
        "passed_quality": 0,
        "inserted_roots": 0,
        "inserted_pending": 0,
        "skipped_existing_root": 0,
        "skipped_quality": 0,
        "skipped_private": 0,
        "skipped_profile_unavailable": 0,
    }


def enqueue_followers(root_username: str, followers: List[str], roots_type: str = "") -> dict:
    """将粉丝入队，并将 root 写入 tiktok_roots（入库前做隐私判定，fail-closed）。"""
    if not followers:
        return {
            "root": (root_username or "").strip().lstrip("@"),
            "followers_input": 0,
            "inserted_root": 0,
            "inserted_queue": 0,
            "skipped_private": 0,
            "skipped_profile_unavailable": 0,
            "skipped_duplicate_queue": 0,
            "blocked_root_private": 0,
            "blocked_root_profile_unavailable": 0,
        }

    conn = _get_db()
    try:
        now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        roots_type = roots_type.strip()

        root_clean = (root_username or "").strip().lstrip("@")
        if not root_clean:
            print("[入库前隐私拦截] root_username 为空，跳过写库", flush=True)
            return

        # root 入库前隐私判定（判定失败也拦截）
        root_profile = fetch_profile_metrics_via_autosearch(root_clean, AUTOSEARCH_PATH)
        if not root_profile:
            print(f"[入库前隐私拦截] @{root_clean} 判定失败(profile_unavailable)，跳过写入 tiktok_roots", flush=True)
            return {
                "root": root_clean,
                "followers_input": len(followers),
                "inserted_root": 0,
                "inserted_queue": 0,
                "skipped_private": 0,
                "skipped_profile_unavailable": 0,
                "skipped_duplicate_queue": 0,
                "blocked_root_private": 0,
                "blocked_root_profile_unavailable": 1,
            }
        if bool(root_profile.get("private_account_guess")):
            print(
                f"[入库前隐私拦截] @{root_clean} 私密账号，跳过写入 tiktok_roots | reason={root_profile.get('private_account_reason')}",
                flush=True,
            )
            return {
                "root": root_clean,
                "followers_input": len(followers),
                "inserted_root": 0,
                "inserted_queue": 0,
                "skipped_private": 0,
                "skipped_profile_unavailable": 0,
                "skipped_duplicate_queue": 0,
                "blocked_root_private": 1,
                "blocked_root_profile_unavailable": 0,
            }

        root_cur = conn.execute(
            """
            INSERT OR IGNORE INTO tiktok_roots (root_username, roots_type, discovered_at, last_use_follower_count, usage_count)
            VALUES (?, ?, ?, NULL, 0)
            """,
            (root_clean, roots_type, now),
        )
        inserted_root = 1 if (root_cur.rowcount or 0) > 0 else 0

        inserted_queue = 0
        skipped_private = 0
        skipped_profile_unavailable = 0
        skipped_duplicate_queue = 0
        for follower in followers:
            follower_clean = follower.strip().lstrip("@")
            if not follower_clean:
                continue

            profile = fetch_profile_metrics_via_autosearch(follower_clean, AUTOSEARCH_PATH)
            if not profile:
                skipped_profile_unavailable += 1
                print(f"[入库前隐私拦截] @{follower_clean} 判定失败(profile_unavailable)，跳过入队", flush=True)
                continue
            if bool(profile.get("private_account_guess")):
                skipped_private += 1
                print(
                    f"[入库前隐私拦截] @{follower_clean} 私密账号，跳过入队 | reason={profile.get('private_account_reason')}",
                    flush=True,
                )
                continue

            cur = conn.execute(
                """
                INSERT OR IGNORE INTO follower_queue (username, root_username, created_at)
                VALUES (?, ?, ?)
                """,
                (follower_clean, root_clean, now),
            )
            if (cur.rowcount or 0) > 0:
                inserted_queue += 1
            else:
                skipped_duplicate_queue += 1

        conn.commit()
        print(
            f"[入库汇总] enqueue_followers 完成: root=@{root_clean}, 写入tiktok_roots={inserted_root}, "
            f"写入follower_queue={inserted_queue}, 去重(已在队列)={skipped_duplicate_queue}, "
            f"拦截(私密)={skipped_private}, 拦截(profile_unavailable)={skipped_profile_unavailable}",
            flush=True,
        )
        return {
            "root": root_clean,
            "followers_input": len(followers),
            "inserted_root": inserted_root,
            "inserted_queue": inserted_queue,
            "skipped_private": skipped_private,
            "skipped_profile_unavailable": skipped_profile_unavailable,
            "skipped_duplicate_queue": skipped_duplicate_queue,
            "blocked_root_private": 0,
            "blocked_root_profile_unavailable": 0,
        }
    finally:
        conn.close()


def fetch_follower_batch(limit: int = 100) -> List[tuple[str, str, str, int, str]]:
    """取待处理 follower（唯一 username），返回 [(username, root_username, following_access_status, following_fetch_success, mobile_push_status)]。"""
    conn = _get_db()
    try:
        rows = conn.execute(
            """
            SELECT username, root_username, following_access_status, following_fetch_success, mobile_push_status
            FROM follower_queue
            WHERE COALESCE(following_fetch_success, 0) = 0
            ORDER BY created_at
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
        return [(row[0], row[1], row[2] or "unknown", int(row[3] or 0), row[4] or "pending") for row in rows]
    finally:
        conn.close()


def update_follower_queue_status(
    username: str,
    following_access_status: str,
    mobile_push_status: str,
    following_fetch_success: int | None = None,
):
    conn = _get_db()
    try:
        if following_fetch_success is None:
            conn.execute(
                """
                UPDATE follower_queue
                SET following_access_status = ?, mobile_push_status = ?
                WHERE username = ?
                """,
                (following_access_status, mobile_push_status, username),
            )
        else:
            conn.execute(
                """
                UPDATE follower_queue
                SET following_access_status = ?, mobile_push_status = ?, following_fetch_success = ?
                WHERE username = ?
                """,
                (following_access_status, mobile_push_status, int(following_fetch_success), username),
            )
        conn.commit()
    finally:
        conn.close()


def remove_followers_from_queue(usernames: List[str], only_when_direct_mode: bool = False):
    if not usernames:
        return
    if only_when_direct_mode:
        return
    conn = _get_db()
    try:
        conn.executemany(
            "DELETE FROM follower_queue WHERE username = ?",
            [(u,) for u in usernames],
        )
        conn.commit()
    finally:
        conn.close()

START_CONTAINER_PATH = "/api/v1/browser/start"
LIST_CONTAINER_PATH = "/api/v1/browser/list"
IMPLICIT_WAIT_SECONDS = 5
EXPLICIT_WAIT_SECONDS = 20


def wait_for_element_with_heartbeat(
    driver: webdriver.Chrome,
    locator,
    timeout_seconds: int,
    label: str,
    heartbeat_seconds: int = 5,
) -> bool:
    """等待元素出现，并定时输出心跳日志，避免长时间无输出。"""
    start = time.time()
    while True:
        elapsed = int(time.time() - start)
        remain = timeout_seconds - elapsed
        if remain <= 0:
            print(f"[超时] 等待{label}超时（{timeout_seconds}s）", flush=True)
            return False

        step = min(heartbeat_seconds, max(1, remain))
        try:
            WebDriverWait(driver, step).until(EC.presence_of_element_located(locator))
            total = int(time.time() - start)
            print(f"[检测到] {label}（等待 {total}s）", flush=True)
            return True
        except TimeoutException:
            waited = min(timeout_seconds, elapsed + step)
            print(f"[等待中] {label}，已等待 {waited}/{timeout_seconds}s", flush=True)


@dataclass
class EdgeRecord:
    """一条关系记录：原始用户名 -> 其粉丝 -> 粉丝关注的人"""

    root_username: str
    follower_username: str
    following_username: str


# ================== 浏览器相关 ==================

def _normalize_debugger_addr(value) -> str | None:
    # 端口号字段（int/数字字符串）
    if isinstance(value, int):
        if 1 <= value <= 65535:
            return f"127.0.0.1:{value}"
        return None

    t = str(value or "").strip()
    if not t:
        return None

    # 纯数字字符串按端口处理
    if t.isdigit():
        port = int(t)
        if 1 <= port <= 65535:
            return f"127.0.0.1:{port}"
        return None

    if "://" in t:
        t = t.split("://", 1)[1].split("/", 1)[0]

    m = re.search(r"^([a-zA-Z0-9_.-]+):(\d{2,5})$", t)
    if not m:
        return None
    host = m.group(1)
    port = int(m.group(2))
    if port <= 0 or port > 65535:
        return None
    return f"{host}:{port}"


def _find_debugger_addr(obj):
    candidate_fields = [
        "debuggingPort", "debugging_port", "remoteDebuggingPort", "remote_debugging_port",
        "debuggerAddress", "debugger_address", "debugAddress",
        "chromeDebuggerAddress", "chrome_debugger_address",
        "ws", "wsUrl", "webSocketUrl",
        "webdriver", "webDriver", "web_driver",
    ]

    if isinstance(obj, dict):
        for k, v in obj.items():
            if k in candidate_fields:
                parsed = _normalize_debugger_addr(v)
                if parsed:
                    return parsed
            got = _find_debugger_addr(v)
            if got:
                return got
    elif isinstance(obj, list):
        for it in obj:
            got = _find_debugger_addr(it)
            if got:
                return got
    return None


def _start_hubstudio_env(container_id: str, hubstudio_local_api: str, hubstudio_container_start_path: str, hubstudio_container_list_path: str) -> tuple[str | None, str | None]:
    verbose = _coerce_bool(os.environ.get("VERBOSE_DRIVER_LOGS"), DEFAULT_CONFIG.get("verbose_driver_logs", False))

    def _trim_debug_payload(obj, depth: int = 0):
        if depth > 2:
            return "..."
        if isinstance(obj, dict):
            out = {}
            idx = 0
            for k, v in obj.items():
                idx += 1
                if idx > 40:
                    out["__truncated__"] = True
                    break
                out[str(k)] = _trim_debug_payload(v, depth + 1)
            return out
        if isinstance(obj, list):
            clipped = obj[:20]
            return [_trim_debug_payload(x, depth + 1) for x in clipped]
        if isinstance(obj, str):
            return obj[:200]
        return obj

    # 1) 先 start 环境
    start_data = {}
    try:
        url = f"{hubstudio_local_api}{hubstudio_container_start_path}"
        resp = requests.post(url, json={"containerCode": container_id}, timeout=30)
        resp.raise_for_status()
        start_data = resp.json() if resp.content else {}
        if verbose:
            print(f"[调试] start原始返回={json.dumps(_trim_debug_payload(start_data), ensure_ascii=False)}")
    except Exception as e:
        print(f"[环境启动失败] {container_id}: {e}")
        return None, None

    addr = _find_debugger_addr(start_data)
    webdriver_path = None
    if isinstance(start_data, dict):
        data_obj = start_data.get("data") if isinstance(start_data.get("data"), dict) else start_data
        if isinstance(data_obj, dict):
            wp = data_obj.get("webdriver") or data_obj.get("webDriver") or data_obj.get("web_driver")
            if isinstance(wp, str) and wp.strip() and (".exe" in wp.lower() or os.path.exists(wp)):
                webdriver_path = wp.strip()

    if addr:
        if verbose:
            print(f"[环境模式] start接口命中调试地址={addr}")
        return addr, webdriver_path

    # 2) 再 list 环境兜底查地址（参考你给的发布脚本思路）
    try:
        list_url = f"{hubstudio_local_api}{hubstudio_container_list_path}"
        list_resp = requests.post(list_url, json={}, timeout=20)
        list_resp.raise_for_status()
        list_data = list_resp.json() if list_resp.content else {}
        if verbose:
            print(f"[调试] list原始返回={json.dumps(_trim_debug_payload(list_data), ensure_ascii=False)}")
    except Exception as e:
        if verbose:
            print(f"[环境模式] list接口获取失败: {e}")
        return None, webdriver_path

    items = []
    if isinstance(list_data, dict):
        for key in ("data", "list", "items", "rows", "result"):
            value = list_data.get(key)
            if isinstance(value, list):
                items = value
                break
    elif isinstance(list_data, list):
        items = list_data

    target = None
    for item in items:
        if not isinstance(item, dict):
            continue
        cid = str(item.get("containerCode") or item.get("id") or item.get("containerId") or "").strip()
        if cid == str(container_id).strip():
            target = item
            break

    if target is None and isinstance(list_data, dict):
        target = list_data

    addr = _find_debugger_addr(target)
    if addr:
        if verbose:
            print(f"[环境模式] list接口命中调试地址={addr}")
    else:
        if verbose:
            print(f"[环境模式] 未在start/list返回中解析到调试地址，container_id={container_id}")
    return addr, webdriver_path


def init_driver(container_id: str | None = None, chrome_user_data_dir: str | None = None, hubstudio_local_api: str | None = None, hubstudio_container_start_path: str | None = None, hubstudio_container_list_path: str | None = None) -> webdriver.Chrome:
    """初始化 Chrome 浏览器。必须使用 Hubstudio 环境ID模式，不再支持手动登录。"""
    if not container_id:
        raise RuntimeError("必须提供 --container-id。已禁用手动登录模式。")
    verbose = _coerce_bool(os.environ.get("VERBOSE_DRIVER_LOGS"), DEFAULT_CONFIG.get("verbose_driver_logs", False))

    options = Options()
    options.add_argument("--disable-blink-features=AutomationControlled")
    options.add_argument("--start-maximized")

    if chrome_user_data_dir:
        options.add_argument(f"--user-data-dir={chrome_user_data_dir}")

    debugger_address, webdriver_path = _start_hubstudio_env(
        container_id,
        hubstudio_local_api or DEFAULT_CONFIG["hubstudio_local_api"],
        hubstudio_container_start_path or START_CONTAINER_PATH,
        hubstudio_container_list_path or LIST_CONTAINER_PATH,
    )
    if not debugger_address:
        raise RuntimeError(f"环境ID={container_id} 启动失败：未获取到有效调试地址")

    options.debugger_address = debugger_address
    if verbose:
        print(f"[环境模式] 使用环境ID={container_id} 调试地址={debugger_address}")

    try:
        if webdriver_path:
            if verbose:
                print(f"[环境模式] 优先使用Hubstudio返回的webdriver: {webdriver_path}")
            driver = webdriver.Chrome(service=Service(webdriver_path), options=options)
        else:
            driver = webdriver.Chrome(options=options)
    except Exception as ex:
        if verbose:
            print(f"[环境模式] 使用Hubstudio webdriver失败，回退系统驱动。错误: {ex}")
        driver = webdriver.Chrome(options=options)
    driver.implicitly_wait(IMPLICIT_WAIT_SECONDS)
    return driver


def safe_click(driver: webdriver.Chrome, locator, timeout: int = EXPLICIT_WAIT_SECONDS):
    """等待元素可点击后再点击"""
    WebDriverWait(driver, timeout).until(EC.element_to_be_clickable(locator)).click()


def close_popup_if_open(driver: webdriver.Chrome):
    """如果弹窗打开，尝试关闭它（快速路径，避免隐式等待导致卡顿）"""
    try:
        # 临时关闭隐式等待，避免每个 selector 触发 5s*N 的累计等待
        driver.implicitly_wait(0)

        # 尝试查找关闭按钮（X按钮）
        close_selectors = [
            'div[data-e2e="follow-popup-close"]',
            'div[data-e2e="follow-popup-close"][role="button"]',
            'button[aria-label*="Close"]',
            'button[aria-label*="close"]',
            'button[aria-label*="关闭"]',
            'svg[data-e2e="close-icon"]',
            'button[class*="Close"]',
            'div[role="dialog"] button:last-child',  # 弹窗的最后一个按钮通常是关闭按钮
        ]
        for selector in close_selectors:
            elems = driver.find_elements(By.CSS_SELECTOR, selector)
            if not elems:
                continue
            close_btn = elems[0]
            driver.execute_script("arguments[0].click();", close_btn)
            time.sleep(0.2)
            return True

        # 如果找不到关闭按钮，尝试按ESC键
        from selenium.webdriver.common.keys import Keys
        popups = driver.find_elements(By.CSS_SELECTOR, 'div[data-e2e="follow-info-popup"], div[role="dialog"]')
        if popups:
            popups[0].send_keys(Keys.ESCAPE)
            time.sleep(0.2)
            return True
    except Exception:
        pass
    finally:
        # 恢复默认隐式等待
        try:
            driver.implicitly_wait(IMPLICIT_WAIT_SECONDS)
        except Exception:
            pass

    return False


def scroll_to_bottom(driver: webdriver.Chrome, container_selector: str, max_scroll: int = 30, pause: float = 1.0):
    """
    滚动粉丝 / 关注列表容器，尽量多加载一些数据。
    container_selector: CSS 选择器，指向可滚动区域
    """
    try:
        scroll_div = driver.find_element(By.CSS_SELECTOR, container_selector)
    except NoSuchElementException:
        return

    last_height = 0
    same_count = 0

    for _ in range(max_scroll):
        # 逐页向下滚动，而不是一下子滑到最底，效果更接近人工滚动
        driver.execute_script(
            "arguments[0].scrollTop = arguments[0].scrollTop + arguments[0].clientHeight;",
            scroll_div,
        )
        time.sleep(pause)
        height = driver.execute_script("return arguments[0].scrollHeight;", scroll_div)
        if height == last_height:
            same_count += 1
            if same_count >= 3:
                break
        else:
            same_count = 0
            last_height = height


# ================== TikTok 页面操作 ==================

def open_user_profile_by_search(driver: webdriver.Chrome, username: str) -> bool:
    """
    通过搜索打开某个用户的个人主页。
    返回是否成功打开。
    """
    q = urllib.parse.quote(username)
    url = f"https://www.tiktok.com/search/user?q={q}"
    driver.get(url)

    print(f"[步骤] 打开搜索页并等待结果: {username}", flush=True)
    ok = wait_for_element_with_heartbeat(
        driver,
        (By.CSS_SELECTOR, 'main, [data-e2e="search-user-container"], [data-e2e="search-result"]'),
        EXPLICIT_WAIT_SECONDS,
        f"搜索结果页面({username})",
        heartbeat_seconds=5,
    )
    if not ok:
        print(f"[搜索失败] 搜索结果页面加载超时: {username}", flush=True)
        return False
    time.sleep(1)  # 额外等待，确保搜索结果完全加载

    # 优先在搜索结果区域查找链接，排除左侧导航栏
    # 策略：先尝试在 main 标签或搜索结果容器中查找
    search_containers = [
        'main',
        '[data-e2e="search-user-container"]',
        '[data-e2e="search-result"]',
        'div[class*="SearchContainer"]',
        'div[class*="SearchResult"]',
    ]
    
    links = []
    for container_selector in search_containers:
        try:
            container = driver.find_element(By.CSS_SELECTOR, container_selector)
            # 在容器内查找用户链接，排除导航栏链接
            container_links = container.find_elements(By.CSS_SELECTOR, 'a[href^="/@"]')
            if container_links:
                links = container_links
                break
        except NoSuchElementException:
            continue
    
    # 如果容器查找失败，使用全局查找，但排除明显的导航栏链接
    if not links:
        all_links = driver.find_elements(By.CSS_SELECTOR, 'a[href^="/@"]')
        # 过滤掉导航栏中的链接（通常包含特定class或data-e2e属性）
        for link in all_links:
            try:
                # 检查链接是否在导航栏中
                parent = link.find_element(By.XPATH, "./ancestor::nav | ./ancestor::*[@role='navigation'] | ./ancestor::*[contains(@class, 'Sidebar')] | ./ancestor::*[contains(@class, 'Nav')]")
                # 如果在导航栏中，跳过
                continue
            except NoSuchElementException:
                # 不在导航栏中，添加到候选列表
                links.append(link)
    
    if not links:
        print(f"[搜索失败] 未找到搜索结果: {username}")
        return False

    # 优先找完全匹配 @username 的结果
    target_link = None
    username_clean = username.lstrip("@").lower()
    
    for link in links:
        href = link.get_attribute("href") or ""
        if "/@" in href:
            handle = href.split("/@")[-1].split("?")[0].strip("/").lower()
            if handle == username_clean:
                target_link = link
                break
    
    # 如果没有完全匹配，检查部分匹配（用户名包含搜索关键词）
    if target_link is None:
        for link in links:
            href = link.get_attribute("href") or ""
            if "/@" in href:
                handle = href.split("/@")[-1].split("?")[0].strip("/").lower()
                if username_clean in handle or handle in username_clean:
                    target_link = link
                    break
    
    # 如果还是没有匹配，取第一个非导航栏的链接
    if target_link is None and links:
        target_link = links[0]

    if target_link is None:
        print(f"[搜索失败] 未找到用户链接: {username}")
        return False

    # 点击链接前，验证一下URL是否正确
    href = target_link.get_attribute("href") or ""
    if "/@" in href:
        clicked_handle = href.split("/@")[-1].split("?")[0].strip("/").lower()
        if clicked_handle != username_clean:
            print(f"[警告] 将点击的用户名 {clicked_handle} 与目标 {username_clean} 不完全匹配，但继续执行")
    
    driver.execute_script("arguments[0].click();", target_link)
    time.sleep(3)
    
    # 验证是否成功跳转到目标用户主页
    current_url = driver.current_url
    if f"/@{username_clean}" in current_url.lower():
        return True
    else:
        print(f"[警告] 点击后URL不匹配，当前URL: {current_url}, 目标: @{username_clean}")
        return True  # 仍然返回True，因为可能URL格式略有不同


def extract_handles_from_links(links) -> List[str]:
    """从 a[href*="/@"] 列表中提取用户名"""
    handles: List[str] = []
    for a in links:
        href = a.get_attribute("href") or ""
        if "/@" not in href:
            continue
        handle = href.split("/@")[-1].split("?")[0].strip("/")
        if handle and handle not in handles:
            handles.append(handle)
    return handles


def get_first_followers(driver: webdriver.Chrome, max_count: int) -> List[str]:
    """
    在个人主页中打开"粉丝(Followers)"列表，获取前 max_count 个粉丝用户名。
    注意：定位器可能需要根据当前 TikTok 页面结构进行微调。
    如果超过3分钟无进展，返回已收集的数据。
    """
    start_time = time.time()
    TIMEOUT_SECONDS = 180  # 3分钟超时
    
    # 1. 点击"粉丝"标签/按钮/数字
    clicked = False

    # 先尝试国际版英文 UI
    candidates = [
        (By.XPATH, '//button[contains(., "Followers")]'),
        (By.XPATH, '//a[contains(., "Followers")]'),
        (By.CSS_SELECTOR, 'a[href*="followers"]'),
    ]

    for by, value in candidates:
        try:
            safe_click(driver, (by, value), timeout=5)
            clicked = True
            break
        except Exception:
            continue

    # 如果是中文 UI，粉丝数字通常在 data-e2e="followers-count" 上
    if not clicked:
        try:
            elem = driver.find_element(By.CSS_SELECTOR, '[data-e2e="followers-count"]')
            driver.execute_script("arguments[0].click();", elem)
            clicked = True
        except Exception:
            clicked = False

    if not clicked:
        print('[警告] 未能自动点击"粉丝(Followers/粉丝)"区域，等待弹窗出现（最多等待3分钟）...', flush=True)
        # 等待弹窗出现，如果3分钟内没有出现就跳过
        remaining_time = int(TIMEOUT_SECONDS - (time.time() - start_time))
        if remaining_time <= 0:
            print("[超时跳过] 已超过3分钟，跳过该用户", flush=True)
            return []
        ok = wait_for_element_with_heartbeat(
            driver,
            (By.CSS_SELECTOR, 'div[data-e2e="follow-info-popup"], div[role="dialog"]'),
            remaining_time,
            "粉丝弹窗",
            heartbeat_seconds=5,
        )
        if not ok:
            print("[超时跳过] 3分钟内未检测到粉丝弹窗，跳过该用户", flush=True)
            return []
        print("[成功] 检测到粉丝弹窗已打开", flush=True)

    # 检查超时
    if time.time() - start_time > TIMEOUT_SECONDS:
        print("[超时] 点击粉丝按钮耗时超过3分钟，跳过")
        return []

    # 2. 等粉丝列表弹窗出现（容器选择器请根据实际页面调整）
    remaining_time = int(min(EXPLICIT_WAIT_SECONDS, TIMEOUT_SECONDS - (time.time() - start_time)))
    if remaining_time <= 0:
        print("[超时跳过] 已超过3分钟，跳过该用户", flush=True)
        return []
    ok = wait_for_element_with_heartbeat(
        driver,
        (By.CSS_SELECTOR, 'div[data-e2e="follow-info-popup"], div[role="dialog"]'),
        remaining_time,
        "粉丝列表弹窗",
        heartbeat_seconds=5,
    )
    if not ok:
        print("[警告] 未检测到粉丝弹窗，尝试直接在页面中采集粉丝链接。", flush=True)

    # 检查超时
    if time.time() - start_time > TIMEOUT_SECONDS:
        print("[超时] 等待粉丝弹窗耗时超过3分钟，跳过")
        return []

    # 3. 不断滚动列表，按顺序收集 a[href*="/@"] 链接，确保获取前 max_count 个
    collected: List[str] = []
    collected_set: Set[str] = set()
    last_collected_count = 0
    no_progress_count = 0
    scroll_step = 300  # 每次滚动300px，平衡速度和准确性

    # 找到滚动容器
    popup = None
    list_container = None
    try:
        popup = driver.find_element(By.CSS_SELECTOR, 'div[data-e2e="follow-info-popup"]')
    except NoSuchElementException:
        try:
            popup = driver.find_element(By.CSS_SELECTOR, 'div[role="dialog"]')
        except NoSuchElementException:
            popup = None

    if popup:
        container_selectors = [
            'div[class*="DivUserListContainer"]',
            'div[class*="DivUsersListContainer"]',
            'div[class*="DivUserContainer"]',
            'div[class*="DivListContainer"]',
        ]
        for sel in container_selectors:
            try:
                list_container = popup.find_element(By.CSS_SELECTOR, sel)
                break
            except NoSuchElementException:
                continue
        if not list_container:
            list_container = popup

    scroll_container = list_container if list_container else popup

    # 通用兜底：如果弹窗内存在空列表容器，且未找到任何 /@ 用户链接，则直接判定为不可采集并快速返回
    try:
        popup_for_check = popup if popup else driver
        empty_blocks = popup_for_check.find_elements(By.CSS_SELECTOR, 'div[class*="DivEmptyListContainer"]')
        maybe_links = []
        if list_container:
            maybe_links = list_container.find_elements(By.CSS_SELECTOR, 'a[href^="/@"], a[href*="/@"]')
        elif popup:
            maybe_links = popup.find_elements(By.CSS_SELECTOR, 'a[href^="/@"], a[href*="/@"]')

        if empty_blocks and len(maybe_links) == 0:
            empty_text = (empty_blocks[0].text or "").strip()
            print(f"[跳过] following列表不可采集(empty_container命中，link=0) | text={empty_text[:120]}")
            close_popup_if_open(driver)
            return []
    except Exception:
        pass

    # 开始滚动收集
    for scroll_round in range(150):  # 增加最大滚动次数到150
        # 检查超时
        if time.time() - start_time > TIMEOUT_SECONDS:
            print(f"[超时] 采集粉丝列表耗时超过3分钟，已收集 {len(collected)} 个，返回")
            return collected[:max_count]
        
        # 如果已经收集到足够的数量，立即返回
        if len(collected) >= max_count:
            print(f"[完成] 已收集到目标数量 {len(collected)} 个粉丝")
            return collected[:max_count]
        
        # 在用户列表容器中找用户名链接
        if list_container:
            links = list_container.find_elements(By.CSS_SELECTOR, 'a[href^="/@"]')
            if not links:
                links = list_container.find_elements(By.CSS_SELECTOR, 'a[href*="/@"]')
        elif popup:
            links = popup.find_elements(By.CSS_SELECTOR, 'a[href^="/@"]')
            if not links:
                links = popup.find_elements(By.CSS_SELECTOR, 'a[href*="/@"]')
        else:
            links = driver.find_elements(By.CSS_SELECTOR, 'a[href*="/@"]')

        # 提取用户名，按顺序添加（保持DOM中的顺序）
        handles = extract_handles_from_links(links)
        new_count = 0
        for h in handles:
            if h not in collected_set:
                collected_set.add(h)
                collected.append(h)
                new_count += 1
                # 达到目标数量立即返回
                if len(collected) >= max_count:
                    print(f"[完成] 已收集到目标数量 {len(collected)} 个粉丝")
                    return collected[:max_count]

        # 每10轮输出一次进度
        if scroll_round % 10 == 0 and scroll_round > 0:
            print(f"  [进度] 已滚动 {scroll_round} 轮，收集到 {len(collected)}/{max_count} 个粉丝")

        # 检查是否有进展
        if new_count == 0:
            no_progress_count += 1
            # 如果连续10次无新数据，且还没达到目标数量，继续尝试
            if no_progress_count >= 10:
                # 检查是否真的到底了：尝试滚动到底部
                if scroll_container:
                    max_scroll = driver.execute_script("return arguments[0].scrollHeight;", scroll_container)
                    current_scroll = driver.execute_script("return arguments[0].scrollTop;", scroll_container)
                    client_height = driver.execute_script("return arguments[0].clientHeight;", scroll_container)
                    
                    # 如果已经滚动到底部（或接近底部），说明真的没有更多内容了
                    if current_scroll + client_height >= max_scroll - 50:
                        print(f"[完成] 已滚动到底部，收集到 {len(collected)} 个粉丝（目标: {max_count}）")
                        break
                    else:
                        # 还没到底，继续滚动
                        no_progress_count = 0
                        print(f"  [提示] 暂时无新数据，但未到底部，继续滚动...")
        else:
            no_progress_count = 0
            last_collected_count = len(collected)

        # 如果已经收集到足够的数量，立即返回
        if len(collected) >= max_count:
            print(f"[完成] 已收集到目标数量 {len(collected)} 个粉丝")
            return collected[:max_count]

        # 精细滚动：每次滚动较小距离，确保不跳过用户
        if scroll_container:
            current_scroll = driver.execute_script("return arguments[0].scrollTop;", scroll_container)
            driver.execute_script(
                f"arguments[0].scrollTop = {current_scroll} + {scroll_step};",
                scroll_container,
            )
        else:
            driver.execute_script(f"window.scrollBy(0, {scroll_step});")
        
        # 等待新内容加载（增加等待时间，确保内容加载完成）
        time.sleep(0.8)

    # 返回前关闭弹窗（如果有）
    close_popup_if_open(driver)
    return collected[:max_count]


def open_user_profile_direct(driver: webdriver.Chrome, username: str) -> bool:
    """通过直接访问 https://www.tiktok.com/@username 打开主页"""
    handle = username.lstrip("@")
    url = f"https://www.tiktok.com/@{handle}"
    driver.get(url)
    try:
        WebDriverWait(driver, EXPLICIT_WAIT_SECONDS).until(
            EC.presence_of_element_located((By.CSS_SELECTOR, 'h1, h2, [data-e2e="user-title"]'))
        )
        return True
    except TimeoutException:
        print(f"[警告] 打开用户主页失败: {username}")
        return False


def is_private_account(driver: webdriver.Chrome) -> bool:
    """
    检测当前个人主页是否为"私密账号"。
    根据HTML结构精确判断：查找包含"这是私密账号"文本的p标签元素。
    """
    try:
        # 方法1：通过精确的CSS选择器查找包含"这是私密账号"的p标签
        # 根据截图，该元素有 data-e2e 属性，class包含 PTitle
        private_elements = driver.find_elements(
            By.XPATH, 
            '//p[contains(text(), "这是私密账号")]'
        )
        if private_elements:
            return True
        
        # 方法2：查找包含"关注此帐号,即可查看他的内容和点赞的内容"的p标签
        private_elements = driver.find_elements(
            By.XPATH,
            '//p[contains(text(), "关注此帐号")]'
        )
        if private_elements:
            return True
        
        # 方法3：英文版判断
        private_elements = driver.find_elements(
            By.XPATH,
            '//p[contains(text(), "This account is private")]'
        )
        if private_elements:
            return True
            
    except Exception:
        pass
    
    return False


def get_first_following(driver: webdriver.Chrome, max_count: int) -> List[str]:
    """
    在个人主页中打开"关注(Following)"列表，获取前 max_count 个关注用户名。
    如果超过3分钟无进展，返回已收集的数据。
    """
    start_time = time.time()
    TIMEOUT_SECONDS = 180  # 3分钟超时

    try:
        if int(max_count) <= 0:
            print(f"[警告] max_count={max_count}，将导致采集0条关注；请检查 max_following_per_user 配置", flush=True)
    except Exception:
        pass
    
    clicked = False

    # 直接点击头像下方的"已关注"数字/文字区域，避免点到左侧导航栏的"已关注"入口
    # 你的截图里 follow 数字是在 strong[data-e2e="following-count"] 上，
    # 旁边是 span[data-e2e="following"]，它们都位于个人资料计数区，而不是左侧菜单。
    try:
        # 先找到数字元素
        num_elem = driver.find_element(By.CSS_SELECTOR, 'strong[data-e2e="following-count"]')
        # 再找它的父容器（整块"已关注"区域）
        container = num_elem.find_element(By.XPATH, "./..")
        driver.execute_script("arguments[0].click();", container)
        clicked = True
    except Exception:
        # 退一步，直接点紧挨着数字的"已关注"文字 span（同样在头像下方区域）
        try:
            text_elem = driver.find_element(By.CSS_SELECTOR, 'span[data-e2e="following"]')
            driver.execute_script("arguments[0].click();", text_elem)
            clicked = True
        except Exception:
            clicked = False

    if not clicked:
        print('[警告] 未能自动点击"关注(Following/已关注)"区域，等待弹窗出现（最多等待3分钟）...', flush=True)
        # 等待弹窗出现，如果3分钟内没有出现就跳过
        remaining_time = int(TIMEOUT_SECONDS - (time.time() - start_time))
        if remaining_time <= 0:
            print("[超时跳过] 已超过3分钟，跳过该用户", flush=True)
            return []
        ok = wait_for_element_with_heartbeat(
            driver,
            (By.CSS_SELECTOR, 'div[data-e2e="follow-info-popup"], div[role="dialog"]'),
            remaining_time,
            "关注弹窗",
            heartbeat_seconds=5,
        )
        if not ok:
            print("[超时跳过] 3分钟内未检测到关注弹窗，跳过该用户", flush=True)
            return []
        print("[成功] 检测到关注弹窗已打开", flush=True)

    # 检查超时
    if time.time() - start_time > TIMEOUT_SECONDS:
        print("[超时] 点击关注按钮耗时超过3分钟，跳过")
        return []

    # 优先等待 data-e2e="follow-info-popup" 这个关注弹窗容器
    remaining_time = int(min(EXPLICIT_WAIT_SECONDS, TIMEOUT_SECONDS - (time.time() - start_time)))
    if remaining_time <= 0:
        print("[超时跳过] 已超过3分钟，跳过该用户", flush=True)
        return []

    ok_popup = wait_for_element_with_heartbeat(
        driver,
        (By.CSS_SELECTOR, 'div[data-e2e="follow-info-popup"]'),
        remaining_time,
        "关注弹窗(data-e2e)",
        heartbeat_seconds=5,
    )

    if not ok_popup:
        # 兼容旧结构：退回到 role="dialog"
        remaining_time = int(min(EXPLICIT_WAIT_SECONDS, TIMEOUT_SECONDS - (time.time() - start_time)))
        if remaining_time <= 0:
            print("[超时跳过] 已超过3分钟，跳过该用户", flush=True)
            return []
        ok_dialog = wait_for_element_with_heartbeat(
            driver,
            (By.CSS_SELECTOR, 'div[role="dialog"]'),
            remaining_time,
            "关注弹窗(role=dialog)",
            heartbeat_seconds=5,
        )
        if not ok_dialog:
            print("[警告] 未检测到关注弹窗，尝试直接在页面中采集关注链接。", flush=True)

    # 检查超时
    if time.time() - start_time > TIMEOUT_SECONDS:
        print("[超时] 等待关注弹窗耗时超过3分钟，跳过")
        return []

    # 等待弹窗内容加载完成
    time.sleep(1.0)

    # 检测关注列表是否设为私密状态
    try:
        # 方法1：通过XPath精确查找包含私密提示文本的div元素
        # 根据HTML结构，这些文本在 class包含"DivTitle"或"DivDesc"的div中
        private_selectors = [
            # 中文提示文本
            '//div[contains(@class, "DivTitle") and contains(text(), "此账号已将关注列表设为私密状态")]',
            '//div[contains(@class, "DivDesc") and contains(text(), "关注列表当前已隐藏")]',
            '//div[contains(text(), "此账号已将关注列表设为私密状态")]',
            '//div[contains(text(), "关注列表当前已隐藏")]',
            # 英文提示文本
            '//div[contains(text(), "following list is private")]',
            '//div[contains(text(), "following list is currently hidden")]',
            # 西语提示文本（你提供的页面）
            '//div[contains(text(), "La lista de cuentas a las que sigue esta cuenta es privada")]',
            '//div[contains(text(), "actualmente está oculta")]',
            # 查找空列表容器（私密状态时会出现DivEmptyListContainer）
            '//div[contains(@class, "DivEmptyListContainer")]//div[contains(text(), "私密")]',
            '//div[contains(@class, "DivEmptyListContainer")]//div[contains(text(), "private")]',
            '//div[contains(@class, "DivEmptyListContainer")]//div[contains(text(), "privada")]',
            '//div[contains(@class, "DivEmptyListContainer")]//div[contains(text(), "oculta")]',
        ]
        
        for selector in private_selectors:
            try:
                elems = driver.find_elements(By.XPATH, selector)
                if elems:
                    print(f"[跳过] 该账号的关注列表已设为私密状态 | selector命中: {selector[:80]}")
                    close_popup_if_open(driver)
                    return []
            except Exception:
                continue
        
        # 方法2：在弹窗内查找空列表容器，并检查其文本内容
        try:
            popup_elem = driver.find_element(By.CSS_SELECTOR, 'div[data-e2e="follow-info-popup"]')
            # 查找空列表容器
            empty_container = popup_elem.find_element(By.CSS_SELECTOR, 'div[class*="DivEmptyListContainer"]')
            if empty_container:
                container_text = empty_container.text or ""
                private_keywords = [
                    "此账号已将关注列表设为私密状态",
                    "关注列表当前已隐藏",
                    "following list is private",
                    "following list is currently hidden",
                    "La lista de cuentas a las que sigue esta cuenta es privada",
                    "actualmente está oculta"
                ]
                if any(keyword in container_text for keyword in private_keywords):
                    print("[跳过] 该账号的关注列表已设为私密状态")
                    close_popup_if_open(driver)
                    return []
        except NoSuchElementException:
            pass
            
    except Exception as e:
        # 如果检测过程出错，不影响后续流程，继续执行
        pass

    collected: List[str] = []
    collected_set: Set[str] = set()
    last_collected_count = 0
    no_progress_count = 0
    scroll_step = 300  # 每次滚动300px，平衡速度和准确性

    # 找到滚动容器
    popup = None
    list_container = None
    try:
        popup = driver.find_element(By.CSS_SELECTOR, 'div[data-e2e="follow-info-popup"]')
    except NoSuchElementException:
        try:
            popup = driver.find_element(By.CSS_SELECTOR, 'div[role="dialog"]')
        except NoSuchElementException:
            popup = None

    if popup:
        container_selectors = [
            'div[class*="DivUserListContainer"]',
            'div[class*="DivUsersListContainer"]',
            'div[class*="DivUserContainer"]',
            'div[class*="DivListContainer"]',
        ]
        for sel in container_selectors:
            try:
                list_container = popup.find_element(By.CSS_SELECTOR, sel)
                break
            except NoSuchElementException:
                continue
        if not list_container:
            list_container = popup

    scroll_container = list_container if list_container else popup

    # 开始滚动收集
    for scroll_round in range(150):  # 增加最大滚动次数到150
        # 检查超时
        if time.time() - start_time > TIMEOUT_SECONDS:
            print(f"[超时] 采集关注列表耗时超过3分钟，已收集 {len(collected)} 个，返回")
            return collected[:max_count]
        
        # 如果已经收集到足够的数量，立即返回
        if len(collected) >= max_count:
            print(f"[完成] 已收集到目标数量 {len(collected)} 个关注")
            return collected[:max_count]
        
        # 只在用户列表容器中查找 /@ 开头的个人主页链接，避免误抓到弹窗内其他链接
        if list_container:
            links = list_container.find_elements(By.CSS_SELECTOR, 'a[href^="/@"]')
            if not links:
                links = list_container.find_elements(By.CSS_SELECTOR, 'a[href*="/@"]')
        elif popup:
            links = popup.find_elements(By.CSS_SELECTOR, 'a[href^="/@"]')
            if not links:
                links = popup.find_elements(By.CSS_SELECTOR, 'a[href*="/@"]')
        else:
            links = driver.find_elements(By.CSS_SELECTOR, 'a[href^="/@"]')

        # 提取用户名，按顺序添加（保持DOM中的顺序）
        handles = extract_handles_from_links(links)
        new_count = 0
        for h in handles:
            if h not in collected_set:
                collected_set.add(h)
                collected.append(h)
                new_count += 1
                # 达到目标数量立即返回
                if len(collected) >= max_count:
                    print(f"[完成] 已收集到目标数量 {len(collected)} 个关注")
                    return collected[:max_count]

        # 每10轮输出一次进度
        if scroll_round % 10 == 0 and scroll_round > 0:
            print(f"  [进度] 已滚动 {scroll_round} 轮，收集到 {len(collected)}/{max_count} 个关注")

        # 检查是否有进展
        if new_count == 0:
            no_progress_count += 1
            # 如果连续10次无新数据，且还没达到目标数量，继续尝试
            if no_progress_count >= 10:
                # 检查是否真的到底了：尝试滚动到底部
                if scroll_container:
                    max_scroll = driver.execute_script("return arguments[0].scrollHeight;", scroll_container)
                    current_scroll = driver.execute_script("return arguments[0].scrollTop;", scroll_container)
                    client_height = driver.execute_script("return arguments[0].clientHeight;", scroll_container)
                    
                    # 如果已经滚动到底部（或接近底部），说明真的没有更多内容了
                    if current_scroll + client_height >= max_scroll - 50:
                        print(f"[完成] 已滚动到底部，收集到 {len(collected)} 个关注（目标: {max_count}）")
                        break
                    else:
                        # 还没到底，继续滚动
                        no_progress_count = 0
                        print(f"  [提示] 暂时无新数据，但未到底部，继续滚动...")
        else:
            no_progress_count = 0
            last_collected_count = len(collected)

        # 如果已经收集到足够的数量，立即返回
        if len(collected) >= max_count:
            print(f"[完成] 已收集到目标数量 {len(collected)} 个关注")
            return collected[:max_count]

        # 精细滚动：每次滚动较小距离，确保不跳过用户
        if scroll_container:
            current_scroll = driver.execute_script("return arguments[0].scrollTop;", scroll_container)
            driver.execute_script(
                f"arguments[0].scrollTop = {current_scroll} + {scroll_step};",
                scroll_container,
            )
        else:
            driver.execute_script(f"window.scrollBy(0, {scroll_step});")
        
        # 等待新内容加载（增加等待时间，确保内容加载完成）
        time.sleep(0.8)

    # 返回前关闭弹窗（如果有）
    close_popup_if_open(driver)
    return collected[:max_count]


# ================== Excel 读写 ==================

def read_root_usernames_from_excel(
    path: str,
    sheet_name: str | None,
    col_letter: str,
    status_col_letter: str,
    type_col_letter: str,
    skip_completed: bool = True,
) -> List[Tuple[str, str, str]]:
    """
    从 Excel 中读取根用户名列表、状态与博主类型。
    返回: List[Tuple[username, status, blogger_type]]
    skip_completed: 是否跳过状态为"读取完成"的用户
    """
    if not os.path.exists(path):
        raise FileNotFoundError(f"未找到 Excel 文件: {path}")

    wb = load_workbook(path)
    ws = wb[sheet_name] if sheet_name else wb[wb.sheetnames[0]]

    # 检查是否有表头
    start_row = 2 if ws[f"{col_letter}1"].value and "用户名" in str(ws[f"{col_letter}1"].value) else 1
    
    usernames: List[Tuple[str, str, str]] = []
    for row_idx in range(start_row, ws.max_row + 1):
        username_cell = ws[f"{col_letter}{row_idx}"]
        status_cell = ws[f"{status_col_letter}{row_idx}"]
        type_cell = ws[f"{type_col_letter}{row_idx}"]

        username_value = str(username_cell.value).strip() if username_cell.value else ""
        if not username_value:
            continue

        status_value = str(status_cell.value).strip() if status_cell.value else ""
        type_value = str(type_cell.value).strip() if type_cell.value else ""

        # 如果跳过已完成的任务
        if skip_completed and status_value == "读取完成":
            continue

        usernames.append((username_value, status_value, type_value))

    return usernames


def init_input_excel_headers(path: str, sheet_name: str | None):
    """初始化input Excel文件的表头（如果不存在）"""
    if not os.path.exists(path):
        return

    try:
        wb = load_workbook(path)
        ws = wb[sheet_name] if sheet_name else wb[wb.sheetnames[0]]

        # 检查第一行是否有表头
        if ws[f"{USERNAME_COLUMN_LETTER}1"].value is None or str(ws[f"{USERNAME_COLUMN_LETTER}1"].value).strip() == "":
            # 如果没有表头，添加表头
            ws[f"{USERNAME_COLUMN_LETTER}1"] = "用户名"
            ws[f"{STATUS_COLUMN_LETTER}1"] = "状态"
            ws[f"{DATE_COLUMN_LETTER}1"] = "日期"
            ws[f"{TYPE_COLUMN_LETTER}1"] = "类型"
            wb.save(path)
            print("[初始化] 已添加表头")
    except Exception as e:
        print(f"[警告] 初始化表头失败: {e}")


def update_input_status(path: str, sheet_name: str | None, username: str, status: str):
    """
    更新input Excel文件中指定用户名的状态和日期。
    status: "读取完成"、"读取中"、"读取中断"
    """
    if not os.path.exists(path):
        return
    
    try:
        wb = load_workbook(path)
        ws = wb[sheet_name] if sheet_name else wb[wb.sheetnames[0]]
        
        # 查找用户名所在行
        for row_idx in range(1, ws.max_row + 1):
            cell_value = ws[f"{USERNAME_COLUMN_LETTER}{row_idx}"].value
            if cell_value and str(cell_value).strip().lower() == username.lower().strip():
                # 更新状态和日期
                current_date = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                ws[f"{STATUS_COLUMN_LETTER}{row_idx}"] = status
                ws[f"{DATE_COLUMN_LETTER}{row_idx}"] = current_date
                wb.save(path)
                return
        
        print(f"[警告] 未找到用户名: {username}，无法更新状态")
    except Exception as e:
        print(f"[警告] 更新状态失败: {e}")


def deduplicate_input_excel(path: str, sheet_name: str | None, col_letter: str):
    """
    对input Excel文件进行去重并持久化。
    读取文件，去重后写回原文件。
    同时初始化表头（如果不存在）。
    """
    if not os.path.exists(path):
        print(f"[警告] 未找到 Excel 文件: {path}，跳过去重")
        return

    try:
        # 先初始化表头
        init_input_excel_headers(path, sheet_name)

        wb = load_workbook(path)
        ws = wb[sheet_name] if sheet_name else wb[wb.sheetnames[0]]

        # 读取所有用户名（跳过表头）
        usernames: List[str] = []
        statuses: List[str] = []
        dates: List[str] = []
        types: List[str] = []

        start_row = 2 if ws[f"{col_letter}1"].value and "用户名" in str(ws[f"{col_letter}1"].value) else 1

        for row_idx in range(start_row, ws.max_row + 1):
            username_cell = ws[f"{col_letter}{row_idx}"]
            status_cell = ws[f"{STATUS_COLUMN_LETTER}{row_idx}"]
            date_cell = ws[f"{DATE_COLUMN_LETTER}{row_idx}"]
            type_cell = ws[f"{TYPE_COLUMN_LETTER}{row_idx}"]

            username_value = str(username_cell.value).strip() if username_cell.value else ""
            if not username_value:
                continue

            usernames.append(username_value)
            statuses.append(str(status_cell.value).strip() if status_cell.value else "")
            dates.append(str(date_cell.value).strip() if date_cell.value else "")
            types.append(str(type_cell.value).strip() if type_cell.value else "")

        # 去重（保持顺序）
        seen = set()
        unique_usernames = []
        unique_statuses = []
        unique_dates = []
        unique_types = []

        for idx, username in enumerate(usernames):
            username_lower = username.lower().strip()
            if username_lower and username_lower not in seen:
                seen.add(username_lower)
                unique_usernames.append(username)
                unique_statuses.append(statuses[idx] if idx < len(statuses) else "")
                unique_dates.append(dates[idx] if idx < len(dates) else "")
                unique_types.append(types[idx] if idx < len(types) else "")

        # 如果去重后有变化，写回文件
        if len(unique_usernames) != len(usernames):
            # 清空原列（保留表头）
            for row_idx in range(2, ws.max_row + 1):
                ws[f"{col_letter}{row_idx}"].value = None
                ws[f"{STATUS_COLUMN_LETTER}{row_idx}"].value = None
                ws[f"{DATE_COLUMN_LETTER}{row_idx}"].value = None
                ws[f"{TYPE_COLUMN_LETTER}{row_idx}"].value = None

            # 写入去重后的数据
            for idx, username in enumerate(unique_usernames, start=2):
                ws[f"{col_letter}{idx}"] = username
                if idx - 2 < len(unique_statuses) and unique_statuses[idx - 2]:
                    ws[f"{STATUS_COLUMN_LETTER}{idx}"] = unique_statuses[idx - 2]
                if idx - 2 < len(unique_dates) and unique_dates[idx - 2]:
                    ws[f"{DATE_COLUMN_LETTER}{idx}"] = unique_dates[idx - 2]
                if idx - 2 < len(unique_types) and unique_types[idx - 2]:
                    ws[f"{TYPE_COLUMN_LETTER}{idx}"] = unique_types[idx - 2]

            wb.save(path)
            print(f"[去重完成] input文件已去重：原 {len(usernames)} 条，去重后 {len(unique_usernames)} 条")
        else:
            print(f"[去重完成] input文件无需去重：{len(usernames)} 条记录")

    except Exception as e:
        print(f"[警告] input文件去重失败: {e}，继续执行")


def get_next_txt_filename() -> str:
    """获取下一个txt文件名（数字1-20循环_扳机_日期_count+1）"""
    today = datetime.now().strftime("%Y%m%d")
    base_dir = "."
    
    # 查找当天已有的文件，匹配模式：数字_扳机_日期_count.txt
    pattern = f"[0-9]*_扳机_{today}_*.txt"
    existing_files = glob.glob(os.path.join(base_dir, pattern))
    
    # 提取最大计数和使用的数字
    max_count = 0
    used_numbers = set()
    
    for file in existing_files:
        try:
            basename = os.path.basename(file).replace(".txt", "")
            # 格式：数字_扳机_日期_计数
            parts = basename.split("_")
            if len(parts) >= 4 and parts[1] == "扳机" and parts[2] == today:
                number = int(parts[0])
                count = int(parts[3])
                used_numbers.add(number)
                max_count = max(max_count, count)
        except (ValueError, IndexError):
            continue
    
    # 选择数字（1-20循环）
    if used_numbers:
        # 找到最小的未使用数字，或者最大的数字+1（如果都用了就循环）
        max_used = max(used_numbers) if used_numbers else 0
        next_number = (max_used % 20) + 1
    else:
        next_number = 1
    
    # 计算新计数
    new_count = max_count + 1
    
    return f"{next_number}_扳机_{today}_{new_count}.txt"


def save_following_ids_to_txt(records: List[EdgeRecord]):
    """
    将records中的following_username（C列）保存到txt文件。
    如果当前文件超过500个ID，创建新文件。
    """
    # 提取所有following_username并去重
    following_ids = set()
    for r in records:
        if r.following_username:
            following_ids.add(r.following_username)
    
    if not following_ids:
        return
    
    # 查找最新的txt文件
    today = datetime.now().strftime("%Y%m%d")
    pattern = f"[0-9]*_扳机_{today}_*.txt"
    existing_files = glob.glob(pattern)
    
    current_file = None
    current_ids = set()
    
    if existing_files:
        # 找到最新的文件（按计数排序）
        existing_files.sort(key=lambda x: int(os.path.basename(x).split("_")[-1].replace(".txt", "")) if os.path.basename(x).split("_")[-1].replace(".txt", "").isdigit() else 0, reverse=True)
        latest_file = existing_files[0]
        
        # 读取现有ID
        try:
            with open(latest_file, 'r', encoding='utf-8') as f:
                current_ids = {line.strip() for line in f if line.strip()}
            
            # 检查是否还能添加
            if len(current_ids) < MAX_TXT_IDS:
                current_file = latest_file
        except Exception as e:
            print(f"[警告] 读取现有txt文件失败: {e}")
    
    # 合并ID（去重）
    all_ids = current_ids | following_ids
    
    # 如果当前文件存在且合并后不超过限制，追加到现有文件
    if current_file and len(all_ids) <= MAX_TXT_IDS:
        # 追加到现有文件
        with open(current_file, 'w', encoding='utf-8') as f:
            for id in sorted(all_ids):
                f.write(f"{id}\n")
        print(f"ID已追加到: {current_file}（当前 {len(all_ids)} 个ID）")
    else:
        # 需要创建新文件
        remaining_ids = []
        
        # 如果当前文件已满，当前文件保持不变，只保存新增ID
        if current_file and len(current_ids) >= MAX_TXT_IDS:
            remaining_ids = list(following_ids)
        elif current_file and len(current_ids) > 0:
            # 当前文件未满，但合并后会超过，先保存当前文件的500个
            ids_to_save_current = sorted(list(current_ids))[:MAX_TXT_IDS]
            with open(current_file, 'w', encoding='utf-8') as f:
                for id in ids_to_save_current:
                    f.write(f"{id}\n")
            print(f"ID已保存到: {current_file}（{len(ids_to_save_current)} 个ID，达到上限）")
            # 剩余ID = 所有ID - 已保存的ID
            remaining_ids = sorted(list(all_ids - set(ids_to_save_current)))
        else:
            # 没有当前文件，所有ID都是新的
            remaining_ids = sorted(list(following_ids))
        
        # 保存剩余ID到新文件（循环分割，每个文件最多500个）
        while remaining_ids:
            new_file = get_next_txt_filename()
            ids_to_save = remaining_ids[:MAX_TXT_IDS]
            remaining_ids = remaining_ids[MAX_TXT_IDS:]
            
            with open(new_file, 'w', encoding='utf-8') as f:
                for id in ids_to_save:
                    f.write(f"{id}\n")
            print(f"ID已保存到新文件: {new_file}（{len(ids_to_save)} 个ID）")


def get_next_excel_filename(base_path: str) -> str:
    """获取下一个Excel文件名（带日期和计数）"""
    today = datetime.now().strftime("%Y%m%d")
    base_name = os.path.splitext(base_path)[0]
    base_dir = os.path.dirname(base_path) or "."
    
    # 查找当天已有的文件
    pattern = f"{base_name}_{today}_*.xlsx"
    existing_files = glob.glob(os.path.join(base_dir, pattern))
    
    # 提取最大计数
    max_count = 0
    for file in existing_files:
        try:
            # 从文件名中提取计数：output_follow_graph_20240101_1.xlsx
            parts = os.path.basename(file).replace(f"{os.path.basename(base_name)}_{today}_", "").replace(".xlsx", "")
            count = int(parts)
            max_count = max(max_count, count)
        except ValueError:
            continue
    
    # 返回新文件名
    new_count = max_count + 1
    return f"{base_name}_{today}_{new_count}.xlsx"


def read_existing_excel_records(path: str) -> List[EdgeRecord]:
    """读取已有Excel文件中的记录"""
    if not os.path.exists(path):
        return []
    
    try:
        wb = load_workbook(path)
        ws = wb.active
        
        existing_records = []
        # 跳过表头，从第2行开始读取
        for row in ws.iter_rows(min_row=2, values_only=True):
            if len(row) >= 3 and row[0] and row[1] and row[2]:
                existing_records.append(EdgeRecord(
                    root_username=str(row[0]).strip(),
                    follower_username=str(row[1]).strip(),
                    following_username=str(row[2]).strip()
                ))
        return existing_records
    except Exception as e:
        print(f"[警告] 读取已有Excel文件失败: {e}")
        return []


def read_all_excel_files(base_path: str) -> List[EdgeRecord]:
    """读取所有相关的Excel文件（包括分割文件）"""
    all_records = []
    
    # 1. 读取主文件
    if os.path.exists(base_path):
        all_records.extend(read_existing_excel_records(base_path))
    
    # 2. 读取所有分割文件
    today = datetime.now().strftime("%Y%m%d")
    base_name = os.path.splitext(base_path)[0]
    base_dir = os.path.dirname(base_path) or "."
    pattern = f"{os.path.basename(base_name)}_{today}_*.xlsx"
    existing_files = glob.glob(os.path.join(base_dir, pattern))
    
    for file in existing_files:
        if file != base_path:  # 跳过主文件（已经读取过）
            all_records.extend(read_existing_excel_records(file))
    
    return all_records


def write_edges_to_excel(path: str, records: List[EdgeRecord]):
    """
    将结果写入Excel文件，保留已持久化的数据，合并新数据后去重保存。
    如果超过5000行，自动创建新文件。
    """
    # 1. 读取所有已有文件的数据（包括分割文件）
    existing_records = read_all_excel_files(path)
    
    # 2. 合并已有数据和新数据
    all_records = existing_records + records
    
    # 3. 对合并后的记录进行去重：使用三元组作为唯一键
    seen = set()
    unique_records = []
    for r in all_records:
        key = (r.root_username, r.follower_username, r.following_username)
        if key not in seen:
            seen.add(key)
            unique_records.append(r)
    
    # 4. 检查是否需要分割文件
    if len(unique_records) > MAX_EXCEL_ROWS:
        # 需要分割：循环保存，每个文件最多5000条
        current_path = path
        remaining_records = unique_records
        
        # 先删除所有旧的分割文件（因为要重新分配）
        today = datetime.now().strftime("%Y%m%d")
        base_name = os.path.splitext(path)[0]
        base_dir = os.path.dirname(path) or "."
        pattern = f"{os.path.basename(base_name)}_{today}_*.xlsx"
        old_files = glob.glob(os.path.join(base_dir, pattern))
        for old_file in old_files:
            if old_file != path:  # 保留主文件路径，但会覆盖内容
                try:
                    os.remove(old_file)
                except Exception:
                    pass
        
        while remaining_records:
            records_to_save = remaining_records[:MAX_EXCEL_ROWS]
            remaining_records = remaining_records[MAX_EXCEL_ROWS:]
            
            # 保存当前文件
            wb = Workbook()
            ws = wb.active
            ws.title = "result"
            ws.append(["root_username", "follower_username", "following_username"])
            for r in records_to_save:
                ws.append([r.root_username, r.follower_username, r.following_username])
            wb.save(current_path)
            print(f"结果已保存到: {current_path}（{len(records_to_save)} 条记录）")
            
            # 如果还有剩余记录，准备下一个文件
            if remaining_records:
                current_path = get_next_excel_filename(path)
    else:
        # 不需要分割，直接保存到主文件
        # 先删除所有旧的分割文件（因为数据已经合并到主文件了）
        today = datetime.now().strftime("%Y%m%d")
        base_name = os.path.splitext(path)[0]
        base_dir = os.path.dirname(path) or "."
        pattern = f"{os.path.basename(base_name)}_{today}_*.xlsx"
        old_files = glob.glob(os.path.join(base_dir, pattern))
        for old_file in old_files:
            if old_file != path:
                try:
                    os.remove(old_file)
                except Exception:
                    pass
        
        wb = Workbook()
        ws = wb.active
        ws.title = "result"
        ws.append(["root_username", "follower_username", "following_username"])
        for r in unique_records:
            ws.append([r.root_username, r.follower_username, r.following_username])
        wb.save(path)
        new_count = len(unique_records) - len(existing_records)
        print(f"结果已保存到: {path}（已有 {len(existing_records)} 条，新增 {new_count} 条，总计 {len(unique_records)} 条去重后的记录）")


# ================== 主流程 ==================

def run_collect_roots(cfg: dict, root_username_data: List[Tuple[str, str, str]] | None = None, source_label: str = "Excel", container_id: str | None = None):
    print("=== TikTok 关系采集脚本启动（抓 root 粉丝） ===")
    print(f"根用户来源: {source_label}")
    print(f"结果将写入 SQLite 数据库: {cfg['db_path']}")

    # 0. 初始化关系库表结构
    init_db_for_follow_graph(cfg["db_path"])

    if root_username_data is None:
        # 默认仍保留 Excel 流程
        print(f"源表(根用户名 Excel): {cfg['excel_input_path']}")
        print("\n[步骤0] 对input文件进行去重...")
        deduplicate_input_excel(
            cfg["excel_input_path"],
            cfg["input_sheet_name"],
            cfg["username_column_letter"],
        )

        # 读 Excel（跳过已完成的任务）
        root_username_data = read_root_usernames_from_excel(
            cfg["excel_input_path"],
            cfg["input_sheet_name"],
            cfg["username_column_letter"],
            cfg["status_column_letter"],
            cfg["type_column_letter"],
            skip_completed=True,
        )
        print(f"从 Excel 读取到 {len(root_username_data)} 个用户名（已跳过已完成的任务）")

    if not root_username_data:
        print("无可处理根用户，程序结束。")
        return

    # 3. 打开浏览器
    driver = init_driver(
        container_id=container_id,
        chrome_user_data_dir=cfg["chrome_user_data_dir"],
        hubstudio_local_api=cfg["hubstudio_local_api"],
        hubstudio_container_start_path=cfg["hubstudio_container_start_path"],
        hubstudio_container_list_path=cfg["hubstudio_container_list_path"],
    )

    try:
        # 批次汇总累加器（root -> follower_queue 入队效果）
        batch_total_roots = 0
        batch_root_blocked_private = 0
        batch_root_blocked_profile_unavailable = 0
        batch_inserted_root = 0
        batch_followers_input = 0
        batch_inserted_queue = 0
        batch_skipped_duplicate_queue = 0
        batch_skipped_private = 0
        batch_skipped_profile_unavailable = 0

        for idx, (root_name, current_status, blogger_type) in enumerate(root_username_data, start=1):
            type_label = blogger_type or "未分类"
            print(f"\n=== [{idx}/{len(root_username_data)}] 处理用户名: {root_name} | 类型: {type_label} ===", flush=True)
            print(f"[阶段] 开始处理 root={root_name}", flush=True)

            # 仅 Excel 来源时更新输入表状态
            if source_label.lower() == "excel":
                update_input_status(cfg["excel_input_path"], cfg["input_sheet_name"], root_name, "读取中")

            try:
                # 3.1 搜索并打开根用户主页
                print("[阶段] 打开 root 用户主页", flush=True)
                if not open_user_profile_by_search(driver, root_name):
                    if source_label.lower() == "excel":
                        update_input_status(
                            cfg["excel_input_path"],
                            cfg["input_sheet_name"],
                            root_name,
                            "读取中断",
                        )
                    continue

                # 3.2 获取其前 N 个粉丝
                print(f"[阶段] 开始采集粉丝列表（目标 {cfg['max_followers_per_user']}）", flush=True)
                followers_start_time = time.time()
                followers = get_first_followers(driver, cfg["max_followers_per_user"])
                print(f"[阶段] 粉丝采集完成，获取到粉丝 {len(followers)} 个（最多 {cfg['max_followers_per_user']}）", flush=True)

                # 如果获取粉丝超时，跳过当前根用户
                if time.time() - followers_start_time > 180:
                    print(f"[超时跳过] 获取根用户 {root_name} 的粉丝列表超时，跳过该用户")
                    close_popup_if_open(driver)
                    driver.back()
                    time.sleep(2)
                    update_input_status(cfg["excel_input_path"], cfg["input_sheet_name"], root_name, "读取中断")
                    continue

                # 粉丝入队（去重，仅入队一次）
                qstats = enqueue_followers(root_name, followers, blogger_type)
                print(
                    "[重点汇总] "
                    f"root=@{qstats.get('root') or root_name} | "
                    f"followers输入={qstats.get('followers_input', 0)} | "
                    f"写入tiktok_roots={qstats.get('inserted_root', 0)} | "
                    f"写入follower_queue={qstats.get('inserted_queue', 0)} | "
                    f"去重(已在队列)={qstats.get('skipped_duplicate_queue', 0)} | "
                    f"拦截(私密)={qstats.get('skipped_private', 0)} | "
                    f"拦截(profile_unavailable)={qstats.get('skipped_profile_unavailable', 0)} | "
                    f"root拦截(私密)={qstats.get('blocked_root_private', 0)} | "
                    f"root拦截(profile_unavailable)={qstats.get('blocked_root_profile_unavailable', 0)}",
                    flush=True,
                )

                # 批次累加
                batch_total_roots += 1
                batch_followers_input += int(qstats.get("followers_input", 0) or 0)
                batch_inserted_root += int(qstats.get("inserted_root", 0) or 0)
                batch_inserted_queue += int(qstats.get("inserted_queue", 0) or 0)
                batch_skipped_duplicate_queue += int(qstats.get("skipped_duplicate_queue", 0) or 0)
                batch_skipped_private += int(qstats.get("skipped_private", 0) or 0)
                batch_skipped_profile_unavailable += int(qstats.get("skipped_profile_unavailable", 0) or 0)
                batch_root_blocked_private += int(qstats.get("blocked_root_private", 0) or 0)
                batch_root_blocked_profile_unavailable += int(qstats.get("blocked_root_profile_unavailable", 0) or 0)

                # 仅 Excel 来源时更新输入表状态
                if source_label.lower() == "excel":
                    update_input_status(cfg["excel_input_path"], cfg["input_sheet_name"], root_name, "读取完成")

            except Exception as e:
                # 发生异常，Excel 来源才更新状态
                print(f"[错误] 处理用户名 {root_name} 时发生异常: {e}")
                if source_label.lower() == "excel":
                    update_input_status(cfg["excel_input_path"], cfg["input_sheet_name"], root_name, "读取中断")
                continue

    except KeyboardInterrupt:
        print("\n[中断] 用户中断程序")
        # 如果当前正在处理某个用户名，将其标记为"读取中断"
        if "root_name" in locals() and source_label.lower() == "excel":
            update_input_status(cfg["excel_input_path"], cfg["input_sheet_name"], root_name, "读取中断")
    finally:
        driver.quit()
        print("浏览器已关闭。")

    print(
        "=== root 粉丝采集完成（批次汇总） ===\n"
        f"根用户数(尝试enqueue)={batch_total_roots} | "
        f"root拦截(私密)={batch_root_blocked_private} | "
        f"root拦截(profile_unavailable)={batch_root_blocked_profile_unavailable}\n"
        f"写入tiktok_roots={batch_inserted_root} | "
        f"followers输入总数={batch_followers_input} | "
        f"写入follower_queue={batch_inserted_queue} | "
        f"去重(已在队列)={batch_skipped_duplicate_queue} | "
        f"拦截(私密)={batch_skipped_private} | "
        f"拦截(profile_unavailable)={batch_skipped_profile_unavailable}",
        flush=True,
    )


def fetch_root_batch_for_collect(limit: int = 100, priority: str = "high") -> List[Tuple[str, str, str]]:
    """
    从 tiktok_roots 取待采集根用户。
    priority:
      - high: 按 usage_count 升序、discovered_at 升序（久未执行优先）
      - medium: 按 discovered_at 升序、usage_count 升序（老根优先）
      - low: 按 usage_count 降序、discovered_at 降序（最近执行优先）
    返回: [(root_username, "", roots_type)]
    """
    p = str(priority or "high").strip().lower()
    if p == "low":
        order_sql = "usage_count DESC, discovered_at DESC"
    elif p == "medium":
        order_sql = "discovered_at ASC, usage_count ASC"
    else:
        order_sql = "usage_count ASC, discovered_at ASC"
    conn = _get_db()
    try:
        rows = conn.execute(
            f"""
            SELECT root_username, roots_type
            FROM tiktok_roots
            ORDER BY {order_sql}
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
        return [(str(r[0]).strip(), "", (str(r[1]).strip() if r[1] else "")) for r in rows if str(r[0]).strip()]
    finally:
        conn.close()


def run_collect_roots_from_db_once(
    cfg: dict,
    limit: int = 100,
    container_id: str | None = None,
    priority: str = "high",
):
    batch = fetch_root_batch_for_collect(limit=limit, priority=priority)
    print(f"从 tiktok_roots 读取到 {len(batch)} 个根用户用于采集 | priority={priority}")
    run_collect_roots(cfg, root_username_data=batch, source_label="tiktok_roots", container_id=container_id)


def run_collect_roots_from_db_loop(
    cfg: dict,
    limit: int = 100,
    interval_seconds: int = 300,
    container_id: str | None = None,
    priority: str = "high",
):
    print("=== TikTok 关系采集脚本启动（DB定时抓 root 粉丝） ===")
    print(f"根用户来源: tiktok_roots | 每轮上限: {limit} | 间隔: {interval_seconds}s | priority={priority}")
    init_db_for_follow_graph(cfg["db_path"])

    while True:
        run_collect_roots_from_db_once(cfg, limit=limit, container_id=container_id, priority=priority)
        print(f"等待 {interval_seconds} 秒后继续 DB 根用户采集...")
        time.sleep(interval_seconds)


def run_process_queue_once(cfg: dict, limit: int = 100, container_id: str | None = None):
    print("=== TikTok 关系采集脚本启动（处理 follower 队列） ===")
    print(f"结果将写入 SQLite 数据库: {cfg['db_path']}")

    init_db_for_follow_graph(cfg["db_path"])

    # 1. 打开浏览器
    driver = init_driver(
        container_id=container_id,
        chrome_user_data_dir=cfg["chrome_user_data_dir"],
        hubstudio_local_api=cfg["hubstudio_local_api"],
        hubstudio_container_start_path=cfg["hubstudio_container_start_path"],
        hubstudio_container_list_path=cfg["hubstudio_container_list_path"],
    )

    try:
        # 2. 处理 follower 队列（随机100个/批）
        batch = fetch_follower_batch(limit=limit)
        if not batch:
            print("无待处理 follower，队列为空")
            return

        print(f"本轮将处理 {len(batch)} 个 follower")
        processed: List[str] = []
        # 批次汇总累加器（follower -> following 回流效果）
        batch_followers = 0
        batch_following_candidates = 0
        batch_passed_quality = 0
        batch_inserted_roots = 0
        batch_inserted_pending = 0
        batch_skipped_quality = 0
        batch_skipped_private = 0
        batch_skipped_profile_unavailable = 0
        batch_skipped_existing_root = 0

        for f_idx, (follower_name, root_username, following_access_status, following_fetch_success, mobile_push_status) in enumerate(batch, start=1):
            follower_start_time = time.time()
            stage_open_direct = 0.0
            stage_open_search = 0.0
            stage_open_total = 0.0
            stage_private_check = 0.0
            stage_collect_following = 0.0
            stage_save_db = 0.0
            print(f"  -> follower [{f_idx}/{len(batch)}]: {follower_name} (root={root_username})", flush=True)
            print(f"    [阶段] 开始处理 follower={follower_name}", flush=True)

            # 打开粉丝主页（直接 @username 访问，失败再尝试搜索）
            print("    [阶段] 尝试直接打开主页", flush=True)
            open_start = time.time()
            direct_start = time.time()
            opened = open_user_profile_direct(driver, follower_name)
            stage_open_direct = time.time() - direct_start
            if not opened:
                print("    [阶段] 直接打开失败，改用搜索", flush=True)
                search_start = time.time()
                opened = open_user_profile_by_search(driver, follower_name)
                stage_open_search = time.time() - search_start
                if not opened:
                    stage_open_total = time.time() - open_start
                    total_cost = time.time() - follower_start_time
                    print(
                        f"    [跳过] 无法打开粉丝主页: {follower_name} | 耗时统计: 直开={stage_open_direct:.1f}s, 搜索={stage_open_search:.1f}s, 打开总计={stage_open_total:.1f}s, follower总计={total_cost:.1f}s",
                        flush=True,
                    )
                    update_follower_queue_status(follower_name, "not_found", "not_eligible")
                    processed.append(follower_name)
                    continue
            stage_open_total = time.time() - open_start
            print(
                f"    [耗时] 打开主页阶段: 直开={stage_open_direct:.1f}s, 搜索={stage_open_search:.1f}s, 小计={stage_open_total:.1f}s",
                flush=True,
            )

            # 检查超时
            if time.time() - follower_start_time > 180:
                print(f"    [超时跳过] 打开粉丝主页超时: {follower_name}", flush=True)
                close_popup_if_open(driver)
                driver.back()
                time.sleep(2)
                update_follower_queue_status(follower_name, "not_found", "not_eligible")
                processed.append(follower_name)
                continue

            # 私密账号直接跳过
            private_check_start = time.time()
            private_account = is_private_account(driver)
            stage_private_check = time.time() - private_check_start
            print(f"    [耗时] 私密检测阶段: {stage_private_check:.1f}s", flush=True)
            if private_account:
                total_cost = time.time() - follower_start_time
                print(f"    [跳过] 粉丝账号为私密账号: {follower_name} | follower总耗时={total_cost:.1f}s", flush=True)
                update_follower_queue_status(follower_name, "private_account", "not_eligible")
                processed.append(follower_name)
                continue

            # 获取其前 M 个关注
            print(f"    [阶段] 开始采集关注列表（目标 {cfg['max_following_per_user']}）", flush=True)
            collect_start = time.time()
            following_list = get_first_following(driver, cfg["max_following_per_user"])
            stage_collect_following = time.time() - collect_start
            print(
                f"    [阶段] 关注采集完成，关注数: {len(following_list)}（最多 {cfg['max_following_per_user']}） | 采集耗时={stage_collect_following:.1f}s",
                flush=True,
            )

            # 检查超时
            if time.time() - follower_start_time > 180:
                print(f"    [超时跳过] 获取粉丝关注列表超时: {follower_name}", flush=True)
                close_popup_if_open(driver)
                driver.back()
                time.sleep(2)
                update_follower_queue_status(follower_name, "not_found", "not_eligible")
                processed.append(follower_name)
                continue

            close_popup_if_open(driver)

            batch_records: List[EdgeRecord] = []
            for following_name in following_list:
                batch_records.append(
                    EdgeRecord(
                        root_username=root_username,
                        follower_username=follower_name,
                        following_username=following_name,
                    )
                )

            save_start = time.time()
            if batch_records:
                stats = save_edges_to_db(batch_records)
                update_follower_queue_status(follower_name, "available", "pending", 1)
                print(
                    "[重点汇总] "
                    f"follower=@{follower_name} (root=@{root_username}) | "
                    f"following候选={stats.get('candidates', 0)} | "
                    f"通过筛选={stats.get('passed_quality', 0)} | "
                    f"写入tiktok_roots={stats.get('inserted_roots', 0)} | "
                    f"写入mobile_pending_usernames={stats.get('inserted_pending', 0)} | "
                    f"筛掉(质量/不完整)={stats.get('skipped_quality', 0)} | "
                    f"筛掉(私密)={stats.get('skipped_private', 0)} | "
                    f"筛掉(profile_unavailable)={stats.get('skipped_profile_unavailable', 0)} | "
                    f"去重(已在tiktok_roots)={stats.get('skipped_existing_root', 0)}",
                    flush=True,
                )
                # 批次累加
                batch_followers += 1
                batch_following_candidates += int(stats.get("candidates", 0) or 0)
                batch_passed_quality += int(stats.get("passed_quality", 0) or 0)
                batch_inserted_roots += int(stats.get("inserted_roots", 0) or 0)
                batch_inserted_pending += int(stats.get("inserted_pending", 0) or 0)
                batch_skipped_quality += int(stats.get("skipped_quality", 0) or 0)
                batch_skipped_private += int(stats.get("skipped_private", 0) or 0)
                batch_skipped_profile_unavailable += int(stats.get("skipped_profile_unavailable", 0) or 0)
                batch_skipped_existing_root += int(stats.get("skipped_existing_root", 0) or 0)
            else:
                update_follower_queue_status(follower_name, "following_private", "not_eligible", 1)
            stage_save_db = time.time() - save_start

            total_cost = time.time() - follower_start_time
            print(
                f"    [汇总耗时] follower={follower_name} | 打开主页={stage_open_total:.1f}s(直开{stage_open_direct:.1f}s/搜索{stage_open_search:.1f}s) | 私密检测={stage_private_check:.1f}s | 采集关注={stage_collect_following:.1f}s | 入库+状态更新={stage_save_db:.1f}s | 总计={total_cost:.1f}s",
                flush=True,
            )
            processed.append(follower_name)

        remove_followers_from_queue(processed)

    except KeyboardInterrupt:
        print("\n[中断] 用户中断程序")
    finally:
        driver.quit()
        print("浏览器已关闭。")

    print(
        "=== follower 队列处理完成（批次汇总） ===\n"
        f"follower处理数={batch_followers} | "
        f"following候选总数={batch_following_candidates} | "
        f"通过筛选总数={batch_passed_quality}\n"
        f"写入tiktok_roots={batch_inserted_roots} | "
        f"写入mobile_pending_usernames={batch_inserted_pending} | "
        f"筛掉(质量/不完整)={batch_skipped_quality} | "
        f"筛掉(私密)={batch_skipped_private} | "
        f"筛掉(profile_unavailable)={batch_skipped_profile_unavailable} | "
        f"去重(已在tiktok_roots)={batch_skipped_existing_root}",
        flush=True,
    )


def run_process_queue_loop(cfg: dict, limit: int = 100, interval_seconds: int = 300, container_id: str | None = None):
    print("=== TikTok 关系采集脚本启动（队列定时处理） ===")
    print(f"结果将写入 SQLite 数据库: {cfg['db_path']}")
    init_db_for_follow_graph(cfg["db_path"])

    while True:
        run_process_queue_once(cfg, limit=limit, container_id=container_id)
        print(f"等待 {interval_seconds} 秒后继续处理队列...")
        time.sleep(interval_seconds)


def main():
    parser = argparse.ArgumentParser(description="TikTok 关系采集脚本")
    parser.add_argument(
        "--mode",
        choices=["collect_roots", "collect_roots_db", "collect_roots_db_loop", "process_queue", "process_queue_loop"],
        default="collect_roots",
        help="运行模式：collect_roots=Excel抓root粉丝入队，collect_roots_db=从tiktok_roots抓一轮，collect_roots_db_loop=从tiktok_roots定时抓取，process_queue=处理队列一次，process_queue_loop=定时处理队列",
    )
    parser.add_argument("--limit", type=int, default=100, help="每次处理的 follower 数量")
    parser.add_argument("--interval", type=int, default=300, help="定时处理间隔（秒）")
    parser.add_argument("--priority", type=str, default="high", choices=["high", "medium", "low"], help="DB根用户抓取优先级")
    parser.add_argument("--container-id", type=str, default="", help="Hubstudio环境ID（传入后不再需要手动登录）")
    parser.add_argument("--excel-input-path", type=str, default="", help="Excel 输入文件路径")
    parser.add_argument("--excel-output-path", type=str, default="", help="Excel 输出文件路径")
    parser.add_argument("--input-sheet-name", type=str, default="", help="Excel 工作表名称")
    parser.add_argument("--username-column-letter", type=str, default="", help="用户名列")
    parser.add_argument("--status-column-letter", type=str, default="", help="状态列")
    parser.add_argument("--date-column-letter", type=str, default="", help="日期列")
    parser.add_argument("--type-column-letter", type=str, default="", help="类型列")
    parser.add_argument("--max-followers-per-user", type=int, default=0, help="每个 root 采集的粉丝数量")
    parser.add_argument("--max-following-per-user", type=int, default=0, help="每个 follower 采集的关注数量")
    parser.add_argument("--db-path", type=str, default="", help="SQLite 数据库路径")
    parser.add_argument("--autosearch-path", type=str, default="", help="autoSearch 脚本路径")
    parser.add_argument("--chrome-user-data-dir", type=str, default="", help="Chrome 用户数据目录")
    parser.add_argument("--hubstudio-local-api", type=str, default="", help="Hubstudio 本地 API 地址")
    parser.add_argument("--hubstudio-start-path", type=str, default="", help="Hubstudio start 接口路径")
    parser.add_argument("--hubstudio-list-path", type=str, default="", help="Hubstudio list 接口路径")
    parser.add_argument("--internal-overview-api", type=str, default="", help="内部配置查询 API")
    parser.add_argument("--following-filter-min-video-count", type=int, default=0, help="following 过滤最小视频数")
    parser.add_argument("--following-filter-max-following-count", type=int, default=0, help="following 过滤最小关注阈值")
    args = parser.parse_args()
    container_id = (args.container_id or "").strip() or (os.getenv("HUBSTUDIO_CONTAINER_ID", "").strip() or None)
    cfg = _load_runtime_config(args)
    if container_id:
        cfg["hubstudio_container_id"] = container_id

    if args.mode == "collect_roots":
        run_collect_roots(cfg, container_id=container_id)
    elif args.mode == "collect_roots_db":
        run_collect_roots_from_db_once(cfg, limit=args.limit, container_id=container_id, priority=args.priority)
    elif args.mode == "collect_roots_db_loop":
        run_collect_roots_from_db_loop(cfg, limit=args.limit, interval_seconds=args.interval, container_id=container_id, priority=args.priority)
    elif args.mode == "process_queue":
        run_process_queue_once(cfg, limit=args.limit, container_id=container_id)
    else:
        run_process_queue_loop(cfg, limit=args.limit, interval_seconds=args.interval, container_id=container_id)


if __name__ == "__main__":
    main()


