"""切号高亮由当前北京时段计划派生，不再由切号 OP 直接写入。"""
from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import main as m  # noqa: E402


def _setup_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.execute(
        """
        CREATE TABLE device_editor_rows (
            owner_username TEXT NOT NULL,
            row_index INTEGER NOT NULL,
            device_alias TEXT NOT NULL DEFAULT '',
            device_id TEXT NOT NULL DEFAULT '',
            username TEXT NOT NULL DEFAULT '',
            assigned_alias TEXT NOT NULL DEFAULT '',
            backup_path TEXT NOT NULL DEFAULT '',
            backup_name TEXT NOT NULL DEFAULT '',
            switch_highlight TEXT NOT NULL DEFAULT '否',
            env_status TEXT NOT NULL DEFAULT '',
            video_count TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE auto_switch_weekly_plans (
            owner_username TEXT NOT NULL,
            alias TEXT NOT NULL,
            week_start_date TEXT NOT NULL,
            plan_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, alias, week_start_date)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE auto_switch_daily_plans (
            owner_username TEXT NOT NULL,
            alias TEXT NOT NULL,
            schedule_date TEXT NOT NULL,
            plan_json TEXT NOT NULL,
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, alias, schedule_date)
        )
        """
    )
    return conn


def _patch_get_db(conn: sqlite3.Connection):
    original = m._get_db

    class _ConnWrapper:
        __slots__ = ("_conn",)

        def __init__(self, inner: sqlite3.Connection) -> None:
            self._conn = inner

        def __getattr__(self, name: str):
            return getattr(self._conn, name)

        def close(self) -> None:
            return None

    def _fake_get_db():
        return _ConnWrapper(conn)

    m._get_db = _fake_get_db
    return original


def _insert_weekly_plan(
    conn: sqlite3.Connection,
    owner: str,
    alias: str,
    schedule_date: str,
    plan: dict,
    now: str,
) -> None:
    week_start, week_end = m._beijing_week_bounds(schedule_date)
    days = {d: plan for d in m._iter_dates_inclusive(week_start, week_end)}
    sample_rows = [
        {"row_index": 0, "username": "user_a"},
        {"row_index": 1, "username": "user_b"},
    ]
    conn.execute(
        """
        INSERT INTO auto_switch_weekly_plans
            (owner_username, alias, week_start_date, plan_json, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (
            owner,
            alias,
            week_start,
            json.dumps(
                {
                    "week_start_date": week_start,
                    "week_end_date": week_end,
                    "account_count": len(sample_rows),
                    "rows_fingerprint": m._weekly_plan_rows_fingerprint(sample_rows),
                    "days": days,
                }
            ),
            now,
            now,
        ),
    )
    for day_key, day_plan in days.items():
        conn.execute(
            """
            INSERT INTO auto_switch_daily_plans
                (owner_username, alias, schedule_date, plan_json, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (owner, alias, day_key, json.dumps(day_plan), now),
        )


def _two_account_plan() -> dict:
    return {
        "slot_minutes": 720,
        "slots": [
            {
                "slot_index": 0,
                "start_min": 0,
                "end_min": 720,
                "row_index": 0,
                "username": "user_a",
            },
            {
                "slot_index": 1,
                "start_min": 720,
                "end_min": 1440,
                "row_index": 1,
                "username": "user_b",
            },
        ],
    }


def test_resolve_slot_highlight_row_index_morning() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    schedule_date = "2026-06-28"
    now = m.now_text()
    plan = _two_account_plan()
    try:
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, username, switch_highlight, updated_at)
            VALUES (?, 0, ?, 'user_a', '否', ?),
                   (?, 1, ?, 'user_b', '是', ?)
            """,
            (owner, alias, now, owner, alias, now),
        )
        _insert_weekly_plan(conn, owner, alias, schedule_date, plan, now)
        conn.commit()

        ri_am = m._resolve_slot_highlight_row_index(owner, alias, schedule_date, 60)
        ri_pm = m._resolve_slot_highlight_row_index(owner, alias, schedule_date, 800)
        assert ri_am == 0
        assert ri_pm == 1

        m._sync_switch_highlight_from_slot(conn, owner, alias, schedule_date, 60)
        conn.commit()
        row0 = conn.execute(
            "SELECT switch_highlight FROM device_editor_rows WHERE row_index = 0"
        ).fetchone()
        row1 = conn.execute(
            "SELECT switch_highlight FROM device_editor_rows WHERE row_index = 1"
        ).fetchone()
        assert row0["switch_highlight"] == "是"
        assert row1["switch_highlight"] == "否"

        m._sync_switch_highlight_from_slot(conn, owner, alias, schedule_date, 800)
        conn.commit()
        row0 = conn.execute(
            "SELECT switch_highlight FROM device_editor_rows WHERE row_index = 0"
        ).fetchone()
        row1 = conn.execute(
            "SELECT switch_highlight FROM device_editor_rows WHERE row_index = 1"
        ).fetchone()
        assert row0["switch_highlight"] == "否"
        assert row1["switch_highlight"] == "是"
    finally:
        m._get_db = original_get_db


def test_cut_switch_intent_sets_env_pending_keeps_slot_highlight() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    schedule_date = "2026-06-28"
    now = m.now_text()
    plan = _two_account_plan()
    try:
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, username, switch_highlight, updated_at)
            VALUES (?, 0, ?, 'user_a', '是', ?),
                   (?, 1, ?, 'user_b', '否', ?)
            """,
            (owner, alias, now, owner, alias, now),
        )
        _insert_weekly_plan(conn, owner, alias, schedule_date, plan, now)
        conn.commit()

        assert m._apply_cut_switch_highlight_intent(owner, 1) is True
        m._sync_switch_highlight_from_slot(conn, owner, alias, schedule_date, 60)
        conn.commit()
        row0 = conn.execute(
            "SELECT switch_highlight, env_status FROM device_editor_rows WHERE row_index = 0"
        ).fetchone()
        row1 = conn.execute(
            "SELECT switch_highlight, env_status FROM device_editor_rows WHERE row_index = 1"
        ).fetchone()
        assert row0["switch_highlight"] == "是"
        assert row1["switch_highlight"] == "否"
        assert row1["env_status"] == m.ENV_STATUS_PENDING
    finally:
        m._get_db = original_get_db


if __name__ == "__main__":
    test_resolve_slot_highlight_row_index_morning()
    test_cut_switch_intent_sets_env_pending_keeps_slot_highlight()
    print("ok")
