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
        private const val DURATION_SECONDS = 0.8f
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    fun playPilotPrompt() {
        if (isPlaying) return
        val pcmData = generatePromptTone(180f)
        playPrompt(pcmData)
    }

    fun playObserverPrompt() {
        if (isPlaying) return
        val pcmData = generatePromptTone(220f)
        playPrompt(pcmData)
    }

    private fun generatePromptTone(baseFreq: Float): ByteArray {
        val numSamples = (SAMPLE_RATE * DURATION_SECONDS).toInt()
        val audioData = FloatArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toFloat() / SAMPLE_RATE.toFloat()
            var sample = 0f
            
            for (h in 1..5) {
                val amp = 1.0f / h
                sample += amp * sin(2.0f * PI.toFloat() * baseFreq * h * t)
            }
            
            val envelope = when {
                t < 0.08f -> t / 0.08f
                t > 0.72f -> (1.0f - (t - 0.72f) / 0.08f)
                else -> 1.0f
            }
            
            audioData[i] = sample * 0.4f * envelope
        }

        val pcmBytes = ByteArray(numSamples * 2)
        for (i in audioData.indices) {
            val sample = (audioData[i] * 32767).toInt()
            val clampedSample = sample.coerceIn(-32768, 32767)
            pcmBytes[i * 2] = (clampedSample and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = (clampedSample shr 8 and 0xFF).toByte()
        }

        Log.d(TAG, "提示音合成完成: ${if (baseFreq == 180f) "飞行员" else "观察者"}")
        return pcmBytes
    }

    private fun playPrompt(pcmData: ByteArray) {
        if (isPlaying) return
        isPlaying = true

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: maxVolume / 2
            
            val targetVolume = (maxVolume * 0.5f).toInt().coerceAtLeast(1)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            var minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            if (minBufferSize <= 0) {
                minBufferSize = pcmData.size * 2
            }
            
            val bufferSize = maxOf(minBufferSize, pcmData.size * 2)

            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack 初始化失败")
                isPlaying = false
                return
            }

            audioTrack?.play()
            
            var offset = 0
            val chunkSize = 1024
            while (offset < pcmData.size) {
                val end = minOf(offset + chunkSize, pcmData.size)
                val chunk = pcmData.copyOfRange(offset, end)
                audioTrack?.write(chunk, 0, chunk.size)
                offset = end
            }

            Thread.sleep((DURATION_SECONDS * 1000).toLong() + 100)

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)

            Log.d(TAG, "✅ 提示音播放完成")

        } catch (e: Exception) {
            Log.e(TAG, "播放提示音异常: ${e.message}", e)
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
            } catch (e2: Exception) { /* ignore */ }
        } finally {
            isPlaying = false
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) { /* ignore */ }
        isPlaying = false
    }
}
