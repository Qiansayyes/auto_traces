"""PC 投屏地址批量下发：URL 校验与入队。"""
import unittest
from unittest.mock import patch

import main as m


class DevicePcServiceBatchSetTest(unittest.TestCase):
    def test_normalize_windows_base_url(self) -> None:
        self.assertEqual(m._normalize_windows_base_url(" http://192.168.1.6:5088/ "), "http://192.168.1.6:5088")
        with self.assertRaises(ValueError):
            m._normalize_windows_base_url("192.168.1.6:5088")

    def test_load_device_pc_service_url_roundtrip(self) -> None:
        did = "dev_pc_url_bundle_test_001"
        owner = "admin_pc_url_test"
        alias = "手机PC地址测试"
        fp = "fp_pc_url_bundle_test_001"
        now = m.now_text()
        conn = m._get_db()
        try:
            conn.execute(
                """
                INSERT OR REPLACE INTO client_devices (
                    device_id, owner_username, alias, device_group, fingerprint,
                    status, pending_start, paused, auto_start_opt_out,
                    created_at, updated_at, pc_service_url
                ) VALUES (?, ?, ?, '', ?, 'offline', 0, 0, 0, ?, ?, '')
                """,
                (did, owner, alias, fp, now, now),
            )
            conn.commit()
        finally:
            conn.close()
        m._persist_device_pc_service_url(did, "http://192.168.1.8:5088")
        self.assertEqual(m._load_device_pc_service_url(did), "http://192.168.1.8:5088")
        self.assertEqual(m._load_device_pc_service_url(""), "")

    def test_batch_set_enqueues_op(self) -> None:
        owner = "admin_pc_url_test"
        did = "dev_pc_url_test_001"
        alias = "手机A"
        with m.store.lock:
            m.store.devices[did] = m.DeviceInfo(
                alias=alias,
                fingerprint="fp_pc_url_test",
                owner_username=owner,
            )
            m.store.pending_device_ops.pop(did, None)
        session = {"username": owner}
        payload = m.DevicePcServiceUrlBatchSetRequest(
            items=[
                m.DevicePcServiceUrlSetItem(
                    device_id=did,
                    alias=alias,
                    windows_base_url="http://192.168.1.6:5088",
                )
            ]
        )
        with patch.object(m, "_require_login", return_value=(None, session)), patch.object(
            m, "_require_login_and_owning_device", return_value=(None, m.store.devices[did])
        ), patch.object(m, "_persist_device_pc_service_url") as persist:
            resp = m.admin_device_pc_service_batch_set(payload, authorization="Bearer test")
        self.assertEqual(resp["code"], 0)
        self.assertEqual(resp["enqueued"], 1)
        self.assertEqual(resp["results"][0]["windows_base_url"], "http://192.168.1.6:5088")
        persist.assert_called_once_with(did, "http://192.168.1.6:5088")
        with m.store.lock:
            queue = m.store.pending_device_ops.get(did) or []
        self.assertEqual(len(queue), 1)
        self.assertEqual(queue[0]["op_type"], "set_pc_service_url")
        self.assertEqual(queue[0]["payload"]["windows_base_url"], "http://192.168.1.6:5088")

    def test_batch_set_ignores_stale_client_alias(self) -> None:
        """改绑后管理台若仍传旧 alias，须按 DB/store 当前别名鉴权并入队。"""
        owner = "admin_pc_url_test"
        did = "dev_pc_url_stale_alias_001"
        current_alias = "9_Mx99"
        stale_alias = "9_Mx61"
        with m.store.lock:
            m.store.devices[did] = m.DeviceInfo(
                alias=current_alias,
                fingerprint="fp_pc_url_stale_alias",
                owner_username=owner,
            )
            m.store.pending_device_ops.pop(did, None)
        session = {"username": owner}
        payload = m.DevicePcServiceUrlBatchSetRequest(
            items=[
                m.DevicePcServiceUrlSetItem(
                    device_id=did,
                    alias=stale_alias,
                    windows_base_url="http://172.168.100.222:5088",
                )
            ]
        )
        with patch.object(m, "_require_login", return_value=(None, session)), patch.object(
            m, "_require_login_and_owning_device", return_value=(None, m.store.devices[did])
        ) as auth_mock, patch.object(m, "_persist_device_pc_service_url") as persist:
            resp = m.admin_device_pc_service_batch_set(payload, authorization="Bearer test")
        self.assertEqual(resp["code"], 0)
        self.assertEqual(resp["enqueued"], 1)
        self.assertEqual(resp["results"][0]["alias"], current_alias)
        auth_mock.assert_called_once()
        self.assertEqual(auth_mock.call_args.args[0], did)
        self.assertEqual(auth_mock.call_args.args[1], current_alias)
        persist.assert_called_once_with(did, "http://172.168.100.222:5088")


if __name__ == "__main__":
    unittest.main()
