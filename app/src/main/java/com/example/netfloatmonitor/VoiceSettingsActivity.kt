package com.example.netfloatmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var switchVoice: Switch
    private lateinit var tvVoiceStatus: TextView
    private lateinit var tvVoiceRole: TextView

    private var isVoiceRunning = false
    private var currentRole = 1

    private val REQUEST_RECORD_AUDIO = 1001

    private val voiceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                "com.example.netfloatmonitor.VOICE_STATUS" -> {
                    isVoiceRunning = intent.getBooleanExtra("RUNNING", false)
                    currentRole = intent.getIntExtra("ROLE", 1)
                    switchVoice.isChecked = isVoiceRunning
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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val rootLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                setBackgroundColor(Color.parseColor("#1A1A2E"))
            }

            val titleView = TextView(this).apply {
                text = "🎤 语音对讲"
                textSize = 22f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 24)
            }
            rootLayout.addView(titleView)

            val ipLabel = TextView(this).apply {
                text = "组播地址"
                textSize = 15f
                setTextColor(Color.parseColor("#AAAAAA"))
            }
            rootLayout.addView(ipLabel)

            etMulticastIp = EditText(this).apply {
                setText("224.12.34.56")
                setPadding(16, 16, 16, 16)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#666666"))
                setBackgroundResource(android.R.drawable.editbox_background)
            }
            rootLayout.addView(etMulticastIp)

            val portLabel = TextView(this).apply {
                text = "组播端口"
                textSize = 15f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(0, 20, 0, 0)
            }
            rootLayout.addView(portLabel)

            etMulticastPort = EditText(this).apply {
                setText("18000")
                setPadding(16, 16, 16, 16)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#666666"))
                setBackgroundResource(android.R.drawable.editbox_background)
            }
            rootLayout.addView(etMulticastPort)

            val divider = View(this)
            divider.setBackgroundColor(Color.parseColor("#333333"))
            val dividerParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            dividerParams.setMargins(0, 24, 0, 24)
            divider.layoutParams = dividerParams
            rootLayout.addView(divider)

            val switchLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 0)
            }

            val switchLabel = TextView(this).apply {
                text = "🔊 对讲功能"
                textSize = 17f
                setTextColor(Color.WHITE)
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = params
            }
            switchLayout.addView(switchLabel)

            switchVoice = Switch(this).apply {
                isChecked = false
            }
            switchLayout.addView(switchVoice)
            rootLayout.addView(switchLayout)

            val switchHint = TextView(this).apply {
                text = "开启后可讲话和收听"
                textSize = 12f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 4, 0, 0)
            }
            rootLayout.addView(switchHint)

            val divider2 = View(this)
            divider2.setBackgroundColor(Color.parseColor("#333333"))
            val dividerParams2 = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            dividerParams2.setMargins(0, 24, 0, 24)
            divider2.layoutParams = dividerParams2
            rootLayout.addView(divider2)

            val statusLabel = TextView(this).apply {
                text = "📊 实时状态"
                textSize = 16f
                setTextColor(Color.parseColor("#DDDDDD"))
                setPadding(0, 0, 0, 12)
            }
            rootLayout.addView(statusLabel)

            tvVoiceStatus = TextView(this).apply {
                text = "状态: ○ 未连接"
                textSize = 15f
                setTextColor(Color.parseColor("#E74C3C"))
            }
            rootLayout.addView(tvVoiceStatus)

            tvVoiceRole = TextView(this).apply {
                text = "角色: 观察者 🎧"
                textSize = 15f
                setTextColor(Color.parseColor("#3498DB"))
                setPadding(0, 6, 0, 0)
            }
            rootLayout.addView(tvVoiceRole)

            val hint1 = TextView(this).apply {
                text = "💡 role=0 飞行员模式（可讲话）"
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 24, 0, 0)
            }
            rootLayout.addView(hint1)

            val hint2 = TextView(this).apply {
                text = "💡 role=1 观察者模式（仅收听）"
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 4, 0, 0)
            }
            rootLayout.addView(hint2)

            val backBtn = Button(this).apply {
                text = "← 返回"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(0, 24, 0, 0)
                setBackgroundColor(Color.TRANSPARENT)
            }
            backBtn.setOnClickListener { finish() }
            rootLayout.addView(backBtn)

            val scrollView = ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#1A1A2E"))
                addView(rootLayout)
            }
            setContentView(scrollView)

            loadConfig()
            registerReceivers()
            setupListeners()
            updateUI()
            checkRecordPermission()

        } catch (e: Exception) {
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadConfig() {
        try {
            val sp = getSharedPreferences("voice_config", Context.MODE_PRIVATE)
            etMulticastIp.setText(sp.getString("multicast_ip", "224.12.34.56"))
            etMulticastPort.setText(sp.getString("multicast_port", "18000"))
            switchVoice.isChecked = sp.getBoolean("voice_enabled", false)
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
                putBoolean("voice_enabled", switchVoice.isChecked)
                apply()
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun registerReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction("com.example.netfloatmonitor.VOICE_STATUS")
                addAction("com.example.netfloatmonitor.VOICE_ROLE_CHANGE")
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(voiceStatusReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupListeners() {
        switchVoice.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!checkRecordPermission()) {
                    requestRecordPermission()
                    switchVoice.isChecked = false
                    return@setOnCheckedChangeListener
                }
                saveConfig()
                startVoiceService()
            } else {
                stopVoiceService()
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
                switchVoice.isChecked = true
                startVoiceService()
            } else {
                Toast.makeText(this, "⚠️ 需要录音权限才能讲话", Toast.LENGTH_SHORT).show()
                switchVoice.isChecked = false
            }
        }
    }

    private fun startVoiceService() {
        val ip = etMulticastIp.text.toString().trim()
        val portStr = etMulticastPort.text.toString().trim()

        if (ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "请填写组播地址和端口", Toast.LENGTH_SHORT).show()
            switchVoice.isChecked = false
            return
        }

        val port = portStr.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, "端口号无效（1024-65535）", Toast.LENGTH_SHORT).show()
            switchVoice.isChecked = false
            return
        }

        val intent = Intent(this, VoiceService::class.java).apply {
            putExtra("ACTION", "START")
            putExtra("MULTICAST_IP", ip)
            putExtra("MULTICAST_PORT", port)
            putExtra("PROMPT_ENABLED", true)
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isVoiceRunning = true
            updateUI()
            Toast.makeText(this, "🎧 语音对讲已开启", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            switchVoice.isChecked = false
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
            Toast.makeText(this, "⏹ 语音对讲已关闭", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "停止失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        tvVoiceStatus.text = if (isVoiceRunning) {
            "状态: ● 已连接"
        } else {
            "状态: ○ 未连接"
        }
        tvVoiceStatus.setTextColor(if (isVoiceRunning) Color.parseColor("#4CAF50") else Color.parseColor("#E74C3C"))

        val roleText = if (currentRole == 0) "飞行员 🎤" else "观察者 🎧"
        tvVoiceRole.text = "角色: $roleText"
        tvVoiceRole.setTextColor(if (currentRole == 0) Color.parseColor("#2ECC71") else Color.parseColor("#3498DB"))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(voiceStatusReceiver)
        } catch (e: Exception) {
            // 忽略
        }
    }
}
