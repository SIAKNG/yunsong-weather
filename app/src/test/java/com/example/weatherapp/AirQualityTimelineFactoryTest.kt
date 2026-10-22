package com.example.weatherapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirQualityTimelineFactoryTest {

    @Test
    fun qweatherOnlyFillsMissingCaiyunPollutants() {
        val time = 1_788_000_000_000L
        val caiyun = AirQualityTimeline(
            AirQualitySource.CAIYUN,
            time,
            realtime = null,
            hourly = listOf(
                AirQualityPoint(time, aqi = 42.0, pm25 = 18.0, pm10 = null, source = AirQualitySource.CAIYUN)
            )
        )
        val qweather = AirQualityTimeline(
            AirQualitySource.QWEATHER,
            time,
            realtime = null,
            hourly = listOf(
                AirQualityPoint(
                    time, aqi = 88.0, pm25 = 35.0, pm10 = 51.0, no2 = 12.0,
                    source = AirQualitySource.QWEATHER
                )
            )
        )

        val point = AirQualityTimelineFactory.mergeCaiyunWithQWeather(caiyun, qweather).hourly.single()

        assertEquals(42.0, point.aqi!!, 0.0)
        assertEquals(18.0, point.pm25!!, 0.0)
        assertEquals(51.0, point.pm10!!, 0.0)
        assertEquals(12.0, point.no2!!, 0.0)
        assertEquals(AirQualitySource.QWEATHER, point.fieldSources["pm10"])
    }

    @Test
    fun existingCaiyunPollutantIsNeverOverwritten() {
        val time = 1_788_000_000_000L
        val caiyun = AirQualityTimeline(
            AirQualitySource.CAIYUN, time, null,
            listOf(AirQualityPoint(time, pm10 = 36.0, source = AirQualitySource.CAIYUN))
        )
        val qweather = AirQualityTimeline(
            AirQualitySource.QWEATHER, time, null,
            listOf(AirQualityPoint(time, pm10 = 90.0, source = AirQualitySource.QWEATHER))
        )

        val point = AirQualityTimelineFactory.mergeCaiyunWithQWeather(caiyun, qweather).hourly.single()

        assertEquals(36.0, point.pm10!!, 0.0)
        assertNull(point.fieldSources["pm10"])
    }
}
