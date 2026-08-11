package com.example.netfloatmonitor

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket

class UdpReceiver(private val port: Int, private val onData: (String) -> Unit) {

    private var socket: DatagramSocket? = null
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        
        thread = Thread {
            val buffer = ByteArray(2048)
            try {
                socket = DatagramSocket(port)
                Log.d("UdpReceiver", "UDP 服务已在端口 $port 启动监听...")

                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    val ip = packet.address?.hostAddress ?: "未知IP"
                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()

                    Log.d("UdpReceiver", "收到来自 $ip 的原始数据: $data")
                    
                    onData(data)
                }
            } catch (e: Exception) {
                Log.e("UdpReceiver", "UDP 服务运行异常: ${e.message}", e)
            } finally {
                stop()
            }
        }
        thread?.start()
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e("UdpReceiver", "关闭 Socket 异常: ${e.message}")
        }
        socket = null
        thread = null
    }
}
