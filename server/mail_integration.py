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


def mail_module_can_fetch_code(owner_username: str, email: str) -> bool:
    """邮箱管理模块是否已配置该邮箱且令牌齐全。"""
    if not is_mail_module_ready():
        return False
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
    _stop_node_process()


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
        }

    @router.post("/api/mail-module/restart")
    async def mail_module_restart() -> dict[str, Any]:
        _stop_node_process()
        ok = await asyncio.to_thread(_start_node_process)
        return {"code": 0 if ok else 1, "ready": ok, "upstream": _mail_upstream_base()}

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
