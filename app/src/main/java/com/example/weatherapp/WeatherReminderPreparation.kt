package com.example.weatherapp

import android.content.Context
import androidx.work.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prepared text is private local state, never a second weather data source. */
internal object WeatherReminderPreparation {
    private const val PREFS = "weather_prepared_reminders_v1"
    private const val TAG = "weather_reminder_preparation"
    private const val PERIODIC = "weather_reminder_preparation_periodic"
    private const val IMMEDIATE = "weather_reminder_preparation_now"
    private val cacheThread = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "weather-reminder-cache") }

    fun onCacheSaved(context: Context) {
        val c = context.applicationContext
        if (!enabled(c)) return
        cacheThread.execute { WeatherCityStore.currentLocationCity(c)?.let { prepareFromCache(c, it) } }
    }

    fun enabled(c: Context) = WeatherSettings.dailyForecastReminder(c) || WeatherSettings.temperatureChangeReminder(c)
    private fun identity(c: Context, city: CityPage) =
        WeatherWidgetPolicy.sourceKey(c) + "|" + WeatherSettings.tempUnit(c) + "|" + WeatherNotificationLedger.cityKey(city)

    @Synchronized fun prepareFromCache(c: Context, city: CityPage, now: Long = System.currentTimeMillis()) {
        if (!enabled(c)) return
        val selected = WeatherCityStore.currentLocationCity(c) ?: return
        if (!WeatherWidgetPolicy.sameCity(selected, city)) return
        val source = WeatherWidgetPolicy.sourceKey(c)
        val scope = identity(c, selected)
        val daily = if (WeatherSettings.useQWeather(c)) null else WeatherCache.loadCaiyun(c, selected)?.result?.daily
        val qDaily = if (WeatherSettings.useQWeather(c)) WeatherCache.loadQWeather(c, selected)?.daily.orEmpty() else emptyList()
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val items = JSONObject()
        for (date in listOf(today, today.plusDays(1))) {
            val key = date.toString()
            val day = daily?.temperature.orEmpty().firstOrNull { it.date?.take(10) == key }
            val q = qDaily.firstOrNull { it.date.take(10) == key }
            val low = day?.min ?: q?.low
            val high = day?.max ?: q?.high
            if (low == null || high == null) continue
            val sky = daily?.skycon.orEmpty().firstOrNull { it.date?.take(10) == key }?.value ?: q?.skycon
            val rain = daily?.precipitation.orEmpty().firstOrNull { it.date?.take(10) == key }?.probability ?: q?.precipProb
            val advice = when {
                rain != null && rain >= 50.0 -> "有较高降雨可能，提前备好雨具并留意路面湿滑。"
                rain != null && rain >= 20.0 -> "可能有短时降雨，出门建议随身带伞。"
                else -> "按温差增减衣物，出门留意天气变化。"
            }
            items.put(key, "${WeatherText.skyconToText(sky)}，${WeatherSettings.tempText(c, low)}–${WeatherSettings.tempText(c, high)}。$advice")
        }
        if (source != WeatherWidgetPolicy.sourceKey(c) || scope != identity(c, selected)) return
        // Preserve the observation/cache age when reading, rather than making old data fresh.
        val saved = WeatherCache.savedAt(c, selected)
        if (items.length() > 0 && saved > 0 && saved <= now + WeatherCachePolicy.CLOCK_SKEW_MS) {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = runCatching { JSONObject(p.getString("snapshot", "{}").orEmpty()) }.getOrDefault(JSONObject())
            val days = if (previous.optString("scope") == scope) previous.optJSONObject("days") ?: JSONObject() else JSONObject()
            // Preserve each missing day's content and its original data age; never renew it merely by merging.
            for (date in days.keys().asSequence().toList()) {
                val age = now - (days.optJSONObject(date)?.optLong("dataAt", 0L) ?: 0L)
                if (age !in 0..WeatherReminderTiming.PREPARED_MAX_AGE_MS) days.remove(date)
            }
            for (date in items.keys()) {
                if ((days.optJSONObject(date)?.optLong("dataAt", 0L) ?: 0L) <= saved)
                    days.put(date, JSONObject().put("text", items.getString(date)).put("dataAt", saved))
            }
            p.edit().putString("snapshot", JSONObject().put("scope", scope).put("days", days).toString()).commit()
        }
        if (WeatherSettings.temperatureChangeReminder(c)) {
            // After midnight, recover a previous evening event from the same dated forecast.
            for (comparison in listOf(today.minusDays(1), today)) {
                val event = if (daily != null) WeatherReminderPolicy.temperatureChange(selected, daily, comparison)
                    else WeatherReminderPolicy.temperatureChange(selected, qDaily, comparison)
                if (source != WeatherWidgetPolicy.sourceKey(c)) return
                if (event != null) TemperatureChangeDeliveryLedger.stage(c, event, saved)
                else if (listOf(comparison, comparison.plusDays(1)).all { date ->
                    daily?.temperature.orEmpty().any { it.date?.take(10) == date.toString() && it.min != null && it.max != null } ||
                        qDaily.any { it.date.take(10) == date.toString() && it.low != null && it.high != null }
                }) {
                    TemperatureChangeDeliveryLedger.removeUndelivered(c, selected, comparison.plusDays(1), saved)
                }
            }
        }
    }

    @Synchronized fun message(c: Context, city: CityPage, date: java.time.LocalDate, now: Long = System.currentTimeMillis()): String? {
        // One immutable encoded snapshot, so scope/body/data age cannot come from different commits.
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("snapshot", null) ?: return null
        return runCatching {
            val snapshot = JSONObject(raw)
            if (snapshot.optString("scope") != identity(c, city)) return@runCatching null
            val item = snapshot.optJSONObject("days")?.optJSONObject(date.toString()) ?: return@runCatching null
            if (now - item.optLong("dataAt", 0L) !in 0..WeatherReminderTiming.PREPARED_MAX_AGE_MS) return@runCatching null
            item.optString("text").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun summary(c: Context): String {
        val city = WeatherCityStore.currentLocationCity(c) ?: return "提醒内容：等待添加城市"
        val today = java.time.LocalDate.now()
        val ready = message(c, city, today) != null || message(c, city, today.plusDays(1)) != null
        return "提醒内容：" + if (ready) "已在本机准备，到点优先离线投递" else "尚无有效预报，联网后提前准备"
    }

    fun schedule(c: Context) {
        val manager = WorkManager.getInstance(c)
        if (!enabled(c)) { manager.cancelAllWorkByTag(TAG); return }
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WeatherReminderPreparationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network).addTag(TAG).build())
        val now = System.currentTimeMillis()
        for (at in WeatherReminderTiming.preparationTimes(now, WeatherSettings.dailyForecastReminder(c), WeatherSettings.temperatureChangeReminder(c))) {
            manager.enqueueUniqueWork("weather_prepare_$at", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WeatherReminderPreparationWorker>()
                    .setInitialDelay(at - now, TimeUnit.MILLISECONDS).setConstraints(network)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).addTag(TAG).build())
        }
    }

    fun request(c: Context) {
        schedule(c)
        if (!enabled(c)) return
        WorkManager.getInstance(c).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WeatherReminderPreparationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).addTag(TAG).build())
    }
}

class WeatherReminderPreparationWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        if (!WeatherReminderPreparation.enabled(c)) return@withContext Result.success()
        WeatherReminderPreparation.schedule(c)
        val city = WeatherCityStore.currentLocationCity(c) ?: return@withContext Result.success()
        WeatherReminderPreparation.prepareFromCache(c, city)
        val now = System.currentTimeMillis()
        val age = now - WeatherCache.savedAt(c, city)
        val ready = WeatherReminderPreparation.message(c, city, java.time.LocalDate.now()) != null &&
            WeatherReminderPreparation.message(c, city, java.time.LocalDate.now().plusDays(1)) != null
        if (age in 0..(15L * 60_000L) && ready) return@withContext Result.success()
        if (ready && !WeatherReminderTiming.nearDelivery(now, WeatherSettings.dailyForecastReminder(c), WeatherSettings.temperatureChangeReminder(c)))
            return@withContext Result.success()
        val outcome = WeatherBackgroundRefresh.refresh(c, city, publishRisk = false)
        WeatherReminderPreparation.prepareFromCache(c, city)
        WeatherReminderDiagnostics.record(c, "提醒预备", if (outcome == RefreshOutcome.SUCCESS) "已更新" else "保留已准备内容")
        if (outcome == RefreshOutcome.TRANSIENT_FAILURE && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
