# -*- coding: utf-8 -*-
from __future__ import annotations

import base64

from totp_util import generate_totp_code, normalize_totp_secret, totp_remaining_seconds


def test_totp_rfc6238_6_digits():
    secret = base64.b32encode(b"12345678901234567890").decode("ascii")
    assert generate_totp_code(secret, for_time=59) == "287082"
    assert generate_totp_code(secret, for_time=1111111109) == "081804"


def test_normalize_otpauth_uri():
    uri = "otpauth://totp/TikTok:demo?secret=JBSWY3DPEHPK3PXP&issuer=TikTok"
    assert normalize_totp_secret(uri) == "JBSWY3DPEHPK3PXP"
    code = generate_totp_code(uri)
    assert code.isdigit() and len(code) == 6


def test_remaining_seconds_bounds():
    rem = totp_remaining_seconds(for_time=10, period=30)
    assert 1 <= rem <= 30
