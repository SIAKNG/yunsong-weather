package com.example.weatherapp
import org.junit.Assert.*
import org.junit.Test
class WeatherWidgetPolicyTest {
    @Test fun forecastHourCaptionDoesNotInheritRefreshMinuteAndCrossesMidnight() {
        val c=java.util.Calendar.getInstance().apply{set(2026,10,2,20,37,0)}
        assertEquals("20:00",WeatherWidgetRenderer.forecastHourLabel(c.timeInMillis))
        c.set(java.util.Calendar.HOUR_OF_DAY,23)
        assertEquals("23:00",WeatherWidgetRenderer.forecastHourLabel(c.timeInMillis))
        c.add(java.util.Calendar.HOUR_OF_DAY,1)
        assertEquals("00:00",WeatherWidgetRenderer.forecastHourLabel(c.timeInMillis))
    }
    @Test fun fiveTemplatesUseColumnsThenRows() {
        assertEquals(2 to 1,WeatherWidgetPolicy.size("compact"))
        assertEquals(2 to 2,WeatherWidgetPolicy.size("square"))
        assertEquals(4 to 2,WeatherWidgetPolicy.size("wide"))
        assertEquals(4 to 2,WeatherWidgetPolicy.size("weekly"))
        assertEquals(4 to 4,WeatherWidgetPolicy.size("large"))
    }
    @Test fun oldObservationNeverBecomesFreshByResaving() {
        val now=10_000_000L
        assertTrue(WeatherWidgetPolicy.stale(now-700_000,now,now))
        assertFalse(WeatherWidgetPolicy.stale(now-60_000,now-5_000,now))
        assertTrue(WeatherWidgetPolicy.stale(now,now-700_000,now))
    }
    @Test fun futureTimestampIsNotDisplayedAsCurrent() {
        assertFalse(WeatherWidgetPolicy.usableObservation(0,1000))
        assertFalse(WeatherWidgetPolicy.usableObservation(1_000_000,1000))
        assertTrue(WeatherWidgetPolicy.usableObservation(1000,1000))
    }
    @Test fun badCoordinatesCannotReachProvider() {
        assertFalse(WeatherWidgetPolicy.validCity(CityPage("bad",Double.NaN,0.0,false)))
        assertFalse(WeatherWidgetPolicy.validCity(CityPage("bad",120.0,91.0,false)))
        assertTrue(WeatherWidgetPolicy.validCity(CityPage("北京",116.407,39.904,false)))
    }
    @Test fun sourceLabelsNeverContainCredentials() {
        assertEquals("Open-Meteo",WeatherWidgetData.sourceName("open_meteo"))
        assertEquals("和风天气",WeatherWidgetData.sourceName("qweather"))
        assertEquals("彩云天气",WeatherWidgetData.sourceName("caiyun"))
    }
    @Test fun futureForecastDatesExcludeTodayAndCrossMonthCorrectly() {
        val now=java.util.Calendar.getInstance().apply{set(2026,9,30,12,0,0)}.timeInMillis
        val dates=WeatherWidgetPolicy.futureDates(7,now)
        assertEquals(7,dates.size)
        assertEquals("2026-10-31",dates.first())
        assertEquals("2026-11-06",dates.last())
        assertFalse(dates.contains("2026-10-30"))
    }

}
