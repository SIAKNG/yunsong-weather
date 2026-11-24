package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class WeatherNoticeCopyTest {
    @Test fun knownDailyFieldsAreAllRepresentedWithAdviceAndNoInventedAlert() {
        val daily = WeatherNoticeCopy.daily("11℃–24℃", 11.0, 24.0, "CLEAR_DAY", 46.0, 0.0, 9.0, null, null)
        for (text in listOf("11℃–24℃", "晴", "空气质量总体为优", "不用特意带伞", "风比较温和", "温差明显")) {
            assertTrue(text, daily.contains(text))
        }
        assertFalse(daily.contains("预警"))
    }

    @Test fun unavailableFieldsAreOmittedAndKnownWindRainAndAlertStayVisible() {
        val sparse = WeatherNoticeCopy.daily("20℃–23℃", 20.0, 23.0, null, null, null, null, null, null)
        assertEquals("20℃–23℃。", sparse)
        val storm = WeatherNoticeCopy.daily("20℃–23℃", 20.0, 23.0, "RAIN", 115.0, 0.75, 43.0, 75.0,
            WeatherNoticeCopy.warningSummary("海淀区", "暴雨橙色预警"))
        for (text in listOf("海淀区已发布暴雨橙色预警", "轻度污染", "75%", "阵风")) assertTrue(text, storm.contains(text))
    }

    @Test fun officialWarningKeepsItsPublishingAreaAndDirections() {
        val area = WeatherNoticeCopy.warningArea("北京市海淀区气象台", "北京市")
        val title = WeatherNoticeCopy.warningTitle(area, "大雾橙色预警", true)
        assertTrue(title.startsWith("北京市海淀区｜"))
        assertTrue(title.endsWith("已升级"))
        val instructions = "能见度低，请控制车速。"
        val body = WeatherNoticeCopy.warningBody(area, "大雾橙色预警", instructions, true)
        assertTrue(body.startsWith("北京市海淀区已发布"))
        assertTrue(body.endsWith(instructions))
    }
}