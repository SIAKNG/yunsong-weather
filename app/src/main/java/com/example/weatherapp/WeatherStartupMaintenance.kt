package com.example.weatherapp

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Process-owned maintenance must not block theme setup or the first weather frame. */
internal object WeatherStartupMaintenance {
    data class Timing(val channelsMs: Long,val alarmsMs: Long,val workersMs: Long,val ranOnMain: Boolean,val complete: Boolean)
    @Volatile var lastTiming: Timing?=null;private set
    private var scheduled: Future<*>?=null
    private val worker=Executors.newSingleThreadExecutor { task ->
        Thread({android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);task.run()},"weather-startup-maintenance")
    }
    @Synchronized fun schedule(context: Context): Future<*> {
        scheduled?.let{return it}
        val app=context.applicationContext
        return worker.submit {
            val main=Looper.myLooper()==Looper.getMainLooper()
            var channels=0L;var alarms=0L;var jobs=0L;var complete=false
            try {
                var start=SystemClock.elapsedRealtime()
                // Keep the previous order. Delivery paths also ensure their own channels.
                WeatherNotificationCenter.ensureChannels(app);channels=SystemClock.elapsedRealtime()-start
                start=SystemClock.elapsedRealtime();WeatherNotificationCenter.updateSchedules(app);alarms=SystemClock.elapsedRealtime()-start
                start=SystemClock.elapsedRealtime();WeatherBackgroundScheduler.schedule(app);jobs=SystemClock.elapsedRealtime()-start
                complete=true
            } catch (_: Exception) {
                Log.w("WeatherStartup","Maintenance incomplete; settings/receivers or the next process start can retry")
            } finally {
                lastTiming=Timing(channels,alarms,jobs,main,complete)
                Log.i("WeatherStartup","maintenance channelsMs=$channels alarmsMs=$alarms workersMs=$jobs main=$main complete=$complete")
            }
        }.also{scheduled=it}
    }
}
