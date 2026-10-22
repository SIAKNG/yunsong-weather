package com.example.weatherapp

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 不记录坐标、Token 或完整错误内容的本机通知诊断摘要。 */
object WeatherReminderDiagnostics {
    private const val PREFS = "weather_reminder_diagnostics"
    private const val KEY_TIME = "last_time"
    private const val KEY_TASK = "last_task"
    private const val KEY_STATE = "last_state"
    private const val KEY_DETAIL = "last_detail"
    private const val KEY_SCHEDULE_TIME = "schedule_time"
    private const val KEY_SCHEDULE_DETAIL = "schedule_detail"

    fun record(context: Context, task: String, state: String, detail: String = "") {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putString(KEY_TASK, task.take(32))
            .putString(KEY_STATE, state.take(32))
            .putString(KEY_DETAIL, detail.replace('\n', ' ').take(120))
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_TIME, 0L)
        if (time <= 0L) return "最近任务：尚未运行"
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val task = prefs.getString(KEY_TASK, "通知任务") ?: "通知任务"
        val state = prefs.getString(KEY_STATE, "未知") ?: "未知"
        val detail = prefs.getString(KEY_DETAIL, "")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        return "最近任务：${formatter.format(Date(time))} $task $state$detail"
    }

    fun recordSchedule(context: Context, detail: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SCHEDULE_TIME, System.currentTimeMillis())
            .putString(KEY_SCHEDULE_DETAIL, detail.take(120))
            .apply()
    }

    fun scheduleSummary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_SCHEDULE_TIME, 0L)
        if (time <= 0L) return "风险检查：未排程"
        val detail = prefs.getString(KEY_SCHEDULE_DETAIL, "已排程") ?: "已排程"
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return "风险检查：$detail（${formatter.format(Date(time))} 校验）"
    }
}
