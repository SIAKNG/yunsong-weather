package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Locale

// 分钟级降雨图表（参考 xiaoshitubiao 风格）：
// 每个槽位先画一根淡灰蓝“幽灵”胶囊条，有雨的槽位再叠一根亮青渐变短胶囊条；
// 条宽约为槽宽的 51%（约为上一版粗细的 50%），两端圆角；
// 按 2.5 分钟一个槽位线性插值，线条数量适当增多，
// 条形中心仍与时间刻度一一对齐（现在/每半小时/满 2 小时及原始数据点都正对一根降雨条）。
class MinutelyChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values = DoubleArray(0)
    private var times = LongArray(0)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 0, 0, 0) // 幽灵底条：8% 黑色
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        textSize = sp(10f)
    }
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    fun setPrecip(v: List<Double>, times: List<Long>) {
        values = v.toDoubleArray()
        this.times = times.toLongArray()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || values.isEmpty()) return

        val labelH = dp(18f)
        val chartTop = dp(6f)
        val baseY = h - labelH - dp(2f)
        val chartH = baseY - chartTop
        if (chartH <= 0f) return

        val left = dp(2f)
        val right = w - dp(2f)
        // 按 2.5 分钟一个槽位线性插值绘制：线条更细、数量适当增多，
        // 原始数据点（5/10 分钟）与整半点时间刻度仍各自正对一根降雨条
        val t0 = if (times.isNotEmpty()) times[0] else System.currentTimeMillis()
        val twoHours = 2L * 60 * 60_000L
        val slotStep = 150_000L
        val slotCount = (twoHours / slotStep).toInt() + 1
        val slotW = (right - left) / (slotCount - 1).coerceAtLeast(1)
        // 线条粗细调整到现在的 50%：条宽约为槽宽的 51%，上限 9dp、下限 3dp
        val barW = (slotW * 0.51f).coerceIn(dp(3f), dp(9f))
        val rr = barW / 2f
        val ghostTop = chartTop + dp(2f)
        val ghostH = baseY - ghostTop

        // 1) 每个槽位先画淡灰蓝幽灵条；有雨的槽位再叠亮青渐变短条。
        //    高度映射与之前一致：输入 mm/h，分段算填充比例后取 0.70 次方视觉压缩。
        for (j in 0 until slotCount) {
            val v = valueAt(t0 + slotStep * j)
            val cx = left + slotW * j
            val bLeft = cx - barW / 2f
            val bRight = cx + barW / 2f
            canvas.drawRoundRect(bLeft, ghostTop, bRight, baseY, rr, rr, ghostPaint)
            if (v <= 0.0) continue
            val fillRatio = fillRatioFor(v)
            val visualRatio = if (fillRatio > 0f) Math.pow(fillRatio.toDouble(), 0.70).toFloat() else 0f
            val barH = (ghostH * visualRatio).coerceIn(0f, ghostH)
            val bTop = baseY - barH
            // 参考图：顶 #A7E9FF → 底 #8FD9FF 的亮青渐变
            barPaint.shader = LinearGradient(
                0f, bTop, 0f, baseY,
                Color.rgb(167, 233, 255),
                Color.rgb(143, 217, 255),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(bLeft, bTop, bRight, baseY, rr, rr, barPaint)
        }
        barPaint.shader = null

        // 3) 时间轴：现在 + 每半小时 + 满 2 小时，文字与条形中心对齐（不绘制刻度圆点）
        val halfHour = 30 * 60_000L
        val labelY = h - dp(3f)
        var dt = 0L
        while (dt <= twoHours) {
            val x = left + slotW * (dt / slotStep)
            when (dt) {
                0L -> {
                    labelPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText("现在", left, labelY, labelPaint)
                }
                twoHours -> {
                    labelPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(clockFmt.format(t0 + dt), right, labelY, labelPaint)
                }
                else -> {
                    labelPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(clockFmt.format(t0 + dt), x, labelY, labelPaint)
                }
            }
            dt += halfHour
        }
    }

    // 线性插值：取原始分钟级数据在目标时刻的值（首尾点直接沿用端点值）
    private fun valueAt(targetMillis: Long): Double {
        if (times.isEmpty()) return 0.0
        if (targetMillis <= times[0]) return values[0]
        if (targetMillis >= times.last()) return values.last()
        for (i in 0 until times.size - 1) {
            val a = times[i]
            val b = times[i + 1]
            if (targetMillis in a..b) {
                val span = (b - a).coerceAtLeast(1L)
                val f = (targetMillis - a).toDouble() / span
                return values[i] + (values[i + 1] - values[i]) * f
            }
        }
        return values.last()
    }

    // 高度映射：与 SkyPulse 的 MinutelyPrecipitation 逻辑一致。
    // 输入为 mm/h，分段 0.1/0.3/0.5/1/2/2.5/8/16/30，满条 = 30mm/h。
    private fun fillRatioFor(mmPerHour: Double): Float {
        return when {
            mmPerHour <= 0.1 -> (mmPerHour / 0.1 * 0.03f).toFloat()
            mmPerHour <= 0.3 -> (0.03f + (mmPerHour - 0.1) / 0.2 * 0.05f).toFloat()
            mmPerHour <= 0.5 -> (0.08f + (mmPerHour - 0.3) / 0.2 * 0.07f).toFloat()
            mmPerHour <= 1.0 -> (0.15f + (mmPerHour - 0.5) / 0.5 * 0.07f).toFloat()
            mmPerHour <= 2.0 -> (0.22f + (mmPerHour - 1.0) / 1.0 * 0.10f).toFloat()
            mmPerHour <= 2.5 -> (0.32f + (mmPerHour - 2.0) / 0.5 * 0.06f).toFloat()
            mmPerHour <= 8.0 -> (0.38f + (mmPerHour - 2.5) / 5.5 * 0.32f).toFloat()
            mmPerHour <= 16.0 -> (0.70f + (mmPerHour - 8.0) / 8.0 * 0.20f).toFloat()
            else -> (0.90f + (mmPerHour - 16.0) / 14.0 * 0.10f).coerceIn(0.90, 1.0).toFloat()
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
