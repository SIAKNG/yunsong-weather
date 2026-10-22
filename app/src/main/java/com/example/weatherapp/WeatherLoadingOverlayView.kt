package com.example.weatherapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * 首屏天气数据尚未可用时显示的轻量骨架。
 * 使用透明圆角轮廓与缓慢扫光，不遮挡背景，也避免启动阶段先绘制一套会马上被替换的文字。
 */
class WeatherLoadingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(92, 255, 255, 255)
    }
    private val softFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(17, 255, 255, 255)
    }
    private var sweep = 0f
    private var animator: ValueAnimator? = null
    private var targets: List<View> = emptyList()
    private val overlayLocation = IntArray(2)
    private val targetLocation = IntArray(2)
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        // 字号、屏幕方向、可选卡片或弹性空白改变时，下一帧直接重新取真实边界。
        invalidate()
    }
    /** 骨架完全淡出时通知页面启动常规渲染循环，避免冷启动时的时序竞态。 */
    var onLoadingFinished: (() -> Unit)? = null

    fun showLoading() {
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        startAnimation()
        post { invalidate() }
    }

    fun hideLoading() {
        if (visibility != VISIBLE) return
        animate().cancel()
        animate().alpha(0f).setDuration(150L).withEndAction {
            visibility = GONE
            alpha = 1f
            stopAnimation()
            onLoadingFinished?.invoke()
        }.start()
    }

    fun isLoading(): Boolean = visibility == VISIBLE

    /**
     * 使用真实控件的已测量位置绘制骨架，布局、字体缩放和可选卡片变化时都会同步，
     * 不再按屏幕比例猜测矩形位置。
     */
    fun setSkeletonTargets(views: Collection<View>) {
        targets.forEach { it.removeOnLayoutChangeListener(layoutListener) }
        targets = views.distinct()
        targets.forEach { it.addOnLayoutChangeListener(layoutListener) }
        post { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startAnimation()
    }

    override fun onDetachedFromWindow() {
        targets.forEach { it.removeOnLayoutChangeListener(layoutListener) }
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val shapes = measuredShapes()
        // 不再保留按百分比生成的备用矩形：控件尚未完成测量时宁可等待下一帧，
        // 也不显示尺寸或位置不正确的占位框。
        if (shapes.isEmpty()) return
        shapes.forEach { shape ->
            canvas.drawRoundRect(shape.bounds, shape.radius, shape.radius, softFill)
        }
        val sweepX = -w + (w * 3f) * sweep
        framePaint.shader = LinearGradient(
            sweepX - w * .35f, 0f, sweepX + w * .35f, 0f,
            intArrayOf(
                Color.argb(40, 255, 255, 255),
                Color.argb(150, 255, 255, 255),
                Color.argb(40, 255, 255, 255)
            ),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        shapes.forEach { shape ->
            canvas.drawRoundRect(shape.bounds, shape.radius, shape.radius, framePaint)
        }
        framePaint.shader = null
    }

    private data class SkeletonShape(val bounds: RectF, val radius: Float)

    private fun measuredShapes(): List<SkeletonShape> {
        if (targets.isEmpty()) return emptyList()
        getLocationInWindow(overlayLocation)
        return targets.asSequence()
            .filter { it.isLaidOut && it.width > 0 && it.height > 0 && it.visibility == VISIBLE }
            .mapNotNull { target ->
                target.getLocationInWindow(targetLocation)
                val left = (targetLocation[0] - overlayLocation[0]).toFloat()
                val top = (targetLocation[1] - overlayLocation[1]).toFloat()
                val right = left + target.width
                val bottom = top + target.height
                // 覆盖层只展示当前屏幕范围内的实际元素，避免下方长列表产生无意义矩形。
                if (bottom <= 0f || top >= height || right <= 0f || left >= width) null
                else {
                    val bounds = RectF(
                        left.coerceAtLeast(0f), top.coerceAtLeast(0f),
                        right.coerceAtMost(width.toFloat()), bottom.coerceAtMost(height.toFloat())
                    )
                    // 毛玻璃卡片沿用自身 16dp 圆角；文字行则按真实行高收圆，
                    // 不会出现标题/数字被过度圆角或边界被放大的情况。
                    val radius = if (target is BlurCardView) dp(16f) else min(dp(7f), bounds.height() / 2f)
                    SkeletonShape(bounds, radius)
                }
            }
            .toList()
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_250L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
