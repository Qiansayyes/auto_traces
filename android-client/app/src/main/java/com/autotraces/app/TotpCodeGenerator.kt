package com.autotraces.app

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

/**
 * RFC 6238 / Google Authenticator 兼容 TOTP（HMAC-SHA1，默认 6 位 / 30 秒）。
 * 设备端本地生成，无需请求 2fa.run 等云端。
 */
object TotpCodeGenerator {
    data class Result(
        val code: String,
        val remainingSec: Int,
        val period: Int = 30,
    )

    fun normalizeSecret(secret: String?): String {
        var text = secret?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (text.contains("otpauth://", ignoreCase = true) && text.contains("secret=", ignoreCase = true)) {
            val m = Regex("""(?i)(?:^|[?&])secret=([^&]+)""").find(text)
            if (m != null) {
                text = try {
                    java.net.URLDecoder.decode(m.groupValues[1], Charsets.UTF_8.name())
                } catch (_: Exception) {
                    m.groupValues[1]
                }
            }
        }
        // 账号表可能原样存带 '-' 的 Setup Key（如 V5F2-LWEL-...）；有 '-' 则去掉后再算 TOTP，无 '-' 则仅去空白。
        return if (text.contains('-')) {
            text.replace(Regex("""[\s\-]+"""), "").uppercase()
        } else {
            text.replace(Regex("""\s+"""), "").uppercase()
        }
    }

    fun generate(
        secret: String,
        forTimeMs: Long = System.currentTimeMillis(),
        digits: Int = 6,
        periodSec: Int = 30,
    ): Result {
        val key = decodeBase32(normalizeSecret(secret))
        if (key.isEmpty()) {
            throw IllegalArgumentException("2FA 密钥不是有效 Base32")
        }
        val safeDigits = max(6, min(8, digits))
        val safePeriod = max(1, periodSec)
        val counter = (forTimeMs / 1000L) / safePeriod
        val msg = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val digest = mac.doFinal(msg)
        val offset = digest[digest.size - 1].toInt() and 0x0f
        val binary =
            ((digest[offset].toInt() and 0x7f) shl 24) or
                ((digest[offset + 1].toInt() and 0xff) shl 16) or
                ((digest[offset + 2].toInt() and 0xff) shl 8) or
                (digest[offset + 3].toInt() and 0xff)
        var modulus = 1
        repeat(safeDigits) { modulus *= 10 }
        val code = (binary % modulus).toString().padStart(safeDigits, '0')
        val remaining = safePeriod - ((forTimeMs / 1000L) % safePeriod).toInt()
        return Result(code = code, remainingSec = remaining, period = safePeriod)
    }

    /** RFC 4648 Base32（A-Z2-7），忽略非法字符与 padding。 */
    private fun decodeBase32(input: String): ByteArray {
        val cleaned = input.replace(Regex("""[^A-Z2-7]"""), "")
        if (cleaned.length < 8) {
            throw IllegalArgumentException("2FA 密钥无效（Base32 过短）")
        }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>((cleaned.length * 5) / 8 + 1)
        for (ch in cleaned) {
            val value = alphabet.indexOf(ch)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
