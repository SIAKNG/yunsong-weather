package com.example.weatherapp

/**
 * 预警事件身份规则。供应商 ID 只用于同源精确识别；semanticKey 用于彩云/和风切换后的去重。
 */
object WeatherAlertIdentity {
    private const val SEMANTIC_WINDOW_MS = 2L * 60L * 60_000L

    fun providerKey(city: CityPage, type: String, area: String, upstreamId: String?, issued: Long): String {
        val stableEvent = upstreamId.orEmpty().trim().ifBlank { (issued / (24L * 60L * 60_000L)).toString() }
        return "${prefix(city, type, area)}|$stableEvent"
    }

    /** 等级不参与匹配，预警升级才会沿用同一 ID 进行再次提醒。 */
    fun semanticKey(city: CityPage, type: String, area: String, issued: Long): String =
        "${prefix(city, type, area)}|${issued / SEMANTIC_WINDOW_MS}"

    private fun prefix(city: CityPage, type: String, area: String): String {
        val normalizedArea = area
            .replace(Regex("(气象台|气象局|气象站|预警中心|发布中心)$"), "")
            .trim()
            .ifBlank { city.name }
        return "${WeatherNotificationLedger.cityKey(city)}|$type|$normalizedArea"
    }
}
