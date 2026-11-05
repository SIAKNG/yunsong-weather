package com.example.weatherapp

import android.appwidget.*
import androidx.test.core.app.ActivityScenario
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.work.WorkManager

class WeatherWidgetDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val c get()=ins.targetContext
    private val beijing=CityPage("北京",116.407,39.904,false)
    private fun example(): WidgetWeather {
        val now=System.currentTimeMillis()
        val firstHour=java.util.Calendar.getInstance().apply{timeInMillis=now;set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis
        val date=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.CHINA)
        return WidgetWeather(beijing,WeatherSettings.dataSource(c),now,now,23.0,24.0,"PARTLY_CLOUDY_DAY",45.0,12.0,36.0,
            (0..5).map{WidgetHour(firstHour+it*3_600_000L,23.0-it,"PARTLY_CLOUDY_DAY",0.0)},
            (0..7).map{WidgetDay(date.format(java.util.Date(now+it*86_400_000L)),15.0+it,26.0+it,"PARTLY_CLOUDY_DAY")})
    }
    private fun output(name: String,b: Bitmap) {
        val folder=File(c.getExternalFilesDir(null),"widget-verification").apply{mkdirs()}
        File(folder,name).outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun allTemplatesHaveCorrectGridAndRemoteViewsRenderOnDevice() {
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java).onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        foreground.onActivity {activity ->
            val root=activity.findViewById<View>(android.R.id.content)
            for(kind in listOf("compact","square","wide","weekly","large")) assertNotNull("General manager must offer $kind",root.findViewWithTag<View>("widget_add_$kind"))
        }
        val m=AppWidgetManager.getInstance(c)
        assertEquals(10,WeatherWidgetCatalog.all(c).size)
        assertTrue(WeatherWidgetCatalog.all(c).first{it.kind=="weekly"}.title.contains("五日"))
        val kinds=listOf(Triple("compact",WeatherCompactWidget::class.java,2 to 1),Triple("square",WeatherSquareWidget::class.java,2 to 2),Triple("wide",WeatherWideWidget::class.java,4 to 2),Triple("weekly",WeatherWeeklyWidget::class.java,4 to 2),Triple("large",WeatherLargeWidget::class.java,4 to 4))
        // Explicit Beijing example; never screenshot a personal home address.
        try {
            val example=example()
            for(height in listOf(140,148,160)) {
                val small=WeatherWidgetRenderer.render(c,"wide",356,height,beijing,example)
                output("wide-height-$height.png",small)
            }
            WeatherWidgetStore.setCity(c,777,beijing)
            for((kind,klass,size) in kinds) {
                val info=m.installedProviders.first{it.provider==ComponentName(c,klass)}
                if(android.os.Build.VERSION.SDK_INT>=31) {assertEquals(size.first,info.targetCellWidth);assertEquals(size.second,info.targetCellHeight)
                    assertTrue(info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE!=0)
                }
                assertEquals(ComponentName(c,WeatherWidgetActivity::class.java),info.configure)
                assertEquals("All widget sizes are fixed",AppWidgetProviderInfo.RESIZE_NONE,info.resizeMode)
                if(android.os.Build.VERSION.SDK_INT>=31) assertTrue(info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL!=0)
                for(id in m.getAppWidgetIds(ComponentName(c,klass))) {
                    val options=m.getAppWidgetOptions(id)
                    val actualW=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,356)
                    val actualH=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,172)
                    output("launcher-$kind-$id-${actualW}x${actualH}.png",WeatherWidgetRenderer.render(c,kind,actualW,actualH,beijing,example))
                }
                val w=if(kind=="square" || kind=="compact") 172 else 356
                val h=when(kind){"compact"->76;"large"->372;else->172}
                val data=example
                val bitmap=WeatherWidgetRenderer.render(c,kind,w,h,beijing,data)
                assertTrue(bitmap.byteCount<4_000_000)
                assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(0,0)))
                output("$kind.png",bitmap)
                ins.runOnMainSync {
                    val rv=WeatherWidgets.views(c,777,kind,w,h)
                    val parent=FrameLayout(c)
                    val view=rv.apply(c,parent)
                    assertTrue(view.findViewById<View>(R.id.widgetImage).hasOnClickListeners())
                    assertEquals(3,(view as android.view.ViewGroup).childCount)
                    assertTrue(view.findViewById<View>(R.id.widgetEditArea).hasOnClickListeners())
                    val stamp=view.findViewById<android.widget.TextView>(R.id.widgetEditArea)
                    val expectedSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,if(kind=="compact") 9f else 10f,c.resources.displayMetrics)
                    assertEquals("Native timestamp must use the reviewed per-template size",expectedSize,stamp.textSize,.1f)
                    assertFalse("Timestamp must not contain separator dots",stamp.text.toString().contains(" · "))
                    val reviewedHeader=WeatherWidgetRenderer.header(c,kind,w,h,beijing,data)
                    if(kind=="compact" || kind=="square") {
                        assertEquals("Small widgets put the timestamp below the city",reviewedHeader.x,reviewedHeader.stampX,.1f)
                        assertTrue(reviewedHeader.stampTop>reviewedHeader.baseline)
                    }

                    assertEquals(View.GONE,view.findViewById<View>(R.id.widgetLoading).visibility)
                    for(name in listOf("widgetRefresh","widgetStatus")) {
                        val removed=c.resources.getIdentifier(name,"id",c.packageName)
                        assertTrue(removed==0 || view.findViewById<View>(removed)==null)
                    }
                    val factor=c.resources.displayMetrics.density
                    val screenW=(w*factor).toInt();val screenH=(h*factor).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(screenW,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(screenH,View.MeasureSpec.EXACTLY))
                    view.layout(0,0,screenW,screenH)
                    val result=Bitmap.createBitmap(screenW,screenH,Bitmap.Config.ARGB_8888);view.draw(Canvas(result))
                    output("$kind-remoteviews.png",result)
                }
            }
        } finally {
            foreground.close()
            WeatherWidgetStore.delete(c,777)

        }
    }
    @Test fun reviewedCornerGeometryAndLongWeatherRenderAcrossTemplates() {
        assertEquals(20f,WeatherWidgetRenderer.cornerRadiusDp(),0f)
        assertEquals("雨夹雪",WeatherText.skyconToText("RAIN_SNOW"))
        assertEquals("雷阵雨",WeatherText.skyconToText("LIGHT_THUNDER"))
        val dims=listOf(Triple("compact",172,76),Triple("square",172,172),Triple("wide",356,172),Triple("weekly",356,172),Triple("large",356,372))
        for(sky in listOf("PARTLY_CLOUDY_DAY","HEAVY_HAZE","RAIN_SNOW","LIGHT_THUNDER")) for(night in listOf(false,true)) for((kind,w,h) in dims) {
            val data=example().copy(sky=sky,night=night)
            val bitmap=WeatherWidgetRenderer.renderWidget(c,kind,w,h,beijing,data)
            val dx=bitmap.width.toFloat()/w;val dy=bitmap.height.toFloat()/h
            for(right in listOf(false,true)) for(bottom in listOf(false,true)) {
                fun alpha(dp: Float): Int {
                    val x=(dp*dx).toInt().coerceIn(0,bitmap.width-1)
                    val y=(dp*dy).toInt().coerceIn(0,bitmap.height-1)
                    return android.graphics.Color.alpha(bitmap.getPixel(if(right) bitmap.width-1-x else x,if(bottom) bitmap.height-1-y else y))
                }
                assertTrue("Outside the 20dp curve must stay transparent: $kind",alpha(5f)<50)
                assertTrue("Inside the 20dp curve must stay filled: $kind",alpha(9f)>200)
            }
            if(sky=="HEAVY_HAZE") output("reviewed-long-$kind-${if(night) "night" else "day"}.png",bitmap)
            bitmap.recycle()
        }
    }
    @Test fun sixDayLargeWidgetUsesActualHostSizesAndKeepsShortForecastsHonest() {
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java).onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        try {
            assertTrue(c.getString(R.string.widget_large_description).contains("未来六天"))
            assertTrue(c.getString(R.string.widget_weekly_description).contains("未来五天"))
            val manager=AppWidgetManager.getInstance(c)
            val inventory=org.json.JSONArray()
            val dimensions=linkedSetOf(356 to 372,356 to 340)
            for(info in WeatherWidgetCatalog.all(c)) {
                for(id in manager.getAppWidgetIds(ComponentName(c,when(info.kind){"compact"->WeatherCompactWidget::class.java;"square"->WeatherSquareWidget::class.java;"wide"->WeatherWideWidget::class.java;"weekly"->WeatherWeeklyWidget::class.java;else->WeatherLargeWidget::class.java}))) {
                    val opts=manager.getAppWidgetOptions(id)
                    val width=opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,356)
                    val height=opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,372)
                    inventory.put(org.json.JSONObject().put("id",id).put("kind",info.kind).put("widthDp",width).put("heightDp",height))
                    if(info.kind=="large") dimensions+=width to height
                }
            }
            File(c.getExternalFilesDir(null),"widget-verification/widget-inventory.json").writeText(inventory.toString(2))
            for((width,height) in dimensions) for(sky in listOf("PARTLY_CLOUDY_DAY","HEAVY_HAZE","RAIN_SNOW","WIND")) for(night in listOf(false,true)) {
                val data=example().copy(sky=sky,night=night)
                val bitmap=WeatherWidgetRenderer.render(c,"large",width,height,beijing,data)
                output("sixdays-${width}x$height-$sky-$night.png",bitmap)
                bitmap.recycle()
            }
            for(count in listOf(0,3,7)) {
                val data=example().let{it.copy(days=it.days.take(count))}
                val bitmap=WeatherWidgetRenderer.render(c,"large",356,372,beijing,data)
                assertTrue(bitmap.byteCount<4_000_000);output("sixdays-data-count-$count.png",bitmap);bitmap.recycle()
            }
            // Existing desktop instances keep their cities and receive this APK's new layout immediately.
            WeatherWidgets.render(c)
        } finally {foreground.close()}
    }
    @Test fun allWeatherPalettesRemainReadableAndMissingFieldsDoNotCrash() {
        for(sky in listOf("CLEAR_DAY","CLEAR_NIGHT","CLOUDY","LIGHT_HAZE","LIGHT_SNOW","LIGHT_RAIN","WIND")) {
            val data=example().copy(sky=sky,temperature=null,feels=null,aqi=null,hours=emptyList(),days=emptyList())
            val bitmap=WeatherWidgetRenderer.render(c,"wide",356,172,beijing,data)
            val bottom=bitmap.getPixel(bitmap.width/2,bitmap.height-20)
            assertTrue("White footer must remain readable for $sky",androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE,bottom)>4.0)
        }
    }
    @Test(timeout=90_000) fun widgetReadsExactLiveHomepageResponseAndCachesPersist() {
        // OPPO freezes cached instrumentation when no Activity is visible; exercise real foreground loading.
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java).onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val targetThread=Thread.currentThread()
        val phase=java.util.concurrent.atomic.AtomicReference("select-source")
        val watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"widget-live-check-watchdog").apply {isDaemon=true} }
        watchdog.scheduleAtFixedRate({
            runCatching {
                val folder=File(c.getExternalFilesDir(null),"widget-verification").apply{mkdirs()}
                File(folder,"live-request-debug.txt").writeText("phase=${phase.get()}\n"+targetThread.stackTrace.joinToString("\n"))
            }
        },10,10,TimeUnit.SECONDS)
        try {
        val epoch=WeatherWidgetPolicy.sourceKey(c)
        if(WeatherSettings.useQWeather(c)) {
            phase.set("qweather-request")
            val b=QWeatherApi.fetchAll(c,beijing.lat,beijing.lng)
            assertNotNull("Selected source must provide actual weather",b.now)
            phase.set("qweather-cache-write")
            WeatherCache.saveQWeather(c,beijing,b)
            val d=WeatherWidgetData.load(c,beijing)!!
            assertEquals(b.now!!.temp,d.temperature!!,0.0)
            assertEquals(b.now!!.humidity.let{if(it<=1.0) it*100 else it},d.humidity!!,0.0)
            assertEquals(b.aqi?.aqi,d.aqi)
        } else {
            phase.set("selected-caiyun-compatible-request")
            val response=WeatherDataRepository.fetchCaiyunWeatherBlocking(c,beijing.lng,beijing.lat)
            assertTrue("Live weather request must succeed",response.isSuccessful)
            val b=response.body!!;assertNotNull(b.result?.realtime?.temperature)
            phase.set("caiyun-cache-write")
            WeatherCache.saveCaiyun(c,beijing,b)
            val d=WeatherWidgetData.load(c,beijing)!!
            assertEquals(b.result!!.realtime!!.temperature,d.temperature)
            assertEquals(b.result!!.realtime!!.apparentTemperature,d.feels)
            assertEquals(b.result!!.realtime!!.airQuality?.aqi?.chn ?: b.result!!.realtime!!.aqi?.chn,d.aqi)
            assertEquals(b.result!!.realtime!!.humidity?.times(100),d.humidity)
        }
        assertEquals(epoch,WeatherWidgetPolicy.sourceKey(c))
        val d=WeatherWidgetData.load(c,beijing)!!
        assertEquals(WeatherSettings.dataSource(c),d.source)
        assertTrue(d.saved>System.currentTimeMillis()-180_000)
        assertTrue(d.days.isNotEmpty());assertTrue(d.hours.isNotEmpty())
        val daily=d.days.associateBy{it.date.take(10)}
        for(date in WeatherWidgetPolicy.futureDates(6)) {
            assertNotNull("Selected source must cover each of the six future days: $date",daily[date])
            assertNotNull(daily[date]!!.low);assertNotNull(daily[date]!!.high)
        }
        for((kind,w,h) in listOf(Triple("compact",172,76),Triple("square",172,172),Triple("wide",356,172),Triple("weekly",356,172),Triple("large",356,372))) {
            phase.set("render-$kind")
            output("live-$kind.png",WeatherWidgetRenderer.render(c,kind,w,h,beijing,d))
        }
        val receipt="source=${WeatherWidgetData.sourceName(d.source)}\ncity=Beijing\ntemperature_matches=true\nfeels_matches=true\naqi_matches=true\nhumidity_matches=true\nhours=${d.hours.size}\ndays=${d.days.size}\nobservation=${d.observed}\n"
        File(c.getExternalFilesDir(null),"widget-verification/live-check.txt").writeText(receipt)
        } finally {watchdog.shutdownNow();foreground.close()}
    }
    @Test fun actualHostBindsRendersResizesAndRemovalCancelsPeriodicWork() {
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java).onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val host=AppWidgetHost(c,8843)
        val m=AppWidgetManager.getInstance(c)
        val id=host.allocateAppWidgetId()
        try {
            ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            assertTrue("System widget host binding",m.bindAppWidgetIdIfAllowed(id,ComponentName(c,WeatherWideWidget::class.java)))
            ins.uiAutomation.dropShellPermissionIdentity()
            WeatherWidgetStore.setCity(c,id,beijing)
            WeatherWidgets.restore(c)
            val work=WorkManager.getInstance(c).getWorkInfosForUniqueWork("weather_widget_periodic").get(10,TimeUnit.SECONDS)
            assertTrue(work.any{!it.state.isFinished})
            val previous=WorkManager.getInstance(c).getWorkInfosForUniqueWork("weather_widget_refresh_manual").get(10,TimeUnit.SECONDS).filter{it.state.isFinished}.map{it.id}.toSet()
            val clickedAt=System.currentTimeMillis()
            ins.runOnMainSync {
                host.startListening()
                val view=host.createView(c,id,m.getAppWidgetInfo(id))
                view.updateAppWidget(WeatherWidgets.views(c,id,"wide",356,172))
                assertFalse("Host must not use error view",view.findViewById<View>(R.id.widgetImage)==null)
                view.updateAppWidgetOptions(Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,320);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,190)})
                view.measure(View.MeasureSpec.makeMeasureSpec(1068,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(516,View.MeasureSpec.EXACTLY));view.layout(0,0,1068,516)
                val bitmap=Bitmap.createBitmap(1068,516,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));output("real-host.png",bitmap)
                assertTrue(foreground.let{scenario->var clicked=false;scenario.onActivity{activity ->
                    clicked=activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("widget_refresh_action").performClick()
                };clicked})
                host.stopListening()
            }
            val deadline=SystemClock.elapsedRealtime()+90_000
            var finished=false
            var lastState=""
            while(SystemClock.elapsedRealtime()<deadline && !finished) {
                val jobs=WorkManager.getInstance(c).getWorkInfosForUniqueWork("weather_widget_refresh_manual").get(10,TimeUnit.SECONDS)
                val current=jobs.filter{it.id !in previous && it.state!=androidx.work.WorkInfo.State.CANCELLED}
                lastState=current.joinToString{ "${it.state}/attempt=${it.runAttemptCount}" }+"; "+WeatherWidgetStore.status(c)
                finished=current.isNotEmpty() && current.all{it.state==androidx.work.WorkInfo.State.SUCCEEDED} &&
                    WeatherCache.savedAt(c,beijing)>=clickedAt && WeatherWidgetStore.status(c)=="刷新完成"
                if(!finished) SystemClock.sleep(500)
            }
            assertTrue("Widget refresh must update the shared cache: $lastState",finished)
            assertEquals("刷新完成",WeatherWidgetStore.status(c))
        } finally {
            foreground.close()
            ins.uiAutomation.dropShellPermissionIdentity()
            host.deleteAppWidgetId(id);WeatherWidgetStore.delete(c,id);WeatherWidgets.schedule(c)
        }
        if(WeatherWidgetStore.ids(c).isEmpty()) {
            val work=WorkManager.getInstance(c).getWorkInfosForUniqueWork("weather_widget_periodic").get(10,TimeUnit.SECONDS)
            assertTrue(work.all{it.state.isFinished})
        }
    }
    @Test fun systemReconfigureOpensCityEditorAndChangesOnlyThatWidget() {
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java).onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val host=AppWidgetHost(c,8844);val m=AppWidgetManager.getInstance(c)
        val id=host.allocateAppWidgetId()
        val other=host.allocateAppWidgetId()
        var monitor: android.app.Instrumentation.ActivityMonitor?=null
        try {
            ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            assertTrue(m.bindAppWidgetIdIfAllowed(id,ComponentName(c,WeatherSquareWidget::class.java)))
            assertTrue(m.bindAppWidgetIdIfAllowed(other,ComponentName(c,WeatherSquareWidget::class.java)))
            ins.uiAutomation.dropShellPermissionIdentity()
            WeatherWidgetStore.setCity(c,id,beijing)
            val shanghai=CityPage("上海",121.4737,31.2304,false)
            WeatherWidgetStore.setCity(c,other,shanghai)
            val citiesBefore=c.getSharedPreferences("weather_cities",Context.MODE_PRIVATE).getString("cities",null)
            monitor=ins.addMonitor(WeatherWidgetActivity::class.java.name,null,false)
            foreground.onActivity {host.startAppWidgetConfigureActivityForResult(it,id,0,7701,Bundle())}
            val editor=ins.waitForMonitorWithTimeout(monitor,8_000) ?: throw AssertionError("System reconfigure must open the city editor")
            ins.waitForIdleSync()
            assertEquals(id,editor.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1))
            ins.runOnMainSync {
                assertNotNull(editor.findViewById<View>(android.R.id.content).findViewWithTag<View>("widget_city_follow"))
                editor.findViewById<View>(android.R.id.content).findViewWithTag<View>("widget_city_follow").performClick()
            }
            ins.waitForIdleSync()
            assertTrue(WeatherWidgetStore.followsLocation(c,id))
            assertEquals(shanghai,WeatherWidgetStore.city(c,other))
            assertEquals(citiesBefore,c.getSharedPreferences("weather_cities",Context.MODE_PRIVATE).getString("cities",null))
        } finally {
            monitor?.let{ins.removeMonitor(it)}
            ins.uiAutomation.dropShellPermissionIdentity()
            host.deleteAppWidgetId(id);host.deleteAppWidgetId(other)
            WeatherWidgetStore.delete(c,id);WeatherWidgetStore.delete(c,other);WeatherWidgets.schedule(c)
            foreground.close()
        }
    }

}
