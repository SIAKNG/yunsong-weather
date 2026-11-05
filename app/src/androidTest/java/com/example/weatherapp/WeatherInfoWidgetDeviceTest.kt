package com.example.weatherapp

import android.appwidget.*
import android.content.ComponentName
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Calendar

class WeatherInfoWidgetDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val c get()=ins.targetContext
    private val beijing=CityPage("北京",116.407,39.904,false)
    private val shanghai=CityPage("上海",121.4737,31.2304,false)
    private val hangzhou=CityPage("杭州",120.1551,30.2741,false)
    private val providers=linkedMapOf("air" to WeatherAirWidget::class.java,"rain" to WeatherRainWidget::class.java,"rain_trend" to WeatherRainTrendWidget::class.java,"cities" to WeatherCitiesWidget::class.java,"daily_summary" to WeatherDailySummaryWidget::class.java)
    private fun example(): WidgetWeather {
        val now=System.currentTimeMillis();val start=ForecastDayHistoryPolicy.startOfDay(now);val date=DailyForecastDetailPolicy.dateKey(now)
        val rows=(0..23).map {i->ForecastHour(start+i*ForecastDetailData.HOUR,"PARTLY_CLOUDY_DAY",if(i<12)15.0 else 26.0,if(i<12)14.0 else 27.0,if(i<12)6.0 else 18.0,if(i==12)4.2 else 0.0,if(i<12)48.0 else 86.0,if(i<12)38.0 else 82.0,precipitationProbability=if(i==17).72 else .1)}
        return WidgetWeather(beijing,WeatherSettings.dataSource(c),now,now,23.0,24.0,"PARTLY_CLOUDY_DAY",64.0,12.0,38.0,
            (0..5).map{i->WidgetHour(now+i*ForecastDetailData.HOUR,23.0-i,"PARTLY_CLOUDY_DAY",listOf(.1,.35,.72,.6,.3,.1)[i])},
            listOf(WidgetDay(date,15.0,26.0,"PARTLY_CLOUDY_DAY")),night=false,forecast=rows,
            today=ForecastDaySnapshot(date,"PARTLY_CLOUDY_DAY",26.0,15.0,72),sunrise="06:42",sunset="17:08")
    }
    private fun output(name: String,b: Bitmap){val folder=File(c.getExternalFilesDir(null),"info-widget-verification").apply{mkdirs()};File(folder,name).outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))}}
    @Test fun reviewedCardsRenderWithWhiteCurrentTemperatureAndNoMissingFieldsInvented() {
        val ex=example();val cityData=listOf(beijing to ex,shanghai to ex.copy(city=shanghai,sky="LIGHT_RAIN"),hangzhou to ex.copy(city=hangzhou,sky="CLOUDY"))
        for(night in listOf(false,true))for(kind in providers.keys){val square=kind in setOf("air","rain");val large=kind=="daily_summary";val w=if(square)172 else 356;val h=if(large)372 else 172
            val bitmap=WeatherWidgetRenderer.render(c,kind,w,h,beijing,ex.copy(night=night),cityData.map{it.first to it.second?.copy(night=night)})
            assertTrue(bitmap.byteCount<4_000_000);output("example-$kind-${if(night)"night" else "day"}.png",bitmap)
            if(kind=="daily_summary"){
                var white=0;val dx=bitmap.width/356f;val dy=bitmap.height/372f
                for(y in (63*dy).toInt() until (103*dy).toInt())for(x in (14*dx).toInt() until (90*dx).toInt()){val p=bitmap.getPixel(x,y);if(Color.red(p)>238&&Color.green(p)>238&&Color.blue(p)>238)white++}
                assertTrue("Current temperature must use normal white",white>30)
                assertFalse(WeatherInfoWidgetRenderer.description(c,kind,ex,emptyList()).contains("优—良"))
            }
            bitmap.recycle()
        }
        for((w,h) in listOf(356 to 340,392 to 412))for(sky in listOf("HEAVY_HAZE","LIGHT_SNOW","WIND")) {
            val missing=ex.copy(city=CityPage("天气示例长城市名称",116.407,39.904,false),temperature=-28.0,feels=null,sky=sky,forecast=ex.forecast.take(3).map{it.copy(feelsLike=null,wind=null,aqi=null,uv=null)})
            val bitmap=WeatherWidgetRenderer.render(c,"daily_summary",w,h,missing.city,missing);output("edge-$w-$h-$sky.png",bitmap);bitmap.recycle()
        }
    }
    @Test fun dailyWidgetAndActualDetailUseExactlyTheSameAdvice() {
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java)
        try {foreground.onActivity {activity->
            for(ex in listOf(example(),example().copy(today=null),example().copy(forecast=emptyList(),today=null))) {
                val page=ForecastDayPage(DailyForecastDetailPolicy.dateKey(System.currentTimeMillis()),ex.forecast,ex.today)
                val actual=ForecastWeatherPage(activity,ex.forecast,page,ex.temperature).summaryMetrics.drop(1).map{it.advice}
                val widget=WeatherWidgetSummary.build(ex)
                assertEquals(actual,listOf(widget.rainAdvice,widget.windAdvice,widget.uvAdvice,widget.airAdvice))
            }
        }} finally {foreground.close()}
    }
    @Test fun realAppWidgetHostBindsAllFiveAndMultiCitySelectionIsIsolated() {
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java).onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val host=AppWidgetHost(c,8854);val manager=AppWidgetManager.getInstance(c);val allocated=mutableListOf<Int>()
        try {
            assertEquals(10,WeatherWidgetCatalog.all(c).size)
            for((kind,provider) in providers){
                val id=host.allocateAppWidgetId();allocated+=id
                ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(c,provider)));ins.uiAutomation.dropShellPermissionIdentity()
                assertFalse(WeatherWidgetStore.isConfigured(c,id));WeatherWidgetStore.setCity(c,id,beijing)
                if(kind=="cities"){
                    WeatherWidgetStore.setExtraCities(c,id,listOf(shanghai,hangzhou));assertEquals(listOf("北京","上海","杭州"),WeatherWidgetStore.cities(c,id).map{it.name})
                    assertTrue(allocated.dropLast(1).all{WeatherWidgetStore.city(c,it)?.name=="北京"})
                }
                val pair=WeatherWidgetPolicy.size(kind);val w=if(pair.first==2)172 else 356;val h=if(pair.second==4)372 else 172
                foreground.onActivity {activity->
                    host.startListening();val view=host.createView(c,id,manager.getAppWidgetInfo(id));view.updateAppWidget(WeatherWidgets.views(c,id,kind,w,h))
                    assertNotNull(view.findViewById<View>(R.id.widgetImage))
                    val density=c.resources.displayMetrics.density;val width=(w*density).toInt();val height=(h*density).toInt()
                    val frame=FrameLayout(activity);frame.addView(view);activity.setContentView(frame)
                    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));output("actual-host-$kind.png",bitmap);bitmap.recycle()
                    view.updateAppWidgetOptions(Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,w);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,h)})
                }
            }
        }finally{ins.uiAutomation.dropShellPermissionIdentity();host.stopListening();allocated.forEach{host.deleteAppWidgetId(it);WeatherWidgetStore.delete(c,it)};WeatherWidgets.schedule(c);foreground.close()}
    }
    @Test(timeout=180_000) fun selectedSourceFeedsNewCardsAndIndependentCityCaches() {
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java).onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        try {
            val values=listOf(beijing,shanghai,hangzhou).map{city->
                if(WeatherSettings.useQWeather(c)){
                    val b=QWeatherApi.fetchAll(c,city.lat,city.lng);assertNotNull(b.now);WeatherCache.saveQWeather(c,city,b)
                    assertEquals(b.now!!.temp,WeatherWidgetData.load(c,city)!!.temperature!!,0.0)
                }else{
                    val response=WeatherDataRepository.fetchCaiyunWeatherBlocking(c,city.lng,city.lat);assertTrue(response.isSuccessful)
                    val b=response.body!!;WeatherCache.saveCaiyun(c,city,b);assertEquals(b.result!!.realtime!!.temperature,WeatherWidgetData.load(c,city)!!.temperature)
                }
                city to WeatherWidgetData.load(c,city)
            }
            values.forEach{(city,d)->assertNotNull(d);assertTrue(WeatherWidgetPolicy.sameCity(city,d!!.city));assertTrue(d.forecast.isNotEmpty())}
            val data=values.first().second!!;val summary=WeatherWidgetSummary.build(data)
            assertEquals(DailyForecastDetailPolicy.temperatureRange(ForecastDayPage(summary.date,data.forecast.filter{DailyForecastDetailPolicy.dateKey(it.time)==summary.date},data.today)),summary.temperature)
            for(kind in providers.keys){val size=WeatherWidgetPolicy.size(kind);val bitmap=WeatherWidgetRenderer.render(c,kind,if(size.first==2)172 else 356,if(size.second==4)372 else 172,beijing,data,values);output("live-$kind.png",bitmap);bitmap.recycle()}
            File(c.getExternalFilesDir(null),"info-widget-verification/live-check.json").writeText(org.json.JSONObject().put("source",WeatherWidgetData.sourceName(data.source)).put("cities",3).put("realtime_matches",true).put("summary_date",summary.date).put("hourly_count",data.forecast.size).put("partial",!summary.complete).toString(2))
            WeatherWidgets.render(c)
        }finally{foreground.close()}
    }
}
