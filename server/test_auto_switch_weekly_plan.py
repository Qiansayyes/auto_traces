"""自动切号周计划：7 天独立洗牌，设备 bundle 含整周安排。"""
from __future__ import annotations

import importlib.util
import os
import sys
import unittest


def _load_main():
    server_dir = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(server_dir, "main.py")
    spec = importlib.util.spec_from_file_location("auto_traces_main_weekly", path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["auto_traces_main_weekly"] = mod
    spec.loader.exec_module(mod)
    return mod


class AutoSwitchWeeklyPlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = _load_main()

    def _sample_rows(self, n: int = 3):
        return [
            {"row_index": i, "username": f"user_{i}"}
            for i in range(n)
        ]

    def test_week_bounds_monday_sunday(self):
        start, end = self.m._beijing_week_bounds("2026-06-26")  # Friday
        self.assertEqual(start, "2026-06-22")
        self.assertEqual(end, "2026-06-28")

    def test_iter_week_has_seven_days(self):
        dates = self.m._iter_dates_inclusive("2026-06-22", "2026-06-28")
        self.assertEqual(len(dates), 7)
        self.assertEqual(dates[0], "2026-06-22")
        self.assertEqual(dates[-1], "2026-06-28")

    def test_weekly_plan_generates_seven_days(self):
        rows = self._sample_rows(3)
        plan = self.m._generate_auto_switch_weekly_plan(rows, "2026-06-22", "2026-06-28")
        self.assertEqual(plan["week_start_date"], "2026-06-22")
        self.assertEqual(plan["week_end_date"], "2026-06-28")
        self.assertEqual(len(plan["days"]), 7)
        for date_key in self.m._iter_dates_inclusive("2026-06-22", "2026-06-28"):
            day = plan["days"][date_key]
            self.assertEqual(len(day["slots"]), 3)

    def test_each_day_shuffle_differs_with_three_accounts(self):
        rows = self._sample_rows(3)
        plan = self.m._generate_auto_switch_weekly_plan(rows, "2026-06-22", "2026-06-28")
        orders = [self.m._slot_username_order(plan["days"][d]) for d in plan["days"]]
        self.assertGreater(len(set(orders)), 1, "三天以上账号时，周内每天至少应有一种不同排班")

    def test_weekly_plan_for_bundle_has_day_plans_list(self):
        rows = self._sample_rows(2)
        plan = self.m._generate_auto_switch_weekly_plan(rows, "2026-06-22", "2026-06-28")
        out = self.m._weekly_plan_for_bundle(plan)
        self.assertEqual(len(out["day_plans"]), 7)
        self.assertEqual(out["day_plans"][0]["schedule_date"], "2026-06-22")
        self.assertIn("daily_plan", out["day_plans"][0])

    def test_daily_plan_for_schedule_date(self):
        rows = self._sample_rows(2)
        plan = self.m._generate_auto_switch_weekly_plan(rows, "2026-06-22", "2026-06-28")
        day = self.m._daily_plan_for_schedule_date(plan, "2026-06-24")
        self.assertIsNotNone(day)
        self.assertEqual(len(day["slots"]), 2)


    def test_weekly_plan_invalidates_on_rows_change(self):
        rows_a = self._sample_rows(3)
        plan = self.m._generate_auto_switch_weekly_plan(rows_a, "2026-06-22", "2026-06-28")
        self.assertTrue(
            self.m._weekly_plan_is_complete(plan, "2026-06-22", "2026-06-28", rows_a)
        )
        rows_b = rows_a + [{"row_index": 3, "username": "user_3"}]
        self.assertFalse(
            self.m._weekly_plan_is_complete(plan, "2026-06-22", "2026-06-28", rows_b)
        )


if __name__ == "__main__":
    unittest.main()
