package com.example.weatherapp

import android.content.Context
import com.google.gson.Gson
import java.util.Locale

/**
 * 保存最近一次成功天气响应，用于冷启动和弱网时先渲染已有数据。
 * 缓存按数据波动程度裁剪：冷启动可保留仍有效的日/天文数据，但不会把过期实况当作当前天气。
 */
object WeatherCache {
    private const val PREFS = "weather_response_cache"
    private const val SCHEMA_VERSION = 3
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val MAX_PAYLOAD_CHARS = 1_000_000
    private val gson = Gson()
    data class StartupSnapshot<T>(val fresh: T?,val display: T)

    /** Parse once; retain exactly the same TTL and display-only stale projection. */
    fun loadCaiyunStartup(context: Context,page: CityPage): StartupSnapshot<CaiyunResponse>? {
        if (WeatherSettings.useQWeather(context)) return null
        migrateToUnifiedSchema(context)
        val cached=load(context,key(context,"unified_v3",page)) ?: return null
        val parsed=runCatching {gson.fromJson(cached.payload,CaiyunResponse::class.java)}.getOrNull() ?: return null
        return StartupSnapshot(pruneCaiyun(parsed,cached.ageMs),parsed)
    }
    fun loadQWeatherStartup(context: Context,page: CityPage): StartupSnapshot<QWeatherBundle>? {
        if (!WeatherSettings.useQWeather(context)) return null
        val cached=load(context,key(context,WeatherSettings.SOURCE_QWEATHER,page)) ?: return null
        val parsed=parseQWeatherPayload(cached.payload) ?: return null
        return StartupSnapshot(pruneQWeather(parsed,cached.ageMs),parsed)
    }

    fun saveCaiyun(context: Context, page: CityPage, response: CaiyunResponse) {
        migrateToUnifiedSchema(context)
        response.result?.let { ForecastDayHistoryCache.save(context,page,ForecastDayHistoryCache.fromCaiyun(it)) }
        response.result?.hourly?.let { hourly ->
            val uv = hourly.lifeIndex?.ultraviolet.orEmpty().associateBy { it.datetime }
            val sky = hourly.skycon.orEmpty().associateBy { it.datetime }
            val rain = hourly.precipitation.orEmpty().associateBy { it.datetime }
            val captured = System.currentTimeMillis()
            CaiyunWeatherHistorySnapshotCache.save(context, page, hourly = hourly.temperature.orEmpty().mapNotNull { point ->
                val time = point.datetime?.let(TimeUtil::parse) ?: return@mapNotNull null
                CaiyunHourlyWeatherSnapshot(time, point.value, sky[point.datetime]?.value,
                    rain[point.datetime]?.probability, uv[point.datetime]?.index?.toDoubleOrNull(), captured)
            })
        }
        save(context, key(context,"unified_v3", page), gson.toJson(response))
    }

    fun saveQWeather(context: Context, page: CityPage, response: QWeatherBundle) {
        ForecastDayHistoryCache.save(context,page,ForecastDayHistoryCache.fromQWeather(response))
        save(context, key(context,WeatherSettings.SOURCE_QWEATHER, page), gson.toJson(response))
    }

    fun loadCaiyun(context: Context, page: CityPage): CaiyunResponse? {
        if (WeatherSettings.useQWeather(context)) return null
        migrateToUnifiedSchema(context)
        return load(context, key(context,"unified_v3", page))?.let { cached ->
            runCatching { gson.fromJson(cached.payload, CaiyunResponse::class.java) }.getOrNull()
                ?.let { pruneCaiyun(it, cached.ageMs) }
        }
    }

    /**
     * 仅供启动首帧显示“上次退出前内容”。它不参与新鲜度判断、通知或后台决策，
     * 网络成功后会立即由正常缓存结果替换，避免弱网进入时白屏或骨架矩形。
     */
    fun loadCaiyunDisplaySnapshot(context: Context, page: CityPage): CaiyunResponse? {
        migrateToUnifiedSchema(context)
        return load(context, key(context,"unified_v3", page))?.let { cached ->
            runCatching { gson.fromJson(cached.payload, CaiyunResponse::class.java) }.getOrNull()
        }
    }

    fun clearCaiyun(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = prefs.all.keys.filter { it.startsWith("weather_") }
        prefs.edit().apply {
            keys.forEach(::remove)
            apply()
        }
    }

    /**
     * 双源旧缓存里的 Kotlin 非空集合可能被 Gson 反序列化为 null（此前正是安装后崩溃
     * 的根因）。统一快照启用时直接废弃它们，避免旧结构重新参与首帧渲染。
     */
    private fun migrateToUnifiedSchema(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SCHEMA_VERSION, 0) >= SCHEMA_VERSION) return
        val obsolete = prefs.all.keys.filter {
            it.startsWith("weather_caiyun_") || it.startsWith("weather_qweather_")
        }
        prefs.edit().apply {
            obsolete.forEach(::remove)
            putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            apply()
        }
    }

    fun loadQWeather(context: Context, page: CityPage): QWeatherBundle? =
        (if (WeatherSettings.useQWeather(context)) load(context, key(context,WeatherSettings.SOURCE_QWEATHER, page)) else null)?.let { cached ->
            parseQWeatherPayload(cached.payload)
                ?.let { pruneQWeather(it, cached.ageMs) }
        }

    fun loadQWeatherDisplaySnapshot(context: Context, page: CityPage): QWeatherBundle? =
        (if (WeatherSettings.useQWeather(context)) load(context, key(context,WeatherSettings.SOURCE_QWEATHER, page)) else null)?.let { cached ->
            parseQWeatherPayload(cached.payload)
        }

    /** Older Gson payloads can bypass Kotlin defaults. Repair collections before copy/render. */
    private fun parseQWeatherPayload(payload: String): QWeatherBundle? = runCatching {
        val raw=gson.fromJson(payload,QWeatherBundle::class.java) ?: return@runCatching null
        raw.copy(hourly=(raw.hourly as List<QHour>?).orEmpty(),daily=(raw.daily as List<QDay>?).orEmpty(),
            aqiHourly=(raw.aqiHourly as List<QAirQualityHour>?).orEmpty(),minutely=(raw.minutely as List<QMinute>?).orEmpty(),
            lifeAdvice=(raw.lifeAdvice as List<QLifeIndex>?).orEmpty(),alerts=(raw.alerts as List<QAlert>?).orEmpty())
    }.getOrNull()

    private fun save(context: Context, key: String, payload: String) {
        if (payload.length > MAX_PAYLOAD_CHARS) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("${key}_at", System.currentTimeMillis())
            .putString("${key}_json", payload)
            .apply()
    }

    private data class CachedPayload(val payload: String, val ageMs: Long)

    private fun load(context: Context, key: String): CachedPayload? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong("${key}_at", 0L)
        val age = System.currentTimeMillis() - savedAt
        if (savedAt <= 0L || !WeatherCachePolicy.payloadUsable(age)) {
            prefs.edit().remove("${key}_at").remove("${key}_json").apply()
            return null
        }
        return prefs.getString("${key}_json", null)?.let { CachedPayload(it, age) }
    }

    private fun pruneCaiyun(response: CaiyunResponse, ageMs: Long): CaiyunResponse? {
        val result = response.result ?: return null
        val volatile = WeatherCachePolicy.volatileUsable(WeatherCachePolicy.observationAge(
            System.currentTimeMillis(), ageMs, response.serverTime?.times(1000)))
        val hourly = WeatherCachePolicy.hourlyUsable(ageMs)
        val daily = WeatherCachePolicy.dailyUsable(ageMs)
        val pruned = result.copy(
            realtime = if (volatile) result.realtime else null,
            minutely = if (volatile) result.minutely else null,
            alert = if (volatile) result.alert else null,
            hourly = if (hourly) result.hourly else null,
            daily = if (daily) result.daily else null,
            forecastKeypoint = if (daily) result.forecastKeypoint else null
        )
        return if (pruned.realtime == null && pruned.hourly == null && pruned.daily == null) null
        else response.copy(result = pruned)
    }

    private fun pruneQWeather(bundle: QWeatherBundle, ageMs: Long): QWeatherBundle? {
        val volatile = WeatherCachePolicy.volatileUsable(WeatherCachePolicy.observationAge(
            System.currentTimeMillis(), ageMs, bundle.now?.obsTimeMillis ?: bundle.updateTimeMillis))
        val hourly = WeatherCachePolicy.hourlyUsable(ageMs)
        val daily = WeatherCachePolicy.dailyUsable(ageMs)
        val pruned = bundle.copy(
            now = if (volatile) bundle.now else null,
            aqi = if (volatile) bundle.aqi else null,
            minutely = if (volatile) bundle.minutely else emptyList(),
            minutelySummary = if (volatile) bundle.minutelySummary else null,
            hourly = if (hourly) bundle.hourly else emptyList(),
            // 已安装版本留下的 JSON 不含新增字段时，Gson 会绕开 Kotlin 默认值并写入 null。
            // 这里把旧缓存视为“尚无 AQI 小时数据”，而不是在冷启动时崩溃。
            aqiHourly = if (hourly) (bundle.aqiHourly as List<QAirQualityHour>?).orEmpty() else emptyList(),
            daily = if (daily) (bundle.daily as List<QDay>?).orEmpty() else emptyList(),
            uv = if (daily) bundle.uv else null,
            lifeAdvice = (bundle.lifeAdvice as List<QLifeIndex>?).orEmpty(),
            alerts = if (volatile) (bundle.alerts as List<QAlert>?).orEmpty() else emptyList()
        )
        return if (pruned.now == null && pruned.hourly.isEmpty() && pruned.daily.isEmpty()) null else pruned
    }

    private fun key(context: Context, source: String, page: CityPage): String = String.format(
        Locale.US,
        "weather_%s_%.3f_%.3f",
        (if (source == "unified_v3") WeatherSettings.dataSource(context) else source) + "_" + WeatherSettings.sourceKey(context),
        page.lat,
        page.lng
    )
}
