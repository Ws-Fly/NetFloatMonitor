package com.example.netfloatmonitor

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

class VoicePromptPlayer {

    companion object {
        private const val TAG = "VoicePromptPlayer"
    }

    fun playPilotPrompt() {
        Log.d(TAG, "🔊 播放飞行员提示音")
        playTone(ToneGenerator.TONE_DTMF_8)  // 880Hz
    }

    fun playObserverPrompt() {
        Log.d(TAG, "🔊 播放观察者提示音")
        playTone(ToneGenerator.TONE_DTMF_6)  // 660Hz
    }

    private fun playTone(toneType: Int) {
        var toneGenerator: ToneGenerator? = null
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
            toneGenerator.startTone(toneType, 300)
            Log.d(TAG, "✅ 提示音开始播放")
            
            // 等待播放完成
            Thread.sleep(350)
            
            Log.d(TAG, "✅ 提示音播放完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 播放失败: ${e.message}", e)
        } finally {
            try {
                toneGenerator?.release()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }
}
