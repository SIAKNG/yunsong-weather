package com.example.weatherapp

import android.content.Context
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Locale

/** 日出日落的全天详情：天气源日出日落 + 本地太阳位置算法补全晨昏数据。 */
open class SunDetailActivity : AppCompatActivity(), DetailPageMotionHost {
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        DetailCardEdges.apply(findViewById(android.R.id.content))
    }
    private lateinit var pageMotion: DetailPageMotion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        val dark = WeatherSettings.isDarkTheme(this)
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_sun_detail)
        val root = findViewById<View>(R.id.sunDetailRoot)
        root.let {
            val rootView = it
            OplusViewSeamlessNavigator.finishWhenUserInteracts(root)
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val sunScroll = findViewById<DetailScrollView>(R.id.sunDetailScroll)
        DetailHeaderOverlay.promote(root as android.view.ViewGroup, sunScroll, findViewById(R.id.sunDetailHeader))
        pageMotion = DetailPageMotion(root, sunScroll, null, findViewById(R.id.tvSunDetailTitle))
        pageMotion.prepareEnter(
            intent.getIntExtra(DetailNavigator.EXTRA_MOTION_DIRECTION, 0),
            intent.getBooleanExtra(DetailNavigator.EXTRA_INTERACTIVE_HANDOFF, false)
        )
        findViewById<View>(R.id.btnSunDetailBack).setOnClickListener {
            if (!DetailNavigator.move(this, DetailPage.SUN, -1)) finish()
        }
        findViewById<View>(R.id.btnSunDetailNext).setOnClickListener {
            DetailNavigator.move(this, DetailPage.SUN, 1)
        }
        val content = SunDetailContent(this, intent, root)
        content.bind(dark)
        DetailContentPager.install(
            this, root as android.widget.FrameLayout, sunScroll,
            findViewById(R.id.sunDetailHeader), findViewById(R.id.tvSunDetailTitle),
            DetailPage.SUN, content.chart, savedInstanceState
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        DetailContentPager.of(this)?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun animateDetailPageExit(delta: Int, onEnd: () -> Unit): Boolean =
        pageMotion.playExit(delta, onEnd)

    companion object {
        const val EXTRA_CITY = "sun_city"
        const val EXTRA_SUNRISE = "sun_sunrise"
        const val EXTRA_SUNSET = "sun_sunset"
        const val EXTRA_YESTERDAY_SUNSET = "sun_yesterday_sunset"
        const val EXTRA_NEXT_SUNRISE = "sun_next_sunrise"
        const val EXTRA_LAT = "sun_lat"
        const val EXTRA_LNG = "sun_lng"
        const val EXTRA_SOURCE = "sun_source"
        const val EXTRA_SKYCON = "sun_skycon"

        fun createIntent(context: Context, city: String, data: SunAstronomyData): Intent =
            Intent(context, SunDetailActivity::class.java).apply {
                putExtra(EXTRA_CITY, city)
                putExtra(EXTRA_SUNRISE, data.sunriseMillis)
                putExtra(EXTRA_SUNSET, data.sunsetMillis)
                putExtra(EXTRA_YESTERDAY_SUNSET, data.yesterdaySunsetMillis)
                putExtra(EXTRA_NEXT_SUNRISE, data.nextSunriseMillis)
                putExtra(EXTRA_LAT, data.latitude)
                putExtra(EXTRA_LNG, data.longitude)
                putExtra(EXTRA_SOURCE, data.sourceName)
                putExtra(EXTRA_SKYCON, data.skycon)
            }
    }
}
