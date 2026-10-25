package com.example.weatherapp

import android.content.Intent
import android.content.res.ColorStateList
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.card.MaterialCardView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// 城市管理页：展示所有已保存城市（含实时天气），
// 点击进入城市详情；右上角"编辑"进入多选批量删除；
// 点击搜索框后下方城市卡片渐隐，显示搜索面板
class CityManageActivity : AppCompatActivity() {

    private val previewReq = 2001
    private var pendingCity: CityInfo? = null

    private class CityItem(
        var name: String,
        var lng: Double,
        var lat: Double,
        var isLoc: Boolean,
        var detail: String,
        var temp: String = "--°",
        var weather: String = "--",
        var aqi: String = "",
        var skycon: String = "",
        var night: Boolean = false,
        var selected: Boolean = false
    )

    private val items = ArrayList<CityItem>()
    // 本次运行内的温度缓存：再次进入管理页直接显示，减少重复请求
    private val tempCache = HashMap<String, String>()
    private val weatherCache = HashMap<String, String>()
    private var editMode = false
    private var currentIndex = 0
    // 每张卡片的勾选圆点和内容区，供编辑动画使用
    private val cardChecks = ArrayList<View>()
    private val cardViews = ArrayList<LinearLayout>()
    private val cardLefts = ArrayList<LinearLayout>()

    private lateinit var cityListContainer: LinearLayout
    private lateinit var cityScroll: ScrollView
    private lateinit var searchPanel: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnSearchCancel: TextView
    private lateinit var gridRecommend: GridLayout
    private lateinit var listCities: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var btnEdit: TextView
    private lateinit var btnDelete: TextView
    private lateinit var deleteBar: LinearLayout
    private var searchMode = false
    private var searchCancelAnimator: ValueAnimator? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchAdapter = CityAdapter(emptyList())

    private val recommendCities = listOf(
        "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "武汉", "西安"
    )
    // 推荐城市标签（名称 → 标签），用于标记已添加城市
    private val recommendTags = LinkedHashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与设置页一致，优先使用手机当前的 Material You 动态主题色。
        // 此前城市编辑页未应用动态色，Material3 默认紫色会覆盖系统实际的绿色。
        DynamicColors.applyToActivityIfAvailable(this)
        // 状态栏图标颜色跟随深浅色模式
        val darkTheme = WeatherSettings.isDarkTheme(this)
        enableEdgeToEdge(
            statusBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_city_manage)
        // 边到边：把状态栏/导航栏 insets 应用到整页，避免顶部栏被状态栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cityRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        cityListContainer = findViewById(R.id.cityListContainer)
        cityScroll = findViewById(R.id.cityScroll)
        searchPanel = findViewById(R.id.searchPanel)
        etSearch = findViewById(R.id.etSearch)
        // 搜索仅使用中性色，不把应用主题色扩散到输入、光标选区或边框。
        val searchNeutral = ContextCompat.getColor(this, R.color.text_tertiary)
        etSearch.highlightColor = ColorUtils.setAlphaComponent(searchNeutral, 112)
        etSearch.backgroundTintList = null
        btnSearchCancel = findViewById(R.id.btnSearchCancel)
        gridRecommend = findViewById(R.id.gridRecommend)
        listCities = findViewById(R.id.listCities)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnEdit = findViewById(R.id.btnEdit)
        btnDelete = findViewById(R.id.btnDelete)
        deleteBar = findViewById(R.id.deleteBar)

        findViewById<View>(R.id.btnBack).setOnClickListener {
            // 返回时把最终城市列表带回主界面（保留当前页索引）
            finishWithSelection(currentIndex.coerceIn(0, items.lastIndex))
        }
        btnEdit.setOnClickListener {
            editMode = !editMode
            btnEdit.text = if (editMode) "完成" else "编辑"
            deleteBar.visibility = if (editMode) View.VISIBLE else View.GONE
            if (editMode) enterEditMode() else exitEditMode()
            updateDeleteText()
        }
        btnDelete.setOnClickListener {
            // 当前定位卡不可删除，只删除选中的新增城市
            items.removeAll { it.selected && !it.isLoc }
            if (editMode) {
                editMode = false
                btnEdit.text = "编辑"
                deleteBar.visibility = View.GONE
            }
            renderCards()
            // 删除后立即写入返回结果：即使用系统手势返回，删除也不会被丢弃
            setResult(
                RESULT_OK,
                buildResultIntent(currentIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)))
            )
        }

        // 系统返回键/手势也要带回最终城市列表（此前直接 finish 会把删除结果丢掉）
        onBackPressedDispatcher.addCallback(this) {
            if (searchMode) {
                closeSearchMode()
            } else {
                finishWithSelection(currentIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)))
            }
        }

        listCities.adapter = searchAdapter
        setupSearch()
        setupRecommendTags()

        // 读取 MainActivity 传入的城市列表
        val names = intent.getStringArrayListExtra("cities") ?: arrayListOf()
        val lngs = intent.getDoubleArrayExtra("lngs") ?: DoubleArray(0)
        val lats = intent.getDoubleArrayExtra("lats") ?: DoubleArray(0)
        val isLoc = intent.getBooleanArrayExtra("isLoc") ?: BooleanArray(0)
        val details = intent.getStringArrayListExtra("details") ?: arrayListOf()
        val temps = intent.getStringArrayListExtra("temps") ?: arrayListOf()
        val descs = intent.getStringArrayListExtra("descs") ?: arrayListOf()
        val skycons = intent.getStringArrayListExtra("skycons") ?: arrayListOf()
        val nights = intent.getBooleanArrayExtra("nights") ?: BooleanArray(0)
        currentIndex = intent.getIntExtra("current", 0)
        for (i in names.indices) {
            items.add(
                CityItem(
                    name = names[i],
                    lng = lngs.getOrElse(i) { Double.NaN },
                    lat = lats.getOrElse(i) { Double.NaN },
                    isLoc = isLoc.getOrElse(i) { false },
                    detail = details.getOrNull(i).orEmpty(),
                    // 旧版只保存格式化后的字符串，切换摄氏/华氏后无法可靠反算；
                    // 进入管理页统一按当前数据源刷新，避免短暂显示上一种单位。
                    temp = "--°",
                    weather = descs.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "--",
                    skycon = skycons.getOrNull(i).orEmpty(),
                    night = nights.getOrElse(i) { false }
                )
            )
        }
        renderCards()
        updateRecommendTagStates()
    }

    // ===== 城市卡片 =====
    private fun renderCards() {
        cityListContainer.removeAllViews()
        cardChecks.clear()
        cardViews.clear()
        cardLefts.clear()
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (i in items.indices) {
            val hasFreshCache = restoreCachedCityWeather(items[i])
            cityListContainer.addView(buildCard(i))
            // 缓存使用原始摄氏温度，进入时会按当前单位重新格式化；仅缓存过期才请求。
            if (!hasFreshCache) fetchCityWeather(i)
        }
        updateDeleteText()
        updateRecommendTagStates()
    }

    private fun buildCard(index: Int): View {
        val item = items[index]
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        applyCardBackground(card, item)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12f) }
        card.tag = index

        // 有天气背景时文字转白色，否则沿用默认浅色卡片文字
        val hasWeather = item.skycon.isNotBlank()
        val nameColor = if (hasWeather) Color.WHITE
        else ContextCompat.getColor(this, R.color.text_primary)
        val tempColor = if (hasWeather) Color.WHITE
        else ContextCompat.getColor(this, R.color.text_primary)
        val weatherColor = if (hasWeather) Color.argb(230, 255, 255, 255)
        else ContextCompat.getColor(this, R.color.text_secondary)

        // 多选圆点（定位卡不显示，不可删除）
        val check = MaterialCheckBox(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f)).apply {
                marginEnd = dp(12f)
            }
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            isClickable = false
            isFocusable = false
            buttonTintList = selectionIndicatorTint()
            isChecked = item.selected
            visibility = if (editMode && !item.isLoc) View.VISIBLE else View.GONE
            alpha = if (editMode && !item.isLoc) 1f else 0f
        }
        card.addView(check)
        cardChecks.add(check)

        // 内容区：左侧城市信息 + 右侧温度天气（编辑时整体向右滑，给圆点让位）
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(
            TextView(this).apply {
                // 当前定位：直接显示详细地址；其他城市显示城市名
                text = if (item.isLoc && item.detail.isNotEmpty()) item.detail else item.name
                setTextColor(nameColor)
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }.also { it.tag = "name" }
        )

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(
            TextView(this).apply {
                text = tempCache[item.name] ?: item.temp
                setTextColor(tempColor)
                textSize = 24f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }.also { it.tag = "temp" }
        )
        right.addView(
            TextView(this).apply {
                text = weatherCache[item.name] ?: item.weather
                setTextColor(weatherColor)
                textSize = 13f
                gravity = Gravity.END
            }.also { it.tag = "weather" }
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        content.addView(left)
        content.addView(right)
        card.addView(content)
        cardViews.add(card)
        cardLefts.add(left)

        card.setOnClickListener {
            if (editMode) {
                // 当前定位卡不可删除，点按无操作
                if (!item.isLoc) {
                    item.selected = !item.selected
                    updateCheck(index)
                    updateDeleteText()
                }
            } else {
                finishWithSelection(index)
            }
        }
        return card
    }

    // 进入编辑：只有左侧城市文字向右滑动（温度不动），圆点淡入；定位卡置灰
    private fun enterEditMode() {
        for (i in items.indices) {
            val check = cardChecks.getOrNull(i) ?: continue
            val card = cardViews.getOrNull(i) ?: continue
            val left = cardLefts.getOrNull(i) ?: continue
            if (items[i].isLoc) {
                card.alpha = 0.55f
                left.animate().translationX(0f).setDuration(200).start()
                continue
            }
            check.visibility = View.VISIBLE
            check.animate().alpha(1f).setDuration(250).start()
            // 保留原有左移过渡，但缩短距离，避免城市文字压到勾选按钮。
            left.animate().translationX(dp(20f).toFloat())
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // 退出编辑：动画反向（圆点淡出，文字向左滑回原位；定位卡恢复）
    private fun exitEditMode() {
        for (i in items.indices) {
            val check = cardChecks.getOrNull(i) ?: continue
            val card = cardViews.getOrNull(i) ?: continue
            val left = cardLefts.getOrNull(i) ?: continue
            card.alpha = 1f
            if (items[i].isLoc) {
                left.animate().translationX(0f).setDuration(200).start()
                continue
            }
            check.animate().alpha(0f).setDuration(200).withEndAction {
                check.visibility = View.GONE
            }.start()
            left.animate().translationX(0f)
                .setDuration(300)
                .setInterpolator(AccelerateInterpolator())
                .start()
            items[i].selected = false
        }
        updateDeleteText()
    }

    // 只更新某张卡片的圆点选中样式，不重建整张卡片
    private fun updateCheck(index: Int) {
        val item = items.getOrNull(index) ?: return
        val check = cardChecks.getOrNull(index) ?: return
        (check as? MaterialCheckBox)?.isChecked = item.selected
    }

    // 原生 Material 多选框：主题色直接来自 colorPrimary / colorOutline，选中时有系统勾选图形。
    private fun selectionIndicatorTint(): ColorStateList {
        val primary = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(this, R.color.check_fill)
        )
        val outline = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutline,
            ContextCompat.getColor(this, R.color.check_stroke)
        )
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(primary, outline)
        )
    }

    private fun fetchCityWeather(index: Int) {
        val item = items.getOrNull(index) ?: return
        if (item.lng.isNaN() || item.lat.isNaN()) return
        val epoch=WeatherSettings.sourceKey(this)
        if (WeatherSettings.useQWeather(this)) {
            val lat = item.lat; val lng = item.lng
            Thread {
                val now = runCatching { QWeatherApi.fetchAll(this, lat, lng).now }.getOrNull()
                runOnUiThread {
                    val current = items.getOrNull(index) ?: return@runOnUiThread
                    if (isFinishing || isDestroyed || current.lat != lat || current.lng != lng || epoch!=WeatherSettings.sourceKey(this)) return@runOnUiThread
                    if (now != null) {
                        current.temp = WeatherSettings.tempText(this, now.temp)
                        current.weather = now.text
                        current.skycon = now.skycon.orEmpty()
                        current.night = isCityNight(current)
                        tempCache[current.name] = current.temp
                        weatherCache[current.name] = current.weather
                        CitySummaryCache.save(this, lat, lng, now.temp, current.weather, current.skycon)
                    } else if (current.temp.startsWith("--")) current.weather = "天气暂不可用"
                    updateCard(index)
                }
            }.start()
            return
        }
        // 城市摘要与主页同样使用统一彩云主源缓存，避免每次进入重新刷所有城市。
        WeatherDataRepository.enqueueCaiyunWeather(this, item.lng, item.lat, 3, 3) { response ->
            if(epoch!=WeatherSettings.sourceKey(this)) return@enqueueCaiyunWeather
            val cur = items.getOrNull(index) ?: return@enqueueCaiyunWeather
            val rt = response.body?.result?.realtime
            if (response.isSuccessful && rt != null) {
                cur.temp = WeatherSettings.tempText(this@CityManageActivity, rt.temperature)
                cur.weather = WeatherText.skyconToText(rt.skycon)
                cur.skycon = rt.skycon.orEmpty()
                cur.night = isCityNight(cur)
                tempCache[cur.name] = cur.temp
                weatherCache[cur.name] = cur.weather
                CitySummaryCache.save(
                    this@CityManageActivity, cur.lat, cur.lng,
                    rt.temperature, cur.weather, cur.skycon
                )
            } else if (cur.temp.startsWith("--")) {
                cur.weather = "网络错误"
            }
            updateCard(index)
        }
    }

    /** 从短时摘要缓存恢复，并按当前温标重新格式化，避免切换单位后显示旧字符串。 */
    private fun restoreCachedCityWeather(item: CityItem): Boolean {
        val cached = CitySummaryCache.load(this, item.lat, item.lng) ?: return false
        item.temp = WeatherSettings.tempText(this, cached.temperatureC)
        item.weather = cached.weather.ifBlank { item.weather }
        item.skycon = cached.skycon.ifBlank { item.skycon }
        item.night = isCityNight(item)
        tempCache[item.name] = item.temp
        weatherCache[item.name] = item.weather
        return true
    }

    private fun updateCard(index: Int) {
        val item = items.getOrNull(index) ?: return
        val card = cityListContainer.getChildAt(index) as? ViewGroup ?: return
        applyCardBackground(card, item)
        val hasWeather = item.skycon.isNotBlank()
        val nameColor = if (hasWeather) Color.WHITE
        else ContextCompat.getColor(this, R.color.text_primary)
        val tempColor = if (hasWeather) Color.WHITE
        else ContextCompat.getColor(this, R.color.text_primary)
        val weatherColor = if (hasWeather) Color.argb(230, 255, 255, 255)
        else ContextCompat.getColor(this, R.color.text_secondary)
        // 结构：card > content > (left / right) > TextView
        for (i in 0 until card.childCount) {
            val content = card.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until content.childCount) {
                val col = content.getChildAt(j) as? LinearLayout ?: continue
                for (k in 0 until col.childCount) {
                    val tv = col.getChildAt(k) as? TextView ?: continue
                    when (tv.tag) {
                        "name" -> {
                            tv.text = if (item.isLoc && item.detail.isNotEmpty()) item.detail else item.name
                            tv.setTextColor(nameColor)
                        }
                        "temp" -> {
                            tv.text = item.temp
                            tv.setTextColor(tempColor)
                        }
                        "weather" -> {
                            tv.text = item.weather
                            tv.setTextColor(weatherColor)
                        }
                    }
                }
            }
        }
    }

    // 卡片背景与该城市当前天气背景一致（共用 WeatherBackgroundPalette 同一套渐变）
    private fun applyCardBackground(card: View, item: CityItem) {
        if (card.foreground !is WeatherFrostedBorderDrawable) {
            card.foreground = WeatherFrostedBorderDrawable(resources.displayMetrics.density, 20f)
        }
        if (item.skycon.isBlank()) {
            card.setBackgroundResource(R.drawable.city_card_bg)
            return
        }
        // 阴雨、阴天的 skycon 往往不含 NIGHT；以该城市坐标的太阳高度判断，
        // 城市管理页才能和实际昼夜背景同步，而非错误地固定在白天。
        item.night = isCityNight(item)
        val colors = WeatherBackgroundPalette.colors(item.skycon, item.night)
        card.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, colors
        ).apply {
            cornerRadius = dp(20f).toFloat()
        }
    }

    private fun isCityNight(item: CityItem): Boolean {
        if (!item.lat.isFinite() || !item.lng.isFinite()) return item.night
        return SunAstronomyCalculator.solarElevation(
            System.currentTimeMillis(), item.lat, item.lng
        ) < -0.833
    }

    private fun updateDeleteText() {
        // 当前定位卡不可删除，计数时排除
        val n = items.count { it.selected && !it.isLoc }
        btnDelete.text = if (n > 0) "删除($n)" else "删除(0)"
    }

    private fun finishWithSelection(selected: Int) {
        setResult(RESULT_OK, buildResultIntent(selected))
        finish()
    }

    private fun buildResultIntent(selected: Int): Intent {
        val names = ArrayList<String>()
        val lngs = DoubleArray(items.size)
        val lats = DoubleArray(items.size)
        val isLoc = BooleanArray(items.size)
        val details = ArrayList<String>()
        for (i in items.indices) {
            names.add(items[i].name)
            lngs[i] = items[i].lng
            lats[i] = items[i].lat
            isLoc[i] = items[i].isLoc
            details.add(items[i].detail)
        }
        return Intent().apply {
            putStringArrayListExtra("cities", names)
            putExtra("lngs", lngs)
            putExtra("lats", lats)
            putExtra("isLoc", isLoc)
            putStringArrayListExtra("details", details)
            putExtra("selected", selected)
        }
    }

    // ===== 搜索 =====
    private fun setupSearch() {
        btnSearchCancel.setOnClickListener { closeSearchMode() }
        etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showSearchPanel()
            } else if (etSearch.text.isNullOrBlank()) {
                hideSearchPanel()
            }
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchHandler.removeCallbacksAndMessages(null)
                val kw = s?.toString()?.trim().orEmpty()
                if (kw.isEmpty()) {
                    searchAdapter.update(emptyList())
                    gridRecommend.visibility = View.VISIBLE
                } else {
                    searchHandler.postDelayed({ search(kw) }, 300)
                }
            }
        })
    }

    private fun showSearchPanel() {
        if (searchMode) return
        searchMode = true
        animateSearchCancel(show = true)
        // 进入搜索输入界面：隐藏右上角“编辑”；若正处于编辑模式先退出
        if (editMode) {
            editMode = false
            btnEdit.text = "编辑"
            deleteBar.visibility = View.GONE
            exitEditMode()
        }
        btnEdit.visibility = View.INVISIBLE
        cityScroll.animate().alpha(0f).setDuration(200).withEndAction {
            cityScroll.visibility = View.GONE
        }.start()
        searchPanel.alpha = 0f
        searchPanel.visibility = View.VISIBLE
        searchPanel.animate().alpha(1f).setDuration(200).start()
        tvEmpty.visibility = View.GONE
    }

    private fun hideSearchPanel() {
        if (!searchMode) return
        searchMode = false
        animateSearchCancel(show = false)
        btnEdit.visibility = View.VISIBLE
        searchPanel.animate().alpha(0f).setDuration(200).withEndAction {
            searchPanel.visibility = View.GONE
        }.start()
        cityScroll.alpha = 0f
        cityScroll.visibility = View.VISIBLE
        cityScroll.animate().alpha(1f).setDuration(200).start()
    }

    private fun closeSearchMode() {
        if (!searchMode) return
        searchHandler.removeCallbacksAndMessages(null)
        // 先标记退出，避免 setText/clearFocus 触发的回调重复播放两套动画。
        searchMode = false
        etSearch.setText("")
        etSearch.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        animateSearchCancel(show = false)
        btnEdit.visibility = View.VISIBLE
        searchPanel.animate().alpha(0f).setDuration(180).withEndAction {
            searchPanel.visibility = View.GONE
        }.start()
        cityScroll.alpha = 0f
        cityScroll.visibility = View.VISIBLE
        cityScroll.animate().alpha(1f).setDuration(180).start()
    }

    // 取消按钮通过自身宽度给搜索框让位；Fast-out/slow-in 的非线性曲线让缩放更自然。
    private fun animateSearchCancel(show: Boolean) {
        searchCancelAnimator?.cancel()
        val target = if (show) dp(52f) else 0
        if (show) {
            btnSearchCancel.visibility = View.VISIBLE
            btnSearchCancel.isClickable = true
        }
        val lp = btnSearchCancel.layoutParams
        val start = lp.width.coerceAtLeast(0)
        searchCancelAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = 240L
            interpolator = if (show) DecelerateInterpolator(1.7f) else AccelerateInterpolator(1.35f)
            addUpdateListener { animator ->
                lp.width = animator.animatedValue as Int
                btnSearchCancel.layoutParams = lp
                btnSearchCancel.alpha = (lp.width.toFloat() / dp(52f)).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!show && lp.width == 0) {
                        btnSearchCancel.visibility = View.GONE
                        btnSearchCancel.isClickable = false
                    }
                }
            })
            start()
        }
    }

    private fun setupRecommendTags() {
        for (name in recommendCities) {
            val tag = TextView(this).apply {
                text = name
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@CityManageActivity, R.color.text_primary))
                setBackgroundResource(R.drawable.tag_bg)
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            }
            tag.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            }
            tag.setOnClickListener { source ->
                val c = ChinaCities.cities.firstOrNull { it.name == name }
                if (c != null) pickCity(c, source)
            }
            recommendTags[name] = tag
            gridRecommend.addView(tag)
        }
    }

    // 已添加城市的推荐标签：严格读取当前主题，不能固定为绿色。
    private fun updateRecommendTagStates() {
        for ((name, tag) in recommendTags) {
            val added = items.any { it.name == name }
            if (added) {
                tag.text = "✓ $name"
                val primaryContainer = MaterialColors.getColor(
                    tag, com.google.android.material.R.attr.colorPrimaryContainer
                )
                val onPrimaryContainer = MaterialColors.getColor(
                    tag, com.google.android.material.R.attr.colorOnPrimaryContainer
                )
                tag.setTextColor(onPrimaryContainer)
                tag.background = GradientDrawable().apply {
                    cornerRadius = dp(20f).toFloat()
                    setColor(primaryContainer)
                }
            } else {
                tag.text = name
                tag.setTextColor(
                    ContextCompat.getColor(this@CityManageActivity, R.color.text_primary)
                )
                tag.setBackgroundResource(R.drawable.tag_bg)
            }
        }
    }

    private fun search(keyword: String) {
        Thread {
            val results = AmapSearch.search(this, keyword)
            runOnUiThread {
                gridRecommend.visibility = View.GONE
                if (results.isEmpty()) {
                    // 在线失败时回退内置城市表
                    val local = ChinaCities.cities.filter {
                        it.name.contains(keyword) || it.province.contains(keyword)
                    }
                    searchAdapter.update(local)
                } else {
                    searchAdapter.update(results)
                }
            }
        }.start()
    }

    private fun pickCity(city: CityInfo, source: View? = null) {
        // 已添加的城市：不进预览，直接跳到该城市天气并提示
        val exist = items.indexOfFirst { it.name == city.name }
        if (exist >= 0) {
            Toast.makeText(this, "该城市已添加", Toast.LENGTH_SHORT).show()
            finishWithSelection(exist)
            return
        }
        // 先弹出新城市天气预览，确认后再添加
        pendingCity = city
        val intent = Intent(this, CityPreviewActivity::class.java)
            .putExtra("city", city.name)
            .putExtra("lng", city.lng)
            .putExtra("lat", city.lat)
        if (source == null) {
            startActivityForResult(intent, previewReq)
        } else {
            OplusViewSeamlessNavigator.startForResultFromView(
                this, source, intent, previewReq, cornerRadiusDp = 8f
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != previewReq) return
        val city = pendingCity ?: return
        pendingCity = null
        if (resultCode != RESULT_OK) return
        val exist = items.indexOfFirst { it.name == city.name }
        val idx: Int
        if (exist >= 0) {
            idx = exist
        } else {
            items.add(CityItem(city.name, city.lng, city.lat, false, ""))
            idx = items.size - 1
        }
        finishWithSelection(idx)
    }

    private inner class CityAdapter(private var list: List<CityInfo>) : BaseAdapter() {
        fun update(newList: List<CityInfo>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun getCount() = list.size
        override fun getItem(position: Int) = list[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val city = list[position]
            val card = (convertView as? MaterialCardView) ?: MaterialCardView(this@CityManageActivity).apply {
                radius = dp(16f).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1f)
                val surface = ContextCompat.getColor(this@CityManageActivity, R.color.metric_detail_panel)
                val divider = ContextCompat.getColor(this@CityManageActivity, R.color.divider)
                setCardBackgroundColor(surface)
                strokeColor = divider
                rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(divider, 44))
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
                addView(TextView(this@CityManageActivity).apply {
                    setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(this@CityManageActivity, R.color.text_primary))
                })
            }
            val tv = card.getChildAt(0) as TextView
            tv.text = if (city.province.isNotBlank()) "${city.name}（${city.province}）" else city.name
            card.setOnClickListener { source -> pickCity(city, source) }
            return card
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
