package com.example.weatherapp

import org.junit.Test

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Refresh policy regression tests. These run on the local JVM without a device.
 */
class WeatherRefreshPolicyTest {
    @Test
    fun firstEntryAlwaysRefreshes() {
        assertTrue(WeatherRefreshPolicy.shouldStartEntryRefresh(10_000L, 0L))
    }

    @Test
    fun repeatedEntryInsideDebounceWindowIsSkipped() {
        val last = 10_000L
        assertFalse(
            WeatherRefreshPolicy.shouldStartEntryRefresh(
                last + WeatherRefreshPolicy.ENTRY_DEBOUNCE_MS - 1,
                last
            )
        )
    }

    @Test
    fun repeatedEntryAtDebounceBoundaryRefreshes() {
        val last = 10_000L
        assertTrue(
            WeatherRefreshPolicy.shouldStartEntryRefresh(
                last + WeatherRefreshPolicy.ENTRY_DEBOUNCE_MS,
                last
            )
        )
    }

    @Test
    fun firstVisitToAnotherCityRefreshesImmediatelyThenUsesRateLimit() {
        val now = 100_000L
        assertTrue(WeatherRefreshPolicy.shouldRefreshPageCity(now, 0L))
        assertFalse(
            WeatherRefreshPolicy.shouldRefreshPageCity(
                now + WeatherRefreshPolicy.PAGE_CITY_REFRESH_MS - 1,
                now
            )
        )
        assertTrue(
            WeatherRefreshPolicy.shouldRefreshPageCity(
                now + WeatherRefreshPolicy.PAGE_CITY_REFRESH_MS,
                now
            )
        )
    }

    @Test
    fun explicitAgriculturalAlertsAreHidden() {
        assertTrue(AlertVisibilityPolicy.isAgriculturalAlert("农业气象灾害预警", "农作物病虫害风险"))
    }

    @Test
    fun generalWeatherAlertsRemainVisible() {
        assertFalse(AlertVisibilityPolicy.isAgriculturalAlert("霜冻橙色预警", "请注意道路结冰"))
    }

    @Test
    fun rainstormIsNotAgriculturalJustBecauseInstructionsMentionFarmland() {
        assertFalse(AlertVisibilityPolicy.isAgriculturalAlert("暴雨黄色预警", "西安市气象台"))
        assertTrue(AlertVisibilityPolicy.isAgriculturalAlert("农业气象灾害预警", "西安市农业部门"))
    }

    @Test
    fun qweatherSeverityIsAlwaysShownAsChineseWarningLevel() {
        val alert = QAlert(
            id = "test", senderName = "测试市气象台", issuedTimeMillis = 1L, expireTimeMillis = 2L,
            messageType = "", eventTypeName = "暴雨事件", eventTypeCode = "", severity = "moderate",
            colorCode = "", headline = "", description = "moderate rain warning", instruction = ""
        )
        assertEquals("黄色", WeatherAlertPresentation.levelName(alert))
        assertEquals("暴雨黄色预警", WeatherAlertPresentation.title(alert))
        assertFalse(WeatherAlertPresentation.cleanDetail(alert.description).contains("moderate", ignoreCase = true))
    }

    @Test
    fun rainNotificationUsesLocationRelativeTimeAndBothCardLines() {
        val now = 1_000_000L
        assertEquals(
            "南京市，未来20分钟会有降雨",
            WeatherRainPresentation.notificationTitle("南京市", now + 20L * 60_000L, now)
        )
        assertEquals(
            "当前无雨，约20分后小雨\n约08:20 达到小雨级别，建议带伞防滑",
            WeatherRainPresentation.notificationText(
                "当前无雨，约20分后小雨",
                "约08:20 达到小雨级别，建议带伞防滑"
            )
        )
    }

    @Test
    fun crossSourceAlertIdentityKeepsOneEventButRetainsDifferentRegions() {
        val city = CityPage("南京市", 118.7969, 32.0603, true)
        val issued = 200L * 60L * 60L * 1_000L
        val caiyun = WeatherAlertIdentity.semanticKey(city, "暴雨", "南京市", issued)
        val qweather = WeatherAlertIdentity.semanticKey(city, "暴雨", "南京市气象台", issued + 45L * 60_000L)
        val otherRegion = WeatherAlertIdentity.semanticKey(city, "暴雨", "苏州市气象台", issued)
        assertEquals(caiyun, qweather)
        assertFalse(caiyun == otherRegion)
    }

    @Test
    fun sunCardUsesSunriseToSunsetIntervalDuringDaytime() {
        val sunrise = 6L * HOUR
        val sunset = 18L * HOUR
        val state = SunCardStateCalculator.calculate(
            sunrise, sunset, 0L, 30L * HOUR, 12L * HOUR
        )
        assertNotNull(state)
        assertTrue(state!!.isDaytime)
        assertTrue(state.leftIsSunrise)
        assertFalse(state.rightIsSunrise)
        assertEquals(sunrise, state.leftTimeMillis)
        assertEquals(sunset, state.rightTimeMillis)
    }

    @Test
    fun sunCardUsesSunsetToNextSunriseIntervalAtNight() {
        val sunrise = 6L * HOUR
        val sunset = 18L * HOUR
        val state = SunCardStateCalculator.calculate(
            sunrise, sunset, -6L * HOUR, 30L * HOUR, 21L * HOUR
        )
        assertNotNull(state)
        assertFalse(state!!.isDaytime)
        assertFalse(state.leftIsSunrise)
        assertTrue(state.rightIsSunrise)
        assertEquals(sunset, state.leftTimeMillis)
        assertEquals(30L * HOUR, state.rightTimeMillis)
    }

    @Test
    fun statusBarChoosesTheHigherContrastColor() {
        assertTrue(StatusBarContrastPolicy.usesLightIcons(0xff000000L.toInt()))
        assertFalse(StatusBarContrastPolicy.usesLightIcons(0xffffffffL.toInt()))
    }

    @Test
    fun hourlyCardAlignmentAddsOnlyTheMeasuredAvailableSpace() {
        assertEquals(120, HourlyCardAlignmentPolicy.targetSpacerHeight(80, 40))
        assertEquals(80, HourlyCardAlignmentPolicy.targetSpacerHeight(80, 0))
        assertEquals(null, HourlyCardAlignmentPolicy.targetSpacerHeight(20, -30))
    }

    @Test
    fun previewTitleUsesTheFormerTopMaskRevealThreshold() {
        assertEquals(0f, TopChromeScrollPolicy.revealProgress(20, 160, 120, 64))
        assertEquals(0.5f, TopChromeScrollPolicy.revealProgress(72, 160, 120, 64))
        assertEquals(1f, TopChromeScrollPolicy.revealProgress(200, 160, 120, 64))
    }

    @Test
    fun unitConverterUsesOneSharedBaseForAllDisplayUnits() {
        assertEquals(68.0, WeatherUnitConverter.temperature(20.0, WeatherSettings.TEMP_F), 0.001)
        assertEquals(10.0, WeatherUnitConverter.wind(36.0, WeatherSettings.WIND_MS), 0.001)
        assertEquals(62.1371, WeatherUnitConverter.wind(100.0, WeatherSettings.WIND_MPH), 0.001)
        assertEquals(760.0, WeatherUnitConverter.pressure(1013.25, WeatherSettings.PRESSURE_MMHG), 0.2)
        assertEquals(1000.0, WeatherUnitConverter.visibility(1.0, WeatherSettings.VIS_M), 0.001)
        assertEquals(0.621371, WeatherUnitConverter.visibility(1.0, WeatherSettings.VIS_MI), 0.000001)
    }

    @Test
    fun liveAqiSnapshotWinsWhileMissingPollutantsKeepTrendValue() {
        val trend = listOf(
            AirQualityPoint(1_000L, aqi = 42.0, pm25 = 20.0, o3 = 99.0),
            AirQualityPoint(2_000L, aqi = 43.0, pm25 = 21.0, o3 = 100.0)
        )
        val merged = AirQualitySnapshotPolicy.mergeCurrentIntoTrend(
            trend, AirQualityPoint(1_050L, aqi = 58.0, pm25 = 36.0), 1_100L
        )
        assertEquals(58.0, merged[0].aqi!!, 0.0)
        assertEquals(36.0, merged[0].pm25!!, 0.0)
        assertEquals(99.0, merged[0].o3!!, 0.0)
    }

    @Test
    fun airQualityTimelineKeepsOneProviderForRealtimeAndHourlyPoints() {
        val realtime = AirQualityPoint(
            timeMillis = 1_010L, aqi = 55.0, source = AirQualitySource.CAIYUN,
            updatedAtMillis = 1_010L
        )
        val timeline = AirQualityTimeline(
            AirQualitySource.CAIYUN, 1_010L, realtime,
            listOf(AirQualityPoint(1_000L, aqi = 42.0, source = AirQualitySource.CAIYUN))
        )
        assertEquals(AirQualitySource.CAIYUN, timeline.current(1_020L)?.source)
        assertTrue(timeline.pointsWithRealtime(1_020L).all { it.source == AirQualitySource.CAIYUN })
    }

    @Test
    fun groupedCacheExpiresVolatileDataBeforeDailyPayload() {
        val age = WeatherCachePolicy.VOLATILE_MS + 1L
        assertFalse(WeatherCachePolicy.volatileUsable(age))
        assertTrue(WeatherCachePolicy.hourlyUsable(age))
        assertTrue(WeatherCachePolicy.dailyUsable(age))
        assertFalse(WeatherCachePolicy.payloadUsable(WeatherCachePolicy.MAX_PAYLOAD_MS + 1L))
    }

    @Test
    fun detailNavigationCyclesInTheSpecifiedOrder() {
        assertEquals(DetailPage.AIR_QUALITY, DetailNavigator.step(DetailPage.SUN, 1))
        assertEquals(DetailPage.SUN, DetailNavigator.step(DetailPage.AIR_QUALITY, -1))
        assertEquals(DetailPage.WIND, DetailNavigator.step(DetailPage.FEELS_LIKE, 1))
    }

    @Test
    fun meeRankUsesOnlyTheParsedCurrentPublicationRows() {
        val rows = (1..100).joinToString(separator = "") { index ->
            "<tr onclick=\"openWin2(&#39;城市${index}市&#39;,&#39;2026年09月01日 10时&#39;)\"><td>城市${index}市</td><td><i class=\"ii\">${index}</i></td></tr>"
        }
        val result = MeeAirQualityRankRepository.parse("2026年09月01日 10时$rows", "城市50")
        assertTrue(result is MeeAirQualityRankRepository.Result.Available)
        result as MeeAirQualityRankRepository.Result.Available
        assertEquals(50, result.rank)
        assertEquals(100, result.total)
        assertEquals(50, result.topPercent)
    }

    @Test
    fun meeRankDoesNotInventUnavailableCityRank() {
        val rows = (1..100).joinToString(separator = "") { index ->
            "<tr onclick=\"openWin2(&#39;城市${index}市&#39;,&#39;2026年09月01日 10时&#39;)\"><td>城市${index}市</td><td><i class=\"ii\">${index}</i></td></tr>"
        }
        assertEquals(
            MeeAirQualityRankRepository.Result.CityNotCovered,
            MeeAirQualityRankRepository.parse("2026年09月01日 10时$rows", "未收录城市")
        )
    }

    @Test
    fun caiyunCapabilitySummaryNeverCarriesCredentialMaterial() {
        val probe = CaiyunTokenCapabilityProbe(
            v27Route = true, v27Realtime = true, v27Hourly = true, v27Daily = true,
            v27AlertRoute = true, v26Route = true, v26Realtime = true, v26Hourly = true,
            v26Daily = true, v26AlertRoute = true, v26History = true,
            v26Astronomy = true, v26MinutelyRoute = true, v26MinutelyData = true,
            activationCode = 200
        )
        assertTrue(probe.canActivate)
        assertEquals("v2.7、v2.6、历史24小时、日出日落、分钟降雨", probe.compactSummary())
    }


    @Test
    fun solarCalculatorProvidesDaylightAndTwilightForNormalLatitude() {
        // This fixture describes a Shanghai civil day, not the CI host's UTC day.
        val originalZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val noon = java.util.Calendar.getInstance().apply {
                set(2026, java.util.Calendar.JUNE, 20, 12, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val data = SunAstronomyData(noon, noon + HOUR, latitude = 31.2, longitude = 121.5, sourceName = "测试")
            val phases = SunAstronomyCalculator.phases(data)
            assertNotNull(SunAstronomyCalculator.estimatedDaylightDuration(data))
            assertNotNull(phases.civilDawn)
            assertNotNull(phases.civilDusk)
            assertTrue(SunAstronomyCalculator.sunAzimuth(noon, 31.2, 121.5) in 0.0..360.0)
        } finally {
            java.util.TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun feelsLikeComfortUsesAbsoluteFeelsTemperatureAndVisibleRounding() {
        // 实际温度不同不会改变同一体感温度的舒适度结论。
        assertTrue(MetricDisplayPolicy.feelsLikeAdvice(25.0, -10.0).contains("舒适"))
        assertTrue(MetricDisplayPolicy.feelsLikeAdvice(30.0, 8.0).contains("偏热"))
        assertTrue(MetricDisplayPolicy.feelsLikeAdvice(5.0, 32.0).contains("偏冷"))
        // 卡片显示为整数；显示值相同便必须回到中心点。
        assertEquals(0.0, FeelsLikeComfortPolicy.displayDelta(18.2, 18.4)!!, 0.0)
        assertEquals(-1.2, FeelsLikeComfortPolicy.displayDelta(19.2, 18.0)!!, 0.001)
        assertEquals("较实际温度低 1℃", MetricDisplayPolicy.feelsLikeComparison(22.0, 21.0, "℃"))
        assertEquals("与实际温度相同（温差 0℃）", MetricDisplayPolicy.feelsLikeComparison(21.2, 21.4, "℃"))
    }

    @Test
    fun entryRefreshWaitsForCurrentCityInsteadOfRefreshingAnAttachedNeighbor() {
        val keys = listOf("current", "shanghai", "beijing")
        assertEquals(
            null,
            EntryRefreshQueuePolicy.orderedAttachedKeys(
                currentKey = "current", cityKeys = keys, attachedKeys = setOf("shanghai")
            )
        )
        assertEquals(
            listOf("current", "shanghai"),
            EntryRefreshQueuePolicy.orderedAttachedKeys(
                currentKey = "current", cityKeys = keys, attachedKeys = setOf("shanghai", "current")
            )
        )
    }

    @Test
    fun windSecondaryIsAlwaysTheGustSeries() {
        val point = HourlyMetricPoint(timeMillis = 0L, windKmh = 18.0, gustKmh = 31.0)
        assertEquals(31.0, MetricDisplayPolicy.gustOf(point)!!, 0.0)
    }

    @Test
    fun lifeAdvicePreservesQWeatherCopyAndMetricPriority() {
        val advice = LifeAdviceFactory.fromQWeather(
            listOf(
                QLifeIndex("1", "运动指数", "2", "较好", "原始运动建议"),
                QLifeIndex("5", "紫外线指数", "4", "高", "原始紫外线建议"),
                QLifeIndex("10", "空气污染扩散条件指数", "3", "中", "原始空气建议")
            )
        )
        assertEquals(3, advice.size)
        // 和风数值等级仅用于服务端分级，UI 只展示人可读分类，不再附加“· 4”等数字。
        assertEquals("高", advice.first { it.key == "q5" }.level)
        assertEquals("原始紫外线建议", advice.first { it.key == "q5" }.detail)
        assertEquals("q10", LifeAdviceFactory.forAirQuality(advice).first().key)
        assertEquals("q5", LifeAdviceFactory.forSun(advice).first().key)
    }

    @Test
    fun lifeAdviceStatusToneUsesComfortAndRiskSemantics() {
        assertEquals(LifeAdviceStatusStyle.Tone.POSITIVE, LifeAdviceStatusStyle.toneFor("舒适"))
        assertEquals(LifeAdviceStatusStyle.Tone.POSITIVE, LifeAdviceStatusStyle.toneFor("最弱"))
        assertEquals(LifeAdviceStatusStyle.Tone.RISK, LifeAdviceStatusStyle.toneFor("易发"))
        assertEquals(LifeAdviceStatusStyle.Tone.RISK, LifeAdviceStatusStyle.toneFor("不宜"))
        assertEquals(LifeAdviceStatusStyle.Tone.WARNING, LifeAdviceStatusStyle.toneFor("中等"))
        assertEquals(LifeAdviceStatusStyle.Tone.COOL, LifeAdviceStatusStyle.toneFor("保暖"))
        assertFalse(
            LifeAdviceStatusStyle.colorFor("舒适", dark = false) ==
                LifeAdviceStatusStyle.colorFor("易发", dark = false)
        )
    }

    @Test
    fun lifeAdviceStatusUsesFourGradesExceptNeutralToolIndices() {
        assertEquals(LifeAdviceStatusStyle.Grade.GREEN, LifeAdviceStatusStyle.gradeFor("舒适"))
        assertEquals(LifeAdviceStatusStyle.Grade.YELLOW, LifeAdviceStatusStyle.gradeFor("中等"))
        assertEquals(LifeAdviceStatusStyle.Grade.ORANGE, LifeAdviceStatusStyle.gradeFor("较差"))
        assertEquals(LifeAdviceStatusStyle.Grade.RED, LifeAdviceStatusStyle.gradeFor("易发"))
        val strong = LifeAdviceStatusStyle.colorFor("很强", dark = false)
        val neutralTool = LifeAdviceStatusStyle.colorFor(
            LifeAdviceItem("q12", "太阳镜", "很强", ""), dark = false
        )
        assertFalse(strong == neutralTool)
    }

    @Test
    fun forecastMetricValuesKeepTheirMeaningWhenTrackHeaderScrollsAway() {
        assertEquals("3级", ForecastMetricValueFormatter.format("紫外线", 3.2))
        assertEquals("66%", ForecastMetricValueFormatter.format("湿度", 65.6))
        assertEquals("49", ForecastMetricValueFormatter.format("空气", 49.4))
        assertEquals("18km/h", ForecastMetricValueFormatter.format("风力", 18.2))
        assertEquals("很弱", ForecastMetricSemanticLabel.uv(2.0))
        assertEquals("中等", ForecastMetricSemanticLabel.uv(3.0))
        assertEquals("极强", ForecastMetricSemanticLabel.uv(11.0))
        assertEquals("舒适", ForecastMetricSemanticLabel.humidity(60.0))
        assertEquals("偏湿", ForecastMetricSemanticLabel.humidity(70.0))
        assertEquals("很潮湿", ForecastMetricSemanticLabel.humidity(90.0))
    }

    @Test
    fun animatedIndicatorGapMovesTogetherWithItsHighlight() {
        val start = IndicatorTrackGeometry.segments(
            left = 0f, right = 200f, markerRadius = 4f, gapRadius = 12f, progress = 0f
        )
        val destination = IndicatorTrackGeometry.segments(
            left = 0f, right = 200f, markerRadius = 4f, gapRadius = 12f, progress = .72f
        )
        assertEquals(4f, start.markerX, .001f)
        assertEquals(0f, start.leftEnd, .001f)
        assertEquals(16f, start.rightStart, .001f)
        assertTrue(destination.markerX > start.markerX)
        assertEquals(destination.markerX - 12f, destination.leftEnd, .001f)
        assertEquals(destination.markerX + 12f, destination.rightStart, .001f)
        assertEquals(.5f, IndicatorTrackGeometry.feelsLikeProgress(0.0, 6.0), .001f)
        assertEquals(0f, IndicatorTrackGeometry.feelsLikeProgress(-6.0, 6.0), .001f)
        assertEquals(1f, IndicatorTrackGeometry.feelsLikeProgress(6.0, 6.0), .001f)
    }

    companion object {
        private const val HOUR = 60L * 60L * 1000L
    }
}
