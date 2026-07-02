"""读路径优化：device_editor 维护限频与 user_runtime 缓存。"""
import sqlite3
import time
import unittest
from unittest.mock import patch

import main as m


class ReadPathOptimizationsTest(unittest.TestCase):
    def setUp(self) -> None:
        m._device_editor_read_maintain_last_at.clear()
        m._invalidate_user_runtime_state_cache()

    def test_maintain_skipped_within_interval(self) -> None:
        conn = sqlite3.connect(":memory:")
        owner = "user_a"
        m._device_editor_read_maintain_last_at[owner] = time.monotonic()
        with patch.object(m, "_maybe_prune_orphan_device_editor_rows") as prune:
            with patch.object(m, "_maybe_reconcile_editor_alias_bindings") as reconcile:
                m._maybe_maintain_device_editor_on_read(conn, owner)
        prune.assert_not_called()
        reconcile.assert_not_called()
        conn.close()

    def test_user_runtime_state_cache_hit(self) -> None:
        owner = "cached_user"
        with m._user_runtime_state_cache_lock:
            m._user_runtime_state_cache[owner] = (
                {"run_mode": "direct"},
                time.monotonic() + 60.0,
            )
        with patch.object(m, "_get_db") as get_db:
            got = m._load_user_runtime_state(owner, use_cache=True)
        get_db.assert_not_called()
        self.assertEqual(got.get("run_mode"), "direct")

    def test_invalidate_runtime_cache(self) -> None:
        owner = "u1"
        with m._user_runtime_state_cache_lock:
            m._user_runtime_state_cache[owner] = ({"a": 1}, time.monotonic() + 60.0)
        m._invalidate_user_runtime_state_cache(owner)
        with m._user_runtime_state_cache_lock:
            self.assertNotIn(owner, m._user_runtime_state_cache)

    def test_reset_maintain_schedule(self) -> None:
        owner = "u2"
        m._device_editor_read_maintain_last_at[owner] = time.monotonic()
        m._reset_device_editor_read_maintain_schedule(owner)
        self.assertNotIn(owner, m._device_editor_read_maintain_last_at)

    def test_growth_random_pull_from_roots(self) -> None:
        conn = sqlite3.connect(":memory:")
        conn.execute(
            "CREATE TABLE tiktok_roots (root_username TEXT PRIMARY KEY, created_at TEXT)"
        )
        for name in ("alice", "bob", "carol"):
            conn.execute(
                "INSERT INTO tiktok_roots (root_username, created_at) VALUES (?, ?)",
                (name, "2026-01-01"),
            )
        picked = m._pull_growth_usernames_random_from_roots(conn, 2)
        self.assertEqual(len(picked), 2)
        self.assertTrue(all(u in {"alice", "bob", "carol"} for u in picked))
        self.assertEqual(
            conn.execute("SELECT COUNT(1) FROM tiktok_roots").fetchone()[0],
            3,
        )
        conn.close()

    def test_overview_pending_uses_roots_temp(self) -> None:
        conn = sqlite3.connect(":memory:")
        conn.execute(
            "CREATE TABLE follower_queue (username TEXT, following_fetch_success INTEGER)"
        )
        conn.execute(
            "CREATE TABLE mobile_pending_usernames (id INTEGER PRIMARY KEY, username TEXT, created_at TEXT, consume_status TEXT)"
        )
        conn.execute(
            "CREATE TABLE tiktok_roots (root_username TEXT PRIMARY KEY, created_at TEXT)"
        )
        conn.execute("CREATE TABLE live_customer (username TEXT PRIMARY KEY)")
        conn.execute("INSERT INTO tiktok_roots (root_username, created_at) VALUES ('u1', 't')")
        conn.execute("INSERT INTO tiktok_roots (root_username, created_at) VALUES ('u2', 't')")
        conn.execute(
            "INSERT INTO mobile_pending_usernames (username, created_at, consume_status) VALUES ('queued', 't', 'pending')"
        )
        with patch.object(m, "_get_db", return_value=conn):
            stats = m._load_overview_queue_stats_from_db(preview_limit=10)
        self.assertEqual(stats["mobile_pending_total"], 2)
        self.assertEqual(stats["mobile_pending_preview"], ["u1", "u2"])
        conn.close()


if __name__ == "__main__":
    unittest.main()
