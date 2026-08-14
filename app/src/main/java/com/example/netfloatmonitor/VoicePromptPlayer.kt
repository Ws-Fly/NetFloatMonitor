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
        private const val MAX_SAME_ALERT_COUNT = 2
        private const val DEBOUNCE_THRESHOLD = 3
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val speakQueue = ConcurrentLinkedQueue<String>()
    private var isSpeaking = false

    private var weakSignalCount = 0
    private var lostSignalCount = 0

    private var weakDebounceCount = 0
    private var lostDebounceCount = 0
    private var normalDebounceCount = 0
    private var isWeakState = false
    private var isLostState = false

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

    fun playPilotPrompt() {
        Log.d(TAG, "🔊 播报: 飞行员模式")
        speak("飞行员模式")
    }

    fun playObserverPrompt() {
        Log.d(TAG, "🔊 播报: 观察者模式")
        speak("观察者模式")
    }

    fun handleSignalStateChange(newState: SignalState, minRssi: Int, snr: Int) {
        Log.d(TAG, "📶 信号状态: $newState, minRssi=$minRssi, snr=$snr, " +
                "weakDebounce=$weakDebounceCount, lostDebounce=$lostDebounceCount, " +
                "normalDebounce=$normalDebounceCount")

        when (newState) {
            SignalState.WEAK -> {
                weakDebounceCount++
                lostDebounceCount = 0
                normalDebounceCount = 0

                if (weakDebounceCount >= DEBOUNCE_THRESHOLD && !isWeakState) {
                    isWeakState = true
                    isLostState = false
                    
                    if (weakSignalCount < MAX_SAME_ALERT_COUNT) {
                        weakSignalCount++
                        Log.d(TAG, "🔊 播报信号弱 (连续${weakDebounceCount}次确认, 第${weakSignalCount}次)")
                        speak("信号弱，请调整天线或高度")
                    }
                }
                lostSignalCount = 0
            }

            SignalState.LOST -> {
                lostDebounceCount++
                weakDebounceCount = 0
                normalDebounceCount = 0

                if (lostDebounceCount >= DEBOUNCE_THRESHOLD && !isLostState) {
                    isLostState = true
                    isWeakState = false
                    
                    if (lostSignalCount < MAX_SAME_ALERT_COUNT) {
                        lostSignalCount++
                        Log.d(TAG, "🔊 播报信号丢失 (连续${lostDebounceCount}次确认, 第${lostSignalCount}次)")
                        speak("信号丢失，天空端失去连接")
                    }
                }
                weakSignalCount = 0
            }

            SignalState.NORMAL -> {
                normalDebounceCount++
                weakDebounceCount = 0
                lostDebounceCount = 0

                if (normalDebounceCount >= DEBOUNCE_THRESHOLD) {
                    if (isWeakState || isLostState) {
                        Log.d(TAG, "🔊 播报: 信号已恢复 (连续${normalDebounceCount}次确认)")
                        speak("信号已恢复")
                    }
                    isWeakState = false
                    isLostState = false
                    weakSignalCount = 0
                    lostSignalCount = 0
                }
            }
        }
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

    fun shutdown() {
        try {
            speakQueue.clear()
            isSpeaking = false
            weakSignalCount = 0
            lostSignalCount = 0
            weakDebounceCount = 0
            lostDebounceCount = 0
            normalDebounceCount = 0
            isWeakState = false
            isLostState = false
            tts?.stop()
            tts?.shutdown()
            tts = null
            isReady = false
            Log.d(TAG, "TTS 已释放")
        } catch (e: Exception) {
            // 忽略
        }
    }
}
