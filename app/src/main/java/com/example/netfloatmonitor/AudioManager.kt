package com.example.netfloatmonitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

class AudioManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioManager"
    }

    private val audioManager: android.media.AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    private var currentOutputDevice: String = "扬声器"
    private var isBluetoothConnected = false
    private var isHeadsetConnected = false

    private var deviceChangeListener: ((String) -> Unit)? = null

    private val audioDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    Log.d(TAG, "音频设备断开（耳机/蓝牙拔出）")
                    switchToSpeaker()
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_ACL_CONNECTED,
                BluetoothAdapter.ACTION_ACL_DISCONNECTED -> {
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
            addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_ACL_CONNECTED)
            addAction(BluetoothAdapter.ACTION_ACL_DISCONNECTED)
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
            if (bluetoothAdapter != null) {
                val isEnabled = bluetoothAdapter.isEnabled
                if (isEnabled) {
                    val profileProxy = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            val devices = proxy.connectedDevices
                            isBluetoothConnected = devices.isNotEmpty()
                            if (isBluetoothConnected) {
                                Log.d(TAG, "蓝牙音频设备已连接: ${devices.firstOrNull()?.name}")
                                switchToBluetooth()
                            } else {
                                Log.d(TAG, "没有蓝牙音频设备连接")
                                checkAndSwitchToBestDevice()
                            }
                            proxy.close()
                        }

                        override fun onServiceDisconnected(profile: Int) {}
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            bluetoothAdapter.getProfileProxy(
                                context,
                                profileProxy,
                                BluetoothProfile.HEADSET
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "获取蓝牙代理失败: ${e.message}")
                            checkAndSwitchToBestDevice()
                        }
                    } else {
                        checkAndSwitchToBestDevice()
                    }
                } else {
                    isBluetoothConnected = false
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.setCommunicationDevice(null)
            } else {
                audioManager.setSpeakerphoneOn(true)
                audioManager.setBluetoothScoOn(false)
                audioManager.isWiredHeadsetOn = false
            }
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
                val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
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
                switchToSpeaker()
            } else {
                audioManager.setSpeakerphoneOn(false)
                audioManager.setBluetoothScoOn(false)
                audioManager.isWiredHeadsetOn = true
                currentOutputDevice = "有线耳机"
                deviceChangeListener?.invoke(currentOutputDevice)
                Log.d(TAG, "已切换到有线耳机（旧API）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "切换到有线耳机失败: ${e.message}")
            switchToSpeaker()
        }
    }

    fun switchToBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
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
                switchToSpeaker()
            } else {
                audioManager.setSpeakerphoneOn(false)
                audioManager.setBluetoothScoOn(true)
                audioManager.isWiredHeadsetOn = false
                currentOutputDevice = "蓝牙耳机"
                deviceChangeListener?.invoke(currentOutputDevice)
                Log.d(TAG, "已切换到蓝牙耳机（旧API）")
            }
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
        Log.d(TAG, "AudioManager已释放")
    }
}
