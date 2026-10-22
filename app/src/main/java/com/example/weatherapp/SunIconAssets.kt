package com.example.weatherapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG

/** 统一把日出、日落 SVG 素材渲染为位图，供图表与卡片文字旁复用。 */
object SunIconAssets {
    private val cache = HashMap<String, Bitmap>()

    @Synchronized
    fun load(context: Context, sunrise: Boolean, width: Int, height: Int): Bitmap? {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val name = if (sunrise) "richu.svg" else "riluo.svg"
        val key = "$name@$safeWidth x $safeHeight"
        cache[key]?.let { return it }
        return try {
            val svg = SVG.getFromAsset(context.assets, "weather/$name")
            svg.setDocumentWidth(safeWidth.toFloat())
            svg.setDocumentHeight(safeHeight.toFloat())
            val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPicture(svg.renderToPicture())
            cache[key] = bitmap
            bitmap
        } catch (_: Exception) {
            PublicWeatherIconRenderer.render(name,safeWidth,safeHeight).also { cache[key] = it }
        }
    }
}
