package com.example.weatherapp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Credential-free source. Coordinates rounded to city-scale precision; no device/account headers. */
object OpenMeteoSource {
    private val china = ZoneOffset.ofHours(8)
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(china)
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(china)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    val hourlyFields = listOf("temperature_2m", "apparent_temperature", "relative_humidity_2m",
        "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "surface_pressure", "visibility",
        "uv_index", "weather_code", "precipitation_probability", "precipitation", "is_day")
    val pollutionFields = listOf("pm2_5", "pm10", "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide", "ozone")

    fun weatherUrl(lat: Double, lng: Double): String {
        require(lat.isFinite() && lat in -90.0..90.0 && lng.isFinite() && lng in -180.0..180.0)
        return "https://api.open-meteo.com/v1/forecast?" + String.format(Locale.US,"latitude=%.2f&longitude=%.2f",lat,lng) +
            "&current=" + (hourlyFields - "precipitation_probability").joinToString(",") +
            "&hourly=" + hourlyFields.joinToString(",") +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&forecast_days=15&past_days=1&timezone=Asia%2FShanghai&timeformat=unixtime"
    }
    fun airUrl(lat: Double, lng: Double): String =
        "https://air-quality-api.open-meteo.com/v1/air-quality?" + String.format(Locale.US,"latitude=%.2f&longitude=%.2f",lat,lng) +
            "&hourly=" + pollutionFields.joinToString(",") +
            "&forecast_days=5&past_days=1&timezone=Asia%2FShanghai&timeformat=unixtime"

    fun fetch(lat: Double, lng: Double): GatewayResult<CaiyunResponse> = try {
        val weather = get(weatherUrl(lat,lng))
        // An independent AQ request failure must not discard otherwise valid weather.
        val air = runCatching { get(airUrl(lat,lng)) }.getOrNull()
        GatewayResult(parse(weather,air),200)
    } catch (_: Exception) { GatewayResult(code=0,message="Open-Meteo 暂不可用，请稍后重试") }

    private fun get(url: String): String = client.newCall(Request.Builder().url(url)
        .header("User-Agent","YunsongWeather/" + BuildConfig.VERSION_NAME).build()).execute().use { r ->
        check(r.isSuccessful)
        val input = requireNotNull(r.body()).byteStream()
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n=input.read(buffer);if(n<0) break
            check(output.size()+n<=1_500_000);output.write(buffer,0,n)
        }
        output.toString("UTF-8")
    }

    /** Reuse the existing presentation DTO, without representing the data as a Caiyun response. */
    fun parse(weather: String, air: String?, receivedAt: Long = System.currentTimeMillis()): CaiyunResponse {
        val w=JsonParser.parseString(weather).asJsonObject
        check(w.get("error")?.asBoolean != true)
        val current=requireNotNull(w.getAsJsonObject("current"))
        requireNotNull(number(current,"temperature_2m"))
        val h=requireNotNull(w.getAsJsonObject("hourly"))
        val d=requireNotNull(w.getAsJsonObject("daily"))
        fun times(o: JsonObject)=o.getAsJsonArray("time")?.mapNotNull { runCatching {it.asLong}.getOrNull() }.orEmpty()
        val ht=times(h);val dt=times(d)
        fun timestamp(t: Long)=Instant.ofEpochSecond(t).atOffset(china).toString()
        fun series(name: String, scale: Double=1.0)=ht.mapIndexed { i,t -> HourlyValue(timestamp(t),at(h,name,i)?.times(scale)) }
        val ah=runCatching { air?.let { JsonParser.parseString(it).asJsonObject.getAsJsonObject("hourly") } }.getOrNull()
        val atimes=ah?.let(::times).orEmpty()
        fun pollutant(name: String,scale: Double=1.0)=atimes.mapIndexed { i,t -> HourlyValue(timestamp(t),at(ah,name,i)?.times(scale)) }
        val airValues=atimes.mapIndexed { i,t ->
            // Reference estimate from model concentrations, NOT a Chinese station observation.
            HourlyAqiValue(timestamp(t),Aqi(chn=OpenMeteo.chinaAqi(at(ah,"pm2_5",i),at(ah,"pm10",i),
                at(ah,"nitrogen_dioxide",i),at(ah,"sulphur_dioxide",i),at(ah,"carbon_monoxide",i),at(ah,"ozone",i))))
        }
        val aq=if(atimes.isEmpty()) null else HourlyAirQuality(airValues,pollutant("pm2_5"),pollutant("pm10"),
            pollutant("nitrogen_dioxide"),pollutant("ozone"),pollutant("carbon_monoxide",0.001),pollutant("sulphur_dioxide"))
        val ci=atimes.indexOfLast { it*1000<=receivedAt }.takeIf {it>=0}
        fun pollutantNow(name: String)=ci?.let { at(ah,name,it) }
        val uv=number(current,"uv_index")
        val rt=RealTime(status="ok",temperature=number(current,"temperature_2m"),
            apparentTemperature=number(current,"apparent_temperature"),
            skycon=skycon(number(current,"weather_code")?.toInt(),number(current,"is_day")!=0.0),
            humidity=number(current,"relative_humidity_2m")?.div(100),visibility=number(current,"visibility")?.div(1000),
            pressure=number(current,"surface_pressure")?.times(100),
            wind=Wind(number(current,"wind_speed_10m"),number(current,"wind_direction_10m")),
            gust=number(current,"wind_gusts_10m"),
            precipitation=Precipitation(local=PrecipLocal(intensity=number(current,"precipitation"))),
            lifeIndex=LifeIndex(ultraviolet=uv?.let {LifeIndexItem(it.toString(),null)}),
            airQuality=ci?.let { AirQuality(pollutantNow("pm2_5"),pollutantNow("pm10"),pollutantNow("ozone"),
                pollutantNow("sulphur_dioxide"),pollutantNow("nitrogen_dioxide"),pollutantNow("carbon_monoxide")?.div(1000),airValues[it].value) })
        val hourly=Hourly(status="ok",temperature=series("temperature_2m"),apparentTemperature=series("apparent_temperature"),
            precipitation=ht.mapIndexed {i,t -> HourlyValue(timestamp(t),at(h,"precipitation",i),at(h,"precipitation_probability",i))},
            wind=ht.mapIndexed {i,t -> HourlyWind(timestamp(t),at(h,"wind_speed_10m",i),at(h,"wind_direction_10m",i))},
            gust=series("wind_gusts_10m"),humidity=series("relative_humidity_2m",0.01),pressure=series("surface_pressure",100.0),
            visibility=series("visibility",0.001),skycon=ht.mapIndexed {i,t -> HourlySkycon(timestamp(t),skycon(at(h,"weather_code",i)?.toInt(),at(h,"is_day",i)!=0.0))},
            airQuality=aq,lifeIndex=HourlyLifeIndex(ht.mapIndexed {i,t -> HourlyUvItem(timestamp(t),at(h,"uv_index",i)?.toString())}))
        val daily=Daily(status="ok",temperature=dt.mapIndexed {i,t -> DailyTemp(date.format(Instant.ofEpochSecond(t)),at(d,"temperature_2m_max",i),at(d,"temperature_2m_min",i))},
            skycon=dt.mapIndexed {i,t -> DailySkycon(date.format(Instant.ofEpochSecond(t)),skycon(at(d,"weather_code",i)?.toInt(),true))},
            precipitation=dt.mapIndexed {i,t -> DailyPrecip(date=date.format(Instant.ofEpochSecond(t)),probability=at(d,"precipitation_probability_max",i))},
            astro=dt.mapIndexed {i,t -> Astro(date.format(Instant.ofEpochSecond(t)),
                at(d,"sunrise",i)?.toLong()?.takeIf {it>0}?.let {AstroTime(clock.format(Instant.ofEpochSecond(it)))},
                at(d,"sunset",i)?.toLong()?.takeIf {it>0}?.let {AstroTime(clock.format(Instant.ofEpochSecond(it)))}) })
        return CaiyunResponse("ok","open-meteo",number(current,"time")?.toLong() ?: receivedAt/1000,Result(rt,hourly=hourly,daily=daily))
    }
    fun coverage(response: CaiyunResponse, now: Long=System.currentTimeMillis()): List<ApiCapabilityRow> {
        val r=response.result;val h=r?.hourly;val current=now/3_600_000L*3_600_000L
        fun valid(list: List<HourlyValue>?)=list.orEmpty().count {it.value!=null && (it.datetime?.let(TimeUtil::parse) ?: 0)>=current}
        fun row(feature: String,ok: Boolean,note: String)=ApiCapabilityRow("Open-Meteo",feature,if(ok) ApiSupport.SUPPORTED else ApiSupport.MISSING,note)
        val air=h?.airQuality
        return listOf(
            row("当前天气",r?.realtime?.temperature!=null,"数值模式当前天气，非附近站点实测"),
            row("48小时气温/体感",valid(h?.temperature)>=48 && valid(h?.apparentTemperature)>=48,"气温 ${valid(h?.temperature)} 小时；体感 ${valid(h?.apparentTemperature)} 小时"),
            row("风/湿度/气压/能见度",valid(h?.humidity)>=48 && valid(h?.pressure)>=48 && valid(h?.visibility)>=48 && h?.wind.orEmpty().count {it.speed!=null}>=48,"风向、风速、阵风；湿度、站点气压和能见度按时间对齐"),
            row("逐小时紫外线",valid(h?.lifeIndex?.ultraviolet?.map {HourlyValue(it.datetime,it.index?.toDoubleOrNull())})>=48,"按接口原始小时值展示"),
            row("降雨概率及小时雨量",valid(h?.precipitation)>=48,"小时预报，不是雷达分钟临近预报"),
            row("多日天气",r?.daily?.temperature.orEmpty().size>=15,"返回 ${r?.daily?.temperature.orEmpty().size} 个日期（包含历史日）"),
            row("历史/昨日对比",h?.temperature.orEmpty().count {it.value!=null && (it.datetime?.let(TimeUtil::parse) ?: Long.MAX_VALUE)<current}>=24,"来自历史模式/预报数据，不标成实测"),
            ApiCapabilityRow("Open-Meteo","AQI与六项污染物",if(air?.aqi.orEmpty().any {it.value?.chn!=null}) ApiSupport.PARTIAL else ApiSupport.MISSING,
                "参考AQI ${air?.aqi.orEmpty().count {it.value?.chn!=null && (it.datetime?.let(TimeUtil::parse) ?: 0)>=current}} 小时；六污染物最短 ${listOf(air?.pm25,air?.pm10,air?.no2,air?.o3,air?.co,air?.so2).minOf {valid(it)}} 小时。模型参考估算，非监测站官方AQI"),
            row("日出日落",r?.daily?.astro.orEmpty().any {it.sunrise!=null && it.sunset!=null},"晨昏、轨迹和霞光时段由App本地参考计算"),
            ApiCapabilityRow("Open-Meteo","雷达分钟降雨",ApiSupport.MISSING,"无中国雷达分钟临近预报；不以插值冒充"),
            ApiCapabilityRow("Open-Meteo","官方灾害预警",ApiSupport.MISSING,"该源不提供国内官方预警"),
            ApiCapabilityRow("Open-Meteo","官方生活指数",ApiSupport.MISSING,"不提供和风式全量生活指数；图表通用建议仍可使用"),
            ApiCapabilityRow("App独立功能","全国空气排名",ApiSupport.LOCAL,"独立官方数据源，取决于该源可用性，不用模型AQI伪造官方名次")
        )
    }
    private fun number(o: JsonObject?,key: String): Double? = runCatching {o?.get(key)?.takeUnless {it.isJsonNull}?.asDouble?.takeIf {it.isFinite()}}.getOrNull()
    private fun at(o: JsonObject?,key: String,index: Int): Double? = runCatching {o?.getAsJsonArray(key)?.get(index)?.takeUnless {it.isJsonNull}?.asDouble?.takeIf {it.isFinite()}}.getOrNull()
    fun skycon(code: Int?,day: Boolean): String? = when(code) {
        0 -> if(day) "CLEAR_DAY" else "CLEAR_NIGHT"
        1,2 -> if(day) "PARTLY_CLOUDY_DAY" else "PARTLY_CLOUDY_NIGHT"
        3 -> "CLOUDY";45,48 -> "FOG"
        51,53,55,56,57,61,66,80 -> "LIGHT_RAIN";63,81 -> "MODERATE_RAIN";65,82 -> "HEAVY_RAIN"
        67,71,77,85 -> "LIGHT_SNOW";73,86 -> "MODERATE_SNOW";75 -> "HEAVY_SNOW"
        95,96,99 -> "STORM_RAIN";else -> null
    }
}
