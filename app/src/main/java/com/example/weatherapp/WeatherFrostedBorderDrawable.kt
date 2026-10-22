package com.example.weatherapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Original dark-mode rim; the light-mode counterpart is drawn by [LightFrostedEdge]. */
internal object WeatherFrostedEdge {
    fun shader(top: Float, bottom: Float) = LinearGradient(0f, top, 0f, bottom,
        intArrayOf(Color.argb(74, 255, 255, 255), Color.argb(19, 255, 255, 255),
            Color.argb(12, 255, 255, 255), Color.argb(38, 255, 255, 255)),
        floatArrayOf(0f, .24f, .68f, 1f), Shader.TileMode.CLAMP)
}

internal class WeatherFrostedBorderDrawable(private val density: Float, private val radiusDp: Float,
    internal val dark: Boolean = true) : Drawable() {
    private val lightEdge = LightFrostedEdge(density)
    private val layerPaint = Paint()
    private var shaderTop = Int.MIN_VALUE
    private var shaderBottom = Int.MIN_VALUE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = .75f * density
    }
    override fun onBoundsChange(bounds: android.graphics.Rect) {
        if (bounds.top == shaderTop && bounds.bottom == shaderBottom) return
        shaderTop = bounds.top
        shaderBottom = bounds.bottom
        paint.shader = WeatherFrostedEdge.shader(bounds.top.toFloat(), bounds.bottom.toFloat())
    }
    override fun draw(canvas: Canvas) {
        if (!dark) {
            val save = if (paint.alpha != 255 || paint.colorFilter != null) {
                layerPaint.alpha = paint.alpha; layerPaint.colorFilter = paint.colorFilter
                canvas.saveLayer(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), layerPaint)
            } else -1
            lightEdge.draw(canvas, bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), radiusDp * density)
            if (save >= 0) canvas.restoreToCount(save)
            return
        }
        val inset = paint.strokeWidth / 2f
        val radius = radiusDp * density - inset
        canvas.drawRoundRect(bounds.left + inset, bounds.top + inset, bounds.right - inset,
            bounds.bottom - inset, radius, radius, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
