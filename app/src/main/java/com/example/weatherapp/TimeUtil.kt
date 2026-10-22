package com.example.weatherapp

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// 解析形如 "2026-08-12T19:00+08:00"、"2026-08-12T19:00:00+08:00"、"2026-08-12T19:00Z" 的时间字符串。
// 手工切分并换算时区偏移，不依赖 SimpleDateFormat 的 "XXX" 格式，
// 避免部分 Android 设备解析失败导致时间回退成 0:00 起点、历史列/高亮丢失。
object TimeUtil {

    fun parse(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val m = Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}(?::\d{2})?)(Z|[+-]\d{2}:?\d{2})$""").find(s)
        if (m != null) {
            val (datePart, timePart, offset) = m.destructured
            val withSec = if (timePart.length == 5) "$timePart:00" else timePart
            val base = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    .parse("${datePart}T$withSec")?.time
            } catch (e: Exception) {
                null
            } ?: return null
            val offMillis = when {
                offset == "Z" -> 0L
                else -> {
                    val sign = if (offset.startsWith("-")) -1L else 1L
                    val digits = offset.removePrefix("+").removePrefix("-").replace(":", "")
                    val hh = digits.take(2).toLongOrNull() ?: 0L
                    val mm = digits.drop(2).take(2).toLongOrNull() ?: 0L
                    sign * (hh * 3600000L + mm * 60000L)
                }
            }
            // SimpleDateFormat 按设备时区解释：localWall = base + 设备偏移；
            // 目标时刻 = localWall - 目标时区偏移
            return base + TimeZone.getDefault().getOffset(base) - offMillis
        }
        // 退化：不带时区的字符串按设备本地时间解析
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(s)?.time
        } catch (e: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(s)?.time
            } catch (e2: Exception) {
                null
            }
        }
    }
}
