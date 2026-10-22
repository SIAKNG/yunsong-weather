package com.example.weatherapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import kotlin.math.ceil
import androidx.core.content.ContextCompat

/** Blur the real backdrop, never foreground labels. Popups explicitly supply their activity root. */
internal class DetailGlassSurface private constructor(
    private val host: View,
    private val radius: Float,
    private val backdrop: View? = null
) : Drawable() {
    private val density = host.resources.displayMetrics.density
    private val glass = BlurCardView(host.context).apply {
        setFrostedMaterial(true); setCornerRadius(radius)
        setBlurRadius((if (backdrop != null) WeatherCardStyle.FROST_BLUR_DP else 26f) * density)
        setStrokeVisible(false)
    }
    private var sample: Bitmap? = null
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val lastRegion = Rect()
    private val visibleRegion = Rect()
    private var dirty = true
    private var pending = false
    private var capturing = false
    private var lastCapture = 0L
    private var observer: ViewTreeObserver? = null
    private var backdropObserver: ViewTreeObserver? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (host.background === this && !capturing && host.isShown && host.width > 0 && host.height > 0) {
            host.getLocationOnScreen(location)
            if (dirty || lastRegion.left != location[0] || lastRegion.top != location[1] ||
                lastRegion.width() != host.width || lastRegion.height() != host.height) requestCapture()
        }
        true
    }
    private val scroll = ViewTreeObserver.OnScrollChangedListener { dirty = true; requestCapture() }
    init {
        setStateTint(false, false)
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { observe(); dirty = true; requestCapture() }
            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(preDraw); it.removeOnScrollChangedListener(scroll) }
                observer = null
                backdropObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scroll)
                backdropObserver = null
            }
        })
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> dirty = true; requestCapture() }
        if (host.isAttachedToWindow) observe()
        requestCapture()
    }
    private fun observe() {
        val next = host.rootView.viewTreeObserver
        if (observer === next) return
        observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(preDraw); it.removeOnScrollChangedListener(scroll) }
        observer = next
        next.addOnPreDrawListener(preDraw); next.addOnScrollChangedListener(scroll)
        backdropObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scroll)
        backdropObserver = backdrop?.viewTreeObserver?.also { it.addOnScrollChangedListener(scroll) }
    }
    fun setStateTint(action: Boolean, disabled: Boolean) {
        // Available actions share the panel's milky glass. Like the dark counterpart,
        // unavailable actions use a subdued neutral absorption layer, with no bright rim/shadow.
        // Do not fade the whole View: its grey label must remain readable.
        val unavailableAction = action && disabled
        val darkPopup = backdrop != null && WeatherSettings.isDarkTheme(host.context)
        val top = if (darkPopup) Color.argb(205, 24, 35, 55)
            else if (unavailableAction) Color.argb(36, 127, 139, 155)
            else ContextCompat.getColor(host.context, R.color.detail_glass_tint_top)
        val bottom = if (darkPopup) Color.argb(215, 32, 45, 67)
            else if (unavailableAction) Color.argb(44, 127, 139, 155)
            else ContextCompat.getColor(host.context, R.color.detail_glass_tint_bottom)
        glass.setTintWithWhiteBase(0f, top, bottom)
        host.elevation = density * if (unavailableAction) 0f else 4.5f
        host.outlineProvider = ViewOutlineProvider.BACKGROUND
        if (Build.VERSION.SDK_INT >= 28) {
            host.outlineAmbientShadowColor = Color.argb(if (disabled) 20 else 45, 42, 49, 61)
            host.outlineSpotShadowColor = Color.argb(if (disabled) 8 else 18, 42, 49, 61)
        }
        invalidateSelf()
    }
    private fun requestCapture() {
        // Long help/settings lists must not blur panels outside the scrolling viewport.
        if (!host.isShown || !host.getGlobalVisibleRect(visibleRegion)) return
        if (pending || capturing) return
        pending = true
        val delay = (80L - (SystemClock.uptimeMillis() - lastCapture)).coerceAtLeast(0L)
        host.postDelayed({
            pending = false
            if (host.background !== this || !host.isAttachedToWindow || !host.isShown || host.width <= 0 || host.height <= 0 || !host.getGlobalVisibleRect(visibleRegion)) return@postDelayed
            capture()
        }, delay)
    }
    private fun capture() {
        val root = backdrop ?: host.rootView
        if (root === host) return
        if (!root.isAttachedToWindow || root.width <= 0 || root.height <= 0) return
        // A PopupWindow and its activity have different window origins. Screen coordinates
        // align the crop across both windows, including insets and constrained popup placement.
        host.getLocationOnScreen(location); root.getLocationOnScreen(rootLocation)
        val scale = .25f
        val pad = (32f * density).toInt()
        val sw=ceil((host.width + 2 * pad) * scale).toInt()
        val sh=ceil((host.height + 2 * pad) * scale).toInt()
        val bitmap=sample?.takeIf {it.width==sw && it.height==sh}
            ?: Bitmap.createBitmap(sw,sh,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
        canvas.scale(scale, scale)
        canvas.translate((pad - location[0] + rootLocation[0]).toFloat(), (pad - location[1] + rootLocation[1]).toFloat())
        val visibility = host.visibility
        capturing = true
        // External activity roots already exclude the popup. Never hide its labels or sample
        // the popup's own transparent root instead of the settings content underneath it.
        if (backdrop == null) host.visibility = View.INVISIBLE
        try { root.draw(canvas) } finally { host.visibility = visibility; capturing = false }
        lastRegion.set(location[0], location[1], location[0] + host.width, location[1] + host.height)
        dirty = false; lastCapture = SystemClock.uptimeMillis()
        sample = bitmap
        val region = Rect((pad * scale).toInt(), (pad * scale).toInt(),
            ((pad + host.width) * scale).toInt(), ((pad + host.height) * scale).toInt())
        if (backdrop != null && Build.VERSION.SDK_INT < 31) {
            // Small, downsampled menus also need actual blur on pre-RenderEffect devices.
            glass.blurInBackground(bitmap, region)
            glass.commitBlur()
        } else {
            glass.setLiveSource(bitmap, region)
        }
        // A previous size can still be referenced by a hardware display list; let GC
        // release it. Stable-size scrolling reuses the current sample instead of allocating.
        invalidateSelf()
    }
    override fun onBoundsChange(bounds: Rect) { glass.background.bounds = bounds }
    override fun draw(canvas: Canvas) { glass.background.draw(canvas) }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radius) }
    override fun setAlpha(alpha: Int) { glass.background.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { glass.background.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    companion object {
        fun install(host: View, radius: Float, backdrop: View? = null): DetailGlassSurface {
            (host.background as? DetailGlassSurface)?.takeIf { it.backdrop === backdrop }?.let { return it }
            val padding = intArrayOf(host.paddingLeft, host.paddingTop, host.paddingRight, host.paddingBottom)
            val material = DetailGlassSurface(host, radius, backdrop)
            host.background = material
            host.setPadding(padding[0], padding[1], padding[2], padding[3])
            host.invalidateOutline()
            return material
        }
    }
}
