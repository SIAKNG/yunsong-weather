package com.example.weatherapp

import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** Ordinary system-font credit, not an altered or extracted third-party wordmark. */
internal object WeatherSourceAttribution {
    val providers = listOf("彩云天气" to "https://caiyunapp.com/", "和风天气" to "https://www.qweather.com/")
    fun bind(first: ImageView,second: ImageView,label: TextView) {
        if (!BuildConfig.USER_CONFIGURABLE) return
        first.visibility=View.GONE;second.visibility=View.GONE
        val text="数据来自 彩云天气 · 和风天气"
        label.text=SpannableString(text).apply {
            providers.forEach { (name,url)->
                val begin=text.indexOf(name)
                setSpan(URLSpan(url),begin,begin+name.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        label.movementMethod=LinkMovementMethod.getInstance()
        label.setLinkTextColor(label.currentTextColor)
        label.layoutParams=label.layoutParams.apply {height=ViewGroup.LayoutParams.WRAP_CONTENT}
        (label.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart=0
        val padding=(label.resources.displayMetrics.density*3).toInt()
        label.setPadding(0,padding,0,padding)
    }
}
