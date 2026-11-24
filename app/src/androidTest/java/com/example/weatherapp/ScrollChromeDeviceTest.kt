package com.example.weatherapp

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ScrollChromeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun weatherPreviewChromeIsEdgeToEdgeAndRevealsOnlyAfterScrolling() {
        val intent = Intent(context, CityPreviewActivity::class.java)
            .putExtra("city", "北京")
            .putExtra("lat", 39.90)
            .putExtra("lng", 116.39)
        ActivityScenario.launch<CityPreviewActivity>(intent).use { scenario ->
            SystemClock.sleep(600)
            scenario.onActivity { activity ->
                val scroll = activity.findViewById<ElasticWeatherScrollView>(R.id.weatherRoot)
                val body = scroll.getChildAt(0) as android.widget.LinearLayout
                val d = activity.resources.displayMetrics.density
                val band = View(activity).apply { setBackgroundColor(android.graphics.Color.MAGENTA) }
                body.addView(band, 0, android.widget.LinearLayout.LayoutParams(-1, (110 * d).toInt()))
                body.addView(View(activity), android.widget.LinearLayout.LayoutParams(-1, activity.resources.displayMetrics.heightPixels))
            }
            SystemClock.sleep(350)
            scenario.onActivity { activity ->
                val scroll = activity.findViewById<ElasticWeatherScrollView>(R.id.weatherRoot)
                val topMask = activity.findViewById<View>(R.id.topMask)
                val city = activity.findViewById<View>(R.id.tvPreviewCity)
                val refresh = activity.findViewById<View>(R.id.refreshLayout)
                val location = IntArray(2)
                scroll.getLocationOnScreen(location)
                val scrollTop = location[1]
                topMask.getLocationOnScreen(location)
                assertEquals("The scrolling viewport reaches the mask from the top", location[1], scrollTop)
                val d = activity.resources.displayMetrics.density
                assertEquals(0, refresh.paddingTop)
                assertTrue("The ramp extends well below the title bar", topMask.height - city.height > (65 * d).toInt())
                scroll.scrollTo(0, 0)
                assertEquals(0f, topMask.alpha, .01f)
                scroll.scrollTo(0, (55 * d).toInt())
                assertEquals((55 * d).toInt(), scroll.scrollY)
                assertTrue("Midway through the reveal stays partially transparent: " + topMask.alpha, topMask.alpha in 0.10f..0.85f)
                scroll.scrollTo(0, (185 * d).toInt())
                assertEquals(1f, topMask.alpha, .01f)
                assertTrue(city.isShown)
            }
            SystemClock.sleep(350)
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragmentHost) as WeatherFragment
                val field = fragment.javaClass.getDeclaredField("maskShotBmp").apply { isAccessible = true }
                val refreshGlass = fragment.javaClass.getDeclaredMethod("refreshGlass", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                refreshGlass.invoke(fragment, true)
                val sample = field.get(fragment) as android.graphics.Bitmap
                val x = sample.width / 2
                val visible = (0 until (sample.height * .55f).toInt()).any { y ->
                    val px = sample.getPixel(x, y)
                    android.graphics.Color.red(px) > 190 && android.graphics.Color.blue(px) > 190 &&
                        android.graphics.Color.green(px) < 80
                }
                assertTrue("The actual content under the title participates in the mask", visible)
                val scroll = activity.findViewById<ElasticWeatherScrollView>(R.id.weatherRoot)
                scroll.scrollTo(0, 0)
                assertEquals(0f, activity.findViewById<View>(R.id.topMask).alpha, .01f)
            }
        }
    }

    @Test fun edgeMoveUsesResistanceAndSettlesAtRest() {
        val now = System.currentTimeMillis()
        val weather = listOf(ForecastHour(now, "CLEAR_DAY", 20.0, 19.0, 12.0, 3.0, 50.0, 40.0))
        ActivityScenario.launch<ForecastDetailActivity>(
            ForecastDetailActivity.createIntent(context, "北京", weather)
        ).use { scenario ->
            lateinit var scroll: ElasticWeatherScrollView
            lateinit var content: View
            scenario.onActivity { activity ->
                scroll = ElasticWeatherScrollView(activity)
                content = View(activity).apply {
                    isClickable = true
                    minimumHeight = activity.resources.displayMetrics.heightPixels * 2
                }
                scroll.addView(content, FrameLayout.LayoutParams(-1, activity.resources.displayMetrics.heightPixels * 2))
                activity.setContentView(scroll)
            }
            SystemClock.sleep(200)
            scenario.onActivity {
                val d = scroll.resources.displayMetrics.density
                val y = 275 * d
                val x = 160 * d
                val down = SystemClock.uptimeMillis()
                fun send(action: Int, py: Float, elapsed: Long) {
                    MotionEvent.obtain(down, down + elapsed, action, x, py, 0).also {
                        scroll.dispatchTouchEvent(it)
                        it.recycle()
                    }
                }
                scroll.scrollTo(0, content.height)
                assertFalse(scroll.canScrollVertically(1))
                assertTrue("Fixture should be at the lower edge of an actual scrolling area", scroll.canScrollVertically(-1))
                send(MotionEvent.ACTION_DOWN, y, 0)
                send(MotionEvent.ACTION_MOVE, y - 60 * d, 40)
                val first = content.translationY
                send(MotionEvent.ACTION_MOVE, y - 150 * d, 80)
                assertTrue("Expected more pull with resistance: first=$first next=${content.translationY} y=${scroll.scrollY} d=$d", content.translationY < first && content.translationY > -56 * d)
                send(MotionEvent.ACTION_UP, y - 150 * d, 140)
            }
            SystemClock.sleep(480)
            scenario.onActivity {
                assertEquals(0f, content.translationY, .01f)
                assertFalse(scroll.canScrollVertically(1))
                assertTrue("Fixture should be at the lower edge of an actual scrolling area", scroll.canScrollVertically(-1))
            }
        }
    }
}
