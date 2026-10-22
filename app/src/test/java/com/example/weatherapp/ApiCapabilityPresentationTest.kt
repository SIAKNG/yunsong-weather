package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class ApiCapabilityPresentationTest {
    private fun row(state: ApiSupport,detail: String="",feature: String="小时气温")=ApiCapabilityRow("测试",feature,state,detail)
    @Test fun missingClearlyDistinguishesAppSupportFromTheCurrentApi() {
        assertEquals("暂不支持",ApiSupport.MISSING.label)
        val s=ApiCapabilityPresentation.explanation(row(ApiSupport.MISSING))
        assertTrue(s.contains("App 支持"));assertTrue(s.contains("当前 API"));assertTrue(s.contains("版本或套餐"))
        assertFalse(s.contains("永久"))
    }
    @Test fun partialHoursAndDaysKeepTheirRealRange() {
        assertEquals("提供 24 小时，完整展示需要 48 小时。",ApiCapabilityPresentation.explanation(row(ApiSupport.PARTIAL,"24/48 小时；本次有效24个小时点")))
        assertEquals("提供未来 7 日，完整展示需要 15 日。",ApiCapabilityPresentation.explanation(row(ApiSupport.PARTIAL,"本次 7/15 日\n2026-10-22—2026-10-28")))
        assertEquals("提供 48 小时，可正常展示。",ApiCapabilityPresentation.explanation(row(ApiSupport.SUPPORTED,"48/48 小时")))
    }
    @Test fun authenticationQuotaAndNetworkAreNotMislabelledAsMissingFeatures() {
        assertTrue(ApiCapabilityPresentation.explanation(row(ApiSupport.RESTRICTED,"HTTP/业务码 401")).contains("凭证"))
        assertTrue(ApiCapabilityPresentation.explanation(row(ApiSupport.RESTRICTED,"HTTP/业务码 429")).contains("频率"))
        listOf(ApiSupport.RESTRICTED,ApiSupport.FAILED,ApiSupport.UNCONFIGURED).forEach {
            assertFalse(ApiCapabilityPresentation.explanation(row(it)).contains("暂不支持"))
        }
    }
    @Test fun missingFieldNamesAndIndependentFunctionsRemainHonest() {
        assertEquals("部分数据可用；缺少：湿度、能见度",ApiCapabilityPresentation.explanation(row(ApiSupport.PARTIAL,"3/5项；有：风速；缺：湿度、能见度；备注")))
        assertTrue(ApiCapabilityPresentation.explanation(row(ApiSupport.LOCAL,feature="全国空气排名")).contains("独立"))
        assertEquals("小时污染物 · PM2.5",ApiCapabilityPresentation.feature("小时污染物 · pm25"))
    }
}
