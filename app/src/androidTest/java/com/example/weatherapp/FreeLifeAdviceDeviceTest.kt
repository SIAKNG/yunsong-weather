package com.example.weatherapp

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Uses the already-selected free source; never clears or substitutes personal credentials. */
class FreeLifeAdviceDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun texts(view: View): List<String> = buildList {
        if(view is TextView) add(view.text.toString())
        if(view is ViewGroup) repeat(view.childCount) {addAll(texts(view.getChildAt(it)))}
    }

    @Test fun freeAdviceDetailShowsUnsupportedInsteadOfProviderOrRefreshErrors() {
        assertTrue("Select free source before this explicit device regression",WeatherSettings.useOpenMeteo(context))
        for(items in listOf(emptyList(),listOf(LifeAdviceItem("q1","运动","","")))) {
            ActivityScenario.launch<LifeAdviceActivity>(LifeAdviceActivity.createIntent(context,items)).use { scenario ->
                scenario.onActivity { a ->
                    val copy=texts(a.findViewById(R.id.lifeAdviceContent))
                    assertTrue(copy.contains("当前不支持"))
                    assertFalse(copy.any {it.contains("和风") || it.contains("下拉刷新")})
                }
            }
        }
    }

    @Test fun allSixDetailFootersShowUnsupportedAndPreserveAvailableAdvice() {
        assertTrue("Select free source before this explicit device regression",WeatherSettings.useOpenMeteo(context))
        val point=HourlyMetricPoint(System.currentTimeMillis(),20.0,20.0,
            uv=2.0,windKmh=8.0,pressureHpa=1010.0,visibilityKm=12.0,humidityPercent=55.0)
        for(kind in MetricKind.entries) {
            ActivityScenario.launch<MetricDetailActivity>(MetricDetailActivity.createIntent(context,kind,listOf(point),cityName="北京")).use { scenario ->
                scenario.onActivity { a ->
                    val strip=a.findViewById<LifeAdviceStripView>(R.id.metricLifeAdviceStrip)
                    assertEquals("当前不支持",strip.contentDescription.toString())
                    strip.setAdvice(listOf(LifeAdviceItem("q1","运动","适宜","适量运动")),WeatherSettings.isDarkTheme(a))
                    assertEquals("运动：适宜",strip.contentDescription.toString())
                    strip.setAdvice(emptyList(),WeatherSettings.isDarkTheme(a))
                    assertEquals("当前不支持",strip.contentDescription.toString())
                }
            }
        }
    }
}
