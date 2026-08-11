package com.example.netfloatmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.switchmaterial.SwitchMaterial

class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var etMulticastIp: AutoCompleteTextView
    private lateinit var etMulticastPort: AutoCompleteTextView
    private lateinit var actvCodec: AutoCompleteTextView
    private lateinit var actvSampleRate: AutoCompleteTextView
    private lateinit var switchPrompt: SwitchMaterial
    private lateinit var btnVoiceStart: Button
    private lateinit var btnVoiceStop: Button
    private lateinit var btnPtt: Button
    private lateinit var tvVoiceStatus: TextView
    private lateinit var tvVoiceRole: TextView
    private lateinit var tvAudioDevice: TextView

    private var isVoiceRunning = false
    private var currentRole = 1
    private var isMuted = false
    private var isPilot = false

    private val voiceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                "com.example.netfloatmonitor.VOICE_STATUS" -> {
                    isVoiceRunning = intent.getBooleanExtra("RUNNING", false)
                    currentRole = intent.getIntExtra("ROLE", 1)
                    isPilot = currentRole == 0
                    updateUI()
                }
                "com.example.netfloatmonitor.VOICE_ROLE_CHANGE" -> {
                    currentRole = intent.getIntExtra("ROLE", 1)
                    isPilot = currentRole == 0
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
                    isPilot = intent.getBooleanExtra("IS_PILOT", false)
                    updatePttButton()
                }
                "com.example.netfloatmonitor.VOICE_DEVICE_CHANGE" -> {
                    val device = intent.getStringExtra("DEVICE") ?: "扬声器"
                    tvAudioDevice.text = "📱 音频输出: $device"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_settings)

        initViews()
        setupDropdowns()
        loadConfig()
        registerReceivers()
        setupListeners()
        updateUI()
    }

    private fun initViews() {
        etMulticastIp = findViewById(R.id.etMulticastIp)
        etMulticastPort = findViewById(R.id.etMulticastPort)
        actvCodec = findViewById(R.id.actvCodec)
        actvSampleRate = findViewById(R.id.actvSampleRate)
        switchPrompt = findViewById(R.id.switchPrompt)
        btnVoiceStart = findViewById(R.id.btnVoiceStart)
        btnVoiceStop = findViewById(R.id.btnVoiceStop)
        btnPtt = findViewById(R.id.btnPtt)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        tvVoiceRole = findViewById(R.id.tvVoiceRole)
        tvAudioDevice = findViewById(R.id.tvAudioDevice)
    }

    private fun setupDropdowns() {
        val codecOptions = arrayOf(
            getString(R.string.voice_codec_pcm),
            getString(R.string.voice_codec_g711),
            getString(R.string.voice_codec_opus)
        )
        val codecAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, codecOptions)
        actvCodec.setAdapter(codecAdapter)
        actvCodec.setOnClickListener { actvCodec.showDropDown() }

        val sampleOptions = arrayOf(
            getString(R.string.voice_sample_8k),
            getString(R.string.voice_sample_16k)
        )
        val sampleAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sampleOptions)
        actvSampleRate.setAdapter(sampleAdapter)
        actvSampleRate.setOnClickListener { actvSampleRate.showDropDown() }
    }

    private fun loadConfig() {
        val sp = getSharedPreferences("voice_config", Context.MODE_PRIVATE)
        etMulticastIp.setText(sp.getString("multicast_ip", "224.0.0.1"))
        etMulticastPort.setText(sp.getString("multicast_port", "50000"))
        actvCodec.setText(sp.getString("codec", getString(R.string.voice_codec_pcm)))
        actvSampleRate.setText(sp.getString("sample_rate", getString(R.string.voice_sample_8k)))
        switchPrompt.isChecked = sp.getBoolean("prompt_enabled", true)
    }

    private fun saveConfig() {
        val sp = getSharedPreferences("voice_config", Context.MODE_PRIVATE)
        sp.edit().apply {
            putString("multicast_ip", etMulticastIp.text.toString())
            putString("multicast_port", etMulticastPort.text.toString())
            putString("codec", actvCodec.text.toString())
            putString("sample_rate", actvSampleRate.text.toString())
            putBoolean("prompt_enabled", switchPrompt.isChecked)
            apply()
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction("com.example.netfloatmonitor.VOICE_STATUS")
            addAction("com.example.netfloatmonitor.VOICE_ROLE_CHANGE")
            addAction("com.example.netfloatmonitor.VOICE_PTT_STATE")
            addAction("com.example.netfloatmonitor.VOICE_DEVICE_CHANGE")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(voiceStatusReceiver, filter)
    }

    private fun setupListeners() {
        btnVoiceStart.setOnClickListener {
            saveConfig()
            startVoiceService()
        }

        btnVoiceStop.setOnClickListener {
            stopVoiceService()
        }

        btnPtt.setOnClickListener {
            if (isVoiceRunning && isPilot) {
                isMuted = !isMuted
                broadcastPttState(isMuted)
                updatePttButton()
                Toast.makeText(
                    this,
                    if (isMuted) "🔇 已静音" else "🎤 已取消静音",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (isVoiceRunning && !isPilot) {
                Toast.makeText(this, "⚠️ 观察者模式无法讲话", Toast.LENGTH_SHORT).show()
            }
        }

        btnPtt.setOnLongClickListener {
            if (isVoiceRunning && isPilot) {
                if (isMuted) {
                    isMuted = false
                    broadcastPttState(isMuted)
                    updatePttButton()
                    Toast.makeText(this, "🎤 临时讲话（松开恢复静音）", Toast.LENGTH_SHORT).show()
                }
                true
            } else {
                false
            }
        }

        btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_UP -> {
                    // 长按松开恢复
                }
            }
            false
        }
    }

    private fun startVoiceService() {
        val ip = etMulticastIp.text.toString().trim()
        val portStr = etMulticastPort.text.toString().trim()
        val codec = actvCodec.text.toString()
        val sampleRate = actvSampleRate.text.toString()
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

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isVoiceRunning = true
        updateUI()
        Toast.makeText(this, "🎧 语音服务启动中...", Toast.LENGTH_SHORT).show()
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceService::class.java).apply {
            putExtra("ACTION", "STOP")
        }
        startService(intent)
        isVoiceRunning = false
        updateUI()
        Toast.makeText(this, "⏹ 语音服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun broadcastPttState(muted: Boolean) {
        val intent = Intent("com.example.netfloatmonitor.VOICE_PTT_STATE").apply {
            putExtra("MUTED", muted)
            putExtra("IS_PILOT", isPilot)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateUI() {
        tvVoiceStatus.text = if (isVoiceRunning) {
            "● ${getString(R.string.voice_connected)}"
        } else {
            "○ ${getString(R.string.voice_disconnected)}"
        }
        tvVoiceStatus.setTextColor(if (isVoiceRunning) 0xFF4CAF50.toInt() else 0xFFE74C3C.toInt())

        val roleText = if (currentRole == 0) {
            getString(R.string.voice_pilot)
        } else {
            getString(R.string.voice_observer)
        }
        tvVoiceRole.text = roleText
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
            btnPtt.backgroundTintList = if (isMuted) {
                android.content.res.ColorStateList.valueOf(0xFF757575.toInt())
            } else {
                android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
            }
        } else {
            btnPtt.isEnabled = false
            btnPtt.text = if (isVoiceRunning) "⛔ 仅收听模式" else "⛔ 服务未启动"
            btnPtt.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFBDBDBD.toInt())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(voiceStatusReceiver)
        } catch (e: Exception) { /* ignore */ }
    }
}
