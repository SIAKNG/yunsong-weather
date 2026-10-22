package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// 风力罗盘：360° 刻度圈（上下左右四点空置放北南东西），
// 圈内按风向贯穿一条箭头（尾端圆点 = 风的来向），中央显示风级。
class WindCompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var dirDeg: Double? = null

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    fun setData(dirDeg: Double?) {
        this.dirDeg = dirDeg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        // 刻度环外圈即罗盘区域的真实边界：只留 2dp 安全边距，整体放大
        val r = min(w, h) / 2f - dp(2f)
        if (r <= 0f) return

        // 360° 刻度线：密度降低（48 格），四个方向文字直接压在刻度环上，
        // 文字下方的刻度线隐藏（按文字覆盖的圆心角跳过）
        val tickOuter = r - dp(1f)
        val tickInner = r - dp(9f)
        // 文字中心落在刻度环带上，整体向中心微移一点
        val labelR = r - dp(4f)
        val fm = labelPaint.fontMetrics
        fun textHalfAngle(text: String): Float {
            val half = labelPaint.measureText(text) / 2f
            if (half <= 0f || labelR <= 0f) return 0f
            return Math.toDegrees(atan2(half.toDouble(), labelR.toDouble())).toFloat() + 2.5f
        }
        val northHalf = textHalfAngle("北")
        val eastHalf = textHalfAngle("东")
        val southHalf = textHalfAngle("南")
        val westHalf = textHalfAngle("西")
        fun nearCardinal(deg: Float, cardinal: Float, half: Float): Boolean {
            var d = abs(deg - cardinal) % 360f
            if (d > 180f) d = 360f - d
            return d < half
        }

        val count = 48
        for (i in 0 until count) {
            val deg = i * (360f / count)
            if (nearCardinal(deg, 0f, northHalf) ||
                nearCardinal(deg, 90f, eastHalf) ||
                nearCardinal(deg, 180f, southHalf) ||
                nearCardinal(deg, 270f, westHalf)
            ) continue
            // 普通刻度更细；东北/东南/西北/西南四个斜向刻度稍粗一点
            val diag = (abs((deg + 45f) % 90f) < 3f) || (abs((deg + 45f) % 90f) > 87f)
            tickPaint.strokeWidth = if (diag) dp(2.4f) else dp(1.0f)
            val rad = Math.toRadians((deg - 90.0))
            val x1 = cx + cos(rad).toFloat() * tickOuter
            val y1 = cy + sin(rad).toFloat() * tickOuter
            val x2 = cx + cos(rad).toFloat() * tickInner
            val y2 = cy + sin(rad).toFloat() * tickInner
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // 北 / 南 / 东 / 西：文字中心落在刻度环带上（与刻度线重合），
        // 用字体度量做真正的纵向居中，并保证文字整体留在视图内
        fun baseline(y: Float): Float = y - (fm.ascent + fm.descent) / 2f
        canvas.drawText("北", cx, baseline(cy - labelR), labelPaint)
        canvas.drawText("南", cx, baseline(cy + labelR), labelPaint)
        canvas.drawText("东", cx + labelR, baseline(cy), labelPaint)
        canvas.drawText("西", cx - labelR, baseline(cy), labelPaint)

        val dir = dirDeg
        if (dir != null) {
            // 风的来向 = dir；箭头从“来向”贯穿到“去向”（dir+180）
            val fromRad = Math.toRadians(dir - 90.0)
            val toRad = Math.toRadians(dir + 180.0 - 90.0)
            // 箭头拉长：圆点与箭头头部都落在刻度环带上（与刻度线重合）
            val fromR = r - dp(4f)
            val toR = r - dp(1f)
            val fx = cx + cos(fromRad).toFloat() * fromR
            val fy = cy + sin(fromRad).toFloat() * fromR
            val tx = cx + cos(toRad).toFloat() * toR
            val ty = cy + sin(toRad).toFloat() * toR

            canvas.drawLine(fx, fy, tx, ty, arrowPaint)

            // 箭头头部
            val headLen = dp(9f)
            val angle = Math.atan2((ty - cy).toDouble(), (tx - cx).toDouble())
            val path = Path()
            path.moveTo(tx, ty)
            path.lineTo(
                tx - cos(angle - Math.PI / 6).toFloat() * headLen,
                ty - sin(angle - Math.PI / 6).toFloat() * headLen
            )
            path.moveTo(tx, ty)
            path.lineTo(
                tx - cos(angle + Math.PI / 6).toFloat() * headLen,
                ty - sin(angle + Math.PI / 6).toFloat() * headLen
            )
            canvas.drawPath(path, arrowPaint)

            // 尾端圆点（风的来向）
            canvas.drawCircle(fx, fy, dp(5f), dotPaint)
        }

    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
