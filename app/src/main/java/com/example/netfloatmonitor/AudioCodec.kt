package com.example.netfloatmonitor

import android.util.Log

interface AudioCodec {
    fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray?
    fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray?
    fun getName(): String
}

// ===== PCM =====
class PcmCodec : AudioCodec {
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? = pcmData
    override fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray? = encodedData
    override fun getName(): String = "PCM"
}

// ===== G.711 μ-law（修复符号位） =====
class G711Codec : AudioCodec {
    
    companion object {
        private const val TAG = "G711Codec"
        private const val BIAS = 0x84
        private val ULAW_MAX = 0x1FFF
        private val ULAW_ZERO = 0x84
    }
    
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            if (pcmData.size < 2) return null
            
            val out = ByteArray(pcmData.size / 2)
            for (i in out.indices) {
                val low = pcmData[i * 2].toInt() and 0xFF
                val high = pcmData[i * 2 + 1].toInt() and 0xFF
                var sample = (high shl 8) or low
                if (sample >= 0x8000) sample -= 0x10000
                out[i] = linearToULaw(sample)
            }
            return out
        } catch (e: Exception) {
            Log.e(TAG, "编码失败: ${e.message}")
            return null
        }
    }
    
    override fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            if (encodedData.isEmpty()) return null
            
            val out = ByteArray(encodedData.size * 2)
            for (i in encodedData.indices) {
                val sample = uLawToLinear(encodedData[i].toInt() and 0xFF)
                // 削波保护
                val clamped = sample.coerceIn(-32768, 32767)
                val unsigned = if (clamped < 0) clamped + 0x10000 else clamped
                out[i * 2] = (unsigned and 0xFF).toByte()
                out[i * 2 + 1] = (unsigned shr 8 and 0xFF).toByte()
            }
            return out
        } catch (e: Exception) {
            Log.e(TAG, "解码失败: ${e.message}")
            return null
        }
    }
    
    override fun getName(): String = "G.711"
    
    private fun linearToULaw(sample: Int): Byte {
        var sign = 0
        var exponent = 0
        var mantissa = 0
        var absSample = sample
        
        if (sample < 0) {
            absSample = -sample
            sign = 0x80
        }
        
        // 限制最大范围
        if (absSample > ULAW_MAX) absSample = ULAW_MAX
        
        // 计算指数和尾数
        when {
            absSample < 0x100 -> { exponent = 0; mantissa = absSample shr 4 }
            absSample < 0x200 -> { exponent = 1; mantissa = absSample shr 5 }
            absSample < 0x400 -> { exponent = 2; mantissa = absSample shr 6 }
            absSample < 0x800 -> { exponent = 3; mantissa = absSample shr 7 }
            absSample < 0x1000 -> { exponent = 4; mantissa = absSample shr 8 }
            absSample < 0x2000 -> { exponent = 5; mantissa = absSample shr 9 }
            absSample < 0x4000 -> { exponent = 6; mantissa = absSample shr 10 }
            else -> { exponent = 7; mantissa = absSample shr 11 }
        }
        
        // 构建 μ-law 字节
        val ulawByte = sign or (exponent shl 4) or (mantissa and 0x0F)
        return (ulawByte xor 0xFF).toByte()
    }
    
    private fun uLawToLinear(ulaw: Int): Int {
        // 反转 μ-law 编码
        val ulawInv = ulaw xor 0xFF
        val sign = if ((ulawInv and 0x80) != 0) -1 else 1
        val exponent = (ulawInv shr 4) and 0x07
        val mantissa = ulawInv and 0x0F
        
        val value = when (exponent) {
            0 -> (mantissa shl 4) or 0x08
            1 -> (mantissa shl 5) or 0x10
            2 -> (mantissa shl 6) or 0x20
            3 -> (mantissa shl 7) or 0x40
            4 -> (mantissa shl 8) or 0x80
            5 -> (mantissa shl 9) or 0x100
            6 -> (mantissa shl 10) or 0x200
            7 -> (mantissa shl 11) or 0x400
            else -> 0
        }
        
        return sign * value
    }
}

// ===== ADPCM（修复符号位和编码表） =====
class AdpcmCodec : AudioCodec {
    
    companion object {
        private const val TAG = "AdpcmCodec"
        
        private val INDEX_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )
        
        private val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024
        )
    }
    
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            if (pcmData.size < 2) return null
            
            val sampleCount = pcmData.size / 2
            val outSize = (sampleCount + 1) / 2 + 2
            val out = ByteArray(outSize)
            
            var predSample = 0
            var predIndex = 0
            
            // 初始状态
            out[0] = (predSample shr 8 and 0xFF).toByte()
            out[1] = (predSample and 0xFF).toByte()
            
            var byteIdx = 2
            var bitPos = 0
            var curByte = 0
            
            for (i in 0 until sampleCount) {
                val low = pcmData[i * 2].toInt() and 0xFF
                val high = pcmData[i * 2 + 1].toInt() and 0xFF
                var sample = (high shl 8) or low
                if (sample >= 0x8000) sample -= 0x10000
                
                val diff = sample - predSample
                val step = STEP_TABLE[predIndex.coerceIn(0, STEP_TABLE.size - 1)]
                
                // 编码为 4 位
                var code = if (diff < 0) 8 else 0
                var absDiff = if (diff < 0) -diff else diff
                var diffStep = step
                
                for (j in 3 downTo 0) {
                    if (absDiff >= diffStep) {
                        code = code or (1 shl j)
                        absDiff -= diffStep
                    }
                    diffStep = diffStep shr 1
                }
                
                // 解码以更新预测值
                var diffNew = 0
                diffStep = step
                for (j in 3 downTo 0) {
                    if ((code and (1 shl j)) != 0) {
                        diffNew += diffStep
                    }
                    diffStep = diffStep shr 1
                }
                if ((code and 8) != 0) diffNew = -diffNew
                predSample += diffNew
                predSample = predSample.coerceIn(-32768, 32767)
                predIndex += INDEX_TABLE[code and 0x0F]
                predIndex = predIndex.coerceIn(0, STEP_TABLE.size - 1)
                
                // 写入 4 位
                if (bitPos == 0) {
                    curByte = (code and 0x0F) shl 4
                    bitPos = 4
                } else {
                    curByte = curByte or (code and 0x0F)
                    out[byteIdx] = curByte.toByte()
                    byteIdx++
                    bitPos = 0
                }
            }
            
            if (bitPos > 0) {
                out[byteIdx] = curByte.toByte()
            }
            
            return out
        } catch (e: Exception) {
            Log.e(TAG, "ADPCM编码失败: ${e.message}")
            return null
        }
    }
    
    override fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            if (encodedData.size < 3) return null
            
            // 恢复初始状态
            var predSample = ((encodedData[0].toInt() and 0xFF) shl 8) or (encodedData[1].toInt() and 0xFF)
            if (predSample >= 0x8000) predSample -= 0x10000
            var predIndex = 0
            
            val maxSamples = (encodedData.size - 2) * 2
            val out = ByteArray(maxSamples * 2)
            var count = 0
            
            var byteIdx = 2
            var bitPos = 4
            var curByte = encodedData[byteIdx].toInt() and 0xFF
            
            while (byteIdx < encodedData.size && count < maxSamples) {
                val code = if (bitPos == 4) {
                    bitPos = 0
                    (curByte shr 4) and 0x0F
                } else {
                    byteIdx++
                    bitPos = 4
                    if (byteIdx < encodedData.size) {
                        curByte = encodedData[byteIdx].toInt() and 0xFF
                    }
                    curByte and 0x0F
                }
                
                val step = STEP_TABLE[predIndex.coerceIn(0, STEP_TABLE.size - 1)]
                
                var diffNew = 0
                var diffStep = step
                for (j in 3 downTo 0) {
                    if ((code and (1 shl j)) != 0) {
                        diffNew += diffStep
                    }
                    diffStep = diffStep shr 1
                }
                if ((code and 8) != 0) diffNew = -diffNew
                predSample += diffNew
                predSample = predSample.coerceIn(-32768, 32767)
                predIndex += INDEX_TABLE[code and 0x0F]
                predIndex = predIndex.coerceIn(0, STEP_TABLE.size - 1)
                
                val unsigned = if (predSample < 0) predSample + 0x10000 else predSample
                out[count * 2] = (unsigned and 0xFF).toByte()
                out[count * 2 + 1] = (unsigned shr 8 and 0xFF).toByte()
                count++
            }
            
            return out.copyOf(count * 2)
        } catch (e: Exception) {
            Log.e(TAG, "ADPCM解码失败: ${e.message}")
            return null
        }
    }
    
    override fun getName(): String = "ADPCM"
}

object CodecFactory {
    fun getCodec(codecName: String): AudioCodec {
        return when (codecName.trim()) {
            "G.711" -> G711Codec()
            "ADPCM" -> AdpcmCodec()
            else -> PcmCodec()
        }
    }
}
