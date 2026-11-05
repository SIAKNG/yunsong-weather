package com.example.weatherapp

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class WidgetHour(val time: Long,val temperature: Double?,val sky: String?,val rain: Double?)
internal data class WidgetDay(val date: String,val low: Double?,val high: Double?,val sky: String?)
internal data class WidgetWeather(
    val city: CityPage,val source: String,val observed: Long,val saved: Long,
    val temperature: Double?,val feels: Double?,val sky: String?,val humidity: Double?,val wind: Double?,
    val aqi: Double?,val hours: List<WidgetHour>,val days: List<WidgetDay>,val night: Boolean?=null,
    val forecast: List<ForecastHour> = emptyList(), val today: ForecastDaySnapshot? = null,
    val sunrise: String? = null, val sunset: String? = null
)
internal object WeatherWidgetPolicy {
    fun sourceKey(c: Context)=WeatherSettings.dataSource(c)+"_r"+(if(BuildConfig.USER_CONFIGURABLE) UserApiConfiguration.revision(c) else 0)
    const val REFRESH_MS=30L*60_000
    fun stale(observed: Long,saved: Long,now: Long)=
        !WeatherCachePolicy.volatileUsable(WeatherCachePolicy.observationAge(now,now-saved,observed))
    fun size(kind: String)=when(kind) {"compact"->2 to 1;"square","air","rain"->2 to 2;"large","daily_summary"->4 to 4;else->4 to 2}
    fun futureDates(count: Int,now: Long=System.currentTimeMillis()): List<String> {
        val calendar=java.util.Calendar.getInstance().apply{timeInMillis=now}
        val format=SimpleDateFormat("yyyy-MM-dd",Locale.CHINA)
        return (1..count).map{calendar.add(java.util.Calendar.DATE,1);format.format(calendar.time)}
    }
    fun validCity(city: CityPage)=city.lat.isFinite() && city.lng.isFinite() && city.lat in -90.0..90.0 && city.lng in -180.0..180.0
    fun sameCity(a: CityPage,b: CityPage)=kotlin.math.abs(a.lat-b.lat)<.0005 && kotlin.math.abs(a.lng-b.lng)<.0005
    fun usableObservation(observed: Long,now: Long)=observed>0 && observed<=now+WeatherCachePolicy.CLOCK_SKEW_MS
}
/** No widget weather payload: every render reads the homepage's source/revision-scoped cache. */
internal object WeatherWidgetData {
    fun load(context: Context,city: CityPage,now: Long=System.currentTimeMillis()): WidgetWeather? {
        if(!WeatherWidgetPolicy.validCity(city)) return null
        val saved=WeatherCache.savedAt(context,city)
        if(saved<=0) return null
        if(WeatherSettings.useQWeather(context)) {
            val b=WeatherCache.loadQWeatherDisplaySnapshot(context,city) ?: return null
            val rt=b.now ?: return null
            val observed=rt.obsTimeMillis.takeIf{it>0} ?: b.updateTimeMillis
            if(!WeatherWidgetPolicy.usableObservation(observed,now)) return null
            return WidgetWeather(city,WeatherSettings.dataSource(context),observed,saved,rt.temp,rt.feelsLike,rt.skycon,
                rt.humidity.let{if(it<=1.0) it*100 else it},rt.windSpeedKmh,b.aqi?.aqi,
                b.hourly.filter{it.timeMillis>=now-30*60_000}.sortedBy{it.timeMillis}.take(6).map{WidgetHour(it.timeMillis,it.temp,it.skycon,it.pop)},
                b.daily.filter{it.date.take(10)>=date(now)}.take(8).map{WidgetDay(it.date.take(10),it.low,it.high,it.skycon)},
                night=night(rt.skycon,b.daily.firstOrNull{it.date.take(10)==date(now)}?.sunrise,b.daily.firstOrNull{it.date.take(10)==date(now)}?.sunset,now),
                forecast=rows(context,city,ForecastDayHistoryCache.fromQWeather(b),now),
                today=b.daily.firstOrNull{it.date.take(10)==date(now)}?.let{ForecastDaySnapshot(it.date.take(10),it.skycon,it.high,it.low,HourlyForecastDetailPolicy.percent(it.precipProb))},
                sunrise=b.daily.firstOrNull{it.date.take(10)==date(now)}?.sunrise,sunset=b.daily.firstOrNull{it.date.take(10)==date(now)}?.sunset)
        }
        val b=WeatherCache.loadCaiyunDisplaySnapshot(context,city) ?: return null
        val rt=b.result?.realtime ?: return null
        val observed=b.serverTime?.times(1000) ?: saved
        if(!WeatherWidgetPolicy.usableObservation(observed,now)) return null
        val h=b.result?.hourly
        val skies=h?.skycon.orEmpty().associateBy{it.datetime}
        val rain=h?.precipitation.orEmpty().associateBy{it.datetime}
        val d=b.result?.daily
        val dailySky=d?.skycon.orEmpty().associateBy{it.date?.take(10)}
        return WidgetWeather(city,WeatherSettings.dataSource(context),observed,saved,rt.temperature,rt.apparentTemperature,
            rt.skycon,rt.humidity?.times(100),rt.wind?.speed,rt.airQuality?.aqi?.chn ?: rt.aqi?.chn,
            h?.temperature.orEmpty().mapNotNull {v ->
                val t=v.datetime?.let(TimeUtil::parse) ?: return@mapNotNull null
                if(t<now-30*60_000) null else WidgetHour(t,v.value,skies[v.datetime]?.value,rain[v.datetime]?.probability)
            }.sortedBy{it.time}.take(6),
            d?.temperature.orEmpty().filter{it.date.orEmpty().take(10)>=date(now)}.take(8).map{WidgetDay(it.date.orEmpty().take(10),it.min,it.max,dailySky[it.date?.take(10)]?.value)},
            night=night(rt.skycon,d?.astro.orEmpty().firstOrNull{it.date?.take(10)==date(now)}?.sunrise?.time,
                d?.astro.orEmpty().firstOrNull{it.date?.take(10)==date(now)}?.sunset?.time,now),
            forecast=rows(context,city,ForecastDayHistoryCache.fromCaiyun(b.result!!,now),now),
            today=d?.temperature.orEmpty().firstOrNull{it.date?.take(10)==date(now)}?.let{v->ForecastDaySnapshot(date(now),dailySky[date(now)]?.value,v.max,v.min,HourlyForecastDetailPolicy.percent(d?.precipitation.orEmpty().firstOrNull{it.date?.take(10)==date(now)}?.probability))},
            sunrise=d?.astro.orEmpty().firstOrNull{it.date?.take(10)==date(now)}?.sunrise?.time,
            sunset=d?.astro.orEmpty().firstOrNull{it.date?.take(10)==date(now)}?.sunset?.time)
    }
    private fun rows(c: Context,city: CityPage,incoming: List<ForecastHour>,now: Long): List<ForecastHour> {
        val history=ForecastDayHistoryCache.load(c,city,now)
        val metrics=MetricHistoryTimelineCache.load(c,city,"caiyun_v26")
        val captured=ForecastDetailData.build(metrics?.points.orEmpty(),emptyMap(),metrics?.airQualityPoints.orEmpty(),now,48)
        return ForecastDayHistoryPolicy.merge(history+captured,incoming,now)
    }
    private fun night(sky: String?,rise: String?,set: String?,now: Long): Boolean {
        fun parse(value: String?): Long? = TimeUtil.parse(value) ?: value?.takeIf{it.matches(Regex("\\d{2}:\\d{2}"))}?.let {
            runCatching{SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).apply{isLenient=false}.parse("${date(now)} $it")?.time}.getOrNull()
        }
        val a=parse(rise);val b=parse(set)
        return if(a!=null && b!=null && a<b) now<a || now>=b else when {
            sky?.contains("NIGHT")==true -> true
            sky?.contains("DAY")==true -> false
            else -> java.util.Calendar.getInstance().apply{timeInMillis=now}.get(java.util.Calendar.HOUR_OF_DAY) !in 6..17
        }
    }
    fun sourceName(source: String)=when(source){WeatherSettings.SOURCE_QWEATHER->"和风天气";"open_meteo"->"Open-Meteo";else->"彩云天气"}
    private fun date(t: Long)=SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(Date(t))
}
