package com.example.weatherapp

/**
 * 指示条的高亮点与留白必须使用同一份几何数据：动画期间只移动 marker，
 * 两侧轨道的缺口也会随 marker 一起移动，避免出现“点已移动、留白仍留在原处”的断裂感。
 */
internal data class IndicatorTrackSegments(
    val markerX: Float,
    val leftEnd: Float,
    val rightStart: Float
)

internal object IndicatorTrackGeometry {
    fun normalized(value: Double, maximum: Double): Float =
        (value / maximum).toFloat().coerceIn(0f, 1f)

    /**
     * Marker 的圆心不会越出可见轨道；[gapRadius] 是随高亮点移动的留白半径。
     */
    fun segments(
        left: Float,
        right: Float,
        markerRadius: Float,
        gapRadius: Float,
        progress: Float
    ): IndicatorTrackSegments {
        val safeProgress = progress.coerceIn(0f, 1f)
        val markerLeft = left + markerRadius
        val markerRight = right - markerRadius
        val markerX = if (markerRight > markerLeft) {
            markerLeft + (markerRight - markerLeft) * safeProgress
        } else {
            (left + right) / 2f
        }
        return IndicatorTrackSegments(
            markerX = markerX,
            leftEnd = (markerX - gapRadius).coerceAtLeast(left),
            rightStart = (markerX + gapRadius).coerceAtMost(right)
        )
    }

    /**
     * 体感条以实际温度所在中点为 0；-range 映射到最左端，+range 映射到最右端。
     */
    fun feelsLikeProgress(delta: Double, range: Double): Float =
        (0.5f + (delta / range / 2.0).toFloat()).coerceIn(0f, 1f)
}
