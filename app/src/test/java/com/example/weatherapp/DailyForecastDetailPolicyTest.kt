package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DailyForecastDetailPolicyTest {
    private fun time(value: String)=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).parse(value)!!.time
    private fun hour(date: String,temp: Double)=ForecastHour(time(date),"CLEAR_DAY",temp,temp,10.0,2.0,55.0,40.0)

    @Test fun separatesLocalMidnightAndSortsWithoutMixingDays() {
        val a=hour("2026-10-27 23:00",20.0);val b=hour("2026-10-28 00:00",8.0)
        val pages=DailyForecastDetailPolicy.pages(listOf(b,a,a),emptyList())
        assertEquals(listOf("2026-10-27","2026-10-28"),pages.map {it.date})
        assertEquals(listOf(a),pages[0].hours);assertEquals(listOf(b),pages[1].hours)
    }
    @Test fun explicitSelectionIncludesHistoryAndFarDaysWithoutHours() {
        val snapshots=listOf("2026-10-26","2026-10-27","2026-11-05").map {ForecastDaySnapshot(it,"CLOUDY",22.0,12.0,null)}
        val pages=DailyForecastDetailPolicy.pages(listOf(hour("2026-10-27 12:00",20.0)),snapshots)
        assertEquals(0,DailyForecastDetailPolicy.initialIndex(pages,"2026-10-26",time("2026-10-27 12:00")))
        assertEquals(2,DailyForecastDetailPolicy.initialIndex(pages,"2026-11-05",time("2026-10-27 12:00")))
        assertTrue(pages[2].hours.isEmpty())
        assertEquals(12.0 to 22.0,DailyForecastDetailPolicy.temperatureRange(pages[2]))
    }
    @Test fun defaultSelectionIsTodayAndDoesNotStartOnYesterday() {
        val pages=DailyForecastDetailPolicy.pages(listOf(hour("2026-10-26 23:00",10.0),hour("2026-10-27 12:00",20.0)),emptyList())
        assertEquals(1,DailyForecastDetailPolicy.initialIndex(pages,null,time("2026-10-27 12:00")))
        assertEquals(1,DailyForecastDetailPolicy.initialIndex(pages,"bad-date",time("2026-10-27 12:00")))
    }
    @Test fun rangeUsesOnlySelectedDayIncludingItsDailyForecast() {
        val pages=DailyForecastDetailPolicy.pages(listOf(hour("2026-10-27 12:00",20.0),hour("2026-10-28 12:00",99.0)),
            listOf(ForecastDaySnapshot("2026-10-27",null,25.0,12.0,null)))
        assertEquals(12.0 to 25.0,DailyForecastDetailPolicy.temperatureRange(pages[0]))
    }
    @Test fun noDataStaysMissingAndProbabilityOneMeansOnePercent() {
        val snapshot=ForecastDaySnapshot.from(DailyForecast("2026-10-27",null,null,null,1))
        val page=DailyForecastDetailPolicy.pages(emptyList(),listOf(snapshot)).single()
        assertNull(DailyForecastDetailPolicy.temperatureRange(page));assertEquals(1,snapshot.rainPercent)
        assertTrue(page.hours.isEmpty())
    }
    @Test fun explicitTimezoneControlsDayGrouping() {
        val h=hour("2026-10-27 12:00",20.0)
        val zone=TimeZone.getTimeZone("GMT+14")
        assertEquals(DailyForecastDetailPolicy.dateKey(h.time,zone),DailyForecastDetailPolicy.pages(listOf(h),emptyList(),zone).single().date)
    }
    @Test fun columnHitTestingSharesExactShadeBoundariesAtAllWidths() {
        for(width in listOf(300f,1000f)) for(n in listOf(2,7,15)) for(i in 0 until n) {
            val bounds=DailyForecastDetailPolicy.columnBounds(i,width,32f,n)
            assertEquals(i,DailyForecastDetailPolicy.columnAt((bounds.first+bounds.second)/2,width,32f,n))
        }
        assertEquals(0,DailyForecastDetailPolicy.columnAt(0f,300f,32f,7))
        assertEquals(6,DailyForecastDetailPolicy.columnAt(300f,300f,32f,7))
        assertNull(DailyForecastDetailPolicy.columnAt(-1f,300f,32f,7))
        assertNull(DailyForecastDetailPolicy.columnAt(1f,300f,32f,0))
    }
    @Test fun temperatureAdviceIsBasedOnTheChosenDay() {
        assertTrue(DailyForecastDetailPolicy.temperatureAdvice(2.0,12.0).contains("保暖"))
        assertTrue(DailyForecastDetailPolicy.temperatureAdvice(25.0,36.0).contains("补水"))
        assertTrue(DailyForecastDetailPolicy.temperatureAdvice(12.0,24.0).contains("分层"))
    }
}
