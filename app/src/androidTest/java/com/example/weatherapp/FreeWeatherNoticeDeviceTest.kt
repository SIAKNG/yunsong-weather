package com.example.weatherapp

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.*
import org.junit.Test

/** Simulates source events in memory; does not modify the user's encrypted API configuration. */
class FreeWeatherNoticeDeviceTest {
    private fun dialog(notice: FreeWeatherNotice?): AlertDialog? =
        FreeWeatherNotice::class.java.getDeclaredField("dialog").apply {isAccessible=true}.get(notice) as? AlertDialog

    @Test fun acknowledgementSurvivesARecreatedWeatherEntry() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("free_weather_notice",android.content.Context.MODE_PRIVATE)
        val original=prefs.getString("acknowledged",null)
        try {
            ActivityScenario.launch(WeatherSettingsActivity::class.java).use { scenario ->
                var notice: FreeWeatherNotice?=null
                scenario.onActivity {a ->
                    notice=FreeWeatherNotice(a,{"open_meteo"},{"persistence-regression"})
                    notice!!.maybeShow()
                    assertTrue(dialog(notice)?.isShowing==true)
                    dialog(notice)!!.findViewById<android.view.View>(R.id.freeWeatherNoticeDismiss)!!.performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {a ->
                    notice!!.dispose()
                    notice=FreeWeatherNotice(a,{"open_meteo"},{"persistence-regression"}).apply {restore(null);maybeShow()}
                    assertNull(dialog(notice))
                    notice!!.dispose()
                }
            }
        } finally {
            if(original==null) prefs.edit().remove("acknowledged").commit()
            else prefs.edit().putString("acknowledged",original).commit()
        }
    }

    @Test fun firstEntryAndConfigurationReturnButNotRefreshOrPersonalSource() {
        ActivityScenario.launch(WeatherSettingsActivity::class.java).use { scenario ->
            var notice: FreeWeatherNotice?=null
            var source="open_meteo"
            var event="r0:0"
            scenario.onActivity { a ->
                notice=FreeWeatherNotice(a,{source},{event},{})
                notice!!.maybeShow()
            }
            scenario.onActivity {
                assertTrue(dialog(notice)?.isShowing==true)
                dialog(notice)!!.findViewById<android.view.View>(R.id.freeWeatherNoticeDismiss)!!.performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                notice!!.maybeShow()
                assertNull(dialog(notice))
            }
            // Rotation restoration does not create a second reminder.
            scenario.onActivity { a ->
                val state=Bundle();notice!!.save(state);notice!!.dispose()
                notice=FreeWeatherNotice(a,{source},{event},{}).apply {restore(state);maybeShow()}
            }
            scenario.onActivity {assertNull(dialog(notice))}
            // Return without a configured API, then clearing a configured API.
            for(next in listOf("r0:1","r1:2")) {
                event=next
                scenario.onActivity {
                    notice!!.maybeShow()
                    assertTrue(dialog(notice)?.isShowing==true)
                    dialog(notice)!!.findViewById<android.view.View>(R.id.freeWeatherNoticeDismiss)!!.performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            for(personal in listOf("caiyun","qweather")) {
                source=personal;event="r2:3"
                scenario.onActivity {notice!!.maybeShow();assertNull(dialog(notice))}
            }
            scenario.onActivity {notice!!.dispose()}
        }
    }
}
