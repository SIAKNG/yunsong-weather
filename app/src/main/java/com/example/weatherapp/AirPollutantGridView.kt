package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 六项污染物的两行三列信息与进度条。 */
class AirPollutantGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: AirQualityPoint? = null
    private var darkTheme = false
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setData(point: AirQualityPoint?, isDark: Boolean) {
        data = point
        darkTheme = isDark
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val items = listOf(
            "PM2.5" to data?.pm25, "NO₂" to data?.no2, "O₃" to data?.o3,
            "PM10" to data?.pm10, "CO" to data?.co, "SO₂" to data?.so2
        )
        val column = width / 3f
        val row = height / 2f
        val primary = if (darkTheme) Color.WHITE else Color.rgb(24, 28, 31)
        val secondary = if (darkTheme) Color.argb(160, 255, 255, 255) else Color.rgb(142, 142, 142)
        val track = if (darkTheme) Color.argb(65, 255, 255, 255) else Color.rgb(229, 229, 229)
        items.forEachIndexed { index, (name, value) ->
            val col = index % 3
            val rowIndex = index / 3
            val left = col * column + column * 0.12f
            val right = (col + 1) * column - column * 0.12f
            val top = rowIndex * row
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = resources.displayMetrics.scaledDensity * 13f
            textPaint.color = secondary
            canvas.drawText(name, (left + right) / 2f, top + row * 0.20f, textPaint)
            textPaint.textSize = resources.displayMetrics.scaledDensity * 18f
            textPaint.color = primary
            canvas.drawText(AirQualityScale.formatPollutant(name, value), (left + right) / 2f, top + row * 0.48f, textPaint)
            val barTop = top + row * 0.67f
            val barHeight = resources.displayMetrics.density * 8f
            val rect = RectF(left, barTop, right, barTop + barHeight)
            barPaint.color = track
            canvas.drawRoundRect(rect, barHeight, barHeight, barPaint)
            val progress = AirQualityScale.pollutantProgress(name, value)
            if (value != null && !value.isNaN()) {
                // 即使数值很低或为 0，也至少以一个圆点大小表示“已有观测数据”。
                val fillRight = (left + (right - left) * progress).coerceAtLeast(left + barHeight)
                // 这里不能沿用半透明渐变：在详情页的浅色卡片上会被灰色轨道“冲淡”，
                // 让填充段像是盖了一层蒙版。填充层固定为 255 alpha 的高饱和实色，
                // 轨道仅作为未达部分的对比，不参与填充颜色混合。
                barPaint.shader = null
                barPaint.alpha = 255
                barPaint.color = displayFillColor(
                    AirQualityScale.color((progress * 300.0).coerceAtLeast(16.0))
                )
                canvas.drawRoundRect(RectF(left, barTop, fillRight, barTop + barHeight), barHeight, barHeight, barPaint)
            }
        }
    }

    /** 统一提升饱和度和明度，避免任一主题下低浓度颜色融入灰色轨道。 */
    private fun displayFillColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceAtLeast(if (darkTheme) 0.78f else 0.72f)
        hsv[2] = hsv[2].coerceAtLeast(if (darkTheme) 0.96f else 0.82f)
        return Color.HSVToColor(255, hsv)
    }
}
