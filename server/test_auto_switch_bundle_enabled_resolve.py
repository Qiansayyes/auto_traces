"""bundle 拉取：enter session 已写入 DB 时，内存开关滞后仍应视为已开启。"""
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import main as m  # noqa: E402


def _setup_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(
        """
        CREATE TABLE auto_switch_sessions (
            session_id TEXT PRIMARY KEY,
            enabled INTEGER NOT NULL DEFAULT 0,
            bundle_revision INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL,
            closed_at TEXT
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
        CREATE TABLE client_devices (
            device_id TEXT PRIMARY KEY,
            owner_username TEXT NOT NULL,
            alias TEXT NOT NULL,
            device_group TEXT NOT NULL DEFAULT '',
            fingerprint TEXT NOT NULL,
            status TEXT NOT NULL,
            pending_start INTEGER NOT NULL DEFAULT 0,
            paused INTEGER NOT NULL DEFAULT 0,
            auto_start_opt_out INTEGER NOT NULL DEFAULT 0,
            auto_switch_enabled INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
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


def test_bundle_enabled_resolves_from_active_enter_session() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_s6"
    session_id = "as_on_20260704020230_test"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO client_devices
                (device_id, owner_username, alias, device_group, fingerprint, status,
                 pending_start, paused, auto_start_opt_out, auto_switch_enabled, created_at, updated_at)
            VALUES (?, 'admin', 'S6', '', 'fp', 'online', 0, 0, 0, 0, ?, ?)
            """,
            (device_id, now, now),
        )
        conn.execute(
            """
            INSERT INTO auto_switch_sessions (session_id, enabled, bundle_revision, created_at, closed_at)
            VALUES (?, 1, 3, ?, NULL)
            """,
            (session_id, now),
        )
        conn.execute(
            """
            INSERT INTO device_mode_events
                (device_id, owner_username, event_type, session_id, bundle_revision, payload_json, created_at)
            VALUES (?, 'admin', 'auto_switch_enter', ?, 3, '{}', ?)
            """,
            (device_id, session_id, now),
        )
        conn.commit()
        m.store.devices.clear()
        m.store.devices[device_id] = m.DeviceInfo(
            alias="S6",
            fingerprint="fp",
            owner_username="admin",
            auto_switch_enabled=False,
        )
        assert m._device_auto_switch_enabled(device_id) is False
        assert m._device_auto_switch_enabled_resolved(device_id, session_id) is True
        assert m._device_auto_switch_enabled(device_id) is True
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()
