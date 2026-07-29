package com.example.netfloatmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.LinkedList

class FloatView(context: Context) : LinearLayout(context) {

    // ── 信号格 ──
    private val airSignalBars = SignalBarsView(context)
    private val gndSignalBars = SignalBarsView(context)

    // ── 数据展示容器 ──
    private val airLayout = LinearLayout(context)
    private val gndLayout = LinearLayout(context)

    // ── 波形图 ──
    private val airChartView = WaveformView(context, true)
    private val gndChartView = WaveformView(context, false)

    // ── 折叠态圆形图标 ──
    private val collapsedIcon = SignalBarsView(context).apply {
        setCircularMode(true)
        setSignalQuality(SignalQuality.DISCONNECTED)
    }

    // ── 展开态内容容器 ──
    private val contentPanel = LinearLayout(context)

    // ── 顶部栏 ──
    private val topBar = LinearLayout(context)

    // ── 缓存数值 ──
    private var cacheAirRssi1 = "110"
    private var cacheAirRssi2 = "--"
    private var cacheAirSnr = "0"
    private var cacheGndRssi1 = "110"
    private var cacheGndRssi2 = "--"
    private var cacheGndSnr = "0"

    // ── 窗口参数引用（用于拖动） ──
    var windowParams: android.view.WindowManager.LayoutParams? = null
    var windowManager: android.view.WindowManager? = null

    init {
        orientation = VERTICAL
        setPadding(8, 6, 8, 8)
        setBackgroundColor(Color.argb(180, 0, 0, 0))

        // ── 顶部栏 ──
        topBar.orientation = HORIZONTAL
        topBar.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        topBar.setPadding(0, 0, 4, 4)
        val toggleBtn = TextView(context).apply {
            text = "×"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 4, 16, 4)
            setOnClickListener { toggleExpanded() }
        }
        topBar.addView(toggleBtn)
        addView(topBar)

        // ── 内容面板 ──
        contentPanel.orientation = HORIZONTAL

        // AIR
        airLayout.orientation = VERTICAL
        airSignalBars.setLabel("AIR")
        airLayout.addView(airSignalBars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 6) })

        // GND
        gndLayout.orientation = VERTICAL
        gndSignalBars.setLabel("GND")
        gndLayout.addView(gndSignalBars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 6) })

        // 图表容器
        val chartContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(airChartView, LinearLayout.LayoutParams(0, 0, 1f).apply {
                setMargins(0, 0, 0, 8)
            })
            addView(gndChartView, LinearLayout.LayoutParams(0, 0, 1f))
        }

        contentPanel.addView(wrapInScroll(airLayout), LinearLayout.LayoutParams(300, LinearLayout.LayoutParams.MATCH_PARENT))
        contentPanel.addView(wrapInScroll(gndLayout), LinearLayout.LayoutParams(300, LinearLayout.LayoutParams.MATCH_PARENT))
        contentPanel.addView(chartContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(12, 0, 4, 0)
        })

        addView(contentPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        // ── 折叠态圆形图标（初始隐藏） ──
        collapsedIcon.visibility = View.GONE
        collapsedIcon.setLabel("总览")
        addView(collapsedIcon, LinearLayout.LayoutParams(100, 100).apply {
            gravity = Gravity.CENTER
        })
        collapsedIcon.visibility = View.GONE // 初始展开态不显示

        // 圆形图标触摸监听
        collapsedIcon.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var downTime = 0L
            private var dragging = false

            override fun onTouch(v: View?, ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        downTime = System.currentTimeMillis()
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (!dragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) {
                            dragging = true
                        }
                        if (dragging) {
                            windowParams?.let { p ->
                                p.x += dx.toInt()
                                p.y += dy.toInt()
                                windowManager?.updateViewLayout(this@FloatView, p)
                                downX = ev.rawX
                                downY = ev.rawY
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dur = System.currentTimeMillis() - downTime
                        return if (!dragging && dur < 500) {
                            toggleExpanded()
                            true
                        } else if (!dragging) {
                            showDetailToast()
                            true
                        } else {
                            true
                        }
                    }
                }
                return true
            }
        })

        // 初始信号格
        airSignalBars.setSignalQuality(SignalQuality.BAD)
        gndSignalBars.setSignalQuality(SignalQuality.BAD)
    }

    private fun wrapInScroll(layout: LinearLayout): ScrollView {
        val scroll = ScrollView(context)
        scroll.addView(layout)
        return scroll
    }

    private fun toggleExpanded() {
        val isExpanded = contentPanel.visibility == View.VISIBLE
        if (isExpanded) {
            // 折叠
            contentPanel.visibility = View.GONE
            topBar.visibility = View.GONE
            collapsedIcon.visibility = View.VISIBLE
            // 缩小窗口
            windowParams?.let { p ->
                p.width = 100
                p.height = 100
                windowManager?.updateViewLayout(this, p)
            }
        } else {
            // 展开
            collapsedIcon.visibility = View.GONE
            contentPanel.visibility = View.VISIBLE
            topBar.visibility = View.VISIBLE
            // 恢复窗口
            windowParams?.let { p ->
                p.width = 1000
                p.height = 600
                windowManager?.updateViewLayout(this, p)
            }
        }
    }

    /**
     * 核心方法：从 JSON 解析 RSSI/SNR 并更新信号格
     */
    fun updateJson(json: String) {
        if (json.isBlank()) return
        try {
            val obj = JSONObject(json)

            cacheAirRssi1 = obj.optString("rssi1_a", "110")
            cacheAirRssi2 = obj.optString("rssi2_a", "--")
            cacheAirSnr = obj.optString("snr_a", "0")
            cacheGndRssi1 = obj.optString("rssi1_g", "110")
            cacheGndRssi2 = obj.optString("rssi2_g", "--")
            cacheGndSnr = obj.optString("snr_g", "0")

            val airDisc = cacheAirSnr == "0" || cacheAirRssi1 == "110" || cacheAirRssi2 == "110"
            val gndDisc = cacheGndSnr == "0" || cacheGndRssi1 == "110" || cacheGndRssi2 == "110"

            val airQ = if (airDisc) SignalQuality.DISCONNECTED
                       else SignalQuality.fromRawStrings(cacheAirRssi1, cacheAirSnr)
            val gndQ = if (gndDisc) SignalQuality.DISCONNECTED
                       else SignalQuality.fromRawStrings(cacheGndRssi1, cacheGndSnr)
            val totalQ = SignalQuality.worse(airQ, gndQ)

            airSignalBars.setSignalQuality(airQ)
            gndSignalBars.setSignalQuality(gndQ)
            collapsedIcon.setSignalQuality(totalQ)

            val aR1 = cacheAirRssi1.toFloatOrNull()
            val aR2 = cacheAirRssi2.toFloatOrNull()
            val aSnr = cacheAirSnr.toFloatOrNull()
            val gR1 = cacheGndRssi1.toFloatOrNull()
            val gR2 = cacheGndRssi2.toFloatOrNull()
            val gSnr = cacheGndSnr.toFloatOrNull()
            airChartView.addData(aR1, aR2, aSnr)
            gndChartView.addData(gR1, gR2, gSnr)

            rebuildDataLists(obj)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun rebuildDataLists(obj: JSONObject) {
        airLayout.removeAllViews()
        gndLayout.removeAllViews()

        airSignalBars.setLabel("AIR")
        airLayout.addView(airSignalBars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 6) })

        gndSignalBars.setLabel("GND")
        gndLayout.addView(gndSignalBars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 6) })

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.get(key).toString()
            val lowerKey = key.lowercase()
            val target = if (lowerKey.endsWith("_g")) gndLayout else airLayout
            addItem(target, key, value)
        }
    }

    private fun addItem(layout: LinearLayout, key: String, value: String) {
        val tv = TextView(context).apply {
            text = "$key : $value"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(4, 3, 4, 3)
        }
        layout.addView(tv)
    }

    private fun showDetailToast() {
        Toast.makeText(
            context,
            "AIR: rssi1=$cacheAirRssi1 rssi2=$cacheAirRssi2 snr=$cacheAirSnr\n" +
            "GND: rssi1=$cacheGndRssi1 rssi2=$cacheGndRssi2 snr=$cacheGndSnr",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 供 FloatService 调用的简化接口
     */
    fun update(airRssi: String, airSnr: String, gndRssi: String, gndSnr: String) {
        cacheAirRssi1 = airRssi
        cacheAirSnr = airSnr
        cacheGndRssi1 = gndRssi
        cacheGndSnr = gndSnr

        val airQ = SignalQuality.fromRawStrings(airRssi, airSnr)
        val gndQ = SignalQuality.fromRawStrings(gndRssi, gndSnr)
        val totalQ = SignalQuality.worse(airQ, gndQ)

        airSignalBars.setSignalQuality(airQ)
        gndSignalBars.setSignalQuality(gndQ)
        collapsedIcon.setSignalQuality(totalQ)
    }

    fun showDetail(airRssi: String, airSnr: String, gndRssi: String, gndSnr: String) {
        Toast.makeText(
            context,
            "AIR: rssi=$airRssi snr=$airSnr\nGND: rssi=$gndRssi snr=$gndSnr",
            Toast.LENGTH_LONG
        ).show()
    }

    // ─────────────────────────────────────
    // 波形图
    // ─────────────────────────────────────
    private class WaveformView(context: Context, private val isAir: Boolean) : View(context) {
        private val maxPts = 100
        private val yAxisW = 85f
        private val r1L = LinkedList<Float>()
        private val r2L = LinkedList<Float>()
        private val sL = LinkedList<Float>()
        private val c1 = Color.parseColor("#2980B9")
        private val c2 = Color.parseColor("#3498DB")
        private val c3 = Color.parseColor("#2ECC71")
        private val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c1; strokeWidth = 4f; style = Paint.Style.STROKE }
        private val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c2; strokeWidth = 3f; style = Paint.Style.STROKE }
        private val p3 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c3; strokeWidth = 3f; style = Paint.Style.STROKE }
        private val ax = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#BDC3C7"); textSize = 16f }
        private val gp = Paint().apply { color = Color.argb(45, 255, 255, 255); strokeWidth = 1f }

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
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            for (i in 0..4) {
                val y = h * i / 4f
                canvas.drawLine(yAxisW, y, w, y, gp)
            }
            canvas.drawText("120", 8f, 18f, ax)
            canvas.drawText("0", 28f, h - 8f, ax)

            drawLine(canvas, r1L, 0f, 120f, yAxisW + 5f, h, p1)
            drawLine(canvas, r2L, 0f, 120f, yAxisW + 5f, h, p2)
            drawLine(canvas, sL, 0f, 50f, yAxisW + 5f, h, p3)
        }

        private fun drawLine(canvas: Canvas, list: LinkedList<Float>, mn: Float, mx: Float, lo: Float, h: Float, pt: Paint) {
            if (list.size < 2) return
            val range = mx - mn
            if (range <= 0) return
            val step = (lo + (list.size - 1) * 10f - lo) / (list.size - 1).coerceAtLeast(1)
            for (i in 1 until list.size) {
                val v0 = list[i - 1].coerceIn(mn, mx)
                val v1 = list[i].coerceIn(mn, mx)
                val x0 = lo + (i - 1) * step
                val x1 = lo + i * step
                val y0 = h - ((v0 - mn) / range) * h
                val y1 = h - ((v1 - mn) / range) * h
                canvas.drawLine(x0, y0, x1, y1, pt)
            }
        }
    }
}
