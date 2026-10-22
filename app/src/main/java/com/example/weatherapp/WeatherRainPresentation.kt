package com.example.weatherapp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分钟级降雨在卡片和通知中共用的文案规则。
 *
 * 这样后台提醒不会再出现与页面卡片不一致的另一套“泛化提示”。
 */
object WeatherRainPresentation {
    const val RAIN_THRESHOLD_MM_PER_HOUR = 0.05

    fun summary(
        values: List<Double>,
        times: List<Long>,
        peakCategory: String,
        now: Long = System.currentTimeMillis()
    ): String {
        if (values.isEmpty() || times.isEmpty()) return ""
        val count = minOf(values.size, times.size)
        val usableValues = values.take(count)
        val usableTimes = times.take(count)
        val rainIndexes = usableValues.indices.filter { usableValues[it] > RAIN_THRESHOLD_MM_PER_HOUR }
        if (rainIndexes.isEmpty()) return ""

        val firstIndex = rainIndexes.first()
        val lastIndex = rainIndexes.last()
        val peakIndex = rainIndexes.maxByOrNull { usableValues[it] } ?: firstIndex
        val firstMinute = minutesUntil(usableTimes[firstIndex], now).toInt()
        val peakMinute = minutesUntil(usableTimes[peakIndex], now).toInt()
        val currentValue = usableValues.firstOrNull() ?: 0.0
        val currentCategory = category(currentValue)
        val rainingNow = currentValue > RAIN_THRESHOLD_MM_PER_HOUR
        val firstCategory = category(usableValues[firstIndex])
        val pauseIndex = (firstIndex until lastIndex).firstOrNull { index ->
            usableValues[index] > RAIN_THRESHOLD_MM_PER_HOUR &&
                usableValues[index + 1] <= RAIN_THRESHOLD_MM_PER_HOUR &&
                usableValues.subList(index + 1, lastIndex + 1).any { it > RAIN_THRESHOLD_MM_PER_HOUR }
        }
        val resumeIndex = pauseIndex?.let { pause ->
            (pause + 1..lastIndex).firstOrNull { usableValues[it] > RAIN_THRESHOLD_MM_PER_HOUR }
        }
        val endsWithNoRain = lastIndex < usableValues.lastIndex &&
            usableValues.drop(lastIndex + 1).any { it <= RAIN_THRESHOLD_MM_PER_HOUR }

        val change = when {
            !rainingNow -> {
                val start = "${aboutMinutes(firstMinute)}$firstCategory"
                if (peakCategory == firstCategory) {
                    start
                } else {
                    val changeMinutes = (peakMinute - firstMinute).coerceAtLeast(0)
                    "$start，${aboutMinutes(peakMinute)}${changeSpeed(changeMinutes)}增强至$peakCategory"
                }
            }
            peakCategory != currentCategory ->
                "${aboutMinutes(peakMinute)}${changeSpeed(peakMinute)}增强至$peakCategory"
            else -> "雨势基本平稳"
        }
        val ending = when {
            pauseIndex != null -> {
                val pauseMinute = minutesUntil(usableTimes[pauseIndex + 1], now).toInt()
                val resumeCategory = resumeIndex?.let { category(usableValues[it]) } ?: firstCategory
                "，${aboutMinutes(pauseMinute)}停，随后$resumeCategory"
            }
            endsWithNoRain -> "，随后停"
            else -> ""
        }
        return "当前${if (rainingNow) currentCategory else "无雨"}，$change$ending"
    }

    fun peakText(peakMillis: Long, peakValue: Double, peakCategory: String): String {
        val clock = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(peakMillis))
        return "约$clock 达到${peakCategory}级别，${travelAdvice(peakValue)}"
    }

    /** 供通知标题使用：例如“南京市，未来20分钟会有降雨”。 */
    fun notificationTitle(locationName: String, firstRainAt: Long, now: Long = System.currentTimeMillis()): String {
        val location = locationName.ifBlank { "当前位置" }
        val minutes = minutesUntil(firstRainAt, now)
        return if (minutes == 0L) "$location，现在会有降雨" else "$location，未来${minutes}分钟会有降雨"
    }

    /** 将降雨卡片上、下两行原样合并为通知展开后的正文。 */
    fun notificationText(summary: String, peak: String): String =
        listOf(summary.trim(), peak.trim()).filter { it.isNotEmpty() }.joinToString("\n")

    fun category(mmPerHour: Double): String = when {
        mmPerHour < 0.1 -> "微量降雨"
        mmPerHour < 2.5 -> "小雨"
        mmPerHour < 8.0 -> "中雨"
        mmPerHour < 16.0 -> "大雨"
        mmPerHour < 32.0 -> "暴雨"
        else -> "强降雨"
    }

    fun categoryRank(category: String): Int = when (category) {
        "微量降雨" -> 0
        "小雨" -> 1
        "中雨" -> 2
        "大雨" -> 3
        "暴雨" -> 4
        else -> 5
    }

    private fun minutesUntil(time: Long, now: Long): Long =
        ((time - now) / 60_000L).coerceAtLeast(0L)

    private fun travelAdvice(peakValue: Double): String = when {
        peakValue >= 16.0 -> "建议延后出行，避开积水"
        peakValue >= 8.0 -> "建议避开低洼路段"
        peakValue >= 2.5 -> "建议带伞慢行"
        else -> "建议带伞防滑"
    }

    private fun aboutMinutes(minutes: Int): String =
        if (minutes <= 0) "即将" else "约${minutes}分后"

    private fun changeSpeed(minutes: Int): String = when {
        minutes <= 15 -> "迅速"
        minutes <= 45 -> "逐渐"
        else -> "缓慢"
    }
}

/** 当前定位的详细地址首段由 Geocoder 保存为区/县，降雨标题优先使用该层级。 */
internal fun CityPage.preciseRainNotificationLocation(): String {
    if (!isCurrentLocation) return name.ifBlank { "当前位置" }
    val district = locationDetail.trim().substringBefore(' ').trim()
    return district.ifBlank { name.ifBlank { "当前位置" } }
}
