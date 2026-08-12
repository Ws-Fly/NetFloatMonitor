package com.example.netfloatmonitor

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "voice_channel"
        
        private const val PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val PACKET_DURATION_MS = 60
        private const val HEADER_SIZE = 9
    }

    private val isRunning = AtomicBoolean(false)
    private val isPilotMode = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val isPttOverridden = AtomicBoolean(false)
    
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    
    private var multicastSocket: MulticastSocket? = null
    private var multicastGroup: InetAddress? = null
    private var multicastPort: Int = 50000
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private var receiveThread: Thread? = null
    private var sendThread: Thread? = null
    private var playThread: Thread? = null
    
    private var sampleRate: Int = 8000
    private var codecType: String = "PCM"
    private var promptEnabled: Boolean = true
    private var audioCodec: AudioCodec = PcmCodec()
    private var packetSeq: Int = 0
    
    private lateinit var promptPlayer: VoicePromptPlayer
    private lateinit var audioDeviceManager: AudioDeviceManager
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentRoleFromJson: Int = 1
    
    private val jitterBuffer = mutableListOf<ByteArray>()
    private val MAX_JITTER_BUFFER = 3

    private val localIpAddresses = mutableListOf<String>()
    private var selectedNetworkInterface: NetworkInterface? = null

    private val roleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.netfloatmonitor.ROLE_CHANGE") {
                val role = intent.getIntExtra("ROLE", 1)
                currentRoleFromJson = role
                if (!isPttOverridden.get()) {
                    handleRoleChange(role)
                } else {
                    Log.d(TAG, "PTT覆盖中，忽略自动角色切换: role=$role")
                }
            }
        }
    }
    
    private val pttReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.netfloatmonitor.VOICE_PTT_STATE") {
                val muted = intent.getBooleanExtra("MUTED", false)
                handlePttState(muted)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VoiceService onCreate")
        createNotificationChannel()
        
        collectNetworkInfo()
        
        promptPlayer = VoicePromptPlayer(this)
        audioDeviceManager = AudioDeviceManager(this)
        audioDeviceManager.setDeviceChangeListener { device ->
            Log.d(TAG, "音频设备切换: $device")
            broadcastDeviceChange(device)
        }
        
        LocalBroadcastManager.getInstance(this).registerReceiver(
            roleReceiver,
            IntentFilter("com.example.netfloatmonitor.ROLE_CHANGE")
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            pttReceiver,
            IntentFilter("com.example.netfloatmonitor.VOICE_PTT_STATE")
        )
    }

    private fun collectNetworkInfo() {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val ni = networkInterfaces.nextElement()
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val hostAddress = address.hostAddress ?: continue
                    if (!hostAddress.contains(":") && !hostAddress.startsWith("127.")) {
                        localIpAddresses.add(hostAddress)
                        if (selectedNetworkInterface == null && ni.isUp) {
                            selectedNetworkInterface = ni
                            Log.d(TAG, "✅ 选中网卡: ${ni.displayName}, IP: $hostAddress")
                        }
                    }
                }
            }
            Log.d(TAG, "本机 IP 列表: $localIpAddresses")
        } catch (e: Exception) {
            Log.e(TAG, "获取网络信息失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("ACTION") ?: return START_NOT_STICKY

        when (action) {
            "START" -> {
                multicastPort = intent.getIntExtra("MULTICAST_PORT", 50000)
                codecType = intent.getStringExtra("CODEC") ?: "PCM"
                val sampleRateStr = intent.getStringExtra("SAMPLE_RATE") ?: "8kHz"
                sampleRate = if (sampleRateStr.contains("16")) 16000 else 8000
                promptEnabled = intent.getBooleanExtra("PROMPT_ENABLED", true)
                
                audioCodec = CodecFactory.getCodec(codecType)
                Log.d(TAG, "使用编解码器: ${audioCodec.getName()}")

                val ipStr = intent.getStringExtra("MULTICAST_IP") ?: "224.0.0.1"
                try {
                    multicastGroup = InetAddress.getByName(ipStr)
                } catch (e: Exception) {
                    Log.e(TAG, "无效的组播地址: $ipStr")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startVoice()
            }
            "STOP" -> {
                stopVoice()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startVoice() {
        if (isRunning.get()) return

        try {
            startForeground(NOTIFICATION_ID, createNotification())

            multicastSocket = MulticastSocket(multicastPort).apply {
                reuseAddress = true
                setTimeToLive(32)
                
                selectedNetworkInterface?.let { ni ->
                    try {
                        setNetworkInterface(ni)
                        Log.d(TAG, "✅ 组播Socket已绑定到网卡: ${ni.displayName}")
                    } catch (e: Exception) {
                        Log.e(TAG, "绑定网卡失败: ${e.message}")
                    }
                }
                
                joinGroup(multicastGroup)
                Log.d(TAG, "✅ 组播已加入: ${multicastGroup?.hostAddress}:$multicastPort")
            }

            initAudioTrack()
            initAudioRecord()
            startReceiveThread()
            startPlayThread()

            isRunning.set(true)
            isPilotMode.set(false)
            isMuted.set(false)
            isPttOverridden.set(false)
            packetSeq = 0
            
            jitterBuffer.clear()

            broadcastStatus()
            audioDeviceManager.checkAndSwitchToBestDevice()
            
            Log.d(TAG, "语音服务已启动（初始观察者模式）")

        } catch (e: Exception) {
            Log.e(TAG, "启动语音服务失败: ${e.message}", e)
            stopVoice()
            stopSelf()
        }
    }

    private fun stopVoice() {
        isRunning.set(false)
        isPilotMode.set(false)
        isPttOverridden.set(false)

        receiveThread?.interrupt()
        receiveThread = null
        sendThread?.interrupt()
        sendThread = null
        playThread?.interrupt()
        playThread = null

        audioRecord?.let {
            try { it.stop(); it.release() } catch (e: Exception) { /* ignore */ }
        }
        audioRecord = null

        audioTrack?.let {
            try { it.stop(); it.release() } catch (e: Exception) { /* ignore */ }
        }
        audioTrack = null

        multicastSocket?.let {
            try {
                it.leaveGroup(multicastGroup)
                it.close()
            } catch (e: Exception) { /* ignore */ }
        }
        multicastSocket = null

        audioQueue.clear()
        jitterBuffer.clear()
        promptPlayer.stop()

        broadcastStatus()
        Log.d(TAG, "语音服务已停止")
    }

    private fun handleRoleChange(role: Int) {
        val newIsPilot = role == 0
        
        if (newIsPilot != isPilotMode.get()) {
            isPilotMode.set(newIsPilot)
            
            if (newIsPilot) {
                isMuted.set(false)
                startSendThread()
                
                if (promptEnabled) {
                    mainHandler.post {
                        promptPlayer.playPilotPrompt()
                    }
                }
                
                broadcastRoleChange(0)
                updateNotification()
                Log.d(TAG, "✅ 自动切换到飞行员模式（可讲话）")
            } else {
                sendThread?.interrupt()
                sendThread = null
                
                if (promptEnabled) {
                    mainHandler.post {
                        promptPlayer.playObserverPrompt()
                    }
                }
                
                broadcastRoleChange(1)
                updateNotification()
                Log.d(TAG, "✅ 自动切换到观察者模式（仅收听）")
            }
        }
    }

    private fun handlePttState(muted: Boolean) {
        if (!isPilotMode.get()) {
            Log.d(TAG, "PTT操作无效：当前为观察者模式")
            broadcastPttState(true, isPilotMode.get())
            return
        }
        
        isMuted.set(muted)
        isPttOverridden.set(true)
        broadcastPttState(muted, isPilotMode.get())
        updateNotification()
        
        Log.d(TAG, "PTT状态: ${if (muted) "🔇 静音" else "🎤 取消静音"}")
    }

    private fun broadcastDeviceChange(device: String) {
        val intent = Intent("com.example.netfloatmonitor.VOICE_DEVICE_CHANGE").apply {
            putExtra("DEVICE", device)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun initAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            CHANNEL_CONFIG,
            PCM_ENCODING
        )

        if (minBufferSize <= 0) {
            Log.e(TAG, "获取AudioRecord最小缓冲区失败")
            return
        }

        val bufferSize = minBufferSize * 4
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            CHANNEL_CONFIG,
            PCM_ENCODING,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord初始化失败，尝试使用MIC源")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                PCM_ENCODING,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord再次初始化失败")
                audioRecord = null
                return
            }
        }
        
        audioRecord?.startRecording()
        Log.d(TAG, "AudioRecord已初始化: ${sampleRate}Hz, 缓冲区: $bufferSize")
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            CHANNEL_OUT_CONFIG,
            PCM_ENCODING
        )

        if (minBufferSize <= 0) {
            Log.e(TAG, "获取AudioTrack最小缓冲区失败")
            return
        }

        val bufferSize = minBufferSize * 6
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(PCM_ENCODING)
            .setSampleRate(sampleRate)
            .setChannelMask(CHANNEL_OUT_CONFIG)
            .build()

        audioTrack = AudioTrack(
            audioAttributes,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack初始化失败")
            audioTrack = null
        } else {
            audioTrack?.play()
            Log.d(TAG, "AudioTrack已初始化: ${sampleRate}Hz, 缓冲区: $bufferSize")
        }
    }

    private fun startReceiveThread() {
        receiveThread = thread(name = "VoiceReceiveThread") {
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)

            Log.d(TAG, "📥 接收线程启动，本机 IP 黑名单: $localIpAddresses")

            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                try {
                    multicastSocket?.receive(packet)
                    
                    val senderIp = packet.address?.hostAddress ?: ""
                    
                    var isSelf = false
                    for (localIp in localIpAddresses) {
                        if (senderIp == localIp) {
                            isSelf = true
                            break
                        }
                    }
                    
                    if (isSelf) {
                        continue
                    }
                    
                    val data = ByteArray(packet.length)
                    System.arraycopy(packet.data, 0, data, 0, packet.length)
                    
                    if (data.size >= HEADER_SIZE) {
                        val codecTypeFromPacket = data[8].toInt() and 0xFF
                        val audioData = data.copyOfRange(HEADER_SIZE, data.size)
                        
                        val codec = CodecFactory.getCodec(
                            when (codecTypeFromPacket) {
                                0 -> "PCM"
                                1 -> "G.711"
                                2 -> "Opus"
                                else -> "PCM"
                            }
                        )
                        
                        val pcmData = codec.decode(audioData, sampleRate)
                        
                        if (pcmData != null && pcmData.isNotEmpty()) {
                            synchronized(jitterBuffer) {
                                jitterBuffer.add(pcmData)
                                if (jitterBuffer.size > MAX_JITTER_BUFFER) {
                                    val oldest = jitterBuffer.removeAt(0)
                                    audioQueue.offer(oldest)
                                }
                            }
                        }
                    }
                    
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "接收线程异常: ${e.message}")
                    }
                    break
                }
            }
            Log.d(TAG, "接收线程已退出")
        }
    }

    private fun startPlayThread() {
        playThread = thread(name = "VoicePlayThread") {
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val data = audioQueue.poll()
                    if (data != null && data.isNotEmpty()) {
                        audioTrack?.write(data, 0, data.size)
                    } else {
                        Thread.sleep(10)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "播放线程异常: ${e.message}")
                }
            }
            Log.d(TAG, "播放线程已退出")
        }
    }

    private fun startSendThread() {
        sendThread?.interrupt()
        sendThread = thread(name = "VoiceSendThread") {
            val buffer = ByteArray(4096)
            val pcmPacketSize = (sampleRate * 2 * PACKET_DURATION_MS / 1000).toInt()
            
            Log.d(TAG, "🔊 发送线程启动 - 采样率: $sampleRate, 包大小: $pcmPacketSize 字节")
            Log.d(TAG, "🔊 组播目标: ${multicastGroup?.hostAddress}:$multicastPort")

            var sendCount = 0

            while (isRunning.get() && isPilotMode.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val audioRecord = audioRecord
                    if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                        Thread.sleep(100)
                        continue
                    }

                    if (isMuted.get()) {
                        Thread.sleep(PACKET_DURATION_MS.toLong())
                        continue
                    }

                    val readSize = audioRecord.read(buffer, 0, pcmPacketSize)
                    if (readSize > 0) {
                        val pcmData = buffer.copyOf(readSize)
                        
                        // ===== 音频归一化处理（消除杂音） =====
                        val normalizedData = normalizeAudio(pcmData)
                        
                        val encodedData = audioCodec.encode(normalizedData, sampleRate)
                        
                        if (encodedData != null && encodedData.isNotEmpty()) {
                            val packetData = ByteArray(HEADER_SIZE + encodedData.size)
                            
                            packetData[0] = (packetSeq shr 24 and 0xFF).toByte()
                            packetData[1] = (packetSeq shr 16 and 0xFF).toByte()
                            packetData[2] = (packetSeq shr 8 and 0xFF).toByte()
                            packetData[3] = (packetSeq and 0xFF).toByte()
                            packetSeq++
                            
                            val timestamp = System.currentTimeMillis()
                            packetData[4] = (timestamp shr 24 and 0xFF).toByte()
                            packetData[5] = (timestamp shr 16 and 0xFF).toByte()
                            packetData[6] = (timestamp shr 8 and 0xFF).toByte()
                            packetData[7] = (timestamp and 0xFF).toByte()
                            
                            val codecId = when (audioCodec.getName()) {
                                "PCM" -> 0
                                "G.711" -> 1
                                "Opus" -> 2
                                else -> 0
                            }
                            packetData[8] = codecId.toByte()
                            
                            System.arraycopy(encodedData, 0, packetData, HEADER_SIZE, encodedData.size)
                            
                            val packet = DatagramPacket(
                                packetData,
                                packetData.size,
                                multicastGroup,
                                multicastPort
                            )
                            
                            multicastSocket?.send(packet)
                            
                            sendCount++
                            if (sendCount % 10 == 0) {
                                val compressionRatio = if (audioCodec.getName() != "PCM") {
                                    (1 - encodedData.size.toFloat() / pcmPacketSize) * 100
                                } else 0f
                                Log.d(TAG, "📤 已发送 $sendCount 包, 压缩率: ${"%.1f".format(compressionRatio)}%, 包大小: ${packetData.size} 字节")
                            }
                        }
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get() && isPilotMode.get()) {
                        Log.e(TAG, "发送线程异常: ${e.message}")
                    }
                    break
                }
            }
            Log.d(TAG, "📤 发送线程已退出，共发送 $sendCount 个包")
        }
    }

    // ===== 音频归一化处理（消除杂音） =====
    private fun normalizeAudio(pcmData: ByteArray): ByteArray {
        if (pcmData.size < 2) return pcmData
        
        try {
            val shorts = ShortArray(pcmData.size / 2)
            for (i in shorts.indices) {
                val low = pcmData[i * 2].toInt() and 0xFF
                val high = pcmData[i * 2 + 1].toInt() and 0xFF
                shorts[i] = ((high shl 8) or low).toShort()
            }
            
            var maxAmplitude = 0
            for (s in shorts) {
                val amp = abs(s.toInt())
                if (amp > maxAmplitude) maxAmplitude = amp
            }
            
            if (maxAmplitude < 50) {
                return pcmData
            }
            
            val targetLevel = 0.7f
            val scaleFactor = (targetLevel * 32767) / maxAmplitude
            
            val result = ByteArray(pcmData.size)
            for (i in shorts.indices) {
                var sample = (shorts[i].toFloat() * scaleFactor).toInt()
                sample = sample.coerceIn(-32768, 32767)
                result[i * 2] = (sample and 0xFF).toByte()
                result[i * 2 + 1] = (sample shr 8 and 0xFF).toByte()
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "音频归一化失败: ${e.message}")
            return pcmData
        }
    }

    private fun broadcastStatus() {
        val intent = Intent("com.example.netfloatmonitor.VOICE_STATUS").apply {
            putExtra("RUNNING", isRunning.get())
            putExtra("ROLE", if (isPilotMode.get()) 0 else 1)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastRoleChange(role: Int) {
        val intent = Intent("com.example.netfloatmonitor.VOICE_ROLE_CHANGE").apply {
            putExtra("ROLE", role)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastPttState(muted: Boolean, isPilot: Boolean) {
        val intent = Intent("com.example.netfloatmonitor.VOICE_PTT_STATE").apply {
            putExtra("MUTED", muted)
            putExtra("IS_PILOT", isPilot)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateNotification() {
        val roleText = if (isPilotMode.get()) "飞行员 🎤" else "观察者 🎧"
        val mutedText = if (isMuted.get()) " 🔇静音" else ""
        val deviceText = " 📱${audioDeviceManager.getCurrentOutputDevice()}"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音对讲")
            .setContentText("组播语音 $roleText$mutedText$deviceText")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音对讲",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音对讲")
            .setContentText("组播语音 (观察者)")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoice()
        audioDeviceManager.release()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(roleReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(pttReceiver)
        } catch (e: Exception) { /* ignore */ }
        Log.d(TAG, "VoiceService onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
