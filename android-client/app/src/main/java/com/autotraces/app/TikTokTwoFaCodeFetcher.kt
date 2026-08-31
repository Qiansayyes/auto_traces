package com.autotraces.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按 2FA 密钥获取当前 TOTP 验证码。
 * 优先本地计算；本地无密钥时再请求服务端（服务端同样本地生成，不向 2fa.run 索取）。
 */
object TikTokTwoFaCodeFetcher {
    suspend fun fetchCode(
        deviceId: String,
        alias: String,
        secret: String = "",
        username: String = "",
    ): Result<String> {
        val localSecret = TotpCodeGenerator.normalizeSecret(secret)
        if (localSecret.isNotBlank()) {
            return runCatching {
                TotpCodeGenerator.generate(localSecret).code
            }
        }
        if (deviceId.isBlank() || alias.isBlank()) {
            return Result.failure(IllegalStateException("2FA 密钥为空且设备未绑定，无法代取"))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val resp = ApiService.getTikTokTwoFaCodeFromServer(deviceId, alias, username = username, secret = "")
                if (resp.code != 0) {
                    throw IllegalStateException(resp.msg.ifBlank { "云端代取 2FA 失败" })
                }
                val code = resp.two_fa_code?.trim().orEmpty()
                if (!code.matches(Regex("""\d{6,8}"""))) {
                    throw IllegalStateException("云端未返回有效 2FA 验证码")
                }
                code
            }
        }
    }
}
