package com.example.netfloatmonitor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LogManager(private val context: Context) {

    private val logDir = File(context.getExternalFilesDir(null), "NetFloatLogs").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private var isRecording = false
    private var currentFileName: String? = null
    private val csvHeaders = mutableListOf<String>()

    private fun generateNewFileName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "log_${sdf.format(Date())}.csv"
    }

    private fun getTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date())
    }

    fun getLogPath(): String = logDir.absolutePath

    fun getLogFiles(): List<File> {
        return logDir.listFiles()?.filter { it.isFile && it.name.endsWith(".csv") }?.toList() ?: emptyList()
    }

    fun getCurrentFileName(): String {
        return currentFileName ?: "未开启监控"
    }

    @Synchronized
    fun startNewSession() {
        if (isRecording) {
            stopSession()
        }
        csvHeaders.clear()
        currentFileName = generateNewFileName()
        isRecording = true
        Log.d("LogManager", ">>> 新CSV会话开启: $currentFileName")
    }

    @Synchronized
    fun stopSession() {
        if (!isRecording) return
        isRecording = false
        currentFileName = null
        csvHeaders.clear()
        Log.d("LogManager", ">>> CSV会话已安全关闭")
    }

    @Synchronized
    fun save(jsonData: String) {
        if (!isRecording || currentFileName == null || jsonData.isBlank()) return

        try {
            val jsonObject = JSONObject(jsonData)
            val file = File(logDir, currentFileName!!)

            // 遇到本批次文件的第一条有效数据，初始化表头
            if (csvHeaders.isEmpty()) {
                csvHeaders.add("Timestamp")
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    csvHeaders.add(keys.next())
                }
                val headerLine = csvHeaders.joinToString(separator = ",") + "\n"
                file.appendText(headerLine)
            }

            // 根据表头对齐填充数据
            val rowData = mutableListOf<String>()
            rowData.add(getTime())

            for (i in 1 until csvHeaders.size) {
                val key = csvHeaders[i]
                val value = jsonObject.optString(key, "")
                
                // 处理可能存在的内部逗号以包裹双引号，防止CSV错位
                val cleanValue = if (value.contains(",")) "\"$value\"" else value
                rowData.add(cleanValue)
            }

            val dataLine = rowData.joinToString(separator = ",") + "\n"
            file.appendText(dataLine)

        } catch (e: Exception) {
            Log.e("LogManager", "解析JSON并写入CSV失败: ${e.message}")
        }
    }
}
