package com.example.weatherapp

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import kotlin.math.*

/** Touch-operated miniature of the real UI; all data is intentionally synthetic. */
class GuideInteractionView(context: Context) : FrameLayout(context) {
    var onStateChanged: ((Boolean) -> Unit)? = null
    private var step = 0
    private var stage = 0
    private var done = false
    private var selected = 1
    private var downX = 0f
    private var downY = 0f
    private var pulse = 0f
    private var animator: ValueAnimator? = null
    private data class Scene(val step: Int,val stage: Int,val done: Boolean,val selected: Int)
    private var oldScene: Scene? = null
    private var blend = 1f
    private var slideDirection = 1f
    private var transitionAnimator: ValueAnimator? = null
    private var demoAnimator: ValueAnimator? = null
    private var pull = 0f
    private var refreshBlend = 0f
    private var chartDownX = 0f
    private var drawingWeather = true
    private val ease = DecelerateInterpolator(1.4f) // Shared timing character with DetailContentPager.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hit = RectF()
    private val primary get() = if(drawingWeather) Color.WHITE else ContextCompat.getColor(context, R.color.text_primary)
    private val secondary get() = if(drawingWeather) Color.argb(222,255,255,255) else ContextCompat.getColor(context, R.color.text_secondary)
    private val accent get() = if(drawingWeather) Color.WHITE else ContextCompat.getColor(context, R.color.accent)
    private val surface get() = ContextCompat.getColor(context, R.color.card_bg_start)
    private val logicalContext=context.createConfigurationContext(Configuration(resources.configuration).apply { densityDpi=160 })
    private val materials=HashMap<Long,BlurCardView>()
    private var skySample: Bitmap?=null
    private val background=WeatherBackgroundView(context).apply {
        setWeather("CLEAR_NIGHT",true);setBottomLandscapeVisible(true,false)
        outlineProvider=object: ViewOutlineProvider() {
            override fun getOutline(v: View,o: Outline) { o.setRoundRect(0,0,v.width,v.height,v.width/340f*22f) }
        };clipToOutline=true
    }
    private val chart=HourlyMetricChartView(context).apply { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val spinner=ProgressBar(context).apply {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        indeterminateTintList=android.content.res.ColorStateList.valueOf(Color.WHITE);visibility=GONE
    }
    // Two-hour samples keep the miniature's native axis readable while still spanning 48 hours.
    private val temperatures=DoubleArray(25) { 24+sin(it*.38)*3 }
    private val times=LongArray(25) { System.currentTimeMillis()/3_600_000L*3_600_000L+it*2*3_600_000L }
    init {
        isClickable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(background);addView(chart);addView(spinner)
        chart.setDarkTheme(WeatherSettings.isDarkTheme(context));chart.setSeries(MetricKind.FEELS_LIKE,times,temperatures)
        chart.onSelectionChanged={ index,_,_,_ -> selected=index;invalidate() }
        chart.setOnTouchListener { _,e ->
            if(e.actionMasked==MotionEvent.ACTION_DOWN) { chartDownX=e.x;demoAnimator?.cancel() }
            if(e.actionMasked==MotionEvent.ACTION_MOVE && step==1 && stage>=1 && !done && abs(e.x-chartDownX)>24*scale()) {
                done=true;stage=2;updateAnimation();onStateChanged?.invoke(true);invalidate()
            }
            false // Native chart retains the entire gesture, even after the practice is completed.
        }
        syncChildren()
    }
    private fun scene()=Scene(step,stage,done,selected)
    private fun scale()=min(width/340f,height/350f).coerceAtLeast(.01f)
    private fun isChart(s: Scene)=s.step==1 && s.stage>=1
    private fun isHome(s: Scene)=!(s.step==0 && s.stage==1 || isChart(s) || s.step==3 && s.stage>=1 || s.step==4)

    fun setStep(value: Int, completed: Boolean) {
        val old=scene();demoAnimator?.cancel();pull=0f;refreshBlend=0f
        step = value; done = completed; stage = if (completed) 2 else 0
        selected = 1
        slideDirection=if(value<old.step) -1f else 1f
        chart.setDarkTheme(WeatherSettings.isDarkTheme(context))
        if(value==1) chart.setSeries(MetricKind.FEELS_LIKE,times,temperatures)
        animateScene(old);updateAnimation();invalidate()
    }
    fun actionLabel(): String = when (step) {
        0 -> if (stage == 0) "演示点击地址栏" else "选择演示城市"
        1 -> if (stage == 0) "演示打开 48 小时卡片" else "演示拖动图表"
        2 -> "演示下拉刷新"
        3 -> if (stage == 0) "演示打开设置" else "演示打开天气源"
        else -> "演示保存并检测 API"
    }
    fun instruction(): String = when (step) {
        0 -> if (stage == 0) "轻点发光的地址栏 ↑" else "点击城市列表中的上海"
        1 -> if (stage == 0) "点击 48 小时天气卡片" else "按住真实图表左右拖动，查看时间与数值"
        2 -> "在演示天气页上按住向下拖动 ↓"
        3 -> if (stage == 0) "点击右上角设置图标" else "点击高亮的天气源一行"
        else -> "这里仅演示操作，点击保存即可"
    }
    /** Accessible equivalent of the illustrated gesture, not a skip/completion shortcut. */
    fun performDemonstration() {
        if (done) return
        val old=scene()
        if ((step == 0 || step == 1 || step == 3) && stage == 0) stage = 1
        else { stage = 2; done = true; selected = 4 }
        slideDirection=if(step==0 && done) -1f else 1f
        animateScene(old);updateAnimation(); invalidate(); onStateChanged?.invoke(done)
        if(step==2 && done) animateRefresh()
        if(step==1 && done) animateChart()
    }
    private fun animateScene(old: Scene) {
        transitionAnimator?.cancel()
        if(!isAttachedToWindow || old==scene()) { oldScene=null;blend=1f;syncChildren();return }
        oldScene=old;blend=0f;syncChildren()
        transitionAnimator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=420;interpolator=ease
            addUpdateListener { blend=it.animatedValue as Float;syncChildren();invalidate() };start()
        }
    }
    private fun syncChildren() {
        val target=isChart(scene());val exiting=blend<1f && oldScene?.let(::isChart)==true
        chart.visibility=if(target||exiting) VISIBLE else GONE
        val retained=target && exiting
        chart.alpha=if(retained) 1f else if(target) blend else if(exiting) 1-blend else 0f
        chart.translationX=(if(retained) 0f else if(target) (1-blend)*22 else if(exiting) -blend*22 else 0f)*scale()*slideDirection
        spinner.visibility=if(step==2 && pull>2) VISIBLE else GONE;spinner.alpha=(pull/28).coerceIn(0f,1f)
    }
    private fun animateChart() {
        demoAnimator?.cancel()
        demoAnimator=ValueAnimator.ofFloat(.16f,.72f).apply {
            duration=1050;interpolator=ease
            addUpdateListener {
                val e=MotionEvent.obtain(0,android.os.SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,chart.width*(it.animatedValue as Float),chart.height*.5f,0)
                chart.onTouchEvent(e);e.recycle()
            };start()
        }
    }
    private fun animateRefresh() {
        demoAnimator?.cancel();val initial=pull
        demoAnimator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=1050;interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener {
                val frame=GuideRefreshMotion.frame(it.animatedFraction,initial)
                pull=frame.pull;refreshBlend=frame.refreshBlend;syncChildren();invalidate()
            };start()
        }
    }
    private fun settlePull() {
        demoAnimator?.cancel()
        demoAnimator=ValueAnimator.ofFloat(pull,0f).apply {
            duration=280;interpolator=ease
            addUpdateListener { pull=it.animatedValue as Float;syncChildren();invalidate() };start()
        }
    }
    private fun updateAnimation() {
        animator?.cancel(); animator = null
        if (isAttachedToWindow && !done) animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2100; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() {
        animator?.cancel();transitionAnimator?.cancel();demoAnimator?.cancel()
        animator=null;transitionAnimator=null;demoAnimator=null
        materials.clear();skySample?.recycle();skySample=null
        super.onDetachedFromWindow()
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        val s=scale();val ox=(width-340*s)/2;val oy=(height-350*s)/2
        background.layout(ox.toInt(),oy.toInt(),(ox+340*s).toInt(),(oy+350*s).toInt())
        chart.layout((ox+26*s).toInt(),(oy+128*s).toInt(),(ox+314*s).toInt(),(oy+306*s).toInt())
        spinner.layout((ox+153*s).toInt(),(oy+58*s).toInt(),(ox+187*s).toInt(),(oy+92*s).toInt())
        if(changed || skySample==null) {
            val bitmap=Bitmap.createBitmap(340,350,Bitmap.Config.ARGB_8888)
            val c=Canvas(bitmap);c.scale(340f/background.width.coerceAtLeast(1),350f/background.height.coerceAtLeast(1));background.draw(c)
            materials.clear();skySample?.recycle();skySample=bitmap
        }
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = primary, bold: Boolean = false) {
        paint.color = color; paint.style = Paint.Style.FILL; paint.textSize = size
        paint.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText(value, x, y, paint)
    }
    private fun box(c: Canvas, r: RectF, color: Int, radius: Float = 14f) {
        if(drawingWeather && skySample!=null) {
            val key=(r.width().toInt().toLong() shl 32) or r.height().toInt().toLong()
            val card=materials.getOrPut(key) { BlurCardView(logicalContext).apply {
                setFrostedMaterial(true);setBlurRadius(WeatherCardStyle.FROST_BLUR_DP)
                val tint=WeatherCardStyle.frostedTint("CLEAR_NIGHT",true)
                setTintWithWhiteBase(WeatherCardStyle.FROST_WHITE_BASE,tint,tint)
            } }
            card.setCornerRadius(radius);card.setLiveSource(skySample!!,Rect(r.left.toInt(),r.top.toInt(),r.right.toInt(),r.bottom.toInt()))
            c.save();c.translate(r.left,r.top);card.background.setBounds(0,0,r.width().toInt(),r.height().toInt());card.background.draw(c);c.restore();return
        }
        paint.color = color; paint.style = Paint.Style.FILL; c.drawRoundRect(r, radius, radius, paint)
        WeatherFrostedBorderDrawable(1f,radius,WeatherSettings.isDarkTheme(context)).apply {
            setBounds(r.left.toInt(),r.top.toInt(),r.right.toInt(),r.bottom.toInt());draw(c)
        }
    }
    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, stroke: Float = 2f) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
        c.drawLine(x1, y1, x2, y2, paint); paint.style = Paint.Style.FILL
    }
    override fun dispatchDraw(canvas: Canvas) {
        drawChild(canvas,background,drawingTime)
        if(blend<1f && oldScene!=null) {
            val save=canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),((1-blend)*255).toInt())
            canvas.translate(-blend*22*scale()*slideDirection,0f);drawMiniature(canvas,oldScene!!);canvas.restoreToCount(save)
        }
        val save=canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(blend*255).toInt())
        canvas.translate((1-blend)*22*scale()*slideDirection,0f);drawMiniature(canvas,scene());canvas.restoreToCount(save)
        if(chart.visibility==VISIBLE) drawChild(canvas,chart,drawingTime)
        if(spinner.visibility==VISIBLE) drawChild(canvas,spinner,drawingTime)
    }
    private fun drawMiniature(canvas: Canvas,s: Scene) {
        val step=s.step;val stage=s.stage;val done=s.done;val selected=s.selected
        drawingWeather=isHome(s)
        // Fixed design coordinates scale uniformly; no screenshot or live credential sampling.
        val scale = min(width / 340f, height / 350f)
        if (scale <= 0) return
        val ox = (width - 340 * scale) / 2
        val oy = (height - 350 * scale) / 2
        canvas.save(); canvas.translate(ox, oy); canvas.scale(scale, scale)
        val c = canvas
        val clip=Path().apply { addRoundRect(RectF(2f,2f,338f,348f),22f,22f,Path.Direction.CW) };c.clipPath(clip)
        if(!drawingWeather) box(c, RectF(2f, 2f, 338f, 348f),ContextCompat.getColor(context,R.color.page_bg),22f)
        text(c, "交互演示 · 虚拟天气，不使用 API", 18f, 28f, 10.5f, secondary)
        if(step==2) c.translate(0f,pull)
        when {
            step == 0 && stage == 1 -> {
                text(c, "‹   城市管理", 22f, 65f, 19f, bold = true)
                box(c, RectF(20f, 83f, 320f, 119f), alpha(accent, 20))
                text(c, "搜索城市 / 区县", 36f, 107f, 14f, secondary)
                box(c,RectF(20f,132f,320f,174f),surface)
                text(c, "北京 · 当前城市          24°", 32f, 158f, 15f)
                box(c, RectF(20f, 180f, 320f, 236f), alpha(accent, 27))
                text(c, "上海              25°     ＋", 32f, 215f, 17f, accent, true)
                text(c, if (done) "✓ 已切换到上海" else "点击后返回天气首页", 32f, 282f, 14f, secondary)
                hit.set(20f, 180f, 320f, 236f)
            }
            step == 1 && stage >= 1 -> {
                text(c, "上海 · 48 小时天气详情", 20f, 64f, 17f, bold = true)
                val index=selected.coerceIn(temperatures.indices)
                val label=java.text.SimpleDateFormat("HH时",java.util.Locale.CHINA).format(java.util.Date(times[index]))
                text(c, "$label · ${temperatures[index].roundToInt()}° · 左右拖动查看", 20f, 101f, 14f, secondary)
                box(c,RectF(18f,120f,322f,314f),surface)
                text(c,"复用天气页图表 · 数据仅供练习",66f,337f,11f,secondary)
                hit.set(18f, 115f, 322f, 312f)
            }
            step == 3 && stage >= 1 -> {
                text(c, "‹   设置", 22f, 65f, 19f, bold = true)
                box(c, RectF(18f, 91f, 322f, 146f), alpha(accent, 27))
                text(c, "天气源                未配置  ›", 28f, 125f, 16f, accent, true)
                text(c, "通知与提醒                      ›", 28f, 189f, 16f)
                text(c, "主题与单位                      ›", 28f, 246f, 16f)
                if(done) {
                    box(c, RectF(18f, 265f, 322f, 332f), surface)
                    text(c, "官方平台 → 申请凭证 → 填写保存", 26f, 290f, 14f, accent, true)
                    text(c, "下方链接可查看申请方法", 26f, 316f, 13f, secondary)
                }
                hit.set(18f, 91f, 322f, 146f)
            }
            step == 4 -> {
                text(c, "‹   天气 API 配置", 20f, 66f, 19f, bold = true)
                text(c, "① 选择天气源    彩云 / 和风", 24f, 108f, 15f)
                text(c, "② 填入你自己的凭证", 24f, 151f, 15f)
                box(c, RectF(22f, 167f, 318f, 208f),ContextCompat.getColor(context,R.color.input_bg),20f)
                text(c, "••••••••  仅为示例，不是真实密钥", 32f, 193f, 13f, secondary)
                box(c, RectF(22f, 237f, 318f, 287f), accent)
                text(c, if(done) "✓ 检测示例" else "③ 保存并使用", 109f, 268f, 17f, Color.WHITE, true)
                text(c, "保存后显示字段、支持度与时间范围", 27f, 322f, 13f, secondary)
                if(done) {
                    box(c,RectF(18f,88f,322f,329f),surface,18f)
                    text(c,"接口支持情况 · 示例",38f,118f,17f,bold=true)
                    text(c,"功能         支持度        返回范围",35f,154f,12f,secondary)
                    text(c,"实况天气      支持             实时",35f,192f,13f)
                    text(c,"小时预报    部分支持        24/48小时",35f,230f,12f)
                    text(c,"分钟降水    暂不支持        本次缺失",35f,268f,12f)
                    text(c,"网络失败不等于永久不支持",35f,307f,12f,secondary)
                }
                hit.set(22f, 237f, 318f, 287f)
            }
            else -> {
                box(c, RectF(18f, 44f, 271f, 87f), if(step == 0) alpha(accent, 30) else alpha(accent, 12))
                text(c, "⌖  ${if(step==0 && done) "上海" else "北京"} · 点击管理城市   ›", 28f, 72f, 14f, accent, true)
                box(c, RectF(283f, 44f, 323f, 87f), alpha(accent, if(step == 3) 40 else 12))
                text(c, "⚙", 292f, 73f, 25f, accent)
                if(step==2 && refreshBlend>0) {
                    text(c,"24°",28f,152f,49f,alpha(primary,((1-refreshBlend)*255).toInt()),true)
                    text(c,"25°",28f,152f,49f,alpha(primary,(refreshBlend*255).toInt()),true)
                } else text(c, "24°", 28f, 152f, 49f, bold = true)
                text(c, "晴夜  ·  舒适微风  ·  虚拟数据", 30f, 183f, 13f, secondary)
                box(c, RectF(18f, 207f, 322f, 275f), surface)
                text(c, "48 小时天气                      ›", 32f, 236f, 16f, accent, true)
                text(c, "现在 24°      明日 25°      降水 0%", 32f, 260f, 12f, secondary)
                listOf("空气 35 · 优","体感 25°","湿度 52%").forEachIndexed { i,label ->
                    box(c,RectF(18f+i*104,284f,114f+i*104,321f),surface,12f)
                    text(c,label,25f+i*104,308f,11f,secondary)
                }
                if (step == 2) {
                    text(c, if(done && pull<2) "✓ 刷新完成，虚拟天气已更新" else if(done) "正在更新虚拟天气…" else "↓ 按住页面向下拉动", 36f, 340f, 12f, accent, true)
                    hit.set(18f, 95f, 322f, 320f)
                } else if (step == 0) hit.set(18f, 44f, 271f, 87f)
                else if (step == 3) hit.set(283f, 44f, 323f, 87f)
                else hit.set(18f, 207f, 322f, 275f)
            }
        }
        if (!done) {
            paint.color = alpha(accent, (100 + 100 * (1 - pulse)).toInt()); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            c.drawRoundRect(hit, 14f, 14f, paint); paint.style = Paint.Style.FILL
            val horizontal = step == 1 && stage == 1
            val x = if(horizontal) 85 + pulse * 130 else hit.centerX()
            val y = if(step == 2) 160 + pulse * 80 else hit.centerY() + 10
            paint.color = alpha(accent, 45); c.drawCircle(x,y,17f,paint)
            paint.color = accent; c.drawCircle(x,y,7f,paint)
        }
        canvas.restore()
    }
    private fun alpha(c: Int, a: Int) = (c and 0x00ffffff) or (a.coerceIn(0,255) shl 24)
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        // An outgoing chart is still drawn during the crossfade, but must not steal
        // the first gesture on the newly selected city/refresh/configuration scene.
        return !isChart(scene()) || super.onInterceptTouchEvent(e)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(done) return true
        val scale = min(width / 340f, height / 350f)
        if (scale <= 0) return false
        val x = (e.x - (width - 340 * scale) / 2) / scale
        val y = (e.y - (height - 350 * scale) / 2) / scale
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=x; downY=y;if(step==2) demoAnimator?.cancel();parent.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> {
                if(step == 1 && stage == 1 && hit.contains(downX,downY)) {
                    selected = ((x-30) / 46).roundToInt().coerceIn(0,6); invalidate()
                    if(abs(x-downX) >= 24) performDemonstration()
                } else if(step == 2 && hit.contains(downX,downY)) {
                    pull=((y-downY)*.58f).coerceIn(0f,62f);syncChildren();invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if(step==2 && hit.contains(downX,downY) && y-downY>=40) performDemonstration()
                else if(step==2) settlePull()
                if(abs(x-downX) < 16 && abs(y-downY) < 16 && hit.contains(x,y) && !(step == 2 || step == 1 && stage == 1)) {
                    performClick(); performDemonstration()
                }
                parent.requestDisallowInterceptTouchEvent(false); return true
            }
            MotionEvent.ACTION_CANCEL -> { if(step==2) settlePull();parent.requestDisallowInterceptTouchEvent(false);return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
