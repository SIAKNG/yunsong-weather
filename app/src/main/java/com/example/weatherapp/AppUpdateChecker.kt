package com.example.weatherapp

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Bounded background request with a six-hour success cache and ten-minute retry backoff. */
class AppUpdateChecker(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("public_release_check", Context.MODE_PRIVATE)

    fun check(): AppUpdatePolicy.Result {
        val now = System.currentTimeMillis()
        val saved = runCatching { AppUpdatePolicy.Status.valueOf(prefs.getString("status", "").orEmpty()) }.getOrNull()
        val checkedAt = prefs.getLong("checked_at", 0L)
        val ttl = if (saved == AppUpdatePolicy.Status.CURRENT || saved == AppUpdatePolicy.Status.NEW_VERSION) 6 * 60 * 60_000L else 10 * 60_000L
        if (saved != null && now >= checkedAt && now - checkedAt < ttl &&
            prefs.getInt("installed_code", -1) == BuildConfig.VERSION_CODE) {
            return AppUpdatePolicy.Result(saved, prefs.getString("version", "").orEmpty())
        }
        var connection: HttpURLConnection? = null
        val result = try {
            connection = URL(AppUpdatePolicy.API_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "YunsongWeather/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            val body = if (code == 200) connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(128 * 1024 + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count < buffer.size) { "Release response too large" }
                String(buffer, 0, count)
            } else ""
            AppUpdatePolicy.evaluate(code, body, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        } catch (_: Exception) {
            AppUpdatePolicy.Result(AppUpdatePolicy.Status.FAILED)
        } finally {
            connection?.disconnect()
        }
        // Failed checks replace a stale badge; do not claim an old result is current.
        prefs.edit().putString("status", result.status.name).putString("version", result.version)
            .putLong("checked_at", now).putInt("installed_code", BuildConfig.VERSION_CODE).apply()
        return result
    }
}
