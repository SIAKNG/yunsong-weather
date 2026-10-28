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
        val record = context.getSharedPreferences(PREFS, 0).getString(key(context, page), null)
            ?.let { runCatching { gson.fromJson(it, Record::class.java) }.getOrNull() } ?: return emptyList()
        if (now - record.savedAt !in 0..(72L * ForecastDetailData.HOUR)) return emptyList()
        ForecastDayHistoryPolicy.merge(record.hours.orEmpty(), emptyList(), now)
    }

    fun save(context: Context, page: CityPage, hours: List<ForecastHour>, now: Long = System.currentTimeMillis()) = synchronized(this) {
        if (hours.isEmpty()) return
        val merged = ForecastDayHistoryPolicy.merge(load(context, page, now), hours, now)
        context.getSharedPreferences(PREFS, 0).edit().putString(key(context, page), gson.toJson(Record(now, merged))).apply()
    }

    fun fromCaiyun(result: Result, now: Long = System.currentTimeMillis()): List<ForecastHour> {
        val hourly = result.hourly ?: return emptyList()
        fun values(items: List<HourlyValue>?) = items.orEmpty().associate { it.datetime to it.value }
        val feels = values(hourly.apparentTemperature); val humidity = values(hourly.humidity)
        val pressure = values(hourly.pressure); val visibility = values(hourly.visibility); val gust = values(hourly.gust)
        val wind = hourly.wind.orEmpty().associateBy { it.datetime }; val rain = hourly.precipitation.orEmpty().associateBy { it.datetime }
        val uv = hourly.lifeIndex?.ultraviolet.orEmpty().associateBy { it.datetime }
        val sky = hourly.skycon.orEmpty().associateBy { it.datetime }
        val air = hourly.airQuality?.aqi.orEmpty().associateBy { it.datetime }
        return hourly.temperature.orEmpty().mapNotNull { point ->
            val time = point.datetime?.let(TimeUtil::parse) ?: return@mapNotNull null
            ForecastHour(time, sky[point.datetime]?.value, point.value, feels[point.datetime], wind[point.datetime]?.speed,
                uv[point.datetime]?.index?.toDoubleOrNull(), humidity[point.datetime]?.times(100), air[point.datetime]?.value?.chn,
                time < now / ForecastDetailData.HOUR * ForecastDetailData.HOUR, wind[point.datetime]?.direction,
                rain[point.datetime]?.probability, rain[point.datetime]?.value, gust[point.datetime],
                pressure[point.datetime]?.div(100), visibility[point.datetime])
        }
    }

    fun fromQWeather(bundle: QWeatherBundle): List<ForecastHour> {
        val aqi = bundle.aqiHourly.orEmpty().associateBy { it.timeMillis / ForecastDetailData.HOUR }
        return bundle.hourly.orEmpty().map { point ->
            ForecastHour(point.timeMillis, point.skycon, point.temp, point.feelsLike, point.windSpeedKmh, point.uvIndex,
                point.humidityPercent, aqi[point.timeMillis / ForecastDetailData.HOUR]?.aqi,
                precipitationProbability = point.pop, pressureHpa = point.pressureHpa, visibilityKm = point.visibilityKm)
        }
    }
}
