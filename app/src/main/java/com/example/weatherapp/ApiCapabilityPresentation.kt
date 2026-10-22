package com.example.weatherapp

/** Friendly wording is separate from protocol evidence; missing never means permanent denial. */
internal object ApiCapabilityPresentation {
    fun feature(value: String): String = value.replace(" / 48小时页","")
        .replace("（App 请求15日）","").replace("（当前请求7日）","")
        .replace("（目标24小时）","").replace("pm25","PM2.5").replace("pm10","PM10")
    fun explanation(row: ApiCapabilityRow): String {
        val hours=Regex("^(\\d+)/(\\d+) 小时").find(row.detail)
        val days=Regex("^本次 (\\d+)/(\\d+) 日").find(row.detail)
        return when(row.support) {
            ApiSupport.MISSING -> "App 支持；当前 API 暂未提供，请检查版本或套餐权限。"
            ApiSupport.UNCONFIGURED -> "尚未填写此接口，填写后可检测。"
            ApiSupport.RESTRICTED -> when {
                "401" in row.detail -> "凭证未通过验证，请核对 Key、Token 或 Host。"
                "429" in row.detail -> "调用次数或频率受限，请稍后再试。"
                "Host" in row.detail -> "请求受限，请检查 Host、额度或访问设置。"
                else -> "请求受限，请检查账号权限或剩余额度。"
            }
            ApiSupport.FAILED -> if("上限" in row.detail || "超时" in row.detail) "本次未检测完成，请稍后重试。" else "本次检测失败，暂不能判断功能是否可用。"
            ApiSupport.LOCAL -> when {
                "全国" in row.feature -> "由独立排名数据提供，不依赖此 API 的权限。"
                "历史" in row.feature -> "当前使用缓存或补充数据，不属于本次 API 权限检测。"
                else -> "由 App 本地计算，不需要单独的 API 权限。"
            }
            ApiSupport.SUPPORTED,ApiSupport.PARTIAL -> when {
                hours!=null -> "提供 ${hours.groupValues[1]} 小时"+if(row.support==ApiSupport.PARTIAL) "，完整展示需要 ${hours.groupValues[2]} 小时。" else "，可正常展示。"
                days!=null -> "提供未来 ${days.groupValues[1]} 日"+if(row.support==ApiSupport.PARTIAL) "，完整展示需要 ${days.groupValues[2]} 日。" else "，可正常展示。"
                row.detail.startsWith("本次 ") -> row.detail.substringBefore('；').substringBefore('\n')
                "缺：" in row.detail -> "部分数据可用；缺少："+row.detail.substringAfter("缺：").substringBefore('；')
                row.support==ApiSupport.PARTIAL -> "只提供部分数据，部分内容暂无法展示。"
                else -> "当前 API 可提供此功能的数据。"
            }
        }
    }
}
