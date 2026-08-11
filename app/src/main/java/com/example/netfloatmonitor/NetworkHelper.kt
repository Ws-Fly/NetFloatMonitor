package com.example.netfloatmonitor

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

class NetworkHelper(private val context: Context) {

    companion object {
        private const val TAG = "NetworkHelper"
    }

    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("NetFloatMonitor_MulticastLock")
                multicastLock?.setReferenceCounted(false)
                multicastLock?.acquire()
                Log.d(TAG, "✅ 组播锁已获取")
            } else {
                Log.w(TAG, "⚠️ WifiManager 不可用")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 获取组播锁失败: ${e.message}")
        }
    }

    fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "✅ 组播锁已释放")
                }
            }
            multicastLock = null
        } catch (e: Exception) {
            Log.e(TAG, "释放组播锁异常: ${e.message}")
        }
    }

    fun isWifiConnected(): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled == true
        } catch (e: Exception) {
            false
        }
    }
}
