package com.example.weatherapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.graphics.Color
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

/** One reminder per app entry, or when personal weather changes back to the free source. */
internal object FreeWeatherNoticePolicy {
    fun shouldShow(source: String, hidden: Boolean, newEntry: Boolean, previouslyFree: Boolean?): Boolean =
        source == WeatherSettings.SOURCE_OPEN_METEO && !hidden && (newEntry || previouslyFree == false)
}

internal class FreeWeatherNotice(
    private val activity: AppCompatActivity,
    private val currentSource: () -> String = { WeatherSettings.dataSource(activity) },
    private val currentSession: () -> Long = { WeatherForegroundSession.current },
    private val hidden: () -> Boolean = { isHidden(activity) }
) {
    private var evaluatedSession = -1L
    private var previouslyFree: Boolean? = null
    private var dialog: AlertDialog? = null

    fun restore(state: Bundle?) {
        if (state?.getString(STATE_PROCESS) != processId) return
        evaluatedSession = state.getLong(STATE_SESSION, -1L)
        previouslyFree = state.takeIf { it.containsKey(STATE_FREE) }?.getBoolean(STATE_FREE)
    }

    fun save(state: Bundle) {
        state.putString(STATE_PROCESS, processId)
        state.putLong(STATE_SESSION, evaluatedSession)
        previouslyFree?.let { state.putBoolean(STATE_FREE, it) }
    }

    fun maybeShow() {
        if (!BuildConfig.USER_CONFIGURABLE || activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            FirstRunGuide.isShowing(activity) || dialog?.isShowing == true) return
        val source = currentSource()
        val session = currentSession()
        val show = FreeWeatherNoticePolicy.shouldShow(source, hidden(), session != evaluatedSession, previouslyFree)
        evaluatedSession = session
        previouslyFree = source == WeatherSettings.SOURCE_OPEN_METEO
        if (show) showGlassDialog()
    }

    private fun showGlassDialog(explanation: Boolean = false) {
        val backdrop = activity.window.decorView
        val primary = ContextCompat.getColor(activity, R.color.text_primary)
        val secondary = ContextCompat.getColor(activity, R.color.text_secondary)
        val accent = ContextCompat.getColor(activity, R.color.accent)
        val panel = LinearLayout(activity).apply {
            id = R.id.freeWeatherNoticePanel
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(22))
            clipToOutline = true
        }
        fun label(textValue: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = textValue; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(4).toFloat(), 1.08f)
        }
        val provider = "Open-Meteo"
        val title = if (explanation) "为什么需要自己的 API？" else "当前使用 $provider 天气源"
        val heading = label(title, 21f, primary, true)
        if (!explanation) {
            heading.text = SpannableString(title).apply {
                setSpan(ForegroundColorSpan(accent), title.indexOf(provider), title.indexOf(provider) + provider.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        panel.addView(heading,
            LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8);bottomMargin=dp(12)})
        val body = LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        fun paragraph(value: String, color: Int = primary, bold: Boolean = false) {
            body.addView(label(value, 14f, color, bold), LinearLayout.LayoutParams(-1, -2).apply {
                if (body.childCount > 0) topMargin = dp(10)
            })
        }
        fun link(idValue: Int, value: String, callback: () -> Unit) {
            body.addView(label(value, 14f, accent, true).apply {
                id = idValue
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isFocusable = true
                setOnClickListener { callback() }
            })
        }
        fun open(url: String) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { android.widget.Toast.makeText(activity, "请用浏览器打开 $url", android.widget.Toast.LENGTH_LONG).show() }
        }
        if (explanation) {
            paragraph("彩云和和风官方都提供免费额度。额度按账号和服务规则计算，适合个人使用。若所有安装者共用一个账号，刷新、预报及后台请求都会累计到同一份额度，很容易用完。")
            paragraph("例如，和风官方将天气和基础服务合计的每月前 50,000 次请求列为 0 元，超过后可按量计费。彩云的免费试用、有效期和数据权限以控制台为准。")
            link(View.NO_ID, "查看彩云免费额度与计费") { open("https://caiyunai.com/api/pricing.html") }
            link(View.NO_ID, "查看和风天气和基础服务定价") { open("https://dev.qweather.com/docs/finance/pricing/") }
        } else {
            paragraph("免费天气使用气象预报模型，部分本地天气可能有偏差。")
            val unavailableColor = if (WeatherSettings.isDarkTheme(activity)) Color.rgb(255, 197, 201)
                else Color.rgb(143, 25, 43)
            paragraph("暂不支持分钟级降雨、官方气象预警、生活建议卡片。", unavailableColor, bold = true)
            paragraph("建议配置个人彩云或和风 API，两家均提供免费额度。")
            link(R.id.freeWeatherNoticeWhy, "为什么云松天气不内置免费的彩云和和风 API？") {
                dialog?.dismiss()
                showGlassDialog(explanation = true)
            }
            body.addView(label("不再提醒？到「天气设置 → 天气数据」开启「不再显示免费天气源提醒」。", 12f, secondary))
        }
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Keep actions reachable with large system fonts or a short screen.
                val cap=(resources.displayMetrics.heightPixels*.48f).toInt()
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
            }
        }.apply {isVerticalScrollBarEnabled=false;addView(body);clipToPadding=false}
        panel.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        fun action(idValue: Int, value: String, emphasized: Boolean, click: () -> Unit) {
            val button=TextView(activity).apply {
                id=idValue;text=value;textSize=15f;gravity=Gravity.CENTER
                typeface=Typeface.DEFAULT_BOLD;minHeight=dp(48)
                setPadding(dp(16),dp(12),dp(16),dp(12))
                isClickable=true;isFocusable=true
                setOnClickListener {click()}
            }
            panel.addView(button,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(if(emphasized)20 else 10)})
            SettingsGlassStyle.decorate(button,16f,action=true)
            button.setTextColor(if(emphasized) accent else primary)
        }
        if (explanation) {
            action(R.id.freeWeatherNoticeDismiss, "返回", true) {
                dialog?.dismiss()
                showGlassDialog()
            }
        } else {
            action(R.id.freeWeatherNoticeConfigure, "配置个人 API", true) {
                dialog?.dismiss()
                activity.startActivity(Intent(activity, ApiConfigurationActivity::class.java))
            }
            action(R.id.freeWeatherNoticeDismiss, "我知道了", false) { dialog?.dismiss() }
        }
        SettingsGlassStyle.decoratePopup(panel, backdrop, radiusDp = 28f, material = PopupGlassMaterial.FREE_NOTICE)
        dialog=AlertDialog.Builder(activity).setView(panel).create().also {notice ->
            notice.setOnDismissListener {if(dialog===notice) dialog=null}
            notice.show()
            notice.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes=attributes.apply {dimAmount = if (WeatherSettings.isDarkTheme(activity)) .16f else .07f}
                setLayout((activity.resources.displayMetrics.widthPixels-dp(40)).coerceAtMost(dp(440)),WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()

    fun dispose() { dialog?.dismiss(); dialog = null }

    companion object {
        private const val STATE_PROCESS = "free_notice_process"
        private const val STATE_SESSION = "free_notice_session"
        private const val STATE_FREE = "free_notice_previous_source"
        private const val HIDDEN = "hidden"
        private val processId = java.util.UUID.randomUUID().toString()

        private fun preferences(context: Context) =
            context.getSharedPreferences("free_weather_notice", Context.MODE_PRIVATE)

        fun isHidden(context: Context): Boolean = preferences(context).getBoolean(HIDDEN, false)

        fun setHidden(context: Context, hidden: Boolean) {
            preferences(context).edit().putBoolean(HIDDEN, hidden).apply()
        }
    }
}
