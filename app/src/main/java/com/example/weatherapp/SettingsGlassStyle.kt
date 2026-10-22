package com.example.weatherapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Explicitly scoped to settings/help; never changes homepage or forecast surfaces. */
internal object SettingsGlassStyle {
    /** PopupWindow is a separate window: capture the activity, not the popup root. */
    fun decoratePopup(view: View, backdrop: View) {
        decorate(view, backdrop = backdrop)
    }
    fun decorate(view: View, radiusDp: Float = 14f, action: Boolean = false, backdrop: View? = null) {
        val d = view.resources.displayMetrics.density
        val dark = WeatherSettings.isDarkTheme(view.context)
        val pad = intArrayOf(view.paddingLeft,view.paddingTop,view.paddingRight,view.paddingBottom)
        view.background = GradientDrawable().apply {
            cornerRadius = radiusDp * d
            setColor(ContextCompat.getColor(view.context,R.color.metric_detail_panel))
        }
        if (!dark || backdrop != null) DetailGlassSurface.install(view,radiusDp*d,backdrop).setStateTint(action,!view.isEnabled)
        view.foreground = WeatherFrostedBorderDrawable(d,radiusDp,dark)
        view.elevation = 4.5f*d
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        if (Build.VERSION.SDK_INT >= 28) {
            view.outlineAmbientShadowColor = Color.argb(if(dark) 70 else 45,42,49,61)
            view.outlineSpotShadowColor = Color.argb(if(dark) 40 else 18,42,49,61)
        }
        view.setPadding(pad[0],pad[1],pad[2],pad[3])
        view.tag = "settings_glass_surface"
        if(action && view is TextView) {
            view.setTextColor(ContextCompat.getColor(view.context,if(view.isEnabled) R.color.text_primary else R.color.text_secondary))
            view.alpha = 1f
        }
    }
    fun applyTree(root: View) {
        val bg = root.background as? GradientDrawable
        if(bg != null && bg.shape == GradientDrawable.RECTANGLE && (bg.cornerRadius > 0 || bg.cornerRadii != null)) {
            decorate(root,(bg.cornerRadii?.firstOrNull() ?: bg.cornerRadius)/root.resources.displayMetrics.density,root.isClickable && root is TextView)
        }
        if(root is ViewGroup) {
            // Only panel interiors / scroll content may reveal shadows. The page root must
            // keep clipping its ScrollView, otherwise rows paint over the fixed toolbar.
            if(root.tag == "settings_glass_surface" || root.parent is android.widget.ScrollView) {
                root.clipChildren = false
                root.clipToPadding = false
            }
            repeat(root.childCount) { applyTree(root.getChildAt(it)) }
        }
    }
}
