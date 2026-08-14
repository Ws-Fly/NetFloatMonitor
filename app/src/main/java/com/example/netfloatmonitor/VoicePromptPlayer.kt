package com.example.netfloatmonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

class VoicePromptPlayer(private val context: Context) {

    companion object {
        private const val TAG = "VoicePromptPlayer"
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    init {
        Log.d(TAG, "VoicePromptPlayer 构造函数被调用, context=${context.packageName}")
    }

    fun playPilotPrompt() {
        Log.d(TAG, "🔊 playPilotPrompt() 被调用")
        playVoice(880f, 300, "飞行员")
    }

    fun playObserverPrompt() {
        Log.d(TAG, "🔊 playObserverPrompt() 被调用")
        playVoice(660f, 300, "观察者")
    }

    private fun playVoice(freq: Float, durationMs: Int, label: String) {
        try {
            Log.d(TAG, "🔊 开始播放: $label")

            val numSamples = SAMPLE_RATE * durationMs / 1000
            val pcmData = ByteArray(numSamples * 2)

            for (i in 0 until numSamples) {
                val t = i.toFloat() / SAMPLE_RATE
                var sample = 0.5f * sin(2.0f * PI.toFloat() * freq * t)
                sample += 0.3f * sin(2.0f * PI.toFloat() * (freq * 1.5f) * t)
                val envelope = when {
                    t < 0.02f -> t / 0.02f
                    t < 0.15f -> 1.0f
                    else -> 1.0f - (t - 0.15f) / (durationMs / 1000f - 0.15f) * 0.85f
                }
                sample *= envelope
                val intSample = (sample * 30000).toInt().coerceIn(-32768, 32767)
                val unsigned = if (intSample < 0) intSample + 0x10000 else intSample
                pcmData[i * 2] = (unsigned and 0xFF).toByte()
                pcmData[i * 2 + 1] = (unsigned shr 8 and 0xFF).toByte()
            }

            Log.d(TAG, "PCM 数据生成完成, size=${pcmData.size}")

            // ===== 获取 AudioManager =====
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                Log.e(TAG, "❌ AudioManager 为空")
                return
            }

            // 保存当前媒体音量并调大
            val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (maxVolume * 0.6f).toInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            Log.d(TAG, "音量: 原始=$originalVolume, 目标=$targetVolume, 最大=$maxVolume")

            // ===== 创建 AudioTrack =====
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize, pcmData.size * 2) else pcmData.size * 2

            Log.d(TAG, "minBufferSize=$minBufferSize, bufferSize=$bufferSize")

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioTrack 初始化失败, state=${audioTrack.state}")
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                return
            }

            Log.d(TAG, "✅ AudioTrack 初始化成功, bufferSize=$bufferSize")

            // ===== 写入并播放 =====
            val writeResult = audioTrack.write(pcmData, 0, pcmData.size)
            Log.d(TAG, "写入结果: $writeResult / ${pcmData.size} 字节")

            if (writeResult <= 0) {
                Log.e(TAG, "❌ 写入失败, writeResult=$writeResult")
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                return
            }

            audioTrack.play()
            Log.d(TAG, "AudioTrack 开始播放")

            Thread.sleep(durationMs + 200L)

            audioTrack.stop()
            audioTrack.release()

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)

            Log.d(TAG, "✅ 播放完成: $label")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 播放失败: ${e.message}", e)
        }
    }
}
