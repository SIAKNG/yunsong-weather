package com.example.weatherapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

// 空气质量渐变条：绿→黄→橙→红→紫→深红，当前色高亮点定位当前 AQI。
class AqiBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var aqi = 0f
    private var hasValue = false
    private var targetProgress = 0f
    private var shownProgress = 0f
    private var lastEntryGeneration = Long.MIN_VALUE
    private var entryAnimator: ValueAnimator? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setAqi(value: Double?) {
        hasValue = value != null
        aqi = (value ?: 0.0).toFloat()
        targetProgress = IndicatorTrackGeometry.normalized(aqi.toDouble(), 300.0)
        if (entryAnimator?.isRunning != true) shownProgress = targetProgress
        invalidate()
    }

    /** 每次当前城市页重新进入时，从左端以非线性曲线滑到实时 AQI。 */
    fun playEntryAnimation(generation: Long): Boolean {
        if (!hasValue || lastEntryGeneration == generation) return false
        lastEntryGeneration = generation
        entryAnimator?.cancel()
        shownProgress = 0f
        entryAnimator = ValueAnimator.ofFloat(0f, targetProgress).apply {
            // 速度降为此前的 50%，保留原来的非线性缓入节奏。
            duration = 1320L
            interpolator = PathInterpolator(0.18f, 0.76f, 0.22f, 1f)
            addUpdateListener {
                shownProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val cy = h / 2f
        val left = dp(2f)
        val right = w - dp(2f)
        val barH = dp(8f)
        val rect = RectF(left, cy - barH / 2f, right, cy + barH / 2f)

        barPaint.shader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(
                Color.rgb(52, 199, 89),
                Color.rgb(255, 204, 0),
                Color.rgb(255, 149, 0),
                Color.rgb(255, 59, 48),
                Color.rgb(150, 60, 200),
                Color.rgb(120, 30, 60)
            ),
            floatArrayOf(0f, 0.17f, 0.33f, 0.5f, 0.67f, 1f),
            Shader.TileMode.CLAMP
        )
        if (hasValue) {
            val markerRadius = barH / 2f
            // 指示点位置将渐变条截成两段圆角端点。入场动画时这道留白与点共用
            // IndicatorTrackGeometry，因此绝不会残留在终点或起点。
            val gap = markerRadius + dp(2f)
            val segments = IndicatorTrackGeometry.segments(left, right, markerRadius, gap, shownProgress)
            if (segments.leftEnd > left) {
                canvas.drawRoundRect(RectF(left, cy - barH / 2f, segments.leftEnd, cy + barH / 2f), barH / 2f, barH / 2f, barPaint)
            }
            if (segments.rightStart < right) {
                canvas.drawRoundRect(RectF(segments.rightStart, cy - barH / 2f, right, cy + barH / 2f), barH / 2f, barH / 2f, barPaint)
            }
            dotPaint.color = colorAt(shownProgress)
            canvas.drawCircle(segments.markerX, cy, markerRadius, dotPaint)
        } else {
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, barPaint)
        }
        barPaint.shader = null
    }

    override fun onDetachedFromWindow() {
        entryAnimator?.cancel()
        entryAnimator = null
        super.onDetachedFromWindow()
    }

    private fun colorAt(progress: Float): Int {
        val colors = intArrayOf(Color.rgb(52, 199, 89), Color.rgb(255, 204, 0), Color.rgb(255, 149, 0), Color.rgb(255, 59, 48), Color.rgb(150, 60, 200), Color.rgb(120, 30, 60))
        val stops = floatArrayOf(0f, .17f, .33f, .5f, .67f, 1f)
        val segment = stops.indices.last { stops[it] <= progress }.coerceAtMost(stops.lastIndex - 1)
        val f = ((progress - stops[segment]) / (stops[segment + 1] - stops[segment])).coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * f).toInt()
        return Color.rgb(channel(Color.red(colors[segment]), Color.red(colors[segment + 1])), channel(Color.green(colors[segment]), Color.green(colors[segment + 1])), channel(Color.blue(colors[segment]), Color.blue(colors[segment + 1])))
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
