package com.example.netfloatmonitor

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.util.Log
import java.util.Timer
import java.util.TimerTask

class FloatService : Service() {

    private var floatView: FloatView? = null
    private var receiver: UdpReceiver? = null
    private lateinit var logger: LogManager

    private var totalPackets = 0
    private var packetsInLastSecond = 0
    private var currentHz = 0
    private var statusTimer: Timer? = null

    // ── 缓存最新 RSSI/SNR（从 LinkStatus 解析） ──
    private var airRssi: Float? = null
    private var airSnr: Float? = null
    private var gndRssi: Float? = null
    private var gndSnr: Float? = null

    override fun onCreate() {
        super.onCreate()
        logger = LogManager(this)
        Log.d("FloatService", "Service onCreate 触发")
        createNotificationChannel()
        startForeground(1001, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("PORT", 16789) ?: 16789

        totalPackets = 0
        currentHz = 0
        airRssi = null; airSnr = null
        gndRssi = null; gndSnr = null
        logger.startNewSession()

        showFloatWindow()
        startUdpReceive(port)
        startStatusTimer()

        Handler(Looper.getMainLooper()).postDelayed({
            sendStatusBroadcast()
        }, 200)

        return START_NOT_STICKY
    }

    private fun startUdpReceive(port: Int) {
        receiver?.stop()

        receiver = UdpReceiver(port) { data ->
            try {
                totalPackets++
                packetsInLastSecond++

                logger.save(data)

                // ── 解析 JSON 提取 RSSI/SNR ──
                try {
                    val linkStatus = JsonParser.parse(data)
                    airRssi = linkStatus.airRssi1.toFloatOrNull()?.let { Math.abs(it) }
                        ?: linkStatus.airRssi2.toFloatOrNull()?.let { Math.abs(it) }
                    airSnr = linkStatus.airSnr.toFloatOrNull()
                    gndRssi = linkStatus.gndRssi1.toFloatOrNull()?.let { Math.abs(it) }
                        ?: linkStatus.gndRssi2.toFloatOrNull()?.let { Math.abs(it) }
                    gndSnr = linkStatus.gndSnr.toFloatOrNull()
                } catch (je: Exception) {
                    Log.w("FloatService", "RSSI解析跳过: ${je.message}")
                }

                floatView?.post {
                    floatView?.updateJson(data)
                }
            } catch (e: Exception) {
                Log.e("FloatService", "数据流转处理异常", e)
            }
        }

        receiver?.start()
    }

    private fun startStatusTimer() {
        statusTimer?.cancel()
        statusTimer = Timer()
        statusTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                currentHz = packetsInLastSecond
                packetsInLastSecond = 0
                sendStatusBroadcast()
            }
        }, 1000, 1000)
    }

    private fun sendStatusBroadcast() {
        val intent = Intent("com.example.netfloatmonitor.STATUS_UPDATE").apply {
            putExtra("TOTAL_PACKETS", totalPackets)
            putExtra("HZ", currentHz)
            // 附带 RSSI/SNR 供主界面信号格使用
            airRssi?.let { putExtra("AIR_RSSI", it) }
            airSnr?.let { putExtra("AIR_SNR", it) }
            gndRssi?.let { putExtra("GND_RSSI", it) }
            gndSnr?.let { putExtra("GND_SNR", it) }
        }
        LocalBroadcastManager.getInstance(this@FloatService).sendBroadcast(intent)
    }

    private fun showFloatWindow() {
        if (floatView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams()

        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.format = PixelFormat.TRANSLUCENT
        params.x = 50
        params.y = 200

        floatView = FloatView(this, wm, params)
        wm.addView(floatView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusTimer?.cancel()
        statusTimer = null

        logger.stopSession()

        val intent = Intent("com.example.netfloatmonitor.STATUS_UPDATE").apply {
            putExtra("IS_STOPPED", true)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        receiver?.stop()
        receiver = null

        if (floatView != null) {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(floatView)
            } catch (e: Exception) {
                Log.e("FloatService", "移除悬浮窗异常: ${e.message}")
            }
            floatView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "net_monitor",
                "NetFloat Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "net_monitor")
            .setContentTitle("NetFloat Monitor")
            .setContentText("UDP监听运行中")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }
}
