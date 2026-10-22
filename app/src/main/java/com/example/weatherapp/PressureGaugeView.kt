package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// 气压仪表盘：完全复刻参考图样式——
// 240° 半圆刻度轨道；已走过部分为渐变大圆弧（深灰蓝→亮白），其余为细短刻度线；
// 大圆弧末端是白色粗短端头；中央从上到下依次是趋势箭头、数值、单位；底部左右“低/高”。
class PressureGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var pressureHpa: Double? = null
    private var unitText = "百帕"
    // 气压变化趋势（hPa/小时）；null 表示未知，显示三条横线
    private var trendPerHour: Double? = null

    // 量程加宽（950~1080 hPa），让常见气压的指示线大多停留在中部，不贴最高/最低点
    private val lowHpa = 950f
    private val highHpa = 1080f
    private val startAngle = 150f
    private val sweepAngle = 240f

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 半透明白色刻度线
        color = Color.argb(130, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    // 半透明色带：铺满整个 240° 刻度带，带拖尾渐变（靠近指示条越白），两端直角
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(26f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 与其他卡片说明文字一致的 60% 白色
        color = Color.argb(153, 255, 255, 255)
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    // 趋势图标：圆角线条箭头 / 等于号
    private val trendStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setPressure(hpa: Double?, unit: String, trend: Double? = null) {
        pressureHpa = hpa
        unitText = unit
        trendPerHour = trend
        invalidate()
    }

    // 单独更新趋势（气压趋势数据晚于实况到达）
    fun setTrend(trend: Double?) {
        trendPerHour = trend
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 几何尺寸完全收在视图内：弧顶不超出上边界，低/高标尺不超出下边界
        val cx = w / 2f
        // 图表整体上移：圆心贴近顶部标题所在横行，弧顶紧贴视图上边界
        val cy = h * 0.44f
        val radius = minOf(
            w / 2f - dp(12f),
            cy - dp(2f),
            (h - cy - dp(10f)) / 0.60f
        ).coerceAtLeast(dp(6f))

        // 1) 细刻度线：整段 240° 均匀分布
        tickPaint.strokeWidth = dp(1.5f)
        val tickCount = 37
        for (i in 0 until tickCount) {
            val deg = startAngle + sweepAngle * i / (tickCount - 1)
            val rad = Math.toRadians(deg.toDouble())
            val x1 = cx + cos(rad).toFloat() * (radius - dp(1f))
            val y1 = cy + sin(rad).toFloat() * (radius - dp(1f))
            val x2 = cx + cos(rad).toFloat() * (radius - dp(8f))
            val y2 = cy + sin(rad).toFloat() * (radius - dp(8f))
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // 2) 当前值位置
        val p = ((pressureHpa ?: 1005.0).toFloat() - lowHpa) / (highHpa - lowHpa)
        val progress = p.coerceIn(0f, 1f)
        val curAngle = startAngle + sweepAngle * progress
        val curRad = Math.toRadians(curAngle.toDouble())

        // 3) 半透明色带：径向范围与刻度带完全一致（r-8 ~ r-1，中心在 r-4.5），
        //    从“低”端到指示条逐段渐变：指示条处白色，向“低”端渐变为透明
        bandPaint.strokeWidth = dp(7f)
        val bandRadius = radius - dp(4.5f)
        val bandRect = RectF(
            cx - bandRadius, cy - bandRadius,
            cx + bandRadius, cy + bandRadius
        )
        val endAngle = startAngle + sweepAngle * progress
        if (endAngle - startAngle > 0.5f) {
            val step = 1.5f
            var a = startAngle
            while (a < endAngle - 0.01f) {
                val next = minOf(a + step, endAngle)
                val t = (a - startAngle) / (endAngle - startAngle)
                val alpha = (18 + 138 * t).toInt().coerceIn(0, 156)
                bandPaint.color = Color.argb(alpha, 255, 255, 255)
                canvas.drawArc(bandRect, a, next - a, false, bandPaint)
                a = next
            }
        }

        // 4) 白色指示条：径向短条，完全居中在刻度带内（r-8 ~ r-1），缩短外侧延伸
        headPaint.strokeWidth = dp(4f)
        val hx1 = cx + cos(curRad).toFloat() * (radius - dp(8f))
        val hy1 = cy + sin(curRad).toFloat() * (radius - dp(8f))
        val hx2 = cx + cos(curRad).toFloat() * (radius - dp(1f))
        val hy2 = cy + sin(curRad).toFloat() * (radius - dp(1f))
        canvas.drawLine(hx1, hy1, hx2, hy2, headPaint)

        // 4) 底部“低 / 高”标尺
        // 低/高：向下移动到图表底部，与卡片下边距的预留距离一致，不贴刻度线
        // 低/高文字底端贴近卡片内边距（约16dp），与其他卡片说明文字下边距一致
        val labelY = h - dp(8f)
        // 两端文字沿纵轴中心线向中间收敛一点
        canvas.drawText("低", cx - radius * 0.68f, labelY, sidePaint)
        canvas.drawText("高", cx + radius * 0.68f, labelY, sidePaint)

        // 5) 中央：趋势箭头 + 数值（千分位）+ 单位
        val value = pressureHpa?.let { v ->
            when (unitText) {
                "毫米汞柱" -> String.format(Locale.US, "%,d", Math.round(v * 0.750062))
                "英寸汞柱" -> one(v * 0.02953)
                else -> String.format(Locale.US, "%,d", Math.round(v))
            }
        } ?: "--"
        // 趋势图标：上箭头 = 正在上升，下箭头 = 正在下降，等于号 = 平稳/未知
        // 趋势图标整体上移，避免贴近下方数值
        drawTrendIcon(canvas, cx, cy - dp(24f))
        canvas.drawText(value, cx, cy + dp(8f), valuePaint)
        canvas.drawText(unitText, cx, cy + dp(28f), unitPaint)
    }

    private fun drawTrendIcon(canvas: Canvas, cx: Float, centerY: Float) {
        val trend = trendPerHour
        if (trend == null || abs(trend) <= 0.1) {
            // 平稳（或未知）：等于号
            val half = dp(6f)
            for (dy in intArrayOf(-1, 1)) {
                val y = centerY + dy * dp(2.2f)
                canvas.drawLine(cx - half, y, cx + half, y, trendStrokePaint)
            }
            return
        }
        val shaft = dp(11f)
        val head = dp(4.5f)
        if (trend > 0.1) {
            // 上升：箭头朝上
            val top = centerY - shaft / 2f
            val bottom = centerY + shaft / 2f
            canvas.drawLine(cx, bottom, cx, top, trendStrokePaint)
            canvas.drawLine(cx - head, top + head, cx, top, trendStrokePaint)
            canvas.drawLine(cx + head, top + head, cx, top, trendStrokePaint)
        } else {
            // 下降：箭头朝下
            val top = centerY - shaft / 2f
            val bottom = centerY + shaft / 2f
            canvas.drawLine(cx, top, cx, bottom, trendStrokePaint)
            canvas.drawLine(cx - head, bottom - head, cx, bottom, trendStrokePaint)
            canvas.drawLine(cx + head, bottom - head, cx, bottom, trendStrokePaint)
        }
    }

    private fun one(v: Double): String {
        val s = String.format(Locale.US, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
