package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class MandatoryGuidePolicyTest {
    @Test fun cannotAdvanceWithoutCurrentInteraction() {
        val p = MandatoryGuideProgress(3)
        assertFalse(p.canAdvance); assertFalse(p.next()); assertEquals(0,p.index)
        p.completeCurrent(); assertTrue(p.next()); assertEquals(1,p.index)
        assertFalse(p.canAdvance); assertFalse(p.allComplete)
    }
    @Test fun allStepsMustBeCompletedBeforeFinish() {
        val p = MandatoryGuideProgress(5)
        repeat(4) { p.completeCurrent(); assertTrue(p.next()); assertFalse(p.allComplete) }
        p.completeCurrent(); assertTrue(p.allComplete); assertFalse(p.next())
        p.previous(); assertTrue(p.canAdvance)
    }
    @Test fun recreationRestoresOnlyValidProgress() {
        val original = MandatoryGuideProgress(3); original.completeCurrent(); original.next()
        val restored = MandatoryGuideProgress(3,original.index,original.mask)
        assertEquals(1,restored.index); assertFalse(restored.canAdvance)
        restored.previous(); assertTrue(restored.canAdvance)
        assertEquals(2, MandatoryGuideProgress(3,99).index)
        assertEquals(0,MandatoryGuideProgress(3,-5).index)
        assertEquals(7,MandatoryGuideProgress(3,0,255).mask)
    }
    @Test fun missingApiNeverBecomesNetworkErrorOrSuccess() {
        listOf(true,false).forEach { assertEquals(WeatherAccessFeedback.MISSING_API, WeatherAccessFeedback.refreshResult(true,it)) }
        assertEquals("刷新成功",WeatherAccessFeedback.refreshResult(false,true))
        assertTrue(WeatherAccessFeedback.refreshResult(false,false).contains("网络"))
        assertFalse(WeatherAccessFeedback.refreshResult(false,false).contains("未配置"))
    }
    @Test fun credentialCompletenessUsesSelectedSource() {
        assertFalse(UserApiConfiguration.Config().hasPrimary())
        assertTrue(UserApiConfiguration.Config(caiyunToken="fixture").hasPrimary())
        assertFalse(UserApiConfiguration.Config(source=WeatherSettings.SOURCE_QWEATHER,caiyunToken="fixture").hasPrimary())
        assertTrue(UserApiConfiguration.Config(source=WeatherSettings.SOURCE_QWEATHER,qweatherHost="demo.qweatherapi.com",qweatherApiKey="fixture").hasPrimary())
    }
}
