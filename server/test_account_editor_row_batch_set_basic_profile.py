"""验证批量设置账号记录 basic_profile_modified（资料）。"""
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
                (owner_username, row_index, username, basic_profile_modified, updated_at)
            VALUES (?, ?, ?, '否', ?)
            """,
            (owner, idx, username, now),
        )


def _batch_set_in_conn(
    conn: sqlite3.Connection,
    owner: str,
    row_indices: list[int],
    value: str,
) -> tuple[int, int, int]:
    target_value = m._normalize_switch_highlight(value)
    updated_count = 0
    not_found_count = 0
    unchanged_count = 0
    now = m.now_text()
    for row_index in row_indices:
        existing = conn.execute(
            """
            SELECT basic_profile_modified
            FROM account_editor_rows
            WHERE owner_username = ? AND row_index = ?
            LIMIT 1
            """,
            (owner, row_index),
        ).fetchone()
        if not existing:
            not_found_count += 1
            continue
        current_value = m._normalize_switch_highlight(existing["basic_profile_modified"])
        if current_value == target_value:
            unchanged_count += 1
            continue
        cur = conn.execute(
            """
            UPDATE account_editor_rows
            SET basic_profile_modified = ?, updated_at = ?
            WHERE owner_username = ? AND row_index = ?
            """,
            (target_value, now, owner, row_index),
        )
        if int(cur.rowcount or 0) > 0:
            updated_count += 1
    return updated_count, not_found_count, unchanged_count


def test_batch_set_basic_profile_updates_selected_rows() -> None:
    conn = _setup_conn()
    owner = "admin"
    _insert_rows(conn, owner, ["u0", "u1", "u2"])
    updated, not_found, unchanged = _batch_set_in_conn(conn, owner, [0, 2], "是")
    assert updated == 2
    assert not_found == 0
    assert unchanged == 0
    rows = conn.execute(
        """
        SELECT row_index, basic_profile_modified FROM account_editor_rows
        WHERE owner_username = ?
        ORDER BY row_index ASC
        """,
        (owner,),
    ).fetchall()
    assert [(int(r["row_index"]), r["basic_profile_modified"]) for r in rows] == [
        (0, "是"),
        (1, "否"),
        (2, "是"),
    ]


def test_batch_set_basic_profile_skips_unchanged_rows() -> None:
    conn = _setup_conn()
    owner = "admin"
    _insert_rows(conn, owner, ["u0", "u1"])
    conn.execute(
        """
        UPDATE account_editor_rows
        SET basic_profile_modified = '是'
        WHERE owner_username = ? AND row_index = 0
        """,
        (owner,),
    )
    updated, not_found, unchanged = _batch_set_in_conn(conn, owner, [0, 1], "是")
    assert updated == 1
    assert not_found == 0
    assert unchanged == 1


def test_batch_set_basic_profile_unchanged_with_not_found_is_not_total_failure() -> None:
    conn = _setup_conn()
    owner = "admin"
    _insert_rows(conn, owner, ["u0"])
    conn.execute(
        """
        UPDATE account_editor_rows
        SET basic_profile_modified = '是'
        WHERE owner_username = ? AND row_index = 0
        """,
        (owner,),
    )
    updated, not_found, unchanged = _batch_set_in_conn(conn, owner, [0, 99], "是")
    assert updated == 0
    assert not_found == 1
    assert unchanged == 1
