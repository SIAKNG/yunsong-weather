package com.example.weatherapp

import android.content.Context
import com.google.gson.Gson
import java.util.Locale

/**
 * 二级页“昨日同期”使用的历史小时时间线。记录来源与获取时间，防止将异步补数
 * 错当作当前天气源数据；成功结果短期缓存，重复进入详情不再闪现“待加载”。
 */
data class MetricHistoryTimeline(
    val source: String,
    val observedAtMillis: Long,
    val savedAtMillis: Long,
    val points: List<HourlyMetricPoint>,
    /** 仅列出由明确补充源填入的指标，不把补充源伪装成选中天气源。 */
    val supplementedKinds: List<String>? = emptyList(),
    val supplementSource: String? = null,
    /** 同一次彩云历史响应中的 AQI/污染物，供空气详情页做昨日同期对比。 */
    val airQualityPoints: List<AirQualityPoint>? = emptyList()
)

object MetricHistoryTimelineCache {
    private const val PREFS = "metric_history_timeline"
    private const val MAX_AGE_MS = 36L * 60L * 60L * 1000L
    private val gson = Gson()

    fun load(context: Context, page: CityPage, source: String): MetricHistoryTimeline? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(page, source), null)
            ?.let { runCatching { gson.fromJson(it, MetricHistoryTimeline::class.java) }.getOrNull() }
            ?: return null
        if (System.currentTimeMillis() - value.savedAtMillis > MAX_AGE_MS || value.points.isEmpty()) return null
        return value.copy(
            points = value.points.filter { it.timeMillis > 0L }.sortedBy { it.timeMillis }.takeLast(24),
            supplementedKinds = value.supplementedKinds.orEmpty(),
            airQualityPoints = value.airQualityPoints.orEmpty()
        )
    }

    fun save(
        context: Context,
        page: CityPage,
        source: String,
        points: List<HourlyMetricPoint>,
        supplementedKinds: Set<MetricKind> = emptySet(),
        supplementSource: String? = null,
        airQualityPoints: List<AirQualityPoint> = emptyList()
    ) {
        val valid = points.filter { it.timeMillis > 0L }.sortedBy { it.timeMillis }.takeLast(24)
        if (valid.isEmpty()) return
        val timeline = MetricHistoryTimeline(
            source = source,
            observedAtMillis = valid.last().timeMillis,
            savedAtMillis = System.currentTimeMillis(),
            points = valid,
            supplementedKinds = supplementedKinds.map { it.name }.sorted(),
            supplementSource = supplementSource,
            airQualityPoints = airQualityPoints
                .filter { it.timeMillis > 0L }
                .sortedBy { it.timeMillis }
                .takeLast(24)
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(page, source), gson.toJson(timeline))
            .apply()
    }

    private fun key(page: CityPage, source: String): String = String.format(
        Locale.US, "%s_%.3f_%.3f", source, page.lat, page.lng
    )
}
