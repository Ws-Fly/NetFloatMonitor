package com.example.netfloatmonitor

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "voice_channel"
        
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        private const val PCM_FRAME_SIZE = 320
        private const val BATCH_COUNT = 2
    }

    private val isRunning = AtomicBoolean(false)
    private val isPilotMode = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    
    private var multicastSocket: MulticastSocket? = null
    private var multicastGroup: InetAddress? = null
    private var multicastPort: Int = 18000
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private var receiveThread: Thread? = null
    private var sendThread: Thread? = null
    private var playThread: Thread? = null
    
    private var promptEnabled: Boolean = true
    private var packetSeq: Int = 0
    
    private lateinit var audioDeviceManager: AudioDeviceManager
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val jitterBuffer = mutableListOf<ByteArray>()
    private val MAX_JITTER_BUFFER = 3
    private val localIpAddresses = mutableListOf<String>()
    private var selectedNetworkInterface: NetworkInterface? = null
    
    private var multicastLock: WifiManager.MulticastLock? = null
    
    private val rxPackets = AtomicLong(0)
    private val txPackets = AtomicLong(0)

    private val roleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.netfloatmonitor.ROLE_CHANGE") {
                val role = intent.getIntExtra("ROLE", 1)
                Log.d(TAG, "📨 收到 role 广播: $role")
                handleRoleChange(role)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VoiceService onCreate")
        createNotificationChannel()
        
        collectNetworkInfo()
        acquireMulticastLock()
        
        audioDeviceManager = AudioDeviceManager(this)
        audioDeviceManager.setDeviceChangeListener { device ->
            Log.d(TAG, "音频设备切换: $device")
            broadcastDeviceChange(device)
        }
        
        LocalBroadcastManager.getInstance(this).registerReceiver(
            roleReceiver,
            IntentFilter("com.example.netfloatmonitor.ROLE_CHANGE")
        )
        Log.d(TAG, "✅ roleReceiver 已注册")
    }

    private fun collectNetworkInfo() {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            if (networkInterfaces != null) {
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
            }
            Log.d(TAG, "本机 IP 列表: $localIpAddresses")
        } catch (e: Exception) {
            Log.e(TAG, "获取网络信息失败: ${e.message}")
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("netaudiotalk_mcast_lock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "✅ 组播锁已获取")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 获取组播锁失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("ACTION") ?: return START_NOT_STICKY
        when (action) {
            "START" -> {
                multicastPort = intent.getIntExtra("MULTICAST_PORT", 18000)
                promptEnabled = intent.getBooleanExtra("PROMPT_ENABLED", true)

                val ipStr = intent.getStringExtra("MULTICAST_IP") ?: "224.12.34.56"
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
            
            val localAddr = if (localIpAddresses.isNotEmpty()) {
                InetAddress.getByName(localIpAddresses.first())
            } else {
                null
            }
            
            multicastSocket = MulticastSocket(multicastPort).apply {
                reuseAddress = true
                setTimeToLive(64)
                
                if (localAddr != null) {
                    val ni = NetworkInterface.getByInetAddress(localAddr)
                    if (ni != null) {
                        setNetworkInterface(ni)
                        Log.d(TAG, "✅ 绑定到网卡: ${ni.displayName}")
                    }
                }
                loopbackMode = false
                joinGroup(multicastGroup)
                Log.d(TAG, "✅ 组播已加入: ${multicastGroup?.hostAddress}:$multicastPort")
            }

            initAudioTrack()
            initAudioRecord()

            startReceiveThread()
            startPlayThread()

            isRunning.set(true)
            isPilotMode.set(false)
            packetSeq = 0
            
            jitterBuffer.clear()
            rxPackets.set(0)
            txPackets.set(0)

            broadcastStatus()
            audioDeviceManager.checkAndSwitchToBestDevice()
            
            Log.d(TAG, "✅ 语音服务已启动")

        } catch (e: Exception) {
            Log.e(TAG, "启动语音服务失败: ${e.message}", e)
            stopVoice()
            stopSelf()
        }
    }

    private fun initAudioTrack() {
        try {
            val minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
            val bufferSize = maxOf(minTrackBuf, PCM_FRAME_SIZE * 4)
            
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_OUT,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            ).apply { 
                play() 
                Log.d(TAG, "✅ AudioTrack 初始化成功: ${SAMPLE_RATE}Hz, 缓冲区: $bufferSize")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 初始化失败: ${e.message}")
            audioTrack = null
        }
    }

    private fun initAudioRecord() {
        try {
            val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
            val bufferSize = maxOf(minRecBuf, PCM_FRAME_SIZE * 4)
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                audioRecord = null
                return
            }
            
            audioRecord?.startRecording()
            Log.d(TAG, "✅ AudioRecord 初始化成功: ${SAMPLE_RATE}Hz, 缓冲区: $bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 初始化异常: ${e.message}")
            audioRecord = null
        }
    }

    private fun startReceiveThread() {
        receiveThread = thread(name = "VoiceReceiveThread") {
            val receiveBuffer = ByteArray((PCM_FRAME_SIZE / 2) * BATCH_COUNT)
            val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                try {
                    multicastSocket?.receive(packet)
                    
                    val senderIp = packet.address?.hostAddress ?: ""
                    if (localIpAddresses.contains(senderIp)) continue
                    
                    rxPackets.incrementAndGet()
                    
                    val data = ByteArray(packet.length)
                    System.arraycopy(packet.data, 0, data, 0, packet.length)
                    
                    val pcmData = decodeG711U(data, 0, data.size)
                    
                    if (pcmData != null && pcmData.isNotEmpty()) {
                        synchronized(jitterBuffer) {
                            jitterBuffer.add(pcmData)
                            if (jitterBuffer.size > MAX_JITTER_BUFFER) {
                                audioQueue.offer(jitterBuffer.removeAt(0))
                            }
                        }
                    }
                    
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "接收异常: ${e.message}")
                    }
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
                    Log.e(TAG, "播放异常: ${e.message}")
                }
            }
            Log.d(TAG, "播放线程已退出")
        }
    }

    private fun startSendThread() {
        sendThread?.interrupt()
        sendThread = thread(name = "VoiceSendThread") {
            val pcmBuffer = ByteArray(PCM_FRAME_SIZE)
            val compressedBatchBuf = ByteArray((PCM_FRAME_SIZE / 2) * BATCH_COUNT)
            var batchIndexLocal = 0
            var sendCount = 0

            Log.d(TAG, "📤 发送线程启动, 目标: ${multicastGroup?.hostAddress}:$multicastPort, BATCH_COUNT=$BATCH_COUNT")

            while (isRunning.get() && isPilotMode.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val record = audioRecord
                    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                        Thread.sleep(50)
                        continue
                    }

                    if (isMuted.get()) {
                        Thread.sleep(10)
                        continue
                    }

                    val readSize = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readSize > 0) {
                        val compressedFrame = encodeG711U(pcmBuffer, readSize)
                        
                        System.arraycopy(compressedFrame, 0, compressedBatchBuf, batchIndexLocal, compressedFrame.size)
                        batchIndexLocal += compressedFrame.size

                        if (batchIndexLocal >= compressedBatchBuf.size) {
                            val packet = DatagramPacket(
                                compressedBatchBuf,
                                compressedBatchBuf.size,
                                multicastGroup,
                                multicastPort
                            )
                            multicastSocket?.send(packet)
                            
                            batchIndexLocal = 0
                            txPackets.incrementAndGet()
                            sendCount++
                            
                            if (sendCount % 20 == 0) {
                                Log.d(TAG, "📤 已发送 $sendCount 批, 每批 ${compressedBatchBuf.size} 字节")
                            }
                        }
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get() && isPilotMode.get()) {
                        Log.e(TAG, "发送异常: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "📤 发送线程已退出")
        }
    }

    private val BIAS = 0x84
    private val CLIP = 32635

    private fun encodeG711U(pcm: ByteArray, length: Int): ByteArray {
        val encoded = ByteArray(length / 2)
        var pcmIndex = 0
        for (i in encoded.indices) {
            val low = pcm[pcmIndex].toInt() and 0xFF
            val high = pcm[pcmIndex + 1].toInt() shl 8
            var sample = high or low
            if (sample > 32767) sample -= 65536
            pcmIndex += 2

            val sign = if (sample < 0) 0 else 0x80
            if (sample < 0) sample = -sample
            if (sample > CLIP) sample = CLIP
            sample += BIAS

            var exponent = 7
            var expMask = 0x4000
            while ((sample and expMask) == 0 && exponent > 0) {
                exponent--
                expMask = expMask shr 1
            }
            val mantissa = (sample shr (exponent + 3)) and 0x0F
            val byteVal = (sign or (exponent shl 4) or mantissa).inv() and 0xFF
            encoded[i] = byteVal.toByte()
        }
        return encoded
    }

    private fun decodeG711U(encoded: ByteArray, offset: Int, length: Int): ByteArray? {
        try {
            val pcm = ByteArray(length * 2)
            var pcmIndex = 0
            for (i in 0 until length) {
                var q = encoded[offset + i].toInt().inv() and 0xFF
                val sign = q and 0x80
                val exponent = (q shr 4) and 0x07
                val mantissa = q and 0x0F
                var sample = ((mantissa shl 3) + BIAS) shl exponent
                sample -= BIAS
                if (sign == 0) sample = -sample

                pcm[pcmIndex] = (sample and 0xFF).toByte()
                pcm[pcmIndex + 1] = ((sample shr 8) and 0xFF).toByte()
                pcmIndex += 2
            }
            return pcm
        } catch (e: Exception) {
            Log.e(TAG, "G.711 解码失败: ${e.message}")
            return null
        }
    }

    // ===== 角色切换 - 只切换模式，不播放提示音 =====
    private fun handleRoleChange(role: Int) {
        val newIsPilot = role == 0
        Log.d(TAG, "🔄 handleRoleChange: role=$role, newIsPilot=$newIsPilot")
        
        if (newIsPilot != isPilotMode.get()) {
            isPilotMode.set(newIsPilot)
            
            if (newIsPilot) {
                isMuted.set(false)
                startSendThread()
                broadcastRoleChange(0)
                updateNotification()
                Log.d(TAG, "✅ 切换到飞行员模式")
            } else {
                sendThread?.interrupt()
                sendThread = null
                broadcastRoleChange(1)
                updateNotification()
                Log.d(TAG, "✅ 切换到观察者模式")
            }
        }
    }

    private fun broadcastDeviceChange(device: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("com.example.netfloatmonitor.VOICE_DEVICE_CHANGE").apply { putExtra("DEVICE", device) }
        )
    }

    private fun broadcastStatus() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("com.example.netfloatmonitor.VOICE_STATUS").apply {
                putExtra("RUNNING", isRunning.get())
                putExtra("ROLE", if (isPilotMode.get()) 0 else 1)
            }
        )
    }

    private fun broadcastRoleChange(role: Int) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("com.example.netfloatmonitor.VOICE_ROLE_CHANGE").apply { putExtra("ROLE", role) }
        )
    }

    private fun updateNotification() {
        val roleText = if (isPilotMode.get()) "飞行员 🎤" else "观察者 🎧"
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音对讲")
            .setContentText("组播语音 $roleText | RX:${rxPackets.get()} TX:${txPackets.get()}")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "语音对讲", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音对讲")
            .setContentText("组播语音 (观察者)")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
    }

    private fun stopVoice() {
        isRunning.set(false)
        isPilotMode.set(false)
        
        receiveThread?.interrupt(); receiveThread = null
        sendThread?.interrupt(); sendThread = null
        playThread?.interrupt(); playThread = null
        
        audioRecord?.let {
            try { 
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                it.release() 
            } catch (e: Exception) {}
        }
        audioRecord = null
        
        audioTrack?.let {
            try { 
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                it.release() 
            } catch (e: Exception) {}
        }
        audioTrack = null
        
        multicastSocket?.let {
            try { 
                it.leaveGroup(multicastGroup)
                it.close() 
            } catch (e: Exception) {}
        }
        multicastSocket = null
        
        audioQueue.clear()
        jitterBuffer.clear()
        
        broadcastStatus()
        Log.d(TAG, "语音服务已停止")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoice()
        audioDeviceManager.release()
        multicastLock?.let {
            try { if (it.isHeld) it.release() } catch (e: Exception) {}
        }
        multicastLock = null
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(roleReceiver)
        } catch (e: Exception) {}
        Log.d(TAG, "VoiceService onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
