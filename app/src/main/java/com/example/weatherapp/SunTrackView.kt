package com.example.weatherapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

// 日出日落时间轴：
// 白天 = 日出→日落（太阳图标）；夜间 = 日落→次日日出（月亮图标，顺序对调）。
// 已走过部分半透明、未走过部分白色，图标位置线条截断隐藏，与白天效果一致。
class SunTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var intervalStart = 0L
    private var intervalEnd = 0L
    private var isDay = true
    private var nowMillis = 0L
    private var targetProgress = 0f
    private var shownProgress = 0f
    private var lastEntryGeneration = Long.MIN_VALUE
    private var entryAnimator: ValueAnimator? = null

    // 未走过部分：白色
    private val remainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    // 已走过部分：半透明白色
    private val elapsedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // tianqitubiao 素材：太阳/月亮，保留素材原始颜色（不再统一染白）
    private var sunBmp: Bitmap? = null
    private var moonBmp: Bitmap? = null
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // 传入当前展示的时间段（白天=日出→日落；夜晚=日落→日出）与昼夜标记
    fun setInterval(start: Long, end: Long, day: Boolean, now: Long) {
        intervalStart = start
        intervalEnd = end
        isDay = day
        nowMillis = now
        targetProgress = intervalProgress(start, end, now)
        if (entryAnimator?.isRunning != true) shownProgress = targetProgress
        invalidate()
    }

    /** 当前城市页进入时，让日/月图标从轨道左端沿非线性曲线移动到真实时刻。 */
    fun playEntryAnimation(generation: Long): Boolean {
        if (intervalStart <= 0L || intervalEnd <= intervalStart || lastEntryGeneration == generation) {
            return false
        }
        lastEntryGeneration = generation
        entryAnimator?.cancel()
        shownProgress = 0f
        entryAnimator = ValueAnimator.ofFloat(0f, targetProgress).apply {
            // 速度降为此前的 50%，让日/月沿轨道的移动更容易被辨识。
            duration = 1440L
            interpolator = PathInterpolator(0.18f, 0.76f, 0.22f, 1f)
            addUpdateListener {
                shownProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val cy = h / 2f
        val stroke = dp(4f)
        remainPaint.strokeWidth = stroke
        elapsedPaint.strokeWidth = stroke
        // 圆角端帽会向两端各外扩 stroke/2：两端内缩半个线宽，
        // 避免圆头被视图边缘裁成直角（此前只留 1dp，圆头被切掉）
        val left = stroke / 2f
        val right = w - stroke / 2f
        if (right <= left) return

        // 轨道缺口和日/月图标均由 shownProgress 驱动；动画期间缺口不会留在真实终点。
        val mx = left + (right - left) * shownProgress.coerceIn(0f, 1f)

        // 图标位置的线条截断隐藏，不与图标重合（白天/夜间一致）
        val gap = dp(16f)
        if (mx - gap > left) canvas.drawLine(left, cy, mx - gap, cy, elapsedPaint)
        if (mx + gap < right) canvas.drawLine(mx + gap, cy, right, cy, remainPaint)

        val bmp = if (isDay) sunIcon() else moonIcon()
        bmp?.let {
            val iconSize = dp(26f)
            canvas.drawBitmap(it, mx - iconSize / 2f, cy - iconSize / 2f, iconPaint)
        }
    }

    override fun onDetachedFromWindow() {
        entryAnimator?.cancel()
        entryAnimator = null
        super.onDetachedFromWindow()
    }

    private fun intervalProgress(start: Long, end: Long, now: Long): Float {
        var safeEnd = end
        if (safeEnd <= start) safeEnd = start + 24L * 3600000L
        val span = safeEnd - start
        return if (span > 0L) {
            ((now - start).toFloat() / span).coerceIn(0f, 1f)
        } else {
            0.5f
        }
    }

    private fun sunIcon(): Bitmap? {
        if (sunBmp == null) sunBmp = loadAsset("tq-1.svg")
        return sunBmp
    }

    private fun moonIcon(): Bitmap? {
        if (moonBmp == null) moonBmp = loadAsset("yewan.svg")
        return moonBmp
    }

    private fun loadAsset(name: String): Bitmap? {
        val px = dp(26f).toInt().coerceAtLeast(1)
        return WeatherSvgRasterCache.load(context, name, px)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
