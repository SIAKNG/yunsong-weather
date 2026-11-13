package com.example.weatherapp

import android.content.Context
import android.graphics.*
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** Production bitmap renderer for the five reviewed information widgets. No fixture data. */
internal object WeatherInfoWidgetRenderer {
    val kinds = setOf("air", "rain", "rain_trend", "cities", "daily_summary")
    fun supports(kind: String) = kind in kinds
    fun displaySky(data: WidgetWeather?): String? = when {
        data?.night == true && data.sky == "CLEAR_DAY" -> "CLEAR_NIGHT"
        data?.night == true && data.sky == "PARTLY_CLOUDY_DAY" -> "PARTLY_CLOUDY_NIGHT"
        data?.night == false && data.sky == "CLEAR_NIGHT" -> "CLEAR_DAY"
        data?.night == false && data.sky == "PARTLY_CLOUDY_NIGHT" -> "PARTLY_CLOUDY_DAY"
        else -> data?.sky
    }
    fun cityEdges(width: Float,count: Int=3): List<Float> = (0..count).map { 1f + (width - 2f) * it / count }
    internal data class Element(val type: String,val label: String,val bounds: RectF)
    fun cityUpdateText(c: Context,values: List<Pair<CityPage,WidgetWeather?>>): String {
        if(values.size<2 || values.any{it.second==null || it.second!!.observed<=0}) return "待刷新"
        return WeatherWidgetRenderer.updateText(c,values.mapNotNull{it.second}.minBy{it.observed})
    }
    fun description(c: Context,kind: String,d: WidgetWeather?,cities: List<Pair<CityPage,WidgetWeather?>>): String {
        if(kind=="cities") return cities.joinToString("；"){(city,data)->"${WeatherAddressFormatter.city(city.name)}，${WeatherSettings.tempText(c,data?.temperature)}，体感${WeatherSettings.tempText(c,data?.feels)}，${WeatherText.skyconToText(data?.sky)}"}.ifEmpty{"多城天气，尚未选择城市"}+"；"+cityUpdateText(c,cities)
        if(d==null) return "暂无天气，请打开应用刷新"
        return when(kind) {
            "air" -> "${WeatherAddressFormatter.city(d.city.name)}，空气质量AQI ${d.aqi?.roundToInt() ?: "暂无"}，${AirQualityScale.level(d.aqi)}，${WeatherWidgetRenderer.updateText(c,d)}"
            "rain","rain_trend" -> "${WeatherAddressFormatter.city(d.city.name)}，降水趋势，${nextHours(d).mapNotNull{HourlyForecastDetailPolicy.percent(it.rain)}.maxOrNull()?.let{"最高概率$it%"} ?: "概率待补充"}，${WeatherWidgetRenderer.updateText(c,d)}"
            else -> WeatherWidgetSummary.build(d).let{s->"${WeatherAddressFormatter.city(d.city.name)}，今日概览，实时${WeatherSettings.tempText(c,d.temperature)}，体感${WeatherSettings.tempText(c,d.feels)}，低高温${rangeTemp(c,s.temperature)}。${s.rainAdvice}。${s.windAdvice}。${s.uvAdvice}。${s.airAdvice}。${WeatherWidgetRenderer.updateText(c,d)}"}
        }
    }
    private fun nextHours(d: WidgetWeather,now: Long=System.currentTimeMillis()) = d.hours.filter{it.time>=now-30*60_000}.take(6)
    private fun rangeTemp(c: Context,range: Pair<Double,Double>?) = range?.let{"${WeatherSettings.tempText(c,it.first)}–${WeatherSettings.tempText(c,it.second)}"} ?: "—"
    private fun numberRange(range: Pair<Double,Double>?) = range?.let{"${it.first.roundToInt()}–${it.second.roundToInt()}"} ?: "—"
    private fun date(t: Long,pattern: String)=SimpleDateFormat(pattern,Locale.CHINA).format(Date(t))
    private fun clock(value: String?): String = value?.let { if(it.matches(Regex("\\d{2}:\\d{2}"))) it else TimeUtil.parse(it)?.let{v->date(v,"HH:mm")} } ?: "—"
    private fun colors(d: WidgetWeather?): IntArray = WeatherBackgroundPalette.colors(d?.sky,d?.night ?: (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) !in 6..17)).map {original->
        var v=original
        while(ColorUtils.calculateContrast(Color.WHITE,v)<5.0) v=ColorUtils.blendARGB(v,Color.rgb(22,38,66),.12f)
        v
    }.toIntArray()
    fun render(context: Context,kind: String,width: Int,height: Int,city: CityPage?,data: WidgetWeather?,cities: List<Pair<CityPage,WidgetWeather?>> = emptyList(),audit: MutableList<Element>?=null): Bitmap {
        val w=width.coerceIn(120,600).toFloat();val h=height.coerceIn(64,700).toFloat()
        val scale=min(context.resources.displayMetrics.density,min(900f/w,1050f/h))
        val bitmap=Bitmap.createBitmap((w*scale).roundToInt(),(h*scale).roundToInt(),Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.scale(scale,scale)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val cityCount=cities.size.coerceIn(2,3)
        val surfaceColors=if(kind=="cities") (0 until cityCount).map{i->colors(cities.getOrNull(i)?.second ?: if(i==0) data else null)} else listOf(colors(data))
        WeatherWidgetSurface.draw(canvas,w,h,surfaceColors)
        val bw=if(kind in setOf("air","rain")) 172f else 356f
        val bh=if(kind=="daily_summary") 372f else 172f
        val pad=WeatherWidgetRenderer.contentInset(width,height,kind);val sx=(w-pad*2)/(bw-28);val sy=(h-pad*2)/(bh-28);val fontScale=min(1f,min(sx,sy)).coerceAtLeast(.5f)
        fun x(value: Float)=pad+(value-14f)*sx
        fun y(value: Float)=pad+(value-14f)*sy
        fun text(value: String,xx: Float,yy: Float,size: Float=12f,bold: Boolean=false,color: Int=Color.WHITE,alpha: Int=255,maxWidth: Float=bw-14-xx,align: Paint.Align=Paint.Align.LEFT,shrink: Boolean=true,bottomAlignedTo: Float?=null): RectF {
            paint.shader=null;paint.color=color;paint.alpha=alpha;paint.typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)
            val available=(maxWidth*sx).coerceAtLeast(1f)
            paint.textSize=size*fontScale
            // TextUtils.breakText rounds widths differently at an exact fit. Keep a small
            // safety margin so complete temperatures and four-character weather labels survive.
            if(shrink) paint.textSize=min(paint.textSize,paint.textSize*(available-fontScale).coerceAtLeast(1f)/paint.measureText(value).coerceAtLeast(1f)).coerceAtLeast(8f*fontScale)
            var str=TextUtils.ellipsize(value,android.text.TextPaint(paint),available,TextUtils.TruncateAt.END).toString()
            // Hinting and fallback fonts are not perfectly linear. Verify the final Android
            // string and fit again rather than allowing a rounded width to remove a digit.
            if(shrink) while(str!=value && paint.textSize>8f*fontScale+.01f) {
                paint.textSize=(paint.textSize-.25f*fontScale).coerceAtLeast(8f*fontScale)
                str=TextUtils.ellipsize(value,android.text.TextPaint(paint),available,TextUtils.TruncateAt.END).toString()
            }
            val bounds=Rect();paint.getTextBounds(str,0,str.length,bounds)
            var tx=x(xx);val ty=if(bottomAlignedTo!=null) bottomAlignedTo-bounds.bottom else if(yy>=bh-30f) h-pad-bounds.bottom else y(yy)-bounds.top
            if(align==Paint.Align.LEFT) tx-=bounds.left
            else if(align==Paint.Align.RIGHT) tx-=bounds.right
            else tx-=(bounds.left+bounds.right)/2f
            val measuredBounds=RectF(tx+bounds.left,ty+bounds.top,tx+bounds.right,ty+bounds.bottom)
            audit?.add(Element("text",str,measuredBounds))
            canvas.drawText(str,tx,ty,paint);paint.alpha=255
            return measuredBounds
        }
        fun line(xx: Float,yy: Float,length: Float) {paint.shader=null;paint.color=0x25FFFFFF;paint.strokeWidth=.6f;canvas.drawLine(x(xx),y(yy),x(xx+length),y(yy),paint)}
        fun icon(sky: String?,xx: Float,yy: Float,size: Float,bottomAlignedTo: Float?=null) {WeatherIconCache.load(context,sky,size*scale*fontScale)?.let {b->
            paint.shader=null;paint.alpha=255
            var left=0;var top=0;var right=b.width;var bottom=b.height
            if(bottomAlignedTo!=null) {
                val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
                left=b.width;top=b.height;right=0;bottom=0
                for(py in 0 until b.height)for(px in 0 until b.width)if((pixels[py*b.width+px] ushr 24)>8){left=min(left,px);top=min(top,py);right=max(right,px+1);bottom=max(bottom,py+1)}
                if(bottom==0){left=0;top=0;right=b.width;bottom=b.height}
            }
            val side=size*fontScale;val actualTop=bottomAlignedTo?.let{it-bottom.toFloat()/b.height*side} ?: y(yy)
            val rect=RectF(x(xx),actualTop,x(xx)+side,actualTop+side)
            audit?.add(Element("icon",sky.orEmpty(),if(bottomAlignedTo==null)RectF(rect)else RectF(rect.left+left.toFloat()/b.width*side,rect.top+top.toFloat()/b.height*side,rect.left+right.toFloat()/b.width*side,bottomAlignedTo)))
            canvas.drawBitmap(b,null,rect,paint)
        }}
        fun plate(sky: String?,xx: Float,yy: Float) {paint.shader=null;paint.color=0x1FFFFFFF;val r=18*fontScale;canvas.drawCircle(x(xx)+r,y(yy)+r,r,paint);icon(sky,xx+6*fontScale/sx,yy+6*fontScale/sy,24f)}
        fun header(title: String,small: Boolean=false) {
            text(title,14f,14f,if(small)14f else 15f,true,maxWidth=if(small)144f else 235f)
            if(small) text("${city?.let{WeatherAddressFormatter.city(it.name)} ?: "云松天气"}  ${WeatherWidgetRenderer.updateText(context,data)}",14f,35f,10f,alpha=145,maxWidth=144f)
            else text(WeatherWidgetRenderer.updateText(context,data),bw-14,18f,10f,alpha=145,maxWidth=85f,align=Paint.Align.RIGHT)
        }
        fun airColor(value: Double?): Int {
            var color=if(value==null) Color.LTGRAY else AirQualityScale.color(value)
            val backdrop=colors(data).last()
            repeat(20){if(ColorUtils.calculateContrast(color,backdrop)<3.0) color=ColorUtils.blendARGB(color,Color.WHITE,.06f)}
            return color
        }
        fun airMeter(value: Double?,xx: Float,yy: Float,length: Float) {
            val stops=floatArrayOf(0f,.1f,.2f,.3f,.4f,.6f,1f)
            paint.shader=LinearGradient(x(xx),0f,x(xx+length),0f,doubleArrayOf(0.0,50.0,100.0,150.0,200.0,300.0,500.0).map(AirQualityScale::color).toIntArray(),stops,Shader.TileMode.CLAMP)
            paint.strokeWidth=5f*fontScale;paint.strokeCap=Paint.Cap.ROUND;canvas.drawLine(x(xx),y(yy),x(xx+length),y(yy),paint);paint.shader=null;paint.strokeCap=Paint.Cap.BUTT
            value?.takeIf{it.isFinite()}?.let{v->val at=x(xx)+(x(xx+length)-x(xx))*(v.toFloat()/500f).coerceIn(0f,1f);paint.color=Color.WHITE;canvas.drawCircle(at,y(yy),4f*fontScale,paint);paint.color=AirQualityScale.color(v);canvas.drawCircle(at,y(yy),2.7f*fontScale,paint)}
        }
        fun bars(hours: List<WidgetHour>,xx: Float,yy: Float,length: Float,height: Float,showValues: Boolean) {
            val step=length/6
            repeat(6){i->val item=hours.getOrNull(i);val percent=HourlyForecastDetailPolicy.percent(item?.rain);val center=xx+(i+.5f)*step
                paint.shader=null;paint.color=if(percent==null)0x35FFFFFF else 0xBBBAE9FF.toInt()
                val barHeight=max(1f,(percent ?: 0)/100f*height)
                canvas.drawRoundRect(RectF(x(center-5),y(yy+height-barHeight),x(center+5),y(yy+height)),3f*fontScale,3f*fontScale,paint)
                if(showValues) text(percent?.let{"$it%"} ?: "—",center,yy+height-barHeight-17,11f,alpha=220,maxWidth=step-3,align=Paint.Align.CENTER)
                if(showValues || i in listOf(0,2,5)) text(item?.let{date(it.time,"HH时")} ?: "—",center,yy+height+6,11f,alpha=220,maxWidth=step-2,align=Paint.Align.CENTER)
            }
        }
        if(kind=="cities") {
            text(cityUpdateText(context,cities),342f,if(cityCount==3)145f else 18f,11f,alpha=150,maxWidth=105f,align=Paint.Align.RIGHT)
            val edges=cityEdges(w,cityCount)
            fun bx(actual: Float)=14f+(actual-pad)/sx
            fun by(actual: Float)=14f+(actual-pad)/sy
            repeat(cityCount){i->
                val entry=cities.getOrNull(i) ?: if(i==0 && city!=null) city to data else null
                val left=edges[i];val right=edges[i+1];val origin=bx(left);val cw=(right-left)/sx
                val two=cityCount==2;val size=if(two)46f else 36f
                val weather=entry?.second?.let{WeatherText.skyconToText(it.sky)} ?: "待更新"
                val weatherSize=if(two)13f else 12f;val weatherMax=if(two)70f else 46f
                paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL);paint.textSize=weatherSize*fontScale
                val measured=paint.measureText(weather)/sx
                val fitted=measured*min(1f,weatherMax/measured.coerceAtLeast(1f))
                val center=origin+min(cw-(if(two)41f else 33f),cw-13f/sx-fitted/2)
                val start=bx(left+13f)
                text(entry?.first?.let{WeatherAddressFormatter.city(it.name)} ?: "请选择第${i+1}城",start,14f,if(two)18f else 16f,true,maxWidth=((right-left-26)/sx-(if(two&&i==1)60f else 0f)).coerceAtLeast(24f),shrink=false)
                val currentBounds=text(WeatherSettings.tempText(context,entry?.second?.temperature),start,if(two)59f else 55f,if(two)38f else 32f,true,maxWidth=if(two)88f else min(48f,center-start-fitted/2-4).coerceAtLeast(24f))
                text("体感 ${WeatherSettings.tempText(context,entry?.second?.feels)}",start,if(two)107f else 105f,if(two)15f else 14f,alpha=220,maxWidth=(right-left-26)/sx)
                // Align the complete weather shape with the actual fitted temperature glyphs,
                // including negative values and Fahrenheit. Do not align it to the city heading.
                val iconTop=by(currentBounds.centerY()-size*fontScale/2f)
                val conditionTop=by(currentBounds.centerY()+size*fontScale/2f+6f*fontScale)
                icon(displaySky(entry?.second),center-size*fontScale/(2*sx),iconTop,size)
                text(weather,center,conditionTop,weatherSize,alpha=220,maxWidth=weatherMax,align=Paint.Align.CENTER)
                val day=entry?.second?.days?.firstOrNull{it.date==DailyForecastDetailPolicy.dateKey(System.currentTimeMillis())}
                text("${WeatherSettings.tempText(context,day?.low)} / ${WeatherSettings.tempText(context,day?.high)}",start,if(two)142f else 127f,14f,alpha=225,maxWidth=(right-left-26)/sx)
            }
            paint.shader=null;paint.color=0x30FFFFFF;paint.strokeWidth=.75f;edges.drop(1).dropLast(1).forEach{edge->canvas.drawLine(edge,2f,edge,h-2,paint)}
            return bitmap
        }
        if(kind=="air") {
            header("空气质量",true)
            val aqiBounds=text(data?.aqi?.takeIf{it.isFinite()}?.roundToInt()?.toString() ?: "—",14f,54f,43f,true,maxWidth=77f)
            val level=AirQualityScale.level(data?.aqi);text(level,94f,79f,if(level.length>2)15f else 17f,true,color=airColor(data?.aqi),maxWidth=64f,bottomAlignedTo=aqiBounds.bottom)
            text("当前 AQI",14f,105f,11f,alpha=220);airMeter(data?.aqi,17f,130f,138f)
            text(if(data?.aqi==null) "等待空气质量数据" else "当前空气 · $level",14f,145f,11f,alpha=220)
            return bitmap
        }
        if(kind in setOf("rain","rain_trend")) {
            val hours=data?.let{nextHours(it)}.orEmpty();val peak=hours.mapNotNull{HourlyForecastDetailPolicy.percent(it.rain)}.maxOrNull()
            if(kind=="rain") {
                header(if(data?.sky?.contains("SNOW")==true) "未来降水" else "未来降雨",true)
                val peakBounds=text(peak?.toString() ?: "—",14f,54f,40f,true,maxWidth=82f)
                if(peak!=null) text("%",if(peak<10)44f else if(peak<100)66f else 88f,78f,16f,maxWidth=21f,bottomAlignedTo=peakBounds.bottom)
                icon(displaySky(data),115f,52f,41f,bottomAlignedTo=peakBounds.bottom.takeIf{peak!=null});text("未来6小时最高概率",14f,100f,11f,alpha=220)
                bars(hours,14f,117f,144f,24f,false)
            } else {
                header("${city?.let{WeatherAddressFormatter.city(it.name)} ?: "云松天气"} · 降雨趋势")
                val headline=when{peak==null->"概率待补充";peak<=10->"降水概率较低";else->"未来6小时"}
                val headlineBounds=text(headline,14f,45f,27f,true,maxWidth=180f)
                text(peak?.let{"最高 $it%"} ?: "暂无概率",282f,61f,17f,alpha=220,maxWidth=100f,align=Paint.Align.RIGHT,bottomAlignedTo=headlineBounds.bottom)
                icon(displaySky(data),302f,44f,40f,bottomAlignedTo=headlineBounds.bottom);bars(hours,14f,102f,328f,35f,true)
            }
            return bitmap
        }
        text("${city?.let{WeatherAddressFormatter.city(it.name)} ?: "云松天气"} · 今日概览",14f,14f,18f,true,maxWidth=215f)
        text(WeatherWidgetRenderer.updateText(context,data),342f,19f,11f,alpha=150,maxWidth=105f,align=Paint.Align.RIGHT)
        if(data==null) {text("当日数据待补充",14f,65f,24f,true);text("打开应用刷新后查看",14f,114f,14f,alpha=220);return bitmap}
        val summary=WeatherWidgetSummary.build(data)
        if(!summary.complete) text("逐时未齐",14f,40f,11f,alpha=160,maxWidth=180f)
        val current=WeatherSettings.tempText(context,data.temperature)
        paint.typeface=Typeface.create("sans-serif",Typeface.BOLD);paint.textSize=36f*fontScale
        val feelX=14f+min(90f,paint.measureText(current)/sx)+10f
        text(current,14f,63f,36f,true,maxWidth=90f)
        text("体感 ${WeatherSettings.tempText(context,data.feels)}",feelX,82f,14f,alpha=220,maxWidth=(180-feelX).coerceAtLeast(40f))
        text(WeatherText.skyconToText(data.sky),276f,70f,20f,true,maxWidth=82f,align=Paint.Align.RIGHT)
        icon(displaySky(data),284f,52f,58f)
        summary.temperature?.let{range->
            paint.shader=LinearGradient(x(18f),0f,x(172f),0f,intArrayOf(0xFF9CDFE8.toInt(),0xFFB6E6A4.toInt(),0xFFEAD677.toInt(),0xFFEFB56B.toInt(),0xFFED8C78.toInt()),null,Shader.TileMode.CLAMP)
            paint.strokeWidth=5*fontScale;paint.strokeCap=Paint.Cap.ROUND;canvas.drawLine(x(18f),y(112f),x(172f),y(112f),paint);paint.shader=null;paint.strokeCap=Paint.Cap.BUTT
            data.temperature?.takeIf{it.isFinite()}?.let{v->val fraction=if(range.second<=range.first).5f else ((v-range.first)/(range.second-range.first)).toFloat().coerceIn(0f,1f);paint.color=Color.WHITE;canvas.drawCircle(x(18f)+(x(172f)-x(18f))*fraction,y(112f),4.3f*fontScale,paint)}
            text("低 ${WeatherSettings.tempText(context,range.first)}",14f,130f,12f,alpha=220,maxWidth=77f)
            text("高 ${WeatherSettings.tempText(context,range.second)}",176f,130f,12f,alpha=220,maxWidth=77f,align=Paint.Align.RIGHT)
        }
        line(14f,155f,328f)
        text("降水概率",58f,167f,12f,alpha=220,maxWidth=104f);plate(if(data.sky?.contains("SNOW")==true) "LIGHT_SNOW" else "LIGHT_RAIN",14f,182f)
        text(summary.rain?.let{"$it%"} ?: "—",58f,187f,24f,true,maxWidth=104f);text(summary.rainAdvice,14f,224f,12f,alpha=220,maxWidth=148f)
        text("风速范围",238f,167f,12f,alpha=220,maxWidth=104f);plate("WIND",194f,182f)
        val windText=summary.wind?.let{val unit=WeatherSettings.metricUnit(context,MetricKind.WIND);WeatherSettings.windSpeedValue(context,it.first,null).removeSuffix(unit)+"–"+WeatherSettings.windSpeedValue(context,it.second,null).removeSuffix(unit)} ?: "—"
        text(windText,238f,187f,23f,true,maxWidth=70f);if(summary.wind!=null) text(WeatherSettings.metricUnit(context,MetricKind.WIND),342f,200f,11f,alpha=220,maxWidth=33f,align=Paint.Align.RIGHT)
        text(summary.windAdvice,194f,224f,12f,alpha=220,maxWidth=148f)
        line(14f,245f,148f);line(194f,245f,148f)
        text("紫外线峰值",58f,254f,12f,alpha=220,maxWidth=104f);plate("CLEAR_DAY",14f,270f)
        text(summary.ultraviolet?.uv?.let{String.format(Locale.US,"%.1f",it)} ?: "—",58f,274f,24f,true,maxWidth=104f)
        text(summary.uvAdvice,14f,311f,12f,alpha=220,maxWidth=148f)
        text("空气质量范围",238f,254f,12f,alpha=220,maxWidth=104f);plate("LIGHT_HAZE",194f,270f)
        text(numberRange(summary.air),238f,274f,22f,true,maxWidth=104f)
        // Requested final wording: no extra '优—良' level row under this daily range.
        text(summary.airAdvice,194f,311f,12f,alpha=220,maxWidth=148f)
        line(14f,333f,328f)
        text("湿度 "+(summary.humidity?.let{numberRange(it)+"%"} ?: "—"),14f,344f,12f,alpha=220,maxWidth=117f)
        text("日出 ${clock(data.sunrise)}",199f,344f,12f,alpha=220,maxWidth=95f,align=Paint.Align.CENTER)
        text("日落 ${clock(data.sunset)}",342f,344f,12f,alpha=220,maxWidth=95f,align=Paint.Align.RIGHT)
        return bitmap
    }
}
