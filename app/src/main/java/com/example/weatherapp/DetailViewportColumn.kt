package com.example.weatherapp

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Lays out the detail's natural text first and fits the flexible charts to the safe viewport. */
internal class DetailViewportColumn @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    var viewportHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            forceLayout()
        }

    private data class Chart(val view: View, val preferred: Int, val minimum: Int)
    private val charts = mutableListOf<Chart>()
    private val originalMargins = mutableMapOf<View, Int>()

    override fun onFinishInflate() {
        super.onFinishInflate()
        findViewById<View>(R.id.metricDetailChart)?.let { fitChart(it, 180) }
        findViewById<View>(R.id.sunAltitudeChart)?.let { fitChart(it, 144) }
        findViewById<View>(R.id.airQualityGauge)?.let { fitChart(it, 116) }
        findViewById<View>(R.id.aqiHourlyChart)?.let { fitChart(it, 94) }
    }

    fun fitChart(view: View, minimumDp: Int) {
        val preferred = view.layoutParams.height
        require(preferred > 0)
        val minimum = (minimumDp * resources.displayMetrics.density).roundToInt()
        charts.add(Chart(view, preferred, minimum.coerceAtMost(preferred)))
    }

    override fun onViewRemoved(child: View) {
        originalMargins.remove(child)
        super.onViewRemoved(child)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val children = (0 until childCount).map(::getChildAt)
        children.forEach { child ->
            val params = child.layoutParams as LayoutParams
            val margin = originalMargins.getOrPut(child) { params.topMargin }
            if (margin != params.topMargin) {
                params.topMargin = margin
                child.forceLayout()
            }
        }
        charts.forEach { resize(it, it.preferred) }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (viewportHeight <= 0) return

        val visibleCharts = charts.filter { it.view.visibility != GONE }
        val deficit = (measuredHeight - viewportHeight).coerceAtLeast(0)
        val room = visibleCharts.sumOf { it.preferred - it.minimum }
        if (deficit > 0 && room > 0) {
            var remaining = deficit.coerceAtMost(room)
            var remainingRoom = room
            for (chart in visibleCharts) {
                val capacity = chart.preferred - chart.minimum
                val shrink = if (remainingRoom == 0) 0 else remaining * capacity / remainingRoom
                resize(chart, chart.preferred - shrink)
                remaining -= shrink
                remainingRoom -= capacity
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        var extra = viewportHeight - measuredHeight
        if (extra <= 0 || visibleCharts.isEmpty()) return
        // Give tall screens more plot area, then spread the rest between readable sections.
        val maxGrowth = (72 * resources.displayMetrics.density).roundToInt()
        val chartGrowth = (extra / (visibleCharts.size + 1)).coerceAtMost(maxGrowth)
        visibleCharts.forEach { resize(it, it.view.layoutParams.height + chartGrowth) }
        extra -= chartGrowth * visibleCharts.size
        var gaps = children.count { it.visibility != GONE } - 1
        for (child in children.filter { it.visibility != GONE }.drop(1)) {
            val share = extra / gaps--
            (child.layoutParams as LayoutParams).topMargin += share
            child.forceLayout()
            extra -= share
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun resize(chart: Chart, height: Int) {
        if (chart.view.layoutParams.height == height) return
        chart.view.layoutParams.height = height
        var view: View? = chart.view
        while (view != null && view !== this) {
            view.forceLayout()
            view = view.parent as? View
        }
    }
}
