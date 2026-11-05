package com.example.weatherapp

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Risk-only requests. No QWeather homepage bundle, AQI, hourly or life-advice chain. */
internal object WeatherRiskRefresh {
    fun epoch(context: Context): String = WeatherSettings.dataSource(context) + "_r" +
        if (BuildConfig.USER_CONFIGURABLE) UserApiConfiguration.revision(context) else 0

    suspend fun refresh(context: Context, city: CityPage): RefreshOutcome = withContext(Dispatchers.IO) {
        val captured = epoch(context)
        val isCurrent = {
            WeatherRiskFreshness.isCurrent(captured, epoch(context), city, WeatherCityStore.currentLocationCity(context))
        }
        if (!isCurrent()) return@withContext RefreshOutcome.NO_DATA
        if (WeatherSettings.useQWeather(context)) {
            WeatherRiskBranches.run(isCurrent, qweatherBranches(context, city, isCurrent))
        } else {
            caiyun(context, city, isCurrent)
        }
    }

    private fun qweatherAvailable(context: Context): Boolean =
        !BuildConfig.USER_CONFIGURABLE || UserApiConfiguration.read(context).hasQWeather()

    private fun qweatherBranches(
        context: Context, city: CityPage, isCurrent: () -> Boolean,
        alerts: Boolean = true, rain: Boolean = true, temperature: Boolean = true, wind: Boolean = true
    ): List<suspend () -> Unit> = buildList {
        if (!qweatherAvailable(context)) return@buildList
        if (alerts && WeatherSettings.disasterReminder(context)) add {
            WeatherRiskBranches.fetchAndPublish(isCurrent,
                { QWeatherApi.fetchAlertsOnlyChecked(context, city.lat, city.lng) },
                { events -> WeatherNotificationCenter.reconcileAlerts(context, city, events.mapNotNull { qAlert(city, it) }) })
        }
        if (rain && WeatherSettings.rainReminder(context)) add {
            WeatherRiskBranches.fetchAndPublish(isCurrent,
                { QWeatherApi.fetchMinutelyOnlyChecked(context, city.lat, city.lng) },
                { publishQWeatherRain(context, city, it) })
        }
        if (temperature && WeatherSettings.temperatureChangeReminder(context)) add {
            WeatherRiskBranches.fetchAndPublish(isCurrent,
                { QWeatherApi.fetchDailyOnlyChecked(context, city.lat, city.lng) },
                { days ->
                    val today = java.time.LocalDate.now()
                    val event = WeatherReminderPolicy.temperatureChange(city, days)
                    if (event != null) WeatherNotificationCenter.reconcileWeatherChange(context, event)
                    else if (listOf(today, today.plusDays(1)).all { date -> days.any { it.date.take(10) == date.toString() && it.low != null && it.high != null } })
                        TemperatureChangeDeliveryLedger.removeUndelivered(context, city, today.plusDays(1))
                })
        }
        if (wind && WeatherSettings.strongWindReminder(context)) add {
            WeatherRiskBranches.fetchAndPublish(isCurrent,
                { QWeatherApi.fetchWindOnlyChecked(context, city.lat, city.lng) },
                { speed ->
                    val event = WindReminderPolicy.event(city, speed, null)
                    if (event != null) WeatherNotificationCenter.reconcileWeatherChange(context, event)
                    else WindReminderDelivery.clear(context, city)
                })
        }
    }

    private suspend fun caiyun(context: Context, city: CityPage, isCurrent: () -> Boolean): RefreshOutcome {
        val freeSource = WeatherSettings.dataSource(context) == "open_meteo"
        var authorizationDenied = false
        val response = try {
            // Bypass the repository's optional minute probe: publish the single composite
            // response first. Missing capability is never interpreted as an all-clear.
            if (freeSource) WeatherDataRepository.fetchCaiyunWeatherBlocking(context, city.lng, city.lat)
            else fetchCaiyunComposite(context, city)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: WeatherRiskAuthorizationFailure) {
            authorizationDenied = true
            null
        } catch (_: Exception) {
            null
        }
        if (!isCurrent()) return RefreshOutcome.NO_DATA
        val body = response?.body?.takeIf { response.isSuccessful && it.status == "ok" }
        val data = body?.result
        val ownAlerts = !freeSource && data?.alert?.status == "ok" && data.alert.content != null
        val ownRain = !freeSource && validCaiyunMinutes(data?.minutely)
        val ownTemperature = !data?.daily?.temperature.isNullOrEmpty()
        val ownWind = data?.realtime?.let { realtime ->
            realtime.status == "ok" && (realtime.wind?.speed?.isFinite() == true || realtime.gust?.isFinite() == true)
        } == true
        val branches = mutableListOf<suspend () -> Unit>()
        // Put locally available alert/minute publication before optional network requests.
        if (ownAlerts && WeatherSettings.disasterReminder(context)) branches += suspend {
            if (isCurrent()) WeatherNotificationCenter.reconcileAlerts(context, city,
                data!!.alert!!.content!!.mapNotNull { caiyunAlert(city, it) })
        }
        if (ownRain && WeatherSettings.rainReminder(context)) branches += suspend {
            if (isCurrent()) publishCaiyunRain(context, city, data!!.minutely!!)
        }
        if (ownTemperature && WeatherSettings.temperatureChangeReminder(context)) branches += suspend {
            if (isCurrent()) {
                val today = java.time.LocalDate.now()
                val event = WeatherReminderPolicy.temperatureChange(city, data!!.daily)
                if (event != null) WeatherNotificationCenter.reconcileWeatherChange(context, event)
                else if (listOf(today, today.plusDays(1)).all { date -> data.daily?.temperature.orEmpty().any { it.date?.take(10) == date.toString() && it.min != null && it.max != null } })
                    TemperatureChangeDeliveryLedger.removeUndelivered(context, city, today.plusDays(1))
            }
        }
        if (ownWind && WeatherSettings.strongWindReminder(context)) branches += suspend {
            if (isCurrent()) {
                val event = WindReminderPolicy.fromCaiyun(city, data!!)
                if (event != null) WeatherNotificationCenter.reconcileWeatherChange(context, event)
                else WindReminderDelivery.clear(context, city)
            }
        }
        // A risk-only Caiyun response can lack optional radar fields: never replace the complete homepage cache with it.
        if (freeSource && data?.realtime != null) branches += suspend {
            if (isCurrent()) WeatherCache.saveCaiyun(context, city, body!!)
        }
        val canFetchOwnMinutes = !freeSource && !ownRain && WeatherSettings.rainReminder(context) &&
            WeatherSettings.caiyunMinutelyCapability(context) == WeatherSettings.CAIYUN_MINUTELY_AVAILABLE
        if (canFetchOwnMinutes) branches += suspend {
            // The optional v2.6 minute request starts only after composite data has been
            // dispatched; failure cannot block official alerts or clear the rain ledger.
            try {
                WeatherRiskBranches.fetchAndPublish(isCurrent,
                    { fetchCaiyunMinutes(context, city) }, { publishCaiyunRain(context, city, it) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!qweatherAvailable(context)) throw failure
                val denied = WeatherRiskAuthorizationFailure.isAuthorizationFailure(failure)
                try {
                    WeatherRiskBranches.fetchAndPublish(isCurrent,
                        { QWeatherApi.fetchMinutelyOnlyChecked(context, city.lat, city.lng) },
                        { publishQWeatherRain(context, city, it) })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fallbackFailure: Exception) {
                    if (denied) throw WeatherRiskAuthorizationFailure()
                    throw fallbackFailure
                }
                if (denied) throw WeatherRiskAuthorizationFailure()
            }
        }
        // Free-source hourly precipitation is not radar-minute/official-alert capability.
        if (!freeSource) branches += qweatherBranches(context, city, isCurrent,
            alerts = !ownAlerts, rain = !ownRain && !canFetchOwnMinutes,
            temperature = !ownTemperature, wind = !ownWind)
        if (data == null) branches += suspend {
            if (authorizationDenied) throw WeatherRiskAuthorizationFailure()
            throw IOException("Primary weather unavailable")
        }
        return WeatherRiskBranches.run(isCurrent, branches)
    }

    private fun fetchCaiyunComposite(context: Context, city: CityPage): GatewayResult<CaiyunResponse> {
        val configuration = if (BuildConfig.USER_CONFIGURABLE) UserApiConfiguration.read(context) else null
        val token = configuration?.caiyunToken ?: BuildConfig.CAIYUN_EMBEDDED_TOKEN.trim()
        if (token.isBlank()) throw IOException("Primary weather not configured")
        val service = CaiyunDirectApi.service
        val call = if (configuration?.caiyunVersion == "2.6")
            service.getWeatherV26(token, city.lng, city.lat, true, 15, 360)
        else service.getWeather(token, city.lng, city.lat, true, 15, 0, 360)
        val response = call.execute()
        if (response.code() == 401 || response.code() == 403) throw WeatherRiskAuthorizationFailure()
        return GatewayResult(response.body(), response.code())
    }

    private fun fetchCaiyunMinutes(context: Context, city: CityPage): Minutely {
        val token = if (BuildConfig.USER_CONFIGURABLE) UserApiConfiguration.read(context).caiyunToken
            else BuildConfig.CAIYUN_EMBEDDED_TOKEN.trim()
        if (token.isBlank()) throw IOException("Minute weather not configured")
        val response = CaiyunDirectApi.service.getMinutelyV26(token, city.lng, city.lat).execute()
        if (response.code() == 401 || response.code() == 403) throw WeatherRiskAuthorizationFailure()
        val body = response.body()
        val minutely = body?.result?.minutely
        if (!response.isSuccessful || body?.status != "ok" || !validCaiyunMinutes(minutely))
            throw IOException("Minute weather unavailable")
        return minutely!!
    }

    internal fun validCaiyunMinutes(minutely: Minutely?): Boolean {
        if (minutely?.status != "ok") return false
        val values = minutely.precipitation2h ?: minutely.precipitation
        return !values.isNullOrEmpty() && values.all { it.isFinite() && it >= 0.0 }
    }

    private fun qAlert(city: CityPage, alert: QAlert): WeatherAlertEvent? {
        if (alert.messageType.equals("cancel", true) || AlertVisibilityPolicy.isAgriculturalAlert(
                alert.eventTypeName, alert.headline, alert.senderName)) return null
        val type = WeatherAlertPresentation.normalizedType(alert.eventTypeName.ifBlank { alert.headline })
        val level = WeatherAlertPresentation.levelName(alert)
        return WeatherAlertEvent(
            key = WeatherAlertIdentity.providerKey(city, type, alert.senderName, alert.id, alert.issuedTimeMillis),
            semanticKey = WeatherAlertIdentity.semanticKey(city, type, alert.senderName, alert.issuedTimeMillis),
            source = "qweather", title = WeatherAlertPresentation.title(alert), level = level,
            severityRank = WeatherAlertPresentation.severityRank(level),
            detail = WeatherAlertPresentation.cleanDetail(alert.description.ifBlank { alert.instruction }),
            expiresAt = alert.expireTimeMillis.takeIf { it > System.currentTimeMillis() }
                ?: System.currentTimeMillis() + 24L * 60L * 60_000L)
    }

    private fun caiyunAlert(city: CityPage, alert: AlertContent): WeatherAlertEvent? {
        if (alert.status.equals("cancel", true) || AlertVisibilityPolicy.isAgriculturalAlert(
                alert.type.orEmpty(), alert.title.orEmpty(), alert.source.orEmpty())) return null
        val type = WeatherAlertPresentation.normalizedType(alert.type ?: alert.subtype ?: alert.subType ?: alert.title)
        val level = WeatherAlertPresentation.levelName(alert.code, alert.level, alert.title)
        val issued = alert.pubTimestamp ?: System.currentTimeMillis()
        val area = listOfNotNull(alert.county, alert.city, alert.province).firstOrNull().orEmpty()
        return WeatherAlertEvent(
            key = WeatherAlertIdentity.providerKey(city, type, area, alert.alertId, issued),
            semanticKey = WeatherAlertIdentity.semanticKey(city, type, area, issued),
            source = "caiyun", title = WeatherAlertPresentation.title(alert), level = level,
            severityRank = WeatherAlertPresentation.severityRank(level),
            detail = WeatherAlertPresentation.cleanDetail(alert.description ?: alert.content),
            expiresAt = System.currentTimeMillis() + 24L * 60L * 60_000L)
    }

    private fun publishQWeatherRain(context: Context, city: CityPage, minutes: List<QMinute>) {
        val now = System.currentTimeMillis()
        // Exclude an entirely expired response instead of clearing a current rain event.
        val usable = minutes.filter { it.timeMillis + 5L * 60_000L > now }.sortedBy { it.timeMillis }
        if (usable.isEmpty()) throw IOException("Minute forecast expired")
        publishRain(context, city, usable.map { it.precip * 12.0 }, usable.map { it.timeMillis }, now)
    }

    private fun publishCaiyunRain(context: Context, city: CityPage, minutely: Minutely) {
        val values = (minutely.precipitation2h ?: minutely.precipitation)!!
        val now = System.currentTimeMillis()
        val interval = (if (minutely.precipitation2h != null) 120.0 else 60.0) / values.size
        publishRain(context, city, values, values.indices.map { now + (it * interval * 60_000L).toLong() }, now)
    }

    private fun publishRain(context: Context, city: CityPage, values: List<Double>, times: List<Long>, now: Long) {
        val first = values.indexOfFirst { it > WeatherRainPresentation.RAIN_THRESHOLD_MM_PER_HOUR }
        if (first < 0) {
            WeatherNotificationCenter.reconcileRain(context, null)
            return
        }
        val peak = values.indices.maxByOrNull { values[it] }!!
        val category = WeatherRainPresentation.category(values[peak])
        val summary = WeatherRainPresentation.summary(values, times, category, now)
        if (summary.isBlank()) throw IOException("Rain summary unavailable")
        WeatherNotificationCenter.reconcileRain(context, WeatherRainEvent(
            cityKey = WeatherNotificationLedger.cityKey(city), locationName = city.preciseRainNotificationLocation(),
            firstRainAt = times[first], category = category, categoryRank = WeatherRainPresentation.categoryRank(category),
            summary = summary, peak = WeatherRainPresentation.peakText(times[peak], values[peak], category)))
    }
}
