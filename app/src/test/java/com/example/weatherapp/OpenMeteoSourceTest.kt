package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class OpenMeteoSourceTest {
    private val weather="""{"current":{"time":1792886400,"temperature_2m":20,"apparent_temperature":19,"weather_code":0,"is_day":0,"relative_humidity_2m":60,"surface_pressure":1001,"visibility":12000,"wind_speed_10m":8,"wind_direction_10m":90,"uv_index":0},"hourly":{"time":[1792882800,1792886400,1792890000],"temperature_2m":[18,20,21],"apparent_temperature":[17,19,null],"relative_humidity_2m":[58,60,62],"surface_pressure":[1000,1001,1002],"visibility":[10000,12000,14000],"uv_index":[0,0,1],"weather_code":[0,0,2],"is_day":[0,0,1],"precipitation_probability":[0,10,20],"precipitation":[0,0.1,0.2]},"daily":{"time":[1792857600],"temperature_2m_min":[10],"temperature_2m_max":[23],"weather_code":[2],"sunrise":[1792881000],"sunset":[1792924200]}}"""
    @Test fun blankOrIncompleteLegacyConfigurationHasNoPrivateCredentialRequirement() {
        for(source in listOf("auto","caiyun","qweather")) assertEquals("open_meteo",UserApiConfiguration.Config(source=source).effectiveSource())
    }
    @Test fun personalCredentialsAlwaysExcludeFreeProvider() {
        val c=UserApiConfiguration.Config(caiyunToken="dummy",qweatherHost="test.qweatherapi.com",qweatherApiKey="dummy")
        assertEquals("caiyun",c.effectiveSource())
        assertEquals("qweather",c.copy(source="qweather").effectiveSource())
        assertEquals("qweather",c.copy(caiyunToken="").effectiveSource())
        assertEquals("caiyun",c.copy(qweatherApiKey="").effectiveSource())
    }
    @Test fun coordinatesAreRoundedAndNoSecretsOrIdentifiersAreSent() {
        val url=OpenMeteoSource.weatherUrl(39.90423456,116.40745678)
        assertTrue(url.startsWith("https://api.open-meteo.com/"))
        assertTrue(url.contains("latitude=39.90&longitude=116.41"))
        assertFalse(url.contains("token"));assertFalse(url.contains("device"));assertFalse(url.contains("key="))
    }
    @Test fun normalizesUnitsAndPreservesNullsInsteadOfFabricatingValues() {
        val r=OpenMeteoSource.parse(weather,null,1792886400000).result!!
        assertEquals("CLEAR_NIGHT",r.realtime!!.skycon)
        assertEquals(0.6,r.realtime!!.humidity!!,0.0001)
        assertEquals(100100.0,r.realtime!!.pressure!!,0.001)
        assertEquals(12.0,r.realtime!!.visibility!!,0.001)
        assertNull(r.hourly!!.apparentTemperature!!.last().value)
        assertNull(r.hourly!!.airQuality);assertNull(r.alert);assertNull(r.minutely)
        assertNull(r.daily!!.lifeIndex)
        assertEquals(listOf(18.0,20.0,21.0),r.hourly!!.temperature!!.map {it.value})
    }
    @Test fun airFailureDoesNotInvalidateWeather() { assertNotNull(OpenMeteoSource.parse(weather,"{}",1792886400000).result!!.realtime) }
    @Test fun invalidCoreDataFailsInsteadOfReturningSuccessfulEmptyWeather() {
        assertThrows(Exception::class.java) {OpenMeteoSource.parse("{}",null)}
    }
    @Test fun absentAirDataAndUnsupportedFeaturesAreReportedHonestly() {
        val rows=OpenMeteoSource.coverage(OpenMeteoSource.parse(weather,null),1792886400000)
        for(label in listOf("雷达分钟降雨","官方灾害预警","官方生活指数","AQI与六项污染物"))
            assertEquals(ApiSupport.MISSING,rows.first {it.feature==label}.support)
    }
    @Test fun wmoMappingIsDayNightAwareAndUnknownStaysUnknown() {
        assertEquals("PARTLY_CLOUDY_NIGHT",OpenMeteoSource.skycon(2,false))
        assertEquals("HEAVY_RAIN",OpenMeteoSource.skycon(65,true))
        assertNull(OpenMeteoSource.skycon(999,true))
    }
}
