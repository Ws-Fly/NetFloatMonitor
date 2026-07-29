package com.example.netfloatmonitor

/**
 * 信号质量等级（5 格信号栏 + 断链）
 *
 * 判定规则（按附图）：
 *   RSSI ≤ 75 且 SNR ≥ 20  → 极佳（满格）
 *   RSSI 75~85 且 SNR 15~20 → 良好（4 格）
 *   RSSI 85~90 且 SNR 10~15 → 一般（3 格）
 *   RSSI 90~98 且 SNR 5~10  → 稍差（2 格）
 *   RSSI ≥ 98 或 SNR < 5    → 极差（1 格）
 *   AIR 无 SNR（断链）        → DISCONNECTED（显示 X）
 */
enum class SignalQuality(
    val bars: Int,
    val label: String,
    val colorHex: String
) {
    /** 断链 */
    DISCONNECTED(-1, "断链", "#FFE74C3C"),

    EXCELLENT(5, "极佳", "#FF1ABC9C"),
    GOOD(4, "良好", "#FF2ECC71"),
    FAIR(3, "一般", "#FFF39C12"),
    POOR(2, "稍差", "#FFE67E22"),
    BAD(1, "极差", "#FFE74C3C");

    val isDisconnected: Boolean get() = this == DISCONNECTED

    companion object {
        /**
         * 根据 RSSI + SNR 联合判定信号等级
         * @param rssi 信号强度（绝对值，1 最强，110 最弱）
         * @param snr  信噪比（dB，越大越好）
         * @param hasSnrData 是否收到了有效 SNR 数据（false = 断链）
         */
        fun fromRssiSnr(rssi: Float?, snr: Float?, hasSnrData: Boolean = true): SignalQuality {
            // 没有收到 SNR 数据 → 断链
            if (!hasSnrData || snr == null) return DISCONNECTED

            val r = rssi ?: 110f
            val s = snr

            return when {
                r <= 75f  && s >= 20f -> EXCELLENT
                r <= 85f  && s >= 15f -> GOOD
                r <= 90f  && s >= 10f -> FAIR
                r <= 98f  && s >= 5f  -> POOR
                else                   -> BAD
            }
        }

        /**
         * 用 JSON 原始字符串值判定（适配断链时 SNR="0" RSSI="110"）
         * @param rssiStr  RSSI 原始字符串（如 "72" 或 "110"）
         * @param snrStr   SNR 原始字符串（如 "22" 或 "0"）
         * @return DISCONNECTED 当且仅当 snr=="0" 或 rssi=="110"
         */
        fun fromRawStrings(rssiStr: String?, snrStr: String?): SignalQuality {
            val rssiVal = rssiStr?.toFloatOrNull()
            val snrVal = snrStr?.toFloatOrNull()

            // 断链特征：SNR="0" 或 RSSI="110"（或任一字段缺失/无效）
            if (snrStr == null || snrStr == "0" || snrVal == null) return DISCONNECTED
            if (rssiStr == null || rssiStr == "110" || rssiVal == null) return DISCONNECTED

            return fromRssiSnr(rssiVal, snrVal, hasSnrData = true)
        }

        /**
         * 取两端中较差的一档（DISCONNECTED 最严重）
         */
        fun worse(a: SignalQuality, b: SignalQuality): SignalQuality {
            // DISCONNECTED 最优先
            if (a == DISCONNECTED) return a
            if (b == DISCONNECTED) return b
            return if (a.ordinal < b.ordinal) b else a
        }
    }
}
