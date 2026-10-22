package com.example.weatherapp

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 统一处理冷启动和设置页开关需要的权限与系统特殊访问。 */
class WeatherPermissionCoordinator(private val activity: ComponentActivity) {
    enum class ReminderType { DISASTER, TEMPERATURE, WIND, RAIN, DAILY }
    private enum class Need { FOREGROUND_LOCATION, NOTIFICATIONS, BACKGROUND_LOCATION, EXACT_ALARM }

    private val prefs = activity.getSharedPreferences("weather_permission_state", Context.MODE_PRIVATE)
    private var needs: List<Need> = emptyList()
    private var attempted = mutableSetOf<Need>()
    private var completion: ((Boolean) -> Unit)? = null
    private var waitingForSystemSettings = false

    private val foregroundLocation = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { finishNeed() }
    private val notification = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { finishNeed() }
    private val backgroundLocation = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { finishNeed() }

    fun requestColdStart(onFinished: (foregroundGranted: Boolean) -> Unit) {
        // 首次启动只申请界面确实需要的权限；后台定位与精确闹钟不再阻断天气浏览。
        start(listOf(Need.FOREGROUND_LOCATION, Need.NOTIFICATIONS)) {
            onFinished(hasForegroundLocation(activity))
        }
    }

    fun requestForReminder(type: ReminderType, onFinished: (Boolean) -> Unit) {
        val required = requirementsFor(type)
        start(required) { onFinished(required.all(::granted)) }
    }

    fun canEnableReminder(type: ReminderType): Boolean = requirementsFor(type).all(::granted)

    private fun requirementsFor(type: ReminderType): List<Need> = when (type) {
            // 精确闹钟未授权时仍登记普通闹钟，不应让每日预报开关失效。
            ReminderType.DAILY -> listOf(Need.NOTIFICATIONS)
            // 风险检查可使用最后一次保存的当前位置；后台定位只提升位置自动更新能力。
            ReminderType.DISASTER, ReminderType.TEMPERATURE, ReminderType.WIND, ReminderType.RAIN -> listOf(Need.NOTIFICATIONS)
        }

    fun onResume() {
        if (!waitingForSystemSettings) return
        waitingForSystemSettings = false
        continueFlow()
    }

    private fun start(required: List<Need>, done: (Boolean) -> Unit) {
        needs = required
        attempted = mutableSetOf()
        completion = done
        continueFlow()
    }

    private fun continueFlow() {
        val next = needs.firstOrNull { !granted(it) && it !in attempted }
        if (next == null) {
            val done = completion
            completion = null
            done?.invoke(needs.all(::granted))
            return
        }
        attempted += next
        showExplanation(next)
    }

    private fun showExplanation(need: Need) {
        val (title, message) = when (need) {
            Need.FOREGROUND_LOCATION -> "允许定位" to "定位用于显示当前位置天气。拒绝后仍可使用手动添加的城市，但无法自动定位到你所在城市。"
            Need.NOTIFICATIONS -> "允许天气通知" to "通知用于灾害、降雨及每日天气预报提醒。拒绝后相关开关不能开启，App 不会在系统通知栏提示天气变化。"
            Need.BACKGROUND_LOCATION -> "允许后台定位" to "后台定位会提升当前位置更新能力；不允许时，提醒仍会使用上次保存的当前位置。"
            Need.EXACT_ALARM -> "允许闹钟和提醒" to "用于尽量在每天 07:00 与 22:00 发送天气预报。未允许时仍会登记普通系统闹钟，但可能被省电策略延后。"
        }
        // 运行时授权页由 Android 系统绘制，应用不能替换；这里的授权前说明使用
        // Material You 弹窗，使圆角、按钮和配色跟随当前手机主题。
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("暂不允许") { _, _ -> continueFlow() }
            .setPositiveButton(if (shouldOpenSettings(need)) "前往设置" else "继续") { _, _ -> requestNeed(need) }
            .setOnCancelListener { continueFlow() }
            .show()
    }

    private fun requestNeed(need: Need) {
        // 用户永久拒绝定位/通知时，重复调用运行时授权不会再显示系统弹窗；直接进入应用详情页。
        if ((need == Need.FOREGROUND_LOCATION || need == Need.NOTIFICATIONS) && shouldOpenSettings(need)) {
            waitingForSystemSettings = true
            activity.startActivity(
                if (need == Need.NOTIFICATIONS) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                    }
                }
            )
            return
        }
        when (need) {
            Need.FOREGROUND_LOCATION -> {
                markAsked(Manifest.permission.ACCESS_FINE_LOCATION)
                foregroundLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            Need.NOTIFICATIONS -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                finishNeed()
            } else {
                markAsked(Manifest.permission.POST_NOTIFICATIONS)
                notification.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            Need.BACKGROUND_LOCATION -> when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !hasForegroundLocation(activity) -> finishNeed()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    waitingForSystemSettings = true
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                    })
                }
                else -> {
                    markAsked(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            Need.EXACT_ALARM -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms(activity)) {
                finishNeed()
            } else {
                waitingForSystemSettings = true
                activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                })
            }
        }
    }

    private fun finishNeed() = continueFlow()

    private fun shouldOpenSettings(need: Need): Boolean = when (need) {
        Need.BACKGROUND_LOCATION -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        Need.EXACT_ALARM -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        Need.FOREGROUND_LOCATION -> permanentlyDenied(Manifest.permission.ACCESS_FINE_LOCATION)
        Need.NOTIFICATIONS ->
            !WeatherNotificationCenter.canNotify(activity) &&
                (WeatherNotificationCenter.hasRuntimeNotificationPermission(activity) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && permanentlyDenied(Manifest.permission.POST_NOTIFICATIONS)))
    }

    private fun permanentlyDenied(permission: String): Boolean =
        prefs.getBoolean("asked_$permission", false) && !activity.shouldShowRequestPermissionRationale(permission) &&
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED

    private fun markAsked(permission: String) = prefs.edit().putBoolean("asked_$permission", true).apply()

    private fun granted(need: Need): Boolean = when (need) {
        Need.FOREGROUND_LOCATION -> hasForegroundLocation(activity)
        Need.NOTIFICATIONS -> WeatherNotificationCenter.canNotify(activity)
        Need.BACKGROUND_LOCATION -> hasBackgroundLocation(activity)
        Need.EXACT_ALARM -> canScheduleExactAlarms(activity)
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
