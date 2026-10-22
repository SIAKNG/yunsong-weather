package com.example.weatherapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** 六项指标、48 小时与多日图表共用的三层数据焦点。 */
internal object ChartSelectionMarker {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun draw(
        canvas: Canvas,
        x: Float,
        y: Float,
        color: Int,
        darkTheme: Boolean,
        density: Float
    ) {
        paint.color = Color.argb(92, Color.red(color), Color.green(color), Color.blue(color))
        // 与首页 48 小时卡片当前点一致：外层泛光半径为 8dp，避免在紧凑轨道内
        // 覆盖相邻文字；6dp 主题内核与 3.5dp 数据色中心保持不变。
        canvas.drawCircle(x, y, 8f * density, paint)
        paint.color = if (darkTheme) Color.WHITE else Color.rgb(26, 36, 51)
        canvas.drawCircle(x, y, 6f * density, paint)
        paint.color = color
        canvas.drawCircle(x, y, 3.5f * density, paint)
    }
}
