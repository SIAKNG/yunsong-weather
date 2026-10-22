package com.example.weatherapp

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Lightweight GET client for the non-Retrofit weather endpoints. */
object WeatherHttp {
    private const val CONNECT_TIMEOUT_MS = 7_000
    private const val READ_TIMEOUT_MS = 7_000
    private const val MAX_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 350L

    private class HttpStatusException(code: Int, body: String) : IOException(
        "HTTP $code${if (body.isBlank()) "" else "：$body"}"
    )

    /**
     * Retries only transient network and 5xx failures. 4xx failures are returned immediately,
     * so an invalid token or request never creates a retry storm.
     */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                if (BuildConfig.USER_CONFIGURABLE && headers.isNotEmpty()) connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

                val code = connection.responseCode
                if (code in 200..299) {
                    val stream = if (connection.getHeaderField("Content-Encoding")
                            ?.contains("gzip", ignoreCase = true) == true
                    ) {
                        GZIPInputStream(connection.inputStream)
                    } else {
                        connection.inputStream
                    }
                    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }

                val errorBody = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText().take(200) }
                    .orEmpty()
                val error = HttpStatusException(code, if (BuildConfig.USER_CONFIGURABLE) "" else errorBody)
                if (code !in 500..599 || attempt == MAX_ATTEMPTS - 1) throw error
                lastError = error
            } catch (error: HttpStatusException) {
                // 4xx 是确定性请求错误；最后一次 5xx 也应立即向上层报告。
                throw error
            } catch (error: IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw error
                lastError = error
            } finally {
                connection.disconnect()
            }

            try {
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Request interrupted", interrupted)
            }
        }
        throw lastError ?: IOException("Request failed")
    }
}
