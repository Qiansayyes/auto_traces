"""mode_wait 尽力投递：每条事件仅按 since_event_id 投递一次，不依赖 ACK 重发。"""
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


def _insert_event(conn: sqlite3.Connection, device_id: str, event_type: str) -> int:
    cur = conn.execute(
        """
        INSERT INTO device_mode_events
            (device_id, owner_username, event_type, session_id, bundle_revision, payload_json, created_at)
        VALUES (?, 'admin', ?, 'sess', 1, '{}', ?)
        """,
        (device_id, event_type, m.now_text()),
    )
    conn.commit()
    return int(cur.lastrowid or 0)


def test_delivers_next_event_when_since_below_id() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_one"
    try:
        eid = _insert_event(conn, device_id, "auto_switch_enter")
        event = m._fetch_pending_device_mode_event(device_id, since_event_id=0)
        assert event is not None
        assert event["event_id"] == eid
        assert event["type"] == "auto_switch_enter"
    finally:
        m._get_db = original_get_db


def test_does_not_redeliver_after_since_passed_without_ack() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_one"
    try:
        eid = _insert_event(conn, device_id, "auto_switch_enter")
        first = m._fetch_pending_device_mode_event(device_id, since_event_id=0)
        assert first is not None
        assert first["event_id"] == eid
        # 设备已推进 since，但未 ACK —— 不应再投递同一条。
        again = m._fetch_pending_device_mode_event(device_id, since_event_id=eid)
        assert again is None
    finally:
        m._get_db = original_get_db


def test_delivers_only_newer_events_after_since() -> None:
    conn = _setup_conn()
    original_get_db = _patch_get_db(conn)
    device_id = "dev_one"
    try:
        e1 = _insert_event(conn, device_id, "auto_switch_enter")
        e2 = _insert_event(conn, device_id, "auto_switch_exit")
        event = m._fetch_pending_device_mode_event(device_id, since_event_id=e1)
        assert event is not None
        assert event["event_id"] == e2
        assert event["type"] == "auto_switch_exit"
    finally:
        m._get_db = original_get_db
