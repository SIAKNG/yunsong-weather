package com.example.weatherapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 设备重启或应用更新后恢复已启用的每日天气提醒。 */
class WeatherBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RECOVERY_ACTIONS) return
        WeatherNotificationCenter.ensureChannels(context)
        WeatherRecoveryAlarm.schedule(context, replace = true)
        WeatherNotificationCenter.updateSchedules(context)
        WeatherBackgroundScheduler.schedule(context)
    }

    companion object {
        internal val RECOVERY_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}
