package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 可拖动的 24 小时曲线图。主线均使用平滑二次贝塞尔曲线；风力页的 secondary
 * 为阵风，主风力曲线才绘制向下渐隐填充，体感页的 secondary 则是灰色实时温度参考线。
 */
class HourlyMetricChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onSelectionChanged: ((Int, Long, Double?, Double?) -> Unit)? = null

    private var kind = MetricKind.UV
    private var times = LongArray(0)
    private var values = DoubleArray(0)
    private var secondary = DoubleArray(0)
    private var selectedIndex = 0
    private var darkTheme = true

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val selectionGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(145, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(4f)
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 214, 235, 246)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.5f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(174, 255, 255, 255)
        textAlign = Paint.Align.RIGHT
        textSize = sp(10f)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(178, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    fun setDarkTheme(isDark: Boolean) {
        darkTheme = isDark
        val text = if (isDark) Color.WHITE else Color.rgb(26, 36, 51)
        val subdued = if (isDark) Color.WHITE else Color.rgb(42, 57, 74)
        gridPaint.color = if (isDark) Color.argb(42, 255, 255, 255) else Color.argb(34, 26, 36, 51)
        guidePaint.color = if (isDark) Color.argb(70, 255, 255, 255) else Color.argb(76, 26, 36, 51)
        selectionGuidePaint.color = if (isDark) Color.argb(145, 255, 255, 255) else Color.argb(132, 26, 36, 51)
        axisPaint.color = Color.argb(178, Color.red(subdued), Color.green(subdued), Color.blue(subdued))
        captionPaint.color = Color.argb(185, Color.red(subdued), Color.green(subdued), Color.blue(subdued))
        secondaryPaint.color = if (isDark) Color.argb(205, 214, 235, 246) else Color.argb(205, 80, 96, 110)
        invalidate()
    }

    fun setSeries(
        kind: MetricKind,
        times: LongArray,
        values: DoubleArray,
        secondary: DoubleArray = DoubleArray(values.size) { Double.NaN }
    ) {
        this.kind = kind
        this.times = times
        this.values = values
        this.secondary = secondary
        selectedIndex = times.indices.minByOrNull { abs(times[it] - System.currentTimeMillis()) } ?: 0
        dispatchSelection()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty() || width <= 0 || height <= 0) return
        // 曲线横向顶到图表自身边缘；页面容器已经提供统一的 20dp 外边距。
        // 纵轴数值放到右侧图内，避免再为标签留下导致曲线缩短的空白列。
        val left = dp(2f)
        val right = width - dp(2f)
        val top = dp(8f)
        val bottom = height - dp(30f)
        if (right <= left || bottom <= top) return
        val axis = axisRange()
        val yOf: (Double) -> Float = { raw ->
            val value = raw.coerceIn(axis.minimum, axis.maximum)
            bottom - ((value - axis.minimum) / axis.span).toFloat() * (bottom - top)
        }
        val xOf: (Int) -> Float = { index ->
            if (values.size <= 1) (left + right) / 2f
            else left + (right - left) * index / (values.size - 1).toFloat()
        }

        drawGrid(canvas, left, right, top, bottom, axis, xOf)
        val primaryRuns = curveRuns(values, xOf, yOf)
        val secondaryRuns = curveRuns(secondary, xOf, yOf)
        // 风力和其他指标一样仅填充主数据曲线下方；阵风只作为独立的细线对照。
        if (kind == MetricKind.WIND) {
            drawArea(
                canvas, primaryRuns, bottom,
                kind.lineColors,
                // 风力填充在浅色页也保持可辨识，但仍低于主曲线的视觉权重。
                if (darkTheme) 112 else 142, top
            )
            drawRuns(canvas, secondaryRuns, secondaryPaint.apply { color = Color.rgb(190, 230, 255) })
        } else {
            drawArea(canvas, primaryRuns, bottom, kind.lineColors, 92, top)
            if (secondaryRuns.isNotEmpty()) drawRuns(canvas, secondaryRuns, secondaryPaint)
        }
        linePaint.shader = LinearGradient(
            0f, bottom, 0f, top,
            kind.lineColors,
            null,
            Shader.TileMode.CLAMP
        )
        drawRuns(canvas, primaryRuns, linePaint)
        linePaint.shader = null
        drawSelection(canvas, left, right, top, bottom, xOf, yOf)
    }

    private fun drawGrid(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        axis: AxisRange,
        xOf: (Int) -> Float
    ) {
        repeat(5) { step ->
            val f = step / 4f
            val y = bottom - (bottom - top) * f
            canvas.drawLine(left, y, right, y, gridPaint)
            val value = axis.minimum + axis.span * f
            canvas.drawText(axisText(value), right - dp(3f), y + dp(3f), axisPaint)
        }
        val labelIndices = values.indices.filter { it % 6 == 0 || it == values.lastIndex }
        labelIndices.forEach { index ->
            val x = xOf(index)
            canvas.drawLine(x, top, x, bottom, guidePaint)
            val label = times.getOrNull(index)?.let { timeFormat.format(Date(it)) }.orEmpty()
            val safeX = x.coerceIn(dp(16f), width - dp(16f))
            canvas.drawText(label, safeX, height - dp(8f), captionPaint)
        }
    }

    private fun drawArea(
        canvas: Canvas,
        runs: List<CurveRun>,
        bottom: Float,
        colors: IntArray,
        alpha: Int,
        top: Float
    ) {
        if (runs.isEmpty()) return
        val colorGradient = LinearGradient(
            0f, bottom, 0f, top,
            colors.map { color ->
                Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            }.toIntArray(),
            null,
            Shader.TileMode.CLAMP
        )
        val fadeGradient = LinearGradient(
            0f, top, 0f, bottom,
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        // 颜色和透明度都沿纵轴变化：低值在底部、高值在顶部，填充向下自然淡出。
        fillPaint.shader = ComposeShader(colorGradient, fadeGradient, PorterDuff.Mode.DST_IN)
        runs.forEach { run ->
            val area = Path(run.path)
            area.lineTo(run.lastX, bottom)
            area.lineTo(run.firstX, bottom)
            area.close()
            canvas.drawPath(area, fillPaint)
        }
        fillPaint.shader = null
    }

    private fun drawRuns(canvas: Canvas, runs: List<CurveRun>, paint: Paint) {
        runs.forEach { canvas.drawPath(it.path, paint) }
    }

    private fun drawSelection(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        xOf: (Int) -> Float,
        yOf: (Double) -> Float
    ) {
        val index = selectedIndex.coerceIn(values.indices)
        val x = xOf(index)
        val selectedValue = values[index]
        canvas.drawLine(x, top, x, bottom, selectionGuidePaint)
        if (!selectedValue.isNaN()) {
            val y = yOf(selectedValue)
            val axis = axisRange()
            val lineColor = colorAt(selectedValue, axis)
            // 三层焦点由所有天气图表共用：语义色光晕、主题反色内核、折线同色中心。
            ChartSelectionMarker.draw(canvas, x, y, lineColor, darkTheme, resources.displayMetrics.density)
        }
        val secondaryValue = secondary.getOrNull(index)?.takeUnless { it.isNaN() }
        if (kind == MetricKind.WIND && secondaryValue != null) {
            ChartSelectionMarker.draw(
                canvas, x, yOf(secondaryValue), Color.rgb(190, 230, 255),
                darkTheme, resources.displayMetrics.density
            )
        }
        // 当前数据、等级和建议在图表上方的页面文本中同步更新；图内只保留导向线与高亮点。
    }

    private data class Point(val x: Float, val y: Float)
    private data class CurveRun(val path: Path, val firstX: Float, val lastX: Float)

    private fun curveRuns(
        series: DoubleArray,
        xOf: (Int) -> Float,
        yOf: (Double) -> Float
    ): List<CurveRun> {
        val out = ArrayList<CurveRun>()
        val run = ArrayList<Point>()
        fun flush() {
            if (run.isEmpty()) return
            val path = Path()
            path.moveTo(run.first().x, run.first().y)
            when (run.size) {
                1 -> Unit
                2 -> path.lineTo(run.last().x, run.last().y)
                else -> {
                    for (i in 1 until run.size) {
                        val previous = run[i - 1]
                        val current = run[i]
                        if (i < run.lastIndex) {
                            val midpointX = (previous.x + current.x) / 2f
                            val midpointY = (previous.y + current.y) / 2f
                            path.quadTo(previous.x, previous.y, midpointX, midpointY)
                        } else {
                            path.quadTo(previous.x, previous.y, current.x, current.y)
                        }
                    }
                }
            }
            out += CurveRun(path, run.first().x, run.last().x)
            run.clear()
        }
        series.forEachIndexed { index, value ->
            if (value.isNaN()) flush() else run += Point(xOf(index), yOf(value))
        }
        flush()
        return out
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val left = dp(2f)
                val right = width - dp(2f)
                val index = ((event.x - left) / (right - left).coerceAtLeast(1f) *
                    (values.size - 1)).toInt().coerceIn(values.indices)
                if (index != selectedIndex) {
                    selectedIndex = index
                    dispatchSelection()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private data class AxisRange(val minimum: Double, val maximum: Double) {
        val span: Double get() = (maximum - minimum).coerceAtLeast(1.0)
    }

    // 常规指标保持 0 起点；体感温度与气压使用贴近数据的整数范围，避免信息被压扁。
    private fun axisRange(): AxisRange {
        val all = values.asSequence().plus(secondary.asSequence()).filter { !it.isNaN() }.toList()
        val rawMax = all.maxOrNull() ?: 1.0
        if (kind != MetricKind.FEELS_LIKE && kind != MetricKind.PRESSURE) {
            val upper = ceil(max(1.0, rawMax * 1.12) / 4.0) * 4.0
            return AxisRange(0.0, upper)
        }
        val rawMin = all.minOrNull() ?: 0.0
        val span = (rawMax - rawMin).coerceAtLeast(if (kind == MetricKind.PRESSURE) 2.0 else 4.0)
        val padding = max(if (kind == MetricKind.PRESSURE) 1.0 else 2.0, span * 0.18)
        var lower = floor(rawMin - padding)
        var upper = ceil(rawMax + padding)
        if (upper - lower < 2.0) {
            lower = floor(rawMin) - 1.0
            upper = ceil(rawMax) + 1.0
        }
        // 五条横向网格线（四个区间）均对齐整数，避免出现 19、20、20、21 的重复刻度。
        val remainder = ((upper - lower).toInt() % 4 + 4) % 4
        if (remainder != 0) upper += 4 - remainder
        return AxisRange(lower, upper)
    }

    private fun axisText(value: Double): String =
        WeatherSettings.metricAxisText(context, kind, value)

    private fun colorAt(value: Double, axis: AxisRange): Int {
        val fraction = ((value - axis.minimum) / axis.span).toFloat().coerceIn(0f, 1f)
        val scaled = fraction * (kind.lineColors.size - 1)
        val start = scaled.toInt().coerceIn(0, kind.lineColors.lastIndex)
        val end = (start + 1).coerceAtMost(kind.lineColors.lastIndex)
        val local = scaled - start
        val from = kind.lineColors[start]
        val to = kind.lineColors[end]
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * local).roundToInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * local).roundToInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * local).roundToInt()
        )
    }

    private fun dispatchSelection() {
        onSelectionChanged?.invoke(
            selectedIndex,
            times.getOrElse(selectedIndex) { 0L },
            values.getOrNull(selectedIndex)?.takeUnless { it.isNaN() },
            secondary.getOrNull(selectedIndex)?.takeUnless { it.isNaN() }
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
