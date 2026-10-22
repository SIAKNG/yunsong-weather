package com.example.weatherapp

import kotlin.math.abs

/** 首页实时快照与趋势补充数据的合并规则，确保“现在”只使用天气源权威值。 */
object AirQualitySnapshotPolicy {
    fun mergeCurrentIntoTrend(
        points: List<AirQualityPoint>,
        current: AirQualityPoint?,
        nowMillis: Long = System.currentTimeMillis()
    ): List<AirQualityPoint> {
        if (current == null) return points.sortedBy { it.timeMillis }
        val ordered = points.sortedBy { it.timeMillis }.toMutableList()
        if (ordered.isEmpty()) return listOf(current)
        val nearest = ordered.indices.minByOrNull { abs(ordered[it].timeMillis - nowMillis) } ?: 0
        val trend = ordered[nearest]
        ordered[nearest] = AirQualityPoint(
            timeMillis = trend.timeMillis,
            aqi = current.aqi ?: trend.aqi,
            pm25 = current.pm25 ?: trend.pm25,
            pm10 = current.pm10 ?: trend.pm10,
            no2 = current.no2 ?: trend.no2,
            o3 = current.o3 ?: trend.o3,
            co = current.co ?: trend.co,
            so2 = current.so2 ?: trend.so2,
            source = current.source,
            updatedAtMillis = maxOf(current.updatedAtMillis, trend.updatedAtMillis)
        )
        return ordered
    }
}
