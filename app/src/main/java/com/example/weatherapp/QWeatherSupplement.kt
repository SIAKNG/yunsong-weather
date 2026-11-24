package com.example.weatherapp

import android.content.Context

/** Requests scoped to the Open-Meteo plus QWeather mode and shared by foreground and risk checks. */
internal object QWeatherSupplement {
    private val airLock = Any()
    private val alertLock = Any()
    private const val ALERT_FRESHNESS_MS = 15L * 60_000L

    fun air(context: Context, page: CityPage): QWeatherBundle? = synchronized(airLock) {
        WeatherCache.loadSupplementalAir(context, page)?.let { return@synchronized it }
        val scope = WeatherCache.homeScope(context)
        if (!WeatherSettings.useQWeatherSupplement(context)) return@synchronized null
        val data = QWeatherApi.fetchAirOnly(context, page.lat, page.lng)
        if (scope != WeatherCache.homeScope(context)) return@synchronized null
        if (data.aqi != null || data.aqiHourly.isNotEmpty()) {
            WeatherCache.saveSupplementalAir(context, page, data)
            data
        } else null
    }

    fun alerts(context: Context, page: CityPage): List<QAlert> = synchronized(alertLock) {
        WeatherCache.loadRecentHomeAlerts(context, page, ALERT_FRESHNESS_MS)?.let { return@synchronized it }
        val scope = WeatherCache.homeScope(context)
        check(WeatherSettings.useQWeatherSupplement(context))
        val data = QWeatherApi.fetchAlertsOnlyChecked(context, page.lat, page.lng)
        check(scope == WeatherCache.homeScope(context))
        WeatherCache.saveHomeAlerts(context, page, data)
        data
    }
}
