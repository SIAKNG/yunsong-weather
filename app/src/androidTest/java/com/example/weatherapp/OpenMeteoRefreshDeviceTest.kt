package com.example.weatherapp

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoRefreshDeviceTest {
    @Test fun progressiveWeatherRefreshProvidesValidForecastAndAQuickRepeat() {
        val first = fetchBeijing()
        assertTrue(first in 0..15_000)
        val second = fetchBeijing()
        assertTrue("Cached foreground repeat took ${second}ms", second <= maxOf(1_000, first))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("open_meteo_beijing", "weather first=${first}ms, repeat=${second}ms")
        })
    }

    private fun fetchBeijing(): Long {
        val started = SystemClock.elapsedRealtime()
        val done = CountDownLatch(1)
        var primary: GatewayResult<CaiyunResponse>? = null
        OpenMeteoSource.enqueue(39.904, 116.407, { received ->
            primary = received
            done.countDown()
        }, {}, includeAir = true)
        assertTrue("Open-Meteo main request did not finish", done.await(16, TimeUnit.SECONDS))
        assertTrue("Open-Meteo did not return usable weather", primary?.isSuccessful == true)
        val body = requireNotNull(primary?.body?.result)
        assertNotNull(body.realtime?.temperature)
        assertTrue(body.hourly?.temperature.orEmpty().size >= 48)
        assertTrue(body.daily?.temperature.orEmpty().size >= 15)
        return SystemClock.elapsedRealtime() - started
    }
}
