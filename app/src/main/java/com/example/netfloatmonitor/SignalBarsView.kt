package com.example.netfloatmonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var quality = SignalQuality.DISCONNECTED
    private var circular = false
    private var labelText = ""

    fun setSignalQuality(q: SignalQuality) {
        quality = q
        invalidate()
    }

    fun setCircularMode(c: Boolean) {
        circular = c
        invalidate()
    }

    fun setLabel(text: String) {
        labelText = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val color = when (quality) {
            SignalQuality.EXCELLENT -> Color.parseColor("#00FF99")
            SignalQuality.GOOD -> Color.parseColor("#66FF33")
            SignalQuality.FAIR -> Color.parseColor("#FFCC00")
            SignalQuality.POOR -> Color.parseColor("#FF6600")
            SignalQuality.BAD -> Color.parseColor("#FF0000")
            SignalQuality.DISCONNECTED -> Color.RED
        }

        paint.color = color

        if (circular) {
            // 圆形背景
            canvas.drawCircle(w / 2f, h / 2f, w / 2f * 0.85f, paint)

            if (quality == SignalQuality.DISCONNECTED) {
                // 画白色 X
                paint.color = Color.WHITE
                paint.strokeWidth = 6f
                paint.style = Paint.Style.STROKE
                val s = w * 0.35f
                canvas.drawLine(w / 2f - s, h / 2f - s, w / 2f + s, h / 2f + s, paint)
                canvas.drawLine(w / 2f + s, h / 2f - s, w / 2f - s, h / 2f + s, paint)
                paint.style = Paint.Style.FILL
            }

            // 底部标签
            if (labelText.isNotEmpty()) {
                paint.color = Color.WHITE
                paint.textSize = 12f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(labelText, w / 2f, h - 4f, paint)
            }
        } else {
            // 矩形信号条
            val barW = w / 6f
            for (i in 0..4) {
                val left = i * (barW + 4f)
                val top = h * (1f - (i + 1) / 5f)
                canvas.drawRect(left, top, left + barW, h, paint)
            }

            // 标签
            if (labelText.isNotEmpty()) {
                paint.color = Color.WHITE
                paint.textSize = 13f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(labelText, w / 2f, h - 4f, paint)
            }
        }
    }
}
