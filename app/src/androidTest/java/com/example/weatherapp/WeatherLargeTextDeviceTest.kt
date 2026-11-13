package com.example.weatherapp

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Calendar
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/** The runner selects the actual system font setting. Every detail fixture uses a sample city. */
class WeatherLargeTextDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val screenshotPreview = InstrumentationRegistry.getArguments().getString("font_preview") == "true"
    private val city = "北京市海淀区中关村科学城北区天气示例观测点"
    private val longText = "请留意未来逐小时的天气变化。出门前检查路线情况，携带保暖衣物并关注后续预报。"
    private val advice = listOf("穿衣", "运动", "紫外线").mapIndexed { index, title ->
        LifeAdviceItem("large_text_$index", "$title 天气生活建议", "建议减少长时间户外活动", longText.repeat(4))
    }
    private fun today(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun hours() = (0..71).map { hour ->
        ForecastHour(today() + hour * ForecastDetailData.HOUR, "CLOUDY", -12.0 + hour % 16, -14.0 + hour % 16,
            24.0 + hour % 5, 6.0, 89.0, 128.0, precipitationProbability = 55.0, precipitationAmountMm = 2.1, pressureHpa = 1029.0 + hour % 5)
    }

    @Test fun forecastPagesScrollToFullChartsAndKeepHorizontalControls() {
        val hours = hours()
        val date = DailyForecastDetailPolicy.dateKey(today())
        ActivityScenario.launch<HourlyForecastDetailActivity>(HourlyForecastDetailActivity.createIntent(context, city, hours, -12.0)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val body = activity.findViewById<View>(android.R.id.content).findViewWithTag<ViewGroup>("forecast_page:48h")
                val scroll = body.parent as ScrollView
                assertFontLimit(activity)
                assertScrollableContent(scroll)
                assertTrue("The time axis stays horizontally scrollable", body.findViewWithTag<View>("forecast_hour_scroll").canScrollHorizontally(1))
                assertCompleteText(activity.findViewById(android.R.id.content))
                if (screenshotPreview) capture(activity, "48小时-摘要")
                scroll.scrollTo(0, scroll.getChildAt(0).height)
            }
            settle()
            val shown = Rect()
            scenario.onActivity { activity ->
                val host = activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("forecast_hour_scroll")
                assertTrue(host.getGlobalVisibleRect(shown))
                assertEquals(host.height, shown.height())
                if (screenshotPreview) capture(activity, "48小时-图表")
            }
            swipe(shown.right - shown.width() / 5f, shown.exactCenterY(),
                shown.left + shown.width() / 5f, shown.exactCenterY())
            settle()
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                val host = root.findViewWithTag<HorizontalScrollView>("forecast_hour_scroll")
                assertTrue("Dragging across the chart advances the hourly timeline", host.scrollX > 0)
                val reset = root.findViewWithTag<View>("forecast_return_current")
                assertTrue("Dragging away enables return to current", reset.isEnabled)
                if (screenshotPreview) capture(activity, "48小时-横滑后")
                reset.performClick()
            }
            settle()
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                assertEquals(0, root.findViewWithTag<HorizontalScrollView>("forecast_hour_scroll").scrollX)
                assertFalse(root.findViewWithTag<View>("forecast_return_current").isEnabled)
            }
            swipe(shown.exactCenterX(), shown.top + shown.height() / 4f,
                shown.exactCenterX(), shown.bottom - shown.height() / 4f)
            settle()
            scenario.onActivity { activity ->
                val body = activity.findViewById<View>(android.R.id.content).findViewWithTag<ViewGroup>("forecast_page:48h")
                val scroll = body.parent as ScrollView
                assertEquals("A vertical drag in the chart returns to the summary", 0, scroll.scrollY)
            }
        }
        ActivityScenario.launch<ForecastDetailActivity>(ForecastDetailActivity.createIntent(context, city, hours, date)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                val body = root.findViewWithTag<ViewGroup>("forecast_page:$date")
                val scroll = body.parent as ScrollView
                assertFontLimit(activity)
                assertScrollableContent(scroll)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                val pressure = body.findViewWithTag<View>("daily_metric:PRESSURE")
                pressure.performClick()
                assertTrue(body.findViewWithTag<TextView>("daily_metric_reading").text.contains("气压"))
            }
            settle()
            scenario.onActivity { activity ->
                val body = activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("forecast_page:$date")
                assertCompleteText(body)
                if (screenshotPreview) capture(activity, "多日-图表")
            }
        }
    }

    @Test fun metricAirAndSolarDetailsHaveReachableLastSections() {
        val now = System.currentTimeMillis()
        val metrics = (0..23).map { hour ->
            HourlyMetricPoint(now + hour * ForecastDetailData.HOUR, temperature = 22.0, feelsLike = 25.0,
                uv = 7.0, windKmh = 44.0, gustKmh = 60.0, windDirectionDegrees = 315.0,
                pressureHpa = 1029.0, visibilityKm = 1.2, humidityPercent = 95.0)
        }
        for (kind in MetricKind.entries) {
            ActivityScenario.launch<MetricDetailActivity>(MetricDetailActivity.createIntent(context, kind, metrics,
                cityName = city, windDirection = "西北偏北风", lifeAdvice = advice)).use { scenario ->
                settle()
                scenario.onActivity { activity ->
                    val title = activity.findViewById<TextView>(R.id.tvMetricDetailLocation)
                    assertEquals(city, title.text.toString())
                    assertCompleteText(activity.findViewById(android.R.id.content))
                    val scroll = activity.findViewById<DetailScrollView>(R.id.metricDetailScroll)
                    assertFontLimit(activity)
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                    assertTrue(activity.findViewById<View>(R.id.metricLifeAdviceStrip).getGlobalVisibleRect(Rect()))
                    if (screenshotPreview && kind == MetricKind.WIND) capture(activity, "风力-生活建议")
                }
            }
        }
        val air = (0..23).map { hour ->
            AirQualityPoint(now + hour * ForecastDetailData.HOUR, 153.0, 125.0, 155.0, 88.0, 77.0, 3.1, 31.0)
        }
        ActivityScenario.launch<AirQualityDetailActivity>(AirQualityDetailActivity.createIntent(context, city, air, air.first(), "北京")).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                assertCompleteText(activity.findViewById(android.R.id.content))
                val scroll = activity.findViewById<DetailScrollView>(R.id.airQualityDetailScroll)
                assertFontLimit(activity)
                assertScrollableContent(scroll)
                if (screenshotPreview) capture(activity, "空气质量-摘要")
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                assertTrue(activity.findViewById<View>(R.id.airQualityRankCard).getGlobalVisibleRect(Rect()))
            }
        }
        val sun = SunAstronomyData(today() + 6 * ForecastDetailData.HOUR, today() + 18 * ForecastDetailData.HOUR,
            latitude = 39.9, longitude = 116.4, sourceName = "示例", skycon = "CLEAR_DAY")
        ActivityScenario.launch<SunDetailActivity>(SunDetailActivity.createIntent(context, city, sun)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                assertCompleteText(activity.findViewById(android.R.id.content))
                val scroll = activity.findViewById<DetailScrollView>(R.id.sunDetailScroll)
                assertFontLimit(activity)
                assertScrollableContent(scroll)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                assertFalse(scroll.canScrollVertically(1))
                if (screenshotPreview) capture(activity, "日出日落-底部")
            }
        }
    }

    @Test fun lifeAdviceAndAlertAllowLongBodyTextToWrap() {
        ActivityScenario.launch<LifeAdviceActivity>(LifeAdviceActivity.createIntent(context, advice)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                assertFontLimit(activity)
                assertCompleteText(activity.findViewById(android.R.id.content))
                if (screenshotPreview) capture(activity, "生活建议")
            }
        }
        val alert = Intent(context, AlertActivity::class.java).apply {
            putStringArrayListExtra("titles", arrayListOf("北京市示例区域大风与暴雨天气风险预警"))
            putStringArrayListExtra("contents", arrayListOf(longText.repeat(12)))
            putStringArrayListExtra("locations", arrayListOf(city))
        }
        ActivityScenario.launch<AlertActivity>(alert).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                assertFontLimit(activity)
                val list = activity.findViewById<ListView>(R.id.listAlerts)
                assertTrue(list.canScrollVertically(1))
                assertCompleteText(list)
                if (screenshotPreview) capture(activity, "气象预警")
            }
        }
    }

    @Test fun homepageCardsPreservePaddingAndCapTextWithoutEllipses() {
        instrumentation.runOnMainSync {
            val themed = android.view.ContextThemeWrapper(context.withWeatherFontLimit(), R.style.Theme_WeatherApp)
            val root = android.view.LayoutInflater.from(themed).inflate(R.layout.fragment_weather, null)
            val feel = root.findViewById<TextView>(R.id.tvFeelsDesc)
            feel.text = "体感温度较气温偏高，午后户外活动请留意防晒补水"
            val peak = root.findViewById<TextView>(R.id.tvMinutelyPeak)
            peak.text = "预计未来两小时有短时强降雨，小时峰值出现在傍晚"
            val visibility = root.findViewById<TextView>(R.id.tvVisibilityDesc)
            visibility.text = "视野良好，出行顺畅又安心。关注后续能见度变化"
            val humidity = root.findViewById<TextView>(R.id.tvHumidityDesc)
            humidity.text = "空气略潮，注意通风"
            val dew = root.findViewById<TextView>(R.id.tvHumidityDew)
            dew.text = "露点温度 13°"
            dew.visibility = View.VISIBLE
            val rain = root.findViewById<View>(R.id.minutelyCard)
            rain.visibility = View.VISIBLE
            val density = root.resources.displayMetrics.density
            val width = (320 * density).roundToInt()
            val height = (720 * density).roundToInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val expectedSize = minOf(root.resources.weatherSp(12f), 12f * density * 1.20f)
            assertEquals(expectedSize, feel.textSize, .05f)
            assertEquals((16f * density).roundToInt(), rain.paddingLeft)
            assertEquals(rain.paddingLeft, rain.paddingRight)
            assertTrue(feel.lineCount > 1)
            val visibilityCard = root.findViewById<View>(R.id.cardVisibility)
            val humidityCard = root.findViewById<View>(R.id.cardHumidity)
            assertEquals(visibilityCard.height, humidityCard.height)
            assertTrue("Visibility note stays inside its card", visibility.bottom <= visibilityCard.height - visibilityCard.paddingBottom)
            assertTrue("Dew point stays inside its card", dew.bottom <= humidityCard.height - humidityCard.paddingBottom)
            assertCompleteText(root.findViewById(R.id.weatherRoot))
        }
    }

    private fun swipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val start = SystemClock.uptimeMillis()
        val steps = 16
        for (step in 0..steps) {
            val progress = step.toFloat() / steps
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                steps -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                startX + (endX - startX) * progress, startY + (endY - startY) * progress, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            SystemClock.sleep(18)
        }
    }

    private fun settle() {
        SystemClock.sleep(270)
        instrumentation.waitForIdleSync()
    }

    @Test fun homepageAndSettingsFollowTheSameFontLimitAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity(::assertFontLimit)
            scenario.recreate()
            scenario.onActivity(::assertFontLimit)
        }
        ActivityScenario.launch(WeatherSettingsActivity::class.java).use { scenario ->
            scenario.onActivity(::assertFontLimit)
        }
    }

    @Test fun capPreservesTheSameDpSizeAtDifferentDensities() {
        val textSizes = listOf(12f, 24f, 100f)
        val baselineDp = textSizes.map { size ->
            val reference = context.createConfigurationContext(Configuration().apply { fontScale = 1.20f }).resources
            reference.weatherSp(size) / reference.displayMetrics.density
        }
        for (targetDensity in listOf(320, 480, 640)) {
            val larger = context.createConfigurationContext(Configuration().apply {
                fontScale = 2f
                densityDpi = targetDensity
            })
            assertEquals(targetDensity, larger.resources.displayMetrics.densityDpi)
            val capped = larger.withWeatherFontLimit().resources
            assertEquals(targetDensity, capped.displayMetrics.densityDpi)
            assertEquals(1.20f, capped.configuration.fontScale, .001f)
            textSizes.forEachIndexed { index, size ->
                assertEquals("Text size in dp at density $targetDensity", baselineDp[index],
                    capped.weatherSp(size) / capped.displayMetrics.density, .05f)
            }
        }
    }

    private fun assertFontLimit(activity: Activity) {
        val systemScale = context.resources.configuration.fontScale
        val requested = InstrumentationRegistry.getArguments().getString("font_scale")?.toFloatOrNull()
        if (requested != null) assertEquals("The device applied the requested font scale", requested, systemScale, .01f)
        val expected = minOf(systemScale, 1.20f)
        assertEquals("Activity font scale", expected, activity.resources.configuration.fontScale, .001f)
        val reference = activity.createConfigurationContext(Configuration().apply { fontScale = expected }).resources
        for (size in listOf(12f, 24f, 100f)) {
            assertEquals("App text and Canvas must use the same capped font scale for ${size}sp",
                reference.weatherSp(size), activity.resources.weatherSp(size), .05f)
        }
    }

    private fun assertScrollableContent(scroll: ScrollView) {
        val available = scroll.height - scroll.paddingTop - scroll.paddingBottom
        if (scroll.getChildAt(0).height > available) assertTrue(scroll.canScrollVertically(1))
    }

    private fun assertCompleteText(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotBlank() && view.width > 0 && view.height > 0) {
            val layout = requireNotNull(view.layout)
            val name = view.text.take(45)
            assertTrue("Text height fits $name", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom + 2)
            assertEquals("Text is complete $name", layout.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) assertEquals("No ellipsis in $name", 0, layout.getEllipsisCount(line))
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) assertCompleteText(view.getChildAt(index))
    }

    private fun capture(activity: Activity, label: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val folder = File(activity.getExternalFilesDir(null), "large-text-verification").apply { mkdirs() }
        File(folder, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
