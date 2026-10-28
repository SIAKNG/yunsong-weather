package com.example.weatherapp

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GatewayResult<T>(
    val body: T? = null,
    val code: Int = 0,
    val message: String? = null
) {
    val isSuccessful: Boolean get() = code in 200..299 && body != null
}

/**
 * 用户 Token 的一次性能力探测结果。只存在于内存和设置页文案中，不含 Token、坐标、
 * 原始响应或服务端错误正文，因此可安全用于决定是否切换数据源。
 */
data class CaiyunTokenCapabilityProbe(
    val v27Route: Boolean,
    val v27Realtime: Boolean,
    val v27Hourly: Boolean,
    val v27Daily: Boolean,
    val v27AlertRoute: Boolean,
    val v26Route: Boolean,
    val v26Realtime: Boolean,
    val v26Hourly: Boolean,
    val v26Daily: Boolean,
    val v26AlertRoute: Boolean,
    val v26History: Boolean,
    val v26Astronomy: Boolean,
    /** v2.6 分钟降水路由是否可达，以及是否真的返回 minutely 数据块。 */
    val v26MinutelyRoute: Boolean,
    val v26MinutelyData: Boolean,
    val activationCode: Int
) {
    /** 主页仍依赖 v2.7；只有该主路径真实可用才允许切换到个人 Token。 */
    val canActivate: Boolean get() = v27Route && v27Realtime

    fun compactSummary(): String = buildList {
        if (v27Route) add("v2.7")
        if (v26Route) add("v2.6")
        if (v26History) add("历史24小时")
        if (v26Astronomy) add("日出日落")
        if (v26MinutelyData) add("分钟降雨")
        else if (v26MinutelyRoute) add("分钟降雨未返回")
    }.joinToString("、").ifBlank { "未检测到可用路由" }
}

data class GatewayCitiesResponse(val cities: List<CityInfo> = emptyList())

private object WeatherInstallIdentity {
    private const val PREFS = "weather_install_identity"
    private const val KEY_ID = "install_id"

    fun value(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
    }
}

interface WeatherGatewayService {
    @GET("caiyun/weather")
    fun caiyunWeather(@Query("lat") lat: Double, @Query("lon") lon: Double): Call<CaiyunResponse>

    @GET("caiyun/astronomy")
    fun caiyunAstronomy(@Query("lat") lat: Double, @Query("lon") lon: Double): Call<CaiyunResponse>

    @GET("qweather/bundle")
    fun qweatherBundle(@Query("lat") lat: Double, @Query("lon") lon: Double): Call<QWeatherBundle>

    @GET("cities")
    fun cities(@Query("q") query: String): Call<GatewayCitiesResponse>
}

/** 公开共享天气服务的唯一 Android 出口；URL 本身不是密钥。 */
object WeatherGatewayApi {
    private const val PLACEHOLDER_HOST = "api.example.invalid"

    fun isConfigured(): Boolean = !BuildConfig.USER_CONFIGURABLE &&
        BuildConfig.WEATHER_GATEWAY_BASE_URL.startsWith("https://") &&
        !BuildConfig.WEATHER_GATEWAY_BASE_URL.contains(PLACEHOLDER_HOST)

    fun service(context: Context): WeatherGatewayService {
        val app = context.applicationContext
        val headers = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("X-Install-Id", WeatherInstallIdentity.value(app))
                .header("X-App-Version", BuildConfig.VERSION_NAME)
                .build()
            chain.proceed(request)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(headers)
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.WEATHER_GATEWAY_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherGatewayService::class.java)
    }
}

/**
 * 将所有彩云访问模式收口：共享模式从 BFF 取数，用户模式仅携带其本机 Token 访问官方彩云域名。
 * 回调沿用 Retrofit 的主线程语义，便于现有界面和 Worker 分别调用。
 */
object WeatherDataRepository {
    private val gson = Gson()

    /**
     * 统一数据链路的唯一彩云凭证。它由 Gradle 从未提交的本机构建配置写入 APK，
     * 因而不会受卸载重装、设置页或 DataStore 清理影响。
     */
    private fun embeddedCaiyunToken(context: Context): String = if (BuildConfig.USER_CONFIGURABLE)
        UserApiConfiguration.read(context).caiyunToken else BuildConfig.CAIYUN_EMBEDDED_TOKEN.trim()

    private fun weatherCall(context: Context, token: String, lon: Double, lat: Double, span: Int, hourlySteps: Int): Call<CaiyunResponse> =
        if (BuildConfig.USER_CONFIGURABLE && UserApiConfiguration.read(context).caiyunVersion == "2.6")
            CaiyunDirectApi.service.getWeatherV26(token, lon, lat, true, span, hourlySteps)
        else CaiyunDirectApi.service.getWeather(token, lon, lat, false, span, 0, hourlySteps)

    fun enqueueCaiyunWeather(
        context: Context,
        lon: Double,
        lat: Double,
        span: Int,
        hourlySteps: Int,
        onSupplement: ((GatewayResult<CaiyunResponse>) -> Unit)? = null,
        callback: (GatewayResult<CaiyunResponse>) -> Unit
    ) {
        if (WeatherSettings.useOpenMeteo(context)) {
            val sourceKey=WeatherSettings.sourceKey(context)
            val handler=android.os.Handler(android.os.Looper.getMainLooper())
            OpenMeteoSource.enqueue(lat,lon,{ result -> handler.post {
                callback(if(sourceKey==WeatherSettings.sourceKey(context)) result else GatewayResult(code=409,message="天气源已变更"))
            } },{ result -> handler.post {
                if(sourceKey==WeatherSettings.sourceKey(context)) onSupplement?.invoke(result)
            } })
            return
        }
        val token = embeddedCaiyunToken(context)
        if (token.isBlank()) {
            callback(GatewayResult(code = 503, message = if (BuildConfig.USER_CONFIGURABLE) "请在设置中填写彩云 Token" else "天气服务构建配置缺失"))
            return
        }
        enqueueOwnCaiyunWeather(context, token, lon, lat, span, hourlySteps, callback)
    }

    fun enqueueCaiyunAstronomy(
        context: Context,
        lon: Double,
        lat: Double,
        callback: (GatewayResult<CaiyunResponse>) -> Unit
    ) {
        val token = embeddedCaiyunToken(context)
        if (token.isBlank()) callback(GatewayResult(code = 503, message = "天气服务构建配置缺失"))
        else CaiyunDirectApi.service.getDailyAstronomyV26(token, lon, lat).enqueue(gatewayCallback(callback))
    }

    /**
     * 彩云 v2.6 的过去一天只用于同源历史指标。共享网关尚未公开该路由时明确返回
     * 不可用，由调用方保留缓存或历史补充源，绝不伪装成彩云返回。
     */
    fun enqueueCaiyunMetricHistory(
        context: Context,
        lon: Double,
        lat: Double,
        beginSeconds: Long,
        callback: (GatewayResult<CaiyunResponse>) -> Unit
    ) {
        val token = embeddedCaiyunToken(context)
        if (token.isBlank()) callback(GatewayResult(code = 503, message = "天气服务构建配置缺失"))
        else CaiyunDirectApi.service.getWeatherHistoryV26(token, lon, lat, beginSeconds)
            .enqueue(gatewayCallback(callback))
    }

    fun fetchCaiyunWeatherBlocking(
        context: Context,
        lon: Double,
        lat: Double,
        span: Int = 15,
        hourlySteps: Int = 360
    ): GatewayResult<CaiyunResponse> = try {
        if (WeatherSettings.useOpenMeteo(context)) OpenMeteoSource.fetch(lat,lon)
        else {
            val token = embeddedCaiyunToken(context)
            if (token.isBlank()) GatewayResult(code = 503, message = "天气服务构建配置缺失")
            else fetchOwnCaiyunWeatherBlocking(context, token, lon, lat, span, hourlySteps)
        }
    } catch (_: Exception) {
        GatewayResult(code = 0, message = "网络错误")
    }

    /**
     * 个人彩云 Token 的主页请求。分钟级数据只在权限已验证时每次补入；未知状态只允许
     * 一个城市发起 v2.6 探测，失败不会阻塞实况、小时或日预报的正常展示。
     */
    private fun enqueueOwnCaiyunWeather(
        context: Context,
        token: String,
        lon: Double,
        lat: Double,
        span: Int,
        hourlySteps: Int,
        callback: (GatewayResult<CaiyunResponse>) -> Unit
    ) {
        // dailystart=-1 会让小时预报也从昨天开始，挤占未来窗口；昨日改用独立历史请求。
        weatherCall(context, token, lon, lat, span, hourlySteps)
            .enqueue(object : Callback<CaiyunResponse> {
                override fun onResponse(call: Call<CaiyunResponse>, response: Response<CaiyunResponse>) {
                    val base = GatewayResult(
                        body = response.body(),
                        code = response.code(),
                        message = if (BuildConfig.USER_CONFIGURABLE) "HTTP ${response.code()}" else response.errorBody()?.string()
                    )
                    val body = base.body
                    if (!base.isSuccessful || body?.result == null) {
                        callback(base)
                        return
                    }
                    enqueueOwnCaiyunMinutely(context, token, lon, lat, base, callback)
                }

                override fun onFailure(call: Call<CaiyunResponse>, t: Throwable) {
                    callback(GatewayResult(code = 0, message = "网络错误"))
                }
            })
    }

    private fun enqueueOwnCaiyunMinutely(
        context: Context,
        token: String,
        lon: Double,
        lat: Double,
        base: GatewayResult<CaiyunResponse>,
        callback: (GatewayResult<CaiyunResponse>) -> Unit
    ) {
        val state = WeatherSettings.caiyunMinutelyCapability(context)
        val probing = state == WeatherSettings.CAIYUN_MINUTELY_UNKNOWN &&
            WeatherSettings.beginCaiyunMinutelyProbe(context)
        val shouldRequest = state == WeatherSettings.CAIYUN_MINUTELY_AVAILABLE || probing
        if (!shouldRequest) {
            callback(base)
            return
        }
        CaiyunDirectApi.service.getMinutelyV26(token, lon, lat).enqueue(object : Callback<CaiyunResponse> {
            override fun onResponse(call: Call<CaiyunResponse>, response: Response<CaiyunResponse>) {
                val minutely = response.body()?.result?.minutely
                if (response.isSuccessful && minutely != null) {
                    WeatherSettings.setCaiyunMinutelyCapability(
                        context,
                        WeatherSettings.CAIYUN_MINUTELY_AVAILABLE
                    )
                    callback(base.withMinutely(minutely))
                } else {
                    // 首次得到明确的“无数据块/权限错误”才标记为未开通；服务端故障可下次重试。
                    if (probing) {
                        val next = if (response.isSuccessful || response.code() in 400..499) {
                            WeatherSettings.CAIYUN_MINUTELY_UNAVAILABLE
                        } else {
                            WeatherSettings.CAIYUN_MINUTELY_UNKNOWN
                        }
                        WeatherSettings.setCaiyunMinutelyCapability(context, next)
                    }
                    callback(base)
                }
            }

            override fun onFailure(call: Call<CaiyunResponse>, t: Throwable) {
                if (probing) {
                    WeatherSettings.setCaiyunMinutelyCapability(
                        context,
                        WeatherSettings.CAIYUN_MINUTELY_UNKNOWN
                    )
                }
                callback(base)
            }
        })
    }

    private fun fetchOwnCaiyunWeatherBlocking(
        context: Context,
        token: String,
        lon: Double,
        lat: Double,
        span: Int,
        hourlySteps: Int
    ): GatewayResult<CaiyunResponse> {
        val response = weatherCall(context, token, lon, lat, span, hourlySteps).execute()
        val base = GatewayResult(response.body(), response.code(), if (BuildConfig.USER_CONFIGURABLE) "HTTP ${response.code()}" else response.errorBody()?.string())
        if (!base.isSuccessful || base.body?.result == null) return base

        val state = WeatherSettings.caiyunMinutelyCapability(context)
        val probing = state == WeatherSettings.CAIYUN_MINUTELY_UNKNOWN &&
            WeatherSettings.beginCaiyunMinutelyProbe(context)
        if (state != WeatherSettings.CAIYUN_MINUTELY_AVAILABLE && !probing) return base

        return try {
            val minuteResponse = CaiyunDirectApi.service.getMinutelyV26(token, lon, lat).execute()
            val minutely = minuteResponse.body()?.result?.minutely
            if (minuteResponse.isSuccessful && minutely != null) {
                WeatherSettings.setCaiyunMinutelyCapability(
                    context,
                    WeatherSettings.CAIYUN_MINUTELY_AVAILABLE
                )
                base.withMinutely(minutely)
            } else {
                if (probing) {
                    val next = if (minuteResponse.isSuccessful || minuteResponse.code() in 400..499) {
                        WeatherSettings.CAIYUN_MINUTELY_UNAVAILABLE
                    } else {
                        WeatherSettings.CAIYUN_MINUTELY_UNKNOWN
                    }
                    WeatherSettings.setCaiyunMinutelyCapability(context, next)
                }
                base
            }
        } catch (_: Exception) {
            if (probing) WeatherSettings.setCaiyunMinutelyCapability(
                context,
                WeatherSettings.CAIYUN_MINUTELY_UNKNOWN
            )
            base
        }
    }

    private fun GatewayResult<CaiyunResponse>.withMinutely(minutely: Minutely): GatewayResult<CaiyunResponse> {
        val merged = body?.let { response ->
            response.result?.let { result -> response.copy(result = result.copy(minutely = minutely)) } ?: response
        }
        return copy(body = merged)
    }

    fun validateOwnCaiyunToken(token: String, lon: Double, lat: Double): GatewayResult<CaiyunResponse> = try {
        val response = CaiyunDirectApi.service.getWeather(token.trim(), lon, lat, true, 3, 0, 3).execute()
        GatewayResult(response.body(), response.code(), response.errorBody()?.string())
    } catch (_: Exception) {
        GatewayResult(code = 0, message = "网络错误")
    }

    /**
     * 按同一坐标、相近时间窗口验证个人 Token 的完整接口组合。此方法不能写缓存，
     * 也不能进入日志；调用方只在验证成功后把 Token 加密写入 Android Keystore 存储。
     */
    fun probeOwnCaiyunToken(token: String, lon: Double, lat: Double): CaiyunTokenCapabilityProbe {
        val clean = token.trim()
        fun request(block: () -> Response<CaiyunResponse>): Pair<Boolean, CaiyunResponse?> = try {
            val response = block()
            (response.isSuccessful && response.body() != null) to response.body()
        } catch (_: Exception) {
            false to null
        }

        val (v27Ok, v27) = request {
            CaiyunDirectApi.service.getWeather(clean, lon, lat, true, 3, 0, 24).execute()
        }
        val (v26Ok, v26) = request {
            CaiyunDirectApi.service.getWeatherV26(clean, lon, lat, true, 3, 24).execute()
        }
        val begin = (System.currentTimeMillis() / 1_000L) - 24L * 60L * 60L
        val (historyOk, history) = request {
            CaiyunDirectApi.service.getWeatherHistoryV26(clean, lon, lat, begin).execute()
        }
        val (astronomyOk, astronomy) = request {
            CaiyunDirectApi.service.getDailyAstronomyV26(clean, lon, lat).execute()
        }
        val (minutelyOk, minutely) = request {
            CaiyunDirectApi.service.getMinutelyV26(clean, lon, lat).execute()
        }
        return CaiyunTokenCapabilityProbe(
            v27Route = v27Ok,
            v27Realtime = v27?.result?.realtime != null,
            v27Hourly = !v27?.result?.hourly?.temperature.isNullOrEmpty(),
            v27Daily = !v27?.result?.daily?.temperature.isNullOrEmpty(),
            // 没有生效预警时 content 为空仍是“预警路由可达”，不能当作能力缺失。
            v27AlertRoute = v27Ok,
            v26Route = v26Ok,
            v26Realtime = v26?.result?.realtime != null,
            v26Hourly = !v26?.result?.hourly?.temperature.isNullOrEmpty(),
            v26Daily = !v26?.result?.daily?.temperature.isNullOrEmpty(),
            v26AlertRoute = v26Ok,
            v26History = historyOk && !history?.result?.hourly?.temperature.isNullOrEmpty(),
            v26Astronomy = astronomyOk && !astronomy?.result?.daily?.astro.isNullOrEmpty(),
            v26MinutelyRoute = minutelyOk,
            v26MinutelyData = minutelyOk && minutely?.result?.minutely != null,
            activationCode = if (v27Ok) 200 else if (v26Ok) 206 else 0
        )
    }

    fun fetchQWeather(context: Context, lat: Double, lon: Double): QWeatherBundle {
        if (!WeatherGatewayApi.isConfigured()) return qweatherFailure("共享天气服务尚未配置")
        return try {
            val response = WeatherGatewayApi.service(context).qweatherBundle(lat, lon).execute()
            response.body() ?: qweatherFailure("和风天气请求失败：HTTP ${response.code()}")
        } catch (_: Exception) {
            qweatherFailure("和风天气网络错误")
        }
    }

    fun searchCities(context: Context, keyword: String): List<CityInfo> {
        if (!WeatherGatewayApi.isConfigured()) return emptyList()
        return try {
            WeatherGatewayApi.service(context).cities(keyword).execute().body()?.cities.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun qweatherFailure(message: String) = QWeatherBundle(
        now = null, hourly = emptyList(), daily = emptyList(), aqi = null,
        updateTimeMillis = System.currentTimeMillis(), error = message
    )

    private fun <T> gatewayCallback(callback: (GatewayResult<T>) -> Unit) = object : Callback<T> {
        override fun onResponse(call: Call<T>, response: Response<T>) {
            callback(GatewayResult(response.body(), response.code(), if (BuildConfig.USER_CONFIGURABLE) "HTTP ${response.code()}" else response.errorBody()?.string()))
        }

        override fun onFailure(call: Call<T>, t: Throwable) {
            callback(GatewayResult(code = 0, message = "网络错误"))
        }
    }
}
