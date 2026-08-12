package com.example.netfloatmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var etMulticastIp: EditText
    private lateinit var etMulticastPort: EditText
    private lateinit var spinnerCodec: Spinner
    private lateinit var spinnerSampleRate: Spinner
    private lateinit var switchPrompt: Switch
    private lateinit var btnVoiceStart: Button
    private lateinit var btnVoiceStop: Button
    private lateinit var btnPtt: Button
    private lateinit var tvVoiceStatus: TextView
    private lateinit var tvVoiceRole: TextView

    private var isVoiceRunning = false
    private var currentRole = 1
    private var isMuted = false

    private val REQUEST_RECORD_AUDIO = 1001

    private val voiceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                "com.example.netfloatmonitor.VOICE_STATUS" -> {
                    isVoiceRunning = intent.getBooleanExtra("RUNNING", false)
                    currentRole = intent.getIntExtra("ROLE", 1)
                    updateUI()
                }
                "com.example.netfloatmonitor.VOICE_ROLE_CHANGE" -> {
                    currentRole = intent.getIntExtra("ROLE", 1)
                    updateUI()
                    val roleText = if (currentRole == 0) "飞行员 🎤" else "观察者 🎧"
                    Toast.makeText(
                        this@VoiceSettingsActivity,
                        "🔄 角色切换: $roleText",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                "com.example.netfloatmonitor.VOICE_PTT_STATE" -> {
                    isMuted = intent.getBooleanExtra("MUTED", false)
                    updatePttButton()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setupUI()
        
        loadConfig()
        registerReceivers()
        setupListeners()
        updateUI()
        
        checkRecordPermission()
    }

    private fun setupUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // 标题
        val titleView = TextView(this).apply {
            text = "🎤 语音对讲设置"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(titleView)

        // 组播地址
        val ipLabel = TextView(this).apply {
            text = "组播地址"
            textSize = 16f
        }
        rootLayout.addView(ipLabel)

        etMulticastIp = EditText(this).apply {
            setText("224.0.0.1")
            setPadding(16, 16, 16, 16)
        }
        rootLayout.addView(etMulticastIp)

        // 组播端口
        val portLabel = TextView(this).apply {
            text = "组播端口"
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }
        rootLayout.addView(portLabel)

        etMulticastPort = EditText(this).apply {
            setText("50000")
            setPadding(16, 16, 16, 16)
        }
        rootLayout.addView(etMulticastPort)

        // 编解码格式（下拉选择）
        val codecLabel = TextView(this).apply {
            text = "编解码格式"
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }
        rootLayout.addView(codecLabel)

        spinnerCodec = Spinner(this).apply {
            val codecOptions = arrayOf("PCM", "G.711", "Opus")
            val adapter = ArrayAdapter(
                this@VoiceSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                codecOptions
            )
            this.adapter = adapter
            setPadding(16, 16, 16, 16)
        }
        rootLayout.addView(spinnerCodec)

        // 采样率（下拉选择）
        val sampleLabel = TextView(this).apply {
            text = "采样率"
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }
        rootLayout.addView(sampleLabel)

        spinnerSampleRate = Spinner(this).apply {
            val sampleOptions = arrayOf("8kHz", "16kHz")
            val adapter = ArrayAdapter(
                this@VoiceSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                sampleOptions
            )
            this.adapter = adapter
            setPadding(16, 16, 16, 16)
        }
        rootLayout.addView(spinnerSampleRate)

        // 提示音开关
        val switchLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }

        val switchLabel = TextView(this).apply {
            text = "启用提示音"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        switchLayout.addView(switchLabel)

        switchPrompt = Switch(this)
        switchLayout.addView(switchPrompt)
        rootLayout.addView(switchLayout)

        // 状态显示
        val statusLabel = TextView(this).apply {
            text = "📊 实时状态"
            textSize = 18f
            setPadding(0, 32, 0, 16)
        }
        rootLayout.addView(statusLabel)

        tvVoiceStatus = TextView(this).apply {
            text = "状态: ○ 未连接"
            textSize = 16f
        }
        rootLayout.addView(tvVoiceStatus)

        tvVoiceRole = TextView(this).apply {
            text = "角色: 观察者 🎧"
            textSize = 16f
            setPadding(0, 8, 0, 0)
        }
        rootLayout.addView(tvVoiceRole)

        // 操作按钮区域
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }

        btnVoiceStart = Button(this).apply {
            text = "启动语音"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 16, 0)
            }
        }
        btnLayout.addView(btnVoiceStart)

        btnVoiceStop = Button(this).apply {
            text = "停止语音"
            textSize = 16f
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnLayout.addView(btnVoiceStop)
        rootLayout.addView(btnLayout)

        // PTT 按钮
        btnPtt = Button(this).apply {
            text = "🔇 静音"
            textSize = 16f
            isEnabled = false
            setPadding(0, 24, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 0)
            }
        }
        rootLayout.addView(btnPtt)

        // 测试提示音按钮
        val testPromptBtn = Button(this).apply {
            text = "🔊 测试提示音 (飞行员)"
            textSize = 16f
            setPadding(0, 24, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 0)
            }
        }
        testPromptBtn.setOnClickListener {
            try {
                val player = VoicePromptPlayer(this@VoiceSettingsActivity)
                player.playPilotPrompt()
                Toast.makeText(this@VoiceSettingsActivity, "🔊 播放提示音 (飞行员)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@VoiceSettingsActivity, "播放失败: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
        rootLayout.addView(testPromptBtn)

        // 测试观察者提示音按钮
        val testObserverPromptBtn = Button(this).apply {
            text = "🔊 测试提示音 (观察者)"
            textSize = 16f
            setPadding(0, 24, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 0)
            }
        }
        testObserverPromptBtn.setOnClickListener {
            try {
                val player = VoicePromptPlayer(this@VoiceSettingsActivity)
                player.playObserverPrompt()
                Toast.makeText(this@VoiceSettingsActivity, "🔊 播放提示音 (观察者)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@VoiceSettingsActivity, "播放失败: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
        rootLayout.addView(testObserverPromptBtn)

        // 提示信息
        val hint1 = TextView(this).apply {
            text = "💡 role=0 飞行员模式（可讲话）"
            textSize = 13f
            setPadding(0, 32, 0, 0)
        }
        rootLayout.addView(hint1)

        val hint2 = TextView(this).apply {
            text = "💡 role=1 观察者模式（仅收听）"
            textSize = 13f
        }
        rootLayout.addView(hint2)

        // 返回按钮
        val backBtn = Button(this).apply {
            text = "← 返回"
            textSize = 16f
            setPadding(0, 32, 0, 0)
        }
        backBtn.setOnClickListener { finish() }
        rootLayout.addView(backBtn)

        val scrollView = ScrollView(this).apply {
            addView(rootLayout)
        }
        setContentView(scrollView)
    }

    private fun loadConfig() {
        try {
            val sp = getSharedPreferences("voice_config", Context.MODE_PRIVATE)
            etMulticastIp.setText(sp.getString("multicast_ip", "224.0.0.1"))
            etMulticastPort.setText(sp.getString("multicast_port", "50000"))
            
            val codecPos = sp.getInt("codec_pos", 0)
            spinnerCodec.setSelection(codecPos)
            
            val samplePos = sp.getInt("sample_pos", 0)
            spinnerSampleRate.setSelection(samplePos)
            
            switchPrompt.isChecked = sp.getBoolean("prompt_enabled", true)
        } catch (e: Exception) {
            // 使用默认值
        }
    }

    private fun saveConfig() {
        try {
            val sp = getSharedPreferences("voice_config", Context.MODE_PRIVATE)
            sp.edit().apply {
                putString("multicast_ip", etMulticastIp.text.toString())
                putString("multicast_port", etMulticastPort.text.toString())
                putString("codec", spinnerCodec.selectedItem.toString())
                putInt("codec_pos", spinnerCodec.selectedItemPosition)
                putString("sample_rate", spinnerSampleRate.selectedItem.toString())
                putInt("sample_pos", spinnerSampleRate.selectedItemPosition)
                putBoolean("prompt_enabled", switchPrompt.isChecked)
                apply()
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction("com.example.netfloatmonitor.VOICE_STATUS")
            addAction("com.example.netfloatmonitor.VOICE_ROLE_CHANGE")
            addAction("com.example.netfloatmonitor.VOICE_PTT_STATE")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(voiceStatusReceiver, filter)
    }

    private fun setupListeners() {
        btnVoiceStart.setOnClickListener {
            if (!checkRecordPermission()) {
                requestRecordPermission()
                return@setOnClickListener
            }
            saveConfig()
            startVoiceService()
        }

        btnVoiceStop.setOnClickListener {
            stopVoiceService()
        }

        btnPtt.setOnClickListener {
            if (isVoiceRunning && currentRole == 0) {
                isMuted = !isMuted
                broadcastPttState(isMuted)
                updatePttButton()
                Toast.makeText(
                    this,
                    if (isMuted) "🔇 已静音" else "🎤 已取消静音",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (isVoiceRunning && currentRole != 0) {
                Toast.makeText(this, "⚠️ 观察者模式无法讲话", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkRecordPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestRecordPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ 录音权限已获取", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ 需要录音权限才能讲话", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVoiceService() {
        val ip = etMulticastIp.text.toString().trim()
        val portStr = etMulticastPort.text.toString().trim()
        val codec = spinnerCodec.selectedItem.toString()
        val sampleRate = spinnerSampleRate.selectedItem.toString()
        val promptEnabled = switchPrompt.isChecked

        if (ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "请填写组播地址和端口", Toast.LENGTH_SHORT).show()
            return
        }

        val port = portStr.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, "端口号无效（1024-65535）", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, VoiceService::class.java).apply {
            putExtra("ACTION", "START")
            putExtra("MULTICAST_IP", ip)
            putExtra("MULTICAST_PORT", port)
            putExtra("CODEC", codec)
            putExtra("SAMPLE_RATE", sampleRate)
            putExtra("PROMPT_ENABLED", promptEnabled)
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isVoiceRunning = true
            updateUI()
            Toast.makeText(this, "🎧 语音服务启动中...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceService() {
        try {
            val intent = Intent(this, VoiceService::class.java).apply {
                putExtra("ACTION", "STOP")
            }
            startService(intent)
            isVoiceRunning = false
            updateUI()
            Toast.makeText(this, "⏹ 语音服务已停止", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "停止失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun broadcastPttState(muted: Boolean) {
        val intent = Intent("com.example.netfloatmonitor.VOICE_PTT_STATE").apply {
            putExtra("MUTED", muted)
            putExtra("IS_PILOT", currentRole == 0)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateUI() {
        tvVoiceStatus.text = if (isVoiceRunning) {
            "状态: ● 已连接"
        } else {
            "状态: ○ 未连接"
        }
        tvVoiceStatus.setTextColor(if (isVoiceRunning) 0xFF4CAF50.toInt() else 0xFFE74C3C.toInt())

        val roleText = if (currentRole == 0) "飞行员 🎤" else "观察者 🎧"
        tvVoiceRole.text = "角色: $roleText"
        tvVoiceRole.setTextColor(if (currentRole == 0) 0xFF2ECC71.toInt() else 0xFF3498DB.toInt())

        btnVoiceStart.isEnabled = !isVoiceRunning
        btnVoiceStop.isEnabled = isVoiceRunning
        btnPtt.isEnabled = isVoiceRunning && currentRole == 0
        
        updatePttButton()
    }

    private fun updatePttButton() {
        if (isVoiceRunning && currentRole == 0) {
            btnPtt.isEnabled = true
            btnPtt.text = if (isMuted) "🔇 取消静音" else "🎤 静音"
        } else {
            btnPtt.isEnabled = false
            btnPtt.text = if (isVoiceRunning) "⛔ 仅收听模式" else "⛔ 服务未启动"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(voiceStatusReceiver)
        } catch (e: Exception) { /* ignore */ }
    }
}
