package com.example.weatherapp

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Read-only illustrated instructions. Never signs up, logs in or accesses the clipboard. */
class ApiTutorialActivity : AppCompatActivity() {
    private var qweather = false
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var caiyunTab: TextView
    private lateinit var qweatherTab: TextView
    private fun dp(v: Int) = (v*resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this,id)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(!BuildConfig.USER_CONFIGURABLE) { finish(); return }
        val dark=WeatherSettings.isDarkTheme(this)
        val bars=if(dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle=bars, navigationBarStyle=bars)
        qweather = savedInstanceState?.getBoolean("qweather") ?: intent.getBooleanExtra("qweather",false)
        val root = LinearLayout(this).apply {
            id = R.id.apiTutorialRoot; orientation=LinearLayout.VERTICAL; setBackgroundColor(color(R.color.page_bg))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i ->
            val bars=i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(bars.left,bars.top,bars.right,bars.bottom); i
        }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        root.addView(header)
        header.addView(action("‹ 返回") { finish() },LinearLayout.LayoutParams(dp(78),dp(46)))
        header.addView(label("API 申请教程",19f,true),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(14) })
        val tabs=LinearLayout(this).apply { setPadding(dp(16),dp(8),dp(16),dp(14)) }
        caiyunTab=action("彩云 Token") { switch(false) }.apply { id=R.id.apiTutorialCaiyun }
        qweatherTab=action("和风 API Key") { switch(true) }.apply { id=R.id.apiTutorialQweather }
        tabs.addView(caiyunTab,LinearLayout.LayoutParams(0,dp(48),1f).apply { rightMargin=dp(10) })
        tabs.addView(qweatherTab,LinearLayout.LayoutParams(0,dp(48),1f))
        root.addView(tabs)
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(24)); clipChildren=false }
        scroll=ScrollView(this).apply { id=R.id.apiTutorialScroll; isVerticalScrollBarEnabled=false; clipToPadding=false; addView(content) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); render()
    }
    private fun label(text: String,size: Float=15f,bold: Boolean=false) = TextView(this).apply {
        this.text=text; textSize=size; setTextColor(color(if(bold) R.color.text_primary else R.color.text_secondary))
        setLineSpacing(dp(5).toFloat(),1.05f)
        if(bold) setTypeface(typeface,Typeface.BOLD)
    }
    private fun action(text: String,click:()->Unit) = label(text,15f,true).apply {
        gravity=Gravity.CENTER; setPadding(dp(10),dp(8),dp(10),dp(8)); minHeight=dp(48)
        isClickable=true; isFocusable=true; setOnClickListener { click() }; SettingsGlassStyle.decorate(this,14f,true)
    }
    private fun switch(q: Boolean) { qweather=q; render(); scroll.scrollTo(0,0) }
    private fun render() {
        caiyunTab.isSelected=!qweather; qweatherTab.isSelected=qweather
        listOf(caiyunTab,qweatherTab).forEach { it.setTextColor(color(if(it.isSelected) R.color.accent else R.color.text_secondary)); it.typeface=if(it.isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        content.removeAllViews()
        content.addView(label(if(qweather) "要准备：自己的 API Host ＋ API Key" else "要准备：自己的彩云天气 Token",19f,true))
        content.addView(label("按顺序照做即可。下方路径图是操作示意，不是官网截图；网页按钮可能改名，以当前官网为准。最后核对：2026-10-20。",14f).apply { setPadding(0,dp(10),0,dp(14)) })
        ApiTutorialContent.steps(qweather).forEachIndexed { index,step ->
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(18)) }
            card.addView(label("${index+1}  ${step.title}",18f,true))
            card.addView(label(step.body).apply { setPadding(0,dp(12),0,0) })
            if(step.path.isNotEmpty()) {
                val route=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12)) }
                route.addView(label("官网导航 / 填写路径示意",12f))
                step.path.forEachIndexed { i,value -> route.addView(label((if(i==0) "●  " else "↓  ")+value,14f,true).apply { setTextColor(color(R.color.accent)); setPadding(0,dp(8),0,0) }) }
                card.addView(route,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(14) })
                SettingsGlassStyle.decorate(route,12f)
            }
            step.url?.let { url ->
                card.addView(label(url,12f).apply { setPadding(0,dp(12),0,dp(8)); setTextIsSelectable(true) })
                card.addView(action(step.link) { runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }.onFailure { android.widget.Toast.makeText(this,"未找到可用浏览器，请手动打开上方网址",android.widget.Toast.LENGTH_LONG).show() } },LinearLayout.LayoutParams(-1,-2))
            }
            content.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(18) })
            SettingsGlassStyle.decorate(card,18f)
        }
        content.addView(action("我已准备好，返回填写") { finish() },LinearLayout.LayoutParams(-1,-2))
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("qweather",qweather); super.onSaveInstanceState(outState) }
}
