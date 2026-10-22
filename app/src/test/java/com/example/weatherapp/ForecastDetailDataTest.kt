package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class ForecastDetailDataTest {
    @Test fun fullHorizonRetainedAndAqiAlignedByHourNotIndex() {
        val h = ForecastDetailData.HOUR
        val points = (0..360).map { HourlyMetricPoint(it * h, temperature = it.toDouble(), uv = 0.0, windKmh = 10.0) }
        val result = ForecastDetailData.build(points.reversed(), mapOf(1L to "CLEAR_DAY"),
            listOf(AirQualityPoint(2 * h + 60_000, aqi = 42.0)), h)
        assertEquals(360, result.size)
        assertEquals("CLEAR_DAY", result[0].skycon)
        assertNull(result[0].aqi)
        assertEquals(42.0, result[1].aqi!!, 0.0)
        assertEquals(0.0, result[0].uv!!, 0.0)
        assertNull(result[0].feelsLike)
        assertEquals(48, result.take(ForecastDetailData.HOME_HOURS).size)
    }
    @Test fun missingFieldsAreNotInventedAndDuplicateHoursRemoved() {
        val rows = ForecastDetailData.build(listOf(HourlyMetricPoint(0, uv = Double.NaN), HourlyMetricPoint(0)), emptyMap(), emptyList(), 0)
        assertEquals(1, rows.size)
        assertNull(rows.single().uv)
        assertNull(rows.single().aqi)
    }
    @Test fun detailCanIncludeExactlyOneHistoricalHourAndMarksIt() {
        val h = ForecastDetailData.HOUR
        val now = 10 * h + 12_000L
        val rows = ForecastDetailData.build(
            (7L..12L).map { HourlyMetricPoint(it * h, temperature = it.toDouble()) },
            emptyMap(), emptyList(), now, pastHours = 1
        )
        assertEquals(listOf(9L, 10L, 11L, 12L), rows.map { it.time / h })
        assertEquals(listOf(true, false, false, false), rows.map { it.isHistorical })
    }
    @Test fun hourlyDetailKeepsFortyEightPointsAndNormalizesRainProbability() {
        val rows = (0 until 52).map { index ->
            ForecastHour(
                time = index * ForecastDetailData.HOUR,
                skycon = "CLEAR_DAY",
                temperature = 20.0,
                feelsLike = 19.0,
                wind = 12.0,
                uv = 3.0,
                humidity = 65.0,
                aqi = 42.0,
                isHistorical = index == 0,
                precipitationProbability = if (index % 2 == 0) 0.35 else 35.0
            )
        }
        val window = HourlyForecastDetailPolicy.window(rows)
        assertEquals(48, window.size)
        assertEquals(1, HourlyForecastDetailPolicy.initialIndex(window))
        assertEquals(35, HourlyForecastDetailPolicy.percent(window[0].precipitationProbability))
        assertEquals(35, HourlyForecastDetailPolicy.percent(window[1].precipitationProbability))
        assertEquals(7, HourlyForecastDetailPolicy.centeredIndex((7 * HourlyForecastDetailPolicy.HOUR_STEP_DP).toInt(), HourlyForecastDetailPolicy.HOUR_STEP_DP, 47))
        assertEquals(1f, HourlyForecastDetailPolicy.selectionEmphasis(0f), 0.001f)
        assertEquals(0.5f, HourlyForecastDetailPolicy.selectionEmphasis(0.5f), 0.001f)
        assertEquals(0f, HourlyForecastDetailPolicy.selectionEmphasis(1f), 0.001f)
        assertEquals(HourlyForecastDetailPolicy.BAR_WIDTH_DP, HourlyForecastDetailPolicy.barWidthDp(0f), 0.001f)
        assertEquals(HourlyForecastDetailPolicy.BAR_SELECTED_WIDTH_DP, HourlyForecastDetailPolicy.barWidthDp(1f), 0.001f)
        assertEquals(1f, HourlyForecastDetailPolicy.metricLabelScale(0f), 0.001f)
        assertEquals(HourlyForecastDetailPolicy.HIGHLIGHTED_METRIC_SCALE,
            HourlyForecastDetailPolicy.metricLabelScale(1f), 0.001f)
        assertTrue(HourlyForecastDetailPolicy.shouldShowTimeLabel(0))
        assertTrue(HourlyForecastDetailPolicy.shouldShowTimeLabel(6))
        assertTrue(HourlyForecastDetailPolicy.shouldShowTimeLabel(42))
        assertFalse(HourlyForecastDetailPolicy.shouldShowTimeLabel(5))
        assertFalse(HourlyForecastDetailPolicy.shouldShowTimeLabel(47))
        assertTrue(HourlyForecastDetailPolicy.shouldRenderTimeLabel(6, 0f))
        assertTrue(HourlyForecastDetailPolicy.shouldRenderTimeLabel(5, .4f))
        assertFalse(HourlyForecastDetailPolicy.shouldRenderTimeLabel(5, 0f))
        assertTrue(HourlyForecastDetailPolicy.EDGE_INSET_DP >= HourlyForecastDetailPolicy.BAR_SELECTED_WIDTH_DP / 2f)
        // 48 小时页不再把高亮点固定在视口中央：当前列从左侧起步，并会随手势平滑移向右端。
        val start = HourlyForecastDetailPolicy.movingSelectionViewportX(
            scrollX = 0, maxScroll = 926, initialX = 38f, viewportWidth = 320, edgeInset = 12f
        )
        val middle = HourlyForecastDetailPolicy.movingSelectionViewportX(
            scrollX = 463, maxScroll = 926, initialX = 38f, viewportWidth = 320, edgeInset = 12f
        )
        val end = HourlyForecastDetailPolicy.movingSelectionViewportX(
            scrollX = 926, maxScroll = 926, initialX = 38f, viewportWidth = 320, edgeInset = 12f
        )
        assertEquals(38f, start, 0.001f)
        assertEquals(173f, middle, 0.001f)
        assertEquals(308f, end, 0.001f)
    }
    @Test fun hourlySummaryUsesTodayTomorrowAndTheDayAfterInsteadOfAbsoluteDates() {
        val calendar = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 21, 10, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val reference = calendar.timeInMillis
        assertEquals("今天17点左右", HourlyForecastDetailPolicy.relativeTimeLabel(reference + 7 * ForecastDetailData.HOUR, reference))
        assertEquals("明天17点左右", HourlyForecastDetailPolicy.relativeTimeLabel(reference + 31 * ForecastDetailData.HOUR, reference))
        assertEquals("后天17点左右", HourlyForecastDetailPolicy.relativeTimeLabel(reference + 55 * ForecastDetailData.HOUR, reference))
        assertEquals("今天 17时", HourlyForecastDetailPolicy.timeAxisLabel(reference + 7 * ForecastDetailData.HOUR, reference))
        assertEquals("明日 17时", HourlyForecastDetailPolicy.timeAxisLabel(reference + 31 * ForecastDetailData.HOUR, reference))
        assertEquals("后日 17时", HourlyForecastDetailPolicy.timeAxisLabel(reference + 55 * ForecastDetailData.HOUR, reference))
    }
    @Test fun hourlyMetricCurveStaysInsideItsOwnLaneForEveryHour() {
        val top = 184f
        val bottom = 236f
        listOf(0.0, 3.5, 8.0, 16.0, 31.0).forEach { value ->
            val y = HourlyForecastDetailPolicy.metricY(value, low = 0.0, high = 31.0, graphTop = top, graphBottom = bottom)
            assertTrue("value=$value y=$y", y in top..bottom)
        }
        assertEquals(bottom, HourlyForecastDetailPolicy.metricY(0.0, 0.0, 31.0, top, bottom), 0.001f)
        assertEquals(top, HourlyForecastDetailPolicy.metricY(31.0, 0.0, 31.0, top, bottom), 0.001f)
    }
    @Test fun forecastPreservesWindDirectionAndConvertsItToNavigationArrowRotation() {
        val rows = ForecastDetailData.build(
            listOf(
                HourlyMetricPoint(0L, windKmh = 12.0, windDirectionDegrees = 0.0),
                HourlyMetricPoint(ForecastDetailData.HOUR, windKmh = 13.0, windDirectionDegrees = -90.0),
                HourlyMetricPoint(ForecastDetailData.HOUR * 2, windKmh = 14.0, windDirectionDegrees = Double.NaN)
            ), emptyMap(), emptyList(), 0L
        )
        assertEquals(0.0, rows[0].windDirectionDegrees!!, 0.0)
        // 原始来向角原样保留；绘制层再统一归一化，避免改写天气源返回的读数。
        assertEquals(-90.0, rows[1].windDirectionDegrees!!, 0.0)
        assertNull(rows[2].windDirectionDegrees)
        // 北风向南流动；西风向东流动。导航箭头均指向空气实际去向。
        assertEquals(180f, ForecastWindDirection.navigationArrowRotation(0.0)!!, 0.001f)
        assertEquals(90f, ForecastWindDirection.navigationArrowRotation(-90.0)!!, 0.001f)
        assertNull(ForecastWindDirection.navigationArrowRotation(Double.NaN))
    }
    @Test fun homeUsesExactlyRequestedNineWithoutChangingFullAdvice() {
        val all = (1..16).map { LifeAdviceItem("q$it", "原始$it", "适宜", "说明$it") }
        val home = HomeLifeAdvice.select(all)
        assertEquals(listOf("q1", "q3", "q5", "q15", "q4", "q6", "q9", "q8", "q7"), home.map { it.key })
        assertEquals(16, all.size)
        assertEquals("说明5", home[2].detail)
        assertEquals(9, HomeLifeAdvice.select(emptyList()).size)
    }
}
