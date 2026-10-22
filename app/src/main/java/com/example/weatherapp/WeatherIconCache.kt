package com.example.weatherapp

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache

// 天气 SVG 图标加载缓存：24小时图表和多日预报共用，
// SVG 解析及原始位图由 WeatherSvgRasterCache 共用，此处只缓存居中后的图标。
object WeatherIconCache {

    private data class Key(val assets: AssetManager, val filename: String, val px: Int)

    // Eviction drops references only: callers may still be displaying a bitmap.
    private val cache = object : LruCache<Key, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
    }

    // 彩云天气代码 → 对应的 SVG 文件名（使用 tianqitubiao 素材集）
    fun skyconToIcon(skycon: String?): String? = when (skycon) {
        "CLEAR_DAY" -> "tq-1.svg"
        "CLEAR_NIGHT" -> "yewan.svg"
        "PARTLY_CLOUDY_DAY" -> "duoyun.svg"
        "PARTLY_CLOUDY_NIGHT" -> "yejianduoyun.svg"
        "CLOUDY" -> "yin.svg"
        "LIGHT_HAZE", "MODERATE_HAZE", "HEAVY_HAZE" -> "mai.svg"
        "LIGHT_RAIN" -> "xiaoyu.svg"
        "MODERATE_RAIN" -> "zhongyu.svg"
        "HEAVY_RAIN" -> "dayu.svg"
        "STORM_RAIN" -> "baoyu.svg"
        "LIGHT_THUNDER", "MODERATE_THUNDER", "HEAVY_THUNDER", "STORM_THUNDER" -> "leizhenyu.svg"
        "FOG" -> "wu.svg"
        "LIGHT_SNOW" -> "xiaoxue.svg"
        "MODERATE_SNOW" -> "zhongxue.svg"
        "HEAVY_SNOW" -> "daxue.svg"
        "STORM_SNOW" -> "baoxue.svg"
        "LIGHT_SLEET", "MODERATE_SLEET", "HEAVY_SLEET", "RAIN_SNOW" -> "yujiaxue.svg"
        "DUST" -> "fuchen.svg"
        "SAND" -> "yangsha.svg"
        "WIND" -> "dafeng.svg"
        else -> null
    }

    @Synchronized
    fun load(context: Context, skycon: String?, size: Float): Bitmap? {
        val filename = skyconToIcon(skycon) ?: return null
        val px = size.toInt().coerceAtLeast(1)
        val key = Key(context.assets, filename, px)
        cache.get(key)?.let { return it }
        val bmp = try {
            val raw = WeatherSvgRasterCache.load(context, filename, px) ?: return null
            // 图标内容在 SVG 里不一定居中（如雨云带雨滴偏下），
            // 把不透明内容裁剪后重新居中到方框，保证和文字垂直中心对齐
            centerContent(raw)
        } catch (e: Exception) {
            null
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    // 把位图中的不透明内容按"完整形状外框"居中：
    // 雨滴/云朵这类图标，像素重心在云上，但眼睛看到的是整个形状（含雨滴），
    // 按外框居中才能让图标整体（云+雨滴）的中心和文字中心对齐
    private fun centerContent(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] ushr 24) and 0xFF > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX) return src
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        if (cw >= w && ch >= h) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src,
            Rect(minX, minY, maxX + 1, maxY + 1),
            Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }
}
