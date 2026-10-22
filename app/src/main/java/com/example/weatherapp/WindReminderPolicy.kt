package com.example.weatherapp

import android.content.Context

/** 对齐用户提供的南风 WindAlertPolicy：持续风、阵风均从 38.9 km/h 起提醒。 */
object WindReminderPolicy {
    const val THRESHOLD_KMH = 38.9
    const val REPEAT_INTERVAL_MS = 2L * 60 * 60 * 1000
    data class Wind(val speed: Double, val level: String, val isGust: Boolean)

    fun evaluate(windSpeed: Double?, gustSpeed: Double?): Wind? =
        listOfNotNull(candidate(windSpeed, false), candidate(gustSpeed, true)).maxByOrNull { it.speed }

    private fun candidate(speed: Double?, gust: Boolean): Wind? {
        if (speed == null || !speed.isFinite() || speed < THRESHOLD_KMH) return null
        val level = when {
            speed >= 88.2 -> "9级"
            speed >= 74.9 -> "8级"
            speed >= 61.9 -> "7级"
            speed >= 50.0 -> "6级"
            else -> "5级"
        }
        return Wind(speed, level, gust)
    }

    fun event(city: CityPage, speed: Double?, gust: Double?, now: Long = System.currentTimeMillis()): WeatherChangeEvent? {
        val wind = evaluate(speed, gust) ?: return null
        val kind = if (wind.isGust) "阵风" else "大风"
        return WeatherChangeEvent(city, "wind", now, wind.level.removeSuffix("级").toInt(),
            "${city.name}大风提醒 · ${wind.level}$kind",
            "当前${if (wind.isGust) "阵风" else "风速"} ${wind.speed} km/h，请注意防风，避免高空作业。")
    }

    fun fromCaiyun(city: CityPage, result: Result, now: Long = System.currentTimeMillis()): WeatherChangeEvent? {
        val hour = now / 3_600_000L * 3_600_000L
        val hourlyGust = result.hourly?.gust.orEmpty().mapNotNull { item ->
            val time = item.datetime?.let(TimeUtil::parse) ?: return@mapNotNull null
            item.value?.takeIf { it.isFinite() }?.let { time to it }
        }.filter { it.first in hour..(hour + 3_600_000L) }.minByOrNull { it.first }?.second
        return event(city, result.realtime?.wind?.speed,
            result.realtime?.gust?.takeIf { it.isFinite() } ?: hourlyGust, now)
    }

    fun isDue(lastSuccess: Long, now: Long): Boolean =
        lastSuccess <= 0L || now < lastSuccess || now - lastSuccess > REPEAT_INTERVAL_MS
}

/**
 * 同一次持续大风只允许同文案通知一次。投递失败不写入；风停后会由后台刷新清除，
 * 下次真正重新达到阈值才能重新提醒。
 */
object WindReminderDelivery {
    @Synchronized
    fun deliver(context: Context, city: CityPage, fingerprint: String, send: () -> Boolean): Boolean {
        val prefs = context.getSharedPreferences("wind_delivery_v1", Context.MODE_PRIVATE)
        val key = WeatherNotificationLedger.cityKey(city)
        if (prefs.getString(key, null) == fingerprint) return false
        if (!send()) return false
        prefs.edit().putString(key, fingerprint).commit()
        return true
    }

    fun clear(context: Context, city: CityPage) {
        context.getSharedPreferences("wind_delivery_v1", Context.MODE_PRIVATE)
            .edit().remove(WeatherNotificationLedger.cityKey(city)).apply()
    }
}
