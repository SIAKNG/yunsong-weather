package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class PublicWeatherIconRendererTest {
    @Test fun publicWeatherFallbacksCoverCoreConditions() {
        val cases = mapOf("tq-1.svg" to "SUN", "yewan.svg" to "MOON", "duoyun.svg" to "CLOUD",
            "xiaoyu.svg" to "RAIN", "baoxue.svg" to "SNOW", "yujiaxue.svg" to "SLEET",
            "leizhenyu.svg" to "STORM", "mai.svg" to "FOG", "dafeng.svg" to "WIND")
        cases.forEach { (name,expected) -> assertEquals(expected,PublicWeatherIconRenderer.kind(name).name) }
    }
    @Test fun sunEventsKeepDifferentFallbackSemantics() {
        assertEquals(PublicWeatherIconRenderer.Kind.SUNRISE,PublicWeatherIconRenderer.kind("richu.svg"))
        assertEquals(PublicWeatherIconRenderer.Kind.SUNSET,PublicWeatherIconRenderer.kind("riluo.svg"))
    }
    @Test fun unknownAndLegacyNamesAreSafe() {
        assertEquals(PublicWeatherIconRenderer.Kind.RAIN,PublicWeatherIconRenderer.kind("icon_xiaoyu.svg"))
        assertEquals(PublicWeatherIconRenderer.Kind.CLOUD,PublicWeatherIconRenderer.kind("unknown.svg"))
    }
}
