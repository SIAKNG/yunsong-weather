package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class WeatherWidgetSummaryTest {
    private val noon=Calendar.getInstance().apply {set(2026,10,4,12,0,0);set(Calendar.MILLISECOND,0)}.timeInMillis
    private fun data(rows: List<ForecastHour>,daily: ForecastDaySnapshot?=null)=WidgetWeather(CityPage("北京",116.407,39.904,false),"open_meteo",noon,noon,23.0,24.0,"PARTLY_CLOUDY_DAY",64.0,12.0,38.0,emptyList(),emptyList(),forecast=rows,today=daily)
    private fun hour(offset: Int,temp: Double?,feel: Double?=null,wind: Double?=null,uv: Double?=null,aqi: Double?=null,rain: Double?=null)=ForecastHour(ForecastDayHistoryPolicy.startOfDay(noon)+offset*ForecastDetailData.HOUR,"CLOUDY",temp,feel,wind,uv,64.0,aqi,precipitationProbability=rain)
    @Test fun dailyWindowCannotIncludeTomorrowOrReplaceMissingFeelsWithRealtime() {
        val s=WeatherWidgetSummary.build(data(listOf(hour(0,15.0),hour(23,26.0),hour(24,40.0))),noon)
        assertEquals(15.0 to 26.0,s.temperature);assertNull(s.feels);assertFalse(s.complete)
    }
    @Test fun dailyForecastProbabilityHasPriorityAndNoInventedPeakHour() {
        val d=data(listOf(hour(5,15.0,rain=.1),hour(17,26.0,rain=.7)),ForecastDaySnapshot("2026-11-04","CLOUDY",26.0,15.0,90))
        val s=WeatherWidgetSummary.build(d,noon)
        assertEquals(90,s.rain);assertNull(s.rainHour);assertEquals(ForecastSummaryAdvice.dailyRain(90),s.rainAdvice)
    }
    @Test fun dailyRangesAndAdviceUseSameSourcesAsDetail() {
        val d=data(listOf(hour(0,-6.0,-10.0,8.0,.0,38.0,.0),hour(12,1.0,-2.0,24.0,4.2,82.0,.72)))
        val s=WeatherWidgetSummary.build(d,noon)
        assertEquals(-6.0 to 1.0,s.temperature);assertEquals(-10.0 to -2.0,s.feels)
        assertEquals(8.0 to 24.0,s.wind);assertEquals(38.0 to 82.0,s.air)
        assertEquals(4.2,s.ultraviolet!!.uv!!,0.0)
        assertEquals(ForecastSummaryAdvice.wind(24.0),s.windAdvice)
        assertEquals(ForecastSummaryAdvice.ultraviolet(4.2),s.uvAdvice)
        assertEquals(ForecastSummaryAdvice.air(82.0),s.airAdvice)
    }
    @Test fun invalidFieldsRemainUnavailableAndTemplatesRetainTheirGrid() {
        val s=WeatherWidgetSummary.build(data(listOf(hour(1,Double.NaN,Double.POSITIVE_INFINITY))),noon)
        assertNull(s.temperature);assertNull(s.feels);assertNull(s.air);assertNull(s.rain)
        assertEquals(ForecastSummaryAdvice.MISSING,s.airAdvice)
        assertEquals(2 to 2,WeatherWidgetPolicy.size("air"));assertEquals(2 to 2,WeatherWidgetPolicy.size("rain"))
        assertEquals(4 to 4,WeatherWidgetPolicy.size("daily_summary"))
    }
    @Test fun astronomicalDayNightAndCurrentIconStayConsistentWithCachedSkyCodes() {
        assertEquals("PARTLY_CLOUDY_NIGHT",WeatherInfoWidgetRenderer.displaySky(data(emptyList()).copy(night=true)))
        assertEquals("CLEAR_NIGHT",WeatherInfoWidgetRenderer.displaySky(data(emptyList()).copy(night=true,sky="CLEAR_DAY")))
        assertEquals("CLEAR_DAY",WeatherInfoWidgetRenderer.displaySky(data(emptyList()).copy(night=false,sky="CLEAR_NIGHT")))
        assertEquals("LIGHT_RAIN",WeatherInfoWidgetRenderer.displaySky(data(emptyList()).copy(night=true,sky="LIGHT_RAIN")))
    }
}
