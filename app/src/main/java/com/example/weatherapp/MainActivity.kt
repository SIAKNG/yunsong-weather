package com.example.weatherapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import java.util.ArrayDeque
import java.util.Locale

// 一个城市页的数据
data class CityPage(
    val name: String,
    val lng: Double,
    val lat: Double,
    val isCurrentLocation: Boolean,
    val locationDetail: String = ""
)

/** 不随显示名称变化的城市选择键；定位页单独保持一个稳定身份。 */
private fun CityPage.selectionKey(): String = if (isCurrentLocation) {
    "current-location"
} else {
    "saved:${"%.5f".format(Locale.US, lat)}:${"%.5f".format(Locale.US, lng)}"
}

/**
 * 入场刷新只依赖稳定城市键排队，方便在 Fragment 延迟 attach 的冷启动阶段验证顺序。
 * null 表示当前页面尚未可用，调用方必须等待，不能退而先刷新别的城市。
 */
internal object EntryRefreshQueuePolicy {
    fun orderedAttachedKeys(
        currentKey: String?,
        cityKeys: List<String>,
        attachedKeys: Set<String>
    ): List<String>? {
        if (currentKey != null && currentKey !in attachedKeys) return null
        return buildList {
            currentKey?.takeIf { it in attachedKeys }?.let(::add)
            cityKeys.filterTo(this) { it in attachedKeys && it != currentKey }
        }.distinct()
    }
}

// 主页：承载多个城市天气页（左右滑动切换），右上角圆点指示当前页；
// 首次启动获取实时定位并创建定位页，搜索选中新城市时添加新页
class MainActivity : AppCompatActivity(), WeatherUiHost {

    private lateinit var pager: ViewPager2
    private val cityPages = ArrayList<CityPage>()
    private val prefs by lazy { getSharedPreferences("weather_cities", MODE_PRIVATE) }
    // 全局共享的天气背景：所有城市页共用，天气相同则不切换，天气不同才整体淡入淡出
    private lateinit var bgCurrent: WeatherBackgroundView
    private lateinit var bgNext: WeatherBackgroundView
    private var currentBgIsA = true
    private var currentBgSkycon: String? = null
    private var currentBgNight = false
    private var pendingBgSkycon: String? = null
    private var pendingBgNight = false
    private var lastPagerScroll = 0f
    private var backgroundFooterProgress = 0f
    private var backgroundCloudSafeTop = 0
    // 交叉淡入期间：状态栏颜色按"目标背景"计算，避免淡入过程中读到旧背景
    private var bgFadeRunning = false
    private var pendingBgDark = false
    private var bgAnimator: ValueAnimator? = null
    // 背景过渡代际：每次新过渡/原地切换都递增；旧动画被取消时的结束回调
    // 因代际不匹配而直接忽略，避免 A/B 当前页标记被翻转错位。
    private var bgTransitionId = 0
    private var lastStatusBarLightIcons: Boolean? = null
    private var wasStopped = true
    private var lastEntryRefreshAt = 0L
    private val entryRefreshHandler = Handler(Looper.getMainLooper())
    private var entryRefreshGeneration = 0
    private lateinit var permissionCoordinator: WeatherPermissionCoordinator
    private var initialPermissionFlowStarted = false
    private val firstApiConfiguration = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        requestInitialPermissions()
    }
    private val selectedCityPrefKey = "selected_city_key"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug 真机验收可由启动参数临时强制白天多云外观；不写入用户设置。
        WeatherAppearancePreview.configure(intent)
        permissionCoordinator = WeatherPermissionCoordinator(this)
        // 底部沉浸：导航条区域也完全透明，天气内容可以一直延伸到手势指示条位置
        enableEdgeToEdge(
            // 初始背景尚未取得天气数据，先用白色图标；拿到顶部天气背景后立即按对比度更新。
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_main)
        bgCurrent = findViewById(R.id.bgCurrent)
        bgNext = findViewById(R.id.bgNext)
        bgCurrent.setBottomLandscapeVisible(false, animate = false)
        bgNext.setBottomLandscapeVisible(false, animate = false)
        bgNext.alpha = 0f
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 顶部不再整体下移：地址栏蒙版需要延伸到状态栏区域，内容的避让由 WeatherFragment 按 insets 处理；
            // 底部不再按导航栏高度上抬，卡片内容沉浸显示到手势指示条区域
            v.setPadding(systemBars.left, 0, systemBars.right, 0)
            insets
        }

        pager = findViewById(R.id.main)
        bindCityPager()
        // 页面切换：只有内容（refreshLayout）左右滑动；
        // 背景动画和顶部覆盖层（地理位置）原地淡入淡出，避免滑动裁剪出现空白
        pager.setPageTransformer { page, position ->
            val p = abs(position)
            val w = page.width.toFloat().coerceAtLeast(1f)
            // 所有城市 Fragment 都会被保留以复用缓存。旧逻辑把每个根页面固定到
            // 屏幕中央，RecyclerView 在刷新/重新布局的短暂时序里可能让第三页也参与绘制，
            // 于是出现多座城市的文字和卡片叠在一起。只有当前页和紧邻的滑动目标页允许绘制。
            val isTransitionParticipant = p <= 1.01f
            if (!isTransitionParticipant) {
                page.translationX = 0f
                page.alpha = 0f
                page.visibility = View.INVISIBLE
                page.findViewById<View>(R.id.topOverlay)?.visibility = View.INVISIBLE
                page.findViewById<View>(R.id.refreshLayout)?.translationX = 0f
                return@setPageTransformer
            }
            page.visibility = View.VISIBLE
            // ViewPager2 页面本身会随 RecyclerView 滚动，先用 -position*w 把整页钉回屏幕中央，
            // 这样顶部覆盖层原地不动；内容再用 +position*w 横向滑动
            // （背景已全局共享在 ViewPager 下层，这里不再处理背景）
            page.translationX = -position * w
            page.alpha = 1f

            // 顶部城市名始终只有一个可见层。过去多页的长淡入会让保留 Fragment
            // 同时绘制城市/温度文字；中点短暂留白比叠字更稳定、也避免可见旧城市。
            page.findViewById<View>(R.id.topOverlay)?.let { overlay ->
                val showOverlay = p < 0.5f
                overlay.alpha = if (showOverlay) 1f else 0f
                overlay.visibility = if (showOverlay) View.VISIBLE else View.INVISIBLE
            }
            page.findViewById<View>(R.id.refreshLayout)?.translationX = position * w
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                if (positionOffset <= 0.01f) return
                // Early directional hand-off; cached scenes need not wait for half a screen.
                // 若还没拿到天气，report 方法会保持现有背景，绝不露出一帧错误底色。
                val scroll = position + positionOffset
                val movingRight = scroll >= lastPagerScroll
                lastPagerScroll = scroll
                val focus = (position + if (movingRight && positionOffset >= .12f) 1
                    else if (!movingRight && positionOffset > .88f) 1 else 0)
                    .coerceIn(0, cityPages.lastIndex.coerceAtLeast(0))
                reportPageBackground(focus)
            }

            override fun onPageSelected(position: Int) {
                // 取消此前以旧可见城市为优先级的入场刷新队列。新页自身会立即按其
                // 稳定选择键刷新，避免多城市冷启动时的旧回调误占用当前页面的刷新状态。
                entryRefreshGeneration++
                cityPages.getOrNull(position)?.selectionKey()?.let { key ->
                    prefs.edit().putString(selectedCityPrefKey, key).apply()
                }
                pager.post { reportPageBackground(position) }
            }
        })

        // 恢复上次保存的城市；定位城市排第一位，保证每次进入默认显示定位城市
        cityPages.addAll(loadCities())
        if (cityPages.isNotEmpty()) {
            val locIdx = cityPages.indexOfFirst { it.isCurrentLocation }
            if (locIdx > 0) {
                val loc = cityPages.removeAt(locIdx)
                cityPages.add(0, loc)
            }
            bindCityPager()
            val savedSelection = prefs.getString(selectedCityPrefKey, null)
            cityPages.indexOfFirst { it.selectionKey() == savedSelection }
                .takeIf { it >= 0 }
                ?.let { target -> pager.post { pager.setCurrentItem(target, false) } }
        }

        // 冷启动逐项检查定位、通知、后台定位和精确闹钟。定位获准后立即更新当前位置；
        // 拒绝时不覆盖已有城市，首次没有城市才保留北京演示页作为可恢复入口。
        FirstRunGuide.show(this) { configure ->
            if (configure && BuildConfig.USER_CONFIGURABLE) firstApiConfiguration.launch(Intent(this, ApiConfigurationActivity::class.java))
            else requestInitialPermissions()
        }
    }

    private fun requestInitialPermissions() {
        if (initialPermissionFlowStarted || isFinishing || isDestroyed) return
        initialPermissionFlowStarted = true
        permissionCoordinator.requestColdStart { locationGranted ->
            if (locationGranted) getLocation()
            else if (cityPages.isEmpty()) addCity(CityPage("北京", 116.407, 39.904, isCurrentLocation = false))
            WeatherBackgroundScheduler.schedule(this)
        }
    }

    private var returningFromWeatherCard = false
    private var lastForegroundSession = -1L

    fun markWeatherCardOpened() { returningFromWeatherCard = true }

    override fun onResume() {
        super.onResume()
        permissionCoordinator.onResume()
        // 回前台时按当前天气背景校正，不再跟随应用深浅色模式。
        applySystemStatusBarStyle()
        val session = WeatherForegroundSession.current
        val sameForeground = session == lastForegroundSession
        lastForegroundSession = session
        if (returningFromWeatherCard && sameForeground) {
            returningFromWeatherCard = false
            wasStopped = false
            return
        }
        returningFromWeatherCard = false
        if (wasStopped) {
            wasStopped = false
            // 兜底：个别设备把任务带回前台时可能连续触发两次 stop/resume，
            // 短时间内重复进入只刷一次，避免全部城市被重复请求。
            val now = System.currentTimeMillis()
            if (!WeatherRefreshPolicy.shouldStartEntryRefresh(now, lastEntryRefreshAt)) return
            lastEntryRefreshAt = now
            refreshAllCitiesOnEntry()
        }
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
        // 不在后台继续启动排队中的城市刷新；已开始的请求会保留其正常回调保护。
        entryRefreshGeneration++
        entryRefreshHandler.removeCallbacksAndMessages(null)
        // 退出时保存当前城市列表（含定位页退出前的坐标/地址）
        saveCities()
    }

    // 绑定城市页适配器，并让 ViewPager2 保留全部城市页：
    // 默认只缓存左右各 1 页，滑动稍远就会销毁已看过的页面，下次滑回来 Fragment
    // 重建并立即重新联网拉天气、重刷背景。这里把缓存范围扩到整个城市列表，
    // 滑动到任意城市都直接复用已有数据，不再触发刷新。
    private fun bindCityPager() {
        pager.adapter = CityPagerAdapter()
        pager.offscreenPageLimit = maxOf(1, cityPages.size - 1)
    }

    private fun reportPageBackground(index: Int) {
        val page = cityPages.getOrNull(index) ?: return
        supportFragmentManager.fragments.filterIsInstance<WeatherFragment>()
            .firstOrNull { it.isAdded && it.matchesPage(page) }
            ?.reportCurrentAppearanceForPager()
    }

    // 每次进入天气主页：当前城市优先刷新，其他城市串行静默刷新。
    // 旧实现会让所有页面同时请求彩云/和风/Open-Meteo；城市较多时容易触发限流、
    // 堆积网络线程并造成大量过期回调。队列保证一个城市结束（或超时）后再启动下一个。
    private fun refreshAllCitiesOnEntry(attachRetry: Int = 0) {
        pager.post {
            if (isFinishing || isDestroyed) return@post
            Log.i("WeatherApp", "refreshAllCitiesOnEntry: cities=${cityPages.size} current=${pager.currentItem}")
            val current = cityPages.getOrNull(pager.currentItem)
            val fragments = supportFragmentManager.fragments.filterIsInstance<WeatherFragment>()
            val fragmentByKey = cityPages.mapNotNull { city ->
                fragments.firstOrNull { it.isAdded && it.matchesPage(city) }
                    ?.let { city.selectionKey() to it }
            }.toMap()
            val currentKey = current?.selectionKey()
            val orderedKeys = EntryRefreshQueuePolicy.orderedAttachedKeys(
                currentKey = currentKey,
                cityKeys = cityPages.map { it.selectionKey() },
                attachedKeys = fragmentByKey.keys
            )
            // 多城市冷启动时，非当前页经常比当前页更早 attach。此前会先静默刷新它，
            // 令“正在刷新”的状态和当前所见城市脱节。当前页尚未就绪时只等待当前页，
            // 不启动任何后台城市请求。
            if (orderedKeys == null) {
                if (attachRetry < 4) {
                    pager.postDelayed({ refreshAllCitiesOnEntry(attachRetry + 1) }, 220L)
                } else {
                    Log.w("WeatherApp", "current city fragment was not attached; defer entry refresh")
                }
                return@post
            }
            val queue = ArrayDeque<WeatherFragment>()
            // 当前页必须优先，后续城市再按用户列表顺序静默更新。
            orderedKeys.mapNotNullTo(queue) { fragmentByKey[it] }
            // ViewPager2 首次创建时 Fragment 可能尚未加入 FragmentManager；稍后重试，
            // 不把“空队列”误当作本轮天气刷新已完成。
            if (queue.isEmpty() && current != null && attachRetry < 4) {
                pager.postDelayed({ refreshAllCitiesOnEntry(attachRetry + 1) }, 220L)
                return@post
            }
            refreshNextEntryCity(queue, current, ++entryRefreshGeneration)
        }
    }

    private fun refreshNextEntryCity(
        queue: ArrayDeque<WeatherFragment>,
        current: CityPage?,
        generation: Int
    ) {
        if (generation != entryRefreshGeneration || wasStopped || isFinishing || isDestroyed) return
        val fragment = queue.pollFirst() ?: return
        fragment.refreshOnEntry(showIndicator = current != null && fragment.matchesPage(current)) {
            entryRefreshHandler.postDelayed(
                { refreshNextEntryCity(queue, current, generation) },
                WeatherRefreshPolicy.BETWEEN_CITIES_DELAY_MS
            )
        }
    }

    // ===== 城市列表持久化（SharedPreferences JSON） =====
    private fun saveCities() {
        val arr = JSONArray()
        for (c in cityPages) {
            val o = JSONObject()
                .put("name", c.name)
                .put("lng", c.lng)
                .put("lat", c.lat)
                .put("isLoc", c.isCurrentLocation)
                .put("detail", c.locationDetail)
            arr.put(o)
        }
        prefs.edit().putString("cities", arr.toString()).apply()
    }

    private fun loadCities(): ArrayList<CityPage> {
        val out = ArrayList<CityPage>()
        val raw = prefs.getString("cities", null) ?: return out
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    CityPage(
                        name = o.optString("name", "城市"),
                        lng = o.optDouble("lng", Double.NaN),
                        lat = o.optDouble("lat", Double.NaN),
                        isCurrentLocation = o.optBoolean("isLoc", false),
                        locationDetail = o.optString("detail", "")
                    )
                )
            }
            out
        } catch (e: Exception) {
            ArrayList()
        }
    }

    // ===== 供 WeatherFragment 的页面指示器使用 =====
    fun cityList(): List<CityPage> = cityPages

    fun currentPage(): Int = pager.currentItem

    fun jumpTo(index: Int) {
        if (index in cityPages.indices) pager.setCurrentItem(index, true)
    }

    // 各城市天气加载后上报：天气相同则背景完全不动；不同则全局背景整体淡入淡出
    fun setWeatherBackground(skycon: String?, night: Boolean) {
        if (skycon.isNullOrBlank()) return
        // Pager reports every frame. Never restart an already-running identical target.
        if (bgFadeRunning && skycon == pendingBgSkycon && night == pendingBgNight) return
        if (!bgFadeRunning && skycon == currentBgSkycon && night == currentBgNight) return

        val id = ++bgTransitionId
        bgAnimator?.cancel()
        if (bgFadeRunning) {
            val source = if (currentBgIsA) bgCurrent else bgNext
            val target = if (currentBgIsA) bgNext else bgCurrent
            // A reversed swipe can fade back from the exact current blend without a blank frame.
            if ((skycon == currentBgSkycon && night == currentBgNight) || target.alpha > source.alpha) {
                currentBgIsA = !currentBgIsA
                currentBgSkycon = pendingBgSkycon
                currentBgNight = pendingBgNight
            }
        }

        // 仅昼夜变化（如跨过日出/日落时刻）：原地重建当前背景，不做淡入淡出
        if (skycon == currentBgSkycon && !bgFadeRunning) {
            bgFadeRunning = false
            pendingBgSkycon = null
            currentBgNight = night
            val cur = if (currentBgIsA) bgCurrent else bgNext
            val other = if (currentBgIsA) bgNext else bgCurrent
            cur.setWeather(skycon, night)
            cur.setBottomLandscapeProgress(backgroundFooterProgress)
            cur.alpha = 1f
            other.alpha = 0f
            applyStatusBarStyle(WeatherBackgroundPalette.statusBarUsesLightIcons(skycon, night))
            return
        }

        val from = if (currentBgIsA) bgCurrent else bgNext
        val to = if (currentBgIsA) bgNext else bgCurrent
        to.setWeather(skycon, night)
        to.setBottomLandscapeProgress(backgroundFooterProgress)
        val initialTargetAlpha = to.alpha
        val initialSourceAlpha = from.alpha
        // 立即按目标顶部背景的对比度设置状态栏，不等淡入结束。
        pendingBgDark = to.isDarkScene
        applyStatusBarStyle(WeatherBackgroundPalette.statusBarUsesLightIcons(skycon, night))
        bgFadeRunning = true
        pendingBgSkycon = skycon
        pendingBgNight = night

        // 先递增代际再取消旧动画：旧动画的 onAnimationEnd 因 id 不匹配而直接返回，
        // 不再翻转 currentBgIsA / currentBgSkycon / bgFadeRunning。
        bgAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                to.alpha = initialTargetAlpha + (1f - initialTargetAlpha) * f
                from.alpha = initialSourceAlpha * (1f - f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (id != bgTransitionId) return
                    from.alpha = 0f
                    to.alpha = 1f
                    currentBgIsA = !currentBgIsA
                    currentBgSkycon = skycon
                    currentBgNight = night
                    bgFadeRunning = false
                    pendingBgSkycon = null
                }
            })
            start()
        }
    }

    // 当前共享背景是否偏暗（仅供昼夜兜底判断使用）。
    fun isBackgroundDark(): Boolean = if (bgFadeRunning) {
        pendingBgDark
    } else {
        (if (currentBgIsA) bgCurrent else bgNext).isDarkScene
    }

    // 天气页左上角定位栏点击时调用：打开城市管理页
    fun openCityManage() {
        val names = ArrayList<String>()
        val lngs = DoubleArray(cityPages.size)
        val lats = DoubleArray(cityPages.size)
        val isLoc = BooleanArray(cityPages.size)
        val details = ArrayList<String>()
        val temps = ArrayList<String>()
        val descs = ArrayList<String>()
        val skycons = ArrayList<String>()
        val nights = BooleanArray(cityPages.size)
        for (i in cityPages.indices) {
            names.add(cityPages[i].name)
            lngs[i] = cityPages[i].lng
            lats[i] = cityPages[i].lat
            isLoc[i] = cityPages[i].isCurrentLocation
            details.add(cityPages[i].locationDetail)
            val snap = citySnaps[cityPages[i].name] ?: readSnap(cityPages[i].name)
            temps.add(snap?.temp.orEmpty())
            descs.add(snap?.desc.orEmpty())
            skycons.add(snap?.skycon.orEmpty())
            nights[i] = snap?.night == true
        }
        cityManageLauncher.launch(
            Intent(this, CityManageActivity::class.java).apply {
                putExtra("current", pager.currentItem)
                putStringArrayListExtra("cities", names)
                putExtra("lngs", lngs)
                putExtra("lats", lats)
                putExtra("isLoc", isLoc)
                putStringArrayListExtra("details", details)
                putStringArrayListExtra("temps", temps)
                putStringArrayListExtra("descs", descs)
                putStringArrayListExtra("skycons", skycons)
                putExtra("nights", nights)
            }
        )
    }

    // 城市管理页返回：用最终城市列表重建页面，并跳到选中的城市
    private val cityManageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val names = data.getStringArrayListExtra("cities") ?: return@registerForActivityResult
            val lngs = data.getDoubleArrayExtra("lngs") ?: return@registerForActivityResult
            val lats = data.getDoubleArrayExtra("lats") ?: return@registerForActivityResult
            val isLoc = data.getBooleanArrayExtra("isLoc") ?: return@registerForActivityResult
            val details = data.getStringArrayListExtra("details") ?: arrayListOf()
            if (names.isEmpty()) {
                addCity(CityPage("北京", 116.407, 39.904, isCurrentLocation = false))
                return@registerForActivityResult
            }
            val old = ArrayList(cityPages)
            val newList = ArrayList<CityPage>(names.size)
            for (i in names.indices) {
                newList.add(
                    CityPage(
                        name = names[i],
                        lng = lngs.getOrElse(i) { Double.NaN },
                        lat = lats.getOrElse(i) { Double.NaN },
                        isCurrentLocation = isLoc.getOrElse(i) { false },
                        locationDetail = details.getOrNull(i).orEmpty()
                    )
                )
            }
            val changed = newList.size != old.size || newList.indices.any { i ->
                val a = newList[i]
                val b = old[i]
                a.name != b.name || a.lng != b.lng || a.lat != b.lat ||
                    a.isCurrentLocation != b.isCurrentLocation ||
                    a.locationDetail != b.locationDetail
            }
            cityPages.clear()
            cityPages.addAll(newList)
            val sel = data.getIntExtra("selected", 0)
            // 列表真的变化了才重建适配器；原样返回时保留现有城市页与日出日落/背景状态，
            // 避免每次从城市管理返回都销毁重建、把夜间背景误切回白天
            if (changed) bindCityPager()
            // 等 ViewPager2 完成一次布局后再跳页：城市管理返回时若同步 setCurrentItem(false)，
            // 页面内容尚未按 PageTransformer 复位，会出现只有背景、没有文字/信息的白屏
            val target = sel.coerceIn(0, cityPages.lastIndex)
            pager.post {
                if (target in cityPages.indices) pager.setCurrentItem(target, true)
            }
            saveCities()
        }
    }

    private fun addCity(page: CityPage) {
        cityPages.add(page)
        bindCityPager()
        pager.setCurrentItem(cityPages.size - 1, true)
        saveCities()
        // 新增城市不等待下一次前后台切换，也进入与启动相同的串行刷新队列。
        refreshAllCitiesOnEntry()
    }

    // 状态栏文字颜色：lightIcons=true 表示白色文字（深色模式），false 表示黑色文字（浅色模式）
    fun applyStatusBarStyle(lightIcons: Boolean) {
        if (lastStatusBarLightIcons == lightIcons) return
        lastStatusBarLightIcons = lightIcons
        // 用 InsetsController 直接切换状态栏前景色：
        // 深色背景 → 白色图标；浅色背景 → 深色图标
        // 注意：isAppearanceLightStatusBars=true 表示状态栏外观为浅色（系统绘制深色图标），
        // 所以想要浅色（白色）图标时必须传 false，之前写反导致刷新后图标变黑
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !lightIcons
    }

    // 回到前台时按当前顶部天气背景重设状态栏文字和图标颜色。
    fun applySystemStatusBarStyle() {
        val skycon = currentBgSkycon
        applyStatusBarStyle(
            if (skycon == null) true
            else WeatherBackgroundPalette.statusBarUsesLightIcons(skycon, currentBgNight)
        )
    }

    // WeatherUiHost：把天气页的上报转发到主页的共享背景与状态栏
    override fun hostWeatherBackground(skycon: String?, night: Boolean) =
        setWeatherBackground(skycon, night)

    override fun hostBackgroundFooterVisible(visible: Boolean) {
        hostBackgroundFooterProgress(if (visible) 1f else 0f)
    }

    override fun hostBackgroundFooterProgress(progress: Float) {
        val target = progress.coerceIn(0f, 1f)
        if (kotlin.math.abs(backgroundFooterProgress - target) < 0.002f) return
        backgroundFooterProgress = target
        bgCurrent.setBottomLandscapeProgress(target)
        bgNext.setBottomLandscapeProgress(target)
    }

    override fun hostBackgroundCloudSafeTop(safeTopPx: Int) {
        val safe = safeTopPx.coerceAtLeast(0)
        if (backgroundCloudSafeTop == safe) return
        backgroundCloudSafeTop = safe
        bgCurrent.setCloudSafeTop(safe)
        bgNext.setCloudSafeTop(safe)
    }

    // 各城市最近一次天气快照：内存 + 持久化，供城市管理页进入前直接显示
    private data class CitySnap(
        val temp: String?,
        val desc: String?,
        val skycon: String?,
        val night: Boolean
    )

    private val citySnaps = HashMap<String, CitySnap>()

    override fun hostWeatherSnapshot(
        name: String,
        temp: String?,
        desc: String?,
        skycon: String?,
        night: Boolean
    ) {
        if (name.isBlank()) return
        val snap = CitySnap(temp, desc, skycon, night)
        citySnaps[name] = snap
        prefs.edit()
            .putString("snap_$name", "${temp.orEmpty()}|${desc.orEmpty()}|${skycon.orEmpty()}|${if (night) 1 else 0}")
            .apply()
    }

    private fun readSnap(name: String): CitySnap? {
        val v = prefs.getString("snap_$name", null) ?: return null
        val parts = v.split("|")
        return CitySnap(
            temp = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
            desc = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
            skycon = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
            night = parts.getOrNull(3) == "1"
        )
    }

    override fun hostStatusBarStyle(lightIcons: Boolean) =
        applyStatusBarStyle(lightIcons)

    // ===== 1. 获取定位 =====
    private fun getLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        val last = lastKnownLocation(lm)
        if (last != null) {
            onLocationFound(last)
            return
        }

        var gotIt = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!gotIt) {
                    gotIt = true
                    lm.removeUpdates(this)
                    onLocationFound(location)
                }
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!gotIt) {
                gotIt = true
                lm.removeUpdates(listener)
                if (cityPages.isEmpty()) {
                    toast("定位超时，请确认已打开定位，尽量到户外或窗边再试")
                    addCity(CityPage("北京", 116.407, 39.904, isCurrentLocation = false))
                }
            }
        }, 15000)

        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(
                    LocationManager.NETWORK_PROVIDER,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(
                    LocationManager.GPS_PROVIDER,
                    listener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            toast("定位出错：${e.message}")
            if (cityPages.isEmpty()) {
                addCity(CityPage("北京", 116.407, 39.904, isCurrentLocation = false))
            }
        }
    }

    private fun lastKnownLocation(lm: LocationManager): Location? {
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time in 0..WeatherRefreshPolicy.LOCATION_CACHE_MS }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun onLocationFound(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        // 反查城市名 + 详细位置（区县/街道/小区），完成后创建定位页
        Thread {
            val (name, detail) = geocode(lat, lng)
            runOnUiThread {
                val hadNoCities = cityPages.isEmpty()
                val selectedWasLocation = cityPages.getOrNull(pager.currentItem)?.isCurrentLocation == true
                val selectedBefore = cityPages.getOrNull(pager.currentItem)?.selectionKey()
                    ?: prefs.getString(selectedCityPrefKey, null)
                val locPage = CityPage(
                    name = name ?: "我的位置",
                    lng = lng,
                    lat = lat,
                    isCurrentLocation = true,
                    locationDetail = detail.orEmpty()
                )
                val idx = cityPages.indexOfFirst { it.isCurrentLocation }
                if (idx >= 0) {
                    // 只更新定位页内容，不再把用户当前阅读的城市强行拉回第一页。
                    cityPages.removeAt(idx)
                    cityPages.add(0, locPage)
                } else {
                    cityPages.add(0, locPage)
                }
                bindCityPager()
                val target = when {
                    hadNoCities || selectedWasLocation -> 0
                    else -> cityPages.indexOfFirst { it.selectionKey() == selectedBefore }
                        .takeIf { it >= 0 } ?: pager.currentItem.coerceIn(0, cityPages.lastIndex)
                }
                pager.post { pager.setCurrentItem(target, false) }
                saveCities()
                // 首次定位成功后刚创建的定位页尚未请求天气，交给串行队列立即刷新。
                refreshAllCitiesOnEntry()
            }
        }.start()
    }

    // 下拉刷新时请求一次最新定位：成功回调最新坐标+详细地址，超时/失败回调 false
    fun requestLocationRefresh(onDone: (Boolean, Double, Double, String) -> Unit) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var gotIt = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (gotIt) return
                gotIt = true
                try {
                    lm.removeUpdates(this)
                } catch (e: Exception) {
                    // 忽略移除失败
                }
                onLocationRefreshed(location, onDone)
            }
        }

        fun timeout() {
            if (gotIt) return
            gotIt = true
            try {
                lm.removeUpdates(listener)
            } catch (e: Exception) {
                // 忽略移除失败
            }
            runOnUiThread { onDone(false, 0.0, 0.0, "") }
        }

        Handler(Looper.getMainLooper()).postDelayed({ timeout() }, 8000)
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(
                    LocationManager.NETWORK_PROVIDER,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(
                    LocationManager.GPS_PROVIDER,
                    listener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            timeout()
        }
    }

    // 拿到新定位后：反查地址 → 原地更新定位城市页并保存 → 回调给 WeatherFragment
    private fun onLocationRefreshed(
        location: Location,
        onDone: (Boolean, Double, Double, String) -> Unit
    ) {
        val lat = location.latitude
        val lng = location.longitude
        Thread {
            val (name, detail) = geocode(lat, lng)
            runOnUiThread {
                val idx = cityPages.indexOfFirst { it.isCurrentLocation }
                if (idx >= 0) {
                    val old = cityPages[idx]
                    cityPages[idx] = old.copy(
                        name = name ?: old.name,
                        lng = lng,
                        lat = lat,
                        locationDetail = detail.orEmpty()
                    )
                    saveCities()
                }
                onDone(true, lng, lat, detail.orEmpty())
            }
        }.start()
    }

    // 用 Geocoder 反查：城市名 + 详细地址（subLocality 区县 / thoroughfare 街道 / featureName 小区）
    private fun geocode(lat: Double, lng: Double): Pair<String?, String?> {
        return try {
            val addr = Geocoder(this, Locale.CHINA).getFromLocation(lat, lng, 1)?.firstOrNull()
            if (addr == null) return null to null
            val name = addr.locality
            val detail = WeatherAddressFormatter.fromParts(name, addr.subLocality ?: addr.subAdminArea,
                addr.thoroughfare, addr.featureName)
            name to detail.ifEmpty { null }
        } catch (e: Exception) {
            null to null
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private inner class CityPagerAdapter : FragmentStateAdapter(this) {
        override fun getItemCount() = cityPages.size
        override fun createFragment(position: Int): Fragment =
            WeatherFragment.newInstance(cityPages[position])

        // 稳定 ID：不能只用城市名称，同名区县/不同坐标城市会被错误复用成同一 Fragment。
        override fun getItemId(position: Int): Long = cityPages[position].stableId()

        override fun containsItem(itemId: Long): Boolean =
            cityPages.any { it.stableId() == itemId }

        private fun CityPage.stableId(): Long {
            var id = name.hashCode().toLong()
            id = 31L * id + lng.toBits()
            id = 31L * id + lat.toBits()
            return 31L * id + if (isCurrentLocation) 1L else 0L
        }
    }
}
