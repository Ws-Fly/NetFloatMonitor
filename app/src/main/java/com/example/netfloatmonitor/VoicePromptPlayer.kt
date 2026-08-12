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
        private const val SAMPLE_RATE = 16000
    }

    fun playPilotPrompt() {
        playTone(180f)
    }

    fun playObserverPrompt() {
        playTone(220f)
    }

    private fun playTone(baseFreq: Float) {
        try {
            val duration = 0.6f
            val numSamples = (SAMPLE_RATE * duration).toInt()
            val audioData = FloatArray(numSamples)

            for (i in 0 until numSamples) {
                val t = i.toFloat() / SAMPLE_RATE.toFloat()
                var sample = 0f
                for (h in 1..6) {
                    val amp = 1.0f / h
                    sample += amp * sin(2.0f * PI.toFloat() * baseFreq * h * t)
                }
                sample += 0.3f * sin(2.0f * PI.toFloat() * baseFreq * 2 * t)
                val envelope = when {
                    t < 0.03f -> t / 0.03f
                    t > 0.55f -> 1.0f - (t - 0.55f) / 0.05f
                    else -> 1.0f
                }
                audioData[i] = sample * 0.5f * envelope
            }

            val pcmBytes = ByteArray(numSamples * 2)
            for (i in audioData.indices) {
                val sample = (audioData[i] * 32767).toInt().coerceIn(-32768, 32767)
                val unsigned = if (sample < 0) sample + 0x10000 else sample
                pcmBytes[i * 2] = (unsigned and 0xFF).toByte()
                pcmBytes[i * 2 + 1] = (unsigned shr 8 and 0xFF).toByte()
            }

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            val origVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: maxVol / 2
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = if (minBuf > 0) maxOf(minBuf, pcmBytes.size) else pcmBytes.size

            val track = AudioTrack(attrs, format, bufSize, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack 初始化失败")
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, origVol, 0)
                return
            }

            track.write(pcmBytes, 0, pcmBytes.size)
            track.play()
            Thread.sleep((duration * 1000 + 100).toLong())

            track.stop()
            track.release()
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, origVol, 0)

            Log.d(TAG, "✅ 提示音播放完成: ${if (baseFreq == 180f) "飞行员" else "观察者"}")

        } catch (e: Exception) {
            Log.e(TAG, "播放提示音失败: ${e.message}", e)
        }
    }
}
