package com.example.weatherapp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Simulates source and entry changes without modifying the user's encrypted API configuration. */
class FreeWeatherNoticeDeviceTest {
    private fun dialog(notice: FreeWeatherNotice): AlertDialog? =
        FreeWeatherNotice::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(notice) as? AlertDialog

    @Test fun eachFreeEntryCanShowOnceAndOptOutSuppressesLaterEntries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(WeatherSettingsActivity::class.java).use { scenario ->
            lateinit var notice: FreeWeatherNotice
            var source = WeatherSettings.SOURCE_OPEN_METEO
            var session = 1L
            var hidden = false

            fun dismiss() {
                scenario.onActivity {
                    assertTrue(dialog(notice)?.isShowing == true)
                    dialog(notice)!!.findViewById<View>(R.id.freeWeatherNoticeDismiss)!!.performClick()
                }
                instrumentation.waitForIdleSync()
            }
            fun evaluate(expected: Boolean) {
                scenario.onActivity {
                    notice.maybeShow()
                    assertEquals(expected, dialog(notice)?.isShowing == true)
                }
            }

            scenario.onActivity { activity ->
                notice = FreeWeatherNotice(activity, { source }, { session }, { hidden })
            }
            evaluate(true)
            dismiss()
            evaluate(false)

            scenario.onActivity { activity ->
                val state = Bundle()
                notice.save(state)
                notice.dispose()
                notice = FreeWeatherNotice(activity, { source }, { session }, { hidden }).apply { restore(state) }
            }
            evaluate(false)

            session++
            evaluate(true)
            dismiss()

            source = WeatherSettings.SOURCE_CAIYUN
            evaluate(false)
            source = WeatherSettings.SOURCE_OPEN_METEO
            evaluate(true)
            dismiss()

            hidden = true
            session++
            evaluate(false)
            scenario.onActivity { notice.dispose() }
        }
    }
}
