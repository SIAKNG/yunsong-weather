package com.example.weatherapp

import android.app.Application
import com.google.android.material.color.DynamicColors

// 应用启动时先按保存的深浅色设置应用夜间模式，
// 确保任何 Activity 创建前主题就已就位（固定深色/浅色/跟随系统）
class WeatherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(WeatherForegroundSession)
        if (BuildConfig.USER_CONFIGURABLE && WeatherSettings.caiyunMinutelyCapability(this) == WeatherSettings.CAIYUN_MINUTELY_CHECKING)
            WeatherSettings.resetCaiyunMinutelyCapability(this)
        WeatherSettings.applyNightMode(this)
        // 统一为所有 Activity 注入系统动态配色（Material You）。这也让权限说明、
        // 设置及详情页面读取到同一组手机主题色；不支持动态配色的设备会自然回退主题默认值。
        DynamicColors.applyToActivitiesIfAvailable(this)
        WeatherStartupMaintenance.schedule(this)
    }
}
