package com.example.weatherapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaiyunWeatherHistoryPolicyTest {

    @Test
    fun historyWithMissingUvRetainsPreviouslySavedForecastUv() {
        val now = 1_800_000_000_000L
        val forecast = CaiyunHourlyWeatherSnapshot(now - 3_600_000L, temperature = 22.0, uv = 4.2, capturedAtMillis = now - 86_400_000L)
        val actual = forecast.copy(temperature = 21.0, uv = null, capturedAtMillis = now)
        val merged = CaiyunWeatherHistoryPolicy.mergeHourly(listOf(forecast), listOf(actual), now).single()
        assertEquals(21.0, merged.temperature!!, 0.0)
        assertEquals(4.2, merged.uv!!, 0.0)
    }

    @Test
    fun newerCaiyunHourReplacesSameHourAndExpiredSnapshotIsDropped() {
        val now = 1_800_000_000_000L
        val hour = now - 3_600_000L
        val stale = CaiyunHourlyWeatherSnapshot(
            hour - 3_600_000L, 20.0, "CLEAR_DAY", 0.0, 2.0,
            now - CaiyunWeatherHistoryPolicy.MAX_AGE_MS - 1L
        )
        val old = CaiyunHourlyWeatherSnapshot(hour, 22.0, "CLOUDY", 0.2, 3.0, now - 4_000L)
        val fresh = CaiyunHourlyWeatherSnapshot(hour, 23.0, "LIGHT_RAIN", 0.6, 4.0, now)

        val result = CaiyunWeatherHistoryPolicy.mergeHourly(listOf(stale, old), listOf(fresh), now)

        assertEquals(1, result.size)
        assertEquals(23.0, result.single().temperature!!, 0.0)
        assertEquals(4.0, result.single().uv!!, 0.0)
    }

    @Test
    fun currentDailyResponseWinsButOlderCachedDayRemains() {
        val now = 1_800_000_000_000L
        val cachedYesterday = CaiyunDailyWeatherSnapshot("2027-01-01", "CLEAR_DAY", 20, 10, 0, now - 1_000L)
        val cachedToday = CaiyunDailyWeatherSnapshot("2027-01-02", "CLOUDY", 21, 11, 10, now - 1_000L)
        val incomingToday = CaiyunDailyWeatherSnapshot("2027-01-02", "LIGHT_RAIN", 19, 12, 80, now)

        val result = CaiyunWeatherHistoryPolicy.mergeDaily(
            listOf(cachedYesterday, cachedToday), listOf(incomingToday), now
        )

        assertEquals(listOf("2027-01-01", "2027-01-02"), result.map { it.date })
        assertEquals("LIGHT_RAIN", result.last().skycon)
        assertTrue(result.first().capturedAtMillis < result.last().capturedAtMillis)
    }
}
