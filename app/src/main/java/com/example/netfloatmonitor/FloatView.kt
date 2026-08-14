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

enum class SignalState {
    NORMAL,
    WEAK,
    LOST
}

class FloatView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams
) : LinearLayout(context) {

    private val TEXT_COL_WIDTH = 220
    private val CHART_COL_WIDTH = 480
    
    private val collapsedWidth = 220
    private val collapsedHeight = 130
    private var lastExpandedHeight = 650 

    private var isExpanded = true
    private var isWaveformExpanded = true
    private var isNoiseExpanded = true

    private var startWidth = 0
    private var startHeight = 0
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var resize = false

    private val lastValues = HashMap<String, String>()
    private val redTimerRunnables = HashMap<String, Runnable>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val noiseCurveColors = intArrayOf(
        Color.parseColor("#E74C3C"),
        Color.parseColor("#F1C40F"),
        Color.parseColor("#3498DB"),
        Color.parseColor("#9B59B6"),
        Color.parseColor("#1ABC9C"),
        Color.parseColor("#E67E22")
    )

    private val COLOR_SIGNAL_EXCELLENT = Color.parseColor("#2ECC71")
    private val COLOR_SIGNAL_GOOD = Color.parseColor("#1ABC9C")
    private val COLOR_SIGNAL_FAIR = Color.parseColor("#F1C40F")
    private val COLOR_SIGNAL_POOR = Color.parseColor("#E67E22")
    private val COLOR_SIGNAL_LOST = Color.parseColor("#E74C3C")
    private val COLOR_SIGNAL_DEFAULT = Color.WHITE

    var currentSignalState: SignalState = SignalState.NORMAL
        private set

    var onSignalStateChanged: ((SignalState, Int, Int) -> Unit)? = null

    private val topBar = LinearLayout(context)
    private val contentFrame = FrameLayout(context)
    private val contentPanel = LinearLayout(context)
    
    private val waveformCol = LinearLayout(context)
    private val noiseCol = LinearLayout(context)

    private val airLayout = LinearLayout(context)
    private val gndLayout = LinearLayout(context)
    
    private val airChartView = WaveformView(context, isAir = true)
    private val gndChartView = WaveformView(context, isAir = false)
    private val airNoiseChartView = NoiseFloorChartView(context, isAir = true, noiseCurveColors)
    private val gndNoiseChartView = NoiseFloorChartView(context, isAir = false, noiseCurveColors)

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

    private val signalStatusView = TextView(context).apply {
        text = "📶 信号正常"
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_SIGNAL_EXCELLENT)
        setPadding(4, 2, 4, 2)
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
        
        val btnLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 48).apply { rightMargin = 12 }
        topBar.addView(waveformToggleBtn, btnLp)
        topBar.addView(noiseToggleBtn, btnLp)
        topBar.addView(toggleBtn, LinearLayout.LayoutParams(48, 48))
        addView(topBar)

        contentPanel.orientation = LinearLayout.HORIZONTAL
        airLayout.orientation = LinearLayout.VERTICAL
        gndLayout.orientation = LinearLayout.VERTICAL
        
        val airPanel = createPanel("AIR", airLayout)
        val airPanelContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        airPanelContainer.addView(signalStatusView)
        airPanelContainer.addView(airPanel)
        contentPanel.addView(airPanelContainer, LinearLayout.LayoutParams(TEXT_COL_WIDTH, LinearLayout.LayoutParams.MATCH_PARENT))
        
        val gndTextLp = LinearLayout.LayoutParams(TEXT_COL_WIDTH, LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = 12 }
        contentPanel.addView(createPanel("GND", gndLayout), gndTextLp)

        val subChartLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = 6 }
        val lastChartLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        waveformCol.orientation = LinearLayout.VERTICAL
        waveformCol.addView(airChartView, subChartLp)
        waveformCol.addView(gndChartView, lastChartLp)
        val waveColLp = LinearLayout.LayoutParams(CHART_COL_WIDTH, LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = 16 }
        contentPanel.addView(waveformCol, waveColLp)

        noiseCol.orientation = LinearLayout.VERTICAL
        noiseCol.addView(airNoiseChartView, subChartLp)
        noiseCol.addView(gndNoiseChartView, lastChartLp)
        val noiseColLp = LinearLayout.LayoutParams(CHART_COL_WIDTH, LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = 16 }
        contentPanel.addView(noiseCol, noiseColLp)
        
        contentFrame.addView(contentPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(resizeIndicator, FrameLayout.LayoutParams(18, 18).apply { 
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, 2, 2) 
        })
        addView(contentFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        updateWindowLayoutWidth()
        params.height = lastExpandedHeight

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

    private fun updateWindowLayoutWidth() {
        if (!isAttachedToWindow || !isExpanded) return
        
        var dynamicWidth = TEXT_COL_WIDTH * 2 + 50
        
        if (isWaveformExpanded) {
            dynamicWidth += CHART_COL_WIDTH + 16
        }
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

    private fun getSignalLevel(rssi: Int, snr: Int): SignalLevel {
        // 特殊判断：RSSI == 110 或 SNR == 0 → 丢失
        if (rssi == 110 || snr == 0) {
            return SignalLevel.LOST
        }
        
        // 信号丢失（RSSI > 98 或 SNR < 5）
        if (rssi > 98 || snr < 5) {
            return SignalLevel.LOST
        }
        
        // 信号弱（RSSI 90~98 或 SNR 5~10）
        if ((rssi in 90..98) || (snr in 5..10)) {
            return SignalLevel.POOR
        }
        
        // 信号一般（RSSI 86~90 且 SNR 10~15）
        if (rssi in 86..90 && snr in 10..15) {
            return SignalLevel.FAIR
        }
        
        // 信号良好（RSSI 76~85 且 SNR 15~20）
        if (rssi in 76..85 && snr in 15..20) {
            return SignalLevel.GOOD
        }
        
        // 信号极佳（RSSI <= 75 且 SNR >= 20）
        if (rssi <= 75 && snr >= 20) {
            return SignalLevel.EXCELLENT
        }
        
        // 兜底：按 RSSI 和 SNR 综合判断
        return when {
            rssi <= 85 && snr >= 10 -> SignalLevel.GOOD
            rssi <= 90 && snr >= 5 -> SignalLevel.FAIR
            else -> SignalLevel.POOR
        }
    }

    private fun getSignalColor(level: SignalLevel): Int {
        return when (level) {
            SignalLevel.EXCELLENT -> COLOR_SIGNAL_EXCELLENT
            SignalLevel.GOOD -> COLOR_SIGNAL_GOOD
            SignalLevel.FAIR -> COLOR_SIGNAL_FAIR
            SignalLevel.POOR -> COLOR_SIGNAL_POOR
            SignalLevel.LOST -> COLOR_SIGNAL_LOST
        }
    }

    private fun getSignalState(level: SignalLevel): SignalState {
        return when (level) {
            SignalLevel.EXCELLENT, SignalLevel.GOOD, SignalLevel.FAIR -> SignalState.NORMAL
            SignalLevel.POOR -> SignalState.WEAK
            SignalLevel.LOST -> SignalState.LOST
        }
    }

    fun updateJsonDynamic(rawJson: String) {
        if (!isAttachedToWindow) return
        post {
            try {
                val obj = JSONObject(rawJson)
                
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
                        updateOrAddTextWithColor(airLayout, airTextViewMap, key, valueStr, isAir = true)
                        when {
                            key.contains("rssi1") -> airR1 = valueStr.toFloatOrNull()
                            key.contains("rssi2") -> airR2 = valueStr.toFloatOrNull()
                            key.contains("snr") -> airSnr = valueStr.toFloatOrNull()
                        }
                    } else if (key.endsWith("_g") || key.startsWith("gnd_")) {
                        updateOrAddTextWithColor(gndLayout, gndTextViewMap, key, valueStr, isAir = false)
                        when {
                            key.contains("rssi1") -> gndR1 = valueStr.toFloatOrNull()
                            key.contains("rssi2") -> gndR2 = valueStr.toFloatOrNull()
                            key.contains("snr") -> gndSnr = valueStr.toFloatOrNull()
                        }
                    } else {
                        if (key != "role") {
                            updateOrAddTextWithColor(airLayout, airTextViewMap, key, valueStr, isAir = true)
                        }
                    }
                }

                // ===== AIR 信号等级（独立计算） =====
                val aR1 = airR1?.toInt()
                val aR2 = airR2?.toInt()
                val aSnr = airSnr?.toInt()
                
                if (aR1 != null && aR2 != null && aSnr != null) {
                    val aMinRssi = minOf(aR1, aR2)
                    val aLevel = getSignalLevel(aMinRssi, aSnr)
                    val aColor = getSignalColor(aLevel)
                    val aState = getSignalState(aLevel)
                    
                    airSignalIconView.setSignalData(airR1 ?: 0f, airR2 ?: 0f, airSnr ?: 0f, aLevel)
                    
                    val stateText = when (aState) {
                        SignalState.NORMAL -> "📶 信号正常"
                        SignalState.WEAK -> "⚠️ 信号弱"
                        SignalState.LOST -> "🚫 信号丢失"
                    }
                    signalStatusView.text = stateText
                    signalStatusView.setTextColor(aColor)
                    
                    if (aState != currentSignalState) {
                        currentSignalState = aState
                        onSignalStateChanged?.invoke(aState, aMinRssi, aSnr)
                    }
                }

                // ===== GND 信号等级（独立计算，互不影响） =====
                val gR1 = gndR1?.toInt()
                val gR2 = gndR2?.toInt()
                val gSnr = gndSnr?.toInt()
                
                if (gR1 != null && gR2 != null && gSnr != null) {
                    val gMinRssi = minOf(gR1, gR2)
                    val gLevel = getSignalLevel(gMinRssi, gSnr)
                    gndSignalIconView.setSignalData(gndR1 ?: 0f, gndR2 ?: 0f, gndSnr ?: 0f, gLevel)
                } else {
                    gndSignalIconView.setSignalData(gndR1 ?: 0f, gndR2 ?: 0f, gndSnr ?: 0f)
                }

                if (airR1 != null || airR2 != null || airSnr != null) {
                    airChartView.addData(airR1, airR2, airSnr)
                }
                if (gndR1 != null || gndR2 != null || gndSnr != null) {
                    gndChartView.addData(gndR1, gndR2, gndSnr)
                }

            } catch (e: Exception) {
                android.util.Log.e("FloatViewError", "数据处理渲染异常: ${e.message}")
            }
        }
    }

    private fun updateOrAddTextWithColor(
        layout: LinearLayout,
        map: HashMap<String, TextView>,
        key: String,
        value: String,
        isAir: Boolean
    ) {
        val cachedTv = map[key]
        var signalColor = COLOR_SIGNAL_DEFAULT
        
        if (key.contains("rssi", ignoreCase = true)) {
            val rssiVal = value.toFloatOrNull()?.toInt()
            val snrKey = if (isAir) key.replace("rssi", "snr") else key.replace("rssi", "snr")
            val snrVal = map[snrKey]?.text?.toString()?.toFloatOrNull()?.toInt() ?: 20
            
            if (rssiVal != null) {
                val level = getSignalLevel(rssiVal, snrVal)
                signalColor = getSignalColor(level)
            }
        } else if (key.contains("snr", ignoreCase = true)) {
            val snrVal = value.toFloatOrNull()?.toInt() ?: 0
            val level = when {
                snrVal >= 20 -> SignalLevel.EXCELLENT
                snrVal in 15..19 -> SignalLevel.GOOD
                snrVal in 10..14 -> SignalLevel.FAIR
                snrVal in 5..9 -> SignalLevel.POOR
                else -> SignalLevel.LOST
            }
            signalColor = getSignalColor(level)
        } else if (key.contains("failed", ignoreCase = true)) {
            val oldValue = lastValues[key]
            lastValues[key] = value

            if (oldValue != null && oldValue != value) {
                redTimerRunnables[key]?.let { mainHandler.removeCallbacks(it) }
                val resetRunnable = Runnable {
                    map[key]?.setTextColor(COLOR_SIGNAL_DEFAULT)
                    redTimerRunnables.remove(key)
                }
                redTimerRunnables[key] = resetRunnable
                mainHandler.postDelayed(resetRunnable, 5000)
                signalColor = COLOR_SIGNAL_LOST
            } else {
                signalColor = if (redTimerRunnables.containsKey(key)) {
                    COLOR_SIGNAL_LOST
                } else {
                    COLOR_SIGNAL_DEFAULT
                }
            }
        } else if (key.contains("pass", ignoreCase = true)) {
            signalColor = Color.parseColor("#3498DB")
        }

        val displayText = "$key : $value"
        if (cachedTv != null) {
            cachedTv.text = displayText
            cachedTv.setTextColor(signalColor)
        } else {
            val tv = TextView(context).apply {
                text = displayText
                textSize = 10.5f
                setTextColor(signalColor)
                setPadding(6, 4, 6, 4)
            }
            layout.addView(tv)
            map[key] = tv
        }
    }

    private enum class SignalLevel {
        EXCELLENT, GOOD, FAIR, POOR, LOST
    }

    private class SignalIconView(context: Context, private val label: String) : View(context) {
        private var r1 = 0f
        private var r2 = 0f
        private var snr = 0f
        private var currentLevel: SignalLevel = SignalLevel.LOST

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

        private val COLOR_EXCELLENT = Color.parseColor("#2ECC71")
        private val COLOR_GOOD = Color.parseColor("#1ABC9C")
        private val COLOR_FAIR = Color.parseColor("#F1C40F")
        private val COLOR_POOR = Color.parseColor("#E67E22")
        private val COLOR_LOST = Color.parseColor("#E74C3C")

        fun setSignalData(rssi1: Float, rssi2: Float, snrVal: Float, level: SignalLevel? = null) {
            this.r1 = rssi1
            this.r2 = rssi2
            this.snr = snrVal
            if (level != null) {
                this.currentLevel = level
            } else {
                val rssi = if (rssi1 > 0 && rssi2 > 0) minOf(rssi1, rssi2).toInt() else maxOf(rssi1, rssi2).toInt()
                val snrInt = snrVal.toInt()
                this.currentLevel = when {
                    rssi == 110 || snrInt == 0 -> SignalLevel.LOST
                    rssi <= 75 && snrInt >= 20 -> SignalLevel.EXCELLENT
                    rssi in 76..85 && snrInt in 15..19 -> SignalLevel.GOOD
                    rssi in 86..90 && snrInt in 10..14 -> SignalLevel.FAIR
                    rssi in 91..98 && snrInt in 5..9 -> SignalLevel.POOR
                    else -> SignalLevel.LOST
                }
            }
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

            val barColor = when (currentLevel) {
                SignalLevel.EXCELLENT -> COLOR_EXCELLENT
                SignalLevel.GOOD -> COLOR_GOOD
                SignalLevel.FAIR -> COLOR_FAIR
                SignalLevel.POOR -> COLOR_POOR
                SignalLevel.LOST -> COLOR_LOST
            }
            val barCount = when (currentLevel) {
                SignalLevel.EXCELLENT -> 4
                SignalLevel.GOOD -> 3
                SignalLevel.FAIR -> 2
                SignalLevel.POOR -> 1
                SignalLevel.LOST -> 0
            }

            val totalBars = 4
            val barSpacing = 4f
            val totalSpacing = barSpacing * (totalBars - 1)
            val barWidth = 6f
            val startX = (w - (barWidth * totalBars + totalSpacing)) / 2f
            val baseLineY = h - 45f

            for (i in 0 until totalBars) {
                val x = startX + i * (barWidth + barSpacing)
                val barHeight = 8f + i * 5f
                val top = baseLineY - barHeight
                
                if (i < barCount) {
                    paint.color = barColor
                    paint.style = Paint.Style.FILL
                } else {
                    paint.color = Color.argb(55, 255, 255, 255)
                    paint.style = Paint.Style.FILL
                }
                canvas.drawRect(x, top, x + barWidth, baseLineY, paint)
            }

            val isLost = currentLevel == SignalLevel.LOST
            val infoStr = if (isLost) {
                "LOST"
            } else {
                "${r1.toInt()}/${r2.toInt()}/${snr.toInt()}"
            }
            subTextPaint.color = barColor
            canvas.drawText(infoStr, w / 2f, h - 15f, subTextPaint)
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
