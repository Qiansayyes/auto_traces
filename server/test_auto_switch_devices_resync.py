"""已开启自动切号的设备再次点「开启」仍须推送 enter 事件（资料重同步）。"""
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


def _seed_device(device_id: str, *, enabled: bool) -> None:
    m.store.devices[device_id] = m.DeviceInfo(
        alias="S6",
        fingerprint="fp",
        owner_username="admin",
        auto_switch_enabled=enabled,
    )


def test_reenable_already_enabled_device_still_notifies() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_s6"
    m.store.auto_switch_bundle_revision = 3
    try:
        _seed_device(device_id, enabled=True)
        admin_session = {"username": "admin", "device_ids": [device_id]}
        payload = m.AutoSwitchDevicesRequest(device_ids=[device_id], enabled=True)
        with patch.object(m, "_require_login", return_value=(None, admin_session)), patch.object(
            m, "_session_device_ids", return_value={device_id}
        ), patch.object(m, "persist_runtime_state"), patch.object(
            m, "_append_device_log"
        ):
            resp = m.set_auto_switch_devices(payload, authorization="Bearer test")

        assert resp["code"] == 0
        data = resp["data"]
        assert data["devices_changed"] == 0
        assert data["devices_notified"] == 1
        assert data["devices_resynced"] == 1
        assert data["bundle_revision"] == 4

        row = conn.execute(
            "SELECT event_type, bundle_revision FROM device_mode_events WHERE device_id = ?",
            (device_id,),
        ).fetchone()
        assert row is not None
        assert row["event_type"] == "auto_switch_enter"
        assert int(row["bundle_revision"]) == 4
        assert m.store.devices[device_id].auto_switch_enabled is True
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()
        m.store.auto_switch_bundle_revision = 0


def test_first_enable_still_counts_as_changed_not_resynced() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_br25"
    try:
        _seed_device(device_id, enabled=False)
        admin_session = {"username": "admin", "device_ids": [device_id]}
        payload = m.AutoSwitchDevicesRequest(device_ids=[device_id], enabled=True)
        with patch.object(m, "_require_login", return_value=(None, admin_session)), patch.object(
            m, "_session_device_ids", return_value={device_id}
        ), patch.object(m, "persist_runtime_state"), patch.object(
            m, "_append_device_log"
        ):
            resp = m.set_auto_switch_devices(payload, authorization="Bearer test")

        assert resp["code"] == 0
        data = resp["data"]
        assert data["devices_changed"] == 1
        assert data["devices_notified"] == 1
        assert data["devices_resynced"] == 0
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()
        m.store.auto_switch_bundle_revision = 0
