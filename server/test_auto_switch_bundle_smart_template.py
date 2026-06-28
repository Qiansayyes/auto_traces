"""自动切号 bundle 智能模板打包：per-account 配置 + session enter 重掷。"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import main as m  # noqa: E402


def test_session_id_changes_smart_template_roll() -> None:
    owner = "admin"
    data = {
        "run_mode": "growth",
        "active_template": "default",
        "configs_by_template": {
            "tpl_a": {"pull_batch_size": 5},
            "tpl_b": {"pull_batch_size": 9},
        },
        "smart_template_config": {
            "old_branch_min_followers": 99999,
            "old_branch_min_video_count": 99999,
            "new_branch_templates": [
                {"template_name": "tpl_a", "weight": 50},
                {"template_name": "tpl_b", "weight": 50},
            ],
            "old_branch_templates": [],
        },
        "alias_smart_template_by_mode": {"growth": {"phone-a": True}},
    }
    templates = dict(data["configs_by_template"])
    stats = {"tiktok_username": "user1", "follower_count": 0, "video_count": 0}
    _, dbg1 = m._resolve_smart_template_for_alias(
        owner, data, "growth", "phone-a", templates,
        account_stats=stats, session_id="sess-aaa",
    )
    _, dbg2 = m._resolve_smart_template_for_alias(
        owner, data, "growth", "phone-a", templates,
        account_stats=stats, session_id="sess-bbb",
    )
    assert dbg1.get("seed") != dbg2.get("seed")
    assert "sess-aaa" in str(dbg1.get("seed"))
    assert "sess-bbb" in str(dbg2.get("seed"))


def test_per_account_different_branch_picks() -> None:
    owner = "admin"
    runtime = {
        "run_mode": "growth",
        "active_template": "default",
        "configs_by_template": {
            "new_tpl": {"pull_batch_size": 3},
            "old_tpl": {"pull_batch_size": 7},
        },
        "smart_template_config": {
            "old_branch_min_followers": 100,
            "old_branch_min_video_count": 10,
            "new_branch_templates": [{"template_name": "new_tpl", "weight": 100}],
            "old_branch_templates": [{"template_name": "old_tpl", "weight": 100}],
        },
        "alias_smart_template_by_mode": {"growth": {"phone-a": True}},
    }
    rows = [
        {"username": "newbie", "follower_count": "0", "video_count": "0"},
        {"username": "veteran", "follower_count": "500", "video_count": "20"},
    ]
    configs, debug, _active, _eff = m._build_auto_switch_account_configs(
        owner, runtime, "growth", "phone-a", rows, session_id="enter-1",
    )
    assert configs["newbie"]["active_template"] == "new_tpl"
    assert configs["veteran"]["active_template"] == "old_tpl"
    assert debug.get("enabled") is True
    assert debug.get("enter_reroll") is True
    acct_dbg = debug.get("accounts") or {}
    assert acct_dbg["newbie"]["branch"] == "new"
    assert acct_dbg["veteran"]["branch"] == "old"


def test_alias_binding_when_smart_template_off() -> None:
    owner = "admin"
    runtime = {
        "run_mode": "growth",
        "active_template": "default",
        "configs_by_template": {
            "default": {"pull_batch_size": 5},
            "bound_tpl": {"pull_batch_size": 11},
        },
        "alias_template_by_mode": {"growth": {"phone-a": "bound_tpl"}},
        "alias_smart_template_by_mode": {"growth": {"phone-a": False}},
    }
    rows = [
        {"username": "user_a", "follower_count": "0", "video_count": "0"},
        {"username": "user_b", "follower_count": "100", "video_count": "5"},
    ]
    configs, debug, active, _eff = m._build_auto_switch_account_configs(
        owner, runtime, "growth", "phone-a", rows, session_id="sess-x",
    )
    assert active == "bound_tpl"
    assert configs["user_a"]["active_template"] == "bound_tpl"
    assert configs["user_b"]["active_template"] == "bound_tpl"
    assert debug.get("enabled") is False
    assert (debug.get("accounts") or {})["user_a"]["resolution_source"] == "alias_binding"


def test_account_activity_audit_from_ledger() -> None:
    entries = [
        {
            "op_type": "auto_switch_account_activity",
            "username": "user_a",
            "slot_key": "2026-06-27|3",
            "report_data": {
                "account_username": "user_a",
                "active_template": "tpl_growth",
                "auto_switch_slot_key": "2026-06-27|3",
                "segment_started_at": "2026-06-27 10:00:00",
                "segment_ended_at": "2026-06-27 12:30:00",
                "segment_reason": "cut_switch",
                "run_mode": "growth",
                "template_config_json": '{"like_prob":0.5,"mutual_traffic_comment":true}',
                "action_counts_json": '{"like":12,"comment":3,"mutual_traffic_comment":2,"mutual_follow":5}',
            },
        },
    ]
    out = m._account_activity_audit_from_ledger(entries)
    assert len(out) == 1
    assert out[0]["account"] == "user_a"
    assert out[0]["active_template"] == "tpl_growth"
    assert out[0]["action_counts"]["mutual_follow"] == 5


def test_reconcile_includes_account_activity() -> None:
    ledger = [
        {
            "op_type": "auto_switch_account_activity",
            "status": "success",
            "username": "user_a",
            "slot_key": "2026-06-27|1",
            "report_data": {
                "account_username": "user_a",
                "active_template": "tpl1",
                "auto_switch_slot_key": "2026-06-27|1",
                "segment_started_at": "2026-06-27 09:00:00",
                "segment_ended_at": "2026-06-27 11:00:00",
                "segment_reason": "cut_switch",
                "run_mode": "growth",
                "template_config_json": '{"like_prob":0.3}',
                "action_counts_json": '{"like":5}',
            },
        },
    ]
    recon = m._reconcile_auto_switch_session_summary("admin", "phone-a", "2026-06-27", ledger)
    activities = recon.get("account_activity") or []
    assert len(activities) == 1
    assert activities[0]["account"] == "user_a"
    assert any("账号任务段审计" in str(n) for n in (recon.get("notes") or []))


if __name__ == "__main__":
    test_session_id_changes_smart_template_roll()
    test_per_account_different_branch_picks()
    test_alias_binding_when_smart_template_off()
    test_account_activity_audit_from_ledger()
    test_reconcile_includes_account_activity()
    print("ok")
