package com.example.netfloatmonitor

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class VoicePromptPlayer(private val context: Context) {

    companion object {
        private const val TAG = "VoicePromptPlayer"
        // ===== 播报限次控制 =====
        private const val MAX_SAME_ALERT_COUNT = 2
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val speakQueue = ConcurrentLinkedQueue<String>()
    private var isSpeaking = false

    // ===== 各告警播报计数 =====
    private var weakSignalCount = 0
    private var lostSignalCount = 0
    private var lastWeakState = false
    private var lastLostState = false

    init {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.CHINESE)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "⚠️ 中文不支持，使用英文")
                        tts?.setLanguage(Locale.US)
                    }
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.0f)
                    isReady = true
                    Log.d(TAG, "✅ TTS 初始化成功")
                    processQueue()
                } else {
                    Log.e(TAG, "❌ TTS 初始化失败")
                    isReady = false
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS 开始: $utteranceId")
                        isSpeaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS 完成: $utteranceId")
                        isSpeaking = false
                        processQueue()
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS 错误: $utteranceId")
                        isSpeaking = false
                        processQueue()
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ TTS 初始化异常: ${e.message}")
        }
    }

    // ============================================================
    // 1. 角色切换播报
    // ============================================================
    fun playPilotPrompt() {
        Log.d(TAG, "🔊 播报: 飞行员模式")
        speak("飞行员模式")
    }

    fun playObserverPrompt() {
        Log.d(TAG, "🔊 播报: 观察者模式")
        speak("观察者模式")
    }

    // ============================================================
    // 2. 信号状态播报（告警 + 恢复）
    // ============================================================

    /**
     * 处理信号状态变化
     * @param newState 新状态
     * @param minRssi 极小值 RSSI
     * @param snr SNR 值
     */
    fun handleSignalStateChange(newState: SignalState, minRssi: Int, snr: Int) {
        Log.d(TAG, "📶 信号状态变化: $newState, minRssi=$minRssi, snr=$snr")

        when (newState) {
            SignalState.WEAK -> {
                // 信号弱：只播报 2 次
                if (weakSignalCount < MAX_SAME_ALERT_COUNT && !lastWeakState) {
                    weakSignalCount++
                    lastWeakState = true
                    Log.d(TAG, "🔊 播报信号弱 (第 $weakSignalCount 次)")
                    speak("信号弱，请调整天线或高度")
                } else if (weakSignalCount >= MAX_SAME_ALERT_COUNT) {
                    Log.d(TAG, "⏭ 信号弱播报已达上限 (${MAX_SAME_ALERT_COUNT}次)，跳过")
                }
                // 重置丢失状态（因为已回到弱信号）
                lastLostState = false
                lostSignalCount = 0
            }

            SignalState.LOST -> {
                // 信号丢失：只播报 2 次
                if (lostSignalCount < MAX_SAME_ALERT_COUNT && !lastLostState) {
                    lostSignalCount++
                    lastLostState = true
                    Log.d(TAG, "🔊 播报信号丢失 (第 $lostSignalCount 次)")
                    speak("信号丢失，天空端失去连接")
                } else if (lostSignalCount >= MAX_SAME_ALERT_COUNT) {
                    Log.d(TAG, "⏭ 信号丢失播报已达上限 (${MAX_SAME_ALERT_COUNT}次)，跳过")
                }
                // 重置弱信号状态（因为已进入丢失）
                lastWeakState = false
                weakSignalCount = 0
            }

            SignalState.NORMAL -> {
                // ===== 从异常恢复到正常：播报 "信号已恢复" =====
                // 如果之前处于告警状态，播报恢复
                if (lastWeakState || lastLostState) {
                    Log.d(TAG, "🔊 播报: 信号已恢复")
                    speak("信号已恢复")
                }
                // 重置所有状态
                lastWeakState = false
                lastLostState = false
                weakSignalCount = 0
                lostSignalCount = 0
                Log.d(TAG, "✅ 信号恢复正常，状态已重置")
            }
        }
    }

    // ============================================================
    // 3. 通用播报方法
    // ============================================================
    fun playConnected() {
        Log.d(TAG, "🔊 播报: 已连接")
        speak("已连接")
    }

    fun playDisconnected() {
        Log.d(TAG, "🔊 播报: 已断开")
        speak("已断开")
    }

    fun playWarning() {
        Log.d(TAG, "🔊 播报: 警告")
        speak("警告")
    }

    fun speakText(text: String) {
        Log.d(TAG, "🔊 播报: $text")
        speak(text)
    }

    fun stop() {
        try {
            tts?.stop()
            isSpeaking = false
            speakQueue.clear()
            Log.d(TAG, "⏹ 播报已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止播报异常: ${e.message}")
        }
    }

    // ============================================================
    // 4. 队列管理
    // ============================================================
    private fun speak(text: String) {
        if (text.isEmpty()) return
        if (speakQueue.contains(text)) {
            Log.d(TAG, "⏭ 跳过重复播报: $text")
            return
        }
        speakQueue.offer(text)
        processQueue()
    }

    private fun processQueue() {
        if (isSpeaking || !isReady) {
            return
        }
        val text = speakQueue.poll() ?: return
        doSpeak(text)
    }

    private fun doSpeak(text: String) {
        try {
            if (tts == null) {
                Log.e(TAG, "❌ TTS 为空")
                return
            }

            val utteranceId = "${System.currentTimeMillis()}_$text"

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }

            if (result == TextToSpeech.SUCCESS) {
                Log.d(TAG, "✅ TTS 播报成功: $text")
                isSpeaking = true
            } else {
                Log.e(TAG, "❌ TTS 播报失败: result=$result")
                isSpeaking = false
                processQueue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ TTS 播报异常: ${e.message}")
            isSpeaking = false
            processQueue()
        }
    }

    // ============================================================
    // 5. 资源释放
    // ============================================================
    fun shutdown() {
        try {
            speakQueue.clear()
            isSpeaking = false
            weakSignalCount = 0
            lostSignalCount = 0
            lastWeakState = false
            lastLostState = false
            tts?.stop()
            tts?.shutdown()
            tts = null
            isReady = false
            Log.d(TAG, "TTS 已释放")
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun isReady(): Boolean = isReady
    fun isSpeaking(): Boolean = isSpeaking
    fun getQueueSize(): Int = speakQueue.size
}
