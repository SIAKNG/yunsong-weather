package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/** 实时温度居中的体感温差条：绿色基准点的圆角断口、连续的数据高亮与两侧圆角实线。 */
class FeelsLikeDeltaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(8f)
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(8f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 211, 104)
        style = Paint.Style.FILL
    }
    private val endpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val endpointHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private var actual: Double? = null
    private var feelsLike: Double? = null
    private var fahrenheit = false

    fun setTemperatures(actual: Double?, feelsLike: Double?, unitText: String) {
        val isFahrenheit = unitText.endsWith("°F")
        if (this.actual == actual && this.feelsLike == feelsLike && fahrenheit == isFahrenheit) return
        this.actual = actual
        this.feelsLike = feelsLike
        fahrenheit = isFahrenheit
        // 体感温差条不参与首页非线性入场动画，数据更新后立即落到真实位置。
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lineWidth = dp(8f)
        val left = lineWidth / 2f
        val right = width - lineWidth / 2f
        if (right <= left) return
        val centerX = width / 2f
        val centerY = height / 2f
        // 只有实际温度的绿色基准点拥有圆角断口；数据高亮的底轨保持连续，
        // 不再把留白跟着高亮点移动。
        // 与 UV/AQI 高亮点到轨道端帽约 2dp 的留白保持一致，避免中间绿色基准点
        // 紧贴左右轨道截断位置。
        val centerGap = dp(10f)
        if (centerX - centerGap > left) canvas.drawLine(left, centerY, centerX - centerGap, centerY, trackPaint)
        if (centerX + centerGap < right) canvas.drawLine(centerX + centerGap, centerY, right, centerY, trackPaint)

        val delta = displayedDelta()
        if (delta != null) {
            val markerX = IndicatorTrackGeometry.feelsLikeMarkerX(
                left, right, centerGap, dp(5f), lineWidth, delta, if (fahrenheit) 10.8 else 6.0
            )
            if (abs(markerX - centerX) >= .5f) {
                val warm = markerX > centerX
                val nearColor = if (warm) Color.rgb(255, 204, 0) else Color.rgb(90, 200, 250)
                val farColor = if (warm) Color.rgb(255, 69, 58) else Color.rgb(10, 132, 255)
                val direction = if (warm) 1f else -1f
                val fillStartX = centerX + direction * centerGap
                fillPaint.shader = LinearGradient(
                    fillStartX, centerY, markerX, centerY,
                    nearColor, farColor, Shader.TileMode.CLAMP
                )
                if ((markerX - fillStartX) * direction >= .5f) {
                    canvas.drawLine(fillStartX, centerY, markerX, centerY, fillPaint)
                }
                fillPaint.shader = null
            }
            // 即使体感与实温完全相同，也在 0 点绘制高亮。绿色基准点随后覆盖在中心，
            // 形成“光晕 + 白色环 + 绿色内芯”，不会被误认为数据缺失。
            canvas.drawCircle(markerX, centerY, dp(9f), endpointHaloPaint)
            canvas.drawCircle(markerX, centerY, dp(5f), endpointPaint)
        }

        // 绿色圆点固定表示实际温度，并恰好覆盖中间圆角断口。
        canvas.drawCircle(centerX, centerY, dp(4f), dotPaint)
    }

    private fun displayedDelta(): Double? =
        FeelsLikeComfortPolicy.displayDelta(actual, feelsLike)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
