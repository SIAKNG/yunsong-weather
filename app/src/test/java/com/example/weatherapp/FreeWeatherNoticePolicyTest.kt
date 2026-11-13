package com.example.weatherapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeWeatherNoticePolicyTest {
    @Test fun freeWeatherRemindsOncePerEntry() {
        assertTrue(FreeWeatherNoticePolicy.shouldShow("open_meteo", false, true, null))
        assertTrue(FreeWeatherNoticePolicy.shouldShow("open_meteo", false, true, true))
        assertFalse(FreeWeatherNoticePolicy.shouldShow("open_meteo", false, false, true))
    }

    @Test fun clearingPersonalWeatherRemindsWithinSameEntry() {
        assertTrue(FreeWeatherNoticePolicy.shouldShow("open_meteo", false, false, false))
    }

    @Test fun optOutAndPersonalSourcesSuppressNotice() {
        assertFalse(FreeWeatherNoticePolicy.shouldShow("open_meteo", true, true, false))
        for (source in listOf("caiyun", "qweather")) {
            assertFalse(FreeWeatherNoticePolicy.shouldShow(source, false, true, false))
        }
    }
}
