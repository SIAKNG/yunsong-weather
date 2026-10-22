package com.example.weatherapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Independently drawn basic-shape fallback; no third-party SVG paths or pixel data. */
internal object PublicWeatherIconRenderer {
    enum class Kind { SUN, MOON, CLOUD, RAIN, SNOW, SLEET, STORM, FOG, WIND, SUNRISE, SUNSET }

    fun kind(filename: String): Kind = when (filename.removePrefix("icon_").removeSuffix(".svg")) {
        "tq-1", "qingtian", "re" -> Kind.SUN
        "yewan", "yejianqingtian" -> Kind.MOON
        "richu" -> Kind.SUNRISE
        "riluo" -> Kind.SUNSET
        "xiaoyu", "zhongyu", "dayu", "baoyu", "tedabaoyubaoyu", "zhenyu", "dongyu" -> Kind.RAIN
        "xiaoxue", "zhongxue", "daxue", "baoxue", "zhenxue", "leng" -> Kind.SNOW
        "yujiaxue" -> Kind.SLEET
        "leizhenyu", "leizhenyubanyoubingbao" -> Kind.STORM
        "wu", "mai", "nongwu", "qingwu", "qiangnongwu", "fuchen", "yangsha", "shachenbao" -> Kind.FOG
        "dafeng", "hefeng", "weifeng", "qingfeng", "jifeng", "fengbao", "longjuanfeng" -> Kind.WIND
        else -> Kind.CLOUD
    }

    fun render(filename: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(bitmap.width / 100f, bitmap.height / 100f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun fill(color: Int) { p.style = Paint.Style.FILL; p.color = color }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, stroke: Float = 4f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.color = color
            c.drawLine(x1,y1,x2,y2,p)
        }
        val gold = Color.rgb(238,169,30)
        val blue = Color.rgb(41,128,202)
        val cloud = Color.rgb(137,171,195)
        fun sun() {
            fill(gold);c.drawCircle(50f,43f,20f,p)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                line(50f+29f*kotlin.math.cos(a).toFloat(),43f+29f*kotlin.math.sin(a).toFloat(),
                    50f+35f*kotlin.math.cos(a).toFloat(),43f+35f*kotlin.math.sin(a).toFloat(),gold)
            }
        }
        fun clouds() {
            fill(cloud);c.drawCircle(36f,43f,17f,p);c.drawCircle(55f,35f,22f,p);c.drawCircle(72f,46f,14f,p)
            c.drawRoundRect(19f,43f,86f,64f,11f,11f,p)
        }
        val k = kind(filename)
        when (k) {
            Kind.SUN -> sun()
            Kind.MOON -> {
                val moon = Path().apply { addCircle(48f,44f,29f,Path.Direction.CW) }
                moon.op(Path().apply { addCircle(63f,30f,26f,Path.Direction.CW) },Path.Op.DIFFERENCE)
                fill(gold);c.drawPath(moon,p)
            }
            Kind.SUNRISE, Kind.SUNSET -> {
                c.save();c.clipRect(0f,0f,100f,58f);fill(gold);c.drawCircle(50f,58f,22f,p);c.restore()
                line(15f,60f,85f,60f,cloud)
                val tip = if(k == Kind.SUNRISE) 72f else 87f
                val base = if(k == Kind.SUNRISE) 87f else 72f
                line(50f,base,50f,tip,blue)
                val shoulder = if(k == Kind.SUNRISE) tip+6f else tip-6f
                line(44f,shoulder,50f,tip,blue);line(56f,shoulder,50f,tip,blue)
            }
            Kind.WIND -> {
                line(17f,32f,79f,32f,cloud);line(26f,49f,86f,49f,blue);line(17f,66f,68f,66f,cloud)
            }
            else -> {
                clouds()
                when(k) {
                    Kind.RAIN, Kind.SLEET -> repeat(3) { i ->
                        val x=32f+i*20f;line(x,73f,x-4f,86f,blue,5f)
                    }
                    Kind.SNOW -> repeat(3) { i ->
                        val x=31f+i*21f;line(x-4f,77f,x+4f,85f,blue,3f)
                        line(x+4f,77f,x-4f,85f,blue,3f);line(x,75f,x,87f,blue,3f)
                    }
                    Kind.STORM -> {
                        fill(gold);c.drawPath(Path().apply {
                            moveTo(52f,65f);lineTo(40f,80f);lineTo(50f,80f);lineTo(45f,94f)
                            lineTo(63f,74f);lineTo(53f,74f);close()
                        },p)
                    }
                    Kind.FOG -> { line(22f,75f,80f,75f,cloud,3f);line(31f,85f,72f,85f,cloud,3f) }
                    else -> Unit
                }
            }
        }
        return bitmap
    }
}
