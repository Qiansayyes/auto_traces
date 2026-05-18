"""
从 Excel A 列读 TikTok 用户名，请求主页后解析粉丝数并写回 C 列；
粉丝数优先从内嵌 __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON 解析（路径：
__DEFAULT_SCOPE__ → webapp.user-detail → userInfo → user / stats），与文档
TikTokUserInfo 字段一致；无内嵌数据时再回退正则。

单函数 extract_tiktok_universal_profile_snapshot(universal, page_username) 汇总：
与 tiktok_auto_publish 一致的指标、主页 privateAccount、会话 showPrivateBanner、
forceLoginSpecialRegions、hashedIP、i18n 两位国码前缀文案键示例等（见函数内注释）。

可选将每次 HTTP 响应摘要写入 tiktok_fetch_debug.txt（见下方开关）。

单用户调试（不读 Excel）：
  在项目目录打开终端，执行：
    python autoSearch_5.28_with_debug.py 你的用户名
调试输出（ENABLE_DEBUG_TXT=True 时）：
  - tiktok_fetch_debug.txt：摘要 + 原始 HTML 片段 + 完整 soup 字符串
  - tiktok_soup_str_ONLY.txt：仅 script#__UNIVERSAL_DATA_FOR_REHYDRATION__ 内 JSON 原文（无则提示未找到）

手动 Cookie（绕过部分 WAF / 拿到 __UNIVERSAL_DATA_FOR_REHYDRATION__）：
  1. 用浏览器打开 tiktok.com 并登录或完成验证；F12 → Network → 任一带 Cookie 的请求 →
     请求标头里复制整段「Cookie:」后面的值。
  2. 任选一种文件（与本脚本同目录，或设置环境变量 TIKTOK_COOKIES_FILE 为绝对路径）：
     - tiktok_cookies.txt：第一行粘贴整段「a=b; c=d」即可；# 开头为注释。
     - tiktok_cookies.json：要么写 { "_cookie_header": "整段..." }，要么写多个键值
       { "sessionid_ss": "...", "ttwid": "..." }（键名以下划线 _ 开头的除 _cookie_header 外会忽略）。

无 Cookie 对比测试（三选一）：
  - 将上方 ENABLE_TIKTOK_COOKIE 改为 False；或
  - 运行前设置环境变量 TIKTOK_SKIP_COOKIE=1（Windows: set TIKTOK_SKIP_COOKIE=1）；或
  - 单用户：python autoSearch_5.28_with_debug.py --no-cookie 你的用户名
"""
import os
import sys
import json
import openpyxl
import requests
import re
from dataclasses import asdict, dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

from bs4 import BeautifulSoup
from openpyxl import load_workbook
from openpyxl.styles import PatternFill
from openpyxl.utils import get_column_letter

# 运行结束时是否生成调试 txt；改为 False 则只更新 Excel、不写 txt
ENABLE_DEBUG_TXT = True

# 是否加载并发送 tiktok_cookies.json（及 TIKTOK_COOKIES_FILE）。改为 False 则纯 requests，便于与带 Cookie 对比 WAF。
# 也可不改本行，在运行前设置环境变量 TIKTOK_SKIP_COOKIE=1，或单用户加参数 --no-cookie。
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


def _resolve_tiktok_cookie_file_path() -> Optional[str]:
    """
    优先环境变量 TIKTOK_COOKIES_FILE（可为相对/绝对路径，由加载阶段检查是否存在）；
    未设置时在本脚本同目录查找 tiktok_cookies.json，再 tiktok_cookies.txt。
    """
    env = (os.environ.get("TIKTOK_COOKIES_FILE") or "").strip()
    if env:
        return env
    base = os.path.dirname(os.path.abspath(__file__))
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
        lines.append("  未使用 Cookie 文件（同目录无 tiktok_cookies.json / .txt，且未设置 TIKTOK_COOKIES_FILE）。")
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
    profile_following_visibility: Optional[int] = None
    following_list_hidden_guess: Optional[bool] = None
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


def filter_tiktok_universal_extract(
    extract: Optional[TikTokUniversalExtract],
    fields: Optional[List[str]] = None,
    *,
    include_none: bool = False,
) -> Dict[str, Any]:
    """
    从 TikTokUniversalExtract 中筛选指定字段，返回更小的字典。
    """
    if extract is None:
        return {}

    alias_map = {
        "粉丝量": "follower_count",
        "关注数": "following_count",
        "总赞": "heart_count",
        "点赞量": "heart_count",
        "视频数": "video_count",
    }

    source = asdict(extract)
    if not fields:
        fields = list(source.keys())

    out: Dict[str, Any] = {}
    for field_name in fields:
        src_key = alias_map.get(field_name, field_name)
        if src_key not in source:
            continue
        value = source.get(src_key)
        if value is None and not include_none:
            continue
        out[field_name] = value
    return out


def extract_tiktok_fields_from_text(
    raw_text: str,
    fields: List[str],
    *,
    include_none: bool = False,
) -> Dict[str, Any]:
    """
    直接从原始文本里按字段名检索，尽量不依赖固定对象结构。
    """
    if not raw_text or not fields:
        return {}

    alias_map = {
        "粉丝量": "followerCount",
        "关注数": "followingCount",
        "总赞": "heartCount",
        "点赞量": "heartCount",
        "视频数": "videoCount",
        "主页是否隐私": "privateAccount",
        "会话是否隐私": "isPrivateAccount",
        "关注列表可见性": "followingVisibility",
        "profile_following_visibility": "followingVisibility",
    }

    result: Dict[str, Any] = {}
    for field_name in fields:
        key = alias_map.get(field_name, field_name)
        pat = rf'"{re.escape(key)}"\s*:\s*(true|false|null|".*?"|\d+)'
        m = re.search(pat, raw_text, re.I | re.S)
        if not m:
            if include_none:
                result[field_name] = None
            continue
        value: Any = m.group(1)
        low = value.lower()
        if low == "true":
            value = True
        elif low == "false":
            value = False
        elif low == "null":
            value = None
        elif value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        result[field_name] = value
    return result


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


def _extract_universal_script_inner_text(html_text: str) -> str:
    """仅取出 #__UNIVERSAL_DATA_FOR_REHYDRATION__ 标签内原始文本（多为单行 JSON），不写整页 HTML。"""
    if not html_text:
        return ""
    try:
        soup = BeautifulSoup(html_text, "html.parser")
        tag = soup.find("script", {"id": "__UNIVERSAL_DATA_FOR_REHYDRATION__"})
        if not tag:
            return ""
        return (tag.string or tag.get_text() or "").strip()
    except Exception:
        return ""


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


def _follower_not_found_message(
    user_detail_meta: Optional[Tuple[Any, str]], base: str = "数据未找到"
) -> str:
    """在无法解析粉丝时附带 webapp.user-detail 状态码，便于区分 WAF 与 SSR 无数据。"""
    if user_detail_meta is None:
        return base
    code, _ = user_detail_meta
    if code in (0, "0", None) or str(code) == "0":
        return base
    return f"{base}(user-detail状态码={code})"


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


def _safe_int_optional(value: Any) -> Optional[int]:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def following_list_hidden_guess_from_visibility(visibility: Optional[int]) -> Optional[bool]:
    """user.followingVisibility：1=公开；非 1（常见 2）= 关注列表对他人隐藏。"""
    if visibility is None:
        return None
    return visibility != 1


def _following_visibility_near_unique_id(html_text: str, unique_id: str) -> Optional[int]:
    """在 uniqueId 附近窗口内查找 followingVisibility，避免误匹配页面其它用户。"""
    uid = str(unique_id or "").strip()
    if not uid or not html_text:
        return None
    pat = rf'"uniqueId"\s*:\s*"{re.escape(uid)}"'
    m = re.search(pat, html_text, re.I)
    if not m:
        return None
    chunk = html_text[m.start() : m.start() + 4000]
    vm = re.search(r'"followingVisibility"\s*:\s*(\d+)', chunk, re.I)
    return int(vm.group(1)) if vm else None


def apply_following_visibility_html_fallback(
    snap: TikTokUniversalExtract,
    html_text: str,
) -> None:
    """Universal user 块缺 followingVisibility 时，从主页 HTML 回退解析。"""
    if snap.profile_following_visibility is not None or not html_text:
        return
    uid = (snap.profile_unique_id or snap.page_username or "").strip()
    vis = _following_visibility_near_unique_id(html_text, uid) if uid else None
    if vis is None:
        fb = extract_tiktok_fields_from_text(html_text, ["followingVisibility"])
        vis = _safe_int_optional(fb.get("followingVisibility"))
    if vis is None:
        return
    snap.profile_following_visibility = vis
    snap.following_list_hidden_guess = following_list_hidden_guess_from_visibility(vis)


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
    - profile_following_visibility：user.followingVisibility，关注列表可见性（1=公开，非 1 常见为仅自己）。
    - following_list_hidden_guess：由 followingVisibility 推导，True 表示关注列表对他人不可见。
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
            snap.profile_following_visibility = _safe_int_optional(u.get("followingVisibility"))
            snap.following_list_hidden_guess = following_list_hidden_guess_from_visibility(
                snap.profile_following_visibility
            )
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


def parse_tiktok_user_from_universal(
    universal: Optional[Dict[str, Any]], page_username: Optional[str] = None
) -> Optional[TikTokUserInfo]:
    """
    从 Universal 解析 TikTokUserInfo。
    若传入 page_username，则优先只接受 user.uniqueId 与该用户名一致的 userInfo（与发布工具一致，避免串号）。
    """
    if not isinstance(universal, dict):
        return None
    if page_username:
        ui, _d = _get_matched_user_info_from_universal(universal, page_username)
        if ui is not None:
            return _parse_user_info_to_model(ui)
    scope = universal.get("__DEFAULT_SCOPE__")
    user_info = _find_user_info_from_scope(scope)
    if user_info is not None:
        return _parse_user_info_to_model(user_info)
    for node in _walk_json_dict(universal):
        if not isinstance(node, dict):
            continue
        ui = node.get("userInfo")
        if isinstance(ui, dict) and isinstance(ui.get("user"), dict) and ui["user"].get("id") is not None:
            if isinstance(ui.get("stats"), dict) or ui["user"].get("secUid"):
                return _parse_user_info_to_model(ui)
    return None


def _append_tiktok_debug_txt(
    txt_path: str,
    username: str,
    response: requests.Response,
    html_text: str,
    soup_str: str,
    soup_only_txt_path=None,
    tiktok_user_info: Optional[TikTokUserInfo] = None,
    cookie_debug_lines: Optional[List[str]] = None,
    user_detail_meta: Optional[Tuple[Any, str]] = None,
    universal_extract: Optional[TikTokUniversalExtract] = None,
) -> None:
    """
    将单次请求的摘要与正文片段追加到 tiktok_fetch_debug.txt（含完整 soup 字符串）；
    tiktok_soup_str_ONLY.txt 仅写入 script#__UNIVERSAL_DATA_FOR_REHYDRATION__ 内 JSON 原文。
    """
    url = f"https://www.tiktok.com/@{username}"
    univ_inner = _extract_universal_script_inner_text(html_text)
    lines = []
    lines.append("")
    lines.append("=" * 80)
    lines.append(f"【块开始】用户名: @{username}")
    lines.append("-" * 80)
    lines.append(f"请求 URL: {url}")
    lines.append(f"HTTP 状态码: {response.status_code}")
    lines.append(f"最终 URL: {response.url}")
    lines.append(f"Content-Type: {response.headers.get('Content-Type', '')}")
    lines.append(f"正文长度: {len(html_text)} 字符")
    lines.append("-" * 80)
    lines.append("【Cookie 配置文件】")
    if cookie_debug_lines:
        lines.extend(cookie_debug_lines)
    else:
        lines.append("  （本次未附带 Cookie 加载说明）")
    lines.append("-" * 80)
    lines.append("【关键字检测】")
    marker_follower = '"followerCount":'
    idx = html_text.find(marker_follower)
    lines.append(f'  "{marker_follower}" 首次出现下标: {idx if idx >= 0 else "未找到"}')
    if idx >= 0:
        a, b = max(0, idx - 150), min(len(html_text), idx + 250)
        lines.append("  邻近原文片段 (用于核对 JSON 是否还在):")
        lines.append("  " + html_text[a:b].replace("\n", "\\n"))
    for name in (
        "__UNIVERSAL_DATA_FOR_REHYDRATION__",
        "SIGI_STATE",
        "__NEXT_DATA__",
    ):
        yes_no = "是" if name in html_text else "否"
        lines.append(f"  包含子串 {name!r}: {yes_no}")
    lines.append("-" * 80)
    lines.append("【tiktok_soup_str_ONLY.txt】")
    lines.append(
        f"  仅写入 script#__UNIVERSAL_DATA_FOR_REHYDRATION__ 内 JSON 原文，"
        f"当前长度 {len(univ_inner)} 字符"
    )
    if not univ_inner:
        lines.append("  （无内容：可能为 WAF 壳页或未返回内嵌 Universal）")
    lines.append("-" * 80)
    lines.append("【Universal Data → TikTokUserInfo】")
    if tiktok_user_info is not None:
        t = tiktok_user_info
        lines.append(f"  uid: {t.uid}")
        lines.append(f"  sec_uid: {t.sec_uid}")
        lines.append(f"  unique_id: {t.unique_id}")
        lines.append(f"  nickname: {t.nickname}")
        lines.append(f"  follower_count: {t.follower_count}")
        lines.append(f"  following_count: {t.following_count}")
        lines.append(f"  heart_count: {t.heart_count}")
        lines.append(f"  video_count: {t.video_count}")
    else:
        lines.append("  (未能从内嵌 __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON 解析出用户信息)")
    lines.append("-" * 80)
    lines.append("【extract_tiktok_universal_profile_snapshot】")
    if universal_extract is not None:
        ex = universal_extract
        lines.append(f"  请求主页: @{ex.page_username}")
        lines.append(
            f"  主页作者 user: privateAccount={ex.profile_private_account}, secret={ex.profile_secret}, "
            f"followingVisibility={ex.profile_following_visibility}, "
            f"following_list_hidden_guess={ex.following_list_hidden_guess}, "
            f"uniqueId={ex.profile_unique_id!r}"
        )
        lines.append(
            f"  会话用户 app-context.user: isPrivateAccount={ex.session_is_private_account}, "
            f"showPrivateBanner={ex.session_show_private_banner}, "
            f"region={ex.session_region!r}, storeRegion={ex.session_store_region!r}"
        )
        lines.append(
            f"  与发布工具一致指标: 粉丝={ex.follower_count}, 关注={ex.following_count}, "
            f"总赞/点赞量同源={ex.heart_count}, 视频={ex.video_count}"
        )
        pub = ex.to_publish_style_metrics()
        lines.append(
            f"  to_publish_style_metrics: {pub}"
        )
        lines.append(f"  forceLoginSpecialRegions: {list(ex.force_login_special_regions)}")
        lines.append(f"  biz hashedIP: {ex.biz_hashed_ip!r}" if ex.biz_hashed_ip else "  biz hashedIP: (空)")
        pref_parts = []
        for pref, samples in list(ex.i18n_country_prefix_samples.items())[:15]:
            tail = "; ".join(samples[:2])
            if len(samples) > 2:
                tail += "…"
            pref_parts.append(f"{pref}({tail})")
        lines.append(
            "  i18n 两位国码前缀示例键(部分): " + (" | ".join(pref_parts) if pref_parts else "(无)")
        )
    else:
        lines.append("  (本块未生成 extract)")
    lines.append("-" * 80)
    lines.append("【webapp.user-detail（SSR）】")
    if user_detail_meta is not None:
        code, msg = user_detail_meta
        lines.append(f"  statusCode: {code}")
        lines.append(f"  statusMsg: {msg!r}")
        if code not in (0, "0", None) and str(code) != "0":
            lines.append(
                "  说明: 非 0 时通常无 userInfo / 无粉丝等统计；"
                "可能是用户名不存在、私密、地区限制或 TikTok 改为仅客户端拉取。"
            )
    else:
        lines.append("  (Universal 中无 webapp.user-detail.statusCode 字段)")
    lines.append("-" * 80)
    lines.append("【<title>】")
    m = re.search(r"<title[^>]*>([^<]{0,500})", html_text, re.I)
    lines.append(f"  {m.group(1).strip() if m else '(未匹配到 title)'}")
    lines.append("-" * 80)
    lines.append("【正文开头 6000 字符】(过长已截断，便于判断是否为验证页/空壳页)")
    lines.append(html_text[:6000])
    if len(html_text) > 6000:
        lines.append(f"\n... 以下省略 {len(html_text) - 6000} 字符 ...")
    lines.append("-" * 80)
    lines.append('【str(BeautifulSoup(response.text, "html.parser")) 完整内容】')
    lines.append(
        f"与代码里 soup = str(BeautifulSoup(response.text, \"html.parser\")) 完全一致；"
        f"长度 {len(soup_str)} 字符。可直接 Ctrl+F 搜 followerCount。"
    )
    lines.append("-" * 80)

    with open(txt_path, "a", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
        f.write(soup_str)
        f.write("\n" + "-" * 80 + "\n")
        f.write("【以上为完整 soup 字符串】\n")
        f.write("=" * 80 + "\n")
        f.write(f"【块结束】@{username}\n\n")

    if soup_only_txt_path:
        with open(soup_only_txt_path, "w", encoding="utf-8") as sf:
            if univ_inner:
                sf.write(univ_inner)
            else:
                sf.write(
                    "(未找到 script#__UNIVERSAL_DATA_FOR_REHYDRATION__ 或内容为空。)\n"
                )


def profile_metrics_dict_for_username(username: str) -> Optional[Dict[str, Any]]:
    """
    查询单个用户画像（与旧版 autoSearch_5.28 --profile JSON 字段兼容）。
    供 server / follower 爬虫 / autoSearch_5.28 共用。
    """
    username = str(username or "").strip().lstrip("@")
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
    use_ck = _should_use_tiktok_cookie()
    cookie_path = _resolve_tiktok_cookie_file_path() if use_ck else None
    if use_ck:
        cookies_dict, _cookie_debug_lines = _load_tiktok_cookies_from_file(cookie_path)
    else:
        cookies_dict = None
    if cookies_dict:
        headers["Referer"] = "https://www.tiktok.com/"
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

    try:
        response = requests.get(
            url, headers=headers, cookies=cookies_dict, timeout=15
        )
        response.raise_for_status()
        html_raw = response.text or ""
    except Exception as exc:
        print(f"抓取失败 [{username}]: {exc}")
        return None

    universal = _get_universal_data(html_raw)
    snap = extract_tiktok_universal_profile_snapshot(universal, username)
    apply_following_visibility_html_fallback(snap, html_raw)
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


def scrape_tiktok_followers(
    username,
    debug_txt_path=None,
    soup_only_txt_path=None,
    print_snapshot: bool = False,
):
    url = f"https://www.tiktok.com/@{username}"
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
            "  TikTok Cookie：已关闭（ENABLE_TIKTOK_COOKIE=False 或环境变量 TIKTOK_SKIP_COOKIE=1 / 命令行 --no-cookie），不读取、不发送 Cookie。"
        ]
    if cookies_dict:
        headers["Referer"] = "https://www.tiktok.com/"
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

    try:
        response = requests.get(
            url,
            headers=headers,
            cookies=cookies_dict,
            timeout=10,
        )
        html_raw = response.text
        soup = str(BeautifulSoup(html_raw, "html.parser"))
        univ_inner = _extract_universal_script_inner_text(html_raw)
        universal = _get_universal_data(html_raw)
        universal_extract = extract_tiktok_universal_profile_snapshot(universal, username)
        tiktok_user_info = parse_tiktok_user_from_universal(universal, page_username=username)
        user_detail_meta = _webapp_user_detail_meta(universal)
        if debug_txt_path:
            solo_path = soup_only_txt_path
            if solo_path is None:
                solo_path = os.path.join(
                    os.path.dirname(os.path.abspath(debug_txt_path)) or ".",
                    "tiktok_soup_str_ONLY.txt",
                )
            _append_tiktok_debug_txt(
                debug_txt_path,
                username,
                response,
                html_raw,
                soup,
                soup_only_txt_path=solo_path,
                tiktok_user_info=tiktok_user_info,
                cookie_debug_lines=cookie_debug_lines,
                user_detail_meta=user_detail_meta,
                universal_extract=universal_extract,
            )

        if print_snapshot:
            print("\n【Universal Snapshot】")
            if universal_extract is not None:
                snapshot_items = filter_tiktok_universal_extract(universal_extract)
                if snapshot_items:
                    for k, v in snapshot_items.items():
                        print(f"{k}: {v}")
                else:
                    print("(已解析到 Universal 结构，但筛选结果为空)")
            else:
                print("(未解析到 Universal 结构对象，改为从原始 JSON 文本提取)")
                raw_fields = extract_tiktok_fields_from_text(
                    univ_inner,
                    [
                        "page_username",
                        "user_detail_status_code",
                        "user_detail_status_msg",
                        "profile_uid",
                        "profile_sec_uid",
                        "profile_unique_id",
                        "profile_nickname",
                        "profile_private_account",
                        "profile_secret",
                        "profile_following_visibility",
                        "following_list_hidden_guess",
                        "follower_count",
                        "following_count",
                        "heart_count",
                        "video_count",
                        "session_is_private_account",
                        "session_show_private_banner",
                        "session_region",
                        "session_store_region",
                        "force_login_special_regions",
                        "biz_hashed_ip",
                    ],
                    include_none=True,
                )
                for k, v in raw_fields.items():
                    print(f"{k}: {v}")

        if tiktok_user_info is not None:
            return str(tiktok_user_info.follower_count)
        if universal_extract is not None and universal_extract.follower_count > 0:
            return str(universal_extract.follower_count)

        # 无内嵌 Universal / WAF 壳页等：回退正则（仍从整页 HTML 抠 followerCount）
        m = re.search(r'"followerCount"\s*:\s*(\d+)', html_raw)
        if m:
            return m.group(1)
        m2 = re.search(r'"followerCount"\s*:\s*(\d+)', soup)
        if m2:
            return m2.group(1)

        # 回退：旧版固定偏移（易因字段长度变化失效）
        step = soup.find('"followerCount":')
        if step < 0:
            return _follower_not_found_message(user_detail_meta)
        followers_tag = soup[step + 16 : step + 22]
        found = re.findall(r"\d+", followers_tag)
        return found[0] if found else _follower_not_found_message(user_detail_meta)
    except Exception as e:
        print(f"抓取失败: {str(e)}")
        return "抓取失败"


def read_first_column(file_path):
    """
    读取xlsx文件中第一列的所有单元格内容
    :param file_path: Excel文件路径
    :return: 包含第一列内容的列表
    """
    # 加载工作簿
    workbook = openpyxl.load_workbook(file_path)

    # 获取第一个工作表
    sheet = workbook.active

    # 读取第一列数据（假设从第一行开始）
    first_column = []
    for row in sheet.iter_rows(min_col=1, max_col=1):
        cell_value = row[0].value
        if cell_value is not None:  # 跳过空单元格
            first_column.append(str(cell_value))  # 确保转换为字符串

    return first_column


def process_and_save_column(input_file, output_file=None, debug_txt_path=None):
    """
    读取第一列数据，处理后存入第二列
    :param input_file: 输入文件路径
    :param output_file: 输出文件路径（如果为None，则覆盖原文件）
    :param debug_txt_path: 若提供路径，将把每次 TikTok 请求的摘要与 HTML 片段写入该 txt（本运行会先清空再写入）
    """
    if debug_txt_path:
        base_dir = os.path.dirname(os.path.abspath(debug_txt_path)) or "."
        os.makedirs(base_dir, exist_ok=True)
        with open(debug_txt_path, "w", encoding="utf-8") as f:
            f.write("TikTok 页面抓取 — 调试导出\n")
            f.write(f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            f.write(f"输入 Excel: {os.path.abspath(input_file)}\n")
            f.write("说明: 每个账号对应下面一块「=====」分隔的内容。\n")
            f.write(
                "Cookie: 可将 tiktok_cookies.example.json 复制为 tiktok_cookies.json 并填写；"
                "或设置环境变量 TIKTOK_COOKIES_FILE。详见脚本顶部文档字符串。\n"
            )
            if not _should_use_tiktok_cookie():
                f.write(
                    "当前运行: 无 Cookie 模式（ENABLE_TIKTOK_COOKIE=False 或 TIKTOK_SKIP_COOKIE=1）。\n"
                )
            f.write("\n")

    # 加载工作簿
    workbook = openpyxl.load_workbook(input_file)
    sheet = workbook.active

    # 遍历所有行,第三列写入第二列
    for row in sheet.iter_rows():
        # 获取C列值（第三列，索引2）
        source_cell = row[2] if len(row) > 2 else None

        # 获取B列单元格（第二列，索引1）
        target_cell = sheet.cell(row=row[0].row, column=2)

        # 复制值（如果C列存在数据）
        if source_cell and source_cell.value is not None:
            target_cell.value = source_cell.value
        else:
            target_cell.value = None  # 清空B列内容

    # 处理第一列并写入第三列
    for row_idx, row in enumerate(sheet.iter_rows(min_col=1, max_col=1), start=1):
        if row[0].value is not None:
            original_text = str(row[0].value)
            processed_text = scrape_tiktok_followers(
                original_text, debug_txt_path=debug_txt_path
            )
            # 写入第3列（C列）
            sheet[f"C{row_idx}"] = processed_text
    process_video_analysis(sheet)
    # 自动调整第二列宽度
    column_letter = get_column_letter(4)  # c列
    sheet.column_dimensions[column_letter].width = 30
    # 保存文件
    output_file = output_file or input_file
    workbook.save(output_file)
    print(f"处理完成，结果已保存到: {output_file}")


def process_video_analysis(sheet):
    """
       处理视频数据（纯净版）
       功能：
       1. 清空所有行的4/5/6列
       2. 从第一行开始处理
       3. 保留核心业务逻辑
       """
    # 初始化统计指标
    stats = {
        "total_need_change": 0,
        "total_fans_increase": 0.0,
        "natural_flow_accounts": 0,
    }

    # 样式配置
    highlight_style = PatternFill(start_color="FFFF00", fill_type="solid")

    # ========== 清空所有行的4/5/6列 ==========
    max_row = sheet.max_row
    for row_idx in range(1, max_row + 1):
        for col in [4, 5, 6]:  # D/E/F列
            sheet.cell(row=row_idx, column=col).value = None

    # ========== 数据处理（从第一行开始）==========
    for row_idx, row in enumerate(sheet.iter_rows(min_row=1), start=1):
        try:
            # 获取原始数据
            a_value = sheet.cell(row=row_idx, column=1).value  # A列
            b_value = sheet.cell(row=row_idx, column=2).value  # B列
            c_value = sheet.cell(row=row_idx, column=3).value  # C列

            # 数值转换函数
            def to_float(value):
                try:
                    return float(value)
                except (TypeError, ValueError):
                    return None

            b_num = to_float(b_value)
            c_num = to_float(c_value)
            d_cell = sheet.cell(row=row_idx, column=4)
            e_cell = sheet.cell(row=row_idx, column=5)

            # 主逻辑处理
            if all([isinstance(b_num, float), isinstance(c_num, float)]):
                # 条件1：两列都>5000
                if b_num > 5000 and c_num > 5000:
                    d_cell.value = 0
                else:
                    # 计算差值
                    diff = c_num - b_num
                    d_cell.value = diff

                    # 统计总粉丝增长
                    stats["total_fans_increase"] += diff

                    # 条件2：自然流账号统计
                    if c_num >= 5000 and b_num < 5000:
                        stats["natural_flow_accounts"] += 1

                    # 更换提示逻辑
                    if diff <= 30:
                        stats["total_need_change"] += 1
                        e_cell.value = f"需要更换视频，用户名为：{a_value or '未知'}"
                        # 设置高亮
                        d_cell.fill = highlight_style
                        e_cell.fill = highlight_style
            else:
                d_cell.value = "需要手动搜索观察"

        except Exception as e:
            print(f"行 {row_idx} 处理异常: {str(e)}")
            continue

    # ========== 写入统计结果 ==========
    stats_start_row = max_row + 2  # 在数据下方追加

    # 清除原统计区域（F列）
    for r in range(stats_start_row, stats_start_row + 4):
        sheet.cell(r, 6).value = None

    # 写入新统计
    sheet.cell(stats_start_row, 6).value = f"需要更换数量：{stats['total_need_change']}"
    sheet.cell(stats_start_row + 1, 6).value = f"总增加粉丝：{stats['total_fans_increase']:.0f}"
    sheet.cell(
        stats_start_row + 2, 6
    ).value = f"5000+粉丝自然流账号增加数量：{stats['natural_flow_accounts']}"


def _init_debug_txt_header(txt_path: str, extra_line: str = "") -> None:
    """新建或覆盖调试 txt 的头部说明。"""
    base_dir = os.path.dirname(os.path.abspath(txt_path)) or "."
    os.makedirs(base_dir, exist_ok=True)
    with open(txt_path, "w", encoding="utf-8") as f:
        f.write("TikTok 页面抓取 — 调试导出\n")
        f.write(f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
        if extra_line:
            f.write(extra_line.rstrip() + "\n")
        f.write("\n")


def _run_autosearch_once_from_cli() -> None:
    """定时任务 --once：执行 autoSearch_5.28 的粉丝监控逻辑（抓取仍走本文件）。"""
    import importlib.util

    script_dir = os.path.dirname(os.path.abspath(__file__))
    legacy_path = os.path.join(script_dir, "autoSearch_5.28.py")
    spec = importlib.util.spec_from_file_location("autosearch528", legacy_path)
    if spec is None or spec.loader is None:
        raise ImportError(f"无法加载: {legacy_path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    mod.init_db()
    mod.run_once(batch_size=1000)


if __name__ == "__main__":
    _script_dir = os.path.dirname(os.path.abspath(__file__))
    _debug_out = (
        os.path.join(_script_dir, "tiktok_fetch_debug.txt") if ENABLE_DEBUG_TXT else None
    )

    _cli_args = list(sys.argv[1:])
    while _cli_args and _cli_args[0].lower() in ("--no-cookie", "-n", "--no_cookie"):
        os.environ["TIKTOK_SKIP_COOKIE"] = "1"
        _cli_args.pop(0)

    if _cli_args and _cli_args[0] == "--once":
        _run_autosearch_once_from_cli()
        sys.exit(0)

    if _cli_args and _cli_args[0] == "--profile":
        uname = (_cli_args[1] if len(_cli_args) > 1 else "").strip().lstrip("@")
        if not uname:
            print(json.dumps({}, ensure_ascii=False))
            sys.exit(1)
        data = profile_metrics_dict_for_username(uname)
        print(json.dumps(data or {}, ensure_ascii=False))
        sys.exit(0)

    # 单用户： python autoSearch_5.28_with_debug.py [--no-cookie] tiktokusername
    if _cli_args:
        one = _cli_args[0].strip().lstrip("@")
        if not one:
            print("用户名不能为空。")
            sys.exit(1)
        if _debug_out:
            _hdr = f"单用户测试: @{one}"
            if not _should_use_tiktok_cookie():
                _hdr += " | 无 Cookie 模式"
            _init_debug_txt_header(_debug_out, _hdr)
        result = scrape_tiktok_followers(one, debug_txt_path=_debug_out, print_snapshot=True)
        print(f"用户名: @{one}")
        print(f"解析结果（写入 Excel 时会是这个字符串）: {result}")
        print("Cookie 配置:")
        if _should_use_tiktok_cookie():
            _cp = _resolve_tiktok_cookie_file_path()
            _, _ck_dbg = _load_tiktok_cookies_from_file(_cp)
        else:
            _ck_dbg = [
                "  TikTok Cookie：已关闭（ENABLE_TIKTOK_COOKIE=False 或 TIKTOK_SKIP_COOKIE=1 / --no-cookie）。"
            ]
        for _ln in _ck_dbg:
            print(_ln.rstrip())

        if _debug_out:
            print(f"完整页面摘要已写入: {_debug_out}")
            print(
                "Universal 内嵌 JSON 原文（script#__UNIVERSAL_DATA_FOR_REHYDRATION__）: "
                f"{os.path.join(_script_dir, 'tiktok_soup_str_ONLY.txt')}"
            )
            print("用记事本/VS Code 打开上述文件；不要在 autoSearch_5.28_test_write.txt 里找（那是历史权限测试残留）。")
        sys.exit(0)

    file_path = "wudijiaoben.xlsx"  # 替换为你的文件路径
    process_and_save_column(file_path, file_path, debug_txt_path=_debug_out)
    if _debug_out:
        print(f"调试导出已写入: {_debug_out}")
