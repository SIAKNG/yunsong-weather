package com.example.weatherapp

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.view.MotionEvent
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** The 48-hour activity and each daily page render the same summary and chart components. */
class HourlyForecastDetailActivity : AppCompatActivity() {
    private lateinit var palette: HourlyForecastDetailPalette
    private fun dp(value: Int)=(value*resources.displayMetrics.density).roundToInt()
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        DetailCardEdges.apply(findViewById(android.R.id.content))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark=WeatherSettings.isDarkTheme(this)
        palette=HourlyForecastDetailPalette.from(this,dark)
        val bars=if(dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT,Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle=bars,navigationBarStyle=bars)
        if(android.os.Build.VERSION.SDK_INT>=29) window.isNavigationBarContrastEnforced=false
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(palette.background)}
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) {v,i ->
            val b=i.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);i
        }
        root.addView(buildTitleBar())
        @Suppress("DEPRECATION")
        val rows=(intent.getSerializableExtra(EXTRA_HOURS) as? ArrayList<ForecastHour>).orEmpty()
        if(rows.isEmpty()) {
            root.addView(TextView(this).apply {text="暂无逐小时预报数据，请返回主页刷新后重试。";textSize=14f;setTextColor(palette.secondary);setPadding(dp(24),dp(32),dp(24),0)})
        } else root.addView(ForecastWeatherPage(this,HourlyForecastDetailPolicy.window(rows),
            currentTemperatureCelsius=intent.getDoubleExtra(EXTRA_CURRENT_TEMPERATURE,Double.NaN).takeIf(Double::isFinite)).view,
            LinearLayout.LayoutParams(-1,0,1f))
    }

    private fun buildTitleBar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(7), dp(12), dp(5))
        addView(ImageView(this@HourlyForecastDetailActivity).apply {
            setImageResource(R.drawable.ic_back_arrow)
            setColorFilter(palette.primary)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(this@HourlyForecastDetailActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@HourlyForecastDetailActivity).apply {
                WeatherAddressFormatter.bind(this, intent.getStringExtra(EXTRA_CITY).orEmpty())
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(palette.primary)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@HourlyForecastDetailActivity).apply {
                text = "48小时天气"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(palette.secondary)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // 保留等宽占位，确保双行标题始终几何居中。
        addView(View(this@HourlyForecastDetailActivity), LinearLayout.LayoutParams(dp(44), 1))
    }

    companion object {
        private const val EXTRA_CITY = "city"
        private const val EXTRA_HOURS = "hours"
        private const val EXTRA_CURRENT_TEMPERATURE = "current_temperature_celsius"

        fun createIntent(
            context: Context,
            city: String,
            rows: List<ForecastHour>,
            currentTemperatureCelsius: Double? = null
        ): Intent =
            Intent(context, HourlyForecastDetailActivity::class.java).apply {
                putExtra(EXTRA_CITY, city)
                putExtra(EXTRA_HOURS, ArrayList(rows))
                currentTemperatureCelsius?.takeIf(Double::isFinite)?.let {
                    putExtra(EXTRA_CURRENT_TEMPERATURE, it)
                }
            }
    }
}

/** A reusable, one-screen forecast body. Every instance owns exactly one immutable day/window. */
internal class ForecastWeatherPage(
    activity: Activity,
    private val points: List<ForecastHour>,
    private val day: ForecastDayPage? = null,
    private val currentTemperatureCelsius: Double? = null,
    private val initialScrollX: Int = 0,
    private val currentDay: String = DailyForecastDetailPolicy.dateKey(System.currentTimeMillis()),
    private val onScrollPosition: ((Int)->Unit)? = null,
    private val onReturnToCurrent: (()->Unit)? = null,
    private val initialMetric: String? = null,
    private val onMetricChanged: ((String)->Unit)? = null
) : ContextWrapper(activity) {
    private fun dp(value: Int)=(value*resources.displayMetrics.density).roundToInt()
    private val palette=HourlyForecastDetailPalette.from(this,WeatherSettings.isDarkTheme(this))
    private var selectedIndex=HourlyForecastDetailPolicy.initialIndex(points)
    private val currentIndex=selectedIndex
    private lateinit var chart: HourlyForecastCompositeChartView
    private lateinit var hourlyScroll: HorizontalScrollView
    private lateinit var returnCurrentButton: TextView
    private var returnCurrentState: Boolean?=null
    private var pendingScrollX=0
    private var selectionPosted=false
    private var dailyPanel: DailyForecastMetricPanel? = null
    val selectedMetric: String? get() = dailyPanel?.selectedMetric?.name
    private val periodLabel get()=if(day==null) "未来48小时" else "当日"
    val summaryMetrics=buildSummaryMetrics()
    val view=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;tag="forecast_page:${day?.date ?: "48h"}"
        setPadding(dp(20),dp(4),dp(20),dp(12))
        addSummary(this);addChartSection(this)
    }
    init {DetailCardEdges.apply(view)}
    fun resetToCurrent() {
        dailyPanel?.resetToCurrent()
        if(::hourlyScroll.isInitialized) hourlyScroll.smoothScrollTo(0,0)
    }
    private fun addSummary(parent: LinearLayout) {
        if (day == null) parent.addView(sectionTitle("信息摘要"))
        else {
            val today=DailyForecastDetailPolicy.dateKey(System.currentTimeMillis())
            val atToday=day.date==today
            val unavailable=currentDay!=today
            val row=LinearLayout(this).apply {tag="daily_summary_header";gravity=Gravity.CENTER_VERTICAL}
            row.addView(sectionTitle("信息摘要"),LinearLayout.LayoutParams(0,-2,1f))
            val todayButton=FrostedActionButton(this).apply {
                tag="forecast_return_today";text="返回今日";textSize=11f;gravity=Gravity.CENTER
                setOnClickListener {onReturnToCurrent?.invoke() ?: resetToCurrent()}
            }
            DetailReturnCurrentButtonStyle.apply(todayButton,atToday || unavailable,palette.primary,palette.secondary,
                ColorUtils.setAlphaComponent(palette.primary,28),resources.displayMetrics.density)
            todayButton.contentDescription=when {unavailable -> "暂无今日预报";atToday -> "已位于今日";else -> "返回今日天气"}
            ViewCompat.setStateDescription(todayButton,if(atToday || unavailable) "不可点击" else "可点击")
            row.addView(todayButton,LinearLayout.LayoutParams(dp(76),dp(30)))
            parent.addView(row,LinearLayout.LayoutParams(-1,dp(36)))
        }
        parent.addView(
            HourlyForecastSummaryView(
                context = this,
                panelColor = palette.card,
                pageBackgroundColor = palette.background,
                primary = palette.primary,
                secondary = palette.secondary,
                divider = palette.grid,
                metrics = summaryMetrics
            ),
            // 摘要的建议文本需要可在一眼内读完；图表区域仍以 weight 占用其余高度，
            // 因此增加摘要高度不会引入纵向滚动或页面底部空白。
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        )
    }

    /**
     * 摘要以五项可扫读的信息图呈现：数值、时间/状态与建议分层保留，避免把原来
     * 五段说明性长文再次做成一张“文字卡片”。所有峰值仅取实际非历史逐小时数据。
     */
    private fun buildSummaryMetrics(): List<HourlyForecastSummaryMetric> {
        val futurePoints = if (day != null) points else points.filterNot { it.isHistorical }.ifEmpty { points }
        val temperatures = day?.let { DailyForecastDetailPolicy.temperatureRange(it)?.let {range -> listOf(range.first,range.second)} }.orEmpty().ifEmpty {points.mapNotNull { it.temperature?.takeIf(Double::isFinite) }}
        val temperature = if (temperatures.isEmpty()) {
            HourlyForecastSummaryMetric(
                kind = HourlyForecastSummaryKind.TEMPERATURE,
                label = "气温范围",
                value = "—",
                detail = "温度待补充",
                advice = "请下拉刷新后再查看",
                progress = 0f,
                color = palette.temperature
            )
        } else {
            val low = temperatures.minOrNull()!!
            val high = temperatures.maxOrNull()!!
            val displayedSpread = (WeatherSettings.tempDisplay(this, high) ?: high) -
                (WeatherSettings.tempDisplay(this, low) ?: low)
            val realtimeTemperature = if (day == null || day.date == DailyForecastDetailPolicy.dateKey(System.currentTimeMillis())) {
                currentTemperatureCelsius?.takeIf(Double::isFinite)
                    ?: points.firstOrNull { !it.isHistorical && it.temperature?.isFinite() == true }?.temperature
            } else null
            val displayedLow = WeatherSettings.tempDisplay(this, low) ?: low
            val displayedHigh = WeatherSettings.tempDisplay(this, high) ?: high
            val displayedCurrent = realtimeTemperature?.let { WeatherSettings.tempDisplay(this, it) }
            val currentPosition = displayedCurrent?.let { current ->
                if (displayedHigh <= displayedLow) .5f
                else ((current - displayedLow) / (displayedHigh - displayedLow)).toFloat().coerceIn(0f, 1f)
            }
            val outlook = if(day == null) twoDayTemperatureOutlook(futurePoints) else
                "${day.daily?.skycon?.let {WeatherText.skyconToText(it)}.orEmpty()} · 当日温差${((high-low)*if(WeatherSettings.tempUnit(this)=="f") 1.8 else 1.0).roundToInt()}°".trim(' ','·') to DailyForecastDetailPolicy.temperatureAdvice(low,high)
            HourlyForecastSummaryMetric(
                kind = HourlyForecastSummaryKind.TEMPERATURE,
                label = "气温范围",
                value = "${WeatherSettings.tempText(this, low)}–${WeatherSettings.tempText(this, high)}",
                detail = outlook.first,
                advice = outlook.second,
                progress = (displayedSpread / 14.0).toFloat().coerceIn(0f, 1f),
                color = palette.temperature,
                currentValue = realtimeTemperature?.let { WeatherSettings.tempText(this, it) },
                currentProgress = currentPosition,
                minimumTemperatureCelsius = low,
                maximumTemperatureCelsius = high
            )
        }

        val rain = futurePoints.mapNotNull { point ->
            HourlyForecastDetailPolicy.percent(point.precipitationProbability)?.let { point to it }
        }
        val dailyRain=day?.daily?.rainPercent
        val rainMetric = when {
            dailyRain != null -> HourlyForecastSummaryMetric(
                HourlyForecastSummaryKind.RAIN,"降水概率",if(dailyRain==0) "无雨" else "$dailyRain%",periodLabel,
                if(dailyRain>=30) "出门建议备伞" else "出行留意天气变化",dailyRain/100f,palette.rain)
            rain.isEmpty() -> HourlyForecastSummaryMetric(
                HourlyForecastSummaryKind.RAIN, "降水概率", "—", "概率待补充", "出门前留意实时天气", 0f, palette.rain
            )
            rain.size == futurePoints.size && rain.all { it.second == 0 } -> HourlyForecastSummaryMetric(
                HourlyForecastSummaryKind.RAIN, "降水概率", "无雨", if(day != null && futurePoints.size<24) "已提供时段" else periodLabel, if(day==null) "可放心安排出行" else "出行留意天气变化", 0f, palette.rain
            )
            else -> {
                val (point, value) = rain.maxByOrNull { it.second }!!
                HourlyForecastSummaryMetric(
                    kind = HourlyForecastSummaryKind.RAIN,
                    label = "降水概率",
                    value = "$value%",
                    detail = compactTime(point.time),
                    advice = when {
                        value >= 70 -> "建议带伞"
                        value >= 30 -> "建议带伞"
                        else -> "常规出行即可"
                    },
                    progress = value / 100f,
                    color = palette.rain
                )
            }
        }

        val winds = futurePoints.mapNotNull { it.wind?.takeIf(Double::isFinite) }
        val windMetric = if (winds.isNotEmpty()) {
            val low = winds.minOrNull()!!
            val high = winds.maxOrNull()!!
            val unit = WeatherSettings.metricUnit(this, MetricKind.WIND)
            val lowText = WeatherSettings.windSpeedValue(this, low, null).removeSuffix(unit)
            val highText = WeatherSettings.windSpeedValue(this, high, null)
            HourlyForecastSummaryMetric(
                kind = HourlyForecastSummaryKind.WIND,
                label = "风力范围",
                value = "$lowText–$highText",
                detail = periodLabel,
                advice = when {
                    high < 12 -> "风力温和，适合出行"
                    high < 25 -> "阵风时注意防风"
                    else -> "风力较强，减少高处停留"
                },
                progress = (high / 45.0).toFloat().coerceIn(0f, 1f),
                color = palette.wind
            )
        } else HourlyForecastSummaryMetric(
            HourlyForecastSummaryKind.WIND, "风力范围", "—", "风力待补充", "请下拉刷新后再查看", 0f, palette.wind
        )

        val uvPeak = futurePoints.mapNotNull { point ->
            point.uv?.takeIf(Double::isFinite)?.let { point to it }
        }.maxByOrNull { it.second }
        val uvMetric = uvPeak?.let { (point, value) ->
            HourlyForecastSummaryMetric(
                kind = HourlyForecastSummaryKind.UV,
                label = "紫外线峰值",
                value = "${value.roundToInt()}级",
                detail = "${ForecastMetricSemanticLabel.uv(value)} · ${compactTime(point.time)}",
                advice = when {
                    value <= 2 -> "按需防晒"
                    value <= 5 -> "基础防晒"
                    else -> "避免午间暴晒"
                },
                progress = (value / 11.0).toFloat().coerceIn(0f, 1f),
                color = palette.ultraviolet
            )
        } ?: HourlyForecastSummaryMetric(
            HourlyForecastSummaryKind.UV, "紫外线峰值", "—", "紫外线待补充", "白天外出注意防晒", 0f, palette.ultraviolet
        )

        val aqi = futurePoints.mapNotNull { point ->
            point.aqi?.takeIf(Double::isFinite)?.let { point to it }
        }
        val aqiMetric = if (aqi.isEmpty()) {
            HourlyForecastSummaryMetric(
                HourlyForecastSummaryKind.AQI, "空气质量", "—", "AQI 待补充", "出门前留意实时空气质量", 0f, palette.aqi
            )
        } else {
            val worstValue = aqi.maxOf { it.second }
            val bestValue = aqi.minOf { it.second }
            val average = aqi.map { it.second }.average()
            HourlyForecastSummaryMetric(
                kind = HourlyForecastSummaryKind.AQI,
                label = "空气质量",
                value = "AQI ${bestValue.roundToInt()}–${worstValue.roundToInt()}",
                detail = airQualityRangeSummary(bestValue, worstValue, average),
                advice = compactAirTravelAdvice(worstValue),
                progress = (worstValue / 200.0).toFloat().coerceIn(0f, 1f),
                color = palette.aqi
            )
        }

        return listOf(temperature, rainMetric, windMetric, uvMetric, aqiMetric).map {
            if(day != null && it.value=="—") it.copy(detail="当日数据暂缺",advice="以已提供的数据为准") else it
        }
    }

    private fun twoDayTemperatureOutlook(rows: List<ForecastHour>): Pair<String, String> {
        val now = System.currentTimeMillis()
        fun dayOffset(time: Long): Int {
            fun dayStart(value: Long): Long = Calendar.getInstance().run {
                timeInMillis = value
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                timeInMillis
            }
            return ((dayStart(time) - dayStart(now)) / (24L * 60 * 60 * 1000)).toInt()
        }
        val daily = rows.mapNotNull { point ->
            point.temperature?.takeIf(Double::isFinite)?.let { Triple(dayOffset(point.time), point.time, it) }
        }.groupBy { it.first }.toSortedMap()
        val preferred = listOfNotNull(daily[1], daily[2]).ifEmpty { daily.values.take(2) }
        if (preferred.isEmpty()) return "未来两日待补充" to "请下拉刷新后再查看"
        val ranges = preferred.map { values ->
            val offset = values.first().first
            val label = when (offset) {
                0 -> "今天"
                1 -> "明日"
                2 -> "后日"
                else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(values.first().second))
            }
            val low = values.minOf { it.third }
            val high = values.maxOf { it.third }
            Triple(label, low, high)
        }
        val detail = ranges.joinToString(" / ") { (label, low, high) ->
            "$label${WeatherSettings.tempText(this, low)}–${WeatherSettings.tempText(this, high)}"
        }
        val advice = if (ranges.size >= 2) {
            val firstMean = (ranges[0].second + ranges[0].third) / 2.0
            val secondMean = (ranges[1].second + ranges[1].third) / 2.0
            when {
                secondMean - firstMean >= 3.0 -> "后日升温，可适当减衣"
                secondMean - firstMean <= -3.0 -> "后日降温，及时添衣"
                ranges.maxOf { it.third - it.second } >= 8.0 -> "昼夜温差较大，建议分层穿衣"
                else -> "气温变化平稳，保持当前着装"
            }
        } else if (ranges.first().third - ranges.first().second >= 8.0) {
            "昼夜温差较大，建议分层穿衣"
        } else "气温变化平稳，保持当前着装"
        return detail to advice
    }

    private fun compactTime(time: Long): String =
        if(day == null) HourlyForecastDetailPolicy.relativeTimeLabel(time).removeSuffix("左右") else SimpleDateFormat("H点",Locale.CHINA).format(Date(time))

    private fun compactAirTravelAdvice(value: Double): String = when {
        value <= 50 -> "放心出行"
        value <= 100 -> "日常出行正常"
        value <= 150 -> "敏感人群少久留"
        value <= 200 -> "减少户外活动"
        else -> "尽量减少外出"
    }

    private fun airQualityRangeSummary(best: Double, worst: Double, average: Double): String {
        val bestLevel = AirQualityScale.level(best)
        val worstLevel = AirQualityScale.level(worst)
        val overall = when (AirQualityScale.level(average)) {
            "优" -> "整体良好"
            "良" -> "整体尚可"
            "轻度污染" -> "整体轻度污染"
            "中度污染" -> "整体中度污染"
            "重度污染" -> "整体重度污染"
            "严重污染" -> "整体严重污染"
            else -> "整体空气质量平稳"
        }
        return if (bestLevel == worstLevel) overall else "$overall，短时$worstLevel"
    }

    private fun addChartSection(parent: LinearLayout) {
        if (day != null) {
            dailyPanel=DailyForecastMetricPanel(this,day,initialMetric,onMetricChanged,initialScrollX,onScrollPosition)
                .also { parent.addView(it,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=dp(8)}) }
            return
        }
        parent.addView(buildLegend(), LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(8) })
        if(points.isEmpty()) {
            parent.addView(TextView(this).apply {
                tag="forecast_empty_hours";text="当日逐小时数据暂未提供";gravity=Gravity.CENTER
                textSize=14f;setTextColor(palette.secondary)
            },LinearLayout.LayoutParams(-1,0,1f))
            return
        }

        val host = FrameLayout(this)
        hourlyScroll = object : HorizontalScrollView(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if(day != null && ::chart.isInitialized && chart.totalWidth > width)
                    parent.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            tag="forecast_hour_scroll"
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            contentDescription = if(day == null) "48小时全宽五指标图表" else "${day.date}当日逐小时图表"
        }
        chart = HourlyForecastCompositeChartView(this, palette, day != null).apply {
            setPoints(points)
            setSelectedIndex(selectedIndex)
        }
        // 横向滚动仅服务于时间轴；图表高度由一屏内容区的剩余空间分配。
        hourlyScroll.addView(chart, ViewGroup.LayoutParams(chart.totalWidth, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(hourlyScroll, FrameLayout.LayoutParams(-1, -1))
        parent.addView(host, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(3) })

        hourlyScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            onScrollPosition?.invoke(scrollX)
            chart.setViewportWindow(scrollX, hourlyScroll.width)
            pendingScrollX = scrollX
            if (selectionPosted) return@setOnScrollChangeListener
            selectionPosted = true
            hourlyScroll.postOnAnimation {
                selectionPosted = false
                val contentX = chart.selectionContentX(pendingScrollX, hourlyScroll.width)
                chart.setSelectionContentX(contentX)
                val index = chart.indexAtContent(contentX)
                selectedIndex = index
                updateReturnCurrentButton()
            }
        }
        hourlyScroll.post {
            chart.layoutParams=chart.layoutParams.apply {width=maxOf(chart.totalWidth,hourlyScroll.width)}
            val restored=initialScrollX.coerceIn(0,(chart.totalWidth-hourlyScroll.width).coerceAtLeast(0))
            hourlyScroll.scrollTo(restored, 0)
            chart.setViewportWindow(restored, hourlyScroll.width)
            chart.setSelectionContentX(chart.selectionContentX(restored, hourlyScroll.width))
            selectedIndex=chart.indexAtContent(chart.selectionContentX(restored,hourlyScroll.width))
            updateReturnCurrentButton()
        }
    }

    private fun buildLegend(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = if(day == null) "48小时指标图例" else "当日指标图例"
        listOf(
            Triple(if(day==null) "温度" else "温/体感", ForecastChartGradient.temperature, false),
            Triple("降水", ForecastChartGradient.rain, true),
            Triple("风力", ForecastChartGradient.wind, false),
            Triple("紫外线", ForecastChartGradient.uv, false),
            Triple("空气质量", ForecastChartGradient.air, true)
        ).let {if(day == null) it else it.toMutableList().apply {add(4,Triple("湿度",ForecastChartGradient.forTrack("湿度"),false))}}.forEach { (label, colors, isBar) ->
            addView(LinearLayout(this@ForecastWeatherPage).apply {
                gravity = Gravity.CENTER
                addView(View(this@ForecastWeatherPage).apply {
                    tag = "chart_legend_swatch"
                    background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
                        cornerRadius = dp(if (isBar) 3 else 99).toFloat()
                    }
                }, LinearLayout.LayoutParams(dp(10), if (isBar) dp(7) else dp(3)))
                addView(TextView(this@ForecastWeatherPage).apply {
                    text = label
                    textSize = if(day == null) 8.5f else 8f
                    setTextColor(palette.secondary)
                    maxLines = 1
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        returnCurrentButton = FrostedActionButton(this@ForecastWeatherPage).apply {
            tag="forecast_return_current"
            text = "回到当前"
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(palette.primary)
            setOnClickListener { onReturnToCurrent?.invoke() ?: resetToCurrent() }
        }
        addView(returnCurrentButton, LinearLayout.LayoutParams(dp(58), dp(26)).apply { marginStart = dp(4) })
        updateReturnCurrentButton()
    }

    private fun updateReturnCurrentButton() {
        if (!::returnCurrentButton.isInitialized) return
        val atCurrent = (day == null || day.date == currentDay) && selectedIndex == currentIndex
        if (returnCurrentState == atCurrent) return
        returnCurrentState = atCurrent
        DetailReturnCurrentButtonStyle.apply(
            button = returnCurrentButton,
            atCurrent = atCurrent,
            primary = palette.primary,
            secondary = palette.secondary,
            activeFill = ColorUtils.setAlphaComponent(palette.primary, 28),
            density = resources.displayMetrics.density
        )
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette.primary)
    }

    private fun roundedBackground(@ColorInt color: Int, radiusDp: Int = 24): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

}

private data class HourlyForecastDetailPalette(
    @ColorInt val background: Int,
    @ColorInt val card: Int,
    @ColorInt val primary: Int,
    @ColorInt val secondary: Int,
    @ColorInt val faint: Int,
    @ColorInt val grid: Int,
    @ColorInt val temperature: Int,
    @ColorInt val rain: Int,
    @ColorInt val wind: Int,
    @ColorInt val ultraviolet: Int,
    @ColorInt val aqi: Int,
    @ColorInt val barTrack: Int
) {
    companion object {
        fun from(context: Context, dark: Boolean): HourlyForecastDetailPalette {
            val primary = ContextCompat.getColor(context, R.color.text_primary)
            val secondary = ContextCompat.getColor(context, R.color.text_secondary)
            return HourlyForecastDetailPalette(
                background = ContextCompat.getColor(context, R.color.page_bg),
                card = ContextCompat.getColor(context, R.color.metric_detail_panel),
                primary = primary,
                secondary = secondary,
                faint = ContextCompat.getColor(context, R.color.text_tertiary),
                grid = ColorUtils.setAlphaComponent(secondary, if (dark) 66 else 44),
                temperature = if (dark) Color.rgb(244, 247, 251) else Color.rgb(41, 51, 64),
                rain = if (dark) Color.rgb(113, 203, 255) else Color.rgb(28, 129, 211),
                wind = if (dark) Color.rgb(79, 210, 186) else Color.rgb(0, 132, 121),
                ultraviolet = if (dark) Color.rgb(186, 145, 244) else Color.rgb(142, 88, 204),
                aqi = if (dark) Color.rgb(114, 202, 98) else Color.rgb(48, 150, 76),
                barTrack = ColorUtils.setAlphaComponent(secondary, if (dark) 64 else 46)
            )
        }
    }
}

/**
 * 全宽五指标小时图。纵向仅保留网格，指标名称由图表上方的单行图例承担；横向滚动
 * 不重建 Path，中心列索引由 Activity 统一驱动顶部实时数据与全部高亮点。
 */
private class HourlyForecastCompositeChartView(
    context: Context,
    private val palette: HourlyForecastDetailPalette,
    private val dailyMode: Boolean = false
) : View(context) {
    private data class Point(val x: Float, val y: Float)

    private var points: List<ForecastHour> = emptyList()
    private var selectedIndex = 0
    private var viewportWidth = 0
    private var viewportScrollX = 0
    /** 内容坐标而不是整数列号；高亮点随手指连续移动，不等松手后跳格。 */
    private var selectionContentX = 0f
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = palette.grid
    }
    private val verticalGuidePaint = Paint(gridPaint).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(5f)), 0f)
    }
    private val historyDash = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gradientCache = ForecastChartGradient.Cache()
    private val readableColors=android.util.SparseIntArray()
    private val segmentPath=Path()
    private var cachedHeight=-1
    private var tempPositionsCache: List<Point?> = emptyList()
    private var windPositionsCache: List<Point?> = emptyList()
    private var uvPositionsCache: List<Point?> = emptyList()
    private var humidityPositionsCache: List<Point?> = emptyList()
    private var feelsPositionsCache: List<Point?> = emptyList()
    private var temperatureValues: List<Double?> = emptyList()
    private var rainValues: List<Double?> = emptyList()
    private var windValues: List<Double?> = emptyList()
    private var uvValues: List<Double?> = emptyList()
    private var airValues: List<Double?> = emptyList()
    private var feelsValues: List<Double?> = emptyList()
    private var humidityValues: List<Double?> = emptyList()
    private var windLabels: List<String> = emptyList()
    private val windArrow=Path().apply {
        moveTo(0f,-1f);lineTo(.58f,.8f);lineTo(0f,.45f);lineTo(-.58f,.8f);close()
    }
    private var temperatureLabels=emptyList<String>()
    private var temperatureLabelUnit=""
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.secondary
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
    }

    /** 360dp 常见窄屏内，12 个小时列（11 个间隔）与两侧内边距可完整同屏。 */
    val hourStepPx: Float get() = dp(HourlyForecastDetailPolicy.HOUR_STEP_DP)
    private val edgeInsetPx: Float get() = dp(HourlyForecastDetailPolicy.EDGE_INSET_DP)
    private val initialSelectionIndex: Int
        get() = points.indexOfFirst { !it.isHistorical }.takeIf { it >= 0 } ?: 0
    private val initialSelectionX: Float get() = xAt(initialSelectionIndex)
    val totalWidth: Int
        get() = (edgeInsetPx * 2f + hourStepPx * (points.size - 1).coerceAtLeast(0)).roundToInt().coerceAtLeast(1)

    init {
        contentDescription = "温度、降水、风力、紫外线与空气质量逐小时图表"
    }

    fun setPoints(value: List<ForecastHour>) {
        points = value
        cachedHeight=-1
        temperatureValues=points.map{it.temperature};rainValues=points.map{HourlyForecastDetailPolicy.percent(it.precipitationProbability)?.toDouble()}
        windValues=points.map{it.wind};uvValues=points.map{it.uv};airValues=points.map{it.aqi}
        feelsValues=points.map{it.feelsLike};humidityValues=points.map{it.humidity}
        windLabels=points.map {point ->
            if(dailyMode) point.wind?.let {WeatherSettings.windSpeedValue(context,it,null).removeSuffix(WeatherSettings.metricUnit(context,MetricKind.WIND))} ?: "—"
            else point.wind?.roundToInt()?.toString() ?: "—"
        }
        temperatureLabels=emptyList();temperatureLabelUnit=""
        selectedIndex = selectedIndex.coerceIn(0, max(0, points.lastIndex))
        selectionContentX = xAt(selectedIndex)
        requestLayout()
        invalidate()
    }

    fun setSelectedIndex(value: Int) {
        selectedIndex = value.coerceIn(0, max(0, points.lastIndex))
        selectionContentX = xAt(selectedIndex)
        invalidate()
    }

    /**
     * 图表本身比可视窗口宽。保存窗口位置后，时间轴只绘制完整落入窗口的标签，
     * 避免紧凑 12 小时布局在右边缘留下被裁掉的半个数字。
     */
    fun setViewportWindow(scrollX: Int, width: Int) {
        val safeScrollX = scrollX.coerceAtLeast(0)
        val safeWidth = width.coerceAtLeast(0)
        if (viewportScrollX == safeScrollX && viewportWidth == safeWidth) return
        viewportScrollX = safeScrollX
        viewportWidth = safeWidth
        invalidate()
    }

    /** 与多日详情相同：首列从左内边距开始，末列停在右内边距，高亮引导线随滚动向右移动。 */
    fun selectionViewportX(scrollX: Int, viewport: Int): Float {
        val maxScroll = (totalWidth - viewport).coerceAtLeast(0)
        return HourlyForecastDetailPolicy.movingSelectionViewportX(
            scrollX = scrollX,
            maxScroll = maxScroll,
            initialX = initialSelectionX,
            viewportWidth = viewport,
            edgeInset = edgeInsetPx
        )
    }

    fun selectionContentX(scrollX: Int, viewport: Int): Float =
        (scrollX + selectionViewportX(scrollX, viewport)).coerceIn(xAt(0), xAt(points.lastIndex))

    fun indexAtContent(contentX: Float): Int =
        ((contentX - edgeInsetPx) / hourStepPx).roundToInt().coerceIn(points.indices)

    fun setSelectionContentX(contentX: Float) {
        if (points.isEmpty()) return
        val clamped = contentX.coerceIn(xAt(0), xAt(points.lastIndex))
        val next = indexAtContent(clamped)
        if (selectedIndex == next && kotlin.math.abs(selectionContentX - clamped) < .25f) return
        selectedIndex = next
        selectionContentX = clamped
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 父容器以权重分配一屏中剩余高度；未受约束（例如离屏测试）时才回落到正常高度。
        setMeasuredDimension(maxOf(totalWidth,MeasureSpec.getSize(widthMeasureSpec)), resolveSize(dp(330f).roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        // 顶部时刻、五条轨道和图例共用同一横向内容坐标；两端 12dp 正好容纳 24dp
        // 的选中条形，因此首末数据列的外边界与上方摘要卡的两端对齐。
        val headerHeight = minOf(dp(if(dailyMode) 48f else 36f), height * if(dailyMode) .17f else .15f)
        val laneGap = minOf(dp(8f), maxOf(dp(4f), height * .025f))
        val laneCount=if(dailyMode) 6 else 5
        val airLane=laneCount-1
        val laneHeight = ((height - headerHeight - laneGap * (laneCount-1)) / laneCount).coerceAtLeast(dp(if(dailyMode) 24f else 42f))
        val chartBottom = headerHeight + laneHeight * laneCount + laneGap * (laneCount-1)
        val left = 0f
        val right = width.toFloat()
        fun laneTop(index: Int) = headerHeight + index * (laneHeight + laneGap)
        fun laneBottom(index: Int) = laneTop(index) + laneHeight
        fun graphTop(index: Int) = laneTop(index) + minOf(dp(if(dailyMode && index==0) 28f else 23f), laneHeight * if(dailyMode && index==0) .64f else .42f)
        fun graphBottom(index: Int) = laneBottom(index) - minOf(dp(5f), laneHeight * .12f)

        repeat(laneCount) { lane ->
            canvas.drawLine(left, laneBottom(lane), right, laneBottom(lane), gridPaint)
        }
        val guideIndices = (points.indices.filter { it % 6 == 0 } + points.lastIndex).distinct()
        guideIndices.forEach { index ->
            verticalGuidePaint.color = colorForIndex(palette.grid, index)
            canvas.drawLine(xAt(index), headerHeight, xAt(index), chartBottom, verticalGuidePaint)
        }

        val unit=WeatherSettings.tempUnit(context)
        if(temperatureLabelUnit!=unit || temperatureLabels.size!=points.size) {
            temperatureLabelUnit=unit;temperatureLabels=temperatureValues.map{WeatherSettings.tempText(context,it)}
        }
        val temperatures=temperatureValues;val rains=rainValues;val winds=windValues;val ultraviolet=uvValues;val airQuality=airValues
        // 每个指标的纵向范围固定在对应轨道内。此前把逐小时 index 传给 graphTop/Bottom，
        // 造成第二列之后的曲线向下跨越多个轨道，真机横滑时会直接跑出图表。
        if(cachedHeight!=height) {
            cachedHeight=height
            val temperatureScale=if(dailyMode) temperatures+feelsValues else temperatures
            tempPositionsCache=positions(temperatures,lowerFloor=null,graphTop=graphTop(0),graphBottom=graphBottom(0),scaleValues=temperatureScale)
            if(dailyMode) {
                feelsPositionsCache=positions(feelsValues,null,graphTop(0),graphBottom(0),temperatureScale)
                humidityPositionsCache=positions(humidityValues,0.0,graphTop(4),graphBottom(4))
            }
            windPositionsCache=positions(winds,lowerFloor=0.0,graphTop=graphTop(2),graphBottom=graphBottom(2))
            uvPositionsCache=positions(ultraviolet,lowerFloor=0.0,graphTop=graphTop(3),graphBottom=graphBottom(3))
        }
        val tempPositions=tempPositionsCache;val windPositions=windPositionsCache;val uvPositions=uvPositionsCache
        drawSeries(canvas, tempPositions, ForecastChartGradient.temperature, graphTop(0), graphBottom(0), dp(2.5f))
        if(dailyMode) {
            drawSeries(canvas,feelsPositionsCache,ForecastChartGradient.temperature,graphTop(0),graphBottom(0),dp(1.4f),true)
            drawSeries(canvas,humidityPositionsCache,ForecastChartGradient.humidity,graphTop(4),graphBottom(4),dp(2.2f))
        }
        drawBars(
            canvas = canvas,
            values = rains,
            graphTop = graphTop(1),
            graphBottom = graphBottom(1),
            baseColor = palette.rain,
            normalize = { (it / 100.0).toFloat().coerceIn(0f, 1f) }
        )
        drawSeries(canvas, windPositions, ForecastChartGradient.wind, graphTop(2), graphBottom(2), dp(2.3f))
        drawSeries(canvas, uvPositions, ForecastChartGradient.uv, graphTop(3), graphBottom(3), dp(2.2f))
        val aqiCeiling = airQuality.mapNotNull { it?.takeIf(Double::isFinite) }
            .maxOrNull()?.coerceAtLeast(50.0) ?: 50.0
        drawBars(
            canvas = canvas,
            values = airQuality,
            graphTop = graphTop(airLane),
            graphBottom = graphBottom(airLane),
            baseColor = palette.aqi,
            // 空气质量的常规柱与焦点柱始终保持绿色语义；仅通过亮浅绿到深绿的渐变和
            // 平滑加宽表达当前选中态，不能复用降水的蓝色高亮。
            normalize = { (it / aqiCeiling).toFloat().coerceIn(0f, 1f) }
        )

        val selectionX = selectionContentX.coerceIn(xAt(0), xAt(points.lastIndex))
        val historicalSelection = points[indexAtContent(selectionX)].isHistorical
        fun marker(positions: List<Point?>, colors: IntArray, lane: Int) {
            val point = pointAtSelection(positions) ?: return
            drawSelectedPoint(canvas, point, ForecastChartGradient.colorAt(colors, point.y, graphTop(lane), graphBottom(lane)), historicalSelection)
        }
        marker(tempPositions, ForecastChartGradient.temperature, 0)
        marker(windPositions, ForecastChartGradient.wind, 2)
        marker(uvPositions, ForecastChartGradient.uv, 3)
        if(dailyMode) {
            marker(feelsPositionsCache,ForecastChartGradient.temperature,0)
            marker(humidityPositionsCache,ForecastChartGradient.humidity,4)
        }

        // 多日页的同款焦点：数据与时刻由同一个 Canvas 元素连续变色、缩放、加粗，
        // 不叠放“普通态 + 高亮态”，拖动时不会出现双重残影或松手后才跳变。
        // Keep two guard columns for enlarged labels and curve continuity. Off-screen
        // columns need no formatting/contrast work; the visible painting is unchanged.
        val firstVisible=if(viewportWidth>0) (kotlin.math.floor((viewportScrollX-edgeInsetPx)/hourStepPx).toInt()-2).coerceAtLeast(0) else 0
        val lastVisible=if(viewportWidth>0) (kotlin.math.ceil((viewportScrollX+viewportWidth-edgeInsetPx)/hourStepPx).toInt()+2).coerceAtMost(points.lastIndex) else points.lastIndex
        for(index in firstVisible..lastVisible) {
            val point = points[index]
            val emphasis = selectionEmphasisForIndex(index)
            drawTimeLabel(canvas, index, minOf(dp(22f), headerHeight * .7f), emphasis)
            if(dailyMode && headerHeight >= dp(44f)) {
                val size=dp(13f*(1f+emphasis*.22f))
                WeatherIconCache.load(context,point.skycon,size)?.let {bitmap ->
                    canvas.drawBitmap(bitmap,xAt(index)-size/2f,headerHeight-dp(25f),null)
                }
            }
            // 数字基线抬高、曲线起点下移，给放大后的焦点读数留出稳定的上下间距。
            val labelOffset = minOf(dp(12f), laneHeight * .24f)
            val temperatureColor = tempPositions[index]?.let {
                ForecastChartGradient.colorAt(ForecastChartGradient.temperature, it.y, graphTop(0), graphBottom(0))
            } ?: palette.secondary
            drawMetricLabel(canvas, temperatureLabels[index], xAt(index), laneTop(0) + labelOffset,
                temperatureColor, emphasis, index, alwaysAccent = true)
            if(dailyMode) {
                val feelsColor=feelsPositionsCache[index]?.let {ForecastChartGradient.colorAt(ForecastChartGradient.temperature,it.y,graphTop(0),graphBottom(0))} ?: palette.secondary
                drawMetricLabel(canvas,point.feelsLike?.let {WeatherSettings.tempText(context,it)} ?: "—",
                    xAt(index),laneTop(0)+minOf(dp(24f),laneHeight*.57f),feelsColor,emphasis,index)
                drawMetricLabel(canvas,point.humidity?.let {"${it.roundToInt()}%"} ?: "—",xAt(index),laneTop(4)+labelOffset,
                    ForecastChartGradient.humidity.last(),emphasis,index)
            }
            drawMetricLabel(canvas, HourlyForecastDetailPolicy.percent(point.precipitationProbability)?.let { "$it%" } ?: "—",
                xAt(index), laneTop(1) + labelOffset, palette.rain, emphasis, index)
            val windColor=windPositions[index]?.let { ForecastChartGradient.colorAt(ForecastChartGradient.wind, it.y, graphTop(2), graphBottom(2)) } ?: palette.wind
            drawMetricLabel(canvas,windLabels[index],xAt(index),laneTop(2)+labelOffset,windColor,emphasis,index)
            if(dailyMode) ForecastWindDirection.navigationArrowRotation(point.windDirectionDegrees)?.let {rotation ->
                val half=textPaint.measureText(windLabels[index])/2f
                val center=xAt(index).coerceIn(half+dp(2f),(width-half-dp(2f)).coerceAtLeast(half+dp(2f)))
                val arrowX=center+half+dp(4f)
                if(arrowX-dp(3f)>=viewportScrollX && arrowX+dp(3f)<=viewportScrollX+viewportWidth) {
                    val saved=canvas.save()
                    canvas.translate(arrowX,laneTop(2)+labelOffset-dp(3f));canvas.rotate(rotation)
                    val scale=dp(3f+emphasis);canvas.scale(scale,scale)
                    fillPaint.shader=null;fillPaint.color=textPaint.color
                    canvas.drawPath(windArrow,fillPaint);canvas.restoreToCount(saved)
                }
            }
            drawMetricLabel(canvas, point.uv?.roundToInt()?.let { "${it}级" } ?: "—", xAt(index), laneTop(3) + labelOffset,
                uvPositions[index]?.let { ForecastChartGradient.colorAt(ForecastChartGradient.uv, it.y, graphTop(3), graphBottom(3)) }
                    ?: palette.ultraviolet, emphasis, index)
            drawMetricLabel(canvas, point.aqi?.roundToInt()?.toString() ?: "—", xAt(index), laneTop(airLane) + labelOffset,
                AirQualityScale.color(point.aqi), emphasis, index)
        }
    }

    /** 降水与空气质量均使用有底框的柱形：无数据/零值仍保留低对比灰蓝槽位。 */
    private fun drawBars(
        canvas: Canvas,
        values: List<Double?>,
        graphTop: Float,
        graphBottom: Float,
        @ColorInt baseColor: Int,
        normalize: (Double) -> Float
    ) {
        values.forEachIndexed { index, rawValue ->
            val emphasis = selectionEmphasisForIndex(index)
            val barWidth = dp(HourlyForecastDetailPolicy.barWidthDp(emphasis))
            val left = xAt(index) - barWidth / 2f
            val right = xAt(index) + barWidth / 2f
            // 常规窄柱可保持柔和圆端；焦点放大到 24dp 时半径封顶为 5dp，
            // 让它自然过渡成圆角矩形，而不是被拉宽的胶囊形。
            val radius = minOf(barWidth / 2f, dp(5f))
            val visibleLeft = viewportScrollX.toFloat()
            val visibleRight = visibleLeft + viewportWidth
            // 边缘只保留完整数据列，避免 12 小时紧凑视图露出下一列的一半灰色底框。
            if (viewportWidth > 0 && (left < visibleLeft || right > visibleRight)) return@forEachIndexed
            fillPaint.shader = null
            fillPaint.color = colorForIndex(palette.barTrack, index)
            canvas.drawRoundRect(
                left, graphTop, right, graphBottom, radius, radius,
                fillPaint
            )
            val value = rawValue?.takeIf(Double::isFinite) ?: return@forEachIndexed
            val ratio = normalize(value)
            if (ratio <= 0f) return@forEachIndexed
            val filledTop = (graphBottom - (graphBottom - graphTop) * ratio)
                .coerceAtMost(graphBottom - dp(2f))
            val colors = if (baseColor == palette.aqi) ForecastChartGradient.air else ForecastChartGradient.rain
            // Paint 先用于半透明的灰色底轨；Shader 会继承 Paint 颜色的 alpha。
            // 必须在绘制彩色填充前还原不透明白色，否则绿色/蓝色会被底轨透明度稀释。
            fillPaint.color = Color.WHITE
            fillPaint.alpha = if (points[index].isHistorical) 122 else 255
            fillPaint.shader = if (baseColor == palette.aqi) null else gradientCache.shader(colors, graphTop, graphBottom)
            if (baseColor == palette.aqi) {
                fillPaint.color = AirQualityScale.color(value)
                fillPaint.alpha = if (points[index].isHistorical) 122 else 255
            }
            canvas.drawRoundRect(left, filledTop, right, graphBottom, radius, radius, fillPaint)
        }
        fillPaint.shader = null
    }

    /** 历史小时到当前小时的唯一连接段采用虚线，且历史点、柱、时间一起降低透明度。 */
    private fun drawSeries(canvas: Canvas, positions: List<Point?>, colors: IntArray, top: Float, bottom: Float, stroke: Float, dashed: Boolean = false) {
        linePaint.strokeWidth = stroke
        linePaint.shader = gradientCache.shader(colors, top, bottom)
        for (index in 0 until positions.lastIndex) {
            val from = positions[index] ?: continue
            val to = positions[index + 1] ?: continue
            if(viewportWidth>0 && (to.x<viewportScrollX-hourStepPx*2 || from.x>viewportScrollX+viewportWidth+hourStepPx*2)) continue
            linePaint.color = Color.WHITE
            linePaint.alpha = if (points[index].isHistorical) 122 else 255
            linePaint.pathEffect = if (points[index].isHistorical || dashed) historyDash else null
            // 与多日图相同的水平控制柄贝塞尔段；连续滑动的焦点由同一曲线函数取点。
            segmentPath.reset();segmentPath.moveTo(from.x,from.y)
            segmentPath.cubicTo((from.x+to.x)/2f,from.y,(from.x+to.x)/2f,to.y,to.x,to.y)
            canvas.drawPath(segmentPath, linePaint)
        }
        linePaint.pathEffect = null
        linePaint.shader = null
        linePaint.alpha = 255
        // 数据点只在当前高亮位置绘制，非选中小时保留连续折线，避免压缩到 12 列后变成密集锯齿点阵。
    }

    private fun selectionEmphasisForIndex(index: Int): Float =
        HourlyForecastDetailPolicy.selectionEmphasis((xAt(index) - selectionContentX) / hourStepPx)

    /** 与多日页一致：同一文本从普通态连续过渡到轨道色、粗字和放大态。 */
    private fun drawMetricLabel(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        @ColorInt accent: Int,
        emphasis: Float,
        index: Int,
        alwaysAccent: Boolean = false
    ) {
        val focus = emphasis.coerceIn(0f, 1f)
        textPaint.style = Paint.Style.FILL
        textPaint.pathEffect = null
        textPaint.textAlign = Paint.Align.CENTER
        // 焦点列约 14.6sp，普通列仍为 8.5sp；只放大当前连续焦点，不叠放第二份文字，
        // 因此高亮读数更醒目且不会制造横滑残影。
        textPaint.textSize = sp(8.5f * HourlyForecastDetailPolicy.metricLabelScale(focus))
        textPaint.typeface = if (focus >= .48f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val cachedColorIndex=readableColors.indexOfKey(accent)
        val readableAccent=if(cachedColorIndex>=0) readableColors.valueAt(cachedColorIndex) else {
            var candidate=accent
            repeat(8) {if(ColorUtils.calculateContrast(candidate,palette.background)<4.5) candidate=ColorUtils.blendARGB(candidate,palette.primary,.18f)}
            if(readableColors.size()>=4096) readableColors.clear()
            readableColors.put(accent,candidate);candidate
        }
        textPaint.color = ColorUtils.blendARGB(
            colorForIndex(palette.secondary, index),
            colorForIndex(readableAccent, index),
            if (alwaysAccent) 1f else focus
        )
        val halfWidth = textPaint.measureText(value) / 2f
        val minX = halfWidth + dp(2f)
        val maxX = width - halfWidth - dp(2f)
        val safeX = if (minX <= maxX) x.coerceIn(minX, maxX) else width / 2f
        val visibleLeft = viewportScrollX.toFloat()
        val visibleRight = visibleLeft + viewportWidth
        if (viewportWidth > 0 && (safeX - halfWidth < visibleLeft || safeX + halfWidth > visibleRight)) return
        canvas.drawText(value, safeX, baseline, textPaint)
    }

    /**
     * 顶部时间轴以六小时为基础刻度；当前连续高亮列额外显示其具体时刻并随焦点放大。
     * 紧邻的常规刻度暂避让，防止 26dp 列距内两个时间标签彼此挤压。
     */
    private fun drawTimeLabel(canvas: Canvas, index: Int, baseline: Float, emphasis: Float) {
        val regularTick = HourlyForecastDetailPolicy.shouldShowTimeLabel(index)
        val highlightedIndex = indexAtContent(selectionContentX)
        val isHighlighted = index == highlightedIndex
        if (!regularTick && !isHighlighted) return
        if (regularTick && !isHighlighted && kotlin.math.abs(index - highlightedIndex) <= 1) return
        // 连续焦点位于两列之间时只让最近的一列承载时间文字，避免两份“明日 xx时”
        // 同时放大后重叠；跨过中点时两侧字号权重相同，切换不会产生尺寸跳变。
        val focus = if (isHighlighted) emphasis.coerceAtLeast(.5f) else 0f
        val label = if(dailyMode) SimpleDateFormat("HH时",Locale.CHINA).format(Date(points[index].time)) else HourlyForecastDetailPolicy.timeAxisLabel(points[index].time)
        textPaint.style = Paint.Style.FILL
        textPaint.pathEffect = null
        textPaint.textSize = sp(7.5f * (1f + .52f * focus))
        textPaint.typeface = if (focus >= .48f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textPaint.color = ColorUtils.blendARGB(
            colorForIndex(palette.secondary, index),
            colorForIndex(palette.primary, index),
            focus
        )
        val labelWidth = textPaint.measureText(label)
        val isFirst = index == 0
        val isLast = index == points.lastIndex
        val drawX: Float
        val labelLeft: Float
        val labelRight: Float
        when {
            isFirst -> {
                textPaint.textAlign = Paint.Align.LEFT
                drawX = 0f
                labelLeft = 0f
                labelRight = labelWidth
            }
            isLast -> {
                textPaint.textAlign = Paint.Align.RIGHT
                drawX = width.toFloat()
                labelLeft = width - labelWidth
                labelRight = width.toFloat()
            }
            else -> {
                textPaint.textAlign = Paint.Align.CENTER
                drawX = xAt(index)
                labelLeft = drawX - labelWidth / 2f
                labelRight = drawX + labelWidth / 2f
            }
        }
        // HorizontalScrollView 会裁剪内容，但这里先跳过“半个字”的标签，避免滚动边界
        // 留下被截断的时间数字。
        val visibleLeft = viewportScrollX.toFloat()
        val visibleRight = visibleLeft + viewportWidth
        if (viewportWidth > 0 && (labelLeft < visibleLeft || labelRight > visibleRight)) return
        canvas.drawText(label, drawX, baseline, textPaint)
    }

    private fun drawSelectedPoint(
        canvas: Canvas,
        point: Point?,
        @ColorInt color: Int,
        historical: Boolean = false
    ) {
        point ?: return
        val displayColor = if (historical) historicalColor(color) else color
        ChartSelectionMarker.draw(
            canvas, point.x, point.y, displayColor,
            WeatherSettings.isDarkTheme(context), resources.displayMetrics.density
        )
    }

    private fun pointAtSelection(positions: List<Point?>): Point? {
        val raw = ((selectionContentX - edgeInsetPx) / hourStepPx).coerceIn(0f, points.lastIndex.toFloat())
        val left = kotlin.math.floor(raw).toInt()
        val right = (left + 1).coerceAtMost(points.lastIndex)
        val fraction = raw - left
        val from = positions[left] ?: return positions[right]
        val to = positions[right] ?: return from
        val curveFraction = ForecastCurveInterpolator.easingAtContentFraction(fraction)
        return Point(selectionContentX, from.y + (to.y - from.y) * curveFraction)
    }

    private fun positions(
        values: List<Double?>,
        lowerFloor: Double?,
        graphTop: Float,
        graphBottom: Float,
        scaleValues: List<Double?> = values
    ): List<Point?> {
        val finite = scaleValues.mapNotNull { it?.takeIf(Double::isFinite) }
        var low = finite.minOrNull() ?: 0.0
        var high = finite.maxOrNull() ?: 1.0
        if (lowerFloor != null) low = minOf(low, lowerFloor)
        val spread = (high - low).takeIf { it >= 0.8 } ?: 1.0
        low -= spread * .08
        high += spread * .08
        if (lowerFloor != null) low = minOf(low, lowerFloor)
        return values.mapIndexed { index, value ->
            value?.takeIf(Double::isFinite)?.let {
                Point(
                    xAt(index),
                    HourlyForecastDetailPolicy.metricY(it, low, high, graphTop, graphBottom)
                )
            }
        }
    }

    private fun xAt(index: Int): Float = edgeInsetPx + hourStepPx * index.coerceIn(0, max(0, points.lastIndex))
    private fun historicalColor(@ColorInt color: Int): Int =
        ColorUtils.setAlphaComponent(color, (Color.alpha(color) * .48f).roundToInt())
    private fun colorForIndex(@ColorInt color: Int, index: Int): Int =
        if (points.getOrNull(index)?.isHistorical == true) historicalColor(color) else color
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
