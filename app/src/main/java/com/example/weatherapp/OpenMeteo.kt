package com.example.weatherapp

import org.json.JSONObject

// 15 天逐日预报补充数据源（Open-Meteo，免费、无需密钥）。
// 彩云付费套餐实测：逐日最多 7 天（dailystart=-1 可含昨天）、逐小时最多 72 小时。
// 因此多日预报卡片由彩云优先、这里补齐超过 7 天的部分和更早的历史；
// 逐小时卡片由彩云提供未来 72 小时，这里补充过去 2 小时历史，并在彩云缺失时兜底。
data class DailyForecast(
    val date: String,
    val skycon: String?,
    val high: Int?,
    val low: Int?,
    val precipProb: Int?
)

object OpenMeteo {

    /**
     * 交叉校验专用的当前天气现象。只请求 WMO weather_code，不请求用户设备标识、
     * 彩云 Token 或其它 App 私密信息。
     */
    data class CurrentCondition(
        val timeMillis: Long,
        val skycon: String?
    )

    fun fetchCurrentCondition(lat: Double, lng: Double): CurrentCondition? {
        val urlStr = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lng&current=weather_code&timezone=Asia%2FShanghai"
        val text = httpGet(urlStr) ?: return null
        return try {
            val current = JSONObject(text).optJSONObject("current") ?: return null
            if (!current.has("weather_code") || current.isNull("weather_code")) return null
            val timestamp = parseShanghaiTime(current.optString("time")) ?: return null
            CurrentCondition(
                timeMillis = timestamp,
                skycon = skyconFromCode(current.optInt("weather_code"))
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 空气质量补全固定走 Open-Meteo 空气质量端点：它返回逐小时污染物浓度，
     * 再用现有中国 AQI 标准计算指数。这样不会把彩云、和风各自的字段格式混用。
     */
    fun fetchAirQuality(
        lat: Double,
        lng: Double,
        hours: Int = 24,
        pastHours: Int = 24
    ): List<AirQualityPoint> {
        val urlStr = "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=$lat&longitude=$lng" +
            "&hourly=pm2_5,pm10,nitrogen_dioxide,sulphur_dioxide,carbon_monoxide,ozone" +
            "&timezone=Asia%2FShanghai&past_hours=$pastHours&forecast_hours=$hours"
        val text = httpGet(urlStr) ?: return emptyList()
        return try {
            val hourly = JSONObject(text).optJSONObject("hourly") ?: return emptyList()
            val times = hourly.optJSONArray("time") ?: return emptyList()
            val pm25 = hourly.optJSONArray("pm2_5")
            val pm10 = hourly.optJSONArray("pm10")
            val no2 = hourly.optJSONArray("nitrogen_dioxide")
            val so2 = hourly.optJSONArray("sulphur_dioxide")
            val co = hourly.optJSONArray("carbon_monoxide")
            val o3 = hourly.optJSONArray("ozone")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
            List(times.length()) { index ->
                val p25 = valueAt(pm25, index)
                val p10 = valueAt(pm10, index)
                val n2 = valueAt(no2, index)
                val s2 = valueAt(so2, index)
                // Open-Meteo 的 CO 单位为 μg/m³；应用界面按国内常用 mg/m³ 展示。
                val carbon = valueAt(co, index)?.div(1000.0)
                val ozone = valueAt(o3, index)
                AirQualityPoint(
                    timeMillis = try { fmt.parse(times.optString(index))?.time ?: 0L } catch (_: Exception) { 0L },
                    aqi = chinaAqi(p25, p10, n2, s2, carbon?.times(1000.0), ozone),
                    pm25 = p25,
                    pm10 = p10,
                    no2 = n2,
                    so2 = s2,
                    co = carbon,
                    o3 = ozone
                )
            }.filter { it.timeMillis > 0L }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // 过去小时的历史天气（用于 24 小时预报卡片最前面的灰色数据）
    data class HourlyPoint(
        val timeMillis: Long,
        val temp: Double?,
        val skycon: String?,
        val prob: Double?
    )

    /**
     * 详情卡片使用的小时补充指标。主数据源缺字段时才合并这些值；同时取前 24 小时，
     * 供详情页显示“与昨日同期”对比。wind_gusts_10m 为 Open-Meteo 官方小时变量。
     */
    fun fetchHourlyMetrics(
        lat: Double,
        lng: Double,
        hours: Int = 24,
        pastHours: Int = 24
    ): List<HourlyMetricPoint> {
        val urlStr = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lng" +
            "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m," +
            "wind_gusts_10m,wind_direction_10m,surface_pressure,visibility,uv_index" +
            "&timezone=Asia%2FShanghai&past_hours=$pastHours&forecast_hours=$hours"
        val text = httpGet(urlStr) ?: return emptyList()
        return try {
            val hourly = JSONObject(text).optJSONObject("hourly") ?: return emptyList()
            val times = hourly.optJSONArray("time") ?: return emptyList()
            val temps = hourly.optJSONArray("temperature_2m")
            val feels = hourly.optJSONArray("apparent_temperature")
            val humidity = hourly.optJSONArray("relative_humidity_2m")
            val wind = hourly.optJSONArray("wind_speed_10m")
            val gust = hourly.optJSONArray("wind_gusts_10m")
            val windDirection = hourly.optJSONArray("wind_direction_10m")
            val pressure = hourly.optJSONArray("surface_pressure")
            val visibility = hourly.optJSONArray("visibility")
            val uv = hourly.optJSONArray("uv_index")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
            List(times.length()) { i ->
                HourlyMetricPoint(
                    timeMillis = try { fmt.parse(times.optString(i))?.time ?: 0L } catch (_: Exception) { 0L },
                    temperature = valueAt(temps, i),
                    feelsLike = valueAt(feels, i),
                    uv = valueAt(uv, i),
                    windKmh = valueAt(wind, i),
                    gustKmh = valueAt(gust, i),
                    windDirectionDegrees = valueAt(windDirection, i),
                    pressureHpa = valueAt(pressure, i),
                    visibilityKm = valueAt(visibility, i)?.div(1000.0),
                    humidityPercent = valueAt(humidity, i)
                )
            }.filter { it.timeMillis > 0L }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun fetchDaily(lat: Double, lng: Double, days: Int = 15): List<DailyForecast> {
        val urlStr = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lng" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FShanghai&forecast_days=$days&past_days=2"
        val text = httpGet(urlStr) ?: return emptyList()
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return emptyList()
        }
        val daily = json.optJSONObject("daily") ?: return emptyList()
        val times = daily.optJSONArray("time") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code") ?: return emptyList()
        val highs = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val lows = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
        val probs = daily.optJSONArray("precipitation_probability_max")

        val out = ArrayList<DailyForecast>()
        for (i in 0 until times.length()) {
            out.add(
                DailyForecast(
                    date = times.optString(i),
                    skycon = skyconFromCode(codes.optInt(i)),
                    high = roundTemp(highs.optDouble(i)),
                    low = roundTemp(lows.optDouble(i)),
                    precipProb = probs?.optInt(i) ?: 0
                )
            )
        }
        return out
    }

    private fun roundTemp(v: Double): Int? =
        if (v.isNaN()) null else Math.round(v).toInt()

    private fun parseShanghaiTime(value: String): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        }.parse(value)?.time
    } catch (_: Exception) {
        null
    }

    // 拉取过去 2 小时 + 未来小时的小时级数据，供 24 小时卡片前置灰色历史段
    fun fetchHourlyPast(
        lat: Double,
        lng: Double,
        pastHours: Int = 2,
        forecastHours: Int = 26
    ): List<HourlyPoint> {
        val urlStr = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lng" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&timezone=Asia%2FShanghai&past_hours=$pastHours&forecast_hours=$forecastHours"
        val text = httpGet(urlStr) ?: return emptyList()
        return try {
            val hourly = JSONObject(text).optJSONObject("hourly") ?: return emptyList()
            val times = hourly.optJSONArray("time") ?: return emptyList()
            val temps = hourly.optJSONArray("temperature_2m") ?: return emptyList()
            val codes = hourly.optJSONArray("weather_code") ?: return emptyList()
            val probs = hourly.optJSONArray("precipitation_probability")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
            val out = ArrayList<HourlyPoint>()
            for (i in 0 until times.length()) {
                val millis = try {
                    fmt.parse(times.optString(i))?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
                val prob = probs?.optDouble(i)?.takeIf { !it.isNaN() }
                out.add(
                    HourlyPoint(
                        timeMillis = millis,
                        temp = if (temps.isNull(i)) null else temps.optDouble(i),
                        skycon = skyconFromCode(codes.optInt(i)),
                        prob = prob
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 气压变化趋势（hPa/小时）：用最近两个整点的小时气压差计算，
    // 供气压仪表盘显示“上升 / 下降 / 平稳”。
    fun fetchPressureTrend(lat: Double, lng: Double): Double? {
        val urlStr = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lng&hourly=pressure_msl" +
            "&timezone=Asia%2FShanghai&past_hours=3&forecast_hours=1"
        val text = httpGet(urlStr) ?: return null
        return try {
            val hourly = JSONObject(text).optJSONObject("hourly") ?: return null
            val times = hourly.optJSONArray("time") ?: return null
            val vals = hourly.optJSONArray("pressure_msl") ?: return null
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
            val pts = ArrayList<Pair<Long, Double>>()
            for (i in 0 until times.length()) {
                if (vals.isNull(i)) continue
                val m = try {
                    fmt.parse(times.optString(i))?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
                if (m == 0L) continue
                pts.add(m to vals.optDouble(i))
            }
            if (pts.size < 2) return null
            val sorted = pts.sortedBy { it.first }
            val now = System.currentTimeMillis()
            val past = sorted.filter { it.first <= now }
            if (past.size < 2) return null
            val p1 = past[past.size - 1]
            val p0 = past[past.size - 2]
            val hours = (p1.first - p0.first) / 3600000.0
            if (hours <= 0.0) null else (p1.second - p0.second) / hours
        } catch (e: Exception) {
            null
        }
    }

    // 彩云免费接口不返回 AQI 时，用 Open-Meteo 的污染物浓度按中国 HJ 633-2012 标准计算 AQI。
    // 之前直接用 us_aqi 再套中国等级，会把数值/等级错位（例如 US AQI 125 在中国标准下只有“良”）。
    fun fetchAqi(lat: Double, lng: Double): Double? {
        val urlStr = "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=$lat&longitude=$lng" +
            "&current=pm2_5,pm10,nitrogen_dioxide,sulphur_dioxide,carbon_monoxide,ozone" +
            "&timezone=auto"
        val text = httpGet(urlStr) ?: return null
        return try {
            val current = JSONObject(text).optJSONObject("current") ?: return null
            val pm25 = optD(current, "pm2_5")
            val pm10 = optD(current, "pm10")
            val no2 = optD(current, "nitrogen_dioxide")
            val so2 = optD(current, "sulphur_dioxide")
            val co = optD(current, "carbon_monoxide")
            val o3 = optD(current, "ozone")
            chinaAqi(pm25, pm10, no2, so2, co, o3)
        } catch (e: Exception) {
            null
        }
    }

    private fun optD(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        return try {
            o.optDouble(key).takeIf { !it.isNaN() }
        } catch (e: Exception) {
            null
        }
    }

    private fun valueAt(array: org.json.JSONArray?, index: Int): Double? {
        if (array == null || index !in 0 until array.length() || array.isNull(index)) return null
        return array.optDouble(index).takeIf { !it.isNaN() }
    }

    // 中国 AQI（HJ 633-2012）：各污染物 IAQI 取最大值。
    // PM 按 24 小时、气体按 1 小时分段；用当前观测浓度近似计算，标准与彩云/国内 App 一致。
    fun chinaAqi(
        pm25: Double?,
        pm10: Double?,
        no2: Double?,
        so2: Double?,
        coUgm3: Double?,
        o3: Double?
    ): Double? {
        val vals = listOfNotNull(
            pm25?.let { iaqi(it, PM25_BP) },
            pm10?.let { iaqi(it, PM10_BP) },
            no2?.let { iaqi(it, NO2_BP) },
            so2?.let { iaqi(it, SO2_BP) },
            coUgm3?.let { iaqi(it / 1000.0, CO_BP) },
            o3?.let { iaqi(it, O3_BP) }
        )
        return vals.maxOrNull()
    }

    private fun iaqi(c: Double, bp: DoubleArray): Double {
        if (c <= bp[0]) return 0.0
        for (k in 1 until bp.size) {
            if (c <= bp[k]) {
                return IAQ[k - 1] +
                    (IAQ[k] - IAQ[k - 1]) * (c - bp[k - 1]) / (bp[k] - bp[k - 1])
            }
        }
        return 500.0
    }

    private val IAQ = doubleArrayOf(0.0, 50.0, 100.0, 150.0, 200.0, 300.0, 400.0, 500.0)
    private val PM25_BP = doubleArrayOf(0.0, 35.0, 75.0, 115.0, 150.0, 250.0, 350.0, 500.0)
    private val PM10_BP = doubleArrayOf(0.0, 50.0, 150.0, 250.0, 350.0, 420.0, 500.0, 600.0)
    private val NO2_BP = doubleArrayOf(0.0, 40.0, 80.0, 180.0, 280.0, 565.0, 750.0, 940.0)
    private val SO2_BP = doubleArrayOf(0.0, 50.0, 150.0, 475.0, 800.0, 1600.0, 2100.0, 2620.0)
    private val CO_BP = doubleArrayOf(0.0, 5.0, 10.0, 35.0, 60.0, 90.0, 120.0, 150.0)
    private val O3_BP = doubleArrayOf(0.0, 160.0, 200.0, 300.0, 400.0, 800.0, 1000.0, 1200.0)

    // Open-Meteo 的 WMO 天气代码 → 转成彩云风格 skycon，图标和中文文字直接复用
    private fun skyconFromCode(code: Int): String? = when (code) {
        0 -> "CLEAR_DAY"
        1, 2 -> "PARTLY_CLOUDY_DAY"
        3 -> "CLOUDY"
        45, 48 -> "FOG"
        51, 53, 55, 56, 57, 61, 66, 80 -> "LIGHT_RAIN"
        63, 81 -> "MODERATE_RAIN"
        65, 82 -> "HEAVY_RAIN"
        67, 71, 77, 85 -> "LIGHT_SNOW"
        73, 86 -> "MODERATE_SNOW"
        75 -> "HEAVY_SNOW"
        95, 96, 99 -> "STORM_RAIN"
        else -> null
    }

    private fun httpGet(urlStr: String): String? = try {
        WeatherHttp.get(urlStr)
    } catch (e: Exception) {
        null
    }
}
