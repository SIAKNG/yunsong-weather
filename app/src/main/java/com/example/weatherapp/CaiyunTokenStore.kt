package com.example.weatherapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first

private val Context.caiyunTokenDataStore by preferencesDataStore(name = "caiyun_token_store")

/**
 * 用户自行输入的彩云 Token：密文落到 DataStore，AES 密钥只保存在 Android Keystore。
 * 不使用生物认证锁定，以保证用户主动开启的后台天气任务仍然可读取该 Token。
 */
object CaiyunTokenStore {
    private const val KEY_ALIAS = "weatherapp_caiyun_token_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SEPARATOR = "."
    private val tokenBlob = stringPreferencesKey("encrypted_token_v1")

    suspend fun read(context: Context): String? {
        val blob = context.applicationContext.caiyunTokenDataStore.data.first()[tokenBlob] ?: return null
        return runCatching { decrypt(blob) }.getOrNull()
    }

    suspend fun save(context: Context, token: String) {
        val value = token.trim()
        require(value.isNotEmpty()) { "Token 不能为空" }
        context.applicationContext.caiyunTokenDataStore.edit { prefs ->
            prefs[tokenBlob] = encrypt(value)
        }
    }

    suspend fun clear(context: Context) {
        context.applicationContext.caiyunTokenDataStore.edit { it.remove(tokenBlob) }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(SEPARATOR, limit = 2)
        require(parts.size == 2) { "无效的 Token 密文" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            javax.crypto.spec.GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }
}
