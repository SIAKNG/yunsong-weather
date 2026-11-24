package com.example.weatherapp

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 统一处理首页定位和设置中的天气提醒权限。 */
class WeatherPermissionCoordinator(private val activity: ComponentActivity) {
    enum class ReminderType(internal val channelId: String, internal val channelLabel: String) {
        DISASTER(WeatherNotificationCenter.CHANNEL_ALERT, "灾害预警"),
        TEMPERATURE(WeatherNotificationCenter.CHANNEL_CHANGE, "天气变化提醒"),
        WIND(WeatherNotificationCenter.CHANNEL_CHANGE, "天气变化提醒"),
        RAIN(WeatherNotificationCenter.CHANNEL_RAIN, "降雨提醒"),
        DAILY(WeatherNotificationCenter.CHANNEL_FORECAST, "每日天气预报")
    }

    private enum class Need { FOREGROUND_LOCATION, DEVICE_LOCATION, NOTIFICATIONS, REMINDER_CHANNEL }

    private val settings = WeatherPermissionSettings(activity)
    private val prefs = activity.getSharedPreferences("weather_permission_state", Context.MODE_PRIVATE)
    private var needs: List<Need> = emptyList()
    private val attempted = mutableSetOf<Need>()
    private var completion: ((Boolean) -> Unit)? = null
    private var reminder: ReminderType? = null
    private var waitingForSystemSettings = false

    private val foregroundLocation = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { continueFlow() }
    private val notification = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { continueFlow() }

    fun requestColdStart(onLocationReady: () -> Unit, onFinished: () -> Unit) {
        requestForegroundLocation {
            onLocationReady()
            requestNotifications { onFinished() }
        }
    }

    fun requestForegroundLocation(onFinished: (Boolean) -> Unit) {
        start(listOf(Need.FOREGROUND_LOCATION, Need.DEVICE_LOCATION), done = onFinished)
    }

    fun requestNotifications(onFinished: (Boolean) -> Unit) {
        start(listOf(Need.NOTIFICATIONS), done = onFinished)
    }

    fun requestForReminder(type: ReminderType, onFinished: (Boolean) -> Unit) {
        WeatherNotificationCenter.ensureChannels(activity)
        start(listOf(Need.NOTIFICATIONS, Need.REMINDER_CHANNEL), type, onFinished)
    }

    fun canEnableReminder(type: ReminderType): Boolean {
        WeatherNotificationCenter.ensureChannels(activity)
        return WeatherNotificationCenter.canNotifyChannel(activity, type.channelId)
    }

    fun onResume() {
        if (!waitingForSystemSettings) return
        waitingForSystemSettings = false
        continueFlow()
    }

    private fun start(required: List<Need>, type: ReminderType? = null, done: (Boolean) -> Unit) {
        needs = required
        reminder = type
        attempted.clear()
        completion = done
        continueFlow()
    }

    private fun continueFlow() {
        val next = needs.firstOrNull { it !in attempted && !granted(it) && prerequisitesMet(it) }
        if (next == null) {
            val done = completion
            val allowed = needs.all(::granted)
            completion = null
            reminder = null
            done?.invoke(allowed)
            return
        }
        attempted += next
        showExplanation(next)
    }

    private fun prerequisitesMet(need: Need): Boolean = when (need) {
        Need.DEVICE_LOCATION -> hasForegroundLocation(activity)
        Need.REMINDER_CHANNEL -> WeatherNotificationCenter.canNotify(activity)
        else -> true
    }

    private fun showExplanation(need: Need) {
        val (title, message) = when (need) {
            Need.FOREGROUND_LOCATION -> "允许定位" to "允许云松天气使用位置信息后，可查看当前位置的天气。你也可以手动添加城市。"
            Need.DEVICE_LOCATION -> "开启系统定位" to "云松天气已有定位权限，请打开手机的「位置信息」开关。"
            Need.NOTIFICATIONS -> "允许天气通知" to "打开云松天气的通知开关，即可接收预警、降雨和每日天气预报等提醒。"
            Need.REMINDER_CHANNEL -> "开启${reminder?.channelLabel}" to "云松天气的通知已开启，请再打开「${reminder?.channelLabel}」的通知开关。"
        }
        val openSettings = shouldOpenSettings(need)
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("暂不允许") { _, _ -> continueFlow() }
            .setPositiveButton(if (openSettings) "前往设置" else "继续") { _, _ -> requestNeed(need, openSettings) }
            .setOnCancelListener { continueFlow() }
            .create().showFrosted(activity)
    }

    private fun requestNeed(need: Need, openSettings: Boolean) {
        if (openSettings) {
            waitingForSystemSettings = when (need) {
                Need.FOREGROUND_LOCATION -> settings.openAppLocation()
                Need.DEVICE_LOCATION -> settings.openDeviceLocation()
                Need.NOTIFICATIONS -> settings.openNotifications()
                Need.REMINDER_CHANNEL -> reminder?.let { settings.openChannel(it.channelId, it.channelLabel) } == true
            }
            if (!waitingForSystemSettings) continueFlow()
            return
        }
        when (need) {
            Need.FOREGROUND_LOCATION -> {
                markAsked(Manifest.permission.ACCESS_FINE_LOCATION)
                foregroundLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            Need.NOTIFICATIONS -> {
                markAsked(Manifest.permission.POST_NOTIFICATIONS)
                notification.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> continueFlow()
        }
    }

    private fun shouldOpenSettings(need: Need): Boolean = when (need) {
        Need.FOREGROUND_LOCATION -> permanentlyDenied(Manifest.permission.ACCESS_FINE_LOCATION)
        Need.NOTIFICATIONS -> WeatherNotificationCenter.hasRuntimeNotificationPermission(activity) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && permanentlyDenied(Manifest.permission.POST_NOTIFICATIONS))
        Need.DEVICE_LOCATION, Need.REMINDER_CHANNEL -> true
    }

    private fun permanentlyDenied(permission: String): Boolean =
        prefs.getBoolean("asked_$permission", false) && !activity.shouldShowRequestPermissionRationale(permission) &&
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED

    private fun markAsked(permission: String) = prefs.edit().putBoolean("asked_$permission", true).apply()

    private fun granted(need: Need): Boolean = when (need) {
        Need.FOREGROUND_LOCATION -> hasForegroundLocation(activity)
        Need.DEVICE_LOCATION -> LocationManagerCompat.isLocationEnabled(activity.getSystemService(LocationManager::class.java))
        Need.NOTIFICATIONS -> WeatherNotificationCenter.canNotify(activity)
        Need.REMINDER_CHANNEL -> reminder?.let { WeatherNotificationCenter.canNotifyChannel(activity, it.channelId) } == true
    }

    companion object {
        fun hasForegroundLocation(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasBackgroundLocation(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        fun canScheduleExactAlarms(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
