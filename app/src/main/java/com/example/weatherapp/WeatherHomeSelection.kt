package com.example.weatherapp

/** Entry selection never depends on the last page retained by ViewPager or preferences. */
internal object WeatherHomeSelection {
    fun defaultIndex(cities: List<CityPage>): Int = cities.indexOfFirst { it.isCurrentLocation }.takeIf { it >= 0 } ?: 0
}
