package com.example.netfloatmonitor

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

class AudioDeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioDeviceManager"
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var currentOutputDevice: String = "扬声器"
    private var isBluetoothConnected = false
    private var isHeadsetConnected = false

    private var deviceChangeListener: ((String) -> Unit)? = null

    private val audioDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    Log.d(TAG, "音频设备断开")
                    switchToSpeaker()
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    checkBluetoothState()
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    isHeadsetConnected = state == 1
                    if (isHeadsetConnected) {
                        Log.d(TAG, "有线耳机已插入")
                        switchToHeadset()
                    } else {
                        Log.d(TAG, "有线耳机已拔出")
                        checkAndSwitchToBestDevice()
                    }
                }
            }
        }
    }

    init {
        registerReceiver()
        checkInitialState()
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        context.registerReceiver(audioDeviceReceiver, filter)
        Log.d(TAG, "音频设备广播接收器已注册")
    }

    private fun checkInitialState() {
        checkBluetoothState()
        isHeadsetConnected = audioManager.isWiredHeadsetOn
        Log.d(TAG, "初始设备状态 - 蓝牙: $isBluetoothConnected, 耳机: $isHeadsetConnected")
        checkAndSwitchToBestDevice()
    }

    private fun checkBluetoothState() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                isBluetoothConnected = audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
                if (isBluetoothConnected) {
                    Log.d(TAG, "蓝牙音频已连接")
                    switchToBluetooth()
                } else {
                    checkAndSwitchToBestDevice()
                }
            } else {
                isBluetoothConnected = false
                checkAndSwitchToBestDevice()
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查蓝牙状态异常: ${e.message}")
            checkAndSwitchToBestDevice()
        }
    }

    fun checkAndSwitchToBestDevice() {
        when {
            isHeadsetConnected -> switchToHeadset()
            isBluetoothConnected -> switchToBluetooth()
            else -> switchToSpeaker()
        }
    }

    fun switchToSpeaker() {
        try {
            audioManager.setSpeakerphoneOn(true)
            audioManager.setBluetoothScoOn(false)
            audioManager.isWiredHeadsetOn = false
            
            currentOutputDevice = "扬声器"
            deviceChangeListener?.invoke(currentOutputDevice)
            Log.d(TAG, "已切换到扬声器")
        } catch (e: Exception) {
            Log.e(TAG, "切换到扬声器失败: ${e.message}")
        }
    }

    fun switchToHeadset() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (device in devices) {
                    if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                        audioManager.setCommunicationDevice(device)
                        currentOutputDevice = "有线耳机"
                        deviceChangeListener?.invoke(currentOutputDevice)
                        Log.d(TAG, "已切换到有线耳机: ${device.productName}")
                        return
                    }
                }
            }
            
            audioManager.setSpeakerphoneOn(false)
            audioManager.setBluetoothScoOn(false)
            audioManager.isWiredHeadsetOn = true
            currentOutputDevice = "有线耳机"
            deviceChangeListener?.invoke(currentOutputDevice)
            Log.d(TAG, "已切换到有线耳机（传统方式）")
        } catch (e: Exception) {
            Log.e(TAG, "切换到有线耳机失败: ${e.message}")
            switchToSpeaker()
        }
    }

    fun switchToBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (device in devices) {
                    if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        audioManager.setCommunicationDevice(device)
                        currentOutputDevice = "蓝牙耳机"
                        deviceChangeListener?.invoke(currentOutputDevice)
                        Log.d(TAG, "已切换到蓝牙设备: ${device.productName}")
                        return
                    }
                }
            }
            
            audioManager.setSpeakerphoneOn(false)
            audioManager.setBluetoothScoOn(true)
            audioManager.isWiredHeadsetOn = false
            currentOutputDevice = "蓝牙耳机"
            deviceChangeListener?.invoke(currentOutputDevice)
            Log.d(TAG, "已切换到蓝牙耳机（传统方式）")
        } catch (e: Exception) {
            Log.e(TAG, "切换到蓝牙失败: ${e.message}")
            switchToSpeaker()
        }
    }

    fun setDeviceChangeListener(listener: (String) -> Unit) {
        this.deviceChangeListener = listener
    }

    fun getCurrentOutputDevice(): String = currentOutputDevice

    fun release() {
        try {
            context.unregisterReceiver(audioDeviceReceiver)
        } catch (e: Exception) { /* ignore */ }
        Log.d(TAG, "AudioDeviceManager已释放")
    }
}
