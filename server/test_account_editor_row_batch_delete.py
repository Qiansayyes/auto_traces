"""验证批量删除账号记录时 row_index 重排不会触发主键冲突。"""
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
        CREATE TABLE account_editor_rows (
            owner_username TEXT NOT NULL,
            row_index INTEGER NOT NULL,
            username TEXT NOT NULL DEFAULT '',
            password TEXT NOT NULL DEFAULT '',
            email TEXT NOT NULL DEFAULT '',
            email_password TEXT NOT NULL DEFAULT '',
            api TEXT NOT NULL DEFAULT '',
            is_login INTEGER NOT NULL DEFAULT 0,
            login_time TEXT NOT NULL DEFAULT '',
            region TEXT NOT NULL DEFAULT '',
            assigned_alias TEXT NOT NULL DEFAULT '',
            video_count TEXT NOT NULL DEFAULT '',
            follower_count TEXT NOT NULL DEFAULT '',
            follower_growth TEXT NOT NULL DEFAULT '',
            basic_profile_modified TEXT NOT NULL DEFAULT '否',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE device_editor_rows (
            owner_username TEXT NOT NULL,
            row_index INTEGER NOT NULL,
            username TEXT NOT NULL DEFAULT '',
            device_alias TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    return conn


def _insert_rows(conn: sqlite3.Connection, owner: str, usernames: list[str]) -> None:
    now = m.now_text()
    for idx, username in enumerate(usernames):
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, updated_at)
            VALUES (?, ?, ?, ?)
            """,
            (owner, idx, username, now),
        )


def test_batch_delete_multiple_rows_reindexes_safely() -> None:
    conn = _setup_conn()
    owner = "admin"
    _insert_rows(conn, owner, ["u0", "u1", "u2", "u3", "u4"])
    for row_index in sorted([1, 3], reverse=True):
        result = m._delete_one_account_editor_row_in_conn(conn, owner, row_index, "")
        assert result["found"] is True
        assert result["deleted_accounts"] == 1
    rows = conn.execute(
        """
        SELECT row_index, username FROM account_editor_rows
        WHERE owner_username = ?
        ORDER BY row_index ASC
        """,
        (owner,),
    ).fetchall()
    assert [(int(r["row_index"]), r["username"]) for r in rows] == [
        (0, "u0"),
        (1, "u2"),
        (2, "u4"),
    ]


def test_batch_delete_cascades_device_rows() -> None:
    conn = _setup_conn()
    owner = "admin"
    _insert_rows(conn, owner, ["u0", "u1"])
    conn.execute(
        "INSERT INTO device_editor_rows (owner_username, row_index, username, device_alias) VALUES (?, ?, ?, ?)",
        (owner, 0, "u1", "mx1"),
    )
    result = m._delete_one_account_editor_row_in_conn(conn, owner, 1, "u1")
    assert result["deleted_device_rows"] == 1
    remaining = conn.execute(
        "SELECT username FROM device_editor_rows WHERE owner_username = ?",
        (owner,),
    ).fetchall()
    assert len(remaining) == 0
