package com.v2ray.ang.delta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import android.util.TypedValue

/** Compact, direction-stable pill matching the approved dashboard mockup. */
class DeltaTrafficView(context: Context, private val english: Boolean) : LinearLayout(context) {
    private val downColor = Color.rgb(55, 237, 168)
    private val upColor = Color.rgb(55, 200, 255)
    private val down: TextView
    private val up: TextView
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

    init {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(downColor, Color.rgb(66, 111, 211), upColor)).apply {
            cornerRadius = dp(40).toFloat()
        }
        setPadding(dp(1), dp(1), dp(1), dp(1))
        val inner = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(20, 44, 57), Color.rgb(20, 31, 54), Color.rgb(19, 38, 65))).apply {
                cornerRadius = dp(39).toFloat()
            }
        }
        addView(inner, LayoutParams(-1, -1))
        down = addRate(inner, false, downColor)
        inner.addView(View(context).apply {
            setBackgroundColor(0x665C7495)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(1), dp(27)))
        up = addRate(inner, true, upColor)
        show(0, 0)
    }

    private fun addRate(parent: LinearLayout, upload: Boolean, accent: Int): TextView {
        val half = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        parent.addView(half, LayoutParams(0, -1, 1f))
        half.addView(TrafficArrow(context, upload, accent), LayoutParams(dp(25), dp(25)))
        return TextView(context).apply {
            text = "0 B/s"
            setTextColor(Color.rgb(239, 245, 255))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setSingleLine(true)
            includeFontPadding = false
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 10, 16, 1, TypedValue.COMPLEX_UNIT_SP)
            half.addView(this, LayoutParams(0, -1, 1f).apply { leftMargin = dp(5) })
        }
    }

    fun show(download: Long, upload: Long) {
        fun setValue(view: TextView, value: Long, label: String) {
            val next = DeltaTraffic.format(value)
            if (view.text.toString() != next) view.text = next
            val desc = "$label: $next"
            if (view.contentDescription != desc) view.contentDescription = desc
        }
        setValue(down, download, if (english) "Download speed" else "سرعت دانلود")
        setValue(up, upload, if (english) "Upload speed" else "سرعت آپلود")
    }
}

private class TrafficArrow(context: Context, private val upload: Boolean, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24f * scale) / 2f, (height - 24f * scale) / 2f)
        canvas.scale(scale, scale)
        paint.strokeWidth = 2.25f
        paint.color = Color.rgb(235, 247, 255)
        val tip = if (upload) 3f else 14f
        val tail = if (upload) 14f else 3f
        val wing = if (upload) 8f else 9f
        canvas.drawLine(12f, tail, 12f, tip, paint)
        canvas.drawPath(Path().apply { moveTo(7f, wing); lineTo(12f, tip); lineTo(17f, wing) }, paint)
        paint.color = accent
        canvas.drawPath(Path().apply {
            moveTo(3f, 15f); lineTo(3f, 18f); quadTo(3f, 21f, 6f, 21f)
            lineTo(18f, 21f); quadTo(21f, 21f, 21f, 18f); lineTo(21f, 15f)
        }, paint)
        canvas.restore()
    }
}
