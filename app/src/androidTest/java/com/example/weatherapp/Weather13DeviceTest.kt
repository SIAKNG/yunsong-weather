package com.example.weatherapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.viewpager2.widget.ViewPager2
import android.widget.TextView
import android.view.MotionEvent
import java.util.Calendar
import java.io.File
import android.view.PixelCopy

class Weather13DeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    @Test fun installedAndAboutVersionAreBoth13() {
        @Suppress("DEPRECATION")
        val installed=context.packageManager.getPackageInfo(context.packageName,0)
        assertTrue(installed.versionName.orEmpty().startsWith("1.3"))
        ActivityScenario.launch(WeatherAboutActivity::class.java).use {scenario ->
            scenario.onActivity {activity ->
                assertTrue(activity.findViewById<TextView>(R.id.tvWeatherAboutVersion).text.contains(installed.versionName.orEmpty()))
            }
        }
    }
    private fun midnight(offset: Int)=Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);add(Calendar.DATE,offset)
    }.timeInMillis
    private fun rows(offset: Int)=(0..23).map {h ->
        ForecastHour(midnight(offset)+h*ForecastDetailData.HOUR,"CLEAR_DAY",15.0+h%10,14.0+h%10,8.0+h%10,
            h%10.0,40.0+h,35.0+h,false,135.0,h*2.0,if(h in 10..15) 1.2 else 0.0,25.0,1008.0+h%8,12.0)
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any,name: String): T=
        target.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(target) as T

    @Test fun dailyCapsulesUseMetricChartsKeepTheHourAndFitBothThemes() {
        val original=WeatherSettings.themeMode(context)
        try {
            for(dark in listOf(false,true)) {
                WeatherSettings.setThemeMode(context,if(dark) WeatherSettings.THEME_DARK else WeatherSettings.THEME_LIGHT)
                ins.runOnMainSync {WeatherSettings.applyNightMode(context)}
                val date=DailyForecastDetailPolicy.dateKey(midnight(0))
                ActivityScenario.launch<ForecastDetailActivity>(ForecastDetailActivity.createIntent(context,"北京市",rows(0),date,
                    listOf(DailyForecast(date,"CLEAR_DAY",26,15,40)),21.0)).use {scenario ->
                    SystemClock.sleep(450);ins.waitForIdleSync()
                    scenario.onActivity {activity ->
                        val panel=activity.findViewById<View>(android.R.id.content).findViewWithTag<DailyForecastMetricPanel>("daily_metric_panel")
                        assertNotNull(panel)
                        val capsule=panel.findViewWithTag<ViewGroup>("daily_metric_capsule")
                        assertNotNull(capsule)
                        assertNotNull(capsule.findViewWithTag<View>("weather_segment_thumb"))
                        assertTrue(panel.chart.height > 120*activity.resources.displayMetrics.density)
                        val expectedTime=midnight(0)+11*ForecastDetailData.HOUR
                        panel.chart.selectTime(expectedTime)
                        for(metric in DailyForecastMetric.entries) {
                            val option=panel.findViewWithTag<View>("daily_metric:${metric.name}")
                            assertNull("Options share one capsule rather than separate pill backgrounds",option.background)
                            option.performClick()
                            assertEquals(metric,panel.selectedMetric)
                            assertEquals(11,field<Int>(panel.chart,"selectedIndex"))
                            assertEquals(24,field<DoubleArray>(panel.chart,"values").size)
                            assertEquals(metric.value(rows(0)[11])!!,field<DoubleArray>(panel.chart,"values")[11],0.0)
                        }
                        val visible=Rect();assertTrue(panel.getGlobalVisibleRect(visible))
                        assertEquals("Entire chart section fits in one screen",panel.height,visible.height())
                        panel.findViewWithTag<View>("daily_metric:TEMPERATURE").performClick()
                        val reading=panel.findViewWithTag<TextView>("daily_metric_reading")
                        assertTrue(reading.layout==null || reading.layout.getEllipsisCount(0)==0)
                    }
                    SystemClock.sleep(280);ins.waitForIdleSync()
                    capture(scenario,"daily-v13-${if(dark) "dark" else "light"}.png")
                    scenario.onActivity {it.findViewById<View>(android.R.id.content).findViewWithTag<View>("daily_metric:PRESSURE").performClick()}
                    scenario.recreate();SystemClock.sleep(350);ins.waitForIdleSync()
                    scenario.onActivity {activity ->
                        assertEquals(DailyForecastMetric.PRESSURE,activity.findViewById<View>(android.R.id.content).findViewWithTag<DailyForecastMetricPanel>("daily_metric_panel").selectedMetric)
                        assertEquals(11,field<Int>(activity.findViewById<View>(android.R.id.content).findViewWithTag<HourlyMetricChartView>("daily_metric_chart"),"selectedIndex"))
                    }
                }
            }
        } finally {WeatherSettings.setThemeMode(context,original);ins.runOnMainSync {WeatherSettings.applyNightMode(context)}}
    }

    @Test fun savedFullDayRollsOverWithoutCrossingCitiesOrWritingUserConfiguration() {
        val namespaces=HashSet<String>()
        val fixture=object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences {
                val isolated="v13_test_"+name;namespaces.add(isolated)
                return context.getSharedPreferences(isolated,mode)
            }
        }
        try {
            val city=CityPage("北京",116.407,39.904,false)
            val now=midnight(0)+14*ForecastDetailData.HOUR
            ForecastDayHistoryCache.save(fixture,city,rows(0),now)
            assertEquals(24,ForecastDayHistoryCache.load(fixture,city,now).size)
            val tomorrow=midnight(1)+ForecastDetailData.HOUR
            val previous=ForecastDayHistoryCache.load(fixture,city,tomorrow)
            assertEquals(24,previous.size);assertTrue(previous.all {it.isHistorical})
            assertTrue(ForecastDayHistoryCache.load(fixture,CityPage("上海",121.47,31.23,false),tomorrow).isEmpty())
            if(BuildConfig.USER_CONFIGURABLE) {
                fixture.getSharedPreferences(UserApiConfiguration.PREFS,0).edit().putLong("revision",99).commit()
                assertTrue("A new API configuration never inherits another source revision's history",ForecastDayHistoryCache.load(fixture,city,tomorrow).isEmpty())
            }
        } finally {namespaces.forEach {context.getSharedPreferences(it,0).edit().clear().commit()}}
    }

    private fun capture(scenario: ActivityScenario<ForecastDetailActivity>,name: String) {
        lateinit var window:android.view.Window
        scenario.onActivity {window=it.window}
        val bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
        val done=java.util.concurrent.CountDownLatch(1);var result=-1
        ins.runOnMainSync {PixelCopy.request(window,bitmap,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
        assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
        val folder=File(context.getExternalFilesDir(null),"weather-v13-test").apply {mkdirs()}
        File(folder,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun foregroundRepairsRetainedInvisiblePageBeforeAnyGesture() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(800);ins.waitForIdleSync()
            scenario.onActivity { activity ->
                val pager=activity.findViewById<ViewPager2>(R.id.main)
                val recycler=pager.getChildAt(0) as androidx.recyclerview.widget.RecyclerView
                val current=recycler.layoutManager!!.findViewByPosition(pager.currentItem)!!
                // Re-create the retained offscreen transform left by an interrupted layout.
                current.alpha=0f;current.visibility=View.INVISIBLE
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            SystemClock.sleep(250);ins.waitForIdleSync()
            scenario.onActivity { activity ->
                val pager=activity.findViewById<ViewPager2>(R.id.main)
                val recycler=pager.getChildAt(0) as androidx.recyclerview.widget.RecyclerView
                val current=recycler.layoutManager!!.findViewByPosition(pager.currentItem)!!
                assertTrue("Retained page recovers before a swipe",current.isShown && current.alpha>.99f)
            }
        }
    }

    @Test fun homepageForegroundCyclesNeedNoTouchToRestoreContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(1200)
            repeat(8) {
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                SystemClock.sleep(180);ins.waitForIdleSync()
                scenario.onActivity { activity ->
                    val pager=activity.findViewById<ViewPager2>(R.id.main)
                    val recycler=pager.getChildAt(0) as ViewGroup
                    val layout=(recycler as androidx.recyclerview.widget.RecyclerView).layoutManager!!
                    val current=layout.findViewByPosition(pager.currentItem)
                    assertNotNull("Selected city attached without a gesture",current)
                    assertTrue("Selected city is visible without touching",current!!.isShown && current.alpha>.99f)
                    current.findViewById<View>(R.id.refreshLayout)?.let {
                        assertTrue(it.isShown);assertTrue(it.alpha>.99f)
                    }
                }
            }
        }
    }

    @Test fun softwareTransitionSnapshotKeepsRealBlur() {
        ins.runOnMainSync {
            val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            for (y in 0 until 96) for (x in 0 until 96)
                source.setPixel(x, y, if ((x / 4) % 2 == 0) Color.BLACK else Color.WHITE)
            val card = BlurCardView(ins.targetContext).apply {
                setTint(Color.TRANSPARENT, Color.TRANSPARENT)
                setStrokeVisible(false); setEdgeShadowVisible(false)
                setBlurRadius(24f)
                layout(0, 0, 96, 96)
                setLiveSource(source, Rect(0, 0, 96, 96))
            }
            val snapshot = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            card.draw(Canvas(snapshot))
            val pixels = (28..68).map { Color.red(snapshot.getPixel(it, 48)) }
            assertTrue("Software transition must blur stripes, actual contrast=${pixels.max()-pixels.min()}",
                pixels.max() - pixels.min() < 60)
        }
    }
}
