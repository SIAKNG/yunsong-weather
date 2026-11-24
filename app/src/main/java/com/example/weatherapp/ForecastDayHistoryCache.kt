package com.example.weatherapp

import android.content.Context
import com.google.gson.Gson
import java.util.Calendar
import java.util.Locale

/** Saved source forecasts, not reconstructed observations. Roll over by calendar day on every read. */
internal object ForecastDayHistoryPolicy {
    fun startOfDay(now: Long, offset: Int = 0): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DATE, offset)
    }.timeInMillis

    fun merge(old: List<ForecastHour>, incoming: List<ForecastHour>, now: Long): List<ForecastHour> {
        val begin = startOfDay(now, -1)
        val end = startOfDay(now, 17)
        return (old + incoming).filter { it.time in begin until end }
            .groupBy { it.time / ForecastDetailData.HOUR }.values.map { rows ->
                val latest = rows.last()
                fun value(pick: (ForecastHour) -> Double?) = rows.asReversed().firstNotNullOfOrNull { pick(it)?.takeIf(Double::isFinite) }
                latest.copy(time = latest.time / ForecastDetailData.HOUR * ForecastDetailData.HOUR,
                    skycon = rows.asReversed().firstNotNullOfOrNull { it.skycon },
                    temperature = value { it.temperature }, feelsLike = value { it.feelsLike },
                    wind = value { it.wind }, uv = value { it.uv }, humidity = value { it.humidity }, aqi = value { it.aqi },
                    pressureHpa = value { it.pressureHpa }, visibilityKm = value { it.visibilityKm },
                    precipitationProbability = value { it.precipitationProbability },
                    precipitationAmountMm = value { it.precipitationAmountMm }, gustKmh = value { it.gustKmh },
                    windDirectionDegrees = value { it.windDirectionDegrees },
                    isHistorical = latest.time / ForecastDetailData.HOUR < now / ForecastDetailData.HOUR)
            }.sortedBy { it.time }
    }

    /** Missing hours remain explicit gaps. No daily value is copied into an invented hourly series. */
    fun slots(date: String, rows: List<ForecastHour>, now: Long = System.currentTimeMillis()): List<ForecastHour> {
        val start = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(date)?.time ?: return emptyList()
        val end = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DATE, 1) }.timeInMillis
        val byHour = rows.filter { it.time in start until end }.associateBy { it.time / ForecastDetailData.HOUR }
        return generateSequence(start) { it + ForecastDetailData.HOUR }.takeWhile { it < end }.map { time ->
            (byHour[time / ForecastDetailData.HOUR] ?: ForecastHour(time, null, null, null, null, null, null, null))
                .copy(isHistorical = time / ForecastDetailData.HOUR < now / ForecastDetailData.HOUR)
        }.toList()
    }
}

internal object ForecastDayHistoryCache {
    private const val PREFS = "forecast_day_history_v1"
    private val gson = Gson()
    private data class Record(val savedAt: Long, val hours: List<ForecastHour>)
    private fun key(context: Context, page: CityPage): String {
        val source=WeatherSettings.dataSource(context)+"_r"+(if(BuildConfig.USER_CONFIGURABLE) UserApiConfiguration.revision(context) else 0)
        return String.format(Locale.US,"%s_%.4f_%.4f",source,page.lat,page.lng)
    }

    fun load(context: Context, page: CityPage, now: Long = System.currentTimeMillis()): List<ForecastHour> = synchronized(this) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val exact = key(context, page)
        val prefix = exact.substringBeforeLast('_').substringBeforeLast('_') + "_"
        fun parse(raw: String?): Record? = raw?.let {
            runCatching { gson.fromJson(WeatherDiskCache.unpack(it), Record::class.java) }.getOrNull()
        }?.takeIf { now - it.savedAt in 0..(72L * ForecastDetailData.HOUR) }
        val stable = prefix + "location"
        if (page.isCurrentLocation &&
            WeatherCache.nearby(page, Double.fromBits(prefs.getLong("$stable@lat", Double.NaN.toBits())),
                Double.fromBits(prefs.getLong("$stable@lng", Double.NaN.toBits())))
        ) {
            val record = parse(prefs.getString(stable, null))
            if (record != null) return@synchronized ForecastDayHistoryPolicy.merge(emptyList(), record.hours.orEmpty(), now)
        }
        val records = if (!page.isCurrentLocation) {
            listOfNotNull(parse(prefs.getString(exact, null)))
        } else {
            prefs.all.mapNotNull { (entry, value) ->
                if (!entry.startsWith(prefix) || value !is String) return@mapNotNull null
                val coordinates = entry.removePrefix(prefix).split('_', limit = 2)
                val lat = coordinates.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = coordinates.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                if (WeatherCache.nearby(page, lat, lng)) parse(value) else null
            }.sortedBy { it.savedAt }
        }
        ForecastDayHistoryPolicy.merge(emptyList(), records.flatMap { it.hours.orEmpty() }, now)
    }

    fun save(context: Context, page: CityPage, hours: List<ForecastHour>, now: Long = System.currentTimeMillis()) = synchronized(this) {
        if (hours.isEmpty()) return
        val merged = ForecastDayHistoryPolicy.merge(load(context, page, now), hours, now)
        val exact = key(context, page)
        val target = if (page.isCurrentLocation) exact.substringBeforeLast('_').substringBeforeLast('_') + "_location" else exact
        val prefs = context.getSharedPreferences(PREFS, 0)
        val packed = WeatherDiskCache.pack(gson.toJson(Record(now, merged)))
        WeatherDiskCache.write(context, prefs) {
            putString(target, packed)
            putLong("$target@at", now)
            if (page.isCurrentLocation) {
                putLong("$target@lat", page.lat.toBits())
                putLong("$target@lng", page.lng.toBits())
            }
        }
    }

    fun fromCaiyun(result: Result, now: Long = System.currentTimeMillis()): List<ForecastHour> {
        val hourly = result.hourly ?: return emptyList()
        fun hour(time: String?) = time?.let(TimeUtil::parse)?.let { it / ForecastDetailData.HOUR }
        fun <T> keyed(rows: List<T>?, at: (T) -> String?) = rows.orEmpty()
            .mapNotNull { row -> hour(at(row))?.let { it to row } }.toMap()
        val temp = keyed(hourly.temperature) { it.datetime }
        val feels = keyed(hourly.apparentTemperature) { it.datetime }
        val humidity = keyed(hourly.humidity) { it.datetime }
        val pressure = keyed(hourly.pressure) { it.datetime }
        val visibility = keyed(hourly.visibility) { it.datetime }
        val gust = keyed(hourly.gust) { it.datetime }
        val wind = keyed(hourly.wind) { it.datetime }
        val rain = keyed(hourly.precipitation) { it.datetime }
        val uv = keyed(hourly.lifeIndex?.ultraviolet) { it.datetime }
        val sky = keyed(hourly.skycon) { it.datetime }
        val air = keyed(hourly.airQuality?.aqi) { it.datetime }
        val hours = listOf(temp, feels, humidity, pressure, visibility, gust, wind, rain, uv, sky, air)
            .flatMap { it.keys }.toSortedSet()
        return hours.map { h ->
            ForecastHour(h * ForecastDetailData.HOUR, sky[h]?.value, temp[h]?.value, feels[h]?.value,
                wind[h]?.speed, uv[h]?.index?.toDoubleOrNull(), humidity[h]?.value?.times(100), air[h]?.value?.chn,
                h < now / ForecastDetailData.HOUR, wind[h]?.direction, rain[h]?.probability, rain[h]?.value,
                gust[h]?.value, pressure[h]?.value?.div(100), visibility[h]?.value)
        }
    }

    fun fromCaiyunResponse(response: CaiyunResponse, now: Long = System.currentTimeMillis()): List<ForecastHour> {
        val result = response.result ?: return emptyList()
        val hours = fromCaiyun(result, now)
        val realtime = result.realtime ?: return hours
        val timestamp = response.serverTime?.let { if (it in 1..9_999_999_999L) it * 1_000 else it } ?: return hours
        if (now - timestamp !in -60_000L..(2L * ForecastDetailData.HOUR)) return hours
        return hours + ForecastHour(timestamp, realtime.skycon, realtime.temperature, realtime.apparentTemperature,
            realtime.wind?.speed, realtime.lifeIndex?.ultraviolet?.index?.toDoubleOrNull(),
            realtime.humidity?.let { if (it <= 1.0) it * 100.0 else it },
            realtime.airQuality?.aqi?.chn ?: realtime.aqi?.chn, windDirectionDegrees = realtime.wind?.direction,
            gustKmh = realtime.gust, pressureHpa = realtime.pressure?.let {
                when { it > 20_000 -> it / 100.0; it < 150 -> it * 10.0; else -> it }
            }, visibilityKm = realtime.visibility)
    }

    fun fromQWeather(bundle: QWeatherBundle): List<ForecastHour> {
        val weather = bundle.hourly.orEmpty().associateBy { it.timeMillis / ForecastDetailData.HOUR }
        val aqi = bundle.aqiHourly.orEmpty().associateBy { it.timeMillis / ForecastDetailData.HOUR }
        val hours = (weather.keys + aqi.keys).sorted().map { hour ->
            val point = weather[hour]
            ForecastHour(hour * ForecastDetailData.HOUR, point?.skycon, point?.temp, point?.feelsLike,
                point?.windSpeedKmh, point?.uvIndex, point?.humidityPercent, aqi[hour]?.aqi,
                windDirectionDegrees = point?.windDirectionDegrees, precipitationProbability = point?.pop?.div(100.0),
                precipitationAmountMm = point?.precipitationAmountMm, gustKmh = point?.gustKmh,
                pressureHpa = point?.pressureHpa, visibilityKm = point?.visibilityKm)
        }
        val now = bundle.now ?: return hours
        if (now.obsTimeMillis <= 0L) return hours
        return hours + ForecastHour(now.obsTimeMillis, now.skycon, now.temp, now.feelsLike, null, null,
            null, null, windDirectionDegrees = now.windDirDeg, pressureHpa = now.pressureHpa)
    }
}

internal fun ForecastHour.asHistoryMetric() = HourlyMetricPoint(
    timeMillis = time, temperature = temperature, feelsLike = feelsLike,
    precipitationProbability = precipitationProbability, precipitationAmountMm = precipitationAmountMm,
    uv = uv, windKmh = wind, gustKmh = gustKmh, windDirectionDegrees = windDirectionDegrees,
    pressureHpa = pressureHpa, visibilityKm = visibilityKm, humidityPercent = humidity
)
