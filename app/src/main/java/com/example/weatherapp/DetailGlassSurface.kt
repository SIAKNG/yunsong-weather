package com.example.weatherapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import kotlin.math.ceil
import androidx.core.content.ContextCompat

internal enum class PopupGlassMaterial { MENU, CHART_MENU, DIALOG, FREE_NOTICE }

/** Blur the real backdrop, never foreground labels. Popups explicitly supply their activity root. */
internal class DetailGlassSurface private constructor(
    private val host: View,
    private val radius: Float,
    private val backdrop: View? = null,
    sampleBackdrop: Boolean = true,
    private val popupMaterial: PopupGlassMaterial = PopupGlassMaterial.MENU,
    private val lightTint: Pair<Int, Int>? = null
) : Drawable() {
    private val density = host.resources.displayMetrics.density
    private val glass = BlurCardView(host.context).apply {
        setFrostedMaterial(true); setCornerRadius(radius)
        setBlurRadius((if (backdrop != null) 34f else 26f) * density)
        setStrokeVisible(false)
    }
    private val ownerWindow = backdrop?.let { source ->
        sequenceOf(host.context, source.context)
            .mapNotNull { findActivity(it)?.window }
            .firstOrNull { it.decorView === source.rootView }
    }
    private var hasPopupSample = false
    private var sample: Bitmap? = null
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val lastRegion = Rect()
    private val visibleRegion = Rect()
    private val windowLocation = IntArray(2)
    private val backdropPreDraw = ViewTreeObserver.OnPreDrawListener {
        if (SystemClock.uptimeMillis() - lastCapture >= POPUP_REFRESH_MS) {
            dirty = true
            requestCapture()
        }
        true
    }
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
        if (sampleBackdrop) trackBackdrop()
    }

    private fun trackBackdrop() {
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { observe(); dirty = true; requestCapture() }
            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(preDraw); it.removeOnScrollChangedListener(scroll) }
                removeBackdropObserver()
                observer = null
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
        removeBackdropObserver()
        backdropObserver = backdrop?.viewTreeObserver?.also {
            if (it !== next) {
                it.addOnScrollChangedListener(scroll)
                it.addOnPreDrawListener(backdropPreDraw)
            }
        }
    }
    private fun removeBackdropObserver() {
        backdropObserver?.takeIf { it.isAlive && it !== observer }?.let {
            it.removeOnScrollChangedListener(scroll)
            it.removeOnPreDrawListener(backdropPreDraw)
        }
        backdropObserver = null
    }

    fun setStateTint(action: Boolean, disabled: Boolean) {
        // Available actions share the panel's milky glass. Like the dark counterpart,
        // unavailable actions use a subdued neutral absorption layer, with no bright rim/shadow.
        // Do not fade the whole View: its grey label must remain readable.
        val unavailableAction = action && disabled
        if (backdrop != null) {
            setPopupTint()
        } else {
            val top = if (unavailableAction) Color.argb(36, 127, 139, 155)
                else lightTint?.first ?: ContextCompat.getColor(host.context, R.color.detail_glass_tint_top)
            val bottom = if (unavailableAction) Color.argb(44, 127, 139, 155)
                else lightTint?.second ?: ContextCompat.getColor(host.context, R.color.detail_glass_tint_bottom)
            glass.setTintWithWhiteBase(0f, top, bottom)
        }
        host.elevation = density * if (unavailableAction) 0f else 4.5f
        host.outlineProvider = ViewOutlineProvider.BACKGROUND
        if (Build.VERSION.SDK_INT >= 28) {
            host.outlineAmbientShadowColor = Color.argb(if (disabled) 20 else 45, 42, 49, 61)
            host.outlineSpotShadowColor = Color.argb(if (disabled) 8 else 18, 42, 49, 61)
        }
        invalidateSelf()
    }
    private fun setPopupTint() {
        val dark = WeatherSettings.isDarkTheme(host.context)
        val opacity = if (!hasPopupSample) 246 else when (popupMaterial) {
            PopupGlassMaterial.MENU -> if (dark) 110 else 52
            PopupGlassMaterial.CHART_MENU -> if (dark) 135 else 67
            PopupGlassMaterial.DIALOG -> if (dark) 132 else 80
            PopupGlassMaterial.FREE_NOTICE -> if (dark) 110 else 64
        }
        val chart = popupMaterial == PopupGlassMaterial.CHART_MENU
        val top = when {
            dark && chart -> Color.argb(opacity, 45, 63, 94)
            dark -> Color.argb(opacity, 15, 23, 39)
            chart -> Color.argb(opacity, 227, 237, 254)
            else -> Color.argb(opacity, 255, 255, 255)
        }
        val bottom = when {
            dark && chart -> Color.argb(opacity, 35, 51, 81)
            dark -> Color.argb(opacity, 23, 33, 52)
            chart -> Color.argb(opacity, 198, 216, 246)
            else -> Color.argb(opacity, 235, 243, 254)
        }
        glass.setTintWithWhiteBase(0f, top, bottom, independentOpacity = true)
    }

    private fun requestCapture() {
        // Long help/settings lists must not blur panels outside the scrolling viewport.
        if (!host.isShown || !host.getGlobalVisibleRect(visibleRegion)) return
        if (pending || capturing) return
        pending = true
        val interval = if (backdrop == null) 80L else POPUP_REFRESH_MS
        val delay = (interval - (SystemClock.uptimeMillis() - lastCapture)).coerceAtLeast(0L)
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
        val area = Rect(location[0], location[1], location[0] + host.width, location[1] + host.height)
        val window = ownerWindow
        val secure = window != null && window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (backdrop != null && window != null && !secure && Build.VERSION.SDK_INT >= 26 &&
            captureRenderedWindow(window, root, area)
        ) return
        captureWithView(root, area, maskInputs = backdrop != null && (window == null || secure))
    }

    private fun captureRenderedWindow(window: android.view.Window, root: View, area: Rect): Boolean {
        root.getLocationInWindow(windowLocation)
        val src = Rect(area).apply {
            offset(windowLocation[0] - rootLocation[0], windowLocation[1] - rootLocation[1])
        }
        if (!Rect(windowLocation[0], windowLocation[1], windowLocation[0] + root.width,
                windowLocation[1] + root.height).contains(src)
        ) return false
        check(Build.VERSION.SDK_INT >= 26)
        val bitmap = Bitmap.createBitmap(
            (area.width() * SAMPLE_SCALE).toInt().coerceAtLeast(1),
            (area.height() * SAMPLE_SCALE).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        capturing = true
        return try {
            PixelCopy.request(window, src, bitmap, { result ->
                capturing = false
                if (!host.isAttachedToWindow || host.background !== this) return@request
                host.getLocationOnScreen(location)
                if (area.left != location[0] || area.top != location[1] ||
                    area.width() != host.width || area.height() != host.height
                ) {
                    dirty = true
                    requestCapture()
                } else if (result == PixelCopy.SUCCESS) {
                    bindSample(bitmap, Rect(0, 0, bitmap.width, bitmap.height), area)
                } else {
                    captureWithView(root, area, maskInputs = false)
                }
            }, Handler(Looper.getMainLooper()))
            true
        } catch (_: IllegalArgumentException) {
            capturing = false
            false
        }
    }

    private fun captureWithView(root: View, area: Rect, maskInputs: Boolean) {
        val scale = SAMPLE_SCALE
        val pad = (32f * density).toInt()
        val sw=ceil((host.width + 2 * pad) * scale).toInt()
        val sh=ceil((host.height + 2 * pad) * scale).toInt()
        val bitmap=sample?.takeIf {it.width==sw && it.height==sh}
            ?: Bitmap.createBitmap(sw,sh,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
        canvas.scale(scale, scale)
        canvas.translate((pad - area.left + rootLocation[0]).toFloat(), (pad - area.top + rootLocation[1]).toFloat())
        val visibility = host.visibility
        capturing = true
        // External activity roots already exclude the popup. Never hide its labels or sample
        // the popup's own transparent root instead of the settings content underneath it.
        if (backdrop == null) host.visibility = View.INVISIBLE
        try {
            root.draw(canvas)
            if (maskInputs) maskSecureInputs(root, canvas)
        } finally {
            host.visibility = visibility
            capturing = false
        }
        sample = bitmap
        val region = Rect((pad * scale).toInt(), (pad * scale).toInt(),
            ((pad + area.width()) * scale).toInt(), ((pad + area.height()) * scale).toInt())
        bindSample(bitmap, region, area)
    }

    private fun maskSecureInputs(root: View, canvas: Canvas) {
        val paint = android.graphics.Paint().apply {
            color = ContextCompat.getColor(host.context, R.color.page_bg)
        }
        val position = IntArray(2)
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is EditText) {
                view.getLocationOnScreen(position)
                val x = (position[0] - rootLocation[0]).toFloat()
                val y = (position[1] - rootLocation[1]).toFloat()
                canvas.drawRect(x, y, x + view.width, y + view.height, paint)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }

    private fun bindSample(bitmap: Bitmap, region: Rect, area: Rect) {
        lastRegion.set(area)
        dirty = false
        lastCapture = SystemClock.uptimeMillis()
        if (backdrop != null && !hasPopupSample) {
            hasPopupSample = true
            setPopupTint()
        }
        if (backdrop != null && Build.VERSION.SDK_INT < 31) {
            glass.blurInBackground(bitmap, region)
            glass.commitBlur()
        } else {
            glass.setLiveSource(bitmap, region)
        }
        invalidateSelf()
    }
    override fun onBoundsChange(bounds: Rect) { glass.background.bounds = bounds }
    override fun draw(canvas: Canvas) { glass.background.draw(canvas) }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radius) }
    override fun setAlpha(alpha: Int) { glass.background.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { glass.background.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    companion object {
        private const val SAMPLE_SCALE = .25f
        private const val POPUP_REFRESH_MS = 165L

        private fun findActivity(context: Context?): Activity? {
            var current = context
            while (current is ContextWrapper) {
                if (current is Activity) return current
                val base = current.baseContext
                if (base === current) return null
                current = base
            }
            return null
        }

        fun install(host: View, radius: Float, backdrop: View? = null, sampleBackdrop: Boolean = true,
                    popupMaterial: PopupGlassMaterial = PopupGlassMaterial.MENU,
                    lightTint: Pair<Int, Int>? = null): DetailGlassSurface {
            (host.background as? DetailGlassSurface)?.takeIf { it.backdrop === backdrop && it.popupMaterial == popupMaterial && it.lightTint == lightTint }?.let { return it }
            val padding = intArrayOf(host.paddingLeft, host.paddingTop, host.paddingRight, host.paddingBottom)
            val material = DetailGlassSurface(host, radius, backdrop, sampleBackdrop, popupMaterial, lightTint)
            host.background = material
            host.setPadding(padding[0], padding[1], padding[2], padding[3])
            host.invalidateOutline()
            return material
        }
    }
}
