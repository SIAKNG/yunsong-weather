package com.example.weatherapp

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** 空气质量专属二级页：AQI 半圆仪表、污染物网格和逐小时趋势。 */
open class AirQualityDetailActivity : AppCompatActivity(), DetailPageMotionHost {
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        DetailCardEdges.apply(findViewById(android.R.id.content))
    }

    private lateinit var pageMotion: DetailPageMotion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        val darkTheme = WeatherSettings.isDarkTheme(this)
        enableEdgeToEdge(
            statusBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_air_quality_detail)
        val root = findViewById<View>(R.id.airQualityDetailRoot)
        root.let {
            val rootView = it
            OplusViewSeamlessNavigator.finishWhenUserInteracts(root)
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val airScroll = findViewById<DetailScrollView>(R.id.airQualityDetailScroll)
        DetailHeaderOverlay.promote(root as android.view.ViewGroup, airScroll, findViewById(R.id.airQualityDetailHeader))
        pageMotion = DetailPageMotion(root, airScroll, null, findViewById(R.id.tvAirQualityDetailTitle))
        pageMotion.prepareEnter(
            intent.getIntExtra(DetailNavigator.EXTRA_MOTION_DIRECTION, 0),
            intent.getBooleanExtra(DetailNavigator.EXTRA_INTERACTIVE_HANDOFF, false)
        )
        findViewById<View>(R.id.btnAirQualityBack).setOnClickListener {
            if (!DetailNavigator.move(this, DetailPage.AIR_QUALITY, -1)) finish()
        }
        findViewById<View>(R.id.btnAirQualityNext).setOnClickListener {
            DetailNavigator.move(this, DetailPage.AIR_QUALITY, 1)
        }
        val content = AirQualityDetailContent(this, intent, root)
        content.bind(darkTheme)
        DetailContentPager.install(
            this, root as android.widget.FrameLayout, airScroll,
            findViewById(R.id.airQualityDetailHeader), findViewById(R.id.tvAirQualityDetailTitle),
            DetailPage.AIR_QUALITY, content.chart, savedInstanceState
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        DetailContentPager.of(this)?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun animateDetailPageExit(delta: Int, onEnd: () -> Unit): Boolean =
        pageMotion.playExit(delta, onEnd)

    companion object {
        const val EXTRA_CITY = "air_quality_city"
        const val EXTRA_TIMES = "air_quality_times"
        const val EXTRA_AQI = "air_quality_aqi"
        const val EXTRA_PM25 = "air_quality_pm25"
        const val EXTRA_PM10 = "air_quality_pm10"
        const val EXTRA_NO2 = "air_quality_no2"
        const val EXTRA_O3 = "air_quality_o3"
        const val EXTRA_CO = "air_quality_co"
        const val EXTRA_SO2 = "air_quality_so2"
        const val EXTRA_CURRENT_AQI = "air_quality_current_aqi"
        const val EXTRA_CURRENT_TIME = "air_quality_current_time"
        const val EXTRA_CURRENT_PM25 = "air_quality_current_pm25"
        const val EXTRA_CURRENT_PM10 = "air_quality_current_pm10"
        const val EXTRA_CURRENT_NO2 = "air_quality_current_no2"
        const val EXTRA_CURRENT_O3 = "air_quality_current_o3"
        const val EXTRA_CURRENT_CO = "air_quality_current_co"
        const val EXTRA_CURRENT_SO2 = "air_quality_current_so2"
        const val EXTRA_SOURCE = "air_quality_source"
        const val EXTRA_RANKING_CITY = "air_quality_ranking_city"

        fun createIntent(
            context: Context,
            city: String,
            points: List<AirQualityPoint>,
            current: AirQualityPoint?,
            rankingCity: String = city
        ): Intent {
            // 用实时权威快照替换趋势中最接近“现在”的点；缺少的污染物继续使用
            // 趋势补充值，保证曲线连续且首页/详情当前数据一致。
            val ordered = AirQualitySnapshotPolicy.mergeCurrentIntoTrend(points, current)
            fun arrayOf(selector: (AirQualityPoint) -> Double?) =
                DoubleArray(ordered.size) { selector(ordered[it]) ?: Double.NaN }
            return Intent(context, AirQualityDetailActivity::class.java).apply {
                putExtra(EXTRA_CITY, city)
                putExtra(EXTRA_TIMES, ordered.map { it.timeMillis }.toLongArray())
                putExtra(EXTRA_AQI, arrayOf { it.aqi })
                putExtra(EXTRA_PM25, arrayOf { it.pm25 })
                putExtra(EXTRA_PM10, arrayOf { it.pm10 })
                putExtra(EXTRA_NO2, arrayOf { it.no2 })
                putExtra(EXTRA_O3, arrayOf { it.o3 })
                putExtra(EXTRA_CO, arrayOf { it.co })
                putExtra(EXTRA_SO2, arrayOf { it.so2 })
                putExtra(EXTRA_CURRENT_TIME, current?.timeMillis ?: 0L)
                putExtra(EXTRA_CURRENT_AQI, current?.aqi ?: Double.NaN)
                putExtra(EXTRA_CURRENT_PM25, current?.pm25 ?: Double.NaN)
                putExtra(EXTRA_CURRENT_PM10, current?.pm10 ?: Double.NaN)
                putExtra(EXTRA_CURRENT_NO2, current?.no2 ?: Double.NaN)
                putExtra(EXTRA_CURRENT_O3, current?.o3 ?: Double.NaN)
                putExtra(EXTRA_CURRENT_CO, current?.co ?: Double.NaN)
                putExtra(EXTRA_CURRENT_SO2, current?.so2 ?: Double.NaN)
                putExtra(EXTRA_SOURCE, current?.source?.displayName.orEmpty())
                putExtra(EXTRA_RANKING_CITY, rankingCity)
            }
        }
    }
}
