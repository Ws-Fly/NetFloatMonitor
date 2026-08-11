package com.example.netfloatmonitor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class LogManager(private val context: Context) {

    private val logDir = File(context.getExternalFilesDir(null), "NetFloatLogs").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val isRecording = AtomicBoolean(false)
    private var currentFileName: String? = null
    private val csvHeaders = mutableListOf<String>()
    
    private val dataQueue = LinkedBlockingQueue<String>()
    private var consumerThread: Thread? = null

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

    fun startNewSession() {
        if (isRecording.get()) {
            stopSession()
        }
        csvHeaders.clear()
        dataQueue.clear()
        
        currentFileName = generateNewFileName()
        isRecording.set(true)
        
        consumerThread = Thread({
            Log.d("LogManager", ">>> 异步日志消费线程启动成功")
            while (isRecording.get() || dataQueue.isNotEmpty()) {
                try {
                    val data = dataQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (data != null) {
                        processAndWrite(data)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("LogManager", "异步处理日志异常: ${e.message}")
                }
            }
            Log.d("LogManager", ">>> 异步日志消费线程安全退出")
        }, "NetLogConsumer-Thread").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
        
        Log.d("LogManager", ">>> 新CSV会话开启: $currentFileName")
    }

    fun stopSession() {
        if (!isRecording.get()) return
        isRecording.set(false)
        consumerThread?.interrupt()
        consumerThread = null
        currentFileName = null
        csvHeaders.clear()
        Log.d("LogManager", ">>> CSV会话已关闭，触发消费者线程退出信号")
    }

    fun save(jsonData: String) {
        if (!isRecording.get() || currentFileName == null || jsonData.isBlank()) return
        dataQueue.offer(jsonData)
    }

    private fun processAndWrite(jsonData: String) {
        val name = currentFileName ?: return
        try {
            val jsonObject = JSONObject(jsonData)
            val file = File(logDir, name)

            if (csvHeaders.isEmpty()) {
                csvHeaders.add("Timestamp")
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    csvHeaders.add(keys.next())
                }
                val headerLine = csvHeaders.joinToString(separator = ",") + "\n"
                file.appendText(headerLine)
            }

            val rowData = ArrayList<String>(csvHeaders.size)
            rowData.add(getTime())

            for (i in 1 until csvHeaders.size) {
                val key = csvHeaders[i]
                val value = jsonObject.optString(key, "")
                val cleanValue = if (value.contains(",")) "\"$value\"" else value
                rowData.add(cleanValue)
            }

            val dataLine = rowData.joinToString(separator = ",") + "\n"
            file.appendText(dataLine)

        } catch (e: Exception) {
            Log.e("LogManager", "落盘写入失败: ${e.message}")
        }
    }
}
