package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

// 每日温度范围条（参考图片效果）：
// 一条半透明深色空轨道，按当天最低/最高温在全局温度刻度上的位置，
// 用黄→橙红渐变填充中间一段；今天的条上还有一个白点标记当前实时温度
class TempBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var low = 0
    private var high = 0
    private var minTemp = 0
    private var maxTemp = 100
    private var currentTemp: Double? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    // 不依赖软件层阴影，避免列表滚动时掉帧；用一条低透明度偏移线营造轻微立体感。
    private val fillShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(34, 28, 45, 82)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
    }

    fun setRange(low: Int, high: Int, minTemp: Int, maxTemp: Int, currentTemp: Double?) {
        this.low = low
        this.high = high
        this.minTemp = minTemp
        this.maxTemp = maxTemp
        this.currentTemp = currentTemp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val stroke = dp(5f)
        trackPaint.strokeWidth = stroke
        fillPaint.strokeWidth = stroke

        val pad = dp(6f)
        val left = pad
        val right = w - pad
        val cy = h / 2f
        if (right <= left) return

        // 深色空轨道（整条）
        canvas.drawLine(left, cy, right, cy, trackPaint)

        val span = (maxTemp - minTemp).toFloat()
        if (span <= 0f || high <= low) return
        val lx = left + (low - minTemp) / span * (right - left)
        val rx = left + (high - minTemp) / span * (right - left)
        if (rx - lx < stroke) return

        // 更明亮的暖色填充：在半透明天气卡片上仍保持清晰，同时保留柔和边缘。
        fillPaint.shader = LinearGradient(
            lx, 0f, rx, 0f,
            Color.argb(246, 255, 229, 76), Color.argb(246, 255, 111, 62),
            Shader.TileMode.CLAMP
        )
        fillShadowPaint.color = Color.argb(64, 255, 102, 58)
        fillShadowPaint.strokeWidth = stroke + dp(.5f)
        canvas.drawLine(lx, cy + dp(1f), rx, cy + dp(1f), fillShadowPaint)
        canvas.drawLine(lx, cy, rx, cy, fillPaint)
        fillPaint.shader = null

        // 今天的白点：标记当前实时温度在当天范围内的位置
        currentTemp?.let { t ->
            val cx = left + (t.toFloat() - minTemp) / span * (right - left)
            if (cx in left..right) {
                canvas.drawCircle(cx, cy, dp(7f), dotHaloPaint)
                canvas.drawCircle(cx, cy, dp(4f), dotPaint)
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
