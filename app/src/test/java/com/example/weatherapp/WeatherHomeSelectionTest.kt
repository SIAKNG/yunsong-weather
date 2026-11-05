package com.example.weatherapp

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherHomeSelectionTest {
    @Test fun locationWinsOverPreviouslySelectedCityAndListOrder() {
        val a = CityPage("北京", 116.4, 39.9, false)
        val b = CityPage("上海", 121.4, 31.2, true)
        assertEquals(1, WeatherHomeSelection.defaultIndex(listOf(a, b)))
    }
    @Test fun missingLocationUsesLeftmostCity() {
        assertEquals(0, WeatherHomeSelection.defaultIndex(listOf(CityPage("北京", 116.4, 39.9, false))))
        assertEquals(0, WeatherHomeSelection.defaultIndex(emptyList()))
    }
}
