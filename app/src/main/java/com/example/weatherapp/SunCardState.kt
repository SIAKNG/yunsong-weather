package com.example.weatherapp

/** 日出日落卡片的纯展示状态，避免时间区间判断散落在 Fragment 中。 */
data class SunCardState(
    val leftTimeMillis: Long,
    val rightTimeMillis: Long,
    val leftIsSunrise: Boolean,
    val rightIsSunrise: Boolean,
    val isDaytime: Boolean,
    val countdownTargetMillis: Long,
    val daylightMillis: Long
)

object SunCardStateCalculator {
    fun calculate(
        sunriseMillis: Long,
        sunsetMillis: Long,
        yesterdaySunsetMillis: Long,
        nextSunriseMillis: Long,
        nowMillis: Long
    ): SunCardState? {
        if (sunriseMillis <= 0L || sunsetMillis <= sunriseMillis) return null
        val isDaytime = nowMillis in sunriseMillis until sunsetMillis
        val beforeSunrise = nowMillis < sunriseMillis
        val leftTime = when {
            isDaytime -> sunriseMillis
            beforeSunrise -> yesterdaySunsetMillis.takeIf { it > 0L } ?: sunsetMillis
            else -> sunsetMillis
        }
        val rightTime = when {
            isDaytime -> sunsetMillis
            beforeSunrise -> sunriseMillis
            else -> nextSunriseMillis.takeIf { it > 0L } ?: sunriseMillis + DAY_MILLIS
        }
        return SunCardState(
            leftTimeMillis = leftTime,
            rightTimeMillis = rightTime,
            leftIsSunrise = isDaytime,
            rightIsSunrise = !isDaytime,
            isDaytime = isDaytime,
            countdownTargetMillis = if (isDaytime) sunsetMillis else rightTime,
            daylightMillis = sunsetMillis - sunriseMillis
        )
    }

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
}
