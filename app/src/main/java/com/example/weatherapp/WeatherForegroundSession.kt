package com.example.weatherapp

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Count all app screens, not MainActivity stops: a detail transition is not backgrounding. */
class ForegroundSessionCounter {
    var session: Long = 0; private set
    private var started = 0
    private var backgrounded = false
    fun activityStarted() {
        if (started == 0 && (session == 0L || backgrounded)) session++
        started++; backgrounded = false
    }
    fun activityStopped(changingConfiguration: Boolean = false) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !changingConfiguration) backgrounded = true
    }
}

object WeatherForegroundSession : Application.ActivityLifecycleCallbacks {
    private val counter = ForegroundSessionCounter()
    val current get() = counter.session
    override fun onActivityStarted(activity: Activity) = counter.activityStarted()
    override fun onActivityStopped(activity: Activity) = counter.activityStopped(activity.isChangingConfigurations)
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
