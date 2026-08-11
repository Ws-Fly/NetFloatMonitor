package com.example.netfloatmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class FloatView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams
) : LinearLayout(context) {

    private val airLayout = LinearLayout(context)
    private val gndLayout = LinearLayout(context)
    
    private val chartContainer = LinearLayout(context)
    private val airChartView = WaveformView(context, isAir = true)
    private val gndChartView = WaveformView(context, isAir = false)
    
    private val airNoiseChartView = NoiseFloorChartView(context, isAir = true)
    private val gndNoiseChartView = NoiseFloorChartView(context, isAir = false)

    private var isExpanded = true
    private var lastExpandedWidth = 1380
    private var lastExpandedHeight = 650 
    
    private val collapsedWidth = 220
    private val collapsedHeight = 130

    private var startWidth = 0
    private var startHeight = 0
    
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var resize = false

    private val topBar = LinearLayout(context)
    private val contentFrame = FrameLayout(context)
    private val contentPanel = LinearLayout(context)
    
    private val collapsedPanel = LinearLayout(context)
    private val airSignalIconView = SignalIconView(context, "AIR")
    private val gndSignalIconView = SignalIconView(context, "GND")
    
    private val airTextViewMap = HashMap<String, TextView>()
    private val gndTextViewMap = HashMap<String, TextView>()

    private val roleStatusView = TextView(context).apply {
        text = "👤 观察者"
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#3498DB"))
        setPadding(8, 4, 8, 4)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.argb(60, 52, 152, 219))
            cornerRadius = 8f
        }
        visibility = View.GONE
    }
    
    private val isFlashing = AtomicBoolean(false)
    private var flashCount = 0
    private val MAX_FLASH_COUNT = 6

    private val resizeIndicator = View(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#3498DB"))
            cornerRadius = 4f
        }
        visibility = View.VISIBLE
    }

    private val toggleBtn = Button(context).apply {
        text = "×"
        textSize = 14f
        setTextColor(Color.WHITE)
        setGravity(Gravity.CENTER)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#C0392B"))
            cornerRadius = 6f
        }
    }

    private val vibrator: Vibrator? = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    init {
        this.orientation = LinearLayout.VERTICAL
        this.setPadding(12, 8, 12, 12)

        val bg = GradientDrawable()
        bg.setColor(Color.argb(205, 15, 15, 15))
        bg.cornerRadius = 14f
        this.background = bg

        collapsedPanel.orientation = LinearLayout.HORIZONTAL
        collapsedPanel.gravity = Gravity.CENTER
        collapsedPanel.visibility = View.GONE
        
        val iconLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        collapsedPanel.addView(airSignalIconView, iconLp)
        collapsedPanel.addView(gndSignalIconView, iconLp)
        addView(collapsedPanel)

        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        topBar.setPadding(0, 0, 4, 6)
        
        val roleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        roleLp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        topBar.addView(roleStatusView, roleLp)
        
        val btnLp = LinearLayout.LayoutParams(48, 48)
        topBar.addView(toggleBtn, btnLp)
        addView(topBar)

        contentPanel.orientation = LinearLayout.HORIZONTAL
        airLayout.orientation = LinearLayout.VERTICAL
        gndLayout.orientation = LinearLayout.VERTICAL
        
        contentPanel.addView(createPanel("AIR TELEMETRY", airLayout))
        contentPanel.addView(createPanel("GND TELEMETRY", gndLayout))
        
        chartContainer.orientation = LinearLayout.VERTICAL
        val subChartLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, 0, 0, 6) }
        val lastChartLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        
        chartContainer.addView(airChartView, subChartLp)
        chartContainer.addView(gndChartView, subChartLp)
        chartContainer.addView(airNoiseChartView, subChartLp)
        chartContainer.addView(gndNoiseChartView, lastChartLp)
        
        val chartContainerLp = LinearLayout.LayoutParams(750, LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(16, 0, 4, 0) }
        contentPanel.addView(chartContainer, chartContainerLp)
        
        contentFrame.addView(contentPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(resizeIndicator, FrameLayout.LayoutParams(18, 18).apply { gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, 2, 2) })
        addView(contentFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        toggleBtn.setOnClickListener {
            if (isExpanded) performToggle()
        }

        setOnTouchListener(object : OnTouchListener {
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                if (!isAttachedToWindow) return false
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        lastX = event.rawX
                        lastY = event.rawY
                        startWidth = width
                        startHeight = height
                        resize = isExpanded && (event.x > (width - 120)) && (event.y > (height - 120))
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isExpanded && resize) {
                            val totalDx = event.rawX - downX
                            val totalDy = event.rawY - downY
                            
                            val newWidth = (startWidth + totalDx).toInt().coerceAtLeast(650)
                            val newHeight = (startHeight + totalDy).toInt().coerceAtLeast(350)
                            
                            params.width = newWidth
                            params.height = newHeight
                            lastExpandedWidth = newWidth
                            lastExpandedHeight = newHeight
                        } else {
                            val dx = event.rawX - lastX
                            val dy = event.rawY - lastY
                            
                            if (Math.abs(event.rawX - downX) > 5 || Math.abs(event.rawY - downY) > 5) {
                                isDragging = true
                            }
                            
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                        }
                        
                        lastX = event.rawX
                        lastY = event.rawY
                        
                        try {
                            windowManager.updateViewLayout(this@FloatView, params)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isExpanded && !isDragging) {
                            performToggle()
                        }
                    }
                }
                return true
            }
        })
    }

    private fun performToggle() {
        if (!isAttachedToWindow) return
        val panelBg = GradientDrawable()
        if (isExpanded) {
            isExpanded = false
            topBar.visibility = View.GONE
            contentFrame.visibility = View.GONE
            collapsedPanel.visibility = View.VISIBLE
            
            panelBg.setColor(Color.argb(220, 20, 20, 20))
            panelBg.cornerRadius = 16f
            this.background = panelBg
            this.setPadding(6, 8, 6, 6)
            
            params.width = collapsedWidth
            params.height = collapsedHeight
        } else {
            isExpanded = true
            collapsedPanel.visibility = View.GONE
            topBar.visibility = View.VISIBLE
            contentFrame.visibility = View.VISIBLE
            
            panelBg.setColor(Color.argb(205, 15, 15, 15))
            panelBg.cornerRadius = 14f
            this.background = panelBg
            this.setPadding(12, 8, 12, 12)
            
            params.width = lastExpandedWidth
            params.height = lastExpandedHeight
        }
        try {
            windowManager.updateViewLayout(this@FloatView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createPanel(title: String, containerLayout: LinearLayout): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleView = TextView(context).apply {
            text = title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E67E22"))
            setPadding(4, 2, 4, 6)
        }
        box.addView(titleView)
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(containerLayout)
        box.addView(scroll, LinearLayout.LayoutParams(310, LinearLayout.LayoutParams.MATCH_PARENT))
        return box
    }

    fun updateRole(role: Int, enablePrompt: Boolean = true) {
        if (!isAttachedToWindow) return
        post {
            val isPilot = role == 0
            val roleText = if (isPilot) "🎤 飞行员" else "🎧 观察者"
            val color = if (isPilot) Color.parseColor("#2ECC71") else Color.parseColor("#3498DB")
            val bgColor = if (isPilot) Color.argb(60, 46, 204, 113) else Color.argb(60, 52, 152, 219)
            
            roleStatusView.text = roleText
            roleStatusView.setTextColor(color)
            (roleStatusView.background as? GradientDrawable)?.setColor(bgColor)
            roleStatusView.visibility = View.VISIBLE
            
            if (enablePrompt) {
                startFlashing(color)
                triggerVibration()
            }
        }
    }
    
    private fun startFlashing(color: Int) {
        if (isFlashing.get()) return
        isFlashing.set(true)
        flashCount = 0
        
        val originalColor = roleStatusView.currentTextColor
        
        fun doFlash() {
            if (!isAttachedToWindow || !isFlashing.get() || flashCount >= MAX_FLASH_COUNT) {
                isFlashing.set(false)
                roleStatusView.setTextColor(originalColor)
                return
            }
            
            val isVisible = flashCount % 2 == 0
            roleStatusView.setTextColor(if (isVisible) color else originalColor)
            flashCount++
            
            postDelayed({ doFlash() }, 300)
        }
        
        doFlash()
    }
    
    private fun triggerVibration() {
        try {
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            // 震动失败忽略
        }
    }

    fun updateJsonDynamic(rawJson: String) {
        if (!isAttachedToWindow) return
        post {
            try {
                val obj = JSONObject(rawJson)
                
                var airR1: Float? = null
                var airR2: Float? = null
                var airSnr: Float? = null
                var gndR1: Float? = null
                var gndR2: Float? = null
                var gndSnr: Float? = null
                
                var role = obj.optInt("role", 1)

                val noiseColors = arrayOf("#E74C3C", "#F1C40F", "#3498DB", "#9B59B6", "#1ABC9C", "#E67E22")

                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val valueStr = obj.optString(key, "")

                    if (key == "noiseFloor_a" || key == "noiseFloor_g") {
                        val isAir = key == "noiseFloor_a"
                        val targetLayout = if (isAir) airLayout else gndLayout
                        val targetMap = if (isAir) airTextViewMap else gndTextViewMap
                        val chart = if (isAir) airNoiseChartView else gndNoiseChartView
                        
                        chart.addNoiseData(valueStr)
                        
                        val parts = valueStr.split(",")
                        parts.forEachIndexed { index, partValue ->
                            val subKey = "${key}_ch${index + 1}"
                            val channelColor = Color.parseColor(noiseColors[index % noiseColors.size])
                            val prefixLabel = if (isAir) "空中频点" else "地面频点"
                            val displayText = "$prefixLabel${index + 1} : ${partValue.trim()}"
                            
                            val cachedTv = targetMap[subKey]
                            if (cachedTv != null) {
                                cachedTv.text = displayText
                                cachedTv.setTextColor(channelColor)
                            } else {
                                val tv = TextView(context).apply {
                                    text = displayText
                                    textSize = 12f
                                    setTextColor(channelColor)
                                    setPadding(6, 4, 6, 4)
                                }
                                targetLayout.addView(tv)
                                targetMap[subKey] = tv
                            }
                        }
                        continue 
                    }

                    if (key.endsWith("_a") || key.startsWith("air_")) {
                        updateOrAddTextWithColor(airLayout, airTextViewMap, key, valueStr)
                        if (key.contains("rssi1")) airR1 = valueStr.toFloatOrNull()
                        if (key.contains("rssi2")) airR2 = valueStr.toFloatOrNull()
                        if (key.contains("snr")) airSnr = valueStr.toFloatOrNull()
                    } else if (key.endsWith("_g") || key.startsWith("gnd_")) {
                        updateOrAddTextWithColor(gndLayout, gndTextViewMap, key, valueStr)
                        if (key.contains("rssi1")) gndR1 = valueStr.toFloatOrNull()
                        if (key.contains("rssi2")) gndR2 = valueStr.toFloatOrNull()
                        if (key.contains("snr")) gndSnr = valueStr.toFloatOrNull()
                    } else {
                        if (key != "role") {
                            updateOrAddTextWithColor(airLayout, airTextViewMap, key, valueStr)
                        }
                    }
                }

                airSignalIconView.setSignalData(airR1 ?: 0f, airR2 ?: 0f, airSnr ?: 0f)
                gndSignalIconView.setSignalData(gndR1 ?: 0f, gndR2 ?: 0f, gndSnr ?: 0f)

                if (airR1 != null || airR2 != null || airSnr != null) airChartView.addData(airR1, airR2, airSnr)
                if (gndR1 != null || gndR2 != null || gndSnr != null) gndChartView.addData(gndR1, gndR2, gndSnr)

            } catch (e: Exception) {
                android.util.Log.e("FloatViewError", "数据刷新渲染异常: ${e.message}")
            }
        }
    }

    private fun updateOrAddTextWithColor(layout: LinearLayout, map: HashMap<String, TextView>, key: String, value: String) {
        val cachedTv = map[key]
        val displayColor = when {
            key.contains("rssi", ignoreCase = true) -> {
                val rssiVal = value.toFloatOrNull() ?: 0f
                when {
                    rssiVal == 0f -> Color.parseColor("#E74C3C")
                    rssiVal < 60f -> Color.parseColor("#2ECC71")
                    rssiVal < 75f -> Color.parseColor("#F1C40F")
                    rssiVal < 90f -> Color.parseColor("#E67E22")
                    else -> Color.parseColor("#E74C3C")
                }
            }
            key.contains("snr", ignoreCase = true) -> {
                val snrVal = value.toFloatOrNull() ?: 0f
                when {
                    snrVal < 8f -> Color.parseColor("#E74C3C")
                    snrVal < 18f -> Color.parseColor("#F1C40F")
                    else -> Color.parseColor("#2ECC71")
                }
            }
            key.contains("failed", ignoreCase = true) -> {
                val failedCount = value.toIntOrNull() ?: 0
                if (failedCount > 0) Color.parseColor("#E74C3C") else Color.WHITE
            }
            key.contains("pass", ignoreCase = true) -> Color.parseColor("#3498DB")
            else -> Color.WHITE
        }

        val displayText = "$key : $value"
        if (cachedTv != null) {
            cachedTv.text = displayText
            cachedTv.setTextColor(displayColor)
        } else {
            val tv = TextView(context).apply {
                text = displayText
                textSize = 12f
                setTextColor(displayColor)
                setPadding(6, 4, 6, 4)
            }
            layout.addView(tv)
            map[key] = tv
        }
    }

    private class SignalIconView(context: Context, private val label: String) : View(context) {
        private var r1 = 0f
        private var r2 = 0f
        private var snr = 0f

        private val paint = Paint().apply { isAntiAlias = true }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 14f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        private val subTextPaint = Paint().apply {
            color = Color.parseColor("#BDC3C7")
            textSize = 15f         
            isFakeBoldText = true  
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        fun setSignalData(rssi1: Float, rssi2: Float, snrVal: Float) {
            this.r1 = rssi1
            this.r2 = rssi2
            this.snr = snrVal
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            textPaint.color = if (label == "AIR") Color.parseColor("#E67E22") else Color.parseColor("#3498DB")
            textPaint.isFakeBoldText = true
            canvas.drawText(label, w / 2f, 20f, textPaint)

            val primaryRssi = if (r1 > 0 && r2 > 0) Math.min(r1, r2) else Math.max(r1, r2)
            val (bars, barColor) = when {
                primaryRssi == 0f -> 1 to Color.parseColor("#E74C3C")
                primaryRssi < 60f -> 4 to Color.parseColor("#2ECC71")
                primaryRssi < 75f -> 3 to Color.parseColor("#F1C40F")
                primaryRssi < 90f -> 2 to Color.parseColor("#E67E22")
                else -> 1 to Color.parseColor("#E74C3C")
            }

            val barCount = 4
            val barSpacing = 4f
            val totalSpacing = barSpacing * (barCount - 1)
            val barWidth = 6f
            val startX = (w - (barWidth * barCount + totalSpacing)) / 2f
            val baseLineY = h - 45f

            for (i in 0 until barCount) {
                val x = startX + i * (barWidth + barSpacing)
                val barHeight = 8f + i * 5f
                val top = baseLineY - barHeight
                
                if (i < bars) {
                    paint.color = barColor
                    paint.style = Paint.Style.FILL
                } else {
                    paint.color = Color.argb(55, 255, 255, 255)
                    paint.style = Paint.Style.FILL
                }
                canvas.drawRect(x, top, x + barWidth, baseLineY, paint)
            }

            val infoStr = "${r1.toInt()}/${r2.toInt()}/${snr.toInt()}"
            val finalInfo = if (primaryRssi == 0f) "DISCONN" else infoStr
            subTextPaint.color = barColor
            canvas.drawText(finalInfo, w / 2f, h - 15f, subTextPaint)
        }
    }

    private class WaveformView(context: Context, private val isAir: Boolean) : View(context) {
        private val maxDataPoints = 100
        private val yAxisWidth = 85f 

        private val rssi1List = CopyOnWriteArrayList<Float>()
        private val rssi2List = CopyOnWriteArrayList<Float>()
        private val snrList = CopyOnWriteArrayList<Float>()

        private val axisTextPaint = Paint().apply { color = Color.parseColor("#95A5A6"); textSize = 13f; isAntiAlias = true }
        private val prefixTextPaint = Paint().apply { 
            color = Color.parseColor("#ECF0F1")
            textSize = 14f
            isFakeBoldText = true
            isAntiAlias = true 
        }

        private val colorRssi1 = Color.parseColor("#2980B9")
        private val colorRssi2 = Color.parseColor("#3498DB")
        private val colorSnr   = Color.parseColor("#2ECC71")

        private val paintRssi1 = Paint().apply { color = colorRssi1; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
        private val paintRssi2 = Paint().apply { color = colorRssi2; strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true }
        private val paintSnr   = Paint().apply { color = colorSnr; strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }

        private val paintTextRssi1 = Paint().apply { color = colorRssi1; textSize = 14f; isAntiAlias = true }
        private val paintTextRssi2 = Paint().apply { color = colorRssi2; textSize = 14f; isAntiAlias = true }
        private val paintTextSnr   = Paint().apply { color = colorSnr; textSize = 14f; isAntiAlias = true }

        private val gridPaint = Paint().apply { color = Color.argb(30, 255, 255, 255); strokeWidth = 1f }
        private val bgPaint = Paint().apply { color = Color.argb(15, 255, 255, 255) }

        private val rssiMin = 0f
        private val rssiMax = 120f
        private val snrMin = 0f
        private val snrMax = 50f

        fun addData(r1: Float?, r2: Float?, snr: Float?) {
            rssi1List.add(r1 ?: rssi1List.lastOrNull() ?: 0f)
            rssi2List.add(r2 ?: rssi2List.lastOrNull() ?: 0f)
            snrList.add(snr ?: snrList.lastOrNull() ?: 0f)
            
            if (rssi1List.size > maxDataPoints) rssi1List.removeAt(0)
            if (rssi2List.size > maxDataPoints) rssi2List.removeAt(0)
            if (snrList.size > maxDataPoints) snrList.removeAt(0)
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val chartLeft = yAxisWidth
            val chartRight = w
            val chartWidth = chartRight - chartLeft
            canvas.drawRect(chartLeft, 0f, chartRight, h, bgPaint)

            val yPositions = floatArrayOf(h * 0.2f, h * 0.5f, h * 0.8f)
            val rssiLabels = arrayOf("120", "60", "0")
            val snrLabels = arrayOf("50", "25", "0")

            for (i in yPositions.indices) {
                val y = yPositions[i]
                canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
                canvas.drawText("${rssiLabels[i]}(${snrLabels[i]})", 5f, y + 5f, axisTextPaint)
            }

            val prefix = if (isAir) "[AIR] " else "[GND] "
            canvas.drawText(prefix, chartLeft + 15f, 22f, prefixTextPaint)
            val startX = chartLeft + 15f + prefixTextPaint.measureText(prefix)

            val r1Text = "R1: ${rssi1List.lastOrNull()?.toInt() ?: 0}  "
            canvas.drawText(r1Text, startX, 22f, paintTextRssi1)
            val r2Text = "R2: ${rssi2List.lastOrNull()?.toInt() ?: 0}  "
            canvas.drawText(r2Text, startX + paintTextRssi1.measureText(r1Text), 22f, paintTextRssi2)
            val snrText = "SNR: ${snrList.lastOrNull()?.toInt() ?: 0}"
            canvas.drawText(snrText, startX + paintTextRssi1.measureText(r1Text) + paintTextRssi2.measureText(r2Text), 22f, paintTextSnr)

            drawNormalCurve(canvas, rssi1List, rssiMin, rssiMax, chartLeft, chartWidth, h, paintRssi1)
            drawNormalCurve(canvas, rssi2List, rssiMin, rssiMax, chartLeft, chartWidth, h, paintRssi2)
            drawNormalCurve(canvas, snrList, minVal = snrMin, maxVal = snrMax, leftOffset = chartLeft, cWidth = chartWidth, h = h, paint = paintSnr)
        }

        private fun drawNormalCurve(canvas: Canvas, list: List<Float>, minVal: Float, maxVal: Float, leftOffset: Float, cWidth: Float, h: Float, paint: Paint) {
            val size = list.size
            if (size < 2) return
            val stepX = cWidth / (maxDataPoints - 1)
            val range = maxVal - minVal
            for (i in 0 until size - 1) {
                val startX = leftOffset + (i * stepX)
                val endX = leftOffset + ((i + 1) * stepX)
                val valStart = list[i].coerceIn(minVal, maxVal)
                val valEnd = list[i + 1].coerceIn(minVal, maxVal)
                canvas.drawLine(startX, h * (1f - (valStart - minVal) / range), endX, h * (1f - (valEnd - minVal) / range), paint)
            }
        }
    }

    private class NoiseFloorChartView(context: Context, private val isAir: Boolean) : View(context) {
        private val maxDataPoints = 100
        private val yAxisWidth = 85f
        
        private val historyList = CopyOnWriteArrayList<FloatArray>()
        
        private val axisTextPaint = Paint().apply { color = Color.parseColor("#95A5A6"); textSize = 13f; isAntiAlias = true }
        private val headerTextPaint = Paint().apply { color = Color.parseColor("#E67E22"); textSize = 14f; isFakeBoldText = true; isAntiAlias = true }
        private val gridPaint = Paint().apply { color = Color.argb(30, 255, 255, 255); strokeWidth = 1f }
        private val bgPaint = Paint().apply { color = Color.argb(20, 230, 126, 34) } 

        private val curveColors = intArrayOf(
            Color.parseColor("#E74C3C"), 
            Color.parseColor("#F1C40F"), 
            Color.parseColor("#3498DB"), 
            Color.parseColor("#9B59B6"), 
            Color.parseColor("#1ABC9C"), 
            Color.parseColor("#E67E22")  
        )
        private val curvePaints = Array(curveColors.size) { i ->
            Paint().apply { color = curveColors[i]; strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true }
        }

        private val noiseMin = 40f
        private val noiseMax = 140f

        fun addNoiseData(rawCsv: String) {
            try {
                val parts = rawCsv.split(",")
                val floatArray = FloatArray(parts.size)
                for (i in parts.indices) {
                    floatArray[i] = parts[i].trim().toFloatOrNull() ?: 0f
                }
                historyList.add(floatArray)
                if (historyList.size > maxDataPoints) historyList.removeAt(0)
                postInvalidate()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val chartLeft = yAxisWidth
            val chartRight = w
            val chartWidth = chartRight - chartLeft
            canvas.drawRect(chartLeft, 0f, chartRight, h, bgPaint)

            val yPositions = floatArrayOf(h * 0.2f, h * 0.5f, h * 0.8f)
            val labels = arrayOf("140", "90", "40")
            for (i in yPositions.indices) {
                val y = yPositions[i]
                canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
                canvas.drawText(labels[i], 20f, y + 5f, axisTextPaint)
            }

            val title = if (isAir) "[AIR NOISE]" else "[GND NOISE]"
            canvas.drawText(title, chartLeft + 15f, 22f, headerTextPaint)

            val historySize = historyList.size
            if (historySize == 0) return
            
            val currentChannels = historyList[historySize - 1].size
            val stepX = chartWidth / (maxDataPoints - 1)
            val range = noiseMax - noiseMin

            for (ch in 0 until currentChannels) {
                val paint = curvePaints[ch % curvePaints.size]
                
                for (i in 0 until historySize - 1) {
                    val startArray = historyList[i]
                    val endArray = historyList[i + 1]
                    
                    if (ch >= startArray.size || ch >= endArray.size) continue
                    
                    val startX = chartLeft + (i * stepX)
                    val endX = chartLeft + ((i + 1) * stepX)
                    
                    val valStart = startArray[ch].coerceIn(noiseMin, noiseMax)
                    val valEnd = endArray[ch].coerceIn(noiseMin, noiseMax)
                    
                    canvas.drawLine(
                        startX, h * (1f - (valStart - noiseMin) / range),
                        endX, h * (1f - (valEnd - noiseMin) / range),
                        paint
                    )
                }
            }

            val legendPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
            val legendTextPaint = Paint().apply { color = Color.parseColor("#BDC3C7"); textSize = 11f; isAntiAlias = true }
            
            var legendRightX = w - 15f
            val legendY = 22f

            for (ch in (currentChannels - 1) downTo 0) {
                val chColor = curveColors[ch % curveColors.size]
                val labelStr = "P${ch + 1}"
                
                val textWidth = legendTextPaint.measureText(labelStr)
                val itemWidth = textWidth + 14f
                
                legendPaint.color = chColor
                canvas.drawRect(legendRightX - itemWidth, legendY - 8f, legendRightX - itemWidth + 8f, legendY, legendPaint)
                canvas.drawText(labelStr, legendRightX - itemWidth + 12f, legendY, legendTextPaint)
                
                legendRightX -= (itemWidth + 14f)
            }
        }
    }
}
