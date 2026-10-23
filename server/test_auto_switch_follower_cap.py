"""自动切号：每个别名桶最多 5 个账号按粉丝量参加。"""
from __future__ import annotations

import importlib.util
import os
import sqlite3
import sys


def _load_main():
    server_dir = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(server_dir, "main.py")
    spec = importlib.util.spec_from_file_location("auto_traces_main_follower_cap", path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["auto_traces_main_follower_cap"] = mod
    spec.loader.exec_module(mod)
    return mod


m = _load_main()


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
            follower_count TEXT NOT NULL DEFAULT '',
            auto_switch_excluded INTEGER NOT NULL DEFAULT 0,
            auto_switch_exclude_reason TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    return conn


def _patch_get_db(conn: sqlite3.Connection):
    original = m._get_db

    class _ConnWrapper:
        def __init__(self, inner: sqlite3.Connection):
            self._inner = inner

        def __getattr__(self, name: str):
            return getattr(self._inner, name)

        def close(self) -> None:
            return None

    def _fake_get_db():
        return _ConnWrapper(conn)

    m._get_db = _fake_get_db
    return original


def test_follower_cap_keeps_top5_and_excludes_rest() -> None:
    conn = _setup_conn()
    original = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    now = m.now_text()
    try:
        # 7 个账号：粉丝 700..100；另有一个手动排除的高粉账号
        for i, followers in enumerate([700, 600, 500, 400, 300, 200, 100]):
            conn.execute(
                """
                INSERT INTO device_editor_rows
                    (owner_username, row_index, device_alias, username, follower_count,
                     auto_switch_excluded, auto_switch_exclude_reason, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, '', ?)
                """,
                (owner, i, alias, f"user_{i}", str(followers), now),
            )
        # row 1（粉丝 600）手动不参加
        conn.execute(
            """
            UPDATE device_editor_rows
            SET auto_switch_excluded = 1, auto_switch_exclude_reason = ?
            WHERE owner_username = ? AND row_index = 1
            """,
            (m.AUTO_SWITCH_EXCLUDE_REASON_MANUAL, owner),
        )
        conn.commit()

        result = m._reconcile_follower_cap_auto_switch_exclusions(owner, alias)
        assert int(result["updated_rows"]) >= 2
        assert alias in result["capped_aliases"]

        rows = conn.execute(
            """
            SELECT row_index, username, follower_count, auto_switch_excluded, auto_switch_exclude_reason
            FROM device_editor_rows
            WHERE owner_username = ?
            ORDER BY row_index ASC
            """,
            (owner,),
        ).fetchall()
        by_ri = {int(r["row_index"]): r for r in rows}

        # 前 5（0..4）中：0,2,3,4 参加；1 保持 manual
        assert int(by_ri[0]["auto_switch_excluded"] or 0) == 0
        assert int(by_ri[1]["auto_switch_excluded"] or 0) == 1
        assert by_ri[1]["auto_switch_exclude_reason"] == m.AUTO_SWITCH_EXCLUDE_REASON_MANUAL
        assert int(by_ri[2]["auto_switch_excluded"] or 0) == 0
        assert int(by_ri[3]["auto_switch_excluded"] or 0) == 0
        assert int(by_ri[4]["auto_switch_excluded"] or 0) == 0
        # 5、6 未进前 5 → follower_cap
        assert int(by_ri[5]["auto_switch_excluded"] or 0) == 1
        assert by_ri[5]["auto_switch_exclude_reason"] == m.AUTO_SWITCH_EXCLUDE_REASON_FOLLOWER_CAP
        assert int(by_ri[6]["auto_switch_excluded"] or 0) == 1
        assert by_ri[6]["auto_switch_exclude_reason"] == m.AUTO_SWITCH_EXCLUDE_REASON_FOLLOWER_CAP

        # 粉丝上涨后：user_6 进前 5，应自动恢复；原第 5 名被挤出
        conn.execute(
            "UPDATE device_editor_rows SET follower_count = '800' WHERE owner_username = ? AND row_index = 6",
            (owner,),
        )
        conn.commit()
        m._reconcile_follower_cap_auto_switch_exclusions(owner, alias)
        rows2 = {
            int(r["row_index"]): r
            for r in conn.execute(
                "SELECT row_index, auto_switch_excluded, auto_switch_exclude_reason FROM device_editor_rows"
            ).fetchall()
        }
        assert int(rows2[6]["auto_switch_excluded"] or 0) == 0
        assert int(rows2[4]["auto_switch_excluded"] or 0) == 1
        assert rows2[4]["auto_switch_exclude_reason"] == m.AUTO_SWITCH_EXCLUDE_REASON_FOLLOWER_CAP
    finally:
        m._get_db = original


def test_follower_cap_noop_when_under_limit() -> None:
    conn = _setup_conn()
    original = _patch_get_db(conn)
    owner = "admin"
    now = m.now_text()
    try:
        for i in range(3):
            conn.execute(
                """
                INSERT INTO device_editor_rows
                    (owner_username, row_index, device_alias, username, follower_count,
                     auto_switch_excluded, auto_switch_exclude_reason, updated_at)
                VALUES (?, ?, 'phone-b', ?, ?, 0, '', ?)
                """,
                (owner, i, f"u{i}", str((i + 1) * 10), now),
            )
        conn.commit()
        result = m._reconcile_follower_cap_auto_switch_exclusions(owner, "phone-b")
        assert int(result["updated_rows"]) == 0
        assert result["capped_aliases"] == []
        participating = conn.execute(
            "SELECT COUNT(*) FROM device_editor_rows WHERE auto_switch_excluded = 0"
        ).fetchone()[0]
        assert participating == 3
    finally:
        m._get_db = original


if __name__ == "__main__":
    test_follower_cap_keeps_top5_and_excludes_rest()
    test_follower_cap_noop_when_under_limit()
    print("ok")
