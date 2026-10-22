package com.example.weatherapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/** Required interactions are persisted independently from whether the guide was opened. */
class MandatoryGuideProgress(val count: Int, index: Int = 0, mask: Int = 0) {
    init { require(count in 1..30) }
    var index = index.coerceIn(0, count - 1); private set
    var mask = mask and ((1 shl count) - 1); private set
    val canAdvance get() = mask and (1 shl index) != 0
    val allComplete get() = mask == (1 shl count) - 1
    fun completeCurrent() { mask = mask or (1 shl index) }
    fun next(): Boolean { if (!canAdvance || index == count - 1) return false; index++; return true }
    fun previous() { if (index > 0) index-- }
}

/** A native, illustrated practice surface. No real city, permission or credential is modified. */
object FirstRunGuide {
    const val PREFS = "weather_first_run_guide"
    const val COMPLETION_KEY = "completed_v2"
    data class Step(val title: String, val body: String)
    fun steps(userApi: Boolean): List<Step> = buildList {
        add(Step("点击地址，管理城市", "试着点击高亮地址栏，再选择演示城市。真实首页也可以这样添加城市，左右滑动切换城市。"))
        add(Step("点开卡片，拖动查看预报", "先打开 48 小时卡片，再左右拖动图表查看时间与数值。其他天气卡片也能点击查看详情。"))
        add(Step("向下拉动，刷新天气", "在演示首页向下拖动，完成一次刷新。离线时会保留上次天气；通知可在设置中按需开启。"))
        if (userApi) {
            add(Step("找到天气源与官方 API", "点击设置，再打开天气源。彩云使用 v2 Token（不是 App Key + Secret）；和风仅需自己的 API Host 和 API Key。配置页的获取按钮提供逐步申请教程。"))
            add(Step("练习保存，查看接口能力", "点击演示表单的保存按钮，预览支持度表格。真实保存后会实测接口字段与范围；这里仅使用虚拟数据，不需要输入密钥。"))
        }
    }
    fun needsIntroduction(context: Context) = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(COMPLETION_KEY, false)
    fun isShowing(activity: AppCompatActivity) = activity.findViewById<View>(R.id.guideRoot) != null

    fun show(activity: AppCompatActivity, force: Boolean = false, onFinished: (configureApi: Boolean) -> Unit) {
        if (isShowing(activity)) return
        val mandatory = needsIntroduction(activity)
        if (!force && !mandatory) { onFinished(false); return }
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = steps(BuildConfig.USER_CONFIGURABLE)
        val state = MandatoryGuideProgress(items.size, if (mandatory) prefs.getInt("index_v2", 0) else 0,
            if (mandatory) prefs.getInt("mask_v2", 0) else 0)
        fun persist() { if (mandatory) prefs.edit().putInt("index_v2", state.index).putInt("mask_v2", state.mask).apply() }
        val density = activity.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        fun color(id: Int) = ContextCompat.getColor(activity, id)
        val parent = activity.findViewById<ViewGroup>(android.R.id.content)
        val hidden = (0 until parent.childCount).map { parent.getChildAt(it) to parent.getChildAt(it).importantForAccessibility }
        hidden.forEach { it.first.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val root = LinearLayout(activity).apply {
            id = R.id.guideRoot; orientation = LinearLayout.VERTICAL; isClickable = true; isFocusable = true
            setBackgroundColor(color(R.color.page_bg))
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        parent.addView(root, ViewGroup.LayoutParams(-1, -1))
        root.alpha=0f;root.translationY=dp(10).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(320)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f)).start()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(20), bars.top + dp(12), dp(20), bars.bottom + dp(8)); insets
        }
        ViewCompat.requestApplyInsets(root)
        fun label(size: Float, secondary: Boolean = false) = TextView(activity).apply {
            textSize = size; setTextColor(color(if (secondary) R.color.text_secondary else R.color.text_primary))
            setLineSpacing(dp(3).toFloat(), 1f)
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val progress = label(13f, true).apply { id = R.id.guideProgress }
        val title = label(23f).apply { setTypeface(typeface, Typeface.BOLD) }
        val body = label(15f, true)
        val scene = GuideInteractionView(activity).apply { id = R.id.guideScene }
        root.addView(scene, LinearLayout.LayoutParams(-1, 0, 1f))
        val links = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val linkScroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(links) }
        root.addView(linkScroll, LinearLayout.LayoutParams(-1, dp(80)))
        fun link(text: String, url: String) {
            links.addView(MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                this.text = text; textSize = 13f
                setOnClickListener { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            }, LinearLayout.LayoutParams(-1, dp(40)))
        }
        link("彩云官方：申请 Token", "https://docs.caiyunapp.com/weather-api/dev.html")
        link("和风官方：项目与凭据", "https://dev.qweather.com/docs/configuration/project-and-key/")
        link("和风官方：查询 API Host", "https://dev.qweather.com/docs/configuration/api-host/")
        val hint = label(14f).apply { id = R.id.guideHint; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        val action = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = R.id.guideAction; textSize = 14f
        }
        root.addView(action, LinearLayout.LayoutParams(-1, -2))
        val nav = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        val previous = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            id = R.id.guidePrevious; text = "上一步"
        }
        val next = MaterialButton(activity).apply { id = R.id.guideNext }
        nav.addView(previous, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(next, LinearLayout.LayoutParams(0, -2, 2f))
        var ended = false
        lateinit var back: OnBackPressedCallback
        fun remove() {
            if (ended) return
            ended = true; back.remove(); parent.removeView(root)
            hidden.forEach { it.first.importantForAccessibility = it.second }
        }
        if (!mandatory) root.addView(MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "关闭演示"; setOnClickListener { remove() }
        }, LinearLayout.LayoutParams(-1, -2))
        back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!mandatory) remove()
                else Toast.makeText(activity, "请完成当前操作后继续引导；可用系统主页键暂时离开", Toast.LENGTH_SHORT).show()
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        fun updateActions() {
            hint.text = if (state.canAdvance) "✓ 已完成，点击下方继续" else scene.instruction()
            action.text = scene.actionLabel()
            action.isEnabled = !state.canAdvance
            next.isEnabled = state.canAdvance
            next.alpha = if (state.canAdvance) 1f else .38f
        }
        fun render() {
            progress.text = "操作引导  ${state.index + 1} / ${items.size}  ·  演示不修改真实数据"
            title.text = items[state.index].title; body.text = items[state.index].body
            listOf(title,body).forEach { label ->
                label.animate().cancel();label.alpha=0f;label.translationY=dp(5).toFloat()
                label.animate().alpha(1f).translationY(0f).setDuration(280)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f)).start()
            }
            linkScroll.visibility = if (state.index >= 3) View.VISIBLE else View.GONE
            previous.isEnabled = state.index > 0
            next.text = if (state.index < items.lastIndex) "下一步"
                else if (BuildConfig.USER_CONFIGURABLE) "完成并填写 API" else "开始使用"
            scene.setStep(state.index, state.canAdvance)
            updateActions(); persist()
        }
        scene.onStateChanged = { done ->
            if (done) state.completeCurrent()
            updateActions(); persist()
        }
        action.setOnClickListener { scene.performDemonstration() }
        previous.setOnClickListener { state.previous(); render() }
        next.setOnClickListener {
            if (!state.canAdvance) return@setOnClickListener
            if (state.next()) render()
            else if (state.allComplete) {
                prefs.edit().putBoolean(COMPLETION_KEY, true).remove("index_v2").remove("mask_v2").apply()
                remove(); onFinished(BuildConfig.USER_CONFIGURABLE)
            }
        }
        render()
    }
}
