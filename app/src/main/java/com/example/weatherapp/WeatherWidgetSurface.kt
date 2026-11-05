package com.example.weatherapp

import android.graphics.*
import kotlin.math.max

/** The installed widgets share one clipped gradient, radius and frosted rim. */
internal object WeatherWidgetSurface {
    fun draw(canvas: Canvas,w: Float,h: Float,panels: List<IntArray>) {
        val radius=WeatherWidgetRenderer.cornerRadiusDp()
        val card=RectF(1f,1f,w-1,h-1)
        val shape=Path().apply{addRoundRect(card,radius,radius,Path.Direction.CW)}
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save();canvas.clipPath(shape)
        panels.forEachIndexed {i,colors->
            val left=if(panels.size==1) 0f else 1f+(w-2)*i/panels.size
            val right=if(panels.size==1) w else 1f+(w-2)*(i+1)/panels.size
            canvas.save()
            if(panels.size>1) canvas.clipRect(if(i==0) 0f else left,0f,if(i==panels.lastIndex) w else right,h)
            p.shader=LinearGradient(left,0f,right,h,colors,null,Shader.TileMode.CLAMP)
            canvas.drawRect(card,p)
            canvas.restore()
        }
        // One glow and veil for the whole card, including multi-city surfaces.
        p.shader=RadialGradient(w*.85f,h*.15f,max(w,h)*.85f,intArrayOf(0x3AFFFFFF,0x00FFFFFF),null,Shader.TileMode.CLAMP)
        canvas.drawRect(card,p)
        p.shader=null;p.color=0x20000000;canvas.drawRect(card,p)
        WeatherFrostedBorderDrawable(1f,radius,true).apply{setBounds(1,1,(w-1).toInt(),(h-1).toInt());draw(canvas)}
        canvas.restore()
    }
}
