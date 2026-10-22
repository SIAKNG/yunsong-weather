package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class UserApiConfigurationTest {
    private fun caiyun() = UserApiConfiguration.Config(caiyunToken = "test_dummy_token")
    @Test fun emptyPrimaryCannotBeSaved() { assertNotNull(UserApiConfiguration.validate(UserApiConfiguration.Config())) }
    @Test fun caiyunVersionsAreExplicit() {
        assertNull(UserApiConfiguration.validate(caiyun()))
        assertNull(UserApiConfiguration.validate(caiyun().copy(caiyunVersion = "2.7")))
        assertNotNull(UserApiConfiguration.validate(caiyun().copy(caiyunVersion = "3")))
    }
    @Test fun tokenCannotInjectUrlOrQuery() {
        listOf("abc/def", "abc?foo=1", "abc\nxyz").forEach { assertNotNull(UserApiConfiguration.validate(caiyun().copy(caiyunToken = it))) }
    }
    @Test fun onlyAccountOfficialHostIsAccepted() {
        listOf("http://test.qweatherapi.com", "test.qweatherapi.com.evil.test", "test.qweatherapi.com/path", "127.0.0.1", "user@host.qweatherapi.com").forEach {
            assertNotNull(UserApiConfiguration.validate(caiyun().copy(qweatherHost = it)))
        }
        assertNull(UserApiConfiguration.validate(caiyun().copy(qweatherHost = "https://test.qweatherapi.com/")))
    }
    @Test fun qweatherNeedsHostAndOneAuthenticationMethod() {
        val q = UserApiConfiguration.Config(source = WeatherSettings.SOURCE_QWEATHER, qweatherHost = "test.qweatherapi.com", qweatherApiKey = "dummy_key")
        assertNull(UserApiConfiguration.validate(q))
        assertNotNull(UserApiConfiguration.validate(q.copy(qweatherHost = "")))
        assertNotNull(UserApiConfiguration.validate(q.copy(qweatherPem = "dummy_pem")))
        assertNotNull(UserApiConfiguration.validate(q.copy(qweatherApiKey = "")))
    }
    @Test fun keyAuthenticationNeverAddsBearer() {
        assertEquals(mapOf("X-QW-Api-Key" to "dummy"), QWeatherApi.authenticationHeaders("api-key:dummy"))
        assertEquals(mapOf("Authorization" to "Bearer dummy.jwt"), QWeatherApi.authenticationHeaders("dummy.jwt"))
    }
    @Test fun flavorBuildFieldsEnforceDistributionBoundary() {
        if (BuildConfig.USER_CONFIGURABLE) {
            assertEquals("", BuildConfig.CAIYUN_EMBEDDED_TOKEN)
            assertEquals("", BuildConfig.LEGACY_QWEATHER_HOST)
            assertEquals("", BuildConfig.LEGACY_QWEATHER_KID)
            assertEquals("", BuildConfig.LEGACY_QWEATHER_SUB)
            assertEquals("", BuildConfig.LEGACY_QWEATHER_PRIVATE_KEY_PEM)
            assertEquals("", BuildConfig.WEATHER_GATEWAY_BASE_URL)
            assertEquals("", AmapKey.KEY)
            assertFalse(WeatherGatewayApi.isConfigured())
            assertTrue(BuildConfig.APPLICATION_ID.endsWith(".userapi"))
        } else {
            assertTrue(BuildConfig.CAIYUN_EMBEDDED_TOKEN.isNotBlank())
            assertTrue(BuildConfig.LEGACY_QWEATHER_PRIVATE_KEY_PEM.isNotBlank())
            assertTrue(AmapKey.KEY.isNotBlank())
        }
    }
}
