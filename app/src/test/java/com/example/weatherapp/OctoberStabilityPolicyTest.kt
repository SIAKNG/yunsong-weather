package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class OctoberStabilityPolicyTest {
    @Test fun detailTransitionStaysInSameForegroundSession() {
        val counter = ForegroundSessionCounter()
        counter.activityStarted(); val initial = counter.session
        counter.activityStarted(); counter.activityStopped()
        counter.activityStarted(); counter.activityStopped()
        assertEquals(initial, counter.session)
    }
    @Test fun backgroundingWhileInDetailsStartsNewSessionOnReturn() {
        val counter = ForegroundSessionCounter()
        counter.activityStarted(); counter.activityStarted(); counter.activityStopped()
        val initial = counter.session
        counter.activityStopped(); counter.activityStarted()
        assertTrue(counter.session > initial)
    }
    @Test fun configurationChangeIsNotARealBackgroundEntry() {
        val counter = ForegroundSessionCounter()
        counter.activityStarted(); val initial = counter.session
        counter.activityStopped(true); counter.activityStarted()
        assertEquals(initial, counter.session)
    }
    @Test fun addressIsLimitedWithoutSplittingUnicode() {
        val value = WeatherAddressFormatter.compact("高新区非常长的街道社区地址🏡还有后续门牌文字和附加楼栋")
        assertTrue(value.codePointCount(0, value.length) <= 16)
        assertTrue(value.endsWith("…"))
        assertFalse(value.contains('\n'))
    }
    @Test fun districtAndNamedStreetArePreferredOverDoorNumber() {
        assertEquals("雁塔区 科技路", WeatherAddressFormatter.fromParts("西安市", "雁塔区", "科技路", "科技路88号3栋201室"))
        assertEquals("高新区 阳光花园", WeatherAddressFormatter.fromParts("成都", "高新区", null, "阳光花园"))
        assertEquals("高新区", WeatherAddressFormatter.fromParts("成都", "高新区", null, "123号3栋"))
    }
    @Test fun repeatedDistrictIsNotDuplicatedAndCityFallbackIsUsable() {
        assertEquals("高新区科技路", WeatherAddressFormatter.fromParts("西安", "高新区", "高新区科技路", null))
        assertEquals("西安", WeatherAddressFormatter.fromParts("西安", null, null, null))
        assertEquals("当前位置", WeatherAddressFormatter.compact("  "))
    }
    @Test fun savedIdentityIsNotModifiedByDisplayShortening() {
        val page = CityPage("测试市", 1.0, 2.0, true, "很长的真实地址与更多小区门牌信息用于模拟测试")
        val original = page.copy()
        assertEquals(WeatherAddressFormatter.label(page), WeatherAddressFormatter.compact(page.locationDetail))
        assertEquals(original, page)
    }
    @Test fun staleObservationCannotGainFreshnessFromARecentWrite() {
        val now = 1_000_000_000L
        assertFalse(WeatherCachePolicy.volatileUsable(WeatherCachePolicy.observationAge(now, 0, now - 3 * 60 * 60_000)))
        assertTrue(WeatherCachePolicy.volatileUsable(WeatherCachePolicy.observationAge(now, 1000, now - 2000)))
        assertFalse(WeatherCachePolicy.volatileUsable(WeatherCachePolicy.observationAge(now, 0, now + 60 * 60_000)))
    }
    @Test fun networkResponseCannotRegressOrBringBackOldWeather() {
        val now = 1_000_000_000L
        assertTrue(WeatherRefreshPolicy.canCommitObservation(now, now - 60_000, now - 120_000))
        assertFalse(WeatherRefreshPolicy.canCommitObservation(now, now - 120_000, now - 60_000))
        assertFalse(WeatherRefreshPolicy.canCommitObservation(now, now - 3 * 60 * 60_000, 0))
        assertFalse(WeatherRefreshPolicy.canCommitObservation(now, now + 3600_000, 0))
        assertTrue(WeatherRefreshPolicy.canCommitObservation(now, null, 0))
        assertTrue(WeatherRefreshPolicy.canCommitObservation(now, now, now + 2 * 24 * 3600_000L))
    }
    @Test fun fullGuideDoesNotContainApiSetupAndUserGuideDoes() {
        assertEquals(3, FirstRunGuide.steps(false).size)
        assertFalse(FirstRunGuide.steps(false).any { it.body.contains("Token") || it.body.contains("JWT") })
        assertEquals(5, FirstRunGuide.steps(true).size)
        assertTrue(FirstRunGuide.steps(true).any { it.body.contains("API Host") && it.body.contains("API Key") })
    }
}
