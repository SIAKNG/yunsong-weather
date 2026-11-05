package com.example.weatherapp

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class FourByFourLayoutDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val c get()=ins.targetContext
    private val city=CityPage("西安市",108.9,34.2,false)
    private val skies=listOf("CLEAR_DAY","CLEAR_NIGHT","PARTLY_CLOUDY_DAY","PARTLY_CLOUDY_NIGHT","CLOUDY","LIGHT_HAZE","MODERATE_HAZE","HEAVY_HAZE","LIGHT_RAIN","MODERATE_RAIN","HEAVY_RAIN","STORM_RAIN","LIGHT_THUNDER","MODERATE_THUNDER","HEAVY_THUNDER","STORM_THUNDER","FOG","LIGHT_SNOW","MODERATE_SNOW","HEAVY_SNOW","STORM_SNOW","LIGHT_SLEET","MODERATE_SLEET","HEAVY_SLEET","RAIN_SNOW","DUST","SAND","WIND")
    private fun folder()=File(c.getExternalFilesDir(null),"four-by-four-verification").apply{mkdirs()}
    private fun output(name:String,b:Bitmap){File(folder(),name).outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))}}
    private fun fixture(sky:String,night:Boolean):WidgetWeather {
        val now=System.currentTimeMillis();val start=ForecastDayHistoryPolicy.startOfDay(now);val date=DailyForecastDetailPolicy.dateKey(now)
        val rows=(0..23).map{i->ForecastHour(start+i*ForecastDetailData.HOUR,sky,if(i<12)-32.0 else 42.0,if(i<12)-35.0 else 44.0,if(i<12)4.0 else 26.0,if(i==12)5.2 else .0,64.0,if(i<12)38.0 else 178.0,precipitationProbability=.72)}
        return WidgetWeather(city,WeatherSettings.dataSource(c),now,now,-28.0,-31.0,sky,64.0,12.0,82.0,(0..5).map{i->WidgetHour(now+i*ForecastDetailData.HOUR,-28.0+i,sky,.12*i)},(0..6).map{i->WidgetDay(DailyForecastDetailPolicy.dateKey(now+i*24*ForecastDetailData.HOUR),-32.0+i,42.0-i,sky)},night,rows,ForecastDaySnapshot(date,sky,42.0,-32.0,72),"06:42","17:08")
    }
    @Test fun bothFourByFourCardsKeepCompleteContentAtLargerInsets() {
        val original=WeatherSettings.tempUnit(c);val issues=JSONArray();var renders=0
        val sizes=linkedSetOf(320 to 320,356 to 340,356 to 372,392 to 412)
        val manager=AppWidgetManager.getInstance(c)
        for(t in WeatherWidgetCatalog.all(c).filter{it.kind in listOf("large","daily_summary")})for(id in manager.getAppWidgetIds(t.provider)) {
            val o=manager.getAppWidgetOptions(id);sizes+=o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,356) to o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,372)
        }
        fun issue(v:String){if(issues.length()<100)issues.put(v)}
        ActivityScenario.launch(WeatherWidgetActivity::class.java).use{screen->
            screen.onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try {
                for(unit in listOf("c","f")) {
                    WeatherSettings.setTempUnit(c,unit)
                    for(sky in skies)for(night in listOf(false,true))for((w,h) in sizes)for(kind in listOf("large","daily_summary")) {
                        val elements=mutableListOf<WeatherInfoWidgetRenderer.Element>();val d=fixture(sky,night)
                        val b=WeatherWidgetRenderer.renderWidget(c,kind,w,h,city,d,audit=elements);renders++
                        val key="$kind/$w/$h/$sky/$night/$unit";val before=issues.length()
                        for(e in elements){val r=e.bounds;if(r.left<17.35f||r.top<17.35f||r.right>w-17.35f||r.bottom>h-17.35f)issue("$key outside ${e.label}: $r");if(e.type=="text"&&e.label.contains('…'))issue("$key truncated ${e.label}")}
                        for(i in elements.indices)for(j in i+1 until elements.size){val cross=RectF(elements[i].bounds);if(cross.intersect(elements[j].bounds)&&cross.width()>1.1f&&cross.height()>1.1f)issue("$key overlap ${elements[i].label}/${elements[j].label}: $cross")}
                        if(elements.none{it.type=="text"&&it.label==WeatherText.skyconToText(sky)})issue("$key missing weather label")
                        if(kind=="large") {
                            if(elements.count{it.type=="text"&&it.label.contains("月")&&it.label.contains("周")}!=6)issue("$key incomplete six days")
                            val header=WeatherWidgetRenderer.header(c,kind,w,h,city,d)
                            val weatherIcon=elements.firstOrNull{it.type=="icon"}
                            if(weatherIcon==null||weatherIcon.bounds.top<header.baseline+7f)issue("$key weather must move below city")
                        }
                        if((unit=="c"&&!night&&w==392&&sky in listOf("CLOUDY","HEAVY_HAZE","LIGHT_THUNDER","RAIN_SNOW"))||(issues.length()>before&&before<8))output("$kind-$w-$h-$sky-$night-$unit.png",b)
                        b.recycle()
                    }
                }
                for(kind in listOf("large","daily_summary")){val elements=mutableListOf<WeatherInfoWidgetRenderer.Element>();val b=WeatherWidgetRenderer.renderWidget(c,kind,356,372,city,null,audit=elements);assertFalse(elements.any{it.label.contains('…')});b.recycle()}
            } finally {WeatherSettings.setTempUnit(c,original);WeatherWidgets.restore(c)}
        }
        File(folder(),"matrix.json").writeText(JSONObject().put("renders",renders).put("skies",skies.size).put("units",JSONArray(listOf("c","f"))).put("sizes",JSONArray(sizes.map{"${it.first}x${it.second}"})).put("four_by_four_inset_dp",18).put("other_inset_dp",14).put("issues",issues).toString(2))
        assertEquals(issues.toString().take(4000),0,issues.length())
    }
    @Test fun nativeHeaderTimestampUsesTopRightAndMatchesCityInkBottom() {
        ActivityScenario.launch(WeatherWidgetActivity::class.java).use{screen->
            screen.onActivity{a->
                for((w,h) in listOf(356 to 340,356 to 372,392 to 412)) {
                    val data=fixture("CLOUDY",false);val header=WeatherWidgetRenderer.header(c,"large",w,h,city,data)
                    val density=c.resources.displayMetrics.density
                    val parent=FrameLayout(a);val root=WeatherWidgets.views(c,-9601,"large",w,h).apply(c,parent)
                    val stamp=root.findViewById<TextView>(R.id.widgetEditArea)
                    stamp.visibility=View.VISIBLE;stamp.text=WeatherWidgetRenderer.updateText(c,data)
                    stamp.setPadding((header.stampX*density).toInt(),(header.stampTop*density).toInt(),((w-header.stampX-header.stampWidth)*density).toInt(),0)
                    root.findViewById<TextView>(R.id.widgetLoading).visibility=View.GONE
                    root.findViewById<ImageView>(R.id.widgetImage).setImageBitmap(WeatherWidgetRenderer.renderWidget(c,"large",w,h,city,data))
                    parent.addView(root);a.setContentView(parent)
                    val pw=(w*density).toInt();val ph=(h*density).toInt();root.measure(View.MeasureSpec.makeMeasureSpec(pw,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(ph,View.MeasureSpec.EXACTLY));root.layout(0,0,pw,ph)
                    val stampInk=Rect();stamp.paint.getTextBounds(stamp.text.toString(),0,stamp.text.length,stampInk)
                    val cityPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=header.size*density;typeface=Typeface.create("sans-serif",Typeface.BOLD)}
                    val cityInk=Rect();cityPaint.getTextBounds(header.name,0,header.name.length,cityInk)
                    val cityBottom=header.baseline*density+cityInk.bottom
                    val stampBottom=stamp.baseline+stampInk.bottom
                    File(folder(),"native-header-$w-$h.json").writeText(JSONObject().put("header",header.toString()).put("density",density).put("stamp_text",stamp.text).put("native_text_size",stamp.textSize).put("native_ink",stampInk.toString()).put("right_dp",(stamp.paddingLeft+stampInk.right)/density).put("bottom_delta_dp",(stampBottom-cityBottom)/density).put("ellipsis",stamp.layout.getEllipsisCount(0)).toString(2))
                    val b=Bitmap.createBitmap(pw,ph,Bitmap.Config.ARGB_8888);root.draw(Canvas(b));output("native-large-${w}x$h.png",b);b.recycle()
                    assertTrue("City/time glyph bottoms must align, delta=${(stampBottom-cityBottom)/density}",abs(stampBottom-cityBottom)<1.2f*density)
                    assertTrue("Timestamp complete: ${stamp.text}; content=${stamp.width-stamp.paddingLeft-stamp.paddingRight}, measured=${stamp.paint.measureText(stamp.text.toString())}, size=${stamp.textSize}, header=$header",stamp.layout.getEllipsisCount(0)==0)
                    assertTrue("Timestamp right=${(stamp.paddingLeft+stampInk.right)/density}, expected=${w-18}, header=$header",abs((stamp.paddingLeft+stampInk.right)/density-(w-18))<1.2f)
                }
            }
        }
    }
}
