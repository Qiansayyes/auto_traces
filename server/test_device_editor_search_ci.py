"""设备备份档案云端搜索：大小写不敏感。"""
import sqlite3
import unittest

import main as m


class DeviceEditorSearchCiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.execute(
            """
            CREATE TABLE device_editor_rows (
                owner_username TEXT,
                row_index INTEGER,
                device_alias TEXT,
                assigned_alias TEXT,
                username TEXT,
                region TEXT,
                device_id TEXT,
                ip TEXT
            )
            """
        )
        self.conn.execute(
            """
            CREATE TABLE account_editor_rows (
                owner_username TEXT,
                username TEXT,
                assigned_alias TEXT,
                region TEXT
            )
            """
        )
        self.conn.execute(
            """
            INSERT INTO device_editor_rows VALUES
            ('admin', 1, 'G56', '', 'UserABC', 'US', 'dev-56', '192.168.1.56')
            """
        )
        self.conn.execute(
            """
            INSERT INTO account_editor_rows VALUES ('admin', 'UserABC', 'G56', 'EU')
            """
        )

    def tearDown(self) -> None:
        self.conn.close()

    def _match_usernames(self, search: str) -> list[str]:
        sql, params = m._device_editor_search_sql(search)
        rows = self.conn.execute(
            f"SELECT username FROM device_editor_rows WHERE owner_username = 'admin'{sql}",
            params,
        ).fetchall()
        return [str(r[0]) for r in rows]

    def test_username_case_insensitive(self) -> None:
        self.assertEqual(self._match_usernames("userabc"), ["UserABC"])
        self.assertEqual(self._match_usernames("USERABC"), ["UserABC"])

    def test_alias_case_insensitive(self) -> None:
        self.assertEqual(self._match_usernames("g56"), ["UserABC"])

    def test_device_id_case_insensitive(self) -> None:
        self.assertEqual(self._match_usernames("DEV-56"), ["UserABC"])

    def test_account_region_via_exists(self) -> None:
        self.assertEqual(self._match_usernames("eu"), ["UserABC"])


if __name__ == "__main__":
    unittest.main()
