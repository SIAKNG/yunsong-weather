package com.example.weatherapp

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

internal const val WEATHER_MAX_FONT_SCALE = 1.20f

/** Cap the font multiplier while keeping the device's display density, locale and theme. */
internal fun Context.withWeatherFontLimit(): Context {
    val current = resources.configuration
    if (current.fontScale <= WEATHER_MAX_FONT_SCALE) return this
    val override = Configuration(current).apply { fontScale = WEATHER_MAX_FONT_SCALE }
    return createConfigurationContext(override)
}

abstract class WeatherActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withWeatherFontLimit())
    }
}
