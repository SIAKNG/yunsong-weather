package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

// 多日预报的"折线"模式（参考图片 2 的样式）：
// 顶部星期 + 日期，下面天气描述/图标/降水概率，
// 中间两条白色趋势线（最高温、最低温），线上标高温、线下标低温；
// 7 天时铺满卡片，展开 15 天时变宽横向滑动
class DailyTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var dayLabels: List<String> = emptyList()
    private var dateTexts: List<String> = emptyList()
    private var skycons: List<String?> = emptyList()
    private var probs: List<Double> = emptyList()
    private var highs: List<Int?> = emptyList()
    private var lows: List<Int?> = emptyList()
    private var showCount = 7
    private var pastCount = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // 历史段折线：虚线显示
    private val pastLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val descPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val probPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 160, 225, 255)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val highLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val lowLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    fun setData(
        dayLabels: List<String>,
        dateTexts: List<String>,
        skycons: List<String?>,
        probs: List<Double>,
        highs: List<Int?>,
        lows: List<Int?>,
        pastCount: Int = 0
    ) {
        this.dayLabels = dayLabels
        this.dateTexts = dateTexts
        this.skycons = skycons
        this.probs = probs
        this.highs = highs
        this.lows = lows
        this.pastCount = pastCount.coerceIn(0, dayLabels.size)
        invalidate()
        requestLayout()
    }

    // 7 天铺满卡片；展开时显示全部（15 天，变宽可横向滑动）
    fun setShowCount(count: Int) {
        // 数据尚未加载时 dayLabels 为空，coerceIn(1, 0) 会抛空区间异常，先兜底
        showCount = count.coerceIn(1, maxOf(1, dayLabels.size))
        invalidate()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = showCount
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        // 每页 5 天：列宽 = 卡片宽度 / 5，15 天时整体宽度 = 3 页，可横向滑动
        val colW = if (parentW > 0) parentW / 5 else dp(72f).toInt()
        val desired = maxOf(parentW, colW * n)
        val height = MeasureSpec.getSize(heightMeasureSpec)
            .let { if (it == 0) dp(230f).toInt() else it }
        setMeasuredDimension(desired, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = showCount
        if (n < 2) return

        // 参照 24 小时卡片：左右各留 32dp，保证首尾列的宽文字/图标
        // 在滑动到两端时也不会被卡片边缘裁断
        val left = dp(32f)
        val right = width - dp(32f)
        val chartW = right - left
        if (chartW <= 0) return
        // 与 24 小时卡片一致：列从左边距开始依次排列（第 0 列在 left、最后一列在 right），
        // 保证滑动到两端时首尾列文字距卡片边缘的间距与标题一致
        val step = chartW / (n - 1).toFloat()

        fun xAt(i: Int): Float = left + step * i

        // 顶部信息：星期 / 日期 / 天气文字 / 图标 / 降水概率
        val dayY = dp(16f)
        val dateY = dp(34f)
        val descY = dp(54f)
        val iconCY = dp(82f)
        val iconSize = dp(26f)
        val probY = dp(102f)

        for (i in 0 until n) {
            val x = xAt(i)
            val dim = i < pastCount
            if (dim) {
                headPaint.alpha = 110
                datePaint.alpha = 100
                descPaint.alpha = 110
                probPaint.alpha = 100
            } else {
                headPaint.alpha = 255
                datePaint.alpha = 200
                descPaint.alpha = 230
                probPaint.alpha = 210
            }
            canvas.drawText(dayLabels.getOrNull(i) ?: "", x, dayY, headPaint)
            canvas.drawText(dateTexts.getOrNull(i) ?: "", x, dateY, datePaint)
            val sky = skycons.getOrNull(i)
            val desc = sky?.let { WeatherText.skyconToText(it) } ?: ""
            if (desc.isNotBlank()) canvas.drawText(desc, x, descY, descPaint)
            val bmp = WeatherIconCache.load(context, sky, iconSize)
            if (bmp != null) {
                if (dim) {
                    canvas.saveLayerAlpha(
                        x - iconSize / 2f, iconCY - iconSize / 2f,
                        x + iconSize / 2f, iconCY + iconSize / 2f, 110
                    )
                }
                canvas.drawBitmap(bmp, x - iconSize / 2f, iconCY - iconSize / 2f, null)
                if (dim) canvas.restore()
            }
            val prob = probs.getOrNull(i) ?: 0.0
            if (prob >= 1 && isPrecip(sky)) {
                canvas.drawText("${prob.toInt()}%", x, probY, probPaint)
            }
        }

        // 两条温度线：最高温在上半区，最低温在下半区
        val chartTop = dp(118f)
        val chartMid = dp(168f)
        val chartBottom = dp(212f)

        val highValues = highs.take(n).filterNotNull()
        val lowValues = lows.take(n).filterNotNull()
        if (highValues.isEmpty() || lowValues.isEmpty()) return

        val hMin = highValues.min()
        val hMax = highValues.max()
        val lMin = lowValues.min()
        val lMax = lowValues.max()
        val hRange = if (hMax - hMin < 1) 1 else hMax - hMin
        val lRange = if (lMax - lMin < 1) 1 else lMax - lMin

        fun highY(v: Int): Float =
            chartTop + dp(8f) + (hMax - v).toFloat() / hRange * (chartMid - chartTop - dp(16f))

        fun lowY(v: Int): Float =
            chartMid + dp(8f) + (lMax - v).toFloat() / lRange * (chartBottom - chartMid - dp(16f))

        // 最高温线 + 点 + 数值：与 24 小时卡片一致，用单调保形贝塞尔连接数据点
        val px = FloatArray(n) { xAt(it) }
        val highPy = FloatArray(n) { Float.NaN }
        for (i in 0 until n) {
            highs.getOrNull(i)?.let { highPy[i] = highY(it) }
        }
        val highM = computeTangents(highPy, step)
        val pastHighPath = Path()
        val highPath = Path()
        appendBezierSegments(pastHighPath, px, highPy, highM, 0, pastCount)
        appendBezierSegments(highPath, px, highPy, highM, pastCount, n - 1)
        // —— 历史段：折线、圆点、温度数字在同一图层统一淡化，重叠处不再加深 ——
        val pastRight = if (pastCount >= n) {
            width.toFloat()
        } else {
            // 图层右边界覆盖到"今天"第一个点，避免历史曲线末端被裁断造成断线
            xAt(pastCount) + dp(20f)
        }
        if (pastCount > 0) {
            canvas.saveLayerAlpha(0f, 0f, pastRight, height.toFloat(), 120)
        }
        canvas.drawPath(pastHighPath, pastLinePaint)
        for (i in 0 until pastCount) {
            val v = highs.getOrNull(i) ?: continue
            val x = xAt(i)
            val y = highY(v)
            canvas.drawCircle(x, y, dp(3f), dotPaint)
            canvas.drawText("$v°", x, y - dp(7f), highLabelPaint)
        }
        if (pastCount > 0) canvas.restore()
        canvas.drawPath(highPath, linePaint)
        for (i in pastCount until n) {
            val v = highs.getOrNull(i) ?: continue
            val x = xAt(i)
            val y = highY(v)
            canvas.drawCircle(x, y, dp(3f), dotPaint)
            canvas.drawText("$v°", x, y - dp(7f), highLabelPaint)
        }

        // 最低温线 + 点 + 数值：同样用贝塞尔连接
        val lowPy = FloatArray(n) { Float.NaN }
        for (i in 0 until n) {
            lows.getOrNull(i)?.let { lowPy[i] = lowY(it) }
        }
        val lowM = computeTangents(lowPy, step)
        val pastLowPath = Path()
        val lowPath = Path()
        appendBezierSegments(pastLowPath, px, lowPy, lowM, 0, pastCount)
        appendBezierSegments(lowPath, px, lowPy, lowM, pastCount, n - 1)
        if (pastCount > 0) {
            canvas.saveLayerAlpha(0f, 0f, pastRight, height.toFloat(), 120)
        }
        canvas.drawPath(pastLowPath, pastLinePaint)
        for (i in 0 until pastCount) {
            val v = lows.getOrNull(i) ?: continue
            val x = xAt(i)
            val y = lowY(v)
            canvas.drawCircle(x, y, dp(3f), dotPaint)
            canvas.drawText("$v°", x, y + dp(14f), lowLabelPaint)
        }
        if (pastCount > 0) canvas.restore()
        canvas.drawPath(lowPath, linePaint)
        for (i in pastCount until n) {
            val v = lows.getOrNull(i) ?: continue
            val x = xAt(i)
            val y = lowY(v)
            canvas.drawCircle(x, y, dp(3f), dotPaint)
            canvas.drawText("$v°", x, y + dp(14f), lowLabelPaint)
        }
    }

    // 与 24 小时图表相同的单调保形贝塞尔切线：切线由左右相邻点调和平均决定，曲线不过冲
    private fun computeTangents(py: FloatArray, step: Float): FloatArray {
        val n = py.size
        val dy = FloatArray(n)
        for (i in 0 until n - 1) {
            if (!py[i].isNaN() && !py[i + 1].isNaN()) dy[i] = (py[i + 1] - py[i]) / step
        }
        val m = FloatArray(n)
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
        return m
    }

    // 把 [from, to) 段内相邻数据点用三次贝塞尔连接（跳过缺数据段）
    private fun appendBezierSegments(
        path: Path,
        px: FloatArray,
        py: FloatArray,
        m: FloatArray,
        from: Int,
        to: Int
    ) {
        var started = false
        for (i in from until to) {
            if (py[i].isNaN() || py[i + 1].isNaN()) {
                started = false
                continue
            }
            val x0 = px[i]
            val y0 = py[i]
            val x1 = px[i + 1]
            val y1 = py[i + 1]
            val dx = x1 - x0
            val c1y = y0 + m[i] * dx / 3f
            val c2y = y1 - m[i + 1] * dx / 3f
            if (!started) {
                path.moveTo(x0, y0)
                started = true
            }
            path.cubicTo(x0 + dx / 3f, c1y, x1 - dx / 3f, c2y, x1, y1)
        }
    }

    private fun isPrecip(skycon: String?): Boolean =
        skycon?.contains("RAIN") == true || skycon?.contains("SNOW") == true
            || skycon?.contains("STORM") == true

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
