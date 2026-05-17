"""
TikTok 主页 __UNIVERSAL_DATA_FOR_REHYDRATION__ 解析逻辑，与
D:\\autoGroup\\auto_upload\\autoSearch_5.28_with_debug.py 中
extract_tiktok_universal_profile_snapshot 及其依赖、以及 scrape_tiktok_followers
的 Cookie 开关（ENABLE_TIKTOK_COOKIE、TIKTOK_SKIP_COOKIE / TIKTOK_NO_COOKIE）保持一致。
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from bs4 import BeautifulSoup

# 与 autoSearch_5.28_with_debug.py 一致：为 True 且未设置 TIKTOK_SKIP_COOKIE 时才读取并发送 Cookie。
ENABLE_TIKTOK_COOKIE = True


def _should_use_tiktok_cookie() -> bool:
    """为 True 时才读取 Cookie 文件并随请求发送。"""
    if not ENABLE_TIKTOK_COOKIE:
        return False
    v = (os.environ.get("TIKTOK_SKIP_COOKIE") or os.environ.get("TIKTOK_NO_COOKIE") or "").strip().lower()
    if v in ("1", "true", "yes", "on"):
        return False
    return True


def _parse_cookie_header_string(value: str) -> Dict[str, str]:
    """把浏览器「Cookie」头里的 `a=b; c=d` 解析为 requests 可用的字典。"""
    out: Dict[str, str] = {}
    for part in value.split(";"):
        part = part.strip()
        if not part or part.startswith("#"):
            continue
        if "=" not in part:
            continue
        name, val = part.split("=", 1)
        name, val = name.strip(), val.strip()
        if name:
            out[name] = val
    return out


def _default_cookie_search_dir() -> str:
    return str(Path(__file__).resolve().parent)


def _resolve_tiktok_cookie_file_path() -> Optional[str]:
    """
    优先环境变量 TIKTOK_COOKIES_FILE（可为相对/绝对路径，由加载阶段检查是否存在）；
    未设置时在 server 目录查找 tiktok_cookies.json，再 tiktok_cookies.txt。
    """
    env = (os.environ.get("TIKTOK_COOKIES_FILE") or "").strip()
    if env:
        return env
    base = _default_cookie_search_dir()
    for name in ("tiktok_cookies.json", "tiktok_cookies.txt"):
        p = os.path.join(base, name)
        if os.path.isfile(p):
            return p
    return None


def _load_tiktok_cookies_from_file(path: Optional[str]) -> Tuple[Optional[Dict[str, str]], List[str]]:
    """
    读取 Cookie 配置。返回 (cookies 字典或 None, 供调试写入的说明行，不含 cookie 值)。
    """
    lines: List[str] = []
    if not path:
        lines.append("  未使用 Cookie 文件（server 目录无 tiktok_cookies.json / .txt，且未设置 TIKTOK_COOKIES_FILE）。")
        return None, lines
    if not os.path.isfile(path):
        ap = os.path.abspath(path)
        lines.append(f"  Cookie 文件不存在: {ap}")
        return None, lines
    ap = os.path.abspath(path)
    try:
        with open(path, encoding="utf-8") as f:
            raw = f.read().strip("\ufeff").strip()
    except OSError as e:
        lines.append(f"  无法读取文件: {ap} ({e})")
        return None, lines
    if not raw:
        lines.append(f"  文件为空: {ap}")
        return None, lines

    cookies: Dict[str, str] = {}
    low = path.lower()
    if low.endswith(".json"):
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as e:
            lines.append(f"  JSON 解析失败: {ap} ({e})")
            return None, lines
        if not isinstance(data, dict):
            lines.append(f"  JSON 根节点须为对象: {ap}")
            return None, lines
        hdr = data.get("_cookie_header") or data.get("cookie_header")
        if isinstance(hdr, str) and hdr.strip():
            cookies = _parse_cookie_header_string(hdr)
        else:
            for k, v in data.items():
                if not isinstance(k, str):
                    continue
                if k in ("_cookie_header", "cookie_header"):
                    continue
                if k.startswith("_"):
                    continue
                if isinstance(v, str) and v != "":
                    cookies[k] = v
    else:
        parts = []
        for line in raw.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts.append(line)
        if not parts:
            lines.append(f"  无有效内容（可写一行整段 Cookie，或多行 name=value）: {ap}")
            return None, lines
        if len(parts) == 1 and ";" in parts[0]:
            cookies = _parse_cookie_header_string(parts[0])
        else:
            for line in parts:
                if "=" not in line:
                    continue
                n, v = line.split("=", 1)
                n, v = n.strip(), v.strip()
                if n:
                    cookies[n] = v

    if not cookies:
        lines.append(f"  未能解析出任何 cookie 键: {ap}")
        return None, lines
    lines.append(f"  已加载: {ap}")
    lines.append(f"  cookie 键数量: {len(cookies)}（调试用，不输出具体值）")
    return cookies, lines


@dataclass
class TikTokUserInfo:
    """与文档一致：来自 __UNIVERSAL_DATA_FOR_REHYDRATION__ → user-detail → userInfo。"""

    uid: str
    sec_uid: str
    unique_id: str
    nickname: str
    follower_count: int
    following_count: int
    heart_count: int
    video_count: int


@dataclass
class TikTokUniversalExtract:
    """
    从 Universal JSON 中「按需」抽取的一页快照（不含整页 HTML）。
    各字段含义见 extract_tiktok_universal_profile_snapshot 函数注释。
    """

    page_username: str
    user_detail_status_code: Optional[Any] = None
    user_detail_status_msg: str = ""
    profile_uid: str = ""
    profile_sec_uid: str = ""
    profile_unique_id: str = ""
    profile_nickname: str = ""
    profile_private_account: Optional[bool] = None
    profile_secret: Optional[bool] = None
    follower_count: int = 0
    following_count: int = 0
    heart_count: int = 0
    video_count: int = 0
    session_is_private_account: Optional[bool] = None
    session_show_private_banner: Optional[bool] = None
    session_region: str = ""
    session_store_region: str = ""
    force_login_special_regions: Tuple[str, ...] = ()
    biz_hashed_ip: str = ""
    i18n_country_prefix_samples: Dict[str, Tuple[str, ...]] = field(default_factory=dict)

    def to_publish_style_metrics(self) -> Dict[str, str]:
        """与 tiktok_auto_publish_0315 写入 Excel 的键一致（字符串数字）。"""
        return {
            "粉丝量": str(self.follower_count),
            "关注数": str(self.following_count),
            "总赞": str(self.heart_count),
            "视频数": str(self.video_count),
            "点赞量": str(self.heart_count),
        }


def _get_universal_data(html_text: str) -> Optional[Dict[str, Any]]:
    """从页面内嵌 script#__UNIVERSAL_DATA_FOR_REHYDRATION__ 解析 JSON（文档中的 Universal Data）。"""
    if not html_text:
        return None
    try:
        soup = BeautifulSoup(html_text, "html.parser")
        tag = soup.find("script", {"id": "__UNIVERSAL_DATA_FOR_REHYDRATION__"})
        if not tag:
            return None
        raw = (tag.string or tag.get_text() or "").strip()
        if not raw:
            return None
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError, ValueError):
        return None


def _webapp_user_detail_meta(
    universal: Optional[Dict[str, Any]],
) -> Optional[Tuple[Any, str]]:
    """
    读取 SSR 中 webapp.user-detail 的状态。
    statusCode 非 0 时通常无 userInfo（例如 10221：服务端未下发该用户详情，需客户端再拉取或用户名无效）。
    """
    if not isinstance(universal, dict):
        return None
    scope = universal.get("__DEFAULT_SCOPE__")
    if not isinstance(scope, dict):
        return None
    detail = scope.get("webapp.user-detail")
    if not isinstance(detail, dict) or "statusCode" not in detail:
        return None
    return (detail.get("statusCode"), str(detail.get("statusMsg") or ""))


def _safe_int_metric(value: Any) -> int:
    if value is None:
        return 0
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    text = str(value).strip().upper().replace(",", "")
    try:
        if text.endswith("K"):
            return int(float(text[:-1]) * 1000)
        if text.endswith("M"):
            return int(float(text[:-1]) * 1000000)
        if text.endswith("B"):
            return int(float(text[:-1]) * 1000000000)
        return int(float(re.sub(r"[^\d.]", "", text) or 0))
    except (TypeError, ValueError):
        return 0


def _find_user_info_from_scope(scope: Any) -> Optional[Dict[str, Any]]:
    """
    解析路径：__DEFAULT_SCOPE__ → webapp.user-detail → userInfo。
    若键名微调，在 __DEFAULT_SCOPE__ 下回退查找含 user-detail 且含 userInfo.user 的节点。
    """
    if not isinstance(scope, dict):
        return None
    detail = scope.get("webapp.user-detail")
    if isinstance(detail, dict):
        ui = detail.get("userInfo")
        if isinstance(ui, dict) and isinstance(ui.get("user"), dict):
            return ui
    for key, val in scope.items():
        if not isinstance(val, dict) or "userInfo" not in val:
            continue
        if "user-detail" not in str(key):
            continue
        ui = val.get("userInfo")
        if isinstance(ui, dict) and isinstance(ui.get("user"), dict):
            return ui
    return None


def _parse_user_info_to_model(user_info: Dict[str, Any]) -> Optional[TikTokUserInfo]:
    """userInfo → user + stats，构建 TikTokUserInfo（与文档字段映射一致）。"""
    user = user_info.get("user")
    if not isinstance(user, dict):
        return None
    stats = user_info.get("stats")
    if not isinstance(stats, dict):
        stats = {}
    uid = user.get("id")
    if uid is None:
        return None
    hc = _safe_int_metric(stats.get("heartCount"))
    if hc == 0:
        hc = _safe_int_metric(stats.get("heart"))
    return TikTokUserInfo(
        uid=str(uid).strip(),
        sec_uid=str(user.get("secUid") or "").strip(),
        unique_id=str(user.get("uniqueId") or "").strip(),
        nickname=str(user.get("nickname") or "").strip(),
        follower_count=_safe_int_metric(stats.get("followerCount")),
        following_count=_safe_int_metric(stats.get("followingCount")),
        heart_count=hc,
        video_count=_safe_int_metric(stats.get("videoCount")),
    )


def _walk_json_dict(obj: Any):
    if isinstance(obj, dict):
        yield obj
        for v in obj.values():
            yield from _walk_json_dict(v)
    elif isinstance(obj, list):
        for item in obj:
            yield from _walk_json_dict(item)


_RE_I18N_COUNTRY_KEY = re.compile(r"^[A-Z]{2}_[A-Za-z0-9_]+$")


def _norm_page_username(value: str) -> str:
    return str(value or "").strip().lstrip("@").lower()


def _optional_bool(v: Any) -> Optional[bool]:
    if v is True or v is False:
        return v
    if isinstance(v, str):
        s = v.strip().lower()
        if s in ("true", "1", "yes"):
            return True
        if s in ("false", "0", "no"):
            return False
    return None


def _get_matched_user_info_from_universal(
    universal: Dict[str, Any], page_username: str
) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
    """
    在 Universal 中定位与 page_username（user.uniqueId）一致的 userInfo。
    第二项为来源的 webapp.user-detail 区块（若来自树遍历则为 None）。
    """
    exp = _norm_page_username(page_username)
    if not isinstance(universal, dict) or not exp:
        return None, None
    scope = universal.get("__DEFAULT_SCOPE__")
    if not isinstance(scope, dict):
        return None, None

    def try_detail(detail: Any) -> Optional[Tuple[Dict[str, Any], Dict[str, Any]]]:
        if not isinstance(detail, dict):
            return None
        code = detail.get("statusCode")
        if code not in (0, "0", None) and str(code) != "0":
            return None
        ui = detail.get("userInfo")
        if not isinstance(ui, dict) or not isinstance(ui.get("user"), dict):
            return None
        u = ui["user"]
        uid = _norm_page_username(str(u.get("uniqueId") or ""))
        if not uid or uid != exp:
            return None
        return ui, detail

    d = scope.get("webapp.user-detail")
    if isinstance(d, dict):
        got = try_detail(d)
        if got:
            return got[0], got[1]
    for key, val in scope.items():
        if isinstance(val, dict) and "user-detail" in str(key).lower():
            got = try_detail(val)
            if got:
                return got[0], got[1]
    for node in _walk_json_dict(universal):
        if not isinstance(node, dict):
            continue
        ui = node.get("userInfo")
        if not isinstance(ui, dict) or not isinstance(ui.get("user"), dict):
            continue
        u = ui["user"]
        if _norm_page_username(str(u.get("uniqueId") or "")) == exp:
            return ui, None
    return None, None


def _scan_i18n_country_prefixed_keys(
    universal: Dict[str, Any],
    *,
    samples_per_prefix: int = 4,
    max_prefixes_reported: int = 35,
    max_dict_visits: int = 12000,
) -> Dict[str, Tuple[str, ...]]:
    """
    仅在 webapp.i18n-translation 下收集键名形如 XX_xxx（XX 为两位大写拉丁字母）。
    多为该国合规/文案资源 ID（如 BR_…、MX_…）；返回各前缀若干示例键，不写全量。
    """
    scope = universal.get("__DEFAULT_SCOPE__")
    if not isinstance(scope, dict):
        return {}
    root = scope.get("webapp.i18n-translation")
    if root is None:
        return {}

    prefix_counts: Dict[str, int] = {}
    prefix_samples: Dict[str, List[str]] = {}
    dict_visits = 0

    def visit(obj: Any) -> None:
        nonlocal dict_visits
        if dict_visits >= max_dict_visits:
            return
        if isinstance(obj, dict):
            dict_visits += 1
            for k, v in obj.items():
                if dict_visits >= max_dict_visits:
                    break
                if isinstance(k, str) and _RE_I18N_COUNTRY_KEY.match(k):
                    pref = k[:2]
                    prefix_counts[pref] = prefix_counts.get(pref, 0) + 1
                    lst = prefix_samples.setdefault(pref, [])
                    if len(lst) < samples_per_prefix:
                        lst.append(k)
                visit(v)
        elif isinstance(obj, list):
            for it in obj[:600]:
                visit(it)
                if dict_visits >= max_dict_visits:
                    break

    visit(root)
    ordered = sorted(prefix_counts.keys(), key=lambda p: prefix_counts[p], reverse=True)[
        :max_prefixes_reported
    ]
    return {p: tuple(prefix_samples.get(p, ())) for p in ordered}


def extract_tiktok_universal_profile_snapshot(
    universal: Optional[Dict[str, Any]], page_username: str
) -> TikTokUniversalExtract:
    """
    从已解析的 Universal 根对象一次性取出本脚本关心的字段（精、少冗余）。

    取值说明（路径均在 __DEFAULT_SCOPE__ 下）：
    - 「profile_* / follower_*」：来自 webapp.user-detail（等）且 **user.uniqueId 与 page_username 一致**
      的 userInfo；用于当前 @handle 主页统计，避免误用 JSON 树里其它账号数据。
    - profile_private_account：user.privateAccount，主页作者是否私密账号。
    - profile_secret：user.secret，另一私密相关标记。
    - session_*：来自 webapp.app-context.user，**当前 Cookie 登录会话**，可能与主页不是同一人；
      isPrivateAccount / showPrivateBanner 常在此对象上。
    - force_login_special_regions：webapp.biz-context.forceLoginSpecialRegions，策略涉及的国家码列表。
    - biz_hashed_ip：webapp.biz-context.hashedIP，**非明文 IP**。
    - i18n_country_prefix_samples：仅在 webapp.i18n-translation 下扫描键名 **^[A-Z]{2}_**，
      按两位码分组的示例键（如 BR_、MX_），表示该国相关文案键是否出现在本页配置中。
    """
    un = universal if isinstance(universal, dict) else {}
    snap = TikTokUniversalExtract(page_username=str(page_username or "").strip().lstrip("@"))
    scope = un.get("__DEFAULT_SCOPE__")
    if not isinstance(scope, dict):
        return snap

    ui, detail = _get_matched_user_info_from_universal(un, snap.page_username)
    if isinstance(detail, dict):
        snap.user_detail_status_code = detail.get("statusCode")
        snap.user_detail_status_msg = str(detail.get("statusMsg") or "")
    if isinstance(ui, dict):
        u = ui.get("user")
        st = ui.get("stats") if isinstance(ui.get("stats"), dict) else {}
        if isinstance(u, dict):
            snap.profile_uid = str(u.get("id") or "").strip()
            snap.profile_sec_uid = str(u.get("secUid") or "").strip()
            snap.profile_unique_id = str(u.get("uniqueId") or "").strip()
            snap.profile_nickname = str(u.get("nickname") or "").strip()
            snap.profile_private_account = _optional_bool(u.get("privateAccount"))
            snap.profile_secret = _optional_bool(u.get("secret"))
        snap.follower_count = _safe_int_metric(st.get("followerCount"))
        snap.following_count = _safe_int_metric(st.get("followingCount"))
        snap.heart_count = _safe_int_metric(st.get("heartCount"))
        if snap.heart_count == 0:
            snap.heart_count = _safe_int_metric(st.get("heart"))
        snap.video_count = _safe_int_metric(st.get("videoCount"))

    ctx = scope.get("webapp.app-context")
    if isinstance(ctx, dict):
        su = ctx.get("user")
        if isinstance(su, dict):
            snap.session_is_private_account = _optional_bool(su.get("isPrivateAccount"))
            snap.session_show_private_banner = _optional_bool(su.get("showPrivateBanner"))
            snap.session_region = str(su.get("region") or "").strip()
            snap.session_store_region = str(su.get("storeRegion") or "").strip()

    biz = scope.get("webapp.biz-context")
    if isinstance(biz, dict):
        fl = biz.get("forceLoginSpecialRegions")
        if isinstance(fl, list):
            snap.force_login_special_regions = tuple(
                str(x).strip() for x in fl if str(x).strip()
            )
        hip = biz.get("hashedIP")
        if hip is not None and str(hip).strip():
            snap.biz_hashed_ip = str(hip).strip()

    snap.i18n_country_prefix_samples = _scan_i18n_country_prefixed_keys(un)
    return snap


def tiktok_universal_extract_to_json_dict(snap: TikTokUniversalExtract) -> Dict[str, Any]:
    """将 TikTokUniversalExtract 转为 JSON 友好结构（与 dataclass 字段一一对应）。"""
    return {
        "page_username": snap.page_username,
        "user_detail_status_code": snap.user_detail_status_code,
        "user_detail_status_msg": snap.user_detail_status_msg,
        "profile_uid": snap.profile_uid,
        "profile_sec_uid": snap.profile_sec_uid,
        "profile_unique_id": snap.profile_unique_id,
        "profile_nickname": snap.profile_nickname,
        "profile_private_account": snap.profile_private_account,
        "profile_secret": snap.profile_secret,
        "follower_count": snap.follower_count,
        "following_count": snap.following_count,
        "heart_count": snap.heart_count,
        "video_count": snap.video_count,
        "session_is_private_account": snap.session_is_private_account,
        "session_show_private_banner": snap.session_show_private_banner,
        "session_region": snap.session_region,
        "session_store_region": snap.session_store_region,
        "force_login_special_regions": list(snap.force_login_special_regions),
        "biz_hashed_ip": snap.biz_hashed_ip,
        "i18n_country_prefix_samples": {k: list(v) for k, v in snap.i18n_country_prefix_samples.items()},
        "to_publish_style_metrics": snap.to_publish_style_metrics(),
    }


def prepare_tiktok_profile_request_headers_and_cookies() -> Tuple[
    Dict[str, str], Optional[Dict[str, str]], List[str], Optional[str]
]:
    """
    与 autoSearch scrape_tiktok_followers 一致：基础 UA + Accept-Language；
    仅当 _should_use_tiktok_cookie() 为真且成功加载 Cookie 时附加 Referer/Accept 与 cookies。
    """
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        ),
        "Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8",
    }
    use_ck = _should_use_tiktok_cookie()
    cookie_path = _resolve_tiktok_cookie_file_path() if use_ck else None
    if use_ck:
        cookies_dict, cookie_debug_lines = _load_tiktok_cookies_from_file(cookie_path)
    else:
        cookies_dict = None
        cookie_debug_lines = [
            "  TikTok Cookie：已关闭（ENABLE_TIKTOK_COOKIE=False 或环境变量 TIKTOK_SKIP_COOKIE=1 / TIKTOK_NO_COOKIE=1），不读取、不发送 Cookie。"
        ]
    if cookies_dict:
        headers["Referer"] = "https://www.tiktok.com/"
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    return headers, cookies_dict, cookie_debug_lines, cookie_path
