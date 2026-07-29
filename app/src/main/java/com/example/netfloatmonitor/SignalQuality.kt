package com.example.netfloatmonitor

/**
 * 信号质量等级（5 格信号栏）
 *
 * 判定规则（按附图）：
 *   RSSI ≤ 75 且 SNR ≥ 20  → 极佳（满格）
 *   RSSI 75~85 且 SNR 15~20 → 良好（4 格）
 *   RSSI 85~90 且 SNR 10~15 → 一般（3 格）
 *   RSSI 90~98 且 SNR 5~10  → 稍差（2 格）
 *   RSSI ≥ 98 或 SNR < 5    → 极差（1 格/断连）
 */
enum class SignalQuality(
    val bars: Int,
    val label: String,
    val colorHex: String
) {
    EXCELLENT(5, "极佳", "#FF1ABC9C"),
    GOOD(4, "良好", "#FF2ECC71"),
    FAIR(3, "一般", "#FFF39C12"),
    POOR(2, "稍差", "#FFE67E22"),
    BAD(1, "极差", "#FFE74C3C");

    companion object {
        fun fromRssiSnr(rssi: Float?, snr: Float?): SignalQuality {
            val r = rssi ?: 110f
            val s = snr ?: 0f
            return when {
                r <= 75f  && s >= 20f -> EXCELLENT
                r <= 85f  && s >= 15f -> GOOD
                r <= 90f  && s >= 10f -> FAIR
                r <= 98f  && s >= 5f  -> POOR
                else                   -> BAD
            }
        }

        fun worse(a: SignalQuality, b: SignalQuality): SignalQuality {
            return if (a.ordinal < b.ordinal) b else a
        }
    }
}
