package com.example.weatherapp

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Opens the most specific system page that this device lets ordinary apps launch. */
internal class WeatherPermissionSettings(private val activity: Activity) {
    private val packageUri get() = Uri.fromParts("package", activity.packageName, null)

    fun openDeviceLocation(): Boolean = open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) ||
        unavailable("请在系统设置中开启「位置信息」")

    fun openAppLocation(): Boolean {
        val location = permissionIntent("android.intent.action.MANAGE_APP_PERMISSION").apply {
            putExtra("android.intent.extra.PERMISSION_NAME", Manifest.permission.ACCESS_FINE_LOCATION)
            putExtra("android.intent.extra.PERMISSION_GROUP_NAME", Manifest.permission_group.LOCATION)
        }
        if (open(location)) return true
        if (open(permissionIntent("android.intent.action.MANAGE_APP_PERMISSIONS")) ||
            open(permissionIntent("android.settings.APP_PERMISSIONS_SETTINGS"))
        ) {
            hint("请选择「位置信息」")
            return true
        }
        return openAppDetails("请选择「权限 → 位置信息」")
    }

    fun openNotifications(): Boolean = open(notificationIntent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)) ||
        openAppDetails("请选择「通知」")

    fun openChannel(channel: String, label: String): Boolean {
        if (open(notificationIntent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            })) return true
        if (open(notificationIntent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))) {
            hint("请选择「$label」")
            return true
        }
        return openAppDetails("请选择「通知 → $label」")
    }

    fun openExactAlarm(): Boolean =
        open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)) ||
            unavailable("请在系统设置的「特殊应用权限」中打开「闹钟和提醒」")

    private fun permissionIntent(action: String) =
        Intent(action).putExtra(Intent.EXTRA_PACKAGE_NAME, activity.packageName)

    private fun notificationIntent(action: String) = Intent(action).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        // Android 7 and some manufacturer Settings builds read the legacy extras.
        putExtra("app_package", activity.packageName)
        putExtra("app_uid", activity.applicationInfo.uid)
    }

    private fun openAppDetails(path: String): Boolean {
        if (!open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))) {
            return unavailable("请在系统设置中打开「云松天气」的应用信息")
        }
        hint(path)
        return true
    }

    private fun open(intent: Intent): Boolean {
        val handlers = activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (handler in handlers) {
            val target = handler.activityInfo ?: continue
            if (!target.exported || target.applicationInfo.flags and SYSTEM_APP_FLAGS == 0) continue
            val permission = target.permission
            if (permission != null && activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) continue
            try {
                activity.startActivity(Intent(intent).setComponent(ComponentName(target.packageName, target.name)))
                return true
            } catch (_: ActivityNotFoundException) {
                // Settings can disappear between the query and launch.
            } catch (_: SecurityException) {
                // Some ROMs enforce a caller check in addition to the manifest.
            }
        }
        return false
    }

    private fun hint(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun unavailable(message: String): Boolean {
        hint(message)
        return false
    }

    private companion object {
        const val SYSTEM_APP_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}
