package com.example.weatherapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * 风险通知的闹钟兜底。WorkManager 仍负责约束和失败重试；此接收器在应用进程
 * 被正常回收后重新唤醒同一条唯一任务，避免只依赖厂商对周期任务的调度。
 */
class WeatherRiskRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WeatherRiskRecoveryAlarm.schedule(context, replace = true)
        if (WeatherRiskRecoveryAlarm.isEnabled(context)) {
            WeatherBackgroundScheduler.enqueueRiskCheck(context, expedited = true)
        }
    }
}

object WeatherRiskRecoveryAlarm {
    private const val PREFS = "weather_risk_recovery_alarm_v1"
    private const val KEY_NEXT = "next"
    private const val REQUEST_CODE = 7_115
    private const val INTERVAL_MS = 15L * 60_000L

    fun isEnabled(context: Context): Boolean = (
        WeatherSettings.disasterReminder(context) ||
            WeatherSettings.rainReminder(context) ||
            WeatherSettings.temperatureChangeReminder(context) ||
            WeatherSettings.strongWindReminder(context)
        ) && !WeatherPushGateway.isCloudRiskDeliveryActive(context)

    fun schedule(context: Context, replace: Boolean = false) {
        if (!isEnabled(context)) {
            cancel(context)
            return
        }
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = now + INTERVAL_MS
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context)
        // The persisted time is only a UI/diagnostic hint, not proof that Android still
        // owns the alarm. Package updates and some vendor cleanup paths remove alarms
        // while keeping SharedPreferences, so every scheduler reconciliation re-registers
        // this one stable PendingIntent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        prefs.edit().putLong(KEY_NEXT, at).apply()
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_NEXT).apply()
    }

    internal fun nextScheduledAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_NEXT, 0L)

    internal fun pendingIntentExists(context: Context): Boolean = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WeatherRiskRecoveryReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    ) != null

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WeatherRiskRecoveryReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * 变温提醒有明确的“前一晚 21:00”起点，因此不依赖周期任务的随机相位。
 * 闹钟只唤醒一次风险检查；失败后的持续重试仍交给现有 15 分钟任务与风险兜底闹钟。
 */
class TemperatureChangeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TemperatureChangeReminderAlarm.schedule(context, replace = true)
        if (TemperatureChangeReminderAlarm.isEnabled(context)) {
            WeatherBackgroundScheduler.enqueueRiskCheck(context, expedited = true)
        }
    }
}

object TemperatureChangeReminderAlarm {
    private const val PREFS = "temperature_change_alarm_v1"
    private const val KEY_NEXT = "next"
    private const val REQUEST_CODE = 7_116

    fun isEnabled(context: Context): Boolean = WeatherSettings.temperatureChangeReminder(context) &&
        !WeatherPushGateway.isCloudRiskDeliveryActive(context)

    fun schedule(context: Context, replace: Boolean = false) {
        if (!isEnabled(context)) {
            cancel(context)
            return
        }
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context)
        // As with the 15-minute recovery alarm, do not treat a surviving preference as
        // evidence that the operating system retained the actual alarm after an update.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        prefs.edit().putLong(KEY_NEXT, at).apply()
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_NEXT).apply()
    }

    internal fun nextScheduledAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_NEXT, 0L)

    internal fun pendingIntentExists(context: Context): Boolean = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, TemperatureChangeReminderReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    ) != null

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, TemperatureChangeReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
