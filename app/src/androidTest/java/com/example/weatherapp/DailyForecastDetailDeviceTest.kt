package com.example.weatherapp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Calendar

/** All UI fixtures are Beijing simulations, never a saved personal city or API configuration. */
class DailyForecastDetailDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun day(offset: Int): Long=Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);add(Calendar.DATE,offset)
    }.timeInMillis
    private fun key(offset: Int)=DailyForecastDetailPolicy.dateKey(day(offset))
    private fun hours()=(0..2).flatMap {d -> (0..23).map {h ->
        val temp=when(d) {1 ->28.0+h%7;2 ->5.0+h%10;else ->18.0+h%7}
        ForecastHour(day(d)+h*ForecastDetailData.HOUR,"CLEAR_DAY",temp,temp-1,8.0+h%5,
            if(h in 8..17) 3.0 else 0.0,50.0,30.0+h%8,
            isHistorical=day(d)+h*ForecastDetailData.HOUR<System.currentTimeMillis()/ForecastDetailData.HOUR*ForecastDetailData.HOUR,
            precipitationProbability=0.0,windDirectionDegrees=135.0)
    }}
    private fun daily()=listOf(
        DailyForecast(key(-1),"CLOUDY",21,10,null),DailyForecast(key(0),"CLEAR_DAY",26,16,0),
        DailyForecast(key(1),"CLEAR_DAY",36,28,0),DailyForecast(key(2),"CLOUDY",15,5,60))+
        (3..14).map {DailyForecast(key(it),"CLOUDY",17,8,null)}
    @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any,name: String): T=
        target.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(target) as T
    private fun set(target: Any,name: String,value: Any) {target.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(target,value)}
    private fun pager(a: Activity)=a.findViewById<View>(android.R.id.content).findViewWithTag<ViewPager2>("daily_forecast_pager")
    private fun page(a: Activity,date: String)=field<HashMap<String,ForecastWeatherPage>>(a,"pages").getValue(date)
    private fun settle() {SystemClock.sleep(400);ins.waitForIdleSync()}

    @Test fun chartDragAndCancelNeverOpenADay() {
        ins.runOnMainSync {
            val chart=DailyTrendView(context)
            chart.setData(listOf("今天","明天","后天"),listOf("1/1","1/2","1/3"),List(3){"CLEAR_DAY"},List(3){0.0},List(3){20},List(3){10})
            chart.setShowCount(3);chart.layout(0,0,900,600)
            var count=0;chart.onDayClick={count++}
            var time=SystemClock.uptimeMillis()
            touch(chart,time,time,MotionEvent.ACTION_DOWN,100f,200f)
            touch(chart,time,time+100,MotionEvent.ACTION_UP,700f,200f)
            assertEquals(0,count)
            time+=200
            touch(chart,time,time,MotionEvent.ACTION_DOWN,100f,200f)
            touch(chart,time,time+100,MotionEvent.ACTION_CANCEL,100f,200f)
            assertEquals(0,count)
            time+=200
            touch(chart,time,time,MotionEvent.ACTION_DOWN,100f,200f)
            touch(chart,time,time+100,MotionEvent.ACTION_UP,100f,200f)
            assertEquals(1,count)
        }
    }

    @Test fun dailyWindKeepsTheSelectedUnit() {
        val original=WeatherSettings.windUnit(context)
        try {
            WeatherSettings.setWindUnit(context,WeatherSettings.WIND_MS)
            ActivityScenario.launch<ForecastDetailActivity>(ForecastDetailActivity.createIntent(context,"北京市",hours(),key(1),daily())).use {scenario ->
                settle()
                scenario.onActivity {a ->
                    val panel=page(a,key(1)).view.findViewWithTag<DailyForecastMetricPanel>("daily_metric_panel")
                    panel.findViewWithTag<View>("daily_metric:WIND").performClick()
                    assertEquals(8.0,field<DoubleArray>(panel.chart,"values").first(),0.0)
                    assertTrue(panel.findViewWithTag<android.widget.TextView>("daily_metric_reading").text.contains("m/s"))
                }
            }
        } finally {WeatherSettings.setWindUnit(context,original)}
    }

    @Test fun shared48HourPageKeepsItsWholeWindowAndCurrentTemperature() {
        ActivityScenario.launch<HourlyForecastDetailActivity>(HourlyForecastDetailActivity.createIntent(context,"北京市",hours(),21.0)).use {scenario ->
            settle()
            scenario.onActivity {a ->
                val body=a.findViewById<View>(android.R.id.content).findViewWithTag<View>("forecast_page:48h")
                assertNotNull(body)
                fun summary(v: View): HourlyForecastSummaryView? {
                    if(v is HourlyForecastSummaryView) return v
                    if(v is android.view.ViewGroup) repeat(v.childCount) {summary(v.getChildAt(it))?.let {return it}}
                    return null
                }
                val metrics=field<List<HourlyForecastSummaryMetric>>(summary(body)!!,"metrics")
                assertEquals(18.0,metrics[0].minimumTemperatureCelsius!!,0.0)
                assertEquals(34.0,metrics[0].maximumTemperatureCelsius!!,0.0)
                assertEquals(WeatherSettings.tempText(a,21.0),metrics[0].currentValue)
                assertTrue(metrics[2].detail.contains("48小时"))
                assertEquals(5,metrics.size)
            }
        }
    }

    @Test fun selectedDaySummaryPagingMissingHoursAndRecreationInBothThemes() {
        val original=WeatherSettings.themeMode(context)
        try {
            for(dark in listOf(false,true)) {
                WeatherSettings.setThemeMode(context,if(dark) WeatherSettings.THEME_DARK else WeatherSettings.THEME_LIGHT)
                ins.runOnMainSync {WeatherSettings.applyNightMode(context)}
                val intent=ForecastDetailActivity.createIntent(context,"北京市",hours(),key(1),daily(),21.0)
                ActivityScenario.launch<ForecastDetailActivity>(intent).use { scenario ->
                    settle()
                    scenario.onActivity {a ->
                        assertEquals(2,pager(a).currentItem)
                        val selected=page(a,key(1))
                        assertEquals(28.0,selected.summaryMetrics[0].minimumTemperatureCelsius!!,0.0)
                        assertEquals(36.0,selected.summaryMetrics[0].maximumTemperatureCelsius!!,0.0)
                        assertNull(selected.summaryMetrics[0].currentValue)
                        assertFalse(selected.summaryMetrics.any {it.detail.contains("48小时") || it.advice.contains("后日")})
                        assertTrue(field<List<ForecastHour>>(selected,"points").all {DailyForecastDetailPolicy.dateKey(it.time)==key(1)})
                    }
                    capture(scenario,"daily-${if(dark) "dark" else "light"}.png")
                    scenario.onActivity {pager(it).setCurrentItem(3,false)};settle()
                    scenario.onActivity {a ->
                        assertEquals(5.0,page(a,key(2)).summaryMetrics[0].minimumTemperatureCelsius!!,0.0)
                        assertEquals(15.0,page(a,key(2)).summaryMetrics[0].maximumTemperatureCelsius!!,0.0)
                        assertEquals("60%",page(a,key(2)).summaryMetrics[1].value)
                        pager(a).setCurrentItem(10,false)
                    };settle()
                    scenario.onActivity {a ->
                        val selected=page(a,key(9))
                        assertTrue(field<List<ForecastHour>>(selected,"points").isEmpty())
                        assertEquals(8.0,selected.summaryMetrics[0].minimumTemperatureCelsius!!,0.0)
                        assertTrue(selected.view.findViewWithTag<android.widget.TextView>("daily_history_status").text.contains("未提供"))
                    }
                    scenario.recreate();settle()
                    scenario.onActivity {a ->
                        assertEquals(10,pager(a).currentItem)
                        page(a,key(9)).view.findViewWithTag<View>("forecast_return_current").performClick()
                    };settle()
                    scenario.onActivity {a ->assertEquals(1,pager(a).currentItem)}
                    scenario.onActivity {a ->a.findViewById<View>(android.R.id.content).findViewWithTag<View>("daily_date:${key(14)}").performClick()};settle()
                    scenario.onActivity {a ->
                        assertEquals(15,pager(a).currentItem)
                        val chip=a.findViewById<View>(android.R.id.content).findViewWithTag<View>("daily_date:${key(14)}")
                        val bounds=android.graphics.Rect();assertTrue(chip.getGlobalVisibleRect(bounds));assertEquals(chip.width,bounds.width())
                    }
                }
            }
        } finally {WeatherSettings.setThemeMode(context,original);ins.runOnMainSync {WeatherSettings.applyNightMode(context)}}
    }

    @Test fun summaryDragPagesButHourlyDragKeepsTheDay() {
        ActivityScenario.launch<ForecastDetailActivity>(ForecastDetailActivity.createIntent(context,"北京市",hours(),key(1),daily())).use {scenario ->
            settle()
            var moved=false
            scenario.onActivity {a ->pager(a).registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int,positionOffset: Float,positionOffsetPixels: Int) {if(positionOffset>.05f) moved=true}
            })}
            var started=0L
            scenario.onActivity {a ->
                val p=pager(a);started=SystemClock.uptimeMillis();touch(p,started,started,MotionEvent.ACTION_DOWN,p.width*.8f,40f)
                touch(p,started,started+60,MotionEvent.ACTION_MOVE,p.width*.72f,40f)
                touch(p,started,started+140,MotionEvent.ACTION_MOVE,p.width*.30f,40f)
            }
            SystemClock.sleep(60)
            scenario.onActivity {a ->
                assertTrue("Day pager follows the drag before release",moved)
                val p=pager(a);touch(p,started,started+220,MotionEvent.ACTION_UP,p.width*.2f,40f)
            };settle()
            scenario.onActivity {a ->pager(a).setCurrentItem(2,false)};settle()
            scenario.onActivity {a ->
                val chart=page(a,key(1)).view.findViewWithTag<HourlyMetricChartView>("daily_metric_chart")
                val time=SystemClock.uptimeMillis()
                touch(chart,time,time,MotionEvent.ACTION_DOWN,chart.width*.8f,chart.height*.5f)
                touch(chart,time,time+100,MotionEvent.ACTION_MOVE,chart.width*.25f,chart.height*.5f)
                touch(chart,time,time+200,MotionEvent.ACTION_UP,chart.width*.25f,chart.height*.5f)
                assertEquals(2,pager(a).currentItem)
                assertTrue(field<Int>(chart,"selectedIndex") in 4..7)
            }
        }
    }

    @Test fun homeListAndChartOpenTheirExactDateWithColumnFeedback() {
        val intent=Intent(context,CityPreviewActivity::class.java).putExtra("city","北京").putExtra("lat",39.904).putExtra("lng",116.407)
        ActivityScenario.launch<CityPreviewActivity>(intent).use {scenario ->
            settle()
            lateinit var fragment: WeatherFragment
            scenario.onActivity {a ->
                fragment=a.supportFragmentManager.findFragmentById(R.id.fragmentHost) as WeatherFragment
                set(fragment,"fetchSeq",field<Int>(fragment,"fetchSeq")+1)
                set(fragment,"hourlyMetricPoints",hours().map {HourlyMetricPoint(it.time,temperature=it.temperature,feelsLike=it.feelsLike,windKmh=it.wind,uv=it.uv,humidityPercent=it.humidity,precipitationProbability=it.precipitationProbability)})
                set(fragment,"dailySource",daily())
                fragment.javaClass.getDeclaredMethod("renderDaily",List::class.java).apply {isAccessible=true}.invoke(fragment,daily())
                val weather=fragment.requireView().findViewById<ScrollView>(R.id.weatherRoot)
                val card=fragment.requireView().findViewById<View>(R.id.dailyCard)
                weather.scrollTo(0,card.top)
            };settle()
            openAndCheckDate(key(1)) {
                scenario.onActivity {field<ArrayList<View>>(fragment,"dailyRows")[2].performClick()}
            }
            scenario.onActivity {
                fragment.javaClass.getDeclaredMethod("setDailyMode",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(fragment,true,false)
            };settle()
            openAndCheckDate(key(2)) {
                scenario.onActivity {
                    val chart=field<DailyTrendView>(fragment,"dailyChart")
                    val bounds=DailyForecastDetailPolicy.columnBounds(3,chart.width.toFloat(),32f*chart.resources.displayMetrics.density,daily().size)
                    val x=(bounds.first+bounds.second)/2
                    val time=SystemClock.uptimeMillis()
                    touch(chart,time,time,MotionEvent.ACTION_DOWN,x,chart.height*.75f)
                    assertTrue(chart.isPressed)
                    val bitmap=Bitmap.createBitmap(chart.width,chart.height,Bitmap.Config.ARGB_8888)
                    chart.draw(Canvas(bitmap))
                    assertTrue(android.graphics.Color.alpha(bitmap.getPixel(x.toInt(),chart.height-2))>0)
                    bitmap.recycle()
                    touch(chart,time,time+100,MotionEvent.ACTION_UP,x,chart.height*.75f)
                }
            }
            scenario.onActivity {field<HorizontalScrollView>(fragment,"dailyChartScroll").scrollTo(Int.MAX_VALUE,0)};settle()
            openAndCheckDate(key(14)) {
                scenario.onActivity {
                    val chart=field<DailyTrendView>(fragment,"dailyChart")
                    val time=SystemClock.uptimeMillis()
                    touch(chart,time,time,MotionEvent.ACTION_DOWN,chart.width-8f,chart.height*.35f)
                    touch(chart,time,time+100,MotionEvent.ACTION_UP,chart.width-8f,chart.height*.35f)
                }
            }
        }
    }

    private fun openAndCheckDate(expected: String,action: ()->Unit) {
        val monitor=ins.addMonitor(ForecastDetailActivity::class.java.name,null,false)
        var opened: Activity?=null
        try {
            action();opened=ins.waitForMonitorWithTimeout(monitor,8000)
            assertNotNull("Card click should launch day details",opened);settle()
            ins.runOnMainSync {
                assertEquals(expected,field<List<ForecastDayPage>>(opened!!,"days")[pager(opened!!).currentItem].date)
            }
        } finally {opened?.let {a ->ins.runOnMainSync {a.finish()}};ins.removeMonitor(monitor);settle()}
    }

    private fun touch(view: View,down: Long,time: Long,action: Int,x: Float,y: Float) {
        MotionEvent.obtain(down,time,action,x,y,0).also {view.dispatchTouchEvent(it);it.recycle()}
    }
    private fun capture(scenario: ActivityScenario<ForecastDetailActivity>,name: String) {
        lateinit var window: android.view.Window
        scenario.onActivity {window=it.window}
        val bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
        val done=java.util.concurrent.CountDownLatch(1);var result=-1
        ins.runOnMainSync {PixelCopy.request(window,bitmap,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
        assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
        val folder=File(context.getExternalFilesDir(null),"daily-detail-test").apply {mkdirs()}
        File(folder,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
