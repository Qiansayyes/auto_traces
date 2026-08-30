#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
待注册 TK 邮箱领取工具

功能：
1. 调用 POST /api/tk-pending/accounts/take 从待注册池取邮箱并转入邮箱账户管理
2. 将取出的账号追加写入指定 xlsx（路径自动记忆）
3. 一键生成 xlsx 模板

依赖：pip install requests openpyxl
运行：python scripts/tk_pending_take_tool.py
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import tkinter as tk
from datetime import datetime
from pathlib import Path
from tkinter import filedialog, messagebox, scrolledtext, ttk
from typing import Any

try:
    import requests
except ImportError:
    raise SystemExit("缺少 requests，请先执行：pip install requests")

try:
    from openpyxl import Workbook, load_workbook
except ImportError:
    raise SystemExit("缺少 openpyxl，请先执行：pip install openpyxl")


CONFIG_PATH = Path(__file__).with_name("tk_pending_take_tool_config.json")
XLSX_HEADERS = [
    "TK用户名",
    "TK密码",
    "邮箱",
    "邮箱密码",
    "Client ID",
    "Refresh Token",
    "注册状态",
    "环境名称",
    "更新时间",
]
REGISTER_STATUS_PENDING = "未注册"
DEFAULT_API_BASE = "http://127.0.0.1:8000"
AUTO_UPLOAD_DIR = Path(r"D:\autoGroup\auto_upload")

# 复用 auto_upload 注册模块的用户名/密码生成逻辑
if AUTO_UPLOAD_DIR.is_dir() and str(AUTO_UPLOAD_DIR) not in sys.path:
    sys.path.insert(0, str(AUTO_UPLOAD_DIR))
try:
    from account_register_utils import (  # type: ignore
        generate_tiktok_password,
        generate_tiktok_username,
    )
except ImportError:
    import random
    import string

    def generate_tiktok_username() -> str:
        first = random.choice(["emma", "olivia", "sophia", "mia", "ava"])
        last = random.choice(["smith", "johnson", "brown", "davis", "wilson"])
        digits = "".join(random.choices(string.digits, k=3))
        return f"{first}.{last}{digits}"

    def generate_tiktok_password() -> str:
        letters = [random.choice(string.ascii_lowercase) for _ in range(6)]
        parts = letters + [random.choice(string.digits), random.choice("!@#$%")]
        random.shuffle(parts)
        return "".join(parts)


def load_config() -> dict[str, Any]:
    if not CONFIG_PATH.exists():
        return {}
    try:
        return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_config(data: dict[str, Any]) -> None:
    CONFIG_PATH.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def normalize_base_url(value: str) -> str:
    text = str(value or "").strip().rstrip("/")
    return text or DEFAULT_API_BASE


def normalize_auth_token(value: str) -> str:
    text = str(value or "").strip()
    if text.lower().startswith("bearer "):
        text = text[7:].strip()
    return text


def format_request_error(exc: Exception, base_url: str) -> str:
    msg = str(exc)
    if "10061" in msg or "Connection refused" in msg or "积极拒绝" in msg:
        return (
            f"无法连接 {base_url}。"
            "请把 API Base URL 改成浏览器里实际访问的地址（如 http://43.165.168.45），"
            "不要填 127.0.0.1:8000，除非本机正在运行后端服务。"
        )
    return f"网络错误：{exc}"


def extract_account(payload: dict[str, Any]) -> dict[str, str] | None:
    data = payload.get("data")
    if not isinstance(data, dict):
        return None
    if not data.get("taken") and not data.get("promoted"):
        return None
    account = data.get("account") if isinstance(data.get("account"), dict) else {}
    email = str(account.get("email") or data.get("email") or "").strip()
    if not email:
        return None
    return {
        "email": email,
        "password": str(account.get("password") or "").strip(),
        "client_id": str(account.get("client_id") or "").strip(),
        "refresh_token": str(account.get("refresh_token") or "").strip(),
    }


def _normalize_header(value: Any) -> str:
    return str(value or "").strip().lower()


def _is_header_row(values: tuple[Any, ...]) -> bool:
    if not values:
        return False
    first = _normalize_header(values[0])
    if first in {"tk用户名", "username", "邮箱", "email"}:
        return True
    joined = " ".join(_normalize_header(v) for v in values[:4])
    return "tk用户名" in joined or "username" in joined or first == "邮箱"


def _row_to_account(values: tuple[Any, ...]) -> dict[str, str] | None:
    cells = [str(v or "").strip() for v in values]
    while len(cells) < 9:
        cells.append("")

    # 新格式：TK用户名 | TK密码 | 邮箱 | ...
    if "@" in cells[2]:
        email = cells[2]
        return {
            "tk_username": cells[0] or generate_tiktok_username(),
            "tk_password": cells[1] or generate_tiktok_password(),
            "email": email,
            "password": cells[3],
            "client_id": cells[4],
            "refresh_token": cells[5],
            "register_status": cells[6] or REGISTER_STATUS_PENDING,
            "env_name": cells[7],
            "updated_at": cells[8],
        }

    # 旧格式：邮箱 | 密码 | client_id | token
    if "@" in cells[0]:
        return {
            "tk_username": generate_tiktok_username(),
            "tk_password": generate_tiktok_password(),
            "email": cells[0],
            "password": cells[1],
            "client_id": cells[2],
            "refresh_token": cells[3],
            "register_status": REGISTER_STATUS_PENDING,
            "env_name": "",
            "updated_at": "",
        }
    return None


def _account_row_values(account: dict[str, str]) -> list[str]:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return [
        account.get("tk_username") or generate_tiktok_username(),
        account.get("tk_password") or generate_tiktok_password(),
        account.get("email", ""),
        account.get("password", ""),
        account.get("client_id", ""),
        account.get("refresh_token", ""),
        account.get("register_status") or REGISTER_STATUS_PENDING,
        account.get("env_name", ""),
        account.get("updated_at") or now,
    ]


def _read_workbook_accounts(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    wb = load_workbook(path, read_only=True, data_only=True)
    accounts: list[dict[str, str]] = []
    try:
        ws = wb.active
        for row in ws.iter_rows(values_only=True):
            if not row or not any(row):
                continue
            if _is_header_row(row):
                continue
            first = str(row[0] or "").strip().lower()
            if first.startswith("example@"):
                continue
            account = _row_to_account(row)
            if account and "@" in account["email"]:
                accounts.append(account)
    finally:
        wb.close()
    return accounts


def read_existing_emails(path: Path) -> set[str]:
    return {a["email"].lower() for a in _read_workbook_accounts(path) if a.get("email")}


def _write_workbook(path: Path, accounts: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    wb = Workbook()
    ws = wb.active
    ws.title = "接码邮箱"
    ws.append(XLSX_HEADERS)
    for account in accounts:
        ws.append(_account_row_values(account))
    wb.save(path)


def append_account_to_xlsx(path: Path, account: dict[str, str]) -> None:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    row_account = {
        "tk_username": generate_tiktok_username(),
        "tk_password": generate_tiktok_password(),
        "email": account["email"],
        "password": account["password"],
        "client_id": account["client_id"],
        "refresh_token": account["refresh_token"],
        "register_status": REGISTER_STATUS_PENDING,
        "env_name": "",
        "updated_at": now,
    }

    if path.exists():
        accounts = _read_workbook_accounts(path)
        accounts.append(row_account)
        _write_workbook(path, accounts)
    else:
        _write_workbook(path, [row_account])


def create_template_xlsx(path: Path) -> None:
    _write_workbook(path, [])


def open_file_with_default_app(path: Path) -> None:
    target = str(path.resolve())
    if sys.platform.startswith("win"):
        os.startfile(target)  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        subprocess.run(["open", target], check=True)
    else:
        subprocess.run(["xdg-open", target], check=True)


class TkPendingTakeApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("待注册 TK 邮箱领取工具")
        self.root.geometry("860x680")
        self.root.minsize(760, 560)

        cfg = load_config()
        self.api_base_var = tk.StringVar(value=cfg.get("api_base_url", DEFAULT_API_BASE))
        self.token_var = tk.StringVar(value=cfg.get("auth_token", ""))
        self.password_var = tk.StringVar(value=cfg.get("api_password", ""))
        self.xlsx_var = tk.StringVar(value=cfg.get("xlsx_path", ""))
        self.email_var = tk.StringVar(value="")
        self.batch_count_var = tk.StringVar(value="10")

        self._busy = False
        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

    def _build_ui(self) -> None:
        pad = {"padx": 10, "pady": 4}
        frame = ttk.Frame(self.root, padding=10)
        frame.pack(fill="both", expand=True)

        conn = ttk.LabelFrame(frame, text="连接设置", padding=10)
        conn.pack(fill="x", **pad)

        ttk.Label(conn, text="API Base URL").grid(row=0, column=0, sticky="w")
        ttk.Entry(conn, textvariable=self.api_base_var, width=72).grid(row=0, column=1, columnspan=2, sticky="ew", padx=(8, 0))
        ttk.Label(
            conn,
            text="填浏览器地址栏里的服务器地址，例如 http://43.165.168.45（不要填 127.0.0.1，除非本机正在跑服务）",
            foreground="#666",
        ).grid(row=1, column=1, columnspan=2, sticky="w", padx=(8, 0))

        ttk.Label(conn, text="登录 Token").grid(row=2, column=0, sticky="w", pady=(8, 0))
        ttk.Entry(conn, textvariable=self.token_var, width=72, show="*").grid(row=2, column=1, columnspan=2, sticky="ew", padx=(8, 0), pady=(8, 0))
        ttk.Label(conn, text="Network → Headers → Authorization: Bearer 后面的整串 token", foreground="#666").grid(
            row=3, column=1, columnspan=2, sticky="w", padx=(8, 0)
        )

        ttk.Label(conn, text="接口密钥").grid(row=4, column=0, sticky="w", pady=(8, 0))
        ttk.Entry(conn, textvariable=self.password_var, width=72, show="*").grid(row=4, column=1, columnspan=2, sticky="ew", padx=(8, 0), pady=(8, 0))
        ttk.Label(conn, text="对应邮箱模块「接口密钥」，可选", foreground="#666").grid(
            row=5, column=1, columnspan=2, sticky="w", padx=(8, 0)
        )
        conn.columnconfigure(1, weight=1)

        file_box = ttk.LabelFrame(frame, text="输出表格", padding=10)
        file_box.pack(fill="x", **pad)

        ttk.Label(file_box, text="XLSX 路径").grid(row=0, column=0, sticky="w")
        ttk.Entry(file_box, textvariable=self.xlsx_var, width=58).grid(row=0, column=1, sticky="ew", padx=(8, 8))
        btn_col = ttk.Frame(file_box)
        btn_col.grid(row=0, column=2, rowspan=2, sticky="n")
        ttk.Button(btn_col, text="浏览...", command=self.choose_xlsx).pack(fill="x")
        ttk.Button(btn_col, text="打开表格", command=self.open_xlsx).pack(fill="x", pady=(8, 0))
        ttk.Button(btn_col, text="生成模板表格", command=self.generate_template).pack(fill="x", pady=(8, 0))
        file_box.columnconfigure(1, weight=1)

        action = ttk.LabelFrame(frame, text="领取操作", padding=10)
        action.pack(fill="x", **pad)

        ttk.Label(action, text="指定邮箱（可选）").grid(row=0, column=0, sticky="w")
        ttk.Entry(action, textvariable=self.email_var, width=36).grid(row=0, column=1, sticky="w", padx=(8, 8))
        ttk.Label(action, text="留空则按导入时间取最早一条", foreground="#666").grid(row=0, column=2, sticky="w")

        btn_row = ttk.Frame(action)
        btn_row.grid(row=1, column=0, columnspan=3, sticky="w", pady=(10, 0))

        self.take_one_btn = ttk.Button(btn_row, text="取出一个邮箱", command=self.take_one)
        self.take_one_btn.pack(side="left")

        ttk.Label(btn_row, text="连续取出").pack(side="left", padx=(16, 4))
        ttk.Entry(btn_row, textvariable=self.batch_count_var, width=6).pack(side="left")
        ttk.Label(btn_row, text="个").pack(side="left", padx=(4, 8))
        self.take_batch_btn = ttk.Button(btn_row, text="开始批量", command=self.take_batch)
        self.take_batch_btn.pack(side="left")

        self.take_all_btn = ttk.Button(btn_row, text="取出全部待注册", command=self.take_all)
        self.take_all_btn.pack(side="left", padx=(12, 0))

        ttk.Button(btn_row, text="保存配置", command=self.persist_config).pack(side="right")

        log_box = ttk.LabelFrame(frame, text="运行日志", padding=10)
        log_box.pack(fill="both", expand=True, **pad)
        self.log_text = scrolledtext.ScrolledText(log_box, height=18, wrap="word", state="disabled")
        self.log_text.pack(fill="both", expand=True)

    def log(self, message: str) -> None:
        ts = datetime.now().strftime("%H:%M:%S")
        self.log_text.configure(state="normal")
        self.log_text.insert("end", f"[{ts}] {message}\n")
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    def set_busy(self, busy: bool) -> None:
        self._busy = busy
        state = "disabled" if busy else "normal"
        for btn in (self.take_one_btn, self.take_batch_btn, self.take_all_btn):
            btn.configure(state=state)

    def persist_config(self) -> None:
        save_config({
            "api_base_url": normalize_base_url(self.api_base_var.get()),
            "auth_token": self.token_var.get().strip(),
            "api_password": self.password_var.get().strip(),
            "xlsx_path": self.xlsx_var.get().strip(),
        })
        self.log("配置已保存")

    def on_close(self) -> None:
        self.persist_config()
        self.root.destroy()

    def choose_xlsx(self) -> None:
        initial = self.xlsx_var.get().strip()
        initial_dir = str(Path(initial).parent) if initial else str(Path.home() / "Desktop")
        path = filedialog.asksaveasfilename(
            title="选择或新建 XLSX 文件",
            defaultextension=".xlsx",
            filetypes=[("Excel 文件", "*.xlsx")],
            initialdir=initial_dir,
            initialfile=Path(initial).name if initial else "tk_pending_accounts.xlsx",
        )
        if path:
            self.xlsx_var.set(path)
            self.persist_config()

    def _validate_before_run(self) -> Path | None:
        xlsx = self.xlsx_var.get().strip()
        if not xlsx:
            messagebox.showwarning("提示", "请先选择或生成 XLSX 表格路径")
            return None
        token = self.token_var.get().strip()
        if not token:
            messagebox.showwarning("提示", "请先填写登录 Token（管理台登录后在浏览器开发者工具中复制）")
            return None
        return Path(xlsx)

    def generate_template(self) -> None:
        initial = self.xlsx_var.get().strip()
        initial_dir = str(Path(initial).parent) if initial else str(Path.home() / "Desktop")
        path = filedialog.asksaveasfilename(
            title="生成模板表格",
            defaultextension=".xlsx",
            filetypes=[("Excel 文件", "*.xlsx")],
            initialdir=initial_dir,
            initialfile="tk_pending_accounts_template.xlsx",
        )
        if not path:
            return
        try:
            create_template_xlsx(Path(path))
            self.xlsx_var.set(path)
            self.persist_config()
            self.log(f"模板已生成：{path}")
            messagebox.showinfo("完成", f"模板已生成：\n{path}")
        except Exception as exc:
            messagebox.showerror("失败", str(exc))

    def open_xlsx(self) -> None:
        xlsx = self.xlsx_var.get().strip()
        if not xlsx:
            messagebox.showwarning("提示", "请先选择 XLSX 表格路径")
            return
        path = Path(xlsx)
        if not path.exists():
            messagebox.showwarning("提示", f"文件不存在：\n{path}")
            return
        try:
            open_file_with_default_app(path)
            self.log(f"已打开表格：{path}")
        except Exception as exc:
            messagebox.showerror("打开失败", str(exc))
            self.log(f"打开表格失败：{exc}")

    def take_one(self) -> None:
        if self._busy:
            return
        xlsx_path = self._validate_before_run()
        if not xlsx_path:
            return
        self.set_busy(True)
        threading.Thread(target=self._take_worker, args=(xlsx_path, 1), daemon=True).start()

    def take_batch(self) -> None:
        if self._busy:
            return
        xlsx_path = self._validate_before_run()
        if not xlsx_path:
            return
        try:
            count = max(1, int(self.batch_count_var.get().strip() or "1"))
        except ValueError:
            messagebox.showwarning("提示", "批量数量必须是整数")
            return
        self.set_busy(True)
        threading.Thread(target=self._take_worker, args=(xlsx_path, count), daemon=True).start()

    def take_all(self) -> None:
        if self._busy:
            return
        xlsx_path = self._validate_before_run()
        if not xlsx_path:
            return
        if not messagebox.askyesno("确认", "将连续取出全部待注册邮箱，直到池为空。是否继续？"):
            return
        self.set_busy(True)
        threading.Thread(target=self._take_worker, args=(xlsx_path, None), daemon=True).start()

    def _take_worker(self, xlsx_path: Path, count: int | None) -> None:
        try:
            taken = 0
            skipped_dup = 0
            attempts = 0
            existing = read_existing_emails(xlsx_path)
            max_attempts = 10_000 if count is None else max(count * 3, count)

            while attempts < max_attempts:
                if count is not None and taken >= count:
                    break

                ok, account, msg = self._call_take_api()
                attempts += 1
                if not ok:
                    if taken == 0 and skipped_dup == 0:
                        self.root.after(0, lambda m=msg: self.log(f"领取失败：{m}"))
                    else:
                        self.root.after(0, lambda m=msg: self.log(f"停止：{m}"))
                    break

                email_key = account["email"].lower()
                if email_key in existing:
                    skipped_dup += 1
                    self.root.after(0, lambda e=account["email"]: self.log(f"表格中已存在，跳过写入：{e}"))
                    continue

                append_account_to_xlsx(xlsx_path, account)
                existing.add(email_key)
                taken += 1
                self.root.after(
                    0,
                    lambda e=account["email"]: self.log(f"已领取并写入：{e}"),
                )

            summary = f"完成：成功写入 {taken} 条"
            if skipped_dup:
                summary += f"，跳过重复 {skipped_dup} 条"
            self.root.after(0, lambda s=summary: self.log(s))
        except Exception as exc:
            self.root.after(0, lambda e=str(exc): self.log(f"异常：{e}"))
        finally:
            self.root.after(0, lambda: self.set_busy(False))

    def _call_take_api(self) -> tuple[bool, dict[str, str], str]:
        base = normalize_base_url(self.api_base_var.get())
        url = f"{base}/api/tk-pending/accounts/take"
        token = normalize_auth_token(self.token_var.get())
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        }
        body: dict[str, str] = {}
        api_password = self.password_var.get().strip()
        if api_password:
            body["password"] = api_password
        email = self.email_var.get().strip()
        if email:
            body["email"] = email

        try:
            resp = requests.post(url, headers=headers, json=body, timeout=30)
        except requests.RequestException as exc:
            return False, {}, format_request_error(exc, base)

        try:
            payload = resp.json()
        except ValueError:
            return False, {}, f"响应不是 JSON（HTTP {resp.status_code}）"

        if resp.status_code == 401:
            return False, {}, "未授权：请检查登录 Token 是否有效"
        if resp.status_code >= 400:
            err = payload.get("error") or payload.get("msg") or f"HTTP {resp.status_code}"
            return False, {}, str(err)

        code = payload.get("code")
        if code not in (200, "200"):
            err = payload.get("error") or payload.get("msg") or payload.get("message") or "请求失败"
            data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
            reason = data.get("reason")
            if reason:
                err = f"{err} ({reason})"
            return False, {}, str(err)

        account = extract_account(payload)
        if not account:
            data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
            reason = data.get("reason") or "无可用待注册邮箱"
            reason_map = {
                "pool_empty": "待注册池已空",
                "email_not_available": "指定邮箱不可用或不存在",
                "race_conflict": "并发冲突，请重试",
            }
            return False, {}, reason_map.get(str(reason), str(reason))

        return True, account, "ok"


def main() -> None:
    root = tk.Tk()
    try:
        style = ttk.Style()
        if "vista" in style.theme_names():
            style.theme_use("vista")
    except Exception:
        pass
    TkPendingTakeApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
