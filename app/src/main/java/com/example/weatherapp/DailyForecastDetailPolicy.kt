package com.example.weatherapp

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Daily forecasts are kept separate from hourly rows: unavailable hours are never invented. */
data class ForecastDaySnapshot(
    val date: String, val skycon: String?, val high: Double?, val low: Double?, val rainPercent: Int?
) : Serializable {
    companion object {
        fun from(day: DailyForecast) = ForecastDaySnapshot(day.date.take(10),day.skycon,
            day.high?.toDouble(),day.low?.toDouble(),day.precipProb?.takeIf {it in 0..100})
    }
}

internal data class ForecastDayPage(val date: String, val hours: List<ForecastHour>, val daily: ForecastDaySnapshot?)

internal object DailyForecastDetailPolicy {
    fun dateKey(time: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd",Locale.US).apply {timeZone=zone}.format(Date(time))

    fun pages(hours: List<ForecastHour>, daily: List<ForecastDaySnapshot>, zone: TimeZone = TimeZone.getDefault()): List<ForecastDayPage> {
        val grouped=hours.distinctBy {it.time}.sortedBy {it.time}.groupBy {dateKey(it.time,zone)}
        val snapshots=daily.filter {it.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))}.associateBy {it.date}
        return (grouped.keys+snapshots.keys).sorted().map {ForecastDayPage(it,grouped[it].orEmpty(),snapshots[it])}
    }

    fun initialIndex(pages: List<ForecastDayPage>, requested: String?, now: Long = System.currentTimeMillis()): Int {
        val exact=pages.indexOfFirst {it.date==requested}
        if(exact>=0) return exact
        return pages.indexOfFirst {it.date>=dateKey(now)}.takeIf {it>=0} ?: pages.lastIndex.coerceAtLeast(0)
    }

    fun temperatureRange(page: ForecastDayPage): Pair<Double,Double>? {
        val values=page.hours.mapNotNull {it.temperature?.takeIf(Double::isFinite)}+
            listOfNotNull(page.daily?.low?.takeIf(Double::isFinite),page.daily?.high?.takeIf(Double::isFinite))
        return if(values.isEmpty()) null else values.minOrNull()!! to values.maxOrNull()!!
    }

    fun temperatureAdvice(low: Double, high: Double): String = when {
        high>=33 -> "天气炎热，轻薄着装并及时补水"
        low<=5 -> "早晚寒冷，外出注意添衣保暖"
        high-low>=8 -> "昼夜温差较大，建议分层穿衣"
        high<18 -> "天气偏凉，建议备一件外套"
        else -> "温度较舒适，按需增减衣物"
    }

    /** The same midpoint boundaries drive hit testing and the full-column pressed shade. */
    fun columnAt(x: Float, width: Float, inset: Float, count: Int): Int? {
        if(count<=0 || width<=0 || x<0 || x>width) return null
        if(count==1) return 0
        val span=(width-2*inset).coerceAtLeast(1f)
        return ((x-inset)/span*(count-1)).roundToInt().coerceIn(0,count-1)
    }

    fun columnBounds(index: Int, width: Float, inset: Float, count: Int): Pair<Float,Float> {
        if(count<=1) return 0f to width
        val step=(width-2*inset).coerceAtLeast(1f)/(count-1)
        return (if(index==0) 0f else inset+(index-.5f)*step) to
            (if(index==count-1) width else inset+(index+.5f)*step)
    }
}
