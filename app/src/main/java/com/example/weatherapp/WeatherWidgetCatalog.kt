package com.example.weatherapp

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager

internal data class WeatherWidgetTemplate(val kind: String,val title: String,val info: AppWidgetProviderInfo) {
    val provider get()=info.provider
}
/** Discover every registered widget, so new providers automatically appear in the manager. */
internal object WeatherWidgetCatalog {
    const val TEMPLATE_METADATA="com.example.weatherapp.WIDGET_TEMPLATE"
    fun kind(c: Context,info: AppWidgetProviderInfo): String = runCatching {
        @Suppress("DEPRECATION")
        c.packageManager.getReceiverInfo(info.provider,PackageManager.GET_META_DATA).metaData?.getString(TEMPLATE_METADATA)
    }.getOrNull() ?: when(info.provider.className.substringAfterLast('.')) {
        "WeatherCompactWidget"->"compact";"WeatherSquareWidget"->"square"
        "WeatherLargeWidget"->"large";"WeatherWeeklyWidget"->"weekly";else->"wide"
    }
    fun all(c: Context): List<WeatherWidgetTemplate> {
        val order=listOf("compact","square","wide","weekly","large","air","rain","rain_trend","cities","daily_summary")
        return AppWidgetManager.getInstance(c).installedProviders.filter{it.provider.packageName==c.packageName}
            .map{WeatherWidgetTemplate(kind(c,it),it.loadLabel(c.packageManager).removePrefix("云松天气 · "),it)}
            .sortedWith(compareBy<WeatherWidgetTemplate>{order.indexOf(it.kind).let{n->if(n<0) 100 else n}}.thenBy{it.title})
    }
}
