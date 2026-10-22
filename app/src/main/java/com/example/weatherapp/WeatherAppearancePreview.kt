package com.example.weatherapp

import android.content.Intent

/**
 * 仅限 Debug 真机验收的外观覆盖层。
 * 不写入偏好、不改动天气数据，也不影响发布版本；进程重启且不携带参数后会自动恢复真实天气外观。
 */
object WeatherAppearancePreview {
    const val EXTRA_FORCE_DAYTIME_PARTLY = "com.example.weatherapp.extra.FORCE_DAYTIME_PARTLY"

    @Volatile
    private var forceDaytimePartly = false

    fun configure(intent: Intent?) {
        forceDaytimePartly = BuildConfig.DEBUG &&
            (intent?.getBooleanExtra(EXTRA_FORCE_DAYTIME_PARTLY, false) == true)
    }

    fun resolve(skycon: String, night: Boolean): Pair<String, Boolean> =
        if (forceDaytimePartly) "PARTLY_CLOUDY_DAY" to false else skycon to night

    fun isActive(): Boolean = forceDaytimePartly
}
