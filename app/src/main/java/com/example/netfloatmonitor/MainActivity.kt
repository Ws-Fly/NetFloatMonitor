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

    // ── 信号格控件 ──
    private lateinit var airSignalBars: SignalBarsView
    private lateinit var gndSignalBars: SignalBarsView
    private lateinit var overallSignalBars: SignalBarsView
    private lateinit var airSignalLabel: TextView
    private lateinit var gndSignalLabel: TextView
    private lateinit var overallSignalLabel: TextView

    // 缓存最新值
    private var latestAirRssi: Float? = null
    private var latestAirSnr: Float? = null
    private var latestGndRssi: Float? = null
    private var latestGndSnr: Float? = null
    private var latestAirHasSnr = false
    private var latestGndHasSnr = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val isStopped = intent.getBooleanExtra("IS_STOPPED", false)
            if (isStopped) {
                tvStatusInfo.text = "链路状态: 已停止\n当前文件: 未开启监控\n已收数据: 0 包 | 速率: 0 Hz"
                airSignalBars.setQuality(SignalQuality.BAD)
                gndSignalBars.setQuality(SignalQuality.BAD)
                overallSignalBars.setQuality(SignalQuality.BAD)
                airSignalLabel.text = "--"
                gndSignalLabel.text = "--"
                overallSignalLabel.text = "等待数据..."
                latestAirRssi = null; latestAirSnr = null; latestAirHasSnr = false
                latestGndRssi = null; latestGndSnr = null; latestGndHasSnr = false
                return
            }

            val total = intent.getIntExtra("TOTAL_PACKETS", 0)
            val hz = intent.getIntExtra("HZ", 0)

            // 从广播读取 RSSI/SNR
            val airRssi = intent.getFloatExtra("AIR_RSSI", Float.NaN).let { if (it.isNaN()) null else it }
            val airSnr = intent.getFloatExtra("AIR_SNR", Float.NaN).let { if (it.isNaN()) null else it }
            val gndRssi = intent.getFloatExtra("GND_RSSI", Float.NaN).let { if (it.isNaN()) null else it }
            val gndSnr = intent.getFloatExtra("GND_SNR", Float.NaN).let { if (it.isNaN()) null else it }
            val airHasSnr = intent.getBooleanExtra("AIR_HAS_SNR", latestAirHasSnr)
            val gndHasSnr = intent.getBooleanExtra("GND_HAS_SNR", latestGndHasSnr)

            if (airRssi != null) latestAirRssi = airRssi
            if (airSnr != null) latestAirSnr = airSnr
            if (gndRssi != null) latestGndRssi = gndRssi
            if (gndSnr != null) latestGndSnr = gndSnr
            latestAirHasSnr = airHasSnr
            latestGndHasSnr = gndHasSnr

            updateSignalBars()

            val currentFile = logManager.getCurrentFileName()
            tvStatusInfo.text = """
                链路状态: 正在监听...
                当前文件: $currentFile
                已收数据: $total 包 | 速率: $hz Hz
            """.trimIndent()
        }
    }

    private fun updateSignalBars() {
        // 断链判定：SNR=0 或 RSSI=110
        val airDisconnected = (latestAirSnr == 0f) || (latestAirRssi == 110f)
        val gndDisconnected = (latestGndSnr == 0f) || (latestGndRssi == 110f)

        val airQ = if (airDisconnected) SignalQuality.DISCONNECTED
                   else SignalQuality.fromRssiSnr(latestAirRssi, latestAirSnr, latestAirHasSnr)
        val gndQ = if (gndDisconnected) SignalQuality.DISCONNECTED
                   else SignalQuality.fromRssiSnr(latestGndRssi, latestGndSnr, latestGndHasSnr)
        val overallQ = SignalQuality.worse(airQ, gndQ)

        airSignalBars.setQuality(airQ)
        gndSignalBars.setQuality(gndQ)
        overallSignalBars.setQuality(overallQ)

        // 断链时显示 ✕
        if (airQ.isDisconnected) {
            airSignalLabel.text = "✕ 断链"
        } else {
            airSignalLabel.text = "${airQ.bars}格/${airQ.label}\nrssi=${latestAirRssi?.toInt() ?: "--"} snr=${latestAirSnr?.toInt() ?: "--"}"
        }
        if (gndQ.isDisconnected) {
            gndSignalLabel.text = "✕ 断链"
        } else {
            gndSignalLabel.text = "${gndQ.bars}格/${gndQ.label}\nrssi=${latestGndRssi?.toInt() ?: "--"} snr=${latestGndSnr?.toInt() ?: "--"}"
        }
        overallSignalLabel.text = if (overallQ.isDisconnected) "✕ 断链" else "${overallQ.bars}格 · ${overallQ.label}"

        // 弹窗数据
        airSignalBars.setDetailValues(
            latestAirRssi?.toInt()?.toString() ?: "--",
            "--",  // airRssi2 主界面暂不单独解析，悬浮窗里有
            latestAirSnr?.toInt()?.toString() ?: "--"
        )
        gndSignalBars.setDetailValues(
            latestGndRssi?.toInt()?.toString() ?: "--",
            "--",
            latestGndSnr?.toInt()?.toString() ?: "--"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logManager = LogManager(this)

        ipEdit = findViewById(R.id.editIp)
        portEdit = findViewById(R.id.editPort)
        logPath = findViewById(R.id.logPath)
        tvStatusInfo = findViewById(R.id.tvStatusInfo)

        // 信号格控件
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

        tvStatusInfo.text = "链路状态: 待机\n当前文件: 未开启监控\n已收数据: 0 包 | 速率: 0 Hz"
        airSignalBars.setQuality(SignalQuality.BAD)
        gndSignalBars.setQuality(SignalQuality.BAD)
        overallSignalBars.setQuality(SignalQuality.BAD)

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
            if (file.exists() && file.delete()) deletedCount++
        }
        Toast.makeText(
            this,
            if (deletedCount > 0) "已成功清除 $deletedCount 个历史CSV表格" else "没有需要清除的历史数据",
            Toast.LENGTH_SHORT
        ).show()
    }
}
