package com.example.netfloatmonitor

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LogManager(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
    private var currentLogFile: File? = null

    fun startNewSession() {
        val dir = getLogDir()
        if (!dir.exists()) dir.mkdirs()
        val fileName = "link_${dateFormat.format(Date())}.csv"
        currentLogFile = File(dir, fileName)
        // 写 CSV 表头
        currentLogFile?.appendText("timestamp,rssi1_a,rssi2_a,snr_a,rssi1_g,rssi2_g,sr_g\n")
    }

    fun save(data: String) {
        try {
            val file = currentLogFile ?: return
            val timestamp = System.currentTimeMillis()
            // 简单 CSV：时间戳 + 原始 JSON
            file.appendText("$timestamp,$data\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCurrentFileName(): String {
        return currentLogFile?.name ?: "未创建"
    }

    fun getLogPath(): String {
        return getLogDir().absolutePath
    }

    fun getLogFiles(): List<File> {
        val dir = getLogDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.toList() ?: emptyList()
    }

    private fun getLogDir(): File {
        return File(context.getExternalFilesDir(null), "logs")
    }

    companion object {
        @Volatile
        private var instance: LogManager? = null

        fun getInstance(context: Context): LogManager {
            return instance ?: synchronized(this) {
                instance ?: LogManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
