package com.example.netfloatmonitor

import android.util.Log

interface AudioCodec {
    fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray?
    fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray?
    fun getName(): String
}

// ===== PCM 无损 =====
class PcmCodec : AudioCodec {
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? = pcmData
    override fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray? = encodedData
    override fun getName(): String = "PCM"
}

// ===== G.711 μ-law 压缩（2:1 压缩比） =====
class G711Codec : AudioCodec {
    
    companion object {
        private const val TAG = "G711Codec"
    }
    
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            if (pcmData.size % 2 != 0) {
                return null
            }
            
            val encodedSize = pcmData.size / 2
            val encoded = ByteArray(encodedSize)
            
            for (i in 0 until encodedSize) {
                val low = pcmData[i * 2].toInt() and 0xFF
                val high = pcmData[i * 2 + 1].toInt() and 0xFF
                val sample = (high shl 8) or low
                val signedSample = if (sample >= 0x8000) sample - 0x10000 else sample
                encoded[i] = linearToULaw(signedSample)
            }
            
            return encoded
        } catch (e: Exception) {
            Log.e(TAG, "G.711编码失败: ${e.message}")
            return null
        }
    }
    
    override fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            val pcmSize = encodedData.size * 2
            val pcmData = ByteArray(pcmSize)
            
            for (i in encodedData.indices) {
                val pcmSample = uLawToLinear(encodedData[i])
                val clampedSample = pcmSample.coerceIn(-32768, 32767)
                val unsignedSample = if (clampedSample < 0) clampedSample + 0x10000 else clampedSample
                pcmData[i * 2] = (unsignedSample and 0xFF).toByte()
                pcmData[i * 2 + 1] = (unsignedSample shr 8 and 0xFF).toByte()
            }
            
            return pcmData
        } catch (e: Exception) {
            Log.e(TAG, "G.711解码失败: ${e.message}")
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
        
        if (absSample < 0x100) {
            exponent = 0
            mantissa = (absSample shr 4) and 0x0F
        } else if (absSample < 0x200) {
            exponent = 1
            mantissa = (absSample shr 5) and 0x0F
        } else if (absSample < 0x400) {
            exponent = 2
            mantissa = (absSample shr 6) and 0x0F
        } else if (absSample < 0x800) {
            exponent = 3
            mantissa = (absSample shr 7) and 0x0F
        } else if (absSample < 0x1000) {
            exponent = 4
            mantissa = (absSample shr 8) and 0x0F
        } else if (absSample < 0x2000) {
            exponent = 5
            mantissa = (absSample shr 9) and 0x0F
        } else if (absSample < 0x4000) {
            exponent = 6
            mantissa = (absSample shr 10) and 0x0F
        } else {
            exponent = 7
            mantissa = (absSample shr 11) and 0x0F
        }
        
        return (sign or (exponent shl 4) or mantissa).toByte()
    }
    
    private fun uLawToLinear(uLaw: Byte): Int {
        val uLawValue = uLaw.toInt() and 0xFF
        val sign = if (uLawValue and 0x80 != 0) -1 else 1
        val exponent = (uLawValue shr 4) and 0x07
        val mantissa = uLawValue and 0x0F
        
        val value = when (exponent) {
            0 -> mantissa shl 4
            1 -> (mantissa shl 5) or 0x80
            2 -> (mantissa shl 6) or 0x100
            3 -> (mantissa shl 7) or 0x200
            4 -> (mantissa shl 8) or 0x400
            5 -> (mantissa shl 9) or 0x800
            6 -> (mantissa shl 10) or 0x1000
            7 -> (mantissa shl 11) or 0x2000
            else -> 0
        }
        
        return if (sign > 0) value else -value
    }
}

// ===== ADPCM 压缩（4:1 压缩比，音质优于 G.711） =====
class AdpcmCodec : AudioCodec {
    
    companion object {
        private const val TAG = "AdpcmCodec"
        
        // ADPCM 量化表
        private val INDEX_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )
        
        private val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14,
            16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66,
            73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411,
            1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024
        )
    }
    
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            if (pcmData.size < 2) return null
            
            val sampleCount = pcmData.size / 2
            val encodedSize = (sampleCount + 1) / 2  // 每个采样点 4bit
            val encoded = ByteArray(encodedSize + 2)  // +2 保存初始状态
            
            var predSample = 0
            var predIndex = 0
            
            // 保存初始状态
            encoded[0] = (predSample shr 8 and 0xFF).toByte()
            encoded[1] = (predSample and 0xFF).toByte()
            
            var byteIndex = 2
            var bitPos = 0
            var currentByte = 0
            
            for (i in 0 until sampleCount) {
                val low = pcmData[i * 2].toInt() and 0xFF
                val high = pcmData[i * 2 + 1].toInt() and 0xFF
                val sample = if (high >= 0x80) (high shl 8) or low - 0x10000 else (high shl 8) or low
                
                val diff = sample - predSample
                var step = STEP_TABLE[predIndex.coerceIn(0, STEP_TABLE.size - 1)]
                
                var sign = 0
                var absDiff = diff
                if (diff < 0) {
                    sign = 8
                    absDiff = -diff
                }
                
                var code = sign
                var diffStep = step
                for (j in 3 downTo 0) {
                    if (absDiff >= diffStep) {
                        code = code or (1 shl j)
                        absDiff -= diffStep
                    }
                    diffStep = diffStep shr 1
                }
                
                // 更新预测值
                var diffNew = 0
                diffStep = step
                for (j in 3 downTo 0) {
                    if ((code and (1 shl j)) != 0) {
                        diffNew += diffStep
                    }
                    diffStep = diffStep shr 1
                }
                if ((code and 8) != 0) {
                    diffNew = -diffNew
                }
                predSample += diffNew
                predSample = predSample.coerceIn(-32768, 32767)
                
                predIndex += INDEX_TABLE[code and 0x0F]
                predIndex = predIndex.coerceIn(0, STEP_TABLE.size - 1)
                
                // 写入 4bit
                if (bitPos == 0) {
                    currentByte = (code and 0x0F) shl 4
                    bitPos = 4
                } else {
                    currentByte = currentByte or (code and 0x0F)
                    encoded[byteIndex] = currentByte.toByte()
                    byteIndex++
                    bitPos = 0
                }
            }
            
            // 处理最后一个半字节
            if (bitPos > 0) {
                encoded[byteIndex] = currentByte.toByte()
            }
            
            // 裁剪到实际大小
            val resultSize = if (bitPos > 0) byteIndex + 1 else byteIndex
            return encoded.copyOf(resultSize)
            
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
            val pcmData = ByteArray(maxSamples * 2)
            var sampleCount = 0
            
            var byteIndex = 2
            var bitPos = 4
            var currentByte = encodedData[byteIndex].toInt() and 0xFF
            
            while (byteIndex < encodedData.size && sampleCount < maxSamples) {
                var code: Int
                if (bitPos == 4) {
                    code = (currentByte shr 4) and 0x0F
                    bitPos = 0
                } else {
                    code = currentByte and 0x0F
                    byteIndex++
                    bitPos = 4
                    if (byteIndex < encodedData.size) {
                        currentByte = encodedData[byteIndex].toInt() and 0xFF
                    }
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
                if ((code and 8) != 0) {
                    diffNew = -diffNew
                }
                predSample += diffNew
                predSample = predSample.coerceIn(-32768, 32767)
                
                predIndex += INDEX_TABLE[code and 0x0F]
                predIndex = predIndex.coerceIn(0, STEP_TABLE.size - 1)
                
                val unsignedSample = if (predSample < 0) predSample + 0x10000 else predSample
                pcmData[sampleCount * 2] = (unsignedSample and 0xFF).toByte()
                pcmData[sampleCount * 2 + 1] = (unsignedSample shr 8 and 0xFF).toByte()
                sampleCount++
            }
            
            return pcmData.copyOf(sampleCount * 2)
            
        } catch (e: Exception) {
            Log.e(TAG, "ADPCM解码失败: ${e.message}")
            return null
        }
    }
    
    override fun getName(): String = "ADPCM"
}

object CodecFactory {
    fun getCodec(codecName: String): AudioCodec {
        return when (codecName) {
            "G.711" -> G711Codec()
            "ADPCM" -> AdpcmCodec()
            else -> PcmCodec()
        }
    }
}
