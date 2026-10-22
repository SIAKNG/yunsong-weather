package com.example.weatherapp

import android.text.TextUtils
import android.widget.TextView

/** Display-only shortening: never change the saved city identity or request coordinates. */
object WeatherAddressFormatter {
    const val MAX_CODE_POINTS = 16
    fun compact(raw: String, fallback: String = "当前位置"): String {
        val clean = raw.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s+"), " ").trim()
            .ifBlank { fallback.trim().ifBlank { "当前位置" } }
        if (clean.codePointCount(0, clean.length) <= MAX_CODE_POINTS) return clean
        return clean.substring(0, clean.offsetByCodePoints(0, MAX_CODE_POINTS - 1)).trimEnd() + "…"
    }
    fun fromParts(city: String?, district: String?, street: String?, feature: String?): String {
        val locality = district.orEmpty().trim().takeUnless { it == city }.orEmpty()
        // Prefer a named street; do not append building, room or duplicate POI detail.
        val road = street.orEmpty().trim().ifBlank {
            feature.orEmpty().trim().takeUnless { it.matches(Regex("[0-9０-９\\-号弄栋幢单元楼室层]+")) }.orEmpty()
        }.replace(Regex("(?:[0-9０-９]+(?:号|栋|幢|单元|室|楼)).*$"), "").trim()
        val detail = listOf(locality, road).filter(String::isNotBlank).distinct()
            .let { if(it.size == 2 && it[1].startsWith(it[0])) listOf(it[1]) else it }.joinToString(" ")
        return compact(detail, city.orEmpty())
    }
    fun label(page: CityPage): String = if (page.isCurrentLocation)
        compact(page.locationDetail, page.name)
    else city(page.name)
    fun city(name: String): String = compact(if (name.endsWith("市") || name.endsWith("区") || name.endsWith("县") || name.endsWith("旗")) name else "${name}市")
    fun bind(view: TextView, raw: String) {
        view.setSingleLine(true)
        view.ellipsize = TextUtils.TruncateAt.END
        view.text = compact(raw)
    }
}
