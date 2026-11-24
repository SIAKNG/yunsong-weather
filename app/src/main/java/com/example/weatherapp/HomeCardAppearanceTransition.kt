package com.example.weatherapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import androidx.core.view.children

/** Commits the new geometry once. Peers then move from their old drawn positions on the render layer. */
internal class HomeCardAppearanceTransition {
    private var pending: ViewTreeObserver.OnPreDrawListener? = null
    private var root: View? = null
    private var animator: ValueAnimator? = null
    private val appearingCards = linkedMapOf<View, Float>()
    private var fadingCards = emptySet<View>()

    val isPending: Boolean get() = pending != null

    fun finish() {
        val listener = pending
        root?.viewTreeObserver?.takeIf { it.isAlive }?.let { observer ->
            listener?.let(observer::removeOnPreDrawListener)
        }
        pending = null
        root = null
        appearingCards.keys.forEach { it.alpha = 1f }
        appearingCards.clear()
        animator?.cancel()
    }

    fun setVisible(view: View, card: View, show: Boolean, align: () -> Boolean) {
        if (root === view && isPending) {
            stage(card, show)
            return
        }
        val siblings = (card.parent as? ViewGroup)?.children?.filter {
            it.visibility == View.VISIBLE && it.height > 0
        }?.toList().orEmpty()
        val oldPositions = siblings.associateWith { it.y }
        val unfinishedFades = fadingCards.associateWith { it.alpha }
        finish()

        unfinishedFades.forEach { (view, alpha) ->
            if (view !== card && view.visibility == View.VISIBLE) {
                view.alpha = alpha
                appearingCards[view] = alpha
            }
        }
        stage(card, show)
        root = view
        var aligned = false
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!aligned) {
                    aligned = true
                    if (align()) return false
                }
                view.viewTreeObserver.removeOnPreDrawListener(this)
                pending = null
                root = null
                val fading = appearingCards.toMap()
                fadingCards = fading.keys
                appearingCards.clear()
                val offsets = oldPositions.mapNotNull { (peer, oldY) ->
                    val offset = oldY - peer.top
                    if (peer.visibility == View.VISIBLE && offset != 0f) peer to offset else null
                }
                offsets.forEach { (peer, offset) -> peer.translationY = offset }
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 240L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { frame ->
                        val progress = frame.animatedValue as Float
                        fading.forEach { (card, start) -> card.alpha = start + (1f - start) * progress }
                        offsets.forEach { (peer, offset) -> peer.translationY = offset * (1f - progress) }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            fading.keys.forEach { it.alpha = 1f }
                            fadingCards = emptySet()
                            offsets.forEach { (peer, _) -> peer.translationY = 0f }
                            animator = null
                        }
                    })
                    start()
                }
                return true
            }
        }
        pending = listener
        view.viewTreeObserver.addOnPreDrawListener(listener)
        view.invalidate()
    }

    private fun stage(card: View, show: Boolean) {
        card.visibility = if (show) View.VISIBLE else View.GONE
        card.alpha = if (show) 0f else 1f
        if (show) appearingCards[card] = 0f else appearingCards.remove(card)
    }
}
