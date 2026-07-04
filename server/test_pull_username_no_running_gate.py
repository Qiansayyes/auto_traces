"""pull_username：设备认证通过即可拉取，不要求云端先标 running。"""
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
        CREATE TABLE tiktok_roots (
            root_username TEXT PRIMARY KEY
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
    conn.executemany(
        "INSERT INTO tiktok_roots (root_username) VALUES (?)",
        [("user_a",), ("user_b",), ("user_c",)],
    )
    conn.commit()
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


def _seed_device(status: str = "online", *, pending_start: bool = False, paused: bool = False) -> str:
    device_id = "dev_pull"
    m.store.devices[device_id] = m.DeviceInfo(
        alias="S6",
        fingerprint="fp",
        owner_username="admin",
        status=status,  # type: ignore[arg-type]
        pending_start=pending_start,
        paused=paused,
    )
    return device_id


def test_pull_username_serves_without_cloud_running_gate() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = _seed_device(status="online")
    try:
        with (
            patch.object(m, "_load_user_runtime_state", return_value={"run_mode": "growth"}),
            patch.object(m, "_resolve_switch_highlight_tiktok_username", return_value=""),
            patch.object(m, "_resolve_template_for_mode_alias", return_value=("tpl", {}, {}, {})),
            patch.object(m, "_persist_client_device_to_db"),
        ):
            resp = m.pull_username(device_id=device_id, alias="S6", count=2, run_mode="growth")
        assert resp.get("code") == 0, resp
        assert len(resp.get("usernames") or []) == 2
        dev = m.store.devices[device_id]
        assert dev.status == "running"
        assert dev.paused is False
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()


def test_pull_username_no_longer_returns_not_started() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = _seed_device(status="paused", paused=True)
    try:
        with (
            patch.object(m, "_load_user_runtime_state", return_value={"run_mode": "growth"}),
            patch.object(m, "_resolve_switch_highlight_tiktok_username", return_value=""),
            patch.object(m, "_resolve_template_for_mode_alias", return_value=("tpl", {}, {}, {})),
            patch.object(m, "_persist_client_device_to_db"),
        ):
            resp = m.pull_username(device_id=device_id, alias="S6", count=1, run_mode="growth")
        assert resp.get("code") != 3, resp
        assert resp.get("msg") != "设备未启动，请先从云端一键启动"
        assert m.store.devices[device_id].status == "running"
    finally:
        m._get_db = original_get_db
        m.store.devices.clear()
