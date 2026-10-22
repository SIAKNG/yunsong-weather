package com.example.weatherapp

import kotlin.math.abs

/**
 * 单一空气质量时间线：首页实时快照、污染物网格和详情图表均由它派生。
 * 不在此处混入其他天气源；补充源只能显式构造自己的时间线。
 */
data class AirQualityTimeline(
    val source: AirQualitySource,
    val updatedAtMillis: Long,
    val realtime: AirQualityPoint?,
    val hourly: List<AirQualityPoint>
) {
    fun pointsWithRealtime(nowMillis: Long = System.currentTimeMillis()): List<AirQualityPoint> =
        AirQualitySnapshotPolicy.mergeCurrentIntoTrend(hourly, realtime, nowMillis)

    fun current(nowMillis: Long = System.currentTimeMillis()): AirQualityPoint? =
        realtime ?: hourly.minByOrNull { abs(it.timeMillis - nowMillis) }

    companion object {
        fun empty(source: AirQualitySource) = AirQualityTimeline(source, 0L, null, emptyList())
    }
}
