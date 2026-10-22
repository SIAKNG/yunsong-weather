package com.example.weatherapp

import android.graphics.Color

// 天气背景渐变配色（顶 → 中 → 底）。
// 主天气页的动态背景和城市管理卡片共用同一套配色，保证两张界面颜色一致。
object WeatherBackgroundPalette {

    enum class Type { CLEAR, PARTLY, CLOUDY, RAIN, SNOW, HAZE, WIND }

    private val CLEAR_DAY = intArrayOf(
        Color.rgb(16, 98, 205), Color.rgb(31, 134, 221), Color.rgb(86, 180, 232)
    )
    private val PARTLY = intArrayOf(
        // 白天多云：保留方案二更深、更亮的蓝色层次；夜晚仍统一使用 NIGHT。
        Color.rgb(22, 126, 234), Color.rgb(50, 154, 247), Color.rgb(114, 197, 255)
    )
    private val CLOUDY = intArrayOf(
        // Daytime only: restrained neutral grey, rather than an opaque card overlay.
        // Night still uses the unchanged shared NIGHT palette below.
        Color.rgb(72, 86, 98), Color.rgb(105, 120, 132), Color.rgb(138, 149, 157)
    )
    private val RAIN = intArrayOf(
        Color.rgb(54, 80, 105), Color.rgb(90, 119, 139), Color.rgb(139, 160, 176)
    )
    private val SNOW = intArrayOf(
        Color.rgb(112, 144, 168), Color.rgb(166, 192, 208), Color.rgb(211, 221, 228)
    )
    private val HAZE = intArrayOf(
        // Readable neutral haze comes from the sky, not an opaque card overlay.
        Color.rgb(85, 90, 85), Color.rgb(104, 112, 99), Color.rgb(138, 146, 131)
    )
    private val WIND = intArrayOf(
        Color.rgb(67, 118, 159), Color.rgb(121, 166, 195), Color.rgb(161, 193, 212)
    )
    private val NIGHT = intArrayOf(
        Color.rgb(18, 29, 78), Color.rgb(28, 45, 105), Color.rgb(51, 71, 128)
    )

    fun typeOf(skycon: String?): Type {
        val s = skycon ?: return Type.PARTLY
        return when {
            s.contains("SNOW") -> Type.SNOW
            s.contains("RAIN") || s.contains("THUNDER") || s.contains("STORM") -> Type.RAIN
            s.contains("CLEAR") -> Type.CLEAR
            s.contains("PARTLY") -> Type.PARTLY
            s.contains("CLOUDY") -> Type.CLOUDY
            s.contains("HAZE") || s.contains("FOG") ||
                s.contains("DUST") || s.contains("SAND") -> Type.HAZE
            s.contains("WIND") -> Type.WIND
            else -> Type.PARTLY
        }
    }

    // 返回 4 色渐变（顶部与中段之间插一色），与动态背景的天空渐变完全一致
    fun colors(skycon: String?, night: Boolean): IntArray {
        val day = when (typeOf(skycon)) {
            Type.CLEAR -> CLEAR_DAY
            Type.PARTLY -> PARTLY
            Type.CLOUDY -> CLOUDY
            Type.RAIN -> RAIN
            Type.SNOW -> SNOW
            Type.HAZE -> HAZE
            Type.WIND -> WIND
        }
        return expand4(if (night) NIGHT else day)
    }

    fun isDark(skycon: String?, night: Boolean): Boolean {
        val t = typeOf(skycon)
        return night || (t != Type.CLEAR && t != Type.PARTLY)
    }

    /**
     * 根据状态栏下方的顶部渐变色，选择黑/白系统文字和图标。
     * 用 WCAG 对比度而非深浅色主题判断，保证浅雾、雪天等背景也清晰可读。
     */
    fun statusBarUsesLightIcons(skycon: String?, night: Boolean): Boolean =
        statusBarUsesLightIconsForTopColor(colors(skycon, night).first())

    fun statusBarUsesLightIconsForTopColor(color: Int): Boolean {
        return StatusBarContrastPolicy.usesLightIcons(color)
    }

    /** 返回背景在垂直位置上的实际天空色，供毛玻璃卡片获得同源填充。 */
    fun colorAt(skycon: String?, night: Boolean, verticalFraction: Float): Int {
        val palette = colors(skycon, night)
        val value = verticalFraction.coerceIn(0f, 1f) * (palette.lastIndex)
        val index = value.toInt().coerceIn(0, palette.lastIndex - 1)
        val progress = value - index
        val from = palette[index]
        val to = palette[(index + 1).coerceAtMost(palette.lastIndex)]
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * progress).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * progress).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * progress).toInt()
        )
    }

    private fun expand4(c: IntArray): IntArray {
        val mid = Color.rgb(
            (Color.red(c[0]) + Color.red(c[1])) / 2,
            (Color.green(c[0]) + Color.green(c[1])) / 2,
            (Color.blue(c[0]) + Color.blue(c[1])) / 2
        )
        return intArrayOf(c[0], mid, c[1], c[2])
    }
}
