package com.example.weatherapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

internal enum class DailyForecastMetric(val title: String, val kind: MetricKind, val colors: IntArray) {
    TEMPERATURE("温度", MetricKind.FEELS_LIKE, ForecastChartGradient.temperature),
    FEELS_LIKE("体感", MetricKind.FEELS_LIKE, ForecastChartGradient.temperature),
    RAIN("降水", MetricKind.UV, ForecastChartGradient.rain),
    WIND("风力", MetricKind.WIND, ForecastChartGradient.wind),
    UV("紫外线", MetricKind.UV, ForecastChartGradient.uv),
    HUMIDITY("湿度", MetricKind.HUMIDITY, ForecastChartGradient.humidity),
    PRESSURE("气压", MetricKind.PRESSURE, MetricKind.PRESSURE.lineColors),
    VISIBILITY("能见度", MetricKind.VISIBILITY, MetricKind.VISIBILITY.lineColors),
    AQI("空气", MetricKind.UV, ForecastChartGradient.air);

    fun value(point: ForecastHour): Double? = when(this) {
        TEMPERATURE -> point.temperature; FEELS_LIKE -> point.feelsLike
        RAIN -> point.precipitationAmountMm; WIND -> point.wind; UV -> point.uv
        HUMIDITY -> point.humidity; PRESSURE -> point.pressureHpa; VISIBILITY -> point.visibilityKm; AQI -> point.aqi
    }?.takeIf(Double::isFinite)
    fun secondary(point: ForecastHour): Double? = when(this) {
        TEMPERATURE -> point.feelsLike; FEELS_LIKE -> point.temperature; WIND -> point.gustKmh; else -> null
    }?.takeIf(Double::isFinite)
    fun text(context: Context, value: Double?): String = if(value == null || !value.isFinite()) "暂无数据" else when(this) {
        TEMPERATURE, FEELS_LIKE -> WeatherSettings.tempText(context, value)
        RAIN -> "${String.format(Locale.US,"%.1f",value).removeSuffix(".0")}毫米"
        AQI -> "${value.roundToInt()} AQI"
        else -> WeatherSettings.metricText(context, kind, value)
    }
}

/** Uses the actual six-metric chart renderer, including its fill, grid, gradient and focus marker. */
internal class DailyForecastMetricPanel(
    context: Context,
    private val day: ForecastDayPage,
    private val currentDay: String,
    initialMetric: String? = null,
    private val onMetricChanged: ((String)->Unit)? = null,
    initialHourIndex: Int = -1,
    private val onHourSelected: ((Int)->Unit)? = null,
    private val onReturnToCurrent: ()->Unit
) : LinearLayout(context) {
    private fun dp(v: Int) = (v*resources.displayMetrics.density).roundToInt()
    private val dark = WeatherSettings.isDarkTheme(context)
    private val primary = ContextCompat.getColor(context,R.color.text_primary)
    private val secondary = ContextCompat.getColor(context,R.color.text_secondary)
    private val textPalette = ForecastSelectionPalette(primary, primary, dark, ContextCompat.getColor(context,R.color.light_card_surface))
    private val slots = ForecastDayHistoryPolicy.slots(day.date,day.hours)
    private val chips = ArrayList<TextView>()
    private lateinit var capsuleScroll: HorizontalScrollView
    private lateinit var capsuleThumb: View
    private val reading = TextView(context)
    private val meta = TextView(context)
    private val status = TextView(context)
    val chart = HourlyMetricChartView(context)
    val returnButton = FrostedActionButton(context)
    var selectedMetric: DailyForecastMetric = DailyForecastMetric.entries.firstOrNull { it.name == initialMetric } ?: DailyForecastMetric.TEMPERATURE
        private set
    private var selectedTime = slots.getOrNull(initialHourIndex)?.time
        ?: slots.minByOrNull { kotlin.math.abs(it.time-System.currentTimeMillis()) }?.time ?: 0L

    init {
        orientation = VERTICAL
        tag = "daily_metric_panel"
        val bar = LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        bar.addView(TextView(context).apply {text="当日逐小时";textSize=15f;setTextColor(primary);typeface=Typeface.DEFAULT_BOLD},LayoutParams(0,-2,1f))
        returnButton.apply {
            tag="forecast_return_current";text="回到当前";textSize=11f;gravity=Gravity.CENTER
            setOnClickListener {onReturnToCurrent()}
        }
        bar.addView(returnButton,LayoutParams(dp(76),dp(32)))
        addView(bar,LayoutParams(-1,dp(40)))
        // Same single track + moving inner highlight as the homepage line/list switch.
        val capsule = android.widget.FrameLayout(context).apply {
            tag="daily_metric_capsule"
            background=ContextCompat.getDrawable(context,R.drawable.mode_capsule_bg)?.mutate()?.also {
                (it as? GradientDrawable)?.cornerRadius=dp(18).toFloat()
            }
            setPadding(dp(2),dp(2),dp(2),dp(2))
            clipToOutline=true;outlineProvider=android.view.ViewOutlineProvider.BACKGROUND
            foreground=WeatherFrostedBorderDrawable(resources.displayMetrics.density,18f,dark)
        }
        val row = LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        val content=android.widget.FrameLayout(context)
        capsuleThumb=View(context).apply {
            tag="weather_segment_thumb"
            background=ContextCompat.getDrawable(context,R.drawable.mode_tab_selected_bg)?.mutate()?.also {
                (it as? GradientDrawable)?.cornerRadius=dp(14).toFloat()
            }
        }
        content.addView(capsuleThumb,android.widget.FrameLayout.LayoutParams(dp(49),dp(28),Gravity.CENTER_VERTICAL).apply {leftMargin=dp(2)})
        content.addView(row,android.widget.FrameLayout.LayoutParams(-2,-1))
        capsuleScroll = object : HorizontalScrollView(context) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                parent?.requestDisallowInterceptTouchEvent(event.actionMasked != android.view.MotionEvent.ACTION_UP && event.actionMasked != android.view.MotionEvent.ACTION_CANCEL)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            tag="daily_metric_capsule_scroll"
            isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER
            addView(content,android.widget.FrameLayout.LayoutParams(-2,-1))
        }
        capsule.addView(capsuleScroll,android.widget.FrameLayout.LayoutParams(-1,-1))
        addView(capsule,LayoutParams(-1,dp(36)).apply {bottomMargin=dp(10)})
        DailyForecastMetric.entries.forEach {metric ->
            val chip=TextView(context).apply {
                tag="daily_metric:${metric.name}";text=metric.title;textSize=12f;gravity=Gravity.CENTER
                isClickable=true;isFocusable=true
                setOnClickListener {select(metric)}
            }
            chips.add(chip);row.addView(chip,LayoutParams(dp(54),-1))
        }
        capsule.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_-> moveCapsuleThumb(false)}
        capsule.post {moveCapsuleThumb(false)}
        val panel = LinearLayout(context).apply {
            orientation=VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(10))
            background=GradientDrawable().apply {setColor(if(dark) Color.argb(28,255,255,255) else ContextCompat.getColor(context,R.color.light_card_surface));cornerRadius=dp(24).toFloat()}
        }
        reading.apply {tag="daily_metric_reading";textSize=22f;typeface=Typeface.DEFAULT_BOLD;setTextColor(primary);includeFontPadding=false}
        meta.apply {textSize=12f;setTextColor(secondary);setPadding(0,dp(6),0,dp(6));minLines=2}
        panel.addView(reading,LayoutParams(-1,-2));panel.addView(meta,LayoutParams(-1,-2))
        chart.tag="daily_metric_chart";chart.setDarkTheme(dark)
        chart.onSelectionChanged={index,time,value,second ->
            selectedTime=time
            onHourSelected?.invoke(index)
            reading.text="${selectedMetric.title} ${selectedMetric.text(context,value)}"
            reading.setTextColor(if(value==null) secondary else textPalette.selectedDataText(chart.forecastValueColor(value)))
            val timeText=SimpleDateFormat("HH:mm",Locale.CHINA).format(time)
            meta.text=when {
                value==null -> "$timeText · 该时段暂无记录\n未保存的时段保持空缺，不推算数据"
                selectedMetric==DailyForecastMetric.TEMPERATURE -> "$timeText · 体感 ${DailyForecastMetric.FEELS_LIKE.text(context,second)}\n${DailyForecastDetailPolicy.temperatureAdvice(value,value)}"
                selectedMetric==DailyForecastMetric.AQI -> "$timeText · ${AirQualityScale.level(value)}\n${AirQualityScale.advice(value)}"
                selectedMetric==DailyForecastMetric.RAIN -> "$timeText · 小时降水量\n降水概率 ${slots.firstOrNull {it.time==time}?.precipitationProbability?.let(HourlyForecastDetailPolicy::percent)?.let {"$it%"} ?: "暂无数据"}"
                else -> "$timeText · ${selectedMetric.kind.level(value,second)}\n${selectedMetric.kind.advice(value,second)}"
            }
            updateReturnButton()
        }
        panel.addView(chart,LayoutParams(-1,0,1f))
        status.apply {tag="daily_history_status";textSize=11f;setTextColor(secondary);setPadding(0,dp(6),0,0)}
        panel.addView(status,LayoutParams(-1,-2))
        addView(panel,LayoutParams(-1,0,1f))
        select(selectedMetric)
    }

    fun resetToCurrent() { chart.selectTime(System.currentTimeMillis()) }

    private fun select(metric: DailyForecastMetric) {
        selectedMetric=metric;onMetricChanged?.invoke(metric.name)
        DailyForecastMetric.entries.forEachIndexed {i,item ->
            val active=item==metric
            chips[i].apply {
                isSelected=active;setTextColor(if(active) primary else secondary)
                typeface=if(active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            }
        }
        moveCapsuleThumb(true)
        val previousTime=selectedTime
        chart.setForecastSeries(metric,slots.map {it.time}.toLongArray(),slots.map {metric.value(it) ?: Double.NaN}.toDoubleArray(),slots.map {metric.secondary(it) ?: Double.NaN}.toDoubleArray())
        chart.selectTime(previousTime)
        val count=slots.count {metric.value(it)!=null}
        status.text=when {
            count==0 -> "当前天气源未提供当日${metric.title}数据"
            day.date<=DailyForecastDetailPolicy.dateKey(System.currentTimeMillis()) -> "历史时段为本机保存的同源预报 · 已有$count/${slots.size}小时"
            else -> "当日已有$count/${slots.size}小时 · 空缺时段不作推算"
        }
    }

    private fun moveCapsuleThumb(animate: Boolean) {
        if(!::capsuleThumb.isInitialized || !::capsuleScroll.isInitialized) return
        val chip=chips.getOrNull(selectedMetric.ordinal) ?: return
        if(chip.width<=0) return
        val target=chip.left.toFloat()
        capsuleThumb.animate().cancel()
        if(animate && capsuleThumb.isLaidOut) capsuleThumb.animate().translationX(target)
            .setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        else capsuleThumb.translationX=target
        val scroll=(chip.left-(capsuleScroll.width-chip.width)/2).coerceAtLeast(0)
        if(animate) capsuleScroll.smoothScrollTo(scroll,0) else capsuleScroll.scrollTo(scroll,0)
    }

    private fun updateReturnButton() {
        val now=System.currentTimeMillis()/ForecastDetailData.HOUR
        DetailReturnCurrentButtonStyle.apply(returnButton,day.date==currentDay && selectedTime/ForecastDetailData.HOUR==now,
            primary,secondary,ColorUtils.setAlphaComponent(primary,28),resources.displayMetrics.density)
    }
}
