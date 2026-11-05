package com.example.weatherapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

open class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context,manager: AppWidgetManager,ids: IntArray) {
        ids.forEach { WeatherWidgetStore.register(context,it) }
        WeatherWidgets.restore(context)
        WeatherWidgets.refresh(context,false)
    }
    override fun onAppWidgetOptionsChanged(context: Context,manager: AppWidgetManager,id: Int,options: Bundle) {
        WeatherWidgets.render(context)
    }
    override fun onDeleted(context: Context,ids: IntArray) {
        ids.forEach{WeatherWidgetStore.delete(context,it)}
        WeatherWidgets.schedule(context)
    }
    override fun onDisabled(context: Context) {WeatherWidgets.schedule(context)}
    override fun onReceive(context: Context,intent: Intent) {
        super.onReceive(context,intent)
        if(intent.action==WeatherWidgets.ACTION_REFRESH) {
            // Explicit immutable PendingIntent targets our non-exported refresh receiver.
            WeatherWidgets.refresh(context,true)
        }
        if(intent.action==AppWidgetManager.ACTION_APPWIDGET_RESTORED) {
            val old=(intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: intArrayOf())
            val fresh=(intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: intArrayOf())
            old.zip(fresh).forEach{(a,b)->
                if(WeatherWidgetStore.isConfigured(context,a)) WeatherWidgetStore.setCity(context,b,WeatherWidgetStore.city(context,a))
                else WeatherWidgetStore.register(context,b)
                WeatherWidgetStore.copyExtras(context,a,b)
                WeatherWidgetStore.delete(context,a)
            }
            WeatherWidgets.restore(context)
        }
    }
}
class WeatherCompactWidget: WeatherWidgetProvider()
class WeatherSquareWidget: WeatherWidgetProvider()
class WeatherWideWidget: WeatherWidgetProvider()
class WeatherLargeWidget: WeatherWidgetProvider()
class WeatherWeeklyWidget: WeatherWidgetProvider()
class WeatherAirWidget: WeatherWidgetProvider()
class WeatherRainWidget: WeatherWidgetProvider()
class WeatherRainTrendWidget: WeatherWidgetProvider()
class WeatherCitiesWidget: WeatherWidgetProvider()
class WeatherDailySummaryWidget: WeatherWidgetProvider()

internal object WeatherWidgets {
    const val ACTION_REFRESH="com.example.weatherapp.WIDGET_REFRESH"
    private const val PERIODIC="weather_widget_periodic"
    private const val IMMEDIATE="weather_widget_refresh"
    private val controlThread=Executors.newSingleThreadExecutor {r->Thread(r,"weather-widget-control")}
    private val renderThread=Executors.newSingleThreadExecutor {r->Thread(r,"weather-widget-render")}
    fun render(context: Context) {
        val c=context.applicationContext
        renderThread.execute {WeatherWidgetStore.ids(c).forEach {id ->runCatching{renderOne(c,id)}.onFailure{WeatherWidgetStore.record(c,"组件绘制失败，请检查并恢复")}}}
    }
    internal fun views(c: Context,id: Int,kind: String,width: Int,height: Int): RemoteViews {
        val configured=WeatherWidgetStore.isConfigured(c,id)
        val city=WeatherWidgetStore.city(c,id)
        val data=city?.let{WeatherWidgetData.load(c,it)}
        val open=activity(c,id,if(configured) MainActivity::class.java else WeatherWidgetActivity::class.java,if(configured) "open" else "edit")
        return RemoteViews(c.packageName,R.layout.weather_widget).apply {
            setViewVisibility(R.id.widgetLoading,if(configured) android.view.View.GONE else android.view.View.VISIBLE)
            setImageViewBitmap(R.id.widgetImage,if(configured) WeatherWidgetRenderer.renderWidget(c,kind,width,height,city,data,if(kind=="cities") WeatherWidgetStore.cities(c,id).map{it to WeatherWidgetData.load(c,it)} else emptyList())
                else WeatherWidgetRenderer.renderPlaceholder(c,width,height))
            setTextViewText(R.id.widgetLoading,"点击设置城市")
            setContentDescription(R.id.widgetImage,if(configured) WeatherWidgetRenderer.description(c,city,data) else "尚未设置城市，点击编辑")
            setViewVisibility(R.id.widgetEditArea,if(configured) android.view.View.VISIBLE else android.view.View.GONE)
            setTextViewText(R.id.widgetEditArea,WeatherWidgetRenderer.updateText(c,data))
            setTextViewTextSize(R.id.widgetEditArea,android.util.TypedValue.COMPLEX_UNIT_SP,WeatherWidgetRenderer.timestampSize(kind))
            val header=WeatherWidgetRenderer.header(c,kind,width,height,city,data)
            val density=c.resources.displayMetrics.density
            setViewPadding(R.id.widgetEditArea,(header.stampX*density).toInt(),(header.stampTop*density).toInt(),
                ((width-header.stampX-header.stampWidth).coerceAtLeast(0f)*density).toInt(),0)
            // The 4x4 timestamp scales with its image; launchers may resize bitmaps and text differently.
            if(kind=="large") setViewVisibility(R.id.widgetEditArea,android.view.View.GONE)
            if(WeatherInfoWidgetRenderer.supports(kind)) {
                setViewVisibility(R.id.widgetEditArea,android.view.View.GONE)
                setContentDescription(R.id.widgetImage,WeatherInfoWidgetRenderer.description(c,kind,data,
                    if(kind=="cities") WeatherWidgetStore.cities(c,id).map{it to WeatherWidgetData.load(c,it)} else emptyList()))
            }
            setOnClickPendingIntent(R.id.widgetImage,open)
            setOnClickPendingIntent(R.id.widgetLoading,open)
            setOnClickPendingIntent(R.id.widgetEditArea,open)
        }
    }
    private fun activity(c: Context,id: Int,clazz: Class<*>,path: String): PendingIntent {
        val i=Intent(c,clazz).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("yunsong-widget://$path/$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun renderOne(c: Context,id: Int) {
        val m=AppWidgetManager.getInstance(c)
        val info=m.getAppWidgetInfo(id) ?: return
        val kind=WeatherWidgetCatalog.kind(c,info)
        val o=m.getAppWidgetOptions(id)
        val fallback=WeatherWidgetPolicy.size(kind)
        val w=o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,(fallback.first*72-16)).coerceAtLeast(120)
        val h=o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,(fallback.second*82-16)).coerceAtLeast(64)
        m.updateAppWidget(id,views(c,id,kind,w,h))
    }
    fun schedule(c: Context) {
        WeatherWidgetRecoveryAlarm.schedule(c)
        val m=WorkManager.getInstance(c)
        if(WeatherWidgetStore.ids(c).isEmpty()) {
            listOf(PERIODIC,IMMEDIATE,"${IMMEDIATE}_manual").forEach{m.cancelUniqueWork(it)}
            return
        }
        m.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WeatherWidgetWorker>(30,TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.MINUTES).addTag("weather_widget").build())
    }
    fun restore(c: Context) {schedule(c);render(c)}
    fun refresh(c: Context,manual: Boolean) {
        if(WeatherWidgetStore.ids(c).isEmpty()) return
        recordRefreshing(c)
        val b=OneTimeWorkRequestBuilder<WeatherWidgetWorker>().setInputData(workDataOf("manual" to manual))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.MINUTES).addTag("weather_widget")
        if(manual && android.os.Build.VERSION.SDK_INT>=31) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val name=if(manual) "${IMMEDIATE}_manual" else IMMEDIATE
        val request=b.build()
        controlThread.execute {
            if(WeatherWidgetStore.ids(c).isEmpty()) return@execute
            val manager=WorkManager.getInstance(c)
            val retryWaiting=manual && runCatching {
                manager.getWorkInfosForUniqueWork(name).get(5,TimeUnit.SECONDS).any{
                    it.state==WorkInfo.State.ENQUEUED && it.runAttemptCount>0
                }
            }.getOrDefault(false)
            manager.enqueueUniqueWork(name,if(retryWaiting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)
        }
    }
    private fun recordRefreshing(c: Context) {WeatherWidgetStore.record(c,"等待联网刷新");render(c)}
}
class WeatherWidgetWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        val c=applicationContext
        WeatherReminderRecovery.repair(c)
        val cities=WeatherWidgetStore.ids(c).flatMap{ id -> if(AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.let{WeatherWidgetCatalog.kind(c,it)}=="cities") WeatherWidgetStore.cities(c,id) else listOfNotNull(WeatherWidgetStore.city(c,id)) }.distinctBy{"${it.lat},${it.lng}"}
        if(WeatherWidgetStore.ids(c).isEmpty()) return Result.success()
        if(cities.isEmpty()) {WeatherWidgetStore.record(c,"请先在 App 添加城市");WeatherWidgets.render(c);return Result.success()}
        val epoch=WeatherWidgetPolicy.sourceKey(c)
        var failed=false
        for(city in cities) {
            if(isStopped || epoch!=WeatherWidgetPolicy.sourceKey(c)) {WeatherWidgets.render(c);return Result.success()}
            val data=WeatherWidgetData.load(c,city)
            if(!inputData.getBoolean("manual",false) && data!=null && !WeatherWidgetPolicy.stale(data.observed,data.saved,System.currentTimeMillis())) continue
            // Shared weather/cache path. Optional alert delivery must not turn a successful weather refresh into a retry.
            val result=WeatherBackgroundRefresh.refresh(c,city,publishRisk=false)
            if(result!=RefreshOutcome.SUCCESS) failed=true
        }
        WeatherWidgetStore.record(c,if(failed) "刷新未成功，保留上次数据，稍后重试" else "刷新完成")
        WeatherWidgets.render(c)
        WeatherCityStore.currentLocationCity(c)?.let { WeatherReminderPreparation.prepareFromCache(c,it) }
        return if(failed && runAttemptCount<3) Result.retry() else Result.success()
    }
}
