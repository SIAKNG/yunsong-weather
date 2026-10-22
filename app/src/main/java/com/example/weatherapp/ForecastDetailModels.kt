package com.example.weatherapp

import java.io.Serializable

data class ForecastHour(
    val time: Long, val skycon: String?, val temperature: Double?, val feelsLike: Double?, val wind: Double?,
    val uv: Double?, val humidity: Double?, val aqi: Double?, val isHistorical: Boolean = false,
    /** 气象标准的来向角：0° 为北风，90° 为东风。 */
    val windDirectionDegrees: Double? = null,
    val precipitationProbability: Double? = null,
    val precipitationAmountMm: Double? = null,
    val gustKmh: Double? = null,
    val pressureHpa: Double? = null,
    val visibilityKm: Double? = null
) : Serializable

/**
 * 将“风从何处来”的气象角度转换为导航箭头的朝向。图中的箭头指向空气实际流动的
 * 去向，因此要相对来向旋转 180°；未提供或非法的风向绝不绘制为一个猜测的箭头。
 */
internal object ForecastWindDirection {
    fun navigationArrowRotation(fromDegrees: Double?): Float? {
        if (fromDegrees == null || !fromDegrees.isFinite()) return null
        val normalized = ((fromDegrees % 360.0) + 360.0) % 360.0
        return ((normalized + 180.0) % 360.0).toFloat()
    }
}

/** 多日详情中紧凑读数下的中文语义提示，数值本身仍保留在上一行。 */
internal object ForecastMetricSemanticLabel {
    fun uv(value: Double): String = when {
        value < 0.5 -> "无"
        value <= 2.0 -> "很弱"
        value <= 5.0 -> "中等"
        value <= 7.0 -> "较强"
        value <= 10.0 -> "很强"
        else -> "极强"
    }

    fun humidity(value: Double): String = when {
        value < 30.0 -> "干燥"
        value <= 60.0 -> "舒适"
        value <= 75.0 -> "偏湿"
        value <= 85.0 -> "潮湿"
        else -> "很潮湿"
    }
}

object ForecastDetailData {
    const val HOUR = 3_600_000L
    const val HOME_HOURS = 48
    fun build(points: List<HourlyMetricPoint>, sky: Map<Long, String?>,
              air: List<AirQualityPoint>, now: Long, pastHours: Int = 0): List<ForecastHour> {
        val aqi = air.associateBy { it.timeMillis / HOUR }
        val currentHour = now / HOUR
        val firstHour = currentHour - pastHours.coerceAtLeast(0)
        fun Double?.valid() = this?.takeIf { it.isFinite() }
        return points.filter { it.timeMillis / HOUR >= firstHour }
            .distinctBy { it.timeMillis / HOUR }.sortedBy { it.timeMillis }.map {
                ForecastHour(
                    time = it.timeMillis,
                    skycon = sky[it.timeMillis / HOUR],
                    temperature = it.temperature.valid(),
                    feelsLike = it.feelsLike.valid(),
                    precipitationProbability = it.precipitationProbability.valid(),
                    precipitationAmountMm = it.precipitationAmountMm.valid(),
                    wind = it.windKmh.valid(),
                    gustKmh = it.gustKmh.valid(),
                    uv = it.uv.valid(),
                    humidity = it.humidityPercent.valid(),
                    aqi = aqi[it.timeMillis / HOUR]?.aqi.valid(),
                    pressureHpa = it.pressureHpa.valid(),
                    visibilityKm = it.visibilityKm.valid(),
                    isHistorical = it.timeMillis / HOUR < currentHour,
                    windDirectionDegrees = it.windDirectionDegrees.valid()
                )
            }
    }
}
