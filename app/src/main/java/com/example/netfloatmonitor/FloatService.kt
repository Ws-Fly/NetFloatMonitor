package com.example.netfloatmonitor

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager

class FloatService : Service() {

    private var udpReceiver: UdpReceiver? = null
    private var floatView: FloatView? = null
    private var wm: WindowManager? = null
    private lateinit var params: WindowManager.LayoutParams

    private var totalPackets = 0
    private var currentHz = 0
    private var lastCount = 0
    private var lastTime = System.currentTimeMillis()

    companion object {
        const val ACTION_STATUS = "com.example.netfloatmonitor.STATUS_UPDATE"
        const val EXTRA_AIR_RSSI = "AIR_RSSI"
        const val EXTRA_AIR_SNR = "AIR_SNR"
        const val EXTRA_GND_RSSI = "GND_RSSI"
        const val EXTRA_GND_SNR = "GND_SNR"
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams().apply {
            width = 1000
            height = 600
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            format = PixelFormat.TRANSLUCENT
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 200
        }

        floatView = FloatView(this).apply {
            windowParams = params
            windowManager = wm
        }
        wm?.addView(floatView, params)

        Log.d("FloatService", "FloatService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("PORT", 16789) ?: 16789
        startUdp(port)
        return START_STICKY
    }

    private fun startUdp(port: Int) {
        if (udpReceiver != null) return

        udpReceiver = UdpReceiver(port) { data ->
            totalPackets++

            val now = System.currentTimeMillis()
            val elapsed = now - lastTime
            if (elapsed >= 1000) {
                currentHz = ((totalPackets - lastCount) * 1000 / elapsed).toInt()
                lastCount = totalPackets
                lastTime = now
            }

            // 更新悬浮窗（主线程）
            floatView?.post {
                floatView?.updateJson(data)
            }

            // 解析 RSSI/SNR 并发广播给 MainActivity
            try {
                val obj = org.json.JSONObject(data)

                fun pick(obj: org.json.JSONObject, vararg keys: String): String {
                    for (k in keys) {
                        if (obj.has(k)) return obj.getString(k)
                    }
                    return "110"
                }

                val airRssi = pick(obj, "rssi1_a", "rssi_a", "air_rssi1")
                val airSnr = pick(obj, "snr_a", "air_snr")
                val gndRssi = pick(obj, "rssi1_g", "rssi_g", "gnd_rssi1")
                val gndSnr = pick(obj, "snr_g", "gnd_snr")

                val statusIntent = Intent(ACTION_STATUS).apply {
                    putExtra(EXTRA_AIR_RSSI, airRssi)
                    putExtra(EXTRA_AIR_SNR, airSnr)
                    putExtra(EXTRA_GND_RSSI, gndRssi)
                    putExtra(EXTRA_GND_SNR, gndSnr)
                    putExtra("TOTAL_PACKETS", totalPackets)
                    putExtra("HZ", currentHz)
                }
                androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(this).sendBroadcast(statusIntent)

            } catch (e: Exception) {
                Log.e("FloatService", "Parse error: ${e.message}")
            }

            // 日志
            val logger = LogManager.getInstance(this)
            logger.save(data)
        }
        udpReceiver?.start()
    }

    fun stopMonitoring() {
        udpReceiver?.stop()
        udpReceiver = null
        totalPackets = 0
        currentHz = 0

        val stoppedIntent = Intent(ACTION_STATUS).apply {
            putExtra("IS_STOPPED", true)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(this).sendBroadcast(stoppedIntent)
    }

    override fun onDestroy() {
        udpReceiver?.stop()
        udpReceiver = null
        if (floatView != null) {
            wm?.removeView(floatView)
            floatView = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
