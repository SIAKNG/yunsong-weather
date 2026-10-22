package com.example.weatherapp

import android.graphics.Color
import java.util.Locale

/** 可在首页卡片和详情页之间安全传递的逐小时指标。基础单位统一为 ℃、km/h、hPa、km、%。 */
data class HourlyMetricPoint(
    val timeMillis: Long,
    val temperature: Double? = null,
    val feelsLike: Double? = null,
    /** 概率可以来自彩云 0–1 或和风 0–100；展示层统一规范化。 */
    val precipitationProbability: Double? = null,
    /** 小时累计降水量，单位 mm；源未提供时保持 null，不以概率推算。 */
    val precipitationAmountMm: Double? = null,
    val uv: Double? = null,
    val windKmh: Double? = null,
    val gustKmh: Double? = null,
    val windDirectionDegrees: Double? = null,
    val pressureHpa: Double? = null,
    val visibilityKm: Double? = null,
    val humidityPercent: Double? = null
)

enum class MetricKind(
    val title: String,
    val unit: String,
    val color: Int,
    val lineColors: IntArray,
    val about: String
) {
    UV("紫外线", "级", Color.rgb(255, 209, 56), intArrayOf(Color.rgb(76, 214, 105), Color.rgb(229, 223, 49), Color.rgb(255, 172, 35), Color.rgb(255, 84, 87), Color.rgb(231, 60, 145)), "紫外线指数（UVI）反映太阳紫外线对皮肤的潜在伤害，单位为无量纲指数。0–2 为低，3–5 为中等，6–7 为高，8–10 很高，11 以上极高。达到 3 级应使用防晒、遮阳帽或墨镜，正午尽量减少暴晒。"),
    FEELS_LIKE("体感温度", "℃", Color.rgb(94, 211, 104), intArrayOf(Color.rgb(66, 148, 255), Color.rgb(84, 211, 104), Color.rgb(255, 191, 51), Color.rgb(255, 80, 78)), "体感温度综合气温、湿度、风速和日照估算人体感受，常用单位为摄氏度。舒适度根据当前体感温度判断：偏高时及时补水、防晒，偏低时添衣并注意风寒；极端冷热时应减少长时间户外活动。"),
    WIND("风力", "km/h", Color.rgb(113, 205, 255), intArrayOf(Color.rgb(170, 231, 255), Color.rgb(95, 189, 255), Color.rgb(28, 112, 218)), "风力表示近地面空气运动速度，常用单位为 km/h 或风力等级；阵风是短时最大风速。风速超过 20km/h 骑行需更谨慎，超过 39km/h 应远离广告牌和临时搭建物，高空及水上活动需避风。"),
    PRESSURE("气压", "hPa", Color.rgb(186, 170, 255), intArrayOf(Color.rgb(132, 205, 255), Color.rgb(185, 157, 255), Color.rgb(239, 173, 255)), "气压是单位面积空气柱的压力，常用单位 hPa。海平面附近常见约 1013hPa，但会随海拔不同而变化。气压快速升降常伴随天气变化；部分敏感人群可能感到不适，应保证休息并留意预警。"),
    VISIBILITY("能见度", "km", Color.rgb(95, 224, 191), intArrayOf(Color.rgb(74, 207, 169), Color.rgb(83, 188, 235), Color.rgb(57, 135, 255)), "能见度是能清晰辨认目标的最大距离，常用单位为 km。低于 1km 属于低能见度，驾车应减速、开启灯光并增大车距；5km 以下需留意雾、霾或降水，高速及水上出行宜提前规划。"),
    HUMIDITY("湿度", "%", Color.rgb(75, 188, 224), intArrayOf(Color.rgb(87, 211, 128), Color.rgb(50, 180, 191), Color.rgb(62, 131, 255)), "相对湿度表示空气接近饱和的程度，单位为百分比。40%–60% 通常较舒适；过低会令皮肤和呼吸道干燥，过高则闷热、衣物不易干。持续高湿时应加强通风、防潮和除霉。");

    fun valueOf(point: HourlyMetricPoint): Double? = when (this) {
        UV -> point.uv
        FEELS_LIKE -> point.feelsLike
        WIND -> point.windKmh
        PRESSURE -> point.pressureHpa
        VISIBILITY -> point.visibilityKm
        HUMIDITY -> point.humidityPercent
    }

    fun secondaryOf(point: HourlyMetricPoint): Double? = when (this) {
        FEELS_LIKE -> point.temperature
        WIND -> MetricDisplayPolicy.gustOf(point)
        else -> null
    }

    fun format(value: Double?): String {
        if (value == null || value.isNaN()) return "暂无数据"
        return when (this) {
            UV -> "${value.toInt()}级"
            FEELS_LIKE -> "${value.roundText()}℃"
            WIND -> "${value.roundText()}km/h"
            PRESSURE -> "${value.toInt()}hPa"
            VISIBILITY -> "${value.oneDecimal()}km"
            HUMIDITY -> "${value.toInt()}%"
        }
    }

    fun level(value: Double?, secondary: Double? = null): String {
        if (value == null || value.isNaN()) return "暂无等级"
        return when (this) {
            UV -> when { value <= 2 -> "很弱"; value <= 5 -> "中等"; value <= 7 -> "强"; value <= 10 -> "很强"; else -> "极强" }
            FEELS_LIKE -> FeelsLikeComfortPolicy.forCelsius(value).level
            WIND -> when { value < 12 -> "微风"; value < 20 -> "和风"; value < 39 -> "风感明显"; else -> "较强" }
            PRESSURE -> when { value < 1000 -> "偏低"; value > 1025 -> "偏高"; else -> "平稳" }
            VISIBILITY -> when { value < 1 -> "较低"; value < 5 -> "一般"; else -> "良好" }
            HUMIDITY -> when { value < 30 -> "偏干"; value > 80 -> "偏湿"; else -> "舒适" }
        }
    }

    fun advice(value: Double?, secondary: Double? = null): String {
        if (value == null || value.isNaN()) return "暂未获取到该时段预测，请稍后刷新。"
        return when (this) {
            UV -> when { value >= 8 -> "防晒强度拉满，避开长时间直晒。"; value >= 5 -> "外出做好防晒，午间尽量走阴凉处。"; value >= 3 -> "日常防晒即可，别忘了补涂。"; else -> "紫外线较弱，按需进行日常防护。" }
            FEELS_LIKE -> MetricDisplayPolicy.feelsLikeAdvice(value, secondary)
            WIND -> when { value >= 39 -> "风力较强，远离临时搭建物和高空坠物。"; value >= 20 -> "风感明显，骑行和高空活动请注意安全。"; else -> "风力平稳，适合正常安排出行。" }
            PRESSURE -> "气压变化较快时，敏感人群可适当放慢活动节奏。"
            VISIBILITY -> when { value < 1 -> "能见度较低，驾车请减速并开启灯光。"; value < 5 -> "视野一般，出行请留意道路情况。"; else -> "视野良好，可正常安排出行。" }
            HUMIDITY -> when { value >= 80 -> "空气偏潮湿，注意通风与衣物干燥。"; value <= 30 -> "空气较干燥，建议及时补水保湿。"; else -> "湿度适宜，体感相对舒适。" }
        }
    }

    companion object {
        fun fromIntent(value: String?): MetricKind = entries.firstOrNull { it.name == value } ?: UV
    }
}

private fun Double.roundText(): String = String.format(Locale.US, "%.0f", this)
private fun Double.oneDecimal(): String = String.format(Locale.US, "%.1f", this).removeSuffix(".0")
