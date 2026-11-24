package com.example.weatherapp

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailViewportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val hour = ForecastDetailData.HOUR
    private val city = "北京"
    private val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val forecast = (0..71).map {
        ForecastHour(today + it * hour, "CLOUDY", 18.0 + it % 10, 17.0 + it % 7,
            12.0 + it % 5, 5.0, 73.0, 99.0, precipitationProbability = 20.0)
    }

    @Test fun forecastChartsFitTheActualViewportAndKeepHorizontalInteraction() {
        checkPage<HourlyForecastDetailActivity>(
            HourlyForecastDetailActivity.createIntent(context, city, forecast, 20.0),
            { it.findViewById<View>(android.R.id.content).findViewWithTag<View>("forecast_page:48h").parent as ScrollView },
            { scroll -> assertTrue(scroll.findViewWithTag<View>("forecast_hour_scroll").canScrollHorizontally(1))
                assertCompactCapsule(scroll.findViewWithTag("forecast_return_current"), 6, 2, 25) }
        )
        val date = DailyForecastDetailPolicy.dateKey(today)
        checkPage<ForecastDetailActivity>(
            ForecastDetailActivity.createIntent(context, city, forecast, date),
            { it.findViewById<View>(android.R.id.content).findViewWithTag<View>("forecast_page:$date").parent as ScrollView },
            { scroll ->
                assertFalse(scroll.findViewWithTag<View>("daily_history_status").isShown)
                assertCompactCapsule(scroll.findViewWithTag("forecast_return_today"), 9)
                assertCompactCapsule(scroll.findViewWithTag("daily_metric_picker"), 9)
            }
        )
    }

    @Test fun metricSolarAndAirDetailsFitAndFillTheActualViewport() {
        val now = System.currentTimeMillis()
        val metrics = (0..23).map {
            HourlyMetricPoint(now + it * hour, temperature = 22.0, feelsLike = 25.0,
                uv = 7.0, windKmh = 44.0, gustKmh = 60.0, windDirectionDegrees = 315.0,
                pressureHpa = 1029.0, visibilityKm = 12.0, humidityPercent = 65.0)
        }
        for (kind in MetricKind.entries) {
            checkPage<MetricDetailActivity>(
                MetricDetailActivity.createIntent(context, kind, metrics, cityName = city,
                    windDirection = "西北风",
                    lifeAdvice = listOf(LifeAdviceItem("q1", "运动", "适宜", "适合户外活动"))),
                { it.findViewById(R.id.metricDetailScroll) }
            )
        }
        val air = (0..23).map { AirQualityPoint(now + it * hour, 72.0, 32.0, 45.0, 18.0, 9.0, 1.1, 34.0) }
        checkPage<AirQualityDetailActivity>(
            AirQualityDetailActivity.createIntent(context, city, air, air.first(), city),
            { it.findViewById(R.id.airQualityDetailScroll) }
        )
        val sun = SunAstronomyData(today + 6 * hour, today + 18 * hour,
            latitude = 39.9, longitude = 116.4, sourceName = "示例", skycon = "CLEAR_DAY")
        checkPage<SunDetailActivity>(
            SunDetailActivity.createIntent(context, city, sun),
            { it.findViewById(R.id.sunDetailScroll) }
        )
    }

    @Test fun dailyWeatherArtworkAndEveryItemInTheCompactMenuHasAnIcon() {
        val tomorrow = DailyForecastDetailPolicy.dateKey(today + 30 * hour)
        val tag = "forecast_page:$tomorrow"
        val hazeForecast = forecast.map { it.copy(skycon = "HEAVY_HAZE") }
        ActivityScenario.launch<ForecastDetailActivity>(ForecastDetailActivity.createIntent(context, city, hazeForecast, tomorrow)).use { scenario ->
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(300)
            scenario.onActivity { activity ->
                val body = activity.findViewById<View>(android.R.id.content)
                    .findViewWithTag<android.view.ViewGroup>(tag)
                val summary = (0 until body.childCount).map(body::getChildAt)
                    .filterIsInstance<HourlyForecastSummaryView>().single()
                val description = summary.contentDescription.toString()
                assertTrue(description.contains("当日天气：重度雾霾"))
                assertFalse(description.contains("逐小时气温低点"))
                val icon = HourlyForecastSummaryView::class.java.getDeclaredField("dailyIcon").apply { isAccessible = true }
                val glow = HourlyForecastSummaryView::class.java.getDeclaredField("dailyHalo").apply { isAccessible = true }
                assertTrue(icon.get(summary) != null)
                assertTrue(glow.get(summary) != null)
                val bounds = (HourlyForecastSummaryView::class.java.getDeclaredField("dailyIconBounds")
                    .apply { isAccessible = true }.get(summary)) as android.graphics.RectF
                val items = HourlyForecastSummaryView::class.java.getDeclaredField("paragraphs")
                    .apply { isAccessible = true }.get(summary) as List<*>
                val label = items.filterNotNull().first {
                    val block = it.javaClass.getDeclaredField("layout").apply { isAccessible = true }
                        .get(it) as android.text.StaticLayout
                    block.text.toString() == "重度雾霾"
                }
                val labelLayout = label.javaClass.getDeclaredField("layout").apply { isAccessible = true }
                    .get(label) as android.text.StaticLayout
                val labelX = label.javaClass.getDeclaredField("x").apply { isAccessible = true }.getFloat(label)
                assertTrue(labelLayout.paint.textSize <= bounds.height() / 3f + .1f)
                assertEquals(1, labelLayout.lineCount)
                assertTrue(labelLayout.getLineWidth(0) <= labelLayout.width)
                assertTrue(bounds.left - (labelX + labelLayout.width) >= 13f * activity.resources.displayMetrics.density)
                assertEquals(labelLayout.text.length, labelLayout.getLineEnd(labelLayout.lineCount - 1))
                val scaledConfig = android.content.res.Configuration(activity.resources.configuration).apply {
                    fontScale = 2f
                }
                val scaledContext = activity.createConfigurationContext(scaledConfig)
                val narrow = HourlyForecastSummaryView(scaledContext, android.graphics.Color.WHITE,
                    android.graphics.Color.WHITE, android.graphics.Color.BLACK, android.graphics.Color.GRAY,
                    android.graphics.Color.LTGRAY,
                    listOf(HourlyForecastSummaryMetric(HourlyForecastSummaryKind.TEMPERATURE,
                        "气温范围", "15°–24°", "当日温差9°", "留意温差", 0f, android.graphics.Color.BLACK)),
                    compactTemperature = true, dailyWeather = DailySummaryWeather("HEAVY_HAZE"))
                val narrowWidth = (280 * scaledContext.resources.displayMetrics.density).toInt()
                narrow.measure(View.MeasureSpec.makeMeasureSpec(narrowWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val narrowParagraphs = HourlyForecastSummaryView::class.java.getDeclaredField("paragraphs")
                    .apply { isAccessible = true }.get(narrow) as List<*>
                val narrowWeather = narrowParagraphs.filterNotNull().map {
                    it.javaClass.getDeclaredField("layout").apply { isAccessible = true }.get(it) as android.text.StaticLayout
                }.first { it.text.toString() == "重度雾霾" }
                assertEquals(1, narrowWeather.lineCount)
                assertEquals(4, narrowWeather.getLineEnd(0))
                assertTrue(narrowWeather.getLineWidth(0) <= narrowWeather.width)
                val bitmap = android.graphics.Bitmap.createBitmap(summary.width, summary.height, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    summary.draw(android.graphics.Canvas(bitmap))
                    val folder = java.io.File(context.getExternalFilesDir(null), "daily-summary-qa").apply { mkdirs() }
                    java.io.File(folder, "tomorrow-temperature.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            openDailyMetricMenu(scenario, tag) { popup ->
                val density = popup.contentView.resources.displayMetrics.density
                assertTrue(popup.width / density <= 164.5f)
                val entries = DailyForecastMetric.entries.map { metric ->
                    popup.contentView.findViewWithTag<android.widget.TextView>("daily_metric:${metric.name}")
                }
                assertTrue(entries.all { it.compoundDrawablesRelative[2] != null })
                entries.first { it.text == "风力" }.performClick()
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content).findViewWithTag<View>(tag)
                val picker = root.findViewWithTag<android.widget.TextView>("daily_metric_picker")
                assertTrue(picker.text.startsWith("风力"))
                if (!WeatherSettings.isDarkTheme(activity)) {
                    val material = picker.background as android.graphics.drawable.GradientDrawable
                    assertEquals(listOf(android.graphics.Color.WHITE, 0xFFF5F5F5.toInt()), material.colors!!.toList())
                    assertTrue(picker.elevation > 0f)
                }
            }
        }
    }

    @Test fun dailyGlowUsesLocalColorsAndStaysOutsideTheIcon() {
        val icon = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(icon)
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.rgb(255, 151, 25)
        canvas.drawRect(10f, 14f, 33f, 46f, paint)
        paint.color = android.graphics.Color.rgb(104, 170, 244)
        canvas.drawRect(35f, 14f, 56f, 46f, paint)
        for (light in listOf(false, true)) {
            val glow = DailyWeatherIconGlow.load(icon, light)
            val pad = (glow.width - icon.width) / 2
            val orange = glow.getPixel(pad + 18, pad + 12)
            val blue = glow.getPixel(pad + 45, pad + 12)
            assertTrue(android.graphics.Color.alpha(orange) > 14 && android.graphics.Color.alpha(blue) > 14)
            assertTrue(android.graphics.Color.red(orange) > android.graphics.Color.blue(orange) + 100)
            assertTrue(android.graphics.Color.blue(blue) > android.graphics.Color.red(blue) + 75)
            assertEquals(0, android.graphics.Color.alpha(glow.getPixel(pad + 18, pad + 27)))
            assertEquals(0, android.graphics.Color.alpha(glow.getPixel(0, 0)))
            assertTrue(glow === DailyWeatherIconGlow.load(icon, light))
        }
    }

    private fun assertCompactCapsule(button: View, horizontalPaddingDp: Int, verticalPaddingDp: Int = 3, heightDp: Int = 27) {
        val density = button.resources.displayMetrics.density
        assertTrue("Capsule height = ${button.height / density}dp", kotlin.math.abs(button.height / density - heightDp) < .5f)
        assertTrue(kotlin.math.abs(button.paddingStart / density - horizontalPaddingDp) < .3f)
        assertTrue(kotlin.math.abs(button.paddingTop / density - verticalPaddingDp) < .3f)
    }

    private inline fun <reified T : Activity> checkPage(
        intent: Intent,
        crossinline scrollFor: (T) -> ScrollView,
        crossinline extra: (ScrollView) -> Unit = {}
    ) {
        ActivityScenario.launch<T>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(300)
            scenario.onActivity { activity ->
                val scroll = scrollFor(activity)
                val body = scroll.getChildAt(0)
                assertTrue(scroll.height > 0)
                assertFalse("Vertical scroll in ${activity.localClassName}: ${body.height} vs ${scroll.height - scroll.paddingTop - scroll.paddingBottom}",
                    scroll.canScrollVertically(1))
                assertEquals(scroll.height - scroll.paddingTop - scroll.paddingBottom, body.height)
                val visible = Rect()
                assertTrue(body.getGlobalVisibleRect(visible))
                assertEquals(body.height, visible.height())
                extra(scroll)
            }
        }
    }
}
