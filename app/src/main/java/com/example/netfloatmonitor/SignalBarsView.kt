package com.example.netfloatmonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.drawable.GradientDrawable

/**
 * 信号格 View
 *
 * 两种模式：
 * - 正常模式（默认）：水平排列的信号条 + 底部标签文字
 * - 圆形模式 (setCircularMode(true))：圆形背景 + 居中信号条（用于折叠态悬浮窗）
 *
 * 特殊状态：
 * - DISCONNECTED → 显示红色 ✕
 *
 * 点击弹窗：
 * - 调用 setDetailPopup(...) 设置弹窗内容
 * - 点击后在弹窗中显示完整 RSSI1/RSSI2/SNR 数值
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 模式 ──
    private var circularMode = false

    // ── 尺寸参数（正常模式） ──
    private val barCount = 5
    private val barWidth = 16f
    private val barGap = 5f
    private val maxBarHeight = 48f
    private val minBarHeight = 12f
    private val cornerRadius = 3f
    private val labelTextSize = 14f

    // ── 尺寸参数（圆形模式） ──
    private val circleBarWidth = 10f
    private val circleBarGap = 3f
    private val circleMaxBarH = 36f
    private val circleMinBarH = 10f
    private val circlePadding = 10f

    // ── 状态 ──
    private var quality: SignalQuality = SignalQuality.BAD
    private var label: String = ""

    // ── 弹窗数据 ──
    private var detailRssi1: String = "--"
    private var detailRssi2: String = "--"
    private var detailSnr: String = "--"

    // ── 画笔 ──
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#55333333")
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val paintX = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintCircleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── 密度 ──
    private val density = resources.displayMetrics.density
    private val barWidthPx = barWidth * density
    private val barGapPx = barGap * density
    private val maxBarHeightPx = maxBarHeight * density
    private val minBarHeightPx = minBarHeight * density
    private val cornerPx = cornerRadius * density
    private val labelTextPx = labelTextSize * density

    private val cBarWidthPx = circleBarWidth * density
    private val cBarGapPx = circleBarGap * density
    private val cMaxBarHPx = circleMaxBarH * density
    private val cMinBarHPx = circleMinBarH * density
    private val cPadPx = circlePadding * density

    init {
        paintLabel.textSize = labelTextPx
        isClickable = true
        setOnClickListener { showDetailPopup() }
    }

    // ═════════════════════════════════════════
    // 对外接口
    // ═════════════════════════════════════════

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

    fun setCircularMode(enabled: Boolean) {
        circularMode = enabled
        requestLayout()
        invalidate()
    }

    /**
     * 设置弹窗显示的详细数值
     */
    fun setDetailValues(rssi1: String, rssi2: String, snr: String) {
        detailRssi1 = rssi1
        detailRssi2 = rssi2
        detailSnr = snr
    }

    // ═════════════════════════════════════════
    // 测量 & 绘制
    // ═════════════════════════════════════════

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (circularMode) {
            // 圆形模式：正方形，边长 = 信号条总宽 + 两倍 padding
            val barTotalW = barCount * cBarWidthPx + (barCount - 1) * cBarGapPx
            val side = (barTotalW + 2 * cPadPx).toInt() + paddingLeft + paddingRight
            val diameter = Math.max(side, (64 * density).toInt()) // 最小 64dp
            setMeasuredDimension(
                resolveSize(diameter, widthSpec),
                resolveSize(diameter, heightSpec)
            )
        } else {
            val desiredW = ((barWidthPx + barGapPx) * barCount + barGapPx).toInt() + paddingLeft + paddingRight
            val desiredH = (maxBarHeightPx + labelTextPx + 10f * density).toInt() + paddingTop + paddingBottom
            setMeasuredDimension(
                resolveSize(desiredW, widthSpec),
                resolveSize(desiredH, heightSpec)
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (circularMode) {
            drawCircularMode(canvas)
        } else {
            drawNormalMode(canvas)
        }
    }

    // ── 正常模式：水平信号条 + 底部标签 ──
    private fun drawNormalMode(canvas: Canvas) {
        val bars = quality.bars
        val fillColor = Color.parseColor(quality.colorHex)

        val totalW = barCount * barWidthPx + (barCount - 1) * barGapPx
        val startX = (width - totalW) / 2f
        val baseY = height - labelTextPx - 8f * density

        // DISCONNECTED → 画整条红色空条 + 底部写"断链"
        if (quality.isDisconnected) {
            for (i in 0 until barCount) {
                val ratio = (i + 1) / barCount.toFloat()
                val h = minBarHeightPx + (maxBarHeightPx - minBarHeightPx) * ratio
                val x = startX + i * (barWidthPx + barGapPx)
                val top = baseY - h
                val rect = RectF(x, top, x + barWidthPx, baseY)
                paintFill.color = Color.parseColor("#55E74C3C")
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paintFill)
            }
            // 大 X
            paintX.color = Color.parseColor("#FFE74C3C")
            paintX.textSize = 36f * density
            canvas.drawText("✕", width / 2f, baseY - maxBarHeightPx * 0.6f, paintX)
            paintLabel.color = Color.parseColor("#FFE74C3C")
            canvas.drawText("断链", width / 2f, height - 2f * density, paintLabel)
            return
        }

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
            paintLabel.color = Color.WHITE
            val labelY = height - 2f * density
            canvas.drawText(label, width / 2f, labelY, paintLabel)
        }
    }

    // ── 圆形模式：圆形背景 + 居中信号条 ──
    private fun drawCircularMode(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = Math.min(width, height) / 2f - 2f * density

        // 圆形背景（按信号等级着色，透明度高一点让信号条突出）
        val bgColor = when {
            quality.isDisconnected -> Color.parseColor("#CCE74C3C")
            quality.bars >= 4 -> Color.parseColor("#CC1ABC9C")
            quality.bars >= 3 -> Color.parseColor("#CCF39C12")
            quality.bars >= 2 -> Color.parseColor("#CCE67E22")
            else -> Color.parseColor("#CCE74C3C")
        }
        paintCircleBg.color = bgColor
        canvas.drawCircle(cx, cy, radius, paintCircleBg)

        if (quality.isDisconnected) {
            // 大 X
            paintX.color = Color.WHITE
            paintX.textSize = radius * 0.9f
            canvas.drawText("✕", cx, cy + paintX.textSize * 0.35f, paintX)
            return
        }

        // 居中画信号条
        val bars = quality.bars
        val fillColor = Color.parseColor(quality.colorHex)
        // 提亮以便深色背景上也清晰
        val brightFill = if (quality.bars >= 4) Color.WHITE else fillColor

        val totalW = barCount * cBarWidthPx + (barCount - 1) * cBarGapPx
        val startX = (width - totalW) / 2f
        val baseY = cy + cMaxBarHPx * 0.45f

        for (i in 0 until barCount) {
            val ratio = (i + 1) / barCount.toFloat()
            val h = cMinBarHPx + (cMaxBarHPx - cMinBarHPx) * ratio
            val x = startX + i * (cBarWidthPx + cBarGapPx)
            val top = baseY - h
            val rect = RectF(x, top, x + cBarWidthPx, baseY)
            val cCorner = 2f * density

            if (i < bars) {
                paintFill.color = brightFill
                canvas.drawRoundRect(rect, cCorner, cCorner, paintFill)
            } else {
                paintEmpty.color = Color.parseColor("#55FFFFFF")
                canvas.drawRoundRect(rect, cCorner, cCorner, paintEmpty)
            }
        }
    }

    // ═════════════════════════════════════════
    // 点击弹窗
    // ═════════════════════════════════════════

    private fun showDetailPopup() {
        val popupView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)

            val bg = GradientDrawable().apply {
                setColor(Color.argb(220, 30, 30, 30))
                cornerRadius = 12f * density
            }
            background = bg

            // 标题
            addView(TextView(context).apply {
                text = if (label.isNotEmpty()) "📶 $label 信号详情" else "📶 信号详情"
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(0, 0, 0, 12)
            })

            // 分隔线
            addView(View(context).apply {
                setBackgroundColor(Color.parseColor("#44FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 2
                ).apply { setMargins(0, 0, 0, 10) }
            })

            // 数据行
            addView(makeDetailRow("RSSI1", detailRssi1))
            addView(makeDetailRow("RSSI2", detailRssi2))
            addView(makeDetailRow("SNR", detailSnr))

            // 信号等级
            addView(TextView(context).apply {
                text = "信号等级: ${quality.label} (${quality.bars}格)"
                setTextColor(Color.parseColor(quality.colorHex))
                textSize = 13f
                setPadding(0, 10, 0, 0)
            })
        }

        val popup = PopupWindow(
            popupView,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        })
        popup.isOutsideTouchable = true
        popup.isFocusable = true

        // 在 View 上方居中显示
        val xOff = -(popupView.measuredWidth - width) / 2
        popup.showAsDropDown(this, xOff, -height - 20)
    }

    private fun makeDetailRow(key: String, value: String): TextView {
        return TextView(context).apply {
            text = "$key : $value"
            setTextColor(Color.parseColor("#FFBDC3C7"))
            textSize = 13f
            setPadding(0, 4, 0, 4)
        }
    }
}
