package com.example.weatherapp

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 展示和风天气返回的全部生活指数，不改写其等级或建议文本。 */
class LifeAdviceActivity : AppCompatActivity() {
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        DetailCardEdges.apply(findViewById(android.R.id.content))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = WeatherSettings.isDarkTheme(this)
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // 防止系统在透明手势导航栏下额外加一层对比度底色。
            window.isNavigationBarContrastEnforced = false
        }
        setContentView(R.layout.activity_life_advice)
        val root = findViewById<View>(R.id.lifeAdviceRoot)
        // 从卡片展开后的首次滚动应优先响应用户手势，避免系统转场残留抢占输入。
        OplusViewSeamlessNavigator.finishWhenUserInteracts(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 根背景延展到手势导航栏下；列表自身保留安全底距，最后一项不会被导航条遮住。
            view.setPadding(bars.left, bars.top, bars.right, 0)
            findViewById<ScrollView>(R.id.lifeAdviceScroll).setPadding(
                dp(20), dp(8), dp(20), dp(20) + bars.bottom
            )
            insets
        }
        findViewById<View>(R.id.btnLifeAdviceBack).setOnClickListener { finish() }
        @Suppress("DEPRECATION")
        val items = intent.getSerializableExtra(EXTRA_ITEMS) as? ArrayList<LifeAdviceItem> ?: arrayListOf()
        val content = findViewById<LinearLayout>(R.id.lifeAdviceContent)
        if (items.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "和风天气生活建议暂未返回，稍后下拉刷新后再试。"
                setTextColor(ContextCompat.getColor(this@LifeAdviceActivity, R.color.text_secondary))
                textSize = 14f
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
        } else {
            val groups = listOf(
                "健康与舒适" to setOf("q5", "q7", "q8", "q9", "q10", "q11", "q16"),
                "出行与活动" to setOf("q1", "q4", "q6", "q15"),
                "日常生活" to setOf("q2", "q3", "q12", "q13", "q14")
            )
            groups.forEach { (title, keys) ->
                val rows = items.distinctBy { it.key }.filter { it.key in keys }
                if (rows.isNotEmpty()) {
                    content.addView(TextView(this).apply {
                        text = title; textSize = 13f
                        setTextColor(ContextCompat.getColor(this@LifeAdviceActivity, R.color.text_secondary))
                        setPadding(dp(5), dp(16), 0, dp(10))
                    })
                    rows.forEach { content.addView(adviceRow(it, dark)) }
                }
            }
            items.filter { item -> groups.none { item.key in it.second } }.forEach { content.addView(adviceRow(it, dark)) }
        }
    }

    private fun adviceRow(item: LifeAdviceItem, dark: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_metric_detail_panel)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        val statusText = item.level.ifBlank { "暂无等级" }
        val statusColor = LifeAdviceStatusStyle.colorFor(item, dark)
        val head = LinearLayout(this@LifeAdviceActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(LifeAdviceIconView(this@LifeAdviceActivity).apply {
            adviceKey = item.key
            // 二级页中图标线稿、图标底框和右侧等级共用同一等级色；仅用品型
            // 中性指数在 LifeAdviceStatusStyle 中回退到中性提示色。
            iconColor = statusColor
            // 每个图标均置于同尺寸的圆角色块内；以等级语义色的低透明度填充，
            // 既统一不同图形的视觉占位，也不压过标题和状态文字。
            background = GradientDrawable().apply {
                setColor(Color.argb(if (dark) 48 else 32, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor)))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) }
        })
        head.addView(TextView(this@LifeAdviceActivity).apply {
            text = item.title
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@LifeAdviceActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        })
        head.addView(TextView(this@LifeAdviceActivity).apply {
            // 等级固定在标题同一基线的右端，不再作为次级小字占据第二行。
            text = statusText
            textSize = 14f
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(8), 0, 0, 0)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(statusColor)
        })
        addView(head)
        addView(TextView(this@LifeAdviceActivity).apply {
            text = item.detail.ifBlank { "和风天气暂未提供文字建议。" }
            textSize = 14f
            setLineSpacing(0f, 1.22f)
            setTextColor(ContextCompat.getColor(this@LifeAdviceActivity, R.color.text_primary))
            setPadding(dp(52), dp(7), 0, 0)
        })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        private const val EXTRA_ITEMS = "life_advice_items"

        fun createIntent(context: Context, items: List<LifeAdviceItem>): Intent =
            Intent(context, LifeAdviceActivity::class.java)
                .putExtra(EXTRA_ITEMS, ArrayList(items))
    }
}
