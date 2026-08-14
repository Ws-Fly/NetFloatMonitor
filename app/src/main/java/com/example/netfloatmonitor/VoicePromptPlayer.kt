package com.example.netfloatmonitor

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class VoicePromptPlayer(private val context: Context) {

    companion object {
        private const val TAG = "VoicePromptPlayer"
    }

    // ===== TTS 实例 =====
    private var tts: TextToSpeech? = null
    private var isReady = false

    // ===== 播报队列（防止频繁调用冲突） =====
    private val speakQueue = ConcurrentLinkedQueue<String>()
    private var isSpeaking = false

    // ===== 回调监听 =====
    private var onSpeakStart: ((String) -> Unit)? = null
    private var onSpeakComplete: ((String) -> Unit)? = null

    init {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // 设置中文语言
                    val result = tts?.setLanguage(Locale.CHINESE)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "⚠️ 中文不支持，使用英文")
                        tts?.setLanguage(Locale.US)
                    }
                    // 设置语速（正常语速）
                    tts?.setSpeechRate(1.0f)
                    // 设置音调（正常）
                    tts?.setPitch(1.0f)
                    isReady = true
                    Log.d(TAG, "✅ TTS 初始化成功")

                    // 处理队列中积压的播报
                    processQueue()
                } else {
                    Log.e(TAG, "❌ TTS 初始化失败")
                    isReady = false
                }
            }

            // 设置播放监听
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS 开始: $utteranceId")
                        isSpeaking = true
                        onSpeakStart?.invoke(utteranceId ?: "")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS 完成: $utteranceId")
                        isSpeaking = false
                        onSpeakComplete?.invoke(utteranceId ?: "")
                        // 播放下一个队列中的内容
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
    // 2. 连接状态播报（预留）
    // ============================================================
    fun playConnected() {
        Log.d(TAG, "🔊 播报: 已连接")
        speak("已连接")
    }

    fun playDisconnected() {
        Log.d(TAG, "🔊 播报: 已断开")
        speak("已断开")
    }

    // ============================================================
    // 3. 链路状态播报（预留）
    // ============================================================
    fun playLinkUp() {
        Log.d(TAG, "🔊 播报: 链路已建立")
        speak("链路已建立")
    }

    fun playLinkDown() {
        Log.d(TAG, "🔊 播报: 链路已断开")
        speak("链路已断开")
    }

    // ============================================================
    // 4. 告警播报（预留）
    // ============================================================
    fun playWarning() {
        Log.d(TAG, "🔊 播报: 警告")
        speak("警告")
    }

    fun playWarningWithText(text: String) {
        Log.d(TAG, "🔊 播报: $text")
        speak(text)
    }

    // ============================================================
    // 5. 自定义播报
    // ============================================================
    fun speakText(text: String) {
        Log.d(TAG, "🔊 播报: $text")
        speak(text)
    }

    // ============================================================
    // 6. 停止播放
    // ============================================================
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
    // 7. 队列管理
    // ============================================================
    private fun speak(text: String) {
        if (text.isEmpty()) return

        // 如果队列中已有相同内容，不重复添加
        if (speakQueue.contains(text)) {
            Log.d(TAG, "⏭ 跳过重复播报: $text")
            return
        }

        speakQueue.offer(text)
        processQueue()
    }

    private fun processQueue() {
        // 如果正在播放或 TTS 未就绪，等待
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

            // 使用 utteranceId 用于回调识别
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
                // 失败时继续处理队列
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
    // 8. 回调设置
    // ============================================================
    fun setOnSpeakStart(listener: (String) -> Unit) {
        this.onSpeakStart = listener
    }

    fun setOnSpeakComplete(listener: (String) -> Unit) {
        this.onSpeakComplete = listener
    }

    // ============================================================
    // 9. 资源释放
    // ============================================================
    fun shutdown() {
        try {
            speakQueue.clear()
            isSpeaking = false
            tts?.stop()
            tts?.shutdown()
            tts = null
            isReady = false
            Log.d(TAG, "TTS 已释放")
        } catch (e: Exception) {
            // 忽略
        }
    }

    // ============================================================
    // 10. 状态检查
    // ============================================================
    fun isReady(): Boolean = isReady
    fun isSpeaking(): Boolean = isSpeaking
    fun getQueueSize(): Int = speakQueue.size
}
