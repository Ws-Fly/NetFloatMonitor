package com.example.netfloatmonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 信号格 View
 *
 * 两种模式：
 * - 正常模式（默认）：水平排列的信号条 + 底部标签文字
 * - 圆形模式 (setCircularMode)：圆形背景 + 居中 5 根细信号条
 *
 * 断链：DISCONNECTED → 显示红色 ✕
 *
 * 点击/长按弹窗：由外部调用 showPopup(...) 触发
 *   （悬浮窗内不能直接弹 PopupWindow，需通过 Activity 的 WindowManager）
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 模式 ──
    private var circularMode = false

    // ── 正常模式尺寸 ──
    private val barCount = 5
    private val barWidth = 16f
    private val barGap = 5f
    private val maxBarHeight = 48f
    private val minBarHeight = 12f
    private val cornerRadius = 3f
    private val labelTextSize = 14f

    // ── 圆形模式尺寸（更紧凑、5 格清晰可见） ──
    private val cBarWidth = 4f      // 每根条 4dp 宽（细）
    private val cBarGap = 3f        // 间距 3dp
    private val cMaxBarH = 22f      // 最高条 22dp
    private val cMinBarH = 8f       // 最低条 8dp
    private val cBottomMargin = 16f // 底部留白给"总览"文字

    // ── 状态 ──
    private var quality: SignalQuality = SignalQuality.BAD
    private var label: String = ""

    // ── 弹窗数据 ──
    private var detailLine1: String = "--"
    private var detailLine2: String = "--"
    private var detailLine3: String = "--"

    // ── 画笔 ──
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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

    private val cBarWidthPx = cBarWidth * density
    private val cBarGapPx = cBarGap * density
    private val cMaxBarHPx = cMaxBarH * density
    private val cMinBarHPx = cMinBarH * density
    private val cBottomMarginPx = cBottomMargin * density

    init {
        paintLabel.textSize = labelTextPx
        isClickable = true
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

    fun setDetailValues(line1: String, line2: String, line3: String) {
        detailLine1 = line1
        detailLine2 = line2
        detailLine3 = line3
    }

    /**
     * 供外部（FloatView / Activity）在确认弹窗能正常显示的环境下调用
     */
    fun showDetailPopup() {
        try {
            val ctx = context
            val popupView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                val bg = GradientDrawable().apply {
                    setColor(Color.argb(230, 30, 30, 30))
                    cornerRadius = 12f * density
                }
                background = bg

                addView(TextView(ctx).apply {
                    text = if (label.isNotEmpty()) "📶 $label 信号详情" else "📶 信号详情"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setPadding(0, 0, 0, 12)
                })
                addView(View(ctx).apply {
                    setBackgroundColor(Color.parseColor("#44FFFFFF"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 2
                    ).apply { setMargins(0, 0, 0, 10) }
                })
                addView(TextView(ctx).apply {
                    text = detailLine1; setTextColor(Color.parseColor("#FFBDC3C7")); textSize = 13f
                    setPadding(0, 4, 0, 4)
                })
                addView(TextView(ctx).apply {
                    text = detailLine2; setTextColor(Color.parseColor("#FFBDC3C7")); textSize = 13f
                    setPadding(0, 4, 0, 4)
                })
                addView(TextView(ctx).apply {
                    text = detailLine3; setTextColor(Color.parseColor(quality.colorHex)); textSize = 13f
                    setPadding(0, 10, 0, 0)
                })
            }

            val popup = PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
            )
            popup.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            popup.isOutsideTouchable = true
            popup.isFocusable = true

            popup.showAsDropDown(this, -(popupView.measuredWidth - width) / 2, -height - 20)
        } catch (e: Exception) {
            // 悬浮窗内弹窗可能失败，忽略
        }
    }

    // ═════════════════════════════════════════
    // 测量 & 绘制
    // ═════════════════════════════════════════

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (circularMode) {
            // 正方形，直径由外部 params 决定（默认 100dp）
            val desired = (100 * density).toInt()
            setMeasuredDimension(
                resolveSize(desired, widthSpec),
                resolveSize(desired, heightSpec)
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
        if (circularMode) drawCircularMode(canvas) else drawNormalMode(canvas)
    }

    // ── 正常模式 ──
    private fun drawNormalMode(canvas: Canvas) {
        val bars = quality.bars
        val fillColor = Color.parseColor(quality.colorHex)
        val totalW = barCount * barWidthPx + (barCount - 1) * barGapPx
        val startX = (width - totalW) / 2f
        val baseY = height - labelTextPx - 8f * density

        if (quality.isDisconnected) {
            for (i in 0 until barCount) {
                val ratio = (i + 1) / barCount.toFloat()
                val h = minBarHeightPx + (maxBarHeightPx - minBarHeightPx) * ratio
                val x = startX + i * (barWidthPx + barGapPx)
                val top = baseY - h
                paintFill.color = Color.parseColor("#55E74C3C")
                canvas.drawRoundRect(RectF(x, top, x + barWidthPx, baseY), cornerPx, cornerPx, paintFill)
            }
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
                paintEmpty.color = Color.parseColor("#55333333")
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paintEmpty)
            }
        }
        if (label.isNotEmpty()) {
            paintLabel.color = Color.WHITE
            canvas.drawText(label, width / 2f, height - 2f * density, paintLabel)
        }
    }

    // ── 圆形模式（紧凑 5 格） ──
    private fun drawCircularMode(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = Math.min(width, height) / 2f - 3f * density

        // 圆形背景
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
            paintX.textSize = radius * 0.85f
            canvas.drawText("✕", cx, cy + paintX.textSize * 0.35f, paintX)
            return
        }

        // ── 5 根细信号条，居中，底部留出空间给文字 ──
        val bars = quality.bars
        val totalW = barCount * cBarWidthPx + (barCount - 1) * cBarGapPx
        val startX = (width - totalW) / 2f
        // 条形的基准线：圆的中心偏下一点，给底部文字留位
        val baseY = cy + cMaxBarHPx * 0.25f

        for (i in 0 until barCount) {
            val ratio = (i + 1) / barCount.toFloat()
            val h = cMinBarHPx + (cMaxBarHPx - cMinBarHPx) * ratio
            val x = startX + i * (cBarWidthPx + cBarGapPx)
            val top = baseY - h
            val rect = RectF(x, top, x + cBarWidthPx, baseY)
            val cCorner = 1.5f * density

            if (i < bars) {
                // 填充色：满格白色，其余用信号色
                paintFill.color = if (bars >= 5) Color.WHITE else Color.parseColor(quality.colorHex)
                canvas.drawRoundRect(rect, cCorner, cCorner, paintFill)
            } else {
                paintEmpty.color = Color.parseColor("#44FFFFFF")
                canvas.drawRoundRect(rect, cCorner, cCorner, paintEmpty)
            }
        }

        // 底部文字（"总览" + 格数）
        if (label.isNotEmpty()) {
            paintLabel.color = Color.WHITE
            paintLabel.textSize = 10f * density
            canvas.drawText("$label ${quality.bars}/5", cx, height - 4f * density, paintLabel)
        }
    }
}
