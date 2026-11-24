package com.example.weatherapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Locale

/** Only predefined events are accepted, so request parameters and addresses never enter the log. */
internal object WeatherDiagnostics {
    const val PREFS = "weather_problem_diagnostics"
    const val MIME_TYPE = "application/zip"
    private const val MAX_EVENTS = 640
    private const val EVENT_COUNT = "event_count"
    private const val SESSION_ENDED = "session_ended"
    private const val LAST_SESSION = "last_session"
    private val legacyLine = Regex("""^(.+?)  (?:\[([^\]]+)\] )?(.+)$""")
    private const val SESSION_MS = 30 * 60 * 1_000L
    private const val CRASH_HISTORY_MS = 7 * 24 * 60 * 60 * 1_000L
    private const val CRASH_PREFS = "weather_problem_crashes"
    private const val ENTRIES = "entries"
    private const val STARTED = "started"
    private const val CLEARED = "cleared"
    internal val locationProviders = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)

    enum class Event(val label: String) {
        RECORDING_STARTED("开始记录"), RECORDING_STOPPED("停止记录"), USER_MARK("用户标记问题"),
        HOME_OPEN("打开首页"), LOCATION_START("请求系统定位"), LOCATION_PRECISE("精确定位已授权"),
        LOCATION_APPROXIMATE("仅大致定位已授权"), LOCATION_PERMISSION("定位权限未授权"),
        LOCATION_OFF("系统定位已关闭"), LOCATION_NO_PROVIDER("没有可用的定位服务"),
        LOCATION_PROVIDER_STARTED("已请求定位服务"), LOCATION_PROVIDER_FAILED("定位服务无法启动"),
        LOCATION_CACHED("收到近期缓存位置"),
        LOCATION_RECEIVED("收到系统位置"), LOCATION_TIMEOUT("系统定位超时"),
        LOCATION_CANCELLED("离开首页，停止本次定位"), LOCATION_APPLIED("已更新当前位置天气页"),
        LOCATION_RETRY("用户重试定位"),
        ADDRESS_RESOLVED("系统已解析地址"), ADDRESS_UNAVAILABLE("系统地址解析无结果或失败"),
        ADDRESS_TIMEOUT("系统地址解析超时，使用已取得的位置"),
        WEATHER_OK("天气请求完成"), WEATHER_FAILED("天气请求失败")
    }

    enum class Issue(val label: String) {
        LOCATION("定位"), WEATHER("天气数据"), PERFORMANCE("卡顿或掉帧"), DISPLAY("界面显示或交互"),
        CRASH("闪退或无响应"), NOTIFICATION("天气提醒"), WIDGET("桌面小组件"), OTHER("其他问题")
    }

    fun sessionRemainingMs(context: Context): Long {
        val elapsed = System.currentTimeMillis() - store(context).getLong(STARTED, 0L)
        return if (elapsed in 0 until SESSION_MS) SESSION_MS - elapsed else 0L
    }

    fun enabled(context: Context): Boolean = sessionRemainingMs(context) > 0L

    fun activeSession(context: Context): Long {
        val started = store(context).getLong(STARTED, 0L)
        return started.takeIf { System.currentTimeMillis() - it in 0 until SESSION_MS } ?: 0L
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (!enabled) WeatherDiagnosticMonitor.flushCurrentWindow()
        synchronized(this) {
            val prefs = store(context)
            if (enabled) {
                val started = System.currentTimeMillis()
                prefs.edit().putLong(STARTED, started).putLong(LAST_SESSION, started).remove(SESSION_ENDED).apply()
                record(context, Event.RECORDING_STARTED)
            } else {
                record(context, Event.RECORDING_STOPPED)
                prefs.edit().remove(STARTED).putLong(SESSION_ENDED, System.currentTimeMillis()).apply()
            }
        }
        WeatherDiagnosticMonitor.sessionChanged(enabled)
    }

    fun record(context: Context, event: Event, provider: String? = null) {
        val category = when (event) {
            Event.RECORDING_STARTED, Event.RECORDING_STOPPED, Event.USER_MARK -> "记录"
            Event.HOME_OPEN -> "界面"
            Event.WEATHER_OK, Event.WEATHER_FAILED -> "天气"
            else -> "定位"
        }
        val eventText = providerLabel(provider)?.let { "${event.label}（$it）" } ?: event.label
        event(context, category, eventText)
    }

    fun mark(context: Context, issue: Issue) = event(context, "标记", issue.label)

    // All callers supply fixed labels and diagnostic numbers only.
    @Synchronized
    fun event(context: Context, category: String, detail: String, fields: JSONObject? = null) {
        val session = activeSession(context)
        if (session == 0L) return
        val prefs = store(context)
        val previous = storedLines(context)
        val number = prefs.getLong(EVENT_COUNT, 0L).coerceAtLeast(previous.size.toLong()) + 1
        val now = System.currentTimeMillis()
        val item = JSONObject()
            .put("sequence", number)
            .put("session_started_at_ms", session)
            .put("timestamp_ms", now)
            .put("elapsed_realtime_ms", android.os.SystemClock.elapsedRealtime())
            .put("category", category)
            .put("message", detail)
        if (fields != null) item.put("data", fields)
        val lines = (previous + item.toString()).takeLast(MAX_EVENTS)
        prefs.edit().putLong(EVENT_COUNT, number).putString(ENTRIES, lines.joinToString("\n")).apply()
    }

    private fun storedLines(context: Context): List<String> =
        store(context).getString(ENTRIES, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()

    internal fun entries(context: Context): List<JSONObject> = storedLines(context).mapNotNull { line ->
        if (line.startsWith('{')) runCatching { JSONObject(line) }.getOrNull()
        else legacyLine.matchEntire(line)?.let { match ->
            JSONObject().put("local_timestamp", match.groupValues[1])
                .put("category", match.groupValues[2].ifEmpty { "记录" })
                .put("message", match.groupValues[3])
        }
    }

    fun clear(context: Context) {
        synchronized(this) {
            store(context).edit().clear().putLong(CLEARED, System.currentTimeMillis()).apply()
            crashes(context).edit().clear().apply()
        }
        WeatherDiagnosticMonitor.sessionChanged(false)
    }

    fun saveCrash(context: Context, failure: Throwable, mainThread: Boolean) {
        // Persist before Android terminates the process. Avoid exception messages and raw system traces.
        val now = System.currentTimeMillis()
        val causes = JSONArray()
        generateSequence(failure) { it.cause }.take(4).forEach { error ->
            val frames = JSONArray()
            error.stackTrace.asSequence().filter {
                it.className.startsWith("com.example.weatherapp.") || it.className.startsWith("android.")
            }.take(20).forEach {
                frames.put(JSONObject().put("class", it.className).put("method", it.methodName).put("line", it.lineNumber))
            }
            causes.put(JSONObject().put("type", error.javaClass.name).put("frames", frames))
        }
        val crash = JSONObject().put("timestamp_ms", now)
            .put("session_started_at_ms", activeSession(context).takeIf { it > 0L } ?: JSONObject.NULL)
            .put("thread", if (mainThread) "main" else "background")
            .put("exceptions", causes)
        crashes(context).edit().putLong("time", now).putString("stack", crash.toString()).commit()
    }

    fun archiveName(): String {
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return "云松天气-诊断-$time.zip"
    }

    fun writeArchive(context: Context, output: OutputStream) {
        val now = System.currentTimeMillis()
        val prefs = store(context)
        val (events, discarded) = synchronized(this) {
            val items = entries(context)
            items to (prefs.getLong(EVENT_COUNT, 0L) - items.size).coerceAtLeast(0)
        }
        val session = prefs.getLong(LAST_SESSION, 0L).takeIf { it > 0L }
        val ended = prefs.getLong(SESSION_ENDED, 0L).takeIf { it > 0L }
            ?: session?.plus(SESSION_MS)?.takeIf { it <= now }
        val manifest = JSONObject()
            .put("schema", "yunsong.diagnostics")
            .put("schema_version", 1)
            .put("exported_at_ms", now)
            .put("timezone", TimeZone.getDefault().id)
            .put("event_count", events.size)
            .put("discarded_events", discarded)
            .put("latest_session_started_at_ms", session ?: JSONObject.NULL)
            .put("latest_session_ended_at_ms", ended ?: JSONObject.NULL)
            .put("recording", enabled(context))
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("app_build", BuildConfig.VERSION_CODE)
            .put("edition", if (BuildConfig.USER_CONFIGURABLE) "user_api" else "built_in")
        ZipOutputStream(output).use { zip ->
            zip.entry("manifest.json", manifest.toString(2))
            zip.entry("environment.json", environment(context).toString(2))
            zip.entry("timeline.jsonl", events.joinToString("\n") + if (events.isEmpty()) "" else "\n")
            zip.entry("crashes.json", crashHistory(context, now).toString(2))
        }
    }

    private fun ZipOutputStream.entry(name: String, contents: String) {
        putNextEntry(ZipEntry(name))
        write(contents.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun crashHistory(context: Context, now: Long): JSONObject {
        val after = maxOf(store(context).getLong(CLEARED, 0L), now - CRASH_HISTORY_MS)
        val saved = crashes(context)
        val time = saved.getLong("time", 0L)
        val recent = saved.getString("stack", null)?.takeIf { time > after }?.let { stack ->
            runCatching { JSONObject(stack) }.getOrElse {
                JSONObject().put("timestamp_ms", time).put("legacy_filtered_stack", stack)
            }
        }
        val exits = JSONArray()
        WeatherDiagnosticMonitor.recentSystemExits(context, after).forEach { (timestamp, reason) ->
            exits.put(JSONObject().put("timestamp_ms", timestamp).put("reason", reason))
        }
        return JSONObject().put("system_exit_history_supported", Build.VERSION.SDK_INT >= 30)
            .put("last_java_crash", recent ?: JSONObject.NULL)
            .put("system_exits", exits)
    }

    private fun environment(context: Context): JSONObject {
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val permission = when {
            precise -> "precise"
            WeatherPermissionCoordinator.hasForegroundLocation(context) -> "approximate"
            else -> "denied"
        }
        val config = context.resources.configuration
        val manager = context.getSystemService(LocationManager::class.java)
        val locationEnabled = runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrNull()
        val available = runCatching { manager.allProviders }.getOrDefault(emptyList())
        val providers = JSONObject()
        for (provider in locationProviders) {
            val state = when {
                provider !in available -> "unavailable"
                runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) -> "enabled"
                else -> "disabled"
            }
            providers.put(provider, state)
        }
        val network = runCatching {
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        }.getOrNull()
        val transport = when {
            network == null -> "unavailable"
            network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val notifications = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        return JSONObject().put("android_api", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("weather_source", WeatherSettings.dataSource(context).takeIf {
                it in setOf("caiyun", "qweather", "open_meteo", "open_meteo_qweather")
            } ?: JSONObject.NULL)
            .put("dark_mode", WeatherSettings.isDarkTheme(context))
            .put("font_scale", config.fontScale.toDouble()).put("density_dpi", config.densityDpi)
            .put("location_permission", permission)
            .put("has_location_city", WeatherCityStore.cities(context).any { it.isCurrentLocation })
            .put("location_enabled", locationEnabled ?: JSONObject.NULL).put("location_providers", providers)
            .put("geocoder_available", runCatching { Geocoder.isPresent() }.getOrDefault(false))
            .put("network_transport", transport).put("notifications_enabled", notifications)
    }

    private fun providerLabel(provider: String?): String? = when (provider) {
        "fused" -> "系统融合定位"
        LocationManager.NETWORK_PROVIDER -> "网络定位"
        LocationManager.GPS_PROVIDER -> "卫星定位"
        LocationManager.PASSIVE_PROVIDER -> "被动定位"
        else -> null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(context: Context): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, archiveName())
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val file = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(resolver.openOutputStream(file)).use { output ->
                writeArchive(context, output)
            }
            val completed = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(file, completed, null, null) == 1)
            return file
        } catch (failure: Exception) {
            runCatching { resolver.delete(file, null, null) }
            throw failure
        }
    }

    private fun crashes(context: Context) = context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)

    private fun store(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
