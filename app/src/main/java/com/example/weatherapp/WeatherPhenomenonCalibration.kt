package com.example.weatherapp

/**
 * 天气现象的保守交叉校验规则。
 *
 * 参考南风天气的行为：只对不确定性较高的云量类现象做校验，不改温度、风、AQI、
 * 降水量、预警或原始 API 响应。辅助源必须是已授权、独立的数据源；当前实现使用
 * Open-Meteo，未接入小米天气的非公开接口。
 */
data class PhenomenonCalibrationDecision(
    val skycon: String?,
    val applied: Boolean,
    val reason: String
)

object WeatherPhenomenonCalibration {
    private const val MAX_REFERENCE_AGE_MILLIS = 90L * 60L * 1000L

    /** 只有这些“云量判断”会发起辅助校验，避免覆盖彩云已经明确给出的恶劣天气。 */
    fun shouldRequest(primarySkycon: String?): Boolean =
        primarySkycon in setOf(
            "CLOUDY",
            "OVERCAST",
            "PARTLY_CLOUDY_DAY",
            "PARTLY_CLOUDY_NIGHT"
        )

    /**
     * 仅当辅助源把云量类现象识别为雨、雪、雾或强对流时才应用。不同供应商的
     * “晴/多云/阴”口径差异较大，不能据此把原始结果改成晴天。
     */
    fun decide(
        primarySkycon: String?,
        referenceSkycon: String?,
        referenceTimeMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): PhenomenonCalibrationDecision {
        if (!shouldRequest(primarySkycon)) {
            return PhenomenonCalibrationDecision(primarySkycon, false, "primary-not-cloud-uncertain")
        }
        if (referenceSkycon.isNullOrBlank()) {
            return PhenomenonCalibrationDecision(primarySkycon, false, "reference-empty")
        }
        if (referenceTimeMillis <= 0L ||
            kotlin.math.abs(nowMillis - referenceTimeMillis) > MAX_REFERENCE_AGE_MILLIS
        ) {
            return PhenomenonCalibrationDecision(primarySkycon, false, "reference-stale")
        }
        if (!isHighImpactWeather(referenceSkycon)) {
            return PhenomenonCalibrationDecision(primarySkycon, false, "reference-not-high-impact")
        }
        return PhenomenonCalibrationDecision(referenceSkycon, true, "independent-high-impact-weather")
    }

    private fun isHighImpactWeather(skycon: String): Boolean =
        skycon.contains("RAIN") ||
            skycon.contains("SNOW") ||
            skycon == "FOG" ||
            skycon.contains("STORM")
}
