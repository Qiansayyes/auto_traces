"""测试：TikTok 验证码优先走邮箱管理模块。"""

import sqlite3
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

SERVER_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVER_DIR))

import mail_integration as mi


def _create_accounts_db(path: Path, owner: str, email: str, *, with_token: bool = True) -> None:
    conn = sqlite3.connect(str(path))
    try:
        conn.executescript(
            """
            CREATE TABLE account_records (
                record_key TEXT PRIMARY KEY,
                email TEXT NOT NULL,
                password TEXT NOT NULL DEFAULT '',
                client_id TEXT NOT NULL DEFAULT '',
                refresh_token TEXT NOT NULL DEFAULT '',
                raw_line TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'active',
                imported_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                owner_username TEXT NOT NULL DEFAULT ''
            );
            """
        )
        conn.execute(
            """
            INSERT INTO account_records (
                record_key, email, password, client_id, refresh_token, raw_line, source, status,
                imported_at, updated_at, owner_username
            ) VALUES (?, ?, '', ?, ?, '', 'test', 'active', 't', 't', ?)
            """,
            (
                "rk1",
                email,
                "client-id" if with_token else "",
                "refresh-token" if with_token else "",
                owner,
            ),
        )
        conn.commit()
    finally:
        conn.close()


def test_mail_account_lookup_and_token_check():
    with tempfile.TemporaryDirectory() as tmp:
        db_path = Path(tmp) / "accounts.sqlite"
        _create_accounts_db(db_path, "alice", "user@outlook.com", with_token=True)
        with patch.object(mi, "MAIL_ACCOUNTS_DB_PATH", db_path), patch.object(mi, "is_mail_module_ready", return_value=True):
            record = mi.get_mail_account_record("alice", "user@outlook.com")
            assert record is not None
            assert mi.mail_account_has_valid_token(record)
            assert mi.mail_module_can_fetch_code("alice", "user@outlook.com")

            missing = mi.get_mail_account_record("bob", "user@outlook.com")
            assert missing is None


def test_fetch_tiktok_email_code_via_mail_module_extracts_code():
    sample_mails = [
        {"subject": "TikTok verification", "text": "Your 6-digit code is 123456", "date": "2026-06-16T10:00:00"},
    ]

    def fake_fetch(owner, account, *, mailbox="INBOX", top=30, timeout_sec=60.0):
        return sample_mails if mailbox == "INBOX" else []

    with tempfile.TemporaryDirectory() as tmp:
        db_path = Path(tmp) / "accounts.sqlite"
        _create_accounts_db(db_path, "alice", "user@outlook.com", with_token=True)
        with patch.object(mi, "MAIL_ACCOUNTS_DB_PATH", db_path), patch.object(mi, "is_mail_module_ready", return_value=True), patch.object(
            mi, "fetch_mails_via_mail_module", side_effect=fake_fetch
        ):
            code, msg, value = mi.fetch_tiktok_email_code_via_mail_module(
                "alice",
                "user@outlook.com",
                lambda body: "123456" if "123456" in body else (_ for _ in ()).throw(ValueError("no")),
            )
            assert code == 0
            assert value == "123456"


def test_main_tiktok_email_code_prefers_mail_module():
    import main as app_main

    with patch.object(app_main, "_try_fetch_tiktok_email_code_via_mail_module", return_value=(0, "ok", "654321")), patch.object(
        app_main, "_fetch_tiktok_email_code_from_api", side_effect=AssertionError("should not call legacy api")
    ):
        resp = app_main._tiktok_email_code_response(
            "dev_1",
            "A1",
            "https://example.com/old-api",
            owner_username="alice",
            account_email="user@outlook.com",
        )
        assert resp["code"] == 0
        assert resp["email_code"] == "654321"
        assert resp.get("source") == "mail_module"


def test_main_tiktok_email_code_falls_back_to_api():
    import main as app_main

    with patch.object(
        app_main, "_try_fetch_tiktok_email_code_via_mail_module", return_value=(1, "not configured", None)
    ), patch.object(app_main, "_fetch_tiktok_email_code_from_api", return_value=(0, "ok", "112233")):
        resp = app_main._tiktok_email_code_response(
            "dev_1",
            "A1",
            "https://example.com/old-api",
            owner_username="alice",
            account_email="user@outlook.com",
        )
        assert resp["code"] == 0
        assert resp["email_code"] == "112233"
        assert resp.get("source") == "api"


if __name__ == "__main__":
    test_mail_account_lookup_and_token_check()
    test_fetch_tiktok_email_code_via_mail_module_extracts_code()
    test_main_tiktok_email_code_prefers_mail_module()
    test_main_tiktok_email_code_falls_back_to_api()
    print("mail module email code tests passed")
