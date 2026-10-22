package com.example.weatherapp

/** 首页固定九宫格；只改展示简称，不改和风等级和原始建议。 */
object HomeLifeAdvice {
    val categories = listOf("q1" to "运动", "q3" to "穿衣", "q5" to "防晒",
        "q15" to "交通", "q4" to "钓鱼", "q6" to "旅游",
        "q9" to "感冒", "q8" to "舒适度", "q7" to "过敏")

    fun select(items: List<LifeAdviceItem>): List<LifeAdviceItem> {
        val byKey = items.associateBy { it.key }
        return categories.map { (key, title) ->
            byKey[key]?.copy(title = title) ?: LifeAdviceItem(key, title, "暂无数据", "")
        }
    }
}
