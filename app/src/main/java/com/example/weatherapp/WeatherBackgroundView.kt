package com.example.weatherapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.example.weatherapp.WeatherBackgroundPalette.Type
import java.util.Random
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// 动态天气背景：按天气类型 + 白天/夜晚绘制天空渐变，并播放
// 太阳/月亮/云、星星、雨、雪、雾、风等动画。
// 太阳、月亮、云朵沿用天气图标素材；其余元素使用同一套扁平填色与圆润线条语言。
class WeatherBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var type = Type.CLEAR
    private var night = false
    private var lastSkycon: String? = null
    private var rainLevel = 0 // 0 小雨 … 3 暴雨
    private var snowLevel = 0 // 0 小雪 … 3 暴雪

    private var animator: ValueAnimator? = null
    private var landscapeAnimator: ValueAnimator? = null
    private var time = 0f // 动画计时（秒）
    // 地形不是常驻装饰：由当前天气页在读到底时淡入，其他位置保持纯天气底色。
    private var bottomLandscapeProgress = 0f
    // 未完成顶部栏测量时也先留出最小安全区，避免冷启动首帧的云压住地址文字。
    private var cloudSafeTopPx = dp(96f).toFloat()

    private val skyPaint = Paint()
    private var skyColors = WeatherBackgroundPalette.colors("CLEAR_DAY", false)
    private val dynamicPath = Path()
    private val bottomPath = Path()
    private val landscapePath = Path()

    // ---- 动画元素（setWeather 时按天气重建） ----
    // loopStart: 云只在屏幕右侧这一比例的区域内循环（0 = 全屏），用来避开左上角的城市文字
    private class Cloud(
        val seedX: Float,
        val yRatio: Float,
        val scale: Float,
        val speed: Float,
        val loopStart: Float
    )

    private var clouds = ArrayList<Cloud>()
    private var cloudAlpha = 225
    private var rainX0 = FloatArray(0)
    private var rainOff = FloatArray(0)
    private var rainLen = FloatArray(0)
    private var rainCount = 0
    private var snowX0 = FloatArray(0)
    private var snowOff = FloatArray(0)
    private var snowSize = FloatArray(0)
    private var snowSway = FloatArray(0)
    private var snowCount = 0
    private var starX = FloatArray(0)
    private var starY = FloatArray(0)
    private var starSize = FloatArray(0)
    private var starPhase = FloatArray(0)
    private var starFreq = FloatArray(0)
    private var starAlpha = FloatArray(0)
    private var starCount = 0
    private var windSeed = FloatArray(0)
    private var windY = FloatArray(0)
    private var windLen = FloatArray(0)
    private var windCount = 0
    // 背景天气运动速度统一缩放（当前为 60%）
    private val motionScale = 0.6f

    // 画笔
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 213, 79) }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(236, 241, 255)
        style = Paint.Style.FILL
    }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(197, 211, 249)
        strokeCap = Paint.Cap.ROUND
    }
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(236, 241, 255)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val windPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(253, 182, 71)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(221, 229, 255)
        style = Paint.Style.FILL
    }
    private val bottomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val landscapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val landscapeDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // 太阳/月亮/云朵使用现有 tianqitubiao 天气图标素材
    private var sunBmp: Bitmap? = null
    private var moonBmp: Bitmap? = null
    private var cloudBmp: Bitmap? = null
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // 背景是否偏暗（决定状态栏是否用浅色图标）
    val isDarkScene: Boolean
        get() = night || (type != Type.CLEAR && type != Type.PARTLY)

    // ===== 对外接口：根据彩云天气代码 + 实时昼夜状态切换背景 =====
    fun setWeather(skycon: String?, night: Boolean) {
        val s = skycon ?: return
        // 昼夜由调用方按实时日出/日落时间计算，不再跟随手机深浅色模式
        this.night = night
        lastSkycon = s
        type = WeatherBackgroundPalette.typeOf(s)
        rainLevel = when {
            s == "LIGHT_RAIN" -> 0
            s == "MODERATE_RAIN" -> 1
            s == "HEAVY_RAIN" -> 2
            else -> 3
        }
        snowLevel = when {
            s == "LIGHT_SNOW" -> 0
            s == "MODERATE_SNOW" -> 1
            s == "HEAVY_SNOW" -> 2
            else -> 3
        }
        rebuild(night)
        invalidate()
    }

    fun setBottomLandscapeVisible(visible: Boolean, animate: Boolean = true) {
        val target = if (visible) 1f else 0f
        if (kotlin.math.abs(target - bottomLandscapeProgress) < 0.01f) return
        landscapeAnimator?.cancel()
        if (!animate) {
            bottomLandscapeProgress = target
            invalidate()
            return
        }
        landscapeAnimator = ValueAnimator.ofFloat(bottomLandscapeProgress, target).apply {
            duration = if (visible) 220L else 140L
            addUpdateListener {
                bottomLandscapeProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 由天气页滚动位置直接驱动，避免底部地形在到达底部时另起一段淡入动画。 */
    fun setBottomLandscapeProgress(progress: Float) {
        val target = progress.coerceIn(0f, 1f)
        if (kotlin.math.abs(target - bottomLandscapeProgress) < 0.002f) return
        landscapeAnimator?.cancel()
        bottomLandscapeProgress = target
        invalidate()
    }

    fun setCloudSafeTop(safeTop: Int) {
        val next = safeTop.coerceAtLeast(dp(96f)).toFloat()
        if (kotlin.math.abs(next - cloudSafeTopPx) < 1f) return
        cloudSafeTopPx = next
        invalidate()
    }

    // ===== 动画 =====
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_800_000L // 30 分钟循环一次
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    time = (currentPlayTime / 1000f) % 1800f
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    // ===== 按天气重建元素 =====
    private fun rebuild(night: Boolean) {
        skyColors = WeatherBackgroundPalette.colors(lastSkycon, night)
        rebuildShader()

        val rand = Random()

        // 云：晴天没有云；每条云固定在独立的横向"泳道"（都在页面上半部分），
        // 泳道间距大于云高，任何情况下云朵都不会相互重叠
        clouds.clear()
        val lanes = when (type) {
            Type.CLEAR -> emptyList()
            Type.PARTLY -> listOf(
                floatArrayOf(0.07f, 0.55f),
                floatArrayOf(0.21f, 0f),
                floatArrayOf(0.35f, 0f)
            )
            Type.CLOUDY, Type.RAIN, Type.SNOW -> listOf(
                floatArrayOf(0.04f, 0.55f),
                floatArrayOf(0.18f, 0f),
                floatArrayOf(0.32f, 0f),
                floatArrayOf(0.46f, 0f)
            )
            else -> listOf(
                floatArrayOf(0.08f, 0.55f),
                floatArrayOf(0.24f, 0f)
            )
        }
        // 云朵透明度统一 90%（约 alpha 230）
        cloudAlpha = 230
        for (lane in lanes) {
            // 云朵整体再缩小一点点，且每朵只在自己的泳道内横向循环
            val scale = 0.28f + rand.nextFloat() * 0.10f
            val speed = when (type) {
                Type.WIND -> 55f + rand.nextFloat() * 30f
                Type.CLOUDY, Type.RAIN -> 14f + rand.nextFloat() * 10f
                else -> 8f + rand.nextFloat() * 8f
            } * motionScale
            clouds.add(
                Cloud(
                    rand.nextFloat(),
                    lane[0],
                    scale,
                    speed,
                    lane[1]
                )
            )
        }

        // 雨：雨量越大越密
        rainCount = if (type == Type.RAIN) {
            when (rainLevel) { 0 -> 18; 1 -> 28; 2 -> 40; else -> 52 }
        } else 0
        if (rainCount > 0) {
            rainX0 = FloatArray(rainCount)
            rainOff = FloatArray(rainCount)
            rainLen = FloatArray(rainCount)
            for (i in 0 until rainCount) {
                rainX0[i] = rand.nextFloat()
                rainOff[i] = rand.nextFloat()
                rainLen[i] = dp(8f + rand.nextFloat() * 5f).toFloat()
            }
        }

        // 雪
        snowCount = if (type == Type.SNOW) {
            when (snowLevel) { 0 -> 18; 1 -> 28; 2 -> 40; else -> 54 }
        } else 0
        if (snowCount > 0) {
            snowX0 = FloatArray(snowCount)
            snowOff = FloatArray(snowCount)
            snowSize = FloatArray(snowCount)
            snowSway = FloatArray(snowCount)
            for (i in 0 until snowCount) {
                snowX0[i] = rand.nextFloat()
                snowOff[i] = rand.nextFloat()
                snowSize[i] = dp(1.5f + rand.nextFloat() * 1.8f).toFloat()
                snowSway[i] = dp(4f + rand.nextFloat() * 8f).toFloat()
            }
        }

        // 星星：夜晚才显示，阴雨天被云遮住所以少
        starCount = if (night) {
            when (type) {
                Type.CLEAR -> 58
                Type.PARTLY -> 40
                Type.CLOUDY -> 0 // 阴天夜晚云层遮蔽，不显示星星
                Type.RAIN -> 9
                Type.SNOW -> 0
                else -> 18
            }
        } else 0
        if (starCount > 0) {
            starX = FloatArray(starCount)
            starY = FloatArray(starCount)
            starSize = FloatArray(starCount)
            starPhase = FloatArray(starCount)
            starFreq = FloatArray(starCount)
            starAlpha = FloatArray(starCount)
            for (i in 0 until starCount) {
                starX[i] = rand.nextFloat()
                starY[i] = 0.05f + rand.nextFloat() * 0.55f
                starSize[i] = dp(1.2f + rand.nextFloat() * 1.5f).toFloat()
                starPhase[i] = rand.nextFloat() * 6.2831853f
                starFreq[i] = 0.4f + rand.nextFloat() * 1.6f
                starAlpha[i] = 145f + rand.nextFloat() * 100f
            }
        }

        // 风线（大风天）
        windCount = if (type == Type.WIND) 10 else 0
        if (windCount > 0) {
            windSeed = FloatArray(windCount)
            windY = FloatArray(windCount)
            windLen = FloatArray(windCount)
            for (i in 0 until windCount) {
                windSeed[i] = rand.nextFloat()
                windY[i] = 0.08f + rand.nextFloat() * 0.42f
                windLen[i] = dp(42f + rand.nextFloat() * 70f).toFloat()
            }
        }
    }

    private fun rebuildShader() {
        if (width > 0 && height > 0) {
            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(), skyColors,
                floatArrayOf(0f, 0.34f, 0.68f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader()
    }

    // ===== 绘制 =====
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (skyPaint.shader == null) rebuildShader()

        // 天空
        canvas.drawRect(0f, 0f, w, h, skyPaint)

        // 星 → 太阳/月亮 → 云 → 雨/雪/风/雾 → 底部极少量地形
        if (starCount > 0) drawStars(canvas, w, h)
        if (type == Type.CLEAR || type == Type.PARTLY) {
            if (night) drawMoon(canvas, w, h) else drawSun(canvas, w, h)
        }
        drawClouds(canvas, w, h)
        if (type == Type.RAIN) drawRain(canvas, w, h)
        if (type == Type.SNOW) drawSnow(canvas, w, h)
        if (type == Type.WIND) drawWind(canvas, w, h)
        if (type == Type.HAZE) drawFog(canvas, w, h)
        drawBottomLandscape(canvas, w, h)
    }

    // 太阳：tq-1 太阳图标素材 + 柔和光晕
    private fun drawSun(canvas: Canvas, w: Float, h: Float) {
        val sx = w * 0.80f
        val sy = h * 0.15f
        val r = dp(26f).toFloat()

        // 最外层柔和光晕
        sunPaint.alpha = 16
        canvas.drawCircle(sx, sy, r * 3.8f, sunPaint)
        sunPaint.alpha = 26
        canvas.drawCircle(sx, sy, r * 3.0f, sunPaint)
        sunPaint.alpha = 42
        canvas.drawCircle(sx, sy, r * 2.2f, sunPaint)
        sunPaint.alpha = 75
        canvas.drawCircle(sx, sy, r * 1.5f, sunPaint)

        // 太阳本体使用 tq-1 图标素材（自带散射光线，保留原色）；
        // 以极慢速度绕中心旋转，让晴天背景保持自然的生命感而不干扰阅读。
        val bmp = sunBmp ?: loadAsset("tq-1.svg", dp(96f)).also { sunBmp = it }
        bmp?.let {
            iconPaint.alpha = 255
            val size = r * 3.6f
            canvas.save()
            canvas.rotate((time * 1.2f) % 360f, sx, sy)
            canvas.drawBitmap(
                it, null,
                RectF(sx - size / 2f, sy - size / 2f, sx + size / 2f, sy + size / 2f),
                iconPaint
            )
            canvas.restore()
        }
    }

    // 月亮：yewan 月牙图标素材
    private fun drawMoon(canvas: Canvas, w: Float, h: Float) {
        val mx = w * 0.80f
        val my = h * 0.19f
        val r = dp(18f).toFloat()
        val bmp = moonBmp ?: loadAsset("yewan.svg", dp(64f)).also { moonBmp = it }
        bmp?.let {
            iconPaint.alpha = 255
            val size = r * 5.04f
            canvas.drawBitmap(
                it, null,
                RectF(mx - size / 2f, my - size / 2f, mx + size / 2f, my + size / 2f),
                iconPaint
            )
        }
    }

    private fun drawStars(canvas: Canvas, w: Float, h: Float) {
        for (i in 0 until starCount) {
            val tw = 0.5f + 0.5f * sin(time * starFreq[i] + starPhase[i])
            val cx = starX[i] * w
            val cy = starY[i] * h
            val radius = starSize[i]
            val alpha = (starAlpha[i] * (0.52f + 0.48f * tw)).toInt().coerceIn(135, 255)
            starPaint.alpha = alpha
            drawSparkle(canvas, cx, cy, radius)
        }
    }

    // 四角小光点：比随机圆点更接近天气图标中的几何填色。
    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        dynamicPath.reset()
        dynamicPath.moveTo(cx, cy - radius)
        dynamicPath.lineTo(cx + radius * 0.42f, cy - radius * 0.42f)
        dynamicPath.lineTo(cx + radius, cy)
        dynamicPath.lineTo(cx + radius * 0.42f, cy + radius * 0.42f)
        dynamicPath.lineTo(cx, cy + radius)
        dynamicPath.lineTo(cx - radius * 0.42f, cy + radius * 0.42f)
        dynamicPath.lineTo(cx - radius, cy)
        dynamicPath.lineTo(cx - radius * 0.42f, cy - radius * 0.42f)
        dynamicPath.close()
        canvas.drawPath(dynamicPath, starPaint)
    }

    // 云：yin 云朵图标素材，缓慢水平飘动
    private fun drawClouds(canvas: Canvas, w: Float, h: Float) {
        val bmp = cloudBmp ?: loadAsset("yin.svg", dp(160f)).also { cloudBmp = it }
        if (bmp == null) return
        iconPaint.alpha = cloudAlpha
        var previousBottom = cloudSafeTopPx
        for (c in clouds) {
            val r = dp(64f) * c.scale
            val span = r * 4.5f + w * (1f - c.loopStart)
            // 右半屏循环的云从右半区内部进入，永远不伸到左侧文字区；全屏循环的云从屏幕外滑入
            val cx = if (c.loopStart > 0f) {
                frac(time * c.speed / span + c.seedX) * span + w * c.loopStart + r * 2.25f
            } else {
                frac(time * c.speed / span + c.seedX) * span - r * 2.25f
            }
            val size = r * 4.5f
            // 云体（不是仅中心点）必须完整避开地址栏；相邻泳道也保留间距，
            // 避免被整体下移后互相重叠。
            val minCenter = cloudSafeTopPx + size / 2f + dp(10f)
            val cy = maxOf(c.yRatio * h, minCenter, previousBottom + size / 2f + dp(12f))
            previousBottom = cy + size / 2f
            canvas.drawBitmap(
                bmp, null,
                RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f),
                iconPaint
            )
        }
    }

    // 与其他视图共用原始位图，保留素材原始位置，不使用居中图标缓存。
    private fun loadAsset(name: String, px: Int): Bitmap? =
        WeatherSvgRasterCache.load(context, name, px)

    // 雨：使用天气图标同色系的圆头短线，只在天空上半区运动，避免压满信息区。
    private fun drawRain(canvas: Canvas, w: Float, h: Float) {
        val angle = 0.75f
        val cosA = cos(angle)
        val sinA = sin(angle)
        val speed = (1000f + rainLevel * 250f) * motionScale
        val upperH = h * 0.58f
        val path = upperH / sinA + 260f
        val wrap = w + 400f
        val xPer = path * cosA / wrap
        rainPaint.strokeWidth = dp(2.2f + rainLevel * 0.25f).toFloat()
        rainPaint.color = if (night) Color.rgb(197, 211, 249) else Color.rgb(221, 229, 255)
        rainPaint.alpha = 118 + rainLevel * 16
        for (i in 0 until rainCount) {
            val p = frac(time * speed / path + rainOff[i])
            val x = frac(p * xPer + rainX0[i]) * wrap - 200f
            val y = -200f + p * path * sinA
            val len = rainLen[i]
            canvas.drawLine(x, y, x + len * cosA, y + len * sinA, rainPaint)
        }
    }

    // 雪：六向圆头雪花，使用图标中的冰蓝色，运动范围收在上半区。
    private fun drawSnow(canvas: Canvas, w: Float, h: Float) {
        val speed = (46f + snowLevel * 26f) * motionScale
        val upperH = h * 0.58f
        val path = upperH + 80f
        snowPaint.color = if (night) Color.rgb(197, 211, 249) else Color.rgb(236, 241, 255)
        snowPaint.strokeWidth = dp(1.35f).toFloat()
        snowPaint.alpha = 145 + snowLevel * 18
        for (i in 0 until snowCount) {
            val p = frac(time * speed / path + snowOff[i])
            val y = -80f + p * path
            val x = snowX0[i] * w + sin(time * 0.8f * motionScale + snowOff[i] * 6.2831853f) * snowSway[i]
            drawSnowflake(canvas, x, y, snowSize[i])
        }
    }

    private fun drawSnowflake(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        for (arm in 0 until 3) {
            val angle = arm * Math.PI.toFloat() / 3f
            val dx = cos(angle) * radius
            val dy = sin(angle) * radius
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, snowPaint)
        }
        snowPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius * 0.22f, snowPaint)
        snowPaint.style = Paint.Style.STROKE
    }

    // 风：使用图标同样的圆润弧线，而不是横向直线。
    private fun drawWind(canvas: Canvas, w: Float, h: Float) {
        val upperH = h * 0.56f
        windPaint.strokeWidth = dp(2.8f).toFloat()
        windPaint.color = if (night) Color.rgb(255, 191, 64) else Color.rgb(253, 182, 71)
        windPaint.alpha = 90
        val span = w + 360f
        for (i in 0 until windCount) {
            val x = frac(time * 300f * motionScale / span + windSeed[i]) * span - 180f
            val y = windY[i] * upperH
            val len = windLen[i]
            dynamicPath.reset()
            dynamicPath.moveTo(x, y)
            dynamicPath.cubicTo(
                x + len * 0.22f, y - dp(8f),
                x + len * 0.54f, y + dp(8f),
                x + len, y
            )
            canvas.drawPath(dynamicPath, windPaint)
        }
    }

    // 雾：扁平圆角短带，保持图标中的柔和块面，不再使用大面积白色椭圆。
    private fun drawFog(canvas: Canvas, w: Float, h: Float) {
        val fogH = dp(10f).toFloat()
        val span = w * 2.2f
        for (i in 0 until 3) {
            val cx = frac(time * (6f + 4f * i) * motionScale / span + i * 0.31f) * span - w * 0.8f
            val cy = h * (0.22f + 0.10f * i)
            fogPaint.color = if (night) Color.rgb(197, 211, 249) else Color.rgb(221, 229, 255)
            fogPaint.alpha = (48 - i * 10).toInt()
            canvas.drawRoundRect(
                cx - w * (0.32f - i * 0.03f), cy - fogH / 2,
                cx + w * (0.32f - i * 0.03f), cy + fogH / 2,
                fogH / 2, fogH / 2, fogPaint
            )
        }
    }

    // 底部地形：参考插画中的层叠山丘，但把高度压缩到画面最低约 10%，避免干扰天气信息。
    private fun drawBottomLandscape(canvas: Canvas, w: Float, h: Float) {
        if (bottomLandscapeProgress <= 0.005f) return
        val tones = landscapeTones()
        // 与底部来源信息使用同一滚动进度：图形从屏幕底部自然上移进入，
        // 不再以 alpha 淡入，避免在读到底时出现与内容脱节的渐变。
        canvas.save()
        canvas.translate(0f, (1f - bottomLandscapeProgress) * h * 0.14f)
        fun alpha(value: Int): Int = value

        // 读到底后使用更实的颜色层次，仍让底色在边缘自然透出。
        landscapePaint.color = Color.argb(alpha(if (night) 108 else 138), Color.red(tones[0]), Color.green(tones[0]), Color.blue(tones[0]))
        landscapePath.reset()
        landscapePath.moveTo(0f, h)
        landscapePath.cubicTo(w * 0.16f, h * 0.90f, w * 0.35f, h * 0.96f, w * 0.53f, h * 0.94f)
        landscapePath.cubicTo(w * 0.70f, h * 0.92f, w * 0.86f, h * 0.90f, w, h * 0.93f)
        landscapePath.lineTo(w, h)
        landscapePath.close()
        canvas.drawPath(landscapePath, landscapePaint)

        // 中景：一层更柔和的圆弧，保持扁平插画风格。
        landscapePaint.color = Color.argb(alpha(if (night) 138 else 170), Color.red(tones[1]), Color.green(tones[1]), Color.blue(tones[1]))
        landscapePath.reset()
        landscapePath.moveTo(0f, h)
        landscapePath.cubicTo(w * 0.18f, h * 0.94f, w * 0.37f, h * 0.98f, w * 0.56f, h * 0.96f)
        landscapePath.cubicTo(w * 0.73f, h * 0.93f, w * 0.87f, h * 0.94f, w, h * 0.95f)
        landscapePath.lineTo(w, h)
        landscapePath.close()
        canvas.drawPath(landscapePath, landscapePaint)

        // 前景：只占最底部一小段，颜色更接近图标的深色块面。
        landscapePaint.color = Color.argb(alpha(if (night) 172 else 202), Color.red(tones[2]), Color.green(tones[2]), Color.blue(tones[2]))
        landscapePath.reset()
        landscapePath.moveTo(0f, h)
        landscapePath.cubicTo(w * 0.16f, h * 0.98f, w * 0.34f, h * 0.95f, w * 0.53f, h * 0.985f)
        landscapePath.cubicTo(w * 0.71f, h * 0.96f, w * 0.87f, h * 0.965f, w, h * 0.97f)
        landscapePath.lineTo(w, h)
        landscapePath.close()
        canvas.drawPath(landscapePath, landscapePaint)

        // 极少量圆形树冠，贴着底边出现，不延伸到信息主体区域。
        landscapeDetailPaint.color = Color.argb(alpha(if (night) 158 else 190), Color.red(tones[2]), Color.green(tones[2]), Color.blue(tones[2]))
        drawTreeCluster(canvas, w * 0.08f, h, 0.85f)
        drawTreeCluster(canvas, w * 0.18f, h, 0.55f)
        drawTreeCluster(canvas, w * 0.86f, h, 0.72f)

        // 各天气只保留一个很轻的底边提示：雨是水纹，雪是雪沿，风是弧线，雾是短带。
        val accent = if (night) Color.rgb(197, 211, 249) else Color.rgb(221, 229, 255)
        bottomPaint.color = accent
        bottomPaint.alpha = alpha(if (night) 74 else 86)
        bottomPaint.strokeWidth = dp(1.5f).toFloat()
        bottomPath.reset()
        when (type) {
            Type.RAIN -> {
                bottomPath.moveTo(w * 0.70f, h * 0.985f)
                bottomPath.quadTo(w * 0.79f, h * 0.965f, w * 0.88f, h * 0.985f)
            }
            Type.SNOW -> {
                landscapeDetailPaint.color = Color.argb(alpha(if (night) 120 else 148), Color.red(accent), Color.green(accent), Color.blue(accent))
                canvas.drawOval(w * 0.68f, h * 0.975f, w * 0.82f, h * 1.01f, landscapeDetailPaint)
                canvas.drawOval(w * 0.80f, h * 0.98f, w * 0.92f, h * 1.01f, landscapeDetailPaint)
                canvas.restore()
                return
            }
            Type.WIND -> {
                bottomPaint.color = Color.rgb(253, 182, 71)
                bottomPaint.alpha = alpha(if (night) 78 else 94)
                bottomPath.moveTo(w * 0.70f, h * 0.982f)
                bottomPath.cubicTo(w * 0.78f, h * 0.958f, w * 0.88f, h * 0.998f, w * 0.96f, h * 0.975f)
            }
            Type.HAZE -> {
                bottomPath.moveTo(w * 0.74f, h * 0.97f)
                bottomPath.lineTo(w * 0.94f, h * 0.97f)
            }
            else -> {
                canvas.restore()
                return
            }
        }
        canvas.drawPath(bottomPath, bottomPaint)
        canvas.restore()
    }

    private fun drawTreeCluster(canvas: Canvas, x: Float, h: Float, scale: Float) {
        val radius = dp(18f).toFloat() * scale
        val base = h * 1.005f
        canvas.drawCircle(x, base - radius * 0.76f, radius * 1.15f, landscapeDetailPaint)
        canvas.drawCircle(x + radius * 0.82f, base - radius * 0.62f, radius * 0.82f, landscapeDetailPaint)
        canvas.drawCircle(x - radius * 0.78f, base - radius * 0.48f, radius * 0.68f, landscapeDetailPaint)
    }

    private fun landscapeTones(): IntArray = when {
        night -> when (type) {
            Type.SNOW -> intArrayOf(Color.rgb(110, 139, 169), Color.rgb(76, 108, 141), Color.rgb(47, 77, 112))
            Type.RAIN, Type.HAZE -> intArrayOf(Color.rgb(58, 84, 119), Color.rgb(42, 67, 101), Color.rgb(27, 49, 84))
            Type.WIND -> intArrayOf(Color.rgb(55, 95, 126), Color.rgb(40, 78, 108), Color.rgb(27, 58, 88))
            else -> intArrayOf(Color.rgb(67, 101, 142), Color.rgb(46, 78, 119), Color.rgb(27, 53, 91))
        }
        else -> when (type) {
            Type.CLEAR, Type.PARTLY -> intArrayOf(Color.rgb(170, 215, 126), Color.rgb(116, 184, 111), Color.rgb(68, 139, 83))
            Type.SNOW -> intArrayOf(Color.rgb(213, 229, 238), Color.rgb(168, 197, 214), Color.rgb(119, 157, 179))
            Type.RAIN -> intArrayOf(Color.rgb(124, 159, 179), Color.rgb(89, 126, 149), Color.rgb(58, 94, 119))
            Type.HAZE -> intArrayOf(Color.rgb(181, 193, 187), Color.rgb(137, 158, 151), Color.rgb(94, 121, 112))
            Type.WIND -> intArrayOf(Color.rgb(127, 190, 190), Color.rgb(88, 158, 151), Color.rgb(54, 119, 105))
            Type.CLOUDY -> intArrayOf(Color.rgb(158, 178, 183), Color.rgb(116, 145, 155), Color.rgb(78, 111, 126))
        }
    }

    private fun frac(v: Float): Float = v - Math.floor(v.toDouble()).toFloat()

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
