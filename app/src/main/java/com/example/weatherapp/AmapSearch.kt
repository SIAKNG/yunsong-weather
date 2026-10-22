package com.example.weatherapp

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 高德地图在线城市搜索（模糊匹配，可搜到区县、乡镇等行政地名）：
// 1. 「输入提示」接口：输入关键字返回候选，行政地名的 typecode 以 19 开头
// 2. 城市级结果没有坐标，再用「行政区划」接口补城市中心点
object AmapSearch {

    /** 旧版直连入口；保留 Context 参数以兼容网关改造后的调用点。 */
    fun search(context: Context, keyword: String): List<CityInfo> = searchWithKey(keyword,
        if (BuildConfig.USER_CONFIGURABLE) UserApiConfiguration.read(context).amapKey else AmapKey.KEY)

    fun search(keyword: String): List<CityInfo> = searchWithKey(keyword, AmapKey.KEY)

    private fun searchWithKey(keyword: String, key: String): List<CityInfo> {
        if (key.isBlank()) return emptyList()
        val out = ArrayList<CityInfo>()
        try {
            for (tip in requestTips(keyword, key)) {
                var lng = tip.lng
                var lat = tip.lat
                if (lng.isNaN() || lat.isNaN()) {
                    val center = requestCenter(tip.name, key) ?: continue
                    lng = center.first
                    lat = center.second
                }
                out.add(CityInfo(tip.name, lng, lat, tip.district))
                if (out.size >= 12) break
            }
        } catch (e: Exception) {
            // 网络异常等：返回空，由调用方降级到本地城市表
        }
        return out
    }

    private data class Tip(
        val name: String,
        val district: String,
        val lng: Double,
        val lat: Double
    )

    // 输入提示：模糊搜索，只要行政地名（typecode 以 19 开头）
    private fun requestTips(keyword: String, key: String): List<Tip> {
        val urlStr = "https://restapi.amap.com/v3/assistant/inputtips" +
            "?key=$key" +
            "&keywords=${URLEncoder.encode(keyword, "UTF-8")}"
        val text = httpGet(urlStr) ?: return emptyList()
        val json = JSONObject(text)
        if (json.optString("status") != "1") return emptyList()
        val tips = json.optJSONArray("tips") ?: return emptyList()
        val out = ArrayList<Tip>()
        for (i in 0 until tips.length()) {
            val tip = tips.optJSONObject(i) ?: continue
            if (!tip.optString("typecode", "").startsWith("19")) continue
            val name = tip.optString("name", "")
            if (name.isEmpty()) continue
            val location = tip.optString("location", "")
            val parts = location.split(",")
            out.add(
                Tip(
                    name,
                    tip.optString("district", ""),
                    parts.getOrNull(0)?.toDoubleOrNull() ?: Double.NaN,
                    parts.getOrNull(1)?.toDoubleOrNull() ?: Double.NaN
                )
            )
        }
        return out
    }

    // 行政区划查询：按名字拿城市中心点坐标
    private fun requestCenter(name: String, key: String): Pair<Double, Double>? {
        val urlStr = "https://restapi.amap.com/v3/config/district" +
            "?key=$key" +
            "&keywords=${URLEncoder.encode(name, "UTF-8")}" +
            "&subdistrict=0"
        val text = httpGet(urlStr) ?: return null
        val json = JSONObject(text)
        if (json.optString("status") != "1") return null
        val districts = json.optJSONArray("districts") ?: return null
        if (districts.length() == 0) return null
        val center = districts.optJSONObject(0)?.optString("center", "") ?: return null
        val parts = center.split(",")
        val lng = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return Pair(lng, lat)
    }

    private fun httpGet(urlStr: String): String? = try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        if (BuildConfig.USER_CONFIGURABLE) conn.instanceFollowRedirects = false
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
