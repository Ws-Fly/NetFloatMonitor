package com.example.netfloatmonitor

import android.util.Log

interface AudioCodec {
    fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray?
    fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray?
    fun getName(): String
}

class PcmCodec : AudioCodec {
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? = pcmData
    override fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray? = encodedData
    override fun getName(): String = "PCM"
}

class G711Codec : AudioCodec {
    
    companion object {
        private const val TAG = "G711Codec"
    }
    
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            if (pcmData.size % 2 != 0) {
                Log.w(TAG, "PCM数据长度不是偶数")
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

class OpusCodec : AudioCodec {
    companion object {
        private const val TAG = "OpusCodec"
    }
    
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            // 使用 G.711 作为 Opus 的降级方案
            // 实际生产环境应集成 libopus
            Log.d(TAG, "Opus 编码 (使用 G.711 降级): 数据大小 ${pcmData.size} 字节")
            val g711Codec = G711Codec()
            return g711Codec.encode(pcmData, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Opus 编码失败: ${e.message}")
            return null
        }
    }
    
    override fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            val g711Codec = G711Codec()
            return g711Codec.decode(encodedData, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Opus 解码失败: ${e.message}")
            return null
        }
    }
    
    override fun getName(): String = "Opus"
}

object CodecFactory {
    fun getCodec(codecName: String): AudioCodec {
        return when (codecName) {
            "G.711" -> G711Codec()
            "Opus" -> OpusCodec()
            else -> PcmCodec()
        }
    }
}
