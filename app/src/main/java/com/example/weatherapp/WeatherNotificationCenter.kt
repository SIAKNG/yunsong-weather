package com.example.weatherapp

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * App 内唯一允许投递天气通知的入口。
 *
 * 页面渲染只能展示天气数据，不能调用这里的风险通知方法；风险任务在后台通过
 * [reconcileAlerts] 与 [reconcileRain] 把本次结果交给持久化台账比较后再投递。
 */
object WeatherNotificationCenter {
    const val CHANNEL_ALERT = "weather_alerts"
    const val CHANNEL_RAIN = "weather_rain_v1"
    const val CHANNEL_FORECAST = "weather_forecast_v3"
    const val CHANNEL_CHANGE = "weather_changes_v1"

    const val ACTION_DAILY_TODAY = "com.example.weatherapp.DAILY_FORECAST_TODAY"
    const val ACTION_DAILY_TOMORROW = "com.example.weatherapp.DAILY_FORECAST_TOMORROW"

    // 旧版曾把多条预警折叠为一条摘要；保留 ID 仅用于升级后撤销那条旧摘要通知。
    private const val LEGACY_ALERT_GROUP_SUMMARY_ID = 2_991
    private const val RAIN_NOTIFICATION_ID = 2_001
    private const val TODAY_NOTIFICATION_ID = 3_001
    private const val TOMORROW_NOTIFICATION_ID = 3_002
    private const val TEST_ALERT_NOTIFICATION_ID = 9_101
    private const val TEST_RAIN_NOTIFICATION_ID = 9_102
    private const val TEST_FORECAST_NOTIFICATION_ID = 9_103
    private const val TEST_CHANGE_NOTIFICATION_ID = 9_104
    private const val REQUEST_DAILY_TODAY = 700
    private const val REQUEST_DAILY_TOMORROW = 2_200

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ALERT, "灾害预警", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "台风、暴雨、高温等气象灾害预警"
                },
                NotificationChannel(CHANNEL_RAIN, "降雨提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "未来两小时可能出现的降雨提醒"
                },
                NotificationChannel(CHANNEL_FORECAST, "每日天气预报", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "每日 07:00 当日预报和 22:00 次日预报"
                },
                NotificationChannel(CHANNEL_CHANGE, "天气变化提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "变温、大风与极端天气提醒"
                }
            )
        )
    }

    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun hasRuntimeNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun canNotifyChannel(context: Context, channel: String): Boolean = canNotify(context) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(channel)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true)

    /** 统一比较本次预警快照，并只对新增或升级预警发出声音。 */
    fun reconcileAlerts(
        context: Context,
        city: CityPage,
        events: List<WeatherAlertEvent>
    ): AlertReconcileResult {
        ensureChannels(context)
        if (!WeatherSettings.disasterReminder(context) || !canNotifyChannel(context, CHANNEL_ALERT))
            return AlertReconcileResult(emptyList(), emptyList(), events.size)
        val result = WeatherNotificationLedger.reconcileAlerts(context, city, events) { delivery ->
            val event = delivery.event
            notify(context, event.notificationId, CHANNEL_ALERT, event.title,
                event.detail.ifBlank { "请留意当地天气变化" },
                onlyAlertOnce = delivery.delivery == AlertDelivery.QUIET_UPDATE,
                alertAgain = delivery.delivery == AlertDelivery.ESCALATED)
        }
        val notifications = NotificationManagerCompat.from(context)
        result.cancelledNotificationIds.forEach(notifications::cancel)

        // 每条预警都独立显示标题和详情，不再用“X 条气象预警”摘要折叠。
        notifications.cancel(LEGACY_ALERT_GROUP_SUMMARY_ID)
        return result
    }

    /** 同一地点、同一场降雨只保留一条通知；新增或增强才提醒。 */
    fun reconcileRain(
        context: Context,
        event: WeatherRainEvent?
    ): RainReconcileResult {
        ensureChannels(context)
        if (!WeatherSettings.rainReminder(context) || !canNotifyChannel(context, CHANNEL_RAIN))
            return RainReconcileResult(null)
        val result = if (event == null) {
            RainReconcileResult(null, WeatherNotificationLedger.clearRain(context))
        } else {
            WeatherNotificationLedger.reconcileRain(context, event) { delivery ->
                notify(context, event.notificationId, CHANNEL_RAIN, event.notificationTitle, event.notificationText,
                    onlyAlertOnce = delivery == RainDelivery.QUIET_UPDATE, alertAgain = delivery == RainDelivery.ESCALATED)
            }
        }
        val notifications = NotificationManagerCompat.from(context)
        // 清理旧版本使用的全局 2001 降雨通知；新台账从此按地点使用稳定 ID。
        notifications.cancel(RAIN_NOTIFICATION_ID)
        result.cancelledNotificationIds.forEach(notifications::cancel)
        return result
    }

    /** 预报变化使用持久台账；同一内容在一次成功投递后不会再次发送。 */
    fun reconcileWeatherChange(context: Context, event: WeatherChangeEvent) {
        ensureChannels(context)
        val enabled = when (event.type) {
            "temperature" -> WeatherSettings.temperatureChangeReminder(context)
            "wind" -> WeatherSettings.strongWindReminder(context)
            else -> false
        }
        if (!enabled) {
            if (event.type == "temperature") TemperatureChangeDeliveryLedger.clear(context)
            return
        }
        if (event.type == "temperature") {
            TemperatureChangeDeliveryLedger.stage(context, event)
            reconcilePendingTemperature(context)
            return
        }
        if (!canNotifyChannel(context, CHANNEL_CHANGE)) return
        if (event.type == "wind") {
            WindReminderDelivery.deliver(context, event.city, "${event.title}\u0000${event.detail}") {
                notify(context, 4400 + WeatherNotificationLedger.cityKey(event.city).stablePositiveHash(),
                    CHANNEL_CHANGE, event.title, event.detail, alertAgain = true)
            }
            return
        }
        if (!WeatherChangeNotificationLedger.shouldDeliver(context, event)) return
        if (notify(context, event.notificationId, CHANNEL_CHANGE, event.title, event.detail, alertAgain = true))
            WeatherChangeNotificationLedger.markDelivered(context, event)
    }

    /** 风险任务每次运行都会调用，直至前一晚发现的变温事件成功投递。 */
    fun reconcilePendingTemperature(context: Context) {
        ensureChannels(context)
        if (!WeatherSettings.temperatureChangeReminder(context)) {
            TemperatureChangeDeliveryLedger.clear(context)
            return
        }
        if (!canNotifyChannel(context, CHANNEL_CHANGE)) return
        TemperatureChangeDeliveryLedger.deliverDue(context) { pending ->
            notify(context, pending.notificationId, CHANNEL_CHANGE, pending.title, pending.detail, alertAgain = true)
        }
    }

    /**
     * 仅供旧版自动化测试和调试调用。业务代码不得使用；请改为 [reconcileAlerts]。
     */
    @Deprecated("Use reconcileAlerts from WeatherRiskWorker")
    fun postAlert(context: Context, key: String, title: String, level: String, detail: String) {
        if (!WeatherSettings.disasterReminder(context) || !canNotify(context)) return
        val type = WeatherAlertPresentation.normalizedType(title)
        val localizedLevel = WeatherAlertPresentation.levelName(null, level, title)
        notify(
            context,
            1_000 + key.stablePositiveHash(),
            CHANNEL_ALERT,
            "${type}${localizedLevel}预警",
            WeatherAlertPresentation.cleanDetail(detail)
        )
    }

    /** 仅供旧版自动化测试和调试调用。业务代码不得使用；请改为 [reconcileRain]。 */
    @Deprecated("Use reconcileRain from WeatherRiskWorker")
    fun postRain(context: Context, windowKey: Long, summary: String, peak: String) {
        if (!WeatherSettings.rainReminder(context) || !canNotify(context)) return
        notify(context, RAIN_NOTIFICATION_ID, CHANNEL_RAIN, "降雨提醒", "$summary $peak".take(260))
    }

    /** 每日提醒使用已缓存数据立即送达，随后后台刷新只会静默更新。 */
    @Synchronized
    fun postDailyForecast(context: Context, tomorrow: Boolean): Boolean {
        ensureChannels(context)
        updateSchedules(context)
        if (WeatherReminderPolicy.window().tomorrow != tomorrow) return true
        if (DailyForecastDeliveryLedger.delivered(context, tomorrow)) return true
        if (!WeatherSettings.dailyForecastReminder(context) || !canNotifyChannel(context, CHANNEL_FORECAST)) {
            WeatherReminderDiagnostics.record(context, "每日预报", "未投递", "通知权限或每日预报开关未开启")
            return false
        }
        val page = WeatherCityStore.currentLocationCity(context)
        if (page == null) {
            WeatherReminderDiagnostics.record(context, "每日预报", "未投递", "暂无已保存的当前位置")
            return false
        }
        val message = loadDailyMessage(context, page, if (tomorrow) 1 else 0) ?: return false
        val sent = notify(
            context = context,
            id = if (tomorrow) TOMORROW_NOTIFICATION_ID else TODAY_NOTIFICATION_ID,
            channelId = CHANNEL_FORECAST,
            title = "${page.name}${if (WeatherReminderPolicy.window().forecastDate == java.time.LocalDate.now()) " 今日" else " 明日"}天气",
            text = message,
            onlyAlertOnce = false
        )
        if (!sent) return false
        DailyForecastDeliveryLedger.markDelivered(context, tomorrow)
        WeatherReminderDiagnostics.record(context, "每日预报", "已投递", if (tomorrow) "22:00 次日预报" else "07:00 当日预报")
        return true
    }

    /** 设置页诊断按钮：不依赖天气接口，直接验证四个通道是否能显示。 */
    fun postTestNotifications(context: Context): Boolean {
        ensureChannels(context)
        if (!canNotify(context)) return false
        notify(context, TEST_ALERT_NOTIFICATION_ID, CHANNEL_ALERT, "暴雨黄色预警", "测试通知：灾害预警通道工作正常")
        notify(context, TEST_RAIN_NOTIFICATION_ID, CHANNEL_RAIN, "降雨提醒", "测试通知：未来两小时可能有中雨")
        notify(context, TEST_FORECAST_NOTIFICATION_ID, CHANNEL_FORECAST, "今日天气预报", "测试通知：晴，20～28℃")
        notify(context, TEST_CHANGE_NOTIFICATION_ID, CHANNEL_CHANGE, "天气变化提醒", "测试通知：变温与大风通道工作正常")
        WeatherReminderDiagnostics.record(context, "通知测试", "已发送", "灾害、降雨、每日预报、天气变化四个样例")
        return true
    }

    @Synchronized fun updateSchedules(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val shouldSchedule = WeatherSettings.dailyForecastReminder(context)
        if (!shouldSchedule) {
            cancelDaily(context, alarm, true)
            cancelDaily(context, alarm, false)
            return
        }
        scheduleDaily(context, alarm, true, 7)
        scheduleDaily(context, alarm, false, 22)
    }

    /** 兼容旧调用点，统一改为登记早晚两次预报。 */
    fun updateMorningSchedule(context: Context) = updateSchedules(context)

    /** 设置页中可读的、不会泄露地点或 Token 的本机通知状态。 */
    fun diagnosticsText(context: Context): String {
        ensureChannels(context)
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val notificationState = if (canNotify(context)) "已允许" else "未允许"
        val exactAlarmState = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "系统不需要单独授权"
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() -> "已允许"
            else -> "未允许（将使用可能延后的系统闹钟）"
        }
        val backgroundLocation = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) "已允许" else "未允许（仍会使用上次保存的位置）"
        val batteryState = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            "系统不需要单独设置"
        } else {
            val manager = context.getSystemService(PowerManager::class.java)
            if (manager.isIgnoringBatteryOptimizations(context.packageName)) "未限制"
            else "受电池优化限制（建议在系统设置中设为不受限制）"
        }
        return buildString {
            append("通知权限：").append(notificationState).append('\n')
            append("通道：灾害 ").append(channelState(context, CHANNEL_ALERT))
            append("｜降雨 ").append(channelState(context, CHANNEL_RAIN))
            append("｜每日预报 ").append(channelState(context, CHANNEL_FORECAST))
            append("｜天气变化 ").append(channelState(context, CHANNEL_CHANGE)).append('\n')
            append("精确闹钟：").append(exactAlarmState).append('\n')
            append("后台定位：").append(backgroundLocation).append('\n')
            append("后台运行：").append(batteryState).append('\n')
            append(WeatherReminderDiagnostics.scheduleSummary(context)).append('\n')
            val riskAt = WeatherRiskRecoveryAlarm.nextScheduledAt(context)
            append("下次风险兜底：").append(if (riskAt > System.currentTimeMillis()) formatter.format(Date(riskAt)) else "未排程").append('\n')
            append("下次每日预报：07:00 ").append(formatter.format(Date(nextDailyMillis(7))))
            append("；22:00 ").append(formatter.format(Date(nextDailyMillis(22)))).append('\n')
            append(WeatherReminderDiagnostics.summary(context))
        }
    }

    private fun channelState(context: Context, channelId: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "可用"
        val importance = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(channelId)?.importance ?: NotificationManager.IMPORTANCE_NONE
        return if (importance == NotificationManager.IMPORTANCE_NONE) "已关闭" else "已开启"
    }

    private fun scheduleDaily(context: Context, alarm: AlarmManager, today: Boolean, hour: Int) {
        val pending = dailyForecastPendingIntent(context, today)
        val triggerAt = nextDailyMillis(hour)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun cancelDaily(context: Context, alarm: AlarmManager, today: Boolean) {
        alarm.cancel(dailyForecastPendingIntent(context, today))
    }

    internal fun dailyAlarmExists(context: Context, today: Boolean): Boolean = PendingIntent.getBroadcast(
        context,
        if (today) REQUEST_DAILY_TODAY else REQUEST_DAILY_TOMORROW,
        Intent(context, MorningWeatherReceiver::class.java).setAction(if (today) ACTION_DAILY_TODAY else ACTION_DAILY_TOMORROW),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    ) != null

    private fun dailyForecastPendingIntent(context: Context, today: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (today) REQUEST_DAILY_TODAY else REQUEST_DAILY_TOMORROW,
        Intent(context, MorningWeatherReceiver::class.java).setAction(if (today) ACTION_DAILY_TODAY else ACTION_DAILY_TOMORROW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun nextDailyMillis(hour: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    private fun loadDailyMessage(context: Context, page: CityPage, index: Int): String? {
        val primary = WeatherCache.loadCaiyun(context, page)?.result
        val targetDate = WeatherReminderPolicy.window().forecastDate.toString()
        return if (primary?.daily?.temperature.orEmpty().none { it.date?.take(10) == targetDate }) {
            val bundle = WeatherCache.loadQWeather(context, page)
            val daily = bundle?.daily?.firstOrNull { it.date.take(10) == targetDate } ?: return null
            val range = listOfNotNull(daily.low?.let { WeatherSettings.tempText(context, it) }, daily.high?.let { WeatherSettings.tempText(context, it) })
                .joinToString("–")
            "${WeatherText.skyconToText(daily.skycon)}${if (range.isBlank()) "" else "，${range}"}。${tomorrowAdvice(daily.precipProb ?: 0.0)}"
        } else {
            val result = primary ?: return null
            val daily = result.daily ?: return null
            val targetDate = WeatherReminderPolicy.window().forecastDate.toString()
            val weather = daily.temperature?.firstOrNull { it.date?.take(10) == targetDate } ?: return null
            val range = listOfNotNull(weather.min?.let { WeatherSettings.tempText(context, it) }, weather.max?.let { WeatherSettings.tempText(context, it) })
                .joinToString("–")
            val skycon = daily.skycon?.firstOrNull { it.date?.take(10) == targetDate }?.value
            val precip = daily.precipitation?.firstOrNull { it.date?.take(10) == targetDate }?.probability ?: 0.0
            "${WeatherText.skyconToText(skycon)}${if (range.isBlank()) "" else "，${range}"}。${tomorrowAdvice(precip)}"
        }
    }

    private fun tomorrowAdvice(precipProbability: Double): String = when {
        precipProbability >= 50.0 -> "有较高降雨可能，提前备好雨具并留意路面湿滑。"
        precipProbability >= 20.0 -> "可能有短时降雨，出门建议随身带伞。"
        else -> "天气较平稳，按温差增减衣物并适当补水。"
    }

    private fun notify(
        context: Context,
        id: Int,
        channelId: String,
        title: String,
        text: String,
        onlyAlertOnce: Boolean = false,
        alertAgain: Boolean = false
    ): Boolean {
        if (!canNotifyChannel(context, channelId)) return false
        val launch = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce && !alertAgain)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        return runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
            true
        }.getOrElse {
            WeatherReminderDiagnostics.record(context, "通知投递", "失败", it.javaClass.simpleName)
            false
        }
    }
}
