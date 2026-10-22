package com.example.weatherapp

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 将二级页的导航标题从可横移的 ScrollView 内容中提升为根布局静态层。
 * 这样手势中的内容边缘不会覆盖标题、城市文字或左右箭头。
 */
object DetailHeaderOverlay {
    fun promote(root: ViewGroup, scroll: DetailScrollView, header: View) {
        val parent = header.parent as? ViewGroup ?: return
        parent.removeView(header)
        val horizontal = dp(root, 20)
        val top = dp(root, 4)
        root.addView(
            header,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                leftMargin = horizontal
                rightMargin = horizontal
                topMargin = top
            }
        )
        root.post {
            // 内容从固定标题栏下方开始滚动，留出少量自然间距。
            scroll.setPadding(
                scroll.paddingLeft,
                header.height + top + dp(root, 4),
                scroll.paddingRight,
                scroll.paddingBottom
            )
            scroll.clipToPadding = false
        }
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()
}
