package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 全天太阳高度图：水平线为地平线，拖动可选择任意时刻。 */
class SunAltitudeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onSelectionChanged: ((Long, Double) -> Unit)? = null
    private var samples: List<Pair<Long, Double>> = emptyList()
    private var selected = 0
    private var dark = false
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun setData(values: List<Pair<Long, Double>>, darkTheme: Boolean) {
        samples = values
        dark = darkTheme
        selected = samples.indices.minByOrNull { abs(samples[it].first - System.currentTimeMillis()) } ?: 0
        invalidate(); notifySelection()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) return
        val left = dp(2f); val right = width - dp(2f); val top = dp(10f); val bottom = height - dp(26f)
        val minValue = min(-20.0, samples.minOf { it.second })
        val maxValue = max(50.0, samples.maxOf { it.second })
        fun x(index: Int) = left + (right - left) * index / (samples.lastIndex.toFloat())
        fun y(value: Double) = bottom - ((value - minValue) / (maxValue - minValue)).toFloat() * (bottom - top)
        val horizon = y(0.0)
        grid.color = if (dark) Color.argb(115, 255, 255, 255) else Color.argb(85, 38, 50, 65)
        grid.strokeWidth = dp(1f)
        canvas.drawLine(left, horizon, right, horizon, grid)
        val curve = Path().apply { moveTo(x(0), y(samples[0].second)); for (i in 1 until samples.size) lineTo(x(i), y(samples[i].second)) }
        val sun = Color.rgb(255, 188, 54)
        val sky = Color.rgb(72, 164, 255)

        // 太阳高度曲线的色彩严格由下至上分布：夜晚深蓝→浅蓝，跨越地平线后黄→橙红。
        // 因此无论滑到日出还是日落，曲线颜色都能明确表达所处时段。
        val horizonFraction = ((bottom - horizon) / (bottom - top)).coerceIn(0.05f, 0.88f)
        val deepNight = Color.rgb(10, 35, 91)
        val lightNight = Color.rgb(110, 183, 255)
        val sunYellow = Color.rgb(255, 211, 71)
        val sunOrange = Color.rgb(255, 105, 67)

        // 图表不再对日落后的区域铺蓝色填充，只保留曲线自身的夜间颜色；
        // 白天的低饱和暖色面积用于读取高度趋势，且不会与夜间区域发生视觉竞争。
        val daylight = Path()
        var startedDaylight = false
        var lastDaylightX = left
        samples.forEachIndexed { index, sample ->
            if (sample.second >= 0.0) {
                val px = x(index)
                if (!startedDaylight) {
                    daylight.moveTo(px, horizon)
                    startedDaylight = true
                }
                daylight.lineTo(px, y(sample.second))
                lastDaylightX = px
            } else if (startedDaylight) {
                daylight.lineTo(lastDaylightX, horizon)
                daylight.close()
                startedDaylight = false
            }
        }
        if (startedDaylight) {
            daylight.lineTo(lastDaylightX, horizon)
            daylight.close()
        }
        fill.shader = LinearGradient(
            0f, top, 0f, horizon,
            intArrayOf(Color.argb(112, sunOrange.red(), sunOrange.green(), sunOrange.blue()), Color.argb(65, sunYellow.red(), sunYellow.green(), sunYellow.blue()), Color.argb(8, sky.red(), sky.green(), sky.blue())),
            floatArrayOf(0f, .55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(daylight, fill)
        fill.shader = null
        line.shader = LinearGradient(
            0f, bottom, 0f, top,
            intArrayOf(deepNight, lightNight, lightNight, sunYellow, sunOrange),
            floatArrayOf(0f, horizonFraction * .72f, horizonFraction, (horizonFraction + .10f).coerceAtMost(.94f), 1f),
            Shader.TileMode.CLAMP
        )
        line.strokeWidth = dp(3.5f); canvas.drawPath(curve, line); line.shader = null
        val index = selected.coerceIn(samples.indices); val px = x(index); val py = y(samples[index].second)
        grid.color = if (dark) Color.argb(170, 255, 255, 255) else Color.argb(130, 24, 34, 44)
        canvas.drawLine(px, top, px, bottom, grid)
        ChartSelectionMarker.draw(
            canvas = canvas,
            x = px,
            y = py,
            color = curveColor(samples[index].second),
            darkTheme = dark,
            density = resources.displayMetrics.density
        )
        text.color = if (dark) Color.argb(190, 255, 255, 255) else Color.rgb(112, 119, 128); text.textSize = sp(11f)
        listOf(0, samples.lastIndex / 4, samples.lastIndex / 2, samples.lastIndex * 3 / 4, samples.lastIndex).distinct().forEach { i ->
            val c = java.util.Calendar.getInstance().apply { timeInMillis = samples[i].first }
            canvas.drawText(String.format(java.util.Locale.US, "%02d:00", c.get(java.util.Calendar.HOUR_OF_DAY)), x(i).coerceIn(dp(16f), right - dp(16f)), height - dp(7f), text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP)
                val fraction = (event.x / width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                selected = (fraction * samples.lastIndex).toInt().coerceIn(samples.indices)
                invalidate(); notifySelection()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun notifySelection() { samples.getOrNull(selected)?.let { onSelectionChanged?.invoke(it.first, it.second) } }
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    private fun Int.red() = Color.red(this)
    private fun Int.green() = Color.green(this)
    private fun Int.blue() = Color.blue(this)
    private fun curveColor(elevation: Double): Int {
        fun blend(start: Int, end: Int, progress: Float): Int = Color.rgb(
            (Color.red(start) + (Color.red(end) - Color.red(start)) * progress).toInt(),
            (Color.green(start) + (Color.green(end) - Color.green(start)) * progress).toInt(),
            (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * progress).toInt()
        )
        return if (elevation < 0.0) {
            blend(Color.rgb(10, 35, 91), Color.rgb(110, 183, 255), ((elevation + 20.0) / 20.0).toFloat().coerceIn(0f, 1f))
        } else {
            blend(Color.rgb(255, 211, 71), Color.rgb(255, 105, 67), (elevation / 60.0).toFloat().coerceIn(0f, 1f))
        }
    }
}
