package com.example.weatherapp

import kotlin.math.roundToInt

/** 不依赖界面资源的指标文案和序列规则，便于本地单元测试覆盖。 */
object MetricDisplayPolicy {
    /**
     * 体感说明描述的是人体此刻的冷热舒适度，不是“体感值比气温高/低多少”。
     * [actual] 保留在签名中，避免旧调用方和历史快照反序列化断裂；它不参与舒适度判断。
     */
    fun feelsLikeAdvice(feels: Double?, actual: Double?): String =
        FeelsLikeComfortPolicy.forCelsius(feels).advice

    /**
     * 详情图表中的第二行始终保留体感与实际温度的可见差值。
     * 传入值已按当前设置转换为 ℃ 或 ℉，因此文字、图表和首页卡片采用同一显示口径。
     */
    fun feelsLikeComparison(actual: Double?, feels: Double?, unit: String): String {
        val delta = FeelsLikeComfortPolicy.displayDelta(actual, feels)
            ?: return "实际温度待更新"
        if (delta == 0.0) return "与实际温度相同（温差 0$unit）"
        val direction = if (delta > 0) "高" else "低"
        return "较实际温度$direction ${kotlin.math.abs(delta).roundToInt()}$unit"
    }

    fun gustOf(point: HourlyMetricPoint): Double? = point.gustKmh
}

/** 以摄氏体感温度划分的、面向用户的舒适度状态。 */
data class FeelsLikeComfort(
    val level: String,
    val advice: String
)

/**
 * 统一首页和二级页的体感文案。实际温度只是“体感温度”的输入因素之一，
 * 不应反过来决定用户看到偏热、偏冷或舒适的结论。
 */
object FeelsLikeComfortPolicy {
    fun forCelsius(feels: Double?): FeelsLikeComfort {
        if (feels == null || feels.isNaN()) return FeelsLikeComfort("暂无等级", "体感数据待更新")
        return when {
            feels <= -10 -> FeelsLikeComfort("严寒", "体感严寒，注意防冻")
            feels <= 0 -> FeelsLikeComfort("寒冷", "体感寒冷，注意保暖")
            feels <= 8 -> FeelsLikeComfort("偏冷", "体感偏冷，建议添衣")
            feels <= 18 -> FeelsLikeComfort("凉爽", "体感凉爽，备件外套")
            feels < 27 -> FeelsLikeComfort("舒适", "体感舒适，适合出行")
            feels < 32 -> FeelsLikeComfort("偏热", "体感偏热，及时补水")
            feels < 36 -> FeelsLikeComfort("炎热", "体感炎热，注意防晒")
            else -> FeelsLikeComfort("酷热", "体感酷热，减少外出")
        }
    }

    /**
     * 卡片以整数温度呈现；显示值相同就应把高亮点固定在正中，
     * 不能让用户看见“18℃ / 18℃”却看到一小段偏移的温差。
     */
    fun displayDelta(actual: Double?, feels: Double?): Double? {
        if (actual == null || feels == null || actual.isNaN() || feels.isNaN()) return null
        return if (actual.roundToInt() == feels.roundToInt()) 0.0 else feels - actual
    }
}
