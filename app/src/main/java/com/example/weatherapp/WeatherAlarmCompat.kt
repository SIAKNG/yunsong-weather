package com.example.weatherapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/** Permission may be revoked between checking and scheduling; retain an inexact fallback. */
internal object WeatherAlarmCompat {
    fun schedule(c: Context, at: Long, pending: PendingIntent, exact: Boolean = true): Boolean {
        val alarm = c.getSystemService(AlarmManager::class.java)
        if (exact && (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms())) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return true
            } catch (_: SecurityException) { /* Reconcile with the current permission below. */ }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        return false
    }
}
