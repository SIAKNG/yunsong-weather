package com.example.weatherapp

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

data class DailyReminderWindow(val tomorrow: Boolean, val issuedDate: LocalDate) {
    val key get() = "${if (tomorrow) "tomorrow" else "today"}:$issuedDate"
    val forecastDate: LocalDate get() = if (tomorrow) issuedDate.plusDays(1) else issuedDate
}

object WeatherReminderPolicy {
    fun window(now: Long = System.currentTimeMillis()): DailyReminderWindow {
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        return when {
            local.hour < 7 -> DailyReminderWindow(true, local.toLocalDate().minusDays(1))
            local.hour >= 22 -> DailyReminderWindow(true, local.toLocalDate())
            else -> DailyReminderWindow(false, local.toLocalDate())
        }
    }

    fun temperatureChange(city: CityPage, daily: Daily?, today: LocalDate = LocalDate.now()): WeatherChangeEvent? {
        val dates = daily?.temperature.orEmpty().associateBy { it.date?.take(10) }
        val current = dates[today.toString()] ?: return null
        val next = dates[today.plusDays(1).toString()] ?: return null
        return temperatureChange(city, today, current.max, current.min, next.max, next.min)
    }

    /** 和风日预报也走同一整数阈值和同一台账，避免切换天气源后变温提醒失效。 */
    fun temperatureChange(city: CityPage, daily: List<QDay>, today: LocalDate = LocalDate.now()): WeatherChangeEvent? {
        val dates = daily.associateBy { it.date.take(10) }
        val current = dates[today.toString()] ?: return null
        val next = dates[today.plusDays(1).toString()] ?: return null
        return temperatureChange(city, today, current.high, current.low, next.high, next.low)
    }

    private fun temperatureChange(
        city: CityPage,
        today: LocalDate,
        currentHigh: Double?,
        currentLow: Double?,
        nextHigh: Double?,
        nextLow: Double?
    ): WeatherChangeEvent? {
        // 提醒的阈值与正文都按用户看见的整数温度计算，避免出现 4.9℃/5.0℃
        // 在同一轮判断中前后不一致的边界情况。
        val highDelta = nextHigh?.roundToInt()?.let { high -> currentHigh?.roundToInt()?.let { high - it } }
        val lowDelta = nextLow?.roundToInt()?.let { low -> currentLow?.roundToInt()?.let { low - it } }
        val changes = listOfNotNull(highDelta?.let { "最高温" to it }, lowDelta?.let { "最低温" to it })
            .filter { abs(it.second) >= 5 }
        if (changes.isEmpty()) return null
        val detail = changes.joinToString("，") { (label, delta) ->
            "$label${if (delta > 0) "升高" else "降低"}${abs(delta)}℃"
        }
        return WeatherChangeEvent(city, "temperature",
            today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            1, "${city.name}明日变温提醒", "明天较今天$detail，请及时增减衣物。")
    }

    /**
     * 变温是“次日预报”提醒：从前一日 21:00 开始，最迟可补送至预报日结束。
     * 成功投递由台账单独记录；这里仅判断当前时刻是否应继续尝试。
     */
    fun isTemperatureReminderDue(forecastAtMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
        val zone = ZoneId.systemDefault()
        val forecastDate = Instant.ofEpochMilli(forecastAtMillis).atZone(zone).toLocalDate()
        val startsAt = forecastDate.minusDays(1).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        val expiresAt = forecastDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return now in startsAt until expiresAt
    }
}
