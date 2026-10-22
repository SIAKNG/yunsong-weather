package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class WeatherReminderPolicyTest {
    private fun time(value: String) = LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun midnightRetainsPreviousEveningWindowUntilSeven() {
        val evening = WeatherReminderPolicy.window(time("2026-09-06T22:00"))
        assertEquals(evening, WeatherReminderPolicy.window(time("2026-09-07T06:59")))
        val morning = WeatherReminderPolicy.window(time("2026-09-07T07:00"))
        assertFalse(morning.tomorrow)
        assertEquals(evening.forecastDate, morning.forecastDate)
        assertNotEquals(evening.key, morning.key)
    }

    @Test fun compareCorrespondingDailyExtremesAtFiveDegreesWithoutIndexMatching() {
        val city = CityPage("测试", 0.0, 0.0, false)
        val today = LocalDate.parse("2026-09-06")
        fun daily(high: Double, low: Double) = Daily(temperature = listOf(
            DailyTemp("2026-09-07", high, low), DailyTemp("2026-09-05", 50.0, -10.0),
            DailyTemp("2026-09-06", 30.0, 20.0)))
        assertNull(WeatherReminderPolicy.temperatureChange(city, daily(34.4, 15.6), today))
        assertTrue(WeatherReminderPolicy.temperatureChange(city, daily(35.0, 20.0), today)!!.detail.contains("最高温升高5℃"))
        assertTrue(WeatherReminderPolicy.temperatureChange(city, daily(30.0, 15.0), today)!!.detail.contains("最低温降低5℃"))
        assertNull(WeatherReminderPolicy.temperatureChange(city, Daily(), today))
    }

    @Test fun temperatureReminderStartsAtPreviousNightNineAndRetriesThroughForecastDay() {
        val target = time("2026-09-07T00:00")
        assertFalse(WeatherReminderPolicy.isTemperatureReminderDue(target, time("2026-09-06T20:59")))
        assertTrue(WeatherReminderPolicy.isTemperatureReminderDue(target, time("2026-09-06T21:00")))
        assertTrue(WeatherReminderPolicy.isTemperatureReminderDue(target, time("2026-09-07T23:59")))
        assertFalse(WeatherReminderPolicy.isTemperatureReminderDue(target, time("2026-09-08T00:00")))
    }

    @Test fun rainTitleUsesCurrentDistrictBeforeCity() {
        val current = CityPage("北京市", 116.4, 39.9, true, "朝阳区 望京街道")
        val saved = CityPage("南京市", 118.8, 32.0, false, "玄武区")
        assertEquals("朝阳区", current.preciseRainNotificationLocation())
        assertEquals("南京市", saved.preciseRainNotificationLocation())
    }
}
