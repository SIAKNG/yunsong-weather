package com.example.weatherapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 八类天气详情的稳定循环顺序。 */
enum class DetailPage {
    AIR_QUALITY, UV, FEELS_LIKE, WIND, PRESSURE, VISIBILITY, HUMIDITY, SUN
}

data class DetailNavigationState(
    val cityName: String,
    /** 行政城市名用于生态环境部城市排名；详情标题仍可显示更精确的街区地址。 */
    val rankingCityName: String = cityName,
    val metricPoints: List<HourlyMetricPoint>,
    val yesterdayMetricPoints: List<HourlyMetricPoint>,
    /** 昨日值中由已标注补充源填入的指标。 */
    val historicalSupplementedKinds: Set<MetricKind> = emptySet(),
    val windDirection: String?,
    val lifeAdvice: List<LifeAdviceItem>,
    val airPoints: List<AirQualityPoint>,
    val currentAir: AirQualityPoint?,
    val sun: SunAstronomyData
)

/**
 * 当前详情会话的数据注册表。数据保留在进程内而不落盘；进程重建后各页面原有 Intent
 * 仍可正常显示，只是不能跨页切换，避免把天气数据或密钥写进静态缓存。
 */
object DetailNavigationStore {
    const val EXTRA_ID = "detail_navigation_id"
    private val sessions = ConcurrentHashMap<String, DetailNavigationState>()

    fun register(state: DetailNavigationState): String = UUID.randomUUID().toString().also {
        sessions[it] = state
    }

    fun get(intent: Intent): Pair<String, DetailNavigationState>? {
        val id = intent.getStringExtra(EXTRA_ID) ?: return null
        return sessions[id]?.let { id to it }
    }
}

object DetailNavigator {
    private val order = DetailPage.entries
    const val EXTRA_MOTION_DIRECTION = "detail_motion_direction"
    const val EXTRA_INTERACTIVE_HANDOFF = "detail_interactive_handoff"

    fun pageOf(kind: MetricKind): DetailPage = when (kind) {
        MetricKind.UV -> DetailPage.UV
        MetricKind.FEELS_LIKE -> DetailPage.FEELS_LIKE
        MetricKind.WIND -> DetailPage.WIND
        MetricKind.PRESSURE -> DetailPage.PRESSURE
        MetricKind.VISIBILITY -> DetailPage.VISIBILITY
        MetricKind.HUMIDITY -> DetailPage.HUMIDITY
    }

    fun metricOf(page: DetailPage): MetricKind? = when (page) {
        DetailPage.UV -> MetricKind.UV
        DetailPage.FEELS_LIKE -> MetricKind.FEELS_LIKE
        DetailPage.WIND -> MetricKind.WIND
        DetailPage.PRESSURE -> MetricKind.PRESSURE
        DetailPage.VISIBILITY -> MetricKind.VISIBILITY
        DetailPage.HUMIDITY -> MetricKind.HUMIDITY
        else -> null
    }

    fun intent(context: Context, page: DetailPage, id: String, state: DetailNavigationState): Intent {
        val target = when (page) {
            DetailPage.AIR_QUALITY -> AirQualityDetailActivity.createIntent(
                context, state.cityName, state.airPoints, state.currentAir, state.rankingCityName
            )
            DetailPage.SUN -> SunDetailActivity.createIntent(context, state.cityName, state.sun)
            else -> MetricDetailActivity.createIntent(
                context,
                metricOf(page) ?: MetricKind.UV,
                state.metricPoints,
                state.yesterdayMetricPoints,
                state.cityName,
                state.windDirection,
                LifeAdviceFactory.forMetric(state.lifeAdvice, metricOf(page) ?: MetricKind.UV),
                state.lifeAdvice,
                historicalSupplemented = state.historicalSupplementedKinds.contains(
                    metricOf(page) ?: MetricKind.UV
                )
            )
        }
        return target.putExtra(DetailNavigationStore.EXTRA_ID, id)
    }

    /**
     * 首页卡片专用的详情入口。详情 Activity 的常规主题是透明窗口，以便页内横滑同时
     * 保留前后内容层；但 ColorOS 会拒绝对透明目标窗口执行卡片无缝展开。这里通过
     * 不透明 activity-alias 首次打开同一个详情类，进入后所有页内跳转仍使用 [intent]。
     */
    fun cardEntryIntent(
        context: Context,
        page: DetailPage,
        id: String,
        state: DetailNavigationState
    ): Intent = intent(context, page, id, state).apply {
        setClass(context, cardEntryClass(page))
    }

    fun move(activity: Activity, from: DetailPage, delta: Int, animate: Boolean = true): Boolean {
        DetailContentPager.of(activity)?.let { return it.move(delta) }
        val (id, state) = DetailNavigationStore.get(activity.intent) ?: return false
        val target = step(from, delta)
        val launchTarget = {
            activity.startActivity(
                intent(activity, target, id, state)
                    .putExtra(EXTRA_MOTION_DIRECTION, if (delta < 0) -1 else 1)
                    .putExtra(EXTRA_INTERACTIVE_HANDOFF, !animate)
            )
            // 背景由当前 Activity 根容器保持静止，目标页只在自己的内容层完成入场动画。
            activity.overridePendingTransition(0, 0)
            if (animate) {
                activity.finish()
                activity.overridePendingTransition(0, 0)
            } else {
                // 让透明目标页与旧页共同绘制一个短转场周期；结束后才移除旧页。
                activity.window.decorView.postDelayed({
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.finish()
                        activity.overridePendingTransition(0, 0)
                    }
                }, 270L)
            }
            Unit
        }
        val motionHost = activity as? DetailPageMotionHost
        if (animate && motionHost != null) {
            motionHost.animateDetailPageExit(delta, launchTarget)
        } else {
            launchTarget()
        }
        return true
    }

    /** 供页面和单元测试共用的循环规则。 */
    fun step(from: DetailPage, delta: Int): DetailPage {
        val index = order.indexOf(from)
        return order[(index + delta).floorMod(order.size)]
    }

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

    private fun cardEntryClass(page: DetailPage): Class<out Activity> = when (page) {
        DetailPage.AIR_QUALITY -> AirQualityDetailCardEntryActivity::class.java
        DetailPage.SUN -> SunDetailCardEntryActivity::class.java
        else -> MetricDetailCardEntryActivity::class.java
    }
}
