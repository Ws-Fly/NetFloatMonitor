package com.example.netfloatmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.LinkedList

class FloatView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams
) : LinearLayout(context) {

    private val airLayout = LinearLayout(context)
    private val gndLayout = LinearLayout(context)

    // 右侧图表
    private val chartContainer = LinearLayout(context)
    private val airChartView = WaveformView(context, isAir = true)
    private val gndChartView = WaveformView(context, isAir = false)

    // ── 信号格（AIR / GND / 折叠态圆形图标） ──
    private val airSignalBars = SignalBarsView(context)
    private val gndSignalBars = SignalBarsView(context)

    // 折叠态：圆形图标里画信号格
    private val collapsedCircleView = SignalBarsView(context).apply {
        setCircularMode(true)
    }

    private var isExpanded = true
    private var lastExpandedWidth = 1300
    private var lastExpandedHeight = 540
    private val collapsedSize = 96   // 圆形图标 96dp，更紧凑

    private var startWidth = 0
    private var startHeight = 0
    private var downX = 0f
    private var downY = 0f
    private var resize = false

    private val topBar = LinearLayout(context)
    private val contentFrame = FrameLayout(context)
    private val contentPanel = LinearLayout(context)

    private val resizeIndicator = View(context).apply {
        val triangleBg = GradientDrawable().apply {
            setColor(Color.parseColor("#3498DB"))
            cornerRadius = 4f
        }
        background = triangleBg
        visibility = View.VISIBLE
    }

    private val toggleBtn = Button(context).apply {
        text = "×"
        textSize = 14f
        setTextColor(Color.WHITE)
        setGravity(Gravity.CENTER)
        val btnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#C0392B"))
            cornerRadius = 6f
        }
        background = btnBg
    }

    // ── 缓存最新数值（供弹窗和折叠态使用） ──
    private var airRssi1Val: String = "--"
    private var airRssi2Val: String = "--"
    private var airSnrVal: String = "--"
    private var gndRssi1Val: String = "--"
    private var gndRssi2Val: String = "--"
    private var gndSnrVal: String = "--"

    init {
        this.setOrientation(LinearLayout.VERTICAL)
        this.setPadding(8, 6, 8, 8)

        val bg = GradientDrawable()
        bg.setColor(Color.argb(180, 0, 0, 0))
        bg.cornerRadius = 10f
        this.setBackground(bg)

        topBar.setOrientation(LinearLayout.HORIZONTAL)
        topBar.setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        topBar.setPadding(0, 0, 4, 4)

        val btnLp = LinearLayout.LayoutParams(45, 45)
        topBar.addView(toggleBtn, btnLp)
        addView(topBar)

        contentPanel.setOrientation(LinearLayout.HORIZONTAL)
        airLayout.setOrientation(LinearLayout.VERTICAL)
        gndLayout.setOrientation(LinearLayout.VERTICAL)

        // ── AIR 面板顶部插入信号格（带点击弹窗） ──
        airSignalBars.setLabel("AIR")
        val airBarLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 6) }
        airLayout.addView(airSignalBars, airBarLp)

        // ── GND 面板顶部插入信号格 ──
        gndSignalBars.setLabel("GND")
        val gndBarLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 6) }
        gndLayout.addView(gndSignalBars, gndBarLp)

        contentPanel.addView(createPanel(airLayout))
        contentPanel.addView(createPanel(gndLayout))

        chartContainer.setOrientation(LinearLayout.VERTICAL)
        val airChartLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, 0, 0, 8)
        }
        val gndChartLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        chartContainer.addView(airChartView, airChartLp)
        chartContainer.addView(gndChartView, gndChartLp)

        val chartContainerLp = LinearLayout.LayoutParams(700, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            setMargins(12, 0, 4, 0)
        }
        contentPanel.addView(chartContainer, chartContainerLp)

        contentFrame.addView(contentPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val indicatorLp = FrameLayout.LayoutParams(15, 15).apply {
            gravity = Gravity.BOTTOM or Gravity.RIGHT
            setMargins(0, 0, 4, 4)
        }
        contentFrame.addView(resizeIndicator, indicatorLp)

        // ── 折叠态：圆形信号格图标（居中覆盖整个区域） ──
        collapsedCircleView.visibility = View.GONE
        collapsedCircleView.setLabel("")
        val collapsedLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER }
        contentFrame.addView(collapsedCircleView, collapsedLp)

        val frameLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        addView(contentFrame, frameLp)

        // 初始状态
        airSignalBars.setQuality(SignalQuality.BAD)
        gndSignalBars.setQuality(SignalQuality.BAD)
        collapsedCircleView.setQuality(SignalQuality.BAD)

        // ── 折叠按钮触摸逻辑（保留原有） ──
        toggleBtn.setOnTouchListener(object : OnTouchListener {
            private var btnDownX = 0f
            private var btnDownY = 0f
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                if (isExpanded) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        btnDownX = event.rawX
                        btnDownY = event.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - btnDownX
                        val dy = event.rawY - btnDownY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                        if (isDragging) {
                            params.x += (event.rawX - downX).toInt()
                            params.y += (event.rawY - downY).toInt()
                            downX = event.rawX
                            downY = event.rawY
                            val maxAllowableY = getScreenHeight() - getNavigationBarHeight() - height
                            if (params.y > maxAllowableY) params.y = maxAllowableY
                            windowManager.updateViewLayout(this@FloatView, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) performToggle()
                    }
                }
                return true
            }
        })

        toggleBtn.setOnClickListener {
            if (isExpanded) performToggle()
        }

        // ── 整体拖动 / 缩放（保留原有） ──
        setOnTouchListener(object : OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startWidth = width
                        startHeight = height
                        resize = isExpanded && (event.x > (width - 120)) && (event.y > (height - 120))
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val location = IntArray(2)
                        this@FloatView.getLocationOnScreen(location)
                        val absoluteY = location[1]
                        val navBarHeight = getNavigationBarHeight()
                        val usableScreenHeight = getScreenHeight() - navBarHeight

                        if (resize) {
                            val newWidth = (startWidth + event.rawX - downX).toInt().coerceAtLeast(500)
                            var newHeight = (startHeight + event.rawY - downY).toInt().coerceAtLeast(200)
                            if (absoluteY + newHeight > usableScreenHeight) {
                                newHeight = usableScreenHeight - absoluteY
                            }
                            params.width = newWidth
                            params.height = newHeight
                            lastExpandedWidth = newWidth
                            lastExpandedHeight = newHeight
                        } else {
                            params.x += (event.rawX - downX).toInt()
                            var targetY = params.y + (event.rawY - downY).toInt()
                            if (targetY + height > usableScreenHeight) {
                                targetY = usableScreenHeight - height
                            }
                            params.y = targetY
                            downX = event.rawX
                            downY = event.rawY
                        }
                        windowManager.updateViewLayout(this@FloatView, params)
                    }
                }
                return true
            }
        })
    }

    private fun getNavigationBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    private fun getScreenHeight(): Int {
        return context.resources.displayMetrics.heightPixels
    }

    // ── 折叠 / 展开（保留原有逻辑 + 圆形图标适配） ──
    private fun performToggle() {
        val panelBg = GradientDrawable()

        if (isExpanded) {
            // → 折叠：隐藏内容，显示圆形信号格图标
            isExpanded = false
            contentPanel.visibility = View.GONE
            resizeIndicator.visibility = View.GONE
            collapsedCircleView.visibility = View.VISIBLE

            val collapsedLp2 = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            toggleBtn.layoutParams = collapsedLp2
            toggleBtn.text = ""
            toggleBtn.textSize = 1f
            val btnBg = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 80f
            }
            toggleBtn.background = btnBg

            panelBg.setColor(Color.TRANSPARENT)
            this.setBackground(panelBg)
            this.setPadding(0, 0, 0, 0)

            params.width = collapsedSize
            params.height = collapsedSize
        } else {
            // → 展开：恢复内容面板
            isExpanded = true
            contentPanel.visibility = View.VISIBLE
            resizeIndicator.visibility = View.VISIBLE
            collapsedCircleView.visibility = View.GONE

            val expandedLp = LinearLayout.LayoutParams(45, 45)
            toggleBtn.layoutParams = expandedLp
            toggleBtn.text = "×"
            toggleBtn.textSize = 14f
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#C0392B"))
                cornerRadius = 6f
            }
            toggleBtn.background = btnBg

            panelBg.setColor(Color.argb(180, 0, 0, 0))
            panelBg.cornerRadius = 10f
            this.setBackground(panelBg)
            this.setPadding(8, 6, 8, 8)

            params.width = lastExpandedWidth
            params.height = lastExpandedHeight
        }
        windowManager.updateViewLayout(this@FloatView, params)
    }

    private fun createPanel(containerLayout: LinearLayout): View {
        val box = LinearLayout(context)
        box.setOrientation(LinearLayout.VERTICAL)

        val scroll = ScrollView(context)
        scroll.addView(containerLayout)

        val lp = LinearLayout.LayoutParams(300, LinearLayout.LayoutParams.MATCH_PARENT)
        box.addView(scroll, lp)

        return box
    }

    // ── JSON 数据刷新（核心） ──
    fun updateJson(json: String) {
        try {
            if (json.isBlank()) return
            val obj = JSONObject(json)

            airLayout.removeAllViews()
            gndLayout.removeAllViews()

            // 重新插入信号格
            airSignalBars.setLabel("AIR")
            gndSignalBars.setLabel("GND")
            val airBarLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) }
            val gndBarLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) }
            airLayout.addView(airSignalBars, airBarLp)
            gndLayout.addView(gndSignalBars, gndBarLp)

            var airRssi1: Float? = null
            var airRssi2: Float? = null
            var airSnr: Float? = null
            var gndRssi1: Float? = null
            var gndRssi2: Float? = null
            var gndSnr: Float? = null

            // 原始字符串（用于断链判定）
            var airRssi1Str = ""
            var airRssi2Str = ""
            var airSnrStr = ""
            var gndRssi1Str = ""
            var gndRssi2Str = ""
            var gndSnrStr = ""

            obj.keys().forEach { key ->
                val valueStr = obj.get(key).toString()
                val lowerKey = key.lowercase()
                val numValue = valueStr.toFloatOrNull()?.let { Math.abs(it) }

                if (numValue != null) {
                    when {
                        lowerKey.endsWith("_a") -> {
                            when {
                                lowerKey.contains("rssi1") -> { airRssi1 = numValue; airRssi1Str = valueStr }
                                lowerKey.contains("rssi2") -> { airRssi2 = numValue; airRssi2Str = valueStr }
                                lowerKey.contains("rssi") && airRssi1 == null -> { airRssi1 = numValue; airRssi1Str = valueStr }
                                lowerKey.contains("snr") -> { airSnr = numValue; airSnrStr = valueStr }
                            }
                        }
                        lowerKey.endsWith("_g") -> {
                            when {
                                lowerKey.contains("rssi1") -> { gndRssi1 = numValue; gndRssi1Str = valueStr }
                                lowerKey.contains("rssi2") -> { gndRssi2 = numValue; gndRssi2Str = valueStr }
                                lowerKey.contains("rssi") && gndRssi1 == null -> { gndRssi1 = numValue; gndRssi1Str = valueStr }
                                lowerKey.contains("snr") -> { gndSnr = numValue; gndSnrStr = valueStr }
                            }
                        }
                        lowerKey.contains("air_rssi1") -> { airRssi1 = numValue; airRssi1Str = valueStr }
                        lowerKey.contains("air_rssi2") -> { airRssi2 = numValue; airRssi2Str = valueStr }
                        lowerKey.contains("air_snr") -> { airSnr = numValue; airSnrStr = valueStr }
                        lowerKey.contains("gnd_rssi1") -> { gndRssi1 = numValue; gndRssi1Str = valueStr }
                        lowerKey.contains("gnd_rssi2") -> { gndRssi2 = numValue; gndRssi2Str = valueStr }
                        lowerKey.contains("gnd_snr") -> { gndSnr = numValue; gndSnrStr = valueStr }
                    }
                }

                when {
                    key.endsWith("_g") -> addItem(gndLayout, key, valueStr)
                    key.endsWith("_a") -> addItem(airLayout, key, valueStr)
                    else -> addItem(airLayout, key, valueStr)
                }
            }

            // ── 断链判定：SNR="0" 或 RSSI="110" ──
            val airDisconnected = airSnrStr == "0" || airRssi1Str == "110" || airRssi2Str == "110"
            val gndDisconnected = gndSnrStr == "0" || gndRssi1Str == "110" || gndRssi2Str == "110"

            // ── 更新信号格 ──
            val airRssi = airRssi1 ?: airRssi2
            val gndRssi = gndRssi1 ?: gndRssi2
            val airQ = if (airDisconnected) SignalQuality.DISCONNECTED
                       else SignalQuality.fromRssiSnr(airRssi, airSnr, hasSnrData = true)
            val gndQ = if (gndDisconnected) SignalQuality.DISCONNECTED
                       else SignalQuality.fromRssiSnr(gndRssi, gndSnr, hasSnrData = true)

            airSignalBars.setQuality(airQ)
            gndSignalBars.setQuality(gndQ)

            // 弹窗数据
            airSignalBars.setDetailValues(
                if (airDisconnected) "断链" else (airRssi1?.toInt()?.toString() ?: "--"),
                if (airDisconnected) "断链" else (airRssi2?.toInt()?.toString() ?: "--"),
                if (airDisconnected) "断链" else (airSnr?.toInt()?.toString() ?: "--")
            )
            gndSignalBars.setDetailValues(
                if (gndDisconnected) "断链" else (gndRssi1?.toInt()?.toString() ?: "--"),
                if (gndDisconnected) "断链" else (gndRssi2?.toInt()?.toString() ?: "--"),
                if (gndDisconnected) "断链" else (gndSnr?.toInt()?.toString() ?: "--")
            )

            // 缓存（供折叠态弹窗用）
            airRssi1Val = airRssi1?.toInt()?.toString() ?: "--"
            airRssi2Val = airRssi2?.toInt()?.toString() ?: "--"
            airSnrVal = airSnr?.toInt()?.toString() ?: "--"
            gndRssi1Val = gndRssi1?.toInt()?.toString() ?: "--"
            gndRssi2Val = gndRssi2?.toInt()?.toString() ?: "--"
            gndSnrVal = gndSnr?.toInt()?.toString() ?: "--"

            // 折叠态圆形图标：取两端较差值
            val overallQ = SignalQuality.worse(airQ, gndQ)
            collapsedCircleView.setQuality(overallQ)
            collapsedCircleView.setLabel("")
            collapsedCircleView.setDetailValues(
                "AIR.rssi1=$airRssi1Val AIR.rssi2=$airRssi2Val AIR.snr=$airSnrVal",
                "GND.rssi1=$gndRssi1Val GND.rssi2=$gndRssi2Val GND.snr=$gndSnrVal",
                overallQ.label
            )

            airChartView.addData(airRssi1, airRssi2, airSnr)
            gndChartView.addData(gndRssi1, gndRssi2, gndSnr)

        } catch (e: Exception) {
            airLayout.removeAllViews()
            gndLayout.removeAllViews()
            addItem(airLayout, "JSON_ERROR", e.message ?: "Unknown Error")
        }
    }

    private fun addItem(layout: LinearLayout, key: String, value: String) {
        val tv = TextView(context)
        tv.text = "$key : $value"
        tv.textSize = 12f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(4, 3, 4, 3)
        layout.addView(tv)
    }

    // ──────────────────────────────────────────────
    // 内嵌波形图（保持不变）
    // ──────────────────────────────────────────────
    private class WaveformView(
        context: Context,
        private val isAir: Boolean = false
    ) : View(context) {
        private val maxDataPoints = 100
        private val yAxisWidth = 85f

        private val rssi1List = LinkedList<Float>()
        private val rssi2List = LinkedList<Float>()
        private val snrList = LinkedList<Float>()

        private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BDC3C7"); textSize = 16f
        }

        private val colorRssi1 = Color.parseColor("#2980B9")
        private val colorRssi2 = Color.parseColor("#3498DB")
        private val colorSnr = Color.parseColor("#2ECC71")

        private val paintRssi1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorRssi1; strokeWidth = 4f; style = Paint.Style.STROKE
        }
        private val paintRssi2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorRssi2; strokeWidth = 3f; style = Paint.Style.STROKE
        }
        private val paintSnr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorSnr; strokeWidth = 3f; style = Paint.Style.STROKE
        }

        private val paintTextRssi1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorRssi1; textSize = 18f
        }
        private val paintTextRssi2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorRssi2; textSize = 18f
        }
        private val paintTextSnr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorSnr; textSize = 18f
        }

        private val gridPaint = Paint().apply {
            color = Color.argb(45, 255, 255, 255); strokeWidth = 1f
        }
        private val bgPaint = Paint().apply {
            color = Color.argb(30, 255, 255, 255)
        }

        private val rssiMin = 0f; private val rssiMax = 120f
        private val snrMin = 0f; private val snrMax = 50f

        fun addData(r1: Float?, r2: Float?, snr: Float?) {
            rssi1List.addLast(r1 ?: rssi1List.lastOrNull() ?: 0f)
            rssi2List.addLast(r2 ?: rssi2List.lastOrNull() ?: 0f)
            snrList.addLast(snr ?: snrList.lastOrNull() ?: 0f)
            if (rssi1List.size > maxDataPoints) rssi1List.removeFirst()
            if (rssi2List.size > maxDataPoints) rssi2List.removeFirst()
            if (snrList.size > maxDataPoints) snrList.removeFirst()
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            canvas.drawRect(0f, 0f, w, h, bgPaint)

            for (i in 0..4) {
                val y = h * i / 4f
                canvas.drawLine(yAxisWidth, y, w, y, gridPaint)
            }

            canvas.drawText("120", 8f, 18f, axisTextPaint)
            canvas.drawText("0", 28f, h - 8f, axisTextPaint)
            canvas.drawText("50", w - 40f, 18f, axisTextPaint)

            var dashY = 28f
            canvas.drawText("rssi1", 50f, dashY, paintTextRssi1); dashY += 22f
            canvas.drawText("rssi2", 50f, dashY, paintTextRssi2); dashY += 22f
            canvas.drawText("snr", 50f, dashY, paintTextSnr)

            val chartW = w - yAxisWidth - 10f
            val chartLeft = yAxisWidth + 5f

            drawLine(canvas, rssi1List, rssiMin, rssiMax, chartLeft, chartW, h, paintRssi1)
            drawLine(canvas, rssi2List, rssiMin, rssiMax, chartLeft, chartW, h, paintRssi2)
            drawLine(canvas, snrList, snrMin, snrMax, chartLeft, chartW, h, paintSnr)
        }

        private fun drawLine(
            canvas: Canvas, list: LinkedList<Float>,
            minVal: Float, maxVal: Float,
            leftOffset: Float, cWidth: Float, h: Float, paint: Paint
        ) {
            if (list.size < 2) return
            val range = maxVal - minVal
            val stepX = if (list.size > 1) cWidth / (maxDataPoints - 1) else 0f

            for (i in 1 until list.size) {
                val v0 = list[i - 1].coerceIn(minVal, maxVal)
                val v1 = list[i].coerceIn(minVal, maxVal)
                val x0 = leftOffset + (i - 1) * stepX
                val x1 = leftOffset + i * stepX
                val y0 = h - ((v0 - minVal) / range) * h
                val y1 = h - ((v1 - minVal) / range) * h
                canvas.drawLine(x0, y0, x1, y1, paint)
            }
        }
    }
}
