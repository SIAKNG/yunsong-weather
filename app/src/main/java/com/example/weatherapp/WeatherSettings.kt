package com.example.weatherapp

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import kotlin.math.roundToInt

// 天气设置（SharedPreferences 持久化）：深浅色模式、单位及提醒开关。
// 天气数据固定为“彩云主源 + 和风按字段补齐”，用户不能切换数据源或输入凭证。
object WeatherSettings {

    const val SOURCE_CAIYUN = "caiyun"
    const val SOURCE_QWEATHER = "qweather"

    const val CAIYUN_ACCESS_LEGACY_DIRECT = "legacy_direct"
    const val CAIYUN_ACCESS_SHARED = "shared"
    const val CAIYUN_ACCESS_OWN_TOKEN = "own_token"

    const val CAIYUN_MINUTELY_UNKNOWN = 0
    const val CAIYUN_MINUTELY_CHECKING = 1
    const val CAIYUN_MINUTELY_AVAILABLE = 2
    const val CAIYUN_MINUTELY_UNAVAILABLE = 3

    const val THEME_SYSTEM = "system"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    const val TEMP_C = "c"
    const val TEMP_F = "f"

    const val WIND_KMH = "kmh"
    const val WIND_MS = "ms"
    const val WIND_MPH = "mph"
    const val WIND_BF = "bf"

    const val PRESSURE_HPA = "hpa"
    const val PRESSURE_MMHG = "mmhg"
    const val PRESSURE_INHG = "inhg"

    const val VIS_KM = "km"
    const val VIS_MI = "mi"
    const val VIS_M = "m"

    private const val PREFS = "weather_settings"
    private const val KEY_CAIYUN_MINUTELY_CAPABILITY = "caiyun_minutely_capability"

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 旧安装的 source 偏好被忽略，避免后台回调把页面切回旧数据源。 */
    fun dataSource(c: Context): String = if (BuildConfig.USER_CONFIGURABLE) UserApiConfiguration.read(c).source else SOURCE_CAIYUN

    fun setDataSource(c: Context, v: String) {
        // 保留 ABI 兼容；数据源已固定，不能再由设置修改。
    }

    /**
     * 旧版“内置直连”包含固定凭证，已移除。保留该历史值的安装会平滑迁移到
     * 本机加密 Token 模式，不再让旧凭证继续发起网络请求。
     */
    fun caiyunAccessMode(c: Context): String = when (
        prefs(c).getString("caiyun_access_mode", CAIYUN_ACCESS_OWN_TOKEN)
    ) {
        CAIYUN_ACCESS_SHARED -> CAIYUN_ACCESS_SHARED
        CAIYUN_ACCESS_OWN_TOKEN -> CAIYUN_ACCESS_OWN_TOKEN
        else -> CAIYUN_ACCESS_OWN_TOKEN
    }

    fun setCaiyunAccessMode(c: Context, v: String) {
        val normalized = if (v == CAIYUN_ACCESS_SHARED) CAIYUN_ACCESS_SHARED else CAIYUN_ACCESS_OWN_TOKEN
        prefs(c).edit().putString("caiyun_access_mode", normalized).apply()
    }

    fun usesOwnCaiyunToken(c: Context): Boolean = BuildConfig.USER_CONFIGURABLE

    fun usesSharedCaiyunGateway(c: Context): Boolean = false

    fun caiyunMinutelyCapability(c: Context): Int = if (BuildConfig.USER_CONFIGURABLE)
        prefs(c).getInt(KEY_CAIYUN_MINUTELY_CAPABILITY, CAIYUN_MINUTELY_UNKNOWN) else CAIYUN_MINUTELY_AVAILABLE

    /** 首次天气请求只允许一个页面探测分钟降雨权限，避免多城市并发重复探测。 */
    @Synchronized fun beginCaiyunMinutelyProbe(c: Context): Boolean {
        if (!BuildConfig.USER_CONFIGURABLE || caiyunMinutelyCapability(c) != CAIYUN_MINUTELY_UNKNOWN) return false
        setCaiyunMinutelyCapability(c, CAIYUN_MINUTELY_CHECKING)
        return true
    }

    fun setCaiyunMinutelyCapability(c: Context, capability: Int) {
        if (BuildConfig.USER_CONFIGURABLE) prefs(c).edit().putInt(KEY_CAIYUN_MINUTELY_CAPABILITY, capability).apply()
    }

    fun resetCaiyunMinutelyCapability(c: Context) {
        setCaiyunMinutelyCapability(c, CAIYUN_MINUTELY_UNKNOWN)
    }

    /**
     * 使用彩云主数据源时默认执行天气现象校准；和风天气不执行。该规则不在设置页
     * 暴露，因此历史偏好值不会阻止彩云校准。辅助源只获得本次城市坐标，不读取、
     * 不上传彩云 Token。
     */
    fun usesOpenMeteoPhenomenonCalibration(c: Context): Boolean = false

    /** 当前本机恢复旧直连时使用；共享网关启用后应移除该配置。 */
    fun legacyQWeatherJwtConfig(context: Context? = null): QWeatherJwt.Config =
        if (BuildConfig.USER_CONFIGURABLE && context != null) UserApiConfiguration.read(context).jwtConfig() else QWeatherJwt.Config(
        host = BuildConfig.LEGACY_QWEATHER_HOST,
        kid = BuildConfig.LEGACY_QWEATHER_KID,
        sub = BuildConfig.LEGACY_QWEATHER_SUB,
        privateKeyPem = BuildConfig.LEGACY_QWEATHER_PRIVATE_KEY_PEM
    )

    fun tempUnit(c: Context): String = prefs(c).getString("temp_unit", TEMP_C) ?: TEMP_C

    fun setTempUnit(c: Context, v: String) {
        prefs(c).edit().putString("temp_unit", v).apply()
    }

    fun windUnit(c: Context): String = prefs(c).getString("wind_unit", WIND_KMH) ?: WIND_KMH

    fun setWindUnit(c: Context, v: String) {
        prefs(c).edit().putString("wind_unit", v).apply()
    }

    fun pressureUnit(c: Context): String =
        prefs(c).getString("pressure_unit", PRESSURE_HPA) ?: PRESSURE_HPA

    fun setPressureUnit(c: Context, v: String) {
        prefs(c).edit().putString("pressure_unit", v).apply()
    }

    fun visibilityUnit(c: Context): String =
        prefs(c).getString("vis_unit", VIS_KM) ?: VIS_KM

    fun setVisibilityUnit(c: Context, v: String) {
        prefs(c).edit().putString("vis_unit", v).apply()
    }

    // ===== 深浅色模式：跟随系统 / 固定深色 / 固定浅色 =====
    fun themeMode(c: Context): String =
        prefs(c).getString("theme_mode", THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(c: Context, v: String) {
        prefs(c).edit().putString("theme_mode", v).apply()
    }

    // 当前实际是否处于深色（固定模式直接判断；跟随系统时看系统 uiMode）
    fun isDarkTheme(c: Context): Boolean = when (themeMode(c)) {
        THEME_DARK -> true
        THEME_LIGHT -> false
        else -> (c.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // 把设置同步到 AppCompat 的夜间模式，驱动全 App 的 values-night 资源与背景配色
    fun applyNightMode(c: Context) {
        val mode = when (themeMode(c)) {
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    fun useQWeather(c: Context): Boolean = BuildConfig.USER_CONFIGURABLE && dataSource(c) == SOURCE_QWEATHER

    // ===== 天气提醒 =====
    fun disasterReminder(c: Context): Boolean = prefs(c).getBoolean("reminder_disaster", false)
    fun setDisasterReminder(c: Context, enabled: Boolean) = prefs(c).edit().putBoolean("reminder_disaster", enabled).apply()
    fun temperatureChangeReminder(c: Context): Boolean = prefs(c).getBoolean("reminder_temperature_change", false)
    fun setTemperatureChangeReminder(c: Context, enabled: Boolean) = prefs(c).edit().putBoolean("reminder_temperature_change", enabled).apply()
    fun strongWindReminder(c: Context): Boolean = prefs(c).getBoolean("reminder_strong_wind", false)
    fun setStrongWindReminder(c: Context, enabled: Boolean) = prefs(c).edit().putBoolean("reminder_strong_wind", enabled).apply()
    fun rainReminder(c: Context): Boolean = prefs(c).getBoolean("reminder_rain", false)
    fun setRainReminder(c: Context, enabled: Boolean) = prefs(c).edit().putBoolean("reminder_rain", enabled).apply()
    fun dailyForecastReminder(c: Context): Boolean = prefs(c).getBoolean("reminder_daily", false)
    fun setDailyForecastReminder(c: Context, enabled: Boolean) = prefs(c).edit().putBoolean("reminder_daily", enabled).apply()

    // ===== 单位换算 =====

    /** 摄氏温度 → 用户选择的显示单位 */
    fun tempDisplay(c: Context, celsius: Double?): Double? {
        if (celsius == null) return null
        return WeatherUnitConverter.temperature(celsius, tempUnit(c))
    }

    fun tempText(c: Context, celsius: Double?): String {
        val v = tempDisplay(c, celsius) ?: return "--°"
        return if (tempUnit(c) == TEMP_F) "${Math.round(v)}°F" else "${Math.round(v)}°"
    }

    fun windText(c: Context, dir: String?, speedKmh: Double?, scale: String?): String {
        val prefix = if (dir.isNullOrBlank()) "风" else dir
        val kmh = speedKmh ?: return prefix
        return when (windUnit(c)) {
            WIND_MS -> "$prefix ${one(WeatherUnitConverter.wind(kmh, WIND_MS))}m/s"
            WIND_MPH -> "$prefix ${one(WeatherUnitConverter.wind(kmh, WIND_MPH))}mph"
            WIND_BF -> {
                val s = scale ?: beaufort(kmh).toString()
                "$prefix ${s}级"
            }
            else -> "$prefix ${kmh.roundToInt()}km/h"
        }
    }

    fun pressureText(c: Context, hpa: Double?): String {
        val v = hpa ?: return "气压 --"
        return when (pressureUnit(c)) {
            PRESSURE_MMHG -> "气压 ${Math.round(WeatherUnitConverter.pressure(v, PRESSURE_MMHG))}mmHg"
            PRESSURE_INHG -> "气压 ${one(WeatherUnitConverter.pressure(v, PRESSURE_INHG))}inHg"
            else -> "气压 ${Math.round(v)}hPa"
        }
    }

    fun visibilityText(c: Context, km: Double?): String {
        val v = km ?: return "能见度 --"
        return when (visibilityUnit(c)) {
            VIS_MI -> "能见度 ${one(WeatherUnitConverter.visibility(v, VIS_MI))}mi"
            VIS_M -> "能见度 ${Math.round(WeatherUnitConverter.visibility(v, VIS_M))}m"
            else -> "能见度 ${one(v)}km"
        }
    }

    // 详情卡片用：只返回数值+单位（不带标题前缀）
    fun windSpeedValue(c: Context, speedKmh: Double?, scale: String?): String {
        val kmh = speedKmh ?: return "--"
        return when (windUnit(c)) {
            WIND_MS -> "${one(WeatherUnitConverter.wind(kmh, WIND_MS))}m/s"
            WIND_MPH -> "${one(WeatherUnitConverter.wind(kmh, WIND_MPH))}mph"
            WIND_BF -> "${scale ?: beaufort(kmh)}级"
            else -> "${kmh.roundToInt()}km/h"
        }
    }

    // 风力等级（罗盘中央显示，如 “1级”）
    fun windLevel(c: Context, speedKmh: Double?, scale: String?): String {
        val kmh = speedKmh ?: return "--"
        return "${scale ?: beaufort(kmh)}级"
    }

    fun pressureValue(c: Context, hpa: Double?): String {
        val v = hpa ?: return "--"
        return when (pressureUnit(c)) {
            PRESSURE_MMHG -> "${Math.round(WeatherUnitConverter.pressure(v, PRESSURE_MMHG))}mmHg"
            PRESSURE_INHG -> "${one(WeatherUnitConverter.pressure(v, PRESSURE_INHG))}inHg"
            else -> "${Math.round(v)}hPa"
        }
    }

    fun visibilityValue(c: Context, km: Double?): String {
        val v = km ?: return "--"
        return when (visibilityUnit(c)) {
            VIS_MI -> "${one(WeatherUnitConverter.visibility(v, VIS_MI))}mi"
            VIS_M -> "${Math.round(WeatherUnitConverter.visibility(v, VIS_M))}m"
            else -> "${one(v)}km"
        }
    }

    /**
     * 二级图表和摘要共用的指标格式化入口。所有接口数据仍以基础单位保存，
     * 这里只在展示层转换，避免不同页面各自换算后出现不一致。
     */
    fun metricUnit(c: Context, kind: MetricKind): String = when (kind) {
        MetricKind.UV -> "级"
        MetricKind.FEELS_LIKE -> if (tempUnit(c) == TEMP_F) "°F" else "℃"
        MetricKind.WIND -> when (windUnit(c)) {
            WIND_MS -> "m/s"
            WIND_MPH -> "mph"
            WIND_BF -> "级"
            else -> "km/h"
        }
        MetricKind.PRESSURE -> when (pressureUnit(c)) {
            PRESSURE_MMHG -> "mmHg"
            PRESSURE_INHG -> "inHg"
            else -> "hPa"
        }
        MetricKind.VISIBILITY -> when (visibilityUnit(c)) {
            VIS_MI -> "mi"
            VIS_M -> "m"
            else -> "km"
        }
        MetricKind.HUMIDITY -> "%"
    }

    fun metricText(c: Context, kind: MetricKind, value: Double?): String {
        if (value == null || value.isNaN()) return "暂无数据"
        return when (kind) {
            MetricKind.UV -> "${value.roundToInt()}级"
            MetricKind.FEELS_LIKE -> {
                val shown = tempDisplay(c, value) ?: return "暂无数据"
                "${shown.roundToInt()}${metricUnit(c, kind)}"
            }
            MetricKind.WIND -> when (windUnit(c)) {
                WIND_MS -> "${one(WeatherUnitConverter.wind(value, WIND_MS))}m/s"
                WIND_MPH -> "${one(WeatherUnitConverter.wind(value, WIND_MPH))}mph"
                WIND_BF -> "${beaufort(value)}级"
                else -> "${value.roundToInt()}km/h"
            }
            MetricKind.PRESSURE -> when (pressureUnit(c)) {
                PRESSURE_MMHG -> "${WeatherUnitConverter.pressure(value, PRESSURE_MMHG).roundToInt()}mmHg"
                PRESSURE_INHG -> "${one(WeatherUnitConverter.pressure(value, PRESSURE_INHG))}inHg"
                else -> "${value.roundToInt()}hPa"
            }
            MetricKind.VISIBILITY -> when (visibilityUnit(c)) {
                VIS_MI -> "${one(WeatherUnitConverter.visibility(value, VIS_MI))}mi"
                VIS_M -> "${WeatherUnitConverter.visibility(value, VIS_M).roundToInt()}m"
                else -> "${one(value)}km"
            }
            MetricKind.HUMIDITY -> "${value.roundToInt()}%"
        }
    }

    /** 图表纵轴显示数值，不附带单位，数据刻度仍与原始曲线同一位置。 */
    fun metricAxisText(c: Context, kind: MetricKind, value: Double): String = when (kind) {
        MetricKind.FEELS_LIKE -> (tempDisplay(c, value) ?: value).roundToInt().toString()
        MetricKind.WIND -> when (windUnit(c)) {
            WIND_MS -> one(WeatherUnitConverter.wind(value, WIND_MS))
            WIND_MPH -> one(WeatherUnitConverter.wind(value, WIND_MPH))
            WIND_BF -> beaufort(value).toString()
            else -> value.roundToInt().toString()
        }
        MetricKind.PRESSURE -> when (pressureUnit(c)) {
            PRESSURE_MMHG -> WeatherUnitConverter.pressure(value, PRESSURE_MMHG).roundToInt().toString()
            PRESSURE_INHG -> one(WeatherUnitConverter.pressure(value, PRESSURE_INHG))
            else -> value.roundToInt().toString()
        }
        MetricKind.VISIBILITY -> when (visibilityUnit(c)) {
            VIS_MI -> one(WeatherUnitConverter.visibility(value, VIS_MI))
            VIS_M -> WeatherUnitConverter.visibility(value, VIS_M).roundToInt().toString()
            else -> one(value)
        }
        else -> value.roundToInt().toString()
    }

    /** 与昨日的“差值”不能直接复用温度绝对值转换（华氏温标不应加 32）。 */
    fun metricDeltaText(c: Context, kind: MetricKind, delta: Double): String = when (kind) {
        MetricKind.FEELS_LIKE -> "${(if (tempUnit(c) == TEMP_F) delta * 1.8 else delta).roundToInt()}${metricUnit(c, kind)}"
        MetricKind.WIND -> when (windUnit(c)) {
            WIND_MS -> "${one(WeatherUnitConverter.wind(delta, WIND_MS))}m/s"
            WIND_MPH -> "${one(WeatherUnitConverter.wind(delta, WIND_MPH))}mph"
            WIND_BF -> "${beaufort(kotlin.math.abs(delta))}级"
            else -> "${delta.roundToInt()}km/h"
        }
        MetricKind.PRESSURE -> when (pressureUnit(c)) {
            PRESSURE_MMHG -> "${WeatherUnitConverter.pressure(delta, PRESSURE_MMHG).roundToInt()}mmHg"
            PRESSURE_INHG -> "${one(WeatherUnitConverter.pressure(delta, PRESSURE_INHG))}inHg"
            else -> "${delta.roundToInt()}hPa"
        }
        MetricKind.VISIBILITY -> when (visibilityUnit(c)) {
            VIS_MI -> "${one(WeatherUnitConverter.visibility(delta, VIS_MI))}mi"
            VIS_M -> "${WeatherUnitConverter.visibility(delta, VIS_M).roundToInt()}m"
            else -> "${one(delta)}km"
        }
        else -> metricText(c, kind, delta)
    }

    private fun beaufort(kmh: Double): Int = when {
        kmh < 1 -> 0
        kmh < 6 -> 1
        kmh < 12 -> 2
        kmh < 20 -> 3
        kmh < 29 -> 4
        kmh < 39 -> 5
        kmh < 50 -> 6
        kmh < 62 -> 7
        kmh < 75 -> 8
        kmh < 89 -> 9
        kmh < 103 -> 10
        kmh < 118 -> 11
        else -> 12
    }

    private fun one(v: Double): String {
        val s = String.format(Locale.US, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
