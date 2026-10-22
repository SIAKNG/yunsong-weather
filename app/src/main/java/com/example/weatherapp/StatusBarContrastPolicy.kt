package com.example.weatherapp

/**
 * 状态栏默认保持白色图标，只有背景已经接近白色、白色图标辨识度明显不足时才改为深色。
 * 这样既符合天气页的沉浸式视觉，也不再受应用深浅色主题影响。
 */
object StatusBarContrastPolicy {
    fun usesLightIcons(backgroundColor: Int): Boolean {
        val luminance = relativeLuminance(backgroundColor)
        // 0.55 以上可视为接近高亮背景；常见晴天、阴天和夜景仍稳定使用白色。
        return luminance < 0.55
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        val red = (color shr 16) and 0xff
        val green = (color shr 8) and 0xff
        val blue = color and 0xff
        return 0.2126 * channel(red) +
            0.7152 * channel(green) +
            0.0722 * channel(blue)
    }
}
