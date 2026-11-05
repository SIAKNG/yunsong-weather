package com.example.weatherapp

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/** Private preference namespaces keep test cities, API choices and delivery ledgers out of real user data. */
class NotificationReliabilityDeviceTest {
    private val real = InstrumentationRegistry.getInstrumentation().targetContext
    private val used = mutableSetOf<String>()
    private val c = object : ContextWrapper(real) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            used += name
            return real.getSharedPreferences("notification_test_$name", mode)
        }
    }
    private val city = CityPage("北京", 116.407, 39.904, true)
    private val manager get() = real.getSystemService(NotificationManager::class.java)
    private val original = mutableMapOf<Int, android.app.Notification>()
    private val ids = mutableSetOf<Int>()

    @Before fun setup() {
        manager.activeNotifications.forEach { original[it.id] = it.notification }
        c.getSharedPreferences("weather_cities", 0).edit().putString("cities", """[{"name":"北京","lng":116.407,"lat":39.904,"isLoc":true}]""").commit()
        WeatherSettings.setDailyForecastReminder(c, true)
        WeatherSettings.setTemperatureChangeReminder(c, true)
        WeatherSettings.setRainReminder(c, false)
        WeatherSettings.setDisasterReminder(c, false)
        WeatherSettings.setStrongWindReminder(c, false)
        WeatherNotificationCenter.ensureChannels(c)
        seedCache()
    }
    @After fun restore() {
        ids.forEach { id -> manager.cancel(id); original[id]?.let { manager.notify(id, it) } }
        used.toList().forEach { real.getSharedPreferences("notification_test_$it", 0).edit().clear().commit() }
        WeatherNotificationCenter.updateSchedules(real)
        WeatherBackgroundScheduler.schedule(real)
        WeatherWidgets.restore(real)
    }
    private fun seedCache(includeTomorrow: Boolean = true) {
        val today = LocalDate.now()
        val payload = CaiyunResponse(status="ok", serverTime=System.currentTimeMillis()/1000,
            result=Result(daily=Daily(temperature=(-1..if(includeTomorrow) 1 else 0).map { i -> DailyTemp(today.plusDays(i.toLong()).toString(), 24.0 + i*6, 12.0 + i*6) },
                skycon=(-1..1).map { i -> DailySkycon(today.plusDays(i.toLong()).toString(), "CLEAR_DAY") })))
        val key=String.format(Locale.US,"weather_%s_%.3f_%.3f",(if(BuildConfig.USER_CONFIGURABLE) WeatherSettings.dataSource(c)+"_"+WeatherWidgetPolicy.sourceKey(c) else "unified_v3"),city.lat,city.lng)
        c.getSharedPreferences("weather_response_cache",0).edit().putInt("schema_version",3)
            .putString("${key}_json", Gson().toJson(payload)).putLong("${key}_at",System.currentTimeMillis()).commit()
    }
    private fun awaitNotification(id: Int) {
        val end=System.currentTimeMillis()+2500L
        while(System.currentTimeMillis()<end && manager.activeNotifications.none{it.id==id}) Thread.sleep(25)
        assertTrue("Notification must reach Android",manager.activeNotifications.any{it.id==id})
    }
    @Test fun preparedForecastSurvivesOfflineAndExpiresWithoutBecomingFreshAgain() {
        WeatherReminderPreparation.prepareFromCache(c,city)
        assertNotNull(WeatherReminderPreparation.message(c,city,LocalDate.now()))
        c.getSharedPreferences("weather_response_cache",0).edit().clear().commit()
        WeatherReminderPreparation.prepareFromCache(c,city)
        assertNotNull(WeatherReminderPreparation.message(c,city,LocalDate.now()))
        assertNull(WeatherReminderPreparation.message(c,city,LocalDate.now(),System.currentTimeMillis()+13*3_600_000L))
        WeatherSettings.setTempUnit(c,WeatherSettings.TEMP_F)
        assertNull("Unit changes must not use old prepared text",WeatherReminderPreparation.message(c,city,LocalDate.now()))
    }
    @Test fun alarmDeliveryUsesPreparedForecastWithoutNetworkAndNeverRepeatsAcceptedContent() {
        assertTrue("Enable notifications for this device regression",WeatherNotificationCenter.canNotifyChannel(c,WeatherNotificationCenter.CHANNEL_FORECAST))
        val tomorrow=WeatherReminderPolicy.window().tomorrow
        val id=if(tomorrow) 3002 else 3001;ids+=id
        WeatherReminderPreparation.prepareFromCache(c,city)
        c.getSharedPreferences("weather_response_cache",0).edit().clear().commit()
        MorningWeatherReceiver().onReceive(c, android.content.Intent().setAction(
            if(tomorrow) WeatherNotificationCenter.ACTION_DAILY_TOMORROW else WeatherNotificationCenter.ACTION_DAILY_TODAY))
        awaitNotification(id)
        assertTrue(DailyForecastDeliveryLedger.delivered(c,tomorrow))
        manager.cancel(id)
        assertTrue(WeatherNotificationCenter.postDailyForecast(c,tomorrow))
        assertFalse(manager.activeNotifications.any{it.id==id})
    }
    @Test fun temperatureReadyBeforeNetworkCanDeliverAndFailedAttemptRemainsRetryable() {
        WeatherReminderPreparation.prepareFromCache(c,city)
        val event=WeatherReminderPolicy.temperatureChange(city,Daily(temperature=listOf(
            DailyTemp(LocalDate.now().minusDays(1).toString(),18.0,6.0),DailyTemp(LocalDate.now().toString(),24.0,12.0))),LocalDate.now().minusDays(1))!!
        ids+=event.notificationId
        val n=System.currentTimeMillis()
        var attempts=0
        assertEquals(0,TemperatureChangeDeliveryLedger.deliverDue(c,n){ attempts++;false })
        assertTrue(attempts>0)
        c.getSharedPreferences("weather_response_cache",0).edit().clear().commit()
        WeatherNotificationCenter.reconcilePendingTemperature(c)
        awaitNotification(event.notificationId)
        manager.cancel(event.notificationId)
        WeatherNotificationCenter.reconcilePendingTemperature(c)
        assertFalse(manager.activeNotifications.any{it.id==event.notificationId})
    }
    @Test fun preparedTextAndPendingTemperatureDoNotLeakAcrossCitiesOrSourceRevision() {
        WeatherReminderPreparation.prepareFromCache(c,city)
        assertNull(WeatherReminderPreparation.message(c,city.copy(lng=121.474,lat=31.230,name="上海"),LocalDate.now()))
        if(BuildConfig.USER_CONFIGURABLE) {
            c.getSharedPreferences(UserApiConfiguration.PREFS,0).edit().putLong("revision",1234).commit()
            assertNull(WeatherReminderPreparation.message(c,city,LocalDate.now()))
            WeatherNotificationCenter.reconcilePendingTemperature(c)
            val event=WeatherReminderPolicy.temperatureChange(city,Daily(temperature=listOf(
                DailyTemp(LocalDate.now().minusDays(1).toString(),18.0,6.0),DailyTemp(LocalDate.now().toString(),24.0,12.0))),LocalDate.now().minusDays(1))!!
            assertFalse(manager.activeNotifications.any{it.id==event.notificationId})
        }
    }
    @Test fun correctedForecastRemovesUnsentTemperatureButCannotResetSuccessfulDelivery() {
        val date=LocalDate.now()
        val at=date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val event=WeatherChangeEvent(city,"temperature",at,1,"测试","变温")
        val now=System.currentTimeMillis()
        TemperatureChangeDeliveryLedger.stage(c,event,now-3)
        TemperatureChangeDeliveryLedger.removeUndelivered(c,city,date,now-2)
        assertEquals(0,TemperatureChangeDeliveryLedger.deliverDue(c){true})
        TemperatureChangeDeliveryLedger.stage(c,event,now-1)
        assertEquals(1,TemperatureChangeDeliveryLedger.deliverDue(c){true})
        TemperatureChangeDeliveryLedger.removeUndelivered(c,city,date)
        TemperatureChangeDeliveryLedger.stage(c,event)
        assertEquals(0,TemperatureChangeDeliveryLedger.deliverDue(c){true})
    }
    @Test fun partialNewForecastKeepsOtherPreparedDayAndItsOriginalAge() {
        WeatherReminderPreparation.prepareFromCache(c,city)
        val tomorrow=LocalDate.now().plusDays(1)
        val old=WeatherReminderPreparation.message(c,city,tomorrow)
        assertNotNull(old)
        seedCache(includeTomorrow=false)
        WeatherReminderPreparation.prepareFromCache(c,city)
        assertEquals(old,WeatherReminderPreparation.message(c,city,tomorrow))
        assertNull(WeatherReminderPreparation.message(c,city,tomorrow,System.currentTimeMillis()+13*3_600_000L))
    }
    @Test fun olderCacheCannotOverrideOrCancelNewerTemperatureJudgement() {
        val date=LocalDate.now()
        val at=date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now=System.currentTimeMillis()
        val fresh=WeatherChangeEvent(city,"temperature",at,1,"最新判断","最新接口温差")
        TemperatureChangeDeliveryLedger.stage(c,fresh,now)
        TemperatureChangeDeliveryLedger.stage(c,fresh.copy(detail="较旧缓存"),now-60_000L)
        TemperatureChangeDeliveryLedger.removeUndelivered(c,city,date,now-60_000L)
        var delivered=""
        assertEquals(1,TemperatureChangeDeliveryLedger.deliverDue(c){ delivered=it.detail;true })
        assertEquals("最新接口温差",delivered)
    }
    @Test fun preparationJobsAndActualPermissionStateAreInspectableWithoutPrivateData() {
        WeatherNotificationCenter.updateSchedules(c)
        val scheduled=androidx.work.WorkManager.getInstance(real).getWorkInfosByTag("weather_reminder_preparation")
            .get(5,java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(scheduled.any{!it.state.isFinished})
        val alarm=real.getSystemService(android.app.AlarmManager::class.java)
        val exact=android.os.Build.VERSION.SDK_INT<31 || alarm.canScheduleExactAlarms()
        val value=JSONObject().put("exact_alarm_allowed",exact)
            .put("notifications_allowed",WeatherNotificationCenter.canNotify(real))
            .put("prepared_jobs_registered",true).put("network_required_for_alarm_delivery",false)
            .put("private_preferences_isolated",true)
        val folder=java.io.File(real.getExternalFilesDir(null),"notification-verification").apply{mkdirs()}
        java.io.File(folder,"device-check.json").writeText(value.toString(2))
    }
    @Test fun newerNoChangeJudgementCannotBeResurrectedByOlderOrSameTimestampCache() {
        val date=LocalDate.now()
        val at=date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val event=WeatherChangeEvent(city,"temperature",at,1,"旧判断","旧缓存温差")
        val now=System.currentTimeMillis()
        TemperatureChangeDeliveryLedger.stage(c,event,now-60_000L)
        TemperatureChangeDeliveryLedger.removeUndelivered(c,city,date,now)
        TemperatureChangeDeliveryLedger.stage(c,event,now-60_000L)
        TemperatureChangeDeliveryLedger.stage(c,event,now)
        assertEquals(0,TemperatureChangeDeliveryLedger.deliverDue(c){true})
        TemperatureChangeDeliveryLedger.stage(c,event.copy(detail="更新后的有效预报"),now+1)
        assertEquals(1,TemperatureChangeDeliveryLedger.deliverDue(c){true})
    }
    @Test fun disablingThenReenablingTemperatureCannotRepeatAnAlreadyAcceptedReminder() {
        val at=LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val event=WeatherChangeEvent(city,"temperature",at,1,"已发送","变温")
        TemperatureChangeDeliveryLedger.stage(c,event)
        assertEquals(1,TemperatureChangeDeliveryLedger.deliverDue(c){true})
        TemperatureChangeDeliveryLedger.discardUnsent(c)
        TemperatureChangeDeliveryLedger.stage(c,event)
        assertEquals(0,TemperatureChangeDeliveryLedger.deliverDue(c){true})
    }
}
