package com.example.weatherapp

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only predefined events are accepted, so request parameters and addresses never enter the log. */
internal object WeatherDiagnostics {
    const val PREFS = "weather_problem_diagnostics"
    private const val MAX_EVENTS = 100

    enum class Event(val label: String) {
        RECORDING_STARTED("开始记录"), RECORDING_STOPPED("停止记录"), USER_MARK("用户标记问题"),
        HOME_OPEN("打开首页"), LOCATION_START("请求系统定位"), LOCATION_PRECISE("精确定位已授权"),
        LOCATION_APPROXIMATE("仅大致定位已授权"), LOCATION_PERMISSION("定位权限未授权"),
        LOCATION_OFF("系统定位已关闭"), LOCATION_NO_PROVIDER("没有可用的定位服务"),
        LOCATION_PROVIDER_FAILED("某个定位服务无法启动"), LOCATION_CACHED("收到近期缓存位置"),
        LOCATION_RECEIVED("收到系统位置"), LOCATION_TIMEOUT("系统定位超时"),
        ADDRESS_RESOLVED("系统已解析地址"), ADDRESS_UNAVAILABLE("系统地址解析无结果或失败"),
        ADDRESS_TIMEOUT("系统地址解析超时，使用已取得的位置"),
        WEATHER_OK("天气请求完成"), WEATHER_FAILED("天气请求失败")
    }

    fun enabled(context: Context): Boolean = store(context).getBoolean("enabled", false)

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = store(context)
        if (enabled) {
            prefs.edit().putBoolean("enabled", true).apply()
            record(context, Event.RECORDING_STARTED)
        } else {
            record(context, Event.RECORDING_STOPPED)
            prefs.edit().putBoolean("enabled", false).apply()
        }
    }

    @Synchronized
    fun record(context: Context, event: Event) {
        if (!BuildConfig.USER_CONFIGURABLE || !enabled(context)) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())
        val items = (entries(context) + "$timestamp  ${event.label}").takeLast(MAX_EVENTS)
        store(context).edit().putString("entries", items.joinToString("\n")).apply()
    }

    fun entries(context: Context): List<String> =
        store(context).getString("entries", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()

    @Synchronized
    fun clear(context: Context) {
        store(context).edit().clear().apply()
    }

    @Synchronized
    fun report(context: Context): String = buildString {
        appendLine("云松天气问题诊断")
        appendLine("版本 ${BuildConfig.VERSION_NAME} · Android API ${Build.VERSION.SDK_INT}")
        val source = when (WeatherSettings.dataSource(context)) {
            WeatherSettings.SOURCE_CAIYUN -> "彩云"
            WeatherSettings.SOURCE_QWEATHER -> "和风"
            else -> "免费天气"
        }
        appendLine("当前天气源 $source")
        appendLine("当前定位权限 ${if (WeatherPermissionCoordinator.hasForegroundLocation(context)) "已授权" else "未授权"}")
        appendLine()
        appendLine(entries(context).joinToString("\n").ifEmpty { "暂无记录" })
    }

    fun shareIntent(context: Context): Intent {
        val directory = File(context.cacheDir, "diagnostic-shares")
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val file = File.createTempFile("云松天气-诊断记录-", ".txt", directory)
        try {
            file.writeText(report(context), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            return Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("云松天气诊断报告", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(context: Context) {
        val fileTime = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "云松天气-诊断记录-$fileTime.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val file = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(resolver.openOutputStream(file)).use { output ->
                output.write(report(context).toByteArray(Charsets.UTF_8))
            }
            val completed = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(file, completed, null, null) == 1)
        } catch (failure: Exception) {
            runCatching { resolver.delete(file, null, null) }
            throw failure
        }
    }

    private fun store(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
