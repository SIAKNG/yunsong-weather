package com.example.weatherapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

// 下拉刷新云朵线条动画：
// 下拉时轮廓随进度逐步画出；达到阈值后保留一个小缺口；刷新中缺口沿轮廓滚动；
// 成功后轮廓闭合并在云朵内画对钩。
class RefreshCloudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    // 中心轴对称云朵轮廓（24x24 视口，对称轴 x=12），
    // 让运动的轮廓线条左右对称、更统一
    private val cloudPath = Path().apply {
        moveTo(12f, 4f)
        cubicTo(9.6f, 4f, 7.6f, 5.7f, 6.7f, 8.1f)
        cubicTo(3.7f, 8.5f, 1.5f, 11.2f, 1.5f, 14.3f)
        cubicTo(1.5f, 17.4f, 4.1f, 20f, 7.2f, 20f)
        lineTo(16.8f, 20f)
        cubicTo(19.9f, 20f, 22.5f, 17.4f, 22.5f, 14.3f)
        cubicTo(22.5f, 11.2f, 20.3f, 8.5f, 17.3f, 8.1f)
        cubicTo(16.4f, 5.7f, 14.4f, 4f, 12f, 4f)
        close()
    }

    private val matrix = Matrix()
    private val drawCloudPath = Path()
    private val checkPath = Path()
    private val measure = PathMeasure()
    private var totalLen = 0f

    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // 下拉绘制时先垫一层淡淡的完整轮廓：任何状态下都是同一朵云，
    // 只是亮色线条在轮廓上逐渐画出/缺口滚动/闭合打勾
    private val faintCloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var pullProgress = 0f
    private var refreshing = false
    private var showCheck = false
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = min(w / 24f, h / 24f)
        val tx = (w - 24f * scale) / 2f
        val ty = (h - 24f * scale) / 2f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
        drawCloudPath.reset()
        drawCloudPath.addPath(cloudPath)
        drawCloudPath.transform(matrix)
        measure.setPath(drawCloudPath, false)
        totalLen = measure.length

        checkPath.reset()
        // 对钩整体居中于对称云朵，并相对旧位置轻微向左上移动
        checkPath.moveTo(8.8f, 13.6f)
        checkPath.lineTo(11.1f, 16.0f)
        checkPath.lineTo(15.9f, 10.6f)
        checkPath.transform(matrix)
    }

    // 下拉中：0..1 逐步画出轮廓（末尾保留小缺口）
    fun setPullProgress(p: Float) {
        stopAnimator()
        refreshing = false
        showCheck = false
        pullProgress = p.coerceIn(0f, 1f)
        invalidate()
    }

    // 已到阈值：轮廓完整画好，只保留一个小缺口
    fun setReady() {
        stopAnimator()
        refreshing = false
        showCheck = false
        pullProgress = 1f
        invalidate()
    }

    // 刷新中：缺口沿轮廓滚动
    fun setRefreshing(on: Boolean) {
        refreshing = on
        showCheck = false
        if (on) {
            if (!animator.isStarted) animator.start()
        } else {
            stopAnimator()
        }
        invalidate()
    }

    // 完成：轮廓闭合，成功时云朵内画对钩
    fun setSuccess(ok: Boolean) {
        stopAnimator()
        refreshing = false
        showCheck = ok
        pullProgress = 1f
        invalidate()
    }

    private fun stopAnimator() {
        animator.cancel()
        phase = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totalLen <= 0f) return
        val gap = totalLen * 0.08f
        val dst = Path()
        when {
            showCheck -> {
                measure.getSegment(0f, totalLen, dst, true)
            }
            refreshing -> {
                val c = phase * totalLen
                val a = c - gap / 2f
                val b = c + gap / 2f
                if (a > 0f) measure.getSegment(0f, a, dst, true)
                if (b < totalLen) measure.getSegment(b, totalLen, dst, true)
            }
            else -> {
                // 下拉绘制：同一朵云的完整轮廓（淡色）垫底，
                // 亮色线条沿轮廓画到 progress 处，末尾留缺口
                canvas.drawPath(drawCloudPath, faintCloudPaint)
                val end = (pullProgress * totalLen - gap).coerceAtLeast(0f)
                if (end > 0f) measure.getSegment(0f, end, dst, true)
            }
        }
        canvas.drawPath(dst, cloudPaint)
        if (showCheck) {
            canvas.drawPath(checkPath, cloudPaint)
        }
    }
}
