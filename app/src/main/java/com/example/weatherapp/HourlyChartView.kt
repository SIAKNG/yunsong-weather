package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs

// 日出/日落标记：hoursFromNow 表示距当前时刻的小时数（负数表示过去）
data class SunPoint(
    val hoursFromNow: Float,
    val timeText: String,
    val isSunrise: Boolean
)

// 图表中的一列：整点小时列（hourIndex >= 0）或日出/日落独立列（hourIndex = -1）
private data class ChartColumn(
    val hourIndex: Int,
    val sun: SunPoint?
)

// 自绘 24 小时图表（仿照参考 App 的逐小时预报样式）：
// 平滑曲线 = 气温（稳定 Bezier：单调保形切线，跨段连贯、永不过冲、无尖刺）；
// 曲线下方是淡蓝色渐变；下雨的时段画白色雨线；
// 每个小时下方：天气小图标 + 中文描述 + 降水概率；
// 日出/日落是独立列：垂直浅色虚线，折线穿过但不标点、不标温度、不显示降水概率
class HourlyChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var labels: List<String> = emptyList()
    private var temps: List<Double?> = emptyList()
    private var skycons: List<String?> = emptyList()
    private var probs: List<Double> = emptyList()
    private var pastCount = 0
    private var highlightIndex = 0
    private var sunEvents: List<SunPoint> = emptyList()

    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    // 历史段折线：虚线显示
    private val pastLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }
    private val tempDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val probPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 160, 225, 255)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val tempLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    // 图标是位图，drawBitmap 不用画笔颜色，必须用带 alpha 的画笔才能淡化
    private val iconBmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 255
    }
    private val pastLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(135, 255, 255, 255)
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val pastProbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 160, 225, 255)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setData(
        labels: List<String>,
        temps: List<Double?>,
        skycons: List<String?>,
        probs: List<Double>,
        pastCount: Int = 0,
        highlightIndex: Int = pastCount
    ) {
        this.labels = labels
        this.temps = temps
        this.skycons = skycons
        this.probs = probs
        this.pastCount = pastCount.coerceIn(0, temps.size)
        this.highlightIndex = highlightIndex.coerceIn(0, maxOf(0, temps.size - 1))
        invalidate()
        requestLayout()
    }

    fun setSunEvents(events: List<SunPoint>) {
        sunEvents = events
        invalidate()
        requestLayout()
    }

    // 把整点小时列和日出/日落事件按时间合并成等宽列：
    // 日出/日落插在对应整点列之间，成为独立的列
    private fun buildColumns(): List<ChartColumn> {
        val n = temps.size
        if (n == 0) return emptyList()
        val lastHour = n - pastCount
        val suns = sunEvents
            // 历史窗口只保留过去 1 小时（与 pastCount=1 对齐），
            // 不再显示更早的日出/日落列。
            .filter { it.hoursFromNow >= -1f && it.hoursFromNow <= lastHour - 0.01f }
            .sortedBy { it.hoursFromNow }
        val cols = ArrayList<ChartColumn>(n + suns.size)
        var si = 0
        for (i in 0 until n) {
            val h = (i - pastCount).toFloat()
            while (si < suns.size && suns[si].hoursFromNow < h) {
                val s = suns[si]
                // 与整点几乎重合时跳过，避免两列挤在同一位置
                if (abs(s.hoursFromNow - Math.round(s.hoursFromNow).toFloat()) > 0.02f) {
                    cols.add(ChartColumn(-1, s))
                }
                si++
            }
            cols.add(ChartColumn(i, null))
        }
        while (si < suns.size) {
            val s = suns[si]
            if (abs(s.hoursFromNow - Math.round(s.hoursFromNow).toFloat()) > 0.02f) {
                cols.add(ChartColumn(-1, s))
            }
            si++
        }
        return cols
    }

    // 让图表宽度根据数据列数自动变宽，从而可以在外层横向滑动看全部 24 小时
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cols = buildColumns()
        val desiredWidth = if (cols.isEmpty()) {
            resources.displayMetrics.widthPixels
        } else {
            (dp(54f) * cols.size).toInt()
        }
        val height = MeasureSpec.getSize(heightMeasureSpec)
            .let { if (it == 0) dp(240f).toInt() else it }
        setMeasuredDimension(desiredWidth, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (temps.isEmpty()) return
        val cols = buildColumns()
        if (cols.size < 2) return

        val left = dp(32f)
        val right = width - dp(32f)
        val top = dp(20f)
        val areaBottom = dp(96f)
        val descY = dp(126f)
        val probY = dp(148f)
        val iconCY = dp(176f)
        val iconSize = dp(32f)
        val timeY = height - dp(16f)

        val chartWidth = right - left
        if (chartWidth <= 0) return

        val tempValues = temps.filterNotNull()
        if (tempValues.isEmpty()) return
        val tMin = tempValues.min()
        val tMax = tempValues.max()
        val tRange = if (tMax - tMin < 1.0) 1.0 else tMax - tMin
        val pad = tRange * 0.15
        val yMin = tMin - pad
        val yMax = tMax + pad
        val yRange = yMax - yMin

        val n = temps.size
        if (n < 2) return
        val mCols = cols.size
        val step = chartWidth / (mCols - 1)

        fun xAtCol(c: Int): Float = left + step * c

        fun tempY(v: Double): Float =
            (areaBottom - ((v - yMin) / yRange * (areaBottom - top))).toFloat()

        // 每个整点小时在合并列中的下标（气温曲线只依赖整点列）
        val hourCol = IntArray(n)
        var hi = 0
        for (c in cols.indices) {
            if (cols[c].hourIndex >= 0) {
                hourCol[hi] = c
                hi++
            }
        }

        // —— 每个数据点的像素坐标（缺数据记为 NaN）——
        val px = FloatArray(n)
        val py = FloatArray(n)
        for (i in 0 until n) {
            px[i] = xAtCol(hourCol[i])
            py[i] = temps[i]?.let { tempY(it) } ?: Float.NaN
        }

        // 单调保形切线（Fritsch-Carlson）：每个点的切线由左右相邻两点共同决定，
        // 整条曲线跨段连贯（切线连续），且切线受调和平均限制 → 数学上永不过冲。
        val dy = FloatArray(n)
        for (i in 0 until n - 1) {
            if (!py[i].isNaN() && !py[i + 1].isNaN()) {
                val dx = px[i + 1] - px[i]
                if (dx > 0f) dy[i] = (py[i + 1] - py[i]) / dx
            }
        }
        val m = FloatArray(n)  // 每点切线斜率（像素/像素）
        for (i in 0 until n) {
            if (py[i].isNaN()) continue
            val dL = if (i > 0) dy[i - 1] else Float.NaN
            val dR = if (i < n - 1) dy[i] else Float.NaN
            m[i] = when {
                dL.isNaN() && dR.isNaN() -> 0f
                dL.isNaN() -> dR
                dR.isNaN() -> dL
                dL * dR > 0f -> 2f * dL * dR / (dL + dR)
                else -> 0f
            }
        }

        // 曲线任意 x 处的高度（像素）；所在段缺数据时返回 NaN。
        // 日出/日落列之间没有整点，按两侧整点列的贝塞尔段插值，折线自然穿过
        fun curveYAt(x: Float): Float {
            val pos = (x - left) / step
            if (pos < hourCol[0] || pos > hourCol[n - 1]) return Float.NaN
            var i = 0
            while (i < n - 1 && pos > hourCol[i + 1]) i++
            val p0 = hourCol[i].toFloat()
            val p1 = hourCol[i + 1].toFloat()
            val y0 = py[i]
            val y1 = py[i + 1]
            if (y0.isNaN() || y1.isNaN()) return Float.NaN
            val dx = px[i + 1] - px[i]
            val t = ((pos - p0) / (p1 - p0)).coerceIn(0f, 1f)
            val c1y = y0 + m[i] * dx / 3f
            val c2y = y1 - m[i + 1] * dx / 3f
            val u = 1f - t
            return u * u * u * y0 + 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t * y1
        }

        // —— 曲线下方：一整条干净的淡蓝色渐变（与曲线同源采样）——
        val areaPath = Path()
        var areaStarted = false
        var areaMinY = areaBottom
        val ax0 = xAtCol(hourCol[0])
        val ax1 = xAtCol(hourCol[n - 1])
        val areaSamples = n * 16
        for (s in 0..areaSamples) {
            val sx = ax0 + (ax1 - ax0) * s / areaSamples
            val sy = curveYAt(sx)
            if (sy.isNaN()) {
                areaStarted = false
                continue
            }
            if (!areaStarted) {
                areaPath.moveTo(sx, sy)
                areaStarted = true
            } else {
                areaPath.lineTo(sx, sy)
            }
            if (sy < areaMinY) areaMinY = sy
        }
        areaPath.lineTo(ax1, areaBottom)
        areaPath.lineTo(ax0, areaBottom)
        areaPath.close()
        areaPaint.shader = LinearGradient(
            0f, areaMinY, 0f, areaBottom,
            intArrayOf(Color.argb(80, 120, 190, 255), Color.argb(0, 120, 190, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(areaPath, areaPaint)
        areaPaint.shader = null

        // —— 平滑气温曲线：历史段用灰色，当前及未来用白色 ——
        val pastCurvePath = Path()
        val curvePath = Path()
        var pastCurveStarted = false
        var curveStarted = false
        for (i in 0 until n - 1) {
            val y0 = py[i]
            val y1 = py[i + 1]
            if (y0.isNaN() || y1.isNaN()) {
                pastCurveStarted = false
                curveStarted = false
                continue
            }
            val x0 = px[i]
            val x1 = px[i + 1]
            val dx = x1 - x0
            val c1y = y0 + m[i] * dx / 3f
            val c2y = y1 - m[i + 1] * dx / 3f
            if (i < pastCount) {
                if (!pastCurveStarted) {
                    pastCurvePath.moveTo(x0, y0)
                    pastCurveStarted = true
                }
                pastCurvePath.cubicTo(x0 + dx / 3f, c1y, x1 - dx / 3f, c2y, x1, y1)
            } else {
                if (!curveStarted) {
                    curvePath.moveTo(x0, y0)
                    curveStarted = true
                }
                curvePath.cubicTo(x0 + dx / 3f, c1y, x1 - dx / 3f, c2y, x1, y1)
            }
        }
        // —— 历史段：曲线、圆点、温度数字在同一图层统一淡化，重叠处不再加深 ——
        val pastRight = if (pastCount >= n) {
            width.toFloat()
        } else {
            // 图层右边界要覆盖到“今天”第一个点，避免历史曲线末端被裁断造成断线
            xAtCol(hourCol[pastCount]) + dp(20f)
        }
        if (pastCount > 0) {
            canvas.saveLayerAlpha(0f, 0f, pastRight, height.toFloat(), 120)
        }
        canvas.drawPath(pastCurvePath, pastLinePaint)
        for (i in 0 until pastCount) {
            val v = temps[i] ?: continue
            val x = px[i]
            val y = py[i]
            canvas.drawCircle(x, y, dp(3.5f), tempDotPaint)
            canvas.drawText("${v.toInt()}°", x, y - dp(12f), tempLabelPaint)
        }
        if (pastCount > 0) canvas.restore()

        // —— 当前及未来：曲线、圆点、温度数字正常不透明绘制 ——
        canvas.drawPath(curvePath, tempPaint)
        for (i in pastCount until n) {
            val v = temps[i] ?: continue
            val x = px[i]
            val y = py[i]
            if (i == highlightIndex) {
                canvas.drawCircle(x, y, dp(8f), haloPaint)
                canvas.drawCircle(x, y, dp(4.5f), tempDotPaint)
                canvas.drawText("${v.toInt()}°", x, y - dp(12f), tempLabelPaint)
            } else {
                canvas.drawCircle(x, y, dp(3.5f), tempDotPaint)
                canvas.drawText("${v.toInt()}°", x, y - dp(12f), tempLabelPaint)
            }
        }

        // —— 每个整点小时的天气描述 + 降水概率 ——
        for ((ci, col) in cols.withIndex()) {
            if (col.hourIndex < 0) continue
            val i = col.hourIndex
            val x = xAtCol(ci)
            val sky = skycons.getOrNull(i)
            val desc = sky?.let { WeatherText.skyconToText(it) } ?: ""
            val past = i < pastCount
            if (desc.isNotBlank()) {
                canvas.drawText(desc, x, descY, if (past) pastLabelPaint else labelPaint)
            }
            val prob = probs.getOrNull(i) ?: 0.0
            if (prob >= 1 && isPrecip(sky)) {
                canvas.drawText(
                    "${prob.toInt()}%", x, probY,
                    if (past) pastProbPaint else probPaint
                )
            }
        }

        // —— 每个整点小时的天气图标（tianqitubiao 素材）——
        for ((ci, col) in cols.withIndex()) {
            if (col.hourIndex < 0) continue
            val i = col.hourIndex
            drawWeatherIcon(
                canvas, skycons.getOrNull(i), xAtCol(ci), iconCY, iconSize,
                if (i < pastCount) 135 else 255
            )
        }

        // —— 日出/日落独立列：文字、图标、时间（无圆点、无温度、无降水概率）——
        for ((ci, col) in cols.withIndex()) {
            val sun = col.sun ?: continue
            val x = xAtCol(ci)
            val past = sun.hoursFromNow < 0f
            val textPaint = if (past) pastLabelPaint else labelPaint
            canvas.drawText(if (sun.isSunrise) "日出" else "日落", x, descY, textPaint)
            // 与同列天气图标使用相同的 32dp 基准尺寸。
            drawSunIcon(canvas, x, iconCY, iconSize, sun.isSunrise, if (past) 135 else 255)
            canvas.drawText(sun.timeText, x, timeY, textPaint)
        }

        // —— 底部时间行（整点小时）——
        for ((ci, col) in cols.withIndex()) {
            if (col.hourIndex < 0) continue
            val i = col.hourIndex
            val text = labels.getOrNull(i) ?: continue
            if (text.isBlank()) continue
            canvas.drawText(
                text, xAtCol(ci), timeY,
                if (i < pastCount) pastLabelPaint else labelPaint
            )
        }
    }

    private fun isPrecip(skycon: String?): Boolean =
        skycon?.contains("RAIN") == true || skycon?.contains("SNOW") == true
            || skycon?.contains("STORM") == true

    // 天气小图标：统一使用 tianqitubiao 素材（与多日预报同一缓存，避免重复解析）
    private fun drawWeatherIcon(
        canvas: Canvas,
        skycon: String?,
        cx: Float,
        cy: Float,
        size: Float,
        alpha: Int = 255
    ) {
        val bmp = WeatherIconCache.load(context, skycon, size) ?: return
        iconBmpPaint.alpha = alpha
        canvas.drawBitmap(bmp, cx - size / 2f, cy - size / 2f, iconBmpPaint)
    }

    // 日出/日落图标：使用用户提供的 richu / riluo SVG 素材。
    private fun drawSunIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        isSunrise: Boolean,
        alpha: Int = 255
    ) {
        val height = (size * 0.76f).toInt().coerceAtLeast(1)
        val bitmap = SunIconAssets.load(context, isSunrise, size.toInt(), height) ?: return
        iconBmpPaint.alpha = alpha
        canvas.drawBitmap(bitmap, cx - size / 2f, cy - height / 2f, iconBmpPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}
