package com.example.weatherapp

import android.content.ContextWrapper
import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Shared live content binding: the card entry and all in-window pages use the same data path. */
internal class MetricDetailContent(
    private val activity: Activity,
    private val intent: Intent,
    private val scope: View
) : ContextWrapper(activity) {
    private fun <T : View> findViewById(id: Int): T = scope.findViewById(id)
    lateinit var kind: MetricKind
    private lateinit var value: TextView
    private lateinit var advice: TextView
    lateinit var chart: HourlyMetricChartView
    private lateinit var windMeta: TextView
    private var windDirections = DoubleArray(0)
    private var fallbackWindDirection = ""
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.CHINA)


    fun bind(darkTheme: Boolean) {
        kind = MetricKind.fromIntent(intent.getStringExtra(EXTRA_KIND))
        WeatherAddressFormatter.bind(findViewById(R.id.tvMetricDetailLocation), intent.getStringExtra(EXTRA_CITY).orEmpty())
        findViewById<TextView>(R.id.tvMetricDetailTitle).text = "${kind.title} · 未来24小时"
        value = findViewById(R.id.tvMetricDetailValue)
        advice = findViewById(R.id.tvMetricDetailAdvice)
        chart = findViewById(R.id.metricDetailChart)
        // 风力页额外展示“风向 / 阵风”一行。仅回收图表的 24dp，
        // 让该页与其他指标一样在默认字体下一屏完整呈现、无需纵向滚动。
        if (kind == MetricKind.WIND) {
            chart.layoutParams = chart.layoutParams.apply {
                height = dp(220)
            }
        }
        chart.setDarkTheme(darkTheme)
        @Suppress("DEPRECATION")
        val lifeAdvice = intent.getSerializableExtra(EXTRA_LIFE_ADVICE) as? ArrayList<LifeAdviceItem>
        @Suppress("DEPRECATION")
        val allLifeAdvice = intent.getSerializableExtra(EXTRA_ALL_LIFE_ADVICE) as? ArrayList<LifeAdviceItem>
            ?: lifeAdvice.orEmpty().let(::ArrayList)
        findViewById<LifeAdviceStripView>(R.id.metricLifeAdviceStrip).apply {
            setAdvice(lifeAdvice.orEmpty().take(3), darkTheme)
            setOnClickListener { card ->
                OplusViewSeamlessNavigator.startFromCard(
                    activity,
                    card,
                    LifeAdviceActivity.createIntent(activity, allLifeAdvice)
                )
            }
        }
        windMeta = findViewById(R.id.tvMetricDetailWindDirection)
        findViewById<TextView>(R.id.tvMetricDetailAboutTitle).text = "关于${kind.title}指标"
        findViewById<TextView>(R.id.tvMetricDetailAbout).text = kind.about
        findViewById<View>(R.id.btnMetricDetailBack).setOnClickListener {
            if (!DetailNavigator.move(activity, DetailNavigator.pageOf(kind), -1)) activity.finish()
        }
        findViewById<View>(R.id.btnMetricDetailNext).setOnClickListener {
            DetailNavigator.move(activity, DetailNavigator.pageOf(kind), 1)
        }

        val times = intent.getLongArrayExtra(EXTRA_TIMES) ?: LongArray(0)
        val values = intent.getDoubleArrayExtra(EXTRA_VALUES) ?: DoubleArray(0)
        val secondary = intent.getDoubleArrayExtra(EXTRA_SECONDARY)
            ?: DoubleArray(values.size) { Double.NaN }
        val yesterday = intent.getDoubleArrayExtra(EXTRA_YESTERDAY)
            ?: DoubleArray(0)
        val yesterdaySupplemented = intent.getBooleanExtra(EXTRA_YESTERDAY_SUPPLEMENTED, false)
        windDirections = intent.getDoubleArrayExtra(EXTRA_WIND_DIRECTIONS) ?: DoubleArray(0)
        fallbackWindDirection = intent.getStringExtra(EXTRA_WIND_DIRECTION).orEmpty()
        windMeta.visibility = if (kind == MetricKind.WIND) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvMetricDetailSummary).text =
            buildSummary(values, yesterday, yesterdaySupplemented)

        chart.onSelectionChanged = { index, time, primary, second ->
            val timeText = displayTime(time)
            value.text = "${kind.title} ${WeatherSettings.metricText(this, kind, primary)} · ${kind.level(primary, second)}"
            advice.text = if (kind == MetricKind.FEELS_LIKE) {
                // 舒适度与体感/实际温度差是两种不同信息；分两行稳定呈现，
                // 横滑穿过任意小时都不会因一行空间不足而省略温差。
                "$timeText · ${selectionAdvice(primary, second)}\n${MetricDisplayPolicy.feelsLikeComparison(
                    actual = WeatherSettings.tempDisplay(this, second),
                    feels = WeatherSettings.tempDisplay(this, primary),
                    unit = WeatherSettings.metricUnit(this, kind)
                )}"
            } else {
                "$timeText · ${selectionAdvice(primary, second)}"
            }
            if (kind == MetricKind.WIND) updateWindMeta(index, second)
        }
        chart.setSeries(kind, times, values, secondary)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun selectionAdvice(primary: Double?, secondary: Double?): String = when (kind) {
        MetricKind.PRESSURE -> "留意后续天气变化。"
        MetricKind.FEELS_LIKE -> FeelsLikeComfortPolicy.forCelsius(primary).advice
        else -> kind.advice(primary, secondary)
    }

    private fun updateWindMeta(index: Int, gust: Double?) {
        val direction = windDirectionText(windDirections.getOrNull(index)) ?: fallbackWindDirection
        val directionText = if (direction.isBlank()) "风向待更新" else "风向：$direction"
        val gustText = gust?.takeUnless { it.isNaN() }?.let {
            "阵风：${WeatherSettings.metricText(this, MetricKind.WIND, it)}"
        } ?: "暂无阵风数据"
        windMeta.text = "$directionText · $gustText"
    }

    private fun windDirectionText(degrees: Double?): String? {
        if (degrees == null || degrees.isNaN()) return null
        val names = arrayOf("北风", "东北风", "东风", "东南风", "南风", "西南风", "西风", "西北风")
        return names[((degrees + 22.5) / 45.0).toInt().mod(names.size)]
    }

    private fun buildSummary(
        values: DoubleArray,
        yesterday: DoubleArray,
        yesterdaySupplemented: Boolean
    ): String {
        val valid = values.filter { !it.isNaN() }
        if (valid.isEmpty()) return "未来24小时暂未取得${kind.title}数据，请稍后下拉刷新。"
        val min = valid.minOrNull() ?: 0.0
        val max = valid.maxOrNull() ?: 0.0
        val yesterdayValue = yesterday.firstOrNull()?.takeUnless { it.isNaN() }
        val current = valid.first()
        val compared = if (yesterdayValue == null) {
            "选中天气源未提供昨日同期数据"
        } else {
            val change = current - yesterdayValue
            // 当前唯一允许的历史补齐是本机已保存的彩云快照，不会混入第三方或估算值。
            val source = if (yesterdaySupplemented) "（彩云已存快照）" else ""
            when {
                kotlin.math.abs(change) < 0.1 -> "与昨日同期$source 基本持平"
                change > 0 -> "较昨日同期$source 升${WeatherSettings.metricDeltaText(this, kind, change)}"
                else -> "较昨日同期$source 降${WeatherSettings.metricDeltaText(this, kind, -change)}"
            }
        }
        return "未来24小时${kind.title}范围 ${WeatherSettings.metricText(this, kind, min)}–${WeatherSettings.metricText(this, kind, max)}，$compared。${kind.advice(max)}"
    }

    private fun displayTime(time: Long): String {
        if (time <= 0L) return "预测时段"
        val now = Calendar.getInstance()
        val selected = Calendar.getInstance().apply { timeInMillis = time }
        val tomorrow = (now.get(Calendar.DAY_OF_YEAR) != selected.get(Calendar.DAY_OF_YEAR))
        return if (tomorrow) "明日 ${clockFormat.format(time)}" else clockFormat.format(time)
    }

    companion object {
        private const val EXTRA_KIND = "metric_kind"
        private const val EXTRA_TIMES = "metric_times"
        private const val EXTRA_VALUES = "metric_values"
        private const val EXTRA_SECONDARY = "metric_secondary"
        private const val EXTRA_YESTERDAY = "metric_yesterday"
        private const val EXTRA_YESTERDAY_SUPPLEMENTED = "metric_yesterday_supplemented"
        private const val EXTRA_CITY = "metric_city"
        private const val EXTRA_WIND_DIRECTION = "metric_wind_direction"
        private const val EXTRA_WIND_DIRECTIONS = "metric_wind_directions"
        private const val EXTRA_LIFE_ADVICE = "metric_life_advice"
        private const val EXTRA_ALL_LIFE_ADVICE = "metric_all_life_advice"
    }
}
