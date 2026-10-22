package com.example.weatherapp

import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat

/** 48 小时与多日详情共用的“回到当前”状态样式。 */
internal object DetailReturnCurrentButtonStyle {
    fun apply(
        button: TextView,
        atCurrent: Boolean,
        @ColorInt primary: Int,
        @ColorInt secondary: Int,
        @ColorInt activeFill: Int,
        density: Float
    ) {
        button.isEnabled = !atCurrent
        button.isClickable = !atCurrent
        val dark = WeatherSettings.isDarkTheme(button.context)
        button.setTextColor(if (atCurrent) {
            if (dark) Color.rgb(151, 161, 179) else Color.rgb(106, 116, 131)
        } else {
            if (dark) Color.rgb(230, 243, 255) else Color.rgb(24, 77, 127)
        })
        button.typeface = if (atCurrent) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        if (button is FrostedActionButton) {
            button.setGlassState(atCurrent, dark)
        } else button.background = GradientDrawable().apply {
            setColor(
                if (atCurrent) {
                    // 只降低底板强调度，不降低整个 View 的 alpha；浅色主题仍保留
                    // 足够清晰的禁用文字，深色主题也不会变成不可辨认的灰块。
                    ColorUtils.setAlphaComponent(secondary, 18)
                } else {
                    activeFill
                }
            )
            cornerRadius = 12f * density
        }
        button.contentDescription = if (atCurrent) "已位于当前小时" else "回到当前小时"
        ViewCompat.setStateDescription(button, if (atCurrent) "不可点击，已经位于当前" else "可点击")
    }
}
