package com.example.weatherapp

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Repeatable regression: inspecting/recovering schedules must not postpone an existing wake-up. */
class NotificationAlarmReproductionTest {
    @Test fun repeatedRecoveryChecksKeepOriginalDeadline() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val daily = WeatherSettings.dailyForecastReminder(c)
        val rain = WeatherSettings.rainReminder(c)
        try {
            WeatherSettings.setDailyForecastReminder(c, true)
            WeatherSettings.setRainReminder(c, true)
            WeatherRecoveryAlarm.schedule(c, replace = true)
            WeatherRiskRecoveryAlarm.schedule(c, replace = true)
            val firstDaily = WeatherRecoveryAlarm.nextScheduledAt(c)
            val firstRisk = WeatherRiskRecoveryAlarm.nextScheduledAt(c)
            Thread.sleep(180)
            WeatherRecoveryAlarm.schedule(c)
            WeatherRiskRecoveryAlarm.schedule(c)
            assertEquals("Repeated recovery checks postponed the daily alarm", firstDaily, WeatherRecoveryAlarm.nextScheduledAt(c))
            assertEquals("Repeated recovery checks postponed the risk alarm", firstRisk, WeatherRiskRecoveryAlarm.nextScheduledAt(c))
        } finally {
            WeatherSettings.setDailyForecastReminder(c, daily)
            WeatherSettings.setRainReminder(c, rain)
            WeatherRecoveryAlarm.schedule(c, replace = true)
            WeatherRiskRecoveryAlarm.schedule(c, replace = true)
        }
    }
    @Test fun widgetRecoveryRechecksWithoutPostponingOrChangingWidgetConfiguration() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val ids=WeatherWidgetStore.ids(c)
        val before=ids.associateWith { WeatherWidgetStore.city(c,it) }
        WeatherWidgetRecoveryAlarm.schedule(c)
        val at=WeatherWidgetRecoveryAlarm.nextAt(c)
        Thread.sleep(80)
        WeatherWidgetRecoveryAlarm.schedule(c)
        assertEquals(at,WeatherWidgetRecoveryAlarm.nextAt(c))
        assertEquals(ids,WeatherWidgetStore.ids(c))
        assertEquals(before,ids.associateWith { WeatherWidgetStore.city(c,it) })
        assertEquals(ids.isNotEmpty(),at>System.currentTimeMillis())
    }
}
