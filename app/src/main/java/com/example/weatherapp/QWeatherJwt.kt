package com.example.weatherapp

import android.util.Base64
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap

/**
 * 旧版和风 JWT 签发器，仅用于本机直连恢复。
 * 发布版迁移到共享网关后，应删除该文件和 eddsa 依赖。
 */
object QWeatherJwt {
    data class Config(
        val host: String,
        val kid: String,
        val sub: String,
        val privateKeyPem: String,
        val apiKey: String = ""
    ) {
        override fun toString(): String = "QWeatherJwt.Config(credentials=<redacted>)"
    }

    private data class CachedToken(val value: String, val expiresAtSeconds: Long)
    private val cache = ConcurrentHashMap<String, CachedToken>()

    fun clearCache() = cache.clear()

    fun token(config: Config): String {
        if (config.apiKey.isNotBlank()) return "api-key:${config.apiKey}"
        require(config.privateKeyPem.isNotBlank()) { "和风 JWT 私钥未配置" }
        val now = System.currentTimeMillis() / 1000L
        val cacheKey = "${config.kid}:${config.sub}"
        cache[cacheKey]?.takeIf { it.expiresAtSeconds - now > 60 }?.let { return it.value }

        val header = base64Url("{\"alg\":\"EdDSA\",\"kid\":\"${config.kid}\"}".toByteArray())
        val expiresAt = now + 900L
        val payload = base64Url("{\"sub\":\"${config.sub}\",\"iat\":${now - 30L},\"exp\":$expiresAt}".toByteArray())
        val signingInput = "$header.$payload"
        val signer = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        signer.initSign(EdDSAPrivateKey(PKCS8EncodedKeySpec(pemToDer(config.privateKeyPem))))
        signer.update(signingInput.toByteArray(Charsets.UTF_8))
        val result = "$signingInput.${base64Url(signer.sign())}"
        cache[cacheKey] = CachedToken(result, expiresAt)
        return result
    }

    private fun pemToDer(pem: String): ByteArray {
        val base64 = pem
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s"), "")
        return Base64.decode(base64, Base64.DEFAULT)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
