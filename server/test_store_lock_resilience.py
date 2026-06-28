"""验证 store.lock 超时快速失败，避免设备热路径无限阻塞。"""
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import main as m  # noqa: E402


def _hold_lock_until(release: threading.Event, acquired: threading.Event) -> None:
  m.store.lock.acquire()
  acquired.set()
  try:
    release.wait(timeout=5.0)
  finally:
    m.store.lock.release()


def test_store_lock_raises_when_busy_other_thread() -> None:
  release = threading.Event()
  acquired = threading.Event()
  t = threading.Thread(target=_hold_lock_until, args=(release, acquired), daemon=True)
  t.start()
  assert acquired.wait(2), "background lock holder did not acquire in time"

  raised = False
  try:
    with m._tracked_store_lock("test_busy", wait_sec=0.05):
      pass
  except m.StoreLockBusy:
    raised = True
  finally:
    release.set()
    t.join(timeout=2)
  assert raised, "expected StoreLockBusy when lock held by another thread"


def test_check_start_returns_503_when_lock_busy() -> None:
  owner = "lock_test_owner"
  did = "dev_lock_test_check_start"
  alias = "L1"
  m.store.devices[did] = m.DeviceInfo(
    alias=alias,
    fingerprint="fp_lock_test",
    owner_username=owner,
    status="online",
  )
  release = threading.Event()
  acquired = threading.Event()
  t = threading.Thread(target=_hold_lock_until, args=(release, acquired), daemon=True)
  t.start()
  assert acquired.wait(2), "background lock holder did not acquire in time"

  try:
    resp = m.check_start(did, alias)
    assert resp.get("code") == 503
    assert "繁忙" in str(resp.get("msg") or "")
  finally:
    release.set()
    t.join(timeout=2)


def test_next_device_op_returns_none_when_lock_busy() -> None:
  did = "dev_lock_test_ops"
  m.store.pending_device_ops[did] = [{"type": "noop", "op_id": "op1"}]
  release = threading.Event()
  acquired = threading.Event()
  t = threading.Thread(target=_hold_lock_until, args=(release, acquired), daemon=True)
  t.start()
  assert acquired.wait(2), "background lock holder did not acquire in time"

  try:
    assert m._next_device_op(did) is None
    assert len(m.store.pending_device_ops.get(did) or []) == 1
  finally:
    release.set()
    t.join(timeout=2)


def test_rlock_allows_reentrant_acquire_same_thread() -> None:
  with m._store_lock("outer", blocking=True):
    with m._store_lock("inner", blocking=True):
      pass


if __name__ == "__main__":
  test_store_lock_raises_when_busy_other_thread()
  test_check_start_returns_503_when_lock_busy()
  test_next_device_op_returns_none_when_lock_busy()
  test_rlock_allows_reentrant_acquire_same_thread()
  print("ok")
