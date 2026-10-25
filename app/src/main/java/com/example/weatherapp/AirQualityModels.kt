package com.example.weatherapp

import android.graphics.Color
import kotlin.math.roundToInt

/** 空气质量数据的实际提供方。界面据此避免把补充数据误标成所选天气源。 */
enum class AirQualitySource(val displayName: String) {
    CAIYUN("彩云天气"),
    QWEATHER("和风天气"),
    OPEN_METEO("Open-Meteo · 模型污染物/AQI参考估算"),
    UNKNOWN("天气数据")
}

/** 统一使用中国 AQI 与 μg/m³（CO 保留 mg/m³）传递实时及逐小时空气质量数据。 */
data class AirQualityPoint(
    val timeMillis: Long,
    val aqi: Double? = null,
    val pm25: Double? = null,
    val pm10: Double? = null,
    val no2: Double? = null,
    val o3: Double? = null,
    val co: Double? = null,
    val so2: Double? = null,
    val source: AirQualitySource = AirQualitySource.UNKNOWN,
    /** 每个补齐字段的实际来源；首页和详情仍以 [source] 的彩云时间线为主。 */
    val fieldSources: Map<String, AirQualitySource> = emptyMap(),
    /** 服务端数据生成/接收时间；用于缓存与详情页的来源说明。 */
    val updatedAtMillis: Long = timeMillis
)

object AirQualityScale {
    fun level(aqi: Double?): String {
        val value = aqi?.roundToInt() ?: return "暂无"
        return when {
            value <= 50 -> "优"
            value <= 100 -> "良"
            value <= 150 -> "轻度污染"
            value <= 200 -> "中度污染"
            value <= 300 -> "重度污染"
            else -> "严重污染"
        }
    }

    fun advice(aqi: Double?): String {
        val value = aqi?.roundToInt() ?: return "暂无可用的空气质量数据。"
        return when {
            value <= 50 -> "空气很好，可正常活动，快去呼吸新鲜空气吧。"
            value <= 100 -> "空气不错，适合户外活动，建议适当开窗通风。"
            value <= 150 -> "空气质量一般，敏感人群请减少户外活动。"
            value <= 200 -> "空气较差，建议减少户外活动，外出佩戴口罩。"
            value <= 300 -> "空气很差，尽量留在室内，关闭门窗。"
            else -> "空气极差，避免外出，做好健康防护。"
        }
    }

    fun color(aqi: Double?): Int {
        val value = (aqi ?: 0.0).coerceIn(0.0, 500.0)
        val stops = doubleArrayOf(0.0, 50.0, 100.0, 150.0, 200.0, 300.0, 500.0)
        val colors = intArrayOf(
            // “优”区保持更鲜明的绿色，在深色界面中也能和轨道清晰区分。
            Color.rgb(62, 211, 98), Color.rgb(156, 214, 57), Color.rgb(255, 205, 46),
            Color.rgb(255, 151, 47), Color.rgb(226, 83, 87), Color.rgb(156, 78, 174),
            Color.rgb(124, 43, 76)
        )
        for (i in 1 until stops.size) {
            if (value <= stops[i]) {
                val f = ((value - stops[i - 1]) / (stops[i] - stops[i - 1])).toFloat()
                return blend(colors[i - 1], colors[i], f)
            }
        }
        return colors.last()
    }

    fun pollutantProgress(name: String, value: Double?): Float {
        val max = when (name) {
            "PM2.5" -> 150.0
            "PM10" -> 250.0
            "NO₂" -> 280.0
            "O₃" -> 400.0
            "CO" -> 60.0
            "SO₂" -> 800.0
            else -> 100.0
        }
        return ((value ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
    }

    fun formatPollutant(name: String, value: Double?): String {
        if (value == null || value.isNaN()) return "--"
        return if (name == "CO") {
            "%.1f".format(java.util.Locale.US, value)
        } else {
            value.roundToInt().toString()
        }
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * fraction).roundToInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * fraction).roundToInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction).roundToInt()
    )
}
