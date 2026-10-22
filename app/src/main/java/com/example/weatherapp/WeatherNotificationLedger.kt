package com.example.weatherapp

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** 后台风险检查产生的统一预警事件。 */
data class WeatherAlertEvent(
    /** 上游唯一 ID；仅用于同一供应商内的精确识别。 */
    val key: String,
    /** 跨彩云/和风的统一事件指纹：地点、灾种和发布时间窗口。 */
    val semanticKey: String = key,
    /** 只用于判断是否发生了数据源切换，绝不展示给用户。 */
    val source: String = "unknown",
    val title: String,
    val level: String,
    val severityRank: Int,
    val detail: String,
    val expiresAt: Long,
    /** 命中跨源事件时沿用已有通知 ID，保证静默覆盖同一条通知。 */
    val notificationIdOverride: Int? = null
) {
    val fingerprint: String get() = "$title|$level|$detail"
    val notificationId: Int get() = notificationIdOverride ?: 1000 + key.stablePositiveHash()
}

data class WeatherRainEvent(
    val cityKey: String,
    val locationName: String,
    val firstRainAt: Long,
    val category: String,
    val categoryRank: Int,
    val summary: String,
    val peak: String
) {
    val notificationId: Int get() = 2001 + cityKey.stablePositiveHash()
    val notificationTitle: String get() = WeatherRainPresentation.notificationTitle(locationName, firstRainAt)
    val notificationText: String get() = WeatherRainPresentation.notificationText(summary, peak)
}

enum class AlertDelivery { NEW, QUIET_UPDATE, ESCALATED }

data class AlertDeliveryRequest(val event: WeatherAlertEvent, val delivery: AlertDelivery)

data class AlertReconcileResult(
    val deliveries: List<AlertDeliveryRequest>,
    val cancelledNotificationIds: List<Int>,
    val activeCount: Int
)

enum class RainDelivery { NEW, QUIET_UPDATE, ESCALATED }

data class RainReconcileResult(
    val delivery: RainDelivery?,
    val cancelledNotificationIds: List<Int> = emptyList()
)

/**
 * 预警/降雨的本机事件台账。它记录“事件状态”，而非简单的时间节流：
 * 同一事件不会因为打开 App 或六小时后再次出现而重发；等级升高才会再次响铃。
 */
object WeatherNotificationLedger {
    private const val PREFS = "weather_notification_ledger_v2"
    private const val ALERT_PREFIX = "alert."
    private const val RAIN_PREFIX = "rain."
    private const val KEY_LEGACY_MIGRATED = "legacy_migrated"
    private const val MAX_ALERT_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val ALERT_DISPLAY_FORMAT_VERSION = 2

    fun cityKey(city: CityPage): String = String.format(Locale.US, "%.2f,%.2f", city.lat, city.lng)

    @Synchronized
    fun reconcileAlerts(context: Context, city: CityPage, input: List<WeatherAlertEvent>,
                        deliver: (AlertDeliveryRequest) -> Boolean = { true }): AlertReconcileResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val scope = cityKey(city)
        val events = input
            .filter { it.expiresAt > now }
            .distinctBy { it.key }
        val baselineOnly = migrateLegacyAlertState(context, prefs)
        val activeKeys = events.mapTo(hashSetOf()) { it.key }
        val activeSemanticKeys = events.mapTo(hashSetOf()) { it.semanticKey }
        val activeLegacyPrefixes = events.mapTo(hashSetOf()) { it.key.substringBeforeLast('|') }
        val editor = prefs.edit()
        val cancelled = ArrayList<Int>()
        val stored = prefs.all.keys
            .filter { it.startsWith(ALERT_PREFIX + scope + "|") }
            .mapNotNull { storedKey ->
                parseAlertEntry(prefs.getString(storedKey, null))?.let { storedKey to it }
            }

        prefs.all.keys
            .filter { it.startsWith(ALERT_PREFIX + scope + "|") }
            .forEach { storedKey ->
                val entry = parseAlertEntry(prefs.getString(storedKey, null))
                val eventKey = storedKey.removePrefix(ALERT_PREFIX)
                // semanticKey 命中表示用户刚切换彩云/和风：不能先删旧记录，
                // 否则随后会把同一预警错误地当作新的预警再次响铃。
                val remainsActive = eventKey in activeKeys ||
                    entry?.semanticKey in activeSemanticKeys ||
                    (entry?.semanticKey.isNullOrBlank() && eventKey.substringBeforeLast('|') in activeLegacyPrefixes)
                if (entry == null || entry.expiresAt <= now || !remainsActive) {
                    entry?.let { cancelled += it.notificationId }
                    editor.remove(storedKey)
                }
            }

        val deliveries = ArrayList<AlertDeliveryRequest>()
        events.forEach { event ->
            var storageKey = ALERT_PREFIX + event.key
            var existing = parseAlertEntry(prefs.getString(storageKey, null))
            if (existing == null) {
                // 只有来源不同才跨源合并。相同来源换了上游 ID 时，仍按新预警处理，
                // 避免把两个真正独立的预警错误合成一个。
                val crossSource = stored.firstOrNull { (candidateKey, candidate) ->
                    candidate.source != event.source &&
                        candidate.semanticKey == event.semanticKey
                } ?: stored.firstOrNull { (candidateKey, candidate) ->
                    // 兼容本次升级前已写入的记录：当地区+灾种一致且标题一致时，
                    // 先沿用通知 ID，不因升级或首次换源重复提示。
                    candidate.semanticKey.isBlank() &&
                        candidateKey.removePrefix(ALERT_PREFIX).substringBeforeLast('|') == event.key.substringBeforeLast('|') &&
                        candidate.fingerprint.substringBefore('|') == event.title
                }
                if (crossSource != null) {
                    storageKey = crossSource.first
                    existing = crossSource.second
                }
            }
            val delivery = when {
                existing == null -> if (baselineOnly) null else AlertDelivery.NEW
                event.severityRank > existing.severityRank -> AlertDelivery.ESCALATED
                // 升级到“每条预警独立展示”的通知样式：仅一次静默重绘，
                // 让旧版仍在通知栏里的合并子项立即显示自己的标题与详情。
                existing.displayFormatVersion < ALERT_DISPLAY_FORMAT_VERSION -> AlertDelivery.QUIET_UPDATE
                event.fingerprint != existing.fingerprint -> AlertDelivery.QUIET_UPDATE
                else -> null
            }
            val effectiveEvent = if (existing == null) event else event.copy(notificationIdOverride = existing.notificationId)
            if (delivery != null && !deliver(AlertDeliveryRequest(effectiveEvent, delivery))) return@forEach
            editor.putString(storageKey, AlertEntry(
                notificationId = effectiveEvent.notificationId,
                fingerprint = event.fingerprint,
                severityRank = event.severityRank,
                expiresAt = event.expiresAt.coerceAtMost(now + MAX_ALERT_AGE_MS),
                semanticKey = event.semanticKey,
                source = event.source,
                displayFormatVersion = ALERT_DISPLAY_FORMAT_VERSION
            ).encode())
            if (delivery != null) deliveries += AlertDeliveryRequest(effectiveEvent, delivery)
        }
        editor.apply()
        return AlertReconcileResult(deliveries, cancelled.distinct(), events.size)
    }

    @Synchronized
    fun reconcileRain(context: Context, event: WeatherRainEvent, deliver: (RainDelivery) -> Boolean = { true }): RainReconcileResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storageKey = RAIN_PREFIX + event.cityKey
        val existing = parseRainEntry(prefs.getString(storageKey, null))
        // 提醒地点变更后，原地点的“两小时降雨”已经不再适用，主动撤销旧通知。
        val staleEntries = prefs.all.keys
            .filter { it.startsWith(RAIN_PREFIX) && it != storageKey }
            .mapNotNull { key -> parseRainEntry(prefs.getString(key, null)) }
        val earlier = existing != null && event.firstRainAt + 15L * 60_000L < existing.firstRainAt
        val delivery = when {
            existing == null -> RainDelivery.NEW
            event.categoryRank > existing.categoryRank -> RainDelivery.ESCALATED
            earlier -> RainDelivery.ESCALATED
            event.locationName != existing.locationName ||
                event.summary != existing.summary || event.peak != existing.peak -> RainDelivery.QUIET_UPDATE
            else -> null
        }
        if (delivery != null && !deliver(delivery)) return RainReconcileResult(null)
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(RAIN_PREFIX) && it != storageKey }.forEach(::remove)
            putString(storageKey, RainEntry(
            notificationId = event.notificationId,
            locationName = event.locationName,
            firstRainAt = event.firstRainAt,
            categoryRank = event.categoryRank,
            summary = event.summary,
            peak = event.peak
            ).encode())
            apply()
        }
        return RainReconcileResult(delivery, staleEntries.map { it.notificationId }.distinct())
    }

    fun clearRain(context: Context): List<Int> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 当前定位城市无降雨时，其他地点的临时降雨提醒同样不再适用。
        val entries = prefs.all.keys
            .filter { it.startsWith(RAIN_PREFIX) }
            .mapNotNull { key -> parseRainEntry(prefs.getString(key, null)) }
        if (entries.isEmpty()) return emptyList()
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(RAIN_PREFIX) }.forEach(::remove)
            apply()
        }
        return entries.map { it.notificationId }.distinct()
    }

    private fun migrateLegacyAlertState(context: Context, ledger: android.content.SharedPreferences): Boolean {
        if (ledger.getBoolean(KEY_LEGACY_MIGRATED, false)) return false
        val old = context.applicationContext.getSharedPreferences("weather_notification_state", Context.MODE_PRIVATE)
        val oldKeys = old.all.keys.filter { it.startsWith("alert_") }
        // 已安装旧版本有活跃预警时，先只建立新台账，避免升级后把旧预警再响一遍。
        if (oldKeys.isNotEmpty()) old.edit().apply { oldKeys.forEach(::remove); apply() }
        ledger.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
        return oldKeys.isNotEmpty()
    }

    private data class AlertEntry(
        val notificationId: Int,
        val fingerprint: String,
        val severityRank: Int,
        val expiresAt: Long,
        val semanticKey: String,
        val source: String,
        val displayFormatVersion: Int
    ) {
        fun encode(): String = JSONObject().apply {
            put("id", notificationId)
            put("f", fingerprint)
            put("s", severityRank)
            put("e", expiresAt)
            put("k", semanticKey)
            put("src", source)
            put("v", displayFormatVersion)
        }.toString()
    }

    private fun parseAlertEntry(raw: String?): AlertEntry? = runCatching {
        val value = JSONObject(raw.orEmpty())
        AlertEntry(
            notificationId = value.getInt("id"),
            fingerprint = value.getString("f"),
            severityRank = value.getInt("s"),
            expiresAt = value.getLong("e"),
            semanticKey = value.optString("k", ""),
            source = value.optString("src", "unknown"),
            displayFormatVersion = value.optInt("v", 1)
        )
    }.getOrNull()

    private data class RainEntry(
        val notificationId: Int,
        val locationName: String,
        val firstRainAt: Long,
        val categoryRank: Int,
        val summary: String,
        val peak: String
    ) {
        fun encode(): String = JSONObject().apply {
            put("id", notificationId)
            put("n", locationName)
            put("t", firstRainAt)
            put("r", categoryRank)
            put("s", summary)
            put("p", peak)
        }.toString()
    }

    private fun parseRainEntry(raw: String?): RainEntry? = runCatching {
        val value = JSONObject(raw.orEmpty())
        RainEntry(
            notificationId = value.getInt("id"),
            locationName = value.optString("n", ""),
            firstRainAt = value.getLong("t"),
            categoryRank = value.getInt("r"),
            summary = value.getString("s"),
            peak = value.getString("p")
        )
    }.getOrNull()
}

internal fun String.stablePositiveHash(): Int = when (val hash = hashCode()) {
    Int.MIN_VALUE -> 0
    else -> kotlin.math.abs(hash)
}

/** 变温/大风等预报提醒台账：同一城市、类型、预报时段只响一次，强度升级才再提醒。 */
data class WeatherChangeEvent(
    val city: CityPage,
    val type: String,
    val forecastAtMillis: Long,
    val severity: Int,
    val title: String,
    val detail: String
) {
    val key: String get() = "${WeatherNotificationLedger.cityKey(city)}|$type|${forecastAtMillis / 3_600_000L}"
    val notificationId: Int get() = 4_000 + key.stablePositiveHash()
}

object WeatherChangeNotificationLedger {
    private const val PREFS = "weather_change_notification_ledger"

    /** @return true when the event is new or its severity has increased. */
    fun shouldDeliver(context: Context, event: WeatherChangeEvent): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getInt(event.key, Int.MIN_VALUE)
        val deliver = previous == Int.MIN_VALUE || event.severity > previous
        return deliver
    }

    fun markDelivered(context: Context, event: WeatherChangeEvent) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(event.key, event.severity).apply()
        // 预报窗口已过的记录在每次写入时顺手裁掉，避免台账无限增长。
        val nowHour = System.currentTimeMillis() / 3_600_000L
        prefs.all.keys.filter { key ->
            key.substringAfterLast('|').toLongOrNull()?.let { it < nowHour - 48L } == true
        }.forEach { stale -> prefs.edit().remove(stale).apply() }
    }
}

/**
 * 变温提醒在 21:00 前即可被天气刷新发现，但只能在发送窗口内实际投递。
 * 待投递状态独立保存，避免跨过午夜或一次系统拒绝后丢失“直到成功”的重试条件。
 */
object TemperatureChangeDeliveryLedger {
    private const val PREFS = "temperature_change_delivery_v1"
    private const val PREFIX = "pending."

    data class Pending(
        val key: String,
        val notificationId: Int,
        val forecastAtMillis: Long,
        val severity: Int,
        val title: String,
        val detail: String,
        val delivered: Boolean
    ) {
        fun encode(): String = JSONObject().apply {
            put("id", notificationId)
            put("at", forecastAtMillis)
            put("severity", severity)
            put("title", title)
            put("detail", detail)
            put("delivered", delivered)
        }.toString()
    }

    @Synchronized
    fun stage(context: Context, event: WeatherChangeEvent) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pruneExpired(prefs)
        val storageKey = PREFIX + event.key
        val old = parse(event.key, prefs.getString(storageKey, null))
        // 已成功的同一预报日绝不能因后续刷新再次退回“待投递”。
        if (old?.delivered == true) return
        val next = Pending(
            key = event.key,
            notificationId = event.notificationId,
            forecastAtMillis = event.forecastAtMillis,
            severity = event.severity,
            title = event.title,
            detail = event.detail,
            delivered = false
        )
        prefs.edit().putString(storageKey, next.encode()).apply()
    }

    /** 仅在通知管理器实际接受后才把对应事件改为已送达。 */
    @Synchronized
    fun deliverDue(
        context: Context,
        now: Long = System.currentTimeMillis(),
        deliver: (Pending) -> Boolean
    ): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pruneExpired(prefs, now)
        var delivered = 0
        prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { storageKey ->
            val pending = parse(storageKey.removePrefix(PREFIX), prefs.getString(storageKey, null)) ?: return@forEach
            if (pending.delivered || !WeatherReminderPolicy.isTemperatureReminderDue(pending.forecastAtMillis, now)) {
                return@forEach
            }
            if (deliver(pending)) {
                prefs.edit().putString(storageKey, pending.copy(delivered = true).encode()).apply()
                delivered++
            }
        }
        return delivered
    }

    @Synchronized
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun pruneExpired(prefs: android.content.SharedPreferences, now: Long = System.currentTimeMillis()) {
        val editor = prefs.edit()
        var changed = false
        prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { storageKey ->
            val pending = parse(storageKey.removePrefix(PREFIX), prefs.getString(storageKey, null))
            val forecastDate = pending?.forecastAtMillis?.let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            }
            if (pending == null || forecastDate == null || now >= forecastDate.plusDays(1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) {
                editor.remove(storageKey)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun parse(key: String, raw: String?): Pending? = runCatching {
        val value = JSONObject(raw.orEmpty())
        Pending(
            key = key,
            notificationId = value.getInt("id"),
            forecastAtMillis = value.getLong("at"),
            severity = value.getInt("severity"),
            title = value.getString("title"),
            detail = value.getString("detail"),
            delivered = value.optBoolean("delivered", false)
        )
    }.getOrNull()
}

/** 每日预报只有在系统接受通知后才标记投递成功，权限恢复时便可继续补发。 */
object DailyForecastDeliveryLedger {
    private const val PREFS = "weather_daily_delivery_ledger"
    fun key(tomorrow: Boolean, now: Long = System.currentTimeMillis()): String =
        WeatherReminderPolicy.window(now).let { window ->
            if (window.tomorrow == tomorrow) window.key else "inactive:$tomorrow:${window.issuedDate}"
        }
    fun delivered(context: Context, tomorrow: Boolean): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(tomorrow), false)
    fun markDelivered(context: Context, tomorrow: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key(tomorrow), true).apply()
    }
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
