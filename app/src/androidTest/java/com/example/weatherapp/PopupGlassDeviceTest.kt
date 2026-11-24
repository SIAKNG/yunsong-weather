package com.example.weatherapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

class PopupGlassDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun renderedBackdropKeepsColorAndRemovesSharpLinesInBothThemes() {
        val oldTheme = WeatherSettings.themeMode(context)
        try {
            for (theme in listOf(WeatherSettings.THEME_LIGHT, WeatherSettings.THEME_DARK)) {
                WeatherSettings.setThemeMode(context, theme)
                instrumentation.runOnMainSync { WeatherSettings.applyNightMode(context) }
                val now = System.currentTimeMillis()
                val forecast = listOf(ForecastHour(now, "CLEAR_DAY", 20.0, 19.0, 12.0, 3.0, 50.0, 40.0))
                ActivityScenario.launch<ForecastDetailActivity>(
                    ForecastDetailActivity.createIntent(context, "测试天气", forecast)
                ).use { scenario ->
                    lateinit var root: View
                    scenario.onActivity { activity ->
                        root = object : View(activity) {
                            private val paint = Paint()
                            override fun onDraw(canvas: Canvas) {
                                val half = width * .5f
                                paint.color = Color.rgb(235, 33, 28)
                                canvas.drawRect(0f, 0f, half, height.toFloat(), paint)
                                paint.color = Color.rgb(30, 59, 235)
                                canvas.drawRect(half, 0f, width.toFloat(), height.toFloat(), paint)
                                val interval = (resources.displayMetrics.density * 3).toInt().coerceAtLeast(1)
                                for (y in 0 until height step (interval * 2)) {
                                    paint.color = Color.WHITE
                                    canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + interval / 2).toFloat(), paint)
                                }
                            }
                        }
                        activity.setContentView(root)
                    }
                    SystemClock.sleep(200)
                    lateinit var menu: PopupWindow
                    lateinit var menuView: View
                    scenario.onActivity { activity ->
                        val d = activity.resources.displayMetrics.density
                        menuView = FrameLayout(activity)
                        SettingsGlassStyle.decoratePopup(menuView, activity.window.decorView)
                        menu = PopupWindow(menuView, (276 * d).toInt(), (188 * d).toInt(), true)
                        menu.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        menu.showAtLocation(root, Gravity.CENTER, 0, 0)
                    }
                    sampleFrame(scenario, menuView, "menu-$theme")
                    scenario.onActivity { menu.dismiss() }
                    lateinit var dialog: AlertDialog
                    scenario.onActivity { activity ->
                        dialog = AlertDialog.Builder(activity).setTitle("磨砂验证")
                            .setMessage("测试文字保持清晰").setPositiveButton("完成", null)
                            .create().showFrosted(activity)
                    }
                    sampleFrame(scenario, dialog.window!!.decorView, "dialog-$theme", testLines = false)
                    scenario.onActivity { dialog.dismiss() }
                }
            }
        } finally {
            WeatherSettings.setThemeMode(context, oldTheme)
            instrumentation.runOnMainSync { WeatherSettings.applyNightMode(context) }
        }
    }

    private fun sampleFrame(
        scenario: ActivityScenario<ForecastDetailActivity>,
        view: View,
        name: String,
        testLines: Boolean = true
    ) {
        SystemClock.sleep(680)
        lateinit var bounds: Rect
        scenario.onActivity {
            val pos = IntArray(2).apply(view::getLocationOnScreen)
            bounds = Rect(pos[0], pos[1], pos[0] + view.width, pos[1] + view.height)
        }
        val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val crop = android.graphics.Bitmap.createBitmap(frame, bounds.left, bounds.top, bounds.width(), bounds.height())
        val y0 = if (testLines) (crop.height * .28f).toInt() else (crop.height * .58f).toInt()
        val y1 = if (testLines) (crop.height * .70f).toInt() else (crop.height * .65f).toInt()
        val left = (crop.width * .30f).toInt()
        val right = (crop.width * .70f).toInt()
        fun contrast(pixel: Int) = Color.red(pixel) - Color.blue(pixel)
        val leftHue = (y0..y1).map { contrast(crop.getPixel(left, it)) }.average()
        val rightHue = (y0..y1).map { contrast(crop.getPixel(right, it)) }.average()
        val folder = File(context.getExternalFilesDir(null), "popup-glass-test").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val colorDifference = leftHue - rightHue
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("transmitted_color", "$name ${"%.1f".format(java.util.Locale.US, colorDifference)}")
        })
        assertTrue("$name left=$leftHue right=$rightHue", colorDifference > 85)
        if (testLines) {
            var darkest = 255
            var brightest = 0
            for (y in y0..y1) {
                val green = Color.green(crop.getPixel(left, y))
                darkest = min(darkest, green)
                brightest = max(brightest, green)
            }
            assertTrue("$name retained sharp line contrast " + (brightest - darkest), brightest - darkest < 34)
        }
        crop.recycle()
        frame.recycle()
    }
}
