package com.example.weatherapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** Opt-in documentation capture. Synthetic public-city data; no credential form screenshots. */
class ShowcaseScreenshotTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val hour = 3_600_000L
    private val base = System.currentTimeMillis() / hour * hour
    private val date = SimpleDateFormat("yyyy-MM-dd",Locale.CHINA)
    private val clock = SimpleDateFormat("HH:mm",Locale.CHINA)
    private val life = listOf(
        LifeAdviceItem("q3","穿衣指数","舒适","早晚稍凉，可备一件薄外套，随气温变化增减衣物。"),
        LifeAdviceItem("q5","紫外线指数","中等","午后外出建议涂抹防晒霜，佩戴遮阳帽。"),
        LifeAdviceItem("q1","运动指数","适宜","微风相伴，适合散步慢跑；运动后及时补水。"),
        LifeAdviceItem("q2","洗车指数","不宜","明日可能降雨，建议等天气转晴后再洗车。"),
        LifeAdviceItem("q8","舒适度指数","舒适","气温与湿度适宜，可适当开窗通风。"),
        LifeAdviceItem("q9","感冒指数","较易发","注意昼夜温差，外出增减衣物，保证睡眠。"),
        LifeAdviceItem("q6","旅游指数","适宜","天气总体适合出行，出门前关注短时降水变化。"),
        LifeAdviceItem("q7","过敏指数","易发","敏感人群外出做好防护，尽量避开花粉集中的区域。"),
        LifeAdviceItem("q12","化妆指数","保湿","建议选用保湿型护肤品，保持肌肤水润。"),
        LifeAdviceItem("q13","太阳镜指数","建议佩戴","日间光线较强，户外活动可佩戴太阳镜。")
    )
    private val rows = (-1..359).map { i ->
        val t = base + i * hour
        val h = java.util.Calendar.getInstance().apply { timeInMillis=t }.get(java.util.Calendar.HOUR_OF_DAY)
        val temp = 21 + 5 * sin((h-8)*PI/12) + sin(i/29.0)*1.8
        val rain = if(i in 14..25) 65.0 * sin((i-13)*PI/14).coerceAtLeast(0.0) else 0.0
        ForecastHour(t,if(rain>20) "LIGHT_RAIN" else if(h in 7..17) "PARTLY_CLOUDY_DAY" else "CLEAR_NIGHT",
            temp,temp+0.8,11+7*abs(sin(i/8.0)),if(h in 7..17) 6*max(0.0,sin((h-6)*PI/12)) else 0.0,
            51+13*sin(i/11.0),42+28*abs(sin(i/15.0)),i<0,135.0+i*3%100,rain,
            if(rain>0) rain/35 else 0.0,19+5*sin(i/13.0),1010+5*sin(i/20.0),18+4*cos(i/15.0))
    }
    private val points = rows.filter { !it.isHistorical }.take(24).map { r ->
        HourlyMetricPoint(r.time,r.temperature,r.feelsLike,r.precipitationProbability,r.precipitationAmountMm,
            r.uv,r.wind,r.gustKmh,r.windDirectionDegrees,r.pressureHpa,r.visibilityKm,r.humidity)
    }
    private val air = rows.filter { !it.isHistorical }.take(24).map { r ->
        AirQualityPoint(r.time,r.aqi,18.0,35.0,20.0,74.0,0.5,5.0,AirQualitySource.CAIYUN)
    }

    private fun set(target: Any,name: String,value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(target,value)
    }
    private fun call(target: Any,name: String,vararg args: Any?) {
        target.javaClass.declaredMethods.first { it.name==name && it.parameterCount==args.size }
            .apply { isAccessible=true }.invoke(target,*args)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any,name: String): T = target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target) as T

    private fun mark(activity: Activity,city: String="北京") {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.addView(TextView(activity).apply {
            text="界面演示 · $city · 气象/预警为模拟数据";textSize=9f;setTextColor(Color.WHITE)
            setBackgroundColor(0xB3222D3A.toInt());gravity=Gravity.CENTER
            setPadding(12,5,12,5);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin=(18*activity.resources.displayMetrics.density).toInt()
        })
    }
    private fun capture(activity: Activity,name: String) {
        android.os.SystemClock.sleep(700)
        val deadline=android.os.SystemClock.uptimeMillis()+5_000
        var focused=false
        while(!focused && android.os.SystemClock.uptimeMillis()<deadline) {
            ins.runOnMainSync { focused=activity.hasWindowFocus() }
            if(!focused) android.os.SystemClock.sleep(100)
        }
        assertTrue("Capture must belong to the foreground demo activity: $name, active package=${ins.uiAutomation.rootInActiveWindow?.packageName}",focused)
        val bitmap = requireNotNull(ins.uiAutomation.takeScreenshot())
        val bars = ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.systemBars())
        val top = bars?.top ?: 0
        val bottom = bars?.bottom ?: 0
        val cropped = Bitmap.createBitmap(bitmap,0,top,bitmap.width,bitmap.height-top-bottom)
        val scaled = Bitmap.createScaledBitmap(cropped,720,(cropped.height*720f/cropped.width).roundToInt(),true)
        val folder = File(context.getExternalFilesDir(null),"showcase-build5").apply { mkdirs() }
        File(folder,"$name.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG,91,it) }
        scaled.recycle();cropped.recycle();bitmap.recycle()
        ins.sendStatus(0,android.os.Bundle().apply { putString("showcase_capture",name) })
    }
    @Suppress("UNCHECKED_CAST")
    private fun <A:Activity> withActivity(intent: Intent,body:(A)->Unit) {
        val activity=ins.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as A
        try { body(activity) } finally {
            ins.runOnMainSync { activity.finish() }
            android.os.SystemClock.sleep(180)
        }
    }
    private fun screen(name: String,intent: Intent,city: String="北京",prepare: (Activity)->Unit={}) {
        withActivity<Activity>(intent) { activity ->
            ins.runOnMainSync { prepare(activity);mark(activity,city) }
            capture(activity,name)
        }
    }
    private fun restore(prefs: SharedPreferences,values: Map<String,*>) {
        val editor=prefs.edit().clear()
        values.forEach { (k,v) -> when(v) {
            is String->editor.putString(k,v);is Long->editor.putLong(k,v);is Int->editor.putInt(k,v)
            is Boolean->editor.putBoolean(k,v);is Float->editor.putFloat(k,v)
            is Set<*>->@Suppress("UNCHECKED_CAST") editor.putStringSet(k,v as Set<String>)
        } };assertTrue(editor.commit())
    }

    @Test fun captureDocumentationOnlyWhenExplicitlyRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("capture_showcase")=="true")
        val oldTheme=InstrumentationRegistry.getArguments().getString("original_theme")
            ?.takeIf { it in listOf("system","light","dark") } ?: WeatherSettings.themeMode(context)
        val cityCache=context.getSharedPreferences("city_summary_cache",Context.MODE_PRIVATE)
        val oldCities=cityCache.all.toMap()
        try {
            for(dark in listOf(false,true)) {
                WeatherSettings.setThemeMode(context,if(dark) WeatherSettings.THEME_DARK else WeatherSettings.THEME_LIGHT)
                ins.runOnMainSync { WeatherSettings.applyNightMode(context) }
                val suffix=if(dark) "dark" else "light"
                screen("forecast-48h-$suffix",HourlyForecastDetailActivity.createIntent(context,"北京市",rows,rows.first { !it.isHistorical }.temperature))
                screen("forecast-multiday-$suffix",ForecastDetailActivity.createIntent(context,"北京市",rows))
                if(dark) continue
                for(kind in MetricKind.entries) {
                    screen("metric-${kind.name.lowercase()}",MetricDetailActivity.createIntent(context,kind,points,
                        points.map { it.copy(timeMillis=it.timeMillis-24*hour,temperature=it.temperature?.minus(2),feelsLike=it.feelsLike?.minus(2)) },
                        "北京市","东南风",LifeAdviceFactory.forMetric(life,kind),life))
                }
                screen("air-quality",AirQualityDetailActivity.createIntent(context,"北京市",air,air.first(),"演示")) {
                    it.findViewById<TextView>(R.id.tvAirQualityRank).text="全国排名暂不可用（演示）"
                    it.findViewById<TextView>(R.id.tvAirQualityRankPercent).text=""
                }
                val sunrise=java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY,6);set(java.util.Calendar.MINUTE,18) }.timeInMillis
                screen("sunrise-sunset",SunDetailActivity.createIntent(context,"北京市",
                    SunAstronomyData(sunrise,sunrise+11*hour+24*60_000,sunrise-13*hour,sunrise+24*hour,39.9042,116.4074,"本地计算 · 演示","PARTLY_CLOUDY_DAY")))
                screen("life-advice",LifeAdviceActivity.createIntent(context,life))
                screen("weather-alerts",Intent(context,AlertActivity::class.java)
                    .putStringArrayListExtra("titles",arrayListOf("雷雨大风黄色预警（演示）","暴雨蓝色预警（演示）"))
                    .putStringArrayListExtra("levels",arrayListOf("黄色","蓝色"))
                    .putStringArrayListExtra("contents",arrayListOf("此为界面演示，并非真实预警。示例：预计未来数小时可能出现雷雨大风，外出注意避开广告牌及临时搭建物。","此为模拟预警信息。示例：低洼路段可能积水，请提前规划路线，避免涉水通行。"))
                    .putStringArrayListExtra("times",arrayListOf("今天 13:30","今天 12:10"))
                    .putStringArrayListExtra("locations",arrayListOf("北京市","北京市"))
                    .putStringArrayListExtra("sources",arrayListOf("模拟信息，非气象台实报","模拟信息，非气象台实报"))
                    .putStringArrayListExtra("codes",arrayListOf("1602","0201")))
                screen("api-guide-caiyun",Intent(context,ApiTutorialActivity::class.java))
                screen("api-guide-qweather",Intent(context,ApiTutorialActivity::class.java).putExtra("qweather",true))
                screen("settings",Intent(context,WeatherSettingsActivity::class.java)) {
                    it.findViewById<TextView>(R.id.tvNotificationDiagnostics).text="通知、后台运行与精确闹钟状态可在此检查。\n演示页不包含个人通知记录。"
                    it.findViewById<View>(R.id.btnAbout).requestFocus()
                    var parent=it.findViewById<View>(R.id.appInformationPanel).parent
                    while(parent!=null && parent !is ScrollView) parent=parent.parent
                    (parent as? ScrollView)?.post { parent.fullScroll(View.FOCUS_DOWN) }
                }
                for((lat,lng,temp) in listOf(Triple(39.9042,116.4074,23.0),Triple(31.2304,121.4737,25.0))) {
                    CitySummaryCache.save(context,lat,lng,temp,"多云","PARTLY_CLOUDY_DAY")
                }
                screen("cities",Intent(context,CityManageActivity::class.java)
                    .putStringArrayListExtra("cities",arrayListOf("北京","上海"))
                    .putExtra("lngs",doubleArrayOf(116.4074,121.4737)).putExtra("lats",doubleArrayOf(39.9042,31.2304))
                    .putExtra("isLoc",booleanArrayOf(false,false)).putStringArrayListExtra("details",arrayListOf("",""))
                    .putStringArrayListExtra("descs",arrayListOf("多云","多云")))
                screen("first-run-guide",Intent(context,WeatherSettingsActivity::class.java)) {
                    FirstRunGuide.show(it as AppCompatActivity,force=true) { }
                }
            }
            home("weather-home-day",false,false)
            home("weather-home-night",true,false)
            home("weather-home-rain",false,true)
        } finally {
            restore(cityCache,oldCities)
            context.getSharedPreferences("weather_settings",Context.MODE_PRIVATE).edit().putString("theme_mode",oldTheme).commit()
            ins.runOnMainSync { WeatherSettings.applyNightMode(context) }
        }
    }

    private fun home(name: String,night: Boolean,rain: Boolean) {
        withActivity<CityPreviewActivity>(Intent(context,CityPreviewActivity::class.java)
            .putExtra("city","北京").putExtra("lng",116.4074).putExtra("lat",39.9042)) { a ->
            lateinit var activity: CityPreviewActivity
            lateinit var fragment: WeatherFragment
            ins.runOnMainSync {
                activity=a;fragment=a.supportFragmentManager.findFragmentById(R.id.fragmentHost) as WeatherFragment
                for(key in listOf("fetchSeq","metricHistoryFetchSeq","alertRequestVersion","calibrationFetchSeq")) set(fragment,key,10000)
                a.findViewById<View>(R.id.topBar).visibility=View.GONE
                val root=fragment.requireView()
                root.findViewById<View>(R.id.locationBar).visibility=View.VISIBLE
                root.findViewById<View>(R.id.btnSettings).visibility=View.VISIBLE
                set(fragment,"previewMode",false)
                set(fragment,"lifeAdvice",life)
                set(fragment,"lastHigh",27.0);set(fragment,"lastLow",17.0)
                call(fragment,"showRealtime",RealTime(status="ok",temperature=23.0,apparentTemperature=24.0,
                    skycon=if(rain) "LIGHT_RAIN" else if(night) "CLEAR_NIGHT" else "CLEAR_DAY",humidity=0.56,
                    visibility=22.0,pressure=101500.0,wind=Wind(13.0,135.0),
                    airQuality=AirQuality(18.0,35.0,74.0,5.0,20.0,0.5,Aqi(42.0)),
                    lifeIndex=LifeIndex(LifeIndexItem("4","中等"))))
                root.findViewById<TextView>(R.id.tvHighLow).text="最高 27°   最低 17°"
                set(fragment,"hourlyRefMillis",base)
                set(fragment,"hourlyLabels",rows.drop(1).take(48).map { clock.format(Date(it.time)) })
                set(fragment,"hourlyTemps",rows.drop(1).take(48).map { it.temperature })
                set(fragment,"hourlySkycons",rows.drop(1).take(48).map { it.skycon })
                set(fragment,"hourlyProbs",rows.drop(1).take(48).map { it.precipitationProbability ?: 0.0 })
                call(fragment,"renderHourlyChart")
                val days=(0..14).map { DailyForecast(date.format(Date(base+it*24*hour)),if(it in 1..2) "LIGHT_RAIN" else "PARTLY_CLOUDY_DAY",27-it%4,17-it%3,if(it==1)65 else 0) }
                set(fragment,"dailySource",days);set(fragment,"primaryDaily",days)
                call(fragment,"renderDaily",days)
                set(fragment,"sunriseMillis",base-8*hour);set(fragment,"sunsetMillis",base+5*hour)
                call(fragment,"updateSunCard")
                if(rain) {
                    val amounts=(0..120).map { max(0.0,2.6*sin((it+8)*PI/100)) }
                    call(fragment,"renderMinutely",amounts,amounts.indices.map { System.currentTimeMillis()+it*60_000L },"模拟降水",1.0)
                    field<ArrayList<String>>(fragment,"alertTitles").add("雷雨大风黄色预警（演示）")
                    field<ArrayList<Int>>(fragment,"alertColors").add(Color.rgb(222,162,43))
                    call(fragment,"refreshAlertEntry")
                }
                call(fragment,"finishInitialLoading")
                call(fragment,"showDataSourceFooter")
                a.hostWeatherBackground(if(rain) "LIGHT_RAIN" else if(night) "CLEAR_NIGHT" else "CLEAR_DAY",night)
                call(fragment,"applyDefaultWeatherCardTint",if(rain) "LIGHT_RAIN" else if(night) "CLEAR_NIGHT" else "CLEAR_DAY",night)
                mark(a)
            }
            capture(activity,name)
            if(!night && !rain) {
                ins.runOnMainSync {
                    val scroll=fragment.requireView().findViewById<ScrollView>(R.id.weatherRoot)
                    val card=fragment.requireView().findViewById<View>(R.id.aqiCard)
                    val pos=IntArray(2);card.getLocationOnScreen(pos)
                    scroll.scrollBy(0,pos[1]-(125*a.resources.displayMetrics.density).toInt())
                }
                capture(activity,"weather-metrics-cards")
                ins.runOnMainSync {
                    val scroll=fragment.requireView().findViewById<ScrollView>(R.id.weatherRoot)
                    val card=fragment.requireView().findViewById<View>(R.id.dailyCard)
                    val pos=IntArray(2);card.getLocationOnScreen(pos)
                    scroll.scrollBy(0,pos[1]-(105*a.resources.displayMetrics.density).toInt())
                }
                capture(activity,"weather-daily-card")
                ins.runOnMainSync { fragment.requireView().findViewById<ScrollView>(R.id.weatherRoot).fullScroll(View.FOCUS_DOWN) }
                capture(activity,"weather-footer-life")
            }
            if(rain) {
                ins.runOnMainSync {
                    val scroll=fragment.requireView().findViewById<ScrollView>(R.id.weatherRoot)
                    val card=fragment.requireView().findViewById<View>(R.id.minutelyCard)
                    val pos=IntArray(2);card.getLocationOnScreen(pos)
                    scroll.scrollBy(0,pos[1]-(105*a.resources.displayMetrics.density).toInt())
                }
                capture(activity,"minute-rain-card")
            }
        }
    }
}
