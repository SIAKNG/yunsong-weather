package com.example.weatherapp

import java.text.SimpleDateFormat
import java.util.Locale

/** 生态环境部城市 AQI 实时报的轻量读取器；页面格式失配时宁可明确不可用。 */
object MeeAirQualityRankRepository {
    // 两个地址均为生态环境部数据服务入口；镜像只在主站不可达时回退，
    // 不与商业天气源混合，更不会把不同发布时间的数据拼成全国排名。
    private val urls = listOf(
        "https://www.mee.gov.cn/websjzx/homepages/airDatas.vm",
        "https://datacenter.mee.gov.cn/websjzx/homepages/airDatas.vm"
    )
    private val timeFormat = SimpleDateFormat("yyyy年MM月dd日 HH时", Locale.CHINA)
    // 页面实际将城市名、同批次发布时间和 AQI 放在同一个 openWin2 行；旧式匹配
    // 错把 openWin2 第一个参数后的逗号当成标签结尾，导致真实页面 0 行可解析。
    private val rowPattern = Regex(
        """openWin2\(&#39;([^&]+?)&#39;,\s*&#39;([^&]+?)&#39;.*?<i\s+class=\"ii\">\s*(\d+)\s*</i>""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val reportTimePattern = Regex("""(\d{4}年\d{2}月\d{2}日\s+\d{1,2}时)""")

    sealed interface Result {
        data class Available(
            val rank: Int,
            val total: Int,
            val topPercent: Int,
            val reportedAtMillis: Long
        ) : Result
        data object CityNotCovered : Result
        data object Unavailable : Result
    }

    fun fetch(city: String): Result {
        urls.forEach { url ->
            val parsed = runCatching { parse(WeatherHttp.get(url), city) }.getOrDefault(Result.Unavailable)
            if (parsed !is Result.Unavailable) return parsed
        }
        return Result.Unavailable
    }

    fun parse(html: String, city: String): Result {
        data class PublishedRow(val name: String, val reportTime: String, val aqi: Int)
        val parsedRows = rowPattern.findAll(html).mapNotNull { match ->
            val name = decode(match.groupValues[1]).trim()
            val reportTime = decode(match.groupValues[2]).trim()
            val aqi = match.groupValues[3].toIntOrNull()
            if (name.isBlank() || reportTime.isBlank() || aqi == null) null
            else PublishedRow(name, reportTime, aqi)
        }.toList()
        // 只采用数量最多的同一发布时间；既避免混入页面残留表格，也不拼接不同批次。
        val reportTime = parsedRows.groupingBy { it.reportTime }.eachCount()
            .maxByOrNull { it.value }?.key ?: return Result.Unavailable
        val rows = parsedRows.filter { it.reportTime == reportTime }
        if (rows.size < 100) return Result.Unavailable
        val matched = normalize(city).let { requested ->
            rows.firstOrNull { normalize(it.name) == requested }
                ?: rows.firstOrNull { normalize(it.name).contains(requested) || requested.contains(normalize(it.name)) }
        } ?: return Result.CityNotCovered
        val rank = rows.count { it.aqi < matched.aqi } + 1
        val topPercent = kotlin.math.ceil(rank * 100.0 / rows.size).toInt().coerceIn(1, 100)
        val time = reportTimePattern.find(reportTime)?.groupValues?.getOrNull(1)
            ?.let { runCatching { timeFormat.parse(it)?.time }.getOrNull() } ?: 0L
        return Result.Available(rank, rows.size, topPercent, time)
    }

    private fun normalize(value: String): String = value
        .trim()
        .replace("市", "")
        .replace("地区", "")
        .replace("自治州", "")
        .replace(Regex("\\s+"), "")

    private fun decode(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
}
