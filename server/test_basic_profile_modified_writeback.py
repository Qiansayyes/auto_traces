"""验证改资料成功回写 basic_profile_modified 仅更新目标 username，不误伤同别名其它账号。"""
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

    m._get_db = lambda: _ConnWrapper(conn)
    return original


def _insert_same_alias_accounts(conn: sqlite3.Connection, owner: str, alias: str, now: str) -> None:
    conn.execute(
        """
        INSERT INTO account_editor_rows
            (owner_username, row_index, username, assigned_alias, basic_profile_modified, updated_at)
        VALUES (?, 0, 'user_a', ?, '否', ?),
               (?, 1, 'user_b', ?, '否', ?)
        """,
        (owner, alias, now, owner, alias, now),
    )
    conn.execute(
        """
        INSERT INTO device_editor_rows
            (owner_username, row_index, device_alias, username, switch_highlight, updated_at)
        VALUES (?, 0, ?, 'user_a', '是', ?),
               (?, 1, ?, 'user_b', '否', ?)
        """,
        (owner, alias, now, owner, alias, now),
    )
    conn.commit()


def _basic_flag(conn: sqlite3.Connection, owner: str, username: str) -> str:
    row = conn.execute(
        """
        SELECT basic_profile_modified FROM account_editor_rows
        WHERE owner_username = ? AND LOWER(TRIM(username)) = LOWER(?)
        """,
        (owner, username),
    ).fetchone()
    return str(row["basic_profile_modified"] if row else "")


def test_profile_edit_device_op_report_updates_only_target_username() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    now = m.now_text()
    try:
        _insert_same_alias_accounts(conn, owner, alias, now)
        data = {
            "op_type": "tiktok_edit_basic_profile",
            "profile_edit_success": True,
            "profile_edit_bio_after_part1_ok": True,
            "profile_edit_bio_after_part2_ok": True,
            "username": "user_a",
            "account_username": "user_a",
        }
        result = m._apply_profile_edit_device_op_report(owner, alias, data)
        assert result["updated"] == 1
        assert result["username"] == "user_a"
        assert _basic_flag(conn, owner, "user_a") == "是"
        assert _basic_flag(conn, owner, "user_b") == "否"
    finally:
        m._get_db = original_get_db


def test_profile_edit_report_username_fallback_switch_highlight() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    now = m.now_text()
    try:
        _insert_same_alias_accounts(conn, owner, alias, now)
        data = {
            "op_type": "tiktok_edit_basic_profile",
            "profile_edit_success": True,
            "profile_edit_bio_after_part1_ok": True,
            "profile_edit_bio_after_part2_ok": True,
        }
        resolved = m._profile_edit_report_username(data, owner, alias)
        assert resolved == "user_a"
        result = m._apply_profile_edit_device_op_report(owner, alias, data)
        assert result["updated"] == 1
        assert _basic_flag(conn, owner, "user_b") == "否"
    finally:
        m._get_db = original_get_db


def test_profile_edit_writeback_after_username_renamed_in_remote_mode() -> None:
    """Bio 步骤已将 DB username 改为 new_user，回传含 row_index + old/new，仍应写回正确行。"""
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, assigned_alias, basic_profile_modified, updated_at)
            VALUES (?, 5, 'new_user', ?, '否', ?)
            """,
            (owner, alias, now),
        )
        conn.commit()
        data = {
            "op_type": "tiktok_edit_basic_profile",
            "profile_edit_success": True,
            "profile_edit_bio_after_part1_ok": True,
            "profile_edit_bio_after_part2_ok": True,
            "username": "new_user",
            "account_username": "new_user",
            "profile_edit_assigned_username": "old_user",
            "old_username": "old_user",
            "new_username": "new_user",
            "account_row_index": 5,
        }
        result = m._apply_profile_edit_device_op_report(owner, alias, data)
        assert result["updated"] == 1
        assert _basic_flag(conn, owner, "new_user") == "是"
    finally:
        m._get_db = original_get_db


def test_profile_edit_writeback_falls_back_to_assigned_username() -> None:
    """DB 仍为旧用户名（自治对账前），回传 new_username + profile_edit_assigned_username 可命中。"""
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, assigned_alias, basic_profile_modified, updated_at)
            VALUES (?, 2, 'old_user', ?, '否', ?)
            """,
            (owner, alias, now),
        )
        conn.commit()
        data = {
            "op_type": "tiktok_edit_basic_profile",
            "profile_edit_success": True,
            "profile_edit_bio_after_part1_ok": True,
            "profile_edit_bio_after_part2_ok": True,
            "username": "new_user",
            "profile_edit_assigned_username": "old_user",
            "old_username": "old_user",
            "new_username": "new_user",
        }
        result = m._apply_profile_edit_device_op_report(owner, alias, data)
        assert result["updated"] == 1
        assert _basic_flag(conn, owner, "old_user") == "是"
    finally:
        m._get_db = original_get_db


def test_profile_edit_ledger_identity_keys_include_old_and_new() -> None:
    ledger = [
        {
            "op_type": "tiktok_edit_basic_profile",
            "phase": "success",
            "username": "new_user",
            "report_data": {
                "profile_edit_success": True,
                "profile_edit_bio_after_part1_ok": True,
                "profile_edit_bio_after_part2_ok": True,
                "profile_edit_assigned_username": "old_user",
                "old_username": "old_user",
                "new_username": "new_user",
            },
        }
    ]
    done = m._profile_edit_success_usernames_from_ledger(ledger)
    assert "old_user" in done
    assert "new_user" in done
    pending = {"old_user"}
    assert not (pending - done)


def test_profile_edit_writeback_by_row_index_zero() -> None:
    """account_row_index=0 合法，应能写回。"""
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, assigned_alias, basic_profile_modified, updated_at)
            VALUES (?, 0, 'user_zero', ?, '否', ?)
            """,
            (owner, alias, now),
        )
        conn.commit()
        data = {
            "op_type": "tiktok_edit_basic_profile",
            "profile_edit_success": True,
            "profile_edit_bio_after_part1_ok": True,
            "profile_edit_bio_after_part2_ok": True,
            "username": "user_zero",
            "profile_edit_assigned_username": "user_zero",
            "account_row_index": 0,
        }
        result = m._apply_profile_edit_device_op_report(owner, alias, data)
        assert result["updated"] == 1
        assert _basic_flag(conn, owner, "user_zero") == "是"
    finally:
        m._get_db = original_get_db


def test_profile_edit_device_op_report_skips_when_parts_incomplete() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    now = m.now_text()
    try:
        _insert_same_alias_accounts(conn, owner, alias, now)
        data = {
            "op_type": "tiktok_edit_basic_profile",
            "profile_edit_success": True,
            "profile_edit_bio_after_part1_ok": True,
            "profile_edit_bio_after_part2_ok": False,
            "username": "user_a",
        }
        result = m._apply_profile_edit_device_op_report(owner, alias, data)
        assert result["updated"] == 0
        assert _basic_flag(conn, owner, "user_a") == "否"
        assert _basic_flag(conn, owner, "user_b") == "否"
    finally:
        m._get_db = original_get_db
