package com.example.weatherapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.graphics.drawable.ColorDrawable
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

internal const val DAILY_METRIC_PICKER_TAG = "daily_metric_picker"

internal enum class DailyForecastMetric(
    val title: String,
    val kind: MetricKind,
    val colors: IntArray,
    @androidx.annotation.DrawableRes val icon: Int
) {
    TEMPERATURE("温度/体感", MetricKind.FEELS_LIKE, ForecastChartGradient.temperature, R.drawable.ic_forecast_metric_temperature),
    RAIN("降水", MetricKind.UV, ForecastChartGradient.rain, R.drawable.ic_forecast_metric_rain),
    WIND("风力", MetricKind.WIND, ForecastChartGradient.wind, R.drawable.ic_forecast_metric_wind),
    UV("紫外线", MetricKind.UV, ForecastChartGradient.uv, R.drawable.ic_forecast_metric_uv),
    HUMIDITY("湿度", MetricKind.HUMIDITY, ForecastChartGradient.humidity, R.drawable.ic_forecast_metric_humidity),
    PRESSURE("气压", MetricKind.PRESSURE, MetricKind.PRESSURE.lineColors, R.drawable.ic_forecast_metric_pressure),
    VISIBILITY("能见度", MetricKind.VISIBILITY, MetricKind.VISIBILITY.lineColors, R.drawable.ic_forecast_metric_visibility),
    AQI("空气", MetricKind.UV, ForecastChartGradient.air, R.drawable.ic_forecast_metric_air);

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
    private var metricPopup: PopupWindow? = null
    private val metricPicker = TextView(context)
    private val reading = TextView(context)
    private val meta = TextView(context)
    private val missing = TextView(context)
    val chart = HourlyMetricChartView(context)
    var selectedMetric: DailyForecastMetric = DailyForecastMetric.entries.firstOrNull { it.name == initialMetric } ?: DailyForecastMetric.TEMPERATURE
        private set
    private var selectedTime = slots.getOrNull(initialHourIndex)?.time
        ?: slots.minByOrNull { kotlin.math.abs(it.time-System.currentTimeMillis()/ForecastDetailData.HOUR*ForecastDetailData.HOUR) }?.time ?: 0L

    init {
        orientation = VERTICAL
        tag = "daily_metric_panel"
        val panel = LinearLayout(context).apply {
            orientation=VERTICAL;setPadding(dp(6),dp(14),dp(6),dp(10))
            val radius = dp(24).toFloat()
            if (dark) {
                background = GradientDrawable().apply {
                    setColor(Color.argb(28, 255, 255, 255))
                    cornerRadius = radius
                }
            } else {
                DetailGlassSurface.install(this, radius, sampleBackdrop = false,
                    lightTint = Color.rgb(234, 236, 240) to Color.rgb(225, 228, 233))
                foreground = WeatherFrostedBorderDrawable(resources.displayMetrics.density, 24f, false)
            }
        }
        reading.apply {tag="daily_metric_reading";textSize=22f;typeface=Typeface.DEFAULT_BOLD;setTextColor(primary);includeFontPadding=false;setPadding(dp(8),0,dp(8),0)}
        meta.apply {tag="daily_metric_meta";textSize=12f;setTextColor(secondary);setPadding(dp(8),dp(6),dp(8),dp(6));minLines=2}
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(6), 0)
        }
        header.addView(reading, LayoutParams(0, -2, 1f))
        metricPicker.apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(primary)
            useCompactDetailCapsule()
            setOnClickListener { openMetricPicker() }
            if (dark) {
                SettingsGlassStyle.decorate(this, radiusDp = DETAIL_CAPSULE_RADIUS_DP, action = true)
            } else {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.WHITE, Color.rgb(245, 245, 245))
                ).apply {
                    cornerRadius = dp(DETAIL_CAPSULE_RADIUS_DP.toInt()).toFloat()
                    setStroke(dp(1), Color.WHITE)
                }
                setTextColor(primary)
                elevation = dp(3).toFloat()
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    outlineAmbientShadowColor = Color.argb(90, 48, 48, 48)
                    outlineSpotShadowColor = Color.argb(62, 48, 48, 48)
                }
            }
            tag = DAILY_METRIC_PICKER_TAG
        }
        header.addView(metricPicker, LayoutParams(-2, -2))
        header.keepDetailCapsuleTouchable(metricPicker)
        panel.addView(header, LayoutParams(-1, -2))
        panel.addView(meta, LayoutParams(-1, -2))
        missing.apply {
            tag = "daily_history_status"
            textSize = 11f
            setTextColor(secondary)
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        panel.addView(missing, LayoutParams(-1, -2))
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
                value==null -> "$timeText · 该时段暂无记录"
                selectedMetric==DailyForecastMetric.AQI -> "$timeText · ${AirQualityScale.level(value)}\n${AirQualityScale.advice(value)}"
                selectedMetric==DailyForecastMetric.RAIN -> "$timeText · 降水概率\n小时雨量 ${slots.firstOrNull {it.time==time}?.precipitationAmountMm?.let {String.format(Locale.US,"%.1f毫米",it)} ?: "暂无数据"}"
                else -> "$timeText · ${selectedMetric.kind.level(value,second)}\n${selectedMetric.kind.advice(value,second)}"
            }
        }
        panel.addView(chart, LayoutParams(-1, maxOf(dp(286), dp(247) + resources.weatherSp(39f).roundToInt())))
        addView(panel,LayoutParams(-1,-2))
        select(selectedMetric)
    }

    fun resetToCurrent() { chart.selectTime(System.currentTimeMillis()/ForecastDetailData.HOUR*ForecastDetailData.HOUR) }

    private fun select(metric: DailyForecastMetric) {
        selectedMetric=metric;onMetricChanged?.invoke(metric.name)
        metricPicker.text = metric.title + " ▾"
        metricPicker.contentDescription = "选择图表指标，当前为" + metric.title
        val previousTime=selectedTime
        chart.setForecastSeries(metric,slots.map {it.time}.toLongArray(),slots.map {metric.value(it) ?: Double.NaN}.toDoubleArray(),slots.map {metric.secondary(it) ?: Double.NaN}.toDoubleArray())
        chart.selectTime(previousTime)
        missing.text = "当前天气源未提供当日${metric.title}数据"
        missing.visibility = if (slots.none { metric.value(it) != null || metric.secondary(it) != null }) View.VISIBLE else View.GONE
    }

    private fun openMetricPicker() {
        if (metricPopup?.isShowing == true) return
        val backdrop = rootView
        val options = LinearLayout(context).apply { orientation = VERTICAL }
        val rowHeight = maxOf(dp(44), (resources.weatherSp(15f) * 1.4f).roundToInt() + dp(14))
        val width = (backdrop.width - dp(32)).coerceAtLeast(dp(132)).coerceAtMost(dp(164))
        val height = (rowHeight * DailyForecastMetric.entries.size + dp(12))
            .coerceAtMost((backdrop.height / 2).coerceAtLeast(dp(148)))
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(6), 0, dp(6))
            addView(options, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        val popup = PopupWindow(scroll, width, height, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(10).toFloat()
        }
        DailyForecastMetric.entries.forEach { item ->
            options.addView(TextView(context).apply {
                val selected = item == selectedMetric
                val ink = if (selected) primary else secondary
                text = item.title
                tag = "daily_metric:" + item.name
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(14), 0, dp(14), 0)
                setTextColor(ink)
                compoundDrawablePadding = dp(10)
                val icon = ContextCompat.getDrawable(context, item.icon)?.mutate()?.apply { setTint(ink) }
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon, null)
                if (selected) {
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(primary, if (dark) 25 else 16))
                        cornerRadius = dp(8).toFloat()
                    }
                }
                isSelected = selected
                isFocusable = true
                setOnClickListener {
                    select(item)
                    popup.dismiss()
                }
            }, LayoutParams(-1, rowHeight))
        }
        SettingsGlassStyle.decoratePopup(scroll, backdrop, material = PopupGlassMaterial.CHART_MENU)
        metricPopup = popup
        popup.setOnDismissListener { metricPopup = null }
        popup.showAsDropDown(metricPicker, metricPicker.width - width, dp(4))
    }

    override fun onDetachedFromWindow() {
        metricPopup?.dismiss()
        super.onDetachedFromWindow()
    }
}
