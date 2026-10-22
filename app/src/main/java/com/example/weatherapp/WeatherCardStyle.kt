package com.example.weatherapp

import android.graphics.Color

/** 天气页毛玻璃卡片的默认填充层。描边与模糊仍由 [BlurCardView] 独立负责。 */
object WeatherCardStyle {
    fun needsIndependentTint(skycon: String?): Boolean = false
    const val FROST_BLUR_DP = 36f
    const val FROST_WHITE_BASE = 0.055f

    /** 轻度深色吸收层保留白色天气文字的对比度，同时让背景色透过磨砂表面。 */
    fun frostedTint(skycon: String?, night: Boolean): Int {
        val color = WeatherBackgroundPalette.colorAt(skycon, night, CARD_COLOR_FRACTION)
        // Haze uses the same translucent white + sky-colour path, never the old
        // 180/255 opaque black absorption layer. Night retains ordinary shared glass.
        val hazeDay=!night && WeatherBackgroundPalette.typeOf(skycon)==WeatherBackgroundPalette.Type.HAZE
        val absorption=if(hazeDay) .76f else .82f
        return Color.argb(if(hazeDay) 72 else 58, (Color.red(color) * absorption).toInt(),
            (Color.green(color) * absorption).toInt(), (Color.blue(color) * absorption).toInt())
    }
    // 普通首页卡片为 10% 白色玻璃层 + 30% 当前天气背景色；白色底由 BlurCardView 叠加。
    // 预览页操作按钮不使用此规则，继续由 CityPreviewActivity 固定为 15% 白色。
    private const val CARD_FILL_ALPHA = 77
    private const val CARD_COLOR_FRACTION = 0.5f

    /** 兼容预览页尚未得到天气背景时的同规则初始色。 */
    fun defaultFillTint(night: Boolean): Int = backgroundTint(null, night)

    /**
     * 所有天气卡片都使用同一张天气调色板的中段背景色。
     * 不按单卡垂直位置重新取色，避免六项指标格与大卡片产生不同填充色。
     */
    fun backgroundTint(skycon: String?, night: Boolean): Int {
        val color = WeatherBackgroundPalette.colorAt(skycon, night, CARD_COLOR_FRACTION)
        return Color.argb(
            CARD_FILL_ALPHA,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    /** 昼夜均使用约 25% 的上下白色边缘。 */
    fun edgeStrokeAlpha(night: Boolean): Int = 64
}
