package com.example.weatherapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

// 毛玻璃卡片（按 BlurLayout 库的思路实现）：
// 外部每帧把 Activity 根视图画面截成小图喂进来，这里裁出卡片所在区域做高斯模糊，
// 放大后画成圆角背景，再叠一层玻璃提亮渐变，效果类似 iOS 控制中心/通知中心。
// 模糊的是"卡片背后的真实画面"，所以卡片滚动到哪、模糊就跟随到哪。
open class BlurCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var cornerRadius = 16f * density
    // 玻璃提亮渐变：卡片本身带 10% 白色（顶部蒙版在外部 setTint 为完全透明）
    private var tintTop = Color.argb(26, 255, 255, 255)
    private var tintBottom = Color.argb(26, 255, 255, 255)
    // 预警入口专用：颜色叠加在白色底上，低透明度时也能保持原色相（不会被深色背景拉成灰褐）
    private var tintWhiteBase = 0f
    private var independentTintOpacity = false
    private val independentTintPaint = Paint()
    private var showStroke = true
    // 上下边描边白色透明度：约 25%，保留左右渐隐与圆角分段逻辑。
    private var strokeAlpha = 64
    // 预警等强调色卡片可关闭白色内侧光晕，仅保留自身的描边和颜色。
    private var showInnerGlow = false
    // 启用淡侧边和内侧光晕的增强边缘效果；昼夜可共用，仅玻璃填充色不同。
    private var useEnhancedEdgeStyle = true
    // 首页天气卡片可分别调整顶部描边粗细、侧边亮度与仅底部的向内泛光。
    // 默认保持旧卡片行为，预览页操作按钮不受天气卡片专用样式影响。
    private var topStrokeWidthScale = 1f
    private var sideStrokeAlphaOverride: Int? = null
    private var bottomInsetGlowVisible = false
    // 轻量外阴影沿完整圆角轮廓出现，不改变现有白色描边或卡片填充。
    // 顶部内容蒙版会通过 setStrokeVisible(false) 一并关闭，避免形成无意义的暗边。
    private var edgeShadowVisible = true
    private var frostedMaterial = false
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frostTexturePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frostSheen: Shader? = null
    private var frostEdge: Shader? = null
    private var frostWidth = -1
    private var frostHeight = -1

    /** 只由普通天气卡开启；预览按钮、顶部蒙版和预警沿用原有材质。 */
    fun setFrostedMaterial(enabled: Boolean) {
        if (frostedMaterial == enabled) return
        frostedMaterial = enabled
        setBlurRadius(density * if (enabled) WeatherCardStyle.FROST_BLUR_DP else 28.8f)
        setEdgeShadowVisible(edgeShadowVisible)
        invalidate()
    }
    // API 31+（Android 12+）：绘制时用 GPU 的 RenderEffect 直接模糊，不再走 CPU 盒式模糊，
    // 滚动时模糊可以逐帧跟随截图，几乎零延迟。真机是 Android 16，走这条快路径。
    private val useGpuBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // 默认毛玻璃减弱至旧效果的 60%，文字与天气背景仍能保持层次。
    private var blurRadius = 28.8f * density
    // GPU 模糊效果缓存：硬件渲染在 drawRenderNode 录制时才读取 effect，
    // 所以设置后要一直保留，不能在 draw 里马上清掉（清掉等于没模糊）
    private var blurEffect: RenderEffect? = null
    private val nodeDstRect=RectF()
    private val tintBounds=Rect()
    private var cachedTintTop=0
    private var cachedTintBottom=0
    private var tintShader: Shader?=null
    private val gpuFadeBounds=Rect()
    private var gpuFadeStart=Float.NaN
    private var gpuFadeEnd=Float.NaN
    private var gpuFadeShader: Shader?=null
    private val dstIn=PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    // 小尺寸遮罩（如风级圆点）可调小模糊半径，避免把周围刻度/文字晕成光斑
    fun setBlurRadius(radius: Float) {
        if(blurRadius==radius && (!useGpuBlur || blurEffect!=null)) return
        blurRadius = radius
        blurEffect = if (useGpuBlur) {
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        } else {
            null
        }
        invalidate()
    }

    // true 表示这块玻璃模糊的是"实时内容"（如顶部地址栏蒙版），
    // 而不是卡片背后的背景；刷新时用另一套截图源，让所有卡片内容统一被蒙版模糊
    var blursContentInsteadOfBackground = false

    // 底部下边缘向下淡化高度（0 = 不淡化）：顶部蒙版下缘做渐变过渡
    private var bottomFadePx = 0f
    // 指定淡化范围时，起点以本 View 顶部为基准，终点以底部内缩为基准。
    // 这让地址栏能从“城市文字行底部”开始精确过渡，而不是只能从固定底部高度过渡。
    private var fadeStartPx = Float.NaN
    private var fadeEndInsetPx = 0f

    fun setBottomFadeHeight(fadePx: Float) {
        bottomFadePx = fadePx
        fadeStartPx = Float.NaN
        invalidate()
    }

    fun setVerticalFadeRange(startPx: Float, clearEndInsetPx: Float = 0f) {
        fadeStartPx = startPx.coerceAtLeast(0f)
        fadeEndInsetPx = clearEndInsetPx.coerceAtLeast(0f)
        invalidate()
    }

    // 模糊缓存：后台线程独占，尺寸不变就复用，避免每帧申请内存
    private var cropBmp: Bitmap? = null
    // 双缓冲：后台写 writeBlur，主线程 commitBlur() 时换到 frontBlur 显示，
    // 保证绘制时永远引用一张完整的模糊图，不会看到画到一半的背景
    private var writeBlur: Bitmap? = null
    private var frontBlur: Bitmap? = null

    private var boxA: IntArray? = null
    private var boxB: IntArray? = null
    private var pixels: IntArray? = null

    private val glass = GlassBackground()
    private val glassPaint = Paint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.98f
        color = Color.argb(51, 255, 255, 255)
    }
    // 描边向内的光晕：使用硬件描边叠加，不引入软件阴影。
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // 光晕直线与圆角在切点处精确拼接；使用平头避免两段圆头叠加成亮斑。
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }
    // 底部淡化缓存：把截图区域复制后在下缘做 alpha 渐变（软件画布上的 DST_IN 最可靠）
    private var fadeBmp: Bitmap? = null
    private val fadeMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val dstRect = RectF()
    // GPU 模糊用的渲染节点：把截图画进节点，再给节点挂上 RenderEffect 模糊后绘制
    private var renderNode: RenderNode? = null
    private val roundedCardOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
        }
    }

    init {
        background = glass
        // 使用硬件环境阴影而非软件 BlurMaskFilter，避免破坏毛玻璃的 GPU RenderEffect。
        // 仅保留环境阴影，让上下左右轮廓的暗化均匀且非常轻；20% 黑色为设计上限。
        outlineProvider = roundedCardOutline
        elevation = density * EDGE_SHADOW_ELEVATION_DP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = Color.argb(EDGE_SHADOW_ALPHA, 0, 0, 0)
            outlineSpotShadowColor = Color.TRANSPARENT
        }
    }

    // 后台线程调用：shot 为包含本卡片区域的根视图截图（等比缩小），region 是卡片在其中的区域，
    // 这里裁出卡片区域做高斯模糊，结果写进后台缓冲；要等主线程 commitBlur() 才会显示
    fun blurInBackground(shot: Bitmap, region: Rect) {
        val w = region.width()
        val h = region.height()
        if (w <= 0 || h <= 0 || region.left < 0 || region.top < 0) return
        val crop = cropBmp
        val target = if (crop != null && crop.width == w && crop.height == h) {
            crop
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { cropBmp = it }
        }
        Canvas(target).drawBitmap(shot, region, Rect(0, 0, w, h), null)
        val blur = writeBlur
        val out = if (blur != null && blur.width == w && blur.height == h) {
            blur
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { writeBlur = it }
        }
        boxBlur(target, out, 11)
    }

    // 主线程调用：把后台刚模糊好的缓冲换到前台显示
    fun commitBlur() {
        val t = writeBlur
        writeBlur = frontBlur
        frontBlur = t
        glass.setSource(frontBlur, null)
    }

    // 主线程调用（API 31+ GPU 模糊）：直接把"整块截图 + 本卡片在截图里的区域"交给玻璃背景，
    // 绘制时用 RenderEffect 在 GPU 上模糊，不需要 CPU 预先模糊，因此响应最快
    fun setLiveSource(shot: Bitmap, region: Rect) {
        glass.setSource(shot, region)
    }

    // 顶部蒙版配置：自定义提亮颜色（背景同色）、圆角和描边
    fun setTint(top: Int, bottom: Int) {
        tintTop = top
        tintBottom = bottom
        invalidate()
    }

    // 白色底 + 原色：颜色用较低透明度也能保持本来的色相
    fun setTintWithWhiteBase(whiteAlpha: Float, top: Int, bottom: Int, independentOpacity: Boolean = false) {
        tintWhiteBase = whiteAlpha.coerceIn(0f, 1f)
        independentTintOpacity = independentOpacity
        tintTop = top
        tintBottom = bottom
        invalidate()
    }

    fun setCornerRadius(radius: Float) {
        if(cornerRadius==radius) return
        cornerRadius = radius
        invalidateOutline()
        invalidate()
    }

    fun setStrokeVisible(visible: Boolean) {
        showStroke = visible
        setEdgeShadowVisible(visible)
        invalidate()
    }

    /** 控制完整卡片轮廓的轻量环境阴影，不会影响描边自身的白色透明度。 */
    fun setEdgeShadowVisible(visible: Boolean) {
        edgeShadowVisible = visible
        elevation = if (visible) density * (if (frostedMaterial) 5f else EDGE_SHADOW_ELEVATION_DP) else 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = Color.argb(if (frostedMaterial) 70 else EDGE_SHADOW_ALPHA, 0, 0, 0)
            outlineSpotShadowColor = if (frostedMaterial) Color.argb(30, 0, 0, 0) else Color.TRANSPARENT
        }
        invalidateOutline()
    }

    fun setStrokeAlpha(alpha: Int) {
        strokeAlpha = alpha.coerceIn(0, 255)
        invalidate()
    }

    fun setInnerGlowVisible(visible: Boolean) {
        showInnerGlow = visible
        invalidate()
    }

    fun setEnhancedEdgeStyle(enabled: Boolean) {
        useEnhancedEdgeStyle = enabled
        invalidate()
    }

    fun setWeatherCardEdgeStyle(
        topStrokeScale: Float = 1f,
        sideStrokeAlpha: Int? = null,
        bottomInsetGlow: Boolean = false
    ) {
        topStrokeWidthScale = topStrokeScale.coerceIn(0.2f, 1.2f)
        sideStrokeAlphaOverride = sideStrokeAlpha?.coerceIn(0, 255)
        bottomInsetGlowVisible = bottomInsetGlow
        invalidate()
    }

    private companion object {
        // 共用静态细颗粒纹理，不逐帧生成随机噪点，避免滚动闪烁和位图分配。
        val FROST_TEXTURE: Shader by lazy {
            val random = java.util.Random(18018L)
            val pixels = IntArray(64 * 64) {
                val value = if (random.nextBoolean()) 255 else 0
                Color.argb(1 + random.nextInt(4), value, value, value)
            }
            val bitmap = Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
            android.graphics.BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        const val EDGE_SHADOW_ALPHA = 153 // 60% black environment glow
        const val EDGE_SHADOW_ELEVATION_DP = 2.2f
    }

    // 不同 Android 版本创建 RenderNode 的入口不同：29~36 用公开工厂 create(name, host)，
    // 37+ 改成了公开构造函数；用反射兼容，避免以后换到其他版本手机时崩溃
    private fun createRenderNode(): RenderNode {
        try {
            val m = RenderNode::class.java.getMethod(
                "create", String::class.java, View::class.java
            )
            return m.invoke(null, "glassCard", null) as RenderNode
        } catch (e: NoSuchMethodException) {
            val ctor = RenderNode::class.java.getConstructor(String::class.java)
            return ctor.newInstance("glassCard")
        }
    }

    // 两遍盒式模糊近似高斯模糊（纯 CPU，兼容所有系统，替代已废弃的 RenderScript）
    private fun boxBlur(src: Bitmap, dst: Bitmap, radius: Int) {
        val w = src.width
        val h = src.height
        val n = w * h
        var a = boxA
        var b = boxB
        var p = pixels
        if (a == null || a.size != n) {
            a = IntArray(n)
            b = IntArray(n)
            p = IntArray(n)
            boxA = a
            boxB = b
            pixels = p
        }
        val buf = a!!
        val buf2 = b!!
        val pix = p!!
        src.getPixels(pix, 0, w, 0, 0, w, h)
        // 逐通道模糊（R、G、B），alpha 保留原值
        for (shift in intArrayOf(16, 8, 0)) {
            for (i in 0 until n) buf[i] = (pix[i] shr shift) and 0xFF
            for (iter in 0 until 2) {
                boxH(buf, buf2, w, h, radius)
                boxV(buf2, buf, w, h, radius)
            }
            for (i in 0 until n) {
                pix[i] = (pix[i] and (0xFF shl shift).inv()) or (buf[i] shl shift)
            }
        }
        dst.setPixels(pix, 0, w, 0, 0, w, h)
    }

    // 水平滑动窗口均值
    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r + r + 1
        for (y in 0 until h) {
            val base = y * w
            var sum = 0
            for (i in -r..r) sum += src[base + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[base + x] = sum / div
                sum += src[base + (x + r + 1).coerceAtMost(w - 1)] - src[base + (x - r).coerceAtLeast(0)]
            }
        }
    }

    // 垂直滑动窗口均值
    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r + r + 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                val idx = y * w + x
                dst[idx] = sum / div
                sum += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    // 玻璃背景：模糊图 + 上亮下淡的玻璃渐变 + 细描边
    private inner class GlassBackground : Drawable() {
        private var source: Bitmap? = null
        private var region: Rect? = null
        private var sourceGeneration = 0L
        private var softwareGeneration = -1L
        private var softwareRadius = Float.NaN
        private var softwareCrop: Bitmap? = null
        private var softwareBlur: Bitmap? = null

        fun setSource(src: Bitmap?, srcRegion: Rect?) {
            if (source !== src || region !== srcRegion) {
                source = src
                region = srcRegion
                sourceGeneration++
                invalidateSelf()
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val left = b.left.toFloat()
            val top = b.top.toFloat()
            val right = b.right.toFloat()
            val bottom = b.bottom.toFloat()

            source?.let { src ->
                path.reset()
                path.addRoundRect(
                    left, top, right, bottom, cornerRadius, cornerRadius, Path.Direction.CW
                )
                val sc = canvas.save()
                canvas.clipPath(path)
                dstRect.set(left, top, right, bottom)
                // 底部下边缘渐变淡化：把截图区域复制到小画板，用软件画布的 DST_IN
                // 在底部做 alpha 渐变（硬件画布上 xfermode 与 RenderNode 组合不稳定），
                // 之后照常交给 RenderNode 模糊，保证淡化稳定生效
                var displaySrc: Bitmap = src
                var displayRegion: Rect? = region
                val reg = region ?: Rect(0, 0, src.width, src.height)
                val tw = reg.width()
                val th = reg.height()
                val hasFade = bottomFadePx > 0f || !fadeStartPx.isNaN()
                val postFade = useGpuBlur && canvas.isHardwareAccelerated && hasFade
                val viewFadeStart = if (fadeStartPx.isNaN()) {
                    b.height() - bottomFadePx
                } else {
                    fadeStartPx
                }.coerceIn(0f, b.height().toFloat())
                val viewFadeEnd = if (fadeStartPx.isNaN()) {
                    b.height().toFloat()
                } else {
                    (b.height() - fadeEndInsetPx).coerceIn(0f, b.height().toFloat())
                }
                // 软件路径先在位图上做淡化；GPU 路径必须在模糊结果之后再遮罩，
                // 否则高斯扩散会在已透明的下边界重新漏出一条浅色分界线。
                if (!postFade && hasFade && tw > 0 && th > 0 && b.height() > 0) {
                    val scale = th.toFloat() / b.height().toFloat()
                    val fadeStart = if (fadeStartPx.isNaN()) {
                        th - bottomFadePx * scale
                    } else {
                        fadeStartPx * scale
                    }.coerceIn(0f, th.toFloat())
                    val fadeEnd = if (fadeStartPx.isNaN()) {
                        th.toFloat()
                    } else {
                        (th - fadeEndInsetPx * scale).coerceIn(0f, th.toFloat())
                    }
                    if (fadeEnd - fadeStart > 1f) {
                        val tmp = fadeBmp?.takeIf { it.width == tw && it.height == th }
                            ?: Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                                .also { fadeBmp = it }
                        val ct = Canvas(tmp)
                        ct.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        ct.drawBitmap(src, reg, Rect(0, 0, tw, th), bitmapPaint)
                        val p = Paint().apply {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                            shader = LinearGradient(
                                0f, 0f, 0f, th.toFloat(),
                                intArrayOf(
                                    Color.WHITE,
                                    Color.WHITE,
                                    Color.TRANSPARENT,
                                    Color.TRANSPARENT
                                ),
                                floatArrayOf(0f, fadeStart / th, fadeEnd / th, 1f),
                                Shader.TileMode.CLAMP
                            )
                        }
                        // 必须覆盖整块位图：上半段保留 100% 模糊，终点以下完全清晰。
                        ct.drawRect(0f, 0f, tw.toFloat(), th.toFloat(), p)
                        displaySrc = tmp
                        displayRegion = null
                    }
                }
                if (useGpuBlur && canvas.isHardwareAccelerated) {
                    // 硬件模糊：把卡片区域的截图录进 RenderNode，节点挂 GPU 模糊效果后画出来，
                    // 整条链路都在 GPU 上，CPU 只做几毫秒的截图，滚动时模糊实时跟随
                    val w = b.width()
                    val h = b.height()
                    val node = renderNode
                        ?: createRenderNode().also { renderNode = it }
                    if (node.left != b.left || node.top != b.top ||
                        node.width != w || node.height != h
                    ) {
                        node.setPosition(b.left, b.top, b.right, b.bottom)
                    }
                    val rc = node.beginRecording()
                    nodeDstRect.set(0f,0f,w.toFloat(),h.toFloat())
                    rc.drawBitmap(
                        displaySrc, displayRegion,
                        nodeDstRect, bitmapPaint
                    )
                    node.endRecording()
                    node.setRenderEffect(
                        blurEffect ?: RenderEffect.createBlurEffect(
                            blurRadius, blurRadius, Shader.TileMode.CLAMP
                        ).also { blurEffect = it }
                    )
                    val fadeLayer = if (postFade) {
                        canvas.saveLayer(left, top, right, bottom, null)
                    } else {
                        -1
                    }
                    canvas.drawRenderNode(node)
                    if (fadeLayer >= 0) {
                        fadeMaskPaint.xfermode = dstIn
                        if(gpuFadeShader==null || gpuFadeBounds!=b || gpuFadeStart!=viewFadeStart || gpuFadeEnd!=viewFadeEnd) {
                            gpuFadeBounds.set(b);gpuFadeStart=viewFadeStart;gpuFadeEnd=viewFadeEnd
                            gpuFadeShader = LinearGradient(
                            0f, top, 0f, bottom,
                            intArrayOf(
                                Color.WHITE,
                                Color.WHITE,
                                Color.TRANSPARENT,
                                Color.TRANSPARENT
                            ),
                            floatArrayOf(
                                0f,
                                viewFadeStart / b.height().toFloat(),
                                viewFadeEnd / b.height().toFloat(),
                                1f
                            ),
                            Shader.TileMode.CLAMP
                        )
                        }
                        fadeMaskPaint.shader=gpuFadeShader
                        canvas.drawRect(left, top, right, bottom, fadeMaskPaint)
                        fadeMaskPaint.shader = null
                        fadeMaskPaint.xfermode = null
                        canvas.restoreToCount(fadeLayer)
                    }
                } else {
                    // OEM/shared-element snapshots use a software Canvas. RenderEffect is not
                    // available there: retain a real blurred frame instead of exposing the sharp source.
                    if (useGpuBlur && tw > 0 && th > 0) {
                        val scale = minOf(1f, 160f / maxOf(tw, th))
                        val sw = (tw * scale).toInt().coerceAtLeast(1)
                        val sh = (th * scale).toInt().coerceAtLeast(1)
                        var crop = softwareCrop
                        var blurred = softwareBlur
                        if (crop == null || crop.width != sw || crop.height != sh || blurred == null) {
                            crop = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                            blurred = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                            softwareCrop = crop; softwareBlur = blurred; softwareGeneration = -1
                        }
                        if (softwareGeneration != sourceGeneration || softwareRadius != blurRadius) {
                            Canvas(crop).apply {
                                drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                                drawBitmap(displaySrc, displayRegion, Rect(0, 0, sw, sh), bitmapPaint)
                            }
                            val radius = (blurRadius * sw / b.width().coerceAtLeast(1)).toInt().coerceIn(1, 48)
                            boxBlur(crop, blurred, radius)
                            softwareGeneration = sourceGeneration; softwareRadius = blurRadius
                        }
                        canvas.drawBitmap(blurred, null, dstRect, bitmapPaint)
                    } else canvas.drawBitmap(displaySrc, displayRegion, dstRect, bitmapPaint)
                }
                canvas.restoreToCount(sc)
            }

            // 白色底：让后续的半透明原色不至于被深色玻璃背景拉灰
            if (tintWhiteBase > 0f) {
                glassPaint.shader = null
                glassPaint.color = Color.argb((tintWhiteBase * 255f).toInt(), 255, 255, 255)
                canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, glassPaint)
            }

            // 玻璃提亮：上亮下淡的半透明白渐变（模拟玻璃反光）
            // Opt-in keeps legacy skies pixel-compatible. A tint must not inherit the
            // white underlay's paint alpha (e.g. 14/255 multiplying the intended tint again).
            val tintPaint = if (independentTintOpacity) independentTintPaint else glassPaint
            if(tintShader==null || tintBounds!=b || cachedTintTop!=tintTop || cachedTintBottom!=tintBottom) {
                tintBounds.set(b);cachedTintTop=tintTop;cachedTintBottom=tintBottom
                tintShader=LinearGradient(
                0f, top, 0f, bottom,
                tintTop,
                tintBottom,
                Shader.TileMode.CLAMP
            )
            }
            tintPaint.shader=tintShader
            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, tintPaint)
            tintPaint.shader = null

            if (frostedMaterial) {
                if (frostWidth != b.width() || frostHeight != b.height()) {
                    frostWidth = b.width(); frostHeight = b.height()
                    frostSheen = LinearGradient(left, top, right * .35f, bottom,
                        intArrayOf(Color.argb(13, 255, 255, 255), Color.argb(4, 255, 255, 255),
                            Color.argb(5, 0, 0, 0)), floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
                    frostEdge = WeatherFrostedEdge.shader(top, bottom)
                }
                frostPaint.style = Paint.Style.FILL
                frostPaint.shader = frostSheen
                canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, frostPaint)
                frostTexturePaint.shader = FROST_TEXTURE
                canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, frostTexturePaint)
                if (showStroke) {
                    frostPaint.style = Paint.Style.STROKE
                    frostPaint.strokeWidth = density * .75f
                    frostPaint.shader = frostEdge
                    val inset = frostPaint.strokeWidth / 2f
                    canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset,
                        (cornerRadius - inset).coerceAtLeast(0f), (cornerRadius - inset).coerceAtLeast(0f), frostPaint)
                }
                frostPaint.shader = null
            }

            if (showStroke && !frostedMaterial) {
                // 上下边缘保持亮白；圆角沿路径过渡到较淡的两侧白线，避免硬切换。
                // 侧边只保留淡描边，不参与向内泛光。
                val inset = density * 0.49f
                val l = left + inset
                val t = top + inset
                val rr = right - inset
                val bb = bottom - inset
                // 圆角半径钳制到有效范围：胶囊形控件（圆角 > 高/宽一半）不能超过半高/半宽，
                // 否则上下直边端点倒置、描边伸出控件外变形
                val rc = minOf(
                    cornerRadius - inset,
                    (bb - t) / 2f,
                    (rr - l) / 2f
                ).coerceAtLeast(0f)
                fun drawFadedArc(
                    paint: Paint,
                    oval: RectF,
                    startAngle: Float,
                    fromAlpha: Int,
                    toAlpha: Int,
                    fadeAtSideOnly: Boolean = false
                ) {
                    // 进一步加密弧线分段，圆角透明度过渡与内侧光晕都不会出现明显阶梯。
                    val segments = 24
                    val sweep = 90f / segments
                    for (index in 0 until segments) {
                        val progress = (index + 0.5f) / segments
                        // 底部圆角的白线先完整覆盖大部分弧线，只在贴到无色侧边的
                        // 最后 32% 渐隐；避免圆角整体都显得发灰。
                        val fadeProgress = if (!fadeAtSideOnly) progress else when {
                            fromAlpha == 0 && toAlpha > 0 -> (progress / 0.32f).coerceIn(0f, 1f)
                            fromAlpha > 0 && toAlpha == 0 -> ((progress - 0.68f) / 0.32f).coerceIn(0f, 1f)
                            else -> progress
                        }
                        val alpha = (fromAlpha + (toAlpha - fromAlpha) * fadeProgress)
                            .toInt()
                            .coerceIn(0, 255)
                        if (alpha > 0) {
                            paint.color = Color.argb(alpha, 255, 255, 255)
                            canvas.drawArc(
                                oval,
                                startAngle + sweep * index,
                                // 不让相邻弧段重叠，避免局部 alpha 叠加后出现亮度台阶。
                                sweep,
                                false,
                                paint
                            )
                        }
                    }
                }

                strokePaint.shader = null
                // 普通卡片可覆盖侧边亮度；天气主页传入 0，直边不画描边，
                // 仅让圆角从上下边线自然淡出。
                val sideAlpha = if (useEnhancedEdgeStyle) sideStrokeAlphaOverride ?: 13 else 0
                strokePaint.color = Color.argb(strokeAlpha, 255, 255, 255)
                val baseStrokeWidth = density * 0.98f
                strokePaint.strokeWidth = baseStrokeWidth * topStrokeWidthScale
                canvas.drawLine(l + rc, t, rr - rc, t, strokePaint)
                // 底边与顶部使用完全相同的白度和线宽；两侧直边仍单独控制。
                strokePaint.strokeWidth = baseStrokeWidth * topStrokeWidthScale
                canvas.drawLine(l + rc, bb, rr - rc, bb, strokePaint)
                if (rc > 0f) {
                    strokePaint.strokeWidth = baseStrokeWidth * topStrokeWidthScale
                    drawFadedArc(
                        strokePaint, RectF(rr - 2 * rc, t, rr, t + 2 * rc),
                        270f, strokeAlpha, sideAlpha
                    )
                    drawFadedArc(
                        strokePaint, RectF(l, t, l + 2 * rc, t + 2 * rc),
                        180f, sideAlpha, strokeAlpha
                    )
                    strokePaint.strokeWidth = baseStrokeWidth * topStrokeWidthScale
                    drawFadedArc(
                        strokePaint, RectF(rr - 2 * rc, bb - 2 * rc, rr, bb),
                        0f, sideAlpha, strokeAlpha, fadeAtSideOnly = true
                    )
                    drawFadedArc(
                        strokePaint, RectF(l, bb - 2 * rc, l + 2 * rc, bb),
                        90f, strokeAlpha, sideAlpha, fadeAtSideOnly = true
                    )
                }
                if (sideAlpha > 0) {
                    strokePaint.strokeWidth = baseStrokeWidth * topStrokeWidthScale
                    strokePaint.color = Color.argb(sideAlpha, 255, 255, 255)
                    canvas.drawLine(l, t + rc, l, bb - rc, strokePaint)
                    canvas.drawLine(rr, t + rc, rr, bb - rc, strokePaint)
                }

                // 约 18dp（原 12dp 的 150%）的内侧光晕：沿亮的底边与底部圆角
                // 法线向内扩散；侧边不绘制内侧光晕。
                val glowDepth = minOf(
                    density * 18f,
                    (bb - t) / 3f,
                    (rr - l) / 3f
                )
                if (useEnhancedEdgeStyle && (showInnerGlow || bottomInsetGlowVisible) && glowDepth > 0f) {
                    // 底部泛光与描边接触的第一像素使用完全相同的白色亮度，
                    // 之后才逐层衰减，避免出现“描边亮、泛光忽然变灰”的断层。
                    if (bottomInsetGlowVisible) {
                        val contactInset = (baseStrokeWidth * 0.55f).coerceAtMost(glowDepth * 0.2f)
                        val contactLeft = l + contactInset
                        val contactRight = rr - contactInset
                        val contactBottom = bb - contactInset
                        val contactRadius = (rc - contactInset).coerceAtLeast(0f)
                        edgeGlowPaint.shader = null
                        edgeGlowPaint.strokeWidth = baseStrokeWidth * topStrokeWidthScale
                        edgeGlowPaint.color = Color.argb(strokeAlpha, 255, 255, 255)
                        canvas.drawLine(
                            contactLeft + contactRadius,
                            contactBottom,
                            contactRight - contactRadius,
                            contactBottom,
                            edgeGlowPaint
                        )
                        if (contactRadius > 0f) {
                            // 底部圆角从无色侧边连续过渡至与底部描边同亮度的接触点。
                            drawFadedArc(
                                edgeGlowPaint,
                                RectF(
                                    contactRight - 2 * contactRadius,
                                    contactBottom - 2 * contactRadius,
                                    contactRight,
                                    contactBottom
                                ),
                                0f,
                                0,
                                strokeAlpha,
                                fadeAtSideOnly = true
                            )
                            drawFadedArc(
                                edgeGlowPaint,
                                RectF(
                                    contactLeft,
                                    contactBottom - 2 * contactRadius,
                                    contactLeft + 2 * contactRadius,
                                    contactBottom
                                ),
                                90f,
                                strokeAlpha,
                                0,
                                fadeAtSideOnly = true
                            )
                        }
                    }
                    // 以更细密的重叠层形成连续柔和的约 18dp 泛光。
                    val layers = 10
                    val step = glowDepth / layers
                    edgeGlowPaint.shader = null
                    edgeGlowPaint.strokeWidth = step * 1.9f
                    for (layer in 1..layers) {
                        val progress = layer.toFloat() / layers
                        val innerInset = step * (layer - 0.5f)
                        val innerLeft = l + innerInset
                        val innerTop = t + innerInset
                        val innerRight = rr - innerInset
                        val innerBottom = bb - innerInset
                        if (innerRight <= innerLeft || innerBottom <= innerTop) break
                        val fade = 1f - progress
                        // 边缘起始略亮、之后以平滑的非线性曲线衰减，避免层间亮度突变。
                        val smoothFade = fade * fade * (0.65f + 0.35f * fade)
                        val glowAlpha = (strokeAlpha * 0.38f * smoothFade)
                            .toInt()
                            .coerceAtLeast(0)
                        if (glowAlpha == 0) continue
                        val innerRadius = (rc - innerInset).coerceAtLeast(0f)
                        edgeGlowPaint.color = Color.argb(glowAlpha, 255, 255, 255)
                        if (showInnerGlow) {
                            canvas.drawLine(
                                innerLeft + innerRadius,
                                innerTop,
                                innerRight - innerRadius,
                                innerTop,
                                edgeGlowPaint
                            )
                        }
                        canvas.drawLine(
                            innerLeft + innerRadius,
                            innerBottom,
                            innerRight - innerRadius,
                            innerBottom,
                            edgeGlowPaint
                        )
                        if (innerRadius > 0f) {
                            if (showInnerGlow) {
                                drawFadedArc(
                                    edgeGlowPaint,
                                    RectF(
                                        innerRight - 2 * innerRadius,
                                        innerTop,
                                        innerRight,
                                        innerTop + 2 * innerRadius
                                    ),
                                    270f,
                                    glowAlpha,
                                    0
                                )
                                drawFadedArc(
                                    edgeGlowPaint,
                                    RectF(
                                        innerLeft,
                                        innerTop,
                                        innerLeft + 2 * innerRadius,
                                        innerTop + 2 * innerRadius
                                    ),
                                    180f,
                                    0,
                                    glowAlpha
                                )
                            }
                            drawFadedArc(
                                edgeGlowPaint,
                                RectF(
                                    innerRight - 2 * innerRadius,
                                    innerBottom - 2 * innerRadius,
                                    innerRight,
                                    innerBottom
                                ),
                                0f,
                                0,
                                glowAlpha,
                                fadeAtSideOnly = true
                            )
                            drawFadedArc(
                                edgeGlowPaint,
                                RectF(
                                    innerLeft,
                                    innerBottom - 2 * innerRadius,
                                    innerLeft + 2 * innerRadius,
                                    innerBottom
                                ),
                                90f,
                                glowAlpha,
                                0,
                                fadeAtSideOnly = true
                            )
                        }
                    }
                }
            }
        }

        override fun setAlpha(alpha: Int) {
            glassPaint.alpha = alpha
            independentTintPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            glassPaint.colorFilter = colorFilter
            independentTintPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
