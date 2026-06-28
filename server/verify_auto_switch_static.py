"""轻量静态验证：不 import main，避免磁盘满时触发 DB 初始化。"""
from __future__ import annotations


_WRITEBACK_TYPES = frozenset({"tiktok_backup_and_clear", "tiktok_clear", "tiktok_restore"})


def _writeback_eligible(entry: dict) -> tuple[bool, str]:
    op_type = str(entry.get("op_type") or "").strip()
    phase = str(entry.get("phase") or entry.get("status") or "").strip().lower()
    status = str(entry.get("status") or "").strip().lower()
    terminal = phase in ("success", "failed") or status in ("success", "failed")
    if op_type not in _WRITEBACK_TYPES:
        return False, f"{op_type or '未知类型'} 不参与档案写回，仅记入对账"
    if not terminal:
        return False, f"{op_type} 未达终态（phase={phase or status or '-'}），跳过写回"
    data = entry.get("report_data") or {}
    if not data:
        return False, f"{op_type} 缺少 report_data，无法写回"
    if op_type == "tiktok_backup_and_clear":
        if status != "success" and phase != "success":
            return True, ""
        if bool(data.get("backup_skipped")):
            return False, "备份已跳过（backup_skipped），不写回备份字段"
    return True, ""


def test_writeback_eligibility() -> None:
    ok, note = _writeback_eligible(
        {"op_type": "publish_post_start", "status": "success", "phase": "success"}
    )
    assert ok is False and "不参与档案写回" in note

    ok, note = _writeback_eligible(
        {
            "op_type": "tiktok_backup_and_clear",
            "status": "success",
            "phase": "success",
            "report_data": {"op_type": "tiktok_backup_and_clear", "username": "u1"},
        }
    )
    assert ok is True and note == ""

    ok, note = _writeback_eligible(
        {
            "op_type": "tiktok_restore",
            "status": "running",
            "phase": "running",
            "report_data": {"op_type": "tiktok_restore"},
        }
    )
    assert ok is False and "未达终态" in note


def test_phase_machine() -> None:
    rank = {"queued": 0, "running": 1, "success": 2, "failed": 2}

    def can_go(a: str, b: str) -> bool:
        if a == b:
            return True
        if a in ("success", "failed") and b in ("success", "failed") and a != b:
            return False
        return rank.get(b, 0) >= rank.get(a, 0)

    assert can_go("queued", "running")
    assert can_go("running", "success")
    assert not can_go("success", "failed")


def test_publish_outcome_op_id() -> None:
    start = "local-as-publish-99"
    outcome = f"{start}|outcome"
    assert start != outcome


def _pending_profile_edit_usernames_from_ledger(entries: list[dict]) -> set[str]:
    pending: set[str] = set()
    for raw in entries:
        op_type = str(raw.get("op_type") or "")
        if op_type == "auto_switch_profile_baseline":
            data = raw.get("report_data") or {}
            raw_list = str(data.get("pending_profile_edit_usernames") or "").strip()
            for part in raw_list.split(","):
                u = part.strip().lower()
                if u:
                    pending.add(u)
        elif op_type == "profile_edit_required":
            data = raw.get("report_data") or {}
            u = str(raw.get("username") or data.get("username") or "").strip().lower()
            if u:
                pending.add(u)
    return pending


def _profile_edit_success_usernames_from_ledger(entries: list[dict]) -> set[str]:
    done: set[str] = set()
    for raw in entries:
        if str(raw.get("op_type") or "") != "tiktok_edit_basic_profile":
            continue
        phase = str(raw.get("phase") or raw.get("status") or "").strip().lower()
        if phase != "success":
            continue
        data = raw.get("report_data") or {}
        if bool(data.get("skip_account_db_update")):
            continue
        if data.get("profile_edit_success") is False:
            continue
        u = str(raw.get("username") or data.get("username") or "").strip().lower()
        if u:
            done.add(u)
    return done


def test_profile_edit_reconciliation() -> None:
    ledger = [
        {
            "op_type": "profile_edit_required",
            "phase": "success",
            "username": "alice",
            "report_data": {"username": "alice"},
        },
        {
            "op_type": "profile_edit_required",
            "phase": "success",
            "username": "bob",
            "report_data": {"username": "bob"},
        },
        {
            "op_type": "tiktok_edit_basic_profile",
            "phase": "success",
            "username": "alice",
            "report_data": {"profile_edit_success": True, "username": "alice"},
        },
        {
            "op_type": "tiktok_edit_basic_profile",
            "phase": "success",
            "username": "carol",
            "report_data": {
                "profile_edit_success": True,
                "username": "carol",
                "skip_account_db_update": True,
            },
        },
    ]
    pending = _pending_profile_edit_usernames_from_ledger(ledger)
    assert pending == {"alice", "bob"}
    done = _profile_edit_success_usernames_from_ledger(ledger)
    assert done == {"alice"}
    missing = sorted(pending - done)
    assert missing == ["bob"]


def test_publish_inflight_logic() -> None:
    """模拟：有 start 无 outcome => inflight；有 outcome success => done。"""
    def is_done(entries, sk: str) -> bool:
        return any(
            e.get("slot_key") == sk
            and e.get("op_type") == "publish_post_outcome"
            and e.get("phase") == "success"
            for e in entries
        )

    def is_aborted(entries, sk: str) -> bool:
        return any(
            e.get("slot_key") == sk
            and e.get("op_type") == "publish_post_outcome"
            and e.get("phase") == "failed"
            and ("登录" in e.get("message", "") or "时段已切换" in e.get("message", ""))
            for e in entries
        )

    def is_inflight(entries, sk: str) -> bool:
        if is_done(entries, sk) or is_aborted(entries, sk):
            return False
        return any(e.get("slot_key") == sk and e.get("op_type") == "publish_post_start" for e in entries)

    sk = "2026-06-25|0"
    after_dispatch = [
        {"op_id": "p1", "op_type": "publish_post_start", "phase": "success", "slot_key": sk},
    ]
    assert is_inflight(after_dispatch, sk) is True

    after_outcome = after_dispatch + [
        {
            "op_id": "p1|outcome",
            "op_type": "publish_post_outcome",
            "phase": "success",
            "slot_key": sk,
        }
    ]
    assert is_done(after_outcome, sk) is True
    assert is_inflight(after_outcome, sk) is False


if __name__ == "__main__":
    test_writeback_eligibility()
    test_phase_machine()
    test_publish_outcome_op_id()
    test_profile_edit_reconciliation()
    test_publish_inflight_logic()
    print("verify_auto_switch_static: ok")
