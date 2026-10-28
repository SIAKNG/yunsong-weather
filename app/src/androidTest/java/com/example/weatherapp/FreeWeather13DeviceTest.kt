package com.example.weatherapp

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario

class FreeWeather13DeviceTest {
    @Test fun liveFreeSourceKeepsEveryHourOfTodayWithoutCredentials() {
        val ins=InstrumentationRegistry.getInstrumentation()
        // ColorOS can freeze an instrumented process with no foreground Activity.
        // Keep the real app visible while testing a foreground weather load.
        ActivityScenario.launch(WeatherAboutActivity::class.java).use {
        val done=CountDownLatch(1)
        var response:CaiyunResponse?=null
        val start=SystemClock.elapsedRealtime()
        var primaryMs=0L
        OpenMeteoSource.enqueue(39.90,116.41,{ result ->
            response=result.body;primaryMs=SystemClock.elapsedRealtime()-start;done.countDown()
        },{})
        assertTrue("Free source completes within its network timeout",done.await(25,TimeUnit.SECONDS))
        val body=requireNotNull(response) {"Free-source network request failed on the connected phone"}
        assertNotNull(body.result?.realtime?.temperature)
        val rows=ForecastDayHistoryCache.fromCaiyun(requireNotNull(body.result))
        val today=DailyForecastDetailPolicy.dateKey(System.currentTimeMillis())
        assertEquals(24,rows.count {DailyForecastDetailPolicy.dateKey(it.time)==today && it.temperature!=null})
        ins.sendStatus(0,android.os.Bundle().apply {
            putString("free_source_load","primaryMs=$primaryMs todayHours=24 totalHours=${rows.size}")
        })
        }
    }
}
