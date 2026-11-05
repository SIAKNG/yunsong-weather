package com.example.weatherapp

import android.appwidget.AppWidgetManager
import android.content.Context
import org.json.JSONObject

internal object WeatherWidgetStore {
    private fun prefs(c: Context)=c.getSharedPreferences("weather_widgets",Context.MODE_PRIVATE)
    fun ids(c: Context)=WeatherWidgetCatalog.all(c).flatMap{AppWidgetManager.getInstance(c).getAppWidgetIds(it.provider).toList()}.distinct()
    // Upgrade only: old widgets had no configured flag, but did have a refresh attempt or saved city.
    // New installations never infer configuration from the presence of a widget ID.
    @Synchronized private fun migrate(c: Context) {
        val p=prefs(c)
        if(p.getBoolean("configuration_v2",false)) return
        val e=p.edit().putBoolean("configuration_v2",true)
        if(p.contains("attempt_at") || p.all.keys.any{it.startsWith("city_")}) {
            ids(c).forEach {id -> if(!p.contains("configured_$id")) e.putBoolean("configured_$id",true)}
        }
        e.commit()
    }
    fun isConfigured(c: Context,id: Int): Boolean {migrate(c);return prefs(c).getBoolean("configured_$id",false)}
    fun isKnown(c: Context,id: Int): Boolean {migrate(c);return prefs(c).contains("configured_$id")}
    fun register(c: Context,id: Int) {migrate(c);if(!isKnown(c,id)) prefs(c).edit().putBoolean("configured_$id",false).apply()}
    fun city(c: Context,id: Int): CityPage? {
        if(!isConfigured(c,id)) return null
        val raw=prefs(c).getString("city_$id",null) ?: return WeatherCityStore.currentLocationCity(c)?.takeIf(WeatherWidgetPolicy::validCity)
        return runCatching {
            val o=JSONObject(raw)
            if(o.optBoolean("follow")) WeatherCityStore.currentLocationCity(c)
            else CityPage(o.getString("name"),o.getDouble("lng"),o.getDouble("lat"),false)
        }.getOrNull()?.takeIf(WeatherWidgetPolicy::validCity)
    }
    fun setCity(c: Context,id: Int,city: CityPage?) {
        migrate(c)
        val e=prefs(c).edit().putBoolean("configured_$id",true)
        if(city==null || city.isCurrentLocation) e.remove("city_$id") else e.putString("city_$id",JSONObject().put("name",city.name).put("lat",city.lat).put("lng",city.lng).toString())
        e.apply()
    }
    fun cities(c: Context,id: Int): List<CityPage> {
        val primary=city(c,id) ?: return emptyList()
        val saved=prefs(c).getString("cities_$id",null)
        val extras=if(saved==null) WeatherCityStore.cities(c).filterNot{WeatherWidgetPolicy.sameCity(primary,it)}.take(2)
        else runCatching{val a=org.json.JSONArray(saved);(0 until a.length()).map{n->a.getJSONObject(n).let{CityPage(it.getString("name"),it.getDouble("lng"),it.getDouble("lat"),false)}}}.getOrDefault(emptyList())
        return (listOf(primary)+extras).filter(WeatherWidgetPolicy::validCity).distinctBy{"${it.lat},${it.lng}"}.take(3)
    }
    fun setExtraCities(c: Context,id: Int,extras: List<CityPage>) {
        val a=org.json.JSONArray();extras.take(2).filter(WeatherWidgetPolicy::validCity).forEach{a.put(JSONObject().put("name",it.name).put("lng",it.lng).put("lat",it.lat))}
        prefs(c).edit().putString("cities_$id",a.toString()).apply()
    }
    fun setCities(c: Context,id: Int,primary: CityPage,extras: List<CityPage>,follow: Boolean): Boolean {
        val all=listOf(primary)+extras
        if(all.size !in 2..3 || all.any{!WeatherWidgetPolicy.validCity(it)} || all.indices.any{i->all.take(i).any{WeatherWidgetPolicy.sameCity(it,all[i])}}) return false
        if(follow && WeatherCityStore.currentLocationCity(c)?.let{WeatherWidgetPolicy.sameCity(it,primary)}!=true) return false
        migrate(c)
        val a=org.json.JSONArray();extras.forEach{a.put(JSONObject().put("name",it.name).put("lng",it.lng).put("lat",it.lat))}
        val e=prefs(c).edit().putBoolean("configured_$id",true).putString("cities_$id",a.toString())
        if(follow) e.remove("city_$id") else e.putString("city_$id",JSONObject().put("name",primary.name).put("lat",primary.lat).put("lng",primary.lng).toString())
        e.apply();return true
    }
    fun copyExtras(c: Context,from: Int,to: Int) {prefs(c).getString("cities_$from",null)?.let{prefs(c).edit().putString("cities_$to",it).apply()}}
    fun followsLocation(c: Context,id: Int)=isConfigured(c,id) && !prefs(c).contains("city_$id")
    fun delete(c: Context,id: Int)=prefs(c).edit().remove("city_$id").remove("configured_$id").remove("cities_$id").apply()
    fun status(c: Context)=prefs(c).getString("status","尚未刷新").orEmpty()
    fun record(c: Context,status: String)=prefs(c).edit().putString("status",status).putLong("attempt_at",System.currentTimeMillis()).apply()
    fun attemptAt(c: Context)=prefs(c).getLong("attempt_at",0)
}
