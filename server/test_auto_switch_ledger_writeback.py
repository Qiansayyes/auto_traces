"""验证自动切号会话总结 ledger 回放写回 device_editor_rows。"""
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import main as m  # noqa: E402


def _setup_publish_writeback_table(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS auto_switch_publish_video_writeback (
            owner_username TEXT NOT NULL,
            session_id TEXT NOT NULL,
            dedupe_key TEXT NOT NULL,
            slot_key TEXT NOT NULL DEFAULT '',
            outcome_op_id TEXT NOT NULL DEFAULT '',
            username TEXT NOT NULL DEFAULT '',
            delta INTEGER NOT NULL DEFAULT 1,
            applied_at TEXT NOT NULL,
            PRIMARY KEY (owner_username, session_id, dedupe_key)
        )
        """
    )


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

    def _fake_get_db():
        return _ConnWrapper(conn)

    m._get_db = _fake_get_db
    return original


def test_ledger_writeback_cut_switch_success_updates_highlight_and_backup() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, username, backup_name, backup_path,
                 switch_highlight, env_status, updated_at)
            VALUES (?, 0, ?, 'user_a', '', '', '是', '', ?),
                   (?, 1, ?, 'user_b', 'user_b.tar.gz', '/data/backup/user_b.tar.gz',
                    '否', '', ?)
            """,
            (owner, alias, now, owner, alias, now),
        )
        conn.commit()

        ledger = [
            {
                "op_id": "op-backup-1",
                "op_type": "tiktok_backup_and_clear",
                "status": "success",
                "report_data": {
                    "op_type": "tiktok_backup_and_clear",
                    "backup_record_id": 0,
                    "username": "user_a",
                    "mobile_alias": alias,
                    "backup_path": "/data/backup/user_a_new.tar.gz",
                    "backup_name": "user_a_new.tar.gz",
                    "cut_switch_after_backup": "clear_source_for_restore",
                    "cut_switch_target_row_index": 1,
                },
            },
            {
                "op_id": "op-restore-1",
                "op_type": "tiktok_restore",
                "status": "success",
                "report_data": {
                    "op_type": "tiktok_restore",
                    "backup_name": "user_b.tar.gz",
                    "restore_highlight_row_index": 1,
                },
            },
        ]

        result = m._replay_auto_switch_ledger_to_device_editor_rows(owner, alias, ledger)
        assert result["applied_effects"] >= 2

        row0 = conn.execute(
            "SELECT backup_status, backup_name, switch_highlight FROM device_editor_rows WHERE row_index = 0"
        ).fetchone()
        row1 = conn.execute(
            "SELECT switch_highlight, env_status FROM device_editor_rows WHERE row_index = 1"
        ).fetchone()
        assert row0["backup_status"] == "已备份"
        assert row0["backup_name"] == "user_a_new.tar.gz"
        assert row0["switch_highlight"] == "否"
        assert row1["switch_highlight"] == "是"
        assert row1["env_status"] == m.ENV_STATUS_ACTIVE
    finally:
        m._get_db = original_get_db


def test_ledger_writeback_backup_failure_rolls_back_highlight() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    now = m.now_text()
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
        conn.commit()

        ledger = [
            {
                "op_id": "op-backup-fail",
                "op_type": "tiktok_backup_and_clear",
                "status": "failed",
                "report_data": {
                    "op_type": "tiktok_backup_and_clear",
                    "backup_record_id": 0,
                    "cut_switch_after_backup": "clear_source_for_restore",
                    "cut_switch_target_row_index": 1,
                },
            },
        ]

        m._replay_auto_switch_ledger_to_device_editor_rows(owner, alias, ledger)
        row0 = conn.execute(
            "SELECT switch_highlight FROM device_editor_rows WHERE row_index = 0"
        ).fetchone()
        assert row0["switch_highlight"] == "是"
    finally:
        m._get_db = original_get_db


def test_ledger_writeback_skips_publish_entries() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "phone-a"
    try:
        ledger = [
            {
                "op_id": "op-publish-1",
                "op_type": "publish_post_start",
                "status": "success",
                "phase": "success",
                "slot_key": "2026-06-25|0",
            },
            {
                "op_id": "op-publish-outcome",
                "op_type": "publish_post_outcome",
                "status": "success",
                "phase": "success",
                "slot_key": "2026-06-25|0",
            },
        ]
        result = m._replay_auto_switch_ledger_to_device_editor_rows(owner, alias, ledger)
        assert result["applied_effects"] == 0
        notes = " ".join(result.get("writeback_notes") or [])
        assert "不参与档案写回" in notes or "无可写回" in notes
    finally:
        m._get_db = original_get_db


def test_session_summary_upsert_is_idempotent() -> None:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.execute(
        """
        CREATE TABLE auto_switch_session_summaries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            owner_username TEXT NOT NULL,
            alias TEXT NOT NULL,
            bundle_revision INTEGER NOT NULL DEFAULT 0,
            schedule_date TEXT NOT NULL DEFAULT '',
            summary_json TEXT NOT NULL,
            human_log TEXT NOT NULL,
            summary_kind TEXT NOT NULL DEFAULT 'final',
            reconcile_key TEXT NOT NULL DEFAULT 'final',
            created_at TEXT NOT NULL
        )
        """
    )
    original_get_db = m._get_db

    class _ConnWrapper:
        __slots__ = ("_conn",)

        def __init__(self, inner: sqlite3.Connection) -> None:
            self._conn = inner

        def __getattr__(self, name: str):
            return getattr(self._conn, name)

        def close(self) -> None:
            return None

    m._get_db = lambda: _ConnWrapper(conn)
    try:
        payload = m.AutoSwitchSessionSummaryRequest(
            device_id="dev-1",
            alias="phone-a",
            session_id="as_on_test",
            bundle_revision=3,
            schedule_date="2026-06-25",
            ledger=[],
            human_log="log1",
        )
        recon = {"notes": []}
        id1 = m._save_auto_switch_session_summary("admin", "dev-1", "phone-a", payload, recon)
        id2 = m._save_auto_switch_session_summary("admin", "dev-1", "phone-a", payload, recon)
        assert id1 > 0
        assert id2 == id1
        count = conn.execute("SELECT COUNT(*) FROM auto_switch_session_summaries").fetchone()[0]
        assert count == 1
    finally:
        m._get_db = original_get_db


def test_device_op_report_duplicate_is_detected() -> None:
    oid = "op-dup-test"
    m._save_device_op_result(oid, {"status": "success", "message": "ok"})
    assert m._device_op_report_is_duplicate(oid, "success") is True
    assert m._device_op_report_is_duplicate(oid, "failed") is True
    assert m._device_op_report_is_duplicate("op-new", "success") is False


def test_ledger_stats_writeback_graduate_and_publish() -> None:
    conn = _setup_conn()
    conn.execute(
        """
        CREATE TABLE account_editor_rows (
            owner_username TEXT NOT NULL,
            row_index INTEGER NOT NULL,
            username TEXT NOT NULL DEFAULT '',
            video_count TEXT NOT NULL DEFAULT '',
            follower_count TEXT NOT NULL DEFAULT '',
            follower_growth TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, username, video_count, updated_at)
            VALUES (?, 0, 'S6', 'user_a', ?, ?),
                   (?, 1, 'S6', 'user_b', '2', ?)
            """,
            (owner, str(m.NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN), now, owner, now),
        )
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, video_count, updated_at)
            VALUES (?, 0, 'user_a', ?, ?),
                   (?, 1, 'user_b', '2', ?)
            """,
            (owner, str(m.NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN), now, owner, now),
        )
        conn.commit()

        graduate_ledger = [
            {
                "op_type": "auto_switch_video_count_graduate",
                "phase": "success",
                "username": "user_a",
                "report_data": {
                    "op_type": "auto_switch_video_count_graduate",
                    "username": "user_a",
                    "row_index": 0,
                    "video_count": 0,
                },
            }
        ]
        result = m._apply_auto_switch_ledger_stats_writeback(owner, graduate_ledger, session_id="sess-test")
        assert result["applied"] == 1
        vc = conn.execute(
            "SELECT video_count FROM device_editor_rows WHERE row_index = 0"
        ).fetchone()["video_count"]
        assert vc == "0"

        publish_ledger = [
            {
                "op_type": "publish_post_start",
                "slot_key": "2026-06-27|0",
                "report_data": {
                    "op_type": "publish_post_start",
                    "publish_count": 2,
                    "auto_switch_slot_key": "2026-06-27|0",
                },
            },
            {
                "op_type": "publish_post_outcome",
                "phase": "success",
                "op_id": "local-as-publish-1|outcome",
                "slot_key": "2026-06-27|0",
                "username": "user_b",
                "report_data": {
                    "op_type": "publish_post_outcome",
                    "account_username": "user_b",
                    "publish_post_completed": True,
                },
            },
        ]
        _setup_publish_writeback_table(conn)
        result2 = m._apply_auto_switch_ledger_stats_writeback(
            owner, publish_ledger, session_id="sess-test",
        )
        assert result2["applied"] == 1
        vc2 = conn.execute(
            "SELECT video_count FROM device_editor_rows WHERE username = 'user_b'"
        ).fetchone()["video_count"]
        assert vc2 == "4"
        result3 = m._apply_auto_switch_ledger_stats_writeback(
            owner, publish_ledger, session_id="sess-test",
        )
        assert result3["applied"] == 0
        vc3 = conn.execute(
            "SELECT video_count FROM device_editor_rows WHERE username = 'user_b'"
        ).fetchone()["video_count"]
        assert vc3 == "4"
    finally:
        m._get_db = original_get_db


def test_profile_stats_writeback_fills_minus_one() -> None:
    conn = _setup_conn()
    conn.execute(
        """
        CREATE TABLE account_editor_rows (
            owner_username TEXT NOT NULL,
            row_index INTEGER NOT NULL,
            username TEXT NOT NULL DEFAULT '',
            video_count TEXT NOT NULL DEFAULT '',
            follower_count TEXT NOT NULL DEFAULT '',
            follower_growth TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    now = m.now_text()
    original_fetch = m.fetch_tiktok_profile_stats
    try:
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, username, video_count, follower_count, updated_at)
            VALUES (?, 0, 'S6', 'evelynse153', ?, '100', ?)
            """,
            (owner, str(m.NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN), now),
        )
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, video_count, follower_count, updated_at)
            VALUES (?, 0, 'evelynse153', ?, '100', ?)
            """,
            (owner, str(m.NEW_ACCOUNT_VIDEO_COUNT_COOLDOWN), now),
        )
        conn.commit()

        def _fake_fetch(username: str, write_back: bool = False, **kwargs):
            assert username == "evelynse153"
            assert write_back is False
            return {
                "code": 0,
                "universal_extract": {"video_count": 3, "follower_count": 120},
            }

        m.fetch_tiktok_profile_stats = _fake_fetch
        ledger = [
            {
                "op_type": "tiktok_restore",
                "phase": "success",
                "username": "evelynse153",
                "report_data": {
                    "op_type": "tiktok_restore",
                    "username": "evelynse153",
                },
            }
        ]
        result = m._apply_auto_switch_session_profile_stats_writeback(owner, ledger)
        assert result["applied"] == 1
        row = conn.execute(
            "SELECT video_count, follower_count FROM device_editor_rows WHERE username = 'evelynse153'"
        ).fetchone()
        assert row["video_count"] == "3"
        assert row["follower_count"] == "120"
    finally:
        m.fetch_tiktok_profile_stats = original_fetch
        m._get_db = original_get_db


def test_daily_reconcile_publish_increment_idempotent() -> None:
    conn = _setup_conn()
    _setup_publish_writeback_table(conn)
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    now = m.now_text()
    session_id = "sess-daily"
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS account_editor_rows (
                owner_username TEXT NOT NULL,
                row_index INTEGER NOT NULL,
                username TEXT NOT NULL DEFAULT '',
                video_count TEXT NOT NULL DEFAULT '',
                follower_count TEXT NOT NULL DEFAULT '',
                follower_growth TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (owner_username, row_index)
            )
            """
        )
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, username, video_count, updated_at)
            VALUES (?, 0, 'S6', 'user_b', '2', ?)
            """,
            (owner, now),
        )
        conn.commit()
        publish_ledger = [
            {
                "op_type": "publish_post_start",
                "slot_key": "2026-06-27|0",
                "report_data": {
                    "op_type": "publish_post_start",
                    "publish_count": 2,
                    "auto_switch_slot_key": "2026-06-27|0",
                },
            },
            {
                "op_type": "publish_post_outcome",
                "phase": "success",
                "op_id": "local-as-publish-daily|outcome",
                "slot_key": "2026-06-27|0",
                "username": "user_b",
                "report_data": {
                    "op_type": "publish_post_outcome",
                    "account_username": "user_b",
                    "publish_post_completed": True,
                },
            },
        ]
        wb1 = m._apply_auto_switch_ledger_writeback_bundle(
            owner, "S6", publish_ledger, "daily", session_id,
        )
        vc1 = conn.execute(
            "SELECT video_count FROM device_editor_rows WHERE username = 'user_b'"
        ).fetchone()["video_count"]
        assert vc1 == "4"
        assert wb1["stats_writeback"]["applied"] == 1
        wb2 = m._apply_auto_switch_ledger_writeback_bundle(
            owner, "S6", publish_ledger, "daily", session_id,
        )
        vc2 = conn.execute(
            "SELECT video_count FROM device_editor_rows WHERE username = 'user_b'"
        ).fetchone()["video_count"]
        assert vc2 == "4"
        assert wb2["stats_writeback"]["applied"] == 0
        wb3 = m._apply_auto_switch_ledger_writeback_bundle(
            owner, "S6", publish_ledger, "final", session_id,
        )
        vc3 = conn.execute(
            "SELECT video_count FROM device_editor_rows WHERE username = 'user_b'"
        ).fetchone()["video_count"]
        assert vc3 == "4"
        assert wb3["stats_writeback"]["applied"] == 0
    finally:
        m._get_db = original_get_db


def test_publish_writeback_uses_actual_success_count() -> None:
    """计划发 2 条但 outcome 只上报实际成功 1 条时，video_count 只 +1。"""
    conn = _setup_conn()
    _setup_publish_writeback_table(conn)
    conn.execute(
        """
        CREATE TABLE account_editor_rows (
            owner_username TEXT NOT NULL,
            row_index INTEGER NOT NULL,
            username TEXT NOT NULL DEFAULT '',
            video_count TEXT NOT NULL DEFAULT '',
            follower_count TEXT NOT NULL DEFAULT '',
            follower_growth TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, username, video_count, updated_at)
            VALUES (?, 0, 'S6', 'user_c', '5', ?)
            """,
            (owner, now),
        )
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, video_count, updated_at)
            VALUES (?, 0, 'user_c', '5', ?)
            """,
            (owner, now),
        )
        conn.commit()
        ledger = [
            {
                "op_type": "publish_post_start",
                "slot_key": "2026-06-27|2",
                "report_data": {
                    "op_type": "publish_post_start",
                    "publish_count": 2,
                    "auto_switch_slot_key": "2026-06-27|2",
                },
            },
            {
                "op_type": "publish_post_outcome",
                "phase": "success",
                "op_id": "local-as-publish-partial|outcome",
                "slot_key": "2026-06-27|2",
                "username": "user_c",
                "report_data": {
                    "op_type": "publish_post_outcome",
                    "account_username": "user_c",
                    "publish_post_completed": True,
                    "publish_post_success_count": 1,
                },
            },
        ]
        result = m._apply_auto_switch_ledger_stats_writeback(
            owner, ledger, session_id="sess-partial",
        )
        assert result["applied"] == 1
        vc = conn.execute(
            "SELECT video_count FROM device_editor_rows WHERE username = 'user_c'"
        ).fetchone()["video_count"]
        assert vc == "6"
    finally:
        m._get_db = original_get_db


def test_ledger_login_writeback_sets_is_login() -> None:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.execute(
        """
        CREATE TABLE account_editor_rows (
            owner_username TEXT NOT NULL,
            row_index INTEGER NOT NULL,
            username TEXT NOT NULL DEFAULT '',
            is_login INTEGER NOT NULL DEFAULT 0,
            login_time TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, is_login, login_time, updated_at)
            VALUES (?, 0, 'vernahsiung6', 0, '', ?)
            """,
            (owner, now),
        )
        conn.commit()
        ledger = [
            {
                "op_type": "account_mark_login",
                "phase": "success",
                "username": "vernahsiung6",
                "at": "2026-06-27 03:33:22",
                "report_data": {
                    "op_type": "account_mark_login",
                    "username": "vernahsiung6",
                    "login_time": "2026-06-27 03:33:22",
                },
            },
        ]
        result = m._apply_auto_switch_ledger_account_login_writeback(owner, ledger)
        assert result["applied"] == 1
        row = conn.execute(
            "SELECT is_login, login_time FROM account_editor_rows WHERE username = 'vernahsiung6'"
        ).fetchone()
        assert int(row["is_login"]) == 1
        assert str(row["login_time"]).startswith("2026-06-27")
    finally:
        m._get_db = original_get_db


def test_ledger_username_update_writeback() -> None:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.execute(
        """
        CREATE TABLE account_editor_rows (
            owner_username TEXT NOT NULL,
            row_index INTEGER NOT NULL,
            username TEXT NOT NULL DEFAULT '',
            assigned_alias TEXT NOT NULL DEFAULT '',
            is_login INTEGER NOT NULL DEFAULT 0,
            login_time TEXT NOT NULL DEFAULT '',
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
            assigned_alias TEXT NOT NULL DEFAULT '',
            username TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    original_get_db = _patch_get_db(conn)
    owner = "admin"
    alias = "S6"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO account_editor_rows
                (owner_username, row_index, username, assigned_alias, updated_at)
            VALUES (?, 0, 'old_user', ?, ?)
            """,
            (owner, alias, now),
        )
        conn.execute(
            """
            INSERT INTO device_editor_rows
                (owner_username, row_index, device_alias, assigned_alias, username, updated_at)
            VALUES (?, 0, ?, ?, 'old_user', ?)
            """,
            (owner, alias, alias, now),
        )
        conn.commit()
        ledger = [
            {
                "op_type": "account_username_update",
                "phase": "success",
                "report_data": {
                    "op_type": "account_username_update",
                    "old_username": "old_user",
                    "new_username": "new_user",
                },
            },
        ]
        result = m._apply_auto_switch_ledger_username_update_writeback(owner, alias, ledger)
        assert result["applied"] == 1
        acct = conn.execute(
            "SELECT username FROM account_editor_rows WHERE row_index = 0"
        ).fetchone()["username"]
        dev = conn.execute(
            "SELECT username FROM device_editor_rows WHERE row_index = 0"
        ).fetchone()["username"]
        assert acct == "new_user"
        assert dev == "new_user"
    finally:
        m._get_db = original_get_db


if __name__ == "__main__":
    test_ledger_writeback_cut_switch_success_updates_highlight_and_backup()
    test_ledger_writeback_backup_failure_rolls_back_highlight()
    test_ledger_writeback_skips_publish_entries()
    test_ledger_stats_writeback_graduate_and_publish()
    test_profile_stats_writeback_fills_minus_one()
    test_daily_reconcile_publish_increment_idempotent()
    test_publish_writeback_uses_actual_success_count()
    test_session_summary_upsert_is_idempotent()
    test_device_op_report_duplicate_is_detected()
    test_ledger_login_writeback_sets_is_login()
    test_ledger_username_update_writeback()
    print("ok")
