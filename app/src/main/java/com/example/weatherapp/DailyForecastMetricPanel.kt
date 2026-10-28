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
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

internal enum class DailyForecastMetric(val title: String, val kind: MetricKind, val colors: IntArray) {
    TEMPERATURE("温度/体感", MetricKind.FEELS_LIKE, ForecastChartGradient.temperature),
    RAIN("降水", MetricKind.UV, ForecastChartGradient.rain),
    WIND("风力", MetricKind.WIND, ForecastChartGradient.wind),
    UV("紫外线", MetricKind.UV, ForecastChartGradient.uv),
    HUMIDITY("湿度", MetricKind.HUMIDITY, ForecastChartGradient.humidity),
    PRESSURE("气压", MetricKind.PRESSURE, MetricKind.PRESSURE.lineColors),
    VISIBILITY("能见度", MetricKind.VISIBILITY, MetricKind.VISIBILITY.lineColors),
    AQI("空气", MetricKind.UV, ForecastChartGradient.air);

    fun value(point: ForecastHour): Double? = when(this) {
        TEMPERATURE -> point.feelsLike
        RAIN -> HourlyForecastDetailPolicy.percent(point.precipitationProbability)?.toDouble(); WIND -> point.wind; UV -> point.uv
        HUMIDITY -> point.humidity; PRESSURE -> point.pressureHpa; VISIBILITY -> point.visibilityKm; AQI -> point.aqi
    }?.takeIf(Double::isFinite)
    fun secondary(point: ForecastHour): Double? = when(this) {
        TEMPERATURE -> point.temperature; WIND -> point.gustKmh; else -> null
    }?.takeIf(Double::isFinite)
    fun text(context: Context, value: Double?): String = if(value == null || !value.isFinite()) "暂无数据" else when(this) {
        TEMPERATURE -> WeatherSettings.tempText(context, value)
        RAIN -> "${value.roundToInt()}%"
        AQI -> "${value.roundToInt()} AQI"
        else -> WeatherSettings.metricText(context, kind, value)
    }
}

/** Uses the actual six-metric chart renderer, including its fill, grid, gradient and focus marker. */
internal class DailyForecastMetricPanel(
    context: Context,
    private val day: ForecastDayPage,
    initialMetric: String? = null,
    private val onMetricChanged: ((String)->Unit)? = null,
    initialHourIndex: Int = -1,
    private val onHourSelected: ((Int)->Unit)? = null
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
    private var thumbAnimator: android.animation.ValueAnimator? = null
    private val reading = TextView(context)
    private val meta = TextView(context)
    private val status = TextView(context)
    val chart = HourlyMetricChartView(context)
    var selectedMetric: DailyForecastMetric = DailyForecastMetric.entries.firstOrNull { it.name == initialMetric } ?: DailyForecastMetric.TEMPERATURE
        private set
    private var selectedTime = slots.getOrNull(initialHourIndex)?.time
        ?: slots.minByOrNull { kotlin.math.abs(it.time-System.currentTimeMillis()/ForecastDetailData.HOUR*ForecastDetailData.HOUR) }?.time ?: 0L

    init {
        orientation = VERTICAL
        tag = "daily_metric_panel"
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
        val row = LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            // Keep labels above the elevated selection shadow throughout the slide.
            translationZ=dp(3).toFloat()
        }
        val content=android.widget.FrameLayout(context)
        capsuleThumb=View(context).apply {
            tag="weather_segment_thumb"
            background=ContextCompat.getDrawable(context,R.drawable.mode_tab_selected_bg)?.mutate()?.also {
                (it as? GradientDrawable)?.cornerRadius=dp(14).toFloat()
            }
            elevation=dp(2).toFloat()
            outlineProvider=android.view.ViewOutlineProvider.BACKGROUND
            if(android.os.Build.VERSION.SDK_INT>=28) {
                outlineAmbientShadowColor=Color.argb(if(dark) 100 else 72,0,0,0)
                outlineSpotShadowColor=Color.TRANSPARENT
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
            chips.add(chip);row.addView(chip,LayoutParams(dp(if(metric==DailyForecastMetric.TEMPERATURE) 86 else 54),-1))
        }
        capsule.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_-> moveCapsuleThumb(false)}
        capsule.post {moveCapsuleThumb(false)}
        val panel = LinearLayout(context).apply {
            orientation=VERTICAL;setPadding(dp(6),dp(14),dp(6),dp(10))
            background=GradientDrawable().apply {setColor(if(dark) Color.argb(28,255,255,255) else ContextCompat.getColor(context,R.color.light_card_surface));cornerRadius=dp(24).toFloat()}
        }
        reading.apply {tag="daily_metric_reading";textSize=22f;typeface=Typeface.DEFAULT_BOLD;setTextColor(primary);includeFontPadding=false;setPadding(dp(8),0,dp(8),0)}
        meta.apply {tag="daily_metric_meta";textSize=12f;setTextColor(secondary);setPadding(dp(8),dp(6),dp(8),dp(6));minLines=2}
        panel.addView(reading,LayoutParams(-1,-2));panel.addView(meta,LayoutParams(-1,-2))
        chart.tag="daily_metric_chart";chart.setDarkTheme(dark)
        chart.onSelectionChanged={index,time,value,second ->
            selectedTime=time
            onHourSelected?.invoke(index)
            reading.text=if(selectedMetric==DailyForecastMetric.TEMPERATURE)
                "体感 ${selectedMetric.text(context,value)}  气温 ${selectedMetric.text(context,second)}"
            else "${selectedMetric.title} ${selectedMetric.text(context,value)}"
            reading.setTextColor(if(value==null) secondary else textPalette.selectedDataText(chart.forecastValueColor(value)))
            val timeText=SimpleDateFormat("HH:mm",Locale.CHINA).format(time)
            meta.text=when {
                selectedMetric==DailyForecastMetric.TEMPERATURE -> {
                    val comparison=when {
                        value==null -> "体感数据暂无，暂不能计算温差"
                        second==null -> "气温数据暂无，暂不能计算温差"
                        else -> MetricDisplayPolicy.feelsLikeComparison(
                            WeatherSettings.tempDisplay(context,second),WeatherSettings.tempDisplay(context,value),
                            WeatherSettings.metricUnit(context,MetricKind.FEELS_LIKE))
                    }
                    "$timeText · 彩线体感 / 灰线气温\n$comparison · ${MetricKind.FEELS_LIKE.level(value)}"
                }
                value==null -> "$timeText · 该时段暂无记录\n未保存的时段保持空缺，不推算数据"
                selectedMetric==DailyForecastMetric.AQI -> "$timeText · ${AirQualityScale.level(value)}\n${AirQualityScale.advice(value)}"
                selectedMetric==DailyForecastMetric.RAIN -> "$timeText · 降水概率\n小时雨量 ${slots.firstOrNull {it.time==time}?.precipitationAmountMm?.let {String.format(Locale.US,"%.1f毫米",it)} ?: "暂无数据"}"
                else -> "$timeText · ${selectedMetric.kind.level(value,second)}\n${selectedMetric.kind.advice(value,second)}"
            }
        }
        panel.addView(chart,LayoutParams(-1,0,1f))
        status.apply {tag="daily_history_status";textSize=11f;setTextColor(secondary);setPadding(dp(8),dp(6),dp(8),0)}
        panel.addView(status,LayoutParams(-1,-2))
        addView(panel,LayoutParams(-1,0,1f))
        select(selectedMetric)
    }

    fun resetToCurrent() { chart.selectTime(System.currentTimeMillis()/ForecastDetailData.HOUR*ForecastDetailData.HOUR) }

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
        val count=slots.count {metric.value(it)!=null || metric.secondary(it)!=null}
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
        if(!animate && thumbAnimator?.isRunning==true) return
        thumbAnimator?.cancel()
        val startX=capsuleThumb.translationX
        val startWidth=capsuleThumb.layoutParams.width
        val targetWidth=(chip.width-dp(5)).coerceAtLeast(dp(12))
        fun position(fraction: Float) {
            capsuleThumb.translationX=startX+(target-startX)*fraction
            val width=(startWidth+(targetWidth-startWidth)*fraction).roundToInt()
            if(capsuleThumb.layoutParams.width!=width) capsuleThumb.layoutParams=capsuleThumb.layoutParams.apply {this.width=width}
            updateCapsuleTextColors()
        }
        if(animate && capsuleThumb.isLaidOut && (kotlin.math.abs(startX-target)>.5f || startWidth!=targetWidth)) {
            thumbAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
                duration=260
                interpolator=android.view.animation.DecelerateInterpolator(1.5f)
                addUpdateListener {position(it.animatedValue as Float)}
                start()
            }
        } else position(1f)
        val scroll=(chip.left-(capsuleScroll.width-chip.width)/2).coerceAtLeast(0)
        if(animate) capsuleScroll.smoothScrollTo(scroll,0) else capsuleScroll.scrollTo(scroll,0)
    }

    private fun updateCapsuleTextColors() {
        chips.forEach {chip ->
            val emphasis=(1f-kotlin.math.abs(chip.left-capsuleThumb.translationX)/chip.width.coerceAtLeast(1)).coerceIn(0f,1f)
            chip.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(secondary,primary,emphasis))
        }
    }

    override fun onDetachedFromWindow() {
        thumbAnimator?.cancel()
        super.onDetachedFromWindow()
    }

}
