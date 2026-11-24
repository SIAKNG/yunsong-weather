package com.example.weatherapp

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WidgetClockDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val c = ins.targetContext
    private val city = CityPage("北京市", 116.407, 39.904, false)
    private val now = LocalDateTime.of(2026, 11, 23, 9, 21).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun sample(night: Boolean, sky: String = if (night) "PARTLY_CLOUDY_NIGHT" else "PARTLY_CLOUDY_DAY"): WidgetWeather {
        val observed = System.currentTimeMillis()
        val firstHour = now - 21 * 60_000L + 3_600_000L
        val days = (listOf("2026-11-23") + WeatherWidgetPolicy.futureDates(5, now)).mapIndexed { i, date ->
            WidgetDay(date, 2.0 - i, 14.0 - i, if (i % 2 == 0) "PARTLY_CLOUDY_DAY" else "CLEAR_DAY")
        }
        return WidgetWeather(city, WeatherSettings.dataSource(c), observed, observed, if(night) 4.0 else 8.0,
            6.0, sky, 45.0, 10.0, 28.0, List(6) { hour ->
                WidgetHour(firstHour + (hour - 1) * 3_600_000L, 8.0 + hour, "PARTLY_CLOUDY_DAY", 0.0)
            }, days, night, forecast = listOf(ForecastHour(firstHour + 5 * 3_600_000L,
                "PARTLY_CLOUDY_DAY", 14.0, 13.0, 10.0, null, null, null)))
    }

    @Test fun clockLayoutUsesVisibleIconAndTemperatureBoundsAndRetainsForecasts() {
        assertEquals("11/23 周一 · 十月十五", WeatherClockWidget.date(now))
        assertNotEquals(WeatherClockWidget.date(now), WeatherClockWidget.date(WeatherClockWidget.nextMidnight(now)))
        val dir = File(c.getExternalFilesDir(null), "clock-widget-verification").apply { mkdirs() }
        for (kind in listOf(WeatherClockWidget.HOURLY, WeatherClockWidget.WEEKLY)) {
            for ((width, height) in listOf(331 to 157, 250 to 110, 356 to 172)) {
                val night = kind == WeatherClockWidget.WEEKLY
                val data = sample(night)
                val elements = mutableListOf<WeatherInfoWidgetRenderer.Element>()
                val bitmap = WeatherWidgetRenderer.renderWidget(c, kind, width, height, city, data, audit = elements, now = now)
                val clock = elements.first { it.label == "09:21" }.bounds
                val weatherIcon = elements.first { it.type == "icon" }.bounds
                val temperature = elements.first { it.label == if (night) "4°" else "8°" }.bounds
                assertEquals(temperature.centerY(), weatherIcon.centerY(), 1.6f)
                assertEquals(temperature.height(), weatherIcon.height(), 2.6f)
                assertTrue("Artwork has a readable gap from the temperature", weatherIcon.right < temperature.left)
                assertTrue(elements.any { it.label == "11/23 周一 · 十月十五" })
                assertTrue(elements.any { it.label.startsWith("多云") && '/' in it.label })
                val pointLabels = elements.filter { e ->
                    if (night) e.label in setOf("明天","后天","周四","周五","周六") else e.label.matches(Regex("[0-9]{2}:00"))
                }
                assertEquals(if (night) 5 else 6, pointLabels.size)
                val upperBottom = elements.filter { "十五" in it.label || it.label.startsWith("多云") }.maxOf { it.bounds.bottom }
                assertTrue("Clock/forecast gap at ${width}×${height}: $upperBottom/${pointLabels.first().bounds.top}",
                    upperBottom + 1f <= pointLabels.first().bounds.top)
                assertNoOverlap(clock, temperature)
                assertTrue(elements.none { '…' in it.label })
                if (width == 331) File(dir, "$kind-${width}x${height}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        }
    }

    private fun assertNoOverlap(a: RectF, b: RectF) = assertFalse(RectF.intersects(a, b))
    private fun atFontScale(ratio: Float): Context = c.createConfigurationContext(Configuration(c.resources.configuration).apply {
        fontScale = ratio
        densityDpi = android.util.DisplayMetrics.DENSITY_DEFAULT
    })

    @Test fun allTwelveTemplatesIgnoreSystemFontScaleAndClockProvidersAreRegistered() {
        val normal = atFontScale(1f)
        val large = atFontScale(2f)
        val catalog = WeatherWidgetCatalog.all(c)
        assertEquals(12, catalog.size)
        val ids = listOf(7986, 7987, 7988)
        val clockIds = mapOf(WeatherClockWidget.HOURLY to ids[1], WeatherClockWidget.WEEKLY to ids[2])
        try {
            for (template in catalog.filter { WeatherClockWidget.supports(it.kind) }) {
                val id = clockIds.getValue(template.kind)
                WeatherWidgetStore.delete(c, id)
                assertEquals(ComponentName(c, WeatherWidgetActivity::class.java), template.info.configure)
                assertEquals(AppWidgetProviderInfo.RESIZE_NONE, template.info.resizeMode)
                assertEquals(4, template.info.targetCellWidth)
                assertEquals(2, template.info.targetCellHeight)
                ins.runOnMainSync {
                    val initial = WeatherWidgets.views(c, id, template.kind, 331, 157).apply(c, FrameLayout(c))
                    assertEquals(View.VISIBLE, initial.findViewById<View>(R.id.widgetLoading).visibility)
                    assertEquals(View.GONE, initial.findViewById<View>(R.id.widgetClock).visibility)
                }
                WeatherWidgetStore.setCity(c, id, city)
            }
            WeatherWidgetStore.setCity(c, ids.first(), city)
            val longWeather = sample(false, "HEAVY_HAZE").copy(temperature = -33.0)
            for (template in catalog) {
                val grid = WeatherWidgetPolicy.size(template.kind)
                val w = if (grid.first == 2) 172 else 331
                val h = when(grid.second) {1 -> 76; 4 -> 390; else -> 157}
                val baseline = WeatherWidgetRenderer.renderWidget(normal, template.kind, w, h, city, longWeather, now = now)
                val enlarged = WeatherWidgetRenderer.renderWidget(large, template.kind, w, h, city, longWeather, now = now)
                assertTrue("Canvas layout must be identical for ${template.kind}", baseline.sameAs(enlarged))
                baseline.recycle()
                enlarged.recycle()
                val id = clockIds[template.kind] ?: ids.first()
                ins.runOnMainSync {
                    val smallView = WeatherWidgets.views(normal, id, template.kind, w, h).apply(normal, FrameLayout(normal))
                    val largeView = WeatherWidgets.views(large, id, template.kind, w, h).apply(large, FrameLayout(large))
                    for (item in listOf(R.id.widgetLoading, R.id.widgetEditArea, R.id.widgetClock)) {
                        assertEquals(smallView.findViewById<TextView>(item).textSize,
                            largeView.findViewById<TextView>(item).textSize, .1f)
                    }
                    val clock = largeView.findViewById<TextClock>(R.id.widgetClock)
                    if (WeatherClockWidget.supports(template.kind)) {
                        assertEquals(View.VISIBLE, clock.visibility)
                        assertEquals(View.GONE, largeView.findViewById<View>(R.id.widgetEditArea).visibility)
                        assertEquals("HH:mm", clock.format24Hour.toString())
                        assertEquals("HH:mm", clock.format12Hour.toString())
                        assertTrue(clock.hasOnClickListeners())
                    } else assertEquals(View.GONE, clock.visibility)
                }
            }
        } finally {
            ids.forEach { WeatherWidgetStore.delete(c, it) }
        }
    }

    @Test(timeout = 90_000) fun visibleClocksFollowTheNextPhoneMinuteWithoutRepublishingTheCards() {
        val ids = intArrayOf(7971, 7972)
        ids.forEach { WeatherWidgetStore.setCity(c, it, city) }
        try {
            ActivityScenario.launch(WeatherWidgetActivity::class.java).use { scenario ->
                val widgets = listOf(WeatherClockWidget.HOURLY, WeatherClockWidget.WEEKLY)
                val imageDrawables = arrayOfNulls<android.graphics.drawable.Drawable>(2)
                val clocks = arrayOfNulls<TextClock>(2)
                val imageViews = arrayOfNulls<android.widget.ImageView>(2)
                val shownAt = LongArray(2)
                val tick = CountDownLatch(2)
                var boundary = 0L
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    val density = activity.resources.displayMetrics.density
                    for ((index, kind) in widgets.withIndex()) {
                        val card = WeatherWidgets.views(c, ids[index], kind, 331, 157).apply(c, root)
                        root.addView(card, LinearLayout.LayoutParams((331 * density).toInt(), (157 * density).toInt()))
                        imageViews[index] = card.findViewById(R.id.widgetImage)
                        imageDrawables[index] = imageViews[index]?.drawable
                        clocks[index] = card.findViewById(R.id.widgetClock)
                    }
                    activity.setContentView(root)
                    boundary = (System.currentTimeMillis() / 60_000L + 1) * 60_000L
                    val target = WeatherClockWidget.clock(boundary)
                    for ((index, clock) in clocks.withIndex()) {
                        requireNotNull(clock)
                        assertTrue(clock.isAttachedToWindow)
                        assertNotEquals(target, clock.text.toString())
                        clock.doAfterTextChanged {
                            if (it.toString() == target && shownAt[index] == 0L) {
                                shownAt[index] = System.currentTimeMillis()
                                tick.countDown()
                            }
                        }
                    }
                }
                assertTrue("Both views should change without a provider refresh", tick.await(66, TimeUnit.SECONDS))
                scenario.onActivity {
                    for (i in widgets.indices) {
                        val delay = shownAt[i] - boundary
                        assertTrue("Clock ${widgets[i]} updated after ${delay}ms", delay in 0L..3000L)
                        assertSame(imageDrawables[i], imageViews[i]?.drawable)
                        android.util.Log.i("WeatherWidgetClockTest", "${widgets[i]} delay_ms=$delay")
                    }
                }
            }
        } finally {
            ids.forEach { WeatherWidgetStore.delete(c, it) }
        }
    }
}
