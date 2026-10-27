package com.example.weatherapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// 单个城市的天气页：请求彩云天气数据并展示，左上角定位栏点击打开搜索页
class WeatherFragment : Fragment() {

    private lateinit var page: CityPage
    private var callbackPager: ViewPager2? = null
    private var renderedDisplaySettings: String? = null
    private fun displaySettingsKey(): String {
        val c=requireContext()
        return listOf(WeatherSettings.tempUnit(c),WeatherSettings.windUnit(c),WeatherSettings.pressureUnit(c),
            WeatherSettings.visibilityUnit(c),SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(System.currentTimeMillis())).joinToString("|")
    }
    private lateinit var tvCity: TextView
    private lateinit var tvLocationDetail: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvHighLow: TextView
    private lateinit var tvSunrise: TextView
    private lateinit var tvSunset: TextView
    private lateinit var sunTrack: SunTrackView
    private lateinit var ivSunLeftIcon: ImageView
    private lateinit var ivSunRightIcon: ImageView
    private lateinit var tvSunDuration: TextView
    private lateinit var tvAqiLevel: TextView
    private lateinit var tvAqiValue: TextView
    private lateinit var tvAqiDesc: TextView
    private lateinit var aqiBar: AqiBarView
    private lateinit var tvStatus: TextView
    private lateinit var refreshLayout: SwipeRefreshLayout
    private lateinit var tvRefreshTip: TextView
    private lateinit var refreshTipRow: View
    private lateinit var refreshCloud: RefreshCloudView
    private lateinit var locationBar: View
    private lateinit var topMask: BlurCardView
    private lateinit var topOverlayContent: View
    private lateinit var hourlyChart: HourlyChartView
    private lateinit var dailyListContainer: LinearLayout
    private lateinit var dailyChartScroll: HorizontalScrollView
    private lateinit var dailyChart: DailyTrendView
    private lateinit var dailyCard: BlurCardView
    private lateinit var tvDailyMore: TextView
    private lateinit var tvModeChart: TextView
    private lateinit var tvModeList: TextView
    private lateinit var modeHighlight: View
    private lateinit var dailyMoreContainer: LinearLayout
    private val dailyRows = ArrayList<View>()
    private var dailyDisplayedDates: List<String> = emptyList()
    private var expandedDaily = false
    private var dailyModeChart = false
    private var dailySource: List<DailyForecast>? = null
    private var primaryDaily: List<DailyForecast> = emptyList()
    private var openMeteoDaily: List<DailyForecast> = emptyList()
    private var moreAnimator: ValueAnimator? = null
    /** 多日卡高度插值期间使用固定根坐标背景，避免每帧重裁剪而拉伸毛玻璃。 */
    private data class DailyBlurFreeze(
        val bitmap: Bitmap,
        val width: Int,
        val maxHeight: Int
    )
    private var dailyBlurFreeze: DailyBlurFreeze? = null
    private var lastSpacerSetAt = 0L
    private var modeTabWidth = 0
    private var statusBarHeight = 0
    private var refreshTasks = 0
    private var refreshOk = true
    private var refreshing = false
    private var lastPageRefreshAt = 0L
    private var pageRefreshInFlight = false
    private var refreshFinishedCallback: (() -> Unit)? = null
    private var fetchSeq = 0
    private var calibrationFetchSeq = 0
    private var lastServerTimeMillis = 0L
    private var displayingOldSnapshot = false
    private val refreshTipHideRunnable = Runnable {
        refreshTipRow.animate().alpha(0f).setDuration(300).withEndAction {
            refreshTipRow.visibility = View.GONE
            refreshCloud.setRefreshing(false)
            refreshCloud.setSuccess(false)
        }.start()
    }
    private lateinit var indicatorScroll: HorizontalScrollView
    private lateinit var indicatorContainer: LinearLayout
    private var hourlyRefMillis = 0L
    private var sunriseMillis = 0L
    private var sunsetMillis = 0L
    private var nextSunriseMillis = 0L
    private var yesterdaySunsetMillis = 0L
    // 彩云主天气继续使用 v2.7；日出日落独立以 v2.6 /daily 的 astro 为准。
    // 列表只保存当前请求的结果，切换数据源或定位刷新时会立刻清空，避免串城。
    private var caiyunV26Astro: List<Astro> = emptyList()
    // 从二级页返回时保留阅读位置，避免异步布局把页面拉回顶部。
    private var lastWeatherScrollY = 0
    private var hourlyLabels: List<String> = emptyList()
    private var hourlyTemps: List<Double?> = emptyList()
    private var hourlySkycons: List<String?> = emptyList()
    private var hourlyProbs: List<Double> = emptyList()
    // 首页历史列只保留彩云历史响应或彩云已保存快照，绝不再从 Open-Meteo 伪造。
    private var hourlyPast: List<CaiyunHourlyWeatherSnapshot> = emptyList()
    private var hourlyMetricPoints: List<HourlyMetricPoint> = emptyList()
    private var forecastSkyByHour: Map<Long, String?> = emptyMap()

    private fun openForecastDetail(card: View, selectedDate: String? = null) {
        OplusViewSeamlessNavigator.startFromCard(this, card,
            ForecastDetailActivity.createIntent(requireContext(), detailHeaderLocation(), forecastRowsForDetail(),
                selectedDate, dailySource.orEmpty().filter {it.date.take(10) in dailyDisplayedDates}, lastTemp))
    }

    /** 首页 48 小时卡片专属入口；多日卡片仍使用既有 ForecastDetailActivity。 */
    private fun openHourlyForecastDetail(card: View) {
        OplusViewSeamlessNavigator.startFromCard(this, card,
            HourlyForecastDetailActivity.createIntent(
                requireContext(), detailHeaderLocation(), forecastRowsForDetail(), lastTemp
            ))
    }

    /** 与六项指标详情页共用同一地址：定位城市显示实时定位地址，收藏城市显示城市名。 */
    private fun detailHeaderLocation(): String =
        WeatherAddressFormatter.label(page)

    /**
     * 首页与两个二级页只从同一条时间线取数：一列已缓存的真实历史 + 当前整点及后续预报。
     * 不能按界面各自重新索引，否则卡片、48 小时详情和多日详情会落到不同小时。
     */
    private fun forecastRowsForDetail(): List<ForecastHour> {
        val now = System.currentTimeMillis()
        val currentHour = now / ForecastDetailData.HOUR * ForecastDetailData.HOUR
        val cachedMetrics = MetricHistoryTimelineCache.load(requireContext(), page, "caiyun_v26")
        val historicalMetrics = (cachedMetrics?.points.orEmpty() + yesterdayMetricPoints)
            .associateBy { it.timeMillis / ForecastDetailData.HOUR }
        val historicalWeather = CaiyunWeatherHistorySnapshotCache.load(requireContext(), page)
            ?.hourly.orEmpty()
            .associateBy { it.timeMillis / ForecastDetailData.HOUR }

        // 48 小时卡片及其二级页只保留当前整点前一小时；缺字段只画断线，不以推算值代替。
        val historySlots = listOf(currentHour - ForecastDetailData.HOUR).map { time ->
            val hour = time / ForecastDetailData.HOUR
            val metric = historicalMetrics[hour]
            val weather = historicalWeather[hour]
            (metric ?: HourlyMetricPoint(timeMillis = time)).copy(
                timeMillis = time,
                temperature = metric?.temperature ?: weather?.temperature,
                uv = metric?.uv ?: weather?.uv,
                precipitationProbability = metric?.precipitationProbability
                    ?: weather?.precipitationProbability
            )
        }
        val historySky = historySlots.associate { point ->
            point.timeMillis / ForecastDetailData.HOUR to
                historicalWeather[point.timeMillis / ForecastDetailData.HOUR]?.skycon
        }
        val historicalAir = (cachedMetrics?.airQualityPoints.orEmpty() + historicalAirQualityPoints)
            .distinctBy { it.timeMillis / ForecastDetailData.HOUR }
        return ForecastDetailData.build(
            historySlots + hourlyMetricPoints,
            historySky + forecastSkyByHour,
            historicalAir + airQualityTimeline.pointsWithRealtime(),
            now,
            pastHours = 1
        )
    }
    private var yesterdayMetricPoints: List<HourlyMetricPoint> = emptyList()
    private var historicalSupplementedKinds: Set<MetricKind> = emptySet()
    private var metricFetchSeq = 0
    private var metricHistoryFetchSeq = 0
    private var hasSelectedSourceMetricHistory = false
    private var metricHistoryLoading = false
    private var metricSupplementLoading = false
    private val metricReadyCallbacks = ArrayList<() -> Unit>()
    private var airQualityPoints: List<AirQualityPoint> = emptyList()
    private var historicalAirQualityPoints: List<AirQualityPoint> = emptyList()
    private var currentAirQuality: AirQualityPoint? = null
    private var airQualityTimeline = AirQualityTimeline.empty(AirQualitySource.UNKNOWN)
    private var lastSkycon: String? = null
    private var lastTemp: Double? = null
    private var lastHigh: Double? = null
    private var lastLow: Double? = null
    private var lastWindDir: String? = null
    private var lastWindDirDeg: Double? = null
    private var lastWindSpeedKmh: Double? = null
    private var lastWindScale: String? = null
    private var lastFeelsLike: Double? = null
    private var lastUvIndex: String? = null
    private var lastUvDesc: String? = null
    private var lastHumidity: Double? = null
    private var lastPressureHpa: Double? = null
    private var lastVisibilityKm: Double? = null
    private var lastPrecipMm: Double? = null
    private var lifeAdvice: List<LifeAdviceItem> = emptyList()
    /** 彩云自己的五项日生活指数；和风只补齐此列表未覆盖的类别。 */
    private var caiyunLifeAdvice: List<LifeAdviceItem> = emptyList()
    private var appliedSource = ""
    private var appliedApiRevision = -1L
    private lateinit var btnSettings: View
    private lateinit var tvSunLeftLabel: TextView
    private lateinit var tvSunRightLabel: TextView
    private lateinit var tvSunCountdownLabel: TextView
    private lateinit var tvSunCountdownTime: TextView
    private lateinit var windCompass: WindCompassView
    private lateinit var windLevelCard: BlurCardView
    private lateinit var tvWindLevel: TextView
    private lateinit var tvWindDesc: TextView
    private lateinit var tvHumidityValue: TextView
    private lateinit var tvHumidityDesc: TextView
    private lateinit var tvHumidityDew: TextView
    private lateinit var pressureGauge: PressureGaugeView
    private lateinit var tvVisibilityValue: TextView
    private lateinit var tvVisibilityDesc: TextView
    private lateinit var tvFeelsValue: TextView
    private lateinit var feelsDelta: FeelsLikeDeltaView
    private lateinit var tvFeelsDesc: TextView
    private lateinit var tvUvValue: TextView
    private lateinit var tvUvDesc: TextView
    private lateinit var uvBar: UvBarView
    private lateinit var tvAlertEntry: TextView
    private lateinit var alertEntryCard: BlurCardView
    private lateinit var ivAlertIcon: ImageView
    private lateinit var minutelyCard: BlurCardView
    private lateinit var minutelyChart: MinutelyChartView
    private lateinit var tvMinutelySummary: TextView
    private lateinit var tvMinutelyPeak: TextView
    private lateinit var weatherLoadingOverlay: WeatherLoadingOverlayView
    private lateinit var ivSourceLogo: ImageView
    private lateinit var ivSourceSupplementLogo: ImageView
    private lateinit var tvStatusTime: TextView
    private lateinit var bottomStatusContainer: View
    private lateinit var lifeAdviceGrid: LifeAdviceGridView
    private var lastPressureTrend: Double? = null

    // 灾害预警列表（无预警时入口隐藏）
    private val alertTitles = ArrayList<String>()
    private val alertLevels = ArrayList<String>()
    private val alertContents = ArrayList<String>()
    private val alertTimes = ArrayList<String>()
    private val alertCodes = ArrayList<String>()
    private val alertLocations = ArrayList<String>()
    private val alertSources = ArrayList<String>()
    private val alertColors = ArrayList<Int>()
    // 多预警入口轮播
    private val alertHandler = Handler(Looper.getMainLooper())
    private var alertIndex = 0
    // 每次替换预警列表都推进版本号。轮播动画结束时若列表已被刷新/清空，
    // 不再读取旧索引，避免网络回包与动画交错导致 IndexOutOfBoundsException。
    private var alertContentVersion = 0L
    private var alertRequestVersion = 0L
    private val alertScrollRunnable = object : Runnable {
        override fun run() {
            if (!isAdded) return
            if (alertTitles.size > 1) {
                val next = (alertIndex + 1) % alertTitles.size
                fadeAlertTo(next)
                alertIndex = next
                alertHandler.postDelayed(this, 3000L)
            }
        }
    }

    // 每 30 秒刷新一次“距离日出/日落”倒计时
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateSunCard()
            mainHandler.postDelayed(this, 30000L)
        }
    }

    // 真机是 Android 16：用 GPU RenderEffect 做毛玻璃，截图完直接绘制，不再等 CPU 模糊线程，
    // 滚动时背景虚化可以逐帧跟随；API 31 以下才退回原来的后台 CPU 模糊方案
    private val useGpuBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // ===== 毛玻璃卡片（BlurLayout 方式：周期截根视图画面做模糊背景） =====
    private val glassCards = mutableListOf<BlurCardView>()
    private val glassHandler = Handler(Looper.getMainLooper())
    private var glassRunnable: Runnable? = null
    // 滚动/切页时只保留约 30fps 的快速截图，静止后进一步降频；视觉跟手与主线程负载之间保持平衡。
    private var fastGlass = false
    private var glassIdleReset: Runnable? = null
    private var lastMaskGlassFrameNanos = 0L
    private var previewMode = false
    // 帧同步刷新：滚动时由 Choreographer 在每帧绘制前截图，
    // 模糊与画面严格同帧，消除 Handler 定时带来的延迟和抖动
    private val glassFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!fastGlass || glassRunnable == null) return
            // 普通天气卡只取共享天气背景，开销很低，滚动时必须逐帧同步，
            // 否则云层相对卡片会留下拖影。顶部“内容模糊”仍可 30fps 更新以控制开销。
            val refreshContentMasks = frameTimeNanos - lastMaskGlassFrameNanos >= 33_000_000L
            if (refreshContentMasks) lastMaskGlassFrameNanos = frameTimeNanos
            refreshGlass(refreshContentMasks)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // 模糊在后台线程做（CPU 盒式模糊要 40-60ms，放主线程会卡帧）；
    // 主线程只负责截根视图（几毫秒）和把结果换到前台显示
    private val mainHandler = Handler(Looper.getMainLooper())
    private val blurLock = Object()
    private var blurThread: Thread? = null
    private var pendingFrame: GlassFrame? = null
    // 双缓冲画板：主线程截到 A 时后台模糊 A，主线程下一帧用 B，轮流来
    private var shotA: Bitmap? = null
    private var shotB: Bitmap? = null
    private var busyA = false
    private var busyB = false
    // GPU 模式只用一块画板：截图和绘制都在主线程，互不竞争
    private var shotBmp: Bitmap? = null
    private var shotW = 0
    private var shotH = 0
    // 内容蒙版（如顶部地址栏/预览页顶部栏）单独用一块画板：截图里保留所有卡片内容，
    // 让蒙版的实时模糊作用到卡片与实时文字，处于统一平面
    private var maskShotBmp: Bitmap? = null
    private var maskShotW = 0
    private var maskShotH = 0
    // 宿主注册的"覆盖层内容"（如预览页顶部胶囊按钮）：截图时隐藏，避免被蒙版模糊成双重影像
    private val overlayViews = mutableListOf<View>()
    // 预览页外部按钮应保持其自身 15% 白色填充，不能被首页普通天气卡片样式覆盖。
    private val externalContentMasks = mutableSetOf<BlurCardView>()
    // 三张需要非线性入场的指示条各自记录可见状态。卡片从滚动区域外重新进入时才
    // 重播，体感等普通指示条始终直接落在真实值，不再参与这套动画。
    private var indicatorEntryGeneration = 0L
    private var indicatorEntryAnimationPosted = false
    private var sunEntryGeneration = 0L
    private var aqiEntryGeneration = 0L
    private var uvEntryGeneration = 0L
    private var sunIndicatorVisible = false
    private var aqiIndicatorVisible = false
    private var uvIndicatorVisible = false
    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(state: Int) {
            if (!isAdded) return
            setFastGlass(state != ViewPager2.SCROLL_STATE_IDLE)
        }

        override fun onPageSelected(position: Int) {
            if (!isAdded) return
            setFastGlass(true)
            val selected = (activity as? MainActivity)?.cityList()?.getOrNull(position)
            if (selected != null && matchesPage(selected)) {
                refreshWhenPageVisible()
                view?.findViewById<ScrollView>(R.id.weatherRoot)?.let {
                    updateBottomStatusVisibility(it, it.scrollY, animate = false)
                }
                requestIndicatorEntryAnimation()
            }
        }
    }

    /**
     * 页面重新成为当前城市页时，重置三张卡的“已出现”状态。真正的播放时机由卡片
     * 可见比例决定，因而返回页面和向下滚动到卡片时均会自然重播。
     */
    private fun requestIndicatorEntryAnimation() {
        if (!isAdded || !isCurrentPage()) return
        sunIndicatorVisible = false
        aqiIndicatorVisible = false
        uvIndicatorVisible = false
        scheduleVisibleIndicatorCheck()
    }

    private fun scheduleVisibleIndicatorCheck() {
        if (indicatorEntryAnimationPosted) return
        val root = view ?: return
        indicatorEntryAnimationPosted = true
        root.post {
            indicatorEntryAnimationPosted = false
            updateVisibleIndicatorAnimations()
        }
    }

    /** 仅在卡片至少三分之一进入真实屏幕区域时触发，防止边缘一闪就开始播放。 */
    private fun isIndicatorCardVisible(cardId: Int): Boolean {
        val card = view?.findViewById<View>(cardId) ?: return false
        if (!card.isShown || card.height <= 0 || card.width <= 0) return false
        val visible = Rect()
        if (!card.getGlobalVisibleRect(visible)) return false
        return visible.height() >= card.height * .35f && visible.width() >= card.width * .70f
    }

    /** 根据滚动后的实际可见状态逐卡播放；每张卡都有独立 generation，异步数据随后到达也可补播。 */
    private fun updateVisibleIndicatorAnimations() {
        if (!isAdded || !isCurrentPage() || !::sunTrack.isInitialized ||
            !::aqiBar.isInitialized || !::uvBar.isInitialized
        ) return
        val sunVisible = isIndicatorCardVisible(R.id.sunCard)
        val aqiVisible = isIndicatorCardVisible(R.id.aqiCard)
        val uvVisible = isIndicatorCardVisible(R.id.cardUv)
        if (sunVisible && !sunIndicatorVisible) {
            sunEntryGeneration = ++indicatorEntryGeneration
            sunTrack.playEntryAnimation(sunEntryGeneration)
        }
        if (aqiVisible && !aqiIndicatorVisible) {
            aqiEntryGeneration = ++indicatorEntryGeneration
            aqiBar.playEntryAnimation(aqiEntryGeneration)
        }
        if (uvVisible && !uvIndicatorVisible) {
            uvEntryGeneration = ++indicatorEntryGeneration
            uvBar.playEntryAnimation(uvEntryGeneration)
        }
        sunIndicatorVisible = sunVisible
        aqiIndicatorVisible = aqiVisible
        uvIndicatorVisible = uvVisible
    }

    private fun playPendingIndicatorEntryAnimation() {
        if (!isAdded || !isCurrentPage() || !::sunTrack.isInitialized ||
            !::aqiBar.isInitialized || !::uvBar.isInitialized
        ) return
        // 之前可见但尚无数据的卡片保留其 generation；数据到达后会在这里真正开播。
        if (sunIndicatorVisible && sunEntryGeneration != 0L) sunTrack.playEntryAnimation(sunEntryGeneration)
        if (aqiIndicatorVisible && aqiEntryGeneration != 0L) aqiBar.playEntryAnimation(aqiEntryGeneration)
        if (uvIndicatorVisible && uvEntryGeneration != 0L) uvBar.playEntryAnimation(uvEntryGeneration)
        scheduleVisibleIndicatorCheck()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_weather, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderedDisplaySettings=null
        val args = arguments
        page = CityPage(
            name = args?.getString("name") ?: "",
            lng = args?.getDouble("lng") ?: 0.0,
            lat = args?.getDouble("lat") ?: 0.0,
            isCurrentLocation = args?.getBoolean("isLoc") ?: false,
            locationDetail = args?.getString("detail") ?: ""
        )

        tvCity = view.findViewById(R.id.tvCity)
        tvLocationDetail = view.findViewById(R.id.tvLocationDetail)
        tvTemp = view.findViewById(R.id.tvTemp)
        tvDesc = view.findViewById(R.id.tvDesc)
        tvHighLow = view.findViewById(R.id.tvHighLow)
        tvSunrise = view.findViewById(R.id.tvSunrise)
        tvSunset = view.findViewById(R.id.tvSunset)
        view.findViewById<BlurCardView>(R.id.sunCard).setOnClickListener { card ->
            val state = detailNavigationState()
            val navigationId = DetailNavigationStore.register(state)
            OplusViewSeamlessNavigator.startFromCard(
                this,
                card,
                DetailNavigator.cardEntryIntent(requireContext(), DetailPage.SUN, navigationId, state)
            )
        }
        sunTrack = view.findViewById(R.id.sunTrack)
        ivSunLeftIcon = view.findViewById(R.id.ivSunLeftIcon)
        ivSunRightIcon = view.findViewById(R.id.ivSunRightIcon)
        tvSunDuration = view.findViewById(R.id.tvSunDuration)
        tvAqiLevel = view.findViewById(R.id.tvAqiLevel)
        tvAqiValue = view.findViewById(R.id.tvAqiValue)
        tvAqiDesc = view.findViewById(R.id.tvAqiDesc)
        aqiBar = view.findViewById(R.id.aqiBar)
        lifeAdviceGrid = view.findViewById(R.id.lifeAdviceGrid)
        tvStatus = view.findViewById(R.id.tvStatus)
        refreshLayout = view.findViewById(R.id.refreshLayout)
        tvRefreshTip = view.findViewById(R.id.tvRefreshTip)
        refreshTipRow = view.findViewById(R.id.refreshTipRow)
        refreshCloud = view.findViewById(R.id.refreshCloud)
        locationBar = view.findViewById(R.id.locationBar)
        topMask = view.findViewById(R.id.topMask)
        topOverlayContent = view.findViewById(R.id.topOverlayContent)
        hourlyChart = view.findViewById(R.id.hourlyChart)
        dailyListContainer = view.findViewById(R.id.dailyListContainer)
        dailyChartScroll = view.findViewById(R.id.dailyChartScroll)
        dailyChart = view.findViewById(R.id.dailyChart)
        dailyCard = view.findViewById(R.id.dailyCard)
        tvDailyMore = view.findViewById(R.id.tvDailyMore)
        tvModeChart = view.findViewById(R.id.tvModeChart)
        tvModeList = view.findViewById(R.id.tvModeList)
        modeHighlight = view.findViewById(R.id.modeHighlight)
        indicatorScroll = view.findViewById(R.id.indicatorScroll)
        indicatorContainer = view.findViewById(R.id.indicatorContainer)
        btnSettings = view.findViewById(R.id.btnSettings)
        tvSunLeftLabel = view.findViewById(R.id.tvSunLeftLabel)
        tvSunRightLabel = view.findViewById(R.id.tvSunRightLabel)
        tvSunCountdownLabel = view.findViewById(R.id.tvSunCountdownLabel)
        tvSunCountdownTime = view.findViewById(R.id.tvSunCountdownTime)
        windCompass = view.findViewById(R.id.windCompass)
        windLevelCard = view.findViewById(R.id.windLevelCard)
        tvWindLevel = view.findViewById(R.id.tvWindLevel)
        tvWindDesc = view.findViewById(R.id.tvWindDesc)
        tvHumidityValue = view.findViewById(R.id.tvHumidityValue)
        tvHumidityDesc = view.findViewById(R.id.tvHumidityDesc)
        tvHumidityDew = view.findViewById(R.id.tvHumidityDew)
        pressureGauge = view.findViewById(R.id.pressureGauge)
        tvVisibilityValue = view.findViewById(R.id.tvVisibilityValue)
        tvVisibilityDesc = view.findViewById(R.id.tvVisibilityDesc)
        tvFeelsValue = view.findViewById(R.id.tvFeelsValue)
        feelsDelta = view.findViewById(R.id.feelsDelta)
        tvFeelsDesc = view.findViewById(R.id.tvFeelsDesc)
        tvUvValue = view.findViewById(R.id.tvUvValue)
        tvUvDesc = view.findViewById(R.id.tvUvDesc)
        uvBar = view.findViewById(R.id.uvBar)
        tvAlertEntry = view.findViewById(R.id.tvAlertEntry)
        alertEntryCard = view.findViewById(R.id.alertEntryCard)
        ivAlertIcon = view.findViewById(R.id.ivAlertIcon)
        minutelyCard = view.findViewById(R.id.minutelyCard)
        minutelyChart = view.findViewById(R.id.minutelyChart)
        tvMinutelySummary = view.findViewById(R.id.tvMinutelySummary)
        tvMinutelyPeak = view.findViewById(R.id.tvMinutelyPeak)
        weatherLoadingOverlay = view.findViewById(R.id.weatherLoadingOverlay)
        weatherLoadingOverlay.onLoadingFinished = {
            if (isAdded && isResumed) startGlassLoop()
        }
        // 占位框跟随真实信息文字、图表和卡片的最终测量尺寸；屏幕/字体变化后也不会错位。
        weatherLoadingOverlay.setSkeletonTargets(
            // 明确以 View 为元素类型；首三个 TextView 不能让 Kotlin 将整个可变列表
            // 推断为 TextView[]，否则后续加入 BlurCardView 会在真机创建页面时崩溃。
            listOf<View>(
                tvTemp, tvDesc, tvHighLow,
                view.findViewById(R.id.hourlyCard), view.findViewById(R.id.dailyCard),
                view.findViewById(R.id.sunCard), view.findViewById(R.id.aqiCard),
                view.findViewById(R.id.cardWind), view.findViewById(R.id.cardHumidity),
                view.findViewById(R.id.cardPressure), view.findViewById(R.id.cardVisibility),
                view.findViewById(R.id.cardFeelsLike), view.findViewById(R.id.cardUv),
                view.findViewById(R.id.lifeAdviceCard)
            )
        )
        // 无数据状态不再绘制随内容滚动的透明骨架矩形；页面会先恢复最近一次
        // 完整快照，完全没有快照时仅保留真实的状态文字和下拉刷新入口。
        weatherLoadingOverlay.visibility = View.GONE
        ivSourceLogo = view.findViewById(R.id.ivSourceLogo)
        ivSourceSupplementLogo = view.findViewById(R.id.ivSourceSupplementLogo)
        tvStatusTime = view.findViewById(R.id.tvStatusTime)
        bottomStatusContainer = view.findViewById(R.id.bottomStatusContainer)

        setupMetricDetailCardClicks(view)
        val hourlyCard = view.findViewById<View>(R.id.hourlyCard)
        hourlyCard.setOnClickListener { card ->
            openHourlyForecastDetail(card)
        }
        // 小时图表位于可横滑的 ScrollView 内，会优先接收点击事件；把轻触入口也
        // 绑定到图表自身，才能保证用户点按卡片中的任一可见内容都可进入二级页。
        // 横向拖动仍由其父容器处理，不会误触发详情页。
        hourlyChart.setOnClickListener { openHourlyForecastDetail(hourlyCard) }
        dailyCard.setOnClickListener { card -> openForecastDetail(card) }
        dailyChart.onDayClick = { index -> dailyDisplayedDates.getOrNull(index)?.let { openForecastDetail(dailyCard, it) } }

        // 主页九宫格只作入口，使用与其余天气卡一致的卡片展开转场。
        view.findViewById<View>(R.id.lifeAdviceCard).setOnClickListener { card ->
            OplusViewSeamlessNavigator.startFromCard(
                this,
                card,
                LifeAdviceActivity.createIntent(requireContext(), lifeAdvice)
            )
        }

        // 灾害预警入口：整张毛玻璃卡片可点击，进入预警二级界面
        alertEntryCard.setOnClickListener { card ->
            val i = Intent(requireContext(), AlertActivity::class.java)
            i.putStringArrayListExtra("titles", alertTitles)
            i.putStringArrayListExtra("levels", alertLevels)
            i.putStringArrayListExtra("contents", alertContents)
            i.putStringArrayListExtra("times", alertTimes)
            i.putStringArrayListExtra("codes", alertCodes)
            i.putStringArrayListExtra("locations", alertLocations)
            i.putStringArrayListExtra("sources", alertSources)
            OplusViewSeamlessNavigator.startFromCard(this, card, i)
        }

        // 右上角三个点：进入天气设置二级菜单
        btnSettings.setOnClickListener {
            startActivity(Intent(requireContext(), WeatherSettingsActivity::class.java))
        }

        // 当前定位页：直接显示详细地址；其他城市名统一补全“市”字
        tvCity.text = cityDisplayName()
        if (page.isCurrentLocation && page.locationDetail.isNotEmpty()) {
            tvLocationDetail.text = page.locationDetail
            tvLocationDetail.visibility = View.GONE
        }

        // 预览模式：不显示地址指示条和右上角设置入口
        if (args?.getBoolean("preview") == true) {
            previewMode = true
            view.findViewById<View>(R.id.locationBar).visibility = View.GONE
            btnSettings.visibility = View.GONE
            indicatorScroll.visibility = View.GONE
            // 预览页直接复用天气页自身的顶部蒙版；不再由 Activity 维护另一套尺寸/渐变参数。
            topMask.visibility = View.VISIBLE
        }

        // 点击位置文字进入城市管理：仅中轴线以左生效，右侧留给设置等操作
        val locationBar = view.findViewById<View>(R.id.locationBar)
        locationBar.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) v.tag = e.x
            false
        }
        locationBar.setOnClickListener {
            val x = (it.tag as? Float) ?: 0f
            val screenW = resources.displayMetrics.widthPixels.toFloat()
            if (it.left + x <= screenW / 2f) {
                (activity as? MainActivity)?.openCityManage()
            }
        }

        // 多日预报：右上角切换 折线/列表（高亮块滑动），列表底部按钮展开/收起 15 天
        modeTabWidth = dp(47f)
        tvModeChart.setOnClickListener { setDailyMode(true, animate = true) }
        tvModeList.setOnClickListener { setDailyMode(false, animate = true) }
        tvDailyMore.setOnClickListener {
            expandedDaily = !expandedDaily
            updateDailyExpand(animate = true)
        }
        setDailyMode(false, animate = false)

        // 页面回调只注册一次、不随 onPause 注销：
        // 避免滑动切换城市时"正在遍历回调列表又被增删"导致 ConcurrentModificationException
        callbackPager=(activity as? MainActivity)?.findViewById<ViewPager2>(R.id.main)
        callbackPager?.registerOnPageChangeCallback(pageCallback)

        // 下拉刷新：隐藏默认白色圆圈，改用云朵线条动画；
        // 触发距离与云朵画满距离保持一致
        refreshLayout.setColorSchemeColors(Color.TRANSPARENT)
        refreshLayout.setProgressBackgroundColorSchemeColor(Color.TRANSPARENT)
        // 云朵提示与系统触发阈值保持一致；降低到 88dp 后短距离下拉也能可靠进入刷新。
        refreshLayout.setDistanceToTriggerSync(dp(88f))
        (refreshLayout as? CloudSwipeRefreshLayout)?.let {
            it.triggerDistancePx = dp(88f)
            it.pullProgressListener = { p -> onPullProgress(p) }
            it.contentSettledListener = { if (isAdded) alignBottom() }
        }
        refreshLayout.setOnRefreshListener { refreshAll() }

        // 顶部模糊蒙版 + 三张信息卡片：每帧截取背后的画面做 GPU 模糊（BlurLayout 方式）
        // 顶部蒙版：接近无色透明的模糊（中性深色、低透明度）
        // 蒙版只保留模糊，颜色完全透明
        topMask.setTint(Color.TRANSPARENT, Color.TRANSPARENT)
        topMask.setCornerRadius(0f)
        topMask.setStrokeVisible(false)
        // 实际淡化起止位置在 updateTopMaskSize() 中按城市文字行计算。
        // 顶部地址栏蒙版模糊的是实时内容（含所有卡片），与普通卡片用不同的截图源
        topMask.blursContentInsteadOfBackground = true
        // 顶部时蒙版完全透明，滚动后按距离渐显（已去除底部描边分割线）
        topMask.alpha = 0f
        listOf(
            R.id.topMask, R.id.minutelyCard, R.id.hourlyCard, R.id.dailyCard,
            R.id.sunCard, R.id.aqiCard,
            R.id.cardWind, R.id.cardHumidity,
            R.id.cardPressure, R.id.cardVisibility,
            R.id.cardFeelsLike, R.id.cardUv,
            R.id.windLevelCard, R.id.alertEntryCard, R.id.lifeAdviceCard
        ).forEach { id ->
            view.findViewById<BlurCardView>(id).let { glassCards.add(it) }
        }
        // 灾害预警卡沿用相同描边和填充规则；仅保留无内侧光晕，避免干扰图标与文字。
        alertEntryCard.setStrokeAlpha(64)
        alertEntryCard.setInnerGlowVisible(false)

        // 页面滚动时毛玻璃必须跟手：滚动中提升到 60fps 截图；
        // 同时按滚动距离渐显顶部蒙版和分割线
        val weatherScroll = requireView().findViewById<ScrollView>(R.id.weatherRoot)
        weatherScroll
            .setOnScrollChangeListener { _, _, _, _, scrollY ->
                lastWeatherScrollY = scrollY
                setFastGlass(true)
                // 回到顶部时重新校正弹性空白（此时测量不含滚动偏移）
                if (scrollY == 0) alignBottom()
                // 实时温度文字块（温度/描述/最高最低）还远离蒙版下缘时蒙版就完全就位，
                // 避免半透明渐显过程与温度文字重叠。
                // 直接用“温度文字顶端与蒙版下缘的几何距离”计算渐显进度，
                // 不再叠加状态栏高度/固定 150dp 等常量，避免预览页因布局差异而失效。
                // 蒙版下缘用“实时屏幕几何”计算：每次滚动都重新读取当前高度与位置，
                // 状态栏/蒙版高度中途变化时下一帧自动校正，避免预览页偶发失效。
                val chromeBottom = topMask.bottom.takeIf { it > 0 }
                    ?: topOverlayContent.height.takeIf { it > 0 }
                    ?: (statusBarHeight + dp(60f))
                val reveal = TopChromeScrollPolicy.revealProgress(
                    scrollY, tvTemp.top, chromeBottom + dp(16f), dp(64f)
                )
                if(topMask.alpha<=0f && reveal>0f) lastMaskGlassFrameNanos=0L
                topMask.alpha = reveal
                val indicatorAlpha = 1f - reveal
                indicatorScroll.alpha = indicatorAlpha
                indicatorScroll.visibility =
                    if (indicatorAlpha <= 0.02f) View.INVISIBLE else View.VISIBLE
                // 预览页仅让顶部实时模糊蒙版随滚动显隐；地址文字和操作按钮保持可见。
                updateBottomStatusVisibility(weatherScroll, scrollY)
                // 日出日落、AQI、紫外线卡从屏幕外重新进入时才播放非线性指示条；
                // 判断使用真实屏幕可见范围，避免滚动位置或布局缓存不一致。
                updateVisibleIndicatorAnimations()
            }
        weatherScroll.post { updateBottomStatusVisibility(weatherScroll, weatherScroll.scrollY, animate = false) }

        // 状态栏避让：地址栏内容仍然从状态栏之下开始，
        // 但蒙版可以向上延伸到状态栏区域（系统状态栏文字绘制在蒙版之上，不会被遮盖）
        ViewCompat.setOnApplyWindowInsetsListener(requireView()) { _, insets ->
            applyInsets()
            insets
        }
        view?.post {
            if (isAdded) applyInsets()
        }

        // 只有 API 31 以下才需要 CPU 模糊线程；真机 GPU 路径不需要
        if (!useGpuBlur && blurThread == null) {
            blurThread = Thread({ blurLoop() }, "glass-blur").also { it.start() }
        }

        refreshIndicator()
        updateTopMaskSize()
        alignBottom()
        appliedSource = WeatherSettings.dataSource(requireContext())
        appliedApiRevision = UserApiConfiguration.revision(requireContext())
        // 先呈现不超过一天的上次成功数据；网络结果回来后再无感覆盖，避免弱网冷启动白屏。
        restoreCachedWeather()
        if (previewMode || activity !is MainActivity) {
            // 预览页不在主页的城市队列中，必须在创建后立即获取数据。
            fetchWeather(page.lng, page.lat)
        } else {
            // ViewPager 初次附着时，MainActivity 的城市刷新队列可能早于 Fragment 建立。
            // 当前页尚未发出主请求时兜底；已有快照不能阻止本次启动拉取实况。
            view.postDelayed({
                if (isAdded && isCurrentPage() && fetchSeq == 0) {
                    Log.w("WeatherApp", "entry refresh missed fragment; starting fallback request")
                    fetchWeather(page.lng, page.lat)
                }
            }, 700L)
        }
    }

    // ===== 下拉刷新：天气 + 定位一起刷新，完成/失败后给出提示 =====
    private fun refreshAll(onFinished: (() -> Unit)? = null) {
        if (WeatherAccessFeedback.needsApi(requireContext())) {
            refreshOk = false
            updateApiSetupNotice(true)
            finishInitialLoading()
            refreshLayout.isRefreshing = false
            (refreshLayout as? CloudSwipeRefreshLayout)?.finishRefreshMotion()
            showRefreshStatus(WeatherAccessFeedback.MISSING_API)
            if (isCurrentPage() && activity?.let { it is androidx.appcompat.app.AppCompatActivity && FirstRunGuide.isShowing(it) } != true) {
                android.widget.Toast.makeText(requireContext(), WeatherAccessFeedback.MISSING_API, android.widget.Toast.LENGTH_LONG).show()
            }
            onFinished?.invoke()
            return
        }
        if (refreshing) {
            onFinished?.invoke()
            return
        }
        refreshing = true
        refreshOk = true
        refreshTasks = 0
        refreshFinishedCallback = onFinished
        refreshLayout.isRefreshing = true
        // 刷新中提示：云朵线条缺口滚动 + "页面刷新中"
        showRefreshStatus("页面刷新中", autoHide = false, refreshingState = true)

        startRefreshTask { fetchWeather(page.lng, page.lat, fromRefresh = true) }

        // 当前定位页：同时请求最新定位，拿到后原地更新地址并用新坐标再刷一次天气
        if (page.isCurrentLocation) {
            startRefreshTask {
                (activity as? MainActivity)?.requestLocationRefresh { ok, lng, lat, detail ->
                    if (!isAdded) {
                        finishRefreshTask(ok)
                        return@requestLocationRefresh
                    }
                    if (ok && (lng != page.lng || lat != page.lat)) {
                        page = page.copy(lng = lng, lat = lat, locationDetail = detail)
                        tvCity.text = cityDisplayName()
                        updateTopMaskSize()
                        startRefreshTask { fetchWeather(page.lng, page.lat, fromRefresh = true) }
                    }
                    finishRefreshTask(ok)
                }
            }
        }

        // 安全兜底：个别请求异常挂起时最多等 15 秒强制结束刷新
        refreshLayout.postDelayed({
            if (refreshing) {
                completeRefreshCycle()
            }
        }, WeatherRefreshPolicy.SILENT_CITY_TIMEOUT_MS)
    }

    private fun startRefreshTask(task: () -> Unit) {
        refreshTasks++
        task()
    }

    private fun finishRefreshTask(ok: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finishRefreshTask(ok) }
            return
        }
        if (!refreshing) return
        if (!ok) refreshOk = false
        refreshTasks--
        if (refreshTasks <= 0) {
            completeRefreshCycle()
        }
    }

    private fun completeRefreshCycle() {
        if (!refreshing) return
        refreshing = false
        refreshTasks = 0
        refreshLayout.isRefreshing = false
        (refreshLayout as? CloudSwipeRefreshLayout)?.finishRefreshMotion()
        showRefreshStatus(refreshDoneText())
        // 回弹期间不改弹性空白高度；CloudSwipeRefreshLayout 会在动画结束后回调校正。
        if ((refreshLayout as? CloudSwipeRefreshLayout)?.isContentMotionActive() != true) {
            view?.post { if (isAdded) alignBottom() }
        }
        val callback = refreshFinishedCallback
        refreshFinishedCallback = null
        callback?.invoke()
    }

    // 每次进入天气主页时由 MainActivity 调用：刷新本页天气；
    // showIndicator=true 时走完整下拉刷新流程（刷新提示 + 云朵动画，供当前可见页使用），
    // 其余城市页静默刷新。定位页同时请求最新定位，位置变化时原地更新地址并用新坐标重刷。
    fun refreshOnEntry(
        showIndicator: Boolean = false,
        onFinished: (() -> Unit)? = null
    ) {
        if (!isAdded) {
            onFinished?.invoke()
            return
        }
        Log.i("WeatherApp", "entry refresh started")
        if (showIndicator && isAtInitialViewport()) {
            refreshAll(onFinished)
            return
        }
        refreshSilently(onFinished)
    }

    private fun isAtInitialViewport(): Boolean =
        view?.findViewById<ScrollView>(R.id.weatherRoot)?.scrollY == 0

    // 城市切换完成后立即检查本城市的新鲜度；90 秒内已成功刷新则复用，避免连续横滑重复联网。
    private fun refreshWhenPageVisible() {
        val now = System.currentTimeMillis()
        if (refreshing || pageRefreshInFlight ||
            !WeatherRefreshPolicy.shouldRefreshPageCity(now, lastPageRefreshAt)
        ) return
        pageRefreshInFlight = true
        refreshSilently { pageRefreshInFlight = false }
    }

    private fun refreshSilently(onFinished: (() -> Unit)?) {
        var remaining = if (page.isCurrentLocation && activity is MainActivity) 2 else 1
        var completed = false
        var timeout: Runnable? = null
        fun finish() {
            if (completed) return
            completed = true
            timeout?.let(mainHandler::removeCallbacks)
            onFinished?.invoke()
        }
        val taskFinished: (Boolean) -> Unit = {
            remaining--
            if (remaining <= 0) finish()
        }
        val timeoutRunnable = Runnable { finish() }
        timeout = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, WeatherRefreshPolicy.SILENT_CITY_TIMEOUT_MS)
        fetchWeather(page.lng, page.lat, onComplete = taskFinished)
        if (page.isCurrentLocation) {
            (activity as? MainActivity)?.requestLocationRefresh { ok, lng, lat, detail ->
                if (!isAdded) { taskFinished(false); return@requestLocationRefresh }
                if (ok) {
                    val moved = lng != page.lng || lat != page.lat
                    page = page.copy(lng = lng, lat = lat, locationDetail = detail)
                    tvCity.text = cityDisplayName()
                    updateTopMaskSize()
                    if (moved) {
                        fetchWeather(lng, lat, onComplete = taskFinished)
                        return@requestLocationRefresh
                    }
                }
                taskFinished(ok)
            }
        }
    }

    // 供 MainActivity 判断该页面是否对应当前可见城市
    fun matchesPage(p: CityPage): Boolean =
        page.name == p.name && page.lng == p.lng && page.lat == p.lat

    private fun showRefreshStatus(
        text: String,
        autoHide: Boolean = true,
        refreshingState: Boolean = false
    ) {
        tvRefreshTip.text = text
        refreshTipRow.visibility = View.VISIBLE
        refreshTipRow.alpha = 1f
        if (refreshingState) {
            refreshCloud.setRefreshing(true)
        } else {
            refreshCloud.setSuccess(refreshOk)
        }
        if (autoHide) {
            hideRefreshStatusLater()
        }
    }

    // 下拉进度回调：0..1 云朵逐步画出；≥1 显示"松开刷新"；-1 手指离开
    private fun onPullProgress(p: Float) {
        if (refreshing) return
        if (p < 0f) {
            if (refreshTipRow.visibility == View.VISIBLE) hideRefreshCloud()
            return
        }
        // 下拉过程中毛玻璃切到跟手刷新，避免内容位移时卡片模糊滞后造成抖动感
        setFastGlass(true)
        refreshTipRow.visibility = View.VISIBLE
        refreshTipRow.alpha = 1f
        if (p >= 1f) {
            tvRefreshTip.text = "松开刷新"
            refreshCloud.setReady()
        } else {
            tvRefreshTip.text = "下拉刷新"
            refreshCloud.setPullProgress(p)
        }
    }

    private fun hideRefreshCloud() {
        refreshTipRow.removeCallbacks(refreshTipHideRunnable)
        refreshTipRow.visibility = View.GONE
        refreshCloud.setRefreshing(false)
        refreshCloud.setSuccess(false)
        refreshCloud.setPullProgress(0f)
    }

    // 刷新完成文案：成功显示数据发布时间，失败显示网络提示
    private fun refreshDoneText(): String {
        // A late network completion may arrive after this city's Fragment is detached.
        return WeatherAccessFeedback.refreshResult(context?.let(WeatherAccessFeedback::needsApi) == true, refreshOk)
    }

    private fun hideRefreshStatusLater() {
        refreshTipRow.removeCallbacks(refreshTipHideRunnable)
        refreshTipRow.postDelayed(refreshTipHideRunnable, 1200)
    }

    // 应用状态栏/导航栏避让：内容在状态栏之下开始，顶部蒙版则从屏幕最顶开始
    private fun applyInsets() {
        val v = view ?: return
        val insets = ViewCompat.getRootWindowInsets(v) ?: return
        val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        statusBarHeight = sb.top
        // 预览页没有外层 ViewPager 避让导航栏，这里自己补底部 insets，
        // 保证与默认天气页一样默认完整展示 48 小时卡片
        refreshLayout.setPadding(sb.left, sb.top, sb.right, if (previewMode) sb.bottom else 0)
        // 地址栏上移：去掉刷新提示占位后，只保留极小的状态栏间距
        topOverlayContent.setPadding(dp(20f), sb.top + dp(2f), dp(20f), dp(8f))
        updateRefreshTipPosition()
        updateTopMaskSize()
        // insets 到达/变化后会改变内容位置，需要重新校正弹性空白
        alignBottom()
    }

    // 刷新状态行放在圆点指示器下方（蒙版区域之外）
    private fun updateRefreshTipPosition() {
        val v = view ?: return
        v.post {
            if (!isAdded) return@post
            val lp = refreshTipRow.layoutParams as? FrameLayout.LayoutParams ?: return@post
            // 预览页顶部被“取消/城市名/添加”胶囊栏接管，刷新提示整体下移避免被遮挡
            lp.topMargin = if (previewMode) {
                statusBarHeight + dp(64f)
            } else {
                topOverlayContent.height + dp(8f)
            }
            // 与地址栏文字左对齐（地址文字从 20dp 处开始）
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = dp(20f)
            refreshTipRow.layoutParams = lp
        }
    }

    // 计算顶部蒙版高度：城市文字行底部保持完整模糊，之后逐步退为清晰背景。
    private fun updateTopMaskSize() {
        val v = view ?: return
        if (!::locationBar.isInitialized) return
        v.post {
            if (!isAdded) return@post
            if (previewMode) {
                // 预览页隐藏了主页的位置栏，不能再从被隐藏的城市文字测量高度。
                // 使用与主页“状态栏 + 城市行 + 6dp 起始 + 44dp 渐隐 + 8dp 清除”相同的几何，
                // 让全宽实时模糊蒙版保持同样尺寸和渐变过渡。
                val fadeStart = statusBarHeight + dp(38f) + dp(6f)
                val clearInset = dp(8f)
                val h = fadeStart + dp(44f) + clearInset
                topMask.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    h
                ).apply {
                    gravity = Gravity.TOP
                }
                topMask.setVerticalFadeRange(fadeStart.toFloat(), clearInset.toFloat())
                updateRefreshTipPosition()
                return@post
            }
            // 使用屏幕坐标避免因嵌套 LinearLayout 的局部坐标造成固定排版跳变。
            // 城市名文字行下方 6dp 为 100% 模糊，随后向下 44dp 渐变，
            // 在蒙版底端前 8dp 已降为清晰。
            val cityPosition = IntArray(2)
            val maskPosition = IntArray(2)
            tvCity.getLocationOnScreen(cityPosition)
            topMask.getLocationOnScreen(maskPosition)
            val cityRowBottom = (cityPosition[1] - maskPosition[1] + tvCity.height)
                .coerceAtLeast(dp(40f))
            if (isCurrentPage()) {
                // 背景与页面同处于 edge-to-edge 坐标系，直接使用城市文字的屏幕下沿。
                (activity as? WeatherUiHost)?.hostBackgroundCloudSafeTop(
                    cityPosition[1] + tvCity.height + dp(12f)
                )
            }
            val fadeStart = cityRowBottom + dp(6f)
            val clearInset = dp(8f)
            val h = maxOf(
                topOverlayContent.height + clearInset,
                fadeStart + dp(44f) + clearInset,
                dp(40f)
            )
            topMask.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                h
            ).apply {
                gravity = Gravity.TOP
            }
            topMask.setVerticalFadeRange(fadeStart.toFloat(), clearInset.toFloat())
            // 内容尚未测量时，等下一次布局后重新计算
            if (topOverlayContent.height == 0) {
                if (isAdded) v.post { updateTopMaskSize() }
            } else {
                updateRefreshTipPosition()
            }
        }
    }

    // 把 24 小时预报卡片的底端对齐到屏幕最低可见线：测出卡片底与可见底线的空隙，用弹性空白填上
    private fun alignBottom() {
        val v = view ?: return
        v.post {
            if (!isAdded || view == null) return@post
            // 下拉/回弹过程中不允许弹性空白改高度，否则会形成“改高度→重新布局→再改”的跳跃循环。
            if ((refreshLayout as? CloudSwipeRefreshLayout)?.isContentMotionActive() == true) return@post
            val scroll = v.findViewById<View>(R.id.weatherRoot)
            if (scroll.translationY != 0f) return@post
            // 只在初始位置（未滚动）校正：滚动中的测量会把滚动偏移算进去，
            // 导致弹性空白随滚动无限增长、内容被顶回初始位置
            if (scroll.scrollY != 0) return@post
            val card = v.findViewById<View>(R.id.hourlyCard)
            val spacer = v.findViewById<View>(R.id.tempSpacer)
            val out = IntArray(2)
            card.getLocationOnScreen(out)
            val cardBottom = out[1] + card.height
            v.getLocationOnScreen(out)
            // 预览页内容区自己避让底部导航栏：可见底线 = 视图底 - 底部内边距
            val spare = out[1] + v.height - refreshLayout.paddingBottom - cardBottom
            val lp = spacer.layoutParams as? LinearLayout.LayoutParams
            val current = lp?.height ?: 0
            // 目标高度 = 当前空隙 + 当前弹性空白高度：
            // 直接拿“空隙”当目标会在对齐后变成 0，下一轮又改回，形成 0↔正确值 的周期翻转（闪烁抖动）
            // 仅主页的天气内容相对当前下移 3dp；顶部地址栏、蒙版与预览页不受影响。
            val visualLift = if (previewMode) 0 else dp(13f)
            val target = HourlyCardAlignmentPolicy.targetSpacerHeight(current, spare, visualLift)
            // 1px 容差：稳定后不再重设，避免布局抖动/无限重排
            if (target != null && Math.abs(target - current) > 1) {
                // 冷却：避免刚设置的高度还没被布局应用时，连续回调互相覆盖造成启动期抖动
                val now = System.currentTimeMillis()
                if (now - lastSpacerSetAt < 150) return@post
                lastSpacerSetAt = now
                spacer.layoutParams = LinearLayout.LayoutParams(dp(1f), target)
            }
        }
    }

    // 重新回到前台时（比如搜索页切回、页切换、系统深浅色切换后）重新刷背景和指示器
    override fun onResume() {
        super.onResume()
        if (lastSkycon != null) applyBackground()
        refreshIndicator()
        if (!::weatherLoadingOverlay.isInitialized || !weatherLoadingOverlay.isLoading()) {
            startGlassLoop()
        }
        // 倒计时每 30 秒刷新一次
        mainHandler.removeCallbacks(countdownRunnable)
        mainHandler.post(countdownRunnable)
        // 从二级页、设置页或后台回到当前城市时，重新检查三类指示条的入场动画。
        requestIndicatorEntryAnimation()
        // 先恢复二级页返回前的位置，再决定是否需要初始页对齐；不能让顶部对齐逻辑
        // 抢在 ScrollView 状态恢复前执行，否则会把用户阅读中的页面拉回默认位置。
        view?.post {
            if (!isAdded) return@post
            val scroll = view?.findViewById<ScrollView>(R.id.weatherRoot) ?: return@post
            if (lastWeatherScrollY > 0) {
                scroll.scrollTo(0, lastWeatherScrollY)
            } else {
                alignBottom()
            }
            updateBottomStatusVisibility(scroll, scroll.scrollY, animate = false)
        }
        if (isAdded) {
            val src = WeatherSettings.dataSource(requireContext())
            if (src != appliedSource || (BuildConfig.USER_CONFIGURABLE &&
                    appliedApiRevision != UserApiConfiguration.revision(requireContext()))) {
                resetSourceState()
                appliedSource = src
                fetchWeather(page.lng, page.lat)
            } else {
                // 单位设置可能已变化：用最新单位重绘当前数据，不需要重新联网
                if(renderedDisplaySettings!=displaySettingsKey()) reapplyUnits()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        view?.findViewById<ScrollView>(R.id.weatherRoot)?.let { lastWeatherScrollY = it.scrollY }
        mainHandler.removeCallbacks(countdownRunnable)
        // 切走/滑走时不白跑模糊，省电
        stopGlassLoop()
        if (isCurrentPage()) (activity as? WeatherUiHost)?.hostBackgroundFooterProgress(0f)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopGlassLoop()
        moreAnimator?.cancel()
        endDailyCardBlurFreeze()
        alertHandler.removeCallbacks(alertScrollRunnable)
        blurThread?.interrupt()
        blurThread = null
        val oldPager=callbackPager
        callbackPager=null
        // ViewPager may currently be dispatching a page callback; defer removal until
        // that traversal ends, while pairing each view's registration with destruction.
        oldPager?.post {oldPager.unregisterOnPageChangeCallback(pageCallback)}
        glassCards.clear();externalContentMasks.clear();overlayViews.clear()
        renderedDisplaySettings=null
    }

    // ===== 毛玻璃：每帧把根视图画面截成小图，喂给三张卡片做模糊背景 =====
    private fun startGlassLoop() {
        stopGlassLoop()
        val runnable = object : Runnable {
            override fun run() {
                refreshGlass()
                // 滚动时按帧刷新，静止时降到约 7fps；毛玻璃仍持续更新，但不再占满主线程。
                if (fastGlass) {
                    Choreographer.getInstance().postFrameCallback(glassFrameCallback)
                } else {
                    glassHandler.postDelayed(this, 140L)
                }
            }
        }
        glassRunnable = runnable
        glassHandler.post(runnable)
    }

    // 滚动/切页时切到快速刷新；停止滚动后很快回落，减少截图与模糊造成的掉帧。
    private fun setFastGlass(fast: Boolean) {
        glassIdleReset?.let { glassHandler.removeCallbacks(it) }
        glassIdleReset = null
        if (fast) {
            val wasFast = fastGlass
            fastGlass = true
            val reset = Runnable {
                fastGlass = false
                Choreographer.getInstance().removeFrameCallback(glassFrameCallback)
                glassRunnable?.let {
                    glassHandler.removeCallbacks(it)
                    glassHandler.post(it)
                }
            }
            glassIdleReset = reset
            glassHandler.postDelayed(reset, 120L)
            // 从静止切到滚动：取消排队中的慢刷新，立即进入帧同步快速刷新
            if (!wasFast) {
                glassRunnable?.let {
                    glassHandler.removeCallbacks(it)
                }
                Choreographer.getInstance().removeFrameCallback(glassFrameCallback)
                Choreographer.getInstance().postFrameCallback(glassFrameCallback)
            }
        } else {
            fastGlass = false
            Choreographer.getInstance().removeFrameCallback(glassFrameCallback)
            glassRunnable?.let {
                glassHandler.removeCallbacks(it)
                glassHandler.post(it)
            }
        }
    }

    // 供宿主（如城市预览页）把外部胶囊注册进毛玻璃循环，获得与卡片一致的真实模糊
    fun addExternalGlassCard(card: BlurCardView) {
        glassCards.add(card)
    }

    // 预览页顶部与主页一样模糊滚动内容；外部覆盖文字/按钮可另行注册，保持清晰。
    fun addExternalContentMask(card: BlurCardView) {
        card.blursContentInsteadOfBackground = true
        glassCards.add(card)
        externalContentMasks.add(card)
    }

    // 外部覆盖层内容（如预览页顶部按钮）：截图时一并隐藏，避免出现在蒙版模糊里
    fun addExternalOverlayView(v: View) {
        overlayViews.add(v)
    }

    private fun stopGlassLoop() {
        glassIdleReset?.let {glassHandler.removeCallbacks(it)}
        glassIdleReset=null;fastGlass=false
        glassRunnable?.let { glassHandler.removeCallbacks(it) }
        glassRunnable = null
        lastMaskGlassFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(glassFrameCallback)
    }

    private fun refreshGlass(includeContentMasks: Boolean = true) {
        if (glassCards.isEmpty()) return
        val root = (requireActivity() as? Activity)?.window?.decorView
            ?.findViewById<View>(android.R.id.content) ?: return
        if (root.width <= 0 || root.height <= 0) return
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val loc = IntArray(2)

        // 展开/收起多日卡时，它由固定背景帧单独绘制；排除实时截图可避免高度每帧
        // 变化触发 Bitmap 重建与旧帧缩放。
        val normal = glassCards.filter {
            !it.blursContentInsteadOfBackground && !(dailyBlurFreeze != null && it === dailyCard)
        }
        val masks = glassCards.filter { it.blursContentInsteadOfBackground }

        if (useGpuBlur) {
            // 背景截图：只保留共享天气背景 → 给普通卡片。
            // 不再把卡片/文字一起截进源图，滚动时不会出现旧帧叠在云朵上的拖影或闪白。
            captureGlass(root, rootLoc, loc, normal, hideCards = true)
            // 内容截图：只隐藏蒙版自身和覆盖层文字，保留全部卡片内容 → 给顶部蒙版，
            // 使所有卡片与实时文字统一处于同一平面，滑到蒙版下方时被实时模糊
            if (includeContentMasks) captureGlass(root, rootLoc, loc, masks, hideCards = false)
            return
        }

        // API 31 以下 CPU 路径：保持单次背景截图（旧设备蒙版也复用同一背景源）
        captureGlass(root, rootLoc, loc, glassCards, hideCards = true)
    }

    /**
     * 为多日卡片将要经历的最大高度截取一次纯天气背景。卡片顶部保持不动，之后每帧
     * 只从这张固定根坐标图中取当前高度的区域，因此不会出现旧毛玻璃被纵向拉伸。
     */
    private fun beginDailyCardBlurFreeze(maxCardHeight: Int) {
        if (!useGpuBlur || !::dailyCard.isInitialized || dailyCard.width <= 0) return
        val root = (activity as? Activity)?.window?.decorView
            ?.findViewById<View>(android.R.id.content) ?: return
        val pageLayer = view ?: return
        val rootLoc = IntArray(2)
        val cardLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        dailyCard.getLocationInWindow(cardLoc)
        val left = cardLoc[0] - rootLoc[0]
        val top = cardLoc[1] - rootLoc[1]
        val width = dailyCard.width
        val height = maxOf(dailyCard.height, maxCardHeight)
        if (width <= 0 || height <= 0) return
        val bitmap = Bitmap.createBitmap(
            (width * SHOT_QUALITY).toInt().coerceAtLeast(1),
            (height * SHOT_QUALITY).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )

        val hidden = ArrayList<Pair<View, Int>>()
        hidden += topMask to topMask.visibility
        hidden += topOverlayContent to topOverlayContent.visibility
        topMask.visibility = View.INVISIBLE
        topOverlayContent.visibility = View.INVISIBLE
        for (overlay in overlayViews) {
            hidden += overlay to overlay.visibility
            overlay.visibility = View.INVISIBLE
        }
        val previousAlpha = pageLayer.alpha
        pageLayer.alpha = 0f
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.scale(SHOT_QUALITY, SHOT_QUALITY)
            canvas.translate(-left.toFloat(), -top.toFloat())
            root.draw(canvas)
        } finally {
            pageLayer.alpha = previousAlpha
            hidden.forEach { (v, visibility) -> v.visibility = visibility }
        }
        dailyBlurFreeze = DailyBlurFreeze(bitmap, bitmap.width, bitmap.height)
        applyDailyCardBlurFreeze()
    }

    private fun applyDailyCardBlurFreeze() {
        val frame = dailyBlurFreeze ?: return
        if (!::dailyCard.isInitialized || dailyCard.height <= 0) return
        val height = (dailyCard.height * SHOT_QUALITY).toInt()
            .coerceIn(1, frame.maxHeight)
        dailyCard.setLiveSource(frame.bitmap, Rect(0, 0, frame.width, height))
    }

    private fun endDailyCardBlurFreeze() {
        if (dailyBlurFreeze == null) return
        dailyBlurFreeze = null
        // 结束后重新回到实时背景帧，后续云朵和滚动仍按正常频率更新。
        view?.post { if (isAdded) refreshGlass() }
    }

    // 截取给定玻璃卡背后的画面：hideCards=true 时剔除所有卡片（背景源），
    // false 时保留卡片内容只剔除蒙版自身（实时内容源）
    private fun captureGlass(
        root: View,
        rootLoc: IntArray,
        loc: IntArray,
        cards: List<BlurCardView>,
        hideCards: Boolean
    ) {
        if (cards.isEmpty()) return
        var minL = Int.MAX_VALUE
        var minT = Int.MAX_VALUE
        var maxR = Int.MIN_VALUE
        var maxB = Int.MIN_VALUE
        var any = false
        val visible = ArrayList<BlurCardView>(cards.size)
        val regions = ArrayList<Rect>(cards.size)
        for (c in cards) {
            if(!c.isShown || c.alpha<=0f) continue
            val cw = c.width
            val ch = c.height
            if (cw <= 0 || ch <= 0) continue
            c.getLocationInWindow(loc)
            val l = loc[0] - rootLoc[0]
            val t = loc[1] - rootLoc[1]
            if (l >= root.width || t >= root.height || l + cw <= 0 || t + ch <= 0) continue
            minL = minOf(minL, l)
            minT = minOf(minT, t)
            maxR = maxOf(maxR, l + cw)
            maxB = maxOf(maxB, t + ch)
            visible.add(c)
            regions.add(Rect(l, t, l + cw, t + ch))
            any = true
        }
        if (!any) return

        val sw = ((maxR - minL) * SHOT_QUALITY).toInt().coerceAtLeast(1)
        val sh = ((maxB - minT) * SHOT_QUALITY).toInt().coerceAtLeast(1)

        if (useGpuBlur) {
            val cached = if (hideCards) shotBmp else maskShotBmp
            val cw = if (hideCards) shotW else maskShotW
            val chh = if (hideCards) shotH else maskShotH
            val bitmap = if (cached != null && cw == sw && chh == sh) {
                cached
            } else {
                Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also {
                    if (hideCards) {
                        shotBmp = it
                        shotW = sw
                        shotH = sh
                    } else {
                        maskShotBmp = it
                        maskShotW = sw
                        maskShotH = sh
                    }
                }
            }

            val savedAlphas = ArrayList<Pair<View, Float>>()
            val savedVis = ArrayList<Pair<View, Int>>()
            // 主页和预览页都必须排除蒙版本身及固定顶部层，避免预览页把上一帧
            // 蒙版重新录入截图而形成自我采样，导致模糊/半透明效果与主页不一致。
            savedVis.add(topMask to topMask.visibility)
            topMask.visibility = View.INVISIBLE
            savedVis.add(topOverlayContent to topOverlayContent.visibility)
            topOverlayContent.visibility = View.INVISIBLE
            for (v in overlayViews) {
                savedVis.add(v to v.visibility)
                v.visibility = View.INVISIBLE
            }
            // 普通卡片的源图直接从共同的天气背景取得。此前只逐一隐藏玻璃卡，
            // 仍会把温度文字、其他卡片及其上一帧的玻璃内容录入，滚动时形成拖影。
            val pageLayer = if (hideCards) view else null
            if (pageLayer != null) {
                savedAlphas.add(pageLayer to pageLayer.alpha)
                pageLayer.alpha = 0f
            } else if (hideCards) {
                for (c in glassCards) {
                    if (c === topMask) continue
                    savedAlphas.add(c to c.alpha)
                    c.alpha = 0f
                }
            } else {
                for (c in cards) {
                    if (c === topMask) continue
                    savedVis.add(c to c.visibility)
                    c.visibility = View.INVISIBLE
                }
            }
            val canvas = Canvas(bitmap)
            // 画板会复用；根视图任一帧含透明区域时先清空，防止旧帧的白色残影混入实时蒙版。
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.scale(SHOT_QUALITY, SHOT_QUALITY)
            canvas.translate(-minL.toFloat(), -minT.toFloat())
            root.draw(canvas)
            for ((v, a) in savedAlphas) v.alpha = a
            for ((v, vis) in savedVis) v.visibility = vis

            for (i in visible.indices) {
                val r = regions[i]
                visible[i].setLiveSource(
                    bitmap,
                    Rect(
                        ((r.left - minL) * SHOT_QUALITY).toInt().coerceAtLeast(0),
                        ((r.top - minT) * SHOT_QUALITY).toInt().coerceAtLeast(0),
                        ((r.right - minL) * SHOT_QUALITY).toInt().coerceAtMost(sw),
                        ((r.bottom - minT) * SHOT_QUALITY).toInt().coerceAtMost(sh)
                    )
                )
            }
            return
        }

        // 选一块空闲画板（后台还在模糊的那块不能动）；两块都忙就丢这一帧
        val board: Bitmap
        synchronized(blurLock) {
            val useA = !busyA
            if (!useA && busyB) return
            var b = if (useA) shotA else shotB
            if (b == null || b.width != sw || b.height != sh) {
                b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                if (useA) shotA = b else shotB = b
            }
            board = b
        }

        val savedAlphas = ArrayList<Pair<View, Float>>()
        val savedVis = ArrayList<Pair<View, Int>>()
        savedVis.add(topMask to topMask.visibility)
        topMask.visibility = View.INVISIBLE
        savedVis.add(topOverlayContent to topOverlayContent.visibility)
        topOverlayContent.visibility = View.INVISIBLE
        for (v in overlayViews) {
            savedVis.add(v to v.visibility)
            v.visibility = View.INVISIBLE
        }
        for (c in glassCards) {
            if (c === topMask) continue
            savedAlphas.add(c to c.alpha)
            c.alpha = 0f
        }
        val canvas = Canvas(board)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.scale(SHOT_QUALITY, SHOT_QUALITY)
        canvas.translate(-minL.toFloat(), -minT.toFloat())
        root.draw(canvas)
        for ((v, a) in savedAlphas) v.alpha = a
        for ((v, vis) in savedVis) v.visibility = vis

        // 每张卡片在截图里的区域（相对合并区域，按截图比例换算）
        for (i in visible.indices) {
            val r = regions[i]
            regions[i] = Rect(
                ((r.left - minL) * SHOT_QUALITY).toInt().coerceAtLeast(0),
                ((r.top - minT) * SHOT_QUALITY).toInt().coerceAtLeast(0),
                ((r.right - minL) * SHOT_QUALITY).toInt().coerceAtMost(sw),
                ((r.bottom - minT) * SHOT_QUALITY).toInt().coerceAtMost(sh)
            )
        }

        // 交给后台线程做模糊
        synchronized(blurLock) {
            pendingFrame = GlassFrame(board, visible, regions)
            blurLock.notifyAll()
        }
    }

    // 后台线程循环：拿最新一帧 → 逐卡片裁图模糊 → 回主线程换缓冲显示
    private fun blurLoop() {
        // CPU 模糊线程提高一点调度优先级，减少排队等待（仅 API 31 以下旧设备使用）
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (true) {
            val frame: GlassFrame
            synchronized(blurLock) {
                while (pendingFrame == null) {
                    try {
                        blurLock.wait()
                    } catch (e: InterruptedException) {
                        return
                    }
                }
                frame = pendingFrame!!
                pendingFrame = null
                if (frame.shot === shotA) busyA = true else busyB = true
            }
            for (i in frame.cards.indices) {
                val c = frame.cards[i]
                c.blurInBackground(frame.shot, frame.regions[i])
                mainHandler.post { c.commitBlur() }
            }
            synchronized(blurLock) {
                if (frame.shot === shotA) busyA = false else busyB = false
            }
        }
    }

    // 一帧待模糊的截图：画面 + 可见卡片 + 各卡片在截图里的区域
    private class GlassFrame(
        val shot: Bitmap,
        val cards: List<BlurCardView>,
        val regions: List<Rect>
    )

    // ===== 页面指示器：最左侧定位图标（第 0 页），右侧为新增城市的小圆点 =====
    private fun refreshIndicator() {
        val activity = activity as? MainActivity ?: return
        val cities = activity.cityList()
        val current = activity.currentPage()
        indicatorContainer.removeAllViews()

        // 定位图标：亮 = 当前页是定位页
        indicatorContainer.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_location)
            contentDescription = "定位"
            layoutParams = LinearLayout.LayoutParams(dp(10f), dp(10f)).apply {
                marginEnd = dp(2f)
            }
            alpha = if (current == 0) 1f else 0.35f
            setOnClickListener { activity.jumpTo(0) }
        })

        // 新增城市的小圆点：当前页白色，其余半透明
        for (i in 1 until cities.size) {
            val isCurrent = i == current
            indicatorContainer.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6f), dp(6f)).apply {
                    marginStart = dp(4f)
                    marginEnd = dp(4f)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isCurrent) Color.WHITE else Color.parseColor("#59FFFFFF"))
                }
                setOnClickListener { activity.jumpTo(i) }
            })
        }

        // 当前目标滚到可见位置
        indicatorScroll.post {
            val target = indicatorContainer.getChildAt(if (current == 0) 0 else current)
            if (target != null) {
                val x = target.left + target.width / 2 - indicatorScroll.width / 2
                indicatorScroll.smoothScrollTo(maxOf(0, x), 0)
            }
        }
    }

    // ===== 动态天气背景 + 状态栏配色 =====
    private fun applyBackground() {
        if (!isAdded) return
        val skycon = lastSkycon ?: return
        val night = currentNight(skycon)
        val (appearanceSkycon, appearanceNight) = WeatherAppearancePreview.resolve(skycon, night)
        applyDefaultWeatherCardTint(appearanceSkycon, appearanceNight)
        // 背景已全局共享在主页底层，这里只上报天气，由主页决定是否切换/淡入淡出。
        // 只有当前可见页才更新全局背景与状态栏，避免进入主页时多个城市同时刷新完成、
        // 各自上报背景导致背景互相覆盖闪烁；城市管理快照则每个城市都上报。
        val host = activity as? WeatherUiHost
        if (host != null) {
            if (isCurrentPage()) {
                host.hostWeatherBackground(appearanceSkycon, appearanceNight)
                // 状态栏文字/图标按其下方顶部背景色的实际对比度决定，不跟随应用主题。
                host.hostStatusBarStyle(
                    WeatherBackgroundPalette.statusBarUsesLightIcons(appearanceSkycon, appearanceNight)
                )
            }
            // 预览只覆盖当前屏幕的视觉层，城市快照仍保留真实天气，避免测试状态写入城市管理页。
            reportSnapshot(host, skycon, night)
        }
    }

    private fun applyDefaultWeatherCardTint(skycon: String?, night: Boolean) {
        if (glassCards.isEmpty() || !::topMask.isInitialized) return
        val tint = WeatherCardStyle.frostedTint(skycon, night)
        glassCards.forEach { card ->
            // 顶部地址蒙版必须无色；预警入口保持当前预警级别的实色填充。
            if (card !== topMask && card !== alertEntryCard && card !in externalContentMasks) {
                card.setFrostedMaterial(true)
                card.setTintWithWhiteBase(WeatherCardStyle.FROST_WHITE_BASE, tint, tint, WeatherCardStyle.needsIndependentTint(skycon))
            }
            if (card !== topMask && card !== alertEntryCard && card !in externalContentMasks) {
                card.setStrokeAlpha(WeatherCardStyle.edgeStrokeAlpha(night))
                card.setEnhancedEdgeStyle(true)
                card.setInnerGlowVisible(false)
                card.setWeatherCardEdgeStyle(
                    // 上下描边均为上一版的 150%：0.70 × 1.5 = 1.05。
                    topStrokeScale = 1.05f,
                    // 四边与圆角统一相同亮度和粗细。
                    sideStrokeAlpha = WeatherCardStyle.edgeStrokeAlpha(night),
                    // 底边仅保留与顶部同粗细、同亮度的描边，不再向卡片内部泛光。
                    bottomInsetGlow = false
                )
            }
        }
    }

    /** ViewPager 切换前由宿主调用，确保目标背景已经准备好而不是等待该页重新请求。 */
    fun reportCurrentAppearanceForPager() {
        val skycon = lastSkycon ?: return
        val night = currentNight(skycon)
        val (appearanceSkycon, appearanceNight) = WeatherAppearancePreview.resolve(skycon, night)
        (activity as? WeatherUiHost)?.hostWeatherBackground(appearanceSkycon, appearanceNight)
    }

    // 当前页是否正显示在 ViewPager 中（预览页/非主页宿主按当前页处理）
    private fun isCurrentPage(): Boolean {
        val main = activity as? MainActivity ?: return true
        val cur = main.cityList().getOrNull(main.currentPage()) ?: return false
        return page.name == cur.name && page.lng == cur.lng && page.lat == cur.lat
    }

    // 昼夜跟随实时日出/日落时间，而不是手机深浅色模式；
    // 日出日落数据尚未到位时退回彩云 skycon 的 NIGHT 标记
    private fun currentNight(skycon: String): Boolean {
        val now = System.currentTimeMillis()
        return if (sunriseMillis > 0L && sunsetMillis > 0L) {
            now < sunriseMillis || now >= sunsetMillis
        } else if (skycon.contains("NIGHT")) {
            true
        } else if (skycon.contains("DAY")) {
            false
        } else {
            // CLOUDY/RAIN 等夜间 skycon 不带 NIGHT 标记；城市管理返回重建页面、
            // 日出日落尚未解析时会误判成白天。这里沿用当前全局背景的昼夜状态兜底。
            (activity as? MainActivity)?.isBackgroundDark() ?: false
        }
    }

    // 上报本页城市快照：城市管理页据此在进入前就显示温度/天气/背景色
    private fun reportSnapshot(host: WeatherUiHost, skycon: String?, night: Boolean) {
        val temp = lastTemp?.let { WeatherSettings.tempText(requireContext(), it) }
        val desc = skycon?.let { WeatherText.skyconToText(it) }
        host.hostWeatherSnapshot(page.name, temp, desc, skycon, night)
    }

    // ===== 请求天气 =====
    private fun completeDataLoad(
        fromRefresh: Boolean,
        onComplete: ((Boolean) -> Unit)?,
        ok: Boolean
    ) {
        if (fromRefresh) finishRefreshTask(ok)
        if (onComplete != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onComplete(ok)
            } else {
                mainHandler.post { onComplete(ok) }
            }
        }
    }

    private fun resetSourceState() {
        ++fetchSeq;++metricHistoryFetchSeq;++metricFetchSeq;++alertRequestVersion;++calibrationFetchSeq
        lastServerTimeMillis=0;lastPageRefreshAt=0
        lastTemp=null;lastHigh=null;lastLow=null;lastFeelsLike=null;lastSkycon=null
        lastUvIndex=null;lastUvDesc=null;lastHumidity=null;lastPressureHpa=null;lastVisibilityKm=null
        lastWindDir=null;lastWindDirDeg=null;lastWindSpeedKmh=null;lastWindScale=null;lastPrecipMm=null
        sunriseMillis=0;sunsetMillis=0;yesterdaySunsetMillis=0;nextSunriseMillis=0
        caiyunV26Astro=emptyList();caiyunLifeAdvice=emptyList();lifeAdvice=emptyList()
        hourlyLabels=emptyList();hourlyTemps=emptyList();hourlySkycons=emptyList();hourlyProbs=emptyList()
        hourlyMetricPoints=emptyList();hourlyPast=emptyList();forecastSkyByHour=emptyMap()
        yesterdayMetricPoints=emptyList();historicalAirQualityPoints=emptyList();airQualityPoints=emptyList()
        currentAirQuality=null;airQualityTimeline=AirQualityTimeline.empty(AirQualitySource.UNKNOWN)
        historicalSupplementedKinds=emptySet();metricHistoryLoading=false;metricSupplementLoading=false
        primaryDaily=emptyList();openMeteoDaily=emptyList();dailySource=null
        clearAlerts();refreshAlertEntry();renderMinutely(emptyList(),emptyList(),null)
        tvTemp.text="--";tvDesc.text="正在获取所选天气源";tvHighLow.text="--"
        tvSunrise.text="--:--";tvSunset.text="--:--"
        hourlyChart.setData(emptyList(),emptyList(),emptyList(),emptyList())
        dailyListContainer.removeAllViews()
        dailyRows.clear()
        dailyChart.setData(emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        sunTrack.setInterval(0,0,false,System.currentTimeMillis())
        tvSunDuration.text="--";tvSunCountdownTime.text="--"
        updateDetailCards();renderAqiCard(null);renderLifeAdvice()
    }

    private fun restoreCachedWeather() {
        val context = context ?: return
        val caiyunStartup=if(WeatherSettings.useQWeather(context)) null else WeatherCache.loadCaiyunStartup(context,page)
        val freshCaiyun=caiyunStartup?.fresh
        val cached = if (WeatherSettings.useQWeather(context)) null else
            freshCaiyun?.takeIf { it.result?.realtime != null } ?: caiyunStartup?.display
        if (cached != null) {
            appliedSource = WeatherSettings.dataSource(context)
            cached.serverTime?.let { lastServerTimeMillis = it * 1000L }
            showWeather(cached.result)
            displayingOldSnapshot = freshCaiyun?.result?.realtime == null
            if (displayingOldSnapshot) {
                tvDesc.append(" · 上次天气")
                setStatusError("正在更新")
            }
            Log.i("WeatherApp", "Restored weather display snapshot")
            renderedDisplaySettings=displaySettingsKey()
            return
        }
        // 只有彩云整包失败后写入的和风降级快照才会走到这里；它不作为可选源呈现。
        val qweatherStartup=WeatherCache.loadQWeatherStartup(context,page)
        val freshQWeather=qweatherStartup?.fresh
        val fallback = freshQWeather?.takeIf { it.now != null }
            ?: qweatherStartup?.display
            ?: freshQWeather
            ?: return
        appliedSource = WeatherSettings.SOURCE_QWEATHER
        lastServerTimeMillis = fallback.updateTimeMillis
        if (fallback.now != null) showQWeather(fallback, loadSupplementalDaily = false)
        else showCachedQWeatherForecast(fallback)
        displayingOldSnapshot = !WeatherCachePolicy.volatileUsable(
            WeatherCachePolicy.observationAge(System.currentTimeMillis(), 0, fallback.now?.obsTimeMillis ?: fallback.updateTimeMillis))
        if (displayingOldSnapshot) { tvDesc.append(" · 上次天气"); setStatusError("正在更新") }
        if(fallback.now!=null || fallback.daily.isNotEmpty()) renderedDisplaySettings=displaySettingsKey()
    }

    /**
     * 实况过期后不再伪装成当前天气；仍有效的和风日预报/天文数据可在离线冷启动展示。
     * 网络恢复后完整响应会自然覆盖本段内容。
     */
    private fun showCachedQWeatherForecast(bundle: QWeatherBundle) {
        val days = bundle.daily
        if (days.isEmpty()) return
        primaryDaily = days.map {
            DailyForecast(
                date = it.date.take(10), skycon = it.skycon,
                high = it.high?.let { value -> Math.round(value).toInt() },
                low = it.low?.let { value -> Math.round(value).toInt() },
                precipProb = it.precipProb?.toInt()
            )
        }
        dailySource = mergedDaily()
        dailySource?.let(::renderDaily)
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(System.currentTimeMillis())
        val today = days.firstOrNull { it.date.take(10) == todayKey } ?: days.firstOrNull()
        today?.sunrise?.let { parseAstroTime(today.date, it) }?.let { sunriseMillis = it; tvSunrise.text = formatClock(it) }
        today?.sunset?.let { parseAstroTime(today.date, it) }?.let { sunsetMillis = it; tvSunset.text = formatClock(it) }
        if (sunriseMillis > 0L || sunsetMillis > 0L) updateSunCard()
        finishInitialLoading()
        showDataSourceFooter()
    }

    private fun fetchWeather(
        lng: Double,
        lat: Double,
        fromRefresh: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (BuildConfig.USER_CONFIGURABLE) {
            if(appliedSource!=WeatherSettings.dataSource(requireContext()) || appliedApiRevision!=UserApiConfiguration.revision(requireContext())) {
                resetSourceState();appliedSource=WeatherSettings.dataSource(requireContext())
            }
            appliedApiRevision = UserApiConfiguration.revision(requireContext())
            val config = UserApiConfiguration.read(requireContext())
            updateApiSetupNotice(false)
            if (config.effectiveSource() == WeatherSettings.SOURCE_QWEATHER) {
                fetchQWeather(lng, lat, fromRefresh, onComplete)
                return
            }
        }
        // 彩云 v2.7 主请求：实测 15 天、360 小时和 120 分钟降水均可用。
        val seq = ++fetchSeq
        val sourceKey = WeatherSettings.sourceKey(requireContext())
        caiyunV26Astro = emptyList()
        caiyunLifeAdvice = emptyList()
        if (!WeatherSettings.useOpenMeteo(requireContext())) fetchCaiyunV26Astronomy(lng, lat, seq)
        WeatherDataRepository.enqueueCaiyunWeather(requireContext(), lng, lat, 15, 360) { response ->
                    if (!isAdded) {
                        completeDataLoad(fromRefresh, onComplete, false)
                        return@enqueueCaiyunWeather
                    }
                    // 定位刷新后会用新坐标再请求一次，旧响应直接作废，不算失败
                    if (seq != fetchSeq || sourceKey != WeatherSettings.sourceKey(requireContext())) {
                        completeDataLoad(fromRefresh, onComplete, true)
                        return@enqueueCaiyunWeather
                    }
                    val body = response.body
                    if (response.isSuccessful && body?.result?.realtime?.temperature?.isFinite() == true &&
                        WeatherRefreshPolicy.canCommitObservation(System.currentTimeMillis(), body.serverTime?.times(1000), lastServerTimeMillis)) {
                        appliedSource = WeatherSettings.dataSource(requireContext())
                        displayingOldSnapshot = false
                        lastPageRefreshAt = System.currentTimeMillis()
                        // 记录数据发布时间（秒 → 毫秒），刷新成功时显示"数据发布于 X 分钟前"
                        body.serverTime?.let { lastServerTimeMillis = it * 1000L }
                        if (fromRefresh) animateWeatherDataSwap()
                        WeatherCache.saveCaiyun(requireContext(), page, body)
                        showWeather(body.result)
                        // 只在彩云已有数据成功后启动和风字段补齐，避免补充源抢先渲染主页面。
                        if (!WeatherSettings.useOpenMeteo(requireContext())) fetchQWeatherSupplement(lng, lat, seq)
                        completeDataLoad(fromRefresh, onComplete, true)
                    } else {
                        setStatusError(if(WeatherSettings.useOpenMeteo(requireContext())) "Open-Meteo 暂不可用，请稍后重试" else "彩云请求失败，请检查接口配置与网络")
                        finishInitialLoading()
                        completeDataLoad(fromRefresh, onComplete, false)
                    }
                }
    }

    /** 和风仅按字段补齐：空气质量缺失污染物与彩云未覆盖的生活指数。 */
    private fun fetchQWeatherSupplement(lng: Double, lat: Double, seq: Int) {
        val appContext = requireContext().applicationContext
        if (BuildConfig.USER_CONFIGURABLE && !UserApiConfiguration.read(appContext).hasQWeather()) return
        val sourceKey=WeatherSettings.sourceKey(appContext)
        Thread {
            val advice = QWeatherApi.fetchLifeAdvice(appContext, lat, lng)
            activity?.runOnUiThread {
                if (isAdded && seq == fetchSeq && sourceKey==WeatherSettings.sourceKey(appContext) && advice.isNotEmpty()) {
                    lifeAdvice = LifeAdviceFactory.fromQWeather(advice)
                    renderLifeAdvice()
                }
            }
            val bundle = runCatching { QWeatherApi.fetchAll(appContext, lat, lng) }.getOrNull()
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (!isAdded || seq != fetchSeq || sourceKey!=WeatherSettings.sourceKey(appContext) || bundle == null) return@runOnUiThread
                val qAdvice = LifeAdviceFactory.fromQWeather(bundle.lifeAdvice)
                if (qAdvice.isNotEmpty()) lifeAdvice = qAdvice
                renderLifeAdvice()
                if (airQualityTimeline.source == AirQualitySource.CAIYUN) {
                    applyAirQualityTimeline(
                        AirQualityTimelineFactory.mergeCaiyunWithQWeather(
                            airQualityTimeline, AirQualityTimelineFactory.qweather(bundle)
                        )
                    )
                }
            }
        }.start()
    }

    /**
     * 彩云 v2.6 的 /daily 专职提供今天、昨天、明天的日出日落；详情页的其他太阳阶段
     * 均由本地算法推算。请求不参与首页主数据的成功判定，失败时安全降级到 v2.7 已有 astro。
     */
    private fun fetchCaiyunV26Astronomy(lng: Double, lat: Double, seq: Int) {
        val sourceKey=WeatherSettings.sourceKey(requireContext())
        WeatherDataRepository.enqueueCaiyunAstronomy(requireContext(), lng, lat) { response ->
                    if (!isAdded || seq != fetchSeq || sourceKey!=WeatherSettings.sourceKey(requireContext())) {
                        return@enqueueCaiyunAstronomy
                    }
                    val astro = response.body?.result?.daily?.astro.orEmpty()
                    if (response.isSuccessful && astro.isNotEmpty()) {
                        caiyunV26Astro = astro
                        applyCaiyunSunAstronomy(astro)
                    }
                }
    }

    private fun sunDataSourceName(): String = when(WeatherSettings.dataSource(requireContext())) {
        WeatherSettings.SOURCE_OPEN_METEO -> "Open-Meteo · 模型数据/本地计算"
        WeatherSettings.SOURCE_QWEATHER -> "和风天气"
        else -> if (caiyunV26Astro.isNotEmpty()) "彩云天气 v2.6" else "彩云天气"
    }

    // ===== 和风天气数据源 =====
    private fun fetchQWeather(
        lng: Double,
        lat: Double,
        fromRefresh: Boolean,
        onComplete: ((Boolean) -> Unit)?
    ) {
        val seq = ++fetchSeq
        val sourceKey=WeatherSettings.sourceKey(requireContext())
        Thread {
            val bundle = try {
                QWeatherApi.fetchAll(requireContext(), lat, lng)
            } catch (e: Exception) {
                null
            }
            val act = activity
            if (act == null) {
                completeDataLoad(fromRefresh, onComplete, false)
                return@Thread
            }
            act.runOnUiThread {
                if (!isAdded) {
                    completeDataLoad(fromRefresh, onComplete, false)
                    return@runOnUiThread
                }
                if (seq != fetchSeq || sourceKey!=WeatherSettings.sourceKey(requireContext())) {
                    completeDataLoad(fromRefresh, onComplete, true)
                    return@runOnUiThread
                }
                if (bundle == null || bundle.now == null || !bundle.now.temp.isFinite() ||
                    !WeatherRefreshPolicy.canCommitObservation(System.currentTimeMillis(), bundle.now.obsTimeMillis, lastServerTimeMillis)) {
                    Log.w("WeatherApp", "QWeather request did not produce a current observation")
                    setStatusError(
                        bundle?.error
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "和风天气请求失败：$it" }
                            ?: "和风天气请求失败：请检查共享天气服务与网络"
                    )
                    completeDataLoad(fromRefresh, onComplete, false)
                    return@runOnUiThread
                }
                Log.i("WeatherApp", "QWeather observation committed")
                appliedSource = WeatherSettings.SOURCE_QWEATHER
                displayingOldSnapshot = false
                lastPageRefreshAt = System.currentTimeMillis()
                lastServerTimeMillis = bundle.updateTimeMillis
                if (fromRefresh) animateWeatherDataSwap()
                WeatherCache.saveQWeather(requireContext(), page, bundle)
                showQWeather(bundle)
                completeDataLoad(fromRefresh, onComplete, true)
            }
        }.start()
    }

    private fun showQWeather(
        b: QWeatherBundle,
        loadSupplementalDaily: Boolean = true
    ) {
        val now = b.now ?: return
        finishInitialLoading()
        val c = requireContext()
        lastSkycon = now.skycon
        lastTemp = now.temp
        lastWindDir = now.windDir
        lastWindDirDeg = now.windDirDeg
        lastWindSpeedKmh = now.windSpeedKmh
        lastWindScale = now.windScale
        lastFeelsLike = now.feelsLike
        // 和风：紫外线来自 v7 天气指数接口（type=5）
        lastUvIndex = b.uv?.level
        lastUvDesc = b.uv?.category
        // 和风湿度为百分比；若个别字段返回 0~1 小数也统一换算
        lastHumidity = now.humidity.let { if (it <= 1.0) it * 100.0 else it }
        lastPressureHpa = now.pressureHpa
        lastVisibilityKm = now.visibilityKm
        lastPrecipMm = now.precip
        applyBackground()
        tvTemp.text = WeatherSettings.tempText(c, now.temp)
        tvDesc.text = WeatherText.skyconToText(now.skycon)
        updateDetailCards()
        // 生活建议完全采用和风 indices 的原始条目；不再以本地温湿风规则推算九项。
        lifeAdvice = LifeAdviceFactory.fromQWeather(b.lifeAdvice.ifEmpty {
            LifeAdviceCache.load(requireContext(), page.lat, page.lng)
        })
        renderLifeAdvice()

        // 空气质量必须从和风实时 + 和风原生逐小时预报构建同一条时间线。
        // 不再以 Open-Meteo 趋势替换已选天气源的图表。
        applyAirQualityTimeline(AirQualityTimelineFactory.qweather(b))

        // 小时预报统一以“当前整点”为第一列。和风常从下一整点开始返回，
        // 若直接拿它的首项作为当前列，就会出现首页实时温度是 13 点、图表却高亮 14 点的错位。
        // 当前列永远采用同一份实时快照；到真实时间跨过下一整点后才切换到下一列。
        if (b.hourly.isNotEmpty()) {
            val currentHour = currentHourMillis()
            val currentForecast = b.hourly.firstOrNull {
                hourStartMillis(it.timeMillis) == currentHour
            }
            val future = b.hourly
                .filter { hourStartMillis(it.timeMillis) > currentHour }
                .sortedBy { it.timeMillis }
            hourlyLabels = listOf(formatClock(currentHour)) + future.map { formatClock(it.timeMillis) }
            hourlyTemps = listOf(now.temp) + future.map { it.temp }
            hourlySkycons = listOf(now.skycon) + future.map { it.skycon }
            hourlyProbs = listOf(currentForecast?.pop ?: 0.0) + future.map { it.pop }
            hourlyRefMillis = currentHour
            forecastSkyByHour = (listOf(currentHour / 3_600_000L to now.skycon) +
                future.map { it.timeMillis / 3_600_000L to it.skycon }).toMap()
            val currentFallback = currentForecast?.let {
                HourlyMetricPoint(
                    timeMillis = it.timeMillis,
                    temperature = it.temp,
                    feelsLike = it.feelsLike,
                    precipitationProbability = it.pop,
                    uv = it.uvIndex,
                    windKmh = it.windSpeedKmh,
                    pressureHpa = it.pressureHpa,
                    visibilityKm = it.visibilityKm,
                    humidityPercent = it.humidityPercent
                )
            }
            hourlyMetricPoints = listOf(realtimeMetricPoint(currentHour, currentFallback)) + future.map {
                HourlyMetricPoint(
                    timeMillis = it.timeMillis,
                    temperature = it.temp,
                    feelsLike = it.feelsLike,
                    precipitationProbability = it.pop,
                    uv = it.uvIndex,
                    windKmh = it.windSpeedKmh,
                    pressureHpa = it.pressureHpa,
                    visibilityKm = it.visibilityKm,
                    humidityPercent = it.humidityPercent
                )
            }
            renderHourlyChart()
        }

        // 高低温 + 日出/日落独立列（按日期匹配今天，避免历史列污染）
        val days = b.daily
        val todayKey2 =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(System.currentTimeMillis())
        val today = days.firstOrNull { it.date.take(10) == todayKey2 } ?: days.firstOrNull()
        lastHigh = today?.high
        lastLow = today?.low
        if (today != null) {
            tvHighLow.text =
                "最高 ${WeatherSettings.tempText(c, today.high)}   最低 ${WeatherSettings.tempText(c, today.low)}"
        }
        val ref = if (hourlyRefMillis != 0L) hourlyRefMillis else System.currentTimeMillis()
        val events = ArrayList<SunPoint>()
        for (d in days) {
            d.sunrise?.let { t ->
                parseAstroTime(d.date, t)?.let { m ->
                    val h = (m - ref) / 3600000f
                    if (h in -1f..24f) events.add(SunPoint(h, formatClock(m), true))
                }
            }
            d.sunset?.let { t ->
                parseAstroTime(d.date, t)?.let { m ->
                    val h = (m - ref) / 3600000f
                    if (h in -1f..24f) events.add(SunPoint(h, formatClock(m), false))
                }
            }
        }
        events.sortBy { it.hoursFromNow }
        val yesterdayAstro = days.firstOrNull { it.date.take(10) < todayKey2 }
        val tomorrow = days.firstOrNull { it.date.take(10) > todayKey2 }
        today?.sunrise?.let { t ->
            parseAstroTime(today.date, t)?.let { m ->
                sunriseMillis = m
                tvSunrise.text = formatClock(m)
            }
        }
        today?.sunset?.let { t ->
            parseAstroTime(today.date, t)?.let { m ->
                sunsetMillis = m
                tvSunset.text = formatClock(m)
            }
        }
        // 昨天日落缺失时用“今天日落 - 24h”兜底，保证日出前进度条不是 0
        yesterdaySunsetMillis = yesterdayAstro?.sunset?.let {
            parseAstroTime(yesterdayAstro.date, it)
        } ?: (sunsetMillis - 24L * 3600000L)
        nextSunriseMillis = tomorrow?.sunrise?.let { parseAstroTime(tomorrow.date, it) }
            ?: (sunriseMillis + 24L * 3600000L)
        updateSunCard()
        // 日出日落到位后重算昼夜并重新上报，避免 applyBackground 先于天文时间执行导致的白天误判
        applyBackground()
        hourlyChart.setSunEvents(events)

        // 仅用于彩云整体失败的和风降级路径，不再拼接第三天气源。
        if (days.isNotEmpty()) {
            primaryDaily = days.map {
                DailyForecast(
                    date = it.date.take(10),
                    skycon = it.skycon,
                    high = it.high?.let { v -> Math.round(v).toInt() },
                    low = it.low?.let { v -> Math.round(v).toInt() },
                    precipProb = it.precipProb?.toInt()
                )
            }
            dailySource = mergedDaily()
            renderDaily(dailySource!!)
        }

        // 分钟级降水：无雨或接口不可用时隐藏卡片
        renderMinutely(
            b.minutely.map { it.precip },
            b.minutely.map { it.timeMillis },
            b.minutelySummary,
            mmPerHourScale = 12.0 // 和风 minutely.precip 是 5 分钟累计 mm，×12 = mm/h
        )
        // 灾害预警：和风 v1 预警接口，跳过取消/已过期预警，并按简化后的名称去重
        clearAlerts()
        val alertNow = System.currentTimeMillis()
        val seenAlertNames = HashSet<String>()
        for (a in b.alerts) {
            if (a.messageType == "cancel" || a.expireTimeMillis <= alertNow) continue
            if (AlertVisibilityPolicy.isAgriculturalAlert(
                    a.eventTypeName, a.headline, a.senderName
                )) continue
            val name = alertShortName(a)
            val nameKey = normalizeAlertKey(name)
            if (nameKey.isEmpty() || !seenAlertNames.add(nameKey)) continue
            val desc = buildString {
                append(a.description.ifBlank { a.headline }.ifBlank { "暂无详细预警内容" })
                if (a.instruction.isNotBlank()) {
                    append("\n防御指南：").append(a.instruction)
                }
            }
            alertTitles.add(name)
            alertLevels.add(alertLevelName(a))
            alertContents.add(desc)
            alertTimes.add(formatAlertTime(a.issuedTimeMillis))
            alertCodes.add(a.eventTypeCode)
            alertLocations.add(if (page.isCurrentLocation) page.locationDetail else page.name)
            alertSources.add(a.senderName)
            alertColors.add(alertColorOf(a))
        }
        refreshAlertEntry()
        fetchPressureTrend()
        showDataSourceFooter()
    }

    // ===== 分钟级降雨卡片 =====
    private fun renderMinutely(
        precip: List<Double>?,
        times: List<Long>?,
        summary: String?,
        mmPerHourScale: Double = 1.0
    ) {
        val raw = precip.orEmpty().map { it.coerceAtLeast(0.0) }
        val rawTimes = times.orEmpty()
        // 图表窗口严格为“现在 → 满 2 小时”，超出部分（如彩云 10 分钟间隔数据）裁掉，
        // 保证底部坐标轴最右侧的满 2 小时时刻与数据范围一致
        val t0 = rawTimes.firstOrNull() ?: System.currentTimeMillis()
        val twoHours = 2L * 60 * 60 * 1000L
        val values = ArrayList<Double>()
        val clippedTimes = ArrayList<Long>()
        for (i in raw.indices) {
            val t = rawTimes.getOrNull(i) ?: (t0 + i * 5L * 60_000L)
            if (t - t0 <= twoHours) {
                values.add(raw[i])
                clippedTimes.add(t)
            }
        }
        // 统一换算成 mm/h：和风 5 分钟累计 mm ×12；彩云 precipitation_2h 本身就是 mm/h
        val scaled = values.map { it * mmPerHourScale }
        val hasRain = values.any { it > 0.0 }
        Log.i("WeatherApp", "Minutely precipitation display updated")
        if (!hasRain) {
            minutelyCard.visibility = View.GONE
            return
        }
        minutelyCard.visibility = View.VISIBLE
        minutelyChart.setPrecip(scaled, clippedTimes)
        val peakIdx = scaled.indices.maxByOrNull { scaled[it] } ?: 0
        val peakValue = scaled.getOrNull(peakIdx) ?: 0.0
        val peakCat = rainCategory(peakValue)
        val peakMillis = clippedTimes.getOrNull(peakIdx) ?: System.currentTimeMillis()
        tvMinutelySummary.text = buildMinutelySummary(scaled, clippedTimes, peakCat)
        tvMinutelyPeak.text = buildPeakText(peakMillis, peakValue, peakCat)
        // 这里只渲染卡片。降雨通知仅由 WeatherRiskWorker 经事件台账决定，
        // 因此打开页面、下拉刷新都不会再次弹出系统通知。
        // 卡片出现后重新校正弹性空白，保持 48 小时卡片底端对齐屏幕底
        view?.post { if (isAdded) alignBottom() }
    }

    // 根据未来两小时分钟级降雨数据生成一行状态说明：当前雨势、变化速度和中途停雨都会优先交代。
    private fun buildMinutelySummary(
        values: List<Double>,
        times: List<Long>,
        peakCat: String
    ): String = WeatherRainPresentation.summary(values, times, peakCat)

    // 底部最大雨势文案：明确大致时刻、对应级别和出行建议，保持为一行。
    private fun buildPeakText(peakMillis: Long, peakValue: Double, peakCat: String): String =
        WeatherRainPresentation.peakText(peakMillis, peakValue, peakCat)

    // 按毫米/小时划分降雨强度（参考国内气象标准）
    private fun rainCategory(mmPerHour: Double): String = WeatherRainPresentation.category(mmPerHour)

    // ===== 底部数据来源：随页面内容自然上推出现，不再到达底部后独立淡入 =====
    private fun showDataSourceFooter() {
        if (!::ivSourceLogo.isInitialized) return
        if (BuildConfig.USER_CONFIGURABLE) {
            ivSourceLogo.visibility=View.GONE
            ivSourceSupplementLogo.visibility=View.GONE
            tvStatus.text=when(WeatherSettings.dataSource(requireContext())) {
                WeatherSettings.SOURCE_OPEN_METEO -> "Open-Meteo · 模型天气数据（CC BY 4.0）"
                WeatherSettings.SOURCE_QWEATHER -> "数据来自和风天气"
                else -> if(UserApiConfiguration.read(requireContext()).hasQWeather()) "数据来自彩云天气、和风天气" else "数据来自彩云天气"
            }
            tvStatus.visibility=View.VISIBLE
            tvStatus.setOnClickListener { if(WeatherSettings.useOpenMeteo(requireContext()))
                startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://open-meteo.com/"))) }
            tvStatusTime.text="更新于${formatClock(lastServerTimeMillis)}"
            tvStatusTime.visibility=View.VISIBLE
            bottomStatusContainer.visibility=View.VISIBLE;bottomStatusContainer.alpha=1f
            return
        }
        // 使用公开展示的官方字标位图，而不是以系统字体拼写品牌名称。
        ivSourceLogo.setImageResource(R.drawable.brand_caiyun_weather_compact)
        ivSourceLogo.contentDescription = "彩云天气"
        ivSourceLogo.visibility = View.VISIBLE
        ivSourceSupplementLogo.setImageResource(R.drawable.brand_qweather_weather_trimmed)
        ivSourceSupplementLogo.contentDescription = "和风天气"
        ivSourceSupplementLogo.visibility = View.VISIBLE
        tvStatus.text = "提供数据支持"
        tvStatus.visibility = View.VISIBLE
        WeatherSourceAttribution.bind(ivSourceLogo,ivSourceSupplementLogo,tvStatus)
        tvStatusTime.text = "更新于${formatClock(lastServerTimeMillis)}"
        tvStatusTime.visibility = View.VISIBLE
        bottomStatusContainer.animate().cancel()
        bottomStatusContainer.alpha = 1f
        bottomStatusContainer.visibility = View.VISIBLE
    }

    /**
     * 底部来源信息本身是滚动内容，会随页面上推自然进入视野。背景插画同步使用
     * 最后一段滚动距离的进度，以位移出现，避免此前“内容已到位而背景另行淡入”的割裂感。
     */
    private fun updateBottomStatusVisibility(
        scroll: ScrollView,
        scrollY: Int,
        animate: Boolean = true
    ) {
        // ViewPager/配置变化可能让 post 的这一帧晚于 Fragment detach；此时不能再读取 resources。
        if (!isAdded || !::bottomStatusContainer.isInitialized) return
        val content = scroll.getChildAt(0) ?: return
        val remaining = (content.height - (scrollY + scroll.height)).coerceAtLeast(0)
        val revealRange = dp(140f).coerceAtLeast(1)
        val reveal = (1f - remaining.toFloat() / revealRange).coerceIn(0f, 1f)
        if (isCurrentPage()) {
            (activity as? WeatherUiHost)?.hostBackgroundFooterProgress(reveal)
        }
    }

    private fun updateApiSetupNotice(missing: Boolean) {
        val scroll = view?.findViewById<ScrollView>(R.id.weatherRoot) ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        var notice = content.findViewById<View>(R.id.apiSetupNotice)
        if (notice == null && missing) {
            notice = layoutInflater.inflate(R.layout.api_setup_notice, content, false)
            content.addView(notice, 0)
            notice.findViewById<View>(R.id.btnSetupApi).setOnClickListener {
                startActivity(Intent(requireContext(), ApiConfigurationActivity::class.java))
            }
        }
        notice?.visibility = if (missing) View.VISIBLE else View.GONE
    }

    private fun setStatusError(msg: String) {
        if (::ivSourceLogo.isInitialized) ivSourceLogo.visibility = View.GONE
        if (::ivSourceSupplementLogo.isInitialized) ivSourceSupplementLogo.visibility = View.GONE
        if (::tvStatusTime.isInitialized) tvStatusTime.visibility = View.GONE
        tvStatus.text = if (displayingOldSnapshot) "上次天气 · $msg" else msg
        tvStatus.visibility = View.VISIBLE
        // 无快照且请求失败时仅显示状态文字；透明骨架已移除，用户仍可直接下拉刷新。
        if (hasRenderableWeatherData()) finishInitialLoading()
    }

    /** 首个缓存或网络结果到达后再启动玻璃刷新，避免冷启动与截图循环争夺首帧时间。 */
    private fun finishInitialLoading() {
        if (!::weatherLoadingOverlay.isInitialized) return
        weatherLoadingOverlay.hideLoading()
    }

    private fun hasRenderableWeatherData(): Boolean =
        lastTemp != null || lastSkycon != null || hourlyLabels.isNotEmpty() || dailySource != null

    /** 旧内容保持在屏幕上，网络结果提交时只淡入新的文字和图表，不闪白或重置滚动位置。 */
    private fun animateWeatherDataSwap() {
        val content = view?.findViewById<View>(R.id.weatherRoot) ?: return
        if (!hasRenderableWeatherData()) return
        content.animate().cancel()
        content.alpha = 0.72f
        content.animate().alpha(1f).setDuration(220L).setInterpolator(DecelerateInterpolator()).start()
    }

    // 后台拉取气压变化趋势（hPa/小时），成功后更新气压仪表盘箭头
    private fun fetchPressureTrend() {
        val current = hourlyMetricPoints.firstOrNull()?.pressureHpa ?: lastPressureHpa
        val next = hourlyMetricPoints.drop(1).firstOrNull { it.pressureHpa != null }?.pressureHpa
        val trend = if (current != null && next != null) next - current else null
        lastPressureTrend = trend
        if (::pressureGauge.isInitialized) pressureGauge.setTrend(trend)
    }

    private fun renderLifeAdvice() {
        if (!::lifeAdviceGrid.isInitialized) return
        lifeAdviceGrid.setAdvice(HomeLifeAdvice.select(lifeAdvice))
    }

    // 实时详情卡片：风力 / 湿度 / 气压 / 能见度（按设置单位显示）
    private fun updateDetailCards() {
        val c = requireContext()
        windCompass.setData(lastWindDirDeg)
        // 罗盘中心与外层风力卡使用相同的实时背景截图、磨砂强度和填充层。
        // 在首帧（背景尚未走完 applyDefaultWeatherCardTint）也先建立同一材质，避免
        // 中央圆片短暂显示成一块更浅、更硬的纯色。
        val windNight = lastSkycon?.let(::currentNight) ?: WeatherSettings.isDarkTheme(c)
        val windTint = WeatherCardStyle.frostedTint(lastSkycon, windNight)
        windLevelCard.setFrostedMaterial(true)
        windLevelCard.setTintWithWhiteBase(
            WeatherCardStyle.FROST_WHITE_BASE, windTint, windTint, WeatherCardStyle.needsIndependentTint(lastSkycon)
        )
        windLevelCard.setCornerRadius(dp(17f).toFloat())
        windLevelCard.setStrokeVisible(false)
        // 中央风级蒙版与父卡使用同一层级的实时背景模糊；不再出现更浅、更硬的圆形底。
        windLevelCard.setBlurRadius(dp(WeatherCardStyle.FROST_BLUR_DP).toFloat())
        tvWindLevel.text = WeatherSettings.windLevel(c, lastWindSpeedKmh, lastWindScale)
        // 罗盘下方显示具体风向 + 风速，例如 "东南风 12km/h"（按设置单位换算）
        val windDesc = WeatherSettings.windText(c, lastWindDir, lastWindSpeedKmh, lastWindScale)
        tvWindDesc.text = if (lastWindSpeedKmh == null && lastWindDir.isNullOrBlank()) {
            windFeelText(null)
        } else {
            windDesc
        }
        tvHumidityValue.text = lastHumidity?.let { "${it.toInt()}%" } ?: "--"
        tvHumidityDesc.text = humidityFeelText(lastHumidity)
        val dewC = dewPointC(lastTemp, lastHumidity)
        if (dewC != null) {
            tvHumidityDew.visibility = View.VISIBLE
            tvHumidityDew.text = "露点温度 ${WeatherSettings.tempText(c, dewC)}"
        } else {
            tvHumidityDew.visibility = View.GONE
        }
        val pressureUnit = when (WeatherSettings.pressureUnit(c)) {
            WeatherSettings.PRESSURE_MMHG -> "毫米汞柱"
            WeatherSettings.PRESSURE_INHG -> "英寸汞柱"
            else -> "百帕"
        }
        pressureGauge.setPressure(lastPressureHpa, pressureUnit, lastPressureTrend)
        tvVisibilityValue.text = WeatherSettings.visibilityValue(c, lastVisibilityKm)
        tvVisibilityDesc.text = visibilityFeelText(lastVisibilityKm)
        tvFeelsValue.text = WeatherSettings.tempText(c, lastFeelsLike)
        feelsDelta.setTemperatures(
            WeatherSettings.tempDisplay(c, lastTemp),
            WeatherSettings.tempDisplay(c, lastFeelsLike),
            WeatherSettings.tempText(c, lastTemp)
        )
        tvFeelsDesc.text = feelsDescText(lastFeelsLike)
        tvUvValue.text = lastUvIndex ?: "--"
        tvUvDesc.text = uvDescText(lastUvIndex, lastUvDesc)
        uvBar.setUv(lastUvIndex?.toDoubleOrNull())
        playPendingIndicatorEntryAnimation()
    }

    private fun feelsDescText(feels: Double?): String =
        FeelsLikeComfortPolicy.forCelsius(feels).advice

    private fun uvDescText(index: String?, desc: String?): String {
        val i = index?.toIntOrNull()
        return when {
            i == null -> desc ?: "暂无紫外线数据"
            i <= 2 -> "紫外线弱，无需特别防护"
            i <= 5 -> "紫外线中等，出门注意防晒"
            i <= 7 -> "紫外线强，外出做好防晒"
            i <= 10 -> "紫外线很强，尽量避免暴晒"
            else -> "紫外线极强，注意防护"
        }
    }

    // Magnus 公式估算露点温度
    private fun dewPointC(tempC: Double?, humidityPct: Double?): Double? {
        if (tempC == null || humidityPct == null) return null
        val rh = humidityPct.coerceIn(1.0, 100.0)
        val a = 17.62
        val b = 243.12
        val gamma = Math.log(rh / 100.0) + (a * tempC) / (b + tempC)
        return b * gamma / (a - gamma)
    }

    private fun windFeelText(kmh: Double?): String = when {
        kmh == null -> "暂无风力数据"
        kmh < 1 -> "风平浪静，适合静享时光"
        kmh < 6 -> "微风拂面，出门很舒服"
        kmh < 12 -> "和风轻送，散步正合适"
        kmh < 20 -> "清风相伴，出行心情好"
        kmh < 29 -> "风力稍强，记得添件外套"
        kmh < 39 -> "风比较大，出门注意防风"
        kmh < 50 -> "大风天气，尽量减少外出"
        else -> "狂风来袭，注意安全，非必要不出门"
    }

    private fun humidityFeelText(pct: Double?): String = when {
        pct == null -> "暂无湿度数据"
        pct < 30 -> "空气偏干，记得多喝水"
        pct <= 60 -> "湿度舒适，体感刚刚好"
        pct <= 80 -> "空气略潮，注意通风"
        else -> "比较潮湿，记得防潮"
    }

    private fun visibilityFeelText(km: Double?): String = when {
        km == null -> "暂无能见度数据"
        km >= 20 -> "视野极佳，远处的风景都清晰可见"
        km >= 10 -> "视野良好，出行顺畅又安心"
        km >= 4 -> "能见度一般，开车慢一点更稳妥"
        km >= 1 -> "能见度较差，出行注意安全"
        else -> "有雾，出门千万注意安全"
    }

    // 日出日落卡片：白天 日出→日落；日出前 昨天日落→今天日出；日落后 今天日落→次日日出
    private fun updateSunCard() {
        if (!::tvSunCountdownLabel.isInitialized) return
        val now = System.currentTimeMillis()
        val state = SunCardStateCalculator.calculate(
            sunriseMillis, sunsetMillis, yesterdaySunsetMillis, nextSunriseMillis, now
        ) ?: return
        tvSunLeftLabel.text = if (state.leftIsSunrise) "日出" else "日落"
        tvSunRightLabel.text = if (state.rightIsSunrise) "日出" else "日落"
        ivSunLeftIcon.setImageBitmap(
            SunIconAssets.load(requireContext(), state.leftIsSunrise, dp(26f), dp(26f))
        )
        ivSunRightIcon.setImageBitmap(
            SunIconAssets.load(requireContext(), state.rightIsSunrise, dp(26f), dp(26f))
        )
        ivSunLeftIcon.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        ivSunRightIcon.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        tvSunrise.text = formatClock(state.leftTimeMillis)
        tvSunset.text = formatClock(state.rightTimeMillis)
        val countdown = if (state.countdownTargetMillis > now) {
            val diff = state.countdownTargetMillis - now
            val h = diff / 3600000L
            val m = (diff % 3600000L) / 60000L
            String.format(Locale.US, "%02d:%02d", h, m)
        } else {
            ""
        }
        tvSunCountdownLabel.text = if (state.isDaytime) "距离日落还有" else "距离日出还有"
        tvSunCountdownTime.text = countdown
        sunTrack.setInterval(
            state.leftTimeMillis, state.rightTimeMillis, state.isDaytime, now
        )
        playPendingIndicatorEntryAnimation()
        val daylightHours = state.daylightMillis / 3_600_000L
        val daylightMinutes = (state.daylightMillis % 3_600_000L) / 60_000L
        tvSunDuration.text = "日照时长 ${daylightHours}小时${daylightMinutes}分钟"
    }

    // 地址栏显示文本：当前定位显示详细地址；其他城市名补全“市”字
    private fun cityDisplayName(): String {
        return WeatherAddressFormatter.label(page)
    }

    // 彩云风速方向是 0~360 度，转成中文风向
    private fun windDirText(deg: Double?): String? {
        if (deg == null) return null
        val dirs = arrayOf("北风", "东北风", "东风", "东南风", "南风", "西南风", "西风", "西北风")
        return dirs[((deg + 22.5) / 45.0).toInt() % 8]
    }

    // 设置页返回后按最新单位重绘当前数据；数据源变化则由 onResume 重新拉取
    private fun reapplyUnits() {
        if (!isAdded) return
        val c = requireContext()
        tvTemp.text = WeatherSettings.tempText(c, lastTemp)
        if (lastHigh != null || lastLow != null) {
            tvHighLow.text =
                "最高 ${WeatherSettings.tempText(c, lastHigh)}   最低 ${WeatherSettings.tempText(c, lastLow)}"
        }
        updateDetailCards()
        renderLifeAdvice()
        renderHourlyChart()
        dailySource?.let { renderDaily(it) }
        renderedDisplaySettings=displaySettingsKey()
    }

    // ===== 把数据显示到界面 =====
    private fun showWeather(result: Result?) {
        if (result == null) {
            setStatusError("数据为空")
            return
        }
        result.realtime?.let { showRealtime(it) }
        result.hourly?.let { showHourly(it) }
        result.daily?.let { showDaily(it) }
        // 彩云详情图表使用同一份 result.hourly.air_quality；实时快照仅覆盖时间线的“现在”。
        val timeline=AirQualityTimelineFactory.caiyun(result,lastServerTimeMillis.takeIf {it>0} ?: System.currentTimeMillis())
        if(WeatherSettings.useOpenMeteo(requireContext())) {
            historicalAirQualityPoints=timeline.hourly.filter {it.timeMillis<currentHourMillis()}.map {it.copy(source=AirQualitySource.OPEN_METEO)}
            applyAirQualityTimeline(timeline.copy(source=AirQualitySource.OPEN_METEO,
                realtime=timeline.realtime?.copy(source=AirQualitySource.OPEN_METEO),
                hourly=timeline.hourly.filter {it.timeMillis>=currentHourMillis()}.map {it.copy(source=AirQualitySource.OPEN_METEO)}))
            tvAqiDesc.text="模型污染物参考估算，非监测站实测"
        } else applyAirQualityTimeline(timeline)
        // 仅在至少有一类可展示天气数据时退出加载态；接口返回空对象时继续显示准确骨架。
        if (hasRenderableWeatherData()) finishInitialLoading()
        // 预警独立使用和风；彩云缓存及响应中的预警一律不进入界面。
        if(WeatherSettings.useOpenMeteo(requireContext())) { clearAlerts();refreshAlertEntry() }
        else supplementAlertsFromQWeather(emptySet()) { refreshAlertEntry() }
        // 分钟级降水：彩云免费套餐 minutely 可能未返回，为空时隐藏卡片
        val m = result.minutely
        val minutelyValues = m?.precipitation2h ?: m?.precipitation
        val nowMillis = System.currentTimeMillis()
        val minutelyTimes = minutelyValues?.mapIndexed { i, _ ->
            // 彩云 v2.6 precipitation_2h 为未来两小时共 120 个逐分钟值。
            nowMillis + i * 60_000L
        }
        renderMinutely(minutelyValues, minutelyTimes, m?.description)
        fetchPressureTrend()
        showDataSourceFooter()
        requestCaiyunPhenomenonCalibration(result.realtime?.skycon)
    }

    /**
     * 主数据先完整显示，校验在后台进行。这样辅助源超时或不可用时不会拖慢彩云加载，
     * 也不会影响通知、预警或缓存中的原始数据。
     */
    private fun requestCaiyunPhenomenonCalibration(primarySkycon: String?) {
        val context = context ?: return
        if (!WeatherSettings.usesOpenMeteoPhenomenonCalibration(context) ||
            !WeatherPhenomenonCalibration.shouldRequest(primarySkycon)
        ) return

        val request = ++calibrationFetchSeq
        Thread {
            val reference = try {
                OpenMeteo.fetchCurrentCondition(page.lat, page.lng)
            } catch (_: Exception) {
                null
            }
            val activity = activity ?: return@Thread
            activity.runOnUiThread {
                if (!isAdded || request != calibrationFetchSeq ||
                    !WeatherSettings.usesOpenMeteoPhenomenonCalibration(requireContext()) ||
                    lastSkycon != primarySkycon
                ) return@runOnUiThread

                val decision = WeatherPhenomenonCalibration.decide(
                    primarySkycon = primarySkycon,
                    referenceSkycon = reference?.skycon,
                    referenceTimeMillis = reference?.timeMillis ?: 0L
                )
                if (!decision.applied || decision.skycon == primarySkycon) return@runOnUiThread

                lastSkycon = decision.skycon
                tvDesc.text = WeatherText.skyconToText(decision.skycon)
                if (hourlySkycons.isNotEmpty()) {
                    hourlySkycons = hourlySkycons.toMutableList().apply { this[0] = decision.skycon }
                    renderHourlyChart()
                }
                applyBackground()
                Log.i("WeatherApp", "Applied Caiyun phenomenon calibration via Open-Meteo")
            }
        }.start()
    }

    // 清空当前预警数据（切换数据源时避免残留）
    private fun clearAlerts() {
        alertContentVersion++
        alertHandler.removeCallbacks(alertScrollRunnable)
        alertTitles.clear()
        alertLevels.clear()
        alertContents.clear()
        alertTimes.clear()
        alertCodes.clear()
        alertLocations.clear()
        alertSources.clear()
        alertColors.clear()
    }

    // 用和风 v1 预警补齐彩云缺失的预警；existingKeys 是已展示的简化名称归一化集合
    private fun supplementAlertsFromQWeather(
        existingKeys: Set<String>,
        onCompleted: () -> Unit
    ) {
        val request = ++alertRequestVersion
        val appContext = requireContext().applicationContext
        if(BuildConfig.USER_CONFIGURABLE && !UserApiConfiguration.read(appContext).hasQWeather()) { clearAlerts();onCompleted();return }
        val sourceKey=WeatherSettings.sourceKey(appContext)
        Thread {
            val alerts = try {
                QWeatherApi.fetchAlertsOnlyChecked(appContext, page.lat, page.lng)
            } catch (e: Exception) {
                return@Thread // 请求失败不能当成“预警已解除”。
            }
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (!isAdded || view == null || request != alertRequestVersion || sourceKey!=WeatherSettings.sourceKey(appContext)) return@runOnUiThread
                clearAlerts()
                val seen = HashSet(existingKeys)
                val now = System.currentTimeMillis()
                for (a in alerts) {
                    if (a.messageType == "cancel" || a.expireTimeMillis <= now) continue
                    if (AlertVisibilityPolicy.isAgriculturalAlert(
                            a.eventTypeName, a.headline, a.senderName
                        )) continue
                    val title = alertShortName(a)
                    val key = normalizeAlertKey(title)
                    if (key.isEmpty() || !seen.add(key)) continue

                    alertTitles.add(title)
                    alertLevels.add(alertLevelName(a))
                    alertContents.add(
                        buildString {
                            append(a.description.ifBlank { a.headline }.ifBlank { "暂无详细预警内容" })
                            if (a.instruction.isNotBlank()) append("\n防御指南：").append(a.instruction)
                        }
                    )
                    alertTimes.add(formatAlertTime(a.issuedTimeMillis))
                    alertCodes.add(a.eventTypeCode)
                    alertLocations.add(
                        if (page.isCurrentLocation) page.locationDetail else page.name
                    )
                    alertSources.add(a.senderName)
                    alertColors.add(alertColorOf(a))
                }
                onCompleted()
            }
        }.start()
    }

    // 预警标题去重归一化：忽略括号、方括号与空白，让同一预警在彩云/和风两种措辞下不重复
    private fun normalizeAlertKey(s: String): String =
        s.replace(Regex("[\\[\\]（）()\\s]"), "").trim()

    // 预警入口：有预警显示并轮播，无预警隐藏（彩云/和风共用）
    private fun refreshAlertEntry() {
        if (!::alertEntryCard.isInitialized) return
        if (alertTitles.isEmpty()) {
            alertEntryCard.visibility = View.GONE
            ivAlertIcon.visibility = View.GONE
            alertHandler.removeCallbacks(alertScrollRunnable)
        } else {
            alertEntryCard.visibility = View.VISIBLE
            ivAlertIcon.visibility = View.VISIBLE
            alertIndex = 0
            tvAlertEntry.text = alertTitles[0]
            applyAlertEntryColor(alertColors.getOrNull(0))
            tvAlertEntry.alpha = 1f
            // 预警入口只负责显示。通知由后台风险检查统一产生，避免每次进入页面重复提醒。
            tvAlertEntry.translationY = 0f
            ivAlertIcon.alpha = 1f
            ivAlertIcon.translationY = 0f
            alertHandler.removeCallbacks(alertScrollRunnable)
            // 多条预警时每 3 秒轮播显示一条
            if (alertTitles.size > 1) alertHandler.postDelayed(alertScrollRunnable, 3000L)
        }
    }

    // 预警入口精简名称：从 code 生成 "暴雨红色预警" / "雷雨大风黄色预警" 等
    private fun alertShortName(a: AlertContent): String = WeatherAlertPresentation.title(a)

    // 预警等级：code 后两位（00白/01蓝/02黄/03橙/04红），缺失时从标题猜测
    private fun alertLevelName(code: String?, level: String?, title: String?): String =
        WeatherAlertPresentation.levelName(code, level, title)

    // 和风预警入口精简名称："山洪灾害事件" → "山洪灾害" + 等级 + "预警"
    private fun alertShortName(a: QAlert): String = WeatherAlertPresentation.title(a)

    // 和风预警等级：优先 color.code，其次 severity
    private fun alertLevelName(a: QAlert): String = WeatherAlertPresentation.levelName(a)

    // 预警级别 → 100% 不透明颜色（用于入口毛玻璃底色）
    private fun alertColorOf(a: AlertContent): Int {
        val base = when (alertLevelName(a.code, a.level, a.title)) {
            "红色" -> Color.rgb(217, 83, 79)
            "橙色" -> Color.rgb(255, 149, 0)
            "黄色" -> Color.rgb(240, 173, 78)
            "蓝色" -> Color.rgb(90, 180, 220)
            else -> Color.WHITE
        }
        // 预警入口颜色统一 100% 不透明
        return Color.argb(255, Color.red(base), Color.green(base), Color.blue(base))
    }

    // 和风预警级别 → 与彩云一致的入口底色
    private fun alertColorOf(a: QAlert): Int {
        val base = when (alertLevelName(a)) {
            "红色" -> Color.rgb(217, 83, 79)
            "橙色" -> Color.rgb(255, 149, 0)
            "黄色" -> Color.rgb(240, 173, 78)
            "蓝色" -> Color.rgb(90, 180, 220)
            else -> Color.WHITE
        }
        return Color.argb(255, Color.red(base), Color.green(base), Color.blue(base))
    }

    /** 预警入口始终以当前预警等级的原色 100% 填充，不参与普通卡片透明玻璃配色。 */
    private fun applyAlertEntryColor(color: Int?) {
        val solid = color ?: Color.WHITE
        alertEntryCard.setTint(
            Color.argb(255, Color.red(solid), Color.green(solid), Color.blue(solid)),
            Color.argb(255, Color.red(solid), Color.green(solid), Color.blue(solid))
        )
    }

    // 多条预警轮播：图标与文字整体下滑淡出淡入（始终随卡片居中），
    // 颜色不同时底色同步渐变
    private fun fadeAlertTo(next: Int) {
        if (!isAdded || alertTitles.size < 2) return
        val target = next.coerceIn(0, alertTitles.lastIndex)
        val contentVersion = alertContentVersion
        val slide = dp(12f).toFloat()
        val out = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180
            addUpdateListener { v ->
                val f = v.animatedValue as Float
                ivAlertIcon.alpha = f
                ivAlertIcon.translationY = slide * (1f - f)
                tvAlertEntry.alpha = f
                tvAlertEntry.translationY = slide * (1f - f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 预警接口可在淡出期间清空/替换列表；此时直接结束本次旧动画。
                    if (!isAdded || contentVersion != alertContentVersion) return
                    val nextTitle = alertTitles.getOrNull(target) ?: return
                    tvAlertEntry.text = nextTitle
                    applyAlertEntryColor(alertColors.getOrNull(target))
                    val inn = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 180
                        addUpdateListener { v ->
                            val f = v.animatedValue as Float
                            ivAlertIcon.alpha = f
                            ivAlertIcon.translationY = -slide * (1f - f)
                            tvAlertEntry.alpha = f
                            tvAlertEntry.translationY = -slide * (1f - f)
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                ivAlertIcon.translationY = 0f
                                tvAlertEntry.translationY = 0f
                            }
                        })
                    }
                    inn.start()
                }
            })
        }
        out.start()
    }

    private fun showRealtime(rt: RealTime) {
        lastSkycon = rt.skycon
        lastTemp = rt.temperature
        lastWindDir = windDirText(rt.wind?.direction)
        lastWindDirDeg = rt.wind?.direction
        lastWindSpeedKmh = rt.wind?.speed
        lastWindScale = null
        lastFeelsLike = rt.apparentTemperature
        lastUvIndex = rt.lifeIndex?.ultraviolet?.index
        lastUvDesc = rt.lifeIndex?.ultraviolet?.desc
        // 彩云湿度可能是 0~1 小数或百分比；气压可能是 Pa/kPa/hPa，统一换算成百分比与 hPa
        lastHumidity = rt.humidity?.let { if (it <= 1.0) it * 100.0 else it }
        lastPressureHpa = rt.pressure?.let { p ->
            when {
                p > 20000 -> p / 100.0
                p < 150 -> p * 10.0
                else -> p
            }
        }
        lastVisibilityKm = rt.visibility
        lastPrecipMm = rt.precipitation?.local?.intensity
        applyBackground()
        tvTemp.text = WeatherSettings.tempText(requireContext(), rt.temperature)
        tvDesc.text = WeatherText.skyconToText(rt.skycon)
        updateDetailCards()
        renderLifeAdvice()
        // 空气质量卡片：等级、数值、描述/建议随 AQI 实时变化
        // v2.7 的空气质量在 air_quality.aqi.chn 嵌套结构里，旧版顶层 aqi 作为兼容兜底
        val aqiVal = rt.airQuality?.aqi?.chn ?: rt.aqi?.chn
        currentAirQuality = AirQualityPoint(
            timeMillis = System.currentTimeMillis(),
            aqi = aqiVal,
            pm25 = rt.airQuality?.pm25 ?: rt.pm25,
            pm10 = rt.airQuality?.pm10,
            no2 = rt.airQuality?.no2,
            o3 = rt.airQuality?.o3,
            co = rt.airQuality?.co,
            so2 = rt.airQuality?.so2,
            source = AirQualitySource.CAIYUN
        )
        renderAqiCard(aqiVal)
    }

    private fun renderAqiCard(aqi: Double?) {
        val info = aqiInfo(aqi)
        tvAqiLevel.text = info.first
        tvAqiValue.text = aqi?.let { "${it.toInt()}" } ?: "--"
        tvAqiDesc.text = info.second
        aqiBar.setAqi(aqi)
        playPendingIndicatorEntryAnimation()
    }

    /** 主页 AQI 卡片、污染物网格与二级页只读这一条带来源的时间线。 */
    private fun applyAirQualityTimeline(timeline: AirQualityTimeline) {
        airQualityTimeline = timeline
        airQualityPoints = timeline.pointsWithRealtime()
        currentAirQuality = timeline.current()
        renderAqiCard(currentAirQuality?.aqi)
    }

    // 按中国 AQI 标准生成等级与描述/建议文案
    private fun aqiInfo(aqi: Double?): Pair<String, String> {
        return AirQualityScale.level(aqi) to AirQualityScale.advice(aqi)
    }

    private fun currentHourMillis(): Long = hourStartMillis(System.currentTimeMillis())

    private fun hourStartMillis(timeMillis: Long): Long =
        (timeMillis / 3_600_000L) * 3_600_000L

    /** 首页卡片与二级图表共用的“当前整点”原子快照。 */
    private fun realtimeMetricPoint(
        hourMillis: Long,
        fallback: HourlyMetricPoint? = null
    ): HourlyMetricPoint = HourlyMetricPoint(
        timeMillis = hourMillis,
        temperature = lastTemp ?: fallback?.temperature,
        feelsLike = lastFeelsLike ?: fallback?.feelsLike,
        precipitationProbability = fallback?.precipitationProbability,
        precipitationAmountMm = lastPrecipMm ?: fallback?.precipitationAmountMm,
        uv = lastUvIndex?.toDoubleOrNull() ?: fallback?.uv,
        windKmh = lastWindSpeedKmh ?: fallback?.windKmh,
        gustKmh = fallback?.gustKmh,
        windDirectionDegrees = lastWindDirDeg ?: fallback?.windDirectionDegrees,
        pressureHpa = lastPressureHpa ?: fallback?.pressureHpa,
        visibilityKm = lastVisibilityKm ?: fallback?.visibilityKm,
        humidityPercent = lastHumidity ?: fallback?.humidityPercent
    )

    private fun showHourly(hourly: Hourly) {
        val tempList = hourly.temperature.orEmpty()
        if (tempList.isEmpty()) {
            // 主源缺少小时段时不再用第三天气源伪造曲线；保留已缓存段并请求同源历史。
            requestSelectedSourceMetricHistory()
            return
        }

        // 以温度为基准，天气/降水按 datetime 对齐到同一小时，
        // 避免列表顺序或长度不一致导致"天气和数据对不上"
        val skyByTime = hourly.skycon.orEmpty().associate { it.datetime to it.value }
        val precipitationByTime = hourly.precipitation.orEmpty().associateBy { it.datetime }
        val probByTime = precipitationByTime.mapValues { (_, value) -> value.probability ?: 0.0 }
        val feelsByTime = hourly.apparentTemperature.orEmpty().associate { it.datetime to it.value }
        val humidityByTime = hourly.humidity.orEmpty().associate { it.datetime to it.value }
        val pressureByTime = hourly.pressure.orEmpty().associate { it.datetime to it.value }
        val visibilityByTime = hourly.visibility.orEmpty().associate { it.datetime to it.value }
        val windByTime = hourly.wind.orEmpty().associateBy { it.datetime }
        val gustByTime = hourly.gust.orEmpty().associate { it.datetime to it.value }
        val uvByTime = hourly.lifeIndex?.ultraviolet.orEmpty().associateBy { it.datetime }
        // 两个天气源都用同一“当前整点 + 后续整点”时间轴：只在真实时间跨整点时换高亮，
        // 当前列采用 realtime 快照，因此 48 小时卡片、顶部实时温度与二级详情的首项完全一致。
        val currentHour = currentHourMillis()
        val parsedTimes = tempList.mapIndexed { index, value ->
            value.datetime?.let(::parseTime) ?: (currentHour + index * 3_600_000L)
        }
        val capturedAt = System.currentTimeMillis()
        val hourlySnapshots = tempList.mapIndexed { index, value ->
            CaiyunHourlyWeatherSnapshot(
                timeMillis = parsedTimes[index],
                temperature = value.value,
                skycon = skyByTime[value.datetime],
                precipitationProbability = probByTime[value.datetime],
                uv = uvByTime[value.datetime]?.index?.toDoubleOrNull(),
                capturedAtMillis = capturedAt
            )
        }
        CaiyunWeatherHistorySnapshotCache.save(
            requireContext(), page, hourly = hourlySnapshots
        )
        fun metricAt(index: Int): HourlyMetricPoint {
            val value = tempList[index]
            return HourlyMetricPoint(
                timeMillis = parsedTimes[index],
                temperature = value.value,
                feelsLike = feelsByTime[value.datetime],
                precipitationProbability = precipitationByTime[value.datetime]?.probability,
                precipitationAmountMm = precipitationByTime[value.datetime]?.value,
                // v2.7 直接读取 hourly.life_index.ultraviolet；不能把 dswrf 当作 UV。
                uv = uvByTime[value.datetime]?.index?.toDoubleOrNull(),
                windKmh = windByTime[value.datetime]?.speed,
                gustKmh = gustByTime[value.datetime],
                windDirectionDegrees = windByTime[value.datetime]?.direction,
                pressureHpa = pressureByTime[value.datetime]?.let(::toHpa),
                visibilityKm = visibilityByTime[value.datetime],
                humidityPercent = humidityByTime[value.datetime]?.let { if (it <= 1.0) it * 100.0 else it }
            )
        }
        val currentIndex = parsedTimes.indices.firstOrNull {
            hourStartMillis(parsedTimes[it]) == currentHour
        }
        val futureIndexes = parsedTimes.indices
            .filter { hourStartMillis(parsedTimes[it]) > currentHour }
            .sortedBy { parsedTimes[it] }
        hourlyRefMillis = currentHour
        forecastSkyByHour = tempList.indices.associate {
            parsedTimes[it] / 3_600_000L to skyByTime[tempList[it].datetime]
        }
        hourlyLabels = listOf(formatClock(currentHour)) + futureIndexes.map { formatClock(parsedTimes[it]) }
        hourlyTemps = listOf(lastTemp ?: currentIndex?.let { tempList[it].value }) +
            futureIndexes.map { tempList[it].value }
        hourlySkycons = listOf(lastSkycon ?: currentIndex?.let { skyByTime[tempList[it].datetime] }) +
            futureIndexes.map { skyByTime[tempList[it].datetime] }
        hourlyProbs = listOf(currentIndex?.let { probByTime[tempList[it].datetime] ?: 0.0 } ?: 0.0) +
            futureIndexes.map { probByTime[tempList[it].datetime] ?: 0.0 }
        hourlyMetricPoints = listOf(realtimeMetricPoint(currentHour, currentIndex?.let(::metricAt))) +
            futureIndexes.map(::metricAt)
        if(WeatherSettings.useOpenMeteo(requireContext())) {
            yesterdayMetricPoints=parsedTimes.indices.filter {parsedTimes[it]<currentHour}.map(::metricAt).takeLast(24)
            historicalSupplementedKinds=emptySet()
            hasSelectedSourceMetricHistory=yesterdayMetricPoints.isNotEmpty()
        }
        restoreCachedHourlyHistory()
        renderHourlyChart()
        requestSelectedSourceMetricHistory()
    }

    /** 首页 48 小时卡片只展示最近一列已过去的真实彩云快照。 */
    private fun restoreCachedHourlyHistory() {
        val ref = if (hourlyRefMillis > 0L) hourlyRefMillis else currentHourMillis()
        hourlyPast = CaiyunWeatherHistorySnapshotCache.load(requireContext(), page)
            ?.hourly.orEmpty()
            .filter { it.timeMillis in (ref - 3_600_000L) until ref }
            .sortedBy { it.timeMillis }
            .takeLast(1)
    }

    /** 彩云 v2.6 begin 历史响应可直接还原首页所需的温度、现象和降水。 */
    private fun hourlyWeatherHistoryFromCaiyun(result: Result?): List<CaiyunHourlyWeatherSnapshot> {
        val hourly = result?.hourly ?: return emptyList()
        val skyByTime = hourly.skycon.orEmpty().associate { it.datetime to it.value }
        val precipitationByTime = hourly.precipitation.orEmpty()
            .associate { it.datetime to (it.probability ?: 0.0) }
        val uvByTime = hourly.lifeIndex?.ultraviolet.orEmpty().associateBy { it.datetime }
        val capturedAt = System.currentTimeMillis()
        return hourly.temperature.orEmpty().mapNotNull { temperature ->
            val datetime = temperature.datetime ?: return@mapNotNull null
            val time = runCatching { parseTime(datetime) }.getOrNull() ?: return@mapNotNull null
            CaiyunHourlyWeatherSnapshot(
                timeMillis = time,
                temperature = temperature.value,
                skycon = skyByTime[datetime],
                precipitationProbability = precipitationByTime[datetime],
                uv = uvByTime[datetime]?.index?.toDoubleOrNull(),
                capturedAtMillis = capturedAt
            )
        }
    }

    /** 恢复同源历史缓存；彩云再请求 v2.6 begin，以最新成功结果替换缓存。 */
    private fun requestSelectedSourceMetricHistory() {
        val context = context ?: return
        if(WeatherSettings.useOpenMeteo(context) || WeatherSettings.useQWeather(context)) {
            metricHistoryLoading=false;dispatchMetricReadyIfComplete();return
        }
        val sourceKey=WeatherSettings.sourceKey(context)
        hasSelectedSourceMetricHistory = false
        metricHistoryLoading = false
        val primarySource = "caiyun_v26"
        MetricHistoryTimelineCache.load(context, page, primarySource)?.let { cached ->
            val cachedUv = mergeCachedUvSnapshots(cached.points)
            yesterdayMetricPoints = cachedUv.points
            historicalSupplementedKinds = cached.supplementedKinds.orEmpty()
                .mapNotNull { name -> MetricKind.entries.firstOrNull { it.name == name } }
                .toSet() + if (cachedUv.used) setOf(MetricKind.UV) else emptySet()
            historicalAirQualityPoints = cached.airQualityPoints.orEmpty()
            hasSelectedSourceMetricHistory = true
        }
        if (!hasSelectedSourceMetricHistory) {
            val cachedUv = mergeCachedUvSnapshots(emptyList())
            if (cachedUv.points.isNotEmpty()) {
                yesterdayMetricPoints = cachedUv.points
                historicalSupplementedKinds = setOf(MetricKind.UV)
                hasSelectedSourceMetricHistory = true
            }
        }

        val request = ++metricHistoryFetchSeq
        metricHistoryLoading = true
        val beginSeconds = (currentHourMillis() - 24L * 3_600_000L) / 1000L
        WeatherDataRepository.enqueueCaiyunMetricHistory(
            context, page.lng, page.lat, beginSeconds
        ) { response ->
            if (!isAdded || request != metricHistoryFetchSeq || sourceKey!=WeatherSettings.sourceKey(requireContext())) return@enqueueCaiyunMetricHistory
            if (response.isSuccessful) {
                response.body?.result?.daily?.let(::applyYesterdayDailyHistory)
                val history = metricHistoryFromCaiyun(response.body?.result)
                    .filter { it.timeMillis < currentHourMillis() }
                    .sortedBy { it.timeMillis }
                    .takeLast(24)
                val weatherHistory = hourlyWeatherHistoryFromCaiyun(response.body?.result)
                    .filter { it.timeMillis < currentHourMillis() }
                    .sortedBy { it.timeMillis }
                if (weatherHistory.isNotEmpty()) {
                    CaiyunWeatherHistorySnapshotCache.save(
                        requireContext(), page, hourly = weatherHistory
                    )
                    restoreCachedHourlyHistory()
                    renderHourlyChart()
                }
                val airHistory = airQualityHistoryFromCaiyun(response.body?.result)
                    .filter { it.timeMillis < currentHourMillis() }
                    .sortedBy { it.timeMillis }
                    .takeLast(24)
                val cachedUv = mergeCachedUvSnapshots(history)
                if (cachedUv.points.isNotEmpty()) {
                    val supplemented = if (cachedUv.used) setOf(MetricKind.UV) else emptySet()
                    MetricHistoryTimelineCache.save(
                        requireContext(), page, primarySource, cachedUv.points,
                        supplementedKinds = supplemented,
                        airQualityPoints = airHistory
                    )
                    yesterdayMetricPoints = cachedUv.points
                    historicalSupplementedKinds = supplemented
                    historicalAirQualityPoints = airHistory
                    hasSelectedSourceMetricHistory = true
                }
            }
            metricHistoryLoading = false
            dispatchMetricReadyIfComplete()
        }
    }

    private data class CachedUvMerge(
        val points: List<HourlyMetricPoint>,
        val used: Boolean
    )

    /**
     * 彩云 begin 若未返回历史 UV，才从应用先前保存的同源小时预测快照补空值。
     * 这不是由日照或第三方推导出的数值；没有快照就保持缺失。
     */
    private fun mergeCachedUvSnapshots(points: List<HourlyMetricPoint>): CachedUvMerge {
        val now = currentHourMillis()
        val uvByHour = CaiyunWeatherHistorySnapshotCache.load(requireContext(), page)
            ?.hourly.orEmpty()
            .filter { it.timeMillis < now && it.timeMillis >= now - 26L * 3_600_000L && it.uv != null }
            .associateBy { it.timeMillis / 3_600_000L }
        if (uvByHour.isEmpty()) return CachedUvMerge(points, false)

        var used = false
        val merged = points.map { point ->
            if (point.uv != null) point else {
                val snapshot = uvByHour[point.timeMillis / 3_600_000L]
                if (snapshot?.uv != null) {
                    used = true
                    point.copy(uv = snapshot.uv)
                } else point
            }
        }.toMutableList()
        val existingHours = merged.map { it.timeMillis / 3_600_000L }.toHashSet()
        uvByHour.forEach { (hour, snapshot) ->
            if (hour !in existingHours) {
                merged += HourlyMetricPoint(timeMillis = snapshot.timeMillis, uv = snapshot.uv)
                used = true
            }
        }
        return CachedUvMerge(merged.sortedBy { it.timeMillis }.takeLast(24), used)
    }

    /** v2.6 与当前 v2.7 共享的小时字段按时间键合并，缺字段保留为空而不臆测数值。 */
    private fun metricHistoryFromCaiyun(result: Result?): List<HourlyMetricPoint> {
        val hourly = result?.hourly ?: return emptyList()
        val temperatures = hourly.temperature.orEmpty()
        val feels = hourly.apparentTemperature.orEmpty().associate { it.datetime to it.value }
        val humidity = hourly.humidity.orEmpty().associate { it.datetime to it.value }
        val pressure = hourly.pressure.orEmpty().associate { it.datetime to it.value }
        val visibility = hourly.visibility.orEmpty().associate { it.datetime to it.value }
        val wind = hourly.wind.orEmpty().associateBy { it.datetime }
        val gust = hourly.gust.orEmpty().associate { it.datetime to it.value }
        val ultraviolet = hourly.lifeIndex?.ultraviolet.orEmpty().associateBy { it.datetime }
        return temperatures.mapNotNull { temperature ->
            val time = temperature.datetime?.let(::parseTime) ?: return@mapNotNull null
            HourlyMetricPoint(
                timeMillis = time,
                temperature = temperature.value,
                feelsLike = feels[temperature.datetime],
                uv = ultraviolet[temperature.datetime]?.index?.toDoubleOrNull(),
                windKmh = wind[temperature.datetime]?.speed,
                gustKmh = gust[temperature.datetime],
                windDirectionDegrees = wind[temperature.datetime]?.direction,
                pressureHpa = pressure[temperature.datetime]?.let(::toHpa),
                visibilityKm = visibility[temperature.datetime],
                humidityPercent = humidity[temperature.datetime]?.let { if (it <= 1.0) it * 100.0 else it }
            )
        }
    }

    /** 与历史六项指标同批读取的彩云 hourly.air_quality，不能拿未来趋势替代昨日 AQI。 */
    private fun airQualityHistoryFromCaiyun(result: Result?): List<AirQualityPoint> {
        val air = result?.hourly?.airQuality ?: return emptyList()
        val aqi = air.aqi.orEmpty().associateBy { it.datetime }
        val pm25 = air.pm25.orEmpty().associate { it.datetime to it.value }
        val pm10 = air.pm10.orEmpty().associate { it.datetime to it.value }
        val no2 = air.no2.orEmpty().associate { it.datetime to it.value }
        val o3 = air.o3.orEmpty().associate { it.datetime to it.value }
        val co = air.co.orEmpty().associate { it.datetime to it.value }
        val so2 = air.so2.orEmpty().associate { it.datetime to it.value }
        val times = (aqi.keys + pm25.keys + pm10.keys + no2.keys + o3.keys + co.keys + so2.keys)
            .filterNotNull()
            .distinct()
        return times.mapNotNull { datetime ->
            val time = runCatching { parseTime(datetime) }.getOrNull() ?: return@mapNotNull null
            val aqiValue = aqi[datetime]?.value?.chn
            val values = listOf(aqiValue, pm25[datetime], pm10[datetime], no2[datetime], o3[datetime], co[datetime], so2[datetime])
            if (values.all { it == null }) return@mapNotNull null
            AirQualityPoint(
                timeMillis = time,
                aqi = aqiValue,
                pm25 = pm25[datetime],
                pm10 = pm10[datetime],
                no2 = no2[datetime],
                o3 = o3[datetime],
                co = co[datetime],
                so2 = so2[datetime],
                source = AirQualitySource.CAIYUN,
                updatedAtMillis = lastServerTimeMillis
            )
        }
    }

    private fun setupMetricDetailCardClicks(root: View) {
        root.findViewById<View>(R.id.aqiCard).setOnClickListener { card ->
            openAirQualityDetail(card)
        }
        val cards = listOf(
            R.id.cardUv to MetricKind.UV,
            R.id.cardFeelsLike to MetricKind.FEELS_LIKE,
            R.id.cardWind to MetricKind.WIND,
            R.id.cardPressure to MetricKind.PRESSURE,
            R.id.cardVisibility to MetricKind.VISIBILITY,
            R.id.cardHumidity to MetricKind.HUMIDITY
        )
        cards.forEach { (id, kind) ->
            root.findViewById<View>(id).setOnClickListener { card ->
                openMetricDetail(kind, card)
            }
        }
    }

    private fun openMetricDetail(kind: MetricKind, source: View) {
        // 历史请求与明确的缺字段补充尚在运行时，先等待同一个渲染事务提交。
        // 这样首次进入详情也不会固定显示“昨日同期待补充”。
        if (yesterdayMetricPoints.none { kind.valueOf(it) != null } &&
            (metricHistoryLoading || metricSupplementLoading)
        ) {
            metricReadyCallbacks += { if (isAdded) openMetricDetail(kind, source) }
            android.widget.Toast.makeText(requireContext(), "正在准备昨日同期数据…", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 阵风仅由补充小时数据提供。若用户比网络请求更早点击风力卡，先等本轮
        // 合并完成再打开，避免把“待更新”固化到详情页。
        if (kind == MetricKind.WIND && hourlyMetricPoints.none { it.gustKmh != null } && metricSupplementLoading) {
            metricReadyCallbacks += { if (isAdded) launchMetricDetail(kind, source) }
            android.widget.Toast.makeText(requireContext(), "正在准备阵风数据…", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        launchMetricDetail(kind, source)
    }

    private fun openAirQualityDetail(source: View) {
        if (historicalAirQualityPoints.isEmpty() && metricHistoryLoading) {
            metricReadyCallbacks += { if (isAdded) openAirQualityDetail(source) }
            android.widget.Toast.makeText(requireContext(), "正在准备昨日同期空气数据…", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val state = detailNavigationState()
        val navigationId = DetailNavigationStore.register(state)
        OplusViewSeamlessNavigator.startFromCard(
            this,
            source,
            DetailNavigator.cardEntryIntent(requireContext(), DetailPage.AIR_QUALITY, navigationId, state)
        )
    }

    private fun launchMetricDetail(kind: MetricKind, source: View) {
        val points = hourlyMetricPoints.ifEmpty { fallbackMetricPoints() }
        val state = detailNavigationState(points)
        val navigationId = DetailNavigationStore.register(state)
        OplusViewSeamlessNavigator.startFromCard(
            this,
            source,
            DetailNavigator.cardEntryIntent(
                requireContext(),
                DetailNavigator.pageOf(kind),
                navigationId,
                state
            )
        )
    }

    private fun detailNavigationState(metricPoints: List<HourlyMetricPoint> = hourlyMetricPoints.ifEmpty { fallbackMetricPoints() }): DetailNavigationState =
        DetailNavigationState(
            cityName = if (page.isCurrentLocation) page.locationDetail else page.name,
            rankingCityName = page.name,
            metricPoints = metricPoints,
            yesterdayMetricPoints = yesterdayMetricPoints,
            historicalSupplementedKinds = historicalSupplementedKinds,
            windDirection = lastWindDir,
            lifeAdvice = lifeAdvice,
            airPoints = (historicalAirQualityPoints + airQualityPoints)
                .distinctBy { it.timeMillis / 3_600_000L }
                .ifEmpty { listOfNotNull(currentAirQuality) },
            currentAir = currentAirQuality,
            sun = SunAstronomyData(
                sunriseMillis, sunsetMillis, yesterdaySunsetMillis, nextSunriseMillis,
                page.lat, page.lng, sunDataSourceName(), lastSkycon
            )
        )

    private fun fallbackMetricPoints(): List<HourlyMetricPoint> {
        val start = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
        return List(1) { index ->
            HourlyMetricPoint(
                timeMillis = start + index * 3_600_000L,
                temperature = lastTemp,
                feelsLike = lastFeelsLike,
                uv = lastUvIndex?.toDoubleOrNull(),
                windKmh = lastWindSpeedKmh,
                windDirectionDegrees = lastWindDirDeg,
                pressureHpa = lastPressureHpa,
                visibilityKm = lastVisibilityKm,
                humidityPercent = lastHumidity
            )
        }
    }

    // Open-Meteo 仅补主数据源没有返回的小时字段；彩云和和风各自的原始字段不会被覆盖。
    private fun fetchOpenMeteoHourlyMetrics() {
        // Open-Meteo 已从生产数据链路移除；详情直接呈现彩云主源和和风补齐字段。
        metricSupplementLoading = false
        dispatchMetricReadyIfComplete()
        return
        val request = ++metricFetchSeq
        val sourcePoints = hourlyMetricPoints
        metricSupplementLoading = true
        // 保留上一轮已取得的昨日数据直到新请求成功，避免重复进入详情页时异步补数
        // 尚未完成而短暂显示“昨日同期数据待补充”。
        Thread {
            val supplemental = try {
                // 额外请求到未来 30 小时，避免和风最后一个整点因边界差异没有阵风数据。
                OpenMeteo.fetchHourlyMetrics(page.lat, page.lng, hours = 30, pastHours = 24)
            } catch (_: Exception) {
                emptyList()
            }
            activity?.runOnUiThread {
                if (!isAdded || request != metricFetchSeq) return@runOnUiThread
                if (supplemental.isNotEmpty()) {
                    hourlyMetricPoints = mergeMetricPoints(sourcePoints, supplemental)
                    val currentHour = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
                    val history = supplemental
                        .filter { it.timeMillis < currentHour }
                        .sortedBy { it.timeMillis }
                        .takeLast(24)
                    if (!hasSelectedSourceMetricHistory && history.isNotEmpty()) {
                        yesterdayMetricPoints = history
                        historicalSupplementedKinds = MetricKind.entries.toSet()
                        MetricHistoryTimelineCache.save(
                            requireContext(), page, "open_meteo_history", history,
                            supplementedKinds = MetricKind.entries.toSet(),
                            supplementSource = "Open-Meteo"
                        )
                    } else if (hasSelectedSourceMetricHistory && history.isNotEmpty()) {
                        // 彩云历史接口没有逐小时 UV 等字段时，只补齐确实为空的指标。
                        // 已有同源历史值永远优先，详情页会明确标注该指标用了补充源。
                        val merged = mergeMetricPoints(yesterdayMetricPoints, history)
                        val newlySupplemented = MetricKind.entries.filter { kind ->
                            yesterdayMetricPoints.none { kind.valueOf(it) != null } &&
                                merged.any { kind.valueOf(it) != null }
                        }.toSet()
                        if (newlySupplemented.isNotEmpty()) {
                            yesterdayMetricPoints = merged
                            historicalSupplementedKinds = historicalSupplementedKinds + newlySupplemented
                            val primarySource = if (WeatherSettings.useQWeather(requireContext())) {
                                "qweather_time_machine"
                            } else {
                                "caiyun_v26"
                            }
                            MetricHistoryTimelineCache.save(
                                requireContext(), page, primarySource, merged,
                                supplementedKinds = historicalSupplementedKinds,
                                supplementSource = "Open-Meteo",
                                airQualityPoints = historicalAirQualityPoints
                            )
                        }
                    }
                }
                metricSupplementLoading = false
                dispatchMetricReadyIfComplete()
            }
        }.start()
    }

    /** 历史主源与补充源都结束后，才允许此前等待的详情页创建自己的不可变快照。 */
    private fun dispatchMetricReadyIfComplete() {
        if (metricHistoryLoading || metricSupplementLoading) return
        val callbacks = metricReadyCallbacks.toList()
        metricReadyCallbacks.clear()
        callbacks.forEach { it.invoke() }
    }

    private fun mergeMetricPoints(
        primary: List<HourlyMetricPoint>,
        supplemental: List<HourlyMetricPoint>
    ): List<HourlyMetricPoint> {
        val extra = supplemental.associateBy { it.timeMillis / 3_600_000L }
        val base = if (primary.isEmpty()) supplemental else primary
        return base.map { point ->
            val second = extra[point.timeMillis / 3_600_000L] ?: return@map point
            point.copy(
                temperature = point.temperature ?: second.temperature,
                feelsLike = point.feelsLike ?: second.feelsLike,
                uv = point.uv ?: second.uv,
                windKmh = point.windKmh ?: second.windKmh,
                gustKmh = point.gustKmh ?: second.gustKmh,
                windDirectionDegrees = point.windDirectionDegrees ?: second.windDirectionDegrees,
                pressureHpa = point.pressureHpa ?: second.pressureHpa,
                visibilityKm = point.visibilityKm ?: second.visibilityKm,
                humidityPercent = point.humidityPercent ?: second.humidityPercent
            )
        }.take(24)
    }

    private fun toHpa(value: Double): Double = when {
        value > 20_000 -> value / 100.0
        value < 150 -> value * 10.0
        else -> value
    }

    // 把 Open-Meteo 的过去 2 小时前置到彩云数据前面（灰色），
    // 当前小时高亮标记保持在彩云第一列（紧跟在历史数据之后）
    private fun renderHourlyChart() {
        if (hourlyTemps.isEmpty()) return
        // 解析失败兜底：直接用当前时间作为分界，保证历史 2 小时和高亮依然正常
        val ref = if (hourlyRefMillis != 0L) hourlyRefMillis else System.currentTimeMillis()
        val past = if (ref != 0L) {
            hourlyPast.filter { it.timeMillis in (ref - 3_600_000L) until ref }
                .sortedBy { it.timeMillis }
                .takeLast(1)
        } else {
            emptyList()
        }
        val labels = ArrayList<String>(past.size + hourlyLabels.size)
        val temps = ArrayList<Double?>(past.size + hourlyTemps.size)
        val skycons = ArrayList<String?>(past.size + hourlySkycons.size)
        val probs = ArrayList<Double>(past.size + hourlyProbs.size)
        val c = requireContext()
        for (p in past) {
            labels.add(formatClock(p.timeMillis))
            temps.add(WeatherSettings.tempDisplay(c, p.temperature))
            skycons.add(p.skycon)
            probs.add(p.precipitationProbability ?: 0.0)
        }
        labels.addAll(hourlyLabels.take(ForecastDetailData.HOME_HOURS))
        for (t in hourlyTemps.take(ForecastDetailData.HOME_HOURS)) temps.add(WeatherSettings.tempDisplay(c, t))
        skycons.addAll(hourlySkycons.take(ForecastDetailData.HOME_HOURS))
        probs.addAll(hourlyProbs.take(ForecastDetailData.HOME_HOURS))
        // 高亮列（当前整点）用实时气温覆盖，保证高亮数字与顶部实时温度一致
        if (lastTemp != null) {
            temps[past.size] = WeatherSettings.tempDisplay(c, lastTemp)
        }
        hourlyChart.setData(labels, temps, skycons, probs, pastCount = past.size)
        // 48 小时卡片数据就绪后重新校正弹性空白：
        // 预览页没有入口刷新兜底，必须在这里保证默认完整展示 48 小时卡片
        view?.post { if (isAdded) alignBottom() }
    }

    private fun applyYesterdayDailyHistory(daily: Daily) {
        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
        val t = daily.temperature.orEmpty().firstOrNull { it.date?.take(10) == yesterday } ?: return
        val sky = daily.skycon.orEmpty().firstOrNull { it.date?.take(10) == yesterday }?.value
        val rain = daily.precipitation.orEmpty().firstOrNull { it.date?.take(10) == yesterday }?.probability?.toInt()
        val high = t.max?.let { Math.round(it).toInt() }
        val low = t.min?.let { Math.round(it).toInt() }
        CaiyunWeatherHistorySnapshotCache.save(requireContext(), page, daily = listOf(
            CaiyunDailyWeatherSnapshot(yesterday, sky, high, low, rain, System.currentTimeMillis())
        ))
        primaryDaily = (primaryDaily.filterNot { it.date == yesterday } +
            DailyForecast(yesterday, sky, high, low, rain)).sortedBy { it.date }
        dailySource = mergedDaily()
        dailySource?.let(::renderDaily)
    }

    private fun showDaily(daily: Daily) {
        val temps = daily.temperature.orEmpty()
        val skycons = daily.skycon.orEmpty()
        val precipts = daily.precipitation.orEmpty()

        // 日预报以日期键匹配“今天”，避免服务端排序变化时高低温错位。
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(System.currentTimeMillis())
        val today = temps.firstOrNull { (it.date ?: "").take(10) == todayKey } ?: temps.firstOrNull()
        if (today != null) {
            lastHigh = today.max
            lastLow = today.min
            tvHighLow.text =
                "最高 ${WeatherSettings.tempText(requireContext(), today.max)}   最低 ${WeatherSettings.tempText(requireContext(), today.min)}"
        }

        val astroList = daily.astro.orEmpty()
        applyCaiyunSunAstronomy(caiyunV26Astro.ifEmpty { astroList })

        lifeAdvice = if(WeatherSettings.useOpenMeteo(requireContext())) emptyList() else LifeAdviceFactory.fromQWeather(LifeAdviceCache.load(requireContext(), page.lat, page.lng))
        renderLifeAdvice()

        // 彩云字段按日期对齐，不能按数组索引把天气/降水错配。
        val skyconByDate = daily.skycon.orEmpty().associate { it.date?.take(10) to it.value }
        val precipitationByDate = daily.precipitation.orEmpty()
            .associate { it.date?.take(10) to it.probability?.toInt() }
        val capturedAt = System.currentTimeMillis()
        val incomingDaily = temps.mapNotNull { t ->
            val date = t.date?.take(10)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DailyForecast(
                date = date,
                skycon = skyconByDate[date],
                high = t.max?.let { v -> Math.round(v).toInt() },
                low = t.min?.let { v -> Math.round(v).toInt() },
                precipProb = precipitationByDate[date]
            )
        }
        CaiyunWeatherHistorySnapshotCache.save(
            requireContext(), page,
            daily = incomingDaily.map {
                CaiyunDailyWeatherSnapshot(
                    date = it.date,
                    skycon = it.skycon,
                    high = it.high,
                    low = it.low,
                    precipitationProbability = it.precipProb,
                    capturedAtMillis = capturedAt
                )
            }
        )
        // 当前彩云响应优先；它未包含的过去日期只可由仍有效的同源快照补齐。
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(System.currentTimeMillis())
        val cachedPast = CaiyunWeatherHistorySnapshotCache.load(requireContext(), page)
            ?.daily.orEmpty()
            .filter { it.date.take(10) < todayDate }
            .map {
                DailyForecast(
                    date = it.date.take(10),
                    skycon = it.skycon,
                    high = it.high,
                    low = it.low,
                    precipProb = it.precipitationProbability
                )
            }
        val yesterdayDate = java.time.LocalDate.now().minusDays(1).toString()
        primaryDaily = (cachedPast + incomingDaily)
            .filter { it.date >= yesterdayDate }
            .groupBy { it.date }
            .values
            .map { rows -> rows.last() }
            .sortedBy { it.date }
        dailySource = mergedDaily()
        renderDaily(dailySource!!)
    }

    /** 将彩云 v2.6 daily.astro 映射到首页卡片与逐小时图；v2.7 仅作为 v2.6 失败时的后备。 */
    private fun applyCaiyunSunAstronomy(astroList: List<Astro>) {
        if (astroList.isEmpty() || !::tvSunrise.isInitialized) return
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(System.currentTimeMillis())
        val ref = if (hourlyRefMillis != 0L) hourlyRefMillis else System.currentTimeMillis()
        val horizon = maxOf(hourlyTemps.size.toFloat(), 24f)
        val events = ArrayList<SunPoint>()
        val todayAstro = astroList.firstOrNull { (it.date ?: "").take(10) == todayKey }
            ?: astroList.firstOrNull()
        val tomorrowAstro = astroList.firstOrNull { (it.date ?: "").take(10) > todayKey }
        val yesterdayAstro = astroList.firstOrNull { (it.date ?: "").take(10) < todayKey }
        for (astro in astroList) {
            astro.sunrise?.time?.let { value ->
                parseAstroTime(astro.date, value)?.let { millis ->
                    val hours = (millis - ref) / 3600000f
                    if (hours in -1f..horizon) events.add(SunPoint(hours, formatClock(millis), true))
                }
            }
            astro.sunset?.time?.let { value ->
                parseAstroTime(astro.date, value)?.let { millis ->
                    val hours = (millis - ref) / 3600000f
                    if (hours in -1f..horizon) events.add(SunPoint(hours, formatClock(millis), false))
                }
            }
        }
        todayAstro?.sunrise?.time?.let { value ->
            parseAstroTime(todayAstro.date, value)?.let { millis ->
                sunriseMillis = millis
                tvSunrise.text = formatClock(millis)
            }
        }
        todayAstro?.sunset?.time?.let { value ->
            parseAstroTime(todayAstro.date, value)?.let { millis ->
                sunsetMillis = millis
                tvSunset.text = formatClock(millis)
            }
        }
        yesterdaySunsetMillis = yesterdayAstro?.sunset?.time?.let { value ->
            parseAstroTime(yesterdayAstro.date, value)
        } ?: sunsetMillis.takeIf { it > 0L }?.minus(24L * 3600000L) ?: 0L
        nextSunriseMillis = tomorrowAstro?.sunrise?.time?.let { value ->
            parseAstroTime(tomorrowAstro.date, value)
        } ?: sunriseMillis.takeIf { it > 0L }?.plus(24L * 3600000L) ?: 0L
        events.sortBy { it.hoursFromNow }
        updateSunCard()
        // 日出日落到位后重算昼夜并重新上报，避免 applyBackground 先于天文时间执行导致的白天误判
        applyBackground()
        hourlyChart.setSunEvents(events)
    }

    // 多日预报完全使用彩云主源；降级路径才会传入和风完整数据。
    private fun mergedDaily(): List<DailyForecast> {
        return primaryDaily
    }

    // 多日预报统一渲染：列表 + 折线图（最多 15 天）
    private fun renderDaily(input: List<DailyForecast>) {
        val list = input.filter { it.date.take(10) >= java.time.LocalDate.now().minusDays(1).toString() }
        dailyDisplayedDates = list.map {it.date.take(10)}
        dailyRows.clear()
        dailyListContainer.removeAllViews()

        val dayLabels = ArrayList<String>()
        val dateTexts = ArrayList<String>()
        val chartSkycons = ArrayList<String?>()
        val chartProbs = ArrayList<Double>()
        val chartHighs = ArrayList<Int?>()
        val chartLows = ArrayList<Int?>()

        // 按设置的单位换算后再显示/画条（内部数据始终是摄氏）
        val c = requireContext()
        fun show(v: Int?): Int? = v?.let { WeatherSettings.tempDisplay(c, it.toDouble())?.toInt() }

        // 所有温度条共用同一全局刻度，才能对比出每天的温差长短
        val validLows = list.mapNotNull { show(it.low) }
        val validHighs = list.mapNotNull { show(it.high) }
        var minTemp = validLows.minOrNull() ?: 0
        var maxTemp = validHighs.maxOrNull() ?: 40
        if (maxTemp <= minTemp) maxTemp = minTemp + 1
        // 前 2 天是历史天气（灰色显示），今天从 pastCount 开始
        val pastCount = list.takeWhile { isPastDate(it.date) }.size
        // 第 8 天起放进独立容器；数据少于 8 天时容器也要存在（空容器）
        dailyMoreContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
            visibility = View.GONE
        }
        var moreAdded = false

        for (i in 0 until list.size) {
            // 第 8 天起放进独立容器（先渲染完前 7 天再加容器，
            // 保证展开后顺序是 今天→未来，而不是反的）
            if (i == 7) {
                dailyListContainer.addView(dailyMoreContainer)
                moreAdded = true
            }
            val f = list.getOrNull(i)
            val day = dayText(f?.date)
            val dateText = monthDayText(f?.date)
            val skyValue = f?.skycon
            val prob = f?.precipProb?.toDouble() ?: 0.0
            dayLabels.add(day)
            dateTexts.add(dateText)
            chartSkycons.add(skyValue)
            chartProbs.add(prob)
            chartHighs.add(show(f?.high))
            chartLows.add(show(f?.low))

            dailyRows.add(
                buildDailyRow(
                    container = if (i < 7) dailyListContainer else dailyMoreContainer,
                    day = day,
                    forecastDate = f?.date?.take(10),
                    date = dateText,
                    skycon = skyValue,
                    low = show(f?.low),
                    high = show(f?.high),
                    minTemp = minTemp,
                    maxTemp = maxTemp,
                    currentTemp =
                    if (i == pastCount) lastTemp?.let { WeatherSettings.tempDisplay(c, it) }
                    else null,
                    today = i == pastCount,
                    past = i < pastCount,
                    missing = f == null
                )
            )
        }
        if (!moreAdded) {
            dailyListContainer.addView(dailyMoreContainer)
        }

        // 数据刷新后按当前展开状态恢复（不做动画）
        if (expandedDaily) {
            dailyMoreContainer.layoutParams!!.height = LinearLayout.LayoutParams.WRAP_CONTENT
            dailyMoreContainer.visibility = View.VISIBLE
        } else {
            dailyMoreContainer.layoutParams!!.height = 0
            dailyMoreContainer.visibility = View.GONE
        }
        dailyChart.setData(
            dayLabels, dateTexts, chartSkycons, chartProbs, chartHighs, chartLows, pastCount
        )
        updateDailyExpand(animate = false)
    }

    // 后台拉取 Open-Meteo 15 天预报，成功后按日期合并进多日预报卡片
    private fun fetchOpenMeteoDaily(
        lng: Double,
        lat: Double,
        fromRefresh: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        onComplete?.invoke(false)
        return
        Thread {
            var ok = false
            try {
                val list = OpenMeteo.fetchDaily(lat, lng, 15)
                if (list.isNotEmpty()) {
                    val act = activity
                    if (act != null) {
                        act.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            openMeteoDaily = list
                            dailySource = mergedDaily()
                            renderDaily(dailySource!!)
                        }
                    }
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            completeDataLoad(fromRefresh, onComplete, ok)
        }.start()
    }

    // 列表模式的一行：星期(日期) → 天气文字 → 天气图标 → 低温 + 渐变温度条 + 高温
    private fun buildDailyRow(
        container: LinearLayout,
        day: String,
        forecastDate: String?,
        date: String,
        skycon: String?,
        low: Int?,
        high: Int?,
        minTemp: Int,
        maxTemp: Int,
        currentTemp: Double?,
        today: Boolean,
        past: Boolean,
        missing: Boolean
    ): View {
        val row = LinearLayout(requireContext())
        row.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(60,0,0,0)),null,
            GradientDrawable().apply {setColor(Color.WHITE);cornerRadius=dp(8f).toFloat()})
        row.contentDescription = "${forecastDate.orEmpty()} $day ${skycon?.let {WeatherText.skyconToText(it)}.orEmpty()}"
        row.setOnClickListener { openForecastDetail(dailyCard, forecastDate) }
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        // 星期/日期缩小后行间距同步收窄
        row.setPadding(0, dp(4f), 0, dp(4f))
        // 历史天气整行降低透明度，视觉上置灰
        if (past) row.alpha = 0.55f

        // 1) 星期 + 日期
        val left = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                // 固定日期列宽：两位数字的日期（8月10日、8月20日）不会
                // 把天气文字列和图标列顶到右边，保证所有行的列都对齐
                dp(64f),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // 紧凑日期列让天气文字和图标整体左移，同时为温度范围条腾出连续宽度。
                marginEnd = dp(0f)
            }
        }
        // 星期与日期统一为折线图日期文字同款字号（12sp）
        left.addView(rowText(day, 12f, bold = today, dim = missing, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        left.addView(rowText(date, 12f, bold = false, dim = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 2) 天气文字（晴/多云/小雨…）
        val desc = skycon?.let { WeatherText.skyconToText(it) } ?: "--"
        val iconSize = dp(26f)
        val descTv = rowText(
            desc, 13f, bold = false, dim = missing,
            gravity = Gravity.CENTER
        )
        // 日期、天气文字、天气图标使用相同列宽，三列中心点等距。
        descTv.layoutParams = LinearLayout.LayoutParams(
            dp(64f), iconSize
        ).apply {
            gravity = Gravity.CENTER
        }

        // 3) 天气图标
        val icon = WeatherIconCache.load(requireContext(), skycon, iconSize.toFloat())
        val iconColumn = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64f), iconSize)
            addView(ImageView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                if (icon != null) setImageBitmap(icon)
            })
        }

        // 4) 低温 + 渐变温度条 + 高温
        val tempBlock = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.8f
            )
        }
        tempBlock.addView(
            rowText(
                if (low != null) "$low°" else "--",
                // 温度条两侧气温与第二列天气文字同字号（13sp），历史行由整行透明度保持淡化
                13f, bold = true, dim = missing,
                gravity = Gravity.END
            )
        )
        tempBlock.addView(
            TempBarView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, dp(16f), 1f
                )
                if (low != null && high != null) {
                    setRange(low, high, minTemp, maxTemp, if (today) currentTemp else null)
                }
            }
        )
        tempBlock.addView(
            rowText(
                if (high != null) "$high°" else "--",
                13f, bold = true, dim = missing,
                gravity = Gravity.START
            )
        )

        // 仅保留极小弹性间隔，把主要可用宽度交给温度范围条。
        val tempSpacer = View(requireContext())
        tempSpacer.layoutParams = LinearLayout.LayoutParams(0, 1, 0.02f)

        row.addView(left)
        row.addView(descTv)
        row.addView(iconColumn)
        row.addView(tempSpacer)
        row.addView(tempBlock)
        container.addView(row)
        return row
    }

    private fun rowText(
        text: String,
        sizeSp: Float,
        bold: Boolean,
        dim: Boolean = false,
        gravity: Int = Gravity.START,
        color: Int = Color.WHITE
    ): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = sizeSp
            includeFontPadding = false
            setTextColor(color)
            this.gravity = gravity
            setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
            if (dim) alpha = 0.5f
        }
    }

    // 切换 折线 / 列表 模式（高亮块左右滑动；底部按钮只属于列表模式）
    private fun setDailyMode(chart: Boolean, animate: Boolean = true) {
        dailyModeChart = chart
        dailyChartScroll.visibility = if (chart) View.VISIBLE else View.GONE
        dailyListContainer.visibility = if (chart) View.GONE else View.VISIBLE
        tvDailyMore.visibility = if (chart) View.GONE else View.VISIBLE
        val active = Color.WHITE
        // 未选中项：半透明白色文字，与浅色滑块风格统一
        val inactive = Color.argb(179, 255, 255, 255)
        tvModeChart.setTextColor(if (chart) active else inactive)
        tvModeList.setTextColor(if (chart) inactive else active)
        tvModeChart.setTypeface(null, if (chart) Typeface.BOLD else Typeface.NORMAL)
        tvModeList.setTypeface(null, if (chart) Typeface.NORMAL else Typeface.BOLD)
        if (animate) {
            modeHighlight.animate()
                .translationX(if (chart) 0f else modeTabWidth.toFloat())
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            modeHighlight.translationX = if (chart) 0f else modeTabWidth.toFloat()
        }
        updateDailyExpand(animate = false)
    }

    // 收起/展开：仅列表模式；第 8~15 天在"查看更多"下面做非线性展开/收回动画
    private fun updateDailyExpand(animate: Boolean = false) {
        if (dailyModeChart) {
            tvDailyMore.visibility = View.GONE
            // 折线图：一页 5 天，含过去 2 天 + 未来 15 天横向滑动查看
            dailyChart.setShowCount(dailyRows.size)
            return
        }
        tvDailyMore.visibility = View.VISIBLE
        tvDailyMore.text = if (expandedDaily) "收起" else "查看更多"
        if (animate) animateDailyMore(expandedDaily)
    }

    // 列表"查看更多/收起"：第 8~15 天容器高度从 0 非线性展开 / 收回
    private fun animateDailyMore(expand: Boolean) {
        if (!::dailyMoreContainer.isInitialized) return
        val more = dailyMoreContainer
        moreAnimator?.cancel()
        endDailyCardBlurFreeze()

        if (expand) {
            more.visibility = View.VISIBLE
            more.measure(
                View.MeasureSpec.makeMeasureSpec(
                    dailyListContainer.width.coerceAtLeast(1),
                    View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val full = more.measuredHeight.coerceAtLeast(1)
            beginDailyCardBlurFreeze(dailyCard.height + full)
            val anim = ValueAnimator.ofInt(0, full)
            anim.duration = 380
            anim.interpolator = DecelerateInterpolator()
            anim.addUpdateListener {
                val h = it.animatedValue as Int
                more.layoutParams!!.height = h
                more.requestLayout()
                more.alpha = 0.4f + 0.6f * it.animatedFraction
                more.post { applyDailyCardBlurFreeze() }
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    more.layoutParams!!.height = LinearLayout.LayoutParams.WRAP_CONTENT
                    more.requestLayout()
                    more.alpha = 1f
                    endDailyCardBlurFreeze()
                }
            })
            anim.start()
            moreAnimator = anim
        } else {
            val start = more.height.coerceAtLeast(1)
            beginDailyCardBlurFreeze(dailyCard.height)
            val anim = ValueAnimator.ofInt(start, 0)
            anim.duration = 300
            anim.interpolator = AccelerateInterpolator()
            anim.addUpdateListener {
                val h = it.animatedValue as Int
                more.layoutParams!!.height = h
                more.requestLayout()
                more.alpha = 1f - 0.6f * it.animatedFraction
                more.post { applyDailyCardBlurFreeze() }
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    more.layoutParams!!.height = 0
                    more.visibility = View.GONE
                    more.alpha = 1f
                    endDailyCardBlurFreeze()
                }
            })
            anim.start()
            moreAnimator = anim
        }
    }

    private fun monthDayText(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return "-"
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val date = fmt.parse(dateStr)!!
            val cal = Calendar.getInstance().apply { time = date }
            "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
        } catch (e: Exception) {
            "-"
        }
    }

    // 判断某天是否早于今天（用于历史天气置灰）
    private fun isPastDate(dateStr: String?): Boolean {
        if (dateStr.isNullOrBlank()) return false
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val date = fmt.parse(dateStr)!!.time
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            date < today
        } catch (e: Exception) {
            false
        }
    }

    private fun dayText(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return "-"
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val date = fmt.parse(dateStr)!!
            // 都按当天零点算，避免"明天凌晨减当前时间<24小时"被整除成 0 天
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val cal = Calendar.getInstance().apply { time = date }
            val diffDays =
                ((cal.timeInMillis - today.timeInMillis) / (24L * 60 * 60 * 1000)).toInt()
            when {
                diffDays == 0 -> "今天"
                diffDays == 1 -> "明天"
                diffDays == -1 -> "昨天"
                diffDays == -2 -> "前天"
                else -> when (cal.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> "周一"
                    Calendar.TUESDAY -> "周二"
                    Calendar.WEDNESDAY -> "周三"
                    Calendar.THURSDAY -> "周四"
                    Calendar.FRIDAY -> "周五"
                    Calendar.SATURDAY -> "周六"
                    else -> "周日"
                }
            }
        } catch (e: Exception) {
            "-"
        }
    }

    // 解析彩云返回的时间字符串为毫秒数
    private fun parseTime(s: String): Long? {
        return TimeUtil.parse(s)
    }

    // 日出日落时间可能是完整时间，也可能只有时分；都能解析
    private fun parseAstroTime(date: String?, time: String): Long? {
        parseTime(time)?.let { return it }
        // 彩云返回的 date 形如 "2026-08-12T00:00+08:00"，只取日期部分
        val dateStr = date?.take(10) ?: return null
        val candidates = listOf("${dateStr}T$time", "${dateStr}T$time:00")
        for (c in candidates) {
            try {
                return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).parse(c)?.time
            } catch (e: Exception) {
                // 尝试下一种格式
            }
        }
        return null
    }

    private fun formatClock(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(millis)

    private fun formatAlertTime(millis: Long): String =
        SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(millis)

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        // 毛玻璃截图质量：按此比例缩小后模糊再放大，越小越快、越糊
        private const val SHOT_QUALITY = 0.28f

        fun newInstance(page: CityPage, preview: Boolean = false): WeatherFragment {
            return WeatherFragment().apply {
                arguments = Bundle().apply {
                    putString("name", page.name)
                    putDouble("lng", page.lng)
                    putDouble("lat", page.lat)
                    putBoolean("isLoc", page.isCurrentLocation)
                    putString("detail", page.locationDetail)
                    putBoolean("preview", preview)
                }
            }
        }
    }
}
