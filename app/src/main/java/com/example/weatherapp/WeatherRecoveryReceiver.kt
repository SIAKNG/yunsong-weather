package com.example.weatherapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 每日提醒的独立补发唤醒。接收器仅读缓存投递；联网交给持久任务。 */
class WeatherRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WeatherRecoveryAlarm.schedule(context, replace = true)
        val tomorrow = WeatherReminderPolicy.window().tomorrow
        if (!WeatherNotificationCenter.postDailyForecast(context, tomorrow))
            WeatherBackgroundScheduler.enqueueDailyForecast(context, tomorrow)
    }
}

object WeatherRecoveryAlarm {
    fun schedule(context: Context, replace: Boolean = false) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, WeatherRecoveryReceiver::class.java)
        val prefs = context.getSharedPreferences("weather_recovery_alarm", Context.MODE_PRIVATE)
        val pending = PendingIntent.getBroadcast(context, 7015, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!WeatherSettings.dailyForecastReminder(context)) {
            alarm.cancel(pending); prefs.edit().remove("next").apply(); return
        }
        val now = System.currentTimeMillis()
        val at = now + 15 * 60_000L
        // A package replace can leave this preference behind while Android removes the
        // corresponding alarm. Re-register the stable PendingIntent during every
        // reconciliation instead of trusting the cached timestamp.
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        prefs.edit().putLong("next", at).apply()
    }

    internal fun nextScheduledAt(context: Context): Long =
        context.getSharedPreferences("weather_recovery_alarm", Context.MODE_PRIVATE).getLong("next", 0L)

    internal fun pendingIntentExists(context: Context): Boolean = PendingIntent.getBroadcast(
        context,
        7015,
        Intent(context, WeatherRecoveryReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    ) != null
}
