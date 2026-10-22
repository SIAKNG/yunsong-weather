package com.example.weatherapp

import java.util.Locale

/**
 * 预警的统一展示与身份规则。
 *
 * 上游的标题、severity 写法并不稳定（和风会给出 minor/moderate，彩云也可能
 * 给出长标题），因此通知和页面都必须经由这里生成面向用户的中文名称。
 */
object WeatherAlertPresentation {
    fun title(alert: AlertContent): String {
        val type = typeFromCaiyunCode(alert.code)
            ?: normalizedType(alert.type ?: alert.subtype ?: alert.subType ?: alert.title)
        val level = levelName(alert.code, alert.level, alert.title)
        return title(type, level)
    }

    fun title(alert: QAlert): String = title(
        normalizedType(alert.eventTypeName.ifBlank { alert.headline }),
        levelName(alert)
    )

    fun levelName(code: String?, level: String?, title: String?): String {
        when (code?.takeLast(2)) {
            "00" -> return "白色"
            "01" -> return "蓝色"
            "02" -> return "黄色"
            "03" -> return "橙色"
            "04" -> return "红色"
        }
        val normalized = normalizeRawLevel(level)
        if (normalized.isNotBlank()) return normalized
        val rawTitle = title.orEmpty()
        return when {
            rawTitle.contains("红色") -> "红色"
            rawTitle.contains("橙色") -> "橙色"
            rawTitle.contains("黄色") -> "黄色"
            rawTitle.contains("蓝色") -> "蓝色"
            rawTitle.contains("白色") -> "白色"
            else -> ""
        }
    }

    fun levelName(alert: QAlert): String = when (alert.colorCode.lowercase(Locale.US)) {
        "blue" -> "蓝色"
        "yellow" -> "黄色"
        "orange" -> "橙色"
        "red" -> "红色"
        "white" -> "白色"
        else -> normalizeRawLevel(alert.severity)
    }

    fun severityRank(level: String): Int = when (level) {
        "白色" -> 0
        "蓝色" -> 1
        "黄色" -> 2
        "橙色" -> 3
        "红色" -> 4
        else -> 0
    }

    fun normalizedType(value: String?): String {
        val raw = value.orEmpty()
            .replace(Regex("^.*?发布"), "")
            .replace(Regex("事件$"), "")
            .replace(Regex("[【\\[（(].*?[】\\]）)]"), "")
            .replace(Regex("(?i)\\b(minor|moderate|severe|extreme)\\b"), "")
            .replace("预警", "")
            .replace(Regex("(红色|橙色|黄色|蓝色|白色|较重|严重|特别严重)"), "")
            .trim()
        return raw.ifBlank { "天气" }
    }

    /** 仅用于通知正文，防止供应商的英文等级原样泄漏到系统通知栏。 */
    fun cleanDetail(value: String?): String = value.orEmpty()
        .replace(Regex("(?i)\\bminor\\b"), "蓝色")
        .replace(Regex("(?i)\\bmoderate\\b"), "黄色")
        .replace(Regex("(?i)\\bsevere\\b"), "橙色")
        .replace(Regex("(?i)\\bextreme\\b"), "红色")
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun title(type: String, level: String): String = "${type}${level}预警"

    private fun normalizeRawLevel(level: String?): String = when (level.orEmpty().trim().lowercase(Locale.US)) {
        "minor" -> "蓝色"
        "moderate" -> "黄色"
        "severe" -> "橙色"
        "extreme" -> "红色"
        "blue", "蓝色" -> "蓝色"
        "yellow", "黄色" -> "黄色"
        "orange", "橙色" -> "橙色"
        "red", "红色" -> "红色"
        "white", "白色" -> "白色"
        else -> ""
    }

    private fun typeFromCaiyunCode(code: String?): String? = when (code?.take(2)) {
        "01" -> "台风"
        "02" -> "暴雨"
        "03" -> "暴雪"
        "04" -> "寒潮"
        "05" -> "大风"
        "06" -> "沙尘暴"
        "07" -> "高温"
        "08" -> "干旱"
        "09" -> "雷电"
        "10" -> "冰雹"
        "11" -> "霜冻"
        "12" -> "大雾"
        "13" -> "霾"
        "14" -> "道路结冰"
        "15" -> "森林火险"
        "16" -> "雷雨大风"
        "17" -> "春季沙尘"
        "18" -> "沙尘"
        else -> null
    }
}
