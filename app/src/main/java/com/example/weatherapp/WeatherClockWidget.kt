package com.example.weatherapp

import android.icu.util.ChineseCalendar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal object WeatherClockWidget {
    const val HOURLY = "clock_hourly"
    const val WEEKLY = "clock_weekly"
    const val CLOCK_FORMAT = "HH:mm"

    fun supports(kind: String) = kind == HOURLY || kind == WEEKLY

    data class Layout(val inset: Float, val top: Float, val size: Float, val scale: Float)

    fun layout(width: Int, height: Int): Layout {
        val w = width.coerceIn(120, 600)
        val h = height.coerceIn(64, 700)
        val inset = WeatherWidgetRenderer.contentInset(w, h)
        val scale = minOf(1f, (w - inset * 2) / 278f, (h - inset * 2) / 147f).coerceAtLeast(.29f)
        return Layout(inset, inset + 15f * scale, 47f * scale, scale)
    }

    fun clock(now: Long): String = SimpleDateFormat(CLOCK_FORMAT, Locale.CHINA).format(Date(now))

    private val months = listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val days = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

    fun date(now: Long): String {
        val civil = SimpleDateFormat("M/d EEE", Locale.CHINA).format(Date(now)).replace("星期", "周")
        val lunar = ChineseCalendar().apply { timeInMillis = now }
        val day = lunar.get(ChineseCalendar.DAY_OF_MONTH)
        val label = when (day) {
            in 1..10 -> "初" + days[day - 1]
            in 11..19 -> "十" + days[day - 11]
            20 -> "二十"
            in 21..29 -> "廿" + days[day - 21]
            else -> "三十"
        }
        val leap = if (lunar.get(ChineseCalendar.IS_LEAP_MONTH) == 1) "闰" else ""
        return "$civil · $leap${months[lunar.get(ChineseCalendar.MONTH)]}月$label"
    }

    fun nextMidnight(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DATE, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
