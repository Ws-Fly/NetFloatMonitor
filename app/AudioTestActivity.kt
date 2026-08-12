package com.example.netfloatmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class AudioTestActivity : AppCompatActivity() {

    private val REQUEST_RECORD_AUDIO = 1001

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnTestMic: Button
    private lateinit var btnTestSpeaker: Button
    private lateinit var btnTestPrompt: Button

    private var isRecording = false
    private val logMessages = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ===== 动态创建布局 =====
        val scrollView = ScrollView(this)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // 标题
        val title = TextView(this).apply {
            text = "🔬 音频测试工具"
            textSize = 28f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(title)

        // 状态显示
        tvStatus = TextView(this).apply {
            text = "状态: 就绪"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(tvStatus)

        // 日志显示
        tvLog = TextView(this).apply {
            text = "📋 日志:\n等待测试..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
            setTextColor(0xFF666666.toInt())
        }
        rootLayout.addView(tvLog)

        // ===== 测试1: 麦克风录音并播放 =====
        btnTestMic = Button(this).apply {
            text = "🎙️ 测试麦克风 (录音3秒 → 播放)"
            textSize = 18f
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }
        rootLayout.addView(btnTestMic)

        // ===== 测试2: 播放测试音 =====
        btnTestSpeaker = Button(this).apply {
            text = "🔊 测试扬声器 (播放440Hz)"
            textSize = 18f
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }
        rootLayout.addView(btnTestSpeaker)

        // ===== 测试3: 提示音 =====
        btnTestPrompt = Button(this).apply {
            text = "🔊 测试提示音 (合成音)"
            textSize = 18f
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }
        rootLayout.addView(btnTestPrompt)

        // ===== 说明 =====
        val info = TextView(this).apply {
            text = """
                💡 测试说明:
                1. 测试麦克风 → 录音3秒后自动播放
                2. 测试扬声器 → 播放440Hz正弦波
                3. 测试提示音 → 播放合成提示音

                ⚠️ 听不到声音请检查:
                • 媒体音量是否开启（按音量+键）
                • 是否连接蓝牙耳机
                • 录音权限是否已授予
                • 手机是否处于静音模式
            """.trimIndent()
            textSize = 14f
            setPadding(0, 32, 0, 0)
            setTextColor(0xFF888888.toInt())
        }
        rootLayout.addView(info)

        scrollView.addView(rootLayout)
        setContentView(scrollView)

        setupListeners()
        checkPermission()

        addLog("✅ 测试工具已启动，点击按钮测试")
    }

    private fun setupListeners() {
        btnTestMic.setOnClickListener {
            if (!checkPermission()) {
                requestPermission()
                return@setOnClickListener
            }
            if (!isRecording) {
                testMic()
            } else {
                Toast.makeText(this, "正在录音中，请稍候...", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestSpeaker.setOnClickListener {
            testSpeaker()
        }

        btnTestPrompt.setOnClickListener {
            testPrompt()
        }
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPermission() {
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
                addLog("✅ 录音权限已获取")
            } else {
                Toast.makeText(this, "⚠️ 需要录音权限才能测试", Toast.LENGTH_LONG).show()
                addLog("⚠️ 录音权限被拒绝")
            }
        }
    }

    // ===== 更新UI =====
    private fun updateStatus(text: String) {
        handler.post {
            tvStatus.text = "状态: $text"
        }
    }

    private fun addLog(text: String) {
        handler.post {
            logMessages.add(text)
            if (logMessages.size > 20) {
                logMessages.removeAt(0)
            }
            tvLog.text = "📋 日志:\n${logMessages.joinToString("\n")}"
        }
    }

    // ============================================================
    // 测试1: 麦克风录音 + 播放
    // ============================================================
    private fun testMic() {
        isRecording = true
        btnTestMic.isEnabled = false
        updateStatus("🔴 录音中... 请说话 (3秒)")
        addLog("🔴 开始录音...")

        thread {
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                // 获取最小缓冲区
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                addLog("📊 最小缓冲区: $minBufferSize")
                if (minBufferSize <= 0) {
                    handler.post {
                        updateStatus("❌ 获取缓冲区失败")
                        addLog("❌ minBufferSize <= 0")
                        btnTestMic.isEnabled = true
                        isRecording = false
                    }
                    return@thread
                }

                val bufferSize = minBufferSize * 2
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    handler.post {
                        updateStatus("❌ AudioRecord 初始化失败")
                        addLog("❌ AudioRecord.state = ${record.state}")
                        btnTestMic.isEnabled = true
                        isRecording = false
                    }
                    record.release()
                    return@thread
                }
                addLog("✅ AudioRecord 初始化成功")

                // 开始录音
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    handler.post {
                        updateStatus("❌ 启动录音失败")
                        addLog("❌ recordingState = ${record.recordingState}")
                        btnTestMic.isEnabled = true
                        isRecording = false
                    }
                    record.release()
                    return@thread
                }
                addLog("✅ 录音已启动")

                // 录音 3 秒
                val durationMs = 3000
                val totalSamples = sampleRate * durationMs / 1000
                val pcmData = ByteArray(totalSamples * 2)
                var totalRead = 0
                val buffer = ByteArray(bufferSize)

                while (totalRead < pcmData.size && isRecording) {
                    val readSize = record.read(buffer, 0, bufferSize)
                    if (readSize > 0) {
                        System.arraycopy(buffer, 0, pcmData, totalRead, readSize)
                        totalRead += readSize
                    }
                }

                addLog("📊 录音完成，共 ${totalRead} 字节")

                // 停止录音
                record.stop()
                record.release()

                if (totalRead == 0) {
                    handler.post {
                        updateStatus("❌ 未录到任何数据")
                        addLog("❌ totalRead = 0")
                        btnTestMic.isEnabled = true
                        isRecording = false
                    }
                    return@thread
                }

                handler.post {
                    updateStatus("🔊 播放录音中...")
                    addLog("🔊 开始播放录音")
                }

                // 播放录音
                val track = createAudioTrack(sampleRate)
                if (track == null) {
                    handler.post {
                        updateStatus("❌ AudioTrack 创建失败")
                        addLog("❌ AudioTrack 创建失败")
                        btnTestMic.isEnabled = true
                        isRecording = false
                    }
                    return@thread
                }

                // 写入数据并播放
                track.write(pcmData, 0, totalRead)
                track.play()

                // 等待播放完成
                Thread.sleep(durationMs + 200L)

                track.stop()
                track.release()

                handler.post {
                    updateStatus("✅ 测试完成")
                    addLog("✅ 麦克风测试完成")
                    Toast.makeText(this, "✅ 录音播放完成，请听是否有声音", Toast.LENGTH_LONG).show()
                    btnTestMic.isEnabled = true
                    isRecording = false
                }

            } catch (e: Exception) {
                handler.post {
                    updateStatus("❌ 异常: ${e.message}")
                    addLog("❌ 异常: ${e.message}")
                    btnTestMic.isEnabled = true
                    isRecording = false
                }
                e.printStackTrace()
            }
        }
    }

    // ============================================================
    // 测试2: 播放测试音 (440Hz)
    // ============================================================
    private fun testSpeaker() {
        btnTestSpeaker.isEnabled = false
        updateStatus("🔊 播放测试音 (440Hz)...")
        addLog("🔊 开始播放440Hz测试音")

        thread {
            try {
                val sampleRate = 16000
                val durationMs = 2000
                val numSamples = sampleRate * durationMs / 1000

                // 生成 440Hz 正弦波
                val pcmData = ByteArray(numSamples * 2)
                for (i in 0 until numSamples) {
                    val t = i.toFloat() / sampleRate
                    val sample = (0.3f * sin(2.0f * PI.toFloat() * 440f * t) * 32767).toInt()
                    val clamped = sample.coerceIn(-32768, 32767)
                    val unsigned = if (clamped < 0) clamped + 0x10000 else clamped
                    pcmData[i * 2] = (unsigned and 0xFF).toByte()
                    pcmData[i * 2 + 1] = (unsigned shr 8 and 0xFF).toByte()
                }

                addLog("📊 生成 440Hz 正弦波，${pcmData.size} 字节")

                val track = createAudioTrack(sampleRate)
                if (track == null) {
                    handler.post {
                        updateStatus("❌ AudioTrack 创建失败")
                        addLog("❌ AudioTrack 创建失败")
                        btnTestSpeaker.isEnabled = true
                    }
                    return@thread
                }

                track.write(pcmData, 0, pcmData.size)
                track.play()
                addLog("✅ AudioTrack 开始播放")

                Thread.sleep(durationMs + 200L)

                track.stop()
                track.release()

                handler.post {
                    updateStatus("✅ 测试完成")
                    addLog("✅ 扬声器测试完成")
                    Toast.makeText(this, "✅ 440Hz 测试音播放完成", Toast.LENGTH_LONG).show()
                    btnTestSpeaker.isEnabled = true
                }

            } catch (e: Exception) {
                handler.post {
                    updateStatus("❌ 异常: ${e.message}")
                    addLog("❌ 异常: ${e.message}")
                    btnTestSpeaker.isEnabled = true
                }
                e.printStackTrace()
            }
        }
    }

    // ============================================================
    // 测试3: 提示音
    // ============================================================
    private fun testPrompt() {
        btnTestPrompt.isEnabled = false
        updateStatus("🔊 播放提示音...")
        addLog("🔊 开始播放提示音")

        thread {
            try {
                val sampleRate = 16000
                val durationMs = 800
                val numSamples = sampleRate * durationMs / 1000

                val pcmData = ByteArray(numSamples * 2)
                for (i in 0 until numSamples) {
                    val t = i.toFloat() / sampleRate
                    var sample = 0f
                    // 基频 300Hz + 谐波
                    for (h in 1..5) {
                        sample += (1.0f / h) * sin(2.0f * PI.toFloat() * 300f * h * t)
                    }
                    // 包络
                    val envelope = when {
                        t < 0.05f -> t / 0.05f
                        t > 0.7f -> 1.0f - (t - 0.7f) / 0.1f
                        else -> 1.0f
                    }
                    sample *= 0.5f * envelope
                    val intSample = (sample * 32767).toInt().coerceIn(-32768, 32767)
                    val unsigned = if (intSample < 0) intSample + 0x10000 else intSample
                    pcmData[i * 2] = (unsigned and 0xFF).toByte()
                    pcmData[i * 2 + 1] = (unsigned shr 8 and 0xFF).toByte()
                }

                addLog("📊 生成提示音，${pcmData.size} 字节")

                val track = createAudioTrack(sampleRate)
                if (track == null) {
                    handler.post {
                        updateStatus("❌ AudioTrack 创建失败")
                        addLog("❌ AudioTrack 创建失败")
                        btnTestPrompt.isEnabled = true
                    }
                    return@thread
                }

                track.write(pcmData, 0, pcmData.size)
                track.play()
                addLog("✅ AudioTrack 开始播放")

                Thread.sleep(durationMs + 200L)

                track.stop()
                track.release()

                handler.post {
                    updateStatus("✅ 提示音播放完成")
                    addLog("✅ 提示音测试完成")
                    Toast.makeText(this, "✅ 提示音播放完成", Toast.LENGTH_SHORT).show()
                    btnTestPrompt.isEnabled = true
                }

            } catch (e: Exception) {
                handler.post {
                    updateStatus("❌ 异常: ${e.message}")
                    addLog("❌ 异常: ${e.message}")
                    btnTestPrompt.isEnabled = true
                }
                e.printStackTrace()
            }
        }
    }

    // ============================================================
    // 创建 AudioTrack
    // ============================================================
    private fun createAudioTrack(sampleRate: Int): AudioTrack? {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else 8192

            AudioTrack(attrs, format, bufferSize, AudioTrack.MODE_STREAM, 0)
        } catch (e: Exception) {
            addLog("❌ 创建 AudioTrack 失败: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
    }
}
