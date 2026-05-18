"""
运行时加载项目根目录的 autoSearch_5.28_with_debug.py（不复制实现）。
"""
from __future__ import annotations

import importlib.util
import os
from dataclasses import asdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import requests

_PROJECT_ROOT = Path(__file__).resolve().parents[1]
_SERVER_DIR = Path(__file__).resolve().parent
_SCRIPT_NAME = "autoSearch_5.28_with_debug.py"
_COOKIE_NAMES = ("tiktok_cookies.json", "tiktok_cookies.txt")

_runtime_module: Any = None


def _autosearch_script_path() -> Path:
    if os.environ.get("AUTOSEARCH_WITH_DEBUG_DIR"):
        base = Path(os.environ["AUTOSEARCH_WITH_DEBUG_DIR"]).expanduser()
    else:
        base = _PROJECT_ROOT
    path = (base / _SCRIPT_NAME).resolve()
    if not path.is_file():
        raise FileNotFoundError(
            f"未找到 autoSearch 脚本: {path}\n"
            f"请将 {_SCRIPT_NAME} 放在项目根目录，或设置 AUTOSEARCH_WITH_DEBUG_DIR。"
        )
    return path


def _ensure_tiktok_cookies_env() -> None:
    """未显式配置时，自动在项目根 / server 等目录查找 Cookie 文件。"""
    if (os.environ.get("TIKTOK_COOKIES_FILE") or "").strip():
        return
    search_dirs = (
        _PROJECT_ROOT,
        _SERVER_DIR,
        _autosearch_script_path().parent,
        Path(r"D:\autoGroup\auto_upload"),
    )
    seen: set[str] = set()
    for base in search_dirs:
        key = str(base.resolve())
        if key in seen:
            continue
        seen.add(key)
        for name in _COOKIE_NAMES:
            candidate = (base / name).resolve()
            if candidate.is_file():
                os.environ["TIKTOK_COOKIES_FILE"] = str(candidate)
                return


def _load_autosearch_module():
    global _runtime_module
    if _runtime_module is not None:
        return _runtime_module

    _ensure_tiktok_cookies_env()
    script = _autosearch_script_path()
    spec = importlib.util.spec_from_file_location(
        "autosearch_with_debug_runtime",
        script,
    )
    if spec is None or spec.loader is None:
        raise ImportError(f"无法加载模块 spec: {script}")

    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    _runtime_module = mod
    return mod


def _get_universal_data(html_text: str) -> Optional[Dict[str, Any]]:
    return _load_autosearch_module()._get_universal_data(html_text)


def _webapp_user_detail_meta(
    universal: Optional[Dict[str, Any]],
) -> Optional[Tuple[Any, str]]:
    return _load_autosearch_module()._webapp_user_detail_meta(universal)


def extract_tiktok_universal_profile_snapshot(
    universal: Optional[Dict[str, Any]], page_username: str
):
    return _load_autosearch_module().extract_tiktok_universal_profile_snapshot(
        universal, page_username
    )


def apply_following_visibility_html_fallback(snap: Any, html_text: str) -> None:
    _load_autosearch_module().apply_following_visibility_html_fallback(snap, html_text)


def prepare_tiktok_profile_request_headers_and_cookies() -> Tuple[
    Dict[str, str], Optional[Dict[str, str]], List[str], Optional[str]
]:
    m = _load_autosearch_module()
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        ),
        "Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8",
    }
    use_ck = m._should_use_tiktok_cookie()
    cookie_path = m._resolve_tiktok_cookie_file_path() if use_ck else None
    if use_ck:
        cookies_dict, cookie_debug_lines = m._load_tiktok_cookies_from_file(cookie_path)
    else:
        cookies_dict = None
        cookie_debug_lines = [
            "  TikTok Cookie：已关闭（ENABLE_TIKTOK_COOKIE=False 或环境变量 TIKTOK_SKIP_COOKIE=1 / TIKTOK_NO_COOKIE=1），不读取、不发送 Cookie。"
        ]
    if cookies_dict:
        headers["Referer"] = "https://www.tiktok.com/"
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    return headers, cookies_dict, cookie_debug_lines, cookie_path


def fetch_tiktok_profile_metrics_dict(username: str) -> Optional[Dict[str, Any]]:
    """
    供 autoSearch_5.28 / tiktok_follow_scraper 使用的统一画像查询。
    返回字段与旧版 scrape_tiktok_profile_metrics 兼容。
    """
    m = _load_autosearch_module()
    if hasattr(m, "profile_metrics_dict_for_username"):
        return m.profile_metrics_dict_for_username(username)

    username = str(username or "").strip().lstrip("@")
    if not username:
        return None

    headers, cookies_dict, _, _ = prepare_tiktok_profile_request_headers_and_cookies()
    url = f"https://www.tiktok.com/@{username}"
    try:
        response = requests.get(url, headers=headers, cookies=cookies_dict, timeout=15)
        response.raise_for_status()
        html_text = response.text or ""
    except Exception as exc:
        print(f"抓取失败 [{username}]: {exc}")
        return None

    universal = _get_universal_data(html_text)
    snap = extract_tiktok_universal_profile_snapshot(universal, username)
    apply_following_visibility_html_fallback(snap, html_text)
    is_private = snap.profile_private_account is True or snap.profile_secret is True
    private_reason = ""
    if is_private:
        private_reason = (
            "universal_matched_user_flags:"
            f"privateAccount={snap.profile_private_account},"
            f"secret={snap.profile_secret}"
        )

    following_hidden = snap.following_list_hidden_guess
    following_hidden_reason = ""
    if following_hidden is True:
        following_hidden_reason = (
            f"universal_matched_user_flags:followingVisibility={snap.profile_following_visibility}"
        )

    follower_count = snap.follower_count or None
    following_count = snap.following_count or None
    heart_count = snap.heart_count or None
    video_count = snap.video_count or None
    if follower_count is None and following_count is None and video_count is None:
        return None

    return {
        "username": username,
        "follower_count": follower_count,
        "following_count": following_count,
        "heart_count": heart_count,
        "video_count": video_count,
        "recent_video_play_counts": [],
        "private_account_guess": is_private,
        "private_account_reason": private_reason,
        "profile_following_visibility": snap.profile_following_visibility,
        "following_list_hidden_guess": following_hidden,
        "following_list_hidden_reason": following_hidden_reason,
    }


def tiktok_universal_extract_to_json_dict(snap: Any) -> Dict[str, Any]:
    raw = asdict(snap)
    i18n = raw.get("i18n_country_prefix_samples") or {}
    if isinstance(i18n, dict):
        raw["i18n_country_prefix_samples"] = {
            k: list(v) if isinstance(v, tuple) else v for k, v in i18n.items()
        }
    fl = raw.get("force_login_special_regions")
    if isinstance(fl, tuple):
        raw["force_login_special_regions"] = list(fl)
    raw["to_publish_style_metrics"] = snap.to_publish_style_metrics()
    return raw
