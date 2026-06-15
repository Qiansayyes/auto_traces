"""验证设备档案分配时 assigned_alias 会同步回账号记录。"""
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
            env_status TEXT NOT NULL DEFAULT '',
            video_count TEXT NOT NULL DEFAULT '',
            follower_count TEXT NOT NULL DEFAULT '',
            follower_growth TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    return conn


def test_device_alias_syncs_to_account_on_reconcile() -> None:
    conn = _setup_conn()
    owner = "admin"
    now = m.now_text()
    conn.execute(
        """
        INSERT INTO account_editor_rows
            (owner_username, row_index, username, region, assigned_alias, updated_at)
        VALUES (?, 0, 'sqcd09', 'MX', '', ?)
        """,
        (owner, now),
    )
    conn.execute(
        """
        INSERT INTO device_editor_rows
            (owner_username, row_index, device_alias, username, region, assigned_alias, switch_highlight, updated_at)
        VALUES (?, 0, 'sqcd09', 'sqcd09', 'MX', '', '是', ?)
        """,
        (owner, now),
    )
    conn.commit()

    touched = m._sync_device_aliases_to_account_rows(conn, owner)
    assert touched == 1, f"expected 1 account row synced, got {touched}"
    conn.commit()

    acct = conn.execute(
        "SELECT assigned_alias FROM account_editor_rows WHERE owner_username = ? AND username = ?",
        (owner, "sqcd09"),
    ).fetchone()
    assert acct["assigned_alias"] == "sqcd09"


def test_sync_account_assigned_alias_skips_when_already_set() -> None:
    conn = _setup_conn()
    owner = "admin"
    now = m.now_text()
    conn.execute(
        """
        INSERT INTO account_editor_rows
            (owner_username, row_index, username, assigned_alias, updated_at)
        VALUES (?, 0, 'user1', 'alias-a', ?)
        """,
        (owner, now),
    )
    conn.commit()

    touched = m._sync_account_assigned_alias_for_username(
        conn, owner, "user1", "alias-b", only_if_empty=True
    )
    assert touched == 0
    acct = conn.execute(
        "SELECT assigned_alias FROM account_editor_rows WHERE username = 'user1'"
    ).fetchone()
    assert acct["assigned_alias"] == "alias-a"


def test_reconcile_bidirectional() -> None:
    conn = _setup_conn()
    owner = "admin"
    now = m.now_text()
    conn.execute(
        """
        INSERT INTO account_editor_rows
            (owner_username, row_index, username, assigned_alias, region, updated_at)
        VALUES (?, 0, 'acct_only', 'phone-x', 'US', ?)
        """,
        (owner, now),
    )
    conn.execute(
        """
        INSERT INTO account_editor_rows
            (owner_username, row_index, username, assigned_alias, region, updated_at)
        VALUES (?, 1, 'device_only', '', 'MX', ?)
        """,
        (owner, now),
    )
    conn.execute(
        """
        INSERT INTO device_editor_rows
            (owner_username, row_index, device_alias, username, region, switch_highlight, updated_at)
        VALUES (?, 0, 'phone-y', 'device_only', 'MX', '是', ?)
        """,
        (owner, now),
    )
    conn.commit()

    to_accounts, to_devices = m._reconcile_editor_alias_bindings(conn, owner)
    conn.commit()
    assert to_accounts == 1
    assert to_devices >= 1

    acct = conn.execute(
        "SELECT assigned_alias FROM account_editor_rows WHERE username = 'device_only'"
    ).fetchone()
    assert acct["assigned_alias"] == "phone-y"

    dev = conn.execute(
        """
        SELECT assigned_alias, device_alias
        FROM device_editor_rows
        WHERE username = 'acct_only'
        """
    ).fetchone()
    assert dev["assigned_alias"] == "phone-x" or dev["device_alias"] == "phone-x"


if __name__ == "__main__":
    test_device_alias_syncs_to_account_on_reconcile()
    test_sync_account_assigned_alias_skips_when_already_set()
    test_reconcile_bidirectional()
    print("ok: alias allocation sync tests passed")
