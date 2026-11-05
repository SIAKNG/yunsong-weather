package com.example.weatherapp

import android.appwidget.*
import android.content.*
import android.graphics.*
import android.view.View
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.*
import java.io.File
import kotlin.math.abs

class CityCardsAlignmentDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val c get()=ins.targetContext
    private val names=listOf(CityPage("西安市",108.9,34.2,false),CityPage("上海市",121.47,31.23,false),CityPage("乌鲁木齐市",87.6,43.8,false))
    private val skies=listOf("CLEAR_DAY","CLEAR_NIGHT","PARTLY_CLOUDY_DAY","PARTLY_CLOUDY_NIGHT","CLOUDY","LIGHT_HAZE","MODERATE_HAZE","HEAVY_HAZE","LIGHT_RAIN","MODERATE_RAIN","HEAVY_RAIN","STORM_RAIN","LIGHT_THUNDER","MODERATE_THUNDER","HEAVY_THUNDER","STORM_THUNDER","FOG","LIGHT_SNOW","MODERATE_SNOW","HEAVY_SNOW","STORM_SNOW","LIGHT_SLEET","MODERATE_SLEET","HEAVY_SLEET","RAIN_SNOW","DUST","SAND","WIND")
    private fun folder()=File(c.getExternalFilesDir(null),"city-alignment-verification").apply{mkdirs()}
    private fun output(name:String,b:Bitmap){File(folder(),name).outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))}}
    private fun fixture(city:CityPage,sky:String="LIGHT_RAIN",temp:Double?=19.0,feels:Double?=18.0,night:Boolean=false):WidgetWeather {
        val now=System.currentTimeMillis();val date=DailyForecastDetailPolicy.dateKey(now)
        return WidgetWeather(city,WeatherSettings.dataSource(c),now,now,temp,feels,sky,64.0,12.0,38.0,emptyList(),listOf(WidgetDay(date,-32.0,42.0,sky)),night=night)
    }
    @Test fun allWeatherTextLengthsAndTemperatureUnitsFitAndAlignOnDevice() {
        val original=WeatherSettings.tempUnit(c);val issues=JSONArray();val diagnostics=JSONArray();var count=0;var maxCenterDelta=0f;var maxCaptionDelta=0f
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java).onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        fun issue(value:String){if(issues.length()<60)issues.put(value)}
        try {
            for(unit in listOf("c","f")) {
                WeatherSettings.setTempUnit(c,unit)
                for(sky in skies)for(night in listOf(false,true))for(cities in 2..3)for(width in listOf(320,340,356))for(temp in listOf(19.0,-28.0,42.0,null)) {
                    val values=names.take(cities).map{it to fixture(it,sky,temp,if(temp==null)null else temp-1,night)}
                    val elements=mutableListOf<WeatherInfoWidgetRenderer.Element>()
                    val b=WeatherInfoWidgetRenderer.render(c,"cities",width,if(width==340)166 else 172,names[0],values[0].second,values,elements);count++
                    val label="$unit/$sky/$night/$cities/$width/$temp"
                    val height=if(width==340)166 else 172
                    for(e in elements)if(e.bounds.left<13.4f||e.bounds.top<13.4f||e.bounds.right>width-13.4f||e.bounds.bottom>height-13.4f)issue("$label outside ${e.label}: ${e.bounds}")
                    for(i in elements.indices)for(j in i+1 until elements.size){val cross=RectF(elements[i].bounds);if(cross.intersect(elements[j].bounds)&&cross.width()>1.1f&&cross.height()>1.1f)issue("$label overlap ${elements[i].label}/${elements[j].label}")}
                    val icons=elements.filter{it.type=="icon"};val temperatures=elements.filter{it.type=="text"&&it.label==WeatherSettings.tempText(c,temp)}
                    val weather=WeatherText.skyconToText(sky);val captions=elements.filter{it.type=="text"&&it.label==weather}
                    if(icons.size!=cities||temperatures.size!=cities||captions.size!=cities){issue("$label missing/truncated current or weather text");if(diagnostics.length()<6)diagnostics.put(JSONObject().put("case",label).put("icons",icons.size).put("temperatures",temperatures.size).put("captions",captions.size).put("actual_labels",JSONArray(elements.filter{it.type=="text"}.map{it.label})))}
                    for(i in 0 until minOf(icons.size,temperatures.size,captions.size)){
                        val dy=abs(icons[i].bounds.centerY()-temperatures[i].bounds.centerY());maxCenterDelta=maxOf(maxCenterDelta,dy)
                        val dx=abs(icons[i].bounds.centerX()-captions[i].bounds.centerX());maxCaptionDelta=maxOf(maxCaptionDelta,dx)
                        if(dy>.05f||dx>.05f||captions[i].bounds.top<icons[i].bounds.bottom)issue("$label alignment $dy/$dx")
                    }
                    val stamp=WeatherInfoWidgetRenderer.cityUpdateText(c,values)
                    val stamps=elements.filter{it.type=="text"&&it.label==stamp}
                    if(stamps.size!=1||abs(stamps[0].bounds.right-(width-14))>.05f||(cities==3&&abs(stamps[0].bounds.bottom-(height-14))>.05f))issue("$label timestamp margin")
                    if(elements.any{it.label=="三城天气"||it.label=="两城天气"||it.label=="西安市"||it.label=="上海市"||it.label=="乌鲁木齐市"})issue("$label old heading/city suffix")
                    if(unit=="c"&&!night&&width==356&&temp==19.0&&sky in listOf("CLEAR_DAY","PARTLY_CLOUDY_DAY","LIGHT_RAIN","LIGHT_THUNDER","LIGHT_SLEET","HEAVY_HAZE"))output("length-$cities-$sky.png",b)
                    if(unit=="f"&&!night&&width==340&&temp==-28.0&&sky=="HEAVY_HAZE")output("fahrenheit-tight-$cities.png",b)
                    b.recycle()
                }
            }
            val report=JSONObject().put("renders",count).put("sky_codes",skies.size).put("text_lengths",JSONArray(listOf(1,2,3,4))).put("units",JSONArray(listOf("c","f"))).put("max_icon_temperature_center_delta_dp",maxCenterDelta).put("max_caption_icon_center_delta_dp",maxCaptionDelta).put("issues",issues).put("diagnostics",diagnostics)
            File(folder(),"alignment-matrix.json").writeText(report.toString(2))
            assertEquals(issues.toString().take(3000),0,issues.length())
        } finally {WeatherSettings.setTempUnit(c,original);foreground.close()}
    }
    @Test fun nativeWidgetHostShowsBothLayoutsAndCompleteLongWeatherLabels() {
        val original=WeatherSettings.tempUnit(c);WeatherSettings.setTempUnit(c,"c")
        val host=AppWidgetHost(c,8891);val ids=mutableListOf<Int>()
        val hostSamples=mutableListOf<Triple<AppWidgetHostView,RemoteViews,Int>>()
        ActivityScenario.launch(WeatherWidgetActivity::class.java).use {screen->
            try {
                screen.onActivity {a->
                    a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val d=c.resources.displayMetrics.density;fun px(v:Int)=(v*d).toInt()
                    val cardWidth=minOf(356,(a.resources.displayMetrics.widthPixels/d).toInt()-32)
                    val root=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(px(16),px(24),px(16),px(16));setBackgroundColor(Color.rgb(16,27,42))}
                    root.addView(TextView(a).apply{text="多城天气 · 实机排布检查（虚拟数据）";textSize=17f;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=px(24)})
                    host.startListening()
                    for(count in 2..3){
                        val id=host.allocateAppWidgetId();ids+=id;ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                        try {assertTrue(AppWidgetManager.getInstance(c).bindAppWidgetIdIfAllowed(id,ComponentName(c,WeatherCitiesWidget::class.java)))}finally{ins.uiAutomation.dropShellPermissionIdentity()}
                        WeatherWidgetStore.register(c,id);assertTrue(WeatherWidgetStore.setCities(c,id,names[0],names.drop(1).take(count-1),false))
                        val rows=names.take(count).mapIndexed{i,city->city to fixture(city,listOf("LIGHT_RAIN","HEAVY_HAZE","LIGHT_THUNDER")[i],listOf(19.0,23.0,-8.0)[i],listOf(18.0,22.0,-12.0)[i])}
                        val bitmap=WeatherInfoWidgetRenderer.render(c,"cities",cardWidth,172,names[0],rows[0].second,rows)
                        // Resolve IDs from the installed release, not the debug variant used to compile instrumentation.
                        val imageId=c.resources.getIdentifier("widgetImage","id",c.packageName)
                        val loadingId=c.resources.getIdentifier("widgetLoading","id",c.packageName)
                        val editId=c.resources.getIdentifier("widgetEditArea","id",c.packageName)
                        val rv=WeatherWidgets.views(c,id,"cities",cardWidth,172).apply{setImageViewBitmap(imageId,bitmap);setViewVisibility(loadingId,View.GONE);setViewVisibility(editId,View.GONE);setContentDescription(imageId,WeatherInfoWidgetRenderer.description(c,"cities",rows[0].second,rows))}
                        val view=host.createView(c,id,AppWidgetManager.getInstance(c).getAppWidgetInfo(id));view.updateAppWidget(rv)
                        root.addView(view,LinearLayout.LayoutParams(px(cardWidth),px(172)).apply{bottomMargin=px(26)})
                        view.measure(View.MeasureSpec.makeMeasureSpec(px(cardWidth),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(px(172),View.MeasureSpec.EXACTLY));view.layout(0,0,px(cardWidth),px(172))
                        hostSamples+=Triple(view,rv,count)
                    }
                    a.setContentView(root)
                }
                ins.waitForIdleSync();Thread.sleep(600)
                screen.onActivity {
                    // Test fixtures must not be replaced by a real no-cache refresh arriving
                    // after binding. Stop callbacks, then show the formal renderer in its host.
                    host.stopListening()
                    for((view,rv,count) in hostSamples){
                        view.updateAppWidget(rv)
                        val imageId=c.resources.getIdentifier("widgetImage","id",c.packageName)
                        val image=view.findViewById<View>(imageId);assertNotNull("Installed release widget image",image)
                        assertTrue(image.contentDescription.toString().contains("西安"))
                        val shot=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(shot));output("native-host-aligned-$count.png",shot);shot.recycle()
                    }
                }
                ins.waitForIdleSync();Thread.sleep(150)
                val screenshot=ins.uiAutomation.takeScreenshot();assertNotNull(screenshot);output("phone-aligned-host-screen.png",screenshot!!);screenshot.recycle()
            } finally {WeatherSettings.setTempUnit(c,original);host.stopListening();ids.forEach{host.deleteAppWidgetId(it);WeatherWidgetStore.delete(c,it)};WeatherWidgets.restore(c)}
        }
    }
    @Test fun cityDisplayBindingAndActualManagementPageDoNotLoseText() {
        ActivityScenario.launch(CityManageActivity::class.java).use {screen->
            screen.onActivity {a->
                val t=TextView(a);WeatherAddressFormatter.bind(t,"西安市");assertEquals("西安",t.text.toString());WeatherAddressFormatter.bind(t,"西安市 雁塔区 科技路");assertEquals("西安市 雁塔区 科技路",t.text.toString())
            }
            ins.waitForIdleSync();Thread.sleep(350)
            screen.onActivity{a->
                fun visit(v:View){if(v is TextView&&v.isShown&&v.text.isNotBlank()) {val layout=v.layout;if(layout!=null){assertTrue("Negative text width",layout.width>=0);assertTrue("City management text exceeds right edge: ${v.text}",v.right<=((v.parent as? View)?.width?:Int.MAX_VALUE)+1)}};if(v is android.view.ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))}
                visit(a.window.decorView)
            }
            val shot=ins.uiAutomation.takeScreenshot();assertNotNull(shot);output("phone-city-management.png",shot!!);shot.recycle()
        }
    }
    @Test fun airQualityStatusSharesTheIndexBottomWithoutTruncatingAnyLevel() {
        val issues=JSONArray();var count=0;var maxDelta=0f
        for(aqi in listOf(35.0,75.0,125.0,175.0,250.0,380.0,null))for(width in listOf(166,172))for(sky in listOf("CLEAR_DAY","LIGHT_RAIN","CLEAR_NIGHT")) {
            val d=fixture(names[0],sky,night=sky=="CLEAR_NIGHT").copy(aqi=aqi)
            val e=mutableListOf<WeatherInfoWidgetRenderer.Element>()
            val b=WeatherInfoWidgetRenderer.render(c,"air",width,width,names[0],d,audit=e);count++
            val numeric=aqi?.toInt()?.toString()?:"—";val level=AirQualityScale.level(aqi)
            val numbers=e.filter{it.type=="text"&&it.label==numeric};val labels=e.filter{it.type=="text"&&it.label==level}
            if(numbers.size!=1||labels.size!=1)issues.put("$aqi/$width/$sky incomplete $numeric/$level")
            else {val delta=abs(numbers[0].bounds.bottom-labels[0].bounds.bottom);maxDelta=maxOf(maxDelta,delta);if(delta>.05f)issues.put("$aqi/$width/$sky bottom $delta")}
            for(item in e)if(item.bounds.left<13.4f||item.bounds.top<13.4f||item.bounds.right>width-13.4f||item.bounds.bottom>width-13.4f)issues.put("$aqi/$width/$sky outside ${item.label}")
            for(i in e.indices)for(j in i+1 until e.size){val cross=RectF(e[i].bounds);if(cross.intersect(e[j].bounds)&&cross.width()>1.1f&&cross.height()>1.1f)issues.put("$aqi/$width/$sky overlap ${e[i].label}/${e[j].label}")}
            if(width==172&&sky=="LIGHT_RAIN")output("air-baseline-${aqi?.toInt()?:"missing"}.png",b)
            b.recycle()
        }
        File(folder(),"air-baseline-matrix.json").writeText(JSONObject().put("renders",count).put("levels",7).put("max_bottom_delta_dp",maxDelta).put("issues",issues).toString(2))
        assertEquals(issues.toString().take(3000),0,issues.length())
    }
}
