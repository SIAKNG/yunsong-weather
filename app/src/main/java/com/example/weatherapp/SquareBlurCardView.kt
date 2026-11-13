package com.example.weatherapp

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec

/** Keep metric cards at least square, with room for multiline readings and notes. */
class SquareBlurCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BlurCardView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredWidth == 0 || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) return
        val height = resolveSize(maxOf(measuredWidth, measuredHeight), heightMeasureSpec)
        if (height != measuredHeight) {
            // Re-measure the weighted spacer so the last note stays above the bottom padding.
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
}
