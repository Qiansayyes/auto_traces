# -*- coding: utf-8 -*-
"""
TikTok 粉丝监控（极简版）：只为手机端下发队列准备数据。

数据库表结构（字段说明）：

1) tiktok_roots — 根用户名池
   - root_username              TEXT PRIMARY KEY
   - roots_type                 TEXT NOT NULL
   - discovered_at              TEXT NOT NULL
   - last_use_follower_count    INTEGER
   - usage_count                INTEGER NOT NULL DEFAULT 0

2) mobile_pending_usernames — 手机端待拉取用户名队列（由服务端 /api/pull_username 消费并删除）
   - id             INTEGER PRIMARY KEY AUTOINCREMENT
   - username       TEXT NOT NULL
   - created_at     TEXT NOT NULL
"""

import argparse
import json
import sqlite3
import time
from datetime import datetime, timedelta
from pathlib import Path

from zoneinfo import ZoneInfo  # Python 3.9+

_SERVER_DIR = Path(__file__).resolve().parent / "server"


def _import_profile_metrics():
    import sys

    server = str(_SERVER_DIR)
    if server not in sys.path:
        sys.path.insert(0, server)
    from tiktok_universal_snapshot import fetch_tiktok_profile_metrics_dict

    return fetch_tiktok_profile_metrics_dict

# 统一使用一个数据库文件（与脚本同目录）
DB_PATH = Path(__file__).resolve().parent / "tiktok_fans.db"
GROWTH_HOURS = 12
# 美国时间早上 6 点自动跑 run_once 的时区（美东）
US_SCHEDULE_TZ = ZoneInfo("America/New_York")
US_SCHEDULE_HOUR = 6


# ---------- 数据库初始化与表结构 ----------

def _get_db():
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    """确保 autoSearch 相关表存在（以 tiktok_roots 为准）。"""
    conn = _get_db()
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS tiktok_roots (
                root_username TEXT PRIMARY KEY,
                roots_type TEXT NOT NULL DEFAULT '',
                discovered_at TEXT NOT NULL,
                last_use_follower_count INTEGER,
                usage_count INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS mobile_pending_usernames (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            """
        )

        conn.commit()
    finally:
        conn.close()


# ---------- 抓取（统一走 autoSearch_5.28_with_debug） ----------


def scrape_tiktok_profile_metrics(username):
    """抓取 TikTok 主页关键指标（实现见 autoSearch_5.28_with_debug）。"""
    return _import_profile_metrics()(username)


def scrape_tiktok_followers(username):
    """兼容旧逻辑：仅返回粉丝数。"""
    profile = scrape_tiktok_profile_metrics(username)
    if not profile:
        return None
    follower_count = profile.get("follower_count")
    try:
        return int(follower_count) if follower_count is not None else None
    except (TypeError, ValueError):
        return None


# ---------- root 用户管理（tiktok_roots）----------

def get_all_root_usernames():
    """返回当前要监控的 root_username 列表。"""
    conn = _get_db()
    try:
        rows = conn.execute("SELECT root_username FROM tiktok_roots ORDER BY discovered_at").fetchall()
        return [r[0] for r in rows]
    finally:
        conn.close()


# ---------- 一次完整运行（全部写数据库）----------

def run_once(batch_size: int = 1000):
    """
    抓取当前粉丝数，更新 tiktok_roots.last_use_follower_count，
    并把符合增长条件（当前粉丝-上次基准>=50）的 root_username 写入 mobile_pending_usernames。
    每处理 batch_size 个 root 提交一次。
    """
    init_db()
    usernames = get_all_root_usernames()
    if not usernames:
        print("tiktok_roots 表为空，请先导入 root_username。")
        return

    now = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
    conn = _get_db()
    updated = 0

    try:
        for idx, username in enumerate(usernames, start=1):
            row = conn.execute(
                "SELECT last_use_follower_count FROM tiktok_roots WHERE root_username = ?",
                (username,),
            ).fetchone()
            last_use_follower_count = row[0] if row else None

            current = scrape_tiktok_followers(username)
            if current is None:
                continue

            if last_use_follower_count is None:
                conn.execute(
                    "UPDATE tiktok_roots SET last_use_follower_count = ? WHERE root_username = ?",
                    (current, username),
                )
                updated += 1
            else:
                growth = current - last_use_follower_count
                if growth >= 50:
                    conn.execute(
                        "UPDATE tiktok_roots SET last_use_follower_count = ?, usage_count = usage_count + 1 WHERE root_username = ?",
                        (current, username),
                    )
                    conn.execute(
                        "INSERT INTO mobile_pending_usernames (username, created_at) VALUES (?, ?)",
                        (username, now),
                    )
                    updated += 1

            if idx % batch_size == 0:
                conn.commit()
                print(f"已处理 {idx}/{len(usernames)} 个 root_username，阶段性提交完成")

        conn.commit()
        print(f"运行完成，共更新/入队 {updated} 个账号。")
    finally:
        conn.close()


# ---------- 美国时间早上 6 点自动跑一次 ----------

def _next_schedule_time():
    """返回下一次「美东 6:00」的 datetime（带时区）。"""
    now = datetime.now(US_SCHEDULE_TZ)
    today = now.replace(hour=US_SCHEDULE_HOUR, minute=0, second=0, microsecond=0)
    if now >= today:
        today += timedelta(days=1)
    return today


def run_scheduler():
    """
    常驻循环：每天美国时间早上 6 点执行一次 run_once()，
    在首次执行后 2 小时再补跑一次。
    """
    init_db()
    while True:
        target = _next_schedule_time()
        print(f"[定时] 下次运行时间: {target.strftime('%Y-%m-%d %H:%M')} ({US_SCHEDULE_TZ})")
        now = datetime.now(US_SCHEDULE_TZ)
        sleep_seconds = (target - now).total_seconds()
        if sleep_seconds > 0:
            time.sleep(sleep_seconds)

        print(f"[定时] 开始执行 run_once() @ {datetime.now(US_SCHEDULE_TZ)}")
        try:
            run_once(batch_size=1000)
        except Exception as e:
            print(f"[定时] run_once 异常: {e}")

        print("[定时] 2小时后执行补跑...")
        time.sleep(2 * 60 * 60)
        try:
            run_once(batch_size=1000)
        except Exception as e:
            print(f"[定时] 补跑 run_once 异常: {e}")

        # 避免重复执行：再等 1 分钟再进入下一轮计算
        time.sleep(60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="TikTok 粉丝监控：可每日美东 6:00 自动跑一次")
    parser.add_argument(
        "--once",
        action="store_true",
        help="只执行一次 run_once() 然后退出，不启动每日 6 点定时",
    )
    parser.add_argument(
        "--profile",
        default="",
        help="仅查询单个用户名画像并输出JSON（用于其它脚本质量筛选）",
    )
    parser.add_argument(
        "--tz",
        default=None,
        help="美国时区，如 America/Los_Angeles（默认 America/New_York）",
    )
    args = parser.parse_args()

    if args.tz:
        US_SCHEDULE_TZ = ZoneInfo(args.tz)

    init_db()
    if args.profile:
        uname = str(args.profile).strip().lstrip("@")
        data = _import_profile_metrics()(uname)
        print(json.dumps(data or {}, ensure_ascii=False))
    elif args.once:
        run_once()
    else:
        run_scheduler()
