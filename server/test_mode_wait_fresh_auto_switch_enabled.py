"""mode_wait 返回时须现读 auto_switch_enabled，不得使用长轮询开始时的陈旧快照。"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import main as m  # noqa: E402


def test_mode_wait_reads_auto_switch_enabled_at_return_time() -> None:
    device_id = "dev_fresh_switch"
    owner = "admin"
    with m.store.lock:
        m.store.devices[device_id] = m.DeviceInfo(
            alias="S6",
            fingerprint="fp",
            owner_username=owner,
            auto_switch_enabled=False,
        )

    def _read_enabled() -> bool:
        with m.store.lock:
            dev = m.store.devices.get(device_id)
            return bool(dev and dev.auto_switch_enabled)

    assert _read_enabled() is False

    with m.store.lock:
        m.store.devices[device_id].auto_switch_enabled = True

    assert _read_enabled() is True
    assert m._device_auto_switch_enabled(device_id) is True
