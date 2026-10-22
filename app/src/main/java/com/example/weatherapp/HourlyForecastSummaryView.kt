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
    private val metrics: List<HourlyForecastSummaryMetric>
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT
    }
    private var temperatureHeight = 0f
    private var rowHeights = floatArrayOf(0f, 0f)
    private var detailLayout: StaticLayout? = null
    private var temperatureAdviceLayout: StaticLayout? = null
    private var adviceLayouts: List<StaticLayout> = emptyList()

    private fun paragraph(text: String, width: Float): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondary
            textSize = sp(9.2f)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.roundToInt().coerceAtLeast(1))
            .setIncludePad(false).setLineSpacing(dp(1.5f), 1f).build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val pad = dp(14f)
        if (metrics.size >= 5) {
            val textWidth = (measuredWidth - pad * 2f) * .56f - dp(12f)
            detailLayout = paragraph(metrics[0].detail, textWidth)
            temperatureAdviceLayout = paragraph(metrics[0].advice, textWidth)
            temperatureHeight = maxOf(dp(80f), dp(49f) + detailLayout!!.height + dp(3f) + temperatureAdviceLayout!!.height)
            val cellWidth = measuredWidth / 2f - pad - dp(19f)
            adviceLayouts = metrics.drop(1).map { paragraph("${it.detail} · ${it.advice}", cellWidth - dp(4f)) }
            rowHeights = floatArrayOf(
                dp(48f) + maxOf(adviceLayouts[0].height, adviceLayouts[1].height),
                dp(48f) + maxOf(adviceLayouts[2].height, adviceLayouts[3].height)
            )
        }
        val desired = (pad * 2f + temperatureHeight + dp(2f) + rowHeights.sum()).roundToInt()
        setMeasuredDimension(measuredWidth, resolveSize(desired, heightMeasureSpec))
    }

    private fun drawParagraph(canvas: Canvas, layout: StaticLayout?, x: Float, y: Float) {
        if (layout == null) return
        val save = canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    init {
        background = GradientDrawable().apply {
            setColor(panelColor)
            cornerRadius = dp(24f)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = metrics.joinToString(separator = "。") { metric ->
            buildString {
                append("${metric.label} ${metric.value}，${metric.detail}，${metric.advice}")
                metric.currentValue?.let { append("，当前温度 $it") }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (metrics.size < 5 || width <= 0 || height <= 0) return
        val pad = dp(14f)
        val dividerY = pad + temperatureHeight + dp(2f)
        val halfWidth = width / 2f
        val contentBottom = height - pad
        val bottomTop = dividerY
        val cellInset = dp(7f)
        val columnGap = dp(12f)
        val rowDividerY = bottomTop + (contentBottom - bottomTop) * rowHeights[0] / rowHeights.sum().coerceAtLeast(1f)

        drawTemperature(canvas, metrics[0], RectF(pad, pad, width - pad, pad + temperatureHeight))

        strokePaint.strokeWidth = dp(.7f)
        strokePaint.color = divider
        canvas.drawLine(pad, dividerY, width - pad, dividerY, strokePaint)
        canvas.drawLine(halfWidth, bottomTop + dp(6f), halfWidth, contentBottom - dp(6f), strokePaint)
        canvas.drawLine(pad + cellInset, rowDividerY, halfWidth - dp(8f), rowDividerY, strokePaint)
        canvas.drawLine(halfWidth + dp(8f), rowDividerY, width - pad - cellInset, rowDividerY, strokePaint)

        drawMetric(canvas, metrics[1], RectF(pad + cellInset, bottomTop + dp(4f), halfWidth - columnGap, rowDividerY - dp(4f)), adviceLayouts[0])
        drawMetric(canvas, metrics[2], RectF(halfWidth + columnGap, bottomTop + dp(4f), width - pad - cellInset, rowDividerY - dp(4f)), adviceLayouts[1])
        drawMetric(canvas, metrics[3], RectF(pad + cellInset, rowDividerY + dp(4f), halfWidth - columnGap, contentBottom - dp(4f)), adviceLayouts[2])
        drawMetric(canvas, metrics[4], RectF(halfWidth + columnGap, rowDividerY + dp(4f), width - pad - cellInset, contentBottom - dp(4f)), adviceLayouts[3])
    }

    private fun drawTemperature(canvas: Canvas, metric: HourlyForecastSummaryMetric, rect: RectF) {
        textPaint.color = secondary
        textPaint.textSize = sp(10f)
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText(metric.label, rect.left, rect.top + dp(14f), textPaint)

        textPaint.color = metric.color
        textPaint.textSize = sp(19.5f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(metric.value, rect.left, rect.top + dp(40f), textPaint)

        // 温度轨道按本次最低至最高温度均匀映射浅蓝绿—黄—橙—红，不按人体舒适区着色。
        val values = metric.value.split("–", limit = 2)
        val low = values.getOrNull(0) ?: "—"
        val high = values.getOrNull(1) ?: low
        val trackLeft = rect.left + rect.width() * .56f
        val trackRight = rect.right
        val trackY = rect.top + dp(39f)
        strokePaint.strokeWidth = dp(4f)
        strokePaint.color = ColorUtils.setAlphaComponent(metric.color, 72)
        canvas.drawLine(trackLeft, trackY, trackRight, trackY, strokePaint)
        val minC = metric.minimumTemperatureCelsius
        val maxC = metric.maximumTemperatureCelsius
        strokePaint.shader = if (minC != null && maxC != null) {
            LinearGradient(trackLeft, trackY, trackRight, trackY, TEMPERATURE_RANGE_COLORS, null, Shader.TileMode.CLAMP)
        } else null
        strokePaint.color = Color.WHITE
        canvas.drawLine(trackLeft, trackY, trackRight, trackY, strokePaint)
        strokePaint.shader = null
        fillPaint.color = ColorUtils.setAlphaComponent(if (minC != null && maxC != null) TEMPERATURE_RANGE_COLORS.first() else metric.color, 220)
        canvas.drawCircle(trackLeft, trackY, dp(4f), fillPaint)
        fillPaint.color = if (minC != null && maxC != null) TEMPERATURE_RANGE_COLORS.last() else metric.color
        canvas.drawCircle(trackRight, trackY, dp(4f), fillPaint)

        metric.currentProgress?.let { progress ->
            val markerX = trackLeft + (trackRight - trackLeft) * progress.coerceIn(0f, 1f)
            val currentColor = if (minC != null && maxC != null) {
                temperatureRangeColor(progress)
            } else metric.color
            val selectedColor = selectedDataColor(currentColor)
            ChartSelectionMarker.draw(
                canvas = canvas,
                x = markerX,
                y = trackY,
                color = selectedColor,
                darkTheme = WeatherSettings.isDarkTheme(context),
                density = resources.displayMetrics.density
            )

            textPaint.color = selectedColor
            textPaint.textSize = sp(9.3f)
            textPaint.typeface = Typeface.DEFAULT_BOLD
            val currentLabel = "当前 ${metric.currentValue.orEmpty()}"
            val labelWidth = textPaint.measureText(currentLabel)
            val safeX = markerX.coerceIn(trackLeft + labelWidth / 2f, trackRight - labelWidth / 2f)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(currentLabel, safeX, rect.top + dp(30f), textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        textPaint.color = secondary
        textPaint.textSize = sp(8.6f)
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("低 $low", trackLeft, rect.top + dp(62f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("高 $high", trackRight, rect.top + dp(62f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        // 左半区分两行呈现未来两日范围与穿衣建议，避免长句被压成难读的一行。
        textPaint.color = secondary
        textPaint.textSize = sp(8.5f)
        textPaint.typeface = Typeface.DEFAULT
        drawParagraph(canvas, detailLayout, rect.left, rect.top + dp(49f))
        drawParagraph(canvas, temperatureAdviceLayout, rect.left,
            rect.top + dp(52f) + (detailLayout?.height ?: 0))
    }

    private fun drawMetric(canvas: Canvas, metric: HourlyForecastSummaryMetric, rect: RectF, advice: StaticLayout) {
        // 四格采用等高行、等距分隔线。放大图形后，圆环高度与标题/数值两行高度相当，
        // 建议单独落在下方，避免上排图标看起来比下排更靠近分隔线。
        val iconCenterX = rect.left + dp(12f)
        val iconCenterY = rect.top + dp(18f)
        drawIcon(canvas, metric, iconCenterX, iconCenterY, dp(14f))

        val textLeft = rect.left + dp(38f)
        textPaint.color = secondary
        textPaint.textSize = sp(9.4f)
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText(ellipsize(metric.label, textPaint, rect.right - textLeft), textLeft, rect.top + dp(13f), textPaint)

        textPaint.color = metric.color
        textPaint.textSize = sp(15.5f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(ellipsize(metric.value, textPaint, rect.right - textLeft), textLeft, rect.top + dp(33f), textPaint)

        textPaint.color = secondary
        textPaint.textSize = sp(9.2f)
        textPaint.typeface = Typeface.DEFAULT
        drawParagraph(canvas, advice, rect.left + dp(2f), rect.top + dp(39f))
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

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (width <= 0f || paint.measureText(text) <= width) return text
        val ellipsis = "…"
        val count = paint.breakText(text, true, (width - paint.measureText(ellipsis)).coerceAtLeast(0f), null)
        return text.take(count) + ellipsis
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

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
