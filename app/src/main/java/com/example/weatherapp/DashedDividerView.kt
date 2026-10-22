package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** 设置分组内使用的浅灰细实线分割。 */
class DashedDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 168, 174, 182)
        strokeWidth = dp(0.75f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawLine(paddingLeft.toFloat(), height / 2f, (width - paddingRight).toFloat(), height / 2f, paint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
