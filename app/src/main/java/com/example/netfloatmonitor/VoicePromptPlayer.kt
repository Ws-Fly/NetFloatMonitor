package com.example.netfloatmonitor

import android.content.Context
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
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    fun playPilotPrompt() {
        playTone(440f, 300)
    }

    fun playObserverPrompt() {
        playTone(660f, 300)
    }

    private fun playTone(freq: Float, durationMs: Int) {
        try {
            val numSamples = SAMPLE_RATE * durationMs / 1000
            val pcmData = ByteArray(numSamples * 2)

            for (i in 0 until numSamples) {
                val t = i.toFloat() / SAMPLE_RATE
                val sample = (0.3f * sin(2.0f * PI.toFloat() * freq * t) * 32767).toInt()
                val clamped = sample.coerceIn(-32768, 32767)
                val unsigned = if (clamped < 0) clamped + 0x10000 else clamped
                pcmData[i * 2] = (unsigned and 0xFF).toByte()
                pcmData[i * 2 + 1] = (unsigned shr 8 and 0xFF).toByte()
            }

            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
            val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize, pcmData.size) else pcmData.size
            
            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_OUT,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STATIC
            )

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack 初始化失败")
                return
            }

            audioTrack.write(pcmData, 0, pcmData.size)
            audioTrack.play()
            Thread.sleep(durationMs + 100L)
            audioTrack.stop()
            audioTrack.release()

            Log.d(TAG, "✅ 提示音播放完成: ${if (freq == 440f) "飞行员" else "观察者"}")

        } catch (e: Exception) {
            Log.e(TAG, "播放提示音失败: ${e.message}", e)
        }
    }
}
