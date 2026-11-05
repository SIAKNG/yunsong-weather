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

class WeatherApprovedCardsDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val c get()=ins.targetContext
    private val cities=listOf(CityPage("北京",116.407,39.904,true),CityPage("上海",121.4737,31.2304,false),CityPage("杭州",120.1551,30.2741,false))
    private fun output(name: String,b: Bitmap){val dir=File(c.getExternalFilesDir(null),"approved-widget-verification").apply{mkdirs()};File(dir,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    private fun fixture(sky: String,night: Boolean): WidgetWeather {
        val now=System.currentTimeMillis();val start=ForecastDayHistoryPolicy.startOfDay(now);val date=DailyForecastDetailPolicy.dateKey(now)
        val rows=(0..23).map{i->ForecastHour(start+i*ForecastDetailData.HOUR,sky,if(i<12)-6.0 else 21.0,if(i<12)-9.0 else 23.0,if(i<12)4.0 else 26.0,if(i==12)5.2 else .0,64.0,if(i<12)38.0 else 178.0,precipitationProbability=.72)}
        return WidgetWeather(cities[0],WeatherSettings.dataSource(c),now,now,19.0,18.0,sky,64.0,12.0,82.0,(0..5).map{i->WidgetHour(now+i*ForecastDetailData.HOUR,19.0-i,sky,.12*i)},listOf(WidgetDay(date,-6.0,21.0,sky)),night,rows,ForecastDaySnapshot(date,sky,21.0,-6.0,72),"06:42","17:08")
    }
    @Test fun allSupportedSkiesHaveUniformInsetsRoundCornersAndInstalledRim() {
        val foreground=ActivityScenario.launch(WeatherWidgetActivity::class.java).onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        try {
        val skies=listOf("CLEAR_DAY","CLEAR_NIGHT","PARTLY_CLOUDY_DAY","PARTLY_CLOUDY_NIGHT","CLOUDY","LIGHT_HAZE","MODERATE_HAZE","HEAVY_HAZE","LIGHT_RAIN","MODERATE_RAIN","HEAVY_RAIN","STORM_RAIN","LIGHT_THUNDER","MODERATE_THUNDER","HEAVY_THUNDER","STORM_THUNDER","FOG","LIGHT_SNOW","MODERATE_SNOW","HEAVY_SNOW","STORM_SNOW","LIGHT_SLEET","MODERATE_SLEET","HEAVY_SLEET","RAIN_SNOW","DUST","SAND","WIND")
        val issues=JSONArray();var renders=0;var edgePixels=0
        for(sky in skies)for(night in listOf(false,true))for(kind in listOf("air","rain","rain_trend","cities2","cities3","daily_summary"))for(tight in listOf(false,true)) {
            val square=kind in listOf("air","rain");val large=kind=="daily_summary"
            val w=if(square)if(tight)166 else 172 else if(tight)340 else 356
            val h=if(large)if(tight)340 else 372 else if(tight)166 else 172
            val d=fixture(sky,night);val values=cities.take(if(kind=="cities2")2 else 3).map{it to d.copy(city=it)}
            val elements=mutableListOf<WeatherInfoWidgetRenderer.Element>()
            val b=WeatherInfoWidgetRenderer.render(c,if(kind.startsWith("cities"))"cities" else kind,w,h,d.city,d,values,elements);renders++
            elements.forEach{e->val r=e.bounds;if(r.left<13.4f||r.top<13.4f||r.right>w-13.4f||r.bottom>h-13.4f)issues.put("$kind/$sky/$night/$tight outside ${e.label}: $r")}
            for(i in elements.indices)for(j in i+1 until elements.size){val a=elements[i];val z=elements[j];val cross=RectF(a.bounds);if(cross.intersect(z.bounds)&&cross.width()>1.1f&&cross.height()>1.1f)issues.put("$kind/$sky/$night/$tight overlap ${a.label}/${z.label}: $cross")}
            val baseline=WeatherWidgetRenderer.renderPlaceholder(c,w,h)
            val sameWeatherBaseline=WeatherWidgetRenderer.renderWidget(c,"square",w,h,d.city,d)
            val scale=b.width.toFloat()/w;val band=(6*scale).toInt()
            for(y in 0 until b.height step 3)for(x in 0 until b.width step 3)if(x<band||x>=b.width-band||y<band||y>=b.height-band) {
                val pixel=b.getPixel(x,y)
                if(Color.alpha(pixel)!=Color.alpha(baseline.getPixel(x,y)))issues.put("$kind/$sky mask mismatch $x,$y")
                if(!kind.startsWith("cities")&&pixel!=sameWeatherBaseline.getPixel(x,y))issues.put("$kind/$sky rim mismatch $x,$y")
                edgePixels++
            }
            if(!tight)output("weather-$kind-$sky-${if(night)"night" else "day"}.png",b)
            b.recycle();baseline.recycle();sameWeatherBaseline.recycle()
        }
        val report=JSONObject().put("sky_codes",skies.size).put("day_night",2).put("renders",renders).put("edge_pixels",edgePixels).put("corner_dp",WeatherWidgetRenderer.cornerRadiusDp()).put("inset_dp",14).put("issues",issues)
        File(c.getExternalFilesDir(null),"approved-widget-verification/weather-matrix.json").writeText(report.toString(2))
        assertEquals("All weather sizes and existing rim must match: ${issues.toString().take(1800)}",0,issues.length())
        } finally {foreground.close()}
    }
    private fun allocate(host: AppWidgetHost): Int {
        val id=host.allocateAppWidgetId();ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try{assertTrue(AppWidgetManager.getInstance(c).bindAppWidgetIdIfAllowed(id,ComponentName(c,WeatherCitiesWidget::class.java)))}finally{ins.uiAutomation.dropShellPermissionIdentity()}
        WeatherWidgetStore.register(c,id);return id
    }
    @Test fun cityDraftRequiresTwoCitiesExplicitThirdAndCompletionAndSurvivesRotation() {
        val prefs=c.getSharedPreferences("weather_cities",Context.MODE_PRIVATE);val before=prefs.getString("cities",null)
        val array=JSONArray();cities.forEach{array.put(JSONObject().put("name",it.name).put("lng",it.lng).put("lat",it.lat).put("isLoc",it.isCurrentLocation))}
        val host=AppWidgetHost(c,8861);val id=allocate(host)
        try {
            prefs.edit().putString("cities",array.toString()).commit()
            val intent=Intent(c,WeatherWidgetActivity::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
            ActivityScenario.launch<WeatherWidgetActivity>(intent).use {editor->
                fun choose(slot: Int,label: String){editor.onActivity{a->val spinner=a.findViewById<View>(android.R.id.content).findViewWithTag<Spinner>("widget_multi_slot_$slot");val index=(0 until spinner.count).first{spinner.getItemAtPosition(it).toString().contains(label)};spinner.setSelection(index)};ins.waitForIdleSync()}
                editor.onActivity{a->assertFalse(a.findViewById<View>(android.R.id.content).findViewWithTag<Button>("widget_multi_done").isEnabled);for(slot in 0..1){val spinner=a.findViewById<View>(android.R.id.content).findViewWithTag<Spinner>("widget_multi_slot_$slot");assertTrue((0 until spinner.count).none{spinner.getItemAtPosition(it).toString().contains("无城市")})}}
                choose(0,"跟随当前位置")
                assertFalse(WeatherWidgetStore.isConfigured(c,id));assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED,editor.state)
                editor.recreate();ins.waitForIdleSync();choose(1,"上海")
                editor.onActivity{a->assertFalse(a.findViewById<View>(android.R.id.content).findViewWithTag<Button>("widget_multi_done").isEnabled)}
                choose(2,"无城市")
                editor.onActivity{a->val button=a.findViewById<View>(android.R.id.content).findViewWithTag<Button>("widget_multi_done");assertTrue(button.isEnabled);val view=a.window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));output("native-config-two-ready.png",bitmap);bitmap.recycle();button.performClick()}
                ins.waitForIdleSync();assertTrue(WeatherWidgetStore.isConfigured(c,id));assertTrue(WeatherWidgetStore.followsLocation(c,id));assertEquals(2,WeatherWidgetStore.cities(c,id).size)
            }
            ActivityScenario.launch<WeatherWidgetActivity>(intent.putExtra("widget_edit_requested",true)).use {editor->
                editor.onActivity{a->val spinner=a.findViewById<View>(android.R.id.content).findViewWithTag<Spinner>("widget_multi_slot_2");spinner.setSelection((0 until spinner.count).first{spinner.getItemAtPosition(it).toString()=="杭州"})};ins.waitForIdleSync()
                assertEquals(2,WeatherWidgetStore.cities(c,id).size)
                editor.onActivity{a->a.findViewById<View>(android.R.id.content).findViewWithTag<Button>("widget_multi_done").performClick()};ins.waitForIdleSync();assertEquals(3,WeatherWidgetStore.cities(c,id).size)
            }
        } finally {if(before==null)prefs.edit().remove("cities").commit() else prefs.edit().putString("cities",before).commit();host.deleteAppWidgetId(id);WeatherWidgetStore.delete(c,id);WeatherWidgets.restore(c)}
    }
    @Test fun invalidCitySetsCannotCommitAndUnifiedTimestampUsesOldestObservation() {
        val id=887000;try {
            assertFalse(WeatherWidgetStore.setCities(c,id,cities[0],emptyList(),false));assertFalse(WeatherWidgetStore.isConfigured(c,id))
            assertFalse(WeatherWidgetStore.setCities(c,id,cities[0],listOf(cities[0]),false));assertFalse(WeatherWidgetStore.isConfigured(c,id))
            assertTrue(WeatherWidgetStore.setCities(c,id,cities[0],listOf(cities[1]),false));assertEquals(2,WeatherWidgetStore.cities(c,id).size)
            val d=fixture("CLOUDY",false);val old=d.copy(observed=d.observed-600_000)
            assertEquals(WeatherWidgetRenderer.updateText(c,old),WeatherInfoWidgetRenderer.cityUpdateText(c,listOf(cities[0] to d,cities[1] to old)))
            assertEquals("待刷新",WeatherInfoWidgetRenderer.cityUpdateText(c,listOf(cities[0] to d,cities[1] to null)))
        }finally{WeatherWidgetStore.delete(c,id)}
    }
    @Test fun actualNativeHostShowsBothCityLayoutsWithoutChangingOtherWidgets() {
        val host=AppWidgetHost(c,8862);val id=allocate(host)
        ActivityScenario.launch(WeatherWidgetActivity::class.java).use {screen->
            try {
                for(count in 2..3){assertTrue(WeatherWidgetStore.setCities(c,id,cities[0],cities.drop(1).take(count-1),false))
                    screen.onActivity{a->host.startListening();val view=host.createView(c,id,AppWidgetManager.getInstance(c).getAppWidgetInfo(id));view.updateAppWidget(WeatherWidgets.views(c,id,"cities",356,172));val frame=FrameLayout(a);frame.addView(view);a.setContentView(frame);val density=c.resources.displayMetrics.density;val w=(356*density).toInt();val h=(172*density).toInt();view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));output("native-host-cities-$count.png",bitmap);bitmap.recycle()}
                }
            }finally{host.stopListening();host.deleteAppWidgetId(id);WeatherWidgetStore.delete(c,id);WeatherWidgets.restore(c)}
        }
    }
}
