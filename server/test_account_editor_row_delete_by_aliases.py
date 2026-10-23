"""验证按别名批量删除会删掉匹配账号，并级联清理设备备份档案。"""
import sqlite3
import sys
from pathlib import Path
from unittest import mock

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
            two_fa TEXT NOT NULL DEFAULT '',
            api TEXT NOT NULL DEFAULT '',
            is_login INTEGER NOT NULL DEFAULT 0,
            login_time TEXT NOT NULL DEFAULT '',
            region TEXT NOT NULL DEFAULT '',
            assigned_alias TEXT NOT NULL DEFAULT '',
            video_count TEXT NOT NULL DEFAULT '',
            follower_count TEXT NOT NULL DEFAULT '',
            follower_growth TEXT NOT NULL DEFAULT '',
            heart_count TEXT NOT NULL DEFAULT '',
            follower_growth_day_before TEXT NOT NULL DEFAULT '',
            follower_growth_yesterday TEXT NOT NULL DEFAULT '',
            follower_growth_today TEXT NOT NULL DEFAULT '',
            heart_growth_day_before TEXT NOT NULL DEFAULT '',
            heart_growth_yesterday TEXT NOT NULL DEFAULT '',
            heart_growth_today TEXT NOT NULL DEFAULT '',
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
            assigned_alias TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    return conn


def _seed(conn: sqlite3.Connection, owner: str) -> None:
    now = m.now_text()
    rows = [
        (0, "u_keep", "other"),
        (1, "u_a1", "6gmxz8"),
        (2, "u_a2", "6gmxz8"),
        (3, "u_b1", "6gs1"),
        (4, "", "6gs1"),  # 无 username 空行，仍应按别名删
    ]
    for row_index, username, alias in rows:
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, assigned_alias, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (owner, row_index, username, alias, now),
        )
    conn.execute(
        """
        INSERT INTO device_editor_rows
            (owner_username, row_index, username, device_alias, assigned_alias, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (owner, 0, "u_a1", "6gmxz8", "6gmxz8", now),
    )
    conn.commit()


class _KeepAliveConn:
    """包装内存连接：允许业务代码反复 close，而不真正释放。"""

    def __init__(self, conn: sqlite3.Connection):
        self._conn = conn

    def close(self) -> None:
        return None

    def __getattr__(self, name: str):
        return getattr(self._conn, name)


def test_delete_account_editor_rows_by_aliases() -> None:
    raw = _setup_conn()
    owner = "admin"
    _seed(raw, owner)
    wrapped = _KeepAliveConn(raw)

    with mock.patch.object(m, "_get_db", lambda: wrapped), mock.patch.object(
        m, "_ensure_device_editor_switch_highlight_invariants", lambda *_a, **_k: None
    ), mock.patch.object(
        m, "_reset_device_editor_read_maintain_schedule", lambda *_a, **_k: None
    ):
        result = m._delete_account_editor_rows_by_aliases(owner, ["6gmxz8", "6gs1"])

    assert int(result.get("code", 1)) == 0, result
    assert int(result.get("deleted_accounts") or 0) >= 3
    remaining = raw.execute(
        """
        SELECT row_index, username, assigned_alias
        FROM account_editor_rows
        WHERE owner_username = ?
        ORDER BY row_index ASC
        """,
        (owner,),
    ).fetchall()
    assert len(remaining) == 1
    assert remaining[0]["username"] == "u_keep"
    assert remaining[0]["assigned_alias"] == "other"
    # 空行删除后 row_index 重排，保留行应在 index 0
    assert int(remaining[0]["row_index"]) == 0
    device_left = raw.execute(
        "SELECT username FROM device_editor_rows WHERE owner_username = ?",
        (owner,),
    ).fetchall()
    assert len(device_left) == 0
    raw.close()


if __name__ == "__main__":
    test_delete_account_editor_rows_by_aliases()
    print("ok")
