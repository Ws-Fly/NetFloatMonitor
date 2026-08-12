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

    fun playPilotPrompt() {
        // ===== 播放 "飞行员模式" 语音 =====
        playVoice("飞行员模式", 880f, 300)
    }

    fun playObserverPrompt() {
        // ===== 播放 "观察者模式" 语音 =====
        playVoice("观察者模式", 660f, 300)
    }

    private fun playVoice(text: String, freq: Float, durationMs: Int) {
        try {
            Log.d(TAG, "🔊 开始播放语音播报: $text")

            // ===== 使用双音多频模拟语音（更清晰） =====
            val numSamples = SAMPLE_RATE * durationMs / 1000
            val pcmData = ByteArray(numSamples * 2)

            for (i in 0 until numSamples) {
                val t = i.toFloat() / SAMPLE_RATE
                // 双音叠加（类似电话拨号音效，清晰可辨）
                var sample = 0.45f * sin(2.0f * PI.toFloat() * freq * t)
                sample += 0.35f * sin(2.0f * PI.toFloat() * (freq * 1.5f) * t)
                // 包络：快速起音 + 平滑衰减
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

            // ===== 播放音频 =====
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val originalVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            val targetVolume = (maxVolume * 0.5f).toInt().coerceAtLeast(1)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)

            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize, pcmData.size * 2) else pcmData.size * 2

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
                Log.e(TAG, "AudioTrack 初始化失败")
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                return
            }

            audioTrack.write(pcmData, 0, pcmData.size)
            audioTrack.play()
            Thread.sleep(durationMs + 200L)
            audioTrack.stop()
            audioTrack.release()

            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)

            Log.d(TAG, "✅ 语音播报完成: $text")

        } catch (e: Exception) {
            Log.e(TAG, "播放语音失败: ${e.message}", e)
        }
    }
}
