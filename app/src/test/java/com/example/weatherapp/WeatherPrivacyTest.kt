package com.example.weatherapp

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test

class WeatherPrivacyTest {
    @Test fun credentialObjectsNeverPrintSecrets() {
        val user=UserApiConfiguration.Config(caiyunToken="fixture-private-token",qweatherHost="fixture.qweatherapi.com",
            qweatherApiKey="fixture-private-key",qweatherPem="fixture-private-pem",amapKey="fixture-amap")
        val jwt=QWeatherJwt.Config("fixture.qweatherapi.com","fixture-kid","fixture-sub","fixture-private-pem","fixture-private-key")
        listOf(user.toString(),jwt.toString()).forEach {text->
            assertTrue(text.contains("redacted"));assertFalse(text.contains("fixture"))
        }
        assertEquals("fixture-private-token",user.copy().caiyunToken)
        assertEquals(user,user.copy())
    }
    @Test fun networkErrorsDoNotExposeUrlsBodiesOrCoordinates() {
        val sensitive="https://fixture.example/path?key=fixture-private-key&location=116.4,39.9"
        listOf(IOException(sensitive),IllegalArgumentException(sensitive),SSLException(sensitive),
            SocketTimeoutException(sensitive),UnknownHostException(sensitive)).forEach {error->
            val text=WeatherPrivacy.requestFailure(error)
            assertFalse(text.contains("fixture"));assertFalse(text.contains("116.4"));assertFalse(text.contains("https"))
            assertTrue(text.isNotBlank())
        }
    }
    @Test fun httpProblemsStillHaveActionableDistinctDescriptions() {
        assertTrue(WeatherPrivacy.requestFailure(IOException("HTTP 401: fixture-private-key")).contains("凭证"))
        assertTrue(WeatherPrivacy.requestFailure(IOException("HTTP 403: fixture-private-key")).contains("权限"))
        assertTrue(WeatherPrivacy.requestFailure(IOException("HTTP 429: fixture-private-key")).contains("频率"))
        assertTrue(WeatherPrivacy.requestFailure(IOException("HTTP 503: fixture-private-key")).contains("暂时"))
        assertFalse(WeatherPrivacy.requestFailure(IOException("HTTP 401: fixture-private-key")).contains("fixture"))
    }
}
