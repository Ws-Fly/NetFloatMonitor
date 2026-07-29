package com.example.netfloatmonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper

class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 5
        private const val MIN_BAR_HEIGHT_DP = 8f
        private const val MAX_BAR_HEIGHT_DP = 22f
        private const val BAR_WIDTH_DP = 4f
        private const val SPACING_DP = 3f
        private const val BOTTOM_MARGIN_DP = 16f
        private const val CIRCLE_DIAMETER_DP = 100f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }

    private val barHeights = FloatArray(BAR_COUNT) { MIN_BAR_HEIGHT_DP }
    private val barColors = IntArray(BAR_COUNT) { Color.GRAY }
    private var level = 0f // 0.0 ~ 1.0
    private var isCircular = false
    private var labelText = ""
    private var signalQuality: SignalQuality = SignalQuality.DISCONNECTED

    private val gestureHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var isLongPressTriggered = false
    private var touchStartX = 0f
    private var touchStartY = 0f

    init {
        // 默认初始化为灰色
        updateColors()
    }

    fun setLevel(level: Float) {
        this.level = level.coerceIn(0f, 1f)
        calculateBarHeights()
        updateColors()
        invalidate()
    }

    fun setSignalQuality(quality: SignalQuality) {
        this.signalQuality = quality
        updateColors()
        invalidate()
    }

    fun setCircularMode(isCircular: Boolean) {
        this.isCircular = isCircular
        requestLayout()
        invalidate()
    }

    fun setLabel(label: String) {
        this.labelText = label
        invalidate()
    }

    private fun calculateBarHeights() {
        val totalHeight = MAX_BAR_HEIGHT_DP - MIN_BAR_HEIGHT_DP
        for (i in 0 until BAR_COUNT) {
            val ratio = i.toFloat() / (BAR_COUNT - 1)
            barHeights[i] = MIN_BAR_HEIGHT_DP + totalHeight * (level * ratio).coerceIn(0f, 1f)
        }
    }

    private fun updateColors() {
        val color = when (signalQuality) {
            SignalQuality.EXCELLENT -> Color.parseColor("#00FF99") // 青绿
            SignalQuality.GOOD -> Color.parseColor("#66FF33")     // 绿
            SignalQuality.FAIR -> Color.parseColor("#FFCC00")     // 橙黄
            SignalQuality.POOR -> Color.parseColor("#FF6600")     // 深橙
            SignalQuality.BAD -> Color.parseColor("#FF0000")      // 红
            SignalQuality.DISCONNECTED -> Color.WHITE             // 白（用于 X）
        }

        for (i in 0 until BAR_COUNT) {
            barColors[i] = color
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val diameter = if (isCircular) {
            (CIRCLE_DIAMETER_DP * resources.displayMetrics.density).toInt()
        } else {
            (200 * resources.displayMetrics.density).toInt()
        }
        setMeasuredDimension(diameter, diameter)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = if (isCircular) width / 2f * 0.85f else width / 2f * 0.7f

        if (isCircular) {
            drawCircularBars(canvas, centerX, centerY, radius)
            drawLabel(canvas, centerX, centerY + radius + 10)
            if (signalQuality == SignalQuality.DISCONNECTED) {
                drawDisconnectedX(canvas, centerX, centerY)
            }
        } else {
            drawRectangularBars(canvas, centerX, centerY)
            drawLabel(canvas, centerX, centerY + radius + 10)
        }
    }

    private fun drawCircularBars(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val barWidthPx = (BAR_WIDTH_DP * resources.displayMetrics.density)
        val spacingPx = (SPACING_DP * resources.displayMetrics.density)
        val totalWidth = BAR_COUNT * barWidthPx + (BAR_COUNT - 1) * spacingPx
        val startX = centerX - totalWidth / 2

        for (i in 0 until BAR_COUNT) {
            val left = startX + i * (barWidthPx + spacingPx)
            val top = centerY - barHeights[i] * resources.displayMetrics.density
            val right = left + barWidthPx
            val bottom = centerY

            val rect = RectF(left, top, right, bottom)
            paint.color = barColors[i]
            canvas.drawRect(rect, paint)
        }
    }

    private fun drawRectangularBars(canvas: Canvas, centerX: Float, centerY: Float) {
        val barWidthPx = (BAR_WIDTH_DP * resources.displayMetrics.density)
        val spacingPx = (SPACING_DP * resources.displayMetrics.density)
        val totalWidth = BAR_COUNT * barWidthPx + (BAR_COUNT - 1) * spacingPx
        val startX = centerX - totalWidth / 2
        val baseY = centerY + (MAX_BAR_HEIGHT_DP * resources.displayMetrics.density)

        for (i in 0 until BAR_COUNT) {
            val left = startX + i * (barWidthPx + spacingPx)
            val top = baseY - barHeights[i] * resources.displayMetrics.density
            val right = left + barWidthPx
            val bottom = baseY

            val rect = RectF(left, top, right, bottom)
            paint.color = barColors[i]
            canvas.drawRect(rect, paint)
        }
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float) {
        if (labelText.isNotEmpty()) {
            paint.apply {
                color = Color.WHITE
                textSize = 12f * resources.displayMetrics.scaledDensity
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(labelText, x, y, paint)
        }
    }

    private fun drawDisconnectedX(canvas: Canvas, centerX: Float, centerY: Float) {
        paint.apply {
            color = Color.WHITE
            strokeWidth = 4f * resources.displayMetrics.density
            style = Paint.Style.STROKE
        }

        val size = radius * 0.6f
        canvas.drawLine(
            centerX - size, centerY - size,
            centerX + size, centerY + size,
            paint
        )
        canvas.drawLine(
            centerX + size, centerY - size,
            centerX - size, centerY + size,
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                isLongPressTriggered = false
                longPressRunnable = Runnable {
                    isLongPressTriggered = true
                    showDetailPopup()
                }
                gestureHandler.postDelayed(longPressRunnable!!, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.x - touchStartX)
                val dy = Math.abs(event.y - touchStartY)
                if (dx > 6 || dy > 6) {
                    gestureHandler.removeCallbacks(longPressRunnable!!)
                    // 拖动逻辑由 FloatView 处理
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureHandler.removeCallbacks(longPressRunnable!!)
                if (!isLongPressTriggered) {
                    performToggle()
                }
            }
        }
        return true
    }

    private fun showDetailPopup() {
        // 这里应该由 FloatView 显示弹窗，此处仅标记
        // 实际弹窗逻辑在 FloatView 中实现
    }

    private fun performToggle() {
        // 由 FloatView 处理展开/折叠
    }
}
