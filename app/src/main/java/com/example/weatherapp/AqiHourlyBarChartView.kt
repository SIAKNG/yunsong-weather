package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/** 可拖动查看的未来 24 小时 AQI 柱状图；安全区以更醒目的绿色标注。 */
class AqiHourlyBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<AirQualityPoint> = emptyList()
    private var darkTheme = false
    private var selectedIndex = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    var onSelectionChanged: ((AirQualityPoint) -> Unit)? = null

    fun setData(values: List<AirQualityPoint>, isDark: Boolean) {
        points = values.sortedBy { it.timeMillis }.take(24)
        darkTheme = isDark
        selectedIndex = points.indices.minByOrNull { abs(points[it].timeMillis - System.currentTimeMillis()) } ?: 0
        invalidate()
        notifySelection()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        // View 本身已在页面统一左右内边距内，图形无需再次缩进。
        val left = 0f
        val right = width.toFloat()
        val top = dp(4f)
        val bottom = height - dp(30f)
        if (right <= left || bottom <= top) return
        val rawMax = points.mapNotNull { it.aqi }.maxOrNull() ?: 50.0
        val max = maxOf(150.0, ceil(rawMax / 50.0) * 50.0)
        fun yOf(value: Double): Float = bottom - (value.coerceIn(0.0, max) / max).toFloat() * (bottom - top)

        val grid = if (darkTheme) Color.argb(52, 255, 255, 255) else Color.argb(42, 20, 30, 40)
        val goodGreen = Color.rgb(62, 211, 98)
        val goodFill = if (darkTheme) Color.argb(64, 62, 211, 98) else Color.argb(48, 62, 211, 98)
        paint.style = Paint.Style.FILL
        paint.color = goodFill
        canvas.drawRect(left, yOf(50.0), right, bottom, paint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
        listOf(0.0, 50.0, 100.0, 150.0).filter { it <= max }.forEach { value ->
            val y = yOf(value)
            linePaint.color = if (value == 50.0) goodGreen else grid
            linePaint.strokeWidth = if (value == 50.0) dp(1.5f) else dp(1f)
            canvas.drawLine(left, y, right, y, linePaint)
        }

        val slot = (right - left) / points.size
        val bar = (slot * 0.70f).coerceAtLeast(dp(3f))
        points.forEachIndexed { index, point ->
            val value = (point.aqi ?: 0.0).coerceIn(0.0, max)
            val x = left + slot * index + (slot - bar) / 2f
            val y = yOf(value)
            paint.color = AirQualityScale.color(value)
            canvas.drawRoundRect(RectF(x, y, x + bar, bottom), dp(3.5f), dp(3.5f), paint)
            if (index % 6 == 0 || index == points.lastIndex) {
                labelPaint.color = if (darkTheme) Color.argb(185, 255, 255, 255) else Color.rgb(124, 130, 136)
                labelPaint.textSize = resources.displayMetrics.scaledDensity * 9f
                // 首尾标签贴合图形边界，避免时间字符串在窄屏上被裁切。
                labelPaint.textAlign = when (index) {
                    0 -> Paint.Align.LEFT
                    points.lastIndex -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                canvas.drawText(
                    timeFormat.format(Date(point.timeMillis)),
                    when (index) {
                        0 -> left
                        points.lastIndex -> right
                        else -> x + bar / 2f
                    },
                    height - dp(10f),
                    labelPaint
                )
                labelPaint.textAlign = Paint.Align.CENTER
            }
        }
        drawSelection(canvas, left, right, top, bottom, slot, bar, ::yOf)
    }

    private fun drawSelection(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        slot: Float,
        bar: Float,
        yOf: (Double) -> Float
    ) {
        val index = selectedIndex.coerceIn(points.indices)
        val point = points[index]
        val x = (left + slot * index + slot / 2f).coerceIn(left, right)
        val value = point.aqi ?: return
        val y = yOf(value)
        val color = AirQualityScale.color(value)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
        paint.color = if (darkTheme) Color.argb(150, 255, 255, 255) else Color.argb(110, 45, 55, 64)
        canvas.drawLine(x, top, x, bottom, paint)
        paint.pathEffect = null
        ChartSelectionMarker.draw(
            canvas = canvas,
            x = x,
            y = y,
            color = color,
            darkTheme = darkTheme,
            density = resources.displayMetrics.density
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateSelection(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateSelection(event.x)
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSelection(x: Float) {
        val left = 0f
        val right = width.toFloat()
        val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 0.9999f)
        val newIndex = (fraction * points.size).toInt().coerceIn(points.indices)
        if (newIndex != selectedIndex) {
            selectedIndex = newIndex
            invalidate()
            notifySelection()
        }
    }

    private fun notifySelection() {
        points.getOrNull(selectedIndex)?.let { onSelectionChanged?.invoke(it) }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
