package com.example.netfloatmonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.charset.Charset

class FloatService : Service() {

    companion object {
        const val ACTION_SIGNAL_UPDATE = "com.example.netfloatmonitor.SIGNAL_UPDATE"
        const val EXTRA_AIR_RSSI = "air_rssi"
        const val EXTRA_AIR_SNR = "air_snr"
        const val EXTRA_GND_RSSI = "gnd_rssi"
        const val EXTRA_GND_SNR = "gnd_snr"

        const val PREFS_NAME = "netfloat_prefs"
        const val KEY_IP = "ip"
        const val KEY_PORT = "port"
    }

    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var running = false

    private var floatView: FloatView? = null

    override fun onCreate() {
        super.onCreate()
        floatView = FloatView(this)
        floatView?.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ip = prefs.getString(KEY_IP, "0.0.0.0") ?: "0.0.0.0"
        val port = prefs.getInt(KEY_PORT, 8080)

        startUdp(port)
        return START_STICKY
    }

    private fun startUdp(port: Int) {
        if (running) return
        running = true

        receiveThread = Thread {
            try {
                socket = DatagramSocket(port)
                val buf = ByteArray(4096)

                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet)

                    val json = String(packet.data, 0, packet.length, Charset.forName("UTF-8"))
                    handleJson(json)
                }
            } catch (e: Exception) {
                Log.e("FloatService", "UDP recv error", e)
            }
        }.also { it.start() }
    }

    private fun handleJson(json: String) {
        // 你原仓库的解析入口，不要改 JsonParser 本身
        val status = JsonParser.parse(json)

        // ---- 抽字段（字符串原样保留，断链判定交给 SignalQuality）----
        val airRssi = status.rssi1_a ?: "110"
        val airSnr = status.snr_a ?: "0"
        val gndRssi = status.rssi1_g ?: "110"
        val gndSnr = status.snr_g ?: "0"

        // ---- 刷新悬浮窗圆形总览 ----
        floatView?.update(airRsti = airRssi, airSnr = airSnr, gndRssi = gndRssi, gndSnr = gndSnr)

        // ---- 广播给 MainActivity ----
        val broadcast = Intent(ACTION_SIGNAL_UPDATE).apply {
            putExtra(EXTRA_AIR_RSSI, airRssi)
            putExtra(EXTRA_AIR_SNR, airSnr)
            putExtra(EXTRA_GND_RSSI, gndRssi)
            putExtra(EXTRA_GND_SNR, gndSnr)
        }
        sendBroadcast(broadcast)
    }

    override fun onDestroy() {
        running = false
        receiveThread?.interrupt()
        receiveThread = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null

        floatView?.remove()
        floatView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
