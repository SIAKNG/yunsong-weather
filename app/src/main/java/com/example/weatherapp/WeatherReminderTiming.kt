package com.example.weatherapp

import java.time.Instant
import java.time.ZoneId

/** Pure timing rules shared by alarms, preparation jobs and recovery tests. */
internal object WeatherReminderTiming {
    const val PREPARE_LEAD_MS = 20L * 60_000L
    const val PREPARED_MAX_AGE_MS = 12L * 60L * 60_000L

    fun recoveryAt(now: Long, previous: Long, interval: Long, fired: Boolean): Long =
        if (!fired && previous > now && previous <= now + interval) previous else now + interval

    fun preparationTimes(now: Long, daily: Boolean, temperature: Boolean): List<Long> {
        val zone = ZoneId.systemDefault()
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val hours = buildList { if (daily) { add(7); add(22) }; if (temperature) add(21) }
        return hours.map { hour ->
            var at = local.toLocalDate().atTime(hour, 0).atZone(zone).toInstant().toEpochMilli() - PREPARE_LEAD_MS
            if (at <= now) at = local.toLocalDate().plusDays(1).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli() - PREPARE_LEAD_MS
            at
        }.sorted()
    }

    fun nearDelivery(now: Long, daily: Boolean, temperature: Boolean): Boolean {
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val minute = local.hour * 60 + local.minute
        return (daily && (minute in 390..435 || minute in 1290..1335)) ||
            (temperature && minute in 1230..1275)
    }
}
