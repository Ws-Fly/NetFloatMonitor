package com.example.netfloatmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var ipEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var logPath: TextView
    private lateinit var logManager: LogManager
    private lateinit var tvStatusInfo: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val isStopped = intent.getBooleanExtra("IS_STOPPED", false)
            if (isStopped) {
                tvStatusInfo.text = "链路状态: 已停止\n当前文件: 未开启监控\n已收数据: 0 包 | 速率: 0 Hz"
                return
            }

            val total = intent.getIntExtra("TOTAL_PACKETS", 0)
            val hz = intent.getIntExtra("HZ", 0)
            val currentFile = logManager.getCurrentFileName()

            tvStatusInfo.text = """
                链路状态: 正在监听...
                当前文件: $currentFile
                已收数据: $total 包 | 速率: $hz Hz
            """.trimIndent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logManager = LogManager(this)

        ipEdit = findViewById(R.id.editIp)
        portEdit = findViewById(R.id.editPort)
        logPath = findViewById(R.id.logPath)
        tvStatusInfo = findViewById(R.id.tvStatusInfo)

        val startBtn = findViewById<Button>(R.id.startBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)
        val clearBtn = findViewById<Button>(R.id.clearBtn)

        loadConfig()
        showLogPath()

        tvStatusInfo.text = "链路状态: 待机\n当前文件: 未开启监控\n已收数据: 0 包 | 速率: 0 Hz"

        startBtn.setOnClickListener {
            saveConfig()

            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "请开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val port = portEdit.text.toString().toIntOrNull() ?: 16789

            logManager.startNewSession()
            val previewFile = logManager.getCurrentFileName()
            tvStatusInfo.text = """
                链路状态: 正在初始化...
                当前文件: $previewFile
                已收数据: 0 包 | 速率: 0 Hz
            """.trimIndent()

            val serviceIntent = Intent(this, FloatService::class.java).apply {
                putExtra("PORT", port)
                putExtra("IP", ipEdit.text.toString())
            }

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "UDP监听启动 端口:$port", Toast.LENGTH_SHORT).show()
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, FloatService::class.java))
            Toast.makeText(this, "监听已停止，CSV表格已封存", Toast.LENGTH_SHORT).show()
        }

        clearBtn.setOnClickListener {
            clearLog()
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver,
            IntentFilter("com.example.netfloatmonitor.STATUS_UPDATE")
        )
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    private fun saveConfig() {
        getSharedPreferences("net_config", Context.MODE_PRIVATE)
            .edit()
            .putString("ip", ipEdit.text.toString())
            .putString("port", portEdit.text.toString())
            .apply()
    }

    private fun loadConfig() {
        val sp = getSharedPreferences("net_config", Context.MODE_PRIVATE)
        ipEdit.setText(sp.getString("ip", "192.168.144.33"))
        portEdit.setText(sp.getString("port", "16789"))
    }

    private fun showLogPath() {
        logPath.text = "日志目录:\n${logManager.getLogPath()}"
    }

    private fun clearLog() {
        val files: List<File> = logManager.getLogFiles()
        var deletedCount = 0

        files.forEach { file ->
            if (file.exists() && file.delete()) {
                deletedCount++
            }
        }

        Toast.makeText(
            this,
            if (deletedCount > 0) "已成功清除 $deletedCount 个历史CSV表格" else "没有需要清除的历史数据",
            Toast.LENGTH_SHORT
        ).show()
    }
}
