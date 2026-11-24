package com.example.weatherapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import kotlin.math.exp
import kotlin.math.roundToInt

/** A small cached halo made from the original icon's coloured silhouette. */
internal object DailyWeatherIconGlow {
    private const val ICON_SIZE = 64
    private const val PADDING = 9
    private const val SIZE = ICON_SIZE + PADDING * 2
    private const val RADIUS = PADDING - 1
    val paddingRatio: Float get() = PADDING.toFloat() / ICON_SIZE

    private val cache = LruCache<Pair<Bitmap, Boolean>, Bitmap>(16)
    private val weights = FloatArray(RADIUS * 2 + 1) { i ->
        val distance = (i - RADIUS) / 2.8
        exp(-.5 * distance * distance).toFloat()
    }.let { raw ->
        val sum = raw.sum()
        FloatArray(raw.size) { raw[it] / sum }
    }

    fun load(icon: Bitmap, light: Boolean): Bitmap {
        val key = icon to light
        return cache[key] ?: render(icon, light).also { cache.put(key, it) }
    }

    private fun render(icon: Bitmap, light: Boolean): Bitmap {
        val image = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val iconRect = Rect(PADDING, PADDING, SIZE - PADDING, SIZE - PADDING)
        Canvas(image).drawBitmap(icon, null, iconRect, Paint(Paint.FILTER_BITMAP_FLAG))
        val source = IntArray(SIZE * SIZE)
        image.getPixels(source, 0, SIZE, 0, 0, SIZE, SIZE)

        val alpha = FloatArray(source.size) { Color.alpha(source[it]).toFloat() }
        val input = FloatArray(source.size)
        val pass = FloatArray(source.size)
        val blurredAlpha = FloatArray(source.size)
        blur(alpha, pass, blurredAlpha)

        val result = IntArray(source.size)
        val blurredChannel = FloatArray(input.size)
        val whitening = if (light) .14f else .24f
        for (shift in intArrayOf(16, 8, 0)) {
            for (index in input.indices) {
                val channel = (source[index] shr shift) and 0xff
                val tint = channel + (255 - channel) * whitening
                input[index] = tint * alpha[index] / 255f
            }
            blur(input, pass, blurredChannel)
            for (index in result.indices) {
                if (blurredAlpha[index] < 1f) continue
                val channel = (blurredChannel[index] * 255f / blurredAlpha[index]).roundToInt().coerceIn(0, 255)
                result[index] = result[index] or (channel shl shift)
            }
        }

        val strength = if (light) .57f else .79f
        for (index in result.indices) {
            val outside = 1f - alpha[index] / 255f
            val opacity = (blurredAlpha[index] * outside * strength).roundToInt()
            result[index] = if (opacity > 0) result[index] or (opacity shl 24) else Color.TRANSPARENT
        }
        image.setPixels(result, 0, SIZE, 0, 0, SIZE, SIZE)
        return image
    }

    private fun blur(input: FloatArray, pass: FloatArray, output: FloatArray) {
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val row = y * SIZE
            var sum = 0f
            for (offset in -RADIUS..RADIUS) {
                val near = x + offset
                if (near in 0 until SIZE) sum += input[row + near] * weights[offset + RADIUS]
            }
            pass[row + x] = sum
        }
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var sum = 0f
            for (offset in -RADIUS..RADIUS) {
                val near = y + offset
                if (near in 0 until SIZE) sum += pass[near * SIZE + x] * weights[offset + RADIUS]
            }
            output[y * SIZE + x] = sum
        }
    }
}
