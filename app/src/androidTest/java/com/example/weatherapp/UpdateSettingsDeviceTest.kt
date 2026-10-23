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
                        (parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
                    }
                    ins.waitForIdleSync()
                    val visible = Rect()
                    scenario.onActivity { activity ->
                        val panel = activity.findViewById<View>(R.id.appInformationPanel)
                        assertTrue(panel.getGlobalVisibleRect(visible))
                        assertEquals(panel.height,visible.height())
                    }
                    val screen = requireNotNull(ins.uiAutomation.takeScreenshot())
                    val bitmap = Bitmap.createBitmap(screen,visible.left,visible.top,visible.width(),visible.height())
                    val folder = File(context.getExternalFilesDir(null),"update-review").apply { mkdirs() }
                    File(folder,"panel-$dark.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                    screen.recycle()
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

    @Test fun updateClickOpensExpectedGitHubDownloadPage() {
        var opened: Intent? = null
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW && intent.data?.host == "github.com") {
                    opened = Intent(intent)
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
                }
                return null
            }
        }
        ins.addMonitor(monitor)
        try {
            ActivityScenario.launch(WeatherSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.btnCheckUpdate).performClick() }
                ins.waitForIdleSync()
                assertEquals(AppUpdatePolicy.DOWNLOAD_PAGE,opened?.dataString)
            }
        } finally { ins.removeMonitor(monitor) }
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
}
