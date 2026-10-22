package com.example.weatherapp

/**
 * 农业专项预警不在面向普通天气用户的预警入口中展示。
 * 调用方只能传预警名称、标题和发布单位等身份字段，不能传正文或防御指南；
 * 常规暴雨的防御指南经常出现“农田排水”，正文命中不能作为隐藏依据。
 */
object AlertVisibilityPolicy {
    private val agriculturalTerms = listOf(
        "农业", "农作物", "农田", "农事", "农牧", "农情", "墒情",
        "病虫害", "土壤", "牧业", "渔业", "林业"
    )

    fun isAgriculturalAlert(vararg texts: String?): Boolean = texts.any { text ->
        !text.isNullOrBlank() && agriculturalTerms.any(text::contains)
    }
}
