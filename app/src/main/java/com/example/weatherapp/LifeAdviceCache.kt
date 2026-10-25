package com.example.weatherapp

import android.content.Context
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 和风生活建议按城市及当地日期独立缓存。生活指数属于日级内容，进入页面时先显示
 * 最近一次成功结果，再按较长时效后台更新；网络失败绝不抹掉当天已有建议。
 */
object LifeAdviceCache {
    private const val PREFS = "qweather_life_advice_v1"
    private const val ATTEMPT_PREFIX = "attempt:"
    private const val SUCCESS_PREFIX = "success:"
    private const val RETRY_MS = 15L * 60_000L
    private fun key(lat: Double, lng: Double) = String.format(Locale.US, "%.3f,%.3f:%s", lat, lng,
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))

    fun load(context: Context, lat: Double, lng: Double): List<QLifeIndex> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(WeatherSettings.sourceKey(context)+key(lat, lng), null)
        Gson().fromJson(raw, Array<QLifeIndex>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized
    fun getOrFetch(context: Context, lat: Double, lng: Double, fetch: () -> List<QLifeIndex>): List<QLifeIndex> {
        val appContext = context.applicationContext
        val cached = load(appContext, lat, lng)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val epoch=WeatherSettings.sourceKey(appContext)
        val k = epoch+key(lat, lng)
        val now = System.currentTimeMillis()
        // 南风天气的公开首页将生活建议视为当日内容持续展示；这里同样允许完整或
        // 部分成功结果在 6 小时内直接复用，避免每次进入都把已有建议重新置为等待。
        val lastSuccess = prefs.getLong(SUCCESS_PREFIX + k, 0L)
        if (cached.isNotEmpty() && WeatherCachePolicy.dailyUsable(now - lastSuccess)) return cached
        if (now - prefs.getLong(ATTEMPT_PREFIX + k, 0L) < RETRY_MS) return cached
        prefs.edit().putLong(ATTEMPT_PREFIX + k, now).apply()
        val fresh = runCatching(fetch).getOrDefault(emptyList())
        if(epoch!=WeatherSettings.sourceKey(appContext)) return emptyList()
        val merged = (cached + fresh).associateBy { it.type }.values.sortedBy { it.type.toIntOrNull() ?: 99 }
        if (merged.isNotEmpty()) {
            prefs.edit()
                .putString(k, Gson().toJson(merged))
                .putLong(SUCCESS_PREFIX + k, now)
                .apply()
        }
        return merged
    }

    /**
     * 供回归测试和显式的“重新获取生活建议”动作使用。三个相关时间戳必须一起清除，
     * 否则保留的失败节流会把清空后的首个请求错误地当作刚刚失败。
     */
    internal fun clear(context: Context, lat: Double, lng: Double) {
        val k = WeatherSettings.sourceKey(context)+key(lat, lng)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(k)
            .remove(ATTEMPT_PREFIX + k)
            .remove(SUCCESS_PREFIX + k)
            .commit()
    }
}
