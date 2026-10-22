package com.example.weatherapp

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 二级页的内容区横滑切换器。图表仍完整接管触摸以选择时间点；其余区域一旦确认
 * 横向拖动，详情内容由 [DetailContentPager] 逐帧跟随手指移动，地址保持固定。
 */
object DetailSwipeNavigator {
    fun attach(
        scroll: DetailScrollView,
        excluded: () -> List<View>,
        motion: DetailPageMotion,
        onHorizontalSwipe: (Int) -> Unit
    ) {
        scroll.setHorizontalSwipeHandler(
            excluded = excluded,
            onProgress = motion::dragTo,
            onRelease = { delta, commit ->
                motion.finishInteractiveDrag(delta, commit) {
                    // 当前页已经随手势滑离屏幕，目标页直接进场，不能重复播放旧页退出动画。
                    onHorizontalSwipe(delta)
                }
            }
        )
    }
}

private fun View.hitRaw(rawX: Float, rawY: Float): Boolean {
    if (!isShown) return false
    val location = IntArray(2)
    getLocationOnScreen(location)
    return rawX >= location[0] && rawX <= location[0] + width &&
        rawY >= location[1] && rawY <= location[1] + height
}

/** 在拦截阶段判定横滑，避免子 View 吃掉 MOVE 后只在 ACTION_UP 才触发切页。 */
class DetailScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    init {
        // 所有详情页均使用这个垂直容器；保留上下滑动，但不显示右侧系统滚动条。
        isVerticalScrollBarEnabled = false
    }

    private var downX = 0f
    private var downY = 0f
    private var startedInExcluded = false
    private var horizontalDrag = false
    private var excluded: (() -> List<View>)? = null
    private var onProgress: ((Float) -> Unit)? = null
    private var onRelease: ((Int, Boolean) -> Unit)? = null
    private val slop get() = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    fun setHorizontalSwipeHandler(
        excluded: () -> List<View>,
        onProgress: (Float) -> Unit,
        onRelease: (Int, Boolean) -> Unit
    ) {
        this.excluded = excluded
        this.onProgress = onProgress
        this.onRelease = onRelease
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recordDown(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (beginHorizontalDrag(event)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (horizontalDrag) return true
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // ScrollView 自己成为触摸目标时，后续 MOVE 不会重新走 onInterceptTouchEvent；
            // 这里同步记录起点，确保两条事件分发路径都能进入跟手逻辑。
            recordDown(event)
            return super.onTouchEvent(event)
        }
        if (!horizontalDrag && event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (beginHorizontalDrag(event)) {
                parent?.requestDisallowInterceptTouchEvent(true)
            } else {
                return super.onTouchEvent(event)
            }
        }
        if (!horizontalDrag) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                onProgress?.invoke(dx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.rawX - downX
                val direction = if (dx < 0f) 1 else -1
                // 与主页城市页相同：短拖动回弹，达到约 18% 屏宽即提交到相邻详情页。
                val commit = event.actionMasked == MotionEvent.ACTION_UP &&
                    abs(dx) >= width.coerceAtLeast(1) * 0.18f
                onRelease?.invoke(direction, commit)
                horizontalDrag = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun recordDown(event: MotionEvent) {
        downX = event.rawX
        downY = event.rawY
        startedInExcluded = excluded?.invoke()?.any { it.hitRaw(downX, downY) } == true
        horizontalDrag = false
    }

    private fun beginHorizontalDrag(event: MotionEvent): Boolean {
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        if (startedInExcluded || horizontalDrag || abs(dx) <= slop || abs(dx) <= abs(dy) * 1.15f) {
            return false
        }
        horizontalDrag = true
        onProgress?.invoke(dx)
        return true
    }

}
