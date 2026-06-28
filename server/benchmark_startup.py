"""本地启动耗时诊断：python benchmark_startup.py"""
from __future__ import annotations

import time


def ms(t0: float) -> float:
    return (time.perf_counter() - t0) * 1000


def main() -> None:
    rows: list[tuple[str, float]] = []

    t = time.perf_counter()
    import requests  # noqa: F401

    rows.append(("requests", ms(t)))

    t = time.perf_counter()
    from fastapi import FastAPI  # noqa: F401

    rows.append(("fastapi", ms(t)))

    t = time.perf_counter()
    try:
        from openpyxl import Workbook  # noqa: F401
    except Exception:
        pass
    rows.append(("openpyxl", ms(t)))

    t = time.perf_counter()
    import main  # noqa: F401

    rows.append(("import main (full module)", ms(t)))

    print("=== auto_traces startup benchmark ===")
    for name, elapsed in rows:
        print(f"{name:28s} {elapsed:8.0f} ms")
    print(f"{'TOTAL':28s} {sum(x for _, x in rows):8.0f} ms")
    print()
    print("若 import main > 5000ms 或 VPS 上 systemctl 启动慢，属正常（17000 行 + SQLite init）。")
    print("登录 48900ms 慢写 = _get_db 8s busy_timeout × 6 次重试；已改用 _auth_db_conn(3s) 登录专用连接。")


if __name__ == "__main__":
    main()
