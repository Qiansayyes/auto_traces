"""关闭自动切号时须结束陈旧 enter session，且不得借 resolved() 复活开关。"""
import sqlite3
import sys
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import main as m  # noqa: E402


def _setup_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(
        """
        CREATE TABLE client_devices (
            device_id TEXT PRIMARY KEY,
            owner_username TEXT NOT NULL DEFAULT '',
            alias TEXT NOT NULL DEFAULT '',
            device_group TEXT NOT NULL DEFAULT '',
            fingerprint TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'offline',
            pending_start INTEGER NOT NULL DEFAULT 0,
            paused INTEGER NOT NULL DEFAULT 0,
            auto_start_opt_out INTEGER NOT NULL DEFAULT 0,
            auto_switch_enabled INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE device_mode_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            owner_username TEXT NOT NULL,
            event_type TEXT NOT NULL,
            session_id TEXT NOT NULL,
            bundle_revision INTEGER NOT NULL DEFAULT 0,
            payload_json TEXT NOT NULL DEFAULT '{}',
            created_at TEXT NOT NULL,
            acked_at TEXT,
            ack_phase TEXT
        );
        CREATE TABLE auto_switch_sessions (
            session_id TEXT PRIMARY KEY,
            enabled INTEGER NOT NULL DEFAULT 0,
            bundle_revision INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT '',
            closed_at TEXT
        );
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


def _seed_open_enter_session(
    conn: sqlite3.Connection,
    device_id: str,
    session_id: str,
) -> None:
    now = m.now_text()
    conn.execute(
        """
        INSERT INTO auto_switch_sessions (session_id, enabled, bundle_revision, created_at, closed_at)
        VALUES (?, 1, 2, ?, NULL)
        """,
        (session_id, now),
    )
    conn.execute(
        """
        INSERT INTO device_mode_events
            (device_id, owner_username, event_type, session_id, bundle_revision, payload_json, created_at)
        VALUES (?, 'admin', 'auto_switch_enter', ?, 2, '{}', ?)
        """,
        (device_id, session_id, now),
    )
    conn.commit()


def test_turn_off_closes_active_enter_sessions() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_s6"
    old_session = "as_on_20260704044600_old"
    try:
        _seed_open_enter_session(conn, device_id, old_session)
        m.store.devices[device_id] = m.DeviceInfo(
            alias="S6",
            fingerprint="fp",
            owner_username="admin",
            auto_switch_enabled=True,
        )
        admin_session = {"username": "admin", "device_ids": [device_id]}
        payload = m.AutoSwitchDevicesRequest(device_ids=[device_id], enabled=False)
        with patch.object(m, "_require_login", return_value=(None, admin_session)), patch.object(
            m, "_session_device_ids", return_value={device_id}
        ), patch.object(m, "persist_runtime_state"), patch.object(
            m, "_append_device_log"
        ), patch.object(
            m, "_cancel_pending_auto_switch_device_ops_for_device", return_value=0
        ):
            resp = m.set_auto_switch_devices(payload, authorization="Bearer test")

        assert resp["code"] == 0
        row = conn.execute(
            "SELECT closed_at, enabled FROM auto_switch_sessions WHERE session_id = ?",
            (old_session,),
        ).fetchone()
        assert row is not None
        assert row["closed_at"]
        assert int(row["enabled"]) == 0
        assert m.store.devices[device_id].auto_switch_enabled is False
        assert m._device_auto_switch_enabled_resolved(device_id) is False
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()


def test_pending_exit_blocks_resolved_resurrection() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_s6"
    old_session = "as_on_20260704044600_old"
    try:
        _seed_open_enter_session(conn, device_id, old_session)
        now = m.now_text()
        conn.execute(
            """
            INSERT INTO device_mode_events
                (device_id, owner_username, event_type, session_id, bundle_revision, payload_json, created_at)
            VALUES (?, 'admin', 'auto_switch_exit', 'as_off_new', 0, '{}', ?)
            """,
            (device_id, now),
        )
        conn.commit()
        m.store.devices[device_id] = m.DeviceInfo(
            alias="S6",
            fingerprint="fp",
            owner_username="admin",
            auto_switch_enabled=False,
        )
        assert m._device_auto_switch_enabled_resolved(device_id) is False
        assert m.store.devices[device_id].auto_switch_enabled is False
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()


def test_exit_ack_honored_after_turn_off() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_s6"
    old_session = "as_on_20260704044600_old"
    try:
        _seed_open_enter_session(conn, device_id, old_session)
        m.store.devices[device_id] = m.DeviceInfo(
            alias="S6",
            fingerprint="fp",
            owner_username="admin",
            auto_switch_enabled=True,
        )
        admin_session = {"username": "admin", "device_ids": [device_id]}
        payload = m.AutoSwitchDevicesRequest(device_ids=[device_id], enabled=False)
        with patch.object(m, "_require_login", return_value=(None, admin_session)), patch.object(
            m, "_session_device_ids", return_value={device_id}
        ), patch.object(m, "persist_runtime_state"), patch.object(
            m, "_append_device_log"
        ), patch.object(
            m, "_cancel_pending_auto_switch_device_ops_for_device", return_value=0
        ):
            m.set_auto_switch_devices(payload, authorization="Bearer test")

        exit_id = int(
            conn.execute(
                "SELECT id FROM device_mode_events WHERE event_type = 'auto_switch_exit'"
            ).fetchone()[0]
        )
        ok = m._ack_device_mode_event(device_id, exit_id, "summary_uploaded", "as_off_new")
        assert ok is True
        assert m.store.devices[device_id].auto_switch_enabled is False
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()
