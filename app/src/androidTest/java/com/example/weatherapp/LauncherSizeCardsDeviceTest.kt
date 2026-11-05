package com.example.weatherapp

import android.appwidget.AppWidgetManager
import android.graphics.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.*
import java.io.File

class LauncherSizeCardsDeviceTest {
    @Test fun actualDesktopOptionsKeepEveryPlacedTemplateComplete() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(c.getExternalFilesDir(null),"launcher-size-verification").apply{mkdirs()}
        val manager=AppWidgetManager.getInstance(c);val inventory=JSONArray();val issues=JSONArray();var renders=0
        val original=WeatherSettings.tempUnit(c)
        ActivityScenario.launch(WeatherWidgetActivity::class.java).use {screen->
            screen.onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try {
                WeatherSettings.setTempUnit(c,"c")
                for(template in WeatherWidgetCatalog.all(c))for(id in manager.getAppWidgetIds(template.provider)) {
                    val o=manager.getAppWidgetOptions(id);val fallback=WeatherWidgetPolicy.size(template.kind)
                    val w=o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,fallback.first*72-16).coerceAtLeast(120)
                    val h=o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,fallback.second*82-16).coerceAtLeast(64)
                    inventory.put(JSONObject().put("id",id).put("kind",template.kind).put("width_dp",w).put("height_dp",h))
                    val now=System.currentTimeMillis();val start=ForecastDayHistoryPolicy.startOfDay(now);val date=DailyForecastDetailPolicy.dateKey(now)
                    val city=WeatherWidgetStore.city(c,id)?:CityPage("西安市",108.9,34.2,false)
                    val cityValues=WeatherWidgetStore.cities(c,id).takeIf{it.size>=2}?:listOf(city,CityPage("上海市",121.47,31.23,false),CityPage("渭南市",109.5,34.5,false))
                    for(sky in listOf("CLEAR_DAY","PARTLY_CLOUDY_DAY","LIGHT_THUNDER","HEAVY_HAZE")) {
                        val rows=(0..23).map{i->ForecastHour(start+i*ForecastDetailData.HOUR,sky,if(i<12)15.0 else 26.0,if(i<12)14.0 else 27.0,if(i<12)4.0 else 18.0,if(i==12)4.2 else 0.0,64.0,if(i<12)38.0 else 82.0,precipitationProbability=.72)}
                        val d=WidgetWeather(city,WeatherSettings.dataSource(c),now,now,19.0,18.0,sky,64.0,12.0,125.0,(0..5).map{i->WidgetHour(now+i*ForecastDetailData.HOUR,19.0-i,sky,.12*i)},(1..7).map{i->WidgetDay(DailyForecastDetailPolicy.dateKey(now+(i-1)*24*ForecastDetailData.HOUR),15.0,26.0,sky)},false,rows,ForecastDaySnapshot(date,sky,26.0,15.0,72),"06:42","17:08")
                        val values=cityValues.map{it to d.copy(city=it)}
                        val elements=mutableListOf<WeatherInfoWidgetRenderer.Element>()
                        val b=if(WeatherInfoWidgetRenderer.supports(template.kind))WeatherInfoWidgetRenderer.render(c,template.kind,w,h,city,d,values,elements)else WeatherWidgetRenderer.renderWidget(c,template.kind,w,h,city,d)
                        renders++;assertTrue(b.byteCount<4_000_000);assertEquals(0,Color.alpha(b.getPixel(0,0)))
                        for(e in elements){if(e.bounds.left<13.4f||e.bounds.top<13.4f||e.bounds.right>w-13.4f||e.bounds.bottom>h-13.4f)issues.put("${template.kind}/$w/$h/$sky outside ${e.label}");if(e.type=="text"&&e.label.contains('…')&&!e.label.startsWith(WeatherAddressFormatter.city(city.name).take(2)))issues.put("${template.kind}/$w/$h/$sky truncated ${e.label}")}
                        for(i in elements.indices)for(j in i+1 until elements.size){val cross=RectF(elements[i].bounds);if(cross.intersect(elements[j].bounds)&&cross.width()>1.1f&&cross.height()>1.1f)issues.put("${template.kind}/$w/$h/$sky overlap ${elements[i].label}/${elements[j].label}")}
                        File(dir,"actual-size-${template.kind}-$sky.png").outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))};b.recycle()
                    }
                }
                File(dir,"launcher-options-matrix.json").writeText(JSONObject().put("inventory",inventory).put("renders",renders).put("virtual_weather",true).put("actual_launcher_dimensions",true).put("issues",issues).toString(2))
                assertTrue(inventory.length()>0);assertEquals(issues.toString().take(4000),0,issues.length())
            } finally {WeatherSettings.setTempUnit(c,original);WeatherWidgets.restore(c)}
        }
    }
}
