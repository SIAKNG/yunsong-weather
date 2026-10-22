package com.example.weatherapp

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 48 小时二级页的纯数据规则。把“选中小时”放在这里，保证横向滚动、顶部实时信息
 * 和图表高亮使用同一个索引，且可在不依赖 Android View 的单测中验证。
 */
internal object HourlyForecastDetailPolicy {
    const val POINT_COUNT = 48
    const val VISIBLE_HOUR_COUNT = 12
    /** 12 个小时列在 320dp 常见视口完整显示；两端恰好容纳选中态 24dp 条形。 */
    const val HOUR_STEP_DP = 26f
    const val EDGE_INSET_DP = 12f
    const val BAR_WIDTH_DP = 11f
    const val BAR_SELECTED_WIDTH_DP = 24f
    /** 顶部时间轴只在每六小时的整列上落字，避免在 12 列紧凑视口重复干扰读数。 */
    const val TIME_AXIS_INTERVAL_HOURS = 6
    /** 焦点列的数值需要显著大于普通列，但仍不挤占相邻 26dp 小时列。 */
    const val HIGHLIGHTED_METRIC_SCALE = 1.72f

    fun window(rows: List<ForecastHour>): List<ForecastHour> = rows.take(POINT_COUNT)

    fun initialIndex(points: List<ForecastHour>): Int =
        points.indexOfFirst { !it.isHistorical }.takeIf { it >= 0 } ?: 0

    /** 当内容区的小时列恰好居中时，[scrollX] 正好是 index × step。 */
    fun centeredIndex(scrollX: Int, stepPx: Float, lastIndex: Int): Int {
        if (lastIndex <= 0 || stepPx <= 0f) return 0
        return (scrollX / stepPx).roundToInt().coerceIn(0, lastIndex)
    }

    /**
     * 与多日详情一致的边缘行为：起始时高亮落在“当前”列，随滚动连续向右移动，
     * 到末端正好停在右内边距。这里是纯坐标规则，方便单测验证首末端不再被钉死在中心。
     */
    fun movingSelectionViewportX(
        scrollX: Int,
        maxScroll: Int,
        initialX: Float,
        viewportWidth: Int,
        edgeInset: Float
    ): Float {
        val right = (viewportWidth - edgeInset).coerceAtLeast(edgeInset)
        if (maxScroll <= 0) return initialX.coerceIn(edgeInset, right)
        val progress = (scrollX.toFloat() / maxScroll).coerceIn(0f, 1f)
        return initialX + (right - initialX) * progress
    }

    /** 与多日详情相同的 smoothstep 焦点权重，用于文字、图标和条形宽度连续过渡。 */
    fun selectionEmphasis(distanceColumns: Float): Float {
        val raw = (1f - kotlin.math.abs(distanceColumns)).coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw)
    }

    fun barWidthDp(emphasis: Float): Float =
        BAR_WIDTH_DP + (BAR_SELECTED_WIDTH_DP - BAR_WIDTH_DP) * emphasis.coerceIn(0f, 1f)

    fun metricLabelScale(emphasis: Float): Float =
        1f + (HIGHLIGHTED_METRIC_SCALE - 1f) * emphasis.coerceIn(0f, 1f)

    fun shouldShowTimeLabel(index: Int): Boolean =
        index >= 0 && index % TIME_AXIS_INTERVAL_HOURS == 0

    /** 常规刻度保持每六小时；手势当前高亮列则始终露出具体时刻。 */
    fun shouldRenderTimeLabel(index: Int, emphasis: Float): Boolean =
        shouldShowTimeLabel(index) || (index >= 0 && emphasis > .02f)

    /** 将单个指标值限制到它自己的图表轨道内，绝不能按小时列号改变纵向轨道。 */
    fun metricY(value: Double, low: Double, high: Double, graphTop: Float, graphBottom: Float): Float {
        val range = (high - low).coerceAtLeast(.01)
        val normalized = ((value - low) / range).coerceIn(0.0, 1.0)
        return (graphBottom - normalized * (graphBottom - graphTop)).toFloat()
    }

    /**
     * 48 小时预报比绝对日期更适合用相对日期阅读。传入参考时刻可让测试和跨日场景
     * 保持可预测；超过“后天”的兜底仅用于异常/过长输入，正常 48 小时窗口不会出现。
     */
    fun relativeTimeLabel(timeMillis: Long, referenceMillis: Long = System.currentTimeMillis()): String {
        fun dayStart(millis: Long): Long = Calendar.getInstance().run {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
        val target = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val dayOffset = ((dayStart(timeMillis) - dayStart(referenceMillis)) / (24 * 60 * 60 * 1000L)).toInt()
        val day = when (dayOffset) {
            0 -> "今天"
            1 -> "明天"
            2 -> "后天"
            else -> "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
        }
        return "$day${target.get(Calendar.HOUR_OF_DAY)}点左右"
    }

    /** 图表顶部的紧凑日期时刻：跨日后明确标注明日/后日，避免只看小时产生歧义。 */
    fun timeAxisLabel(timeMillis: Long, referenceMillis: Long = System.currentTimeMillis()): String {
        fun dayStart(millis: Long): Long = Calendar.getInstance().run {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
        val target = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val offset = ((dayStart(timeMillis) - dayStart(referenceMillis)) / (24 * 60 * 60 * 1000L)).toInt()
        val day = when (offset) {
            0 -> "今天"
            1 -> "明日"
            2 -> "后日"
            else -> "${target.get(Calendar.MONTH) + 1}/${target.get(Calendar.DAY_OF_MONTH)}"
        }
        return "$day ${String.format("%02d时", target.get(Calendar.HOUR_OF_DAY))}"
    }

    /** 同时兼容彩云 0–1 与和风 0–100 的降水概率。 */
    fun percent(value: Double?): Int? {
        val raw = value?.takeIf { it.isFinite() } ?: return null
        val normalized = if (raw in 0.0..1.0) raw * 100.0 else raw
        return normalized.roundToInt().coerceIn(0, 100)
    }
}
