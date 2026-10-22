package com.example.weatherapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/** One continuous white specular rim like the native light dock, not grey double lines. */
internal class LightFrostedEdge(private val density: Float) {
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val reflection = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cached = RectF()
    private var emphasis = -1f
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, strength: Float = 1f) {
        if (right <= left || bottom <= top) return
        if (cached.left != left || cached.top != top || cached.right != right || cached.bottom != bottom || emphasis != strength) {
            cached.set(left, top, right, bottom); emphasis = strength
            fun white(alpha: Int) = Color.argb((alpha * strength).toInt().coerceIn(0, 255), 255, 255, 255)
            rim.shader = LinearGradient(0f, top, 0f, bottom,
                intArrayOf(white(190), white(42), white(48), white(235)),
                floatArrayOf(0f, .22f, .7f, 1f), Shader.TileMode.CLAMP)
            reflection.shader = LinearGradient(0f, top, 0f, bottom,
                intArrayOf(white(24), Color.TRANSPARENT, white(14)),
                floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)
        }
        val r = minOf(radius, (right - left) / 2f, (bottom - top) / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(cached, r, r, reflection)
        val inset = rim.strokeWidth / 2f
        canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset,
            (r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f), rim)
    }
}
