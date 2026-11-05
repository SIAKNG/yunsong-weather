package com.example.weatherapp

/** One wording source for the 48-hour/day detail and the daily summary widget. */
internal object ForecastSummaryAdvice {
    fun dailyRain(value: Int) = if (value >= 30) "出门建议备伞" else "出行留意天气变化"
    fun hourlyRain(value: Int) = if (value >= 30) "建议带伞" else "常规出行即可"
    fun wind(maximum: Double) = when {
        maximum < 12 -> "风力温和，适合出行"
        maximum < 25 -> "阵风时注意防风"
        else -> "风力较强，减少高处停留"
    }
    fun ultraviolet(maximum: Double) = when {
        maximum <= 2 -> "按需防晒"
        maximum <= 5 -> "基础防晒"
        else -> "避免午间暴晒"
    }
    fun air(maximum: Double) = when {
        maximum <= 50 -> "放心出行"
        maximum <= 100 -> "日常出行正常"
        maximum <= 150 -> "敏感人群少久留"
        maximum <= 200 -> "减少户外活动"
        else -> "尽量减少外出"
    }
    const val MISSING = "以已提供的数据为准"
}
