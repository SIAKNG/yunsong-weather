package com.example.weatherapp

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ForecastCapsuleDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val start get() = ForecastDayHistoryPolicy.startOfDay(System.currentTimeMillis())

    @Test fun frostedMenuFitsBothThemesAndKeepsTheSelectedHour() {
        val original = WeatherSettings.themeMode(context)
        try {
            for (theme in listOf(WeatherSettings.THEME_LIGHT, WeatherSettings.THEME_DARK)) {
                WeatherSettings.setThemeMode(context, theme)
                ins.runOnMainSync { WeatherSettings.applyNightMode(context) }
                val rows = (0..23).map { hour ->
                    ForecastHour(start + hour * ForecastDetailData.HOUR, "CLEAR_DAY", 21.0, 20.0, 10.0, 3.0, 50.0, 40.0,
                        pressureHpa = 1001.0, visibilityKm = 12.0, precipitationProbability = 0.0)
                }
                ActivityScenario.launch<ForecastDetailActivity>(ForecastDetailActivity.createIntent(context, "北京市", rows)).use { scenario ->
                    scenario.onActivity { activity ->
                        val panel = activity.findViewById<View>(android.R.id.content).findViewWithTag<DailyForecastMetricPanel>("daily_metric_panel")
                        val scroll = generateSequence(panel.parent) { (it as? View)?.parent }.filterIsInstance<android.widget.ScrollView>().first()
                        scroll.scrollTo(0, scroll.getChildAt(0).height)
                        panel.chart.selectTime(start + 11 * ForecastDetailData.HOUR)
                    }
                    for (metric in DailyForecastMetric.entries) {
                        chooseDailyMetric(scenario, metric.name)
                        scenario.onActivity { activity ->
                            val root = activity.findViewById<View>(android.R.id.content)
                            val panel = root.findViewWithTag<DailyForecastMetricPanel>("daily_metric_panel")
                            val picker = panel.findViewWithTag<TextView>("daily_metric_picker")
                            val reading = panel.findViewWithTag<TextView>("daily_metric_reading")
                            assertEquals(metric, panel.selectedMetric)
                            assertTrue(picker.text.contains(metric.title))
                            assertTrue(reading.right <= picker.left)
                            assertEquals(11, panel.chart.javaClass.getDeclaredField("selectedIndex").apply { isAccessible = true }.getInt(panel.chart))
                            assertTrue(panel.chart.height > 275 * activity.resources.displayMetrics.density)
                        }
                    }
                    val menuBounds = android.graphics.Rect()
                    openDailyMetricMenu(scenario) { menu ->
                        assertEquals("settings_glass_surface", menu.contentView.tag)
                        assertTrue(menu.contentView.foreground is WeatherFrostedBorderDrawable)
                    }
                    android.os.SystemClock.sleep(450)
                    ins.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val panel = activity.findViewById<View>(android.R.id.content).findViewWithTag<DailyForecastMetricPanel>("daily_metric_panel")
                        val menu = panel.javaClass.getDeclaredField("metricPopup").apply { isAccessible = true }.get(panel) as android.widget.PopupWindow
                        val position = IntArray(2).apply(menu.contentView::getLocationOnScreen)
                        menuBounds.set(position[0], position[1], position[0] + menu.contentView.width,
                            position[1] + menu.contentView.height)
                    }
                    val bitmap = ins.uiAutomation.takeScreenshot()
                    if (bitmap != null) {
                        val folder = java.io.File(context.getExternalFilesDir(null), "weather-v13-test").apply { mkdirs() }
                        if (menuBounds.intersect(0, 0, bitmap.width, bitmap.height)) {
                            val region = android.graphics.Bitmap.createBitmap(bitmap, menuBounds.left, menuBounds.top,
                                menuBounds.width(), menuBounds.height())
                            java.io.File(folder, "metric-popup-$theme.png").outputStream().use {
                                region.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                            region.recycle()
                        }
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            WeatherSettings.setThemeMode(context, original)
            ins.runOnMainSync { WeatherSettings.applyNightMode(context) }
        }
    }
}
