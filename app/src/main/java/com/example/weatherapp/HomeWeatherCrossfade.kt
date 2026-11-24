package com.example.weatherapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/** Replaces visible weather readings in place while keeping their cards fully opaque. */
internal class HomeWeatherCrossfade {
    private data class Reading(
        val label: TextView,
        val parent: ViewGroup,
        val text: String,
        val previous: BitmapDrawable,
        val originalAlpha: Float
    )

    private var animation: ValueAnimator? = null
    private var cancelPendingStart: (() -> Unit)? = null
    private var resetReadings: (() -> Unit)? = null

    fun finish() {
        cancelPendingStart?.invoke()
        cancelPendingStart = null
        animation?.cancel()
        resetReadings?.invoke()
        resetReadings = null
    }

    fun render(labels: List<TextView>, update: () -> Unit) {
        finish()
        val visibleRect = Rect()
        val previousReadings = labels.mapNotNull { label ->
            val parent = label.parent as? ViewGroup ?: return@mapNotNull null
            if (!label.isShown || !label.getGlobalVisibleRect(visibleRect) || label.width <= 0 || label.height <= 0 ||
                label.text.isNullOrBlank() || label.alpha <= 0f
            ) return@mapNotNull null

            val bitmap = Bitmap.createBitmap(label.width, label.height, Bitmap.Config.ARGB_8888)
            label.draw(Canvas(bitmap))
            val old = BitmapDrawable(label.resources, bitmap).apply {
                setBounds(label.left, label.top, label.right, label.bottom)
                alpha = (255 * label.alpha).toInt()
            }
            Reading(label, parent, label.text.toString(), old, label.alpha)
        }

        update()

        val changed = previousReadings.filter { it.label.isShown && it.label.text.toString() != it.text }
        if (changed.isEmpty()) return
        changed.forEach {
            it.parent.overlay.add(it.previous)
            it.label.alpha = 0f
        }
        resetReadings = {
            changed.forEach {
                it.parent.overlay.remove(it.previous)
                it.label.alpha = it.originalAlpha
            }
        }

        // A fresh bundle can request a layout. Start the animation after its first drawn frame so
        // work in that traversal cannot consume the fade before the user sees it.
        val anchor = changed.first().parent
        val observer = anchor.viewTreeObserver
        var posted = false
        val start = Runnable {
            cancelPendingStart?.invoke()
            cancelPendingStart = null
            if (!anchor.isAttachedToWindow) {
                finish()
                return@Runnable
            }
            animate(changed)
        }
        val listener = ViewTreeObserver.OnDrawListener {
            if (!posted) {
                posted = true
                anchor.post(start)
            }
        }
        cancelPendingStart = {
            if (observer.isAlive) observer.removeOnDrawListener(listener)
            anchor.removeCallbacks(start)
        }
        observer.addOnDrawListener(listener)
        anchor.invalidate()
    }

    private fun animate(changed: List<Reading>) {
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                changed.forEach {
                    it.label.alpha = it.originalAlpha * progress
                    it.previous.alpha = (255 * it.originalAlpha * (1f - progress)).toInt()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    resetReadings?.invoke()
                    resetReadings = null
                    animation = null
                }
            })
            start()
        }
    }
}
