package com.example.netfloatmonitor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.*

class FloatView(context: Context) : LinearLayout(context), View.OnTouchListener {

    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val params: WindowManager.LayoutParams

    private val floatView: View
    private val collapsedIcon: SignalBarsView
    private val topBar: LinearLayout
    private val toggleBtn: Button
    private val signalBarsAIR: SignalBarsView
    private val signalBarsGND: SignalBarsView
    private val tvStatusInfo: TextView
    private val startBtn: Button
    private val stopBtn: Button
    private val clearBtn: Button

    private var isExpanded = false
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var lastX: Int = 0
    private var lastY: Int = 0

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var isDragging = false

    private var airRssi1 = 0f
    private var airRssi2 = 0f
    private var airSnr = 0f
    private var gndRssi1 = 0f
    private var gndRssi2 = 0f
    private var gndSnr = 0f
    private var airHasSnr = false
    private var gndHasSnr = false

    init {
        // 加载布局
        floatView = inflate(context, R.layout.float_view, this) as LinearLayout

        // 初始化控件
        collapsedIcon = floatView.findViewById(R.id.collapsedIcon)
        topBar = floatView.findViewById(R.id.topBar)
        toggleBtn = floatView.findViewById(R.id.toggleBtn)
        signalBarsAIR = floatView.findViewById(R.id.signalBarsAIR)
        signalBarsGND = floatView.findViewById(R.id.signalBarsGND)
        tvStatusInfo = floatView.findViewById(R.id.tvStatusInfo)
        startBtn = floatView.findViewById(R.id.startBtn)
        stopBtn = floatView.findViewById(R.id.stopBtn)
        clearBtn = floatView.findViewById(R.id.clearBtn)

        // 设置折叠态为圆形
        collapsedIcon.setCircularMode(true)
        collapsedIcon.setLabel("总览")
        collapsedIcon.setOnTouchListener(this)
        collapsedIcon.setOnClickListener { performToggle() }

        // 设置展开态信号格
        signalBarsAIR.setCircularMode(false)
        signalBarsGND.setCircularMode(false)

        // 设置按钮点击事件
        toggleBtn.setOnClickListener { performToggle() }
        startBtn.setOnClickListener { startMonitoring() }
        stopBtn.setOnClickListener { stopMonitoring() }
        clearBtn.setOnClickListener { clearLog() }

        // 初始化 WindowManager 参数
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = (100 * metrics.density).toInt()
            height = (100 * metrics.density).toInt()
            gravity = Gravity.TOP or Gravity.START
            x = width - (150 * metrics.density).toInt()
            y = height / 4
        }

        // 默认折叠
        collapse()
    }

    fun show() {
        try {
            windowManager.addView(this, params)
        } catch (e: Exception) {
            Log.e("FloatView", "Failed to add view", e)
        }
    }

    fun remove() {
        try {
            windowManager.removeView(this)
        } catch (e: Exception) {
            Log.e("FloatView", "Failed to remove view", e)
        }
    }

    private fun performToggle() {
        if (isExpanded) {
            collapse()
        } else {
            expand()
        }
    }

    private fun expand() {
        isExpanded = true
        // 展开布局
        topBar.visibility = VISIBLE
        signalBarsAIR.visibility = VISIBLE
        signalBarsGND.visibility = VISIBLE
        tvStatusInfo.visibility = VISIBLE
        startBtn.visibility = VISIBLE
        stopBtn.visibility = VISIBLE
        clearBtn.visibility = VISIBLE

        // 设置展开态尺寸
        params.width = (300 * resources.displayMetrics.density).toInt()
        params.height = (250 * resources.displayMetrics.density).toInt()
        windowManager.updateViewLayout(this, params)

        // 更新信号格
        updateSignalBars()
    }

    private fun collapse() {
        isExpanded = false
        // 折叠布局
        topBar.visibility = GONE
        signalBarsAIR.visibility = GONE
        signalBarsGND.visibility = GONE
        tvStatusInfo.visibility = GONE
        startBtn.visibility = GONE
        stopBtn.visibility = GONE
        clearBtn.visibility = GONE

        // 设置折叠态尺寸（圆形图标）
        params.width = (100 * resources.displayMetrics.density).toInt()
        params.height = (100 * resources.displayMetrics.density).toInt()
        windowManager.updateViewLayout(this, params)

        // 更新总览信号
        updateCollapsedIcon()
    }

    private fun updateSignalBars() {
        // 计算 AIR 和 GND 的信号质量
        val airQuality = SignalQuality.fromRawStrings(airRssi1.toString(), airSnr.toString(), airHasSnr)
        val gndQuality = SignalQuality.fromRawStrings(gndRssi1.toString(), gndSnr.toString(), gndHasSnr)
        val totalQuality = SignalQuality.worse(airQuality, gndQuality)

        // 更新信号格
        signalBarsAIR.setSignalQuality(airQuality)
        signalBarsGND.setSignalQuality(gndQuality)

        // 更新状态文本
        tvStatusInfo.text = "AIR: ${getRssiText(airRssi1)} SNR:${getSnrText(airSnr)} | GND: ${getRssiText(gndRssi1)} SNR:${getSnrText(gndSnr)}"
    }

    private fun updateCollapsedIcon() {
        // 计算总览信号质量
        val airQuality = SignalQuality.fromRawStrings(airRssi1.toString(), airSnr.toString(), airHasSnr)
        val gndQuality = SignalQuality.fromRawStrings(gndRssi1.toString(), gndSnr.toString(), gndHasSnr)
        val totalQuality = SignalQuality.worse(airQuality, gndQuality)

        // 更新折叠态图标
        collapsedIcon.setSignalQuality(totalQuality)
        collapsedIcon.setLabel("总览 ${getQualityText(totalQuality)}")
    }

    private fun getRssiText(rssi: Float): String {
        return if (rssi == 110f) "✕" else rssi.toInt().toString()
    }

    private fun getSnrText(snr: Float): String {
        return if (snr == 0f) "✕" else snr.toInt().toString()
    }

    private fun getQualityText(quality: SignalQuality): String {
        return when (quality) {
            SignalQuality.EXCELLENT -> "5"
            SignalQuality.GOOD -> "4"
            SignalQuality.FAIR -> "3"
            SignalQuality.POOR -> "2"
            SignalQuality.BAD -> "1"
            SignalQuality.DISCONNECTED -> "✕"
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                longPressRunnable = Runnable {
                    isDragging = false
                    showDetailPopup()
                }
                handler.postDelayed(longPressRunnable!!, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                    handler.removeCallbacks(longPressRunnable!!)
                    isDragging = true

                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()

                    // 限制在屏幕内
                    val metrics = DisplayMetrics()
                    windowManager.defaultDisplay.getMetrics(metrics)
                    params.x = params.x.coerceIn(0, metrics.widthPixels - params.width)
                    params.y = params.y.coerceIn(0, metrics.heightPixels - params.height)

                    windowManager.updateViewLayout(this, params)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable!!)
                if (!isDragging && isExpanded) {
                    // 单击展开/折叠已在 collapsedIcon 处理
                }
            }
        }
        return true
    }

    private fun showDetailPopup() {
        // 这里应该显示一个 PopupWindow 或 Dialog
        // 示例：显示 AIR 和 GND 的详细数值
        val popup = PopupWindow(context)
        val content = TextView(context).apply {
            text = "AIR: rssi1=${airRssi1} rssi2=${airRssi2} snr=${airSnr}\nGND: rssi1=${gndRssi1} rssi2=${gndRssi2} snr=${gndSnr}"
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#80000000"))
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        popup.contentView = content
        popup.width = (250 * resources.displayMetrics.density).toInt()
        popup.height = WindowManager.LayoutParams.WRAP_CONTENT
        popup.isFocusable = true
        popup.showAsDropDown(this, 0, 0)
    }

    fun updateJson(json: String) {
        try {
            // 解析 JSON，提取 RSSI 和 SNR
            // 这里假设 JSON 格式为：{"air": {"rssi1": 72, "rssi2": 75, "snr": 20}, "gnd": {"rssi1": 88, "rssi2": 90, "snr": 12}}
            // 实际解析逻辑根据你的 JSON 格式调整

            // 示例解析（简化版）
            val airRssi1Str = "72" // 从 JSON 提取
            val airSnrStr = "20"    // 从 JSON 提取
            val gndRssi1Str = "88"  // 从 JSON 提取
            val gndSnrStr = "12"    // 从 JSON 提取

            airRssi1 = airRssi1Str.toFloatOrNull() ?: 110f
            airSnr = airSnrStr.toFloatOrNull() ?: 0f
            gndRssi1 = gndRssi1Str.toFloatOrNull() ?: 110f
            gndSnr = gndSnrStr.toFloatOrNull() ?: 0f

            // 判断是否有 SNR 数据
            airHasSnr = airSnrStr.isNotBlank() && airSnrStr != "0" && airSnrStr != "--"
            gndHasSnr = gndSnrStr.isNotBlank() && gndSnrStr != "0" && gndSnrStr != "--"

            // 更新 UI
            if (isExpanded) {
                updateSignalBars()
            } else {
                updateCollapsedIcon()
            }
        } catch (e: Exception) {
            Log.e("FloatView", "Failed to parse JSON", e)
        }
    }

    private fun startMonitoring() {
        // 启动监控逻辑
        tvStatusInfo.text = "监控中..."
    }

    private fun stopMonitoring() {
        // 停止监控逻辑
        tvStatusInfo.text = "已停止"
    }

    private fun clearLog() {
        // 清除日志
        tvStatusInfo.text = "日志已清除"
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return onTouchEvent(event)
    }
}
