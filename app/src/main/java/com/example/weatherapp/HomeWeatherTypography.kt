package com.example.weatherapp

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView

internal fun Resources.weatherSp(size: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, displayMetrics)

/** The homepage keeps compact chart and card geometry at the largest system font sizes. */
internal fun Resources.homeWeatherSp(size: Float): Float =
    minOf(weatherSp(size), size * displayMetrics.density * WEATHER_MAX_FONT_SCALE)

class HomeWeatherTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        val style = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.textSize), defStyleAttr, 0)
        try {
            val value = style.peekValue(0)
            if (value != null && value.type == TypedValue.TYPE_DIMENSION &&
                value.data and TypedValue.COMPLEX_UNIT_MASK == TypedValue.COMPLEX_UNIT_SP) {
                setWeatherSize(TypedValue.complexToFloat(value.data))
            }
        } finally {
            style.recycle()
        }
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
        ellipsize = null
    }

    internal fun setWeatherSize(size: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.homeWeatherSp(size))
    }
}
