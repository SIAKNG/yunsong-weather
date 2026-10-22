package com.example.weatherapp

/**
 * 统一管理进入主页后的后台刷新节奏。
 *
 * 当前城市保持立即刷新；其他城市按队列串行处理，避免多城市同时请求多个天气接口，
 * 从而在弱网或 API 限流时拖慢首页、耗尽线程或制造大量过期回调。
 */
object WeatherRefreshPolicy {
    // 进入前台的重复生命周期回调只做很短防抖：每次真正回到 App 都会刷新全部保存城市。
    const val ENTRY_DEBOUNCE_MS = 3_000L
    // 页面切换采用参考工程的城市级 90 秒新鲜度窗口，避免左右快速滑动造成重复请求。
    const val PAGE_CITY_REFRESH_MS = 90_000L
    const val BETWEEN_CITIES_DELAY_MS = 500L
    const val SILENT_CITY_TIMEOUT_MS = 15_000L
    const val MAX_NETWORK_OBSERVATION_AGE_MS = 60L * 60 * 1000
    const val LOCATION_CACHE_MS = 5L * 60 * 1000

    fun canCommitObservation(now: Long, incomingAt: Long?, renderedAt: Long): Boolean {
        if (incomingAt == null || incomingAt <= 0) return true
        return incomingAt <= now + WeatherCachePolicy.CLOCK_SKEW_MS &&
            now - incomingAt <= MAX_NETWORK_OBSERVATION_AGE_MS &&
            (renderedAt !in (now - MAX_NETWORK_OBSERVATION_AGE_MS)..(now + WeatherCachePolicy.CLOCK_SKEW_MS) || incomingAt >= renderedAt)
    }

    fun shouldStartEntryRefresh(nowMillis: Long, lastStartedAtMillis: Long): Boolean =
        lastStartedAtMillis <= 0L || nowMillis - lastStartedAtMillis >= ENTRY_DEBOUNCE_MS

    fun shouldRefreshPageCity(nowMillis: Long, lastRefreshedAtMillis: Long): Boolean =
        lastRefreshedAtMillis <= 0L || nowMillis - lastRefreshedAtMillis >= PAGE_CITY_REFRESH_MS
}
