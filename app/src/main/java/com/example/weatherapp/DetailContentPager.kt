package com.example.weatherapp

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Keep the original card-entry window alive: only its live content moves, never a second Activity. */
internal class DetailContentPager private constructor(
    private val activity: Activity,
    private val root: FrameLayout,
    private val header: View,
    private val title: TextView,
    private val sessionId: String,
    private val state: DetailNavigationState,
    initial: DetailPage,
    scroll: DetailScrollView,
    chart: View
) {
    private data class Page(val scroll: DetailScrollView, val chart: View, val title: String)
    private val pages = mutableMapOf(initial to Page(scroll, chart, title.text.toString()))
    var currentPage = initial
        private set
    internal val currentScroll get() = pages.getValue(currentPage).scroll
    private var target: DetailPage? = null
    private var offset = 0f
    private var settling: ValueAnimator? = null
    private var entryResolved = false

    init {
        attach(pages.getValue(initial))
        // Bind both neighbours before the first gesture; no API requests or Activity launches on MOVE.
        root.post { if (!activity.isDestroyed) prewarm() }
    }

    private fun prewarm() {
        page(DetailNavigator.step(currentPage, -1))
        page(DetailNavigator.step(currentPage, 1))
    }

    private fun page(key: DetailPage): Page = pages.getOrPut(key) {
        val metric = DetailNavigator.metricOf(key)
        val layout = when (key) {
            DetailPage.AIR_QUALITY -> R.layout.activity_air_quality_detail
            DetailPage.SUN -> R.layout.activity_sun_detail
            else -> R.layout.activity_metric_detail
        }
        val inflated = LayoutInflater.from(activity).inflate(layout, root, false) as FrameLayout
        val scrollId: Int
        val headerId: Int
        val titleId: Int
        when (key) {
            DetailPage.AIR_QUALITY -> { scrollId = R.id.airQualityDetailScroll; headerId = R.id.airQualityDetailHeader; titleId = R.id.tvAirQualityDetailTitle }
            DetailPage.SUN -> { scrollId = R.id.sunDetailScroll; headerId = R.id.sunDetailHeader; titleId = R.id.tvSunDetailTitle }
            else -> { scrollId = R.id.metricDetailScroll; headerId = R.id.metricDetailHeader; titleId = R.id.tvMetricDetailTitle }
        }
        val dataIntent = DetailNavigator.intent(activity, key, sessionId, state)
        val dark = WeatherSettings.isDarkTheme(activity)
        val chart = when {
            metric != null -> MetricDetailContent(activity, dataIntent, inflated).apply { bind(dark) }.chart
            key == DetailPage.AIR_QUALITY -> AirQualityDetailContent(activity, dataIntent, inflated).apply { bind(dark) }.chart
            else -> SunDetailContent(activity, dataIntent, inflated).apply { bind(dark) }.chart
        }
        val pageTitle = inflated.findViewById<TextView>(titleId).text.toString()
        val hiddenHeader = inflated.findViewById<View>(headerId)
        (hiddenHeader.parent as ViewGroup).removeView(hiddenHeader)
        val content = inflated.findViewById<DetailScrollView>(scrollId)
        inflated.removeView(content)
        content.setPadding(currentScroll.paddingLeft, currentScroll.paddingTop, currentScroll.paddingRight, currentScroll.paddingBottom)
        DetailCardEdges.apply(content)
        Page(content, chart, pageTitle).also(::attach)
    }

    private fun attach(page: Page) {
        page.scroll.setHorizontalSwipeHandler(
            excluded = { listOf(page.chart) },
            onProgress = ::drag,
            onRelease = { delta, commit -> release(delta, commit) }
        )
    }

    private fun showTarget(delta: Int) {
        val desired = DetailNavigator.step(currentPage, delta)
        if (target != desired) {
            target?.let { root.removeView(page(it).scroll) }
            target = desired
            val next = page(desired).scroll
            next.setPadding(currentScroll.paddingLeft, currentScroll.paddingTop, currentScroll.paddingRight, currentScroll.paddingBottom)
            root.addView(next, 0, FrameLayout.LayoutParams(-1, -1))
            header.bringToFront()
        }
    }

    private fun render(dx: Float) {
        offset = dx
        val width = currentScroll.width.coerceAtLeast(1).toFloat()
        val fraction = (abs(dx) / width).coerceIn(0f, 1f)
        currentScroll.translationX = dx
        currentScroll.alpha = 1f - .16f * fraction
        target?.let { key ->
            page(key).scroll.translationX = dx + if (dx <= 0f) width else -width
            page(key).scroll.alpha = .84f + .16f * fraction
            title.text = if (fraction < .5f) pages.getValue(currentPage).title else page(key).title
            title.alpha = abs(1f - 2f * fraction)
        }
    }

    private fun drag(dx: Float) {
        if (settling != null) return
        // The ScrollView owns this gesture, so the root's interaction listener never sees it.
        // End any in-flight OEM entry snapshot before moving live content; keep the same return window.
        if (target == null) finishEntryIfRunning()
        showTarget(if (dx < 0f) 1 else -1)
        render(dx.coerceIn(-currentScroll.width.toFloat(), currentScroll.width.toFloat()))
    }

    private fun release(delta: Int, commit: Boolean) {
        if (settling != null) return
        if (target == null) return
        showTarget(delta)
        settle(commit)
    }

    private fun settle(commit: Boolean) {
        val destination = if (!commit) 0f else if (offset <= 0f) -currentScroll.width.toFloat() else currentScroll.width.toFloat()
        val animator = ValueAnimator.ofFloat(offset, destination)
        settling = animator
        animator.duration = (100L + 130L * (abs(destination - offset) / currentScroll.width.coerceAtLeast(1)).coerceIn(0f, 1f)).toLong()
        animator.interpolator = DecelerateInterpolator(1.4f)
        animator.addUpdateListener { render(it.animatedValue as Float) }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (settling !== animator) return
                val next = target ?: return
                if (commit) {
                    root.removeView(currentScroll)
                    currentPage = next
                } else root.removeView(page(next).scroll)
                currentScroll.translationX = 0f
                currentScroll.alpha = 1f
                title.text = pages.getValue(currentPage).title
                title.alpha = 1f
                target = null
                offset = 0f
                settling = null
                root.post { if (!activity.isDestroyed) prewarm() }
            }
        })
        animator.start()
    }

    fun move(delta: Int): Boolean {
        if (settling != null) return true
        if (target == null) finishEntryIfRunning()
        showTarget(delta)
        // Zero has no direction, seed a subpixel displacement on the intended side.
        render(if (delta > 0) -.01f else .01f)
        settle(true)
        return true
    }

    private fun finishEntryIfRunning() {
        if (entryResolved) return
        entryResolved = true
        val started = activity.intent.getLongExtra(OplusViewSeamlessNavigator.EXTRA_ENTRY_STARTED, 0L)
        // Never stop a global OEM animation belonging to another window or a finished entry.
        if (started > 0L && android.os.SystemClock.uptimeMillis() - started in 0L..800L) {
            OplusViewSeamlessNavigator.finishRunningAnimation()
        }
    }

    fun saveState(out: Bundle) { out.putString(SAVED_PAGE, currentPage.name) }

    companion object {
        private const val SAVED_PAGE = "detail_live_page"
        private val instances = WeakHashMap<Activity, WeakReference<DetailContentPager>>()
        fun of(activity: Activity): DetailContentPager? = instances[activity]?.get()
        fun install(activity: Activity, root: FrameLayout, scroll: DetailScrollView, header: View,
                    title: TextView, initial: DetailPage, chart: View, saved: Bundle?) {
            val (id, state) = DetailNavigationStore.get(activity.intent) ?: return
            val pager = DetailContentPager(activity, root, header, title, id, state, initial, scroll, chart)
            instances[activity] = WeakReference(pager)
            val restored = saved?.getString(SAVED_PAGE)?.let { value -> DetailPage.entries.find { it.name == value } }
            if (restored != null && restored != initial) root.post {
                root.removeView(pager.currentScroll)
                val replacement = pager.page(restored)
                root.addView(replacement.scroll, 0, FrameLayout.LayoutParams(-1, -1))
                pager.currentPage = restored
                title.text = replacement.title
                header.bringToFront()
            }
        }
    }
}
