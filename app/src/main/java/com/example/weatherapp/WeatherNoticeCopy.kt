package com.example.weatherapp

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Copy and advice are independent of thresholds, schedules and notification identity. */
internal object WeatherNoticeCopy {
    fun daily(
        temperatureRange: String, low: Double, high: Double, sky: String?,
        aqi: Double?, probability: Double?, wind: Double?, gust: Double?, warning: String?
    ): String {
        val phenomenon = sky?.takeIf { it.isNotBlank() }?.let(WeatherText::skyconToText)
        val weather = temperatureRange + (phenomenon?.let { "，$it" } ?: "") + "。"
        val air = aqi?.takeIf { it.isFinite() && it >= 0 }?.let {
            when (val level = AirQualityScale.level(it)) {
                "优" -> "空气质量总体为优。"
                "良" -> "整体空气质量不错。"
                else -> "空气质量整体预计为$level。"
            }
        }
        val rainProbability = HourlyForecastDetailPolicy.percent(probability)
        val snow = sky?.let { "SNOW" in it || "SLEET" in it } == true
        val rain = when {
            snow -> "出门可以带伞，或者穿件能挡雨雪的外套。"
            rainProbability == null -> null
            rainProbability == 0 -> "出门一般不用特意带伞。"
            rainProbability >= 50 -> "下雨的可能性较大（约$rainProbability%），出门记得带伞。"
            else -> "有小概率下雨（约$rainProbability%），出门久的话，可以备把伞。"
        }
        val breeze = when {
            gust != null && gust >= WindReminderPolicy.THRESHOLD_KMH -> "可能突然起阵风，出门前可以看一眼风势。"
            wind == null -> null
            wind >= WindReminderPolicy.THRESHOLD_KMH -> "风会比较大，骑车或经过开阔路段时可以慢一点。"
            wind < 4 -> "几乎没什么风。"
            wind <= 15 -> "风比较温和。"
            else -> "出门会感觉到风，怕凉可以带件挡风外套。"
        }
        val tip = when {
            high - low >= 10 -> "早晚和白天温差明显，带件方便穿脱的外套，暖了就能减一层。"
            snow -> "雨雪后地面可能湿滑，路上多留几分钟。"
            high >= 34 -> "白天偏热，出门可以带瓶水。"
            low <= 5 -> "早出门的话，可以穿暖一点。"
            else -> null
        }
        return listOf(weather, listOfNotNull(air, rain).joinToString(""), listOfNotNull(breeze, warning, tip).joinToString(""))
            .filter(String::isNotBlank).joinToString("\n")
    }

    fun warningArea(sender: String, queriedCity: String): String {
        val issuer = sender.substringBefore("气象").trim()
        return issuer.takeIf { it.endsWith("市") || it.endsWith("区") || it.endsWith("县") || it.endsWith("旗") }
            ?: WeatherAddressFormatter.city(queriedCity) + "（预警覆盖）"
    }

    fun warningTitle(area: String?, title: String, upgraded: Boolean): String =
        (area?.takeIf(String::isNotBlank)?.let { "$it｜" } ?: "") + title +
            (if (upgraded) " · 已升级" else "")

    fun warningSummary(area: String?, title: String): String = when {
        area.isNullOrBlank() -> "$title，请留意官方指引。"
        area.endsWith("（预警覆盖）") -> "${area.removeSuffix("（预警覆盖）")}有$title，请留意官方指引。"
        else -> "${area}已发布$title，请留意官方指引。"
    }

    fun warningBody(area: String?, title: String, officialText: String, urgent: Boolean): String {
        val prefix = when {
            area.isNullOrBlank() -> ""
            area.endsWith("（预警覆盖）") -> "这条${title}覆盖${area.removeSuffix("（预警覆盖）")}。"
            officialText.startsWith(area) -> ""
            else -> "${area}已发布$title。"
        }
        val urgency = if (urgent) "立即" else "及时"
        val scope = area?.takeUnless { it.isBlank() || it.endsWith("（预警覆盖）") } ?: "预警覆盖地区"
        return prefix + officialText.replace("当地", scope).ifBlank { "请${urgency}查看气象部门发布的影响范围和防御指引。" }
    }

    fun rain(event: WeatherRainEvent, delivery: RainDelivery, now: Long): Pair<String, String> {
        val location = event.locationName
        val forecast = event.forecast
        if (delivery == RainDelivery.ENDING) {
            val end = forecast?.endingAt(now)
            return if (end == null) "$location，降雨已结束" to "接下来一小段时间，雨看起来不会再下。地面可能还湿，走路时留意脚下。"
            else "$location，雨即将结束" to "预计约${minutes(end, now)}分钟后停雨。行程不急的话，可以等雨小些再出门。"
        }
        if (forecast == null) return event.notificationTitle to event.notificationText
        val nowWet = forecast.rankAt(now) >= 0
        val wet = forecast.wetIndexes(now)
        val category = event.category
        val start = minutes(event.firstRainAt, now)
        val escalating = delivery == RainDelivery.ESCALATED
        val title = when {
            escalating -> "$location｜雨势可能增强"
            nowWet -> "$location｜外面正下雨"
            delivery == RainDelivery.UPDATED -> "$location｜下雨时间有变化"
            else -> "$location｜雨快到了"
        }
        val startText = when {
            escalating -> "接下来雨可能下得更急，最强可到$category。"
            nowWet -> "这会儿外面正在下雨，预计这一阵最强可到$category。"
            else -> "预计约${start}分钟后开始下雨，这一阵最强可到$category。"
        }
        val pauses = wet.zipWithNext().any { (first, second) -> second > first + 1 }
        val tip = when {
            pauses -> "这阵雨可能停一会儿又下，可以先把伞留在包里。"
            event.categoryRank >= 2 -> "行程可以调整的话，不妨等雨小一点再出门。"
            nowWet -> "出门带把伞，经过湿滑的台阶时慢一点。"
            else -> "一会儿要出门的话，可以先把伞放进包里。"
        }
        return title to (startText + tip)
    }

    private fun minutes(time: Long, now: Long) = ((time - now + 59_999L) / 60_000L).coerceAtLeast(1)

    fun temperature(city: String, highDelta: Int?, lowDelta: Int?): Pair<String, String> {
        val high = highDelta?.takeIf { abs(it) >= 5 }
        val low = lowDelta?.takeIf { abs(it) >= 5 }
        val mixed = high != null && low != null && (high > 0) != (low > 0)
        val title = when {
            mixed -> "明天白天和早晚的温度变化不同"
            high != null && low != null -> if (high < 0) "明天整体会更冷" else "明天整体会回暖"
            high != null -> if (high < 0) "明天白天明显降温" else "明天白天暖一些"
            else -> if ((low ?: 0) < 0) "明天早晚更冷些" else "明天早晚会暖一些"
        }
        fun change(name: String, delta: Int) = "${name}比今天${if (delta > 0) "高" else "低"}${abs(delta)}℃"
        val readings = listOfNotNull(high?.let { change("最高温", it) }, low?.let { change("最低温", it) }).joinToString("，")
        val tip = when {
            mixed -> "衣服可以分层穿，早晚加一层，白天暖了再减。"
            (high ?: 0) < 0 || (low ?: 0) < 0 -> "早出门或晚回家的话，可以多带件外套。"
            else -> "带件容易穿脱的外套，暖和了就能减一层。"
        }
        return "$city｜$title" to ("明天$readings。$tip")
    }

    fun temperatureAtDelivery(title: String, body: String, forecastAt: Long, now: Long): Pair<String, String> {
        val zone = ZoneId.systemDefault()
        fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        if (day(forecastAt) != day(now)) return title to body
        return title.replace("明日", "今日").replace("明天", "今天") to
            body.replace("比今天", "比昨天").replace("较今天", "较昨天").replace("明天", "今天")
    }
}
