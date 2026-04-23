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
import requests
import re
import sqlite3
import time
from datetime import datetime, timedelta
from pathlib import Path

from bs4 import BeautifulSoup

from zoneinfo import ZoneInfo  # Python 3.9+

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


# ---------- 抓取 ----------

def _safe_int(v):
    try:
        if isinstance(v, bool):
            return None
        if isinstance(v, (int, float)):
            return int(v)
        if isinstance(v, str):
            m = re.search(r"\d+", v.replace(",", ""))
            return int(m.group()) if m else None
    except Exception:
        return None
    return None


def _extract_universal_data(html_text: str):
    """从 __UNIVERSAL_DATA_FOR_REHYDRATION__ script 中提取 JSON。"""
    soup = BeautifulSoup(html_text, "html.parser")
    tag = soup.find("script", {"id": "__UNIVERSAL_DATA_FOR_REHYDRATION__"})
    if not tag:
        return None
    raw = tag.string or tag.get_text() or ""
    raw = raw.strip()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except Exception:
        return None


def _walk(obj):
    """递归遍历 JSON，产出所有 dict。"""
    if isinstance(obj, dict):
        yield obj
        for v in obj.values():
            yield from _walk(v)
    elif isinstance(obj, list):
        for item in obj:
            yield from _walk(item)


def scrape_tiktok_profile_metrics(username):
    """
    抓取 TikTok 主页关键指标：
    - follower_count
    - following_count
    - heart_count
    - video_count
    并附带尝试提取 recent_video_play_counts（可为空）。

    :return: dict | None
    """
    url = f"https://www.tiktok.com/@{username}"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8",
    }

    try:
        response = requests.get(url, headers=headers, timeout=15)
        response.raise_for_status()
        html_text = response.text

        universal = _extract_universal_data(html_text)

        follower_count = None
        following_count = None
        heart_count = None
        video_count = None
        recent_video_play_counts = []

        if universal is not None:
            for node in _walk(universal):
                if follower_count is None and "followerCount" in node:
                    follower_count = _safe_int(node.get("followerCount"))
                if following_count is None and "followingCount" in node:
                    following_count = _safe_int(node.get("followingCount"))
                if heart_count is None and "heartCount" in node:
                    heart_count = _safe_int(node.get("heartCount"))
                if video_count is None and "videoCount" in node:
                    video_count = _safe_int(node.get("videoCount"))

                stats_obj = node.get("stats") if isinstance(node.get("stats"), dict) else None
                for candidate in (node, stats_obj):
                    if not isinstance(candidate, dict):
                        continue
                    pc = None
                    for key in ("playCount", "play_count", "viewCount", "view_count"):
                        if key in candidate:
                            pc = _safe_int(candidate.get(key))
                            if pc is not None:
                                break
                    if pc is not None:
                        recent_video_play_counts.append(pc)

        # 兜底：若 JSON 没解析到，就用正则取关键字段
        if follower_count is None:
            m = re.search(r'"followerCount"\s*:\s*(\d+)', html_text)
            follower_count = int(m.group(1)) if m else None
        if following_count is None:
            m = re.search(r'"followingCount"\s*:\s*(\d+)', html_text)
            following_count = int(m.group(1)) if m else None
        if heart_count is None:
            m = re.search(r'"heartCount"\s*:\s*(\d+)', html_text)
            heart_count = int(m.group(1)) if m else None
        if video_count is None:
            m = re.search(r'"videoCount"\s*:\s*(\d+)', html_text)
            video_count = int(m.group(1)) if m else None

        # 去重并限制采样量
        if recent_video_play_counts:
            dedup = []
            seen = set()
            for x in recent_video_play_counts:
                if x not in seen:
                    seen.add(x)
                    dedup.append(x)
            recent_video_play_counts = dedup[:20]

        # 对质量筛选场景，不强依赖 follower_count，尽量返回可用字段
        if follower_count is None and following_count is None and video_count is None:
            return None

        private_account_guess = False
        private_account_reason = ""

        if universal is not None:
            matched_private = None
            matched_secret = None
            target = username.strip().lstrip("@").lower()
            for node in _walk(universal):
                uid = node.get("uniqueId") if isinstance(node, dict) else None
                if isinstance(uid, str) and uid.lower() == target:
                    p = node.get("privateAccount")
                    s = node.get("secret")
                    if isinstance(p, bool):
                        matched_private = p
                    if isinstance(s, bool):
                        matched_secret = s
                    break

            if matched_private is True or matched_secret is True:
                private_account_guess = True
                private_account_reason = (
                    f"universal_user_flags:privateAccount={matched_private},secret={matched_secret}"
                )

        return {
            "username": username,
            "follower_count": follower_count,
            "following_count": following_count,
            "heart_count": heart_count,
            "video_count": video_count,
            "recent_video_play_counts": recent_video_play_counts,
            "private_account_guess": private_account_guess,
            "private_account_reason": private_account_reason,
        }

    except Exception as e:
        print(f"抓取失败 [{username}]: {str(e)}")
        return None


def scrape_tiktok_followers(username):
    """兼容旧逻辑：仅返回粉丝数。"""
    profile = scrape_tiktok_profile_metrics(username)
    return profile.get("follower_count") if profile else None


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
        data = scrape_tiktok_profile_metrics(uname)
        print(json.dumps(data or {}, ensure_ascii=False))
    elif args.once:
        run_once()
    else:
        run_scheduler()
