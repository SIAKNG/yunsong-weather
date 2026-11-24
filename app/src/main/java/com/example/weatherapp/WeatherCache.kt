package com.example.weatherapp

import android.content.Context
import com.google.gson.Gson
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    data class StartupSnapshot<T>(val fresh: T?, val display: T, val savedAt: Long = 0L)
    data class HomeStartup(
        val caiyun: StartupSnapshot<CaiyunResponse>? = null,
        val qweather: StartupSnapshot<QWeatherBundle>? = null,
        val alerts: List<QAlert> = emptyList()
    )

    private const val HOME_PREFS = "weather_home_display"
    internal val homePersistenceDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val homeWrites = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + homePersistenceDispatcher)

    internal fun persistHomeUpdates(context: Context, save: (Context) -> Unit) {
        val app = context.applicationContext
        val scope = homeScope(app)
        homeWrites.launch {
            if (scope == homeScope(app)) runCatching { save(app) }.onFailure {
                android.util.Log.w("WeatherApp", "Weather snapshot persistence failed", it)
            }
        }
    }

    internal fun homeScope(context: Context): String =
        WeatherSettings.dataSource(context) + "_r" +
            if (BuildConfig.USER_CONFIGURABLE) UserApiConfiguration.revision(context) else 0

    /** A foreground response survives independent widget/risk writes and location jitter. */
    fun loadHomeStartup(context: Context, page: CityPage): HomeStartup {
        val alerts = loadHomeAlerts(context, page)
        return loadForegroundStartup(context, page)?.let { snapshot ->
            if (snapshot.caiyun != null) snapshot.copy(alerts = alerts) else snapshot
        } ?: HomeStartup(loadCaiyunStartup(context, page), loadQWeatherStartup(context, page), alerts)
    }

    internal fun loadForegroundStartup(context: Context, page: CityPage): HomeStartup? {
        val prefs = context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
        val homeKey = homeKey(context, page)
        val home = loadHomePayload(context, page, homeKey)
        if (home != null) {
            if (prefs.getBoolean(homeKey + "_q", false)) {
                parseQWeatherPayload(home.payload)?.let {
                    return HomeStartup(qweather = StartupSnapshot(pruneQWeather(it, home.ageMs), it, home.savedAt))
                }
            } else {
                runCatching { gson.fromJson(home.payload, CaiyunResponse::class.java) }.getOrNull()?.let {
                    return HomeStartup(caiyun = StartupSnapshot(pruneCaiyun(it, home.ageMs), it, home.savedAt))
                }
            }
        }
        return null
    }

    internal fun loadRecentHomeAlerts(context: Context, page: CityPage, maxAge: Long): List<QAlert>? =
        loadHomePayload(context, page, homeKey(context, page) + "_alerts")
            ?.takeIf { it.ageMs < maxAge }
            ?.let { runCatching { gson.fromJson(it.payload, Array<QAlert>::class.java).toList() }.getOrNull() }

    internal fun loadSupplementalAir(context: Context, page: CityPage): QWeatherBundle? =
        loadHomePayload(context, page, homeKey(context, page) + "_supplement_air")
            ?.takeIf { WeatherCachePolicy.hourlyUsable(it.ageMs) }
            ?.let { parseQWeatherPayload(it.payload) }

    internal fun saveSupplementalAir(context: Context, page: CityPage, air: QWeatherBundle) {
        saveHomePayload(context, page, homeKey(context, page) + "_supplement_air", gson.toJson(air), true)
        val points = ForecastDayHistoryCache.fromQWeather(air)
        ForecastDayHistoryCache.save(context, page, points)
        WeatherWidgets.render(context)
        WeatherReminderPreparation.onCacheSaved(context)
    }

    internal fun saveHomeAlerts(context: Context, page: CityPage, alerts: List<QAlert>) {
        val valid = alerts.filter { it.expireTimeMillis > System.currentTimeMillis() }
        saveHomePayload(context, page, homeKey(context, page) + "_alerts",
            gson.toJson(valid.toTypedArray()), false)
    }

    internal fun loadHomeAlerts(context: Context, page: CityPage): List<QAlert> =
        loadHomePayload(context, page, homeKey(context, page) + "_alerts")?.let { cached ->
            runCatching { gson.fromJson(cached.payload, Array<QAlert>::class.java).toList() }
                .getOrDefault(emptyList())
        }.orEmpty()

    private fun saveHomePayload(context: Context, page: CityPage, key: String, payload: String, qweather: Boolean) {
        if (payload.length > MAX_PAYLOAD_CHARS) return
        val prefs = context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
        val packed = WeatherDiskCache.pack(payload)
        WeatherDiskCache.write(context, prefs, commit = true) {
            putString(key + "_json", packed)
            putLong(key + "_at", System.currentTimeMillis())
            putLong(key + "_lat", page.lat.toBits())
            putLong(key + "_lng", page.lng.toBits())
            putBoolean(key + "_q", qweather)
        }
    }

    private fun loadHomePayload(context: Context, page: CityPage, key: String): CachedPayload? {
        val prefs = context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(key + "_at", 0L)
        val age = System.currentTimeMillis() - at
        if (at == 0L || !WeatherCachePolicy.payloadUsable(age)) return null
        if (page.isCurrentLocation &&
            !nearby(page, Double.fromBits(prefs.getLong(key + "_lat", Double.NaN.toBits())),
                Double.fromBits(prefs.getLong(key + "_lng", Double.NaN.toBits())))
        ) return null
        return WeatherDiskCache.unpack(prefs.getString(key + "_json", null))?.let { CachedPayload(it, age, at) }
    }

    private fun homeKey(context: Context, page: CityPage): String =
        homeScope(context) + if (page.isCurrentLocation) "_location" else
            String.format(Locale.US, "_%.3f_%.3f", page.lat, page.lng)

    internal fun nearby(page: CityPage, lat: Double, lng: Double): Boolean {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 ||
            page.lat !in -90.0..90.0 || page.lng !in -180.0..180.0
        ) return false
        val distance = FloatArray(1)
        android.location.Location.distanceBetween(page.lat, page.lng, lat, lng, distance)
        return distance[0] < 1_000f
    }

    // Legacy cache keys were rounded to roughly one hundred metres.
    private fun loadLegacyHome(context: Context, page: CityPage, exactKey: String): CachedPayload? {
        if (page.isCurrentLocation) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stable = locationKey(exactKey)
            if (sameCachedLocation(prefs, page, stable)) load(context, stable)?.let { return it }
        }
        load(context, exactKey)?.let { return it }
        if (!page.isCurrentLocation) return null
        val prefix = exactKey.substringBeforeLast('_').substringBeforeLast('_') + "_"
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val newestNearby = values.asSequence().mapNotNull { (key, value) ->
            if (!key.startsWith(prefix) || !key.endsWith("_at") || value !is Long) return@mapNotNull null
            val coordinates = key.removePrefix(prefix).removeSuffix("_at").split('_')
            val lat = coordinates.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = coordinates.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val age = System.currentTimeMillis() - value
            if (WeatherCachePolicy.payloadUsable(age) && nearby(page, lat, lng)) key.removeSuffix("_at") to value
            else null
        }.maxByOrNull { it.second } ?: return null
        return load(context, newestNearby.first)
    }

    /** Parse once; retain exactly the same TTL and display-only stale projection. */
    fun loadCaiyunStartup(context: Context,page: CityPage): StartupSnapshot<CaiyunResponse>? {
        if (WeatherSettings.useQWeather(context)) return null
        migrateToUnifiedSchema(context)
        val cached=loadLegacyHome(context,page,key(context,"unified_v3",page)) ?: return null
        val parsed=runCatching {gson.fromJson(cached.payload,CaiyunResponse::class.java)}.getOrNull() ?: return null
        return StartupSnapshot(pruneCaiyun(parsed,cached.ageMs), parsed, cached.savedAt)
    }
    fun loadQWeatherStartup(context: Context,page: CityPage): StartupSnapshot<QWeatherBundle>? {
        if (!WeatherSettings.useQWeather(context)) return null
        val cached=loadLegacyHome(context,page,key(context,WeatherSettings.SOURCE_QWEATHER,page)) ?: return null
        val parsed=parseQWeatherPayload(cached.payload) ?: return null
        return StartupSnapshot(pruneQWeather(parsed,cached.ageMs), parsed, cached.savedAt)
    }

    fun saveCaiyun(context: Context, page: CityPage, response: CaiyunResponse, fromHome: Boolean = false) {
        migrateToUnifiedSchema(context)
        ForecastDayHistoryCache.save(context, page, ForecastDayHistoryCache.fromCaiyunResponse(response))
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
        val payload = gson.toJson(response)
        if (fromHome) saveHomePayload(context, page, homeKey(context, page), payload, false)
        save(context, page, key(context, "unified_v3", page), payload)
    }

    fun saveQWeather(context: Context, page: CityPage, response: QWeatherBundle, fromHome: Boolean = false) {
        if (WeatherSettings.useQWeather(context)) {
            ForecastDayHistoryCache.save(context,page,ForecastDayHistoryCache.fromQWeather(response))
        }
        val payload = gson.toJson(response)
        if (fromHome) saveHomePayload(context, page, homeKey(context, page), payload, true)
        save(context, page, key(context, WeatherSettings.SOURCE_QWEATHER, page), payload)
    }

    fun loadCaiyun(context: Context, page: CityPage): CaiyunResponse? {
        if (WeatherSettings.useQWeather(context)) return null
        migrateToUnifiedSchema(context)
        return loadLegacyHome(context, page, key(context, "unified_v3", page))?.let { cached ->
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
        return loadLegacyHome(context, page, key(context, "unified_v3", page))?.let { cached ->
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
        (if (WeatherSettings.useQWeather(context)) loadLegacyHome(context, page, key(context, WeatherSettings.SOURCE_QWEATHER, page)) else null)?.let { cached ->
            parseQWeatherPayload(cached.payload)
                ?.let { pruneQWeather(it, cached.ageMs) }
        }

    fun loadQWeatherDisplaySnapshot(context: Context, page: CityPage): QWeatherBundle? =
        (if (WeatherSettings.useQWeather(context)) loadLegacyHome(context, page, key(context, WeatherSettings.SOURCE_QWEATHER, page)) else null)?.let { cached ->
            parseQWeatherPayload(cached.payload)
        }

    /** Older Gson payloads can bypass Kotlin defaults. Repair collections before copy/render. */
    private fun parseQWeatherPayload(payload: String): QWeatherBundle? = runCatching {
        val raw=gson.fromJson(payload,QWeatherBundle::class.java) ?: return@runCatching null
        raw.copy(hourly=(raw.hourly as List<QHour>?).orEmpty(),daily=(raw.daily as List<QDay>?).orEmpty(),
            aqiHourly=(raw.aqiHourly as List<QAirQualityHour>?).orEmpty(),minutely=(raw.minutely as List<QMinute>?).orEmpty(),
            lifeAdvice=(raw.lifeAdvice as List<QLifeIndex>?).orEmpty(),alerts=(raw.alerts as List<QAlert>?).orEmpty())
    }.getOrNull()

    fun savedAt(context: Context,page: CityPage): Long {
        val source=if(WeatherSettings.useQWeather(context)) WeatherSettings.SOURCE_QWEATHER else "unified_v3"
        val exact = key(context, source, page)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stable = locationKey(exact)
        val saved = if (page.isCurrentLocation && sameCachedLocation(prefs, page, stable)) stable else exact
        return prefs.getLong("${saved}_at", 0L)
    }

    private fun locationKey(exact: String) =
        exact.substringBeforeLast('_').substringBeforeLast('_') + "_location"

    private fun sameCachedLocation(prefs: android.content.SharedPreferences, page: CityPage, key: String): Boolean =
        nearby(page, Double.fromBits(prefs.getLong("${key}_lat", Double.NaN.toBits())),
            Double.fromBits(prefs.getLong("${key}_lng", Double.NaN.toBits())))

    private fun save(context: Context, page: CityPage, exactKey: String, payload: String) {
        if (payload.length > MAX_PAYLOAD_CHARS) return
        val key = if (page.isCurrentLocation) locationKey(exactKey) else exactKey
        val packed = WeatherDiskCache.pack(payload)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        WeatherDiskCache.write(context, prefs) {
            putLong("${key}_at", System.currentTimeMillis())
            putString("${key}_json", packed)
            if (page.isCurrentLocation) {
                putLong("${key}_lat", page.lat.toBits())
                putLong("${key}_lng", page.lng.toBits())
            }
        }
        WeatherWidgets.render(context)
        WeatherReminderPreparation.onCacheSaved(context)
    }

    private data class CachedPayload(val payload: String, val ageMs: Long, val savedAt: Long)

    private fun load(context: Context, key: String): CachedPayload? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong("${key}_at", 0L)
        val age = System.currentTimeMillis() - savedAt
        if (savedAt <= 0L || !WeatherCachePolicy.payloadUsable(age)) {
            synchronized(prefs) {
                if (prefs.getLong("${key}_at", 0L) == savedAt) {
                    prefs.edit().remove("${key}_at").remove("${key}_json")
                        .remove("${key}_lat").remove("${key}_lng").apply()
                }
            }
            return null
        }
        return WeatherDiskCache.unpack(prefs.getString("${key}_json", null))?.let { CachedPayload(it, age, savedAt) }
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
