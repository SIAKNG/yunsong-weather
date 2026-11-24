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

    private data class NoticeDay(
        val low: Double,
        val high: Double,
        val sky: String?,
        val rain: Double?,
        val aqi: Double?,
        val wind: Double?,
        val gust: Double?,
        val savedAt: Long
    )

    private fun noticeDay(snapshot: WeatherCache.HomeStartup, date: String, now: Long): NoticeDay? {
        val caiyun = snapshot.caiyun
        val daily = caiyun?.fresh?.result?.daily
        val temp = daily?.temperature.orEmpty().firstOrNull { it.date?.take(10) == date }
        val min = temp?.min?.takeIf(Double::isFinite)
        val max = temp?.max?.takeIf(Double::isFinite)
        if (caiyun != null && min != null && max != null && WeatherCachePolicy.dailyUsable(now - caiyun.savedAt)) {
            return NoticeDay(min, max,
                daily?.skycon.orEmpty().firstOrNull { it.date?.take(10) == date }?.value,
                daily?.precipitation.orEmpty().firstOrNull { it.date?.take(10) == date }?.probability,
                daily?.airQuality?.aqi.orEmpty().firstOrNull { it.date?.take(10) == date }?.avg?.chn,
                daily?.wind.orEmpty().firstOrNull { it.date?.take(10) == date }?.max?.speed,
                null, caiyun.savedAt)
        }
        val qweather = snapshot.qweather ?: return null
        val q = qweather.fresh?.daily.orEmpty().firstOrNull { it.date.take(10) == date } ?: return null
        val low = q.low?.takeIf(Double::isFinite) ?: return null
        val high = q.high?.takeIf(Double::isFinite) ?: return null
        if (!WeatherCachePolicy.dailyUsable(now - qweather.savedAt)) return null
        return NoticeDay(low, high, q.skycon, q.precipProb?.div(100), null, q.windKmh, q.gustKmh, qweather.savedAt)
    }

    @Synchronized fun prepareFromCache(c: Context, city: CityPage, now: Long = System.currentTimeMillis()) {
        if (!enabled(c)) return
        val selected = WeatherCityStore.currentLocationCity(c) ?: return
        if (!WeatherWidgetPolicy.sameCity(selected, city)) return
        val source = WeatherWidgetPolicy.sourceKey(c)
        val scope = identity(c, selected)
        val selectedQWeather = WeatherSettings.useQWeather(c)
        val background = if (selectedQWeather) {
            WeatherCache.HomeStartup(qweather = WeatherCache.loadQWeatherStartup(c, selected))
        } else {
            WeatherCache.HomeStartup(caiyun = WeatherCache.loadCaiyunStartup(c, selected))
        }
        val foreground = WeatherCache.loadForegroundStartup(c, selected)?.takeIf {
            if (selectedQWeather) it.qweather != null else it.caiyun != null
        }
        val caiyun = background.caiyun?.fresh?.result
        val qweather = background.qweather?.fresh
        val daily = caiyun?.daily
        val qDaily = qweather?.daily.orEmpty()
        val hours = ForecastDayHistoryCache.load(c, selected, now)
        val zone = ZoneId.systemDefault()
        val cachedWarnings = WeatherCache.loadHomeAlerts(c, selected)
        val qAlerts = cachedWarnings + foreground?.qweather?.fresh?.alerts.orEmpty() + qweather?.alerts.orEmpty()
        val caiyunAlerts = (foreground?.caiyun?.fresh?.result?.alert?.content.orEmpty() +
            caiyun?.alert?.content.orEmpty()).mapNotNull { item ->
            val timestamp = item.pubTimestamp ?: return@mapNotNull null
            val issuedAt = if (timestamp in 1..9_999_999_999L) timestamp * 1_000 else timestamp
            if (now - issuedAt !in 0L until 24L * ForecastDetailData.HOUR) return@mapNotNull null
            WeatherRiskRefresh.caiyunAlert(selected, item)
        }
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val items = JSONObject()
        val homeDates = mutableSetOf<String>()
        for (date in listOf(today, today.plusDays(1))) {
            val key = date.toString()
            val visible = foreground?.let { noticeDay(it, key, now) }
            val forecast = visible ?: noticeDay(background, key, now) ?: continue
            if (visible != null) homeDates += key
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val dateHours = hours.filter { it.time in start until end }
            val airHours = dateHours.filter { it.aqi?.isFinite() == true }
            val aqi = forecast.aqi ?: airHours.takeIf {
                it.size >= 6 && it.last().time - it.first().time >= 12L * ForecastDetailData.HOUR
            }?.mapNotNull { it.aqi }?.average()
            val qWarning = qAlerts.filter {
                it.expireTimeMillis > maxOf(start, now) && it.issuedTimeMillis < end &&
                    !it.messageType.equals("cancel", true) &&
                    !AlertVisibilityPolicy.isAgriculturalAlert(it.eventTypeName, it.headline, it.senderName)
            }.maxByOrNull { WeatherAlertPresentation.severityRank(WeatherAlertPresentation.levelName(it)) }
            val caiyunWarning = if (date == today) caiyunAlerts.maxByOrNull { it.severityRank } else null
            val warning = when {
                caiyunWarning != null -> WeatherNoticeCopy.warningSummary(caiyunWarning.notificationArea, caiyunWarning.title)
                qWarning != null -> WeatherNoticeCopy.warningSummary(
                    WeatherNoticeCopy.warningArea(qWarning.senderName, selected.name), WeatherAlertPresentation.title(qWarning))
                else -> null
            }
            val range = "预计最低 ${WeatherSettings.tempText(c, forecast.low)}，最高 ${WeatherSettings.tempText(c, forecast.high)}"
            val message = WeatherNoticeCopy.daily(range, forecast.low, forecast.high, forecast.sky, aqi,
                forecast.rain, forecast.wind ?: dateHours.mapNotNull { it.wind }.maxOrNull(),
                forecast.gust ?: dateHours.mapNotNull { it.gustKmh }.maxOrNull(), warning)
            items.put(key, JSONObject().put("text", message).put("dataAt", forecast.savedAt))
        }
        if (source != WeatherWidgetPolicy.sourceKey(c) || scope != identity(c, selected)) return
        // Preserve the observation/cache age when reading, rather than making old data fresh.
        if (items.length() > 0) {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = runCatching { JSONObject(p.getString("snapshot", "{}").orEmpty()) }.getOrDefault(JSONObject())
            val days = if (previous.optString("scope") == scope) previous.optJSONObject("days") ?: JSONObject() else JSONObject()
            // Preserve each missing day's content and its original data age; never renew it merely by merging.
            for (date in days.keys().asSequence().toList()) {
                val age = now - (days.optJSONObject(date)?.optLong("dataAt", 0L) ?: 0L)
                if (age !in 0..WeatherReminderTiming.PREPARED_MAX_AGE_MS) days.remove(date)
            }
            for (date in items.keys()) {
                val next = items.getJSONObject(date)
                if (date in homeDates || (days.optJSONObject(date)?.optLong("dataAt", 0L) ?: 0L) <= next.getLong("dataAt")) {
                    days.put(date, next)
                }
            }
            p.edit().putString("snapshot", JSONObject().put("scope", scope).put("days", days).toString()).commit()
        }
        if (WeatherSettings.temperatureChangeReminder(c)) {
            val saved = background.caiyun?.savedAt ?: background.qweather?.savedAt ?: 0L
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
