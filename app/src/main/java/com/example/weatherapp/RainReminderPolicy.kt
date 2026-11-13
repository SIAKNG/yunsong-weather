package com.example.weatherapp

import kotlin.math.roundToInt

/** Actual forecast intervals, independent of countdown/summary wording. Rates are mm/h. */
data class RainForecast(val times: List<Long>, val rates: List<Double>, val intervalMillis: Long) {
    init {
        require(times.isNotEmpty() && times.size == rates.size && intervalMillis in 1..300_000L)
        require(rates.all { it.isFinite() && it >= 0.0 })
        require(times.zipWithNext().all { (a, b) -> b > a && b - a in (intervalMillis - 1)..intervalMillis })
    }
    val horizonEnd: Long get() = times.last() + intervalMillis
    fun rateAt(at: Long): Double? {
        if (at < times.first() || at >= horizonEnd) return null
        val index = times.binarySearch(at).let { if (it >= 0) it else -it - 2 }
        return rates.getOrNull(index)
    }
    fun wetIndexes(now: Long): List<Int> = rates.indices.filter {
        times[it] + intervalMillis > now && rates[it] > WeatherRainPresentation.RAIN_THRESHOLD_MM_PER_HOUR
    }
    fun rankAt(at: Long): Int = rateAt(at)?.let {
        if (it <= WeatherRainPresentation.RAIN_THRESHOLD_MM_PER_HOUR) -1
        else WeatherRainPresentation.categoryRank(WeatherRainPresentation.category(it))
    } ?: -1
    fun endingAt(now: Long): Long? = wetIndexes(now).lastOrNull()?.let { times[it] + intervalMillis }
}

/** Persisted only after acceptance, so permission/system failures remain retryable. */
data class RainReminderState(
    val delivered: RainForecast,
    val lastDeliveredAt: Long,
    val active: Boolean,
    val endingNotified: Boolean,
    val urgentRank: Int = -1,
    val observedAt: Long = lastDeliveredAt,
    val observed: RainForecast = delivered,
    val rainingObserved: Boolean = delivered.rankAt(lastDeliveredAt) >= 0
)

data class RainReminderDecision(val delivery: RainDelivery?, val state: RainReminderState?)

object RainReminderPolicy {
    const val NORMAL_STEP_MS = 30L * 60_000L
    private const val COMPARE_STEP_MS = 60_000L

    fun decide(forecast: RainForecast, previous: RainReminderState?, now: Long): RainReminderDecision {
        require(forecast.times.first() <= now + 300_000L && forecast.horizonEnd > now)
        // Keep deduplication across long background gaps, but do not infer a recent ending from an old observation.
        val old = previous
        val recentlyObserved = old != null && now <= old.observedAt + 3L * 60L * 60_000L
        val wet = forecast.wetIndexes(now)
        val endingAt = forecast.endingAt(now)
        val endingSoon = forecast.rankAt(now) >= 0 && endingAt != null &&
            endingAt <= now + NORMAL_STEP_MS && forecast.horizonEnd - endingAt >= NORMAL_STEP_MS
        val confirmedDry = wet.isEmpty() && forecast.times.first() <= now &&
            forecast.horizonEnd - now >= NORMAL_STEP_MS
        val ended = confirmedDry && recentlyObserved && old?.active == true && old.rainingObserved
        val urgentRank = wet.filter { forecast.times[it] <= now + NORMAL_STEP_MS }
            .maxOfOrNull { forecast.rankAt(maxOf(now, forecast.times[it])) } ?: -1
        val suddenIncrease = old != null && urgentRank >= 2 && urgentRank > old.urgentRank &&
            wet.any { i ->
                val at = maxOf(now, forecast.times[i])
                at <= now + NORMAL_STEP_MS && forecast.rankAt(at) >= 2 &&
                    // Unknown future coverage must not be mistaken for a sudden escalation.
                    old.observed.rateAt(at) != null && forecast.rankAt(at) > old.observed.rankAt(at)
            }
        val delivery = when {
            wet.isNotEmpty() && (old == null || !old.active) && urgentRank >= 2 -> RainDelivery.NEW
            suddenIncrease -> RainDelivery.ESCALATED
            (endingSoon || ended) && old?.endingNotified != true -> RainDelivery.ENDING
            wet.isEmpty() -> null
            old == null || !old.active -> RainDelivery.NEW
            now - old.lastDeliveredAt >= NORMAL_STEP_MS && changed(old.delivered, forecast, now) -> RainDelivery.UPDATED
            else -> null
        }
        if (delivery == null) return RainReminderDecision(null, old?.copy(
            active = if (confirmedDry) false else old.active,
            observedAt = now, observed = forecast,
            rainingObserved = old.rainingObserved || forecast.rankAt(now) >= 0))
        return RainReminderDecision(delivery, RainReminderState(
            delivered = forecast, lastDeliveredAt = now, active = wet.isNotEmpty(),
            endingNotified = delivery == RainDelivery.ENDING,
            urgentRank = if (old?.active == true) maxOf(old.urgentRank, urgentRank) else urgentRank,
            rainingObserved = (old?.active == true && old.rainingObserved) || forecast.rankAt(now) >= 0
        ))
    }

    /** Compare the same absolute times. Advancing a countdown/rolling dry horizon is not a change. */
    internal fun changed(old: RainForecast, next: RainForecast, now: Long): Boolean {
        var at = maxOf(now, old.times.first(), next.times.first())
        val until = minOf(old.horizonEnd, next.horizonEnd)
        while (at < until) {
            if (bucket(old.rateAt(at)!!) != bucket(next.rateAt(at)!!)) return true
            at += COMPARE_STEP_MS
        }
        // Extending the horizon with the same intensity is not a new forecast change.
        return next.rates.indices.any { i ->
            next.times[i] >= maxOf(now, old.horizonEnd) &&
                bucket(next.rates[i]) != bucket(old.rates.last())
        }
    }

    private fun bucket(rate: Double): Int =
        if (rate <= WeatherRainPresentation.RAIN_THRESHOLD_MM_PER_HOUR) 0 else maxOf(1, (rate * 10).roundToInt())
}
