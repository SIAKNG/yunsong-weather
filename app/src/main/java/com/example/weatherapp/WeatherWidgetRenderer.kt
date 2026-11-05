package com.example.weatherapp

import android.content.Context
import android.graphics.*
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** Bounded launcher bitmap; palette, icons and rim are shared with the application. */
internal object WeatherWidgetRenderer {
    fun description(c: Context,city: CityPage?,d: WidgetWeather?): String = if(d==null)
        "${city?.let{WeatherAddressFormatter.city(it.name)} ?: "云松天气"}，暂无天气，请打开 App 添加城市或刷新" else
        "${WeatherAddressFormatter.city(d.city.name)}，${WeatherSettings.tempText(c,d.temperature)}，${WeatherText.skyconToText(d.sky)}，${stamp(d)}，${WeatherWidgetData.sourceName(d.source)}"
    fun forecastHourLabel(t: Long)=SimpleDateFormat("HH':00'",Locale.CHINA).format(Date(t))
    private fun clock(t: Long)=SimpleDateFormat("HH:mm",Locale.CHINA).format(Date(t))
    private fun stamp(d: WidgetWeather)=if(WeatherWidgetPolicy.stale(d.observed,d.saved,System.currentTimeMillis()))
        "${SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(d.observed))}缓存" else "${clock(d.observed)}更新"
    fun updateText(context: Context,data: WidgetWeather?): String {
        val d=data ?: return "暂无更新"
        val status=WeatherWidgetStore.status(context)
        return if(status=="等待联网刷新") "${clock(d.observed)}待刷新"
            else if(status.startsWith("刷新未成功") || WeatherWidgetPolicy.stale(d.observed,d.saved,System.currentTimeMillis())) "${clock(d.observed)}缓存" else "${clock(d.observed)}更新"
    }
    fun timestampSize(kind: String)=if(kind=="compact") 9f else 10f
    fun contentInset(width: Int,height: Int,kind: String="")=if(kind=="large" || kind=="daily_summary") 18f else if(height<74) 10f else 14f
    private val visibleBounds=java.util.WeakHashMap<Bitmap,RectF>()
    private fun bitmapInk(bitmap: Bitmap): RectF = synchronized(visibleBounds) {
        visibleBounds[bitmap] ?: run {
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            var l=bitmap.width;var t=bitmap.height;var r=-1;var b=-1
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) if((pixels[y*bitmap.width+x] ushr 24)>8){l=min(l,x);t=min(t,y);r=max(r,x);b=max(b,y)}
            RectF(if(r>=l) l.toFloat()/bitmap.width else 0f,if(b>=t) t.toFloat()/bitmap.height else 0f,
                if(r>=l) (r+1f)/bitmap.width else 1f,if(b>=t) (b+1f)/bitmap.height else 1f).also{visibleBounds[bitmap]=it}
        }
    }
    private fun iconInk(context: Context,sky: String?,size: Float): RectF =
        WeatherIconCache.load(context,sky,size*context.resources.displayMetrics.density)?.let{bitmapInk(it)}?.let{RectF(it.left*size,it.top*size,it.right*size,it.bottom*size)} ?: RectF(0f,0f,size,size)
    private const val DAILY_TEXT_SIZE=12f
    private const val LARGE_DAY_COUNT=6
    private const val LARGE_DAY_TEXT_SIZE=13f
    private const val WIDGET_CORNER_RADIUS=20f
    fun cornerRadiusDp()=WIDGET_CORNER_RADIUS
    private const val LARGE_MAIN=56f
    private const val SQUARE_MAIN=43f
    private const val WIDE_MAIN=LARGE_MAIN*.8f
    private const val COMPACT_MAIN=28f
    private const val WIDE_TEXT_SCALE=WIDE_MAIN/46f
    private const val COMPACT_TEXT_SCALE=COMPACT_MAIN/29f
    internal data class CompactWeather(val iconSize: Float,val centerX: Float,val labelSize: Float,
        val labelBaseline: Float,val left: Float,val iconTop: Float)
    private fun compactWeather(context: Context,w: Float,h: Float,sky: String?): CompactWeather {
        val pad=contentInset(w.toInt(),h.toInt())
        val tight=h<74f
        val labelSize=if(tight) 10f else 11.5f
        val label=WeatherText.skyconToText(sky)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=labelSize;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
        val sharedBottom=h-pad
        val bounds=Rect();paint.getTextBounds(label,0,label.length,bounds)
        val iconTop=pad
        val iconSize=(sharedBottom-bounds.height()-4f-iconTop).coerceIn(18f,32f)
        val columnWidth=max(iconInk(context,sky,iconSize).width(),bounds.width().toFloat()).coerceAtMost(w*.44f)
        val center=w-pad-columnWidth*.5f
        return CompactWeather(iconSize,center,labelSize,sharedBottom-bounds.bottom,w-pad-columnWidth,iconTop-iconInk(context,sky,iconSize).top)
    }
    internal data class Header(val name: String,val size: Float,val x: Float,val baseline: Float,
        val stampX: Float,val stampTop: Float,val stampWidth: Float,val cityWidth: Float)
    fun header(context: Context,kind: String,width: Int,height: Int,city: CityPage?,data: WidgetWeather?): Header {
        val w=width.coerceIn(120,600).toFloat()
        val h=height.coerceIn(64,700).toFloat()
        val pad=contentInset(width,height,kind)
        val compact=kind=="compact" || h<110
        val square=kind=="square" || w<235
        val large=kind=="large" && h>=320
        val size=if(compact) {if(h<74) 10f else 11f} else if(square) 15f else if(large) 18f else if(h<154) 13f else 15f
        val baseline=if(compact) {if(h<74) 18f else 24f} else if(square) 28f else if(large) 34f else if(h<154) 22f else 28f
        val x=pad
        val paint=android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {typeface=Typeface.create("sans-serif",Typeface.BOLD);textSize=size}
        val raw=(city?.let{WeatherAddressFormatter.city(it.name)} ?: "云松天气").take(18)
        val smallPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {typeface=Typeface.create("sans-serif",Typeface.NORMAL)
            textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,timestampSize(kind),context.resources.displayMetrics)/context.resources.displayMetrics.density}
        val right=if(compact) compactWeather(context,w,h,data?.sky).left-4f else if(square) w-pad-44f else if(large) w-pad else {
            paint.textSize=(if(large) 18f else if(h<154) 14f else 15f)*1.2f
            w-pad-paint.measureText(WeatherText.skyconToText(data?.sky)).coerceAtMost(w*.35f)-7f-(if(large) 44f else if(h<154) 32f else 34f)*1.2f-5f
        }
        val stacked=compact || square
        val stampWidth=smallPaint.measureText(updateText(context,data))+6f
        val available=(right-x-if(stacked) 0f else 5f+stampWidth).coerceAtLeast(18f)
        val fitSize=min(size,(size*available/paint.apply{textSize=size}.measureText(raw).coerceAtLeast(1f))).coerceAtLeast(10f)
        paint.textSize=fitSize
        val name=TextUtils.ellipsize(raw,paint,available,TextUtils.TruncateAt.END).toString()
        val nameBounds=Rect();paint.getTextBounds(name,0,name.length,nameBounds)
        val nameBaseline=pad-nameBounds.top
        val stampBounds=Rect();val update=updateText(context,data)
        smallPaint.getTextBounds(update,0,update.length,stampBounds)
        val density=context.resources.displayMetrics.density
        val stampPixelPaint=Paint(smallPaint).apply{textSize*=density}
        val stampPixels=Rect();stampPixelPaint.getTextBounds(update,0,update.length,stampPixels)
        val stampX=if(large) w-pad-stampPixels.right/density else if(stacked) x else x+paint.measureText(name)+5f
        val stampTop=if(large) nameBaseline+nameBounds.bottom-stampPixels.bottom/density+stampPixelPaint.fontMetrics.ascent/density
            else if(compact) nameBaseline+2f else if(stacked) nameBaseline+3f else nameBaseline+smallPaint.fontMetrics.ascent
        return Header(name,fitSize,x,nameBaseline,stampX,stampTop,if(large) stampWidth else (right-stampX).coerceAtLeast(1f),available)
    }
    fun renderPlaceholder(context: Context,widthDp: Int,heightDp: Int): Bitmap =
        renderInternal(context,"",widthDp,heightDp,null,null,false,true)
    fun render(context: Context,kind: String,widthDp: Int,heightDp: Int,city: CityPage?,data: WidgetWeather?,cities: List<Pair<CityPage,WidgetWeather?>> = emptyList()): Bitmap =
        if(WeatherInfoWidgetRenderer.supports(kind)) WeatherInfoWidgetRenderer.render(context,kind,widthDp,heightDp,city,data,cities) else renderInternal(context,kind,widthDp,heightDp,city,data,true)
    fun renderWidget(context: Context,kind: String,widthDp: Int,heightDp: Int,city: CityPage?,data: WidgetWeather?,cities: List<Pair<CityPage,WidgetWeather?>> = emptyList(),audit: MutableList<WeatherInfoWidgetRenderer.Element>?=null): Bitmap =
        if(WeatherInfoWidgetRenderer.supports(kind)) WeatherInfoWidgetRenderer.render(context,kind,widthDp,heightDp,city,data,cities,audit) else renderInternal(context,kind,widthDp,heightDp,city,data,false,audit=audit)
    private fun renderInternal(context: Context,kind: String,widthDp: Int,heightDp: Int,city: CityPage?,data: WidgetWeather?,showFooter: Boolean,placeholder: Boolean=false,audit: MutableList<WeatherInfoWidgetRenderer.Element>?=null): Bitmap {
        val w=widthDp.coerceIn(120,600).toFloat();val h=heightDp.coerceIn(64,700).toFloat()
        val pad=contentInset(widthDp,heightDp,kind)
        val scale=min(context.resources.displayMetrics.density,min(900f/w,1050f/h))
        val bitmap=Bitmap.createBitmap((w*scale).roundToInt(),(h*scale).roundToInt(),Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap);c.scale(scale,scale)
        val night=data?.night ?: (data?.sky?.contains("NIGHT")==true || java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) !in 6..17)
        val colors=WeatherBackgroundPalette.colors(data?.sky,night).map {original ->
            var value=original
            while(androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE,value)<5.0)
                value=androidx.core.graphics.ColorUtils.blendARGB(value,Color.rgb(22,38,66),.12f)
            value
        }.toIntArray()
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        WeatherWidgetSurface.draw(c,w,h,listOf(colors))
        if(placeholder) return bitmap
        fun font(size: Float,bold: Boolean=false) {p.textSize=size;p.typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)}
        fun text(value: String,x: Float,y: Float,size: Float=13f,bold: Boolean=false,alpha: Int=255,maxWidth: Float=w-x-pad,align: Paint.Align=Paint.Align.LEFT) {
            p.shader=null;p.color=Color.WHITE;p.alpha=alpha;font(size,bold);p.textAlign=align
            var fittedSize=size
            var s=TextUtils.ellipsize(value,android.text.TextPaint(p),maxWidth.coerceAtLeast(1f),TextUtils.TruncateAt.END).toString()
            while(s!=value && fittedSize>8f) {
                fittedSize=(fittedSize-.25f).coerceAtLeast(8f);font(fittedSize,bold)
                s=TextUtils.ellipsize(value,android.text.TextPaint(p),maxWidth.coerceAtLeast(1f),TextUtils.TruncateAt.END).toString()
            }
            val ink=Rect();p.getTextBounds(s,0,s.length,ink)
            val alignOffset=when(align){Paint.Align.CENTER -> -p.measureText(s)*.5f;Paint.Align.RIGHT -> -p.measureText(s);else -> 0f}
            var tx=x;var ty=y
            if(align==Paint.Align.LEFT && abs(x-pad)<.1f) tx=pad-ink.left
            if(align==Paint.Align.RIGHT && abs(x-(w-pad))<.1f) tx=w-pad-alignOffset-ink.right
            tx+=max(0f,pad-(tx+alignOffset+ink.left));tx-=max(0f,tx+alignOffset+ink.right-(w-pad))
            ty+=max(0f,pad-(ty+ink.top));ty-=max(0f,ty+ink.bottom-(h-pad))
            audit?.add(WeatherInfoWidgetRenderer.Element("text",s,RectF(tx+alignOffset+ink.left,ty+ink.top,tx+alignOffset+ink.right,ty+ink.bottom)))
            c.drawText(s,tx,ty,p);p.alpha=255;p.textAlign=Paint.Align.LEFT
        }
        fun icon(sky: String?,x: Float,y: Float,size: Float) {
            val bmp=WeatherIconCache.load(context,sky,size*scale)
            val ink=bmp?.let{bitmapInk(it)} ?: RectF(0f,0f,1f,1f)
            val tx=x+max(0f,pad-(x+ink.left*size))-max(0f,x+ink.right*size-(w-pad))
            val ty=y+max(0f,pad-(y+ink.top*size))-max(0f,y+ink.bottom*size-(h-pad))
            audit?.add(WeatherInfoWidgetRenderer.Element("icon",sky.orEmpty(),RectF(tx+ink.left*size,ty+ink.top*size,tx+ink.right*size,ty+ink.bottom*size)))
            bmp?.let{p.alpha=255;c.drawBitmap(it,null,RectF(tx,ty,tx+size,ty+size),p)}
        }
        fun temp(v: Double?)=WeatherSettings.tempText(context,v)
        val header=header(context,kind,widthDp,heightDp,city,data)
        fun footer(value: String) {if(showFooter) text(value,header.stampX,header.stampTop-Paint().apply{textSize=timestampSize(kind)}.fontMetrics.ascent,timestampSize(kind),alpha=128,maxWidth=header.stampWidth)}
        fun cityHeader() {text(header.name,header.x,header.baseline,header.size,true,maxWidth=header.cityWidth)}
        if(data==null) {
            cityHeader()
            text("暂无天气",pad,if(h<90) 47f else 67f,if(h<90) 20f else 27f,true,maxWidth=w-pad*2)
            if(h>=125) text("打开 App 添加城市并刷新",pad,99f,12f,alpha=210,maxWidth=w-pad*2)
            footer("暂无更新");return bitmap
        }
        val d=data
        val updated=updateText(context,d)
        val today=SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(Date())
        val day=d.days.firstOrNull{it.date.take(10)==today}
        val lowAndHigh="最低 ${temp(day?.low)}  最高 ${temp(day?.high)}"
        fun alignedTemperatureRow(bottom: Float,preferredMainSize: Float) {
            val main=temp(d.temperature)
            val feels="体感 ${temp(d.feels)}"
            val feelRatio=.4f
            val feelAlpha=204
            val rangeSize=13f*WIDE_TEXT_SCALE
            val gap=10f
            font(preferredMainSize,true);val mainWidth=p.measureText(main)
            font(preferredMainSize*feelRatio);val feelsWidth=p.measureText(feels)
            font(rangeSize);val rangeWidth=p.measureText(lowAndHigh)
            val fit=min(1f,(w-pad*2-gap*2f-rangeWidth-2f)/(mainWidth+feelsWidth).coerceAtLeast(1f))
            fun bottomText(value: String,x: Float,size: Float,bold: Boolean=false,align: Paint.Align=Paint.Align.LEFT,width: Float,alpha: Int=255) {
                font(size,bold)
                val bounds=Rect();p.getTextBounds(value,0,value.length,bounds)
                text(value,x,bottom-bounds.bottom,size,bold,alpha=alpha,maxWidth=width,align=align)
            }
            bottomText(main,pad,preferredMainSize*fit,true,width=mainWidth*fit+1f)
            bottomText(feels,pad+mainWidth*fit+gap,preferredMainSize*fit*feelRatio,width=feelsWidth*fit+1f,alpha=feelAlpha)
            bottomText(lowAndHigh,w-pad,rangeSize,align=Paint.Align.RIGHT,width=rangeWidth+1f)
        }
        if(kind=="compact" || h<110) {
            val tight=h<74
            val weather=compactWeather(context,w,h,d.sky)
            cityHeader()
            icon(d.sky,weather.centerX-weather.iconSize*.5f,weather.iconTop,weather.iconSize)
            val preferredSize=if(tight) COMPACT_MAIN*.82f else COMPACT_MAIN
            val main=temp(d.temperature)
            val caption="体感 ${temp(d.feels)}"
            val gap=4f
            font(preferredSize,true);val preferredMainWidth=p.measureText(main)
            font(preferredSize*.4f);val preferredCaptionWidth=p.measureText(caption)
            font(weather.labelSize)
            val weatherLabelWidth=p.measureText(WeatherText.skyconToText(d.sky))
            val bodyRight=weather.centerX-weatherLabelWidth*.5f-4f
            val available=(bodyRight-pad-gap).coerceAtLeast(1f)
            val fit=min(1f,available/(preferredMainWidth+preferredCaptionWidth).coerceAtLeast(1f))
            val stampHeight=timestampSize(kind)*context.resources.displayMetrics.scaledDensity/context.resources.displayMetrics.density*1.2f
            val heightFit=((h-pad)-(header.stampTop+stampHeight+2f))/(preferredSize*.8f)
            var mainSize=preferredSize*min(fit,heightFit.coerceIn(.35f,1f))
            repeat(16) {
                font(mainSize,true);val a=p.measureText(main)
                font(mainSize*.4f);val b=p.measureText(caption)
                if(a+b>available-.75f) mainSize*=.98f
            }
            font(mainSize,true)
            val mainWidth=p.measureText(main)
            val mainBounds=Rect();p.getTextBounds(main,0,main.length,mainBounds)
            val baseline=h-pad-mainBounds.bottom
            text(main,pad,baseline,mainSize,true,maxWidth=mainWidth+1f)
            val captionSize=mainSize*.4f
            font(captionSize)
            val captionBounds=Rect();p.getTextBounds(caption,0,caption.length,captionBounds)
            text(caption,pad+mainWidth+gap,h-pad-captionBounds.bottom,captionSize,
                alpha=(255f*.80f).roundToInt(),maxWidth=(bodyRight-pad-mainWidth-gap).coerceAtLeast(1f))
            text(WeatherText.skyconToText(d.sky),weather.centerX,weather.labelBaseline,weather.labelSize,
                maxWidth=(w-weather.centerX-7f)*2f,align=Paint.Align.CENTER)
            footer(updated);return bitmap
        }
        val square=kind=="square" || w<235
        if(square) {
            cityHeader()
            val main=temp(d.temperature)
            val feels="体感 ${temp(d.feels)}"
            font(13f)
            val rangeSize=min(13f,13f*(w-33f)/p.measureText(lowAndHigh).coerceAtLeast(1f))
            font(rangeSize)
            val rangeBounds=Rect();p.getTextBounds(lowAndHigh,0,lowAndHigh.length,rangeBounds)
            val rangeBaseline=h-pad-rangeBounds.bottom
            val groupShift=rangeBaseline-(h-24f)
            val weatherIconSize=39f
            val weatherInk=iconInk(context,d.sky,weatherIconSize)
            font(rangeSize);val labelInk=Rect();val condition=WeatherText.skyconToText(d.sky);p.getTextBounds(condition,0,condition.length,labelInk)
            val weatherCenter=w-pad-max(weatherInk.width(),labelInk.width().toFloat())*.5f
            icon(d.sky,weatherCenter-weatherIconSize*.5f,pad-weatherInk.top,weatherIconSize)
            text(condition,weatherCenter,pad+weatherInk.height()+8f-labelInk.top,rangeSize,
                maxWidth=(w-weatherCenter-7f)*2f,align=Paint.Align.CENTER)
            font(SQUARE_MAIN,true);val mainWidth=p.measureText(main)
            val fit=min(1f,(w-pad*2)/mainWidth.coerceAtLeast(1f))
            val baseBottom=min(84f,h-70f)
            val mainSize=min(SQUARE_MAIN*fit,(baseBottom-35f)/.75f).coerceAtLeast(22f)
            val bottom=baseBottom+groupShift
            font(mainSize,true)
            val mainBounds=Rect();p.getTextBounds(main,0,main.length,mainBounds)
            text(main,pad,bottom-mainBounds.bottom,mainSize,true,maxWidth=w-pad*2)
            val feelsSize=mainSize*.4f
            val feelsBaseline=bottom+max(20f,feelsSize*1.3f)
            text(feels,pad,feelsBaseline,feelsSize,alpha=204,maxWidth=w-pad*2)
            text(lowAndHigh,pad,rangeBaseline,rangeSize,maxWidth=w-pad*2)
            footer(updated);return bitmap
        }
        val large=kind=="large" && h>=320
        val weekly=kind=="weekly"
        val shortHeader=h<154
        val shortLarge=large && h<360
        cityHeader()
        // Larger templates have their own type scale; do not reuse the 2-row header.
        val conditionSize=(if(large) 18f else if(shortHeader) 14f else 15f)*1.2f*(if(large) 1f else WIDE_TEXT_SCALE)
        val weatherSize=(if(large) 44f else if(shortHeader) 32f else 34f)*1.2f
        font(conditionSize)
        val condition=WeatherText.skyconToText(d.sky)
        val labelWidth=p.measureText(condition).coerceAtMost(w*.35f)
        val weatherTop=(if(large) header.baseline+8f else pad)-iconInk(context,d.sky,weatherSize).top
        icon(d.sky,w-pad-labelWidth-7-weatherSize,weatherTop,weatherSize)
        val conditionBounds=Rect();p.getTextBounds(condition,0,condition.length,conditionBounds)
        val conditionBaseline=weatherTop+weatherSize*.5f-(conditionBounds.top+conditionBounds.bottom)*.5f
        text(condition,w-pad,conditionBaseline,conditionSize,maxWidth=w*.35f,align=Paint.Align.RIGHT)
        val denseHeader=(weekly || kind=="wide") && h<172
        val tempY=if(large) (if(shortLarge) 88f else 96f) else if(shortHeader) 63f else if(denseHeader) 71f else 77f
        val tempSize=if(large) LARGE_MAIN else if(shortHeader) 42f else if(weekly) 50f else 48f
        // 2-row cards keep main, 40% feels and high/low aligned; large feels uses its own row.
        if(large) {
            val main=temp(d.temperature)
            val feels="体感 ${temp(d.feels)}"
            val rangeSize=18f
            font(rangeSize)
            val rangeWidth=p.measureText(lowAndHigh)
            font(tempSize,true)
            val mainWidth=p.measureText(main)
            val mainSize=tempSize*min(1f,(w-pad*2-rangeWidth-14f)/mainWidth.coerceAtLeast(1f))
            font(mainSize,true)
            val mainBounds=Rect();p.getTextBounds(main,0,main.length,mainBounds)
            text(main,pad,tempY-mainBounds.bottom,mainSize,true,maxWidth=w-pad*2-rangeWidth-14f)
            val feelsSize=mainSize*.4f
            val feelsBaseline=tempY+max(22f,feelsSize*1.3f)
            text(feels,pad,feelsBaseline,feelsSize,alpha=204,maxWidth=w-pad*2)
            font(feelsSize)
            val feelsBounds=Rect();p.getTextBounds(feels,0,feels.length,feelsBounds)
            val feelsBottom=feelsBaseline+feelsBounds.bottom
            font(rangeSize)
            val rangeBounds=Rect();p.getTextBounds(lowAndHigh,0,lowAndHigh.length,rangeBounds)
            text(lowAndHigh,w-pad,feelsBottom-rangeBounds.bottom,rangeSize,maxWidth=rangeWidth+1f,align=Paint.Align.RIGHT)
        } else {
            alignedTemperatureRow(tempY,if(shortHeader) WIDE_MAIN*42f/46f else WIDE_MAIN)
        }
        fun forecastItem(label: String,sky: String?,value: String,center: Float,step: Float,
            bottom: Float,timeSize: Float,valueSize: Float,iconSize: Float) {
            fun fitValue(preferred: Float): Float {
                font(preferred,true)
                var result=min(preferred,preferred*(step-4f)/p.measureText(value).coerceAtLeast(1f))
                repeat(16) {font(result,true);if(p.measureText(value)>step-4f) result*=.98f}
                return result
            }
            val actualSize=fitValue(valueSize)
            font(actualSize,true)
            val valueBounds=Rect();p.getTextBounds(value,0,value.length,valueBounds)
            val valueBaseline=bottom-valueBounds.bottom
            val iconLayoutHeight=if(!large) {
                // Keep icon position/size unchanged while reducing the forecast text only.
                val previousSize=(if(h<154) 13f else 15f)*WIDE_TEXT_SCALE
                font(fitValue(previousSize),true)
                val originalBounds=Rect();p.getTextBounds(value,0,value.length,originalBounds)
                originalBounds.height().toFloat()
            } else valueBounds.height().toFloat()
            val iconTop=bottom-iconLayoutHeight-5f-iconSize
            font(timeSize)
            val timeBounds=Rect();p.getTextBounds(label,0,label.length,timeBounds)
            val timeBaseline=iconTop-5f-timeBounds.bottom
            text(label,center,timeBaseline,timeSize,alpha=225,maxWidth=step-3,align=Paint.Align.CENTER)
            icon(sky,center-iconSize*.5f,iconTop,iconSize)
            text(value,center,valueBaseline,actualSize,true,maxWidth=step-3,align=Paint.Align.CENTER)
        }
        val forecastTimeSize=if(large) 13f else DAILY_TEXT_SIZE
        val forecastValueSize=if(large) 15f else DAILY_TEXT_SIZE
        val forecastIconSize=if(!large && h<154f) 24f else 28f
        if(weekly) {
            val dates=WeatherWidgetPolicy.futureDates(5)
            val byDate=d.days.associateBy{it.date.take(10)}
            val step=(w-pad*2)/5f
            dates.forEachIndexed {i,date ->
                val item=byDate[date];val center=pad+step*(i+.5f)
                val caption=when(i){0->"明天";1->"后天";else->weekday(date)}
                forecastItem(caption,item?.sky,"${temp(item?.low)}/${temp(item?.high)}",center,step,
                    h-pad,forecastTimeSize,forecastValueSize,forecastIconSize)
            }
        } else if(h>=140) {
            val divider=if(large) (if(shortLarge) 133f else 142f) else 85f
            val bottom=if(large) divider+66f else h-pad
            val step=(w-pad*2)/6f
            repeat(6) {i ->
                val item=d.hours.getOrNull(i);val center=pad+step*(i+.5f)
                forecastItem(item?.let{forecastHourLabel(it.time)} ?: "--",item?.sky,temp(item?.temperature),
                    center,step,bottom,forecastTimeSize,forecastValueSize,forecastIconSize)
            }
        }
        if(large) {
            val rowText=LARGE_DAY_TEXT_SIZE
            val byDate=d.days.associateBy{it.date.take(10)}
            val future=WeatherWidgetPolicy.futureDates(LARGE_DAY_COUNT).map{it to byDate[it]}
            val lows=future.mapNotNull{it.second?.low};val highs=future.mapNotNull{it.second?.high}
            val minimum=lows.minOrNull() ?: 0.0;val maximum=highs.maxOrNull() ?: 1.0
            // Reserve complete date/weekday and temperature labels before sizing the rail.
            font(rowText)
            val dateWidth=future.maxOf{p.measureText(datedWeekday(it.first))}+1f
            font(rowText,true)
            val numberWidth=future.flatMap{listOf(temp(it.second?.low),temp(it.second?.high))}.maxOf{p.measureText(it)}+2f
            val baseIcon=if(shortLarge) 16f else 20f
            val fixedGaps=pad*2+10f+10f+5f+8f
            val columnScale=min(1f,(w-fixedGaps-48f)/(dateWidth+baseIcon+numberWidth*2)).coerceAtLeast(.5f)
            val labelSize=rowText*columnScale
            val dayIcon=baseIcon*columnScale
            val valueWidth=numberWidth*columnScale
            val previousIconX=pad+dateWidth*columnScale+10f
            val previousLeft=previousIconX+dayIcon+10f+valueWidth+5f
            val previousRight=w-pad-valueWidth-5f
            val previousLength=(previousRight-previousLeft).coerceAtLeast(0f)
            val railLength=previousLength*.70f
            // Split the freed 30% equally between date/icon and icon/rail gaps.
            val equalGap=10f+previousLength*.15f
            val iconX=pad+dateWidth*columnScale+equalGap
            val lowX=iconX+dayIcon+equalGap
            val left=lowX+valueWidth+5f
            val right=left+railLength
            // The only separator sits halfway between the actual hourly/day content bounds.
            val hourlyBottom=(if(shortLarge) 133f else 142f)+66f
            val firstCenter=hourlyBottom+14f+max(dayIcon*.5f,labelSize-5f)
            font(labelSize);val lastText=Rect();p.getTextBounds(temp(future.lastOrNull()?.second?.high),0,temp(future.lastOrNull()?.second?.high).length,lastText)
            val lastIconHalf=iconInk(context,future.lastOrNull()?.second?.sky,dayIcon).height()*.5f
            val lastCenter=h-pad-max(lastIconHalf,5f+lastText.bottom)
            val dayStep=(lastCenter-firstCenter)/(future.size-1).coerceAtLeast(1)
            val firstDayTop=firstCenter-max(dayIcon*.5f,labelSize-5f)
            val separator=(hourlyBottom+firstDayTop)*.5f
            p.color=0x28FFFFFF;p.strokeWidth=.6f;c.drawLine(pad,separator,w-pad,separator,p)
            future.forEachIndexed {i,(date,item) ->
                val y=firstCenter+dayStep*i
                text(datedWeekday(date),pad,y+5,labelSize,maxWidth=dateWidth*columnScale)
                icon(item?.sky,iconX,y-dayIcon/2,dayIcon)
                // Low and high sit immediately outside opposite ends of the rail.
                text(temp(item?.low),lowX,y+5,labelSize,alpha=230,maxWidth=valueWidth)
                p.strokeCap=Paint.Cap.ROUND;p.strokeWidth=6f;p.color=0x25FFFFFF;c.drawLine(left,y,right,y,p)
                if(item?.low!=null && item.high!=null) {
                    val span=(maximum-minimum).toFloat().coerceAtLeast(1f)
                    val a=left+(item.low-minimum).toFloat()/span*(right-left)
                    val b=left+(item.high-minimum).toFloat()/span*(right-left)
                    p.alpha=255;p.shader=LinearGradient(left,0f,right,0f,intArrayOf(0xFF8ED8FF.toInt(),0xFF8EE5C0.toInt(),0xFFFFCE70.toInt()),null,Shader.TileMode.CLAMP)
                    c.drawLine(a,y,max(a+.5f,b),y,p);p.shader=null
                }
                p.strokeCap=Paint.Cap.BUTT
                text(temp(item?.high),right+5f,y+5,labelSize,true,maxWidth=valueWidth)
            }
        }
        footer(updated);return bitmap
    }
    private fun datedWeekday(date: String): String = runCatching {
        val time=SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).apply{isLenient=false}.parse(date)!!
        SimpleDateFormat("M月d日 EEE",Locale.CHINA).format(time).replace("星期","周")
    }.getOrDefault("${date.takeLast(5)} ${weekday(date)}")
    private fun weekday(date: String): String = runCatching {
        val time=SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).parse(date)!!
        SimpleDateFormat("EEE",Locale.CHINA).format(time).replace("星期","周")
    }.getOrDefault(date.takeLast(5))
}
