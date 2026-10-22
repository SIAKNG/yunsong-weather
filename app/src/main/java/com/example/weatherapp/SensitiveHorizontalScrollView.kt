package com.example.weatherapp

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs

// 更灵敏的水平滚动容器：
// 手指水平移动只要超过系统触摸阈值的一半就接管滑动，
// 并禁止外层 ViewPager2 / 垂直 ScrollView 抢走横向手势，
// 解决 24 小时预报、多日折线图"有时滑不动"的问题
class SensitiveHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var intercepting = false
    private var snapToPage = false

    // 多日折线图按整页吸附：静止时每页正好 5 列完整显示，
    // 最左/最右列不会被边界裁断
    fun setSnapToPage(enabled: Boolean) {
        snapToPage = enabled
    }

    override fun fling(velocityX: Int) {
        super.fling(velocityX)
        if (snapToPage) post { snapToNearestPage() }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (snapToPage) post { snapToNearestPage() }
            }
        }
        return handled
    }

    private fun snapToNearestPage() {
        val pageW = width
        if (pageW <= 0) return
        val contentW = getChildAt(0)?.width ?: width
        val maxScroll = maxOf(0, contentW - width)
        val target = Math.round(scrollX.toFloat() / pageW) * pageW
        smoothScrollTo(target.coerceIn(0, maxScroll), 0)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                intercepting = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!intercepting && abs(dx) > slop * 0.5f && abs(dx) > abs(dy) * 1.2f) {
                    intercepting = true
                    // 通知外层（ViewPager2、垂直 ScrollView）不要再抢这个手势
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> intercepting = false
        }
        return super.onInterceptTouchEvent(ev)
    }
}
