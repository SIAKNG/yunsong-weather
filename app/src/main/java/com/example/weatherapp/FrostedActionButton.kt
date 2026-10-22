package com.example.weatherapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.widget.TextView

/** Reuses the homepage glass renderer; only the background is blurred, never the label. */
internal class FrostedActionButton(context: Context) : TextView(context) {
    private val glass = BlurCardView(context).apply {
        setFrostedMaterial(true)
        setCornerRadius(12f * resources.displayMetrics.density)
        setEdgeShadowVisible(false)
    }
    private var sample: Bitmap? = null
    private var capturePending = false

    init { background = glass.background }

    fun setGlassState(atCurrent: Boolean, dark: Boolean) {
        if (!dark) {
            DetailGlassSurface.install(this, 12f * resources.displayMetrics.density).setStateTint(true, atCurrent)
            // As in dark mode, the unavailable action loses its bright glass rim.
            foreground = if (atCurrent) null else WeatherFrostedBorderDrawable(resources.displayMetrics.density, 12f, false)
            invalidate()
            return
        }
        foreground = null
        elevation = 0f
        val tint = if (dark) {
            if (atCurrent) Color.argb(28, 150, 159, 175) else Color.argb(156, 91, 117, 151)
        } else {
            if (atCurrent) Color.argb(22, 109, 120, 136) else Color.argb(145, 124, 179, 226)
        }
        // Tint already includes its intended alpha. Avoid a separate translucent white
        // base multiplying that alpha in the shared renderer; homepage fills stay unchanged.
        glass.setTintWithWhiteBase(0f, tint, tint)
        // Strong state differentiation without fading the text into unreadability.
        glass.setStrokeVisible(!atCurrent)
        background = glass.background
        invalidate()
        requestCapture()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestCapture()
    }

    private fun requestCapture() {
        if (capturePending) return
        capturePending = true
        post {
            capturePending = false
            if (!isAttachedToWindow || width == 0 || height == 0) return@post
            // Capture only a small padded region, excluding this button to avoid self-sampling.
            val root = rootView
            val rootLocation = IntArray(2); root.getLocationInWindow(rootLocation)
            val location = IntArray(2); getLocationInWindow(location)
            val pad = (36f * resources.displayMetrics.density).toInt()
            val x = location[0] - rootLocation[0]
            val y = location[1] - rootLocation[1]
            val bitmap = Bitmap.createBitmap(width + pad * 2, height + pad * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate((pad - x).toFloat(), (pad - y).toFloat())
            val previousVisibility = visibility
            visibility = INVISIBLE
            try { root.draw(canvas) } finally { visibility = previousVisibility }
            val old = sample
            sample = bitmap
            glass.setLiveSource(bitmap, Rect(pad, pad, pad + width, pad + height))
            old?.recycle()
            invalidate()
        }
    }
}
