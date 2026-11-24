package com.example.weatherapp

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Maintenance only knows about regenerable weather stores, never settings or credentials. */
internal object WeatherDiskCache {
    private const val PREFIX = "gz1:"
    private const val MAX_EXPANDED = 4 * 1024 * 1024
    private const val HOUR = 3_600_000L
    private val savedAtPattern = Regex("""["]savedAt(?:Millis)?["]\s*:\s*(\d+)""")
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "weather-cache-prune")
    }
    private var scheduled = false
    private var writes = 0
    private var nextCleanup = 0L

    private enum class Format { PAYLOAD, RECORD, SUMMARY, ADVICE }

    private data class Store(
        val name: String, val format: Format, val maxAge: Long, val entries: Int, val maxChars: Int
    )

    private val stores = listOf(
        Store("weather_response_cache", Format.PAYLOAD, WeatherCachePolicy.MAX_PAYLOAD_MS, 64, 3_000_000),
        Store("weather_home_display", Format.PAYLOAD, WeatherCachePolicy.MAX_PAYLOAD_MS, 96, 3_500_000),
        Store("forecast_day_history_v1", Format.RECORD, 72 * HOUR, 48, 1_500_000),
        Store("caiyun_weather_history_snapshot_v1", Format.RECORD, 72 * HOUR, 48, 1_000_000),
        Store("metric_history_timeline", Format.RECORD, 36 * HOUR, 64, 750_000),
        Store("city_summary_cache", Format.SUMMARY, WeatherCachePolicy.VOLATILE_MS, 80, 240_000),
        Store("qweather_life_advice_v1", Format.ADVICE, 48 * HOUR, 96, 450_000)
    )

    internal fun pack(json: String): String {
        if (json.length < 1024 || json.startsWith(PREFIX)) return json
        val raw = json.toByteArray(Charsets.UTF_8)
        if (raw.size > MAX_EXPANDED) return json
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        val packed = PREFIX + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return if (packed.length < json.length) packed else json
    }

    internal fun unpack(stored: String?): String? {
        if (stored == null || !stored.startsWith(PREFIX)) return stored
        return runCatching {
            val input = ByteArrayInputStream(Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP))
            val output = ByteArrayOutputStream()
            GZIPInputStream(input).use {
                val chunk = ByteArray(8192)
                while (true) {
                    val size = it.read(chunk)
                    if (size < 0) break
                    check(output.size() + size <= MAX_EXPANDED)
                    output.write(chunk, 0, size)
                }
            }
            output.toString("UTF-8")
        }.getOrNull()
    }

    internal fun write(context: Context, prefs: SharedPreferences, commit: Boolean = false, edit: SharedPreferences.Editor.() -> Unit) {
        synchronized(prefs) {
            val editor = prefs.edit().apply(edit)
            if (commit) editor.commit() else editor.apply()
        }
        schedule(context, afterWrite = true)
    }

    @Synchronized
    fun schedule(context: Context, afterWrite: Boolean = false) {
        if (afterWrite) writes++
        val now = SystemClock.elapsedRealtime()
        if (scheduled || (now < nextCleanup && writes < 32)) return
        scheduled = true
        writes = 0
        nextCleanup = now + 15 * 60_000L
        val app = context.applicationContext
        worker.execute {
            try {
                pruneNow(app)
            } catch (_: Exception) {
                Log.w("WeatherStorage", "Cache maintenance will retry")
            } finally {
                synchronized(this) { scheduled = false }
            }
        }
    }

    private data class Entry(
        val key: String, val at: Long, val keys: List<String>, val payload: String? = null,
        val stamp: String? = null
    )

    internal fun pruneNow(context: Context, now: Long = System.currentTimeMillis()) {
        val cities = (
            WeatherCityStore.cities(context) + runCatching {
                WeatherWidgetStore.ids(context).flatMap { WeatherWidgetStore.cities(context, it) }
            }.getOrDefault(emptyList())
        )
        val coordinates = cities.flatMap { city ->
            listOf(
                String.format(Locale.US, "%.3f_%.3f", city.lat, city.lng),
                String.format(Locale.US, "%.4f_%.4f", city.lat, city.lng),
                String.format(Locale.US, "%.3f,%.3f", city.lat, city.lng)
            )
        }.toSet()
        val scope = WeatherSettings.sourceKey(context)
        var removed = 0
        var savedChars = 0L
        for (store in stores) {
            val prefs = context.getSharedPreferences(store.name, Context.MODE_PRIVATE)
            val values = prefs.all
            val entries = collect(store.format, values).sortedWith(
                compareByDescending<Entry> { scope in it.key && ("_location" in it.key || coordinates.any(it.key::contains)) }
                    .thenByDescending { it.at }
            )
            var kept = 0
            var used = 0L
            val replacements = mutableListOf<Pair<Entry, String?>>()
            val deletions = mutableListOf<Entry>()
            for (entry in entries) {
                if (entry.at <= 0 || now - entry.at !in -WeatherCachePolicy.CLOCK_SKEW_MS..store.maxAge || kept >= store.entries) {
                    deletions += entry
                    continue
                }
                val raw = entry.payload?.let { values[it] as? String }
                val new = if (store.format == Format.PAYLOAD || store.format == Format.RECORD) raw?.let(::pack) else raw
                val size = (new?.length ?: 0) + entry.keys.sumOf { key ->
                    if (key == entry.payload) 0 else (values[key] as? String)?.length ?: 16
                } + 160L
                if (used + size > store.maxChars) {
                    deletions += entry
                    continue
                }
                used += size
                kept++
                if (new != null && (new != raw || (entry.stamp != null && !values.containsKey(entry.stamp)))) {
                    replacements += entry to new
                }
            }

            if (deletions.isEmpty() && replacements.isEmpty()) continue
            synchronized(prefs) {
                val current = prefs.all
                fun same(entry: Entry) = entry.keys.all { current[it] == values[it] }
                val editor = prefs.edit()
                deletions.filter(::same).forEach { entry ->
                    removed++
                    savedChars += entry.keys.sumOf { (values[it] as? String)?.length ?: 0 }.toLong()
                    entry.keys.forEach(editor::remove)
                }
                replacements.filter { same(it.first) }.forEach { (entry, encoded) ->
                    val payload = requireNotNull(entry.payload)
                    savedChars += (values[payload] as String).length - requireNotNull(encoded).length
                    editor.putString(payload, encoded)
                    entry.stamp?.let { editor.putLong(it, entry.at) }
                }
                editor.commit()
            }
        }
        clearOldFiles(File(context.cacheDir, "app-updates"), "yunsong-update-", ".apk", now - 48 * HOUR)
        if (removed > 0 || savedChars > 0) {
            Log.i("WeatherStorage", "Cleaned cache entries=$removed savedCharacters=$savedChars")
        }
    }

    private fun collect(format: Format, values: Map<String, *>): List<Entry> {
        fun time(key: String): Long = values[key] as? Long ?: 0L
        return when (format) {
            Format.PAYLOAD -> values.keys.filter { it.endsWith("_json") }.map { payload ->
                val key = payload.removeSuffix("_json")
                Entry(key, time("${key}_at"), listOf(payload, "${key}_at", "${key}_q", "${key}_lat", "${key}_lng"), payload)
            }
            Format.RECORD -> values.entries.mapNotNull { (key, raw) ->
                if (raw !is String) return@mapNotNull null
                val stamp = "$key@at"
                val at = time(stamp).takeIf { it > 0 }
                    ?: savedAtPattern.find(raw.take(192))?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                Entry(key, at, listOf(key, stamp, "$key@lat", "$key@lng"), key, stamp)
            }
            Format.SUMMARY -> values.keys.filter { it.startsWith("summary_") && it.endsWith("_at") }.map { stamp ->
                val key = stamp.removeSuffix("_at")
                Entry(key, time(stamp), listOf(stamp, "${key}_temp", "${key}_weather", "${key}_skycon"))
            }
            Format.ADVICE -> values.keys.map { it.removePrefix("attempt:").removePrefix("success:") }.distinct().map { key ->
                Entry(key, maxOf(time("attempt:$key"), time("success:$key")),
                    listOf(key, "attempt:$key", "success:$key"), key)
            }
        }
    }

    private fun clearOldFiles(folder: File, prefix: String, extension: String, olderThan: Long) {
        folder.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(prefix) && file.name.endsWith(extension) &&
                file.lastModified() in 1 until olderThan) file.delete()
        }
    }
}
