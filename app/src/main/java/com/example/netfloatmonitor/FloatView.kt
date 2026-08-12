package com.example.netfloatmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class FloatView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams
) : LinearLayout(context) {

    // ==========================================
    // 尺寸常量定义 (天空地面双栏已同步收窄为 220)
    // ==========================================
    private val TEXT_COL_WIDTH = 220      // 单个文本数据列宽度
    private val CHART_COL_WIDTH = 480     // 单个图表曲线列宽度
    
    private val collapsedWidth = 220
    private val collapsedHeight = 130
    private var lastExpandedHeight = 650 

    // 核心状态控制：完全独立区分开两张图表的收纳与展开
    private var isExpanded = true             // 整体悬浮窗是否展开 (false 为右下角小图标状态)
    private var isWaveformExpanded = true     // 实时波形曲线图列是否展开
    private var isNoiseExpanded = true        // 底噪频谱曲线图列是否展开

    private var startWidth = 0
    private var startHeight = 0
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var resize = false

    // =========================================================================
    // 用于支持 failed 字段动态变红、无闪烁保持、5秒自动恢复原色的状态追踪变量
    // =========================================================================
    private val lastValues = HashMap<String, String>()                    // 记录上一次的数值快照
    private val redTimerRunnables = HashMap<String, Runnable>()           // 存放每个 key 专属的定时恢复任务
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper()) // 主线程路由驱动

    // 统一底噪曲线颜色调色板（供文本与频谱图表共享匹配）
    private val noiseCurveColors = intArrayOf(
        Color.parseColor("#E74C3C"), // ch1: 红
        Color.parseColor("#F1C40F"), // ch2: 黄
        Color.parseColor("#3498DB"), // ch3: 蓝
        Color.parseColor("#9B59B6"), // ch4: 紫
        Color.parseColor("#1ABC9C"), // ch5: 青
        Color.parseColor("#E67E22")  // ch6: 橙
    )

    // UI 容器组件
    private val topBar = LinearLayout(context)
    private val contentFrame = FrameLayout(context)
    private val contentPanel = LinearLayout(context) // 主内容水平平铺容器
    
    // 独立解耦的图表列容器
    private val waveformCol = LinearLayout(context)
    private val noiseCol = LinearLayout(context)

    // 数据列表容器
    private val airLayout = LinearLayout(context)
    private val gndLayout = LinearLayout(context)
    
    // 自定义 View 实例
    private val airChartView = WaveformView(context, isAir = true)
    private val gndChartView = WaveformView(context, isAir = false)
    private val airNoiseChartView = NoiseFloorChartView(context, isAir = true, noiseCurveColors)
    private val gndNoiseChartView = NoiseFloorChartView(context, isAir = false, noiseCurveColors)

    // 迷你折叠面板组件
    private val collapsedPanel = LinearLayout(context)
    private val airSignalIconView = SignalIconView(context, "AIR")
    private val gndSignalIconView = SignalIconView(context, "GND")
    
    private val airTextViewMap = HashMap<String, TextView>()
    private val gndTextViewMap = HashMap<String, TextView>()

    private val resizeIndicator = View(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#3498DB"))
            cornerRadius = 4f
        }
    }

    // 顶部全局最小化至图标按钮
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

    // 单独控制【实时波形图】的独立开关
    private val waveformToggleBtn = Button(context).apply {
        text = "Link Curve"
        textSize = 11f
        setTextColor(Color.WHITE)
        setGravity(Gravity.CENTER)
        setPadding(10, 0, 10, 0)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#2980B9"))
            cornerRadius = 6f
        }
    }

    // 单独控制【底噪频谱图】的独立开关
    private val noiseToggleBtn = Button(context).apply {
        text = "Noise Floor"
        textSize = 11f
        setTextColor(Color.WHITE)
        setGravity(Gravity.CENTER)
        setPadding(10, 0, 10, 0)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#27AE60"))
            cornerRadius = 6f
        }
    }

    // ===== 新增：角色状态显示 =====
    private val roleStatusView = TextView(context).apply {
        text = "👤 观察者"
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#3498DB"))
        setPadding(8, 4, 8, 4)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.argb(60, 52, 152, 219))
            cornerRadius = 8f
        }
        visibility = View.VISIBLE
    }

    init {
        this.orientation = LinearLayout.VERTICAL
        this.setPadding(12, 8, 12, 12)

        val bg = GradientDrawable().apply {
            setColor(Color.argb(205, 15, 15, 15))
            cornerRadius = 14f
        }
        this.background = bg

        // 1. 初始化右下角迷你模式状态面板
        collapsedPanel.orientation = LinearLayout.HORIZONTAL
        collapsedPanel.gravity = Gravity.CENTER
        collapsedPanel.visibility = View.GONE
        val iconLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        collapsedPanel.addView(airSignalIconView, iconLp)
        collapsedPanel.addView(gndSignalIconView, iconLp)
        addView(collapsedPanel)

        // 2. 初始化顶部控制状态栏 (加入双图表独立控制开关 + role 显示)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        topBar.setPadding(0, 0, 4, 6)
        
        // ===== 在 topBar 左侧添加 role 状态显示 =====
        val roleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        roleLp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        topBar.addView(roleStatusView, roleLp)
        
        val btnLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 48).apply { rightMargin = 12 }
        topBar.addView(waveformToggleBtn, btnLp)
        topBar.addView(noiseToggleBtn, btnLp)
        topBar.addView(toggleBtn, LinearLayout.LayoutParams(48, 48))
        addView(topBar)

        // 3. 构建主内容区 (经典的横向平铺串联架构)
        contentPanel.orientation = LinearLayout.HORIZONTAL
        airLayout.orientation = LinearLayout.VERTICAL
        gndLayout.orientation = LinearLayout.VERTICAL
        
        // 第 1 列：空中数传文本面板
        contentPanel.addView(createPanel("AIR", airLayout), LinearLayout.LayoutParams(TEXT_COL_WIDTH, LinearLayout.LayoutParams.MATCH_PARENT))
        
        // 第 2 列：地面数传文本面板
        val gndTextLp = LinearLayout.LayoutParams(TEXT_COL_WIDTH, LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = 12 }
        contentPanel.addView(createPanel("GND", gndLayout), gndTextLp)

        val subChartLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = 6 }
        val lastChartLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        // 第 3 列：实时波形网格图层 (完全拆分为独立列容器)
        waveformCol.orientation = LinearLayout.VERTICAL
        waveformCol.addView(airChartView, subChartLp)
        waveformCol.addView(gndChartView, lastChartLp)
        val waveColLp = LinearLayout.LayoutParams(CHART_COL_WIDTH, LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = 16 }
        contentPanel.addView(waveformCol, waveColLp)

        // 第 4 列：底噪频谱网格图层 (完全拆分为独立列容器)
        noiseCol.orientation = LinearLayout.VERTICAL
        noiseCol.addView(airNoiseChartView, subChartLp)
        noiseCol.addView(gndNoiseChartView, lastChartLp)
        val noiseColLp = LinearLayout.LayoutParams(CHART_COL_WIDTH, LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = 16 }
        contentPanel.addView(noiseCol, noiseColLp)
        
        // 4. 组装外层容器与边缘边界拉伸片
        contentFrame.addView(contentPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(resizeIndicator, FrameLayout.LayoutParams(18, 18).apply { 
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, 2, 2) 
        })
        addView(contentFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        // 动态计算并应用初始窗口总宽度
        updateWindowLayoutWidth()
        params.height = lastExpandedHeight

        // 5. 事件监听绑定与独立状态切换
        waveformToggleBtn.setOnClickListener {
            isWaveformExpanded = !isWaveformExpanded
            waveformCol.visibility = if (isWaveformExpanded) View.VISIBLE else View.GONE
            waveformToggleBtn.text = "Link Curve"
            waveformToggleBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isWaveformExpanded) "#2980B9" else "#7F8C8D"))
                cornerRadius = 6f
            }
            updateWindowLayoutWidth()
        }

        noiseToggleBtn.setOnClickListener {
            isNoiseExpanded = !isNoiseExpanded
            noiseCol.visibility = if (isNoiseExpanded) View.VISIBLE else View.GONE
            noiseToggleBtn.text = "Noise Floor"
            noiseToggleBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isNoiseExpanded) "#27AE60" else "#7F8C8D"))
                cornerRadius = 6f
            }
            updateWindowLayoutWidth()
        }

        toggleBtn.setOnClickListener {
            if (isExpanded) performGlobalToggle()
        }

        setupTouchInteraction()
    }

    /**
     * 根据当前波形图、底噪图的独立展开状态，动态计算并更新悬浮窗的最佳物理宽度
     */
    private fun updateWindowLayoutWidth() {
        if (!isAttachedToWindow || !isExpanded) return
        
        // 基础两列文本宽度 + 必要的内外边距兜底
        var dynamicWidth = TEXT_COL_WIDTH * 2 + 50
        
        // 独立加上波形图列宽与间距
        if (isWaveformExpanded) {
            dynamicWidth += CHART_COL_WIDTH + 16
        }
        // 独立加上底噪图列宽与间距
        if (isNoiseExpanded) {
            dynamicWidth += CHART_COL_WIDTH + 16
        }
        
        params.width = dynamicWidth
        try {
            windowManager.updateViewLayout(this, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupTouchInteraction() {
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
                            
                            val newWidth = (startWidth + totalDx).toInt().coerceAtLeast(350)
                            val newHeight = (startHeight + totalDy).toInt().coerceAtLeast(250)
                            
                            params.width = newWidth
                            params.height = newHeight
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
                            performGlobalToggle()
                        }
                    }
                }
                return true
            }
        })
    }

    /**
     * 全局最小化至极简状态面板切换逻辑 (对应右上角关闭按钮)
     */
    private fun performGlobalToggle() {
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
            
            updateWindowLayoutWidth()
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
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return box
    }

    // =========================================================================
    // 数据动态刷新与高保真核心渲染逻辑
    // =========================================================================
    fun updateJsonDynamic(rawJson: String) {
        if (!isAttachedToWindow) return
        post {
            try {
                val obj = JSONObject(rawJson)
                
                // ===== 解析 role 并更新显示 =====
                val role = obj.optInt("role", 1)
                roleStatusView.apply {
                    val isPilot = role == 0
                    val roleText = if (isPilot) "🎤 飞行员" else "🎧 观察者"
                    val color = if (isPilot) Color.parseColor("#2ECC71") else Color.parseColor("#3498DB")
                    val bgColor = if (isPilot) Color.argb(60, 46, 204, 113) else Color.argb(60, 52, 152, 219)
                    text = roleText
                    setTextColor(color)
                    (background as? GradientDrawable)?.setColor(bgColor)
                    visibility = View.VISIBLE
                }
                
                var airR1: Float? = null
                var airR2: Float? = null
                var airSnr: Float? = null
                var gndR1: Float? = null
                var gndR2: Float? = null
                var gndSnr: Float? = null

                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val valueStr = obj.optString(key, "")

                    // 噪声文本解析分支
                    if (key == "noiseFloor_a" || key == "noiseFloor_g") {
                        val isAir = key == "noiseFloor_a"
                        val targetLayout = if (isAir) airLayout else gndLayout
                        val targetMap = if (isAir) airTextViewMap else gndTextViewMap
                        val chart = if (isAir) airNoiseChartView else gndNoiseChartView
                        
                        chart.addNoiseData(valueStr)
                        
                        val parts = valueStr.split(",")
                        parts.forEachIndexed { index, partValue ->
                            val subKey = "${key}_ch${index + 1}"
                            val prefixLabel = if (isAir) "Air_ch" else "Gnd_ch"
                            val displayText = "$prefixLabel${index + 1} : ${partValue.trim()}"
                            
                            val chColor = noiseCurveColors[index % noiseCurveColors.size]
                            
                            val cachedTv = targetMap[subKey]
                            if (cachedTv != null) {
                                cachedTv.text = displayText
                                cachedTv.setTextColor(chColor)
                            } else {
                                val tv = TextView(context).apply {
                                    text = displayText
                                    textSize = 10.5f
                                    setTextColor(chColor)
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
                        // 跳过 role 字段，因为已经单独处理
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
                android.util.Log.e("FloatViewError", "数据处理渲染异常: ${e.message}")
            }
        }
    }

    private fun updateOrAddTextWithColor(layout: LinearLayout, map: HashMap<String, TextView>, key: String, value: String) {
        val cachedTv = map[key]
        
        var displayColor = when {
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
            key.contains("pass", ignoreCase = true) -> Color.parseColor("#3498DB")
            else -> Color.WHITE
        }

        if (key.contains("failed", ignoreCase = true)) {
            val oldValue = lastValues[key]
            lastValues[key] = value

            if (oldValue != null && oldValue != value) {
                redTimerRunnables[key]?.let { mainHandler.removeCallbacks(it) }
                
                val resetRunnable = Runnable {
                    map[key]?.setTextColor(Color.WHITE)
                    redTimerRunnables.remove(key)
                }
                redTimerRunnables[key] = resetRunnable
                mainHandler.postDelayed(resetRunnable, 5000)
                
                displayColor = Color.parseColor("#E74C3C")
            } else {
                displayColor = if (redTimerRunnables.containsKey(key)) {
                    Color.parseColor("#E74C3C")
                } else {
                    Color.WHITE
                }
            }
        }

        val displayText = "$key : $value"
        if (cachedTv != null) {
            cachedTv.text = displayText
            cachedTv.setTextColor(displayColor)
        } else {
            val tv = TextView(context).apply {
                text = displayText
                textSize = 10.5f
                setTextColor(displayColor)
                setPadding(6, 4, 6, 4)
            }
            layout.addView(tv)
            map[key] = tv
        }
    }

    // ==========================================
    // 内部私有自定义测量 View 绘制实现
    // ==========================================

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
        
            val axisRssiRange = rssiMax - rssiMin
            
            val labelValues = floatArrayOf(110f, 90f, 70f, 50f, 30f, 0f)
            val rssiLabels = arrayOf("110", "90", "70", "50", "30", "0")
            val snrLabels  = arrayOf("45", "35", "25", "15", "8", "0")
        
            val yPositions = FloatArray(labelValues.size) { i ->
                h * (1f - (labelValues[i] - rssiMin) / axisRssiRange)
            }
        
            for (i in yPositions.indices) {
                val y = yPositions[i]
                
                if (y in 0f..h) {
                    canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
                    val textY = if (i == yPositions.lastIndex) y - 6f else y + 5f
                    canvas.drawText("${rssiLabels[i]}(${snrLabels[i]})", 5f, textY, axisTextPaint)
                }
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

            val range = rssiMax - rssiMin
            drawNormalCurve(canvas, rssi1List, rssiMin, rssiMax, chartLeft, chartWidth, h, paintRssi1)
            drawNormalCurve(canvas, rssi2List, rssiMin, rssiMax, chartLeft, chartWidth, h, paintRssi2)
            drawNormalCurve(canvas, snrList, minVal = snrMin, maxVal = snrMax, leftOffset = chartLeft, cWidth = chartWidth, h = h, paint = paintSnr)
        }

        private fun drawNormalCurve(canvas: Canvas, list: List<Float>, minVal: Float, maxVal: Float, leftOffset: Float, cWidth: Float, h: Float, paint: Paint) {
            val size = list.size
            if (size < 2) return
            val stepX = cWidth / (maxDataPoints - 1)
            val curRange = maxVal - minVal
            for (i in 0 until size - 1) {
                val startX = leftOffset + (i * stepX)
                val endX = leftOffset + ((i + 1) * stepX)
                val valStart = list[i].coerceIn(minVal, maxVal)
                val valEnd = list[i + 1].coerceIn(minVal, maxVal)
                canvas.drawLine(startX, h * (1f - (valStart - minVal) / curRange), endX, h * (1f - (valEnd - minVal) / curRange), paint)
            }
        }
    }

    private class NoiseFloorChartView(
        context: Context, 
        private val isAir: Boolean,
        private val curveColors: IntArray
    ) : View(context) {
        private val maxDataPoints = 100
        private val yAxisWidth = 85f
        
        private val historyList = CopyOnWriteArrayList<FloatArray>()
        
        private val axisTextPaint = Paint().apply { color = Color.parseColor("#95A5A6"); textSize = 13f; isAntiAlias = true }
        private val headerTextPaint = Paint().apply { color = Color.parseColor("#E67E22"); textSize = 14f; isFakeBoldText = true; isAntiAlias = true }
        private val gridPaint = Paint().apply { color = Color.argb(30, 255, 255, 255); strokeWidth = 1f }
        private val bgPaint = Paint().apply { color = Color.argb(20, 230, 126, 34) }

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
        
            val axisNoiseRange = noiseMax - noiseMin
            
            val noiseLabels = arrayOf("120", "105", "90", "75", "60", "45", "30")
            val noiseValues = floatArrayOf(120f, 105f, 90f, 75f, 60f, 45f, 30f)
        
            val yPositions = FloatArray(noiseValues.size) { i ->
                h * (1f - (noiseValues[i] - noiseMin) / axisNoiseRange)
            }
        
            for (i in yPositions.indices) {
                val y = yPositions[i]
                if (y in 0f..h) {
                    canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
                    val textY = if (i == yPositions.lastIndex) y - 4f else y + 5f
                    canvas.drawText(noiseLabels[i], 20f, textY, axisTextPaint)
                }
            }
        
            val title = if (isAir) "[AIR] NOISE" else "[GND] NOISE"
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
                val labelStr = "ch${ch + 1}"
                
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
