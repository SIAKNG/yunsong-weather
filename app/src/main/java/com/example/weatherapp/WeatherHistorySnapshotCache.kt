package com.example.weatherapp

import android.content.Context
import com.google.gson.Gson
import java.util.Locale

/**
 * 彩云原始响应的本地快照：用于在接口只给出未来时恢复刚刚过去的小时、昨日逐日行和
 * 昨日紫外线预测值。它不是估算值，也不会混入其它天气源。
 */
data class CaiyunHourlyWeatherSnapshot(
    val timeMillis: Long,
    val temperature: Double? = null,
    val skycon: String? = null,
    val precipitationProbability: Double? = null,
    val uv: Double? = null,
    val capturedAtMillis: Long
)

data class CaiyunDailyWeatherSnapshot(
    val date: String,
    val skycon: String? = null,
    val high: Int? = null,
    val low: Int? = null,
    val precipitationProbability: Int? = null,
    val capturedAtMillis: Long
)

data class CaiyunWeatherHistorySnapshot(
    val version: Int = 1,
    val savedAtMillis: Long,
    val hourly: List<CaiyunHourlyWeatherSnapshot> = emptyList(),
    val daily: List<CaiyunDailyWeatherSnapshot> = emptyList()
)

/** 纯合并规则，供缓存和单元测试共用。 */
object CaiyunWeatherHistoryPolicy {
    const val MAX_AGE_MS = 72L * 60L * 60L * 1000L

    fun mergeHourly(
        cached: List<CaiyunHourlyWeatherSnapshot>,
        incoming: List<CaiyunHourlyWeatherSnapshot>,
        nowMillis: Long
    ): List<CaiyunHourlyWeatherSnapshot> {
        val validCached = cached.filter { validHour(it, nowMillis) }
        val validIncoming = incoming.filter { validHour(it, nowMillis) }
        return (validCached + validIncoming)
            .groupBy { it.timeMillis / 3_600_000L }
            .values
            .map { rows ->
                val newest = rows.maxByOrNull { it.capturedAtMillis }!!
                newest.copy(uv = newest.uv ?: rows.filter { it.uv != null }.maxByOrNull { it.capturedAtMillis }?.uv)
            }
            .sortedBy { it.timeMillis }
    }

    fun mergeDaily(
        cached: List<CaiyunDailyWeatherSnapshot>,
        incoming: List<CaiyunDailyWeatherSnapshot>,
        nowMillis: Long
    ): List<CaiyunDailyWeatherSnapshot> {
        val valid = (cached + incoming)
            .filter { it.date.isNotBlank() && nowMillis - it.capturedAtMillis in 0..MAX_AGE_MS }
        return valid.groupBy { it.date.take(10) }
            .values
            .map { rows -> rows.maxByOrNull { it.capturedAtMillis }!! }
            .sortedBy { it.date }
    }

    private fun validHour(item: CaiyunHourlyWeatherSnapshot, nowMillis: Long): Boolean =
        item.timeMillis > 0L && nowMillis - item.capturedAtMillis in 0..MAX_AGE_MS &&
            item.timeMillis >= nowMillis - MAX_AGE_MS &&
            item.timeMillis <= nowMillis + 16L * 24L * 60L * 60L * 1000L
}

object CaiyunWeatherHistorySnapshotCache {
    private const val PREFS = "caiyun_weather_history_snapshot_v1"
    private const val SOURCE = "caiyun"
    private val gson = Gson()

    fun load(context: Context, page: CityPage): CaiyunWeatherHistorySnapshot? {
        val parsed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(page), null)
            ?.let { runCatching { gson.fromJson(it, CaiyunWeatherHistorySnapshot::class.java) }.getOrNull() }
            ?: return null
        val now = System.currentTimeMillis()
        val hourly = CaiyunWeatherHistoryPolicy.mergeHourly(parsed.hourly.orEmpty(), emptyList(), now)
        val daily = CaiyunWeatherHistoryPolicy.mergeDaily(parsed.daily.orEmpty(), emptyList(), now)
        if (hourly.isEmpty() && daily.isEmpty()) return null
        return parsed.copy(hourly = hourly, daily = daily)
    }

    fun save(
        context: Context,
        page: CityPage,
        hourly: List<CaiyunHourlyWeatherSnapshot> = emptyList(),
        daily: List<CaiyunDailyWeatherSnapshot> = emptyList()
    ) {
        if (hourly.isEmpty() && daily.isEmpty()) return
        val now = System.currentTimeMillis()
        val old = load(context, page)
        val merged = CaiyunWeatherHistorySnapshot(
            savedAtMillis = now,
            hourly = CaiyunWeatherHistoryPolicy.mergeHourly(old?.hourly.orEmpty(), hourly, now),
            daily = CaiyunWeatherHistoryPolicy.mergeDaily(old?.daily.orEmpty(), daily, now)
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(page), gson.toJson(merged))
            .apply()
    }

    private fun key(page: CityPage): String = String.format(
        Locale.US, "%s_%.3f_%.3f", SOURCE, page.lat, page.lng
    )
}
