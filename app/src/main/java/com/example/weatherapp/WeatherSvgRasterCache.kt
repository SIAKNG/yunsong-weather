package com.example.weatherapp

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import com.caverock.androidsvg.SVG

/** Exact, uncropped weather rasters. Returned bitmaps must be treated as read-only. */
internal object WeatherSvgRasterCache {
    private data class DocumentKey(val assets: AssetManager, val filename: String)
    private data class RasterKey(val document: DocumentKey, val px: Int)
    private data class Document(val svg: SVG?)

    // Enough for the bundled weather assets; also bounds failed asset lookups.
    private val documents = LruCache<DocumentKey, Document>(128)
    private val rasters = object : LruCache<RasterKey, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: RasterKey, value: Bitmap): Int = value.byteCount
    }

    // SVG dimensions are mutable: serialize lookup, dimension changes and rendering,
    // so concurrent requests cannot render another request's dimensions.
    @Synchronized
    fun load(context: Context, filename: String, px: Int): Bitmap? {
        if (px <= 0) return null
        val documentKey = DocumentKey(context.assets, filename)
        val key = RasterKey(documentKey, px)
        rasters.get(key)?.let { return it }
        return try {
            val document = documents.get(documentKey) ?: Document(
                try {
                    SVG.getFromAsset(documentKey.assets, "weather/$filename")
                } catch (e: Exception) {
                    null
                }
            ).also { documents.put(documentKey, it) }
            val svg = document.svg ?: return PublicWeatherIconRenderer.render(filename,px,px).also {
                rasters.put(key,it)
            }
            // Preserve the original viewport/aspect pipeline at every requested size.
            svg.setDocumentWidth(px.toFloat())
            svg.setDocumentHeight(px.toFloat())
            val picture = svg.renderToPicture()
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { bitmap ->
                Canvas(bitmap).drawPicture(picture)
                rasters.put(key, bitmap)
            }
        } catch (e: Exception) {
            null
        }
    }

    // LruCache eviction only drops references. Views and the centered icon cache
    // may still reference these bitmaps, so they must never be recycled here.
}
