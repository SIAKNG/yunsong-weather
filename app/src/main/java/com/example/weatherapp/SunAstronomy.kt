package com.example.weatherapp

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 日出日落详情页的中立数据模型：两种天气源都只需提供可靠的日出、日落即可。 */
data class SunAstronomyData(
    val sunriseMillis: Long,
    val sunsetMillis: Long,
    val yesterdaySunsetMillis: Long = 0L,
    val nextSunriseMillis: Long = 0L,
    val latitude: Double,
    val longitude: Double,
    val sourceName: String,
    /** 用于本地早霞、晚霞观赏预测的当前天气类型，两套天气源均已统一为 skycon。 */
    val skycon: String? = null
)

/** 早霞、晚霞并非第三方接口字段，而是基于晨昏几何时段与天气状况生成的本地观赏预测。 */
data class SunGlowForecast(
    val startMillis: Long?,
    val endMillis: Long?,
    val score: Int,
    val level: String,
    val description: String
)

data class SunPhaseTimes(
    val solarNoon: Long,
    val civilDawn: Long?,
    val civilDusk: Long?,
    val nauticalDawn: Long?,
    val nauticalDusk: Long?,
    val astronomicalDawn: Long?,
    val astronomicalDusk: Long?,
    val blueMorningStart: Long?,
    val blueMorningEnd: Long?,
    val blueEveningStart: Long?,
    val blueEveningEnd: Long?,
    val goldenMorningStart: Long?,
    val goldenMorningEnd: Long?,
    val goldenEveningStart: Long?,
    val goldenEveningEnd: Long?
)

/**
 * 基于地点和日期计算太阳高度与晨昏时刻。天气源返回的日出、日落优先展示；
 * 其余时段使用同一算法补足，因此彩云和和风都能拥有一致的详情体验。
 */
object SunAstronomyCalculator {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val SUNRISE_ALTITUDE = -0.833

    /**
     * 详情页永远先拿到可绘制的数据。若用户在天气请求尚未结束时进入，使用地点和当天日期
     * 本地计算日出/日落及前后相邻事件；因此反复进入不会出现“数据待加载”的空状态。
     */
    fun resolveForDisplay(data: SunAstronomyData): SunAstronomyData {
        val anchor = data.sunriseMillis.takeIf { it > 0L }
            ?: data.sunsetMillis.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val todayStart = dayStart(anchor)
        val today = localSunEvents(todayStart, data.latitude, data.longitude)
        val yesterday = localSunEvents(todayStart - DAY_MS, data.latitude, data.longitude)
        val tomorrow = localSunEvents(todayStart + DAY_MS, data.latitude, data.longitude)
        val sunrise = data.sunriseMillis.takeIf { it > 0L } ?: today.sunrise ?: 0L
        val sunset = data.sunsetMillis.takeIf { it > sunrise } ?: today.sunset ?: 0L
        return data.copy(
            sunriseMillis = sunrise,
            sunsetMillis = sunset,
            yesterdaySunsetMillis = data.yesterdaySunsetMillis.takeIf { it > 0L }
                ?: yesterday.sunset
                ?: sunset.takeIf { it > 0L }?.minus(DAY_MS)
                ?: 0L,
            nextSunriseMillis = data.nextSunriseMillis.takeIf { it > 0L }
                ?: tomorrow.sunrise
                ?: sunrise.takeIf { it > 0L }?.plus(DAY_MS)
                ?: 0L
        )
    }

    fun hourlyCurve(data: SunAstronomyData, stepMinutes: Int = 10): List<Pair<Long, Double>> {
        val start = dayStart(data.sunriseMillis.takeIf { it > 0L } ?: System.currentTimeMillis())
        val step = stepMinutes.coerceIn(5, 30) * 60_000L
        return buildList {
            var time = start
            while (time <= start + DAY_MS) {
                add(time to solarElevation(time, data.latitude, data.longitude))
                time += step
            }
        }
    }

    fun phases(data: SunAstronomyData, curve: List<Pair<Long, Double>> = hourlyCurve(data)): SunPhaseTimes {
        fun crossing(target: Double, rising: Boolean): Long? {
            for (index in 1 until curve.size) {
                val (beforeTime, before) = curve[index - 1]
                val (afterTime, after) = curve[index]
                val crossesUp = before < target && after >= target
                val crossesDown = before >= target && after < target
                if ((rising && crossesUp) || (!rising && crossesDown)) {
                    val fraction = ((target - before) / (after - before)).coerceIn(0.0, 1.0)
                    return beforeTime + ((afterTime - beforeTime) * fraction).toLong()
                }
            }
            return null
        }
        val noon = curve.maxByOrNull { it.second }?.first ?: data.sunriseMillis
        return SunPhaseTimes(
            solarNoon = noon,
            civilDawn = crossing(-6.0, true), civilDusk = crossing(-6.0, false),
            nauticalDawn = crossing(-12.0, true), nauticalDusk = crossing(-12.0, false),
            astronomicalDawn = crossing(-18.0, true), astronomicalDusk = crossing(-18.0, false),
            blueMorningStart = crossing(-6.0, true), blueMorningEnd = crossing(-4.0, true),
            blueEveningStart = crossing(-4.0, false), blueEveningEnd = crossing(-6.0, false),
            goldenMorningStart = crossing(-4.0, true), goldenMorningEnd = crossing(6.0, true),
            goldenEveningStart = crossing(6.0, false), goldenEveningEnd = crossing(-4.0, false)
        )
    }

    /** 以晨昏实际可见窗口结合云况做的本地预测，避免把“霞光”伪装成任一接口的实况字段。 */
    fun glowForecasts(data: SunAstronomyData, phases: SunPhaseTimes): Pair<SunGlowForecast, SunGlowForecast> {
        val key = data.skycon.orEmpty().uppercase()
        val (score, note) = when {
            key.contains("STORM") || key.contains("RAIN") || key.contains("SNOW") ->
                16 to "降水或强对流影响，霞光不易显现"
            key.contains("FOG") || key.contains("HAZE") || key.contains("DUST") || key.contains("SAND") ->
                24 to "能见度受影响，色彩层次会偏弱"
            key.contains("PARTLY") -> 78 to "云量疏密适中，较有机会形成层次"
            key.contains("CLOUDY") -> 42 to "云层偏厚，留意云隙透光"
            key.contains("CLEAR") -> 58 to "晴空为主，通透但云彩层次较少"
            else -> 50 to "按当天晨昏太阳高度做本地估算"
        }
        fun make(start: Long?, end: Long?, title: String) = SunGlowForecast(
            startMillis = start,
            endMillis = end,
            score = score,
            level = when {
                score >= 72 -> "较佳"
                score >= 52 -> "一般"
                score >= 32 -> "偏弱"
                else -> "不明显"
            },
            description = "$title$note"
        )
        return make(
            phases.blueMorningStart ?: phases.civilDawn,
            data.sunriseMillis.takeIf { it > 0L } ?: phases.blueMorningEnd,
            "日出前后，"
        ) to make(
            data.sunsetMillis.takeIf { it > 0L } ?: phases.blueEveningStart,
            phases.blueEveningEnd ?: phases.civilDusk,
            "日落前后，"
        )
    }

    /** 以地平线（0°）交点估算某日昼长，供缺少昨日天文数据时显示可解释的降级对比。 */
    fun estimatedDaylightDuration(data: SunAstronomyData): Long? {
        val curve = hourlyCurve(data)
        var sunrise: Long? = null
        var sunset: Long? = null
        for (index in 1 until curve.size) {
            val (beforeTime, before) = curve[index - 1]
            val (afterTime, after) = curve[index]
            if (sunrise == null && before < 0.0 && after >= 0.0) {
                sunrise = interpolate(beforeTime, before, afterTime, after, 0.0)
            }
            if (before >= 0.0 && after < 0.0) {
                sunset = interpolate(beforeTime, before, afterTime, after, 0.0)
            }
        }
        return if (sunrise != null && sunset != null && sunset > sunrise) sunset - sunrise else null
    }

    /** NOAA 近似太阳高度，足够用于分钟级展示与交互，不替代天气源给出的日出日落。 */
    fun solarElevation(timeMillis: Long, latitude: Double, longitude: Double): Double {
        val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val day = calendar.get(Calendar.DAY_OF_YEAR).toDouble()
        val hour = calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60.0 +
            calendar.get(Calendar.SECOND) / 3600.0
        val zone = calendar.timeZone.getOffset(timeMillis) / 3_600_000.0
        val gamma = 2.0 * PI / 365.0 * (day - 1.0 + (hour - 12.0) / 24.0)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2.0 * gamma) - 0.040849 * sin(2.0 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2.0 * gamma) + 0.000907 * sin(2.0 * gamma) -
            0.002697 * cos(3.0 * gamma) + 0.00148 * sin(3.0 * gamma)
        val minutes = hour * 60.0 + eqTime + 4.0 * longitude - 60.0 * zone
        val hourAngle = normalizedHourAngle(minutes) * PI / 180.0
        val lat = latitude * PI / 180.0
        val elevation = asin(sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(hourAngle))
        return elevation * 180.0 / PI
    }

    fun sunDirection(timeMillis: Long, latitude: Double, longitude: Double): String {
        val elevation = solarElevation(timeMillis, latitude, longitude)
        return when {
            elevation < -18 -> "深夜"
            elevation < -6 -> "天文晨昏"
            elevation < 0 -> "蓝调时刻"
            elevation < 6 -> "金色时刻"
            else -> "白昼"
        }
    }

    /** NOAA 近似太阳方位角：正北为 0°、顺时针增加，用于详情页交互信息。 */
    fun sunAzimuth(timeMillis: Long, latitude: Double, longitude: Double): Double {
        val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val day = calendar.get(Calendar.DAY_OF_YEAR).toDouble()
        val hour = calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60.0 +
            calendar.get(Calendar.SECOND) / 3600.0
        val zone = calendar.timeZone.getOffset(timeMillis) / 3_600_000.0
        val gamma = 2.0 * PI / 365.0 * (day - 1.0 + (hour - 12.0) / 24.0)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2.0 * gamma) - 0.040849 * sin(2.0 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2.0 * gamma) + 0.000907 * sin(2.0 * gamma) -
            0.002697 * cos(3.0 * gamma) + 0.00148 * sin(3.0 * gamma)
        val minutes = hour * 60.0 + eqTime + 4.0 * longitude - 60.0 * zone
        val hourAngle = normalizedHourAngle(minutes) * PI / 180.0
        val lat = latitude * PI / 180.0
        return ((atan2(
            sin(hourAngle),
            cos(hourAngle) * sin(lat) - kotlin.math.tan(decl) * cos(lat)
        ) * 180.0 / PI + 180.0) + 360.0) % 360.0
    }

    private fun interpolate(beforeTime: Long, before: Double, afterTime: Long, after: Double, target: Double): Long {
        val fraction = ((target - before) / (after - before)).coerceIn(0.0, 1.0)
        return beforeTime + ((afterTime - beforeTime) * fraction).toLong()
    }

    private fun normalizedHourAngle(trueSolarMinutes: Double): Double {
        var degrees = trueSolarMinutes / 4.0 - 180.0
        while (degrees < -180.0) degrees += 360.0
        while (degrees > 180.0) degrees -= 360.0
        return degrees
    }

    private data class LocalSunEvents(val sunrise: Long?, val sunset: Long?)

    /** 使用 -0.833° 的标准视日出日落高度，在本地插值得到分钟级事件时刻。 */
    private fun localSunEvents(dayStart: Long, latitude: Double, longitude: Double): LocalSunEvents {
        val step = 4L * 60_000L
        var previousTime = dayStart
        var previousElevation = solarElevation(previousTime, latitude, longitude)
        var sunrise: Long? = null
        var sunset: Long? = null
        var time = previousTime + step
        while (time <= dayStart + DAY_MS) {
            val elevation = solarElevation(time, latitude, longitude)
            if (sunrise == null && previousElevation < SUNRISE_ALTITUDE && elevation >= SUNRISE_ALTITUDE) {
                sunrise = interpolate(previousTime, previousElevation, time, elevation, SUNRISE_ALTITUDE)
            }
            if (sunset == null && previousElevation >= SUNRISE_ALTITUDE && elevation < SUNRISE_ALTITUDE) {
                sunset = interpolate(previousTime, previousElevation, time, elevation, SUNRISE_ALTITUDE)
            }
            previousTime = time
            previousElevation = elevation
            time += step
        }
        return LocalSunEvents(sunrise, sunset)
    }

    private fun dayStart(timeMillis: Long): Long = Calendar.getInstance(TimeZone.getDefault()).run {
        timeInMillis = timeMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }
}
