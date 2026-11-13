package com.example.weatherapp

internal data class WidgetDailySummary(
    val date: String, val temperature: Pair<Double, Double>?, val feels: Pair<Double, Double>?,
    val wind: Pair<Double, Double>?, val humidity: Pair<Double, Double>?, val air: Pair<Double, Double>?,
    val ultraviolet: ForecastHour?, val rain: Int?, val rainHour: Long?,
    val rainAdvice: String, val windAdvice: String, val uvAdvice: String, val airAdvice: String,
    val complete: Boolean, val sky: String?
)
internal object WeatherWidgetSummary {
    fun range(values: List<Double?>): Pair<Double, Double>? = values.filterNotNull().filter(Double::isFinite)
        .takeIf { it.isNotEmpty() }?.let { it.minOrNull()!! to it.maxOrNull()!! }

    fun build(data: WidgetWeather, now: Long = System.currentTimeMillis()): WidgetDailySummary {
        val date = DailyForecastDetailPolicy.dateKey(now)
        val hours = data.forecast.filter { DailyForecastDetailPolicy.dateKey(it.time) == date }
            .distinctBy { it.time / ForecastDetailData.HOUR }.sortedBy { it.time }
        val daily = data.today?.takeIf { it.date.take(10) == date }
        val page = ForecastDayPage(date, hours, daily)
        val wind = range(hours.map { it.wind }); val air = range(hours.map { it.aqi })
        val uv = hours.filter { it.uv?.isFinite() == true }.maxByOrNull { it.uv!! }
        val rainRows = hours.mapNotNull { h -> HourlyForecastDetailPolicy.percent(h.precipitationProbability)?.let { h to it } }
        val peak = rainRows.maxByOrNull { it.second }
        val probability = daily?.rainPercent ?: peak?.second
        val rainAdvice = when {
            probability == null -> ForecastSummaryAdvice.MISSING
            daily?.rainPercent != null -> ForecastSummaryAdvice.dailyRain(probability)
            rainRows.size == hours.size && rainRows.all { it.second == 0 } -> ForecastSummaryAdvice.hourlyRain(0)
            else -> ForecastSummaryAdvice.hourlyRain(probability)
        }
        val start = ForecastDayHistoryPolicy.startOfDay(now)
        val expected = ((ForecastDayHistoryPolicy.startOfDay(now, 1) - start) / ForecastDetailData.HOUR).toInt()
        val complete = hours.size == expected && hours.all { h ->
            listOf(h.temperature, h.feelsLike, h.wind, h.humidity, h.uv, h.aqi).all { it?.isFinite() == true }
        }
        return WidgetDailySummary(date, DailyForecastDetailPolicy.temperatureRange(page), range(hours.map { it.feelsLike }),
            wind, range(hours.map { it.humidity }), air, uv, probability,
            peak?.takeIf { it.second == probability }?.first?.time,
            rainAdvice, wind?.let { ForecastSummaryAdvice.wind(it.second) } ?: ForecastSummaryAdvice.MISSING,
            uv?.uv?.let(ForecastSummaryAdvice::ultraviolet) ?: ForecastSummaryAdvice.MISSING,
            air?.let { ForecastSummaryAdvice.air(it.second) } ?: ForecastSummaryAdvice.MISSING,
            complete, daily?.skycon ?: hours.firstNotNullOfOrNull { it.skycon })
    }
}
