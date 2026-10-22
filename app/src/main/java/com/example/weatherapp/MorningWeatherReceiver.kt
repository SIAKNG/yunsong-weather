package com.example.weatherapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 每日 07:00 当天、22:00 次日预报提醒；触发后重新登记下一天。 */
class MorningWeatherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val tomorrow = intent.action == WeatherNotificationCenter.ACTION_DAILY_TOMORROW
        // 精确闹钟触发时先直接用已保存的天气快照发出通知，避免厂商系统延后
        // WorkManager 而把早晚提醒整个错过；后台任务完成联网刷新后会以同一通知 ID 静默更新内容。
        WeatherNotificationCenter.postDailyForecast(appContext, tomorrow)
        WeatherBackgroundScheduler.enqueueDailyForecast(
            appContext,
            tomorrow
        )
    }
}
