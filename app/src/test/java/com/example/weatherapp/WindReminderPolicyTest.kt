package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class WindReminderPolicyTest {
    @Test fun matchesNanfengThresholdAndStrongerCandidate() {
        assertNull(WindReminderPolicy.evaluate(38.8, 38.8))
        assertEquals("5级", WindReminderPolicy.evaluate(38.9, null)?.level)
        assertFalse(WindReminderPolicy.evaluate(38.9, null)!!.isGust)
        assertTrue(WindReminderPolicy.evaluate(20.0, 38.9)!!.isGust)
        assertEquals("8级", WindReminderPolicy.evaluate(50.0, 74.9)?.level)
        assertNull(WindReminderPolicy.evaluate(Double.NaN, Double.POSITIVE_INFINITY))
    }

    @Test fun currentWindDoesNotRequireHourlyDataAndGustAloneWorks() {
        val city = CityPage("测试", 0.0, 0.0, false)
        val now = TimeUtil.parse("2026-09-06T12:20:00+08:00")!!
        assertNotNull(WindReminderPolicy.fromCaiyun(city, Result(realtime = RealTime(wind = Wind(40.0))), now))
        val result = Result(realtime = RealTime(), hourly = Hourly(gust = listOf(
            HourlyValue("2026-09-06T12:00:00+08:00", 38.9))))
        assertTrue(WindReminderPolicy.fromCaiyun(city, result, now)!!.detail.contains("阵风"))
        val stale = result.copy(hourly = Hourly(gust = listOf(HourlyValue("2026-09-05T12:00:00+08:00", 99.0))))
        assertNull(WindReminderPolicy.fromCaiyun(city, stale, now))
    }

    @Test fun twoHourIntervalCountsFromSuccessfulDelivery() {
        val sent = 1_800_000_000_000L
        assertTrue(WindReminderPolicy.isDue(0, sent))
        assertFalse(WindReminderPolicy.isDue(sent, sent + 7_200_000L))
        assertTrue(WindReminderPolicy.isDue(sent, sent + 7_200_001L))
    }
}
