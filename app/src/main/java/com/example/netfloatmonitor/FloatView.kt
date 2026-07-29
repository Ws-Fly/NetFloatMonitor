package com.example.netfloatmonitor

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class FloatView(context: Context) : LinearLayout(context) {

    private val icon = SignalBarsView(context)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var expanded = false

    private var airRssi = "110"
    private var airSnr = "0"
    private var gndRssi = "110"
    private var gndSnr = "0"

    init {
        orientation = HORIZONTAL
        addView(icon)
        icon.setCircularMode(true)
        icon.setSignalQuality(SignalQuality.DISCONNECTED)
    }

    fun update(airRssi: String, airSnr: String, gndRssi: String, gndSnr: String) {
        this.airRssi = airRssi
        this.airSnr = airSnr
        this.gndRssi = gndRssi
        this.gndSnr = gndSnr

        val air = SignalQuality.fromRawStrings(airRssi, airSnr)
        val gnd = SignalQuality.fromRawStrings(gndRssi, gndSnr)
        val total = SignalQuality.worse(air, gnd)

        icon.setSignalQuality(total)
    }

    fun showDetail() {
        Toast.makeText(
            context,
            "AIR: rssi=$airRssi snr=$airSnr\nGND: rssi=$gndRssi snr=$gndSnr",
            Toast.LENGTH_LONG
        ).show()
    }
}
