package com.example.weatherapp

import android.content.Context
import android.location.LocationManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 本机通知调度：每日预报、低频缓存更新和风险检查相互独立。
 * WorkManager 受系统省电策略约束，因此它是本机兜底，不承诺分钟级送达。
 */
object WeatherBackgroundScheduler {
    private const val REGULAR_WORK = "weather_regular_check"
    private const val RISK_WORK = "weather_risk_check"
    private const val DAILY_WORK = "weather_daily_forecast"
    private const val DAILY_RECOVERY_WORK = "weather_daily_recovery"
    private const val TAG_REGULAR = "weather_regular"
    private const val TAG_RISK = "weather_risk"
    private const val TAG_DAILY = "weather_daily"
    const val KEY_TOMORROW = "tomorrow"

    @Synchronized fun schedule(context: Context) {
        WeatherRecoveryAlarm.schedule(context)
        TemperatureChangeReminderAlarm.schedule(context)
        val manager = WorkManager.getInstance(context)
        val needsLocalRiskWork = (
            WeatherSettings.disasterReminder(context) || WeatherSettings.rainReminder(context) ||
                WeatherSettings.temperatureChangeReminder(context) || WeatherSettings.strongWindReminder(context)
            ) &&
            !WeatherPushGateway.isCloudRiskDeliveryActive(context)
        if (WeatherSettings.dailyForecastReminder(context)) {
            val tomorrow = WeatherReminderPolicy.window().tomorrow
            if (!DailyForecastDeliveryLedger.delivered(context, tomorrow)) enqueueDailyForecast(context, tomorrow)
            manager.enqueueUniquePeriodicWork(
                DAILY_RECOVERY_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeatherDailyRecoveryWorker>(15, TimeUnit.MINUTES)
                    .addTag(TAG_DAILY).build()
            )
        } else {
            manager.cancelUniqueWork(DAILY_RECOVERY_WORK)
        }
        if (!needsLocalRiskWork) {
            WeatherRiskRecoveryAlarm.cancel(context)
            TemperatureChangeReminderAlarm.cancel(context)
            manager.cancelUniqueWork(REGULAR_WORK)
            manager.cancelUniqueWork(RISK_WORK)
            WeatherReminderDiagnostics.recordSchedule(context, "未启用天气变化、降雨或极端天气提醒")
            return
        }
        WeatherRiskRecoveryAlarm.schedule(context)
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        manager.enqueueUniquePeriodicWork(
            REGULAR_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WeatherCheckWorker>(60, TimeUnit.MINUTES)
                .setConstraints(network).addTag(TAG_REGULAR).build()
        )
        manager.enqueueUniquePeriodicWork(
            RISK_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WeatherRiskWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(TAG_RISK).build()
        )
        WeatherReminderDiagnostics.recordSchedule(context, "已排程，每约 15 分钟检查一次（系统可能延后）")
    }

    fun enqueueRiskCheck(context: Context, expedited: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<WeatherRiskWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG_RISK)
        if (expedited) builder.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "weather_risk_alarm_check", ExistingWorkPolicy.KEEP, builder.build()
        )
    }

    fun enqueueDailyForecast(context: Context, tomorrow: Boolean) {
        val request = OneTimeWorkRequestBuilder<WeatherDailyForecastWorker>()
            .setInputData(androidx.work.workDataOf(KEY_TOMORROW to tomorrow))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .addTag(TAG_DAILY)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$DAILY_WORK-$tomorrow", ExistingWorkPolicy.KEEP, request
        )
    }
}

/** 常规检查只在已授权后台定位时更新位置；无权限时继续使用最后保存的当前城市。 */
class WeatherCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val cityFromLastLocation = if (WeatherPermissionCoordinator.hasBackgroundLocation(applicationContext)) {
            val manager = applicationContext.getSystemService(LocationManager::class.java)
            runCatching {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                    .maxByOrNull { it.time }
                    ?.let { WeatherCityStore.updateCurrentLocation(applicationContext, it) }
            }.getOrNull()
        } else {
            null
        }
        val city = cityFromLastLocation ?: WeatherCityStore.currentLocationCity(applicationContext)
        if (city == null) {
            WeatherReminderDiagnostics.record(applicationContext, "常规检查", "跳过", "暂无已保存的当前位置")
            return Result.success()
        }
        WeatherPushGateway.syncCurrentCity(applicationContext, city)
        return WeatherBackgroundRefresh.refresh(applicationContext, city, publishRisk = false).asWorkResult(
            applicationContext, "常规检查"
        )
    }
}

/** 所有灾害、降雨本机通知只从这个后台入口产生。 */
class WeatherRiskWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // 先补送前一晚已判定、但尚未被系统接受的变温提醒；网络刷新失败不能中断它。
        WeatherNotificationCenter.reconcilePendingTemperature(applicationContext)
        val city = WeatherCityStore.currentLocationCity(applicationContext)
        if (city == null) {
            WeatherReminderDiagnostics.record(applicationContext, "风险检查", "跳过", "暂无已保存的当前位置")
            return Result.success()
        }
        val outcome = WeatherBackgroundRefresh.refresh(applicationContext, city, publishRisk = true)
        WeatherNotificationCenter.reconcilePendingTemperature(applicationContext)
        return outcome.asWorkResult(
            applicationContext, "风险检查"
        )
    }
}

/** 每日预报闹钟触发后先刷新；失败时仍以缓存生成一条可见通知。 */
class WeatherDailyForecastWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!WeatherSettings.dailyForecastReminder(applicationContext)) return Result.success()
        val tomorrow = WeatherReminderPolicy.window().tomorrow
        if (WeatherNotificationCenter.postDailyForecast(applicationContext, tomorrow)) return Result.success()
        val city = WeatherCityStore.currentLocationCity(applicationContext)
        val outcome = city?.let { WeatherBackgroundRefresh.refresh(applicationContext, it, publishRisk = false) }
        val sent = WeatherNotificationCenter.postDailyForecast(applicationContext, tomorrow)
        WeatherReminderDiagnostics.record(
            applicationContext,
            "每日预报刷新",
            outcome?.label() ?: "无定位缓存",
            if (tomorrow) "22:00 次日预报" else "07:00 当日预报"
        )
        return if (sent) Result.success() else Result.retry()
    }
}

/** 错过 07:00/22:00 闹钟时每 15 分钟补查，直到通知实际投递或进入下一时段。 */
class WeatherDailyRecoveryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!WeatherSettings.dailyForecastReminder(applicationContext)) return Result.success()
        val tomorrow = WeatherReminderPolicy.window().tomorrow
        if (DailyForecastDeliveryLedger.delivered(applicationContext, tomorrow)) return Result.success()
        WeatherBackgroundScheduler.enqueueDailyForecast(applicationContext, tomorrow)
        WeatherReminderDiagnostics.record(applicationContext, "每日预报补发", "已排队", if (tomorrow) "22:00 时段" else "07:00 时段")
        return Result.success()
    }
}

private enum class RefreshOutcome { SUCCESS, NO_DATA, TRANSIENT_FAILURE }

private fun RefreshOutcome.label(): String = when (this) {
    RefreshOutcome.SUCCESS -> "成功"
    RefreshOutcome.NO_DATA -> "无可用数据"
    RefreshOutcome.TRANSIENT_FAILURE -> "网络或服务暂时不可用"
}

private fun RefreshOutcome.asWorkResult(context: Context, task: String): Result {
    WeatherReminderDiagnostics.record(context, task, label())
    return when (this) {
        RefreshOutcome.SUCCESS, RefreshOutcome.NO_DATA -> Result.success()
        RefreshOutcome.TRANSIENT_FAILURE -> Result.retry()
    }
}

private object WeatherBackgroundRefresh {
    fun refresh(context: Context, city: CityPage, publishRisk: Boolean): RefreshOutcome = try {
        if (WeatherSettings.useQWeather(context)) refreshQWeather(context, city, publishRisk)
        else refreshCaiyun(context, city, publishRisk)
    } catch (_: Exception) {
        RefreshOutcome.TRANSIENT_FAILURE
    }

    private fun refreshQWeather(context: Context, city: CityPage, publishRisk: Boolean): RefreshOutcome {
        val epoch=WeatherSettings.sourceKey(context)
        val bundle = QWeatherApi.fetchAll(context, city.lat, city.lng)
        if(epoch!=WeatherSettings.sourceKey(context)) return RefreshOutcome.NO_DATA
        if (bundle.now == null) return RefreshOutcome.TRANSIENT_FAILURE
        WeatherCache.saveQWeather(context, city, bundle)
        if (publishRisk) {
            val alerts = QWeatherApi.fetchAlertsOnlyChecked(context, city.lat, city.lng).mapNotNull { qAlertEvent(city, it) }
            WeatherNotificationCenter.reconcileAlerts(context, city, alerts)
            publishQWeatherRain(context, city, bundle)
            WeatherReminderPolicy.temperatureChange(city, bundle.daily)?.let {
                WeatherNotificationCenter.reconcileWeatherChange(context, it)
            }
            val wind = WindReminderPolicy.event(city, bundle.now.windSpeedKmh, null)
            if (wind != null) WeatherNotificationCenter.reconcileWeatherChange(context, wind)
            else WindReminderDelivery.clear(context, city)
        }
        return RefreshOutcome.SUCCESS
    }

    private fun refreshCaiyun(context: Context, city: CityPage, publishRisk: Boolean): RefreshOutcome {
        val epoch=WeatherSettings.sourceKey(context)
        val response = WeatherDataRepository.fetchCaiyunWeatherBlocking(context, city.lng, city.lat)
        if(epoch!=WeatherSettings.sourceKey(context)) return RefreshOutcome.NO_DATA
        val body = response.body ?: return if (response.code == 401) RefreshOutcome.NO_DATA else RefreshOutcome.TRANSIENT_FAILURE
        if (!response.isSuccessful || body.result?.realtime == null) return RefreshOutcome.TRANSIENT_FAILURE
        WeatherCache.saveCaiyun(context, city, body)
        if (publishRisk) {
            publishCaiyunRain(context, city, body.result?.minutely)
            publishCaiyunChanges(context, city, body.result!!)
            if(WeatherSettings.useOpenMeteo(context)) return RefreshOutcome.SUCCESS // No radar-minute or official alert capability.
            // 预警仅用和风；错误继续向 Worker 传播以重试，不发布彩云预警。
            val alerts = if (BuildConfig.USER_CONFIGURABLE && !UserApiConfiguration.read(context).hasQWeather())
                body.result?.alert?.content.orEmpty().mapNotNull { caiyunAlertEvent(city, it) }
            else QWeatherApi.fetchAlertsOnlyChecked(context, city.lat, city.lng).mapNotNull { qAlertEvent(city, it) }
            WeatherNotificationCenter.reconcileAlerts(context, city, alerts)
        }
        return RefreshOutcome.SUCCESS
    }

    private fun publishCaiyunChanges(context: Context, city: CityPage, result: com.example.weatherapp.Result) {
        val now = System.currentTimeMillis()
        WeatherReminderPolicy.temperatureChange(city, result.daily)?.let {
            WeatherNotificationCenter.reconcileWeatherChange(context, it)
        }
        val wind = WindReminderPolicy.fromCaiyun(city, result, now)
        if (wind != null) WeatherNotificationCenter.reconcileWeatherChange(context, wind)
        else WindReminderDelivery.clear(context, city)
    }

    private fun formatClock(millis: Long): String = java.text.SimpleDateFormat(
        "HH:mm", java.util.Locale.CHINA
    ).format(java.util.Date(millis))

    private fun caiyunAlertEvent(city: CityPage, alert: AlertContent): WeatherAlertEvent? {
        if (alert.status.equals("cancel", true) || AlertVisibilityPolicy.isAgriculturalAlert(
                alert.type.orEmpty(), alert.title.orEmpty(), alert.source.orEmpty()
            )
        ) return null
        val type = WeatherAlertPresentation.normalizedType(alert.type ?: alert.subtype ?: alert.subType ?: alert.title)
        val level = WeatherAlertPresentation.levelName(alert.code, alert.level, alert.title)
        val issued = alert.pubTimestamp ?: System.currentTimeMillis()
        val area = listOfNotNull(alert.county, alert.city, alert.province).firstOrNull().orEmpty()
        return WeatherAlertEvent(
            key = WeatherAlertIdentity.providerKey(city, type, area, alert.alertId, issued),
            semanticKey = WeatherAlertIdentity.semanticKey(city, type, area, issued),
            source = "caiyun",
            title = WeatherAlertPresentation.title(alert),
            level = level,
            severityRank = WeatherAlertPresentation.severityRank(level),
            detail = WeatherAlertPresentation.cleanDetail(alert.description ?: alert.content),
            // 彩云响应没有统一过期时间，最多保留 24 小时；下一次正常刷新会移除已取消项目。
            expiresAt = System.currentTimeMillis() + 24L * 60L * 60_000L
        )
    }

    private fun qAlertEvent(city: CityPage, alert: QAlert): WeatherAlertEvent? {
        if (alert.messageType.equals("cancel", true) || AlertVisibilityPolicy.isAgriculturalAlert(
                alert.eventTypeName, alert.headline, alert.senderName
            )
        ) return null
        val type = WeatherAlertPresentation.normalizedType(alert.eventTypeName.ifBlank { alert.headline })
        val level = WeatherAlertPresentation.levelName(alert)
        return WeatherAlertEvent(
            key = WeatherAlertIdentity.providerKey(city, type, alert.senderName, alert.id, alert.issuedTimeMillis),
            semanticKey = WeatherAlertIdentity.semanticKey(city, type, alert.senderName, alert.issuedTimeMillis),
            source = "qweather",
            title = WeatherAlertPresentation.title(alert),
            level = level,
            severityRank = WeatherAlertPresentation.severityRank(level),
            detail = WeatherAlertPresentation.cleanDetail(alert.description.ifBlank { alert.instruction }),
            expiresAt = alert.expireTimeMillis.takeIf { it > System.currentTimeMillis() }
                ?: System.currentTimeMillis() + 24L * 60L * 60_000L
        )
    }

    /** 彩云优先；只有发布地区确实不同才并存。 */
    private fun mergeAlertSources(primary: List<WeatherAlertEvent>, supplement: List<WeatherAlertEvent>): List<WeatherAlertEvent> {
        val result = ArrayList<WeatherAlertEvent>()
        (primary + supplement).forEach { candidate ->
            val identity = candidate.semanticKey
            val same = result.indexOfFirst { current ->
                current.semanticKey == identity
            }
            if (same < 0) result += candidate
        }
        return result
    }

    private fun publishQWeatherRain(context: Context, city: CityPage, bundle: QWeatherBundle) {
        // 和风分钟降水为每 5 分钟累计量；与页面卡片相同，换算为 mm/h 后再判断。
        val values = bundle.minutely.map { it.precip * 12.0 }
        val times = bundle.minutely.map { it.timeMillis }
        val firstIndex = values.indexOfFirst { it > WeatherRainPresentation.RAIN_THRESHOLD_MM_PER_HOUR }
        val now = System.currentTimeMillis()
        val cardSummary = if (firstIndex >= 0) {
            val peakIndex = values.indices.maxByOrNull { values[it] } ?: firstIndex
            WeatherRainPresentation.summary(values, times, WeatherRainPresentation.category(values[peakIndex]), now)
        } else {
            ""
        }
        if (firstIndex < 0 || cardSummary.isBlank()) {
            WeatherNotificationCenter.reconcileRain(context, null)
            return
        }
        val peakIndex = values.indices.maxByOrNull { values[it] } ?: firstIndex
        val category = WeatherRainPresentation.category(values[peakIndex])
        WeatherNotificationCenter.reconcileRain(
            context,
            WeatherRainEvent(
                cityKey = WeatherNotificationLedger.cityKey(city),
                locationName = city.preciseRainNotificationLocation(),
                firstRainAt = times[firstIndex],
                category = category,
                categoryRank = WeatherRainPresentation.categoryRank(category),
                summary = cardSummary,
                peak = WeatherRainPresentation.peakText(times[peakIndex], values[peakIndex], category)
            )
        )
    }

    private fun publishCaiyunRain(context: Context, city: CityPage, minutely: Minutely?) {
        val values = minutely?.precipitation2h ?: minutely?.precipitation
        if (values.isNullOrEmpty()) {
            WeatherNotificationCenter.reconcileRain(context, null)
            return
        }
        val firstIndex = values.indexOfFirst { it > WeatherRainPresentation.RAIN_THRESHOLD_MM_PER_HOUR }
        if (firstIndex < 0) {
            WeatherNotificationCenter.reconcileRain(context, null)
            return
        }
        val interval = 120.0 / values.size
        val now = System.currentTimeMillis()
        val times = values.indices.map { index -> now + (index * interval * 60_000L).toLong() }
        val peakIndex = values.indices.maxByOrNull { values[it] } ?: firstIndex
        val category = WeatherRainPresentation.category(values[peakIndex])
        val cardSummary = WeatherRainPresentation.summary(values, times, category, now)
        if (cardSummary.isBlank()) {
            WeatherNotificationCenter.reconcileRain(context, null)
            return
        }
        WeatherNotificationCenter.reconcileRain(
            context,
            WeatherRainEvent(
                cityKey = WeatherNotificationLedger.cityKey(city),
                locationName = city.preciseRainNotificationLocation(),
                firstRainAt = times[firstIndex],
                category = category,
                categoryRank = WeatherRainPresentation.categoryRank(category),
                summary = cardSummary,
                peak = WeatherRainPresentation.peakText(times[peakIndex], values[peakIndex], category)
            )
        )
    }
}
