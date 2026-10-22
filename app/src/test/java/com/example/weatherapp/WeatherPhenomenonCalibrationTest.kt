package com.example.weatherapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherPhenomenonCalibrationTest {
    private val now = 1_720_000_000_000L

    @Test
    fun cloudIsCorrectedWhenIndependentSourceReportsRain() {
        val decision = WeatherPhenomenonCalibration.decide(
            primarySkycon = "CLOUDY",
            referenceSkycon = "MODERATE_RAIN",
            referenceTimeMillis = now,
            nowMillis = now
        )

        assertTrue(decision.applied)
        assertEquals("MODERATE_RAIN", decision.skycon)
    }

    @Test
    fun clearReferenceDoesNotRewriteCloudForecast() {
        val decision = WeatherPhenomenonCalibration.decide(
            primarySkycon = "PARTLY_CLOUDY_DAY",
            referenceSkycon = "CLEAR_DAY",
            referenceTimeMillis = now,
            nowMillis = now
        )

        assertFalse(decision.applied)
        assertEquals("PARTLY_CLOUDY_DAY", decision.skycon)
    }

    @Test
    fun explicitPrimaryRainIsNeverOverwritten() {
        val decision = WeatherPhenomenonCalibration.decide(
            primarySkycon = "LIGHT_RAIN",
            referenceSkycon = "FOG",
            referenceTimeMillis = now,
            nowMillis = now
        )

        assertFalse(decision.applied)
        assertEquals("LIGHT_RAIN", decision.skycon)
    }

    @Test
    fun staleReferenceIsIgnored() {
        val decision = WeatherPhenomenonCalibration.decide(
            primarySkycon = "OVERCAST",
            referenceSkycon = "FOG",
            referenceTimeMillis = now - 91L * 60L * 1000L,
            nowMillis = now
        )

        assertFalse(decision.applied)
        assertEquals("OVERCAST", decision.skycon)
    }
}
