package com.example.weatherapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// 新城市天气预览：完整复用 WeatherFragment（隐藏地址指示条与设置入口），
// 顶部为“取消 / 居中城市名 / 添加”胶囊栏，背景与主页一致
class CityPreviewActivity : AppCompatActivity(), WeatherUiHost {

    private val previewButtonTint = Color.argb(38, 255, 255, 255)

    private lateinit var bgPreview: WeatherBackgroundView
    private lateinit var previewCityTitle: TextView
    private lateinit var topBar: View
    private var previewActionCards: List<BlurCardView> = emptyList()
    private var cityName = ""
    private var lng = 0.0
    private var lat = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            // 初始即采用白色状态栏图标；天气背景加载后再仅在高亮背景切成深色图标。
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        // 预览页滚动时禁止状态栏/导航栏临时隐藏（Android 15+ 默认 transient 会
        // 在滑动中改变 insets 与蒙版高度，导致顶部蒙版偶发失效）
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        setContentView(R.layout.activity_city_preview)
        OplusViewSeamlessNavigator.finishWhenUserInteracts(findViewById(R.id.previewRoot))
        bgPreview = findViewById(R.id.bgPreview)
        // 顶部胶囊栏避让状态栏
        topBar = findViewById(R.id.topBar)
        // 默认只隐藏天气页的顶部模糊蒙版；地址与两个操作按钮始终正常显示。
        topBar.translationY = 0f
        topBar.alpha = 1f
        topBar.visibility = View.VISIBLE
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val side = (20 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left + side, bars.top + dp(2f).toInt(), bars.right + side, dp(8f).toInt())
            v.post {
                if (::bgPreview.isInitialized) {
                    hostBackgroundCloudSafeTop(v.bottom + dp(12f).toInt())
                }
            }
            insets
        }

        cityName = intent.getStringExtra("city") ?: ""
        lng = intent.getDoubleExtra("lng", 0.0)
        lat = intent.getDoubleExtra("lat", 0.0)
        previewCityTitle = findViewById<TextView>(R.id.tvPreviewCity).apply {
            text = cityDisplayName(cityName)
        }

        // 胶囊：圆角胶囊样式 + 真实背景模糊（注册进天气页的毛玻璃循环）
        val btnCancel = findViewById<BlurCardView>(R.id.btnCancel)
        val btnAdd = findViewById<BlurCardView>(R.id.btnAdd)
        val corner = (24 * resources.displayMetrics.density).toInt().toFloat()
        previewActionCards = listOf(btnCancel, btnAdd)
        previewActionCards.forEach { button ->
            button.setCornerRadius(corner)
            // 预览操作按钮固定使用 15% 白色填充，不再跟随普通天气卡片的背景色。
            button.setTint(previewButtonTint, previewButtonTint)
            // 初始夜间沿用原有卡片边缘；天气结果到达后再按昼夜同步。
            button.setStrokeVisible(true)
            button.setStrokeAlpha(WeatherCardStyle.edgeStrokeAlpha(night = true))
            button.setEnhancedEdgeStyle(true)
        }
        findViewById<View>(R.id.btnCancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<View>(R.id.btnAdd).setOnClickListener {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra("city", cityName)
                    .putExtra("lng", lng)
                    .putExtra("lat", lat)
            )
            finish()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentHost,
                    WeatherFragment.newInstance(
                        CityPage(cityName, lng, lat, false, ""),
                        preview = true
                    )
                )
                .commitNow()
        }
        val frag = supportFragmentManager
            .findFragmentById(R.id.fragmentHost) as? WeatherFragment
        // 与顶部蒙版使用同一张“实时内容”截图源，使按钮也能模糊其下方滚动内容。
        frag?.addExternalContentMask(btnCancel)
        frag?.addExternalContentMask(btnAdd)
        // 按钮本身已在内容蒙版截图中隐藏；若再重复注册覆盖层，恢复顺序会把它们留在 INVISIBLE。
        frag?.addExternalOverlayView(previewCityTitle)
    }

    override fun hostWeatherBackground(skycon: String?, night: Boolean) {
        if (skycon.isNullOrBlank()) return
        bgPreview.setWeather(skycon, night)
        previewActionCards.forEach {
            it.setTint(previewButtonTint, previewButtonTint)
            it.setStrokeAlpha(WeatherCardStyle.edgeStrokeAlpha(night))
            it.setEnhancedEdgeStyle(true)
        }
    }

    override fun hostBackgroundFooterVisible(visible: Boolean) {
        hostBackgroundFooterProgress(if (visible) 1f else 0f)
    }

    override fun hostBackgroundFooterProgress(progress: Float) {
        if (::bgPreview.isInitialized) bgPreview.setBottomLandscapeProgress(progress)
    }

    override fun hostBackgroundCloudSafeTop(safeTopPx: Int) {
        if (::bgPreview.isInitialized) bgPreview.setCloudSafeTop(safeTopPx)
    }

    override fun hostStatusBarStyle(lightIcons: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !lightIcons
    }

    override fun hostPreviewCityTitleAlpha(alpha: Float) {
        // 顶部模糊蒙版的显隐不再影响地址文字或操作按钮。
    }

    // 城市名补全“市”字；区/县/已带“市”的保持不变
    private fun cityDisplayName(name: String): String {
        return WeatherAddressFormatter.city(name)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
