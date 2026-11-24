package com.example.weatherapp

import android.view.View
import android.widget.PopupWindow
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertTrue

internal fun openDailyMetricMenu(scenario: ActivityScenario<ForecastDetailActivity>, pageTag: String? = null, verify: (PopupWindow) -> Unit = {}) {
    scenario.onActivity { activity ->
        val content = activity.findViewById<View>(android.R.id.content)
        val page = pageTag?.let { content.findViewWithTag<View>(it) } ?: content
        val panel = page.findViewWithTag<DailyForecastMetricPanel>("daily_metric_panel")
        val picker = panel.findViewWithTag<View>("daily_metric_picker")
        picker.performClick()
        val menu = panel.javaClass.getDeclaredField("metricPopup").apply { isAccessible = true }.get(panel) as PopupWindow
        assertTrue(menu.isShowing)
        assertTrue(menu.isFocusable)
        verify(menu)
    }
}

internal fun chooseDailyMetric(scenario: ActivityScenario<ForecastDetailActivity>, metric: String, pageTag: String? = null) {
    openDailyMetricMenu(scenario, pageTag) { it.contentView.findViewWithTag<View>("daily_metric:$metric").performClick() }
}
