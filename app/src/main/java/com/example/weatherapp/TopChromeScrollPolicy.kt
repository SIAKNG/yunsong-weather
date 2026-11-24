package com.example.weatherapp

/** Maps weather-page scroll position to top chrome visibility without depending on Views. */
object TopChromeScrollPolicy {
    fun revealProgress(scrollY: Int, start: Int, fadeDistance: Int): Float {
        val t = ((scrollY - start).toFloat() / fadeDistance.coerceAtLeast(1)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
