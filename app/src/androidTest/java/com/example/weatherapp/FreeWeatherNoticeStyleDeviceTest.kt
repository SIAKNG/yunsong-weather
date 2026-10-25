package com.example.weatherapp

import android.graphics.Bitmap
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FreeWeatherNoticeStyleDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun dialog(notice: FreeWeatherNotice): AlertDialog =
        FreeWeatherNotice::class.java.getDeclaredField("dialog").apply {isAccessible=true}.get(notice) as AlertDialog

    @Test fun realBackdropBlurRimsAndDimmingInBothThemes() {
        val original=WeatherSettings.themeMode(context)
        try {
            for(dark in listOf(false,true)) {
                WeatherSettings.setThemeMode(context,if(dark) WeatherSettings.THEME_DARK else WeatherSettings.THEME_LIGHT)
                ins.runOnMainSync {WeatherSettings.applyNightMode(context)}
                ActivityScenario.launch(WeatherSettingsActivity::class.java).use { scenario ->
                    lateinit var notice: FreeWeatherNotice
                    lateinit var window: android.view.Window
                    scenario.onActivity {a ->
                        assertEquals(dark,WeatherSettings.isDarkTheme(a))
                        notice=FreeWeatherNotice(a,{"open_meteo"},{"style-$dark"},{})
                        notice.maybeShow()
                        window=requireNotNull(dialog(notice).window)
                    }
                    android.os.SystemClock.sleep(250)
                    ins.waitForIdleSync()
                    scenario.onActivity {
                        val panel=dialog(notice).findViewById<View>(R.id.freeWeatherNoticePanel)!!
                        assertTrue(panel.background is DetailGlassSurface)
                        assertNotNull(DetailGlassSurface::class.java.getDeclaredField("sample").apply {isAccessible=true}.get(panel.background))
                        assertTrue(panel.foreground is WeatherFrostedBorderDrawable)
                        for(id in listOf(R.id.freeWeatherNoticeConfigure,R.id.freeWeatherNoticeDismiss)) {
                            val button=dialog(notice).findViewById<View>(id)!!
                            assertTrue(button.background is DetailGlassSurface)
                            assertTrue(button.foreground is WeatherFrostedBorderDrawable)
                            assertTrue(button.height>=48*button.resources.displayMetrics.density)
                        }
                        assertTrue(window.attributes.dimAmount>=.55f)
                        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                    }
                    // Capture only the dialog window, never the system UI or private home city.
                    val bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
                    val done=CountDownLatch(1);var result=-1
                    ins.runOnMainSync {PixelCopy.request(window,bitmap,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
                    assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
                    val folder=File(context.getExternalFilesDir(null),"indicator-refinement").apply {mkdirs()}
                    File(folder,"free-notice-${if(dark) "dark" else "light"}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                    scenario.onActivity {notice.dispose()}
                }
            }
        } finally {
            WeatherSettings.setThemeMode(context,original)
            ins.runOnMainSync {WeatherSettings.applyNightMode(context)}
        }
    }
}
