package com.example.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import java.io.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 生活指数展示模型；和风数据保留原始名称、文字分类和建议文本。 */
data class LifeAdviceItem(
    val key: String,
    val title: String,
    val level: String,
    val detail: String
) : Serializable

object LifeAdviceFactory {
    fun fromQWeather(items: List<QLifeIndex>): List<LifeAdviceItem> = items.map {
        LifeAdviceItem(
            key = "q${it.type}",
            title = it.name,
            // `level` 是和风用于排序的数值等级。界面只需要分类文字（如“较好”），
            // 不再拼接“· 3”之类数字，同时原始建议文本仍完整保留在 detail。
            level = it.category,
            detail = it.text
        )
    }

    /** 彩云日生活指数直接保留原始等级和建议；键与和风同语义项一致，便于主源优先合并。 */
    fun fromCaiyun(index: DailyLifeIndex?): List<LifeAdviceItem> {
        fun first(
            items: List<DailyLifeIndexItem>?, key: String, title: String
        ): LifeAdviceItem? = items?.firstOrNull()?.let {
            LifeAdviceItem(key, title, it.index.orEmpty(), it.desc.orEmpty())
        }
        return listOfNotNull(
            first(index?.ultraviolet, "q5", "紫外线"),
            first(index?.comfort, "q8", "舒适度"),
            first(index?.carWashing, "q2", "洗车"),
            first(index?.coldRisk, "q9", "感冒"),
            first(index?.dressing, "q3", "穿衣")
        )
    }

    /** 保留彩云已有类别，再以和风扩展其余所有指数。 */
    fun mergePrimaryCaiyun(caiyun: List<LifeAdviceItem>, qweather: List<LifeAdviceItem>): List<LifeAdviceItem> {
        val primary = caiyun.associateBy { it.key }
        return primary.values.toList() + qweather.filterNot { primary.containsKey(it.key) }
    }
    fun create(
        temperature: Double?, uvText: String?, humidity: Double?, windKmh: Double?,
        visibilityKm: Double?, precipitationMm: Double?
    ): List<LifeAdviceItem> {
        val temp = temperature ?: 20.0
        val uv = uvText?.toDoubleOrNull() ?: 0.0
        val wet = humidity ?: 55.0
        val wind = windKmh ?: 0.0
        val visibility = visibilityKm ?: 10.0
        val rain = precipitationMm ?: 0.0
        fun item(key: String, title: String, level: String, detail: String) = LifeAdviceItem(key, title, level, detail)
        return listOf(
            item("dress", "穿衣", when { temp >= 28 -> "清凉"; temp >= 20 -> "轻薄"; temp >= 12 -> "外套"; else -> "保暖" }, when { temp >= 34 -> "炎热轻薄透气"; temp >= 28 -> "短袖保持透气"; temp >= 20 -> "轻装出行舒适"; temp >= 12 -> "早晚备件外套"; temp <= 5 -> "厚衣注意保暖"; else -> "注意添衣保暖" }),
            item("sun", "防晒", when { uv >= 8 -> "很强"; uv >= 5 -> "较强"; uv >= 3 -> "中等"; else -> "较弱" }, when { uv >= 8 -> "烈日避免久晒"; uv >= 5 -> "及时补涂防晒"; uv >= 3 -> "遮阳做好防护"; else -> "外出轻度防晒" }),
            item("cold", "感冒", when { temp <= 8 || wind >= 28 -> "易发"; temp <= 16 -> "注意"; else -> "较低" }, when { temp <= 8 -> "注意防寒保暖"; wind >= 28 -> "风大谨防受凉"; temp <= 16 -> "早晚适当添衣"; else -> "保持室内通风" }),
            item("wash", "洗车", when { rain >= 0.1 || wet >= 88 -> "不宜"; wet >= 75 -> "一般"; else -> "适宜" }, when { rain >= 2 -> "雨势明显暂缓"; rain >= 0.1 -> "可能有雨暂缓"; wet >= 88 -> "潮湿天气观望"; wet >= 75 -> "稍后再洗更好"; else -> "适合安排洗车" }),
            item("sport", "运动", when { rain >= 0.1 || wind >= 35 -> "不宜"; temp >= 32 || temp <= 4 -> "注意"; else -> "适宜" }, when { rain >= 0.1 -> "雨天改做室内"; wind >= 35 -> "强风减少户外"; temp >= 32 -> "高温避免暴晒"; temp <= 4 -> "寒冷减少户外"; else -> "适合适量运动" }),
            item("traffic", "交通", when { visibility < 1 || rain >= 8 -> "谨慎"; visibility < 5 || rain >= 0.1 -> "注意"; else -> "顺畅" }, when { visibility < 1 -> "减速保持车距"; visibility < 5 -> "开启灯光慢行"; rain >= 8 -> "暴雨尽量绕行"; rain >= 0.1 -> "路面湿滑慢行"; else -> "路况整体顺畅" }),
            item("fish", "钓鱼", when { rain >= 2 || wind >= 28 -> "不适宜"; wind >= 18 -> "一般"; else -> "适宜" }, when { rain >= 2 -> "雨天不宜垂钓"; wind >= 28 -> "风大注意安全"; wind >= 18 -> "风起垂钓一般"; else -> "适合休闲垂钓" }),
            item("allergy", "过敏", when { wind >= 25 && wet < 60 -> "易过敏"; wind >= 14 -> "注意"; else -> "较低" }, when { wind >= 25 && wet < 60 -> "减少接触花粉"; wind >= 14 -> "敏感人群留意"; wet < 35 -> "干燥注意保湿"; else -> "外出注意清洁" }),
            item("mold", "霉变", when { wet >= 86 -> "易霉变"; wet >= 72 -> "注意"; else -> "较低" }, when { wet >= 86 -> "及时做好除湿"; wet >= 72 -> "保持通风干燥"; rain >= 0.1 -> "潮湿留意霉变"; else -> "环境较为干爽" })
        )
    }

    /** 二级页将当前指标最相关的生活建议置于前排，剩余项目保持稳定顺序。 */
    fun forMetric(items: List<LifeAdviceItem>, kind: MetricKind): List<LifeAdviceItem> =
        prioritize(items, when (kind) {
            MetricKind.UV -> listOf("q5", "q16", "q12", "q3")
            MetricKind.FEELS_LIKE -> listOf("q8", "q3", "q1", "q9")
            MetricKind.WIND -> listOf("q15", "q1", "q4", "q6")
            MetricKind.PRESSURE -> listOf("q8", "q9", "q1", "q15")
            MetricKind.VISIBILITY -> listOf("q15", "q6", "q1", "q7")
            MetricKind.HUMIDITY -> listOf("q14", "q2", "q8", "q13")
        })

    fun forAirQuality(items: List<LifeAdviceItem>): List<LifeAdviceItem> =
        prioritize(items, listOf("q10", "q1", "q7", "q15"))

    fun forSun(items: List<LifeAdviceItem>): List<LifeAdviceItem> =
        prioritize(items, listOf("q5", "q16", "q12", "q3"))

    private fun prioritize(items: List<LifeAdviceItem>, keys: List<String>): List<LifeAdviceItem> {
        val ordered = keys.mapNotNull { key -> items.firstOrNull { it.key == key } }
        return ordered + items.filterNot { item -> keys.contains(item.key) }
    }
}

/**
 * 生活指数的等级词本身携带风险语义。二级页让图标、图标底框和等级文字共用同一
 * 绿色 / 黄色 / 橙色 / 红色等级色；化妆、太阳镜、空调等没有明确风险等级的工具
 * 型指数才使用中性色，避免把单纯用品建议误表达成天气风险。
 */
internal object LifeAdviceStatusStyle {
    enum class Tone { POSITIVE, CAUTION, WARNING, RISK, COOL, INFO }
    enum class Grade { GREEN, YELLOW, ORANGE, RED, NEUTRAL }

    fun toneFor(level: String): Tone {
        val value = level.trim().replace(" ", "")
        if (value.isEmpty() || value == "暂无等级") return Tone.INFO
        return when {
            value.containsAny("不宜", "不适", "易发", "易过敏", "易霉变", "严重", "危险", "极强", "很强", "高风险") -> Tone.RISK
            value.containsAny("炎热", "酷热", "谨慎", "注意", "较强", "较高", "偏高", "一般", "中等", "中") -> Tone.WARNING
            value.containsAny("冷", "寒", "保暖", "凉") -> Tone.COOL
            value.containsAny("舒适", "适宜", "优", "良", "较好", "顺畅", "最弱", "较弱", "较低", "低", "清凉", "轻薄") -> Tone.POSITIVE
            value.containsAny("外套", "清爽", "平稳") -> Tone.CAUTION
            else -> Tone.INFO
        }
    }

    // 保留这一入口给历史调用方；实际显示以 gradeFor() 收敛为四级颜色。
    fun colorFor(level: String, dark: Boolean): Int = colorForGrade(gradeFor(level), dark)

    /** 具备明确好坏含义的等级只使用绿、黄、橙、红四级颜色。 */
    fun gradeFor(level: String): Grade {
        val value = level.trim().replace(" ", "")
        if (value.isEmpty() || value == "暂无等级") return Grade.NEUTRAL
        return when {
            value.containsAny("不宜", "不适", "易发", "易过敏", "易霉变", "严重", "危险", "极强", "很强", "高风险") -> Grade.RED
            value.containsAny("较差", "炎热", "酷热", "谨慎", "注意", "较强", "较高", "偏高") -> Grade.ORANGE
            value.containsAny("中等", "中", "一般", "外套", "清爽", "平稳", "冷", "寒", "保暖", "凉") -> Grade.YELLOW
            value.containsAny("舒适", "适宜", "优", "良", "较好", "顺畅", "最弱", "较弱", "较低", "低", "清凉", "轻薄", "少发") -> Grade.GREEN
            else -> Grade.YELLOW
        }
    }

    /**
     * 二级卡片用这一重载保证状态文字、图标线稿和图标底框取同一色彩。少数没有
     * 强提示含义的用品型指数保留中性提示色，避免“太阳镜”本身被误读为风险等级。
     */
    fun colorFor(item: LifeAdviceItem, dark: Boolean): Int =
        if (usesNeutralIndicatorColor(item)) neutralColor(dark) else colorFor(item.level, dark)

    fun usesNeutralIndicatorColor(item: LifeAdviceItem): Boolean =
        item.key in setOf("q11", "q12", "q13") ||
            item.title.containsAny("空调", "太阳镜", "化妆")

    private fun colorForGrade(grade: Grade, dark: Boolean): Int = when (grade) {
        // 使用 ARGB 常量而非 Color.rgb，保证等级映射也能在本地 JVM 单测中运行。
        Grade.GREEN -> if (dark) 0xFF64DA8F.toInt() else 0xFF18894E.toInt()
        Grade.YELLOW -> if (dark) 0xFFFFD266.toInt() else 0xFF9E6800.toInt()
        Grade.ORANGE -> if (dark) 0xFFFFAB70.toInt() else 0xFFC25212.toInt()
        Grade.RED -> if (dark) 0xFFFF7B7B.toInt() else 0xFFBE2D36.toInt()
        Grade.NEUTRAL -> neutralColor(dark)
    }

    private fun neutralColor(dark: Boolean): Int =
        if (dark) 0xFFB7CDEF.toInt() else 0xFF416591.toInt()

    private fun String.containsAny(vararg words: String): Boolean = words.any(::contains)
}

/** 主页卡片的 3×3 建议图标网格。 */
class LifeAdviceGridView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {
    private var items: List<LifeAdviceItem> = emptyList()
    private val advicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(188, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val iconColor = Color.argb(248, 255, 255, 255)

    fun setAdvice(value: List<LifeAdviceItem>) { items = value.distinctBy { it.key }; requestLayout(); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = ((items.size + 2) / 3).coerceAtLeast(1)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(dp(rows * 82f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return
        val col = width / 3f
        val row = height / ((items.size + 2) / 3).coerceAtLeast(1).toFloat()
        advicePaint.textSize = dp(11.5f)
        items.forEachIndexed { index, item ->
            val cx = col * (index % 3 + .5f)
            val top = row * (index / 3)
            val iconCenterY = top + row * .31f
            // 一级宫格保持轻量线稿，不再为每个图标额外铺设圆角底框。
            LifeAdviceIcon.draw(canvas, context, item.key, cx, iconCenterY, dp(12.6f), iconColor)
            // 主页仅显示和风返回的文字分类；点击整卡可查看每项完整原始建议文本。
            advicePaint.color = Color.WHITE
            canvas.drawText(item.title, cx, top + row * .66f, advicePaint)
            advicePaint.color = Color.argb(188, 255, 255, 255)
            canvas.drawText(item.level, cx, top + row * .89f, advicePaint)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}

/** 详情页建议条：固定容纳三项，避免与详情页横滑手势冲突。 */
class LifeAdviceStripView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {
    private var items: List<LifeAdviceItem> = emptyList()
    private var unsupportedText: String? = null
    private val advicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val iconBackdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var darkTheme = false
    private val iconBorder = WeatherFrostedBorderDrawable(resources.displayMetrics.density, 10f, WeatherSettings.isDarkTheme(context))

    fun setAdvice(value: List<LifeAdviceItem>, dark: Boolean) {
        val freeSource = WeatherSettings.useOpenMeteo(context)
        items = value.take(3).map { item ->
            if (freeSource && item.level.isBlank()) item.copy(level = "当前不支持") else item
        }
        unsupportedText = if (freeSource && items.isEmpty()) "当前不支持" else null
        contentDescription = unsupportedText ?: items.joinToString("，") { "${it.title}：${it.level}" }
        darkTheme = dark
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = max(dp(312f).toInt(), suggestedMinimumWidth)
        setMeasuredDimension(resolveSize(desiredW, widthMeasureSpec), resolveSize(dp(84f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        unsupportedText?.let { text ->
            advicePaint.textSize = dp(14f)
            advicePaint.color = androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary)
            canvas.drawText(text, width / 2f,
                h / 2f - (advicePaint.ascent() + advicePaint.descent()) / 2f, advicePaint)
            return
        }
        val tileWidth = width / max(1, items.size).toFloat()
        items.forEachIndexed { index, item ->
            val cx = tileWidth * (index + .5f)
            // 固定高度内同时容纳图标、项目名称和等级词，保持三项建议始终在一屏内完整呈现。
            val iconCenterY = h * .30f
            val badgeSize = min(tileWidth * .48f, dp(31f))
            val statusColor = LifeAdviceStatusStyle.colorFor(item, darkTheme)
            iconBackdropPaint.color = Color.argb(if (darkTheme) 48 else 32,
                Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor))
            canvas.drawRoundRect(
                cx - badgeSize / 2f, iconCenterY - badgeSize / 2f,
                cx + badgeSize / 2f, iconCenterY + badgeSize / 2f,
                min(dp(10f), badgeSize / 2f), min(dp(10f), badgeSize / 2f), iconBackdropPaint
            )
            iconBorder.setBounds((cx - badgeSize / 2f).toInt(), (iconCenterY - badgeSize / 2f).toInt(),
                (cx + badgeSize / 2f).toInt(), (iconCenterY + badgeSize / 2f).toInt())
            iconBorder.draw(canvas)
            LifeAdviceIcon.draw(canvas, context, item.key, cx, iconCenterY, dp(11.4f), statusColor)
            advicePaint.textSize = dp(10f)
            advicePaint.color = if (darkTheme) Color.argb(224, 244, 248, 255)
            else Color.argb(220, 31, 43, 58)
            canvas.drawText(fitText(item.title, tileWidth - dp(12f)), cx, h * .64f, advicePaint)
            advicePaint.textSize = dp(10.5f)
            advicePaint.color = statusColor
            canvas.drawText(fitText(item.level.ifBlank { item.title }, tileWidth - dp(12f)), cx, h * .87f, advicePaint)
        }
    }

    private fun fitText(value: String, maxWidth: Float): String {
        if (advicePaint.measureText(value) <= maxWidth) return value
        val ellipsis = "…"
        val available = (maxWidth - advicePaint.measureText(ellipsis)).coerceAtLeast(0f)
        val count = advicePaint.breakText(value, true, available, null)
        return value.take(count) + ellipsis
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}

/**
 * 生活建议统一线性图标集。
 *
 * 旧版本混用多套 SVG，且 q10～q13 会回退成带数字的圆点，放在同一宫格或列表里
 * 显得粗细、留白和语义都不一致。这里全部改为同一套圆角线稿，并为和风 16 个
 * 标准生活指数及本地兜底类别绘制对应图形；未知类别也有中性的建议图标，不再显示编号。
 */
internal object LifeAdviceIcon {
    private const val BASE_RADIUS = 16f
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.85f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val softFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun draw(
        canvas: Canvas,
        @Suppress("UNUSED_PARAMETER") context: Context,
        key: String,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int
    ) {
        if (radius <= 0f) return
        stroke.color = color
        softFill.color = Color.argb(
            (Color.alpha(color) * 0.16f).roundToInt(),
            Color.red(color), Color.green(color), Color.blue(color)
        )
        canvas.save()
        canvas.translate(cx, cy)
        val scale = (radius / BASE_RADIUS).coerceAtLeast(0.01f)
        canvas.scale(scale, scale)
        when (semanticFor(key)) {
            Semantic.SPORT -> drawSport(canvas)
            Semantic.WASH -> drawCar(canvas, withDrops = true)
            Semantic.DRESS -> drawDress(canvas)
            Semantic.FISH -> drawFish(canvas)
            Semantic.UV -> drawSun(canvas)
            Semantic.TRAVEL -> drawTravel(canvas)
            Semantic.ALLERGY -> drawAllergy(canvas)
            Semantic.COMFORT -> drawComfort(canvas)
            Semantic.COLD -> drawCold(canvas)
            Semantic.AIR -> drawAir(canvas)
            Semantic.AIR_CONDITIONER -> drawAirConditioner(canvas)
            Semantic.SUNGLASSES -> drawSunglasses(canvas)
            Semantic.MAKEUP -> drawMakeup(canvas)
            Semantic.DRYING -> drawDrying(canvas)
            Semantic.TRAFFIC -> drawCar(canvas, withDrops = false)
            Semantic.SUNSCREEN -> drawSunscreen(canvas)
            Semantic.MOLD -> drawMold(canvas)
            Semantic.GENERIC -> drawGenericAdvice(canvas)
        }
        canvas.restore()
    }

    private enum class Semantic {
        SPORT, WASH, DRESS, FISH, UV, TRAVEL, ALLERGY, COMFORT, COLD, AIR,
        AIR_CONDITIONER, SUNGLASSES, MAKEUP, DRYING, TRAFFIC, SUNSCREEN, MOLD, GENERIC
    }

    private fun semanticFor(key: String): Semantic = when (key) {
        "q1", "sport" -> Semantic.SPORT
        "q2", "wash" -> Semantic.WASH
        "q3", "dress" -> Semantic.DRESS
        "q4", "fish" -> Semantic.FISH
        "q5", "sun" -> Semantic.UV
        "q6", "travel" -> Semantic.TRAVEL
        "q7", "allergy" -> Semantic.ALLERGY
        "q8", "comfort" -> Semantic.COMFORT
        "q9", "cold" -> Semantic.COLD
        "q10" -> Semantic.AIR
        "q11" -> Semantic.AIR_CONDITIONER
        "q12" -> Semantic.SUNGLASSES
        "q13" -> Semantic.MAKEUP
        "q14" -> Semantic.DRYING
        "q15", "traffic" -> Semantic.TRAFFIC
        "q16" -> Semantic.SUNSCREEN
        "mold" -> Semantic.MOLD
        else -> Semantic.GENERIC
    }

    private fun outlinedPath(canvas: Canvas, build: Path.() -> Unit) {
        path.reset()
        path.build()
        canvas.drawPath(path, stroke)
    }

    private fun filledPath(canvas: Canvas, build: Path.() -> Unit) {
        path.reset()
        path.build()
        canvas.drawPath(path, softFill)
        canvas.drawPath(path, stroke)
    }

    private fun roundedPanel(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, corner: Float) {
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, softFill)
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, stroke)
    }

    private fun drawSport(canvas: Canvas) {
        canvas.drawCircle(0f, 0f, 10f, softFill)
        canvas.drawCircle(0f, 0f, 10f, stroke)
        canvas.drawArc(-10f, -10f, 10f, 10f, -62f, 124f, false, stroke)
        canvas.drawArc(-10f, -10f, 10f, 10f, 118f, 124f, false, stroke)
        canvas.drawArc(-8f, -5f, 8f, 11f, 198f, 142f, false, stroke)
        canvas.drawLine(-8f, -4f, 8f, 4f, stroke)
    }

    private fun drawCar(canvas: Canvas, withDrops: Boolean) {
        filledPath(canvas) {
            moveTo(-12f, 3f)
            lineTo(-9f, -4f)
            quadTo(-8f, -7f, -4f, -7f)
            lineTo(5f, -7f)
            quadTo(8f, -7f, 10f, -4f)
            lineTo(12f, 3f)
            lineTo(12f, 8f)
            lineTo(-12f, 8f)
            close()
        }
        canvas.drawCircle(-7f, 8f, 2.6f, stroke)
        canvas.drawCircle(7f, 8f, 2.6f, stroke)
        canvas.drawLine(-5f, -2f, 4f, -2f, stroke)
        if (withDrops) {
            drawDrop(canvas, -8f, -12f, .72f)
            drawDrop(canvas, 0f, -14f, .72f)
            drawDrop(canvas, 8f, -12f, .72f)
        }
    }

    private fun drawDress(canvas: Canvas) {
        filledPath(canvas) {
            moveTo(-6f, -12f)
            quadTo(-3f, -8f, 0f, -8f)
            quadTo(3f, -8f, 6f, -12f)
            lineTo(10f, -8f)
            lineTo(14f, -5f)
            lineTo(9f, 1f)
            lineTo(6f, -1f)
            lineTo(6f, 12f)
            lineTo(-6f, 12f)
            lineTo(-6f, -1f)
            lineTo(-9f, 1f)
            lineTo(-14f, -5f)
            lineTo(-10f, -8f)
            close()
        }
    }

    private fun drawFish(canvas: Canvas) {
        filledPath(canvas) {
            moveTo(-11f, 0f)
            cubicTo(-5f, -8f, 5f, -8f, 11f, 0f)
            cubicTo(5f, 8f, -5f, 8f, -11f, 0f)
            close()
        }
        outlinedPath(canvas) {
            moveTo(-11f, 0f)
            lineTo(-15f, -6f)
            lineTo(-15f, 6f)
            close()
        }
        canvas.drawCircle(5f, -2f, 1.2f, softFill)
        canvas.drawCircle(5f, -2f, 1.2f, stroke)
        canvas.drawArc(-15f, -15f, -3f, -3f, 23f, 165f, false, stroke)
    }

    private fun drawSun(canvas: Canvas) {
        canvas.drawCircle(0f, 0f, 5.2f, softFill)
        canvas.drawCircle(0f, 0f, 5.2f, stroke)
        val rays = arrayOf(
            floatArrayOf(0f, -13f, 0f, -9f), floatArrayOf(0f, 13f, 0f, 9f),
            floatArrayOf(-13f, 0f, -9f, 0f), floatArrayOf(13f, 0f, 9f, 0f),
            floatArrayOf(-9f, -9f, -6.5f, -6.5f), floatArrayOf(9f, -9f, 6.5f, -6.5f),
            floatArrayOf(-9f, 9f, -6.5f, 6.5f), floatArrayOf(9f, 9f, 6.5f, 6.5f)
        )
        rays.forEach { canvas.drawLine(it[0], it[1], it[2], it[3], stroke) }
    }

    private fun drawTravel(canvas: Canvas) {
        roundedPanel(canvas, -11f, -7f, 11f, 11f, 3f)
        canvas.drawLine(-4f, -7f, -4f, -11f, stroke)
        canvas.drawLine(4f, -7f, 4f, -11f, stroke)
        canvas.drawLine(-4f, -11f, 4f, -11f, stroke)
        canvas.drawLine(-4f, 0f, 4f, 0f, stroke)
        canvas.drawCircle(-6f, 13f, 1.3f, stroke)
        canvas.drawCircle(6f, 13f, 1.3f, stroke)
    }

    private fun drawAllergy(canvas: Canvas) {
        canvas.drawCircle(0f, 0f, 3.2f, softFill)
        canvas.drawCircle(0f, 0f, 3.2f, stroke)
        arrayOf(-8f to 0f, 8f to 0f, 0f to -8f, 0f to 8f, -5.8f to -5.8f, 5.8f to -5.8f).forEach { (x, y) ->
            canvas.drawCircle(x, y, 3.3f, stroke)
        }
        canvas.drawCircle(10f, 8f, 1f, stroke)
        canvas.drawCircle(14f, 4f, .8f, stroke)
    }

    private fun drawComfort(canvas: Canvas) {
        canvas.drawCircle(0f, 0f, 11f, softFill)
        canvas.drawCircle(0f, 0f, 11f, stroke)
        canvas.drawCircle(-4f, -2f, .9f, stroke)
        canvas.drawCircle(4f, -2f, .9f, stroke)
        canvas.drawArc(-6f, -1f, 6f, 8f, 18f, 144f, false, stroke)
    }

    private fun drawCold(canvas: Canvas) {
        roundedPanel(canvas, -3.2f, -12f, 3.2f, 6f, 3.2f)
        canvas.drawLine(0f, -7f, 0f, 8f, stroke)
        canvas.drawCircle(0f, 9f, 4.6f, softFill)
        canvas.drawCircle(0f, 9f, 4.6f, stroke)
        canvas.drawLine(9f, -9f, 9f, -1f, stroke)
        canvas.drawLine(5.5f, -5f, 12.5f, -5f, stroke)
        canvas.drawLine(6.5f, -7.5f, 11.5f, -2.5f, stroke)
        canvas.drawLine(11.5f, -7.5f, 6.5f, -2.5f, stroke)
    }

    private fun drawAir(canvas: Canvas) {
        outlinedPath(canvas) {
            moveTo(-13f, -7f)
            cubicTo(-8f, -12f, -3f, -2f, 4f, -7f)
            cubicTo(8f, -10f, 10f, -9f, 12f, -8f)
        }
        outlinedPath(canvas) {
            moveTo(-14f, 0f)
            cubicTo(-8f, -5f, -2f, 5f, 7f, 0f)
            cubicTo(10f, -2f, 12f, -1f, 14f, 1f)
        }
        outlinedPath(canvas) {
            moveTo(-10f, 8f)
            cubicTo(-5f, 4f, 1f, 12f, 8f, 7f)
        }
        canvas.drawCircle(13f, -8f, 1f, stroke)
    }

    private fun drawAirConditioner(canvas: Canvas) {
        roundedPanel(canvas, -12f, -12f, 12f, 12f, 5f)
        canvas.drawCircle(0f, 0f, 2f, softFill)
        canvas.drawCircle(0f, 0f, 2f, stroke)
        repeat(3) { index ->
            canvas.save()
            canvas.rotate(index * 120f)
            outlinedPath(canvas) {
                moveTo(2f, -2f)
                cubicTo(9f, -7f, 9f, 1f, 3f, 4f)
            }
            canvas.restore()
        }
    }

    private fun drawSunglasses(canvas: Canvas) {
        roundedPanel(canvas, -13f, -5f, -2f, 5f, 3f)
        roundedPanel(canvas, 2f, -5f, 13f, 5f, 3f)
        canvas.drawLine(-2f, -1f, 2f, -1f, stroke)
        canvas.drawLine(-13f, -2f, -16f, -5f, stroke)
        canvas.drawLine(13f, -2f, 16f, -5f, stroke)
    }

    private fun drawMakeup(canvas: Canvas) {
        canvas.drawCircle(-5f, 2f, 7.5f, softFill)
        canvas.drawCircle(-5f, 2f, 7.5f, stroke)
        canvas.drawLine(-10f, -7f, 1f, -7f, stroke)
        roundedPanel(canvas, 5f, -10f, 10f, 7f, 1.5f)
        filledPath(canvas) {
            moveTo(5f, -10f)
            lineTo(10f, -10f)
            lineTo(8.5f, -14f)
            lineTo(6.5f, -14f)
            close()
        }
        canvas.drawLine(5f, 2f, 10f, 2f, stroke)
    }

    private fun drawDrying(canvas: Canvas) {
        canvas.drawCircle(-9f, -9f, 3f, softFill)
        canvas.drawCircle(-9f, -9f, 3f, stroke)
        canvas.drawLine(-9f, -15f, -9f, -13f, stroke)
        canvas.drawLine(-15f, -9f, -13f, -9f, stroke)
        canvas.drawLine(-5f, -13f, -4f, -14f, stroke)
        canvas.drawLine(-13f, -13f, -14f, -14f, stroke)
        outlinedPath(canvas) {
            moveTo(1f, -5f)
            quadTo(1f, -10f, 5f, -10f)
            quadTo(9f, -10f, 9f, -5f)
            moveTo(-5f, -2f)
            lineTo(15f, -2f)
            moveTo(-3f, -1f)
            lineTo(-10f, 10f)
            lineTo(10f, 10f)
            close()
        }
    }

    private fun drawSunscreen(canvas: Canvas) {
        roundedPanel(canvas, -7f, -6f, 7f, 12f, 3f)
        roundedPanel(canvas, -4f, -12f, 4f, -7f, 1.5f)
        canvas.drawCircle(0f, 3f, 2.5f, stroke)
        canvas.drawLine(-4f, 3f, -2.8f, 3f, stroke)
        canvas.drawLine(4f, 3f, 2.8f, 3f, stroke)
        canvas.drawLine(0f, -1f, 0f, -.3f, stroke)
        canvas.drawLine(0f, 6.3f, 0f, 7f, stroke)
    }

    private fun drawMold(canvas: Canvas) {
        roundedPanel(canvas, -12f, -10f, 12f, 12f, 4f)
        canvas.drawCircle(-5f, -3f, 2f, stroke)
        canvas.drawCircle(3f, 1f, 2.5f, stroke)
        canvas.drawCircle(7f, -5f, 1.4f, stroke)
        canvas.drawLine(-7f, 7f, 7f, 7f, stroke)
        canvas.drawLine(-7f, 3f, -7f, 7f, stroke)
    }

    private fun drawGenericAdvice(canvas: Canvas) {
        roundedPanel(canvas, -11f, -11f, 11f, 11f, 6f)
        canvas.drawCircle(-3f, -2f, 1f, stroke)
        canvas.drawCircle(3f, -2f, 1f, stroke)
        canvas.drawArc(-6f, -1f, 6f, 7f, 18f, 144f, false, stroke)
        canvas.drawLine(0f, -16f, 0f, -13f, stroke)
        canvas.drawLine(-16f, 0f, -13f, 0f, stroke)
        canvas.drawLine(16f, 0f, 13f, 0f, stroke)
    }

    private fun drawDrop(canvas: Canvas, x: Float, y: Float, scale: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        filledPath(canvas) {
            moveTo(0f, -5f)
            cubicTo(-4f, 0f, -4f, 3f, 0f, 5f)
            cubicTo(4f, 3f, 4f, 0f, 0f, -5f)
            close()
        }
        canvas.restore()
    }
}

class LifeAdviceIconView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {
    var adviceKey: String = ""
        set(value) { field = value; invalidate() }
    var iconColor: Int = Color.rgb(52, 92, 126)
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        LifeAdviceIcon.draw(
            canvas, context, adviceKey, width / 2f, height / 2f,
            kotlin.math.min(width, height) * .30f, iconColor
        )
    }
}
