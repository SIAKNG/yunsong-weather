package com.example.weatherapp

import android.appwidget.*
import android.content.ComponentName
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class WidgetFirstConfigurationDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val c get() = ins.targetContext

    @Test fun hostConfigureAfterProviderUpdateStillLeavesNewWidgetUnconfigured() {
        val host = AppWidgetHost(c, 8852)
        val manager = AppWidgetManager.getInstance(c)
        val id = host.allocateAppWidgetId()
        try {
            ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(c, WeatherSquareWidget::class.java)))
            ins.uiAutomation.dropShellPermissionIdentity()
            // Reproduce the real callback race: onUpdate has already registered this ID.
            WeatherWidgetStore.register(c, id)
            val intent = android.content.Intent(c, WeatherWidgetActivity::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            ActivityScenario.launch<WeatherWidgetActivity>(intent).use {
                assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, it.state)
            }
            assertFalse(WeatherWidgetStore.isConfigured(c, id))
            assertNull(WeatherWidgetStore.city(c, id))
            // The manager's explicit edit must still work for that unconfigured widget.
            intent.putExtra("widget_edit_requested", true)
            ActivityScenario.launch<WeatherWidgetActivity>(intent).use { editor ->
                editor.onActivity {
                    assertNotNull(it.findViewById<View>(android.R.id.content).findViewWithTag<View>("widget_city_follow"))
                }
            }
            assertFalse(WeatherWidgetStore.isConfigured(c, id))
        } finally {
            ins.uiAutomation.dropShellPermissionIdentity()
            host.deleteAppWidgetId(id)
            WeatherWidgetStore.delete(c, id)
            WeatherWidgets.schedule(c)
        }
    }

    @Test fun newWidgetWaitsForCityAndThenOpensWeatherNotEditor() {
        val scenario = ActivityScenario.launch(WeatherWidgetActivity::class.java)
        val host = AppWidgetHost(c, 8851)
        val manager = AppWidgetManager.getInstance(c)
        val id = host.allocateAppWidgetId()
        var monitor: android.app.Instrumentation.ActivityMonitor? = null
        try {
            ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(c, WeatherSquareWidget::class.java)))
            ins.uiAutomation.dropShellPermissionIdentity()
            WeatherWidgetStore.register(c, id)
            assertFalse(WeatherWidgetStore.isConfigured(c, id))
            assertNull(WeatherWidgetStore.city(c, id))
            // Rendering and periodic tasks cannot silently choose the location city.
            WeatherWidgets.restore(c)
            assertFalse(WeatherWidgetStore.isConfigured(c, id))
            monitor = ins.addMonitor(WeatherWidgetActivity::class.java.name, null, false)
            ins.runOnMainSync {
                val root = WeatherWidgets.views(c, id, "square", 172, 172).apply(c, FrameLayout(c))
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widgetLoading).visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.widgetEditArea).visibility)
                root.findViewById<View>(R.id.widgetImage).performClick()
            }
            val editor = ins.waitForMonitorWithTimeout(monitor, 6000) ?: error("First click must open city editor")
            ins.waitForIdleSync()
            assertFalse(WeatherWidgetStore.isConfigured(c, id))
            assertEquals(id, editor.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            ins.runOnMainSync {
                assertNotNull(editor.findViewById<View>(android.R.id.content).findViewWithTag<View>("widget_city_follow"))
                editor.findViewById<View>(android.R.id.content).findViewWithTag<View>("widget_city_follow").performClick()
            }
            ins.waitForIdleSync()
            assertTrue(WeatherWidgetStore.isConfigured(c, id))
            assertNotNull(WeatherWidgetStore.city(c, id))
            ins.removeMonitor(monitor)
            monitor = ins.addMonitor(MainActivity::class.java.name, null, false)
            ins.runOnMainSync {
                val root = WeatherWidgets.views(c, id, "square", 172, 172).apply(c, FrameLayout(c))
                assertEquals(View.GONE, root.findViewById<View>(R.id.widgetLoading).visibility)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widgetEditArea).visibility)
                root.findViewById<View>(R.id.widgetImage).performClick()
            }
            val main = ins.waitForMonitorWithTimeout(monitor, 6000) as? MainActivity ?: error("Configured click must open weather homepage")
            SystemClock.sleep(500)
            ins.runOnMainSync {
                assertTrue(WeatherWidgetPolicy.sameCity(WeatherWidgetStore.city(c, id)!!, main.cityList()[main.currentPage()]))
                main.finish()
            }
        } finally {
            monitor?.let(ins::removeMonitor)
            ins.uiAutomation.dropShellPermissionIdentity()
            host.deleteAppWidgetId(id)
            WeatherWidgetStore.delete(c, id)
            WeatherWidgets.schedule(c)
            scenario.close()
        }
    }
}
