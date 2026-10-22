package com.example.weatherapp

/** 各类气象数据的展示缓存时效；网络可用时仍以实时请求为准。 */
object WeatherCachePolicy {
    const val VOLATILE_MS = 10L * 60L * 1_000L
    const val HOURLY_MS = 60L * 60L * 1_000L
    const val DAILY_MS = 6L * 60L * 60L * 1_000L
    const val MAX_PAYLOAD_MS = 30L * 24L * 60L * 60L * 1_000L
    const val CLOCK_SKEW_MS = 5L * 60L * 1_000L

    /** Re-saving an old observation must never make it fresh again. */
    fun observationAge(now: Long, cacheAge: Long, observedAt: Long?): Long {
        if (observedAt == null || observedAt <= 0) return cacheAge
        if (observedAt > now + CLOCK_SKEW_MS) return MAX_PAYLOAD_MS + 1
        return maxOf(cacheAge, (now - observedAt).coerceAtLeast(0))
    }

    fun volatileUsable(ageMs: Long): Boolean = ageMs in 0..VOLATILE_MS
    fun hourlyUsable(ageMs: Long): Boolean = ageMs in 0..HOURLY_MS
    fun dailyUsable(ageMs: Long): Boolean = ageMs in 0..DAILY_MS
    fun payloadUsable(ageMs: Long): Boolean = ageMs in 0..MAX_PAYLOAD_MS
}
