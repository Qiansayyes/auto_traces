"""备份档案 xlsx 导入：已有 username 时 backup 字段可被覆盖。"""
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
            video_count TEXT NOT NULL DEFAULT '',
            follower_count TEXT NOT NULL DEFAULT '',
            follower_growth TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (owner_username, row_index)
        )
        """
    )
    return conn


class _ConnKeepOpen:
    def __init__(self, inner: sqlite3.Connection) -> None:
        self._inner = inner

    def __getattr__(self, name: str):
        return getattr(self._inner, name)

    def close(self) -> None:
        pass


def _patch_get_db(conn: sqlite3.Connection):
    wrapped = _ConnKeepOpen(conn)
    m._get_db = lambda: wrapped  # type: ignore[assignment]


def test_import_overwrites_existing_backup_fields() -> None:
    conn = _setup_conn()
    owner = "admin"
    old_name = "S6_loganhsi566_dev_d2a2e9103923401dad0a1b2967c947cb_20260623_160020.tar.gz"
    new_name = "S6_loganhsi566_dev_0862de38c6a740ba9b2d46b8cdc62b7b_20260627_160013.tar.gz"
    new_path = f"/storage/emulated/0/Android/data/com.autotraces.app/files/backups/{new_name}"
    conn.execute(
        """
        INSERT INTO device_editor_rows
            (owner_username, row_index, device_alias, device_id, username, assigned_alias,
             backup_path, backup_name, backup_time, backup_status, switch_highlight, updated_at)
        VALUES (?, 347, 'S6', 'dev_d2a2e9103923401dad0a1b2967c947cb', 'loganhsi566', 'S6',
                ?, ?, '2026-06-23 16:00:20', '已备份', '否', '2026-06-23')
        """,
        (owner, f"/old/{old_name}", old_name),
    )
    conn.commit()
    _patch_get_db(conn)

    result = m._import_backup_archive_xlsx_rows(
        owner,
        [
            {
                "row_index": "347",
                "assigned_alias": "S6",
                "device_alias": "S6",
                "device_id": "dev_d2a2e9103923401dad0a1b2967c947cb",
                "username": "loganhsi566",
                "backup_path": new_path,
                "backup_name": new_name,
                "backup_time": "2026-06-28 06:02:42",
                "backup_status": "已备份",
            }
        ],
    )
    assert result["code"] == 0
    assert result["updated"] == 1

    row = conn.execute(
        """
        SELECT backup_path, backup_name, backup_time, backup_status
        FROM device_editor_rows
        WHERE owner_username = ? AND row_index = 347
        """,
        (owner,),
    ).fetchone()
    assert row["backup_name"] == new_name
    assert row["backup_path"] == new_path
    assert row["backup_time"] == "2026-06-28 06:02:42"
    assert row["backup_status"] == "已备份"


if __name__ == "__main__":
    test_import_overwrites_existing_backup_fields()
    print("ok")
