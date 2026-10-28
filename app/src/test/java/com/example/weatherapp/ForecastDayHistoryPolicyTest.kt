package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ForecastDayHistoryPolicyTest {
    private val now=Calendar.getInstance().apply {
        set(2026,Calendar.OCTOBER,28,14,20,0);set(Calendar.MILLISECOND,0)
    }.timeInMillis
    private val start=ForecastDayHistoryPolicy.startOfDay(now)
    private fun row(time:Long,temp:Double?=21.0)=ForecastHour(time,"CLEAR_DAY",temp,null,12.0,3.0,50.0,35.0)

    @Test fun completeTodaySurvivesRefreshAndAutomaticallyBecomesYesterday() {
        val hours=(0..23).map {row(start+it*ForecastDetailData.HOUR)}
        val refreshed=ForecastDayHistoryPolicy.merge(hours,listOf(row(start+15*ForecastDetailData.HOUR,26.0)),now)
        assertEquals(24,refreshed.size);assertEquals(26.0,refreshed[15].temperature!!,0.0)
        val tomorrow=ForecastDayHistoryPolicy.startOfDay(now,1)+ForecastDetailData.HOUR
        val yesterday=ForecastDayHistoryPolicy.merge(refreshed,emptyList(),tomorrow)
        assertEquals(24,yesterday.size);assertTrue(yesterday.all {it.isHistorical})
        assertTrue(ForecastDayHistoryPolicy.merge(yesterday,emptyList(),ForecastDayHistoryPolicy.startOfDay(now,2)).isEmpty())
    }

    @Test fun missingFieldsKeepSavedValuesWithoutInventingHours() {
        val old=row(start,20.0).copy(feelsLike=19.0,visibilityKm=15.0)
        val merged=ForecastDayHistoryPolicy.merge(listOf(old),listOf(row(start,null).copy(wind=null)),now)
        assertEquals(1,merged.size);assertEquals(20.0,merged[0].temperature!!,0.0)
        assertEquals(19.0,merged[0].feelsLike!!,0.0);assertEquals(12.0,merged[0].wind!!,0.0)
        val slots=ForecastDayHistoryPolicy.slots(DailyForecastDetailPolicy.dateKey(start),merged,now)
        assertEquals(24,slots.size);assertEquals(1,slots.count {it.temperature!=null})
        assertNull(slots[1].skycon);assertNull(slots[1].aqi)
    }

    @Test fun excludesOtherDaysAndNonFiniteValues() {
        val day=DailyForecastDetailPolicy.dateKey(start)
        val slots=ForecastDayHistoryPolicy.slots(day,listOf(row(start-ForecastDetailData.HOUR),row(start),row(start+24*ForecastDetailData.HOUR)),now)
        assertEquals(1,slots.count {it.temperature!=null})
        val merged=ForecastDayHistoryPolicy.merge(emptyList(),listOf(row(start,Double.NaN)),now)
        assertNull(merged.single().temperature)
    }
}
