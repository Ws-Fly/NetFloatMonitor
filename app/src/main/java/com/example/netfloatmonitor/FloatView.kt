package com.example.netfloatmonitor

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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

    // ── 展开态 ──
    private val airLayout = LinearLayout(context)
    private val gndLayout = LinearLayout(context)
    private val chartContainer = LinearLayout(context)
    private val airChartView = WaveformView(context, isAir = true)
    private val gndChartView = WaveformView(context, isAir = false)

    private val airSignalBars = SignalBarsView(context)
    private val gndSignalBars = SignalBarsView(context)

    // ── 折叠态圆形图标（总览信号） ──
    private val collapsedIcon = SignalBarsView(context).apply {
        setCircularMode(true)
    }

    // ── 状态 ──
    private var isExpanded = true
    private var lastExpandedWidth = 1300
    private var lastExpandedHeight = 540
    private val collapsedSize = 100  // dp

    // ── 展开态容器 ──
    private val topBar = LinearLayout(context)
    private val contentFrame = FrameLayout(context)
    private val contentPanel = LinearLayout(context)

    private val resizeIndicator = View(context).apply {
        val g = GradientDrawable().apply { setColor(Color.parseColor("#3498DB")); cornerRadius = 4f }
        background = g
        visibility = View.VISIBLE
    }

    private val toggleBtn = Button(context).apply {
        text = "×"
        textSize = 14f
        setTextColor(Color.WHITE)
        setGravity(Gravity.CENTER)
        background = GradientDrawable().apply { setColor(Color.parseColor("#C0392B")); cornerRadius = 6f }
    }

    // ── 缓存 ──
    private var airRssi1Val = "--"; private var airRssi2Val = "--"; private var airSnrVal = "--"
    private var gndRssi1Val = "--"; private var gndRssi2Val = "--"; private var gndSnrVal = "--"

    // ── 长按检测 ──
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressPending = false
    private val LONG_PRESS_DELAY = 500L

    private val longPressRunnable = Runnable {
        longPressPending = false
        // 长按 → 弹窗（在折叠态）
        if (!isExpanded) {
            try {
                collapsedIcon.showDetailPopup()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    init {
        orientation = VERTICAL
        setPadding(8, 6, 8, 8)
        background = GradientDrawable().apply { setColor(Color.argb(180, 0, 0, 0)); cornerRadius = 10f }

        // 顶部栏
        topBar.orientation = HORIZONTAL
        topBar.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        topBar.setPadding(0, 0, 4, 4)
        topBar.addView(toggleBtn, LinearLayout.LayoutParams(45, 45))
        addView(topBar)

        // 内容面板
        contentPanel.orientation = HORIZONTAL
        airLayout.orientation = VERTICAL
        gndLayout.orientation = VERTICAL

        airSignalBars.setLabel("AIR")
        airLayout.addView(airSignalBars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 6) })

        gndSignalBars.setLabel("GND")
        gndLayout.addView(gndSignalBars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 6) })

        contentPanel.addView(createPanel(airLayout))
        contentPanel.addView(createPanel(gndLayout))

        chartContainer.orientation = VERTICAL
        chartContainer.addView(airChartView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, 0, 0, 8) })
        chartContainer.addView(gndChartView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        contentPanel.addView(chartContainer, LinearLayout.LayoutParams(
            700, LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(12, 0, 4, 0) })

        contentFrame.addView(contentPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(resizeIndicator, FrameLayout.LayoutParams(15, 15).apply {
            gravity = Gravity.BOTTOM or Gravity.RIGHT; setMargins(0, 0, 4, 4)
        })

        // 折叠态圆形图标（初始隐藏）
        collapsedIcon.visibility = View.GONE
        contentFrame.addView(collapsedIcon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        airSignalBars.setQuality(SignalQuality.BAD)
        gndSignalBars.setQuality(SignalQuality.BAD)
        collapsedIcon.setQuality(SignalQuality.BAD)

        // ── × 按钮：点击折叠 ──
        toggleBtn.setOnClickListener { if (isExpanded) performToggle() }

        // ── ★ 圆形图标：触摸监听（单击展开 / 长按弹窗 / 拖动） ──
        collapsedIcon.setOnTouchListener(object : OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var downTime = 0L
            private var dragging = false
            private var paramDownX = 0
            private var paramDownY = 0

            override fun onTouch(v: View?, ev: MotionEvent): Boolean {
                if (isExpanded) return false

                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        downTime = System.currentTimeMillis()
                        dragging = false
                        paramDownX = params.x
                        paramDownY = params.y
                        // 启动长按检测
                        longPressPending = true
                        longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DELAY)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (!dragging && (Math.abs(dx) > 6 || Math.abs(dy) > 6)) {
                            dragging = true
                            // 开始拖动 → 取消长按
                            longPressHandler.removeCallbacks(longPressRunnable)
                            longPressPending = false
                        }
                        if (dragging) {
                            params.x = paramDownX + dx.toInt()
                            params.y = paramDownY + dy.toInt()
                            // 限制不超出屏幕
                            val maxY = getScreenHeight() - getNavigationBarHeight() - height
                            if (params.y > maxY) params.y = maxY
                            if (params.y < 0) params.y = 0
                            windowManager.updateViewLayout(this@FloatView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        val wasLongPress = !longPressPending && !dragging
                        val duration = System.currentTimeMillis() - downTime
                        longPressPending = false

                        if (!dragging && duration < LONG_PRESS_DELAY + 100) {
                            // 单击 → 展开
                            performToggle()
                        }
                        return true
                    }
                }
                return true
            }
        })

        // ── 展开态：整体拖动 + 缩放 ──
        setOnTouchListener(object : OnTouchListener {
            private var startW = 0; private var startH = 0
            private var downX2 = 0f; private var downY2 = 0f
            private var resizing = false

            override fun onTouch(v: View?, ev: MotionEvent): Boolean {
                if (!isExpanded) return false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX2 = ev.rawX; downY2 = ev.rawY
                        startW = width; startH = height
                        resizing = (ev.x > (width - 120)) && (ev.y > (height - 120))
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (resizing) {
                            val nw = (startW + ev.rawX - downX2).toInt().coerceAtLeast(500)
                            var nh = (startH + ev.rawY - downY2).toInt().coerceAtLeast(200)
                            val maxY = getScreenHeight() - getNavigationBarHeight()
                            if (getLocationY() + nh > maxY) nh = maxY - getLocationY()
                            params.width = nw; params.height = nh
                            lastExpandedWidth = nw; lastExpandedHeight = nh
                        } else {
                            params.x += (ev.rawX - downX2).toInt()
                            var ty = params.y + (ev.rawY - downY2).toInt()
                            val maxY = getScreenHeight() - getNavigationBarHeight() - height
                            if (ty > maxY) ty = maxY
                            params.y = ty
                            downX2 = ev.rawX; downY2 = ev.rawY
                        }
                        windowManager.updateViewLayout(this@FloatView, params)
                    }
                }
                return true
            }
        })
    }

    // ── 辅助 ──
    private fun getLocationY(): Int {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return loc[1]
    }

    private fun getNavigationBarHeight(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun getScreenHeight(): Int = context.resources.displayMetrics.heightPixels

    // ── 折叠 ↔ 展开 ──
    private fun performToggle() {
        if (isExpanded) {
            isExpanded = false
            topBar.visibility = View.GONE
            contentPanel.visibility = View.GONE
            resizeIndicator.visibility = View.GONE
            collapsedIcon.visibility = View.VISIBLE
            background = null
            setPadding(0, 0, 0, 0)
            params.width = collapsedSize
            params.height = collapsedSize
        } else {
            isExpanded = true
            topBar.visibility = View.VISIBLE
            contentPanel.visibility = View.VISIBLE
            resizeIndicator.visibility = View.VISIBLE
            collapsedIcon.visibility = View.GONE
            val bg = GradientDrawable()
            bg.setColor(Color.argb(180, 0, 0, 0))
            bg.cornerRadius = 10f
            background = bg
            setPadding(8, 6, 8, 8)
            params.width = lastExpandedWidth
            params.height = lastExpandedHeight
        }
        windowManager.updateViewLayout(this@FloatView, params)
    }

    private fun createPanel(container: LinearLayout): View {
        val box = LinearLayout(context)
        box.orientation = VERTICAL
        val scroll = ScrollView(context)
        scroll.addView(container)
        box.addView(scroll, LinearLayout.LayoutParams(300, LinearLayout.LayoutParams.MATCH_PARENT))
        return box
    }

    // ── JSON 刷新 ──
    fun updateJson(json: String) {
        try {
            if (json.isBlank()) return
            val obj = JSONObject(json)

            airLayout.removeAllViews()
            gndLayout.removeAllViews()
            airSignalBars.setLabel("AIR")
            gndSignalBars.setLabel("GND")
            airLayout.addView(airSignalBars, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) })
            gndLayout.addView(gndSignalBars, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) })

            var aR1: Float? = null; var aR2: Float? = null; var aSnr: Float? = null
            var gR1: Float? = null; var gR2: Float? = null; var gSnr: Float? = null
            var aR1s = ""; var aR2s = ""; var aSnrs = ""
            var gR1s = ""; var gR2s = ""; var gSnrs = ""

            obj.keys().forEach { key ->
                val vs = obj.get(key).toString()
                val lk = key.lowercase()
                val nv = vs.toFloatOrNull()?.let { Math.abs(it) }
                if (nv != null) {
                    when {
                        lk.endsWith("_a") -> when {
                            lk.contains("rssi1") -> { aR1 = nv; aR1s = vs }
                            lk.contains("rssi2") -> { aR2 = nv; aR2s = vs }
                            lk.contains("rssi") && aR1 == null -> { aR1 = nv; aR1s = vs }
                            lk.contains("snr") -> { aSnr = nv; aSnrs = vs }
                        }
                        lk.endsWith("_g") -> when {
                            lk.contains("rssi1") -> { gR1 = nv; gR1s = vs }
                            lk.contains("rssi2") -> { gR2 = nv; gR2s = vs }
                            lk.contains("rssi") && gR1 == null -> { gR1 = nv; gR1s = vs }
                            lk.contains("snr") -> { gSnr = nv; gSnrs = vs }
                        }
                        lk.contains("air_rssi1") -> { aR1 = nv; aR1s = vs }
                        lk.contains("air_rssi2") -> { aR2 = nv; aR2s = vs }
                        lk.contains("air_snr") -> { aSnr = nv; aSnrs = vs }
                        lk.contains("gnd_rssi1") -> { gR1 = nv; gR1s = vs }
                        lk.contains("gnd_rssi2") -> { gR2 = nv; gR2s = vs }
                        lk.contains("gnd_snr") -> { gSnr = nv; gSnrs = vs }
                    }
                }
                when {
                    key.endsWith("_g") -> addItem(gndLayout, key, vs)
                    key.endsWith("_a") -> addItem(airLayout, key, vs)
                    else -> addItem(airLayout, key, vs)
                }
            }

            val aDisc = aSnrs == "0" || aR1s == "110" || aR2s == "110"
            val gDisc = gSnrs == "0" || gR1s == "110" || gR2s == "110"
            val aR = aR1 ?: aR2; val gR = gR1 ?: gR2
            val aQ = if (aDisc) SignalQuality.DISCONNECTED else SignalQuality.fromRssiSnr(aR, aSnr, true)
            val gQ = if (gDisc) SignalQuality.DISCONNECTED else SignalQuality.fromRssiSnr(gR, gSnr, true)

            airSignalBars.setQuality(aQ)
            gndSignalBars.setQuality(gQ)

            val overallQ = SignalQuality.worse(aQ, gQ)
            collapsedIcon.setQuality(overallQ)
            collapsedIcon.setLabel("总览")

            airSignalBars.setDetailValues(
                "AIR rssi1=${aR1?.toInt() ?: "--"}",
                "AIR rssi2=${aR2?.toInt() ?: "--"}",
                "AIR snr=${aSnr?.toInt() ?: "--"} (${aQ.label})"
            )
            gndSignalBars.setDetailValues(
                "GND rssi1=${gR1?.toInt() ?: "--"}",
                "GND rssi2=${gR2?.toInt() ?: "--"}",
                "GND snr=${gSnr?.toInt() ?: "--"} (${gQ.label})"
            )
            collapsedIcon.setDetailValues(
                "AIR: rssi1=${aR1?.toInt() ?: "--"} rssi2=${aR2?.toInt() ?: "--"} snr=${aSnr?.toInt() ?: "--"}",
                "GND: rssi1=${gR1?.toInt() ?: "--"} rssi2=${gR2?.toInt() ?: "--"} snr=${gSnr?.toInt() ?: "--"}",
                "总览: ${overallQ.label} ${overallQ.bars}/5"
            )

            airRssi1Val = aR1?.toInt()?.toString() ?: "--"
            airRssi2Val = aR2?.toInt()?.toString() ?: "--"
            airSnrVal = aSnr?.toInt()?.toString() ?: "--"
            gndRssi1Val = gR1?.toInt()?.toString() ?: "--"
            gndRssi2Val = gR2?.toInt()?.toString() ?: "--"
            gndSnrVal = gSnr?.toInt()?.toString() ?: "--"

            airChartView.addData(aR1, aR2, aSnr)
            gndChartView.addData(gR1, gR2, gSnr)
        } catch (e: Exception) {
            airLayout.removeAllViews(); gndLayout.removeAllViews()
            addItem(airLayout, "JSON_ERROR", e.message ?: "Unknown")
        }
    }

    private fun addItem(layout: LinearLayout, key: String, value: String) {
        val tv = TextView(context)
        tv.text = "$key : $value"
        tv.textSize = 12f; tv.setTextColor(Color.WHITE)
        tv.setPadding(4, 3, 4, 3)
        layout.addView(tv)
    }

    // ──────────────────────────────────────
    // 波形图
    // ──────────────────────────────────────
    private class WaveformView(context: Context, private val isAir: Boolean = false) : View(context) {
        private val maxPts = 100; private val yAxisW = 85f
        private val r1L = LinkedList<Float>(); private val r2L = LinkedList<Float>(); private val sL = LinkedList<Float>()
        private val c1 = Color.parseColor("#2980B9"); private val c2 = Color.parseColor("#3498DB"); private val c3 = Color.parseColor("#2ECC71")
        private val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c1; strokeWidth = 4f; style = Paint.Style.STROKE }
        private val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c2; strokeWidth = 3f; style = Paint.Style.STROKE }
        private val p3 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c3; strokeWidth = 3f; style = Paint.Style.STROKE }
        private val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c1; textSize = 18f }
        private val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c2; textSize = 18f }
        private val t3 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c3; textSize = 18f }
        private val ax = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#BDC3C7"); textSize = 16f }
        private val gp = Paint().apply { color = Color.argb(45,255,255,255); strokeWidth = 1f }
        private val bp = Paint().apply { color = Color.argb(30,255,255,255) }

        fun addData(r1: Float?, r2: Float?, snr: Float?) {
            r1L.addLast(r1 ?: r1L.lastOrNull() ?: 0f)
            r2L.addLast(r2 ?: r2L.lastOrNull() ?: 0f)
            sL.addLast(snr ?: sL.lastOrNull() ?: 0f)
            if (r1L.size > maxPts) r1L.removeFirst()
            if (r2L.size > maxPts) r2L.removeFirst()
            if (sL.size > maxPts) sL.removeFirst()
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawRect(0f, 0f, w, h, bp)
            for (i in 0..4) { val y = h * i / 4f; canvas.drawLine(yAxisW, y, w, y, gp) }
            canvas.drawText("120", 8f, 18f, ax); canvas.drawText("0", 28f, h-8f, ax); canvas.drawText("50", w-40f, 18f, ax)
            var dy = 28f
            canvas.drawText("rssi1", 50f, dy, t1); dy += 22f
            canvas.drawText("rssi2", 50f, dy, t2); dy += 22f
            canvas.drawText("snr", 50f, dy, t3)
            val cW = w - yAxisW - 10f; val cL = yAxisW + 5f
            drawLine(canvas, r1L, 0f, 120f, cL, cW, h, p1)
            drawLine(canvas, r2L, 0f, 120f, cL, cW, h, p2)
            drawLine(canvas, sL, 0f, 50f, cL, cW, h, p3)
        }

        private fun drawLine(canvas: Canvas, list: LinkedList<Float>, mn: Float, mx: Float, lo: Float, cW: Float, h: Float, pt: Paint) {
            if (list.size < 2) return
            val range = mx - mn; val step = if (list.size > 1) cW / (maxPts - 1) else 0f
            for (i in 1 until list.size) {
                val v0 = list[i-1].coerceIn(mn, mx); val v1 = list[i].coerceIn(mn, mx)
                canvas.drawLine(lo+(i-1)*step, h-((v0-mn)/range)*h, lo+i*step, h-((v1-mn)/range)*h, pt)
            }
        }
    }
}
