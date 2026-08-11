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
            
            // 基频 + 谐波（更清晰的提示音）
            for (h in 1..5) {
                val amp = 1.0f / h
                sample += amp * sin(2.0f * PI.toFloat() * baseFreq * h * t)
            }
            
            // 包络（淡入淡出）
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

        Log.d(TAG, "提示音合成完成: ${if (baseFreq == 180f) "飞行员" else "观察者"}模式")
        return pcmBytes
    }

    private fun playPrompt(pcmData: ByteArray) {
        if (isPlaying) return
        isPlaying = true

        try {
            // ===== 关键修复：使用 STREAM_MUSIC 确保媒体通道播放 =====
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val originalVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            
            // 保存当前音量，播放时用 40% 音量
            val targetVolume = (maxVolume * 0.4f).toInt().coerceAtLeast(1)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)

            // ===== 修复：明确指定流类型为 STREAM_MUSIC =====
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize, pcmData.size * 2) else pcmData.size * 2

            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            audioTrack?.play()
            audioTrack?.write(pcmData, 0, pcmData.size)

            // 等待播放完成
            val totalFrames = pcmData.size / 2
            var playedFrames = 0
            val startTime = System.currentTimeMillis()
            val timeout = (DURATION_SECONDS * 1000 + 500).toLong()

            while (playedFrames < totalFrames && System.currentTimeMillis() - startTime < timeout) {
                playedFrames = audioTrack?.playbackHeadPosition ?: totalFrames
                Thread.sleep(20)
            }

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            // 恢复音量
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)

            Log.d(TAG, "提示音播放完成")

        } catch (e: Exception) {
            Log.e(TAG, "播放提示音异常: ${e.message}")
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
