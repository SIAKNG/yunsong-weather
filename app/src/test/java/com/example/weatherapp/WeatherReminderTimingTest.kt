package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WeatherReminderTimingTest {
    private fun at(s: String) = LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    @Test fun repeatedChecksKeepDeadlineButFiredExpiredOrClockChangedAlarmsAdvance() {
        val n = at("2026-11-03T06:00")
        val interval = 900_000L
        assertEquals(n + interval, WeatherReminderTiming.recoveryAt(n + 120_000L, n + interval, interval, false))
        assertEquals(n + interval, WeatherReminderTiming.recoveryAt(n, 0L, interval, false))
        assertEquals(n + interval, WeatherReminderTiming.recoveryAt(n, n - 1, interval, false))
        assertEquals(n + interval, WeatherReminderTiming.recoveryAt(n, n + 2 * interval, interval, false))
        assertEquals(n + interval, WeatherReminderTiming.recoveryAt(n, n + 10, interval, true))
    }
    @Test fun preparationHasThreeIndependentSlotsBeforeDeliveryAndRollsAcrossDates() {
        val n = at("2026-11-03T06:00")
        assertEquals(listOf(at("2026-11-03T06:40"), at("2026-11-03T20:40"), at("2026-11-03T21:40")), WeatherReminderTiming.preparationTimes(n, true, true))
        assertEquals(listOf(at("2026-11-04T20:40")), WeatherReminderTiming.preparationTimes(at("2026-11-03T20:41"), false, true))
        assertTrue(WeatherReminderTiming.preparationTimes(n, false, false).isEmpty())
    }
    @Test fun refreshOnlyBecomesFrequentAroundEnabledReminderWindows() {
        assertTrue(WeatherReminderTiming.nearDelivery(at("2026-11-03T06:40"), true, false))
        assertTrue(WeatherReminderTiming.nearDelivery(at("2026-11-03T20:40"), false, true))
        assertTrue(WeatherReminderTiming.nearDelivery(at("2026-11-03T21:40"), true, false))
        assertFalse(WeatherReminderTiming.nearDelivery(at("2026-11-03T12:00"), true, true))
        assertFalse(WeatherReminderTiming.nearDelivery(at("2026-11-03T20:40"), true, false))
    }
}
