package com.example.weatherapp

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// 灾害预警详情页：每条预警一张卡片，标题按等级着色，正文与发布时间完整展示
class AlertActivity : AppCompatActivity() {

    private lateinit var listAlerts: ListView
    private lateinit var tvEmpty: TextView
    private val titles = ArrayList<String>()
    private val levels = ArrayList<String>()
    private val contents = ArrayList<String>()
    private val times = ArrayList<String>()
    private val locations = ArrayList<String>()
    private val sources = ArrayList<String>()
    private val codes = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkTheme = WeatherSettings.isDarkTheme(this)
        enableEdgeToEdge(
            statusBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_alert)
        val alertRoot = findViewById<View>(R.id.alertRoot)
        OplusViewSeamlessNavigator.finishWhenUserInteracts(alertRoot)
        ViewCompat.setOnApplyWindowInsetsListener(alertRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 列表背景延展至手势导航区，底部不再被非沉浸式导航底色截断。
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        listAlerts = findViewById(R.id.listAlerts)
        tvEmpty = findViewById(R.id.tvAlertEmpty)

        titles.addAll(intent.getStringArrayListExtra("titles").orEmpty())
        levels.addAll(intent.getStringArrayListExtra("levels").orEmpty())
        contents.addAll(intent.getStringArrayListExtra("contents").orEmpty())
        times.addAll(intent.getStringArrayListExtra("times").orEmpty())
        locations.addAll(intent.getStringArrayListExtra("locations").orEmpty())
        sources.addAll(intent.getStringArrayListExtra("sources").orEmpty())
        codes.addAll(intent.getStringArrayListExtra("codes").orEmpty())

        if (titles.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            listAlerts.visibility = View.GONE
        } else {
            listAlerts.adapter = AlertAdapter()
        }
    }

    private inner class AlertAdapter : BaseAdapter() {
        override fun getCount() = titles.size
        override fun getItem(position: Int) = titles[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? LinearLayout) ?: LinearLayout(this@AlertActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
                setBackgroundResource(R.drawable.search_bg)
                DetailCardEdges.apply(this)
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
                val titleRow = LinearLayout(this@AlertActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                titleRow.addView(AlertSignalView(this@AlertActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
                })
                titleRow.addView(TextView(this@AlertActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(10f) }
                    textSize = 17f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(titleRow)
                addView(TextView(this@AlertActivity).apply {
                    textSize = 14f
                    setPadding(0, dp(8f), 0, 0)
                    setLineSpacing(0f, 1.3f)
                    setTextColor(
                        ContextCompat.getColor(this@AlertActivity, R.color.text_primary)
                    )
                })
                addView(TextView(this@AlertActivity).apply {
                    textSize = 12f
                    setPadding(0, dp(10f), 0, 0)
                    setTextColor(
                        ContextCompat.getColor(this@AlertActivity, R.color.text_secondary)
                    )
                })
            }
            val titleRow = card.getChildAt(0) as LinearLayout
            val signalIcon = titleRow.getChildAt(0) as AlertSignalView
            val titleTv = titleRow.getChildAt(1) as TextView
            val contentTv = card.getChildAt(1) as TextView
            val metaTv = card.getChildAt(2) as TextView

            val level = levels.getOrNull(position).orEmpty()
            val type = alertType(codes.getOrNull(position), titles.getOrNull(position).orEmpty())
            signalIcon.setAlert(type, level)
            titleTv.text = titles[position]
            titleTv.setTextColor(levelColor(level))
            contentTv.text = contents.getOrNull(position).orEmpty()

            val line1 = ArrayList<String>()
            times.getOrNull(position)
                ?.takeIf { it.isNotBlank() }
                ?.let { line1.add("发布时间：$it") }
            locations.getOrNull(position)
                ?.takeIf { it.isNotBlank() }
                ?.let { line1.add(it) }
            val source = sources.getOrNull(position)
                ?.takeIf { it.isNotBlank() }
            metaTv.text = buildString {
                append(line1.joinToString(" · "))
                if (source != null) {
                    if (isNotEmpty()) append("\n")
                    append("来源：$source")
                }
            }
            return card
        }
    }

    // 预警类型：优先从标题匹配（彩云/和风通用），缺失时用彩云 code 前两位兜底
    private fun alertType(code: String?, title: String): String {
        val keywords = listOf(
            "雷雨大风", "道路结冰", "森林火险", "春季沙尘", "沙尘暴",
            "地质灾害", "山体滑坡", "泥石流", "山洪", "强对流", "内涝",
            "台风", "暴雨", "暴雪", "寒潮", "大风", "高温", "干旱",
            "雷电", "冰雹", "霜冻", "大雾", "霾", "沙尘"
        )
        keywords.firstOrNull { title.contains(it) }?.let { return it }
        val fromCode = when (code?.take(2)) {
            "01" -> "台风"
            "02" -> "暴雨"
            "03" -> "暴雪"
            "04" -> "寒潮"
            "05" -> "大风"
            "06" -> "沙尘暴"
            "07" -> "高温"
            "08" -> "干旱"
            "09" -> "雷电"
            "10" -> "冰雹"
            "11" -> "霜冻"
            "12" -> "大雾"
            "13" -> "霾"
            "14" -> "道路结冰"
            "15" -> "森林火险"
            "16" -> "雷雨大风"
            "17" -> "春季沙尘"
            "18" -> "沙尘"
            else -> null
        }
        if (fromCode != null) return fromCode
        return "天气"
    }

    private fun levelColor(level: String): Int = when (level) {
        "红色" -> Color.rgb(217, 83, 79)
        "橙色" -> Color.rgb(255, 149, 0)
        "黄色" -> Color.rgb(240, 173, 78)
        "蓝色" -> Color.rgb(90, 180, 220)
        else -> ContextCompat.getColor(this, R.color.text_primary)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
