package com.example.weatherapp

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherDiskStorageDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun retainsAndCompressesValidWeatherAndRemovesExpiredOrExcessEntries() {
        val now = System.currentTimeMillis()
        val home = context.getSharedPreferences("weather_home_display", Context.MODE_PRIVATE)
        val forecast = context.getSharedPreferences("forecast_day_history_v1", Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences("qa_storage_sentinel", Context.MODE_PRIVATE)
        val scope = WeatherSettings.sourceKey(context)
        val keep = "${scope}_location"
        val stale = "${scope}_stale"
        val payload = """{"message":"${"天气预报".repeat(4200)}"}"""
        synchronized(home) {
            val editor = home.edit()
            editor.putLong("${keep}_at", now).putString("${keep}_json", payload)
            editor.putLong("${stale}_at", now - 31L * 24L * 60L * 60_000L).putString("${stale}_json", payload)
            repeat(130) { i ->
                val cell = "${scope}_preview_$i"
                editor.putLong("${cell}_at", now - i - 50_000L).putString("${cell}_json", payload)
            }
            assertTrue(editor.commit())
        }
        val hours = List(500) { "{}" }.joinToString(",")
        forecast.edit()
            .remove("qa_fresh@at")
            .remove("qa_stale@at")
            .putString("qa_fresh", """{"savedAt":$now,"hours":[$hours]}""")
            .putString("qa_stale", """{"savedAt":${now - 4L * 24 * 60 * 60_000L},"hours":[]}""")
            .commit()
        settings.edit().putString("keep", "untouched").commit()

        WeatherDiskCache.pruneNow(context, now)

        val stored = requireNotNull(home.getString("${keep}_json", null))
        assertTrue(stored.length < payload.length / 4)
        assertEquals(payload, WeatherDiskCache.unpack(stored))
        assertFalse(home.contains("${stale}_json"))
        assertTrue(home.all.keys.count { it.endsWith("_json") } <= 96)
        assertTrue(forecast.getLong("qa_fresh@at", 0L) == now)
        assertFalse(forecast.contains("qa_stale"))
        assertEquals("untouched", settings.getString("keep", null))
    }

    @Test fun movingWithinOneAreaReusesOneLocationHistory() {
        val now = System.currentTimeMillis()
        val hour = now / ForecastDetailData.HOUR * ForecastDetailData.HOUR
        val first = CityPage("北京", 116.401, 39.904, true)
        val near = first.copy(lng = 116.402)
        val far = first.copy(lng = 121.5)
        ForecastDayHistoryCache.save(context, first,
            listOf(ForecastHour(hour, "CLEAR_DAY", 16.0, null, null, null, null, null)))
        assertEquals(16.0, ForecastDayHistoryCache.load(context, near).single { it.time == hour }.temperature)
        assertTrue(ForecastDayHistoryCache.load(context, far).isEmpty())
        ForecastDayHistoryCache.save(context, near,
            listOf(ForecastHour(hour + ForecastDetailData.HOUR, "CLEAR_DAY", 18.0, null, null, null, null, null)))
        val keys = context.getSharedPreferences("forecast_day_history_v1", Context.MODE_PRIVATE).all.keys
        assertEquals(1, keys.count { it == WeatherSettings.sourceKey(context) + "_location" })
        assertEquals(listOf(16.0, 18.0), ForecastDayHistoryCache.load(context, near).map { it.temperature })
    }
}
