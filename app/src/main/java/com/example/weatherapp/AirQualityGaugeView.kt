package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** 紧凑的 270° AQI 仪表，两端采用圆角描边。 */
class AirQualityGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var aqi: Double? = null
    private var darkTheme = false
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var readableLevelColor=Color.WHITE

    fun setData(value: Double?, isDark: Boolean) {
        aqi = value
        darkTheme = isDark
        var readable=AirQualityScale.color(value)
        val foreground=if(isDark) Color.WHITE else Color.rgb(20,24,24)
        val surface=androidx.core.content.ContextCompat.getColor(context,R.color.page_bg)
        repeat(8) {
            if (androidx.core.graphics.ColorUtils.calculateContrast(readable,surface)<4.5)
                readable=androidx.core.graphics.ColorUtils.blendARGB(readable,foreground,.18f)
        }
        readableLevelColor=if(androidx.core.graphics.ColorUtils.calculateContrast(readable,surface)>=4.5) readable else foreground
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        // 限制直径，避免在宽屏上被拉成过宽的大仪表盘。
        val size = min(width * 0.88f, height * 1.10f)
        val stroke = size * 0.078f
        val cx = width / 2f
        val cy = height * 0.57f
        val radius = size * 0.42f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val track = if (darkTheme) Color.argb(70, 255, 255, 255) else Color.rgb(228, 228, 228)
        arcPaint.strokeWidth = stroke
        arcPaint.color = track
        canvas.drawArc(oval, 135f, 270f, false, arcPaint)

        val progress = ((aqi ?: 0.0) / 500.0).toFloat().coerceIn(0f, 1f)
        val segments = (progress * 108).toInt().coerceAtLeast(0)
        if (segments > 0) {
            val unit = 270f / 108f
            repeat(segments) { index ->
                val level = (index + 1) * 500.0 / 108.0
                arcPaint.color = AirQualityScale.color(level)
                canvas.drawArc(oval, 135f + index * unit, unit + 0.8f, false, arcPaint)
            }
        }

        val primary = if (darkTheme) Color.WHITE else Color.rgb(20, 24, 24)
        textPaint.color = primary
        // 中央数值是仪表的主信息，略高于原先比例但不扩大整体仪表。
        textPaint.textSize = size * 0.225f
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(aqi?.toInt()?.toString() ?: "--", cx, cy - radius * 0.03f + dp(7f), textPaint)
        textPaint.color = readableLevelColor
        textPaint.textSize = size * 0.080f
        canvas.drawText(AirQualityScale.level(aqi), cx, cy + radius * 0.23f + dp(6f), textPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
