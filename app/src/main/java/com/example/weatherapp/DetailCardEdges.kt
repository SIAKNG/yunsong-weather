package com.example.weatherapp

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup

/** Light material is applied only to detail roots, never homepage/preview/city cards. */
internal object DetailCardEdges {
    fun apply(view: View) {
        val background = view.background as? GradientDrawable
        if (view.tag != "chart_legend_swatch" && view.tag != "weather_segment_thumb" && view.tag != "daily_metric_capsule" && background != null && background.shape == GradientDrawable.RECTANGLE &&
            (background.cornerRadius > 0f || background.cornerRadii != null) && view !is FrostedActionButton) {
            val density = view.resources.displayMetrics.density
            val radius = background.cornerRadii?.firstOrNull() ?: background.cornerRadius
            val dark = WeatherSettings.isDarkTheme(view.context)
            // Semantic icon badges retain their status-colour fill.
            if (!dark && view !is LifeAdviceIconView) DetailGlassSurface.install(view, radius)
            view.foreground = WeatherFrostedBorderDrawable(density, radius / density, dark)
        }
        if (view is ViewGroup) repeat(view.childCount) { apply(view.getChildAt(it)) }
    }

}
