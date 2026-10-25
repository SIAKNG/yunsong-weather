package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class FreeWeatherNoticePolicyTest {
    @Test fun firstFreeEntryShowsNotice() {
        assertTrue(FreeWeatherNoticePolicy.shouldShow("open_meteo","r0:0",null))
    }
    @Test fun refreshingOrChangingCityDoesNotRepeatNotice() {
        assertFalse(FreeWeatherNoticePolicy.shouldShow("open_meteo","r0:0","r0:0"))
    }
    @Test fun clearingOrReturningFromConfigurationShowsItAgain() {
        assertTrue(FreeWeatherNoticePolicy.shouldShow("open_meteo","r1:0","r0:0"))
        assertTrue(FreeWeatherNoticePolicy.shouldShow("open_meteo","r0:1","r0:0"))
    }
    @Test fun personalSourcesNeverShowFreeSourceNotice() {
        for (source in listOf("caiyun","qweather"))
            assertFalse(FreeWeatherNoticePolicy.shouldShow(source,"r1:1",null))
    }
}
