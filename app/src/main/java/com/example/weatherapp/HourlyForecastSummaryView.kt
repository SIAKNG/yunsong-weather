package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 五项摘要中的图形语义；图示只辅助扫读，所有结论仍以 [HourlyForecastSummaryMetric] 的真实数值为准。 */
internal enum class HourlyForecastSummaryKind { TEMPERATURE, RAIN, WIND, UV, AQI }

internal data class HourlyForecastSummaryMetric(
    val kind: HourlyForecastSummaryKind,
    val label: String,
    val value: String,
    val detail: String,
    val advice: String,
    val progress: Float,
    @ColorInt val color: Int,
    val currentValue: String? = null,
    val currentProgress: Float? = null,
    val minimumTemperatureCelsius: Double? = null,
    val maximumTemperatureCelsius: Double? = null
)

/**
 * 48 小时预报的轻量信息图摘要。
 *
 * 顶行用冷热渐变轨道概括 48 小时气温；其余四项用环状进度与语义图标收纳峰值、
 * 时段和出行建议。这样保留原摘要的行动建议，但不再让用户阅读五段大段文字。
 */
internal class HourlyForecastSummaryView(
    context: Context,
    @ColorInt private val panelColor: Int,
    @ColorInt pageBackgroundColor: Int,
    @ColorInt private val primary: Int,
    @ColorInt private val secondary: Int,
    @ColorInt private val divider: Int,
    private val metrics: List<HourlyForecastSummaryMetric>,
    private val compactTemperature: Boolean
) : View(context) {
    internal val metricCount: Int get() = metrics.size

    // The card itself is translucent in dark mode. Contrast calculations require an
    // opaque surface, so compare against the card composited over the page background.
    @ColorInt private val contrastSurfaceColor = ColorUtils.compositeColors(panelColor, pageBackgroundColor)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private data class Paragraph(val layout: StaticLayout, val x: Float, val y: Float)
    private data class Symbol(val metric: HourlyForecastSummaryMetric, val x: Float, val y: Float)
    private val paragraphs = mutableListOf<Paragraph>()
    private val symbols = mutableListOf<Symbol>()
    private val dividers = mutableListOf<Float>()
    private val track = RectF()
    private var metricGridDivider: Float? = null

    init {
        background = GradientDrawable().apply {
            setColor(panelColor)
            cornerRadius = dp(24f)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = metrics.joinToString("。") { metric ->
            buildString {
                append("${metric.label} ${metric.value}，${metric.detail}，${metric.advice}")
                metric.currentValue?.let { append("，当前温度 $it") }
            }
        }
    }

    private fun paragraph(text: String, x: Float, y: Float, width: Float, size: Float, color: Int,
                          bold: Boolean = false, alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
                          fitValue: Boolean = false): Float {
        if (text.isBlank()) return y
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = resources.weatherSp(size)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val availableWidth = width.toInt().coerceAtLeast(1)
        if (fitValue) {
            val measured = paint.measureText(text)
            if (measured > availableWidth) {
                paint.textSize *= (availableWidth / measured).coerceAtLeast(.86f)
            }
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(alignment).setLineSpacing(dp(1.5f), 1f).build()
        paragraphs += Paragraph(layout, x, y)
        return y + layout.height
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val panelWidth = MeasureSpec.getSize(widthMeasureSpec)
        val pad = dp(14f)
        val contentWidth = (panelWidth - pad * 2).coerceAtLeast(1f)
        paragraphs.clear()
        symbols.clear()
        dividers.clear()
        track.setEmpty()
        metricGridDivider = null
        var y = pad
        val temperature = metrics.firstOrNull()
        if (temperature != null && compactTemperature) {
            y = layoutCompactTemperature(temperature, pad, contentWidth)
        } else if (temperature != null) {
            y = paragraph(temperature.label, pad, y, contentWidth, 10f, secondary)
            y = paragraph(temperature.value, pad, y + dp(2f), contentWidth, 19.5f,
                selectedDataColor(temperature.color), true)
            val trackY = y + dp(12f)
            track.set(pad + dp(5f), trackY, panelWidth - pad - dp(5f), trackY)
            val current = temperature.currentValue?.let { "当前 $it" }.orEmpty()
            y = paragraph(current, pad, y + dp(22f), contentWidth, 9.3f, primary, true)
            y = paragraph(temperature.detail, pad, y + dp(2f), contentWidth, 9.6f, secondary)
            y = paragraph(temperature.advice, pad, y + dp(2f), contentWidth, 9.6f, secondary)
        }

        val columns = if (contentWidth < dp(254f) || resources.configuration.fontScale > 1.3f) 1 else 2
        if (compactTemperature && columns == 2 && metrics.size > 1) metricGridDivider = y + dp(14f)
        val gap = dp(14f)
        val cellWidth = (contentWidth - gap * (columns - 1)) / columns
        for (row in metrics.drop(1).chunked(columns)) {
            dividers += y + dp(7f)
            val top = y + dp(14f)
            var rowBottom = top
            row.forEachIndexed { index, metric ->
                val left = pad + index * (cellWidth + gap)
                val textLeft = left + dp(38f)
                val titleWidth = cellWidth - (textLeft - left)
                symbols += Symbol(metric, left + dp(15f), top + dp(16f))
                val titleEnd = paragraph(metric.label, textLeft, top, titleWidth, 10f, secondary)
                val value = if (compactTemperature && metric.kind == HourlyForecastSummaryKind.WIND) {
                    // Keep km/h and m/s together when a narrow viewport needs the unit to wrap.
                    metric.value.replace("/", "\u2060/\u2060")
                } else metric.value
                val valueEnd = paragraph(value, textLeft, titleEnd, titleWidth, 15.5f,
                    selectedDataColor(metric.color), true, fitValue = compactTemperature)
                rowBottom = maxOf(rowBottom, paragraph("${metric.detail} · ${metric.advice}", left,
                    maxOf(valueEnd, top + dp(32f)) + dp(4f), cellWidth, 9.6f, secondary))
            }
            y = rowBottom
        }
        setMeasuredDimension(panelWidth, resolveSize((y + pad).roundToInt(), heightMeasureSpec))
    }

    private fun layoutCompactTemperature(metric: HourlyForecastSummaryMetric, pad: Float, contentWidth: Float): Float {
        // Match the earlier 48-hour card: roughly 56% for the text and 44% for the temperature scale.
        val split = contentWidth * .56f
        val textWidth = split - dp(6f)
        val labelBottom = paragraph(metric.label, pad, pad, textWidth, 10f, secondary)
        val valueTop = labelBottom + dp(2f)
        val valueBottom = paragraph(metric.value, pad, valueTop, textWidth, 19.5f,
            selectedDataColor(metric.color), true)
        val detailBottom = paragraph(metric.detail, pad, valueBottom + dp(2f), textWidth, 9.6f, secondary)
        val textBottom = paragraph(metric.advice, pad, detailBottom + dp(2f), textWidth, 9.6f, secondary)

        val rightStart = pad + split
        val rightWidth = contentWidth - split
        val current = metric.currentValue?.let { "当前 $it" }.orEmpty()
        val currentBottom = paragraph(current, rightStart, pad, rightWidth, 9.3f, primary, true,
            Layout.Alignment.ALIGN_CENTER)
        val trackY = maxOf(currentBottom + dp(10f), (valueTop + valueBottom) / 2f)
        val markerInset = dp(8f)
        track.set(rightStart + markerInset, trackY, pad + contentWidth - markerInset, trackY)
        val range = metric.value.split('–', limit = 2)
        if (range.size != 2) return maxOf(textBottom, trackY + dp(10f))

        val endpointsTop = trackY + dp(8f)
        val endpointWidth = rightWidth / 2f - dp(2f)
        val lowBottom = paragraph("低 ${range.first()}", rightStart, endpointsTop, endpointWidth, 8.6f, secondary)
        val highBottom = paragraph("高 ${range.last()}", rightStart + rightWidth - endpointWidth, endpointsTop,
            endpointWidth, 8.6f, secondary, alignment = Layout.Alignment.ALIGN_OPPOSITE)
        return maxOf(textBottom, lowBottom, highBottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        metrics.firstOrNull()?.let { drawTemperatureTrack(canvas, it) }
        strokePaint.strokeWidth = dp(.7f)
        strokePaint.color = divider
        dividers.forEach { y -> canvas.drawLine(dp(14f), y, width - dp(14f), y, strokePaint) }
        metricGridDivider?.let { top ->
            canvas.drawLine(width / 2f, top, width / 2f, height - dp(14f), strokePaint)
        }
        symbols.forEach { drawIcon(canvas, it.metric, it.x, it.y, dp(13f)) }
        paragraphs.forEach { paragraph ->
            val save = canvas.save()
            canvas.translate(paragraph.x, paragraph.y)
            paragraph.layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun drawTemperatureTrack(canvas: Canvas, metric: HourlyForecastSummaryMetric) {
        if (track.right <= track.left) return
        val hasRange = metric.minimumTemperatureCelsius != null && metric.maximumTemperatureCelsius != null
        strokePaint.strokeWidth = dp(4f)
        strokePaint.color = ColorUtils.setAlphaComponent(metric.color, 100)
        if (hasRange) {
            strokePaint.color = Color.WHITE
            strokePaint.shader = LinearGradient(track.left, track.top, track.right, track.top,
                TEMPERATURE_RANGE_COLORS, null, Shader.TileMode.CLAMP)
        }
        canvas.drawLine(track.left, track.top, track.right, track.top, strokePaint)
        strokePaint.shader = null
        metric.currentProgress?.let { progress ->
            ChartSelectionMarker.draw(canvas, track.left + track.width() * progress.coerceIn(0f, 1f), track.top,
                selectedDataColor(if (hasRange) temperatureRangeColor(progress) else metric.color),
                WeatherSettings.isDarkTheme(context), resources.displayMetrics.density)
        }
    }

    private fun temperatureRangeColor(progress: Float): Int {
        val position = progress.coerceIn(0f, 1f) * (TEMPERATURE_RANGE_COLORS.size - 1)
        val lower = position.toInt().coerceIn(0, TEMPERATURE_RANGE_COLORS.lastIndex)
        val upper = (lower + 1).coerceAtMost(TEMPERATURE_RANGE_COLORS.lastIndex)
        return ColorUtils.blendARGB(TEMPERATURE_RANGE_COLORS[lower], TEMPERATURE_RANGE_COLORS[upper], position - lower)
    }

    private fun selectedDataColor(@ColorInt color: Int): Int {
        var candidate = color
        repeat(8) {
            if (ColorUtils.calculateContrast(candidate, contrastSurfaceColor) >= 4.5) return candidate
            candidate = ColorUtils.blendARGB(candidate, primary, .18f)
        }
        return primary
    }

    private fun drawIcon(canvas: Canvas, metric: HourlyForecastSummaryMetric, cx: Float, cy: Float, radius: Float) {
        val progress = metric.progress.coerceIn(0f, 1f)
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = ColorUtils.setAlphaComponent(metric.color, 48)
        canvas.drawCircle(cx, cy, radius, strokePaint)
        strokePaint.color = metric.color
        // Zero rain probability must not masquerade as a non-zero forecast.
        if (progress > 0f) canvas.drawArc(
            cx - radius, cy - radius, cx + radius, cy + radius,
            -90f, 360f * progress, false, strokePaint
        )

        when (metric.kind) {
            HourlyForecastSummaryKind.RAIN -> drawDrop(canvas, cx, cy, radius * .36f, metric.color)
            HourlyForecastSummaryKind.WIND -> drawWind(canvas, cx, cy, radius * .52f, metric.color)
            HourlyForecastSummaryKind.UV -> drawSun(canvas, cx, cy, radius * .34f, metric.color)
            HourlyForecastSummaryKind.AQI -> drawAqi(canvas, cx, cy, radius * .44f, metric.color)
            HourlyForecastSummaryKind.TEMPERATURE -> Unit
        }
    }

    private fun drawDrop(canvas: Canvas, cx: Float, cy: Float, size: Float, @ColorInt color: Int) {
        fillPaint.color = color
        val path = Path().apply {
            moveTo(cx, cy - size)
            cubicTo(cx + size, cy - size * .1f, cx + size * .75f, cy + size, cx, cy + size)
            cubicTo(cx - size * .75f, cy + size, cx - size, cy - size * .1f, cx, cy - size)
            close()
        }
        canvas.drawPath(path, fillPaint)
    }

    private fun drawWind(canvas: Canvas, cx: Float, cy: Float, size: Float, @ColorInt color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = dp(1.7f)
        listOf(-size * .42f, 0f, size * .42f).forEachIndexed { index, offset ->
            val start = cx - size
            val end = cx + size * (if (index == 1) .86f else .62f)
            val path = Path().apply {
                moveTo(start, cy + offset)
                cubicTo(cx - size * .25f, cy + offset - size * .23f, cx + size * .25f, cy + offset + size * .23f, end, cy + offset)
            }
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, radius: Float, @ColorInt color: Int) {
        fillPaint.color = color
        canvas.drawCircle(cx, cy, radius, fillPaint)
        strokePaint.color = color
        strokePaint.strokeWidth = dp(1.4f)
        repeat(8) { index ->
            val angle = index * PI / 4.0
            val start = radius * 1.55f
            val end = radius * 2.05f
            canvas.drawLine(
                cx + cos(angle).toFloat() * start, cy + sin(angle).toFloat() * start,
                cx + cos(angle).toFloat() * end, cy + sin(angle).toFloat() * end,
                strokePaint
            )
        }
    }

    private fun drawAqi(canvas: Canvas, cx: Float, cy: Float, radius: Float, @ColorInt color: Int) {
        fillPaint.color = color
        listOf(-radius, 0f, radius).forEach { offset ->
            canvas.drawCircle(cx + offset, cy, radius * .34f, fillPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        val TEMPERATURE_RANGE_COLORS = intArrayOf(
            Color.rgb(133, 207, 239), // 浅蓝
            Color.rgb(102, 207, 177), // 蓝绿
            Color.rgb(225, 211, 91),  // 黄
            Color.rgb(242, 157, 70),  // 橙
            Color.rgb(226, 79, 76)    // 红
        )
    }
}
