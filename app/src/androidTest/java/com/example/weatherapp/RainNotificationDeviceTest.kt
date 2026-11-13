package com.example.weatherapp

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic rain and isolated preferences; real user locations/caches/settings are never replaced. */
class RainNotificationDeviceTest {
    private val real = InstrumentationRegistry.getInstrumentation().targetContext
    private val used = mutableSetOf<String>()
    private val c = object : ContextWrapper(real) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            used += name
            return real.getSharedPreferences("rain_policy_test_$name", mode)
        }
    }
    private val manager get() = real.getSystemService(NotificationManager::class.java)
    private val original = mutableMapOf<Int, Notification>()
    private val ids = mutableSetOf(2001)
    private var t = 0L
    private val minute = 60_000L
    private val prefs get() = c.getSharedPreferences("weather_notification_ledger_v2", 0)
    private fun forecast(now: Long = t, rate: (Long) -> Double = { 1.0 }) =
        RainForecast((0 until 120).map { now + it * minute },
            (0 until 120).map { rate(now + it * minute) }, minute)
    private fun event(f: RainForecast = forecast(), city: String = "synthetic-rain-policy") =
        WeatherRainEvent(city, "测试城市", f.times.first(), "小雨", 1,
            "测试数据：降雨变化", "测试数据：分钟预报", f).also { ids += it.notificationId }
    private fun ledger(e: WeatherRainEvent, now: Long = t, accept: Boolean = true) =
        WeatherNotificationLedger.reconcileRain(c, e, now) { accept }
    private fun notification(id: Int, expectedTitle: String = ""): Notification {
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until) {
            manager.activeNotifications.firstOrNull { it.id == id &&
                it.notification.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains(expectedTitle)
            }?.let { return it.notification }
            Thread.sleep(30)
        }
        error("Android did not accept the rain notification")
    }
    @Before fun setup() {
        manager.activeNotifications.forEach { original[it.id] = it.notification }
        t = System.currentTimeMillis()
        WeatherSettings.setRainReminder(c, true)
        WeatherNotificationCenter.ensureChannels(c)
        assertTrue(WeatherNotificationCenter.canNotifyChannel(c, WeatherNotificationCenter.CHANNEL_RAIN))
    }
    @After fun restore() {
        ids.forEach { id -> manager.cancel(id); original[id]?.let { manager.notify(id, it) } }
        used.toList().forEach { real.getSharedPreferences("rain_policy_test_$it", 0).edit().clear().commit() }
    }
    @Test fun persistedLedgerDeduplicatesAndKeepsPendingChangeUntilHalfHour() {
        val e = event()
        assertEquals(RainDelivery.NEW, ledger(e).delivery)
        val changed = event(forecast(t + 15 * minute) { 1.5 })
        assertNull(ledger(changed, t + 15 * minute).delivery)
        assertTrue(JSONObject(prefs.getString("rain." + e.cityKey, null)!!).has("rainState"))
        assertEquals(RainDelivery.UPDATED, ledger(event(forecast(t + 30 * minute) { 1.5 }), t + 30 * minute).delivery)
        assertNull(ledger(event(forecast(t + 60 * minute) { 1.5 }), t + 60 * minute).delivery)
    }
    @Test fun failedNormalEmergencyAndEndingDeliveriesRemainRetryable() {
        val e = event()
        assertNull(ledger(e, accept = false).delivery)
        assertEquals(0, prefs.all.size)
        assertEquals(RainDelivery.NEW, ledger(e).delivery)
        val storm = event(forecast(t + minute) { 20.0 })
        val before = prefs.all.toMap()
        assertNull(ledger(storm, t + minute, false).delivery)
        assertEquals(before, prefs.all)
        assertEquals(RainDelivery.ESCALATED, ledger(storm, t + minute).delivery)
        val dry = event(forecast(t + 2 * minute) { 0.0 })
        assertNull(ledger(dry, t + 2 * minute, false).delivery)
        assertEquals(RainDelivery.ENDING, ledger(dry, t + 2 * minute).delivery)
        assertNull(ledger(dry, t + 3 * minute).delivery)
    }
    @Test fun upgradeSeedsLegacyEventAndChangingCityCancelsOldNotification() {
        val e = event()
        prefs.edit().putString("rain." + e.cityKey, JSONObject().apply {
            put("id", e.notificationId); put("n", e.locationName); put("t", t)
            put("r", 1); put("s", "旧版摘要"); put("p", "旧版峰值")
        }.toString()).commit()
        assertNull(ledger(e).delivery)
        val next = ledger(event(city = "second-synthetic-city"))
        assertEquals(RainDelivery.NEW, next.delivery)
        assertEquals(listOf(e.notificationId), next.cancelledNotificationIds)
    }
    @Test fun androidShowsEndingAdviceOnceAndDoesNotRepostDismissedNotification() {
        val wet = event()
        assertEquals(RainDelivery.NEW, WeatherNotificationCenter.reconcileRain(c, wet, t).delivery)
        notification(wet.notificationId)
        val ending = event(forecast(t + minute) { if (it < t + 15 * minute) 1.0 else 0.0 })
        assertEquals(RainDelivery.ENDING, WeatherNotificationCenter.reconcileRain(c, ending, t + minute).delivery)
        val shown = notification(wet.notificationId, "雨即将结束")
        assertTrue(shown.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("雨即将结束"))
        val body = shown.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(body.contains("雨停后可以正常出行"))
        assertFalse(shown.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        manager.cancel(wet.notificationId)
        Thread.sleep(100)
        assertNull(WeatherNotificationCenter.reconcileRain(c, ending, t + 2 * minute).delivery)
        assertFalse(manager.activeNotifications.any { it.id == wet.notificationId })
        assertNull(WeatherNotificationCenter.reconcileRain(c,
            event(forecast(t + 20 * minute) { 0.0 }), t + 20 * minute).delivery)
    }
    @Test fun androidNormalUpdateAndSuddenStormCanAlertAgainWithoutDuplicatingEntries() {
        val e = event()
        assertEquals(RainDelivery.NEW, WeatherNotificationCenter.reconcileRain(c, e, t).delivery)
        val updated = event(forecast(t + 30 * minute) { 1.5 })
        assertEquals(RainDelivery.UPDATED, WeatherNotificationCenter.reconcileRain(c, updated, t + 30 * minute).delivery)
        assertFalse(notification(e.notificationId).flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        val storm = event(forecast(t + 31 * minute) { 20.0 })
        assertEquals(RainDelivery.ESCALATED, WeatherNotificationCenter.reconcileRain(c, storm, t + 31 * minute).delivery)
        notification(e.notificationId)
        assertNull(WeatherNotificationCenter.reconcileRain(c, storm, t + 32 * minute).delivery)
        assertEquals(1, manager.activeNotifications.count { it.id == e.notificationId })
    }
    @Test fun sharedMinutePublisherRejectsIncompleteDataAndSendsAlreadyEndedMessage() {
        val city = CityPage("测试城市", 0.0, 0.0, true)
        val id = 2001 + WeatherNotificationLedger.cityKey(city).stablePositiveHash()
        ids += id
        val f = forecast()
        WeatherRiskRefresh.publishRain(c, city, f.rates, f.times, t)
        notification(id)
        val before = prefs.all.toMap()
        try {
            WeatherRiskRefresh.publishRain(c, city, listOf(0.0, 0.0), listOf(t, t + 10 * minute), t)
            fail("Gapped data must be rejected")
        } catch (_: java.io.IOException) {}
        assertEquals(before, prefs.all)
        val dry = forecast(t + minute) { 0.0 }
        WeatherRiskRefresh.publishRain(c, city, dry.rates, dry.times, t + minute)
        val shown = notification(id, "降雨已结束")
        assertTrue(shown.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("降雨已结束"))
        assertTrue(shown.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("可以正常出行"))
    }
    @Test fun fiveMinuteAndOneMinuteSourcesShareIdenticalDeduplicationRules() {
        for (step in listOf(1, 5)) {
            val now = t
            val f = RainForecast((0 until 120 step step).map { now + it * minute },
                (0 until 120 step step).map { 1.0 }, step * minute)
            val e = event(f, "source-step-$step")
            assertEquals(RainDelivery.NEW, ledger(e).delivery)
            assertNull(ledger(e.copy(summary = "倒计时文字变化", peak = "峰值文字变化"), t + minute).delivery)
        }
    }
}

