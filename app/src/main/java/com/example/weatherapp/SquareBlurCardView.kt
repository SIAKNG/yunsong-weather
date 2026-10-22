package com.example.weatherapp

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec

// 标准正方形圆角毛玻璃卡片：宽高保持 1:1，用于风力/湿度/气压/能见度四宫格
class SquareBlurCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BlurCardView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        if (w > 0) {
            // 用正方形高度重新测量子元素，weight 撑开的空间才能生效（说明文字贴底）
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, w)
        }
    }
}
