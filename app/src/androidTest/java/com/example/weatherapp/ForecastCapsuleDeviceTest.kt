package com.example.weatherapp

import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import kotlin.math.roundToInt

class ForecastCapsuleDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun day()=Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
    }.timeInMillis
    private fun intent()=ForecastDetailActivity.createIntent(context,"北京市",(0..23).map {
        ForecastHour(day()+it*ForecastDetailData.HOUR,"CLEAR_DAY",21.0,20.0,10.0,3.0,50.0,40.0)
    })

    @Test fun everyOptionHasSymmetricMeasuredSelectionInsets() {
        val original=WeatherSettings.themeMode(context)
        try {
            for(dark in listOf(false,true)) {
                WeatherSettings.setThemeMode(context,if(dark) WeatherSettings.THEME_DARK else WeatherSettings.THEME_LIGHT)
                ins.runOnMainSync {WeatherSettings.applyNightMode(context)}
                ActivityScenario.launch<ForecastDetailActivity>(intent()).use {scenario ->
                    SystemClock.sleep(400);ins.waitForIdleSync()
                    for(metric in listOf("TEMPERATURE","RAIN","AQI","TEMPERATURE")) {
                        scenario.onActivity {it.findViewById<View>(android.R.id.content).findViewWithTag<View>("daily_metric:$metric").performClick()}
                        SystemClock.sleep(350);ins.waitForIdleSync()
                        scenario.onActivity {activity ->
                            val root=activity.findViewById<View>(android.R.id.content)
                            val option=root.findViewWithTag<View>("daily_metric:$metric")
                            val thumb=root.findViewWithTag<View>("weather_segment_thumb")
                            val inset=(2*activity.resources.displayMetrics.density).roundToInt()
                            val left=thumb.x-option.left
                            val right=option.right-(thumb.x+thumb.width)
                            assertEquals("$metric left inset",inset.toFloat(),left,1f)
                            assertEquals("$metric selection must match its actual label cell symmetrically",left,right,1f)
                            assertEquals(option.width-2*inset,thumb.width)
                            assertEquals(option.height-2*inset,thumb.height)
                            val track=root.findViewWithTag<View>("daily_metric_capsule").background as android.graphics.drawable.GradientDrawable
                            val selected=thumb.background as android.graphics.drawable.GradientDrawable
                            val image=android.graphics.Bitmap.createBitmap(thumb.width,thumb.height,android.graphics.Bitmap.Config.ARGB_8888)
                            val sample=selected.constantState!!.newDrawable().mutate()
                            sample.setBounds(0,0,thumb.width,thumb.height);sample.draw(android.graphics.Canvas(image))
                            val outline=androidx.core.graphics.ColorUtils.compositeColors(
                                image.getPixel(thumb.width/2,thumb.height-2),track.colors!!.last())
                            val channels=listOf(android.graphics.Color.red(outline),android.graphics.Color.green(outline),android.graphics.Color.blue(outline))
                            if(dark) {
                                assertTrue("Dark rim stays softer than white labels: $channels",channels.min()>=160 && channels.max()<=210)
                                assertTrue(channels.max()-channels.min()<20)
                                assertEquals(.75f,activity.resources.getDimension(R.dimen.forecast_capsule_selected_stroke)/activity.resources.displayMetrics.density,.01f)
                            } else {
                                assertTrue("Light selected rim stays bright white",channels.min()>=250)
                                assertTrue(channels.max()-channels.min()<8)
                            }
                            image.recycle()
                            val difference=androidx.core.graphics.ColorUtils.calculateLuminance(selected.colors!!.first())-
                                androidx.core.graphics.ColorUtils.calculateLuminance(track.colors!!.first())
                            assertTrue("Selected glass visibly separates from the track in both themes",difference>if(dark) .025 else .15)
                            val text=option as android.widget.TextView
                            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(text.currentTextColor,selected.colors!!.first())>=4.5)
                        }
                    }
                }
            }
        } finally {WeatherSettings.setThemeMode(context,original);ins.runOnMainSync {WeatherSettings.applyNightMode(context)}}
    }
}
