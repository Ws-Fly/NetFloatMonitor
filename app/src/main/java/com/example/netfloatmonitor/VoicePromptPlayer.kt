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
        playPrompt(880f, 250, "飞行员")
    }

    fun playObserverPrompt() {
        playPrompt(660f, 250, "观察者")
    }

    private fun playPrompt(freq: Float, durationMs: Int, label: String) {
        try {
            val numSamples = SAMPLE_RATE * durationMs / 1000
            val pcmData = ByteArray(numSamples * 2)

            for (i in 0 until numSamples) {
                val t = i.toFloat() / SAMPLE_RATE
                var sample = 0.4f * sin(2.0f * PI.toFloat() * freq * t)
                sample += 0.3f * sin(2.0f * PI.toFloat() * (freq * 1.5f) * t)
                val envelope = when {
                    t < 0.02f -> t / 0.02f
                    t < 0.15f -> 1.0f
                    else -> 1.0f - (t - 0.15f) / (durationMs / 1000f - 0.15f) * 0.9f
                }
                sample *= envelope
                val intSample = (sample * 30000).toInt().coerceIn(-32768, 32767)
                val unsigned = if (intSample < 0) intSample + 0x10000 else intSample
                pcmData[i * 2] = (unsigned and 0xFF).toByte()
                pcmData[i * 2 + 1] = (unsigned shr 8 and 0xFF).toByte()
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize, pcmData.size * 2) else pcmData.size * 2

            val audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack 初始化失败")
                return
            }

            audioTrack.write(pcmData, 0, pcmData.size)
            audioTrack.play()
            Thread.sleep(durationMs + 150L)
            audioTrack.stop()
            audioTrack.release()

            Log.d(TAG, "✅ 提示音播放完成: $label")

        } catch (e: Exception) {
            Log.e(TAG, "播放提示音失败: ${e.message}", e)
        }
    }
}
