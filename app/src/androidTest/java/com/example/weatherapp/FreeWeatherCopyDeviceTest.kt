package com.example.weatherapp

import android.graphics.Paint
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class FreeWeatherCopyDeviceTest {
    @Test fun providerAndUnsupportedItemsAreEmphasizedAndWhyLinkIsUnderlined() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val time = System.currentTimeMillis()
        val rows = listOf(ForecastHour(time, "CLEAR_DAY", 20.0, 19.0, 12.0, 3.0, 50.0, 40.0))
        ActivityScenario.launch<ForecastDetailActivity>(
            ForecastDetailActivity.createIntent(context, "北京", rows)
        ).use { scenario ->
            scenario.onActivity { activity ->
                val notice = FreeWeatherNotice(activity, { WeatherSettings.SOURCE_OPEN_METEO }, { 1L }, { false })
                notice.maybeShow()
                val field = notice.javaClass.getDeclaredField("dialog").apply { isAccessible = true }
                fun activeDialog() = field.get(notice) as AlertDialog
                val dialog = activeDialog()
                val panel = dialog.findViewById<View>(R.id.freeWeatherNoticePanel)!!
                fun texts(view: View): List<TextView> =
                    if (view is TextView) listOf(view)
                    else if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                    else emptyList()
                val content = texts(panel)
                val title = content.single { it.text.toString() == "当前使用 Open-Meteo 天气源" }.text as Spanned
                assertEquals(1, title.getSpans(0, title.length, ForegroundColorSpan::class.java).size)
                val line = content.single { "暂不支持分钟级降雨" in it.text }
                assertTrue(line.typeface.isBold && "生活建议" in line.text)
                assertNotEquals(content.first().currentTextColor, line.currentTextColor)
                assertTrue(content.any { "不再提醒" in it.text })
                val link = dialog.findViewById<TextView>(R.id.freeWeatherNoticeWhy)!!
                assertTrue(link.isClickable && link.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0)
                link.performClick()
                assertTrue(texts(activeDialog().findViewById<View>(R.id.freeWeatherNoticePanel)!!).any {
                    "为什么需要自己的 API" in it.text
                })
                activeDialog().dismiss()
            }
        }
    }
}
