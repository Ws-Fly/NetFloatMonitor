package com.example.netfloatmonitor

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

/**
 * Opus 编解码器（使用 Concentus 纯 Java 实现）
 * 支持 8kHz/16kHz 采样率，码率可配置
 */
class OpusCodec : AudioCodec {
    
    companion object {
        private const val TAG = "OpusCodec"
        private const val OPUS_APPLICATION_VOIP = 2048
        private const val OPUS_SIGNAL_VOICE = 3001
        
        // 默认码率：24 kbps
        private var bitrateKbps = 24
    }
    
    // 编码器/解码器实例（延迟初始化）
    private var encoder: Any? = null
    private var decoder: Any? = null
    private var currentSampleRate: Int = 0
    private var isInitialized = false
    
    // 帧大小：60ms
    private fun getFrameSize(sampleRate: Int): Int {
        return sampleRate * 60 / 1000  // 60ms 帧
    }
    
    private fun initCodec(sampleRate: Int) {
        if (isInitialized && currentSampleRate == sampleRate) {
            return
        }
        
        try {
            // 尝试使用 Concentus 库（如果存在）
            // Concentus 是一个纯 Java Opus 编解码器
            val opusClass = Class.forName("org.concentus.Opus")
            val getEncoderMethod = opusClass.getDeclaredMethod(
                "getEncoder", 
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            
            val application = OPUS_APPLICATION_VOIP
            
            // 创建编码器
            encoder = getEncoderMethod.invoke(null, sampleRate, 1, application)
            
            // 设置码率
            val encoderObj = encoder
            if (encoderObj != null) {
                val setEncoderBitrateMethod = opusClass.getDeclaredMethod(
                    "encoderctl",
                    encoderObj.javaClass,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                // OPUS_SET_BITRATE_REQUEST = 4002
                setEncoderBitrateMethod.invoke(null, encoderObj, 4002, bitrateKbps * 1000)
            }
            
            // 创建解码器
            val getDecoderMethod = opusClass.getDeclaredMethod(
                "getDecoder",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            decoder = getDecoderMethod.invoke(null, sampleRate, 1)
            
            currentSampleRate = sampleRate
            isInitialized = true
            Log.d(TAG, "Concentus Opus 编解码器初始化成功: ${sampleRate}Hz, ${bitrateKbps}kbps")
            
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Concentus 库未找到，使用 G.711 降级方案")
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Opus 初始化失败: ${e.message}", e)
            isInitialized = false
        }
    }
    
    override fun encode(pcmData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            // 尝试使用 Concentus
            if (isInitialized && encoder != null) {
                try {
                    val opusClass = Class.forName("org.concentus.Opus")
                    val encodeMethod = opusClass.getDeclaredMethod(
                        "encode",
                        encoder!!.javaClass,
                        ShortArray::class.java,
                        Int::class.javaPrimitiveType,
                        ByteArray::class.java,
                        Int::class.javaPrimitiveType
                    )
                    
                    val frameSize = getFrameSize(sampleRate)
                    val expectedBytes = frameSize * 2
                    
                    // 准备输入数据
                    val pcmBuffer = if (pcmData.size < expectedBytes) {
                        ByteArray(expectedBytes).apply {
                            System.arraycopy(pcmData, 0, this, 0, pcmData.size)
                        }
                    } else {
                        pcmData
                    }
                    
                    // 转换为 Short 数组
                    val shorts = ShortArray(pcmBuffer.size / 2)
                    for (i in shorts.indices) {
                        val low = pcmBuffer[i * 2].toInt() and 0xFF
                        val high = pcmBuffer[i * 2 + 1].toInt() and 0xFF
                        shorts[i] = ((high shl 8) or low).toShort()
                    }
                    
                    // 编码输出缓冲区
                    val maxPayloadSize = 1024
                    val output = ByteArray(maxPayloadSize)
                    
                    val encodedSize = encodeMethod.invoke(
                        null,
                        encoder,
                        shorts,
                        0,
                        output,
                        maxPayloadSize
                    ) as Int
                    
                    if (encodedSize > 0) {
                        return output.copyOf(encodedSize)
                    }
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Concentus 编码失败，降级到 G.711: ${e.message}")
                }
            }
            
            // 降级方案：使用 G.711
            val g711Codec = G711Codec()
            return g711Codec.encode(pcmData, sampleRate)
            
        } catch (e: Exception) {
            Log.e(TAG, "Opus 编码失败: ${e.message}", e)
            return null
        }
    }
    
    override fun decode(encodedData: ByteArray, sampleRate: Int): ByteArray? {
        try {
            // 尝试使用 Concentus
            if (isInitialized && decoder != null) {
                try {
                    val opusClass = Class.forName("org.concentus.Opus")
                    val decodeMethod = opusClass.getDeclaredMethod(
                        "decode",
                        decoder!!.javaClass,
                        ByteArray::class.java,
                        Int::class.javaPrimitiveType,
                        ShortArray::class.java,
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    )
                    
                    val frameSize = getFrameSize(sampleRate)
                    val outputShorts = ShortArray(frameSize)
                    
                    val decodedFrames = decodeMethod.invoke(
                        null,
                        decoder,
                        encodedData,
                        encodedData.size,
                        outputShorts,
                        frameSize,
                        false
                    ) as Int
                    
                    if (decodedFrames > 0) {
                        // 转换为 Byte 数组
                        val pcmData = ByteArray(decodedFrames * 2)
                        for (i in 0 until decodedFrames) {
                            val sample = outputShorts[i].toInt()
                            val unsignedSample = if (sample < 0) sample + 0x10000 else sample
                            pcmData[i * 2] = (unsignedSample and 0xFF).toByte()
                            pcmData[i * 2 + 1] = (unsignedSample shr 8 and 0xFF).toByte()
                        }
                        return pcmData
                    }
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Concentus 解码失败，降级到 G.711: ${e.message}")
                }
            }
            
            // 降级方案：使用 G.711
            val g711Codec = G711Codec()
            return g711Codec.decode(encodedData, sampleRate)
            
        } catch (e: Exception) {
            Log.e(TAG, "Opus 解码失败: ${e.message}", e)
            return null
        }
    }
    
    override fun getName(): String = "Opus"
    
    /**
     * 设置码率（kbps）
     * 推荐值：16-32 kbps（通话质量）
     */
    fun setBitrate(bitrateKbps: Int) {
        this.bitrateKbps = bitrateKbps.coerceIn(6, 128)
        Log.d(TAG, "Opus 码率设置为: ${this.bitrateKbps} kbps")
    }
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
