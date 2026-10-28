package com.example.weatherapp

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

/** Draw only weather layers, without ever hiding or changing alpha on a live pager/Fragment. */
internal object WeatherBackgroundSnapshot {
    fun draw(root: View, canvas: Canvas): Boolean {
        val origin=IntArray(2);root.getLocationInWindow(origin)
        var drawn=false
        fun visit(view: View) {
            if(!view.isShown || view.alpha<=0f) return
            if(view is WeatherBackgroundView) {
                val location=IntArray(2);view.getLocationInWindow(location)
                val saved=canvas.save()
                canvas.translate((location[0]-origin[0]).toFloat(),(location[1]-origin[1]).toFloat())
                val layer=if(view.alpha<1f) canvas.saveLayerAlpha(0f,0f,view.width.toFloat(),view.height.toFloat(),(view.alpha*255).toInt()) else -1
                view.draw(canvas)
                if(layer>=0) canvas.restoreToCount(layer)
                canvas.restoreToCount(saved);drawn=true
            } else if(view is ViewGroup) repeat(view.childCount) {visit(view.getChildAt(it))}
        }
        visit(root)
        return drawn
    }
}
