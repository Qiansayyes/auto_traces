"""过期 auto_switch enter/exit ACK 不得覆盖管理台当前开关状态。"""
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


def _seed_device(device_id: str, enabled: bool) -> None:
    m.store.devices[device_id] = m.DeviceInfo(
        alias="S6",
        fingerprint="fp",
        owner_username="admin",
        auto_switch_enabled=enabled,
    )


def test_stale_exit_ack_does_not_disable_reenabled_device() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_s6"
    try:
        _seed_device(device_id, enabled=True)
        conn.execute(
            """
            INSERT INTO device_mode_events
                (device_id, owner_username, event_type, session_id, bundle_revision, payload_json, created_at)
            VALUES (?, 'admin', 'auto_switch_exit', 'as_off_old', 0, '{}', ?)
            """,
            (device_id, m.now_text()),
        )
        conn.commit()
        exit_id = int(conn.execute("SELECT id FROM device_mode_events").fetchone()[0])

        ok = m._ack_device_mode_event(device_id, exit_id, "summary_uploaded", "as_off_old")
        assert ok is True
        assert m.store.devices[device_id].auto_switch_enabled is True
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()


def test_stale_enter_ack_does_not_enable_disabled_device() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_s6"
    try:
        _seed_device(device_id, enabled=False)
        conn.execute(
            """
            INSERT INTO device_mode_events
                (device_id, owner_username, event_type, session_id, bundle_revision, payload_json, created_at)
            VALUES (?, 'admin', 'auto_switch_enter', 'as_on_old', 1, '{}', ?)
            """,
            (device_id, m.now_text()),
        )
        conn.commit()
        enter_id = int(conn.execute("SELECT id FROM device_mode_events").fetchone()[0])

        ok = m._ack_device_mode_event(device_id, enter_id, "bundle_loaded", "as_on_old")
        assert ok is True
        assert m.store.devices[device_id].auto_switch_enabled is False
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()


def test_hydrate_device_preserves_auto_switch_enabled() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_s6"
    now = m.now_text()
    try:
        conn.execute(
            """
            INSERT INTO client_devices
                (device_id, owner_username, alias, device_group, fingerprint, status,
                 pending_start, paused, auto_start_opt_out, auto_switch_enabled, created_at, updated_at)
            VALUES (?, 'admin', 'S6', '', 'fp', 'online', 0, 0, 0, 1, ?, ?)
            """,
            (device_id, now, now),
        )
        conn.commit()
        m.store.devices[device_id] = m.DeviceInfo(
            alias="S6",
            fingerprint="fp",
            owner_username="admin",
            auto_switch_enabled=True,
        )
        m._hydrate_device_from_db(device_id)
        assert m.store.devices[device_id].auto_switch_enabled is True
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()


def test_enable_supersedes_pending_exit_events() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_s6"
    try:
        _seed_device(device_id, enabled=False)
        conn.execute(
            """
            INSERT INTO device_mode_events
                (device_id, owner_username, event_type, session_id, bundle_revision, payload_json, created_at)
            VALUES (?, 'admin', 'auto_switch_exit', 'as_off_old', 0, '{}', ?)
            """,
            (device_id, m.now_text()),
        )
        conn.commit()

        count = m._supersede_pending_device_mode_events(device_id, ("auto_switch_exit",))
        assert count == 1
        pending = conn.execute(
            "SELECT COUNT(*) FROM device_mode_events WHERE acked_at IS NULL"
        ).fetchone()[0]
        assert pending == 0
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()
