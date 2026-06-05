"""验证删除账号记录时 row_index 重排不会触发主键冲突。"""
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


def _delete_row(conn: sqlite3.Connection, owner: str, row_index: int) -> None:
    conn.execute(
        "DELETE FROM account_editor_rows WHERE owner_username = ? AND row_index = ?",
        (owner, row_index),
    )
    m._shift_account_editor_row_indices_after_delete(conn, owner, row_index)


def test_delete_middle_row_reindexes_safely() -> None:
    conn = _setup_conn()
    owner = "admin"
    _insert_rows(conn, owner, ["u0", "u1", "u2", "u3", "u4"])
    _delete_row(conn, owner, 1)
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
        (2, "u3"),
        (3, "u4"),
    ]


def test_delete_first_row_reindexes_safely() -> None:
    conn = _setup_conn()
    owner = "admin"
    _insert_rows(conn, owner, ["u0", "u1", "u2"])
    _delete_row(conn, owner, 0)
    rows = conn.execute(
        """
        SELECT row_index, username FROM account_editor_rows
        WHERE owner_username = ?
        ORDER BY row_index ASC
        """,
        (owner,),
    ).fetchall()
    assert [(int(r["row_index"]), r["username"]) for r in rows] == [(0, "u1"), (1, "u2")]


def test_delete_last_row_keeps_indices() -> None:
    conn = _setup_conn()
    owner = "admin"
    _insert_rows(conn, owner, ["u0", "u1", "u2"])
    _delete_row(conn, owner, 2)
    rows = conn.execute(
        """
        SELECT row_index, username FROM account_editor_rows
        WHERE owner_username = ?
        ORDER BY row_index ASC
        """,
        (owner,),
    ).fetchall()
    assert [(int(r["row_index"]), r["username"]) for r in rows] == [(0, "u0"), (1, "u1")]
