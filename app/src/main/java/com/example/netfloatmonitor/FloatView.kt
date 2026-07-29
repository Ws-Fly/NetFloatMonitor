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

    /**
     * ✅ 所有数据都从参数进来
     * ✅ 不访问任何成员变量
     */
    fun update(
        airRssi: String,
        airSnr: String,
        gndRssi: String,
        gndSnr: String
    ) {
        val airQ = SignalQuality.fromRawStrings(airRssi, airSnr)
        val gndQ = SignalQuality.fromRawStrings(gndRssi, gndSnr)
        val totalQ = SignalQuality.worse(airQ, gndQ)

        icon.setSignalQuality(totalQ)
    }

    fun showDetail() {
        Toast.makeText(
            context,
            "AIR: rssi=$airRssi snr=$airSnr\nGND: rssi=$gndRssi snr=$gndSnr",
            Toast.LENGTH_LONG
        ).show()
    }
}
