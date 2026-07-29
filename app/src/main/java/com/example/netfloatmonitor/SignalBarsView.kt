package com.example.netfloatmonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * 信号格 View —— 类似手机状态栏的信号图标
 *
 * 用法：
 *   view.setQuality(SignalQuality.GOOD)
 *   view.setLabel("AIR")
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 5
    private val barWidth = 16f
    private val barGap = 5f
    private val maxBarHeight = 48f
    private val minBarHeight = 12f
    private val cornerRadius = 3f
    private val labelTextSize = 14f

    private var quality: SignalQuality = SignalQuality.BAD
    private var label: String = ""

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#55333333")
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val density = resources.displayMetrics.density
    private val barWidthPx = barWidth * density
    private val barGapPx = barGap * density
    private val maxBarHeightPx = maxBarHeight * density
    private val minBarHeightPx = minBarHeight * density
    private val cornerPx = cornerRadius * density
    private val labelTextPx = labelTextSize * density

    init {
        paintLabel.textSize = labelTextPx
    }

    fun setQuality(q: SignalQuality) {
        quality = q
        invalidate()
    }

    fun setLabel(text: String) {
        label = text
        invalidate()
    }

    fun setQualityAndLabel(q: SignalQuality, text: String) {
        quality = q
        label = text
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val desiredW = ((barWidthPx + barGapPx) * barCount + barGapPx).toInt() + paddingLeft + paddingRight
        val desiredH = (maxBarHeightPx + labelTextPx + 10f * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredW, widthSpec),
            resolveSize(desiredH, heightSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bars = quality.bars
        val fillColor = Color.parseColor(quality.colorHex)

        val totalW = barCount * barWidthPx + (barCount - 1) * barGapPx
        val startX = (width - totalW) / 2f
        val baseY = height - labelTextPx - 8f * density

        for (i in 0 until barCount) {
            val ratio = (i + 1) / barCount.toFloat()
            val h = minBarHeightPx + (maxBarHeightPx - minBarHeightPx) * ratio
            val x = startX + i * (barWidthPx + barGapPx)
            val top = baseY - h
            val rect = RectF(x, top, x + barWidthPx, baseY)

            if (i < bars) {
                paintFill.color = fillColor
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paintFill)
            } else {
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paintEmpty)
            }
        }

        if (label.isNotEmpty()) {
            val labelY = height - 2f * density
            canvas.drawText(label, width / 2f, labelY, paintLabel)
        }
    }
}
