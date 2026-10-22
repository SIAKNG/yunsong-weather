package com.example.weatherapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 未来逐小时详情：同一条横向时间轴承载全部可用指标，纵向内容固定在一屏内。
 * 中央指示线属于视口而非时间轴，因此横滑时只移动数据，不移动选择线。
 */
class ForecastDetailActivity : AppCompatActivity() {
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        DetailCardEdges.apply(findViewById(android.R.id.content))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(this@ForecastDetailActivity,
            if (secondary) R.color.text_secondary else R.color.text_primary))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = WeatherSettings.isDarkTheme(this)
        val selectionPalette = ForecastSelectionPalette(
            accent = ContextCompat.getColor(this, R.color.accent),
            foreground = ContextCompat.getColor(this, R.color.text_primary),
            isDark = dark,
            lightSurface = ContextCompat.getColor(this, R.color.light_card_surface)
        )
        val bar = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        @Suppress("DEPRECATION")
        val rows = (intent.getSerializableExtra("hours") as? ArrayList<ForecastHour>).orEmpty()
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val futureIndices = rows.indices.filter { !rows[it].isHistorical }.ifEmpty { rows.indices.toList() }
        // 日期键会在横滑的每一帧读取；预先格式化，避免 360 小时时间轴反复创建 Date / String。
        val rowDateKeys = rows.map { dayKey.format(Date(it.time)) }
        val days = futureIndices.distinctBy { rowDateKeys[it] }
        val forecastHours = futureIndices.takeIf { it.isNotEmpty() }?.let { indices ->
            (rows[indices.last()].time - rows[indices.first()].time + ForecastDetailData.HOUR)
                .coerceAtLeast(ForecastDetailData.HOUR)
        } ?: 0L
        // 以连续预报时长计算“未来 N 日”，避免傍晚起报的 360 小时跨越 16 个日历日期时
        // 被误写成“未来16日”。顶部日期条仍按真实日历日期分段。
        val forecastDays = if (forecastHours == 0L) 0 else
            kotlin.math.ceil(forecastHours.toDouble() / (24 * ForecastDetailData.HOUR)).toInt()
        val forecastTitle = if (forecastDays == 0) "未来天气详情" else "未来${forecastDays}日天气详情"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ForecastDetailActivity, R.color.page_bg))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val titleBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(5))
        }
        titleBar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back_arrow)
            setColorFilter(ContextCompat.getColor(this@ForecastDetailActivity, R.color.text_primary))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleBar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text(intent.getStringExtra("city").orEmpty(), 18f).apply {
                WeatherAddressFormatter.bind(this, intent.getStringExtra("city").orEmpty())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, LinearLayout.LayoutParams(-1, -2))
            addView(text(forecastTitle, 14f, true).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(View(this), LinearLayout.LayoutParams(dp(44), 1))
        root.addView(titleBar)

        if (rows.isEmpty()) {
            root.addView(text("暂未获得小时预报，请返回主页刷新后重试。", 14f, true).apply {
                setPadding(dp(24), dp(32), dp(24), 0)
            })
            return
        }

        val fullFmt = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA)

        val dayStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            contentDescription = "未来七日日期选择"
        }
        // 日期条与下方圆角数据表共用 12dp 外边距。去掉原先额外的 6dp 内边距后，
        // 首末日期的选中矩形会准确对齐数据表的左右边缘。
        val chips = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        dayStrip.addView(chips, FrameLayout.LayoutParams(-2, -1))
        // 顶部日期仅保留这一条可横向过渡的选中区域；白色无描边底框随当前时刻移动，
        // 不再额外创建会跳变的日期/时间文字。
        val dateStripHost = FrameLayout(this)
        val dateSelectionOverlay = ForecastDateSelectionOverlay(this, selectionPalette)
        // 选中框是文字的底板而非蒙版。先加入 FrameLayout，日期文字才能保持可见。
        dateStripHost.addView(dateSelectionOverlay, FrameLayout.LayoutParams(-1, -1))
        dateStripHost.addView(dayStrip, FrameLayout.LayoutParams(-1, -1))
        root.addView(dateStripHost, LinearLayout.LayoutParams(-1, dp(54)).apply {
            setMargins(dp(12), 0, dp(12), 0)
        })

        val tracks = ForecastTrack.available(rows)
        // 图例独立置于日期与圆角数据表之间；每个项目等宽，确保六条指标在窄屏仍保持单行。
        val legendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            contentDescription = "小时指标图例"
        }
        fun addLegendItem(label: String, colors: IntArray) {
            val item = LinearLayout(this).apply { gravity = Gravity.CENTER }
            item.addView(View(this).apply {
                tag = "chart_legend_swatch"
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
                    cornerRadius = dp(2).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(10), dp(3)).apply { marginEnd = dp(2) })
            item.addView(text(label, 9.5f, true).apply {
                includeFontPadding = false
                maxLines = 1
            })
            legend.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        tracks.forEach { track ->
            val combined = track.secondaryValue != null && rows.any { track.secondaryValue.invoke(it) != null }
            addLegendItem(if (combined) "温度/体感" else track.title, ForecastChartGradient.forTrack(track.title))
        }
        legendRow.addView(legend, LinearLayout.LayoutParams(0, dp(28), 1f))
        val returnCurrentButton = FrostedActionButton(this).apply {
            text = "回到当前"
            textSize = 10f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(ContextCompat.getColor(this@ForecastDetailActivity, R.color.text_primary))
        }
        legendRow.addView(returnCurrentButton, LinearLayout.LayoutParams(dp(62), dp(24)).apply {
            marginStart = dp(5)
        })
        root.addView(legendRow, LinearLayout.LayoutParams(-1, dp(30)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        val panel = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@ForecastDetailActivity, R.color.metric_detail_panel))
                cornerRadius = dp(18).toFloat()
            }
            clipToOutline = true
        }
        root.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dp(12), 0, dp(12), dp(20))
        })

        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        panel.addView(horizontal, FrameLayout.LayoutParams(-1, -1))
        val chart = ForecastHoursView(this, rows, tracks, selectionPalette)
        horizontal.addView(chart, android.view.ViewGroup.LayoutParams(chart.totalWidth, -1))
        val selectionOverlay = ForecastSelectionOverlay(this, selectionPalette)
        panel.addView(selectionOverlay, FrameLayout.LayoutParams(-1, -1))
        val currentIndex = rows.indexOfFirst { !it.isHistorical }.takeIf { it >= 0 } ?: 0
        var returnCurrentState: Boolean? = null
        fun updateReturnCurrentButton(atCurrent: Boolean) {
            if (returnCurrentState == atCurrent) return
            returnCurrentState = atCurrent
            DetailReturnCurrentButtonStyle.apply(
                button = returnCurrentButton,
                atCurrent = atCurrent,
                primary = ContextCompat.getColor(this, R.color.text_primary),
                secondary = ContextCompat.getColor(this, R.color.text_secondary),
                activeFill = selectionPalette.dateFill,
                density = resources.displayMetrics.density
            )
        }
        returnCurrentButton.setOnClickListener {
            horizontal.smoothScrollTo(0, 0)
        }
        updateReturnCurrentButton(atCurrent = true)

        val buttons = days.map { index ->
            text(SimpleDateFormat("M/d\nEEE", Locale.CHINA).format(Date(rows[index].time)), 12f).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(2), dp(6), dp(2), dp(6))
                contentDescription = fullFmt.format(Date(rows[index].time))
                chips.addView(this, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
                setOnClickListener {
                    val target = chart.scrollForIndex(index, panel.width)
                    horizontal.smoothScrollTo(target.coerceIn(0, chart.maxScrollFor(horizontal.width)), 0)
                }
            }
        }
        val buttonIndexByDate = days.mapIndexed { buttonIndex, rowIndex ->
            rowDateKeys[rowIndex] to buttonIndex
        }.toMap()
        val dateTextColor = ContextCompat.getColor(this, R.color.text_primary)
        // 仅在焦点权重实际变化时改 View 属性，避免横滑每一帧重绘所有未选日期。
        val previousDateEmphasis = FloatArray(buttons.size) { Float.NaN }

        var selectedKey = ""
        var lastContentX = 0
        var overlayLeftButton: TextView? = null
        var overlayRightButton: TextView? = null
        var overlayFraction = 0f

        /**
         * 日期条因自动居中而滚动时，只需重算选中框相对于视口的位置。旧版在这里再次
         * 计算全部曲线交点，导致一次横滑同时触发两套完整图表计算而产生掉帧。
         */
        fun updateDateSelectionOverlay() {
            val leftButton = overlayLeftButton ?: return
            val rightButton = overlayRightButton ?: leftButton
            if (leftButton.width == 0 || leftButton.height == 0) return
            fun lerp(start: Float, end: Float) = start + (end - start) * overlayFraction
            val leftCenter = leftButton.left + leftButton.width / 2f - dayStrip.scrollX
            val rightCenter = rightButton.left + rightButton.width / 2f - dayStrip.scrollX
            dateSelectionOverlay.update(
                centerX = lerp(leftCenter, rightCenter),
                top = lerp(leftButton.top.toFloat(), rightButton.top.toFloat()),
                width = lerp(leftButton.width.toFloat(), rightButton.width.toFloat()),
                height = lerp(leftButton.height.toFloat(), rightButton.height.toFloat())
            )
        }

        fun updateTopDateSelection(contentX: Int) {
            lastContentX = contentX
            if (buttons.isEmpty()) return
            val position = chart.selectionPositionAtContent(contentX)
            val leftDate = rowDateKeys[position.leftIndex]
            val rightDate = rowDateKeys[position.rightIndex]
            val leftButtonIndex = buttonIndexByDate[leftDate] ?: 0
            val rightButtonIndex = buttonIndexByDate[rightDate] ?: leftButtonIndex
            val leftButton = buttons.getOrNull(leftButtonIndex) ?: return
            val rightButton = buttons.getOrNull(rightButtonIndex) ?: leftButton
            if (leftButton.width == 0 || leftButton.height == 0) return
            val fraction = if (leftButton === rightButton) 0f else position.fraction
            overlayLeftButton = leftButton
            overlayRightButton = rightButton
            overlayFraction = fraction
            buttons.forEachIndexed { buttonIndex, button ->
                val rawFocus = when {
                    leftButtonIndex == rightButtonIndex && buttonIndex == leftButtonIndex -> 1f
                    buttonIndex == leftButtonIndex -> 1f - fraction
                    buttonIndex == rightButtonIndex -> fraction
                    else -> 0f
                }
                val focus = rawFocus * rawFocus * (3f - 2f * rawFocus)
                if (abs(previousDateEmphasis[buttonIndex] - focus) < 0.002f) return@forEachIndexed
                previousDateEmphasis[buttonIndex] = focus
                // 不移动文字的基线，只在进入白色选中底框时连续放大；选中时使用粗字，
                // 深色主题则同步反转为深色文字，保证白底上的阅读对比。
                button.scaleX = 1f + 0.15f * focus
                button.scaleY = 1f + 0.15f * focus
                button.typeface = if (focus >= 0.5f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                button.setTextColor(selectionPalette.dateText(dateTextColor, focus))
            }
            updateDateSelectionOverlay()
        }

        fun updateSelectionNow(x: Int) {
            val lineX = chart.selectionViewportX(x, panel.width)
            val contentX = x + lineX.roundToInt()
            val index = chart.indexAtContent(contentX).coerceIn(rows.indices)
            updateReturnCurrentButton(atCurrent = index == currentIndex)
            chart.setViewportContentBounds(x, panel.width)
            chart.setSelectionContentX(contentX)
            selectionOverlay.updateSelection(
                lineX,
                chart.selectionAt(contentX, panel.height)
            )
            updateTopDateSelection(contentX)
            val key = rowDateKeys[index]
            if (key == selectedKey) return
            selectedKey = key
            val button = buttons.getOrNull(buttonIndexByDate[key] ?: 0) ?: return
            dayStrip.smoothScrollTo((button.left - dp(90)).coerceAtLeast(0), 0)
        }

        // ScrollView 在单个显示帧内可能发送多次位置回调。把状态更新合并为一次 vsync
        // 回调仍能完全跟手，同时避免同帧重复分配曲线交点和重绘叠加层。
        var pendingScrollX = 0
        var selectionUpdatePosted = false
        fun scheduleSelectionUpdate(x: Int) {
            pendingScrollX = x
            if (selectionUpdatePosted) return
            selectionUpdatePosted = true
            panel.postOnAnimation {
                selectionUpdatePosted = false
                updateSelectionNow(pendingScrollX)
            }
        }

        horizontal.setOnScrollChangeListener { _, x, _, _, _ ->
            // 裁剪窗口必须在 ScrollView 实际位移的同一帧更新；高亮交点仍合并到 vsync，
            // 这样既不会出现边缘短暂空白，也不会恢复原来的主线程重复计算。
            chart.setViewportContentBounds(x, panel.width)
            scheduleSelectionUpdate(x)
        }
        dayStrip.setOnScrollChangeListener { _, _, _, _, _ -> updateDateSelectionOverlay() }
        panel.post {
            chart.setViewportWidth(panel.width)
            horizontal.scrollTo(0, 0)
            updateSelectionNow(0)
        }
        dateStripHost.post { updateTopDateSelection(lastContentX) }
        updateSelectionNow(0)
    }

    companion object {
        fun createIntent(context: Context, city: String, rows: List<ForecastHour>) =
            Intent(context, ForecastDetailActivity::class.java)
                .putExtra("city", city)
                .putExtra("hours", ArrayList(rows))
    }
}

internal data class ForecastTrack(
    val title: String,
    val unit: String,
    val color: Int,
    val value: (ForecastHour) -> Double?,
    val secondaryTitle: String? = null,
    val secondaryColor: Int = Color.TRANSPARENT,
    val secondaryValue: ((ForecastHour) -> Double?)? = null
) {
    companion object {
        val all = listOf(
            ForecastTrack("温度", "°", Color.rgb(239, 148, 66), { it.temperature },
                "体感", Color.rgb(230, 105, 125), { it.feelsLike }),
            ForecastTrack("风力", "km/h", Color.rgb(60, 173, 166), { it.wind }),
            ForecastTrack("紫外线", "级", Color.rgb(172, 126, 225), { it.uv }),
            ForecastTrack("湿度", "百分比", Color.rgb(72, 156, 232), { it.humidity }),
            ForecastTrack("空气", "空气质量指数", Color.rgb(107, 183, 102), { it.aqi })
        )

        fun available(rows: List<ForecastHour>) = all.filter { track ->
            rows.any { track.value(it) != null || track.secondaryValue?.invoke(it) != null }
        }
    }
}

/**
 * 图表轨道标题在横向时间轴滑出可视区域后不再总是可见，因此把紧凑的中文单位直接
 * 写入读数，并在其下方补充中文指标名称。温度仍由调用方按用户的摄氏/华氏设置格式化。
 */
internal object ForecastMetricValueFormatter {
    fun format(trackTitle: String, value: Double): String = when (trackTitle) {
        // 数据源内部统一以 km/h 保存；表格使用紧凑的英文单位，避免窄列换行。
        "风力" -> "${value.roundToInt()}km/h"
        // 紫外线指数在本页按级别阅读；统一取整，避免 0.0 这类没有额外信息的小数显示。
        "紫外线" -> "${value.roundToInt()}级"
        "湿度" -> "${value.roundToInt()}%"
        "空气" -> value.roundToInt().toString()
        else -> value.roundToInt().toString()
    }
}

internal data class ForecastPointMarker(val y: Float, val color: Int)
internal data class ForecastSelectionGroup(
    /** 当前滑动位置对应的真实曲线交点；普通时间点不再绘制圆点。 */
    val points: List<ForecastPointMarker>
)
internal data class ForecastChartSelection(
    val groups: List<ForecastSelectionGroup>
)

internal data class ForecastSelectionPosition(
    val leftIndex: Int,
    val rightIndex: Int,
    val fraction: Float
)

/**
 * 折线使用两端水平控制柄的三次贝塞尔曲线。高亮点必须先按 X 轴反解 t，再按同一条
 * 贝塞尔曲线取 Y 值；直接线性插值会在陡升/陡降段偏离折线中心。
 */
internal object ForecastCurveInterpolator {
    fun easingAtContentFraction(fraction: Float): Float {
        val target = fraction.coerceIn(0f, 1f)
        if (target == 0f || target == 1f) return target
        var low = 0f
        var high = 1f
        repeat(12) {
            val t = (low + high) / 2f
            if (xAt(t) < target) low = t else high = t
        }
        val t = (low + high) / 2f
        // y 控制点为 0、1，因此 y(t) 正好是 smoothstep(t)。
        return t * t * (3f - 2f * t)
    }

    private fun xAt(t: Float): Float {
        val inverse = 1f - t
        return 1.5f * inverse * inverse * t + 1.5f * inverse * t * t + t * t * t
    }
}

/**
 * 图表不再使用选择竖线或矩形标签框。非焦点读数使用主题前景色；进入焦点后保留
 * 对应折线的色相，并自动校正明度以满足小字号在深浅色面板上的可读性。
 */
internal class ForecastSelectionPalette(
    accent: Int,
    private val foreground: Int,
    private val isDark: Boolean,
    private val lightSurface: Int = Color.rgb(248, 250, 252)
) {
    private val fallbackAccent = accent
    private val chartSurface: Int = if (isDark) {
        ColorUtils.compositeColors(Color.argb(0x2e, 255, 255, 255), Color.rgb(14, 23, 38))
    } else lightSurface

    /** 顶部日期框在浅色模式为白色，在深色模式改为半透明白色材质，避免突兀的亮块。 */
    val dateFill: Int = if (isDark) Color.argb(0x48, 255, 255, 255) else lightSurface
    /** 高亮折线点的外环按面板主题切换，浅色不再固定为白色而丢失轮廓。 */
    val selectionMarkerOutline: Int = if (isDark) {
        Color.argb(235, 255, 255, 255)
    } else {
        Color.argb(220, 26, 36, 51)
    }
    private val selectedDateText = if (isDark) Color.WHITE else Color.rgb(26, 36, 51)
    fun normalDataText(): Int = foreground

    private val readableColors=android.util.SparseIntArray()
    fun selectedDataText(color: Int): Int {
        val index=readableColors.indexOfKey(color)
        if(index>=0) return readableColors.valueAt(index)
        val result=computeSelectedDataText(color)
        if(readableColors.size()>=4096) readableColors.clear()
        readableColors.put(color,result)
        return result
    }
    private fun computeSelectedDataText(color: Int): Int {
        var candidate = Color.rgb(Color.red(if (Color.alpha(color) == 0) fallbackAccent else color),
            Color.green(if (Color.alpha(color) == 0) fallbackAccent else color),
            Color.blue(if (Color.alpha(color) == 0) fallbackAccent else color))
        repeat(8) {
            if (ColorUtils.calculateContrast(candidate, chartSurface) >= 4.5) return candidate
            candidate = ColorUtils.blendARGB(candidate, foreground, 0.18f)
        }
        return foreground
    }

    fun drawSelectionMarker(canvas: Canvas, x: Float, y: Float, color: Int, density: Float) {
        ChartSelectionMarker.draw(canvas, x, y, color, isDark, density)
    }

    fun dateText(base: Int, focus: Float): Int =
        ColorUtils.blendARGB(base, selectedDateText, focus.coerceIn(0f, 1f))
}

/** 按可见区域绘制，360 小时时间轴也不会创建成千上万个子 View。 */
internal class ForecastHoursView(
    context: Context,
    private val rows: List<ForecastHour>,
    private val tracks: List<ForecastTrack>,
    private val selectionPalette: ForecastSelectionPalette = ForecastSelectionPalette(
        accent = ContextCompat.getColor(context, R.color.accent),
        foreground = ContextCompat.getColor(context, R.color.text_primary),
        isDark = WeatherSettings.isDarkTheme(context),
        lightSurface = ContextCompat.getColor(context, R.color.light_card_surface)
    )
) : View(context) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density
    // 340dp 左右的常见竖屏面板内可完整容纳至少五个小时列；文字仍保留足够宽度。
    val step = dp(68)
    /** 首、末列使用相同的紧凑内边距，避免最右侧出现额外空白。 */
    private val firstCenter = dp(24)
    /** 当前时刻紧跟唯一的历史列；没有历史数据时从首列开始。 */
    private val initialSelectionIndex = rows.indexOfFirst { !it.isHistorical }.takeIf { it >= 0 } ?: 0
    val selectionX: Int get() = centerXForIndex(initialSelectionIndex)
    // 两端都使用与首列相同的内边距，因此最后一列可严丝合缝落在右侧内边距。
    var totalWidth: Int = firstCenter * 2 + step * (rows.size - 1).coerceAtLeast(0)
        private set
    var totalHeight: Int = dp(520)
        private set
    internal var selectedLabelIndex: Int = rows.indexOfFirst { !it.isHistorical }
        .takeIf { it >= 0 } ?: 0
        private set
    /** 以连续坐标而非整数索引记录选择位置，供文字缩放/变色平滑插值。 */
    private var selectionContentX = selectionX.toFloat()
    /** HorizontalScrollView 当前可见的内容窗口，避免硬件 Canvas 重录时重绘整条 360 小时轴。 */
    private var viewportContentX = 0
    private var viewportContentWidth = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val gradientCache = ForecastChartGradient.Cache()
    private val primary = ContextCompat.getColor(context, R.color.text_primary)
    private val secondary = ContextCompat.getColor(context, R.color.text_secondary)
    private val divider = Color.argb(30, 128, 128, 128)
    private val historyDash = DashPathEffect(floatArrayOf(dp(7).toFloat(), dp(5).toFloat()), 0f)
    private val hourFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val dateFormat = SimpleDateFormat("M/d", Locale.CHINA)
    private val bounds = Rect()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val seriesPath = Path()
    private val windDirectionArrowPath = Path()
    private val windDirectionArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // 与 scaledDensity 等价，但避开 Android 近期 API 对该兼容字段的弃用警告。
    private val scaledDensity = resources.displayMetrics.density * resources.configuration.fontScale

    /** 静态的日期、时刻、天气文字与图标在进入页面时生成一次，滚动时只做 Canvas 绘制。 */
    private data class HourDisplay(
        val date: String,
        val hour: String,
        val weather: String,
        val icon: Bitmap?
    )

    private val hourDisplays = rows.map { row ->
        HourDisplay(
            date = dateFormat.format(Date(row.time)),
            hour = hourFormat.format(Date(row.time)),
            weather = WeatherText.skyconToText(row.skycon).ifBlank { "—" },
            icon = WeatherIconCache.load(context, row.skycon, dp(34).toFloat())
        )
    }

    private data class TrackGeometry(
        val top: Int,
        val bottom: Int,
        val graphTop: Int,
        val graphBottom: Int,
        val minValue: Double,
        val maxValue: Double
    ) {
        fun y(value: Double): Float {
            val projected = graphBottom -
                ((value - minValue) / (maxValue - minValue).coerceAtLeast(1.0) *
                    (graphBottom - graphTop)).toFloat()
            // 标签预留或异常插值不能把曲线/高亮点投影到所属轨道外。
            return projected.coerceIn(graphTop.toFloat(), graphBottom.toFloat())
        }
    }

    /**
     * 曲线范围只依赖小时数据和当前高度，绝不能在每个 scroll 回调与 onDraw 中反复
     * 扫描 360 个小时。缓存让横滑帧只计算可见的约 5 个小时列。
     */
    private var cachedGeometryHeight = Int.MIN_VALUE
    private var cachedTrackGeometries: List<TrackGeometry> = emptyList()
    /** 设备回归用计数：同一高度的连续横滑不应再次扫描整条小时数据。 */
    internal var geometryCacheBuildCount = 0
        private set
    /** 绘制窗口回归用：横滑时应始终只处理视口附近的少量小时列。 */
    internal var lastDrawnColumnCount = 0
        private set
    /** 真机绘制回归用：风向行内箭头也必须随小时视口裁剪，不能遍历完整时间轴。 */
    internal var lastDrawnWindDirectionArrowCount = 0
        private set

    init {
        contentDescription = "可横向滑动的小时天气、温度与体感温度、风力、紫外线、湿度和空气质量图表"
    }

    fun setViewportWidth(width: Int) {
        if (width <= 0) return
        totalWidth = firstCenter * 2 + step * (rows.size - 1).coerceAtLeast(0)
        layoutParams = layoutParams.apply { this.width = totalWidth }
        requestLayout()
        invalidate()
    }

    /**
     * 由宿主在每个手势帧传入真实滚动窗口。该方法不单独触发绘制，随后同帧的选中态
     * 更新会统一失效；onDraw 会使用它裁掉窗口外的所有文本、路径和指标。
     */
    fun setViewportContentBounds(scrollX: Int, width: Int) {
        if (width <= 0) return
        viewportContentWidth = width
        viewportContentX = scrollX.coerceIn(0, maxScrollFor(width))
    }

    fun centerXForIndex(index: Int): Int =
        firstCenter + index.coerceIn(rows.indices) * step

    fun indexAtContent(contentX: Int): Int =
        ((contentX - firstCenter) / step.toFloat())
            .roundToInt().coerceIn(rows.indices)

    fun indexAtSelection(scrollX: Int): Int = indexAtContent(scrollX + selectionX)

    /** 末端滚动时，选择线自然从初始当前列滑向右侧最后一列。 */
    fun selectionViewportX(scrollX: Int, viewportWidth: Int): Float {
        val maxScroll = maxScrollFor(viewportWidth)
        if (maxScroll <= 0) return selectionX.toFloat().coerceIn(firstCenter.toFloat(), (viewportWidth - firstCenter).toFloat())
        val progress = (scrollX.toFloat() / maxScroll).coerceIn(0f, 1f)
        val right = (viewportWidth - firstCenter).coerceAtLeast(firstCenter)
        return selectionX + (right - selectionX) * progress
    }

    /** 当前手势位于哪两列之间；顶部日期条与高亮框据此连续跟随，而不是松手后跳变。 */
    fun selectionPositionAtContent(contentX: Int): ForecastSelectionPosition {
        val raw = ((contentX - firstCenter) / step.toFloat()).coerceIn(0f, rows.lastIndex.toFloat())
        val left = floor(raw).toInt()
        return ForecastSelectionPosition(left, (left + 1).coerceAtMost(rows.lastIndex), raw - left)
    }

    fun scrollForIndex(index: Int, viewportWidth: Int): Int {
        val maxScroll = maxScrollFor(viewportWidth)
        if (maxScroll <= 0) return 0
        val targetX = centerXForIndex(index)
        val selectionShift = (viewportWidth - firstCenter - selectionX).toFloat()
        val scroll = (targetX - selectionX) / (1f + selectionShift / maxScroll)
        return scroll.roundToInt().coerceIn(0, maxScroll)
    }

    /** 保留选中列索引给无障碍/测试使用；视觉上不再隐藏底层文字。 */
    fun setSelectionContentX(contentX: Int) {
        val clamped = contentX.toFloat().coerceIn(
            centerXForIndex(0).toFloat(), centerXForIndex(rows.lastIndex).toFloat()
        )
        val next = indexAtContent(clamped.roundToInt())
        if (next == selectedLabelIndex && abs(selectionContentX - clamped) < 0.25f) return
        selectedLabelIndex = next
        selectionContentX = clamped
        // 硬件加速下 Android 会统一处理 View 脏区，因此仍使用标准失效流程；onDraw
        // 会按宿主传入的内容窗口裁剪到可见列，避免重绘整条长时间轴。
        invalidate()
    }

    private fun selectionEmphasisForIndex(index: Int): Float {
        val distance = abs(centerXForIndex(index) - selectionContentX) / step.toFloat()
        val raw = (1f - distance).coerceIn(0f, 1f)
        // smoothstep：进入与离开都没有突兀的速度折点。
        return raw * raw * (3f - 2f * raw)
    }

    fun maxScrollFor(viewportWidth: Int): Int = (totalWidth - viewportWidth).coerceAtLeast(0)

    /** 用实心导航箭头标示空气去向；颜色由调用方按当前风力文字/历史小时规则决定。 */
    private fun drawWindDirectionArrow(
        canvas: Canvas,
        x: Float,
        y: Float,
        rotation: Float,
        color: Int,
        sizeDp: Float
    ) {
        val size = dp(sizeDp)
        windDirectionArrowPath.reset()
        // 未旋转时箭头朝上，随后按“去向”角度围绕风力曲线点旋转。
        windDirectionArrowPath.moveTo(x, y - size)
        windDirectionArrowPath.lineTo(x + size * 0.76f, y + size)
        windDirectionArrowPath.lineTo(x, y + size * 0.42f)
        windDirectionArrowPath.lineTo(x - size * 0.76f, y + size)
        windDirectionArrowPath.close()
        windDirectionArrowPaint.color = color
        canvas.save()
        canvas.rotate(rotation, x, y)
        canvas.drawPath(windDirectionArrowPath, windDirectionArrowPaint)
        canvas.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        totalHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(dp(320))
            else -> dp(520)
        }
        setMeasuredDimension(totalWidth, totalHeight)
    }

    private fun weatherHeight(viewHeight: Int): Int = max(dp(100), (viewHeight * 0.19f).roundToInt())
        .coerceAtMost((viewHeight * 0.27f).roundToInt())

    private fun calculateGeometry(trackIndex: Int, viewHeight: Int): TrackGeometry {
        val weatherHeight = weatherHeight(viewHeight)
        val metricHeight = if (tracks.isEmpty()) viewHeight - weatherHeight
        else (viewHeight - weatherHeight) / tracks.size
        val track = tracks[trackIndex]
        val top = weatherHeight + metricHeight * trackIndex
        val bottom = if (trackIndex == tracks.lastIndex) viewHeight else top + metricHeight
        var rawMin = Double.POSITIVE_INFINITY
        var rawMax = Double.NEGATIVE_INFINITY
        fun include(value: Double?) {
            if (value == null || !value.isFinite()) return
            rawMin = min(rawMin, value)
            rawMax = max(rawMax, value)
        }
        rows.forEach { row ->
            include(track.value(row))
            track.secondaryValue?.let { secondary -> include(secondary(row)) }
        }
        if (!rawMin.isFinite() || !rawMax.isFinite()) {
            rawMin = 0.0
            rawMax = 1.0
        }
        val spread = (rawMax - rawMin).coerceAtLeast(1.0)
        val valueRows = if (track.secondaryValue != null) 2 else 1
        // 除温度/体感外，每个轨道都补一行中文指标说明；曲线从说明下方开始，
        // 以免把“级、百分比、空气质量”等单位说明压到折线之上。
        val hasCategoryRow = track.secondaryValue == null
        val desiredGraphTop = top + dp(when {
            valueRows == 2 -> 41
            hasCategoryRow -> 46
            else -> 29
        })
        // 旧版用 coerceAtLeast(graphTop + 12dp) 保证最小图高，但在紧凑高度/高密度屏幕
        // 会把最后一条轨道的 graphBottom 推过容器底部。现在优先守住轨道下界，再在可用
        // 空间内压缩图高，确保最后一条曲线和高亮点始终落在屏内。
        val graphBottom = (bottom - dp(9)).coerceAtLeast(top + 1)
        val minGraphHeight = min(dp(12), (graphBottom - top).coerceAtLeast(1))
        val graphTop = desiredGraphTop.coerceIn(top, graphBottom - minGraphHeight)
        return TrackGeometry(top, bottom, graphTop, graphBottom,
            rawMin - spread * 0.08, rawMax + spread * 0.08)
    }

    private fun geometriesFor(viewHeight: Int): List<TrackGeometry> {
        val resolvedHeight = viewHeight.takeIf { it > 0 } ?: totalHeight
        if (cachedGeometryHeight != resolvedHeight || cachedTrackGeometries.size != tracks.size) {
            cachedGeometryHeight = resolvedHeight
            cachedTrackGeometries = tracks.indices.map { calculateGeometry(it, resolvedHeight) }
            geometryCacheBuildCount += 1
        }
        return cachedTrackGeometries
    }

    private fun interpolatedValue(
        contentX: Float,
        valueOf: (ForecastHour) -> Double?
    ): Double? {
        val raw = ((contentX - firstCenter) / step.toFloat())
            .coerceIn(0f, rows.lastIndex.toFloat())
        val left = floor(raw).toInt()
        val right = (left + 1).coerceAtMost(rows.lastIndex)
        val leftValue = valueOf(rows[left]) ?: return null
        if (left == right) return leftValue
        val rightValue = valueOf(rows[right]) ?: return null
        if (rows[right].time - rows[left].time > ForecastDetailData.HOUR) return null
        val curveFraction = ForecastCurveInterpolator.easingAtContentFraction(raw - left)
        return leftValue + (rightValue - leftValue) * curveFraction
    }

    /** 返回当前滑动位置与各条指标曲线的真实交点；仅这些点会绘制为高亮标记。 */
    fun selectionAt(contentX: Int, viewHeight: Int): ForecastChartSelection {
        val resolvedHeight = viewHeight.takeIf { it > 0 } ?: totalHeight
        val geometries = geometriesFor(resolvedHeight)
        val groups = tracks.mapIndexedNotNull { index, track ->
            val g = geometries[index]
            val value = interpolatedValue(contentX.toFloat(), track.value)
            val secondaryValue = track.secondaryValue?.let { interpolatedValue(contentX.toFloat(), it) }
            val points = buildList {
                value?.let { add(ForecastPointMarker(g.y(it), if (track.title == "空气") AirQualityScale.color(it)
                    else ForecastChartGradient.colorAt(ForecastChartGradient.forTrack(track.title), g.y(it), g.graphTop.toFloat(), g.graphBottom.toFloat()))) }
                secondaryValue?.let { add(ForecastPointMarker(g.y(it), ForecastChartGradient.colorAt(
                    ForecastChartGradient.temperature, g.y(it), g.graphTop.toFloat(), g.graphBottom.toFloat()))) }
            }
            if (points.isEmpty()) return@mapIndexedNotNull null
            ForecastSelectionGroup(points)
        }
        return ForecastChartSelection(groups)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) return
        canvas.getClipBounds(bounds)
        val viewportLeft = if (viewportContentWidth > 0) viewportContentX else bounds.left
        val viewportRight = if (viewportContentWidth > 0) viewportContentX + viewportContentWidth else bounds.right
        // 曲线在视口边缘仍需多绘一列，保证贝塞尔段不会被过早截断。
        val drawLeft = (viewportLeft - step * 2).coerceAtLeast(0)
        val drawRight = (viewportRight + step * 2).coerceAtMost(totalWidth)
            .coerceAtLeast(drawLeft + 1)
        val start = (((drawLeft - firstCenter) / step) - 1).coerceAtLeast(0)
        val end = (((drawRight - firstCenter) / step) + 1).coerceAtMost(rows.lastIndex)
        lastDrawnColumnCount = (end - start + 1).coerceAtLeast(0)
        lastDrawnWindDirectionArrowCount = 0
        val clipSave = canvas.save()
        canvas.clipRect(drawLeft.toFloat(), 0f, drawRight.toFloat(), height.toFloat())
        val weatherHeight = weatherHeight(height)
        val geometries = geometriesFor(height)

        fun historicalColor(color: Int): Int = Color.argb(
            (Color.alpha(color) * 0.48f).roundToInt(),
            Color.red(color), Color.green(color), Color.blue(color)
        )

        /**
         * 时间、日期和天气文字与数据读数共享同一个焦点权重：同一 Canvas 元素连续
         * 缩放和变色，不叠加第二层文字，因此横滑时没有双重残影。
         */
        fun headerLabel(
            value: String,
            x: Float,
            y: Float,
            baseColor: Int,
            size: Float = 13f,
            bold: Boolean = false,
            emphasis: Float = 0f,
            historical: Boolean = false
        ) {
            val focus = emphasis.coerceIn(0f, 1f)
            val highlighted = selectionPalette.selectedDataText(primary)
            val targetColor = if (historical) historicalColor(highlighted) else highlighted
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.color = ColorUtils.blendARGB(baseColor, targetColor, focus)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = size * scaledDensity * (1f + 0.36f * focus)
            paint.typeface = if (bold || focus >= .48f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.isFakeBoldText = false
            canvas.drawText(value, x, y, paint)
        }

        /**
         * 同一读数只绘制一次。横滑时连续插值颜色与字号，避免旧版“普通文字 +
         * 高亮文字”双层交叉淡入造成的重影；字重只在接近焦点后切换，位置始终不变。
         */
        fun metricLabel(
            value: String,
            x: Float,
            y: Float,
            lineColor: Int,
            size: Float,
            emphasis: Float,
            historical: Boolean
        ) {
            val focus = emphasis.coerceIn(0f, 1f)
            val normal = if (historical) historicalColor(selectionPalette.normalDataText())
            else selectionPalette.normalDataText()
            val emphasized = if (historical) historicalColor(selectionPalette.selectedDataText(lineColor))
            else selectionPalette.selectedDataText(lineColor)
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.textAlign = Paint.Align.CENTER
            paint.color = ColorUtils.blendARGB(normal, emphasized, focus)
            // 焦点读数沿用同一 Canvas 元素连续放大；加大峰值差距，让当前小时在
            // 五列紧凑布局中仍能一眼辨认，而不会重新叠绘出第二层文字。
            paint.textSize = size * scaledDensity * (1f + 0.36f * focus)
            paint.typeface = if (focus >= .48f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.isFakeBoldText = false
            canvas.drawText(value, x, y, paint)
        }

        /**
         * 风向属于风力读数的辅助信息，故在“x 级风”文字右侧以同色导航箭头呈现，
         * 而不再散落到折线上。文字和箭头共用焦点颜色、字号缩放及历史淡化规则。
         */
        fun windDirectionLabel(
            value: String,
            direction: Double?,
            x: Float,
            y: Float,
            lineColor: Int,
            size: Float,
            emphasis: Float,
            historical: Boolean
        ) {
            val rotation = ForecastWindDirection.navigationArrowRotation(direction)
            if (rotation == null) {
                metricLabel(value, x, y, lineColor, size, emphasis, historical)
                return
            }
            val focus = emphasis.coerceIn(0f, 1f)
            val normal = if (historical) historicalColor(selectionPalette.normalDataText())
            else selectionPalette.normalDataText()
            val emphasized = if (historical) historicalColor(selectionPalette.selectedDataText(lineColor))
            else selectionPalette.selectedDataText(lineColor)
            val arrowSizeDp = 3.1f * (1f + 0.36f * focus)
            val arrowSize = dp(arrowSizeDp)
            val gap = dp(3f)
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.textAlign = Paint.Align.CENTER
            paint.color = ColorUtils.blendARGB(normal, emphasized, focus)
            paint.textSize = size * scaledDensity * (1f + 0.36f * focus)
            paint.typeface = if (focus >= .48f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.isFakeBoldText = false
            val textWidth = paint.measureText(value)
            // 把“文字 + 箭头”作为整体居中，避免箭头让风力读数视觉向右偏移。
            val groupWidth = textWidth + gap + arrowSize * 1.52f
            val textCenter = x - groupWidth / 2f + textWidth / 2f
            canvas.drawText(value, textCenter, y, paint)
            drawWindDirectionArrow(
                canvas = canvas,
                x = textCenter + textWidth / 2f + gap + arrowSize * 0.76f,
                y = y - paint.textSize * 0.36f,
                rotation = rotation,
                color = paint.color,
                sizeDp = arrowSizeDp
            )
            lastDrawnWindDirectionArrowCount += 1
        }

        for (i in start..end) {
            val x = centerXForIndex(i).toFloat()
            val row = rows[i]
            val display = hourDisplays[i]
            val primaryColor = if (row.isHistorical) historicalColor(primary) else primary
            val secondaryColor = if (row.isHistorical) historicalColor(secondary) else secondary
            val emphasis = selectionEmphasisForIndex(i)
            // 表格顶部只保留具体时刻；日期已由上方日期条承担，避免同一信息重复占行。
            // 时刻、天气与下方指标共用 10.5sp 基准与同一焦点缩放率。
            headerLabel(display.hour, x, dp(24).toFloat(), primaryColor, 10.5f, true,
                emphasis = emphasis, historical = row.isHistorical)
            display.icon?.let {
                // 未高亮图标缩至旧基准的 70%，高亮点回到原未高亮尺寸；始终保留原色。
                iconPaint.alpha = if (row.isHistorical) 122 else 255
                val iconTop = dp(38).toFloat()
                val iconScale = .70f + .30f * emphasis.coerceIn(0f, 1f)
                val iconSave = canvas.save()
                canvas.scale(iconScale, iconScale, x, iconTop + it.height / 2f)
                canvas.drawBitmap(it, x - it.width / 2f, iconTop, iconPaint)
                canvas.restoreToCount(iconSave)
            }
            headerLabel(display.weather, x, min(weatherHeight - dp(8), dp(97)).toFloat(), secondaryColor,
                10.5f, emphasis = emphasis, historical = row.isHistorical)
        }

        tracks.forEachIndexed { trackIndex, track ->
            val g = geometries[trackIndex]
            paint.style = Paint.Style.STROKE
            paint.pathEffect = null
            // LieFlat F2 Hairline Line 的信息层次：克制的发丝分隔与细折线，
            // 普通节点完全移除，只把滑动焦点作为唯一的曲线标记。
            paint.strokeWidth = dp(0.55f).toFloat().coerceAtLeast(1f)
            paint.color = divider
            canvas.drawLine(drawLeft.toFloat(), g.top.toFloat(), drawRight.toFloat(), g.top.toFloat(), paint)

            fun drawSeries(valueOf: (ForecastHour) -> Double?, colors: IntArray) {
                paint.shader = if (track.title == "空气") gradientCache.airShader(g.minValue, g.maxValue, g.graphTop.toFloat(), g.graphBottom.toFloat())
                    else gradientCache.shader(colors, g.graphTop.toFloat(), g.graphBottom.toFloat())
                for (i in start..end) {
                    val value = valueOf(rows[i]) ?: continue
                    val next = rows.getOrNull(i + 1)?.let(valueOf)
                    val x = centerXForIndex(i).toFloat()
                    if (next != null && rows[i + 1].time - rows[i].time <= ForecastDetailData.HOUR) {
                        val nextX = centerXForIndex(i + 1).toFloat()
                        seriesPath.reset()
                        seriesPath.moveTo(x, g.y(value))
                        seriesPath.cubicTo((x + nextX) / 2f, g.y(value),
                            (x + nextX) / 2f, g.y(next), nextX, g.y(next))
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = dp(2).toFloat()
                        paint.color = Color.WHITE
                        paint.alpha = if (rows[i].isHistorical) 122 else 255
                        paint.strokeCap = Paint.Cap.ROUND
                        // 唯一的历史小时连接到当前小时，以虚线明确区分。
                        paint.pathEffect = if (rows[i].isHistorical) historyDash else null
                        canvas.drawPath(seriesPath, paint)
                    }
                }
                paint.shader = null
                paint.alpha = 255
            }

            drawSeries(track.value, ForecastChartGradient.forTrack(track.title))
            track.secondaryValue?.let { drawSeries(it, ForecastChartGradient.temperature) }

            for (i in start..end) {
                val x = centerXForIndex(i).toFloat()
                val emphasis = selectionEmphasisForIndex(i)
                val value = track.value(rows[i])
                val second = track.secondaryValue?.invoke(rows[i])
                if (value == null && second == null) {
                    metricLabel("—", x, (g.top + dp(20)).toFloat(), track.color, 10.5f,
                        emphasis, rows[i].isHistorical)
                    continue
                }
                value?.let {
                    metricLabel(formatValue(track, it), x, (g.top + dp(18)).toFloat(),
                        if (track.title == "空气") AirQualityScale.color(it) else ForecastChartGradient.colorAt(ForecastChartGradient.forTrack(track.title), g.y(it),
                            g.graphTop.toFloat(), g.graphBottom.toFloat()), 10.5f,
                        emphasis, rows[i].isHistorical)
                }
                second?.let {
                    metricLabel("体感 ${formatTemperature(it)}", x, (g.top + dp(34)).toFloat(),
                        ForecastChartGradient.colorAt(ForecastChartGradient.temperature, g.y(it),
                            g.graphTop.toFloat(), g.graphBottom.toFloat()), 9.5f, emphasis, rows[i].isHistorical)
                }
                if (track.secondaryValue == null && value != null) {
                    when (track.title) {
                        "风力" -> windDirectionLabel("${WeatherSettings.windLevel(context, value, null)}风",
                            rows[i].windDirectionDegrees, x, (g.top + dp(40)).toFloat(), track.color,
                            8.5f, emphasis, rows[i].isHistorical)
                        "紫外线" -> metricLabel(ForecastMetricSemanticLabel.uv(value), x,
                            (g.top + dp(40)).toFloat(), track.color, 8.5f, emphasis, rows[i].isHistorical)
                        "湿度" -> metricLabel(ForecastMetricSemanticLabel.humidity(value), x,
                            (g.top + dp(40)).toFloat(), track.color, 8.5f, emphasis, rows[i].isHistorical)
                        "空气" -> metricLabel(AirQualityScale.level(value), x,
                            (g.top + dp(40)).toFloat(), track.color, 8.2f, emphasis, rows[i].isHistorical)
                    }
                }
            }
        }
        canvas.restoreToCount(clipSave)
        paint.pathEffect = null
    }

    private fun formatTemperature(value: Double): String {
        val display = WeatherSettings.tempDisplay(context, value) ?: value
        return "${display.roundToInt()}°"
    }

    private fun formatValue(track: ForecastTrack, value: Double): String = when (track.title) {
        "温度" -> formatTemperature(value)
        else -> ForecastMetricValueFormatter.format(track.title, value)
    }
}

/** 选择层只绘制当前滑动位置的折线交点，刻意不绘制竖线、标签框或普通时间点。 */
private class ForecastSelectionOverlay(
    context: Context,
    private val palette: ForecastSelectionPalette
) : View(context) {
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private var fixedX = 0f
    private var selection: ForecastChartSelection? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateSelection(x: Float, value: ForecastChartSelection) {
        fixedX = x
        selection = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val value = selection ?: return
        val x = fixedX.coerceIn(dp(8f), width - dp(8f))
        value.groups.forEach { group ->
            group.points.forEach { point ->
                palette.drawSelectionMarker(canvas, x, point.y, point.color, resources.displayMetrics.density)
            }
        }
    }
}

/** 顶部日期行使用随主题适配的无描边圆角矩形，文字由底层连续放大和加粗。 */
private class ForecastDateSelectionOverlay(
    context: Context,
    private val palette: ForecastSelectionPalette
) : View(context) {
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private var centerX = 0f
    private var top = 0f
    private var boxWidth = 0f
    private var boxHeight = 0f
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dateBorder = WeatherFrostedBorderDrawable(resources.displayMetrics.density, 10f, WeatherSettings.isDarkTheme(context))

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update(centerX: Float, top: Float, width: Float, height: Float) {
        this.centerX = centerX
        this.top = top
        this.boxWidth = width
        this.boxHeight = height
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (boxWidth <= 0f || boxHeight <= 0f) return
        // 选中框必须跟随日期条的真实坐标离开视口，不能钉在左右边缘。
        val left = centerX - boxWidth / 2f
        val safeTop = top.coerceIn(dp(2f), height - dp(2f) - boxHeight)
        rect.set(left, safeTop, left + boxWidth, safeTop + boxHeight)
        fill.color = palette.dateFill
        val radius = min(dp(10f), boxHeight / 2f)
        canvas.drawRoundRect(rect, radius, radius, fill)
        dateBorder.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        dateBorder.draw(canvas)
    }
}
