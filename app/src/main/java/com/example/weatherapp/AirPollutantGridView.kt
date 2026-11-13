package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** 六项污染物的两行三列信息与进度条。 */
class AirPollutantGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.widget.LinearLayout(context, attrs) {
    init { orientation = VERTICAL }

    fun setData(point: AirQualityPoint?, isDark: Boolean) {
        removeAllViews()
        val columns = if (resources.configuration.fontScale > 1.3f || resources.configuration.screenWidthDp < 340) 2 else 3
        val items = listOf(
            "PM2.5" to point?.pm25, "NO₂" to point?.no2, "O₃" to point?.o3,
            "PM10" to point?.pm10, "CO" to point?.co, "SO₂" to point?.so2
        )
        for (group in items.chunked(columns)) {
            val row = android.widget.LinearLayout(context).apply { isBaselineAligned = false }
            for ((name, value) in group) {
                val tile = android.widget.LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(7), dp(4), dp(7), dp(7))
                }
                tile.addView(label(name, 13f, R.color.text_secondary))
                tile.addView(label(AirQualityScale.formatPollutant(name, value), 18f, R.color.text_primary))
                val progress = value?.takeIf { it.isFinite() }?.let {
                    AirQualityScale.pollutantProgress(name, it)
                }
                val trackColor = if (isDark) Color.argb(65, 255, 255, 255) else Color.rgb(229, 229, 229)
                val fillColor = progress?.let {
                    displayFillColor(AirQualityScale.color((it * 300.0).coerceAtLeast(16.0)), isDark)
                }
                val bar = PollutantBar(context, progress, trackColor, fillColor)
                tile.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(4) })
                row.addView(tile, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun label(text: String, size: Float, color: Int) = android.widget.TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(androidx.core.content.ContextCompat.getColor(context, color))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.CENTER
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    /** 统一提升饱和度和明度，避免任一主题下低浓度颜色融入灰色轨道。 */
    private fun displayFillColor(color: Int, darkTheme: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceAtLeast(if (darkTheme) 0.78f else 0.72f)
        hsv[2] = hsv[2].coerceAtLeast(if (darkTheme) 0.96f else 0.82f)
        return Color.HSVToColor(255, hsv)
    }

    private class PollutantBar(
        context: Context,
        private val progress: Float?,
        private val trackColor: Int,
        private val fillColor: Int?
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val trackWidth = width.toFloat()
            val trackHeight = height.toFloat()
            val radius = trackHeight / 2f
            paint.color = trackColor
            canvas.drawRoundRect(0f, 0f, trackWidth, trackHeight, radius, radius, paint)

            val fraction = progress ?: return
            paint.color = fillColor ?: return
            val fillWidth = (trackWidth * fraction).coerceIn(
                min(trackWidth, maxOf(radius, trackWidth * .065f)),
                trackWidth
            )
            val fillRadius = min(radius, fillWidth / 2f)
            val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) trackWidth - fillWidth else 0f
            canvas.drawRoundRect(left, 0f, left + fillWidth, trackHeight, fillRadius, fillRadius, paint)
        }
    }
}
