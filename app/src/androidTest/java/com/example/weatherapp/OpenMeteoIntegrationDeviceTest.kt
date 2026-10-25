package com.example.weatherapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit live-network regression. All probes use Beijing/Shanghai, never the saved private address. */
class OpenMeteoIntegrationDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private var latestPreview: CityPreviewActivity?=null
    private val lifecycle=object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity,b: android.os.Bundle?) {if(a is CityPreviewActivity) latestPreview=a}
        override fun onActivityResumed(a: Activity) {if(a is CityPreviewActivity) latestPreview=a}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity,b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
    private fun status(text: String)=ins.sendStatus(0,android.os.Bundle().apply {putString("source_test",text)})
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any,name: String): T=target.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(target) as T
    private fun call(target: Any,name: String,vararg args: Any?): Any? =
        target.javaClass.declaredMethods.first {it.name==name && it.parameterCount==args.size}.apply {isAccessible=true}.invoke(target,*args)
    private fun screen(intent: Intent,name: String,prepare: (Activity)->Unit={}) {
        val a=ins.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ins.runOnMainSync {prepare(a)}
            android.os.SystemClock.sleep(550)
            ins.runOnMainSync {assertNotNull(a.findViewById<ViewGroup>(android.R.id.content))}
            if(name in setOf("48h-light","48h-dark","multi-light","air","sun")) {
                var focused=false;ins.runOnMainSync {focused=a.hasWindowFocus()}
                if(focused) {
                    // Capture only this application's window: excludes call bubbles, notifications and other apps.
                    val bitmap=Bitmap.createBitmap(a.window.decorView.width,a.window.decorView.height,Bitmap.Config.ARGB_8888)
                    val latch=java.util.concurrent.CountDownLatch(1)
                    var copyResult=-1
                    ins.runOnMainSync {
                        android.view.PixelCopy.request(a.window,bitmap,{code->copyResult=code;latch.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))
                    }
                    assertTrue(latch.await(5,java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals(android.view.PixelCopy.SUCCESS,copyResult)
                    val folder=File(context.getExternalFilesDir(null),"open-meteo-evidence").apply {mkdirs()}
                    File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }
            }
            status("native-page=$name opened")
        } finally {ins.runOnMainSync {a.finish()};android.os.SystemClock.sleep(150)}
    }
    private fun restore(p: SharedPreferences,values: Map<String,*>) {
        val e=p.edit().clear()
        values.forEach {(k,v)->when(v) {
            is String->e.putString(k,v);is Long->e.putLong(k,v);is Int->e.putInt(k,v)
            is Boolean->e.putBoolean(k,v);is Float->e.putFloat(k,v)
            is Set<*> -> @Suppress("UNCHECKED_CAST") e.putStringSet(k,v as Set<String>)
        }};assertTrue(e.commit())
    }
    @Test fun liveCoverageAndSourceIsolationAndNativePages() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_source_test")=="true")
        val names=listOf(UserApiConfiguration.PREFS,"weather_settings","weather_response_cache","city_summary_cache",
            "qweather_life_advice_v1","caiyun_weather_history_snapshot_v1","metric_history_timeline")
        val backup=names.associateWith {context.getSharedPreferences(it,Context.MODE_PRIVATE).all.toMap()}
        val original=UserApiConfiguration.read(context)
        val application=context.applicationContext as android.app.Application
        application.registerActivityLifecycleCallbacks(lifecycle)
        var anchor: CityPreviewActivity?=null
        try {
            UserApiConfiguration.save(context,UserApiConfiguration.Config())
            assertEquals("open_meteo",WeatherSettings.dataSource(context))
            val result=OpenMeteoSource.fetch(39.904,116.407)
            assertTrue("Beijing live free weather",result.isSuccessful)
            val response=requireNotNull(result.body)
            val report=OpenMeteoSource.coverage(response)
            report.forEach {status("coverage=${it.feature}|${it.support}|${it.detail}")}
            assertTrue(response.result!!.hourly!!.temperature!!.count {it.value!=null}>=360)
            val shanghai=OpenMeteoSource.fetch(31.230,121.474)
            assertTrue("Shanghai live free weather",shanghai.isSuccessful)
            status("Shanghai hourly=${shanghai.body?.result?.hourly?.temperature?.size} daily=${shanghai.body?.result?.daily?.temperature?.size}")

            val page=CityPage("北京",116.407,39.904,false)
            WeatherCache.saveCaiyun(context,page,response)
            assertNotNull(WeatherCache.loadCaiyun(context,page))
            CitySummaryCache.save(context,page.lat,page.lng,20.0,"晴","CLEAR_DAY")
            // No network requests with dummy credentials. Validate configuration/cache routing only.
            UserApiConfiguration.save(context,UserApiConfiguration.Config(caiyunToken="dummy_not_a_real_key"))
            assertEquals("caiyun",WeatherSettings.dataSource(context));assertNull(WeatherCache.loadCaiyun(context,page))
            assertNull(CitySummaryCache.load(context,page.lat,page.lng))
            UserApiConfiguration.save(context,UserApiConfiguration.Config(source="qweather",qweatherHost="test.qweatherapi.com",qweatherApiKey="dummy_not_a_real_key"))
            assertEquals("qweather",WeatherSettings.dataSource(context));assertNull(WeatherCache.loadQWeather(context,page))
            UserApiConfiguration.clear(context)
            assertEquals("open_meteo",WeatherSettings.dataSource(context))
            status("routing=empty->free->personalCaiyun->personalQWeather->clear->free; isolated caches passed")

            var preview=ins.startActivitySync(Intent(context,CityPreviewActivity::class.java)
                .putExtra("city","北京").putExtra("lng",116.407).putExtra("lat",39.904).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CityPreviewActivity
            anchor=preview
            lateinit var state: DetailNavigationState
            var rows=emptyList<ForecastHour>()
            try {
                val limit=android.os.SystemClock.uptimeMillis()+60_000
                var loaded=false
                while(!loaded && android.os.SystemClock.uptimeMillis()<limit) {
                    ins.runOnMainSync {
                        val f=preview.supportFragmentManager.findFragmentById(R.id.fragmentHost) as? WeatherFragment
                        loaded=f!=null && field<Double?>(f,"lastTemp")!=null && field<String>(f,"appliedSource")=="open_meteo"
                    }
                    if(!loaded) android.os.SystemClock.sleep(200)
                }
                assertTrue("Native free-source preview loaded",loaded)
                ins.runOnMainSync {
                    val f=preview.supportFragmentManager.findFragmentById(R.id.fragmentHost) as WeatherFragment
                    val metrics=field<List<HourlyMetricPoint>>(f,"hourlyMetricPoints")
                    state=call(f,"detailNavigationState",metrics) as DetailNavigationState
                    @Suppress("UNCHECKED_CAST")
                    rows=call(f,"forecastRowsForDetail") as List<ForecastHour>
                    assertTrue(metrics.size>=48)
                    assertTrue(state.yesterdayMetricPoints.size>=24)
                    assertTrue(state.airPoints.all {it.source==AirQualitySource.OPEN_METEO})
                    assertTrue(state.lifeAdvice.isEmpty())
                    assertEquals(field<Double?>(f,"lastTemp"),metrics.first().temperature)
                }
                status("native-preview=data/main/current/history/air source alignment passed")
            } finally { /* Retain this public-city host until the last detail closes (Android background launch rules). */ }
            for(dark in listOf(false,true)) {
                WeatherSettings.setThemeMode(context,if(dark) WeatherSettings.THEME_DARK else WeatherSettings.THEME_LIGHT)
                ins.runOnMainSync {WeatherSettings.applyNightMode(context)}
                val suffix=if(dark) "dark" else "light"
                screen(HourlyForecastDetailActivity.createIntent(context,"北京",rows,rows.firstOrNull {!it.isHistorical}?.temperature),"48h-$suffix")
                screen(ForecastDetailActivity.createIntent(context,"北京",rows),"multi-$suffix")
                for(kind in MetricKind.entries) screen(MetricDetailActivity.createIntent(context,kind,state.metricPoints,
                    state.yesterdayMetricPoints,"北京",state.windDirection,LifeAdviceFactory.forMetric(state.lifeAdvice,kind),state.lifeAdvice),"metric-${kind.name}-$suffix")
                screen(AirQualityDetailActivity.createIntent(context,"北京",state.airPoints,state.currentAir,"Open-Meteo 模型参考"),if(dark) "air-dark" else "air")
                screen(SunDetailActivity.createIntent(context,"北京",state.sun),if(dark) "sun-dark" else "sun")
                screen(LifeAdviceActivity.createIntent(context,emptyList()),"life-empty-$suffix")
                screen(Intent(context,WeatherSettingsActivity::class.java),"settings-$suffix")
            }
            // Theme changes recreate activities; never use the destroyed pre-theme host reference.
            ins.runOnMainSync {preview=requireNotNull(latestPreview)}
            anchor=preview
            // Verify live personal providers only when the owner has already configured them.
            for(source in listOf("caiyun","qweather")) {
                val configured=if(source=="caiyun") original.caiyunToken.isNotBlank() else original.hasQWeather()
                if(!configured) {status("personal-$source=not configured; no credential requested or fabricated");continue}
                UserApiConfiguration.save(context,original.copy(source=source))
                assertEquals(source,WeatherSettings.dataSource(context))
                val ok=if(source=="caiyun") WeatherDataRepository.fetchCaiyunWeatherBlocking(context,116.407,39.904).body?.result?.realtime!=null
                    else QWeatherApi.fetchAll(context,39.904,116.407).now!=null
                status("personal-$source live-weather=$ok; free-provider-selected=false")
                assertTrue("Configured personal weather route $source",ok)
                lateinit var f: WeatherFragment
                ins.runOnMainSync {
                    f=preview.supportFragmentManager.findFragmentById(R.id.fragmentHost) as WeatherFragment
                    call(f,"fetchWeather",116.407,39.904,false,null)
                }
                val limit=android.os.SystemClock.uptimeMillis()+60_000
                var loaded=false
                while(!loaded && android.os.SystemClock.uptimeMillis()<limit) {
                    ins.runOnMainSync {loaded=field<Double?>(f,"lastTemp")!=null && field<String>(f,"appliedSource")==source}
                    if(!loaded) android.os.SystemClock.sleep(200)
                }
                assertTrue("Native personal source switched to $source",loaded)
                ins.runOnMainSync {
                    val metrics=field<List<HourlyMetricPoint>>(f,"hourlyMetricPoints")
                    state=call(f,"detailNavigationState",metrics) as DetailNavigationState
                    @Suppress("UNCHECKED_CAST")
                    rows=call(f,"forecastRowsForDetail") as List<ForecastHour>
                    assertTrue(state.airPoints.none {it.source==AirQualitySource.OPEN_METEO})
                    assertEquals(field<Double?>(f,"lastTemp"),metrics.firstOrNull()?.temperature)
                }
                screen(HourlyForecastDetailActivity.createIntent(context,"北京",rows,rows.firstOrNull {!it.isHistorical}?.temperature),"personal-$source-48h")
                screen(ForecastDetailActivity.createIntent(context,"北京",rows),"personal-$source-multi")
                for(kind in MetricKind.entries) screen(MetricDetailActivity.createIntent(context,kind,state.metricPoints,
                    state.yesterdayMetricPoints,"北京",state.windDirection,LifeAdviceFactory.forMetric(state.lifeAdvice,kind),state.lifeAdvice),"personal-$source-${kind.name}")
                screen(AirQualityDetailActivity.createIntent(context,"北京",state.airPoints,state.currentAir,source),"personal-$source-air")
                screen(SunDetailActivity.createIntent(context,"北京",state.sun),"personal-$source-sun")
                status("native-personal-$source=all detail pages changed with no Open-Meteo air data")
            }
        } finally {
            anchor?.let {ins.runOnMainSync {it.finish()}}
            application.unregisterActivityLifecycleCallbacks(lifecycle)
            names.forEach {restore(context.getSharedPreferences(it,Context.MODE_PRIVATE),backup.getValue(it))}
            ins.runOnMainSync {WeatherSettings.applyNightMode(context)}
            assertEquals(original,UserApiConfiguration.read(context))
            status("owner-config-and-theme=restored-exactly")
        }
    }
}
