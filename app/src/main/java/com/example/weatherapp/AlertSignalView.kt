package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 国家标准气象灾害预警信号图标（自绘）。
 *
 * 参考中国气象局预警信号版式：整体圆角矩形 + 深灰粗边框；
 * 左上浅灰区放灾害图形（用等级色绘制），右上等级色区竖排灾害名称，
 * 左下等级色区放等级字（蓝/黄/橙/红/白），右下等级色区放英文名称。
 */
class AlertSignalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var type = "暴雨"
    private var level = "蓝色"

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun setAlert(type: String, level: String) {
        this.type = type
        this.level = level
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width.toFloat(), height.toFloat())
        if (s <= 0f) return

        val border = s * 0.045f
        val corner = s * 0.10f
        val topSplitX = s * 0.52f
        val horizontalY = s * 0.60f
        val bottomSplitX = s * 0.34f
        val outer = RectF(border, border, s - border, s - border)
        val levelColor = levelColor()

        // 背景：整体浅灰，右上与下半区为等级色
        fillPaint.color = Color.rgb(231, 231, 231)
        canvas.drawRoundRect(outer, corner, corner, fillPaint)
        fillPaint.color = levelColor
        canvas.drawRect(topSplitX, border, s - border, horizontalY, fillPaint)
        canvas.drawRect(border, horizontalY, s - border, s - border, fillPaint)

        // 左上：灾害图形（等级色）
        val glyphRect = RectF(border, border, topSplitX, horizontalY)
        canvas.save()
        canvas.clipRect(glyphRect)
        drawGlyph(canvas, glyphRect, levelColor, s)
        canvas.restore()

        // 右上：灾害名称竖排
        val typeRect = RectF(topSplitX, border, s - border, horizontalY)
        drawVerticalText(canvas, typeRect, type, s * 0.13f)

        // 左下：等级字
        val levelRect = RectF(border, horizontalY, bottomSplitX, s - border)
        drawCenteredText(canvas, levelRect, levelChar(), (s - border - horizontalY) * 0.42f)

        // 右下：英文名称
        val enRect = RectF(bottomSplitX, horizontalY, s - border, s - border)
        drawCenteredText(canvas, enRect, englishName(), fitEnglishSize(enRect))

        // 内部分割线与外边框
        strokePaint.color = Color.rgb(74, 74, 74)
        strokePaint.strokeWidth = s * 0.025f
        canvas.drawLine(topSplitX, border, topSplitX, horizontalY, strokePaint)
        canvas.drawLine(border, horizontalY, s - border, horizontalY, strokePaint)
        canvas.drawLine(bottomSplitX, horizontalY, bottomSplitX, s - border, strokePaint)
        strokePaint.strokeWidth = border * 1.6f
        canvas.drawRoundRect(outer, corner, corner, strokePaint)
    }

    private fun drawVerticalText(canvas: Canvas, rect: RectF, text: String, baseSize: Float) {
        val chars = text.toCharArray()
        if (chars.isEmpty()) return
        val h = rect.height()
        val size = (h / (chars.size + 0.6f)).coerceAtMost(baseSize * 2.2f)
        textPaint.textSize = size
        val fm = textPaint.fontMetrics
        val blockH = chars.size * size
        var y = rect.centerY() - blockH / 2f + size / 2f - (fm.ascent + fm.descent) / 2f
        for (ch in chars) {
            canvas.drawText(ch.toString(), rect.centerX(), y, textPaint)
            y += size
        }
    }

    private fun drawCenteredText(canvas: Canvas, rect: RectF, text: String, size: Float) {
        if (text.isBlank()) return
        textPaint.textSize = size
        val fm = textPaint.fontMetrics
        canvas.drawText(
            text, rect.centerX(),
            rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint
        )
    }

    private fun fitEnglishSize(rect: RectF): Float {
        val maxSize = rect.height() * 0.30f
        var size = maxSize
        textPaint.textSize = size
        while (size > rect.height() * 0.12f &&
            textPaint.measureText(englishName()) > rect.width() - rect.height() * 0.15f
        ) {
            size *= 0.92f
            textPaint.textSize = size
        }
        return size
    }

    private fun drawGlyph(canvas: Canvas, rect: RectF, color: Int, s: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val u = rect.width() * 0.28f
        fillPaint.color = color
        strokePaint.color = color
        strokePaint.strokeWidth = u * 0.30f
        when (type) {
            "台风", "沙尘暴", "春季沙尘", "沙尘" -> {
                strokePaint.style = Paint.Style.STROKE
                canvas.drawCircle(cx, cy - u * 0.15f, u * 0.78f, strokePaint)
                canvas.drawArc(
                    RectF(cx + u * 0.35f, cy - u * 0.95f, cx + u * 1.55f, cy + u * 0.25f),
                    -45f, 180f, false, strokePaint
                )
                fillPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy - u * 0.15f, u * 0.22f, fillPaint)
                strokePaint.style = Paint.Style.STROKE
            }
            "暴雨", "雷雨大风" -> {
                drawCloud(canvas, cx, cy - u * 0.45f, u, color)
                strokePaint.style = Paint.Style.STROKE
                val dy = if (type == "雷雨大风") u * 0.75f else u * 0.90f
                canvas.drawLine(cx - u * 0.45f, cy + u * 0.25f, cx - u * 0.65f, cy + dy, strokePaint)
                canvas.drawLine(cx, cy + u * 0.25f, cx, cy + u * 1.05f, strokePaint)
                canvas.drawLine(cx + u * 0.45f, cy + u * 0.25f, cx + u * 0.65f, cy + dy, strokePaint)
                if (type == "雷雨大风") {
                    val bolt = Path().apply {
                        moveTo(cx + u * 0.15f, cy - u * 1.25f)
                        lineTo(cx - u * 0.35f, cy - u * 0.35f)
                        lineTo(cx + u * 0.05f, cy - u * 0.35f)
                        lineTo(cx - u * 0.15f, cy + u * 0.15f)
                        lineTo(cx + u * 0.35f, cy - u * 0.75f)
                        lineTo(cx + u * 0.05f, cy - u * 0.75f)
                        close()
                    }
                    fillPaint.style = Paint.Style.FILL
                    canvas.drawPath(bolt, fillPaint)
                    strokePaint.style = Paint.Style.STROKE
                }
            }
            "暴雪", "寒潮", "霜冻", "道路结冰" -> {
                drawSnowflake(canvas, cx, cy, u, color)
            }
            "大风" -> drawWindLines(canvas, cx, cy, u, color)
            "高温", "干旱" -> {
                fillPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy - u * 0.15f, u * 0.62f, fillPaint)
                strokePaint.style = Paint.Style.STROKE
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val r1 = u * 0.82f
                    val r2 = u * 1.15f
                    canvas.drawLine(
                        cx + kotlin.math.cos(a).toFloat() * r1,
                        cy - u * 0.15f + kotlin.math.sin(a).toFloat() * r1,
                        cx + kotlin.math.cos(a).toFloat() * r2,
                        cy - u * 0.15f + kotlin.math.sin(a).toFloat() * r2,
                        strokePaint
                    )
                }
                if (type == "干旱") {
                    canvas.drawLine(cx - u * 1.0f, cy + u * 0.85f, cx + u * 1.0f, cy + u * 0.85f, strokePaint)
                }
            }
            "雷电", "强对流" -> {
                val bolt = Path().apply {
                    moveTo(cx + u * 0.20f, cy - u * 1.25f)
                    lineTo(cx - u * 0.55f, cy - u * 0.15f)
                    lineTo(cx - u * 0.05f, cy - u * 0.15f)
                    lineTo(cx - u * 0.25f, cy + u * 0.55f)
                    lineTo(cx + u * 0.55f, cy - u * 0.55f)
                    lineTo(cx + u * 0.10f, cy - u * 0.55f)
                    close()
                }
                fillPaint.style = Paint.Style.FILL
                canvas.drawPath(bolt, fillPaint)
                strokePaint.style = Paint.Style.STROKE
            }
            "山洪", "地质灾害", "山体滑坡", "泥石流", "内涝", "城市内涝" -> {
                val mountain = Path().apply {
                    moveTo(cx - u * 0.95f, cy + u * 0.80f)
                    lineTo(cx - u * 0.25f, cy - u * 0.55f)
                    lineTo(cx, cy + u * 0.05f)
                    lineTo(cx + u * 0.45f, cy - u * 0.25f)
                    lineTo(cx + u * 0.95f, cy + u * 0.80f)
                    close()
                }
                fillPaint.style = Paint.Style.FILL
                canvas.drawPath(mountain, fillPaint)
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = u * 0.18f
                for (i in 0 until 2) {
                    val y = cy + u * 0.95f + i * u * 0.28f
                    canvas.drawLine(cx - u * 0.80f, y, cx + u * 0.80f, y, strokePaint)
                }
                strokePaint.strokeWidth = u * 0.30f
            }
            "冰雹" -> {
                drawCloud(canvas, cx, cy - u * 0.55f, u, color)
                fillPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx - u * 0.40f, cy + u * 0.55f, u * 0.20f, fillPaint)
                canvas.drawCircle(cx, cy + u * 0.75f, u * 0.20f, fillPaint)
                canvas.drawCircle(cx + u * 0.40f, cy + u * 0.55f, u * 0.20f, fillPaint)
            }
            "大雾", "霾" -> {
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = u * 0.34f
                val yy = cy - u * 0.6f
                canvas.drawLine(cx - u, yy, cx + u, yy, strokePaint)
                canvas.drawLine(cx - u, yy + u * 0.6f, cx + u, yy + u * 0.6f, strokePaint)
                canvas.drawLine(cx - u, yy + u * 1.2f, cx + u, yy + u * 1.2f, strokePaint)
                strokePaint.strokeWidth = u * 0.30f
            }
            "森林火险" -> {
                val flame = Path().apply {
                    moveTo(cx, cy + u * 1.05f)
                    cubicTo(cx - u * 0.85f, cy + u * 0.35f, cx - u * 0.55f, cy - u * 0.95f, cx, cy - u * 1.25f)
                    cubicTo(cx + u * 0.55f, cy - u * 0.95f, cx + u * 0.85f, cy + u * 0.35f, cx, cy + u * 1.05f)
                    close()
                }
                fillPaint.style = Paint.Style.FILL
                canvas.drawPath(flame, fillPaint)
            }
            else -> {
                drawCloud(canvas, cx, cy - u * 0.45f, u, color)
                strokePaint.style = Paint.Style.STROKE
                canvas.drawLine(cx - u * 0.45f, cy + u * 0.25f, cx - u * 0.65f, cy + u * 0.90f, strokePaint)
                canvas.drawLine(cx, cy + u * 0.25f, cx, cy + u * 1.05f, strokePaint)
                canvas.drawLine(cx + u * 0.45f, cy + u * 0.25f, cx + u * 0.65f, cy + u * 0.90f, strokePaint)
            }
        }
    }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, u: Float, color: Int) {
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = color
        canvas.drawCircle(cx - u * 0.30f, cy + u * 0.05f, u * 0.34f, fillPaint)
        canvas.drawCircle(cx + u * 0.30f, cy + u * 0.05f, u * 0.34f, fillPaint)
        canvas.drawCircle(cx, cy - u * 0.20f, u * 0.42f, fillPaint)
        canvas.drawRoundRect(
            RectF(cx - u * 0.62f, cy, cx + u * 0.62f, cy + u * 0.42f),
            u * 0.2f, u * 0.2f, fillPaint
        )
    }

    private fun drawSnowflake(canvas: Canvas, cx: Float, cy: Float, u: Float, color: Int) {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = color
        strokePaint.strokeWidth = u * 0.24f
        for (i in 0 until 3) {
            val a = Math.toRadians(i * 60.0)
            val dx = kotlin.math.cos(a).toFloat()
            val dy = kotlin.math.sin(a).toFloat()
            canvas.drawLine(cx - dx * u, cy - dy * u, cx + dx * u, cy + dy * u, strokePaint)
        }
    }

    private fun drawWindLines(canvas: Canvas, cx: Float, cy: Float, u: Float, color: Int) {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = color
        strokePaint.strokeWidth = u * 0.26f
        val y0 = cy - u * 0.75f
        for (i in 0 until 3) {
            val y = y0 + i * u * 0.75f
            val p = Path().apply {
                moveTo(cx - u * 1.05f, y)
                cubicTo(cx - u * 0.45f, y - u * 0.28f, cx + u * 0.05f, y + u * 0.28f, cx + u * 0.75f, y)
                lineTo(cx + u * 0.95f, y - u * 0.28f)
            }
            canvas.drawPath(p, strokePaint)
        }
    }

    private fun levelChar(): String = when (level) {
        "红色" -> "红"
        "橙色" -> "橙"
        "黄色" -> "黄"
        "蓝色" -> "蓝"
        "白色" -> "白"
        else -> "预"
    }

    private fun englishName(): String = when (type) {
        "台风" -> "TYPHOON"
        "暴雨" -> "RAIN STORM"
        "暴雪" -> "SNOW STORM"
        "寒潮" -> "COLD WAVE"
        "大风" -> "GALE"
        "沙尘暴" -> "SAND STORM"
        "高温" -> "HEAT WAVE"
        "干旱" -> "DROUGHT"
        "雷电" -> "LIGHTNING"
        "冰雹" -> "HAIL"
        "霜冻" -> "FROST"
        "大雾" -> "HEAVY FOG"
        "霾" -> "HAZE"
        "道路结冰" -> "ICY ROAD"
        "森林火险" -> "FIRE RISK"
        "雷雨大风" -> "THUNDER GALE"
        "强对流" -> "CONVECTION"
        "山洪" -> "FLASH FLOOD"
        "地质灾害", "山体滑坡", "泥石流" -> "GEOLOGICAL"
        "内涝", "城市内涝" -> "WATERLOGGING"
        "春季沙尘", "沙尘" -> "SAND"
        else -> "WEATHER ALERT"
    }

    private fun levelColor(): Int = when (level) {
        "红色" -> Color.rgb(217, 83, 79)
        "橙色" -> Color.rgb(255, 149, 0)
        "黄色" -> Color.rgb(240, 173, 78)
        "蓝色" -> Color.rgb(90, 180, 220)
        else -> Color.rgb(148, 148, 148)
    }
}
