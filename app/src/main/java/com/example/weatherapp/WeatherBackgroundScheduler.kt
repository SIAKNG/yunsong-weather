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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本机通知调度：每日预报、低频缓存更新和风险检查相互独立。
 * WorkManager 受系统省电策略约束，因此它是本机兜底，不承诺分钟级送达。
 */
object WeatherBackgroundScheduler {
    private const val REGULAR_WORK = "weather_regular_check"
    private const val RISK_WORK = "weather_risk_check"
    private const val RISK_ALARM_WORK = "weather_risk_alarm_check"
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
        val migration = context.getSharedPreferences("weather_reminder_schedule_v2", Context.MODE_PRIVATE)
        if (!migration.getBoolean("risk_v2", false)) {
            manager.cancelUniqueWork("weather_risk_alarm_check")
            migration.edit().putBoolean("risk_v2", true).apply()
        }
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
            manager.cancelUniqueWork(RISK_ALARM_WORK)
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
            // UPDATE migrates the backoff of already-installed periodic work without
            // resetting its cadence or replacing a currently running worker.
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WeatherRiskWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WeatherRiskRetryPolicy.BACKOFF_MINUTES, TimeUnit.MINUTES)
                .addTag(TAG_RISK).build()
        )
        WeatherReminderDiagnostics.recordSchedule(context, "已排程，每约 15 分钟检查一次（系统可能延后）")
    }

    fun enqueueRiskCheck(context: Context, expedited: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<WeatherRiskWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WeatherRiskRetryPolicy.BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG_RISK)
        if (expedited) builder.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork(
            RISK_ALARM_WORK, ExistingWorkPolicy.KEEP, builder.build()
        )
    }

    fun enqueueDailyForecast(context: Context, tomorrow: Boolean) {
        val request = OneTimeWorkRequestBuilder<WeatherDailyForecastWorker>()
            .setInputData(androidx.work.workDataOf(KEY_TOMORROW to tomorrow))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
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
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        WeatherNotificationCenter.reconcilePendingTemperature(applicationContext)
        if (!WeatherRiskRecoveryAlarm.isEnabled(applicationContext)) return@withContext Result.success()
        val city = WeatherCityStore.currentLocationCity(applicationContext)
        if (city == null) {
            WeatherReminderDiagnostics.record(applicationContext, "风险检查", "跳过", "暂无已保存的当前位置")
            return@withContext Result.success()
        }
        val outcome = WeatherBackgroundRefresh.refresh(applicationContext, city, publishRisk = true)
        WeatherNotificationCenter.reconcilePendingTemperature(applicationContext)
        WeatherReminderDiagnostics.record(applicationContext, "风险检查", outcome.label())
        if (outcome == RefreshOutcome.TRANSIENT_FAILURE && WeatherRiskRetryPolicy.shouldRetry(runAttemptCount)) {
            Result.retry()
        } else {
            if (outcome == RefreshOutcome.TRANSIENT_FAILURE)
                WeatherReminderDiagnostics.record(applicationContext, "风险检查", "本轮重试已达上限", "保留台账，等待下轮风险检查")
            Result.success()
        }
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
        return if (sent || runAttemptCount >= 3) Result.success() else Result.retry()
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

internal enum class RefreshOutcome { SUCCESS, NO_DATA, TRANSIENT_FAILURE }

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

internal object WeatherBackgroundRefresh {
    suspend fun refresh(context: Context, city: CityPage, publishRisk: Boolean): RefreshOutcome = withContext(Dispatchers.IO) {
        if (publishRisk) return@withContext WeatherRiskRefresh.refresh(context, city)
        val epoch = WeatherRiskRefresh.epoch(context)
        try {
            if (WeatherSettings.useQWeather(context)) refreshQWeather(context, city, epoch)
            else refreshCaiyun(context, city, epoch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RefreshOutcome.TRANSIENT_FAILURE
        }
    }

    private fun refreshQWeather(context: Context, city: CityPage, epoch: String): RefreshOutcome {
        val bundle = QWeatherApi.fetchAll(context, city.lat, city.lng)
        if (epoch != WeatherRiskRefresh.epoch(context)) return RefreshOutcome.NO_DATA
        if (bundle.now == null) return RefreshOutcome.TRANSIENT_FAILURE
        WeatherCache.saveQWeather(context, city, bundle)
        return RefreshOutcome.SUCCESS
    }

    private fun refreshCaiyun(context: Context, city: CityPage, epoch: String): RefreshOutcome {
        val response = WeatherDataRepository.fetchCaiyunWeatherBlocking(context, city.lng, city.lat)
        if (epoch != WeatherRiskRefresh.epoch(context)) return RefreshOutcome.NO_DATA
        val body = response.body ?: return if (response.code == 401) RefreshOutcome.NO_DATA else RefreshOutcome.TRANSIENT_FAILURE
        if (!response.isSuccessful || body.result?.realtime == null) return RefreshOutcome.TRANSIENT_FAILURE
        WeatherCache.saveCaiyun(context, city, body)
        return RefreshOutcome.SUCCESS
    }
}
