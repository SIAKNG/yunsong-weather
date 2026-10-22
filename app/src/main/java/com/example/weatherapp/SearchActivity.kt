package com.example.weatherapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// 城市搜索页：输入关键字经自有 Gateway 调用高德，可搜区县/乡镇；
// 网关不可用时自动降级为内置城市表。
class SearchActivity : AppCompatActivity() {

    private val previewReq = 1001

    private lateinit var etSearch: EditText
    private lateinit var listView: ListView
    private lateinit var gridRecommend: GridLayout
    private lateinit var titleRecommend: TextView
    private lateinit var tvState: TextView
    private lateinit var adapter: CityAdapter
    private val searchHandler = Handler(Looper.getMainLooper())

    // 推荐城市（搜索框为空时显示）
    private val recommendCities = listOf(
        "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "武汉", "西安"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏图标颜色跟随深浅色模式
        val darkTheme = WeatherSettings.isDarkTheme(this)
        enableEdgeToEdge(
            statusBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_search)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.searchRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        etSearch = findViewById(R.id.etSearch)
        listView = findViewById(R.id.listCities)
        gridRecommend = findViewById(R.id.gridRecommend)
        titleRecommend = findViewById(R.id.titleRecommend)
        tvState = findViewById(R.id.tvState)

        adapter = CityAdapter(emptyList())
        listView.adapter = adapter

        setupRecommendTags()
        showRecommend()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchHandler.removeCallbacksAndMessages(null)
                val kw = s?.toString()?.trim() ?: ""
                if (kw.isEmpty()) {
                    showRecommend()
                } else {
                    // 防抖：停止输入 300ms 后才搜索
                    searchHandler.postDelayed({ search(kw) }, 300)
                }
            }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        listView.setOnItemClickListener { _, source, position, _ ->
            val city = adapter.getItem(position) ?: return@setOnItemClickListener
            pickCity(city, source)
        }
    }

    private fun setupRecommendTags() {
        for (name in recommendCities) {
            val tag = TextView(this).apply {
                text = name
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.text_primary))
                setBackgroundResource(R.drawable.tag_bg)
            }
            tag.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = dp(40f)
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            }
            tag.setOnClickListener { source ->
                val c = ChinaCities.cities.firstOrNull { it.name == name }
                if (c != null) pickCity(c, source)
            }
            gridRecommend.addView(tag)
        }
    }

    private fun showRecommend() {
        titleRecommend.visibility = View.VISIBLE
        gridRecommend.visibility = View.VISIBLE
        tvState.visibility = View.GONE
        adapter.update(emptyList())
    }

    private fun pickCity(city: CityInfo, source: View? = null) {
        // 先弹出新城市天气预览，确认后再返回
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
        if (requestCode == previewReq && resultCode == RESULT_OK && data != null) {
            setResult(RESULT_OK, data)
            finish()
        }
    }

    // 在线搜索；网关不可用或没结果时，降级用内置城市表。
    private fun search(keyword: String) {
        titleRecommend.visibility = View.GONE
        gridRecommend.visibility = View.GONE
        tvState.visibility = View.VISIBLE
        tvState.text = "搜索中…"
        Thread {
            val online = AmapSearch.search(this, keyword)
            val results = if (online.isNotEmpty()) online else ChinaCities.search(keyword)
            runOnUiThread {
                if (results.isEmpty()) {
                    tvState.text = "没有找到相关城市，试试其他关键字"
                } else {
                    tvState.visibility = View.GONE
                }
                adapter.update(results)
            }
        }.start()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    // 列表项：城市名 + 所属省份（两行）
    private inner class CityAdapter(private var items: List<CityInfo>) : BaseAdapter() {

        fun update(list: List<CityInfo>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount() = items.size

        override fun getItem(position: Int): CityInfo? = items.getOrNull(position)

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout
            val newRow = if (row == null) {
                LinearLayout(this@SearchActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
                    layoutParams = AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT,
                        AbsListView.LayoutParams.WRAP_CONTENT
                    )
                    addView(TextView(this@SearchActivity).apply {
                        textSize = 17f
                        setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.text_primary))
                    })
                    addView(TextView(this@SearchActivity).apply {
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.text_secondary))
                        setPadding(0, dp(2f), 0, 0)
                    })
                }
            } else {
                row
            }
            val city = items[position]
            val nameTv = newRow.getChildAt(0) as TextView
            val regionTv = newRow.getChildAt(1) as TextView
            nameTv.text = city.name
            val region = city.province
            if (region.isNotEmpty() && region != city.name) {
                regionTv.text = region
                regionTv.visibility = View.VISIBLE
            } else {
                regionTv.visibility = View.GONE
            }
            return newRow
        }
    }
}
