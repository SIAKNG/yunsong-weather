package com.example.weatherapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

// 隐藏系统圆圈，保留 SwipeRefreshLayout 的触发与嵌套滚动能力；
// 内容跟手位移和松手回弹由此类单独驱动，避免与系统状态动画发生竞争。
class CloudSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    var triggerDistancePx: Int = 0
    var pullProgressListener: ((Float) -> Unit)? = null
    var contentSettledListener: (() -> Unit)? = null

    private var downY = 0f
    private var tracking = false
    private var contentTranslation = 0f
    private var returnAnimator: ValueAnimator? = null
    private var animationToken = 0
    private var circleNeutralized = false
    private var blockUpwardScroll = false
    private var releasePendingRefresh = false
    private val gestureSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }

    /** 供固定排版计算避开下拉与回弹过程，防止改高度造成视觉跳跃。 */
    fun isContentMotionActive(): Boolean =
        contentTranslation > 0.5f || returnAnimator?.isRunning == true

    /** 刷新任务完成后由宿主统一释放此前松手的内容，避免刷新尚未结束就提前回弹。 */
    fun finishRefreshMotion() {
        if (!releasePendingRefresh && contentTranslation <= 0.5f) return
        releasePendingRefresh = false
        springContentBack()
    }

    private fun contentView(): View? {
        for (i in 0 until childCount) {
            getChildAt(i).takeIf { it is ScrollView }?.let { return it }
        }
        return null
    }

    private fun setContentTranslation(offset: Float) {
        val target = contentView() ?: return
        animationToken++
        returnAnimator?.cancel()
        returnAnimator = null
        contentTranslation = offset
        target.translationY = offset
    }

    private fun springContentBack() {
        val target = contentView() ?: return
        if (returnAnimator?.isRunning == true) return
        val start = target.translationY
        if (start <= 0.5f) {
            contentTranslation = 0f
            target.translationY = 0f
            contentSettledListener?.invoke()
            return
        }
        val token = ++animationToken
        // 起始位移越大，回弹越有适度的收束时间；不使用过冲，避免刷新结束时内容闪回顶部。
        val duration = (280L + (start / triggerDistancePx.coerceAtLeast(1) * 145f).toLong())
            .coerceIn(280L, 430L)
        returnAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(2.15f)
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                contentTranslation = value
                target.translationY = value
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != animationToken) return
                    contentTranslation = 0f
                    target.translationY = 0f
                    returnAnimator = null
                    contentSettledListener?.invoke()
                }
            })
            start()
        }
    }

    private fun hideDefaultCircle() {
        if (circleNeutralized) return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ScrollView) continue
            child.alpha = 0f
            child.visibility = View.INVISIBLE
        }
        circleNeutralized = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        hideDefaultCircle()
    }

    override fun dispatchDraw(canvas: Canvas) {
        hideDefaultCircle()
        super.dispatchDraw(canvas)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downY = ev.y
            tracking = !canChildScrollUp()
            blockUpwardScroll = false
        }
        // 刷新未完成时固定在顶部：不把向上的拖动分发给 ScrollView，防止用户在
        // 刷新动画中把内容推走；向下手势仍会正常经过 SwipeRefreshLayout。
        if (isRefreshing && ev.actionMasked == MotionEvent.ACTION_MOVE) {
            val distance = ev.y - downY
            if (distance < -gestureSlop) blockUpwardScroll = true
            if (blockUpwardScroll) return true
        }
        val handled = super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (tracking && !isRefreshing) {
                val distance = ev.y - downY
                if (distance > 0f) {
                    val maxOffset = triggerDistancePx.coerceAtLeast(1) * 0.72f
                    // 阻尼跟手：与手指同向移动，但不会把内容拉出屏幕。
                    setContentTranslation(minOf(distance * 0.58f, maxOffset))
                    if (triggerDistancePx > 0) {
                        pullProgressListener?.invoke((distance / triggerDistancePx).coerceIn(0f, 1f))
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (tracking) {
                pullProgressListener?.invoke(-1f)
                // SwipeRefreshLayout 会在 super.dispatchTouchEvent 中同步触发刷新回调。
                // 已进入刷新时保留手势位移，等真实任务结束后再回弹。
                if (isRefreshing) releasePendingRefresh = true else springContentBack()
                tracking = false
            }
        }
        return handled
    }
}
