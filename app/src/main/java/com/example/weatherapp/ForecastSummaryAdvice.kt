package com.example.weatherapp

/** Short, value-specific wording shared by daily details and the daily summary widget. */
internal object ForecastSummaryAdvice {
    fun dailyRain(value: Int) = when {
        value <= 0 -> "今日无需备伞"
        value <= 10 -> "降水机会小，轻装出行"
        value < 30 -> "或有零星降水，备折叠伞"
        value < 60 -> "有雨可能，出门带伞"
        value < 80 -> "降水可能较大，备好雨具"
        else -> "降水概率高，注意避雨"
    }
    fun hourlyRain(value: Int) = dailyRain(value)
    fun wind(maximum: Double) = when {
        maximum < 6 -> "微风轻拂，适宜户外"
        maximum < 12 -> "风力轻柔，适合出行"
        maximum < 25 -> "风力适中，骑行防风"
        maximum < 40 -> "风力较强，避开高处"
        else -> "大风天气，减少外出"
    }
    fun ultraviolet(maximum: Double) = when {
        maximum <= 1 -> "今日无需额外防晒"
        maximum <= 2 -> "紫外线较弱，适宜户外"
        maximum <= 5 -> "紫外线适中，建议防晒"
        maximum <= 7 -> "紫外线较强，做好防晒"
        maximum <= 10 -> "紫外线很强，避开午间"
        else -> "紫外线极强，减少暴晒"
    }
    fun air(maximum: Double) = when {
        maximum <= 50 -> "空气优，适宜户外"
        maximum <= 100 -> "空气良好，适宜出行"
        maximum <= 150 -> "敏感人群减少户外"
        maximum <= 200 -> "污染较重，减少户外"
        maximum <= 300 -> "空气污染重，尽量少外出"
        else -> "严重污染，尽量留在室内"
    }
    const val MISSING = "数据暂缺，建议待更新"
}
