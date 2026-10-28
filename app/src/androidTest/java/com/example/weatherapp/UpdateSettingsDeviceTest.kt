package com.example.weatherapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** No personal city names, coordinates, credentials or screen-wide captures in test evidence. */
class UpdateSettingsDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun configurationFingerprint() {
        val encrypted = context.getSharedPreferences(UserApiConfiguration.PREFS,Context.MODE_PRIVATE).getString("encrypted","").orEmpty()
        val settings = listOf(WeatherSettings.themeMode(context),WeatherSettings.tempUnit(context),WeatherSettings.windUnit(context),
            WeatherSettings.pressureUnit(context),WeatherSettings.visibilityUnit(context),WeatherSettings.dataSource(context)).joinToString("|")
        ins.sendStatus(0,android.os.Bundle().apply {
            putString("upgrade_state","apiEncryptedSha=${digest(encrypted)} revision=${UserApiConfiguration.revision(context)} settingsSha=${digest(settings)}")
        })
    }

    @Test fun groupedPanelAndBadgesReadableInBothThemes() {
        val original = WeatherSettings.themeMode(context)
        val prefs = context.getSharedPreferences("public_release_check",Context.MODE_PRIVATE)
        val previousCache = prefs.all.toMap()
        try {
            for (dark in listOf(false,true)) {
                WeatherSettings.setThemeMode(context,if(dark) WeatherSettings.THEME_DARK else WeatherSettings.THEME_LIGHT)
                ins.runOnMainSync { WeatherSettings.applyNightMode(context) }
                // Isolate visual assertions from external/private GitHub network state.
                prefs.edit().putString("status","UNAVAILABLE").putLong("checked_at",System.currentTimeMillis())
                    .putInt("installed_code",BuildConfig.VERSION_CODE).commit()
                ActivityScenario.launch(WeatherSettingsActivity::class.java).use { scenario ->
                    ins.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertEquals(dark,WeatherSettings.isDarkTheme(activity))
                        assertEquals(0,activity.resources.getIdentifier("btnIconSource","id",activity.packageName))
                        val panel = activity.findViewById<View>(R.id.appInformationPanel)
                        val check = activity.findViewById<View>(R.id.btnCheckUpdate)
                        assertSame(panel,check.parent)
                        assertSame(panel,activity.findViewById<View>(R.id.btnUsageGuide).parent)
                        assertSame(panel,activity.findViewById<View>(R.id.btnAbout).parent)
                        assertNotNull(panel.background)
                        assertNotNull(panel.foreground)
                        assertTrue(check.height >= 48 * activity.resources.displayMetrics.density)
                    }
                    for (status in AppUpdatePolicy.Status.values()) {
                        scenario.onActivity { activity ->
                            activity.renderUpdateStatus(AppUpdatePolicy.Result(status,"1.2"))
                        }
                        ins.waitForIdleSync()
                        scenario.onActivity { activity ->
                            val badge = activity.findViewById<TextView>(R.id.tvUpdateStatus)
                            assertTrue(badge.text.isNotBlank())
                            assertEquals(androidx.core.content.ContextCompat.getColor(activity,
                                if(status == AppUpdatePolicy.Status.NEW_VERSION) R.color.accent else R.color.text_secondary),badge.currentTextColor)
                            assertNotNull(badge.layout)
                            assertTrue(badge.layout.getEllipsisCount(0) == 0)
                            assertTrue(activity.findViewById<View>(R.id.btnCheckUpdate).isClickable)
                        }
                    }
                    scenario.onActivity { activity ->
                        activity.renderUpdateStatus(AppUpdatePolicy.Result(AppUpdatePolicy.Status.UNAVAILABLE))
                        val panel = activity.findViewById<View>(R.id.appInformationPanel)
                        var parent = panel.parent
                        while (parent != null && parent !is ScrollView) parent = parent.parent
                        (parent as? ScrollView)?.let(::scrollToPanelBottom)
                    }
                    ins.waitForIdleSync()
                    val visible = Rect()
                    scenario.onActivity { activity ->
                        val panel = activity.findViewById<View>(R.id.appInformationPanel)
                        assertTrue(panel.getGlobalVisibleRect(visible))
                        assertEquals(panel.height,visible.height())
                    }
                    capturePanel(scenario,"panel-$dark.png")
                }
            }
        } finally {
            context.getSharedPreferences("weather_settings",Context.MODE_PRIVATE).edit().putString("theme_mode",original).commit()
            val restore = prefs.edit().clear()
            previousCache.forEach { (key,value) -> when(value) {
                is String -> restore.putString(key,value)
                is Long -> restore.putLong(key,value)
                is Int -> restore.putInt(key,value)
            } }
            restore.commit()
            ins.runOnMainSync { WeatherSettings.applyNightMode(context) }
        }
    }

    @Test fun updateClickChecksWithSpinnerAndOnlyNewVersionsOpenDownloads() {
        val original=WeatherSettings.themeMode(context)
        val opened=java.util.Collections.synchronizedList(ArrayList<Intent>())
        val monitor=object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if(intent.action==Intent.ACTION_VIEW && intent.data?.host=="github.com") {
                    opened.add(Intent(intent))
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
                }
                return null
            }
        }
        ins.addMonitor(monitor)
        try {
            for(dark in listOf(false,true)) {
                WeatherSettings.setThemeMode(context,if(dark) WeatherSettings.THEME_DARK else WeatherSettings.THEME_LIGHT)
                ins.runOnMainSync {WeatherSettings.applyNightMode(context)}
                launchControlledUpdateScenario().use {scenario ->
                    for(status in listOf(AppUpdatePolicy.Status.CURRENT,AppUpdatePolicy.Status.FAILED,AppUpdatePolicy.Status.UNAVAILABLE,AppUpdatePolicy.Status.NEW_VERSION)) {
                        opened.clear()
                        val started=java.util.concurrent.CountDownLatch(1)
                        val release=java.util.concurrent.CountDownLatch(1)
                        val calls=java.util.concurrent.atomic.AtomicInteger()
                        try {
                            scenario.onActivity {activity ->
                                val request: (Boolean)->AppUpdatePolicy.Result={force ->
                                    assertTrue("A user tap bypasses the automatic cache",force)
                                    calls.incrementAndGet();started.countDown()
                                    release.await(4,java.util.concurrent.TimeUnit.SECONDS)
                                    AppUpdatePolicy.Result(status,if(status==AppUpdatePolicy.Status.NEW_VERSION) "9.9" else "")
                                }
                                activity.javaClass.getDeclaredField("updateRequest").apply {isAccessible=true}.set(activity,request)
                                activity.findViewById<View>(R.id.btnCheckUpdate).performClick()
                                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.updateCheckProgress).visibility)
                                assertTrue(activity.findViewById<android.widget.ProgressBar>(R.id.updateCheckProgress).isIndeterminate)
                                assertTrue(activity.findViewById<TextView>(R.id.tvUpdateStatus).text.contains("正在检查"))
                                activity.findViewById<View>(R.id.btnCheckUpdate).performClick()
                            }
                            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS))
                            assertEquals(1,calls.get());assertTrue(opened.isEmpty())
                            if(status==AppUpdatePolicy.Status.CURRENT) capturePanel(scenario,"checking-$dark.png")
                            release.countDown();waitForCheckCompletion(scenario)
                            scenario.onActivity {activity ->
                                assertEquals("$status must finish its checking feedback",View.GONE,activity.findViewById<View>(R.id.updateCheckProgress).visibility)
                                assertEquals(AppUpdatePolicy.Result(status,if(status==AppUpdatePolicy.Status.NEW_VERSION) "9.9" else "").label,
                                    activity.findViewById<TextView>(R.id.tvUpdateStatus).text.toString())
                                assertEquals(if(status==AppUpdatePolicy.Status.NEW_VERSION) View.VISIBLE else View.GONE,
                                    activity.findViewById<View>(R.id.updateDownloadArrow).visibility)
                            }
                            assertEquals(if(status==AppUpdatePolicy.Status.NEW_VERSION) 1 else 0,opened.size)
                            if(opened.isNotEmpty()) assertEquals(AppUpdatePolicy.DOWNLOAD_PAGE,opened.single().dataString)
                        } finally {release.countDown()}
                    }
                }
            }
        } finally {
            ins.removeMonitor(monitor)
            WeatherSettings.setThemeMode(context,original)
            ins.runOnMainSync {WeatherSettings.applyNightMode(context)}
        }
    }

    @Test fun manualCheckCannotBeOverwrittenByAnEarlierAutomaticResult() {
        val started=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        launchControlledUpdateScenario().use {scenario ->
            android.os.SystemClock.sleep(500);ins.waitForIdleSync()
            try {
                scenario.onActivity {activity ->
                    val request: (Boolean)->AppUpdatePolicy.Result={manual ->
                        if(!manual) {started.countDown();release.await(4,java.util.concurrent.TimeUnit.SECONDS)}
                        AppUpdatePolicy.Result(if(manual) AppUpdatePolicy.Status.FAILED else AppUpdatePolicy.Status.NEW_VERSION,"9.9")
                    }
                    activity.javaClass.getDeclaredField("updateRequest").apply {isAccessible=true}.set(activity,request)
                    activity.javaClass.getDeclaredMethod("requestUpdateCheck",Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(activity,false)
                }
                assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS))
                scenario.onActivity {it.findViewById<View>(R.id.btnCheckUpdate).performClick()}
                android.os.SystemClock.sleep(500);ins.waitForIdleSync()
                release.countDown();android.os.SystemClock.sleep(250);ins.waitForIdleSync()
                scenario.onActivity {activity ->
                    assertEquals(AppUpdatePolicy.Result(AppUpdatePolicy.Status.FAILED).label,activity.findViewById<TextView>(R.id.tvUpdateStatus).text.toString())
                    assertEquals(View.GONE,activity.findViewById<View>(R.id.updateDownloadArrow).visibility)
                }
            } finally {release.countDown()}
        }
    }

    @Test fun aboutKeepsIconCreditAndUsageTermsInBothThemes() {
        val original = WeatherSettings.themeMode(context)
        try {
            for(dark in listOf(false,true)) {
                WeatherSettings.setThemeMode(context,if(dark) WeatherSettings.THEME_DARK else WeatherSettings.THEME_LIGHT)
                ins.runOnMainSync { WeatherSettings.applyNightMode(context) }
                ActivityScenario.launch(WeatherAboutActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val credit = activity.findViewById<TextView>(R.id.tvIconSourceDescription).text.toString()
                        assertTrue(credit.contains(IconAttribution.AUTHOR))
                        assertTrue(credit.contains(IconAttribution.COLLECTION_URL))
                        assertTrue(credit.contains("授权"))
                        assertTrue(activity.findViewById<TextView>(R.id.tvWeatherAboutVersion).text.contains(BuildConfig.VERSION_NAME))
                    }
                }
            }
        } finally {
            context.getSharedPreferences("weather_settings",Context.MODE_PRIVATE).edit().putString("theme_mode",original).commit()
            ins.runOnMainSync { WeatherSettings.applyNightMode(context) }
        }
    }
    private fun capturePanel(scenario: ActivityScenario<WeatherSettingsActivity>,name:String) {
        lateinit var window:android.view.Window
        val rect=Rect()
        scenario.onActivity {a ->
            val panel=a.findViewById<View>(R.id.appInformationPanel)
            var parent=panel.parent
            while(parent!=null && parent !is ScrollView) parent=parent.parent
            (parent as? ScrollView)?.let(::scrollToPanelBottom)
            window=a.window
        }
        android.os.SystemClock.sleep(120);ins.waitForIdleSync()
        scenario.onActivity {assertTrue(it.findViewById<View>(R.id.appInformationPanel).getGlobalVisibleRect(rect))}
        val bitmap=Bitmap.createBitmap(rect.width(),rect.height(),Bitmap.Config.ARGB_8888)
        val done=java.util.concurrent.CountDownLatch(1);var result=-1
        ins.runOnMainSync {android.view.PixelCopy.request(window,rect,bitmap,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
        assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
        val folder=File(context.getExternalFilesDir(null),"update-review").apply {mkdirs()}
        File(folder,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    /** Install the fake before onResume, so UI tests never race a real GitHub request. */
    private fun launchControlledUpdateScenario(): ActivityScenario<WeatherSettingsActivity> {
        val application=context.applicationContext as android.app.Application
        val callbacks=object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity:android.app.Activity,state:android.os.Bundle?) {
                if(activity is WeatherSettingsActivity) {
                    val request: (Boolean)->AppUpdatePolicy.Result={AppUpdatePolicy.Result(AppUpdatePolicy.Status.CURRENT)}
                    activity.javaClass.getDeclaredField("updateRequest").apply {isAccessible=true}.set(activity,request)
                }
            }
            override fun onActivityStarted(a:android.app.Activity) {}
            override fun onActivityResumed(a:android.app.Activity) {}
            override fun onActivityPaused(a:android.app.Activity) {}
            override fun onActivityStopped(a:android.app.Activity) {}
            override fun onActivitySaveInstanceState(a:android.app.Activity,b:android.os.Bundle) {}
            override fun onActivityDestroyed(a:android.app.Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        return try {ActivityScenario.launch(WeatherSettingsActivity::class.java)} finally {application.unregisterActivityLifecycleCallbacks(callbacks)}
    }

    private fun waitForCheckCompletion(scenario:ActivityScenario<WeatherSettingsActivity>) {
        val deadline=android.os.SystemClock.uptimeMillis()+2500
        var complete=false
        while(!complete && android.os.SystemClock.uptimeMillis()<deadline) {
            scenario.onActivity {a ->complete=a.findViewById<View>(R.id.updateCheckProgress).visibility==View.GONE}
            if(!complete) android.os.SystemClock.sleep(40)
        }
        assertTrue("Update checking must complete after the response arrives",complete)
        ins.waitForIdleSync()
    }

    private fun scrollToPanelBottom(scroll:ScrollView) {
        val child=scroll.getChildAt(0) ?: return
        val margins=child.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        val extent=child.height+(margins?.topMargin ?: 0)+(margins?.bottomMargin ?: 0)
        val bottom=(extent-scroll.height+scroll.paddingTop+scroll.paddingBottom).coerceAtLeast(0)
        scroll.scrollTo(0,bottom)
    }

}
