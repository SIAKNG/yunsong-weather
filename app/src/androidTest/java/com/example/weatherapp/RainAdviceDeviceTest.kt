package com.example.weatherapp

import android.appwidget.AppWidgetManager
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class RainAdviceDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val c get()=ins.targetContext
    private val city=CityPage("虚拟数据",108.9,34.2,false)
    private fun folder()=File(c.getExternalFilesDir(null),"rain-advice-verification").apply{mkdirs()}
    private fun output(name:String,b:Bitmap){File(folder(),name).outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))}}
    private fun fixture(percent:Int,uv:Double=0.0,wind:Double=5.0,aqi:Double=25.0,sky:String="LIGHT_RAIN",night:Boolean=false):WidgetWeather {
        val now=System.currentTimeMillis();val start=ForecastDayHistoryPolicy.startOfDay(now);val date=DailyForecastDetailPolicy.dateKey(now)
        val rows=(0..23).map{i->ForecastHour(start+i*ForecastDetailData.HOUR,sky,18.0+i/6,17.0,wind,uv,64.0,aqi,precipitationProbability=percent/100.0)}
        return WidgetWeather(city,WeatherSettings.dataSource(c),now,now,19.0,18.0,sky,64.0,wind,aqi,(0..5).map{i->WidgetHour(now+i*ForecastDetailData.HOUR,19.0-i,sky,percent/100.0)},listOf(WidgetDay(date,16.0,22.0,sky)),night,rows,ForecastDaySnapshot(date,sky,22.0,16.0,percent),"06:42","17:08")
    }
    @Test fun rainAlignmentFullBarsAndConcreteSingleLineAdviceFit() {
        val issues=JSONArray();var renders=0;var maximumBottomDelta=0f
        fun issue(s:String){if(issues.length()<80)issues.put(s)}
        fun check(kind:String,w:Int,h:Int,d:WidgetWeather,tag:String) {
            val elements=mutableListOf<WeatherInfoWidgetRenderer.Element>();val b=WeatherInfoWidgetRenderer.render(c,kind,w,h,city,d,audit=elements);renders++
            val pad=if(kind=="daily_summary")18f else 14f
            for(e in elements){val r=e.bounds;if(r.left<pad-.7f||r.top<pad-.7f||r.right>w-pad+.7f||r.bottom>h-pad+.7f)issue("$tag outside ${e.label}: $r");if(e.type=="text"&&e.label.contains('…'))issue("$tag truncated ${e.label}")}
            for(i in elements.indices)for(j in i+1 until elements.size){val cross=RectF(elements[i].bounds);if(cross.intersect(elements[j].bounds)&&cross.width()>1.1f&&cross.height()>1.1f)issue("$tag overlap ${elements[i].label}/${elements[j].label}:$cross")}
            val percent=d.today!!.rainPercent!!
            if(kind=="rain") {
                val number=elements.first{it.type=="text"&&it.label==percent.toString()};val suffix=elements.single{it.type=="text"&&it.label=="%"};val icon=elements.single{it.type=="icon"}
                val delta=maxOf(abs(number.bounds.bottom-suffix.bounds.bottom),abs(number.bounds.bottom-icon.bounds.bottom));maximumBottomDelta=maxOf(maximumBottomDelta,delta);if(delta>.05f)issue("$tag baseline $delta")
            }else if(kind=="rain_trend") {
                val headline=elements.first{it.type=="text"&&it.label in listOf("未来6小时","降水概率较低")};val peak=elements.first{it.type=="text"&&it.label=="最高 $percent%"};val icon=elements.single{it.type=="icon"}
                val delta=maxOf(abs(headline.bounds.bottom-peak.bounds.bottom),abs(headline.bounds.bottom-icon.bounds.bottom));maximumBottomDelta=maxOf(maximumBottomDelta,delta);if(delta>.05f)issue("$tag baseline $delta")
                if(elements.any{it.label=="逐时降水概率"})issue("$tag redundant subtitle")
                if(percent==100&&elements.count{it.type=="text"&&it.label=="100%"}!=6)issue("$tag incomplete 100% labels")
            }else {
                val summary=WeatherWidgetSummary.build(d)
                for(advice in listOf(summary.rainAdvice,summary.windAdvice,summary.uvAdvice,summary.airAdvice)) {
                    if(advice.length>12||'\n' in advice||advice.contains("按需")||elements.none{it.type=="text"&&it.label==advice})issue("$tag non-specific or incomplete advice $advice")
                }
                if(d.forecast.first().uv!!<=1&&!summary.uvAdvice.contains("无需"))issue("$tag low UV advice")
                if(percent==0&&!summary.rainAdvice.contains("无需"))issue("$tag dry-day advice")
            }
            if(tag in listOf("rain/100/172/CLOUDY/false","rain_trend/100/356/CLOUDY/false","summary/0.0/0/356","summary/8.0/100/356"))output(tag.replace('/','-')+".png",b)
            b.recycle()
        }
        ActivityScenario.launch(WeatherWidgetActivity::class.java).use{screen->
            screen.onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            for(sky in listOf("CLEAR_DAY","PARTLY_CLOUDY_DAY","CLOUDY","LIGHT_RAIN","RAIN_SNOW","HEAVY_HAZE"))for(night in listOf(false,true))for(percent in listOf(0,1,7,10,76,100)) {
                val d=fixture(percent,sky=sky,night=night)
                for((w,h) in listOf(172 to 172,148 to 148))check("rain",w,h,d,"rain/$percent/$w/$sky/$night")
                for((w,h) in listOf(356 to 172,331 to 157))check("rain_trend",w,h,d,"rain_trend/$percent/$w/$sky/$night")
            }
            for((uv,percent) in listOf(0.0 to 0,1.0 to 0,2.0 to 5,3.0 to 20,5.0 to 30,6.0 to 60,7.0 to 76,8.0 to 100,10.0 to 90,11.0 to 100,15.0 to 100))for((w,h) in listOf(356 to 372,320 to 340,392 to 412)) {
                val d=fixture(percent,uv,if(uv<3)5.0 else 35.0,if(uv<3)25.0 else 180.0,sky="CLEAR_DAY")
                check("daily_summary",w,h,d,"summary/$uv/$percent/$w")
            }
            for(wind in listOf(0.0,5.0,6.0,12.0,24.0,25.0,39.0,40.0,50.0))for((w,h) in listOf(356 to 372,320 to 340))check("daily_summary",w,h,fixture(0,1.0,wind,25.0,"CLEAR_DAY"),"wind/$wind/$w")
            for(air in listOf(0.0,50.0,51.0,100.0,101.0,150.0,151.0,200.0,201.0,300.0,301.0,500.0))for((w,h) in listOf(356 to 372,320 to 340))check("daily_summary",w,h,fixture(0,1.0,5.0,air,"CLEAR_DAY"),"air/$air/$w")
        }
        File(folder(),"matrix.json").writeText(JSONObject().put("renders",renders).put("maximum_bottom_delta_dp",maximumBottomDelta).put("rain_values",JSONArray(listOf(0,1,7,10,76,100))).put("maximum_advice_characters",12).put("issues",issues).toString(2))
        assertEquals(issues.toString().take(6000),0,issues.length())
    }
    @Test fun nativePhoneDisplaysFullRainAndLowUvExamples() {
        ActivityScenario.launch(WeatherWidgetActivity::class.java).use{screen->
            for(kind in listOf("rain_trend","rain","daily_summary")) {
                val w=if(kind=="rain")172 else 356;val h=if(kind=="daily_summary")372 else 172
                val d=fixture(if(kind=="daily_summary")0 else 100,sky=if(kind=="daily_summary")"CLEAR_DAY"else"CLOUDY")
                screen.onActivity{a->
                    a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val root=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(20,30,20,20);setBackgroundColor(Color.rgb(18,26,40))}
                    root.addView(TextView(a).apply{text="手机实测 · 虚拟数据 · "+if(kind=="daily_summary")"UV 0.0 / 降水 0%"else"降水100%";textSize=17f;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(-1,90))
                    val frame=FrameLayout(a);val view=WeatherWidgets.views(c,-9630,kind,w,h).apply(c,frame)
                    view.findViewById<TextView>(R.id.widgetLoading).visibility=View.GONE;view.findViewById<ImageView>(R.id.widgetImage).setImageBitmap(WeatherInfoWidgetRenderer.render(c,kind,w,h,city,d))
                    frame.addView(view);root.addView(frame,LinearLayout.LayoutParams(-1,-2));a.setContentView(root)
                    val density=c.resources.displayMetrics.density;val pw=(w*density*.8f).toInt();val ph=(h*density*.8f).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(pw,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(ph,View.MeasureSpec.EXACTLY));view.layout(0,0,pw,ph)
                    val b=Bitmap.createBitmap(pw,ph,Bitmap.Config.ARGB_8888);view.draw(Canvas(b));output("native-$kind.png",b);b.recycle()
                }
                ins.waitForIdleSync();SystemClock.sleep(250);ins.uiAutomation.takeScreenshot()?.let{output("phone-$kind.png",it);it.recycle()}
            }
        }
        WeatherWidgets.restore(c)
    }
}
