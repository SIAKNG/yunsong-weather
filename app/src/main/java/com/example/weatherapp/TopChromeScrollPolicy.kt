package com.example.weatherapp

/** Maps weather-page scroll position to top chrome visibility without depending on Views. */
object TopChromeScrollPolicy {
    fun revealProgress(scrollY: Int, temperatureTop: Int, chromeBottom: Int, fadeDistance: Int): Float {
        val reach = temperatureTop - chromeBottom
        return ((scrollY - reach).toFloat() / fadeDistance.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}
