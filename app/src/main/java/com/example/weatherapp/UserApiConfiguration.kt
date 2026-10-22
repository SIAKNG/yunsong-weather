package com.example.weatherapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-flavor credentials never enter resources or BuildConfig. */
object UserApiConfiguration {
    const val PREFS = "user_api_credentials"
    private const val ALIAS = "weather_user_api_aes_v1"
    private var cachedBlob: String? = null
    private var cachedConfig: Config? = null
    data class Config(
        val source: String = WeatherSettings.SOURCE_CAIYUN,
        val caiyunToken: String = "",
        val caiyunVersion: String = "2.6",
        val qweatherHost: String = "",
        val qweatherApiKey: String = "",
        val qweatherKid: String = "",
        val qweatherSub: String = "",
        val qweatherPem: String = "",
        val amapKey: String = ""
    ) {
        override fun toString(): String = "UserApiConfiguration.Config(source=$source, credentials=<redacted>)"
        fun hasQWeather(): Boolean = qweatherHost.isNotBlank() && qweatherApiKey.isNotBlank()
        fun hasPrimary(): Boolean = if (source == WeatherSettings.SOURCE_QWEATHER) hasQWeather() else caiyunToken.isNotBlank()
        // Retain legacy fields when reading encrypted data, but never send them from this distribution.
        fun jwtConfig() = QWeatherJwt.Config(qweatherHost, "", "", "", qweatherApiKey)
    }

    fun validate(config: Config): String? {
        if (config.source !in listOf(WeatherSettings.SOURCE_CAIYUN, WeatherSettings.SOURCE_QWEATHER)) return "请选择天气源"
        if (config.caiyunVersion !in listOf("2.6", "2.7")) return "彩云版本无效"
        if (config.caiyunToken.isNotEmpty() && !config.caiyunToken.matches(Regex("[A-Za-z0-9_-]+"))) return "彩云 Token 格式无效"
        if (config.qweatherHost.isNotBlank()) {
            val host = config.qweatherHost.removePrefix("https://").trimEnd('/')
            if (host.length > 253 || !host.matches(Regex("(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+qweatherapi\\.com"))) return "请填写控制台分配的和风 API Host（仅支持官方 HTTPS 域名）"
        }
        if (config.qweatherApiKey.any { it.isWhitespace() || it.code < 32 }) return "和风 API Key 格式无效"
        if (config.qweatherKid.isNotBlank() || config.qweatherSub.isNotBlank() || config.qweatherPem.isNotBlank()) return "自填版仅支持和风 API Key，请在配置页改用 API Key"
        if (!config.hasPrimary()) return "请填写所选主天气源的完整配置"
        if (config.amapKey.isNotEmpty() && !config.amapKey.matches(Regex("[A-Za-z0-9]+"))) return "高德 Key 格式无效"
        return null
    }

    @Synchronized fun read(context: Context): Config {
        if (!BuildConfig.USER_CONFIGURABLE) return Config()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val blob = prefs.getString("encrypted", null) ?: return Config()
        if (blob == cachedBlob) cachedConfig?.let { return it }
        val result = runCatching {
            val parts = blob.split('.', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            Gson().fromJson(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8), Config::class.java) ?: Config()
        }.getOrElse { Config() }
        cachedBlob = blob
        cachedConfig = result
        return result
    }

    @Synchronized fun save(context: Context, config: Config) {
        check(BuildConfig.USER_CONFIGURABLE)
        require(validate(config) == null) { "接口配置无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(Gson().toJson(config).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(prefs.edit().putString("encrypted", blob).putLong("revision", revision(context) + 1).commit())
        invalidate(context)
    }

    @Synchronized fun clear(context: Context) {
        check(BuildConfig.USER_CONFIGURABLE)
        val next = revision(context) + 1
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().putLong("revision", next).commit()
        invalidate(context)
    }
    fun revision(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("revision", 0)
    private fun invalidate(context: Context) {
        cachedBlob = null
        cachedConfig = null
        WeatherCache.clearCaiyun(context)
        context.getSharedPreferences("city_summary_cache", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("qweather_life_advice_v1", Context.MODE_PRIVATE).edit().clear().apply()
        WeatherSettings.resetCaiyunMinutelyCapability(context)
        QWeatherJwt.clearCache()
    }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
