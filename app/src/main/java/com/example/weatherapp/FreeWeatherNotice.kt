package com.example.weatherapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
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

/** Acknowledged on first free entry and each configuration visit, never per refresh or city. */
internal object FreeWeatherNoticePolicy {
    fun shouldShow(source: String, event: String, acknowledged: String?): Boolean =
        source == WeatherSettings.SOURCE_OPEN_METEO && event != acknowledged
}

internal class FreeWeatherNotice(
    private val activity: AppCompatActivity,
    private val currentSource: () -> String = { WeatherSettings.dataSource(activity) },
    private val currentEvent: () -> String = { "${WeatherSettings.sourceKey(activity)}:${visits(activity)}" },
    private val persistAcknowledged: (String) -> Unit = {
        preferences(activity).edit().putString("acknowledged", it).apply()
    }
) {
    private var acknowledged: String? = preferences(activity).getString("acknowledged", null)
    private var dialog: AlertDialog? = null

    fun restore(state: Bundle?) { acknowledged = state?.getString(STATE) ?: acknowledged }
    fun save(state: Bundle) { state.putString(STATE, acknowledged) }

    fun maybeShow() {
        if (!BuildConfig.USER_CONFIGURABLE || activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            FirstRunGuide.isShowing(activity) || dialog?.isShowing == true) return
        val source = currentSource()
        val event = currentEvent()
        if (!FreeWeatherNoticePolicy.shouldShow(source, event, acknowledged)) return
        acknowledged = event
        persistAcknowledged(event)
        showGlassDialog()
    }

    private fun showGlassDialog() {
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
        panel.addView(label("OPEN-METEO · 免费天气", 12f, accent, true))
        panel.addView(label("当前使用免费天气源", 21f, primary, true),
            LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8);bottomMargin=dp(12)})
        val body = LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        body.addView(label("当前没有配置个人天气 API，正在使用 Open-Meteo 免费天气。",15f,primary))
        body.addView(label("数据来自境外气象预报模型，部分本地气象信息可能与实际情况存在差异。分钟级临近降雨、国内官方预警及部分生活指数等数据暂不提供。天气及空气质量信息仅供参考，重要出行请结合当地官方预报。",14f,secondary),
            LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        body.addView(label("强烈建议配置个人 API",16f,accent,true),
            LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(18);bottomMargin=dp(6)})
        body.addView(label("使用自己的彩云或和风 API，通常能提升国内天气数据的准确性和功能可用性，并补充更多本地气象信息。具体功能和数据范围以个人 API 的版本及权限为准。",14f,primary))
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
            SettingsGlassStyle.decorate(button,16f,action=true,backdrop=backdrop)
            button.setTextColor(if(emphasized) accent else primary)
        }
        action(R.id.freeWeatherNoticeConfigure,"配置个人 API",true) {
            dialog?.dismiss()
            activity.startActivity(Intent(activity,ApiConfigurationActivity::class.java))
        }
        action(R.id.freeWeatherNoticeDismiss,"我知道了",false) {dialog?.dismiss()}
        SettingsGlassStyle.decorate(panel,28f,backdrop=backdrop)
        dialog=AlertDialog.Builder(activity).setView(panel).create().also {notice ->
            notice.setOnDismissListener {if(dialog===notice) dialog=null}
            notice.show()
            notice.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes=attributes.apply {dimAmount=.62f}
                setLayout((activity.resources.displayMetrics.widthPixels-dp(40)).coerceAtMost(dp(440)),WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()

    fun dispose() { dialog?.dismiss(); dialog = null }

    companion object {
        private const val STATE = "free_weather_notice_acknowledged"
        private fun preferences(context: Context) = context.getSharedPreferences("free_weather_notice", Context.MODE_PRIVATE)
        private fun visits(context: Context) = preferences(context).getLong("configuration_visits", 0L)
        fun configurationOpened(context: Context) {
            preferences(context).edit().putLong("configuration_visits", visits(context) + 1L).apply()
        }
    }
}
