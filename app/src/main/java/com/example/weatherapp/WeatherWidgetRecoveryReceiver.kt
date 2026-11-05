package com.example.weatherapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A widget is a recovery opportunity, not a resident service or a second notification ledger. */
class WeatherWidgetRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WeatherWidgetRecoveryAlarm.schedule(context, fired = true)
        if (WeatherWidgetStore.ids(context).isEmpty()) return
        WeatherWidgets.restore(context)
        WeatherWidgets.refresh(context, false)
        WeatherReminderRecovery.repair(context)
    }
}

internal object WeatherWidgetRecoveryAlarm {
    private const val PREFS = "weather_widget_recovery"
    private const val INTERVAL = 30L * 60_000L
    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 7117,
        Intent(c, WeatherWidgetRecoveryReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(c: Context, fired: Boolean = false) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (WeatherWidgetStore.ids(c).isEmpty()) {
            c.getSystemService(AlarmManager::class.java).cancel(pending(c))
            p.edit().remove("next").apply()
            return
        }
        val at = WeatherReminderTiming.recoveryAt(System.currentTimeMillis(), p.getLong("next", 0L), INTERVAL, fired)
        WeatherAlarmCompat.schedule(c, at, pending(c), exact = false)
        p.edit().putLong("next", at).apply()
    }
    fun nextAt(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("next", 0L)
}

internal object WeatherReminderRecovery {
    /** Only local cache and scheduling here; network is delegated to unique constrained work. */
    fun repair(c: Context) {
        WeatherNotificationCenter.updateSchedules(c)
        WeatherBackgroundScheduler.schedule(c)
        WeatherCityStore.currentLocationCity(c)?.let { WeatherReminderPreparation.prepareFromCache(c, it) }
        WeatherNotificationCenter.reconcilePendingTemperature(c)
        if (WeatherSettings.dailyForecastReminder(c)) {
            val tomorrow = WeatherReminderPolicy.window().tomorrow
            if (!WeatherNotificationCenter.postDailyForecast(c, tomorrow))
                WeatherBackgroundScheduler.enqueueDailyForecast(c, tomorrow)
        }
        if (WeatherRiskRecoveryAlarm.isEnabled(c)) WeatherBackgroundScheduler.enqueueRiskCheck(c)
    }
}
