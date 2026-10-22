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
        if (BuildConfig.USER_CONFIGURABLE) {
            drawSegmentedDial(canvas)
            return
        }
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
        textPaint.color = AirQualityScale.color(aqi)
        textPaint.textSize = size * 0.080f
        canvas.drawText(AirQualityScale.level(aqi), cx, cy + radius * 0.23f + dp(6f), textPaint)
    }

    /** A full six-band half-dial, not the filled 270-degree gauge used by the reference app. */
    private fun drawSegmentedDial(canvas: Canvas) {
        val cx=width/2f
        val radius=min(width*.40f,height*.68f)
        val cy=height*.78f
        val stroke=radius*.095f
        val oval=RectF(cx-radius,cy-radius,cx+radius,cy+radius)
        val edges=doubleArrayOf(0.0,50.0,100.0,150.0,200.0,300.0,500.0)
        arcPaint.strokeWidth=stroke
        arcPaint.style=Paint.Style.STROKE
        for (index in 0 until edges.lastIndex) {
            arcPaint.color=AirQualityScale.color((edges[index]+edges[index+1])/2)
            val start=180f+(edges[index]/500.0*180).toFloat()+1.2f
            val sweep=((edges[index+1]-edges[index])/500.0*180).toFloat()-2.4f
            canvas.drawArc(oval,start,sweep,false,arcPaint)
        }
        aqi?.takeIf{it.isFinite()}?.let {value->
            val angle=Math.toRadians(180.0+value.coerceIn(0.0,500.0)/500*180)
            val x=cx+kotlin.math.cos(angle).toFloat()*radius
            val y=cy+kotlin.math.sin(angle).toFloat()*radius
            arcPaint.style=Paint.Style.FILL
            arcPaint.color=if(darkTheme) Color.WHITE else Color.rgb(24,32,42)
            canvas.drawCircle(x,y,stroke*.88f,arcPaint)
            arcPaint.color=AirQualityScale.color(value)
            canvas.drawCircle(x,y,stroke*.58f,arcPaint)
        }
        textPaint.textAlign=Paint.Align.CENTER
        textPaint.typeface=android.graphics.Typeface.DEFAULT_BOLD
        textPaint.color=if(darkTheme) Color.WHITE else Color.rgb(20,24,24)
        textPaint.textSize=radius*.49f
        canvas.drawText(aqi?.toInt()?.toString() ?: "--",cx,cy-radius*.20f,textPaint)
        textPaint.color=readableLevelColor
        textPaint.textSize=radius*.18f
        canvas.drawText(AirQualityScale.level(aqi),cx,cy+radius*.10f,textPaint)
        arcPaint.style=Paint.Style.STROKE
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
