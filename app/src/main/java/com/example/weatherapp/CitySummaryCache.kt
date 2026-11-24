package com.example.weatherapp

import android.content.Context
import java.util.Locale

/**
 * 城市管理页专用的轻量实况摘要缓存。
 * 只保存列表显示所需的原始摄氏温度、天气文字和天气现象，避免每次进入管理页为每座城市重复请求。
 */
object CitySummaryCache {
    private const val PREFS = "city_summary_cache"

    data class Snapshot(
        val temperatureC: Double,
        val weather: String,
        val skycon: String,
        val savedAtMillis: Long
    )

    fun load(context: Context, lat: Double, lng: Double): Snapshot? {
        if (!lat.isFinite() || !lng.isFinite()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(context, lat, lng)
        val savedAt = prefs.getLong("${key}_at", 0L)
        val age = System.currentTimeMillis() - savedAt
        if (savedAt <= 0L || !WeatherCachePolicy.volatileUsable(age)) {
            if (savedAt > 0L) {
                prefs.edit()
                    .remove("${key}_at")
                    .remove("${key}_temp")
                    .remove("${key}_weather")
                    .remove("${key}_skycon")
                    .apply()
            }
            return null
        }
        if (!prefs.contains("${key}_temp")) return null
        return Snapshot(
            temperatureC = Double.fromBits(prefs.getLong("${key}_temp", 0L)),
            weather = prefs.getString("${key}_weather", "").orEmpty(),
            skycon = prefs.getString("${key}_skycon", "").orEmpty(),
            savedAtMillis = savedAt
        )
    }

    fun save(
        context: Context,
        lat: Double,
        lng: Double,
        temperatureC: Double?,
        weather: String,
        skycon: String
    ) {
        val temp = temperatureC?.takeIf { it.isFinite() } ?: return
        if (!lat.isFinite() || !lng.isFinite()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(context, lat, lng)
        WeatherDiskCache.write(context, prefs) {
            putLong("${key}_at", System.currentTimeMillis())
            putLong("${key}_temp", temp.toBits())
            putString("${key}_weather", weather)
            putString("${key}_skycon", skycon)
        }
    }

    private fun key(context: Context, lat: Double, lng: Double): String = String.format(
        Locale.US,
        "summary_%s_%.3f_%.3f",
        sourceKey(context), lat, lng
    )

    private fun sourceKey(context: Context): String = WeatherSettings.sourceKey(context)
}
