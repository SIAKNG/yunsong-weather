package com.example.weatherapp

import android.appwidget.*
import android.content.*
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WidgetSettingsGroupDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val c get()=ins.targetContext
    private val first=CityPage("北京",116.407,39.904,false)
    private val second=CityPage("上海",121.4737,31.2304,false)
    private fun snapshot(name: String,v: View){assertTrue(v.width>0&&v.height>0);val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));val dir=File(c.getExternalFilesDir(null),"settings-group-verification").apply{mkdirs()};File(dir,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun bind(host: AppWidgetHost,provider: Class<*>): Int {val id=host.allocateAppWidgetId();ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");try{assertTrue(AppWidgetManager.getInstance(c).bindAppWidgetIdIfAllowed(id,ComponentName(c,provider)))}finally{ins.uiAutomation.dropShellPermissionIdentity()};WeatherWidgetStore.register(c,id);return id}
    private fun theme(value: String){ins.runOnMainSync{WeatherSettings.setThemeMode(c,value);WeatherSettings.applyNightMode(c)};ins.waitForIdleSync()}
    @Test fun widgetManagerGroupsAllAddedRowsAndRecoveryInLightAndDark() {
        val host=AppWidgetHost(c,8865);val ids=listOf(bind(host,WeatherSquareWidget::class.java),bind(host,WeatherRainWidget::class.java));val previous=WeatherSettings.themeMode(c)
        ids.forEach{WeatherWidgetStore.setCity(c,it,first)}
        try {for(mode in listOf(WeatherSettings.THEME_LIGHT,WeatherSettings.THEME_DARK)) {
            theme(mode)
            ActivityScenario.launch(WeatherWidgetActivity::class.java).use {screen->
                ins.waitForIdleSync();screen.onActivity{a->
                    val root=a.findViewById<View>(android.R.id.content);val added=root.findViewWithTag<WidgetSettingsGroup>("widget_added_group");assertNotNull(added)
                    for(id in ids)assertSame(added,root.findViewWithTag<View>("widget_edit_$id").parent)
                    val recovery=root.findViewWithTag<WidgetSettingsGroup>("widget_recovery_group")
                    assertSame(recovery,root.findViewWithTag<View>("widget_refresh_action").parent);assertSame(recovery,root.findViewWithTag<View>("widget_recover_action").parent)
                    assertTrue(added.clipToOutline);assertTrue(added.foreground is WeatherFrostedBorderDrawable);assertNull(root.findViewWithTag<View>("widget_refresh_action").foreground)
                    snapshot("manager-added-$mode.png",added);snapshot("manager-recovery-$mode.png",recovery)
                }
            }
        }}finally{theme(previous);ids.forEach{host.deleteAppWidgetId(it);WeatherWidgetStore.delete(c,it)};WeatherWidgets.restore(c)}
    }
    @Test fun helpFooterAndMultiCityChoicesMatchSettingsSurfacesInLightAndDark() {
        val host=AppWidgetHost(c,8866);val id=bind(host,WeatherCitiesWidget::class.java);val previous=WeatherSettings.themeMode(c)
        assertTrue(WeatherWidgetStore.setCities(c,id,first,listOf(second),false))
        try {for(mode in listOf(WeatherSettings.THEME_LIGHT,WeatherSettings.THEME_DARK)) {
            theme(mode)
            ActivityScenario.launch(WeatherSettingsActivity::class.java).use {screen->
                ins.waitForIdleSync();screen.onActivity{a->
                    val panel=a.findViewById<ViewGroup>(R.id.appInformationPanel)
                    assertSame(panel,a.findViewById<View>(R.id.btnUsageGuide).parent);assertSame(panel,a.findViewById<View>(R.id.btnWeatherWidgets).parent);assertSame(panel,a.findViewById<View>(R.id.btnAbout).parent)
                    assertTrue(panel.foreground is WeatherFrostedBorderDrawable);snapshot("settings-help-$mode.png",panel)
                }
            }
            val intent=Intent(c,WeatherWidgetActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("widget_edit_requested",true)
            ActivityScenario.launch<WeatherWidgetActivity>(intent).use {screen->
                ins.waitForIdleSync();screen.onActivity{a->val root=a.findViewById<View>(android.R.id.content);val panel=root.findViewWithTag<WidgetSettingsGroup>("widget_multi_city_group");assertSame(panel,root.findViewWithTag<View>("widget_multi_done").parent);assertTrue(panel.foreground is WeatherFrostedBorderDrawable);snapshot("city-editor-$mode.png",panel);root.findViewWithTag<WidgetCitySpinner>("widget_multi_slot_2").performClick()}
                ins.waitForIdleSync();screen.onActivity{a->val spinner=a.findViewById<View>(android.R.id.content).findViewWithTag<WidgetCitySpinner>("widget_multi_slot_2");val popup=spinner.openPopup ?: error("City popup must be open");assertEquals("widget_city_popup",popup.contentView.tag);assertTrue(popup.contentView.foreground is WeatherFrostedBorderDrawable);snapshot("city-popup-$mode.png",popup.contentView);popup.dismiss()}
            }
        }}finally{theme(previous);host.deleteAppWidgetId(id);WeatherWidgetStore.delete(c,id);WeatherWidgets.restore(c)}
    }
}
