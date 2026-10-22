package com.example.weatherapp

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors

/** 设置页内的关于页面：把数据使用与支持方式明确呈现，不再挤在系统对话框里。 */
class WeatherAboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        val dark = WeatherSettings.isDarkTheme(this)
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_weather_about)
        findViewById<View>(R.id.weatherAboutRoot).let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        findViewById<View>(R.id.btnWeatherAboutBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvWeatherAboutVersion).text = getString(R.string.app_version_label, BuildConfig.VERSION_NAME)
        findViewById<TextView>(R.id.tvIconSourceDescription).setText(
            if (BuildConfig.HAS_PRIVATE_ICON_ASSETS) R.string.icon_source_about_text
            else R.string.icon_source_public_about_text
        )
    }
}
