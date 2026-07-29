package com.example.netfloatmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var ipEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var logPath: TextView
    private lateinit var logManager: LogManager
    private lateinit var tvStatusInfo: TextView

    private lateinit var airSignalBars: SignalBarsView
    private lateinit var gndSignalBars: SignalBarsView
    private lateinit var overallSignalBars: SignalBarsView
    private lateinit var airSignalLabel: TextView
    private lateinit var gndSignalLabel: TextView
    private lateinit var overallSignalLabel: TextView

    private var latestAirRssi: String? = null
    private var latestAirSnr: String? = null
    private var latestGndRssi: String? = null
    private var latestGndSnr: String? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val isStopped = intent.getBooleanExtra("IS_STOPPED", false)
            if (isStopped) {
                resetSignalUI("已停止")
                return
            }

            val total = intent.getIntExtra("TOTAL_PACKETS", 0)
            val hz = intent.getIntExtra("HZ", 0)

            latestAirRssi = intent.getStringExtra("AIR_RSSI")
            latestAirSnr = intent.getStringExtra("AIR_SNR")
            latestGndRssi = intent.getStringExtra("GND_RSSI")
            latestGndSnr = intent.getStringExtra("GND_SNR")

            updateSignalBars()

            val currentFile = logManager.getCurrentFileName()
            tvStatusInfo.text = """
                链路状态: 正在监听...
                当前文件: $currentFile
                已收数据: $total 包 | 速率: $hz Hz
            """.trimIndent()
        }
    }

    private fun resetSignalUI(reason: String) {
        tvStatusInfo.text = "链路状态: $reason\n当前文件: 未开启监控\n已收数据: 0 包 | 速率: 0 Hz"
        airSignalBars.setSignalQuality(SignalQuality.BAD)
        gndSignalBars.setSignalQuality(SignalQuality.BAD)
        overallSignalBars.setSignalQuality(SignalQuality.BAD)
        airSignalLabel.text = "--"
        gndSignalLabel.text = "--"
        overallSignalLabel.text = "等待数据..."
        latestAirRssi = null
        latestAirSnr = null
        latestGndRssi = null
        latestGndSnr = null
    }

    private fun updateSignalBars() {
        val airQ = SignalQuality.fromRawStrings(latestAirRssi, latestAirSnr)
        val gndQ = SignalQuality.fromRawStrings(latestGndRssi, latestGndSnr)
        val overallQ = SignalQuality.worse(airQ, gndQ)

        airSignalBars.setSignalQuality(airQ)
        gndSignalBars.setSignalQuality(gndQ)
        overallSignalBars.setSignalQuality(overallQ)

        airSignalLabel.text = if (airQ == SignalQuality.DISCONNECTED) {
            "✕ 断链"
        } else {
            "rssi=${latestAirRssi ?: "--"} snr=${latestAirSnr ?: "--"}"
        }

        gndSignalLabel.text = if (gndQ == SignalQuality.DISCONNECTED) {
            "✕ 断链"
        } else {
            "rssi=${latestGndRssi ?: "--"} snr=${latestGndSnr ?: "--"}"
        }

        overallSignalLabel.text = if (overallQ == SignalQuality.DISCONNECTED) {
            "✕ 断链"
        } else {
            when (overallQ) {
                SignalQuality.EXCELLENT -> "5格 · 极佳"
                SignalQuality.GOOD -> "4格 · 良好"
                SignalQuality.FAIR -> "3格 · 一般"
                SignalQuality.POOR -> "2格 · 稍差"
                SignalQuality.BAD -> "1格 · 极差"
                else -> "--"
            }
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

        airSignalBars = findViewById(R.id.airSignalBars)
        gndSignalBars = findViewById(R.id.gndSignalBars)
        overallSignalBars = findViewById(R.id.overallSignalBars)
        airSignalLabel = findViewById(R.id.airSignalLabel)
        gndSignalLabel = findViewById(R.id.gndSignalLabel)
        overallSignalLabel = findViewById(R.id.overallSignalLabel)

        val startBtn = findViewById<Button>(R.id.startBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)
        val clearBtn = findViewById<Button>(R.id.clearBtn)

        loadConfig()
        showLogPath()
        resetSignalUI("待机")

        startBtn.setOnClickListener {
            saveConfig()

            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(this, "请开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val port = portEdit.text.toString().toIntOrNull() ?: 16789
            logManager.startNewSession()

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
        getSharedPreferences("net_config", Context.MODE_PRIVATE).edit().apply {
            putString("ip", ipEdit.text.toString())
            putString("port", portEdit.text.toString())
            apply()
        }
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
        val deleted = logManager.getLogFiles().count { it.exists() && it.delete() }
        Toast.makeText(
            this,
            if (deleted > 0) "已清除 $deleted 个历史CSV" else "无历史数据",
            Toast.LENGTH_SHORT
        ).show()
    }
}
