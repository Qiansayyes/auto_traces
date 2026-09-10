"""
TikTok 主页统计抓取（服务端自包含，不依赖 Windows 上的 auto_upload 路径）。

API 与 tiktok_auto_publish_fast.py / auto_fans.py 对齐，便于 server/main.py 直接复用。
"""
from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import requests

_COOKIE_NAMES = ("tiktok_cookies.json", "tiktok_cookies.txt")


def _app_base_dir() -> str:
    return str(Path(__file__).resolve().parent)


def _project_root() -> Path:
    return Path(__file__).resolve().parents[1]


def _parse_tiktok_cookie_header_string(value: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for part in (value or "").split(";"):
        part = part.strip()
        if not part or "=" not in part:
            continue
        name, val = part.split("=", 1)
        name, val = name.strip(), val.strip()
        if name:
            out[name] = val
    return out


def _safe_int_metric(value: Any) -> int:
    if value is None:
        return 0
    text = str(value).strip().upper().replace(",", "")
    try:
        if text.endswith("K"):
            return int(float(text[:-1]) * 1000)
        if text.endswith("M"):
            return int(float(text[:-1]) * 1000000)
        if text.endswith("B"):
            return int(float(text[:-1]) * 1000000000)
        return int(float(re.sub(r"[^\d.]", "", text) or 0))
    except Exception:
        return 0


def _cookie_search_dirs() -> List[Path]:
    dirs: List[Path] = []
    env_dir = (os.environ.get("AUTO_UPLOAD_DIR") or "").strip()
    if env_dir:
        dirs.append(Path(env_dir))
    dirs.extend(
        [
            _project_root(),
            Path(_app_base_dir()),
            _project_root().parent / "auto_upload",
            Path("/opt/auto_traces/auto_upload"),
            Path(r"D:\autoGroup\auto_upload"),
        ]
    )
    seen: set[str] = set()
    out: List[Path] = []
    for base in dirs:
        try:
            key = str(base.resolve())
        except Exception:
            continue
        if key in seen:
            continue
        seen.add(key)
        out.append(base)
    return out


def _resolve_tiktok_cookie_file_path() -> Optional[str]:
    env = (os.environ.get("TIKTOK_COOKIES_FILE") or "").strip()
    if env:
        return env
    for base in _cookie_search_dirs():
        for name in _COOKIE_NAMES:
            path = base / name
            if path.is_file():
                return str(path.resolve())
    return None


def load_tiktok_cookies_for_scrape() -> Tuple[Optional[Dict[str, str]], str]:
    path = _resolve_tiktok_cookie_file_path()
    if not path:
        return None, (
            "TikTok Cookie：未配置（项目根/server/auto_upload 无 tiktok_cookies.json/.txt，"
            "且未设置 TIKTOK_COOKIES_FILE），请求可能遇 WAF。"
        )
    if not os.path.isfile(path):
        return None, f"TikTok Cookie：文件不存在 {os.path.abspath(path)}"
    abs_path = os.path.abspath(path)
    try:
        with open(path, encoding="utf-8") as f:
            raw = f.read().strip("\ufeff").strip()
    except OSError as exc:
        return None, f"TikTok Cookie：无法读取 {abs_path} ({exc})"
    if not raw:
        return None, f"TikTok Cookie：文件为空 {abs_path}"

    cookies: Dict[str, str] = {}
    if path.lower().endswith(".json"):
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            return None, f"TikTok Cookie：JSON 无效 {abs_path} ({exc})"
        if not isinstance(data, dict):
            return None, f"TikTok Cookie：JSON 根须为对象 {abs_path}"
        header = data.get("_cookie_header") or data.get("cookie_header")
        if isinstance(header, str) and header.strip():
            cookies = _parse_tiktok_cookie_header_string(header)
        else:
            for key, value in data.items():
                if not isinstance(key, str) or key in ("_cookie_header", "cookie_header") or key.startswith("_"):
                    continue
                if isinstance(value, str) and value != "":
                    cookies[key] = value
    else:
        lines = [
            line.strip()
            for line in raw.splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        if not lines:
            return None, f"TikTok Cookie：无有效行 {abs_path}"
        if len(lines) == 1 and ";" in lines[0]:
            cookies = _parse_tiktok_cookie_header_string(lines[0])
        else:
            for line in lines:
                if "=" not in line:
                    continue
                name, val = line.split("=", 1)
                name, val = name.strip(), val.strip()
                if name:
                    cookies[name] = val
    if not cookies:
        return None, f"TikTok Cookie：未能解析出任何键 {abs_path}"
    return cookies, f"TikTok Cookie：已加载 {abs_path}（{len(cookies)} 个键）"


def _extract_universal_data_from_html(html_text: str) -> Optional[dict]:
    if not html_text:
        return None
    try:
        # 优先正则，避免强依赖 bs4
        m = re.search(
            r'<script[^>]+id=["\']__UNIVERSAL_DATA_FOR_REHYDRATION__["\'][^>]*>(.*?)</script>',
            html_text,
            re.I | re.S,
        )
        if m:
            raw = (m.group(1) or "").strip()
            if raw:
                return json.loads(raw)
    except Exception:
        pass
    try:
        from bs4 import BeautifulSoup

        soup = BeautifulSoup(html_text, "html.parser")
        tag = soup.find("script", {"id": "__UNIVERSAL_DATA_FOR_REHYDRATION__"})
        if not tag:
            return None
        raw = (tag.string or tag.get_text() or "").strip()
        if not raw:
            return None
        return json.loads(raw)
    except Exception:
        return None


def _walk_json(obj: Any):
    if isinstance(obj, dict):
        yield obj
        for v in obj.values():
            yield from _walk_json(v)
    elif isinstance(obj, list):
        for item in obj:
            yield from _walk_json(item)


def _user_info_stats_from_universal(universal: Optional[dict]) -> Tuple[Optional[dict], Optional[dict]]:
    """返回 (user_dict_or_None, stats_dict_or_None)。"""
    if not isinstance(universal, dict):
        return None, None
    scope = universal.get("__DEFAULT_SCOPE__")
    candidates: List[dict] = []
    if isinstance(scope, dict):
        detail = scope.get("webapp.user-detail")
        if isinstance(detail, dict):
            candidates.append(detail)
        for _k, val in scope.items():
            if isinstance(val, dict) and "user-detail" in str(_k).lower():
                candidates.append(val)
    for detail in candidates:
        code = detail.get("statusCode")
        if code not in (0, "0") and str(code) != "0":
            continue
        ui = detail.get("userInfo")
        if isinstance(ui, dict):
            user = ui.get("user") if isinstance(ui.get("user"), dict) else None
            st = ui.get("stats") if isinstance(ui.get("stats"), dict) else None
            if user or st:
                return user, st
    for node in _walk_json(universal):
        if not isinstance(node, dict):
            continue
        ui = node.get("userInfo")
        if not isinstance(ui, dict):
            continue
        user = ui.get("user") if isinstance(ui.get("user"), dict) else None
        st = ui.get("stats") if isinstance(ui.get("stats"), dict) else None
        if isinstance(user, dict) or isinstance(st, dict):
            return user, st
    return None, None


def scrape_tiktok_profile_by_username(
    username: str, cookies: Optional[Dict[str, str]] = None
) -> Optional[Dict[str, Any]]:
    username = (username or "").strip().lstrip("@")
    if not username:
        return None

    url = f"https://www.tiktok.com/@{username}"
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        ),
        "Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8",
    }
    if cookies:
        headers["Referer"] = "https://www.tiktok.com/"
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

    try:
        resp = requests.get(url, headers=headers, cookies=cookies, timeout=20)
        resp.raise_for_status()
        html = resp.text or ""
        universal = _extract_universal_data_from_html(html)
        user, stats = _user_info_stats_from_universal(universal)

        follower = _safe_int_metric((stats or {}).get("followerCount"))
        following = _safe_int_metric((stats or {}).get("followingCount"))
        heart = max(
            _safe_int_metric((stats or {}).get("heartCount")),
            _safe_int_metric((stats or {}).get("heart")),
        )
        video = _safe_int_metric((stats or {}).get("videoCount"))
        unique_id = ""
        nickname = ""
        private_account = None
        secret = None
        following_visibility = None
        if isinstance(user, dict):
            unique_id = str(user.get("uniqueId") or user.get("unique_id") or "").strip()
            nickname = str(user.get("nickname") or "").strip()
            if "privateAccount" in user:
                private_account = bool(user.get("privateAccount"))
            if "secret" in user:
                secret = bool(user.get("secret"))
            if "followingVisibility" in user:
                following_visibility = user.get("followingVisibility")

        if not (unique_id or follower or heart or video):
            def _extract_num(pattern: str) -> int:
                match = re.search(pattern, html)
                return _safe_int_metric(match.group(1)) if match else 0

            follower = _extract_num(r'"followerCount"\s*:\s*"?(\d+)"?')
            following = _extract_num(r'"followingCount"\s*:\s*"?(\d+)"?')
            heart = max(
                _extract_num(r'"heartCount"\s*:\s*"?(\d+)"?'),
                _extract_num(r'"heart"\s*:\s*"?(\d+)"?'),
            )
            video = _extract_num(r'"videoCount"\s*:\s*"?(\d+)"?')
            m_uid = re.search(r'"uniqueId"\s*:\s*"([^"]+)"', html)
            if m_uid:
                unique_id = m_uid.group(1)

        if not (unique_id or follower or heart or video):
            return None

        return {
            "profile_unique_id": unique_id or username,
            "profile_nickname": nickname,
            "follower_count": follower,
            "following_count": following,
            "heart_count": heart,
            "video_count": video,
            "profile_private_account": private_account,
            "profile_secret": secret,
            "profile_following_visibility": following_visibility,
            "following_list_hidden_guess": None,
            "粉丝量": str(follower),
            "关注数": str(following),
            "总赞": str(heart),
            "视频数": str(video),
            "点赞量": str(heart),
        }
    except Exception:
        return None
