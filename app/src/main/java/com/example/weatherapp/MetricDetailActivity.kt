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
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** 六张天气详情卡共用的 24 小时指标页面。 */
open class MetricDetailActivity : AppCompatActivity(), DetailPageMotionHost {
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
        setContentView(R.layout.activity_metric_detail)
        val root = findViewById<View>(R.id.metricDetailRoot)
        root.let {
            val rootView = it
            OplusViewSeamlessNavigator.finishWhenUserInteracts(root)
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val metricScroll = findViewById<DetailScrollView>(R.id.metricDetailScroll)
        DetailHeaderOverlay.promote(root as android.view.ViewGroup, metricScroll, findViewById(R.id.metricDetailHeader))
        pageMotion = DetailPageMotion(root, metricScroll, null, findViewById(R.id.tvMetricDetailTitle))
        pageMotion.prepareEnter(
            intent.getIntExtra(DetailNavigator.EXTRA_MOTION_DIRECTION, 0),
            intent.getBooleanExtra(DetailNavigator.EXTRA_INTERACTIVE_HANDOFF, false)
        )
        val content = MetricDetailContent(this, intent, root)
        content.bind(darkTheme)
        DetailContentPager.install(
            this, root as android.widget.FrameLayout, metricScroll,
            findViewById(R.id.metricDetailHeader), findViewById(R.id.tvMetricDetailTitle),
            DetailNavigator.pageOf(content.kind), content.chart, savedInstanceState
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        DetailContentPager.of(this)?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun animateDetailPageExit(delta: Int, onEnd: () -> Unit): Boolean =
        pageMotion.playExit(delta, onEnd)

    companion object {
        const val EXTRA_KIND = "metric_kind"
        const val EXTRA_TIMES = "metric_times"
        const val EXTRA_VALUES = "metric_values"
        const val EXTRA_SECONDARY = "metric_secondary"
        const val EXTRA_YESTERDAY = "metric_yesterday"
        const val EXTRA_YESTERDAY_SUPPLEMENTED = "metric_yesterday_supplemented"
        const val EXTRA_CITY = "metric_city"
        const val EXTRA_WIND_DIRECTION = "metric_wind_direction"
        const val EXTRA_WIND_DIRECTIONS = "metric_wind_directions"
        const val EXTRA_LIFE_ADVICE = "metric_life_advice"
        const val EXTRA_ALL_LIFE_ADVICE = "metric_all_life_advice"

        fun createIntent(
            context: Context,
            kind: MetricKind,
            points: List<HourlyMetricPoint>,
            yesterdayPoints: List<HourlyMetricPoint> = emptyList(),
            cityName: String = "",
            windDirection: String? = null,
            lifeAdvice: List<LifeAdviceItem> = emptyList(),
            allLifeAdvice: List<LifeAdviceItem> = lifeAdvice,
            historicalSupplemented: Boolean = false
        ): Intent {
            val normalized = points.sortedBy { it.timeMillis }.take(24)
            val yesterday = yesterdayPoints.sortedBy { it.timeMillis }.takeLast(24)
            val values = DoubleArray(normalized.size) { index ->
                kind.valueOf(normalized[index]) ?: Double.NaN
            }
            val secondary = DoubleArray(normalized.size) { index ->
                kind.secondaryOf(normalized[index]) ?: Double.NaN
            }
            val historyByHour = yesterday.associateBy { it.timeMillis / 3_600_000L }
            val yesterdayValues = DoubleArray(normalized.size) { index ->
                val historical = historyByHour[normalized[index].timeMillis / 3_600_000L - 24L]
                historical?.let { kind.valueOf(it) } ?: Double.NaN
            }
            val windDirections = DoubleArray(normalized.size) { index ->
                normalized[index].windDirectionDegrees ?: Double.NaN
            }
            return Intent(context, MetricDetailActivity::class.java).apply {
                putExtra(EXTRA_KIND, kind.name)
                putExtra(EXTRA_CITY, cityName)
                putExtra(EXTRA_TIMES, normalized.map { it.timeMillis }.toLongArray())
                putExtra(EXTRA_VALUES, values)
                putExtra(EXTRA_SECONDARY, secondary)
                putExtra(EXTRA_YESTERDAY, yesterdayValues)
                putExtra(EXTRA_YESTERDAY_SUPPLEMENTED, historicalSupplemented)
                putExtra(EXTRA_WIND_DIRECTION, windDirection)
                putExtra(EXTRA_WIND_DIRECTIONS, windDirections)
                putExtra(EXTRA_LIFE_ADVICE, ArrayList(lifeAdvice))
                putExtra(EXTRA_ALL_LIFE_ADVICE, ArrayList(allLifeAdvice))
            }
        }
    }
}
