package com.example.weatherapp

import android.content.ContextWrapper
import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale

/** Shared live content binding: the card entry and all in-window pages use the same data path. */
internal class SunDetailContent(
    private val activity: Activity,
    private val intent: Intent,
    private val scope: View
) : ContextWrapper(activity) {
    private fun <T : View> findViewById(id: Int): T = scope.findViewById(id)
    private val clock = SimpleDateFormat("HH:mm", Locale.CHINA)
    private lateinit var data: SunAstronomyData
    private lateinit var phases: SunPhaseTimes
    private lateinit var detail: TextView
    lateinit var chart: SunAltitudeChartView
    private lateinit var advice: TextView


    fun bind(dark: Boolean) {
        WeatherAddressFormatter.bind(findViewById(R.id.tvSunDetailCity), intent.getStringExtra(EXTRA_CITY).orEmpty())
        val rawData = SunAstronomyData(
            intent.getLongExtra(EXTRA_SUNRISE, 0L), intent.getLongExtra(EXTRA_SUNSET, 0L),
            intent.getLongExtra(EXTRA_YESTERDAY_SUNSET, 0L), intent.getLongExtra(EXTRA_NEXT_SUNRISE, 0L),
            intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LNG, 0.0),
            intent.getStringExtra(EXTRA_SOURCE).orEmpty().ifBlank { "天气数据" },
            intent.getStringExtra(EXTRA_SKYCON)
        )
        // 入口在首轮天气请求尚未结束时也可以打开；这里同步补齐本地天文数据，避免任何加载文案。
        data = SunAstronomyCalculator.resolveForDisplay(rawData)
        val curve = SunAstronomyCalculator.hourlyCurve(data)
        phases = SunAstronomyCalculator.phases(data, curve)
        val (morningGlow, eveningGlow) = SunAstronomyCalculator.glowForecasts(data, phases)
        detail = findViewById(R.id.tvSunDetailSelected)
        advice = findViewById(R.id.tvSunDetailAdvice)
        findViewById<TextView>(R.id.tvSunDetailSummary).text = buildSummary()
        findViewById<TextView>(R.id.tvSunDetailNoon).text = time(phases.solarNoon)
        findViewById<TextView>(R.id.tvSunDetailDaylight).text = daylightText()
        findViewById<TextView>(R.id.tvSunDetailBlueTimes).text = buildPeriodCard(
            phases.blueMorningStart, phases.blueMorningEnd,
            phases.blueEveningStart, phases.blueEveningEnd
        )
        findViewById<TextView>(R.id.tvSunDetailGoldenTimes).text = buildPeriodCard(
            phases.goldenMorningStart, phases.goldenMorningEnd,
            phases.goldenEveningStart, phases.goldenEveningEnd
        )
        findViewById<TextView>(R.id.tvSunDetailMorningGlow).text = buildGlowCard(morningGlow)
        findViewById<TextView>(R.id.tvSunDetailEveningGlow).text = buildGlowCard(eveningGlow)
        chart = findViewById<SunAltitudeChartView>(R.id.sunAltitudeChart).apply {
            onSelectionChanged = { time, elevation -> updateSelection(time, elevation) }
            setData(curve, dark)
        }
    }

    private fun buildSummary(): String {
        if (data.sunriseMillis <= 0L || data.sunsetMillis <= data.sunriseMillis) {
            return "当地可能处于极昼、极夜，或暂无可用的日出日落交点；晨昏时段仍会按本地太阳高度展示。"
        }
        val duration = data.sunsetMillis - data.sunriseMillis
        val hours = duration / 3_600_000L
        val minutes = duration % 3_600_000L / 60_000L
        val yesterdayEstimate = SunAstronomyCalculator.estimatedDaylightDuration(
            data.copy(sunriseMillis = data.sunriseMillis - 24L * 3_600_000L)
        )
        val change = yesterdayEstimate?.let { previous ->
            val deltaMinutes = ((duration - previous) / 60_000L).toInt()
            when {
                kotlin.math.abs(deltaMinutes) <= 1 -> "与昨日昼长基本持平"
                deltaMinutes > 0 -> "较昨日长约${deltaMinutes}分钟"
                else -> "较昨日短约${-deltaMinutes}分钟"
            }
        } ?: "昨日昼长待计算"
        return "日出 ${time(data.sunriseMillis)}，日落 ${time(data.sunsetMillis)}，昼长 ${hours}小时${minutes}分钟，$change。"
    }

    private fun daylightText(): String {
        if (data.sunriseMillis <= 0L || data.sunsetMillis <= data.sunriseMillis) return "--"
        val minutes = (data.sunsetMillis - data.sunriseMillis) / 60_000L
        return "${minutes / 60}小时${minutes % 60}分"
    }

    private fun buildPeriodCard(
        morningStart: Long?, morningEnd: Long?, eveningStart: Long?, eveningEnd: Long?
    ): String = "早晨 ${period(morningStart, morningEnd)}\n傍晚 ${period(eveningStart, eveningEnd)}"

    private fun buildGlowCard(glow: SunGlowForecast): String =
        "${period(glow.startMillis, glow.endMillis)} · ${glow.level} ${glow.score}%\n${glow.description}"

    private fun updateSelection(time: Long, elevation: Double) {
        val phase = SunAstronomyCalculator.sunDirection(time, data.latitude, data.longitude)
        val azimuth = SunAstronomyCalculator.sunAzimuth(time, data.latitude, data.longitude)
        val event = when {
            isNear(time, data.sunriseMillis) -> "日出时刻"
            isNear(time, data.sunsetMillis) -> "日落时刻"
            else -> null
        }
        // 日出、日落的说明放在小字提示中；大字只保留可连续读取的时间与太阳位置，
        // 避免拖动到事件点时重复出现突兀的“日出时刻 / 日落时刻”标题。
        detail.text = listOfNotNull(
            time(time), "太阳高度 ${String.format(Locale.CHINA, "%.1f", elevation)}°", "方位 ${azimuth.toInt()}°", phase
        ).joinToString(" · ")
        advice.text = when {
            event == "日出时刻" -> "日出时刻，光线快速增强，注意低角度眩光。"
            event == "日落时刻" -> "日落时刻，天色转暗，外出请留意照明。"
            elevation < -6 -> "天色较暗，户外活动请留意照明与路况。"
            elevation < 0 -> "处于蓝调时刻，光线变化快，拍摄可适当提高曝光。"
            elevation < 6 -> "处于金色时刻，阳光角度低，注意逆光与眩光。"
            elevation > 45 -> "日照较强，外出可结合紫外线指数做好防晒。"
            else -> "光线平稳，适合按日常安排出行。"
        }
    }

    private fun period(start: Long?, end: Long?): String = if (start == null || end == null) "当地无此时段" else "${time(start)}–${time(end)}"
    private fun time(value: Long): String = if (value <= 0L) "--:--" else clock.format(value)
    private fun isNear(value: Long, target: Long): Boolean = target > 0L && kotlin.math.abs(value - target) <= 12L * 60_000L

    companion object {
        private const val EXTRA_CITY = "sun_city"
        private const val EXTRA_SUNRISE = "sun_sunrise"
        private const val EXTRA_SUNSET = "sun_sunset"
        private const val EXTRA_YESTERDAY_SUNSET = "sun_yesterday_sunset"
        private const val EXTRA_NEXT_SUNRISE = "sun_next_sunrise"
        private const val EXTRA_LAT = "sun_lat"
        private const val EXTRA_LNG = "sun_lng"
        private const val EXTRA_SOURCE = "sun_source"
        private const val EXTRA_SKYCON = "sun_skycon"
    }
}
