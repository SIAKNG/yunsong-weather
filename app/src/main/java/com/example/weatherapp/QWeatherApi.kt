package com.example.weatherapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// 和风天气（QWeather）客户端（JWT / Ed25519 鉴权）。
// 实时天气与小时预报使用公开 v7 接口，保证两者均使用和风的站点气压口径；
// 其他已接入的 v1 能力保持不变。
// 请求按"纬度/经度"定位，返回字段统一映射为彩云风格 skycon，复用现有图标与文案。
data class QNow(
    val obsTimeMillis: Long,
    val temp: Double,
    val feelsLike: Double?,
    val skycon: String?,
    val text: String,
    val windDir: String,
    val windDirDeg: Double?,
    val windScale: String,
    val windSpeedKmh: Double,
    val humidity: Double,
    val pressureHpa: Double,
    val visibilityKm: Double,
    val precip: Double
)

data class QHour(
    val timeMillis: Long,
    val temp: Double,
    val feelsLike: Double? = null,
    val skycon: String?,
    val text: String,
    val pop: Double,
    val windSpeedKmh: Double? = null,
    val humidityPercent: Double? = null,
    val pressureHpa: Double? = null,
    val visibilityKm: Double? = null,
    val uvIndex: Double? = null
)

data class QDay(
    val date: String,
    val skycon: String?,
    val high: Double?,
    val low: Double?,
    val precipProb: Double?,
    val sunrise: String?,
    val sunset: String?
)

data class QAqi(
    val updateTimeMillis: Long,
    val aqi: Double,
    val category: String,
    val primary: String?,
    val pm25: Double? = null,
    val pm10: Double? = null,
    val no2: Double? = null,
    val o3: Double? = null,
    val co: Double? = null,
    val so2: Double? = null
)

/** 和风原生小时空气质量预报；污染物为中国标准浓度。 */
data class QAirQualityHour(
    val timeMillis: Long,
    val aqi: Double?,
    val pm25: Double? = null,
    val pm10: Double? = null,
    val no2: Double? = null,
    val o3: Double? = null,
    val co: Double? = null,
    val so2: Double? = null
)

data class QMinute(
    val timeMillis: Long,
    val precip: Double,
    val type: String
)

data class QUv(
    val level: String,
    val category: String,
    val text: String
)

/** 和风天气生活指数原始项；文字与等级均不经本地规则改写。 */
data class QLifeIndex(
    val type: String,
    val name: String,
    val level: String,
    val category: String,
    val text: String
)

// 和风天气 v1 灾害预警（/weatheralert/v1/current）
data class QAlert(
    val id: String,
    val senderName: String,
    val issuedTimeMillis: Long,
    val expireTimeMillis: Long,
    val messageType: String,
    val eventTypeName: String,
    val eventTypeCode: String,
    val severity: String,
    val colorCode: String,
    val headline: String,
    val description: String,
    val instruction: String
)

data class QWeatherBundle(
    val now: QNow?,
    val hourly: List<QHour>,
    val daily: List<QDay>,
    val aqi: QAqi?,
    val aqiHourly: List<QAirQualityHour> = emptyList(),
    val minutely: List<QMinute> = emptyList(),
    val minutelySummary: String? = null,
    val uv: QUv? = null,
    val lifeAdvice: List<QLifeIndex> = emptyList(),
    val alerts: List<QAlert> = emptyList(),
    val updateTimeMillis: Long,
    val error: String? = null
)

object QWeatherApi {

    const val DEFAULT_HOST = "https://devapi.qweather.com"

    /** 旧版直连入口；保留 Context 参数以兼容已改造的调用点。 */
    fun fetchAll(context: Context, lat: Double, lng: Double): QWeatherBundle =
        fetchAll(lat, lng, WeatherSettings.legacyQWeatherJwtConfig(context), context)

    fun fetchAlertsOnly(context: Context, lat: Double, lng: Double): List<QAlert> =
        runCatching { fetchAlertsOnly(lat, lng, WeatherSettings.legacyQWeatherJwtConfig(context)) }
            .getOrDefault(emptyList())

    /** 不吞掉失败，避免后台把网络故障误记为预警解除。 */
    fun fetchAlertsOnlyChecked(context: Context, lat: Double, lng: Double): List<QAlert> =
        WeatherSettings.legacyQWeatherJwtConfig(context).let {
            fetchAlerts(hostOf(it.host), lat, lng, QWeatherJwt.token(it), strict = true)
        }

    /** 生活建议始终单独取和风全量指数，不影响首页当前选择的实况/预报数据源。 */
    fun fetchLifeAdvice(context: Context, lat: Double, lng: Double): List<QLifeIndex> = runCatching {
        val config = WeatherSettings.legacyQWeatherJwtConfig(context)
        LifeAdviceCache.getOrFetch(context, lat, lng) {
            fetchLifeAdvice(hostOf(config.host), lat, lng, QWeatherJwt.token(config))
        }
    }.getOrDefault(emptyList())

    fun fetchAll(lat: Double, lng: Double, config: QWeatherJwt.Config, context: Context? = null): QWeatherBundle {
        val base = hostOf(config.host)
        val token = try {
            QWeatherJwt.token(config)
        } catch (e: Exception) {
            return QWeatherBundle(
                now = null,
                hourly = emptyList(),
                daily = emptyList(),
                aqi = null,
                updateTimeMillis = System.currentTimeMillis(),
                error = "接口凭证无法使用，请检查天气源配置"
            )
        }
        var error: String? = null
        val now = try {
            fetchNow(base, lat, lng, token)
        } catch (e: Exception) {
            error = WeatherPrivacy.requestFailure(e)
            null
        }
        val hourly = try {
            fetchHourly(base, lat, lng, token)
        } catch (e: Exception) {
            emptyList()
        }
        val daily = try {
            fetchDaily(base, lat, lng, token)
        } catch (e: Exception) {
            emptyList()
        }
        val aqi = try {
            fetchAqi(base, lat, lng, token)
        } catch (e: Exception) {
            null
        }
        val aqiHourly = try {
            fetchAqiHourly(base, lat, lng, token)
        } catch (e: Exception) {
            emptyList()
        }
        val minutely = try {
            fetchMinutely(base, lat, lng, token)
        } catch (e: Exception) {
            emptyList<QMinute>() to null
        }
        val uv = try {
            fetchUv(base, lat, lng, token)
        } catch (e: Exception) {
            null
        }
        val lifeAdvice = try {
            if (context != null) LifeAdviceCache.getOrFetch(context, lat, lng) { fetchLifeAdvice(base, lat, lng, token) }
            else fetchLifeAdvice(base, lat, lng, token)
        } catch (e: Exception) {
            emptyList()
        }
        val alerts = try {
            fetchAlerts(base, lat, lng, token)
        } catch (e: Exception) {
            emptyList()
        }
        return QWeatherBundle(
            now = now,
            hourly = hourly,
            daily = daily,
            aqi = aqi,
            aqiHourly = aqiHourly,
            minutely = minutely.first,
            minutelySummary = minutely.second,
            uv = uv,
            lifeAdvice = lifeAdvice,
            alerts = alerts,
            updateTimeMillis = now?.obsTimeMillis ?: System.currentTimeMillis(),
            error = error
        )
    }

    private fun hostOf(host: String): String {
        val h = host.trim().trimEnd('/')
        if (h.isBlank()) return DEFAULT_HOST
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
    }

    private fun fetchNow(base: String, lat: Double, lng: Double, token: String): QNow? {
        val text = httpGet(
            "$base/v7/weather/now?location=${fmt(lng)},${fmt(lat)}&lang=zh&unit=m",
            token
        ) ?: return null
        return try {
            val root = JSONObject(text)
            val now = root.optJSONObject("now") ?: return null
            val temp = optD(now, "temp") ?: return null
            // v7 的 windSpeed 单位已是 km/h，不能沿用旧 v1 的 m/s × 3.6 转换。
            val pressure = optD(now, "pressure") ?: return null
            QNow(
                obsTimeMillis = parseTime(optStr(now, "obsTime")) ?: System.currentTimeMillis(),
                temp = temp,
                feelsLike = optD(now, "feelsLike"),
                skycon = skyconFromIcon(optStr(now, "icon")),
                text = optStr(now, "text"),
                windDir = windDirText(optStr(now, "windDir"), optD(now, "wind360")),
                windDirDeg = optD(now, "wind360"),
                windScale = optStr(now, "windScale"),
                windSpeedKmh = optD(now, "windSpeed") ?: 0.0,
                humidity = optD(now, "humidity") ?: 0.0,
                pressureHpa = pressure,
                visibilityKm = optD(now, "vis") ?: 0.0,
                precip = optD(now, "precip") ?: 0.0
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchHourly(base: String, lat: Double, lng: Double, token: String): List<QHour> {
        val text = httpGet(
            "$base/v7/weather/24h?location=${fmt(lng)},${fmt(lat)}&lang=zh&unit=m",
            token
        ) ?: return emptyList()
        return try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("hourly") ?: return emptyList()
            val out = ArrayList<QHour>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = parseTime(optStr(o, "fxTime"))
                val temp = optD(o, "temp")
                if (t == null || temp == null) continue
                out.add(
                    QHour(
                        timeMillis = t,
                        temp = temp,
                        feelsLike = optD(o, "feelsLike"),
                        skycon = skyconFromIcon(optStr(o, "icon")),
                        text = optStr(o, "text"),
                        pop = optD(o, "pop") ?: 0.0,
                        windSpeedKmh = optD(o, "windSpeed"),
                        humidityPercent = optD(o, "humidity"),
                        pressureHpa = optD(o, "pressure"),
                        visibilityKm = optD(o, "vis"),
                        uvIndex = optD(o, "uvIndex")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchDaily(base: String, lat: Double, lng: Double, token: String): List<QDay> {
        val text = httpGet(
            "$base/weather/v1/daily/${fmt(lat)}/${fmt(lng)}?days=7&lang=zh&localTime=true",
            token
        ) ?: return emptyList()
        return try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("days") ?: return emptyList()
            val out = ArrayList<QDay>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val daytime = o.optJSONObject("daytime")
                val condition = daytime?.optJSONObject("condition")
                val astro = o.optJSONObject("astro")
                out.add(
                    QDay(
                        date = o.optString("forecastStartTime").ifBlank {
                            o.optString("forecastEndTime")
                        },
                        skycon = skyconFromIcon(condition?.optString("code") ?: ""),
                        high = o.optJSONObject("temperatureMax")?.let { optD(it, "value") },
                        low = o.optJSONObject("temperatureMin")?.let { optD(it, "value") },
                        precipProb = daytime?.optJSONObject("precipitation")
                            ?.let { optD(it, "probability") }?.times(100.0),
                        sunrise = astro?.optString("sunrise")?.takeIf { it.isNotBlank() },
                        sunset = astro?.optString("sunset")?.takeIf { it.isNotBlank() }
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchAqi(base: String, lat: Double, lng: Double, token: String): QAqi? {
        val text = httpGet(
            "$base/airquality/v1/current/${fmt(lat)}/${fmt(lng)}?lang=zh",
            token
        ) ?: return null
        return try {
            val root = JSONObject(text)
            val indexes = root.optJSONArray("indexes") ?: return null
            val chosen = findIndex(indexes, "cn-mee")
                ?: findIndex(indexes, "cn-mee-1h")
                ?: indexes.optJSONObject(0)
                ?: return null
            val aqi = optD(chosen, "aqi") ?: return null
            val pollutants = root.optJSONArray("pollutants")
            QAqi(
                updateTimeMillis = System.currentTimeMillis(),
                aqi = aqi,
                category = chosen.optString("category").takeIf { it.isNotBlank() } ?: "未知",
                primary = chosen.optJSONObject("primaryPollutant")?.optString("name")
                    ?.takeIf { it.isNotBlank() },
                pm25 = pollutantValue(pollutants, "pm2p5", "pm25", "pm2.5"),
                pm10 = pollutantValue(pollutants, "pm10"),
                no2 = pollutantValue(pollutants, "no2"),
                o3 = pollutantValue(pollutants, "o3"),
                co = pollutantValue(pollutants, "co"),
                so2 = pollutantValue(pollutants, "so2")
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 官方 airquality/v1/hourly 返回未来 24 小时 AQI、六项污染物与子指数。
     * 解析时容忍产品版本中污染物 code/浓度字段的小差异，缺失值保持 null。
     */
    private fun fetchAqiHourly(base: String, lat: Double, lng: Double, token: String): List<QAirQualityHour> {
        val text = httpGet(
            "$base/airquality/v1/hourly/${fmt(lat)}/${fmt(lng)}?lang=zh",
            token
        ) ?: return emptyList()
        return parseAqiHourlyResponse(text)
    }

    /**
     * v1 空气质量接口实际返回 `hours` / `forecastTime`，而不是天气 v7 的
     * `hourly` / `fxTime`。保留后者兜底，以兼容网关的历史响应。
     */
    internal fun parseAqiHourlyResponse(text: String): List<QAirQualityHour> {
        return try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("hours") ?: root.optJSONArray("hourly") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val hour = arr.optJSONObject(i) ?: continue
                    val time = parseTime(optStr(hour, "forecastTime"))
                        ?: parseTime(optStr(hour, "fxTime"))
                        ?: parseTime(optStr(hour, "time"))
                        ?: continue
                    val indexes = hour.optJSONArray("indexes")
                    // 中国站点优先国标 AQI；个别覆盖区域只回传 QAQI 时仍保留趋势，
                    // 但 UI 的来源文本会明确是和风 AQI，不与其它数据源混用。
                    val selected = indexes?.let {
                        findIndex(it, "cn-mee") ?: findIndex(it, "cn-mee-1h") ?: findIndex(it, "qaqi")
                    }
                    val pollutants = hour.optJSONArray("pollutants")
                    add(
                        QAirQualityHour(
                            timeMillis = time,
                            aqi = selected?.let { optD(it, "aqi") } ?: optD(hour, "aqi"),
                            pm25 = pollutantValue(pollutants, "pm2p5", "pm25", "pm2.5"),
                            pm10 = pollutantValue(pollutants, "pm10"),
                            no2 = pollutantValue(pollutants, "no2"),
                            o3 = pollutantValue(pollutants, "o3"),
                            co = pollutantValue(pollutants, "co"),
                            so2 = pollutantValue(pollutants, "so2")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // 分钟级降水：未来 2 小时、每 5 分钟一个点的降水预报（仅支持中国地区）。
    // 仍是旧版 v7 端点 /v7/minutely/5m，JWT Bearer 鉴权与 v1 接口相同。
    private fun fetchMinutely(
        base: String,
        lat: Double,
        lng: Double,
        token: String
    ): Pair<List<QMinute>, String?> {
        val text = httpGet(
            "$base/v7/minutely/5m?location=${fmt(lng)},${fmt(lat)}&lang=zh",
            token
        ) ?: return emptyList<QMinute>() to null
        return try {
            val root = JSONObject(text)
            val summary = root.optString("summary").takeIf { it.isNotBlank() }
            val arr = root.optJSONArray("minutely")
                ?: return emptyList<QMinute>() to summary
            val out = ArrayList<QMinute>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = parseTime(o.optString("fxTime")) ?: continue
                val precip = try {
                    o.optString("precip").toDouble().takeIf { !it.isNaN() } ?: 0.0
                } catch (e: Exception) {
                    0.0
                }
                out.add(QMinute(t, precip, o.optString("type")))
            }
            out to summary
        } catch (e: Exception) {
            emptyList<QMinute>() to null
        }
    }

    // 天气指数（紫外线）：v7 指数接口 type=5 为紫外线指数
    private fun fetchUv(base: String, lat: Double, lng: Double, token: String): QUv? {
        val text = httpGet(
            "$base/v7/indices/1d?type=5&location=${fmt(lng)},${fmt(lat)}&lang=zh",
            token
        ) ?: return null
        return try {
            val root = JSONObject(text)
            val daily = root.optJSONArray("daily") ?: return null
            val today = daily.optJSONObject(0) ?: return null
            val level = today.optString("level").takeIf { it.isNotBlank() } ?: return null
            QUv(
                level = level,
                category = today.optString("category").takeIf { it.isNotBlank() }.orEmpty(),
                text = today.optString("text").takeIf { it.isNotBlank() }.orEmpty()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * type=0 是官方的“全部指数”请求。保留服务返回的中文名称、等级、分类和原始 text，
     * 不再由客户端根据温湿风等字段重新推算生活建议。
     */
    private fun fetchLifeAdvice(base: String, lat: Double, lng: Double, token: String): List<QLifeIndex> {
        val text = httpGet(
            "$base/v7/indices/1d?type=0&location=${fmt(lng)},${fmt(lat)}&lang=zh",
            token
        ) ?: return emptyList()
        return try {
            val daily = JSONObject(text).optJSONArray("daily") ?: return emptyList()
            buildList {
                for (i in 0 until daily.length()) {
                    val item = daily.optJSONObject(i) ?: continue
                    val type = item.optString("type").takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                    add(
                        QLifeIndex(
                            type = type,
                            name = name,
                            level = item.optString("level"),
                            category = item.optString("category"),
                            text = item.optString("text")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // 灾害预警：v1 预警接口，返回当前生效的预警；cancel/已过期在上层过滤
    private fun fetchAlerts(base: String, lat: Double, lng: Double, token: String, strict: Boolean = false): List<QAlert> {
        val text = httpGet(
            "$base/weatheralert/v1/current/${fmt(lat)}/${fmt(lng)}?lang=zh&localTime=true",
            token
        ) ?: if (strict) throw java.io.IOException("QWeather alert request failed") else return emptyList()
        return parseAlertsResponse(text, strict)
    }

    internal fun parseAlertsResponse(text: String, strict: Boolean = true): List<QAlert> {
        return try {
            val root = JSONObject(text)
            if (root.optJSONObject("metadata")?.optBoolean("zeroResult") == true) return emptyList()
            val arr = root.optJSONArray("alerts") ?: if (strict) throw java.io.IOException("QWeather alerts missing") else return emptyList()
            val out = ArrayList<QAlert>(arr.length())
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // current 接口本身只返回当前有效预警。个别预警缺少失效时间时不能
                // 直接丢弃；仅在服务端明确给出且已经过期时过滤。
                val issued = parseTime(optStr(o, "issuedTime"))
                    ?: parseTime(optStr(o, "effectiveTime"))
                    ?: parseTime(optStr(o, "onsetTime"))
                    ?: now
                val expire = parseTime(optStr(o, "expireTime"))
                if (expire != null && expire <= now) continue
                val eventType = o.optJSONObject("eventType")
                val messageType = o.optJSONObject("messageType")
                val color = o.optJSONObject("color") ?: o.optJSONObject("severityColor")
                val headline = optStr(o, "headline")
                val eventName = eventType?.let { optStr(it, "name") }.orEmpty()
                    .ifBlank { optStr(o, "eventTypeName") }
                    .ifBlank { headline.substringBefore("预警").trim() }
                out.add(
                    QAlert(
                        id = optStr(o, "id"),
                        senderName = optStr(o, "senderName"),
                        issuedTimeMillis = issued,
                        expireTimeMillis = expire ?: Long.MAX_VALUE,
                        messageType = messageType?.let { optStr(it, "code") }
                            ?.ifBlank { optStr(o, "messageType") } ?: optStr(o, "messageType"),
                        eventTypeName = eventName,
                        eventTypeCode = eventType?.let { optStr(it, "code") } ?: "",
                        severity = optStr(o, "severity"),
                        colorCode = color?.let { optStr(it, "code") } ?: "",
                        headline = headline,
                        description = optStr(o, "description"),
                        instruction = optStr(o, "instruction")
                    )
                )
            }
            out
        } catch (e: Exception) {
            if (strict) throw e
            emptyList()
        }
    }

    // 单独拉取和风预警（页面和后台的唯一预警来源）。
    fun fetchAlertsOnly(lat: Double, lng: Double, config: QWeatherJwt.Config): List<QAlert> {
        val base = hostOf(config.host)
        val token = QWeatherJwt.token(config)
        return fetchAlerts(base, lat, lng, token)
    }

    private fun findIndex(arr: JSONArray, code: String): JSONObject? {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null && o.optString("code") == code) return o
        }
        return null
    }

    private fun pollutantValue(pollutants: JSONArray?, vararg codes: String): Double? {
        if (pollutants == null) return null
        for (index in 0 until pollutants.length()) {
            val item = pollutants.optJSONObject(index) ?: continue
            if (codes.any { it.equals(optStr(item, "code"), true) }) {
                val concentration = item.optJSONObject("concentration")
                return concentration?.let { optD(it, "value") }
                    ?: optD(item, "concentration")
                    ?: optD(item, "value")
            }
        }
        return null
    }

    // 和风 icon 代码 → 彩云风格 skycon（图标/文案直接复用现有素材）
    fun skyconFromIcon(icon: String): String? = when (icon) {
        "100" -> "CLEAR_DAY"
        "150" -> "CLEAR_NIGHT"
        "101", "102", "103" -> "PARTLY_CLOUDY_DAY"
        "151", "152", "153" -> "PARTLY_CLOUDY_NIGHT"
        "104" -> "CLOUDY"
        "300", "301", "305" -> "LIGHT_RAIN"
        "306", "314" -> "MODERATE_RAIN"
        "307", "308", "310", "311", "312", "315", "316", "317", "318", "399" -> "HEAVY_RAIN"
        "302", "303", "304" -> "STORM_RAIN"
        "400" -> "LIGHT_SNOW"
        "401", "408" -> "MODERATE_SNOW"
        "402", "403", "409", "410" -> "HEAVY_SNOW"
        "404", "405", "406", "457" -> "RAIN_SNOW"
        "407", "456" -> "LIGHT_SNOW"
        "499" -> "HEAVY_SNOW"
        "500", "501", "509", "510", "514", "515" -> "FOG"
        "502" -> "LIGHT_HAZE"
        "511" -> "MODERATE_HAZE"
        "512", "513" -> "HEAVY_HAZE"
        "503", "504" -> "DUST"
        "507", "508" -> "SAND"
        "900" -> "CLEAR_DAY"
        "901" -> "CLOUDY"
        else -> null
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    // v1 风向：优先用 compass 代码，缺失时按角度换算成中文八方位
    private fun windDirText(compass: String?, degree: Double?): String {
        when (compass?.lowercase(Locale.US)) {
            "n" -> return "北风"
            "nne" -> return "东北偏北风"
            "ne" -> return "东北风"
            "ene" -> return "东北偏东风"
            "e" -> return "东风"
            "ese" -> return "东南偏东风"
            "se" -> return "东南风"
            "sse" -> return "东南偏南风"
            "s" -> return "南风"
            "ssw" -> return "西南偏南风"
            "sw" -> return "西南风"
            "wsw" -> return "西南偏西风"
            "w" -> return "西风"
            "wnw" -> return "西北偏西风"
            "nw" -> return "西北风"
            "nnw" -> return "西北偏北风"
            "none", "vrb" -> return "风向不定"
        }
        if (degree != null) {
            val dirs = arrayOf("北风", "东北风", "东风", "东南风", "南风", "西南风", "西风", "西北风")
            return dirs[((degree + 22.5) / 45.0).toInt() % 8]
        }
        return ""
    }

    private fun optD(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        return try {
            o.optDouble(key).takeIf { !it.isNaN() }
        } catch (e: Exception) {
            null
        }
    }

    // org.json 的 optString 会把 JSON null 转成字符串 "null"，这里统一清成空串
    private fun optStr(o: JSONObject, key: String): String {
        val s = o.optString(key)
        return if (s == "null") "" else s
    }

    private fun parseTime(s: String): Long? {
        return TimeUtil.parse(s)
    }

    internal fun authenticationHeaders(token: String): Map<String, String> =
        if (token.startsWith("api-key:")) mapOf("X-QW-Api-Key" to token.removePrefix("api-key:"))
        else mapOf("Authorization" to "Bearer $token")

    private fun httpGet(urlStr: String, token: String): String = WeatherHttp.get(urlStr, authenticationHeaders(token))
}
