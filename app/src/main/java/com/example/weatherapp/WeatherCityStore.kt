package com.example.weatherapp

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject

/**
 * 城市列表的轻量访问层。后台任务只能更新定位坐标，不会覆盖用户手动设置的城市名称或排序。
 */
object WeatherCityStore {
    private const val PREFS = "weather_cities"
    private const val KEY_CITIES = "cities"

    fun currentLocationCity(context: Context): CityPage? = cities(context).firstOrNull { it.isCurrentLocation }
        ?: cities(context).firstOrNull()

    fun updateCurrentLocation(context: Context, location: Location): CityPage? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CITIES, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            var current: CityPage? = null
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (!item.optBoolean("isLoc", false)) continue
                item.put("lng", location.longitude)
                item.put("lat", location.latitude)
                current = CityPage(
                    item.optString("name", "当前位置"), location.longitude, location.latitude,
                    true, item.optString("detail", "")
                )
                break
            }
            if (current != null) prefs.edit().putString(KEY_CITIES, array.toString()).apply()
            current
        }.getOrNull()
    }

    private fun cities(context: Context): List<CityPage> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CITIES, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    CityPage(
                        item.optString("name", "当前位置"),
                        item.optDouble("lng"),
                        item.optDouble("lat"),
                        item.optBoolean("isLoc", false),
                        item.optString("detail", "")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
