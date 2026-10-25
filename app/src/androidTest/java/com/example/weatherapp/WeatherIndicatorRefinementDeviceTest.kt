package com.example.weatherapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native Canvas pixel checks; no screenshots of private cities or system windows. */
class WeatherIndicatorRefinementDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun dp(v: Float) = v * context.resources.displayMetrics.density
    private fun render(view: View, width: Int, height: Int): Bitmap {
        view.layout(0,0,width,height)
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    @Test fun oneDegreeMarkersHaveColoredFillAndZeroStaysCentered() {
        ins.runOnMainSync {
            val width=dp(160f).toInt();val height=dp(36f).toInt()
            for (delta in listOf(-1.0,0.0,1.0)) {
                val view=FeelsLikeDeltaView(context)
                view.setTemperatures(20.0,20.0+delta,"°C")
                val bitmap=render(view,width,height)
                save(bitmap,"feels-$delta.png")
                val marker=IndicatorTrackGeometry.feelsLikeMarkerX(dp(4f),width-dp(4f),dp(10f),dp(5f),dp(8f),delta,6.0)
                val color=bitmap.getPixel(marker.toInt(),height/2)
                if(delta==0.0) {
                    assertTrue(Color.green(color)>Color.red(color))
                } else {
                    assertEquals(Color.WHITE,color)
                    val start=width/2f+if(delta>0) dp(10f) else -dp(10f)
                    // Sample the exposed fill, outside the white marker's 5dp radius.
                    val fill=bitmap.getPixel((start*.75f+marker*.25f).toInt(),height/2)
                    assertEquals(255,Color.alpha(fill))
                    if(delta>0) assertTrue(Color.red(fill)>Color.blue(fill))
                    else assertTrue(Color.blue(fill)>Color.red(fill))
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun aqiUsesFilled270DegreeGaugeInBothThemes() {
        ins.runOnMainSync {
            for(dark in listOf(false,true)) {
                val view=AirQualityGaugeView(context)
                view.setData(100.0,dark)
                val width=dp(240f).toInt();val height=dp(180f).toInt()
                val bitmap=render(view,width,height)
                val size=minOf(width*.88f,height*1.10f)
                val radius=size*.42f
                val cy=height*.57f
                val filled=bitmap.getPixel((width/2f-radius).toInt(),cy.toInt())
                assertEquals(255,Color.alpha(filled))
                assertNotEquals(Color.red(filled),Color.blue(filled))
                val track=bitmap.getPixel(width/2,(cy-radius).toInt())
                assertEquals(Color.red(track),Color.blue(track))
                assertEquals(if(dark)70 else 255,Color.alpha(track))
                save(bitmap,"aqi-$dark.png")
                bitmap.recycle()
            }
        }
    }

    private fun save(bitmap: Bitmap,name: String) {
        val folder=File(context.getExternalFilesDir(null),"indicator-refinement").apply { mkdirs() }
        File(folder,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
