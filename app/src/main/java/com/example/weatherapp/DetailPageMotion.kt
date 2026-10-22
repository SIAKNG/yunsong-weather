package com.example.weatherapp

import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 详情页之间的内容层转场：根容器（背景）保持原地，只有详情内容左右滑动；
 * 顶部城市/标题文字独立固定，避免整页 Activity 平移时背景和顶部栏一起被带走。
 */
interface DetailPageMotionHost {
    fun animateDetailPageExit(delta: Int, onEnd: () -> Unit): Boolean
}

class DetailPageMotion(
    private val root: View,
    private val content: View,
    private val fixedHeader: View?,
    private val changingTitle: View
) {
    private var transitioning = false
    private var interactiveOffsetX = 0f

    init {
        // 顶部栏已被提升为根布局静态层；内容层不再需要反向补偿或解除裁剪。
    }

    fun prepareEnter(direction: Int, interactiveHandoff: Boolean = false) {
        if (direction == 0) return
        // 交接页的根层透明，旧页留在下方继续退出；两套详情内容可在中段并存。
        val originalBackground = if (interactiveHandoff) root.background else null
        if (interactiveHandoff) root.background = ColorDrawable(Color.TRANSPARENT)
        val ratio = if (interactiveHandoff) INTERACTIVE_ENTER_OFFSET_RATIO else ENTER_OFFSET_RATIO
        val startX = direction.sign() * travelDistance() * ratio
        content.translationX = startX
        fixedHeader?.translationX = 0f
        content.alpha = if (interactiveHandoff) 1f else 0.96f
        // 城市和箭头与上一页相同，保持不动；只让会变化的二级标题交叉淡入。
        changingTitle.alpha = if (interactiveHandoff) 0f else 1f
        root.post {
            if (!root.isAttachedToWindow) return@post
            animateContent(startX, 0f, DURATION_ENTER) {
                fixedHeader?.translationX = 0f
                content.alpha = 1f
                if (originalBackground != null) root.background = originalBackground
            }
            if (interactiveHandoff) changingTitle.animate().alpha(1f).setDuration(DURATION_ENTER).start()
        }
    }

    fun playExit(delta: Int, onEnd: () -> Unit): Boolean {
        if (transitioning) return true
        transitioning = true
        val endX = -delta.sign() * travelDistance()
        animateContent(interactiveOffsetX, endX, DURATION_EXIT) {
            onEnd()
        }
        return true
    }

    /**
     * 详情页非图表区域的手势进度。内容层直接使用手指的水平位移，而不是等松手后
     * 再播放一段固定动画；顶部地址、二级标题和箭头由反向补偿保持原位。
     */
    fun dragTo(offsetX: Float) {
        if (transitioning) return
        interactiveOffsetX = offsetX.coerceIn(-travelDistance(), travelDistance())
        applyContentPosition(interactiveOffsetX)
        val fraction = (kotlin.math.abs(interactiveOffsetX) / travelDistance()).coerceIn(0f, 1f)
        content.alpha = 1f - fraction * 0.16f
        // 地址与标题栏保持完整且固定；只有其下方详情内容跟随手指。
        changingTitle.alpha = 1f
    }

    /** 完成或取消正在跟手的详情页滑动。 */
    fun finishInteractiveDrag(delta: Int, commit: Boolean, onEnd: () -> Unit = {}) {
        if (transitioning) return
        val from = interactiveOffsetX
        if (!commit) {
            val duration = settleDuration(from, 0f)
            animateContent(from, 0f, duration) {
                interactiveOffsetX = 0f
                changingTitle.alpha = 1f
            }
            return
        }
        transitioning = true
        // 先启动透明的目标层，再让本页继续退出。两页在中段共同绘制，避免空白或硬切。
        onEnd()
        val endX = -delta.sign() * travelDistance()
        animateContent(from, endX, DURATION_EXIT) {}
    }

    private fun animateContent(from: Float, to: Float, duration: Long, onEnd: () -> Unit) {
        val fromAlpha = content.alpha
        val toAlpha = if (to == 0f) 1f else 0.84f
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val x = from + (to - from) * fraction
                applyContentPosition(x)
                // 与主页城市切换保持同一类轻量淡入淡出，不影响背景或固定顶部元素。
                content.alpha = fromAlpha + (toAlpha - fromAlpha) * fraction
            }
            doOnEnd(onEnd)
            start()
        }
    }

    private fun applyContentPosition(x: Float) {
        interactiveOffsetX = x
        content.translationX = x
        fixedHeader?.translationX = 0f
    }

    private fun settleDuration(from: Float, to: Float): Long {
        val fraction = (kotlin.math.abs(to - from) / travelDistance()).coerceIn(0f, 1f)
        return (DURATION_SETTLE_MIN + (DURATION_EXIT - DURATION_SETTLE_MIN) * fraction).toLong()
    }

    private fun travelDistance(): Float =
        (root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels).toFloat()

    private fun Int.sign(): Int = if (this < 0) -1 else 1

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    private companion object {
        const val DURATION_ENTER = 250L
        const val DURATION_EXIT = 210L
        const val DURATION_SETTLE_MIN = 90L
        const val ENTER_OFFSET_RATIO = 0.08f
        const val INTERACTIVE_ENTER_OFFSET_RATIO = 0.82f
    }
}
