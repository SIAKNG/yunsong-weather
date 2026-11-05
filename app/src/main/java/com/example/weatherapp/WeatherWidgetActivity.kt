package com.example.weatherapp

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Configuration and recovery are explicit actions; no new location or notification permission. */
class WeatherWidgetActivity: AppCompatActivity() {
    private var widgetId=AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var box: LinearLayout
    private var activeActionGroup: WidgetSettingsGroup?=null
    private var multiEditor: WidgetMultiCityEditor?=null
    private lateinit var diagnostics: TextView
    private val dp get()=resources.displayMetrics.density
    private fun px(v: Int)=(v*dp).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        widgetId=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(widgetId!=AppWidgetManager.INVALID_APPWIDGET_ID && AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.packageName!=packageName) {finish();return}
        val multi=widgetId!=AppWidgetManager.INVALID_APPWIDGET_ID && AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.let{WeatherWidgetCatalog.kind(this,it)}=="cities"
        // Older launchers require a configure callback before placing a widget. Complete that
        // first callback without choosing a city; the placed placeholder opens the actual editor.
        if(widgetId!=AppWidgetManager.INVALID_APPWIDGET_ID && intent.action==AppWidgetManager.ACTION_APPWIDGET_CONFIGURE &&
            !multi && !WeatherWidgetStore.isConfigured(this,widgetId) && !intent.getBooleanExtra("widget_edit_requested",false)) {
            WeatherWidgetStore.register(this,widgetId)
            setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId));finish();return
        }
        box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(px(16),px(8),px(16),px(32))}
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(ContextCompat.getColor(this@WeatherWidgetActivity,R.color.page_bg))
            fitsSystemWindows=true
        }
        val toolbar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(px(12),px(8),px(12),px(8))}
        toolbar.addView(ImageView(this).apply{setImageResource(R.drawable.ic_back_arrow);setColorFilter(ContextCompat.getColor(context,R.color.text_primary));setPadding(px(8),px(8),px(8),px(8));contentDescription="返回";setOnClickListener{finish()};WidgetSettingsGroup.ripple(this)},LinearLayout.LayoutParams(px(40),px(40)))
        toolbar.addView(TextView(this).apply{text=if(widgetId==AppWidgetManager.INVALID_APPWIDGET_ID) "桌面小组件" else "编辑天气小组件";textSize=18f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(ContextCompat.getColor(context,R.color.text_primary));gravity=android.view.Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(View(this),LinearLayout.LayoutParams(px(40),px(1)));root.addView(toolbar)
        root.addView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(box)},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        label(if(widgetId==AppWidgetManager.INVALID_APPWIDGET_ID) "选择尺寸与布局" else "选择组件城市",19f)
        label(if(widgetId==AppWidgetManager.INVALID_APPWIDGET_ID) "与首页共用天气源及缓存。新添加的组件需先点击设置城市，之后点击主体打开天气。可长按后选择编辑，或在本页更换城市。刷新与排查仍在本页。尺寸按行×列。"
            else if(multi) "前两城必选，第三城可不显示。选择完成后点击完成保存。" else "选择城市后立即保存，仅更改当前小组件；其他小组件和首页城市保持不变。",14f)
        if(widgetId==AppWidgetManager.INVALID_APPWIDGET_ID) {
            val manager=AppWidgetManager.getInstance(this)
            val placed=WeatherWidgetStore.ids(this)
            if(placed.isNotEmpty()) {
                label("已添加的小组件",18f)
                activeActionGroup=WidgetSettingsGroup(this,"widget_added_group").also{box.addView(it,LinearLayout.LayoutParams(-1,-2).apply{topMargin=px(10)})}
                placed.forEach {widgetId ->
                    val info=manager.getAppWidgetInfo(widgetId)
                    val title=info?.loadLabel(packageManager).orEmpty()
                    val city=if(WeatherWidgetStore.isConfigured(this,widgetId)) WeatherWidgetStore.city(this,widgetId)?.let{WeatherAddressFormatter.city(it.name)} ?: "等待定位" else "未设置城市"
                    action("编辑 $title · $city", "widget_edit_$widgetId") {
                        startActivity(Intent(this,WeatherWidgetActivity::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId).putExtra("widget_edit_requested",true))
                    }
                }
                activeActionGroup=null
                label("添加新小组件",18f)
            }
            val choices=WeatherWidgetCatalog.all(this)
            choices.forEach{template ->
                val title=template.title;val kind=template.kind
                val city=WeatherCityStore.currentLocationCity(this)
                val image=ImageView(this).apply{adjustViewBounds=true;contentDescription=title;tag="widget_preview_$kind"}
                box.addView(image,LinearLayout.LayoutParams(-1,px(if(kind=="compact") 76 else if(kind=="large" || kind=="daily_summary") 350 else 166)).apply{topMargin=px(14)})
                lifecycleScope.launch {
                    val bitmap=withContext(Dispatchers.IO){WeatherWidgetRenderer.render(this@WeatherWidgetActivity,kind,if(kind in listOf("square","compact","air","rain")) 166 else 340,if(kind=="compact") 76 else if(kind=="large" || kind=="daily_summary") 350 else 166,city,city?.let{WeatherWidgetData.load(this@WeatherWidgetActivity,it)},if(kind=="cities") WeatherCityStore.cities(this@WeatherWidgetActivity).take(3).map{it to WeatherWidgetData.load(this@WeatherWidgetActivity,it)} else emptyList())}
                    image.setImageBitmap(bitmap)
                }
                action("添加 $title","widget_add_$kind") {
                    val m=AppWidgetManager.getInstance(this)
                    if(android.os.Build.VERSION.SDK_INT>=26 && m.isRequestPinAppWidgetSupported) {
                        if(!m.requestPinAppWidget(template.provider,null,null)) Toast.makeText(this,"请长按桌面空白处，从小组件中添加",Toast.LENGTH_LONG).show()
                    } else Toast.makeText(this,"请长按桌面空白处，选择小组件 → 云松天气",Toast.LENGTH_LONG).show()
                }
            }
        } else if(multi) {
            multiEditor=WidgetMultiCityEditor(this,widgetId,state) {
                WeatherWidgets.restore(this);WeatherWidgets.refresh(this,false)
                setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId));finish()
            }.also{box.addView(it)}
        } else {
            val current=WeatherWidgetStore.city(this,widgetId)
            val follow=WeatherWidgetStore.followsLocation(this,widgetId)
            label("当前城市：${current?.let{WeatherAddressFormatter.city(it.name)} ?: "暂未添加城市"}",15f)
            activeActionGroup=WidgetSettingsGroup(this,"widget_city_choices_group").also{box.addView(it,LinearLayout.LayoutParams(-1,-2).apply{topMargin=px(10)})}
            val location=WeatherCityStore.currentLocationCity(this)
            if(location!=null) action((if(follow) "✓  " else "")+"跟随已保存的定位城市", "widget_city_follow") {choose(null)}
            WeatherCityStore.cities(this).filter{!it.isCurrentLocation}.forEachIndexed {index,city ->
                val selected=!follow && current?.let{WeatherWidgetPolicy.sameCity(it,city)}==true
                action((if(selected) "✓  " else "")+WeatherAddressFormatter.city(city.name),"widget_city_$index"){choose(city)}
            }
            activeActionGroup=null
            label("如需其他城市，请先在 App 的城市管理中添加。",13f)
        }
        label("刷新与状态排查",14f).setTextColor(ContextCompat.getColor(this,R.color.accent))
        val recoveryGroup=WidgetSettingsGroup(this,"widget_recovery_group").also{box.addView(it,LinearLayout.LayoutParams(-1,-2).apply{topMargin=px(10)})}
        diagnostics=TextView(this).apply{text="读取状态…";textSize=14f;setTextColor(ContextCompat.getColor(context,R.color.text_secondary));setPadding(px(16),px(16),px(16),px(16));setLineSpacing(px(4).toFloat(),1f);tag="widget_diagnostics"}
        recoveryGroup.row(diagnostics);activeActionGroup=recoveryGroup
        action("立即刷新","widget_refresh_action") {WeatherWidgets.refresh(this,true);showDiagnostics()}
        action("检查并恢复刷新与通知排程","widget_recover_action") {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    WeatherWidgets.restore(this@WeatherWidgetActivity)
                    WeatherBackgroundScheduler.schedule(this@WeatherWidgetActivity)
                    WeatherNotificationCenter.updateSchedules(this@WeatherWidgetActivity)
                    WeatherWidgets.refresh(this@WeatherWidgetActivity,true)
                }
                Toast.makeText(this@WeatherWidgetActivity,"已检查并恢复已启用的任务",Toast.LENGTH_SHORT).show();showDiagnostics()
            }
        }
        activeActionGroup=null
        label("系统省电、网络和强行停止会影响后台刷新；组件显示更新时间，过期时标注缓存。恢复只修复排程，不改变提醒开关、不重复发送已成功通知，也不会持续获取位置。",13f)
    }
    override fun onSaveInstanceState(outState: Bundle) {multiEditor?.saveDraft(outState);super.onSaveInstanceState(outState)}
    override fun onResume(){super.onResume();if(::diagnostics.isInitialized) showDiagnostics()}
    private fun choose(city: CityPage?) {
        WeatherWidgetStore.setCity(this,widgetId,city);WeatherWidgets.restore(this);WeatherWidgets.refresh(this,false)
        setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId));finish()
    }
    private fun label(value: String,size: Float)=TextView(this).apply {
        text=value;textSize=size;setTextColor(ContextCompat.getColor(context,R.color.text_primary));setLineSpacing(px(5).toFloat(),1f)
        box.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=px(12);bottomMargin=px(5)})
    }
    private fun action(value: String,viewTag: String?=null,click: ()->Unit) {
        activeActionGroup?.let{it.action(value,viewTag,click);return}
        val v=label(value,15f).apply {tag=viewTag;gravity=android.view.Gravity.CENTER;setPadding(px(14),px(15),px(14),px(15));isClickable=true;isFocusable=true;setOnClickListener{click()}}
        SettingsGlassStyle.decorate(v,18f,true)
        if(viewTag!=null) v.tag=viewTag
    }
    private fun showDiagnostics() {
        lifecycleScope.launch {
            val value=withContext(Dispatchers.IO) {
                val ids=WeatherWidgetStore.ids(this@WeatherWidgetActivity)
                val city=if(widgetId==AppWidgetManager.INVALID_APPWIDGET_ID) WeatherCityStore.currentLocationCity(this@WeatherWidgetActivity) else WeatherWidgetStore.city(this@WeatherWidgetActivity,widgetId)
                val d=city?.let{WeatherWidgetData.load(this@WeatherWidgetActivity,it)}
                val at=WeatherWidgetStore.attemptAt(this@WeatherWidgetActivity)
                val date=if(at>0) SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(at)) else "尚无"
                "已添加 ${ids.size} 个组件\n刷新兜底：${WeatherWidgetRecoveryAlarm.nextAt(this@WeatherWidgetActivity).takeIf{it>0}?.let { SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(it)) } ?: "未排程"}\n天气源：${WeatherWidgetData.sourceName(WeatherSettings.dataSource(this@WeatherWidgetActivity))}\n共享缓存：${if(d==null) "暂无可用实况" else if(WeatherWidgetPolicy.stale(d.observed,d.saved,System.currentTimeMillis())) "上次数据，等待刷新" else "实况有效"}\n最近任务：$date · ${WeatherWidgetStore.status(this@WeatherWidgetActivity)}\n\n${WeatherNotificationCenter.diagnosticsText(this@WeatherWidgetActivity)}"
            }
            diagnostics.text=value
        }
    }
}
