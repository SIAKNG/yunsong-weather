package com.example.weatherapp

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.exp

internal object WeatherScrollPhysics {
    fun resistance(distance: Float, limit: Float): Float =
        if (limit <= 0f) 0f else distance * .56f / (1f + abs(distance) * .56f / limit)

    val settle = TimeInterpolator { time ->
        val t = time * 7f
        val tail = 8f * exp(-7f)
        ((1f - (1f + t) * exp(-t)) / (1f - tail)).coerceIn(0f, 1f)
    }
}

/** Native scrolling is unchanged; only motion left over at an edge moves the content. */
open class ElasticWeatherScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    var springAtTop = true
    private var touching = false
    private var ambiguousTouch = false
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var pull = 0f
    private var returnMotion: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val limit = 56f * resources.displayMetrics.density

    init { overScrollMode = OVER_SCROLL_NEVER }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                resetEdge()
                touching = true
                ambiguousTouch = false
                downX = event.rawX
                downY = event.rawY
                lastY = downY
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                ambiguousTouch = true
                settleEdge()
            }
        }
        val oldScroll = scrollY
        val handled = super.dispatchTouchEvent(event)
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                val y = event.rawY
                if (!ambiguousTouch && abs(y - downY) > slop && abs(y - downY) > abs(event.rawX - downX)) {
                    val remaining = y - lastY + (scrollY - oldScroll)
                    dragEdge(remaining)
                }
                lastY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                settleEdge()
            }
        }
        return handled
    }

    private fun dragEdge(delta: Float) {
        val towardTop = (pull > 0f || delta > 0f) && !canScrollVertically(-1) && springAtTop
        val towardBottom = (pull < 0f || delta < 0f) && !canScrollVertically(1)
        pull = when {
            towardTop && canScrollVertically(1) -> (pull + delta).coerceAtLeast(0f)
            towardBottom && canScrollVertically(-1) -> (pull + delta).coerceAtMost(0f)
            else -> 0f
        }
        getChildAt(0)?.translationY = WeatherScrollPhysics.resistance(pull, limit)
    }

    override fun onOverScrolled(x: Int, y: Int, clampedX: Boolean, clampedY: Boolean) {
        val previous = scrollY
        super.onOverScrolled(x, y, clampedX, clampedY)
        if (!clampedY || touching || returnMotion?.isRunning == true || y == previous) return
        val target = getChildAt(0) ?: return
        val hitTop = y == 0 && springAtTop && canScrollVertically(1)
        val hitBottom = y > 0 && !canScrollVertically(1)
        if (hitTop || hitBottom) {
            target.translationY = ((previous - y) * .40f).coerceIn(-limit * .38f, limit * .38f)
            settleEdge()
        }
    }

    private fun settleEdge() {
        val content = getChildAt(0) ?: return
        val offset = content.translationY
        pull = 0f
        if (returnMotion?.isRunning == true) return
        if (abs(offset) < .5f) {
            content.translationY = 0f
            return
        }
        returnMotion = ValueAnimator.ofFloat(offset, 0f).apply {
            duration = (285f + abs(offset) / limit * 130f).toLong()
            interpolator = WeatherScrollPhysics.settle
            addUpdateListener { content.translationY = it.animatedValue as Float }
            start()
        }
    }

    private fun resetEdge() {
        returnMotion?.cancel()
        returnMotion = null
        pull = 0f
        getChildAt(0)?.translationY = 0f
    }

    override fun onDetachedFromWindow() {
        resetEdge()
        super.onDetachedFromWindow()
    }
}
