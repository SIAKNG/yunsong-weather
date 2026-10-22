package com.example.weatherapp

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ===== 彩云 v2.7 返回的数据模型 =====
// 付费套餐实测：realtime/hourly(72h)/daily(7天，dailystart=-1 可含昨天)/alert 可用；
// minutely（雷达分钟级降水）未开通时不会返回。
data class CaiyunResponse(
    @SerializedName("status") val status: String? = null,
    @SerializedName("api_version") val apiVersion: String? = null,
    @SerializedName("server_time") val serverTime: Long? = null,
    @SerializedName("result") val result: Result? = null
)

data class Result(
    @SerializedName("realtime") val realtime: RealTime? = null,
    @SerializedName("minutely") val minutely: Minutely? = null,
    @SerializedName("hourly") val hourly: Hourly? = null,
    @SerializedName("daily") val daily: Daily? = null,
    @SerializedName("alert") val alert: WeatherAlert? = null,
    @SerializedName("forecast_keypoint") val forecastKeypoint: String? = null
)

// ===== 实时天气 =====
data class RealTime(
    @SerializedName("status") val status: String? = null,
    @SerializedName("temperature") val temperature: Double? = null,
    @SerializedName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerializedName("skycon") val skycon: String? = null,
    @SerializedName("humidity") val humidity: Double? = null,
    @SerializedName("cloudrate") val cloudrate: Double? = null,
    @SerializedName("visibility") val visibility: Double? = null,
    @SerializedName("dswrf") val dswrf: Double? = null,
    @SerializedName("wind") val wind: Wind? = null,
    @SerializedName("pressure") val pressure: Double? = null,
    @SerializedName("gust") val gust: Double? = null,
    @SerializedName("precipitation") val precipitation: Precipitation? = null,
    @SerializedName("air_quality") val airQuality: AirQuality? = null,
    @SerializedName("life_index") val lifeIndex: LifeIndex? = null,
    // 旧版兼容：个别老 token 仍在顶层直接返回 aqi/pm25
    @SerializedName("aqi") val aqi: Aqi? = null,
    @SerializedName("pm25") val pm25: Double? = null
)

data class Wind(
    @SerializedName("speed") val speed: Double? = null,
    @SerializedName("direction") val direction: Double? = null
)

data class Precipitation(
    @SerializedName("local") val local: PrecipLocal? = null,
    @SerializedName("nearest") val nearest: PrecipNearest? = null
)

data class PrecipLocal(
    @SerializedName("status") val status: String? = null,
    @SerializedName("datasource") val datasource: String? = null,
    @SerializedName("intensity") val intensity: Double? = null
)

data class PrecipNearest(
    @SerializedName("status") val status: String? = null,
    @SerializedName("distance") val distance: Double? = null,
    @SerializedName("intensity") val intensity: Double? = null
)

data class AirQuality(
    @SerializedName("pm25") val pm25: Double? = null,
    @SerializedName("pm10") val pm10: Double? = null,
    @SerializedName("o3") val o3: Double? = null,
    @SerializedName("so2") val so2: Double? = null,
    @SerializedName("no2") val no2: Double? = null,
    @SerializedName("co") val co: Double? = null,
    @SerializedName("aqi") val aqi: Aqi? = null,
    @SerializedName("description") val description: AirQualityDesc? = null
)

data class AirQualityDesc(
    @SerializedName("chn") val chn: String? = null,
    @SerializedName("usa") val usa: String? = null
)

data class Aqi(
    @SerializedName("chn") val chn: Double? = null,
    @SerializedName("usa") val usa: Double? = null
)

data class LifeIndex(
    @SerializedName("ultraviolet") val ultraviolet: LifeIndexItem? = null,
    @SerializedName("comfort") val comfort: LifeIndexItem? = null
)

data class LifeIndexItem(
    @SerializedName("index") val index: String? = null,
    @SerializedName("desc") val desc: String? = null
)

// ===== 分钟级降水（套餐未开通时不返回） =====
data class Minutely(
    @SerializedName("status") val status: String? = null,
    @SerializedName("datasource") val datasource: String? = null,
    @SerializedName("precipitation_2h") val precipitation2h: List<Double>? = null,
    @SerializedName("precipitation") val precipitation: List<Double>? = null,
    @SerializedName("accumulation") val accumulation: List<Double>? = null,
    @SerializedName("probability") val probability: List<Double>? = null,
    @SerializedName("description") val description: String? = null
)

// ===== 逐小时预报 =====
data class Hourly(
    @SerializedName("status") val status: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("temperature") val temperature: List<HourlyValue>? = null,
    @SerializedName("apparent_temperature") val apparentTemperature: List<HourlyValue>? = null,
    @SerializedName("precipitation") val precipitation: List<HourlyValue>? = null,
    @SerializedName("wind") val wind: List<HourlyWind>? = null,
    @SerializedName("gust") val gust: List<HourlyValue>? = null,
    @SerializedName("humidity") val humidity: List<HourlyValue>? = null,
    @SerializedName("cloudrate") val cloudrate: List<HourlyValue>? = null,
    @SerializedName("skycon") val skycon: List<HourlySkycon>? = null,
    @SerializedName("pressure") val pressure: List<HourlyValue>? = null,
    @SerializedName("visibility") val visibility: List<HourlyValue>? = null,
    @SerializedName("dswrf") val dswrf: List<HourlyValue>? = null,
    @SerializedName("air_quality") val airQuality: HourlyAirQuality? = null,
    // v2.7 逐小时紫外线：hourly.life_index.ultraviolet[]
    @SerializedName("life_index") val lifeIndex: HourlyLifeIndex? = null
)

data class HourlyLifeIndex(
    @SerializedName("ultraviolet") val ultraviolet: List<HourlyUvItem>? = null
)

data class HourlyUvItem(
    @SerializedName("datetime") val datetime: String? = null,
    @SerializedName("index") val index: String? = null,
    @SerializedName("desc") val desc: String? = null
)

data class HourlyValue(
    @SerializedName("datetime") val datetime: String? = null,
    @SerializedName("value") val value: Double? = null,
    @SerializedName("probability") val probability: Double? = null
)

data class HourlyWind(
    @SerializedName("datetime") val datetime: String? = null,
    @SerializedName("speed") val speed: Double? = null,
    @SerializedName("direction") val direction: Double? = null
)

data class HourlySkycon(
    @SerializedName("datetime") val datetime: String? = null,
    @SerializedName("value") val value: String? = null
)

data class HourlyAirQuality(
    @SerializedName("aqi") val aqi: List<HourlyAqiValue>? = null,
    @SerializedName("pm25") val pm25: List<HourlyValue>? = null,
    @SerializedName("pm10") val pm10: List<HourlyValue>? = null,
    @SerializedName("no2") val no2: List<HourlyValue>? = null,
    @SerializedName("o3") val o3: List<HourlyValue>? = null,
    @SerializedName("co") val co: List<HourlyValue>? = null,
    @SerializedName("so2") val so2: List<HourlyValue>? = null
)

data class HourlyAqiValue(
    @SerializedName("datetime") val datetime: String? = null,
    @SerializedName("value") val value: Aqi? = null
)

// ===== 逐日预报 =====
data class Daily(
    @SerializedName("status") val status: String? = null,
    @SerializedName("astro") val astro: List<Astro>? = null,
    @SerializedName("temperature") val temperature: List<DailyTemp>? = null,
    @SerializedName("temperature_08h_20h") val temperature08h20h: List<DailyTemp>? = null,
    @SerializedName("temperature_20h_32h") val temperature20h32h: List<DailyTemp>? = null,
    @SerializedName("precipitation") val precipitation: List<DailyPrecip>? = null,
    @SerializedName("precipitation_08h_20h") val precipitation08h20h: List<DailyPrecip>? = null,
    @SerializedName("precipitation_20h_32h") val precipitation20h32h: List<DailyPrecip>? = null,
    @SerializedName("wind") val wind: List<DailyWind>? = null,
    @SerializedName("wind_08h_20h") val wind08h20h: List<DailyWind>? = null,
    @SerializedName("wind_20h_32h") val wind20h32h: List<DailyWind>? = null,
    @SerializedName("humidity") val humidity: List<DailyRange>? = null,
    @SerializedName("cloudrate") val cloudrate: List<DailyRange>? = null,
    @SerializedName("pressure") val pressure: List<DailyRange>? = null,
    @SerializedName("visibility") val visibility: List<DailyRange>? = null,
    @SerializedName("dswrf") val dswrf: List<DailyRange>? = null,
    @SerializedName("air_quality") val airQuality: DailyAirQuality? = null,
    @SerializedName("skycon") val skycon: List<DailySkycon>? = null,
    @SerializedName("skycon_08h_20h") val skycon08h20h: List<DailySkycon>? = null,
    @SerializedName("skycon_20h_32h") val skycon20h32h: List<DailySkycon>? = null,
    @SerializedName("life_index") val lifeIndex: DailyLifeIndex? = null
)

data class Astro(
    @SerializedName("date") val date: String? = null,
    @SerializedName("sunrise") val sunrise: AstroTime? = null,
    @SerializedName("sunset") val sunset: AstroTime? = null
)

data class AstroTime(
    @SerializedName("time") val time: String? = null
)

data class DailyTemp(
    @SerializedName("date") val date: String? = null,
    @SerializedName("max") val max: Double? = null,
    @SerializedName("min") val min: Double? = null,
    @SerializedName("avg") val avg: Double? = null
)

data class DailyPrecip(
    @SerializedName("date") val date: String? = null,
    @SerializedName("max") val max: Double? = null,
    @SerializedName("min") val min: Double? = null,
    @SerializedName("avg") val avg: Double? = null,
    @SerializedName("probability") val probability: Double? = null
)

data class DailyWind(
    @SerializedName("date") val date: String? = null,
    @SerializedName("max") val max: Wind? = null,
    @SerializedName("min") val min: Wind? = null,
    @SerializedName("avg") val avg: Wind? = null
)

data class DailyRange(
    @SerializedName("date") val date: String? = null,
    @SerializedName("max") val max: Double? = null,
    @SerializedName("min") val min: Double? = null,
    @SerializedName("avg") val avg: Double? = null
)

data class DailySkycon(
    @SerializedName("date") val date: String? = null,
    @SerializedName("value") val value: String? = null
)

data class DailyAirQuality(
    @SerializedName("aqi") val aqi: List<DailyAqiValue>? = null,
    @SerializedName("pm25") val pm25: List<DailyRange>? = null
)

data class DailyAqiValue(
    @SerializedName("date") val date: String? = null,
    @SerializedName("max") val max: Aqi? = null,
    @SerializedName("min") val min: Aqi? = null,
    @SerializedName("avg") val avg: Aqi? = null
)

data class DailyLifeIndex(
    @SerializedName("ultraviolet") val ultraviolet: List<DailyLifeIndexItem>? = null,
    @SerializedName("comfort") val comfort: List<DailyLifeIndexItem>? = null,
    @SerializedName("carWashing") val carWashing: List<DailyLifeIndexItem>? = null,
    @SerializedName("coldRisk") val coldRisk: List<DailyLifeIndexItem>? = null,
    @SerializedName("dressing") val dressing: List<DailyLifeIndexItem>? = null
)

data class DailyLifeIndexItem(
    @SerializedName("date") val date: String? = null,
    @SerializedName("index") val index: String? = null,
    @SerializedName("desc") val desc: String? = null
)

// ===== 天气告警 =====
data class WeatherAlert(
    @SerializedName("status") val status: String? = null,
    @SerializedName("content") val content: List<AlertContent>? = null,
    @SerializedName("adcodes") val adcodes: List<AlertAdcode>? = null
)

data class AlertContent(
    @SerializedName("alertid") val alertId: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("title") val title: String? = null,
    // v2.x 实际返回：正文在 description，类型+等级在 code（如 1602 = 雷雨大风黄色）
    @SerializedName("description") val description: String? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("province") val province: String? = null,
    @SerializedName("city") val city: String? = null,
    @SerializedName("county") val county: String? = null,
    @SerializedName("regionId") val regionId: String? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("pubtimestamp") val pubTimestamp: Long? = null,
    @SerializedName("location") val location: String? = null,
    @SerializedName("adcode") val adcode: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("typeid") val typeId: String? = null,
    @SerializedName("level") val level: String? = null,
    @SerializedName("leveltype") val levelType: String? = null,
    @SerializedName("subtype") val subtype: String? = null,
    @SerializedName("sub_type") val subType: String? = null
)

data class AlertAdcode(
    @SerializedName("adcode") val adcode: String? = null,
    @SerializedName("name") val name: String? = null
)

// ===== 综合接口 =====
interface CaiyunService {
    @GET("v2.7/{token}/{lon},{lat}/weather")
    fun getWeather(
        @Path("token") token: String,
        @Path("lon") longitude: Double,
        @Path("lat") latitude: Double,
        @Query("alert") alert: Boolean,
        @Query("span") span: Int,
        @Query("dailystart") dailystart: Int,
        @Query("hourlysteps") hourlysteps: Int,
        @Query("lang") lang: String = "zh_CN",
        @Query("version") version: String = "7.59.0"
    ): Call<CaiyunResponse>

    /**
     * v2.6 常规综合天气。它与 [getWeatherHistoryV26] 分开声明，是为了在用户保存
     * 自己的 Token 前真实核验 v2.6 的实况、小时、日预报和预警路由，而不是仅凭
     * v2.7 成功就假定全部二级页都可用。
     */
    @GET("v2.6/{token}/{lon},{lat}/weather")
    fun getWeatherV26(
        @Path("token") token: String,
        @Path("lon") longitude: Double,
        @Path("lat") latitude: Double,
        @Query("alert") alert: Boolean = true,
        @Query("dailysteps") dailySteps: Int = 3,
        @Query("hourlysteps") hourlySteps: Int = 24,
        @Query("lang") lang: String = "zh_CN"
    ): Call<CaiyunResponse>

    /**
     * v2.6 分钟级降水单独请求，并要求 metric:v2，确保 precipitation_2h 的 120 个
     * 逐分钟值直接是 mm/h。Token 未开通该增值能力时会返回失败或不带 minutely 块。
     */
    @GET("v2.6/{token}/{lon},{lat}/minutely")
    fun getMinutelyV26(
        @Path("token") token: String,
        @Path("lon") longitude: Double,
        @Path("lat") latitude: Double,
        @Query("unit") unit: String = "metric:v2",
        @Query("lang") lang: String = "zh_CN"
    ): Call<CaiyunResponse>

    /**
     * v2.6 支持以 begin 请求过去一天。此调用只用于详情页的历史逐小时指标，
     * 不替换主页仍在使用的 v2.7 响应，也不会把 Token 写入任何缓存或日志。
     */
    @GET("v2.6/{token}/{lon},{lat}/weather")
    fun getWeatherHistoryV26(
        @Path("token") token: String,
        @Path("lon") longitude: Double,
        @Path("lat") latitude: Double,
        @Query("begin") beginSeconds: Long,
        @Query("alert") alert: Boolean = false,
        @Query("dailysteps") dailySteps: Int = 2,
        @Query("hourlysteps") hourlySteps: Int = 48,
        @Query("lang") lang: String = "zh_CN"
    ): Call<CaiyunResponse>

    /**
     * 日出、日落单独走彩云 v2.6 的 daily 端点。这个端点稳定返回 daily.astro，
     * 后续的晨昏、蓝调、金色和霞光时段都在本地按坐标推算，不依赖 v2.7 的附带字段。
     */
    @GET("v2.6/{token}/{lon},{lat}/daily")
    fun getDailyAstronomyV26(
        @Path("token") token: String,
        @Path("lon") longitude: Double,
        @Path("lat") latitude: Double,
        @Query("dailystart") dailystart: Int = -1,
        @Query("dailysteps") dailysteps: Int = 3,
        @Query("lang") lang: String = "zh_CN"
    ): Call<CaiyunResponse>
}

/** 仅供用户自行提供的彩云 Token 直连官方域名使用。 */
object CaiyunDirectApi {
    private const val BASE_URL = "https://api.caiyunapp.com/"

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(!BuildConfig.USER_CONFIGURABLE)
        .followSslRedirects(!BuildConfig.USER_CONFIGURABLE)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val service: CaiyunService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(CaiyunService::class.java)
}
