package com.example.netfloatmonitor

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast

class FloatView(context: Context) : LinearLayout(context) {

    private val icon = SignalBarsView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addView(icon)
        icon.setCircularMode(true)
        icon.setSignalQuality(SignalQuality.DISCONNECTED)
    }

    fun update(airRssi: String, airSnr: String, gndRssi: String, gndSnr: String) {
        val air = SignalQuality.fromRawStrings(airRssi, airSnr)
        val gnd = SignalQuality.fromRawStrings(gndRssi, gndSnr)
        val total = SignalQuality.worse(air, gnd)

        icon.setSignalQuality(total)
    }

    fun show() {
        // 如果 WindowManager 添加逻辑在这里，可以留空
        // 实际添加由 FloatService 控制
    }

    fun remove() {
        // 移除逻辑由 FloatService 控制
    }

    fun showDetail() {
        Toast.makeText(
            context,
            "AIR: rssi=$airRssi snr=$airSnr\nGND: rssi=$gndRssi snr=$gndSnr",
            Toast.LENGTH_LONG
        ).show()
    }
}
