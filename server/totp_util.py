# -*- coding: utf-8 -*-
"""RFC 6238 / Google Authenticator 兼容 TOTP（HMAC-SHA1，默认 6 位 / 30 秒）。"""
from __future__ import annotations

import base64
import hashlib
import hmac
import re
import struct
import time
from urllib.parse import unquote


def normalize_totp_secret(secret: str) -> str:
    """规范化 TOTP Base32 密钥；兼容 otpauth URI 与空白/连字符。"""
    text = str(secret or "").strip()
    if not text:
        return ""
    if "otpauth://" in text.lower() and "secret=" in text.lower():
        m = re.search(r"(?i)(?:^|[?&])secret=([^&]+)", text)
        if m:
            text = unquote(m.group(1))
    return re.sub(r"[\s\-]+", "", text).upper()


def decode_totp_secret_bytes(secret: str) -> bytes:
    text = normalize_totp_secret(secret)
    if not text:
        raise ValueError("2FA 密钥为空")
    cleaned = re.sub(r"[^A-Z2-7]", "", text)
    if len(cleaned) < 8:
        raise ValueError("2FA 密钥无效（Base32 过短）")
    pad = (-len(cleaned)) % 8
    try:
        return base64.b32decode(cleaned + ("=" * pad), casefold=True)
    except Exception as exc:
        raise ValueError(f"2FA 密钥不是有效 Base32：{exc}") from exc


def generate_totp_code(
    secret: str,
    *,
    for_time: float | None = None,
    digits: int = 6,
    period: int = 30,
) -> str:
    key = decode_totp_secret_bytes(secret)
    safe_digits = max(6, min(8, int(digits or 6)))
    safe_period = max(1, int(period or 30))
    ts = time.time() if for_time is None else float(for_time)
    counter = int(ts // safe_period)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code_int = struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF
    return str(code_int % (10**safe_digits)).zfill(safe_digits)


def totp_remaining_seconds(*, for_time: float | None = None, period: int = 30) -> int:
    safe_period = max(1, int(period or 30))
    ts = time.time() if for_time is None else float(for_time)
    return safe_period - (int(ts) % safe_period)
