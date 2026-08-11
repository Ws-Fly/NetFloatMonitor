package com.example.netfloatmonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class VoicePromptPlayer(private val context: Context) {

    companion object {
        private const val TAG = "VoicePromptPlayer"
        private const val SAMPLE_RATE = 16000
        private const val DURATION_SECONDS = 1.2f
        private const val FADE_DURATION = 0.1f
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    fun playPilotPrompt() {
        if (isPlaying) return
        val pcmData = generatePromptTone(baseFreq = 180f, isPilot = true)
        playPrompt(pcmData)
    }

    fun playObserverPrompt() {
        if (isPlaying) return
        val pcmData = generatePromptTone(baseFreq = 220f, isPilot = false)
        playPrompt(pcmData)
    }

    private fun generatePromptTone(baseFreq: Float, isPilot: Boolean): ByteArray {
        val numSamples = (SAMPLE_RATE * DURATION_SECONDS).toInt()
        val audioData = FloatArray(numSamples)

        val formants = if (isPilot) {
            floatArrayOf(450f, 1200f, 2200f, 3200f)
        } else {
            floatArrayOf(550f, 1500f, 2600f, 3800f)
        }
        val formantAmps = floatArrayOf(1.0f, 0.6f, 0.35f, 0.2f)

        val modFreq = if (isPilot) 4.5f else 5.5f
        val modDepth = 8f

        for (i in 0 until numSamples) {
            val t = i / SAMPLE_RATE.toFloat()
            var sample = 0f

            val freqMod = sin(2 * PI * modFreq * t).toFloat() * modDepth
            val currentFreq = baseFreq + freqMod
            sample += 0.5f * sin(2 * PI * currentFreq * t)

            for (j in formants.indices) {
                val amp = formantAmps[j]
                val formantFreq = formants[j]
                val formantMod = sin(2 * PI * modFreq * t * 0.5f).toFloat() * 10f
                sample += amp * sin(2 * PI * (formantFreq + formantMod) * t)
            }

            for (h in 2..4) {
                sample += 0.08f / h * sin(2 * PI * currentFreq * h * t)
            }

            val envelope = when {
                t < FADE_DURATION -> {
                    0.5f * (1 - cos(PI * t / FADE_DURATION)).toFloat()
                }
                t > DURATION_SECONDS - FADE_DURATION -> {
                    val fadeT = (t - (DURATION_SECONDS - FADE_DURATION)) / FADE_DURATION
                    0.5f * (1 + cos(PI * fadeT)).toFloat()
                }
                else -> 1.0f
            }

            audioData[i] = sample * 0.35f * envelope
        }

        val pcmBytes = ByteArray(numSamples * 2)
        for (i in audioData.indices) {
            val sample = (audioData[i] * 32767).toInt()
            val clampedSample = sample.coerceIn(-32768, 32767)
            pcmBytes[i * 2] = (clampedSample and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = (clampedSample shr 8 and 0xFF).toByte()
        }

        Log.d(TAG, "提示音合成完成: ${if (isPilot) "飞行员" else "观察者"}模式, ${pcmBytes.size} bytes")
        return pcmBytes
    }

    private fun playPrompt(pcmData: ByteArray) {
        if (isPlaying) return
        isPlaying = true

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val originalVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            
            val targetVolume = (maxVolume * 0.3f).toInt()
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)

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
            
            val bufferSize = maxOf(minBufferSize, pcmData.size * 2)

            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            audioTrack?.play()
            audioTrack?.write(pcmData, 0, pcmData.size)

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

            val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val targetRestoreVolume = originalVolume
            
            if (currentVolume < targetRestoreVolume) {
                for (vol in (currentVolume + 1)..targetRestoreVolume) {
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                    Thread.sleep(20)
                }
            }
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetRestoreVolume, 0)

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
