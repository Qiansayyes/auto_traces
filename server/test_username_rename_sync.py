"""验证账号改名时 account_editor_rows 与 device_editor_rows 同步不丢数据。"""
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
            device_alias TEXT NOT NULL DEFAULT '',
            device_id TEXT NOT NULL DEFAULT '',
            ip TEXT NOT NULL DEFAULT '',
            region TEXT NOT NULL DEFAULT '',
            username TEXT NOT NULL DEFAULT '',
            assigned_alias TEXT NOT NULL DEFAULT '',
            backup_path TEXT NOT NULL DEFAULT '',
            backup_name TEXT NOT NULL DEFAULT '',
            backup_time TEXT NOT NULL DEFAULT '',
            backup_status TEXT NOT NULL DEFAULT '',
            remark TEXT NOT NULL DEFAULT '',
            switch_highlight TEXT NOT NULL DEFAULT '否',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    return conn


def test_rename_preserves_device_backup_fields() -> None:
    conn = _setup_conn()
    owner = "admin"
    conn.execute(
        """
        INSERT INTO account_editor_rows
            (owner_username, row_index, username, assigned_alias, updated_at)
        VALUES (?, 0, 'old_user', '', ?)
        """,
        (owner, m.now_text()),
    )
    conn.execute(
        """
        INSERT INTO device_editor_rows
            (owner_username, row_index, device_alias, device_id, username, assigned_alias,
             backup_path, backup_name, backup_time, backup_status, remark, switch_highlight, updated_at)
        VALUES (?, 0, 'phone-a', 'dev-1', 'old_user', '', '/data/bk', 'snap.zip', '2026-01-01', 'ok', 'note', '是', ?)
        """,
        (owner, m.now_text()),
    )
    conn.commit()

    alias = m._recover_assigned_alias_for_account_username(conn, owner, "old_user")
    assert alias == "phone-a", f"expected recovered alias phone-a, got {alias!r}"

    touched = m._sync_device_editor_username_for_account(
        conn, owner, "old_user", "new_user", alias
    )
    assert touched == 1, f"expected 1 device row updated, got {touched}"
    conn.commit()

    dev = conn.execute(
        "SELECT username, backup_path, backup_name, remark, switch_highlight FROM device_editor_rows WHERE owner_username = ?",
        (owner,),
    ).fetchone()
    assert dev["username"] == "new_user"
    assert dev["backup_path"] == "/data/bk"
    assert dev["backup_name"] == "snap.zip"
    assert dev["remark"] == "note"
    assert dev["switch_highlight"] == "是"

    # 与 upsert 一致：先改账号表，再 prune（同一次提交后）
    conn.execute(
        "UPDATE account_editor_rows SET username = 'new_user' WHERE owner_username = ?",
        (owner,),
    )
    conn.commit()
    pruned = m._prune_orphan_device_editor_rows(conn, owner)
    assert pruned == 0, "两张表 username 一致时不应被孤儿清理删掉"


def test_sync_account_editor_stats_on_writeback() -> None:
    conn = _setup_conn()
    owner = "admin"
    conn.execute(
        """
        INSERT INTO account_editor_rows
            (owner_username, row_index, username, video_count, follower_count, follower_growth, updated_at)
        VALUES (?, 0, 'v3we32', '', '', '', ?)
        """,
        (owner, m.now_text()),
    )
    conn.commit()
    touched = m._sync_account_editor_stats(
        conn,
        "v3we32",
        owner_username=owner,
        video_count="2",
        follower_count="65",
        follower_growth="0",
    )
    assert touched == 1
    row = conn.execute(
        "SELECT video_count, follower_count, follower_growth FROM account_editor_rows WHERE username = ?",
        ("v3we32",),
    ).fetchone()
    assert row["video_count"] == "2"
    assert row["follower_count"] == "65"
    assert row["follower_growth"] == "0"


def test_rename_fallback_when_alias_mismatch() -> None:
    conn = _setup_conn()
    owner = "admin"
    conn.execute(
        """
        INSERT INTO account_editor_rows
            (owner_username, row_index, username, assigned_alias, updated_at)
        VALUES (?, 0, 'u1', 'alias-wrong', ?)
        """,
        (owner, m.now_text()),
    )
    conn.execute(
        """
        INSERT INTO device_editor_rows
            (owner_username, row_index, device_alias, device_id, username, assigned_alias, updated_at)
        VALUES (?, 0, 'alias-real', 'dev-1', 'u1', '', ?)
        """,
        (owner, m.now_text()),
    )
    conn.commit()

    touched = m._sync_device_editor_username_for_account(
        conn, owner, "u1", "u2", "alias-wrong"
    )
    assert touched == 1, "fallback should still rename device row by old username"
    dev = conn.execute(
        "SELECT username FROM device_editor_rows WHERE owner_username = ?",
        (owner,),
    ).fetchone()
    assert dev["username"] == "u2"


if __name__ == "__main__":
    test_rename_preserves_device_backup_fields()
    test_sync_account_editor_stats_on_writeback()
    test_rename_fallback_when_alias_mismatch()
    print("ok: username rename sync tests passed")
