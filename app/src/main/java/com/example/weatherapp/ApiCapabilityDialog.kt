package com.example.weatherapp

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ApiCapabilityDialog {
    fun show(a: AppCompatActivity, report: ApiCapabilityReport, done: ()->Unit, edit: ()->Unit, retry: ()->Unit,savedConfiguration: Boolean = true): AlertDialog {
        val d=a.resources.displayMetrics.density
        fun dp(v: Int)=(v*d).toInt()
        val dark=WeatherSettings.isDarkTheme(a)
        val primary=ContextCompat.getColor(a,R.color.text_primary)
        val secondary=ContextCompat.getColor(a,R.color.text_secondary)
        val divider=ContextCompat.getColor(a,R.color.divider)
        fun text(value: String,size: Float=13f,color: Int=primary,bold: Boolean=false)=TextView(a).apply {
            text=value;textSize=size;setTextColor(color);setLineSpacing(dp(3).toFloat(),1f)
            if(bold) setTypeface(null,Typeface.BOLD)
        }
        val root=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8)) }
        root.addView(text("${if(savedConfiguration) "配置已保存" else "仅检测 · 未保存本页修改"} · 北京示例地点\n${SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(report.checkedAt))} · ${report.requests}次检测请求",13f,secondary))
        root.addView(text("可用 ${report.rows.count{it.support==ApiSupport.SUPPORTED}} 项 · 部分可用 ${report.rows.count{it.support==ApiSupport.PARTIAL}} 项 · 暂不支持 ${report.rows.count{it.support==ApiSupport.MISSING}} 项",12f,secondary))
        fun row(values: List<String>,state: ApiSupport?=null,header: Boolean=false): LinearLayout = LinearLayout(a).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP
            val weights=listOf(.33f,.20f,.47f)
            values.forEachIndexed { i,s ->
                val color=if(i==1 && state!=null) when(state) {
                    ApiSupport.SUPPORTED -> if(dark) Color.rgb(116,218,159) else Color.rgb(25,107,62)
                    ApiSupport.PARTIAL -> if(dark) Color.rgb(255,214,122) else Color.rgb(133,88,0)
                    ApiSupport.MISSING -> if(dark) Color.rgb(255,145,145) else Color.rgb(177,35,49)
                    ApiSupport.RESTRICTED -> if(dark) Color.rgb(255,175,139) else Color.rgb(160,62,23)
                    else -> secondary
                } else primary
                addView(text(s,if(header) 13f else 12.5f,color,header||i==1).apply {
                    setPadding(0,dp(10),if(i==2) 0 else dp(8),dp(10))
                },LinearLayout.LayoutParams(0,-2,weights[i]))
            }
        }
        root.addView(row(listOf("天气功能","结果","说明"),header=true))
        val content=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        var group=""
        report.rows.forEach { r ->
            if(group!=r.provider) {
                group=r.provider
                content.addView(text(group,14f,primary,true).apply {
                    setPadding(dp(10),dp(9),dp(10),dp(9))
                    background=GradientDrawable().apply { cornerRadius=dp(8).toFloat();setColor(ContextCompat.getColor(a,R.color.input_bg)) }
                })
            }
            content.addView(row(listOf(ApiCapabilityPresentation.feature(r.feature),r.support.label,ApiCapabilityPresentation.explanation(r)),r.support))
            content.addView(View(a).apply { setBackgroundColor(divider) },LinearLayout.LayoutParams(-1,dp(1).coerceAtLeast(1)))
        }
        val scroll=ScrollView(a).apply { id=R.id.apiCapabilityTable;isVerticalScrollBarEnabled=false;addView(content) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(text("“暂不支持”指当前填写的 API 未提供，并非 App 没有此功能，也不代表永久没有权限。\n认证、额度和网络问题单独显示；重新检测可能消耗调用额度。",12f,secondary).apply { setPadding(0,dp(10),0,0) })
        val builder=AlertDialog.Builder(a).setTitle("API 功能检测").setView(root)
            .setPositiveButton(if(savedConfiguration) "返回天气" else "完成") {_,_->done()}
            .setNeutralButton("重新检测") {_,_->retry()}
            .setCancelable(false)
        if(savedConfiguration) builder.setNegativeButton("继续修改") {_,_->edit()}
        val dialog=builder.create()
        dialog.show()
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setBackgroundDrawable(GradientDrawable().apply { cornerRadius=dp(22).toFloat();setColor(ContextCompat.getColor(a,R.color.page_bg)) })
            val available=a.window.decorView.height.takeIf { it>0 } ?: a.resources.displayMetrics.heightPixels
            setLayout(a.resources.displayMetrics.widthPixels-dp(24),(available*.88f).toInt())
        }
        return dialog
    }
}
