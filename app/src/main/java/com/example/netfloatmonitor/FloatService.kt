package com.example.netfloatmonitor

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.charset.Charset

class FloatService : Service() {

    private var running = false
    private var socket: DatagramSocket? = null
    private var floatView: FloatView? = null

    companion object {
        const val ACTION_SIGNAL_UPDATE = "com.example.netfloatmonitor.ACTION_SIGNAL_UPDATE"
        const val EXTRA_AIR_RSSI = "air_rssi"
        const val EXTRA_AIR_SNR = "air_snr"
        const val EXTRA_GND_RSSI = "gnd_rssi"
        const val EXTRA_GND_SNR = "gnd_snr"
    }

    override fun onCreate() {
        super.onCreate()
        floatView = FloatView(this)
        floatView?.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            startUdp()
        }
        return START_STICKY
    }

    private fun startUdp() {
        Thread {
            try {
                socket = DatagramSocket(8888)
                val buffer = ByteArray(1024)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val json = String(packet.data, 0, packet.length, Charset.forName("UTF-8"))
                    handleJson(json)
                }
            } catch (e: Exception) {
                Log.e("FloatService", "UDP error", e)
            }
        }.start()
    }

    private fun handleJson(json: String) {
        var airRssi = "110"
        var airSnr = "0"
        var gndRssi = "110"
        var gndSnr = "0"

        try {
            if (json.contains("rssi1_a")) {
                airRssi = json.substringAfter("\"rssi1_a\":\"").substringBefore("\"")
            }
            if (json.contains("snr_a")) {
                airSnr = json.substringAfter("\"snr_a\":\"").substringBefore("\"")
            }
            if (json.contains("rssi1_g")) {
                gndRssi = json.substringAfter("\"rssi1_g\":\"").substringBefore("\"")
            }
            if (json.contains("snr_g")) {
                gndSnr = json.substringAfter("\"snr_g\":\"").substringBefore("\"")
            }
        } catch (_: Exception) {}

        floatView?.update(airRssi, airSnr, gndRssi, gndSnr)

        val intent = Intent(ACTION_SIGNAL_UPDATE).apply {
            putExtra(EXTRA_AIR_RSSI, airRssi)
            putExtra(EXTRA_AIR_SNR, airSnr)
            putExtra(EXTRA_GND_RSSI, gndRssi)
            putExtra(EXTRA_GND_SNR, gndSnr)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        floatView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
