package com.v2ray.ang.delta

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import kotlin.math.cos
import kotlin.math.sin

/** Native, allocation-light neon power control. No network calls or background animation. */
class DeltaPowerButton(context: Context): ImageButton(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect=RectF()
    private var connected=false
    private var changing=false
    private var foreground=false
    private var animationStart=0L
    private val center=120f
    private val radius=76f
    private val cyan=Color.rgb(65,224,255)
    private val white=Color.rgb(235,250,255)
    private val halo=RadialGradient(120f,120f,118f,
        intArrayOf(0x003A8DFF,0x123A8DFF,0x503378FF,0x142A77FF,0x002A77FF),
        floatArrayOf(0f,.48f,.66f,.85f,1f),Shader.TileMode.CLAMP)
    private val activeFill=RadialGradient(101f,82f,113f,
        intArrayOf(0xFF64D7FF.toInt(),0xFF247FF5.toInt(),0xFF0648CE.toInt(),0xFF062A83.toInt()),
        floatArrayOf(0f,.34f,.72f,1f),Shader.TileMode.CLAMP)
    private val idleFill=LinearGradient(64f,46f,174f,200f,
        intArrayOf(0xFF3478F6.toInt(),0xFF1647B5.toInt(),0xFF12337D.toInt()),null,Shader.TileMode.CLAMP)
    private val rim=LinearGradient(42f,45f,195f,195f,
        intArrayOf(0xFFE2FFFF.toInt(),0xFF21D5FF.toInt(),0xFF1260DC.toInt(),0xFF64F0FF.toInt()),null,Shader.TileMode.CLAMP)
    private val gloss=LinearGradient(120f,44f,120f,122f,
        intArrayOf(0x65FFFFFF,0x0AFFFFFF,0x00FFFFFF),null,Shader.TileMode.CLAMP)
    init { background=null; isClickable=true }
    fun setForegroundVisible(value:Boolean){ foreground=value;invalidate() }
    fun setConnectionState(active:Boolean,transition:Boolean){
        if(connected!=active || changing!=transition){
            connected=active;changing=transition;animationStart=SystemClock.elapsedRealtime();invalidate()
        }
    }
    private fun motionEnabled()=foreground && isShown && (Build.VERSION.SDK_INT<26 || ValueAnimator.areAnimatorsEnabled())
    private fun alpha(color:Int,opacity:Int)=Color.argb(opacity.coerceIn(0,255),Color.red(color),Color.green(color),Color.blue(color))
    private fun circle(c:Canvas,r:Float,color:Int,stroke:Float=0f){
        paint.shader=null;paint.color=color;paint.strokeWidth=stroke
        paint.style=if(stroke>0f)Paint.Style.STROKE else Paint.Style.FILL
        c.drawCircle(center,center,r,paint)
    }
    private fun arc(c:Canvas,r:Float,start:Float,sweep:Float,color:Int,width:Float){
        rect.set(center-r,center-r,center+r,center+r)
        paint.shader=null;paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=width;paint.strokeCap=Paint.Cap.ROUND
        c.drawArc(rect,start,sweep,false,paint)
    }
    private fun dot(c:Canvas,r:Float,angle:Float){
        val rad=Math.toRadians(angle.toDouble())
        val x=center+r*cos(rad).toFloat();val y=center+r*sin(rad).toFloat()
        paint.shader=null;paint.style=Paint.Style.FILL
        paint.color=alpha(cyan,28);c.drawCircle(x,y,8f,paint)
        paint.color=alpha(cyan,90);c.drawCircle(x,y,4.5f,paint)
        paint.color=white;c.drawCircle(x,y,2.2f,paint)
    }
    override fun onDraw(c:Canvas){
        val size=minOf(width,height).toFloat()
        if(size<=0f)return
        val moving=motionEnabled() && (connected||changing)
        val elapsed=if(moving)(SystemClock.elapsedRealtime()-animationStart).coerceAtLeast(0L) else 0L
        val phase=(elapsed%3600L)/3600f
        c.save()
        c.translate((width-size)/2f,(height-size)/2f);c.scale(size/240f,size/240f)
        if(connected || changing){
            paint.style=Paint.Style.FILL;paint.color=Color.WHITE;paint.shader=halo
            c.drawCircle(center,center,118f,paint);paint.shader=null
            for(i in 0..2){
                val wave=(phase+i/3f)%1f
                circle(c,80f+wave*36f,alpha(cyan,((1f-wave)*82).toInt()),1.2f)
            }
            circle(c,89f,alpha(cyan,70),1f);circle(c,105f,alpha(cyan,50),.8f)
            val angle=phase*360f
            arc(c,89f,angle-88f,48f,alpha(cyan,35),7f)
            arc(c,89f,angle-88f,48f,alpha(cyan,215),1.6f)
            arc(c,105f,-angle+48f,64f,alpha(cyan,28),7f)
            arc(c,105f,-angle+48f,64f,alpha(cyan,210),1.4f)
            dot(c,89f,angle-40f);dot(c,105f,-angle+112f)
            dot(c,105f,-angle+292f)
            circle(c,79f,alpha(cyan,45),9f)
        }
        circle(c,radius+2f,0xFF0A214B.toInt())
        paint.color=Color.WHITE;paint.style=Paint.Style.FILL;paint.shader=if(connected)activeFill else idleFill
        c.drawCircle(center,center,radius,paint)
        paint.shader=gloss
        c.drawOval(50f,48f,190f,119f,paint)
        paint.shader=if(connected)rim else null;paint.color=if(connected)Color.WHITE else 0xFF588AE2.toInt()
        paint.style=Paint.Style.STROKE;paint.strokeWidth=if(connected)3.7f else 1.8f
        c.drawCircle(center,center,radius-1.7f,paint)
        paint.shader=null
        if(connected){
            circle(c,radius-6f,alpha(cyan,115),1.1f)
            arc(c,31f,-47f,274f,alpha(cyan,45),13f)
        }
        // Upright power glyph: open arc and center bar.
        arc(c,25f,-50f,280f,white,6f)
        paint.color=white;paint.strokeWidth=6f;paint.strokeCap=Paint.Cap.ROUND
        c.drawLine(center,center-37f,center,center-10f,paint)
        if(isPressed)circle(c,radius,0x18FFFFFF)
        c.restore()
        if(moving)postInvalidateDelayed(33L)
    }
}
