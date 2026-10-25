package com.example.weatherapp

import android.graphics.Color

import android.content.ContextWrapper
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Shared live content binding: the card entry and all in-window pages use the same data path. */
internal class AirQualityDetailContent(
    private val activity: Activity,
    private val intent: Intent,
    private val scope: View
) : ContextWrapper(activity) {
    private fun <T : View> findViewById(id: Int): T = scope.findViewById(id)
    private fun runOnUiThread(action: () -> Unit) = activity.runOnUiThread(action)
    private val isFinishing get() = activity.isFinishing
    private val isDestroyed get() = activity.isDestroyed
    lateinit var chart: AqiHourlyBarChartView
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)


    fun bind(darkTheme: Boolean) {
        val city = intent.getStringExtra(EXTRA_CITY).orEmpty().ifBlank { "当前位置" }
        WeatherAddressFormatter.bind(findViewById(R.id.tvAirQualityLocation), city)
        val now = System.currentTimeMillis()
        // 详情页必须使用“天气源实时 AQI + 趋势污染物补全”后的同一快照。
        // 旧逻辑优先取原始实时对象（常常只有 AQI），会把已补齐的 PM2.5/NO₂ 等字段重新置空。
        val points = AirQualitySnapshotPolicy.mergeCurrentIntoTrend(
            readPoints(), intentPoint(), now
        )
        // 首页实时 AQI 是当前天气源的权威快照；详情页仪表、污染物和曲线的“现在”
        // 必须复用同一对象，不能因为趋势补充数据而显示另一套数值。
        val current = enrichCurrentPollutants(points, points.minByOrNull { abs(it.timeMillis - now) })
        val future = points.filter { it.timeMillis >= now - 30 * 60_000L }
            .sortedBy { it.timeMillis }
            .take(24)
            .ifEmpty { listOfNotNull(current) }
        val yesterday = points.filter { it.timeMillis < now - 8 * 60 * 60_000L }
            .maxByOrNull { it.timeMillis }
        val aqi = current?.aqi
        findViewById<TextView>(R.id.tvAirQualitySummary).text = buildSummary(aqi, future, yesterday?.aqi)
        if(intent.getStringExtra(EXTRA_SOURCE).orEmpty().contains("Open-Meteo")) {
            findViewById<TextView>(R.id.tvAirQualityDetailTitle).text="空气质量参考 · 未来24小时"
            findViewById<TextView>(R.id.tvAirQualitySummary).append("（模型参考估算，非监测站实测）")
        }
        findViewById<AirQualityGaugeView>(R.id.airQualityGauge).setData(aqi, darkTheme)
        findViewById<AirPollutantGridView>(R.id.airPollutantGrid).setData(current, darkTheme)
        findViewById<TextView>(R.id.tvAqiLegend).text =
            "AQI 色阶：优 0–50 · 良 51–100 · 轻度 101–150 · 中度 151–200"
        val chartValue = findViewById<TextView>(R.id.tvAqiHourlyValue)
        val chartAdvice = findViewById<TextView>(R.id.tvAqiHourlyAdvice)
        chart = findViewById<AqiHourlyBarChartView>(R.id.aqiHourlyChart).apply {
            onSelectionChanged = { point ->
                val time = Calendar.getInstance().apply { timeInMillis = point.timeMillis }
                val today = Calendar.getInstance()
                val dayPrefix = if (time.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)) "明日 " else ""
                val value = point.aqi
                chartValue.text = "AQI ${value?.toInt() ?: "--"} · ${AirQualityScale.level(value)} · ${dayPrefix}${timeFormat.format(point.timeMillis)}"
                chartAdvice.text = AirQualityScale.advice(value)
            }
            setData(future, darkTheme)
        }
        val rank = findViewById<TextView>(R.id.tvAirQualityRank)
        val rankPercent = findViewById<TextView>(R.id.tvAirQualityRankPercent)
        // 排名必须同一次官方发布且全量城市可解析；任一条件不满足就明确不可用，绝不回显旧结果。
        val rankingCity = intent.getStringExtra(EXTRA_RANKING_CITY).orEmpty().ifBlank { city }
        Thread {
            val result = MeeAirQualityRankRepository.fetch(rankingCity)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is MeeAirQualityRankRepository.Result.Available -> {
                        val label = "全国第 ${result.rank} / ${result.total}"
                        rank.text = SpannableString(label).apply {
                            val start = label.indexOf(result.rank.toString())
                            if (start >= 0) setSpan(
                                StyleSpan(Typeface.BOLD), start, start + result.rank.toString().length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        rankPercent.text = "全国前 ${result.topPercent}%"
                        // 排名卡是浅色面板，不能使用白字；资源会随深浅主题解析为可读正文色。
                        rank.setTextColor(getColor(R.color.text_primary))
                        rankPercent.setTextColor(rankPercentColor(result.topPercent))
                    }
                    else -> {
                        rank.text = "全国排名暂不可用"
                        rankPercent.text = ""
                        rank.setTextColor(getColor(R.color.text_primary))
                    }
                }
            }
        }.start()
    }

    private fun buildSummary(current: Double?, future: List<AirQualityPoint>, yesterday: Double?): String {
        val values = future.mapNotNull { it.aqi }
        if (current == null || values.isEmpty()) return "暂未取得完整空气质量数据；联网后会自动补齐未来 24 小时趋势。"
        val min = values.minOrNull()?.toInt() ?: current.toInt()
        val max = values.maxOrNull()?.toInt() ?: current.toInt()
        val compare = yesterday?.let {
            when {
                abs(current - it) < 3 -> "与昨日同期基本持平"
                current > it -> "较昨日同期升${(current - it).toInt()}"
                else -> "较昨日同期降${(it - current).toInt()}"
            }
        } ?: "昨日同期数据待补充"
        return "未来24小时 AQI 为 $min–$max，$compare。${AirQualityScale.advice(max.toDouble())}"
    }

    private fun rankPercentColor(percent: Int): Int = when {
        percent <= 30 -> Color.rgb(87, 211, 128)
        percent <= 70 -> Color.rgb(255, 210, 71)
        else -> Color.rgb(255, 160, 76)
    }

    /**
     * 某些天气源的“实况”只给 AQI，六项污染物存在于临近小时点。
     * 当前 AQI 仍以实况为准，而每种缺失污染物单独从时间线最近的有效点补齐，避免网格整体显示缺失。
     */
    private fun enrichCurrentPollutants(
        points: List<AirQualityPoint>,
        current: AirQualityPoint?
    ): AirQualityPoint? {
        val base = current ?: return null
        fun closest(selector: (AirQualityPoint) -> Double?): Double? = points
            .asSequence()
            .filter { selector(it) != null }
            .minByOrNull { abs(it.timeMillis - base.timeMillis) }
            ?.let(selector)
        return base.copy(
            pm25 = base.pm25 ?: closest { it.pm25 },
            pm10 = base.pm10 ?: closest { it.pm10 },
            no2 = base.no2 ?: closest { it.no2 },
            o3 = base.o3 ?: closest { it.o3 },
            co = base.co ?: closest { it.co },
            so2 = base.so2 ?: closest { it.so2 }
        )
    }

    private fun readPoints(): List<AirQualityPoint> {
        val times = intent.getLongArrayExtra(EXTRA_TIMES) ?: LongArray(0)
        fun values(key: String) = intent.getDoubleArrayExtra(key) ?: DoubleArray(times.size) { Double.NaN }
        val aqi = values(EXTRA_AQI)
        val pm25 = values(EXTRA_PM25)
        val pm10 = values(EXTRA_PM10)
        val no2 = values(EXTRA_NO2)
        val o3 = values(EXTRA_O3)
        val co = values(EXTRA_CO)
        val so2 = values(EXTRA_SO2)
        return times.indices.map { index ->
            AirQualityPoint(
                times[index], aqi.valueAt(index), pm25.valueAt(index), pm10.valueAt(index),
                no2.valueAt(index), o3.valueAt(index), co.valueAt(index), so2.valueAt(index)
            )
        }.filter { it.timeMillis > 0L }
    }

    private fun intentPoint(): AirQualityPoint? {
        val value = intent.getDoubleExtra(EXTRA_CURRENT_AQI, Double.NaN).takeUnless { it.isNaN() }
            ?: return null
        fun optional(key: String) = intent.getDoubleExtra(key, Double.NaN).takeUnless { it.isNaN() }
        return AirQualityPoint(
            intent.getLongExtra(EXTRA_CURRENT_TIME, System.currentTimeMillis()), value,
            optional(EXTRA_CURRENT_PM25), optional(EXTRA_CURRENT_PM10), optional(EXTRA_CURRENT_NO2),
            optional(EXTRA_CURRENT_O3), optional(EXTRA_CURRENT_CO), optional(EXTRA_CURRENT_SO2)
        )
    }

    private fun DoubleArray.valueAt(index: Int): Double? =
        getOrNull(index)?.takeUnless { it.isNaN() }

    companion object {
        private const val EXTRA_CITY = "air_quality_city"
        private const val EXTRA_TIMES = "air_quality_times"
        private const val EXTRA_AQI = "air_quality_aqi"
        private const val EXTRA_PM25 = "air_quality_pm25"
        private const val EXTRA_PM10 = "air_quality_pm10"
        private const val EXTRA_NO2 = "air_quality_no2"
        private const val EXTRA_O3 = "air_quality_o3"
        private const val EXTRA_CO = "air_quality_co"
        private const val EXTRA_SO2 = "air_quality_so2"
        private const val EXTRA_CURRENT_AQI = "air_quality_current_aqi"
        private const val EXTRA_CURRENT_TIME = "air_quality_current_time"
        private const val EXTRA_CURRENT_PM25 = "air_quality_current_pm25"
        private const val EXTRA_CURRENT_PM10 = "air_quality_current_pm10"
        private const val EXTRA_CURRENT_NO2 = "air_quality_current_no2"
        private const val EXTRA_CURRENT_O3 = "air_quality_current_o3"
        private const val EXTRA_CURRENT_CO = "air_quality_current_co"
        private const val EXTRA_CURRENT_SO2 = "air_quality_current_so2"
        private const val EXTRA_SOURCE = "air_quality_source"
        private const val EXTRA_RANKING_CITY = "air_quality_ranking_city"
    }
}
