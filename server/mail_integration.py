"""Outlook 邮箱管理模块与 FastAPI 主服务的集成层。

Node 子服务运行在 server/mail_module/，本模块负责：
1. 按需启动/停止 Node 子进程
2. 将邮箱管理 API 反向代理到子服务（路径与原项目保持一致）
3. 托管邮箱管理前端静态资源（/mail-admin/）
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any, Callable

import httpx
from fastapi import APIRouter, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.base import BaseHTTPMiddleware

logger = logging.getLogger("mail_integration")

SERVER_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SERVER_DIR.parent
MAIL_MODULE_DIR = SERVER_DIR / "mail_module"
MAIL_ACCOUNTS_DB_PATH = MAIL_MODULE_DIR / "data" / "accounts.sqlite"
MAIL_WEB_DIST = PROJECT_ROOT / "web-mail-admin"
if not MAIL_WEB_DIST.exists():
    MAIL_WEB_DIST = MAIL_MODULE_DIR / "web" / "MS_OAuth2API_Next_Web" / "dist"

MAIL_MODULE_ENABLED = os.environ.get("MAIL_MODULE_ENABLED", "1").strip().lower() not in {"0", "false", "no"}
MAIL_MODULE_PORT = int(os.environ.get("MAIL_MODULE_PORT", "3100"))
MAIL_MODULE_HOST = os.environ.get("MAIL_MODULE_HOST", "127.0.0.1").strip() or "127.0.0.1"
MAIL_MODULE_STARTUP_TIMEOUT_SEC = float(os.environ.get("MAIL_MODULE_STARTUP_TIMEOUT_SEC", "30"))
MAIL_INTERNAL_SECRET = os.environ.get("MAIL_INTERNAL_SECRET", "auto-traces-mail-internal").strip()

_node_process: subprocess.Popen[Any] | None = None
_http_client: httpx.AsyncClient | None = None
MAIL_SESSION_RESOLVER: Any = None
_mail_token_refresh_stop = threading.Event()
_mail_token_refresh_thread: threading.Thread | None = None
_mail_token_refresh_lock = threading.Lock()
_mail_token_refresh_in_flight = False
_mail_token_refresh_last: dict[str, Any] = {
    "started_at": "",
    "finished_at": "",
    "status": "idle",
    "message": "",
    "exit_code": None,
}

# 每日巡检；跳过「近 N 天已检测过」的令牌，实现约二十天刷一次
MAIL_TOKEN_REFRESH_ENABLED = os.environ.get("MAIL_TOKEN_REFRESH_ENABLED", "1").strip().lower() not in {
    "0",
    "false",
    "no",
}
MAIL_TOKEN_REFRESH_TICK_SEC = max(3600, int(os.environ.get("MAIL_TOKEN_REFRESH_TICK_SEC", str(24 * 3600))))
MAIL_TOKEN_REFRESH_SKIP_DAYS = max(1, int(os.environ.get("MAIL_TOKEN_REFRESH_SKIP_DAYS", "20")))
MAIL_TOKEN_REFRESH_SCRIPT = MAIL_MODULE_DIR / "scripts" / "auto-refresh-tokens.js"

# 无需登录的邮箱 API（健康检查）
MAIL_API_PUBLIC_PATHS = {
    "/api/server_time",
}

# 与原 ms-mail-tool-personal 路由保持一致，仅代理邮箱模块相关 API。
MAIL_API_PREFIXES = (
    "/api/mail_all",
    "/api/mail_new",
    "/api/mail_one",
    "/api/process-mailbox",
    "/api/test-proxy",
    "/api/template/",
    "/api/sender_filter",
    "/api/sender_groups",
    "/api/sender_options",
    "/api/accounts/",
    "/api/mail/cache/",
    "/api/database/stats",
    "/api/project/",
    "/api/tk-pending/",
    "/api/server_time",
)


def _mail_upstream_base() -> str:
    return f"http://{MAIL_MODULE_HOST}:{MAIL_MODULE_PORT}"


def _is_mail_api_path(path: str) -> bool:
    normalized = path.split("?", 1)[0].rstrip("/") or "/"
    for prefix in MAIL_API_PREFIXES:
        base = prefix.rstrip("/")
        if prefix.endswith("/"):
            if normalized.startswith(base):
                return True
        elif normalized == base or normalized.startswith(base + "/"):
            return True
    return False


def _node_binary() -> str | None:
    configured = os.environ.get("NODE_BINARY", "").strip()
    if configured and Path(configured).exists():
        return configured
    return shutil.which("node")


def _mail_module_ready() -> bool:
    try:
        resp = httpx.get(f"{_mail_upstream_base()}/api/server_time", timeout=1.5)
        return resp.status_code < 500
    except Exception:
        return False


def is_mail_module_ready() -> bool:
    """邮箱管理 Node 子服务是否可用。"""
    return MAIL_MODULE_ENABLED and _mail_module_ready()


def ensure_mail_module_ready() -> bool:
    """拉信前确保 Node 子服务已就绪（未启动则尝试拉起）。"""
    if not MAIL_MODULE_ENABLED:
        return False
    if _mail_module_ready():
        return True
    return _start_node_process()


def _normalize_mail_email(value: str) -> str:
    return str(value or "").strip().lower()


def get_mail_account_record(owner_username: str, email: str) -> dict[str, Any] | None:
    """从邮箱管理模块 SQLite 读取账户（含 OAuth 令牌）。"""
    owner = str(owner_username or "").strip()
    account_email = _normalize_mail_email(email)
    if not owner or not account_email or "@" not in account_email:
        return None
    if not MAIL_ACCOUNTS_DB_PATH.exists():
        return None
    try:
        conn = sqlite3.connect(str(MAIL_ACCOUNTS_DB_PATH), timeout=2.0)
        conn.row_factory = sqlite3.Row
        try:
            row = conn.execute(
                """
                SELECT
                  record_key,
                  email,
                  password,
                  client_id,
                  refresh_token,
                  status,
                  owner_username,
                  updated_at,
                  token_status,
                  last_token_error,
                  last_token_checked_at,
                  token_access_expires_at
                FROM account_records
                WHERE lower(email) = lower(?)
                  AND status = 'active'
                  AND (owner_username = ? OR owner_username = '')
                ORDER BY
                  CASE WHEN owner_username = ? THEN 0 ELSE 1 END,
                  updated_at DESC
                LIMIT 1
                """,
                (account_email, owner, owner),
            ).fetchone()
        finally:
            conn.close()
    except sqlite3.Error as exc:
        logger.warning("Failed to read mail account record for %s: %s", account_email, exc)
        return None
    return dict(row) if row else None


def mail_account_has_valid_token(record: dict[str, Any] | None) -> bool:
    if not record:
        return False
    if str(record.get("status") or "").strip().lower() != "active":
        return False
    token_status = str(record.get("token_status") or "").strip().lower()
    if token_status == "invalid":
        return False
    client_id = str(record.get("client_id") or "").strip()
    refresh_token = str(record.get("refresh_token") or "").strip()
    email = str(record.get("email") or "").strip()
    return bool(email and client_id and refresh_token)


def list_invalid_mail_token_emails(owner_username: str) -> set[str]:
    """返回 owner 下 token_status=invalid 的邮箱集合（小写）。"""
    owner = str(owner_username or "").strip()
    if not owner or not MAIL_ACCOUNTS_DB_PATH.exists():
        return set()
    try:
        conn = sqlite3.connect(str(MAIL_ACCOUNTS_DB_PATH), timeout=2.0)
        try:
            rows = conn.execute(
                """
                SELECT DISTINCT lower(email) AS email
                FROM account_records
                WHERE status = 'active'
                  AND lower(COALESCE(token_status, '')) = 'invalid'
                  AND TRIM(email) <> ''
                  AND (owner_username = ? OR owner_username = '')
                """,
                (owner,),
            ).fetchall()
        finally:
            conn.close()
    except sqlite3.Error as exc:
        logger.warning("Failed to list invalid mail token emails for %s: %s", owner, exc)
        return set()
    out: set[str] = set()
    for row in rows or []:
        email = _normalize_mail_email(row[0] if not hasattr(row, "keys") else row["email"])
        if email and "@" in email:
            out.add(email)
    return out


def list_fetchable_mail_emails(owner_username: str) -> set[str] | None:
    """返回可拉验证码的邮箱集合（有 OAuth 且非 invalid）。

    返回 None 表示邮件库不可用，调用方应 fail-open（不要误伤全部账号）。
    """
    status_map = list_mail_oauth_token_status_map(owner_username)
    if status_map is None:
        return None
    return {email for email, status in status_map.items() if status != "invalid"}


def list_mail_oauth_token_status_map(owner_username: str) -> dict[str, str] | None:
    """返回 email -> token_status（仅含已配置 client_id+refresh_token 的活跃记录）。

    返回 None 表示邮件库不可用。
    """
    owner = str(owner_username or "").strip()
    if not owner:
        return {}
    if not MAIL_ACCOUNTS_DB_PATH.exists():
        return None
    try:
        conn = sqlite3.connect(str(MAIL_ACCOUNTS_DB_PATH), timeout=2.0)
        try:
            rows = conn.execute(
                """
                SELECT lower(email) AS email, lower(COALESCE(token_status, 'unknown')) AS token_status
                FROM account_records
                WHERE status = 'active'
                  AND TRIM(email) <> ''
                  AND TRIM(COALESCE(client_id, '')) <> ''
                  AND TRIM(COALESCE(refresh_token, '')) <> ''
                  AND (owner_username = ? OR owner_username = '')
                ORDER BY
                  CASE WHEN owner_username = ? THEN 0 ELSE 1 END,
                  updated_at DESC
                """,
                (owner, owner),
            ).fetchall()
        finally:
            conn.close()
    except sqlite3.Error as exc:
        logger.warning("Failed to list mail oauth token status for %s: %s", owner, exc)
        return None
    out: dict[str, str] = {}
    for row in rows or []:
        email = _normalize_mail_email(row[0] if not hasattr(row, "keys") else row["email"])
        if not email or "@" not in email or email in out:
            continue
        status = str(row[1] if not hasattr(row, "keys") else row["token_status"] or "unknown").strip().lower()
        if status not in {"valid", "invalid", "transient", "unknown"}:
            status = "unknown"
        out[email] = status
    return out


def mail_module_can_fetch_code(owner_username: str, email: str) -> bool:
    """邮箱管理模块是否已配置该邮箱且令牌齐全。"""
    if not is_mail_module_ready():
        # Node 未就绪时仍可读本地 SQLite 判断（与建桶资格一致）
        fetchable = list_fetchable_mail_emails(owner_username)
        if fetchable is None:
            return False
        return _normalize_mail_email(email) in fetchable
    record = get_mail_account_record(owner_username, email)
    return mail_account_has_valid_token(record)


def _internal_mail_headers(owner_username: str) -> dict[str, str]:
    return {
        "X-Mail-Internal-Secret": MAIL_INTERNAL_SECRET,
        "X-Mail-Owner": str(owner_username or "").strip(),
        "Content-Type": "application/json",
    }


def _extract_mail_messages(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        emails = payload.get("emails")
        if isinstance(emails, list):
            return [item for item in emails if isinstance(item, dict)]
        data = payload.get("data")
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
        if isinstance(data, dict):
            nested = data.get("emails")
            if isinstance(nested, list):
                return [item for item in nested if isinstance(item, dict)]
    return []


def _mail_message_sort_key(mail: dict[str, Any]) -> float:
    raw = str(mail.get("date") or "").strip()
    if not raw:
        return 0.0
    try:
        return float(time.mktime(time.strptime(raw[:19], "%Y-%m-%dT%H:%M:%S")))
    except Exception:
        pass
    try:
        return float(time.mktime(time.strptime(raw[:19], "%Y-%m-%d %H:%M:%S")))
    except Exception:
        return 0.0


def fetch_mails_via_mail_module(
    owner_username: str,
    account: dict[str, Any],
    *,
    mailbox: str = "INBOX",
    top: int = 30,
    timeout_sec: float = 60.0,
) -> list[dict[str, Any]]:
    """通过内部接口拉取邮箱最近邮件（不经过 FastAPI 代理层）。"""
    owner = str(owner_username or "").strip()
    email = str(account.get("email") or "").strip()
    client_id = str(account.get("client_id") or "").strip()
    refresh_token = str(account.get("refresh_token") or "").strip()
    if not owner or not email or not client_id or not refresh_token:
        return []
    if not is_mail_module_ready():
        return []

    payload = {
        "refresh_token": refresh_token,
        "client_id": client_id,
        "email": email,
        "mailbox": mailbox,
        "top": max(1, min(int(top or 30), 200)),
    }
    try:
        resp = httpx.post(
            f"{_mail_upstream_base()}/api/mail_new",
            headers=_internal_mail_headers(owner),
            json=payload,
            timeout=timeout_sec,
        )
    except httpx.RequestError as exc:
        logger.warning("Mail module mail_new failed for %s/%s: %s", email, mailbox, exc)
        return []
    if resp.status_code != 200:
        logger.warning(
            "Mail module mail_new HTTP %s for %s/%s: %s",
            resp.status_code,
            email,
            mailbox,
            (resp.text or "")[:200],
        )
        return []
    try:
        body = resp.json()
    except ValueError:
        return []
    if str(body.get("code") or "") not in {"200", 200}:
        return []
    return _extract_mail_messages(body.get("data"))


def _extract_code_from_mails(
    mails: list[dict[str, Any]],
    extract_code: Callable[[str], str],
) -> str | None:
    for mail in sorted(mails, key=_mail_message_sort_key, reverse=True):
        for field in ("subject", "text", "html"):
            chunk = str(mail.get(field) or "").strip()
            if not chunk:
                continue
            try:
                code = extract_code(chunk)
            except ValueError:
                continue
            if code:
                return code
    return None


def fetch_tiktok_email_code_via_mail_module(
    owner_username: str,
    email: str,
    extract_code: Callable[[str], str],
    *,
    top: int = 30,
) -> tuple[int, str, str | None]:
    """优先从邮箱管理模块拉信并解析 TikTok 6 位验证码。"""
    if not ensure_mail_module_ready():
        return 1, "邮箱管理模块未就绪", None
    record = get_mail_account_record(owner_username, email)
    if not mail_account_has_valid_token(record):
        return 1, "邮箱管理模块未配置该邮箱或 OAuth 令牌缺失", None

    found_any = False
    for mailbox in ("INBOX", "Junk"):
        mails = fetch_mails_via_mail_module(owner_username, record or {}, mailbox=mailbox, top=top)
        if not mails:
            continue
        found_any = True
        code = _extract_code_from_mails(mails, extract_code)
        if code:
            return 0, "ok", code
    if not found_any:
        return 1, "邮箱管理模块未拉取到邮件", None
    return 1, "邮箱管理模块邮件中未识别到 6 位验证码", None


def _post_mail_module_json(owner_username: str, path: str, payload: dict[str, Any] | None = None) -> tuple[int, str, dict[str, Any] | None]:
    owner = str(owner_username or "").strip()
    if not owner:
        return 1, "owner 为空", None
    if not is_mail_module_ready():
        return 1, "邮箱管理模块未就绪", None
    try:
        resp = httpx.post(
            f"{_mail_upstream_base()}{path}",
            headers=_internal_mail_headers(owner),
            json=payload or {},
            timeout=30.0,
        )
    except httpx.RequestError as exc:
        return 1, f"邮箱管理模块请求失败: {exc}", None
    try:
        body = resp.json()
    except ValueError:
        return 1, f"邮箱管理模块返回非 JSON（HTTP {resp.status_code}）", None
    if resp.status_code >= 400 or str(body.get("code") or "") not in {"200", 200}:
        err = str(body.get("error") or body.get("message") or f"HTTP {resp.status_code}").strip()
        return 1, err or "邮箱管理模块请求失败", body.get("data") if isinstance(body.get("data"), dict) else None
    data = body.get("data")
    return 0, "ok", data if isinstance(data, dict) else {}


def acquire_pending_tk_account_via_mail_module(owner_username: str, email: str = "") -> tuple[int, str, dict[str, Any] | None]:
    payload: dict[str, Any] = {}
    account_email = str(email or "").strip()
    if account_email:
        payload["email"] = account_email
    code, msg, data = _post_mail_module_json(owner_username, "/api/tk-pending/accounts/acquire", payload)
    if code != 0:
        return code, msg, data
    if not data or not data.get("available") or not data.get("account"):
        reason = str((data or {}).get("reason") or "无可用待注册邮箱")
        return 1, reason, data
    return 0, "ok", data


def take_pending_tk_account_via_mail_module(owner_username: str, email: str = "") -> tuple[int, str, dict[str, Any] | None]:
    """从待注册池领取邮箱，并立即转入邮箱账户管理。"""
    payload: dict[str, Any] = {}
    account_email = str(email or "").strip()
    if account_email:
        payload["email"] = account_email
    code, msg, data = _post_mail_module_json(owner_username, "/api/tk-pending/accounts/take", payload)
    if code != 0:
        return code, msg, data
    if not data or not data.get("taken") or not data.get("account"):
        reason = str((data or {}).get("reason") or "无可用待注册邮箱")
        return 1, reason, data
    return 0, "ok", data


def complete_pending_tk_account_via_mail_module(
    owner_username: str,
    *,
    email: str = "",
    claim_id: str = "",
) -> tuple[int, str, dict[str, Any] | None]:
    payload: dict[str, Any] = {}
    account_email = str(email or "").strip()
    claim = str(claim_id or "").strip()
    if claim:
        payload["claim_id"] = claim
    if account_email:
        payload["email"] = account_email
    if not payload:
        return 1, "email 或 claim_id 不能为空", None
    code, msg, data = _post_mail_module_json(owner_username, "/api/tk-pending/accounts/complete", payload)
    if code != 0:
        return code, msg, data
    if not data or not data.get("promoted"):
        reason = str((data or {}).get("reason") or "注册完成入库失败")
        return 1, reason, data
    return 0, "ok", data


def _wait_for_mail_module() -> bool:
    deadline = time.monotonic() + MAIL_MODULE_STARTUP_TIMEOUT_SEC
    while time.monotonic() < deadline:
        if _node_process and _node_process.poll() is not None:
            logger.error("Mail module Node process exited early with code %s", _node_process.returncode)
            return False
        if _mail_module_ready():
            return True
        time.sleep(0.4)
    return False


def _start_node_process() -> bool:
    global _node_process

    if _mail_module_ready():
        logger.info("Mail module already reachable at %s", _mail_upstream_base())
        return True

    node = _node_binary()
    if not node:
        logger.warning("Node.js not found; mail module disabled. Install Node or set NODE_BINARY.")
        return False

    main_js = MAIL_MODULE_DIR / "main.js"
    if not main_js.exists():
        logger.warning("Mail module main.js not found at %s", main_js)
        return False

    env = os.environ.copy()
    env.setdefault("MAIL_MODULE_PORT", str(MAIL_MODULE_PORT))
    env.setdefault("MAIL_MODULE_HOST", MAIL_MODULE_HOST)
    env["PORT"] = str(MAIL_MODULE_PORT)
    env["HOST"] = MAIL_MODULE_HOST
    env.setdefault("MAIL_INTERNAL_SECRET", MAIL_INTERNAL_SECRET)
    # 注意：下面会把 env["PORT"] 改成邮件模块端口，主服务端口必须从原始 os.environ 读
    main_port = str(os.environ.get("MAIN_API_PORT") or os.environ.get("PORT") or "8000").strip() or "8000"
    if main_port == str(MAIL_MODULE_PORT):
        main_port = "8000"
    main_host = str(os.environ.get("MAIN_API_HOST", "127.0.0.1")).strip() or "127.0.0.1"
    env.setdefault("MAIN_API_BASE", os.environ.get("MAIN_API_BASE", f"http://{main_host}:{main_port}"))
    env.setdefault("MAIN_API_PORT", main_port)
    env.setdefault("MAIN_API_HOST", main_host)

    logs_dir = MAIL_MODULE_DIR / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    log_path = logs_dir / "mail-module-stdout.log"

    logger.info("Starting mail module Node process: %s %s", node, main_js)
    log_fp = open(log_path, "a", encoding="utf-8")
    _node_process = subprocess.Popen(
        [node, str(main_js)],
        cwd=str(MAIL_MODULE_DIR),
        env=env,
        stdout=log_fp,
        stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NO_WINDOW if sys.platform.startswith("win") else 0,
    )

    if not _wait_for_mail_module():
        logger.error("Mail module failed to become ready within %.0fs", MAIL_MODULE_STARTUP_TIMEOUT_SEC)
        _stop_node_process()
        return False

    logger.info("Mail module ready at %s", _mail_upstream_base())
    return True


def _stop_node_process() -> None:
    global _node_process
    proc = _node_process
    _node_process = None
    if proc is None:
        return
    if proc.poll() is None:
        # Windows 上 Node 对 terminate 响应慢，关闭时直接 kill 避免 uvicorn 卡住。
        if sys.platform.startswith("win"):
            proc.kill()
        else:
            proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
            with contextlib.suppress(Exception):
                proc.wait(timeout=2)


def stop_mail_module_process() -> None:
    """供主服务 shutdown 钩子调用，快速终止 Node 子进程。"""
    _mail_token_refresh_stop.set()
    _stop_node_process()


def get_mail_token_refresh_status() -> dict[str, Any]:
    with _mail_token_refresh_lock:
        return {
            "enabled": bool(MAIL_TOKEN_REFRESH_ENABLED),
            "tick_sec": int(MAIL_TOKEN_REFRESH_TICK_SEC),
            "skip_days": int(MAIL_TOKEN_REFRESH_SKIP_DAYS),
            "in_flight": bool(_mail_token_refresh_in_flight),
            "last": dict(_mail_token_refresh_last),
            "script": str(MAIL_TOKEN_REFRESH_SCRIPT),
        }


def run_mail_token_refresh_job(*, force_all: bool = False, owner_username: str = "") -> dict[str, Any]:
    """运行一次令牌刷新任务（子进程执行 Node 脚本）。

    force_all=True：忽略「近 N 天已检测」跳过，全量刷新（仍默认跳过已标 invalid，除非脚本 env 打开）。
    """
    global _mail_token_refresh_in_flight
    with _mail_token_refresh_lock:
        if _mail_token_refresh_in_flight:
            return {"code": 1, "msg": "令牌刷新任务正在运行"}
        _mail_token_refresh_in_flight = True
        _mail_token_refresh_last.update(
            {
                "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                "finished_at": "",
                "status": "running",
                "message": "force_all" if force_all else f"skip_days={MAIL_TOKEN_REFRESH_SKIP_DAYS}",
                "exit_code": None,
            }
        )

    node = _node_binary()
    if not node:
        with _mail_token_refresh_lock:
            _mail_token_refresh_in_flight = False
            _mail_token_refresh_last.update(
                {"status": "failed", "finished_at": time.strftime("%Y-%m-%d %H:%M:%S"), "message": "Node.js not found"}
            )
        return {"code": 1, "msg": "未找到 Node.js"}
    if not MAIL_TOKEN_REFRESH_SCRIPT.exists():
        with _mail_token_refresh_lock:
            _mail_token_refresh_in_flight = False
            _mail_token_refresh_last.update(
                {
                    "status": "failed",
                    "finished_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "message": f"script missing: {MAIL_TOKEN_REFRESH_SCRIPT}",
                }
            )
        return {"code": 1, "msg": "刷新脚本不存在"}

    env = os.environ.copy()
    env.setdefault("MAIL_INTERNAL_SECRET", MAIL_INTERNAL_SECRET)
    main_port = str(os.environ.get("MAIN_API_PORT") or os.environ.get("PORT") or "8000").strip() or "8000"
    if main_port == str(MAIL_MODULE_PORT):
        main_port = "8000"
    main_host = str(os.environ.get("MAIN_API_HOST", "127.0.0.1")).strip() or "127.0.0.1"
    env.setdefault("MAIN_API_BASE", os.environ.get("MAIN_API_BASE", f"http://{main_host}:{main_port}"))
    env["TOKEN_REFRESH_SKIP_CHECKED_WITHIN_MINUTES"] = "0" if force_all else str(MAIL_TOKEN_REFRESH_SKIP_DAYS * 24 * 60)
    env["TOKEN_REFRESH_INCLUDE_INVALID"] = "1" if force_all else "0"
    if owner_username:
        env["TOKEN_REFRESH_OWNER"] = str(owner_username).strip()

    logs_dir = MAIL_MODULE_DIR / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    log_path = logs_dir / "mail-token-refresh.log"
    logger.info(
        "Starting mail token refresh | force_all=%s skip_days=%s owner=%s",
        force_all,
        0 if force_all else MAIL_TOKEN_REFRESH_SKIP_DAYS,
        owner_username or "*",
    )
    try:
        with open(log_path, "a", encoding="utf-8") as log_fp:
            log_fp.write(
                f"\n===== {time.strftime('%Y-%m-%d %H:%M:%S')} force_all={force_all} "
                f"skip_days={0 if force_all else MAIL_TOKEN_REFRESH_SKIP_DAYS} =====\n"
            )
            log_fp.flush()
            proc = subprocess.run(
                [node, str(MAIL_TOKEN_REFRESH_SCRIPT)],
                cwd=str(MAIL_MODULE_DIR),
                env=env,
                stdout=log_fp,
                stderr=subprocess.STDOUT,
                timeout=6 * 60 * 60,
                check=False,
                creationflags=subprocess.CREATE_NO_WINDOW if sys.platform.startswith("win") else 0,
            )
        code = int(proc.returncode or 0)
        status = "ok" if code == 0 else "failed"
        msg = f"exit_code={code} log={log_path}"
        with _mail_token_refresh_lock:
            _mail_token_refresh_in_flight = False
            _mail_token_refresh_last.update(
                {
                    "finished_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "status": status,
                    "message": msg,
                    "exit_code": code,
                }
            )
        return {"code": 0 if code == 0 else 1, "msg": msg, "exit_code": code, "log": str(log_path)}
    except Exception as exc:
        with _mail_token_refresh_lock:
            _mail_token_refresh_in_flight = False
            _mail_token_refresh_last.update(
                {
                    "finished_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "status": "failed",
                    "message": str(exc),
                    "exit_code": -1,
                }
            )
        logger.exception("Mail token refresh failed")
        return {"code": 1, "msg": str(exc)}


def _mail_token_refresh_worker_loop() -> None:
    # 启动后稍等，避免与主服务/邮件模块冷启动抢资源
    if _mail_token_refresh_stop.wait(90):
        return
    while not _mail_token_refresh_stop.is_set():
        try:
            if MAIL_TOKEN_REFRESH_ENABLED and ensure_mail_module_ready():
                run_mail_token_refresh_job(force_all=False)
        except Exception as exc:
            logger.warning("Mail token refresh tick failed: %s", exc)
        if _mail_token_refresh_stop.wait(MAIL_TOKEN_REFRESH_TICK_SEC):
            break


def start_mail_token_refresh_worker() -> None:
    global _mail_token_refresh_thread
    if not MAIL_TOKEN_REFRESH_ENABLED:
        logger.info("Mail token refresh worker disabled (MAIL_TOKEN_REFRESH_ENABLED=0)")
        return
    if _mail_token_refresh_thread and _mail_token_refresh_thread.is_alive():
        return
    _mail_token_refresh_stop.clear()
    _mail_token_refresh_thread = threading.Thread(
        target=_mail_token_refresh_worker_loop,
        name="mail_token_refresh",
        daemon=True,
    )
    _mail_token_refresh_thread.start()
    logger.info(
        "Mail token refresh worker started | tick=%ss skip_days=%s",
        MAIL_TOKEN_REFRESH_TICK_SEC,
        MAIL_TOKEN_REFRESH_SKIP_DAYS,
    )


def _resolve_mail_owner(request: Request) -> tuple[str | None, JSONResponse | None]:
    if MAIL_SESSION_RESOLVER is not None:
        try:
            resolved = MAIL_SESSION_RESOLVER(request)
            if isinstance(resolved, tuple):
                username, error = resolved
                if error is not None:
                    return None, error
                if username:
                    return str(username), None
        except Exception as exc:
            logger.warning("Custom mail session resolver failed: %s", exc)

    try:
        import main as app_main
    except ImportError:
        return None, JSONResponse(status_code=503, content={"code": 1, "msg": "主服务未加载，无法校验登录态"})

    authorization = request.headers.get("authorization", "")
    try:
        session = app_main._current_session({"authorization": authorization})
    except RuntimeError as exc:
        if str(exc).startswith("DB_BUSY:"):
            return None, JSONResponse(status_code=503, content={"code": 1, "msg": "数据库繁忙，请稍后重试"})
        raise

    if not session:
        return None, JSONResponse(status_code=401, content={"code": 401, "error": "请先登录主系统后再使用邮箱管理模块"})
    return str(session.get("username") or "").strip() or None, None


async def _proxy_to_mail_module(request: Request) -> Response:
    if _http_client is None:
        return JSONResponse(status_code=503, content={"code": "503", "error": "Mail module HTTP client not initialized"})

    path = request.url.path
    if path not in MAIL_API_PUBLIC_PATHS:
        owner, auth_error = _resolve_mail_owner(request)
        if auth_error is not None:
            return auth_error
        if not owner:
            return JSONResponse(status_code=401, content={"code": 401, "error": "请先登录主系统后再使用邮箱管理模块"})
    else:
        owner = ""

    upstream = f"{_mail_upstream_base()}{path}"
    if request.url.query:
        upstream = f"{upstream}?{request.url.query}"

    headers = {
        key: value
        for key, value in request.headers.items()
        if key.lower() not in {"host", "content-length", "connection"}
    }
    if owner:
        headers["X-Mail-Owner"] = owner
        headers["X-Mail-Internal-Secret"] = MAIL_INTERNAL_SECRET

    body = await request.body()
    try:
        upstream_resp = await _http_client.request(
            request.method,
            upstream,
            headers=headers,
            content=body if body else None,
        )
    except httpx.RequestError as exc:
        logger.warning("Mail module proxy failed: %s %s -> %s", request.method, request.url.path, exc)
        return JSONResponse(
            status_code=503,
            content={"code": "503", "error": f"Mail module unavailable: {exc}"},
        )

    excluded_headers = {"content-encoding", "content-length", "transfer-encoding", "connection"}
    response_headers = {
        key: value
        for key, value in upstream_resp.headers.items()
        if key.lower() not in excluded_headers
    }
    return Response(
        content=upstream_resp.content,
        status_code=upstream_resp.status_code,
        headers=response_headers,
        media_type=upstream_resp.headers.get("content-type"),
    )


def _mail_admin_index() -> Path:
    return MAIL_WEB_DIST / "index.html"


class MailApiProxyMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        if _is_mail_api_path(request.url.path):
            return await _proxy_to_mail_module(request)
        return await call_next(request)


def attach_mail_module(app) -> None:
    """将邮箱管理模块挂接到 FastAPI 应用，对 main.py 的侵入最小。"""
    global _http_client

    if not MAIL_MODULE_ENABLED:
        logger.info("Mail module integration disabled (MAIL_MODULE_ENABLED=0)")
        return

    if not MAIL_MODULE_DIR.exists():
        logger.warning("Mail module directory missing: %s", MAIL_MODULE_DIR)
        return

    _http_client = httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=10.0))
    app.add_middleware(MailApiProxyMiddleware)

    router = APIRouter(tags=["mail-module"])

    @router.get("/api/mail-module/status")
    async def mail_module_status() -> dict[str, Any]:
        running = _node_process is not None and _node_process.poll() is None
        ready = _mail_module_ready()
        return {
            "code": 0,
            "enabled": True,
            "ready": ready,
            "process_running": running,
            "upstream": _mail_upstream_base(),
            "web_admin": "/mail-admin/",
            "token_refresh": get_mail_token_refresh_status(),
        }

    @router.post("/api/mail-module/restart")
    async def mail_module_restart() -> dict[str, Any]:
        _stop_node_process()
        ok = await asyncio.to_thread(_start_node_process)
        return {"code": 0 if ok else 1, "ready": ok, "upstream": _mail_upstream_base()}

    @router.get("/api/mail-module/token_refresh/status")
    async def mail_token_refresh_status() -> dict[str, Any]:
        return {"code": 0, "data": get_mail_token_refresh_status()}

    @router.post("/api/mail-module/token_refresh/run")
    async def mail_token_refresh_run(request: Request) -> dict[str, Any]:
        """手动触发令牌刷新（后台跑，立即返回）。body: { force_all?: bool, owner_username?: str }"""
        try:
            body = await request.json()
        except Exception:
            body = {}
        if not isinstance(body, dict):
            body = {}
        force_all = bool(body.get("force_all") or body.get("forceAll"))
        owner = str(body.get("owner_username") or body.get("ownerUsername") or "").strip()
        status = get_mail_token_refresh_status()
        if status.get("in_flight"):
            return {"code": 1, "msg": "令牌刷新任务正在运行", "data": status}

        def _bg() -> None:
            run_mail_token_refresh_job(force_all=force_all, owner_username=owner)

        threading.Thread(target=_bg, name="mail_token_refresh_manual", daemon=True).start()
        return {
            "code": 0,
            "msg": "已启动令牌刷新任务",
            "data": {
                "force_all": force_all,
                "owner_username": owner,
                "skip_days": 0 if force_all else MAIL_TOKEN_REFRESH_SKIP_DAYS,
            },
        }

    app.include_router(router)

    if MAIL_WEB_DIST.exists():
        assets_dir = MAIL_WEB_DIST / "assets"
        if assets_dir.exists():
            app.mount("/mail-admin/assets", StaticFiles(directory=str(assets_dir)), name="mail-admin-assets")

        favicon = MAIL_WEB_DIST / "favicon.png"
        if favicon.exists():

            @app.get("/mail-admin/favicon.png")
            async def mail_admin_favicon() -> FileResponse:
                return FileResponse(favicon)

        @app.get("/mail-admin")
        @app.get("/mail-admin/")
        @app.get("/mail-admin/{page}")
        async def mail_admin_spa(page: str = "") -> Response:
            allowed = {
                "",
                "email",
                "token-mailboxes",
                "sender-groups",
                "template-manager",
                "project-api",
                "project-mailboxes",
                "tk-pending",
                "api-docs",
            }
            if page and page not in allowed:
                return Response(status_code=404, content="Not Found")
            index = _mail_admin_index()
            if not index.exists():
                return JSONResponse(status_code=503, content={"code": 1, "msg": "Mail admin UI not built"})
            return FileResponse(index, media_type="text/html")
    else:
        logger.warning("Mail admin dist not found: %s", MAIL_WEB_DIST)

    @app.on_event("startup")
    async def _mail_module_startup() -> None:
        ok = await asyncio.to_thread(_start_node_process)
        if not ok:
            logger.warning("Mail module did not start; API proxy will return 503 until ready")
        start_mail_token_refresh_worker()

    @app.on_event("shutdown")
    async def _mail_module_shutdown() -> None:
        stop_mail_module_process()
        if _http_client is not None:
            try:
                await asyncio.wait_for(_http_client.aclose(), timeout=2.0)
            except asyncio.TimeoutError:
                logger.warning("Mail module HTTP client close timed out during shutdown")
            except Exception as exc:
                logger.warning("Mail module HTTP client close failed during shutdown: %s", exc)
