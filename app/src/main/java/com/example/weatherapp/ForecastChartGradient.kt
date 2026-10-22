package com.example.weatherapp

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils

/** Same bottom-to-top value gradients as the metric detail charts. */
internal object ForecastChartGradient {
    val temperature = MetricKind.FEELS_LIKE.lineColors
    val wind = MetricKind.WIND.lineColors
    val uv = MetricKind.UV.lineColors
    val humidity = MetricKind.HUMIDITY.lineColors
    val rain = intArrayOf(Color.rgb(65, 164, 236), Color.rgb(23, 105, 213))
    // Semantic stops use exactly the same absolute AQI values as the air-quality card.
    val air = doubleArrayOf(0.0, 50.0, 100.0, 150.0, 200.0, 300.0, 500.0).map(AirQualityScale::color).toIntArray()

    fun forTrack(title: String): IntArray = when (title) {
        "温度", "体感" -> temperature
        "风力" -> wind
        "紫外线" -> uv
        "湿度" -> humidity
        "空气" -> air
        else -> rain
    }

    fun colorAt(colors: IntArray, y: Float, top: Float, bottom: Float): Int {
        val fraction = ((bottom - y) / (bottom - top).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val position = fraction * colors.lastIndex
        val lower = position.toInt().coerceIn(0, colors.lastIndex)
        return ColorUtils.blendARGB(colors[lower], colors[(lower + 1).coerceAtMost(colors.lastIndex)], position - lower)
    }

    fun shader(colors: IntArray, top: Float, bottom: Float): Shader =
        LinearGradient(0f, bottom, 0f, top, colors, null, Shader.TileMode.CLAMP)

    /** Keep shaders out of the per-frame allocation path during horizontal dragging. */
    class Cache {
        private data class Entry(val top: Float, val bottom: Float, val shader: Shader)
        private val entries = HashMap<IntArray, Entry>()
        private var airBounds: List<Double>? = null
        private var airGradient: Shader? = null
        fun airShader(min: Double, max: Double, top: Float, bottom: Float): Shader {
            val bounds = listOf(min, max, top.toDouble(), bottom.toDouble())
            if (airBounds == bounds) return airGradient!!
            val high = max.coerceAtLeast(min + .01)
            val stops = (listOf(min, high) + listOf(0.0, 50.0, 100.0, 150.0, 200.0, 300.0, 500.0)
                .filter { it > min && it < high }).sorted()
            return LinearGradient(0f, bottom, 0f, top, stops.map(AirQualityScale::color).toIntArray(),
                stops.map { ((it - min) / (high - min)).toFloat() }.toFloatArray(), Shader.TileMode.CLAMP).also {
                airBounds = bounds
                airGradient = it
            }
        }
        fun shader(colors: IntArray, top: Float, bottom: Float): Shader {
            val old = entries[colors]
            if (old != null && old.top == top && old.bottom == bottom) return old.shader
            return ForecastChartGradient.shader(colors, top, bottom).also {
                entries[colors] = Entry(top, bottom, it)
            }
        }
    }
}
