package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

/** Offline synthetic JSON only: no endpoints, credentials, account or real location. */
class WeatherRiskParsingDeviceTest {
    @Test fun minuteFailuresNeverBecomeVerifiedDryResponses() {
        val invalid = listOf(
            "{}", "not-json", """{"code":"403","minutely":[]}""",
            """{"code":"200"}""", """{"code":"200","minutely":[]}""",
            """{"code":"200","error":{},"minutely":[]}""",
            """{"code":"200","minutely":[{"fxTime":"invalid","precip":"0"}]}""",
            """{"code":"200","minutely":[{"fxTime":"2026-11-03T12:00+08:00","precip":"NaN"}]}""",
            """{"code":"200","minutely":[{"fxTime":"2026-11-03T12:00+08:00","precip":"-1"}]}"""
        )
        invalid.forEach { assertTrue(runCatching { QWeatherApi.parseMinutelyResponseChecked(it) }.isFailure) }
    }

    @Test fun verifiedMinuteResponsePreservesZeroAndNonzeroAmounts() {
        val minutes = QWeatherApi.parseMinutelyResponseChecked("""{"code":"200","minutely":[
            {"fxTime":"2026-11-03T12:00+08:00","precip":"0","type":"rain"},
            {"fxTime":"2026-11-03T12:05+08:00","precip":"0.1","type":"rain"}]}""")
        assertEquals(listOf(0.0, 0.1), minutes.map { it.precip })
        assertEquals(5L * 60_000L, minutes[1].timeMillis - minutes[0].timeMillis)
    }

    @Test fun explicitAlertErrorsCannotClearLedgerEvenWithEmptyAlerts() {
        assertTrue(runCatching { QWeatherApi.parseAlertsResponse("""{"error":{"status":403},"alerts":[]}""") }.isFailure)
        assertTrue(runCatching { QWeatherApi.parseAlertsResponse("""{"code":"403","alerts":[]}""") }.isFailure)
        assertTrue(QWeatherApi.parseAlertsResponse("""{"metadata":{"zeroResult":true}}""").isEmpty())
        assertTrue(QWeatherApi.parseAlertsResponse("""{"alerts":[]}""").isEmpty())
    }
}
